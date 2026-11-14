package org.firstinspires.ftc.teamcode.calibration;

import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.tools.Calibrate;
import org.ftctestbed.sim.tools.CalibrationData;
import org.ftctestbed.sim.tools.ConfigTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves calibration works before anyone takes a robot out: a stand-in "real robot" with
 * different physics from config/robot.jsonc runs the calibration OpModes, and fitting
 * robot.jsonc to its logs must find the stand-in's values again.
 */
class CalibrationTest {
    static final String FIELD = "{ \"robotStart\": { \"xIn\": 0, \"yIn\": 0, \"headingDeg\": 0 } }";
    @TempDir
    Path dir;
    ExecutorService pool;

    @BeforeEach
    void setUp() {
        System.setProperty("calibration.dir", dir.toString());
        pool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("calibration.dir");
        pool.shutdown();
    }

    /** config/robot.jsonc with some values changed: the "real robot". */
    private static RobotConfig realRobot(String... replacements) throws Exception {
        String text = Files.readString(Path.of("config/robot.jsonc"));
        for (int i = 0; i < replacements.length; i += 2) {
            assertTrue(text.contains(replacements[i]), "config/robot.jsonc should contain " + replacements[i]);
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return RobotConfig.parse(text, "real-robot.jsonc");
    }

    private static Path runOpMode(RobotConfig robot, String opMode) {
        CalibrationLog.lastFile = null;
        try (SimHarness h = SimHarness.of(robot, FieldConfig.parse(FIELD, "field"))) {
            h.init(opMode).start().runUntilStopped(90);
            assertNull(h.error(), h.error());
        }
        assertNotNull(CalibrationLog.lastFile, "the OpMode wrote a log");
        return Path.of(CalibrationLog.lastFile);
    }

    @Test
    void driveCalibrationFindsTheRealRobotsValues() throws Exception {
        RobotConfig real = realRobot(
                "\"wheelFriction\": 0.9", "\"wheelFriction\": 0.72",
                "\"rollerFriction\": 0.2", "\"rollerFriction\": 0.32",
                "\"gearboxFriction\": 0.06,", "\"gearboxFriction\": 0.1,",
                "\"internalResistanceOhms\": 0.1", "\"internalResistanceOhms\": 0.15",
                "\"yawInertiaKgM2\": 0.374", "\"yawInertiaKgM2\": 0.505",
                "\"type\": \"goBILDA-5203-312\"", "\"type\": { \"base\": \"goBILDA-5203-312\", \"freeRpm\": 292, \"stallTorqueKgCm\": 21.5 }");
        Path log = runOpMode(real, "Calibrate Drive");

        Calibrate.Fit fit = Calibrate.fitDrive(ConfigTree.load(Path.of("config/robot.jsonc")), CalibrationData.read(log), pool);
        Calibrate.report(fit, System.out);
        Map<String, Double> v = fit.values();
        assertEquals(292.0 / 312, v.get("drive motor speed (x spec)"), 0.03, "free speed");
        assertEquals(21.5 / 24.3, v.get("drive motor torque (x spec)"), 0.12, "stall torque");
        assertEquals(0.10, v.get("gearboxFriction"), 0.03, "gearbox friction");
        assertEquals(0.72, v.get("wheelFriction"), 0.12, "wheel grip");
        assertEquals(0.32, v.get("rollerFriction"), 0.07, "roller drag");
        assertEquals(0.505, v.get("yawInertiaKgM2"), 0.505 * 0.15, "turning inertia");
        assertEquals(0.15, v.get("battery internalResistanceOhms"), 0.03, "battery resistance");
        assertTrue(fit.after().cost() < fit.before().cost() / 4, "fit improved: " + fit.before().cost() + " -> " + fit.after().cost());
        assertTrue(fit.after().errors().get("wheel speed (% of top)") < 2.5, "wheel speeds match after calibrating");
        // The calibrated config loads, and keeps the user's own battery voltage.
        assertEquals(12.8, fit.tree().toConfig().batteryVolts(), 1e-9);
    }

    @Test
    void mechanismCalibrationFindsTheFlywheelsInertiaAndFriction() throws Exception {
        RobotConfig real = realRobot(
                "\"inertiaKgM2\": 0.00048, \"gearboxFriction\": 0.02", "\"inertiaKgM2\": 0.00072, \"gearboxFriction\": 0.05",
                "{ \"name\": \"shooterLeft\",  \"type\": \"goBILDA-5203-6000\" }",
                "{ \"name\": \"shooterLeft\",  \"type\": { \"base\": \"goBILDA-5203-6000\", \"freeRpm\": 5700 } }",
                "{ \"name\": \"shooterRight\", \"type\": \"goBILDA-5203-6000\",",
                "{ \"name\": \"shooterRight\", \"type\": { \"base\": \"goBILDA-5203-6000\", \"freeRpm\": 5700 },");
        Path log = runOpMode(real, "Calibrate Mechanism");

        Calibrate.Fit fit = Calibrate.fitMechanism(ConfigTree.load(Path.of("config/robot.jsonc")), CalibrationData.read(log), pool);
        Calibrate.report(fit, System.out);
        Map<String, Double> v = fit.values();
        assertEquals(0.00072, v.get("inertiaKgM2"), 0.00072 * 0.12, "flywheel inertia");
        assertEquals(0.05, v.get("gearboxFriction"), 0.015, "flywheel friction");
        assertEquals(5700.0 / 6000, v.get("motor speed (x spec)"), 0.03, "motor free speed");
        assertTrue(fit.after().errors().get("motor speed (% of top)") < 2, "speeds match after calibrating");
    }

    @Test
    void liftCalibrationFindsTheCarriageMassAndFriction() throws Exception {
        RobotConfig real = realRobot(
                "\"massLbs\": 1.26,", "\"massLbs\": 2.0, \"gearboxFriction\": 0.1,",
                "{ \"name\": \"lift\",         \"type\": \"goBILDA-5203-435\" }",
                "{ \"name\": \"lift\", \"type\": { \"base\": \"goBILDA-5203-435\", \"freeRpm\": 413 } }");
        String[] motors = CalibrationConfig.MECHANISM_MOTORS;
        boolean[] reversed = CalibrationConfig.MECHANISM_REVERSED;
        boolean spins = CalibrationConfig.MECHANISM_SPINS;
        int travel = CalibrationConfig.MECHANISM_TRAVEL_TICKS;
        Path log;
        try {
            CalibrationConfig.MECHANISM_MOTORS = new String[]{"lift"};
            CalibrationConfig.MECHANISM_REVERSED = new boolean[]{false};
            CalibrationConfig.MECHANISM_SPINS = false;
            CalibrationConfig.MECHANISM_TRAVEL_TICKS = 1500;
            log = runOpMode(real, "Calibrate Mechanism");
        } finally {
            CalibrationConfig.MECHANISM_MOTORS = motors;
            CalibrationConfig.MECHANISM_REVERSED = reversed;
            CalibrationConfig.MECHANISM_SPINS = spins;
            CalibrationConfig.MECHANISM_TRAVEL_TICKS = travel;
        }
        Calibrate.Fit fit = Calibrate.fitMechanism(ConfigTree.load(Path.of("config/robot.jsonc")), CalibrationData.read(log), pool);
        Calibrate.report(fit, System.out);
        Map<String, Double> v = fit.values();
        assertEquals(2.0 * 0.45359, v.get("mass (kg)"), 2.0 * 0.45359 * 0.15, "carriage mass");
        assertEquals(0.10, v.get("gearboxFriction"), 0.025, "lift friction");
        assertEquals(413.0 / 435, v.get("motor speed (x spec)"), 0.03, "motor free speed");
    }

    @Test
    void turnCalibrationMeasuresTheImuScaleError() throws Exception {
        RobotConfig real = realRobot("\"scaleError\": 0.003", "\"scaleError\": 0.008");
        Calibrate.Fit fit = Calibrate.fitTurn(ConfigTree.load(Path.of("config/robot.jsonc")), CalibrationData.read(runTurn(real)));
        Calibrate.report(fit, System.out);
        assertEquals(0.008, fit.values().get("imu scaleError"), 0.0005, "scale error");
        assertEquals(0.008, fit.tree().toConfig().imu().scaleError(), 0.0005, "written to the calibrated config");
    }

    @Test
    void turnCalibrationMeasuresAPinpointsScaleError() throws Exception {
        String pinpoint = "\"pinpoints\": [ { \"name\": \"pinpoint\", \"yawScaleError\": 0.004,"
                + " \"xPod\": { \"type\": \"goBILDA-4-Bar-Odometry-Pod\", \"yIn\": 3 },"
                + " \"yPod\": { \"type\": \"goBILDA-4-Bar-Odometry-Pod\", \"xIn\": -4 } } ],\n  \"odometryPods\": [";
        RobotConfig real = realRobot("\"odometryPods\": [", pinpoint);
        String heading = CalibrationConfig.TURN_HEADING;
        Path log;
        try {
            CalibrationConfig.TURN_HEADING = "pinpoint";
            log = runTurn(real);
        } finally {
            CalibrationConfig.TURN_HEADING = heading;
        }
        // Fit a config that has the Pinpoint but doesn't know its error yet.
        Path base = dir.resolve("robot.jsonc");
        Files.writeString(base, Files.readString(Path.of("config/robot.jsonc")).replace("\"odometryPods\": [",
                pinpoint.replace("\"yawScaleError\": 0.004,", "")));
        Calibrate.Fit fit = Calibrate.fitTurn(ConfigTree.load(base), CalibrationData.read(log));
        Calibrate.report(fit, System.out);
        assertEquals(0.004, fit.values().get("pinpoint yawScaleError"), 0.0005, "scale error");
        assertEquals(0.004, fit.tree().toConfig().pinpoints().get(0).yawScaleError(), 0.0005, "written to the calibrated config");
    }

    /** Runs Calibrate Turn with a careful simulated driver lining the robot up after each spin. */
    private Path runTurn(RobotConfig real) {
        CalibrationLog.lastFile = null;
        int presses = 0;
        try (SimHarness h = SimHarness.of(real, FieldConfig.parse(FIELD, "field"))) {
            h.init("Calibrate Turn").start();
            double pulse = 0.04, lastErr = Double.NaN;
            int nudges = 0;
            while (CalibrationLog.lastFile == null && h.seconds() < 150) {
                h.runFor(0.05);
                assertNull(h.error(), h.error());
                if (h.telemetry().stream().noneMatch(l -> l.startsWith("LINE IT UP"))) {
                    lastErr = Double.NaN;
                    nudges = 0;
                    continue;
                }
                // A careful driver: steer with the right stick until the robot sits on the seam
                // exactly as it started (true heading 0), then nudge, let it settle, press A.
                double err = normalize(h.headingDegrees());
                while (Math.abs(err) > 1) {
                    float stick = (float) Math.copySign(Math.max(Math.min(Math.abs(err) / 10, 1), 0.4), err);
                    h.gamepad1(g -> g.right_stick_x = stick).runFor(0.02);
                    err = normalize(h.headingDegrees());
                }
                // Let it settle: the OpMode only takes A once its smoothed turn rate is near zero.
                h.gamepad1(g -> g.right_stick_x = 0).runFor(0.8);
                err = normalize(h.headingDegrees());
                if (Math.abs(err) < 0.05 || (nudges > 30 && Math.abs(err) < 0.2)) {
                    h.gamepad1(g -> g.a = true).runFor(0.1).gamepad1(g -> g.a = false).runFor(0.3);
                    presses++;
                    continue;
                }
                if (!Double.isNaN(lastErr)) {
                    if (Math.signum(err) != Math.signum(lastErr)) pulse = Math.max(pulse / 2, 0.02);
                    else if (Math.abs(lastErr - err) < 0.02) pulse = Math.min(pulse * 2, 0.2);
                }
                lastErr = err;
                nudges++;
                float stick = (float) Math.copySign(0.4, err);
                h.gamepad1(g -> g.right_stick_x = stick).runFor(pulse).gamepad1(g -> g.right_stick_x = 0);
            }
            assertNull(h.error(), h.error());
        }
        assertEquals(2, presses, "lined up after each direction");
        assertNotNull(CalibrationLog.lastFile, "the OpMode wrote a log");
        return Path.of(CalibrationLog.lastFile);
    }

    private static double normalize(double deg) {
        deg %= 360;
        return deg > 180 ? deg - 360 : deg < -180 ? deg + 360 : deg;
    }

    @Test
    void imuCalibrationMeasuresDriftAndJitter() throws Exception {
        RobotConfig real = realRobot("\"driftDegPerMin\": 0.03, \"noiseDeg\": 0.03", "\"driftDegPerMin\": 0.8, \"noiseDeg\": 0.05");
        Path log = runOpMode(real, "Calibrate IMU");
        Calibrate.Fit fit = Calibrate.fitImu(ConfigTree.load(Path.of("config/robot.jsonc")), CalibrationData.read(log));
        Calibrate.report(fit, System.out);
        assertEquals(0.8, fit.values().get("imu driftDegPerMin"), 0.12, "drift");
        assertEquals(0.05, fit.values().get("imu noiseDeg"), 0.01, "jitter");
        assertEquals(0.8, Math.toDegrees(fit.tree().toConfig().imu().driftRadPerSec()) * 60, 0.12, "written to the calibrated config");
    }
}
