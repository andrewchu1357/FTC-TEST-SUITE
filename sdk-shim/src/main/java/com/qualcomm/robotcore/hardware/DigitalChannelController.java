package com.qualcomm.robotcore.hardware;

import com.qualcomm.robotcore.util.SerialNumber;

/** The digital ports of a hub (0-7 on a REV hub). */
public interface DigitalChannelController extends HardwareDevice {
    /** The old name for DigitalChannel.Mode, kept by the SDK for older code. */
    @Deprecated
    enum Mode {
        INPUT, OUTPUT;

        public DigitalChannel.Mode migrate() {
            return this == INPUT ? DigitalChannel.Mode.INPUT : DigitalChannel.Mode.OUTPUT;
        }
    }

    SerialNumber getSerialNumber();

    DigitalChannel.Mode getDigitalChannelMode(int channel);

    void setDigitalChannelMode(int channel, DigitalChannel.Mode mode);

    @Deprecated
    void setDigitalChannelMode(int channel, Mode mode);

    boolean getDigitalChannelState(int channel);

    void setDigitalChannelState(int channel, boolean state);
}
