package org.ftctestbed.viewer.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent API: every tool, the sessions they work on, and the project they read and write.
 * Transports (MCP over stdio or HTTP, the REST routes) all call {@link #call}.
 */
public final class AgentApi {
    public static final String NAME = "ftc-sim";
    public static final String VERSION = "1.0.0";

    /** What MCP clients get at the start (shown to the model): enough to begin without reading the docs. */
    static final String INSTRUCTIONS = """
            FTC Test Suite: a physics simulator for FIRST Tech Challenge robots running real FTC Java team code \
            (OpModes, hardwareMap, gamepads, telemetry) on a simulated field with the season's game and scoring.

            Start: project_info (configs, team code, units), then sim_create (a headless simulation of the robot with its team \
            code compiled; add "sources" to include new or changed Java files without editing the project).
            Test an OpMode in one call: opmode_run {opmode, seconds, gamepad script, record channels, image}. It reports how it \
            ended, crashes with the line of robot code, telemetry, the pose moved, loop time and printed output.
            Step by step: opmode_control (init/start/stop) + gamepad + sim_step (until opmode_stopped / robot_stopped) + \
            sim_state (pose, motors, servos, mechanisms, sensors, odometry, score) + sim_record (a table over time).
            Change code: write_file, then compile_code (errors with file/line), then a new sim_create. Try a new mechanism: \
            sim_create with robot_config text (validate_robot_config and parts_catalog help write it).
            See it: sim_image (2D, any session) or ui screenshot (the window, live session). Debug: console_read, hardware \
            (read any device, call any method), java_eval (run Java inside the simulator against the session and team code).
            A whole match: match {auto, teleop} returns the score breakdown.

            Units: inches and degrees; field center origin, +x toward the blue alliance wall, +y away from the audience, \
            heading 0 along +x, counter-clockwise positive. Headless sessions are lockstep: simulated time only moves inside \
            tools that run it, as fast as the computer allows, and repeat exactly. Sessions keep the code they compiled. \
            The "live" session (when connected to the open window) runs in real time. Full guide: the docs tool or docs/agent-api.md.""";

    private final Path project;
    private final String mode;
    private final Sessions sessions;
    private volatile AgentConfig config;
    private final ProjectTools files;
    private final List<Tool> tools = new ArrayList<>();
    private final Map<String, Tool> byName = new LinkedHashMap<>();

    /** mode: how this server runs ("stdio", "http", "window"), for project_info. */
    public AgentApi(Path project, String mode) {
        this.project = project.toAbsolutePath().normalize();
        this.mode = mode;
        this.sessions = new Sessions(this.project);
        this.config = loadConfig();
        this.files = new ProjectTools(this);
        tools.addAll(files.tools());
        tools.addAll(new SimTools(this).tools());
        tools.addAll(new RobotTools(this).tools());
        tools.addAll(new HardwareTools(this).tools());
        tools.addAll(new FieldTools(this).tools());
        tools.addAll(new DevTools(this).tools());
        for (Tool t : tools) byName.put(t.name(), t);
    }

    private AgentConfig loadConfig() {
        try {
            return AgentConfig.load(project);
        } catch (IllegalArgumentException e) {
            Console.add("AGENT API: " + e.getMessage() + " (using the defaults)");
            return AgentConfig.defaults();
        }
    }

    public Path project() { return project; }
    public String mode() { return mode; }
    public Sessions sessions() { return sessions; }
    public AgentConfig config() { return config; }
    ProjectTools files() { return files; }
    public List<Tool> tools() { return tools; }

    public Tool tool(String name) {
        return byName.get(name);
    }

    /** The guide: docs/agent-api.md when the project has it, else the short instructions. */
    public String guide() {
        Path f = project.resolve("docs/agent-api.md");
        try {
            if (Files.exists(f)) return Files.readString(f);
        } catch (java.io.IOException ignored) {
            // fall back to the built-in summary
        }
        return INSTRUCTIONS;
    }

    /** What a call produced: its result, or an error message the agent can act on. */
    public record Outcome(Tool.Result result, String error) {}

    /**
     * Runs a tool. Mistakes the agent can fix (a wrong name, a missing argument, code that doesn't
     * compile) come back as the error text; so do crashes, with where they happened.
     */
    public Outcome call(String name, Object arguments) {
        Tool t = byName.get(name);
        if (t == null) return new Outcome(null, "No tool named \"" + name + "\". Tools: " + byName.keySet());
        config = loadConfig();
        try {
            return new Outcome(t.handler().call(new Json.Args(arguments)), null);
        } catch (Json.ToolError e) {
            return new Outcome(null, e.getMessage());
        } catch (org.ftctestbed.sim.config.ConfigException e) {
            return new Outcome(null, "Config error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(null, name + " was interrupted");
        } catch (Throwable e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            String trace = sw.toString();
            Console.add("AGENT API: " + name + " failed: " + trace);
            return new Outcome(null, name + " failed: " + e + "\n" + (trace.length() > 3000 ? trace.substring(0, 3000) + "..." : trace));
        }
    }

    /** Closes every headless session (the live one belongs to the window). */
    public void close() {
        sessions.closeAll();
    }
}
