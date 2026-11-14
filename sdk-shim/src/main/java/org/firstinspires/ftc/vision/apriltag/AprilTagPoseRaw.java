package org.firstinspires.ftc.vision.apriltag;

import org.firstinspires.ftc.robotcore.external.matrices.MatrixF;

/**
 * A tag's pose as the solver found it, in the camera's frame (x right, y down, z out of the lens),
 * in the processor's output units. R turns the tag's frame (x right, y down, z into the tag) into
 * the camera's.
 */
public class AprilTagPoseRaw {
    public final double x, y, z;
    public final MatrixF R;

    public AprilTagPoseRaw(double x, double y, double z, MatrixF R) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.R = R;
    }
}
