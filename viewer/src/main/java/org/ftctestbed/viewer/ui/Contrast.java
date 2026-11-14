package org.ftctestbed.viewer.ui;

import java.awt.Color;

/** WCAG contrast arithmetic, for keeping text readable in every theme. */
public final class Contrast {
    /** WCAG AA for normal text. */
    public static final double TEXT = 4.5;

    private Contrast() {}

    /** Relative luminance (0 black .. 1 white) of an opaque color. */
    public static double luminance(Color c) {
        return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
    }

    private static double linear(int v) {
        double s = v / 255.0;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** Contrast ratio (1..21) between two opaque colors. */
    public static double ratio(Color a, Color b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** A translucent color laid over an opaque one, as the screen shows it. */
    public static Color over(Color top, Color bottom) {
        double a = top.getAlpha() / 255.0;
        return new Color(mix(top.getRed(), bottom.getRed(), a), mix(top.getGreen(), bottom.getGreen(), a), mix(top.getBlue(), bottom.getBlue(), a));
    }

    private static int mix(int t, int b, double a) {
        return (int) Math.round(t * a + b * (1 - a));
    }

    /** The same color at another opacity (0..1). */
    public static Color alpha(Color c, double a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(Math.max(0, Math.min(1, a)) * 255));
    }

    /** White or near-black, whichever reads better on bg. */
    public static Color textOn(Color bg) {
        Color white = Color.WHITE, black = new Color(0x111111);
        return ratio(white, bg) >= ratio(black, bg) ? white : black;
    }

    /**
     * bg made darker or lighter (away from fg) just far enough that fg on it reaches the ratio, so a
     * theme's button colors stay recognizable but their labels stay readable.
     */
    public static Color readableBehind(Color bg, Color fg, double ratio) {
        Color c = bg;
        boolean darken = luminance(fg) > luminance(bg);
        for (int i = 0; i < 40 && ratio(c, fg) < ratio; i++) {
            c = darken ? scale(c, 0.93) : toward(c, Color.WHITE, 0.08);
        }
        return c;
    }

    /** fg made lighter or darker (away from bg) until it reaches the ratio on bg. */
    public static Color readableOn(Color fg, Color bg, double ratio) {
        Color c = fg;
        boolean lighten = luminance(bg) < 0.18;
        for (int i = 0; i < 40 && ratio(c, bg) < ratio; i++) {
            c = lighten ? toward(c, Color.WHITE, 0.08) : scale(c, 0.92);
        }
        return c;
    }

    static Color scale(Color c, double f) {
        return new Color((int) (c.getRed() * f), (int) (c.getGreen() * f), (int) (c.getBlue() * f), c.getAlpha());
    }

    static Color toward(Color c, Color to, double f) {
        return new Color(mix(to.getRed(), c.getRed(), f), mix(to.getGreen(), c.getGreen(), f), mix(to.getBlue(), c.getBlue(), f), c.getAlpha());
    }
}
