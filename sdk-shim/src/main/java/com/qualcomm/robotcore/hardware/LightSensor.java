package com.qualcomm.robotcore.hardware;

/** A sensor that measures reflected light (0..1), optionally with its own LED. */
public interface LightSensor extends HardwareDevice {
    double getLightDetected();

    double getRawLightDetected();

    double getRawLightDetectedMax();

    void enableLed(boolean enable);

    String status();
}
