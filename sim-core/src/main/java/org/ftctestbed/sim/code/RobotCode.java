package org.ftctestbed.sim.code;

import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.opmode.OpModeRegistry;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A robot package's own team code: compiled from source when the simulator starts (or when
 * the sources changed), loaded in a {@link TeamCodeClassLoader}, and scanned for OpModes.
 *
 * Code comes from the robot config's "code" section, or from a {@code code/} folder (and jars
 * in {@code libs/}) next to the robot file.
 */
public final class RobotCode {
    /** A compile error at a place in a source file (line and column start at 1; 0 if unknown). */
    public record Problem(Path file, long line, long column, String message) {}

    /** What a build produced. ok is false if the code didn't compile; errors explains why. */
    public record Build(boolean ok, boolean upToDate, int sourceFiles, List<String> errors, List<String> messages,
                        OpModeRegistry.Result opModes, ClassLoader loader, Path classes) {}

    private final Path robotDir;
    private final Path buildDir;
    private final List<Path> dirs;
    private final List<String> libPatterns;
    private final int javaRelease;
    private volatile List<Problem> problems = List.of();

    private RobotCode(Path robotDir, Path buildDir, List<Path> dirs, List<String> libPatterns, int javaRelease) {
        this.robotDir = robotDir;
        this.buildDir = buildDir;
        this.dirs = dirs;
        this.libPatterns = libPatterns;
        this.javaRelease = javaRelease;
    }

    /**
     * The project's own TeamCode module (TeamCode/src/main/java), compiled by the simulator so
     * edits made in the app run without a Gradle build. Its classes go to build/sim-teamcode, never
     * into Gradle's output; its libraries (FTCLib...) come from the simulator's classpath, as in
     * the Gradle build. Null if the project has no TeamCode sources.
     */
    public static RobotCode forProjectTeamCode(Path projectDir) {
        Path src = projectDir.resolve("TeamCode/src/main/java").toAbsolutePath().normalize();
        if (!Files.isDirectory(src)) return null;
        return new RobotCode(projectDir.toAbsolutePath().normalize(), projectDir.toAbsolutePath().normalize().resolve("build/sim-teamcode"),
                List.of(src), List.of(), 8);
    }

    /**
     * This code plus more source folders (an AI agent's or a test's own OpModes, say), compiled
     * into buildDir instead of the usual folder, so it never disturbs the window's build.
     */
    public RobotCode withSources(List<Path> extraDirs, Path buildDir) {
        List<Path> all = new ArrayList<>(dirs);
        all.addAll(extraDirs);
        return new RobotCode(robotDir, buildDir, all, libPatterns, javaRelease);
    }

    /**
     * Compile errors from this object's most recent build (empty when the code compiled cleanly). A
     * build that reuses earlier classes reports the errors of the compile that made them.
     */
    public List<Problem> problems() { return problems; }

    /** The robot's code, or null if it uses the project's TeamCode module. */
    public static RobotCode forRobot(Path robotFile, RobotConfig cfg) {
        Path dir = robotFile.toAbsolutePath().normalize().getParent();
        if (cfg.code() != null) {
            List<Path> dirs = cfg.code().dirs().stream().map(d -> dir.resolve(d).normalize()).toList();
            return new RobotCode(dir, dir.resolve("build"), dirs, cfg.code().libs(), cfg.code().javaRelease());
        }
        if (Files.isDirectory(dir.resolve("code"))) {
            return new RobotCode(dir, dir.resolve("build"), List.of(dir.resolve("code")), List.of("libs/*.jar"), 8);
        }
        return null;
    }

    public List<Path> dirs() { return dirs; }

    /** Source roots after looking inside each configured folder (repository, module or source folder). */
    public List<Path> sourceRoots() throws IOException {
        Set<Path> roots = new LinkedHashSet<>();
        for (Path d : dirs) {
            if (!Files.isDirectory(d)) throw new IOException("Code folder not found: " + d);
            roots.addAll(SourceFinder.roots(d));
        }
        return new ArrayList<>(roots);
    }

    public List<Path> libs() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String pattern : libPatterns) {
            // The folder and the file name apart before either becomes a Path: a pattern such as
            // libs/*.jar isn't a path on Windows, where * can't be in a file name.
            String unix = pattern.replace('\\', '/');
            int slash = unix.lastIndexOf('/');
            String name = unix.substring(slash + 1);
            Path parent = slash < 0 ? robotDir : robotDir.resolve(unix.substring(0, slash)).normalize();
            if (!name.contains("*") && !name.contains("?")) {
                Path p = parent.resolve(name).normalize();
                if (Files.exists(p)) out.add(p);
                continue;
            }
            if (!Files.isDirectory(parent)) continue;
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + name);
            try (Stream<Path> files = Files.list(parent)) {
                files.filter(f -> m.matches(f.getFileName())).sorted().forEach(out::add);
            }
        }
        return out;
    }

    public Path classesDir() {
        return buildDir.resolve("classes");
    }

    /**
     * The simulator's own parts, which a copy of the team code must share with the first robot
     * (they're on the classpath for the simulator, not for the team code).
     */
    private static final String[] SIMULATOR = {"org.dyn4j.", "javafx.", "com.studiohartman.", "com.badlogic.",
            "org.junit.", "org.opentest4j.", "org.apiguardian."};

    /**
     * A second copy of the project's TeamCode (the TeamCode module on the simulator's classpath)
     * for a partner robot running the same code. Its classes, and the libraries they use (Road
     * Runner, Pedro, FTCLib, ...), are loaded again in their own TeamCodeClassLoader, so the partner
     * has its own static fields (PoseStorage, a command scheduler, ...): on a real field the two
     * robots are two Robot Controllers that share nothing. The SDK stand-ins, FTC Dashboard and
     * Panels are still shared.
     */
    public static OpModeRegistry.Result projectTeamCodeCopy() {
        return projectTeamCodeCopy(null);
    }

    /**
     * The same, but with the TeamCode classes compiled by the simulator (classesDir of
     * {@link #forProjectTeamCode}) ahead of the copy on the classpath, so the partner runs the latest edits.
     */
    public static OpModeRegistry.Result projectTeamCodeCopy(Path compiledTeamCode) {
        List<String> roots = compiledTeamCode != null ? List.of(compiledTeamCode.toString()) : OpModeRegistry.teamCodeRoots();
        Set<String> simulator = new java.util.HashSet<>();
        for (Class<?> c : new Class<?>[]{org.ftctestbed.shim.SimHooks.class, RobotCode.class}) {
            try {
                simulator.add(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).normalize().toString());
            } catch (Exception ignored) {
                // no code source: nothing to leave out
            }
        }
        List<URL> urls = new ArrayList<>();
        if (compiledTeamCode != null) {
            try {
                urls.add(compiledTeamCode.toUri().toURL());
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path p = Path.of(entry).toAbsolutePath().normalize();
            if (simulator.contains(p.toString())) continue;
            try {
                urls.add(p.toUri().toURL());
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }
        ClassLoader loader = new TeamCodeClassLoader(urls.toArray(new URL[0]), RobotCode.class.getClassLoader(), SIMULATOR);
        OpModeRegistry.Result r = OpModeRegistry.scan(roots, loader);
        if (roots.isEmpty()) r.warnings().add("No TeamCode classes found on the classpath");
        return r;
    }

    /**
     * Compiles if anything changed since the last build, then loads the classes and finds the OpModes.
     * Reusing the classes reports the same problems and files left out as the compile that made them.
     */
    public Build build() {
        List<String> messages = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Path out = classesDir();
        List<Path> sources = new ArrayList<>();
        List<Path> libs;
        try {
            for (Path root : sourceRoots()) {
                sources.addAll(SourceFinder.javaFiles(root));
                messages.add("Team code: " + root);
            }
            libs = libs();
        } catch (IOException e) {
            errors.add(e.getMessage());
            return new Build(false, false, 0, errors, messages, empty(), getClass().getClassLoader(), out);
        }
        if (sources.isEmpty()) {
            errors.add("No .java files found in " + dirs);
            return new Build(false, false, 0, errors, messages, empty(), getClass().getClassLoader(), out);
        }
        String stamp = stamp(sources, libs);
        Path stampFile = buildDir.resolve("classes.stamp");
        Path reportFile = buildDir.resolve("classes.report");
        Report last = null;
        try {
            if (Files.isDirectory(out) && Files.exists(stampFile) && Files.readString(stampFile).equals(stamp)) last = Report.read(reportFile);
        } catch (IOException ignored) {
        }
        boolean upToDate = last != null;
        if (!upToDate) {
            problems = List.of();
            long t0 = System.nanoTime();
            List<String> leftOut = new ArrayList<>();
            if (!compile(sources, libs, out, errors, leftOut)) {
                return new Build(false, false, sources.size(), errors, messages, empty(), getClass().getClassLoader(), out);
            }
            messages.addAll(leftOut);
            try {
                new Report(problems, leftOut).write(reportFile); // before the stamp: classes are only reused with their report
                Files.writeString(stampFile, stamp);
            } catch (IOException ignored) {
            }
            messages.add(String.format(Locale.US, "Compiled %d source files in %.1f s.", sources.size(), (System.nanoTime() - t0) / 1e9));
        } else {
            problems = last.problems();
            messages.addAll(last.messages());
            messages.add("Team code unchanged since the last build (" + sources.size() + " files).");
        }

        List<URL> urls = new ArrayList<>();
        try {
            urls.add(out.toUri().toURL());
            for (Path l : libs) urls.add(l.toUri().toURL());
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
        ClassLoader loader = new TeamCodeClassLoader(urls.toArray(new URL[0]), RobotCode.class.getClassLoader());
        OpModeRegistry.Result opModes = OpModeRegistry.scan(List.of(out.toString()), loader);
        return new Build(true, upToDate, sources.size(), errors, messages, opModes, loader, out);
    }

    private static OpModeRegistry.Result empty() {
        return new OpModeRegistry.Result(new ArrayList<>(), new ArrayList<>());
    }

    /**
     * What a compile said besides its classes: the errors in files it left out, and the messages
     * saying so. Kept next to the classes (one entry per line, tab-separated, escaped) for builds that reuse them.
     */
    private record Report(List<Problem> problems, List<String> messages) {
        void write(Path f) throws IOException {
            StringBuilder b = new StringBuilder();
            for (Problem p : problems) {
                b.append("problem\t").append(escape(p.file() == null ? "" : p.file().toString())).append('\t').append(p.line())
                        .append('\t').append(p.column()).append('\t').append(escape(p.message())).append('\n');
            }
            for (String m : messages) b.append("message\t").append(escape(m)).append('\n');
            Files.writeString(f, b);
        }

        /** The report saved in f, or null if there's none (or it can't be read). */
        static Report read(Path f) {
            List<Problem> problems = new ArrayList<>();
            List<String> messages = new ArrayList<>();
            try {
                for (String line : Files.readAllLines(f)) {
                    String[] p = line.split("\t", -1);
                    if (p[0].equals("problem")) {
                        problems.add(new Problem(p[1].isEmpty() ? null : Path.of(unescape(p[1])), Long.parseLong(p[2]), Long.parseLong(p[3]), unescape(p[4])));
                    } else if (p[0].equals("message")) {
                        messages.add(unescape(p[1]));
                    }
                }
            } catch (IOException | RuntimeException e) {
                return null;
            }
            return new Report(List.copyOf(problems), messages);
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
        }

        private static String unescape(String s) {
            StringBuilder b = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c != '\\' || i + 1 == s.length()) {
                    b.append(c);
                    continue;
                }
                char n = s.charAt(++i);
                b.append(n == 't' ? '\t' : n == 'n' ? '\n' : n == 'r' ? '\r' : n);
            }
            return b.toString();
        }
    }

    /**
     * The FTC SDK compiles at language level 8 (sourceCompatibility 1.8) against Android's library,
     * and Android's compiler (D8) backports newer methods such as List.of and Map.of, so Java 8
     * code may call them on a real robot (Pedro Pathing's tuners do). --release 8 would reject
     * them, so level 8 is -source/-target 8 against this JDK's library; newer levels use --release.
     */
    static List<String> languageOptions(int javaRelease) {
        if (javaRelease <= 8) return List.of("-source", "8", "-target", "8", "-Xlint:-options");
        return List.of("--release", String.valueOf(javaRelease));
    }

    private String stamp(List<Path> sources, List<Path> libs) {
        StringBuilder b = new StringBuilder();
        b.append("options ").append(languageOptions(javaRelease)).append('\n');
        for (Path p : sources) b.append(p).append('@').append(mtime(p)).append('\n');
        for (Path p : libs) b.append(p).append('@').append(mtime(p)).append('\n');
        b.append("classpath ").append(System.getProperty("java.class.path").hashCode());
        return b.toString();
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Compiles the sources. Files that don't compile (and files that need them) are left out, so the
     * rest of the code still runs; messages lists what was left out and why. Fails only if
     * nothing compiles.
     */
    private boolean compile(List<Path> sources, List<Path> libs, Path out, List<String> errors, List<String> messages) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            errors.add("No Java compiler available: run the simulator with a JDK, not a JRE.");
            return false;
        }
        // The robot's own libs first: a team on another version of a library the simulator also
        // has (Pedro Pathing 2 vs 3...) compiles against theirs, as it runs (TeamCodeClassLoader).
        StringBuilder cp = new StringBuilder();
        for (Path l : libs) cp.append(l).append(File.pathSeparator);
        cp.append(System.getProperty("java.class.path"));
        List<String> options = new ArrayList<>(languageOptions(javaRelease));
        options.addAll(List.of("-d", out.toString(),
                "-classpath", cp.toString(), "-encoding", "UTF-8", "-proc:none", "-nowarn", "-Xmaxerrs", "500"));

        List<Path> remaining = new ArrayList<>(sources);
        java.util.Map<Path, String> leftOut = new java.util.LinkedHashMap<>();
        List<String> firstErrors = null;
        Set<String> hints = new LinkedHashSet<>();
        for (int round = 0; round < 8; round++) {
            try {
                deleteTree(out);
                Files.createDirectories(out);
            } catch (IOException e) {
                errors.add("Cannot prepare " + out + ": " + e.getMessage());
                return false;
            }
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            boolean ok;
            try (StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, Locale.US, StandardCharsets.UTF_8)) {
                ok = javac.getTask(null, fm, diagnostics, options, null, fm.getJavaFileObjectsFromPaths(remaining)).call();
            } catch (IOException | RuntimeException e) {
                errors.add("Compiler failed: " + e);
                return false;
            }
            List<String> roundErrors = new ArrayList<>();
            List<Problem> roundProblems = new ArrayList<>();
            java.util.Map<Path, String> bad = new java.util.LinkedHashMap<>();
            boolean global = false;
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() != Diagnostic.Kind.ERROR) continue;
                String msg = d.getMessage(Locale.US);
                String hint = hint(msg);
                if (hint != null) hints.add(hint);
                if (d.getSource() == null) {
                    global = true;
                    roundErrors.add(msg);
                    continue;
                }
                Path file = Path.of(d.getSource().toUri());
                roundProblems.add(new Problem(file, Math.max(0, d.getLineNumber()), Math.max(0, d.getColumnNumber()), msg));
                String line = file.getFileName() + ":" + d.getLineNumber() + ": " + msg;
                roundErrors.add(line);
                bad.putIfAbsent(file, line.lines().findFirst().orElse(line));
            }
            if (firstErrors == null) {
                firstErrors = roundErrors;
                problems = List.copyOf(roundProblems); // every error, before any file is left out
            }
            if (ok) break;
            bad.keySet().retainAll(remaining);
            if (global || bad.isEmpty() || bad.size() >= remaining.size()) {
                errors.addAll(firstErrors);
                errors.addAll(hints);
                return false;
            }
            leftOut.putAll(bad);
            remaining.removeAll(bad.keySet());
            if (round == 7) {
                errors.addAll(firstErrors);
                errors.addAll(hints);
                return false;
            }
        }
        if (!leftOut.isEmpty()) {
            messages.add("Left out " + leftOut.size() + " of " + sources.size() + " files that don't compile in the simulator "
                    + "(OpModes in them, or using them, are missing):");
            leftOut.forEach((f, why) -> messages.add("  " + why));
            for (String h : hints) messages.add("  " + h);
        }
        return true;
    }

    /** Explains common reasons team code doesn't compile in the simulator. */
    static String hint(String msg) {
        if (msg.contains("com.acmerobotics.dashboard")) {
            return "Hint: the simulator only has the common parts of FTC Dashboard (FtcDashboard, @Config, TelemetryPacket, Canvas, MultipleTelemetry).";
        }
        if (msg.contains("com.acmerobotics.roadrunner") || msg.contains("com.pedropathing") || msg.contains("org.firstinspires.ftc.teamcode.R.")) {
            return "Hint: add the libraries your code uses (Road Runner, Pedro Pathing...) as jars in the robot's libs/ folder.";
        }
        if (msg.contains("package com.qualcomm") || msg.contains("package org.firstinspires.ftc") || msg.contains("package android")) {
            return "Hint: your code uses an SDK or Android class the simulator's SDK shim doesn't have yet. Add it to sdk-shim/ (same package, name and methods as the real SDK).";
        }
        return null;
    }

    /**
     * Deletes a folder and everything in it. On Windows a virus scanner, the search indexer or a
     * cloud-sync client often holds a file that was just written for a moment: the file then
     * can't be deleted yet, or its folder still counts as not empty. So a failed pass is tried
     * again a few times, a little later each time, before giving up.
     */
    static void deleteTree(Path p) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            if (!Files.exists(p)) return;
            try (Stream<Path> walk = Files.walk(p)) {
                for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
                return;
            } catch (IOException | java.io.UncheckedIOException e) {
                last = e instanceof IOException io ? io : ((java.io.UncheckedIOException) e).getCause();
            }
            try {
                Thread.sleep(25L << attempt); // 25, 50, 100, 200, 400 ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (last != null && Files.exists(p)) throw last;
    }
}
