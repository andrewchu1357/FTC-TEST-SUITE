package org.firstinspires.ftc.teamcode.buzzbot;

import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.hardware.limelightvision.LLResultTypes;
import com.qualcomm.hardware.limelightvision.Limelight3A;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.util.ElapsedTime;

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Position;

import java.util.Locale;

/**
 * BIOBUZZ's AprilTags, used the way FIRST's kickoff describes: "to aid lining up to the HIVE and
 * determining which way it's tipped before launching".
 *
 * Each HIVE CELL has four AprilTags (36h11) on its bottom, 16 on the field:
 *   30-33 red HIVE, far CELL       34-37 red HIVE, audience CELL
 *   38-41 blue HIVE, audience CELL 42-45 blue HIVE, far CELL
 * A HIVE rests tipped one way, so one CELL is up and the other down. The up CELL's tags are about
 * 50 in above the tiles, the down CELL's about 34 in (the pivot is at 44 in), so the height of any
 * tag we see tells which way its HIVE is tipped, even when only one CELL is in view.
 *
 * The Limelight's AprilTag pipeline (slot 3 in TRC's BIOBUZZ setup) gives each tag's position in
 * robot space (x forward, y right, z up from the tiles under the robot's center), which needs the
 * camera's pose on the robot set in the Limelight's web interface. To see the tags, which face
 * down, the camera has to look up: BuzzBot's is 12 in up and tilted up 35 degrees, a placeholder
 * until your mount is decided. Change the mount there and in robot.jsonc together.
 *
 * BuzzBot.update() calls update() once per loop. Without a Limelight in the robot configuration this does nothing
 * and every question returns "don't know" (null).
 */
public class HiveTags {
    /** Tags above this (in) belong to a CELL that's up (the pivot is 43.95 in up). */
    public static final double UP_HEIGHT_IN = 42.0;
    /** The AprilTag pipeline's slot. */
    public static final int PIPELINE = 3;
    /**
     * How much farther the middle of a CELL's tag strip is than the middle of its opening,
     * horizontally, from where you'd shoot (the tags sit 5.6 in back from the opening on the
     * tilted CELL's bottom, which is about 1.5 in toward the pivot).
     */
    public static final double TAGS_BEHIND_OPENING_IN = 1.5;

    /** One of the four CELLs: a HIVE (RED or BLUE) and which side of its pivot the CELL is on. */
    public enum Cell {
        RED_FAR(30), RED_AUDIENCE(34), BLUE_AUDIENCE(38), BLUE_FAR(42);

        public final int firstTag;

        Cell(int firstTag) {
            this.firstTag = firstTag;
        }

        public Alliance hive() {
            return this == RED_FAR || this == RED_AUDIENCE ? Alliance.RED : Alliance.BLUE;
        }

        /** The CELL this tag is on, or null for a tag that isn't on a HIVE. */
        public static Cell ofTag(int id) {
            for (Cell c : values()) if (id >= c.firstTag && id < c.firstTag + 4) return c;
            return null;
        }

        /** An alliance's HIVE CELL that faces up when the match starts (red: audience side, blue: far side). */
        public static Cell start(Alliance a) {
            return a == Alliance.RED ? RED_AUDIENCE : BLUE_FAR;
        }

        /** Its HIVE's other CELL. */
        public static Cell other(Alliance a) {
            return a == Alliance.RED ? RED_FAR : BLUE_AUDIENCE;
        }
    }

    /** What we saw of one CELL's tags in the newest frame. */
    public static final class Sighting {
        public final Cell cell;
        public final int tags;
        /** Degrees right of the camera's crosshair (the middle of the tags seen). */
        public final double bearingDeg;
        /** Horizontal distance from the robot's center to the middle of the tags (in). */
        public final double distanceIn;
        /** Height of the tags above the tiles (in). */
        public final double heightIn;

        Sighting(Cell cell, int tags, double bearingDeg, double distanceIn, double heightIn) {
            this.cell = cell;
            this.tags = tags;
            this.bearingDeg = bearingDeg;
            this.distanceIn = distanceIn;
            this.heightIn = heightIn;
        }

        public boolean up() {
            return heightIn > UP_HEIGHT_IN;
        }
    }

    private final Limelight3A limelight;
    private final ElapsedTime clock = new ElapsedTime();
    private final Sighting[] seen = new Sighting[4];
    private final double[] upSeenAt = {Double.NaN, Double.NaN}; // per HIVE (RED, BLUE): when we last saw its tags
    private final boolean[] startUp = new boolean[2];          // per HIVE: its start CELL is up

    /** Uses the robot's Limelight named "limelight" if it has one. */
    public HiveTags(HardwareMap hw) {
        this(hw.tryGet(Limelight3A.class, "limelight"));
    }

    public HiveTags(Limelight3A limelight) {
        this.limelight = limelight;
        if (limelight != null) {
            limelight.pipelineSwitch(PIPELINE);
            limelight.start();
        }
    }

    public boolean hasCamera() {
        return limelight != null;
    }

    /** Reads the newest AprilTag result (BuzzBot.update() calls this). */
    public void update() {
        double now = clock.seconds();
        for (int i = 0; i < seen.length; i++) seen[i] = null;
        if (limelight == null) return;
        LLResult r = limelight.getLatestResult();
        if (r == null || !r.isValid() || r.getPipelineIndex() != PIPELINE) return;
        double[] sumTx = new double[4], sumDist = new double[4], sumZ = new double[4];
        int[] count = new int[4];
        for (LLResultTypes.FiducialResult f : r.getFiducialResults()) {
            Cell c = Cell.ofTag(f.getFiducialId());
            if (c == null) continue;
            Position p = f.getTargetPoseRobotSpace().getPosition().toUnit(DistanceUnit.INCH);
            int i = c.ordinal();
            sumTx[i] += f.getTargetXDegrees();
            sumDist[i] += Math.hypot(p.x, p.y);
            sumZ[i] += p.z;
            count[i]++;
        }
        for (Cell c : Cell.values()) {
            int i = c.ordinal();
            if (count[i] == 0) continue;
            Sighting s = new Sighting(c, count[i], sumTx[i] / count[i], sumDist[i] / count[i], sumZ[i] / count[i]);
            seen[i] = s;
            int h = c.hive().ordinal();
            boolean isStart = c == Cell.start(c.hive());
            // This CELL up means it's the up one; down means its HIVE's other CELL is up.
            startUp[h] = isStart == s.up();
            upSeenAt[h] = now;
        }
    }

    /** The newest frame's view of this CELL's tags, or null if none were in it. */
    public Sighting sighting(Cell c) {
        return seen[c.ordinal()];
    }

    /**
     * Whether this alliance's HIVE had its start CELL up (true) or its other CELL up (false) when
     * we last saw its tags, or null if we never have. A HIVE only tips when someone scores in it,
     * so this stays true until the next time the camera sees it tipped the other way. (A CELL
     * that's down has its tags facing the pivot, so from its own side you often can't see them.)
     */
    public Boolean startCellUp(Alliance hive) {
        int h = hive.ordinal();
        return Double.isNaN(upSeenAt[h]) ? null : startUp[h];
    }

    /** Seconds since we last saw this HIVE's tags (infinite if never). */
    public double secondsSinceSeen(Alliance hive) {
        double t = upSeenAt[hive.ordinal()];
        return Double.isNaN(t) ? Double.POSITIVE_INFINITY : clock.seconds() - t;
    }

    /** Horizontal distance (in) from the launcher (1 in ahead of center) to the CELL's opening, from its tags. */
    public static double launcherDistanceToOpening(Sighting s) {
        return s.distanceIn - TAGS_BEHIND_OPENING_IN - 1;
    }

    /** One line for telemetry: which way the HIVES are tipped, as far as the camera knows. */
    public String describe() {
        if (limelight == null) return "no Limelight";
        StringBuilder sb = new StringBuilder();
        for (Alliance hive : Alliance.values()) {
            Boolean start = startCellUp(hive);
            if (start == null) continue;
            Cell up = start ? Cell.start(hive) : Cell.other(hive);
            if (sb.length() > 0) sb.append(", ");
            sb.append(String.format(Locale.US, "%s HIVE: %s CELL up", hive, up == Cell.RED_FAR || up == Cell.BLUE_FAR ? "far" : "audience"));
            double ago = secondsSinceSeen(hive);
            if (ago > 1) sb.append(String.format(Locale.US, " (%.0f s ago)", ago));
        }
        return sb.length() == 0 ? "no HIVE tags seen yet" : sb.toString();
    }
}
