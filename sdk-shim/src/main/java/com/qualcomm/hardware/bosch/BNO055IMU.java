package com.qualcomm.hardware.bosch;

import com.qualcomm.robotcore.hardware.I2cAddr;

import org.firstinspires.ftc.robotcore.external.navigation.Acceleration;
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.MagneticFlux;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Position;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.firstinspires.ftc.robotcore.external.navigation.Temperature;
import org.firstinspires.ftc.robotcore.external.navigation.Velocity;

/**
 * The legacy BNO055 IMU interface (older Control Hubs). Angles are in the hub's own frame;
 * there is no robot-orientation mapping. Heading is zero when initialize() is called.
 */
public interface BNO055IMU {
    enum AngleUnit {
        DEGREES, RADIANS;

        public org.firstinspires.ftc.robotcore.external.navigation.AngleUnit toAngleUnit() {
            return this == DEGREES ? org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.DEGREES
                    : org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.RADIANS;
        }
    }

    enum AccelUnit { METERS_PERSEC_PERSEC, MILLI_EARTH_GRAVITY }

    enum TempUnit {
        CELSIUS, FARENHEIT;

        public org.firstinspires.ftc.robotcore.external.navigation.TempUnit toTempUnit() {
            return this == CELSIUS ? org.firstinspires.ftc.robotcore.external.navigation.TempUnit.CELSIUS
                    : org.firstinspires.ftc.robotcore.external.navigation.TempUnit.FARENHEIT;
        }
    }

    enum SystemStatus {
        UNKNOWN(-1), IDLE(0), SYSTEM_ERROR(1), INITIALIZING_PERIPHERALS(2), SYSTEM_INITIALIZATION(3),
        SELF_TEST(4), RUNNING_FUSION(5), RUNNING_NO_FUSION(6);

        public final byte bVal;

        SystemStatus(int value) {
            bVal = (byte) value;
        }

        public static SystemStatus from(int value) {
            for (SystemStatus s : values()) if (s.bVal == value) return s;
            return UNKNOWN;
        }

        public String toShortString() {
            switch (this) {
                case IDLE: return "idle";
                case SYSTEM_ERROR: return "syserr";
                case INITIALIZING_PERIPHERALS: return "periph";
                case SYSTEM_INITIALIZATION: return "sysinit";
                case SELF_TEST: return "selftest";
                case RUNNING_FUSION: return "fusion";
                case RUNNING_NO_FUSION: return "running";
                default: return "unk";
            }
        }
    }

    enum SystemError {
        UNKNOWN(-1), NO_ERROR(0), PERIPHERAL_INITIALIZATION_ERROR(1), SYSTEM_INITIALIZATION_ERROR(2),
        SELF_TEST_FAILED(3), REGISTER_MAP_OUT_OF_RANGE(4), REGISTER_MAP_ADDRESS_OUT_OF_RANGE(5),
        REGISTER_MAP_WRITE_ERROR(6), LOW_POWER_MODE_NOT_AVAILABLE(7), ACCELEROMETER_POWER_MODE_NOT_AVAILABLE(8),
        FUSION_CONFIGURATION_ERROR(9), SENSOR_CONFIGURATION_ERROR(10);

        public final byte bVal;

        SystemError(int value) {
            bVal = (byte) value;
        }

        public static SystemError from(int value) {
            for (SystemError e : values()) if (e.bVal == value) return e;
            return UNKNOWN;
        }
    }

    /** The CALIB_STAT register: system, gyro, accelerometer and magnetometer, each 0 (not) to 3 (fully calibrated). */
    class CalibrationStatus {
        public final byte calibrationStatus;

        public CalibrationStatus(int calibrationStatus) {
            this.calibrationStatus = (byte) calibrationStatus;
        }

        @Override
        public String toString() {
            int c = calibrationStatus;
            return String.format(java.util.Locale.getDefault(), "s%d", (c >> 6) & 3) + " "
                    + String.format(java.util.Locale.getDefault(), "g%d", (c >> 4) & 3) + " "
                    + String.format(java.util.Locale.getDefault(), "a%d", (c >> 2) & 3) + " "
                    + String.format(java.util.Locale.getDefault(), "m%d", c & 3);
        }
    }

    /** The chip's sensor offsets, saved after calibrating so the next run can start calibrated. */
    class CalibrationData implements Cloneable {
        public short dxAccel, dyAccel, dzAccel;
        public short dxMag, dyMag, dzMag;
        public short dxGyro, dyGyro, dzGyro;
        public short radiusAccel, radiusMag;

        private static final String[] FIELDS = {"dxAccel", "dyAccel", "dzAccel", "dxMag", "dyMag", "dzMag",
                "dxGyro", "dyGyro", "dzGyro", "radiusAccel", "radiusMag"};

        private short[] values() {
            return new short[]{dxAccel, dyAccel, dzAccel, dxMag, dyMag, dzMag, dxGyro, dyGyro, dzGyro, radiusAccel, radiusMag};
        }

        /** As JSON, like the SDK (Gson): {"dxAccel":0,...}. */
        public String serialize() {
            StringBuilder sb = new StringBuilder("{");
            short[] v = values();
            for (int i = 0; i < FIELDS.length; i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(FIELDS[i]).append("\":").append(v[i]);
            }
            return sb.append('}').toString();
        }

        public static CalibrationData deserialize(String json) {
            CalibrationData d = new CalibrationData();
            short[] v = new short[FIELDS.length];
            for (int i = 0; i < FIELDS.length; i++) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + FIELDS[i] + "\"\\s*:\\s*(-?\\d+)").matcher(json);
                if (m.find()) v[i] = Short.parseShort(m.group(1));
            }
            d.dxAccel = v[0]; d.dyAccel = v[1]; d.dzAccel = v[2];
            d.dxMag = v[3]; d.dyMag = v[4]; d.dzMag = v[5];
            d.dxGyro = v[6]; d.dyGyro = v[7]; d.dzGyro = v[8];
            d.radiusAccel = v[9]; d.radiusMag = v[10];
            return d;
        }

        @Override
        public CalibrationData clone() {
            try {
                return (CalibrationData) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Turns accelerometer readings into velocity and position (startAccelerationIntegration). */
    interface AccelerationIntegrator {
        void initialize(Parameters parameters, Position initialPosition, Velocity initialVelocity);

        Position getPosition();

        Velocity getVelocity();

        Acceleration getAcceleration();

        void update(Acceleration linearAcceleration);
    }

    enum SensorMode { CONFIG, ACCONLY, MAGONLY, GYRONLY, ACCMAG, ACCGYRO, MAGGYRO, AMG, IMU, COMPASS, M4G, NDOF_FMC_OFF, NDOF, DISABLED }

    class Parameters implements Cloneable {
        public I2cAddr i2cAddr = I2cAddr.create7bit(0x28);
        public SensorMode mode = SensorMode.IMU;
        public boolean useExternalCrystal = true;
        public AngleUnit angleUnit = AngleUnit.RADIANS;
        public AccelUnit accelUnit = AccelUnit.METERS_PERSEC_PERSEC;
        public TempUnit temperatureUnit = TempUnit.CELSIUS;
        public CalibrationData calibrationData = null;
        public String calibrationDataFile = null;
        public AccelerationIntegrator accelerationIntegrationAlgorithm = null;
        public boolean loggingEnabled = false;
        public String loggingTag = "AdaFruitIMU";

        public Parameters() {}

        @Override
        public Parameters clone() {
            try {
                return (Parameters) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    boolean initialize(Parameters parameters);

    Parameters getParameters();

    void close();

    Orientation getAngularOrientation();

    Orientation getAngularOrientation(AxesReference reference, AxesOrder order,
                                      org.firstinspires.ftc.robotcore.external.navigation.AngleUnit angleUnit);

    AngularVelocity getAngularVelocity();

    boolean isGyroCalibrated();

    boolean isSystemCalibrated();

    boolean isAccelerometerCalibrated();

    boolean isMagnetometerCalibrated();

    Quaternion getQuaternionOrientation();

    Temperature getTemperature();

    MagneticFlux getMagneticFieldStrength();

    /** Gravity plus the robot's own acceleration, as the accelerometer feels it. */
    Acceleration getOverallAcceleration();

    /** The robot's acceleration without gravity. */
    Acceleration getLinearAcceleration();

    /** Which way is down (about 9.8 m/s^2 along the chip's Z when it lies flat). */
    Acceleration getGravity();

    /** The latest acceleration from the integrator (startAccelerationIntegration), else the linear acceleration. */
    Acceleration getAcceleration();

    Position getPosition();

    Velocity getVelocity();

    /** Integrates the linear acceleration every msPollInterval into velocity and position. */
    void startAccelerationIntegration(Position initialPosition, Velocity initialVelocity, int msPollInterval);

    void stopAccelerationIntegration();

    SystemStatus getSystemStatus();

    SystemError getSystemError();

    CalibrationStatus getCalibrationStatus();

    CalibrationData readCalibrationData();

    void writeCalibrationData(CalibrationData data);
}
