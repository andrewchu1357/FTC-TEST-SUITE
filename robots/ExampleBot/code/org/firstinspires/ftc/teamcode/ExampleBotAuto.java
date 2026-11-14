package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;

/** Drives forward 24 in with encoders, raises the lift, opens the claw, lowers the lift. */
@Autonomous(name = "ExampleBot Auto", group = "ExampleBot", preselectTeleOp = "ExampleBot TeleOp")
public class ExampleBotAuto extends LinearOpMode {
    @Override
    public void runOpMode() {
        ExampleBotHardware bot = new ExampleBotHardware(hardwareMap);
        waitForStart();

        int ticks = (int) Math.round(24 * ExampleBotHardware.TICKS_PER_INCH);
        DcMotorEx[] drive = {bot.leftFront, bot.rightFront, bot.leftBack, bot.rightBack};
        for (DcMotorEx m : drive) {
            m.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
            m.setTargetPosition(ticks);
            m.setMode(DcMotor.RunMode.RUN_TO_POSITION);
            m.setPower(0.6);
        }
        while (opModeIsActive() && bot.leftFront.isBusy() && bot.rightFront.isBusy()) {
            telemetry.addData("Driving", "%d / %d", bot.leftFront.getCurrentPosition(), ticks);
            telemetry.update();
        }
        for (DcMotorEx m : drive) m.setPower(0);

        bot.setLift(ExampleBotHardware.LIFT_HIGH);
        while (opModeIsActive() && Math.abs(bot.lift.getCurrentPosition() - ExampleBotHardware.LIFT_HIGH) > 30) idle();
        bot.claw.setPosition(ExampleBotHardware.CLAW_OPEN);
        sleep(500);
        bot.setLift(0);
        while (opModeIsActive() && !bot.liftDown.isPressed()) idle();
        telemetry.addData("Done", "lift %d", bot.lift.getCurrentPosition());
        telemetry.update();
    }
}
