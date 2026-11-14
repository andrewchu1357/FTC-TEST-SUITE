package com.qualcomm.robotcore.hardware;

public class AnalogInput implements HardwareDevice {
    private final AnalogInputController controller;
    private final int channel;

    public AnalogInput(AnalogInputController controller, int channel) {
        this.controller = controller;
        this.channel = channel;
    }

    public double getVoltage() { return controller.getAnalogInputVoltage(channel); }
    public double getMaxVoltage() { return controller.getMaxAnalogInputVoltage(); }

    @Override public Manufacturer getManufacturer() { return controller.getManufacturer(); }
    @Override public String getDeviceName() { return "Analog Input"; }
    @Override public String getConnectionInfo() { return controller.getConnectionInfo() + "; analog port " + channel; }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() {}
    @Override public void close() {}
}
