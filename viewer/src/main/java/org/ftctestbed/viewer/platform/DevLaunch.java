package org.ftctestbed.viewer.platform;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The first stop when the app runs from the project's source folder on Windows ("Run
 * Simulator.bat", the Start menu shortcut). The simulator's own code may have
 * changed since it was last built, so this looks first: if any source file is newer than the last
 * build it builds with Gradle (a small window says so), and then it starts the app and is gone.
 * When nothing changed that costs a tenth of a second.
 *
 * It runs as a tiny Java program of its own, from a folder of loose class files, never from the
 * app's jars: Windows locks a jar while a program runs from it, and Gradle couldn't replace the
 * jars of the very program that asked for the build. So this class uses nothing else of the app.
 *
 * System properties, from the launcher's arguments file:
 * <pre>
 *   testbed.dev.app     the app's own arguments file (java @file): what to start. Without it this
 *                       only brings the build up to date (tools\ftc-sim-mcp.cmd starts its server itself)
 *   testbed.dev.stdio   true: what this says goes to stderr, never stdout (the agent server's channel)
 *   testbed.dev.watch   source folders and build files, separated by ; (relative to the folder this runs in)
 *   testbed.dev.stamp   a file touched after each good build
 *   testbed.dev.build   the build command, its words separated by |
 *   testbed.log         optional: the app's log file; the build's output is added to it
 * </pre>
 * Team code (TeamCode/, robots/) isn't watched: the app compiles that itself, every time it opens.
 */
public final class DevLaunch {
    private DevLaunch() {}

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static PrintStream log = System.out;

    public static void main(String[] args) throws Exception {
        // The agent server talks to its client over stdout: there, everything said here goes to stderr.
        boolean stdio = Boolean.getBoolean("testbed.dev.stdio");
        if (stdio) log = System.err;
        String appArgs = System.getProperty("testbed.dev.app");
        String stampName = System.getProperty("testbed.dev.stamp");
        Path stamp = stampName == null || stampName.isBlank() ? null : Path.of(stampName);
        List<Path> watch = new ArrayList<>();
        for (String w : System.getProperty("testbed.dev.watch", "").split(";")) if (!w.isBlank()) watch.add(Path.of(w.trim()));

        if (stamp != null && !watch.isEmpty() && stale(watch, stamp)) {
            if (!stdio) openLog();
            build(stamp); // ends the program if the build fails and there's no earlier build to open
        }
        if (appArgs == null) return; // only asked to bring the build up to date (the agent server's script starts it itself)
        if (!Files.exists(Path.of(appArgs))) {
            fail("The app hasn't been built yet: " + appArgs + " is missing. Run the launcher (.bat) once with --rebuild.");
        }
        start(appArgs, args);
    }

    /** True if the stamp is missing or any watched file is newer than it. */
    static boolean stale(List<Path> watch, Path stamp) {
        FileTime built;
        try {
            built = Files.getLastModifiedTime(stamp);
        } catch (IOException e) {
            return true;
        }
        boolean[] newer = {false};
        for (Path w : watch) {
            if (!Files.exists(w)) continue;
            try {
                Files.walkFileTree(w, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                        if (a.lastModifiedTime().compareTo(built) > 0) {
                            newer[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                return true; // can't tell: build, Gradle knows
            }
            if (newer[0]) return true;
        }
        return false;
    }

    /** The build's output goes to the end of the app's log, when the launcher named one. */
    private static void openLog() {
        String file = System.getProperty("testbed.log");
        if (file == null || file.isBlank()) return;
        try {
            Path f = Path.of(file);
            Files.createDirectories(f.toAbsolutePath().getParent());
            log = new PrintStream(new java.io.FileOutputStream(f.toFile(), true), true, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log = System.out;
        }
    }

    /**
     * Runs the build, with a small window that says so. Returns when the app can be started: the
     * build worked (the stamp is touched), or it failed and the user asked for the last good build.
     */
    private static void build(Path stamp) throws InterruptedException {
        String app = System.getProperty("testbed.app", "FTC Test Suite");
        List<String> cmd = new ArrayList<>();
        for (String word : System.getProperty("testbed.dev.build", "").split("\\|")) if (!word.isBlank()) cmd.add(word);
        if (cmd.isEmpty()) return;
        boolean windowed = !GraphicsEnvironment.isHeadless();
        JDialog[] dialog = new JDialog[1];
        if (windowed) {
            SwingUtilities.invokeLater(() -> {
                JDialog d = new JDialog((java.awt.Frame) null, app, false);
                JProgressBar bar = new JProgressBar();
                bar.setIndeterminate(true);
                JPanel p = new JPanel(new BorderLayout(0, 10));
                p.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
                p.add(new JLabel("<html><b>Building the latest code…</b><br>The simulator's own code changed since it was last built.<br>"
                        + "This takes about a minute.</html>"), BorderLayout.NORTH);
                p.add(bar, BorderLayout.CENTER);
                d.setContentPane(p);
                d.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
                d.pack();
                d.setSize(Math.max(d.getWidth(), 440), d.getHeight());
                d.setLocationRelativeTo(null);
                d.setVisible(true);
                dialog[0] = d;
            });
        }
        log.println("=== Building the latest code: " + String.join(" ", cmd));
        // A .bat needs cmd.exe to run it, and its full path (this folder isn't on the PATH).
        if (WINDOWS && (cmd.get(0).endsWith(".bat") || cmd.get(0).endsWith(".cmd"))) {
            cmd.set(0, Path.of(cmd.get(0)).toAbsolutePath().toString());
            cmd.addAll(0, List.of("cmd.exe", "/c"));
        }
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("JAVA_HOME", System.getProperty("java.home")); // Gradle runs on this same JDK
        Deque<String> tail = new ArrayDeque<>();
        int exit;
        try {
            Process p = pb.start();
            p.getOutputStream().close();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    log.println(line);
                    tail.addLast(line);
                    if (tail.size() > 40) tail.removeFirst();
                }
            }
            exit = p.waitFor();
        } catch (IOException e) {
            tail.addLast(String.valueOf(e));
            log.println(e);
            exit = -1;
        }
        if (windowed) SwingUtilities.invokeLater(() -> { if (dialog[0] != null) dialog[0].dispose(); });
        if (exit == 0) {
            try {
                Files.createDirectories(stamp.toAbsolutePath().getParent());
                if (Files.exists(stamp)) Files.setLastModifiedTime(stamp, FileTime.fromMillis(System.currentTimeMillis()));
                else Files.createFile(stamp);
            } catch (IOException e) {
                log.println("Couldn't mark the build as done: " + e);
            }
            return;
        }
        log.println("The build failed (exit " + exit + ").");
        boolean haveOld = Files.exists(stamp);
        if (!windowed) {
            System.err.println(String.join(System.lineSeparator(), tail));
            if (!haveOld) System.exit(1);
            return;
        }
        boolean[] openOld = {false};
        try {
            SwingUtilities.invokeAndWait(() -> {
                JTextArea text = new JTextArea(String.join("\n", tail), 16, 90);
                text.setEditable(false);
                text.setFont(new Font(WINDOWS ? "Consolas" : Font.MONOSPACED, Font.PLAIN, 12));
                text.setCaretPosition(text.getDocument().getLength());
                JPanel p = new JPanel(new BorderLayout(0, 8));
                p.add(new JLabel("The simulator's code didn't build. The end of what the build said:"), BorderLayout.NORTH);
                p.add(new JScrollPane(text), BorderLayout.CENTER);
                Object[] options = haveOld ? new Object[]{"Open the last good build", "Quit"} : new Object[]{"Quit"};
                int choice = JOptionPane.showOptionDialog(null, p, app + ": the code didn't build", JOptionPane.DEFAULT_OPTION,
                        JOptionPane.ERROR_MESSAGE, null, options, options[0]);
                openOld[0] = haveOld && choice == 0;
            });
        } catch (java.lang.reflect.InvocationTargetException e) {
            // no window after all: quit below
        }
        if (!openOld[0]) System.exit(1);
    }

    private static void fail(String message) {
        log.println(message);
        System.err.println(message);
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, message, System.getProperty("testbed.app", "FTC Test Suite"), JOptionPane.ERROR_MESSAGE);
        }
        System.exit(1);
    }

    /** Starts the app on this same Java (without a console window, unless this has one), and ends. */
    private static void start(String appArgs, String[] args) throws IOException {
        boolean console = System.console() != null;
        String exe = WINDOWS ? (console ? "java.exe" : "javaw.exe") : "java";
        List<String> cmd = new ArrayList<>();
        cmd.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + exe);
        String logFile = System.getProperty("testbed.log");
        if (logFile != null && !logFile.isBlank()) cmd.add("-Dtestbed.log=" + logFile);
        String appName = System.getProperty("testbed.app");
        if (appName != null) cmd.add("-Dtestbed.app=" + appName);
        cmd.add("@" + appArgs);
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (console) pb.inheritIO();
        else pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.start();
        System.exit(0);
    }
}
