package org.ftctestbed.sim.vision;

import java.util.List;

/** What a camera on one robot can see: the game pieces, the AprilTags, and what's in the way. */
public interface VisionScene {
    /** A game piece: center (m), radius (m), color 0xRRGGBB, kind id ("pollen", "nectar-red"...), alliance or null. */
    record Ball(double x, double y, double z, double radius, int color, String kind, String alliance) {}

    /**
     * Something opaque: a box with this footprint (center, half-sizes, heading) between two
     * heights, and its color in a webcam's picture (0xRRGGBB).
     */
    record Box(double x, double y, double halfLength, double halfWidth, double heading, double bottom, double top, int color) {
        /** A gray steel part. */
        public Box(double x, double y, double halfLength, double halfWidth, double heading, double bottom, double top) {
            this(x, y, halfLength, halfWidth, heading, bottom, top, 0x5A5D63);
        }

        /** The eight corners: bottom four then top four. */
        public double[][] corners() {
            double c = Math.cos(heading), s = Math.sin(heading);
            double[][] out = new double[8][];
            int i = 0;
            for (double z : new double[]{bottom, top}) {
                for (double[] k : new double[][]{{1, 1}, {1, -1}, {-1, -1}, {-1, 1}}) {
                    double lx = k[0] * halfLength, ly = k[1] * halfWidth;
                    out[i++] = new double[]{x + c * lx - s * ly, y + s * lx + c * ly, z};
                }
            }
            return out;
        }

        /** True if the segment from a to b passes through the box. */
        public boolean blocks(double[] a, double[] b) {
            double c = Math.cos(heading), s = Math.sin(heading);
            double[] la = {c * (a[0] - x) + s * (a[1] - y), -s * (a[0] - x) + c * (a[1] - y), a[2]};
            double[] lb = {c * (b[0] - x) + s * (b[1] - y), -s * (b[0] - x) + c * (b[1] - y), b[2]};
            double[] lo = {-halfLength, -halfWidth, bottom}, hi = {halfLength, halfWidth, top};
            double t0 = 0, t1 = 1;
            for (int i = 0; i < 3; i++) {
                double d = lb[i] - la[i];
                if (Math.abs(d) < 1e-12) {
                    if (la[i] < lo[i] || la[i] > hi[i]) return false;
                    continue;
                }
                double u = (lo[i] - la[i]) / d, v = (hi[i] - la[i]) / d;
                if (u > v) { double w = u; u = v; v = w; }
                t0 = Math.max(t0, u);
                t1 = Math.min(t1, v);
                if (t0 > t1) return false;
            }
            return t1 > 1e-6 && t0 < 1 - 1e-6;
        }
    }

    List<Ball> balls();

    List<Fiducial> fiducials();

    /** Where the field map says the tags are (what a Limelight's robot pose is worked out from). */
    List<Fiducial> fieldMap();

    List<Box> occluders();
}
