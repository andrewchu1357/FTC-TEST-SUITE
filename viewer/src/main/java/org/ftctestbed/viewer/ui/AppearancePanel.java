package org.ftctestbed.viewer.ui;

import org.ftctestbed.viewer.platform.Os;

import javax.swing.ButtonGroup;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Setup > Appearance, laid out like Windows 11's Personalization page: the theme as preview
 * tiles, then transparency effects, text size and the frame rate as settings cards.
 */
public final class AppearancePanel extends JPanel {
    private final List<Tile> tiles = new ArrayList<>();

    public AppearancePanel() {
        setLayout(new Ui.StackLayout(8));
        setOpaque(false);

        JPanel grid = new JPanel(new GridLayout(0, 3, 10, 10));
        grid.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        tiles.add(new Tile(null));
        for (Theme t : Theme.values()) tiles.add(new Tile(t));
        for (Tile t : tiles) {
            group.add(t);
            grid.add(t);
        }
        JPanel theme = new JPanel(new BorderLayout(0, 10));
        theme.setOpaque(false);
        theme.add(Ui.note(Os.WINDOWS
                        ? "Automatic follows Windows: Fluent Light or Fluent Dark, as apps are set in Settings > Personalization > Colors, "
                        + "and High Contrast when a contrast theme is on. High Contrast is the easiest to read."
                        : "Automatic follows " + Os.system() + ": Fluent Light by day, Fluent Dark at night. High Contrast is the easiest to read."),
                BorderLayout.NORTH);
        theme.add(grid, BorderLayout.CENTER);
        add(Ui.heading("Theme"));
        add(Ui.card(null, theme));

        add(spacer());
        add(Ui.heading("Effects and text"));
        JComboBox<Appearance.Transparency> transparency = new JComboBox<>(Appearance.Transparency.values());
        transparency.setSelectedItem(Appearance.transparency());
        transparency.addActionListener(e -> Appearance.setTransparency((Appearance.Transparency) transparency.getSelectedItem()));
        transparency.getAccessibleContext().setAccessibleName("Transparency effects");
        add(Ui.settingsCard(Icons.EYE, "Transparency effects",
                "Menus and the open navigation pane show the window through them, blurred. Automatic follows "
                        + (Os.WINDOWS ? "Transparency effects in Windows' settings." : "Reduce transparency in macOS."),
                transparency));

        SelectorBar size = new SelectorBar(FluentButton.Size.SMALL, false);
        for (int i = 0; i < Appearance.TEXT_SIZES.length; i++) size.add(Appearance.TEXT_SIZES[i], null, "Text size: " + Appearance.TEXT_SIZES[i]);
        size.select(Appearance.textSize());
        size.addActionListener(e -> Appearance.setTextSize(size.selectedIndex()));
        size.getAccessibleContext().setAccessibleName("Text size");
        add(Ui.settingsCard(Icons.TEXT_SIZE, "Text size", "Every label and list, the telemetry and the code editor.", size));

        add(spacer());
        add(Ui.heading("Performance"));
        JComboBox<Appearance.FrameRate> rate = new JComboBox<>(Appearance.FrameRate.values());
        rate.setSelectedItem(Appearance.frameRate());
        rate.addActionListener(e -> Appearance.setFrameRate((Appearance.FrameRate) rate.getSelectedItem()));
        rate.getAccessibleContext().setAccessibleName("Frame rate");
        add(Ui.settingsCard(Icons.SPEED, "Frame rate",
                "Efficient draws the field 30 times a second, about half the work. Automatic uses it on battery or "
                        + (Os.WINDOWS ? "when Battery saver is on." : "in Low Power Mode."),
                rate));
        refresh();
    }

    // Listens only while on screen: a listener left in Appearance's static list would keep a
    // closed window (and its simulation and 3D scene) in memory after every reload.
    private final Runnable listener = this::refresh;

    @Override
    public void addNotify() {
        super.addNotify();
        Appearance.addListener(listener);
        refresh();
    }

    @Override
    public void removeNotify() {
        Appearance.removeListener(listener);
        super.removeNotify();
    }

    private static JComponent spacer() {
        JPanel s = new JPanel();
        s.setOpaque(false);
        s.setPreferredSize(new Dimension(1, 6));
        return s;
    }

    private void refresh() {
        for (Tile t : tiles) t.setSelected(t.theme == Appearance.choice());
        repaint();
    }

    /** A theme as a little picture of a window in its colors, with its name. */
    private static final class Tile extends JToggleButton {
        final Theme theme; // null: Automatic

        Tile(Theme theme) {
            super(theme == null ? "Automatic" : theme.label);
            this.theme = theme;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRequestFocusEnabled(false);
            setToolTipText(theme == null ? "Light or dark to match " + Os.system() : theme.label + (theme.dark ? " (dark)" : " (light)"));
            addActionListener(e -> Appearance.setTheme(theme));
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(140, 70 + fm.getHeight() + 8);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Theme ui = Appearance.theme();
            int w = getWidth(), pw = w - 8, ph = 64;
            if (theme == null) {
                // Half light, half dark.
                drawPreview(g, Theme.FLUENT_LIGHT, 4, 4, pw, ph);
                Graphics2D right = (Graphics2D) g.create();
                right.clipRect(4 + pw / 2, 0, pw, ph + 8);
                drawPreview(right, Theme.FLUENT_DARK, 4, 4, pw, ph);
                right.dispose();
            } else {
                drawPreview(g, theme, 4, 4, pw, ph);
            }
            if (isSelected()) {
                g.setStroke(new BasicStroke(2f));
                g.setColor(ui.highContrast() ? ui.palette.warn() : ui.palette.accent());
                g.draw(new RoundRectangle2D.Double(2, 2, w - 4, ph + 4, Fluent.OVERLAY_ARC, Fluent.OVERLAY_ARC));
                // The check badge in the corner, as Windows marks the chosen theme.
                Color badge = ui.highContrast() ? ui.palette.warn() : ui.palette.accent();
                g.setColor(badge);
                g.fill(new Ellipse2D.Double(w - 24, ph - 16, 18, 18));
                Icons.CHECK.icon(12, Contrast.textOn(badge)).paintIcon(this, g, w - 21, ph - 13);
            }
            if (FocusRing.shows(this)) Fluent.focus(g, 2, 2, w - 4, ph + 4, Fluent.OVERLAY_ARC);
            g.setFont(getFont().deriveFont(isSelected() ? Font.BOLD : Font.PLAIN));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(ui.palette.text());
            String label = getText();
            g.drawString(label, (w - fm.stringWidth(label)) / 2, ph + 12 + fm.getAscent());
            g.dispose();
        }

        /** A Fluent window in theme t's colors: Mica with the rail, the content layer, a card and an accent button. */
        private static void drawPreview(Graphics2D g, Theme t, int x, int y, int w, int h) {
            Theme.Palette p = t.palette;
            RoundRectangle2D win = new RoundRectangle2D.Double(x, y, w, h, Fluent.OVERLAY_ARC, Fluent.OVERLAY_ARC);
            g.setColor(Fluent.mica(t));
            g.fill(win);
            // The rail's icons and the selected one's pill.
            g.setColor(p.secondary());
            for (int i = 0; i < 3; i++) g.fill(new RoundRectangle2D.Double(x + 6, y + 16 + i * 10, 6, 6, 2, 2));
            g.setColor(p.accent());
            g.fill(new RoundRectangle2D.Double(x + 2.5, y + 16, 2, 6, 2, 2));
            // The content layer, rounded at its top left.
            Graphics2D c = (Graphics2D) g.create();
            c.clip(win);
            c.setColor(Fluent.layer(t));
            c.fill(new RoundRectangle2D.Double(x + 18, y + 10, w, h, 10, 10));
            c.setColor(p.field());
            c.fillRect(x + 18, y + 20, (int) (w * 0.45), h);
            // The Driver Station pane: a card with lines of text and the accent button.
            c.setColor(Fluent.card(t));
            c.fill(new RoundRectangle2D.Double(x + w * 0.66, y + 24, w * 0.3, h - 30, 6, 6));
            c.setColor(p.text());
            c.fill(new RoundRectangle2D.Double(x + w * 0.69, y + 29, w * 0.2, 3, 2, 2));
            c.setColor(p.secondary());
            c.fill(new RoundRectangle2D.Double(x + w * 0.69, y + 36, w * 0.15, 3, 2, 2));
            c.setColor(p.accent());
            c.fill(new RoundRectangle2D.Double(x + w * 0.69, y + h - 17, w * 0.24, 8, 4, 4));
            Color[] dots = {p.go(), p.stop(), p.warn()};
            for (int i = 0; i < dots.length; i++) {
                c.setColor(dots[i]);
                c.fill(new Ellipse2D.Double(x + 24 + i * 10, y + h - 14, 7, 7));
            }
            c.dispose();
            g.setColor(Fluent.stroke(t).getAlpha() == 255 ? Fluent.stroke(t) : Contrast.alpha(Color.GRAY, 0.5));
            g.setStroke(new BasicStroke(1f));
            g.draw(win);
        }
    }
}
