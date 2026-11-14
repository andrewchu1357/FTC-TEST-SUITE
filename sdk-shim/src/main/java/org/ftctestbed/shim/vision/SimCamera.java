package org.ftctestbed.shim.vision;

import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibration;

/** A simulated webcam, as the SDK's VisionPortal sees it. The simulator implements it. */
public interface SimCamera extends WebcamName {
    /** null if it can stream this size; otherwise the error the real camera library gives. */
    String simCheckSize(int width, int height);

    /** Frames per second it can stream at this size: YUY2 is limited by USB bandwidth, MJPEG isn't. */
    double simMaxFps(int width, int height, boolean mjpeg);

    /**
     * The SDK's built-in calibration for this camera at this size (scaled from another size of the
     * same shape if needed), or null if the SDK has none.
     */
    CameraCalibration simCalibration(int width, int height);

    SimCameraControls simControls();

    /** Physics thread: what the camera sees right now, at this size. */
    SimFrame simCapture(int width, int height);

    /** Robot thread: a portal wants to stream from it. False if another portal has it. */
    boolean simClaim(SimPortal portal);

    void simRelease(SimPortal portal);
}
