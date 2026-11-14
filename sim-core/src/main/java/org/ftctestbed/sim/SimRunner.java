package org.ftctestbed.sim;

import org.ftctestbed.shim.SimHooks;

import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Owns the physics thread and the simulated clock that robot code runs on.
 *
 * Real-time mode (the viewer): physics steps are paced to the wall clock times a speed
 * factor; robot code runs freely on its own thread, exactly as on a Control Hub.
 *
 * Lockstep mode (headless): physics runs as fast as possible, but after each step waits
 * (briefly) for the OpMode thread to finish its loop iteration, so robot code still sees
 * every step. Code that busy-waits without calling opModeIsActive()/idle()/sleep() just
 * makes lockstep slower; it never deadlocks. In lockstep the clock never passes the
 * lockstep limit (see {@link #setLockstepLimit}), so a test harness decides how far time runs.
 *
 * Hardware calls take simulated time (see HubTiming): the running OpMode's thread keeps its
 * own "robot time", which each call pushes forward, and waits whenever that gets a physics
 * step ahead of the clock. That gives the same loop rates as a real Control Hub (tens of
 * milliseconds per loop) instead of one loop per physics step.
 *
 * Each robot on the field has its own OpMode thread with its own robot time (a Driver), so a
 * partner's OpMode never slows this one down; lockstep waits for every one of them.
 */
public final class SimRunner {
    private static final long DT_NANOS = Math.round(Simulation.DT * 1e9);
    // Lockstep waits for robot code to reach its next hardware call, sleep or idle, so its
    // computing (loading Road Runner's classes, JIT warm-up, building a trajectory) takes no
    // simulated time and results don't depend on how busy the computer is. Code that polls
    // hardware without ever spending time (thousands of free calls: a busy loop) lets time pass;
    // code that does nothing at all for LOCKSTEP_TIMEOUT is treated the same from then on.
    private static final long LOCKSTEP_TIMEOUT_NANOS = 30_000_000_000L;
    private static final long BUSY_CODE_TIMEOUT_NANOS = 100_000L;
    private static final long BUSY_POLL_ACCESSES = 2000;

    private final Simulation sim;
    private final SimClock clock;
    private Thread physicsThread;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile double speed = 1.0;
    private volatile boolean lockstep;
    private final java.util.concurrent.atomic.AtomicLong pendingManualSteps = new java.util.concurrent.atomic.AtomicLong();
    private volatile long lockstepLimitNanos = Long.MAX_VALUE;
    private volatile Throwable physicsError;

    /** One robot's OpMode thread and where it is in simulated time. */
    private static final class Driver {
        volatile Thread thread;
        volatile BooleanSupplier stopRequested = () -> false;
        volatile long yieldTick = -1;
        volatile long sleepUntil = 0;
        volatile long busyUntil = 0;
        volatile long lastLoopAt = -1;
        /**
         * True while the robot thread waits on the simulated clock (hardware time, sleep(), idle()).
         * The step that ends such a wait must let the thread run before going on: just after
         * being woken it can still look WAITING to getState(), and treating that as "waiting on
         * something else" would let physics run ahead of it by a step or two at random.
         */
        volatile boolean clockWait;
        volatile long loopHardwareNanos = 0;
        volatile double loopNanosAverage = Double.NaN;
        int missedYields;
    }

    private final Driver[] drivers;

    public SimRunner(Simulation sim) {
        this.sim = sim;
        this.clock = sim.clock;
        SimHooks.install(clock::nanos, new Pacer(), null);
        drivers = new Driver[sim.robots().size()];
        for (int i = 0; i < drivers.length; i++) {
            drivers[i] = new Driver();
            sim.robots().get(i).hubTiming.attach(this::spendRobotTime);
        }
        // Each robot's OpMode.blackboard is its own (robot 1's for threads that aren't a robot's).
        SimHooks.installRobotKey(() -> {
            Driver d = current();
            return d == null || d == drivers[0] ? null : d;
        });
        // ClassFactory's camera manager lists the calling robot's webcams.
        org.ftctestbed.shim.vision.SimVision.installWebcamLister(() -> {
            Driver d = current();
            int i = 0;
            for (int k = 0; k < drivers.length; k++) if (drivers[k] == d) i = k;
            return new java.util.ArrayList<org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName>(sim.robots().get(i).webcams());
        });
    }

    public Simulation simulation() { return sim; }

    public synchronized void start() {
        if (running) return;
        running = true;
        physicsThread = new Thread(this::loop, "physics");
        physicsThread.setDaemon(true);
        physicsThread.setPriority(Thread.MAX_PRIORITY);
        physicsThread.start();
    }

    public synchronized void shutdown() {
        running = false;
        if (physicsThread != null) physicsThread.interrupt();
    }

    public void setPaused(boolean p) {
        paused = p;
        Thread t = physicsThread;
        if (t != null) LockSupport.unpark(t);
    }
    public boolean isPaused() { return paused; }
    public void setSpeed(double s) { speed = Math.max(0.05, s); }
    public double speed() { return speed; }
    public void setLockstep(boolean l) { lockstep = l; }
    public boolean isLockstep() { return lockstep; }

    /** In lockstep mode, physics stops when the simulated clock reaches this time (Long.MAX_VALUE: no limit). */
    public void setLockstepLimit(long simNanos) {
        lockstepLimitNanos = simNanos;
        Thread t = physicsThread;
        if (t != null) LockSupport.unpark(t);
    }
    public Throwable physicsError() { return physicsError; }

    /** While paused, advance this many physics steps. */
    public void stepManually(int steps) {
        pendingManualSteps.addAndGet(steps);
        Thread t = physicsThread;
        if (t != null) LockSupport.unpark(t);
    }

    /** Tells lockstep mode which thread runs the first robot's code (null when no OpMode is running). */
    public void setRobotThread(Thread t, BooleanSupplier stopRequested) {
        setRobotThread(sim.robots().get(0), t, stopRequested);
    }

    /** Tells lockstep mode which thread runs this robot's code (null when its OpMode isn't running). */
    public void setRobotThread(SimRobot robot, Thread t, BooleanSupplier stopRequested) {
        Driver d = drivers[robot.number - 1];
        d.yieldTick = -1;
        d.sleepUntil = 0;
        d.busyUntil = 0;
        d.lastLoopAt = -1;
        d.loopHardwareNanos = 0;
        d.loopNanosAverage = Double.NaN;
        d.missedYields = 0;
        d.clockWait = false;
        d.stopRequested = stopRequested != null ? stopRequested : () -> false;
        d.thread = t;
    }

    /**
     * Told when a real-time loop starts and stops pacing itself against the wall clock (true while
     * it does: not while paused, and never in lockstep). The window uses it to ask Windows for its
     * 1 ms timer only while that matters: with the usual 15.6 ms timer each 2 ms wait in the loop
     * lasts eight steps, so physics would run in bursts and robot code would see time jump.
     */
    public static void setPacingListener(java.util.function.Consumer<Boolean> listener) {
        pacingListener = listener;
    }

    private static volatile java.util.function.Consumer<Boolean> pacingListener;

    private static void notifyPacing(boolean pacing) {
        java.util.function.Consumer<Boolean> l = pacingListener;
        if (l == null) return;
        try {
            l.accept(pacing);
        } catch (RuntimeException | Error e) {
            // a finer timer is a nicety: never the physics thread's problem
        }
    }

    private void loop() {
        long last = System.nanoTime();
        double debt = 0;
        boolean pacing = false;
        try {
            while (running) {
                boolean realTime = !paused && !lockstep;
                if (realTime != pacing) {
                    pacing = realTime;
                    notifyPacing(pacing);
                }
                if (paused) {
                    if (pendingManualSteps.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                        sim.step();
                    } else {
                        // Nothing to do until unpaused or asked for a step (both unpark this thread).
                        LockSupport.parkNanos(50_000_000L);
                    }
                    last = System.nanoTime();
                    debt = 0;
                    continue;
                }
                if (lockstep) {
                    if (clock.nanos() >= lockstepLimitNanos) {
                        LockSupport.parkNanos(1_000_000L); // nothing asked for more time
                    } else {
                        sim.step();
                        awaitRobotCode();
                    }
                    last = System.nanoTime();
                    continue;
                }
                long now = System.nanoTime();
                debt += (now - last) * speed;
                last = now;
                int n = 0;
                while (debt >= DT_NANOS && n < 200) {
                    sim.step();
                    debt -= DT_NANOS;
                    n++;
                }
                if (n == 200) debt = 0; // can't keep up; slow down rather than spiral
                long sleep = (long) ((DT_NANOS - debt) / speed);
                LockSupport.parkNanos(Math.max(sleep, 100_000L));
            }
        } catch (Throwable t) {
            physicsError = t;
            running = false;
            t.printStackTrace();
        } finally {
            if (pacing) notifyPacing(false);
        }
    }

    private void awaitRobotCode() {
        for (Driver d : drivers) awaitRobotCode(d);
    }

    private void awaitRobotCode(Driver d) {
        Thread t = d.thread;
        if (t == null) return;
        long tick = clock.tick();
        long accesses = org.ftctestbed.sim.hardware.ThreadFence.accesses();
        // A thread that didn't yield last time is still in its busy loop: only wait briefly.
        long timeout = d.missedYields >= 1 ? BUSY_CODE_TIMEOUT_NANOS : LOCKSTEP_TIMEOUT_NANOS;
        long deadline = System.nanoTime() + timeout;
        boolean waiting = false;
        while (t.isAlive() && d.yieldTick < tick && d.sleepUntil <= clock.nanos()
                && !d.stopRequested.getAsBoolean() && System.nanoTime() < deadline && running) {
            if (org.ftctestbed.sim.hardware.ThreadFence.accesses() - accesses > BUSY_POLL_ACCESSES) break; // polling
            // Waiting on something other than the simulation (waitForStart(), a lock, a future):
            // it isn't computing, so there's nothing to wait for. (Code waiting in idle()/sleep() was
            // woken by this step's tick, and has said so by now.)
            Thread.State st = t.getState();
            if ((st == Thread.State.WAITING || st == Thread.State.TIMED_WAITING) && !d.clockWait) {
                waiting = true;
                break;
            }
            Thread.onSpinWait();
        }
        boolean yielded = waiting || d.yieldTick >= tick || d.sleepUntil > clock.nanos() || !t.isAlive() || d.stopRequested.getAsBoolean();
        d.missedYields = yielded ? 0 : d.missedYields + 1;
    }

    /** The robot whose OpMode thread this is, or null (the viewer, a test, a thread the OpMode started). */
    private Driver current() {
        Thread me = Thread.currentThread();
        for (Driver d : drivers) if (d.thread == me) return d;
        return null;
    }

    private static boolean isRunning(Driver d) {
        return d != null && d.thread == Thread.currentThread();
    }

    /**
     * Average time per loop of the first robot's OpMode in milliseconds (simulated time), or NaN.
     * Loops that make no hardware calls (e.g. waiting for the end of AUTO) don't count.
     */
    public double robotLoopMillis() {
        return robotLoopMillis(sim.robots().get(0));
    }

    /** As robotLoopMillis(), for this robot's OpMode. */
    public double robotLoopMillis(SimRobot robot) {
        Driver d = drivers[robot.number - 1];
        return d.thread != null ? d.loopNanosAverage / 1e6 : Double.NaN;
    }

    /** Where a robot thread is in simulated time: the clock, or later if it is still busy with hardware. */
    private long robotNow(Driver d) {
        return Math.max(clock.nanos(), d.busyUntil);
    }

    /**
     * A hardware call on the robot thread: the call ends nanos after the previous one (or now).
     * Robot time may run up to one physics step ahead of the clock, so short calls add up
     * exactly instead of each rounding up to a whole step; beyond that the thread waits.
     */
    private void spendRobotTime(long nanos) {
        if (nanos <= 0) return;
        Driver d = current();
        if (d == null) return;
        d.loopHardwareNanos += nanos;
        d.busyUntil = robotNow(d) + nanos;
        long waitUntil = d.busyUntil - DT_NANOS;
        if (clock.nanos() > waitUntil) return;
        d.sleepUntil = waitUntil + 1; // lockstep: physics doesn't wait for a thread that is waiting on it
        Object lock = clock.lock();
        d.clockWait = true;
        try {
            synchronized (lock) {
                while (clock.nanos() <= waitUntil && running) {
                    if (Thread.currentThread().isInterrupted() || d.stopRequested.getAsBoolean()) return;
                    try {
                        lock.wait(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } finally {
            d.clockWait = false;
        }
    }

    /** Called at the end of each OpMode loop (idle / opModeIsActive). */
    private void recordLoop(Driver d) {
        long now = robotNow(d);
        long last = d.lastLoopAt;
        boolean usedHardware = d.loopHardwareNanos > 0;
        d.lastLoopAt = now;
        d.loopHardwareNanos = 0;
        if (last < 0 || now <= last || !usedHardware) return;
        double dt = now - last;
        double avg = d.loopNanosAverage;
        d.loopNanosAverage = Double.isNaN(avg) ? dt : avg + (dt - avg) * 0.05;
    }

    private final class Pacer implements SimHooks.Pacer {
        @Override
        public void sleep(long milliseconds) throws InterruptedException {
            org.ftctestbed.sim.hardware.ThreadFence.check();
            Driver d = current();
            long target = (d != null ? robotNow(d) : clock.nanos()) + milliseconds * 1_000_000L;
            if (d != null) d.sleepUntil = target;
            Object lock = clock.lock();
            if (isRunning(d)) d.clockWait = true;
            try {
                synchronized (lock) {
                    while (clock.nanos() < target) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        org.ftctestbed.sim.hardware.ThreadFence.check();
                        // STOP interrupts a sleeping OpMode, as in the SDK (sleep() then returns early).
                        if (isRunning(d) && d.stopRequested.getAsBoolean()) throw new InterruptedException();
                        lock.wait(20);
                    }
                }
            } finally {
                if (isRunning(d)) d.clockWait = false;
            }
            if (isRunning(d)) d.yieldTick = clock.tick();
        }

        @Override
        public void idle() {
            org.ftctestbed.sim.hardware.ThreadFence.check();
            long tick = clock.tick();
            Driver d = current();
            if (d != null) {
                recordLoop(d);
                // Hardware calls already used up this step's time: carry on, as idle() just yields in
                // the SDK. (Not a yield for lockstep: physics keeps waiting until the thread blocks.)
                if (d.busyUntil > clock.nanos()) return;
                d.yieldTick = tick;
            }
            Object lock = clock.lock();
            if (isRunning(d)) d.clockWait = true;
            try {
                synchronized (lock) {
                    while (clock.tick() == tick && running) {
                        if (Thread.currentThread().isInterrupted()) return;
                        if (isRunning(d) && d.stopRequested.getAsBoolean()) return;
                        try {
                            lock.wait(20);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            } finally {
                if (isRunning(d)) d.clockWait = false;
            }
        }
    }
}
