package org.firstinspires.ftc.teamcode;

import com.arcrobotics.ftclib.command.CommandOpMode;
import com.arcrobotics.ftclib.command.InstantCommand;
import com.arcrobotics.ftclib.command.SequentialCommandGroup;
import com.arcrobotics.ftclib.command.WaitCommand;
import com.arcrobotics.ftclib.command.WaitUntilCommand;
import com.arcrobotics.ftclib.hardware.motors.Motor;
import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.hardware.Servo;

/** Example FTCLib command-based autonomous: raise the lift, open the claw, lower the lift. */
@Autonomous(name = "FTCLib Command Auto", group = "Starter robot examples")
public class FtcLibCommandAuto extends CommandOpMode {
    private Motor lift;

    @Override
    public void initialize() {
        lift = new Motor(hardwareMap, "lift", Motor.GoBILDA.RPM_435);
        lift.setRunMode(Motor.RunMode.PositionControl);
        // With a P-only controller, gravity leaves a steady-state error of about
        // (holding power / kP). Too small a kP and the lift settles outside the tolerance
        // and never reports atTargetPosition() (try 0.01 to see it happen).
        lift.setPositionCoefficient(0.05);
        lift.setPositionTolerance(15);
        lift.resetEncoder();
        Servo claw = hardwareMap.get(Servo.class, "claw");

        schedule(new SequentialCommandGroup(
                new InstantCommand(() -> claw.setPosition(0.3)),
                new InstantCommand(() -> lift.setTargetPosition(1500)),
                new WaitUntilCommand(this::liftAtTarget),
                new InstantCommand(() -> claw.setPosition(0.7)),
                new WaitCommand(750),
                new InstantCommand(() -> lift.setTargetPosition(0)),
                new WaitUntilCommand(this::liftAtTarget),
                new InstantCommand(() -> lift.set(0))
        ));
    }

    private boolean liftAtTarget() {
        return lift.atTargetPosition();
    }

    @Override
    public void run() {
        // FTCLib position control only updates its error (and atTargetPosition()) when
        // set() is called, so call it every loop.
        lift.set(0.8);
        super.run();
        telemetry.addData("Lift", lift.getCurrentPosition());
        telemetry.update();
    }
}
