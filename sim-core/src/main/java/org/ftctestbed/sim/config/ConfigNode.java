package org.ftctestbed.sim.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed, unit-aware access to a parsed config object. Lengths, masses and angles can be
 * given in any supported unit by suffixing the key: "widthIn"/"widthMm"/"widthCm"/"widthM",
 * "massLbs"/"massKg", "headingDeg"/"headingRad". Keys that are never read are reported
 * as warnings so typos don't silently fall back to defaults.
 */
public final class ConfigNode {
    private static final double IN = 0.0254;
    private static final double LB = 0.45359237;

    private final String path;
    private final Map<String, Object> map;
    private final Set<String> used = new HashSet<>();
    private final List<ConfigNode> children = new ArrayList<>();

    public ConfigNode(String path, Map<String, Object> map) {
        this.path = path;
        this.map = map;
    }

    @SuppressWarnings("unchecked")
    public static ConfigNode fromObject(String path, Object parsed) {
        if (!(parsed instanceof Map)) throw new ConfigException(path + ": expected an object { ... }");
        return new ConfigNode(path, (Map<String, Object>) parsed);
    }

    public String path() { return path; }

    public boolean has(String key) { return map.containsKey(key); }

    private Object raw(String key) {
        used.add(key);
        return map.get(key);
    }

    public double getDouble(String key, double def) {
        Object v = raw(key);
        if (v == null) return def;
        if (!(v instanceof Double)) throw new ConfigException(path + "." + key + ": expected a number");
        return (Double) v;
    }

    public int getInt(String key, int def) {
        return (int) Math.round(getDouble(key, def));
    }

    public boolean getBoolean(String key, boolean def) {
        Object v = raw(key);
        if (v == null) return def;
        if (!(v instanceof Boolean)) throw new ConfigException(path + "." + key + ": expected true or false");
        return (Boolean) v;
    }

    public String getString(String key, String def) {
        Object v = raw(key);
        if (v == null) return def;
        if (!(v instanceof String)) throw new ConfigException(path + "." + key + ": expected a string");
        return (String) v;
    }

    public String requireString(String key) {
        String v = getString(key, null);
        if (v == null) throw new ConfigException(path + ": missing required \"" + key + "\"");
        return v;
    }

    public Object getRaw(String key) { return raw(key); }

    public ConfigNode child(String key) {
        Object v = raw(key);
        if (v == null) return null;
        ConfigNode c = fromObject(path + "." + key, v);
        children.add(c);
        return c;
    }

    public List<ConfigNode> children(String key) {
        Object v = raw(key);
        List<ConfigNode> result = new ArrayList<>();
        if (v == null) return result;
        if (!(v instanceof List)) throw new ConfigException(path + "." + key + ": expected a list [ ... ]");
        List<?> list = (List<?>) v;
        for (int k = 0; k < list.size(); k++) {
            ConfigNode c = fromObject(path + "." + key + "[" + k + "]", list.get(k));
            children.add(c);
            result.add(c);
        }
        return result;
    }

    public List<String> getStringList(String key) {
        Object v = raw(key);
        List<String> result = new ArrayList<>();
        if (v == null) return result;
        if (v instanceof String) {
            result.add((String) v);
            return result;
        }
        if (!(v instanceof List)) throw new ConfigException(path + "." + key + ": expected a list of strings");
        for (Object o : (List<?>) v) result.add(String.valueOf(o));
        return result;
    }

    /** Length in meters, from key + In/Mm/Cm/M. */
    public double length(String key, double defaultMeters) {
        if (has(key + "In")) return getDouble(key + "In", 0) * IN;
        if (has(key + "Mm")) return getDouble(key + "Mm", 0) / 1000.0;
        if (has(key + "Cm")) return getDouble(key + "Cm", 0) / 100.0;
        if (has(key + "M")) return getDouble(key + "M", 0);
        return defaultMeters;
    }

    public boolean hasMass(String key) {
        return has(key + "Lbs") || has(key + "Kg") || has(key + "G");
    }

    public boolean hasAngle(String key) {
        return has(key + "Deg") || has(key + "Rad");
    }

    public boolean hasLength(String key) {
        return has(key + "In") || has(key + "Mm") || has(key + "Cm") || has(key + "M");
    }

    /** Mass in kg, from key + Lbs/Kg/G. */
    public double mass(String key, double defaultKg) {
        if (has(key + "Lbs")) return getDouble(key + "Lbs", 0) * LB;
        if (has(key + "Kg")) return getDouble(key + "Kg", 0);
        if (has(key + "G")) return getDouble(key + "G", 0) / 1000.0;
        return defaultKg;
    }

    /** Angle in radians, from key + Deg/Rad. */
    public double angle(String key, double defaultRad) {
        if (has(key + "Deg")) return Math.toRadians(getDouble(key + "Deg", 0));
        if (has(key + "Rad")) return getDouble(key + "Rad", 0);
        return defaultRad;
    }

    /** Collects "unknown setting" warnings for this node and every child that was read. */
    public void collectUnused(List<String> warnings) {
        for (String key : map.keySet()) {
            if (!used.contains(key)) warnings.add(path + "." + key + " is not a recognized setting (typo?) - ignored");
        }
        for (ConfigNode c : children) c.collectUnused(warnings);
    }
}
