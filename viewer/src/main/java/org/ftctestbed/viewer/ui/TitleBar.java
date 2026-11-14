package org.ftctestbed.viewer.ui;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Image;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;

/**
 * The window's title bar, on Mica: the app's icon and name and what's open (as Windows apps
 * show them), after the macOS window buttons, and the status at the right. Drag it to move the
 * window; double-click to zoom.
 */
public final class TitleBar extends JPanel {
    public static final int HEIGHT = 40;
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    private final JPanel windowButtons = new JPanel();
    private final JLabel icon = new JLabel(), title, subtitle;
    private JComponent right;

    public TitleBar(Image appIcon, String name, String what) {
        super(null);
        setOpaque(false);
        // Where macOS draws the close/minimize/zoom buttons over the full-window content.
        windowButtons.setOpaque(false);
        windowButtons.putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER, "mac zeroInFullScreen");
        add(windowButtons);
        if (appIcon != null) icon.setIcon(new ImageIcon(appIcon.getScaledInstance(16, 16, Image.SCALE_SMOOTH)));
        add(icon);
        title = new JLabel(name) {
            @Override
            public void updateUI() {
                super.updateUI();
                setFont(caption(getFont()));
                setForeground(Appearance.palette().text());
            }
        };
        subtitle = new JLabel(what) {
            @Override
            public void updateUI() {
                super.updateUI();
                setFont(caption(getFont()));
                setForeground(Appearance.palette().secondary());
            }
        };
        title.updateUI();
        subtitle.updateUI();
        add(title);
        add(subtitle);
        dragsWindow(this);
    }

    /** Windows' caption type: the title bar text, a size smaller than the body. */
    private static Font caption(Font f) {
        return new javax.swing.plaf.FontUIResource(f.deriveFont(Font.PLAIN, f.getSize2D() * 0.92f));
    }

    public void setSubtitle(String what) {
        subtitle.setText(what);
        revalidate();
    }

    /** What sits at the right end (the status). */
    public void setRight(JComponent c) {
        if (right != null) remove(right);
        right = c;
        add(c);
        revalidate();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(800, HEIGHT);
    }

    @Override
    public void doLayout() {
        int w = getWidth(), h = getHeight();
        Dimension wb = windowButtons.getPreferredSize();
        int buttonsW = wb.width > 0 ? wb.width : MAC ? 78 : 0;
        windowButtons.setBounds(0, 0, buttonsW, h);
        int x = buttonsW > 0 ? buttonsW + 6 : 14;
        Dimension d = icon.getPreferredSize();
        icon.setBounds(x, (h - d.height) / 2, d.width, d.height);
        x += d.width + (d.width > 0 ? 10 : 0);
        int rx = w - 10;
        if (right != null) {
            Dimension r = right.getPreferredSize();
            rx -= r.width;
            right.setBounds(rx, (h - r.height) / 2, r.width, r.height);
            rx -= 16;
        }
        d = title.getPreferredSize();
        title.setBounds(x, (h - d.height) / 2, Math.min(d.width, Math.max(0, rx - x)), d.height);
        x += d.width + 12;
        d = subtitle.getPreferredSize();
        subtitle.setBounds(x, (h - d.height) / 2, Math.max(0, Math.min(d.width, rx - x)), d.height);
    }

    /** Dragging the bar moves the window; double-clicking it zooms the window. */
    private static void dragsWindow(JComponent bar) {
        MouseAdapter drag = new MouseAdapter() {
            private Point grab;

            @Override
            public void mousePressed(MouseEvent e) {
                Window win = SwingUtilities.getWindowAncestor(bar);
                if (win == null) return;
                Point p = e.getLocationOnScreen();
                grab = new Point(p.x - win.getX(), p.y - win.getY());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                Window win = SwingUtilities.getWindowAncestor(bar);
                if (win == null || grab == null) return;
                if (win instanceof Frame f && (f.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) return;
                Point p = e.getLocationOnScreen();
                win.setLocation(p.x - grab.x, p.y - grab.y);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.getWindowAncestor(bar) instanceof Frame f) {
                    f.setExtendedState(f.getExtendedState() ^ Frame.MAXIMIZED_BOTH);
                }
            }
        };
        bar.addMouseListener(drag);
        bar.addMouseMotionListener(drag);
    }
}
