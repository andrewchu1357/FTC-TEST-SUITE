package org.ftctestbed.viewer.agent;

import org.ftctestbed.shim.SimHooks;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The agent API's own program (tools/ftc-sim-mcp runs it):
 *
 * <pre>
 *   --stdio          MCP over stdin/stdout, headless sessions in this process (the default)
 *   --http PORT      MCP (POST /mcp) and REST on 127.0.0.1:PORT; token from --token or FTC_SIM_TOKEN
 *   --live           MCP over stdin/stdout, passed on to the open simulator window (its live session)
 *   --docs           print the tool reference (Markdown) and exit
 *   --project DIR    the simulator project (default: found from the working directory)
 * </pre>
 */
public final class AgentMain {
    private AgentMain() {}

    public static void main(String[] args) throws Exception {
        String mode = "stdio";
        int port = -1;
        String token = System.getenv("FTC_SIM_TOKEN");
        Path project = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--stdio" -> mode = "stdio";
                case "--live" -> mode = "live";
                case "--docs" -> mode = "docs";
                case "--http" -> {
                    mode = "http";
                    if (i + 1 < args.length && args[i + 1].matches("\\d+")) port = Integer.parseInt(args[++i]);
                }
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--token" -> token = args[++i];
                case "--project" -> project = Path.of(args[++i]).toAbsolutePath().normalize();
                case "--help", "-h" -> {
                    System.out.println("Usage: ftc-sim-mcp [--stdio | --live | --http [PORT] [--token T] | --docs] [--project DIR]");
                    return;
                }
                default -> {
                    System.err.println("Unknown option " + args[i] + " (try --help)");
                    System.exit(2);
                }
            }
        }
        if (project == null) project = findProject();
        switch (mode) {
            case "docs" -> {
                System.out.print(ToolDocs.markdown(new AgentApi(project, "docs").tools()));
                System.out.flush();
            }
            case "http" -> http(project, port < 0 ? AgentConfig.DEFAULT_PORT : port, token);
            case "live" -> live(project, port, token);
            default -> stdio(project);
        }
    }

    /** The folder with config/ and TeamCode/, from the working directory up. */
    static Path findProject() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path d = dir; d != null; d = d.getParent()) {
            if (Files.exists(d.resolve("config/robot.jsonc")) || Files.exists(d.resolve("settings.gradle"))) return d;
        }
        return dir;
    }

    /** Robot code's prints, RobotLog and the simulator's messages go to the console buffer (and stderr). */
    private static void captureOutput(PrintStream human) {
        Console.captureSystemStreams(human);
        SimHooks.install(null, null, (level, tag, message) -> System.out.println("[" + level + "] " + tag + ": " + message));
    }

    private static void stdio(Path project) throws IOException {
        System.setProperty("java.awt.headless", "true");
        PrintStream protocol = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, StandardCharsets.UTF_8);
        captureOutput(err);
        AgentApi api = new AgentApi(project, "stdio");
        McpServer server = new McpServer(api);
        err.println("FTC Test Suite agent API " + AgentApi.VERSION + " (MCP over stdio) for " + project);
        serveLines(protocol, server::handle);
        api.close();
        System.exit(0);
    }

    /** Reads JSON-RPC lines from stdin, answers each on its own thread (a long simulation doesn't hold up the rest). */
    private static void serveLines(PrintStream out, java.util.function.Function<String, String> handler) throws IOException {
        ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent API request");
            t.setDaemon(true);
            return t;
        });
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            String msg = line;
            pool.submit(() -> {
                String reply = handler.apply(msg);
                if (reply != null) {
                    synchronized (out) {
                        out.print(reply);
                        out.print('\n');
                        out.flush();
                    }
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void http(Path project, int port, String token) throws IOException, InterruptedException {
        System.setProperty("java.awt.headless", "true");
        PrintStream err = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, StandardCharsets.UTF_8);
        captureOutput(err);
        if (token == null || token.isBlank()) token = LiveToken.get(AgentConfig.defaults());
        AgentApi api = new AgentApi(project, "http");
        HttpTransport t = new HttpTransport(api, token);
        int p = t.start(port);
        err.println("FTC Test Suite agent API " + AgentApi.VERSION + " on http://127.0.0.1:" + p + "/mcp (REST under /api) for " + project);
        err.println("Token: send \"Authorization: Bearer " + token + "\"");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            t.stop();
            api.close();
        }, "agent API shutdown"));
        Thread.currentThread().join();
    }

    /**
     * Passes MCP messages from stdin on to the simulator window's server, so a client that starts
     * programs (Claude Code, Cursor...) can reach the window. When the window isn't serving, the
     * listing calls are answered here and tool calls say how to open it.
     */
    private static void live(Path project, int port, String token) throws IOException {
        PrintStream protocol = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, StandardCharsets.UTF_8);
        System.setOut(err);
        AgentConfig cfg = AgentConfig.load(project);
        int p = port > 0 ? port : cfg.port();
        String tok = token != null && !token.isBlank() ? token : LiveToken.get(cfg);
        URI uri = URI.create("http://127.0.0.1:" + p + "/mcp");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        AgentApi local = new AgentApi(project, "live proxy (window not reachable)");
        McpServer fallback = new McpServer(local);
        err.println("FTC Test Suite agent API: passing MCP to the simulator window at " + uri);
        serveLines(protocol, msg -> {
            try {
                HttpResponse<String> r = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(30))
                                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                                .header("Authorization", "Bearer " + tok).POST(HttpRequest.BodyPublishers.ofString(msg)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 202) return null;
                if (r.statusCode() == 200) return r.body();
                return unreachable(fallback, msg, "The window refused the request (HTTP " + r.statusCode() + "): " + r.body()
                        + (r.statusCode() == 401 ? " The token differs: set the same \"token\" in config/agent-api.jsonc, or pass --token." : ""));
            } catch (ConnectException | java.net.http.HttpConnectTimeoutException e) {
                return unreachable(fallback, msg, "The simulator window isn't serving agents at " + uri + ". Open FTC Test Suite with "
                        + "\"enabled\": true in config/agent-api.jsonc (port " + p + "), or use the headless server (tools/ftc-sim-mcp without --live).");
            } catch (IOException e) {
                return unreachable(fallback, msg, "Lost the connection to the window: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });
        System.exit(0);
    }

    /** Without the window: listings from here, tool calls as errors that say why. */
    private static String unreachable(McpServer fallback, String msg, String why) {
        Object m;
        try {
            m = Json.parse(msg);
        } catch (RuntimeException e) {
            return fallback.handle(msg);
        }
        if (m instanceof Map<?, ?> req && "tools/call".equals(req.get("method"))) {
            if (!req.containsKey("id")) return null;
            return Json.write(Json.obj("jsonrpc", "2.0", "id", req.get("id"),
                    "result", Json.obj("content", List.of(Json.obj("type", "text", "text", why)), "isError", true)));
        }
        return fallback.handle(msg);
    }
}
