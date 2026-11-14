package com.qualcomm.robotcore.hardware;

public interface RobotConfigNameable extends HardwareDevice {
    void setUserConfiguredName(String name);

    String getUserConfiguredName();
}
