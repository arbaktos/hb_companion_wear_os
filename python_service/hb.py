"""Adapter isolating all py-huckleberry-api access.

Everything that touches the library lives here, so when the library changes
(or our reading of its internals turns out wrong at deploy time), this is the
only file to fix. main.py knows only HuckleberryService.
"""

from __future__ import annotations

import asyncio
import logging
import time
from datetime import UTC, datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

import aiohttp
from huckleberry_api import HuckleberryAPI

from child_select import match_child_by_nickname
from state import Store
from status_logic import compute_status, latest_interval_end

log = logging.getLogger(__name__)

# How far back to look for the most recent completed interval (last_sleep_end).
LOOKBACK_DAYS = 7

# Store key for the timezone last reported by a capable client (Wear OS). This
# is the source of truth across restarts and is shared by all clients — see
# _apply_timezone for the reconcile rules.
_TZ_KEY = "timezone"

_ACTIONS = ("start", "pause", "resume", "stop")


class HuckleberryService:
    def __init__(self, api: HuckleberryAPI, child_uid: str, store: Store) -> None:
        self.api = api
        self.child_uid = child_uid
        self.store = store
        # The IANA zone currently applied to self.api._timezone. Tracked here so
        # reconcile is a cheap string compare instead of touching the library.
        self._current_tz: str | None = None
        # One user, one gRPC channel — serialize upstream calls.
        self._lock = asyncio.Lock()

    @classmethod
    async def create(
        cls,
        *,
        email: str,
        password: str,
        tz: str,
        websession: aiohttp.ClientSession,
        store: Store,
        child_nickname: str | None = None,
    ) -> "HuckleberryService":
        api = HuckleberryAPI(
            email=email, password=password, timezone=tz, websession=websession
        )

        # Restore the persisted session if we have one; fall back to a full
        # email/password authenticate. (Token attrs are plain instance fields
        # on HuckleberryAPI — verified against the library source.)
        refresh_token = store.get("refresh_token")
        user_uid = store.get("user_uid")
        if refresh_token and user_uid:
            api.refresh_token = refresh_token
            api.user_uid = user_uid
            try:
                await api.refresh_session_token()
            except Exception:
                await api.authenticate()
        else:
            await api.authenticate()

        # Cache key includes the nickname so switching HB_CHILD_NICKNAME (e.g.
        # to a test child and back) re-resolves instead of reusing a stale uid.
        cache_key = f"child_uid:{child_nickname or '<first>'}"
        child_uid = store.get(cache_key)
        if not child_uid:
            child_uid = await cls._resolve_child(api, child_nickname)
            store.set(cache_key, child_uid)

        svc = cls(api, child_uid, store)
        # A previously reported zone (from Wear OS) outranks the HB_TIMEZONE
        # seed: the env var only matters until a capable client first reports.
        # persist=False — we are reading the store, not writing a new value.
        svc._apply_timezone(store.get(_TZ_KEY) or tz, persist=False)
        svc._persist_tokens()
        return svc

    @staticmethod
    async def _resolve_child(api: HuckleberryAPI, nickname: str | None) -> str:
        user = await api.get_user()
        if user is None or not user.childList:
            raise RuntimeError("Huckleberry account has no children configured")
        if nickname is None:
            return user.childList[0].cid

        cid = match_child_by_nickname(user.childList, nickname)
        if cid:
            return cid
        # Nicknames can be unset on the user doc — fall back to the child
        # documents' display names.
        names = []
        for ref in user.childList:
            child = await api.get_child(ref.cid)
            name = getattr(child, "childsName", None) if child else None
            if name and name.strip().casefold() == nickname.strip().casefold():
                return ref.cid
            names.append(name or ref.nickname or ref.cid)
        raise RuntimeError(
            f"no child named {nickname!r}; available: {names}"
        )

    async def status(self, tz: str | None = None) -> dict[str, Any]:
        async with self._lock:
            self._apply_timezone(tz, persist=True)
            await self._fresh_session()
            return await self._build_status()

    async def action(self, name: str, tz: str | None = None) -> dict[str, Any]:
        """Run a sleep action, then return the resulting status (saves the
        client a second round trip)."""
        if name not in _ACTIONS:
            raise ValueError(f"unknown action {name!r}")
        async with self._lock:
            self._apply_timezone(tz, persist=True)
            await self._fresh_session()
            fn = {
                "start": self.api.start_sleep,
                "pause": self.api.pause_sleep,
                "resume": self.api.resume_sleep,
                "stop": self.api.complete_sleep,
            }[name]
            await fn(self.child_uid)
            return await self._build_status()

    # -- internals ----------------------------------------------------------

    def _apply_timezone(self, tz: str | None, *, persist: bool) -> None:
        """Reconcile the upstream client's zone with a client-reported one.

        Called under self._lock (it mutates the shared api object). The rules:
          * tz is None  -> client sent no hint (e.g. Garmin): keep current zone.
          * tz unchanged -> already correct, nothing to do.
          * tz changed   -> validate, apply to the live client, and (when
                            persist) save it as the new shared source of truth.
        An unparseable zone is ignored rather than fatal: a bogus header must
        not knock out a working session.
        """
        if not tz or tz == self._current_tz:
            return
        try:
            zone = ZoneInfo(tz)
        except Exception:
            log.warning("ignoring invalid timezone from client: %r", tz)
            return
        self.api._timezone = zone  # noqa: SLF001 — see module docstring
        self._current_tz = tz
        if persist:
            self.store.set(_TZ_KEY, tz)
        log.info("timezone now %s (persist=%s)", tz, persist)

    async def _fresh_session(self) -> None:
        await self.api.ensure_session()
        self._persist_tokens()

    def _persist_tokens(self) -> None:
        for key, value in (
            ("refresh_token", self.api.refresh_token),
            ("user_uid", self.api.user_uid),
        ):
            if value and value != self.store.get(key):
                self.store.set(key, value)

    async def _build_status(self) -> dict[str, Any]:
        timer = await self._read_timer()
        last_end = await self._last_sleep_end()
        return compute_status(timer, last_end, time.time())

    async def _read_timer(self) -> dict[str, Any]:
        # The library exposes no public getter for the live timer; this is the
        # same read its own sleep methods perform internally.
        client = await self.api._get_firestore_client()  # noqa: SLF001
        doc = await client.collection("sleep").document(self.child_uid).get()
        raw = doc.to_dict() or {}
        timer = raw.get("timer") or {}
        return timer if isinstance(timer, dict) else dict(timer)

    async def _last_sleep_end(self) -> float | None:
        now = datetime.now(UTC)
        intervals = await self.api.list_sleep_intervals(
            self.child_uid, now - timedelta(days=LOOKBACK_DAYS), now
        )
        return latest_interval_end(intervals)
