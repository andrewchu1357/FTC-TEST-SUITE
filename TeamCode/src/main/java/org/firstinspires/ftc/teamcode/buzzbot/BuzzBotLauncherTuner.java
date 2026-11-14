package org.firstinspires.ftc.teamcode.buzzbot;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

/**
 * Shooter tuning on a practice field. Park at a known distance from a CELL opening (shown
 * from odometry), adjust the flywheel speed until shots go in, and write the speed down
 * for Launcher.SPEED_TABLE.
 *
 *   Dpad up / down   +/- 10 ticks/s       Dpad right / left   +/- 50 ticks/s
 *   Right bumper     aim at the CELL      A                   fire
 *   Y                switch CELL          Left stick / right stick X   drive slowly (robot-centric)
 */
@TeleOp(name = "BuzzBot Launcher Tuner", group = "BuzzBot")
public class BuzzBotLauncherTuner extends LinearOpMode {
    @Override
    public void runOpMode() {
        BuzzBot bot = new BuzzBot(hardwareMap, PoseStorage.alliance);
        bot.localizer.setPose(PoseStorage.pose != null ? PoseStorage.pose : PoseStorage.alliance.map(FieldConstants.START_WALL));
        double speed = 1200;
        boolean far = false;
        boolean prevUp = false, prevDown = false, prevLeft = false, prevRight = false, prevA = false, prevY = false;
        waitForStart();
        while (opModeIsActive()) {
            bot.update();
            if (gamepad1.dpad_up && !prevUp) speed += 10;
            if (gamepad1.dpad_down && !prevDown) speed -= 10;
            if (gamepad1.dpad_right && !prevRight) speed += 50;
            if (gamepad1.dpad_left && !prevLeft) speed -= 50;
            if (gamepad1.y && !prevY) far = !far;
            if (gamepad1.a && !prevA) bot.launcher.fire();
            prevUp = gamepad1.dpad_up;
            prevDown = gamepad1.dpad_down;
            prevLeft = gamepad1.dpad_left;
            prevRight = gamepad1.dpad_right;
            prevA = gamepad1.a;
            prevY = gamepad1.y;

            double[] opening = PoseStorage.alliance.map(far ? FieldConstants.OTHER_CELL_OPENING : FieldConstants.START_CELL_OPENING);
            if (gamepad1.right_bumper) {
                bot.drive.turnTo(bot.pose().headingTo(opening[0], opening[1]), 0.4, 1.0, 0, 0);
            } else {
                bot.drive.drive(-gamepad1.left_stick_y * 0.4, -gamepad1.left_stick_x * 0.4, -gamepad1.right_stick_x * 0.3);
            }
            bot.launcher.setTarget(speed);
            bot.intake.set(gamepad1.right_trigger > 0.2 ? 1 : 0);

            telemetry.addData("Target speed", "%.0f ticks/s", speed);
            telemetry.addData("Flywheel", "%.0f %s", bot.launcher.getVelocity(), bot.launcher.atSpeed() ? "READY" : "");
            telemetry.addData("Distance to opening", "%.1f in (%s CELL)", bot.launcherDistanceTo(opening), far ? "other" : "starting");
            telemetry.addData("Table says", "%.0f ticks/s", Launcher.speedForDistance(bot.launcherDistanceTo(opening)));
            telemetry.addData("Pose", bot.pose());
            telemetry.update();
        }
    }
}
