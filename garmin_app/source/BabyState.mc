// Central state + the optimistic-update logic, mirroring the Wear OS app
// (watch_app/.../SleepViewModel.kt). Server is the source of truth; between
// polls we tick locally on System.getTimer() (monotonic ms).
//
// Pause semantics match Huckleberry (NOT the design handoff): pause freezes
// the timer, resume jumps it forward to wall-clock including the pause gap.

import Toybox.Lang;
import Toybox.System;
import Toybox.Communications;
import Toybox.WatchUi;

module Baby {

    enum {
        PHASE_LOADING,
        PHASE_AWAKE,
        PHASE_SLEEPING,
        PHASE_PAUSED,
        PHASE_ERROR
    }

    var phase = PHASE_LOADING;
    var sessionElapsedSec = null;   // Float|Null — at fetch (sleeping/paused)
    var awakeElapsedSec = null;     // Float|Null — at fetch (awake)
    var sessionStartedAt = null;    // Float|Null — epoch sec, for resume jump
    var serverTimeAtFetch = null;   // Float|Null — epoch sec
    var fetchedAtMs = 0;            // System.getTimer() at fetch
    var inFlight = false;
    var lastError = null;           // String|Null — transient

    // Snapshot for snap-back when an optimistic request fails.
    var _backup = null;

    // -- display ------------------------------------------------------------

    // Elapsed seconds to show right now, ticking locally from the snapshot.
    function displayElapsedSec() {
        var localDelta = (System.getTimer() - fetchedAtMs) / 1000.0;
        if (phase == PHASE_SLEEPING) {
            return sessionElapsedSec == null ? null : sessionElapsedSec + localDelta;
        } else if (phase == PHASE_PAUSED) {
            return sessionElapsedSec; // frozen
        } else if (phase == PHASE_AWAKE) {
            return awakeElapsedSec == null ? null : awakeElapsedSec + localDelta;
        }
        return null;
    }

    // -- actions (optimistic) -------------------------------------------------

    function refresh() {
        if (inFlight) { return; }
        inFlight = true;
        BabyApi.request("GET", "/status");
    }

    // action: "start" | "pause" | "resume" | "stop"
    function act(action) {
        if (inFlight) { return; }
        _backup = _snapshot();
        _predict(action);
        lastError = null;
        inFlight = true;
        BabyApi.request("POST", "/sleep/" + action);
        WatchUi.requestUpdate();
    }

    // The contextual primary action for the current phase.
    function primaryAction() {
        if (phase == PHASE_AWAKE) { return "start"; }
        if (phase == PHASE_SLEEPING) { return "pause"; }
        if (phase == PHASE_PAUSED) { return "resume"; }
        return null; // LOADING/ERROR -> refresh instead
    }

    function _predict(action) {
        var nowMs = System.getTimer();
        if (action.equals("start")) {
            phase = PHASE_SLEEPING;
            sessionElapsedSec = 0.0;
            fetchedAtMs = nowMs;
        } else if (action.equals("pause")) {
            var frozen = displayElapsedSec();
            phase = PHASE_PAUSED;
            if (frozen != null) { sessionElapsedSec = frozen; }
            fetchedAtMs = nowMs;
        } else if (action.equals("resume")) {
            // Server semantics: elapsed jumps to wall-clock since start.
            if (serverTimeAtFetch != null && sessionStartedAt != null) {
                var epochNow = serverTimeAtFetch + (nowMs - fetchedAtMs) / 1000.0;
                sessionElapsedSec = epochNow - sessionStartedAt;
            }
            phase = PHASE_SLEEPING;
            fetchedAtMs = nowMs;
        } else if (action.equals("stop")) {
            phase = PHASE_AWAKE;
            sessionElapsedSec = null;
            awakeElapsedSec = 0.0; // "just now"
            fetchedAtMs = nowMs;
        }
    }

    // -- reconcile (called by BabyApi) ----------------------------------------

    function onStatus(data) {
        inFlight = false;
        _backup = null;
        var state = data["state"];
        if (state == null) { state = "awake"; }
        if (state.equals("sleeping")) {
            phase = PHASE_SLEEPING;
        } else if (state.equals("paused")) {
            phase = PHASE_PAUSED;
        } else {
            phase = PHASE_AWAKE;
        }
        sessionElapsedSec = _f(data["session_elapsed_sec"]);
        sessionStartedAt = _f(data["session_started_at"]);
        serverTimeAtFetch = _f(data["server_time"]);
        var lastEnd = _f(data["last_sleep_end"]);
        if (lastEnd != null && serverTimeAtFetch != null) {
            awakeElapsedSec = serverTimeAtFetch - lastEnd;
        } else {
            awakeElapsedSec = null;
        }
        fetchedAtMs = System.getTimer();
        lastError = null;
        WatchUi.requestUpdate();
    }

    function onFailure(code) {
        inFlight = false;
        if (_backup != null) {
            _restore(_backup);  // snap back — the visible revert is the signal
            _backup = null;
            lastError = "phone nearby?";
        } else if (phase == PHASE_LOADING) {
            phase = PHASE_ERROR;
            lastError = "phone nearby?";
        } else {
            lastError = "sync failed";
        }
        WatchUi.requestUpdate();
    }

    // -- helpers --------------------------------------------------------------

    function _snapshot() {
        return {
            "phase" => phase,
            "sessionElapsedSec" => sessionElapsedSec,
            "awakeElapsedSec" => awakeElapsedSec,
            "sessionStartedAt" => sessionStartedAt,
            "serverTimeAtFetch" => serverTimeAtFetch,
            "fetchedAtMs" => fetchedAtMs
        };
    }

    function _restore(s) {
        phase = s["phase"];
        sessionElapsedSec = s["sessionElapsedSec"];
        awakeElapsedSec = s["awakeElapsedSec"];
        sessionStartedAt = s["sessionStartedAt"];
        serverTimeAtFetch = s["serverTimeAtFetch"];
        fetchedAtMs = s["fetchedAtMs"];
    }

    function _f(v) {
        if (v == null) { return null; }
        return v.toFloat();
    }

    // -- formatting (per the Squish design spec) ------------------------------

    function formatAwake(sec) {
        var s = sec.toNumber();
        if (s < 60) { return "just now"; }
        var h = s / 3600;
        var m = (s % 3600) / 60;
        if (h == 0) { return m.format("%d") + "m"; }
        return h.format("%d") + "h " + m.format("%d") + "m";
    }

    function formatSleep(sec) {
        var s = sec.toNumber();
        var h = s / 3600;
        var m = (s % 3600) / 60;
        var ss = s % 60;
        if (h == 0) { return m.format("%d") + ":" + ss.format("%02d"); }
        return h.format("%d") + ":" + m.format("%02d") + ":" + ss.format("%02d");
    }
}
