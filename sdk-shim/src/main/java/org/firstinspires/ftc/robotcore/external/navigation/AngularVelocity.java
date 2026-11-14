package org.firstinspires.ftc.robotcore.external.navigation;

import java.util.Locale;

/** Rotation rates about the x, y and z axes. Rates never wrap (the unit is unnormalized), as in the SDK. */
public class AngularVelocity {
    public UnnormalizedAngleUnit angleUnit;
    public float xRotationRate;
    public float yRotationRate;
    public float zRotationRate;
    public long acquisitionTime;

    public AngularVelocity() {
        this(AngleUnit.DEGREES, 0, 0, 0, 0);
    }

    public AngularVelocity(UnnormalizedAngleUnit angleUnit, float xRotationRate, float yRotationRate, float zRotationRate, long acquisitionTime) {
        this.angleUnit = angleUnit;
        this.xRotationRate = xRotationRate;
        this.yRotationRate = yRotationRate;
        this.zRotationRate = zRotationRate;
        this.acquisitionTime = acquisitionTime;
    }

    public AngularVelocity(AngleUnit angleUnit, float xRotationRate, float yRotationRate, float zRotationRate, long acquisitionTime) {
        this(angleUnit.getUnnormalized(), xRotationRate, yRotationRate, zRotationRate, acquisitionTime);
    }

    public AngularVelocity toAngleUnit(AngleUnit unit) {
        if (unit.getUnnormalized() == angleUnit) return this;
        UnnormalizedAngleUnit u = unit.getUnnormalized();
        return new AngularVelocity(unit, u.fromUnit(angleUnit, xRotationRate), u.fromUnit(angleUnit, yRotationRate),
                u.fromUnit(angleUnit, zRotationRate), acquisitionTime);
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "{x=%.3f, y=%.3f, z=%.3f (%s)}", xRotationRate, yRotationRate, zRotationRate, angleUnit);
    }
}
