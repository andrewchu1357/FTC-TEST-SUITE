package org.ftctestbed.viewer.agent;

import org.ftctestbed.sim.SimRunner;
import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.Snapshot;
import org.ftctestbed.sim.game.SeasonSnapshot;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The simulation as agents read it: field positions in inches and headings in degrees (what FTC
 * code and field drawings use), rounded so the numbers stay readable. "raw" snapshots keep the
 * simulator's own SI units (meters, radians).
 */
final class Views {
    static final double IN = 0.0254;

    private Views() {}

    static double r3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    static double r2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    static double in(double meters) {
        return r2(meters / IN);
    }

    static double deg(double radians) {
        return r2(Math.toDegrees(radians));
    }

    /** Heading in degrees, -180..180. */
    static double heading(double radians) {
        return r2(Math.toDegrees(Math.IEEEremainder(radians, 2 * Math.PI)));
    }

    static Map<String, Object> pose(Snapshot s) {
        return Json.obj("x", in(s.x()), "y", in(s.y()), "heading", heading(s.heading()),
                "vx", in(s.vx()), "vy", in(s.vy()), "omega", deg(s.omega()),
                "speed", in(Math.hypot(s.vx(), s.vy())));
    }

    static Map<String, Object> opMode(OpModeRunner r, SimRunner physics) {
        OpModeInfo cur = r.current();
        double loop = physics.robotLoopMillis(r.robot());
        return Json.obj("state", r.state(), "opMode", cur == null ? null : cur.name(),
                "kind", cur == null ? null : cur.autonomous() ? "autonomous" : cur.teleOp() ? "teleop" : "other",
                "elapsedSeconds", r3(r.elapsed()), "loopMs", Double.isNaN(loop) ? null : r2(loop),
                "error", r.error(), "warning", r.warning(), "telemetry", r.telemetry().publishedLines(),
                "autoTimer", r.autoTimer(), "gamepad1Rumbling", r.gamepad1.isRumbling(), "gamepad2Rumbling", r.gamepad2.isRumbling());
    }

    static Map<String, Object> opModeInfo(OpModeInfo i) {
        return Json.obj("name", i.name(), "group", i.group(),
                "kind", i.autonomous() ? "autonomous" : i.teleOp() ? "teleop" : "other",
                "class", i.type().getName(), "preselectTeleOp", i.preselectTeleOp(), "utility", i.utility());
    }

    static List<Object> motors(Snapshot s) {
        List<Object> out = new ArrayList<>();
        for (Snapshot.Motor m : s.motors()) {
            out.add(Json.obj("name", m.name(), "hub", m.hub(), "port", m.port(), "mode", m.mode(), "power", r3(m.power()),
                    "duty", r3(m.duty()), "position", m.position(), "velocityTicksPerSec", r2(m.velocityTps()),
                    "currentAmps", r2(m.currentA()), "ticksPerRev", r2(m.ticksPerRev())));
        }
        return out;
    }

    static List<Object> servos(Snapshot s) {
        List<Object> out = new ArrayList<>();
        for (Snapshot.Servo v : s.servos()) {
            out.add(Json.obj("name", v.name(), "continuous", v.continuous(), "commanded", r3(v.commanded()),
                    "angleDeg", deg(v.angle()), "enabled", v.enabled()));
        }
        return out;
    }

    static List<Object> mechanisms(Snapshot s) {
        List<Object> out = new ArrayList<>();
        for (Snapshot.Mechanism m : s.mechanisms()) {
            String unit = switch (m.type()) {
                case "slide" -> "in";
                case "arm" -> "deg";
                default -> "rev";
            };
            out.add(Json.obj("name", m.name(), "type", m.type(), "position", r2(m.position()),
                    "min", Math.abs(m.min()) > 1e12 ? null : r2(m.min()), "max", Math.abs(m.max()) > 1e12 ? null : r2(m.max()), "unit", unit));
        }
        return out;
    }

    static List<Object> odometry(Snapshot s) {
        List<Object> out = new ArrayList<>();
        for (Snapshot.Odometry o : s.odometry()) {
            out.add(Json.obj("name", o.name(), "kind", o.kind(), "x", in(o.x()), "y", in(o.y()), "heading", heading(o.heading()), "status", o.status()));
        }
        return out;
    }

    static List<Object> distanceRays(Snapshot s) {
        List<Object> out = new ArrayList<>();
        for (Snapshot.Ray r : s.rays()) {
            out.add(Json.obj("name", r.name(), "hit", r.hit(), "rangeInches", in(Math.hypot(r.x1() - r.x0(), r.y1() - r.y0())),
                    "from", List.of(in(r.x0()), in(r.y0())), "to", List.of(in(r.x1()), in(r.y1()))));
        }
        return out;
    }

    static List<Object> wheels(Snapshot s) {
        List<Object> out = new ArrayList<>();
        for (Snapshot.Wheel w : s.wheels()) {
            out.add(Json.obj("label", w.label(), "duty", r3(w.duty()), "slipping", w.slipping(), "loadNewtons", r2(w.load()), "lifting", w.lifting()));
        }
        return out;
    }

    static List<Object> lights(Snapshot s) {
        List<Object> out = new ArrayList<>();
        for (Snapshot.Light l : s.lights()) {
            List<String> colors = new ArrayList<>();
            for (int c : l.colors()) colors.add(String.format("#%06x", c & 0xffffff));
            out.add(Json.obj("name", l.name(), "type", l.type(), "colors", colors));
        }
        return out;
    }

    static List<Object> cameras(Snapshot s) {
        List<Object> out = new ArrayList<>();
        for (Snapshot.Camera c : s.cameras()) {
            List<Object> targets = new ArrayList<>();
            for (Snapshot.CameraTarget t : c.targets()) {
                double cx = 0, cy = 0;
                for (double[] p : t.corners()) {
                    cx += p[0];
                    cy += p[1];
                }
                int n = Math.max(1, t.corners().length);
                targets.add(Json.obj("label", t.label(), "primary", t.primary(), "centerPx", List.of(r2(cx / n), r2(cy / n)), "corners", t.corners()));
            }
            out.add(Json.obj("name", c.name(), "kind", c.kind(), "pipeline", c.pipeline(), "pipelineType", c.pipelineType(),
                    "resolution", List.of(c.width(), c.height()), "hfovDeg", deg(c.hfov()), "vfovDeg", deg(c.vfov()),
                    "lensPositionInches", List.of(in(c.position()[0]), in(c.position()[1]), in(c.position()[2])), "targets", targets));
        }
        return out;
    }

    static Map<String, Object> physics(Snapshot s) {
        Snapshot.Physics p = s.physics();
        if (p == null) return null;
        return Json.obj("batteryVolts", r2(s.batteryVolts()), "batteryCharge", r3(p.batteryCharge()), "batteryUsedMah", r2(p.batteryUsedMah()),
                "batteryRestingVolts", r2(p.batteryRestingVolts()), "batteryCurrentAmps", r2(p.batteryCurrent()),
                "cgHeightInches", in(p.cgHeight()), "accelX", r2(p.accelX()), "accelY", r2(p.accelY()),
                "accelUnits", "m/s^2 in the robot frame (x forward, y left)", "liftingWheels", p.liftingWheels());
    }

    static List<Object> pieces(Snapshot s) {
        List<Object> out = new ArrayList<>();
        for (Snapshot.Piece p : s.pieces()) {
            out.add(Json.obj("id", p.id(), "kind", p.kind(), "state", p.state(), "x", in(p.x()), "y", in(p.y()), "z", in(p.z()),
                    "color", String.format("#%06x", p.color() & 0xffffff)));
        }
        return out;
    }

    static Map<String, Object> season(SeasonSnapshot s) {
        if (s == null) return null;
        Map<String, Object> out = Json.obj("title", s.title());
        for (String a : new String[]{"red", "blue"}) {
            out.put(a, Json.obj("score", s.score(a), "breakdown", s.breakdown(a), "rankingPoints", s.rankingPoints(a)));
        }
        out.put("notes", s.notes());
        return out;
    }

    static Map<String, Object> match(Simulation sim, Snapshot s) {
        return Json.obj("phase", sim.match().phase(), "clockSeconds", r2(s.matchClock()));
    }
}
