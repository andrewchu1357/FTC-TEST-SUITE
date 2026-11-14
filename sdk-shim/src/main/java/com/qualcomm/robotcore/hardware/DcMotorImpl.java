package com.qualcomm.robotcore.hardware;

import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;
import com.qualcomm.robotcore.util.Range;

public class DcMotorImpl implements DcMotor {
    protected DcMotorController controller;
    protected int portNumber;
    // volatile so getDirection() needs no lock: the simulator reads it while robot code may be
    // inside another call on this motor (waiting for the hub's reply).
    protected volatile Direction direction;

    public DcMotorImpl(DcMotorController controller, int portNumber) {
        this(controller, portNumber, Direction.FORWARD);
    }

    public DcMotorImpl(DcMotorController controller, int portNumber, Direction direction) {
        this.controller = controller;
        this.portNumber = portNumber;
        this.direction = direction;
    }

    public DcMotorImpl(DcMotorController controller, int portNumber, Direction direction, MotorConfigurationType motorType) {
        this(controller, portNumber, direction);
        controller.setMotorType(portNumber, motorType);
    }

    @Override public Manufacturer getManufacturer() { return controller.getManufacturer(); }
    @Override public String getDeviceName() { return "DC Motor"; }
    @Override public String getConnectionInfo() { return controller.getConnectionInfo() + "; port " + portNumber; }
    @Override public int getVersion() { return 1; }

    @Override
    public synchronized void resetDeviceConfigurationForOpMode() {
        setDirection(Direction.FORWARD);
        controller.resetDeviceConfigurationForOpMode(portNumber);
    }

    @Override public void close() { setPowerFloat(); }

    @Override public MotorConfigurationType getMotorType() { return controller.getMotorType(portNumber); }
    @Override public void setMotorType(MotorConfigurationType motorType) { controller.setMotorType(portNumber, motorType); }
    @Override public DcMotorController getController() { return controller; }
    @Override public int getPortNumber() { return portNumber; }

    @Override public synchronized void setDirection(Direction direction) { this.direction = direction; }
    @Override public Direction getDirection() { return direction; }

    protected Direction getOperationalDirection() { return direction; }

    @Override
    public synchronized void setPower(double power) {
        power = Range.clip(power, -1.0, 1.0);
        internalSetPower(adjustPower(power));
    }

    protected void internalSetPower(double power) { controller.setMotorPower(portNumber, power); }

    @Override
    public synchronized double getPower() {
        double power = controller.getMotorPower(portNumber);
        if (getOperationalDirection() == Direction.REVERSE && power != 0.0) power = -power;
        return power;
    }

    protected double adjustPower(double power) {
        if (getOperationalDirection() == Direction.REVERSE) power = -power;
        return power;
    }

    protected int adjustPosition(int position) {
        if (getOperationalDirection() == Direction.REVERSE) position = -position;
        return position;
    }

    @Override public void setZeroPowerBehavior(ZeroPowerBehavior zeroPowerBehavior) { controller.setMotorZeroPowerBehavior(portNumber, zeroPowerBehavior); }
    @Override public ZeroPowerBehavior getZeroPowerBehavior() { return controller.getMotorZeroPowerBehavior(portNumber); }

    @Override
    @Deprecated
    public synchronized void setPowerFloat() {
        setZeroPowerBehavior(ZeroPowerBehavior.FLOAT);
        setPower(0.0);
    }

    @Override public boolean getPowerFloat() { return controller.getMotorPowerFloat(portNumber); }

    @Override
    public synchronized void setTargetPosition(int position) {
        internalSetTargetPosition(adjustPosition(position));
    }

    protected void internalSetTargetPosition(int position) { controller.setMotorTargetPosition(portNumber, position); }

    @Override public synchronized int getTargetPosition() { return adjustPosition(controller.getMotorTargetPosition(portNumber)); }
    @Override public boolean isBusy() { return controller.isBusy(portNumber); }
    @Override public synchronized int getCurrentPosition() { return adjustPosition(controller.getMotorCurrentPosition(portNumber)); }

    @Override public synchronized void setMode(RunMode mode) { controller.setMotorMode(portNumber, mode.migrate()); }
    @Override public RunMode getMode() { return controller.getMotorMode(portNumber); }
}
