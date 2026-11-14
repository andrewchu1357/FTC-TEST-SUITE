package com.qualcomm.hardware.andymark;

import com.qualcomm.robotcore.hardware.ColorRangeSensor;
import com.qualcomm.robotcore.hardware.I2cAddr;
import com.qualcomm.robotcore.hardware.I2cDeviceSynch;
import com.qualcomm.robotcore.hardware.NormalizedRGBA;

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;

/**
 * The AndyMark Color Sensor (SDK 11.0): color plus proximity. Its raw color counts are about
 * 0-255 a channel [ESTIMATE, from the SDK's classifyColor thresholds]; classifyColor() is the
 * SDK's own rule. In the simulator the readings come from the simulated sensor it wraps.
 */
public class AndyMarkColorSensor implements ColorRangeSensor {
    public enum ProximityGain { GAIN_1X, GAIN_2X, GAIN_4X, GAIN_8X }

    public enum ProximityPulseLength { LENGTH_4US, LENGTH_8US, LENGTH_16US, LENGTH_32US }

    protected final ColorRangeSensor simulated;

    public AndyMarkColorSensor(I2cDeviceSynch deviceClient, boolean deviceClientIsOwned) {
        throw new UnsupportedOperationException("The simulator makes color sensors from robot.jsonc (\"colorSensors\")");
    }

    /** Simulator: a sensor whose readings come from this simulated one. */
    public AndyMarkColorSensor(ColorRangeSensor simulated) {
        this.simulated = simulated;
    }

    private int counts(float normalized) {
        return Math.min(255, Math.round(normalized * 255));
    }

    @Override public int red() { return counts(simulated.getNormalizedColors().red); }
    @Override public int green() { return counts(simulated.getNormalizedColors().green); }
    @Override public int blue() { return counts(simulated.getNormalizedColors().blue); }
    @Override public int alpha() { return counts(simulated.getNormalizedColors().alpha); }
    @Override public int argb() { return simulated.argb(); }
    @Override public NormalizedRGBA getNormalizedColors() { return simulated.getNormalizedColors(); }
    @Override public float getGain() { return simulated.getGain(); }
    @Override public void setGain(float newGain) { simulated.setGain(newGain); }
    @Override public void enableLed(boolean enable) { simulated.enableLed(enable); }
    @Override public void setI2cAddress(I2cAddr newAddress) { simulated.setI2cAddress(newAddress); }
    @Override public I2cAddr getI2cAddress() { return simulated.getI2cAddress(); }
    @Override public double getDistance(DistanceUnit unit) { return simulated.getDistance(unit); }
    @Override public double getLightDetected() { return simulated.getLightDetected(); }
    @Override public double getRawLightDetected() { return simulated.getRawLightDetected(); }
    @Override public double getRawLightDetectedMax() { return simulated.getRawLightDetectedMax(); }
    @Override public String status() { return getDeviceName() + " (simulated)"; }

    /** The SDK's color names: Unknown, Black, White, Yellow, Cyan, Magenta, Red, Green or Blue. */
    public String classifyColor() {
        NormalizedRGBA c = simulated.getNormalizedColors();
        int a = counts(c.alpha), r = counts(c.red), g = counts(c.green), b = counts(c.blue);
        int sum = r + g + b;
        if (a < 50 || sum < 50) return "Unknown";
        if (sum < 90) return "Black";
        if (sum > 450) return "White";
        if (r > 115 && g > 90 && b < 45) return "Yellow";
        if (g > 45 && b > 38 && r < 40) return "Cyan";
        if (r > 65 && g < 30 && b < 30) return "Magenta";
        int margin = 10;
        if (r > g + margin && r > b + margin) return "Red";
        if (g > r + margin && g > b + margin) return "Green";
        if (b > r + margin && b > g + margin) return "Blue";
        return "Unknown";
    }

    /** The raw proximity count (higher when closer). */
    public int getProximity() {
        double cm = simulated.getDistance(DistanceUnit.CM);
        return (int) Math.max(0, Math.min(2047, Math.round(2047 / (1 + cm * cm / 4))));
    }

    public void setProximityGain(ProximityGain gain) {}
    public void setProximityLedPulses(int pulses) {}
    public void setProximityLedPulseLength(ProximityPulseLength length) {}
    public void configureProximitySettings(ProximityGain gain, int pulses, ProximityPulseLength length) {}

    @Override public Manufacturer getManufacturer() { return Manufacturer.Other; }
    @Override public String getDeviceName() { return "AndyMark Proximity & Color Sensor"; }
    @Override public String getConnectionInfo() { return simulated.getConnectionInfo(); }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() { simulated.resetDeviceConfigurationForOpMode(); }
    @Override public void close() {}
}
