package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.util.ElapsedTime;

/** Example iterative (non-linear) OpMode: a timed state machine that drives a square. */
@Autonomous(name = "Iterative Timed Square", group = "Starter robot examples")
public class IterativeTimedSquare extends OpMode {
    private enum Step { FORWARD, RIGHT, BACK, LEFT, DONE }

    private DcMotor leftFront, leftBack, rightFront, rightBack;
    private final ElapsedTime stepTimer = new ElapsedTime();
    private Step step = Step.FORWARD;

    @Override
    public void init() {
        leftFront = hardwareMap.get(DcMotor.class, "leftFront");
        leftBack = hardwareMap.get(DcMotor.class, "leftBack");
        rightFront = hardwareMap.get(DcMotor.class, "rightFront");
        rightBack = hardwareMap.get(DcMotor.class, "rightBack");
        leftFront.setDirection(DcMotorSimple.Direction.REVERSE);
        leftBack.setDirection(DcMotorSimple.Direction.REVERSE);
    }

    @Override
    public void init_loop() {
        telemetry.addData("Status", "waiting for start (%.1f s)", getRuntime());
    }

    @Override
    public void start() {
        stepTimer.reset();
    }

    @Override
    public void loop() {
        double p = 0.4;
        switch (step) {
            case FORWARD: drive(p, 0); break;
            case RIGHT: drive(0, p); break;
            case BACK: drive(-p, 0); break;
            case LEFT: drive(0, -p); break;
            default: drive(0, 0);
        }
        if (step != Step.DONE && stepTimer.seconds() > 1.2) {
            step = Step.values()[step.ordinal() + 1];
            stepTimer.reset();
        }
        telemetry.addData("Step", step);
        telemetry.addData("Time in step", "%.2f", stepTimer.seconds());
    }

    private void drive(double forward, double right) {
        leftFront.setPower(forward + right);
        leftBack.setPower(forward - right);
        rightFront.setPower(forward - right);
        rightBack.setPower(forward + right);
    }

    @Override
    public void stop() {
        drive(0, 0);
    }
}
