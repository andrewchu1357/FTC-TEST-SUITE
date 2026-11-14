package org.ftctestbed.shim;

import android.app.Activity;
import android.app.Application;

/** The simulator's stand-in for the Robot Controller app's activity and application. */
public final class SimActivity extends Activity {
    public static final SimActivity INSTANCE = new SimActivity();
    public static final Application APPLICATION = new Application();

    private SimActivity() {}
}
