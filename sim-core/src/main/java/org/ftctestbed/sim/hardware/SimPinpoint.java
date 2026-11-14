package org.ftctestbed.sim.hardware;

import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.physics.OdometryPodSim;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The firmware of a goBILDA Pinpoint: what the device on the other end of the I2C bus does. It
 * counts its two pods, integrates its own gyro, and fuses them into a pose, as the real one does
 * about 1500 times a second (here every physics step). Robot code talks to it through the SDK's
 * GoBildaPinpointDriver, register by register.
 *
 * Like the real device:
 * - the pose is in mm and radians, of the tracking point the pod offsets describe, starting at
 *   (0, 0, 0) at power-up or resetPosAndIMU(); robot code must send the pod offsets, encoder
 *   resolution and directions every time (wrong ones give wrong poses, as on the robot);
 * - resetPosAndIMU() and recalibrateIMU() measure the gyro's zero for 0.25 s (status
 *   CALIBRATING, outputs held): if the robot moves meanwhile, the heading drifts afterwards;
 * - the gyro over- or under-reads every turn by yawScaleError after goBILDA's factory yaw
 *   scalar (which getYawScalar() reports, and which comes back at power-up), and drifts slowly;
 * - velocities are in the field frame, mm/s, and the heading counts whole turns.
 *
 * Threads: read() and write() run on the robot thread; step() on the physics thread. Reads see
 * an immutable snapshot published after each step; writes are queued and applied at the next
 * step (a fraction of a millisecond later on the real device), so nothing here ever waits.
 */
public final class SimPinpoint implements SimI2cDevice.Firmware {
    static final int ADDRESS = 0x31;
    static final int DEVICE_ID = 2;
    static final double CALIBRATION_SECONDS = 0.25;
    /** goBILDA: "within 0.1% potential of error" from the factory, before the per-unit yaw scalar. */
    static final double RAW_GYRO_SCALE_SIGMA = 0.001;
    /** The gyro's raw zero-rate offset before calibration (rad/s): what calibrating removes. */
    static final double RAW_GYRO_BIAS_SIGMA = Math.toRadians(0.3);
    /** Fusion loop time, microseconds (goBILDA: about 1500 Hz; 500-1100 us is healthy). */
    static final int LOOP_MICROS = 667;
    /** Velocity smoothing time constant (s). [ESTIMATE] goBILDA doesn't document its filter. */
    static final double VELOCITY_TAU = 0.006;
    /** Gyro white noise, rad/s/sqrt(Hz): LSM6DSO-class, 0.0038 deg/s/sqrt(Hz) (heading wanders ~0.05 deg per match). */
    static final double ANGLE_RANDOM_WALK = Math.toRadians(0.0038);
    static final float DEFAULT_TICKS_PER_MM = 19.89436789f; // a 4-bar pod
    static final byte[] DEFAULT_BULK = {3, 5, 6, 7, 8, 9, 10, 11, 12, 13};

    public final RobotConfig.Pinpoint def;
    public final OdometryPodSim xPod, yPod;
    private final int version;
    private Random random;
    private final double rawGyroScale;   // the sensor's own sensitivity error
    private final double rawGyroBias;    // its zero-rate offset (rad/s)
    private final double residualDrift;  // what calibration leaves (rad/s), signed
    private final float factoryYawScalar;
    private final ConcurrentLinkedQueue<byte[]> writes = new ConcurrentLinkedQueue<>();

    // ---- firmware state (physics thread) ----
    private float ticksPerMm, xOffset, yOffset, yawScalar;
    private boolean xReversed, yReversed;
    private byte[] bulk;
    private double x, y, h;              // mm, mm, rad (unnormalized)
    private double vx, vy, vh;           // mm/s (field frame), rad/s
    private int lastEncX, lastEncY;
    private boolean haveEnc;
    private double calibrationLeft, calibrationSum, calibrationTime, zeroRate;
    private double time;

    /** What robot code can read, published after every step. */
    private record Registers(int status, int loopTime, int encX, int encY, float x, float y, float h,
                             float vx, float vy, float vh, float ticksPerMm, float xOffset, float yOffset,
                             float yawScalar, float qw, float qx, float qy, float qz, float pitch, float roll, byte[] bulk) {}

    /** The pose the device reports, for the simulator's displays: mm, mm, rad (unwrapped), status. */
    public record Estimate(double xMm, double yMm, double heading, boolean calibrating) {}

    private volatile Registers published;
    private volatile Estimate estimate = new Estimate(0, 0, 0, false);

    public SimPinpoint(RobotConfig.Pinpoint def, OdometryPodSim xPod, OdometryPodSim yPod) {
        this.def = def;
        this.xPod = xPod;
        this.yPod = yPod;
        this.version = def.firmware();
        this.random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(def.name().hashCode() * 131L + 17));
        Random unit = new Random(def.name().hashCode() * 31L + 1);
        rawGyroScale = def.ideal() ? 0 : unit.nextGaussian() * RAW_GYRO_SCALE_SIGMA;
        rawGyroBias = def.ideal() ? 0 : unit.nextGaussian() * RAW_GYRO_BIAS_SIGMA;
        residualDrift = unit.nextBoolean() ? def.driftRadPerSec() : -def.driftRadPerSec();
        // The factory scalar cancels the raw error, leaving yawScaleError.
        factoryYawScalar = (float) ((1 + def.yawScaleError()) / (1 + rawGyroScale));
        powerCycle();
    }

    @Override public String xmlTag() { return "goBILDAPinpoint"; }
    @Override public int address7Bit() { return ADDRESS; }

    /** Power on: settings back to defaults, pose zero. It has been on long enough to have calibrated. */
    public synchronized void powerCycle() {
        random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(def.name().hashCode() * 131L + 17)); // the same noise from power-on
        writes.clear();
        ticksPerMm = DEFAULT_TICKS_PER_MM;
        xOffset = 0;
        yOffset = 0;
        yawScalar = factoryYawScalar;
        xReversed = false;
        yReversed = false;
        bulk = DEFAULT_BULK.clone();
        x = y = h = 0;
        vx = vy = vh = 0;
        haveEnc = false;
        calibrationLeft = 0;
        zeroRate = rawGyroBias - residualDrift;
        publish(0, 0);
    }

    public Estimate estimate() {
        return estimate;
    }

    // ------------------------------------------------------------------ physics thread

    /** After the pods have been updated. omega: the robot's true turn rate (rad/s, CCW+). */
    public synchronized void step(double dt, double omega) {
        time += dt;
        for (byte[] w; (w = writes.poll()) != null; ) apply(w);

        int encX = (int) Math.round(xPod.ticks()), encY = (int) Math.round(yPod.ticks());
        int dX = haveEnc ? encX - lastEncX : 0, dY = haveEnc ? encY - lastEncY : 0;
        lastEncX = encX;
        lastEncY = encY;
        haveEnc = true;

        double measured = omega * (1 + rawGyroScale) + rawGyroBias
                + (def.ideal() || dt <= 0 ? 0 : random.nextGaussian() * ANGLE_RANDOM_WALK / Math.sqrt(dt));
        if (calibrationLeft > 0) {
            // Measuring the zero: outputs held. Whatever the robot does meanwhile becomes drift.
            calibrationSum += measured * dt;
            calibrationTime += dt;
            calibrationLeft -= dt;
            if (calibrationLeft <= 0) zeroRate = calibrationSum / calibrationTime - residualDrift;
            publish(dX, dY);
            return;
        }
        double rate = (measured - zeroRate) * yawScalar;
        double dTheta = rate * dt;
        double mmX = (xReversed ? -dX : dX) / ticksPerMm, mmY = (yReversed ? -dY : dY) / ticksPerMm;
        // The X pod sits xOffset to the side of the tracking point and sees its turning as
        // sideways-to-it motion; likewise the Y pod, yOffset ahead.
        double fx = mmX + dTheta * xOffset, fy = mmY - dTheta * yOffset;
        double mid = h + dTheta / 2, c = Math.cos(mid), s = Math.sin(mid);
        double gx = c * fx - s * fy, gy = s * fx + c * fy;
        x += gx;
        y += gy;
        h += dTheta;
        if (dt > 0) {
            double a = dt / (VELOCITY_TAU + dt);
            vx += (gx / dt - vx) * a;
            vy += (gy / dt - vy) * a;
            vh += (rate - vh) * a;
        }
        publish(dX, dY);
    }

    private void apply(byte[] w) {
        int reg = w[0];
        ByteBuffer b = ByteBuffer.wrap(w, 1, w.length - 1).order(ByteOrder.LITTLE_ENDIAN);
        int n = w.length - 1;
        switch (reg) {
            case 4 -> {
                if (n < 4) return;
                int flags = b.getInt();
                if ((flags & 1 << 1) != 0) { // reset position and IMU
                    x = y = h = 0;
                    vx = vy = vh = 0;
                    startCalibration();
                } else if ((flags & 1) != 0) {
                    startCalibration();
                }
                if ((flags & 1 << 2) != 0) yReversed = true;
                if ((flags & 1 << 3) != 0) yReversed = false;
                if ((flags & 1 << 4) != 0) xReversed = true;
                if ((flags & 1 << 5) != 0) xReversed = false;
            }
            case 8 -> { if (n >= 4) x = b.getFloat(); }
            case 9 -> { if (n >= 4) y = b.getFloat(); }
            case 10 -> { if (n >= 4) h = b.getFloat(); }
            case 14 -> { if (n >= 4) ticksPerMm = positiveOr(b.getFloat(), ticksPerMm); }
            case 15 -> { if (n >= 4) xOffset = b.getFloat(); }
            case 16 -> { if (n >= 4) yOffset = b.getFloat(); }
            case 17 -> { if (n >= 4) yawScalar = b.getFloat(); }
            case 25 -> { if (version >= 3 && n > 0) bulk = java.util.Arrays.copyOfRange(w, 1, w.length); }
            default -> { }
        }
    }

    private static float positiveOr(float v, float fallback) {
        return v > 0 && Float.isFinite(v) ? v : fallback;
    }

    private void startCalibration() {
        calibrationLeft = CALIBRATION_SECONDS;
        calibrationSum = 0;
        calibrationTime = 0;
    }

    private void publish(int dX, int dY) {
        boolean calibrating = calibrationLeft > 0;
        int loop = LOOP_MICROS + (def.ideal() ? 0 : random.nextInt(41) - 20);
        float hf = (float) (h + (def.ideal() || calibrating ? 0 : random.nextGaussian() * def.noiseRad()));
        double half = h / 2;
        published = new Registers(calibrating ? 2 : 1, loop, lastEncX, lastEncY, (float) x, (float) y, hf,
                (float) vx, (float) vy, (float) vh, ticksPerMm, xOffset, yOffset, yawScalar,
                (float) Math.cos(half), 0f, 0f, (float) Math.sin(half), 0f, 0f, bulk);
        estimate = new Estimate(x, y, h, calibrating);
    }

    // ------------------------------------------------------------------ robot thread (I2C)

    @Override
    public byte[] read(int register, int count) {
        Registers r = published;
        byte[] data;
        if (register == 18) {
            data = version >= 3 ? withCrc(bulkBytes(r, r.bulk())) : bulkBytes(r, DEFAULT_BULK);
        } else {
            ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            putRegister(b, r, register);
            data = withCrc(b.array());
        }
        return java.util.Arrays.copyOf(data, count);
    }

    @Override
    public void write(int register, byte[] data) {
        byte[] w = new byte[data.length + 1];
        w[0] = (byte) register;
        System.arraycopy(data, 0, w, 1, data.length);
        writes.add(w);
    }

    private byte[] bulkBytes(Registers r, byte[] list) {
        ByteBuffer b = ByteBuffer.allocate(list.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (byte reg : list) putRegister(b, r, reg);
        return b.array();
    }

    private void putRegister(ByteBuffer b, Registers r, int reg) {
        boolean v2 = version >= 3;
        switch (reg) {
            case 1 -> b.putInt(DEVICE_ID);
            case 2 -> b.putInt(version);
            case 3 -> b.putInt(r.status());
            case 5 -> b.putInt(r.loopTime());
            case 6 -> b.putInt(r.encX());
            case 7 -> b.putInt(r.encY());
            case 8 -> b.putFloat(r.x());
            case 9 -> b.putFloat(r.y());
            case 10 -> b.putFloat(r.h());
            case 11 -> b.putFloat(r.vx());
            case 12 -> b.putFloat(r.vy());
            case 13 -> b.putFloat(r.vh());
            case 14 -> b.putFloat(r.ticksPerMm());
            case 15 -> b.putFloat(r.xOffset());
            case 16 -> b.putFloat(r.yOffset());
            case 17 -> b.putFloat(r.yawScalar());
            case 19 -> b.putFloat(v2 ? r.qw() : 0);
            case 20 -> b.putFloat(v2 ? r.qx() : 0);
            case 21 -> b.putFloat(v2 ? r.qy() : 0);
            case 22 -> b.putFloat(v2 ? r.qz() : 0);
            case 23 -> b.putFloat(v2 ? r.pitch() : 0);
            case 24 -> b.putFloat(v2 ? r.roll() : 0);
            default -> b.putInt(0); // write-only or unused
        }
    }

    /** Data plus its CRC-8 (polynomial 0x31, initial 0x90), as the Pinpoint V2 sends it. */
    private static byte[] withCrc(byte[] data) {
        byte[] out = java.util.Arrays.copyOf(data, data.length + 1);
        byte crc = (byte) 0x90;
        for (byte d : data) {
            crc ^= d;
            for (int bit = 0; bit < 8; bit++) crc = (crc & 0x80) != 0 ? (byte) ((crc << 1) ^ 0x31) : (byte) (crc << 1);
        }
        out[data.length] = crc;
        return out;
    }
}
