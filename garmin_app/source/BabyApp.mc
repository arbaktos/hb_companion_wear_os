import Toybox.Application;
import Toybox.Lang;
import Toybox.WatchUi;

class BabyApp extends Application.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    function getInitialView() {
        return [new BabyView(), new BabyDelegate()];
    }
}
