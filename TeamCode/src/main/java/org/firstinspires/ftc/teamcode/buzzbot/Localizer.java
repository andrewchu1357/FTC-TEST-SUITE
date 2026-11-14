package org.firstinspires.ftc.teamcode.buzzbot;

import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.IMU;

import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;

/**
 * Two dead wheels + IMU odometry. The parallel wheel measures forward travel, the
 * perpendicular wheel measures sideways travel, and the IMU gives heading. Each update
 * turns the small changes into a field-relative pose.
 *
 * The dead wheels are plugged into the encoder ports of two drive motors, so we read those
 * motors' encoders. (This is also why the drive motors must not use RUN_USING_ENCODER:
 * their velocity control would be reading the dead wheels.)
 */
public class Localizer {
    // goBILDA 4-bar pods: 32 mm wheel, 2000 ticks per revolution.
    static final double INCHES_PER_TICK = 32.0 / 25.4 * Math.PI / 2000.0;
    /** Parallel wheel's sideways offset from the robot center (inches, + = left). */
    static final double PAR_Y = -4.5;
    /** Perpendicular wheel's forward offset from the robot center (inches, + = forward). */
    static final double PERP_X = -6.0;

    private final DcMotorEx par, perp;
    private final IMU imu;
    private int lastPar, lastPerp;
    private double lastYaw;
    private double headingOffset;
    private double x, y;

    public Localizer(DcMotorEx parEncoder, DcMotorEx perpEncoder, IMU imu) {
        this.par = parEncoder;
        this.perp = perpEncoder;
        this.imu = imu;
        lastPar = par.getCurrentPosition();
        lastPerp = perp.getCurrentPosition();
        lastYaw = yaw();
    }

    private double yaw() {
        return imu.getRobotYawPitchRollAngles().getYaw(AngleUnit.RADIANS);
    }

    /** Tell the localizer where the robot is (start of AUTO, or the pose AUTO ended at). */
    public void setPose(Pose pose) {
        x = pose.x;
        y = pose.y;
        lastYaw = yaw();
        headingOffset = pose.heading - lastYaw;
        lastPar = par.getCurrentPosition();
        lastPerp = perp.getCurrentPosition();
    }

    public Pose getPose() {
        return new Pose(x, y, AngleUnit.normalizeRadians(lastYaw + headingOffset));
    }

    public void update() {
        int parNow = par.getCurrentPosition(), perpNow = perp.getCurrentPosition();
        double yawNow = yaw();
        double dPar = (parNow - lastPar) * INCHES_PER_TICK;
        double dPerp = (perpNow - lastPerp) * INCHES_PER_TICK;
        double dTheta = AngleUnit.normalizeRadians(yawNow - lastYaw);
        lastPar = parNow;
        lastPerp = perpNow;

        // Remove the part of each wheel's motion that comes from the robot turning.
        double forward = dPar + PAR_Y * dTheta;
        double left = dPerp - PERP_X * dTheta;

        double heading = lastYaw + headingOffset + dTheta / 2;
        x += forward * Math.cos(heading) - left * Math.sin(heading);
        y += forward * Math.sin(heading) + left * Math.cos(heading);
        lastYaw = yawNow;
    }
}
