package org.firstinspires.ftc.teamcode.buzzbot;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.util.ElapsedTime;

/**
 * Flywheel launcher with a kicker servo. The flywheel runs under the hub's velocity
 * control; fire() kicks one ball in once the flywheel is at speed.
 */
public class Launcher {
    /**
     * Flywheel speed (encoder ticks/s) by distance from the launcher to the CELL opening
     * (inches). Found by test-shooting from 36 to 54 in (./gradlew :TeamCode:launcherSweep).
     * Re-tune on the real robot with the "BuzzBot Launcher Tuner" OpMode.
     */
    static final double[][] SPEED_TABLE = {
            {36, 1490}, {39, 1305}, {42, 1215}, {45, 1155}, {48, 1120}, {51, 1105}, {54, 1100},
    };
    static final double READY_TOLERANCE = 35;   // ticks/s
    static final double P = 50, I = 0, F = 11.5;
    static final double KICK = 0.8, REST = 0.2;
    static final double KICK_SECONDS = 0.2, RETURN_SECONDS = 0.2;

    private enum State { IDLE, KICKING, RETURNING }

    private final DcMotorEx left, right;
    private final Servo kicker;
    private final ElapsedTime timer = new ElapsedTime();
    private State state = State.IDLE;
    private double target;
    private int shots;

    public Launcher(DcMotorEx left, DcMotorEx right, Servo kicker) {
        this.left = left;
        this.right = right;
        this.kicker = kicker;
        right.setDirection(DcMotorSimple.Direction.REVERSE);
        for (DcMotorEx m : new DcMotorEx[]{left, right}) {
            m.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);
            m.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
            // Flywheel tuning: F does most of the work (about 32767 / max ticks per second, a bit less with a
            // full battery) and P corrects the rest. No I: it winds up during spin-up and overshoots.
            m.setVelocityPIDFCoefficients(P, I, 0, F);
        }
        kicker.setPosition(REST);
    }

    /** Linear interpolation in SPEED_TABLE (clamped at the ends). */
    public static double speedForDistance(double inches) {
        double[][] t = SPEED_TABLE;
        if (inches <= t[0][0]) return t[0][1];
        for (int i = 1; i < t.length; i++) {
            if (inches <= t[i][0]) {
                double f = (inches - t[i - 1][0]) / (t[i][0] - t[i - 1][0]);
                return t[i - 1][1] + f * (t[i][1] - t[i - 1][1]);
            }
        }
        return t[t.length - 1][1];
    }

    public void setTarget(double ticksPerSecond) {
        target = ticksPerSecond;
        left.setVelocity(ticksPerSecond);
        right.setVelocity(ticksPerSecond);
    }

    public void stop() {
        setTarget(0);
    }

    public double getTarget() {
        return target;
    }

    public double getVelocity() {
        return left.getVelocity();
    }

    public boolean atSpeed() {
        return target > 0 && Math.abs(getVelocity() - target) < READY_TOLERANCE;
    }

    public boolean busy() {
        return state != State.IDLE;
    }

    public int shots() {
        return shots;
    }

    /** Kick one ball if ready. Returns true if a kick started. */
    public boolean fire() {
        if (state != State.IDLE || !atSpeed()) return false;
        kicker.setPosition(KICK);
        timer.reset();
        state = State.KICKING;
        shots++;
        return true;
    }

    public void update() {
        if (state == State.KICKING && timer.seconds() > KICK_SECONDS) {
            kicker.setPosition(REST);
            timer.reset();
            state = State.RETURNING;
        } else if (state == State.RETURNING && timer.seconds() > RETURN_SECONDS) {
            state = State.IDLE;
        }
    }
}
