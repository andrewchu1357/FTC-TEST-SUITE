package org.firstinspires.ftc.vision.apriltag;

import org.firstinspires.ftc.robotcore.external.matrices.VectorF;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;

import java.util.ArrayList;

/** The tags (and tag clusters) an AprilTagProcessor knows the size and place of. */
public class AprilTagLibrary {
    private final AprilTagMetadata[] data;
    private final AprilTagClusterMetadata[] clusterData;

    AprilTagLibrary(AprilTagMetadata[] data, AprilTagClusterMetadata[] clusterData) {
        this.data = data;
        this.clusterData = clusterData;
    }

    public AprilTagMetadata[] getAllTags() {
        return data;
    }

    public AprilTagClusterMetadata[] getAllClusters() {
        return clusterData;
    }

    public AprilTagMetadata lookupTag(int id) {
        for (AprilTagMetadata t : data) if (t.id == id) return t;
        return null;
    }

    public AprilTagClusterMetadata lookupCluster(int id) {
        for (AprilTagClusterMetadata c : clusterData) if (c.containsTagId(id)) return c;
        return null;
    }

    public static class Builder {
        private final ArrayList<AprilTagMetadata> singleTagData = new ArrayList<AprilTagMetadata>();
        private final ArrayList<AprilTagClusterMetadata> clusterData = new ArrayList<AprilTagClusterMetadata>();
        private boolean allowOverwrite = false;

        public Builder setAllowOverwrite(boolean allowOverwrite) {
            this.allowOverwrite = allowOverwrite;
            return this;
        }

        public Builder addTag(AprilTagMetadata data) {
            for (AprilTagMetadata t : singleTagData) {
                if (t.id == data.id) {
                    if (allowOverwrite) {
                        singleTagData.remove(t);
                        break;
                    }
                    throw new RuntimeException("You attempted to add a tag to the library when it already contains a tag with that ID. You can call .setAllowOverwrite(true) to allow overwriting the existing entry");
                }
            }
            for (AprilTagClusterMetadata c : clusterData) {
                if (c.containsTagId(data.id)) {
                    throw new RuntimeException("You attempted to add a tag to the library when it contains a tag cluster that has a member with the same tag id");
                }
            }
            singleTagData.add(data);
            return this;
        }

        public Builder addCluster(AprilTagClusterMetadata cluster) {
            for (AprilTagMetadata t : singleTagData) {
                if (cluster.containsTagId(t.id)) {
                    throw new RuntimeException("You attempted to add a tag cluster to the library when a tag ID in that cluster is already contained as a single tag in this library.");
                }
            }
            for (AprilTagClusterMetadata c : clusterData) {
                for (int id : c.getMemberIds()) {
                    if (cluster.containsTagId(id)) {
                        throw new RuntimeException("You attempted to add a tag cluster to the library that has clashing tag ids with another cluster in the library");
                    }
                }
            }
            for (AprilTagClusterMetadata c : clusterData) {
                if (c.name.equals(cluster.name)) {
                    if (allowOverwrite) {
                        clusterData.remove(c);
                        break;
                    }
                    throw new RuntimeException("You attempted to add a tag cluster to the library when it already contains a tag cluster with the same name. You can call .setAllowOverwrite(true) to allow overwriting the existing entry");
                }
            }
            clusterData.add(cluster);
            return this;
        }

        public Builder addTag(int id, String name, double size, VectorF fieldPosition, DistanceUnit distanceUnit, Quaternion fieldOrientation) {
            return addTag(new AprilTagMetadata(id, name, size, fieldPosition, distanceUnit, fieldOrientation));
        }

        public Builder addTag(int id, String name, double size, DistanceUnit distanceUnit) {
            return addTag(new AprilTagMetadata(id, name, size, new VectorF(0, 0, 0), distanceUnit, Quaternion.identityQuaternion()));
        }

        public Builder addTags(AprilTagLibrary library) {
            for (AprilTagMetadata t : library.getAllTags()) addTag(t);
            return this;
        }

        public Builder addClusters(AprilTagLibrary library) {
            for (AprilTagClusterMetadata c : library.getAllClusters()) addCluster(c);
            return this;
        }

        public Builder addLibrary(AprilTagLibrary library) {
            addTags(library);
            addClusters(library);
            return this;
        }

        public AprilTagLibrary build() {
            return new AprilTagLibrary(singleTagData.toArray(new AprilTagMetadata[0]), clusterData.toArray(new AprilTagClusterMetadata[0]));
        }
    }
}
