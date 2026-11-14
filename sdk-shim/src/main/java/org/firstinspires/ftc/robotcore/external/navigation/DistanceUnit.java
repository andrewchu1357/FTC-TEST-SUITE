package org.firstinspires.ftc.robotcore.external.navigation;

import java.util.Locale;

/** A distance unit. The arithmetic matches SDK 12.0 exactly (same operation order). */
public enum DistanceUnit {
    METER(0), CM(1), MM(2), INCH(3);

    public final byte bVal;

    public static final double infinity = Double.MAX_VALUE;
    public static final double mmPerInch = 25.4;
    public static final double mPerInch = 0.0254;

    DistanceUnit(int i) {
        bVal = (byte) i;
    }

    public double fromMeters(double meters) {
        if (meters == infinity) return infinity;
        switch (this) {
            case CM: return meters * 100.0;
            case MM: return meters * 1000.0;
            case INCH: return meters / mPerInch;
            default: return meters;
        }
    }

    public double fromInches(double inches) {
        if (inches == infinity) return infinity;
        switch (this) {
            case CM: return inches * mPerInch * 100.0;
            case MM: return inches * mPerInch * 1000.0;
            case INCH: return inches;
            default: return inches * mPerInch;
        }
    }

    public double fromCm(double cm) {
        if (cm == infinity) return infinity;
        switch (this) {
            case CM: return cm;
            case MM: return cm * 10.0;
            case INCH: return fromMeters(METER.fromCm(cm));
            default: return cm / 100.0;
        }
    }

    public double fromMm(double mm) {
        if (mm == infinity) return infinity;
        switch (this) {
            case CM: return mm / 10.0;
            case MM: return mm;
            case INCH: return fromMeters(METER.fromMm(mm));
            default: return mm / 1000.0;
        }
    }

    public double fromUnit(DistanceUnit him, double his) {
        switch (him) {
            case CM: return fromCm(his);
            case MM: return fromMm(his);
            case INCH: return fromInches(his);
            default: return fromMeters(his);
        }
    }

    public double toMeters(double inOurUnits) { return METER.fromUnit(this, inOurUnits); }
    public double toInches(double inOurUnits) { return INCH.fromUnit(this, inOurUnits); }
    public double toCm(double inOurUnits) { return CM.fromUnit(this, inOurUnits); }
    public double toMm(double inOurUnits) { return MM.fromUnit(this, inOurUnits); }

    public String toString(double inOurUnits) {
        switch (this) {
            case CM: return String.format(Locale.getDefault(), "%.1fcm", inOurUnits);
            case MM: return String.format(Locale.getDefault(), "%.0fmm", inOurUnits);
            case INCH: return String.format(Locale.getDefault(), "%.2fin", inOurUnits);
            default: return String.format(Locale.getDefault(), "%.3fm", inOurUnits);
        }
    }

    @Override
    public String toString() {
        switch (this) {
            case CM: return "cm";
            case MM: return "mm";
            case INCH: return "in";
            default: return "m";
        }
    }
}
