package org.firstinspires.ftc.teamcode.buzzbot;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.Gamepad;

/**
 * BuzzBot driver control. Driving is robot-centric: the sticks always mean the same thing to
 * the robot, whichever way it faces. Left stick up drives forward, down backward, right strafes
 * right, left strafes left; right stick right turns right. (FIELD_CENTRIC = true switches to
 * field-centric from the driver's point of view.)
 *
 *   Left stick       drive                    Right stick X   turn
 *   Left bumper      slow mode                Right bumper    hold to aim at the target CELL
 *   A                fire one ball            Y               switch target CELL (starting / other)
 *   Right trigger    intake                   Left trigger    spit out
 *   Dpad up / down   lift to FLOWER / down    B               open / close claw
 *   Back             reset position to the start picked in INIT
 *   During INIT:  B = red alliance, X = blue alliance; with no AUTO before (no pose handed
 *                 over), A = wall start, Y = audience start
 *
 * Driving has the usual competition driver aids (DriverAids): a stick curve for fine control,
 * turning capped at about 190 degrees a second, and smooth speeding up (stopping is instant).
 *
 * The target CELL follows the HIVE: when the Limelight sees our HIVE's AprilTags, they tell
 * which CELL is up and that becomes the target (Y still switches it, until the HIVE tips
 * again). Aiming turns toward the target CELL's tags and takes the distance from them when
 * they're in view, and uses the odometry pose when they aren't (see HiveTags).
 *
 * On a PS4/PS5 controller the light bar shows the alliance color. The controller gives a short
 * buzz when the robot is aimed with the flywheel at speed, and three blips at 30 s left.
 */
@TeleOp(name = "BuzzBot TeleOp", group = "BuzzBot")
public class BuzzBotTeleOp extends LinearOpMode {
    static final double STANDBY_SPEED = 1150; // keep the flywheel spinning between shots
    static final double TELEOP_SECONDS = 120;
    static final double ENDGAME_WARNING = 30;  // seconds left
    /** false: robot-centric (stick up = robot forward). true: stick up = away from your alliance wall. */
    public static boolean FIELD_CENTRIC = false;

    @Override
    public void runOpMode() {
        Alliance alliance = PoseStorage.alliance;
        BuzzBot bot = new BuzzBot(hardwareMap, alliance);
        boolean audienceStart = false; // where the robot starts when no AUTO ran before

        while (opModeInInit()) {
            if (gamepad1.b) alliance = Alliance.RED;
            if (gamepad1.x) alliance = Alliance.BLUE;
            if (gamepad1.a) audienceStart = false;
            if (gamepad1.y) audienceStart = true;
            showAlliance(alliance);
            telemetry.addData("Alliance", "%s   (B = red, X = blue)", alliance);
            telemetry.addData("Start pose", PoseStorage.pose != null ? "from AUTO " + PoseStorage.pose
                    : (audienceStart ? "audience start" : "wall start") + "   (A = wall, Y = audience)");
            telemetry.update();
        }
        bot.setAlliance(alliance);
        Pose start = alliance.map(audienceStart ? FieldConstants.START_AUDIENCE : FieldConstants.START_WALL);
        bot.localizer.setPose(PoseStorage.pose != null ? PoseStorage.pose : start);

        boolean targetOther = false;
        Boolean tipSeen = null; // which way the tags last said our HIVE is tipped
        boolean prevA = false, prevY = false, prevB = false, prevBack = false;
        boolean wasReady = false, warned = false;
        DriverAids aids = new DriverAids();
        double lastLoop = 0;
        resetRuntime();

        while (opModeIsActive()) {
            bot.update();

            // ---- Target CELL: the one the HIVE's AprilTags say is up, when they change ----
            Boolean startUp = bot.tags.startCellUp(alliance);
            if (startUp != null && !startUp.equals(tipSeen)) targetOther = !startUp;
            if (startUp != null) tipSeen = startUp;

            // ---- Buttons (act once per press) ----
            if (gamepad1.y && !prevY) targetOther = !targetOther;
            if (gamepad1.b && !prevB) bot.lift.setClaw(!bot.lift.isClawClosed());
            if (gamepad1.back && !prevBack) bot.localizer.setPose(start);
            boolean firePressed = gamepad1.a && !prevA;
            prevA = gamepad1.a;
            prevY = gamepad1.y;
            prevB = gamepad1.b;
            prevBack = gamepad1.back;
            Pose pose = bot.pose();

            double[] opening = alliance.map(targetOther ? FieldConstants.OTHER_CELL_OPENING : FieldConstants.START_CELL_OPENING);
            HiveTags.Cell targetCell = targetOther ? HiveTags.Cell.other(alliance) : HiveTags.Cell.start(alliance);
            HiveTags.Sighting seen = bot.tags.sighting(targetCell);
            // From the tags when they're in view (odometry drifts; the tags don't), else from the pose.
            double distance = seen != null ? HiveTags.launcherDistanceToOpening(seen) : bot.launcherDistanceTo(opening);

            // ---- Drive ----
            double now = getRuntime(), dt = Math.min(0.1, now - lastLoop);
            lastLoop = now;
            double scale = gamepad1.left_bumper ? 0.4 : 1.0;
            double up = -gamepad1.left_stick_y, right = gamepad1.left_stick_x;
            // The field heading that "stick up" drives toward: the robot's front (robot-centric),
            // or straight away from our alliance wall (field-centric).
            double f = FIELD_CENTRIC ? alliance.driverForwardHeading() : pose.heading;
            double[] cmd = aids.update(up * Math.cos(f) + right * Math.sin(f), up * Math.sin(f) - right * Math.cos(f),
                    -gamepad1.right_stick_x, dt);
            double fieldX = cmd[0] * scale, fieldY = cmd[1] * scale, turn = cmd[2] * scale;
            boolean aiming = gamepad1.right_bumper;
            boolean aimed = false;
            if (aiming) {
                // The camera is on the robot's centerline: turning its tags to the crosshair aims the launcher.
                double heading = seen != null ? pose.heading - Math.toRadians(seen.bearingDeg) : pose.headingTo(opening[0], opening[1]);
                aimed = bot.drive.turnTo(heading, 0.6, 1.5, fieldX, fieldY);
                bot.launcher.setTarget(Launcher.speedForDistance(distance));
            } else {
                bot.drive.driveField(fieldX, fieldY, turn);
                bot.launcher.setTarget(STANDBY_SPEED);
            }
            if (firePressed) bot.launcher.fire();

            // ---- Driver feedback on the controller ----
            boolean ready = aimed && bot.launcher.atSpeed();
            if (ready && !wasReady) gamepad1.rumble(0.4, 0.4, 150);
            wasReady = ready;
            if (!warned && getRuntime() > TELEOP_SECONDS - ENDGAME_WARNING) {
                gamepad1.rumbleBlips(3);
                gamepad2.rumbleBlips(3);
                warned = true;
            }

            // ---- Intake / lift ----
            bot.intake.set(gamepad1.right_trigger > 0.2 ? 1 : gamepad1.left_trigger > 0.2 ? -1 : 0);
            if (gamepad1.dpad_up) bot.lift.setTarget(Lift.FLOWER);
            if (gamepad1.dpad_down) bot.lift.setTarget(Lift.DOWN);

            // On the wrong side of a CELL's opening the ball can't go in.
            double side = opening[1] > 0 ? 1 : -1;
            boolean rightSide = (pose.y - opening[1]) * side > 0;

            telemetry.addData("Alliance", alliance);
            telemetry.addData("Drive", FIELD_CENTRIC ? "field-centric (stick up = away from our wall)" : "robot-centric (stick up = robot forward)");
            telemetry.addData("Target", "%s CELL  %.0f in %s", targetOther ? "OTHER" : "STARTING", distance,
                    rightSide ? "" : "(move to the " + (side > 0 ? "far" : "audience") + " side to shoot)");
            telemetry.addData("AprilTags", "%s%s", bot.tags.describe(),
                    seen != null ? String.format(" · aiming by %d tag%s", seen.tags, seen.tags == 1 ? "" : "s") : "");
            telemetry.addData("Flywheel", "%.0f / %.0f %s", bot.launcher.getVelocity(), bot.launcher.getTarget(),
                    bot.launcher.atSpeed() ? (aimed ? "READY + AIMED" : "READY") : "");
            telemetry.addData("Ball at launcher", "%s%s", bot.intake.staged(), bot.intake.isEjecting() ? " (ejecting opponent NECTAR)" : "");
            telemetry.addData("Shots", bot.launcher.shots());
            telemetry.addData("Lift", "%d %s, claw %s", bot.lift.getPosition(), bot.lift.atBottom() ? "(down)" : "",
                    bot.lift.isClawClosed() ? "closed" : "open");
            telemetry.addData("Pose", pose);
            telemetry.update();
        }
        PoseStorage.pose = bot.pose();
    }

    private void showAlliance(Alliance alliance) {
        double red = alliance == Alliance.RED ? 1 : 0;
        gamepad1.setLedColor(red, 0, 1 - red, Gamepad.LED_DURATION_CONTINUOUS);
        gamepad2.setLedColor(red, 0, 1 - red, Gamepad.LED_DURATION_CONTINUOUS);
    }
}
