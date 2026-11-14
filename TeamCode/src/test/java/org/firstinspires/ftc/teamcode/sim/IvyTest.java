package org.firstinspires.ftc.teamcode.sim;

import com.pedropathing.api.PoseFactory;
import com.pedropathing.follower.Follower;
import com.pedropathing.ivy.Scheduler;
import com.pedropathing.math.Pose;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;

import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static com.pedropathing.api.Paths.curve;
import static com.pedropathing.api.Paths.line;
import static com.pedropathing.ivy.commands.Commands.instant;
import static com.pedropathing.ivy.commands.Commands.waitMs;
import static com.pedropathing.ivy.groups.Groups.sequential;
import static com.pedropathing.ivy.pedro.PedroCommands.follow;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Ivy, Pedro Pathing's command library, runs in the simulator the way team autos use it: an
 * iterative OpMode that schedules follow / wait / follow in start() and runs the follower and
 * the scheduler in loop(). Its waits run on simulated time.
 */
class IvyTest {
    static final double IN = 0.0254;

    public static class IvyAuto extends OpMode {
        static volatile double waitStarted = Double.NaN, waitEnded = Double.NaN, done = Double.NaN;
        private final PoseFactory p = PoseFactory.degrees();
        private final Pose start = p.of(0, 0, 0), score = p.of(18, 0, 0), control = p.of(40, 0, 0), park = p.of(40, 30, 90);
        private Follower follower;

        @Override
        public void init() {
            Scheduler.reset();
            follower = PedroTest.create(hardwareMap);
            follower.setPose(start);
        }

        @Override
        public void start() {
            Scheduler.schedule(sequential(
                    follow(follower, line(start, score).linear(start, score)),
                    instant(() -> waitStarted = getRuntime()),
                    waitMs(1500),
                    instant(() -> waitEnded = getRuntime()),
                    follow(follower, curve(score, control, park).tangent()),
                    instant(() -> done = getRuntime())));
        }

        @Override
        public void loop() {
            follower.update();
            Scheduler.execute();
        }
    }

    @Test
    void followsWaitsAndFollowsOnSimulatedTime() throws Exception {
        RobotConfig robot = RobotConfig.load(Path.of("robots/RoadRunnerQuickstart/robot.jsonc"));
        try (SimHarness h = SimHarness.of(robot, FieldConfig.parse(PedroTest.FIELD, "field"),
                new OpModeRegistry.Result(List.of(new OpModeInfo("Ivy", "", true, null, IvyAuto.class)), List.of()))) {
            h.init("Ivy").runFor(1.0).start().runFor(12);
            assertNull(h.error(), h.error());
            assertFalse(Double.isNaN(IvyAuto.done), "the whole sequence ran");
            assertEquals(1.5, IvyAuto.waitEnded - IvyAuto.waitStarted, 0.06, "waitMs(1500) lasts 1.5 s of robot time");
            // Where the robot really is, relative to where it started (Pedro's (0, 0, 0) at -40, -30 in).
            double x = h.sim.robotX() / IN + 40, y = h.sim.robotY() / IN + 30, heading = Math.toDegrees(h.sim.robotHeading());
            assertEquals(40, x, 1.5, "x");
            assertEquals(30, y, 1.5, "y");
            assertEquals(90, heading, 3, "heading (the curve's tangent at its end)");
        }
    }
}
