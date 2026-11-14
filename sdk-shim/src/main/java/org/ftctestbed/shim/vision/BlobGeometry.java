package org.ftctestbed.shim.vision;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The OpenCV steps the SDK's ColorBlobLocatorProcessor runs on a thresholded mask, for the
 * simulator: Gaussian blur, rectangular erode/dilate, findContours (outer borders, simplified like
 * CHAIN_APPROX_SIMPLE) and the contour measures (area, arc length, convex hull, minAreaRect,
 * minEnclosingCircle).
 */
public final class BlobGeometry {
    private BlobGeometry() {}

    // ------------------------------------------------------------------ filters

    /** OpenCV's GaussianBlur with a k x k kernel and sigma from k, on 0xRRGGBB pixels (edges reflected, as OpenCV does). */
    public static void gaussianBlur(int[] px, int w, int h, int k) {
        if (k <= 1 || w == 0 || h == 0) return;
        double sigma = 0.3 * ((k - 1) * 0.5 - 1) + 0.8;
        double[] kernel = new double[k];
        double sum = 0;
        for (int i = 0; i < k; i++) {
            double x = i - (k - 1) / 2.0;
            kernel[i] = Math.exp(-x * x / (2 * sigma * sigma));
            sum += kernel[i];
        }
        for (int i = 0; i < k; i++) kernel[i] /= sum;
        int half = k / 2;
        float[][] tmp = new float[3][w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double r = 0, g = 0, b = 0;
                for (int i = 0; i < k; i++) {
                    int c = px[y * w + reflect(x + i - half, w)];
                    r += kernel[i] * ((c >> 16) & 0xFF);
                    g += kernel[i] * ((c >> 8) & 0xFF);
                    b += kernel[i] * (c & 0xFF);
                }
                tmp[0][y * w + x] = (float) r;
                tmp[1][y * w + x] = (float) g;
                tmp[2][y * w + x] = (float) b;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double r = 0, g = 0, b = 0;
                for (int i = 0; i < k; i++) {
                    int j = reflect(y + i - half, h) * w + x;
                    r += kernel[i] * tmp[0][j];
                    g += kernel[i] * tmp[1][j];
                    b += kernel[i] * tmp[2][j];
                }
                px[y * w + x] = (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
    }

    /** BORDER_REFLECT_101, OpenCV's default. */
    private static int reflect(int i, int n) {
        if (n == 1) return 0;
        while (i < 0 || i >= n) i = i < 0 ? -i : 2 * n - 2 - i;
        return i;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    /** OpenCV erode with a k x k rectangle (anchor at k/2); outside the picture doesn't erode. */
    public static void erode(boolean[] m, int w, int h, int k) {
        morph(m, w, h, k, true);
    }

    /** OpenCV dilate with a k x k rectangle (anchor at k/2); outside the picture doesn't dilate. */
    public static void dilate(boolean[] m, int w, int h, int k) {
        morph(m, w, h, k, false);
    }

    private static void morph(boolean[] m, int w, int h, int k, boolean erode) {
        if (k <= 1) return;
        int a = k / 2;
        boolean[] tmp = new boolean[m.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean v = erode;
                for (int i = 0; i < k; i++) {
                    int xx = x + i - a;
                    if (xx < 0 || xx >= w) continue;
                    boolean s = m[y * w + xx];
                    if (erode ? !s : s) {
                        v = !erode;
                        break;
                    }
                }
                tmp[y * w + x] = v;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean v = erode;
                for (int i = 0; i < k; i++) {
                    int yy = y + i - a;
                    if (yy < 0 || yy >= h) continue;
                    boolean s = tmp[yy * w + x];
                    if (erode ? !s : s) {
                        v = !erode;
                        break;
                    }
                }
                m[y * w + x] = v;
            }
        }
    }

    // ------------------------------------------------------------------ contours

    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DY = {0, 1, 1, 1, 0, -1, -1, -1};

    /**
     * The border of each 8-connected group of set pixels, as pixel coordinates {x, y} with straight
     * runs reduced to their ends (CHAIN_APPROX_SIMPLE). externalOnly (RETR_EXTERNAL) leaves out
     * groups inside another's hole; otherwise (RETR_LIST) they're included, with each hole's
     * border (traced around the hole's own pixels: within a pixel of OpenCV's).
     */
    public static List<int[][]> contours(boolean[] m, int w, int h, boolean externalOnly) {
        List<int[][]> out = new ArrayList<int[][]>();
        if (w == 0 || h == 0) return out;
        int[] label = new int[w * h];
        // Background reachable from the picture's edge (4-connected): everything else unset is a hole.
        boolean[] outside = new boolean[w * h];
        int[] stack = new int[w * h];
        int sp = 0;
        for (int x = 0; x < w; x++) {
            sp = seed(m, outside, stack, sp, x, 0, w);
            sp = seed(m, outside, stack, sp, x, h - 1, w);
        }
        for (int y = 0; y < h; y++) {
            sp = seed(m, outside, stack, sp, 0, y, w);
            sp = seed(m, outside, stack, sp, w - 1, y, w);
        }
        while (sp > 0) {
            int i = stack[--sp], x = i % w, y = i / w;
            if (x > 0) sp = seed(m, outside, stack, sp, x - 1, y, w);
            if (x < w - 1) sp = seed(m, outside, stack, sp, x + 1, y, w);
            if (y > 0) sp = seed(m, outside, stack, sp, x, y - 1, w);
            if (y < h - 1) sp = seed(m, outside, stack, sp, x, y + 1, w);
        }
        int next = 1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (!m[i] || label[i] != 0) continue;
                fill(m, label, stack, w, h, x, y, next++, true);
                boolean nested = x > 0 && !outside[i - 1];
                if (externalOnly && nested) continue;
                out.add(simplify(trace(m, w, h, x, y)));
            }
        }
        if (!externalOnly) {
            // Holes: unset pixels not reachable from the edge, 4-connected; their own outer border.
            boolean[] hole = new boolean[w * h];
            for (int i = 0; i < w * h; i++) hole[i] = !m[i] && !outside[i];
            int[] holeLabel = new int[w * h];
            int hl = 1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    if (!hole[i] || holeLabel[i] != 0) continue;
                    fill(hole, holeLabel, stack, w, h, x, y, hl++, false);
                    out.add(simplify(trace(hole, w, h, x, y)));
                }
            }
        }
        return out;
    }

    private static int seed(boolean[] m, boolean[] outside, int[] stack, int sp, int x, int y, int w) {
        int i = y * w + x;
        if (!m[i] && !outside[i]) {
            outside[i] = true;
            stack[sp++] = i;
        }
        return sp;
    }

    private static void fill(boolean[] m, int[] label, int[] stack, int w, int h, int x0, int y0, int id, boolean eight) {
        int sp = 0;
        stack[sp++] = y0 * w + x0;
        label[y0 * w + x0] = id;
        while (sp > 0) {
            int i = stack[--sp], x = i % w, y = i / w;
            for (int d = 0; d < 8; d++) {
                if (!eight && (d & 1) == 1) continue;
                int nx = x + DX[d], ny = y + DY[d];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int j = ny * w + nx;
                if (m[j] && label[j] == 0) {
                    label[j] = id;
                    stack[sp++] = j;
                }
            }
        }
    }

    private static boolean set(boolean[] m, int w, int h, int x, int y) {
        return x >= 0 && y >= 0 && x < w && y < h && m[y * w + x];
    }

    /** Moore-neighbor tracing from the group's first pixel in raster order (its west neighbor is unset). */
    private static int[][] trace(boolean[] m, int w, int h, int sx, int sy) {
        List<int[]> pts = new ArrayList<int[]>();
        pts.add(new int[]{sx, sy});
        int px = sx, py = sy, back = 4; // came from the west
        int firstX = -1, firstY = -1;
        int guard = 4 * w * h + 16;
        while (guard-- > 0) {
            int found = -1;
            for (int i = 1; i <= 8; i++) {
                int d = (back + i) & 7;
                if (set(m, w, h, px + DX[d], py + DY[d])) {
                    found = d;
                    break;
                }
            }
            if (found < 0) break; // a lone pixel
            int qx = px + DX[found], qy = py + DY[found];
            if (px == sx && py == sy) {
                if (firstX < 0) {
                    firstX = qx;
                    firstY = qy;
                } else if (qx == firstX && qy == firstY) {
                    break;
                }
            }
            // The last unset neighbor examined, seen from the new pixel.
            int cd = (found + 7) & 7;
            int cx = px + DX[cd], cy = py + DY[cd];
            back = direction(cx - qx, cy - qy);
            px = qx;
            py = qy;
            if (px == sx && py == sy) continue;
            pts.add(new int[]{px, py});
        }
        return pts.toArray(new int[0][]);
    }

    private static int direction(int dx, int dy) {
        for (int d = 0; d < 8; d++) if (DX[d] == dx && DY[d] == dy) return d;
        return 4;
    }

    /** Keep only the points where the border changes direction. */
    private static int[][] simplify(int[][] p) {
        int n = p.length;
        if (n <= 2) return p;
        List<int[]> keep = new ArrayList<int[]>();
        for (int i = 0; i < n; i++) {
            int[] a = p[(i + n - 1) % n], b = p[i], c = p[(i + 1) % n];
            int din = direction(Integer.signum(b[0] - a[0]), Integer.signum(b[1] - a[1]));
            int dout = direction(Integer.signum(c[0] - b[0]), Integer.signum(c[1] - b[1]));
            if (din != dout || (a[0] == c[0] && a[1] == c[1])) keep.add(b);
        }
        if (keep.isEmpty()) keep.add(p[0]);
        return keep.toArray(new int[0][]);
    }

    // ------------------------------------------------------------------ measures

    /** OpenCV contourArea: the polygon's area. */
    public static double area(double[][] p) {
        double a = 0;
        for (int i = 0, n = p.length; i < n; i++) {
            double[] u = p[i], v = p[(i + 1) % n];
            a += u[0] * v[1] - v[0] * u[1];
        }
        return Math.abs(a) / 2;
    }

    /** OpenCV arcLength(closed = true). */
    public static double arcLength(double[][] p) {
        double l = 0;
        for (int i = 0, n = p.length; i < n && n > 1; i++) {
            double[] u = p[i], v = p[(i + 1) % n];
            l += Math.hypot(v[0] - u[0], v[1] - u[1]);
        }
        return l;
    }

    /** Convex hull (Andrew's monotone chain), counter-clockwise in a y-up sense. */
    public static double[][] convexHull(double[][] pts) {
        double[][] p = pts.clone();
        Arrays.sort(p, (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        int n = p.length;
        if (n < 3) return p;
        double[][] hull = new double[2 * n][];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], p[i]) <= 0) k--;
            hull[k++] = p[i];
        }
        for (int i = n - 2, t = k + 1; i >= 0; i--) {
            while (k >= t && cross(hull[k - 2], hull[k - 1], p[i]) <= 0) k--;
            hull[k++] = p[i];
        }
        return Arrays.copyOf(hull, Math.max(1, k - 1));
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /**
     * OpenCV minAreaRect: {centerX, centerY, width, height, angleDegrees}. Width runs along the
     * angle (degrees, in [0, 90)); the box is the smallest that holds the points.
     */
    public static double[] minAreaRect(double[][] pts) {
        double[][] hull = convexHull(pts);
        int n = hull.length;
        if (n == 0) return new double[5];
        if (n == 1) return new double[]{hull[0][0], hull[0][1], 0, 0, 0};
        double best = Double.MAX_VALUE;
        double[] out = null;
        for (int i = 0; i < n; i++) {
            double[] a = hull[i], b = hull[(i + 1) % n];
            double ex = b[0] - a[0], ey = b[1] - a[1], len = Math.hypot(ex, ey);
            if (len < 1e-12) continue;
            ex /= len;
            ey /= len;
            double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
            for (double[] p : hull) {
                double u = p[0] * ex + p[1] * ey, v = -p[0] * ey + p[1] * ex;
                minU = Math.min(minU, u);
                maxU = Math.max(maxU, u);
                minV = Math.min(minV, v);
                maxV = Math.max(maxV, v);
            }
            double area = (maxU - minU) * (maxV - minV);
            if (area < best - 1e-9) {
                best = area;
                double cu = (minU + maxU) / 2, cv = (minV + maxV) / 2;
                double width = maxU - minU, height = maxV - minV;
                // Normalize the angle to [0, 90): a quarter turn swaps the sides.
                double deg = Math.toDegrees(Math.atan2(ey, ex));
                long quarters = (long) Math.floor(deg / 90);
                double angle = deg - 90 * quarters;
                if ((quarters & 1) != 0) {
                    double t = width;
                    width = height;
                    height = t;
                }
                out = new double[]{cu * ex - cv * ey, cu * ey + cv * ex, width, height, angle};
            }
        }
        return out != null ? out : new double[]{hull[0][0], hull[0][1], 0, 0, 0};
    }

    /** OpenCV minEnclosingCircle: {centerX, centerY, radius}. */
    public static double[] minEnclosingCircle(double[][] pts) {
        double[][] p = convexHull(pts);
        int n = p.length;
        if (n == 0) return new double[3];
        double[] c = {p[0][0], p[0][1], 0};
        for (int i = 1; i < n; i++) {
            if (inside(c, p[i])) continue;
            c = new double[]{p[i][0], p[i][1], 0};
            for (int j = 0; j < i; j++) {
                if (inside(c, p[j])) continue;
                c = circle2(p[i], p[j]);
                for (int k = 0; k < j; k++) {
                    if (inside(c, p[k])) continue;
                    c = circle3(p[i], p[j], p[k]);
                }
            }
        }
        return c;
    }

    private static boolean inside(double[] c, double[] p) {
        return Math.hypot(p[0] - c[0], p[1] - c[1]) <= c[2] + 1e-7;
    }

    private static double[] circle2(double[] a, double[] b) {
        return new double[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, Math.hypot(a[0] - b[0], a[1] - b[1]) / 2};
    }

    private static double[] circle3(double[] a, double[] b, double[] c) {
        double bx = b[0] - a[0], by = b[1] - a[1], cx = c[0] - a[0], cy = c[1] - a[1];
        double d = 2 * (bx * cy - by * cx);
        if (Math.abs(d) < 1e-12) {
            double[] ab = circle2(a, b), ac = circle2(a, c), bc = circle2(b, c);
            double[] best = ab[2] > ac[2] ? ab : ac;
            return best[2] > bc[2] ? best : bc;
        }
        double ux = (cy * (bx * bx + by * by) - by * (cx * cx + cy * cy)) / d;
        double uy = (bx * (cx * cx + cy * cy) - cx * (bx * bx + by * by)) / d;
        return new double[]{a[0] + ux, a[1] + uy, Math.hypot(ux, uy)};
    }
}
