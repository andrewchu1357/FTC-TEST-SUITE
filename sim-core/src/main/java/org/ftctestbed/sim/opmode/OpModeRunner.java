package org.ftctestbed.sim.opmode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.HardwareDevice;

import org.ftctestbed.shim.OpModeStopRequest;
import org.ftctestbed.shim.SimHooks;
import org.ftctestbed.sim.hardware.ThreadFence;
import org.ftctestbed.sim.SimRobot;
import org.ftctestbed.sim.SimRunner;
import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.hardware.MotorPort;
import org.ftctestbed.sim.hardware.ServoPort;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plays the role of the Driver Station + Robot Controller app: INIT, START and STOP an
 * OpMode, inject hardwareMap/telemetry/gamepads, and report crashes the way the DS does.
 * One per robot: a partner robot has its own runner, OpMode, gamepads and telemetry.
 */
public final class OpModeRunner {
    public enum State { IDLE, INIT, RUNNING, STOPPING, STOPPED }

    public static final double AUTONOMOUS_SECONDS = 30.0;

    private final Simulation sim;
    private final SimRobot robot;
    private final SimRunner simRunner;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Live controller inputs; the viewer writes these every frame. */
    public final Gamepad gamepad1 = new Gamepad();
    public final Gamepad gamepad2 = new Gamepad();

    private volatile State state = State.IDLE;
    private volatile OpModeInfo current;
    private volatile OpMode opMode;
    private volatile Thread thread;
    private volatile SimTelemetry telemetry = new SimTelemetry();
    private volatile String error;
    private volatile String warning;
    private volatile double initTime;
    private volatile double startTime;
    private volatile boolean autoTimer = true;
    private volatile boolean autoStopFired = false;

    /** The runner for the first robot. */
    public OpModeRunner(Simulation sim, SimRunner simRunner) {
        this(sim.robot(1), simRunner);
    }

    public OpModeRunner(SimRobot robot, SimRunner simRunner) {
        this.sim = robot.simulation();
        this.robot = robot;
        this.simRunner = simRunner;
        gamepad1.id = 1;
        gamepad2.id = 2;
        sim.addStepListener(s -> checkAutoTimer());
    }

    public void addListener(Runnable r) { listeners.add(r); }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    /** Log tag: which robot's OpMode it was, once there is more than one. */
    private String tag() {
        return robot.number == 1 ? "OpModeRunner" : "OpModeRunner (robot " + robot.number + ")";
    }

    /** The robot this runner's OpModes drive. */
    public SimRobot robot() { return robot; }

    public State state() { return state; }
    public OpModeInfo current() { return current; }
    public SimTelemetry telemetry() { return telemetry; }
    public String error() { return error; }
    public String warning() { return warning; }
    public void setAutoTimer(boolean on) { autoTimer = on; }
    public boolean autoTimer() { return autoTimer; }

    /** Seconds since START (or since INIT while in init). */
    public double elapsed() {
        double now = sim.clock.seconds();
        return switch (state) {
            case RUNNING, STOPPING -> now - startTime;
            case INIT -> now - initTime;
            default -> 0;
        };
    }

    public synchronized void init(OpModeInfo info) {
        if (state == State.INIT || state == State.RUNNING || state == State.STOPPING) return;
        error = null;
        warning = null;
        current = info;
        for (HardwareDevice d : robot.hardwareMap) d.resetDeviceConfigurationForOpMode();
        if (robot.number == 1) {
            // One FTC Dashboard and one Panels for the whole simulator: they follow the first robot's OpModes.
            com.acmerobotics.dashboard.FtcDashboard.getInstance().simReset();
            com.bylazar.telemetry.PanelsTelemetry.INSTANCE.simReset();
            com.bylazar.field.PanelsField.INSTANCE.getField().simReset();
        }
        gamepad1.reset();
        gamepad2.reset();
        telemetry = new SimTelemetry();
        try {
            if (info.instance() != null) {
                opMode = info.instance();
                opMode.internalSimResetForRun();
            } else {
                opMode = info.type().getDeclaredConstructor().newInstance();
            }
        } catch (Throwable t) {
            error = "Could not create " + info.type().getName() + ": " + describeHere(rootCause(t));
            state = State.STOPPED;
            fire();
            return;
        }
        opMode.hardwareMap = robot.hardwareMap;
        opMode.telemetry = telemetry;
        opMode.gamepad1 = gamepad1;
        opMode.gamepad2 = gamepad2;
        opMode.internalSimSetStopRequestHandler(this::stop);
        opMode.resetRuntime();
        initTime = sim.clock.seconds();
        notifyListeners(l -> l.onOpModePreInit(opMode));
        Thread t = new Thread(this::runOpMode, "OpMode: " + info.name());
        t.setContextClassLoader(info.type().getClassLoader()); // team code loaded from a robot package
        t.setDaemon(true);
        thread = t;
        // The robot thread from INIT on: in lockstep, physics waits for its init code too, and its
        // hardware calls take hub time during INIT as on a real robot.
        OpMode initOp = opMode;
        simRunner.setRobotThread(robot, t, initOp::internalSimIsStopRequested);
        state = State.INIT;
        SimHooks.log("I", tag(), "INIT " + info.name());
        t.start();
        fire();
    }

    public synchronized void start() {
        if (state != State.INIT) return;
        startTime = sim.clock.seconds();
        autoStopFired = false;
        state = State.RUNNING;
        OpMode op = opMode;
        Thread t = thread;
        notifyListeners(l -> l.onOpModePreStart(op));
        op.internalSimStart();
        SimHooks.log("I", tag(), "START " + current.name());
        fire();
    }

    public void stop() {
        OpMode op;
        Thread t;
        synchronized (this) {
            if (state != State.INIT && state != State.RUNNING) return;
            state = State.STOPPING;
            op = opMode;
            t = thread;
        }
        SimHooks.log("I", tag(), "STOP " + current.name());
        op.internalSimRequestStop();
        // As in the SDK, STOP interrupts a LinearOpMode's thread at once (its sleep() returns,
        // and loops like Road Runner's Actions.runBlocking that check the interrupt flag end).
        if (op instanceof LinearOpMode && t != null) t.interrupt();
        fire();
        Thread watcher = new Thread(() -> {
            try {
                t.join(Math.max(op.msStuckDetectStop, 500));
                if (t.isAlive()) {
                    t.interrupt();
                    t.join(2000);
                }
                if (t.isAlive()) {
                    // The warning first: once fenced, the OpMode's own thread may get to finished() before this one.
                    warning = "OpMode did not stop within " + (op.msStuckDetectStop + 2000)
                            + " ms after STOP. A real robot would restart the Robot Controller app. "
                            + "Make loops check opModeIsActive().";
                    ThreadFence.fence(t); // its next hardware call or sleep() ends it
                    finished();
                }
            } catch (InterruptedException ignored) {
            }
        }, "OpMode stop watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private void runOpMode() {
        OpMode op = opMode;
        org.ftctestbed.shim.SimTime.markRobotThread(); // its Thread.sleep() and its threads' run on simulated time
        try {
            if (op instanceof LinearOpMode linear) {
                linear.runOpMode();
            } else {
                op.init();
                op.telemetry.update();
                while (!op.internalSimIsStarted() && !op.internalSimIsStopRequested()) {
                    op.init_loop();
                    op.telemetry.update();
                    SimHooks.idle();
                }
                if (!op.internalSimIsStopRequested()) {
                    op.start();
                    op.telemetry.update();
                    while (!op.internalSimIsStopRequested()) {
                        op.time = op.getRuntime();
                        op.loop();
                        op.telemetry.update();
                        SimHooks.idle();
                    }
                }
                op.stop();
            }
        } catch (OpModeStopRequest | InterruptedException | ThreadFence.OpModeKilled e) {
            // Normal ways for an OpMode to end.
        } catch (Throwable t) {
            if (Thread.currentThread() != thread) return; // an old OpMode thread that was given up on
            error = "User code threw an uncaught exception: " + describeHere(t);
            SimHooks.log("E", tag(), error);
            t.printStackTrace();
        } finally {
            if (Thread.currentThread() == thread) finished();
        }
    }

    private synchronized void finished() {
        if (state == State.STOPPED || state == State.IDLE) return;
        simRunner.setRobotThread(robot, null, null);
        for (MotorPort p : robot.motorPorts().values()) p.setPower(0);
        for (ServoPort s : robot.servoPorts()) if (s.def.continuous()) s.setPwmEnabled(false);
        // Listeners first (a VisionPortal's camera closes), so anyone waiting for STOPPED sees it all done.
        OpMode op = opMode;
        if (op != null) notifyListeners(l -> l.onOpModePostStop(op));
        state = State.STOPPED;
        fire();
    }

    private void checkAutoTimer() {
        if (state == State.RUNNING && autoTimer && !autoStopFired && current != null && current.autonomous()
                && sim.clock.seconds() - startTime >= AUTONOMOUS_SECONDS) {
            autoStopFired = true;
            Thread stopper = new Thread(this::stop, "auto timer");
            stopper.setDaemon(true);
            stopper.start();
        }
    }

    /** Exception summary with the first line of robot code in the stack trace, plus hints. */
    public static String describe(Throwable t) {
        return describe(t, "the robot config");
    }

    /** Unwraps reflection and class-initialization wrappers to the exception the team code threw. */
    private static Throwable rootCause(Throwable t) {
        while ((t instanceof java.lang.reflect.InvocationTargetException || t instanceof ExceptionInInitializerError) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private String describeHere(Throwable t) {
        return describe(t, robot.config.source());
    }

    /** A Driver Station style crash report with hints; configName is the robot config file in use. */
    public static String describe(Throwable t, String configName) {
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(" - ").append(t.getMessage());
        for (StackTraceElement el : t.getStackTrace()) {
            String c = el.getClassName();
            if (!c.startsWith("java.") && !c.startsWith("jdk.") && !c.startsWith("com.qualcomm.")
                    && !c.startsWith("org.firstinspires.ftc.robotcore.") && !c.startsWith("org.ftctestbed.")) {
                sb.append("\n    at ").append(el);
                break;
            }
        }
        String msg = String.valueOf(t.getMessage());
        if (msg.startsWith("Unable to find a hardware device")) {
            sb.append("\nHint: the device name or type doesn't match " + configName + ". "
                    + "Device names must match your robot configuration exactly (they are case-sensitive).");
        }
        if (t instanceof NoClassDefFoundError || t instanceof NoSuchMethodError) {
            sb.append("\nHint: this SDK or Android class isn't simulated yet. Run ./gradlew checkShim to see what's missing.");
        }
        return sb.toString();
    }

    /** OpModeManagerNotifier listeners (Road Runner's FlightRecorder): told about each OpMode, as on the robot. */
    private static void notifyListeners(java.util.function.Consumer<com.qualcomm.robotcore.eventloop.opmode.OpModeManagerNotifier.Notifications> call) {
        for (var l : com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl.simListeners()) {
            try {
                call.accept(l);
            } catch (RuntimeException | LinkageError e) {
                SimHooks.log("W", "OpModeRunner", "OpMode listener failed: " + e);
            }
        }
    }
}
