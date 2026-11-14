package org.firstinspires.ftc.teamcode;

import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.DistanceSensor;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.util.ElapsedTime;
import com.qualcomm.robotcore.util.Range;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;

/**
 * Example autonomous: encoder moves with RUN_TO_POSITION, an IMU turn, then drive until the
 * distance sensor sees the wall, raise the lift and open the claw.
 */
@Autonomous(name = "Encoder Drive Auto", group = "Starter robot examples", preselectTeleOp = "Mecanum TeleOp")
public class EncoderDriveAuto extends LinearOpMode {
    static final double TICKS_PER_REV = 537.7;          // goBILDA 312 rpm
    static final double WHEEL_DIAMETER_IN = 104 / 25.4; // goBILDA 104 mm mecanum
    static final double TICKS_PER_INCH = TICKS_PER_REV / (WHEEL_DIAMETER_IN * Math.PI);
    static final double STRAFE_CORRECTION = 1.1;        // mecanum strafing loses some distance

    private DcMotorEx leftFront, leftBack, rightFront, rightBack, lift;
    private IMU imu;
    private final ElapsedTime timer = new ElapsedTime();

    @Override
    public void runOpMode() {
        leftFront = hardwareMap.get(DcMotorEx.class, "leftFront");
        leftBack = hardwareMap.get(DcMotorEx.class, "leftBack");
        rightFront = hardwareMap.get(DcMotorEx.class, "rightFront");
        rightBack = hardwareMap.get(DcMotorEx.class, "rightBack");
        leftFront.setDirection(DcMotorSimple.Direction.REVERSE);
        leftBack.setDirection(DcMotorSimple.Direction.REVERSE);
        for (DcMotorEx m : driveMotors()) {
            m.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
            m.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
            m.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
        }
        lift = hardwareMap.get(DcMotorEx.class, "lift");
        lift.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        Servo claw = hardwareMap.get(Servo.class, "claw");
        DistanceSensor front = hardwareMap.get(DistanceSensor.class, "frontDistance");

        imu = hardwareMap.get(IMU.class, "imu");
        imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.UP,
                RevHubOrientationOnRobot.UsbFacingDirection.FORWARD)));
        imu.resetYaw();
        claw.setPosition(0.3);

        telemetry.addLine("Initialized - press START");
        telemetry.update();
        waitForStart();
        timer.reset();

        driveInches(24, 0.6);
        strafeInches(24, 0.5);
        turnToHeading(-90, 0.5);

        // Drive toward the wall until the distance sensor reads 8 inches.
        for (DcMotorEx m : driveMotors()) m.setPower(0.4);
        while (opModeIsActive() && front.getDistance(DistanceUnit.INCH) > 8) {
            telemetry.addData("Step", "approach wall");
            telemetry.addData("Distance", "%.1f in", front.getDistance(DistanceUnit.INCH));
            telemetry.update();
        }
        for (DcMotorEx m : driveMotors()) m.setPower(0);

        lift.setTargetPosition(1100);
        lift.setMode(DcMotor.RunMode.RUN_TO_POSITION);
        lift.setPower(1.0);
        while (opModeIsActive() && lift.isBusy()) {
            telemetry.addData("Step", "raise lift (%d)", lift.getCurrentPosition());
            telemetry.update();
        }
        claw.setPosition(0.7);
        sleep(500);
        lift.setTargetPosition(0);
        while (opModeIsActive() && lift.isBusy()) idle();

        telemetry.addData("Step", "done in %.1f s", timer.seconds());
        telemetry.update();
        while (opModeIsActive()) idle();
    }

    private DcMotorEx[] driveMotors() {
        return new DcMotorEx[]{leftFront, leftBack, rightFront, rightBack};
    }

    private void driveInches(double inches, double power) {
        int ticks = (int) Math.round(inches * TICKS_PER_INCH);
        moveByTicks(ticks, ticks, ticks, ticks, power, "drive " + inches + " in");
    }

    /** Positive = right. */
    private void strafeInches(double inches, double power) {
        int ticks = (int) Math.round(inches * TICKS_PER_INCH * STRAFE_CORRECTION);
        moveByTicks(ticks, -ticks, -ticks, ticks, power, "strafe " + inches + " in");
    }

    private void moveByTicks(int lf, int lb, int rf, int rb, double power, String label) {
        leftFront.setTargetPosition(leftFront.getCurrentPosition() + lf);
        leftBack.setTargetPosition(leftBack.getCurrentPosition() + lb);
        rightFront.setTargetPosition(rightFront.getCurrentPosition() + rf);
        rightBack.setTargetPosition(rightBack.getCurrentPosition() + rb);
        for (DcMotorEx m : driveMotors()) {
            m.setMode(DcMotor.RunMode.RUN_TO_POSITION);
            m.setPower(power);
        }
        while (opModeIsActive() && (leftFront.isBusy() || leftBack.isBusy() || rightFront.isBusy() || rightBack.isBusy())) {
            telemetry.addData("Step", label);
            telemetry.addData("Ticks", "%d %d %d %d", leftFront.getCurrentPosition(), leftBack.getCurrentPosition(),
                    rightFront.getCurrentPosition(), rightBack.getCurrentPosition());
            telemetry.update();
        }
        for (DcMotorEx m : driveMotors()) {
            m.setPower(0);
            m.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
        }
    }

    /** Turns to an IMU yaw (degrees, CCW positive) with a proportional controller. */
    private void turnToHeading(double targetDegrees, double maxPower) {
        ElapsedTime settled = new ElapsedTime();
        while (opModeIsActive()) {
            double yaw = imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES);
            double error = AngleUnit.normalizeDegrees(targetDegrees - yaw);
            if (Math.abs(error) > 1.0) settled.reset();
            if (settled.milliseconds() > 250) break;
            double turn = Range.clip(error * 0.03, -maxPower, maxPower);
            leftFront.setPower(-turn);
            leftBack.setPower(-turn);
            rightFront.setPower(turn);
            rightBack.setPower(turn);
            telemetry.addData("Step", "turn to %.0f", targetDegrees);
            telemetry.addData("Yaw", "%.1f (error %.1f)", yaw, error);
            telemetry.update();
        }
        for (DcMotorEx m : driveMotors()) m.setPower(0);
    }
}
