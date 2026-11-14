package org.firstinspires.ftc.teamcode;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.hardware.TouchSensor;
import com.qualcomm.robotcore.util.Range;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;

/**
 * Example driver-controlled OpMode. Robot- or field-centric mecanum drive, a lift with
 * RUN_TO_POSITION presets, a claw servo and an intake CR servo.
 *
 * Left stick: drive   Right stick X: turn   Right trigger: slow mode
 * Left stick button: toggle field-centric   Right stick button: reset heading
 * Dpad up/down: nudge lift   A/B/Y: lift low/mid/high   Right bumper: claw
 * Left trigger / left bumper: intake in / out
 */
@TeleOp(name = "Mecanum TeleOp", group = "Starter robot examples")
public class MecanumTeleOp extends LinearOpMode {
    static final int LIFT_LOW = 0;
    static final int LIFT_MID = 1100;
    static final int LIFT_HIGH = 2200;
    static final int LIFT_MAX = 2250;

    @Override
    public void runOpMode() {
        DcMotor leftFront = hardwareMap.get(DcMotor.class, "leftFront");
        DcMotor leftBack = hardwareMap.get(DcMotor.class, "leftBack");
        DcMotor rightFront = hardwareMap.get(DcMotor.class, "rightFront");
        DcMotor rightBack = hardwareMap.get(DcMotor.class, "rightBack");
        leftFront.setDirection(DcMotorSimple.Direction.REVERSE);
        leftBack.setDirection(DcMotorSimple.Direction.REVERSE);
        for (DcMotor m : new DcMotor[]{leftFront, leftBack, rightFront, rightBack}) {
            m.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        }

        DcMotorEx lift = hardwareMap.get(DcMotorEx.class, "lift");
        lift.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        lift.setTargetPosition(0);
        lift.setMode(DcMotor.RunMode.RUN_TO_POSITION);
        lift.setPower(1.0);
        TouchSensor liftDown = hardwareMap.get(TouchSensor.class, "liftDown");

        Servo claw = hardwareMap.get(Servo.class, "claw");
        CRServo intake = hardwareMap.get(CRServo.class, "intake");

        IMU imu = hardwareMap.get(IMU.class, "imu");
        imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.UP,
                RevHubOrientationOnRobot.UsbFacingDirection.FORWARD)));

        for (LynxModule hub : hardwareMap.getAll(LynxModule.class)) {
            hub.setBulkCachingMode(LynxModule.BulkCachingMode.AUTO);
        }

        boolean fieldCentric = false;
        boolean clawOpen = false;
        int liftTarget = 0;
        boolean prevToggle = false, prevReset = false, prevClaw = false;

        telemetry.addLine("Ready.");
        telemetry.update();
        waitForStart();

        while (opModeIsActive()) {
            // ---- Edge-detected buttons ----
            if (gamepad1.left_stick_button && !prevToggle) fieldCentric = !fieldCentric;
            if (gamepad1.right_stick_button && !prevReset) imu.resetYaw();
            if (gamepad1.right_bumper && !prevClaw) clawOpen = !clawOpen;
            prevToggle = gamepad1.left_stick_button;
            prevReset = gamepad1.right_stick_button;
            prevClaw = gamepad1.right_bumper;

            // ---- Drive ----
            double y = -gamepad1.left_stick_y;
            double x = gamepad1.left_stick_x;
            double rx = gamepad1.right_stick_x;
            double heading = imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.RADIANS);
            if (fieldCentric) {
                double rotX = x * Math.cos(-heading) - y * Math.sin(-heading);
                double rotY = x * Math.sin(-heading) + y * Math.cos(-heading);
                x = rotX;
                y = rotY;
            }
            double scale = 1.0 - 0.6 * gamepad1.right_trigger;
            double denominator = Math.max(Math.abs(y) + Math.abs(x) + Math.abs(rx), 1);
            leftFront.setPower((y + x + rx) / denominator * scale);
            leftBack.setPower((y - x + rx) / denominator * scale);
            rightFront.setPower((y - x - rx) / denominator * scale);
            rightBack.setPower((y + x - rx) / denominator * scale);

            // ---- Lift ----
            if (gamepad1.a) liftTarget = LIFT_LOW;
            if (gamepad1.b) liftTarget = LIFT_MID;
            if (gamepad1.y) liftTarget = LIFT_HIGH;
            if (gamepad1.dpad_up) liftTarget += 15;
            if (gamepad1.dpad_down) liftTarget -= 15;
            liftTarget = Range.clip(liftTarget, 0, LIFT_MAX);
            lift.setTargetPosition(liftTarget);

            // ---- Claw & intake ----
            claw.setPosition(clawOpen ? 0.7 : 0.3);
            intake.setPower(gamepad1.left_bumper ? -1.0 : gamepad1.left_trigger);

            telemetry.addData("Drive", fieldCentric ? "FIELD-centric" : "robot-centric");
            telemetry.addData("Heading", "%.1f deg", Math.toDegrees(heading));
            telemetry.addData("Lift", "%d / %d ticks %s", lift.getCurrentPosition(), liftTarget,
                    liftDown.isPressed() ? "(bottom)" : "");
            telemetry.addData("Lift current", "%.2f A", lift.getCurrent(org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit.AMPS));
            telemetry.addData("Claw", clawOpen ? "open" : "closed");
            telemetry.update();
        }
    }
}
