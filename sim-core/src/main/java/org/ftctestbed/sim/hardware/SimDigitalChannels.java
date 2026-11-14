package org.ftctestbed.sim.hardware;

import com.qualcomm.robotcore.hardware.DigitalChannel;
import com.qualcomm.robotcore.hardware.DigitalChannelController;
import com.qualcomm.robotcore.util.SerialNumber;

/**
 * The 8 digital ports of one simulated hub, as outputs for LEDs (REV Digital LED Indicator). An
 * output port reads back what was last written; a write the hub doesn't already have is one hub
 * command, as is a read unless a bulk read covers it.
 */
public class SimDigitalChannels implements DigitalChannelController {
    private final SimLynxModule hub;
    private final HubTiming timing;
    private final DigitalChannel.Mode[] modes = new DigitalChannel.Mode[8];
    private final boolean[] states = new boolean[8];

    public SimDigitalChannels(SimLynxModule hub, HubTiming timing) {
        this.hub = hub;
        this.timing = timing;
        for (int i = 0; i < 8; i++) {
            modes[i] = DigitalChannel.Mode.INPUT;
            states[i] = true; // pulled up
        }
    }

    private static void check(int channel) {
        if (channel < 0 || channel > 7) throw new IllegalArgumentException("digital channel " + channel + " (0-7)");
    }

    @Override public SerialNumber getSerialNumber() { return SerialNumber.createFake(); }

    @Override
    public synchronized DigitalChannel.Mode getDigitalChannelMode(int channel) {
        check(channel);
        return modes[channel];
    }

    // Hub commands wait for simulated time, so they happen before taking the lock (the physics
    // thread reads the outputs under it).

    @Override
    public void setDigitalChannelMode(int channel, DigitalChannel.Mode mode) {
        check(channel);
        boolean changes;
        synchronized (this) {
            changes = modes[channel] != mode;
        }
        if (changes) timing.command(hub);
        synchronized (this) {
            modes[channel] = mode;
            if (mode == DigitalChannel.Mode.INPUT) states[channel] = true;
        }
    }

    @Deprecated
    @Override
    public void setDigitalChannelMode(int channel, Mode mode) {
        setDigitalChannelMode(channel, mode.migrate());
    }

    @Override
    public boolean getDigitalChannelState(int channel) {
        check(channel);
        if (hub.getBulkCachingMode() == com.qualcomm.hardware.lynx.LynxModule.BulkCachingMode.OFF) timing.command(hub);
        synchronized (this) {
            return states[channel];
        }
    }

    @Override
    public void setDigitalChannelState(int channel, boolean state) {
        check(channel);
        boolean changes;
        synchronized (this) {
            if (modes[channel] != DigitalChannel.Mode.OUTPUT) return; // an input port ignores writes
            changes = states[channel] != state;
        }
        if (changes) timing.command(hub);
        synchronized (this) {
            states[channel] = state;
        }
    }

    /** Simulator: the level an output drives (true = high), for showing LEDs. */
    public synchronized boolean simOutput(int channel) {
        return modes[channel] == DigitalChannel.Mode.OUTPUT && states[channel];
    }

    /** Simulator: true when the port drives its output low (an LED on it is lit). */
    public synchronized boolean simLow(int channel) {
        return modes[channel] == DigitalChannel.Mode.OUTPUT && !states[channel];
    }

    @Override public Manufacturer getManufacturer() { return Manufacturer.Lynx; }
    @Override public String getDeviceName() { return "Simulated REV Hub Digital Controller"; }
    @Override public String getConnectionInfo() { return hub.getConnectionInfo(); }
    @Override public int getVersion() { return 1; }

    @Override
    public synchronized void resetDeviceConfigurationForOpMode() {
        for (int i = 0; i < 8; i++) {
            modes[i] = DigitalChannel.Mode.INPUT;
            states[i] = true;
        }
    }

    @Override public void close() {}
}
