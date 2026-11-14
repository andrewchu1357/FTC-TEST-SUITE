package org.ftctestbed.viewer.ui;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.function.IntConsumer;

/**
 * The window, laid out as a Windows 11 app: the title bar and the navigation rail on Mica, and
 * the content on a Layer with a rounded top-left corner, the command bar across its top, the
 * page (the field, code or settings) and the Driver Station pane beside it, docked rather than
 * floating, so nothing is ever drawn over the live 3D view. The only see-through surface is the
 * open navigation pane (acrylic, captured once as it opens) and menus.
 */
public final class AppShell extends JLayeredPane {
    private static final int PANE_MIN = 340, GRIP = 7;

    private final JPanel base = new Mica(new BorderLayout());
    private final JPanel railSlot = new JPanel(new BorderLayout());
    private final NavRail nav;
    private final JComponent pane, page, commands;
    private final Frame frame;
    private final JComponent grip = new JPanel();
    private final JComponent shadow = new Shadow();
    private int paneWidth;
    private boolean paneVisible = true;
    private IntConsumer widthListener = w -> {};
    private AWTEventListener dismiss;

    public AppShell(TitleBar titleBar, NavRail nav, CommandBar commands, JComponent page, JComponent pane, int paneWidth) {
        this.nav = nav;
        this.pane = pane;
        this.page = page;
        this.commands = commands;
        this.paneWidth = paneWidth;
        frame = new Frame();
        setLayout(null);
        base.add(titleBar, BorderLayout.NORTH);
        railSlot.setOpaque(false);
        railSlot.setPreferredSize(new Dimension(NavRail.COMPACT, 100));
        railSlot.add(nav, BorderLayout.CENTER);
        base.add(railSlot, BorderLayout.WEST);
        base.add(frame, BorderLayout.CENTER);
        add(base, DEFAULT_LAYER);
        shadow.setVisible(false);
        add(shadow, PALETTE_LAYER);

        grip.setOpaque(false);
        grip.setCursor(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR));
        grip.setToolTipText("Drag to resize the Driver Station");
        MouseAdapter resize = new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                Point p = SwingUtilities.convertPoint(grip, e.getPoint(), frame);
                setPaneWidth(frame.getWidth() - p.x);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                widthListener.accept(AppShell.this.paneWidth);
            }
        };
        grip.addMouseListener(resize);
        grip.addMouseMotionListener(resize);

        nav.onMenu(() -> setNavExpanded(!nav.expanded()));
        nav.addActionListener(e -> setNavExpanded(false));
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeNav");
        getActionMap().put("closeNav", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setNavExpanded(false);
            }

            @Override
            public boolean isEnabled() {
                return nav.expanded(); // otherwise Esc goes on to whatever has the focus
            }
        });
    }

    public void onPaneResized(IntConsumer l) {
        widthListener = l;
    }

    public boolean paneVisible() {
        return paneVisible;
    }

    public int paneWidth() {
        return paneWidth;
    }

    public void setPaneVisible(boolean visible) {
        paneVisible = visible;
        frame.revalidate();
        frame.repaint();
    }

    public void setPaneWidth(int w) {
        int max = Math.max(PANE_MIN, (int) (getWidth() * 0.5));
        paneWidth = Math.max(PANE_MIN, Math.min(max, w));
        frame.revalidate();
        frame.repaint();
    }

    /**
     * Opens the navigation pane wide over the content, on acrylic made from what's under it
     * now (clicking anywhere else or Esc closes it), or closes it back to the rail.
     */
    public void setNavExpanded(boolean expand) {
        if (expand == nav.expanded()) return;
        if (expand) {
            int top = railSlot.getY();
            Rectangle r = new Rectangle(0, top, NavRail.EXPANDED, getHeight() - top);
            java.awt.image.BufferedImage backdrop = Acrylic.capture(this, r);
            railSlot.remove(nav);
            add(nav, MODAL_LAYER);
            nav.setBounds(r);
            nav.setExpanded(true, backdrop);
            shadow.setBounds(r.x + r.width, r.y, 14, r.height);
            shadow.setVisible(true);
            dismiss = e -> {
                if (e.getID() == MouseEvent.MOUSE_PRESSED && e.getSource() instanceof Component c && !SwingUtilities.isDescendingFrom(c, nav)) {
                    SwingUtilities.invokeLater(() -> setNavExpanded(false));
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(dismiss, AWTEvent.MOUSE_EVENT_MASK);
        } else {
            if (dismiss != null) Toolkit.getDefaultToolkit().removeAWTEventListener(dismiss);
            dismiss = null;
            remove(nav);
            nav.setExpanded(false, null);
            railSlot.add(nav, BorderLayout.CENTER);
            shadow.setVisible(false);
            railSlot.revalidate();
        }
        repaint();
    }

    @Override
    public void doLayout() {
        base.setBounds(0, 0, getWidth(), getHeight());
        if (nav.expanded()) {
            int top = railSlot.getY();
            nav.setBounds(0, top, NavRail.EXPANDED, getHeight() - top);
            shadow.setBounds(NavRail.EXPANDED, top, 14, getHeight() - top);
        }
    }

    @Override
    public boolean isOptimizedDrawingEnabled() {
        return !nav.expanded(); // only the open navigation pane overlaps anything
    }

    /** The window's base: Mica (the theme's base color). */
    private static final class Mica extends JPanel {
        Mica(BorderLayout layout) {
            super(layout);
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Fluent.mica(Appearance.theme()));
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }

    /** The content: a Layer with its top-left corner rounded, holding the command bar, the page and the pane. */
    private final class Frame extends JPanel {
        Frame() {
            super(null);
            setOpaque(false);
            add(commands);
            add(page);
            add(grip);
            add(pane);
        }

        @Override
        public void doLayout() {
            int w = getWidth(), h = getHeight(), top = CommandBar.HEIGHT;
            commands.setBounds(1, 1, w - 1, top);
            boolean show = paneVisible && pane.isVisible();
            int pw = show ? Math.min(paneWidth, Math.max(PANE_MIN, (int) (w * 0.6))) : 0;
            page.setBounds(1, top + 1, w - 1 - pw, h - top - 1);
            pane.setBounds(w - pw, top + 1, pw, h - top - 1);
            grip.setBounds(w - pw - GRIP / 2, top + 1, GRIP, h - top - 1);
            grip.setVisible(show);
        }

        private Path2D outline(boolean closed) {
            double r = Fluent.OVERLAY_ARC / 2.0, w = getWidth(), h = getHeight();
            Path2D p = new Path2D.Double();
            p.moveTo(0.5, h + 1);
            p.lineTo(0.5, r + 0.5);
            p.quadTo(0.5, 0.5, r + 0.5, 0.5);
            p.lineTo(w + 1, 0.5);
            if (closed) {
                p.lineTo(w + 1, h + 1);
                p.closePath();
            }
            return p;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Theme t = Appearance.theme();
            g.setColor(Fluent.layer(t));
            g.fill(outline(true));
            g.setColor(Fluent.stroke(t));
            g.setStroke(new BasicStroke(Fluent.strokeWidth(t)));
            g.draw(outline(false));
            // The line between the page and the Driver Station pane.
            if (grip.isVisible()) g.fillRect(pane.getX(), pane.getY(), (int) Fluent.strokeWidth(t), pane.getHeight());
            g.dispose();
        }
    }

    /** The soft shadow the open navigation pane casts on the content. */
    private static final class Shadow extends JComponent {
        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            boolean dark = Appearance.theme().dark;
            g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, dark ? 90 : 40), getWidth(), 0, new Color(0, 0, 0, 0)));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.dispose();
        }
    }
}
