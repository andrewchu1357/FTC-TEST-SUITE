package com.qualcomm.robotcore.hardware;

/**
 * An LED on a digital port, like the REV Digital LED Indicator (one LED device per color). The
 * port is driven low to light it, as in the SDK.
 */
public class LED implements HardwareDevice, SwitchableLight {
    private final DigitalChannelController controller;
    private final int physicalPort;

    public LED(DigitalChannelController controller, int physicalPort) {
        this.controller = controller;
        this.physicalPort = physicalPort;
        controller.setDigitalChannelMode(physicalPort, DigitalChannel.Mode.OUTPUT);
    }

    public void enable(boolean set) {
        controller.setDigitalChannelState(physicalPort, !set);
    }

    @Override
    public boolean isLightOn() {
        return !controller.getDigitalChannelState(physicalPort);
    }

    @Override
    public void enableLight(boolean enable) {
        enable(enable);
    }

    public void on() { enableLight(true); }

    public void off() { enableLight(false); }

    @Override public Manufacturer getManufacturer() { return controller.getManufacturer(); }
    @Override public String getDeviceName() { return "LED"; }
    @Override public String getConnectionInfo() { return controller.getConnectionInfo() + "; digital port " + physicalPort; }
    @Override public int getVersion() { return 0; }

    @Override
    public void resetDeviceConfigurationForOpMode() {
        controller.setDigitalChannelMode(physicalPort, DigitalChannel.Mode.OUTPUT);
    }

    @Override public void close() {}
}
