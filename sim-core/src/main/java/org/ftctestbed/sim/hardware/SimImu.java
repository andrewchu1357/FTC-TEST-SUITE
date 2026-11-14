package org.ftctestbed.sim.hardware;

import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.hardware.IMU;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.firstinspires.ftc.robotcore.external.navigation.UnnormalizedAngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;
import org.ftctestbed.shim.Rotations;
import org.ftctestbed.sim.config.RobotConfig;

import java.util.Random;
import java.util.function.LongSupplier;

/**
 * The hub's IMU. The hub is physically mounted as described in robot.jsonc; the robot code
 * tells the SDK how it *thinks* the hub is mounted via IMU.Parameters. If the two disagree,
 * the readings come out wrong in the same way they would on a real robot.
 *
 * Like a real IMU it isn't perfect: the fused heading updates 100 times a second (reads in
 * between return the last update), creeps while the robot sits still (drift), jitters a little
 * (noise), and over-reads every turn by a small fraction (scale error), so heading error grows
 * with how much the robot has turned.
 */
public class SimImu implements IMU {
    private final double[][] physicalHubToRobot;
    private volatile double[][] configuredHubToRobot;
    private final double samplePeriod;   // BHI260AP fuses at 800 Hz, BNO055 at 100 Hz
    private final double driftRadPerSec, movingDriftRadPerSec, movingDriftSign;
    private final double noiseRad;
    private final double scaleError;
    private double sinceSample;
    private Random random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(1234));
    private final LongSupplier clockNanos;
    private final Runnable i2c;

    private volatile double trueHeading = 0;
    private volatile double trueOmega = 0;
    private volatile double drift = 0;
    private volatile double yawOffset = 0;
    private volatile double hubHeadingOffset = 0;
    private volatile double accelRight = 0, accelForward = 0;

    /** i2c runs on every reading (one I2C transaction, see HubTiming). */
    public SimImu(RobotConfig.Imu def, LongSupplier clockNanos, Runnable i2c) {
        this.i2c = i2c;
        // How the chip really sits: a hub by its logo and USB ports, an AndyMark IMU board by its
        // logo and I2C port (its chip is turned on the board, which its orientation class knows).
        com.qualcomm.robotcore.hardware.ImuOrientationOnRobot physical = def.chip().equals("ANDYMARK")
                ? new com.qualcomm.hardware.andymark.AndyMarkIMUOrientationOnRobot(
                        com.qualcomm.hardware.andymark.AndyMarkIMUOrientationOnRobot.LogoFacingDirection.valueOf(def.logo()),
                        com.qualcomm.hardware.andymark.AndyMarkIMUOrientationOnRobot.I2cPortFacingDirection.valueOf(def.usb()))
                : new RevHubOrientationOnRobot(
                        RevHubOrientationOnRobot.LogoFacingDirection.valueOf(def.logo()),
                        RevHubOrientationOnRobot.UsbFacingDirection.valueOf(def.usb()));
        this.physicalHubToRobot = Rotations.fromQuaternion(physical.imuCoordinateSystemOrientationFromPerspectiveOfRobot());
        this.configuredHubToRobot = Rotations.identity();
        this.driftRadPerSec = def.driftRadPerSec();
        this.movingDriftRadPerSec = def.movingDriftRadPerSec();
        this.movingDriftSign = new Random(def.name().hashCode() * 17L + 5).nextBoolean() ? 1 : -1;
        this.samplePeriod = def.chip().equals("BHI260AP") ? 1 / 800.0 : 0.01; // BNO055s (the AndyMark IMU too) at 100 Hz
        this.noiseRad = def.noiseRad();
        this.scaleError = def.scaleError();
        this.clockNanos = clockNanos;
    }

    /** Physics thread. heading is the robot's field heading (CCW+). */
    public void update(double heading, double omega, double dt) {
        // Turning makes it drift more (Bosch's "dynamic heading drift"), in a fixed direction per hub.
        double turning = Math.min(1, Math.abs(omega) / 0.5);
        drift += (driftRadPerSec + movingDriftSign * movingDriftRadPerSec * turning) * dt + scaleError * omega * dt;
        sinceSample += dt;
        if (dt == 0 || sinceSample >= samplePeriod - 1e-9) {
            sinceSample = 0;
            trueHeading = heading;
            trueOmega = omega;
        }
    }

    /** Physics thread: the robot's acceleration (m/s^2, robot frame: x forward, y left). */
    public void updateAcceleration(double forward, double left) {
        accelForward = forward;
        accelRight = -left;
    }

    /** The robot's acceleration in the chip's frame (m/s^2), without gravity. */
    double[] hubLinearAcceleration() {
        return Rotations.multiply(Rotations.transpose(physicalHubToRobot), new double[]{accelRight, accelForward, 0});
    }

    /** Gravity as the accelerometer feels it, in the chip's frame: 9.8 m/s^2 up the chip's Z when it lies flat. */
    double[] hubGravity() {
        return Rotations.multiply(Rotations.transpose(physicalHubToRobot), new double[]{0, 0, 9.80665});
    }

    long nanos() {
        return clockNanos.getAsLong();
    }

    void i2cRead() {
        i2c.run();
    }

    /** Power cycle: the IMU's yaw starts at zero wherever the robot is pointing. */
    public void powerCycle() {
        random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(1234)); // the same noise from power-on, so a replayed match repeats exactly
        sinceSample = 0;
        drift = 0;
        yawOffset = 0;
        yawOffset = yawOf(rawOrientation(false));
    }

    private double[][] rawOrientation(boolean withNoise) {
        double h = trueHeading + drift + (withNoise && noiseRad > 0 ? random.nextGaussian() * noiseRad : 0);
        double[][] hubInWorld = Rotations.multiply(Rotations.axisRotation(2, h), physicalHubToRobot);
        double[][] robotAsComputed = Rotations.multiply(hubInWorld, Rotations.transpose(configuredHubToRobot));
        return Rotations.multiply(Rotations.axisRotation(2, -yawOffset), robotAsComputed);
    }

    private static double yawOf(double[][] m) {
        return Rotations.toEuler(m, AxesReference.INTRINSIC, AxesOrder.ZXY)[0];
    }

    // ---- Raw hub-frame access for the legacy BNO055 interface ----

    private double[][] hubInWorld() {
        double h = trueHeading + drift + (noiseRad > 0 ? random.nextGaussian() * noiseRad : 0) - hubHeadingOffset;
        return Rotations.multiply(Rotations.axisRotation(2, h), physicalHubToRobot);
    }

    void zeroHubHeading() {
        hubHeadingOffset = 0;
        hubHeadingOffset = Rotations.toEuler(hubInWorld(), AxesReference.INTRINSIC, AxesOrder.ZYX)[0];
    }

    Orientation hubOrientation(AxesReference reference, AxesOrder order, AngleUnit angleUnit) {
        i2c.run();
        double[] e = Rotations.toEuler(hubInWorld(), reference, order);
        return new Orientation(reference, order, angleUnit,
                (float) angleUnit.fromRadians(AngleUnit.normalizeRadians(e[0])),
                (float) angleUnit.fromRadians(AngleUnit.normalizeRadians(e[1])),
                (float) angleUnit.fromRadians(AngleUnit.normalizeRadians(e[2])),
                clockNanos.getAsLong());
    }

    AngularVelocity hubAngularVelocity(AngleUnit angleUnit) {
        i2c.run();
        double[] inHub = Rotations.multiply(Rotations.transpose(physicalHubToRobot), new double[]{0, 0, trueOmega});
        UnnormalizedAngleUnit rate = angleUnit.getUnnormalized();
        return new AngularVelocity(rate, (float) rate.fromRadians(inHub[0]),
                (float) rate.fromRadians(inHub[1]), (float) rate.fromRadians(inHub[2]), clockNanos.getAsLong());
    }

    @Override
    public boolean initialize(Parameters parameters) {
        if (parameters == null || parameters.imuOrientationOnRobot == null) {
            throw new IllegalArgumentException("IMU.Parameters must include an ImuOrientationOnRobot");
        }
        configuredHubToRobot = Rotations.fromQuaternion(
                parameters.imuOrientationOnRobot.imuCoordinateSystemOrientationFromPerspectiveOfRobot());
        return true;
    }

    @Override
    public synchronized void resetYaw() {
        i2c.run();
        double[][] m = rawOrientation(false);
        yawOffset += yawOf(m);
    }

    @Override
    public YawPitchRollAngles getRobotYawPitchRollAngles() {
        i2c.run();
        double[] e = Rotations.toEuler(rawOrientation(true), AxesReference.INTRINSIC, AxesOrder.ZXY);
        return new YawPitchRollAngles(AngleUnit.RADIANS, AngleUnit.normalizeRadians(e[0]), e[1], e[2], clockNanos.getAsLong());
    }

    @Override
    public Orientation getRobotOrientation(AxesReference reference, AxesOrder order, AngleUnit angleUnit) {
        i2c.run();
        double[] e = Rotations.toEuler(rawOrientation(true), reference, order);
        return new Orientation(reference, order, angleUnit,
                (float) angleUnit.fromRadians(AngleUnit.normalizeRadians(e[0])),
                (float) angleUnit.fromRadians(AngleUnit.normalizeRadians(e[1])),
                (float) angleUnit.fromRadians(AngleUnit.normalizeRadians(e[2])),
                clockNanos.getAsLong());
    }

    @Override
    public Quaternion getRobotOrientationAsQuaternion() {
        i2c.run();
        return Rotations.toQuaternion(rawOrientation(true), clockNanos.getAsLong());
    }

    @Override
    public AngularVelocity getRobotAngularVelocity(AngleUnit angleUnit) {
        i2c.run();
        double[] inHub = Rotations.multiply(Rotations.transpose(physicalHubToRobot), new double[]{0, 0, trueOmega * (1 + scaleError)});
        double[] reported = Rotations.multiply(configuredHubToRobot, inHub);
        UnnormalizedAngleUnit rate = angleUnit.getUnnormalized();
        return new AngularVelocity(rate,
                (float) rate.fromRadians(reported[0]),
                (float) rate.fromRadians(reported[1]),
                (float) rate.fromRadians(reported[2]),
                clockNanos.getAsLong());
    }

    @Override public Manufacturer getManufacturer() { return Manufacturer.Lynx; }
    @Override public String getDeviceName() { return "Simulated BHI260AP IMU"; }
    @Override public String getConnectionInfo() { return "I2C bus 0"; }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() {}
    @Override public void close() {}
}
