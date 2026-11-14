package org.ftctestbed.sim.physics;

import org.dyn4j.dynamics.Body;
import org.dyn4j.geometry.Vector2;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.hardware.EncoderSource;

import java.util.Random;

/**
 * An unpowered tracking wheel that measures how far its contact point moves along its axis.
 *
 * Like a real pod it isn't perfect (unless the config says "ideal"): it rolls on its real
 * diameter, not the nominal one robot code assumes; its axis is turned a little from where it
 * was meant to point, so it also sees a sliver of sideways motion; it skips a fraction of a
 * millimeter crossing a tile seam at speed; it under-reads while the robot accelerates or
 * brakes hard (the chassis pitches and unloads it); and a hard hit (a wall, another robot) jolts
 * it by a few millimeters. All [ESTIMATE]s: no published measurements exist, so the sizes are
 * set so a tuned localizer drifts as much as teams report (about 1-2 in over a 30 s auto).
 */
public final class OdometryPodSim implements EncoderSource {
    /** Seams are skipped above this speed, fully from SEAM_FULL_SPEED (m/s). */
    static final double SEAM_MIN_SPEED = 0.5, SEAM_FULL_SPEED = 1.0;
    /** Distance lost per seam crossing at full speed (m). */
    static final double SEAM_LOSS = 0.0002;
    /** Above this acceleration along the pod the chassis unloads it (m/s^2)... */
    static final double SLIP_ACCEL = 0.8 * 9.81;
    /** ...and it under-reads this fraction of the distance travelled meanwhile. */
    static final double SLIP_FRACTION = 0.002;
    /** A change in velocity this sudden is a collision (m/s^2): the pod jumps by IMPACT_JUMP sigma (m). */
    static final double IMPACT_ACCEL = 4 * 9.81, IMPACT_JUMP = 0.005;

    public final RobotConfig.OdometryPod def;
    private final double axisX, axisY;       // measuring direction in the robot frame
    private final double metersPerTick;       // the real wheel's
    private final double tile;               // seam spacing (m)
    private final double halfField;
    private Random random;
    private volatile double ticks = 0;
    private volatile double tps = 0;
    private double lastVx, lastVy, impactCooldown;
    private int lastSeamX = Integer.MIN_VALUE, lastSeamY = Integer.MIN_VALUE;

    public OdometryPodSim(RobotConfig.OdometryPod def) {
        this(def, 6 * 24 * 0.0254);
    }

    /** fieldSize: the field's width (m); its 6 x 6 tiles set where the seams are. */
    public OdometryPodSim(RobotConfig.OdometryPod def, double fieldSize) {
        this.def = def;
        double base = def.lateral() ? Math.PI / 2 : 0;
        double a = base + (def.ideal() ? 0 : def.mountAngle());
        axisX = Math.cos(a);
        axisY = Math.sin(a);
        double realRadius = def.wheelRadius() * (def.ideal() ? 1 : def.diameterScale());
        metersPerTick = 2 * Math.PI * realRadius / def.ticksPerRev();
        tile = fieldSize / 6;
        halfField = fieldSize / 2;
        random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(def.name().hashCode() * 31L + 7));
    }

    public void update(Body body, double dt) {
        double theta = body.getTransform().getRotationAngle();
        double c = Math.cos(theta), s = Math.sin(theta);
        Vector2 v = body.getLinearVelocity();
        double w = body.getAngularVelocity();
        double rx = c * def.x() - s * def.y(), ry = s * def.x() + c * def.y();
        double vpx = v.x - w * ry, vpy = v.y + w * rx;           // contact point velocity, field frame
        double vrx = c * vpx + s * vpy, vry = -s * vpx + c * vpy; // ... in the robot frame
        double along = vrx * axisX + vry * axisY;
        double distance = along * dt;

        if (!def.ideal() && dt > 0) {
            // Hard acceleration along the pod: the chassis pitches and the pod under-reads.
            double ax = (vpx - lastVx) / dt, ay = (vpy - lastVy) / dt;
            double accelAlong = Math.abs((c * ax + s * ay) * axisX + (-s * ax + c * ay) * axisY);
            if (accelAlong > SLIP_ACCEL && accelAlong < IMPACT_ACCEL) distance *= 1 - SLIP_FRACTION;
            // A collision jolts it.
            impactCooldown = Math.max(0, impactCooldown - dt);
            if (Math.hypot(ax, ay) > IMPACT_ACCEL && impactCooldown == 0 && lastVx * lastVx + lastVy * lastVy > 0.01) {
                distance += random.nextGaussian() * IMPACT_JUMP;
                impactCooldown = 0.2;
            }
            // Tile seams: a short skip each time the contact point crosses one at speed.
            double px = body.getTransform().getTranslationX() + rx, py = body.getTransform().getTranslationY() + ry;
            int seamX = (int) Math.floor((px + halfField) / tile), seamY = (int) Math.floor((py + halfField) / tile);
            int crossings = (lastSeamX == Integer.MIN_VALUE ? 0 : Math.abs(seamX - lastSeamX) + Math.abs(seamY - lastSeamY));
            lastSeamX = seamX;
            lastSeamY = seamY;
            double speed = Math.abs(along);
            if (crossings > 0 && speed > SEAM_MIN_SPEED) {
                double loss = SEAM_LOSS * Math.min(1, (speed - SEAM_MIN_SPEED) / (SEAM_FULL_SPEED - SEAM_MIN_SPEED)) * crossings;
                distance -= Math.copySign(Math.min(loss, Math.abs(distance)), distance);
            }
        }
        lastVx = vpx;
        lastVy = vpy;

        double sign = def.reversed() ? -1 : 1;
        tps = along / metersPerTick * sign;
        ticks += distance / metersPerTick * sign;
    }

    /** Placed somewhere new (power cycle or moved by hand): no seam crossing or collision from the jump. */
    /** The robot was reset (power-cycled): the same noise as the first time. */
    public void powerCycle() {
        random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(def.name().hashCode() * 31L + 7));
        resetMotion();
    }

    public void resetMotion() {
        lastSeamX = Integer.MIN_VALUE;
        lastSeamY = Integer.MIN_VALUE;
        lastVx = 0;
        lastVy = 0;
    }

    @Override public double ticks() { return ticks; }
    @Override public double ticksPerSecond() { return tps; }
}
