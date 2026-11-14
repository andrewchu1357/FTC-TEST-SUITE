package org.firstinspires.ftc.robotcore.external.navigation;

/** Units of temperature (the SDK spells Fahrenheit "FARENHEIT"). */
public enum TempUnit {
    CELSIUS(0), FARENHEIT(1), KELVIN(2);

    public final byte bVal;
    public static final double zeroCelsiusK = 273.15;
    public static final double zeroCelsiusF = 32;
    public static final double CperF = 5.0 / 9.0;

    TempUnit(int i) {
        bVal = (byte) i;
    }

    public double fromCelsius(double celsius) {
        switch (this) {
            case FARENHEIT: return celsius / CperF + zeroCelsiusF;
            case KELVIN: return celsius + zeroCelsiusK;
            default: return celsius;
        }
    }

    public double fromKelvin(double kelvin) {
        return fromCelsius(kelvin - zeroCelsiusK);
    }

    public double fromFarenheit(double farenheit) {
        return fromCelsius((farenheit - zeroCelsiusF) * CperF);
    }

    public double fromUnit(TempUnit him, double his) {
        switch (him) {
            case FARENHEIT: return fromFarenheit(his);
            case KELVIN: return fromKelvin(his);
            default: return fromCelsius(his);
        }
    }
}
