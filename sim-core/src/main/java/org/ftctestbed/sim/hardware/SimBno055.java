package org.ftctestbed.sim.hardware;

import com.qualcomm.hardware.bosch.BNO055IMU;
import com.qualcomm.hardware.bosch.NaiveAccelerationIntegrator;
import com.qualcomm.robotcore.hardware.HardwareDevice;

import org.firstinspires.ftc.robotcore.external.navigation.Acceleration;
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.MagneticFlux;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Position;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.firstinspires.ftc.robotcore.external.navigation.Temperature;
import org.firstinspires.ftc.robotcore.external.navigation.Velocity;
import org.ftctestbed.shim.Rotations;

/**
 * The same physical IMU seen through the legacy BNO055IMU interface (e.g. FTCLib's RevIMU).
 * Angles and accelerations are in the chip's own frame. Acceleration integration runs on the
 * simulated clock: every poll interval the integrator gets the linear acceleration, as the SDK's
 * polling thread does.
 */
public class SimBno055 implements BNO055IMU, HardwareDevice {
    private final SimImu imu;
    private volatile Parameters parameters = new Parameters();
    private volatile AccelerationIntegrator integrator;
    private double pollInterval, sincePoll;
    private volatile CalibrationData calibrationData = new CalibrationData();

    public SimBno055(SimImu imu) {
        this.imu = imu;
    }

    @Override
    public boolean initialize(Parameters parameters) {
        this.parameters = parameters.clone();
        if (parameters.calibrationData != null) calibrationData = parameters.calibrationData.clone();
        imu.zeroHubHeading();
        return true;
    }

    @Override public Parameters getParameters() { return parameters; }

    @Override
    public Orientation getAngularOrientation() {
        return getAngularOrientation(AxesReference.INTRINSIC, AxesOrder.ZYX, parameters.angleUnit.toAngleUnit());
    }

    @Override
    public Orientation getAngularOrientation(AxesReference reference, AxesOrder order,
                                             org.firstinspires.ftc.robotcore.external.navigation.AngleUnit angleUnit) {
        return imu.hubOrientation(reference, order, angleUnit);
    }

    @Override
    public AngularVelocity getAngularVelocity() {
        return imu.hubAngularVelocity(parameters.angleUnit.toAngleUnit());
    }

    @Override
    public Quaternion getQuaternionOrientation() {
        Orientation o = imu.hubOrientation(AxesReference.INTRINSIC, AxesOrder.ZYX,
                org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.RADIANS);
        return Rotations.toQuaternion(Rotations.fromEuler(AxesReference.INTRINSIC, AxesOrder.ZYX,
                o.firstAngle, o.secondAngle, o.thirdAngle), imu.nanos());
    }

    private Acceleration acceleration(double[] v) {
        Acceleration a = new Acceleration(DistanceUnit.METER, v[0], v[1], v[2], imu.nanos());
        return a;
    }

    @Override
    public Acceleration getLinearAcceleration() {
        imu.i2cRead();
        return acceleration(imu.hubLinearAcceleration());
    }

    @Override
    public Acceleration getGravity() {
        imu.i2cRead();
        return acceleration(imu.hubGravity());
    }

    @Override
    public Acceleration getOverallAcceleration() {
        imu.i2cRead();
        double[] l = imu.hubLinearAcceleration(), g = imu.hubGravity();
        return acceleration(new double[]{l[0] + g[0], l[1] + g[1], l[2] + g[2]});
    }

    @Override
    public Acceleration getAcceleration() {
        AccelerationIntegrator i = integrator;
        Acceleration a = i != null ? i.getAcceleration() : null;
        return a != null ? a : getLinearAcceleration();
    }

    @Override
    public Position getPosition() {
        AccelerationIntegrator i = integrator;
        return i != null ? i.getPosition() : new Position();
    }

    @Override
    public Velocity getVelocity() {
        AccelerationIntegrator i = integrator;
        return i != null ? i.getVelocity() : new Velocity();
    }

    @Override
    public synchronized void startAccelerationIntegration(Position initialPosition, Velocity initialVelocity, int msPollInterval) {
        AccelerationIntegrator i = parameters.accelerationIntegrationAlgorithm != null
                ? parameters.accelerationIntegrationAlgorithm : new NaiveAccelerationIntegrator();
        i.initialize(parameters, initialPosition, initialVelocity);
        pollInterval = Math.max(1, msPollInterval) / 1000.0;
        sincePoll = 0;
        integrator = i;
    }

    @Override
    public synchronized void stopAccelerationIntegration() {
        integrator = null;
    }

    /** Physics thread: feed the integrator every poll interval. */
    public synchronized void step(double dt) {
        AccelerationIntegrator i = integrator;
        if (i == null) return;
        sincePoll += dt;
        if (sincePoll < pollInterval - 1e-9) return;
        sincePoll = 0;
        i.update(acceleration(imu.hubLinearAcceleration()));
    }

    @Override
    public Temperature getTemperature() {
        imu.i2cRead();
        return new Temperature(org.firstinspires.ftc.robotcore.external.navigation.TempUnit.CELSIUS, 31, imu.nanos())
                .toUnit(parameters.temperatureUnit.toTempUnit());
    }

    /** No magnetometer in IMU mode (and none on the hub's BNO055 fusion): zero. */
    @Override
    public MagneticFlux getMagneticFieldStrength() {
        imu.i2cRead();
        return new MagneticFlux(0, 0, 0, imu.nanos());
    }

    @Override public boolean isGyroCalibrated() { return true; }
    @Override public boolean isSystemCalibrated() { return true; }
    @Override public boolean isAccelerometerCalibrated() { return true; }
    @Override public boolean isMagnetometerCalibrated() { return true; }

    @Override public SystemStatus getSystemStatus() { imu.i2cRead(); return SystemStatus.RUNNING_FUSION; }
    @Override public SystemError getSystemError() { imu.i2cRead(); return SystemError.NO_ERROR; }
    @Override public CalibrationStatus getCalibrationStatus() { imu.i2cRead(); return new CalibrationStatus(0xFF); }

    @Override
    public CalibrationData readCalibrationData() {
        imu.i2cRead();
        return calibrationData.clone();
    }

    @Override
    public void writeCalibrationData(CalibrationData data) {
        imu.i2cRead();
        calibrationData = data.clone();
    }

    @Override public Manufacturer getManufacturer() { return Manufacturer.Adafruit; }
    @Override public String getDeviceName() { return "Simulated BNO055 IMU"; }
    @Override public String getConnectionInfo() { return "I2C bus 0"; }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() {}
    @Override public void close() {}
}
