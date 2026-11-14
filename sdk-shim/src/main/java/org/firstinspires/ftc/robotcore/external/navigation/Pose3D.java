package org.firstinspires.ftc.robotcore.external.navigation;

import java.util.Locale;

/** A position and orientation in 3D (what a Limelight's botpose and an AprilTag's robotPose give). */
public class Pose3D {
    protected final Position position;
    protected final YawPitchRollAngles orientation;

    public Pose3D(Position position, YawPitchRollAngles orientation) {
        this.position = position;
        this.orientation = orientation;
    }

    @Override
    public String toString() {
        return String.format(Locale.getDefault(), "position=%s, orientation=%s", position.toString(), orientation.toString());
    }

    public Position getPosition() { return position; }

    public YawPitchRollAngles getOrientation() { return orientation; }
}
