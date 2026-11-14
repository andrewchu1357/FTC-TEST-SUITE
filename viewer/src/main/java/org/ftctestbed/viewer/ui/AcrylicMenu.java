package org.ftctestbed.viewer.ui;

import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * A menu (flyout) on acrylic, as Windows 11 draws menus: the window shows through it blurred.
 * What's under it is captured once as it opens (see Acrylic), so an open menu costs nothing more
 * than a plain one.
 */
public class AcrylicMenu extends JPopupMenu {
    private BufferedImage backdrop;
    private Point screen;

    @Override
    public void setLocation(int x, int y) {
        screen = new Point(x, y); // where it'll open (after Swing moved it to fit on the screen)
        super.setLocation(x, y);
    }

    @Override
    public void setVisible(boolean b) {
        if (b && !isVisible()) capture();
        super.setVisible(b);
    }

    private void capture() {
        backdrop = capture(this, screen);
    }

    /**
     * What's under popup m opening at screen (screen coordinates), as an acrylic backdrop; null
     * if it can't be captured. Its items are made see-through so the acrylic shows behind them.
     */
    static BufferedImage capture(JPopupMenu m, Point screen) {
        for (Component c : m.getComponents()) if (c instanceof JComponent j) j.setOpaque(false);
        Component invoker = m.getInvoker();
        JRootPane rp = invoker != null ? SwingUtilities.getRootPane(invoker) : null;
        if (rp == null || screen == null || !rp.isShowing()) return null;
        Point p = new Point(screen);
        SwingUtilities.convertPointFromScreen(p, rp.getLayeredPane());
        Dimension d = m.getPreferredSize();
        return Acrylic.capture(rp.getLayeredPane(), new Rectangle(p.x, p.y, d.width, d.height));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Acrylic.paint((Graphics2D) g, new Rectangle(0, 0, getWidth(), getHeight()), backdrop);
    }
}
