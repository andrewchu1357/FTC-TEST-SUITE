package com.qualcomm.robotcore.hardware;

public interface Servo extends HardwareDevice {
    enum Direction { FORWARD, REVERSE }

    double MIN_POSITION = 0.0;
    double MAX_POSITION = 1.0;

    ServoController getController();

    int getPortNumber();

    void setDirection(Direction direction);

    Direction getDirection();

    void setPosition(double position);

    double getPosition();

    void scaleRange(double min, double max);
}
