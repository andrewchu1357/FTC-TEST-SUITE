package com.qualcomm.robotcore.hardware;

import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;

public interface ImuOrientationOnRobot {
    /** Rotation that takes the robot's coordinate system to the IMU's coordinate system. */
    Quaternion imuCoordinateSystemOrientationFromPerspectiveOfRobot();

    Quaternion imuRotationOffset();

    Quaternion angularVelocityTransform();
}
