package com.qualcomm.hardware.andymark;

import com.qualcomm.hardware.bosch.BNO055IMUNew;
import com.qualcomm.robotcore.hardware.I2cDeviceSynchSimple;
import com.qualcomm.robotcore.hardware.IMU;

/**
 * The AndyMark IMU (SDK 11.0), a BNO055 on its own board. Describe how it's mounted with
 * AndyMarkIMUOrientationOnRobot (logo and I2C port directions); its chip sits turned 90° on the board.
 */
public class AndyMarkIMU extends BNO055IMUNew {
    public AndyMarkIMU(I2cDeviceSynchSimple deviceClient, boolean deviceClientIsOwned) {
        super(deviceClient, deviceClientIsOwned);
    }

    /** Simulator: an IMU whose readings come from this simulated one. */
    public AndyMarkIMU(IMU simulated) {
        super(simulated);
    }

    @Override public String getDeviceName() { return "AndyMark IMU"; }
    @Override public Manufacturer getManufacturer() { return Manufacturer.Other; }
}
