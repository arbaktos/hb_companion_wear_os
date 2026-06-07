// Input: START button (or screen tap) = contextual primary action,
// DOWN button = stop, BACK = exit (system default).

import Toybox.Lang;
import Toybox.WatchUi;

class BabyDelegate extends WatchUi.BehaviorDelegate {

    function initialize() {
        BehaviorDelegate.initialize();
    }

    // START key and screen tap both land here.
    function onSelect() {
        var action = Baby.primaryAction();
        if (action != null) {
            Baby.act(action);
        } else {
            Baby.refresh(); // LOADING/ERROR
        }
        return true;
    }

    // DOWN key arrives as the next-page BEHAVIOR (BehaviorDelegate translates
    // it before raw onKey would see it).
    function onNextPage() {
        return _stop();
    }

    function onKey(keyEvent) {
        if (keyEvent.getKey() == WatchUi.KEY_DOWN) {
            return _stop();
        }
        return false;
    }

    function _stop() {
        if (Baby.phase == Baby.PHASE_SLEEPING || Baby.phase == Baby.PHASE_PAUSED) {
            Baby.act("stop");
            return true;
        }
        return false;
    }
}
