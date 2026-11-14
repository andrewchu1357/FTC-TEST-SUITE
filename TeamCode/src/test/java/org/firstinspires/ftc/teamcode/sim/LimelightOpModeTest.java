package org.firstinspires.ftc.teamcode.sim;

import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.hardware.limelightvision.Limelight3A;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotorEx;

import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRegistry;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSeason;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Robot code using a Limelight the way the Tennessee Robotics Center's BIOBUZZ guide teaches. */
class LimelightOpModeTest {
    static final double IN = 0.0254;

    /** The guide's chasePollen(): turn to tx, drive by the distance ty gives, stop 8 in away. */
    public static class ChasePollen extends LinearOpMode {
        static final double CAMERA_HEIGHT_IN = 8.0, MOUNT_ANGLE_DEG = 25.0, BALL_RADIUS_IN = 1.4;
        static final double TURN_KP = 0.02, DRIVE_KP = 0.03, TX_TOLERANCE = 2.0, STOP_DIST_IN = 8.0, MAX_POWER = 0.5;
        static volatile boolean done;
        static volatile double lastDistance, lastTx;
        static volatile int loops;

        @Override
        public void runOpMode() {
            Limelight3A limelight = hardwareMap.get(Limelight3A.class, "limelight");
            limelight.setPollRateHz(100);
            limelight.pipelineSwitch(0);
            limelight.start();
            DcMotorEx[] m = Sdk12Test.driveMotors(hardwareMap);
            done = false;
            loops = 0;
            waitForStart();
            while (opModeIsActive() && !done) {
                loops++;
                LLResult result = limelight.getLatestResult();
                if (result == null || !result.isValid()) {
                    Sdk12Test.drive(m, 0, 0, 0.25); // no ball: slow search spin
                    continue;
                }
                double tx = result.getTx();
                double dist = (CAMERA_HEIGHT_IN - BALL_RADIUS_IN) / Math.tan(Math.toRadians(MOUNT_ANGLE_DEG - result.getTy()));
                double turn = clip(tx * TURN_KP);
                double forward = Math.abs(tx) < 10 ? clip((dist - STOP_DIST_IN) * DRIVE_KP) : 0;
                // The guide's turn is "positive = right"; this drive's turn is counter-clockwise.
                Sdk12Test.drive(m, forward, 0, -turn);
                lastDistance = dist;
                lastTx = tx;
                if (Math.abs(tx) < TX_TOLERANCE && dist <= STOP_DIST_IN + 0.5) done = true;
            }
            Sdk12Test.drive(m, 0, 0, 0);
            limelight.stop();
            while (opModeIsActive()) idle();
        }

        static double clip(double p) {
            return Math.max(-MAX_POWER, Math.min(MAX_POWER, p));
        }
    }

    @Test
    void theGuidesChasePollenFindsABallAndDrivesUpToIt() throws Exception {
        String text = Files.readString(Path.of("config/robot.jsonc"))
                .replaceAll("(?m)^\\s*\"limelights\": \\[.*\\],\\n", "").replace("\"limitSwitches\": [",
                "\"limelights\": [ { \"name\": \"limelight\", \"zIn\": 8, \"tiltUpDeg\": -25 } ],\n  \"limitSwitches\": [");
        RobotConfig robot = RobotConfig.parse(text, "robot.jsonc");
        OpModeRegistry.Result ops = new OpModeRegistry.Result(List.of(new OpModeInfo("ChasePollen", "", false, null, ChasePollen.class)), List.of());
        try (SimHarness h = SimHarness.of(robot, FieldConfig.load(Path.of("config/field.jsonc")), ops)) {
            // Clear the floor, then one POLLEN ahead and to the left, nearer than the FLOWER in view across the
            // field (from farther away, that FLOWER's 4 POLLEN are the bigger blob and the guide's code chases it).
            for (GamePiece p : List.copyOf(h.sim.pieces().all())) if (p.state() == GamePiece.State.FLOOR) h.sim.pieces().takeOffField(p);
            h.sim.robot(1).handling().hopper().clear();
            h.sim.robot(1).moveRobot(-40 * IN, -50 * IN, 0);
            GamePiece ball = h.sim.pieces().create(((BioBuzzSeason) h.sim.season()).pollen());
            h.sim.pieces().placeOnFloor(ball, -40 * IN + 24 * IN, -50 * IN + 7 * IN, 0, 0);
            h.init("ChasePollen").start().runFor(6);
            assertNull(h.error(), h.error());
            assertTrue(ChasePollen.done, "it got there: tx " + ChasePollen.lastTx + ", distance " + ChasePollen.lastDistance + " in");
            double rx = h.xInches(), ry = h.yInches(), hd = Math.toRadians(h.headingDegrees());
            double bx = ball.x() / IN, by = ball.y() / IN;
            double bearing = Math.toDegrees(Math.atan2(by - ry, bx - rx) - hd);
            bearing = Math.toDegrees(Math.atan2(Math.sin(Math.toRadians(bearing)), Math.cos(Math.toRadians(bearing))));
            double lens = rx + Math.cos(hd) * (h.sim.robot(1).config.length() / 2 / IN - 1);
            double lensY = ry + Math.sin(hd) * (h.sim.robot(1).config.length() / 2 / IN - 1);
            double fromLens = Math.hypot(bx - lens, by - lensY);
            System.out.printf("ended %.1f in from the lens, %.1f deg off, %d loops%n", fromLens, bearing, ChasePollen.loops);
            assertEquals(0, bearing, 4, "facing the ball");
            assertEquals(8, fromLens, 2, "about 8 in from the camera, where the guide stops");
        }
    }
}
