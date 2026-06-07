// Single view: flat-color MIP adaptation of the Squish design.
// 64-color transflective display — no gradients, no idle animation; a clean
// state-colored disc, big timer, action hint, STOP hint when active.

import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

class BabyView extends WatchUi.View {

    // Colors chosen on the MIP RGB222 grid, echoing Dusk->Dawn.
    // {bg, blob, text-on-blob, accent}
    const AWAKE_BG = 0x550055;     // dark plum
    const AWAKE_BLOB = 0xFFAAAA;   // coral
    const SLEEP_BG = 0x000055;     // deep indigo
    const SLEEP_BLOB = 0xAAAAFF;   // periwinkle
    const PAUSE_BG = 0x000000;
    const PAUSE_BLOB = 0xAAAAAA;   // grey-violet (grey on MIP)

    var _timer;

    function initialize() {
        View.initialize();
    }

    function onShow() {
        Baby.refresh();
        _timer = new Timer.Timer();
        _timer.start(method(:onTick), 1000, true);
    }

    function onHide() {
        if (_timer != null) {
            _timer.stop();
            _timer = null;
        }
    }

    function onTick() {
        WatchUi.requestUpdate();
    }

    function onUpdate(dc) {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var cx = w / 2;

        var bg = AWAKE_BG;
        var blob = AWAKE_BLOB;
        if (Baby.phase == Baby.PHASE_SLEEPING) {
            bg = SLEEP_BG; blob = SLEEP_BLOB;
        } else if (Baby.phase == Baby.PHASE_PAUSED) {
            bg = PAUSE_BG; blob = PAUSE_BLOB;
        }

        dc.setColor(bg, bg);
        dc.clear();

        // Blob: flat disc, ~61% of screen like the design.
        var r = (w * 0.61 / 2).toNumber();
        var cy = h / 2 - (h * 0.04).toNumber();
        dc.setColor(blob, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(cx, cy, r);

        // Timer.
        var timerText = "...";
        var elapsed = Baby.displayElapsedSec();
        if (Baby.phase == Baby.PHASE_ERROR) {
            timerText = "!";
        } else if (Baby.phase == Baby.PHASE_AWAKE && elapsed != null) {
            timerText = Baby.formatAwake(elapsed);
        } else if ((Baby.phase == Baby.PHASE_SLEEPING || Baby.phase == Baby.PHASE_PAUSED)
                   && elapsed != null) {
            timerText = Baby.formatSleep(elapsed);
        }
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, cy - (r * 0.32).toNumber(), Graphics.FONT_NUMBER_MEDIUM,
            timerText, Graphics.TEXT_JUSTIFY_CENTER);

        // Action hint inside the blob.
        var hint = "";
        if (Baby.phase == Baby.PHASE_AWAKE) { hint = "START sleep"; }
        else if (Baby.phase == Baby.PHASE_SLEEPING) { hint = "START pause"; }
        else if (Baby.phase == Baby.PHASE_PAUSED) { hint = "START resume"; }
        else if (Baby.phase == Baby.PHASE_ERROR) { hint = "START retry"; }
        dc.drawText(cx, cy + (r * 0.30).toNumber(), Graphics.FONT_TINY,
            hint, Graphics.TEXT_JUSTIFY_CENTER);

        // Stop hint at the bottom while a session is live.
        if (Baby.phase == Baby.PHASE_SLEEPING || Baby.phase == Baby.PHASE_PAUSED) {
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, h - (h * 0.13).toNumber(), Graphics.FONT_TINY,
                "DOWN stop", Graphics.TEXT_JUSTIFY_CENTER);
        }

        // Transient error line.
        if (Baby.lastError != null && Baby.phase != Baby.PHASE_ERROR) {
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, (h * 0.08).toNumber(), Graphics.FONT_XTINY,
                Baby.lastError, Graphics.TEXT_JUSTIFY_CENTER);
        }
    }
}
