package org.ftctestbed.sim.tools;

import org.ftctestbed.sim.config.Jsonc;
import org.ftctestbed.sim.config.MotorSpec;
import org.ftctestbed.sim.config.PartsCatalog;
import org.ftctestbed.sim.config.RobotConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A robot.jsonc as an editable tree (objects are maps), so calibration can try different
 * values and turn the result back into a RobotConfig. Comments are not kept.
 */
public final class ConfigTree {
    public final Path file;
    private final PartsCatalog catalog;
    private final Map<String, Object> root;

    private ConfigTree(Path file, PartsCatalog catalog, Map<String, Object> root) {
        this.file = file;
        this.catalog = catalog;
        this.root = root;
    }

    @SuppressWarnings("unchecked")
    public static ConfigTree load(Path robotFile) throws IOException {
        Object parsed = Jsonc.parse(Files.readString(robotFile), robotFile.toString());
        return new ConfigTree(robotFile, PartsCatalog.forConfigFile(robotFile), (Map<String, Object>) parsed);
    }

    public ConfigTree copy() {
        return new ConfigTree(file, catalog, deepCopy(root));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, copyValue(v)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object copyValue(Object v) {
        if (v instanceof Map<?, ?> map) return deepCopy((Map<String, Object>) map);
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object o : list) out.add(copyValue(o));
            return out;
        }
        return v;
    }

    /** The object at a path of keys, created if missing. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> object(String... path) {
        Map<String, Object> m = root;
        for (String key : path) {
            Object next = m.get(key);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                m.put(key, next);
            }
            m = (Map<String, Object>) next;
        }
        return m;
    }

    /** Sets a top-level setting. */
    public void put(String key, Object value) {
        root.put(key, value);
    }

    /** The entry of a list (e.g. "mechanisms") whose given key equals value, or null. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> find(String list, String key, String value) {
        if (!(root.get(list) instanceof List<?> l)) return null;
        for (Object o : l) {
            if (o instanceof Map<?, ?> m && value.equals(m.get(key))) return (Map<String, Object>) m;
        }
        return null;
    }

    /** The mechanism that the named motor drives, or null. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> mechanismOf(String motor) {
        if (!(root.get("mechanisms") instanceof List<?> l)) return null;
        for (Object o : l) {
            if (o instanceof Map<?, ?> m && m.get("motors") instanceof List<?> motors && motors.contains(motor)) {
                return (Map<String, Object>) m;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> addToList(String list, Map<String, Object> entry) {
        Object l = root.get(list);
        if (!(l instanceof List)) {
            l = new ArrayList<Object>();
            root.put(list, l);
        }
        ((List<Object>) l).add(entry);
        return entry;
    }

    /** Replaces a motor's "type" with its full spec, its free speed and stall torque scaled. */
    public void scaleMotor(Map<String, Object> motorEntry, MotorSpec base, double speedScale, double torqueScale) {
        Map<String, Object> spec = new LinkedHashMap<>();
        if (catalog.find("motors", base.id()) != null) spec.put("base", base.id());
        spec.put("ticksPerRev", base.ticksPerRev());
        spec.put("freeRpm", round(base.freeRpm() * speedScale, 1));
        spec.put("stallTorqueNm", round(base.stallTorqueNm() * torqueScale, 4));
        spec.put("stallCurrentA", base.stallCurrentA());
        spec.put("freeCurrentA", base.freeCurrentA());
        motorEntry.put("type", spec);
    }

    /** Sets a mass, replacing whatever unit key (Lbs/Kg/G) the entry used. */
    public static void setMassKg(Map<String, Object> entry, String key, double kg) {
        entry.remove(key + "Lbs");
        entry.remove(key + "G");
        entry.put(key + "Kg", round(kg, 4));
    }

    public static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    public RobotConfig toConfig() {
        return RobotConfig.parse(toJson(), file.getFileName().toString(), catalog);
    }

    public String toJson() {
        StringBuilder b = new StringBuilder();
        write(b, root, "");
        return b.toString();
    }

    private static void write(StringBuilder b, Object v, String indent) {
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                b.append(indent).append("  ").append(quote(String.valueOf(e.getKey()))).append(": ");
                write(b, e.getValue(), indent + "  ");
                if (++i < m.size()) b.append(',');
                b.append('\n');
            }
            b.append(indent).append('}');
        } else if (v instanceof List<?> l) {
            b.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) b.append(", ");
                write(b, l.get(i), indent + "  ");
            }
            b.append(']');
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
            else b.append(String.format(Locale.US, "%s", d));
        } else if (v instanceof Boolean || v == null) {
            b.append(v);
        } else {
            b.append(quote(v.toString()));
        }
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}
