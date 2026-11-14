package org.ftctestbed.viewer.model;

import org.ftctestbed.sim.config.ConfigException;
import org.ftctestbed.sim.config.ConfigNode;
import org.ftctestbed.sim.config.Jsonc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How a CAD model is placed on the simulated robot and which parts move: the
 * {@code <robot>.model.jsonc} file next to the robot config.
 *
 * Positions and axes in bindings are in the robot frame (x forward, y left, z up, origin at the
 * center of the robot on the floor), in inches, so they don't depend on how the CAD was exported.
 */
public final class ModelConfig {
    private static final double IN = 0.0254;
    public static final List<String> UNITS = List.of("auto", "m", "mm", "cm", "in");

    /** A rotation applied to the CAD model to line it up with the robot (x forward, z up). */
    public record Rotation(char axis, double degrees) {}

    /**
     * Moves a CAD part with a simulated device.
     * source: "mechanism", "servo", "motor" or "wheel" (frontLeft/frontRight/backLeft/backRight).
     * Displacement = (value - zero) * scale, where value is inches for a slide mechanism and
     * degrees for everything else (arm angle, servo horn angle from center, rotations of a
     * motor, wheel, CR servo or free mechanism). motion "translate" moves along axis (inches),
     * "rotate" turns about axis through pivot (degrees).
     */
    public static final class Binding {
        public String node = "";
        public String source = "mechanism";
        public String device = "";
        public String motion = "auto";
        public double[] axis;   // robot frame; null = automatic
        public double[] pivot;  // robot frame, inches; null = center of the part
        public double scale = 1;
        public double zero = 0;

        public Binding copy() {
            Binding b = new Binding();
            b.node = node;
            b.source = source;
            b.device = device;
            b.motion = motion;
            b.axis = axis == null ? null : axis.clone();
            b.pivot = pivot == null ? null : pivot.clone();
            b.scale = scale;
            b.zero = zero;
            return b;
        }
    }

    public String file = "";
    public String units = "auto";
    public final List<Rotation> rotations = new ArrayList<>();
    public double[] offsetIn = {0, 0, 0};
    public boolean autoCenter = true;
    public boolean showGenerated = false;
    public final List<Binding> bindings = new ArrayList<>();

    public ModelConfig copy() {
        ModelConfig c = new ModelConfig();
        c.file = file;
        c.units = units;
        c.rotations.addAll(rotations);
        c.offsetIn = offsetIn.clone();
        c.autoCenter = autoCenter;
        c.showGenerated = showGenerated;
        for (Binding b : bindings) c.bindings.add(b.copy());
        return c;
    }

    /** robots/x/robot.jsonc → robots/x/robot.model.jsonc */
    public static Path fileFor(Path robotFile) {
        String n = robotFile.getFileName().toString();
        String base = n.endsWith(".jsonc") ? n.substring(0, n.length() - 6) : n.endsWith(".json") ? n.substring(0, n.length() - 5) : n;
        return robotFile.resolveSibling(base + ".model.jsonc");
    }

    public static ModelConfig load(Path file) throws IOException {
        ConfigNode root = ConfigNode.fromObject(file.getFileName().toString(), Jsonc.parse(Files.readString(file), file.toString()));
        ModelConfig c = new ModelConfig();
        c.file = root.requireString("file");
        c.units = root.getString("units", "auto").toLowerCase(Locale.ROOT);
        if (!UNITS.contains(c.units)) throw new ConfigException(root.path() + ".units: must be one of " + UNITS);
        for (ConfigNode r : root.children("rotations")) {
            String axis = r.requireString("axis").toLowerCase(Locale.ROOT);
            if (!List.of("x", "y", "z").contains(axis)) throw new ConfigException(r.path() + ".axis: must be \"x\", \"y\" or \"z\"");
            c.rotations.add(new Rotation(axis.charAt(0), r.getDouble("degrees", 0)));
        }
        c.offsetIn = vector(root, "offsetIn", new double[]{0, 0, 0});
        c.autoCenter = root.getBoolean("autoCenter", true);
        c.showGenerated = root.getBoolean("showGenerated", false);
        for (ConfigNode b : root.children("bindings")) {
            Binding x = new Binding();
            x.node = b.requireString("node");
            int sources = 0;
            for (String s : new String[]{"mechanism", "servo", "motor", "wheel"}) {
                if (b.has(s)) {
                    x.source = s;
                    x.device = b.requireString(s);
                    sources++;
                }
            }
            if (sources != 1) throw new ConfigException(b.path() + ": give exactly one of \"mechanism\", \"servo\", \"motor\" or \"wheel\"");
            x.motion = b.getString("motion", "auto");
            if (!List.of("auto", "translate", "rotate").contains(x.motion)) {
                throw new ConfigException(b.path() + ".motion: must be \"translate\", \"rotate\" or \"auto\"");
            }
            x.axis = vector(b, "axis", null);
            x.pivot = vector(b, "pivotIn", null);
            x.scale = b.getDouble("scale", 1);
            x.zero = b.getDouble("zero", 0);
            c.bindings.add(x);
        }
        List<String> unused = new ArrayList<>();
        root.collectUnused(unused);
        if (!unused.isEmpty()) throw new ConfigException(String.join("\n", unused));
        return c;
    }

    private static double[] vector(ConfigNode n, String key, double[] def) {
        Object raw = n.has(key) ? n.getRaw(key) : null;
        if (raw == null) return def;
        if (!(raw instanceof List<?> l) || l.size() != 3 || !l.stream().allMatch(o -> o instanceof Double)) {
            throw new ConfigException(n.path() + "." + key + ": expected [x, y, z]");
        }
        return new double[]{(Double) l.get(0), (Double) l.get(1), (Double) l.get(2)};
    }

    public void save(Path path) throws IOException {
        StringBuilder s = new StringBuilder();
        s.append("// 3D model of the robot for the simulator's 3D view (edit in the window's Model tab).\n");
        s.append("// rotations turn the CAD export so the robot's front faces +x and up is +z.\n");
        s.append("// Bindings move parts with simulated devices; axes and pivots are in robot coordinates\n");
        s.append("// (x forward, y left, z up, inches from the robot's center on the floor).\n");
        s.append("{\n");
        s.append("  \"file\": ").append(Jsonc.quote(file)).append(",\n");
        s.append("  \"units\": ").append(Jsonc.quote(units)).append(",\n");
        s.append("  \"rotations\": [");
        for (int i = 0; i < rotations.size(); i++) {
            Rotation r = rotations.get(i);
            s.append(i == 0 ? " " : ", ").append("{ \"axis\": \"").append(r.axis()).append("\", \"degrees\": ").append(num(r.degrees())).append(" }");
        }
        s.append(rotations.isEmpty() ? "],\n" : " ],\n");
        s.append("  \"autoCenter\": ").append(autoCenter).append(",\n");
        s.append("  \"offsetIn\": ").append(vec(offsetIn)).append(",\n");
        s.append("  \"showGenerated\": ").append(showGenerated).append(",\n");
        s.append("  \"bindings\": [");
        for (int i = 0; i < bindings.size(); i++) {
            Binding b = bindings.get(i);
            s.append(i == 0 ? "\n" : ",\n").append("    { \"node\": ").append(Jsonc.quote(b.node)).append(", \"")
                    .append(b.source).append("\": ").append(Jsonc.quote(b.device));
            if (!b.motion.equals("auto")) s.append(", \"motion\": \"").append(b.motion).append('"');
            if (b.axis != null) s.append(", \"axis\": ").append(vec(b.axis));
            if (b.pivot != null) s.append(", \"pivotIn\": ").append(vec(b.pivot));
            if (b.scale != 1) s.append(", \"scale\": ").append(num(b.scale));
            if (b.zero != 0) s.append(", \"zero\": ").append(num(b.zero));
            s.append(" }");
        }
        s.append(bindings.isEmpty() ? "]\n" : "\n  ]\n");
        s.append("}\n");
        Files.writeString(path, s.toString());
    }

    private static String vec(double[] v) {
        return "[" + num(v[0]) + ", " + num(v[1]) + ", " + num(v[2]) + "]";
    }

    private static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e9) return String.valueOf((long) d);
        return String.format(Locale.US, "%.4f", d).replaceAll("0+$", "");
    }

    /** Bindings whose part isn't in the model or whose device isn't on the robot. */
    public List<String> problems(ModelData model, org.ftctestbed.sim.config.RobotConfig robot) {
        java.util.Set<String> names = new java.util.HashSet<>();
        model.root.forEach(n -> names.add(n.name));
        java.util.Set<String> mechanisms = new java.util.HashSet<>(), servos = new java.util.HashSet<>(), motors = new java.util.HashSet<>();
        for (var m : robot.mechanisms()) mechanisms.add(m.name());
        for (var m : robot.allMotors()) {
            motors.add(m.name());
            mechanisms.add(m.name()); // a motor without a mechanism gets a free one of the same name
        }
        for (var s : robot.servos()) servos.add(s.name());
        List<String> out = new ArrayList<>();
        for (Binding b : bindings) {
            if (!names.contains(b.node)) out.add("Model part \"" + b.node + "\" not found");
            boolean ok = switch (b.source) {
                case "mechanism" -> mechanisms.contains(b.device);
                case "servo" -> servos.contains(b.device);
                case "motor" -> motors.contains(b.device);
                case "wheel" -> List.of("frontLeft", "frontRight", "backLeft", "backRight").contains(b.device);
                default -> false;
            };
            if (!ok) out.add("Binding for \"" + b.node + "\": the robot has no " + b.source + " named \"" + b.device + "\"");
        }
        return out;
    }

    // ------------------------------------------------------------------ placement

    /** Meters per model unit. */
    public double unitScale(ModelData model) {
        String u = units.equals("auto") ? detectUnits(model) : units;
        return switch (u) {
            case "mm" -> 0.001;
            case "cm" -> 0.01;
            case "in" -> IN;
            default -> 1.0;
        };
    }

    /** glTF is always meters. For STL/OBJ, guess from the size (an FTC robot is at most ~18 in / 457 mm). */
    public static String detectUnits(ModelData model) {
        if (model.units.equals("m")) return "m";
        double[] b = ModelData.bounds(model.root, ModelData.identity());
        double size = Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2]));
        if (size > 100) return "mm";
        if (size > 2.5) return "in";
        return "m";
    }

    /** CAD model coordinates → robot frame (meters). */
    public double[] alignment(ModelData model) {
        double[] m = ModelData.scale(unitScale(model));
        for (Rotation r : rotations) m = ModelData.multiply(ModelData.rotation(r.axis(), r.degrees()), m);
        double ox = offsetIn[0] * IN, oy = offsetIn[1] * IN, oz = offsetIn[2] * IN;
        if (autoCenter) {
            double[] b = ModelData.bounds(model.root, m);
            if (b[0] <= b[3]) {
                ox -= (b[0] + b[3]) / 2;
                oy -= (b[1] + b[4]) / 2;
                oz -= b[2];
            }
        }
        return ModelData.multiply(ModelData.translation(ox, oy, oz), m);
    }
}
