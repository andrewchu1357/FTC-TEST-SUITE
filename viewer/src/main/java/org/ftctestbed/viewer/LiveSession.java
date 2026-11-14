package org.ftctestbed.viewer;

import com.qualcomm.robotcore.hardware.Gamepad;

import org.ftctestbed.sim.SimRunner;
import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.game.MatchController;
import org.ftctestbed.sim.game.MatchState;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRegistry;
import org.ftctestbed.sim.opmode.OpModeRunner;
import org.ftctestbed.viewer.agent.AgentGamepads;
import org.ftctestbed.viewer.agent.Json;
import org.ftctestbed.viewer.agent.SimContext;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * The window's simulation as an agent session ("live"): real time, at the window's speed, with
 * the person at the computer watching. Gamepads an agent sets replace the controllers until it
 * lets go; screenshots and view changes go through the window.
 */
final class LiveSession implements SimContext {
    /** What the window does for agents (each called on the Swing thread). */
    interface Window {
        void show(String view);

        void camera(String preset);

        String[] cameras();

        void setSpeed(double speed);

        void setPaused(boolean paused);

        void reload();

        String currentView();

        BufferedImage screenshot();
    }

    private final Simulation sim;
    private final SimRunner physics;
    private final List<OpModeRunner> runners;
    private final MatchController match;
    private final List<OpModeRegistry.Result> registries;
    private final List<Path> robotFiles;
    private final Path fieldFile;
    private final List<String> messages;
    private final Window window;

    LiveSession(Simulation sim, SimRunner physics, List<OpModeRunner> runners, MatchController match, List<OpModeRegistry.Result> registries,
                List<Path> robotFiles, Path fieldFile, List<String> messages, Window window) {
        this.sim = sim;
        this.physics = physics;
        this.runners = List.copyOf(runners);
        this.match = match;
        this.registries = List.copyOf(registries);
        this.robotFiles = robotFiles;
        this.fieldFile = fieldFile;
        this.messages = List.copyOf(messages);
        this.window = window;
    }

    @Override public String id() { return "live"; }
    @Override public boolean live() { return true; }
    @Override public Simulation sim() { return sim; }
    @Override public SimRunner physics() { return physics; }
    @Override public MatchController match() { return match; }
    @Override public int robots() { return runners.size(); }

    private void check(int robot) {
        if (robot < 1 || robot > robots()) throw new Json.ToolError("No robot " + robot + ": the window has " + robots());
    }

    @Override
    public OpModeRunner runner(int robot) {
        check(robot);
        return runners.get(robot - 1);
    }

    @Override
    public OpModeRegistry.Result opModes(int robot) {
        check(robot);
        return registries.get(robot - 1);
    }

    @Override
    public Path robotFile(int robot) {
        check(robot);
        return robotFiles.get(Math.min(robot, robotFiles.size()) - 1);
    }

    @Override public Path fieldFile() { return fieldFile; }
    @Override public List<String> messages() { return messages; }

    @Override
    public void setGamepad(int robot, int pad, Gamepad state) {
        AgentGamepads.set(runner(robot), pad, state);
    }

    @Override
    public void releaseGamepad(int robot, int pad) {
        AgentGamepads.clear(runner(robot), pad);
    }

    /** Waits (real time) while simulated time runs; a paused window is stepped by hand. */
    @Override
    public void advance(double seconds) {
        advanceUntil(() -> false, seconds);
    }

    @Override
    public boolean advanceUntil(BooleanSupplier condition, double maxSeconds) {
        long target = sim.clock.nanos() + Math.round(maxSeconds * 1e9);
        if (physics.isPaused()) physics.stepManually((int) Math.ceil(maxSeconds / Simulation.DT));
        long deadline = System.nanoTime() + Math.round(maxSeconds / Math.max(0.05, physics.speed()) * 2e9) + 5_000_000_000L;
        long lastNanos = sim.clock.nanos(), lastMove = System.nanoTime();
        while (sim.clock.nanos() < target) {
            if (condition.getAsBoolean()) return true;
            long now = sim.clock.nanos();
            if (now != lastNanos) {
                lastNanos = now;
                lastMove = System.nanoTime();
            } else if (System.nanoTime() - lastMove > 10_000_000_000L || System.nanoTime() > deadline) {
                throw new Json.ToolError("Simulated time isn't moving in the window (paused, or robot code stuck?)");
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Json.ToolError("Interrupted");
            }
        }
        return condition.getAsBoolean();
    }

    @Override
    public void stopOpMode(int robot) {
        OpModeRunner r = runner(robot);
        r.stop();
        long deadline = System.nanoTime() + 7_000_000_000L;
        while (r.state() != OpModeRunner.State.STOPPED && r.state() != OpModeRunner.State.IDLE && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void runMatch(List<OpModeInfo> autos, List<OpModeInfo> teleops) {
        if (physics.isPaused()) throw new Json.ToolError("The window is paused: resume it (ui resume) to play a match");
        match.start(autos, teleops);
        long deadline = System.nanoTime() + Math.round(170 / Math.max(0.05, physics.speed()) * 1e9) + 60_000_000_000L;
        // The field may still show the last match's end until the reset.
        while ((match.busy() || sim.match().phase() == MatchState.Phase.ENDED) && System.nanoTime() < deadline) sleep();
        while (!(sim.match().phase() == MatchState.Phase.ENDED && !match.busy()) && System.nanoTime() < deadline) sleep();
        if (sim.match().phase() != MatchState.Phase.ENDED) throw new Json.ToolError("The match didn't end in time (aborted in the window?)");
        advance(2.0); // pieces settle before final scoring
    }

    private static void sleep() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Json.ToolError("Interrupted");
        }
    }

    @Override
    public Map<String, Object> describe() {
        List<Object> rs = new ArrayList<>();
        for (int r = 1; r <= robots(); r++) {
            rs.add(Json.obj("robot", r, "config", String.valueOf(robotFile(r)), "alliance", sim.robot(r).alliance(),
                    "startPosition", sim.robot(r).startPosition() == null ? null : sim.robot(r).startPosition().name(),
                    "opModes", opModes(r).opModes().size(), "running", runner(r).current() == null ? null : runner(r).current().name() + " " + runner(r).state()));
        }
        return Json.obj("session", "live", "kind", "the simulator's window (real time; a person may be watching and driving)",
                "timeSeconds", Math.round(sim.clock.seconds() * 1000) / 1000.0, "paused", physics.isPaused(), "speed", physics.speed(),
                "view", onEdt(window::currentView), "field", sim.fieldConfig.name(), "robots", rs);
    }

    @Override
    public BufferedImage screenshot(String view) {
        if (view != null && !view.isBlank()) {
            onEdt(() -> {
                window.show(view(view));
                return null;
            });
            try {
                Thread.sleep(400); // a frame or two in the new view
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return onEdt(window::screenshot);
    }

    private static String view(String v) {
        for (String k : new String[]{"3D", "2D", "Code", "Setup"}) if (k.equalsIgnoreCase(v)) return k;
        throw new Json.ToolError("view: 3D, 2D, Code or Setup");
    }

    @Override
    public String control(Map<String, Object> request) {
        Json.Args a = new Json.Args(request);
        String action = a.str("action");
        return switch (action) {
            case "show" -> {
                String v = view(a.str("view"));
                onEdt(() -> {
                    window.show(v);
                    return null;
                });
                yield "Showing " + v;
            }
            case "camera" -> {
                String want = a.str("camera");
                String[] all = onEdt(window::cameras);
                for (String c : all) {
                    if (c.equalsIgnoreCase(want)) {
                        onEdt(() -> {
                            window.camera(c);
                            window.show("3D");
                            return null;
                        });
                        yield "3D camera: " + c;
                    }
                }
                throw new Json.ToolError("No camera \"" + want + "\". Cameras: " + List.of(all));
            }
            case "pause", "resume" -> {
                boolean p = action.equals("pause");
                onEdt(() -> {
                    window.setPaused(p);
                    return null;
                });
                yield p ? "Paused" : "Running";
            }
            case "speed" -> {
                double s = a.num("speed");
                if (s < 0.05 || s > 8) throw new Json.ToolError("speed: 0.25 to 4");
                onEdt(() -> {
                    window.setSpeed(s);
                    return null;
                });
                yield "Speed " + s + "x";
            }
            case "step" -> {
                int n = Math.max(1, a.integer("steps", 50));
                onEdt(() -> {
                    window.setPaused(true);
                    return null;
                });
                double before = sim.clock.seconds();
                advance(n * Simulation.DT);
                yield String.format(java.util.Locale.US, "Stepped %d physics steps (%.3f s -> %.3f s); paused", n, before, sim.clock.seconds());
            }
            case "reload" -> {
                for (OpModeRunner r : runners) {
                    OpModeRunner.State st = r.state();
                    if (st == OpModeRunner.State.INIT || st == OpModeRunner.State.RUNNING) {
                        throw new Json.ToolError("Stop the OpModes first (opmode_control stop): reloading would end them");
                    }
                }
                SwingUtilities.invokeLater(window::reload);
                yield "Reloading the window's robots and code (it recompiles changed team code). The live session comes back in a few seconds: "
                        + "check with sim_list.";
            }
            case "info" -> Json.write(Json.obj("view", onEdt(window::currentView), "cameras", List.of(onEdt(window::cameras)),
                    "paused", physics.isPaused(), "speed", physics.speed()));
            default -> throw new Json.ToolError("action: screenshot, show, camera, pause, resume, speed, step, reload or info");
        };
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> work) {
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return work.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    out.set(work.call());
                } catch (Exception e) {
                    err.set(e);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Json.ToolError("Interrupted");
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
        if (err.get() instanceof RuntimeException re) throw re;
        if (err.get() != null) throw new RuntimeException(err.get());
        return out.get();
    }

    @Override
    public void close() {
        for (OpModeRunner r : runners) AgentGamepads.clearAll(r);
    }
}
