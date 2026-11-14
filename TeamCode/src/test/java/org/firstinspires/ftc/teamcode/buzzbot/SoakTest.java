package org.firstinspires.ftc.teamcode.buzzbot;

import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.game.GamePiece;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Glitch hunt: drives BuzzBot's TeleOp with random sticks and buttons (intake, shooting, lift,
 * claw, aiming) for minutes of match time and checks every 0.1 s that nothing impossible
 * happens: the robot or a game piece outside the field, a piece inside the robot, pieces
 * stacked on each other on the tiles, a piece hovering in the air, pieces disappearing,
 * non-numbers, or a crash.
 */
class SoakTest {
    private static final double IN = 0.0254;

    @ParameterizedTest(name = "seed {0} from {1}")
    @CsvSource({"1, Red Wall", "5, Blue Audience", "14, Red Audience"})
    void randomDrivingNeverGlitches(long seed, String start) {
        PoseStorage.pose = null;
        PoseStorage.alliance = start.startsWith("Red") ? Alliance.RED : Alliance.BLUE;
        Map<String, String> problems = new LinkedHashMap<>();
        try (SimHarness h = SimHarness.load()) {
            h.startAt(start);
            h.init("BuzzBot TeleOp").start();
            Simulation sim = h.sim;
            Random rnd = new Random(seed);
            int total = sim.pieces().all().size();
            Map<Integer, Double> airborneSince = new HashMap<>();
            Map<String, Double> overlapSince = new HashMap<>();
            double end = sim.clock.seconds() + 120, nextChange = 0;
            float[] stick = new float[3];
            boolean[] buttons = new boolean[6];
            float trigger = 0;
            while (sim.clock.seconds() < end) {
                double t = sim.clock.seconds();
                if (t >= nextChange) {
                    nextChange = t + 0.3 + rnd.nextDouble() * 2;
                    boolean still = rnd.nextDouble() < 0.3;
                    stick[0] = still ? 0 : (float) (rnd.nextDouble() * 2 - 1);
                    stick[1] = still ? 0 : (float) (rnd.nextDouble() * 2 - 1);
                    stick[2] = (float) (rnd.nextGaussian() * 0.4);
                    trigger = rnd.nextDouble() < 0.5 ? 1 : 0;
                    double[] chance = {0.3, 0.15, 0.1, 0.1, 0.3, 0.2};
                    for (int i = 0; i < buttons.length; i++) buttons[i] = rnd.nextDouble() < chance[i];
                }
                float lx = stick[0], ly = stick[1], rx = stick[2], rt = trigger;
                boolean fire = buttons[0] && ((int) (t * 4)) % 2 == 0;
                h.gamepad1(g -> {
                    g.left_stick_x = lx;
                    g.left_stick_y = ly;
                    g.right_stick_x = rx;
                    g.right_trigger = rt;
                    g.a = fire;
                    g.b = buttons[1];
                    g.dpad_up = buttons[2];
                    g.dpad_down = buttons[3];
                    g.right_bumper = buttons[4];
                    g.left_bumper = buttons[5];
                });
                h.runFor(0.1);
                assertNull(h.error(), h.error());
                check(sim, total, airborneSince, overlapSince, problems);
            }
        }
        assertTrue(problems.isEmpty(), "glitches: " + problems);
    }

    private static void check(Simulation sim, int total, Map<Integer, Double> airborneSince, Map<String, Double> overlapSince,
                              Map<String, String> problems) {
        double t = sim.clock.seconds();
        double x = sim.robotX(), y = sim.robotY(), h = sim.robotHeading();
        if (!Double.isFinite(x + y + h)) problems.putIfAbsent("robot pose not finite", "at " + t);
        if (Math.abs(x) > 70.585 * IN || Math.abs(y) > 70.585 * IN) problems.putIfAbsent("robot outside the field", x / IN + ", " + y / IN);
        if (!(sim.batteryVolts() >= 6)) problems.putIfAbsent("battery voltage", "" + sim.batteryVolts());
        List<GamePiece> all = sim.pieces().all();
        if (all.size() != total) problems.putIfAbsent("pieces appeared or vanished", total + " -> " + all.size());
        double c = Math.cos(h), s = Math.sin(h);
        List<GamePiece> floor = new ArrayList<>();
        for (GamePiece p : all) {
            if (!Double.isFinite(p.x() + p.y() + p.z())) problems.putIfAbsent("piece position not finite", p.toString());
            if (p.state() == GamePiece.State.FLOOR) {
                floor.add(p);
                if (Math.abs(p.x()) > 70.585 * IN + 0.01 || Math.abs(p.y()) > 70.585 * IN + 0.01) problems.putIfAbsent("piece outside the field", p.toString());
                double lx = c * (p.x() - x) + s * (p.y() - y), ly = -s * (p.x() - x) + c * (p.y() - y);
                double r = p.kind.radius();
                // BuzzBot's outline: its frame, less the intake mouth (16 in wide, back to 6 in ahead of center).
                double front = Math.abs(ly) < 8 * IN ? 6 * IN : 9 * IN;
                if (lx > -9 * IN + r / 2 && lx < front - r / 2 && Math.abs(ly) < 9 * IN - r / 2) problems.putIfAbsent("piece inside the robot", p + " at t=" + t);
                if (Math.hypot(p.vx(), p.vy()) > 3.6) problems.putIfAbsent("piece shot along the tiles", p + " " + Math.hypot(p.vx(), p.vy()) + " m/s");
            }
            if (p.state() == GamePiece.State.AIRBORNE) {
                airborneSince.putIfAbsent(p.id, t);
                if (t - airborneSince.get(p.id) > 6) problems.putIfAbsent("piece hovering", p + " at z=" + p.z() / IN + " in");
            } else {
                airborneSince.remove(p.id);
            }
        }
        // Pieces can be squeezed together for a moment; stuck on top of each other for a second is a glitch.
        for (int i = 0; i < floor.size(); i++) {
            for (int j = i + 1; j < floor.size(); j++) {
                GamePiece p = floor.get(i), q = floor.get(j);
                String key = p.id + "/" + q.id;
                if (Math.hypot(p.x() - q.x(), p.y() - q.y()) < 0.5 * (p.kind.radius() + q.kind.radius())) {
                    overlapSince.putIfAbsent(key, t);
                    if (t - overlapSince.get(key) > 1.0) problems.putIfAbsent("pieces stuck inside each other", p + " & " + q);
                } else {
                    overlapSince.remove(key);
                }
            }
        }
        if (sim.handling() != null && sim.handling().hopper().size() > 4) problems.putIfAbsent("hopper over capacity", "");
    }
}
