package org.ftctestbed.viewer.agent;

import org.ftctestbed.sim.config.Jsonc;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON for the agent API: parsing (the simulator's own reader) and writing any value, records
 * included, so a snapshot of the simulation goes out whole, every field of every part, without a
 * list to keep up to date. NaN and infinities become null.
 */
public final class Json {
    private Json() {}

    /** Parses JSON text (comments and trailing commas allowed): Map, List, Double, Boolean, String or null. */
    public static Object parse(String text) {
        return Jsonc.parse(text, "request");
    }

    /** An ordered object from key, value, key, value... */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static String write(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value, -1, 0);
        return b.toString();
    }

    /** Indented, for people reading it. */
    public static String pretty(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value, 2, 0);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v, int indent, int depth) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String s) {
            b.append(Jsonc.quote(s));
        } else if (v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof Double d) {
            number(b, d);
        } else if (v instanceof Float f) {
            number(b, f.doubleValue());
        } else if (v instanceof Number) {
            b.append(v);
        } else if (v instanceof Character c) {
            b.append(Jsonc.quote(String.valueOf(c)));
        } else if (v instanceof Enum<?> e) {
            b.append(Jsonc.quote(e.name()));
        } else if (v instanceof Raw r) {
            b.append(r.json());
        } else if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                newline(b, indent, depth + 1);
                b.append(Jsonc.quote(String.valueOf(e.getKey()))).append(indent >= 0 ? ": " : ":");
                write(b, e.getValue(), indent, depth + 1);
            }
            if (!m.isEmpty()) newline(b, indent, depth);
            b.append('}');
        } else if (v instanceof Collection<?> c) {
            b.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) b.append(',');
                first = false;
                if (!(o instanceof Number)) newline(b, indent, depth + 1);
                write(b, o, indent, depth + 1);
            }
            if (!c.isEmpty() && !(c.iterator().next() instanceof Number)) newline(b, indent, depth);
            b.append(']');
        } else if (v.getClass().isArray()) {
            int n = Array.getLength(v);
            b.append('[');
            for (int i = 0; i < n; i++) {
                if (i > 0) b.append(',');
                write(b, Array.get(v, i), indent, depth + 1);
            }
            b.append(']');
        } else if (v instanceof Record r) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (RecordComponent c : r.getClass().getRecordComponents()) {
                try {
                    c.getAccessor().setAccessible(true);
                    m.put(c.getName(), c.getAccessor().invoke(r));
                } catch (ReflectiveOperationException e) {
                    m.put(c.getName(), "(unreadable: " + e + ")");
                }
            }
            write(b, m, indent, depth);
        } else {
            b.append(Jsonc.quote(v.toString()));
        }
    }

    private static void number(StringBuilder b, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
        else if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
        else b.append(d);
    }

    private static void newline(StringBuilder b, int indent, int depth) {
        if (indent < 0) return;
        b.append('\n');
        for (int i = 0; i < indent * depth; i++) b.append(' ');
    }

    /** Text that is already JSON (a schema, say), written as it is. */
    public record Raw(String json) {}

    // ------------------------------------------------------------------ reading arguments

    /** A tool call's arguments, with typed getters that explain what's wrong. */
    public static final class Args {
        private final Map<String, Object> map;

        @SuppressWarnings("unchecked")
        public Args(Object o) {
            map = o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }

        public boolean has(String key) {
            return map.get(key) != null;
        }

        public Object raw(String key) {
            return map.get(key);
        }

        public String str(String key, String def) {
            Object v = map.get(key);
            if (v == null) return def;
            if (v instanceof String s) return s;
            if (v instanceof Double d && d == Math.rint(d)) return String.valueOf(d.longValue());
            return String.valueOf(v);
        }

        public String str(String key) {
            String s = str(key, null);
            if (s == null) throw new ToolError("\"" + key + "\" is required");
            return s;
        }

        public double num(String key, double def) {
            Object v = map.get(key);
            if (v == null) return def;
            if (v instanceof Number n) return n.doubleValue();
            if (v instanceof String s) {
                try {
                    return Double.parseDouble(s.trim());
                } catch (NumberFormatException e) {
                    throw new ToolError("\"" + key + "\" should be a number, not \"" + s + "\"");
                }
            }
            throw new ToolError("\"" + key + "\" should be a number");
        }

        public double num(String key) {
            if (!has(key)) throw new ToolError("\"" + key + "\" is required");
            return num(key, 0);
        }

        public int integer(String key, int def) {
            return (int) Math.round(num(key, def));
        }

        public boolean bool(String key, boolean def) {
            Object v = map.get(key);
            if (v == null) return def;
            if (v instanceof Boolean b) return b;
            if (v instanceof String s) return s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes");
            if (v instanceof Number n) return n.doubleValue() != 0;
            return def;
        }

        @SuppressWarnings("unchecked")
        public List<Object> list(String key) {
            Object v = map.get(key);
            if (v == null) return List.of();
            if (v instanceof List<?> l) return (List<Object>) l;
            return List.of(v);
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> map(String key) {
            Object v = map.get(key);
            if (v == null) return Map.of();
            if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
            throw new ToolError("\"" + key + "\" should be an object");
        }

        public Map<String, Object> all() {
            return map;
        }
    }

    /** A tool failed in a way the agent can fix (a wrong name, a missing argument): reported as the tool's result. */
    public static final class ToolError extends RuntimeException {
        public ToolError(String message) {
            super(message);
        }
    }
}
