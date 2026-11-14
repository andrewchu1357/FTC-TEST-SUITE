package org.ftctestbed.viewer.ui;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Random;

/**
 * Fluent's acrylic: a surface you see the window through, blurred, tinted and with a fine
 * grain. As in Windows 11 it's only for things that come and go (menus, the open navigation
 * pane), and it's drawn the light way: what's behind is captured once when the surface opens,
 * shrunk, blurred and tinted into one small image, so showing it costs a single image draw. The
 * tint is made just opaque enough for text on it to stay readable over whatever is behind
 * (Contrast.TEXT); with transparency effects off it's solid.
 */
public final class Acrylic {
    /** The backdrop is captured at a quarter size: it's blurred anyway, and it's sixteen times less work. */
    static final int SCALE = 4;
    private static TexturePaint noise;

    private Acrylic() {}

    /**
     * The backdrop for an acrylic surface covering r (in c's coordinates; c is usually the
     * window's layered pane): what's there now, blurred, with the theme's tint over it. null
     * when transparency effects are off or it can't be drawn (then the surface is solid).
     */
    public static BufferedImage capture(JComponent c, Rectangle r) {
        Theme t = Appearance.theme();
        if (Appearance.reducedTransparency() || r.width <= 0 || r.height <= 0 || !c.isShowing()) return null;
        int w = (r.width + SCALE - 1) / SCALE, h = (r.height + SCALE - 1) / SCALE;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(t.palette.window());
            g.fillRect(0, 0, w, h);
            g.scale(1.0 / SCALE, 1.0 / SCALE);
            g.translate(-r.x, -r.y);
            g.clip(r);
            c.print(g);
        } catch (RuntimeException e) {
            return null; // something under it can't be drawn off screen: solid instead
        } finally {
            g.dispose();
        }
        blur(img, 3);
        tint(img, t.palette);
        return img;
    }

    /** Lays the palette's tint over the blurred backdrop, as opaque as the worst spot in it needs. */
    static void tint(BufferedImage img, Theme.Palette p) {
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        boolean lightText = Contrast.luminance(p.text()) > 0.5;
        int worst = lightText ? 0x000000 : 0xFFFFFF;
        double worstL = lightText ? -1 : 2;
        for (int v : px) {
            double l = ((v >> 16 & 0xFF) * 0.2126 + (v >> 8 & 0xFF) * 0.7152 + (v & 0xFF) * 0.0722) / 255;
            if (lightText ? l > worstL : l < worstL) {
                worstL = l;
                worst = v;
            }
        }
        double a = requiredAlpha(p, new Color(worst));
        Graphics2D g = img.createGraphics();
        g.setColor(Contrast.alpha(p.acrylic(), a));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.dispose();
    }

    /**
     * The least opacity of the palette's acrylic tint over a color for both text colors on it
     * to reach Contrast.TEXT (with a little to spare), never below the tint's own.
     */
    static double requiredAlpha(Theme.Palette p, Color behind) {
        for (double a = p.acrylic().getAlpha() / 255.0; a < 1; a += 0.02) {
            Color surface = Contrast.over(Contrast.alpha(p.acrylic(), a), behind);
            if (Contrast.ratio(p.secondary(), surface) >= Contrast.TEXT + 0.3 && Contrast.ratio(p.text(), surface) >= Contrast.TEXT + 0.3) return a;
        }
        return 1;
    }

    /** What acrylic shows when it's solid (transparency effects off, or nothing to capture). */
    static Color fallback(Theme t) {
        if (t.highContrast()) return Color.BLACK;
        Color c = t.palette.acrylic();
        return new Color(c.getRed(), c.getGreen(), c.getBlue());
    }

    /**
     * Paints acrylic in shape: the backdrop (or the solid fallback), the grain and the outline.
     * The backdrop covers the component from its top-left corner (as captured for its bounds).
     */
    public static void paint(Graphics2D g0, Shape shape, BufferedImage backdrop) {
        Theme t = Appearance.theme();
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (backdrop == null) {
            g.setColor(fallback(t));
            g.fill(shape);
        } else {
            g.clip(shape);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(backdrop, 0, 0, backdrop.getWidth() * SCALE, backdrop.getHeight() * SCALE, null);
            g.setPaint(noise());
            g.fill(shape);
            g.setClip(null);
        }
        g.setStroke(new BasicStroke(Fluent.strokeWidth(t)));
        g.setColor(Fluent.stroke(t));
        g.draw(shape);
        g.dispose();
    }

    /** Fluent's grain: a tile of faint random specks, made once. */
    static TexturePaint noise() {
        if (noise == null) {
            int n = 128;
            BufferedImage tile = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
            Random random = new Random(7);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int v = random.nextInt(256);
                    tile.setRGB(x, y, (6 << 24) | (v << 16) | (v << 8) | v); // about 2% opaque
                }
            }
            noise = new TexturePaint(tile, new Rectangle(0, 0, n, n));
        }
        return noise;
    }

    /** Two box-blur passes each way (close to a Gaussian). */
    static void blur(BufferedImage img, int radius) {
        int w = img.getWidth(), h = img.getHeight();
        int[] a = ((DataBufferInt) img.getRaster().getDataBuffer()).getData(), b = new int[a.length];
        for (int pass = 0; pass < 2; pass++) {
            boxBlur(a, b, w, h, radius);
            boxBlur(b, a, h, w, radius);
        }
    }

    /** Blurs rows of src into columns of dst (so two calls blur both ways). */
    private static void boxBlur(int[] src, int[] dst, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int rs = 0, gs = 0, bs = 0, row = y * w;
            for (int i = -r; i <= r; i++) {
                int v = src[row + Math.max(0, Math.min(w - 1, i))];
                rs += v >> 16 & 0xFF;
                gs += v >> 8 & 0xFF;
                bs += v & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[x * h + y] = (rs / div) << 16 | (gs / div) << 8 | (bs / div);
                int out = src[row + Math.max(0, x - r)], in = src[row + Math.min(w - 1, x + r + 1)];
                rs += (in >> 16 & 0xFF) - (out >> 16 & 0xFF);
                gs += (in >> 8 & 0xFF) - (out >> 8 & 0xFF);
                bs += (in & 0xFF) - (out & 0xFF);
            }
        }
    }
}
