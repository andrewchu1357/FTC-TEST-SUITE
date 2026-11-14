package org.ftctestbed.sim.hardware;

import com.qualcomm.hardware.lynx.LynxI2cDeviceSynch;
import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.hardware.I2cAddr;
import com.qualcomm.robotcore.hardware.I2cDeviceSynch;
import com.qualcomm.robotcore.hardware.I2cWaitControl;
import com.qualcomm.robotcore.hardware.TimestampedData;

import java.util.Arrays;
import java.util.function.LongSupplier;

/**
 * One device on a simulated hub I2C port. Drivers (the SDK's or a team's own) read and write its
 * registers; the {@link Firmware} answers like the real device's firmware. Each transaction
 * costs hub time for the bytes moved at the bus speed the driver chose (100 kHz unless it asks
 * for 400 kHz, reset for every OpMode as on a real hub).
 *
 * Robot thread only: the firmware's read/write never wait and take no locks the physics
 * thread holds, so a driver's synchronized methods can't deadlock the simulation.
 */
public final class SimI2cDevice extends LynxI2cDeviceSynch implements I2cDeviceSynch {
    /** The device's side of the bus. Called on the robot thread, at the moment the device sees the transaction. */
    public interface Firmware {
        /** The configuration XML tag of the device ("goBILDAPinpoint", "SparkFunOTOS"). */
        String xmlTag();

        /** The device's 7-bit address: other addresses get no answer (zeros), as with a wrong address. */
        int address7Bit();

        byte[] read(int register, int count);

        void write(int register, byte[] data);
    }

    private final Firmware firmware;
    private final LynxModule hub;
    private final HubTiming timing;
    private final LongSupplier clockNanos;
    private volatile BusSpeed busSpeed = BusSpeed.STANDARD_100K;
    private volatile boolean engaged = true;
    private volatile String userName = "";
    private volatile HealthStatus health = HealthStatus.UNKNOWN;
    private volatile boolean logging;
    private volatile String loggingTag = "";
    private volatile boolean writeCoalescing;
    private volatile ReadWindow readWindow;
    private volatile int heartbeatMs;
    private volatile HeartbeatAction heartbeatAction;

    public SimI2cDevice(Firmware firmware, LynxModule hub, int bus, HubTiming timing, LongSupplier clockNanos) {
        super(bus);
        this.firmware = firmware;
        this.hub = hub;
        this.timing = timing;
        this.clockNanos = clockNanos;
    }

    public Firmware firmware() {
        return firmware;
    }

    public BusSpeed busSpeed() {
        return busSpeed;
    }

    @Override
    public void setBusSpeed(BusSpeed speed) {
        ThreadFence.check();
        busSpeed = speed;
        timing.i2c(0, busSpeed == BusSpeed.FAST_400K, () -> {}); // configuring the port is a hub command
    }

    private boolean addressed() {
        return i2cAddr.get7Bit() == firmware.address7Bit();
    }

    // ------------------------------------------------------------------ transactions

    @Override
    public byte[] read(int ireg, int creg) {
        ThreadFence.check();
        byte[][] out = new byte[1][];
        timing.i2c(creg + 1, busSpeed == BusSpeed.FAST_400K, () -> {
            out[0] = addressed() ? firmware.read(ireg, creg) : new byte[creg];
            health = HealthStatus.HEALTHY;
        });
        return out[0].length == creg ? out[0] : Arrays.copyOf(out[0], creg);
    }

    @Override
    public void write(int ireg, byte[] data, I2cWaitControl waitControl) {
        ThreadFence.check();
        byte[] copy = data.clone();
        timing.i2c(copy.length + 1, busSpeed == BusSpeed.FAST_400K, () -> {
            if (addressed()) firmware.write(ireg, copy);
        });
    }

    @Override public byte read8() { return read8(0); }
    @Override public byte read8(int ireg) { return read(ireg, 1)[0]; }
    @Override public byte[] read(int creg) { return read(0, creg); }

    @Override
    public TimestampedData readTimeStamped(int ireg, int creg) {
        TimestampedData d = new TimestampedData();
        d.data = read(ireg, creg);
        d.nanoTime = clockNanos.getAsLong();
        return d;
    }

    @Override public TimestampedData readTimeStamped(int creg) { return readTimeStamped(0, creg); }

    @Override
    public TimestampedData readTimeStamped(int ireg, int creg, ReadWindow readWindowNeeded, ReadWindow readWindowSet) {
        ensureReadWindow(readWindowNeeded, readWindowSet);
        return readTimeStamped(ireg, creg);
    }

    @Override public void write8(int bVal) { write8(0, bVal); }
    @Override public void write8(int ireg, int bVal) { write(ireg, new byte[]{(byte) bVal}, I2cWaitControl.ATOMIC); }
    @Override public void write(byte[] data) { write(0, data); }
    @Override public void write(int ireg, byte[] data) { write(ireg, data, I2cWaitControl.ATOMIC); }
    @Override public void write8(int bVal, I2cWaitControl waitControl) { write8(0, bVal, waitControl); }
    @Override public void write8(int ireg, int bVal, I2cWaitControl waitControl) { write(ireg, new byte[]{(byte) bVal}, waitControl); }
    @Override public void write(byte[] data, I2cWaitControl waitControl) { write(0, data, waitControl); }
    @Override public void waitForWriteCompletions(I2cWaitControl waitControl) { ThreadFence.check(); }
    @Override public void enableWriteCoalescing(boolean enable) { writeCoalescing = enable; }
    @Override public boolean isWriteCoalescingEnabled() { return writeCoalescing; }
    @Override public boolean isArmed() { return true; }

    // ------------------------------------------------------------------ read windows and heartbeats (kept, not needed)

    @Override public void setReadWindow(ReadWindow window) { readWindow = window == null ? null : window.readableCopy(); }
    @Override public ReadWindow getReadWindow() { return readWindow; }

    @Override
    public void ensureReadWindow(ReadWindow windowNeeded, ReadWindow windowToSet) {
        ReadWindow current = readWindow;
        if (current == null || !current.containsWithSameMode(windowNeeded)) setReadWindow(windowToSet);
    }

    @Override public void setHeartbeatInterval(int ms) { heartbeatMs = Math.max(0, ms); }
    @Override public int getHeartbeatInterval() { return heartbeatMs; }
    @Override public void setHeartbeatAction(HeartbeatAction action) { heartbeatAction = action; }
    @Override public HeartbeatAction getHeartbeatAction() { return heartbeatAction; }

    // ------------------------------------------------------------------ device plumbing

    @Override public void engage() { engaged = true; }
    @Override public void disengage() { engaged = false; }
    @Override public boolean isEngaged() { return engaged; }
    @Override public void setI2cAddress(I2cAddr newAddress) { i2cAddr = newAddress; }
    @Override public I2cAddr getI2cAddress() { return i2cAddr; }
    @Override @Deprecated public void setI2cAddr(I2cAddr i2cAddr) { setI2cAddress(i2cAddr); }
    @Override @Deprecated public I2cAddr getI2cAddr() { return getI2cAddress(); }
    @Override public void setUserConfiguredName(String name) { userName = name == null ? "" : name; }
    @Override public String getUserConfiguredName() { return userName; }
    @Override public void setHealthStatus(HealthStatus status) { health = status; }
    @Override public HealthStatus getHealthStatus() { return health; }
    @Override public void setLogging(boolean enabled) { logging = enabled; }
    @Override public boolean getLogging() { return logging; }
    @Override public void setLoggingTag(String tag) { loggingTag = tag; }
    @Override public String getLoggingTag() { return loggingTag; }
    @Override public Manufacturer getManufacturer() { return Manufacturer.Lynx; }
    @Override public String getDeviceName() { return "Lynx I2C Device"; }

    @Override
    public String getConnectionInfo() {
        return String.format("%s; bus %d; addr7=0x%02x", hub.getConnectionInfo(), bus, i2cAddr.get7Bit());
    }

    @Override public int getVersion() { return 1; }

    /** As on a hub: every OpMode starts the port at 100 kHz. */
    @Override public void resetDeviceConfigurationForOpMode() { busSpeed = BusSpeed.STANDARD_100K; }

    @Override public void close() {}
}
