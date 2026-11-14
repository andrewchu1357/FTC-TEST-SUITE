package com.acmerobotics.dashboard.canvas;

import java.util.ArrayList;
import java.util.List;

/**
 * FTC Dashboard field overlay. Simulator stand-in: records the drawing operations (field
 * coordinates in inches, as on the dashboard) so the simulator can show them.
 */
public class Canvas {
    /** One recorded drawing call, e.g. {"strokeCircle", x, y, r}. */
    public static final class Op {
        public final String kind;
        public final double[] values;
        public final String text;

        Op(String kind, String text, double... values) {
            this.kind = kind;
            this.text = text;
            this.values = values;
        }
    }

    private final List<Op> ops = new ArrayList<Op>();

    private Canvas add(String kind, String text, double... values) {
        ops.add(new Op(kind, text, values));
        return this;
    }

    public Canvas setFill(String color) { return add("fill", color); }
    public Canvas setStroke(String color) { return add("stroke", color); }
    public Canvas setStrokeWidth(int width) { return add("strokeWidth", null, width); }
    public Canvas setAlpha(double alpha) { return add("alpha", null, alpha); }
    public Canvas strokeCircle(double x, double y, double radius) { return add("strokeCircle", null, x, y, radius); }
    public Canvas fillCircle(double x, double y, double radius) { return add("fillCircle", null, x, y, radius); }
    public Canvas strokeLine(double x1, double y1, double x2, double y2) { return add("strokeLine", null, x1, y1, x2, y2); }
    public Canvas strokeRect(double x, double y, double width, double height) { return add("strokeRect", null, x, y, width, height); }
    public Canvas fillRect(double x, double y, double width, double height) { return add("fillRect", null, x, y, width, height); }
    public Canvas strokePolygon(double[] xPoints, double[] yPoints) { return add("strokePolygon", null, interleave(xPoints, yPoints)); }
    public Canvas fillPolygon(double[] xPoints, double[] yPoints) { return add("fillPolygon", null, interleave(xPoints, yPoints)); }
    public Canvas strokePolyline(double[] xPoints, double[] yPoints) { return add("strokePolyline", null, interleave(xPoints, yPoints)); }
    public Canvas setTranslation(double x, double y) { return add("translation", null, x, y); }
    public Canvas setRotation(double radians) { return add("rotation", null, radians); }
    public Canvas setScale(double scaleX, double scaleY) { return add("scale", null, scaleX, scaleY); }
    public Canvas fillText(String text, double x, double y, String font, double theta) { return add("text", text, x, y, theta); }
    public Canvas fillText(String text, double x, double y, String font, double theta, boolean usePageFrame) { return add("text", text, x, y, theta); }
    public Canvas strokeText(String text, double x, double y, String font, double theta) { return add("text", text, x, y, theta); }
    public Canvas strokeText(String text, double x, double y, String font, double theta, boolean usePageFrame) { return add("text", text, x, y, theta); }
    public Canvas drawImage(String path, double x, double y, double width, double height) { return this; }
    public Canvas drawImage(String path, double x, double y, double width, double height, double theta, double pivotX, double pivotY, boolean usePageFrame) { return this; }
    public Canvas drawGrid(double x, double y, double width, double height, int numTicksX, int numTicksY) { return this; }
    public Canvas setTransform(double x, double y, double scaleX, double scaleY) { return this; }

    /** The drawing calls so far. Like the dashboard's, the list itself (Road Runner adds a path preview to it). */
    public List<Op> getOperations() { return ops; }

    /** Simulator: a copy that later drawing on this canvas doesn't change. */
    public Canvas simCopy() {
        Canvas c = new Canvas();
        c.ops.addAll(ops);
        return c;
    }

    public void clear() { ops.clear(); }

    private static double[] interleave(double[] xs, double[] ys) {
        int n = Math.min(xs.length, ys.length);
        double[] out = new double[n * 2];
        for (int i = 0; i < n; i++) {
            out[i * 2] = xs[i];
            out[i * 2 + 1] = ys[i];
        }
        return out;
    }
}
