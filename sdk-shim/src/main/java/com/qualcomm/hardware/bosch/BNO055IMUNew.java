package com.qualcomm.hardware.bosch;

import com.qualcomm.robotcore.hardware.I2cDeviceSynchSimple;
import com.qualcomm.robotcore.hardware.IMU;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;

/** The Bosch BNO055 IMU (older Control Hubs) through the universal IMU interface. In the simulator it wraps the simulated IMU. */
public class BNO055IMUNew implements IMU {
    protected final IMU simulated;

    public BNO055IMUNew(I2cDeviceSynchSimple deviceClient, boolean deviceClientIsOwned) {
        throw new UnsupportedOperationException("The simulator makes the IMU from robot.jsonc (\"imu\")");
    }

    /** Simulator: an IMU whose readings come from this simulated one. */
    public BNO055IMUNew(IMU simulated) {
        this.simulated = simulated;
    }

    @Override public boolean initialize(Parameters parameters) { return simulated.initialize(parameters); }
    @Override public void resetYaw() { simulated.resetYaw(); }
    @Override public YawPitchRollAngles getRobotYawPitchRollAngles() { return simulated.getRobotYawPitchRollAngles(); }

    @Override
    public Orientation getRobotOrientation(AxesReference reference, AxesOrder order, AngleUnit angleUnit) {
        return simulated.getRobotOrientation(reference, order, angleUnit);
    }

    @Override public Quaternion getRobotOrientationAsQuaternion() { return simulated.getRobotOrientationAsQuaternion(); }
    @Override public AngularVelocity getRobotAngularVelocity(AngleUnit angleUnit) { return simulated.getRobotAngularVelocity(angleUnit); }

    public int getFirmwareVersion() { return 1; }

    @Override public Manufacturer getManufacturer() { return Manufacturer.Lynx; }
    @Override public String getDeviceName() { return "BNO055 IMU"; }
    @Override public String getConnectionInfo() { return simulated.getConnectionInfo(); }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() { simulated.resetDeviceConfigurationForOpMode(); }
    @Override public void close() {}
}
