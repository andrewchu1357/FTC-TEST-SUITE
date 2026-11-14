package org.ftctestbed.sim.game;

import org.dyn4j.dynamics.Body;
import org.dyn4j.geometry.Vector2;
import org.ftctestbed.sim.config.ConfigException;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.hardware.MotorPort;
import org.ftctestbed.sim.hardware.ServoPort;
import org.ftctestbed.sim.physics.Mechanism1D;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The robot's game-piece mechanisms: intake rollers, a hopper with a transfer, a flywheel
 * launcher, and claws. Everything is driven by the simulated motors and servos that robot
 * code controls - nothing here is scripted.
 *
 * Intake: the rollers touch pieces in the intake zone and pull them by friction (up to
 * squeeze x grip, less for a piece only partly under the rollers' ends, which the end of the
 * roller pushes aside) toward the robot at up to their surface speed; that pull loads the
 * roller's motor. A piece pulled past the zone's inner edge reaches the throat, which takes one
 * piece at a time: it goes in if it's lined up with the throat, there's room in the hopper and
 * the rollers are turning inward. Otherwise it waits there (the funnel walls steer it toward the
 * throat; two side by side jam until one is knocked loose, the rollers are reversed or the
 * robot moves off them).
 *
 * Claws hold a piece by friction: gripForce when fully closed (less while the servo is still
 * closing). A shock that needs more than that to carry the piece along - hitting a wall, a lift
 * slamming into its stop - shakes it loose.
 */
public final class RobotPieceHandling {
    private static final double INTAKE_SECONDS_PER_PIECE = 0.08;
    private static final double EJECT_SECONDS_PER_PIECE = 0.3;
    /** Roller pull per m/s of slip (before the grip limit): stiff enough to carry a piece at roller speed. */
    private static final double ROLLER_SLIP_STIFFNESS = 5.0;
    /** The funnel walls steer a piece at the throat sideways with this share of the rollers' grip. */
    private static final double FUNNEL_STEER = 0.6;
    /** Two pieces this close in depth at the throat are side by side. */
    private static final double SIDE_BY_SIDE = 0.3;
    /** [ESTIMATE] Chance per second that a jam shakes loose on its own (roller vibration). */
    private static final double JAM_CLEAR_RATE = 0.3;
    /** Servo travel past closedBelow for a claw's grip to reach full force. */
    private static final double GRIP_TRAVEL = 0.05;
    /** How quickly bumpers and frame flex let a shock build up (s). */
    private static final double SHOCK_SECONDS = 0.03;
    /** Share of the flywheel energy a shot uses that ends up as ball speed (the rest goes into compression and spin). */
    private static final double LAUNCH_ENERGY_EFFICIENCY = 0.25;

    private final RobotConfig cfg;
    private final Body robot;
    private final GamePieces pieces;
    private final Map<String, MotorPort> motors;
    private final Map<String, ServoPort> servos;
    private final Supplier<List<Retrievable>> retrievables;
    private final Supplier<String> alliance;

    private final List<GamePiece> hopper = new ArrayList<>();
    private boolean staged;
    private double stageProgress;
    private double intakeCooldown;
    private int intakeDirection;
    private boolean jammed;
    private double rollerLoad;
    private final Mechanism1D intakeRoller;
    private java.util.Random random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(11));
    private final Mechanism1D flywheel;
    private final ServoPort feeder;
    private boolean feederWasAbove;
    private final List<Claw> claws = new ArrayList<>();

    private final class Claw {
        final RobotConfig.Gripper def;
        final ServoPort servo;
        final Mechanism1D lift;
        boolean wasClosed;
        GamePiece held;
        // The claw's motion (field frame), for the shocks that can shake a piece loose.
        boolean tracking;
        double lastX, lastY, lastZ, vx, vy, vz, ax, ay, az;

        Claw(RobotConfig.Gripper def, ServoPort servo, Mechanism1D lift) {
            this.def = def;
            this.servo = servo;
            this.lift = lift;
        }

        double height() {
            return def.baseHeight() + (lift != null ? Math.max(0, lift.position()) : 0);
        }

        boolean closed() {
            return servo.isPwmEnabled() && servo.actualPosition() < def.closedBelow();
        }

        /** How hard it's gripping (N): rises from 0 as the servo closes past closedBelow. */
        double gripForce() {
            if (!closed()) return 0;
            return def.gripForce() * Math.min(1, (def.closedBelow() - servo.actualPosition()) / GRIP_TRAVEL);
        }

        /** Robot-frame x of the claw (moves toward xRaised as an angled lift extends). */
        double x() {
            double span = def.raisedHeight() - def.baseHeight();
            double f = span <= 0 ? 0 : Math.max(0, Math.min(1, (height() - def.baseHeight()) / span));
            return def.x() + (def.xRaised() - def.x()) * f;
        }
    }

    public RobotPieceHandling(RobotConfig cfg, Body robot, GamePieces pieces, Map<String, MotorPort> motors,
                              Map<String, ServoPort> servos, Map<String, Mechanism1D> mechanisms,
                              Supplier<List<Retrievable>> retrievables, Supplier<String> alliance) {
        this.cfg = cfg;
        this.robot = robot;
        this.pieces = pieces;
        this.motors = motors;
        this.servos = servos;
        this.retrievables = retrievables;
        this.alliance = alliance;
        if (cfg.intake() != null) requireDevice(cfg.intake().device(), "intake.device");
        intakeRoller = cfg.intake() != null ? mechanisms.get(cfg.intake().device()) : null;
        if (cfg.hopper() != null && cfg.hopper().transfer() != null) requireDevice(cfg.hopper().transfer(), "hopper.transfer");
        if (cfg.launcher() != null) {
            flywheel = mechanisms.get(cfg.launcher().flywheel());
            feeder = servos.get(cfg.launcher().feederServo());
            if (feeder == null || feeder.def.continuous()) {
                throw new ConfigException("launcher.feederServo \"" + cfg.launcher().feederServo() + "\" must be a (positional) servo");
            }
        } else {
            flywheel = null;
            feeder = null;
        }
        for (RobotConfig.Gripper g : cfg.grippers()) {
            ServoPort s = servos.get(g.servo());
            if (s == null || s.def.continuous()) throw new ConfigException("Gripper \"" + g.name() + "\": servo \"" + g.servo() + "\" must be a (positional) servo");
            claws.add(new Claw(g, s, g.heightMechanism() != null ? mechanisms.get(g.heightMechanism()) : null));
        }
    }

    private void requireDevice(String name, String where) {
        if (!motors.containsKey(name) && !(servos.containsKey(name) && servos.get(name).def.continuous())) {
            throw new ConfigException(where + ": \"" + name + "\" is not a motor or CR servo in robot.jsonc");
        }
    }

    /** Surface speed (m/s) of a roller on this motor or CR servo; positive = the device's positive direction. */
    private double surfaceSpeed(String device, double rollerRadius) {
        MotorPort m = motors.get(device);
        if (m != null) return m.shaftOmega() * rollerRadius;
        ServoPort s = servos.get(device);
        return s.crPower() * s.def.crMaxRpm() * 2 * Math.PI / 60 * rollerRadius;
    }

    private int direction(String device) {
        MotorPort m = motors.get(device);
        double v = m != null ? (m.isEnergized() ? m.appliedDuty() : 0) : servos.get(device).crPower();
        return v > 0.05 ? 1 : v < -0.05 ? -1 : 0;
    }

    // ------------------------------------------------------------------ queries

    public int capacity() { return cfg.hopper() != null ? cfg.hopper().capacity() : 0; }

    public List<GamePiece> hopper() { return hopper; }

    /** Every piece the robot currently controls (hopper + claws). */
    public List<GamePiece> held() {
        List<GamePiece> all = new ArrayList<>(hopper);
        for (Claw c : claws) if (c.held != null) all.add(c.held);
        return all;
    }

    /** Color (0xRRGGBB) of the piece in a hopper slot, or -1. */
    public int hopperSlotColor(int slot) {
        if (slot >= hopper.size()) return -1;
        if (slot == 0 && cfg.launcher() != null && !staged) return -1;
        return hopper.get(slot).kind.color();
    }

    /** Put a piece straight into the hopper (pre-loads). Returns false if full or no hopper. */
    public boolean preload(GamePiece p) {
        if (hopper.size() >= capacity()) return false;
        pieces.hold(p, this);
        hopper.add(p);
        if (hopper.size() == 1) staged = true;
        return true;
    }

    public void clear() {
        random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(11));
        jammed = false;
        hopper.clear();
        staged = false;
        stageProgress = 0;
        for (Claw c : claws) c.held = null;
    }

    // ------------------------------------------------------------------ stepping

    public void step(double dt) {
        double heading = robot.getTransform().getRotationAngle();
        double c = Math.cos(heading), s = Math.sin(heading);
        double rx = robot.getTransform().getTranslationX(), ry = robot.getTransform().getTranslationY();

        stepIntake(dt, rx, ry, c, s);
        stepTransfer(dt);
        stepLauncher(rx, ry, c, s, heading);
        for (Claw claw : claws) stepClaw(claw, rx, ry, c, s, dt);

        for (GamePiece p : hopper) p.setPosition(rx, ry, cfg.height() / 2);
    }

    private void stepIntake(double dt, double rx, double ry, double c, double s) {
        RobotConfig.Intake in = cfg.intake();
        intakeCooldown -= dt;
        intakeDirection = 0;
        jammed = false;
        rollerLoad = 0;
        if (in == null) return;
        double rr = in.rollerRadius();
        double vs = surfaceSpeed(in.device(), rr) * (in.inwardIsPositive() ? 1 : -1); // inward surface speed
        if (vs > in.minSurfaceSpeed()) intakeDirection = 1;
        else if (vs < -in.minSurfaceSpeed()) intakeDirection = -1;
        double yc = (in.yMin() + in.yMax()) / 2, halfSpan = (in.yMax() - in.yMin()) / 2;
        Vector2 rv = robot.getLinearVelocity();
        double w = robot.getAngularVelocity();

        // Pieces on the tiles: under the rollers they're pulled; past them they're at the throat.
        List<GamePiece> atThroat = new ArrayList<>();
        double pull = 0;
        for (GamePiece p : pieces.all()) {
            if (p.state != GamePiece.State.FLOOR || !accepts(in.accepts(), p)) continue;
            double dx = p.x - rx, dy = p.y - ry;
            double lx = c * dx + s * dy, ly = -s * dx + c * dy;
            double rb = p.kind.radius();
            if (lx > in.xMax() || lx < in.xMin() - in.mouthDepthMargin() - rb || Math.abs(ly - yc) > halfSpan + rb) continue;
            if (lx < in.xMin()) {
                atThroat.add(p);
                continue;
            }
            // Share of the piece under the rollers: all of it at least half a radius in from their ends.
            double under = Math.max(0, Math.min(1, (halfSpan - Math.abs(ly - yc)) / rb + 0.5));
            if (under <= 0) continue;
            double pvx = p.vx - (rv.x - w * dy), pvy = p.vy - (rv.y + w * dx);
            double vin = -(c * pvx + s * pvy);
            double limit = in.grip() * in.squeeze() * under;
            double k = Math.min(ROLLER_SLIP_STIFFNESS, 0.5 * p.kind.mass() / 0.002);
            double f = Math.max(-limit, Math.min(limit, k * (vs - vin)));
            // The end of a roller rubbing the side of a piece pushes it out past the end.
            double side = (1 - under) * Math.abs(f) * 0.5 * Math.signum(ly - yc);
            push(p, c, s, -f, side, rx, ry);
            pull += f;
        }

        // The throat: one piece at a time, the deepest first.
        atThroat.sort((a, b) -> Double.compare(localX(a, rx, ry, c, s), localX(b, rx, ry, c, s)));
        GamePiece first = atThroat.isEmpty() ? null : atThroat.get(0);
        boolean room = hopper.size() < capacity();
        double half = in.throatWidth() / 2;
        if (first != null) {
            double lx0 = localX(first, rx, ry, c, s), ly0 = localY(first, rx, ry, c, s);
            double r0 = first.kind.radius();
            GamePiece beside = null;
            for (int i = 1; i < atThroat.size(); i++) {
                GamePiece q = atThroat.get(i);
                double lq = localY(q, rx, ry, c, s);
                if (localX(q, rx, ry, c, s) - lx0 < SIDE_BY_SIDE * r0 && Math.abs(lq - yc) < half + 0.5 * q.kind.radius()
                        && Math.abs(lq - ly0) < first.kind.radius() + q.kind.radius() + 0.01) {
                    beside = q;
                    break;
                }
            }
            boolean lined = Math.abs(ly0 - yc) <= half - 0.6 * r0;
            if (beside != null && Math.abs(ly0 - yc) < half + 0.5 * r0
                    && 2 * (r0 + beside.kind.radius()) > in.throatWidth() && vs > in.minSurfaceSpeed()) {
                jammed = true; // wedged in the funnel side by side
                if (random.nextDouble() < JAM_CLEAR_RATE * dt) {
                    GamePiece out = Math.abs(ly0 - yc) > Math.abs(localY(beside, rx, ry, c, s) - yc) ? first : beside;
                    knockBack(out, c, s, rx, ry);
                }
            } else if (lined && room && intakeCooldown <= 0 && vs > in.minSurfaceSpeed()) {
                pieces.hold(first, this);
                hopper.add(first);
                atThroat.remove(0);
                intakeCooldown = INTAKE_SECONDS_PER_PIECE;
            }
        }
        // Everything still at the throat waits at its mouth; the funnel steers it toward the middle.
        for (GamePiece p : atThroat) {
            double ly = localY(p, rx, ry, c, s);
            if (Math.abs(ly - yc) > half - 0.6 * p.kind.radius() && vs > 0) {
                double steer = FUNNEL_STEER * in.grip() * in.squeeze() * -Math.signum(ly - yc);
                push(p, c, s, 0, steer, rx, ry);
            }
            holdAtThroat(p, in.xMin(), rx, ry, c, s, rv, w);
            if (vs > 0) pull += in.grip() * in.squeeze() * 0.5; // the rollers slip on it
        }
        rollerLoad = pull;
        if (intakeRoller != null && pull != 0) intakeRoller.addLoad(-pull * rr * (in.inwardIsPositive() ? 1 : -1));

        // A FLOWER's retrieval opening: the rollers pull its bottom piece out once they reach it.
        if (intakeCooldown <= 0 && room && vs > in.minSurfaceSpeed()) {
            for (Retrievable r : retrievables.get()) {
                for (GamePiece p : r.exposed()) {
                    if (!accepts(in.accepts(), p)) continue;
                    double lx = localX(p, rx, ry, c, s), ly = localY(p, rx, ry, c, s);
                    if (lx >= in.xMin() - p.kind.radius() && lx <= in.xMax() && Math.abs(ly - yc) <= halfSpan) {
                        pieces.hold(p, this);
                        hopper.add(p);
                        r.taken(p);
                        intakeCooldown = INTAKE_SECONDS_PER_PIECE;
                        return;
                    }
                }
            }
        }

        // Spitting out: the reversed rollers push the last piece in the hopper out between them.
        if (intakeDirection < 0 && !hopper.isEmpty() && intakeCooldown <= 0) {
            GamePiece p = hopper.get(hopper.size() - 1);
            double out = in.xMax() + p.kind.radius() + 0.01;
            double ex = rx + c * out, ey = ry + s * out;
            // Pressed against a wall there's no room to push a piece out: it stays in the robot.
            if (!pieces.fitsOnField(ex, ey, p.kind.radius())) return;
            hopper.remove(hopper.size() - 1);
            if (hopper.isEmpty()) staged = false;
            double speed = Math.min(2.0, 0.6 * -vs);
            pieces.placeOnFloor(p, ex, ey, rv.x + c * speed, rv.y + s * speed);
            p.lastTouchedBy = alliance.get();
            intakeCooldown = EJECT_SECONDS_PER_PIECE;
        }
    }

    private double localX(GamePiece p, double rx, double ry, double c, double s) {
        return c * (p.x - rx) + s * (p.y - ry);
    }

    private double localY(GamePiece p, double rx, double ry, double c, double s) {
        return -s * (p.x - rx) + c * (p.y - ry);
    }

    /** A force on a floor piece, given in the robot frame; the robot gets the reaction. */
    private void push(GamePiece p, double c, double s, double fx, double fy, double rx, double ry) {
        if (p.body == null) return;
        double wx = c * fx - s * fy, wy = s * fx + c * fy;
        p.body.applyForce(new Vector2(wx, wy));
        robot.applyForce(new Vector2(-wx, -wy), new Vector2(p.x, p.y));
    }

    /** Keeps a waiting piece from going deeper than the throat's mouth, moving with the robot. */
    private void holdAtThroat(GamePiece p, double xMin, double rx, double ry, double c, double s, Vector2 rv, double w) {
        double lx = localX(p, rx, ry, c, s);
        if (lx >= xMin || p.body == null) return;
        double d = xMin - lx;
        p.body.translate(c * d, s * d);
        p.x += c * d;
        p.y += s * d;
        Vector2 v = p.body.getLinearVelocity();
        double dx = p.x - rx, dy = p.y - ry;
        double relX = v.x - (rv.x - w * dy), relY = v.y - (rv.y + w * dx);
        double in = -(c * relX + s * relY);
        if (in > 0) p.body.setLinearVelocity(v.x + c * in, v.y + s * in);
    }

    /** A jam shaking loose: one of the two pieces pops back out of the funnel. */
    private void knockBack(GamePiece p, double c, double s, double rx, double ry) {
        if (p.body == null) return;
        double ly = localY(p, rx, ry, c, s);
        double side = Math.signum(ly == 0 ? 1 : ly) * 0.15;
        p.body.setLinearVelocity(robot.getLinearVelocity().x + c * 0.4 - s * side, robot.getLinearVelocity().y + s * 0.4 + c * side);
        double out = cfg.intake().xMin() + 0.02 - localX(p, rx, ry, c, s);
        p.body.translate(c * out, s * out);
        p.x += c * out;
        p.y += s * out;
    }

    private static boolean accepts(List<String> kinds, GamePiece p) {
        return kinds.isEmpty() || kinds.contains(p.kind.id());
    }

    private void stepTransfer(double dt) {
        RobotConfig.Hopper h = cfg.hopper();
        if (h == null || cfg.launcher() == null) return;
        if (hopper.isEmpty()) {
            staged = false;
            stageProgress = 0;
            return;
        }
        int dir = h.transfer() == null ? 1 : direction(h.transfer()) * (h.transferForwardIsPositive() ? 1 : -1);
        if (!staged && dir > 0) {
            stageProgress += dt / Math.max(1e-3, h.transferSeconds());
            if (stageProgress >= 1) {
                staged = true;
                stageProgress = 0;
            }
        } else if (staged && dir < 0) {
            staged = false;
        }
    }

    private void stepLauncher(double rx, double ry, double c, double s, double heading) {
        RobotConfig.Launcher l = cfg.launcher();
        if (l == null) return;
        boolean above = feeder.isPwmEnabled() && feeder.actualPosition() > l.fireAbove();
        if (above && !feederWasAbove && staged && !hopper.isEmpty()) {
            GamePiece p = hopper.remove(0);
            staged = false;
            double omega = flywheel.velocity();
            double speed = omega > 0 ? l.efficiency() * omega * l.wheelRadius() : 0.3;
            double px = rx + c * l.x() - s * l.y(), py = ry + s * l.x() + c * l.y();
            Vector2 rv = robot.getLinearVelocity();
            double w = robot.getAngularVelocity();
            double vx = rv.x - w * (py - ry) + c * speed * Math.cos(l.angle());
            double vy = rv.y + w * (px - rx) + s * speed * Math.cos(l.angle());
            double vz = speed * Math.sin(l.angle());
            pieces.launch(p, px, py, l.z(), vx, vy, vz, true, alliance.get());
            // Rolling along the hood: spin x radius = exit speed, about the horizontal axis across
            // the shot (positive spin = backspin, which lifts).
            double spin = l.spin() * speed / p.kind.radius();
            p.setSpin(spin * s, -spin * c, 0);
            p.exitingFrom = robot;
            flywheel.removeEnergy(0.5 * p.kind.mass() * speed * speed / LAUNCH_ENERGY_EFFICIENCY);
        }
        feederWasAbove = above;
    }

    private void stepClaw(Claw claw, double rx, double ry, double c, double s, double dt) {
        boolean closed = claw.closed();
        double lx = claw.x();
        double cx = rx + c * lx - s * claw.def.y(), cy = ry + s * lx + c * claw.def.y();
        double height = claw.height();
        trackMotion(claw, cx, cy, height, dt);
        if (closed && !claw.wasClosed && claw.held == null) {
            GamePiece best = null;
            double bestD = Double.MAX_VALUE;
            for (GamePiece p : pieces.floorPiecesNear(cx, cy, claw.def.grabRadius())) {
                if (!accepts(claw.def.accepts(), p) || height > p.kind.tallness() + 0.03) continue;
                double d = Math.hypot(p.x - cx, p.y - cy);
                if (d < bestD) {
                    best = p;
                    bestD = d;
                }
            }
            if (best != null) {
                pieces.hold(best, claw);
                claw.held = best;
            }
        } else if (claw.held != null && (!closed && claw.wasClosed || shakenLoose(claw, height))) {
            GamePiece p = claw.held;
            claw.held = null;
            pieces.launch(p, cx, cy, Math.max(height, p.kind.radius()), claw.vx, claw.vy, claw.vz, false, alliance.get());
            p.exitingFrom = robot;
        }
        if (claw.held != null) claw.held.setPosition(cx, cy, height);
        claw.wasClosed = closed;
    }

    /** The claw's velocity and (smoothed) acceleration from its path; restarts after a jump (robot moved by hand). */
    private static void trackMotion(Claw claw, double x, double y, double z, double dt) {
        if (!claw.tracking || Math.abs(x - claw.lastX) + Math.abs(y - claw.lastY) + Math.abs(z - claw.lastZ) > 0.05) {
            claw.tracking = true;
            claw.vx = claw.vy = claw.vz = claw.ax = claw.ay = claw.az = 0;
        } else {
            double vx = (x - claw.lastX) / dt, vy = (y - claw.lastY) / dt, vz = (z - claw.lastZ) / dt;
            double k = Math.min(1, dt / SHOCK_SECONDS);
            claw.ax += ((vx - claw.vx) / dt - claw.ax) * k;
            claw.ay += ((vy - claw.vy) / dt - claw.ay) * k;
            claw.az += ((vz - claw.vz) / dt - claw.az) * k;
            claw.vx = vx;
            claw.vy = vy;
            claw.vz = vz;
        }
        claw.lastX = x;
        claw.lastY = y;
        claw.lastZ = z;
    }

    /** The grip can't carry the piece through this shock (its weight too, once it's off the tiles). */
    private static boolean shakenLoose(Claw claw, double height) {
        GamePiece p = claw.held;
        boolean lifted = height > p.kind.tallness() / 2 + 0.01;
        double az = claw.az + (lifted ? GamePieces.G : 0);
        double need = p.kind.mass() * Math.sqrt(claw.ax * claw.ax + claw.ay * claw.ay + az * az);
        return need > claw.gripForce();
    }

    // ------------------------------------------------------------------ snapshot

    public record ClawState(String name, double x, double y, double height, boolean closed, int heldColor) {}

    /** intakeJammed: two pieces wedged side by side at the throat; rollerLoadN: how hard the rollers are pulling. */
    public record Parts(double[] intakeZone, int intakeDirection, List<Integer> hopperColors, boolean staged,
                        double flywheelRpm, double[] launcherPoint, List<ClawState> claws,
                        boolean intakeJammed, double rollerLoadN) {}

    public Parts parts() {
        RobotConfig.Intake in = cfg.intake();
        double[] zone = in == null ? null : new double[]{in.xMin(), in.xMax(), in.yMin(), in.yMax()};
        List<Integer> colors = new ArrayList<>();
        for (GamePiece p : hopper) colors.add(p.kind.color());
        List<ClawState> cs = new ArrayList<>();
        for (Claw c : claws) {
            cs.add(new ClawState(c.def.name(), c.x(), c.def.y(), c.height(), c.closed(), c.held != null ? c.held.kind.color() : -1));
        }
        RobotConfig.Launcher l = cfg.launcher();
        return new Parts(zone, intakeDirection, colors, staged,
                flywheel != null ? flywheel.velocity() * 60 / (2 * Math.PI) : 0,
                l != null ? new double[]{l.x(), l.y()} : null, cs, jammed, rollerLoad);
    }
}
