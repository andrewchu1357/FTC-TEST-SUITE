package android.content;

import android.content.res.AssetManager;
import android.content.res.Resources;

/**
 * The Robot Controller app's context. In the simulator there's no Android app, so this is a
 * stand-in: it has no assets or resources (getIdentifier() finds nothing), which robot code
 * that looks up a sound or a layout already handles, as it would for a missing resource.
 */
public abstract class Context {
    private static final Resources RESOURCES = new Resources();

    public AssetManager getAssets() {
        return null;
    }

    public Resources getResources() {
        return RESOURCES;
    }

    public String getPackageName() {
        return "com.qualcomm.ftcrobotcontroller";
    }

    public Context getApplicationContext() {
        return this;
    }
}
