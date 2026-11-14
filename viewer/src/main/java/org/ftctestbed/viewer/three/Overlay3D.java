package org.ftctestbed.viewer.three;

import com.acmerobotics.dashboard.canvas.Canvas;

import javafx.scene.Group;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;

import org.ftctestbed.viewer.FieldRenderer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What robot code draws on the FTC Dashboard or Panels field (Road Runner's and Pedro's paths,
 * the pose they think the robot is at, targets), laid on the 3D field's tiles like a projection:
 * just above the shadows, under the robots and game pieces. Drawing calls are in field inches,
 * center origin, +y up, as in the 2D view; stroke widths (screen pixels on the dashboard) become
 * a fraction of an inch. Text isn't drawn. Rebuilt only when the code sends a new drawing.
 */
final class Overlay3D {
    static final double IN = 0.0254;
    static final double Z = 0.004;              // above the robots' shadows (Field3D.ROBOT_SHADOW_Z)
    static final double INCHES_PER_PIXEL = 0.3; // a 1 px dashboard line is 0.3 in wide...
    static final double MIN_WIDTH = 0.6;        // ...but at least 0.6 in, to be seen from the driver's view
    private static final int SWATCH = 4, PER_ROW = 16, SIZE = SWATCH * PER_ROW;

    final Group root = new Group();
    private final MeshView view = new MeshView();
    private final WritableImage palette = new WritableImage(SIZE, SIZE);
    private Object lastDashboard, lastPanels;

    /** The mesh being built: points (meters), each face's color swatch. */
    private final List<float[]> triangles = new ArrayList<>();
    private final List<Integer> faceColors = new ArrayList<>();
    private final Map<Integer, Integer> swatches = new LinkedHashMap<>();

    Overlay3D() {
        PhongMaterial m = new PhongMaterial(Color.BLACK);
        m.setDiffuseMap(palette);           // its alpha: see-through where the code set one
        m.setSelfIlluminationMap(palette);  // bright, whatever the lights
        m.setSpecularColor(Color.BLACK);
        view.setMaterial(m);
        view.setCullFace(CullFace.NONE);
        view.setMouseTransparent(true);
        root.getChildren().add(view);
        root.setMouseTransparent(true);
    }

    /** Draws these (either may be null); does nothing if they're the ones already drawn. */
    void update(Canvas dashboard, Canvas panels) {
        if (dashboard == lastDashboard && panels == lastPanels) return;
        lastDashboard = dashboard;
        lastPanels = panels;
        triangles.clear();
        faceColors.clear();
        swatches.clear();
        if (dashboard != null) add(dashboard.getOperations());
        if (panels != null) add(panels.getOperations());
        build();
    }

    /** How many triangles are drawn (for tests). */
    int triangles() {
        return triangles.size();
    }

    /** The colors drawn, 0xAARRGGBB (for tests). */
    List<Integer> colors() {
        return new ArrayList<>(swatches.keySet());
    }

    private void add(List<Canvas.Op> ops) {
        double tx = 0, ty = 0, rot = 0, scx = 1, scy = 1;
        java.awt.Color fill = new java.awt.Color(0x4FC3F7), stroke = new java.awt.Color(0x4FC3F7);
        double width = 1, alpha = 1;
        for (Canvas.Op op : List.copyOf(ops)) {
            double[] v = op.values;
            Xf xf = new Xf(tx, ty, rot, scx, scy);
            switch (op.kind) {
                case "fill" -> fill = FieldRenderer.cssColor(op.text, fill);
                case "stroke" -> stroke = FieldRenderer.cssColor(op.text, stroke);
                case "strokeWidth" -> width = Math.max(0.5, v[0]);
                case "alpha" -> alpha = Math.max(0, Math.min(1, v[0]));
                case "translation" -> { tx = v[0]; ty = v[1]; }
                case "rotation" -> rot = v[0];
                case "scale" -> { scx = v[0]; scy = v[1]; }
                case "strokeCircle" -> {
                    int n = 36;
                    double[] pts = new double[2 * (n + 1)];
                    for (int i = 0; i <= n; i++) {
                        double a = 2 * Math.PI * i / n;
                        pts[2 * i] = v[0] + v[2] * Math.cos(a);
                        pts[2 * i + 1] = v[1] + v[2] * Math.sin(a);
                    }
                    polyline(xf, pts, width, color(stroke, alpha));
                }
                case "fillCircle" -> {
                    int n = 36;
                    double[] pts = new double[2 * n];
                    for (int i = 0; i < n; i++) {
                        double a = 2 * Math.PI * i / n;
                        pts[2 * i] = v[0] + v[2] * Math.cos(a);
                        pts[2 * i + 1] = v[1] + v[2] * Math.sin(a);
                    }
                    polygon(xf, pts, color(fill, alpha));
                }
                case "strokeRect" -> polyline(xf, new double[]{v[0], v[1], v[0] + v[2], v[1], v[0] + v[2], v[1] + v[3],
                        v[0], v[1] + v[3], v[0], v[1]}, width, color(stroke, alpha));
                case "fillRect" -> polygon(xf, new double[]{v[0], v[1], v[0] + v[2], v[1], v[0] + v[2], v[1] + v[3],
                        v[0], v[1] + v[3]}, color(fill, alpha));
                case "strokeLine" -> polyline(xf, new double[]{v[0], v[1], v[2], v[3]}, width, color(stroke, alpha));
                case "strokePolyline" -> polyline(xf, v, width, color(stroke, alpha));
                case "strokePolygon" -> {
                    if (v.length < 4) break;
                    double[] closed = java.util.Arrays.copyOf(v, v.length + 2);
                    closed[v.length] = v[0];
                    closed[v.length + 1] = v[1];
                    polyline(xf, closed, width, color(stroke, alpha));
                }
                case "fillPolygon" -> polygon(xf, v, color(fill, alpha));
                default -> { }
            }
        }
    }

    private static int color(java.awt.Color c, double alpha) {
        int a = (int) Math.round(c.getAlpha() * alpha);
        return (a << 24) | (c.getRGB() & 0xFFFFFF);
    }

    /** The drawing's own transform (translation, rotation, scale) from its coordinates to field inches. */
    private record Xf(double tx, double ty, double rot, double sx, double sy) {
        double[] apply(double x, double y) {
            double c = Math.cos(rot), s = Math.sin(rot);
            double ux = x * sx, uy = y * sy;
            return new double[]{tx + c * ux - s * uy, ty + s * ux + c * uy};
        }
    }

    /** A line through these points (x, y pairs): a flat ribbon per segment. */
    private void polyline(Xf xf, double[] pts, double widthPx, int argb) {
        if ((argb >>> 24) == 0 || pts.length < 4) return;
        double half = Math.max(MIN_WIDTH, widthPx * INCHES_PER_PIXEL) / 2;
        for (int i = 0; i + 3 < pts.length; i += 2) {
            double[] a = xf.apply(pts[i], pts[i + 1]), b = xf.apply(pts[i + 2], pts[i + 3]);
            double dx = b[0] - a[0], dy = b[1] - a[1], len = Math.hypot(dx, dy);
            if (len < 1e-9) continue;
            // Each segment runs a half-width past its ends, so corners close up.
            double ux = dx / len * half, uy = dy / len * half, nx = -uy, ny = ux;
            double[] p0 = {a[0] - ux + nx, a[1] - uy + ny}, p1 = {b[0] + ux + nx, b[1] + uy + ny};
            double[] p2 = {b[0] + ux - nx, b[1] + uy - ny}, p3 = {a[0] - ux - nx, a[1] - uy - ny};
            triangle(p0, p1, p2, argb);
            triangle(p0, p2, p3, argb);
        }
    }

    /** A filled shape (a fan from its first corner: fine for the convex outlines code draws). */
    private void polygon(Xf xf, double[] pts, int argb) {
        if ((argb >>> 24) == 0 || pts.length < 6) return;
        double[] first = xf.apply(pts[0], pts[1]);
        for (int i = 2; i + 3 < pts.length; i += 2) {
            triangle(first, xf.apply(pts[i], pts[i + 1]), xf.apply(pts[i + 2], pts[i + 3]), argb);
        }
    }

    private void triangle(double[] a, double[] b, double[] c, int argb) {
        triangles.add(new float[]{(float) (a[0] * IN), (float) (a[1] * IN), (float) (b[0] * IN), (float) (b[1] * IN),
                (float) (c[0] * IN), (float) (c[1] * IN)});
        Integer s = swatches.get(argb);
        if (s == null) {
            if (swatches.size() >= PER_ROW * PER_ROW) s = 0; // more colors than swatches: reuse the first
            else swatches.put(argb, s = swatches.size());
        }
        faceColors.add(s);
    }

    private void build() {
        PixelWriter w = palette.getPixelWriter();
        for (Map.Entry<Integer, Integer> e : swatches.entrySet()) {
            int sx = e.getValue() % PER_ROW * SWATCH, sy = e.getValue() / PER_ROW * SWATCH;
            for (int i = 0; i < SWATCH; i++) for (int j = 0; j < SWATCH; j++) w.setArgb(sx + i, sy + j, e.getKey());
        }
        TriangleMesh mesh = new TriangleMesh();
        float z = (float) Z;
        for (int k = 0; k < triangles.size(); k++) {
            float[] t = triangles.get(k);
            int base = k * 3;
            mesh.getPoints().addAll(t[0], t[1], z, t[2], t[3], z, t[4], t[5], z);
            int s = faceColors.get(k);
            // Every corner at its swatch's center, so the texture lookup is that one color.
            float u = (s % PER_ROW * SWATCH + SWATCH / 2f) / SIZE, v = (s / PER_ROW * SWATCH + SWATCH / 2f) / SIZE;
            mesh.getTexCoords().addAll(u, v);
            mesh.getFaces().addAll(base, k, base + 1, k, base + 2, k);
        }
        if (triangles.isEmpty()) {
            view.setMesh(null);
            view.setVisible(false);
        } else {
            view.setMesh(mesh);
            view.setVisible(true);
        }
    }
}
