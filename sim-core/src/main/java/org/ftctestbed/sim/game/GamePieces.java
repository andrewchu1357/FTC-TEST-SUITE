package org.ftctestbed.sim.game;

import org.dyn4j.dynamics.Body;
import org.dyn4j.dynamics.BodyFixture;
import org.dyn4j.geometry.Geometry;
import org.dyn4j.geometry.Mass;
import org.dyn4j.geometry.MassType;
import org.dyn4j.geometry.Vector2;
import org.ftctestbed.sim.physics.FieldWorld;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * All game pieces on (and around) the field. Pieces on the floor are dyn4j bodies; flying
 * pieces are integrated here in 3D with gravity, air drag and the Magnus force from their spin
 * ("2.5D": the 2D world has no height, so anything off the floor is handled outside dyn4j).
 *
 * Magnus: a spinning ball drags air around with it, pushing it sideways to its path toward the
 * side spinning with the air (backspin lifts). The lift coefficient grows with the spin ratio
 * S = radius * spin / speed and levels off; spin decays as air friction slows the ball's surface.
 */
public final class GamePieces {
    public static final double G = 9.81;
    private static final double IN = 0.0254;
    private static final double AIR_DENSITY = 1.2;
    private static final double WALL_HEIGHT = 12 * IN;
    private static final double SETTLE_SPEED = 0.7;     // m/s vertical; slower landings stop bouncing
    private static final double OFF_FIELD_RESPAWN = 3.0; // seconds before a lost neutral piece comes back
    private static final int MAX_TRAJECTORIES = 6;
    /**
     * Nothing on the field can send a piece along the tiles faster than this (a robot at full speed
     * hitting a ball sends it off at about 1.5x its own speed). Caps the rare pinball when a piece
     * is squeezed between the robot and a wall and the collision solver pushes it out hard.
     */
    private static final double MAX_FLOOR_SPEED = 3.5;

    /** A recorded flight path, for the viewer's side view. */
    public static final class Trajectory {
        public final int pieceId;
        public final int color;
        public final double startTime;
        public final List<double[]> points = new ArrayList<>();
        public volatile String outcome = "in flight";
        public volatile double[] aimPoint;

        Trajectory(int pieceId, int color, double startTime) {
            this.pieceId = pieceId;
            this.color = color;
            this.startTime = startTime;
        }
    }

    private final FieldWorld field;
    private final double half;
    private final List<GamePiece> pieces = new ArrayList<>();
    private final List<FlightTarget> targets = new ArrayList<>();
    private final List<FlightObstacle> obstacles = new ArrayList<>();
    private final Deque<Trajectory> trajectories = new ArrayDeque<>();
    private final List<double[]> pendingRespawn = new ArrayList<>(); // {id, timeLeft}
    private Consumer<GamePiece> offFieldHandler;
    private java.util.function.UnaryOperator<double[]> floorGuard = xy -> xy;
    private double dragCoefficient = 0.6;
    private double magnusLift = 0.25;      // lift coefficient the spin ratio levels off at
    private double spinDecaySeconds = 2.0;
    private double time;
    private int nextId = 1;
    private PieceKind.Variation variation = PieceKind.Variation.NONE;
    private long variationSeed = 1;

    public GamePieces(FieldWorld field, double fieldSize) {
        this.field = field;
        this.half = fieldSize / 2;
        this.offFieldHandler = p -> respawnLater(p, OFF_FIELD_RESPAWN);
    }

    /** Put an off-field piece back on the floor (near where it left) after a delay. */
    public void respawnLater(GamePiece p, double seconds) {
        pendingRespawn.add(new double[]{p.id, seconds});
    }

    public void setDragCoefficient(double cd) { dragCoefficient = cd; }

    /**
     * Pieces made from now on differ from each other this much (each piece's values come from
     * its id and the seed, so a run is repeatable; a re-staged field has new pieces).
     */
    public void setVariation(PieceKind.Variation v, long seed) {
        variation = v;
        variationSeed = seed;
    }

    /** Magnus lift coefficient at high spin, and how fast spin dies away (time constant, s). */
    public void setSpinEffects(double maxLiftCoefficient, double decaySeconds) {
        magnusLift = maxLiftCoefficient;
        spinDecaySeconds = decaySeconds;
    }
    public void addTarget(FlightTarget t) { targets.add(t); }
    public void addObstacle(FlightObstacle o) { obstacles.add(o); }

    /**
     * Moves a landing spot {x, y, radius} out from under things a piece can't sit inside (the
     * robot's body); returns {x, y}.
     */
    public void setFloorGuard(java.util.function.UnaryOperator<double[]> guard) { floorGuard = guard; }

    /** What to do when a piece leaves the field (default: put it back after 3 s). */
    public void setOffFieldHandler(Consumer<GamePiece> handler) { offFieldHandler = handler; }

    public List<GamePiece> all() { return Collections.unmodifiableList(pieces); }

    public synchronized List<Trajectory> trajectories() { return new ArrayList<>(trajectories); }

    public GamePiece create(PieceKind kind) {
        int id = nextId++;
        PieceKind own = kind.varied(variation, new java.util.Random(variationSeed * 1_000_003L + id));
        GamePiece p = new GamePiece(id, own);
        pieces.add(p);
        return p;
    }

    /**
     * Remove every piece (used when re-staging the field). Numbering starts over, so a re-staged
     * field gets the same balls again, as at an event.
     */
    public void clear() {
        for (GamePiece p : pieces) removeBody(p);
        pieces.clear();
        nextId = 1;
        pendingRespawn.clear();
        synchronized (this) {
            trajectories.clear();
        }
    }

    // ------------------------------------------------------------------ state changes

    /** True if a piece of this radius centered here is inside the field walls. */
    public boolean fitsOnField(double x, double y, double radius) {
        return Math.abs(x) <= half - radius && Math.abs(y) <= half - radius;
    }

    public void placeOnFloor(GamePiece p, double x, double y, double vx, double vy) {
        p.fromTip = false; // it touched the tiles
        removeBody(p);
        double r = p.kind.isBall() ? p.kind.radius() : 0;
        double[] safe = freeSpot(p, x, y, Math.max(r, p.kind.tallness() / 2));
        x = safe[0];
        y = safe[1];
        x = Math.max(-half + r, Math.min(half - r, x));
        y = Math.max(-half + r, Math.min(half - r, y));
        Body b = new Body();
        BodyFixture f = b.addFixture(p.kind.isBall() ? Geometry.createCircle(p.kind.radius())
                : Geometry.createRectangle(p.kind.width(), p.kind.height()), 1.0, 0.3, p.kind.restitution());
        f.setRestitutionVelocity(0.05);
        f.setFilter(FieldWorld.PIECE_FILTER);
        b.setMass(MassType.NORMAL);
        Mass m = b.getMass();
        b.setMass(new Mass(m.getCenter(), p.kind.mass(), m.getInertia() * p.kind.mass() / m.getMass()));
        b.translate(x, y);
        if (p.heading != 0) b.rotate(p.heading, x, y);
        b.setLinearVelocity(vx, vy);
        b.setAngularDamping(p.kind.isBall() ? 5.0 : 3.0);
        b.setUserData(p);
        field.world.addBody(b);
        p.body = b;
        p.state = GamePiece.State.FLOOR;
        p.wx = p.wy = p.wz = 0;
        p.holder = null;
        p.x = x;
        p.y = y;
        p.z = p.kind.tallness() / 2;
        p.vx = vx;
        p.vy = vy;
        p.vz = 0;
        finishTrajectory(p, "floor");
    }

    /**
     * The nearest spot to (x, y) where a piece of this radius can sit on the tiles: inside the
     * walls, outside the robot, and not on top of another piece (two pieces placed at exactly the
     * same spot can't be pushed apart, since there's no direction to push them).
     */
    private double[] freeSpot(GamePiece p, double x, double y, double r) {
        for (int ring = 0; ring <= 12; ring++) {
            int tries = ring == 0 ? 1 : 8 * ring;
            for (int k = 0; k < tries; k++) {
                double a = 2 * Math.PI * k / tries, d = ring * r;
                double cx = Math.max(-half + r, Math.min(half - r, x + d * Math.cos(a)));
                double cy = Math.max(-half + r, Math.min(half - r, y + d * Math.sin(a)));
                double[] g = floorGuard.apply(new double[]{cx, cy, r});
                // The guard moves spots off the robot; next to a wall that can be off the field. Clamping it
                // back would put the piece on the robot again, so try the next spot instead.
                if (Math.abs(g[0]) > half - r + 1e-9 || Math.abs(g[1]) > half - r + 1e-9) continue;
                if (overlapsFloorPiece(p, g[0], g[1], r)) continue;
                return g;
            }
        }
        return floorGuard.apply(new double[]{x, y, r});
    }

    private boolean overlapsFloorPiece(GamePiece self, double x, double y, double r) {
        for (GamePiece q : pieces) {
            if (q == self || q.state != GamePiece.State.FLOOR) continue;
            double rq = q.kind.isBall() ? q.kind.radius() : q.kind.tallness() / 2;
            if (Math.hypot(q.x - x, q.y - y) < r + rq - 0.002) return true;
        }
        return false;
    }

    /** Send a piece flying. record=true keeps its path for the viewer's side view. */
    public void launch(GamePiece p, double x, double y, double z, double vx, double vy, double vz, boolean record, String alliance) {
        removeBody(p);
        p.state = GamePiece.State.AIRBORNE;
        p.holder = null;
        p.x = x;
        p.y = y;
        p.z = z;
        p.vx = vx;
        p.vy = vy;
        p.vz = vz;
        p.wx = p.wy = p.wz = 0;
        p.exitingFrom = null;
        if (alliance != null) p.lastTouchedBy = alliance;
        p.trajectory = null;
        if (record) {
            Trajectory t = new Trajectory(p.id, p.kind.color(), time);
            t.points.add(new double[]{x, y, z});
            p.trajectory = t;
            synchronized (this) {
                trajectories.addLast(t);
                while (trajectories.size() > MAX_TRAJECTORIES) trajectories.removeFirst();
            }
        }
    }

    public void hold(GamePiece p, Object holder) {
        removeBody(p);
        p.state = GamePiece.State.HELD;
        p.holder = holder;
        p.vx = p.vy = p.vz = 0;
    }

    public void contain(GamePiece p, Object holder, double x, double y, double z, String outcome) {
        removeBody(p);
        finishTrajectory(p, outcome); // path ends where it went in, not where it comes to rest
        p.state = GamePiece.State.CONTAINED;
        p.holder = holder;
        p.x = x;
        p.y = y;
        p.z = z;
        p.vx = p.vy = p.vz = 0;
    }

    public void takeOffField(GamePiece p) {
        removeBody(p);
        p.state = GamePiece.State.OFF_FIELD;
        p.holder = null;
        p.vx = p.vy = p.vz = 0;
    }

    private void removeBody(GamePiece p) {
        if (p.body != null) {
            field.world.removeBody(p.body);
            p.body = null;
        }
    }

    private void finishTrajectory(GamePiece p, String outcome) {
        if (p.trajectory != null) {
            p.trajectory.points.add(new double[]{p.x, p.y, p.z});
            p.trajectory.outcome = outcome;
            p.trajectory = null;
        }
    }

    // ------------------------------------------------------------------ stepping

    /** Rolling/sliding resistance against the tiles. Call before the world steps. */
    public void applyFloorForces() {
        for (GamePiece p : pieces) {
            if (p.state != GamePiece.State.FLOOR) continue;
            Vector2 v = p.body.getLinearVelocity();
            double speed = v.getMagnitude();
            if (speed < 1e-6) continue;
            double f = p.kind.floorFriction() * p.kind.mass() * G * Math.tanh(speed / 0.02);
            p.body.applyForce(new Vector2(-v.x / speed * f, -v.y / speed * f));
        }
    }

    /** Copy floor bodies' positions into the pieces. Call after the world steps. */
    public void syncFloorPositions() {
        for (GamePiece p : pieces) {
            if (p.state != GamePiece.State.FLOOR) continue;
            p.x = p.body.getTransform().getTranslationX();
            p.y = p.body.getTransform().getTranslationY();
            p.heading = p.body.getTransform().getRotationAngle();
            Vector2 v = p.body.getLinearVelocity();
            double speed = v.getMagnitude();
            if (speed > MAX_FLOOR_SPEED) {
                p.body.setLinearVelocity(v.x * MAX_FLOOR_SPEED / speed, v.y * MAX_FLOOR_SPEED / speed);
                v = p.body.getLinearVelocity();
            }
            p.vx = v.x;
            p.vy = v.y;
        }
    }

    public void stepAirborne(double dt) {
        time += dt;
        for (int i = 0; i < pieces.size(); i++) {
            GamePiece p = pieces.get(i);
            if (p.state != GamePiece.State.AIRBORNE) continue;
            double x0 = p.x, y0 = p.y, z0 = p.z;
            double r = p.kind.radius();
            double speed = p.speed();
            double area = Math.PI * r * r;
            double k = 0.5 * AIR_DENSITY * dragCoefficient * area / p.kind.mass();
            double ax = -k * speed * p.vx, ay = -k * speed * p.vy, az = -G - k * speed * p.vz;
            double spin = p.spinRate();
            if (spin > 1e-6 && speed > 0.05 && p.kind.isBall()) {
                double ratio = r * spin / speed;
                double cl = magnusLift * ratio / (ratio + 0.25);
                // Force along (spin axis x velocity), magnitude 0.5 rho A CL v^2.
                double cx = p.wy * p.vz - p.wz * p.vy, cy = p.wz * p.vx - p.wx * p.vz, cz = p.wx * p.vy - p.wy * p.vx;
                double scale = 0.5 * AIR_DENSITY * area * cl * speed * speed / p.kind.mass() / (spin * speed);
                ax += cx * scale;
                ay += cy * scale;
                az += cz * scale;
                double decay = Math.exp(-dt / spinDecaySeconds);
                p.wx *= decay;
                p.wy *= decay;
                p.wz *= decay;
            }
            p.vx += ax * dt;
            p.vy += ay * dt;
            p.vz += az * dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.z += p.vz * dt;
            if (p.trajectory != null) p.trajectory.points.add(new double[]{p.x, p.y, p.z});

            boolean captured = false;
            for (FlightTarget t : targets) {
                if (t.tryCapture(p, x0, y0, z0)) {
                    captured = true;
                    break;
                }
            }
            if (captured) continue;
            for (FlightObstacle o : obstacles) o.collide(p, x0, y0, z0);

            // Perimeter walls: bounce below the top of the wall, leave the field above it.
            if (Math.abs(p.x) > half - r || Math.abs(p.y) > half - r) {
                if (p.z < WALL_HEIGHT) {
                    p.fromTip = false; // it touched a wall
                    if (Math.abs(p.x) > half - r) {
                        p.x = Math.copySign(half - r, p.x);
                        p.vx = -0.5 * p.vx;
                    }
                    if (Math.abs(p.y) > half - r) {
                        p.y = Math.copySign(half - r, p.y);
                        p.vy = -0.5 * p.vy;
                    }
                } else if (Math.abs(p.x) > half + r || Math.abs(p.y) > half + r) {
                    finishTrajectory(p, "out of field");
                    takeOffField(p);
                    offFieldHandler.accept(p);
                    continue;
                }
            }

            // Floor: bounce, then settle into a rolling floor body.
            double bottom = p.kind.isBall() ? r : p.kind.tallness() / 2;
            if (p.z <= bottom && p.vz < 0) {
                p.z = bottom;
                p.fromTip = false; // it touched the tiles
                if (-p.vz > SETTLE_SPEED) {
                    p.vz = -p.vz * p.kind.restitution();
                    p.vx *= 0.8;
                    p.vy *= 0.8;
                } else {
                    placeOnFloor(p, p.x, p.y, p.vx * 0.7, p.vy * 0.7);
                }
            }
        }

        for (int i = pendingRespawn.size() - 1; i >= 0; i--) {
            double[] pr = pendingRespawn.get(i);
            pr[1] -= dt;
            if (pr[1] > 0) continue;
            pendingRespawn.remove(i);
            for (GamePiece p : pieces) {
                if (p.id == (int) pr[0] && p.state == GamePiece.State.OFF_FIELD) {
                    double inset = 4 * IN;
                    placeOnFloor(p, Math.max(-half + inset, Math.min(half - inset, p.x)),
                            Math.max(-half + inset, Math.min(half - inset, p.y)), 0, 0);
                }
            }
        }
    }

    /** Floor pieces whose centers are within a circle. */
    public List<GamePiece> floorPiecesNear(double x, double y, double radius) {
        List<GamePiece> out = new ArrayList<>();
        for (GamePiece p : pieces) {
            if (p.state == GamePiece.State.FLOOR && Math.hypot(p.x - x, p.y - y) <= radius) out.add(p);
        }
        return out;
    }
}
