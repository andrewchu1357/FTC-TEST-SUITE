package com.qualcomm.robotcore.hardware;

public interface AnalogInputController extends HardwareDevice {
    double getAnalogInputVoltage(int channel);

    double getMaxAnalogInputVoltage();
}
