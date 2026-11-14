package com.qualcomm.robotcore.hardware;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;

public interface DcMotorControllerEx extends DcMotorController {
    void setMotorEnable(int motor);

    void setMotorDisable(int motor);

    boolean isMotorEnabled(int motor);

    void setMotorVelocity(int motor, double ticksPerSecond);

    void setMotorVelocity(int motor, double angularRate, AngleUnit unit);

    double getMotorVelocity(int motor);

    double getMotorVelocity(int motor, AngleUnit unit);

    @Deprecated
    void setPIDCoefficients(int motor, DcMotor.RunMode mode, PIDCoefficients pidCoefficients);

    void setPIDFCoefficients(int motor, DcMotor.RunMode mode, PIDFCoefficients pidfCoefficients) throws UnsupportedOperationException;

    @Deprecated
    PIDCoefficients getPIDCoefficients(int motor, DcMotor.RunMode mode);

    PIDFCoefficients getPIDFCoefficients(int motor, DcMotor.RunMode mode);

    void setMotorTargetPosition(int motor, int position, int tolerance);

    double getMotorCurrent(int motor, CurrentUnit unit);

    double getMotorCurrentAlert(int motor, CurrentUnit unit);

    void setMotorCurrentAlert(int motor, double current, CurrentUnit unit);

    boolean isMotorOverCurrent(int motor);
}
