package org.firstinspires.ftc.teamcode.sim;

import com.qualcomm.hardware.andymark.AndyMarkColorSensor;
import com.qualcomm.hardware.andymark.AndyMarkIMU;
import com.qualcomm.hardware.andymark.AndyMarkIMUOrientationOnRobot;
import com.qualcomm.hardware.andymark.AndyMarkTOF;
import com.qualcomm.hardware.bosch.BHI260IMU;
import com.qualcomm.hardware.digitalchickenlabs.OctoQuad;
import com.qualcomm.hardware.rev.Rev2mDistanceSensor;
import com.qualcomm.hardware.rev.RevBlinkinLedDriver;
import com.qualcomm.hardware.rev.RevColorSensorV3;
import com.qualcomm.hardware.rev.RevTouchSensor;
import com.qualcomm.hardware.sparkfun.SparkFunLEDStick;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.Utility;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.DigitalChannel;
import com.qualcomm.robotcore.hardware.DistanceSensor;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.LED;
import com.qualcomm.robotcore.hardware.NormalizedColorSensor;
import com.qualcomm.robotcore.hardware.ServoImplEx;
import com.qualcomm.robotcore.hardware.TouchSensor;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.Snapshot;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** What SDK 11.1 - 12.0 added, and the devices teams use that came with it, working in the simulator. */
class Sdk12Test {
    static final String FIELD = "{ \"robotStart\": { \"xIn\": 0, \"yIn\": 0, \"headingDeg\": 0 } }";
    static final double MM = 25.4;

    static final String DEVICES = """
            "lights": [
              { "name": "redLed", "type": "led", "hub": "Control Hub", "port": 0, "color": "red" },
              { "name": "greenLed", "type": "led", "hub": "Control Hub", "port": 1, "color": "green" },
              { "name": "blinkin", "type": "blinkin" },
              { "name": "stick", "type": "ledStick" }
            ],
            "distanceSensors": [
              { "name": "front", "type": "AndyMark-Distance-Sensor", "xIn": 9 },
              { "name": "side", "type": "REV-2m-Distance-Sensor", "yIn": 9, "headingDeg": 90 }
            ],
            "octoquads": [ { "name": "octoquad", %s"channels": [
              { "port": 0, "type": "goBILDA-4-Bar-Odometry-Pod", "xIn": 0, "yIn": 3.5, "direction": "forward" %s},
              { "port": 1, "type": "goBILDA-4-Bar-Odometry-Pod", "xIn": -2, "yIn": 0, "direction": "lateral" %s} ] } ],
            "limitSwitches": [""";

    /** BuzzBot with the devices above (ideal: perfect dead wheels and OctoQuad IMU), and an IMU line replaced if given. */
    static RobotConfig robot(boolean ideal, String imu) throws Exception {
        String text = Files.readString(Path.of("config/robot.jsonc"));
        String i = ideal ? "\"ideal\": true, " : "", p = ideal ? ", \"ideal\": true " : "";
        text = text.replace("\"limitSwitches\": [", String.format(DEVICES, i, p, p));
        if (imu != null) text = text.replaceFirst("\"imu\": \\{[^}]*\\}", imu);
        return RobotConfig.parse(text, "robot.jsonc");
    }

    @SafeVarargs
    static SimHarness harness(RobotConfig robot, Class<? extends OpMode>... ops) {
        List<OpModeInfo> infos = new java.util.ArrayList<>();
        for (Class<? extends OpMode> op : ops) infos.add(new OpModeInfo(op.getSimpleName(), "", false, null, op));
        return SimHarness.of(robot, FieldConfig.parse(FIELD, "field"), new OpModeRegistry.Result(infos, List.of()));
    }

    static DcMotorEx[] driveMotors(com.qualcomm.robotcore.hardware.HardwareMap hw) {
        DcMotorEx[] m = {hw.get(DcMotorEx.class, "leftFront"), hw.get(DcMotorEx.class, "rightFront"),
                hw.get(DcMotorEx.class, "leftBack"), hw.get(DcMotorEx.class, "rightBack")};
        m[0].setDirection(DcMotorSimple.Direction.REVERSE);
        m[2].setDirection(DcMotorSimple.Direction.REVERSE);
        return m;
    }

    static void drive(DcMotorEx[] m, double fwd, double left, double ccw) {
        m[0].setPower(fwd - left - ccw);
        m[1].setPower(fwd + left + ccw);
        m[2].setPower(fwd + left - ccw);
        m[3].setPower(fwd - left + ccw);
    }

    // ---------------------------------------------------------------- gamepad (SDK 11.1)

    @Test
    void triggersReadAsButtonsWithTheirOwnPressAndRelease() {
        Gamepad g = new Gamepad(), in = new Gamepad();
        in.left_trigger = 0.6f;
        g.simApply(in);
        assertTrue(g.left_trigger_pressed, "pulled past the 0.5 default");
        assertFalse(g.right_trigger_pressed);
        assertTrue(g.leftTriggerWasPressed());
        assertFalse(g.leftTriggerWasPressed(), "once per press");
        in.left_trigger = 0.4f;
        g.simApply(in);
        assertFalse(g.left_trigger_pressed);
        assertTrue(g.leftTriggerWasReleased());

        g.setTriggerThreshold(0.3f);
        in.right_trigger = 0.35f;
        g.simApply(in);
        assertTrue(g.right_trigger_pressed, "a lighter threshold");
        assertTrue(g.left_trigger_pressed, "0.4 is past 0.3 too");
        g.resetEdgeDetection();
        assertFalse(g.rightTriggerWasPressed(), "forgotten");
        assertFalse(g.leftTriggerWasPressed());
    }

    // ---------------------------------------------------------------- @Utility (SDK 11.2)

    @Utility(name = "Check Hardware", description = "a utility for the test")
    public static class CheckHardware extends LinearOpMode {
        @Override public void runOpMode() { waitForStart(); }
    }

    @Test
    void utilityOpModesAreOnTheirOwnList() throws Exception {
        Path testClasses = Path.of(Sdk12Test.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        OpModeRegistry.Result found = OpModeRegistry.scan(List.of(testClasses.toString()), Sdk12Test.class.getClassLoader());
        OpModeInfo info = found.opModes().stream().filter(i -> i.name().equals("Check Hardware")).findFirst().orElseThrow();
        assertTrue(info.utility());
        assertFalse(info.teleOp(), "not a TeleOp for matches or the Sandbox");
        assertFalse(info.autonomous());
        assertEquals("Utility · Check Hardware", info.label());
    }

    // ---------------------------------------------------------------- OpMode.blackboard

    public static class LeavePose extends LinearOpMode {
        @Override
        public void runOpMode() {
            blackboard.put("pose", "robot " + hardwareMap.get(com.qualcomm.robotcore.hardware.HardwareDevice.class, "imu").getConnectionInfo() + " " + getClass().getSimpleName());
            blackboard.put("heading", 90.0);
            waitForStart();
        }
    }

    public static class ReadPose extends LinearOpMode {
        static final Map<Integer, Object> seen = new ConcurrentHashMap<>();
        static volatile int robot;

        @Override
        public void runOpMode() {
            Object h = blackboard.get("heading");
            seen.put(robot, h == null ? "nothing" : h);
            waitForStart();
        }
    }

    @Test
    void theBlackboardKeepsAValueForTheNextOpModeOnThatRobotOnly() throws Exception {
        RobotConfig r = robot(false, null);
        OpModeRegistry.Result ops = new OpModeRegistry.Result(List.of(
                new OpModeInfo("LeavePose", "", true, null, LeavePose.class),
                new OpModeInfo("ReadPose", "", false, null, ReadPose.class)), List.of());
        ReadPose.seen.clear();
        try (SimHarness h = SimHarness.of(List.of(r, r), FieldConfig.load(Path.of("config/field.jsonc")), List.of(ops, ops))) {
            h.robot(1).init("LeavePose").start();
            h.runFor(0.2);
            h.robot(1).stop();
            ReadPose.robot = 1;
            h.robot(1).init("ReadPose");
            h.runFor(0.2);
            ReadPose.robot = 2;
            h.robot(2).init("ReadPose");
            h.runFor(0.2);
            assertNull(h.robot(1).error(), h.robot(1).error());
            assertNull(h.robot(2).error(), h.robot(2).error());
        }
        assertEquals(90.0, ReadPose.seen.get(1), "robot 1's TeleOp gets what its AUTO left");
        assertEquals("nothing", ReadPose.seen.get(2), "robot 2 has its own Robot Controller");

        try (SimHarness h = harness(r, ReadPose.class)) {
            ReadPose.robot = 3;
            h.init("ReadPose");
            h.runFor(0.2);
        }
        assertEquals("nothing", ReadPose.seen.get(3), "a new simulator starts with an empty blackboard, like a restarted Robot Controller");
    }

    // ---------------------------------------------------------------- devices

    public static class UseDevices extends LinearOpMode {
        static final Map<String, Object> seen = new ConcurrentHashMap<>();

        @Override
        public void runOpMode() {
            seen.clear();
            DistanceSensor front = hardwareMap.get(DistanceSensor.class, "front");
            seen.put("front", front instanceof AndyMarkTOF ? "AndyMarkTOF" : front.getClass().getSimpleName());
            seen.put("side", hardwareMap.get(DistanceSensor.class, "side") instanceof Rev2mDistanceSensor);
            seen.put("modelId", hardwareMap.get(Rev2mDistanceSensor.class, "side").getModelID());
            seen.put("color", hardwareMap.get(NormalizedColorSensor.class, "hopperColor") instanceof RevColorSensorV3);
            seen.put("imu", hardwareMap.get(IMU.class, "imu") instanceof BHI260IMU);
            TouchSensor touch = hardwareMap.get(TouchSensor.class, "liftDown");
            seen.put("touch", touch instanceof RevTouchSensor && hardwareMap.get(DigitalChannel.class, "liftDown") == touch);
            seen.put("imus", hardwareMap.getAll(IMU.class).size());
            seen.put("distances", hardwareMap.getAll(DistanceSensor.class).size());

            LED red = hardwareMap.get(LED.class, "redLed");
            LED green = hardwareMap.get(LED.class, "greenLed");
            RevBlinkinLedDriver blinkin = hardwareMap.get(RevBlinkinLedDriver.class, "blinkin");
            SparkFunLEDStick stick = hardwareMap.get(SparkFunLEDStick.class, "stick");
            red.on();
            green.off();
            blinkin.setPattern(RevBlinkinLedDriver.BlinkinPattern.BLUE);
            stick.setColor(0x00FF00);
            stick.setColor(3, 0xFF0000);
            seen.put("redOn", red.isLightOn());

            ServoImplEx claw = hardwareMap.get(ServoImplEx.class, "claw");
            claw.setPulseWidth(1500);
            seen.put("pulse", claw.getPulseWidth());
            seen.put("position", claw.getPosition());

            telemetry.addData("pi", Math.PI);
            telemetry.addData("half", 0.5f);
            telemetry.update();
            waitForStart();
            while (opModeIsActive()) idle();
        }
    }

    @Test
    void robotCodeGetsTheSdkClassForEachDeviceAndItsLightsShow() throws Exception {
        try (SimHarness h = harness(robot(false, null), UseDevices.class)) {
            h.init("UseDevices");
            h.runFor(1.0);
            assertNull(h.error(), h.error());
            Map<String, Object> seen = UseDevices.seen;
            assertEquals("AndyMarkTOF", seen.get("front"));
            assertEquals(true, seen.get("side"));
            assertEquals((byte) 0xEE, seen.get("modelId"), "a VL53L0X");
            assertEquals(true, seen.get("color"), "a REV Color Sensor V3 unless the config says otherwise");
            assertEquals(true, seen.get("imu"), "the hub's BHI260AP");
            assertEquals(true, seen.get("touch"), "a limit switch is a RevTouchSensor and a DigitalChannel");
            assertEquals(1, seen.get("imus"), "one IMU, not one per class");
            assertEquals(3, seen.get("distances"), "the two distance sensors, and the color sensor (a V3 measures distance too)");
            assertEquals(true, seen.get("redOn"));
            assertEquals(1500.0, (double) seen.get("pulse"), 1e-9);
            assertEquals(0.5, (double) seen.get("position"), 1e-6, "1500 us is the middle of the 600-2400 us range");
            assertTrue(h.telemetry().contains("pi : 3.1416"), "doubles show 4 decimals, like the Driver Station: " + h.telemetry());
            assertTrue(h.telemetry().contains("half : 0.5"), h.telemetry().toString());

            Map<String, int[]> lights = new java.util.HashMap<>();
            for (Snapshot.Light l : h.sim.snapshot().lights()) lights.put(l.name(), l.colors());
            assertEquals(0xFF2A1A, lights.get("redLed")[0], "lit");
            assertEquals(0, lights.get("greenLed")[0], "off");
            assertEquals(0x0000FF, lights.get("blinkin")[0], "the Blinkin's BLUE pattern (1935 us)");
            int[] stick = lights.get("stick");
            assertEquals(10, stick.length);
            assertEquals(0x00FF00, stick[0]);
            assertEquals(0xFF0000, stick[2], "LED 3");
            Snapshot.Servo claw = h.sim.snapshot().servos().stream().filter(s -> s.name().equals("claw")).findFirst().orElseThrow();
            assertEquals(0, claw.angle(), 0.02, "the claw went to the middle of its travel");
        }
    }

    // ---------------------------------------------------------------- AndyMark IMU

    public static class SpinAndyMark extends LinearOpMode {
        static volatile double yaw;
        static volatile boolean andyMark;

        @Override
        public void runOpMode() {
            IMU imu = hardwareMap.get(IMU.class, "imu");
            andyMark = imu instanceof AndyMarkIMU;
            imu.initialize(new IMU.Parameters(new AndyMarkIMUOrientationOnRobot(
                    AndyMarkIMUOrientationOnRobot.LogoFacingDirection.UP, AndyMarkIMUOrientationOnRobot.I2cPortFacingDirection.BACKWARD)));
            imu.resetYaw();
            DcMotorEx[] m = driveMotors(hardwareMap);
            waitForStart();
            double end = getRuntime() + 0.7;
            while (opModeIsActive() && getRuntime() < end) drive(m, 0, 0, 0.5);
            drive(m, 0, 0, 0);
            sleep(500);
            yaw = imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES);
            while (opModeIsActive()) idle();
        }
    }

    @Test
    void anAndyMarkImuFollowsTheRobotWhenItsMountingIsDescribed() throws Exception {
        String imu = "\"imu\": { \"name\": \"imu\", \"chip\": \"AndyMark\", \"logo\": \"UP\", \"i2cPort\": \"BACKWARD\", \"driftDegPerMin\": 0, \"noiseDeg\": 0, \"scaleError\": 0 }";
        try (SimHarness h = harness(robot(false, imu), SpinAndyMark.class)) {
            h.init("SpinAndyMark").start().runFor(2.0);
            assertNull(h.error(), h.error());
            assertTrue(SpinAndyMark.andyMark);
            assertTrue(Math.abs(h.headingDegrees()) > 30, "it turned: " + h.headingDegrees());
            assertEquals(h.headingDegrees(), SpinAndyMark.yaw, 0.5);
        }
    }

    // ---------------------------------------------------------------- OctoQuad

    /** Sets up the localizer as Digital Chicken Labs' quickstart guide says, then drives a pattern. */
    public static class OctoQuadDrive extends LinearOpMode {
        static volatile boolean centerTcp = true, spinOnly, moveWhileCalibrating, setScalar = true;
        static volatile double scalar = 1;
        static volatile double x, y, heading, maxWobble, velocity0, encoderVelocity0;
        static volatile String statusAfterReset, statusLater;

        @Override
        public void runOpMode() {
            OctoQuad oq = hardwareMap.get(OctoQuad.class, "octoquad");
            float cpmm = (float) (2000 / (Math.PI * 32)); // goBILDA 4-bar pod: 2000 counts a turn of a 32 mm wheel
            oq.setLocalizerPortX(0);
            oq.setLocalizerPortY(1);
            oq.setLocalizerCountsPerMM_X(cpmm);
            oq.setLocalizerCountsPerMM_Y(cpmm);
            // The wheels' lines cross 2 in behind and 3.5 in left of the center: move the TCP to the center.
            oq.setLocalizerTcpOffsetMM_X(centerTcp ? (float) (2 * MM) : 0);
            oq.setLocalizerTcpOffsetMM_Y(centerTcp ? (float) (-3.5 * MM) : 0);
            oq.setLocalizerImuHeadingScalar(setScalar ? (float) scalar : 1);
            oq.setLocalizerVelocityIntervalMS(25);
            oq.setAllVelocitySampleIntervals(50);
            DcMotorEx[] m = driveMotors(hardwareMap);
            waitForStart();
            if (moveWhileCalibrating) drive(m, 0, 0, 0.6);
            oq.resetLocalizerAndCalibrateIMU();
            sleep(100);
            statusAfterReset = oq.getLocalizerStatus().name();
            while (opModeIsActive() && oq.getLocalizerStatus() != OctoQuad.LocalizerStatus.RUNNING) sleep(10);
            drive(m, 0, 0, 0);
            statusLater = oq.getLocalizerStatus().name();
            maxWobble = 0;
            if (spinOnly) {
                run(oq, m, 0, 0, 0.6, 3.0);
            } else if (!moveWhileCalibrating) {
                run(oq, m, 0.6, 0, 0, 1.0);
                OctoQuad.EncoderDataBlock enc = oq.readAllEncoderData();
                encoderVelocity0 = enc.velocities[0] * 20;              // counts per 50 ms -> counts/s
                velocity0 = oq.readLocalizerData().velX_mmS;
                run(oq, m, 0, 0.6, 0, 1.0);
                run(oq, m, 0, 0, 0.5, 1.5);
                run(oq, m, -0.4, -0.3, 0.2, 1.5);
            } else {
                run(oq, m, 0, 0, 0, 10);
            }
            run(oq, m, 0, 0, 0, 0.8);
            OctoQuad.LocalizerDataBlock l = oq.readLocalizerData();
            x = l.posX_mm / MM;
            y = l.posY_mm / MM;
            heading = Math.toDegrees(l.heading_rad);
            while (opModeIsActive()) idle();
        }

        private void run(OctoQuad oq, DcMotorEx[] m, double fwd, double left, double ccw, double seconds) {
            double end = getRuntime() + seconds;
            while (opModeIsActive() && getRuntime() < end) {
                drive(m, fwd, left, ccw);
                OctoQuad.LocalizerDataBlock l = oq.readLocalizerData();
                maxWobble = Math.max(maxWobble, Math.hypot(l.posX_mm, l.posY_mm) / MM);
            }
        }
    }

    static void defaults() {
        OctoQuadDrive.centerTcp = true;
        OctoQuadDrive.spinOnly = false;
        OctoQuadDrive.moveWhileCalibrating = false;
        OctoQuadDrive.setScalar = true;
        OctoQuadDrive.scalar = 1;
    }

    @Test
    void anOctoQuadTracksTheRobotFromItsDeadWheelsAndImu() throws Exception {
        defaults();
        try (SimHarness h = harness(robot(true, null), OctoQuadDrive.class)) {
            h.init("OctoQuadDrive").start().runFor(9);
            assertNull(h.error(), h.error());
            assertEquals("WARMING_UP_IMU", OctoQuadDrive.statusAfterReset);
            assertEquals("RUNNING", OctoQuadDrive.statusLater);
            System.out.printf("true %.2f %.2f %.2f  octoquad %.2f %.2f %.2f%n", h.xInches(), h.yInches(), h.headingDegrees(),
                    OctoQuadDrive.x, OctoQuadDrive.y, OctoQuadDrive.heading);
            assertTrue(Math.hypot(h.xInches(), h.yInches()) > 10, "it drove somewhere");
            assertEquals(h.xInches(), OctoQuadDrive.x, 0.6, "x (whole mm, +X forward at reset)");
            assertEquals(h.yInches(), OctoQuadDrive.y, 0.6, "y (+Y left)");
            assertEquals(0, AngleUnit.normalizeDegrees(h.headingDegrees() - OctoQuadDrive.heading), 0.3, "heading");
            assertTrue(OctoQuadDrive.encoderVelocity0 > 1000, "channel velocity is counts per sample interval: " + OctoQuadDrive.encoderVelocity0);
            assertTrue(OctoQuadDrive.velocity0 > 500, "localizer velocity in mm/s: " + OctoQuadDrive.velocity0);
        } finally {
            defaults();
        }
    }

    @Test
    void theTcpOffsetMovesTheTrackedPointToTheRobotsCenter() throws Exception {
        defaults();
        OctoQuadDrive.spinOnly = true;
        double centered, crossing;
        try (SimHarness h = harness(robot(true, null), OctoQuadDrive.class)) {
            h.init("OctoQuadDrive").start().runFor(5);
            assertNull(h.error(), h.error());
            centered = OctoQuadDrive.maxWobble;
        }
        OctoQuadDrive.centerTcp = false;
        try (SimHarness h = harness(robot(true, null), OctoQuadDrive.class)) {
            h.init("OctoQuadDrive").start().runFor(5);
            crossing = OctoQuadDrive.maxWobble;
        } finally {
            defaults();
        }
        // Spinning in place about the center: the center barely moves, the point where the wheels'
        // lines cross (4 in from it) circles around it.
        assertTrue(centered < 1.0, "with the offset the reported point stays put: " + centered);
        assertTrue(crossing > 4, "without it the point wobbles round the center: " + crossing);
    }

    @Test
    void itsImuOverReadsTurnsUntilTheHeadingScalarIsSetAndDriftsIfMovedWhileCalibrating() throws Exception {
        defaults();
        OctoQuadDrive.spinOnly = true;
        OctoQuadDrive.setScalar = false;
        String imperfect = robotText(false);
        RobotConfig r = RobotConfig.parse(imperfect.replace("\"name\": \"octoquad\", ", "\"name\": \"octoquad\", \"imuScaleError\": 0.02, \"driftDegPerMin\": 0, \"noiseDeg\": 0, "), "robot.jsonc");
        double turned, error;
        try (SimHarness h = harness(r, OctoQuadDrive.class)) {
            h.init("OctoQuadDrive").start().runFor(5);
            turned = h.headingDegrees();
            error = AngleUnit.normalizeDegrees(OctoQuadDrive.heading - turned);
        }
        // 3 s at 0.6 spins over a full turn: 2% over-read is degrees off.
        assertTrue(error > 3, "over-reads by its scale error: " + error + " after " + turned);

        OctoQuadDrive.setScalar = true;
        OctoQuadDrive.scalar = 1 / 1.02;
        try (SimHarness h = harness(r, OctoQuadDrive.class)) {
            h.init("OctoQuadDrive").start().runFor(5);
            assertEquals(0, AngleUnit.normalizeDegrees(OctoQuadDrive.heading - h.headingDegrees()), 0.5, "the scalar cancels it");
        }

        OctoQuadDrive.spinOnly = false;
        OctoQuadDrive.moveWhileCalibrating = true;
        try (SimHarness h = harness(r, OctoQuadDrive.class)) {
            h.init("OctoQuadDrive").start().runFor(13);
            assertNull(h.error(), h.error());
            double still = h.headingDegrees();
            assertTrue(Math.abs(AngleUnit.normalizeDegrees(OctoQuadDrive.heading - still)) > 20,
                    "calibrated while turning, so a still robot's heading creeps: " + OctoQuadDrive.heading + " vs " + still);
        } finally {
            defaults();
        }
    }

    static String robotText(boolean ideal) throws Exception {
        String text = Files.readString(Path.of("config/robot.jsonc"));
        String i = ideal ? "\"ideal\": true, " : "", p = ideal ? ", \"ideal\": true " : "";
        return text.replace("\"limitSwitches\": [", String.format(DEVICES, i, p, p));
    }
}
