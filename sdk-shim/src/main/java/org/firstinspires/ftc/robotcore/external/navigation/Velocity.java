package org.firstinspires.ftc.robotcore.external.navigation;

import java.util.Locale;

/** A velocity, in some distance unit per second, with the time it was measured (System.nanoTime). */
public class Velocity {
    public DistanceUnit unit;
    public double xVeloc;
    public double yVeloc;
    public double zVeloc;
    public long acquisitionTime;

    public Velocity() {
        this(DistanceUnit.MM, 0, 0, 0, 0);
    }

    public Velocity(DistanceUnit unit, double xVeloc, double yVeloc, double zVeloc, long acquisitionTime) {
        this.unit = unit;
        this.xVeloc = xVeloc;
        this.yVeloc = yVeloc;
        this.zVeloc = zVeloc;
        this.acquisitionTime = acquisitionTime;
    }

    public Velocity toUnit(DistanceUnit distanceUnit) {
        if (distanceUnit == unit) return this;
        return new Velocity(distanceUnit, distanceUnit.fromUnit(unit, xVeloc), distanceUnit.fromUnit(unit, yVeloc),
                distanceUnit.fromUnit(unit, zVeloc), acquisitionTime);
    }

    @Override
    public String toString() {
        return String.format(Locale.getDefault(), "(%.3f %.3f %.3f)%s/s", xVeloc, yVeloc, zVeloc, unit.toString());
    }
}
