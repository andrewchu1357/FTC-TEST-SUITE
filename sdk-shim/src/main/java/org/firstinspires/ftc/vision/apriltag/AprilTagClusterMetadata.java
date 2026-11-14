package org.firstinspires.ftc.vision.apriltag;

import org.firstinspires.ftc.robotcore.external.matrices.VectorF;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;

import java.util.ArrayList;

/**
 * Tags fixed together (a BIOBUZZ CELL's four): the processor solves one pose for the cluster's
 * frame from every member it sees. Members' axes are the cluster frame's axes.
 */
public class AprilTagClusterMetadata {
    public final String name;
    public final String shortName;
    public final DistanceUnit distanceUnit;
    public final VectorF fieldPosition;
    public final Quaternion fieldOrientation;
    double xExtentMin = Double.POSITIVE_INFINITY, xExtentMax = Double.NEGATIVE_INFINITY;
    double yExtentMin = Double.POSITIVE_INFINITY, yExtentMax = Double.NEGATIVE_INFINITY;
    double zExtentMin = Double.POSITIVE_INFINITY, zExtentMax = Double.NEGATIVE_INFINITY;
    double largestTagSize = 0;
    ArrayList<AprilTagClusterMemberMetadata> clusterMembers;

    public AprilTagClusterMetadata(ArrayList<AprilTagClusterMemberMetadata> clusterMembers, String name, String shortName,
                                   VectorF fieldPosition, DistanceUnit distanceUnit, Quaternion fieldOrientation) {
        if (clusterMembers.size() < 2) throw new IllegalArgumentException("Cannot make a cluster with less than 2 tags");
        for (int i = 0; i < clusterMembers.size(); i++) {
            for (int j = 0; j < clusterMembers.size(); j++) {
                if (i != j && clusterMembers.get(i).id == clusterMembers.get(j).id) {
                    throw new IllegalArgumentException("Duplicate tag ID " + clusterMembers.get(i).id + " in cluster " + name);
                }
            }
        }
        for (AprilTagClusterMemberMetadata m : clusterMembers) {
            double h = m.tagsize / 2;
            xExtentMin = Math.min(xExtentMin, m.positionInClusterPlane.get(0) - h);
            xExtentMax = Math.max(xExtentMax, m.positionInClusterPlane.get(0) + h);
            yExtentMin = Math.min(yExtentMin, m.positionInClusterPlane.get(1) - h);
            yExtentMax = Math.max(yExtentMax, m.positionInClusterPlane.get(1) + h);
            zExtentMin = Math.min(zExtentMin, m.positionInClusterPlane.get(2));
            zExtentMax = Math.max(zExtentMax, m.positionInClusterPlane.get(2));
            largestTagSize = Math.max(largestTagSize, m.tagsize);
        }
        this.clusterMembers = clusterMembers;
        this.name = name;
        this.shortName = shortName;
        this.fieldPosition = fieldPosition;
        this.distanceUnit = distanceUnit;
        this.fieldOrientation = fieldOrientation;
    }

    protected boolean containsTagId(int id) {
        return getMemberMetadata(id) != null;
    }

    protected ArrayList<Integer> getMemberIds() {
        ArrayList<Integer> ids = new ArrayList<Integer>();
        for (AprilTagClusterMemberMetadata m : clusterMembers) ids.add(m.id);
        return ids;
    }

    protected AprilTagClusterMemberMetadata getMemberMetadata(int id) {
        for (AprilTagClusterMemberMetadata m : clusterMembers) if (m.id == id) return m;
        return null;
    }
}
