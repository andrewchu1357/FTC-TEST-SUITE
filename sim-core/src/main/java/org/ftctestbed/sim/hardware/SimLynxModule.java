package org.ftctestbed.sim.hardware;

import com.qualcomm.hardware.lynx.LynxModule;

import java.util.function.DoubleSupplier;

public class SimLynxModule extends LynxModule {
    private final DoubleSupplier voltage;
    private final DoubleSupplier current;
    private final HubTiming timing;

    public SimLynxModule(String name, int address, boolean parent, DoubleSupplier voltage, DoubleSupplier current, HubTiming timing) {
        super(name, address, parent);
        this.voltage = voltage;
        this.current = current;
        this.timing = timing;
    }

    private volatile SimMotorController motors;

    public String name() { return name; }

    public void attachMotors(SimMotorController motors) { this.motors = motors; }

    @Override
    protected BulkData simBulkRead() {
        SimMotorController m = motors;
        if (m != null) return m.bulkRead();
        timing.command(this);
        return super.simBulkRead();
    }

    // Each of these is a command to the hub.
    @Override
    protected double simInputVoltageVolts() {
        timing.command(this);
        return voltage.getAsDouble();
    }

    @Override
    protected double simCurrentAmps() {
        timing.command(this);
        return current.getAsDouble();
    }
}
