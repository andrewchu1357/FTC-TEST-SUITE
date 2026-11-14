package org.firstinspires.ftc.teamcode.buzzbot;

/**
 * Hands the robot's pose and alliance from AUTO to TELEOP. Static fields survive between
 * OpModes because the Robot Controller app keeps running.
 */
public final class PoseStorage {
    private PoseStorage() {}

    public static Pose pose = null;
    public static Alliance alliance = Alliance.RED;
}
