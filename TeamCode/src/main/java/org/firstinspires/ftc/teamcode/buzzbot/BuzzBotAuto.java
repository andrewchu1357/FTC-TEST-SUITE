package org.firstinspires.ftc.teamcode.buzzbot;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.util.ElapsedTime;

/**
 * BuzzBot's autonomous, written once in red coordinates and mirrored for blue.
 *
 *  1. Drive to the shooting spot for the CELL that starts facing up, spinning up the flywheel.
 *  2. Shoot pre-loaded POLLEN into it until the HIVE tips (20 points). With a Limelight, the
 *     HIVE's AprilTags confirm the tip: if the CELL is still up a few seconds after the volley,
 *     fire another ball.
 *  3. Sweep up the GARDEN POLLEN along the wall.
 *  4. The other CELL faces up after the tip - shoot everything into it from its side.
 *  5. Park partially in the LOADING ZONE (LEAVE 3 + PARK 5), intaking any POLLEN there.
 *
 * Shots aim at the CELL's AprilTags when they're in view (see HiveTags), so odometry drift
 * after the GARDEN sweep doesn't throw off the second volley.
 */
public abstract class BuzzBotAuto extends LinearOpMode {
    /**
     * POLLEN needed to tip the HIVE on top of the 3 NECTAR it starts with. The real number
     * isn't published; this matches the simulator's estimate (config/field.jsonc). Count it on
     * a real HIVE and update both.
     */
    static final int SHOTS_TO_TIP = 3;
    /** How long a HIVE takes to tip after its last ball (a marginal tip is slow) [ESTIMATE]. */
    static final double TIP_WAIT_SECONDS = 3.0;
    /** Balls to add if the tags show the HIVE hasn't tipped. */
    static final int MAX_EXTRA_SHOTS = 2;

    protected abstract Alliance alliance();

    private BuzzBot bot;
    private final ElapsedTime auto = new ElapsedTime();
    private String step = "init";

    @Override
    public void runOpMode() {
        Alliance a = alliance();
        bot = new BuzzBot(hardwareMap, a);
        bot.localizer.setPose(a.map(FieldConstants.START_WALL));
        PoseStorage.alliance = a;
        PoseStorage.pose = null;

        while (opModeInInit()) {
            telemetry.addData("BuzzBot Auto", a);
            telemetry.addData("Start", "against the %s wall, facing the HIVE, 4 POLLEN loaded", a == Alliance.RED ? "red" : "blue");
            telemetry.addData("Ball at launcher", bot.intake.staged());
            telemetry.update();
        }
        auto.reset();

        step = "drive to first shooting spot";
        bot.launcher.setTarget(Launcher.speedForDistance(45));
        driveTo(a.map(FieldConstants.START_CELL_SHOT), 0.8, 3.0);

        step = "shoot until the HIVE tips";
        double[] startOpening = a.map(FieldConstants.START_CELL_OPENING);
        shoot(startOpening, HiveTags.Cell.start(a), SHOTS_TO_TIP, 6.0);
        // FIRST's kickoff: the tags are for "determining which way it's tipped before launching".
        int extra = 0;
        while (opModeIsActive() && bot.tags.hasCamera() && extra < MAX_EXTRA_SHOTS) {
            step = "watch the AprilTags for the tip";
            if (waitForTip(TIP_WAIT_SECONDS)) break;
            step = "not tipped yet: one more ball";
            shoot(startOpening, HiveTags.Cell.start(a), 1, 3.0);
            extra++;
        }

        step = "collect GARDEN POLLEN";
        bot.intake.set(1);
        driveTo(a.map(FieldConstants.GARDEN_APPROACH), 0.8, 3.0);
        driveTo(a.map(FieldConstants.GARDEN_SWEEP_END), 0.3, 3.0);

        step = "drive to second shooting spot (other CELL is up now)";
        bot.launcher.setTarget(Launcher.speedForDistance(45));
        driveTo(a.map(FieldConstants.OTHER_CELL_SHOT), 0.9, 4.0);
        bot.intake.set(0);

        step = "shoot into the other CELL";
        shoot(a.map(FieldConstants.OTHER_CELL_OPENING), HiveTags.Cell.other(a), 4, 8.0);

        step = "park (and pick up POLLEN in the LOADING ZONE)";
        bot.launcher.stop();
        bot.intake.set(1);
        driveTo(a.map(FieldConstants.PARK), 0.9, 4.0);

        step = String.format("done in %.1f s", auto.seconds());
        while (opModeIsActive()) {
            bot.update();
            report();
        }
    }

    private void driveTo(Pose target, double maxPower, double timeoutSeconds) {
        ElapsedTime t = new ElapsedTime();
        while (opModeIsActive() && t.seconds() < timeoutSeconds) {
            bot.update();
            if (bot.drive.driveTo(target, maxPower, 1.0, 2.0)) break;
            report();
        }
        bot.drive.stop();
        PoseStorage.pose = bot.pose();
    }

    /**
     * Aim at a CELL and fire up to count balls (each once the flywheel has recovered). Aims at
     * the CELL's AprilTags when they're in view, otherwise at the opening from the odometry pose.
     * Stops early when no ball reaches the launcher for a second (hopper empty).
     */
    private void shoot(double[] opening, HiveTags.Cell cell, int count, double timeoutSeconds) {
        ElapsedTime t = new ElapsedTime();
        ElapsedTime sinceBall = new ElapsedTime();
        int fired = 0;
        while (opModeIsActive() && fired < count && t.seconds() < timeoutSeconds) {
            bot.update();
            Pose p = bot.pose();
            HiveTags.Sighting seen = bot.tags.sighting(cell);
            double heading = seen != null ? p.heading - Math.toRadians(seen.bearingDeg) : p.headingTo(opening[0], opening[1]);
            boolean aimed = bot.drive.turnTo(heading, 0.5, 1.5, 0, 0);
            double distance = seen != null ? HiveTags.launcherDistanceToOpening(seen) : bot.launcherDistanceTo(opening);
            bot.launcher.setTarget(Launcher.speedForDistance(distance));
            boolean ball = bot.intake.staged() != Intake.Ball.NONE;
            if (ball || bot.launcher.busy()) sinceBall.reset();
            if (sinceBall.seconds() > 1.0) break;
            if (aimed && bot.launcher.atSpeed() && !bot.launcher.busy() && ball) {
                if (bot.launcher.fire()) fired++;
            }
            report();
        }
        while (opModeIsActive() && bot.launcher.busy()) bot.update();
        bot.drive.stop();
        PoseStorage.pose = bot.pose();
    }

    /** Waits (up to seconds) for the HIVE's tags to show its start CELL went down; true if they did. */
    private boolean waitForTip(double seconds) {
        ElapsedTime t = new ElapsedTime();
        while (opModeIsActive() && t.seconds() < seconds) {
            bot.update();
            if (Boolean.FALSE.equals(bot.tags.startCellUp(bot.alliance()))) return true;
            report();
        }
        return false;
    }

    private void report() {
        telemetry.addData("Step", step);
        telemetry.addData("Time", "%.1f s", auto.seconds());
        telemetry.addData("Pose", bot.pose());
        telemetry.addData("Flywheel", "%.0f / %.0f ticks/s %s", bot.launcher.getVelocity(), bot.launcher.getTarget(),
                bot.launcher.atSpeed() ? "READY" : "");
        telemetry.addData("Shots", bot.launcher.shots());
        telemetry.addData("Ball at launcher", bot.intake.staged());
        telemetry.addData("AprilTags", bot.tags.describe());
        telemetry.update();
    }
}
