package com.qualcomm.robotcore.hardware;

import com.qualcomm.robotcore.util.Range;

/** Like the real SDK, power [-1,1] is sent to the controller as a servo position [0,1]. */
public class CRServoImpl implements CRServo {
    protected static final double apiPowerMin = -1.0;
    protected static final double apiPowerMax = 1.0;
    protected static final double apiServoPositionMin = 0.0;
    protected static final double apiServoPositionMax = 1.0;

    protected ServoController controller;
    protected int portNumber;
    protected Direction direction;

    public CRServoImpl(ServoController controller, int portNumber) {
        this(controller, portNumber, Direction.FORWARD);
    }

    public CRServoImpl(ServoController controller, int portNumber, Direction direction) {
        this.controller = controller;
        this.portNumber = portNumber;
        this.direction = direction;
    }

    @Override public Manufacturer getManufacturer() { return controller.getManufacturer(); }
    @Override public String getDeviceName() { return "Continuous Rotation Servo"; }
    @Override public String getConnectionInfo() { return controller.getConnectionInfo() + "; port " + portNumber; }
    @Override public int getVersion() { return 1; }
    @Override public synchronized void resetDeviceConfigurationForOpMode() { direction = Direction.FORWARD; }
    @Override public void close() {}

    @Override public ServoController getController() { return controller; }
    @Override public int getPortNumber() { return portNumber; }
    @Override public synchronized void setDirection(Direction direction) { this.direction = direction; }
    @Override public synchronized Direction getDirection() { return direction; }

    @Override
    public synchronized void setPower(double power) {
        if (direction == Direction.REVERSE) power = -power;
        power = Range.clip(power, apiPowerMin, apiPowerMax);
        power = Range.scale(power, apiPowerMin, apiPowerMax, apiServoPositionMin, apiServoPositionMax);
        controller.setServoPosition(portNumber, power);
    }

    @Override
    public synchronized double getPower() {
        double power = controller.getServoPosition(portNumber);
        power = Range.scale(power, apiServoPositionMin, apiServoPositionMax, apiPowerMin, apiPowerMax);
        if (direction == Direction.REVERSE) power = -power;
        return power;
    }
}
