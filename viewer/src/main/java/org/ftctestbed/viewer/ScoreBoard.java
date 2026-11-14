package org.ftctestbed.viewer;

import org.ftctestbed.sim.game.SeasonSnapshot;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

/** Red and blue scores with their breakdowns and ranking points. */
final class ScoreBoard extends JComponent {
    private static final Color RED = new Color(0xE53935);
    private static final Color BLUE = new Color(0x1E88E5);
    private SeasonSnapshot season;
    private String ourAlliance;

    void update(SeasonSnapshot s, String ourAlliance) {
        if (java.util.Objects.equals(ourAlliance, this.ourAlliance) && org.ftctestbed.sim.Snapshot.sameValues(s, season)) return;
        this.season = s;
        this.ourAlliance = ourAlliance;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(400, 230);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        org.ftctestbed.viewer.ui.Theme.Palette p = org.ftctestbed.viewer.ui.Appearance.palette();
        double ts = org.ftctestbed.viewer.ui.Appearance.textScale();
        if (season == null) {
            g.setColor(p.secondary());
            g.drawString("No game on this field (choose a season field to score).", 10, 20);
            g.dispose();
            return;
        }
        int w = getWidth(), colW = (w - 18) / 2;
        String[] alliances = {"red", "blue"};
        for (int i = 0; i < 2; i++) {
            String a = alliances[i];
            int x = 6 + i * (colW + 6);
            Color c = i == 0 ? RED : BLUE;
            g.setColor(c);
            g.fill(new RoundRectangle2D.Double(x, 6, colW, 46, 10, 10));
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            String score = String.valueOf(season.score(a));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(score, x + colW - fm.stringWidth(score) - 10, 41);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.drawString(a.toUpperCase() + (a.equals(ourAlliance) ? "  (our robot)" : ""), x + 10, 24);

            g.setColor(p.text());
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, (int) Math.round(11 * ts)));
            int lineH = (int) Math.round(14 * ts);
            int y = 68;
            for (String line : season.breakdown(a)) {
                // "LABEL   n x  p =  t" -> label left, total right; skip zero lines to save space
                String[] parts = line.trim().split("\\s{2,}|\\s+(?=\\d+ x)");
                String total = line.substring(line.lastIndexOf(' ') + 1);
                if (total.equals("0")) continue;
                String label = line.substring(0, Math.min(line.length(), 20)).trim();
                String count = line.contains(" x ") ? line.substring(20, line.indexOf(" x ")).trim() + "× " : "";
                g.drawString(count + label, x + 2, y);
                FontMetrics m = g.getFontMetrics();
                g.drawString(total, x + colW - m.stringWidth(total) - 4, y);
                y += lineH;
            }
            y += 6;
            List<String> rps = season.rankingPoints(a);
            for (String rp : rps) {
                g.setColor(rp.startsWith("✓") ? org.ftctestbed.viewer.ui.Contrast.readableOn(p.go(), p.window(), 4.5) : p.secondary());
                g.drawString(rp, x + 2, y);
                y += lineH;
            }
        }
        g.dispose();
    }
}
