package org.firstinspires.ftc.robotcore.external.hardware.camera;

import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.util.SerialNumber;

/** A USB webcam in the robot configuration (hardwareMap.get(WebcamName.class, "Webcam 1")). */
public interface WebcamName extends CameraName, HardwareDevice {
    SerialNumber getSerialNumber();

    String getUsbDeviceNameIfAttached();

    boolean isAttached();
}
