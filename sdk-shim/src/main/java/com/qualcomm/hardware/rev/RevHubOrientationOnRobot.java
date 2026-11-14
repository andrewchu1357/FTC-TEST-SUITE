package com.qualcomm.hardware.rev;

import com.qualcomm.robotcore.hardware.ImuOrientationOnRobot;

import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.ftctestbed.shim.Rotations;

/**
 * How a REV Control/Expansion Hub is mounted. Robot axes: X right, Y forward, Z up.
 * Reference orientation (identity) is logo UP, USB ports FORWARD.
 */
public class RevHubOrientationOnRobot implements ImuOrientationOnRobot {
    public enum LogoFacingDirection { UP, DOWN, FORWARD, BACKWARD, LEFT, RIGHT }

    public enum UsbFacingDirection { UP, DOWN, FORWARD, BACKWARD, LEFT, RIGHT }

    private final double[][] hubToRobot;

    public RevHubOrientationOnRobot(LogoFacingDirection logoFacingDirection, UsbFacingDirection usbFacingDirection) {
        double[] logo = vector(logoFacingDirection.name());
        double[] usb = vector(usbFacingDirection.name());
        double dot = logo[0] * usb[0] + logo[1] * usb[1] + logo[2] * usb[2];
        if (dot != 0) {
            throw new IllegalArgumentException("The specified REV Hub orientation is impossible: logo facing "
                    + logoFacingDirection + " and USB facing " + usbFacingDirection + " are not perpendicular");
        }
        // Hub axes in robot coordinates: Z = logo normal, Y = USB direction, X = Y cross Z.
        double[] x = {usb[1] * logo[2] - usb[2] * logo[1], usb[2] * logo[0] - usb[0] * logo[2], usb[0] * logo[1] - usb[1] * logo[0]};
        hubToRobot = new double[][]{
                {x[0], usb[0], logo[0]},
                {x[1], usb[1], logo[1]},
                {x[2], usb[2], logo[2]}};
    }

    public RevHubOrientationOnRobot(Orientation rotationFromReference) {
        Orientation o = rotationFromReference.toAngleUnit(org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.RADIANS);
        hubToRobot = Rotations.fromEuler(o.axesReference, o.axesOrder, o.firstAngle, o.secondAngle, o.thirdAngle);
    }

    public RevHubOrientationOnRobot(Quaternion rotationFromReference) {
        hubToRobot = Rotations.fromQuaternion(rotationFromReference);
    }

    /** A mounting turned x, then y, then z degrees (intrinsic) from the reference (logo up, USB forward). */
    public static Orientation xyzOrientation(double x, double y, double z) {
        return new Orientation(org.firstinspires.ftc.robotcore.external.navigation.AxesReference.INTRINSIC,
                org.firstinspires.ftc.robotcore.external.navigation.AxesOrder.XYZ,
                org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.DEGREES, (float) x, (float) y, (float) z, 0);
    }

    /** A mounting turned z, then y, then x degrees (intrinsic) from the reference (logo up, USB forward). */
    public static Orientation zyxOrientation(double z, double y, double x) {
        return new Orientation(org.firstinspires.ftc.robotcore.external.navigation.AxesReference.INTRINSIC,
                org.firstinspires.ftc.robotcore.external.navigation.AxesOrder.ZYX,
                org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.DEGREES, (float) z, (float) y, (float) x, 0);
    }

    private static double[] vector(String direction) {
        switch (direction) {
            case "UP": return new double[]{0, 0, 1};
            case "DOWN": return new double[]{0, 0, -1};
            case "FORWARD": return new double[]{0, 1, 0};
            case "BACKWARD": return new double[]{0, -1, 0};
            case "LEFT": return new double[]{-1, 0, 0};
            default: return new double[]{1, 0, 0};
        }
    }

    @Override
    public Quaternion imuCoordinateSystemOrientationFromPerspectiveOfRobot() {
        return Rotations.toQuaternion(hubToRobot, 0);
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
