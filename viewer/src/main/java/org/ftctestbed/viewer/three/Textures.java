package org.ftctestbed.viewer.three;

import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * Pictures drawn in code for the 3D view: the foam tiles, the carpet, the drape and LED board
 * of the stands round the field, and the soft shadows. JavaFX has no shadows of its own, so things on the floor get a dark,
 * soft-edged patch under them; the ones that never move are drawn into the tiles.
 */
final class Textures {
    static final double IN = 0.0254;
    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();

    private Textures() {}

    /**
     * The field's foam tiles, seen from above (top of the picture = +y, away from the audience):
     * gray foam, slightly different from tile to tile, with the interlocking seams between tiles,
     * darker where the floor meets the walls and around whatever stands on it.
     * contacts: {x0, y0, x1, y1, darkness, reach (m)}, footprints of things standing on the tiles.
     * pools: {x, y, radius x, radius y, darkness}, broad shade under something high up.
     */
    static Image tiles(double size, int tilesAcross, List<double[]> contacts, List<double[]> pools) {
        StringBuilder key = new StringBuilder("tiles ").append(size).append(' ').append(tilesAcross);
        for (double[] c : contacts) key.append(java.util.Arrays.toString(c));
        for (double[] p : pools) key.append(java.util.Arrays.toString(p));
        return CACHE.computeIfAbsent(key.toString(), k -> drawTiles(size, tilesAcross, contacts, pools));
    }

    private static Image drawTiles(double size, int tilesAcross, List<double[]> contacts, List<double[]> pools) {
        int n = 2048;
        double half = size / 2, t = size / tilesAcross, px = size / n;
        int[] argb = new int[n * n];
        IntStream.range(0, n).parallel().forEach(j -> {
            double y = half - (j + 0.5) * px;
            for (int i = 0; i < n; i++) {
                double x = -half + (i + 0.5) * px;
                int tx = (int) Math.min(tilesAcross - 1, Math.floor((x + half) / t));
                int ty = (int) Math.min(tilesAcross - 1, Math.floor((y + half) / t));
                // Foam: a little different per tile, faint blotches, and a fine pebbled grain.
                double shade = 1 + 0.035 * (hash(tx, ty, 7) - 0.5)
                        + 0.05 * (fbm(x / (9 * IN), y / (9 * IN), 3, 0, 11) - 0.5)
                        + 0.045 * (noise(x / (0.22 * IN), y / (0.22 * IN), 0, 3) - 0.5);
                // Seams between tiles (not at the field's edge, where the tiles meet the wall).
                double seam = Math.min(seamDistance(x + half, y + half, t, tilesAcross), seamDistance(y + half, x + half, t, tilesAcross));
                double line = clamp01((0.035 * IN + px / 2 - seam) / px);
                shade *= (1 - 0.5 * line) * (1 - 0.10 * Math.exp(-seam / (0.10 * IN)));
                // Darker into the corner where the floor meets the wall.
                double wall = half - Math.max(Math.abs(x), Math.abs(y));
                shade *= 1 - 0.32 * Math.exp(-wall / (1.3 * IN));
                for (double[] c : contacts) {
                    double dx = Math.max(Math.max(c[0] - x, x - c[2]), 0), dy = Math.max(Math.max(c[1] - y, y - c[3]), 0);
                    shade *= 1 - c[4] * Math.exp(-Math.hypot(dx, dy) / c[5]);
                }
                for (double[] p : pools) {
                    double u = (x - p[0]) / p[2], v = (y - p[1]) / p[3];
                    shade *= 1 - p[4] * Math.exp(-(u * u + v * v));
                }
                argb[j * n + i] = rgb(0x5B * shade, 0x5F * shade, 0x65 * shade);
            }
        });
        return image(n, n, argb);
    }

    /**
     * How far (m) a point is from the nearest seam running along the second coordinate: tiles
     * lock together with square-ish teeth, so the seam zigzags half a tooth either side of the
     * tile line. a = position across the seams, b = position along them (both from the field's corner).
     */
    private static double seamDistance(double a, double b, double t, int tiles) {
        int k = (int) Math.round(a / t);
        if (k <= 0 || k >= tiles) return Double.MAX_VALUE;
        double across = a - k * t;
        double period = t / 8, depth = 0.28 * IN;
        double phase = b / period - Math.floor(b / period);
        // Trapezoid teeth: out, ramp, in, ramp.
        double off, slope;
        if (phase < 0.08) { off = depth * phase / 0.08; slope = depth / (0.08 * period); }
        else if (phase < 0.42) { off = depth; slope = 0; }
        else if (phase < 0.58) { off = depth * (1 - 2 * (phase - 0.42) / 0.16); slope = -2 * depth / (0.16 * period); }
        else if (phase < 0.92) { off = -depth; slope = 0; }
        else { off = -depth * (1 - (phase - 0.92) / 0.08); slope = depth / (0.08 * period); }
        if ((k & 1) == 1) off = -off;
        return Math.abs(across - off) / Math.sqrt(1 + slope * slope);
    }

    /** Low-pile venue carpet: dark gray, a fine fiber grain and faint blotches. Repeats seamlessly. */
    static Image carpet() {
        return CACHE.computeIfAbsent("carpet", k -> {
            int n = 512;
            int[] argb = new int[n * n];
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    double shade = 1 + 0.08 * (noise(i / 4.0, j / 4.0, n / 4, 5) - 0.5)
                            + 0.10 * (fbm(i / 64.0, j / 64.0, 3, 8, 6) - 0.5);
                    argb[j * n + i] = rgb(0x34 * shade, 0x36 * shade, 0x3B * shade);
                }
            }
            return image(n, n, argb);
        });
    }

    /** Black pipe-and-drape: soft vertical folds. Repeats around (left-right). */
    static Image drape() {
        return CACHE.computeIfAbsent("drape", k -> {
            int w = 512, h = 256;
            int[] argb = new int[w * h];
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) {
                    double u = (double) i / w;
                    double wobble = 0.03 * (noise(u * 16, j / 64.0, 16, 9) - 0.5);
                    double fold = 0.5 + 0.5 * Math.sin(2 * Math.PI * (u * 12 + wobble) + 1.3 * Math.sin(2 * Math.PI * u * 5));
                    double shade = 0.55 + 0.6 * Math.pow(fold, 1.6) + 0.04 * (noise(i / 2.0, j / 2.0, w / 2, 4) - 0.5);
                    shade *= 0.80 + 0.20 * j / (double) h; // a little darker toward the top
                    argb[j * w + i] = rgb(0x1C * shade, 0x1D * shade, 0x22 * shade);
                }
            }
            return image(w, h, argb);
        });
    }

    /**
     * The LED ribbon board along the front of the stands (it gives off its own light; up close
     * you see its grid of LEDs). "calm": blue into purple with a slanting light line; "red" or
     * "blue": that alliance's color with white arrows, flashed when it scores. Repeats along it.
     */
    static Image ribbon(String kind) {
        return CACHE.computeIfAbsent("ribbon " + kind, k -> {
            int w = 512, h = 32;
            int[] argb = new int[w * h];
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) {
                    double r, g, b;
                    if (kind.equals("calm")) {
                        double t = 0.5 + 0.5 * Math.sin(2 * Math.PI * 2 * i / w);
                        double line = Math.floorMod(i + 2 * j, 128) < 6 ? 0.8 : 0;
                        r = 0.55 * (0x15 + t * (0x7B - 0x15)) + line * 200;
                        g = 0.55 * (0x65 + t * (0x1F - 0x65)) + line * 200;
                        b = 0.55 * (0xC0 + t * (0xA2 - 0xC0)) + line * 200;
                    } else {
                        int c = kind.equals("red") ? 0xE53935 : 0x1E88E5;
                        boolean arrow = Math.floorMod(i - (int) Math.abs(j - 15.5), 64) < 12;
                        r = arrow ? 255 : (c >> 16) & 0xFF;
                        g = arrow ? 255 : (c >> 8) & 0xFF;
                        b = arrow ? 255 : c & 0xFF;
                    }
                    // Bright in the middle of each 4 px cell, dark between: the LEDs.
                    double cx = (i % 4) - 1.5, cy = (j % 4) - 1.5;
                    double led = 0.45 + 0.55 * Math.exp(-(cx * cx + cy * cy) / 2.5);
                    argb[j * w + i] = rgb(r * led, g * led, b * led);
                }
            }
            return image(w, h, argb);
        });
    }

    /**
     * A soft rectangle of shade (black, see-through at the edges) for under a robot: darkest
     * (alpha) in the middle, fading out over the outer part of the picture.
     */
    static Image softRect(double alpha) {
        return CACHE.computeIfAbsent("rect " + alpha, k -> {
            int n = 128;
            int[] argb = new int[n * n];
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    double u = Math.abs((i + 0.5) / n * 2 - 1), v = Math.abs((j + 0.5) / n * 2 - 1);
                    // Distance outside a rounded square of half-size 0.62 (corner radius 0.2).
                    double qx = Math.max(u - 0.42, 0), qy = Math.max(v - 0.42, 0);
                    double d = Math.hypot(qx, qy) - 0.2;
                    double a = alpha * (1 - smooth(-0.18, 0.36, d));
                    argb[j * n + i] = ((int) Math.round(clamp01(a) * 255) << 24);
                }
            }
            return image(n, n, argb);
        });
    }

    /** A round soft shadow (black, see-through at the edge) for under a ball. */
    static Image blob(double alpha) {
        return CACHE.computeIfAbsent("blob " + alpha, k -> {
            int n = 64;
            int[] argb = new int[n * n];
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    double u = (i + 0.5) / n * 2 - 1, v = (j + 0.5) / n * 2 - 1, r = Math.hypot(u, v);
                    double a = alpha * Math.exp(-r * r * 2.2) * (1 - smooth(0.75, 1.0, r));
                    argb[j * n + i] = ((int) Math.round(clamp01(a) * 255) << 24);
                }
            }
            return image(n, n, argb);
        });
    }

    /** One flat color (alpha included): JavaFX 3D blends a see-through diffuse map correctly, not a see-through color. */
    /**
     * An AprilTag-looking picture: a white margin, the black square, and a 6 x 6 block pattern
     * made from the id (the look of a 36h11 tag, not its real code: the simulator works out
     * detections from the geometry, not by reading pictures). 10 x 10 cells, top of the tag up.
     */
    static Image aprilTag(int id) {
        int cell = 8, n = 10;
        WritableImage img = new WritableImage(cell * n, cell * n);
        var w = img.getPixelWriter();
        long bits = id * 0x9E3779B97F4A7C15L + 0x7F4A7C15L;
        for (int cy = 0; cy < n; cy++) {
            for (int cx = 0; cx < n; cx++) {
                boolean white;
                if (cx == 0 || cy == 0 || cx == n - 1 || cy == n - 1) white = true;            // margin
                else if (cx == 1 || cy == 1 || cx == n - 2 || cy == n - 2) white = false;      // black border
                else {
                    int k = (cy - 2) * 6 + (cx - 2);
                    white = ((bits >>> (k % 64)) & 1) == 1 ^ (k >= 32 && ((bits >>> 7) & 1) == 1);
                }
                int argb = white ? 0xFFF4F4F2 : 0xFF141414;
                for (int y = 0; y < cell; y++) for (int x = 0; x < cell; x++) w.setArgb(cx * cell + x, cy * cell + y, argb);
            }
        }
        return img;
    }

    static Image solid(int rgb, double alpha) {
        return CACHE.computeIfAbsent("solid " + rgb + " " + alpha, k -> {
            int a = (int) Math.round(clamp01(alpha) * 255);
            int[] argb = new int[4];
            java.util.Arrays.fill(argb, (a << 24) | (rgb & 0xFFFFFF));
            return image(2, 2, argb);
        });
    }

    // ------------------------------------------------------------------ helpers

    private static Image image(int w, int h, int[] argb) {
        WritableImage img = new WritableImage(w, h);
        img.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        return img;
    }

    private static int rgb(double r, double g, double b) {
        return 0xFF000000 | (channel(r) << 16) | (channel(g) << 8) | channel(b);
    }

    private static int channel(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** 0 below a, 1 above b, a smooth step between. */
    static double smooth(double a, double b, double x) {
        double t = clamp01((x - a) / (b - a));
        return t * t * (3 - 2 * t);
    }

    /** A repeatable pseudo-random number in 0..1 for a lattice point. */
    static double hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0xFFFFFF;
    }

    /** Smooth value noise in 0..1; with period > 0 it repeats every period units. */
    static double noise(double x, double y, int period, int seed) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double fx = x - ix, fy = y - iy;
        double u = fx * fx * (3 - 2 * fx), v = fy * fy * (3 - 2 * fy);
        int x0 = ix, x1 = ix + 1, y0 = iy, y1 = iy + 1;
        if (period > 0) {
            x0 = Math.floorMod(x0, period);
            x1 = Math.floorMod(x1, period);
            y0 = Math.floorMod(y0, period);
            y1 = Math.floorMod(y1, period);
        }
        double a = hash(x0, y0, seed), b = hash(x1, y0, seed), c = hash(x0, y1, seed), d = hash(x1, y1, seed);
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
    }

    /** Layers of noise, each twice as fine and half as strong (0..1). */
    static double fbm(double x, double y, int octaves, int period, int seed) {
        double sum = 0, amp = 0.5, norm = 0;
        for (int o = 0; o < octaves; o++) {
            sum += amp * noise(x, y, period, seed + o);
            norm += amp;
            x *= 2;
            y *= 2;
            period *= 2;
            amp *= 0.5;
        }
        return sum / norm;
    }
}
