package org.ftctestbed.viewer.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The tool reference in Markdown, made from the tools themselves (docs/agent-tools.md is this). */
final class ToolDocs {
    private ToolDocs() {}

    static String markdown(List<Tool> tools) {
        StringBuilder b = new StringBuilder();
        b.append("# FTC Test Suite agent API: tool reference\n\n")
                .append("Generated from the server (`tools/ftc-sim-mcp --docs`); the guide is [agent-api.md](agent-api.md). ")
                .append("Every tool takes one JSON object of arguments and returns JSON (plus a PNG for pictures). ")
                .append("Positions are inches, headings degrees (field center origin, +x toward the blue wall, +y away from the audience, ")
                .append("heading 0 along +x, counter-clockwise positive), unless a field says otherwise.\n\n");
        Map<String, List<Tool>> groups = new LinkedHashMap<>();
        for (Tool t : tools) groups.computeIfAbsent(t.group(), k -> new ArrayList<>()).add(t);
        b.append("| Group | Tools |\n|---|---|\n");
        for (Map.Entry<String, List<Tool>> g : groups.entrySet()) {
            List<String> names = new ArrayList<>();
            for (Tool t : g.getValue()) names.add("[`" + t.name() + "`](#" + t.name() + ")");
            b.append("| ").append(g.getKey()).append(" | ").append(String.join(", ", names)).append(" |\n");
        }
        for (Map.Entry<String, List<Tool>> g : groups.entrySet()) {
            b.append("\n## ").append(g.getKey()).append("\n");
            for (Tool t : g.getValue()) {
                b.append("\n### ").append(t.name()).append("\n\n");
                b.append("**").append(t.title()).append("**").append(t.readOnly() ? " (reads only)" : "").append("\n\n");
                b.append(t.description()).append("\n\n");
                @SuppressWarnings("unchecked")
                Map<String, Object> props = (Map<String, Object>) t.inputSchema().get("properties");
                @SuppressWarnings("unchecked")
                List<String> required = (List<String>) t.inputSchema().getOrDefault("required", List.of());
                if (props == null || props.isEmpty()) {
                    b.append("No arguments.\n");
                    continue;
                }
                b.append("| Argument | Type | Description |\n|---|---|---|\n");
                for (Map.Entry<String, Object> p : props.entrySet()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> s = (Map<String, Object>) p.getValue();
                    b.append("| `").append(p.getKey()).append("`").append(required.contains(p.getKey()) ? " **(required)**" : "")
                            .append(" | ").append(type(s)).append(" | ").append(cell(String.valueOf(s.getOrDefault("description", "")))).append(" |\n");
                }
            }
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static String type(Map<String, Object> s) {
        Object e = s.get("enum");
        if (e instanceof List<?> l) return String.join(" \\| ", l.stream().map(v -> "`" + v + "`").toList());
        Object t = s.get("type");
        if (t == null) return "any";
        if ("array".equals(t)) {
            Map<String, Object> items = (Map<String, Object>) s.get("items");
            if (items != null && "object".equals(items.get("type")) && items.get("properties") instanceof Map<?, ?> p && !p.isEmpty()) {
                return "array of {" + String.join(", ", ((Map<String, Object>) p).keySet()) + "}";
            }
            return "array of " + (items == null ? "any" : items.getOrDefault("type", "any"));
        }
        return String.valueOf(t);
    }

    private static String cell(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }
}
