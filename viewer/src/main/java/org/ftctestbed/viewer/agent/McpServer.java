package org.ftctestbed.viewer.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The Model Context Protocol (JSON-RPC 2.0) over any transport: initialize, tools, resources
 * (the docs and the project's configs and code) and prompts (ready-made test plans). One
 * instance serves one connection; handle() may be called from several threads at once.
 */
public final class McpServer {
    static final List<String> VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    private final AgentApi api;
    private volatile String version = VERSIONS.get(0);

    public McpServer(AgentApi api) {
        this.api = api;
    }

    /** One message (or a batch) in, the reply out: null when nothing is owed (notifications, responses). */
    public String handle(String text) {
        Object msg;
        try {
            msg = Json.parse(text);
        } catch (RuntimeException e) {
            return Json.write(error(null, -32700, "Parse error: " + e.getMessage()));
        }
        if (msg instanceof List<?> batch) {
            if (batch.isEmpty()) return Json.write(error(null, -32600, "Empty batch"));
            List<Object> replies = new ArrayList<>();
            for (Object m : batch) {
                Object r = one(m);
                if (r != null) replies.add(r);
            }
            return replies.isEmpty() ? null : Json.write(replies);
        }
        Object r = one(msg);
        return r == null ? null : Json.write(r);
    }

    private Object one(Object m) {
        if (!(m instanceof Map<?, ?> req)) return error(null, -32600, "Invalid request: expected an object");
        Object id = req.get("id");
        Object method = req.get("method");
        if (method == null) return null; // a response to something we sent (we send nothing): ignore
        if (!(method instanceof String name)) return error(id, -32600, "Invalid request: method must be a string");
        boolean notification = !req.containsKey("id");
        Json.Args params = new Json.Args(req.get("params"));
        try {
            Object result = switch (name) {
                case "initialize" -> initialize(params);
                case "ping", "logging/setLevel" -> Json.obj();
                case "tools/list" -> toolsList();
                case "tools/call" -> toolsCall(params);
                case "resources/list" -> resourcesList();
                case "resources/templates/list" -> Json.obj("resourceTemplates", List.of(
                        Json.obj("uriTemplate", "ftc-sim://file/{path}", "name", "Project file",
                                "description", "Any text file in the simulator project, by path relative to the project.", "mimeType", "text/plain")));
                case "resources/read" -> resourcesRead(params);
                case "prompts/list" -> Prompts.list();
                case "prompts/get" -> Prompts.get(params);
                case "completion/complete" -> Json.obj("completion", Json.obj("values", List.of(), "hasMore", false));
                default -> {
                    if (name.startsWith("notifications/")) yield null;
                    throw new RpcError(-32601, "Method not found: " + name);
                }
            };
            if (notification) return null;
            return Json.obj("jsonrpc", "2.0", "id", id, "result", result == null ? Json.obj() : result);
        } catch (RpcError e) {
            return notification ? null : error(id, e.code, e.getMessage());
        } catch (Json.ToolError e) {
            return notification ? null : error(id, -32602, e.getMessage());
        } catch (RuntimeException e) {
            return notification ? null : error(id, -32603, "Internal error: " + e);
        }
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        return Json.obj("jsonrpc", "2.0", "id", id, "error", Json.obj("code", code, "message", message));
    }

    private static final class RpcError extends RuntimeException {
        final int code;

        RpcError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private Map<String, Object> initialize(Json.Args p) {
        String asked = p.str("protocolVersion", VERSIONS.get(0));
        version = VERSIONS.contains(asked) ? asked : VERSIONS.get(0);
        return Json.obj("protocolVersion", version,
                "capabilities", Json.obj("tools", Json.obj("listChanged", false), "resources", Json.obj("listChanged", false, "subscribe", false),
                        "prompts", Json.obj("listChanged", false), "logging", Json.obj()),
                "serverInfo", Json.obj("name", AgentApi.NAME, "title", "FTC Test Suite simulator", "version", AgentApi.VERSION),
                "instructions", AgentApi.INSTRUCTIONS);
    }

    private Map<String, Object> toolsList() {
        List<Object> out = new ArrayList<>();
        for (Tool t : api.tools()) {
            out.add(Json.obj("name", t.name(), "title", t.title(), "description", t.description(), "inputSchema", t.inputSchema(),
                    "annotations", Json.obj("title", t.title(), "readOnlyHint", t.readOnly(), "destructiveHint", false,
                            "idempotentHint", t.readOnly(), "openWorldHint", false)));
        }
        return Json.obj("tools", out);
    }

    private Map<String, Object> toolsCall(Json.Args p) {
        String name = p.str("name");
        if (api.tool(name) == null) throw new RpcError(-32602, "Unknown tool: " + name);
        AgentApi.Outcome o = api.call(name, p.raw("arguments"));
        return result(o, !version.equals("2024-11-05") && !version.equals("2025-03-26"));
    }

    /** A tool's outcome as MCP content: the data as JSON text, then any pictures. */
    static Map<String, Object> result(AgentApi.Outcome o, boolean structured) {
        List<Object> content = new ArrayList<>();
        if (o.error() != null) {
            content.add(Json.obj("type", "text", "text", o.error()));
            return Json.obj("content", content, "isError", true);
        }
        Tool.Result r = o.result();
        String text = Json.pretty(r.data());
        if (r.note() != null) text = r.note() + "\n" + text;
        content.add(Json.obj("type", "text", "text", text));
        for (byte[] png : r.pngs()) content.add(Json.obj("type", "image", "data", Base64.getEncoder().encodeToString(png), "mimeType", "image/png"));
        Map<String, Object> out = Json.obj("content", content, "isError", false);
        if (structured) out.put("structuredContent", r.data());
        return out;
    }

    // ------------------------------------------------------------------ resources

    private record Doc(String uri, String name, String description, String mime) {}

    private List<Doc> docs() {
        List<Doc> out = new ArrayList<>(List.of(
                new Doc("ftc-sim://docs/guide", "Agent API guide", "How to use this server: workflows, conventions, recipes, troubleshooting.", "text/markdown"),
                new Doc("ftc-sim://docs/tools", "Tool reference", "Every tool and argument (generated).", "text/markdown"),
                new Doc("ftc-sim://docs/readme", "Simulator README", "The simulator: configs, physics, robot packages, team code.", "text/markdown")));
        for (String f : List.of("config/robot.jsonc", "config/field.jsonc")) {
            if (Files.exists(api.project().resolve(f))) out.add(new Doc("ftc-sim://file/" + f, f, "The project's " + f, "text/plain"));
        }
        return out;
    }

    private Map<String, Object> resourcesList() {
        List<Object> out = new ArrayList<>();
        for (Doc d : docs()) out.add(Json.obj("uri", d.uri(), "name", d.name(), "description", d.description(), "mimeType", d.mime()));
        return Json.obj("resources", out);
    }

    private Map<String, Object> resourcesRead(Json.Args p) {
        String uri = p.str("uri");
        String text;
        String mime = "text/markdown";
        try {
            switch (uri) {
                case "ftc-sim://docs/guide" -> text = api.guide();
                case "ftc-sim://docs/tools" -> text = ToolDocs.markdown(api.tools());
                case "ftc-sim://docs/readme" -> {
                    Path f = api.project().resolve("README.md");
                    text = Files.exists(f) ? Files.readString(f) : "No README.md";
                }
                default -> {
                    if (!uri.startsWith("ftc-sim://file/")) throw new RpcError(-32002, "Resource not found: " + uri);
                    Path f = api.files().resolve(uri.substring("ftc-sim://file/".length()));
                    if (!Files.isRegularFile(f)) throw new RpcError(-32002, "Resource not found: " + uri);
                    text = Files.readString(f);
                    mime = "text/plain";
                }
            }
        } catch (java.io.IOException e) {
            throw new RpcError(-32603, "Couldn't read " + uri + ": " + e.getMessage());
        }
        return Json.obj("contents", List.of(Json.obj("uri", uri, "mimeType", mime, "text", text)));
    }
}
