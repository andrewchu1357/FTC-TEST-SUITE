package org.firstinspires.ftc.robotcore.external.hardware.camera;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Workarounds for webcams that misbehave on the Control Hub's USB, by USB vendor and product id.
 * Simulated webcams don't misbehave, so quirks are only remembered.
 */
public abstract class CameraCompatibilityManager {
    public enum Quirk {
        RESET_ALT_SETTING_ON_RELEASE(1),
        AVOID_LIB_USB_RESET_DEVICE(2);

        public final int bitmask;

        Quirk(int bitmask) {
            this.bitmask = bitmask;
        }
    }

    private static final CameraCompatibilityManager INSTANCE = new CameraCompatibilityManager() {
        private final Map<Long, List<Quirk>> quirks = new HashMap<Long, List<Quirk>>();

        private long key(int vid, int pid) {
            return ((long) vid << 32) | (pid & 0xFFFFFFFFL);
        }

        @Override
        public synchronized void addQuirk(int vid, int pid, Quirk quirk) {
            List<Quirk> list = quirks.get(key(vid, pid));
            if (list == null) quirks.put(key(vid, pid), list = new ArrayList<Quirk>());
            if (!list.contains(quirk)) list.add(quirk);
        }

        @Override
        public synchronized void removeQuirk(int vid, int pid, Quirk quirk) {
            List<Quirk> list = quirks.get(key(vid, pid));
            if (list != null) list.remove(quirk);
        }

        @Override
        public synchronized List<Quirk> getQuirks(int vid, int pid) {
            List<Quirk> list = quirks.get(key(vid, pid));
            return list == null ? new ArrayList<Quirk>() : new ArrayList<Quirk>(list);
        }
    };

    public abstract void addQuirk(int vid, int pid, Quirk quirk);

    public abstract void removeQuirk(int vid, int pid, Quirk quirk);

    public abstract List<Quirk> getQuirks(int vid, int pid);

    public static CameraCompatibilityManager getInstance() {
        return INSTANCE;
    }
}
