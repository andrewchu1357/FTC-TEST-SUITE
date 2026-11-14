package org.ftctestbed.viewer.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The simulations agents work with: headless sessions they created ("s1", "s2"...) and, when the
 * server runs inside the window, "live" (the window's own simulation). Each has a lock, so two
 * tool calls on one session take turns while calls on different sessions run side by side.
 */
public final class Sessions {
    public static final String LIVE = "live";
    private static final int MAX_HEADLESS = 16;

    private final Path project;
    private final Map<String, SimContext> open = new LinkedHashMap<>();
    private final Map<String, ReentrantLock> locks = new LinkedHashMap<>();
    private final Map<String, Trail> trails = new LinkedHashMap<>();
    private volatile Supplier<SimContext> live = () -> null;
    private int next = 1;

    public Sessions(Path project) {
        this.project = project.toAbsolutePath().normalize();
    }

    public Path project() {
        return project;
    }

    /** The window's simulation, when this server runs in the window (null when it's closed or reloading). */
    public void setLive(Supplier<SimContext> live) {
        this.live = live == null ? () -> null : live;
    }

    public SimContext live() {
        return live.get();
    }

    /** A new id for a headless session ("s1"...), or the name asked for if it's free. */
    public synchronized String newId(String wanted) {
        if (open.size() >= MAX_HEADLESS) {
            throw new Json.ToolError("There are already " + MAX_HEADLESS + " sessions open: close some with sim_close first.");
        }
        if (wanted != null && !wanted.isBlank()) {
            String id = wanted.trim();
            if (!id.matches("[A-Za-z0-9_.-]{1,40}")) throw new Json.ToolError("A session name may use letters, digits, _ . - (up to 40)");
            if (id.equals(LIVE) || open.containsKey(id)) throw new Json.ToolError("There is already a session named " + id);
            return id;
        }
        String id;
        do {
            id = "s" + next++;
        } while (open.containsKey(id));
        return id;
    }

    public synchronized void add(SimContext ctx) {
        open.put(ctx.id(), ctx);
        locks.put(ctx.id(), new ReentrantLock());
    }

    /**
     * The session with this id. Without one: the only headless session, else the live one. Throws
     * a message listing what's open when there's no such session.
     */
    public SimContext get(String id) {
        SimContext live = live();
        synchronized (this) {
            if (id == null || id.isBlank()) {
                if (open.size() == 1) return open.values().iterator().next();
                if (open.isEmpty() && live != null) return live;
                if (open.isEmpty()) throw new Json.ToolError("No simulation is open: create one with sim_create.");
                throw new Json.ToolError("Several sessions are open " + ids(live) + ": say which with \"session\".");
            }
            if (id.equals(LIVE)) {
                if (live == null) throw new Json.ToolError("The live session is the simulator's window: open the app with agent access on "
                        + "(config/agent-api.jsonc) and connect to its address, or use a headless session (sim_create).");
                return live;
            }
            SimContext c = open.get(id);
            if (c == null) throw new Json.ToolError("No session \"" + id + "\". Open: " + ids(live));
            return c;
        }
    }

    private List<String> ids(SimContext live) {
        List<String> ids = new ArrayList<>(open.keySet());
        if (live != null) ids.add(LIVE);
        return ids;
    }

    /** Runs work on a session while holding its lock (the live session has one too). */
    public <T> T with(SimContext ctx, java.util.concurrent.Callable<T> work) throws Exception {
        ReentrantLock lock;
        synchronized (this) {
            lock = locks.computeIfAbsent(ctx.id(), k -> new ReentrantLock());
        }
        lock.lock();
        try {
            return work.call();
        } finally {
            lock.unlock();
        }
    }

    public synchronized List<SimContext> all() {
        List<SimContext> all = new ArrayList<>(open.values());
        SimContext l = live();
        if (l != null) all.add(l);
        return all;
    }

    public void close(String id) {
        SimContext c;
        synchronized (this) {
            if (LIVE.equals(id)) throw new Json.ToolError("The live session is the window's: it closes with the window.");
            c = open.remove(id);
            locks.remove(id);
            Trail t = trails.remove(id);
            if (t != null && c != null) c.sim().removeStepListener(t);
        }
        if (c == null) throw new Json.ToolError("No session \"" + id + "\"");
        c.close();
    }

    public void closeAll() {
        List<String> ids;
        synchronized (this) {
            ids = new ArrayList<>(open.keySet());
        }
        for (String id : ids) {
            try {
                close(id);
            } catch (RuntimeException ignored) {
                // closing the rest matters more
            }
        }
    }

    /** The path each robot drove (for sim_image), recorded from the first call on. */
    public synchronized Trail trail(SimContext ctx) {
        Trail t = trails.get(ctx.id());
        if (t == null || t.sim != ctx.sim()) {
            if (t != null) t.sim.removeStepListener(t);
            t = new Trail(ctx.sim());
            ctx.sim().addStepListener(t);
            trails.put(ctx.id(), t);
        }
        return t;
    }

    /** Each robot's position every 10 ms of simulated time (the newest 20,000). */
    public static final class Trail implements java.util.function.Consumer<org.ftctestbed.sim.Simulation> {
        final org.ftctestbed.sim.Simulation sim;
        private final List<List<double[]>> points = new ArrayList<>();

        Trail(org.ftctestbed.sim.Simulation sim) {
            this.sim = sim;
            for (int i = 0; i < sim.robots().size(); i++) points.add(new ArrayList<>());
        }

        @Override
        public void accept(org.ftctestbed.sim.Simulation s) {
            if (s.clock.tick() % 5 != 0) return;
            synchronized (points) {
                for (int i = 0; i < points.size(); i++) {
                    var r = s.robots().get(i);
                    List<double[]> p = points.get(i);
                    p.add(new double[]{r.x(), r.y()});
                    if (p.size() > 20_000) p.subList(0, 5_000).clear();
                }
            }
        }

        public List<double[]> of(int robot) {
            synchronized (points) {
                return new ArrayList<>(points.get(robot - 1));
            }
        }

        public void clear() {
            synchronized (points) {
                for (List<double[]> p : points) p.clear();
            }
        }
    }
}
