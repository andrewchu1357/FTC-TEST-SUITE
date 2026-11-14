package com.qualcomm.robotcore.eventloop.opmode;

import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.HardwareMap;

import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.ftctestbed.shim.OpModeStopRequest;
import org.ftctestbed.shim.SimHooks;

/**
 * Iterative OpMode base class. The simulator's OpMode runner fills in hardwareMap,
 * telemetry and the gamepads, then drives init / init_loop / start / loop / stop.
 */
public abstract class OpMode {
    public Gamepad gamepad1 = new Gamepad();
    public Gamepad gamepad2 = new Gamepad();
    public Telemetry telemetry;
    public HardwareMap hardwareMap;
    public double time = 0.0;

    /**
     * Kept from one OpMode to the next until the Robot Controller restarts (SDK 10.1), e.g. an
     * AUTO's end pose for the TeleOp. Each simulated robot has its own.
     */
    public static java.util.HashMap<String, Object> blackboard = new org.ftctestbed.shim.Blackboard();

    public int msStuckDetectInit = 5000;
    public int msStuckDetectInitLoop = 5000;
    public int msStuckDetectStart = 5000;
    public int msStuckDetectLoop = 5000;
    public int msStuckDetectStop = 1000;

    private volatile long startTimeNanos = SimHooks.nanoTime();

    // Lifecycle flags, set by the simulator's OpMode runner (not part of the SDK API).
    volatile boolean simStarted = false;
    volatile boolean simStopRequested = false;
    private volatile Runnable simStopRequestHandler;

    public OpMode() {}

    public abstract void init();

    public void init_loop() {}

    public void start() {}

    public abstract void loop();

    public void stop() {}

    public final void requestOpModeStop() {
        Runnable handler = simStopRequestHandler;
        if (handler == null) handler = simRunningStopHandler; // an OpMode run by another (Pedro's tuning menu)
        if (handler != null) handler.run();
    }

    private static volatile Runnable simRunningStopHandler;

    /** Ends the OpMode immediately, like the SDK's terminateOpModeNow(). */
    public final void terminateOpModeNow() {
        throw new OpModeStopRequest();
    }

    public double getRuntime() {
        return (SimHooks.nanoTime() - startTimeNanos) / 1e9;
    }

    public void resetRuntime() {
        startTimeNanos = SimHooks.nanoTime();
    }

    public void updateTelemetry(Telemetry telemetry) {
        telemetry.update();
    }

    // ---- Simulator internals ----

    public final void internalSimSetStopRequestHandler(Runnable handler) {
        simStopRequestHandler = handler;
        simRunningStopHandler = handler;
    }

    /** An OpMode registered as an instance runs again: it starts over, not started or stopped. */
    public void internalSimResetForRun() {
        simStarted = false;
        simStopRequested = false;
    }

    public void internalSimStart() {
        simStarted = true;
    }

    public void internalSimRequestStop() {
        simStopRequested = true;
    }

    public final boolean internalSimIsStarted() {
        return simStarted;
    }

    public final boolean internalSimIsStopRequested() {
        return simStopRequested;
    }
}
