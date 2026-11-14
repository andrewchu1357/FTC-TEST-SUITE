package org.ftctestbed.shim;

/**
 * The only simulator-specific piece of the shim. Timing calls in the SDK classes
 * (ElapsedTime, LinearOpMode.sleep/idle, OpMode.getRuntime) route through here so the
 * simulator can run them on simulated time, pause them, or speed them up.
 *
 * Defaults behave like the real SDK (wall-clock time), so the shim also works standalone.
 */
public final class SimHooks {
    private SimHooks() {}

    /** Source of "now" in nanoseconds. */
    public interface Clock {
        long nanoTime();
    }

    /** How robot-code threads wait. */
    public interface Pacer {
        /** Block for the given simulated duration. */
        void sleep(long milliseconds) throws InterruptedException;

        /** Called from LinearOpMode.idle() / opModeIsActive(); real SDK just yields. */
        void idle();
    }

    /** Where RobotLog / android.util.Log output goes. */
    public interface LogSink {
        void log(String level, String tag, String message);
    }

    private static volatile Clock clock = System::nanoTime;
    private static volatile Pacer pacer = new Pacer() {
        @Override public void sleep(long milliseconds) throws InterruptedException { Thread.sleep(milliseconds); }
        @Override public void idle() { Thread.yield(); }
    };
    private static volatile LogSink logSink = (level, tag, message) ->
            System.out.println("[" + level + "] " + tag + ": " + message);

    public static long nanoTime() { return clock.nanoTime(); }
    public static void sleep(long milliseconds) throws InterruptedException { pacer.sleep(milliseconds); }
    public static void idle() { pacer.idle(); }
    public static void log(String level, String tag, String message) { logSink.log(level, tag, message); }

    private static volatile java.util.function.Consumer<String> warningSink = m -> {};

    /** A Driver Station warning from robot code (RobotLog.addGlobalWarningMessage). */
    public static void warn(String message) { warningSink.accept(message); }

    public static void installWarningSink(java.util.function.Consumer<String> sink) {
        warningSink = sink != null ? sink : m -> {};
    }

    private static volatile long clockEpochMillis = 0;

    /** Wall-clock time (ms since 1970) when the installed clock read zero; 0 when it's the real clock. */
    public static long clockEpochMillis() { return clockEpochMillis; }

    /** Plays robot code's sounds (SoundPlayer). The viewer installs one that plays them on the computer. */
    public interface SoundSink {
        void play(java.io.File file, float volume, int loopControl, Runnable whenFinished);

        void stopAll();
    }

    private static volatile SoundSink soundSink = new SoundSink() {
        @Override
        public void play(java.io.File file, float volume, int loopControl, Runnable whenFinished) {
            log("I", "SoundPlayer", "sound: " + file.getName());
            if (whenFinished != null) whenFinished.run();
        }

        @Override
        public void stopAll() {}
    };

    public static void playSound(java.io.File file, float volume, int loopControl, Runnable whenFinished) {
        soundSink.play(file, volume, loopControl, whenFinished);
    }

    public static void stopSounds() { soundSink.stopAll(); }

    public static void installSoundSink(SoundSink sink) {
        if (sink != null) soundSink = sink;
    }

    /** Which robot the calling thread runs code for (null: robot 1, or no simulator). */
    public interface RobotKey {
        Object current();
    }

    private static volatile RobotKey robotKey = () -> null;

    public static Object robotKey() { return robotKey.current(); }

    /** The simulator says how to tell robots apart, and starts every robot's OpMode.blackboard empty. */
    public static void installRobotKey(RobotKey key) {
        robotKey = key != null ? key : () -> null;
        Blackboard.simClearAll();
    }

    public static void install(Clock newClock, Pacer newPacer, LogSink newLogSink) {
        if (newClock != null) {
            clock = newClock;
            clockEpochMillis = System.currentTimeMillis() - newClock.nanoTime() / 1_000_000L;
        }
        if (newPacer != null) pacer = newPacer;
        if (newLogSink != null) logSink = newLogSink;
    }
}
