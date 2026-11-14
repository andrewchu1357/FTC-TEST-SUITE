package org.ftctestbed.shim.vision;

import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;
import org.ftctestbed.shim.SimHooks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Per-robot vision bookkeeping that the SDK keeps per Robot Controller app: the webcams it has,
 * the LiveView ids in use, and which processors are attached to a portal.
 */
public final class SimVision {
    private SimVision() {}

    /** Which webcams the calling robot thread's robot has. */
    public interface WebcamLister {
        List<WebcamName> webcams();
    }

    private static volatile WebcamLister lister = new WebcamLister() {
        @Override public List<WebcamName> webcams() { return Collections.emptyList(); }
    };
    private static final Object ROBOT_1 = new Object();
    private static final Map<Object, List<Integer>> VIEWS = new HashMap<Object, List<Integer>>();
    private static final Map<Object, Map<Object, Boolean>> ATTACHED = new HashMap<Object, Map<Object, Boolean>>();

    public static void installWebcamLister(WebcamLister l) {
        lister = l;
        synchronized (VIEWS) {
            VIEWS.clear();
            ATTACHED.clear();
        }
    }

    public static List<WebcamName> webcams() {
        return new ArrayList<WebcamName>(lister.webcams());
    }

    static Object robot() {
        Object key = SimHooks.robotKey();
        return key != null ? key : ROBOT_1;
    }

    /** The LiveView ids this robot's portals use. */
    public static List<Integer> viewsInUse() {
        synchronized (VIEWS) {
            Object key = robot();
            List<Integer> v = VIEWS.get(key);
            if (v == null) VIEWS.put(key, v = new ArrayList<Integer>());
            return v;
        }
    }

    public static Object viewLock() {
        return VIEWS;
    }

    /** VisionPortal.Builder: false if this processor is already attached to a portal (on this robot). */
    public static boolean attach(Object processor) {
        synchronized (VIEWS) {
            Object key = robot();
            Map<Object, Boolean> set = ATTACHED.get(key);
            if (set == null) ATTACHED.put(key, set = new WeakHashMap<Object, Boolean>());
            if (set.containsKey(processor)) return false;
            set.put(processor, Boolean.TRUE);
            return true;
        }
    }
}
