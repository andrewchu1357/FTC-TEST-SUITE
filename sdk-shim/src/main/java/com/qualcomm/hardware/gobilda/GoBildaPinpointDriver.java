package com.qualcomm.hardware.gobilda;

import com.qualcomm.hardware.lynx.LynxI2cDeviceSynch;
import com.qualcomm.robotcore.hardware.I2cAddr;
import com.qualcomm.robotcore.hardware.I2cDeviceSynchDevice;
import com.qualcomm.robotcore.hardware.I2cDeviceSynchSimple;
import com.qualcomm.robotcore.hardware.configuration.annotations.DeviceProperties;
import com.qualcomm.robotcore.hardware.configuration.annotations.I2cDeviceType;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Pose2D;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.firstinspires.ftc.robotcore.external.navigation.UnnormalizedAngleUnit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The goBILDA Pinpoint odometry computer: two dead-wheel pods plus its own IMU, fused on the
 * device about 1500 times a second. Same API and behaviour as the FTC SDK 12.0 driver. It talks
 * to the device over I2C register reads and writes; in the simulator the device on the other
 * end is a simulated Pinpoint (its firmware, pods and gyro), so every call costs the I2C time
 * it does on a real hub and every quirk of the real driver is there.
 *
 * Getters return what the last {@link #update()} read, except those for registers outside the
 * bulk-read scope, which read the device themselves (about 2 ms each).
 */
@I2cDeviceType
@DeviceProperties(name = "goBILDA® Pinpoint Odometry Computer", xmlTag = "goBILDAPinpoint",
        description = "goBILDA® Pinpoint Odometry Computer (IMU Sensor Fusion for 2 Wheel Odometry)")
public class GoBildaPinpointDriver extends I2cDeviceSynchDevice<I2cDeviceSynchSimple> {
    private int deviceID = 0;
    private int deviceVersion = 0;
    private int deviceStatus = 0;
    private int loopTime = 0;
    private int xEncoderValue = 0;
    private int yEncoderValue = 0;
    private float xPosition = 0;
    private float yPosition = 0;
    private float hOrientation = 0;
    private float xVelocity = 0;
    private float yVelocity = 0;
    private float hVelocity = 0;
    private float mmPerTick = 0;
    private float xPodOffset = 0;
    private float yPodOffset = 0;
    private float yawScalar = 0;
    private float quaternionW = 0;
    private float quaternionX = 0;
    private float quaternionY = 0;
    private float quaternionZ = 0;
    private float pitch = 0;
    private float roll = 0;
    private Register[] bulkReadScope = {Register.DEVICE_STATUS, Register.LOOP_TIME, Register.X_ENCODER_VALUE,
            Register.Y_ENCODER_VALUE, Register.X_POSITION, Register.Y_POSITION, Register.H_ORIENTATION,
            Register.X_VELOCITY, Register.Y_VELOCITY, Register.H_VELOCITY};
    private ErrorDetectionType errorDetectionType = ErrorDetectionType.LOCAL_TEST;

    private static final byte DEFAULT_ADDRESS = 0x31;
    private static final float goBILDA_SWINGARM_POD = 13.26291192f; // ticks per mm: 2000 CPR, 48 mm wheel
    private static final float goBILDA_4_BAR_POD = 19.89436789f;    // ticks per mm: 2000 CPR, 32 mm wheel
    private static final int CRC_SIZE = 1;
    private static final byte CRC_INITIAL_VALUE = (byte) 0x90;
    private static final int CRC_POLYNOMIAL_VALUE = 0x31;

    // Bad-read filter limits (LOCAL_TEST): a jump this large between reads can't be real.
    private static final float POSITION_JUMP_MM = 5000;
    private static final float HEADING_JUMP_RAD = 120;
    private static final float VELOCITY_LIMIT_MM_PER_S = 10000;
    private static final float HEADING_VELOCITY_LIMIT_RAD_PER_S = 120;

    public GoBildaPinpointDriver(I2cDeviceSynchSimple deviceClient, boolean deviceClientIsOwned) {
        super(deviceClient, deviceClientIsOwned);
        this.deviceClient.setI2cAddress(I2cAddr.create7bit(DEFAULT_ADDRESS));
        super.registerArmingStateCallback(false);
    }

    @Override
    protected synchronized boolean doInitialize() {
        if (deviceClient instanceof LynxI2cDeviceSynch) {
            ((LynxI2cDeviceSynch) deviceClient).setBusSpeed(LynxI2cDeviceSynch.BusSpeed.FAST_400K);
        }
        return true;
    }

    @Override
    public Manufacturer getManufacturer() {
        return Manufacturer.GoBilda;
    }

    @Override
    public String getDeviceName() {
        return "goBILDA® Pinpoint Odometry Computer";
    }

    // ------------------------------------------------------------------ types

    /** The device's registers, in register-address order. */
    public enum Register {
        DEVICE_ID(1, RegisterType.INT32),
        DEVICE_VERSION(2, RegisterType.INT32),
        DEVICE_STATUS(3, RegisterType.INT32),
        DEVICE_CONTROL(4, RegisterType.INT32),
        LOOP_TIME(5, RegisterType.INT32),
        X_ENCODER_VALUE(6, RegisterType.INT32),
        Y_ENCODER_VALUE(7, RegisterType.INT32),
        X_POSITION(8, RegisterType.FLOAT),
        Y_POSITION(9, RegisterType.FLOAT),
        H_ORIENTATION(10, RegisterType.FLOAT),
        X_VELOCITY(11, RegisterType.FLOAT),
        Y_VELOCITY(12, RegisterType.FLOAT),
        H_VELOCITY(13, RegisterType.FLOAT),
        MM_PER_TICK(14, RegisterType.FLOAT), // holds ticks per mm, despite the name
        X_POD_OFFSET(15, RegisterType.FLOAT),
        Y_POD_OFFSET(16, RegisterType.FLOAT),
        YAW_SCALAR(17, RegisterType.FLOAT),
        BULK_READ(18, RegisterType.BULK),
        QUATERNION_W(19, RegisterType.FLOAT),
        QUATERNION_X(20, RegisterType.FLOAT),
        QUATERNION_Y(21, RegisterType.FLOAT),
        QUATERNION_Z(22, RegisterType.FLOAT),
        PITCH(23, RegisterType.FLOAT),
        ROLL(24, RegisterType.FLOAT),
        SET_BULK_READ(25, RegisterType.INT32);

        private final int bVal;
        private final RegisterType type;

        Register(int bVal, RegisterType type) {
            this.bVal = bVal;
            this.type = type;
        }
    }

    enum RegisterType {
        INT32(4), FLOAT(4), GENERIC(4), BULK(40);

        final int length;

        RegisterType(int length) {
            this.length = length;
        }
    }

    public enum DeviceStatus {
        NOT_READY(0),
        READY(1),
        CALIBRATING(1 << 1),
        FAULT_X_POD_NOT_DETECTED(1 << 2),
        FAULT_Y_POD_NOT_DETECTED(1 << 3),
        FAULT_NO_PODS_DETECTED(1 << 2 | 1 << 3),
        FAULT_IMU_RUNAWAY(1 << 4),
        FAULT_BAD_READ(1 << 5);

        private final int status;

        DeviceStatus(int status) {
            this.status = status;
        }
    }

    public enum EncoderDirection { FORWARD, REVERSED }

    public enum GoBildaOdometryPods { goBILDA_SWINGARM_POD, goBILDA_4_BAR_POD }

    public enum ReadData { ONLY_UPDATE_HEADING }

    /** LOCAL_TEST (default): reject impossible jumps. CRC: check the device's CRC-8 (V2 only). NONE: no checks. */
    public enum ErrorDetectionType { NONE, CRC, LOCAL_TEST }

    private enum DeviceControl {
        RECALIBRATE_IMU(1),
        RESET_POS_AND_IMU(1 << 1),
        SET_Y_ENCODER_REVERSED(1 << 2),
        SET_Y_ENCODER_FORWARD(1 << 3),
        SET_X_ENCODER_REVERSED(1 << 4),
        SET_X_ENCODER_FORWARD(1 << 5);

        final int value;

        DeviceControl(int value) {
            this.value = value;
        }
    }

    // ------------------------------------------------------------------ reading

    /** Reads everything in the bulk-read scope (status, loop time, encoders, position, velocity) in one transaction. */
    public void update() {
        if (deviceVersion == 0) getDeviceVersion();
        if (deviceVersion == 1 || deviceVersion == 2) {
            fixedBulkRead();
        } else if (deviceVersion >= 3) {
            flexBulkRead();
        }
    }

    /** Reads only the heading (4 bytes): the fastest way to keep heading fresh. */
    public void update(ReadData data) {
        if (data != ReadData.ONLY_UPDATE_HEADING) return;
        float h = bytesToFloat(deviceClient.read(Register.H_ORIENTATION.bVal, 4), 0);
        hOrientation = isPositionCorrupt(hOrientation, h, HEADING_JUMP_RAD, false) ? hOrientation : h;
        if (deviceStatus == DeviceStatus.FAULT_BAD_READ.status) deviceStatus = DeviceStatus.READY.status;
    }

    /** V2 firmware: choose which registers update() reads. Getters for the others then read the device themselves. */
    public void setBulkReadScope(Register... registers) {
        if (deviceVersion == 0) deviceVersion = readInt(Register.DEVICE_VERSION);
        if (deviceVersion == 1 || deviceVersion == 2) {
            throw new RuntimeException(".setBulkReadScope is not supported by this device firmware.");
        }
        bulkReadScope = registers.clone();
        Set<Integer> distinct = new LinkedHashSet<>();
        for (Register r : registers) distinct.add(r.bVal);
        byte[] list = new byte[distinct.size()];
        int i = 0;
        for (int r : distinct) list[i++] = (byte) r;
        deviceClient.write(Register.SET_BULK_READ.bVal, list);
    }

    public void setErrorDetectionType(ErrorDetectionType errorDetectionType) {
        this.errorDetectionType = errorDetectionType;
    }

    /** V1 firmware: one fixed 40-byte read of status, loop time, encoders, position and velocity. */
    private void fixedBulkRead() {
        byte[] b = deviceClient.read(Register.BULK_READ.bVal, Register.BULK_READ.type.length);
        int status = bytesToInt(b, 0);
        int loop = bytesToInt(b, 4);
        int encX = bytesToInt(b, 8);
        int encY = bytesToInt(b, 12);
        float px = bytesToFloat(b, 16), py = bytesToFloat(b, 20), ph = bytesToFloat(b, 24);
        float vx = bytesToFloat(b, 28), vy = bytesToFloat(b, 32), vh = bytesToFloat(b, 36);
        deviceStatus = status;
        loopTime = loop;
        xEncoderValue = encX;
        yEncoderValue = encY;
        if (errorDetectionType == ErrorDetectionType.LOCAL_TEST) {
            xPosition = isPositionCorrupt(xPosition, px, POSITION_JUMP_MM, true) ? xPosition : px;
            yPosition = isPositionCorrupt(yPosition, py, POSITION_JUMP_MM, true) ? yPosition : py;
            hOrientation = isPositionCorrupt(hOrientation, ph, HEADING_JUMP_RAD, true) ? hOrientation : ph;
            xVelocity = isVelocityCorrupt(xVelocity, vx, VELOCITY_LIMIT_MM_PER_S, true) ? xVelocity : vx;
            yVelocity = isVelocityCorrupt(yVelocity, vy, VELOCITY_LIMIT_MM_PER_S, true) ? yVelocity : vy;
            hVelocity = isVelocityCorrupt(hVelocity, vh, HEADING_VELOCITY_LIMIT_RAD_PER_S, true) ? hVelocity : vh;
        } else {
            xPosition = px;
            yPosition = py;
            hOrientation = ph;
            xVelocity = vx;
            yVelocity = vy;
            hVelocity = vh;
            if (errorDetectionType == ErrorDetectionType.CRC) {
                throw new RuntimeException("CRC Error Handling Not Supported by this Firmware");
            }
        }
    }

    /** V2 firmware: read the registers in the bulk-read scope, in order, plus a CRC byte in CRC mode. */
    private void flexBulkRead() {
        boolean crc = errorDetectionType == ErrorDetectionType.CRC;
        int length = bulkReadScope.length * 4;
        byte[] b = deviceClient.read(Register.BULK_READ.bVal, length + (crc ? CRC_SIZE : 0));
        if (crc) {
            if (b.length < length + CRC_SIZE || computeCRC8(b, 0, length) != b[length]) {
                deviceStatus = DeviceStatus.FAULT_BAD_READ.status;
                return;
            }
        }
        for (int i = 0; i < bulkReadScope.length; i++) saveData(bulkReadScope[i], b, i * 4);
    }

    private void saveData(Register reg, byte[] b, int offset) {
        boolean test = errorDetectionType == ErrorDetectionType.LOCAL_TEST;
        if (reg.type == RegisterType.INT32) {
            int v = bytesToInt(b, offset);
            switch (reg) {
                case DEVICE_ID: deviceID = v; break;
                case DEVICE_VERSION: deviceVersion = v; break;
                case DEVICE_STATUS: deviceStatus = v; break;
                case LOOP_TIME: loopTime = v; break;
                case X_ENCODER_VALUE: xEncoderValue = v; break;
                case Y_ENCODER_VALUE: yEncoderValue = v; break;
                default: break;
            }
            return;
        }
        float v = bytesToFloat(b, offset);
        switch (reg) {
            case X_POSITION: if (!test || !isPositionCorrupt(xPosition, v, POSITION_JUMP_MM, false)) xPosition = v; break;
            case Y_POSITION: if (!test || !isPositionCorrupt(yPosition, v, POSITION_JUMP_MM, false)) yPosition = v; break;
            case H_ORIENTATION: if (!test || !isPositionCorrupt(hOrientation, v, HEADING_JUMP_RAD, false)) hOrientation = v; break;
            case X_VELOCITY: if (!test || !isVelocityCorrupt(xVelocity, v, VELOCITY_LIMIT_MM_PER_S, false)) xVelocity = v; break;
            case Y_VELOCITY: if (!test || !isVelocityCorrupt(yVelocity, v, VELOCITY_LIMIT_MM_PER_S, false)) yVelocity = v; break;
            case H_VELOCITY: if (!test || !isVelocityCorrupt(hVelocity, v, HEADING_VELOCITY_LIMIT_RAD_PER_S, false)) hVelocity = v; break;
            case MM_PER_TICK: mmPerTick = v; break;
            case X_POD_OFFSET: xPodOffset = v; break;
            case Y_POD_OFFSET: yPodOffset = v; break;
            case YAW_SCALAR: yawScalar = v; break;
            case QUATERNION_W: quaternionW = v; break;
            case QUATERNION_X: quaternionX = v; break;
            case QUATERNION_Y: quaternionY = v; break;
            case QUATERNION_Z: quaternionZ = v; break;
            case PITCH: pitch = v; break;
            case ROLL: roll = v; break;
            default: break;
        }
    }

    /** NaN, or a jump no robot can make between two reads: keep the old value and flag a bad read. */
    private boolean isPositionCorrupt(float oldValue, float newValue, float threshold, boolean bulkUpdate) {
        boolean corrupt = Float.isNaN(newValue) || Math.abs(newValue - oldValue) > threshold || (bulkUpdate && loopTime < 1);
        if (corrupt) deviceStatus = DeviceStatus.FAULT_BAD_READ.status;
        return corrupt;
    }

    private boolean isVelocityCorrupt(float oldValue, float newValue, float threshold, boolean bulkUpdate) {
        boolean corrupt = Float.isNaN(newValue) || Math.abs(newValue) > threshold || (bulkUpdate && loopTime <= 1);
        if (corrupt) deviceStatus = DeviceStatus.FAULT_BAD_READ.status;
        return corrupt;
    }

    private boolean registerNotInBulkRead(Register reg) {
        for (Register r : bulkReadScope) if (r == reg) return false;
        return true;
    }

    /** One register (4 bytes) plus the CRC byte the device sends after it. */
    private void readRegister(Register reg) {
        byte[] b = deviceClient.read(reg.bVal, reg.type.length + CRC_SIZE);
        if (!checkCRC(b, reg.type.length)) deviceStatus = DeviceStatus.FAULT_BAD_READ.status;
        if (errorDetectionType == ErrorDetectionType.CRC && !checkCRC(b, reg.type.length)) return;
        saveData(reg, b, 0);
    }

    private boolean checkCRC(byte[] b, int length) {
        return b.length > length && computeCRC8(b, 0, length) == b[length];
    }

    private static byte computeCRC8(byte[] data, int from, int length) {
        byte crc = CRC_INITIAL_VALUE;
        for (int i = from; i < from + length; i++) {
            crc ^= data[i];
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80) != 0 ? (byte) ((crc << 1) ^ CRC_POLYNOMIAL_VALUE) : (byte) (crc << 1);
            }
        }
        return crc;
    }

    private int readInt(Register reg) {
        return bytesToInt(deviceClient.read(reg.bVal, 4), 0);
    }

    private float readFloat(Register reg) {
        return bytesToFloat(deviceClient.read(reg.bVal, 4), 0);
    }

    private void writeInt(Register reg, int i) {
        deviceClient.write(reg.bVal, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array());
    }

    private void writeFloat(Register reg, float f) {
        deviceClient.write(reg.bVal, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(f).array());
    }

    private static int bytesToInt(byte[] b, int offset) {
        return ByteBuffer.wrap(b, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static float bytesToFloat(byte[] b, int offset) {
        return ByteBuffer.wrap(b, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    // ------------------------------------------------------------------ configuration

    /**
     * Where the pods are relative to the tracking point. xOffset: how far left (+) or right (-)
     * the X (forward) pod is; yOffset: how far forward (+) or back (-) the Y (strafe) pod is.
     */
    public void setOffsets(double xOffset, double yOffset, DistanceUnit distanceUnit) {
        writeFloat(Register.X_POD_OFFSET, (float) distanceUnit.toMm(xOffset));
        writeFloat(Register.Y_POD_OFFSET, (float) distanceUnit.toMm(yOffset));
    }

    /** Re-measures the gyro's zero (about 0.25 s, keep the robot still). Position is kept. */
    public void recalibrateIMU() {
        writeInt(Register.DEVICE_CONTROL, DeviceControl.RECALIBRATE_IMU.value);
    }

    /** Position to (0, 0, 0) and re-measures the gyro's zero (about 0.25 s, keep the robot still). */
    public void resetPosAndIMU() {
        writeInt(Register.DEVICE_CONTROL, DeviceControl.RESET_POS_AND_IMU.value);
    }

    /** X must count up driving forward, Y driving left. Raw encoder counts aren't affected. */
    public void setEncoderDirections(EncoderDirection xEncoder, EncoderDirection yEncoder) {
        if (xEncoder == EncoderDirection.FORWARD) writeInt(Register.DEVICE_CONTROL, DeviceControl.SET_X_ENCODER_FORWARD.value);
        if (xEncoder == EncoderDirection.REVERSED) writeInt(Register.DEVICE_CONTROL, DeviceControl.SET_X_ENCODER_REVERSED.value);
        if (yEncoder == EncoderDirection.FORWARD) writeInt(Register.DEVICE_CONTROL, DeviceControl.SET_Y_ENCODER_FORWARD.value);
        if (yEncoder == EncoderDirection.REVERSED) writeInt(Register.DEVICE_CONTROL, DeviceControl.SET_Y_ENCODER_REVERSED.value);
    }

    public void setEncoderResolution(GoBildaOdometryPods pods) {
        if (pods == GoBildaOdometryPods.goBILDA_SWINGARM_POD) setEncoderResolution(goBILDA_SWINGARM_POD, DistanceUnit.MM);
        if (pods == GoBildaOdometryPods.goBILDA_4_BAR_POD) setEncoderResolution(goBILDA_4_BAR_POD, DistanceUnit.MM);
    }

    /** Encoder ticks per unit of distance (e.g. 13.26291192 per mm for a swingarm pod). */
    public void setEncoderResolution(double ticksPerUnit, DistanceUnit distanceUnit) {
        double resolution = 1.0 / distanceUnit.toMm(1.0 / ticksPerUnit);
        writeFloat(Register.MM_PER_TICK, (float) resolution);
    }

    /** Multiplies the IMU's measured turning. Factory-calibrated per device; back to that value at power-up. */
    public void setYawScalar(double yawScalar) {
        writeFloat(Register.YAW_SCALAR, (float) yawScalar);
    }

    /** Tells the device where the robot is (e.g. a field start position); it tracks from there. */
    public void setPosition(Pose2D pos) {
        setPosX(pos.getX(DistanceUnit.MM), DistanceUnit.MM);
        setPosY(pos.getY(DistanceUnit.MM), DistanceUnit.MM);
        setHeading(pos.getHeading(AngleUnit.RADIANS), AngleUnit.RADIANS);
    }

    public void setPosX(double posX, DistanceUnit distanceUnit) {
        writeFloat(Register.X_POSITION, (float) distanceUnit.toMm(posX));
    }

    public void setPosY(double posY, DistanceUnit distanceUnit) {
        writeFloat(Register.Y_POSITION, (float) distanceUnit.toMm(posY));
    }

    public void setHeading(double heading, AngleUnit angleUnit) {
        writeFloat(Register.H_ORIENTATION, (float) angleUnit.toRadians(heading));
    }

    // ------------------------------------------------------------------ getters

    public int getDeviceID() {
        readRegister(Register.DEVICE_ID);
        return deviceID;
    }

    public int getDeviceVersion() {
        if (registerNotInBulkRead(Register.DEVICE_VERSION)) readRegister(Register.DEVICE_VERSION);
        return deviceVersion;
    }

    public float getYawScalar() {
        if (registerNotInBulkRead(Register.YAW_SCALAR)) readRegister(Register.YAW_SCALAR);
        return yawScalar;
    }

    public DeviceStatus getDeviceStatus() {
        return lookupStatus(deviceStatus);
    }

    private static DeviceStatus lookupStatus(int s) {
        if ((s & DeviceStatus.CALIBRATING.status) != 0) return DeviceStatus.CALIBRATING;
        boolean xPodDetected = (s & DeviceStatus.FAULT_X_POD_NOT_DETECTED.status) == 0;
        boolean yPodDetected = (s & DeviceStatus.FAULT_Y_POD_NOT_DETECTED.status) == 0;
        if (!xPodDetected && !yPodDetected) return DeviceStatus.FAULT_NO_PODS_DETECTED;
        if (!xPodDetected) return DeviceStatus.FAULT_X_POD_NOT_DETECTED;
        if (!yPodDetected) return DeviceStatus.FAULT_Y_POD_NOT_DETECTED;
        if ((s & DeviceStatus.FAULT_IMU_RUNAWAY.status) != 0) return DeviceStatus.FAULT_IMU_RUNAWAY;
        if ((s & DeviceStatus.READY.status) != 0) return DeviceStatus.READY;
        if ((s & DeviceStatus.FAULT_BAD_READ.status) != 0) return DeviceStatus.FAULT_BAD_READ;
        return DeviceStatus.NOT_READY;
    }

    /** Microseconds per fusion loop on the device (about 650). */
    public int getLoopTime() {
        return loopTime;
    }

    /** Fusion loops per second on the device (about 1500). */
    public double getFrequency() {
        return loopTime != 0 ? 1000000.0 / loopTime : 0.0;
    }

    public int getEncoderX() {
        if (registerNotInBulkRead(Register.X_ENCODER_VALUE)) readRegister(Register.X_ENCODER_VALUE);
        return xEncoderValue;
    }

    public int getEncoderY() {
        if (registerNotInBulkRead(Register.Y_ENCODER_VALUE)) readRegister(Register.Y_ENCODER_VALUE);
        return yEncoderValue;
    }

    public double getPosX(DistanceUnit distanceUnit) {
        if (registerNotInBulkRead(Register.X_POSITION)) readRegister(Register.X_POSITION);
        return distanceUnit.fromMm(xPosition);
    }

    public double getPosY(DistanceUnit distanceUnit) {
        if (registerNotInBulkRead(Register.Y_POSITION)) readRegister(Register.Y_POSITION);
        return distanceUnit.fromMm(yPosition);
    }

    /** Heading wrapped to [-180, 180) degrees or [-PI, PI). */
    public double getHeading(AngleUnit angleUnit) {
        if (registerNotInBulkRead(Register.H_ORIENTATION)) readRegister(Register.H_ORIENTATION);
        return angleUnit.fromRadians(hOrientation);
    }

    /** Heading counting whole turns (10 turns counter-clockwise reads 3600 degrees). */
    public double getHeading(UnnormalizedAngleUnit unnormalizedAngleUnit) {
        if (registerNotInBulkRead(Register.H_ORIENTATION)) readRegister(Register.H_ORIENTATION);
        return unnormalizedAngleUnit.fromRadians(hOrientation);
    }

    /** Field-frame velocity, units per second. */
    public double getVelX(DistanceUnit distanceUnit) {
        if (registerNotInBulkRead(Register.X_VELOCITY)) readRegister(Register.X_VELOCITY);
        return distanceUnit.fromMm(xVelocity);
    }

    public double getVelY(DistanceUnit distanceUnit) {
        if (registerNotInBulkRead(Register.Y_VELOCITY)) readRegister(Register.Y_VELOCITY);
        return distanceUnit.fromMm(yVelocity);
    }

    public double getHeadingVelocity(UnnormalizedAngleUnit unnormalizedAngleUnit) {
        if (registerNotInBulkRead(Register.H_VELOCITY)) readRegister(Register.H_VELOCITY);
        return unnormalizedAngleUnit.fromRadians(hVelocity);
    }

    public float getXOffset(DistanceUnit distanceUnit) {
        if (registerNotInBulkRead(Register.X_POD_OFFSET)) readRegister(Register.X_POD_OFFSET);
        return (float) distanceUnit.fromMm(xPodOffset);
    }

    public float getYOffset(DistanceUnit distanceUnit) {
        if (registerNotInBulkRead(Register.Y_POD_OFFSET)) readRegister(Register.Y_POD_OFFSET);
        return (float) distanceUnit.fromMm(yPodOffset);
    }

    /** Position of the tracking point (mm) and heading wrapped to [-PI, PI). */
    public Pose2D getPosition() {
        if (registerNotInBulkRead(Register.X_POSITION)) readRegister(Register.X_POSITION);
        if (registerNotInBulkRead(Register.Y_POSITION)) readRegister(Register.Y_POSITION);
        if (registerNotInBulkRead(Register.H_ORIENTATION)) readRegister(Register.H_ORIENTATION);
        return new Pose2D(DistanceUnit.MM, xPosition, yPosition, AngleUnit.RADIANS, AngleUnit.normalizeRadians(hOrientation));
    }

    /** V2 firmware only: the IMU's orientation. */
    public Quaternion getQuaternion() {
        if (deviceVersion == 0) readInt(Register.DEVICE_VERSION); // as in the SDK, this doesn't cache the version
        if (deviceVersion == 1 || deviceVersion == 2) {
            throw new RuntimeException("Quaternion output is not supported on this device firmware");
        }
        if (registerNotInBulkRead(Register.QUATERNION_W)) readRegister(Register.QUATERNION_W);
        if (registerNotInBulkRead(Register.QUATERNION_X)) readRegister(Register.QUATERNION_X);
        if (registerNotInBulkRead(Register.QUATERNION_Y)) readRegister(Register.QUATERNION_Y);
        if (registerNotInBulkRead(Register.QUATERNION_Z)) readRegister(Register.QUATERNION_Z);
        return new Quaternion(quaternionW, quaternionX, quaternionY, quaternionZ, 0);
    }

    /** V2 firmware only. */
    public double getPitch(AngleUnit angleUnit) {
        if (deviceVersion == 0) readInt(Register.DEVICE_VERSION);
        if (deviceVersion == 1 || deviceVersion == 2) {
            throw new RuntimeException("IMU Pitch output is not supported on this device firmware");
        }
        if (registerNotInBulkRead(Register.PITCH)) pitch = readFloat(Register.PITCH);
        return angleUnit.fromRadians(pitch);
    }

    /** V2 firmware only. */
    public double getRoll(AngleUnit angleUnit) {
        if (deviceVersion == 0) readInt(Register.DEVICE_VERSION);
        if (deviceVersion == 1 || deviceVersion == 2) {
            throw new RuntimeException("IMU Roll output is not supported on this device firmware");
        }
        if (registerNotInBulkRead(Register.ROLL)) roll = readFloat(Register.ROLL);
        return angleUnit.fromRadians(roll);
    }
}
