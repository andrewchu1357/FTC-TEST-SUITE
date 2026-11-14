package org.ftctestbed.viewer;

import org.ftctestbed.sim.SimRobot;
import org.ftctestbed.sim.SimRunner;
import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.config.ConfigException;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.MatchController;
import org.ftctestbed.sim.opmode.OpModeRegistry;
import org.ftctestbed.sim.opmode.OpModeRunner;

import org.ftctestbed.sim.code.HardwareScanner;
import org.ftctestbed.sim.code.RobotCode;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Entry point for the desktop simulator: ./gradlew run */
public final class ViewerMain {
    private static Path robotFile;
    private static Path partnerFile; // the partner robot's config, or null for no partner
    private static Path fieldFile;
    private static Path projectDir;
    private static SimRunner current;
    private static SimWebView webView;

    public static void main(String[] args) {
        // The folder to work in: the one the app was started in, or the one a packaged app's
        // launcher names (made and filled with the starter files here; see AppHome).
        try {
            projectDir = org.ftctestbed.viewer.platform.AppHome.prepare(args);
        } catch (java.io.IOException e) {
            System.err.println(e.getMessage());
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null, e.getMessage(), AppIcon.NAME, JOptionPane.ERROR_MESSAGE);
            }
            System.exit(1);
            return;
        }
        org.ftctestbed.viewer.platform.Win32.setUpApp();
        robotFile = projectDir.resolve("config/robot.jsonc");
        fieldFile = projectDir.resolve("config/field.jsonc");
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--robot")) robotFile = projectDir.resolve(args[++i]).toAbsolutePath();
            else if (args[i].equals("--field")) fieldFile = projectDir.resolve(args[++i]).toAbsolutePath();
            else if (args[i].equals("--partner")) partnerFile = projectDir.resolve(args[++i]).toAbsolutePath();
        }
        // The "Partner robot" switch is remembered between launches, with the robot picked for it.
        java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(MainWindow.class);
        if (partnerFile == null && prefs.getBoolean("partner", false)) {
            String saved = prefs.get("partnerRobot", "");
            Path p = saved.isEmpty() ? robotFile : Path.of(saved);
            partnerFile = java.nio.file.Files.exists(p) ? p : robotFile;
        }
        // Menus in the macOS menu bar; the look (theme, glass, text size) before the first window.
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.appearance", "system");
        org.ftctestbed.viewer.ui.Appearance.init();
        AppIcon.installInDock();
        SwingUtilities.invokeLater(() -> {
            launch();
        });
    }

    private static String webViewNote;

    /**
     * Windows only: whether one of the app's web pages must stay on this computer although its
     * config opens it to the network. The first time a program listens for other computers,
     * Windows Defender Firewall shows a prompt that only an administrator can accept. The app
     * never asks for an administrator by itself, so on Windows a page is opened to the network
     * only when its config also says "allowNetworkOnWindows": true.
     */
    private static boolean thisComputerOnly(String configFile) {
        if (!org.ftctestbed.viewer.platform.Os.WINDOWS) return false;
        Path file = projectDir.resolve(configFile);
        try {
            if (!java.nio.file.Files.exists(file)) return true;
            Object parsed = org.ftctestbed.sim.config.Jsonc.parse(java.nio.file.Files.readString(file), file.toString());
            return !(parsed instanceof java.util.Map<?, ?> map && Boolean.TRUE.equals(map.get("allowNetworkOnWindows")));
        } catch (Exception e) {
            return true;
        }
    }

    private static String windowsNetworkNote(String configFile) {
        return "  (this computer only: opening it to the network makes Windows ask for an administrator once, so that takes"
                + " \"allowNetworkOnWindows\": true in " + configFile + ")";
    }

    /**
     * The view-only web page (config/web-view.jsonc). Starts with the first simulation, so it's
     * only up while the simulator is open; later robots/fields reuse it.
     */
    private static void startWebView(List<String> messages) {
        if (webView == null && webViewNote == null) {
            try {
                SimWebView.Settings settings = SimWebView.loadSettings(projectDir);
                if (settings.enabled()) {
                    boolean localOnly = settings.access() != SimWebView.Access.LOCAL && thisComputerOnly("config/web-view.jsonc");
                    if (localOnly) settings = new SimWebView.Settings(true, settings.port(), SimWebView.Access.LOCAL);
                    SimWebView view = new SimWebView(settings);
                    List<String> urls = view.start();
                    webView = view;
                    webViewNote = "Watch the simulator in a browser (view only): " + String.join("  or  ", urls)
                            + (localOnly ? windowsNetworkNote("config/web-view.jsonc") : "");
                    Runtime.getRuntime().addShutdownHook(new Thread(view::stop, "web view shutdown"));
                } else {
                    webViewNote = "";
                }
            } catch (Exception e) {
                webViewNote = "The view-only web page couldn't start: " + e.getMessage()
                        + " (settings are in config/web-view.jsonc)";
            }
        }
        if (webViewNote != null && !webViewNote.isEmpty()) messages.add(webViewNote);
    }

    /** Builds a fresh simulation from the config files and opens a window for it. */
    static void launch() {
        if (current != null) current.shutdown();
        List<String> messages = new ArrayList<>();
        RobotConfig robot;
        FieldConfig field;
        try {
            robot = RobotConfig.load(robotFile);
            field = FieldConfig.load(fieldFile);
        } catch (ConfigException e) {
            int choice = JOptionPane.showOptionDialog(null, e.getMessage(), "Config error",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null, new String[]{"Retry", "Quit"}, "Retry");
            if (choice == 0) SwingUtilities.invokeLater(ViewerMain::launch); else System.exit(1);
            return;
        }
        messages.add("Loaded " + projectDir.relativize(robotFile) + " and " + projectDir.relativize(fieldFile) + ".");
        for (String w : robot.warnings()) messages.add("CONFIG WARNING: " + w);
        for (String w : field.warnings()) messages.add("CONFIG WARNING: " + w);
        // The partner robot ("Partner robot" in the window). If its config doesn't load it's left off.
        RobotConfig partner = null;
        if (partnerFile != null) {
            try {
                partner = RobotConfig.load(partnerFile);
                for (String w : partner.warnings()) messages.add("CONFIG WARNING (Robot 2): " + w);
            } catch (ConfigException e) {
                messages.add("The partner robot's config didn't load, so it's left off: " + e.getMessage());
                partnerFile = null;
            }
        }

        Simulation sim;
        try {
            sim = partner != null ? new Simulation(field, List.of(robot, partner)) : new Simulation(robot, field);
        } catch (ConfigException e) {
            JOptionPane.showMessageDialog(null, e.getMessage(), "Config error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }
        RobotCode code = RobotCode.forRobot(robotFile, robot);
        // The project's TeamCode is compiled here too (only when it changed), so edits made in the
        // Code tab run without a Gradle build.
        RobotCode projectCode = RobotCode.forProjectTeamCode(projectDir);
        if (code == null && partner == null && projectCode == null) {
            open(sim, List.of(OpModeRegistry.scan()), messages);
            return;
        }
        // A robot package brings its own code: compile it (in the background, with a progress
        // window). The partner gets its own copy of its code, so the two robots' static fields
        // (a saved pose, the alliance) stay apart.
        RobotConfig partnerConfig = partner;
        RobotCode partnerCode = partner != null ? RobotCode.forRobot(partnerFile, partner) : null;
        boolean partnerSame = partner != null && partnerFile.equals(robotFile);
        javax.swing.JDialog progress = progressDialog(code != null
                ? "Compiling team code for " + projectDir.relativize(robotFile) + "…" : "Compiling TeamCode…");
        new javax.swing.SwingWorker<List<Loaded>, Void>() {
            @Override
            protected List<Loaded> doInBackground() {
                List<Loaded> out = new ArrayList<>();
                out.add(code != null ? build(code, robot)
                        : projectCode != null ? buildProject(projectCode) : new Loaded(OpModeRegistry.scan(), null, List.of()));
                if (partnerConfig != null) out.add(partnerCode != null ? build(partnerCode, partnerConfig) : copyOfProjectCode(projectCode));
                return out;
            }

            @Override
            protected void done() {
                progress.dispose();
                List<Loaded> loaded;
                try {
                    loaded = get();
                } catch (Exception e) {
                    loaded = new ArrayList<>();
                    for (int i = 0; i < (partnerConfig != null ? 2 : 1); i++) loaded.add(new Loaded(noOpModes(), failedBuild(e), List.of()));
                }
                List<OpModeRegistry.Result> registries = new ArrayList<>();
                for (int i = 0; i < loaded.size(); i++) {
                    Loaded l = loaded.get(i);
                    registries.add(l.opModes());
                    if (i > 0 && partnerSame) continue; // the same code as the first robot: already reported
                    String who = i == 0 ? "" : " (Robot 2)";
                    if (l.build() != null) for (String m : l.build().messages()) messages.add(i == 0 ? m : "Robot 2: " + m);
                    for (String c : l.check()) messages.add("CONFIG CHECK" + who + ": " + c);
                    if (l.build() != null && !l.build().ok()) {
                        messages.add(i == 0 ? "TEAM CODE DIDN'T COMPILE:" : "ROBOT 2 TEAM CODE DIDN'T COMPILE:");
                        for (String e : l.build().errors()) messages.add("  " + e);
                    }
                }
                open(sim, registries, messages);
                for (int i = 0; i < loaded.size(); i++) {
                    RobotCode.Build build = loaded.get(i).build();
                    if (build == null || (i > 0 && partnerSame)) continue;
                    String whose = i == 0 ? "" : "Robot 2: ";
                    List<String> leftOut = build.messages().stream().dropWhile(m -> !m.startsWith("Left out")).toList();
                    if (build.ok() && !leftOut.isEmpty()) {
                        JTextArea text = new JTextArea(String.join("\n", leftOut), Math.min(16, leftOut.size() + 1), 90);
                        text.setEditable(false);
                        text.setFont(org.ftctestbed.viewer.platform.Os.mono(java.awt.Font.PLAIN, 12));
                        JOptionPane.showMessageDialog(null, new JScrollPane(text), whose + "Some team code was left out", JOptionPane.WARNING_MESSAGE);
                    }
                    if (!build.ok()) {
                        JTextArea text = new JTextArea(String.join("\n", build.errors()), 16, 90);
                        text.setEditable(false);
                        text.setFont(org.ftctestbed.viewer.platform.Os.mono(java.awt.Font.PLAIN, 12));
                        JOptionPane.showMessageDialog(null, new JScrollPane(text), whose + "Team code didn't compile", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        }.execute();
    }

    /** One robot's OpModes, the build they came from (null for the project's TeamCode) and its hardware-name check. */
    private record Loaded(OpModeRegistry.Result opModes, RobotCode.Build build, List<String> check) {}

    private static Loaded build(RobotCode code, RobotConfig cfg) {
        RobotCode.Build build;
        try {
            build = code.build();
        } catch (Exception e) {
            return new Loaded(noOpModes(), failedBuild(e), List.of());
        }
        List<String> check;
        try {
            check = HardwareScanner.missing(HardwareScanner.scan(code.sourceRoots()), cfg);
        } catch (Exception e) {
            check = List.of("Could not scan the code for hardware names: " + e.getMessage());
        }
        return new Loaded(build.opModes(), build, check);
    }

    /** The project's TeamCode, compiled by the simulator (no hardware-name check: it holds several robots' code). */
    private static Loaded buildProject(RobotCode code) {
        try {
            RobotCode.Build build = code.build();
            return new Loaded(build.opModes(), build, List.of());
        } catch (Exception e) {
            return new Loaded(noOpModes(), failedBuild(e), List.of());
        }
    }

    /** A partner's own copy of the project's TeamCode, from the classes the simulator compiled. */
    private static Loaded copyOfProjectCode(RobotCode projectCode) {
        if (projectCode == null) return copyOfProjectCode();
        try {
            RobotCode.Build build = projectCode.build(); // up to date already when the first robot runs it too
            if (!build.ok()) return new Loaded(noOpModes(), build, List.of());
            return new Loaded(RobotCode.projectTeamCodeCopy(projectCode.classesDir()), null, List.of());
        } catch (Exception e) {
            return new Loaded(noOpModes(), failedBuild(e), List.of());
        }
    }

    /** The project's TeamCode for the partner, in its own class loader. */
    private static Loaded copyOfProjectCode() {
        try {
            return new Loaded(RobotCode.projectTeamCodeCopy(), null, List.of());
        } catch (Exception e) {
            return new Loaded(noOpModes(), failedBuild(e), List.of());
        }
    }

    private static OpModeRegistry.Result noOpModes() {
        return new OpModeRegistry.Result(new ArrayList<>(), new ArrayList<>());
    }

    private static RobotCode.Build failedBuild(Exception e) {
        return new RobotCode.Build(false, false, 0, List.of(String.valueOf(e)), List.of(), noOpModes(),
                ViewerMain.class.getClassLoader(), null);
    }

    private static void open(Simulation sim, List<OpModeRegistry.Result> registries, List<String> messages) {
        SimRunner simRunner = new SimRunner(sim);
        List<OpModeRunner> runners = new ArrayList<>();
        for (SimRobot r : sim.robots()) runners.add(new OpModeRunner(r, simRunner));
        MatchController match = new MatchController(sim, runners);
        current = simRunner;
        startWebView(messages);
        messages.addAll(AgentService.start(projectDir));
        // The web page and the 2D view show the first robot.
        if (webView != null) webView.show(new SimWebView.Source(sim, simRunner, runners.get(0), match));

        MainWindow window = new MainWindow(sim, simRunner, runners, match, registries, messages, projectDir,
                robotFile, partnerFile, fieldFile, (robot2, partner2, field2) -> {
                    robotFile = robot2;
                    partnerFile = partner2;
                    fieldFile = field2;
                    SwingUtilities.invokeLater(ViewerMain::launch);
                });
        window.setVisible(true);
        AgentService.setLive(window.agentSession());
        simRunner.start();
        window.runDebugOptions();
        window.resumeAfterCodeReload();
    }

    private static javax.swing.JDialog progressDialog(String message) {
        javax.swing.JDialog d = new javax.swing.JDialog((java.awt.Frame) null, AppIcon.NAME, false);
        javax.swing.JProgressBar bar = new javax.swing.JProgressBar();
        bar.setIndeterminate(true);
        javax.swing.JPanel p = new javax.swing.JPanel(new java.awt.BorderLayout(0, 10));
        p.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 20, 16, 20));
        p.add(new javax.swing.JLabel(message), java.awt.BorderLayout.NORTH);
        p.add(bar, java.awt.BorderLayout.CENTER);
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(null);
        d.setVisible(true);
        return d;
    }
}
