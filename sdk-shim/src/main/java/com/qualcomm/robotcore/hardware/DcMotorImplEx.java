package com.qualcomm.robotcore.hardware;

import com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;

public class DcMotorImplEx extends DcMotorImpl implements DcMotorEx {
    /** Same default the REV hub firmware uses. */
    public static final int DEFAULT_TARGET_POSITION_TOLERANCE = 5;

    protected DcMotorControllerEx controllerEx;
    protected int targetPositionTolerance = DEFAULT_TARGET_POSITION_TOLERANCE;

    public DcMotorImplEx(DcMotorController controller, int portNumber) {
        this(controller, portNumber, Direction.FORWARD);
    }

    public DcMotorImplEx(DcMotorController controller, int portNumber, Direction direction) {
        super(controller, portNumber, direction);
        this.controllerEx = (DcMotorControllerEx) controller;
    }

    public DcMotorImplEx(DcMotorController controller, int portNumber, Direction direction, MotorConfigurationType motorType) {
        super(controller, portNumber, direction, motorType);
        this.controllerEx = (DcMotorControllerEx) controller;
    }

    @Override public void setMotorEnable() { controllerEx.setMotorEnable(portNumber); }
    @Override public void setMotorDisable() { controllerEx.setMotorDisable(portNumber); }
    @Override public boolean isMotorEnabled() { return controllerEx.isMotorEnabled(portNumber); }

    protected double adjustAngularRate(double angularRate) {
        if (getOperationalDirection() == Direction.REVERSE) angularRate = -angularRate;
        return angularRate;
    }

    @Override public synchronized void setVelocity(double angularRate) { controllerEx.setMotorVelocity(portNumber, adjustAngularRate(angularRate)); }
    @Override public synchronized void setVelocity(double angularRate, AngleUnit unit) { controllerEx.setMotorVelocity(portNumber, adjustAngularRate(angularRate), unit); }
    @Override public synchronized double getVelocity() { return adjustAngularRate(controllerEx.getMotorVelocity(portNumber)); }
    @Override public synchronized double getVelocity(AngleUnit unit) { return adjustAngularRate(controllerEx.getMotorVelocity(portNumber, unit)); }

    @Override @Deprecated
    public void setPIDCoefficients(RunMode mode, PIDCoefficients pidCoefficients) { controllerEx.setPIDCoefficients(portNumber, mode, pidCoefficients); }

    @Override
    public void setPIDFCoefficients(RunMode mode, PIDFCoefficients pidfCoefficients) { controllerEx.setPIDFCoefficients(portNumber, mode, pidfCoefficients); }

    @Override
    public void setVelocityPIDFCoefficients(double p, double i, double d, double f) {
        setPIDFCoefficients(RunMode.RUN_USING_ENCODER, new PIDFCoefficients(p, i, d, f, MotorControlAlgorithm.PIDF));
    }

    @Override
    public void setPositionPIDFCoefficients(double p) {
        setPIDFCoefficients(RunMode.RUN_TO_POSITION, new PIDFCoefficients(p, 0, 0, 0, MotorControlAlgorithm.PIDF));
    }

    @Override @Deprecated
    public PIDCoefficients getPIDCoefficients(RunMode mode) { return controllerEx.getPIDCoefficients(portNumber, mode); }

    @Override
    public PIDFCoefficients getPIDFCoefficients(RunMode mode) { return controllerEx.getPIDFCoefficients(portNumber, mode); }

    @Override public synchronized void setTargetPositionTolerance(int tolerance) { targetPositionTolerance = tolerance; }
    @Override public synchronized int getTargetPositionTolerance() { return targetPositionTolerance; }

    @Override
    protected void internalSetTargetPosition(int position) {
        controllerEx.setMotorTargetPosition(portNumber, position, targetPositionTolerance);
    }

    @Override public double getCurrent(CurrentUnit unit) { return controllerEx.getMotorCurrent(portNumber, unit); }
    @Override public double getCurrentAlert(CurrentUnit unit) { return controllerEx.getMotorCurrentAlert(portNumber, unit); }
    @Override public void setCurrentAlert(double current, CurrentUnit unit) { controllerEx.setMotorCurrentAlert(portNumber, current, unit); }
    @Override public boolean isOverCurrent() { return controllerEx.isMotorOverCurrent(portNumber); }
}
