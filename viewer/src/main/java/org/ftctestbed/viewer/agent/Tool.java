package org.ftctestbed.viewer.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One thing an agent can do: its name, what it does (the description agents read to pick it),
 * its arguments (a JSON Schema) and the code. A handler returns data (sent as JSON text and as
 * MCP structured content) and any pictures.
 */
public record Tool(String name, String title, String group, String description, Map<String, Object> inputSchema,
                   boolean readOnly, Handler handler) {

    public interface Handler {
        Result call(Json.Args args) throws Exception;
    }

    /** What a tool returns: data (a map), pictures (PNG), and a short note shown before the data. */
    public record Result(Map<String, Object> data, List<byte[]> pngs, String note) {
        public static Result of(Map<String, Object> data) {
            return new Result(data, List.of(), null);
        }

        public static Result of(Object... kv) {
            return of(Json.obj(kv));
        }

        public Result withPng(byte[] png) {
            List<byte[]> all = new ArrayList<>(pngs);
            all.add(png);
            return new Result(data, all, note);
        }

        public Result withNote(String n) {
            return new Result(data, pngs, n);
        }
    }

    /** Builds an object schema property by property. */
    public static final class Schema {
        private final Map<String, Object> props = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        public static Schema of() {
            return new Schema();
        }

        private Schema put(String name, Map<String, Object> p) {
            props.put(name, p);
            return this;
        }

        public Schema str(String name, String desc) {
            return put(name, Json.obj("type", "string", "description", desc));
        }

        public Schema num(String name, String desc) {
            return put(name, Json.obj("type", "number", "description", desc));
        }

        public Schema integer(String name, String desc) {
            return put(name, Json.obj("type", "integer", "description", desc));
        }

        public Schema bool(String name, String desc) {
            return put(name, Json.obj("type", "boolean", "description", desc));
        }

        public Schema oneOf(String name, String desc, String... values) {
            return put(name, Json.obj("type", "string", "enum", List.of(values), "description", desc));
        }

        public Schema strings(String name, String desc) {
            return put(name, Json.obj("type", "array", "items", Json.obj("type", "string"), "description", desc));
        }

        public Schema array(String name, String desc, Map<String, Object> items) {
            return put(name, Json.obj("type", "array", "items", items, "description", desc));
        }

        public Schema object(String name, String desc) {
            return put(name, Json.obj("type", "object", "description", desc, "additionalProperties", true));
        }

        /** Any JSON value. */
        public Schema any(String name, String desc) {
            return put(name, Json.obj("description", desc));
        }

        /** The session id (optional when only one is open). */
        public Schema session() {
            return str("session", "Session id from sim_create (\"s1\"...), or \"live\" for the simulator's window. "
                    + "Optional when only one session is open.");
        }

        /** Which robot (1 = the first). */
        public Schema robot() {
            return integer("robot", "Which robot: 1 (default) or 2 for the partner.");
        }

        public Schema required(String... names) {
            required.addAll(List.of(names));
            return this;
        }

        public Map<String, Object> build() {
            Map<String, Object> s = Json.obj("type", "object", "properties", props);
            if (!required.isEmpty()) s.put("required", required);
            return s;
        }

        /** An item schema: an object with these properties. */
        public Map<String, Object> item() {
            return build();
        }
    }
}
