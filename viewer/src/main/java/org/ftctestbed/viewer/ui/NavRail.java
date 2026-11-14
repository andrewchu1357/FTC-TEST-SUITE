package org.ftctestbed.viewer.ui;

import javax.swing.AbstractAction;
import javax.swing.ButtonGroup;
import javax.swing.ButtonModel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Fluent's navigation view, in its compact form: a column of icons down the left of the window
 * (the pages), settings at the bottom, the chosen page marked with an accent pill. The menu
 * button at the top opens it wider with every page's name, on acrylic over the content (see
 * AppShell); picking a page or clicking elsewhere closes it again. Up and down arrows move the
 * focus between pages and Space opens one; each page is a toggle button VoiceOver reads by name.
 */
public final class NavRail extends JPanel {
    public static final int COMPACT = 48, EXPANDED = 280;
    private static final int ITEM_H = 38, GAP = 4;

    private final List<Item> items = new ArrayList<>();
    private final List<Boolean> footer = new ArrayList<>();
    private final List<ActionListener> listeners = new ArrayList<>();
    private final ButtonGroup group = new ButtonGroup();
    private final Item menu;
    private Runnable onMenu = () -> {};
    private boolean expanded;
    private BufferedImage backdrop;
    private int selected = -1;

    public NavRail() {
        super(null);
        setOpaque(false);
        menu = new Item("Navigation", Icons.MENU, false);
        menu.setToolTipText("Show the pages' names");
        menu.addActionListener(e -> onMenu.run());
        add(menu);
        getAccessibleContext().setAccessibleName("Pages");
    }

    /** Adds a page (footer: at the bottom, like Settings); the first one added starts selected. */
    public JToggleButton addItem(String label, Icons glyph, String toolTip, boolean atFooter) {
        Item it = new Item(label, glyph, true);
        int index = items.size();
        it.setToolTipText(toolTip);
        it.addActionListener(e -> select(index));
        it.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "next");
        it.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "previous");
        it.getActionMap().put("next", step(+1));
        it.getActionMap().put("previous", step(-1));
        group.add(it);
        items.add(it);
        footer.add(atFooter);
        add(it);
        if (index == 0) select(0);
        return it;
    }

    /** Moves the focus to the next or previous page (Space or Return opens it, as in Windows). */
    private AbstractAction step(int d) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int n = items.size(), at = items.indexOf(e.getSource() instanceof Item i ? i : null);
                items.get(((at < 0 ? selected : at) + d + n) % n).requestFocusInWindow();
            }
        };
    }

    /** What the menu button does (AppShell opens or closes the wide pane). */
    public void onMenu(Runnable r) {
        onMenu = r;
    }

    public void addActionListener(ActionListener l) {
        listeners.add(l);
    }

    /** Selects a page; listeners hear about it when the selection changes. */
    public void select(int index) {
        if (index < 0 || index >= items.size()) return;
        items.get(index).setSelected(true);
        if (index == selected) return;
        selected = index;
        repaint();
        ActionEvent e = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, items.get(index).getText());
        for (ActionListener l : listeners) l.actionPerformed(e);
    }

    public int selectedIndex() {
        return selected;
    }

    public boolean expanded() {
        return expanded;
    }

    /** Wide with names (on the acrylic backdrop, or solid if null) or compact. */
    void setExpanded(boolean e, BufferedImage backdrop) {
        expanded = e;
        this.backdrop = e ? backdrop : null;
        menu.setToolTipText(e ? "Hide the pages' names (Esc)" : "Show the pages' names");
        menu.getAccessibleContext().setAccessibleName(e ? "Close navigation" : "Open navigation");
        revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(expanded ? EXPANDED : COMPACT, 400);
    }

    @Override
    public void doLayout() {
        int w = getWidth() - 8, y = 4;
        menu.setBounds(4, y, w, ITEM_H);
        y += ITEM_H + GAP + 4;
        for (int i = 0; i < items.size(); i++) {
            if (footer.get(i)) continue;
            items.get(i).setBounds(4, y, w, ITEM_H);
            y += ITEM_H + GAP;
        }
        int by = getHeight() - 8;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (!footer.get(i)) continue;
            by -= ITEM_H;
            items.get(i).setBounds(4, by, w, ITEM_H);
            by -= GAP;
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (!expanded) return; // compact: the window's Mica shows through
        // Open: acrylic over the content, its right corners rounded (the left edge is the window's).
        Acrylic.paint((Graphics2D) g, new RoundRectangle2D.Double(-Fluent.OVERLAY_ARC, 0.5, getWidth() + Fluent.OVERLAY_ARC - 0.5,
                getHeight() - 1, Fluent.OVERLAY_ARC, Fluent.OVERLAY_ARC), backdrop);
    }

    /** A page (or the menu button): its icon, its name when the pane is open, the pill when chosen. */
    private final class Item extends JToggleButton {
        private final Icons glyph;
        private final boolean page;

        Item(String label, Icons glyph, boolean page) {
            super(label);
            this.glyph = glyph;
            this.page = page;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setRequestFocusEnabled(false);
            getAccessibleContext().setAccessibleName(label);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
        }

        @Override
        public boolean isSelected() {
            return page && super.isSelected();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Theme t = Appearance.theme();
            Theme.Palette p = t.palette;
            ButtonModel m = getModel();
            boolean hover = m.isRollover(), pressed = m.isArmed() && m.isPressed(), sel = isSelected();
            int w = getWidth(), h = getHeight();
            // Chosen: the hover fill stays (a little lighter); pressing darkens it.
            Color fill = Fluent.subtleFill(t, hover || sel, pressed);
            if (sel && !hover && !pressed) fill = Fluent.subtleFill(t, true, false);
            if (fill.getAlpha() > 0) {
                g.setColor(fill);
                g.fill(new RoundRectangle2D.Double(0, 0, w, h, Fluent.CONTROL_ARC, Fluent.CONTROL_ARC));
            }
            if (sel) {
                double ph = pressed ? 10 : 16;
                g.setColor(t.highContrast() ? p.warn() : p.accent());
                g.fill(new RoundRectangle2D.Double(0, (h - ph) / 2, 3, ph, 3, 3));
            }
            if (FocusRing.shows(this)) Fluent.focus(g, 1, 1, w - 2, h - 2, Fluent.CONTROL_ARC);
            Color fg = sel || hover ? p.text() : Contrast.toward(p.secondary(), p.text(), 0.35);
            int icon = 16, ix = (COMPACT - 8 - icon) / 2;
            glyph.icon(icon, fg).paintIcon(this, g, ix, (h - icon) / 2);
            if (expanded) {
                Font f = FluentButton.fontFor(FluentButton.Size.REGULAR, FluentButton.Style.STANDARD);
                g.setFont(sel ? f.deriveFont(Font.BOLD) : f);
                FontMetrics fm = g.getFontMetrics();
                g.setColor(fg);
                g.drawString(getText(), COMPACT - 4, (h - fm.getHeight()) / 2 + fm.getAscent());
            }
            g.dispose();
        }
    }
}
