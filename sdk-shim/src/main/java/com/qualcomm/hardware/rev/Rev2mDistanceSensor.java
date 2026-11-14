package com.qualcomm.hardware.rev;

import com.qualcomm.hardware.stmicroelectronics.VL53L0X;
import com.qualcomm.robotcore.hardware.DistanceSensor;
import com.qualcomm.robotcore.hardware.I2cDeviceSynch;

/** The REV 2m Distance Sensor (REV-31-1505), a VL53L0X time-of-flight sensor. */
public class Rev2mDistanceSensor extends VL53L0X {
    public Rev2mDistanceSensor(I2cDeviceSynch deviceClient, boolean deviceClientIsOwned) {
        super(deviceClient, deviceClientIsOwned);
    }

    /** Simulator: a sensor whose distances come from this simulated one. */
    public Rev2mDistanceSensor(DistanceSensor simulated) {
        super(simulated);
    }

    @Override
    public String getDeviceName() {
        return "REV 2M ToF Distance Sensor";
    }
}
