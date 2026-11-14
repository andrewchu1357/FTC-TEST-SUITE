package org.ftctestbed.viewer;

import org.ftctestbed.viewer.agent.AgentApi;
import org.ftctestbed.viewer.agent.AgentConfig;
import org.ftctestbed.viewer.agent.HttpTransport;
import org.ftctestbed.viewer.agent.LiveToken;
import org.ftctestbed.viewer.agent.SimContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The agent API inside the app (config/agent-api.jsonc): one HTTP server on 127.0.0.1 for as long
 * as the app runs, serving headless sessions and the window's own simulation ("live"), which it
 * follows from window to window when robots or code are reloaded.
 */
final class AgentService {
    private static AgentApi api;
    private static HttpTransport http;
    private static String status;
    private static volatile SimContext live;

    private AgentService() {}

    /** Starts the server the first time (if the config turns it on); the lines to show in the console. */
    static synchronized List<String> start(Path project) {
        List<String> out = new ArrayList<>();
        if (status != null) {
            if (!status.isEmpty()) out.add(status);
            return out;
        }
        AgentConfig cfg;
        try {
            cfg = AgentConfig.load(project);
        } catch (IllegalArgumentException e) {
            status = "The agent API didn't start: " + e.getMessage();
            out.add(status);
            return out;
        }
        if (!cfg.enabled()) {
            status = "";
            return out;
        }
        try {
            api = new AgentApi(project, "window");
            api.sessions().setLive(() -> live);
            http = new HttpTransport(api, LiveToken.get(cfg));
            int port = http.start(cfg.port());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                http.stop();
                api.close();
            }, "agent API shutdown"));
            status = "AI agents can drive the simulator (MCP): http://127.0.0.1:" + port + "/mcp. File > Copy Agent Connection copies "
                    + "the command that connects Claude Code. Settings: config/agent-api.jsonc";
        } catch (Exception e) {
            status = "The agent API couldn't start on port " + cfg.port() + ": " + e.getMessage() + " (change it in config/agent-api.jsonc)";
        }
        out.add(status);
        return out;
    }

    /** The window agents see as "live" now (null while none is open). */
    static void setLive(SimContext ctx) {
        live = ctx;
    }

    /** A window closing gives up "live" if it's still the one agents see. */
    static void clearLive(SimContext ctx) {
        if (live == ctx) live = null;
        if (ctx != null) ctx.close();
    }

    static boolean running() {
        return http != null;
    }

    /** The command that adds the window's server to Claude Code (it carries this computer's token). */
    static String connectCommand(Path project) {
        AgentConfig cfg = AgentConfig.load(project);
        int port = http != null ? http.port() : cfg.port();
        return "claude mcp add --transport http ftc-sim-live http://127.0.0.1:" + port + "/mcp --header \"Authorization: Bearer "
                + LiveToken.get(cfg) + "\"";
    }
}
