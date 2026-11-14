package com.qualcomm.robotcore.hardware;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;

/** The universal IMU interface (BHI260AP / BNO055 in the real SDK). */
public interface IMU extends HardwareDevice {
    boolean initialize(Parameters parameters);

    void resetYaw();

    YawPitchRollAngles getRobotYawPitchRollAngles();

    Orientation getRobotOrientation(AxesReference reference, AxesOrder order, AngleUnit angleUnit);

    Quaternion getRobotOrientationAsQuaternion();

    AngularVelocity getRobotAngularVelocity(AngleUnit angleUnit);

    class Parameters implements Cloneable {
        public ImuOrientationOnRobot imuOrientationOnRobot;

        public Parameters(ImuOrientationOnRobot imuOrientationOnRobot) {
            this.imuOrientationOnRobot = imuOrientationOnRobot;
        }

        @Override
        public Parameters clone() {
            try {
                return (Parameters) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
