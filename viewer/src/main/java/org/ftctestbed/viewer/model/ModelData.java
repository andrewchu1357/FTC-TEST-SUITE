package org.ftctestbed.viewer.model;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A loaded 3D model: a tree of named nodes with triangle meshes. Plain Java (no JavaFX) so it
 * can be loaded and checked headless. Matrices are 4x4 row-major, applied to column vectors.
 */
public final class ModelData {
    /** Triangles with one color. Loaders leave normals null: the 3D view computes them from the triangles. */
    public static final class Mesh {
        public final float[] positions;
        public final float[] normals;
        public final int[] indices;
        public final float[] rgba;
        /** glTF metallic and roughness (0..1), for the highlight; NaN when the file doesn't say. */
        public float metallic = Float.NaN, roughness = Float.NaN;
        private double[] bounds;

        public Mesh(float[] positions, float[] normals, int[] indices, float[] rgba) {
            this.positions = positions;
            this.normals = normals;
            this.indices = indices;
            this.rgba = rgba;
        }

        public int triangles() { return indices.length / 3; }

        /** {minX, minY, minZ, maxX, maxY, maxZ} in the mesh's own coordinates. */
        public double[] bounds() {
            if (bounds == null) {
                double[] b = emptyBounds();
                for (int i = 0; i + 2 < positions.length; i += 3) include(b, positions[i], positions[i + 1], positions[i + 2]);
                bounds = b;
            }
            return bounds;
        }
    }

    public static final class Node {
        public final String name;
        public final double[] matrix;
        public final List<Mesh> meshes = new ArrayList<>();
        public final List<Node> children = new ArrayList<>();

        public Node(String name, double[] matrix) {
            this.name = name == null ? "" : name;
            this.matrix = matrix == null ? identity() : matrix;
        }

        public void forEach(Consumer<Node> action) {
            action.accept(this);
            for (Node c : children) c.forEach(action);
        }
    }

    public final Node root;
    /** "m" for glTF (meters by definition); "auto" when the file format doesn't say. */
    public final String units;
    public final String source;
    public final List<String> warnings = new ArrayList<>();

    public ModelData(Node root, String units, String source) {
        this.root = root;
        this.units = units;
        this.source = source;
    }

    public int triangles() {
        int[] n = {0};
        root.forEach(node -> node.meshes.forEach(m -> n[0] += m.triangles()));
        return n[0];
    }

    /** Every node, mapped to its matrix in the model's root frame (including its own matrix). */
    public Map<Node, double[]> worldMatrices(double[] rootMatrix) {
        Map<Node, double[]> out = new IdentityHashMap<>();
        walk(root, rootMatrix, out);
        return out;
    }

    private static void walk(Node n, double[] parent, Map<Node, double[]> out) {
        double[] world = multiply(parent, n.matrix);
        out.put(n, world);
        for (Node c : n.children) walk(c, world, out);
    }

    /** Bounds of everything under node, after transform (mesh bounding-box corners, so slightly generous). */
    public static double[] bounds(Node node, double[] transform) {
        double[] b = emptyBounds();
        boundsInto(node, transform, b);
        return b;
    }

    private static void boundsInto(Node n, double[] parent, double[] b) {
        double[] world = multiply(parent, n.matrix);
        for (Mesh m : n.meshes) {
            double[] mb = m.bounds();
            if (mb[0] > mb[3]) continue;
            for (int c = 0; c < 8; c++) {
                double[] p = transformPoint(world, (c & 1) == 0 ? mb[0] : mb[3], (c & 2) == 0 ? mb[1] : mb[4], (c & 4) == 0 ? mb[2] : mb[5]);
                include(b, p[0], p[1], p[2]);
            }
        }
        for (Node c : n.children) boundsInto(c, world, b);
    }

    // ------------------------------------------------------------------ small matrix helpers

    public static double[] emptyBounds() {
        return new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
    }

    static void include(double[] b, double x, double y, double z) {
        b[0] = Math.min(b[0], x);
        b[1] = Math.min(b[1], y);
        b[2] = Math.min(b[2], z);
        b[3] = Math.max(b[3], x);
        b[4] = Math.max(b[4], y);
        b[5] = Math.max(b[5], z);
    }

    public static double[] identity() {
        return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    public static double[] multiply(double[] a, double[] b) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double s = 0;
                for (int k = 0; k < 4; k++) s += a[i * 4 + k] * b[k * 4 + j];
                r[i * 4 + j] = s;
            }
        }
        return r;
    }

    public static double[] transformPoint(double[] m, double x, double y, double z) {
        return new double[]{m[0] * x + m[1] * y + m[2] * z + m[3], m[4] * x + m[5] * y + m[6] * z + m[7],
                m[8] * x + m[9] * y + m[10] * z + m[11]};
    }

    public static double[] transformDirection(double[] m, double x, double y, double z) {
        return new double[]{m[0] * x + m[1] * y + m[2] * z, m[4] * x + m[5] * y + m[6] * z, m[8] * x + m[9] * y + m[10] * z};
    }

    public static double[] scale(double s) {
        return new double[]{s, 0, 0, 0, 0, s, 0, 0, 0, 0, s, 0, 0, 0, 0, 1};
    }

    public static double[] translation(double x, double y, double z) {
        return new double[]{1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1};
    }

    /** Rotation by degrees about the x, y or z axis. */
    public static double[] rotation(char axis, double degrees) {
        double c = Math.cos(Math.toRadians(degrees)), s = Math.sin(Math.toRadians(degrees));
        return switch (axis) {
            case 'x' -> new double[]{1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1};
            case 'y' -> new double[]{c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1};
            default -> new double[]{c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        };
    }

    /** Inverse of an affine matrix (last row 0 0 0 1). */
    public static double[] inverseAffine(double[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[4], e = m[5], f = m[6], g = m[8], h = m[9], i = m[10];
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (Math.abs(det) < 1e-300) return identity();
        double[] r = new double[16];
        r[0] = (e * i - f * h) / det;
        r[1] = (c * h - b * i) / det;
        r[2] = (b * f - c * e) / det;
        r[4] = (f * g - d * i) / det;
        r[5] = (a * i - c * g) / det;
        r[6] = (c * d - a * f) / det;
        r[8] = (d * h - e * g) / det;
        r[9] = (b * g - a * h) / det;
        r[10] = (a * e - b * d) / det;
        r[3] = -(r[0] * m[3] + r[1] * m[7] + r[2] * m[11]);
        r[7] = -(r[4] * m[3] + r[5] * m[7] + r[6] * m[11]);
        r[11] = -(r[8] * m[3] + r[9] * m[7] + r[10] * m[11]);
        r[15] = 1;
        return r;
    }
}
