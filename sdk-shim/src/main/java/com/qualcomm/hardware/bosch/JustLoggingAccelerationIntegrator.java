package com.qualcomm.hardware.bosch;

import com.qualcomm.robotcore.util.RobotLog;

import org.firstinspires.ftc.robotcore.external.navigation.Acceleration;
import org.firstinspires.ftc.robotcore.external.navigation.Position;
import org.firstinspires.ftc.robotcore.external.navigation.Velocity;

/** Logs each acceleration and keeps the starting position and velocity (the SDK's sample integrator). */
public class JustLoggingAccelerationIntegrator implements BNO055IMU.AccelerationIntegrator {
    private BNO055IMU.Parameters parameters;
    private Position position = new Position();
    private Velocity velocity = new Velocity();
    private Acceleration acceleration;

    public JustLoggingAccelerationIntegrator() {}

    @Override
    public void initialize(BNO055IMU.Parameters parameters, Position initialPosition, Velocity initialVelocity) {
        this.parameters = parameters;
        this.position = initialPosition != null ? initialPosition : position;
        this.velocity = initialVelocity != null ? initialVelocity : velocity;
    }

    @Override public Position getPosition() { return position; }
    @Override public Velocity getVelocity() { return velocity; }
    @Override public Acceleration getAcceleration() { return acceleration; }

    @Override
    public void update(Acceleration linearAcceleration) {
        acceleration = linearAcceleration;
        if (parameters != null && parameters.loggingEnabled) RobotLog.vv(parameters.loggingTag, "acceleration: %s", linearAcceleration);
    }
}
