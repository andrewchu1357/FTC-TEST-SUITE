package org.firstinspires.ftc.robotcore.external.navigation;

public class Pose2D {
    protected final double x;
    protected final double y;
    protected final DistanceUnit distanceUnit;
    protected final double heading;
    protected final AngleUnit headingUnit;

    public Pose2D(DistanceUnit distanceUnit, double x, double y, AngleUnit headingUnit, double heading) {
        this.distanceUnit = distanceUnit;
        this.x = x;
        this.y = y;
        this.headingUnit = headingUnit;
        this.heading = heading;
    }

    public double getX(DistanceUnit unit) { return unit.fromUnit(distanceUnit, x); }
    public double getY(DistanceUnit unit) { return unit.fromUnit(distanceUnit, y); }
    public double getHeading(AngleUnit unit) { return unit.fromUnit(headingUnit, heading); }

    @Override
    public String toString() {
        return String.format("(%.2fin, %.2fin, %.1f°)", getX(DistanceUnit.INCH), getY(DistanceUnit.INCH), getHeading(AngleUnit.DEGREES));
    }
}
