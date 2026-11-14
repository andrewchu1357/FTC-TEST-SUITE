package org.firstinspires.ftc.robotcore.external.hardware.camera.controls;

public interface WhiteBalanceControl extends CameraControl {
    enum Mode { UNKNOWN, AUTO, MANUAL }

    Mode getMode();

    boolean setMode(Mode mode);

    int getMinWhiteBalanceTemperature();

    int getMaxWhiteBalanceTemperature();

    int getWhiteBalanceTemperature();

    boolean setWhiteBalanceTemperature(int temperature);
}
