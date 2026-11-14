package org.firstinspires.ftc.vision.opencv;

import org.opencv.core.Scalar;

/** A box of colors in one color space (OpenCV 8-bit: HSV hue is 0-180). */
public class ColorRange {
    protected final ColorSpace colorSpace;
    protected final Scalar min;
    protected final Scalar max;

    public static final ColorRange BLUE = new ColorRange(ColorSpace.YCrCb, new Scalar(16, 0, 155), new Scalar(255, 127, 255));
    public static final ColorRange RED = new ColorRange(ColorSpace.YCrCb, new Scalar(32, 176, 0), new Scalar(255, 255, 132));
    public static final ColorRange YELLOW = new ColorRange(ColorSpace.YCrCb, new Scalar(32, 128, 0), new Scalar(255, 170, 120));
    public static final ColorRange GREEN = new ColorRange(ColorSpace.YCrCb, new Scalar(32, 0, 0), new Scalar(255, 120, 133));
    public static final ColorRange ARTIFACT_GREEN = new ColorRange(ColorSpace.YCrCb, new Scalar(32, 50, 118), new Scalar(255, 105, 145));
    public static final ColorRange ARTIFACT_PURPLE = new ColorRange(ColorSpace.YCrCb, new Scalar(32, 135, 135), new Scalar(255, 155, 169));

    public ColorRange(ColorSpace colorSpace, Scalar min, Scalar max) {
        this.colorSpace = colorSpace;
        this.min = min;
        this.max = max;
    }

    /** OpenCV's cvtColor from RGB to this range's space (8-bit), then inRange. */
    boolean contains(int rgb) {
        int[] c = convert(rgb, colorSpace);
        for (int i = 0; i < 3; i++) if (c[i] < min.val[i] || c[i] > max.val[i]) return false;
        return true;
    }

    static int[] convert(int rgb, ColorSpace space) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        switch (space) {
            case YCrCb: return yCrCb(r, g, b);
            case HSV: return hsv(r, g, b);
            default: return new int[]{r, g, b};
        }
    }

    /** OpenCV COLOR_RGB2YCrCb, 8-bit. */
    static int[] yCrCb(int r, int g, int b) {
        double y = 0.299 * r + 0.587 * g + 0.114 * b;
        int cr = sat((r - y) * 0.713 + 128), cb = sat((b - y) * 0.564 + 128);
        return new int[]{sat(y), cr, cb};
    }

    /** OpenCV COLOR_RGB2HSV, 8-bit: H 0-180, S and V 0-255. */
    static int[] hsv(int r, int g, int b) {
        int v = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        double d = v - mn;
        int s = v == 0 ? 0 : sat(255 * d / v);
        double h;
        if (d == 0) h = 0;
        else if (v == r) h = 60 * (g - b) / d;
        else if (v == g) h = 120 + 60 * (b - r) / d;
        else h = 240 + 60 * (r - g) / d;
        if (h < 0) h += 360;
        return new int[]{(int) Math.round(h / 2) % 180, s, v};
    }

    static int sat(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
