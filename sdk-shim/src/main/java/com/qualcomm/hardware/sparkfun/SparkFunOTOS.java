package com.qualcomm.hardware.sparkfun;

import com.qualcomm.robotcore.hardware.I2cAddr;
import com.qualcomm.robotcore.hardware.I2cDeviceSynch;
import com.qualcomm.robotcore.hardware.I2cDeviceSynchDevice;
import com.qualcomm.robotcore.hardware.configuration.annotations.DeviceProperties;
import com.qualcomm.robotcore.hardware.configuration.annotations.I2cDeviceType;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.ftctestbed.shim.SimHooks;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * The SparkFun Optical Tracking Odometry Sensor: an optical mouse sensor looking at the tiles
 * plus a gyro, fused on the sensor 416 times a second. Same API and behaviour as the FTC SDK 12.0
 * driver, talking to the sensor over I2C registers; in the simulator the sensor is simulated
 * (optics, gyro, firmware), so every call costs the I2C time it does on a real hub.
 *
 * Units are kept on this side (the sensor works in meters and radians) and go back to inches
 * and degrees each time the driver initializes, i.e. at the start of each OpMode. Every angle
 * that comes out is wrapped to [-180, 180) degrees or [-PI, PI), angular rates included, as
 * in the SDK.
 */
@I2cDeviceType
@DeviceProperties(name = "SparkFun OTOS", xmlTag = "SparkFunOTOS", description = "SparkFun Qwiic Optical Tracking Odometry Sensor")
public class SparkFunOTOS extends I2cDeviceSynchDevice<I2cDeviceSynch> {
    public static final byte DEFAULT_ADDRESS = 0x17;
    public static final double MIN_SCALAR = 0.872;
    public static final double MAX_SCALAR = 1.127;

    protected static final byte REG_PRODUCT_ID = 0x00;
    protected static final byte REG_HW_VERSION = 0x01;
    protected static final byte REG_FW_VERSION = 0x02;
    protected static final byte REG_SCALAR_LINEAR = 0x04;
    protected static final byte REG_SCALAR_ANGULAR = 0x05;
    protected static final byte REG_IMU_CALIB = 0x06;
    protected static final byte REG_RESET = 0x07;
    protected static final byte REG_SIGNAL_PROCESS = 0x0E;
    protected static final byte REG_SELF_TEST = 0x0F;
    protected static final byte REG_OFF_XL = 0x10;
    protected static final byte REG_OFF_XH = 0x11;
    protected static final byte REG_OFF_YL = 0x12;
    protected static final byte REG_OFF_YH = 0x13;
    protected static final byte REG_OFF_HL = 0x14;
    protected static final byte REG_OFF_HH = 0x15;
    protected static final byte REG_STATUS = 0x1F;
    protected static final byte REG_POS_XL = 0x20;
    protected static final byte REG_POS_XH = 0x21;
    protected static final byte REG_POS_YL = 0x22;
    protected static final byte REG_POS_YH = 0x23;
    protected static final byte REG_POS_HL = 0x24;
    protected static final byte REG_POS_HH = 0x25;
    protected static final byte REG_VEL_XL = 0x26;
    protected static final byte REG_VEL_XH = 0x27;
    protected static final byte REG_VEL_YL = 0x28;
    protected static final byte REG_VEL_YH = 0x29;
    protected static final byte REG_VEL_HL = 0x2A;
    protected static final byte REG_VEL_HH = 0x2B;
    protected static final byte REG_ACC_XL = 0x2C;
    protected static final byte REG_ACC_XH = 0x2D;
    protected static final byte REG_ACC_YL = 0x2E;
    protected static final byte REG_ACC_YH = 0x2F;
    protected static final byte REG_ACC_HL = 0x30;
    protected static final byte REG_ACC_HH = 0x31;
    protected static final byte REG_POS_STD_XL = 0x32;
    protected static final byte REG_POS_STD_XH = 0x33;
    protected static final byte REG_POS_STD_YL = 0x34;
    protected static final byte REG_POS_STD_YH = 0x35;
    protected static final byte REG_POS_STD_HL = 0x36;
    protected static final byte REG_POS_STD_HH = 0x37;
    protected static final byte REG_VEL_STD_XL = 0x38;
    protected static final byte REG_VEL_STD_XH = 0x39;
    protected static final byte REG_VEL_STD_YL = 0x3A;
    protected static final byte REG_VEL_STD_YH = 0x3B;
    protected static final byte REG_VEL_STD_HL = 0x3C;
    protected static final byte REG_VEL_STD_HH = 0x3D;
    protected static final byte REG_ACC_STD_XL = 0x3E;
    protected static final byte REG_ACC_STD_XH = 0x3F;
    protected static final byte REG_ACC_STD_YL = 0x40;
    protected static final byte REG_ACC_STD_YH = 0x41;
    protected static final byte REG_ACC_STD_HL = 0x42;
    protected static final byte REG_ACC_STD_HH = 0x43;
    protected static final byte PRODUCT_ID = 0x5F;

    protected static final double RADIAN_TO_DEGREE = 57.29577951308232;
    protected static final double DEGREE_TO_RADIAN = 0.017453292519943295;
    // The sensor's int16 full scales: 10 m, 5 m/s, 16 g, PI rad, 2000 deg/s, 1000 PI rad/s^2.
    protected static final double METER_TO_INT16 = 3276.8;
    protected static final double INT16_TO_METER = 3.0517578125E-4;
    protected static final double MPS_TO_INT16 = 6553.6;
    protected static final double INT16_TO_MPS = 1.52587890625E-4;
    protected static final double MPSS_TO_INT16 = 208.83788041787972;
    protected static final double INT16_TO_MPSS = 0.0047884033203125;
    protected static final double RAD_TO_INT16 = 10430.378350470453;
    protected static final double INT16_TO_RAD = 9.587379924285257E-5;
    protected static final double RPS_TO_INT16 = 938.7340515423407;
    protected static final double INT16_TO_RPS = 0.0010652644360316954;
    protected static final double RPSS_TO_INT16 = 10.430378350470454;
    protected static final double INT16_TO_RPSS = 0.09587379924285257;

    protected DistanceUnit _distanceUnit;
    protected AngleUnit _angularUnit;

    public SparkFunOTOS(I2cDeviceSynch deviceClient) {
        super(deviceClient, true);
        this.deviceClient.setI2cAddress(I2cAddr.create7bit(DEFAULT_ADDRESS));
        super.registerArmingStateCallback(false);
        this.deviceClient.engage();
    }

    @Override
    protected boolean doInitialize() {
        _distanceUnit = DistanceUnit.INCH;
        _angularUnit = AngleUnit.DEGREES;
        return isConnected();
    }

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.SparkFun;
    }

    @Override
    public String getDeviceName() {
        return "SparkFun Qwiic Optical Tracking Odometry Sensor";
    }

    public boolean begin() {
        return isConnected();
    }

    public boolean isConnected() {
        return deviceClient.read8(REG_PRODUCT_ID) == PRODUCT_ID;
    }

    public void getVersionInfo(Version hwVersion, Version fwVersion) {
        byte[] b = deviceClient.read(REG_HW_VERSION, 2);
        hwVersion.set(b[0]);
        fwVersion.set(b[1]);
    }

    /** Runs the sensor's self test (it stops tracking for about 20 ms). */
    public boolean selfTest() {
        SelfTestConfig st = new SelfTestConfig();
        st.set((byte) 1);
        deviceClient.write8(REG_SELF_TEST, st.get());
        for (int i = 0; i < 10; i++) {
            if (!sleep(5)) return false;
            st.set(deviceClient.read8(REG_SELF_TEST));
            if (!st.inProgress) break;
        }
        return st.pass;
    }

    /** 255 samples, waiting until done (about 0.6 s). The robot must be still and flat. */
    public boolean calibrateImu() {
        return calibrateImu(255, true);
    }

    /** Averages numSamples (1-255) gyro and accelerometer samples as their new zero. Tracking pauses meanwhile. */
    public boolean calibrateImu(int numSamples, boolean waitUntilDone) {
        if (numSamples < 1 || numSamples > 255) return false;
        deviceClient.write8(REG_IMU_CALIB, numSamples);
        if (!sleep(3)) return false;
        if (!waitUntilDone) return true;
        for (int i = numSamples; i > 0; i--) {
            if (deviceClient.read8(REG_IMU_CALIB) == 0) return true;
            if (!sleep(3)) return false;
        }
        return false;
    }

    /** Calibration samples still to take (0: done). */
    public int getImuCalibrationProgress() {
        return deviceClient.read8(REG_IMU_CALIB) & 0xFF;
    }

    public DistanceUnit getLinearUnit() {
        return _distanceUnit;
    }

    public void setLinearUnit(DistanceUnit unit) {
        if (unit == _distanceUnit) return;
        _distanceUnit = unit;
    }

    public AngleUnit getAngularUnit() {
        return _angularUnit;
    }

    public void setAngularUnit(AngleUnit unit) {
        if (unit == _angularUnit) return;
        _angularUnit = unit;
    }

    public double getLinearScalar() {
        byte rawScalar = deviceClient.read8(REG_SCALAR_LINEAR);
        return rawScalar * 0.001 + 1.0;
    }

    /** Corrects distance: true / measured, 0.872 to 1.127 in steps of 0.001. */
    public boolean setLinearScalar(double scalar) {
        if (scalar < MIN_SCALAR || scalar > MAX_SCALAR) return false;
        byte rawScalar = (byte) (int) ((scalar - 1.0) * 1000.0 + 0.5);
        deviceClient.write8(REG_SCALAR_LINEAR, rawScalar);
        return true;
    }

    public double getAngularScalar() {
        byte rawScalar = deviceClient.read8(REG_SCALAR_ANGULAR);
        return rawScalar * 0.001 + 1.0;
    }

    /** Corrects turning: true / measured (spin 10 turns and compare), 0.872 to 1.127. */
    public boolean setAngularScalar(double scalar) {
        if (scalar < MIN_SCALAR || scalar > MAX_SCALAR) return false;
        byte rawScalar = (byte) (int) ((scalar - 1.0) * 1000.0 + 0.5);
        deviceClient.write8(REG_SCALAR_ANGULAR, rawScalar);
        return true;
    }

    /** Position, velocity and acceleration back to zero. Offset, scalars and calibration are kept. */
    public void resetTracking() {
        deviceClient.write8(REG_RESET, 0x01);
    }

    public SignalProcessConfig getSignalProcessConfig() {
        return new SignalProcessConfig(deviceClient.read8(REG_SIGNAL_PROCESS));
    }

    public void setSignalProcessConfig(SignalProcessConfig config) {
        deviceClient.write8(REG_SIGNAL_PROCESS, config.get());
    }

    public Status getStatus() {
        return new Status(deviceClient.read8(REG_STATUS));
    }

    public Pose2D getOffset() {
        return readPoseRegs(REG_OFF_XL, INT16_TO_METER, INT16_TO_RAD);
    }

    /** Where the sensor sits on the robot (and how it's turned), so the robot's center is tracked. */
    public void setOffset(Pose2D offset) {
        writePoseRegs(REG_OFF_XL, offset, METER_TO_INT16, RAD_TO_INT16);
    }

    public Pose2D getPosition() {
        return readPoseRegs(REG_POS_XL, INT16_TO_METER, INT16_TO_RAD);
    }

    public void setPosition(Pose2D pose) {
        writePoseRegs(REG_POS_XL, pose, METER_TO_INT16, RAD_TO_INT16);
    }

    /** Field-frame velocity. Note h (turn rate) is wrapped like an angle, as in the SDK. */
    public Pose2D getVelocity() {
        return readPoseRegs(REG_VEL_XL, INT16_TO_MPS, INT16_TO_RPS);
    }

    public Pose2D getAcceleration() {
        return readPoseRegs(REG_ACC_XL, INT16_TO_MPSS, INT16_TO_RPSS);
    }

    public Pose2D getPositionStdDev() {
        return readPoseRegs(REG_POS_STD_XL, INT16_TO_METER, INT16_TO_RAD);
    }

    public Pose2D getVelocityStdDev() {
        return readPoseRegs(REG_VEL_STD_XL, INT16_TO_MPS, INT16_TO_RPS);
    }

    public Pose2D getAccelerationStdDev() {
        return readPoseRegs(REG_ACC_STD_XL, INT16_TO_MPSS, INT16_TO_RPSS);
    }

    /** Position, velocity and acceleration from one read (one consistent sample). */
    public void getPosVelAcc(Pose2D pos, Pose2D vel, Pose2D acc) {
        byte[] raw = deviceClient.read(REG_POS_XL, 18);
        pos.set(regsToPose(Arrays.copyOfRange(raw, 0, 6), INT16_TO_METER, INT16_TO_RAD));
        vel.set(regsToPose(Arrays.copyOfRange(raw, 6, 12), INT16_TO_MPS, INT16_TO_RPS));
        acc.set(regsToPose(Arrays.copyOfRange(raw, 12, 18), INT16_TO_MPSS, INT16_TO_RPSS));
    }

    public void getPosVelAccStdDev(Pose2D pos, Pose2D vel, Pose2D acc) {
        byte[] raw = deviceClient.read(REG_POS_STD_XL, 18);
        pos.set(regsToPose(Arrays.copyOfRange(raw, 0, 6), INT16_TO_METER, INT16_TO_RAD));
        vel.set(regsToPose(Arrays.copyOfRange(raw, 6, 12), INT16_TO_MPS, INT16_TO_RPS));
        acc.set(regsToPose(Arrays.copyOfRange(raw, 12, 18), INT16_TO_MPSS, INT16_TO_RPSS));
    }

    public void getPosVelAccAndStdDev(Pose2D pos, Pose2D vel, Pose2D acc, Pose2D posStdDev, Pose2D velStdDev, Pose2D accStdDev) {
        byte[] raw = deviceClient.read(REG_POS_XL, 36);
        pos.set(regsToPose(Arrays.copyOfRange(raw, 0, 6), INT16_TO_METER, INT16_TO_RAD));
        vel.set(regsToPose(Arrays.copyOfRange(raw, 6, 12), INT16_TO_MPS, INT16_TO_RPS));
        acc.set(regsToPose(Arrays.copyOfRange(raw, 12, 18), INT16_TO_MPSS, INT16_TO_RPSS));
        posStdDev.set(regsToPose(Arrays.copyOfRange(raw, 18, 24), INT16_TO_METER, INT16_TO_RAD));
        velStdDev.set(regsToPose(Arrays.copyOfRange(raw, 24, 30), INT16_TO_MPS, INT16_TO_RPS));
        accStdDev.set(regsToPose(Arrays.copyOfRange(raw, 30, 36), INT16_TO_MPSS, INT16_TO_RPSS));
    }

    protected Pose2D readPoseRegs(byte reg, double rawToXY, double rawToH) {
        return regsToPose(deviceClient.read(reg, 6), rawToXY, rawToH);
    }

    protected void writePoseRegs(byte reg, Pose2D pose, double xyToRaw, double hToRaw) {
        byte[] rawData = new byte[6];
        poseToRegs(rawData, pose, xyToRaw, hToRaw);
        deviceClient.write(reg, rawData);
    }

    protected Pose2D regsToPose(byte[] rawData, double rawToXY, double rawToH) {
        ByteBuffer b = ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN);
        short rawX = b.getShort(0), rawY = b.getShort(2), rawH = b.getShort(4);
        return new Pose2D(_distanceUnit.fromMeters(rawX * rawToXY), _distanceUnit.fromMeters(rawY * rawToXY),
                _angularUnit.fromRadians(rawH * rawToH));
    }

    /** Truncates toward zero and wraps at int16, like the SDK (400 in comes back as -387.4 in). */
    protected void poseToRegs(byte[] rawData, Pose2D pose, double xyToRaw, double hToRaw) {
        short rawX = (short) (int) (_distanceUnit.toMeters(pose.x) * xyToRaw);
        short rawY = (short) (int) (_distanceUnit.toMeters(pose.y) * xyToRaw);
        short rawH = (short) (int) (_angularUnit.toRadians(pose.h) * hToRaw);
        ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN).putShort(0, rawX).putShort(2, rawY).putShort(4, rawH);
    }

    /** Waits on the robot's clock (simulated time in the simulator); false if interrupted. */
    private static boolean sleep(long ms) {
        try {
            SimHooks.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ------------------------------------------------------------------ types

    /** A position, velocity or acceleration (x, y and heading h) in the driver's current units. */
    public static class Pose2D {
        public double x;
        public double y;
        public double h;

        public Pose2D() {
            x = 0.0;
            y = 0.0;
            h = 0.0;
        }

        public Pose2D(double x, double y, double h) {
            this.x = x;
            this.y = y;
            this.h = h;
        }

        public void set(Pose2D pose) {
            this.x = pose.x;
            this.y = pose.y;
            this.h = pose.h;
        }

        @Override
        public String toString() {
            return "(SparkFunOTOS.Pose2D) X: " + x + " Y: " + y + " H: " + h;
        }
    }

    public static class Version {
        public byte minor;
        public byte major;

        public Version() {
            set((byte) 0);
        }

        public Version(byte value) {
            set(value);
        }

        public void set(byte value) {
            minor = (byte) (value & 0x0F);
            major = (byte) ((value >> 4) & 0x0F);
        }

        public byte get() {
            return (byte) ((major << 4) | minor);
        }
    }

    /** enLut: speed-dependent correction; enAcc: use the accelerometer; enRot: field frame; enVar: Kalman filtering. */
    public static class SignalProcessConfig {
        public boolean enLut;
        public boolean enAcc;
        public boolean enRot;
        public boolean enVar;

        public SignalProcessConfig() {
            set((byte) 0);
        }

        public SignalProcessConfig(byte value) {
            set(value);
        }

        public void set(byte value) {
            enLut = (value & 0x01) != 0;
            enAcc = (value & 0x02) != 0;
            enRot = (value & 0x04) != 0;
            enVar = (value & 0x08) != 0;
        }

        public byte get() {
            return (byte) ((enLut ? 0x01 : 0) | (enAcc ? 0x02 : 0) | (enRot ? 0x04 : 0) | (enVar ? 0x08 : 0));
        }
    }

    public static class SelfTestConfig {
        public boolean start;
        public boolean inProgress;
        public boolean pass;
        public boolean fail;

        public SelfTestConfig() {
            set((byte) 0);
        }

        public SelfTestConfig(byte value) {
            set(value);
        }

        public void set(byte value) {
            start = (value & 0x01) != 0;
            inProgress = (value & 0x02) != 0;
            pass = (value & 0x04) != 0;
            fail = (value & 0x08) != 0;
        }

        public byte get() {
            return (byte) ((start ? 0x01 : 0) | (inProgress ? 0x02 : 0) | (pass ? 0x04 : 0) | (fail ? 0x08 : 0));
        }
    }

    /** warnTiltAngle: tilted over 11.5 degrees; warnOpticalTracking: lost the surface; errors stay until power-off. */
    public static class Status {
        public boolean warnTiltAngle;
        public boolean warnOpticalTracking;
        public boolean errorPaa;
        public boolean errorLsm;

        public Status() {
            set((byte) 0);
        }

        public Status(byte value) {
            set(value);
        }

        public void set(byte value) {
            warnTiltAngle = (value & 0x01) != 0;
            warnOpticalTracking = (value & 0x02) != 0;
            errorPaa = (value & 0x40) != 0;
            errorLsm = (value & 0x80) != 0;
        }

        public byte get() {
            return (byte) ((warnTiltAngle ? 0x01 : 0) | (warnOpticalTracking ? 0x02 : 0) | (errorPaa ? 0x40 : 0) | (errorLsm ? 0x80 : 0));
        }
    }
}
