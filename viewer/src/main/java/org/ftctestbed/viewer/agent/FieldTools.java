package org.ftctestbed.viewer.agent;

import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.game.MatchState;
import org.ftctestbed.sim.game.PieceKind;
import org.ftctestbed.sim.opmode.OpModeInfo;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The field: game pieces and matches. */
final class FieldTools {
    private final AgentApi api;

    FieldTools(AgentApi api) {
        this.api = api;
    }

    List<Tool> tools() {
        return List.of(
                new Tool("game_pieces", "Game pieces", "Field",
                        "The season's game pieces. list: every piece with id, kind, state (FLOOR, AIRBORNE, HELD by a robot, CONTAINED in a "
                                + "field element, OFF_FIELD) and position (inches; z is the height of its center). kinds: what can be spawned. "
                                + "spawn: a new piece on the tiles. move: put a piece on the tiles somewhere else. remove: take it off the field. "
                                + "launch: throw a piece from a point with a velocity (in/s), as a shooter would, to test scoring. give: load a piece "
                                + "into a robot's hopper (as a preload). human_player: the human player's action for an alliance (season-specific).",
                        Tool.Schema.of().session()
                                .oneOf("action", "list (default), kinds, spawn, move, remove, launch, give, human_player.",
                                        "list", "kinds", "spawn", "move", "remove", "launch", "give", "human_player")
                                .integer("id", "The piece (move, remove, launch, give).")
                                .str("kind", "Piece kind for spawn / launch / give (see kinds).")
                                .num("x", "Inches.").num("y", "Inches.").num("z", "launch: height in inches (default 12).")
                                .num("vx", "Inches/second.").num("vy", "Inches/second.").num("vz", "launch: inches/second up.")
                                .str("alliance", "human_player / launch: red or blue.")
                                .robot()
                                .bool("include_off_field", "list: also pieces off the field (default false).").build(), false, this::pieces),
                new Tool("match", "Play a match", "Field",
                        "A full FTC match: field reset, 30 s AUTO, 8 s transition, 2:00 TELEOP, with each robot's OpModes started and "
                                + "stopped by the match clock as on a real field. run plays it to the end and returns the final score with the "
                                + "breakdown, ranking points, referee notes, and each robot's errors; start begins it (headless: then sim_step, "
                                + "with until match_ended); status reads the clock and score; abort stops it.",
                        Tool.Schema.of().session()
                                .oneOf("action", "run (default), start, status, abort.", "run", "start", "status", "abort")
                                .str("auto", "Robot 1's AUTO OpMode (omit: it sits still in AUTO).")
                                .str("teleop", "Robot 1's TELEOP OpMode (omit: the TeleOp its AUTO preselects, if any).")
                                .str("partner_auto", "Robot 2's AUTO.")
                                .str("partner_teleop", "Robot 2's TELEOP.")
                                .array("gamepad", "TELEOP gamepad script (as opmode_run's, t from the start of TELEOP).", Json.obj("type", "object"))
                                .strings("record", "Channels to record during the match (as sim_record).")
                                .num("record_interval", "Seconds between recorded rows (default 0.25).")
                                .bool("image", "Add a picture of the field at the end (default false).").build(), false, this::match));
    }

    // ------------------------------------------------------------------ pieces

    /** Kinds a season can make: those on the field now, and any its season object hands out. */
    static Map<String, PieceKind> kinds(Simulation sim) {
        Map<String, PieceKind> out = new LinkedHashMap<>();
        for (GamePiece p : sim.pieces().all()) out.putIfAbsent(p.kind.id(), p.kind);
        Object season = sim.season();
        if (season != null) {
            for (Method m : season.getClass().getMethods()) {
                if (m.getReturnType() != PieceKind.class || Modifier.isStatic(m.getModifiers())) continue;
                try {
                    if (m.getParameterCount() == 0) {
                        PieceKind k = (PieceKind) m.invoke(season);
                        if (k != null) out.putIfAbsent(k.id(), k);
                    } else if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                        for (String a : new String[]{"red", "blue"}) {
                            PieceKind k = (PieceKind) m.invoke(season, a);
                            if (k != null) out.putIfAbsent(k.id(), k);
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // not a piece factory
                }
            }
        }
        return out;
    }

    private static PieceKind kind(Simulation sim, String id) {
        Map<String, PieceKind> k = kinds(sim);
        PieceKind p = k.get(id);
        if (p == null) {
            for (Map.Entry<String, PieceKind> e : k.entrySet()) if (e.getKey().equalsIgnoreCase(id)) p = e.getValue();
        }
        if (p == null) throw new Json.ToolError("No piece kind \"" + id + "\". Kinds: " + k.keySet());
        return p;
    }

    private static GamePiece piece(Simulation sim, int id) {
        for (GamePiece p : sim.pieces().all()) if (p.id == id) return p;
        throw new Json.ToolError("No piece " + id + " (game_pieces list shows them)");
    }

    private static Map<String, Object> describe(GamePiece p) {
        return Json.obj("id", p.id, "kind", p.kind.id(), "state", p.state(), "x", Views.in(p.x()), "y", Views.in(p.y()), "z", Views.in(p.z()),
                "speed", Views.in(p.speed()), "lastTouchedBy", p.lastTouchedBy());
    }

    private Tool.Result pieces(Json.Args a) throws Exception {
        SimContext c = api.sessions().get(a.str("session", null));
        Simulation sim = c.sim();
        String action = a.str("action", "list");
        return api.sessions().with(c, () -> switch (action) {
            case "list" -> {
                List<Object> out = new ArrayList<>();
                synchronized (sim) {
                    for (GamePiece p : sim.pieces().all()) {
                        if (p.state() == GamePiece.State.OFF_FIELD && !a.bool("include_off_field", false)) continue;
                        out.add(describe(p));
                    }
                }
                yield Tool.Result.of("session", c.id(), "pieces", out, "count", out.size());
            }
            case "kinds" -> {
                List<Object> out = new ArrayList<>();
                for (PieceKind k : kinds(sim).values()) {
                    out.add(Json.obj("kind", k.id(), "shape", k.shape(), "diameterInches", Views.in(2 * k.radius()),
                            "widthInches", Views.in(k.width()), "heightInches", Views.in(k.height()), "massKg", Views.r3(k.mass()),
                            "alliance", k.alliance(), "color", String.format("#%06x", k.color() & 0xffffff)));
                }
                yield Tool.Result.of("session", c.id(), "kinds", out);
            }
            case "spawn" -> {
                PieceKind k = kind(sim, a.str("kind"));
                GamePiece[] made = new GamePiece[1];
                sim.edit(s -> {
                    made[0] = s.pieces().create(k);
                    s.pieces().placeOnFloor(made[0], a.num("x") * Views.IN, a.num("y") * Views.IN, a.num("vx", 0) * Views.IN, a.num("vy", 0) * Views.IN);
                });
                yield Tool.Result.of("session", c.id(), "piece", describe(made[0]),
                        "note", "Spots on a robot or another piece move to the nearest free spot.");
            }
            case "move" -> {
                GamePiece p = piece(sim, a.integer("id", -1));
                if (p.state() == GamePiece.State.HELD || p.state() == GamePiece.State.CONTAINED) {
                    throw new Json.ToolError("Piece " + p.id + " is " + p.state() + ": it belongs to a robot or field element now (remove it, or spawn another)");
                }
                sim.edit(s -> s.pieces().placeOnFloor(p, a.num("x") * Views.IN, a.num("y") * Views.IN, a.num("vx", 0) * Views.IN, a.num("vy", 0) * Views.IN));
                yield Tool.Result.of("session", c.id(), "piece", describe(p));
            }
            case "remove" -> {
                GamePiece p = piece(sim, a.integer("id", -1));
                if (p.state() == GamePiece.State.HELD) throw new Json.ToolError("Piece " + p.id + " is held by a robot");
                sim.edit(s -> s.pieces().takeOffField(p));
                yield Tool.Result.of("session", c.id(), "piece", describe(p));
            }
            case "launch" -> {
                GamePiece[] p = new GamePiece[1];
                if (a.has("id")) {
                    p[0] = piece(sim, a.integer("id", -1));
                    if (p[0].state() == GamePiece.State.HELD) throw new Json.ToolError("Piece " + p[0].id + " is held by a robot");
                }
                PieceKind k = p[0] == null ? kind(sim, a.str("kind")) : null;
                sim.edit(s -> {
                    if (p[0] == null) p[0] = s.pieces().create(k);
                    s.pieces().launch(p[0], a.num("x") * Views.IN, a.num("y") * Views.IN, a.num("z", 12) * Views.IN,
                            a.num("vx", 0) * Views.IN, a.num("vy", 0) * Views.IN, a.num("vz", 0) * Views.IN, true, a.str("alliance", null));
                });
                yield Tool.Result.of("session", c.id(), "piece", describe(p[0]),
                        "note", "It's in the air: sim_step lets it fly (then game_pieces list or sim_state score shows where it went).");
            }
            case "give" -> {
                int r = RobotTools.robot(c, a);
                GamePiece[] p = new GamePiece[1];
                if (a.has("id")) p[0] = piece(sim, a.integer("id", -1));
                PieceKind k = p[0] == null ? kind(sim, a.str("kind")) : null;
                boolean[] ok = new boolean[1];
                sim.edit(s -> {
                    if (p[0] == null) p[0] = s.pieces().create(k);
                    ok[0] = s.robot(r).handling().preload(p[0]);
                });
                if (!ok[0]) throw new Json.ToolError("Robot " + r + " couldn't take it (no hopper, or it's full)");
                yield Tool.Result.of("session", c.id(), "robot", r, "piece", describe(p[0]), "hopper", sim.robot(r).handling().hopper().size());
            }
            case "human_player" -> {
                String al = a.str("alliance");
                if (!al.equals("red") && !al.equals("blue")) throw new Json.ToolError("alliance: red or blue");
                sim.humanPlayerAction(al);
                yield Tool.Result.of("session", c.id(), "alliance", al, "done", true);
            }
            default -> throw new Json.ToolError("action: list, kinds, spawn, move, remove, launch, give or human_player");
        });
    }

    // ------------------------------------------------------------------ match

    private Tool.Result match(Json.Args a) throws Exception {
        SimContext c = api.sessions().get(a.str("session", null));
        String action = a.str("action", "run");
        return api.sessions().with(c, () -> {
            switch (action) {
                case "status" -> {
                    return Tool.Result.of(status(c));
                }
                case "abort" -> {
                    c.match().abort();
                    for (int r = 1; r <= c.robots(); r++) c.stopOpMode(r);
                    return Tool.Result.of(status(c));
                }
                case "run", "start" -> {
                    List<OpModeInfo> autos = new ArrayList<>(), teles = new ArrayList<>();
                    for (int r = 1; r <= c.robots(); r++) {
                        String au = a.str(r == 1 ? "auto" : "partner_auto", null);
                        String te = a.str(r == 1 ? "teleop" : "partner_teleop", null);
                        OpModeInfo ai = au == null ? null : RobotTools.find(c, r, au);
                        OpModeInfo ti = te == null ? null : RobotTools.find(c, r, te);
                        if (ti == null && ai != null && ai.preselectTeleOp() != null && !ai.preselectTeleOp().isBlank()) {
                            try {
                                ti = RobotTools.find(c, r, ai.preselectTeleOp());
                            } catch (Json.ToolError ignored) {
                                // preselects one this robot doesn't have
                            }
                        }
                        autos.add(ai);
                        teles.add(ti);
                    }
                    long consoleFrom = Console.next() - 1;
                    if (action.equals("start")) {
                        c.match().start(autos, teles);
                        long deadline = System.nanoTime() + 30_000_000_000L;
                        while (c.match().busy() && System.nanoTime() < deadline) Thread.sleep(2);
                        Map<String, Object> out = status(c);
                        out.put("next", c.live() ? "The window plays it in real time: match status to follow it."
                                : "sim_step with until match_ended (seconds up to 170) plays it out.");
                        return Tool.Result.of(out);
                    }
                    Recorder rec = a.has("record") ? new Recorder(c, SimTools.strings(a.list("record")), 1, a.num("record_interval", 0.25), 2000) : null;
                    MatchScript script = a.has("gamepad") ? new MatchScript(c, a.list("gamepad")) : null;
                    long w0 = System.nanoTime();
                    if (rec != null) rec.start();
                    if (script != null) c.sim().addStepListener(script);
                    try {
                        c.runMatch(autos, teles);
                    } finally {
                        if (rec != null) rec.stop();
                        if (script != null) c.sim().removeStepListener(script);
                    }
                    Map<String, Object> out = status(c);
                    out.put("realSeconds", Views.r2((System.nanoTime() - w0) / 1e9));
                    List<Object> plays = new ArrayList<>();
                    for (int r = 1; r <= c.robots(); r++) {
                        plays.add(Json.obj("robot", r, "auto", autos.get(r - 1) == null ? null : autos.get(r - 1).name(),
                                "teleop", teles.get(r - 1) == null ? null : teles.get(r - 1).name()));
                    }
                    out.put("played", plays);
                    List<String> printed = new ArrayList<>();
                    for (Console.Line l : Console.since(consoleFrom, 100, null)) printed.add(l.text());
                    out.put("console", printed);
                    if (rec != null) out.put("recording", rec.result(null));
                    Tool.Result res = Tool.Result.of(out);
                    if (a.bool("image", false)) res = res.withPng(SimTools.render(c, api.sessions().trail(c), "top", 1, 720, true, 2.5));
                    return res;
                }
                default -> throw new Json.ToolError("action: run, start, status or abort");
            }
        });
    }

    private static Map<String, Object> status(SimContext c) {
        var snap = c.sim().snapshot();
        Map<String, Object> out = Json.obj("session", c.id(), "timeSeconds", Views.r3(c.sim().clock.seconds()),
                "match", Views.match(c.sim(), snap), "running", c.match().running(), "score", Views.season(snap.season()));
        List<Object> robots = new ArrayList<>();
        for (int r = 1; r <= c.robots(); r++) {
            var run = c.runner(r);
            robots.add(Json.obj("robot", r, "alliance", c.sim().robot(r).alliance(), "opMode", run.current() == null ? null : run.current().name(),
                    "state", run.state(), "error", run.error(), "warning", run.warning(), "telemetry", run.telemetry().publishedLines()));
        }
        out.put("robots", robots);
        return out;
    }

    /** A gamepad script timed from the start of TELEOP (robot 1 unless an entry says otherwise). */
    private static final class MatchScript implements java.util.function.Consumer<Simulation> {
        private final RobotTools.GamepadScript script;
        private boolean started;

        MatchScript(SimContext c, List<Object> entries) {
            script = new RobotTools.GamepadScript(c, 1, entries);
        }

        @Override
        public void accept(Simulation s) {
            if (!started) {
                if (s.match().phase() != MatchState.Phase.TELEOP) return;
                started = true;
                script.startAt(s.clock.seconds());
            }
            script.accept(s);
        }
    }
}
