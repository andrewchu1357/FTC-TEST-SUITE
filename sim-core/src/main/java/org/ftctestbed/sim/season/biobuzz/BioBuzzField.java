package org.ftctestbed.sim.season.biobuzz;

/**
 * BIOBUZZ (2026-27) field geometry from the Competition Manual (TU04), section 9, and FIRST's
 * official field CAD (am-5850 BIOBUZZ, STEP v26-27.2), measured part by part. Field coordinates
 * in meters: origin at the field center, +x toward the blue alliance wall, +y away from the
 * audience. Values marked "estimate" are in neither.
 */
public final class BioBuzzField {
    private BioBuzzField() {}

    public static final double IN = 0.0254;
    /** The CAD's wall rails' inside faces are 141.17 in apart (6 tiles, not the nominal 144 in). */
    public static final double HALF = 70.585 * IN;

    // ---- Scoring elements (9.8) ----
    public static final double POLLEN_DIAMETER = 2.8 * IN;
    public static final double NECTAR_DIAMETER = 3.6 * IN;
    public static final int POLLEN_COLOR = 0xF2CF1D;
    public static final int RED_NECTAR_COLOR = 0xD7263D;
    public static final int BLUE_NECTAR_COLOR = 0x2E6FD8;

    // ---- HIVE structure (9.6) ----
    /** Each HIVE's pivot is on its alliance's half: red at -x, blue at +x (25.5 in apart). */
    public static final double HIVE_X = 12.75 * IN;
    public static final double PIVOT_HEIGHT = 43.95 * IN;
    /** A-frame feet (CAD am-5878 Sheet Metal Foot Bar): 49.46 in apart outside, 2 in wide, 2.15 in tall, 38.95 in long. */
    public static final double FRAME_HALF_WIDTH = 49.46 / 2 * IN;
    public static final double FRAME_HALF_DEPTH = 38.95 / 2 * IN;
    public static final double FRAME_FOOT_THICKNESS = 1.98 * IN;
    /**
     * Frame Foot A (am-5879-A) widens one end of each foot bar to 22.2-25.0 in from the center,
     * from 7.44 to 19.45 in along it (the far end on blue's foot, the audience end on red's).
     */
    public static final double[] FOOT_A = {22.2 * IN, 25.0 * IN, 7.44 * IN, 19.45 * IN};
    /**
     * The A-frame legs (am-5876) lean in from the feet to the top bar: the center of each leg at
     * the floor and where it crosses LEG_TOP_HEIGHT (a tall robot's top). Robots below that run
     * into the part of the leg between the two.
     */
    public static final double[] LEG_FOOT = {23.65 * IN, 18.5 * IN};
    public static final double[] LEG_TOP = {13.5 * IN, 2.0 * IN, 41.0 * IN};
    public static final double LEG_THICKNESS = 1.0 * IN;
    public static final double CELL_TILT = Math.toRadians(30);
    /**
     * The upward CELL's opening is its outer end: 20 in wide by 14 in tall, tilted 30 degrees
     * back from vertical, facing away from the pivot (toward the audience or far wall) and
     * slightly up. 14 in x cos 30 = 12.1 in, matching the manual's 53.5-65.6 in opening heights.
     */
    public static final double MOUTH_HALF_WIDTH = 10 * IN;
    public static final double MOUTH_HALF_HEIGHT = 7 * IN;
    public static final double MOUTH_HEIGHT = 59.55 * IN;
    /** Horizontal distance of the opening's center from the pivot line (CAD: the outer ribs). */
    public static final double MOUTH_OFFSET_Y = 16.5 * IN;
    /** Opening to the back skin (am-5871), along the CELL. */
    public static final double CELL_DEPTH = 12.67 * IN;
    /**
     * Each CELL as a box in the HIVE's own frame: pivot at the origin, u along the HIVE (toward the
     * far wall), w "up" when the HIVE is level; the far CELL is at +u, the audience CELL at -u.
     * Tilted 30 degrees these give the CAD: the opening 53.5-65.6 in up, 16.5 in from the pivot,
     * and the back corner where the pre-loaded NECTAR sit (9.5 in out, 49.9 in up).
     */
    public static final double CELL_INNER = 9.42 * IN;   // back skin (am-5871)
    public static final double CELL_OUTER = 22.09 * IN;  // the opening
    public static final double CELL_FLOOR = -1.74 * IN;  // bottom skin (am-5888)
    public static final double CELL_ROOF = 12.26 * IN;   // top skin (am-5869)
    public static final double CELL_HALF_WIDTH = 10 * IN;

    // ---- FLOWERS (9.7): on the walls, 4 in opening 21.4 in up, retrieval opening at the bottom ----
    /** Where each FLOWER's axis meets the wall along it (CAD: 23.39 in from the center). */
    public static final double FLOWER_ALONG_WALL = 23.395 * IN;
    /** Wall attachment point and inward normal for each FLOWER. */
    public static final double[][] FLOWERS = {
            {-FLOWER_ALONG_WALL, HALF, 0, -1},
            {HALF, FLOWER_ALONG_WALL, -1, 0},
            {FLOWER_ALONG_WALL, -HALF, 0, 1},
            {-HALF, -FLOWER_ALONG_WALL, 1, 0},
    };
    /** Center of the 4 HIPS pipes (am-5862) from the wall. */
    public static final double FLOWER_AXIS_FROM_WALL = 2.54 * IN;
    /** Footprint of the layers (am-5858 Layer B, the widest below robot height). */
    public static final double FLOWER_BASE_ALONG_WALL = 5.95 * IN;
    public static final double FLOWER_BASE_DEPTH = 5.05 * IN;
    /** Top of the top ring (am-5857 Layer C). */
    public static final double FLOWER_TOP = 21.4 * IN;
    public static final double FLOWER_OPENING_RADIUS = 2.0 * IN;
    /** The bottom piece stands on the tiles inside the bottom ring (CAD: the staged POLLEN). */
    public static final double FLOWER_LOWER_RING_TOP = 0.02 * IN;
    /** Bottom of the scoring volume: the top of the middle ring (am-5858 Layer B, 3.9-5.25 in up). */
    public static final double FLOWER_SCORING_BOTTOM = 5.25 * IN;

    // ---- Zones (9.3), as {xMin, xMax, yMin, yMax}, to the outside edges of the CAD's tape ----
    public static final double[] RED_LOADING_ZONE = {-HALF, -59.10 * IN, 23.91 * IN, 46.60 * IN};
    public static final double[] BLUE_LOADING_ZONE = {59.10 * IN, HALF, -46.60 * IN, -23.91 * IN};
    public static final double[] RED_GARDEN = {-HALF, -47.41 * IN, -HALF, -68.10 * IN};
    public static final double[] BLUE_GARDEN = {47.41 * IN, HALF, 68.10 * IN, HALF};

    // ---- AprilTags (9.9): clusters under each CELL ----
    /** {first tag id, hive x sign (-1 red / +1 blue), cell side (-1 audience / +1 far)}. */
    public static final int[][] TAG_CLUSTERS = {
            {30, -1, 1}, {34, -1, -1}, {38, 1, -1}, {42, 1, 1},
    };
    /** BIOBUZZ AprilTags: 36h11, 3.25 in (the black square). */
    public static final double TAG_SIZE = 3.25 * IN;
    /**
     * Each cluster's 4 tags across its CELL, and where they sit from the cluster's origin (the middle
     * of the CELL's opening): 7.19 in below it and 5.62 in back toward the pivot (SDK 12.0 library).
     */
    public static final double[] TAG_ACROSS = {-6.5 * IN, -2.75 * IN, 2.75 * IN, 6.5 * IN};
    public static final double TAG_DOWN = 7.1874 * IN, TAG_BACK = 5.622 * IN;
    public static final String[] CLUSTER_NAMES = {"RED SCORING", "RED AUDIENCE", "BLUE AUDIENCE", "BLUE SCORING"};

    public static double hiveX(String alliance) {
        return alliance.equals("red") ? -HIVE_X : HIVE_X;
    }

    public static double[] loadingZone(String alliance) {
        return alliance.equals("red") ? RED_LOADING_ZONE : BLUE_LOADING_ZONE;
    }

    public static double[] garden(String alliance) {
        return alliance.equals("red") ? RED_GARDEN : BLUE_GARDEN;
    }

    public static boolean inRect(double[] r, double x, double y, double margin) {
        return x >= r[0] - margin && x <= r[1] + margin && y >= r[2] - margin && y <= r[3] + margin;
    }
}
