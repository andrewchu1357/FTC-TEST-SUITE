import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BuzzBot as a real robot, built from goBILDA and REV parts: writes its 3D model
 * (config/robot.glb), which model parts move with which devices (config/robot.model.jsonc),
 * and its bill of materials with the mass properties that config/robot.jsonc uses
 * (docs/buzzbot-build.md). Dimensions and masses come from the makers' product pages and
 * drawings (linked in the bill of materials); anything estimated is marked.
 *
 *   java tools/BuzzBotCad.java          (from the project folder)
 *
 * Robot frame: x forward, y left, z up, millimeters, origin at the robot's center on the floor.
 * The model is written in glTF's frame (y up); robot.model.jsonc turns it back.
 */
public class BuzzBotCad {

    // ================================================================== sources

    static final String STRAFER = "https://www.gobilda.com/strafer-chassis-kit-104mm-gripforce-mecanum-wheels/";
    static final String GRIPFORCE = "https://www.gobilda.com/gripforce-mecanum-wheel-set-o104mm-40a-durometer-rollers/";
    static final String YJ312 = "https://www.gobilda.com/5203-series-yellow-jacket-planetary-gear-motor-19-2-1-ratio-24mm-length-8mm-rex-shaft-312-rpm-3-3-5v-encoder/";
    static final String YJ435 = "https://www.gobilda.com/5203-series-yellow-jacket-planetary-gear-motor-13-7-1-ratio-24mm-length-8mm-rex-shaft-435-rpm-3-3-5v-encoder/";
    static final String YJ1150 = "https://www.gobilda.com/5203-series-yellow-jacket-planetary-gear-motor-5-2-1-ratio-24mm-length-8mm-rex-shaft-1150-rpm-3-3-5v-encoder/";
    static final String YJ6000 = "https://www.gobilda.com/5203-series-yellow-jacket-motor-1-1-ratio-24mm-length-8mm-rex-shaft-6000-rpm-3-3-5v-encoder/";
    static final String CHANNEL = "https://www.gobilda.com/1120-series-u-channel-17-hole-432mm-length/";
    static final String CONTROL_HUB = "https://docs.revrobotics.com/duo-control/control-system-overview/control-hub-basics";
    static final String EXPANSION_HUB = "https://docs.revrobotics.com/duo-control/control-system-overview/expansion-hub-basics";
    static final String BATTERY = "https://www.revrobotics.com/rev-31-1302/";
    static final String SWITCH = "https://www.revrobotics.com/rev-31-1387/";
    static final String VIPER_KIT = "https://www.gobilda.com/4-stage-viper-slide-kit-belt-driven-240mm-slides/";
    static final String VIPER_SLIDE = "https://www.gobilda.com/steel-viper-slide-10-ball-carriage-240mm-length-174mm-travel/";
    static final String SERVO_SPEED = "https://www.gobilda.com/2000-series-dual-mode-servo-25-3-speed/";
    static final String SERVO_SUPER = "https://www.gobilda.com/2000-series-dual-mode-servo-25-4-super-speed/";
    static final String GECKO96 = "https://www.gobilda.com/gripforce-gecko-wheel-14mm-bore-96mm-diameter/";
    static final String GECKO48 = "https://www.gobilda.com/gripforce-gecko-wheel-8mm-rex-bore-48mm-diameter/";
    static final String FLYWHEEL = "https://www.gobilda.com/steel-flywheel-82mm-diameter-152g-1651-g-cm/";
    static final String POD = "https://www.gobilda.com/4-bar-odometry-pod-32mm-wheel/";
    static final String COLOR = "https://www.revrobotics.com/rev-31-1557/";
    static final String MAG_SWITCH = "https://www.revrobotics.com/rev-31-1462/";

    // ================================================================== bill of materials

    /** One kind of part. note != null marks a mass that is estimated (and how). */
    static final class Bom {
        final String group, name, sku, source, note;
        final double massG;
        double qty;

        Bom(String group, String name, String sku, double massG, String source, String note) {
            this.group = group;
            this.name = name;
            this.sku = sku;
            this.massG = massG;
            this.source = source;
            this.note = note;
        }
    }

    static final List<Bom> BOM = new ArrayList<>();

    static Bom bom(String group, String name, String sku, double massG, String source, String note) {
        Bom b = new Bom(group, name, sku, massG, source, note);
        BOM.add(b);
        return b;
    }

    static final double POLYCARB = 1.20e-3, ALUMINUM = 2.70e-3; // g/mm^3

    static final Bom
            RAIL = bom("Chassis", "1120 Series U-Channel (17 hole, 432 mm): side rails", "1120-0017-0432", 284, CHANNEL, null),
            CROSSBAR = bom("Chassis", "1120 Series U-Channel (10 hole, 264 mm): crossbars", "1120-0010-0264", 174, CHANNEL,
                    "the 432 mm channel's 284 g scaled to 264 mm"),
            DRIVE_MOTOR = bom("Chassis", "5203 Yellow Jacket motor, 19.2:1, 312 rpm: drive", "5203-2402-0019", 437, YJ312, null),
            WHEEL = bom("Chassis", "GripForce mecanum wheel, 104 mm, 40A rollers", "3625-0202-0104", 236, GRIPFORCE, null),
            STRAFER_HW = bom("Chassis", "Strafer hardware: miter gears, hub-shafts, bearings, quad blocks, screws, cables", "3209-0001-0007", 662, STRAFER,
                    "the kit's 4269 g assembled, less the channels, motors and wheels listed above"),
            CONTROL = bom("Electronics", "REV Control Hub", "REV-31-1595", 209, CONTROL_HUB, null),
            EXPANSION = bom("Electronics", "REV Expansion Hub (8 motors need a second hub)", "REV-31-1153", 209, EXPANSION_HUB, null),
            BATT = bom("Electronics", "REV 12 V Slim Battery, 3000 mAh NiMH (20 A fuse)", "REV-31-1302", 567, BATTERY, null),
            POWER_SWITCH = bom("Electronics", "REV Switch Cable and Bracket", "REV-31-1387", 30, SWITCH, "not listed; a switch, bracket and two XT30 leads"),
            WIRING = bom("Electronics", "Wiring: motor, servo, sensor, encoder and power cables", "", 250, null, "about 30 cables"),
            DECK = bom("Electronics", "Electronics deck, 2.5 mm polycarbonate, 200 x 360 mm", "", 200 * 360 * 2.5 * POLYCARB, null,
                    "from its size (polycarbonate 1.20 g/cm³)"),
            TRAY = bom("Electronics", "Battery tray, 2 mm polycarbonate", "", 117 * 120 * 2 * POLYCARB, null, "from its size"),
            INTAKE_MOTOR = bom("Intake", "5203 Yellow Jacket motor, 5.2:1, 1150 rpm: intake", "5203-2402-0005", 399, YJ1150, null),
            GECKO_SMALL = bom("Intake", "GripForce Gecko wheel, 48 mm, 8 mm REX bore", "3613-4008-0048", 16, GECKO48, null),
            REX_SHAFT = bom("Intake", "8 mm REX shaft, 364 mm: roller axles", "2100 series", 364 * 0.39, null, "steel 8 mm REX, about 0.39 g/mm"),
            INTAKE_PLATE = bom("Intake", "Intake side plate, 3 mm aluminum, pocketed", "", 70, null, "from its size, 40% pocketed"),
            RAMP = bom("Intake", "Ramp, 2 mm polycarbonate", "", 205 * 250 * 2 * POLYCARB, null, "from its size"),
            BELT = bom("Intake", "GT2 belt and pulleys", "", 50, null, "two pulleys and a belt"),
            HOPPER = bom("Hopper", "Hopper box, 1.5 mm polycarbonate, with standoffs", "", 166 * 166 * 1.5 * POLYCARB + 3 * 166 * 82 * 1.5 * POLYCARB + 40, null,
                    "from its size, plus 40 g of standoffs"),
            COLOR_SENSOR = bom("Hopper", "REV Color Sensor V3", "REV-31-1557", 8, COLOR, "not listed; a small circuit board in a plastic case"),
            SUPER_SERVO = bom("Hopper and launcher", "2000 Series Dual Mode Servo 25-4 (Super Speed): kicker, transfer", "2000-0025-0004", 58, SERVO_SUPER, null),
            SHOOTER_MOTOR = bom("Launcher", "5203 Yellow Jacket motor, 1:1, 6000 rpm: flywheel", "5203-2402-0001", 391, YJ6000, null),
            GECKO_BIG = bom("Launcher", "GripForce Gecko wheel, 96 mm, 30A", "3613-0014-0096", 105, GECKO96, null),
            STEEL_FLYWHEEL = bom("Launcher", "Steel flywheel, 82 mm, 1651 g·cm²", "3628-0032-0082", 152, FLYWHEEL, null),
            LAUNCHER_PLATE = bom("Launcher", "Launcher side plate, 3 mm aluminum, pocketed", "", 150, null, "from its size, 50% pocketed"),
            HOOD = bom("Launcher", "Hood, 2 mm polycarbonate, with standoffs", "", 45, null, "from its size"),
            SHAFT_PARTS = bom("Launcher", "Flywheel shaft, hubs and bearings", "", 105, null, "170 mm of REX shaft, two hubs, two bearings"),
            KICKER = bom("Launcher", "Kicker paddle and transfer shaft", "", 35, null, "small printed parts"),
            LIFT_MOTOR = bom("Lift", "5203 Yellow Jacket motor, 13.7:1, 435 rpm: lift", "5203-2402-0014", 438, YJ435, null),
            VIPER = bom("Lift", "4 Stage Viper-Slide Kit (belt-driven, 240 mm slides), less the 4 slides", "3210-0004-0004", 1220 - 4 * 170, VIPER_KIT,
                    null),
            SLIDE_RAIL = bom("Lift", "Steel Viper-Slide 240 mm: outer rail (of the kit's 4)", "2500-0014-0240", 100, VIPER_SLIDE, "the slide's 170 g split 100 g rail, 70 g carriage"),
            SLIDE_CARRIAGE = bom("Lift", "Steel Viper-Slide 240 mm: carriage (of the kit's 4)", "2500-0014-0240", 70, VIPER_SLIDE, "the slide's 170 g split 100 g rail, 70 g carriage"),
            SPEED_SERVO = bom("Lift", "2000 Series Dual Mode Servo 25-3 (Speed): claw", "2000-0025-0003", 58, SERVO_SPEED, null),
            CLAW = bom("Lift", "Claw fingers and bracket, aluminum", "", 130, null, "from their size"),
            REAR_PLATE = bom("Lift", "Rear mounting plate, 3 mm aluminum", "", 40 * 360 * 3 * ALUMINUM * 0.6, null, "from its size, 40% pocketed"),
            LIMIT = bom("Lift", "REV Magnetic Limit Switch and magnet", "REV-31-1462", 6, MAG_SWITCH, "not listed"),
            PODS = bom("Odometry", "goBILDA 4-Bar Odometry Pod (32 mm wheel)", "3110-0001-0002", 84, POD, null),
            SIGNS = bom("Other", "Alliance robot signs (2) with standoffs", "", 2 * 216 * 84 * 3 * POLYCARB + 20, null, "3 mm polycarbonate panels"),
            FASTENERS = bom("Other", "Brackets and screws for the mechanisms", "", 250, null, "about 150 screws and a dozen brackets");

    // ================================================================== materials

    record Mat(String name, int srgb, double alpha, double metallic, double roughness) {}

    static final Mat ALU = new Mat("aluminum", 0xAAB2BA, 1, 0.85, 0.4),
            HOLE = new Mat("hole", 0x2A2E33, 1, 0, 0.9),
            STEEL = new Mat("steel", 0xB4BAC0, 1, 1, 0.3),
            ZINC = new Mat("zinc-plated steel", 0xB9BFC4, 1, 0.9, 0.45),
            YELLOW = new Mat("goBILDA yellow", 0xF2B600, 1, 0.2, 0.45),
            GEARBOX = new Mat("gearbox", 0x4A4E54, 1, 0.8, 0.4),
            PLASTIC = new Mat("black plastic", 0x1E2023, 1, 0, 0.55),
            RUBBER = new Mat("rubber", 0x232528, 1, 0, 0.95),
            CORE = new Mat("wheel core", 0x3A3D42, 1, 0.3, 0.5),
            HUB = new Mat("REV hub", 0x26282B, 1, 0, 0.5),
            ORANGE = new Mat("REV orange", 0xF37021, 1, 0, 0.5),
            BATTERY_WRAP = new Mat("battery wrap", 0x161616, 1, 0, 0.7),
            LABEL = new Mat("label", 0xF4C430, 1, 0, 0.6),
            DECK_MAT = new Mat("dark polycarbonate", 0x2C3238, 1, 0, 0.3),
            CLEAR = new Mat("clear polycarbonate", 0xB8CCDA, 0.3, 0, 0.1),
            SIGN = new Mat("alliance sign", 0x9E9E9E, 1, 0, 0.6),
            WHITE = new Mat("white", 0xECECEC, 1, 0, 0.6),
            GREEN = new Mat("circuit board", 0x1B5E20, 1, 0, 0.6);

    // ================================================================== geometry

    /** Triangles in one material. */
    static final class Geo {
        float[] p = new float[3 * 64];
        int np;
        int[] idx = new int[96];
        int ni;

        int vert(double[] q) {
            if (np * 3 + 3 > p.length) p = java.util.Arrays.copyOf(p, p.length * 2);
            p[np * 3] = (float) q[0];
            p[np * 3 + 1] = (float) q[1];
            p[np * 3 + 2] = (float) q[2];
            return np++;
        }

        void tri(int a, int b, int c) {
            if (ni + 3 > idx.length) idx = java.util.Arrays.copyOf(idx, idx.length * 2);
            idx[ni++] = a;
            idx[ni++] = b;
            idx[ni++] = c;
        }

        void quad(double[] a, double[] b, double[] c, double[] d) {
            int i = vert(a), j = vert(b), k = vert(c), l = vert(d);
            tri(i, j, k);
            tri(i, k, l);
        }
    }

    /** A named part of the model: its own triangles and mass, and the parts attached to it. */
    static final class Part {
        final String name;
        final Map<Mat, Geo> geo = new LinkedHashMap<>();
        final List<Part> kids = new ArrayList<>();
        double massG;
        double[] com, size; // optional: where its mass is, and the box it spreads over (else: its triangles' bounds)

        Part(String name) {
            this.name = name;
        }

        Geo g(Mat m) {
            return geo.computeIfAbsent(m, k -> new Geo());
        }

        Part kid(String name) {
            Part p = new Part(name);
            kids.add(p);
            return p;
        }

        Part uses(Bom b, double count) {
            massG += b.massG * count;
            b.qty += count;
            return this;
        }

        Part massAt(double[] com, double[] size) {
            this.com = com;
            this.size = size;
            return this;
        }
    }

    static double[] v(double x, double y, double z) { return new double[]{x, y, z}; }
    static double[] add(double[] a, double[] b) { return v(a[0] + b[0], a[1] + b[1], a[2] + b[2]); }
    static double[] sub(double[] a, double[] b) { return v(a[0] - b[0], a[1] - b[1], a[2] - b[2]); }
    static double[] mul(double[] a, double s) { return v(a[0] * s, a[1] * s, a[2] * s); }
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double[] cross(double[] a, double[] b) {
        return v(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]);
    }
    static double[] norm(double[] a) { return mul(a, 1 / Math.sqrt(dot(a, a))); }
    static double[] sum(double[]... vs) {
        double[] s = v(0, 0, 0);
        for (double[] x : vs) s = add(s, x);
        return s;
    }

    static final double[] X = v(1, 0, 0), Y = v(0, 1, 0), Z = v(0, 0, 1);

    /** Unit vectors e1, e2 perpendicular to a, with e1 x e2 = a. */
    static double[][] basis(double[] a) {
        a = norm(a);
        double[] h = Math.abs(a[2]) < 0.9 ? Z : X;
        double[] e1 = norm(cross(h, a));
        return new double[][]{e1, cross(a, e1)};
    }

    /** The quad center +/- a +/- b, facing a x b. */
    static void face(Geo g, double[] c, double[] a, double[] b) {
        g.quad(sum(c, mul(a, -1), mul(b, -1)), sum(c, a, mul(b, -1)), sum(c, a, b), sum(c, mul(a, -1), b));
    }

    /** A box: center and half-extent vectors with u x v pointing along w. */
    static void box(Geo g, double[] c, double[] u, double[] v, double[] w) {
        face(g, add(c, w), u, v);
        face(g, sub(c, w), v, u);
        face(g, add(c, u), v, w);
        face(g, sub(c, u), w, v);
        face(g, add(c, v), w, u);
        face(g, sub(c, v), u, w);
    }

    static void aabb(Geo g, double x0, double y0, double z0, double x1, double y1, double z1) {
        box(g, v((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2), v(Math.abs(x1 - x0) / 2, 0, 0), v(0, Math.abs(y1 - y0) / 2, 0), v(0, 0, Math.abs(z1 - z0) / 2));
    }

    static double[] ring(double[] c, double[][] e, double r, double ang) {
        return sum(c, mul(e[0], r * Math.cos(ang)), mul(e[1], r * Math.sin(ang)));
    }

    /** A solid of revolution from p0 to p1: profile rows {t (0..1 along the axis), radius}. Ends with a radius are capped. */
    static void lathe(Geo g, double[] p0, double[] p1, double[][] profile, int seg) {
        double[] axis = sub(p1, p0);
        double[][] e = basis(axis);
        int rows = profile.length;
        int[][] id = new int[rows][seg];
        for (int k = 0; k < rows; k++) {
            double[] c = add(p0, mul(axis, profile[k][0]));
            for (int i = 0; i < seg; i++) id[k][i] = g.vert(ring(c, e, profile[k][1], 2 * Math.PI * i / seg));
        }
        for (int k = 0; k + 1 < rows; k++) {
            for (int i = 0; i < seg; i++) {
                int j = (i + 1) % seg;
                g.tri(id[k][i], id[k][j], id[k + 1][j]);
                g.tri(id[k][i], id[k + 1][j], id[k + 1][i]);
            }
        }
        if (profile[0][1] > 0) cap(g, p0, e, profile[0][1], seg, false);
        if (profile[rows - 1][1] > 0) cap(g, add(p0, mul(axis, profile[rows - 1][0])), e, profile[rows - 1][1], seg, true);
    }

    static void cap(Geo g, double[] c, double[][] e, double r, int seg, boolean facingAxis) {
        int center = g.vert(c);
        int[] id = new int[seg];
        for (int i = 0; i < seg; i++) id[i] = g.vert(ring(c, e, r, 2 * Math.PI * i / seg));
        for (int i = 0; i < seg; i++) {
            int j = (i + 1) % seg;
            if (facingAxis) g.tri(center, id[i], id[j]);
            else g.tri(center, id[j], id[i]);
        }
    }

    static void cyl(Geo g, double[] p0, double[] p1, double r, int seg) {
        lathe(g, p0, p1, new double[][]{{0, r}, {1, r}}, seg);
    }

    /** A tube from p0 to p1 between two radii, with flat ends. */
    static void tube(Geo g, double[] p0, double[] p1, double ro, double ri, int seg) {
        double[] axis = sub(p1, p0);
        double[][] e = basis(axis);
        for (int i = 0; i < seg; i++) {
            double a = 2 * Math.PI * i / seg, b = 2 * Math.PI * (i + 1) / seg;
            double[] o0a = ring(p0, e, ro, a), o0b = ring(p0, e, ro, b), o1a = ring(p1, e, ro, a), o1b = ring(p1, e, ro, b);
            double[] i0a = ring(p0, e, ri, a), i0b = ring(p0, e, ri, b), i1a = ring(p1, e, ri, a), i1b = ring(p1, e, ri, b);
            g.quad(o0a, o0b, o1b, o1a);
            g.quad(i0a, i1a, i1b, i0b);
            g.quad(o1a, o1b, i1b, i1a);
            g.quad(o0a, i0a, i0b, o0b);
        }
    }

    /** A flat disc facing n (a hole drawn on a surface). */
    static void disc(Geo g, double[] c, double[] n, double r, int seg) {
        cap(g, c, basis(n), r, seg, true);
    }

    /**
     * A flat plate: the polygon (counter-clockwise in the ex/ey plane, star-shaped about its
     * centroid) at origin + x ex + y ey, extruded thickness t along ex x ey.
     */
    static void prism(Geo g, double[] origin, double[] ex, double[] ey, double t, double[][] poly) {
        double[] n = mul(norm(cross(ex, ey)), t);
        int k = poly.length;
        double cx = 0, cy = 0;
        for (double[] q : poly) {
            cx += q[0] / k;
            cy += q[1] / k;
        }
        double[][] bottom = new double[k][], top = new double[k][];
        for (int i = 0; i < k; i++) {
            bottom[i] = sum(origin, mul(ex, poly[i][0]), mul(ey, poly[i][1]));
            top[i] = add(bottom[i], n);
        }
        double[] cb = sum(origin, mul(ex, cx), mul(ey, cy)), ct = add(cb, n);
        int b0 = g.vert(cb), t0 = g.vert(ct);
        int[] bi = new int[k], ti = new int[k];
        for (int i = 0; i < k; i++) {
            bi[i] = g.vert(bottom[i]);
            ti[i] = g.vert(top[i]);
        }
        for (int i = 0; i < k; i++) {
            int j = (i + 1) % k;
            g.tri(t0, ti[i], ti[j]);
            g.tri(b0, bi[j], bi[i]);
            g.quad(bottom[i], bottom[j], top[j], top[i]);
        }
    }

    // ================================================================== goBILDA / REV parts

    /**
     * 1120 Series U-channel: 48 x 48 mm, 2.5 mm walls, 14 mm holes every 24 mm with 4 mm holes on
     * a 16 mm square around each. Runs from start along dir for len; its open side faces open.
     */
    static void uChannel(Part p, double[] start, double[] dir, double len, double[] open) {
        double[] a = norm(dir), o = norm(open), s = cross(a, o);
        double[] mid = add(start, mul(a, len / 2));
        Geo m = p.g(ALU), h = p.g(HOLE);
        box(m, add(mid, mul(o, -22.75)), mul(a, len / 2), mul(s, 24), mul(o, -1.25));
        for (int side : new int[]{-1, 1}) box(m, add(mid, mul(s, side * 22.75)), mul(a, len / 2), mul(s, 1.25), mul(o, -22.75));
        int holes = (int) Math.round(len / 24) - 1;
        for (int k = 1; k <= holes; k++) {
            double along = -len / 2 + 24 * k;
            double[][] faces = {{-1, 0}, {0, 1}, {0, -1}}; // web (outside: -o), then the two walls (outside: +/-s)
            for (double[] f : faces) {
                double[] n = f[0] != 0 ? mul(o, -1) : mul(s, f[1]);
                double[] across = f[0] != 0 ? s : o;
                double[] c = sum(mid, mul(a, along), mul(n, 24.3));
                disc(h, c, n, 7, 16);
                for (int sa : new int[]{-1, 1}) for (int sb : new int[]{-1, 1}) disc(h, sum(c, mul(a, 8 * sa), mul(across, 8 * sb)), n, 2, 6);
            }
        }
    }

    /**
     * 5203 Yellow Jacket motor: gearbox (36 mm), yellow can and black encoder cap, 75 mm together,
     * and the 8 mm REX output shaft (23.5 mm). face: center of the gearbox's output face.
     */
    static void yellowJacket(Part p, double[] face, double[] shaftDir, double gearboxMm) {
        double[] d = norm(shaftDir);
        double[] back = mul(d, -1);
        cyl(p.g(GEARBOX), add(face, mul(back, 0)), add(face, mul(back, gearboxMm)), 18, 20);
        cyl(p.g(GEARBOX), face, add(face, mul(d, 3)), 8, 14);
        cyl(p.g(YELLOW), add(face, mul(back, gearboxMm)), add(face, mul(back, gearboxMm + 57)), 18.75, 20);
        cyl(p.g(PLASTIC), add(face, mul(back, gearboxMm + 57)), add(face, mul(back, gearboxMm + 75)), 17.5, 20);
        cyl(p.g(STEEL), face, add(face, mul(d, 23.5)), 4, 8);
    }

    /** goBILDA 2000 series servo (40 x 20 x 38 mm, flanges, output spline toward one end). */
    static void servo(Part p, double[] c, double[] out, double[] along) {
        double[] w = norm(out), u = norm(along), vv = cross(w, u);
        box(p.g(PLASTIC), c, mul(u, 20), mul(vv, 10), mul(w, 19));
        box(p.g(PLASTIC), add(c, mul(w, 11)), mul(u, 27), mul(vv, 10), mul(w, 1.25));
        double[] spline = sum(c, mul(u, 10), mul(w, 19));
        cyl(p.g(ZINC), spline, add(spline, mul(w, 4)), 3, 10);
        box(p.g(LABEL), sum(c, mul(vv, 10.15)), mul(u, 14), mul(vv, 0.15), mul(w, 8));
    }

    /**
     * GripForce mecanum wheel (104 mm, 38 mm wide): 11 silicone rollers between two yellow steel
     * plates. slash: the rollers' direction, as in the generated model (front-left and back-right alike).
     */
    static void gripForce(Part p, double[] c, boolean slash) {
        double rc = 41, half = 16, s = slash ? 1 : -1;
        cyl(p.g(CORE), add(c, v(0, -13, 0)), add(c, v(0, 13, 0)), 21, 22);
        disc(p.g(PLASTIC), add(c, v(0, 13.05, 0)), Y, 7, 12);
        disc(p.g(PLASTIC), add(c, v(0, -13.05, 0)), mul(Y, -1), 7, 12);
        double[][] profile = {{0, 6.5}, {0.15, 8.8}, {0.32, 10.3}, {0.5, 11}, {0.68, 10.3}, {0.85, 8.8}, {1, 6.5}};
        double lobe = Math.toDegrees(half * Math.cos(Math.PI / 4) / rc);
        List<Double> plusY = new ArrayList<>(), minusY = new ArrayList<>();
        for (int j = 0; j < 11; j++) {
            double phi = 2 * Math.PI * j / 11;
            double[] center = add(c, v(rc * Math.sin(phi), 0, rc * Math.cos(phi)));
            double[] t = v(Math.cos(phi), 0, -Math.sin(phi));
            double[] d = norm(add(mul(t, 0.7071), mul(Y, s * 0.7071)));
            lathe(p.g(RUBBER), add(center, mul(d, -half)), add(center, mul(d, half)), profile, 10);
            // The roller's +y end sits ahead of its center (in phi) when it leans +y.
            double alpha = Math.toDegrees(phi);
            plusY.add(alpha + s * lobe);
            minusY.add(alpha - s * lobe);
        }
        plate(p.g(YELLOW), add(c, v(0, 19, 0)), plusY);
        plate(p.g(YELLOW), add(c, v(0, -17, 0)), minusY);
    }

    /** A star-shaped wheel plate in the x/z plane at y = origin y, 2 mm thick toward -y, with lobes at the given angles (from +z toward +x). */
    static void plate(Geo g, double[] origin, List<Double> lobesDeg) {
        List<double[]> pts = new ArrayList<>();
        List<Double> betas = new ArrayList<>();
        for (double a : lobesDeg) betas.add(90 - a);
        betas.sort(Double::compare);
        for (int i = 0; i < betas.size(); i++) {
            double b = betas.get(i), next = i + 1 < betas.size() ? betas.get(i + 1) : betas.get(0) + 360;
            pts.add(polar(b - 6, 45));
            pts.add(polar(b + 6, 45));
            pts.add(polar((b + next) / 2, 30));
        }
        prism(g, origin, X, Z, 2, pts.toArray(new double[0][]));
    }

    static double[] polar(double deg, double r) {
        return new double[]{r * Math.cos(Math.toRadians(deg)), r * Math.sin(Math.toRadians(deg))};
    }

    /** GripForce Gecko wheel with its axis along y: silicone tire, plastic core with 8 spokes. */
    static void gecko(Part p, double[] c, double r, double width, double hubR) {
        double tire = r * 0.17;
        tube(p.g(RUBBER), add(c, v(0, -width / 2, 0)), add(c, v(0, width / 2, 0)), r, r - tire, 32);
        tube(p.g(PLASTIC), add(c, v(0, -width / 2 + 1, 0)), add(c, v(0, width / 2 - 1, 0)), hubR, hubR * 0.45, 16);
        for (int k = 0; k < 8; k++) {
            double b = Math.PI * k / 4;
            double[] rad = v(Math.cos(b), 0, Math.sin(b)), tan = cross(rad, Y);
            double mid = (hubR + r - tire) / 2, len = r - tire - hubR + 1;
            box(p.g(PLASTIC), add(c, mul(rad, mid)), mul(rad, len / 2), mul(Y, width / 2 - 2), mul(tan, r * 0.04));
        }
    }

    /** A REV Control/Expansion Hub (143 x 103 x 29.5 mm) standing on its long edge, ports facing forward. */
    static void revHub(Part p, double x0, double y0, double z0, double thickY, boolean control) {
        double y1 = y0 + thickY;
        aabb(p.g(HUB), x0, Math.min(y0, y1), z0, x0 + 143, Math.max(y0, y1), z0 + 103);
        double face = Math.max(y0, y1) == y1 && thickY > 0 ? y1 + 0.2 : Math.min(y0, y1) - 0.2;
        double outward = thickY > 0 ? 1 : -1;
        // The REV logo plate on the outward face, and a row of motor and servo ports along the top.
        aabb(p.g(ORANGE), x0 + 18, face - outward * 0.1, z0 + 30, x0 + 58, face + outward * 0.3, z0 + 72);
        if (control) aabb(p.g(WHITE), x0 + 70, face - outward * 0.1, z0 + 46, x0 + 120, face + outward * 0.3, z0 + 56);
        for (int k = 0; k < 10; k++) {
            double x = x0 + 12 + k * 12;
            aabb(p.g(PLASTIC), x, Math.min(y0, y1) + 4, z0 + 103, x + 9, Math.max(y0, y1) - 4, z0 + 106);
        }
    }

    // ================================================================== BuzzBot

    // Strafer chassis (goBILDA drawing 3209-0001-0007): 432 mm side rails 312 mm apart (centers),
    // 264 mm crossbars between them, wheel axles 48 mm from the rail ends (336 mm wheelbase),
    // 413.7 mm track (451.7 mm over the wheels), channels 28 mm off the floor.
    static final double RAIL_Y = 156, RAIL_HALF = 216, WHEEL_X = 168, WHEEL_Y = 206.85, WHEEL_R = 52, AXLE_Z = 52;
    // Crossbars placed for the dead wheels (Localizer: perpendicular wheel 6 in behind center,
    // parallel wheel 0.5 in ahead of it), each pod inside a crossbar.
    static final double MID_X = 12.7, REAR_X = -152.4;
    // Launcher: the ball leaves 1 in ahead of center, 15 in up, at 55 degrees (robot.jsonc
    // "launcher"), off the back of the flywheel under a hood.
    static final double[] FLY = {87, 0, 338};
    static final double FLY_TO_BALL = 75.5; // 48 mm wheel radius + 35.5 mm ball radius - 8 mm squeeze
    // Lift: a 4-stage Viper-Slide leaning back so the claw moves 0.1043 in back per inch up
    // (robot.jsonc: x -9.6 in at 1.8 in, xRaised at the top).
    static final double LEAN = 0.1043;
    static final double[] LIFT_AXIS = norm(v(-LEAN, 0, 1));
    static final double[] SLIDE_BASE = {-200, 0, 90};
    static final double[] CLAW_POINT = {-9.6 * 25.4, 0, 1.8 * 25.4};
    static final double[] FINGER_PIVOT = {-205, 49.7, 46};

    static Part build() {
        Part robot = new Part("BuzzBot");
        chassis(robot.kid("Chassis"));
        electronics(robot.kid("Electronics"));
        intake(robot.kid("Intake"));
        hopper(robot.kid("Hopper"));
        launcher(robot.kid("Launcher"));
        lift(robot.kid("Lift"));
        odometry(robot.kid("Odometry"));
        other(robot.kid("Signs and hardware"));
        return robot;
    }

    static void chassis(Part ch) {
        for (int side : new int[]{1, -1}) {
            Part rail = ch.kid(side > 0 ? "Side rail L" : "Side rail R").uses(RAIL, 1);
            uChannel(rail, v(-RAIL_HALF, side * RAIL_Y, AXLE_Z), X, 432, mul(Z, -1));
        }
        for (double x : new double[]{MID_X, REAR_X}) {
            Part bar = ch.kid(x > 0 ? "Crossbar middle" : "Crossbar rear").uses(CROSSBAR, 1);
            uChannel(bar, v(x, -132, AXLE_Z), Y, 264, mul(Z, -1));
        }
        String[] names = {"FL", "FR", "BL", "BR"};
        double[][] corners = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        boolean[] slash = {true, false, false, true};
        for (int i = 0; i < 4; i++) {
            double sx = corners[i][0], sy = corners[i][1];
            double[] wc = v(sx * WHEEL_X, sy * WHEEL_Y, AXLE_Z);
            gripForce(ch.kid("Wheel " + names[i]).uses(WHEEL, 1), wc, slash[i]);
            // Motor lying in the rail, its shaft toward the rail end; a pair of steel miter gears
            // turns the drive 90 degrees onto the wheel's hub-shaft.
            Part motor = ch.kid("Drive motor " + names[i]).uses(DRIVE_MOTOR, 1);
            yellowJacket(motor, v(sx * 140, sy * RAIL_Y, AXLE_Z), v(sx, 0, 0), 41.6);
            Part drive = ch.kid("Miter gears and hub-shaft " + names[i]).uses(STRAFER_HW, 340.0 / 662 / 4);
            cyl(drive.g(STEEL), v(sx * 150, sy * RAIL_Y, AXLE_Z), v(sx * 160, sy * RAIL_Y, AXLE_Z), 13, 18);
            cyl(drive.g(STEEL), v(sx * WHEEL_X, sy * (RAIL_Y - 8), AXLE_Z), v(sx * WHEEL_X, sy * (RAIL_Y - 18), AXLE_Z), 13, 18);
            cyl(drive.g(STEEL), v(sx * WHEEL_X, sy * 132, AXLE_Z), v(sx * WHEEL_X, sy * (WHEEL_Y + 6), AXLE_Z), 4, 8);
            cyl(drive.g(ZINC), v(sx * WHEEL_X, sy * 180, AXLE_Z), v(sx * WHEEL_X, sy * 187, AXLE_Z), 16, 18);
        }
        // Quad block mounts at the crossbar ends, then the kit's screws and cables.
        Part blocks = ch.kid("Quad block mounts").uses(STRAFER_HW, 120.0 / 662);
        for (double x : new double[]{MID_X, REAR_X}) for (int side : new int[]{1, -1}) aabb(blocks.g(ALU), x - 21, side * 124, 30, x + 21, side * 131.9, 74);
        ch.kid("Strafer screws and cables").uses(STRAFER_HW, 202.0 / 662).massAt(v(0, 0, AXLE_Z), v(432, 312, 48));
    }

    static void electronics(Part el) {
        Part deck = el.kid("Electronics deck").uses(DECK, 1);
        aabb(deck.g(DECK_MAT), -130, -180, 76, 70, 180, 78.5);
        // The hubs stand on their long edges between the launcher plates and the side rails.
        revHub(el.kid("Control Hub").uses(CONTROL, 1), -60, 100, 78.5, 29.5, true);
        revHub(el.kid("Expansion Hub").uses(EXPANSION, 1), -60, -100, 78.5, -29.5, false);
        // Battery low between the crossbars, on a tray.
        Part tray = el.kid("Battery tray").uses(TRAY, 1);
        aabb(tray.g(DECK_MAT), -128.4, -60, 26, -11.3, 60, 28);
        Part batt = el.kid("Battery").uses(BATT, 1);
        aabb(batt.g(BATTERY_WRAP), -127, -45.25, 28, -13.5, 45.25, 51);
        aabb(batt.g(LABEL), -110, -30, 51, -30, 30, 51.4);
        Part sw = el.kid("Power switch").uses(POWER_SWITCH, 1);
        aabb(sw.g(PLASTIC), -128, 140, 78.5, -100, 158, 95);
        aabb(sw.g(ORANGE), -122, 144, 95, -106, 154, 99);
        el.kid("Wiring").uses(WIRING, 1).massAt(v(-20, 0, 110), v(300, 300, 60));
    }

    static void intake(Part in) {
        // Front roller over the balls, a ramp up to the hopper, and a second roller at the top
        // of the ramp; one 1150 rpm motor under the ramp drives both with a belt.
        double[] r1 = {196, 0, 75};
        double[] rampLow = {206, 0, 14}, rampHigh = {86, 0, 180};
        double[] d = norm(sub(rampHigh, rampLow)), n = v(d[2], 0, -d[0]);
        double[] r2 = add(add(rampLow, mul(d, 170)), mul(n, 85));
        Part front = in.kid("Intake roller").uses(GECKO_SMALL, 10).uses(REX_SHAFT, 1);
        Part upper = in.kid("Intake upper roller").uses(GECKO_SMALL, 8).uses(REX_SHAFT, 1);
        for (Object[] roller : new Object[][]{{front, r1, 10}, {upper, r2, 8}}) {
            Part p = (Part) roller[0];
            double[] c = (double[]) roller[1];
            int count = (int) roller[2];
            cyl(p.g(STEEL), add(c, v(0, -182, 0)), add(c, v(0, 182, 0)), 4, 8);
            for (int k = 0; k < count; k++) {
                double y = -152 + 304.0 * k / (count - 1);
                gecko(p, add(c, v(0, y, 0)), 24, 16, 8);
            }
        }
        Part plates = in.kid("Intake side plates").uses(INTAKE_PLATE, 2);
        for (int side : new int[]{1, -1}) {
            // Bolted to the rail's outer wall ahead of the wheel hub, clear of the wheel.
            double[][] outline = {{186, 30}, {224, 40}, {228, 105}, {r2[0] + 26, r2[2] + 30}, {r2[0] - 30, r2[2] + 34}, {148, 150}, {186, 90}};
            prism(plates.g(ALU), v(0, side > 0 ? 183.5 : -180.5, 0), X, Z, 3, outline);
        }
        // The ramp narrows to the hopper's width where it passes between the launcher plates.
        Part ramp = in.kid("Intake ramp").uses(RAMP, 1);
        double len = Math.sqrt(dot(sub(rampHigh, rampLow), sub(rampHigh, rampLow)));
        prism(ramp.g(CLEAR), rampLow, d, Y, 2, new double[][]{{0, -160}, {100, -80}, {len, -80}, {len, 80}, {100, 80}, {0, 160}});
        Part motor = in.kid("Intake motor").uses(INTAKE_MOTOR, 1);
        yellowJacket(motor, v(140, -112, 55), v(0, -1, 0), 31);
        Part belt = in.kid("Intake belt").uses(BELT, 1);
        double[] pm = {140, -135, 55}, p1 = {r1[0], -135, r1[2]}, p2 = {r2[0], -135, r2[2]};
        for (double[] c : new double[][]{pm, p1, p2}) cyl(belt.g(ALU), add(c, v(0, -4, 0)), add(c, v(0, 4, 0)), 11, 16);
        for (double[][] run : new double[][][]{{pm, p1}, {p1, p2}, {p2, pm}}) {
            double[] a = sub(run[1], run[0]);
            double l = Math.sqrt(dot(a, a));
            double[] an = norm(a), side = cross(an, Y);
            box(belt.g(RUBBER), add(mul(add(run[0], run[1]), 0.5), mul(side, 11)), mul(an, l / 2), mul(Y, 3), mul(side, 0.8));
        }
    }

    static void hopper(Part hp) {
        // Four POLLEN in a 2 x 2 grid around the center (where the 3D view draws them), floor 180 mm up.
        Part box = hp.kid("Hopper").uses(HOPPER, 1);
        Geo c = box.g(CLEAR);
        aabb(c, -83, -83, 180, 83, 83, 182);
        aabb(c, -83, -83, 182, -81, 83, 262);
        for (int side : new int[]{1, -1}) aabb(c, -83, side * 81, 182, 83, side * 83, 262);
        aabb(c, 81, -83, 240, 83, 83, 262);
        for (int sx : new int[]{1, -1}) for (int sy : new int[]{1, -1}) cyl(box.g(ALU), v(sx * 64, sy * 74, 78.5), v(sx * 64, sy * 74, 180), 3.5, 8);
        Part color = hp.kid("Hopper color sensor").uses(COLOR_SENSOR, 1);
        aabb(color.g(PLASTIC), 20, 32, 165, 61.7, 49, 180);
        aabb(color.g(GREEN), 30, 35, 179.9, 50, 46, 180.2);
    }

    static void launcher(Part ln) {
        double[][] outline = {{-55, 78.5}, {130, 78.5}, {140, 300}, {140, 372}, {104, 420}, {-25, 420}, {-55, 385}};
        Part plates = ln.kid("Launcher side plates").uses(LAUNCHER_PLATE, 2);
        for (int side : new int[]{1, -1}) {
            prism(plates.g(ALU), v(0, side > 0 ? 87 : -84, 0), X, Z, 3, outline);
            for (double[] hole : new double[][]{{20, 140, 32}, {85, 140, 30}, {40, 230, 22}, {30, 360, 18}}) {
                disc(plates.g(HOLE), v(hole[0], side * 87.3, hole[1]), mul(Y, side), hole[2], 20);
                disc(plates.g(HOLE), v(hole[0], side * 83.7, hole[1]), mul(Y, -side), hole[2], 20);
            }
        }
        Part fly = ln.kid("Flywheel").uses(GECKO_BIG, 2).uses(STEEL_FLYWHEEL, 1).uses(SHAFT_PARTS, 1);
        double[] f = FLY;
        cyl(fly.g(STEEL), add(f, v(0, -90, 0)), add(f, v(0, 90, 0)), 4, 8);
        for (int side : new int[]{1, -1}) gecko(fly, add(f, v(0, side * 24, 0)), 48, 24, 16);
        tube(fly.g(STEEL), add(f, v(0, -3, 0)), add(f, v(0, 3, 0)), 41, 6, 32);
        for (int k = 0; k < 6; k++) {
            double b = Math.PI * k / 3;
            disc(fly.g(HOLE), add(f, v(26 * Math.cos(b), 3.1, 26 * Math.sin(b))), Y, 7, 12);
            disc(fly.g(HOLE), add(f, v(26 * Math.cos(b), -3.1, 26 * Math.sin(b))), mul(Y, -1), 7, 12);
        }
        for (int side : new int[]{1, -1}) {
            Part motor = ln.kid(side > 0 ? "Shooter motor L" : "Shooter motor R").uses(SHOOTER_MOTOR, 1);
            yellowJacket(motor, add(f, v(0, side * 90, 0)), v(0, -side, 0), 22);
        }
        // Hood: polycarbonate bent around the back of the flywheel, from below it to the exit.
        Part hood = ln.kid("Hood").uses(HOOD, 1);
        double r = FLY_TO_BALL + 35.5 + 1;
        int seg = 12;
        double b0 = Math.toRadians(145), b1 = Math.toRadians(222);
        for (int k = 0; k < seg; k++) {
            double ba = b0 + (b1 - b0) * k / seg, bb = b0 + (b1 - b0) * (k + 1) / seg, bm = (ba + bb) / 2;
            double[] rad = v(Math.cos(bm), 0, Math.sin(bm)), tan = cross(Y, rad);
            double chord = 2 * r * Math.sin((bb - ba) / 2) + 0.6;
            box(hood.g(CLEAR), add(f, mul(rad, r)), mul(tan, chord / 2), mul(Y, 45), mul(rad, 1));
        }
        for (double b : new double[]{b0, b1}) {
            double[] at = add(f, v((r + 6) * Math.cos(b), 0, (r + 6) * Math.sin(b)));
            cyl(hood.g(ALU), add(at, v(0, -84, 0)), add(at, v(0, 84, 0)), 4, 8);
        }
        // The transfer (two Gecko wheels on a CR servo) lifts the next ball out of the hopper to the
        // hood's lower end; the kicker paddle, between the transfer wheels, swings forward and
        // pushes it into the flywheel. Both servos sit outside the launcher plates.
        double[] tw = {-14, 0, 236};
        Part transfer = ln.kid("Transfer wheels").uses(GECKO_SMALL, 2).uses(KICKER, 0.5);
        cyl(transfer.g(STEEL), add(tw, v(0, -40, 0)), add(tw, v(0, 95, 0)), 3, 8);
        for (int side : new int[]{1, -1}) gecko(transfer, add(tw, v(0, side * 30, 0)), 24, 16, 8);
        servo(ln.kid("Transfer servo").uses(SUPER_SERVO, 1), v(-14, 105, 236 - 10), v(0, -1, 0), v(0, 0, 1));
        double[] pivot = {-20, 0, 262};
        Part kicker = ln.kid("Kicker arm").uses(KICKER, 0.5);
        box(kicker.g(PLASTIC), add(pivot, v(0, 0, 26)), mul(X, 2.5), mul(Y, 14), mul(Z, 26));
        cyl(kicker.g(STEEL), add(pivot, v(0, -95, 0)), add(pivot, v(0, 18, 0)), 3, 8);
        servo(ln.kid("Kicker servo").uses(SUPER_SERVO, 1), v(-20, -105, 262 - 10), v(0, 1, 0), v(0, 0, 1));
    }

    static void lift(Part lf) {
        Part plate = lf.kid("Rear plate").uses(REAR_PLATE, 1);
        aabb(plate.g(ALU), -216, -180, 76, -176, 180, 79);
        double[] a = LIFT_AXIS, depth = cross(a, Y);
        double[] y = Y;
        // Four Viper-Slides (240 x 27 x 9.5 mm steel) side by side; stage k's carriage carries
        // the next slide's rail, and the last carriage carries the claw.
        Part fixed = lf.kid("Lift base").uses(SLIDE_RAIL, 1).uses(VIPER, 1);
        Part[] stages = {lf.kid("Lift stage 1"), lf.kid("Lift stage 2"), lf.kid("Lift stage 3"), lf.kid("Lift carriage")};
        for (int k = 0; k < 4; k++) {
            double yk = -19 + 9.5 * (k + 0.5);
            double[] c = add(add(SLIDE_BASE, mul(a, 120)), mul(y, yk));
            Part railOwner = k == 0 ? fixed : stages[k - 1];
            if (k > 0) railOwner.uses(SLIDE_RAIL, 1);
            box(railOwner.g(ZINC), c, mul(a, 120), mul(y, 4.6), mul(depth, 13.5));
            for (int h = 0; h < 6; h++) disc(railOwner.g(HOLE), sum(c, mul(a, -100 + 40 * h), mul(depth, 13.6)), depth, 3, 8);
            stages[k].uses(SLIDE_CARRIAGE, 1);
            box(stages[k].g(STEEL), add(c, mul(depth, -2)), mul(a, 118), mul(y, 2.4), mul(depth, 9));
        }
        Part motor = lf.kid("Lift motor").uses(LIFT_MOTOR, 1);
        yellowJacket(motor, v(-165, 30, 110), v(0, -1, 0), 41.6);
        cyl(motor.g(ALU), v(-165, 16, 110), v(-165, 8, 110), 19.1, 24);
        Part sw = lf.kid("Lift limit switch").uses(LIMIT, 1);
        aabb(sw.g(PLASTIC), -190, 22, 80, -176, 40, 92);
        // Claw on the bottom of the last carriage: a bracket back and down to the fingers, which
        // close around a NECTAR held at the claw point (robot.jsonc: x -9.6 in, 1.8 in up).
        Part carriage = stages[3];
        carriage.uses(CLAW, 1);
        aabb(carriage.g(ALU), -272, -56, 81, -186, 56, 89);
        aabb(carriage.g(ALU), -196, -36, 30, -190, 36, 81);
        servo(carriage, v(-250, 0, 108), v(0, 0, -1), v(1, 0, 0));
        carriage.uses(SPEED_SERVO, 1);
        for (int side : new int[]{1, -1}) {
            Part finger = carriage.kid(side > 0 ? "Claw finger L" : "Claw finger R");
            double py = side * FINGER_PIVOT[1];
            cyl(finger.g(STEEL), v(FINGER_PIVOT[0], py, 28), v(FINGER_PIVOT[0], py, 81), 3, 8);
            double[][] outline = {{FINGER_PIVOT[0] + 6, 31}, {FINGER_PIVOT[0] + 6, 61}, {-262, 61}, {-292, 52}, {-292, 40}, {-262, 31}};
            prism(finger.g(ALU), v(0, py + 2, 0), X, Z, 4, outline);
            aabb(finger.g(RUBBER), -280, py - side * 2.6, 33, -230, py - side * 2.0, 59);
        }
    }

    static void odometry(Part od) {
        // 4-bar pods sit inside the crossbars (they fit a 43 x 43 mm U-channel opening).
        Object[][] pods = {{"Dead wheel perpendicular", v(REAR_X, 0, 0), X}, {"Dead wheel parallel", v(MID_X, -4.5 * 25.4, 0), Y}};
        for (Object[] pd : pods) {
            Part p = od.kid((String) pd[0]).uses(PODS, 1);
            double[] c = (double[]) pd[1], axle = (double[]) pd[2];
            aabb(p.g(PLASTIC), c[0] - 20, c[1] - 20, 30, c[0] + 20, c[1] + 20, 72);
            aabb(p.g(YELLOW), c[0] - 20.5, c[1] - 20.5, 60, c[0] + 20.5, c[1] + 20.5, 66);
            double[] w = v(c[0], c[1], 16);
            lathe(p.g(RUBBER), add(w, mul(axle, -6)), add(w, mul(axle, 6)), new double[][]{{0, 13}, {0.2, 16}, {0.8, 16}, {1, 13}}, 18);
        }
    }

    static void other(Part ot) {
        // Alliance signs between the wheels, out at the robot's sides (the 3D view colors them for
        // the robot's alliance and puts its number on them).
        Part signs = ot.kid("Robot signs").uses(SIGNS, 1);
        for (int side : new int[]{1, -1}) {
            Part s = signs.kid(side > 0 ? "Alliance sign L" : "Alliance sign R");
            aabb(s.g(SIGN), -108, side * 221.5, 56, 108, side * 224.5, 140);
            for (int sx : new int[]{1, -1}) cyl(signs.g(ALU), v(sx * 80, side * 180, 66), v(sx * 80, side * 221.5, 66), 3, 8);
        }
        ot.kid("Brackets and screws").uses(FASTENERS, 1).massAt(v(0, 0, 200), v(300, 300, 300));
    }

    // ================================================================== mass properties

    record Mass(String name, String group, double kg, double[] com, double[] size) {}

    static void collect(Part p, String group, List<Mass> out) {
        String g = group == null ? p.name : group;
        if (p.massG > 0) {
            double[] b = bounds(p);
            double[] com = p.com != null ? p.com : v((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
            double[] size = p.size != null ? p.size : v(b[3] - b[0], b[4] - b[1], b[5] - b[2]);
            out.add(new Mass(p.name, g, p.massG / 1000, com, size));
        }
        for (Part k : p.kids) collect(k, group == null && p.name.equals("BuzzBot") ? null : g, out);
    }

    static double[] bounds(Part p) {
        double[] b = {1e9, 1e9, 1e9, -1e9, -1e9, -1e9};
        for (Geo g : p.geo.values()) {
            for (int i = 0; i < g.np; i++) {
                for (int k = 0; k < 3; k++) {
                    b[k] = Math.min(b[k], g.p[i * 3 + k]);
                    b[k + 3] = Math.max(b[k + 3], g.p[i * 3 + k]);
                }
            }
        }
        return b;
    }

    static double[] allBounds(Part p, double[] b) {
        double[] own = bounds(p);
        for (int k = 0; k < 3; k++) {
            b[k] = Math.min(b[k], own[k]);
            b[k + 3] = Math.max(b[k + 3], own[k + 3]);
        }
        for (Part c : p.kids) allBounds(c, b);
        return b;
    }

    // ================================================================== output

    public static void main(String[] args) throws IOException {
        Path project = Path.of(args.length > 0 ? args[0] : ".");
        Part robot = build();
        byte[] glb = glb(robot);
        Files.write(project.resolve("config/robot.glb"), glb);
        Files.writeString(project.resolve("config/robot.model.jsonc"), modelConfig());
        String report = report(robot, glb.length);
        Files.writeString(project.resolve("docs/buzzbot-build.md"), report);
        System.out.println(report.substring(report.indexOf("## What the simulator uses"), report.indexOf("## Bill of materials")));
        System.out.printf(Locale.US, "Wrote config/robot.glb (%d KB, %d triangles), config/robot.model.jsonc, docs/buzzbot-build.md%n",
                glb.length / 1024, triangles(robot));
    }

    static int triangles(Part p) {
        int n = 0;
        for (Geo g : p.geo.values()) n += g.ni / 3;
        for (Part k : p.kids) n += triangles(k);
        return n;
    }

    static String modelConfig() {
        double in = 25.4, stretch = Math.sqrt(1 + LEAN * LEAN);
        String axis = String.format(Locale.US, "[%.4f, 0, 1]", -LEAN);
        StringBuilder b = new StringBuilder();
        b.append("// BuzzBot's 3D model, generated with config/robot.glb by tools/BuzzBotCad.java from goBILDA and\n")
                .append("// REV part drawings (see docs/buzzbot-build.md). Re-run the tool after changing it; edits made\n")
                .append("// in the window's Model tab are saved here and kept until the tool runs again.\n")
                .append("// The model is drawn in robot coordinates; the rotation turns glTF's y-up frame to z-up.\n")
                .append("{\n  \"file\": \"robot.glb\",\n  \"units\": \"m\",\n")
                .append("  \"rotations\": [ { \"axis\": \"x\", \"degrees\": 90 } ],\n")
                .append("  \"autoCenter\": false,\n  \"offsetIn\": [0, 0, 0],\n  \"showGenerated\": false,\n  \"bindings\": [\n");
        List<String> rows = new ArrayList<>();
        for (String[] w : new String[][]{{"FL", "frontLeft"}, {"FR", "frontRight"}, {"BL", "backLeft"}, {"BR", "backRight"}}) {
            rows.add("    { \"node\": \"Wheel " + w[0] + "\", \"wheel\": \"" + w[1] + "\" }");
        }
        rows.add("    { \"node\": \"Intake roller\", \"motor\": \"intake\", \"motion\": \"rotate\", \"axis\": [0, 1, 0] }");
        rows.add("    { \"node\": \"Intake upper roller\", \"motor\": \"intake\", \"motion\": \"rotate\", \"axis\": [0, 1, 0] }");
        rows.add("    { \"node\": \"Flywheel\", \"mechanism\": \"flywheel\", \"motion\": \"rotate\", \"axis\": [0, 1, 0] }");
        rows.add(String.format(Locale.US, "    { \"node\": \"Kicker arm\", \"servo\": \"kicker\", \"motion\": \"rotate\", \"axis\": [0, 1, 0], \"pivotIn\": [%.3f, 0, %.3f], \"scale\": 0.222, \"zero\": -90 }",
                -20 / in, 262 / in));
        rows.add(String.format(Locale.US, "    { \"node\": \"Transfer wheels\", \"servo\": \"transfer\", \"motion\": \"rotate\", \"axis\": [0, 1, 0], \"pivotIn\": [%.3f, 0, %.3f] }",
                -14 / in, 236 / in));
        String[] stages = {"Lift stage 1", "Lift stage 2", "Lift stage 3", "Lift carriage"};
        for (int k = 0; k < 4; k++) {
            rows.add(String.format(Locale.US, "    { \"node\": \"%s\", \"mechanism\": \"lift\", \"motion\": \"translate\", \"axis\": %s, \"scale\": %.4f }",
                    stages[k], axis, (k + 1) / 4.0 * stretch));
        }
        for (int side : new int[]{1, -1}) {
            rows.add(String.format(Locale.US, "    { \"node\": \"Claw finger %s\", \"servo\": \"claw\", \"motion\": \"rotate\", \"axis\": [0, 0, 1], \"pivotIn\": [%.3f, %.3f, %.3f], \"scale\": %.3f, \"zero\": -90 }",
                    side > 0 ? "L" : "R", FINGER_PIVOT[0] / in, side * FINGER_PIVOT[1] / in, FINGER_PIVOT[2] / in, -side * 0.139));
        }
        b.append(String.join(",\n", rows)).append("\n  ]\n}\n");
        return b.toString();
    }

    // ---- GLB

    static byte[] glb(Part root) throws IOException {
        List<Mat> mats = new ArrayList<>();
        StringBuilder nodes = new StringBuilder(), meshes = new StringBuilder(), accessors = new StringBuilder(), views = new StringBuilder();
        ByteArrayOutputStream bin = new ByteArrayOutputStream();
        int[] counts = new int[4]; // nodes, meshes, accessors, views
        List<Part> order = new ArrayList<>();
        flatten(root, order);
        for (Part p : order) {
            if (counts[0] > 0) nodes.append(',');
            nodes.append("{\"name\":").append(quote(p.name));
            if (p.geo.values().stream().anyMatch(g -> g.ni > 0)) {
                nodes.append(",\"mesh\":").append(counts[1]);
                if (counts[1] > 0) meshes.append(',');
                meshes.append("{\"name\":").append(quote(p.name)).append(",\"primitives\":[");
                boolean first = true;
                for (Map.Entry<Mat, Geo> e : p.geo.entrySet()) {
                    Geo g = e.getValue();
                    if (g.ni == 0) continue;
                    int mi = mats.indexOf(e.getKey());
                    if (mi < 0) {
                        mats.add(e.getKey());
                        mi = mats.size() - 1;
                    }
                    ByteBuffer pos = ByteBuffer.allocate(g.np * 12).order(ByteOrder.LITTLE_ENDIAN);
                    float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
                    for (int i = 0; i < g.np; i++) {
                        // robot (x fwd, y left, z up, mm) -> glTF (x, y up, z = right), meters
                        float[] q = {g.p[i * 3] / 1000f, g.p[i * 3 + 2] / 1000f, -g.p[i * 3 + 1] / 1000f};
                        for (int k = 0; k < 3; k++) {
                            pos.putFloat(q[k]);
                            min[k] = Math.min(min[k], q[k]);
                            max[k] = Math.max(max[k], q[k]);
                        }
                    }
                    ByteBuffer ind = ByteBuffer.allocate(g.ni * 4).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < g.ni; i++) ind.putInt(g.idx[i]);
                    int posView = view(views, counts, bin, pos.array(), 34962), indView = view(views, counts, bin, ind.array(), 34963);
                    int posAcc = counts[2]++, indAcc = counts[2]++;
                    if (posAcc > 0) accessors.append(',');
                    accessors.append(String.format(Locale.US, "{\"bufferView\":%d,\"componentType\":5126,\"count\":%d,\"type\":\"VEC3\",\"min\":[%s,%s,%s],\"max\":[%s,%s,%s]}",
                            posView, g.np, min[0], min[1], min[2], max[0], max[1], max[2]));
                    accessors.append(String.format(Locale.US, ",{\"bufferView\":%d,\"componentType\":5125,\"count\":%d,\"type\":\"SCALAR\"}", indView, g.ni));
                    if (!first) meshes.append(',');
                    first = false;
                    meshes.append(String.format(Locale.US, "{\"attributes\":{\"POSITION\":%d},\"indices\":%d,\"material\":%d,\"mode\":4}", posAcc, indAcc, mi));
                }
                meshes.append("]}");
                counts[1]++;
            }
            if (!p.kids.isEmpty()) {
                nodes.append(",\"children\":[");
                for (int i = 0; i < p.kids.size(); i++) nodes.append(i > 0 ? "," : "").append(order.indexOf(p.kids.get(i)));
                nodes.append(']');
            }
            nodes.append('}');
            counts[0]++;
        }
        StringBuilder materials = new StringBuilder();
        for (int i = 0; i < mats.size(); i++) {
            Mat m = mats.get(i);
            double r = lin((m.srgb >> 16) & 0xFF), g = lin((m.srgb >> 8) & 0xFF), bl = lin(m.srgb & 0xFF);
            materials.append(i > 0 ? "," : "").append(String.format(Locale.US,
                    "{\"name\":%s,\"pbrMetallicRoughness\":{\"baseColorFactor\":[%.4f,%.4f,%.4f,%.2f],\"metallicFactor\":%.2f,\"roughnessFactor\":%.2f}%s}",
                    quote(m.name), r, g, bl, m.alpha, m.metallic, m.roughness, m.alpha < 1 ? ",\"alphaMode\":\"BLEND\"" : ""));
        }
        String json = "{\"asset\":{\"version\":\"2.0\",\"generator\":\"FTC Test Suite tools/BuzzBotCad.java\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":[" + nodes + "],\"meshes\":[" + meshes + "],\"materials\":[" + materials + "],\"accessors\":[" + accessors
                + "],\"bufferViews\":[" + views + "],\"buffers\":[{\"byteLength\":" + bin.size() + "}]}";
        byte[] js = json.getBytes(StandardCharsets.UTF_8);
        int jsPad = (4 - js.length % 4) % 4, binPad = (4 - bin.size() % 4) % 4;
        int total = 12 + 8 + js.length + jsPad + 8 + bin.size() + binPad;
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67).putInt(2).putInt(total);
        out.putInt(js.length + jsPad).putInt(0x4E4F534A).put(js);
        for (int i = 0; i < jsPad; i++) out.put((byte) ' ');
        out.putInt(bin.size() + binPad).putInt(0x004E4942).put(bin.toByteArray());
        for (int i = 0; i < binPad; i++) out.put((byte) 0);
        return out.array();
    }

    static void flatten(Part p, List<Part> out) {
        out.add(p);
        for (Part k : p.kids) flatten(k, out);
    }

    static int view(StringBuilder views, int[] counts, ByteArrayOutputStream bin, byte[] data, int target) {
        while (bin.size() % 4 != 0) bin.write(0);
        int offset = bin.size();
        bin.writeBytes(data);
        if (counts[3] > 0) views.append(',');
        views.append(String.format(Locale.US, "{\"buffer\":0,\"byteOffset\":%d,\"byteLength\":%d,\"target\":%d}", offset, data.length, target));
        return counts[3]++;
    }

    static double lin(int c) {
        return Math.pow(c / 255.0, 2.2);
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---- report

    static String report(Part robot, int glbBytes) {
        List<Mass> masses = new ArrayList<>();
        collect(robot, null, masses);
        double m = 0, mx = 0, my = 0, mz = 0;
        for (Mass x : masses) {
            m += x.kg;
            mx += x.kg * x.com[0];
            my += x.kg * x.com[1];
            mz += x.kg * x.com[2];
        }
        double cx = mx / m, cy = my / m, cz = mz / m;
        double yaw = 0;
        for (Mass x : masses) {
            double dx = (x.com[0] - cx) / 1000, dy = (x.com[1] - cy) / 1000;
            double sx = x.size[0] / 1000, sy = x.size[1] / 1000;
            yaw += x.kg * (dx * dx + dy * dy) + x.kg * (sx * sx + sy * sy) / 12;
        }
        double[] b = allBounds(robot, new double[]{1e9, 1e9, 1e9, -1e9, -1e9, -1e9});
        // Flywheel: the steel flywheel (goBILDA lists its MOI), two Gecko wheels (tire 60% of the
        // mass at r 40-48 mm, the rest in the spokes and hub), shaft and hubs, and the two motors'
        // rotors (RS-555 class, 1:1).
        double gecko = 0.6 * 0.105 * 0.5 * (0.040 * 0.040 + 0.048 * 0.048) + 0.3 * 0.105 * (0.016 * 0.016 + 0.040 * 0.040) / 2 + 0.1 * 0.105 * 0.5 * 0.016 * 0.016;
        double flywheel = 1651e-7 + 2 * gecko + 0.5 * 0.066 * 0.004 * 0.004 + 2 * 0.009 * 0.012 * 0.012 + 2 * 2.0e-6;
        // Lift: the motor carries the claw at full speed and each moving stage k (of 4) at k/4 of it.
        double stageKg = (SLIDE_CARRIAGE.massG + SLIDE_RAIL.massG + 40) / 1000;
        double clawKg = (SLIDE_CARRIAGE.massG + CLAW.massG + SPEED_SERVO.massG) / 1000;
        double liftKg = stageKg * (1 + 2 + 3) / 4.0 + clawKg;

        StringBuilder r = new StringBuilder();
        r.append("# BuzzBot, built from real parts\n\n")
                .append("Generated by `tools/BuzzBotCad.java` (run `java tools/BuzzBotCad.java` from the project folder) together with\n")
                .append("`config/robot.glb`, the 3D model, and `config/robot.model.jsonc`. BuzzBot is the example robot in\n")
                .append("`config/robot.jsonc`; this is the robot those settings describe, put together from goBILDA and REV parts\n")
                .append("with the makers' dimensions and weights, so that the simulator's numbers are ones a real build would have.\n")
                .append("Weights marked *estimate* aren't published; the note says how they were worked out.\n\n")
                .append("![BuzzBot's 3D model with the lift down and raised](buzzbot-model.png)\n\n");
        r.append("## What the simulator uses\n\n")
                .append("| robot.jsonc | Value | From |\n|---|---|---|\n")
                .append(String.format(Locale.US, "| `massLbs` | %.1f lb (%.2f kg) | every part below |%n", m / 0.45359237, m))
                .append(String.format(Locale.US, "| `cgHeightIn` | %.2f in (%.0f mm) | center of gravity, lift down |%n", cz / 25.4, cz))
                .append(String.format(Locale.US, "| `yawInertiaKgM2` | %.3f kg·m² | about the center of gravity (a uniform box would be %.3f) |%n", yaw,
                        m * (0.4572 * 0.4572 + 0.4516 * 0.4516) / 12))
                .append("| `drivetrain.trackWidthIn` | 16.29 in (413.7 mm) | Strafer drawing: 451.7 mm over the 38 mm wheels |\n")
                .append("| `drivetrain.wheelbaseIn` | 13.23 in (336 mm) | Strafer drawing |\n")
                .append(String.format(Locale.US, "| `lengthIn` x `widthIn` x `heightIn` | 18.0 x 17.78 x %.1f in | frame and wheels; intake roller at the front edge; top of the launcher plates |%n",
                        Math.ceil(b[5] / 25.4 * 10) / 10))
                .append(String.format(Locale.US, "| flywheel `inertiaKgM2` | %.5f kg·m² | steel flywheel 1651 g·cm² (goBILDA), two Gecko wheels about %.0f g·cm² each, shaft, hubs, rotors |%n",
                        flywheel, gecko * 1e7))
                .append(String.format(Locale.US, "| lift `kit`, `massLbs` | Viper 4-stage 240 mm, %.2f lb | 696 mm travel; claw + stages weighted by speed (%.0f g per stage, %.0f g claw) |%n",
                        liftKg / 0.45359237, stageKg * 1000, clawKg * 1000))
                .append("| dead wheel `yIn` | -4.5 in | the parallel pod sits in the middle crossbar, inside the right rail |\n\n")
                .append(String.format(Locale.US, "Center of gravity: %.1f mm %s, %.1f mm %s of center, %.0f mm up.%n%n",
                        Math.abs(cx), cx >= 0 ? "ahead" : "behind", Math.abs(cy), cy >= 0 ? "left" : "right", cz));

        r.append("## Mass by subsystem\n\n| Subsystem | Mass |\n|---|---|\n");
        Map<String, Double> groups = new LinkedHashMap<>();
        for (Mass x : masses) groups.merge(x.group, x.kg, Double::sum);
        for (Map.Entry<String, Double> e : groups.entrySet()) r.append(String.format(Locale.US, "| %s | %.0f g |%n", e.getKey(), e.getValue() * 1000));
        r.append(String.format(Locale.US, "| **Total** | **%.0f g (%.1f lb)** |%n%n", m * 1000, m / 0.45359237));

        r.append("## Bill of materials\n\n| Qty | Part | SKU | Each | Total | Source |\n|---|---|---|---|---|---|\n");
        String group = "";
        for (Bom x : BOM) {
            if (x.qty == 0) continue;
            if (!x.group.equals(group)) {
                group = x.group;
                r.append("| | **").append(group).append("** | | | | |\n");
            }
            String qty = Math.abs(x.qty - Math.rint(x.qty)) < 1e-6 ? String.valueOf((long) Math.rint(x.qty)) : String.format(Locale.US, "%.2f", x.qty);
            String src = x.source == null ? "" : "[" + host(x.source) + "](" + x.source + ")";
            String each = String.format(Locale.US, "%.0f g", x.massG) + (x.note != null ? " *estimate: " + x.note + "*" : "");
            r.append(String.format(Locale.US, "| %s | %s | %s | %s | %.0f g | %s |%n", qty, x.name, x.sku, each, x.massG * x.qty, src));
        }
        r.append("\n## Layout\n\n")
                .append("Robot frame: x forward, y left, z up, from the robot's center on the floor.\n\n")
                .append("- **Chassis**: goBILDA Strafer v7. The motors lie inside the side rails and drive the wheels through steel miter gears. ")
                .append("The crossbars sit 0.5 in ahead of center and 6 in behind it, and each holds a dead wheel.\n")
                .append("- **Electronics**: the Control Hub and Expansion Hub stand on edge on the deck, between the launcher plates and the side rails. ")
                .append("The battery lies between the crossbars, 28 mm off the floor, which keeps the center of gravity low.\n")
                .append("- **Intake**: a front roller of ten 48 mm Gecko wheels picks POLLEN off the tiles. A polycarbonate ramp and a second roller take each one up into the hopper. ")
                .append("Both rollers run off one 1150 rpm motor through a belt.\n")
                .append("- **Hopper**: holds four POLLEN, 7 in up. The color sensor looks up through its floor at the ball the transfer takes next.\n")
                .append("- **Launcher**: a CR-servo transfer lifts the next ball to the kicker. The kicker pushes it into two 96 mm Gecko wheels and an 82 mm steel flywheel, all on one shaft between two 6000 rpm motors. ")
                .append("The ball goes up the back of the wheels under a polycarbonate hood and leaves 15 in up at 55°.\n")
                .append("- **Lift**: a 4-stage Viper-Slide on the back, leaning back 6°, driven by a 435 rpm motor and a 60-tooth GT2 pulley (120 mm per turn). ")
                .append("The claw hangs from the last stage's carriage.\n\n")
                .append("Departures from a legal build, kept so BuzzBot's code and autos behave as tuned:\n\n")
                .append("- The claw's grab point is 9.6 in behind center (`grippers` in robot.jsonc), so the claw sticks out about 2.5 in behind the 18 in starting box. ")
                .append("A real BuzzBot would fold the claw in at the start.\n")
                .append("- The REV battery's 20 A fuse would blow if all eight motors pulled hard at once. The simulator doesn't model fuses, only the battery's voltage sag.\n\n")
                .append(String.format(Locale.US, "Model: %d parts, %d triangles, %d KB.%n", count(robot), triangles(robot), glbBytes / 1024));
        return r.toString();
    }

    static int count(Part p) {
        int n = 1;
        for (Part k : p.kids) n += count(k);
        return n;
    }

    static String host(String url) {
        String h = url.replaceFirst("https?://", "");
        h = h.substring(0, h.indexOf('/') > 0 ? h.indexOf('/') : h.length());
        return h.replaceFirst("^www\\.", "");
    }
}
