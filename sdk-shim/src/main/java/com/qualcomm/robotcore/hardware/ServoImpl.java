package com.qualcomm.robotcore.hardware;

import com.qualcomm.robotcore.util.Range;

public class ServoImpl implements Servo {
    protected ServoController controller;
    protected int portNumber;
    protected Direction direction;
    protected double limitPositionMin = MIN_POSITION;
    protected double limitPositionMax = MAX_POSITION;

    public ServoImpl(ServoController controller, int portNumber) {
        this(controller, portNumber, Direction.FORWARD);
    }

    public ServoImpl(ServoController controller, int portNumber, Direction direction) {
        this.controller = controller;
        this.portNumber = portNumber;
        this.direction = direction;
    }

    @Override public Manufacturer getManufacturer() { return controller.getManufacturer(); }
    @Override public String getDeviceName() { return "Servo"; }
    @Override public String getConnectionInfo() { return controller.getConnectionInfo() + "; port " + portNumber; }
    @Override public int getVersion() { return 1; }

    @Override
    public synchronized void resetDeviceConfigurationForOpMode() {
        limitPositionMin = MIN_POSITION;
        limitPositionMax = MAX_POSITION;
        direction = Direction.FORWARD;
    }

    @Override public void close() {}

    @Override public ServoController getController() { return controller; }
    @Override public int getPortNumber() { return portNumber; }
    @Override public synchronized void setDirection(Direction direction) { this.direction = direction; }
    @Override public synchronized Direction getDirection() { return direction; }

    @Override
    public synchronized void setPosition(double position) {
        position = Range.clip(position, MIN_POSITION, MAX_POSITION);
        if (direction == Direction.REVERSE) position = reverse(position);
        double scaled = Range.scale(position, MIN_POSITION, MAX_POSITION, limitPositionMin, limitPositionMax);
        internalSetPosition(scaled);
    }

    protected void internalSetPosition(double position) {
        controller.setServoPosition(portNumber, position);
    }

    @Override
    public synchronized double getPosition() {
        double position = controller.getServoPosition(portNumber);
        double unscaled = Range.scale(position, limitPositionMin, limitPositionMax, MIN_POSITION, MAX_POSITION);
        if (direction == Direction.REVERSE) unscaled = reverse(unscaled);
        return Range.clip(unscaled, MIN_POSITION, MAX_POSITION);
    }

    private double reverse(double position) {
        return MAX_POSITION - position + MIN_POSITION;
    }

    @Override
    public synchronized void scaleRange(double min, double max) {
        min = Range.clip(min, MIN_POSITION, MAX_POSITION);
        max = Range.clip(max, MIN_POSITION, MAX_POSITION);
        if (min >= max) throw new IllegalArgumentException("min must be less than max");
        limitPositionMin = min;
        limitPositionMax = max;
    }
}
