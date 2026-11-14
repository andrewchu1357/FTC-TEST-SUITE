package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;

/**
 *   Left stick   drive (robot-centric)      Right stick X   turn
 *   A / cross    open / close the claw      Dpad up / down  lift up / down
 *   Triggers     lift fine control          Left bumper     slow mode
 */
@TeleOp(name = "ExampleBot TeleOp", group = "ExampleBot")
public class ExampleBotTeleOp extends LinearOpMode {
    @Override
    public void runOpMode() {
        ExampleBotHardware bot = new ExampleBotHardware(hardwareMap);
        boolean clawOpen = false, prevA = false;
        int liftTarget = 0;
        waitForStart();
        while (opModeIsActive()) {
            double scale = gamepad1.left_bumper ? 0.4 : 1.0;
            bot.drive(-gamepad1.left_stick_y * scale, gamepad1.left_stick_x * scale, gamepad1.right_stick_x * scale);

            if (gamepad1.a && !prevA) {
                clawOpen = !clawOpen;
                bot.claw.setPosition(clawOpen ? ExampleBotHardware.CLAW_OPEN : ExampleBotHardware.CLAW_CLOSED);
            }
            prevA = gamepad1.a;
            if (gamepad1.dpad_up) liftTarget = ExampleBotHardware.LIFT_HIGH;
            if (gamepad1.dpad_down) liftTarget = 0;
            liftTarget += (int) ((gamepad1.right_trigger - gamepad1.left_trigger) * 25);
            liftTarget = Math.max(0, Math.min(ExampleBotHardware.LIFT_HIGH, liftTarget));
            bot.setLift(liftTarget);

            telemetry.addData("Lift", "%d / %d %s", bot.lift.getCurrentPosition(), liftTarget, bot.liftDown.isPressed() ? "(down)" : "");
            telemetry.addData("Claw", clawOpen ? "open" : "closed");
            telemetry.addData("Front distance", "%.1f in", bot.frontDistance.getDistance(DistanceUnit.INCH));
            telemetry.update();
        }
    }
}
