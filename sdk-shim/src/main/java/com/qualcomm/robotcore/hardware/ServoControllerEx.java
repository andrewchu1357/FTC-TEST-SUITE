package com.qualcomm.robotcore.hardware;

public interface ServoControllerEx extends ServoController {
    void setServoPwmRange(int servo, PwmControl.PwmRange range);

    PwmControl.PwmRange getServoPwmRange(int servo);

    void setServoPwmEnable(int servo);

    void setServoPwmDisable(int servo);

    boolean isServoPwmEnabled(int servo);

    /** SDK 11.2: sends this pulse width (microseconds) straight to the servo, and turns it on. */
    void setPulseWidth(int servo, double microseconds);

    double getPulseWidth(int servo);
}
