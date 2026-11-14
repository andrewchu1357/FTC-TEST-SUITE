package org.ftctestbed.viewer.agent;

import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.SimRunner;
import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.code.HardwareScanner;
import org.ftctestbed.sim.code.RobotCode;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.MatchController;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRegistry;
import org.ftctestbed.sim.opmode.OpModeRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * A simulation an agent made: no window, lockstep (time moves only when a tool asks, as fast as
 * the robot code allows, so every run is the same), each robot with its own freshly compiled copy
 * of its team code plus any test sources the agent gave.
 */
final class HeadlessSession implements SimContext {
    /** One robot to put on the field: its config (file or text) and extra Java sources. */
    record RobotSpec(Path file, String configText, List<Source> extraSources) {}

    /** A Java source file given inline: its path under the source root (org/.../MyTest.java) and text. */
    record Source(String path, String content) {}

    private final String id;
    private final SimHarness harness;
    private final List<OpModeRegistry.Result> registries;
    private final List<Path> robotFiles;
    private final Path fieldFile;
    private final List<String> messages;
    private final Path workDir;
    private final Path project;
    private final List<Path> cacheDirs;

    private HeadlessSession(String id, SimHarness harness, List<OpModeRegistry.Result> registries, List<Path> robotFiles,
                            Path fieldFile, List<String> messages, Path project, Path workDir, List<Path> cacheDirs) {
        this.id = id;
        this.harness = harness;
        this.registries = registries;
        this.robotFiles = robotFiles;
        this.fieldFile = fieldFile;
        this.messages = messages;
        this.workDir = workDir;
        this.project = project;
        this.cacheDirs = cacheDirs;
    }

    /**
     * Loads the robots and field, compiles each robot's code (its package's, else the project's
     * TeamCode, plus extra sources; see compile), loads a fresh copy of it for each robot, and
     * starts the simulation.
     */
    static HeadlessSession create(String id, Path project, List<RobotSpec> robots, Path fieldFile, String fieldText) throws IOException {
        List<String> messages = new ArrayList<>();
        Path work = project.toAbsolutePath().normalize().resolve("build/agent/sessions").resolve(id);
        deleteTree(work);
        Files.createDirectories(work);
        FieldConfig field = fieldText != null ? FieldConfig.parse(fieldText, "field (inline)") : FieldConfig.load(fieldFile);
        for (String w : field.warnings()) messages.add("FIELD CONFIG WARNING: " + w);
        List<RobotConfig> configs = new ArrayList<>();
        List<OpModeRegistry.Result> registries = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        List<Path> cacheDirs = new ArrayList<>();
        for (int i = 0; i < robots.size(); i++) {
            RobotSpec spec = robots.get(i);
            String who = "Robot " + (i + 1) + ": ";
            Path file = spec.file();
            RobotConfig cfg;
            if (spec.configText() != null) {
                // Inline text reads as if it were the given file (its code folders and parts are found
                // from there); without one it's saved in the session's folder.
                if (file == null) {
                    file = work.resolve("robot" + (i + 1) + ".jsonc");
                    Files.writeString(file, spec.configText());
                }
                cfg = RobotConfig.parse(spec.configText(), file.getFileName() + " (inline)", org.ftctestbed.sim.config.PartsCatalog.forConfigFile(file));
            } else {
                cfg = RobotConfig.load(file);
            }
            for (String w : cfg.warnings()) messages.add(who + "CONFIG WARNING: " + w);
            files.add(file);
            configs.add(cfg);

            RobotCode code = RobotCode.forRobot(file, cfg);
            if (code == null) code = RobotCode.forProjectTeamCode(project);
            if (code == null) {
                if (!spec.extraSources().isEmpty()) throw new Json.ToolError("No team code to add the sources to (the project has no TeamCode/src/main/java)");
                registries.add(i == 0 ? OpModeRegistry.scan() : RobotCode.projectTeamCodeCopy());
                messages.add(who + "OpModes from the simulator's built-in TeamCode.");
                continue;
            }
            Compiled compiled = compile(project, code, cfg, spec.extraSources());
            RobotCode own = compiled.code();
            RobotCode.Build build = compiled.build();
            cacheDirs.add(compiled.dir());
            for (String m : build.messages()) messages.add(who + m);
            if (!build.ok()) {
                StringBuilder b = new StringBuilder(who + "the team code didn't compile:\n");
                for (String e : build.errors()) b.append("  ").append(e).append('\n');
                deleteTree(work);
                throw new Json.ToolError(b.toString());
            }
            try {
                for (String c : HardwareScanner.missing(HardwareScanner.scan(own.sourceRoots()), cfg)) messages.add(who + "CONFIG CHECK: " + c);
            } catch (Exception e) {
                messages.add(who + "couldn't check the code's hardware names: " + e.getMessage());
            }
            registries.add(build.opModes());
            for (String w : build.opModes().warnings()) messages.add(who + "WARNING: " + w);
        }
        SimHarness h = SimHarness.of(configs, field, registries);
        synchronized (IN_USE) {
            IN_USE.addAll(cacheDirs);
        }
        return new HeadlessSession(id, h, registries, files, fieldFile, messages, project.toAbsolutePath().normalize(), work, cacheDirs);
    }

    /** One robot's code compiled into its cache folder. */
    record Compiled(RobotCode code, RobotCode.Build build, Path dir) {}

    /** Cache folders open sessions load classes from (never pruned while in use). */
    private static final List<Path> IN_USE = new ArrayList<>();

    /**
     * Compiles team code (plus extra sources) into build/agent/cache/<hash of everything that goes
     * into the build>. The same code compiles once (a repeat reports the same problems, which the
     * build keeps with its classes), and a folder never changes after its build, so sessions loading
     * classes from it lazily are never disturbed by another session's build.
     */
    static Compiled compile(Path project, RobotCode code, RobotConfig cfg, List<Source> extra) throws IOException {
        String key = cacheKey(code, cfg, extra);
        Path cache = project.resolve("build/agent/cache");
        Path dir = cache.resolve(key);
        prune(cache);
        synchronized (key.intern()) {
            List<Path> extraDirs = new ArrayList<>();
            if (!extra.isEmpty()) {
                Path src = dir.resolve("sources");
                if (!Files.exists(dir.resolve("sources.done"))) {
                    deleteTree(src);
                    for (Source s : extra) {
                        if (s.path() == null || s.content() == null) throw new Json.ToolError("Each source needs \"path\" and \"content\"");
                        Path to = src.resolve(s.path()).normalize();
                        if (!to.startsWith(src) || !s.path().endsWith(".java")) {
                            throw new Json.ToolError("Source path " + s.path() + ": give a .java path under the source root, like org/firstinspires/ftc/teamcode/MyTest.java");
                        }
                        Files.createDirectories(to.getParent());
                        Files.writeString(to, s.content());
                    }
                    Files.writeString(dir.resolve("sources.done"), "");
                }
                extraDirs.add(src);
            }
            RobotCode own = code.withSources(extraDirs, dir);
            RobotCode.Build build = own.build();
            try {
                Files.setLastModifiedTime(dir, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            } catch (IOException ignored) {
            }
            return new Compiled(own, build, dir);
        }
    }

    private static String cacheKey(RobotCode code, RobotConfig cfg, List<Source> extra) throws IOException {
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder b = new StringBuilder("v1\n").append(System.getProperty("java.class.path")).append('\n');
        b.append(cfg.code()).append('\n').append(code.dirs()).append('\n');
        for (Path root : code.sourceRoots()) {
            for (Path f : org.ftctestbed.sim.code.SourceFinder.javaFiles(root)) {
                b.append(f).append('@').append(Files.size(f)).append('@').append(Files.getLastModifiedTime(f).toMillis()).append('\n');
            }
        }
        for (Path l : code.libs()) b.append(l).append('@').append(Files.size(l)).append('@').append(Files.getLastModifiedTime(l).toMillis()).append('\n');
        md.update(b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (Source s : extra) {
            md.update(String.valueOf(s.path()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(String.valueOf(s.content()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update((byte) 0);
        }
        return java.util.HexFormat.of().formatHex(md.digest(), 0, 10);
    }

    /** Cache folders unused for two days go (keeping those open sessions use). */
    private static void prune(Path cache) {
        if (!Files.isDirectory(cache)) return;
        long old = System.currentTimeMillis() - 2L * 24 * 3600 * 1000;
        try (var list = Files.list(cache)) {
            for (Path d : list.toList()) {
                synchronized (IN_USE) {
                    if (IN_USE.contains(d)) continue;
                }
                if (Files.getLastModifiedTime(d).toMillis() < old) deleteTree(d);
            }
        } catch (IOException ignored) {
            // a cache we can't tidy still works
        }
    }

    SimHarness harness() {
        return harness;
    }

    @Override public String id() { return id; }
    @Override public boolean live() { return false; }
    @Override public Simulation sim() { return harness.sim; }
    @Override public SimRunner physics() { return harness.physics; }
    @Override public MatchController match() { return harness.match; }
    @Override public int robots() { return harness.robots(); }

    @Override
    public OpModeRunner runner(int robot) {
        check(robot);
        return harness.robot(robot).opModes;
    }

    @Override
    public OpModeRegistry.Result opModes(int robot) {
        check(robot);
        return registries.get(robot - 1);
    }

    @Override
    public Path robotFile(int robot) {
        check(robot);
        return robotFiles.get(robot - 1);
    }

    @Override public Path fieldFile() { return fieldFile; }

    private void check(int robot) {
        if (robot < 1 || robot > robots()) throw new Json.ToolError("No robot " + robot + ": this session has " + robots());
    }

    @Override
    public void advance(double seconds) {
        harness.advance(seconds);
    }

    @Override
    public boolean advanceUntil(BooleanSupplier condition, double maxSeconds) {
        return harness.advanceUntil(condition, maxSeconds);
    }

    @Override
    public void stopOpMode(int robot) {
        check(robot);
        harness.robot(robot).stop();
    }

    @Override
    public void runMatch(List<OpModeInfo> autos, List<OpModeInfo> teleops) {
        List<String> a = new ArrayList<>(), t = new ArrayList<>();
        for (int r = 0; r < robots(); r++) {
            a.add(r < autos.size() && autos.get(r) != null ? autos.get(r).name() : null);
            t.add(r < teleops.size() && teleops.get(r) != null ? teleops.get(r).name() : null);
        }
        harness.runMatch(a, t);
    }

    @Override public List<String> messages() { return messages; }

    @Override
    public Map<String, Object> describe() {
        List<Object> rs = new ArrayList<>();
        for (int r = 1; r <= robots(); r++) {
            Path f = robotFiles.get(r - 1).toAbsolutePath().normalize();
            String config = f.startsWith(workDir) ? "(inline config)" : f.startsWith(project) ? project.relativize(f).toString() : f.toString();
            rs.add(Json.obj("robot", r, "config", config, "alliance", sim().robot(r).alliance(),
                    "startPosition", sim().robot(r).startPosition() == null ? null : sim().robot(r).startPosition().name(),
                    "opModes", opModes(r).opModes().size()));
        }
        return Json.obj("session", id, "kind", "headless (lockstep: time moves only when a tool asks)", "timeSeconds", sim().clock.seconds(),
                "field", sim().fieldConfig.name(), "robots", rs);
    }

    @Override
    public void close() {
        harness.close();
        synchronized (IN_USE) {
            for (Path d : cacheDirs) IN_USE.remove(d);
        }
        try {
            deleteTree(workDir);
        } catch (IOException ignored) {
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
        }
    }
}
