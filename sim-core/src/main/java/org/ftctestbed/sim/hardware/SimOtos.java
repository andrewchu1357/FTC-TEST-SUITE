package org.ftctestbed.sim.hardware;

import org.dyn4j.dynamics.Body;
import org.dyn4j.geometry.Vector2;
import org.ftctestbed.sim.config.RobotConfig;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The firmware of a SparkFun OTOS (optical tracking odometry sensor): an optical "mouse" chip
 * looking at the tiles from 10 mm up plus a gyro, fused about 416 times a second (here every
 * physics step). Robot code talks to it through the SDK's SparkFunOTOS driver, register by
 * register; values are int16s in meters, m/s, m/s^2 and radians, as on the sensor.
 *
 * Like the real sensor:
 * - distances read short until robot code sets the linear scalar (SparkFun: 3-5% out of the
 *   box), and a few tenths of a percent still change with speed afterwards;
 * - it only sees the foam properly at 10 +/- 1 mm: further off it under-reads more, and beyond
 *   +/- 3 mm (or above 2.5 m/s) it loses the surface: position stops while the robot moves and
 *   status warnOpticalTracking is set;
 * - heading is the gyro alone: off by angularScaleError until the angular scalar is set, and
 *   drifting a little; calibrateImu() measures the gyro's zero (moving meanwhile = drift) and
 *   tracking pauses while it runs;
 * - the reported pose is the robot's center only if setOffset() describes where the sensor sits;
 *   position, velocity and acceleration are in the field frame; position wraps at +/-10 m;
 * - offset registers read back as zeros (firmware v1.0 never copies them to the read buffer).
 *
 * Threads: read() and write() on the robot thread; step() on the physics thread. Reads see an
 * immutable register image published after each step; writes are queued for the next step.
 */
public final class SimOtos implements SimI2cDevice.Firmware {
    static final int ADDRESS = 0x17;
    static final double SAMPLE_SECONDS = 1 / 416.0;
    static final double MAX_SPEED = 2.5;             // m/s, PAA5160 datasheet
    static final double SPEED_SCALE_DROP = 0.01;     // [ESTIMATE] 1% short at 1.5 m/s after the lookup table
    static final double HEIGHT_GOOD_MM = 1, HEIGHT_LOST_MM = 3, HEIGHT_WORST_SCALE = 0.15;
    static final double RAW_GYRO_BIAS_SIGMA = Math.toRadians(0.5);
    static final double ANGLE_RANDOM_WALK = Math.toRadians(0.0038); // LSM6DSO, rad/s/sqrt(Hz)
    static final double VELOCITY_TAU = 0.02;          // Kalman smoothing of the optical velocity (s)
    static final int REGISTERS = 0x44;

    public final RobotConfig.Otos def;
    private Random random;
    private final double rawGyroBias, residualDrift;
    private final ConcurrentLinkedQueue<byte[]> writes = new ConcurrentLinkedQueue<>();

    // ---- firmware state (physics thread) ----
    private int linearRaw, angularRaw, signalProcess;
    private double offX, offY, offH;                  // where robot code says the sensor sits (m, rad)
    private double x, y, h;                           // robot pose estimate, field frame
    private double vx, vy, vh, ax, ay;                // field frame
    private double posStd, headingStd;
    private double zeroRate, calibrationSum;
    private int calibrationLeft, calibrationTotal;
    private double sampleClock, selfTestLeft;
    private int selfTestResult;
    private boolean lost;

    /** The pose the sensor reports for the robot, for the simulator's displays: m, m, rad, tracking OK. */
    public record Estimate(double x, double y, double heading, boolean tracking) {}

    private volatile byte[] image = new byte[REGISTERS];
    private volatile Estimate estimate = new Estimate(0, 0, 0, true);

    public SimOtos(RobotConfig.Otos def) {
        this.def = def;
        this.random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(def.name().hashCode() * 131L + 29));
        Random unit = new Random(def.name().hashCode() * 31L + 3);
        rawGyroBias = def.ideal() ? 0 : unit.nextGaussian() * RAW_GYRO_BIAS_SIGMA;
        residualDrift = unit.nextBoolean() ? def.driftRadPerSec() : -def.driftRadPerSec();
        powerCycle();
    }

    @Override public String xmlTag() { return "SparkFunOTOS"; }
    @Override public int address7Bit() { return ADDRESS; }

    /** Power on: nothing is kept (offset, scalars, pose); the 64-sample start-up gyro calibration has run. */
    public synchronized void powerCycle() {
        random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(def.name().hashCode() * 131L + 29)); // the same noise from power-on
        writes.clear();
        linearRaw = 0;
        angularRaw = 0;
        signalProcess = 0x0F;
        offX = offY = offH = 0;
        x = y = h = 0;
        vx = vy = vh = ax = ay = 0;
        posStd = headingStd = 0;
        zeroRate = rawGyroBias - residualDrift;
        calibrationLeft = 0;
        selfTestLeft = 0;
        selfTestResult = 0;
        lost = false;
        publish();
    }

    public Estimate estimate() {
        return estimate;
    }

    // ------------------------------------------------------------------ physics thread

    public synchronized void step(double dt, Body robot, double accelX, double accelY) {
        for (byte[] w; (w = writes.poll()) != null; ) apply(w);
        if (dt <= 0) {
            publish();
            return;
        }
        double theta = robot.getTransform().getRotationAngle();
        double c = Math.cos(theta), s = Math.sin(theta);
        Vector2 v = robot.getLinearVelocity();
        double w = robot.getAngularVelocity();

        // The gyro.
        double measuredRate = w * (1 + def.angularScaleError()) + rawGyroBias
                + (def.ideal() ? 0 : random.nextGaussian() * ANGLE_RANDOM_WALK / Math.sqrt(dt));
        if (selfTestLeft > 0) {
            selfTestLeft -= dt;
            if (selfTestLeft <= 0) selfTestResult = 0x04; // pass
            publish();
            return; // the self test blocks tracking
        }
        if (calibrationLeft > 0) {
            // calibrateImu(): averaging gyro samples at 416 Hz; tracking paused meanwhile.
            sampleClock += dt;
            while (sampleClock >= SAMPLE_SECONDS && calibrationLeft > 0) {
                sampleClock -= SAMPLE_SECONDS;
                calibrationSum += measuredRate;
                calibrationLeft--;
            }
            if (calibrationLeft == 0) zeroRate = calibrationSum / calibrationTotal - residualDrift;
            publish();
            return;
        }
        double rate = (measuredRate - zeroRate) * (1 + angularRaw * 0.001);
        double dh = rate * dt;

        // The optical sensor: the velocity of the floor under it, in its own frame.
        double sx = def.x(), sy = def.y();
        double rx = c * sx - s * sy, ry = s * sx + c * sy;
        double fvx = v.x - w * ry, fvy = v.y + w * rx;         // sensor velocity, field frame
        double sensorAngle = theta + def.mountHeading();
        double cs = Math.cos(sensorAngle), ss = Math.sin(sensorAngle);
        double ox = cs * fvx + ss * fvy, oy = -ss * fvx + cs * fvy;
        double speed = Math.hypot(ox, oy);
        double heightOff = Math.abs(def.heightMm() - 10);
        lost = !def.ideal() && (heightOff > HEIGHT_LOST_MM || speed > MAX_SPEED);
        double scale;
        if (lost) {
            scale = 0;
        } else if (def.ideal()) {
            scale = 1;
        } else {
            scale = (1 + def.linearScaleError()) * (1 - SPEED_SCALE_DROP * (speed / 1.5) * (speed / 1.5));
            if (heightOff > HEIGHT_GOOD_MM) {
                scale *= 1 - HEIGHT_WORST_SCALE * (heightOff - HEIGHT_GOOD_MM) / (HEIGHT_LOST_MM - HEIGHT_GOOD_MM);
            }
        }
        scale *= 1 + linearRaw * 0.001;
        double noise = def.ideal() || lost ? 0 : def.velocityNoise() * Math.sqrt(SAMPLE_SECONDS / VELOCITY_TAU);
        double mx = ox * scale + random.nextGaussian() * noise, my = oy * scale + random.nextGaussian() * noise;

        // Fuse: the sensor moves along its measured velocity, turned into the field by the
        // estimated heading of the sensor (the robot's plus the offset robot code gave).
        double mid = h + dh / 2;
        double ch = Math.cos(mid + offH), sh = Math.sin(mid + offH);
        double svx = ch * mx - sh * my, svy = sh * mx + ch * my;    // sensor velocity, field frame
        // Robot center = sensor - (offset turned by the heading); its velocity drops the lever arm.
        double cm = Math.cos(mid), sm = Math.sin(mid);
        double armX = cm * offX - sm * offY, armY = sm * offX + cm * offY;
        double rvx = svx + rate * armY, rvy = svy - rate * armX;
        x += rvx * dt;
        y += rvy * dt;
        h += dh;
        double a = dt / (VELOCITY_TAU + dt);
        vx += (rvx - vx) * a;
        vy += (rvy - vy) * a;
        vh += (rate - vh) * a;
        double cr = Math.cos(h), sr = Math.sin(h);
        ax += (cr * accelX - sr * accelY - ax) * a;
        ay += (sr * accelX + cr * accelY - ay) * a;
        posStd += Math.hypot(rvx, rvy) * dt * 0.01 + 1e-6;
        headingStd += Math.abs(dh) * 0.001 + 1e-7;
        publish();
    }

    private void apply(byte[] w) {
        int reg = w[0] & 0xFF, n = w.length - 1;
        ByteBuffer b = ByteBuffer.wrap(w, 1, n).slice().order(ByteOrder.LITTLE_ENDIAN);
        switch (reg) {
            case 0x04 -> { if (n >= 1) linearRaw = w[1]; }
            case 0x05 -> { if (n >= 1) angularRaw = w[1]; }
            case 0x06 -> {
                if (n >= 1 && (w[1] & 0xFF) > 0) {
                    calibrationTotal = calibrationLeft = w[1] & 0xFF;
                    calibrationSum = 0;
                    sampleClock = 0;
                }
            }
            case 0x07 -> {
                if (n >= 1 && (w[1] & 1) != 0) {
                    x = y = h = 0;
                    vx = vy = vh = ax = ay = 0;
                    posStd = headingStd = 0;
                }
            }
            case 0x0E -> { if (n >= 1) signalProcess = w[1] & 0x0F; }
            case 0x0F -> {
                if (n >= 1 && (w[1] & 1) != 0) {
                    selfTestLeft = 0.02;
                    selfTestResult = 0x02; // in progress
                }
            }
            case 0x10 -> {
                if (n >= 6) { // only applied when all six bytes arrive
                    offX = b.getShort(0) * 3.0517578125E-4;
                    offY = b.getShort(2) * 3.0517578125E-4;
                    offH = b.getShort(4) * 9.587379924285257E-5;
                }
            }
            case 0x20 -> {
                if (n >= 6) {
                    x = b.getShort(0) * 3.0517578125E-4;
                    y = b.getShort(2) * 3.0517578125E-4;
                    h = b.getShort(4) * 9.587379924285257E-5;
                }
            }
            default -> { }
        }
    }

    private void publish() {
        ByteBuffer b = ByteBuffer.allocate(REGISTERS).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0x00, (byte) 0x5F);                  // product id
        b.put(0x01, (byte) 0x11);                  // hardware v1.1
        b.put(0x02, (byte) 0x10);                  // firmware v1.0
        b.put(0x04, (byte) linearRaw);
        b.put(0x05, (byte) angularRaw);
        b.put(0x06, (byte) calibrationLeft);
        b.put(0x0E, (byte) signalProcess);
        b.put(0x0F, (byte) selfTestResult);
        b.put(0x1F, (byte) (lost ? 0x02 : 0));
        double hn = normalize(h);
        put3(b, 0x20, x * 3276.8, y * 3276.8, hn * 10430.378350470453);
        put3(b, 0x26, vx * 6553.6, vy * 6553.6, vh * 938.7340515423407);
        put3(b, 0x2C, ax * 208.83788041787972, ay * 208.83788041787972, 0);
        put3(b, 0x32, posStd * 3276.8, posStd * 3276.8, headingStd * 10430.378350470453);
        put3(b, 0x38, 0.01 * 6553.6, 0.01 * 6553.6, 0.002 * 938.7340515423407);
        put3(b, 0x3E, 0.03 * 208.83788041787972, 0.03 * 208.83788041787972, 0);
        image = b.array();
        estimate = new Estimate(x, y, h, !lost);
    }

    /** Three int16s: truncated and wrapped like the firmware's casts. */
    private static void put3(ByteBuffer b, int at, double a, double c, double d) {
        b.putShort(at, (short) (int) a);
        b.putShort(at + 2, (short) (int) c);
        b.putShort(at + 4, (short) (int) d);
    }

    private static double normalize(double a) {
        a %= 2 * Math.PI;
        if (a >= Math.PI) a -= 2 * Math.PI;
        if (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    // ------------------------------------------------------------------ robot thread (I2C)

    @Override
    public byte[] read(int register, int count) {
        byte[] img = image;
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) {
            int r = register + i;
            out[i] = r < REGISTERS && (r < 0x10 || r > 0x15) ? img[r] : 0; // offsets read back as zero
        }
        return out;
    }

    @Override
    public void write(int register, byte[] data) {
        byte[] w = new byte[data.length + 1];
        w[0] = (byte) register;
        System.arraycopy(data, 0, w, 1, data.length);
        writes.add(w);
    }
}
