package org.firstinspires.ftc.teamcode.buzzbot;

/** A position on the field: x, y in inches, heading in radians (counter-clockwise, 0 = +x). */
public final class Pose {
    public final double x, y, heading;

    public Pose(double x, double y, double heading) {
        this.x = x;
        this.y = y;
        this.heading = heading;
    }

    public static Pose degrees(double x, double y, double headingDegrees) {
        return new Pose(x, y, Math.toRadians(headingDegrees));
    }

    public double distanceTo(double ox, double oy) {
        return Math.hypot(ox - x, oy - y);
    }

    /** Heading that points from this pose toward a field point. */
    public double headingTo(double ox, double oy) {
        return Math.atan2(oy - y, ox - x);
    }

    @Override
    public String toString() {
        return String.format("(%.1f, %.1f, %.0f°)", x, y, Math.toDegrees(heading));
    }
}
