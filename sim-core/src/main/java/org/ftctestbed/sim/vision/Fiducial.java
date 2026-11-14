package org.ftctestbed.sim.vision;

/**
 * An AprilTag on the field: its id, family, edge length (m, the black square) and its frame in
 * field coordinates as Limelight's target space has it: +x to the right as you look at the tag,
 * +y down the tag, and (right-handed) +z into it, away from you. So a camera sees it from its -z
 * side, and a tag squarely facing a camera has the camera's own axes. name: a group it's part of
 * (a BIOBUZZ CELL's cluster), or "".
 */
public record Fiducial(int id, String family, double size, Frame3 frame, String name) {
    /** Which way the printed face looks (toward a camera that can read it): -z. */
    public double[] facing() {
        double[] z = frame.axis(2);
        return new double[]{-z[0], -z[1], -z[2]};
    }

    /** The four corners in field coordinates: top-left, top-right, bottom-right, bottom-left as seen from the front. */
    public double[][] corners() {
        double h = size / 2;
        return new double[][]{frame.apply(new double[]{-h, -h, 0}), frame.apply(new double[]{h, -h, 0}),
                frame.apply(new double[]{h, h, 0}), frame.apply(new double[]{-h, h, 0})};
    }
}
