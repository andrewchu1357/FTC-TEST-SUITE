package org.ftctestbed.viewer.three;

import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.shape.VertexFormat;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import org.ftctestbed.sim.Snapshot;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.viewer.model.ModelConfig;
import org.ftctestbed.viewer.model.ModelData;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The robot's CAD model in the 3D view: placed on the robot with the model config's alignment,
 * with bound parts moving along with simulated mechanisms, servos, motors and wheels.
 * Build it off the JavaFX thread if you like; attach and update it on the JavaFX thread.
 */
public final class RobotModel {
    private static final double IN = 0.0254;
    private static final PhongMaterial HIGHLIGHT = new PhongMaterial(Color.web("#FFD54F"));

    final Group root = new Group();
    final boolean showGenerated;
    private final ModelData data;
    private final RobotConfig robot;
    private final Map<ModelData.Node, Group> groups = new IdentityHashMap<>();
    private final Map<ModelData.Node, double[]> world;
    private final List<Active> active = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final Map<MeshView, PhongMaterial> highlighted = new IdentityHashMap<>();
    private int previewIndex = -1;
    private double previewFraction;

    /** A binding resolved to the JavaFX transforms it drives. */
    private record Active(int index, ModelConfig.Binding binding, boolean translate, List<Rotate> rotates, List<Translate> translates,
                          List<double[]> perInch, double[] range, Spin spin) {}

    public RobotModel(ModelData data, ModelConfig config, RobotConfig robot) {
        this.data = data;
        this.robot = robot;
        this.showGenerated = config.showGenerated;
        double[] align = config.alignment(data);
        root.getTransforms().add(affine(align));
        Map<ModelData.Mesh, TriangleMesh> meshes = new IdentityHashMap<>();
        Group top = build(data.root, meshes);
        root.getChildren().add(top);
        world = data.worldMatrices(align);
        for (int i = 0; i < config.bindings.size(); i++) bind(i, config.bindings.get(i));
    }

    public List<String> problems() { return problems; }

    public ModelData data() { return data; }

    private Group build(ModelData.Node n, Map<ModelData.Mesh, TriangleMesh> cache) {
        Group g = new Group();
        g.getTransforms().add(affine(n.matrix));
        for (ModelData.Mesh m : n.meshes) {
            MeshView view = new MeshView(cache.computeIfAbsent(m, RobotModel::toFx));
            view.setMaterial(material(m));
            view.setUserData(n);
            g.getChildren().add(view);
        }
        for (ModelData.Node c : n.children) g.getChildren().add(build(c, cache));
        groups.put(n, g);
        return g;
    }

    /**
     * Phong stand-in for the file's material: smooth metal gets a bright, tight highlight, rough
     * plastic and rubber a dim, broad one. Without metallic/roughness (STL, OBJ) a mild sheen.
     */
    static PhongMaterial material(ModelData.Mesh m) {
        PhongMaterial mat = new PhongMaterial(Color.color(clamp(m.rgba[0]), clamp(m.rgba[1]), clamp(m.rgba[2]), clamp(m.rgba[3])));
        if (Float.isNaN(m.metallic) || Float.isNaN(m.roughness)) {
            mat.setSpecularColor(Color.gray(0.25));
        } else {
            double smooth = 1 - clamp(m.roughness), metal = clamp(m.metallic);
            mat.setSpecularColor(Color.gray(Math.min(1, 0.06 + smooth * (0.25 + 0.45 * metal))));
            mat.setSpecularPower(6 + 70 * smooth * smooth);
        }
        return mat;
    }

    /**
     * Colors the parts named "Alliance sign..." (robot signs, which must show the alliance color)
     * for the robot's alliance.
     */
    public void setAllianceColor(Color c) {
        groups.forEach((node, group) -> {
            if (!node.name.toLowerCase(java.util.Locale.ROOT).startsWith("alliance sign")) return;
            for (Node child : group.getChildren()) {
                if (!(child instanceof MeshView mv)) continue;
                PhongMaterial own = highlighted.containsKey(mv) ? highlighted.get(mv) : (PhongMaterial) mv.getMaterial();
                own.setDiffuseColor(c);
            }
        });
    }

    private static double clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    /**
     * JavaFX computes the normals from the triangles: CAD exports already split vertices along
     * hard edges, so this looks the same as the file's normals and needs about half the memory
     * (meshes with their own normals keep a per-vertex lookup table in JavaFX).
     */
    private static TriangleMesh toFx(ModelData.Mesh m) {
        TriangleMesh t = new TriangleMesh(VertexFormat.POINT_TEXCOORD);
        t.getPoints().setAll(m.positions);
        t.getTexCoords().setAll(0, 0);
        int[] faces = new int[m.indices.length * 2];
        for (int i = 0; i < m.indices.length; i++) faces[i * 2] = m.indices[i];
        t.getFaces().setAll(faces);
        return t;
    }

    static Affine affine(double[] m) {
        return new Affine(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], m[10], m[11]);
    }

    // ------------------------------------------------------------------ bindings

    private void bind(int index, ModelConfig.Binding b) {
        List<ModelData.Node> nodes = new ArrayList<>();
        data.root.forEach(n -> {
            if (n.name.equals(b.node)) nodes.add(n);
        });
        if (nodes.isEmpty()) {
            problems.add("Model part \"" + b.node + "\" not found");
            return;
        }
        double[] range = deviceRange(b);
        if (range == null) {
            problems.add("Binding for \"" + b.node + "\": the robot has no " + b.source + " named \"" + b.device + "\"");
            return;
        }
        boolean translate = b.motion.equals("translate") || (b.motion.equals("auto") && isSlide(b));
        double[] axis = b.axis != null ? normalize(b.axis) : translate ? new double[]{0, 0, 1} : new double[]{0, 1, 0};
        List<Rotate> rotates = new ArrayList<>();
        List<Translate> translates = new ArrayList<>();
        List<double[]> perInch = new ArrayList<>();
        for (ModelData.Node n : nodes) {
            double[] parentWorld = ModelData.multiply(world.get(n), ModelData.inverseAffine(n.matrix));
            double[] toParent = ModelData.inverseAffine(parentWorld);
            Group g = groups.get(n);
            if (translate) {
                double[] v = ModelData.transformDirection(toParent, axis[0] * IN, axis[1] * IN, axis[2] * IN);
                Translate t = new Translate();
                g.getTransforms().add(0, t);
                translates.add(t);
                perInch.add(v);
            } else {
                double[] pivot;
                if (b.pivot != null) {
                    pivot = new double[]{b.pivot[0] * IN, b.pivot[1] * IN, b.pivot[2] * IN};
                } else {
                    double[] bb = ModelData.bounds(n, parentWorld);
                    pivot = new double[]{(bb[0] + bb[3]) / 2, (bb[1] + bb[4]) / 2, (bb[2] + bb[5]) / 2};
                }
                double[] p = ModelData.transformPoint(toParent, pivot[0], pivot[1], pivot[2]);
                double[] a = normalize(ModelData.transformDirection(toParent, axis[0], axis[1], axis[2]));
                double sense = determinant(toParent) < 0 ? -1 : 1; // a mirrored parent frame flips the sense of rotation
                Rotate r = new Rotate(0, p[0], p[1], p[2], new Point3D(a[0] * sense, a[1] * sense, a[2] * sense));
                g.getTransforms().add(0, r);
                rotates.add(r);
            }
        }
        // Things that spin on and on (wheels, rollers, flywheels) are drawn at a speed the eye can
        // follow (see Spin); arms, slides and positional servos as they are.
        active.add(new Active(index, b, translate, rotates, translates, perInch, range, spins(b) ? new Spin(15) : null));
    }

    private boolean spins(ModelConfig.Binding b) {
        switch (b.source) {
            case "wheel", "motor" -> { return true; }
            case "mechanism" -> {
                for (RobotConfig.Mechanism m : robot.mechanisms()) if (m.name().equals(b.device)) return m.type().equals("free");
                return true; // a motor that drives nothing configured spins freely
            }
            case "servo" -> {
                for (RobotConfig.Servo s : robot.servos()) if (s.name().equals(b.device)) return s.continuous();
                return false;
            }
            default -> { return false; }
        }
    }

    private boolean isSlide(ModelConfig.Binding b) {
        if (!b.source.equals("mechanism")) return false;
        for (RobotConfig.Mechanism m : robot.mechanisms()) if (m.name().equals(b.device)) return m.type().equals("slide");
        return false;
    }

    /** {min, max} of the device's value (for previews), or null if there's no such device. */
    private double[] deviceRange(ModelConfig.Binding b) {
        switch (b.source) {
            case "mechanism" -> {
                for (RobotConfig.Mechanism m : robot.mechanisms()) {
                    if (!m.name().equals(b.device)) continue;
                    return switch (m.type()) {
                        case "slide" -> new double[]{0, m.travel() / IN};
                        case "arm" -> new double[]{Math.toDegrees(m.minAngle()), Math.toDegrees(m.maxAngle())};
                        default -> new double[]{0, 360};
                    };
                }
                for (RobotConfig.Motor m : robot.allMotors()) if (m.name().equals(b.device)) return new double[]{0, 360};
                return null;
            }
            case "servo" -> {
                for (RobotConfig.Servo s : robot.servos()) {
                    if (s.name().equals(b.device)) {
                        return s.continuous() ? new double[]{0, 360} : new double[]{-Math.toDegrees(s.rangeRad()) / 2, Math.toDegrees(s.rangeRad()) / 2};
                    }
                }
                return null;
            }
            case "motor" -> {
                for (RobotConfig.Motor m : robot.allMotors()) if (m.name().equals(b.device)) return new double[]{0, 360};
                return null;
            }
            case "wheel" -> {
                return driveMotor(b.device) == null ? null : new double[]{0, 360};
            }
            default -> {
                return null;
            }
        }
    }

    private RobotConfig.Motor driveMotor(String wheel) {
        RobotConfig.Drivetrain dt = robot.drivetrain();
        return switch (wheel) {
            case "frontLeft" -> dt.frontLeft();
            case "frontRight" -> dt.frontRight();
            case "backLeft" -> dt.backLeft();
            case "backRight" -> dt.backRight();
            default -> null;
        };
    }

    /** The device's value in binding units (inches for slides, degrees otherwise). */
    private double value(ModelConfig.Binding b, Snapshot s) {
        switch (b.source) {
            case "mechanism" -> {
                for (Snapshot.Mechanism m : s.mechanisms()) {
                    if (m.name().equals(b.device)) return m.type().equals("free") ? m.position() * 360 : m.position();
                }
            }
            case "servo" -> {
                for (Snapshot.Servo sv : s.servos()) if (sv.name().equals(b.device)) return Math.toDegrees(sv.angle());
            }
            case "motor" -> {
                for (Snapshot.Motor m : s.motors()) if (m.name().equals(b.device)) return m.rawTicks() / m.ticksPerRev() * 360;
            }
            case "wheel" -> {
                RobotConfig.Motor dm = driveMotor(b.device);
                if (dm == null) return 0;
                for (Snapshot.Motor m : s.motors()) {
                    if (m.name().equals(dm.name())) {
                        return m.rawTicks() / m.ticksPerRev() * 360 * (dm.mountedReversed() ? -1 : 1) / robot.drivetrain().gearRatio();
                    }
                }
            }
            default -> { }
        }
        return 0;
    }

    /** Shows binding #index (in the model config) at a fraction (0..1) of its device's range instead of the simulated value; -1 stops. */
    public void preview(int index, double fraction) {
        previewIndex = index;
        previewFraction = fraction;
    }

    void update(Snapshot s) {
        for (int i = 0; i < active.size(); i++) {
            Active a = active.get(i);
            ModelConfig.Binding b = a.binding();
            double v = a.index() == previewIndex ? a.range()[0] + (a.range()[1] - a.range()[0]) * previewFraction : value(b, s);
            double d = (v - b.zero) * b.scale;
            if (a.translate()) {
                for (int k = 0; k < a.translates().size(); k++) {
                    double[] p = a.perInch().get(k);
                    Translate t = a.translates().get(k);
                    t.setX(p[0] * d);
                    t.setY(p[1] * d);
                    t.setZ(p[2] * d);
                }
            } else {
                double shown = a.spin() != null && a.index() != previewIndex ? a.spin().next(d) : d;
                for (Rotate r : a.rotates()) r.setAngle(shown);
            }
        }
    }

    // ------------------------------------------------------------------ selection (Model tab)

    /** The model part a picked JavaFX node belongs to, or null. */
    public static ModelData.Node partOf(Node picked) {
        for (Node n = picked; n != null; n = n.getParent()) {
            if (n.getUserData() instanceof ModelData.Node m) return m;
        }
        return null;
    }

    /** Highlights a part (and everything under it); null clears. */
    public void select(ModelData.Node node) {
        highlighted.forEach(MeshView::setMaterial);
        highlighted.clear();
        Group g = node == null ? null : groups.get(node);
        if (g != null) highlight(g);
    }

    private void highlight(Node n) {
        if (n instanceof MeshView mv && mv.getMaterial() instanceof PhongMaterial pm) {
            highlighted.put(mv, pm);
            mv.setMaterial(HIGHLIGHT);
        } else if (n instanceof Group g) {
            for (Node c : g.getChildren()) highlight(c);
        }
    }

    /** Center of a part in robot coordinates (inches), for setting pivots. */
    public double[] centerIn(ModelData.Node node) {
        double[] parentWorld = ModelData.multiply(world.get(node), ModelData.inverseAffine(node.matrix));
        double[] bb = ModelData.bounds(node, parentWorld);
        return new double[]{(bb[0] + bb[3]) / 2 / IN, (bb[1] + bb[4]) / 2 / IN, (bb[2] + bb[5]) / 2 / IN};
    }

    /** Robot-frame bounds of the whole model {minX..maxZ} in meters. */
    public double[] boundsMeters() {
        return ModelData.bounds(data.root, ModelData.multiply(world.get(data.root), ModelData.inverseAffine(data.root.matrix)));
    }

    private static double[] normalize(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return n < 1e-12 ? new double[]{0, 0, 1} : new double[]{v[0] / n, v[1] / n, v[2] / n};
    }

    private static double determinant(double[] m) {
        return m[0] * (m[5] * m[10] - m[6] * m[9]) - m[1] * (m[4] * m[10] - m[6] * m[8]) + m[2] * (m[4] * m[9] - m[5] * m[8]);
    }
}
