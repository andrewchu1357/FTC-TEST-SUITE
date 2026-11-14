package org.firstinspires.ftc.robotcore.external.hardware.camera.controls;

/** How much the picture is amplified (brighter, but noisier). */
public interface GainControl extends CameraControl {
    int getMinGain();

    int getMaxGain();

    int getGain();

    boolean setGain(int gain);
}
