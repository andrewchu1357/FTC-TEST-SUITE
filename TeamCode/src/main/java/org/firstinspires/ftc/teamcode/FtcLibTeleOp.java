package org.firstinspires.ftc.teamcode;

import com.arcrobotics.ftclib.drivebase.MecanumDrive;
import com.arcrobotics.ftclib.gamepad.GamepadEx;
import com.arcrobotics.ftclib.gamepad.GamepadKeys;
import com.arcrobotics.ftclib.hardware.motors.Motor;
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.IMU;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;

/** Example TeleOp using FTCLib's MecanumDrive, Motor and GamepadEx. A toggles field-centric. */
@TeleOp(name = "FTCLib TeleOp", group = "Starter robot examples")
public class FtcLibTeleOp extends LinearOpMode {
    @Override
    public void runOpMode() {
        Motor frontLeft = new Motor(hardwareMap, "leftFront", Motor.GoBILDA.RPM_312);
        Motor frontRight = new Motor(hardwareMap, "rightFront", Motor.GoBILDA.RPM_312);
        Motor backLeft = new Motor(hardwareMap, "leftBack", Motor.GoBILDA.RPM_312);
        Motor backRight = new Motor(hardwareMap, "rightBack", Motor.GoBILDA.RPM_312);
        // This chassis has its left motors mounted mirrored, so invert the left side
        // instead of using FTCLib's default right-side inversion.
        frontLeft.setInverted(true);
        backLeft.setInverted(true);
        MecanumDrive drive = new MecanumDrive(false, frontLeft, frontRight, backLeft, backRight);

        IMU imu = hardwareMap.get(IMU.class, "imu");
        imu.initialize(new IMU.Parameters(new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.UP,
                RevHubOrientationOnRobot.UsbFacingDirection.FORWARD)));

        GamepadEx driver = new GamepadEx(gamepad1);
        boolean fieldCentric = false;

        waitForStart();
        while (opModeIsActive()) {
            driver.readButtons();
            if (driver.wasJustPressed(GamepadKeys.Button.A)) fieldCentric = !fieldCentric;

            double heading = imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.DEGREES);
            if (fieldCentric) {
                drive.driveFieldCentric(driver.getLeftX(), driver.getLeftY(), driver.getRightX(), heading);
            } else {
                drive.driveRobotCentric(driver.getLeftX(), driver.getLeftY(), driver.getRightX());
            }

            telemetry.addData("Mode", fieldCentric ? "field-centric" : "robot-centric");
            telemetry.addData("Heading", "%.1f", heading);
            telemetry.addData("Front left encoder", frontLeft.getCurrentPosition());
            telemetry.update();
        }
    }
}
