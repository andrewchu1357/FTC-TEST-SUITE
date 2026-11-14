package com.qualcomm.hardware.andymark;

import com.qualcomm.hardware.stmicroelectronics.VL53L0X;
import com.qualcomm.robotcore.hardware.DistanceSensor;
import com.qualcomm.robotcore.hardware.I2cDeviceSynch;

/** The AndyMark Distance Sensor (am-5637, I2C), a VL53L0X time-of-flight sensor (SDK 11.0). */
public class AndyMarkTOF extends VL53L0X {
    public AndyMarkTOF(I2cDeviceSynch deviceClient, boolean deviceClientIsOwned) {
        super(deviceClient, deviceClientIsOwned);
    }

    /** Simulator: a sensor whose distances come from this simulated one. */
    public AndyMarkTOF(DistanceSensor simulated) {
        super(simulated);
    }

    @Override
    public String getDeviceName() {
        return "AndyMark 2m TOF Lidar";
    }
}
