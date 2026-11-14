package org.ftctestbed.sim;

import com.qualcomm.robotcore.hardware.HardwareMap;

import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.game.GamePieces;
import org.ftctestbed.sim.game.MatchState;
import org.ftctestbed.sim.game.PieceKind;
import org.ftctestbed.sim.game.RobotPieceHandling;
import org.ftctestbed.sim.game.RobotView;
import org.ftctestbed.sim.game.Season;
import org.ftctestbed.sim.game.SeasonContext;
import org.ftctestbed.sim.game.SeasonSnapshot;
import org.ftctestbed.sim.game.Seasons;
import org.ftctestbed.sim.hardware.HubTiming;
import org.ftctestbed.sim.hardware.MotorPort;
import org.ftctestbed.sim.hardware.ServoPort;
import org.ftctestbed.sim.physics.Battery;
import org.ftctestbed.sim.physics.FieldWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * One field with its robots (usually one; two when a partner practices along). step()
 * advances physics by DT; it is called by SimRunner's physics thread. Robot code talks to its
 * robot's HardwareMap from its own thread.
 *
 * The robot-level methods here (hardwareMap, robotX(), resetRobot(), snapshot(), ...) are the
 * first robot's; every robot is in robots().
 */
public final class Simulation {
    public static final double DT = 0.002;
    private static final long DT_NANOS = 2_000_000L;

    /** The first robot's config. */
    public final RobotConfig robotConfig;
    public final FieldConfig fieldConfig;
    public final SimClock clock = new SimClock();
    /** The first robot's hardware. */
    public final HardwareMap hardwareMap;
    /** How long the first robot's code waits for each hardware call (attached to the physics runner). */
    public final HubTiming hubTiming;

    private final FieldWorld field;
    private final List<SimRobot> robots = new ArrayList<>();
    private final SimRobot first;
    private final List<Consumer<Simulation>> stepListeners = new CopyOnWriteArrayList<>();

    private final GamePieces pieces;
    private final Season season;
    private final MatchState match = new MatchState();
    private final List<String> runtimeWarnings = new ArrayList<>();

    public Simulation(RobotConfig robotConfig, FieldConfig fieldConfig) {
        this(fieldConfig, List.of(robotConfig));
    }

    /** A field with these robots: the first starts at the field's first start position, the others beside it. */
    public Simulation(FieldConfig fieldConfig, List<RobotConfig> robotConfigs) {
        if (robotConfigs.isEmpty()) throw new IllegalArgumentException("a simulation needs a robot");
        this.fieldConfig = fieldConfig;
        this.field = new FieldWorld(fieldConfig, DT);
        pieces = new GamePieces(field, fieldConfig.size());
        season = fieldConfig.season() != null ? Seasons.create(fieldConfig.season(), fieldConfig.seasonSettings()) : null;
        for (RobotConfig cfg : robotConfigs) {
            SimRobot r = new SimRobot(this, robots.size() + 1, cfg, field, pieces,
                    () -> season != null ? season.retrievables() : List.of());
            robots.add(r);
            pieces.addObstacle(r.new Obstacle());
        }
        first = robots.get(0);
        this.robotConfig = first.config;
        this.hardwareMap = first.hardwareMap;
        this.hubTiming = first.hubTiming;
        pieces.setFloorGuard(this::outsideRobots);
        for (SimRobot r : robots) r.setStart(defaultStart(r));
        if (season != null) season.attach(new Context());
        resetField();
    }

    // ---------------------------------------------------------------- robots

    /** Every robot on the field, the first robot first. */
    public List<SimRobot> robots() { return robots; }

    /** Robot number n (1 is the first). */
    public SimRobot robot(int number) { return robots.get(number - 1); }

    /**
     * Where a robot starts unless told otherwise: the first robot at the field's first start
     * position; a partner at a free start position of the first robot's alliance (or any free
     * one), else beside the first robot.
     */
    private FieldConfig.StartPosition defaultStart(SimRobot r) {
        List<FieldConfig.StartPosition> all = fieldConfig.startPositions();
        if (r == first) return all.get(0);
        String alliance = first.alliance();
        FieldConfig.StartPosition any = null;
        for (FieldConfig.StartPosition sp : all) {
            if (takenByOther(sp, r)) continue;
            if (java.util.Objects.equals(sp.alliance(), alliance)) return sp;
            if (any == null) any = sp;
        }
        if (any != null) return any;
        // No free start position: beside the robot before it, to its left.
        SimRobot prev = robots.get(r.number - 2);
        FieldConfig.Pose p = prev.startPosition().pose();
        double gap = (prev.config.width() + r.config.width()) / 2 + 0.1;
        double half = fieldConfig.size() / 2 - Math.max(r.config.length(), r.config.width()) / 2
                - Math.hypot(r.config.frameOffsetX(), r.config.frameOffsetY());
        double x = Math.max(-half, Math.min(half, p.x() - Math.sin(p.heading()) * gap));
        double y = Math.max(-half, Math.min(half, p.y() + Math.cos(p.heading()) * gap));
        return new FieldConfig.StartPosition("Beside robot " + prev.number, prev.alliance(), new FieldConfig.Pose(x, y, p.heading()));
    }

    private boolean takenByOther(FieldConfig.StartPosition sp, SimRobot r) {
        for (SimRobot o : robots) if (o != r && o.startPosition() != null && o.startPosition().equals(sp)) return true;
        return false;
    }

    /** Moves a landing spot {x, y, r} out of every robot's footprint; returns {x, y}. */
    private double[] outsideRobots(double[] spot) {
        if (robots.size() == 1) return first.outside(spot);
        double[] xy = {spot[0], spot[1]};
        for (int pass = 0; pass < 3; pass++) {
            boolean moved = false;
            for (SimRobot r : robots) {
                double[] s = {xy[0], xy[1], spot[2]};
                if (!r.covers(s)) continue;
                xy = r.outside(s);
                moved = true;
            }
            if (!moved) break;
        }
        return xy;
    }

    // ---------------------------------------------------------------- game

    private final class Context implements SeasonContext {
        @Override public GamePieces pieces() { return pieces; }
        @Override public FieldWorld world() { return field; }
        @Override public double now() { return clock.seconds(); }
        @Override public MatchState match() { return match; }
        @Override public RobotView robot() { return first.view(); }

        @Override
        public List<RobotView> robots() {
            List<RobotView> out = new ArrayList<>();
            for (SimRobot r : Simulation.this.robots) out.add(r.view());
            return out;
        }

        @Override public double fieldSize() { return fieldConfig.size(); }
        @Override public List<String> warnings() { return runtimeWarnings; }
    }

    /** Match setup: every robot back to its start position (power-cycled) and every game piece re-staged. */
    public synchronized void resetField() {
        for (SimRobot r : robots) r.resetForMatch();
        resetPieces();
        field.rebuild(); // the same starting state for dyn4j as the first time, so matches repeat exactly
    }

    /**
     * The robots back at their starts and the game pieces back in place while OpModes keep
     * running (the sandbox): unlike resetField(), the motors, servos and sensors the OpModes are
     * using aren't power-cycled, so their motor directions and modes stay as they set them.
     */
    public synchronized void resetFieldWhileDriving() {
        for (SimRobot r : robots) r.moveToStart();
        resetPieces();
    }

    private void resetPieces() {
        for (SimRobot r : robots) if (r.handling() != null) r.handling().clear();
        if (season != null) {
            season.reset();
        } else {
            pieces.clear();
            for (FieldConfig.Shape sh : fieldConfig.gamePieces()) {
                PieceKind kind = new PieceKind(sh.name().isEmpty() ? "piece" : sh.name(), sh.shape().equals("circle") ? "circle" : "rect",
                        sh.diameter() / 2, sh.shape().equals("circle") ? sh.diameter() : sh.width(),
                        sh.shape().equals("circle") ? sh.diameter() : sh.height(), sh.mass(), sh.color(), sh.floorFriction(),
                        sh.restitution(), null);
                GamePiece gp = pieces.create(kind);
                pieces.placeOnFloor(gp, sh.x(), sh.y(), 0, 0);
            }
        }
    }

    /** Choose where (and for which alliance) the first robot starts, then reset the field. */
    public synchronized void setStartPosition(FieldConfig.StartPosition sp) {
        setStartPosition(first, sp);
    }

    /**
     * Choose where (and for which alliance) a robot starts, then reset the field. A robot that
     * was starting there takes this robot's old place, so two robots never start on top of each other.
     */
    public synchronized void setStartPosition(SimRobot robot, FieldConfig.StartPosition sp) {
        FieldConfig.StartPosition old = robot.startPosition();
        for (SimRobot o : robots) if (o != robot && sp.equals(o.startPosition())) o.setStart(old);
        robot.setStart(sp);
        if (match.phase() == MatchState.Phase.ENDED) match.enter(MatchState.Phase.PRACTICE, clock.seconds());
        resetField();
    }

    public FieldConfig.StartPosition startPosition() { return first.startPosition(); }

    /** The first robot's alliance: "red", "blue" or null (from its start position). */
    public String alliance() { return first.alliance(); }

    public MatchState match() { return match; }
    public Season season() { return season; }
    public GamePieces pieces() { return pieces; }
    public RobotPieceHandling handling() { return first.handling(); }
    public RobotView robot() { return first.view(); }

    /** Warnings found while setting up the season (e.g. unknown settings). */
    public List<String> runtimeWarnings() { return runtimeWarnings; }

    /**
     * Change the field from another thread (e.g. place a game piece in a test). Runs between
     * physics steps, so it can't collide with the physics thread.
     */
    public synchronized void edit(Consumer<Simulation> change) {
        change.accept(this);
    }

    /** Ask the season's human player to act (e.g. enter a NECTAR). */
    public synchronized void humanPlayerAction(String alliance) {
        if (season != null) season.humanPlayerAction(alliance);
    }

    // ---------------------------------------------------------------- the first robot

    public void addStepListener(Consumer<Simulation> listener) { stepListeners.add(listener); }
    public void removeStepListener(Consumer<Simulation> listener) { stepListeners.remove(listener); }

    /** Put the robot back at its start pose and power-cycle it (encoders and IMU zeroed). */
    public synchronized void resetRobot() { first.resetRobot(); }

    public synchronized void resetRobot(double x, double y, double heading) { first.resetRobot(x, y, heading); }

    /** Push the robot by hand: the grabbed point is pulled toward the target like a person pushing it. */
    public void setHand(double grabX, double grabY, double targetX, double targetY) { first.setHand(grabX, grabY, targetX, targetY); }

    public void clearHand() { first.clearHand(); }

    /** Where the hand holds the robot and pulls it to (field, m), or null. */
    public double[] handLine() { return first.handLine(); }

    /** Pick the robot up and set it down somewhere else (encoders keep their counts). */
    public synchronized void moveRobot(double x, double y, double heading) { first.moveRobot(x, y, heading); }

    /** All motor ports by device name (drive, mechanisms, encoder-only dead-wheel ports). */
    public Map<String, MotorPort> motorPorts() { return first.motorPorts(); }

    public List<ServoPort> servoPorts() { return first.servoPorts(); }

    public double batteryVolts() { return first.batteryVolts(); }

    /** The battery: charge left and used, resting voltage. */
    public Battery battery() { return first.battery(); }

    /** Center of gravity height above the tiles right now (m). */
    public double cgHeight() { return first.cgHeight(); }

    /** Current drawn from the battery by all motors (A), as of the last step. */
    public double batteryCurrent() { return first.batteryCurrent(); }

    public double robotX() { return first.x(); }
    /** Robot turn rate, rad/s (counter-clockwise positive). */
    public double robotOmega() { return first.omega(); }

    /** Robot speed over the field, m/s. */
    public double robotSpeed() { return first.speed(); }
    public double robotY() { return first.y(); }
    public double robotHeading() { return first.heading(); }

    // ---------------------------------------------------------------- stepping

    public synchronized void step() {
        for (SimRobot r : robots) r.beforeWorld(DT);
        pieces.applyFloorForces();
        field.world.step(1);
        pieces.syncFloorPositions();
        for (SimRobot r : robots) r.afterWorld(DT);
        pieces.stepAirborne(DT);
        if (season != null) season.step(DT);
        for (SimRobot r : robots) r.finishStep(DT);
        clock.advance(DT_NANOS);
        for (Consumer<Simulation> l : stepListeners) l.accept(this);
    }

    // ---------------------------------------------------------------- snapshot

    /** What every robot's snapshot shares: the time, the game pieces and the game. */
    record FieldState(double now, List<Snapshot.Piece> pieces, List<Snapshot.Flight> flights, SeasonSnapshot season,
                      String phase, double clock) {}

    private FieldState fieldState() {
        List<Snapshot.Piece> pieceStates = new ArrayList<>();
        for (GamePiece gp : pieces.all()) {
            if (gp.state() == GamePiece.State.OFF_FIELD) continue;
            PieceKind k = gp.kind;
            pieceStates.add(new Snapshot.Piece(gp.id, k.id(), k.isBall(), k.radius(), k.width(), k.height(), k.color(),
                    gp.x(), gp.y(), gp.z(), gp.heading(), gp.state().name()));
        }
        List<Snapshot.Flight> flights = new ArrayList<>();
        for (GamePieces.Trajectory t : pieces.trajectories()) {
            flights.add(new Snapshot.Flight(t.color, new ArrayList<>(t.points), t.outcome));
        }
        double now = clock.seconds();
        return new FieldState(now, pieceStates, flights, season != null ? season.snapshot() : null,
                match.phase().name(), match.clock(now));
    }

    /** The first robot and the field. */
    public synchronized Snapshot snapshot() {
        return first.snapshot(fieldState());
    }

    synchronized Snapshot snapshotOf(SimRobot r) {
        return r.snapshot(fieldState());
    }

    /** Every robot at the same moment, the first robot first (they share the field's lists). */
    public synchronized List<Snapshot> snapshots() {
        FieldState f = fieldState();
        List<Snapshot> out = new ArrayList<>();
        for (SimRobot r : robots) out.add(r.snapshot(f));
        return out;
    }
}
