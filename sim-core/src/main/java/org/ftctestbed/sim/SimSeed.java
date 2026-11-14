package org.ftctestbed.sim;

/**
 * One number that moves every random stream of a simulation to another, repeatable one.
 *
 * Every noise source in the simulator (the IMU, the distance sensors, the cameras, dead-wheel
 * slip, the balls' launch scatter...) draws from its own random stream with a fixed seed, so
 * the same match always plays out the same. That is what makes results repeatable, and also what
 * makes them all one single draw: a robot that happens to pass with those seeds may fail with
 * others. {@code SimSeed} shifts all the streams at once:
 *
 * <ul>
 *   <li>offset 0 (the default): every stream is exactly as it always was;</li>
 *   <li>any other offset: every stream, and the game pieces' random variation, is a different
 *       one, the same one each time that offset is used.</li>
 * </ul>
 *
 * Fixed properties of a device (its personal gyro scale error, a pod's mounting angle) don't
 * move: they stand for the robot, which doesn't change from run to run. Set the offset before
 * a simulation is built. Start a JVM with {@code -Dtestbed.seed=N} to run with one.
 */
public final class SimSeed {
    private static volatile long offset = Long.getLong("testbed.seed", 0L);

    private SimSeed() {}

    public static long offset() {
        return offset;
    }

    public static void set(long newOffset) {
        offset = newOffset;
    }

    /** The seed for a random stream whose usual seed is {@code base}. */
    public static long mix(long base) {
        long o = offset;
        return o == 0 ? base : base ^ (o * 0x9E3779B97F4A7C15L);
    }
}
