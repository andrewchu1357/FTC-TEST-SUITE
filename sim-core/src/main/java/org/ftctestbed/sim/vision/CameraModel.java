package org.ftctestbed.sim.vision;

/**
 * A pinhole camera: a picture width x height pixels covering hfov x vfov (radians), principal
 * point in the middle. Camera frame as Limelight and OpenCV use it: +x right, +y down, +z out of
 * the lens.
 */
public record CameraModel(int width, int height, double hfov, double vfov) {
    /** The Limelight 3A at 640 x 480: 54.5 x 42 degrees (Limelight's spec sheet). */
    public static final CameraModel LIMELIGHT_3A = new CameraModel(640, 480, Math.toRadians(54.5), Math.toRadians(42));

    public double fx() { return width / 2.0 / Math.tan(hfov / 2); }
    public double fy() { return height / 2.0 / Math.tan(vfov / 2); }
    public double cx() { return width / 2.0; }
    public double cy() { return height / 2.0; }

    /** Pixel {u, v} of a point in camera coordinates, or null if it's behind the lens. */
    public double[] project(double[] p) {
        if (p[2] <= 1e-4) return null;
        return new double[]{cx() + fx() * p[0] / p[2], cy() + fy() * p[1] / p[2]};
    }

    public boolean inside(double[] px) {
        return px != null && px[0] >= 0 && px[0] <= width && px[1] >= 0 && px[1] <= height;
    }

    /** Degrees right of the picture's center for pixel column u (Limelight's tx). */
    public double txDegrees(double u) {
        return Math.toDegrees(Math.atan((u - cx()) / fx()));
    }

    /** Degrees above the picture's center for pixel row v (Limelight's ty). */
    public double tyDegrees(double v) {
        return Math.toDegrees(Math.atan((cy() - v) / fy()));
    }

    /** The 3x3 camera matrix, row by row (fx 0 cx / 0 fy cy / 0 0 1). */
    public double[] matrix() {
        return new double[]{fx(), 0, cx(), 0, fy(), cy(), 0, 0, 1};
    }
}
