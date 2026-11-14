package com.qualcomm.hardware.andymark;

import com.qualcomm.hardware.rev.RevHubOrientationOnRobot;
import com.qualcomm.robotcore.hardware.ImuOrientationOnRobot;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.ftctestbed.shim.Rotations;

/**
 * How an AndyMark IMU is mounted: which way its logo and its I2C port face. The SDK uses the
 * same direction table as the REV hub (I2C port in place of the USB ports), then turns -90°
 * about the board's Z axis because the chip sits turned on the board; so does this.
 */
public class AndyMarkIMUOrientationOnRobot implements ImuOrientationOnRobot {
    public enum LogoFacingDirection { UP, DOWN, FORWARD, BACKWARD, LEFT, RIGHT }

    public enum I2cPortFacingDirection { UP, DOWN, FORWARD, BACKWARD, LEFT, RIGHT }

    private final double[][] imuToRobot;

    public AndyMarkIMUOrientationOnRobot(LogoFacingDirection logoFacingDirection, I2cPortFacingDirection i2cPortFacingDirection) {
        this(boardToRobot(new RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.valueOf(logoFacingDirection.name()),
                RevHubOrientationOnRobot.UsbFacingDirection.valueOf(i2cPortFacingDirection.name()))));
    }

    public AndyMarkIMUOrientationOnRobot(Orientation rotationFromReference) {
        this(boardToRobot(new RevHubOrientationOnRobot(rotationFromReference)));
    }

    public AndyMarkIMUOrientationOnRobot(Quaternion rotationFromReference) {
        this(boardToRobot(new RevHubOrientationOnRobot(rotationFromReference)));
    }

    private AndyMarkIMUOrientationOnRobot(double[][] boardToRobot) {
        imuToRobot = Rotations.multiply(boardToRobot, Rotations.axisRotation(2, Math.toRadians(-90)));
    }

    private static double[][] boardToRobot(RevHubOrientationOnRobot o) {
        return Rotations.fromQuaternion(o.imuCoordinateSystemOrientationFromPerspectiveOfRobot());
    }

    public static Orientation xyzOrientation(double x, double y, double z) {
        return RevHubOrientationOnRobot.xyzOrientation(x, y, z);
    }

    public static Orientation zyxOrientation(double z, double y, double x) {
        return RevHubOrientationOnRobot.zyxOrientation(z, y, x);
    }

    @Override
    public Quaternion imuCoordinateSystemOrientationFromPerspectiveOfRobot() {
        return Rotations.toQuaternion(imuToRobot, 0);
    }

    @Override
    public Quaternion imuRotationOffset() {
        return imuCoordinateSystemOrientationFromPerspectiveOfRobot().inverse();
    }

    @Override
    public Quaternion angularVelocityTransform() {
        return imuRotationOffset();
    }
}
