package org.firstinspires.ftc.robotcore.external.navigation;

public enum VoltageUnit {
    VOLTS(0), MILLIVOLTS(1);

    public final byte bVal;

    VoltageUnit(int i) {
        bVal = (byte) i;
    }

    /** Converts a voltage expressed in {@code unit} into this unit. */
    public double convert(double voltage, VoltageUnit unit) {
        if (this == unit) return voltage;
        return this == VOLTS ? voltage / 1000.0 : voltage * 1000.0;
    }

    public double toVolts(double voltage) { return this == VOLTS ? voltage : voltage / 1000.0; }
    public double toMilliVolts(double voltage) { return this == MILLIVOLTS ? voltage : voltage * 1000.0; }
}
