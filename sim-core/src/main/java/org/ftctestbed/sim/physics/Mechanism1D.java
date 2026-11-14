package org.ftctestbed.sim.physics;

import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.hardware.MotorPort;

import java.util.List;

/**
 * A one-degree-of-freedom mechanism driven by one or more motors:
 *   slide - linear travel from a spool (position in meters, 0 = retracted)
 *   arm   - rotation with gravity acting on a mass at armLength (radians, 0 = horizontal)
 *   free  - just inertia and friction, e.g. intake rollers or flywheels (radians)
 * The motor back-EMF term is integrated implicitly so stiff gearing stays stable.
 *
 * The motors' rotors count toward the inertia, multiplied by the gear ratio squared. Through a
 * gearbox they often outweigh the mechanism itself: a 435 rpm motor on a 38 mm spool feels
 * like about 2 kg of extra slide mass.
 */
public final class Mechanism1D {
    private static final double G = 9.81;

    public final RobotConfig.Mechanism def;
    private final MotorPort[] ports;
    private final double[] signs;
    private final double k;          // motor shaft rad per unit of mechanism position
    private final double inertia;    // kg (slide) or kg*m^2 (arm/free)

    private volatile double position;
    private volatile double velocity;
    private double batteryCurrent;
    private double externalTorque;

    public Mechanism1D(RobotConfig.Mechanism def, List<MotorPort> ports, List<Boolean> reversed) {
        this.def = def;
        this.ports = ports.toArray(new MotorPort[0]);
        this.signs = new double[this.ports.length];
        for (int i = 0; i < signs.length; i++) signs[i] = reversed.get(i) ? -1 : 1;
        double load;
        switch (def.type()) {
            case "slide" -> {
                k = def.gearRatio() / def.spoolRadius();
                load = def.mass();
            }
            case "arm" -> {
                k = def.gearRatio();
                load = def.mass() * def.armLength() * def.armLength() + def.inertia();
            }
            default -> {
                k = def.gearRatio();
                load = def.inertia();
            }
        }
        double rotors = 0;
        for (MotorPort p : this.ports) rotors += MotorPhysics.rotorInertia(p.spec) * k * k;
        inertia = load + rotors;
        reset();
    }

    public void reset() {
        position = def.type().equals("arm") ? def.startAngle() : 0;
        velocity = 0;
        externalTorque = 0;
    }

    public double position() { return position; }
    public double velocity() { return velocity; }

    /** Take kinetic energy out (e.g. a flywheel launching a ball). */
    public void removeEnergy(double joules) {
        double v = velocity;
        double e = 0.5 * inertia * v * v - joules;
        velocity = e <= 0 ? 0 : Math.copySign(Math.sqrt(2 * e / inertia), v);
    }
    public double batteryCurrent() { return batteryCurrent; }

    /**
     * A load on the mechanism for the next step, in its own units (N for a slide, N*m for an arm
     * or free mechanism; positive along its positive direction), e.g. an intake roller pulling a ball.
     */
    public void addLoad(double forceOrTorque) {
        externalTorque += forceOrTorque;
    }

    public double min() {
        return switch (def.type()) {
            case "slide" -> 0;
            case "arm" -> def.minAngle();
            default -> Double.NEGATIVE_INFINITY;
        };
    }

    public double max() {
        return switch (def.type()) {
            case "slide" -> def.travel();
            case "arm" -> def.maxAngle();
            default -> Double.POSITIVE_INFINITY;
        };
    }

    public void step(double dt, double batteryVolts) {
        double drive = 0, damping = 0, friction = 0;
        for (int i = 0; i < ports.length; i++) {
            MotorPort p = ports[i];
            drive += signs[i] * k * MotorPhysics.drive(p, batteryVolts);
            damping += MotorPhysics.damping(p) * k * k;
            friction += def.frictionFraction() * p.spec.stallTorqueNm() * k;
        }
        double gravity = switch (def.type()) {
            case "slide" -> def.vertical() ? -def.mass() * G : 0;
            case "arm" -> -def.mass() * G * def.armLength() * Math.cos(position);
            default -> 0;
        };

        double v = velocity;
        double fric = -friction * Math.tanh(v * k / MotorPhysics.FRICTION_SMOOTHING);
        // Static friction holds the mechanism if the net force can't overcome it.
        double net = drive + gravity + externalTorque;
        externalTorque = 0;
        if (Math.abs(v) < 1e-4 && Math.abs(net) <= friction) {
            v = 0;
        } else {
            v = (v + dt * (net + fric) / inertia) / (1 + dt * damping / inertia);
        }
        double pos = position + v * dt;
        if (pos < min()) { pos = min(); if (v < 0) v = 0; }
        if (pos > max()) { pos = max(); if (v > 0) v = 0; }
        position = pos;
        velocity = v;

        double total = 0;
        for (int i = 0; i < ports.length; i++) {
            double omega = signs[i] * k * v;
            double amps = MotorPhysics.current(ports[i], batteryVolts, omega);
            ports[i].integrate(omega, amps, dt);
            total += MotorPhysics.batteryCurrent(ports[i], amps);
        }
        batteryCurrent = total;
    }
}
