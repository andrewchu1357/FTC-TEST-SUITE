package org.ftctestbed.viewer.three;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

import org.ftctestbed.sim.Snapshot;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.ftctestbed.sim.season.biobuzz.BioBuzzField.*;
import static org.ftctestbed.viewer.three.Shapes.box;
import static org.ftctestbed.viewer.three.Shapes.clear;
import static org.ftctestbed.viewer.three.Shapes.cylinder;
import static org.ftctestbed.viewer.three.Shapes.cylinderAlong;
import static org.ftctestbed.viewer.three.Shapes.glossy;
import static org.ftctestbed.viewer.three.Shapes.mat;
import static org.ftctestbed.viewer.three.Shapes.matte;
import static org.ftctestbed.viewer.three.Shapes.metal;
import static org.ftctestbed.viewer.three.Shapes.plastic;
import static org.ftctestbed.viewer.three.Shapes.quad;
import static org.ftctestbed.viewer.three.Shapes.sphere;

/**
 * The 3D field: the venue around it, tiles, walls, season elements, and the game pieces. Field
 * coordinates in meters. See-through things (the polycarbonate walls and CELL skins) are in
 * `clear`, which goes into the scene after everything solid: JavaFX blends a see-through surface
 * with whatever was drawn before it, so they're drawn last, farthest first.
 */
final class Field3D {
    private static final int RED = 0xE53935, BLUE = 0x1E88E5;
    private static final int ALUMINUM = 0xB4BAC0, POLYCARBONATE = 0xDCE8EE;
    // Heights of the soft shadows: above the tape on the tiles, a robot's above the balls'
    // (each one drawn over the floor and what's below it).
    static final double BALL_SHADOW_Z = 0.0026, ROBOT_SHADOW_Z = 0.0030;

    final Group root = new Group();
    /** See-through things; the view adds this after the robots. */
    final Group clear = new Group();
    private final Group shadows = new Group();
    private final Group pieces = new Group();
    private final Map<Integer, Sphere> pieceNodes = new HashMap<>();
    private final Map<Integer, MeshView> shadowNodes = new HashMap<>();
    private final Group[] hiveGroups = new Group[2];
    private final Rotate[] hiveTilt = {new Rotate(0, Rotate.X_AXIS), new Rotate(0, Rotate.X_AXIS)};
    private final Rotate[] hiveTiltClear = {new Rotate(0, Rotate.X_AXIS), new Rotate(0, Rotate.X_AXIS)};
    private final Group[] hiveContents = {new Group(), new Group()};
    private final long[] lastHiveContents = {Long.MIN_VALUE, Long.MIN_VALUE};
    private final Group[] flowerStacks = new Group[4];
    private final List<List<Integer>> lastFlowerStacks = new ArrayList<>(List.of(List.of(), List.of(), List.of(), List.of()));
    private final Group rays = new Group();
    /** What robot code draws on the FTC Dashboard / Panels field, on the tiles. */
    final Overlay3D overlay = new Overlay3D();
    private final boolean biobuzz;
    // Things the follow camera can end up behind: drawn as a wire outline ("x-ray") while they
    // really block its view of the robot, solid again once they're clearly out of the way.
    private final List<Occluder> occluders = new ArrayList<>();
    private final java.util.Set<Node> keepSolid = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private long lastFrame;
    static final double CATCH = 0.02, LET_GO = 0.12; // m: blocks within 2 cm of the line, stops past 12 (no flicker)
    static final double HOLD = 0.4;                  // s: stays an outline at least this long

    /** A tube from a to b (radius r), or, with a == null, the space the node takes up. */
    private static final class Occluder {
        final Node node;
        final double[] a, b;
        final double r;
        final List<Node> also = new ArrayList<>(); // drawn elsewhere (see-through parts), outlined with it
        boolean xray;
        double since; // s in its current state

        Occluder(Node node, double[] a, double[] b, double r) {
            this.node = node;
            this.a = a;
            this.b = b;
            this.r = r;
        }
    }

    private int venueNodes; // root's first children: the carpet and stands around the field
    Stadium stadium;
    private Node drape; // in place of the stands when they're turned off
    private long lastAnimated;

    /** The height of the tallest thing on the field (m), walls and season elements, not the venue. */
    double structureTop() {
        double top = 0;
        List<Node> kids = root.getChildren();
        for (int i = venueNodes; i < kids.size(); i++) top = Math.max(top, kids.get(i).getBoundsInParent().getMaxZ());
        return top;
    }

    Field3D(FieldConfig field) {
        biobuzz = "biobuzz".equalsIgnoreCase(field.season());
        double half = field.size() / 2;
        int tiles = (int) Math.round(field.size() / field.tile());
        buildVenue(field.size());
        venueNodes = root.getChildren().size();
        // The foam tiles: one picture, with the seams and the shade around what stands on them.
        root.getChildren().add(quad(-half, -half, half, half, 0, 1,
                Shapes.textured(Textures.tiles(field.size(), tiles, contacts(field), pools()), 0x0A0A0A, 4)));
        // Perimeter walls (12 in tall): clear polycarbonate between aluminum rails (AndyMark am-0481b).
        double wt = 1 * IN;
        for (Group wall : new Group[]{
                wall(0, half + wt / 2, field.size() + 2 * wt, true, true),
                wall(0, -half - wt / 2, field.size() + 2 * wt, true, true),
                wall(half + wt / 2, 0, field.size(), false, false),
                wall(-half - wt / 2, 0, field.size(), false, false)}) {
            clear.getChildren().add(wall);
            occluders.add(new Occluder(wall, null, null, 0));
        }
        for (FieldConfig.Shape o : field.obstacles()) {
            double h = 6 * IN;
            Node n = o.shape().equals("circle")
                    ? cylinderAlong('z', o.x(), o.y(), h / 2, o.diameter() / 2, h, plastic(o.color()))
                    : box(o.x(), o.y(), h / 2, o.width(), o.height(), h, plastic(o.color()));
            if (!o.shape().equals("circle")) n.getTransforms().add(new Rotate(Math.toDegrees(o.heading()), Rotate.Z_AXIS));
            root.getChildren().add(n);
        }
        if (biobuzz) buildBioBuzz();
        root.getChildren().addAll(shadows, overlay.root, pieces, rays);
    }

    /**
     * A tag under a CELL, in the HIVE's own frame, facing down: its top toward the pivot, its right
     * side toward side x (as you look up at it), with the white margin around the black square.
     */
    private static MeshView tag(int id, double x, double u, double w, int side) {
        double h = TAG_SIZE / 2 * 10 / 8; // the black square is 8 of the picture's 10 cells
        TriangleMesh mesh = new TriangleMesh();
        float fw = (float) w;
        // top-left, top-right, bottom-right, bottom-left as seen from below
        mesh.getPoints().addAll(
                (float) (x - side * h), (float) (u - side * h), fw,
                (float) (x + side * h), (float) (u - side * h), fw,
                (float) (x + side * h), (float) (u + side * h), fw,
                (float) (x - side * h), (float) (u + side * h), fw);
        mesh.getTexCoords().addAll(0, 0, 1, 0, 1, 1, 0, 1);
        mesh.getFaces().addAll(0, 0, 1, 1, 2, 2, 0, 0, 2, 2, 3, 3);
        MeshView v = new MeshView(mesh);
        v.setCullFace(CullFace.NONE);
        PhongMaterial m = new PhongMaterial(Color.WHITE);
        m.setDiffuseMap(Textures.aprilTag(id));
        m.setSelfIlluminationMap(Textures.aprilTag(id)); // printed white reads bright under the CELL
        m.setSpecularColor(Color.BLACK);
        v.setMaterial(m);
        return v;
    }

    // ------------------------------------------------------------------ the venue and the field's edge

    /**
     * Where the field stands: carpet a tile's thickness below the foam, and round it the stands,
     * full of people (Stadium), or black drape when they're turned off.
     */
    private void buildVenue(double size) {
        double floor = -0.016, c = Math.max(9, size / 2 + 7);
        root.getChildren().add(quad(-c, -c, c, c, floor, 2 * c / 0.9, Shapes.textured(Textures.carpet(), 0x000000, 1)));
        stadium = new Stadium(size / 2);
        // Black pipe-and-drape for when the stands are turned off, drawn only from inside so a
        // camera pulled back past it still sees the field.
        drape = Shapes.ring(6.2, floor, 3.2, 72, 14, Shapes.textured(Textures.drape(), 0x0C0C0C, 6));
        drape.setVisible(false);
        root.getChildren().addAll(stadium.root, drape);
        double half = size / 2, tape = 2 * IN;
        for (String a : new String[]{"red", "blue"}) {
            int color = a.equals("red") ? RED : BLUE;
            // The alliance area outside the wall, where the drivers stand: tinted carpet and tape.
            double ax = a.equals("red") ? -half - 30 * IN : half + 30 * IN, w = 50 * IN, d = 97 * IN;
            PhongMaterial tint = new PhongMaterial(Color.WHITE.interpolate(Color.rgb((color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF), 0.45));
            tint.setDiffuseMap(Textures.carpet());
            tint.setSpecularColor(Color.BLACK);
            root.getChildren().add(quad(ax - w / 2, -d / 2, ax + w / 2, d / 2, floor + 0.0004, d / 0.9, tint));
            double z = floor + 0.001;
            root.getChildren().addAll(
                    box(ax - w / 2 + tape / 2, 0, z, tape, d, 0.0012, matte(color)),
                    box(ax + w / 2 - tape / 2, 0, z, tape, d, 0.0012, matte(color)),
                    box(ax, -d / 2 + tape / 2, z, w, tape, 0.0012, matte(color)),
                    box(ax, d / 2 - tape / 2, z, w, tape, 0.0012, matte(color)));
        }
    }

    /**
     * One side of the perimeter, centered at (x, y): aluminum rails along the bottom and top,
     * a clear polycarbonate sheet between them, uprights where its three panels meet and, on
     * the walls along x, the hinged corner posts.
     */
    private static Group wall(double x, double y, double length, boolean alongX, boolean corners) {
        double t = 1 * IN, bottom = -0.016, top = 12 * IN, rail = 1.0 * IN, panel = 0.12 * IN;
        Group g = new Group();
        double sx = alongX ? length : t, sy = alongX ? t : length;
        var alu = metal(ALUMINUM);
        double railLow = rail - bottom;
        g.getChildren().addAll(
                box(x, y, bottom + railLow / 2, sx, sy, railLow, alu),
                box(x, y, top - rail / 2, sx, sy, rail, alu));
        for (int k : new int[]{-1, 1}) {
            double along = k * length / 6;
            g.getChildren().add(box(alongX ? x + along : x, alongX ? y : y + along, top / 2, alongX ? 0.8 * IN : 1.1 * t, alongX ? 1.1 * t : 0.8 * IN, top - 2 * rail, alu));
            if (corners) g.getChildren().add(box(x + k * (length / 2 - 0.7 * IN), y, (bottom + top + 0.25 * IN) / 2, 1.4 * IN, 1.4 * IN, top + 0.25 * IN - bottom, alu));
        }
        g.getChildren().add(box(x, y, top / 2, alongX ? length : panel, alongX ? panel : length, top - 2 * rail, clear(POLYCARBONATE, 0.13)));
        return g;
    }

    /** Footprints of what stands on the tiles: {x0, y0, x1, y1, darkness, reach}, shaded into them. */
    private List<double[]> contacts(FieldConfig field) {
        List<double[]> out = new ArrayList<>();
        for (FieldConfig.Shape o : field.obstacles()) {
            double rx = o.shape().equals("circle") ? o.diameter() / 2 : Math.hypot(o.width(), o.height()) / 2;
            double ry = o.shape().equals("circle") ? o.diameter() / 2 : rx;
            if (!o.shape().equals("circle") && Math.abs(Math.sin(2 * o.heading())) < 1e-6) {
                boolean turned = Math.abs(Math.sin(o.heading())) > 0.5;
                rx = (turned ? o.height() : o.width()) / 2;
                ry = (turned ? o.width() : o.height()) / 2;
            }
            out.add(new double[]{o.x() - rx, o.y() - ry, o.x() + rx, o.y() + ry, 0.4, 0.9 * IN});
        }
        if (!biobuzz) return out;
        for (int sx : new int[]{-1, 1}) {
            double x = sx * (FRAME_HALF_WIDTH - FRAME_FOOT_THICKNESS / 2);
            out.add(new double[]{x - FRAME_FOOT_THICKNESS / 2, -FRAME_HALF_DEPTH, x + FRAME_FOOT_THICKNESS / 2, FRAME_HALF_DEPTH, 0.45, 0.9 * IN});
            double ax0 = sx * FOOT_A[0], ax1 = sx * FOOT_A[1], ay0 = sx * FOOT_A[2], ay1 = sx * FOOT_A[3];
            out.add(new double[]{Math.min(ax0, ax1), Math.min(ay0, ay1), Math.max(ax0, ax1), Math.max(ay0, ay1), 0.45, 0.9 * IN});
        }
        for (double[] f : FLOWERS) {
            boolean hWall = f[3] != 0;
            double bx = f[0] + f[2] * FLOWER_BASE_DEPTH / 2, by = f[1] + f[3] * FLOWER_BASE_DEPTH / 2;
            double hx = (hWall ? FLOWER_BASE_ALONG_WALL : FLOWER_BASE_DEPTH) / 2, hy = (hWall ? FLOWER_BASE_DEPTH : FLOWER_BASE_ALONG_WALL) / 2;
            out.add(new double[]{bx - hx, by - hy, bx + hx, by + hy, 0.5, 1.0 * IN});
        }
        return out;
    }

    /** Broad shade under the HIVES, high above the tiles. */
    private List<double[]> pools() {
        return biobuzz ? List.of(new double[]{0, 0, 0.62, 0.62, 0.09}) : List.of();
    }

    // ------------------------------------------------------------------ BIOBUZZ structures

    private void buildBioBuzz() {
        double tape = 0.04 * IN, tw = 1 * IN;
        for (String a : new String[]{"red", "blue"}) {
            int color = a.equals("red") ? RED : BLUE;
            double[] z = loadingZone(a);
            // LOADING ZONE tape: the two edges away from the walls.
            double innerX = a.equals("red") ? z[1] : z[0];
            var vinyl = Shapes.plastic(color);
            root.getChildren().add(box(innerX, (z[2] + z[3]) / 2, tape, tw, z[3] - z[2], tape * 2, vinyl));
            root.getChildren().add(box((z[0] + z[1]) / 2, z[2], tape, z[1] - z[0], tw, tape * 2, vinyl));
            root.getChildren().add(box((z[0] + z[1]) / 2, z[3], tape, z[1] - z[0], tw, tape * 2, vinyl));
            double[] g = garden(a);
            root.getChildren().add(box((g[0] + g[1]) / 2, (g[2] + g[3]) / 2, tape, g[1] - g[0], g[3] - g[2], tape * 2, vinyl));
        }
        // HIVE frame (CAD): foot bars, Frame Foot A, legs leaning in to the top bar, ACM panels.
        var steel = metal(0x98A3AB);
        for (int sx : new int[]{-1, 1}) {
            double x = sx * (FRAME_HALF_WIDTH - FRAME_FOOT_THICKNESS / 2);
            root.getChildren().add(box(x, 0, 1.075 * IN, FRAME_FOOT_THICKNESS, 2 * FRAME_HALF_DEPTH, 2.15 * IN, steel));
            root.getChildren().add(box(sx * (FOOT_A[0] + FOOT_A[1]) / 2, sx * (FOOT_A[2] + FOOT_A[3]) / 2, 1.25 * IN,
                    FOOT_A[1] - FOOT_A[0], FOOT_A[3] - FOOT_A[2], 2.5 * IN, steel));
            for (int sy : new int[]{-1, 1}) {
                double[] foot = {sx * LEG_FOOT[0], sy * LEG_FOOT[1], 0}, top = {sx * LEG_TOP[0], sy * LEG_TOP[1], LEG_TOP[2]};
                Node leg = cylinder(foot, top, LEG_THICKNESS / 2, steel);
                root.getChildren().add(leg);
                occluders.add(new Occluder(leg, foot, top, LEG_THICKNESS / 2));
            }
            Node panel = box(0, sx * 2.9 * IN, 37.05 * IN, 30.16 * IN, 0.12 * IN, 5.95 * IN, plastic(0xF2E48A));
            root.getChildren().add(panel);
            occluders.add(new Occluder(panel, null, null, 0));
        }
        double[] barA = {-12 * IN, 0, 41.45 * IN}, barB = {12 * IN, 0, 41.45 * IN};
        Node bar = cylinder(barA, barB, 0.5 * IN, steel);
        root.getChildren().add(bar);
        occluders.add(new Occluder(bar, barA, barB, 0.5 * IN));
        for (int sx : new int[]{-1, 1}) { // the axles the HIVES turn on
            root.getChildren().add(cylinder(new double[]{sx * 10.5 * IN, 0, PIVOT_HEIGHT}, new double[]{sx * 13.5 * IN, 0, PIVOT_HEIGHT}, 0.4 * IN, steel));
        }

        for (int i = 0; i < 2; i++) {
            String a = i == 0 ? "red" : "blue";
            Group hive = new Group();
            hive.getTransforms().addAll(new Translate(hiveX(a), 0, PIVOT_HEIGHT), hiveTilt[i]);
            // The CELLs' clear skins tilt with it, drawn with the other see-through things.
            Group skins = new Group();
            skins.getTransforms().addAll(new Translate(hiveX(a), 0, PIVOT_HEIGHT), hiveTiltClear[i]);
            clear.getChildren().add(skins);
            int color = i == 0 ? RED : BLUE;
            hive.getChildren().add(box(0, 0, -1 * IN, 1.5 * IN, 43 * IN, 1 * IN, metal(0x90A4AE)));
            for (int side : new int[]{-1, 1}) {
                Group cell = cell(side, color);
                Group skin = cellSkins(side);
                skins.getChildren().add(skin);
                hive.getChildren().add(cell);
                Occluder o = new Occluder(cell, null, null, 0); // each CELL on its own
                o.also.add(skin);
                occluders.add(o);
            }
            hive.getChildren().add(hiveContents[i]);
            keepSolid.add(hiveContents[i]);
            // The AprilTags under each CELL, as the simulator places them (BioBuzzSeason.tags()).
            for (int[] cluster : TAG_CLUSTERS) {
                if (cluster[1] != (i == 0 ? -1 : 1)) continue;
                int side = cluster[2];
                double u = side * (CELL_OUTER - TAG_BACK), w = (CELL_FLOOR + CELL_ROOF) / 2 - TAG_DOWN - 0.002;
                for (int k = 0; k < 4; k++) hive.getChildren().add(tag(cluster[0] + k, side * TAG_ACROSS[k], u, w, side));
            }
            hiveGroups[i] = hive;
            root.getChildren().add(hive);
        }

        // FLOWERS: base, four green pipes, black rings, gold top ring.
        for (int i = 0; i < FLOWERS.length; i++) {
            double[] f = FLOWERS[i];
            Group flower = new Group();
            double ax = f[0] + f[2] * FLOWER_AXIS_FROM_WALL, ay = f[1] + f[3] * FLOWER_AXIS_FROM_WALL;
            double bx = f[0] + f[2] * FLOWER_BASE_DEPTH / 2, by = f[1] + f[3] * FLOWER_BASE_DEPTH / 2;
            boolean hWall = f[3] != 0;
            flower.getChildren().add(box(bx, by, 0.2 * IN, hWall ? FLOWER_BASE_ALONG_WALL : FLOWER_BASE_DEPTH,
                    hWall ? FLOWER_BASE_DEPTH : FLOWER_BASE_ALONG_WALL, 0.43 * IN, plastic(0x1E1F21)));
            flower.getChildren().add(cylinderAlong('z', ax, ay, FLOWER_SCORING_BOTTOM, 2.6 * IN, 0.4 * IN, plastic(0x1E1F21)));
            for (int k = 0; k < 4; k++) {
                double ang = Math.PI / 4 + k * Math.PI / 2;
                double px = ax + Math.cos(ang) * 2.4 * IN, py = ay + Math.sin(ang) * 2.4 * IN;
                flower.getChildren().add(cylinder(new double[]{px, py, FLOWER_SCORING_BOTTOM}, new double[]{px, py, FLOWER_TOP}, 0.52 * IN, glossy(0x3E9B44)));
            }
            flower.getChildren().add(cylinderAlong('z', ax, ay, FLOWER_TOP, 2.9 * IN, 1.0 * IN, glossy(0xE0A82E)));
            flower.getChildren().add(cylinderAlong('z', ax, ay, FLOWER_TOP + 0.02 * IN, FLOWER_OPENING_RADIUS, 1.05 * IN, matte(0x151515)));
            // Wall bracket
            flower.getChildren().add(box(f[0] + f[2] * 0.6 * IN, f[1] + f[3] * 0.6 * IN, (FLOWER_TOP + FLOWER_SCORING_BOTTOM) / 2,
                    hWall ? 1 * IN : 1.2 * IN, hWall ? 1.2 * IN : 1 * IN, FLOWER_TOP - FLOWER_SCORING_BOTTOM, metal(0x9EA4AA)));
            flowerStacks[i] = new Group();
            flower.getChildren().add(flowerStacks[i]);
            keepSolid.add(flowerStacks[i]);
            root.getChildren().add(flower);
            occluders.add(new Occluder(flower, null, null, 0));
        }
    }

    /** One CELL's clear panels in the HIVE's own frame: bottom, top and back wall. */
    private static Group cellSkins(int side) {
        double inner = CELL_INNER, outer = CELL_OUTER, bottom = CELL_FLOOR, top = CELL_ROOF, w = 2 * CELL_HALF_WIDTH;
        double cy = side * (inner + outer) / 2, len = outer - inner, panel = 0.12 * IN;
        var skin = clear(POLYCARBONATE, 0.22);
        return new Group(
                box(0, cy, bottom, w, len, panel, skin),
                box(0, cy, top, w, len, panel, skin),
                box(0, side * inner, (top + bottom) / 2, w, panel, top - bottom, skin));
    }

    /** One CELL's solid frame in the HIVE's own frame (pivot at the origin; arm along y; "up" is +z when level). */
    private Group cell(int side, int color) {
        Group g = new Group();
        double inner = CELL_INNER, outer = CELL_OUTER, bottom = CELL_FLOOR, top = CELL_ROOF, w = 2 * CELL_HALF_WIDTH;
        double cy = side * (inner + outer) / 2, len = outer - inner;
        // Colored ribs framing the outer opening and the back.
        var rib = plastic(color);
        for (double y : new double[]{side * inner, side * outer}) {
            g.getChildren().addAll(
                    box(-w / 2, y, (top + bottom) / 2, 0.6 * IN, 0.6 * IN, top - bottom, rib),
                    box(w / 2, y, (top + bottom) / 2, 0.6 * IN, 0.6 * IN, top - bottom, rib),
                    box(0, y, top, w, 0.6 * IN, 0.6 * IN, rib),
                    box(0, y, bottom, w, 0.6 * IN, 0.6 * IN, rib));
        }
        for (double x : new double[]{-w / 2, w / 2}) {
            g.getChildren().addAll(box(x, cy, top, 0.6 * IN, len, 0.6 * IN, rib), box(x, cy, bottom, 0.6 * IN, len, 0.6 * IN, rib));
        }
        return g;
    }

    // ------------------------------------------------------------------ the camera's view

    /**
     * Every frame, with where the camera is. When following (robot != null), whatever really
     * stands between the camera and the robot is drawn as a wire outline so the robot stays in
     * view; it turns solid again once it's clearly out of the way and has been an outline a
     * moment, so nothing flickers as the camera moves.
     */
    void cameraMoved(double[] eye, double[] robot) {
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        cameraMoved(eye, robot, dt);
    }

    void cameraMoved(double[] eye, double[] robot, double dt) {
        sortClear(eye);
        stadium.cameraMoved(eye, robot);
        for (Occluder o : occluders) {
            o.since += dt;
            boolean blocks = robot != null && blocks(o, eye, robot, o.xray ? LET_GO : CATCH);
            if (blocks == o.xray) continue;
            if (!blocks && robot != null && o.since < HOLD) continue; // give it a moment before it comes back
            o.xray = blocks;
            o.since = 0;
            setXray(o.node, blocks);
            for (Node n : o.also) setXray(n, blocks);
        }
    }

    /**
     * Farthest first: a see-through thing hides whatever is drawn after it behind it, so the
     * ones nearer the camera have to come later.
     */
    private void sortClear(double[] eye) {
        List<Node> now = new ArrayList<>(clear.getChildren());
        List<Node> sorted = new ArrayList<>(now);
        Map<Node, Double> far = new java.util.IdentityHashMap<>();
        for (Node n : sorted) {
            javafx.geometry.Bounds b = n.getBoundsInParent();
            double dx = Math.max(Math.max(b.getMinX() - eye[0], eye[0] - b.getMaxX()), 0);
            double dy = Math.max(Math.max(b.getMinY() - eye[1], eye[1] - b.getMaxY()), 0);
            double dz = Math.max(Math.max(b.getMinZ() - eye[2], eye[2] - b.getMaxZ()), 0);
            // Nearest point of its box, then its middle to break ties (a camera inside the field is 0 from every wall).
            double cx = (b.getMinX() + b.getMaxX()) / 2 - eye[0], cy = (b.getMinY() + b.getMaxY()) / 2 - eye[1], cz = (b.getMinZ() + b.getMaxZ()) / 2 - eye[2];
            far.put(n, Math.sqrt(dx * dx + dy * dy + dz * dz) * 4 + Math.sqrt(cx * cx + cy * cy + cz * cz));
        }
        sorted.sort((p, q) -> Double.compare(far.get(q), far.get(p)));
        if (!sorted.equals(now)) clear.getChildren().setAll(sorted);
    }

    /** Whether this thing is drawn as an outline right now (tests). */
    boolean xrayed(Node node) {
        for (Occluder o : occluders) if (o.node == node) return o.xray;
        return false;
    }

    List<Node> occluderNodes() {
        List<Node> out = new ArrayList<>();
        for (Occluder o : occluders) out.add(o.node);
        return out;
    }

    private boolean blocks(Occluder o, double[] eye, double[] robot, double slack) {
        if (o.a != null) return segmentDistance(eye, robot, o.a, o.b) < o.r + slack;
        return hits(eye, robot, boundsInField(o.node), slack);
    }

    /** A node's bounds in field coordinates (a CELL's are inside its tilting HIVE). */
    javafx.geometry.Bounds boundsInField(Node n) {
        javafx.geometry.Bounds b = n.getBoundsInParent();
        for (javafx.scene.Parent p = n.getParent(); p != null && p != root && p != clear; p = p.getParent()) b = p.localToParent(b);
        return b;
    }

    /** Wire outline (or solid again) for every shape under n, except the pieces held in it. */
    private void setXray(Node n, boolean on) {
        if (keepSolid.contains(n)) return;
        if (n instanceof javafx.scene.shape.Shape3D shape) {
            shape.setDrawMode(on ? javafx.scene.shape.DrawMode.LINE : javafx.scene.shape.DrawMode.FILL);
            shape.setCullFace(on ? javafx.scene.shape.CullFace.NONE : javafx.scene.shape.CullFace.BACK);
        } else if (n instanceof javafx.scene.Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) setXray(c, on);
        }
    }

    /** Does the segment p-q pass through these bounds, grown by slack? */
    static boolean hits(double[] p, double[] q, javafx.geometry.Bounds b, double slack) {
        double[] min = {b.getMinX() - slack, b.getMinY() - slack, b.getMinZ() - slack};
        double[] max = {b.getMaxX() + slack, b.getMaxY() + slack, b.getMaxZ() + slack};
        double t0 = 0, t1 = 1;
        for (int i = 0; i < 3; i++) {
            double d = q[i] - p[i];
            if (Math.abs(d) < 1e-12) {
                if (p[i] < min[i] || p[i] > max[i]) return false;
                continue;
            }
            double ta = (min[i] - p[i]) / d, tb = (max[i] - p[i]) / d;
            t0 = Math.max(t0, Math.min(ta, tb));
            t1 = Math.min(t1, Math.max(ta, tb));
            if (t0 > t1) return false;
        }
        return true;
    }

    /** The closest the segments p1-q1 and p2-q2 come to each other. */
    static double segmentDistance(double[] p1, double[] q1, double[] p2, double[] q2) {
        double[] d1 = sub(q1, p1), d2 = sub(q2, p2), r = sub(p1, p2);
        double a = dot(d1, d1), e = dot(d2, d2), f = dot(d2, r), c = dot(d1, r), b = dot(d1, d2);
        double denom = a * e - b * b;
        double s = denom > 1e-12 ? clamp01((b * f - c * e) / denom) : 0;
        double t = (b * s + f) / e;
        if (t < 0) { t = 0; s = clamp01(-c / a); } else if (t > 1) { t = 1; s = clamp01((b - c) / a); }
        double[] c1 = {p1[0] + d1[0] * s, p1[1] + d1[1] * s, p1[2] + d1[2] * s};
        double[] c2 = {p2[0] + d2[0] * t, p2[1] + d2[1] * t, p2[2] + d2[2] * t};
        return Math.sqrt(dot(sub(c1, c2), sub(c1, c2)));
    }

    private static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }

    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    // ------------------------------------------------------------------ per-frame updates

    void update(Snapshot s) {
        update(List.of(s));
    }

    /** One snapshot per robot, all of the same moment (they share the game pieces and the season). */
    void update(List<Snapshot> robots) {
        Snapshot s = robots.get(0);
        updatePieces(s, robots);
        updateRays(robots);
        if (biobuzz && s.season() instanceof BioBuzzSnapshot bb) updateBioBuzz(bb);
        stadium.follow(s);
        var packet = com.acmerobotics.dashboard.FtcDashboard.getInstance().simLastPacket();
        overlay.update(packet != null ? packet.fieldOverlay() : null, com.bylazar.field.PanelsField.INSTANCE.getField().simCanvas());
    }

    /** Every frame the view is showing, paused or not (now: System.nanoTime()): the crowd moves on. */
    void animate(long now) {
        double dt = lastAnimated == 0 ? 0 : Math.min(0.1, (now - lastAnimated) / 1e9);
        lastAnimated = now;
        if (stadiumVisible()) stadium.animate(dt);
    }

    /** Shows the stands and the crowd, or (false) black drape round the field instead and nothing moving. */
    void setStadiumVisible(boolean on) {
        stadium.root.setVisible(on);
        drape.setVisible(!on);
    }

    boolean stadiumVisible() {
        return stadium.root.isVisible();
    }

    /** Whether the black drape stands round the field (the stands are turned off). */
    boolean drapeVisible() {
        return drape.isVisible();
    }

    private void updatePieces(Snapshot s, List<Snapshot> robots) {
        Set<Integer> seen = new HashSet<>();
        for (Snapshot.Piece p : s.pieces()) {
            // Pieces inside HIVES and FLOWERS are drawn by those elements, the hopper's by the robot.
            if (p.state().equals("CONTAINED") || !p.ball() || p.state().equals("HELD") && !inAClaw(robots, p)) continue;
            seen.add(p.id());
            Sphere sp = pieceNodes.get(p.id());
            if (sp == null) {
                sp = new Sphere(p.radius(), 28);
                sp.setMaterial(glossy(p.color()));
                sp.getTransforms().add(new Translate());
                sp.setMouseTransparent(true);
                pieceNodes.put(p.id(), sp);
                pieces.getChildren().add(sp);
                MeshView sh = quad(-0.5, -0.5, 0.5, 0.5, 0, 1, ballShade(0));
                sh.getTransforms().addAll(new Translate(), new Scale());
                sh.setMouseTransparent(true);
                shadowNodes.put(p.id(), sh);
                shadows.getChildren().add(sh);
            }
            double z = Math.max(p.radius(), p.z());
            Translate tr = (Translate) sp.getTransforms().get(0);
            tr.setX(p.x());
            tr.setY(p.y());
            tr.setZ(z);
            placeShadow(shadowNodes.get(p.id()), p.x(), p.y(), z, p.radius());
        }
        pieceNodes.entrySet().removeIf(e -> {
            if (seen.contains(e.getKey())) return false;
            pieces.getChildren().remove(e.getValue());
            shadows.getChildren().remove(shadowNodes.remove(e.getKey()));
            return true;
        });
    }

    private static final int SHADE_LEVELS = 6;
    private static final PhongMaterial[] BALL_SHADES = new PhongMaterial[SHADE_LEVELS];

    /** A ball's shadow, from darkest (0, on the floor) to faintest (high in the air). */
    private static PhongMaterial ballShade(int level) {
        if (BALL_SHADES[level] == null) BALL_SHADES[level] = Shapes.shade(Textures.blob(0.78 * (1 - level / (double) SHADE_LEVELS)));
        return BALL_SHADES[level];
    }

    /**
     * Under a ball at height z (its center), where the overhead light puts it: a small dark
     * patch while it sits on the floor, bigger, fainter and off to the side as it flies.
     */
    static void placeShadow(MeshView sh, double x, double y, double z, double r) {
        double up = Math.max(0, z - r);
        double size = 2 * r * (2.2 + 1.6 * up);
        Translate tr = (Translate) sh.getTransforms().get(0);
        Scale sc = (Scale) sh.getTransforms().get(1);
        tr.setX(x + z * Shapes.SUN[0] / -Shapes.SUN[2]);
        tr.setY(y + z * Shapes.SUN[1] / -Shapes.SUN[2]);
        tr.setZ(BALL_SHADOW_Z);
        sc.setX(size);
        sc.setY(size);
        int level = (int) Math.min(SHADE_LEVELS - 1, Math.floor(up / 0.25));
        if (sh.getMaterial() != ballShade(level)) sh.setMaterial(ballShade(level));
    }

    /** A held piece in some robot's claw (drawn where the simulation has it), not one in a hopper. */
    private static boolean inAClaw(List<Snapshot> robots, Snapshot.Piece p) {
        for (Snapshot s : robots) if (inClaw(s, p)) return true;
        return false;
    }

    private static boolean inClaw(Snapshot s, Snapshot.Piece p) {
        if (s.parts() == null) return false;
        double c = Math.cos(s.heading()), sn = Math.sin(s.heading());
        for (org.ftctestbed.sim.game.RobotPieceHandling.ClawState claw : s.parts().claws()) {
            if (claw.heldColor() == -1) continue;
            double cx = s.x() + c * claw.x() - sn * claw.y(), cy = s.y() + sn * claw.x() + c * claw.y();
            if (Math.hypot(p.x() - cx, p.y() - cy) < 0.03) return true;
        }
        return false;
    }

    private void updateRays(List<Snapshot> robots) {
        rays.getChildren().clear();
        for (Snapshot s : robots) for (Snapshot.Ray r : s.rays()) {
            if (Math.hypot(r.x1() - r.x0(), r.y1() - r.y0()) < 0.01) continue;
            var c = cylinder(new double[]{r.x0(), r.y0(), 3 * IN}, new double[]{r.x1(), r.y1(), 3 * IN}, 0.15 * IN,
                    mat(0xFF5252, r.hit() ? 0.9 : 0.3));
            c.setMouseTransparent(true);
            rays.getChildren().add(c);
        }
    }

    private void updateBioBuzz(BioBuzzSnapshot bb) {
        for (int i = 0; i < 2 && i < bb.hives().size(); i++) {
            BioBuzzSnapshot.HiveState h = bb.hives().get(i);
            hiveTilt[i].setAngle(h.tiltDegrees());
            hiveTiltClear[i].setAngle(h.tiltDegrees());
            // Pieces sit (and roll out) in the HIVE's own frame, which the tilt above turns.
            long sig = h.contentsAt().size();
            for (double[] c : h.contentsAt()) sig = sig * 31 + Math.round(c[1] * 1000) * 7 + Math.round(c[0] * 1000) + (long) c[4];
            if (sig == lastHiveContents[i]) continue;
            lastHiveContents[i] = sig;
            hiveContents[i].getChildren().clear();
            for (double[] c : h.contentsAt()) {
                hiveContents[i].getChildren().add(sphere(c[0], c[1], c[2], c[3] * 0.98, glossy((int) c[4])));
            }
        }
        for (int i = 0; i < 4 && i < bb.flowers().size(); i++) {
            BioBuzzSnapshot.FlowerState f = bb.flowers().get(i);
            if (f.stack().equals(lastFlowerStacks.get(i))) continue;
            lastFlowerStacks.set(i, new ArrayList<>(f.stack()));
            flowerStacks[i].getChildren().clear();
            double z = FLOWER_LOWER_RING_TOP;
            for (int c : f.stack()) {
                double r = c == POLLEN_COLOR ? POLLEN_DIAMETER / 2 : NECTAR_DIAMETER / 2;
                flowerStacks[i].getChildren().add(sphere(f.axisX(), f.axisY(), z + r, r * 0.98, glossy(c)));
                z += 2 * r;
            }
        }
    }
}
