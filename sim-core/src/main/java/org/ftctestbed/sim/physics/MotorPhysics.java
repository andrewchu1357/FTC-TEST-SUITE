package org.ftctestbed.sim.physics;

import org.ftctestbed.sim.config.MotorSpec;
import org.ftctestbed.sim.hardware.MotorPort;

/**
 * Linear brushed-DC motor model at the output shaft:
 *   torque = stallTorque * (V / 12 - omega / freeSpeed)
 * which is written as torque = a - b * omega so callers can integrate the back-EMF term
 * implicitly. With the H-bridge open (FLOAT at zero power) both terms are zero; BRAKE at
 * zero power shorts the motor, giving pure back-EMF braking (a = 0, b > 0).
 */
public final class MotorPhysics {
    /** Smoothing speed for Coulomb friction, rad/s at the output shaft. */
    public static final double FRICTION_SMOOTHING = 0.5;

    private MotorPhysics() {}

    public static double drive(MotorPort port, double batteryVolts) {
        if (!port.isEnergized()) return 0;
        return port.spec.stallTorqueNm() * port.appliedDuty() * batteryVolts / MotorSpec.NOMINAL_VOLTS;
    }

    public static double damping(MotorPort port) {
        if (!port.isEnergized()) return 0;
        return port.spec.stallTorqueNm() / port.spec.freeSpeedRadPerSec();
    }

    /**
     * Rotor inertia reflected to the output shaft, estimated from a mechanical time constant
     * (time to reach 63% of free speed unloaded) typical of FTC gearmotors.
     */
    public static double rotorInertia(MotorSpec spec) {
        double mechanicalTimeConstant = 0.02;
        return mechanicalTimeConstant * spec.stallTorqueNm() / spec.freeSpeedRadPerSec();
    }

    /** Gearbox/bearing drag, opposing rotation. */
    public static double friction(MotorSpec spec, double frictionFraction, double omega) {
        return -frictionFraction * spec.stallTorqueNm() * Math.tanh(omega / FRICTION_SMOOTHING);
    }

    /** Motor winding current (A) for a given torque-producing state. */
    public static double current(MotorPort port, double batteryVolts, double omega) {
        if (!port.isEnergized()) return 0;
        MotorSpec s = port.spec;
        double frac = port.appliedDuty() * batteryVolts / MotorSpec.NOMINAL_VOLTS - omega / s.freeSpeedRadPerSec();
        return Math.abs(frac * s.stallCurrentA()) + (omega != 0 ? s.freeCurrentA() : 0);
    }

    /** Current drawn from the battery (PWM duty times winding current). */
    public static double batteryCurrent(MotorPort port, double motorCurrent) {
        return Math.abs(port.appliedDuty()) * motorCurrent;
    }
}
