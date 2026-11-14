package org.firstinspires.ftc.teamcode.buzzbot;

/**
 * Driver aids, as competition TeleOps use them: they make a full-speed robot easy to place.
 *
 * - A stick curve: part linear, part cubic, so small stick movements give fine control while
 *   full stick is still full speed.
 * - Turning tops out at MAX_TURN (full right stick turns about 190 degrees a second instead of
 *   270), which keeps aiming and lining up from overshooting.
 * - Speeding up is limited to ACCEL per second (0 to full in a third of a second), so a jab of
 *   the stick doesn't jerk the robot; slowing down and stopping are never limited.
 */
public final class DriverAids {
    public static double CURVE = 0.6;      // share of the cubic in the stick curve (0: linear)
    public static double MAX_TURN = 0.7;   // turn power at full stick
    public static double ACCEL = 3.0;      // stick units per second (driving)
    public static double TURN_ACCEL = 6.0; // stick units per second (turning)

    private double x, y, turn;

    /** The curve: (1 - CURVE) v + CURVE v^3. */
    public static double shape(double v) {
        return (1 - CURVE) * v + CURVE * v * v * v;
    }

    /**
     * The next drive command from the sticks (fieldX, fieldY: drive direction; stickTurn: the
     * turn stick, counter-clockwise positive), dt seconds after the last one.
     */
    public double[] update(double fieldX, double fieldY, double stickTurn, double dt) {
        double mag = Math.hypot(fieldX, fieldY);
        double shaped = shape(Math.min(1, mag));
        double wantX = mag > 1e-9 ? fieldX / mag * shaped : 0, wantY = mag > 1e-9 ? fieldY / mag * shaped : 0;
        double wantTurn = shape(stickTurn) * MAX_TURN;
        // Limit speeding up only: the new command may be any slower than the last one, but at
        // most ACCEL * dt faster.
        double now = Math.hypot(x, y), want = Math.hypot(wantX, wantY);
        double limit = now + ACCEL * dt;
        double k = want > limit ? limit / want : 1;
        x = wantX * k;
        y = wantY * k;
        if (Math.abs(wantTurn) > Math.abs(turn) && Math.signum(wantTurn) == Math.signum(turn) || turn == 0) {
            turn = Math.copySign(Math.min(Math.abs(wantTurn), Math.abs(turn) + TURN_ACCEL * dt), wantTurn);
        } else {
            turn = wantTurn;
        }
        return new double[]{x, y, turn};
    }

    public void reset() {
        x = y = turn = 0;
    }
}
