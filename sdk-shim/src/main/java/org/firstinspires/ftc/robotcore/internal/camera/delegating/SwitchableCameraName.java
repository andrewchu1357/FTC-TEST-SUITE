package org.firstinspires.ftc.robotcore.internal.camera.delegating;

import org.firstinspires.ftc.robotcore.external.hardware.camera.CameraName;

/** Several webcams one VisionPortal can switch between (only one streams at a time). */
public interface SwitchableCameraName extends CameraName {
    CameraName[] getMembers();

    boolean allMembersAreWebcams();
}
