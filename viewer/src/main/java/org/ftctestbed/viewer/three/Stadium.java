package org.ftctestbed.viewer.three;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Translate;

import org.ftctestbed.sim.Snapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Stands all the way round the field, like an arena at a championship, full of people who cheer.
 * Now and then someone raises their arms. When an alliance scores, the sections cheering for it
 * jump with their arms in the air (and the LED board along the front flashes its color); the
 * whole place goes up when a period starts and when the match ends; and every so often, while
 * it's quiet, the crowd does the wave.
 *
 * The stands come close, as in a gym: the front wall is just behind the alliance areas and a
 * little over a meter behind the other two walls. From above they're a rounded rectangle, every
 * row a bigger one behind the last. Each section between two aisles is a group of its own, so
 * the ones between a pulled-back camera and the field can be hidden. The
 * people in a section are in a few groups that move together, left to right: JavaFX draws a few
 * hundred big shapes quickly but thousands of small ones slowly. The stands and the people are
 * all one material, a picture of every color used, each face pointing at its color.
 */
final class Stadium {
    static final int ROWS = 14, SHARES = 6;                     // shares: groups of people in a section that move together
    static final double CORNER = 1.2;                           // m: the front wall's corner radius
    static final double WALL = 0.25, WALL_TOP = 1.15;           // m: the front wall's thickness and height
    static final double TREAD = 0.85, RISE = 0.42, FIRST = 0.55; // m: a row's depth, the step up to the next, the first row's height
    static final double AISLE = 1.1, SEAT = 0.52, CURTAIN = 2.8; // m: aisle and seat widths, the drape above the back row
    static final double LAP = 10;                               // s: the wave, once round the stands
    private static final double FLOOR = -0.016;

    private static final int TREAD_C = 0x6A6E74, RISER_C = 0x4E5258, WALL_C = 0x1B1E23, WALL_TOP_C = 0x2C3038,
            AISLE_C = 0x83878D, NOSE_C = 0xD9A82B, SEAT_C = 0x2D4868;
    private static final int[] SKIN = {0xF1C9A5, 0xE0AC69, 0xC68642, 0x8D5524, 0xFFDBAC, 0x5C3A21, 0xD1A27A};
    private static final int[] HAIR = {0x1B1B1B, 0x2E2018, 0x4A3423, 0x6E4F33, 0xB89058, 0x8A8A8A, 0x7A3418};
    private static final int[] TEAM = {0xF57C00, 0x7CB342, 0x7B1FA2, 0x00897B, 0xFDD835, 0x283593, 0x880E4F, 0x29B6F6,
            0xC62828, 0x1565C0, 0x212121, 0xECEFF1, 0xEC407A, 0x2E7D32, 0xFFB300, 0x6D4C41};
    private static final int[] CASUAL = {0xECEFF1, 0x212121, 0x8E9196, 0x263238, 0x3D5A80, 0xB71C1C, 0x556B2F,
            0x6A1B9A, 0xC8B79E, 0x90CAF9, 0x5D4037, 0xF06292, 0x00695C, 0x37474F};
    private static final int[] PANTS = {0x2F3E56, 0x1E1E1E, 0x3B4A63, 0x9C8B6E, 0x52575D, 0x23314A};
    private static final int[] SHOES = {0xEDEDED, 0x1A1A1A, 0x5A5F66, 0x8B5A2B};
    private static final int[] SIGNS = {0xFFF59D, 0xFFFFFF, 0xB2FF59, 0xFF8A80, 0x80D8FF};

    final Group root = new Group();
    private final double fieldHalf, hx, hy; // m: the front wall's face from the middle, along x and y
    private final List<Section> sections = new ArrayList<>();
    private final Map<Integer, Integer> palette = new LinkedHashMap<>();
    private final List<Part> parts = new ArrayList<>();
    private final PhongMaterial look = new PhongMaterial(Color.gray(0.78)); // the stands a little dimmer than the field
    private final PhongMaterial board = new PhongMaterial(Color.BLACK);
    private final Random rnd = new Random(11);

    // How worked up the supporters of each alliance are (0..1); it dies down over a few seconds.
    private double red, blue, everyone;
    private double time, nextWave = 45, waveStart = -1;
    private double flashUntil = -1;
    private String flash = "calm", shown = "calm";
    private String phase = "";
    private int redScore = -1, blueScore = -1;

    /** The people in one section who move together. */
    private static final class Crowd {
        final Group node = new Group();
        final Translate lift = new Translate();
        final MeshView[] arms = new MeshView[3]; // all down, a few up, all up
        double around;   // radians round the field, for the wave
        double sumX, sumY;
        int people, showing = -1, wants;
        double next, phase;
    }

    /** The stands between two aisles. */
    private static final class Section {
        final Group node = new Group();
        final String cheersFor;   // "red", "blue", or null: both
        final List<Crowd> crowds = new ArrayList<>();
        final double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        final double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        Bounds bounds;
        boolean hidden;

        Section(String cheersFor) {
            this.cheersFor = cheersFor;
        }
    }

    /** Where a seat is: its middle on the tread, facing out (n) from the field; a = back, b = along the row, c = up. */
    private record Frame(double x, double y, double z, double nx, double ny) {
        double[] at(double a, double b, double c) {
            return new double[]{x + a * nx - b * ny, y + a * ny + b * nx, z + c};
        }
    }

    /** A mesh being built in field coordinates, each face colored from the palette. */
    private final class Part {
        final MeshView view = new MeshView();
        final Section section;
        float[] points = new float[3 * 64];
        int[] faces = new int[6 * 64];
        int np, nf;

        Part(Section section) {
            this.section = section;
            parts.add(this);
        }

        int point(double[] p) {
            if (np + 3 > points.length) points = Arrays.copyOf(points, points.length * 2);
            for (int i = 0; i < 3; i++) {
                points[np + i] = (float) p[i];
                section.min[i] = Math.min(section.min[i], p[i]);
                section.max[i] = Math.max(section.max[i], p[i]);
            }
            np += 3;
            return np / 3 - 1;
        }

        void tri(int a, int b, int c, int rgb) {
            if (nf + 6 > faces.length) faces = Arrays.copyOf(faces, faces.length * 2);
            int t = palette.computeIfAbsent(rgb, k -> palette.size());
            faces[nf++] = a;
            faces[nf++] = t;
            faces[nf++] = b;
            faces[nf++] = t;
            faces[nf++] = c;
            faces[nf++] = t;
        }

        /** Counter-clockwise seen from the front. */
        void quad(int a, int b, int c, int d, int rgb) {
            tri(a, b, c, rgb);
            tri(a, c, d, rgb);
        }
    }

    Stadium(double fieldHalf) {
        this.fieldHalf = fieldHalf;
        // Behind the alliance areas (which reach 55 in past the walls along x), and 1.2 m behind the others.
        hx = fieldHalf + 2.2;
        hy = fieldHalf + 1.2;
        root.setMouseTransparent(true);
        board.setSpecularColor(Color.BLACK);
        board.setSelfIlluminationMap(Textures.ribbon("calm"));
        PhongMaterial curtain = Shapes.textured(Textures.drape(), 0x0C0C0C, 6);
        Random layout = new Random(2026);
        // A section on each side and in each corner; the long sides (along x) get two.
        for (int p = 0; p < 8; p++) {
            int n = p == 2 || p == 6 ? 2 : 1;
            for (int k = 0; k < n; k++) addSection(p, (double) k / n, (double) (k + 1) / n, layout, curtain);
        }
        // Every color is known now: the palette picture, and each face's spot in it.
        int n = 32, cell = 4, size = n * cell;
        if (palette.size() > n * n) throw new IllegalStateException("too many colors: " + palette.size());
        int[] argb = new int[size * size];
        float[] uv = new float[2 * palette.size()];
        for (Map.Entry<Integer, Integer> e : palette.entrySet()) {
            int i = e.getValue() % n, j = e.getValue() / n;
            for (int y = 0; y < cell; y++) Arrays.fill(argb, (j * cell + y) * size + i * cell, (j * cell + y) * size + i * cell + cell, 0xFF000000 | e.getKey());
            uv[2 * e.getValue()] = (i * cell + cell / 2f) / size;
            uv[2 * e.getValue() + 1] = (j * cell + cell / 2f) / size;
        }
        WritableImage colors = new WritableImage(size, size);
        colors.getPixelWriter().setPixels(0, 0, size, size, PixelFormat.getIntArgbInstance(), argb, 0, size);
        look.setDiffuseMap(colors);
        look.setSpecularColor(Color.gray(0.06));
        look.setSpecularPower(8);
        for (Part p : parts) {
            TriangleMesh m = new TriangleMesh();
            m.getPoints().setAll(p.points, 0, p.np);
            m.getTexCoords().setAll(uv);
            m.getFaces().setAll(p.faces, 0, p.nf);
            m.getFaceSmoothingGroups().setAll(new int[p.nf / 6]); // flat faces
            p.view.setMesh(m);
            p.view.setMaterial(look);
        }
        for (Section sec : sections) {
            for (Crowd c : sec.crowds) {
                c.around = Math.atan2(c.sumY, c.sumX);
                c.phase = layout.nextDouble() * Math.PI;
                show(c, 0);
            }
        }
    }

    // ------------------------------------------------------------------ the shape of the stands

    /**
     * A point d m back from the front wall's face, s (0..1) along piece p of the outline: 0 is the
     * east (+x) side, then counter-clockwise; the odd pieces are the rounded corners.
     */
    double[] at(int p, double s, double d) {
        double cx = hx - CORNER, cy = hy - CORNER, r = CORNER + d;
        return switch (p) {
            case 0 -> new double[]{hx + d, cy * (2 * s - 1)};
            case 2 -> new double[]{cx * (1 - 2 * s), hy + d};
            case 4 -> new double[]{-hx - d, cy * (1 - 2 * s)};
            case 6 -> new double[]{cx * (2 * s - 1), -hy - d};
            default -> {
                double[] dir = outward(p, s);
                yield new double[]{(p == 1 || p == 7 ? cx : -cx) + r * dir[0], (p == 1 || p == 3 ? cy : -cy) + r * dir[1]};
            }
        };
    }

    /** Straight out from the field (unit, on the floor) at s along piece p. */
    static double[] outward(int p, double s) {
        return p % 2 == 0 ? turn(p / 2, 1, 0) : turn(p / 2, Math.cos(Math.PI / 2 * s), Math.sin(Math.PI / 2 * s));
    }

    /** (x, y) turned q quarter turns counter-clockwise. */
    private static double[] turn(int q, double x, double y) {
        return switch (q & 3) {
            case 0 -> new double[]{x, y};
            case 1 -> new double[]{-y, x};
            case 2 -> new double[]{-x, -y};
            default -> new double[]{y, -x};
        };
    }

    /** How far (m) it is along piece p from s0 to s1, d m back from the front. */
    private double length(int p, double s0, double s1, double d) {
        double straight = p == 0 || p == 4 ? 2 * (hy - CORNER) : 2 * (hx - CORNER);
        return (s1 - s0) * (p % 2 == 0 ? straight : (CORNER + d) * Math.PI / 2);
    }

    private static double[] xyz(double[] xy, double z) {
        return new double[]{xy[0], xy[1], z};
    }

    /**
     * The stands from s0 to s1 along piece p. Behind the alliance areas, and in the corners next
     * to them, the supporters of that alliance sit; along the other two sides, everybody else.
     */
    private void addSection(int p, double s0, double s1, Random layout, Material curtain) {
        Section sec = section(p, s0, s1, layout, curtain);
        sec.max[2] += 0.25; // room for people jumping
        sec.bounds = new BoundingBox(sec.min[0], sec.min[1], sec.min[2],
                sec.max[0] - sec.min[0], sec.max[1] - sec.min[1], sec.max[2] - sec.min[2]);
        sections.add(sec);
        root.getChildren().add(sec.node);
    }

    private Section section(int p, double s0, double s1, Random layout, Material curtain) {
        double mx = at(p, (s0 + s1) / 2, 4)[0];
        Section sec = new Section(p == 2 || p == 6 ? null : mx < 0 ? "red" : "blue");
        double[] ss = new double[p % 2 == 0 ? 2 : 13];
        for (int k = 0; k < ss.length; k++) ss[k] = s0 + (s1 - s0) * k / (ss.length - 1);

        Part stands = new Part(sec);
        sec.node.getChildren().add(stands.view);
        // The front wall: its face, the LED board along it, its top, and its back down to the first row.
        upright(stands, p, ss, 0, FLOOR, WALL_TOP, WALL_C, true);
        flat(stands, p, ss, 0, WALL, WALL_TOP, WALL_TOP_C);
        upright(stands, p, ss, WALL, FIRST, WALL_TOP, WALL_C, false);
        sec.node.getChildren().add(band(sec, p, ss, -0.005, 0.60, 0.98, 4.0, board));
        double last = FIRST + (ROWS - 1) * RISE, back = WALL + ROWS * TREAD;
        sec.node.getChildren().add(band(sec, p, ss, back, last, last + CURTAIN, 2.8, curtain));

        // The section's people, in SHARES groups from left to right.
        Part[][] crowd = new Part[SHARES][4]; // body, then arms all down, a few up, all up
        for (int k = 0; k < SHARES; k++) {
            Crowd c = new Crowd();
            c.node.getTransforms().add(c.lift);
            for (int j = 0; j < 4; j++) {
                crowd[k][j] = new Part(sec);
                c.node.getChildren().add(crowd[k][j].view);
                if (j > 0) c.arms[j - 1] = crowd[k][j].view;
            }
            sec.crowds.add(c);
            sec.node.getChildren().add(c.node);
        }
        int teams = 1 + layout.nextInt(2);
        int[] teamShirt = {TEAM[layout.nextInt(TEAM.length)], TEAM[layout.nextInt(TEAM.length)]};
        double fans = sec.cheersFor == null ? 0.3 : 0.7;

        for (int k = 0; k < ROWS; k++) {
            double d = WALL + k * TREAD, z = FIRST + k * RISE;
            flat(stands, p, ss, d, d + TREAD, z, TREAD_C);
            if (k < ROWS - 1) upright(stands, p, ss, d + TREAD, z, z + RISE, RISER_C, true);
            // The aisle at the section's start: lighter steps with a yellow edge.
            double[] mid = at(p, s0, 0), out = outward(p, s0);
            double[] along = {-out[1] * AISLE / 2, out[0] * AISLE / 2};
            double[][] edge = {{d, NOSE_C, 0.003}, {d + 0.07, AISLE_C, 0.002}, {d + TREAD, 0, 0}};
            for (int e = 0; e < 2; e++) {
                double da = edge[e][0], db = edge[e + 1][0], lift = edge[e][2];
                double[] a0 = {mid[0] + out[0] * da - along[0], mid[1] + out[1] * da - along[1]};
                double[] a1 = {mid[0] + out[0] * da + along[0], mid[1] + out[1] * da + along[1]};
                double[] b1 = {mid[0] + out[0] * db + along[0], mid[1] + out[1] * db + along[1]};
                double[] b0 = {mid[0] + out[0] * db - along[0], mid[1] + out[1] * db - along[1]};
                stands.quad(stands.point(xyz(a0, z + lift)), stands.point(xyz(b0, z + lift)),
                        stands.point(xyz(b1, z + lift)), stands.point(xyz(a1, z + lift)), (int) edge[e][1]);
            }
            // Seats along the row, clear of the aisles at both ends; people in most of them.
            double ds = d + TREAD - 0.30, len = length(p, s0, s1, ds);
            int seats = (int) Math.floor((len - AISLE) / SEAT);
            if (seats < 1) continue;
            double first = AISLE / 2 + (len - AISLE - seats * SEAT) / 2 + SEAT / 2;
            for (int j = 0; j < seats; j++) {
                double s = s0 + (s1 - s0) * (first + j * SEAT) / len;
                double[] at = at(p, s, ds), n = outward(p, s);
                Frame f = new Frame(at[0], at[1], z, n[0], n[1]);
                box(stands, f, -0.21, 0.21, -0.23, 0.23, 0.40, 0.45, SEAT_C);
                box(stands, f, 0.19, 0.25, -0.23, 0.23, 0.45, 0.85, SEAT_C);
                if (layout.nextDouble() > 0.86 - 0.035 * k) continue;
                int share = Math.min(SHARES - 1, j * SHARES / seats);
                boolean fan = layout.nextDouble() < fans;
                int shirt = fan ? teamShirt[teams == 2 && j >= seats / 2 ? 1 : 0] : pick(layout, CASUAL);
                person(f, crowd[share], layout, shirt);
                Crowd c = sec.crowds.get(share);
                c.sumX += at[0];
                c.sumY += at[1];
                c.people++;
            }
        }
        return sec;
    }

    /** A flat surface facing up, d0 to d1 back from the front, at height z. */
    private void flat(Part m, int p, double[] ss, double d0, double d1, double z, int rgb) {
        for (int j = 0; j + 1 < ss.length; j++) {
            int f0 = m.point(xyz(at(p, ss[j], d0), z)), f1 = m.point(xyz(at(p, ss[j + 1], d0), z));
            int b0 = m.point(xyz(at(p, ss[j], d1), z)), b1 = m.point(xyz(at(p, ss[j + 1], d1), z));
            m.quad(f0, b0, b1, f1, rgb);
        }
    }

    /** An upright surface d back from the front, from z0 to z1, facing the field (or away from it). */
    private void upright(Part m, int p, double[] ss, double d, double z0, double z1, int rgb, boolean towardField) {
        for (int j = 0; j + 1 < ss.length; j++) {
            double[] a = at(p, ss[j], d), b = at(p, ss[j + 1], d);
            int a0 = m.point(xyz(a, z0)), a1 = m.point(xyz(a, z1)), b0 = m.point(xyz(b, z0)), b1 = m.point(xyz(b, z1));
            if (towardField) m.quad(b0, a0, a1, b1, rgb);
            else m.quad(a0, b0, b1, a1, rgb);
        }
    }

    /** A pictured upright band facing the field, d back from the front, from z0 to z1; the picture repeats every `every` m. */
    private MeshView band(Section sec, int p, double[] ss, double d, double z0, double z1, double every, Material look) {
        TriangleMesh m = new TriangleMesh();
        double u = 0;
        for (int j = 0; j < ss.length; j++) {
            if (j > 0) u += length(p, ss[j - 1], ss[j], d) / every;
            double[] a = at(p, ss[j], d);
            m.getPoints().addAll((float) a[0], (float) a[1], (float) z0, (float) a[0], (float) a[1], (float) z1);
            m.getTexCoords().addAll((float) u, 1, (float) u, 0);
            for (int i = 0; i < 2; i++) {
                sec.min[i] = Math.min(sec.min[i], a[i]);
                sec.max[i] = Math.max(sec.max[i], a[i]);
            }
            sec.max[2] = Math.max(sec.max[2], z1);
        }
        for (int j = 0; j + 1 < ss.length; j++) {
            int b0 = 2 * j, t0 = b0 + 1, b1 = b0 + 2, t1 = b0 + 3;
            m.getFaces().addAll(b0, b0, t0, t0, b1, b1, b1, b1, t0, t0, t1, t1);
        }
        MeshView v = new MeshView(m);
        v.setMaterial(look);
        return v;
    }

    // ------------------------------------------------------------------ people

    /**
     * Someone sitting in the seat at f, facing the field, into parts: their body, then their
     * arms down, raised if they're one of the keen ones, and raised (some holding up a sign).
     */
    private void person(Frame f, Part[] parts, Random r, int shirt) {
        double s = r.nextDouble() < 0.12 ? 0.72 + 0.06 * r.nextDouble() : 0.86 + 0.2 * r.nextDouble();
        int skin = pick(r, SKIN), hair = pick(r, HAIR), pants = pick(r, PANTS), shoes = pick(r, SHOES);
        boolean hat = r.nextDouble() < 0.15, keen = r.nextDouble() < 0.3, sign = r.nextDouble() < 0.07;
        int hatColor = r.nextBoolean() ? shirt : pick(r, CASUAL), signColor = pick(r, SIGNS);
        double hip = 0.45, top = hip + 0.56 * s;
        Part body = parts[0];
        box(body, f, -0.40 * s, 0.05, -0.17 * s, 0.17 * s, hip, hip + 0.14 * s, pants);      // thighs
        box(body, f, -0.44 * s, -0.31 * s, -0.16 * s, 0.16 * s, 0.05, hip + 0.02, pants);    // shins
        box(body, f, -0.50 * s, -0.30 * s, -0.16 * s, 0.16 * s, 0, 0.07, shoes);
        box(body, f, -0.08 * s, 0.15 * s, -0.20 * s, 0.20 * s, hip, top, shirt);             // torso
        box(body, f, 0, 0.08 * s, -0.045 * s, 0.045 * s, top - 0.01, top + 0.05 * s, skin);  // neck
        head(body, f, 0.03 * s, top + 0.03 * s, top + 0.25 * s, 0.10 * s, skin, hat ? hatColor : hair);
        if (hat) box(body, f, -0.21 * s, -0.06 * s, -0.08 * s, 0.08 * s, top + 0.20 * s, top + 0.22 * s, hatColor);
        armsDown(parts[1], f, s, hip, top, shirt, skin);
        if (keen) armsUp(parts[2], f, s, top, shirt, skin, false, 0);
        else armsDown(parts[2], f, s, hip, top, shirt, skin);
        armsUp(parts[3], f, s, top, shirt, skin, sign, signColor);
    }

    /** Upper arms at their sides, hands together in their lap. */
    private static void armsDown(Part m, Frame f, double s, double hip, double top, int shirt, int skin) {
        for (int side : new int[]{-1, 1}) {
            double[] shoulder = {0.04 * s, side * 0.245 * s, top - 0.05 * s};
            double[] elbow = {0.0, side * 0.25 * s, hip + 0.19 * s};
            double[] hand = {-0.22 * s, side * 0.07 * s, hip + 0.17 * s};
            beam(m, f, shoulder, elbow, 0.09 * s, shirt);
            beam(m, f, elbow, hand, 0.075 * s, skin);
        }
    }

    /** Both arms up in a V, or holding a sign up over their head. */
    private static void armsUp(Part m, Frame f, double s, double top, int shirt, int skin, boolean sign, int signColor) {
        for (int side : new int[]{-1, 1}) {
            double[] shoulder = {0.04 * s, side * 0.235 * s, top - 0.06 * s};
            double[] elbow, hand;
            if (sign) {
                elbow = new double[]{-0.05 * s, side * 0.33 * s, top + 0.12 * s};
                hand = new double[]{-0.14 * s, side * 0.24 * s, top + 0.40 * s};
            } else {
                elbow = new double[]{shoulder[0] - 0.05 * s, shoulder[1] + side * 0.07 * s, shoulder[2] + 0.27 * s};
                hand = new double[]{elbow[0] - 0.03 * s, elbow[1] + side * 0.05 * s, elbow[2] + 0.26 * s};
            }
            beam(m, f, shoulder, elbow, 0.09 * s, shirt);
            beam(m, f, elbow, hand, 0.08 * s, skin);
        }
        if (sign) {
            box(m, f, -0.19 * s, -0.16 * s, -0.34, 0.34, top + 0.33 * s, top + 0.70 * s, 0xF5F5F5);
            box(m, f, -0.20 * s, -0.185 * s, -0.27, 0.27, top + 0.40 * s, top + 0.63 * s, signColor);
        }
    }

    /** A head: an eight-sided prism around (a, 0), skin at the front, hair (or a cap) on top and behind. */
    private static void head(Part m, Frame f, double a, double c0, double c1, double r, int skin, int hair) {
        int sides = 8;
        int[] lo = new int[sides], hi = new int[sides];
        for (int i = 0; i < sides; i++) {
            double t = 2 * Math.PI * i / sides;
            lo[i] = m.point(f.at(a + r * Math.cos(t), r * Math.sin(t), c0));
            hi[i] = m.point(f.at(a + r * Math.cos(t), r * Math.sin(t), c1));
        }
        int topMid = m.point(f.at(a, 0, c1 + 0.2 * r)), bottomMid = m.point(f.at(a, 0, c0));
        for (int i = 0; i < sides; i++) {
            int j = (i + 1) % sides;
            boolean behind = Math.cos(2 * Math.PI * (i + 0.5) / sides) > 0.2; // facing away from the field
            m.quad(lo[i], lo[j], hi[j], hi[i], behind ? hair : skin);
            m.tri(topMid, hi[i], hi[j], hair);
            m.tri(bottomMid, lo[j], lo[i], skin);
        }
    }

    private static final int[][] BOX_FACES = {{1, 3, 7, 5}, {0, 4, 6, 2}, {2, 6, 7, 3}, {0, 1, 5, 4}, {4, 5, 7, 6}, {0, 2, 3, 1}};

    /** A box in the seat's frame: center c, axes x, y, z (unit, right-handed), half sizes h. */
    private static void box(Part m, Frame f, double[] c, double[] x, double[] y, double[] z, double hx, double hy, double hz, int rgb) {
        int[] idx = new int[8];
        for (int k = 0; k < 8; k++) {
            double sx = (k & 1) != 0 ? hx : -hx, sy = (k & 2) != 0 ? hy : -hy, sz = (k & 4) != 0 ? hz : -hz;
            idx[k] = m.point(f.at(c[0] + sx * x[0] + sy * y[0] + sz * z[0],
                    c[1] + sx * x[1] + sy * y[1] + sz * z[1],
                    c[2] + sx * x[2] + sy * y[2] + sz * z[2]));
        }
        for (int[] q : BOX_FACES) m.quad(idx[q[0]], idx[q[1]], idx[q[2]], idx[q[3]], rgb);
    }

    /** A box lined up with the seat: a back, b along the row, c up. */
    private static void box(Part m, Frame f, double a0, double a1, double b0, double b1, double c0, double c1, int rgb) {
        box(m, f, new double[]{(a0 + a1) / 2, (b0 + b1) / 2, (c0 + c1) / 2}, new double[]{1, 0, 0}, new double[]{0, 1, 0}, new double[]{0, 0, 1},
                (a1 - a0) / 2, (b1 - b0) / 2, (c1 - c0) / 2, rgb);
    }

    /** A square bar w thick from p to q (seat frame). */
    private static void beam(Part m, Frame f, double[] p, double[] q, double w, int rgb) {
        double[] d = {q[0] - p[0], q[1] - p[1], q[2] - p[2]};
        double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        double[] z = {d[0] / len, d[1] / len, d[2] / len};
        double[] x = {0, z[2], -z[1]}; // z cross (1, 0, 0)
        double xl = Math.hypot(x[1], x[2]);
        x = new double[]{0, x[1] / xl, x[2] / xl};
        double[] y = {z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0]};
        double[] c = {(p[0] + q[0]) / 2, (p[1] + q[1]) / 2, (p[2] + q[2]) / 2};
        box(m, f, c, x, y, z, w / 2, w / 2, len / 2, rgb);
    }

    private static int pick(Random r, int[] from) {
        return from[r.nextInt(from.length)];
    }

    // ------------------------------------------------------------------ cheering

    /** A new snapshot: cheers when an alliance scores, and from everyone when a period starts and when the match ends. */
    void follow(Snapshot s) {
        String p = s.matchPhase() == null ? "" : s.matchPhase();
        if (!p.equals(phase)) {
            if (!phase.isEmpty() && (p.equals("AUTO") || p.equals("TELEOP"))) cheer(null, 0.8);
            if (p.equals("ENDED") && !phase.isEmpty()) {
                cheer(null, 1.0);
                flash("both", 5);
            }
            phase = p;
        }
        if (s.season() == null) return;
        int r = s.season().score("red"), b = s.season().score("blue");
        if (redScore >= 0 && r > redScore) scored("red", r - redScore);
        if (blueScore >= 0 && b > blueScore) scored("blue", b - blueScore);
        redScore = r;
        blueScore = b;
    }

    /** An alliance scored: its supporters go up, more for more points, and the board flashes its color. */
    void scored(String alliance, int points) {
        cheer(alliance, 0.55 + 0.02 * Math.min(points, 20));
        flash(alliance, 2.5);
    }

    /** Raises the excitement of one alliance's supporters (or everyone's, alliance null), and they react right away. */
    private void cheer(String alliance, double amount) {
        if (alliance == null) everyone = Math.min(1, everyone + amount);
        else if (alliance.equals("red")) red = Math.min(1, red + amount);
        else blue = Math.min(1, blue + amount);
        for (Section sec : sections) {
            if (alliance != null && sec.cheersFor != null && !alliance.equals(sec.cheersFor)) continue;
            for (Crowd c : sec.crowds) c.next = Math.min(c.next, 0.3 * rnd.nextDouble());
        }
    }

    private void flash(String what, double seconds) {
        flash = what;
        flashUntil = time + seconds;
    }

    /** How worked up a section is now (0..1). */
    private double excitement(Section sec) {
        double calm = phase.equals("PRACTICE") || phase.isEmpty() ? 0.10 : 0.16;
        double own = sec.cheersFor == null ? 0.7 * Math.max(red, blue) : sec.cheersFor.equals("red") ? red : blue;
        return Math.min(1, calm + Math.max(own, everyone));
    }

    private double pending;

    /**
     * Moves the crowd on dt seconds. A calm crowd (nobody cheering, no wave, the board still)
     * moves four times a second: someone raising an arm doesn't need smooth motion, and each
     * change makes the whole view draw again. Cheering and the wave move every frame.
     */
    void animate(double dt) {
        pending += dt;
        boolean calm = waveStart < 0 && Math.max(Math.max(red, blue), everyone) < 0.29 && time >= flashUntil;
        if (calm && pending < 0.25) return;
        dt = pending;
        pending = 0;
        time += dt;
        double fade = Math.exp(-dt / 3.0);
        red *= fade;
        blue *= fade;
        everyone *= Math.exp(-dt / 4.0);
        // Now and then, when it's quiet, the wave: twice round, counter-clockwise.
        if (waveStart < 0 && time > nextWave && Math.max(Math.max(red, blue), everyone) < 0.1) waveStart = time;
        if (waveStart >= 0 && time - waveStart > 2 * LAP + 1) {
            waveStart = -1;
            nextWave = time + 60 + 60 * rnd.nextDouble();
        }
        double wave = waveStart < 0 ? Double.NaN : -Math.PI / 2 + 2 * Math.PI * (time - waveStart) / LAP;
        for (Section sec : sections) {
            if (sec.hidden) continue;
            double e = excitement(sec);
            for (Crowd c : sec.crowds) {
                c.next -= dt;
                if (c.next <= 0) {
                    double up = 0.85 * Math.max(0, Math.min(1, (e - 0.3) / 0.6)), few = Math.min(1 - up, 0.12 + 0.45 * e);
                    double roll = rnd.nextDouble();
                    c.wants = roll < up ? 2 : roll < up + few ? 1 : 0;
                    c.next = e > 0.5 ? 0.25 + 0.45 * rnd.nextDouble() : 1.0 + 3.0 * rnd.nextDouble();
                }
                double w = waveLift(c.around, wave);
                show(c, w > 0.3 ? 2 : c.wants);
                double jump = e > 0.45 ? 0.10 * (e - 0.45) / 0.55 * Math.abs(Math.sin(Math.PI * 1.7 * time + c.phase)) : 0;
                c.lift.setZ(Math.max(jump, 0.22 * w));
            }
        }
        // The LED board: an alliance's color when it scores (both, taking turns, at the end), else its calm loop.
        String want = time < flashUntil ? (flash.equals("both") ? ((int) (time * 2) % 2 == 0 ? "red" : "blue") : flash) : "calm";
        if (!want.equals(shown)) {
            shown = want;
            board.setSelfIlluminationMap(Textures.ribbon(want));
        }
    }

    /** How far up (0..1) the wave has people at this angle round the field, its front at `wave` (NaN: no wave). */
    static double waveLift(double around, double wave) {
        if (Double.isNaN(wave)) return 0;
        double behind = Math.IEEEremainder(wave - around, 2 * Math.PI); // > 0: the front has passed them
        if (behind < -0.05 || behind > 0.6) return 0;
        return Math.sin(Math.PI * (behind + 0.05) / 0.65);
    }

    private static void show(Crowd c, int arms) {
        if (arms == c.showing) return;
        c.showing = arms;
        for (int i = 0; i < 3; i++) c.arms[i].setVisible(i == arms);
    }

    // ------------------------------------------------------------------ the camera

    /**
     * Hides the sections between the camera and the field (it's been pulled back behind them),
     * and shows them again once it's back in front of them.
     */
    void cameraMoved(double[] eye, double[] robot) {
        double h = fieldHalf;
        double[][] targets = {{0, 0, 0}, {-h, -h, 0.3}, {h, -h, 0.3}, {h, h, 0.3}, {-h, h, 0.3}, robot};
        for (Section sec : sections) {
            boolean hide = false;
            for (double[] t : targets) if (t != null && Field3D.hits(eye, t, sec.bounds, 0)) hide = true;
            if (hide == sec.hidden) continue;
            sec.hidden = hide;
            sec.node.setVisible(!hide);
        }
    }

    // ------------------------------------------------------------------ for tests

    int sections() {
        return sections.size();
    }

    Bounds bounds(int section) {
        return sections.get(section).bounds;
    }

    boolean hidden(int section) {
        return sections.get(section).hidden;
    }

    String cheersFor(int section) {
        return sections.get(section).cheersFor;
    }

    int people() {
        int n = 0;
        for (Section sec : sections) for (Crowd c : sec.crowds) n += c.people;
        return n;
    }

    /** The share (0..1) of the people cheering for this alliance (null: neither) who have their arms up right now. */
    double armsUp(String alliance) {
        int up = 0, all = 0;
        for (Section sec : sections) {
            if (!java.util.Objects.equals(sec.cheersFor, alliance)) continue;
            for (Crowd c : sec.crowds) {
                all += c.people;
                if (c.showing == 2) up += c.people;
            }
        }
        return all == 0 ? 0 : (double) up / all;
    }

    /** The highest anyone's off their seat right now (m). */
    double highestLift() {
        double top = 0;
        for (Section sec : sections) for (Crowd c : sec.crowds) top = Math.max(top, c.lift.getZ());
        return top;
    }

    String board() {
        return shown;
    }

    int colors() {
        return palette.size();
    }
}
