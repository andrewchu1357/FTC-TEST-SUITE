package org.ftctestbed.viewer.ui;

import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;

/**
 * A Fluent button with 4 px corners. ACCENT is filled with its tint (the main action, like
 * Windows' accent button), STANDARD is a raised control with Fluent's elevation border, SUBTLE
 * shows a fill only under the pointer (the navigation pane), GLASS is SUBTLE with a capsule
 * fill, for the toolbar's Liquid Glass groups (see GlassGroup). Labels always meet
 * Contrast.TEXT on the fill; keyboard focus draws Fluent's two-tone focus ring.
 */
public class FluentButton extends JButton {
    public enum Style { ACCENT, STANDARD, SUBTLE, GLASS }

    public enum Size {
        SMALL(26, 0.92f), REGULAR(32, 1f), LARGE(38, 1.08f);

        final int height;
        final float font;

        Size(int height, float font) {
            this.height = height;
            this.font = font;
        }
    }

    /** Which palette color an ACCENT button is filled with. */
    public enum Tint { ACCENT, GO, STOP, WARN }

    /** Room around the button for the focus ring. */
    static final int RING = 3;

    private Style style;
    private Size size;
    private Tint tint;
    private Icons glyph;
    private boolean chevron;

    public FluentButton(String text, Icons glyph, Style style, Size size) {
        this(text, glyph, style, size, Tint.ACCENT);
    }

    public FluentButton(String text, Icons glyph, Style style, Size size, Tint tint) {
        super(text);
        this.glyph = glyph;
        this.style = style;
        this.size = size;
        this.tint = tint;
        setup(this);
    }

    /** Text and icon (null: none); an icon-only button gets its name from the tool tip for VoiceOver. */
    public void set(String text, Icons glyph) {
        this.glyph = glyph;
        setText(text);
        revalidate();
        repaint();
    }

    public void setStyle(Style style, Tint tint) {
        if (this.style == style && this.tint == tint) return;
        this.style = style;
        this.tint = tint;
        setFont(fontFor(size, style));
        repaint();
    }

    /** A chevron after the label: the button opens a menu (Fluent's drop-down button). */
    public void setChevron(boolean on) {
        chevron = on;
        revalidate();
    }

    public Icons glyph() {
        return glyph;
    }

    @Override
    public void setToolTipText(String text) {
        super.setToolTipText(text);
        if ((getText() == null || getText().isEmpty()) && text != null) getAccessibleContext().setAccessibleName(plain(text));
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (size != null) setup(this);
    }

    private void setup(AbstractButton b) {
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setRolloverEnabled(true);
        // Tab reaches it; a click doesn't take the keyboard away from the robot.
        b.setRequestFocusEnabled(false);
        b.setFont(fontFor(size, style));
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = measure(this, glyph, size);
        return chevron ? new Dimension(d.width + 16, d.height) : d;
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        paint(this, (Graphics2D) g, glyph, style, size, color(tint), chevron);
    }

    // ------------------------------------------------------------------ shared with SelectorBar and NavRail

    static Font fontFor(Size size, Style style) {
        Font base = UIManager.getFont("defaultFont");
        if (base == null) base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        return base.deriveFont(style == Style.ACCENT && size == Size.LARGE ? Font.BOLD : Font.PLAIN, base.getSize2D() * size.font);
    }

    static Color color(Tint t) {
        Theme.Palette p = Appearance.palette();
        return switch (t) {
            case ACCENT -> p.accent();
            case GO -> p.go();
            case STOP -> p.stop();
            case WARN -> p.warn();
        };
    }

    static Dimension measure(AbstractButton b, Icons glyph, Size size) {
        FontMetrics fm = b.getFontMetrics(b.getFont());
        String text = b.getText() == null ? "" : b.getText();
        int icon = glyph != null ? iconSize(size) : 0;
        int content = icon + (text.isEmpty() ? 0 : fm.stringWidth(text) + (icon > 0 ? 8 : 0));
        int h = Math.max(size.height, fm.getHeight() + 8);
        int pad = text.isEmpty() ? (h - icon) / 2 : size == Size.LARGE ? 16 : size == Size.SMALL ? 9 : 12;
        return new Dimension(content + 2 * pad + 2 * RING, h + 2 * RING);
    }

    static int iconSize(Size size) {
        return size == Size.LARGE ? 17 : size == Size.SMALL ? 14 : 16;
    }

    /** Paints a Fluent button: fill and border for its style and state, focus ring, icon, label and chevron. */
    static void paint(AbstractButton b, Graphics2D g0, Icons glyph, Style style, Size size, Color tint, boolean chevron) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        Theme theme = Appearance.theme();
        Theme.Palette p = theme.palette;
        ButtonModel m = b.getModel();
        boolean enabled = b.isEnabled(), hover = enabled && m.isRollover(), pressed = enabled && m.isArmed() && m.isPressed();
        int w = b.getWidth(), h = b.getHeight();
        double bw = w - 2 * RING, bh = h - 2 * RING;
        double arc = style == Style.GLASS ? bh - 1 : Fluent.CONTROL_ARC;
        Shape body = new RoundRectangle2D.Double(RING + 0.5, RING + 0.5, bw - 1, bh - 1, arc, arc);

        Color fg;
        switch (style) {
            case ACCENT -> {
                if (enabled) {
                    fg = Contrast.textOn(tint);
                    Color base = Contrast.readableBehind(tint, fg, Contrast.TEXT);
                    g.setColor(pressed ? Contrast.toward(base, fg, 0.18) : hover ? Contrast.toward(base, fg, 0.09) : base);
                    if (pressed) fg = Contrast.alpha(fg, 0.8);
                } else {
                    fg = Contrast.alpha(p.text(), 0.45);
                    g.setColor(Contrast.alpha(p.text(), theme.dark ? 0.16 : 0.2));
                }
                g.fill(body);
                if (enabled) Fluent.elevation(g, body, theme, true);
            }
            case STANDARD -> {
                fg = pressed ? p.secondary() : p.text();
                g.setColor(Fluent.controlFill(theme, hover, pressed));
                g.fill(body);
                Fluent.elevation(g, body, theme, false);
            }
            case GLASS -> {
                fg = pressed ? p.secondary() : p.text();
                // On glass the pointer shows as a brighter capsule; pressing dims it.
                Color fill = theme.highContrast() ? Fluent.subtleFill(theme, hover, pressed)
                        : pressed ? Contrast.alpha(theme.dark ? Color.WHITE : Color.BLACK, theme.dark ? 0.06 : 0.07)
                        : hover ? Contrast.alpha(Color.WHITE, theme.dark ? 0.13 : 0.75) : new Color(0, 0, 0, 0);
                if (fill.getAlpha() > 0) {
                    g.setColor(fill);
                    g.fill(body);
                }
            }
            default -> {
                fg = pressed ? p.secondary() : p.text();
                Color fill = Fluent.subtleFill(theme, hover, pressed);
                if (fill.getAlpha() > 0) {
                    g.setColor(fill);
                    g.fill(body);
                }
            }
        }
        if (FocusRing.shows(b)) Fluent.focus(g, RING, RING, bw, bh, style == Style.GLASS ? bh : Fluent.CONTROL_ARC);
        if (!enabled && style != Style.ACCENT) fg = Contrast.alpha(fg, 0.45);

        String text = b.getText() == null ? "" : b.getText();
        FontMetrics fm = g.getFontMetrics(b.getFont());
        int icon = glyph != null ? iconSize(size) : 0;
        int textW = text.isEmpty() ? 0 : fm.stringWidth(text);
        int content = icon + textW + (icon > 0 && textW > 0 ? 8 : 0);
        int x = (w - content - (chevron ? 16 : 0)) / 2;
        if (glyph != null) {
            glyph.icon(icon, fg).paintIcon(b, g, x, (h - icon) / 2);
            x += icon + 8;
        }
        if (!text.isEmpty()) {
            g.setFont(b.getFont());
            g.setColor(fg);
            g.drawString(text, x, (h - fm.getHeight()) / 2 + fm.getAscent());
            x += textW;
        }
        if (chevron) Icons.CHEVRON_DOWN.icon(12, Contrast.alpha(fg, fg.getAlpha() / 255.0 * 0.8)).paintIcon(b, g, x + 6, (h - 12) / 2 + 1);
        g.dispose();
    }

    private static String plain(String html) {
        return html.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }
}
