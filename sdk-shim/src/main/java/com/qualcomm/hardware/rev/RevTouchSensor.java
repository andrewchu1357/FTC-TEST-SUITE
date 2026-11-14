package com.qualcomm.hardware.rev;

import com.qualcomm.robotcore.hardware.DigitalChannelController;
import com.qualcomm.robotcore.hardware.TouchSensor;

/**
 * The REV Touch Sensor on a digital port. Its pin reads low (false) while pressed, so
 * isPressed() is the opposite of the pin.
 */
public class RevTouchSensor implements TouchSensor {
    private final DigitalChannelController controller;
    private final int physicalPort;

    public RevTouchSensor(DigitalChannelController controller, int physicalPort) {
        this.controller = controller;
        this.physicalPort = physicalPort;
    }

    /** Simulator: a subclass that knows whether it's pressed (a limit switch on a mechanism). */
    protected RevTouchSensor() {
        this.controller = null;
        this.physicalPort = -1;
    }

    @Override
    public double getValue() {
        return isPressed() ? 1 : 0;
    }

    @Override
    public boolean isPressed() {
        return !controller.getDigitalChannelState(physicalPort);
    }

    @Override public Manufacturer getManufacturer() { return Manufacturer.Lynx; }
    @Override public String getDeviceName() { return "REV Touch Sensor"; }
    @Override public String getConnectionInfo() { return controller.getConnectionInfo() + "; digital channel " + physicalPort; }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() {}
    @Override public void close() {}
}
