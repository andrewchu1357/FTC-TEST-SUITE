package org.ftctestbed.viewer;

import org.ftctestbed.sim.season.biobuzz.BioBuzzSnapshot;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

import static org.ftctestbed.sim.season.biobuzz.BioBuzzField.*;

/** Draws the BIOBUZZ field elements (top view). */
final class BioBuzzRenderer {
    private static final Color RED = new Color(0xE53935);
    private static final Color BLUE = new Color(0x1E88E5);
    private static final Color FRAME = new Color(0xB0BEC5);
    private static final Color FLOWER_RING = new Color(0xE0A82E);

    private BioBuzzRenderer() {}

    static Color alliance(String a) {
        return "red".equals(a) ? RED : BLUE;
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    private static Rectangle2D rect(FieldRenderer r, double[] z) {
        return new Rectangle2D.Double(r.sx(z[0]), r.sy(z[3]), (z[1] - z[0]) * r.scale(), (z[3] - z[2]) * r.scale());
    }

    /** Tape, zones and alliance areas - everything below the robot. */
    static void paintFloor(Graphics2D g, FieldRenderer r, BioBuzzSnapshot s) {
        // Alliance areas outside the walls, with the human player's NECTAR.
        double areaW = 10 * IN;
        for (String a : new String[]{"red", "blue"}) {
            double x0 = a.equals("red") ? -HALF - areaW - 3 * IN : HALF + 3 * IN;
            Rectangle2D area = new Rectangle2D.Double(r.sx(x0), r.sy(48.5 * IN), areaW * r.scale(), 97 * IN * r.scale());
            g.setColor(alpha(alliance(a), 45));
            g.fill(area);
            int n = s.humanPlayerNectar().getOrDefault(a, 0);
            double d = NECTAR_DIAMETER * r.scale();
            for (int i = 0; i < n; i++) {
                double cx = r.sx(x0 + areaW / 2), cy = r.sy(-10 * IN + i * 4.2 * IN);
                g.setColor(alliance(a));
                g.fill(new Ellipse2D.Double(cx - d / 2, cy - d / 2, d, d));
            }
            int allowed = s.humanPlayerAllowed().getOrDefault(a, 0);
            if (n > 0) {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
                g.setColor(Color.WHITE);
                String label = allowed > 0 ? allowed + " ready" : "locked";
                FontMetrics fm = g.getFontMetrics();
                r.text(g, label, r.sx(x0 + areaW / 2), r.sy(-14 * IN), 0.5);
            }
        }

        // Center line: AUTO boundary between the red (columns A-C) and blue (D-F) halves.
        Stroke dashed = new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{8f, 6f}, 0f);
        g.setStroke(dashed);
        g.setColor(new Color(255, 255, 255, 40));
        g.draw(new Line2D.Double(r.sx(0), r.sy(HALF), r.sx(0), r.sy(-HALF)));

        // LOADING ZONES (tape outline) and GARDENS (tape strip).
        for (String a : new String[]{"red", "blue"}) {
            Color c = alliance(a);
            Rectangle2D lz = rect(r, loadingZone(a));
            g.setColor(alpha(c, 40));
            g.fill(lz);
            g.setColor(c);
            g.setStroke(new BasicStroke((float) Math.max(2, IN * r.scale())));
            g.draw(lz);
            g.setColor(alpha(c, 220));
            g.fill(rect(r, garden(a)));
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
        g.setColor(new Color(255, 255, 255, 150));
        label(g, r, "LOADING ZONE", (RED_LOADING_ZONE[0] + RED_LOADING_ZONE[1]) / 2, RED_LOADING_ZONE[3] + 2 * IN);
        label(g, r, "LOADING ZONE", (BLUE_LOADING_ZONE[0] + BLUE_LOADING_ZONE[1]) / 2, BLUE_LOADING_ZONE[2] - 4 * IN);
        label(g, r, "GARDEN", (RED_GARDEN[0] + RED_GARDEN[1]) / 2, RED_GARDEN[3] + 2 * IN);
        label(g, r, "GARDEN", (BLUE_GARDEN[0] + BLUE_GARDEN[1]) / 2, BLUE_GARDEN[2] - 4 * IN);

        // HIVE frame feet and Frame Foot A on the tiles (the legs and top bar are drawn overhead).
        g.setColor(FRAME);
        for (int sx : new int[]{-1, 1}) {
            double fx = sx * (FRAME_HALF_WIDTH - FRAME_FOOT_THICKNESS / 2);
            g.fill(new Rectangle2D.Double(r.sx(fx - FRAME_FOOT_THICKNESS / 2), r.sy(FRAME_HALF_DEPTH),
                    FRAME_FOOT_THICKNESS * r.scale(), 2 * FRAME_HALF_DEPTH * r.scale()));
            double ax0 = sx > 0 ? FOOT_A[0] : -FOOT_A[1], ay1 = sx > 0 ? FOOT_A[3] : -FOOT_A[2];
            g.fill(new Rectangle2D.Double(r.sx(ax0), r.sy(ay1), (FOOT_A[1] - FOOT_A[0]) * r.scale(), (FOOT_A[3] - FOOT_A[2]) * r.scale()));
        }
    }

    private static void label(Graphics2D g, FieldRenderer r, String text, double x, double y) {
        r.text(g, text, r.sx(x), r.sy(y), 0.5);
    }

    /** HIVES and FLOWERS - drawn over the robot, see-through, since robots pass under the HIVES. */
    static void paintOverhead(Graphics2D g, FieldRenderer r, BioBuzzSnapshot s) {
        g.setColor(alpha(FRAME, 160));
        g.setStroke(new BasicStroke(3f));
        g.draw(new Line2D.Double(r.sx(-12 * IN), r.sy(0), r.sx(12 * IN), r.sy(0)));
        // The legs lean in from the feet to the top bar.
        for (int sx : new int[]{-1, 1}) {
            for (int sy : new int[]{-1, 1}) {
                g.draw(new Line2D.Double(r.sx(sx * LEG_FOOT[0]), r.sy(sy * LEG_FOOT[1]), r.sx(sx * LEG_TOP[0]), r.sy(sy * LEG_TOP[1])));
            }
        }

        for (BioBuzzSnapshot.HiveState h : s.hives()) paintHive(g, r, h);
        for (BioBuzzSnapshot.FlowerState f : s.flowers()) paintFlower(g, r, f, s.flowersUnlocked());
    }

    private static void paintHive(Graphics2D g, FieldRenderer r, BioBuzzSnapshot.HiveState h) {
        Color c = alliance(h.alliance());
        // t = how far the far CELL is "up": +1 far up, -1 audience up (animated while tipping).
        double t = h.tiltDegrees() / 30.0;
        for (int side : new int[]{1, -1}) {
            double up = side * t; // +1 = this CELL faces up
            double inner = 5.4 * IN, outer = (up > 0 ? 19.25 : 24) * IN;
            Rectangle2D cell = new Rectangle2D.Double(r.sx(h.x() - MOUTH_HALF_WIDTH),
                    r.sy(side > 0 ? outer : -inner), 2 * MOUTH_HALF_WIDTH * r.scale(), (outer - inner) * r.scale());
            int fillAlpha = (int) (50 + 90 * Math.max(0, up));
            g.setColor(alpha(c, fillAlpha));
            g.fill(cell);
            g.setColor(alpha(c, up > 0 ? 255 : 120));
            g.setStroke(up > 0 ? new BasicStroke(2f)
                    : new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
            g.draw(cell);
            if (up > 0.5 && !h.tipping()) {
                // The opening is the outer edge: shoot at it from that side of the field.
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                double ey = side * outer;
                g.draw(new Line2D.Double(r.sx(h.x() - MOUTH_HALF_WIDTH + IN), r.sy(ey), r.sx(h.x() + MOUTH_HALF_WIDTH - IN), r.sy(ey)));
                // Contents, resting against the back wall.
                List<Integer> contents = h.contents();
                double d = 2.4 * IN * r.scale();
                for (int i = 0; i < contents.size(); i++) {
                    double px = h.x() + ((i % 6) - 2.5) * 3.0 * IN, py = side * (inner + 2 * IN + (i / 6) * 3 * IN);
                    g.setColor(new Color(contents.get(i)));
                    g.fill(new Ellipse2D.Double(r.sx(px) - d / 2, r.sy(py) - d / 2, d, d));
                }
            }
            // AprilTag cluster under each CELL.
            int first = tagFor(h.alliance(), side);
            double ty = side * (inner + outer) / 2;
            g.setColor(new Color(20, 20, 20, 170));
            g.fill(new Rectangle2D.Double(r.sx(h.x() - 3.5 * IN), r.sy(ty + 1 * IN), 7 * IN * r.scale(), 2 * IN * r.scale()));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 8));
            g.setColor(new Color(255, 255, 255, 200));
            label(g, r, first + "-" + (first + 3), h.x(), ty - 0.6 * IN);
        }
        // Weight in the upward CELL vs. the tip threshold, and tips so far.
        double barW = 16 * IN * r.scale(), barH = 4;
        double bx = r.sx(h.x()) - barW / 2, by = r.sy(0) + (h.alliance().equals("red") ? 10 : 10);
        g.setColor(new Color(0, 0, 0, 140));
        g.fill(new RoundRectangle2D.Double(bx, by, barW, barH, 3, 3));
        g.setColor(h.tipping() ? Color.WHITE : c);
        g.fill(new RoundRectangle2D.Double(bx, by, barW * Math.min(1, h.fill()), barH, 3, 3));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        g.setColor(Color.WHITE);
        String tips = (h.tipping() ? "TIPPING" : "TIPS " + (h.tipsAuto() + h.tipsTeleop()));
        r.text(g, tips, r.sx(h.x()), by - 3, 0.5);
    }

    private static int tagFor(String alliance, int side) {
        for (int[] t : TAG_CLUSTERS) {
            if ((t[1] < 0) == alliance.equals("red") && t[2] == side) return t[0];
        }
        return 0;
    }

    private static void paintFlower(Graphics2D g, FieldRenderer r, BioBuzzSnapshot.FlowerState f, boolean unlocked) {
        double ax = r.sx(f.axisX()), ay = r.sy(f.axisY());
        double ro = 2.6 * IN * r.scale(), ri = FLOWER_OPENING_RADIUS * r.scale();
        Color owner = f.owner() != null ? alliance(f.owner()) : null;
        g.setColor(FLOWER_RING);
        g.fill(new Ellipse2D.Double(ax - ro, ay - ro, 2 * ro, 2 * ro));
        g.setColor(new Color(0x2A2C30));
        g.fill(new Ellipse2D.Double(ax - ri, ay - ri, 2 * ri, 2 * ri));
        if (owner != null) {
            g.setColor(owner);
            g.setStroke(new BasicStroke(3f));
            g.draw(new Ellipse2D.Double(ax - ro - 2, ay - ro - 2, 2 * ro + 4, 2 * ro + 4));
        }
        // Stack, bottom to top, drawn as a tube running into the field (it's vertical in reality).
        List<Integer> stack = f.stack();
        double d = 2.0 * IN * r.scale();
        if (!stack.isEmpty()) {
            double a0 = 4.2 * IN, a1 = (4.2 + (stack.size() - 1) * 2.2) * IN;
            double x0 = r.sx(f.axisX() + f.nx() * a0), y0 = r.sy(f.axisY() + f.ny() * a0);
            double x1 = r.sx(f.axisX() + f.nx() * a1), y1 = r.sy(f.axisY() + f.ny() * a1);
            g.setColor(new Color(0x2E7D32));
            g.setStroke(new BasicStroke((float) (d + 5), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(x0, y0, x1, y1));
        }
        for (int i = 0; i < stack.size(); i++) {
            double off = (4.2 + i * 2.2) * IN;
            double px = f.axisX() + f.nx() * off, py = f.axisY() + f.ny() * off;
            g.setColor(new Color(stack.get(i)));
            g.fill(new Ellipse2D.Double(r.sx(px) - d / 2, r.sy(py) - d / 2, d, d));
            g.setColor(new Color(0, 0, 0, 120));
            g.setStroke(new BasicStroke(1f));
            g.draw(new Ellipse2D.Double(r.sx(px) - d / 2, r.sy(py) - d / 2, d, d));
        }
    }
}
