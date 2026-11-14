package com.qualcomm.hardware.limelightvision;

import java.util.ArrayList;
import java.util.List;

/** Where the AprilTags are on the field, for the Limelight's robot pose (botpose / MegaTag). */
public class LLFieldMap {
    /** One tag: id, size (mm), family, and its pose as a 4x4 row-major transform (meters). */
    public static class Fiducial {
        private final int id;
        private final double size;
        private final String family;
        private final List<Double> transform;
        private final boolean unique;

        public Fiducial() {
            this(0, 0, "apriltag3_36h11_classic", new ArrayList<Double>(), true);
        }

        public Fiducial(int id, double size, String family, List<Double> transform, boolean unique) {
            this.id = id;
            this.size = size;
            this.family = family;
            this.transform = transform;
            this.unique = unique;
        }

        public int getId() { return id; }
        public double getSize() { return size; }
        public String getFamily() { return family; }
        public List<Double> getTransform() { return transform; }
        public boolean isUnique() { return unique; }
    }

    private final List<Fiducial> fiducials;
    private final String type;

    public LLFieldMap() {
        this(new ArrayList<Fiducial>(), "ftc");
    }

    public LLFieldMap(List<Fiducial> fiducials, String type) {
        this.fiducials = fiducials;
        this.type = type;
    }

    public List<Fiducial> getFiducials() { return fiducials; }
    public String getType() { return type; }
    public int getNumberOfTags() { return fiducials.size(); }

    public boolean isValid() {
        for (Fiducial f : fiducials) if (f.getTransform().size() != 16) return false;
        return !fiducials.isEmpty();
    }
}
