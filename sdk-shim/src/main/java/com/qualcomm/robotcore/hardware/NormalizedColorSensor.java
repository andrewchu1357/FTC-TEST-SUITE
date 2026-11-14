package com.qualcomm.robotcore.hardware;

public interface NormalizedColorSensor extends HardwareDevice {
    NormalizedRGBA getNormalizedColors();

    float getGain();

    void setGain(float newGain);
}
