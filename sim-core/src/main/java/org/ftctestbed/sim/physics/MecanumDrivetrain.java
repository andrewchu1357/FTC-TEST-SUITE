package org.ftctestbed.sim.physics;

import org.dyn4j.dynamics.Body;
import org.dyn4j.geometry.Mass;
import org.dyn4j.geometry.Vector2;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.hardware.MotorPort;

/**
 * Wheel-force model for a mecanum drivetrain on foam tiles.
 *
 * Each wheel's contact roller can only push along its own axle (45 degrees to the wheel), so
 * the motor's torque turns into a force along that axle; the perpendicular direction rolls
 * freely, resisted only by roller friction. A wheel rolls without slipping until the force
 * it needs exceeds wheel friction * normal load. Then it breaks traction: it pushes with the
 * friction limit and its own speed (wheel + reflected motor rotor inertia) evolves separately
 * from the ground until it catches up and grips again. Encoders count the wheel's rotation,
 * so slip shows up as encoder error, as on a real robot.
 *
 * A gripping wheel turns in step with the ground, so its spinning parts (wheel and, through
 * the gearbox, the motor's rotor) speed up and slow down with the robot. Their inertia is
 * added to the robot body as extra mass and turning inertia: with 312 rpm (19.2:1) motors on
 * 104 mm wheels that is about 0.7 kg per wheel, so a 32 lb robot accelerates about 15% slower
 * than its bare mass suggests. A slipping wheel's inertia is left out (it spins on its own).
 *
 * Weight shifts between the wheels as the robot speeds up, brakes and turns: accelerating
 * forward unloads the front wheels by m*a*h/(2*wheelbase) each (h = center of gravity height,
 * which rises when a lift goes up). Each wheel's grip, roller drag and rolling resistance on
 * the foam tiles follow its own load. A wheel whose load reaches zero is lifting off the
 * tiles - the robot is starting to tip.
 *
 * Robot frame: x forward, y left. Standard "X" roller pattern seen from above.
 */
public final class MecanumDrivetrain {
    private static final double G = 9.81;
    private static final double ROLLER_COS = Math.cos(Math.PI / 4);
    private static final double ROLLER_SMOOTHING = 0.05; // m/s
    private static final double REGRIP_SPEED = 0.02;     // m/s of slip below which a wheel grips again
    /** The chassis and suspension take a moment to pitch and roll, so loads follow acceleration smoothly. */
    private static final double LOAD_TIME_CONSTANT = 0.04;
    /** Collisions give one-step acceleration spikes that the bumpers absorb; cap what loads see. */
    private static final double MAX_LOAD_ACCEL = 3 * 9.81;
    private static final double ROLL_SMOOTHING = 0.5;    // rad/s of wheel speed
    private static final double LIFT_FRACTION = 0.03;    // of the static load: below this a wheel is "lifting"

    public static final class Wheel {
        public final String label;
        public final double x, y;     // contact point, robot frame
        public final double ux, uy;   // roller axle direction (force direction), robot frame
        public final MotorPort port;
        public final double mountSign;
        final double inertia;         // wheel + motor rotor, kg*m^2 at the wheel
        volatile boolean slipping;
        volatile double load;         // N, normal force on the tiles
        double liftedFor;             // s the load has been ~0
        double omegaWheel;
        double motorCurrent;

        Wheel(String label, double x, double y, double ux, double uy, MotorPort port, boolean mountedReversed,
              double gearRatio, double wheelRadius) {
            this.label = label;
            this.x = x;
            this.y = y;
            double n = Math.hypot(ux, uy);
            this.ux = ux / n;
            this.uy = uy / n;
            this.port = port;
            this.mountSign = mountedReversed ? -1 : 1;
            double wheelMass = 0.3;
            this.inertia = MotorPhysics.rotorInertia(port.spec) * gearRatio * gearRatio
                    + 0.5 * wheelMass * wheelRadius * wheelRadius;
        }

        public boolean isSlipping() { return slipping; }

        /** Normal force on this wheel, N. */
        public double load() { return load; }

        /** True when the wheel has had (almost) no load for a moment: it is off the tiles. */
        public boolean isLifting() { return liftedFor > 0.1; }
    }

    private final Wheel[] wheels;
    private final double wheelRadius;
    private final double gearRatio;
    private final double wheelFriction;
    private final double rollerFriction;
    private final double gearboxFriction;
    private final double rollingResistance;
    private final double staticLoad;
    private final double robotMass;
    private final double wheelbase, trackWidth;
    private volatile double cgHeight;
    private double accX, accY;             // filtered acceleration, robot frame (m/s^2)
    private double lastVx, lastVy;
    private boolean haveLastVelocity;
    private final Mass bodyMass;
    private double addedMass = 0, addedInertia = 0;
    private double batteryCurrent;

    /** bodyMass: the robot's own mass and turning inertia (without the drivetrain's spinning parts). */
    public MecanumDrivetrain(RobotConfig.Drivetrain def, Mass bodyMass,
                             MotorPort fl, MotorPort fr, MotorPort bl, MotorPort br) {
        this.bodyMass = new Mass(bodyMass);
        this.robotMass = bodyMass.getMass();
        double a = def.wheelbase() / 2, b = def.trackWidth() / 2;
        wheelRadius = def.wheelRadius();
        gearRatio = def.gearRatio();
        wheels = new Wheel[]{
                new Wheel("FL", a, b, 1, -1, fl, def.frontLeft().mountedReversed(), gearRatio, wheelRadius),
                new Wheel("FR", a, -b, 1, 1, fr, def.frontRight().mountedReversed(), gearRatio, wheelRadius),
                new Wheel("BL", -a, b, 1, 1, bl, def.backLeft().mountedReversed(), gearRatio, wheelRadius),
                new Wheel("BR", -a, -b, 1, -1, br, def.backRight().mountedReversed(), gearRatio, wheelRadius),
        };
        wheelFriction = def.wheelFriction();
        rollerFriction = def.rollerFriction();
        gearboxFriction = def.gearboxFrictionFraction();
        rollingResistance = def.rollingResistance();
        wheelbase = def.wheelbase();
        trackWidth = def.trackWidth();
        staticLoad = robotMass * G / 4;
        for (Wheel wh : wheels) wh.load = staticLoad;
    }

    /** Height of the center of gravity above the tiles (changes as mechanisms move). */
    public void setCgHeight(double h) { cgHeight = h; }

    /** Filtered acceleration in the robot frame (x forward, y left), m/s^2. */
    public double accelX() { return accX; }
    public double accelY() { return accY; }

    /** The wheels and motors at rest (a power-cycled robot set down at its start). */
    public void stopWheels() {
        for (Wheel wh : wheels) {
            wh.omegaWheel = 0;
            wh.motorCurrent = 0;
        }
    }

    /** After the robot is picked up and put down: no load transfer, nothing lifting. */
    public void resetLoads() {
        accX = accY = 0;
        haveLastVelocity = false;
        for (Wheel wh : wheels) {
            wh.load = staticLoad;
            wh.liftedFor = 0;
            wh.slipping = false;
        }
    }

    /** Shares the robot's weight between the wheels from how it is accelerating. */
    private void updateLoads(Body body, double c, double s, double dt) {
        Vector2 v = body.getLinearVelocity();
        if (haveLastVelocity) {
            double axw = (v.x - lastVx) / dt, ayw = (v.y - lastVy) / dt;
            double ax = c * axw + s * ayw, ay = -s * axw + c * ayw;
            double mag = Math.hypot(ax, ay);
            if (mag > MAX_LOAD_ACCEL) {
                ax *= MAX_LOAD_ACCEL / mag;
                ay *= MAX_LOAD_ACCEL / mag;
            }
            double k = dt / (LOAD_TIME_CONSTANT + dt);
            accX += (ax - accX) * k;
            accY += (ay - accY) * k;
        }
        lastVx = v.x;
        lastVy = v.y;
        haveLastVelocity = true;

        double pitch = robotMass * accX * cgHeight / (2 * wheelbase);   // per wheel, moves from front to back
        double roll = robotMass * accY * cgHeight / (2 * trackWidth);   // per wheel, moves from left to right
        double total = 0;
        for (Wheel wh : wheels) {
            double n = staticLoad - Math.signum(wh.x) * pitch - Math.signum(wh.y) * roll;
            wh.load = Math.max(0, n);
            total += wh.load;
        }
        double scale = total > 0 ? 4 * staticLoad / total : 0; // weight is conserved when a wheel lifts
        for (Wheel wh : wheels) {
            wh.load *= scale;
            wh.liftedFor = wh.load < LIFT_FRACTION * staticLoad ? wh.liftedFor + dt : 0;
        }
    }

    /** Which wheels are off the tiles, e.g. "FL, FR", or null. */
    public String liftingWheels() {
        StringBuilder b = new StringBuilder();
        for (Wheel wh : wheels) {
            if (!wh.isLifting()) continue;
            if (b.length() > 0) b.append(", ");
            b.append(wh.label);
        }
        return b.length() > 0 ? b.toString() : null;
    }

    public Wheel[] wheels() { return wheels; }

    public double batteryCurrent() { return batteryCurrent; }

    /** Applies wheel forces to the robot body for the coming physics step. */
    public void applyForces(Body body, double batteryVolts, double dt) {
        double theta = body.getTransform().getRotationAngle();
        double c = Math.cos(theta), s = Math.sin(theta);
        Vector2 v = body.getLinearVelocity();
        double w = body.getAngularVelocity();
        Vector2 com = body.getWorldCenter();
        updateLoads(body, c, s, dt);
        double rk = wheelRadius * ROLLER_COS;
        double totalBattery = 0;

        for (Wheel wh : wheels) {
            double rx = c * wh.x - s * wh.y, ry = s * wh.x + c * wh.y;
            double vpx = v.x - w * ry, vpy = v.y + w * rx;
            double vrx = c * vpx + s * vpy, vry = -s * vpx + c * vpy;

            double vu = vrx * wh.ux + vry * wh.uy;
            double omegaGround = vu / rk;
            double load = wh.load;
            double maxForce = wheelFriction * load;
            // Rolling resistance of the wheel on the foam, as a torque at the wheel against its spin.
            double rollTorque = -rollingResistance * load * wheelRadius;
            MotorPort p = wh.port;
            double gs = gearRatio * wh.mountSign;
            double aT = MotorPhysics.drive(p, batteryVolts);
            double bT = MotorPhysics.damping(p);

            // Force the wheel would need to keep rolling with the ground.
            double omegaMotorGround = omegaGround * gs;
            double tauGround = aT - bT * omegaMotorGround + MotorPhysics.friction(p.spec, gearboxFriction, omegaMotorGround);
            double fuGround = (tauGround * gs + rollTorque * Math.tanh(omegaGround / ROLL_SMOOTHING)) / rk;

            double fu;
            double omegaWheel;
            if (wh.slipping) {
                double vSlip = wh.omegaWheel * rk - vu;
                if (Math.abs(vSlip) < REGRIP_SPEED && Math.abs(fuGround) <= maxForce) {
                    wh.slipping = false;
                }
            } else if (Math.abs(fuGround) > maxForce) {
                wh.slipping = true;
                wh.omegaWheel = omegaGround;
            }

            if (wh.slipping) {
                double vSlip = wh.omegaWheel * rk - vu;
                fu = maxForce * Math.tanh(vSlip / REGRIP_SPEED);
                // Wheel spin: J dw/dt = gear*sign*(a - b*w*gear*sign - friction) - fu*r*cos45 (back-EMF implicit).
                double omegaMotor = wh.omegaWheel * gs;
                double drive = gs * (aT + MotorPhysics.friction(p.spec, gearboxFriction, omegaMotor)) - fu * rk
                        + rollTorque * Math.tanh(wh.omegaWheel / ROLL_SMOOTHING);
                wh.omegaWheel = (wh.omegaWheel + dt * drive / wh.inertia) / (1 + dt * gearRatio * gearRatio * bT / wh.inertia);
                omegaWheel = wh.omegaWheel;
            } else {
                fu = fuGround;
                omegaWheel = omegaGround;
                wh.omegaWheel = omegaGround;
            }
            double omegaMotor = omegaWheel * gs;

            // Free-rolling direction: rollers spin only when the contact point moves
            // differently from the hub-driven surface velocity.
            double px = -wh.uy, py = wh.ux;
            double vFree = (vrx - omegaWheel * wheelRadius) * px + vry * py;
            double fFree = -rollerFriction * load * Math.tanh(vFree / ROLLER_SMOOTHING);

            double frx = wh.ux * fu + px * fFree, fry = wh.uy * fu + py * fFree;
            double fwx = c * frx - s * fry, fwy = s * frx + c * fry;
            body.applyForce(new Vector2(fwx, fwy), new Vector2(com.x + rx, com.y + ry));

            wh.motorCurrent = MotorPhysics.current(p, batteryVolts, omegaMotor);
            totalBattery += MotorPhysics.batteryCurrent(p, wh.motorCurrent);
        }
        batteryCurrent = totalBattery;
        updateRotatingMass(body, rk);
    }

    /**
     * Adds the gripping wheels' inertia to the body. Each wheel resists acceleration along its
     * roller axle like a mass J / (r cos 45)^2; with the X roller pattern the four together act
     * the same in every direction, so each contributes half of that to both x and y.
     */
    private void updateRotatingMass(Body body, double rk) {
        double m = 0, inertia = 0;
        for (Wheel wh : wheels) {
            if (wh.slipping) continue;
            double along = wh.inertia / (rk * rk);
            double lever = wh.x * wh.uy - wh.y * wh.ux;
            m += along / 2;
            inertia += along * lever * lever;
        }
        if (m == addedMass && inertia == addedInertia) return;
        addedMass = m;
        addedInertia = inertia;
        body.setMass(new Mass(bodyMass.getCenter(), bodyMass.getMass() + m, bodyMass.getInertia() + inertia));
    }

    /** After the physics step: turn wheels at the speed the robot actually moved, update encoders. */
    public void updateEncoders(Body body, double dt) {
        double theta = body.getTransform().getRotationAngle();
        double c = Math.cos(theta), s = Math.sin(theta);
        Vector2 v = body.getLinearVelocity();
        double w = body.getAngularVelocity();
        for (Wheel wh : wheels) {
            double omegaWheel = wh.omegaWheel;
            if (!wh.slipping) {
                double rx = c * wh.x - s * wh.y, ry = s * wh.x + c * wh.y;
                double vpx = v.x - w * ry, vpy = v.y + w * rx;
                double vrx = c * vpx + s * vpy, vry = -s * vpx + c * vpy;
                omegaWheel = (vrx * wh.ux + vry * wh.uy) / (wheelRadius * ROLLER_COS);
            }
            wh.port.integrate(omegaWheel * gearRatio * wh.mountSign, wh.motorCurrent, dt);
        }
    }
}
