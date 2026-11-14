package org.ftctestbed.viewer.ui;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;

/**
 * Line icons in the style of Segoe Fluent Icons (thin, even strokes), drawn as shapes so they're
 * sharp at any size and take the color of the text next to them.
 */
public enum Icons {
    CUBE(g -> {
        path(g, true, 8, 1.8, 14, 5, 14, 11.5, 8, 14.6, 2, 11.5, 2, 5);
        line(g, 2, 5, 8, 8.2);
        line(g, 14, 5, 8, 8.2);
        line(g, 8, 8.2, 8, 14.6);
    }),
    MAP(g -> {
        path(g, true, 1.8, 3.5, 6, 2, 10, 3.5, 14.2, 2, 14.2, 12.5, 10, 14, 6, 12.5, 1.8, 14);
        line(g, 6, 2, 6, 12.5);
        line(g, 10, 3.5, 10, 14);
    }),
    CODE(g -> {
        path(g, false, 5, 4, 1.5, 8, 5, 12);
        path(g, false, 11, 4, 14.5, 8, 11, 12);
        line(g, 9.2, 2.8, 6.8, 13.2);
    }),
    CAMERA(g -> {
        g.draw(new RoundRectangle2D.Double(1.5, 4.5, 13, 9, 3, 3));
        path(g, false, 5.2, 4.5, 6.2, 2.6, 9.8, 2.6, 10.8, 4.5);
        g.draw(new Ellipse2D.Double(5.3, 6.3, 5.4, 5.4));
    }),
    PLAY(g -> fill(g, 4, 2.2, 13.6, 8, 4, 13.8)),
    STOP(g -> g.fill(new RoundRectangle2D.Double(3, 3, 10, 10, 3, 3))),
    POWER(g -> {
        g.draw(new Arc2D.Double(2.2, 2.8, 11.6, 11.6, 120, 300, Arc2D.OPEN));
        line(g, 8, 1.4, 8, 7.6);
    }),
    PAUSE(g -> {
        g.fill(new RoundRectangle2D.Double(3.5, 2.5, 3, 11, 1.5, 1.5));
        g.fill(new RoundRectangle2D.Double(9.5, 2.5, 3, 11, 1.5, 1.5));
    }),
    STEP(g -> {
        fill(g, 2.5, 2.8, 10.5, 8, 2.5, 13.2);
        g.fill(new RoundRectangle2D.Double(11.2, 2.8, 2.6, 10.4, 1.3, 1.3));
    }),
    RESET(g -> {
        g.draw(new Arc2D.Double(2.2, 2.4, 11.6, 11.6, 100, 280, Arc2D.OPEN));
        fill(g, 6.4, 0.6, 9.9, 2.8, 6.6, 5.2);
    }),
    GEAR(g -> {
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * i / 4;
            line(g, 8 + Math.cos(a) * 5.2, 8 + Math.sin(a) * 5.2, 8 + Math.cos(a) * 7, 8 + Math.sin(a) * 7);
        }
        g.draw(new Ellipse2D.Double(3, 3, 10, 10));
        g.draw(new Ellipse2D.Double(6, 6, 4, 4));
    }),
    SIDEBAR(g -> {
        g.draw(new RoundRectangle2D.Double(1.5, 2.5, 13, 11, 3, 3));
        line(g, 10, 2.5, 10, 13.5);
        line(g, 11.6, 5.2, 13, 5.2);
        line(g, 11.6, 7.4, 13, 7.4);
    }),
    FLAG(g -> {
        line(g, 3, 1.8, 3, 14.6);
        path(g, true, 3, 2.4, 13.4, 2.4, 11, 5.6, 13.4, 8.8, 3, 8.8);
    }),
    GAMEPAD(g -> {
        Path2D p = new Path2D.Double();
        p.moveTo(4.4, 4);
        p.lineTo(11.6, 4);
        p.curveTo(14.4, 4, 15.4, 9, 15.2, 11.6);
        p.curveTo(15, 13.6, 12.6, 13.8, 11.4, 11.6);
        p.lineTo(10.6, 10.4);
        p.lineTo(5.4, 10.4);
        p.lineTo(4.6, 11.6);
        p.curveTo(3.4, 13.8, 1, 13.6, 0.8, 11.6);
        p.curveTo(0.6, 9, 1.6, 4, 4.4, 4);
        p.closePath();
        g.draw(p);
        line(g, 4.6, 6.2, 4.6, 8.6);
        line(g, 3.4, 7.4, 5.8, 7.4);
        g.fill(new Ellipse2D.Double(10.6, 5.6, 1.6, 1.6));
        g.fill(new Ellipse2D.Double(11.8, 7.6, 1.6, 1.6));
    }),
    TROPHY(g -> {
        path(g, false, 4.5, 2, 11.5, 2, 11.5, 6, 11.5, 6);
        g.draw(new Arc2D.Double(4.5, 1, 7, 9, 180, 180, Arc2D.OPEN));
        line(g, 4.5, 2, 4.5, 5.5);
        g.draw(new Arc2D.Double(1.6, 2.6, 3.6, 3.8, 90, 180, Arc2D.OPEN));
        g.draw(new Arc2D.Double(10.8, 2.6, 3.6, 3.8, 270, 180, Arc2D.OPEN));
        line(g, 8, 10, 8, 12.6);
        line(g, 5, 14, 11, 14);
    }),
    CHIP(g -> {
        g.draw(new RoundRectangle2D.Double(3.5, 3.5, 9, 9, 2, 2));
        for (double x : new double[]{6, 8, 10}) {
            line(g, x, 1.2, x, 3.5);
            line(g, x, 12.5, x, 14.8);
            line(g, 1.2, x, 3.5, x);
            line(g, 12.5, x, 14.8, x);
        }
    }),
    TERMINAL(g -> {
        g.draw(new RoundRectangle2D.Double(1.5, 2.5, 13, 11, 3, 3));
        path(g, false, 4.4, 6, 6.6, 8, 4.4, 10);
        line(g, 8.2, 10.4, 11.4, 10.4);
    }),
    KEYBOARD(g -> {
        g.draw(new RoundRectangle2D.Double(1, 4, 14, 8.5, 2.5, 2.5));
        for (double x = 3.6; x < 13; x += 2.3) g.fill(new Ellipse2D.Double(x - 0.6, 6, 1.2, 1.2));
        line(g, 5, 10, 11, 10);
    }),
    ROBOT(g -> {
        g.draw(new RoundRectangle2D.Double(2.5, 5, 11, 8.5, 3, 3));
        line(g, 8, 5, 8, 2.6);
        g.fill(new Ellipse2D.Double(7, 1.2, 2, 2));
        g.fill(new Ellipse2D.Double(5, 8, 2, 2));
        g.fill(new Ellipse2D.Double(9, 8, 2, 2));
    }),
    LIST(g -> {
        for (double y : new double[]{4, 8, 12}) {
            g.fill(new Ellipse2D.Double(1.6, y - 1, 2, 2));
            line(g, 5.5, y, 14.5, y);
        }
    }),
    SPEED(g -> {
        g.draw(new Arc2D.Double(1.5, 3, 13, 13, 0, 180, Arc2D.OPEN));
        line(g, 8, 9.5, 11.6, 6);
        g.fill(new Ellipse2D.Double(6.8, 8.3, 2.4, 2.4));
    }),
    PIN(g -> {
        Path2D p = new Path2D.Double();
        p.moveTo(8, 14.8);
        p.curveTo(4.4, 10.6, 2.8, 8.4, 2.8, 6.2);
        p.curveTo(2.8, 3.2, 5.2, 1.2, 8, 1.2);
        p.curveTo(10.8, 1.2, 13.2, 3.2, 13.2, 6.2);
        p.curveTo(13.2, 8.4, 11.6, 10.6, 8, 14.8);
        g.draw(p);
        g.draw(new Ellipse2D.Double(6, 4.2, 4, 4));
    }),
    CHEVRON_DOWN(g -> path(g, false, 3.5, 6, 8, 10.5, 12.5, 6)),
    MENU(g -> {
        line(g, 2, 4, 14, 4);
        line(g, 2, 8, 14, 8);
        line(g, 2, 12, 14, 12);
    }),
    SPEAKER(g -> {
        path(g, true, 1.8, 6, 4.6, 6, 8.4, 2.6, 8.4, 13.4, 4.6, 10, 1.8, 10);
        g.draw(new Arc2D.Double(7.2, 5, 4.6, 6, -50, 100, Arc2D.OPEN));
        g.draw(new Arc2D.Double(7.4, 2.6, 7, 10.8, -55, 110, Arc2D.OPEN));
    }),
    CLOCK(g -> {
        g.draw(new Ellipse2D.Double(1.5, 1.5, 13, 13));
        path(g, false, 8, 4.4, 8, 8, 10.6, 9.6);
    }),
    CLOSE(g -> {
        line(g, 4, 4, 12, 12);
        line(g, 12, 4, 4, 12);
    }),
    CHECK(g -> path(g, false, 3, 8.4, 6.6, 12, 13, 4.4)),
    PEOPLE(g -> {
        g.draw(new Ellipse2D.Double(3, 2.5, 4, 4));
        g.draw(new Arc2D.Double(1, 8, 8, 8, 0, 180, Arc2D.OPEN));
        g.draw(new Ellipse2D.Double(9.6, 3.6, 3.4, 3.4));
        g.draw(new Arc2D.Double(9, 8.6, 6.4, 6.4, 0, 180, Arc2D.OPEN));
    }),
    EYE(g -> {
        Path2D p = new Path2D.Double();
        p.moveTo(1, 8);
        p.curveTo(3.6, 3.4, 12.4, 3.4, 15, 8);
        p.curveTo(12.4, 12.6, 3.6, 12.6, 1, 8);
        g.draw(p);
        g.draw(new Ellipse2D.Double(5.6, 5.6, 4.8, 4.8));
    }),
    PALETTE(g -> {
        Path2D p = new Path2D.Double();
        p.moveTo(8, 1.5);
        p.curveTo(12, 1.5, 14.8, 4.4, 14.8, 7.6);
        p.curveTo(14.8, 10, 12.8, 10.4, 11.6, 10.4);
        p.curveTo(10, 10.4, 9.6, 11.6, 10.4, 12.6);
        p.curveTo(11.2, 13.8, 10.2, 14.6, 8, 14.6);
        p.curveTo(4.2, 14.6, 1.2, 11.6, 1.2, 8);
        p.curveTo(1.2, 4.4, 4.2, 1.5, 8, 1.5);
        g.draw(p);
        for (double[] d : new double[][]{{4.4, 7}, {6.4, 4.2}, {9.8, 4.2}}) g.fill(new Ellipse2D.Double(d[0] - 0.9, d[1] - 0.9, 1.8, 1.8));
    }),
    IMPORT(g -> {
        path(g, false, 1.8, 9.5, 1.8, 14, 14.2, 14, 14.2, 9.5);
        line(g, 8, 1.6, 8, 10);
        path(g, false, 4.6, 6.8, 8, 10.2, 11.4, 6.8);
    }),
    LOCK(g -> {
        g.draw(new RoundRectangle2D.Double(3, 7, 10, 7.5, 2, 2));
        g.draw(new Arc2D.Double(5, 2, 6, 9, 0, 180, Arc2D.OPEN));
        line(g, 5, 6.5, 5, 7);
        line(g, 11, 6.5, 11, 7);
    }),
    RELOAD(g -> {
        g.draw(new Arc2D.Double(2.2, 2.2, 11.6, 11.6, 30, 150, Arc2D.OPEN));
        g.draw(new Arc2D.Double(2.2, 2.2, 11.6, 11.6, 210, 150, Arc2D.OPEN));
        fill(g, 13.6, 2.6, 13.8, 6.8, 9.8, 5.6);
        fill(g, 2.4, 13.4, 2.2, 9.2, 6.2, 10.4);
    }),
    TEXT_SIZE(g -> {
        path(g, false, 1.4, 13.5, 5, 4.5, 8.6, 13.5);
        line(g, 2.8, 10.2, 7.2, 10.2);
        path(g, false, 9.4, 13.5, 12, 7.2, 14.6, 13.5);
        line(g, 10.3, 11.3, 13.7, 11.3);
    }),
    CHART(g -> {
        line(g, 1.5, 14.5, 14.5, 14.5);
        g.fill(new RoundRectangle2D.Double(2.6, 8, 2.6, 5.4, 1, 1));
        g.fill(new RoundRectangle2D.Double(6.7, 4.4, 2.6, 9, 1, 1));
        g.fill(new RoundRectangle2D.Double(10.8, 1.8, 2.6, 11.6, 1, 1));
    }),
    PATH(g -> {
        Path2D p = new Path2D.Double();
        p.moveTo(2, 13.5);
        p.curveTo(5, 13.5, 4, 7, 8, 7);
        p.curveTo(12, 7, 11, 2.5, 14, 2.5);
        g.draw(p);
        g.fill(new Ellipse2D.Double(0.8, 12.3, 2.4, 2.4));
    }),
    INFO(g -> {
        g.draw(new Ellipse2D.Double(1.5, 1.5, 13, 13));
        line(g, 8, 7.2, 8, 11.6);
        g.fill(new Ellipse2D.Double(7.1, 4, 1.8, 1.8));
    }),
    WARNING(g -> {
        path(g, true, 8, 1.8, 14.8, 13.8, 1.2, 13.8);
        line(g, 8, 6, 8, 9.6);
        g.fill(new Ellipse2D.Double(7.1, 10.8, 1.8, 1.8));
    });

    private final Consumer<Graphics2D> draw;

    Icons(Consumer<Graphics2D> draw) {
        this.draw = draw;
    }

    /** This glyph as an icon size pixels square, in the color of the component it's painted on. */
    public Icon icon(int size) {
        return icon(size, null);
    }

    /** As icon(size), in a fixed color (null: the component's text color). */
    public Icon icon(int size, Color color) {
        Icons glyph = this;
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                Color col = color != null ? color : c != null ? c.getForeground() : Color.BLACK;
                if (c instanceof AbstractButton b && !b.isEnabled()) col = Contrast.alpha(col, col.getAlpha() / 255.0 * 0.45);
                g.setColor(col);
                g.translate(x, y);
                g.scale(size / 16.0, size / 16.0);
                // About one pixel at 16 px, as Fluent icons are drawn; a little heavier when tiny.
                g.setStroke(new BasicStroke(size <= 14 ? 1.45f : 1.25f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                glyph.draw.accept(g);
                g.dispose();
            }

            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
        };
    }

    private static void line(Graphics2D g, double x1, double y1, double x2, double y2) {
        g.draw(new Line2D.Double(x1, y1, x2, y2));
    }

    private static void path(Graphics2D g, boolean closed, double... xy) {
        Path2D p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        if (closed) p.closePath();
        g.draw(p);
    }

    private static void fill(Graphics2D g, double... xy) {
        Path2D p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.closePath();
        g.fill(p);
    }
}
