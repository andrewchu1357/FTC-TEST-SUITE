package org.ftctestbed.viewer;

import org.ftctestbed.sim.Snapshot;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.List;

/**
 * Side view of the last few launches: height against distance travelled, with the HIVE
 * CELL opening heights marked. Green = scored, red = missed. Use it to tune shooter speed.
 */
final class ShotView extends JComponent {
    private static final double IN = 0.0254;
    private static final double MAX_X = 110, MAX_Z = 100; // inches
    private List<Snapshot.Flight> flights = List.of();

    void update(List<Snapshot.Flight> f) {
        if (Snapshot.sameValues(f, flights)) return;
        flights = f;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(420, 260);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        double left = 36, bottom = h - 24, top = 10, right = w - 10;
        double sx = (right - left) / MAX_X, sz = (bottom - top) / MAX_Z;

        g.setColor(new Color(0x1E2023));
        g.fill(new Rectangle2D.Double(left, top, right - left, bottom - top));
        // CELL opening band and FLOWER top.
        g.setColor(new Color(255, 255, 255, 30));
        g.fill(new Rectangle2D.Double(left, bottom - 65.6 * sz, right - left, (65.6 - 53.5) * sz));
        g.setColor(new Color(255, 255, 255, 150));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        g.drawString("CELL opening 53.5-65.6 in", (float) (right - 140), (float) (bottom - 66 * sz - 3));
        g.setColor(new Color(0xE0A82E));
        g.draw(new Line2D.Double(left, bottom - 21.5 * sz, right, bottom - 21.5 * sz));
        g.drawString("FLOWER top 21.5 in", (float) (right - 110), (float) (bottom - 21.5 * sz - 3));
        // Axes.
        g.setColor(Color.GRAY);
        for (int x = 0; x <= MAX_X; x += 12) {
            g.drawString(String.valueOf(x), (float) (left + x * sx - 6), (float) (bottom + 14));
        }
        for (int z = 0; z <= MAX_Z; z += 24) {
            g.drawString(String.valueOf(z), 6, (float) (bottom - z * sz + 4));
        }
        g.drawString("inches travelled →", (float) (left + 4), (float) (bottom - 4));

        for (int fi = 0; fi < flights.size(); fi++) {
            Snapshot.Flight f = flights.get(fi);
            boolean newest = fi == flights.size() - 1;
            List<double[]> pts = f.points();
            if (pts.size() < 2) continue;
            Path2D.Double path = new Path2D.Double();
            double dist = 0;
            double[] prev = pts.get(0);
            path.moveTo(left, bottom - prev[2] / IN * sz);
            for (int i = 1; i < pts.size(); i++) {
                double[] p = pts.get(i);
                dist += Math.hypot(p[0] - prev[0], p[1] - prev[1]) / IN;
                path.lineTo(left + Math.min(dist, MAX_X) * sx, bottom - p[2] / IN * sz);
                prev = p;
            }
            boolean scored = f.outcome().contains("HIVE") || f.outcome().contains("FLOWER");
            boolean flying = f.outcome().equals("in flight");
            Color c = flying ? Color.LIGHT_GRAY : scored ? new Color(0x66BB6A) : new Color(0xEF5350);
            g.setColor(newest ? c : new Color(c.getRed(), c.getGreen(), c.getBlue(), 110));
            g.setStroke(new BasicStroke(newest ? 2.5f : 1.5f));
            g.draw(path);
            if (newest) {
                g.drawString(f.outcome(), (float) (left + Math.min(dist, MAX_X - 20) * sx + 4),
                        (float) Math.max(top + 10, bottom - prev[2] / IN * sz - 4));
            }
        }
        g.dispose();
    }
}
