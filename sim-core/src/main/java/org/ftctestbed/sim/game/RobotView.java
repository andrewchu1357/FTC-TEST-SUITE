package org.ftctestbed.sim.game;

import java.util.List;

/** The simulated robot as the referee sees it. Field coordinates, meters. */
public interface RobotView {
    double x();

    double y();

    double heading();

    double length();

    double width();

    /** Where the middle of the frame is from the robot's center (robot frame, m): see RobotConfig. */
    default double frameOffsetX() {
        return 0;
    }

    default double frameOffsetY() {
        return 0;
    }

    /** Current height including raised mechanisms. */
    double height();

    /** "red", "blue" or null. */
    String alliance();

    /** Pieces the robot controls right now. */
    List<GamePiece> held();

    /** Load a piece into the robot before the match. False if it has no room. */
    boolean preload(GamePiece p);

    /** True if any motor is being driven (for "robots must be motionless" rules). */
    boolean powered();

    /** 1 for the first robot, 2 for its partner, ... (for referee notes). */
    default int number() { return 1; }

    /** How fast it's moving (m/s). */
    default double speed() { return 0; }

    /** What it's in contact with: "wall", "HIVE frame", "FLOWER", "robot". */
    default java.util.Set<String> touching() { return java.util.Set.of(); }

    /** True if it's in contact with that robot. */
    default boolean touches(RobotView other) { return false; }

    /** True if its drive wheels are being driven hard (its driver is trying to move it). */
    default boolean drivingHard() { return false; }

    /** The robot's four corners in field coordinates, as {x, y} pairs. */
    default double[][] corners() {
        double c = Math.cos(heading()), s = Math.sin(heading());
        double hl = length() / 2, hw = width() / 2;
        double[][] local = {{hl, hw}, {hl, -hw}, {-hl, -hw}, {-hl, hw}};
        double[][] out = new double[4][];
        for (int i = 0; i < 4; i++) {
            out[i] = new double[]{x() + c * local[i][0] - s * local[i][1], y() + s * local[i][0] + c * local[i][1]};
        }
        return out;
    }
}
