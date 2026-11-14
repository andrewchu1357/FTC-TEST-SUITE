package org.firstinspires.ftc.teamcode.buzzbot;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.util.Range;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;

/** Mecanum drive with robot-centric, field-centric and drive-to-pose control. */
public class Drive {
    static final double TRANSLATE_GAIN = 0.07;   // power per inch of error
    static final double TURN_GAIN = 1.6;         // power per radian of error
    static final double MIN_POWER = 0.07;        // enough to overcome friction near the target

    private final DcMotorEx lf, rf, lb, rb;
    private final Localizer localizer;

    public Drive(DcMotorEx lf, DcMotorEx rf, DcMotorEx lb, DcMotorEx rb, Localizer localizer) {
        this.lf = lf;
        this.rf = rf;
        this.lb = lb;
        this.rb = rb;
        this.localizer = localizer;
        lf.setDirection(DcMotorSimple.Direction.REVERSE);
        lb.setDirection(DcMotorSimple.Direction.REVERSE);
        for (DcMotorEx m : new DcMotorEx[]{lf, rf, lb, rb}) {
            m.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
            m.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        }
    }

    /** forward, left and turn (counter-clockwise) in -1..1, relative to the robot. */
    public void drive(double forward, double left, double turn) {
        double flp = forward - left - turn, frp = forward + left + turn;
        double blp = forward + left - turn, brp = forward - left + turn;
        double max = Math.max(1, Math.max(Math.max(Math.abs(flp), Math.abs(frp)), Math.max(Math.abs(blp), Math.abs(brp))));
        lf.setPower(flp / max);
        rf.setPower(frp / max);
        lb.setPower(blp / max);
        rb.setPower(brp / max);
    }

    /** Drive in field directions (x, y in -1..1) while turning. */
    public void driveField(double fieldX, double fieldY, double turn) {
        double h = localizer.getPose().heading;
        double c = Math.cos(h), s = Math.sin(h);
        drive(fieldX * c + fieldY * s, -fieldX * s + fieldY * c, turn);
    }

    public void stop() {
        drive(0, 0, 0);
    }

    /**
     * One step of driving toward a pose. Call every loop; returns true (and stops) once the
     * robot is within the tolerances.
     */
    public boolean driveTo(Pose target, double maxPower, double toleranceIn, double toleranceDeg) {
        Pose p = localizer.getPose();
        double dx = target.x - p.x, dy = target.y - p.y;
        double distance = Math.hypot(dx, dy);
        double dh = AngleUnit.normalizeRadians(target.heading - p.heading);
        if (distance < toleranceIn && Math.abs(dh) < Math.toRadians(toleranceDeg)) {
            stop();
            return true;
        }
        double speed = Range.clip(distance * TRANSLATE_GAIN, distance < toleranceIn ? 0 : MIN_POWER, maxPower);
        double fx = distance > 1e-6 ? dx / distance * speed : 0, fy = distance > 1e-6 ? dy / distance * speed : 0;
        double turn = Range.clip(dh * TURN_GAIN, -maxPower, maxPower);
        if (Math.abs(dh) > Math.toRadians(toleranceDeg) && Math.abs(turn) < MIN_POWER) turn = Math.copySign(MIN_POWER, dh);
        driveField(fx, fy, turn);
        return false;
    }

    /** Turn in place (or while driving) to face a heading. Returns true when within tolerance. */
    public boolean turnTo(double heading, double maxPower, double toleranceDeg, double fieldX, double fieldY) {
        double dh = AngleUnit.normalizeRadians(heading - localizer.getPose().heading);
        double turn = Range.clip(dh * TURN_GAIN, -maxPower, maxPower);
        if (Math.abs(turn) < MIN_POWER && Math.abs(dh) > Math.toRadians(toleranceDeg)) turn = Math.copySign(MIN_POWER, dh);
        driveField(fieldX, fieldY, Math.abs(dh) < Math.toRadians(toleranceDeg) ? 0 : turn);
        return Math.abs(dh) < Math.toRadians(toleranceDeg);
    }
}
