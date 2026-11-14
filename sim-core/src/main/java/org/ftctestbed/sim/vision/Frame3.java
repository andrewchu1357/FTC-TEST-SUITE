package org.ftctestbed.sim.vision;

/**
 * A rigid transform: a rotation (its columns are the frame's x, y, z axes in the parent frame)
 * and an origin in the parent frame. apply() takes a point in this frame to the parent's.
 */
public record Frame3(double[][] r, double[] t) {
    public static final Frame3 IDENTITY = new Frame3(new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}, new double[]{0, 0, 0});

    /** A frame from its axes (in the parent frame) and its origin. */
    public static Frame3 ofAxes(double[] x, double[] y, double[] z, double[] origin) {
        return new Frame3(new double[][]{{x[0], y[0], z[0]}, {x[1], y[1], z[1]}, {x[2], y[2], z[2]}}, origin.clone());
    }

    /** A frame turned by yaw (counter-clockwise, about +z) at (x, y, z). */
    public static Frame3 planar(double x, double y, double z, double yaw) {
        double c = Math.cos(yaw), s = Math.sin(yaw);
        return new Frame3(new double[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}}, new double[]{x, y, z});
    }

    public double[] axis(int i) {
        return new double[]{r[0][i], r[1][i], r[2][i]};
    }

    public double[] origin() {
        return t.clone();
    }

    public double[] apply(double[] p) {
        return new double[]{
                r[0][0] * p[0] + r[0][1] * p[1] + r[0][2] * p[2] + t[0],
                r[1][0] * p[0] + r[1][1] * p[1] + r[1][2] * p[2] + t[1],
                r[2][0] * p[0] + r[2][1] * p[1] + r[2][2] * p[2] + t[2]};
    }

    public double[] rotate(double[] v) {
        return new double[]{
                r[0][0] * v[0] + r[0][1] * v[1] + r[0][2] * v[2],
                r[1][0] * v[0] + r[1][1] * v[1] + r[1][2] * v[2],
                r[2][0] * v[0] + r[2][1] * v[1] + r[2][2] * v[2]};
    }

    /** A point in the parent frame, in this frame. */
    public double[] toLocal(double[] p) {
        double dx = p[0] - t[0], dy = p[1] - t[1], dz = p[2] - t[2];
        return new double[]{
                r[0][0] * dx + r[1][0] * dy + r[2][0] * dz,
                r[0][1] * dx + r[1][1] * dy + r[2][1] * dz,
                r[0][2] * dx + r[1][2] * dy + r[2][2] * dz};
    }

    /** This frame then the child: child's points to this frame's parent. */
    public Frame3 compose(Frame3 child) {
        double[][] m = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                m[i][j] = r[i][0] * child.r[0][j] + r[i][1] * child.r[1][j] + r[i][2] * child.r[2][j];
            }
        }
        return new Frame3(m, apply(child.t));
    }

    public Frame3 inverse() {
        double[][] m = {{r[0][0], r[1][0], r[2][0]}, {r[0][1], r[1][1], r[2][1]}, {r[0][2], r[1][2], r[2][2]}};
        double[] o = {-(m[0][0] * t[0] + m[0][1] * t[1] + m[0][2] * t[2]),
                -(m[1][0] * t[0] + m[1][1] * t[1] + m[1][2] * t[2]),
                -(m[2][0] * t[0] + m[2][1] * t[1] + m[2][2] * t[2])};
        return new Frame3(m, o);
    }

    /** Small rotation about each axis (rad) and a shift (m), for measurement noise. */
    public Frame3 perturbed(double rx, double ry, double rz, double dx, double dy, double dz) {
        double[][] e = rotationXyz(rx, ry, rz);
        double[][] m = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) m[i][j] = r[i][0] * e[0][j] + r[i][1] * e[1][j] + r[i][2] * e[2][j];
        }
        return new Frame3(m, new double[]{t[0] + dx, t[1] + dy, t[2] + dz});
    }

    static double[][] rotationXyz(double rx, double ry, double rz) {
        double cx = Math.cos(rx), sx = Math.sin(rx), cy = Math.cos(ry), sy = Math.sin(ry), cz = Math.cos(rz), sz = Math.sin(rz);
        double[][] x = {{1, 0, 0}, {0, cx, -sx}, {0, sx, cx}};
        double[][] y = {{cy, 0, sy}, {0, 1, 0}, {-sy, 0, cy}};
        double[][] z = {{cz, -sz, 0}, {sz, cz, 0}, {0, 0, 1}};
        return mul(mul(z, y), x);
    }

    static double[][] mul(double[][] a, double[][] b) {
        double[][] m = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) m[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
        return m;
    }

    /**
     * Roll, pitch, yaw (rad) of this rotation as z-y-x Euler angles: yaw about z, then pitch about
     * the new y, then roll about the new x (the usual vehicle convention; for a camera-style frame
     * the caller picks which physical axis each is).
     */
    public double[] rollPitchYaw() {
        double pitch = Math.asin(Math.max(-1, Math.min(1, -r[2][0])));
        double roll = Math.atan2(r[2][1], r[2][2]);
        double yaw = Math.atan2(r[1][0], r[0][0]);
        return new double[]{roll, pitch, yaw};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static double norm(double[] a) {
        return Math.sqrt(dot(a, a));
    }
}
