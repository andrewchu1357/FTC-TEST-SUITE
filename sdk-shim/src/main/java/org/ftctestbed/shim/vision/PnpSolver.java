package org.ftctestbed.shim.vision;

/**
 * The pose of flat points (a tag's corners, or a tag cluster's) from where they appear in a
 * picture, for a pinhole lens: what OpenCV's solvePnP and the AprilTag library's pose estimator do
 * for the SDK. A homography gives the first guess; Gauss-Newton steps then minimize the
 * reprojection error. Like the real solvers, it trusts the lens numbers it's given: wrong
 * intrinsics give wrong poses.
 */
public final class PnpSolver {
    private PnpSolver() {}

    /** R (row by row) turns the object's frame into the camera's; t is the object's origin in the camera's frame. */
    public static final class Pose {
        public final double[] r;
        public final double[] t;
        public final double rmsPixels;

        Pose(double[] r, double[] t, double rmsPixels) {
            this.r = r;
            this.t = t;
            this.rmsPixels = rmsPixels;
        }
    }

    /**
     * obj: N points {x, y, z} in the object's frame, all with the same z (N >= 4); img: their
     * pixels {u, v}. Returns null if the points don't pin down a pose.
     */
    public static Pose solve(double[][] obj, double[][] img, double fx, double fy, double cx, double cy) {
        int n = obj.length;
        if (n < 4 || img.length != n) return null;
        double z0 = obj[0][2];
        // Normalize the object points (centered, unit RMS radius) so the linear system is well conditioned.
        double mx = 0, my = 0;
        for (double[] p : obj) {
            mx += p[0] / n;
            my += p[1] / n;
        }
        double s = 0;
        for (double[] p : obj) s += ((p[0] - mx) * (p[0] - mx) + (p[1] - my) * (p[1] - my)) / n;
        s = Math.sqrt(s);
        if (s < 1e-12) return null;
        // Homography from normalized object (X, Y, 1) to normalized image (x, y, 1), h33 = 1.
        double[][] ata = new double[8][8];
        double[] atb = new double[8];
        for (int i = 0; i < n; i++) {
            double X = (obj[i][0] - mx) / s, Y = (obj[i][1] - my) / s;
            double x = (img[i][0] - cx) / fx, y = (img[i][1] - cy) / fy;
            double[] r1 = {X, Y, 1, 0, 0, 0, -x * X, -x * Y};
            double[] r2 = {0, 0, 0, X, Y, 1, -y * X, -y * Y};
            accumulate(ata, atb, r1, x);
            accumulate(ata, atb, r2, y);
        }
        double[] h = solveLinear(ata, atb);
        if (h == null) return null;
        // Undo the normalization: H = Hn * [[1/s, 0, -mx/s], [0, 1/s, -my/s], [0, 0, 1]].
        double[][] hn = {{h[0], h[1], h[2]}, {h[3], h[4], h[5]}, {h[6], h[7], 1}};
        double[][] H = new double[3][3];
        for (int i = 0; i < 3; i++) {
            H[i][0] = hn[i][0] / s;
            H[i][1] = hn[i][1] / s;
            H[i][2] = hn[i][2] - hn[i][0] * mx / s - hn[i][1] * my / s;
        }
        double[] c1 = {H[0][0], H[1][0], H[2][0]}, c2 = {H[0][1], H[1][1], H[2][1]}, c3 = {H[0][2], H[1][2], H[2][2]};
        double lambda = (norm(c1) + norm(c2)) / 2;
        if (lambda < 1e-12) return null;
        if (c3[2] < 0) lambda = -lambda; // the object is in front of the lens
        double[] a = scale(c1, 1 / lambda), b = scale(c2, 1 / lambda), tPrime = scale(c3, 1 / lambda);
        // Nearest rotation with these first two columns (symmetric Gram-Schmidt).
        double[] e1 = normalize(a), e2 = normalize(b);
        double[] bis = normalize(add(e1, e2)), perp = normalize(cross(bis, cross(e1, e2))); // in the plane, toward e1
        double k = Math.sqrt(0.5);
        double[] r1 = normalize(add(scale(bis, k), scale(perp, k)));
        double[] r2 = normalize(sub(scale(bis, k), scale(perp, k)));
        double[] r3 = cross(r1, r2);
        double[] R = {r1[0], r2[0], r3[0], r1[1], r2[1], r3[1], r1[2], r2[2], r3[2]};
        double[] t = sub(tPrime, scale(r3, z0)); // the plane is at z = z0 in the object's frame
        return refine(obj, img, fx, fy, cx, cy, R, t);
    }

    /** Gauss-Newton (lightly damped) on the reprojection error. */
    private static Pose refine(double[][] obj, double[][] img, double fx, double fy, double cx, double cy, double[] R, double[] t) {
        int n = obj.length;
        double lambda = 1e-6;
        double err = error(obj, img, fx, fy, cx, cy, R, t);
        for (int iter = 0; iter < 20; iter++) {
            double[][] jtj = new double[6][6];
            double[] jtr = new double[6];
            for (int i = 0; i < n; i++) {
                double[] ra = rotate(R, obj[i]);
                double X = ra[0] + t[0], Y = ra[1] + t[1], Z = ra[2] + t[2];
                if (Z <= 1e-9) return null;
                double ru = fx * X / Z + cx - img[i][0], rv = fy * Y / Z + cy - img[i][1];
                // d(u, v)/dP, and dP/d(omega, t) = [-[ra]x | I].
                double[] du = {fx / Z, 0, -fx * X / (Z * Z)}, dv = {0, fy / Z, -fy * Y / (Z * Z)};
                double[] ju = row(du, ra), jv = row(dv, ra);
                accumulate(jtj, jtr, ju, -ru);
                accumulate(jtj, jtr, jv, -rv);
            }
            for (int i = 0; i < 6; i++) jtj[i][i] *= 1 + lambda;
            double[] d = solveLinear(jtj, jtr);
            if (d == null) break;
            double[] Rn = mul(rodrigues(d[0], d[1], d[2]), R);
            double[] tn = {t[0] + d[3], t[1] + d[4], t[2] + d[5]};
            double e = error(obj, img, fx, fy, cx, cy, Rn, tn);
            if (e < err) {
                R = Rn;
                t = tn;
                boolean converged = err - e < 1e-12 * Math.max(1, err);
                err = e;
                lambda = Math.max(1e-9, lambda / 10);
                if (converged) break;
            } else {
                lambda *= 10;
                if (lambda > 1e6) break;
            }
        }
        return new Pose(R, t, Math.sqrt(err / n));
    }

    private static double[] row(double[] dp, double[] a) {
        // dP/domega = -[a]x : columns are -(e_k x a) = a x e_k.
        double[] r = new double[6];
        double[][] m = {{0, a[2], -a[1]}, {-a[2], 0, a[0]}, {a[1], -a[0], 0}};
        for (int k = 0; k < 3; k++) r[k] = dp[0] * m[0][k] + dp[1] * m[1][k] + dp[2] * m[2][k];
        r[3] = dp[0];
        r[4] = dp[1];
        r[5] = dp[2];
        return r;
    }

    private static double error(double[][] obj, double[][] img, double fx, double fy, double cx, double cy, double[] R, double[] t) {
        double e = 0;
        for (int i = 0; i < obj.length; i++) {
            double[] p = rotate(R, obj[i]);
            double Z = p[2] + t[2];
            if (Z <= 1e-9) return Double.MAX_VALUE;
            double du = fx * (p[0] + t[0]) / Z + cx - img[i][0], dv = fy * (p[1] + t[1]) / Z + cy - img[i][1];
            e += du * du + dv * dv;
        }
        return e;
    }

    /** Rotation matrix (row by row) for a rotation vector. */
    static double[] rodrigues(double wx, double wy, double wz) {
        double th = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (th < 1e-15) return new double[]{1, -wz, wy, wz, 1, -wx, -wy, wx, 1};
        double kx = wx / th, ky = wy / th, kz = wz / th, c = Math.cos(th), s = Math.sin(th), v = 1 - c;
        return new double[]{
                c + kx * kx * v, kx * ky * v - kz * s, kx * kz * v + ky * s,
                ky * kx * v + kz * s, c + ky * ky * v, ky * kz * v - kx * s,
                kz * kx * v - ky * s, kz * ky * v + kx * s, c + kz * kz * v};
    }

    static double[] mul(double[] a, double[] b) {
        double[] m = new double[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) m[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
        return m;
    }

    static double[] rotate(double[] R, double[] p) {
        return new double[]{R[0] * p[0] + R[1] * p[1] + R[2] * p[2], R[3] * p[0] + R[4] * p[1] + R[5] * p[2], R[6] * p[0] + R[7] * p[1] + R[8] * p[2]};
    }

    private static void accumulate(double[][] ata, double[] atb, double[] row, double rhs) {
        int m = row.length;
        for (int i = 0; i < m; i++) {
            atb[i] += row[i] * rhs;
            for (int j = 0; j < m; j++) ata[i][j] += row[i] * row[j];
        }
    }

    /** Gaussian elimination with partial pivoting; null if singular. */
    static double[] solveLinear(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for (int c = 0; c < n; c++) {
            int p = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(m[r][c]) > Math.abs(m[p][c])) p = r;
            if (Math.abs(m[p][c]) < 1e-14) return null;
            double[] tmp = m[c];
            m[c] = m[p];
            m[p] = tmp;
            for (int r = c + 1; r < n; r++) {
                double f = m[r][c] / m[c][c];
                for (int k = c; k <= n; k++) m[r][k] -= f * m[c][k];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double v = m[r][n];
            for (int k = r + 1; k < n; k++) v -= m[r][k] * x[k];
            x[r] = v / m[r][r];
        }
        return x;
    }

    private static double norm(double[] v) { return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); }
    private static double[] scale(double[] v, double k) { return new double[]{v[0] * k, v[1] * k, v[2] * k}; }
    private static double[] add(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    private static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    private static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    private static double[] normalize(double[] v) { double n = norm(v); return n < 1e-15 ? v : scale(v, 1 / n); }
}
