package org.ftctestbed.sim.hardware;

import com.qualcomm.robotcore.exception.TargetPositionNotSetException;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorImplEx;
import com.qualcomm.robotcore.hardware.MotorControlAlgorithm;
import com.qualcomm.robotcore.hardware.PIDFCoefficients;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;

import org.ftctestbed.sim.config.MotorSpec;

/**
 * One motor port on a simulated hub: what the robot code commanded (written by the OpMode
 * thread), the hub's closed-loop controller, and the encoder/current state (written by the
 * physics thread). Values are in the port's raw frame - DcMotorImpl applies Direction.
 */
public final class MotorPort {
    /** Hub PIDF output is scaled so 32767 = full power, like the REV firmware. */
    private static final double HUB_FULL_SCALE = 32767.0;
    private static final double DEFAULT_POSITION_P = 10.0;

    public final String name;
    public final MotorSpec spec;
    public final String hubName;
    public final int portNumber;

    private volatile MotorConfigurationType sdkType;

    // ---- Commanded by robot code ----
    private volatile DcMotor.RunMode mode = DcMotor.RunMode.RUN_WITHOUT_ENCODER;
    private volatile double power = 0;
    private volatile double velocityTargetTps = Double.NaN;
    private volatile DcMotor.ZeroPowerBehavior zeroPowerBehavior = DcMotor.ZeroPowerBehavior.BRAKE;
    private volatile int targetPosition = 0;
    private volatile boolean targetPositionSet = false;
    private volatile int targetTolerance = DcMotorImplEx.DEFAULT_TARGET_POSITION_TOLERANCE;
    private volatile boolean enabled = true;
    private volatile PIDFCoefficients velocityPidf;
    private volatile PIDFCoefficients positionPidf;
    private volatile double currentAlertAmps = 7.5;

    // ---- Physical state, written by the physics thread ----
    private volatile double shaftTicks = 0;
    private volatile double shaftOmega = 0;
    private volatile double currentAmps = 0;
    private volatile double appliedDuty = 0;
    private volatile boolean energized = true;
    private volatile double encoderOffset = 0;
    private volatile EncoderSource encoderSource;

    // ---- Controller state, physics thread only ----
    private double integral = 0;
    private double lastError = 0;
    private boolean lastErrorValid = false;

    public MotorPort(String name, MotorSpec spec, String hubName, int portNumber) {
        this.name = name;
        this.spec = spec;
        this.hubName = hubName;
        this.portNumber = portNumber;
        this.sdkType = spec.toSdkType();
        resetForOpMode();
    }

    /** Equivalent of the SDK resetting a motor at the start of every OpMode. Encoder counts persist. */
    public void resetForOpMode() {
        mode = DcMotor.RunMode.RUN_WITHOUT_ENCODER;
        power = 0;
        velocityTargetTps = Double.NaN;
        zeroPowerBehavior = DcMotor.ZeroPowerBehavior.BRAKE;
        targetPositionSet = false;
        targetTolerance = DcMotorImplEx.DEFAULT_TARGET_POSITION_TOLERANCE;
        enabled = true;
        velocityPidf = defaultVelocityPidf();
        positionPidf = new PIDFCoefficients(DEFAULT_POSITION_P, 0, 0, 0, MotorControlAlgorithm.PIDF);
    }

    /** Power cycle: encoder back to zero. */
    public void powerCycle() {
        resetForOpMode();
        encoderOffset = rawEncoderTicks();
    }

    private PIDFCoefficients defaultVelocityPidf() {
        double freeTps = spec.freeRpm() * spec.ticksPerRev() / 60.0;
        double f = HUB_FULL_SCALE / freeTps;
        return new PIDFCoefficients(3 * f, 15 * f, 0, f, MotorControlAlgorithm.PIDF);
    }

    public void attachEncoderSource(EncoderSource source) {
        this.encoderSource = source;
    }

    // ---- Robot-code side (called through SimMotorController) ----

    public MotorConfigurationType getSdkType() { return sdkType; }
    public void setSdkType(MotorConfigurationType type) { sdkType = type; }

    public DcMotor.RunMode getMode() { return mode; }

    public synchronized void setMode(DcMotor.RunMode newMode) {
        if (newMode == DcMotor.RunMode.RUN_TO_POSITION && !targetPositionSet) {
            throw new TargetPositionNotSetException();
        }
        if (newMode == DcMotor.RunMode.STOP_AND_RESET_ENCODER) {
            encoderOffset = rawEncoderTicks();
            power = 0;
            velocityTargetTps = Double.NaN;
        }
        if (newMode != mode) resetController();
        mode = newMode;
    }

    public double getPower() { return power; }

    public void setPower(double p) {
        if (mode == DcMotor.RunMode.STOP_AND_RESET_ENCODER) return;
        power = p;
        velocityTargetTps = Double.NaN;
    }

    public void setVelocityTarget(double ticksPerSecond) {
        if (mode == DcMotor.RunMode.STOP_AND_RESET_ENCODER) return;
        velocityTargetTps = ticksPerSecond;
        double max = maxTicksPerSecond();
        power = max > 0 ? Math.max(-1, Math.min(1, ticksPerSecond / max)) : 0;
    }

    public DcMotor.ZeroPowerBehavior getZeroPowerBehavior() { return zeroPowerBehavior; }

    public void setZeroPowerBehavior(DcMotor.ZeroPowerBehavior zpb) {
        if (zpb != DcMotor.ZeroPowerBehavior.UNKNOWN) zeroPowerBehavior = zpb;
    }

    public int getTargetPosition() { return targetPosition; }

    public void setTargetPosition(int position, int tolerance) {
        targetPosition = position;
        targetTolerance = tolerance;
        targetPositionSet = true;
    }

    public void setTargetPosition(int position) {
        setTargetPosition(position, targetTolerance);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean e) { enabled = e; }

    public PIDFCoefficients getPidf(DcMotor.RunMode m) {
        return new PIDFCoefficients(m == DcMotor.RunMode.RUN_TO_POSITION ? positionPidf : velocityPidf);
    }

    public void setPidf(DcMotor.RunMode m, PIDFCoefficients c) {
        if (m == DcMotor.RunMode.RUN_TO_POSITION) positionPidf = new PIDFCoefficients(c);
        else if (m == DcMotor.RunMode.RUN_USING_ENCODER) velocityPidf = new PIDFCoefficients(c);
        else throw new UnsupportedOperationException("PIDF coefficients can only be set for RUN_USING_ENCODER or RUN_TO_POSITION");
    }

    public double getCurrentAlertAmps() { return currentAlertAmps; }
    public void setCurrentAlertAmps(double amps) { currentAlertAmps = amps; }

    public double maxTicksPerSecond() { return sdkType.getAchieveableMaxTicksPerSecond(); }

    // ---- Encoder readings ----

    public double rawEncoderTicks() {
        EncoderSource s = encoderSource;
        return s != null ? s.ticks() : shaftTicks;
    }

    public int livePosition() {
        return (int) Math.round(rawEncoderTicks() - encoderOffset);
    }

    public double liveVelocityTps() {
        EncoderSource s = encoderSource;
        double v = s != null ? s.ticksPerSecond() : shaftOmega * spec.ticksPerRadian();
        return Math.round(v);
    }

    /**
     * Velocity as the hub reports it to robot code: a 16-bit signed number, so anything faster
     * than 32767 ticks/s wraps around to a negative value. Fast dead wheels hit this on real
     * robots (a 2000-tick pod on a 32 mm wheel overflows above 1.7 m/s); it's why Road Runner
     * corrects encoder velocity for overflow. The hub's own velocity control isn't affected.
     */
    public double reportedVelocityTps() {
        return (short) windowVelocity;
    }

    // The hub measures velocity by counting encoder ticks over the last 50 ms (x 20 = ticks per
    // second), so what robot code reads is always a multiple of 20 and about 25 ms old. Road
    // Runner's overflow correction relies on the multiple of 20.
    private static final int WINDOW_STEPS = 25; // 50 ms of 2 ms physics steps
    private final long[] window = new long[WINDOW_STEPS];
    private int windowAt = -1;
    private volatile int windowVelocity;

    private void sampleVelocityWindow() {
        long now = Math.round(rawEncoderTicks());
        if (windowAt < 0) java.util.Arrays.fill(window, now);
        windowAt = (windowAt + 1) % WINDOW_STEPS;
        long then = window[windowAt];
        window[windowAt] = now;
        windowVelocity = (int) ((now - then) * 20);
    }

    public boolean liveIsBusy() {
        return mode == DcMotor.RunMode.RUN_TO_POSITION && enabled
                && Math.abs(targetPosition - livePosition()) > targetTolerance;
    }

    public double getCurrentAmps() { return currentAmps; }

    // ---- Physics side ----

    public double appliedDuty() { return appliedDuty; }

    /** False only when the H-bridge is open (FLOAT at zero power, or motor disabled). */
    public boolean isEnergized() { return energized; }

    public double shaftOmega() { return shaftOmega; }

    /** Total motor shaft rotation in encoder ticks (ignores encoder resets and dead wheels). */
    public double shaftTicks() { return shaftTicks; }

    /** Runs the hub's motor control for one physics step and latches the output duty. */
    public void control(double dt) {
        sampleVelocityWindow();
        double duty = 0;
        boolean active = false;
        if (enabled) {
            switch (mode) {
                case RUN_WITHOUT_ENCODER:
                    duty = power;
                    active = duty != 0;
                    resetController();
                    break;
                case RUN_USING_ENCODER: {
                    double target = !Double.isNaN(velocityTargetTps) ? velocityTargetTps : power * maxTicksPerSecond();
                    if (target != 0) {
                        duty = velocityLoop(target, dt);
                        active = true;
                    } else {
                        resetController();
                    }
                    break;
                }
                case RUN_TO_POSITION: {
                    double maxV = !Double.isNaN(velocityTargetTps) ? Math.abs(velocityTargetTps) : Math.abs(power) * maxTicksPerSecond();
                    if (targetPositionSet && maxV > 0) {
                        double err = targetPosition - livePosition();
                        double vTarget = Math.max(-maxV, Math.min(maxV, positionPidf.p * err));
                        duty = velocityLoop(vTarget, dt);
                        active = true;
                    } else {
                        resetController();
                    }
                    break;
                }
                default:
                    resetController();
            }
        }
        appliedDuty = Math.max(-1, Math.min(1, duty));
        energized = enabled && (active || zeroPowerBehavior == DcMotor.ZeroPowerBehavior.BRAKE);
    }

    private double velocityLoop(double targetTps, double dt) {
        PIDFCoefficients c = velocityPidf;
        double err = targetTps - liveVelocityTps();
        integral += err * dt;
        if (c.i > 0) {
            double limit = HUB_FULL_SCALE / c.i;
            integral = Math.max(-limit, Math.min(limit, integral));
        }
        double deriv = lastErrorValid ? (err - lastError) / dt : 0;
        lastError = err;
        lastErrorValid = true;
        return (c.p * err + c.i * integral + c.d * deriv + c.f * targetTps) / HUB_FULL_SCALE;
    }

    private void resetController() {
        integral = 0;
        lastErrorValid = false;
    }

    /** Called after each physics step with the motor's output shaft speed (rad/s). */
    public void integrate(double omega, double amps, double dt) {
        shaftOmega = omega;
        shaftTicks += omega * spec.ticksPerRadian() * dt;
        currentAmps = amps;
    }
}
