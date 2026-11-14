package org.firstinspires.ftc.robotcore.external.navigation;

public enum CurrentUnit {
    AMPS(0), MILLIAMPS(1);

    public final byte bVal;

    CurrentUnit(int i) {
        bVal = (byte) i;
    }

    /** Converts a current expressed in {@code unit} into this unit. */
    public double convert(double current, CurrentUnit unit) {
        if (this == unit) return current;
        return this == AMPS ? current / 1000.0 : current * 1000.0;
    }

    public double toAmps(double current) { return this == AMPS ? current : current / 1000.0; }
    public double toMilliAmps(double current) { return this == MILLIAMPS ? current : current * 1000.0; }
}
