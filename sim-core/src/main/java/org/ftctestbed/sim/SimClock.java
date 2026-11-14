package org.ftctestbed.sim;

/** Simulated time. Advanced only by the physics thread. */
public final class SimClock {
    private volatile long nanos = 0;
    private volatile long tick = 0;
    private final Object lock = new Object();

    public long nanos() { return nanos; }

    public double seconds() { return nanos / 1e9; }

    public long tick() { return tick; }

    public Object lock() { return lock; }

    void advance(long dtNanos) {
        synchronized (lock) {
            nanos += dtNanos;
            tick++;
            lock.notifyAll();
        }
    }
}
