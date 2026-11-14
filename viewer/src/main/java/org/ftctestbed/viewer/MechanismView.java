package org.ftctestbed.viewer;

import org.ftctestbed.sim.Snapshot;
import org.ftctestbed.sim.game.RobotPieceHandling;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Locale;

/** Small gauges for mechanisms (slides as bars, arms and servos as dials). */
public final class MechanismView extends JComponent {
    private static final int ROW = 34;
    private static final Color FILL = new Color(0xF2B33D);
    // The gauges' track and labels follow the app's theme.
    private Color TRACK = new Color(0xD5D8DC), TEXT = new Color(0x30343A);

    private List<Snapshot.Mechanism> mechanisms = List.of();
    private List<Snapshot.Servo> servos = List.of();
    private List<Snapshot.Light> lights = List.of();
    private RobotPieceHandling.Parts parts;

    public void update(List<Snapshot.Mechanism> m, List<Snapshot.Servo> s, RobotPieceHandling.Parts p) {
        update(m, s, p, List.of());
    }

    public void update(List<Snapshot.Mechanism> m, List<Snapshot.Servo> s, RobotPieceHandling.Parts p, List<Snapshot.Light> l) {
        if (l == null) l = List.of();
        if (Snapshot.sameValues(m, mechanisms) && Snapshot.sameValues(s, servos) && Snapshot.sameValues(l, lights)
                && Snapshot.sameValues(p, parts)) return;
        boolean resize = m.size() != mechanisms.size() || s.size() != servos.size() || l.size() != lights.size()
                || (p == null) != (parts == null);
        mechanisms = m;
        servos = s;
        lights = l;
        parts = p;
        if (resize) revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(300, (mechanisms.size() + servos.size() + lights.size() + (parts != null ? 1 : 0)) * ROW + 8);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        org.ftctestbed.viewer.ui.Theme.Palette p = org.ftctestbed.viewer.ui.Appearance.palette();
        TEXT = p.text();
        TRACK = org.ftctestbed.viewer.ui.Contrast.alpha(p.text(), 0.22);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, (int) Math.round(12 * org.ftctestbed.viewer.ui.Appearance.textScale())));
        int y = 4;
        int w = getWidth();
        if (parts != null) {
            g.setColor(TEXT);
            g.drawString("Hopper", 8, y + 20);
            for (int i = 0; i < parts.hopperColors().size(); i++) {
                g.setColor(new Color(parts.hopperColors().get(i)));
                g.fillOval(110 + i * 20, y + 8, 15, 15);
            }
            g.setColor(TEXT);
            g.drawString(String.format(Locale.US, "%s  flywheel %.0f rpm", parts.staged() ? "staged" : "", parts.flywheelRpm()), 200, y + 20);
            y += ROW;
        }
        for (Snapshot.Mechanism m : mechanisms) {
            g.setColor(TEXT);
            g.drawString(m.name(), 8, y + 20);
            String value;
            if (m.type().equals("slide")) {
                double frac = (m.position() - m.min()) / (m.max() - m.min());
                bar(g, 110, y + 10, w - 200, frac);
                value = String.format(Locale.US, "%.1f in", m.position());
            } else if (m.type().equals("arm")) {
                dial(g, 110, y, m.position(), m.min(), m.max());
                value = String.format(Locale.US, "%.0f°", m.position());
            } else {
                value = String.format(Locale.US, "%.1f rev", m.position());
            }
            g.setColor(TEXT);
            g.drawString(value, w - 80, y + 20);
            y += ROW;
        }
        for (Snapshot.Servo s : servos) {
            g.setColor(TEXT);
            g.drawString((s.continuous() ? "CR " : "") + s.name(), 8, y + 20);
            double deg = Math.toDegrees(s.angle());
            if (!s.continuous()) dial(g, 110, y, deg, -150, 150);
            String value = !s.enabled() ? "limp (no PWM)"
                    : s.continuous() ? String.format(Locale.US, "power %+.2f", (s.commanded() - 0.5) * 2)
                    : String.format(Locale.US, "pos %.2f  %.0f°", s.commanded(), deg);
            g.drawString(value, s.continuous() ? 110 : 150, y + 20);
            y += ROW;
        }
        // Lights robot code switches: a dot per LED in the color it shows (hollow when off).
        for (Snapshot.Light l : lights) {
            g.setColor(TEXT);
            g.drawString(l.name(), 8, y + 20);
            int[] colors = l.colors();
            int dot = colors.length > 1 ? 13 : 16, gap = colors.length > 1 ? 4 : 0;
            boolean anyOn = false;
            for (int i = 0; i < colors.length; i++) {
                int cx = 110 + i * (dot + gap);
                if (colors[i] != 0) {
                    anyOn = true;
                    g.setColor(new Color(colors[i]));
                    g.fillOval(cx, y + 9, dot, dot);
                }
                g.setColor(TRACK.darker());
                g.drawOval(cx, y + 9, dot, dot);
            }
            if (colors.length == 1) {
                g.setColor(TEXT);
                g.drawString(anyOn ? (l.type().equals("blinkin") ? "Blinkin" : "on") : "off", 134, y + 20);
            }
            y += ROW;
        }
        g.dispose();
    }

    private void bar(Graphics2D g, int x, int y, int w, double frac) {
        frac = Math.max(0, Math.min(1, frac));
        g.setColor(TRACK);
        g.fill(new RoundRectangle2D.Double(x, y, w, 12, 6, 6));
        g.setColor(FILL);
        g.fill(new RoundRectangle2D.Double(x, y, w * frac, 12, 6, 6));
    }

    private void dial(Graphics2D g, int x, int y, double deg, double min, double max) {
        int size = 28;
        double cxp = x + size / 2.0, cyp = y + 3 + size / 2.0;
        g.setColor(TRACK);
        g.setStroke(new BasicStroke(3f));
        g.draw(new Arc2D.Double(x, y + 3, size, size, min, max - min, Arc2D.OPEN));
        g.setColor(FILL.darker());
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double a = Math.toRadians(deg);
        g.draw(new Line2D.Double(cxp, cyp, cxp + Math.cos(a) * size / 2, cyp - Math.sin(a) * size / 2));
    }
}
