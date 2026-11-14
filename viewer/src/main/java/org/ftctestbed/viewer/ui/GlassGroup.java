package org.ftctestbed.viewer.ui;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;

/**
 * A macOS Liquid Glass capsule holding a group of toolbar buttons (FluentButton.Style.GLASS),
 * as macOS 26 toolbars group related items: a frosted see-through body, a bright specular rim
 * along the top, a soft shadow under it. It's painted, not blurred, so it costs nothing per
 * frame. With every button hidden the group takes no room (see shows()).
 */
public final class GlassGroup extends JPanel {
    /** Room around the capsule for its shadow. */
    static final int MARGIN = 3;

    public GlassGroup(Component... buttons) {
        super(null);
        setOpaque(false);
        for (Component c : buttons) add(c);
    }

    /** True if c is showing and, for a group, has a visible button. */
    static boolean shows(Component c) {
        if (!c.isVisible()) return false;
        if (!(c instanceof GlassGroup g)) return true;
        for (Component b : g.getComponents()) if (b.isVisible()) return true;
        return false;
    }

    @Override
    public Dimension getPreferredSize() {
        int w = 0, h = 0;
        for (Component c : getComponents()) {
            if (!c.isVisible()) continue;
            Dimension d = c.getPreferredSize();
            w += d.width;
            h = Math.max(h, d.height);
        }
        return new Dimension(w + 2 * MARGIN, h + 2 * MARGIN);
    }

    @Override
    public void doLayout() {
        int x = MARGIN, h = getHeight();
        for (Component c : getComponents()) {
            if (!c.isVisible()) continue;
            Dimension d = c.getPreferredSize();
            c.setBounds(x, (h - d.height) / 2, d.width, d.height);
            x += d.width;
        }
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Theme t = Appearance.theme();
        double w = getWidth() - 2 * MARGIN, h = getHeight() - 2 * MARGIN;
        Shape body = new RoundRectangle2D.Double(MARGIN + 0.5, MARGIN + 0.5, w - 1, h - 1, h - 1, h - 1);
        if (t.highContrast()) {
            g.setColor(Color.BLACK);
            g.fill(body);
            g.setStroke(new BasicStroke(2f));
            g.setColor(t.palette.text());
            g.draw(body);
            g.dispose();
            return;
        }
        // Shadow: a few soft rings, offset down.
        for (int i = 3; i >= 1; i--) {
            g.setColor(new Color(0, 0, 0, (t.dark ? 22 : 10) / i));
            g.fill(new RoundRectangle2D.Double(MARGIN - i * 0.7, MARGIN + 1.2, w + i * 1.4, h + i * 0.6, h + i, h + i));
        }
        // Body: frosted, lighter at the top where the light catches it.
        Color top = Contrast.alpha(Color.WHITE, t.dark ? 0.16 : 0.78), bottom = Contrast.alpha(Color.WHITE, t.dark ? 0.07 : 0.52);
        g.setPaint(new GradientPaint(0, MARGIN, top, 0, (float) (MARGIN + h), bottom));
        g.fill(body);
        // Specular sheen across the upper half.
        g.setPaint(new GradientPaint(0, MARGIN, Contrast.alpha(Color.WHITE, t.dark ? 0.10 : 0.35), 0, (float) (MARGIN + h * 0.5),
                Contrast.alpha(Color.WHITE, 0)));
        g.fill(new RoundRectangle2D.Double(MARGIN + 1.5, MARGIN + 1.5, w - 3, h * 0.5, h - 3, h - 3));
        // Outer edge: a faint dark line so the glass reads on light backgrounds.
        boolean strong = Appearance.increasedContrast();
        g.setStroke(new BasicStroke(1f));
        g.setColor(strong ? Contrast.alpha(t.palette.text(), 0.55) : Contrast.alpha(Color.BLACK, t.dark ? 0.45 : 0.10));
        g.draw(new RoundRectangle2D.Double(MARGIN, MARGIN, w, h, h, h));
        // Inner rim: bright at the top, fading toward the bottom.
        g.setPaint(new GradientPaint(0, MARGIN, Contrast.alpha(Color.WHITE, t.dark ? 0.42 : 0.95), 0, (float) (MARGIN + h),
                Contrast.alpha(Color.WHITE, t.dark ? 0.06 : 0.35)));
        g.draw(new RoundRectangle2D.Double(MARGIN + 1, MARGIN + 1, w - 2, h - 2, h - 2, h - 2));
        g.dispose();
    }
}
