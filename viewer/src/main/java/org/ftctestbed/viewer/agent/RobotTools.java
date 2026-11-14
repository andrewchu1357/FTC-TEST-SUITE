package org.ftctestbed.viewer.agent;

import com.qualcomm.robotcore.hardware.Gamepad;

import org.ftctestbed.sim.SimRobot;
import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.Snapshot;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRunner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** OpModes, gamepads and putting robots where they're wanted. */
final class RobotTools {
    private final AgentApi api;

    RobotTools(AgentApi api) {
        this.api = api;
    }

    List<Tool> tools() {
        Map<String, Object> scriptItem = Tool.Schema.of()
                .num("t", "Seconds after START (negative: during INIT, after init_seconds + t).")
                .object("gamepad1", "Fields to set on gamepad 1, e.g. {\"left_stick_y\": -1, \"a\": true}. Others keep their value.")
                .object("gamepad2", "Fields to set on gamepad 2.")
                .num("duration", "Release the fields given here this many seconds later (a button tap: 0.1).")
                .bool("neutral", "Release everything on the given gamepads first (both if none given).")
                .integer("robot", "Which robot's gamepads (default: the tool's robot).").item();
        return List.of(
                new Tool("opmode_list", "List OpModes", "OpModes",
                        "The robot's OpModes as the Driver Station lists them: name, group, AUTO or TELEOP, class, and the TeleOp an AUTO preselects.",
                        Tool.Schema.of().session().robot().build(), true, a -> {
                    SimContext c = api.sessions().get(a.str("session", null));
                    int r = robot(c, a);
                    List<Object> out = new ArrayList<>();
                    for (OpModeInfo i : c.opModes(r).opModes()) out.add(Views.opModeInfo(i));
                    return Tool.Result.of("session", c.id(), "robot", r, "opModes", out, "warnings", c.opModes(r).warnings());
                }),
                new Tool("opmode_run", "Run an OpMode", "OpModes",
                        "The one-call test: INITs an OpMode, lets init run, presses START, runs it (optionally driving the gamepads from a "
                                + "script) until it stops by itself, the 30 s AUTO timer stops it, or \"seconds\" pass, then STOPs it. Returns how it "
                                + "ended, any crash with the line of robot code, telemetry (final and as it changed), the start and end pose, loop "
                                + "time, what it printed, an optional recording of channels (see sim_record) and an optional picture.",
                        Tool.Schema.of().session().robot()
                                .str("opmode", "OpMode name as on the Driver Station (or its class name).")
                                .num("init_seconds", "Seconds between INIT and START (default 0.5).")
                                .num("seconds", "Most seconds to run after START (default 30 for AUTO, 15 otherwise; up to 600).")
                                .bool("reset", "Reset the field first (robots to their start positions, pieces re-staged; default false).")
                                .str("start_position", "Put this robot at this start position first (resets the field).")
                                .array("gamepad", "Gamepad script: what the drivers do, by time after START.", scriptItem)
                                .strings("record", "Channels to record while it runs (as sim_record), e.g. [\"x\",\"y\",\"heading\",\"motor.*.power\"].")
                                .num("record_interval", "Seconds between recorded rows (default 0.05).")
                                .integer("max_rows", "Most recorded rows to return (default 400).")
                                .bool("stop", "Press STOP when it's still running at the end (default true; false leaves it running).")
                                .bool("auto_timer", "Stop an AUTO 30 s after START as the field does (default true).")
                                .bool("image", "Add a picture of the field at the end (default false).")
                                .required("opmode").build(), false, this::run),
                new Tool("opmode_control", "Driver Station buttons", "OpModes",
                        "Presses the Driver Station buttons: init (with an OpMode name), start, stop, or init_start. After init it lets init "
                                + "code run for init_seconds. Use sim_step to let the OpMode run, and sim_state / opmode_status to watch it.",
                        Tool.Schema.of().session().robot()
                                .oneOf("action", "What to press.", "init", "start", "stop", "init_start")
                                .str("opmode", "OpMode to INIT (name or class name).")
                                .num("init_seconds", "Seconds to let init run after INIT (default 0.5; 0 for none).")
                                .bool("auto_timer", "Stop an AUTO 30 s after START (default true).")
                                .required("action").build(), false, this::control),
                new Tool("opmode_status", "OpMode status", "OpModes",
                        "The robot's OpMode right now: state (IDLE, INIT, RUNNING, STOPPING, STOPPED), name, time since START, telemetry, "
                                + "the crash message (with the line of robot code) if it threw, warnings, loop time, rumble, and the gamepads as the robot sees them.",
                        Tool.Schema.of().session().robot().build(), true, a -> {
                    SimContext c = api.sessions().get(a.str("session", null));
                    int r = robot(c, a);
                    Map<String, Object> out = Json.obj("session", c.id(), "robot", r, "timeSeconds", Views.r3(c.sim().clock.seconds()));
                    out.putAll(Views.opMode(c.runner(r), c.physics()));
                    out.put("gamepad1", Gamepads.describe(c.runner(r).gamepad1));
                    out.put("gamepad2", Gamepads.describe(c.runner(r).gamepad2));
                    return Tool.Result.of(out);
                }),
                new Tool("gamepad", "Drive the gamepads", "OpModes",
                        "Sets what the robot's gamepad 1 or 2 reports, with the SDK's field names (left_stick_x/y, right_stick_x/y, "
                                + "left/right_trigger: numbers; a b x y, cross circle square triangle, dpad_up/down/left/right, left/right_bumper, "
                                + "start back guide, options share ps, left/right_stick_button, touchpad: true/false). Pushing a stick up is "
                                + "left_stick_y = -1, as on a real gamepad. set keeps the state until changed; tap presses buttons for a moment "
                                + "(advancing time); script plays timed states while time advances; release lets go. INIT resets the gamepads, so "
                                + "set them after INIT. In the live session this overrides the real controllers until released.",
                        Tool.Schema.of().session().robot()
                                .oneOf("action", "set (default), tap, script, release.", "set", "tap", "script", "release")
                                .integer("pad", "1 (default) or 2.")
                                .object("state", "Fields to set, e.g. {\"left_stick_y\": -0.5, \"right_bumper\": true}.")
                                .bool("neutral", "set: release everything else first (default false).")
                                .strings("buttons", "tap: the buttons to press, e.g. [\"a\"].")
                                .num("duration", "tap: seconds held (default 0.1); script: seconds to run in all (default: to the last step + 0.5).")
                                .array("script", "script: timed states, t in seconds from now.", scriptItem).build(), false, this::gamepad),
                new Tool("robot_move", "Move a robot", "Robot",
                        "Puts a robot where a test needs it, in inches and degrees (field center origin, +x toward the blue wall, heading 0 "
                                + "along +x, counter-clockwise). place: picked up and set down (encoders keep their counts, the OpMode keeps "
                                + "running); reset: power-cycled at a pose (encoders and IMU zeroed, default its start position); start_position: "
                                + "changes its start position (resets the whole field); push: a hand pulls the robot toward a point (as when "
                                + "someone pushes it during a tuning OpMode) until release.",
                        Tool.Schema.of().session().robot()
                                .oneOf("action", "What to do.", "place", "reset", "start_position", "push", "release")
                                .num("x", "Inches.").num("y", "Inches.").num("heading", "Degrees.")
                                .str("name", "start_position: its name.")
                                .num("grab_x", "push: point on the robot to push, inches forward of its center (default 0).")
                                .num("grab_y", "push: inches left of its center (default 0).")
                                .required("action").build(), false, this::move));
    }

    static int robot(SimContext c, Json.Args a) {
        int r = a.integer("robot", 1);
        if (r < 1 || r > c.robots()) throw new Json.ToolError("No robot " + r + ": this session has " + c.robots());
        return r;
    }

    static OpModeInfo find(SimContext c, int robot, String name) {
        List<OpModeInfo> ops = c.opModes(robot).opModes();
        for (OpModeInfo i : ops) if (i.name().equals(name)) return i;
        for (OpModeInfo i : ops) if (i.name().equalsIgnoreCase(name)) return i;
        for (OpModeInfo i : ops) if (i.type().getName().equals(name) || i.type().getSimpleName().equals(name)) return i;
        throw new Json.ToolError("No OpMode \"" + name + "\" on robot " + robot + ". OpModes: " + ops.stream().map(OpModeInfo::name).toList());
    }

    // ------------------------------------------------------------------ control

    private Tool.Result control(Json.Args a) throws Exception {
        SimContext c = api.sessions().get(a.str("session", null));
        int r = robot(c, a);
        String action = a.str("action");
        return api.sessions().with(c, () -> {
            OpModeRunner run = c.runner(r);
            if (a.has("auto_timer")) run.setAutoTimer(a.bool("auto_timer", true));
            switch (action) {
                case "init", "init_start" -> {
                    OpModeInfo info = find(c, r, a.str("opmode"));
                    init(c, r, info, a.num("init_seconds", 0.5));
                    if (action.equals("init_start")) run.start();
                }
                case "start" -> {
                    if (run.state() != OpModeRunner.State.INIT) throw new Json.ToolError("START needs an OpMode in INIT (it's " + run.state() + ")");
                    run.start();
                }
                case "stop" -> c.stopOpMode(r);
                default -> throw new Json.ToolError("action: init, start, stop or init_start");
            }
            Map<String, Object> out = Json.obj("session", c.id(), "robot", r, "timeSeconds", Views.r3(c.sim().clock.seconds()));
            out.putAll(Views.opMode(run, c.physics()));
            return Tool.Result.of(out);
        });
    }

    /** INIT (stopping whatever ran first) and let init code run. */
    static void init(SimContext c, int r, OpModeInfo info, double initSeconds) {
        OpModeRunner run = c.runner(r);
        if (run.state() == OpModeRunner.State.INIT || run.state() == OpModeRunner.State.RUNNING || run.state() == OpModeRunner.State.STOPPING) {
            c.stopOpMode(r);
        }
        c.releaseGamepad(r, 1);
        c.releaseGamepad(r, 2);
        run.init(info);
        if (initSeconds > 0) c.advanceUntil(() -> run.state() != OpModeRunner.State.INIT, initSeconds);
    }

    // ------------------------------------------------------------------ run

    private Tool.Result run(Json.Args a) throws Exception {
        SimContext c = api.sessions().get(a.str("session", null));
        int r = robot(c, a);
        OpModeInfo info = find(c, r, a.str("opmode"));
        double initSeconds = Math.max(0, a.num("init_seconds", 0.5));
        double seconds = Math.min(600, Math.max(0, a.num("seconds", info.autonomous() ? 30 : 15)));
        return api.sessions().with(c, () -> {
            OpModeRunner run = c.runner(r);
            Simulation sim = c.sim();
            if (a.has("start_position")) {
                FieldConfig.StartPosition sp = SimTools.startPosition(sim, a.str("start_position"));
                for (int k = 1; k <= c.robots(); k++) c.stopOpMode(k);
                sim.edit(s -> s.setStartPosition(s.robot(r), sp));
            } else if (a.bool("reset", false)) {
                for (int k = 1; k <= c.robots(); k++) c.stopOpMode(k);
                sim.resetField();
            }
            run.setAutoTimer(a.bool("auto_timer", true));
            long consoleFrom = Console.next() - 1;
            Snapshot before = sim.robot(r).snapshot();
            GamepadScript script = new GamepadScript(c, r, a.list("gamepad"));
            init(c, r, info, 0);
            double t0 = sim.clock.seconds();
            // Script times count from START, due initSeconds after INIT; negative ones happen during INIT.
            script.startAt(t0 + initSeconds);
            if (script.firstTime() < 0) script.install();
            if (initSeconds > 0) c.advanceUntil(() -> run.state() != OpModeRunner.State.INIT, initSeconds);
            String initError = run.error();
            double initEnd = sim.clock.seconds();
            Recorder rec = a.has("record") ? new Recorder(c, SimTools.strings(a.list("record")), r, a.num("record_interval", 0.05), a.integer("max_rows", 400)) : null;
            TelemetryLog tlog = new TelemetryLog(run);
            String endedBy;
            double started = Double.NaN, ended = Double.NaN;
            if (run.state() == OpModeRunner.State.INIT) {
                run.start();
                started = sim.clock.seconds();
                script.startAt(started);
                script.install();
                if (rec != null) rec.start();
                sim.addStepListener(tlog);
                try {
                    boolean stopped = c.advanceUntil(() -> run.state() == OpModeRunner.State.STOPPED || run.state() == OpModeRunner.State.IDLE
                            || run.state() == OpModeRunner.State.STOPPING, seconds);
                    if (stopped) {
                        c.advanceUntil(() -> run.state() == OpModeRunner.State.STOPPED, 3.5);
                        double ran = sim.clock.seconds() - started;
                        endedBy = run.error() != null ? "crashed"
                                : info.autonomous() && run.autoTimer() && ran >= OpModeRunner.AUTONOMOUS_SECONDS - 0.05 ? "AUTO timer (30 s)" : "finished by itself";
                    } else if (a.bool("stop", true)) {
                        endedBy = "time limit (" + seconds + " s): STOP pressed";
                    } else {
                        endedBy = "time limit (" + seconds + " s): still running";
                    }
                } finally {
                    ended = sim.clock.seconds();
                    script.uninstall();
                    if (rec != null) rec.stop();
                    sim.removeStepListener(tlog);
                }
                tlog.flush();
                if (a.bool("stop", true)) c.stopOpMode(r);
            } else {
                script.uninstall();
                endedBy = initError != null || run.error() != null ? "crashed during INIT" : "stopped during INIT (state " + run.state() + ")";
            }
            Snapshot after = sim.robot(r).snapshot();
            Map<String, Object> out = Json.obj("session", c.id(), "robot", r, "opMode", info.name(), "endedBy", endedBy,
                    "error", run.error(), "warning", run.warning(),
                    "initSeconds", Views.r3(initEnd - t0), "ranSeconds", Double.isNaN(started) ? 0 : Views.r3(ended - started),
                    "state", run.state(), "telemetry", run.telemetry().publishedLines(), "telemetryHistory", tlog.entries(),
                    "start", Views.pose(before), "end", Views.pose(after),
                    "moved", Json.obj("inches", Views.in(Math.hypot(after.x() - before.x(), after.y() - before.y())),
                            "turnedDeg", Views.r2(Math.toDegrees(Math.IEEEremainder(after.heading() - before.heading(), 2 * Math.PI)))));
            double loop = c.physics().robotLoopMillis(run.robot());
            if (!Double.isNaN(loop)) out.put("loopMs", Views.r2(loop));
            List<String> printed = new ArrayList<>();
            for (Console.Line l : Console.since(consoleFrom, 200, null)) printed.add(l.text());
            out.put("console", printed);
            if (sim.season() != null) out.put("score", Views.season(sim.snapshot().season()));
            if (rec != null) out.put("recording", rec.result(null));
            Tool.Result res = Tool.Result.of(out);
            if (a.bool("image", false)) res = res.withPng(SimTools.render(c, api.sessions().trail(c), "top", r, 720, true, 2.5));
            return res;
        });
    }

    /**
     * Telemetry as it changed over the run: a change is kept when at least spacing seconds passed
     * since the last kept one (0.25 s at first). Past 60 entries every other one goes and the
     * spacing doubles, so the history always spans the whole run.
     */
    static final class TelemetryLog implements Consumer<Simulation> {
        private final OpModeRunner run;
        private final List<Map<String, Object>> entries = new ArrayList<>();
        private List<String> last;
        private double start = Double.NaN, lastKept = Double.NEGATIVE_INFINITY, spacing = 0.25;

        TelemetryLog(OpModeRunner run) {
            this.run = run;
        }

        @Override
        public void accept(Simulation s) {
            if (Double.isNaN(start)) start = s.clock.seconds();
            if (s.clock.tick() % 25 != 0) return; // every 50 ms is plenty
            note(s.clock.seconds(), false);
        }

        /** The final telemetry, if it changed since the last kept entry. */
        void flush() {
            if (!Double.isNaN(start)) note(run.robot().simulation().clock.seconds(), true);
        }

        private void note(double time, boolean force) {
            List<String> now = run.telemetry().publishedLines();
            if (now.equals(last) || (!force && time - lastKept < spacing)) return;
            last = now;
            lastKept = time;
            synchronized (entries) {
                entries.add(Json.obj("t", Views.r2(time - start), "lines", now));
                if (entries.size() > 60) {
                    List<Map<String, Object>> kept = new ArrayList<>();
                    for (int i = 0; i < entries.size(); i++) if (i % 2 == 0 || i == entries.size() - 1) kept.add(entries.get(i));
                    entries.clear();
                    entries.addAll(kept);
                    spacing *= 2;
                }
            }
        }

        List<Object> entries() {
            synchronized (entries) {
                return new ArrayList<>(entries);
            }
        }
    }

    // ------------------------------------------------------------------ gamepads

    /**
     * Timed gamepad states, applied on the physics thread at their simulated time (t after start).
     * Each step changes only the fields it names; duration adds a later step releasing them.
     */
    static final class GamepadScript implements Consumer<Simulation> {
        private record Step(double t, int robot, int pad, Map<String, Object> fields, boolean neutral) {}

        private final SimContext ctx;
        private final List<Step> steps = new ArrayList<>();
        private final Map<Integer, Gamepad> current = new HashMap<>();
        private double start;
        private int next;
        private boolean installed;

        GamepadScript(SimContext ctx, int defaultRobot, List<Object> entries) {
            this.ctx = ctx;
            for (Object o : entries) {
                Json.Args e = new Json.Args(o);
                double t = e.num("t", 0);
                int robot = e.integer("robot", defaultRobot);
                if (robot < 1 || robot > ctx.robots()) throw new Json.ToolError("Gamepad script: no robot " + robot);
                boolean neutral = e.bool("neutral", false);
                boolean any = false;
                for (int pad = 1; pad <= 2; pad++) {
                    if (!e.has("gamepad" + pad)) continue;
                    any = true;
                    Map<String, Object> f = e.map("gamepad" + pad);
                    Gamepads.edited(null, f, true); // check the names now, not mid-run
                    steps.add(new Step(t, robot, pad, f, neutral));
                    if (e.has("duration")) {
                        Map<String, Object> off = new HashMap<>();
                        for (Map.Entry<String, Object> x : f.entrySet()) off.put(x.getKey(), x.getValue() instanceof Boolean ? (Object) false : (Object) 0.0);
                        steps.add(new Step(t + e.num("duration", 0.1), robot, pad, off, false));
                    }
                }
                if (!any && neutral) {
                    steps.add(new Step(t, robot, 1, Map.of(), true));
                    steps.add(new Step(t, robot, 2, Map.of(), true));
                }
            }
            steps.sort(Comparator.comparingDouble(Step::t));
        }

        double firstTime() {
            return steps.isEmpty() ? 0 : steps.get(0).t();
        }

        double lastTime() {
            return steps.isEmpty() ? 0 : steps.get(steps.size() - 1).t();
        }

        boolean isEmpty() {
            return steps.isEmpty();
        }

        void startAt(double simSeconds) {
            start = simSeconds;
        }

        void install() {
            if (installed || steps.isEmpty()) return;
            installed = true;
            ctx.sim().addStepListener(this);
        }

        void uninstall() {
            ctx.sim().removeStepListener(this);
            installed = false;
        }

        @Override
        public void accept(Simulation s) {
            double t = s.clock.seconds() - start;
            while (next < steps.size() && steps.get(next).t() <= t + 1e-9) {
                Step st = steps.get(next++);
                int key = st.robot() * 10 + st.pad();
                Gamepad g = Gamepads.edited(current.get(key), st.fields(), st.neutral());
                current.put(key, g);
                Gamepad copy = new Gamepad();
                copy.copy(g);
                ctx.setGamepad(st.robot(), st.pad(), copy);
            }
        }
    }

    private Tool.Result gamepad(Json.Args a) throws Exception {
        SimContext c = api.sessions().get(a.str("session", null));
        int r = robot(c, a);
        int pad = a.integer("pad", 1);
        if (pad != 1 && pad != 2) throw new Json.ToolError("pad: 1 or 2");
        String action = a.str("action", "set");
        return api.sessions().with(c, () -> {
            OpModeRunner run = c.runner(r);
            Gamepad target = pad == 1 ? run.gamepad1 : run.gamepad2;
            switch (action) {
                case "set" -> {
                    Gamepad g = Gamepads.edited(target, a.map("state"), a.bool("neutral", false));
                    c.setGamepad(r, pad, g);
                }
                case "release" -> c.releaseGamepad(r, pad);
                case "tap" -> {
                    List<String> buttons = SimTools.strings(a.list("buttons"));
                    if (buttons.isEmpty()) throw new Json.ToolError("tap: give \"buttons\", e.g. [\"a\"]");
                    Map<String, Object> on = new HashMap<>(), off = new HashMap<>();
                    for (String b : buttons) {
                        on.put(b, true);
                        off.put(b, false);
                    }
                    c.setGamepad(r, pad, Gamepads.edited(target, on, false));
                    c.advance(Math.max(0.02, a.num("duration", 0.1)));
                    c.setGamepad(r, pad, Gamepads.edited(target, off, false));
                    c.advance(0.02);
                }
                case "script" -> {
                    GamepadScript script = new GamepadScript(c, r, a.list("script"));
                    if (script.isEmpty()) throw new Json.ToolError("script: give timed steps");
                    script.startAt(c.sim().clock.seconds());
                    script.install();
                    try {
                        c.advance(a.has("duration") ? a.num("duration", 1) : script.lastTime() + 0.5);
                    } finally {
                        script.uninstall();
                    }
                }
                default -> throw new Json.ToolError("action: set, tap, script or release");
            }
            Map<String, Object> out = Json.obj("session", c.id(), "robot", r, "timeSeconds", Views.r3(c.sim().clock.seconds()),
                    "gamepad1", Gamepads.describe(run.gamepad1), "gamepad2", Gamepads.describe(run.gamepad2), "opmode", run.state());
            if (run.state() != OpModeRunner.State.INIT && run.state() != OpModeRunner.State.RUNNING) {
                out.put("note", "No OpMode is running: INIT one first (INIT resets the gamepads).");
            }
            if (c.live()) out.put("live", "Applied after the Driver Station delay; the real controllers are overridden until release.");
            out.put("pose", Views.pose(c.sim().robot(r).snapshot()));
            return Tool.Result.of(out);
        });
    }

    // ------------------------------------------------------------------ moving robots

    private Tool.Result move(Json.Args a) throws Exception {
        SimContext c = api.sessions().get(a.str("session", null));
        int r = robot(c, a);
        String action = a.str("action");
        return api.sessions().with(c, () -> {
            Simulation sim = c.sim();
            SimRobot robot = sim.robot(r);
            switch (action) {
                case "place" -> {
                    double x = a.num("x", robot.x() / Views.IN) * Views.IN, y = a.num("y", robot.y() / Views.IN) * Views.IN;
                    double h = Math.toRadians(a.num("heading", Math.toDegrees(robot.heading())));
                    sim.edit(s -> robot.moveRobot(x, y, h));
                }
                case "reset" -> {
                    if (a.has("x") || a.has("y") || a.has("heading")) {
                        FieldConfig.Pose p = robot.startPosition() != null ? robot.startPosition().pose() : sim.fieldConfig.robotStart();
                        double x = a.num("x", p.x() / Views.IN) * Views.IN, y = a.num("y", p.y() / Views.IN) * Views.IN;
                        double h = Math.toRadians(a.num("heading", Math.toDegrees(p.heading())));
                        sim.edit(s -> robot.resetRobot(x, y, h));
                    } else {
                        sim.edit(s -> robot.resetRobot());
                    }
                }
                case "start_position" -> {
                    FieldConfig.StartPosition sp = SimTools.startPosition(sim, a.str("name"));
                    c.stopOpMode(r);
                    sim.edit(s -> s.setStartPosition(robot, sp));
                }
                case "push" -> {
                    double tx = a.num("x") * Views.IN, ty = a.num("y") * Views.IN;
                    robot.setHand(a.num("grab_x", 0) * Views.IN, a.num("grab_y", 0) * Views.IN, tx, ty);
                }
                case "release" -> robot.clearHand();
                default -> throw new Json.ToolError("action: place, reset, start_position, push or release");
            }
            return Tool.Result.of("session", c.id(), "robot", r, "pose", Views.pose(robot.snapshot()),
                    "startPosition", robot.startPosition() == null ? null : robot.startPosition().name(), "opmode", c.runner(r).state(),
                    "pushing", robot.handLine() != null);
        });
    }
}
