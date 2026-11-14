package org.firstinspires.ftc.teamcode.sim;

import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose2D;
import org.firstinspires.ftc.robotcore.external.navigation.UnnormalizedAngleUnit;
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

/** The goBILDA Pinpoint, driven through the SDK's own driver as the goBILDA sample does. */
class PinpointTest {
    static final String PINPOINT = "\"pinpoints\": [ { \"name\": \"pinpoint\", %s"
            + "\"xPod\": { \"type\": \"goBILDA-4-Bar-Odometry-Pod\", \"xIn\": 1.0, \"yIn\": 3.0 },"
            + "\"yPod\": { \"type\": \"goBILDA-4-Bar-Odometry-Pod\", \"xIn\": -4.0, \"yIn\": -0.5 } } ],\n  \"odometryPods\": [";
    static final String FIELD = "{ \"robotStart\": { \"xIn\": 0, \"yIn\": 0, \"headingDeg\": 0 } }";

    static RobotConfig robot(String extra) throws Exception {
        String text = Files.readString(Path.of("config/robot.jsonc"));
        assertTrue(text.contains("\"odometryPods\": ["));
        return RobotConfig.parse(text.replace("\"odometryPods\": [", String.format(PINPOINT, extra)), "robot.jsonc");
    }

    static SimHarness harness(RobotConfig robot, Class<? extends LinearOpMode> op) {
        return SimHarness.of(robot, FieldConfig.parse(FIELD, "field"),
                new OpModeRegistry.Result(List.of(new OpModeInfo(op.getSimpleName(), "", false, null, op)), List.of()));
    }

    /** Drives a pattern and records what the Pinpoint reported at the end. */
    public static class DrivePattern extends LinearOpMode {
        static volatile String statusAfterReset, statusLater;
        static volatile double x, y, heading, turns, loopUs;
        static volatile boolean moveDuringCalibration, combined;
        static volatile double power = 1;
        static volatile boolean rrRobot;

        @Override
        public void runOpMode() {
            GoBildaPinpointDriver pp = hardwareMap.get(GoBildaPinpointDriver.class, "pinpoint");
            if (rrRobot) pp.setOffsets(3.5, -2.0, DistanceUnit.INCH);
            else pp.setOffsets(3.0, -4.0, DistanceUnit.INCH); // X pod 3 in left, Y pod 4 in back
            pp.setEncoderResolution(GoBildaPinpointDriver.GoBildaOdometryPods.goBILDA_4_BAR_POD);
            pp.setEncoderDirections(GoBildaPinpointDriver.EncoderDirection.FORWARD, GoBildaPinpointDriver.EncoderDirection.FORWARD);
            DcMotorEx[] m = {hardwareMap.get(DcMotorEx.class, "leftFront"), hardwareMap.get(DcMotorEx.class, "rightFront"),
                    hardwareMap.get(DcMotorEx.class, "leftBack"), hardwareMap.get(DcMotorEx.class, "rightBack")};
            m[0].setDirection(DcMotorSimple.Direction.REVERSE);
            m[2].setDirection(DcMotorSimple.Direction.REVERSE);
            waitForStart();
            if (moveDuringCalibration) drive(m, 0, 0, 0.6);
            pp.resetPosAndIMU();
            sleep(50);
            pp.update();
            statusAfterReset = pp.getDeviceStatus().name();
            sleep(250);
            drive(m, 0, 0, 0);
            sleep(300);
            pp.update();
            statusLater = pp.getDeviceStatus().name();
            if (combined) {
                run(pp, m, power * 0.7, power * 0.3, power * 0.5, 1.5);
                run(pp, m, 0, 0, 0, 1.0);
            } else if (!moveDuringCalibration) {
                run(pp, m, 0.6, 0, 0, 1.2);    // forward
                run(pp, m, 0, 0.6, 0, 1.0);    // strafe left
                run(pp, m, 0, 0, 0.5, 2.5);    // spin counter-clockwise
                run(pp, m, -0.4, -0.3, 0.2, 1.5);
                run(pp, m, 0, 0, 0, 1.0);      // stop
            } else {
                run(pp, m, 0, 0, 0, 20);       // stand still
            }
            Pose2D p = pp.getPosition();
            x = p.getX(DistanceUnit.INCH);
            y = p.getY(DistanceUnit.INCH);
            heading = p.getHeading(AngleUnit.DEGREES);
            turns = pp.getHeading(UnnormalizedAngleUnit.DEGREES) / 360;
            loopUs = pp.getLoopTime();
            while (opModeIsActive()) idle();
        }

        private void run(GoBildaPinpointDriver pp, DcMotorEx[] m, double fwd, double left, double ccw, double seconds) {
            double end = getRuntime() + seconds;
            while (opModeIsActive() && getRuntime() < end) {
                drive(m, fwd, left, ccw);
                pp.update();
            }
        }

        private static void drive(DcMotorEx[] m, double fwd, double left, double ccw) {
            m[0].setPower(fwd - left - ccw);
            m[1].setPower(fwd + left + ccw);
            m[2].setPower(fwd + left - ccw);
            m[3].setPower(fwd - left + ccw);
        }
    }

    @Test
    void tracksTheRobotThroughTheSdkDriver() throws Exception {
        DrivePattern.moveDuringCalibration = false;
        double trueX, trueY, trueH;
        try (SimHarness h = harness(robot(""), DrivePattern.class)) {
            h.init("DrivePattern").start().runFor(10);
            assertNull(h.error(), h.error());
            trueX = h.xInches();
            trueY = h.yInches();
            trueH = h.headingDegrees();
        }
        assertEquals("CALIBRATING", DrivePattern.statusAfterReset);
        assertEquals("READY", DrivePattern.statusLater);
        System.out.printf("true %.2f %.2f %.2f  pinpoint %.2f %.2f %.2f%n", trueX, trueY, trueH, DrivePattern.x, DrivePattern.y, DrivePattern.heading);
        assertTrue(Math.hypot(trueX, trueY) > 10, "the robot drove somewhere: " + trueX + ", " + trueY);
        // The robot center is the tracking point, so the Pinpoint's pose is the robot's, to within
        // what real pods do (scale, mount angle, seams): about 1-2% of the distance driven.
        assertEquals(trueX, DrivePattern.x, 1.0, "x");
        assertEquals(trueY, DrivePattern.y, 1.0, "y");
        assertEquals(0, AngleUnit.normalizeDegrees(trueH - DrivePattern.heading), 0.5, "heading");
        assertTrue(Math.abs(DrivePattern.turns) > 0.5, "heading counts whole turns: " + DrivePattern.turns);
        assertEquals(667, DrivePattern.loopUs, 25, "loop time");
    }

    @Test
    void tracksFastTurningWhileDriving() throws Exception {
        DrivePattern.combined = true;
        double trueX, trueY, trueH;
        try (SimHarness h = harness(robot("\"ideal\": true, "), DrivePattern.class)) {
            h.init("DrivePattern").start().runFor(5);
            assertNull(h.error(), h.error());
            trueX = h.xInches();
            trueY = h.yInches();
            trueH = h.headingDegrees();
        } finally {
            DrivePattern.combined = false;
        }
        System.out.printf("combined: true %.2f %.2f %.2f  pinpoint %.2f %.2f %.2f%n", trueX, trueY, trueH, DrivePattern.x, DrivePattern.y, DrivePattern.heading);
        assertEquals(trueX, DrivePattern.x, 0.5, "x");
        assertEquals(trueY, DrivePattern.y, 0.5, "y");
    }

    @Test
    void movingWhileItCalibratesMakesTheHeadingDrift() throws Exception {
        DrivePattern.moveDuringCalibration = true;
        try (SimHarness h = harness(robot(""), DrivePattern.class)) {
            h.init("DrivePattern").start().runFor(22);
            assertNull(h.error(), h.error());
        } finally {
            DrivePattern.moveDuringCalibration = false;
        }
        // Spinning during the 0.25 s zeroing: the zero is off by the spin rate, so a still robot's
        // heading creeps by degrees per second afterwards, as goBILDA warns.
        assertTrue(Math.abs(DrivePattern.heading) > 20, "drifted: " + DrivePattern.heading);
    }

    /** A team's own copy of goBILDA's driver (any class with the Pinpoint's xmlTag) works too. */
    @com.qualcomm.robotcore.hardware.configuration.annotations.I2cDeviceType
    @com.qualcomm.robotcore.hardware.configuration.annotations.DeviceProperties(name = "copy", xmlTag = "goBILDAPinpoint")
    public static class CopiedDriver extends com.qualcomm.robotcore.hardware.I2cDeviceSynchDevice<com.qualcomm.robotcore.hardware.I2cDeviceSynchSimple> {
        public CopiedDriver(com.qualcomm.robotcore.hardware.I2cDeviceSynchSimple client, boolean owned) {
            super(client, owned);
            client.setI2cAddress(com.qualcomm.robotcore.hardware.I2cAddr.create7bit(0x31));
        }

        @Override protected boolean doInitialize() {
            ((com.qualcomm.hardware.lynx.LynxI2cDeviceSynch) deviceClient).setBusSpeed(com.qualcomm.hardware.lynx.LynxI2cDeviceSynch.BusSpeed.FAST_400K);
            return true;
        }

        int version() {
            return java.nio.ByteBuffer.wrap(deviceClient.read(2, 4)).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
        }

        @Override public Manufacturer getManufacturer() { return Manufacturer.Other; }
        @Override public String getDeviceName() { return "copy"; }
    }

    public static class UsesCopy extends LinearOpMode {
        static volatile int version;

        @Override
        public void runOpMode() {
            version = hardwareMap.get(CopiedDriver.class, "pinpoint").version();
            waitForStart();
        }
    }

    @Test
    void aTeamsCopiedDriverTalksToTheSameDevice() throws Exception {
        try (SimHarness h = harness(robot("\"version\": \"V1\", "), UsesCopy.class)) {
            h.init("UsesCopy").runFor(0.5);
            assertNull(h.error(), h.error());
        }
        assertEquals(2, UsesCopy.version, "a Pinpoint V1 reports firmware version 2");
    }
}
