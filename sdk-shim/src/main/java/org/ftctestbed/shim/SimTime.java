package org.ftctestbed.shim;

/**
 * Where robot code's own clock calls go. Libraries such as Road Runner, Pedro Pathing and FTCLib
 * (and team code) call System.nanoTime(), System.currentTimeMillis() and Thread.sleep()
 * directly; {@link ClockRewriter} points those calls here as their classes load, so they run on
 * simulated time like ElapsedTime does: right when the simulator is paused, sped up, slowed
 * down, or run as fast as possible in tests.
 *
 * Sleeping uses simulated time on the OpMode's thread and threads it started; other threads
 * (e.g. a class's static initializer run by the viewer) sleep on the wall clock.
 */
public final class SimTime {
    private SimTime() {}

    private static final InheritableThreadLocal<Boolean> ROBOT = new InheritableThreadLocal<>();

    /** Called by the simulator on the OpMode's thread; threads it starts inherit this. */
    public static void markRobotThread() {
        ROBOT.set(Boolean.TRUE);
    }

    public static boolean isRobotThread() {
        return Boolean.TRUE.equals(ROBOT.get());
    }

    public static long nanoTime() {
        return SimHooks.nanoTime();
    }

    /** The date and time when the simulator's clock started, advancing with simulated time. */
    public static long currentTimeMillis() {
        long epoch = SimHooks.clockEpochMillis();
        return epoch == 0 ? System.currentTimeMillis() : epoch + SimHooks.nanoTime() / 1_000_000L;
    }

    public static void sleep(long millis) throws InterruptedException {
        if (millis < 0) throw new IllegalArgumentException("timeout value is negative");
        if (isRobotThread()) SimHooks.sleep(millis);
        else Thread.sleep(millis);
    }

    public static void sleep(long millis, int nanos) throws InterruptedException {
        if (nanos < 0 || nanos > 999999) throw new IllegalArgumentException("nanosecond timeout value out of range");
        sleep(millis + (nanos >= 500000 ? 1 : 0));
    }
}
