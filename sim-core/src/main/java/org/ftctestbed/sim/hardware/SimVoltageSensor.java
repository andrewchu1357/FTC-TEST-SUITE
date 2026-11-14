package org.ftctestbed.sim.hardware;

import com.qualcomm.robotcore.hardware.VoltageSensor;

import java.util.function.DoubleSupplier;

public class SimVoltageSensor implements VoltageSensor {
    private final DoubleSupplier volts;
    private final Runnable readCost;

    /** readCost runs on every read (one hub command, see HubTiming). */
    public SimVoltageSensor(DoubleSupplier volts, Runnable readCost) {
        this.volts = volts;
        this.readCost = readCost;
    }

    @Override
    public double getVoltage() {
        readCost.run();
        return volts.getAsDouble();
    }

    @Override public Manufacturer getManufacturer() { return Manufacturer.Lynx; }
    @Override public String getDeviceName() { return "Simulated Battery Voltage Sensor"; }
    @Override public String getConnectionInfo() { return "hub"; }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() {}
    @Override public void close() {}
}
