package org.ftctestbed.viewer.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The agent API over HTTP on this computer only (127.0.0.1): MCP's Streamable HTTP transport at
 * POST /mcp (JSON replies, no server-sent stream), and plain REST for scripts:
 * GET /api/health, GET /api/tools, POST /api/tools/NAME (the arguments as the body),
 * GET /api/docs/guide|tools|readme. Every route but health needs "Authorization: Bearer TOKEN".
 * Requests from web pages (an Origin that isn't localhost) are refused, so a site open in a
 * browser can't reach it.
 */
public final class HttpTransport {
    private final AgentApi api;
    private final String token;
    private final McpServer mcp;
    private HttpServer server;
    private ExecutorService pool;
    private int port;

    public HttpTransport(AgentApi api, String token) {
        if (token == null || token.length() < 16) throw new IllegalArgumentException("The HTTP transport needs a token of 16+ characters");
        this.api = api;
        this.token = token;
        this.mcp = new McpServer(api);
    }

    /** Starts listening on 127.0.0.1:port (0: any free port). Returns the port. */
    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 32);
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent API http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::route);
        server.start();
        this.port = server.getAddress().getPort();
        return this.port;
    }

    public int port() {
        return port;
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (!localOrigin(ex)) {
                send(ex, 403, "application/json", Json.write(Json.obj("error", "Requests from web pages are refused")));
                return;
            }
            if (path.equals("/api/health")) {
                send(ex, 200, "application/json", Json.write(Json.obj("ok", true, "name", AgentApi.NAME, "version", AgentApi.VERSION,
                        "live", api.sessions().live() != null)));
                return;
            }
            if (!authorized(ex)) {
                ex.getResponseHeaders().add("WWW-Authenticate", "Bearer");
                send(ex, 401, "application/json", Json.write(Json.obj("error", "Missing or wrong token: send Authorization: Bearer <token>")));
                return;
            }
            if (path.equals("/mcp")) {
                if (!method.equals("POST")) {
                    ex.getResponseHeaders().add("Allow", "POST");
                    send(ex, 405, "application/json", Json.write(Json.obj("error", "POST JSON-RPC messages here (no server-sent stream)")));
                    return;
                }
                String reply = mcp.handle(body(ex));
                if (reply == null) {
                    ex.sendResponseHeaders(202, -1);
                    ex.close();
                } else {
                    send(ex, 200, "application/json", reply);
                }
                return;
            }
            if (path.equals("/api/tools") && method.equals("GET")) {
                List<Object> out = new ArrayList<>();
                for (Tool t : api.tools()) out.add(Json.obj("name", t.name(), "title", t.title(), "group", t.group(),
                        "description", t.description(), "inputSchema", t.inputSchema(), "readOnly", t.readOnly()));
                send(ex, 200, "application/json", Json.write(Json.obj("tools", out)));
                return;
            }
            if (path.startsWith("/api/tools/") && method.equals("POST")) {
                String name = path.substring("/api/tools/".length());
                String b = body(ex);
                Object args = b.isBlank() ? Map.of() : Json.parse(b);
                AgentApi.Outcome o = api.call(name, args);
                if (o.error() != null) {
                    send(ex, api.tool(name) == null ? 404 : 400, "application/json", Json.write(Json.obj("ok", false, "error", o.error())));
                } else {
                    List<String> pngs = new ArrayList<>();
                    for (byte[] png : o.result().pngs()) pngs.add(Base64.getEncoder().encodeToString(png));
                    send(ex, 200, "application/json", Json.write(Json.obj("ok", true, "data", o.result().data(), "pngs", pngs)));
                }
                return;
            }
            if (path.startsWith("/api/docs/") && method.equals("GET")) {
                AgentApi.Outcome o = api.call("docs", Json.obj("topic", path.substring("/api/docs/".length())));
                if (o.error() != null) send(ex, 404, "text/plain; charset=utf-8", o.error());
                else send(ex, 200, "text/markdown; charset=utf-8", String.valueOf(o.result().data().get("markdown")));
                return;
            }
            send(ex, 404, "application/json", Json.write(Json.obj("error", "Not found. Routes: POST /mcp, GET /api/health, GET /api/tools, "
                    + "POST /api/tools/{name}, GET /api/docs/{guide|tools|readme}")));
        } catch (RuntimeException e) {
            send(ex, 400, "application/json", Json.write(Json.obj("error", String.valueOf(e.getMessage()))));
        }
    }

    private boolean authorized(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        byte[] given = h.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, token.getBytes(StandardCharsets.UTF_8));
    }

    /** No Origin (a program), or a page on this computer itself. */
    private static boolean localOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.equals("null")) return origin == null;
        try {
            String host = URI.create(origin).getHost();
            return host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String body(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(64 * 1024 * 1024);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int status, String type, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }
}
