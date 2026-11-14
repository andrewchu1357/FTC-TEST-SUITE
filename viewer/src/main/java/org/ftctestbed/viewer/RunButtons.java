package org.ftctestbed.viewer;

import org.ftctestbed.sim.opmode.OpModeRunner;
import org.ftctestbed.viewer.ui.FluentButton;
import org.ftctestbed.viewer.ui.Icons;

/**
 * A Driver Station's one big button, which follows the OpMode: Init → Start → Stop, with a
 * small Stop beside it while an initialized OpMode waits for Start.
 */
final class RunButtons {
    private RunButtons() {}

    /** Shows state st on run (and stop). canInit: there's an OpMode to initialize; keys: mention the menu shortcuts. */
    static void show(FluentButton run, FluentButton stop, OpModeRunner.State st, boolean canInit, boolean keys) {
        switch (st) {
            case IDLE, STOPPED -> {
                run.set("Init", Icons.POWER);
                run.setStyle(FluentButton.Style.ACCENT, FluentButton.Tint.ACCENT);
                run.setToolTipText("Initialize the OpMode" + (keys ? org.ftctestbed.viewer.platform.Os.text(" (⌘I)") : ""));
                run.setEnabled(canInit);
            }
            case INIT -> {
                run.set("Start", Icons.PLAY);
                run.setStyle(FluentButton.Style.ACCENT, FluentButton.Tint.GO);
                run.setToolTipText("Start the OpMode" + (keys ? org.ftctestbed.viewer.platform.Os.text(" (⌘↩)") : ""));
                run.setEnabled(true);
            }
            case RUNNING -> {
                run.set("Stop", Icons.STOP);
                run.setStyle(FluentButton.Style.ACCENT, FluentButton.Tint.STOP);
                run.setToolTipText("Stop the OpMode" + (keys ? org.ftctestbed.viewer.platform.Os.text(" (⌘.)") : ""));
                run.setEnabled(true);
            }
            case STOPPING -> {
                run.set("Stopping…", Icons.STOP);
                run.setEnabled(false);
            }
        }
        stop.setToolTipText("Stop without starting" + (keys ? org.ftctestbed.viewer.platform.Os.text(" (⌘.)") : ""));
        stop.setVisible(st == OpModeRunner.State.INIT);
    }

    /** What pressing run does in state st. */
    static void press(OpModeRunner.State st, Runnable init, Runnable start, Runnable stop) {
        switch (st) {
            case IDLE, STOPPED -> init.run();
            case INIT -> start.run();
            case RUNNING -> stop.run();
            default -> { }
        }
    }
}
