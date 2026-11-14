package org.ftctestbed.sim.game;

import org.ftctestbed.shim.SimHooks;
import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Runs a full FTC match like the field management system: reset the field, AUTO for 30 s,
 * an 8 s transition (the TELEOP OpModes are INITed), then 2:00 of TELEOP. Phase changes happen
 * on the physics thread at exact simulated times; OpMode start/stop runs on a helper thread.
 * Every robot on the field plays: each runner gets its own AUTO and TELEOP OpMode (or none).
 */
public final class MatchController {
    private final Simulation sim;
    private final List<OpModeRunner> runners;
    // One task at a time, in order; the thread ends when idle, so a closed window doesn't leave one behind.
    private final ExecutorService exec = idleEnding(new java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "match controller");
        t.setDaemon(true);
        return t;
    }));

    private static java.util.concurrent.ThreadPoolExecutor idleEnding(java.util.concurrent.ThreadPoolExecutor e) {
        e.allowCoreThreadTimeOut(true);
        return e;
    }
    private volatile List<OpModeInfo> teleops = List.of();
    private volatile boolean running;
    private volatile boolean starting;
    private volatile boolean[] autoHasOpMode = new boolean[0];
    private volatile double autoStartAt;
    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();

    public MatchController(Simulation sim, OpModeRunner runner) {
        this(sim, List.of(runner));
    }

    /** One runner per robot, the first robot's first. */
    public MatchController(Simulation sim, List<OpModeRunner> runners) {
        this.sim = sim;
        this.runners = List.copyOf(runners);
        sim.addStepListener(s -> onStep());
    }

    public boolean running() {
        return running;
    }

    /** The first robot plays these (either may be null: it sits still for that period); any others sit still. */
    public void start(OpModeInfo auto, OpModeInfo teleop) {
        start(Arrays.asList(auto), Arrays.asList(teleop));
    }

    /**
     * Each robot plays its AUTO and TELEOP OpMode, by runner order; null, or a missing entry, sits
     * still for that period.
     */
    public void start(List<OpModeInfo> autos, List<OpModeInfo> teleops) {
        List<OpModeInfo> a = padded(autos), t = padded(teleops);
        this.teleops = t;
        submit(() -> {
            running = false;
            starting = false;
            for (OpModeRunner r : runners) r.stop();
            awaitStopped();
            sim.match().enter(MatchState.Phase.PRACTICE, sim.clock.seconds());
            sim.resetField();
            boolean[] has = new boolean[runners.size()];
            for (int i = 0; i < runners.size(); i++) {
                if (a.get(i) == null) continue;
                runners.get(i).setAutoTimer(true);
                runners.get(i).init(a.get(i));
                has[i] = true;
            }
            // AUTO starts 0.5 s of robot time after INIT, counted by the physics steps (see onStep).
            boolean any = false;
            for (boolean h : has) any |= h;
            autoHasOpMode = has;
            autoStartAt = sim.clock.seconds() + (any ? 0.5 : 0);
            starting = true;
        });
    }

    private List<OpModeInfo> padded(List<OpModeInfo> list) {
        List<OpModeInfo> out = new ArrayList<>();
        for (int i = 0; i < runners.size(); i++) out.add(list != null && i < list.size() ? list.get(i) : null);
        return out;
    }

    /** True while the controller is stopping or INITing an OpMode in the background. */
    public boolean busy() {
        return pending.get() > 0;
    }

    private void submit(Runnable task) {
        pending.incrementAndGet();
        exec.submit(() -> {
            try {
                task.run();
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    /** Stop the match and go back to free practice. */
    public void abort() {
        running = false;
        starting = false;
        sim.match().enter(MatchState.Phase.PRACTICE, sim.clock.seconds());
        submit(this::stopAll);
    }

    private void stopAll() {
        for (OpModeRunner r : runners) r.stop();
    }

    private void onStep() {
        MatchState m = sim.match();
        double now = sim.clock.seconds();
        if (starting && now >= autoStartAt) {
            starting = false;
            m.enter(MatchState.Phase.AUTO, now);
            running = true;
            SimHooks.log("I", "Match", "AUTO start");
            boolean[] has = autoHasOpMode;
            for (int i = 0; i < runners.size(); i++) if (i < has.length && has[i]) runners.get(i).start();
            return;
        }
        if (!running) return;
        double e = m.elapsedInPhase(now);
        switch (m.phase()) {
            case AUTO -> {
                if (e >= MatchState.AUTO_SECONDS) {
                    m.enter(MatchState.Phase.TRANSITION, now);
                    List<OpModeInfo> t = teleops;
                    submit(() -> {
                        stopAll();
                        awaitStopped();
                        for (int i = 0; i < runners.size(); i++) {
                            if (t.get(i) != null && running) runners.get(i).init(t.get(i));
                        }
                    });
                }
            }
            case TRANSITION -> {
                if (e >= MatchState.TRANSITION_SECONDS) {
                    m.enter(MatchState.Phase.TELEOP, now);
                    SimHooks.log("I", "Match", "TELEOP start");
                    for (OpModeRunner r : runners) if (r.state() == OpModeRunner.State.INIT) r.start();
                }
            }
            case TELEOP -> {
                if (e >= MatchState.TELEOP_SECONDS) {
                    m.enter(MatchState.Phase.ENDED, now);
                    running = false;
                    SimHooks.log("I", "Match", "MATCH end");
                    submit(this::stopAll);
                }
            }
            default -> { }
        }
    }

    private void awaitStopped() {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean all = true;
            for (OpModeRunner r : runners) {
                OpModeRunner.State st = r.state();
                if (st != OpModeRunner.State.IDLE && st != OpModeRunner.State.STOPPED) all = false;
            }
            if (all) return;
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
