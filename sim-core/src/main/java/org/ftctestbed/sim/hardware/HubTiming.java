package org.ftctestbed.sim.hardware;

import com.qualcomm.hardware.lynx.LynxModule;

import org.ftctestbed.sim.config.RobotConfig;

/**
 * How long hardware calls take. On a real robot every command to a REV hub (set a motor's
 * power, read an encoder, a bulk read, ...) is a message to the hub and back, and I2C sensors
 * (IMU, color, distance) take longer still. Robot code waits for each one, which is what
 * limits a real OpMode's loop rate (gm0's example: 4 setPower + 1 encoder read + 1 IMU read
 * is about 22 ms, 45 Hz).
 *
 * Devices report each call here; the physics runner makes the running OpMode's thread wait
 * that long in simulated time. Other threads (the viewer, tests that drive devices directly)
 * don't pay anything.
 */
public final class HubTiming {
    /** Spends simulated time on the calling thread. */
    public interface Waiter {
        void spend(long nanos);
    }

    private static final Waiter NONE = nanos -> {};

    private final long controlHubCommandNanos;
    private final long expansionHubCommandNanos;
    private final long i2cNanos;
    private volatile Waiter waiter = NONE;

    public HubTiming(RobotConfig.Timing t) {
        controlHubCommandNanos = Math.round(t.controlHubCommandMs() * 1e6);
        expansionHubCommandNanos = Math.round(t.expansionHubCommandMs() * 1e6);
        i2cNanos = Math.round(t.i2cMs() * 1e6);
    }

    /** Called by the physics runner. */
    public void attach(Waiter w) {
        waiter = w != null ? w : NONE;
    }

    /** One command to a hub (a write, a single read, or a bulk read). */
    public void command(LynxModule hub) {
        waiter.spend(hub.isParent() ? controlHubCommandNanos : expansionHubCommandNanos);
    }

    /** One I2C read or write (IMU, color sensor, distance sensor). */
    public void i2c() {
        waiter.spend(i2cNanos);
    }

    /** [ESTIMATE] A command to a USB network device (a Limelight's HTTP request): about 4 ms. */
    public void network() {
        waiter.spend(4_000_000L);
    }

    /**
     * One I2C transaction moving {@code bytes} bytes (register address included) at the bus
     * speed: the hub command's overhead plus 9 bits per byte on the wire. The device is read or
     * written halfway through, so what comes back is as old as it is on a real hub.
     */
    public void i2c(int bytes, boolean fast400k, Runnable atDevice) {
        long total = i2cNanos + Math.round(bytes * 9 * 1e9 / (fast400k ? 400_000 : 100_000));
        waiter.spend(total / 2);
        atDevice.run();
        waiter.spend(total - total / 2);
    }
}
