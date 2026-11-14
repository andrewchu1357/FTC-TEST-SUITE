package android.app;

import android.content.Context;
import android.view.View;

/**
 * The Robot Controller app's screen. The simulator has none, so robot code gets this stand-in
 * (AppUtil.getInstance().getActivity(), hardwareMap.appContext): its views exist but show nothing.
 */
public abstract class Activity extends Context {
    public View findViewById(int id) {
        return new View();
    }

    /** No UI thread in the simulator: the action runs right away. */
    public void runOnUiThread(Runnable action) {
        action.run();
    }
}
