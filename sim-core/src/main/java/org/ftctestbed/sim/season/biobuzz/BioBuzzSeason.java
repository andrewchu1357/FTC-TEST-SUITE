package org.ftctestbed.sim.season.biobuzz;

import org.ftctestbed.sim.config.ConfigNode;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.game.GamePieces;
import org.ftctestbed.sim.game.MatchState;
import org.ftctestbed.sim.game.PieceKind;
import org.ftctestbed.sim.game.Retrievable;
import org.ftctestbed.sim.game.RobotView;
import org.ftctestbed.sim.game.Season;
import org.ftctestbed.sim.game.SeasonContext;
import org.ftctestbed.sim.game.SeasonSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.ftctestbed.sim.season.biobuzz.BioBuzzField.*;

/**
 * BIOBUZZ presented by RTX (FTC 2026-27). Staging, HIVES, FLOWERS, GARDENS, the human
 * player's NECTAR, and the scoring table from Competition Manual section 10.
 *
 * Every simulated robot plays for the alliance of its start position, so a partner (or an
 * opponent) can practice on the same field. Robots that aren't simulated aren't on the field,
 * but their pre-loads are. Refereeing is automatic and limited to rules the software can see.
 */
public final class BioBuzzSeason implements Season {
    static final String[] ALLIANCES = {"red", "blue"};

    // Points (Table 10-2)
    static final int LEAVE = 3, PARK = 5, TIP = 20, CELL = 2, BOTTOM_NECTAR = 5, FLOWER_OWNED = 2, GARDEN = 1;
    static final int MAJOR = 20, MINOR = 5;
    static final int SWARM_POINTS = 16, POLLINATOR_1_TIPS = 4, POLLINATOR_2_TIPS = 7;

    private final PieceKind pollen;
    private final PieceKind redNectar;
    private final PieceKind blueNectar;
    private final double tipThresholdKg;
    private final double hiveInertia;
    private final PieceKind.Variation variation;
    private final long variationSeed;
    private final boolean autoHumanPlayer;
    private final double dragCoefficient;
    private final double magnusLift;
    private final double spinDecaySeconds;
    private final List<String> settingWarnings = new ArrayList<>();

    private SeasonContext ctx;
    private List<RobotView> robots = List.of();
    private GamePieces pieces;
    private final List<Hive> hives = new ArrayList<>();
    private final List<Flower> flowers = new ArrayList<>();
    private final Map<String, HumanPlayer> humans = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();
    private final Map<String, Integer> foulPoints = new LinkedHashMap<>(); // points credited TO an alliance
    private final java.util.Set<String> warned = new java.util.HashSet<>();

    // Robot achievements, by robot number
    private final Map<Integer, Earned> earned = new LinkedHashMap<>();
    private MatchState.Phase lastPhase = MatchState.Phase.PRACTICE;

    /** What one robot has earned this match. */
    private static final class Earned {
        boolean leave, autoPark, teleopPark;
    }

    // Referee thresholds for what the manual leaves to the REFEREE's eye [ESTIMATE].
    static final double RAM_SPEED = 1.0;          // m/s into the HIVE frame: "ramming at high-speed" (G417)
    static final double BUMP_SPEED = 0.3;         // m/s: a bump worth a warning
    static final double RAM_WINDOW = 5.0;         // s: "multiple times in a short time period"
    static final double PIN_SECONDS = 3.0;        // G421's 3-count
    static final double PIN_SEPARATION = 24 * IN; // 2 ft apart ends a PIN (after 3 s)
    static final double STOPPED = 0.15;           // m/s: "stopped" under the HIVE (G409)

    /** What the referee has seen one robot do this match. */
    private static final class Watch {
        int overFour;                 // G407 instances of 5+ SCORING ELEMENTS
        boolean holdingOverFour;
        int opponentNectar;           // G408 instances
        boolean holdingOpponentNectar;
        int catches, stoppedCatches;  // G409
        boolean onHive;               // G417
        double lastSpeed;
        final java.util.ArrayDeque<Double> hiveHits = new java.util.ArrayDeque<>();
        final java.util.Set<String> cardsFor = new java.util.HashSet<>(); // rules that cost a YELLOW CARD
        final java.util.Set<String> majorsFor = new java.util.HashSet<>(); // per-MATCH MAJOR FOULS given
        double pinCount, pinApart;    // G421, as the pinning robot
        int pinFouls;
    }

    private final Map<Integer, Watch> watching = new LinkedHashMap<>();

    private static final class HumanPlayer {
        final String alliance;
        final List<GamePiece> pool = new ArrayList<>();
        int credits;
        double cooldown;

        HumanPlayer(String alliance) {
            this.alliance = alliance;
        }
    }

    public BioBuzzSeason(Map<String, Object> settings) {
        ConfigNode n = new ConfigNode("field.jsonc biobuzz", settings);
        double pollenMass = n.getDouble("pollenMassG", 25) / 1000;
        double nectarMass = n.getDouble("nectarMassG", 41) / 1000;
        tipThresholdKg = n.getDouble("tipThresholdG", 190) / 1000;
        // The HIVE's inertia sets how fast it swings over; a measured tip time can set it instead.
        double inertia = n.getDouble("hiveInertiaKgM2", 0.3);
        if (n.has("tipSeconds")) {
            inertia = Hive.inertiaForTipSeconds(n.getDouble("tipSeconds", 1), tipThresholdKg, pollenMass, POLLEN_DIAMETER / 2);
        }
        hiveInertia = inertia;
        ConfigNode v = n.child("ballVariation");
        variation = v == null ? PieceKind.Variation.NONE : new PieceKind.Variation(v.getDouble("massPct", 0) / 100,
                v.getDouble("diameterPct", 0) / 100, v.getDouble("bouncePct", 0) / 100, v.getDouble("rollingPct", 0) / 100);
        variationSeed = (v == null ? 1 : v.getInt("seed", 1)) + org.ftctestbed.sim.SimSeed.offset();
        autoHumanPlayer = !"manual".equals(n.getString("humanPlayer", "auto"));
        dragCoefficient = n.getDouble("dragCoefficient", 0.47);
        magnusLift = n.getDouble("magnusLift", 0.25);
        spinDecaySeconds = n.getDouble("spinDecaySeconds", 2.0);
        double rolling = n.getDouble("rollingFriction", 0.08);
        n.collectUnused(settingWarnings);
        pollen = new PieceKind("pollen", "circle", POLLEN_DIAMETER / 2, POLLEN_DIAMETER, POLLEN_DIAMETER, pollenMass,
                POLLEN_COLOR, rolling, 0.45, null);
        redNectar = new PieceKind("nectar-red", "circle", NECTAR_DIAMETER / 2, NECTAR_DIAMETER, NECTAR_DIAMETER, nectarMass,
                RED_NECTAR_COLOR, rolling, 0.45, "red");
        blueNectar = new PieceKind("nectar-blue", "circle", NECTAR_DIAMETER / 2, NECTAR_DIAMETER, NECTAR_DIAMETER, nectarMass,
                BLUE_NECTAR_COLOR, rolling, 0.45, "blue");
    }

    @Override
    public String name() {
        return "biobuzz";
    }

    public PieceKind pollen() { return pollen; }

    /** The two HIVES (red, blue), for tests. */
    List<Hive> hives() { return hives; }
    public PieceKind nectar(String alliance) { return alliance.equals("red") ? redNectar : blueNectar; }

    // ------------------------------------------------------------------ setup

    @Override
    public void attach(SeasonContext ctx) {
        this.ctx = ctx;
        this.robots = List.copyOf(ctx.robots());
        this.pieces = ctx.pieces();
        ctx.warnings().addAll(settingWarnings);
        pieces.setDragCoefficient(dragCoefficient);
        pieces.setSpinEffects(magnusLift, spinDecaySeconds);
        pieces.setVariation(variation, variationSeed);

        // The HIVE frame where robots can run into it: the feet, Frame Foot A, and the legs from the
        // floor up to the top of the robot (they lean in, so a taller robot meets more of each leg).
        // Game pieces only meet the legs at their feet, inside the foot bars' footprint.
        double robotTop = 4 * IN;
        for (RobotView robot : robots) robotTop = Math.max(robotTop, robot.height());
        for (int sx : new int[]{-1, 1}) {
            ctx.world().addStaticRect(sx * (FRAME_HALF_WIDTH - FRAME_FOOT_THICKNESS / 2), 0, FRAME_FOOT_THICKNESS,
                    2 * FRAME_HALF_DEPTH, 0, "HIVE frame");
            ctx.world().addStaticRect(sx * (FOOT_A[0] + FOOT_A[1]) / 2, sx * (FOOT_A[2] + FOOT_A[3]) / 2,
                    FOOT_A[1] - FOOT_A[0], FOOT_A[3] - FOOT_A[2], 0, "HIVE frame");
            for (int sy : new int[]{-1, 1}) {
                double t = Math.min(1, robotTop / LEG_TOP[2]);
                double ax = sx * LEG_FOOT[0], ay = sy * LEG_FOOT[1];
                double bx = ax + t * (sx * LEG_TOP[0] - ax), by = ay + t * (sy * LEG_TOP[1] - ay);
                double len = Math.hypot(bx - ax, by - ay);
                ctx.world().addRobotOnlyRect((ax + bx) / 2, (ay + by) / 2, len + LEG_THICKNESS, LEG_THICKNESS,
                        Math.atan2(by - ay, bx - ax), "HIVE frame");
            }
        }
        pieces.addObstacle(new HiveFrame());
        for (String a : ALLIANCES) {
            Hive h = new Hive(a, a.equals("red") ? -1 : 1, pieces, tipThresholdKg, POLLEN_DIAMETER / 2, hiveInertia);
            h.countsAsAuto = () -> {
                MatchState.Phase p = ctx.match().phase();
                return p == MatchState.Phase.AUTO || p == MatchState.Phase.TRANSITION;
            };
            h.onTip = () -> humans.get(a).credits++;
            hives.add(h);
            pieces.addTarget(h);
            pieces.addObstacle(h);
            humans.put(a, new HumanPlayer(a));
        }
        for (int i = 0; i < FLOWERS.length; i++) {
            Flower f = new Flower(i, FLOWERS[i], pieces);
            double bx = f.wallX + f.nx * FLOWER_BASE_DEPTH / 2, by = f.wallY + f.ny * FLOWER_BASE_DEPTH / 2;
            boolean horizontalWall = f.ny != 0;
            ctx.world().addStaticRect(bx, by, horizontalWall ? FLOWER_BASE_ALONG_WALL : FLOWER_BASE_DEPTH,
                    horizontalWall ? FLOWER_BASE_DEPTH : FLOWER_BASE_ALONG_WALL, 0, "FLOWER");
            f.onEnter = this::onFlowerEntry;
            flowers.add(f);
            pieces.addTarget(f);
        }
        pieces.setOffFieldHandler(this::onOffField);
    }

    @Override
    public void reset() {
        pieces.clear();
        notes.clear();
        foulPoints.clear();
        warned.clear();
        earned.clear();
        watching.clear();
        lastPhase = ctx.match().phase();
        hives.get(0).reset(-1);
        hives.get(1).reset(1);
        for (Flower f : flowers) {
            f.stack.clear();
            for (int i = 0; i < 4; i++) f.addStaged(pieces.create(pollen));
        }
        // GARDENS: 4 POLLEN in a line from the corner nearest the alliance area.
        double r = POLLEN_DIAMETER / 2;
        for (int i = 0; i < 4; i++) {
            pieces.placeOnFloor(pieces.create(pollen), -HALF + r + i * 2 * r + 0.002, -HALF + r + 0.002, 0, 0);
            pieces.placeOnFloor(pieces.create(pollen), HALF - r - i * 2 * r - 0.002, HALF - r - 0.002, 0, 0);
        }
        // 3 NECTAR in each upward CELL; 5 with each human player.
        for (Hive h : hives) {
            PieceKind k = h.alliance.equals("red") ? redNectar : blueNectar;
            for (int i = 0; i < 3; i++) h.preload(pieces.create(k));
            HumanPlayer hp = humans.get(h.alliance);
            hp.pool.clear();
            hp.credits = 0;
            hp.cooldown = 0;
            for (int i = 0; i < 5; i++) {
                GamePiece p = pieces.create(k);
                pieces.takeOffField(p);
                hp.pool.add(p);
            }
        }
        // Robots that aren't present have their 4 pre-loads placed in the center of their
        // alliance's LOADING ZONE against the wall (10.3.1). With one robot simulated, that's
        // its partner's 4 and both opponents' 8.
        String ours = allianceOf(robots.get(0));
        String theirs = ours.equals("red") ? "blue" : "red";
        stageAbsentPreloads(ours, 4 * absentRobots(ours));
        stageAbsentPreloads(theirs, 4 * absentRobots(theirs));
        // Each robot starts with 4 pre-loaded POLLEN (on the tiles touching it if it has no hopper).
        for (RobotView robot : robots) {
            for (int i = 0; i < 4; i++) {
                GamePiece p = pieces.create(pollen);
                if (!robot.preload(p)) {
                    double c = Math.cos(robot.heading()), s = Math.sin(robot.heading());
                    double ahead = robot.frameOffsetX() + robot.length() / 2 + r + 0.005, side = robot.frameOffsetY() + (i - 1.5) * 2 * r;
                    pieces.placeOnFloor(p, robot.x() + c * ahead - s * side, robot.y() + s * ahead + c * side, 0, 0);
                }
            }
        }
    }

    /** A robot's alliance; one with no alliance is counted as red. */
    private static String allianceOf(RobotView robot) {
        return robot.alliance() != null ? robot.alliance() : "red";
    }

    /** How many of an alliance's two robots aren't simulated. */
    private int absentRobots(String alliance) {
        int present = 0;
        for (RobotView robot : robots) if (allianceOf(robot).equals(alliance)) present++;
        return Math.max(0, 2 - present);
    }

    private void stageAbsentPreloads(String alliance, int count) {
        double[] lz = loadingZone(alliance);
        double r = POLLEN_DIAMETER / 2 + 0.002;
        double wallX = alliance.equals("red") ? lz[0] : lz[1];
        double inward = alliance.equals("red") ? 1 : -1;
        double cy = (lz[2] + lz[3]) / 2;
        for (int i = 0; i < count; i++) {
            int row = i / 4, col = i % 4;
            pieces.placeOnFloor(pieces.create(pollen), wallX + inward * (r + row * 2 * r), cy + (col - 1.5) * 2 * r, 0, 0);
        }
    }

    // ------------------------------------------------------------------ vision

    @Override
    public List<org.ftctestbed.sim.vision.Fiducial> fiducials() {
        return tags(hives.get(0).angle, hives.get(1).angle);
    }

    /** The field map: the tags with each HIVE as it starts a match (red's audience CELL up, blue's far CELL up). */
    @Override
    public List<org.ftctestbed.sim.vision.Fiducial> fieldMap() {
        return tags(-CELL_TILT, CELL_TILT);
    }

    /**
     * The 16 tags for these HIVE angles (the far CELL's tilt above level). In a HIVE's own frame
     * (x across, u along toward the far wall, w up when level), a CELL's cluster origin is the
     * middle of its opening; its tags lie on the CELL's underside, 7.19 in below and 5.62 in back
     * from it, facing down, numbered left to right as you look up at them. A tag's top points in
     * toward the pivot: FIRST's AprilTag cluster guide shows the upward CELL facing a robot reading
     * upright (roll -19.7) and the far CELL upside down (roll 159.3), which this gives.
     */
    static List<org.ftctestbed.sim.vision.Fiducial> tags(double redAngle, double blueAngle) {
        List<org.ftctestbed.sim.vision.Fiducial> out = new ArrayList<>();
        for (int c = 0; c < TAG_CLUSTERS.length; c++) {
            int first = TAG_CLUSTERS[c][0], sx = TAG_CLUSTERS[c][1], side = TAG_CLUSTERS[c][2];
            double angle = sx < 0 ? redAngle : blueAngle, hx = sx * HIVE_X;
            double co = Math.cos(angle), si = Math.sin(angle);
            // The HIVE's axes in field coordinates.
            double[] across = {1, 0, 0}, along = {0, co, si}, up = {0, -si, co};
            double u = side * (CELL_OUTER - TAG_BACK), w = (CELL_FLOOR + CELL_ROOF) / 2 - TAG_DOWN;
            // Target space: y down the tag (out of the CELL), z into it (up, away from a camera
            // below), x = y cross z.
            double[] tagY = {side * along[0], side * along[1], side * along[2]};
            double[] tagZ = up;
            double[] tagX = {side, 0, 0};
            for (int i = 0; i < 4; i++) {
                double x = side * TAG_ACROSS[i];
                double[] center = {hx + x * across[0] + u * along[0] + w * up[0],
                        x * across[1] + u * along[1] + w * up[1],
                        PIVOT_HEIGHT + x * across[2] + u * along[2] + w * up[2]};
                out.add(new org.ftctestbed.sim.vision.Fiducial(first + i, "36h11", TAG_SIZE,
                        org.ftctestbed.sim.vision.Frame3.ofAxes(tagX, tagY, tagZ, center), CLUSTER_NAMES[c]));
            }
        }
        return out;
    }

    /** The HIVE frame's feet: low steel bars a camera near the floor can't see past. */
    @Override
    public List<org.ftctestbed.sim.vision.VisionScene.Box> visionOccluders() {
        List<org.ftctestbed.sim.vision.VisionScene.Box> out = new ArrayList<>();
        for (int sx : new int[]{-1, 1}) {
            out.add(new org.ftctestbed.sim.vision.VisionScene.Box(sx * (FRAME_HALF_WIDTH - FRAME_FOOT_THICKNESS / 2), 0,
                    FRAME_FOOT_THICKNESS / 2, FRAME_HALF_DEPTH, 0, 0, 2.15 * IN));
            out.add(new org.ftctestbed.sim.vision.VisionScene.Box(sx * (FOOT_A[0] + FOOT_A[1]) / 2, sx * (FOOT_A[2] + FOOT_A[3]) / 2,
                    (FOOT_A[1] - FOOT_A[0]) / 2, (FOOT_A[3] - FOOT_A[2]) / 2, 0, 0, 2.5 * IN));
        }
        return out;
    }

    @Override
    public List<Retrievable> retrievables() {
        return new ArrayList<>(flowers);
    }

    // ------------------------------------------------------------------ rules

    private void onFlowerEntry(GamePiece p) {
        if (p.kind.alliance() == null || !ctx.match().inMatch()) return;
        double remaining = ctx.match().teleopRemaining(ctx.now());
        if (remaining > 60 && ctx.match().phase() != MatchState.Phase.ENDED && p.lastTouchedBy() != null) {
            foul(p.lastTouchedBy(), MAJOR, "G410 NECTAR entered a FLOWER before the last 60 s");
        }
    }

    private void onOffField(GamePiece p) {
        if (p.kind.alliance() != null) {
            // NECTAR goes back to its drive team, who may re-enter it.
            HumanPlayer hp = humans.get(p.kind.alliance());
            hp.pool.add(p);
            hp.credits++;
            note(p.kind.alliance() + " NECTAR left the field and was returned to the human player");
        } else {
            // POLLEN is put back by field staff near where it left, at the next safe moment.
            pieces.respawnLater(p, 3.0);
        }
    }

    private void foul(String offender, int points, String rule) {
        String other = offender.equals("red") ? "blue" : "red";
        foulPoints.merge(other, points, Integer::sum);
        note(String.format(Locale.US, "%s %s: %s (+%d to %s)", clockLabel(), offender.toUpperCase(), rule, points, other));
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) note(clockLabel() + " " + message);
    }

    private void note(String s) {
        notes.add(s);
        while (notes.size() > 30) notes.remove(0);
    }

    private String clockLabel() {
        double c = ctx.match().clock(ctx.now());
        if (Double.isNaN(c)) return String.format(Locale.US, "[t=%.1f]", ctx.now());
        return String.format(Locale.US, "[%d:%02d]", (int) Math.ceil(c) / 60, (int) Math.ceil(c) % 60);
    }

    @Override
    public void humanPlayerAction(String alliance) {
        HumanPlayer hp = humans.get(alliance);
        if (hp == null || hp.pool.isEmpty()) return;
        boolean allowed = hp.credits > 0 || nectarUnlocked();
        if (ctx.match().inMatch() && !allowed) foul(alliance, MINOR, "G426 NECTAR entered without a TIP before the last 60 s");
        enterNectar(hp, allowed);
    }

    private boolean nectarUnlocked() {
        return ctx.match().inMatch() && ctx.match().teleopRemaining(ctx.now()) <= 60;
    }

    private boolean enterNectar(HumanPlayer hp, boolean useCredit) {
        double[] lz = loadingZone(hp.alliance);
        double r = NECTAR_DIAMETER / 2;
        double x = hp.alliance.equals("red") ? lz[1] - r - 0.01 : lz[0] + r + 0.01;
        double[] slots = {(lz[2] + lz[3]) / 2, lz[2] + 2 * r, lz[3] - 2 * r};
        for (double y : slots) {
            if (!pieces.floorPiecesNear(x, y, 2 * r).isEmpty()) continue;
            if (onARobot(x, y, r)) continue; // can't drop it onto a robot
            GamePiece p = hp.pool.remove(0);
            pieces.placeOnFloor(p, x, y, 0, 0);
            if (useCredit && hp.credits > 0 && !nectarUnlocked()) hp.credits--;
            hp.cooldown = 1.5;
            return true;
        }
        return false;
    }

    /** True if a ball of this radius at (x, y) could touch a robot (any heading: its half-diagonal). */
    private boolean onARobot(double x, double y, double radius) {
        for (RobotView robot : robots) {
            double reach = Math.hypot(robot.length(), robot.width()) / 2 + Math.hypot(robot.frameOffsetX(), robot.frameOffsetY()) + radius;
            if (Math.hypot(robot.x() - x, robot.y() - y) < reach) return true;
        }
        return false;
    }

    @Override
    public void step(double dt) {
        for (Hive h : hives) h.step(dt);
        MatchState match = ctx.match();
        MatchState.Phase phase = match.phase();

        // Human players bring NECTAR in when the rules allow it.
        for (HumanPlayer hp : humans.values()) {
            hp.cooldown -= dt;
            if (!autoHumanPlayer || hp.pool.isEmpty() || hp.cooldown > 0) continue;
            boolean allowed = hp.credits > 0 || nectarUnlocked();
            boolean running = phase == MatchState.Phase.PRACTICE || phase == MatchState.Phase.AUTO
                    || phase == MatchState.Phase.TRANSITION || phase == MatchState.Phase.TELEOP;
            if (allowed && running) enterNectar(hp, true);
        }

        // Robot achievements at phase boundaries.
        if (phase != lastPhase) {
            if (phase == MatchState.Phase.AUTO) {
                earned.clear();
                notes.clear();
                foulPoints.clear();
                warned.clear();
                watching.clear();
            }
            for (RobotView robot : robots) {
                Earned e = earned.computeIfAbsent(robot.number(), n -> new Earned());
                if (lastPhase == MatchState.Phase.AUTO) {
                    e.leave = !touchingWall(robot);
                    e.autoPark = robot.alliance() != null && overlapsRect(robot, loadingZone(robot.alliance()));
                }
                if (phase == MatchState.Phase.ENDED && robot.alliance() != null) {
                    e.teleopPark = overlapsRect(robot, loadingZone(robot.alliance()));
                }
            }
            lastPhase = phase;
        }

        if (match.inMatch() && phase != MatchState.Phase.ENDED) {
            for (RobotView robot : robots) if (robot.alliance() != null) referee(robot, phase, dt);
            pins(dt);
        }
    }

    private String who(RobotView robot) {
        return robots.size() > 1 ? "Robot " + robot.number() : "the robot";
    }

    private Watch watch(RobotView robot) {
        return watching.computeIfAbsent(robot.number(), n -> new Watch());
    }

    /**
     * A YELLOW CARD for the robot's team (at most one per rule per MATCH). A second YELLOW CARD,
     * in this MATCH too, is a RED CARD: the team is DISQUALIFIED from the MATCH (10.6.1).
     */
    private void card(RobotView robot, String rule, String why) {
        Watch w = watch(robot);
        if (!w.cardsFor.add(rule)) return;
        if (w.cardsFor.size() == 1) {
            note(clockLabel() + " YELLOW CARD for " + who(robot) + " (" + rule + "): " + why);
        } else {
            note(clockLabel() + " second YELLOW CARD for " + who(robot) + " (" + rule + "): " + why
                    + ". Two YELLOW CARDS make a RED CARD: the team is DISQUALIFIED from this match");
        }
    }

    /** A MAJOR FOUL that can only be given once per MATCH for this rule (\"MAJOR FOUL ... per MATCH, if STRATEGIC\"). */
    private void majorOnce(RobotView robot, String rule, String why) {
        if (watch(robot).majorsFor.add(rule)) foul(robot.alliance(), MAJOR, rule + " " + why);
    }

    /**
     * The rules a REFEREE watches each robot for. Several are judgment calls (is it STRATEGIC?):
     * the notes say what the manual (TU05) makes likely, and the fouls and cards follow that.
     */
    private void referee(RobotView robot, MatchState.Phase phase, double dt) {
        String who = who(robot);
        String key = ":" + robot.number();
        Watch w = watch(robot);
        List<GamePiece> held = robot.held();

        // G407: no more than 4 SCORING ELEMENTS. 6 or more, or REPEATED instances of 5 (TU05: even
        // with a "keep 4 and discard the rest" sorter), are likely STRATEGIC.
        boolean overFour = held.size() > 4;
        if (overFour && !w.holdingOverFour) {
            w.overFour++;
            if (w.overFour == 1) {
                note(clockLabel() + " G407 VERBAL WARNING: " + who + " controls " + held.size() + " SCORING ELEMENTS (max 4)");
            } else {
                majorOnce(robot, "G407", "REPEATED control of 5+ SCORING ELEMENTS is likely STRATEGIC (TU05)");
                card(robot, "G407", "controlled 5+ SCORING ELEMENTS " + w.overFour + " times this match");
            }
        }
        if (held.size() >= 6) {
            majorOnce(robot, "G407", "controlling " + held.size() + " SCORING ELEMENTS is likely STRATEGIC");
            card(robot, "G407", "controlled " + held.size() + " SCORING ELEMENTS");
        }
        w.holdingOverFour = overFour;

        // G408: don't control the opponent's NECTAR. REPEATED instances are likely STRATEGIC, even
        // with a sorter that spits it back out (TU05).
        boolean opponentNectar = false;
        for (GamePiece p : held) {
            if (p.kind.alliance() != null && !p.kind.alliance().equals(robot.alliance())) opponentNectar = true;
        }
        if (opponentNectar && !w.holdingOpponentNectar) {
            w.opponentNectar++;
            if (w.opponentNectar == 1) {
                note(clockLabel() + " G408 VERBAL WARNING: " + who + " controls the opponent's NECTAR");
            } else {
                card(robot, "G408", "controlled the opponent's NECTAR again (REPEATED is likely STRATEGIC, "
                        + "even if a sorter spits it out: TU05)");
            }
        }
        w.holdingOpponentNectar = opponentNectar;

        // G409: don't catch or deflect what a tipping HIVE spills before it touches anything else.
        for (GamePiece p : pieces.all()) {
            if (p.caughtBy() != robot.number()) continue;
            p.clearCaught();
            w.catches++;
            boolean stopped = w.lastSpeed < STOPPED && underAHive(robot);
            if (stopped) w.stoppedCatches++;
            if (w.catches == 1) {
                note(clockLabel() + " G409 VERBAL WARNING: " + who + " caught a SCORING ELEMENT spilled by a TIPPED HIVE");
            } else if (w.stoppedCatches >= 2) {
                card(robot, "G409", "REPEATEDLY stopped under the HIVE catching what it spills (likely STRATEGIC)");
            } else {
                warnOnce("G409b" + key, "G409 warning: " + who + " caught spilled SCORING ELEMENTS again");
            }
        }

        // G417: don't meddle with the HIVE. Ramming its frame at high speed, or several times in a
        // short time, is likely STRATEGIC.
        boolean onHive = robot.touching().contains("HIVE frame");
        if (onHive && !w.onHive) {
            double impact = w.lastSpeed;
            double now = ctx.now();
            if (impact >= BUMP_SPEED) w.hiveHits.addLast(now);
            while (!w.hiveHits.isEmpty() && now - w.hiveHits.peekFirst() > RAM_WINDOW) w.hiveHits.removeFirst();
            if (impact >= RAM_SPEED) {
                majorOnce(robot, "G417", String.format(Locale.US, "ramming the HIVE frame at %.1f m/s is likely STRATEGIC", impact));
                card(robot, "G417", String.format(Locale.US, "rammed the HIVE frame at %.1f m/s (%.0f in/s)", impact, impact / IN));
            } else if (w.hiveHits.size() >= 2) {
                majorOnce(robot, "G417", "hitting the HIVE frame again and again is likely STRATEGIC");
                card(robot, "G417", "hit the HIVE frame " + w.hiveHits.size() + " times in " + (int) RAM_WINDOW + " s");
            } else if (impact >= BUMP_SPEED) {
                warnOnce("G417" + key, String.format(Locale.US, "G417 VERBAL WARNING: %s bumped the HIVE frame (%.1f m/s)", who, impact));
            }
        }
        w.onHive = onHive;
        w.lastSpeed = robot.speed();

        if (phase == MatchState.Phase.TRANSITION && ctx.match().elapsedInPhase(ctx.now()) > 0.5 && robot.powered()) {
            warnOnce("G403" + key, "G403 warning: powered movement" + (robots.size() > 1 ? " by " + who : "")
                    + " during the AUTO-to-TELEOP transition");
        }
        if (robot.height() > 29 * IN + 1e-3) {
            warnOnce("R105" + key, String.format(Locale.US, "R105 warning: %s is %.1f in tall (max 29 in expanded)", who, robot.height() / IN));
        }
    }

    /** True if the robot's center is under one of the HIVES. */
    private static boolean underAHive(RobotView robot) {
        return Math.abs(Math.abs(robot.x()) - HIVE_X) < 12 * IN && Math.abs(robot.y()) < 18 * IN;
    }

    /**
     * G421: a 3-count on PINS. A robot pins an opponent when it keeps it from moving by contact:
     * they touch, the opponent is driving but stuck, and it's up against a wall or field element
     * (or the pinner). After 3 s, a MAJOR FOUL, and another every 3 s until it ends; the count
     * pauses while they're apart and ends once they've been 2 ft apart for 3 s.
     */
    private void pins(double dt) {
        for (RobotView pinner : robots) {
            if (pinner.alliance() == null) continue;
            Watch w = watch(pinner);
            boolean pinning = false;
            double gap = Double.MAX_VALUE;
            for (RobotView other : robots) {
                if (other == pinner || other.alliance() == null || other.alliance().equals(pinner.alliance())) continue;
                gap = Math.min(gap, Math.hypot(other.x() - pinner.x(), other.y() - pinner.y())
                        - (Math.max(other.length(), other.width()) + Math.max(pinner.length(), pinner.width())) / 2);
                if (pinner.touches(other) && pinner.drivingHard() && other.drivingHard() && other.speed() < 0.05) {
                    // Pinned against something: a wall or a field element (transitively), not just nose to nose.
                    java.util.Set<String> against = new java.util.HashSet<>(other.touching());
                    against.remove("robot");
                    if (!against.isEmpty()) pinning = true;
                }
            }
            if (pinning) {
                w.pinApart = 0;
                double before = w.pinCount;
                w.pinCount += dt;
                int due = (int) Math.floor(w.pinCount / PIN_SECONDS);
                if (due > w.pinFouls) {
                    w.pinFouls = due;
                    foul(pinner.alliance(), MAJOR, String.format(Locale.US, "G421 %s has PINNED an opponent for %.0f s",
                            who(pinner), w.pinCount));
                }
                if (before == 0) note(clockLabel() + " G421 PIN count started: " + who(pinner));
            } else if (w.pinCount > 0) {
                if (gap >= PIN_SEPARATION) w.pinApart += dt;
                if (w.pinApart > PIN_SECONDS) {
                    w.pinCount = 0;
                    w.pinFouls = 0;
                    w.pinApart = 0;
                }
            }
        }
    }

    /** "Robot 2" or "the robot" and its card, for the score breakdown. */
    List<String> cards(String alliance) {
        List<String> out = new ArrayList<>();
        for (RobotView robot : robots) {
            if (!alliance.equals(robot.alliance())) continue;
            Watch w = watching.get(robot.number());
            if (w == null || w.cardsFor.isEmpty()) continue;
            String rules = String.join(", ", new java.util.TreeSet<>(w.cardsFor));
            out.add((w.cardsFor.size() >= 2 ? "RED CARD (DISQUALIFIED) " : "YELLOW CARD ")
                    + (robots.size() > 1 ? "Robot " + robot.number() : "") + " " + rules);
        }
        return out;
    }

    private static boolean touchingWall(RobotView robot) {
        double margin = 0.25 * IN;
        for (double[] c : robot.corners()) {
            if (Math.abs(c[0]) >= HALF - margin || Math.abs(c[1]) >= HALF - margin) return true;
        }
        return false;
    }

    /** True if the robot's footprint is at least partially inside the rectangle. */
    static boolean overlapsRect(RobotView robot, double[] r) {
        double[][] corners = robot.corners();
        for (double[] c : corners) if (inRect(r, c[0], c[1], 0)) return true;
        double[][] rc = {{r[0], r[2]}, {r[1], r[2]}, {r[1], r[3]}, {r[0], r[3]}};
        for (double[] p : rc) if (insidePolygon(corners, p[0], p[1])) return true;
        // Edge crossings (e.g. a thin zone crossing the middle of the robot)
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (segmentsCross(corners[i], corners[(i + 1) % 4], rc[j], rc[(j + 1) % 4])) return true;
            }
        }
        return false;
    }

    private static boolean insidePolygon(double[][] poly, double x, double y) {
        boolean in = false;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            if ((poly[i][1] > y) != (poly[j][1] > y)
                    && x < (poly[j][0] - poly[i][0]) * (y - poly[i][1]) / (poly[j][1] - poly[i][1]) + poly[i][0]) in = !in;
        }
        return in;
    }

    private static boolean segmentsCross(double[] a, double[] b, double[] c, double[] d) {
        double d1 = cross(c, d, a), d2 = cross(c, d, b), d3 = cross(a, b, c), d4 = cross(a, b, d);
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0));
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    // ------------------------------------------------------------------ scoring

    private int gardenCount(String alliance) {
        double[] g = garden(alliance);
        int n = 0;
        for (GamePiece p : pieces.all()) {
            if (p.state() != GamePiece.State.FLOOR) continue;
            if (inRect(g, p.x(), p.y(), p.kind.radius())) n++;
        }
        return n;
    }

    BioBuzzSnapshot.Score score(String alliance) {
        Hive h = hives.get(alliance.equals("red") ? 0 : 1);
        // LEAVE and PARK are per robot: each of the alliance's robots on the field can earn them.
        int leaves = 0, autoParks = 0, teleopParks = 0;
        if (ctx.match().inMatch()) {
            for (RobotView robot : robots) {
                Earned e = earned.get(robot.number());
                if (e == null || !alliance.equals(robot.alliance())) continue;
                if (e.leave) leaves++;
                if (e.autoPark) autoParks++;
                if (e.teleopPark) teleopParks++;
            }
        }
        int owned = 0, bottom = 0;
        for (Flower f : flowers) {
            if (alliance.equals(f.owner())) owned += f.scoredCount();
            if (alliance.equals(f.bottomNectar())) bottom++;
        }
        return new BioBuzzSnapshot.Score(leaves, autoParks, teleopParks,
                h.tipsAuto, h.tipsTeleop, h.scoredCount(), owned, bottom,
                gardenCount(alliance), foulPoints.getOrDefault(alliance, 0), cards(alliance));
    }

    @Override
    public SeasonSnapshot snapshot() {
        List<BioBuzzSnapshot.HiveState> hs = new ArrayList<>();
        for (Hive h : hives) {
            List<Integer> colors = new ArrayList<>();
            for (GamePiece p : h.upContents()) colors.add(p.kind.color());
            List<double[]> at = new ArrayList<>();
            for (Hive.Slot c : h.contents) at.add(new double[]{c.cx, c.u, c.w, c.p.kind.radius(), c.p.kind.color()});
            hs.add(new BioBuzzSnapshot.HiveState(h.alliance, h.x, h.upSide, h.tiltDegrees(), h.tipping(), colors,
                    h.tipsAuto, h.tipsTeleop, h.fill(), at));
        }
        List<BioBuzzSnapshot.FlowerState> fs = new ArrayList<>();
        for (Flower f : flowers) {
            List<Integer> colors = new ArrayList<>();
            for (GamePiece p : f.stack) colors.add(p.kind.color());
            fs.add(new BioBuzzSnapshot.FlowerState(f.index, f.wallX, f.wallY, f.nx, f.ny, f.axisX, f.axisY, colors,
                    f.owner(), f.scoredCount()));
        }
        Map<String, Integer> pool = new LinkedHashMap<>(), credits = new LinkedHashMap<>();
        for (HumanPlayer hp : humans.values()) {
            pool.put(hp.alliance, hp.pool.size());
            credits.put(hp.alliance, nectarUnlocked() ? hp.pool.size() : hp.credits);
        }
        MatchState m = ctx.match();
        return new BioBuzzSnapshot(hs, fs, score("red"), score("blue"), pool, credits, new ArrayList<>(notes),
                m.phase(), m.inMatch() ? m.clock(ctx.now()) : Double.NaN, nectarUnlocked());
    }
}
