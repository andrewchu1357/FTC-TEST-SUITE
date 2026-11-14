package org.ftctestbed.sim;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.exception.TargetPositionNotSetException;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.DistanceSensor;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.hardware.TouchSensor;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SimulationTest {
    private static final double IN = 0.0254;

    static final String ROBOT = """
            {
              "lengthIn": 18, "widthIn": 18, "massLbs": 30,
              "drivetrain": {
                "type": "mecanum", "wheelDiameterMm": 104, "trackWidthIn": 14.5, "wheelbaseIn": 13,
                "motors": {
                  "frontLeft":  { "name": "lf", "type": "goBILDA-5203-312", "mountedReversed": true },
                  "frontRight": { "name": "rf", "type": "goBILDA-5203-312" },
                  "backLeft":   { "name": "lb", "type": "goBILDA-5203-312", "mountedReversed": true },
                  "backRight":  { "name": "rb", "type": "goBILDA-5203-312" }
                }
              },
              "motors": [ { "name": "lift", "type": "goBILDA-5203-435" } ],
              "mechanisms": [ { "name": "lift", "type": "slide", "motors": ["lift"], "travelIn": 28, "massLbs": 3.5 } ],
              "limitSwitches": [ { "name": "liftDown", "mechanism": "lift", "pressedBelow": 0.25 } ],
              "servos": [ { "name": "claw" } ],
              "distanceSensors": [ { "name": "front", "xIn": 9, "maxRangeIn": 78, "noiseMm": 0, "noisePercent": 0 } ],
              "imu": { "driftDegPerMin": 0, "noiseDeg": 0, "scaleError": 0 }
            }
            """;

    static final String FIELD = """
            { "robotStart": { "xIn": 0, "yIn": 0, "headingDeg": 90 } }
            """;

    Simulation sim;
    DcMotorEx lf, rf, lb, rb;

    @BeforeEach
    void setUp() {
        sim = new Simulation(RobotConfig.parse(ROBOT, "robot"), FieldConfig.parse(FIELD, "field"));
        lf = sim.hardwareMap.get(DcMotorEx.class, "lf");
        rf = sim.hardwareMap.get(DcMotorEx.class, "rf");
        lb = sim.hardwareMap.get(DcMotorEx.class, "lb");
        rb = sim.hardwareMap.get(DcMotorEx.class, "rb");
        lf.setDirection(DcMotorSimple.Direction.REVERSE);
        lb.setDirection(DcMotorSimple.Direction.REVERSE);
    }

    void run(double seconds) {
        int steps = (int) Math.round(seconds / Simulation.DT);
        for (int i = 0; i < steps; i++) sim.step();
    }

    void drive(double fl, double fr, double bl, double br) {
        lf.setPower(fl);
        rf.setPower(fr);
        lb.setPower(bl);
        rb.setPower(br);
    }

    @Test
    void drivesStraightForwardAtRealisticSpeed() {
        sim.moveRobot(0, -60 * IN, Math.PI / 2);
        drive(1, 1, 1, 1);
        run(1.5);
        Snapshot s = sim.snapshot();
        double speedInPerSec = Math.hypot(s.vx(), s.vy()) / IN;
        // Free speed of 312 rpm on 104 mm wheels is ~67 in/s; friction and load take some off.
        assertTrue(speedInPerSec > 50 && speedInPerSec < 67, "top speed " + speedInPerSec);
        assertTrue(s.y() > -60 * IN + 0.5, "moved forward (+y): " + s.y());
        assertEquals(0, s.x(), 0.01, "no sideways drift");
        assertEquals(Math.PI / 2, s.heading(), Math.toRadians(0.5), "no rotation");
        assertTrue(lf.getCurrentPosition() > 500 && rf.getCurrentPosition() > 500, "encoders count up going forward");
    }

    @Test
    void forgettingToReverseLeftMotorsSpinsInPlace() {
        lf.setDirection(DcMotorSimple.Direction.FORWARD);
        lb.setDirection(DcMotorSimple.Direction.FORWARD);
        drive(0.5, 0.5, 0.5, 0.5);
        run(1.0);
        Snapshot s = sim.snapshot();
        assertTrue(Math.hypot(s.x(), s.y()) < 0.02, "barely translates");
        assertTrue(Math.abs(s.omega()) > 1.0, "spins: " + s.omega());
    }

    @Test
    void strafesRightSlowerThanForward() {
        sim.moveRobot(-60 * IN, 0, Math.PI / 2);
        drive(1, -1, -1, 1);
        run(1.5);
        Snapshot s = sim.snapshot();
        // Facing +y, robot-right is +x.
        assertTrue(s.x() > -60 * IN + 0.4, "strafed right: " + s.x());
        assertEquals(0, s.y(), 0.03);
        assertEquals(Math.PI / 2, s.heading(), Math.toRadians(1));
        double strafe = Math.abs(s.vx());

        setUp();
        sim.moveRobot(0, -60 * IN, Math.PI / 2);
        drive(1, 1, 1, 1);
        run(1.5);
        double forward = Math.abs(sim.snapshot().vy());
        assertTrue(strafe < forward * 0.97 && strafe > forward * 0.6, "strafe " + strafe + " vs forward " + forward);
    }

    @Test
    void runToPositionReachesTargetAndClearsBusy() {
        sim.moveRobot(0, -60 * IN, Math.PI / 2);
        for (DcMotorEx m : new DcMotorEx[]{lf, rf, lb, rb}) {
            m.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
            m.setTargetPosition(1000);
            m.setMode(DcMotor.RunMode.RUN_TO_POSITION);
            m.setPower(0.5);
        }
        run(0.2);
        assertTrue(lf.isBusy());
        run(3.0);
        assertFalse(lf.isBusy(), "position " + lf.getCurrentPosition());
        assertEquals(1000, lf.getCurrentPosition(), 15);
        // 1000 ticks of a 537.7-tick motor on a 104 mm wheel = ~23.9 in. A little wheel slip
        // when the velocity loop launches at full power makes the real distance slightly short.
        assertEquals(23.9, sim.snapshot().y() / IN + 60, 1.0);
    }

    @Test
    void runToPositionWithoutTargetThrowsLikeTheSdk() {
        assertThrows(TargetPositionNotSetException.class, () -> lf.setMode(DcMotor.RunMode.RUN_TO_POSITION));
    }

    @Test
    void imuYawTracksRotation() {
        IMU imu = sim.hardwareMap.get(IMU.class, "imu");
        imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.UP, RevHubOrientationOnRobot.UsbFacingDirection.FORWARD)));
        assertEquals(0, imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES), 0.01);
        drive(-0.4, 0.4, -0.4, 0.4); // turn left (CCW)
        run(0.6);
        drive(0, 0, 0, 0);
        run(0.5);
        double turned = Math.toDegrees(sim.snapshot().heading() - Math.PI / 2);
        double yaw = imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES);
        assertTrue(turned > 20, "turned " + turned);
        assertEquals(turned, yaw, 0.01);
        assertEquals(0, imu.getRobotYawPitchRollAngles().getPitch(AngleUnit.DEGREES), 0.01);
        imu.resetYaw();
        assertEquals(0, imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES), 0.01);
    }

    @Test
    void realisticImuErrorGrowsWithEveryTurn() {
        Simulation real = new Simulation(RobotConfig.parse(ROBOT.replace(
                "\"imu\": { \"driftDegPerMin\": 0, \"noiseDeg\": 0, \"scaleError\": 0 }", "\"imu\": { \"scaleError\": 0.003 }"), "robot"),
                FieldConfig.parse(FIELD, "field"));
        IMU imu = real.hardwareMap.get(IMU.class, "imu");
        imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.UP, RevHubOrientationOnRobot.UsbFacingDirection.FORWARD)));
        DcMotorEx[] m = {real.hardwareMap.get(DcMotorEx.class, "lf"), real.hardwareMap.get(DcMotorEx.class, "rf"),
                real.hardwareMap.get(DcMotorEx.class, "lb"), real.hardwareMap.get(DcMotorEx.class, "rb")};
        double[] spin = {0.6, 0.6, 0.6, 0.6}; // lf/lb are reversed in config, so equal power turns in place
        for (int i = 0; i < 4; i++) m[i].setPower(spin[i]);
        double turned = 0, last = real.robotHeading();
        for (int i = 0; i < 4000; i++) {
            real.step();
            double h = real.robotHeading();
            turned += Math.atan2(Math.sin(h - last), Math.cos(h - last));
            last = h;
        }
        for (DcMotorEx motor : m) motor.setPower(0);
        for (int i = 0; i < 500; i++) real.step();
        turned += Math.atan2(Math.sin(real.robotHeading() - last), Math.cos(real.robotHeading() - last));
        double turns = Math.abs(turned) / (2 * Math.PI);
        assertTrue(turns > 3, "spun " + turns + " turns");
        double trueYaw = Math.toDegrees(real.robotHeading() - Math.PI / 2);
        double yaw = imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES);
        double error = Math.toDegrees(AngleUnit.normalizeRadians(Math.toRadians(yaw - trueYaw)));
        // 0.3% of every turn: about 1.1 degrees per turn, in the direction of the spin.
        assertEquals(Math.signum(turned) * 0.003 * Math.toDegrees(Math.abs(turned)), error, 0.3,
                "IMU error after " + turns + " turns");
    }

    @Test
    void distanceSensorMeasuresEvery33msWithNoise() {
        Simulation real = new Simulation(RobotConfig.parse(ROBOT.replace("\"noiseMm\": 0, \"noisePercent\": 0", "\"zIn\": 3"), "robot"),
                FieldConfig.parse(FIELD, "field"));
        DistanceSensor front = real.hardwareMap.get(DistanceSensor.class, "front");
        real.step();
        double first = front.getDistance(DistanceUnit.INCH);
        for (int i = 0; i < 10; i++) real.step(); // 20 ms: still the same measurement
        assertEquals(first, front.getDistance(DistanceUnit.INCH), 0.0);
        double sum = 0, sumSq = 0;
        int n = 200;
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < 17; i++) real.step(); // 34 ms: a new measurement each time
            double d = front.getDistance(DistanceUnit.INCH);
            sum += d;
            sumSq += d * d;
        }
        double mean = sum / n, sd = Math.sqrt(sumSq / n - mean * mean);
        assertEquals(63, mean, 0.3, "mean of noisy readings");
        // 3 mm + 1.5% of 1.6 m = 27 mm = 1.07 in
        assertEquals(1.07, sd, 0.25, "spread of readings");
    }

    @Test
    void wrongImuOrientationGivesWrongReadings() {
        IMU imu = sim.hardwareMap.get(IMU.class, "imu");
        // Hub is really logo UP / USB FORWARD, but the code claims logo FORWARD / USB UP.
        imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.FORWARD, RevHubOrientationOnRobot.UsbFacingDirection.UP)));
        double pitch = imu.getRobotYawPitchRollAngles().getPitch(AngleUnit.DEGREES);
        double roll = imu.getRobotYawPitchRollAngles().getRoll(AngleUnit.DEGREES);
        assertTrue(Math.abs(pitch) > 45 || Math.abs(roll) > 45, "pitch " + pitch + " roll " + roll);
    }

    @Test
    void liftRisesUnderPowerAndFallsWhenFloating() {
        DcMotorEx lift = sim.hardwareMap.get(DcMotorEx.class, "lift");
        TouchSensor down = sim.hardwareMap.get(TouchSensor.class, "liftDown");
        run(0.1);
        assertTrue(down.isPressed());
        lift.setPower(1.0);
        run(1.5);
        assertFalse(down.isPressed());
        int top = lift.getCurrentPosition();
        assertTrue(top > 1500, "lift encoder " + top);

        lift.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        lift.setPower(0);
        run(0.3);
        int braked = lift.getCurrentPosition();
        assertTrue(braked > top - 400, "brake mode slows the fall: " + top + " -> " + braked);

        lift.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);
        run(3.0);
        assertTrue(down.isPressed(), "a floating lift falls back down");
    }

    @Test
    void manualBulkCachingReturnsStaleValuesUntilCleared() {
        LynxModule hub = sim.hardwareMap.getAll(LynxModule.class).get(0);
        hub.setBulkCachingMode(LynxModule.BulkCachingMode.MANUAL);
        int first = lf.getCurrentPosition();
        drive(1, 1, 1, 1);
        run(0.5);
        assertEquals(first, lf.getCurrentPosition(), "stale until clearBulkCache()");
        hub.clearBulkCache();
        assertTrue(lf.getCurrentPosition() > first + 50);
    }

    @Test
    void distanceSensorSeesTheWall() {
        DistanceSensor front = sim.hardwareMap.get(DistanceSensor.class, "front");
        run(0.04);
        // Robot at center facing +y; sensor 9 in ahead of center; wall at 72 in.
        assertEquals(72 - 9, front.getDistance(DistanceUnit.INCH), 0.2);
        sim.moveRobot(0, -60 * IN, -Math.PI / 2);
        run(0.04); // the sensor measures every 33 ms
        assertEquals(72 - 60 - 9, front.getDistance(DistanceUnit.INCH), 0.2);
        sim.moveRobot(0, 30 * IN, Math.PI / 2);
        run(0.04);
        assertEquals(72 - 30 - 9, front.getDistance(DistanceUnit.INCH), 0.2);
    }

    @Test
    void servoSlewsAtRatedSpeed() {
        Servo claw = sim.hardwareMap.get(Servo.class, "claw");
        claw.setPosition(1.0);
        run(0.1);
        double partway = sim.servoPorts().get(0).angle();
        run(1.0);
        double end = sim.servoPorts().get(0).angle();
        // Default PWM range 600-2400 us on a 300 deg servo -> +135 deg at position 1.
        assertEquals(135, Math.toDegrees(end), 0.5);
        assertTrue(Math.toDegrees(partway) < 60, "takes time to move: " + Math.toDegrees(partway));
    }

    @Test
    void missingDeviceErrorMatchesSdk() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> sim.hardwareMap.get(DcMotor.class, "nope"));
        assertTrue(e.getMessage().contains("Unable to find a hardware device with name \"nope\""));
    }

    @Test
    void shippedConfigFilesLoadCleanly() {
        for (String[] pair : new String[][]{
                {"../config/robot.jsonc", "../config/field.jsonc"},
                {"../config/examples/starter-robot.jsonc", "../config/examples/blank-field.jsonc"}}) {
            RobotConfig robot = RobotConfig.load(Path.of(pair[0]));
            FieldConfig field = FieldConfig.load(Path.of(pair[1]));
            assertTrue(robot.warnings().isEmpty(), robot.warnings().toString());
            assertTrue(field.warnings().isEmpty(), field.warnings().toString());
            Simulation s = new Simulation(robot, field);
            assertTrue(s.runtimeWarnings().isEmpty(), s.runtimeWarnings().toString());
        }
    }

    /** A still field gives the same scene step after step (so the views skip redrawing it); moving changes it. */
    @Test
    void stillFieldIsTheSameScene() {
        run(1);
        Snapshot a = sim.snapshot();
        run(0.5);
        Snapshot b = sim.snapshot();
        assertNotEquals(a.time(), b.time());
        assertTrue(Snapshot.sameScene(a, b));
        drive(0.5, 0.5, 0.5, 0.5);
        run(0.1);
        assertFalse(Snapshot.sameScene(b, sim.snapshot()));
    }

    /** Step clicks while paused all run, even when they come faster than the steps. */
    @Test
    void everyManualStepRuns() throws Exception {
        SimRunner runner = new SimRunner(sim);
        runner.setPaused(true);
        runner.start();
        try {
            long start = sim.clock.tick();
            Thread[] clickers = new Thread[4];
            for (int i = 0; i < clickers.length; i++) {
                clickers[i] = new Thread(() -> { for (int k = 0; k < 500; k++) runner.stepManually(1); });
                clickers[i].start();
            }
            for (Thread t : clickers) t.join();
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (sim.clock.tick() - start < 2000 && System.nanoTime() < deadline) Thread.sleep(5);
            Thread.sleep(50);
            assertEquals(2000, sim.clock.tick() - start);
        } finally {
            runner.shutdown();
        }
    }
}
