package org.ftctestbed.viewer.platform;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The folder the app works in (config, TeamCode, robots, build output) and its log file.
 *
 * Started from a terminal, Gradle or a script, that's the folder it was started in, as always. A
 * packaged app can't choose that folder (a Windows .exe starts wherever it was double-clicked,
 * pinned or typed), so its launcher names everything with system properties, and the app sets
 * itself up without an installer and without administrator rights:
 *
 * <pre>
 *   -Dtestbed.home=DIR         the working folder; made if it's missing
 *   -Dtestbed.starter=DIR      starter files, copied into the working folder when this version
 *                              of the app first opens it; files already there are never replaced
 *   -Dtestbed.version=TEXT     the app's version (which starter files have been copied is kept by version)
 *   -Dtestbed.home.reset=true  the demo: empties the working folder first (compiled code is kept)
 *   -Dtestbed.log=FILE         what the app prints goes to this file (an .exe has no terminal)
 * </pre>
 *
 * ${user.home} and ${env.NAME} in those are filled in here, and "--home DIR" on the
 * command line picks another working folder (a USB stick, a shared drive).
 */
public final class AppHome {
    private AppHome() {}

    /** Kept through a demo reset: what the app compiled, so the next launch opens faster. */
    private static final Set<String> KEPT_ON_RESET = Set.of("build", ".sim-build");
    private static final long LOG_LIMIT = 4L << 20;

    /**
     * Sets up the log and the working folder, and returns the folder.
     *
     * @throws IOException with a message for the user when the folder can't be made or written to
     */
    public static Path prepare(String[] args) throws IOException {
        startLog(expand(System.getProperty("testbed.log")));
        String home = expand(System.getProperty("testbed.home"));
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals("--home")) home = args[i + 1];
        if (home == null || home.isBlank()) return Path.of("").toAbsolutePath();

        Path dir = Path.of(home).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IOException("Can't create the folder " + dir + ".\n" + reason(e), e);
        }
        if (!writable(dir)) {
            throw new IOException("The folder " + dir + " can't be written to.\n"
                    + "Move the app's folder somewhere of your own (Documents, the Desktop, a USB stick) and open it again.");
        }
        boolean reset = Boolean.getBoolean("testbed.home.reset");
        if (reset) empty(dir);
        String starter = expand(System.getProperty("testbed.starter"));
        if (starter != null && !starter.isBlank()) {
            Path from = Path.of(starter).toAbsolutePath().normalize();
            if (Files.isDirectory(from)) copyStarter(from, dir, reset);
        }
        // Team code's /sdcard files go to the working folder's sdcard/ (see the shim's AppUtil).
        if (System.getProperty("testbed.sdcard") == null) System.setProperty("testbed.sdcard", dir.resolve("sdcard").toString());
        System.out.println("Working folder: " + dir);
        return dir;
    }

    /**
     * For a packaged app's command-line tool (ftc-sim.exe), which reads the folder it is run in:
     * when that folder is the app's working folder, makes sure it has its starter files, so a
     * headless run works there before the window was ever opened (the app's own folder, say). Run
     * anywhere else it does nothing: a tool started in some other folder must not go and create
     * folders in the user's profile. No log, no reset. Problems are reported on stderr and
     * otherwise ignored (the run then says what it can't find).
     */
    public static void ensureStarterFiles() {
        String home = expand(System.getProperty("testbed.home"));
        String starter = expand(System.getProperty("testbed.starter"));
        if (home == null || home.isBlank() || starter == null || starter.isBlank()) return;
        try {
            Path dir = Path.of(home).toAbsolutePath().normalize();
            Path from = Path.of(starter).toAbsolutePath().normalize();
            if (!Files.isDirectory(from) || !Files.isDirectory(dir)) return;
            if (!Files.isSameFile(dir, Path.of("").toAbsolutePath())) return;
            copyStarter(from, dir, false);
        } catch (IOException | RuntimeException e) {
            System.err.println("Couldn't set up the working folder: " + e.getMessage());
        }
    }

    /** Fills in ${user.home} (any system property) and ${env.NAME} (an environment variable). */
    static String expand(String s) {
        if (s == null) return null;
        Matcher m = Pattern.compile("\\$\\{([^}]+)}").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String name = m.group(1), value;
            if (name.startsWith("env.")) value = System.getenv(name.substring(4));
            else value = System.getProperty(name);
            if (value == null && name.equals("env.LOCALAPPDATA")) value = System.getProperty("user.home") + "\\AppData\\Local";
            m.appendReplacement(b, Matcher.quoteReplacement(value == null ? "" : value));
        }
        return m.appendTail(b).toString();
    }

    private static boolean writable(Path dir) {
        try {
            Files.delete(Files.createTempFile(dir, ".write-test", null));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String reason(IOException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * The starter files, into the working folder. Normally only when this version of the app
     * hasn't opened the folder before (so an example you deleted stays deleted until the next
     * update), and never over a file that's there. After a reset: all of them.
     */
    static void copyStarter(Path from, Path to, boolean all) throws IOException {
        String version = System.getProperty("testbed.version", "");
        Path stamp = to.resolve(".starter-version");
        if (!all && !version.isEmpty()) {
            try {
                if (Files.exists(stamp) && Files.readString(stamp).trim().equals(version)) return;
            } catch (IOException ignored) {
                // unreadable: copy again
            }
        }
        int[] copied = {0};
        try {
            Files.walkFileTree(from, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                    Files.createDirectories(to.resolve(from.relativize(d).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    Path target = to.resolve(from.relativize(f).toString());
                    if (all) {
                        Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING);
                        copied[0]++;
                    } else if (!Files.exists(target)) {
                        Files.copy(f, target);
                        copied[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IOException("Can't copy the starter files into " + to + ".\n" + reason(e), e);
        }
        if (!version.isEmpty()) Files.writeString(stamp, version + "\n");
        // On stderr: a headless run's stdout is its result (in the window both go to the log).
        if (copied[0] > 0) System.err.println("Copied " + copied[0] + " starter files into " + to);
    }

    /** Deletes a file or an empty folder. Windows refuses a read-only file, so that mark is taken off first. */
    private static void delete(Path f) throws IOException {
        try {
            Files.deleteIfExists(f);
        } catch (java.nio.file.AccessDeniedException e) {
            if (!f.toFile().setWritable(true)) throw e;
            Files.deleteIfExists(f);
        }
    }

    /** Everything out of the folder except the compiled code. A file that's still held (a virus scanner) is tried again. */
    private static void empty(Path dir) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            last = null;
            try (Stream<Path> top = Files.list(dir)) {
                for (Path p : top.toList()) {
                    if (KEPT_ON_RESET.contains(p.getFileName().toString())) continue;
                    try (Stream<Path> walk = Files.walk(p)) {
                        for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) delete(f);
                    }
                }
            } catch (IOException | UncheckedIOException e) {
                last = e instanceof IOException io ? io : ((UncheckedIOException) e).getCause();
            }
            if (last == null) return;
            try {
                Thread.sleep(40L << attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (last != null) throw new IOException("Can't empty the folder " + dir + " (is the app already open?).\n" + reason(last), last);
    }

    /**
     * Sends System.out and System.err to the log file, adding to it (a log over 4 MB is set aside
     * as .old first). If the file can't be opened the app runs on without a log.
     */
    private static void startLog(String file) {
        if (file == null || file.isBlank()) return;
        try {
            Path log = Path.of(file).toAbsolutePath().normalize();
            Files.createDirectories(log.getParent());
            if (Files.exists(log) && Files.size(log) > LOG_LIMIT) {
                Files.move(log, log.resolveSibling(log.getFileName() + ".old"), StandardCopyOption.REPLACE_EXISTING);
            }
            PrintStream out = new PrintStream(new FileOutputStream(log.toFile(), true), true, StandardCharsets.UTF_8);
            System.setOut(out);
            System.setErr(out);
            out.println("=== " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " "
                    + System.getProperty("testbed.app", "FTC Test Suite") + " " + System.getProperty("testbed.version", "from source")
                    + " (Java " + System.getProperty("java.version") + ", " + System.getProperty("os.name") + " "
                    + System.getProperty("os.arch") + ")");
        } catch (IOException | RuntimeException e) {
            System.err.println("No log file (" + e + ")");
        }
    }
}
