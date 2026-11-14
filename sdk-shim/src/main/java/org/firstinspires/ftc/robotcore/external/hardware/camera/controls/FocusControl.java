package org.firstinspires.ftc.robotcore.external.hardware.camera.controls;

public interface FocusControl extends CameraControl {
    double unknownFocusLength = -1.0;

    enum Mode {
        Unknown, Auto, ContinuousAuto, Macro, Infinity, Fixed;

        public static Mode fromId(int id) {
            return id >= 0 && id < values().length ? values()[id] : Unknown;
        }
    }

    Mode getMode();

    boolean setMode(Mode mode);

    boolean isModeSupported(Mode mode);

    double getMinFocusLength();

    double getMaxFocusLength();

    double getFocusLength();

    boolean setFocusLength(double focusLength);

    boolean isFocusLengthSupported();
}
