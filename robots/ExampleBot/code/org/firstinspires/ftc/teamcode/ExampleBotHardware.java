package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.DistanceSensor;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.hardware.TouchSensor;

/** ExampleBot's hardware, the way a team would write it. */
public class ExampleBotHardware {
    public static final double TICKS_PER_INCH = 537.7 / (104 / 25.4 * Math.PI); // 312 rpm motor, 104 mm wheels
    public static final int LIFT_TICKS_PER_INCH = (int) Math.round(384.5 / (120 / 25.4)); // 435 rpm motor, 120 mm/turn spool
    public static final int LIFT_HIGH = 18 * LIFT_TICKS_PER_INCH;
    public static final double CLAW_OPEN = 0.35, CLAW_CLOSED = 0.65;

    public final DcMotorEx leftFront, rightFront, leftBack, rightBack, lift;
    public final Servo claw;
    public final TouchSensor liftDown;
    public final DistanceSensor frontDistance;

    public ExampleBotHardware(HardwareMap hw) {
        leftFront = hw.get(DcMotorEx.class, "leftFront");
        rightFront = hw.get(DcMotorEx.class, "rightFront");
        leftBack = hw.get(DcMotorEx.class, "leftBack");
        rightBack = hw.get(DcMotorEx.class, "rightBack");
        lift = hw.get(DcMotorEx.class, "lift");
        claw = hw.get(Servo.class, "claw");
        liftDown = hw.get(TouchSensor.class, "liftDown");
        frontDistance = hw.get(DistanceSensor.class, "frontDistance");

        leftFront.setDirection(DcMotorSimple.Direction.REVERSE);
        leftBack.setDirection(DcMotorSimple.Direction.REVERSE);
        for (DcMotorEx m : new DcMotorEx[]{leftFront, rightFront, leftBack, rightBack, lift}) {
            m.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        }
        lift.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        lift.setTargetPosition(0);
        lift.setMode(DcMotor.RunMode.RUN_TO_POSITION);
        lift.setPower(1.0);
        claw.setPosition(CLAW_CLOSED);
    }

    /** Robot-centric mecanum drive. */
    public void drive(double forward, double strafe, double turn) {
        double lf = forward + strafe + turn, rf = forward - strafe - turn;
        double lb = forward - strafe + turn, rb = forward + strafe - turn;
        double max = Math.max(1, Math.max(Math.max(Math.abs(lf), Math.abs(rf)), Math.max(Math.abs(lb), Math.abs(rb))));
        leftFront.setPower(lf / max);
        rightFront.setPower(rf / max);
        leftBack.setPower(lb / max);
        rightBack.setPower(rb / max);
    }

    public void setLift(int ticks) {
        lift.setTargetPosition(Math.max(0, Math.min(LIFT_HIGH, ticks)));
    }
}
