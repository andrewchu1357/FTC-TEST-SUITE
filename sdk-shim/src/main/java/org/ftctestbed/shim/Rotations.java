package org.ftctestbed.shim;

import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;

/**
 * 3x3 rotation-matrix helpers shared by the IMU orientation classes and the simulated IMU.
 * Matrices are row-major double[3][3]; axes 0/1/2 = X/Y/Z.
 */
public final class Rotations {
    private Rotations() {}

    public static double[][] identity() {
        return new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    }

    public static double[][] axisRotation(int axis, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        switch (axis) {
            case 0: return new double[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}};
            case 1: return new double[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
            default: return new double[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
        }
    }

    public static double[][] multiply(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
        return r;
    }

    public static double[] multiply(double[][] m, double[] v) {
        return new double[]{
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    public static double[][] transpose(double[][] m) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[i][j] = m[j][i];
        return r;
    }

    /** Builds a rotation from three successive axis rotations in the given convention. */
    public static double[][] fromEuler(AxesReference reference, AxesOrder order, double first, double second, double third) {
        int[] ax = order.indices();
        double[][] a = axisRotation(ax[0], first), b = axisRotation(ax[1], second), c = axisRotation(ax[2], third);
        return reference == AxesReference.INTRINSIC ? multiply(multiply(a, b), c) : multiply(multiply(c, b), a);
    }

    /** Inverse of {@link #fromEuler}. Returns {first, second, third} in radians. */
    public static double[] toEuler(double[][] m, AxesReference reference, AxesOrder order) {
        if (reference == AxesReference.EXTRINSIC) {
            double[] r = toEulerIntrinsic(m, order.reverse().indices());
            return new double[]{r[2], r[1], r[0]};
        }
        return toEulerIntrinsic(m, order.indices());
    }

    private static double[] toEulerIntrinsic(double[][] m, int[] ax) {
        int i = ax[0], j = ax[1];
        if (ax[2] != i) {
            // Tait-Bryan: R = Ri(a) Rj(b) Rk(c)
            int k = ax[2];
            double s = isCyclic(i, j, k) ? 1 : -1;
            double b = Math.asin(clamp(s * m[i][k]));
            double a = Math.atan2(-s * m[j][k], m[k][k]);
            double c = Math.atan2(-s * m[i][j], m[i][i]);
            return new double[]{a, b, c};
        }
        // Proper Euler: R = Ri(a) Rj(b) Ri(c)
        int k = 3 - i - j;
        double s = isCyclic(i, j, k) ? 1 : -1;
        double b = Math.acos(clamp(m[i][i]));
        double a = Math.atan2(m[j][i], -s * m[k][i]);
        double c = Math.atan2(m[i][j], s * m[i][k]);
        return new double[]{a, b, c};
    }

    private static boolean isCyclic(int i, int j, int k) {
        return (i == 0 && j == 1 && k == 2) || (i == 1 && j == 2 && k == 0) || (i == 2 && j == 0 && k == 1);
    }

    private static double clamp(double v) {
        return Math.max(-1, Math.min(1, v));
    }

    public static Quaternion toQuaternion(double[][] m, long acquisitionTime) {
        double w, x, y, z;
        double tr = m[0][0] + m[1][1] + m[2][2];
        if (tr > 0) {
            double s = Math.sqrt(tr + 1.0) * 2;
            w = 0.25 * s;
            x = (m[2][1] - m[1][2]) / s;
            y = (m[0][2] - m[2][0]) / s;
            z = (m[1][0] - m[0][1]) / s;
        } else if (m[0][0] > m[1][1] && m[0][0] > m[2][2]) {
            double s = Math.sqrt(1.0 + m[0][0] - m[1][1] - m[2][2]) * 2;
            w = (m[2][1] - m[1][2]) / s;
            x = 0.25 * s;
            y = (m[0][1] + m[1][0]) / s;
            z = (m[0][2] + m[2][0]) / s;
        } else if (m[1][1] > m[2][2]) {
            double s = Math.sqrt(1.0 + m[1][1] - m[0][0] - m[2][2]) * 2;
            w = (m[0][2] - m[2][0]) / s;
            x = (m[0][1] + m[1][0]) / s;
            y = 0.25 * s;
            z = (m[1][2] + m[2][1]) / s;
        } else {
            double s = Math.sqrt(1.0 + m[2][2] - m[0][0] - m[1][1]) * 2;
            w = (m[1][0] - m[0][1]) / s;
            x = (m[0][2] + m[2][0]) / s;
            y = (m[1][2] + m[2][1]) / s;
            z = 0.25 * s;
        }
        return new Quaternion((float) w, (float) x, (float) y, (float) z, acquisitionTime);
    }

    public static double[][] fromQuaternion(Quaternion q) {
        double n = Math.sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z);
        double w = q.w / n, x = q.x / n, y = q.y / n, z = q.z / n;
        return new double[][]{
                {1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)},
                {2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)},
                {2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)}};
    }
}
