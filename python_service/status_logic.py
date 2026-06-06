"""Pure functions turning raw Huckleberry/Firestore data into the /status payload.

Free of I/O and library imports so the state mapping is unit-testable.

Huckleberry's timer document (sleep/{child_uid} -> timer):
    active          bool
    paused          bool
    timerStartTime  float, epoch MILLISECONDS
    timerEndTime    float, epoch MILLISECONDS — set at the pause moment

IMPORTANT pause semantics (verified against py-huckleberry-api source):
Huckleberry does NOT accumulate pause segments. Pausing freezes the visible
timer at timerEndTime; resuming un-pauses without subtracting the gap, so the
running elapsed jumps forward to include the paused period, and the final
interval duration is plain wall-clock start->end. This deviates from the
design handoff's baseElapsed model — the server (this logic) is the source of
truth and clients must mirror it, or they'd disagree with the Huckleberry
phone app.
"""

from __future__ import annotations

from typing import Any

AWAKE = "awake"
SLEEPING = "sleeping"
PAUSED = "paused"


def compute_status(
    timer: dict[str, Any],
    last_sleep_end: float | None,
    now: float,
) -> dict[str, Any]:
    """Map the raw timer dict to the /status response payload.

    All times in the payload are epoch seconds (floats).
    """
    active = bool(timer.get("active"))
    start_ms = timer.get("timerStartTime")

    if not active or not start_ms:
        return {
            "state": AWAKE,
            "session_started_at": None,
            "session_elapsed_sec": None,
            "last_sleep_end": last_sleep_end,
            "server_time": now,
        }

    started = float(start_ms) / 1000.0
    if bool(timer.get("paused")):
        end_ms = timer.get("timerEndTime")
        # Frozen at the pause moment; see module docstring for why the gap
        # is not subtracted after resume.
        frozen_until = float(end_ms) / 1000.0 if end_ms else now
        state, elapsed = PAUSED, max(0.0, frozen_until - started)
    else:
        state, elapsed = SLEEPING, max(0.0, now - started)

    return {
        "state": state,
        "session_started_at": started,
        "session_elapsed_sec": elapsed,
        "last_sleep_end": last_sleep_end,
        "server_time": now,
    }


def latest_interval_end(intervals: list[Any]) -> float | None:
    """End time (epoch seconds) of the most recent completed sleep interval.

    Accepts FirebaseSleepIntervalData models or plain dicts with
    `start` (epoch seconds) and `duration` (seconds).
    """
    best: float | None = None
    for iv in intervals:
        start = _field(iv, "start")
        duration = _field(iv, "duration")
        if start is None or duration is None:
            continue
        end = float(start) + float(duration)
        if best is None or end > best:
            best = end
    return best


def _field(obj: Any, name: str) -> Any:
    if isinstance(obj, dict):
        return obj.get(name)
    return getattr(obj, name, None)
