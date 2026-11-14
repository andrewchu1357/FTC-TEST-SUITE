package org.firstinspires.ftc.teamcode.calibration;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.VoltageSensor;
import com.qualcomm.robotcore.util.ElapsedTime;
import com.qualcomm.robotcore.util.Range;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;

import java.io.IOException;
import java.util.List;

import static org.firstinspires.ftc.teamcode.calibration.CalibrationConfig.*;

/**
 * Drives the robot through a fixed set of moves and logs what every sensor saw, so the
 * simulator can be fitted to this robot (./gradlew calibrate). Run it on the field tiles with
 * MAX_TRAVEL_IN of clear space in front of and to the left of the robot. It takes about a
 * minute and returns to where it started after each move. Press STOP at any time.
 *
 * Moves: stand still; a slow power ramp forward; short runs forward at 40%, 70% and 100% power,
 * each followed by coasting (FLOAT) and braking; strafes left at 50% and 100%; spins in place
 * at 30%, 60% and 100%, each followed by coasting.
 */
@TeleOp(name = "Calibrate Drive", group = "Calibration")
public class CalibrateDrive extends LinearOpMode {
    private static final double[] FORWARD = {1, 1, 1, 1};
    private static final double[] LEFT = {-1, 1, 1, -1};
    private static final double[] CCW = {-1, 1, -1, 1};
    private static final double STOPPED_TPS = 25;

    private final DcMotorEx[] motors = new DcMotorEx[4];
    private final int[] home = new int[4];
    private final int[] pos = new int[4];
    private final double[] vel = new double[4];
    private final double[] power = new double[4];
    private final ElapsedTime clock = new ElapsedTime();
    private IMU imu;
    private VoltageSensor battery;
    private List<LynxModule> hubs;
    private CalibrationLog log;
    private boolean brake = true, lastBrake = false;
    private String segment = "";
    private double yaw, lastYawReading, yawRate;
    private boolean haveYaw;

    @Override
    public void runOpMode() {
        String[] names = {LEFT_FRONT, RIGHT_FRONT, LEFT_BACK, RIGHT_BACK};
        boolean[] reversed = {REVERSE_LEFT, REVERSE_RIGHT, REVERSE_LEFT, REVERSE_RIGHT};
        for (int i = 0; i < 4; i++) {
            motors[i] = hardwareMap.get(DcMotorEx.class, names[i]);
            motors[i].setDirection(reversed[i] ? DcMotorSimple.Direction.REVERSE : DcMotorSimple.Direction.FORWARD);
            motors[i].setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
            motors[i].setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
            motors[i].setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        }
        imu = hardwareMap.get(IMU.class, IMU);
        imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(IMU_LOGO, IMU_USB)));
        imu.resetYaw();
        battery = hardwareMap.voltageSensor.iterator().next();
        hubs = hardwareMap.getAll(LynxModule.class);
        for (LynxModule hub : hubs) hub.setBulkCachingMode(LynxModule.BulkCachingMode.MANUAL);

        telemetry.addLine("CALIBRATE DRIVE");
        telemetry.addLine(String.format("Put the robot on the field tiles with %.0f in clear in front and to its left.", MAX_TRAVEL_IN));
        telemetry.addLine("It drives forward, left and spins for about a minute, returning to its start after each move.");
        telemetry.addLine("Press START when ready; STOP ends it at any time.");
        telemetry.update();
        waitForStart();
        if (isStopRequested()) return;

        String result;
        try {
            log = new CalibrationLog("drive");
            log.info("motors", LEFT_FRONT + ";" + RIGHT_FRONT + ";" + LEFT_BACK + ";" + RIGHT_BACK);
            log.info("reversed", REVERSE_LEFT + ";" + REVERSE_RIGHT + ";" + REVERSE_LEFT + ";" + REVERSE_RIGHT);
            log.info("imu", IMU_LOGO + ";" + IMU_USB);
            log.header("t", "t_cmd", "segment", "p_lf", "p_rf", "p_lb", "p_rb", "zpb",
                    "pos_lf", "pos_rf", "pos_lb", "pos_rb", "vel_lf", "vel_rf", "vel_lb", "vel_rb",
                    "yaw_deg", "yaw_rate_dps", "volts");
            clock.reset();
            for (int i = 0; i < 4; i++) home[i] = motors[i].getCurrentPosition();
            runTests();
            result = "Done: " + log.rows() + " samples saved to " + log.path();
        } catch (IOException e) {
            result = "Couldn't write the log: " + e.getMessage();
        } finally {
            for (DcMotorEx m : motors) m.setPower(0);
            try {
                if (log != null) log.close();
            } catch (IOException ignored) {
            }
        }
        telemetry.addLine(result);
        telemetry.addLine("Then on the computer: ./gradlew calibrate --args=\"<that file>\"");
        telemetry.update();
        while (opModeIsActive()) idle();
    }

    private void runTests() throws IOException {
        hold("still", 3.0, true);
        ramp("ramp", FORWARD, 0.5, 2.5);
        stop("brake");
        goHome(FORWARD);
        for (double p : new double[]{0.4, 0.7, 1.0}) {
            if (!run("forward", FORWARD, p, 1.5, 0.3)) return;
            coast("coast", FORWARD, 1.0, 0.7);
            stop("brake");
            goHome(FORWARD);
        }
        for (double p : new double[]{0.5, 1.0}) {
            if (!run("strafe", LEFT, p, 1.2, 0.3)) return;
            coast("coast", LEFT, 0.8, 0.6);
            stop("brake");
            goHome(LEFT);
        }
        for (double p : new double[]{0.3, 0.6, 1.0}) {
            if (!turn("turn", p, 1.5, 540)) return;
            coast("spin-down", null, 1.5, 0);
            stop("brake");
        }
        hold("still", 2.0, true);
    }

    // ------------------------------------------------------------------ moves

    /** Reads every sensor, logs them with the powers about to be applied, applies the powers. */
    private boolean tick() throws IOException {
        for (LynxModule hub : hubs) hub.clearBulkCache();
        double t = clock.seconds();
        for (int i = 0; i < 4; i++) {
            pos[i] = motors[i].getCurrentPosition();
            vel[i] = motors[i].getVelocity();
        }
        double reading = imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES);
        yawRate = imu.getRobotAngularVelocity(AngleUnit.DEGREES).zRotationRate;
        if (haveYaw) yaw += AngleUnit.normalizeDegrees(reading - lastYawReading);
        else yaw = reading;
        lastYawReading = reading;
        haveYaw = true;
        double volts = battery.getVoltage();
        if (brake != lastBrake) {
            for (DcMotorEx m : motors) m.setZeroPowerBehavior(brake ? DcMotor.ZeroPowerBehavior.BRAKE : DcMotor.ZeroPowerBehavior.FLOAT);
            lastBrake = brake;
        }
        for (int i = 0; i < 4; i++) motors[i].setPower(power[i]);
        // t: when the sensors were read; t_cmd: when the new powers had reached the hub.
        log.row(t, clock.seconds(), segment, power[0], power[1], power[2], power[3], brake ? "B" : "F",
                pos[0], pos[1], pos[2], pos[3], vel[0], vel[1], vel[2], vel[3], yaw, yawRate, volts);
        return opModeIsActive();
    }

    private void set(String seg, double[] pattern, double p, boolean brakeMode) {
        segment = seg;
        brake = brakeMode;
        for (int i = 0; i < 4; i++) power[i] = pattern == null ? 0 : pattern[i] * p;
    }

    /** Distance travelled along a move pattern since the start, in inches (wheel encoders only). */
    private double travelled(double[] pattern) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < 4; i++) {
            if (!WHEEL_ENCODERS[i]) continue;
            sum += pattern[i] * (pos[i] - home[i]);
            n++;
        }
        if (n == 0) return 0;
        return sum / n / TICKS_PER_WHEEL_REV * Math.PI * WHEEL_DIAMETER_IN;
    }

    private boolean stopped() {
        for (int i = 0; i < 4; i++) if (WHEEL_ENCODERS[i] && Math.abs(vel[i]) > STOPPED_TPS) return false;
        return Math.abs(yawRate) < 3;
    }

    private void hold(String seg, double seconds, boolean brakeMode) throws IOException {
        set(seg, null, 0, brakeMode);
        double end = clock.seconds() + seconds;
        while (clock.seconds() < end && tick()) { }
    }

    /** Power up gradually from 0 to maxPower (slow enough that the robot is never far from steady speed). */
    private void ramp(String seg, double[] pattern, double maxPower, double seconds) throws IOException {
        double start = clock.seconds();
        while (tick()) {
            double f = (clock.seconds() - start) / seconds;
            if (f >= 1 || travelled(pattern) > 0.6 * MAX_TRAVEL_IN) break;
            set(seg, pattern, f * maxPower, true);
        }
    }

    /** Full power p from a standstill until maxSeconds or the distance share of MAX_TRAVEL_IN. */
    private boolean run(String seg, double[] pattern, double p, double maxSeconds, double distanceShare) throws IOException {
        set(seg, pattern, p, true);
        double end = clock.seconds() + maxSeconds;
        while (tick()) {
            if (clock.seconds() > end || travelled(pattern) > distanceShare * MAX_TRAVEL_IN) return true;
        }
        return false;
    }

    /** Power off with the motors floating, until stopped, maxSeconds, or the distance share. */
    private void coast(String seg, double[] pattern, double maxSeconds, double distanceShare) throws IOException {
        set(seg, null, 0, false);
        double end = clock.seconds() + maxSeconds;
        while (tick()) {
            if (clock.seconds() > end || stopped()) break;
            if (pattern != null && travelled(pattern) > distanceShare * MAX_TRAVEL_IN) break;
        }
    }

    private void stop(String seg) throws IOException {
        set(seg, null, 0, true);
        double end = clock.seconds() + 1.5;
        while (tick()) {
            if (clock.seconds() > end || stopped()) break;
        }
        hold(seg, 0.3, true);
    }

    /** Back to the start along the pattern, gently, under encoder control. */
    private void goHome(double[] pattern) throws IOException {
        double end = clock.seconds() + 5;
        while (tick()) {
            double err = -travelled(pattern);
            if ((Math.abs(err) < 0.5 && stopped()) || clock.seconds() > end) break;
            set("return", pattern, Range.clip(0.06 * err, -0.3, 0.3), true);
        }
        stop("brake");
    }

    /** Spin counter-clockwise at power p for maxSeconds or maxDegrees. */
    private boolean turn(String seg, double p, double maxSeconds, double maxDegrees) throws IOException {
        set(seg, CCW, p, true);
        double end = clock.seconds() + maxSeconds, startYaw = yaw;
        while (tick()) {
            if (clock.seconds() > end || Math.abs(yaw - startYaw) > maxDegrees) return true;
        }
        return false;
    }
}
