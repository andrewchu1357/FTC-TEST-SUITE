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
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Fluent's selector bar: a row of choices as text, the chosen one in bold with a short accent
 * pill under it. The items are toggle buttons (VoiceOver reads each one and whether it's
 * selected); with one focused, the left and right arrows move the selection.
 */
public class SelectorBar extends JPanel {
    private final ButtonGroup group = new ButtonGroup();
    private final List<Item> items = new ArrayList<>();
    private final List<ActionListener> listeners = new ArrayList<>();
    private final FluentButton.Size size;
    private int selected = -1;

    /** fill: share the width equally (true) or size each item to its label, from the left. */
    public SelectorBar(FluentButton.Size size, boolean fill) {
        super(fill ? new GridLayout(1, 0, 2, 0) : new FlowLayout(FlowLayout.LEFT, 2, 0));
        this.size = size;
        setOpaque(false);
    }

    /** Adds an item; the first one added starts selected. */
    public JToggleButton add(String label, Icons glyph, String toolTip) {
        Item s = new Item(label, glyph);
        int index = items.size();
        if (toolTip != null) s.setToolTipText(toolTip);
        if (label == null || label.isEmpty()) s.getAccessibleContext().setAccessibleName(toolTip);
        s.addActionListener(e -> select(index));
        s.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "next");
        s.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "previous");
        s.getActionMap().put("next", step(+1));
        s.getActionMap().put("previous", step(-1));
        group.add(s);
        items.add(s);
        super.add(s);
        if (index == 0) select(0);
        return s;
    }

    private AbstractAction step(int d) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int n = items.size();
                int next = (selected + d + n) % n;
                for (int i = 0; i < n && !items.get(next).isEnabled(); i++) next = (next + d + n) % n;
                select(next);
                items.get(next).requestFocusInWindow();
            }
        };
    }

    /** Selects an item; listeners hear about it when the selection changes. */
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

    public JToggleButton segment(int index) {
        return items.get(index);
    }

    public int count() {
        return items.size();
    }

    public void addActionListener(ActionListener l) {
        listeners.add(l);
    }

    /** One choice: its label (and icon), a fill under the pointer, the pill when chosen. */
    private final class Item extends JToggleButton {
        private final Icons glyph;

        Item(String label, Icons glyph) {
            super(label);
            this.glyph = glyph;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setRequestFocusEnabled(false);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
        }

        private Font font() {
            Font f = FluentButton.fontFor(size, FluentButton.Style.STANDARD);
            return isSelected() ? f.deriveFont(Font.BOLD) : f;
        }

        @Override
        public Dimension getPreferredSize() {
            // Measured in bold, so choosing an item doesn't shift the others.
            FontMetrics fm = getFontMetrics(FluentButton.fontFor(size, FluentButton.Style.STANDARD).deriveFont(Font.BOLD));
            String text = getText() == null ? "" : getText();
            int icon = glyph != null ? FluentButton.iconSize(size) - 2 : 0;
            int w = fm.stringWidth(text) + icon + (icon > 0 && !text.isEmpty() ? 6 : 0) + 24;
            int h = (size == FluentButton.Size.SMALL ? 30 : 36) + (size == FluentButton.Size.LARGE ? 4 : 0);
            return new Dimension(w, Math.max(h, fm.getHeight() + 14));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Theme t = Appearance.theme();
            Theme.Palette p = t.palette;
            ButtonModel m = getModel();
            boolean hover = isEnabled() && m.isRollover(), pressed = isEnabled() && m.isArmed() && m.isPressed();
            int w = getWidth(), h = getHeight();
            Color fill = Fluent.subtleFill(t, hover, pressed);
            if (fill.getAlpha() > 0) {
                g.setColor(fill);
                g.fill(new RoundRectangle2D.Double(2, 2, w - 4, h - 4, Fluent.CONTROL_ARC, Fluent.CONTROL_ARC));
            }
            if (FocusRing.shows(this)) Fluent.focus(g, 3, 3, w - 6, h - 6, Fluent.CONTROL_ARC);
            Color fg = !isEnabled() ? Contrast.alpha(p.text(), 0.45) : isSelected() || hover ? p.text() : p.secondary();
            g.setFont(font());
            FontMetrics fm = g.getFontMetrics();
            String text = getText() == null ? "" : getText();
            int icon = glyph != null ? FluentButton.iconSize(size) - 2 : 0;
            int content = fm.stringWidth(text) + icon + (icon > 0 && !text.isEmpty() ? 6 : 0);
            int x = (w - content) / 2, mid = h / 2 - 1;
            if (glyph != null) {
                glyph.icon(icon, fg).paintIcon(this, g, x, mid - icon / 2);
                x += icon + 6;
            }
            g.setColor(fg);
            g.drawString(text, x, mid - fm.getHeight() / 2 + fm.getAscent());
            if (isSelected()) {
                int pw = t.highContrast() ? 24 : 16, ph = t.highContrast() ? 4 : 3;
                g.setColor(t.highContrast() ? p.warn() : p.accent());
                g.fill(new RoundRectangle2D.Double((w - pw) / 2.0, h - ph - 3, pw, ph, ph, ph));
            }
            g.dispose();
        }
    }
}
