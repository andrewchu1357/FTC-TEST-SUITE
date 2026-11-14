package org.ftctestbed.viewer.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Supplier;

/** Small pieces the Fluent pages and panes are built from. */
public final class Ui {
    private Ui() {}

    /** A section heading in Fluent's "body strong" style: bold, in the text color, sentence case. */
    public static JLabel heading(String text) {
        JLabel l = new JLabel(text) {
            @Override
            public void updateUI() {
                super.updateUI();
                Font f = getFont();
                setFont(new javax.swing.plaf.FontUIResource(f.deriveFont(Font.BOLD)));
                setForeground(Appearance.palette().text());
            }
        };
        l.updateUI();
        return l;
    }

    /** Secondary text (an explanation under a control). */
    public static JLabel note(String text) {
        JLabel l = new JLabel("<html>" + text + "</html>") {
            @Override
            public void updateUI() {
                super.updateUI();
                setFont(smaller(getFont(), Font.PLAIN));
                setForeground(Appearance.palette().secondary());
            }
        };
        l.updateUI();
        return l;
    }

    /**
     * The label font a size smaller, as a UI resource: the next theme or text size change replaces
     * it with the new label font (a plain Font would stay, and shrink again each time).
     */
    private static Font smaller(Font f, int style) {
        return new javax.swing.plaf.FontUIResource(f.deriveFont(style, f.getSize2D() * 0.92f));
    }

    /** A plain label in the text color (kept in step with the theme). */
    public static JLabel label(String text) {
        JLabel l = new JLabel(text) {
            @Override
            public void updateUI() {
                super.updateUI();
                setForeground(Appearance.palette().text());
            }
        };
        l.updateUI();
        return l;
    }

    /** Rows of "Label  [control]" with the labels in one column; each label is linked to its control for VoiceOver. */
    public static JPanel form(Object... labelsAndControls) {
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        for (int i = 0; i + 1 < labelsAndControls.length; i += 2) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = i / 2;
            c.insets = new Insets(3, 0, 3, 8);
            c.anchor = GridBagConstraints.WEST;
            String text = (String) labelsAndControls[i];
            JComponent control = (JComponent) labelsAndControls[i + 1];
            if (text != null && !text.isEmpty()) {
                JLabel l = label(text);
                l.setLabelFor(control);
                c.gridx = 0;
                form.add(l, c);
            }
            c.gridx = text == null || text.isEmpty() ? 0 : 1;
            c.gridwidth = text == null || text.isEmpty() ? 2 : 1;
            c.weightx = 1;
            c.insets = new Insets(3, 0, 3, 0);
            c.fill = GridBagConstraints.HORIZONTAL;
            form.add(control, c);
        }
        return form;
    }

    /** A card (a group of controls on the layer) with an optional heading. */
    public static Surface card(String heading, JComponent body) {
        Surface card = new Surface(Fluent.Material.CARD, Fluent.OVERLAY_ARC, 12, new BorderLayout(0, 8));
        if (heading != null) card.add(heading(heading), BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    /**
     * Fluent's error InfoBar: body beside a warning sign on a red-tinted card (Windows'
     * critical background), readable in every theme.
     */
    public static JPanel alert(JComponent body) {
        JPanel banner = new JPanel(new BorderLayout(10, 0)) {
            @Override
            protected void paintComponent(java.awt.Graphics g0) {
                java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                Theme t = Appearance.theme();
                java.awt.geom.RoundRectangle2D r = new java.awt.geom.RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1,
                        Fluent.CONTROL_ARC, Fluent.CONTROL_ARC);
                g.setColor(t.highContrast() ? java.awt.Color.BLACK : Contrast.over(Contrast.alpha(t.palette.stop(), t.dark ? 0.16 : 0.1), Fluent.card(t)));
                g.fill(r);
                g.setColor(t.highContrast() ? t.palette.stop() : Fluent.stroke(t));
                g.setStroke(new java.awt.BasicStroke(t.highContrast() ? 2f : 1f));
                g.draw(r);
                g.dispose();
            }
        };
        banner.setOpaque(false);
        banner.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JLabel icon = new JLabel(Icons.WARNING.icon(16, Appearance.palette().stop())) {
            @Override
            public void updateUI() {
                super.updateUI();
                setIcon(Icons.WARNING.icon(16, Appearance.palette().stop()));
            }
        };
        icon.setVerticalAlignment(JLabel.TOP);
        banner.add(icon, BorderLayout.WEST);
        banner.add(body, BorderLayout.CENTER);
        return banner;
    }

    /**
     * A row of settings as Windows 11's Settings app shows them: an icon, a title with a line of
     * explanation under it, and the control at the right. The title is linked to the control
     * for VoiceOver.
     */
    public static Surface settingsCard(Icons glyph, String title, String description, JComponent control) {
        Surface card = new Surface(Fluent.Material.CARD, Fluent.OVERLAY_ARC, 0, new GridBagLayout());
        card.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 14));
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        if (glyph != null) {
            JLabel icon = new JLabel(glyph.icon(18)) {
                @Override
                public void updateUI() {
                    super.updateUI();
                    setForeground(Appearance.palette().text());
                }
            };
            icon.updateUI();
            c.insets = new Insets(0, 0, 0, 16);
            card.add(icon, c);
        }
        JPanel words = new JPanel(new java.awt.GridLayout(0, 1, 0, 1));
        words.setOpaque(false);
        JLabel name = label(title);
        name.setLabelFor(control);
        words.add(name);
        if (description != null) words.add(note(description));
        c.gridx = glyph != null ? 1 : 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 12);
        card.add(words, c);
        if (control != null) {
            c.gridx++;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            c.anchor = GridBagConstraints.EAST;
            c.insets = new Insets(0, 0, 0, 0);
            card.add(control, c);
            if (control.getAccessibleContext().getAccessibleName() == null) control.getAccessibleContext().setAccessibleName(title);
        }
        return card;
    }

    /** Read-only text on the card or layer under it (telemetry, console), in font scaled to the text size. */
    public static JTextArea text(Font font) {
        return text(font, null);
    }

    /** As text(font), showing placeholder (in the secondary color) while it's empty. The font follows the text size. */
    public static JTextArea text(Font font, String placeholder) {
        JTextArea t = new JTextArea() {
            @Override
            public void updateUI() {
                super.updateUI();
                if (font != null) setFont(font.deriveFont((float) (font.getSize2D() * Appearance.textScale())));
                setForeground(Appearance.palette().text());
                // New text doesn't move the caret, so an update never scrolls the view (to the end of a
                // long line). Set again after every look change, which brings a new caret.
                if (getCaret() instanceof javax.swing.text.DefaultCaret c) c.setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
                setSelectionColor(Contrast.alpha(Appearance.palette().accent(), 0.35));
            }

            @Override
            protected void paintComponent(java.awt.Graphics g) {
                super.paintComponent(g);
                if (placeholder != null && getDocument().getLength() == 0) {
                    java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    Font f = javax.swing.UIManager.getFont("defaultFont");
                    g2.setFont(f != null ? f : getFont());
                    g2.setColor(Appearance.palette().secondary());
                    g2.drawString(placeholder, getInsets().left + 2, getInsets().top + g2.getFontMetrics().getAscent() + 2);
                    g2.dispose();
                }
            }
        };
        t.setEditable(false);
        t.setOpaque(false);
        // Read-only: Tab moves on to the next control instead of staying in the text.
        t.setFocusTraversalKeys(java.awt.KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
        t.setFocusTraversalKeys(java.awt.KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
        t.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        t.updateUI();
        return t;
    }

    /** A scroll pane without a frame that shows the surface under it. */
    public static JScrollPane scroll(Component view) {
        JScrollPane s = new JScrollPane(view, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        clearScroll(s);
        return s;
    }

    static void clearScroll(JScrollPane s) {
        s.setOpaque(false);
        s.getViewport().setOpaque(false);
        s.setBorder(BorderFactory.createEmptyBorder());
        s.setViewportBorder(null);
        s.getVerticalScrollBar().setOpaque(false);
        s.getHorizontalScrollBar().setOpaque(false);
        s.getVerticalScrollBar().setUnitIncrement(16);
    }

    /**
     * Makes the panels and scroll panes inside c see-through, so a page built for a plain window
     * sits on the layer or card; fields, lists and tables keep their own backgrounds.
     */
    public static void clear(Container c) {
        if (c instanceof JPanel p && !(p instanceof Surface)) p.setOpaque(false);
        if (c instanceof JScrollPane s && !(s.getViewport().getView() instanceof JList<?>)
                && !(s.getViewport().getView() instanceof javax.swing.JTable) && !(s.getViewport().getView() instanceof javax.swing.text.JTextComponent t && t.isEditable())) {
            clearScroll(s);
        }
        if (c instanceof JViewport v) v.setOpaque(false);
        for (Component k : c.getComponents()) if (k instanceof Container kc) clear(kc);
    }

    /** c no wider than max, at the left of a wider space (a page of settings reads best not too wide). */
    public static JPanel maxWidth(JComponent c, int max) {
        JPanel p = new JPanel(new java.awt.LayoutManager() {
            @Override public void addLayoutComponent(String name, Component comp) { }
            @Override public void removeLayoutComponent(Component comp) { }

            @Override
            public java.awt.Dimension preferredLayoutSize(Container parent) {
                java.awt.Dimension d = c.getPreferredSize();
                return new java.awt.Dimension(Math.min(max, d.width), d.height);
            }

            @Override
            public java.awt.Dimension minimumLayoutSize(Container parent) {
                return c.getMinimumSize();
            }

            @Override
            public void layoutContainer(Container parent) {
                Insets in = parent.getInsets();
                int w = Math.min(max, parent.getWidth() - in.left - in.right);
                c.setBounds(in.left, in.top, w, parent.getHeight() - in.top - in.bottom);
            }
        });
        p.setOpaque(false);
        p.add(c);
        return p;
    }

    /** A see-through column: each child at its preferred height, as wide as the column, gap pixels apart. */
    public static JPanel stack(int gap) {
        JPanel p = new JPanel(new StackLayout(gap));
        p.setOpaque(false);
        return p;
    }

    /** Lays children out top to bottom at their preferred heights and the full width. */
    public static final class StackLayout implements java.awt.LayoutManager {
        private final int gap;

        public StackLayout(int gap) {
            this.gap = gap;
        }

        @Override public void addLayoutComponent(String name, Component c) { }
        @Override public void removeLayoutComponent(Component c) { }

        @Override
        public java.awt.Dimension preferredLayoutSize(Container parent) {
            return size(parent, false);
        }

        @Override
        public java.awt.Dimension minimumLayoutSize(Container parent) {
            return size(parent, true);
        }

        private java.awt.Dimension size(Container parent, boolean min) {
            Insets in = parent.getInsets();
            int w = 0, h = 0, n = 0;
            for (Component c : parent.getComponents()) {
                if (!c.isVisible()) continue;
                java.awt.Dimension d = min ? c.getMinimumSize() : c.getPreferredSize();
                w = Math.max(w, d.width);
                h += c.getPreferredSize().height;
                n++;
            }
            return new java.awt.Dimension(w + in.left + in.right, h + Math.max(0, n - 1) * gap + in.top + in.bottom);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            int y = in.top, w = parent.getWidth() - in.left - in.right;
            for (Component c : parent.getComponents()) {
                if (!c.isVisible()) continue;
                int h = c.getPreferredSize().height;
                c.setBounds(in.left, y, w, h);
                y += h + gap;
            }
        }
    }

    /** A command bar drop-down button: a label with a chevron that opens a menu under itself. */
    public static FluentButton menuButton(String text, Icons glyph, String toolTip, Supplier<JPopupMenu> menu) {
        FluentButton b = new FluentButton(text, glyph, FluentButton.Style.GLASS, FluentButton.Size.REGULAR);
        b.setChevron(true);
        b.setToolTipText(toolTip);
        b.addActionListener(e -> {
            JPopupMenu m = menu.get();
            m.show(b, 0, b.getHeight());
        });
        return b;
    }
}
