package org.ftctestbed.viewer.agent;

import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.Snapshot;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.game.SeasonSnapshot;
import org.ftctestbed.sim.opmode.OpModeRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Records channels (pose, motors, mechanisms, sensors, telemetry, score...) every interval of
 * simulated time, on the physics thread, so no step is missed however fast the simulation runs.
 * When there would be more than maxRows rows, every other row goes and the interval doubles.
 *
 * Channel names: x y heading vx vy omega speed battery current opmode telemetry
 * telemetry.CAPTION motor.NAME.power|duty|position|velocity|current servo.NAME.position|angle
 * mechanism.NAME odometry.NAME.x|y|heading distance.NAME piece.ID.x|y|z|state score.red
 * score.blue phase clock. NAME may be * for all of them. "r2." in front reads robot 2.
 */
final class Recorder implements Consumer<Simulation> {
    private record Channel(String name, int robot, boolean needsSnapshot, Function<Sample, Object> read) {}

    /** What a channel reads from: the robot's snapshot and runner at the sample time. */
    private record Sample(Snapshot snap, OpModeRunner runner, Simulation sim) {}

    private final SimContext ctx;
    private final List<Channel> channels = new ArrayList<>();
    private final List<Object[]> rows = new ArrayList<>();
    private final int maxRows;
    private double interval;
    private double start, next;
    private boolean truncatedOnce;

    Recorder(SimContext ctx, List<String> names, int defaultRobot, double interval, int maxRows) {
        this.ctx = ctx;
        this.interval = Math.max(Simulation.DT, interval);
        this.maxRows = Math.max(10, maxRows);
        List<String> wanted = new ArrayList<>(names);
        if (wanted.isEmpty()) wanted.addAll(List.of("x", "y", "heading", "speed", "motor.*.power", "mechanism.*"));
        for (String n : wanted) add(n.trim(), defaultRobot);
        if (channels.isEmpty()) throw new Json.ToolError("No channels to record");
    }

    private void add(String full, int defaultRobot) {
        int robot = defaultRobot;
        String n = full;
        if (n.matches("r\\d+\\..*")) {
            robot = Integer.parseInt(n.substring(1, n.indexOf('.')));
            n = n.substring(n.indexOf('.') + 1);
        }
        if (robot < 1 || robot > ctx.robots()) throw new Json.ToolError(full + ": no robot " + robot);
        String prefix = robot == defaultRobot ? "" : "r" + robot + ".";
        int r = robot;
        Snapshot s0 = ctx.sim().robot(r).snapshot();
        String[] p = n.split("\\.", -1);
        switch (p[0]) {
            case "x" -> snap(prefix + n, r, s -> Views.in(s.x()));
            case "y" -> snap(prefix + n, r, s -> Views.in(s.y()));
            case "heading" -> snap(prefix + n, r, s -> Views.heading(s.heading()));
            case "vx" -> snap(prefix + n, r, s -> Views.in(s.vx()));
            case "vy" -> snap(prefix + n, r, s -> Views.in(s.vy()));
            case "omega" -> snap(prefix + n, r, s -> Views.deg(s.omega()));
            case "speed" -> snap(prefix + n, r, s -> Views.in(Math.hypot(s.vx(), s.vy())));
            case "battery" -> snap(prefix + n, r, s -> Views.r2(s.batteryVolts()));
            case "current" -> snap(prefix + n, r, s -> s.physics() == null ? null : Views.r2(s.physics().batteryCurrent()));
            case "opmode" -> channels.add(new Channel(prefix + n, r, false, x -> x.runner().state().name()));
            case "telemetry" -> {
                if (p.length == 1) {
                    channels.add(new Channel(prefix + n, r, false, x -> String.join(" | ", x.runner().telemetry().publishedLines())));
                } else {
                    String caption = n.substring("telemetry.".length());
                    channels.add(new Channel(prefix + n, r, false, x -> telemetryValue(x.runner().telemetry().publishedLines(), caption)));
                }
            }
            case "motor" -> {
                need(p, 3, full, "motor.NAME.power|duty|position|velocity|current");
                for (Snapshot.Motor m : s0.motors()) {
                    if (!p[1].equals("*") && !p[1].equals(m.name())) continue;
                    String name = m.name(), what = p[2];
                    Function<Snapshot.Motor, Object> get = switch (what) {
                        case "power" -> mm -> Views.r3(mm.power());
                        case "duty" -> mm -> Views.r3(mm.duty());
                        case "position" -> Snapshot.Motor::position;
                        case "velocity" -> mm -> Views.r2(mm.velocityTps());
                        case "current" -> mm -> Views.r2(mm.currentA());
                        default -> throw new Json.ToolError(full + ": a motor has power, duty, position, velocity, current");
                    };
                    snap(prefix + "motor." + name + "." + what, r, s -> {
                        for (Snapshot.Motor mm : s.motors()) if (mm.name().equals(name)) return get.apply(mm);
                        return null;
                    });
                }
                if (!p[1].equals("*")) found(s0.motors().stream().map(Snapshot.Motor::name).toList(), p[1], full);
            }
            case "servo" -> {
                need(p, 3, full, "servo.NAME.position|angle");
                for (Snapshot.Servo v : s0.servos()) {
                    if (!p[1].equals("*") && !p[1].equals(v.name())) continue;
                    String name = v.name();
                    boolean angle = p[2].equals("angle");
                    if (!angle && !p[2].equals("position")) throw new Json.ToolError(full + ": a servo has position, angle");
                    snap(prefix + "servo." + name + "." + p[2], r, s -> {
                        for (Snapshot.Servo vv : s.servos()) if (vv.name().equals(name)) return angle ? Views.deg(vv.angle()) : Views.r3(vv.commanded());
                        return null;
                    });
                }
                if (!p[1].equals("*")) found(s0.servos().stream().map(Snapshot.Servo::name).toList(), p[1], full);
            }
            case "mechanism" -> {
                need(p, 2, full, "mechanism.NAME");
                for (Snapshot.Mechanism m : s0.mechanisms()) {
                    if (!p[1].equals("*") && !p[1].equals(m.name())) continue;
                    String name = m.name();
                    snap(prefix + "mechanism." + name, r, s -> {
                        for (Snapshot.Mechanism mm : s.mechanisms()) if (mm.name().equals(name)) return Views.r2(mm.position());
                        return null;
                    });
                }
                if (!p[1].equals("*")) found(s0.mechanisms().stream().map(Snapshot.Mechanism::name).toList(), p[1], full);
            }
            case "odometry" -> {
                need(p, 3, full, "odometry.NAME.x|y|heading");
                for (Snapshot.Odometry o : s0.odometry()) {
                    if (!p[1].equals("*") && !p[1].equals(o.name())) continue;
                    String name = o.name(), what = p[2];
                    if (!List.of("x", "y", "heading").contains(what)) throw new Json.ToolError(full + ": odometry has x, y, heading");
                    snap(prefix + "odometry." + name + "." + what, r, s -> {
                        for (Snapshot.Odometry oo : s.odometry()) {
                            if (!oo.name().equals(name)) continue;
                            return switch (what) {
                                case "x" -> Views.in(oo.x());
                                case "y" -> Views.in(oo.y());
                                default -> Views.heading(oo.heading());
                            };
                        }
                        return null;
                    });
                }
                if (!p[1].equals("*")) found(s0.odometry().stream().map(Snapshot.Odometry::name).toList(), p[1], full);
            }
            case "distance" -> {
                need(p, 2, full, "distance.NAME");
                for (Snapshot.Ray ray : s0.rays()) {
                    if (!p[1].equals("*") && !p[1].equals(ray.name())) continue;
                    String name = ray.name();
                    snap(prefix + "distance." + name, r, s -> {
                        for (Snapshot.Ray rr : s.rays()) if (rr.name().equals(name)) return rr.hit() ? Views.in(Math.hypot(rr.x1() - rr.x0(), rr.y1() - rr.y0())) : null;
                        return null;
                    });
                }
                if (!p[1].equals("*")) found(s0.rays().stream().map(Snapshot.Ray::name).toList(), p[1], full);
            }
            case "piece" -> {
                need(p, 3, full, "piece.ID.x|y|z|state");
                int id;
                try {
                    id = Integer.parseInt(p[1]);
                } catch (NumberFormatException e) {
                    throw new Json.ToolError(full + ": piece ids are numbers (game_pieces lists them)");
                }
                String what = p[2];
                channels.add(new Channel(n, r, false, x -> {
                    for (GamePiece gp : x.sim().pieces().all()) {
                        if (gp.id != id) continue;
                        return switch (what) {
                            case "x" -> Views.in(gp.x());
                            case "y" -> Views.in(gp.y());
                            case "z" -> Views.in(gp.z());
                            default -> gp.state().name();
                        };
                    }
                    return null;
                }));
            }
            case "score" -> {
                need(p, 2, full, "score.red|blue");
                String alliance = p[1];
                snap(n, r, s -> s.season() == null ? null : s.season().score(alliance));
            }
            case "phase" -> channels.add(new Channel(n, r, false, x -> x.sim().match().phase().name()));
            case "clock" -> snap(n, r, s -> Views.r2(s.matchClock()));
            default -> throw new Json.ToolError("Unknown channel \"" + full + "\". Channels: x y heading vx vy omega speed battery current "
                    + "opmode telemetry telemetry.CAPTION motor.NAME.power|duty|position|velocity|current servo.NAME.position|angle "
                    + "mechanism.NAME odometry.NAME.x|y|heading distance.NAME piece.ID.x|y|z|state score.red score.blue phase clock "
                    + "(NAME may be *; prefix r2. for robot 2)");
        }
    }

    private void snap(String name, int robot, Function<Snapshot, Object> get) {
        channels.add(new Channel(name, robot, true, x -> get.apply(x.snap())));
    }

    private static void need(String[] p, int n, String full, String form) {
        if (p.length != n) throw new Json.ToolError(full + ": write it as " + form);
    }

    private static void found(List<String> names, String name, String full) {
        if (!names.contains(name)) throw new Json.ToolError(full + ": no \"" + name + "\" on this robot. There are: " + names);
    }

    /** The value after "CAPTION :" (or "CAPTION:") in telemetry, as a number when it is one. */
    static Object telemetryValue(List<String> lines, String caption) {
        for (String l : lines) {
            int c = l.indexOf(':');
            if (c < 0 || !l.substring(0, c).trim().equals(caption)) continue;
            String v = l.substring(c + 1).trim();
            try {
                return Double.parseDouble(v);
            } catch (NumberFormatException e) {
                return v;
            }
        }
        return null;
    }

    /** Starts recording now (and records the first row at once). */
    void start() {
        Simulation sim = ctx.sim();
        synchronized (sim) {
            start = sim.clock.seconds();
            next = start;
            accept(sim);
            sim.addStepListener(this);
        }
    }

    void stop() {
        ctx.sim().removeStepListener(this);
    }

    @Override
    public void accept(Simulation sim) {
        double now = sim.clock.seconds();
        if (now + 1e-9 < next) return;
        next += interval;
        if (next <= now) next = now + interval;
        Sample[] samples = new Sample[ctx.robots() + 1];
        Object[] row = new Object[channels.size() + 1];
        row[0] = Views.r3(now - start);
        for (int i = 0; i < channels.size(); i++) {
            Channel c = channels.get(i);
            Sample s = samples[c.robot()];
            if (s == null || (c.needsSnapshot() && s.snap() == null)) {
                s = new Sample(c.needsSnapshot() ? sim.robot(c.robot()).snapshot() : null, ctx.runner(c.robot()), sim);
                samples[c.robot()] = s;
            }
            try {
                row[i + 1] = c.read().apply(s);
            } catch (RuntimeException e) {
                row[i + 1] = null;
            }
        }
        synchronized (rows) {
            rows.add(row);
            if (rows.size() > maxRows) {
                List<Object[]> kept = new ArrayList<>();
                for (int i = 0; i < rows.size(); i += 2) kept.add(rows.get(i));
                rows.clear();
                rows.addAll(kept);
                interval *= 2;
                truncatedOnce = true;
            }
        }
    }

    /** Columns, rows, and each numeric column's first, last, min and max (written to csv too, if given). */
    Map<String, Object> result(Path csv) throws IOException {
        List<String> cols = new ArrayList<>();
        cols.add("t");
        for (Channel c : channels) cols.add(c.name());
        List<Object[]> copy;
        synchronized (rows) {
            copy = new ArrayList<>(rows);
        }
        Map<String, Object> stats = Json.obj();
        for (int i = 1; i < cols.size(); i++) {
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            Object first = null, last = null;
            boolean numeric = false;
            Set<Object> distinct = new LinkedHashSet<>();
            for (Object[] r : copy) {
                Object v = r[i];
                if (v == null) continue;
                if (first == null) first = v;
                last = v;
                if (v instanceof Number num) {
                    numeric = true;
                    min = Math.min(min, num.doubleValue());
                    max = Math.max(max, num.doubleValue());
                } else if (distinct.size() < 20) {
                    distinct.add(v);
                }
            }
            stats.put(cols.get(i), numeric ? Json.obj("first", first, "last", last, "min", Views.r3(min), "max", Views.r3(max))
                    : Json.obj("first", first, "last", last, "values", distinct));
        }
        Map<String, Object> out = Json.obj("intervalSeconds", Views.r3(interval), "decimated", truncatedOnce, "columns", cols,
                "rowCount", copy.size(), "summary", stats);
        if (csv != null) {
            StringBuilder b = new StringBuilder(String.join(",", cols)).append('\n');
            for (Object[] r : copy) {
                for (int i = 0; i < r.length; i++) {
                    if (i > 0) b.append(',');
                    Object v = r[i];
                    if (v == null) continue;
                    String s = String.valueOf(v);
                    b.append(s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s);
                }
                b.append('\n');
            }
            Files.createDirectories(csv.toAbsolutePath().getParent());
            Files.writeString(csv, b.toString());
        }
        out.put("rows", copy);
        return out;
    }

    /** Season scoring for a summary line. */
    static String score(SeasonSnapshot s) {
        return s == null ? null : "red " + s.score("red") + ", blue " + s.score("blue");
    }
}
