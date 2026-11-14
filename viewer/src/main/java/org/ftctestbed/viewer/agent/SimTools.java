package org.ftctestbed.viewer.agent;

import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.Snapshot;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRunner;
import org.ftctestbed.viewer.FieldRenderer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Simulations: creating, stepping, reading, recording and drawing them. */
final class SimTools {
    static final Set<String> SECTIONS = Set.of("pose", "opmode", "motors", "servos", "mechanisms", "odometry", "sensors", "wheels",
            "lights", "cameras", "physics", "pieces", "match", "score", "gamepads", "raw");
    private static final List<String> DEFAULT_SECTIONS = List.of("pose", "opmode", "motors", "servos", "mechanisms", "odometry", "sensors", "match", "score");

    private final AgentApi api;

    SimTools(AgentApi api) {
        this.api = api;
    }

    List<Tool> tools() {
        Map<String, Object> robotItem = Tool.Schema.of()
                .str("robot", "Robot config file relative to the project (default config/robot.jsonc).")
                .str("robot_config", "Robot config as JSONC text instead of a file (try a new mechanism without saving a file).")
                .array("sources", "Extra Java files compiled with this robot's code.", ProjectTools.sourceItem())
                .str("start_position", "Start position name from the field config.").item();
        return List.of(
                new Tool("sim_create", "Create a simulation", "Simulation",
                        "Creates a headless simulation (no window) of one or two robots on a field and compiles their team code, plus any "
                                + "extra Java sources you give (test OpModes, new subsystems) without touching project files. Time is lockstep: "
                                + "it only moves inside tools such as sim_step, opmode_run and match, as fast as the computer allows, so runs "
                                + "repeat exactly. Returns the session id, OpModes per robot, start positions and setup messages (compile "
                                + "warnings, files left out, hardware names the code uses that the config lacks). Compiled code is cached, so "
                                + "making another session with the same code is quick.",
                        Tool.Schema.of()
                                .str("robot", "Robot config file relative to the project (default config/robot.jsonc).")
                                .str("robot_config", "The robot config as JSONC text instead (read as if it were the \"robot\" file, so its code folders resolve from there).")
                                .array("sources", "Extra Java files compiled with robot 1's team code.", ProjectTools.sourceItem())
                                .str("start_position", "Robot 1's start position (field config name); default: the field's first.")
                                .any("partner", "A second robot: true for another copy of robot 1 (its own copy of the code), or a robot config path.")
                                .str("partner_start_position", "The partner's start position.")
                                .array("robots", "Full control instead of robot/partner: one entry per robot.", robotItem)
                                .str("field", "Field config relative to the project (default config/field.jsonc).")
                                .str("field_config", "The field config as JSONC text instead.")
                                .str("name", "Session id to use instead of s1, s2...").build(), false, this::create),
                new Tool("sim_list", "List simulations", "Simulation",
                        "Lists the open sessions (headless ones and \"live\" when the simulator window is serving agents) with their robots, time and OpModes.",
                        Tool.Schema.of().build(), true, a -> {
                    List<Object> out = new ArrayList<>();
                    for (SimContext c : api.sessions().all()) out.add(c.describe());
                    return Tool.Result.of("sessions", out);
                }),
                new Tool("sim_close", "Close a simulation", "Simulation",
                        "Stops a headless session's OpModes and frees it. Close sessions you no longer need (at most 16 can be open).",
                        Tool.Schema.of().session().bool("all", "Close every headless session.").build(), false, a -> {
                    if (a.bool("all", false)) {
                        int n = api.sessions().all().size() - (api.sessions().live() != null ? 1 : 0);
                        api.sessions().closeAll();
                        return Tool.Result.of("closed", n);
                    }
                    SimContext c = api.sessions().get(a.str("session", null));
                    api.sessions().close(c.id());
                    return Tool.Result.of("closed", c.id());
                }),
                new Tool("sim_reset", "Reset the field", "Simulation",
                        "Match setup without recompiling: stops OpModes, puts every robot back at its start position (power-cycled: encoders "
                                + "and IMU zeroed), re-stages the game pieces and resets the score. Optionally changes start positions first.",
                        Tool.Schema.of().session()
                                .object("start_positions", "New start positions by robot number, e.g. {\"1\": \"Red Left\", \"2\": \"Red Right\"}.")
                                .bool("clear_trail", "Forget the path drawn by sim_image (default true).").build(), false, this::reset),
                new Tool("sim_step", "Advance time", "Simulation",
                        "Lets simulated time run (OpModes, physics, game) and returns the new state. Stops early when \"until\" holds. "
                                + "In a headless session this takes as long as the computer needs; in the live session it waits in real time.",
                        Tool.Schema.of().session()
                                .num("seconds", "How much simulated time to run (default 1; up to 600).")
                                .oneOf("until", "Stop as soon as this holds.", "opmode_stopped", "opmode_running", "match_ended", "robot_stopped")
                                .robot()
                                .strings("include", "State sections to return (as sim_state; default pose, opmode).").build(), false, this::step),
                new Tool("sim_state", "Read the simulation", "Simulation",
                        "Everything about the robots and field right now: pose (in, deg, in/s, deg/s), the OpMode (state, telemetry, errors, "
                                + "loop time), motors (power, encoder ticks, ticks/s, amps), servos, mechanisms, odometry devices, distance sensors, "
                                + "wheels, lights, cameras (what they see), battery and physics, game pieces, match phase and score. Pick sections "
                                + "with include, or \"all\". \"raw\" adds the simulator's whole snapshot in SI units.",
                        Tool.Schema.of().session().robot()
                                .strings("include", "Sections: pose opmode motors servos mechanisms odometry sensors wheels lights cameras physics "
                                        + "pieces match score gamepads raw, or all. Default: pose opmode motors servos mechanisms odometry sensors match score.")
                                .bool("all_robots", "Every robot's state (default: only \"robot\", or all when there are two and robot isn't given).")
                                .build(), true, this::state),
                new Tool("sim_record", "Record over time", "Simulation",
                        "Runs the simulation for some seconds while recording channels every interval (on the physics thread: nothing is missed), "
                                + "and returns the table plus first/last/min/max of each channel. Use it to tune and check: a drive's path, a lift's "
                                + "travel, a flywheel's spin-up, motor currents, a sensor's readings. Channels: x y heading vx vy omega speed battery "
                                + "current opmode telemetry telemetry.CAPTION motor.NAME.power|duty|position|velocity|current servo.NAME.position|angle "
                                + "mechanism.NAME odometry.NAME.x|y|heading distance.NAME piece.ID.x|y|z|state score.red score.blue phase clock "
                                + "(NAME may be *, r2. prefix for robot 2).",
                        Tool.Schema.of().session().robot()
                                .num("seconds", "How long to run (default 3).")
                                .num("interval", "Seconds between rows (default 0.05).")
                                .strings("channels", "What to record (default: x y heading speed motor.*.power mechanism.*).")
                                .oneOf("until", "Stop recording early when this holds.", "opmode_stopped", "opmode_running", "match_ended", "robot_stopped")
                                .integer("max_rows", "Most rows to return (default 400; older rows are thinned out evenly).")
                                .str("csv", "Also write the full table to this CSV file (relative to the project).")
                                .bool("rows", "Return the rows (default true; false for just the summary).").build(), false, this::record),
                new Tool("sim_image", "Picture of the field", "Simulation",
                        "Draws the field from above as the simulator's 2D view does: robots, game pieces, field elements, sensor rays, shots "
                                + "in the air, and the path each robot drove. Returns a PNG.",
                        Tool.Schema.of().session()
                                .oneOf("view", "top (default; red wall on the left), driver (from robot's alliance wall), follow (close up behind the robot).",
                                        "top", "driver", "follow")
                                .robot()
                                .integer("size", "Picture size in pixels (default 720, 200..1600).")
                                .bool("trail", "Draw the path the robots drove since this session's first picture (default true).")
                                .num("zoom", "Follow view: how close (default 2.5).").build(), true, this::image));
    }

    // ------------------------------------------------------------------ create

    static List<HeadlessSession.Source> sources(List<Object> list) {
        List<HeadlessSession.Source> out = new ArrayList<>();
        for (Object o : list) {
            Json.Args s = new Json.Args(o);
            out.add(new HeadlessSession.Source(s.str("path"), s.str("content")));
        }
        return out;
    }

    private Tool.Result create(Json.Args a) throws Exception {
        ProjectTools files = api.files();
        List<HeadlessSession.RobotSpec> specs = new ArrayList<>();
        List<String> starts = new ArrayList<>();
        if (a.has("robots")) {
            for (Object o : a.list("robots")) {
                Json.Args r = new Json.Args(o);
                specs.add(new HeadlessSession.RobotSpec(r.has("robot") || !r.has("robot_config") ? files.resolve(r.str("robot", "config/robot.jsonc")) : null,
                        r.str("robot_config", null), sources(r.list("sources"))));
                starts.add(r.str("start_position", null));
            }
            if (specs.isEmpty()) throw new Json.ToolError("\"robots\" is empty");
        } else {
            Path robot = a.has("robot") || !a.has("robot_config") ? files.resolve(a.str("robot", "config/robot.jsonc")) : null;
            specs.add(new HeadlessSession.RobotSpec(robot, a.str("robot_config", null), sources(a.list("sources"))));
            starts.add(a.str("start_position", null));
            Object partner = a.raw("partner");
            if (partner instanceof Boolean b && b || "true".equals(partner)) {
                specs.add(new HeadlessSession.RobotSpec(robot, a.str("robot_config", null), sources(a.list("sources"))));
                starts.add(a.str("partner_start_position", null));
            } else if (partner instanceof String s && !s.isBlank() && !s.equals("false")) {
                specs.add(new HeadlessSession.RobotSpec(files.resolve(s), null, List.of()));
                starts.add(a.str("partner_start_position", null));
            }
        }
        if (specs.size() > 4) throw new Json.ToolError("At most 4 robots");
        Path field = a.has("field_config") && !a.has("field") ? null : files.resolve(a.str("field", "config/field.jsonc"));
        String id = api.sessions().newId(a.str("name", null));
        long t0 = System.nanoTime();
        HeadlessSession s;
        try {
            s = HeadlessSession.create(id, api.project(), specs, field, a.str("field_config", null));
        } catch (org.ftctestbed.sim.config.ConfigException e) {
            throw new Json.ToolError("Config error: " + e.getMessage());
        }
        api.sessions().add(s);
        try {
            for (int i = 0; i < starts.size(); i++) {
                if (starts.get(i) == null) continue;
                FieldConfig.StartPosition sp = startPosition(s.sim(), starts.get(i));
                s.sim().setStartPosition(s.sim().robot(i + 1), sp);
            }
        } catch (RuntimeException e) {
            api.sessions().close(id);
            throw e;
        }
        api.sessions().trail(s);
        Map<String, Object> out = Json.obj("session", id, "setupSeconds", Views.r2((System.nanoTime() - t0) / 1e9));
        out.putAll(s.describe());
        out.put("startPositions", startPositions(s.sim()));
        List<Object> robots = new ArrayList<>();
        for (int r = 1; r <= s.robots(); r++) {
            List<Object> ops = new ArrayList<>();
            for (OpModeInfo i : s.opModes(r).opModes()) ops.add(Views.opModeInfo(i));
            robots.add(Json.obj("robot", r, "config", api.files().relOrAbs(s.robotFile(r)), "alliance", s.sim().robot(r).alliance(),
                    "startPosition", s.sim().robot(r).startPosition() == null ? null : s.sim().robot(r).startPosition().name(),
                    "pose", Views.pose(s.sim().robot(r).snapshot()), "opModes", ops,
                    "hardware", HardwareTools.names(s.hardwareMap(r))));
        }
        out.put("robots", robots);
        out.put("messages", s.messages());
        out.put("next", "opmode_run to run an OpMode start to finish, or opmode_control + sim_step + sim_state to drive it step by step.");
        return Tool.Result.of(out);
    }

    static FieldConfig.StartPosition startPosition(Simulation sim, String name) {
        for (FieldConfig.StartPosition sp : sim.fieldConfig.startPositions()) if (sp.name().equalsIgnoreCase(name)) return sp;
        throw new Json.ToolError("No start position \"" + name + "\". There are: "
                + sim.fieldConfig.startPositions().stream().map(FieldConfig.StartPosition::name).toList());
    }

    static List<Object> startPositions(Simulation sim) {
        List<Object> out = new ArrayList<>();
        for (FieldConfig.StartPosition sp : sim.fieldConfig.startPositions()) {
            out.add(Json.obj("name", sp.name(), "alliance", sp.alliance(), "x", Views.in(sp.pose().x()), "y", Views.in(sp.pose().y()),
                    "heading", Views.heading(sp.pose().heading())));
        }
        return out;
    }

    // ------------------------------------------------------------------ reset / step

    private Tool.Result reset(Json.Args a) throws Exception {
        SimContext c = api.sessions().get(a.str("session", null));
        return api.sessions().with(c, () -> {
            if (c.match().running()) c.match().abort();
            for (int r = 1; r <= c.robots(); r++) c.stopOpMode(r);
            Map<String, Object> sps = a.map("start_positions");
            for (Map.Entry<String, Object> e : sps.entrySet()) {
                int r;
                try {
                    r = (int) Double.parseDouble(e.getKey());
                } catch (NumberFormatException ex) {
                    throw new Json.ToolError("start_positions keys are robot numbers (\"1\", \"2\")");
                }
                if (r < 1 || r > c.robots()) throw new Json.ToolError("No robot " + r);
                FieldConfig.StartPosition sp = startPosition(c.sim(), String.valueOf(e.getValue()));
                c.sim().edit(s -> s.setStartPosition(s.robot(r), sp)); // resets the field
            }
            c.sim().resetField();
            c.sim().match().enter(org.ftctestbed.sim.game.MatchState.Phase.PRACTICE, c.sim().clock.seconds());
            for (int r = 1; r <= c.robots(); r++) {
                c.releaseGamepad(r, 1);
                c.releaseGamepad(r, 2);
            }
            if (a.bool("clear_trail", true)) api.sessions().trail(c).clear();
            return Tool.Result.of("session", c.id(), "timeSeconds", Views.r3(c.sim().clock.seconds()), "state", summary(c, null, List.of("pose", "opmode")));
        });
    }

    /** A condition sim_step and sim_record can stop at. */
    static BooleanSupplier until(SimContext c, String until, int robot) {
        if (until == null) return null;
        OpModeRunner r = c.runner(robot);
        return switch (until) {
            case "opmode_stopped" -> () -> r.state() == OpModeRunner.State.STOPPED || r.state() == OpModeRunner.State.IDLE;
            case "opmode_running" -> () -> r.state() == OpModeRunner.State.RUNNING;
            case "match_ended" -> () -> c.sim().match().phase() == org.ftctestbed.sim.game.MatchState.Phase.ENDED && !c.match().busy();
            case "robot_stopped" -> {
                // Still (under 0.5 in/s and 2 deg/s) after having moved, or still for a whole second.
                double[] stillSince = {Double.NaN};
                yield () -> {
                    var rob = c.sim().robot(robot);
                    boolean still = rob.speed() < 0.5 * Views.IN && Math.abs(rob.omega()) < Math.toRadians(2);
                    double now = c.sim().clock.seconds();
                    if (!still) {
                        stillSince[0] = Double.NaN;
                        return false;
                    }
                    if (Double.isNaN(stillSince[0])) stillSince[0] = now;
                    return now - stillSince[0] >= 0.25;
                };
            }
            default -> throw new Json.ToolError("until: opmode_stopped, opmode_running, match_ended or robot_stopped");
        };
    }

    private Tool.Result step(Json.Args a) throws Exception {
        SimContext c = api.sessions().get(a.str("session", null));
        int robot = a.integer("robot", 1);
        double seconds = Math.min(600, Math.max(0, a.num("seconds", 1)));
        return api.sessions().with(c, () -> {
            double t0 = c.sim().clock.seconds();
            long w0 = System.nanoTime();
            BooleanSupplier cond = until(c, a.str("until", null), robot);
            boolean met = false;
            if (cond == null) c.advance(seconds);
            else met = c.advanceUntil(cond, seconds);
            Map<String, Object> out = Json.obj("session", c.id(), "timeSeconds", Views.r3(c.sim().clock.seconds()),
                    "ranSeconds", Views.r3(c.sim().clock.seconds() - t0), "realSeconds", Views.r2((System.nanoTime() - w0) / 1e9));
            if (cond != null) out.put("untilMet", met);
            List<String> include = a.has("include") ? strings(a.list("include")) : List.of("pose", "opmode");
            out.put("state", summary(c, a.has("robot") ? robot : null, include));
            return Tool.Result.of(out);
        });
    }

    static List<String> strings(List<Object> l) {
        List<String> out = new ArrayList<>();
        for (Object o : l) out.add(String.valueOf(o));
        return out;
    }

    // ------------------------------------------------------------------ state

    private Tool.Result state(Json.Args a) throws Exception {
        SimContext c = api.sessions().get(a.str("session", null));
        List<String> include = a.has("include") ? strings(a.list("include")) : DEFAULT_SECTIONS;
        Integer robot = a.bool("all_robots", false) ? null : a.has("robot") ? (Integer) a.integer("robot", 1) : c.robots() == 1 ? (Integer) 1 : null;
        Map<String, Object> out = Json.obj("session", c.id(), "timeSeconds", Views.r3(c.sim().clock.seconds()));
        out.putAll(summary(c, robot, include));
        return Tool.Result.of(out);
    }

    /**
     * The chosen sections for one robot (robot != null) or every robot, plus the field's (pieces,
     * match, score) once.
     */
    static Map<String, Object> summary(SimContext c, Integer robot, List<String> include) {
        List<String> inc = new ArrayList<>(include);
        if (inc.contains("all")) {
            inc.remove("all");
            for (String s : SECTIONS) if (!s.equals("raw") && !inc.contains(s)) inc.add(s);
        }
        for (String s : inc) if (!SECTIONS.contains(s)) throw new Json.ToolError("Unknown section \"" + s + "\". Sections: " + SECTIONS + " or all");
        if (robot != null && (robot < 1 || robot > c.robots())) throw new Json.ToolError("No robot " + robot + ": this session has " + c.robots());
        List<Snapshot> snaps = c.sim().snapshots();
        Map<String, Object> out = Json.obj();
        List<Object> robots = new ArrayList<>();
        for (int r = 1; r <= c.robots(); r++) {
            if (robot != null && r != robot) continue;
            Snapshot s = snaps.get(r - 1);
            Map<String, Object> m = Json.obj("robot", r, "alliance", s.alliance());
            for (String sec : inc) {
                switch (sec) {
                    case "pose" -> m.put("pose", Views.pose(s));
                    case "opmode" -> m.put("opmode", Views.opMode(c.runner(r), c.physics()));
                    case "motors" -> m.put("motors", Views.motors(s));
                    case "servos" -> m.put("servos", Views.servos(s));
                    case "mechanisms" -> m.put("mechanisms", Views.mechanisms(s));
                    case "odometry" -> m.put("odometry", Views.odometry(s));
                    case "sensors" -> m.put("distanceSensors", Views.distanceRays(s));
                    case "wheels" -> m.put("wheels", Views.wheels(s));
                    case "lights" -> m.put("lights", Views.lights(s));
                    case "cameras" -> m.put("cameras", Views.cameras(s));
                    case "physics" -> m.put("physics", Views.physics(s));
                    case "gamepads" -> m.put("gamepads", Json.obj("gamepad1", Gamepads.describe(c.runner(r).gamepad1),
                            "gamepad2", Gamepads.describe(c.runner(r).gamepad2)));
                    case "raw" -> m.put("raw", s);
                    default -> {
                    }
                }
            }
            robots.add(m);
        }
        out.put("robots", robots);
        Snapshot first = snaps.get(0);
        if (inc.contains("match")) out.put("match", Views.match(c.sim(), first));
        if (inc.contains("score")) out.put("score", Views.season(first.season()));
        if (inc.contains("pieces")) out.put("pieces", Views.pieces(first));
        return out;
    }

    // ------------------------------------------------------------------ record

    private Tool.Result record(Json.Args a) throws Exception {
        SimContext c = api.sessions().get(a.str("session", null));
        int robot = a.integer("robot", 1);
        double seconds = Math.min(600, Math.max(0, a.num("seconds", 3)));
        Path csv = a.has("csv") ? api.files().resolve(a.str("csv")) : null;
        return api.sessions().with(c, () -> {
            Recorder rec = new Recorder(c, strings(a.list("channels")), robot, a.num("interval", 0.05), a.integer("max_rows", 400));
            BooleanSupplier cond = until(c, a.str("until", null), robot);
            double t0 = c.sim().clock.seconds();
            rec.start();
            boolean met = false;
            try {
                if (cond == null) c.advance(seconds);
                else met = c.advanceUntil(cond, seconds);
            } finally {
                rec.stop();
            }
            Map<String, Object> out = Json.obj("session", c.id(), "fromSeconds", Views.r3(t0), "toSeconds", Views.r3(c.sim().clock.seconds()));
            if (cond != null) out.put("untilMet", met);
            Map<String, Object> table = rec.result(csv);
            if (!a.bool("rows", true)) table.remove("rows");
            out.putAll(table);
            if (csv != null) out.put("csv", api.files().rel(csv));
            out.put("opmode", Views.opMode(c.runner(robot), c.physics()));
            return Tool.Result.of(out);
        });
    }

    // ------------------------------------------------------------------ image

    private Tool.Result image(Json.Args a) throws Exception {
        SimContext c = api.sessions().get(a.str("session", null));
        int robot = a.integer("robot", 1);
        if (robot < 1 || robot > c.robots()) throw new Json.ToolError("No robot " + robot);
        int size = Math.max(200, Math.min(1600, a.integer("size", 720)));
        String view = a.str("view", "top");
        boolean trail = a.bool("trail", true);
        byte[] png = render(c, api.sessions().trail(c), view, robot, size, trail, a.num("zoom", 2.5));
        return Tool.Result.of("session", c.id(), "timeSeconds", Views.r3(c.sim().clock.seconds()), "view", view, "size", size,
                "legend", "Robots as seen from above with their front marked; distance sensor rays dashed; the robot's path as a line; "
                        + "the red alliance wall is on the left in the top view.").withPng(png);
    }

    static byte[] render(SimContext c, Sessions.Trail trail, String view, int robot, int size, boolean drawTrail, double zoom) throws IOException {
        List<Snapshot> snaps = c.sim().snapshots();
        Snapshot main = snaps.get(robot - 1);
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(FieldRenderer.BACKGROUND);
        g.fillRect(0, 0, size, size);
        int top = 28;
        List<FieldRenderer> renderers = new ArrayList<>();
        for (int r = 1; r <= c.robots(); r++) {
            FieldRenderer fr = new FieldRenderer(c.sim().fieldConfig, c.sim().robot(r).config);
            switch (view) {
                case "driver" -> {
                    String al = main.alliance();
                    if (al != null) fr.setViewRotation(FieldRenderer.driverView("blue".equals(al) ? Math.PI : 0));
                }
                case "follow" -> {
                    double h = main.heading();
                    fr.setViewRotation(FieldRenderer.driverView(h));
                    fr.setCamera(main.x() + Math.cos(h) * 14 * Views.IN, main.y() + Math.sin(h) * 14 * Views.IN, Math.max(1, Math.min(8, zoom)));
                }
                case "top" -> {
                }
                default -> throw new Json.ToolError("view: top, driver or follow");
            }
            fr.layout(0, top, size, size - top, 0);
            renderers.add(fr);
        }
        // The chosen robot's renderer draws the field and that robot; the others add theirs.
        renderers.get(robot - 1).paint(g, main, drawTrail ? trail.of(robot) : null);
        for (int r = 1; r <= c.robots(); r++) {
            if (r == robot) continue;
            if (drawTrail) {
                List<double[]> t = trail.of(r);
                Graphics2D tg = renderers.get(r - 1).fieldGraphics(g);
                tg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                tg.setColor(new Color(255, 200, 80, 150));
                FieldRenderer fr = renderers.get(r - 1);
                for (int i = 1; i < t.size(); i++) {
                    tg.draw(new java.awt.geom.Line2D.Double(fr.sx(t.get(i - 1)[0]), fr.sy(t.get(i - 1)[1]), fr.sx(t.get(i)[0]), fr.sy(t.get(i)[1])));
                }
                tg.dispose();
            }
            renderers.get(r - 1).paintRobotOnly(g, snaps.get(r - 1));
        }
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(20, 22, 26));
        g.fillRect(0, 0, size, top);
        g.setColor(new Color(235, 238, 242));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        StringBuilder caption = new StringBuilder(String.format(Locale.US, "%s  t=%.2f s  %s", c.id(), main.time(), main.matchPhase()));
        if (main.season() != null) caption.append("  RED ").append(main.season().score("red")).append("  BLUE ").append(main.season().score("blue"));
        for (int r = 1; r <= c.robots(); r++) {
            OpModeRunner run = c.runner(r);
            if (run.current() != null) caption.append("  R").append(r).append(": ").append(run.current().name()).append(" ").append(run.state());
        }
        g.drawString(caption.toString(), 8, 19);
        g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bytes);
        return bytes.toByteArray();
    }
}
