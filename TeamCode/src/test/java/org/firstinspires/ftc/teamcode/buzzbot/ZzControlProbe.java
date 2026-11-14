package org.firstinspires.ftc.teamcode.buzzbot;

import com.qualcomm.robotcore.hardware.Gamepad;

import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.SimRobot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Scratch: how the robot answers the sticks (latency, stopping, drift, smallest move). Prints only. */
class ZzControlProbe {
    static final double IN = 0.0254;

    record Sample(double t, double x, double y, double h, double v, double w) {}

    static List<Sample> record(SimHarness sim, List<Sample> out) {
        SimRobot r = sim.sim.robot(1);
        sim.sim.addStepListener(s -> out.add(new Sample(s.clock.seconds(), r.x(), r.y(), r.heading(), r.speed(), r.omega())));
        return out;
    }

    static SimHarness teleop() {
        PoseStorage.pose = null;
        PoseStorage.alliance = Alliance.RED;
        SimHarness sim = SimHarness.load();
        sim.startAt("Red Wall").init("BuzzBot TeleOp").start();
        sim.sim.robot(1).moveRobot(-45 * IN, -40 * IN, 0); // open floor, clear of the HIVE frame and walls
        sim.runFor(0.5);
        return sim;
    }

    static double firstTime(List<Sample> s, double t0, java.util.function.Predicate<Sample> p) {
        for (Sample a : s) if (a.t >= t0 && p.test(a)) return a.t - t0;
        return Double.NaN;
    }

    static void step(String name, Consumer<Gamepad> on, boolean turn) {
        try (SimHarness sim = teleop()) {
            List<Sample> s = record(sim, new ArrayList<>());
            double t0 = sim.sim.clock.seconds();
            Sample start = new Sample(t0, sim.sim.robot(1).x(), sim.sim.robot(1).y(), sim.sim.robot(1).heading(), 0, 0);
            sim.gamepad1(on);
            sim.runFor(0.8);
            double top = turn ? Math.abs(s.get(s.size() - 1).w) : s.get(s.size() - 1).v;
            double t1 = sim.sim.clock.seconds();
            Sample atRelease = s.get(s.size() - 1);
            sim.gamepad1(g -> { g.left_stick_x = 0; g.left_stick_y = 0; g.right_stick_x = 0; });
            sim.runFor(1.5);
            Sample end = s.get(s.size() - 1);
            java.util.function.ToDoubleFunction<Sample> q = turn ? a -> Math.abs(a.w) : a -> a.v;
            double move = firstTime(s, t0, a -> q.applyAsDouble(a) > 0.02 * top);
            double t50 = firstTime(s, t0, a -> q.applyAsDouble(a) > 0.5 * top);
            double t90 = firstTime(s, t0, a -> q.applyAsDouble(a) > 0.9 * top);
            double stopT = firstTime(s, t1, a -> q.applyAsDouble(a) < 0.01 * top);
            double slide = Math.hypot(end.x - atRelease.x, end.y - atRelease.y) / IN;
            double turnAfter = Math.toDegrees(end.h - atRelease.h);
            // Drift: sideways from the robot's start heading, heading change while driving.
            double c = Math.cos(start.h), sn = Math.sin(start.h);
            double dx = atRelease.x - start.x, dy = atRelease.y - start.y;
            double along = (dx * c + dy * sn) / IN, across = (-dx * sn + dy * c) / IN;
            System.out.printf(Locale.US, "%-22s top %s | first motion %.0f ms, 50%% %.0f ms, 90%% %.0f ms | release: stop %.0f ms, slides %.2f in (%.0f mm), turns %.2f deg | while driving: along %.1f in, across %.2f in, heading %.2f deg | after 1.5 s still: v %.4f in/s w %.4f deg/s | loop %.1f ms%n",
                    name, turn ? String.format(Locale.US, "%.0f deg/s", Math.toDegrees(top)) : String.format(Locale.US, "%.1f in/s", top / IN),
                    move * 1000, t50 * 1000, t90 * 1000, stopT * 1000, slide, slide * 25.4, turnAfter,
                    along, across, Math.toDegrees(atRelease.h - start.h), end.v / IN, Math.toDegrees(end.w), sim.robot(1).loopMillis());
        }
    }

    static void tap(String name, double seconds, Consumer<Gamepad> on) {
        try (SimHarness sim = teleop()) {
            SimRobot r = sim.sim.robot(1);
            double x0 = r.x(), y0 = r.y(), h0 = r.heading();
            sim.gamepad1(on);
            sim.runFor(seconds);
            sim.gamepad1(g -> { g.left_stick_x = 0; g.left_stick_y = 0; g.right_stick_x = 0; g.left_bumper = false; });
            sim.runFor(1.5);
            System.out.printf(Locale.US, "%-34s moved %.1f mm, heading %.3f deg%n", name,
                    Math.hypot(r.x() - x0, r.y() - y0) * 1000, Math.toDegrees(r.heading() - h0));
        }
    }

    @Test
    void probe() {
        step("forward full", g -> g.left_stick_y = -1, false);
        step("forward 30%", g -> g.left_stick_y = -0.3f, false);
        step("strafe left full", g -> g.left_stick_x = -1, false);
        step("strafe left 30%", g -> g.left_stick_x = -0.3f, false);
        step("diagonal full", g -> { g.left_stick_x = -0.7f; g.left_stick_y = -0.7f; }, false);
        step("turn right 30%", g -> g.right_stick_x = 0.3f, true);
        step("turn right full", g -> g.right_stick_x = 1, true);
        for (double ms : new double[]{30, 60, 100, 200}) tap("tap forward " + (int) ms + " ms", ms / 1000, g -> g.left_stick_y = -1);
        for (double ms : new double[]{60, 100, 200}) tap("tap forward slow " + (int) ms + " ms", ms / 1000, g -> { g.left_stick_y = -1; g.left_bumper = true; });
        for (float v : new float[]{0.05f, 0.1f, 0.15f, 0.2f, 0.3f}) tap("hold stick " + v + " for 1 s", 1.0, g -> g.left_stick_y = -v);
        for (float v : new float[]{0.1f, 0.2f}) tap("hold strafe " + v + " for 1 s", 1.0, g -> g.left_stick_x = v);
    }
}
