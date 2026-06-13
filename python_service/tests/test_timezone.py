"""Reconcile rules for the shared, client-reported timezone (hb._apply_timezone).

Importing hb pulls in huckleberry_api; skip where the library isn't installed
(matches the rest of the suite, which only touches pure modules).
"""

import pytest

pytest.importorskip("huckleberry_api")

from hb import _TZ_KEY, HuckleberryService  # noqa: E402


class _FakeApi:
    """Just enough of HuckleberryAPI for the reconcile path: a settable zone."""

    def __init__(self) -> None:
        self._timezone = None


class _CountingStore:
    def __init__(self, initial: dict | None = None) -> None:
        self._kv = dict(initial or {})
        self.writes = 0

    def get(self, key: str):
        return self._kv.get(key)

    def set(self, key: str, value: str) -> None:
        self.writes += 1
        self._kv[key] = value


def _svc(store: _CountingStore | None = None) -> HuckleberryService:
    return HuckleberryService(_FakeApi(), "child-uid", store or _CountingStore())


def test_new_zone_is_applied_and_persisted():
    store = _CountingStore()
    svc = _svc(store)

    svc._apply_timezone("Europe/London", persist=True)

    assert str(svc.api._timezone) == "Europe/London"
    assert svc._current_tz == "Europe/London"
    assert store.get(_TZ_KEY) == "Europe/London"


def test_unchanged_zone_is_a_noop():
    store = _CountingStore()
    svc = _svc(store)
    svc._apply_timezone("Europe/London", persist=True)
    writes_after_first = store.writes

    svc._apply_timezone("Europe/London", persist=True)

    assert store.writes == writes_after_first  # no redundant write


def test_none_keeps_current_zone():
    svc = _svc()
    svc._apply_timezone("America/New_York", persist=True)

    svc._apply_timezone(None, persist=True)  # e.g. a Garmin request

    assert svc._current_tz == "America/New_York"


def test_invalid_zone_is_ignored_not_fatal():
    store = _CountingStore()
    svc = _svc(store)
    svc._apply_timezone("America/New_York", persist=True)
    writes_after_valid = store.writes

    svc._apply_timezone("Not/AZone", persist=True)

    assert svc._current_tz == "America/New_York"  # working session preserved
    assert store.writes == writes_after_valid  # bogus value not persisted


def test_startup_seed_applies_without_persisting():
    store = _CountingStore()
    svc = _svc(store)

    svc._apply_timezone("UTC", persist=False)  # how create() seeds the client

    assert str(svc.api._timezone) == "UTC"
    assert store.writes == 0  # seed must not masquerade as a reported value
