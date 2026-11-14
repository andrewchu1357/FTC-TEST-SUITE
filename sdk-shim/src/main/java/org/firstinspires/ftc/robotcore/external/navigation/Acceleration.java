package org.firstinspires.ftc.robotcore.external.navigation;

import java.util.Locale;

/** An acceleration, in some distance unit per second squared, with the time it was measured (System.nanoTime). */
public class Acceleration {
    /** Standard gravity in m/s^2. */
    public static final double earthGravity = 9.80665;

    public DistanceUnit unit;
    public double xAccel;
    public double yAccel;
    public double zAccel;
    public long acquisitionTime;

    public Acceleration() {
        this(DistanceUnit.MM, 0, 0, 0, 0);
    }

    public Acceleration(DistanceUnit unit, double xAccel, double yAccel, double zAccel, long acquisitionTime) {
        this.unit = unit;
        this.xAccel = xAccel;
        this.yAccel = yAccel;
        this.zAccel = zAccel;
        this.acquisitionTime = acquisitionTime;
    }

    /** An acceleration given in units of g. */
    public static Acceleration fromGravity(double gx, double gy, double gz, long acquisitionTime) {
        return new Acceleration(DistanceUnit.METER, gx * earthGravity, gy * earthGravity, gz * earthGravity, acquisitionTime);
    }

    public Acceleration toUnit(DistanceUnit distanceUnit) {
        if (distanceUnit == unit) return this;
        return new Acceleration(distanceUnit, distanceUnit.fromUnit(unit, xAccel), distanceUnit.fromUnit(unit, yAccel),
                distanceUnit.fromUnit(unit, zAccel), acquisitionTime);
    }

    @Override
    public String toString() {
        return String.format(Locale.getDefault(), "(%.3f %.3f %.3f)%s/s^2", xAccel, yAccel, zAccel, unit.toString());
    }
}
