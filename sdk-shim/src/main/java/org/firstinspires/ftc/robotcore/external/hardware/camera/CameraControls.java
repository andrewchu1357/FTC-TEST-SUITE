package org.firstinspires.ftc.robotcore.external.hardware.camera;

import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.CameraControl;

public interface CameraControls {
    <T extends CameraControl> T getControl(Class<T> controlType);
}
