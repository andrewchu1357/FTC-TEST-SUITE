package com.qualcomm.robotcore.hardware;

public class ServoImplEx extends ServoImpl implements PwmControl {
    protected ServoControllerEx controllerEx;

    public ServoImplEx(ServoControllerEx controller, int portNumber) {
        this(controller, portNumber, Direction.FORWARD);
    }

    public ServoImplEx(ServoControllerEx controller, int portNumber, Direction direction) {
        super(controller, portNumber, direction);
        this.controllerEx = controller;
    }

    @Override public void setPwmRange(PwmRange range) { controllerEx.setServoPwmRange(portNumber, range); }
    @Override public PwmRange getPwmRange() { return controllerEx.getServoPwmRange(portNumber); }
    @Override public void setPwmEnable() { controllerEx.setServoPwmEnable(portNumber); }
    @Override public void setPwmDisable() { controllerEx.setServoPwmDisable(portNumber); }
    @Override public boolean isPwmEnabled() { return controllerEx.isServoPwmEnabled(portNumber); }
    @Override public void setPulseWidth(double microseconds) { controllerEx.setPulseWidth(portNumber, microseconds); }
    @Override public double getPulseWidth() { return controllerEx.getPulseWidth(portNumber); }
}
