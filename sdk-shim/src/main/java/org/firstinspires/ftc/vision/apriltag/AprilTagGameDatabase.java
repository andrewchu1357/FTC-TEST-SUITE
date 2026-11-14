package org.firstinspires.ftc.vision.apriltag;

import org.firstinspires.ftc.robotcore.external.matrices.VectorF;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;

import java.util.ArrayList;

/** FTC's tag libraries for each season, with the SDK 12.0 values. */
public class AprilTagGameDatabase {
    /** The sample tags and this season's (BIOBUZZ) clusters. */
    public static AprilTagLibrary getCurrentGameTagLibrary() {
        return new AprilTagLibrary.Builder()
                .addLibrary(getSampleTagLibrary())
                .addLibrary(getBioBuzzTagLibrary())
                .build();
    }

    public static AprilTagLibrary getCenterStageTagLibrary() {
        return new AprilTagLibrary.Builder()
                .addTag(1, "BlueAllianceLeft", 2, new VectorF(60.25f, 41.41f, 4f), DistanceUnit.INCH, q(0.3535534f, -0.6123724f, 0.6123724f, -0.3535534f))
                .addTag(2, "BlueAllianceCenter", 2, new VectorF(60.25f, 35.41f, 4f), DistanceUnit.INCH, q(0.3535534f, -0.6123724f, 0.6123724f, -0.3535534f))
                .addTag(3, "BlueAllianceRight", 2, new VectorF(60.25f, 29.41f, 4f), DistanceUnit.INCH, q(0.3535534f, -0.6123724f, 0.6123724f, -0.3535534f))
                .addTag(4, "RedAllianceLeft", 2, new VectorF(60.25f, -29.41f, 4f), DistanceUnit.INCH, q(0.3535534f, -0.6123724f, 0.6123724f, -0.3535534f))
                .addTag(5, "RedAllianceCenter", 2, new VectorF(60.25f, -35.41f, 4f), DistanceUnit.INCH, q(0.3535534f, -0.6123724f, 0.6123724f, -0.3535534f))
                .addTag(6, "RedAllianceRight", 2, new VectorF(60.25f, -41.41f, 4f), DistanceUnit.INCH, q(0.3535534f, -0.6123724f, 0.6123724f, -0.3535534f))
                .addTag(7, "RedAudienceWallLarge", 5, new VectorF(-70.25f, -40.625f, 5.5f), DistanceUnit.INCH, q(0.5f, -0.5f, -0.5f, 0.5f))
                .addTag(8, "RedAudienceWallSmall", 2, new VectorF(-70.25f, -35.125f, 4f), DistanceUnit.INCH, q(0.5f, -0.5f, -0.5f, 0.5f))
                .addTag(9, "BlueAudienceWallSmall", 2, new VectorF(-70.25f, 35.125f, 4f), DistanceUnit.INCH, q(0.5f, -0.5f, -0.5f, 0.5f))
                .addTag(10, "BlueAudienceWallLarge", 5, new VectorF(-70.25f, 40.625f, 5.5f), DistanceUnit.INCH, q(0.5f, -0.5f, -0.5f, 0.5f))
                .build();
    }

    public static AprilTagLibrary getIntoTheDeepTagLibrary() {
        return new AprilTagLibrary.Builder()
                .addTag(11, "BlueAudienceWall", 4, new VectorF(-70.25f, 46.83f, 5.75f), DistanceUnit.INCH, q(0.5f, -0.5f, -0.5f, 0.5f))
                .addTag(12, "BlueAllianceWall", 4, new VectorF(0f, 70.25f, 5.75f), DistanceUnit.INCH, q(0.7071067f, -0.7071067f, 0f, 0f))
                .addTag(13, "BlueRearWall", 4, new VectorF(70.25f, 46.83f, 5.75f), DistanceUnit.INCH, q(0.5f, -0.5f, 0.5f, -0.5f))
                .addTag(14, "RedRearWall", 4, new VectorF(70.25f, -46.83f, 5.75f), DistanceUnit.INCH, q(0.5f, -0.5f, 0.5f, -0.5f))
                .addTag(15, "RedAllianceWall", 4, new VectorF(0f, -70.25f, 5.75f), DistanceUnit.INCH, q(0f, 0f, 0.7071067f, -0.7071067f))
                .addTag(16, "RedAudienceWall", 4, new VectorF(-70.25f, -46.83f, 5.75f), DistanceUnit.INCH, q(0.5f, -0.5f, -0.5f, 0.5f))
                .build();
    }

    public static AprilTagLibrary getDecodeTagLibrary() {
        return new AprilTagLibrary.Builder()
                .addTag(20, "BlueTarget", 6.5, new VectorF(-58.3727f, -55.6425f, 29.5f), DistanceUnit.INCH, q(0.2182149f, -0.2182149f, -0.6725937f, 0.6725937f))
                .addTag(21, "Obelisk_GPP", 6.5, DistanceUnit.INCH)
                .addTag(22, "Obelisk_PGP", 6.5, DistanceUnit.INCH)
                .addTag(23, "Obelisk_PPG", 6.5, DistanceUnit.INCH)
                .addTag(24, "RedTarget", 6.5, new VectorF(-58.3727f, 55.6425f, 29.5f), DistanceUnit.INCH, q(0.6725937f, -0.6725937f, -0.2182149f, 0.2182149f))
                .build();
    }

    /**
     * BIOBUZZ: four tags under each of the four CELLs, as clusters. The CELLs tip with the HIVE,
     * so the clusters have no fixed field position (a cluster detection's robotPose is relative to
     * the cluster).
     */
    public static AprilTagLibrary getBioBuzzTagLibrary() {
        return new AprilTagLibrary.Builder()
                .addCluster(new AprilTagClusterMetadata(getBioBuzzCluster(30), "RED SCORING", "RED SCORING", new VectorF(0, 0, 0), DistanceUnit.INCH, Quaternion.identityQuaternion()))
                .addCluster(new AprilTagClusterMetadata(getBioBuzzCluster(34), "RED AUDIENCE", "RED AUDIENCE", new VectorF(0, 0, 0), DistanceUnit.INCH, Quaternion.identityQuaternion()))
                .addCluster(new AprilTagClusterMetadata(getBioBuzzCluster(38), "BLUE AUDIENCE", "BLUE AUDIENCE", new VectorF(0, 0, 0), DistanceUnit.INCH, Quaternion.identityQuaternion()))
                .addCluster(new AprilTagClusterMetadata(getBioBuzzCluster(42), "BLUE SCORING", "BLUE SCORING", new VectorF(0, 0, 0), DistanceUnit.INCH, Quaternion.identityQuaternion()))
                .build();
    }

    private static ArrayList<AprilTagClusterMemberMetadata> getBioBuzzCluster(int firstId) {
        double size = 3.25;
        float down = 7.1874f, back = -5.622f;
        ArrayList<AprilTagClusterMemberMetadata> members = new ArrayList<AprilTagClusterMemberMetadata>();
        members.add(new AprilTagClusterMemberMetadata(firstId, new VectorF(-6.5f, down, back), size));
        members.add(new AprilTagClusterMemberMetadata(firstId + 1, new VectorF(-2.75f, down, back), size));
        members.add(new AprilTagClusterMemberMetadata(firstId + 2, new VectorF(2.75f, down, back), size));
        members.add(new AprilTagClusterMemberMetadata(firstId + 3, new VectorF(6.5f, down, back), size));
        return members;
    }

    /** The tags in the SDK's sample tag sheet (and a two-tag "Center Goal" cluster). */
    public static AprilTagLibrary getSampleTagLibrary() {
        ArrayList<AprilTagClusterMemberMetadata> goal = new ArrayList<AprilTagClusterMemberMetadata>();
        goal.add(new AprilTagClusterMemberMetadata(581, new VectorF(-2f, 0, 0), 3.0));
        goal.add(new AprilTagClusterMemberMetadata(582, new VectorF(2f, 0, 0), 3.0));
        return new AprilTagLibrary.Builder()
                .addTag(583, "Robbie", 2, DistanceUnit.INCH)
                .addTag(584, "Number 5", 4, DistanceUnit.INCH)
                .addTag(585, "C3PO", 6, DistanceUnit.INCH)
                .addTag(586, "K9", 6, DistanceUnit.INCH)
                .addCluster(new AprilTagClusterMetadata(goal, "Center Goal", "GOAL", new VectorF(0, 0, 0), DistanceUnit.INCH, Quaternion.identityQuaternion()))
                .build();
    }

    private static Quaternion q(float w, float x, float y, float z) {
        return new Quaternion(w, x, y, z, 0);
    }
}
