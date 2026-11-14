package org.firstinspires.ftc.teamcode.sim;

import com.qualcomm.hardware.sparkfun.SparkFunOTOS;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The SparkFun OTOS, driven through the SDK's own driver as SparkFun's sample does. */
class OtosTest {
    static final String FIELD = "{ \"robotStart\": { \"xIn\": 0, \"yIn\": 0, \"headingDeg\": 0 } }";

    static RobotConfig robot(double heightMm) throws Exception {
        String text = Files.readString(Path.of("config/robot.jsonc"));
        String otos = "\"otos\": [ { \"name\": \"sensor_otos\", \"xIn\": 2.0, \"yIn\": -3.0, \"mountHeadingDeg\": 90,"
                + " \"heightMm\": " + heightMm + ", \"linearScaleError\": -0.03, \"angularScaleError\": 0.005 } ],\n  \"odometryPods\": [";
        return RobotConfig.parse(text.replace("\"odometryPods\": [", otos), "robot.jsonc");
    }

    public static class DriveWithOtos extends LinearOpMode {
        static volatile boolean calibrated;
        static volatile double x, y, heading;
        static volatile boolean lostTracking, connected;

        @Override
        public void runOpMode() {
            SparkFunOTOS otos = hardwareMap.get(SparkFunOTOS.class, "sensor_otos");
            connected = otos.isConnected();
            otos.setLinearUnit(DistanceUnit.INCH);
            otos.setAngularUnit(AngleUnit.DEGREES);
            otos.setOffset(new SparkFunOTOS.Pose2D(2.0, -3.0, 90));
            otos.setLinearScalar(calibrated ? 1 / 0.97 : 1.0);
            otos.setAngularScalar(calibrated ? 1 / 1.005 : 1.0);
            otos.calibrateImu();
            otos.resetTracking();
            DcMotorEx[] m = {hardwareMap.get(DcMotorEx.class, "leftFront"), hardwareMap.get(DcMotorEx.class, "rightFront"),
                    hardwareMap.get(DcMotorEx.class, "leftBack"), hardwareMap.get(DcMotorEx.class, "rightBack")};
            m[0].setDirection(DcMotorSimple.Direction.REVERSE);
            m[2].setDirection(DcMotorSimple.Direction.REVERSE);
            waitForStart();
            run(otos, m, 0.5, 0, 0, 1.5);
            run(otos, m, 0, 0.5, 0, 1.0);
            run(otos, m, 0, 0, 0.4, 1.5);
            run(otos, m, 0, 0, 0, 1.0);
            SparkFunOTOS.Pose2D p = otos.getPosition();
            x = p.x;
            y = p.y;
            heading = p.h;
            while (opModeIsActive()) idle();
        }

        private void run(SparkFunOTOS otos, DcMotorEx[] m, double fwd, double left, double ccw, double seconds) {
            double end = getRuntime() + seconds;
            while (opModeIsActive() && getRuntime() < end) {
                m[0].setPower(fwd - left - ccw);
                m[1].setPower(fwd + left + ccw);
                m[2].setPower(fwd + left - ccw);
                m[3].setPower(fwd - left + ccw);
                SparkFunOTOS.Pose2D pos = new SparkFunOTOS.Pose2D(), vel = new SparkFunOTOS.Pose2D(), acc = new SparkFunOTOS.Pose2D();
                otos.getPosVelAcc(pos, vel, acc);
                if (otos.getStatus().warnOpticalTracking) lostTracking = true;
            }
        }
    }

    private static double[] drive(double heightMm, boolean calibrated) throws Exception {
        DriveWithOtos.calibrated = calibrated;
        DriveWithOtos.lostTracking = false;
        try (SimHarness h = SimHarness.of(robot(heightMm), FieldConfig.parse(FIELD, "field"),
                new OpModeRegistry.Result(List.of(new OpModeInfo("Otos", "", false, null, DriveWithOtos.class)), List.of()))) {
            h.init("Otos").start().runFor(7);
            assertNull(h.error(), h.error());
            assertTrue(DriveWithOtos.connected, "isConnected(): product id 0x5F");
            return new double[]{h.xInches(), h.yInches(), h.headingDegrees()};
        }
    }

    @Test
    void outOfTheBoxDistancesReadShort() throws Exception {
        double[] t = drive(10, false);
        double trueDist = Math.hypot(t[0], t[1]), measured = Math.hypot(DriveWithOtos.x, DriveWithOtos.y);
        System.out.printf("uncalibrated: true %.2f %.2f %.1f  otos %.2f %.2f %.1f%n", t[0], t[1], t[2], DriveWithOtos.x, DriveWithOtos.y, DriveWithOtos.heading);
        assertEquals(0.97, measured / trueDist, 0.015, "about 3% short, as SparkFun says");
        assertFalse(DriveWithOtos.lostTracking);
    }

    @Test
    void calibratedItTracksTheRobot() throws Exception {
        double[] t = drive(10, true);
        System.out.printf("calibrated: true %.2f %.2f %.1f  otos %.2f %.2f %.1f%n", t[0], t[1], t[2], DriveWithOtos.x, DriveWithOtos.y, DriveWithOtos.heading);
        assertEquals(t[0], DriveWithOtos.x, 0.5, "x");
        assertEquals(t[1], DriveWithOtos.y, 0.5, "y");
        assertEquals(0, AngleUnit.normalizeDegrees(t[2] - DriveWithOtos.heading), 0.5, "heading");
    }

    @Test
    void mountedTooHighItLosesTheTiles() throws Exception {
        double[] t = drive(14, true);
        assertTrue(DriveWithOtos.lostTracking, "status.warnOpticalTracking");
        assertTrue(Math.hypot(DriveWithOtos.x, DriveWithOtos.y) < 0.3 * Math.hypot(t[0], t[1]), "position barely moved");
        assertEquals(0, AngleUnit.normalizeDegrees(t[2] - DriveWithOtos.heading), 1.0, "heading (gyro) still tracks");
    }
}
