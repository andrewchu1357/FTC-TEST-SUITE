package org.firstinspires.ftc.robotcore.external.navigation;

/**
 * An angle unit. As in the SDK, every conversion normalizes its result to [-180, 180) degrees
 * or [-PI, PI) radians, so headings wrap; use {@link UnnormalizedAngleUnit} for rates and
 * accumulated turns. The arithmetic matches SDK 12.0 exactly (same operation order).
 */
public enum AngleUnit {
    DEGREES(0), RADIANS(1);

    public final byte bVal;

    protected static final double TwoPi = 2 * Math.PI;
    public static final float Pif = (float) Math.PI;

    AngleUnit(int i) {
        bVal = (byte) i;
    }

    public double fromDegrees(double degrees) {
        return this == DEGREES ? normalize(degrees) : normalize(degrees / 180.0 * Math.PI);
    }

    public float fromDegrees(float degrees) {
        return this == DEGREES ? normalize(degrees) : normalize(degrees / 180.0f * Pif);
    }

    public double fromRadians(double radians) {
        return this == DEGREES ? normalize(radians / Math.PI * 180.0) : normalize(radians);
    }

    public float fromRadians(float radians) {
        return this == DEGREES ? normalize(radians / Pif * 180.0f) : normalize(radians);
    }

    public double fromUnit(AngleUnit them, double theirs) {
        return them == DEGREES ? fromDegrees(theirs) : fromRadians(theirs);
    }

    public float fromUnit(AngleUnit them, float theirs) {
        return them == DEGREES ? fromDegrees(theirs) : fromRadians(theirs);
    }

    public double toDegrees(double inOurUnits) {
        return this == DEGREES ? DEGREES.fromDegrees(inOurUnits) : DEGREES.fromRadians(inOurUnits);
    }

    public float toDegrees(float inOurUnits) {
        return this == DEGREES ? DEGREES.fromDegrees(inOurUnits) : DEGREES.fromRadians(inOurUnits);
    }

    public double toRadians(double inOurUnits) {
        return this == DEGREES ? RADIANS.fromDegrees(inOurUnits) : RADIANS.fromRadians(inOurUnits);
    }

    public float toRadians(float inOurUnits) {
        return this == DEGREES ? RADIANS.fromDegrees(inOurUnits) : RADIANS.fromRadians(inOurUnits);
    }

    public double normalize(double mine) {
        return this == DEGREES ? normalizeDegrees(mine) : normalizeRadians(mine);
    }

    public float normalize(float mine) {
        return this == DEGREES ? normalizeDegrees(mine) : normalizeRadians(mine);
    }

    /** Normalizes to [-180, 180). */
    public static double normalizeDegrees(double degrees) {
        if (Math.abs(degrees) > 720.0) {
            double turns = Math.floor(Math.abs(degrees) / 360.0);
            degrees = Math.signum(degrees) * (Math.abs(degrees) - turns * 360.0);
        }
        while (degrees >= 180.0) degrees -= 360.0;
        while (degrees < -180.0) degrees += 360.0;
        return degrees;
    }

    public static float normalizeDegrees(float degrees) {
        return (float) normalizeDegrees((double) degrees);
    }

    /** Normalizes to [-PI, PI). */
    public static double normalizeRadians(double radians) {
        if (Math.abs(radians) > 2 * TwoPi) {
            double turns = Math.floor(Math.abs(radians) / TwoPi);
            radians = Math.signum(radians) * (Math.abs(radians) - turns * TwoPi);
        }
        while (radians >= Math.PI) radians -= TwoPi;
        while (radians < -Math.PI) radians += TwoPi;
        return radians;
    }

    public static float normalizeRadians(float radians) {
        return (float) normalizeRadians((double) radians);
    }

    public UnnormalizedAngleUnit getUnnormalized() {
        return this == DEGREES ? UnnormalizedAngleUnit.DEGREES : UnnormalizedAngleUnit.RADIANS;
    }
}
