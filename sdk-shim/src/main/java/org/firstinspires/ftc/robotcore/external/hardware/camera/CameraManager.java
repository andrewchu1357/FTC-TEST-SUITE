package org.firstinspires.ftc.robotcore.external.hardware.camera;

import org.firstinspires.ftc.robotcore.internal.camera.delegating.SwitchableCameraName;

import java.util.List;

/** Finds cameras: ClassFactory.getInstance().getCameraManager(). */
public interface CameraManager {
    List<WebcamName> getAllWebcams();

    CameraName nameFromCameraDirection(BuiltinCameraDirection cameraDirection);

    CameraName nameForUnknownCamera();

    SwitchableCameraName nameForSwitchableCamera(CameraName... names);
}
