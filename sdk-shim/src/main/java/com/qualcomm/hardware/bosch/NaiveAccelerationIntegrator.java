package com.qualcomm.hardware.bosch;

import org.firstinspires.ftc.robotcore.external.navigation.Acceleration;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Position;
import org.firstinspires.ftc.robotcore.external.navigation.Velocity;

/**
 * Integrates acceleration twice, step by step (the SDK's default when no integrator is given).
 * Accelerometer noise makes the position drift away quickly, as on a real robot.
 */
public class NaiveAccelerationIntegrator implements BNO055IMU.AccelerationIntegrator {
    private BNO055IMU.Parameters parameters;
    private Position position = new Position();
    private Velocity velocity = new Velocity();
    private Acceleration acceleration;

    public NaiveAccelerationIntegrator() {}

    @Override
    public void initialize(BNO055IMU.Parameters parameters, Position initialPosition, Velocity initialVelocity) {
        this.parameters = parameters;
        this.position = initialPosition != null ? initialPosition.toUnit(DistanceUnit.METER) : new Position(DistanceUnit.METER, 0, 0, 0, 0);
        this.velocity = initialVelocity != null ? initialVelocity.toUnit(DistanceUnit.METER) : new Velocity(DistanceUnit.METER, 0, 0, 0, 0);
        this.acceleration = null;
    }

    @Override public Position getPosition() { return position; }
    @Override public Velocity getVelocity() { return velocity; }
    @Override public Acceleration getAcceleration() { return acceleration; }

    @Override
    public void update(Acceleration linearAcceleration) {
        if (linearAcceleration.acquisitionTime == 0) return;
        Acceleration a = linearAcceleration.toUnit(DistanceUnit.METER);
        if (acceleration != null) {
            double dt = (a.acquisitionTime - acceleration.acquisitionTime) * 1e-9;
            if (dt > 0) {
                Velocity v = new Velocity(DistanceUnit.METER,
                        velocity.xVeloc + (a.xAccel + acceleration.xAccel) / 2 * dt,
                        velocity.yVeloc + (a.yAccel + acceleration.yAccel) / 2 * dt,
                        velocity.zVeloc + (a.zAccel + acceleration.zAccel) / 2 * dt, a.acquisitionTime);
                position = new Position(DistanceUnit.METER,
                        position.x + (v.xVeloc + velocity.xVeloc) / 2 * dt,
                        position.y + (v.yVeloc + velocity.yVeloc) / 2 * dt,
                        position.z + (v.zVeloc + velocity.zVeloc) / 2 * dt, a.acquisitionTime);
                velocity = v;
            }
        }
        acceleration = a;
    }
}
