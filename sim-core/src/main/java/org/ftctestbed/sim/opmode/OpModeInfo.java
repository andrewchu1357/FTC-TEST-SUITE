package org.ftctestbed.sim.opmode;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;

/**
 * An OpMode on the Driver Station list. Usually a class, created fresh for each run; an OpMode
 * registered as an instance by an @OpModeRegistrar method (Road Runner's tuning OpModes) is that
 * same instance every run, as on the robot. A utility OpMode (@Utility, SDK 11.2) is on the
 * Driver Station's Utility menu instead of the TeleOp list, and runs like a TeleOp.
 */
public record OpModeInfo(String name, String group, boolean autonomous, String preselectTeleOp,
                         Class<? extends OpMode> type, OpMode instance, boolean utility) {
    public OpModeInfo(String name, String group, boolean autonomous, String preselectTeleOp, Class<? extends OpMode> type) {
        this(name, group, autonomous, preselectTeleOp, type, null, false);
    }

    public OpModeInfo(String name, String group, boolean autonomous, String preselectTeleOp,
                      Class<? extends OpMode> type, OpMode instance) {
        this(name, group, autonomous, preselectTeleOp, type, instance, false);
    }

    /** A TeleOp for a match or the Sandbox: not an AUTO and not a utility. */
    public boolean teleOp() {
        return !autonomous && !utility;
    }

    /** How the Driver Station lists it: "Auto  · name", "TeleOp · name" or "Utility · name". */
    public String label() {
        return (autonomous ? "Auto  · " : utility ? "Utility · " : "TeleOp · ") + name;
    }

    @Override
    public String toString() {
        return name;
    }
}
