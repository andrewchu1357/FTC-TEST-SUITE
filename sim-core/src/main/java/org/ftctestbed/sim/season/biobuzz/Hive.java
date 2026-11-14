package org.ftctestbed.sim.season.biobuzz;

import org.ftctestbed.sim.game.Contact;
import org.ftctestbed.sim.game.FlightObstacle;
import org.ftctestbed.sim.game.FlightTarget;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.game.GamePieces;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.ftctestbed.sim.season.biobuzz.BioBuzzField.*;

/**
 * One alliance's HIVE: two CELLS on a pivot, simulated as a rigid body turning about it. It
 * rests against a stop on either side, 30 degrees from level. The structure's own center of
 * mass is a little above the pivot, so its weight holds it on whichever side it leans to; the
 * pieces in the upward CELL pull the other way. When their torque beats the structure's plus
 * the bearings' friction it starts to swing, slowly at first and then faster (past level the
 * structure helps), the dampers catch it over the last few degrees, and the pieces roll out of
 * the CELL that went down as soon as its floor slopes toward the opening.
 *
 * How much weight tips it comes from field.jsonc tipThresholdG: that many grams of POLLEN
 * resting against the back of the CELL just tip it, which fixes the structure's holding torque.
 * Where pieces sit matters (a second row is further out and pulls harder), every piece has its
 * own mass, and a piece that lands hard gives the HIVE a kick (its momentum about the pivot).
 */
final class Hive implements FlightTarget, FlightObstacle {
    private static final double G = GamePieces.G;
    private static final double REST = CELL_TILT;
    /**
     * [ESTIMATE] Share of the tipping torque taken by friction: the pivot's ball bearings (am-4457)
     * hardly have any, so this is mostly the dampers' seals.
     */
    static final double FRICTION_SHARE = 0.01;
    /** The Blumotion 970A dampers' stroke as an angle before each stop (about 10 mm, 75 mm from the pivot). */
    static final double DAMPER_RANGE = Math.toRadians(8);
    /** [ESTIMATE] Damping in the dampers' stroke, per unit of the HIVE's inertia (1/s): soft-close. */
    static final double DAMPER_PER_INERTIA = 8.0;
    /** [ESTIMATE] Share of a landing piece's angular momentum the HIVE takes (the rest goes into the bounce). */
    static final double IMPACT_SHARE = 0.5;
    /** A CELL floor sloping out more than this lets its pieces roll out. */
    private static final double ROLL_OUT_SLOPE = Math.toRadians(2);
    /** A hollow ball rolling without slipping: a = g sin(slope) / (1 + 2/3). */
    private static final double ROLL_ACCEL = G / (1 + 2.0 / 3.0);
    private static final double STOP_RESTITUTION = 0.15;
    /** The opening still faces up enough to take a piece while the CELL is tilted at least this far up. */
    private static final double OPEN_ABOVE = Math.toRadians(12);

    final String alliance;
    final double x;
    /** +1: the far CELL is up, -1: the audience CELL (the side it rests on, until a tip completes). */
    int upSide;
    final List<Slot> contents = new ArrayList<>();
    int tipsAuto, tipsTeleop;
    private final GamePieces pieces;
    private final double thresholdTorque;   // N m, at rest
    private final double structureMoment;   // kg m: structure mass x its center of mass's height above the pivot
    private final double friction;          // N m
    private final double refRadius;
    private final int packFrom;              // pieces settle against the CELL side wall toward this x
    double inertia;                          // kg m^2, the structure alone
    double angle;                            // the far CELL's tilt above level, rad
    double omega;
    boolean resting = true;
    Runnable onTip = () -> {};
    java.util.function.BooleanSupplier countsAsAuto = () -> false;

    /** A piece in a CELL: where it is in the HIVE's frame, and how fast it's rolling out. */
    static final class Slot {
        final GamePiece p;
        final int side;
        double cx, u, w;
        double roll;

        Slot(GamePiece p, int side) {
            this.p = p;
            this.side = side;
        }
    }

    /**
     * thresholdKg: weight of POLLEN (radius refRadius) against the back of the upward CELL that
     * just tips it; inertia: the structure's moment of inertia about the pivot.
     */
    Hive(String alliance, int initialUpSide, GamePieces pieces, double thresholdKg, double refRadius, double inertia) {
        this.alliance = alliance;
        this.x = hiveX(alliance);
        this.pieces = pieces;
        this.refRadius = refRadius;
        this.packFrom = alliance.equals("red") ? -1 : 1; // as in the CAD: against the wall nearer their alliance
        this.thresholdTorque = thresholdKg * G * restLever(refRadius);
        this.friction = FRICTION_SHARE * thresholdTorque;
        this.structureMoment = thresholdTorque * (1 - FRICTION_SHARE) / (G * Math.sin(REST));
        this.inertia = inertia;
        reset(initialUpSide);
    }

    /** Horizontal distance from the pivot of a ball against the back of the upward CELL. */
    static double restLever(double r) {
        return (CELL_INNER + r) * Math.cos(REST) - (CELL_FLOOR + r) * Math.sin(REST);
    }

    @Override
    public String name() {
        return alliance + " HIVE";
    }

    double damper() {
        return DAMPER_PER_INERTIA * inertia;
    }

    /** How far a CELL is tilted up (negative: its floor slopes down toward its opening). */
    double elevation(int side) {
        return side * angle;
    }

    /** Swinging over (not just rocking against its stop). */
    boolean tipping() {
        return !resting && Math.abs(angle - upSide * REST) > Math.toRadians(1);
    }

    double tiltDegrees() {
        return Math.toDegrees(angle);
    }

    /** Pieces in the upward CELL. */
    List<GamePiece> upContents() {
        List<GamePiece> out = new ArrayList<>();
        for (Slot c : contents) if (c.side == upSide) out.add(c.p);
        return out;
    }

    /** CELL points count the pieces in the upward CELL while the HIVE is at rest. */
    int scoredCount() {
        return tipping() ? 0 : upContents().size();
    }

    double massInCell() {
        double m = 0;
        for (GamePiece p : upContents()) m += p.kind.mass();
        return m;
    }

    /** The upward CELL's pull as a share of what tips the HIVE (1 = tips). */
    double fill() {
        double t = 0;
        double a = upSide * REST;
        for (Slot c : contents) {
            if (c.side == upSide) t += c.p.kind.mass() * G * (c.u * Math.cos(a) - c.w * Math.sin(a)) * upSide;
        }
        return t / thresholdTorque;
    }

    void reset(int initialUpSide) {
        upSide = initialUpSide;
        angle = initialUpSide * REST;
        omega = 0;
        resting = true;
        contents.clear();
        tipsAuto = tipsTeleop = 0;
    }

    /** A piece placed in the upward CELL before the match. */
    void preload(GamePiece p) {
        pieces.contain(p, this, x, 0, PIVOT_HEIGHT, name());
        Slot c = new Slot(p, upSide);
        contents.add(c);
        pack();
        place(c);
    }

    /**
     * Where the resting pieces in the upward CELL sit: they roll down its sloped floor to the
     * back skin and settle side by side against a side wall, then in rows further out.
     */
    private void pack() {
        double rowBack = 0, rowDepth = 0, used = 0;
        for (Slot c : contents) {
            if (c.side != upSide || c.roll > 0) continue;
            double r = c.p.kind.radius();
            if (used + 2 * r > 2 * CELL_HALF_WIDTH + 1e-9) {
                rowBack += rowDepth * 0.87; // the next row nests in the gaps of this one
                used = 0;
                rowDepth = 0;
            }
            c.cx = packFrom * (CELL_HALF_WIDTH - used - r);
            c.u = c.side * (CELL_INNER + rowBack + r);
            c.w = CELL_FLOOR + r;
            used += 2 * r;
            rowDepth = Math.max(rowDepth, 2 * r);
        }
    }

    // ------------------------------------------------------------------ frames

    /** {x across, u along, w up} in the HIVE frame of a field point. */
    private double[] toHive(double px, double py, double pz) {
        double c = Math.cos(angle), s = Math.sin(angle), z = pz - PIVOT_HEIGHT;
        return new double[]{px - x, py * c + z * s, -py * s + z * c};
    }

    private void place(Slot c) {
        double co = Math.cos(angle), s = Math.sin(angle);
        c.p.setPosition(x + c.cx, c.u * co - c.w * s, PIVOT_HEIGHT + c.u * s + c.w * co);
    }

    private double contentsTorque() {
        double co = Math.cos(angle), s = Math.sin(angle), t = 0;
        for (Slot c : contents) t -= c.p.kind.mass() * G * (c.u * co - c.w * s);
        return t;
    }

    private double contentsInertia() {
        double i = 0;
        for (Slot c : contents) i += c.p.kind.mass() * (c.u * c.u + c.w * c.w);
        return i;
    }

    // ------------------------------------------------------------------ dynamics

    void step(double dt) {
        rollOut(dt);
        double tau = structureMoment * G * Math.sin(angle) + contentsTorque();
        if (resting) {
            // Pressed against the stop on its upSide: it stays unless the pull away beats the friction.
            if (-upSide * tau <= friction) {
                omega = 0;
                return;
            }
            resting = false;
        }
        double total = inertia + contentsInertia();
        double fr;
        if (Math.abs(omega) > 1e-3) {
            fr = -Math.signum(omega) * friction;
        } else if (Math.abs(tau) <= friction) {
            omega = 0; // static friction holds it where it is
            return;
        } else {
            fr = -Math.signum(tau) * friction;
        }
        double damp = 0;
        int toward = omega > 0 ? 1 : -1;
        if (omega != 0 && Math.abs(angle - toward * REST) < DAMPER_RANGE) damp = -damper() * omega;
        omega += (tau + fr + damp) / total * dt;
        angle += omega * dt;
        if (Math.abs(angle) >= REST) {
            int stop = angle > 0 ? 1 : -1;
            angle = stop * REST;
            if (omega * stop > 0) omega = -STOP_RESTITUTION * omega;
            if (Math.abs(omega) < 0.05) {
                omega = 0;
                resting = true;
                arrive(stop);
            }
        }
        for (Slot c : contents) place(c);
    }

    /** Came to rest against the stop on this side. */
    private void arrive(int stop) {
        if (stop == upSide) return; // rocked and fell back
        upSide = stop;
        if (countsAsAuto.getAsBoolean()) tipsAuto++;
        else tipsTeleop++;
        onTip.run();
    }

    /** Pieces in a CELL whose floor slopes toward its opening roll out of it and fall. */
    private void rollOut(double dt) {
        for (Iterator<Slot> it = contents.iterator(); it.hasNext(); ) {
            Slot c = it.next();
            double slope = -elevation(c.side);
            if (slope <= ROLL_OUT_SLOPE && c.roll <= 0) continue;
            c.roll = Math.max(0, c.roll + ROLL_ACCEL * Math.sin(slope) * dt);
            c.u += c.side * c.roll * dt;
            double r = c.p.kind.radius();
            if (c.side * c.u >= CELL_OUTER) {
                it.remove();
                release(c, r);
            } else {
                place(c);
            }
        }
    }

    /** Off the CELL's lip: falling with the HIVE's swing plus its own roll. */
    private void release(Slot c, double r) {
        double co = Math.cos(angle), s = Math.sin(angle);
        double u = c.side * (CELL_OUTER + r + 0.002);
        double y = u * co - c.w * s, z = u * s + c.w * co;
        double vy = -omega * z + c.side * c.roll * co, vz = omega * y + c.side * c.roll * s;
        pieces.launch(c.p, x + c.cx, y, PIVOT_HEIGHT + z, 0, vy, vz, false, null);
        c.p.setFromTip(true);
    }

    // ------------------------------------------------------------------ pieces arriving

    @Override
    public boolean tryCapture(GamePiece p, double x0, double y0, double z0) {
        int s = upSide;
        if (elevation(s) < OPEN_ABOVE) return false;
        double[] a = toHive(x0, y0, z0), b = toHive(p.x(), p.y(), p.z());
        double d0 = s * a[1] - CELL_OUTER, d1 = s * b[1] - CELL_OUTER;
        if (!(d0 > 0 && d1 <= 0)) return false;
        double t = d0 / (d0 - d1);
        double hx = a[0] + t * (b[0] - a[0]), hw = a[2] + t * (b[2] - a[2]);
        double r = p.kind.radius();
        if (Math.abs(hx) > CELL_HALF_WIDTH - 0.5 * r || hw < CELL_FLOOR + 0.5 * r || hw > CELL_ROOF - 0.5 * r) return false;

        double before = inertia + contentsInertia();
        Slot c = new Slot(p, s);
        contents.add(c);
        pack();
        if (s * c.u > CELL_OUTER - r) { // the CELL is full to its opening
            contents.remove(c);
            return false;
        }
        // Its momentum about the pivot (partly) goes into the HIVE.
        double zRel = p.z() - PIVOT_HEIGHT;
        double kick = IMPACT_SHARE * p.kind.mass() * (p.y() * p.vz() - zRel * p.vy());
        pieces.contain(p, this, p.x(), p.y(), p.z(), name());
        place(c);
        omega = (before * omega + kick) / (inertia + contentsInertia());
        if (resting) {
            if (omega * upSide > 0) omega = 0; // pushed into its stop
            else if (omega != 0) resting = false;
        }
        return true;
    }

    /**
     * Pieces that run into the outside of either CELL (or clip the rim of the opening) bounce off,
     * or come to rest and roll along it (see Contact): a ball on a CELL's sloped top rolls off.
     * The CELLS move with the HIVE, so a swinging HIVE hits pieces too.
     */
    @Override
    public void collide(GamePiece p, double x0, double y0, double z0) {
        double r = p.kind.radius();
        double e = Math.min(p.kind.restitution(), 0.4); // the CELLS' plastic panels flex
        double[] a = toHive(x0, y0, z0), b = toHive(p.x(), p.y(), p.z());
        for (int side : new int[]{upSide, -upSide}) {
            // The upward CELL's opening isn't a wall: pieces go in through it (see tryCapture).
            boolean open = side == upSide && elevation(side) >= OPEN_ABOVE;
            double innerFace = CELL_INNER - r, outerFace = CELL_OUTER + (open ? 0 : r);
            double[] lo = {-CELL_HALF_WIDTH - r, side > 0 ? innerFace : -outerFace, CELL_FLOOR - r};
            double[] hi = {CELL_HALF_WIDTH + r, side > 0 ? outerFace : -innerFace, CELL_ROOF + r};
            if (!inside(b, lo, hi)) continue;
            // The face it came in through (the last one it crossed), or the nearest one.
            int k = -1;
            double latest = -1;
            for (int i = 0; i < 3; i++) {
                double bound = a[i] < lo[i] ? lo[i] : a[i] > hi[i] ? hi[i] : Double.NaN;
                if (Double.isNaN(bound) || b[i] == a[i]) continue;
                double t = (bound - a[i]) / (b[i] - a[i]);
                if (t > latest) {
                    latest = t;
                    k = i;
                }
            }
            double sign;
            if (k >= 0) {
                sign = a[k] < lo[k] ? -1 : 1;
            } else {
                double best = Double.MAX_VALUE;
                sign = 1;
                for (int i = 0; i < 3; i++) {
                    if (b[i] - lo[i] < best) { best = b[i] - lo[i]; k = i; sign = -1; }
                    if (hi[i] - b[i] < best) { best = hi[i] - b[i]; k = i; sign = 1; }
                }
            }
            double depth = sign > 0 ? hi[k] - b[k] : b[k] - lo[k];
            double co = Math.cos(angle), s = Math.sin(angle);
            double[] n = switch (k) {
                case 0 -> new double[]{sign, 0, 0};
                case 1 -> new double[]{0, sign * co, sign * s};
                default -> new double[]{0, -sign * s, sign * co};
            };
            double zRel = p.z() - PIVOT_HEIGHT;
            Contact.resolve(p, n, Math.max(0, depth), e, new double[]{0, -omega * zRel, omega * p.y()});
            return;
        }
    }

    private static boolean inside(double[] v, double[] lo, double[] hi) {
        for (int i = 0; i < 3; i++) if (v[i] <= lo[i] || v[i] >= hi[i]) return false;
        return true;
    }

    // ------------------------------------------------------------------ calibration

    /**
     * Seconds from starting to move to resting on the other side, with the tipping weight plus
     * one POLLEN (pollenKg) against the back of the upward CELL, for a structure inertia.
     */
    static double tipSeconds(double thresholdKg, double pollenKg, double refRadius, double inertia) {
        double lever = restLever(refRadius);
        double thresholdTorque = thresholdKg * G * lever;
        double friction = FRICTION_SHARE * thresholdTorque;
        double moment = thresholdTorque * (1 - FRICTION_SHARE) / (G * Math.sin(REST));
        double m = thresholdKg + pollenKg, u = CELL_INNER + refRadius, w = CELL_FLOOR + refRadius;
        double total = inertia + m * (u * u + w * w);
        double a = REST, om = 0, t = 0, dt = 0.0005;
        while (t < 20) {
            double tau = moment * G * Math.sin(a) - m * G * (u * Math.cos(a) - w * Math.sin(a));
            double damp = om < 0 && a + REST < DAMPER_RANGE ? -DAMPER_PER_INERTIA * inertia * om : 0;
            om += (tau + (om < 0 ? friction : 0) + damp) / total * dt;
            a += om * dt;
            t += dt;
            if (a <= -REST) return t;
        }
        return t;
    }

    /** The structure inertia that makes tipSeconds come out at seconds. */
    static double inertiaForTipSeconds(double seconds, double thresholdKg, double pollenKg, double refRadius) {
        double lo = 0.005, hi = 5;
        for (int i = 0; i < 40; i++) {
            double mid = Math.sqrt(lo * hi);
            if (tipSeconds(thresholdKg, pollenKg, refRadius, mid) < seconds) lo = mid;
            else hi = mid;
        }
        return Math.sqrt(lo * hi);
    }
}
