package org.ftctestbed.viewer.three;

import javafx.geometry.Point3D;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import java.util.HashMap;
import java.util.Map;

/**
 * Geometry helpers in FIELD coordinates (meters; x right, y away from the audience, z up).
 * JavaFX primitives are built along their own axes and placed with transforms.
 */
final class Shapes {
    static final double IN = 0.0254;
    private static final Map<Integer, PhongMaterial> MATERIALS = new HashMap<>();
    private static final Map<String, PhongMaterial> LOOKS = new HashMap<>();
    /**
     * Which way the overhead light shines (field coordinates): mostly down, a little toward the
     * far right corner. Shadows on the floor lie this way from what casts them.
     */
    static final double[] SUN = {0.25, 0.35, -0.9};

    private Shapes() {}

    /** Dull, no shine: foam, tape, fabric, painted panels. */
    static PhongMaterial matte(int rgb) {
        return look("matte", rgb, 0x000000, 1);
    }

    /** Molded or painted plastic: a soft, wide highlight. */
    static PhongMaterial plastic(int rgb) {
        return look("plastic", rgb, 0x3A3A3A, 18);
    }

    /** Smooth hard plastic (the balls, PVC pipe): a small bright highlight. */
    static PhongMaterial glossy(int rgb) {
        return look("glossy", rgb, 0x8C8C8C, 60);
    }

    /** Aluminum and steel: the highlight takes the metal's own color. */
    static PhongMaterial metal(int rgb) {
        Color c = Color.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        return look("metal", rgb, rgb(c.interpolate(Color.WHITE, 0.15).deriveColor(0, 1, 0.5, 1)), 32);
    }

    /** Rubber (wheels, rollers): dark, almost no shine. */
    static PhongMaterial rubber(int rgb) {
        return look("rubber", rgb, 0x141414, 6);
    }

    /**
     * See-through (polycarbonate): tinted, with a sharp highlight. Draw it after everything solid
     * (Field3D.clear), since JavaFX doesn't sort see-through things for you.
     */
    static PhongMaterial clear(int rgb, double alpha) {
        return LOOKS.computeIfAbsent("clear " + rgb + " " + alpha, k -> {
            PhongMaterial m = new PhongMaterial(Color.WHITE);
            m.setDiffuseMap(Textures.solid(rgb, alpha));
            m.setSpecularColor(Color.gray(0.75));
            m.setSpecularPower(110);
            return m;
        });
    }

    /** A picture on a surface (no shine unless asked). */
    static PhongMaterial textured(Image map, int specular, double power) {
        PhongMaterial m = new PhongMaterial(Color.WHITE);
        m.setDiffuseMap(map);
        m.setSpecularColor(color(specular));
        m.setSpecularPower(power);
        return m;
    }

    /** A soft shadow picture: black, see-through where the picture is. */
    static PhongMaterial shade(Image map) {
        PhongMaterial m = new PhongMaterial(Color.BLACK);
        m.setDiffuseMap(map);
        m.setSpecularColor(Color.BLACK);
        return m;
    }

    private static PhongMaterial look(String kind, int rgb, int specular, double power) {
        return LOOKS.computeIfAbsent(kind + " " + rgb, k -> {
            PhongMaterial m = new PhongMaterial(color(rgb));
            m.setSpecularColor(color(specular));
            m.setSpecularPower(power);
            return m;
        });
    }

    private static Color color(int rgb) {
        return Color.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    private static int rgb(Color c) {
        return ((int) Math.round(c.getRed() * 255) << 16) | ((int) Math.round(c.getGreen() * 255) << 8) | (int) Math.round(c.getBlue() * 255);
    }

    /**
     * A flat rectangle facing up, from (x0, y0) to (x1, y1) at height z. The picture's top edge
     * is at y1 (away from the audience), and it repeats `repeat` times across each way.
     */
    static MeshView quad(double x0, double y0, double x1, double y1, double z, double repeat, Material m) {
        TriangleMesh mesh = new TriangleMesh();
        float r = (float) repeat, zf = (float) z;
        mesh.getPoints().addAll((float) x0, (float) y0, zf, (float) x1, (float) y0, zf, (float) x1, (float) y1, zf, (float) x0, (float) y1, zf);
        mesh.getTexCoords().addAll(0, r, r, r, r, 0, 0, 0);
        // Counter-clockwise seen from above: the face (and its normal) point up.
        mesh.getFaces().addAll(0, 0, 1, 1, 2, 2, 0, 0, 2, 2, 3, 3);
        MeshView v = new MeshView(mesh);
        v.setMaterial(m);
        return v;
    }

    /**
     * The inside of an upright ring (radius r, from z0 to z1), seen only from inside: from
     * outside it isn't drawn at all, so it never hides the field from a camera farther out.
     * The picture wraps around `repeat` times.
     */
    static MeshView ring(double r, double z0, double z1, int segments, double repeat, Material m) {
        TriangleMesh mesh = new TriangleMesh();
        for (int k = 0; k <= segments; k++) {
            double a = 2 * Math.PI * k / segments;
            float x = (float) (r * Math.cos(a)), y = (float) (r * Math.sin(a));
            mesh.getPoints().addAll(x, y, (float) z0, x, y, (float) z1);
            float u = (float) (repeat * k / segments);
            mesh.getTexCoords().addAll(u, 1, u, 0);
        }
        for (int k = 0; k < segments; k++) {
            int b0 = 2 * k, t0 = b0 + 1, b1 = b0 + 2, t1 = b0 + 3;
            // Counter-clockwise seen from the middle of the ring.
            mesh.getFaces().addAll(b0, b0, t0, t0, b1, b1, b1, b1, t0, t0, t1, t1);
        }
        MeshView v = new MeshView(mesh);
        v.setMaterial(m);
        return v;
    }

    static PhongMaterial mat(int rgb) {
        return mat(rgb, 1.0);
    }

    static PhongMaterial mat(int rgb, double opacity) {
        int key = rgb ^ ((int) Math.round(opacity * 100) << 24);
        return MATERIALS.computeIfAbsent(key, k -> {
            Color c = Color.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, opacity);
            PhongMaterial m = new PhongMaterial(c);
            m.setSpecularColor(Color.rgb(60, 60, 60));
            m.setSpecularPower(24);
            return m;
        });
    }

    /** Axis-aligned box centered at (x, y, z) with sizes (sx, sy, sz). */
    static Box box(double x, double y, double z, double sx, double sy, double sz, Material m) {
        Box b = new Box(sx, sy, sz);
        b.setMaterial(m);
        b.getTransforms().add(new Translate(x, y, z));
        return b;
    }

    static Sphere sphere(double x, double y, double z, double r, Material m) {
        Sphere s = new Sphere(r, 24);
        s.setMaterial(m);
        s.getTransforms().add(new Translate(x, y, z));
        return s;
    }

    /** Cylinder from a to b. */
    static Cylinder cylinder(double[] a, double[] b, double r, Material m) {
        Point3D pa = new Point3D(a[0], a[1], a[2]), pb = new Point3D(b[0], b[1], b[2]);
        Point3D d = pb.subtract(pa);
        double len = d.magnitude();
        Cylinder c = new Cylinder(r, len, 20);
        c.setMaterial(m);
        Point3D mid = pa.midpoint(pb);
        Point3D yAxis = new Point3D(0, 1, 0);
        Point3D axis = yAxis.crossProduct(d);
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, yAxis.dotProduct(d) / len))));
        c.getTransforms().add(new Translate(mid.getX(), mid.getY(), mid.getZ()));
        if (axis.magnitude() > 1e-9) c.getTransforms().add(new Rotate(angle, axis));
        else if (d.getY() < 0) c.getTransforms().add(new Rotate(180, Rotate.X_AXIS));
        return c;
    }

    /** Cylinder whose axis lies along field x / y / z, centered at (x, y, z). */
    static Cylinder cylinderAlong(char axis, double x, double y, double z, double r, double length, Material m) {
        Cylinder c = new Cylinder(r, length, 24);
        c.setMaterial(m);
        c.getTransforms().add(new Translate(x, y, z));
        if (axis == 'x') c.getTransforms().add(new Rotate(90, Rotate.Z_AXIS));
        if (axis == 'z') c.getTransforms().add(new Rotate(90, Rotate.X_AXIS));
        return c;
    }

    static void setPickable(Node n, boolean pickable) {
        n.setMouseTransparent(!pickable);
    }
}
