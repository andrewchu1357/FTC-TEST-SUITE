package org.ftctestbed.viewer;

import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.game.MatchController;
import org.ftctestbed.sim.game.MatchState;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sandbox: the driver TeleOp running with no match around it, so there's no AUTO, no match
 * clock and no time limit. Whatever was running (a match, another OpMode) is stopped first.
 * With a partner robot both robots' TeleOps run.
 */
final class Sandbox {
    private Sandbox() {}

    /** Stops what runs, goes to free practice and INITs and STARTs the TeleOp. Blocks: call it off the Swing thread. */
    static void start(Simulation sim, MatchController match, OpModeRunner runner, OpModeInfo teleop) {
        start(sim, match, List.of(runner), Arrays.asList(teleop));
    }

    /** As start(), for every robot: each runner gets its TeleOp (null: that robot sits still). */
    static void start(Simulation sim, MatchController match, List<OpModeRunner> runners, List<OpModeInfo> teleops) {
        match.abort();
        while (match.busy()) pause(5);
        for (OpModeRunner r : runners) r.stop();
        for (OpModeRunner r : runners) waitFor(r, OpModeRunner.State.STOPPED, OpModeRunner.State.IDLE);
        sim.match().enter(MatchState.Phase.PRACTICE, sim.clock.seconds());
        List<OpModeRunner> started = new ArrayList<>();
        for (int i = 0; i < runners.size(); i++) {
            OpModeInfo teleop = i < teleops.size() ? teleops.get(i) : null;
            if (teleop == null) continue;
            OpModeRunner r = runners.get(i);
            r.setAutoTimer(false);
            r.init(teleop);
            started.add(r);
        }
        for (OpModeRunner r : started) waitFor(r, OpModeRunner.State.INIT);
        pause(300); // let INIT run a moment, as a driver would
        for (OpModeRunner r : started) r.start();
    }

    private static void waitFor(OpModeRunner runner, OpModeRunner.State... states) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            OpModeRunner.State st = runner.state();
            for (OpModeRunner.State want : states) if (st == want) return;
            pause(5);
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
