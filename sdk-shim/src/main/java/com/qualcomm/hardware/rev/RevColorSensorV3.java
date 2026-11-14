package com.qualcomm.hardware.rev;

import com.qualcomm.robotcore.hardware.ColorRangeSensor;
import com.qualcomm.robotcore.hardware.I2cAddr;
import com.qualcomm.robotcore.hardware.I2cDeviceSynchSimple;
import com.qualcomm.robotcore.hardware.Light;
import com.qualcomm.robotcore.hardware.NormalizedRGBA;

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;

/**
 * The REV Color Sensor V3 (REV-31-1557, a Broadcom APDS-9151): color plus proximity, good to
 * about 10 cm. In the simulator the readings come from the simulated sensor it wraps.
 */
public class RevColorSensorV3 implements ColorRangeSensor, Light {
    protected final ColorRangeSensor simulated;

    public RevColorSensorV3(I2cDeviceSynchSimple deviceClient, boolean deviceClientIsOwned) {
        throw new UnsupportedOperationException("The simulator makes color sensors from robot.jsonc (\"colorSensors\")");
    }

    /** Simulator: a sensor whose readings come from this simulated one. */
    public RevColorSensorV3(ColorRangeSensor simulated) {
        this.simulated = simulated;
    }

    @Override public int red() { return simulated.red(); }
    @Override public int green() { return simulated.green(); }
    @Override public int blue() { return simulated.blue(); }
    @Override public int alpha() { return simulated.alpha(); }
    @Override public int argb() { return simulated.argb(); }
    @Override public NormalizedRGBA getNormalizedColors() { return simulated.getNormalizedColors(); }
    @Override public float getGain() { return simulated.getGain(); }
    @Override public void setGain(float newGain) { simulated.setGain(newGain); }
    @Override public void enableLed(boolean enable) { simulated.enableLed(enable); }
    @Override public boolean isLightOn() { return simulated instanceof Light && ((Light) simulated).isLightOn(); }
    @Override public void setI2cAddress(I2cAddr newAddress) { simulated.setI2cAddress(newAddress); }
    @Override public I2cAddr getI2cAddress() { return simulated.getI2cAddress(); }
    @Override public double getDistance(DistanceUnit unit) { return simulated.getDistance(unit); }
    @Override public double getLightDetected() { return simulated.getLightDetected(); }
    @Override public double getRawLightDetected() { return simulated.getRawLightDetected(); }
    @Override public double getRawLightDetectedMax() { return simulated.getRawLightDetectedMax(); }
    @Override public String status() { return getDeviceName() + " (simulated)"; }

    /** The raw proximity reading the distance is worked out from. */
    public int rawOptical() {
        return (int) Math.round(simulated.getRawLightDetected());
    }

    @Override public Manufacturer getManufacturer() { return Manufacturer.Broadcom; }
    @Override public String getDeviceName() { return "REV Color Sensor v3"; }
    @Override public String getConnectionInfo() { return simulated.getConnectionInfo(); }
    @Override public int getVersion() { return 3; }
    @Override public void resetDeviceConfigurationForOpMode() { simulated.resetDeviceConfigurationForOpMode(); }
    @Override public void close() {}
}
