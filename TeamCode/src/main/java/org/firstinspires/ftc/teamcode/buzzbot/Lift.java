package org.firstinspires.ftc.teamcode.buzzbot;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.hardware.TouchSensor;

/** Angled rear lift with the NECTAR claw. */
public class Lift {
    // 435 rpm motor (384.5 ticks/rev) on a 38.2 mm spool: about 81.4 ticks per inch.
    public static final int DOWN = 0;
    /** Claw about 23 in up: high enough to drop NECTAR into a FLOWER's 21.5 in opening. */
    public static final int FLOWER = 1750;
    public static final double CLAW_OPEN = 0.8, CLAW_CLOSED = 0.2;

    private final DcMotorEx motor;
    private final Servo claw;
    private final TouchSensor bottom;
    private boolean clawClosed;
    private int target;

    public Lift(DcMotorEx motor, Servo claw, TouchSensor bottom) {
        this.motor = motor;
        this.claw = claw;
        this.bottom = bottom;
        motor.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        motor.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        motor.setTargetPosition(0);
        motor.setMode(DcMotor.RunMode.RUN_TO_POSITION);
        motor.setPower(1.0);
        setClaw(false);
    }

    public void setTarget(int ticks) {
        target = ticks;
        motor.setTargetPosition(ticks);
    }

    public int getPosition() {
        return motor.getCurrentPosition();
    }

    public boolean atTarget() {
        return !motor.isBusy();
    }

    public boolean atBottom() {
        return bottom.isPressed();
    }

    public void setClaw(boolean closed) {
        clawClosed = closed;
        claw.setPosition(closed ? CLAW_CLOSED : CLAW_OPEN);
    }

    public boolean isClawClosed() {
        return clawClosed;
    }

    public void update() {
        // Re-zero whenever the lift is resting on its limit switch, so encoder drift can't build up.
        if (target == DOWN && atBottom() && motor.getCurrentPosition() != 0 && atTarget()) {
            motor.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
            motor.setTargetPosition(0);
            motor.setMode(DcMotor.RunMode.RUN_TO_POSITION);
            motor.setPower(1.0);
        }
    }
}
