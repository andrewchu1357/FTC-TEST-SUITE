package org.firstinspires.ftc.robotcore.external.navigation;

/** An angle unit that never wraps: for rates and accumulated turns. Matches SDK 12.0 arithmetic. */
public enum UnnormalizedAngleUnit {
    DEGREES(0), RADIANS(1);

    public final byte bVal;

    UnnormalizedAngleUnit(int i) {
        bVal = (byte) i;
    }

    public double fromDegrees(double degrees) { return this == DEGREES ? degrees : degrees / 180.0 * Math.PI; }
    public float fromDegrees(float degrees) { return this == DEGREES ? degrees : degrees / 180.0f * AngleUnit.Pif; }
    public double fromRadians(double radians) { return this == DEGREES ? radians / Math.PI * 180.0 : radians; }
    public float fromRadians(float radians) { return this == DEGREES ? radians / AngleUnit.Pif * 180.0f : radians; }

    public double fromUnit(UnnormalizedAngleUnit them, double theirs) {
        return them == DEGREES ? fromDegrees(theirs) : fromRadians(theirs);
    }

    public float fromUnit(UnnormalizedAngleUnit them, float theirs) {
        return them == DEGREES ? fromDegrees(theirs) : fromRadians(theirs);
    }

    public double fromUnit(AngleUnit them, double theirs) { return fromUnit(them.getUnnormalized(), theirs); }
    public float fromUnit(AngleUnit them, float theirs) { return fromUnit(them.getUnnormalized(), theirs); }

    public double toDegrees(double inOurUnits) { return this == DEGREES ? DEGREES.fromDegrees(inOurUnits) : DEGREES.fromRadians(inOurUnits); }
    public float toDegrees(float inOurUnits) { return this == DEGREES ? DEGREES.fromDegrees(inOurUnits) : DEGREES.fromRadians(inOurUnits); }
    public double toRadians(double inOurUnits) { return this == DEGREES ? RADIANS.fromDegrees(inOurUnits) : RADIANS.fromRadians(inOurUnits); }
    public float toRadians(float inOurUnits) { return this == DEGREES ? RADIANS.fromDegrees(inOurUnits) : RADIANS.fromRadians(inOurUnits); }

    public AngleUnit getNormalized() { return this == DEGREES ? AngleUnit.DEGREES : AngleUnit.RADIANS; }
}
