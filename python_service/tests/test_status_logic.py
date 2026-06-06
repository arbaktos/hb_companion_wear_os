from status_logic import compute_status, latest_interval_end

NOW = 1_750_000_000.0  # arbitrary fixed epoch seconds


def test_empty_timer_is_awake():
    out = compute_status({}, last_sleep_end=NOW - 3600, now=NOW)
    assert out["state"] == "awake"
    assert out["session_started_at"] is None
    assert out["session_elapsed_sec"] is None
    assert out["last_sleep_end"] == NOW - 3600
    assert out["server_time"] == NOW


def test_inactive_timer_is_awake_even_with_stale_start():
    timer = {"active": False, "timerStartTime": (NOW - 500) * 1000}
    assert compute_status(timer, None, NOW)["state"] == "awake"


def test_active_timer_is_sleeping_with_live_elapsed():
    timer = {"active": True, "paused": False, "timerStartTime": (NOW - 900) * 1000}
    out = compute_status(timer, None, NOW)
    assert out["state"] == "sleeping"
    assert out["session_started_at"] == NOW - 900
    assert out["session_elapsed_sec"] == 900


def test_paused_timer_freezes_at_pause_moment():
    timer = {
        "active": True,
        "paused": True,
        "timerStartTime": (NOW - 900) * 1000,
        "timerEndTime": (NOW - 300) * 1000,  # paused 5 min ago
    }
    out = compute_status(timer, None, NOW)
    assert out["state"] == "paused"
    assert out["session_elapsed_sec"] == 600  # frozen, not 900


def test_paused_without_end_time_falls_back_to_now():
    timer = {"active": True, "paused": True, "timerStartTime": (NOW - 900) * 1000}
    out = compute_status(timer, None, NOW)
    assert out["state"] == "paused"
    assert out["session_elapsed_sec"] == 900


def test_clock_skew_never_goes_negative():
    timer = {"active": True, "paused": False, "timerStartTime": (NOW + 60) * 1000}
    assert compute_status(timer, None, NOW)["session_elapsed_sec"] == 0


def test_latest_interval_end_picks_most_recent():
    intervals = [
        {"start": NOW - 10_000, "duration": 1200},
        {"start": NOW - 5_000, "duration": 600},   # ends at NOW - 4400 (latest)
        {"start": NOW - 7_000, "duration": 100},
    ]
    assert latest_interval_end(intervals) == NOW - 4400


def test_latest_interval_end_empty_and_malformed():
    assert latest_interval_end([]) is None
    assert latest_interval_end([{"start": None, "duration": 5}]) is None


def test_latest_interval_end_accepts_objects():
    class IV:
        start = NOW - 1000
        duration = 400

    assert latest_interval_end([IV()]) == NOW - 600
