package org.firstinspires.ftc.teamcode.sim;

import com.pedropathing.algorithm.Foresight;
import com.pedropathing.algorithm.ForesightConfig;
import com.pedropathing.controllers.Controller;
import com.pedropathing.follower.Follower;
import com.pedropathing.math.Matrix;
import com.pedropathing.math.Pose;
import com.pedropathing.math.Vector2D;
import com.pedropathing.revhub.drivetrains.Mecanum;
import com.pedropathing.revhub.drivetrains.MecanumConfig;
import com.pedropathing.revhub.localizers.PinpointConfig;
import com.pedropathing.revhub.localizers.PinpointLocalizer;
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.HardwareMap;

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
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pedro Pathing 3 (the current Quickstart's version) follows paths in the simulator, with the
 * example Constants from Pedro's docs (pedropathing.com, Tuning / Constants) on a Pinpoint robot.
 */
class PedroTest {
    static final String FIELD = "{ \"robotStart\": { \"xIn\": -40, \"yIn\": -30, \"headingDeg\": 0 } }";

    /** Pedro's documented example Constants, with this robot's motor names and pod offsets. */
    static Follower create(HardwareMap h) {
        MecanumConfig drivetrain = new MecanumConfig(c -> {
            c.frontLeftName.set("leftFront");
            c.backLeftName.set("leftBack");
            c.frontRightName.set("rightFront");
            c.backRightName.set("rightBack");
            c.frontLeftDirection.set(DcMotorSimple.Direction.REVERSE);
            c.backLeftDirection.set(DcMotorSimple.Direction.REVERSE);
            c.frontRightDirection.set(DcMotorSimple.Direction.FORWARD);
            c.backRightDirection.set(DcMotorSimple.Direction.FORWARD);
            c.manualBrakeMode.set(true);
        });
        PinpointConfig localizer = new PinpointConfig(c -> {
            c.name.set("pinpoint");
            c.xPodOffset.set(3.5);
            c.yPodOffset.set(-2.0);
            c.xPodDirection.set(GoBildaPinpointDriver.EncoderDirection.FORWARD);
            c.yPodDirection.set(GoBildaPinpointDriver.EncoderDirection.FORWARD);
        });
        ForesightConfig foresight = new ForesightConfig(c -> {
            c.forwardTranslational.set(Controller.piecewise(Controller.proportional(0.1)).put(2.5, Controller.proportional(0.3)));
            c.strafeTranslational.set(Controller.piecewise(Controller.proportional(0.1)).put(2.5, Controller.proportional(0.3)));
            c.coast.set(Controller.proportionalFeedforward(0.010978350889324107));
            c.brake.set(Controller.proportionalFeedforward(0.008731598255925491));
            c.headingFeedback.set(Controller.proportional(5.258721785960744));
            c.headingBrakeCoefficients.set(Vector2D.cartesian(0.05642143125655298, 0.0063829525363003695));
            c.linearBrakeCoefficients.set(Matrix.diag(0.10605894992901523, 0.08719146175596092));
            c.quadraticBrakeCoefficients.set(Matrix.diag(0.0014663966976606565, 0.0013837064502458813));
            c.maxAchievableForwardVelocity.set(72.72923108818539);
            c.maxAchievableStrafeVelocity.set(52.34323936525474);
            c.naturalForwardDeceleration.set(85.01144677379789);
            c.naturalStrafeDeceleration.set(104.49787535782846);
        });
        return new Follower(new PinpointLocalizer(h, localizer), new Mecanum(h, drivetrain), new Foresight(foresight));
    }

    public static class FollowPaths extends LinearOpMode {
        static volatile Pose end;
        static volatile double seconds;
        static volatile double[] truthAtEnd;
        static volatile java.util.function.Supplier<double[]> truth;

        @Override
        public void runOpMode() {
            Follower follower = create(hardwareMap);
            follower.setPose(new Pose(0, 0, 0));
            waitForStart();
            double start = getRuntime();
            Pose a = new Pose(0, 0, 0), b = new Pose(36, 0, Math.PI / 2), c = new Pose(60, 40, Math.PI);
            follower.follow(line(a, b).linear(a, b));
            while (opModeIsActive() && follower.isBusy()) follower.update();
            follower.follow(curve(b, new Pose(60, 0, Math.PI / 2), c).linear(b, c));
            while (opModeIsActive() && follower.isBusy()) follower.update();
            seconds = getRuntime() - start;
            double holdUntil = getRuntime() + 1.0;
            while (opModeIsActive() && getRuntime() < holdUntil) follower.update(); // Pedro holds the end pose
            end = follower.pose();
            truthAtEnd = truth.get();
            while (opModeIsActive()) follower.update();
        }
    }

    @Test
    void followsALineAndACurve() throws Exception {
        RobotConfig robot = RobotConfig.load(Path.of("robots/RoadRunnerQuickstart/robot.jsonc"));
        try (SimHarness h = SimHarness.of(robot, FieldConfig.parse(FIELD, "field"),
                new OpModeRegistry.Result(List.of(new OpModeInfo("Pedro", "", false, null, FollowPaths.class)), List.of()))) {
            // Where the robot really is, relative to where it started (Pedro's (0, 0, 0)).
            FollowPaths.truth = () -> new double[]{h.sim.robotX() / 0.0254 + 40, h.sim.robotY() / 0.0254 + 30, Math.toDegrees(h.sim.robotHeading())};
            h.init("Pedro").runFor(1.0).start().runFor(10); // wait in INIT, as a driver would
            assertNull(h.error(), h.error());
            double[] t = FollowPaths.truthAtEnd;
            assertNotNull(t, "finished the paths");
            System.out.printf("Pedro: true %.2f, %.2f, %.1f   pedro %s   (paths took %.1f s)%n", t[0], t[1], t[2], FollowPaths.end, FollowPaths.seconds);
            assertEquals(60, t[0], 1.0, "x");
            assertEquals(40, t[1], 1.0, "y");
            assertEquals(180, Math.abs(t[2]), 2, "heading");
            assertEquals(t[0], FollowPaths.end.x(), 0.5, "Pedro knows where it is: x");
            assertEquals(t[1], FollowPaths.end.y(), 0.5, "Pedro knows where it is: y");
        }
    }

    @Test
    void theQuickstartCompiles(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path field = java.nio.file.Files.writeString(dir.resolve("field.jsonc"), FIELD);
        try (SimHarness h = SimHarness.load(Path.of("robots/PedroQuickstart/robot.jsonc"), field)) {
            assertNotNull(h.sim.hardwareMap.get(GoBildaPinpointDriver.class, "pinpoint"));
        }
    }
}
