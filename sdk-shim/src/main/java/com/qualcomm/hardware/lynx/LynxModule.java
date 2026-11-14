package com.qualcomm.hardware.lynx;

import com.qualcomm.robotcore.hardware.Blinker;
import com.qualcomm.robotcore.hardware.HardwareDevice;

import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;
import org.firstinspires.ftc.robotcore.external.navigation.VoltageUnit;
import org.ftctestbed.shim.SimHooks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

/**
 * A REV Control Hub or Expansion Hub. Bulk caching behaves like the real hub: with MANUAL
 * caching, encoder reads stay stale until clearBulkCache() is called. Its status LED takes a
 * color (setConstant) or a blink pattern (up to 16 steps), like the hub's.
 */
public class LynxModule implements HardwareDevice, Blinker {
    public enum BulkCachingMode { OFF, MANUAL, AUTO }

    protected final String name;
    protected final int moduleAddress;
    protected final boolean isParent;
    protected volatile BulkCachingMode bulkCachingMode = BulkCachingMode.OFF;
    protected volatile int ledColor = 0xFF00FF00;
    private volatile long bulkCacheGeneration = 0;

    public LynxModule(String name, int moduleAddress, boolean isParent) {
        this.name = name;
        this.moduleAddress = moduleAddress;
        this.isParent = isParent;
    }

    public void setBulkCachingMode(BulkCachingMode mode) {
        bulkCachingMode = mode;
        clearBulkCache();
    }

    public BulkCachingMode getBulkCachingMode() { return bulkCachingMode; }

    public void clearBulkCache() { bulkCacheGeneration++; }

    /** Simulator internal: increments every time the bulk cache is cleared. */
    public long simGetBulkCacheGeneration() { return bulkCacheGeneration; }

    public double getInputVoltage(VoltageUnit unit) { return unit.convert(simInputVoltageVolts(), VoltageUnit.VOLTS); }

    public double getAuxiliaryVoltage(VoltageUnit unit) { return unit.convert(5.0, VoltageUnit.VOLTS); }

    public double getCurrent(CurrentUnit unit) { return unit.convert(simCurrentAmps(), CurrentUnit.AMPS); }

    public double getGpioBusCurrent(CurrentUnit unit) { return 0; }

    public double getI2cBusCurrent(CurrentUnit unit) { return 0; }

    /** Overridden by the simulator. */
    protected double simInputVoltageVolts() { return 12.0; }

    /** Overridden by the simulator. */
    protected double simCurrentAmps() { return 0; }

    // ---- Status LED (Blinker) ----

    private List<Step> pattern = new ArrayList<Step>();
    private long patternStartNanos;
    private final Deque<List<Step>> patternStack = new ArrayDeque<List<Step>>();

    @Override
    public synchronized void setConstant(int color) {
        List<Step> steps = new ArrayList<Step>();
        steps.add(new Step(color, 1, java.util.concurrent.TimeUnit.SECONDS));
        setPattern(steps);
        ledColor = color;
    }

    @Override
    public synchronized void stopBlinking() {
        setConstant(0);
    }

    @Override
    public synchronized void setPattern(Collection<Step> steps) {
        List<Step> copy = new ArrayList<Step>();
        if (steps != null) {
            for (Step s : steps) {
                if (copy.size() >= getBlinkerPatternMaxLength()) break;
                copy.add(s);
            }
        }
        pattern = copy;
        patternStartNanos = SimHooks.nanoTime();
        if (copy.size() == 1) ledColor = copy.get(0).getColor();
    }

    @Override
    public synchronized Collection<Step> getPattern() {
        return new ArrayList<Step>(pattern);
    }

    @Override
    public synchronized void pushPattern(Collection<Step> steps) {
        patternStack.push(pattern);
        setPattern(steps);
    }

    @Override
    public synchronized boolean patternStackNotEmpty() {
        return !patternStack.isEmpty();
    }

    @Override
    public synchronized boolean popPattern() {
        if (patternStack.isEmpty()) {
            setPattern(null);
            return false;
        }
        setPattern(patternStack.pop());
        return true;
    }

    @Override
    public int getBlinkerPatternMaxLength() {
        return 16;
    }

    /** The color the status LED shows right now (0xRRGGBB; a pattern steps through its colors). */
    public synchronized int simGetLedColor() {
        if (pattern.size() < 2) return ledColor;
        long total = 0;
        for (Step s : pattern) total += s.getDurationMs();
        if (total <= 0) return pattern.get(0).getColor();
        long ms = (SimHooks.nanoTime() - patternStartNanos) / 1_000_000L % total;
        for (Step s : pattern) {
            if (ms < s.getDurationMs()) return s.getColor();
            ms -= s.getDurationMs();
        }
        return ledColor;
    }

    public int getModuleAddress() { return moduleAddress; }

    /** The hub's firmware version, as the hub reports it. Road Runner refuses firmware older than 1.8.2. */
    public String getNullableFirmwareVersionString() { return "HW: 20, Maj: 1, Min: 8, Eng: 2"; }

    public String getFirmwareVersionString() { return getNullableFirmwareVersionString(); }

    /** One bulk read: every motor port's encoder and velocity, digital and analog inputs. */
    public BulkData getBulkData() { return simBulkRead(); }

    /** Overridden by the simulator. */
    protected BulkData simBulkRead() { return new BulkData(new int[4], new int[4], new boolean[4], new boolean[4], new boolean[8], new double[4], true); }

    /** The result of a bulk read. */
    public static class BulkData {
        private final int[] positions, velocities;
        private final boolean[] busy, overCurrent, digital;
        private final double[] analogVolts;
        private final boolean fake;

        protected BulkData(int[] positions, int[] velocities, boolean[] busy, boolean[] overCurrent, boolean[] digital,
                           double[] analogVolts, boolean fake) {
            this.positions = positions;
            this.velocities = velocities;
            this.busy = busy;
            this.overCurrent = overCurrent;
            this.digital = digital;
            this.analogVolts = analogVolts;
            this.fake = fake;
        }

        public boolean getDigitalChannelState(int digitalInputZ) { return digital[digitalInputZ]; }
        public int getMotorCurrentPosition(int motorZ) { return positions[motorZ]; }
        public int getMotorVelocity(int motorZ) { return velocities[motorZ]; }
        public boolean isMotorBusy(int motorZ) { return busy[motorZ]; }
        public boolean isMotorOverCurrent(int motorZ) { return overCurrent[motorZ]; }
        public double getAnalogInputVoltage(int inputZ) { return analogVolts[inputZ]; }

        public double getAnalogInputVoltage(int inputZ, VoltageUnit unit) {
            return unit.convert(analogVolts[inputZ], VoltageUnit.VOLTS);
        }

        public boolean isFake() { return fake; }
    }

    public boolean isParent() { return isParent; }

    @Override public Manufacturer getManufacturer() { return Manufacturer.Lynx; }
    @Override public String getDeviceName() { return isParent ? "REV Control Hub" : "REV Expansion Hub"; }
    @Override public String getConnectionInfo() { return name + " (module " + moduleAddress + ")"; }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() { setBulkCachingMode(BulkCachingMode.OFF); }
    @Override public void close() {}
}
