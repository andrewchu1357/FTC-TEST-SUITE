package org.ftctestbed.shim.vision;

import java.util.Collections;
import java.util.List;

/**
 * One picture a simulated webcam took, as geometry: the simulator works out where the AprilTags'
 * corners and the game pieces land in the picture; the SDK's vision processors (AprilTag, color
 * blobs, predominant color) read it the way the real ones read pixels. render() paints the pixels
 * the color processors threshold.
 */
public final class SimFrame {
    /** A tag the camera sees whole and unblocked (decoding it is up to the processor). */
    public static final class Tag {
        public final int id;
        /** "36h11", "25h9", "16h5" or "standard41h12". */
        public final String family;
        /** Pixel corners in the AprilTag library's order: the tag's bottom-left, bottom-right, top-right, top-left. */
        public final double[][] corners;
        /** Motion blur across the picture during the exposure (px). */
        public final double blur;

        public Tag(int id, String family, double[][] corners, double blur) {
            this.id = id;
            this.family = family;
            this.corners = corners;
            this.blur = blur;
        }
    }

    /** Something to paint: a ball (center u, v and radius r, px) or a convex polygon (xs, ys). */
    public static final class Shape {
        public final int rgb;
        public final double u, v, r;
        public final double[] xs, ys;

        private Shape(int rgb, double u, double v, double r, double[] xs, double[] ys) {
            this.rgb = rgb;
            this.u = u;
            this.v = v;
            this.r = r;
            this.xs = xs;
            this.ys = ys;
        }

        /** A ball: lit from above, darker toward its bottom. */
        public static Shape disc(double u, double v, double r, int rgb) {
            return new Shape(rgb, u, v, r, null, null);
        }

        public static Shape polygon(double[] xs, double[] ys, int rgb) {
            return new Shape(rgb, 0, 0, 0, xs, ys);
        }

        public boolean isDisc() {
            return xs == null;
        }
    }

    /** What's behind everything else at a pixel (floor, field walls, the venue). */
    public interface Background {
        int rgb(double u, double v);
    }

    public final int width, height;
    /** SimHooks.nanoTime() when the shutter opened (AprilTagDetection.frameAcquisitionNanoTime). */
    public final long captureNanos;
    /** The real lens at this resolution (px). */
    public final double fx, fy, cx, cy;
    /** Exposure times gain, relative to a well-exposed picture (1). */
    public final double brightness;
    /** Sensor noise, standard deviation in 0-255 units. */
    public final double noise;
    public final List<Tag> tags;
    /** Far to near: later ones cover earlier ones. */
    public final List<Shape> shapes;
    public final Background background;

    public SimFrame(int width, int height, long captureNanos, double fx, double fy, double cx, double cy,
                    double brightness, double noise, List<Tag> tags, List<Shape> shapes, Background background) {
        this.width = width;
        this.height = height;
        this.captureNanos = captureNanos;
        this.fx = fx;
        this.fy = fy;
        this.cx = cx;
        this.cy = cy;
        this.brightness = brightness;
        this.noise = noise;
        this.tags = tags != null ? tags : Collections.<Tag>emptyList();
        this.shapes = shapes != null ? shapes : Collections.<Shape>emptyList();
        this.background = background;
    }

    /** The picture's pixels in a rectangle, 0xRRGGBB, row by row. */
    public int[] render(int x0, int y0, int w, int h) {
        int[] px = new int[Math.max(0, w * h)];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                px[y * w + x] = background != null ? background.rgb(x0 + x + 0.5, y0 + y + 0.5) : 0x404040;
            }
        }
        for (Shape s : shapes) {
            if (s.isDisc()) paintDisc(px, x0, y0, w, h, s);
            else paintPolygon(px, x0, y0, w, h, s);
        }
        if (brightness != 1 || noise > 0) {
            long seed = captureNanos * 0x9E3779B97F4A7C15L;
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                int r = expose((c >> 16) & 0xFF, seed, i * 3L);
                int g = expose((c >> 8) & 0xFF, seed, i * 3L + 1);
                int b = expose(c & 0xFF, seed, i * 3L + 2);
                px[i] = (r << 16) | (g << 8) | b;
            }
        }
        return px;
    }

    private int expose(int c, long seed, long i) {
        double v = c * brightness;
        if (noise > 0) {
            long z = (seed + i) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 31)) * 0x94D049BB133111EBL;
            z ^= z >>> 29;
            double u = ((z >>> 11) * 0x1.0p-53) - 0.5; // uniform, -0.5..0.5
            v += u * noise * 3.4641; // standard deviation `noise`
        }
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    private static void paintDisc(int[] px, int x0, int y0, int w, int h, Shape s) {
        int top = (int) Math.max(y0, Math.floor(s.v - s.r)), bottom = (int) Math.min(y0 + h - 1, Math.ceil(s.v + s.r));
        int cr = (s.rgb >> 16) & 0xFF, cg = (s.rgb >> 8) & 0xFF, cb = s.rgb & 0xFF;
        for (int y = top; y <= bottom; y++) {
            double dy = y + 0.5 - s.v;
            if (Math.abs(dy) > s.r) continue;
            double half = Math.sqrt(s.r * s.r - dy * dy);
            int from = (int) Math.max(x0, Math.ceil(s.u - half - 0.5)), to = (int) Math.min(x0 + w - 1, Math.floor(s.u + half - 0.5));
            if (from > to) continue;
            double t = (dy + s.r) / (2 * s.r);
            double shade = 1.0 - 0.4 * t * Math.sqrt(t);
            int c = ((int) (cr * shade) << 16) | ((int) (cg * shade) << 8) | (int) (cb * shade);
            int row = (y - y0) * w - x0;
            for (int x = from; x <= to; x++) px[row + x] = c;
        }
    }

    private static void paintPolygon(int[] px, int x0, int y0, int w, int h, Shape s) {
        int n = s.xs.length;
        if (n < 3) return;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double y : s.ys) {
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        int top = (int) Math.max(y0, Math.floor(minY)), bottom = (int) Math.min(y0 + h - 1, Math.ceil(maxY));
        for (int y = top; y <= bottom; y++) {
            double py = y + 0.5, left = Double.MAX_VALUE, right = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double ax = s.xs[i], ay = s.ys[i], bx = s.xs[(i + 1) % n], by = s.ys[(i + 1) % n];
                if ((ay <= py && by > py) || (by <= py && ay > py)) {
                    double x = ax + (py - ay) / (by - ay) * (bx - ax);
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
            if (left > right) continue;
            int from = (int) Math.max(x0, Math.ceil(left - 0.5)), to = (int) Math.min(x0 + w - 1, Math.floor(right - 0.5));
            int row = (y - y0) * w - x0;
            for (int x = from; x <= to; x++) px[row + x] = s.rgb;
        }
    }
}
