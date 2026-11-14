package org.firstinspires.ftc.vision.apriltag;

import org.firstinspires.ftc.robotcore.external.matrices.VectorF;

/** One tag of a cluster: where its center is in the cluster's frame. */
public class AprilTagClusterMemberMetadata {
    public final int id;
    public final VectorF positionInClusterPlane;
    public final double tagsize;

    public AprilTagClusterMemberMetadata(int id, VectorF positionInClusterPlane, double tagsize) {
        this.id = id;
        this.positionInClusterPlane = positionInClusterPlane;
        this.tagsize = tagsize;
    }
}
