package com.qualcomm.robotcore.hardware;

public interface DigitalChannel extends HardwareDevice {
    enum Mode { INPUT, OUTPUT }

    Mode getMode();

    void setMode(Mode mode);

    /** REV touch sensors read true when NOT pressed. */
    boolean getState();

    void setState(boolean state);

    /** The SDK's older way to set the mode. */
    @Deprecated
    default void setMode(DigitalChannelController.Mode mode) {
        setMode(mode.migrate());
    }
}
