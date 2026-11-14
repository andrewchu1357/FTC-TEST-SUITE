package org.ftctestbed.sim.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * FTC parts (motors, servos, wheels, slide kits, odometry pods, sensors) that robot configs
 * refer to by id. The built-in catalog (resource parts/catalog.jsonc) can be extended or
 * overridden by teams in {@code config/parts/*.jsonc} or a robot package's {@code parts/} folder.
 */
public final class PartsCatalog {
    public static final List<String> CATEGORIES = List.of("motors", "servos", "wheels", "slides", "odometryPods", "sensors");
    public static final double DEFAULT_SERVO_VOLTS = 5.0; // REV hub servo ports
    private static final double KGCM = 0.0980665;

    /** One catalog entry. specs holds the raw settings (unit-suffixed keys, as in the file). */
    public record Part(String category, String id, String name, String vendor, String sku, String note,
                       String source, Map<String, Object> specs) {
        ConfigNode node() {
            return new ConfigNode(category + "/" + id, specs);
        }

        double number(String key, double def) {
            return specs.get(key) instanceof Double d ? d : def;
        }

        public boolean has(String key) { return specs.containsKey(key); }
    }

    /** A servo's characteristics at a given supply voltage. */
    public record ServoSpec(double rangeRad, double secPer60Deg, double torqueKgCm, double usAtRangeStart,
                            double usAtRangeEnd, boolean continuousMode) {
        /** Top speed when the servo runs in continuous-rotation mode. */
        public double crMaxRpm() {
            return 60.0 / (6 * secPer60Deg);
        }
    }

    private static PartsCatalog builtIn;
    private final Map<String, Map<String, Part>> parts = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<Path> folders = new ArrayList<>();

    private PartsCatalog() {
        for (String c : CATEGORIES) parts.put(c, new LinkedHashMap<>());
    }

    /** The catalog that ships with the simulator. */
    public static synchronized PartsCatalog builtIn() {
        if (builtIn == null) {
            PartsCatalog c = new PartsCatalog();
            try (InputStream in = PartsCatalog.class.getResourceAsStream("/parts/catalog.jsonc")) {
                if (in == null) throw new IllegalStateException("parts/catalog.jsonc missing from the classpath");
                c.addFile(new String(in.readAllBytes(), StandardCharsets.UTF_8), "built-in");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            builtIn = c;
        }
        return builtIn;
    }

    /**
     * The built-in catalog plus team parts for a robot config file: every {@code *.jsonc} in a
     * {@code parts/} folder next to it, and in {@code config/parts/} of the folders above it.
     */
    public static PartsCatalog forConfigFile(Path robotFile) {
        Set<Path> dirs = new LinkedHashSet<>();
        Path dir = robotFile.toAbsolutePath().normalize().getParent();
        if (dir != null) dirs.add(dir.resolve("parts"));
        for (Path d = dir; d != null; d = d.getParent()) dirs.add(d.resolve("config").resolve("parts"));
        return withFolders(new ArrayList<>(dirs));
    }

    /** The built-in catalog plus every {@code *.jsonc} file in the given folders (later folders win). */
    public static PartsCatalog withFolders(List<Path> dirs) {
        PartsCatalog c = new PartsCatalog();
        c.merge(builtIn());
        List<Path> ordered = new ArrayList<>(dirs);
        java.util.Collections.reverse(ordered); // nearest folder (listed first) overrides farther ones
        for (Path d : ordered) {
            if (!Files.isDirectory(d)) continue;
            c.folders.add(d);
            try (Stream<Path> files = Files.list(d)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".jsonc") || p.toString().endsWith(".json")).sorted().toList()) {
                    try {
                        c.addFile(Files.readString(f), f.getFileName().toString());
                    } catch (IOException | ConfigException e) {
                        c.warnings.add("Parts file " + f + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                c.warnings.add("Cannot list " + d + ": " + e.getMessage());
            }
        }
        return c;
    }

    private void merge(PartsCatalog other) {
        other.parts.forEach((cat, m) -> parts.get(cat).putAll(m));
        warnings.addAll(other.warnings);
    }

    @SuppressWarnings("unchecked")
    private void addFile(String text, String source) {
        Object parsed = Jsonc.parse(text, source);
        if (!(parsed instanceof Map<?, ?> root)) throw new ConfigException(source + ": expected an object { ... }");
        for (Map.Entry<?, ?> e : root.entrySet()) {
            String cat = String.valueOf(e.getKey());
            if (!parts.containsKey(cat)) {
                warnings.add(source + ": unknown parts category \"" + cat + "\" (known: " + String.join(", ", CATEGORIES) + ")");
                continue;
            }
            if (!(e.getValue() instanceof List<?> list)) throw new ConfigException(source + "." + cat + ": expected a list [ ... ]");
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m) || !(m.get("id") instanceof String id)) {
                    throw new ConfigException(source + "." + cat + ": every part needs an \"id\"");
                }
                Map<String, Object> specs = new LinkedHashMap<>((Map<String, Object>) m);
                Part p = new Part(cat, id, str(specs.remove("name"), id), str(specs.remove("vendor"), ""),
                        str(specs.remove("sku"), ""), str(specs.remove("note"), ""), source, specs);
                specs.remove("id");
                parts.get(cat).put(id.toLowerCase(Locale.ROOT), p);
            }
        }
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    // ------------------------------------------------------------------ queries

    public Collection<Part> all(String category) {
        return parts.get(category).values();
    }

    public List<Part> all() {
        List<Part> out = new ArrayList<>();
        for (Map<String, Part> m : parts.values()) out.addAll(m.values());
        return out;
    }

    public List<String> warnings() { return warnings; }

    /** Team parts folders that exist (where new parts files should go). */
    public List<Path> folders() { return folders; }

    public Part find(String category, String id) {
        return parts.get(category).get(id.toLowerCase(Locale.ROOT));
    }

    public Part require(String category, String id, String where) {
        Part p = find(category, id);
        if (p == null) {
            List<String> ids = all(category).stream().map(Part::id).toList();
            throw new ConfigException(where + ": unknown " + singular(category) + " \"" + id + "\". Known: " + String.join(", ", ids)
                    + ". Add your own in config/parts/*.jsonc.");
        }
        return p;
    }

    private static String singular(String category) {
        return switch (category) {
            case "motors" -> "motor type";
            case "servos" -> "servo type";
            case "wheels" -> "wheel";
            case "slides" -> "slide kit";
            case "odometryPods" -> "odometry pod type";
            default -> "sensor type";
        };
    }

    public MotorSpec motor(String id, String where) {
        Part p = require("motors", id, where);
        ConfigNode n = p.node();
        return new MotorSpec(p.id(), n.getString("sdkName", p.name()), n.getDouble("ticksPerRev", 28), n.getDouble("freeRpm", 6000),
                n.has("stallTorqueKgCm") ? n.getDouble("stallTorqueKgCm", 0) * KGCM : n.getDouble("stallTorqueNm", 0.1),
                n.getDouble("stallCurrentA", 9.2), n.getDouble("freeCurrentA", 0.25));
    }

    public ServoSpec servo(String id, double volts, String where) {
        return servoSpec(require("servos", id, where), volts);
    }

    public static ServoSpec servoSpec(Part p, double volts) {
        ConfigNode n = p.node();
        return new ServoSpec(n.angle("range", Math.toRadians(300)),
                atVolts(p, "secPer60Deg", volts, true), atVolts(p, "torqueKgCm", volts, false),
                n.getDouble("usAtRangeStart", 500), n.getDouble("usAtRangeEnd", 2500), n.getBoolean("continuousMode", false));
    }

    /**
     * Interpolates a spec given at 4.8 V and/or 6 V for another supply voltage. With only one
     * voltage listed, speed scales with voltage (time per 60° inversely) and torque linearly.
     */
    static double atVolts(Part p, String key, double volts, boolean inverse) {
        boolean has48 = p.has(key + "4v8"), has6 = p.has(key + "6v");
        if (has48 && has6) {
            double a = p.number(key + "4v8", 0), b = p.number(key + "6v", 0);
            return a + (b - a) * (volts - 4.8) / 1.2;
        }
        if (!has48 && !has6) return p.number(key, inverse ? 0.2 : 0);
        double ref = has48 ? 4.8 : 6.0, v = p.number(key + (has48 ? "4v8" : "6v"), 0);
        return inverse ? v * ref / volts : v * volts / ref;
    }

    /** Wheel diameter (m) and mass (kg). */
    public double[] wheel(String id, String where) {
        ConfigNode n = require("wheels", id, where).node();
        return new double[]{n.length("diameter", 0.104), n.mass("mass", 0)};
    }

    /** Slide kit {travel, retracted length, spool diameter} in meters. */
    public double[] slide(String id, String where) {
        ConfigNode n = require("slides", id, where).node();
        return new double[]{n.length("travel", 0.6), n.length("retracted", 0.3), n.length("spoolDiameter", 0.0382)};
    }

    /** Odometry pod {wheel diameter (m, NaN if the pod doesn't fix it), ticks per rev}. */
    public double[] odometryPod(String id, String where) {
        ConfigNode n = require("odometryPods", id, where).node();
        return new double[]{n.length("wheelDiameter", Double.NaN), n.getDouble("ticksPerRev", 2000)};
    }

    public Part sensor(String id, String where) {
        return require("sensors", id, where);
    }

    /** A human-readable one-line summary of a part's key numbers, for the parts browser. */
    public static String summary(Part p) {
        ConfigNode n = p.node();
        return switch (p.category()) {
            case "motors" -> String.format(Locale.US, "%.0f rpm, %.1f ticks/rev, stall %.2f N·m",
                    n.getDouble("freeRpm", 0), n.getDouble("ticksPerRev", 0),
                    n.has("stallTorqueKgCm") ? n.getDouble("stallTorqueKgCm", 0) * KGCM : n.getDouble("stallTorqueNm", 0));
            case "servos" -> {
                ServoSpec s5 = servoSpec(p, DEFAULT_SERVO_VOLTS);
                yield String.format(Locale.US, "%.0f°, %.3f s/60° and %.1f kg·cm at 5 V%s", Math.toDegrees(s5.rangeRad()),
                        s5.secPer60Deg(), s5.torqueKgCm(), s5.continuousMode() ? String.format(Locale.US, ", CR %.0f rpm", s5.crMaxRpm()) : "");
            }
            case "wheels" -> String.format(Locale.US, "%s, %.0f mm, %.0f g", n.getString("type", "wheel"),
                    n.length("diameter", 0) * 1000, n.mass("mass", 0) * 1000);
            case "slides" -> String.format(Locale.US, "%.0f mm travel, %.0f mm retracted, %.0f mm per spool turn",
                    n.length("travel", 0) * 1000, n.length("retracted", 0) * 1000, n.length("spoolDiameter", 0) * 1000 * Math.PI);
            case "odometryPods" -> {
                double d = n.length("wheelDiameter", Double.NaN);
                yield String.format(Locale.US, "%.0f ticks/rev%s", n.getDouble("ticksPerRev", 0),
                        Double.isNaN(d) ? "" : String.format(Locale.US, ", %.0f mm wheel", d * 1000));
            }
            default -> n.getString("kind", "sensor") + (n.hasLength("maxRange")
                    ? String.format(Locale.US, ", %.1f m range", n.length("maxRange", 0)) : "");
        };
    }
}
