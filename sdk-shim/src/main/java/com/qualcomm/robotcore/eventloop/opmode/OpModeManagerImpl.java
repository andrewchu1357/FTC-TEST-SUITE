package com.qualcomm.robotcore.eventloop.opmode;

import android.app.Activity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Robot Controller's OpMode manager. Simulator stand-in: listeners registered here hear
 * about every OpMode the simulator runs (Road Runner's FlightRecorder logs this way).
 */
public class OpModeManagerImpl implements OpModeManagerNotifier {
    private static final OpModeManagerImpl INSTANCE = new OpModeManagerImpl();
    private static final List<Notifications> LISTENERS = new CopyOnWriteArrayList<Notifications>();

    /** The idle OpMode the Robot Controller runs between OpModes. */
    public static class DefaultOpMode extends OpMode {
        @Override public void init() {}
        @Override public void loop() {}
    }

    public static OpModeManagerImpl getOpModeManagerOfActivity(Activity activity) {
        return INSTANCE;
    }

    @Override
    public OpMode registerListener(Notifications listener) {
        if (!LISTENERS.contains(listener)) LISTENERS.add(listener);
        return null;
    }

    @Override
    public void unregisterListener(Notifications listener) {
        LISTENERS.remove(listener);
    }

    /** Simulator: the listeners, called around each OpMode it runs. */
    public static List<Notifications> simListeners() {
        return LISTENERS;
    }
}
