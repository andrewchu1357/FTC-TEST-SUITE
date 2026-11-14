package org.firstinspires.ftc.robotcore.external.navigation;

/** A temperature reading with the time it was taken (System.nanoTime). */
public class Temperature {
    public TempUnit unit;
    public double temperature;
    public long acquisitionTime;

    public Temperature() {
        this(TempUnit.CELSIUS, 0, 0);
    }

    public Temperature(TempUnit unit, double temperature, long acquisitionTime) {
        this.unit = unit;
        this.temperature = temperature;
        this.acquisitionTime = acquisitionTime;
    }

    public Temperature toUnit(TempUnit tempUnit) {
        if (tempUnit == unit) return this;
        return new Temperature(tempUnit, tempUnit.fromUnit(unit, temperature), acquisitionTime);
    }
}
