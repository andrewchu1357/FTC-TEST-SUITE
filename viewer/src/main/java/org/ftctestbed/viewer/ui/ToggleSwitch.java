package org.ftctestbed.viewer.ui;

import javax.swing.ButtonModel;
import javax.swing.JCheckBox;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Fluent's toggle switch, for a setting that's on or off: "On" or "Off" beside a pill whose
 * knob slides across. It's a check box underneath, so code and VoiceOver use it as one; its
 * text is its accessible name (the label is shown by the settings card it sits in).
 */
public class ToggleSwitch extends JCheckBox {
    private static final int TRACK_W = 40, TRACK_H = 20, RING = 3;

    public ToggleSwitch(String name, boolean selected) {
        super(name, selected);
        setup();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setup();
    }

    private void setup() {
        setOpaque(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
        setRequestFocusEnabled(false);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        int words = Math.max(fm.stringWidth("On"), fm.stringWidth("Off"));
        return new Dimension(words + 10 + TRACK_W + 2 * RING, Math.max(TRACK_H + 2 * RING + 6, fm.getHeight() + 8));
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Theme t = Appearance.theme();
        Theme.Palette p = t.palette;
        ButtonModel m = getModel();
        boolean on = isSelected(), enabled = isEnabled();
        boolean hover = enabled && m.isRollover(), pressed = enabled && m.isArmed() && m.isPressed();
        int w = getWidth(), h = getHeight();
        double tx = w - RING - TRACK_W, ty = (h - TRACK_H) / 2.0;
        RoundRectangle2D track = new RoundRectangle2D.Double(tx + 0.5, ty + 0.5, TRACK_W - 1, TRACK_H - 1, TRACK_H, TRACK_H);
        Color text = enabled ? p.text() : Contrast.alpha(p.text(), 0.45);

        // "On" / "Off", right next to the switch.
        g.setFont(getFont());
        FontMetrics fm = g.getFontMetrics();
        String word = on ? "On" : "Off";
        g.setColor(text);
        g.drawString(word, (int) tx - 10 - fm.stringWidth(word), (h - fm.getHeight()) / 2 + fm.getAscent());

        Color knob;
        if (on) {
            Color fill = Contrast.readableBehind(p.accent(), Contrast.textOn(p.accent()), 3.0);
            if (hover) fill = Contrast.toward(fill, Contrast.textOn(fill), 0.09);
            g.setColor(enabled ? fill : Contrast.alpha(p.text(), 0.2));
            g.fill(track);
            knob = enabled ? Contrast.textOn(fill) : Contrast.alpha(p.text(), 0.5);
            if (t.highContrast()) {
                g.setStroke(new BasicStroke(2f));
                g.setColor(p.text());
                g.draw(track);
            }
        } else {
            g.setColor(Fluent.subtleFill(t, hover, pressed));
            g.fill(track);
            g.setStroke(new BasicStroke(t.highContrast() ? 2f : 1f));
            Color edge = t.highContrast() ? p.text() : p.secondary();
            g.setColor(enabled ? edge : Contrast.alpha(edge, 0.45));
            g.draw(track);
            knob = enabled ? p.secondary() : Contrast.alpha(p.secondary(), 0.45);
        }
        // The knob grows under the pointer and stretches while pressed, as Windows' does.
        double kh = hover || pressed ? 14 : 12, kw = pressed ? 17 : kh;
        double kx = on ? tx + TRACK_W - 4 - kw : tx + 4, ky = ty + (TRACK_H - kh) / 2;
        g.setColor(knob);
        g.fill(pressed ? new RoundRectangle2D.Double(kx, ky, kw, kh, kh, kh) : new Ellipse2D.Double(kx, ky, kw, kh));
        if (FocusRing.shows(this)) Fluent.focus(g, tx, ty, TRACK_W, TRACK_H, TRACK_H);
        g.dispose();
    }
}
