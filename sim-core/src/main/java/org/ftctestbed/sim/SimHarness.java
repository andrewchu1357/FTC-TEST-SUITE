package org.ftctestbed.sim;

import com.qualcomm.robotcore.hardware.Gamepad;

import org.ftctestbed.sim.code.RobotCode;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.MatchController;
import org.ftctestbed.sim.game.MatchState;
import org.ftctestbed.sim.game.SeasonSnapshot;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRegistry;
import org.ftctestbed.sim.opmode.OpModeRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Drives an OpMode from a test or script, with no window, as fast as the robot code allows.
 *
 * <pre>
 * try (SimHarness sim = SimHarness.load()) {
 *     sim.init("Encoder Drive Auto").start().runUntilStopped(30);
 *     assertEquals(55, sim.xInches(), 2.0);
 * }
 * </pre>
 *
 * With a partner robot ({@link #loadWithPartner()}), {@link #robot(int)} drives each robot; the
 * harness's own init/start/gamepad1/xInches are the first robot's.
 */
public final class SimHarness implements AutoCloseable {
    private static final double IN = 0.0254;

    public final Simulation sim;
    public final SimRunner physics;
    /** The first robot's OpModes. */
    public final OpModeRunner opModes;
    public final MatchController match;
    private final OpModeRegistry.Result registry;
    private final List<Driver> drivers = new java.util.ArrayList<>();

    /**
     * One robot's driver: its OpModes (from its own copy of the team code), gamepads and pose.
     * Methods let simulated time run, as the harness's own do.
     */
    public final class Driver {
        public final SimRobot robot;
        public final OpModeRunner opModes;
        private final OpModeRegistry.Result registry;

        private Driver(SimRobot robot, OpModeRegistry.Result registry) {
            this.robot = robot;
            this.opModes = new OpModeRunner(robot, physics);
            this.registry = registry;
        }

        public List<OpModeInfo> availableOpModes() { return registry.opModes(); }

        /** Pick this robot's start position by name and reset the field. */
        public Driver startAt(String startPositionName) {
            sim.setStartPosition(robot, startPosition(startPositionName));
            return this;
        }

        /** INIT an OpMode by its Driver Station name, then let init code run for half a second. */
        public Driver init(String opModeName) {
            opModes.init(find(registry, opModeName));
            waitWhile(0.5, () -> opModes.state() == OpModeRunner.State.INIT);
            return this;
        }

        public Driver start() {
            opModes.start();
            return this;
        }

        public Driver stop() {
            stopAll(List.of(opModes));
            return this;
        }

        public Gamepad gamepad1() { return opModes.gamepad1; }
        public Gamepad gamepad2() { return opModes.gamepad2; }

        /** Apply a gamepad state (with press/release edge detection), e.g. {@code g -> g.left_stick_y = -1}. */
        public Driver gamepad1(java.util.function.Consumer<Gamepad> edit) {
            Gamepad g = new Gamepad();
            g.copy(opModes.gamepad1);
            edit.accept(g);
            opModes.gamepad1.simApply(g);
            return this;
        }

        public double xInches() { return robot.x() / IN; }
        public double yInches() { return robot.y() / IN; }
        public double headingDegrees() { return Math.toDegrees(robot.heading()); }
        public List<String> telemetry() { return opModes.telemetry().publishedLines(); }

        /** Average loop time of its OpMode, ms of simulated time (NaN when none runs). */
        public double loopMillis() { return physics.robotLoopMillis(robot); }

        /** The DS-style error message if the OpMode crashed, else null. */
        public String error() { return opModes.error(); }
    }

    private SimHarness(List<RobotConfig> robots, FieldConfig field, boolean lockstep, List<OpModeRegistry.Result> registries) {
        sim = new Simulation(field, robots);
        physics = new SimRunner(sim);
        physics.setLockstep(lockstep);
        for (int i = 0; i < robots.size(); i++) {
            OpModeRegistry.Result r = i < registries.size() ? registries.get(i) : null;
            // A partner running the project's own TeamCode gets its own copy of it.
            if (r == null) r = i == 0 ? OpModeRegistry.scan() : RobotCode.projectTeamCodeCopy();
            drivers.add(new Driver(sim.robot(i + 1), r));
        }
        opModes = drivers.get(0).opModes;
        registry = drivers.get(0).registry;
        List<OpModeRunner> runners = new java.util.ArrayList<>();
        for (Driver d : drivers) runners.add(d.opModes);
        match = new MatchController(sim, runners);
        if (lockstep) physics.setLockstepLimit(sim.clock.nanos()); // time only runs when a method below asks
        physics.start();
    }

    private SimHarness(RobotConfig robot, FieldConfig field, boolean lockstep, OpModeRegistry.Result registry) {
        this(List.of(robot), field, lockstep, java.util.Arrays.asList(registry));
    }

    /** Robot n's driver (1 is the first robot). */
    public Driver robot(int number) { return drivers.get(number - 1); }

    /** Loads config/robot.jsonc and config/field.jsonc from the project (searching upward from the working directory). */
    public static SimHarness load() {
        return load("config/robot.jsonc", "config/field.jsonc");
    }

    /** Config files given relative to the project folder, e.g. "config/examples/starter-robot.jsonc". */
    public static SimHarness load(String robotFile, String fieldFile) {
        Path dir = projectDir();
        return load(dir.resolve(robotFile), dir.resolve(fieldFile));
    }

    private static Path projectDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("config/robot.jsonc"))) dir = dir.getParent();
        if (dir == null) throw new IllegalStateException("Could not find config/robot.jsonc above " + Path.of("").toAbsolutePath());
        return dir;
    }

    /**
     * Loads a robot and field. If the robot has its own code (a robot package with a code/ folder
     * or a "code" section), that code is compiled and its OpModes are the ones available.
     */
    public static SimHarness load(Path robotFile, Path fieldFile) {
        return load(fieldFile, List.of(robotFile));
    }

    /** config/robot.jsonc twice (the robot and a partner, each with its own copy of the code) on config/field.jsonc. */
    public static SimHarness loadWithPartner() {
        Path dir = projectDir();
        Path robot = dir.resolve("config/robot.jsonc");
        return load(dir.resolve("config/field.jsonc"), List.of(robot, robot));
    }

    /** These robots on one field: the first at the field's first start position, a partner at its alliance's other one. */
    public static SimHarness load(Path fieldFile, List<Path> robotFiles) {
        List<RobotConfig> robots = new java.util.ArrayList<>();
        List<OpModeRegistry.Result> registries = new java.util.ArrayList<>();
        for (Path robotFile : robotFiles) {
            RobotConfig robot = RobotConfig.load(robotFile);
            RobotCode code = RobotCode.forRobot(robotFile, robot);
            OpModeRegistry.Result registry = null;
            if (code != null) {
                // Each build loads the classes again: every robot has its own copy.
                RobotCode.Build build = code.build();
                if (!build.ok()) throw new IllegalStateException("Team code for " + robotFile + " didn't compile:\n" + String.join("\n", build.errors()));
                registry = build.opModes();
            }
            robots.add(robot);
            registries.add(registry);
        }
        return new SimHarness(robots, FieldConfig.load(fieldFile), true, registries);
    }

    public static SimHarness of(RobotConfig robot, FieldConfig field) {
        return new SimHarness(robot, field, true, null);
    }

    /** Several robots, each with the OpModes in its registry. */
    public static SimHarness of(List<RobotConfig> robots, FieldConfig field, List<OpModeRegistry.Result> opModes) {
        return new SimHarness(robots, field, true, opModes);
    }

    /** A harness whose OpModes come from the given registry (e.g. a robot package's compiled code). */
    public static SimHarness of(RobotConfig robot, FieldConfig field, OpModeRegistry.Result opModes) {
        return new SimHarness(robot, field, true, opModes);
    }

    public List<OpModeInfo> availableOpModes() { return registry.opModes(); }

    /** Pick a start position from field.jsonc by name (also sets the alliance) and reset the field. */
    public SimHarness startAt(String startPositionName) {
        sim.setStartPosition(startPosition(startPositionName));
        return this;
    }

    private FieldConfig.StartPosition startPosition(String name) {
        return sim.fieldConfig.startPositions().stream()
                .filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No start position \"" + name + "\""));
    }

    private static OpModeInfo find(OpModeRegistry.Result registry, String opModeName) {
        if (opModeName == null) return null;
        return registry.opModes().stream().filter(i -> i.name().equals(opModeName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No OpMode named \"" + opModeName + "\". Found: "
                        + registry.opModes().stream().map(OpModeInfo::name).toList()));
    }

    /**
     * Play a full match: field reset, AUTO (30 s), transition (8 s), TELEOP (2:00).
     * Either OpMode may be null. Gamepad input during TELEOP can be scripted with
     * {@link #onTeleop}. Returns once the match has ended and pieces have settled.
     */
    public SimHarness runMatch(String autoName, String teleopName) {
        return runMatch(java.util.Arrays.asList(autoName), java.util.Arrays.asList(teleopName));
    }

    /** As runMatch, with every robot playing: autoNames.get(i) and teleopNames.get(i) are robot i+1's (null: none). */
    public SimHarness runMatch(List<String> autoNames, List<String> teleopNames) {
        List<OpModeInfo> autos = new java.util.ArrayList<>(), teles = new java.util.ArrayList<>();
        for (int i = 0; i < drivers.size(); i++) {
            OpModeRegistry.Result r = drivers.get(i).registry;
            autos.add(find(r, i < autoNames.size() ? autoNames.get(i) : null));
            teles.add(find(r, i < teleopNames.size() ? teleopNames.get(i) : null));
        }
        match.start(autos, teles);
        long deadline = System.nanoTime() + 600_000_000_000L;
        // The field may still show the previous match's end until the reset has happened.
        while ((match.busy() || sim.match().phase() == MatchState.Phase.ENDED) && System.nanoTime() < deadline) Thread.onSpinWait();
        // Advance in small slices so the clock stops right when the match ends.
        long slice = 20_000_000L;
        long limit = sim.clock.nanos();
        while (sim.match().phase() != MatchState.Phase.ENDED && System.nanoTime() < deadline) {
            if (physics.physicsError() != null) throw new IllegalStateException("Physics crashed", physics.physicsError());
            // Don't run ahead while the match is stopping or INITing an OpMode in the background.
            if (sim.clock.nanos() >= limit && !match.busy()) {
                limit = sim.clock.nanos() + slice;
                physics.setLockstepLimit(limit);
            }
            Thread.onSpinWait();
        }
        hold();
        waitWhile(2.0, () -> true); // let pieces come to rest before final scoring
        return this;
    }

    /** Season scoring (e.g. BioBuzzSnapshot), or null on a field without a season. */
    public SeasonSnapshot season() {
        return sim.snapshot().season();
    }

    public int score(String alliance) {
        SeasonSnapshot s = season();
        return s == null ? 0 : s.score(alliance);
    }

    /** INIT an OpMode by its Driver Station name, then let init code run for half a second. */
    public SimHarness init(String opModeName) {
        drivers.get(0).init(opModeName);
        return this;
    }

    public SimHarness start() {
        opModes.start();
        return this;
    }

    /** Advance the given amount of simulated time (returns early once no robot's OpMode is running). */
    public SimHarness runFor(double seconds) {
        waitWhile(seconds, () -> {
            for (Driver d : drivers) {
                OpModeRunner.State st = d.opModes.state();
                if (st == OpModeRunner.State.RUNNING || st == OpModeRunner.State.INIT) return true;
            }
            return false;
        });
        return this;
    }

    /**
     * Advance simulated time whether or not an OpMode runs (a mechanism settling, a piece rolling,
     * a match clock running down).
     */
    public SimHarness advance(double seconds) {
        waitWhile(seconds, () -> true);
        return this;
    }

    /** Advance until the condition holds (checked about every millisecond of real time), up to maxSeconds; true if it did. */
    public boolean advanceUntil(java.util.function.BooleanSupplier condition, double maxSeconds) {
        waitWhile(maxSeconds, () -> !condition.getAsBoolean());
        return condition.getAsBoolean();
    }

    /** How many robots are on the field. */
    public int robots() {
        return drivers.size();
    }

    /** Run until the OpMode ends by itself (or the 30 s autonomous timer stops it), up to maxSeconds. */
    public SimHarness runUntilStopped(double maxSeconds) {
        runFor(maxSeconds);
        return stop();
    }

    /** Stop every robot's OpMode. */
    public SimHarness stop() {
        List<OpModeRunner> all = new java.util.ArrayList<>();
        for (Driver d : drivers) all.add(d.opModes);
        stopAll(all);
        return this;
    }

    private void stopAll(List<OpModeRunner> runners) {
        physics.setLockstepLimit(Long.MAX_VALUE); // let a sleeping OpMode wake up and see the stop
        for (OpModeRunner r : runners) r.stop();
        long deadline = System.nanoTime() + 10_000_000_000L; // the stuck-OpMode watcher takes 3 s
        for (OpModeRunner r : runners) {
            while (r.state() != OpModeRunner.State.STOPPED && r.state() != OpModeRunner.State.IDLE
                    && System.nanoTime() < deadline) {
                sleep();
            }
        }
        hold();
    }

    private void waitWhile(double simSeconds, java.util.function.BooleanSupplier condition) {
        long end = sim.clock.nanos() + Math.round(simSeconds * 1e9);
        physics.setLockstepLimit(end);
        long lastNanos = sim.clock.nanos(), lastChange = System.nanoTime();
        try {
            while (sim.clock.nanos() < end && condition.getAsBoolean()) {
                if (physics.physicsError() != null) throw new IllegalStateException("Physics crashed", physics.physicsError());
                long now = sim.clock.nanos();
                if (now != lastNanos) {
                    lastNanos = now;
                    lastChange = System.nanoTime();
                } else if (System.nanoTime() - lastChange > STALL_NANOS) {
                    throw new IllegalStateException(String.format(java.util.Locale.US,
                            "Simulated time stopped at %.3f s for %d s of real time (robot code stuck outside the SDK?)", now / 1e9, STALL_NANOS / 1_000_000_000L));
                }
                sleep();
            }
        } finally {
            hold();
        }
    }

    /** A test fails instead of hanging when simulated time stops moving this long (real time). */
    private static final long STALL_NANOS = 60_000_000_000L;

    /** Lockstep: stop the clock where it is until the next call asks for more time. */
    private void hold() {
        if (physics.isLockstep()) physics.setLockstepLimit(sim.clock.nanos());
    }

    private static void sleep() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Gamepad gamepad1() { return opModes.gamepad1; }
    public Gamepad gamepad2() { return opModes.gamepad2; }

    /** Apply a gamepad state (with press/release edge detection), e.g. {@code g -> g.left_stick_y = -1}. */
    public SimHarness gamepad1(java.util.function.Consumer<Gamepad> edit) {
        Gamepad g = new Gamepad();
        g.copy(opModes.gamepad1);
        edit.accept(g);
        opModes.gamepad1.simApply(g);
        return this;
    }

    public double xInches() { return sim.robotX() / IN; }
    public double yInches() { return sim.robotY() / IN; }
    public double headingDegrees() { return Math.toDegrees(sim.robotHeading()); }
    public double seconds() { return sim.clock.seconds(); }
    public List<String> telemetry() { return opModes.telemetry().publishedLines(); }

    /** The DS-style error message if the OpMode crashed, else null. */
    public String error() { return opModes.error(); }

    @Override
    public void close() {
        stop();
        physics.shutdown();
    }
}
