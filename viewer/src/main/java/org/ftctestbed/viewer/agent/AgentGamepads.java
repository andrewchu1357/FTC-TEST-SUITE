package org.ftctestbed.viewer.agent;

import com.qualcomm.robotcore.hardware.Gamepad;

import org.ftctestbed.sim.opmode.OpModeRunner;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gamepad states an agent set on the window's robots. The window's Driver Station sends these
 * instead of the controllers and keyboard (after the same delay) until the agent releases them.
 */
public final class AgentGamepads {
    private static final Map<OpModeRunner, Gamepad[]> OVERRIDES = new ConcurrentHashMap<>();

    private AgentGamepads() {}

    public static void set(OpModeRunner runner, int pad, Gamepad state) {
        Gamepad copy = new Gamepad();
        copy.copy(state);
        OVERRIDES.computeIfAbsent(runner, r -> new Gamepad[2])[pad - 1] = copy;
    }

    public static void clear(OpModeRunner runner, int pad) {
        Gamepad[] g = OVERRIDES.get(runner);
        if (g != null) g[pad - 1] = null;
    }

    public static void clearAll(OpModeRunner runner) {
        OVERRIDES.remove(runner);
    }

    /** What the agent set for this gamepad (1 or 2), or null to use the controllers. */
    public static Gamepad get(OpModeRunner runner, int pad) {
        Gamepad[] g = OVERRIDES.get(runner);
        return g == null ? null : g[pad - 1];
    }
}
