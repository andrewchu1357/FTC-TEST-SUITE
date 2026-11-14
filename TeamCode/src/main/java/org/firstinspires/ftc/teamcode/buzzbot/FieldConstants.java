package org.firstinspires.ftc.teamcode.buzzbot;

/**
 * BIOBUZZ field locations in RED alliance coordinates (inches), from FIRST's field CAD. Origin
 * at the field center, +x toward the blue wall, +y away from the audience. Use Alliance.map()
 * for blue. A real field is 141.17 in between the walls (6 tiles of about 23.5 in), so the
 * walls are at +/-70.585, not 72.
 *
 * Each HIVE CELL opens outward - away from the HIVE's pivot and toward the audience or far
 * wall - so you shoot a CELL from its own side of the field. Our HIVE's "start" CELL faces
 * up when the match begins (red: audience side, blue: far side); after each tip the other
 * CELL faces up.
 */
public final class FieldConstants {
    private FieldConstants() {}

    /** Opening of the CELL that faces up at the start of the match (red: audience side). */
    public static final double[] START_CELL_OPENING = {-12.75, -16.5};
    /** Opening of the other CELL (faces up after an odd number of tips). */
    public static final double[] OTHER_CELL_OPENING = {-12.75, 16.5};

    /** Half the field: the walls' inside faces. */
    public static final double WALL = 70.585;

    /** Starting spots for our 18 in robot, back against the wall. */
    public static final Pose START_WALL = Pose.degrees(-WALL + 9, 0, 0);
    public static final Pose START_AUDIENCE = Pose.degrees(-38, -WALL + 9, 90);

    /** Good shooting spots: 45 in from each opening, 30 degrees off straight-on toward our wall. */
    public static final Pose START_CELL_SHOT = shotSpot(START_CELL_OPENING, -120, 45);
    public static final Pose OTHER_CELL_SHOT = shotSpot(OTHER_CELL_OPENING, 120, 45);

    /** Red GARDEN POLLEN lie against the audience wall from x = -69.2 to -60.8 (y = -69.2). */
    public static final Pose GARDEN_APPROACH = Pose.degrees(-44, -WALL + 9.2, 180);
    public static final Pose GARDEN_SWEEP_END = Pose.degrees(-WALL + 9.2, -WALL + 9.2, 180);

    /**
     * Partially inside the red LOADING ZONE (x < -59.1, 23.9 < y < 46.6), off the wall, facing
     * it. A partner robot that isn't on the field has its 4 pre-loaded POLLEN placed against the
     * wall here, so parking with the intake running picks them up for TELEOP.
     */
    // 2 in off the wall: the intake reaches the partner's pre-loads against the wall even with an
    // inch or two of odometry error built up by the end of AUTO.
    public static final Pose PARK = Pose.degrees(-WALL + 11, 35.25, 180);

    /** The two FLOWERS nearest red: on the red wall (FLOWER 4) and the far wall (FLOWER 1), where their axes meet the wall. */
    public static final double[] WALL_FLOWER = {-WALL, -23.4};
    public static final double[] FAR_FLOWER = {-23.4, WALL};

    /**
     * Where to put the robot center to shoot at an opening from distanceIn away (measured
     * from the launcher, which is 1 in ahead of center), approaching from approachDeg.
     */
    public static Pose shotSpot(double[] opening, double approachDeg, double distanceIn) {
        double a = Math.toRadians(approachDeg);
        double lx = opening[0] + Math.cos(a) * distanceIn, ly = opening[1] + Math.sin(a) * distanceIn;
        double heading = Math.atan2(opening[1] - ly, opening[0] - lx);
        return new Pose(lx - Math.cos(heading), ly - Math.sin(heading), heading);
    }
}
