package org.ftctestbed.viewer.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.util.Objects;

/**
 * The status in the title bar: what's running (with a colored dot), the clock, and the score, on
 * a small card. VoiceOver reads it as one sentence.
 */
public final class StatusPill extends Surface {
    /** red/blue: the score, or null with no season. kind picks the dot's color. */
    public record Status(Kind kind, String text, String clock, Integer red, Integer blue) {}

    public enum Kind { IDLE, READY, RUNNING, STOPPED, PAUSED, ERROR }

    private static final Color RED = new Color(0xFF3B30), BLUE = new Color(0x0A84FF);
    private Status status = new Status(Kind.IDLE, "Practice", "", null, null);

    public StatusPill() {
        super(Fluent.Material.CARD, Fluent.CONTROL_ARC, 0, null);
        getAccessibleContext().setAccessibleName("Status");
    }

    public void set(Status s) {
        if (Objects.equals(s, status)) return;
        boolean resize = !Objects.equals(s.text(), status.text()) || (s.red() == null) != (status.red() == null);
        status = s;
        String said = s.text() + (s.clock().isEmpty() ? "" : ", " + s.clock())
                + (s.red() == null ? "" : ", red " + s.red() + ", blue " + s.blue());
        getAccessibleContext().setAccessibleDescription(said);
        setToolTipText(said);
        if (resize) {
            revalidate();
            if (getParent() != null) getParent().doLayout();
        }
        repaint();
    }

    private Font font(boolean bold) {
        Font f = getFont();
        return f.deriveFont(bold ? Font.BOLD : Font.PLAIN, f.getSize2D());
    }

    private Font mono() {
        return org.ftctestbed.viewer.platform.Os.mono(Font.BOLD, Math.round(getFont().getSize2D() * 1.05f));
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(font(true)), mm = getFontMetrics(mono());
        int w = 12 + 8 + 8 + fm.stringWidth(status.text());
        if (!status.clock().isEmpty()) w += 18 + mm.stringWidth("00:00");
        if (status.red() != null) w += 18 + 10 + 6 + fm.stringWidth("000 – 000") + 6 + 10;
        return new Dimension(Math.max(150, w + 12), Math.max(28, fm.getHeight() + 10));
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Theme.Palette p = Appearance.palette();
        int h = getHeight(), x = 12;
        FontMetrics fm = g.getFontMetrics(font(true));
        int base = (h - fm.getHeight()) / 2 + fm.getAscent();
        Color dot = switch (status.kind()) {
            case RUNNING -> p.go();
            case READY, PAUSED -> p.warn();
            case STOPPED -> Contrast.alpha(p.text(), 0.5);
            case ERROR -> p.stop();
            case IDLE -> Contrast.alpha(p.text(), 0.35);
        };
        g.setColor(dot);
        g.fill(new Ellipse2D.Double(x, h / 2.0 - 4, 8, 8));
        x += 16;
        g.setFont(font(true));
        g.setColor(p.text());
        g.drawString(clip(status.text(), fm, getWidth() - x - 12), x, base);
        x += fm.stringWidth(status.text());
        if (!status.clock().isEmpty()) {
            x += 9;
            separator(g, p, x, h);
            x += 9;
            g.setFont(mono());
            FontMetrics mm = g.getFontMetrics();
            g.drawString(status.clock(), x, (h - mm.getHeight()) / 2 + mm.getAscent());
            x += mm.stringWidth("00:00");
        }
        if (status.red() != null) {
            x += 9;
            separator(g, p, x, h);
            x += 9;
            g.setFont(font(true));
            g.setColor(RED);
            g.fill(new Ellipse2D.Double(x, h / 2.0 - 5, 10, 10));
            x += 16;
            g.setColor(p.text());
            String score = status.red() + " – " + status.blue();
            g.drawString(score, x, base);
            x += fm.stringWidth(score) + 6;
            g.setColor(BLUE);
            g.fill(new Ellipse2D.Double(x, h / 2.0 - 5, 10, 10));
        }
        g.dispose();
    }

    private static void separator(Graphics2D g, Theme.Palette p, int x, int h) {
        g.setColor(Contrast.alpha(p.text(), 0.25));
        g.fillRect(x, h / 2 - 7, 1, 14);
        g.setColor(p.text());
    }

    private static String clip(String s, FontMetrics fm, int w) {
        if (fm.stringWidth(s) <= w) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
