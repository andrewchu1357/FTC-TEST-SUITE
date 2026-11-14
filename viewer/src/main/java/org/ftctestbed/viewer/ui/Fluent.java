package org.ftctestbed.viewer.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;

/**
 * Fluent (Windows 11) surfaces and control colors. The window is made of Mica (here: the theme's
 * base color); the content sits on a Layer a step lighter, and groups of controls on Cards. All
 * three are solid, so drawing them costs nothing and text on them is checked once per theme.
 * Acrylic, the see-through material, is only for menus and the open navigation pane (see Acrylic).
 * Everything is derived from the palette, so every theme gets the same structure.
 */
public final class Fluent {
    /** MICA: the window's base. LAYER: the content area. CARD: a group of controls on the layer. */
    public enum Material { MICA, LAYER, CARD }

    /** Corner diameters: controls (4 px radius), cards, menus and panes (8 px radius). */
    public static final int CONTROL_ARC = 8, OVERLAY_ARC = 16;

    private Fluent() {}

    public static Color mica(Theme t) {
        return t.palette.window();
    }

    public static Color layer(Theme t) {
        if (t.highContrast()) return Color.BLACK;
        return Contrast.over(Contrast.alpha(Color.WHITE, t.dark ? 0.035 : 0.5), t.palette.window());
    }

    public static Color card(Theme t) {
        if (t.highContrast()) return Color.BLACK;
        return Contrast.over(Contrast.alpha(Color.WHITE, t.dark ? 0.05 : 0.7), layer(t));
    }

    /** The color of material m in theme t (solid). */
    public static Color fill(Theme t, Material m) {
        return switch (m) {
            case MICA -> mica(t);
            case LAYER -> layer(t);
            case CARD -> card(t);
        };
    }

    /** Card and layer outlines, and dividers: a hairline, or a solid 2 px line in high contrast. */
    public static Color stroke(Theme t) {
        if (t.highContrast()) return t.palette.text();
        if (Appearance.increasedContrast()) return Contrast.alpha(t.palette.text(), 0.5);
        return t.palette.hairline();
    }

    public static float strokeWidth(Theme t) {
        return t.highContrast() ? 2f : 1f;
    }

    /** A standard control's fill: rest, under the pointer, pressed (over a card or layer). */
    public static Color controlFill(Theme t, boolean hover, boolean pressed) {
        if (t.highContrast()) return Color.BLACK;
        if (t.dark) return Contrast.alpha(Color.WHITE, pressed ? 0.03 : hover ? 0.085 : 0.06);
        return Contrast.alpha(Color.WHITE, pressed ? 0.3 : hover ? 0.5 : 0.7);
    }

    /** A subtle control (no fill at rest): under the pointer, pressed, or selected (as in the navigation pane). */
    public static Color subtleFill(Theme t, boolean hover, boolean pressed) {
        if (t.highContrast()) return pressed || hover ? Contrast.alpha(t.palette.text(), 0.25) : new Color(0, 0, 0, 0);
        Color ink = t.dark ? Color.WHITE : Color.BLACK;
        if (pressed) return Contrast.alpha(ink, t.dark ? 0.04 : 0.024);
        if (hover) return Contrast.alpha(ink, t.dark ? 0.06 : 0.037);
        return new Color(0, 0, 0, 0);
    }

    /** A rounded rectangle filling w x h, half a pixel in so its edge is crisp. */
    public static Shape round(double w, double h, double arc) {
        return new RoundRectangle2D.Double(0.5, 0.5, w - 1, h - 1, arc, arc);
    }

    /** Paints material m in shape with its outline (Mica has none). */
    public static void paint(Graphics2D g0, Shape shape, Material m) {
        Theme t = Appearance.theme();
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(fill(t, m));
        g.fill(shape);
        if (m != Material.MICA) {
            g.setStroke(new BasicStroke(strokeWidth(t)));
            g.setColor(stroke(t));
            g.draw(shape);
        }
        g.dispose();
    }

    /**
     * Fluent's elevation border on a control: a hairline that's lighter at the top and darker
     * at the bottom (light themes), or brighter at the top (dark themes), so it looks raised.
     */
    static void elevation(Graphics2D g, Shape body, Theme t, boolean accent) {
        if (t.highContrast()) {
            g.setStroke(new BasicStroke(2f));
            g.setColor(t.palette.text());
            g.draw(body);
            return;
        }
        if (Appearance.increasedContrast()) {
            g.setStroke(new BasicStroke(1.2f));
            g.setColor(Contrast.alpha(t.palette.text(), 0.55));
            g.draw(body);
            return;
        }
        java.awt.Rectangle r = body.getBounds();
        Color top, bottom;
        if (accent) {
            top = Contrast.alpha(Color.WHITE, 0.08);
            bottom = Contrast.alpha(Color.BLACK, t.dark ? 0.14 : 0.4);
        } else if (t.dark) {
            top = Contrast.alpha(Color.WHITE, 0.09);
            bottom = Contrast.alpha(Color.WHITE, 0.05);
        } else {
            top = Contrast.alpha(Color.BLACK, 0.06);
            bottom = Contrast.alpha(Color.BLACK, 0.16);
        }
        g.setStroke(new BasicStroke(1f));
        g.setPaint(new java.awt.GradientPaint(0, r.y + r.height - 3, top, 0, r.y + r.height, bottom));
        g.draw(body);
    }

    /**
     * Fluent's keyboard focus visual: a 2 px outer ring in the text color with a 1 px inner ring
     * in the surface color, so it shows on any background. Drawn just outside bounds.
     */
    static void focus(Graphics2D g, double x, double y, double w, double h, double arc) {
        Theme t = Appearance.theme();
        Graphics2D f = (Graphics2D) g.create();
        f.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        f.setStroke(new BasicStroke(1f));
        f.setColor(t.highContrast() ? Color.BLACK : layer(t));
        f.draw(new RoundRectangle2D.Double(x - 0.5, y - 0.5, w + 1, h + 1, arc + 1, arc + 1));
        f.setStroke(new BasicStroke(t.highContrast() ? 3f : 2f));
        f.setColor(t.highContrast() ? t.palette.warn() : t.palette.text());
        f.draw(new RoundRectangle2D.Double(x - 2, y - 2, w + 4, h + 4, arc + 4, arc + 4));
        f.dispose();
    }
}
