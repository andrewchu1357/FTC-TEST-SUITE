package org.ftctestbed.sim.hardware;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorControllerEx;
import com.qualcomm.robotcore.hardware.PIDCoefficients;
import com.qualcomm.robotcore.hardware.PIDFCoefficients;
import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.UnnormalizedAngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;

/**
 * The four motor ports of one simulated hub. Encoder reads honor the hub's bulk caching
 * mode, so code that forgets clearBulkCache() in MANUAL mode sees stale values, as on a robot.
 *
 * Calls take time like the real hub (see HubTiming): each read without bulk caching, each bulk
 * read, and each write is one hub command. Like the SDK's LynxDcMotorController, writing the
 * value the hub already has (the same power again, the same mode, ...) isn't sent.
 */
public class SimMotorController implements DcMotorControllerEx {
    private static final int POSITION = 0, VELOCITY = 1, BUSY = 2;

    private final SimLynxModule hub;
    private final HubTiming timing;
    private final MotorPort[] ports = new MotorPort[4];
    private final double[] lastPower = new double[4];
    private final double[] lastVelocity = new double[4];

    private long cacheGeneration = -1;
    private final int[] cachedPosition = new int[4];
    private final double[] cachedVelocity = new double[4];
    private final boolean[] cachedBusy = new boolean[4];
    private final boolean[][] readSinceRefresh = new boolean[4][3];

    public SimMotorController(SimLynxModule hub, HubTiming timing) {
        this.hub = hub;
        this.timing = timing;
        forgetWrites();
    }

    private void forgetWrites() {
        java.util.Arrays.fill(lastPower, Double.NaN);
        java.util.Arrays.fill(lastVelocity, Double.NaN);
    }

    private void command() {
        timing.command(hub);
    }

    public void setPort(int index, MotorPort port) { ports[index] = port; }

    public MotorPort port(int motor) {
        ThreadFence.check();
        if (motor < 0 || motor >= ports.length || ports[motor] == null) {
            throw new IllegalArgumentException("motor port " + motor + " on " + hub.name() + " is not configured");
        }
        return ports[motor];
    }

    /** Updates the cached values if this read needs a new bulk read, which costs one command. */
    private void refreshIfNeeded(int motor, int field) {
        if (refreshCache(motor, field)) command(); // outside the lock: other threads may read the cache meanwhile
    }

    private synchronized boolean refreshCache(int motor, int field) {
        LynxModule.BulkCachingMode mode = hub.getBulkCachingMode();
        long gen = hub.simGetBulkCacheGeneration();
        boolean refresh = gen != cacheGeneration;
        if (mode == LynxModule.BulkCachingMode.AUTO && readSinceRefresh[motor][field]) refresh = true;
        if (refresh) {
            // One bulk read returns every port's encoder, velocity and busy flag.
            for (int i = 0; i < ports.length; i++) {
                if (ports[i] == null) continue;
                cachedPosition[i] = ports[i].livePosition();
                cachedVelocity[i] = ports[i].reportedVelocityTps();
                cachedBusy[i] = ports[i].liveIsBusy();
                readSinceRefresh[i][POSITION] = readSinceRefresh[i][VELOCITY] = readSinceRefresh[i][BUSY] = false;
            }
            if (mode == LynxModule.BulkCachingMode.AUTO) hub.clearBulkCache();
            cacheGeneration = hub.simGetBulkCacheGeneration();
        }
        readSinceRefresh[motor][field] = true;
        return refresh;
    }

    /**
     * LynxModule.getBulkData(): one bulk read of all four ports, which also refreshes the bulk
     * cache (as on the hub). Costs one command.
     */
    public LynxModule.BulkData bulkRead() {
        LynxModule.BulkData data = snapshot();
        command();
        return data;
    }

    private synchronized LynxModule.BulkData snapshot() {
        int[] pos = new int[4], vel = new int[4];
        boolean[] busy = new boolean[4];
        for (int i = 0; i < ports.length; i++) {
            if (ports[i] == null) continue;
            pos[i] = cachedPosition[i] = ports[i].livePosition();
            cachedVelocity[i] = ports[i].reportedVelocityTps();
            vel[i] = (int) cachedVelocity[i];
            busy[i] = cachedBusy[i] = ports[i].liveIsBusy();
            readSinceRefresh[i][POSITION] = readSinceRefresh[i][VELOCITY] = readSinceRefresh[i][BUSY] = false;
        }
        cacheGeneration = hub.simGetBulkCacheGeneration();
        return new LynxModule.BulkData(pos, vel, busy, new boolean[4], new boolean[8], new double[4], false) {};
    }

    private boolean cached() {
        return hub.getBulkCachingMode() != LynxModule.BulkCachingMode.OFF;
    }

    // ---- DcMotorController ----

    @Override public void setMotorType(int motor, MotorConfigurationType motorType) { port(motor).setSdkType(motorType); }
    @Override public MotorConfigurationType getMotorType(int motor) { return port(motor).getSdkType(); }
    @Override
    public void resetDeviceConfigurationForOpMode(int motor) {
        port(motor).resetForOpMode();
        lastPower[motor] = lastVelocity[motor] = Double.NaN;
    }
    @Override
    public void setMotorMode(int motor, DcMotor.RunMode mode) {
        MotorPort p = port(motor);
        if (mode != p.getMode() || mode == DcMotor.RunMode.STOP_AND_RESET_ENCODER) {
            command();
            lastPower[motor] = lastVelocity[motor] = Double.NaN; // the SDK re-sends power after a mode change
        }
        p.setMode(mode);
    }
    @Override public DcMotor.RunMode getMotorMode(int motor) { return port(motor).getMode(); }
    @Override
    public void setMotorPower(int motor, double power) {
        MotorPort p = port(motor);
        if (power != lastPower[motor]) {
            command();
            lastPower[motor] = power;
            lastVelocity[motor] = Double.NaN;
        }
        p.setPower(power);
    }
    @Override public double getMotorPower(int motor) { return port(motor).getPower(); }

    @Override
    public boolean isBusy(int motor) {
        MotorPort p = port(motor);
        if (!cached()) {
            command();
            return p.liveIsBusy();
        }
        refreshIfNeeded(motor, BUSY);
        return cachedBusy[motor];
    }

    @Override
    public void setMotorZeroPowerBehavior(int motor, DcMotor.ZeroPowerBehavior zpb) {
        MotorPort p = port(motor);
        if (zpb != p.getZeroPowerBehavior()) command();
        p.setZeroPowerBehavior(zpb);
    }
    @Override public DcMotor.ZeroPowerBehavior getMotorZeroPowerBehavior(int motor) { return port(motor).getZeroPowerBehavior(); }

    @Override
    public boolean getMotorPowerFloat(int motor) {
        MotorPort p = port(motor);
        return p.getZeroPowerBehavior() == DcMotor.ZeroPowerBehavior.FLOAT && p.getPower() == 0;
    }

    @Override
    public void setMotorTargetPosition(int motor, int position) {
        MotorPort p = port(motor);
        if (position != p.getTargetPosition()) command();
        p.setTargetPosition(position);
    }
    @Override public int getMotorTargetPosition(int motor) { return port(motor).getTargetPosition(); }

    @Override
    public int getMotorCurrentPosition(int motor) {
        MotorPort p = port(motor);
        if (!cached()) {
            command();
            return p.livePosition();
        }
        refreshIfNeeded(motor, POSITION);
        return cachedPosition[motor];
    }

    // ---- DcMotorControllerEx ----

    @Override public void setMotorEnable(int motor) { setEnabled(motor, true); }
    @Override public void setMotorDisable(int motor) { setEnabled(motor, false); }

    private void setEnabled(int motor, boolean enabled) {
        MotorPort p = port(motor);
        if (enabled != p.isEnabled()) command();
        p.setEnabled(enabled);
    }
    @Override public boolean isMotorEnabled(int motor) { return port(motor).isEnabled(); }
    @Override
    public void setMotorVelocity(int motor, double ticksPerSecond) {
        MotorPort p = port(motor);
        sendVelocity(motor, ticksPerSecond);
        p.setVelocityTarget(ticksPerSecond);
    }

    private void sendVelocity(int motor, double ticksPerSecond) {
        if (ticksPerSecond != lastVelocity[motor]) {
            command();
            lastVelocity[motor] = ticksPerSecond;
            lastPower[motor] = Double.NaN;
        }
    }

    @Override
    public void setMotorVelocity(int motor, double angularRate, AngleUnit unit) {
        MotorPort p = port(motor);
        double revsPerSecond = UnnormalizedAngleUnit.DEGREES.fromUnit(unit.getUnnormalized(), angularRate) / 360.0;
        double tps = revsPerSecond * p.getSdkType().getTicksPerRev();
        sendVelocity(motor, tps);
        p.setVelocityTarget(tps);
    }

    @Override
    public double getMotorVelocity(int motor) {
        MotorPort p = port(motor);
        if (!cached()) {
            command();
            return p.reportedVelocityTps();
        }
        refreshIfNeeded(motor, VELOCITY);
        return cachedVelocity[motor];
    }

    @Override
    public double getMotorVelocity(int motor, AngleUnit unit) {
        double revsPerSecond = getMotorVelocity(motor) / port(motor).getSdkType().getTicksPerRev();
        return unit.getUnnormalized().fromDegrees(revsPerSecond * 360.0);
    }

    @Override @Deprecated
    public void setPIDCoefficients(int motor, DcMotor.RunMode mode, PIDCoefficients c) {
        MotorPort p = port(motor);
        command();
        p.setPidf(mode, new PIDFCoefficients(c));
    }

    @Override
    public void setPIDFCoefficients(int motor, DcMotor.RunMode mode, PIDFCoefficients c) {
        MotorPort p = port(motor);
        command();
        p.setPidf(mode, c);
    }

    @Override @Deprecated
    public PIDCoefficients getPIDCoefficients(int motor, DcMotor.RunMode mode) {
        PIDFCoefficients c = port(motor).getPidf(mode);
        return new PIDCoefficients(c.p, c.i, c.d);
    }

    @Override
    public PIDFCoefficients getPIDFCoefficients(int motor, DcMotor.RunMode mode) { return port(motor).getPidf(mode); }

    @Override
    public void setMotorTargetPosition(int motor, int position, int tolerance) {
        MotorPort p = port(motor);
        if (position != p.getTargetPosition()) command();
        p.setTargetPosition(position, tolerance);
    }

    @Override
    public double getMotorCurrent(int motor, CurrentUnit unit) {
        MotorPort p = port(motor);
        command();
        return unit.convert(p.getCurrentAmps(), CurrentUnit.AMPS);
    }

    @Override public double getMotorCurrentAlert(int motor, CurrentUnit unit) { return unit.convert(port(motor).getCurrentAlertAmps(), CurrentUnit.AMPS); }

    @Override
    public void setMotorCurrentAlert(int motor, double current, CurrentUnit unit) {
        MotorPort p = port(motor);
        command();
        p.setCurrentAlertAmps(CurrentUnit.AMPS.convert(current, unit));
    }

    @Override
    public boolean isMotorOverCurrent(int motor) {
        MotorPort p = port(motor);
        command();
        return p.getCurrentAmps() > p.getCurrentAlertAmps();
    }

    // ---- HardwareDevice ----

    @Override public Manufacturer getManufacturer() { return Manufacturer.Lynx; }
    @Override public String getDeviceName() { return "Simulated REV Hub Motor Controller"; }
    @Override public String getConnectionInfo() { return hub.getConnectionInfo(); }
    @Override public int getVersion() { return 1; }

    @Override
    public void resetDeviceConfigurationForOpMode() {
        for (MotorPort p : ports) if (p != null) p.resetForOpMode();
        forgetWrites();
    }

    @Override public void close() {}
}
