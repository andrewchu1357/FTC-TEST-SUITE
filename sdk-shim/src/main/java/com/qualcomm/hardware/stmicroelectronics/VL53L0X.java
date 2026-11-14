package com.qualcomm.hardware.stmicroelectronics;

import com.qualcomm.robotcore.hardware.DistanceSensor;
import com.qualcomm.robotcore.hardware.I2cAddr;
import com.qualcomm.robotcore.hardware.I2cDeviceSynch;

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;

/**
 * The ST VL53L0X time-of-flight chip inside the REV 2m Distance Sensor and the AndyMark distance
 * sensor (am-5637). In the simulator the readings come from the simulated sensor it wraps.
 */
public class VL53L0X implements DistanceSensor {
    public static final I2cAddr ADDRESS_I2C_DEFAULT = I2cAddr.create8bit(0x52);

    protected final DistanceSensor simulated;

    public VL53L0X(I2cDeviceSynch deviceClient, boolean deviceClientIsOwned) {
        throw new UnsupportedOperationException("The simulator makes distance sensors from robot.jsonc (\"distanceSensors\")");
    }

    /** Simulator: a sensor whose distances come from this simulated one. */
    public VL53L0X(DistanceSensor simulated) {
        this.simulated = simulated;
    }

    /** The chip's model ID register (0xEE for a VL53L0X). */
    public byte getModelID() {
        return (byte) 0xEE;
    }

    /** True if the last measurement timed out (the simulated sensor always answers). */
    public boolean didTimeoutOccur() {
        return false;
    }

    @Override
    public double getDistance(DistanceUnit unit) {
        return simulated.getDistance(unit);
    }

    @Override public Manufacturer getManufacturer() { return Manufacturer.STMicroelectronics; }
    @Override public String getDeviceName() { return "VL53L0X"; }
    @Override public String getConnectionInfo() { return simulated.getConnectionInfo(); }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() { simulated.resetDeviceConfigurationForOpMode(); }
    @Override public void close() {}
}
