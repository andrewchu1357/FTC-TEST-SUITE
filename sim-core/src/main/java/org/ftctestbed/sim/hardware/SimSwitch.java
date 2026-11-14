package org.ftctestbed.sim.hardware;

import com.qualcomm.hardware.rev.RevTouchSensor;
import com.qualcomm.robotcore.hardware.DigitalChannel;

/**
 * A limit switch: a REV Touch Sensor (or magnetic limit switch) on a digital port. Teams read it
 * as a TouchSensor / RevTouchSensor or as a DigitalChannel, so it's all three. As a DigitalChannel
 * it reads like the real one: true when NOT pressed.
 */
public class SimSwitch extends RevTouchSensor implements DigitalChannel {
    public final String name;
    private final Runnable readCost;
    private volatile boolean pressed = false;
    private volatile Mode mode = Mode.INPUT;

    /** readCost runs on every read (a hub command, unless a bulk read covers it). */
    public SimSwitch(String name, Runnable readCost) {
        this.name = name;
        this.readCost = readCost;
    }

    public void setPressed(boolean p) { pressed = p; }

    @Override
    public double getValue() {
        readCost.run();
        return pressed ? 1.0 : 0.0;
    }

    @Override
    public boolean isPressed() {
        readCost.run();
        return pressed;
    }

    @Override public Mode getMode() { return mode; }
    @Override public void setMode(Mode mode) { this.mode = mode; }
    @Override
    public boolean getState() {
        readCost.run();
        return !pressed;
    }
    @Override public void setState(boolean state) {}

    @Override public Manufacturer getManufacturer() { return Manufacturer.Other; }
    @Override public String getDeviceName() { return "Simulated Limit Switch"; }
    @Override public String getConnectionInfo() { return "digital"; }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() { mode = Mode.INPUT; }
    @Override public void close() {}
}
