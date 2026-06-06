"""Tiny SQLite key/value store: refresh token, user_uid, cached child_uid.

Everything here is reconstructible from the Huckleberry credentials — losing
this file just means a full re-auth + one get_user() call on next startup.
"""

from __future__ import annotations

import sqlite3


class Store:
    def __init__(self, path: str) -> None:
        self._db = sqlite3.connect(path)
        self._db.execute(
            "CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT)"
        )
        self._db.commit()

    def get(self, key: str) -> str | None:
        row = self._db.execute(
            "SELECT value FROM kv WHERE key = ?", (key,)
        ).fetchone()
        return row[0] if row else None

    def set(self, key: str, value: str) -> None:
        self._db.execute(
            "INSERT INTO kv (key, value) VALUES (?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (key, value),
        )
        self._db.commit()
