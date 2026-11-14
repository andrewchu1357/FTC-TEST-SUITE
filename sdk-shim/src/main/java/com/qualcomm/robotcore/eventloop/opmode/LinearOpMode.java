package com.qualcomm.robotcore.eventloop.opmode;

import org.ftctestbed.shim.SimHooks;

/**
 * Linear OpMode base class. runOpMode() runs on its own thread, as in the real SDK.
 * sleep() and idle() use simulated time so the robot code stays in step with the physics.
 */
public abstract class LinearOpMode extends OpMode {
    private final Object runningNotifier = new Object();

    public LinearOpMode() {}

    public abstract void runOpMode() throws InterruptedException;

    /**
     * Waits for START (or STOP during INIT, like the SDK). The SDK blocks on a monitor; here the
     * thread waits one simulation step at a time, so the simulator knows the OpMode is idle and
     * the code after waitForStart() runs on the step right after START.
     */
    public void waitForStart() {
        while (!isStarted() && !isStopRequested()) {
            SimHooks.idle();
            if (Thread.currentThread().isInterrupted()) return;
        }
    }

    /** Real SDK yields the thread; the simulator also waits for the next physics step. */
    public final void idle() {
        SimHooks.idle();
    }

    public final void sleep(long milliseconds) {
        try {
            SimHooks.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public final boolean opModeIsActive() {
        boolean isActive = !isStopRequested() && isStarted();
        if (isActive) idle();
        return isActive;
    }

    public final boolean opModeInInit() {
        boolean inInit = !isStarted() && !isStopRequested();
        if (inInit) idle();
        return inInit;
    }

    public final boolean isStarted() {
        return simStarted || Thread.currentThread().isInterrupted();
    }

    public final boolean isStopRequested() {
        return simStopRequested || Thread.currentThread().isInterrupted();
    }

    @Override
    public void internalSimStart() {
        super.internalSimStart();
        synchronized (runningNotifier) {
            runningNotifier.notifyAll();
        }
    }

    @Override
    public void internalSimRequestStop() {
        super.internalSimRequestStop();
        synchronized (runningNotifier) {
            runningNotifier.notifyAll();
        }
    }

    @Override public final void init() {}
    @Override public final void init_loop() {}
    @Override public final void start() {}
    @Override public final void loop() {}
    @Override public final void stop() {}
}
