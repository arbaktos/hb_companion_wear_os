"""Pure child-selection logic (kept import-light for unit tests).

The user document's childList entries have `cid` and an optional `nickname`;
the child documents themselves have `childsName`. Selection by name checks
the nickname here; hb.py falls back to fetching child docs when nicknames
are missing.
"""

from __future__ import annotations

from typing import Any


def match_child_by_nickname(children: list[Any], nickname: str) -> str | None:
    """Return the cid whose nickname matches (case-insensitive), else None."""
    wanted = nickname.strip().casefold()
    for child in children:
        nick = _field(child, "nickname")
        if nick and nick.strip().casefold() == wanted:
            cid = _field(child, "cid")
            if cid:
                return cid
    return None


def _field(obj: Any, name: str) -> Any:
    if isinstance(obj, dict):
        return obj.get(name)
    return getattr(obj, name, None)
