package org.ftctestbed.sim.game;

/**
 * A flying piece touching a solid surface (a field element, the robot). The piece is moved
 * out along the surface normal - not back to where it was - so it can slide and roll along
 * the surface; the speed into the surface bounces back with the restitution, or stops if it
 * is too slow to bounce (resting contact). Gravity then rolls it off slopes and edges as it
 * would really, instead of hovering in place.
 */
public final class Contact {
    /** Slower than this into a surface, a piece doesn't bounce: it rests or rolls on it. */
    public static final double BOUNCE_SPEED = 0.4;
    /** Share of sliding speed kept per bounce (impacts scrub speed off). */
    private static final double IMPACT_FRICTION = 0.85;
    /** Share of rolling speed kept per step of resting contact (a little rolling resistance). */
    private static final double ROLLING_KEEP = 0.998;

    private Contact() {}

    /**
     * n: outward unit normal of the surface (field frame); depth: how far the piece is inside it;
     * sv: velocity of the surface itself (moving robot), or null.
     */
    public static void resolve(GamePiece p, double[] n, double depth, double restitution, double[] sv) {
        p.setPosition(p.x() + n[0] * (depth + 1e-4), p.y() + n[1] * (depth + 1e-4), p.z() + n[2] * (depth + 1e-4));
        double svx = sv != null ? sv[0] : 0, svy = sv != null ? sv[1] : 0, svz = sv != null ? sv[2] : 0;
        double rx = p.vx() - svx, ry = p.vy() - svy, rz = p.vz() - svz;
        double vn = rx * n[0] + ry * n[1] + rz * n[2];
        if (vn >= 0) return; // already moving away
        double tx = rx - vn * n[0], ty = ry - vn * n[1], tz = rz - vn * n[2];
        boolean bounce = -vn > BOUNCE_SPEED;
        double keep = bounce ? IMPACT_FRICTION : ROLLING_KEEP;
        double out = bounce ? -restitution * vn : 0;
        p.setVelocity(svx + tx * keep + out * n[0], svy + ty * keep + out * n[1], svz + tz * keep + out * n[2]);
    }
}
