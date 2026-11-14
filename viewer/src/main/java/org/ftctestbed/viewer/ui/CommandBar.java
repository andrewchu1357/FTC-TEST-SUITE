package org.ftctestbed.viewer.ui;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Fluent's command bar across the top of the content: the page's title, the commands for what's
 * showing at the left and the ones that always apply at the right, each group in a Liquid Glass
 * capsule (see GlassGroup). Hidden commands, and groups with none showing, take no room.
 */
public final class CommandBar extends JPanel {
    public static final int HEIGHT = 48;
    private final JLabel title;
    private final List<Component> left = new ArrayList<>(), right = new ArrayList<>();

    public CommandBar() {
        super(null);
        setOpaque(false);
        title = new JLabel() {
            @Override
            public void updateUI() {
                super.updateUI();
                Font f = getFont();
                setFont(new javax.swing.plaf.FontUIResource(f.deriveFont(Font.BOLD, f.getSize2D() * 1.2f)));
                setForeground(Appearance.palette().text());
            }
        };
        title.updateUI();
        add(title);
    }

    /** The page's title, at the left. */
    public void setTitle(String text) {
        title.setText(text);
        title.getAccessibleContext().setAccessibleName(text + " page");
        revalidate();
        repaint();
    }

    public void addLeft(Component... cs) {
        for (Component c : cs) {
            left.add(c);
            add(c);
        }
    }

    /** Adds commands at the right end, in reading order. */
    public void addRight(Component... cs) {
        for (Component c : cs) {
            right.add(c);
            add(c);
        }
    }

    /** A group of commands on one Liquid Glass capsule. */
    public static GlassGroup group(Component... cs) {
        return new GlassGroup(cs);
    }

    /** A thin vertical line between groups of commands. */
    public static JComponent separator() {
        JComponent s = new JComponent() {
            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setColor(Fluent.stroke(Appearance.theme()));
                g.fillRect(getWidth() / 2, 4, 1, getHeight() - 8);
                g.dispose();
            }
        };
        s.setPreferredSize(new Dimension(13, 28));
        return s;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(600, HEIGHT);
    }

    @Override
    public void doLayout() {
        int w = getWidth(), h = getHeight(), x = 16;
        x = place(title, x, h, true) + (title.getText() == null || title.getText().isEmpty() ? 0 : 18);
        for (Component c : left) if (GlassGroup.shows(c)) x = place(c, x, h, true) + 6;
        int rx = w - 10;
        for (int i = right.size() - 1; i >= 0; i--) if (GlassGroup.shows(right.get(i))) rx = place(right.get(i), rx, h, false) - 6;
    }

    private static int place(Component c, int x, int h, boolean fromLeft) {
        Dimension d = c.getPreferredSize();
        int cx = fromLeft ? x : x - d.width;
        c.setBounds(cx, (h - d.height) / 2, d.width, d.height);
        return fromLeft ? cx + d.width : cx;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        // A hairline between the bar and the page under it.
        Graphics2D g = (Graphics2D) g0.create();
        Theme t = Appearance.theme();
        g.setColor(Fluent.stroke(t));
        g.setStroke(new BasicStroke(Fluent.strokeWidth(t)));
        g.fillRect(0, getHeight() - (int) Fluent.strokeWidth(t), getWidth(), (int) Fluent.strokeWidth(t));
        g.dispose();
    }
}
