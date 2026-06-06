"""Adapter isolating all py-huckleberry-api access.

Everything that touches the library lives here, so when the library changes
(or our reading of its internals turns out wrong at deploy time), this is the
only file to fix. main.py knows only HuckleberryService.
"""

from __future__ import annotations

import asyncio
import time
from datetime import UTC, datetime, timedelta
from typing import Any

import aiohttp
from huckleberry_api import HuckleberryAPI

from state import Store
from status_logic import compute_status, latest_interval_end

# How far back to look for the most recent completed interval (last_sleep_end).
LOOKBACK_DAYS = 7

_ACTIONS = ("start", "pause", "resume", "stop")


class HuckleberryService:
    def __init__(self, api: HuckleberryAPI, child_uid: str, store: Store) -> None:
        self.api = api
        self.child_uid = child_uid
        self.store = store
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

        child_uid = store.get("child_uid")
        if not child_uid:
            user = await api.get_user()
            if user is None or not user.childList:
                raise RuntimeError("Huckleberry account has no children configured")
            child_uid = user.childList[0].cid
            store.set("child_uid", child_uid)

        svc = cls(api, child_uid, store)
        svc._persist_tokens()
        return svc

    async def status(self) -> dict[str, Any]:
        async with self._lock:
            await self._fresh_session()
            return await self._build_status()

    async def action(self, name: str) -> dict[str, Any]:
        """Run a sleep action, then return the resulting status (saves the
        client a second round trip)."""
        if name not in _ACTIONS:
            raise ValueError(f"unknown action {name!r}")
        async with self._lock:
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
