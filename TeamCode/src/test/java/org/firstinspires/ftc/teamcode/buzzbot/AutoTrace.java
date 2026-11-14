package org.firstinspires.ftc.teamcode.buzzbot;

import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.game.MatchState;

import java.util.Locale;

/**
 * Prints an autonomous step by step: the robot's true pose (compare with the odometry pose in
 * the telemetry), hopper contents, flywheel speed and telemetry.
 * ./gradlew :TeamCode:autoTrace --args="'Blue Wall' 'BuzzBot Auto Blue'"
 */
public final class AutoTrace {
    public static void main(String[] args) {
        PoseStorage.pose = null;
        SimHarness h = SimHarness.load();
        h.startAt(args.length > 0 ? args[0] : "Red Wall");
        double[] next = {0};
        h.sim.addStepListener(s -> {
            double t = s.clock.seconds();
            MatchState.Phase phase = s.match().phase();
            if ((phase != MatchState.Phase.AUTO && phase != MatchState.Phase.TRANSITION) || t < next[0]) return;
            next[0] = t + 0.25;
            System.out.printf(Locale.US, "%5.2f %-10s true(%6.1f,%6.1f,%5.0f) hopper=%d flywheel=%5.0frpm  %s%n",
                    s.match().elapsedInPhase(t), phase, s.robotX() / 0.0254, s.robotY() / 0.0254,
                    Math.toDegrees(s.robotHeading()), s.handling().hopper().size(), s.handling().parts().flywheelRpm(),
                    String.join(" | ", h.telemetry()));
        });
        h.runMatch(args.length > 1 ? args[1] : "BuzzBot Auto Red", null);
        System.out.println("\nScore: RED " + h.score("red") + " : " + h.score("blue") + " BLUE");
        h.close();
        System.exit(0);
    }
}
