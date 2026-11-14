package org.ftctestbed.sim.hardware;

import com.qualcomm.hardware.digitalchickenlabs.OctoQuad;

import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.physics.OdometryPodSim;

import java.util.Random;

/**
 * A Digital Chicken Labs OctoQuad FTC Edition MK2 (firmware 3.1), from its datasheet (rev 3.0C)
 * and Localizer Quickstart Guide:
 * - 8 channels counting the dead wheels plugged into them. A channel's velocity is the change in
 *   counts over its sample interval (50 ms unless set), so robot code multiplies it up to counts/s.
 * - Reads cost I2C time. In AUTO caching, reading a channel a second time refreshes the whole
 *   block (one bulk read); in MANUAL only refreshCache() does.
 * - The localizer: the X and Y ports' dead wheels (counts per mm set by the code) and its own IMU,
 *   at 1.92 kHz. +X is the robot's front at reset, +Y its left, counter-clockwise positive. The
 *   point it tracks is where the two wheels' lines cross, moved by the TCP offset (in the robot's
 *   frame). Positions are whole mm (int16), heading in steps of 1/5000 rad, velocities averaged
 *   over the localizer interval (25 ms unless set).
 * - Its IMU over-reads every turn by imuScaleError, which the heading scalar should cancel, and
 *   creeps slowly. resetLocalizerAndCalibrateIMU() warms the IMU up, then measures its zero rate
 *   [ESTIMATE: 0.5 s each]; if the robot turns meanwhile, the heading drifts afterwards.
 * Physics thread: step(). Robot thread: the OctoQuad methods.
 */
public class SimOctoQuad implements OctoQuad {
    static final double WARM_UP_SECONDS = 0.5, CALIBRATION_SECONDS = 0.5;
    static final double LOCALIZER_HZ = 1920;

    public final RobotConfig.OctoQuad def;
    private final OdometryPodSim[] pods = new OdometryPodSim[NUM_ENCODERS];
    private final Runnable i2c;
    private Random random;

    // Channels
    private final boolean[] reversed = new boolean[NUM_ENCODERS];
    private final long[] zero = new long[NUM_ENCODERS];
    private final int[] intervalMs = new int[NUM_ENCODERS];
    private final double[] sinceSample = new double[NUM_ENCODERS];
    private final int[] windowStart = new int[NUM_ENCODERS];
    private final short[] velocity = new short[NUM_ENCODERS];
    private final ChannelPulseWidthParams[] pulseParams = new ChannelPulseWidthParams[NUM_ENCODERS];
    private final boolean[] tracksWrap = new boolean[NUM_ENCODERS];
    private ChannelBankConfig bankConfig = ChannelBankConfig.ALL_QUADRATURE;
    private I2cRecoveryMode recoveryMode = I2cRecoveryMode.NONE;

    // Cache (robot thread)
    private CachingMode cachingMode = CachingMode.AUTO;
    private final EncoderDataBlock cache = new EncoderDataBlock();
    private final boolean[] cacheRead = new boolean[NUM_ENCODERS];
    private boolean cacheValid;

    // Localizer settings: set by code, used from the next reset
    private int portX = 0, portY = 1;
    private float countsPerMmX = 1, countsPerMmY = 1, tcpX, tcpY, headingScalar = 1;
    private int localizerIntervalMs = 25;
    private int runPortX = 0, runPortY = 1;
    private float runCountsPerMmX = 1, runCountsPerMmY = 1, runTcpX, runTcpY, runHeadingScalar = 1;

    // Localizer state (physics thread)
    private LocalizerStatus status = LocalizerStatus.RUNNING;
    private double statusTime;
    private double calibrationSum, calibrationTime, zeroRateError;
    private double heading;              // localizer heading (rad)
    private double px, py;               // where the wheels' lines cross, device frame (mm)
    private int lastX, lastY;
    private double velWindow, velStartX, velStartY, velStartHeading;
    private double velX, velY, velHeading;
    private double sinceLocalizerStep;
    private double uptime;

    /** i2c runs once per I2C transaction (see HubTiming); fieldSize (m) places the tile seams the wheels roll over. */
    public SimOctoQuad(RobotConfig.OctoQuad def, double fieldSize, Runnable i2c) {
        this.def = def;
        this.i2c = i2c;
        this.random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(def.name().hashCode() * 31L + 7));
        for (int i = 0; i < NUM_ENCODERS; i++) {
            RobotConfig.OdometryPod pod = def.pods().get(i);
            if (pod != null) pods[i] = new OdometryPodSim(pod, fieldSize);
        }
        powerCycle();
    }

    /** The dead wheels plugged into it (null where a channel is empty); the robot updates them. */
    public OdometryPodSim[] pods() {
        return pods;
    }

    /** Power on: settings back to defaults, counts and pose zero, IMU already calibrated. */
    public synchronized void powerCycle() {
        random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(def.name().hashCode() * 31L + 7)); // the same noise from power-on
        for (int i = 0; i < NUM_ENCODERS; i++) {
            reversed[i] = false;
            zero[i] = raw(i);
            intervalMs[i] = 50;
            sinceSample[i] = 0;
            windowStart[i] = 0;
            velocity[i] = 0;
            pulseParams[i] = new ChannelPulseWidthParams(1, 1024);
            tracksWrap[i] = false;
        }
        bankConfig = ChannelBankConfig.ALL_QUADRATURE;
        portX = runPortX = 0;
        portY = runPortY = 1;
        countsPerMmX = countsPerMmY = runCountsPerMmX = runCountsPerMmY = 1;
        tcpX = tcpY = runTcpX = runTcpY = 0;
        headingScalar = runHeadingScalar = 1;
        localizerIntervalMs = 25;
        zeroRateError = 0;
        startLocalizer(LocalizerStatus.RUNNING);
        uptime = 0;
        cacheValid = false;
    }

    private long raw(int i) {
        return pods[i] == null ? 0 : Math.round(pods[i].ticks());
    }

    private int count(int i) {
        long c = raw(i) - zero[i];
        return (int) (reversed[i] ? -c : c);
    }

    private void startLocalizer(LocalizerStatus s) {
        status = s;
        statusTime = 0;
        calibrationSum = calibrationTime = 0;
        heading = 0;
        px = -runTcpX;
        py = -runTcpY;
        lastX = count(runPortX);
        lastY = count(runPortY);
        velWindow = 0;
        velStartX = velStartY = velStartHeading = 0;
        velX = velY = velHeading = 0;
        sinceLocalizerStep = 0;
    }

    /** Physics thread: omega is the robot's true turn rate (rad/s). */
    public synchronized void step(double dt, double omega) {
        uptime += dt;
        for (int i = 0; i < NUM_ENCODERS; i++) {
            sinceSample[i] += dt;
            double interval = intervalMs[i] / 1000.0;
            if (sinceSample[i] >= interval - 1e-9) {
                int c = count(i);
                velocity[i] = (short) (c - windowStart[i]);
                windowStart[i] = c;
                sinceSample[i] -= interval;
                if (sinceSample[i] > interval) sinceSample[i] = 0;
            }
        }

        // The gyro: true rate, over-read by its scale error, plus its creep and whatever zero-rate
        // error the last calibration left.
        double measured = omega * (1 + def.imuScaleError()) + def.driftRadPerSec() + zeroRateError;
        statusTime += dt;
        switch (status) {
            case WARMING_UP_IMU -> {
                if (statusTime >= WARM_UP_SECONDS) {
                    status = LocalizerStatus.CALIBRATING_IMU;
                    statusTime = 0;
                }
                return;
            }
            case CALIBRATING_IMU -> {
                calibrationSum += (omega * (1 + def.imuScaleError()) + def.driftRadPerSec()) * dt;
                calibrationTime += dt;
                if (statusTime >= CALIBRATION_SECONDS) {
                    // It takes the average rate it saw as zero: turning meanwhile becomes a drift.
                    zeroRateError = -calibrationSum / calibrationTime;
                    startLocalizer(LocalizerStatus.RUNNING);
                }
                return;
            }
            default -> { }
        }
        if (status != LocalizerStatus.RUNNING) return;

        sinceLocalizerStep += dt;
        double tick = 1 / LOCALIZER_HZ;
        double turned = measured * dt * runHeadingScalar;
        if (sinceLocalizerStep < tick - 1e-12) {
            heading += turned;
            return;
        }
        sinceLocalizerStep = 0;
        int cx = count(runPortX), cy = count(runPortY);
        double dx = (cx - lastX) / runCountsPerMmX, dy = (cy - lastY) / runCountsPerMmY;
        lastX = cx;
        lastY = cy;
        double mid = heading + turned / 2;
        px += dx * Math.cos(mid) - dy * Math.sin(mid);
        py += dx * Math.sin(mid) + dy * Math.cos(mid);
        heading += turned;

        velWindow += dt;
        double[] pose = reportedPose();
        if (velWindow >= localizerIntervalMs / 1000.0 - 1e-9) {
            velX = (pose[0] - velStartX) / velWindow;
            velY = (pose[1] - velStartY) / velWindow;
            velHeading = (heading - velStartHeading) / velWindow;
            velStartX = pose[0];
            velStartY = pose[1];
            velStartHeading = heading;
            velWindow = 0;
        }
    }

    /** The tracking point: where the wheels' lines cross, moved by the TCP offset turned with the robot. */
    private double[] reportedPose() {
        double c = Math.cos(heading), s = Math.sin(heading);
        return new double[]{px + c * runTcpX - s * runTcpY, py + s * runTcpX + c * runTcpY};
    }

    /** What the localizer reports, for the window: x, y (m), heading (rad), status. */
    public synchronized Estimate estimate() {
        double[] p = reportedPose();
        return new Estimate(p[0] / 1000, p[1] / 1000, heading, status);
    }

    public record Estimate(double x, double y, double heading, LocalizerStatus status) {}

    private static short mm(double v) {
        return (short) Math.round(v);
    }

    private synchronized void fillEncoders(EncoderDataBlock out) {
        for (int i = 0; i < NUM_ENCODERS; i++) {
            out.positions[i] = count(i);
            out.velocities[i] = velocity[i];
        }
        out.crcOk = true;
    }

    private synchronized void fillLocalizer(LocalizerDataBlock out) {
        out.localizerStatus = status;
        out.crcOk = true;
        double[] p = reportedPose();
        double h = Math.atan2(Math.sin(heading), Math.cos(heading));
        if (!def.ideal() && def.noiseRad() > 0) h += random.nextGaussian() * def.noiseRad();
        out.heading_rad = (float) (Math.round(h * 5000) / 5000.0);
        out.posX_mm = mm(p[0]);
        out.posY_mm = mm(p[1]);
        out.velX_mmS = mm(velX);
        out.velY_mmS = mm(velY);
        out.velHeading_radS = (float) (Math.round(velHeading * 600) / 600.0);
    }

    // ---- OctoQuad: device ----

    @Override public byte getChipId() { i2c.run(); return OCTOQUAD_CHIP_ID; }
    @Override public FirmwareVersion getFirmwareVersion() { i2c.run(); return new FirmwareVersion(3, 1, 0); }
    @Override public String getFirmwareVersionString() { return getFirmwareVersion().toString(); }
    @Override public int getUptimeMs() { i2c.run(); synchronized (this) { return (int) (uptime * 1000); } }
    @Override public int getIMUDiagParam() { i2c.run(); return 0; }
    @Override public void saveParametersToFlash() { i2c.run(); }

    @Override
    public void resetEverything() {
        i2c.run();
        powerCycle();
    }

    @Override public void setI2cRecoveryMode(I2cRecoveryMode mode) { i2c.run(); synchronized (this) { recoveryMode = mode; } }
    @Override public I2cRecoveryMode getI2cRecoveryMode() { i2c.run(); synchronized (this) { return recoveryMode; } }

    // ---- OctoQuad: channels ----
    // Each call waits its I2C time first and only then takes the lock: the physics thread needs the
    // lock to move time on, so waiting while holding it would stop the clock.

    private static void check(int idx) {
        if (idx < ENCODER_FIRST || idx > ENCODER_LAST) throw new IllegalArgumentException("OctoQuad channel " + idx + " (0-7)");
    }

    @Override
    public void setSingleEncoderDirection(int idx, EncoderDirection dir) {
        check(idx);
        i2c.run();
        synchronized (this) {
            boolean rev = dir == EncoderDirection.REVERSE;
            if (rev != reversed[idx]) {
                // The count keeps its value; it just counts the other way from here.
                int now = count(idx);
                reversed[idx] = rev;
                long r = raw(idx);
                zero[idx] = rev ? r + now : r - now;
            }
        }
    }

    @Override
    public EncoderDirection getSingleEncoderDirection(int idx) {
        check(idx);
        i2c.run();
        synchronized (this) {
            return reversed[idx] ? EncoderDirection.REVERSE : EncoderDirection.FORWARD;
        }
    }

    @Override
    public void setAllEncoderDirections(boolean[] reverse) {
        for (int i = 0; i < NUM_ENCODERS && i < reverse.length; i++) {
            setSingleEncoderDirection(i, reverse[i] ? EncoderDirection.REVERSE : EncoderDirection.FORWARD);
        }
    }

    @Override public void setChannelBankConfig(ChannelBankConfig config) { i2c.run(); synchronized (this) { bankConfig = config; } }
    @Override public ChannelBankConfig getChannelBankConfig() { i2c.run(); synchronized (this) { return bankConfig; } }

    @Override
    public void readAllEncoderData(EncoderDataBlock out) {
        i2c.run();
        fillEncoders(out);
    }

    @Override
    public EncoderDataBlock readAllEncoderData() {
        EncoderDataBlock b = new EncoderDataBlock();
        readAllEncoderData(b);
        return b;
    }

    @Override
    public synchronized void setCachingMode(CachingMode mode) {
        cachingMode = mode;
        cacheValid = false;
    }

    @Override
    public void refreshCache() {
        EncoderDataBlock fresh = readAllEncoderData();
        synchronized (this) {
            fresh.copyTo(cache);
            for (int i = 0; i < NUM_ENCODERS; i++) cacheRead[i] = false;
            cacheValid = true;
        }
    }

    /** The cached value of a channel, refreshing the cache first when it needs it (one bulk read). */
    private void cacheFor(int idx) {
        check(idx);
        boolean refresh;
        synchronized (this) {
            refresh = !cacheValid || (cachingMode == CachingMode.AUTO && cacheRead[idx]);
        }
        if (refresh) refreshCache();
        synchronized (this) {
            cacheRead[idx] = true;
        }
    }

    @Override
    public int readSinglePosition_Caching(int idx) {
        cacheFor(idx);
        synchronized (this) {
            return cache.positions[idx];
        }
    }

    @Override
    public short readSingleVelocity_Caching(int idx) {
        cacheFor(idx);
        synchronized (this) {
            return cache.velocities[idx];
        }
    }

    @Override
    public short readSingleVelocity(int idx) {
        check(idx);
        i2c.run();
        synchronized (this) {
            return velocity[idx];
        }
    }

    @Override
    public int readSinglePosition(int idx) {
        check(idx);
        i2c.run();
        synchronized (this) {
            return count(idx);
        }
    }

    private void resetChannel(int i) {
        zero[i] = raw(i);
        windowStart[i] = 0;
    }

    @Override
    public void resetSinglePosition(int idx) {
        check(idx);
        i2c.run();
        synchronized (this) {
            resetChannel(idx);
        }
    }

    @Override
    public void resetAllPositions() {
        i2c.run();
        synchronized (this) {
            for (int i = 0; i < NUM_ENCODERS; i++) resetChannel(i);
        }
    }

    @Override
    public void resetMultiplePositions(boolean[] resets) {
        i2c.run();
        synchronized (this) {
            for (int i = 0; i < NUM_ENCODERS && i < resets.length; i++) if (resets[i]) resetChannel(i);
        }
    }

    @Override
    public void resetMultiplePositions(int... indices) {
        for (int i : indices) check(i);
        i2c.run();
        synchronized (this) {
            for (int i : indices) resetChannel(i);
        }
    }

    @Override
    public void setSingleVelocitySampleInterval(int idx, int intvlms) {
        check(idx);
        if (intvlms < MIN_VELOCITY_MEASUREMENT_INTERVAL_MS || intvlms > MAX_VELOCITY_MEASUREMENT_INTERVAL_MS) {
            throw new IllegalArgumentException("velocity sample interval " + intvlms + " ms (1-255)");
        }
        i2c.run();
        synchronized (this) {
            intervalMs[idx] = intvlms;
        }
    }

    @Override
    public int getSingleVelocitySampleInterval(int idx) {
        check(idx);
        i2c.run();
        synchronized (this) {
            return intervalMs[idx];
        }
    }

    @Override
    public void setAllVelocitySampleIntervals(int intvlms) {
        for (int i = 0; i < NUM_ENCODERS; i++) setSingleVelocitySampleInterval(i, intvlms);
    }

    @Override
    public void setSingleChannelPulseWidthParams(int idx, int minLengthUs, int maxLengthUs) {
        setSingleChannelPulseWidthParams(idx, new ChannelPulseWidthParams(minLengthUs, maxLengthUs));
    }

    @Override
    public void setSingleChannelPulseWidthParams(int idx, ChannelPulseWidthParams params) {
        check(idx);
        i2c.run();
        synchronized (this) {
            pulseParams[idx] = new ChannelPulseWidthParams(params.min_length_us, params.max_length_us);
        }
    }

    @Override
    public ChannelPulseWidthParams getSingleChannelPulseWidthParams(int idx) {
        check(idx);
        i2c.run();
        synchronized (this) {
            return new ChannelPulseWidthParams(pulseParams[idx].min_length_us, pulseParams[idx].max_length_us);
        }
    }

    @Override
    public void setSingleChannelPulseWidthTracksWrap(int idx, boolean trackWrap) {
        check(idx);
        i2c.run();
        synchronized (this) {
            tracksWrap[idx] = trackWrap;
        }
    }

    @Override
    public boolean getSingleChannelPulseWidthTracksWrap(int idx) {
        check(idx);
        i2c.run();
        synchronized (this) {
            return tracksWrap[idx];
        }
    }

    @Override
    public void setAllChannelsPulseWidthTracksWrap(boolean[] trackWrap) {
        for (int i = 0; i < NUM_ENCODERS && i < trackWrap.length; i++) setSingleChannelPulseWidthTracksWrap(i, trackWrap[i]);
    }

    // ---- OctoQuad: localizer ----

    @Override public void setLocalizerPortX(int port) { check(port); i2c.run(); synchronized (this) { portX = port; } }
    @Override public void setLocalizerPortY(int port) { check(port); i2c.run(); synchronized (this) { portY = port; } }
    @Override public void setLocalizerCountsPerMM_X(float ticksPerMM_x) { i2c.run(); synchronized (this) { countsPerMmX = ticksPerMM_x; } }
    @Override public void setLocalizerCountsPerMM_Y(float ticksPerMM_y) { i2c.run(); synchronized (this) { countsPerMmY = ticksPerMM_y; } }
    @Override public void setLocalizerTcpOffsetMM_X(float tcpOffsetMM_X) { i2c.run(); synchronized (this) { tcpX = tcpOffsetMM_X; } }
    @Override public void setLocalizerTcpOffsetMM_Y(float tcpOffsetMM_Y) { i2c.run(); synchronized (this) { tcpY = tcpOffsetMM_Y; } }
    @Override public void setLocalizerImuHeadingScalar(float scalar) { i2c.run(); synchronized (this) { headingScalar = scalar; } }

    @Override
    public void setLocalizerVelocityIntervalMS(int ms) {
        if (ms < 1 || ms > 255) throw new IllegalArgumentException("localizer velocity interval " + ms + " ms (1-255)");
        i2c.run();
        synchronized (this) {
            localizerIntervalMs = ms;
        }
    }

    @Override
    public void setAllLocalizerParameters(int portX, int portY, float ticksPerMM_x, float ticksPerMM_y,
                                          float tcpOffsetMM_X, float tcpOffsetMM_Y, float headingScalar, int velocityIntervalMs) {
        setLocalizerPortX(portX);
        setLocalizerPortY(portY);
        setLocalizerCountsPerMM_X(ticksPerMM_x);
        setLocalizerCountsPerMM_Y(ticksPerMM_y);
        setLocalizerTcpOffsetMM_X(tcpOffsetMM_X);
        setLocalizerTcpOffsetMM_Y(tcpOffsetMM_Y);
        setLocalizerImuHeadingScalar(headingScalar);
        setLocalizerVelocityIntervalMS(velocityIntervalMs);
    }

    @Override
    public void readLocalizerData(LocalizerDataBlock out) {
        i2c.run();
        fillLocalizer(out);
    }

    @Override
    public LocalizerDataBlock readLocalizerData() {
        LocalizerDataBlock b = new LocalizerDataBlock();
        readLocalizerData(b);
        return b;
    }

    @Override
    public void readLocalizerDataAndAllEncoderData(LocalizerDataBlock localizerOut, EncoderDataBlock encoderOut) {
        i2c.run();
        fillLocalizer(localizerOut);
        fillEncoders(encoderOut);
    }

    @Override
    public void setLocalizerPose(int posX_mm, int posY_mm, float heading_rad) {
        i2c.run();
        synchronized (this) {
            heading = heading_rad;
            double c = Math.cos(heading), s = Math.sin(heading);
            px = posX_mm - (c * runTcpX - s * runTcpY);
            py = posY_mm - (s * runTcpX + c * runTcpY);
        }
    }

    @Override
    public void setLocalizerHeading(float headingRad) {
        i2c.run();
        synchronized (this) {
            double[] p = reportedPose();
            heading = headingRad;
            double c = Math.cos(heading), s = Math.sin(heading);
            px = p[0] - (c * runTcpX - s * runTcpY);
            py = p[1] - (s * runTcpX + c * runTcpY);
        }
    }

    @Override public LocalizerStatus getLocalizerStatus() { i2c.run(); synchronized (this) { return status; } }

    @Override
    public LocalizerYawAxis getLocalizerHeadingAxisChoice() {
        i2c.run();
        synchronized (this) {
            // It picks the axis along gravity while calibrating; mounted flat that's Z.
            return status == LocalizerStatus.WARMING_UP_IMU ? LocalizerYawAxis.UNDECIDED : LocalizerYawAxis.Z;
        }
    }

    @Override
    public void resetLocalizerAndCalibrateIMU() {
        i2c.run();
        synchronized (this) {
            runPortX = portX;
            runPortY = portY;
            runCountsPerMmX = countsPerMmX;
            runCountsPerMmY = countsPerMmY;
            runTcpX = tcpX;
            runTcpY = tcpY;
            runHeadingScalar = headingScalar;
            zeroRateError = 0;
            startLocalizer(LocalizerStatus.WARMING_UP_IMU);
        }
    }

    @Override public Manufacturer getManufacturer() { return Manufacturer.DigitalChickenLabs; }
    @Override public String getDeviceName() { return "OctoQuad FTC Edition MK2"; }
    @Override public String getConnectionInfo() { return "I2C bus " + def.bus(); }
    @Override public int getVersion() { return 3; }
    @Override public void resetDeviceConfigurationForOpMode() {}
    @Override public void close() {}
}
