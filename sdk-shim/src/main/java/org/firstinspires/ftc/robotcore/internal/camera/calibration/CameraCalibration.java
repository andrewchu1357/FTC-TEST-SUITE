package org.firstinspires.ftc.robotcore.internal.camera.calibration;

/** A camera's lens: focal lengths and principal point (pixels) at a resolution. */
public class CameraCalibration {
    public final float focalLengthX, focalLengthY, principalPointX, principalPointY;
    public final int width, height;
    /** Simulator: the calibrated size this one was scaled from (0 x 0 if it wasn't scaled). */
    public final int simScaledFromWidth, simScaledFromHeight;

    public CameraCalibration(int width, int height, float focalLengthX, float focalLengthY, float principalPointX, float principalPointY) {
        this(width, height, focalLengthX, focalLengthY, principalPointX, principalPointY, 0, 0);
    }

    public CameraCalibration(int width, int height, float focalLengthX, float focalLengthY, float principalPointX, float principalPointY,
                             int scaledFromWidth, int scaledFromHeight) {
        this.simScaledFromWidth = scaledFromWidth;
        this.simScaledFromHeight = scaledFromHeight;
        this.width = width;
        this.height = height;
        this.focalLengthX = focalLengthX;
        this.focalLengthY = focalLengthY;
        this.principalPointX = principalPointX;
        this.principalPointY = principalPointY;
    }

    public float[] getFocalLength() { return new float[]{focalLengthX, focalLengthY}; }
    public float[] getPrincipalPoint() { return new float[]{principalPointX, principalPointY}; }
    public boolean isDegenerate() { return focalLengthX <= 0 || focalLengthY <= 0; }
}
