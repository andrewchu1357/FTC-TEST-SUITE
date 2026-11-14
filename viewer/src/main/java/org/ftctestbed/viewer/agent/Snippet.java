package org.ftctestbed.viewer.agent;

import com.qualcomm.robotcore.hardware.HardwareMap;

import org.ftctestbed.sim.SimRobot;
import org.ftctestbed.sim.SimRunner;
import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.game.MatchController;
import org.ftctestbed.sim.opmode.OpModeRunner;

import java.io.PrintStream;
import java.nio.file.Path;

/**
 * A Java snippet an agent runs with java_eval. The snippet's code becomes the body of run(),
 * with these as fields: sim, robot, hardwareMap, runner, physics, match, session, out, project
 * (null without a session), and advance(seconds) to let simulated time run.
 */
public interface Snippet {
    Object run(Context ctx) throws Throwable;

    /** What a snippet works with. Units are the simulator's: meters, radians, seconds. */
    record Context(SimContext session, Simulation sim, SimRobot robot, HardwareMap hardwareMap, OpModeRunner runner,
                   SimRunner physics, MatchController match, ClassLoader teamCode, PrintStream out, Path project) {
        /** Lets simulated time run (headless: lockstep, as fast as possible). */
        public void advance(double seconds) {
            if (session == null) throw new IllegalStateException("No session: give java_eval a session to advance time");
            session.advance(seconds);
        }
    }
}
