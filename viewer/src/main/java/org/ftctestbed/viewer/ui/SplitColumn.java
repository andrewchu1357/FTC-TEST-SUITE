package org.ftctestbed.viewer.ui;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/** Two components one above the other, with a small grip between them to share the height. */
public final class SplitColumn extends JPanel {
    private static final int GRIP = 12;
    private final JComponent top, bottom;
    private final JComponent grip = new Grip();
    private double share;
    private final int minTop, minBottom;

    /** share: the top's share of the height to start with. */
    public SplitColumn(JComponent top, JComponent bottom, double share, int minTop, int minBottom) {
        super(null);
        this.top = top;
        this.bottom = bottom;
        this.share = share;
        this.minTop = minTop;
        this.minBottom = minBottom;
        setOpaque(false);
        add(top);
        add(grip);
        add(bottom);
        MouseAdapter drag = new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                int y = SwingUtilities.convertPoint(grip, e.getPoint(), SplitColumn.this).y;
                setShare((y - GRIP / 2.0) / Math.max(1, getHeight() - GRIP));
            }
        };
        grip.addMouseListener(drag);
        grip.addMouseMotionListener(drag);
    }

    public double share() {
        return share;
    }

    public void setShare(double s) {
        share = Math.max(0.05, Math.min(0.95, s));
        revalidate();
        repaint();
    }

    @Override
    public void doLayout() {
        int w = getWidth(), h = getHeight() - GRIP;
        int th = (int) Math.round(h * share);
        th = Math.max(Math.min(minTop, h - minBottom), Math.min(th, h - minBottom));
        top.setBounds(0, 0, w, th);
        grip.setBounds(0, th, w, GRIP);
        bottom.setBounds(0, th + GRIP, w, h - th);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(100, minTop + minBottom + GRIP);
    }

    private static final class Grip extends JComponent {
        Grip() {
            setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
            setToolTipText("Drag to share the space");
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // Fluent's resize handle: a short, thin rounded bar.
            g.setColor(Contrast.alpha(Appearance.palette().secondary(), 0.55));
            g.fill(new RoundRectangle2D.Double(getWidth() / 2.0 - 16, getHeight() / 2.0 - 1.5, 32, 3, 3, 3));
            g.dispose();
        }
    }
}
