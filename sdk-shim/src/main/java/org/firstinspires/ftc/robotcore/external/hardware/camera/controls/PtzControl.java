package org.firstinspires.ftc.robotcore.external.hardware.camera.controls;

/** Digital pan, tilt and zoom. */
public interface PtzControl extends CameraControl {
    class PanTiltHolder {
        public int pan;
        public int tilt;
    }

    PanTiltHolder getPanTilt();

    boolean setPanTilt(PanTiltHolder panTiltHolder);

    PanTiltHolder getMinPanTilt();

    PanTiltHolder getMaxPanTilt();

    int getZoom();

    boolean setZoom(int zoom);

    int getMinZoom();

    int getMaxZoom();
}
