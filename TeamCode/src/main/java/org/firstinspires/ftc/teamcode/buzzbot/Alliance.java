package org.firstinspires.ftc.teamcode.buzzbot;

/**
 * The BIOBUZZ field is the same after a 180 degree turn: everything the blue alliance does
 * is red's plan rotated about the field center. Write paths in red coordinates and call
 * {@link #map(Pose)} to get the right ones for either alliance.
 */
public enum Alliance {
    RED, BLUE;

    public Pose map(Pose red) {
        if (this == RED) return red;
        return new Pose(-red.x, -red.y, red.heading + Math.PI);
    }

    public double[] map(double[] redPoint) {
        return this == RED ? redPoint : new double[]{-redPoint[0], -redPoint[1]};
    }

    /** Which way "forward" on the stick points for drivers standing at this alliance wall. */
    public double driverForwardHeading() {
        return this == RED ? 0 : Math.PI;
    }
}
