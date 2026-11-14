package com.qualcomm.robotcore.hardware;

public interface ServoController extends HardwareDevice {
    enum PwmStatus { ENABLED, DISABLED, MIXED }

    void pwmEnable();

    void pwmDisable();

    PwmStatus getPwmStatus();

    void setServoPosition(int servo, double position);

    double getServoPosition(int servo);
}
