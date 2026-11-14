package com.qualcomm.hardware.lynx;

import com.qualcomm.robotcore.hardware.I2cAddr;
import com.qualcomm.robotcore.hardware.I2cDeviceSynchSimple;

/**
 * An I2C port on a REV hub. In the simulator this is the simulated hub's I2C bus talking to a
 * simulated device's firmware (its registers); drivers such as GoBildaPinpointDriver cast
 * their client to this class to set the bus speed, as on a real hub.
 */
public abstract class LynxI2cDeviceSynch implements I2cDeviceSynchSimple {
    public static final String TAG = "LynxI2cDeviceSynch";

    public enum BusSpeed { STANDARD_100K, FAST_400K }

    protected I2cAddr i2cAddr = I2cAddr.zero();
    protected int bus;

    protected LynxI2cDeviceSynch(int bus) {
        this.bus = bus;
    }

    /** STANDARD_100K after every OpMode reset, as on a real hub. */
    public abstract void setBusSpeed(BusSpeed speed);
}
