package org.ftctestbed.viewer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What the Code tab hands to the next window when "Save & Run" reloads the simulator: the open
 * files and where the cursor was, which view to show, and which OpMode to start.
 */
final class CodeSession {
    /** files in tab order; carets by file; view: "Code", "3D" or "2D"; runOpMode: null to start nothing. */
    record State(List<Path> files, Path selected, Map<Path, Integer> carets, String view, String runOpMode) {}

    private static State pending;

    private CodeSession() {}

    static synchronized void hand(State s) {
        pending = s;
    }

    /** The state left by the previous window, once (null if there is none). */
    static synchronized State take() {
        State s = pending;
        pending = null;
        return s;
    }
}
