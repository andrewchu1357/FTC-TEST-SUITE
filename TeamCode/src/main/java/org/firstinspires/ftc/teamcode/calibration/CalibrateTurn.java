package org.firstinspires.ftc.teamcode.calibration;

import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver;
import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.hardware.sparkfun.SparkFunOTOS;
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.util.ElapsedTime;
import com.qualcomm.robotcore.util.Range;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.UnnormalizedAngleUnit;

import java.io.IOException;
import java.util.List;

import static org.firstinspires.ftc.teamcode.calibration.CalibrationConfig.*;

/**
 * Measures the heading sensor's scale error (the hub IMU's, or a Pinpoint's or OTOS's: set
 * CalibrationConfig.TURN_HEADING): how much it over- or under-reads every turn. It's
 * why a robot's heading is a little more wrong after each spin, and it is only measurable
 * against something on the field. ./gradlew calibrate turns the log into the simulated IMU's
 * scaleError.
 *
 * Line one side of the robot up exactly with a tile seam (or a strip of tape) and press START.
 * The robot spins TURNS times counter-clockwise by its own heading and stops near where it
 * started. Line it back up with the seam exactly as it started, with the right stick (or turn it
 * by hand: the wheels are let go while the stick is centred), and press A. Then the same
 * clockwise. Take your time lining up: 1 degree of misalignment is 0.03% of 10 turns.
 */
@TeleOp(name = "Calibrate Turn", group = "Calibration")
public class CalibrateTurn extends LinearOpMode {
    public static int TURNS = 10;
    public static double SPIN_POWER = 0.5;
    /** Turning power at full right stick while lining up. */
    public static double ALIGN_POWER = 0.3;
    private static final double[] CCW = {-1, 1, -1, 1};

    private final DcMotorEx[] motors = new DcMotorEx[4];
    private final ElapsedTime clock = new ElapsedTime();
    private IMU imu;
    private GoBildaPinpointDriver pinpoint;
    private SparkFunOTOS otos;
    private double lastT = Double.NaN;
    private List<LynxModule> hubs;
    private CalibrationLog log;
    private String segment = "";
    private double turnPower;
    private boolean brake = true, lastBrake = false;
    private double yaw, lastYawReading, yawRate;
    private boolean haveYaw;

    @Override
    public void runOpMode() {
        String[] names = {LEFT_FRONT, RIGHT_FRONT, LEFT_BACK, RIGHT_BACK};
        boolean[] reversed = {REVERSE_LEFT, REVERSE_RIGHT, REVERSE_LEFT, REVERSE_RIGHT};
        for (int i = 0; i < 4; i++) {
            motors[i] = hardwareMap.get(DcMotorEx.class, names[i]);
            motors[i].setDirection(reversed[i] ? DcMotorSimple.Direction.REVERSE : DcMotorSimple.Direction.FORWARD);
            motors[i].setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        }
        if (TURN_HEADING.equals("pinpoint")) {
            pinpoint = hardwareMap.get(GoBildaPinpointDriver.class, PINPOINT);
            pinpoint.recalibrateIMU(); // its yaw scalar stays as it is: that's what's measured
        } else if (TURN_HEADING.equals("otos")) {
            otos = hardwareMap.get(SparkFunOTOS.class, OTOS);
            otos.setAngularUnit(AngleUnit.DEGREES);
            otos.setAngularScalar(1.0);
            otos.calibrateImu();
        } else {
            imu = hardwareMap.get(IMU.class, IMU);
            imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(IMU_LOGO, IMU_USB)));
        }
        hubs = hardwareMap.getAll(LynxModule.class);
        for (LynxModule hub : hubs) hub.setBulkCachingMode(LynxModule.BulkCachingMode.MANUAL);

        telemetry.addLine("CALIBRATE TURN");
        telemetry.addLine("Line one side of the robot up exactly with a tile seam, then press START.");
        telemetry.addLine("It spins " + TURNS + " turns each way; you line it back up after each.");
        telemetry.update();
        waitForStart();
        if (isStopRequested()) return;

        String result;
        try {
            log = new CalibrationLog("turn");
            log.info("turns", TURNS);
            log.info("heading", TURN_HEADING);
            log.info("device", TURN_HEADING.equals("pinpoint") ? PINPOINT : TURN_HEADING.equals("otos") ? OTOS : IMU);
            log.header("t", "t_cmd", "segment", "power", "yaw_deg", "yaw_rate_dps");
            clock.reset();
            if (imu != null) imu.resetYaw();
            boolean done = hold("still", 2.0)
                    && spin(1) && align("counter-clockwise") && hold("aligned", 0.6)
                    && spin(-1) && align("clockwise") && hold("aligned", 0.6);
            result = done ? "Done: " + log.rows() + " samples saved to " + log.path()
                    : "Stopped early; the log is incomplete: " + log.path();
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

    /** Reads the heading, logs it with the power about to be applied, applies the power. */
    private boolean tick() throws IOException {
        for (LynxModule hub : hubs) hub.clearBulkCache();
        double t = clock.seconds();
        double reading;
        if (pinpoint != null) {
            pinpoint.update();
            reading = pinpoint.getHeading(UnnormalizedAngleUnit.DEGREES);
        } else if (otos != null) {
            reading = otos.getPosition().h;
        } else {
            reading = imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES);
        }
        double before = yaw;
        if (haveYaw) yaw += AngleUnit.normalizeDegrees(reading - lastYawReading);
        else yaw = reading;
        lastYawReading = reading;
        // The turn rate from the heading itself (the OTOS's rate wraps above 180 deg/s), smoothed
        // over about 0.2 s so heading jitter doesn't look like turning.
        if (haveYaw && t > lastT) {
            double dt = t - lastT;
            yawRate += ((yaw - before) / dt - yawRate) * Math.min(1, dt / 0.2);
        }
        lastT = t;
        haveYaw = true;
        if (brake != lastBrake) {
            for (DcMotorEx m : motors) m.setZeroPowerBehavior(brake ? DcMotor.ZeroPowerBehavior.BRAKE : DcMotor.ZeroPowerBehavior.FLOAT);
            lastBrake = brake;
        }
        for (int i = 0; i < 4; i++) motors[i].setPower(CCW[i] * turnPower);
        log.row(t, clock.seconds(), segment, turnPower, yaw, yawRate);
        return opModeIsActive();
    }

    private boolean hold(String seg, double seconds) throws IOException {
        segment = seg;
        turnPower = 0;
        brake = true;
        double end = clock.seconds() + seconds;
        while (clock.seconds() < end) {
            telemetry.addLine("Measuring: don't touch the robot.");
            telemetry.update();
            if (!tick()) return false;
        }
        return true;
    }

    /** TURNS full turns by the heading sensor (dir +1 counter-clockwise), slowing for the last 90 degrees. */
    private boolean spin(int dir) throws IOException {
        segment = "spin";
        brake = true;
        double target = yaw + dir * TURNS * 360.0;
        double end = clock.seconds() + 30 + TURNS * 6;
        while (tick()) {
            double err = target - yaw;
            if ((Math.abs(err) < 2 && Math.abs(yawRate) < 10) || clock.seconds() > end) {
                turnPower = 0;
                return hold("spin", 0.5);
            }
            double p = Range.clip(err / 90 * SPIN_POWER, -SPIN_POWER, SPIN_POWER);
            turnPower = Math.abs(err) < 2 ? 0 : Math.copySign(Math.max(Math.abs(p), 0.12), err);
            telemetry.addData("Spinning", "%.0f of %d turns", Math.abs(dir * TURNS * 360.0 - err) / 360, TURNS);
            telemetry.update();
        }
        return false;
    }

    /** The driver lines the robot back up with the seam (right stick or by hand) and presses A. */
    private boolean align(String direction) throws IOException {
        segment = "align";
        boolean wasA = gamepad1.a;
        while (tick()) {
            double stick = gamepad1.right_stick_x;
            turnPower = Math.abs(stick) > 0.05 ? -stick * ALIGN_POWER : 0;
            brake = turnPower != 0; // let go of the wheels so it can be turned by hand
            telemetry.addLine("LINE IT UP after " + TURNS + " turns " + direction + ".");
            telemetry.addLine("Right stick (or by hand) until the robot sits on the seam exactly as it started.");
            telemetry.addLine("Then let go and press A.");
            telemetry.addData("Heading moved", "%.2f deg", yaw);
            telemetry.update();
            boolean a = gamepad1.a;
            if (a && !wasA && turnPower == 0 && Math.abs(yawRate) < 2) return true;
            wasA = a;
        }
        return false;
    }
}
