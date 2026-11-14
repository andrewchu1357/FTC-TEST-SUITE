package org.ftctestbed.viewer;

import com.formdev.flatlaf.FlatClientProperties;
import com.qualcomm.robotcore.hardware.Gamepad;

import org.ftctestbed.shim.SimHooks;
import org.ftctestbed.sim.PoseLogger;
import org.ftctestbed.sim.SimRunner;
import org.ftctestbed.sim.Simulation;
import org.ftctestbed.viewer.platform.Os;
import org.ftctestbed.sim.Snapshot;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.game.MatchController;
import org.ftctestbed.sim.game.MatchState;
import org.ftctestbed.sim.game.SeasonSnapshot;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRegistry;
import org.ftctestbed.sim.opmode.OpModeRunner;
import org.ftctestbed.viewer.ui.AcrylicMenu;
import org.ftctestbed.viewer.ui.AppShell;
import org.ftctestbed.viewer.ui.Appearance;
import org.ftctestbed.viewer.ui.AppearancePanel;
import org.ftctestbed.viewer.ui.CommandBar;
import org.ftctestbed.viewer.ui.Contrast;
import org.ftctestbed.viewer.ui.Fluent;
import org.ftctestbed.viewer.ui.FluentButton;
import org.ftctestbed.viewer.ui.Icons;
import org.ftctestbed.viewer.ui.NavRail;
import org.ftctestbed.viewer.ui.SelectorBar;
import org.ftctestbed.viewer.ui.SplitColumn;
import org.ftctestbed.viewer.ui.StatusPill;
import org.ftctestbed.viewer.ui.Surface;
import org.ftctestbed.viewer.ui.Theme;
import org.ftctestbed.viewer.ui.TitleBar;
import org.ftctestbed.viewer.ui.ToggleSwitch;
import org.ftctestbed.viewer.ui.Ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The simulator window, laid out as a Windows 11 (Fluent) app: the title bar with the status,
 * the navigation rail (3D field, 2D field, Code, and Setup at the bottom), and the content: the
 * command bar (camera, pause / step / speed, reset, the Driver Station pane) over the page, with
 * the Driver Station docked at the right: run an OpMode, a full match or the sandbox, the
 * telemetry, and the score, hardware, console and controls. Nothing floats over the 3D view, so
 * the window stays light to draw. Every action is also in the menu bar, most with a shortcut.
 */
public final class MainWindow extends JFrame {
    private static final Font MONO = Os.mono(Font.PLAIN, 12);
    private static final String[] VIEWS = {"3D", "2D", "Code"};
    /** The navigation rail's pages (views, plus Setup) and their titles in the command bar. */
    private static final String[] PAGES = {"3D", "2D", "Code", "Setup"};
    private static final String[] PAGE_TITLES = {"3D field", "2D field", "Code", "Setup"};
    private static final String[] VIEWS_2D = {"Driver's view", "Top view", "Follow robot"};
    private static final double[] SPEEDS = {0.25, 0.5, 1, 2, 4};

    private final Simulation sim;
    private final SimRunner simRunner;
    private final OpModeRunner runner;
    // With a partner robot: every robot's runner (the first robot's first), robot 2's, its config file.
    private final List<OpModeRunner> runners;
    private final OpModeRunner partner;
    private final Path partnerFile;
    private final List<OpModeRegistry.Result> registries;
    private RobotPanel partnerPanel;
    private DriverStation partnerStation;
    private volatile int keyboardRobot = 1;
    private final JComboBox<Object> partnerAutoBox = new JComboBox<>();
    private final JComboBox<Object> partnerTeleBox = new JComboBox<>();
    private final JCheckBox partnerBox = new ToggleSwitch("Partner robot", false);
    private SelectorBar keyboardSwitch;
    private final Path projectDir;
    private final Runnable reload;

    // The field views, filling the window.
    private final FieldPanel field;
    private final org.ftctestbed.viewer.three.View3D view3d;
    private final CardLayout views = new CardLayout();
    private final JPanel viewCards = new JPanel(views);
    private final JPanel codeWrapper = new JPanel(new BorderLayout());
    private boolean show3d = true;
    /** "3D", "2D" or "Code": what the window shows. */
    private String currentView = "3D";
    private String lastFieldView = "3D";
    private CodePanel codePanel;
    private String cameraPreset = "Red driver";
    private int view2d;
    private boolean crowd, cameraView, showTrail = true;
    private final boolean robotHasCamera;

    // The window: title bar, navigation rail, command bar.
    private AppShell shell;
    private TitleBar titleBar;
    private NavRail nav;
    private CommandBar commandBar;
    private FluentButton viewMenuButton;
    private final StatusPill statusPill = new StatusPill();
    private FluentButton pauseButton, stepButton, speedButton, resetButton, panelButton;
    /** The page to go back to from Setup, and whether the Driver Station showed there. */
    private String lastView = "3D";
    private boolean paneBeforeSetup = true;

    // The Driver Station pane.
    private JComponent pane;
    private SelectorBar runModes;
    private final CardLayout runCards = new CardLayout() {
        @Override
        public Dimension preferredLayoutSize(java.awt.Container parent) {
            // As tall as the page showing, not the tallest page.
            for (Component c : parent.getComponents()) {
                if (c.isVisible()) return c.getPreferredSize();
            }
            return super.preferredLayoutSize(parent);
        }
    };
    private final JPanel runDeck = new JPanel(runCards);
    private SelectorBar sections;
    private final CardLayout sectionCards = new CardLayout();
    private final JPanel sectionDeck = new JPanel(sectionCards);
    private final List<String> sectionNames = new ArrayList<>();
    private SelectorBar setupPages;
    private final CardLayout setupCards = new CardLayout();
    private final JPanel setupDeck = new JPanel(setupCards);
    private final List<String> setupNames = new ArrayList<>();
    private SplitColumn split;
    private ModelPanel modelPanel;
    // Pages inside a section (Score: Points / Shots; Hardware: Motors / Mechanisms).
    private final List<Pages> pageGroups = new ArrayList<>();

    private final KeyboardGamepad keyboard;
    private DriverStation driverStation;
    private final PhysicalGamepads gamepads = PhysicalGamepads.shared();
    private GamepadView gamepadView;
    private final JComboBox<OpModeInfo> opModeBox = new JComboBox<>();
    private final FluentButton runButton = new FluentButton("Init", Icons.POWER, FluentButton.Style.ACCENT, FluentButton.Size.LARGE);
    private final FluentButton stopButton = new FluentButton(null, Icons.STOP, FluentButton.Style.STANDARD, FluentButton.Size.LARGE);
    private final JLabel timerLabel = new JLabel("0:00");
    private final JLabel loopLabel = new JLabel(" ");
    private final JLabel stateLabel = new JLabel("Pick an OpMode, then Init");
    private final JTextArea errorArea = Ui.text(MONO);
    private JComponent errorBanner;
    private final JTextArea telemetryArea = Ui.text(MONO.deriveFont(13f), "Telemetry from the OpMode shows here.");
    private final JTextArea consoleArea = Ui.text(MONO);
    private final MotorTableModel motorModel = new MotorTableModel();
    private final MechanismView mechanismView = new MechanismView();
    private final JCheckBox recordBox = new ToggleSwitch("Record the robot's pose", false);
    private final JCheckBox autoTimerBox = new ToggleSwitch("Stop an AUTO after 30 seconds", true);
    private final JCheckBox soundsBox = new ToggleSwitch("Match sounds", true);
    private final JCheckBox trailBox = new ToggleSwitch("Draw the robot's path in 2D", true);
    private final MatchSounds sounds = new MatchSounds();
    private final java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(MainWindow.class);
    // Sandbox: the driver TeleOp running with no match, no clock and no AUTO, to drive freely.
    private final FluentButton sandboxButton = new FluentButton("Drive", Icons.GAMEPAD, FluentButton.Style.ACCENT, FluentButton.Size.LARGE, FluentButton.Tint.GO);
    private volatile boolean sandbox;
    private final MatchController match;
    private final JComboBox<Object> matchAutoBox = new JComboBox<>();
    private final JComboBox<Object> matchTeleBox = new JComboBox<>();
    private final FluentButton matchButton = new FluentButton("Start match", Icons.FLAG, FluentButton.Style.ACCENT, FluentButton.Size.LARGE);
    private boolean matchShownRunning;
    private final ScoreBoard scoreBoard = new ScoreBoard();
    private final JTextArea notesArea = Ui.text(MONO.deriveFont(11f));
    private final ShotView shotView = new ShotView();
    private final JComboBox<FieldConfig.StartPosition> startBox = new JComboBox<>();
    private final JComboBox<Path> robotBox = new JComboBox<>();
    private final JComboBox<Path> fieldBox = new JComboBox<>();
    private final Path robotFile, fieldFile;
    private final Relaunch relaunch;
    private List<String> lastNotes = List.of();

    private final Timer uiTimer, powerTimer;
    private volatile boolean minimized, hidden;
    private final java.awt.desktop.AppHiddenListener appHidden = new java.awt.desktop.AppHiddenListener() {
        @Override
        public void appHidden(java.awt.desktop.AppHiddenEvent e) {
            hidden = true;
            updateDrawing();
        }

        @Override
        public void appUnhidden(java.awt.desktop.AppHiddenEvent e) {
            hidden = false;
            updateDrawing();
        }
    };
    private long lastFrame = System.nanoTime();
    private int ticks;
    private List<String> lastTelemetry = List.of();
    private com.acmerobotics.dashboard.telemetry.TelemetryPacket lastPacket;
    private List<String> lastPanels;
    private OpModeRunner.State lastState = OpModeRunner.State.IDLE;
    private PoseLogger logger;
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private final Runnable themeListener = this::restyle;
    /** This window's simulation as AI agents see it (the agent API's "live" session). */
    private final LiveSession agentSession;

    /** Opens the simulator again with these configs (partner: null for no partner robot). */
    interface Relaunch {
        void to(Path robot, Path partner, Path field);
    }

    /**
     * runners and registries: one per robot on the field, the first robot's first (a second one
     * is the partner robot, driven from the Robot 2 section). partnerFile: the partner's config, or null.
     */
    public MainWindow(Simulation sim, SimRunner simRunner, List<OpModeRunner> runners, MatchController match,
                      List<OpModeRegistry.Result> registries, List<String> startupMessages, Path projectDir,
                      Path robotFile, Path partnerFile, Path fieldFile, Relaunch relaunch) {
        super(AppIcon.NAME + " — " + sim.fieldConfig.name());
        if (AppIcon.image() != null) setIconImage(AppIcon.image());
        this.sim = sim;
        this.simRunner = simRunner;
        this.runners = List.copyOf(runners);
        this.runner = runners.get(0);
        this.partner = runners.size() > 1 ? runners.get(1) : null;
        this.registries = List.copyOf(registries);
        this.partnerFile = partner != null ? partnerFile : null;
        OpModeRegistry.Result opModes = registries.get(0);
        this.match = match;
        this.projectDir = projectDir;
        this.robotFile = robotFile;
        this.fieldFile = fieldFile;
        this.relaunch = relaunch;
        this.reload = () -> relaunch.to(robotFile, this.partnerFile, fieldFile);
        this.field = new FieldPanel(sim, runner);
        this.view3d = new org.ftctestbed.viewer.three.View3D(sim);
        this.keyboard = new KeyboardGamepad(() -> {
            OpModeRunner.State st = keyboardRunner().state();
            // Typing code in the Code view never drives the robot.
            return isActive() && !"Code".equals(currentView) && (st == OpModeRunner.State.INIT || st == OpModeRunner.State.RUNNING);
        });
        var robot1 = sim.robots().get(0).config;
        robotHasCamera = !(robot1.limelights().isEmpty() && robot1.webcams().isEmpty());
        crowd = prefs.getBoolean("crowd", true);
        cameraView = prefs.getBoolean("cameraView", true);
        view3d.setStadiumVisible(crowd);
        view3d.setCameraViewVisible(cameraView);

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JRootPane rp = getRootPane();
        // The app draws its own title bar (on Mica, as Windows apps do); the window buttons sit in it.
        rp.putClientProperty("apple.awt.fullWindowContent", true);
        rp.putClientProperty("apple.awt.transparentTitleBar", true);
        rp.putClientProperty("apple.awt.windowTitleVisible", false);
        rp.putClientProperty(FlatClientProperties.MACOS_WINDOW_BUTTONS_SPACING, FlatClientProperties.MACOS_WINDOW_BUTTONS_SPACING_MEDIUM);
        Appearance.decorate(rp);

        buildViews();
        String what = sim.fieldConfig.name() + " · " + configLabel(robotFile) + (this.partnerFile != null ? " + Robot 2" : "");
        titleBar = new TitleBar(AppIcon.image(), AppIcon.NAME, what);
        JMenuBar menuBar = buildMenuBar();
        if (Os.WINDOWS) {
            // Windows: one title bar, of the system's kind, instead of the app's own under it: the
            // icon, the menus, the title and the status, then minimize, maximize and close. Windows
            // itself then moves and sizes the window (dragging to an edge, Win+arrows, the snap
            // layouts on the maximize button), which a bar the app draws and drags can't do.
            titleBar.setVisible(false);
            setTitle(AppIcon.NAME + " — " + what);
            rp.putClientProperty(FlatClientProperties.TITLE_BAR_HEIGHT, 36);
            JPanel status = new JPanel(new java.awt.GridBagLayout()) {
                @Override
                public Dimension getMaximumSize() {
                    return getPreferredSize(); // the space left over goes to the title, not to this
                }
            };
            status.setOpaque(false);
            status.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 10));
            status.add(statusPill);
            menuBar.add(javax.swing.Box.createHorizontalGlue());
            menuBar.add(status);
        } else {
            titleBar.setRight(statusPill);
        }
        nav = buildNav();
        commandBar = buildCommandBar();
        pane = buildPane();
        shell = new AppShell(titleBar, nav, commandBar, viewCards, pane, prefs.getInt("inspectorWidth", 400));
        shell.onPaneResized(w -> prefs.putInt("inspectorWidth", w));
        if (!prefs.getBoolean("inspector", true)) shell.setPaneVisible(false);
        setContentPane(shell);
        setJMenuBar(menuBar);
        setMinimumSize(new Dimension(1000, 640));
        restoreBounds();

        for (OpModeInfo info : opModes.opModes()) opModeBox.addItem(info);
        matchAutoBox.addItem("(no AUTO)");
        matchTeleBox.addItem("(no TELEOP)");
        for (OpModeInfo info : opModes.opModes()) {
            if (info.autonomous()) matchAutoBox.addItem(info);
            else if (info.teleOp()) matchTeleBox.addItem(info);
        }
        if (matchAutoBox.getItemCount() > 1) matchAutoBox.setSelectedIndex(1);
        if (matchTeleBox.getItemCount() > 1) matchTeleBox.setSelectedIndex(1);
        selectMatchTeleOp(matchAutoBox, matchTeleBox);
        if (partner != null) {
            // The partner sits still in AUTO unless one is picked (most AUTOs are written for one start).
            partnerAutoBox.addItem("(no AUTO)");
            partnerTeleBox.addItem("(no TELEOP)");
            for (OpModeInfo info : registries.get(1).opModes()) {
                if (info.autonomous()) partnerAutoBox.addItem(info);
                else if (info.teleOp()) partnerTeleBox.addItem(info);
            }
            // The same TeleOp as robot 1 if the partner has it (the same robot, or the same code).
            if (partnerTeleBox.getItemCount() > 1) partnerTeleBox.setSelectedIndex(1);
            selectMatchTeleOp(partnerAutoBox, partnerTeleBox);
            if (matchTeleBox.getSelectedItem() instanceof OpModeInfo t) {
                for (int k = 0; k < partnerTeleBox.getItemCount(); k++) {
                    if (partnerTeleBox.getItemAt(k) instanceof OpModeInfo i && i.name().equals(t.name())) partnerTeleBox.setSelectedIndex(k);
                }
            }
        }
        for (FieldConfig.StartPosition sp : sim.fieldConfig.startPositions()) startBox.addItem(sp);
        startBox.setSelectedItem(sim.startPosition());
        // Prefer the OpModes written for the chosen alliance.
        selectForAlliance();
        opModeBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                String text = value instanceof OpModeInfo i ? i.label() : "(no OpModes found)";
                return super.getListCellRendererComponent(list, text, index, sel, focus);
            }
        });

        installConsole();
        List<Gamepad[]> pads = new ArrayList<>();
        for (OpModeRunner r : runners) pads.add(new Gamepad[]{r.gamepad1, r.gamepad2});
        gamepads.attach(pads, this::log);
        gamepads.setKeyboardSlot(0);
        driverStation = new DriverStation(sim, runner, keyboard, () -> keyboardRobot == 1, gamepads, 0,
                sim.robotConfig.timing().gamepadDelayMs(), g -> {});
        if (partner != null) {
            partnerStation = new DriverStation(sim, partner, keyboard, () -> keyboardRobot == 2, gamepads, 2,
                    sim.robot(2).config.timing().gamepadDelayMs(), g -> {});
            partner.addListener(() -> SwingUtilities.invokeLater(this::checkSandboxEnded));
        }
        for (String m : startupMessages) log(m);
        log("Found " + opModes.opModes().size() + " OpModes.");
        for (String w : opModes.warnings()) log("WARNING: " + w);
        if (partner != null) {
            log("Partner robot: " + configLabel(partnerFile) + " at " + sim.robot(2).startPosition().name()
                    + ", " + registries.get(1).opModes().size() + " OpModes. Drive it from the Robot 2 section, with the second controller.");
            for (String w : registries.get(1).warnings()) log("WARNING (Robot 2): " + w);
            loadPartnerModel();
        }
        for (String w : sim.runtimeWarnings()) log("CONFIG WARNING: " + w);
        List<Path> files = new ArrayList<>(List.of(robotFile));
        if (this.partnerFile != null) files.add(this.partnerFile);
        agentSession = new LiveSession(sim, simRunner, runners, match, registries, files, fieldFile, startupMessages, new LiveSession.Window() {
            @Override public void show(String view) { if (view.equals("Setup")) showSetup(true); else showView(view); }
            @Override public void camera(String preset) { setCameraPreset(preset); }
            @Override public String[] cameras() { return view3d.presets(); }
            @Override public void setSpeed(double speed) { MainWindow.this.setSpeed(speed); }
            @Override public void setPaused(boolean paused) {
                simRunner.setPaused(paused);
                updatePauseButton();
            }
            @Override public void reload() { reloadRobot(); }
            @Override public String currentView() { return currentView; }
            @Override public java.awt.image.BufferedImage screenshot() { return windowImage(); }
        });

        runButton.addActionListener(e -> RunButtons.press(runner.state(), this::initOpMode, this::startOpMode, runner::stop));
        stopButton.addActionListener(e -> runner.stop());
        runner.addListener(() -> SwingUtilities.invokeLater(this::onStateChange));

        // macOS's power state (battery, Low Power Mode) for the Automatic frame rate, checked now and then.
        powerTimer = new Timer(30_000, e -> Appearance.refreshSystem());
        powerTimer.start();
        // Nothing to draw while the window is minimized or the app hidden (the simulation runs on).
        addWindowStateListener(e -> {
            minimized = (e.getNewState() & java.awt.Frame.ICONIFIED) != 0;
            updateDrawing();
        });
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_EVENT_HIDDEN)) {
            java.awt.Desktop.getDesktop().addAppEventListener(appHidden);
        }
        uiTimer = new Timer(16, e -> tick());
        uiTimer.start();

        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                if ("Code".equals(currentView)) codePanel.reloadChangedFiles(); // edited in another program meanwhile
                Appearance.refreshSystem(); // dark mode or accessibility settings changed in macOS?
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                uiTimer.stop();
                powerTimer.stop();
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_EVENT_HIDDEN)) {
                    java.awt.Desktop.getDesktop().removeAppEventListener(appHidden);
                }
                Appearance.removeListener(themeListener);
                for (OpModeRunner r : runners) r.stop();
                AgentService.clearLive(agentSession);
                keyboard.close();
                driverStation.close();
                if (partnerStation != null) partnerStation.close();
                view3d.dispose();
                closeLogger();
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        });
        Appearance.addListener(themeListener);
        restyle();
        onStateChange();
        updateRunButtons();
        // Start with the camera behind our alliance wall.
        setCameraPreset("blue".equals(sim.alliance()) ? "Blue driver" : "Red driver");
    }

    // ------------------------------------------------------------------ the field views

    private void buildViews() {
        viewCards.add(view3d, "3D");
        viewCards.add(field, "2D");
        codePanel = new CodePanel(CodeWorkspace.forRobots(projectDir, robotFile, sim.robotConfig, partnerFile,
                sim.robots().size() > 1 ? sim.robot(2).config : null), codeHost());
        codeWrapper.add(codePanel, BorderLayout.CENTER);
        viewCards.add(codeWrapper, "Code");
        viewCards.add(buildSetupView(), "Setup");
    }

    /** Shows a page: a view of the field, the code, or Setup (which has the whole content to itself). */
    private void setView(String mode) {
        boolean threeD = mode.equals("3D"), setup = mode.equals("Setup"), wasSetup = currentView.equals("Setup");
        if (setup && !wasSetup) paneBeforeSetup = shell.paneVisible();
        currentView = mode;
        if (!setup) lastView = mode;
        if (!mode.equals("Code") && !setup) lastFieldView = mode;
        show3d = threeD;
        views.show(viewCards, mode);
        updateDrawing();
        if (setup) shell.setPaneVisible(false);
        else if (wasSetup) shell.setPaneVisible(paneBeforeSetup);
        commandBar.setTitle(PAGE_TITLES[List.of(PAGES).indexOf(mode)]);
        viewMenuButton.setVisible(mode.equals("3D") || mode.equals("2D"));
        updateViewMenuButton();
        if (mode.equals("Code")) {
            codePanel.reloadChangedFiles();
            codePanel.openSomethingIfEmpty();
        }
        commandBar.revalidate();
        commandBar.repaint();
    }

    /** Shows a page as if it was picked in the navigation rail. */
    private void showView(String mode) {
        int i = List.of(PAGES).indexOf(mode);
        if (i >= 0) nav.select(i);
    }

    private void updateViewMenuButton() {
        String text = show3d ? cameraPreset : VIEWS_2D[view2d];
        viewMenuButton.set(text, show3d ? Icons.CAMERA : Icons.MAP);
        commandBar.revalidate();
    }

    private void setCameraPreset(String name) {
        cameraPreset = name;
        view3d.preset(name);
        if (viewMenuButton != null) updateViewMenuButton();
    }

    private void setView2d(int index) {
        view2d = index;
        field.setDriverView(index == 0);
        field.setFollow(index == 2);
        updateViewMenuButton();
    }

    private void setCrowd(boolean on) {
        crowd = on;
        view3d.setStadiumVisible(on);
        prefs.putBoolean("crowd", on);
    }

    private void setCameraView(boolean on) {
        cameraView = on;
        view3d.setCameraViewVisible(on);
        prefs.putBoolean("cameraView", on);
    }

    private void setShowTrail(boolean on) {
        showTrail = on;
        trailBox.setSelected(on);
        field.setShowTrail(on);
    }

    /** The command bar's camera / view menu: 3D camera positions, or the 2D view, and what's drawn. */
    private JPopupMenu viewOptionsMenu() {
        JPopupMenu m = new AcrylicMenu();
        if (show3d) {
            ButtonGroup g = new ButtonGroup();
            for (String p : view3d.presets()) {
                JRadioButtonMenuItem item = new JRadioButtonMenuItem(p, p.equals(cameraPreset));
                item.addActionListener(e -> setCameraPreset(p));
                g.add(item);
                m.add(item);
            }
            m.addSeparator();
            JCheckBoxMenuItem c = new JCheckBoxMenuItem("Crowd and stands", crowd);
            c.addActionListener(e -> setCrowd(c.isSelected()));
            m.add(c);
            if (robotHasCamera) {
                JCheckBoxMenuItem v = new JCheckBoxMenuItem("What the robot's camera sees", cameraView);
                v.addActionListener(e -> setCameraView(v.isSelected()));
                m.add(v);
            }
        } else {
            ButtonGroup g = new ButtonGroup();
            for (int i = 0; i < VIEWS_2D.length; i++) {
                int k = i;
                JRadioButtonMenuItem item = new JRadioButtonMenuItem(VIEWS_2D[i], i == view2d);
                item.addActionListener(e -> setView2d(k));
                g.add(item);
                m.add(item);
            }
            m.addSeparator();
            JCheckBoxMenuItem t = new JCheckBoxMenuItem("Draw the robot's path", showTrail);
            t.addActionListener(e -> setShowTrail(t.isSelected()));
            m.add(t);
            JMenuItem clear = new JMenuItem("Clear the path");
            clear.addActionListener(e -> field.clearTrail());
            m.add(clear);
        }
        return m;
    }

    /**
     * What the field views draw: the 3D view only while it shows, nothing while the window is
     * minimized or the app hidden, and 30 times a second instead of 60 when the frame rate is
     * efficient (chosen, or Automatic on battery or in Low Power Mode).
     */
    private void updateDrawing() {
        view3d.setActive(show3d && !minimized && !hidden);
        boolean efficient = Appearance.efficient();
        view3d.setEfficient(efficient);
        field.setEfficient(efficient);
    }

    // ------------------------------------------------------------------ navigation and command bar

    private NavRail buildNav() {
        NavRail n = new NavRail();
        n.addItem("3D field", Icons.CUBE, Os.text("The field in 3D (⌘1)"), false);
        n.addItem("2D field", Icons.MAP, Os.text("The field from above (⌘2)"), false);
        n.addItem("Code", Icons.CODE, Os.text("Edit the robot's code and run it (⌘3; ⌘E switches back)"), false);
        n.addItem("Setup", Icons.GEAR, Os.text("Setup: robot, field, parts, 3D model and appearance (⌘,)"), true);
        n.addActionListener(e -> setView(PAGES[n.selectedIndex()]));
        return n;
    }

    private CommandBar buildCommandBar() {
        CommandBar bar = new CommandBar();
        viewMenuButton = Ui.menuButton(cameraPreset, Icons.CAMERA, "Camera position and what's shown", this::viewOptionsMenu);
        bar.addLeft(CommandBar.group(viewMenuButton));
        pauseButton = command("Pause", Icons.PAUSE, Os.text("Pause the simulation (⌘P)"), e -> togglePause());
        stepButton = command("Step", Icons.STEP, Os.text("Pause and advance 10 physics steps (⌥⌘P)"), e -> step());
        speedButton = Ui.menuButton("1×", Icons.SPEED, "Simulation speed", this::speedMenu);
        resetButton = command("Reset field", Icons.RESET, Os.text("Reset the field: robots and game pieces back to their starts (⇧⌘R)"), e -> resetField());
        panelButton = command("Driver Station", Icons.SIDEBAR, Os.text("Show or hide the Driver Station (⌥⌘I)"), e -> togglePanel());
        bar.addRight(CommandBar.group(pauseButton, stepButton, speedButton), CommandBar.group(resetButton), CommandBar.group(panelButton));
        bar.setTitle(PAGE_TITLES[0]);
        return bar;
    }

    /** A command bar button: icon and label on the bar's glass, a capsule under the pointer. */
    private static FluentButton command(String label, Icons glyph, String toolTip, java.awt.event.ActionListener action) {
        FluentButton b = new FluentButton(label, glyph, FluentButton.Style.GLASS, FluentButton.Size.REGULAR);
        b.setToolTipText(toolTip);
        b.addActionListener(action);
        return b;
    }

    private JPopupMenu speedMenu() {
        JPopupMenu m = new AcrylicMenu();
        ButtonGroup g = new ButtonGroup();
        for (double s : SPEEDS) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(speedLabel(s) + (s == 1 ? "  (real time)" : ""), s == simRunner.speed());
            item.addActionListener(e -> setSpeed(s));
            g.add(item);
            m.add(item);
        }
        return m;
    }

    private static String speedLabel(double s) {
        return (s == Math.rint(s) ? String.valueOf((int) s) : String.valueOf(s)) + "×";
    }

    private void setSpeed(double s) {
        simRunner.setSpeed(s);
        speedButton.set(speedLabel(s), Icons.SPEED);
        commandBar.revalidate();
        if (s != 1) {
            log("Note: at " + speedLabel(s) + " speed, robot code that calls Thread.sleep() or System.nanoTime() directly "
                    + "runs on wall-clock time. SDK timing (ElapsedTime, sleep(), getRuntime()) follows the simulation.");
        }
    }

    private void togglePause() {
        simRunner.setPaused(!simRunner.isPaused());
        updatePauseButton();
    }

    private void step() {
        if (!simRunner.isPaused()) simRunner.setPaused(true);
        updatePauseButton();
        simRunner.stepManually(10);
    }

    private void updatePauseButton() {
        boolean paused = simRunner.isPaused();
        pauseButton.set(paused ? "Resume" : "Pause", paused ? Icons.PLAY : Icons.PAUSE);
        commandBar.revalidate();
        pauseButton.setToolTipText(paused ? Os.text("Resume the simulation (⌘P)") : Os.text("Pause the simulation (⌘P)"));
    }

    private void resetField() {
        boolean busy = anyRobotBusy() || match.running();
        if (busy && !sandbox) {
            log("Stop the OpMode or match before resetting the field.");
            return;
        }
        if (busy) { // sandbox: the TeleOp keeps running
            sim.resetFieldWhileDriving();
            field.clearTrail();
            return;
        }
        sim.match().enter(MatchState.Phase.PRACTICE, sim.clock.seconds());
        sim.resetField();
        field.clearTrail();
    }

    /** Shows or hides the Driver Station pane (from Setup, it goes back to the field with the pane). */
    private void togglePanel() {
        boolean show = "Setup".equals(currentView) || !shell.paneVisible();
        if ("Setup".equals(currentView)) {
            paneBeforeSetup = true;
            showSetup(false);
        }
        shell.setPaneVisible(show);
        prefs.putBoolean("inspector", show);
    }

    // ------------------------------------------------------------------ the Driver Station pane

    private JComponent buildPane() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        p.getAccessibleContext().setAccessibleName("Driver Station");
        p.add(buildRunView(), BorderLayout.CENTER);
        return p;
    }

    private JComponent buildRunView() {
        JPanel v = new JPanel(new BorderLayout(0, 10));
        v.setOpaque(false);
        v.add(buildRunCard(), BorderLayout.NORTH);
        Surface telemetry = Ui.card(partner != null ? "Telemetry · Robot 1" : "Telemetry", Ui.scroll(telemetryArea));
        split = new SplitColumn(telemetry, buildSections(), prefs.getDouble("telemetryShare", 0.36), 90, 170);
        v.add(split, BorderLayout.CENTER);
        return v;
    }

    /** OpMode / Match / Sandbox, the clock, the controls for the chosen one, and where the robot starts. */
    private JComponent buildRunCard() {
        Surface card = new Surface(Fluent.Material.CARD, Fluent.OVERLAY_ARC, 12, new Ui.StackLayout(10));
        runModes = new SelectorBar(FluentButton.Size.SMALL, true);
        runModes.add("OpMode", Icons.ROBOT, "Run one OpMode: pick it, Init, Start, Stop");
        runModes.add("Match", Icons.FLAG, "A full match: AUTO 0:30, 0:08 to pick up controllers, TELEOP 2:00");
        runModes.add("Sandbox", Icons.GAMEPAD, "Drive a TELEOP freely: no AUTO, no match clock, no time limit");
        runModes.getAccessibleContext().setAccessibleName("What to run");
        String[] modes = {"opmode", "match", "sandbox"};
        runModes.addActionListener(e -> {
            runCards.show(runDeck, modes[runModes.selectedIndex()]);
            runDeck.revalidate();
            onStateChange(); // the hint under the clock is about the page showing
        });

        // The clock and what's happening, as on the Driver Station.
        JPanel clock = new JPanel(new BorderLayout(12, 0));
        clock.setOpaque(false);
        timerLabel.getAccessibleContext().setAccessibleName("Clock");
        clock.add(timerLabel, BorderLayout.WEST);
        JPanel words = new JPanel(new GridLayout(0, 1, 0, 0));
        words.setOpaque(false);
        words.add(stateLabel);
        loopLabel.setToolTipText("Average time per OpMode loop. Hardware calls take time like on a real hub "
                + "(set in \"hubTiming\" in robot.jsonc); compare with your robot's loop time.");
        words.add(loopLabel);
        clock.add(words, BorderLayout.CENTER);

        runDeck.setOpaque(false);
        runDeck.add(buildOpModePage(), "opmode");
        runDeck.add(buildMatchPage(), "match");
        runDeck.add(buildSandboxPage(), "sandbox");

        errorBanner = buildErrorBanner();
        errorBanner.setVisible(false);

        startBox.getAccessibleContext().setAccessibleName("Start position");
        startBox.addActionListener(e -> changeStart());
        JPanel footer;
        if (partner != null) {
            keyboardSwitch = new SelectorBar(FluentButton.Size.SMALL, true);
            keyboardSwitch.add("Robot 1", null, "The keyboard drives robot 1 (as its gamepad1)");
            keyboardSwitch.add("Robot 2", null, "The keyboard drives robot 2. Controllers: Options+Cross / Circle for robot 1, Options+Square / Triangle for robot 2");
            keyboardSwitch.addActionListener(e -> setKeyboardRobot(keyboardSwitch.selectedIndex() + 1));
            footer = Ui.form("Robot 1 starts at", startBox, "Keyboard drives", keyboardSwitch);
        } else {
            footer = Ui.form("Starts at", startBox);
        }

        for (JComponent c : new JComponent[]{runModes, clock, runDeck, errorBanner, footer}) card.add(c);
        return card;
    }

    private JComponent buildOpModePage() {
        JPanel p = column();
        if (partner != null) p.add(Ui.heading("Robot 1"));
        opModeBox.getAccessibleContext().setAccessibleName("OpMode");
        opModeBox.setToolTipText("The OpMode to run");
        p.add(opModeBox);
        p.add(Box.createVerticalStrut(8));
        JPanel buttons = new JPanel(new BorderLayout(6, 0));
        buttons.setOpaque(false);
        buttons.add(runButton, BorderLayout.CENTER);
        buttons.add(stopButton, BorderLayout.EAST);
        p.add(buttons);
        return p;
    }

    private JComponent buildMatchPage() {
        JPanel p = column();
        javax.swing.ListCellRenderer<Object> names = nameRenderer();
        for (JComboBox<Object> b : List.of(matchAutoBox, matchTeleBox, partnerAutoBox, partnerTeleBox)) b.setRenderer(names);
        matchAutoBox.getAccessibleContext().setAccessibleName("AUTO OpMode");
        matchTeleBox.getAccessibleContext().setAccessibleName("TELEOP OpMode");
        if (partner == null) {
            p.add(Ui.form("AUTO", matchAutoBox, "TELEOP", matchTeleBox));
        } else {
            // Each robot plays its own AUTO and TELEOP.
            partnerAutoBox.setToolTipText("Robot 2's AUTO. BuzzBot's AUTOs are written for the Wall start.");
            partnerAutoBox.getAccessibleContext().setAccessibleName("Robot 2 AUTO OpMode");
            partnerTeleBox.getAccessibleContext().setAccessibleName("Robot 2 TELEOP OpMode");
            p.add(Ui.form("Robot 1 AUTO", matchAutoBox, "Robot 1 TELEOP", matchTeleBox, "Robot 2 AUTO", partnerAutoBox, "Robot 2 TELEOP", partnerTeleBox));
        }
        p.add(Box.createVerticalStrut(8));
        matchButton.setToolTipText(Os.text("Start the match (⇧⌘M); it resets the field first"));
        matchButton.addActionListener(e -> {
            if (match.running()) abortMatch(); else startMatch();
        });
        p.add(matchButton);
        return p;
    }

    private JComponent buildSandboxPage() {
        JPanel p = column();
        JComboBox<Object> tele = new JComboBox<>(matchTeleBox.getModel()); // the TELEOP picked for the match
        tele.setRenderer(nameRenderer());
        tele.getAccessibleContext().setAccessibleName("Sandbox TELEOP OpMode");
        if (partner == null) {
            p.add(Ui.form("TELEOP", tele));
        } else {
            JComboBox<Object> tele2 = new JComboBox<>(partnerTeleBox.getModel());
            tele2.setRenderer(nameRenderer());
            tele2.getAccessibleContext().setAccessibleName("Robot 2 sandbox TELEOP OpMode");
            p.add(Ui.form("Robot 1 TELEOP", tele, "Robot 2 TELEOP", tele2));
        }
        p.add(Box.createVerticalStrut(8));
        sandboxButton.setToolTipText(Os.text("<html>Drive with this TELEOP: no AUTO, no match clock, no time limit (⇧⌘D).<br>")
                + Os.text("Reset field (⇧⌘R) puts everything back while you drive.") + (partner != null ? " Both robots drive." : "") + "</html>");
        sandboxButton.addActionListener(e -> {
            if (sandbox) leaveSandbox(); else enterSandbox();
        });
        p.add(sandboxButton);
        return p;
    }

    private static javax.swing.ListCellRenderer<Object> nameRenderer() {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                return super.getListCellRendererComponent(list, value instanceof OpModeInfo i ? i.name() : value, index, sel, focus);
            }
        };
    }

    /** What went wrong in the OpMode: red-tinted, with a warning sign, readable in every theme. */
    private JComponent buildErrorBanner() {
        errorArea.setLineWrap(true);
        errorArea.setWrapStyleWord(true);
        errorArea.getAccessibleContext().setAccessibleName("OpMode error");
        return Ui.alert(errorArea);
    }

    /** Score, Hardware, Console and Controls (and the partner's Driver Station). */
    private JComponent buildSections() {
        sections = new SelectorBar(FluentButton.Size.SMALL, true);
        sections.getAccessibleContext().setAccessibleName("Sections");
        sectionDeck.setOpaque(false);
        if (partner != null) {
            partnerPanel = new RobotPanel(sim, sim.robot(2), partner, simRunner, registries.get(1), gamepads,
                    () -> keyboardRobot == 2, () -> anyRobotBusy() || match.running(), autoTimerBox::isSelected,
                    configFiles(true), partnerFile, this::configLabel, p -> {
                        prefs.put("partnerRobot", p.toString());
                        match.abort();
                        dispose();
                        relaunch.to(robotFile, p, fieldFile);
                    }, this::log);
            Ui.clear(partnerPanel);
            // Scrolls when the panel is short, so its telemetry keeps its room above the gamepads.
            addSection("Robot 2", scrollDown(partnerPanel), "The partner robot's Driver Station");
        }
        JPanel scoreTab = new JPanel(new BorderLayout(0, 6));
        scoreTab.setOpaque(false);
        scoreTab.add(scrollDown(scoreBoard), BorderLayout.CENTER);
        JPanel scoreSouth = new JPanel(new BorderLayout(0, 6));
        scoreSouth.setOpaque(false);
        notesArea.setLineWrap(true);
        notesArea.setRows(3);
        notesArea.getAccessibleContext().setAccessibleName("Referee notes");
        JPanel notes = new JPanel(new BorderLayout(0, 2));
        notes.setOpaque(false);
        notes.add(Ui.heading("Referee notes"), BorderLayout.NORTH);
        notes.add(Ui.scroll(notesArea), BorderLayout.CENTER);
        scoreSouth.add(notes, BorderLayout.CENTER);
        JPanel hp = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        hp.setOpaque(false);
        hp.add(Ui.label("Human player enters NECTAR:"));
        for (String a : new String[]{"red", "blue"}) {
            FluentButton b = new FluentButton(a.equals("red") ? "Red" : "Blue", null, FluentButton.Style.STANDARD, FluentButton.Size.SMALL);
            b.setToolTipText("The " + a + " human player enters a NECTAR");
            b.addActionListener(e -> sim.humanPlayerAction(a));
            hp.add(b);
        }
        scoreSouth.add(hp, BorderLayout.SOUTH);
        scoreTab.add(scoreSouth, BorderLayout.SOUTH);
        // The shot chart (green: scored, red: missed) is a page of the Score section.
        if (sim.season() != null && sim.handling() != null) {
            Pages score = new Pages().page("Points", scoreTab).page("Shots", shotView);
            pageGroups.add(score);
            addSection("Score", score, "The score, referee notes and the shot chart");
        } else if (sim.season() != null) {
            addSection("Score", scoreTab, "The score and referee notes");
        } else if (sim.handling() != null) {
            addSection("Shots", shotView, "Where shots went");
        }
        JTable table = new JTable(motorModel);
        table.setFont(MONO);
        table.setAutoCreateColumnsFromModel(false);
        int[] widths = {110, 36, 42, 52, 58, 56, 40};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.setRowSelectionAllowed(false);
        table.setOpaque(false);
        table.setDefaultRenderer(Object.class, new ClearCells());
        table.getAccessibleContext().setAccessibleName("Motors");
        JScrollPane tableScroll = Ui.scroll(table);
        JScrollPane mechanisms = Ui.scroll(mechanismView);
        Pages hardware = new Pages().page("Motors", tableScroll).page("Mechanisms & servos", mechanisms);
        pageGroups.add(hardware);
        addSection("Hardware", hardware, "Motors, mechanisms and servos as the simulation has them");
        consoleArea.getAccessibleContext().setAccessibleName("Console");
        addSection("Console", Ui.scroll(consoleArea), "Messages from the simulator and robot code (System.out, RobotLog)");
        addSection("Controls", buildControlsTab(), "The gamepads as the OpMode sees them, and which keys do what");
        sections.addActionListener(e -> sectionCards.show(sectionDeck, sectionNames.get(sections.selectedIndex())));

        Surface card = new Surface(Fluent.Material.CARD, Fluent.OVERLAY_ARC, 8, new BorderLayout(0, 8));
        card.add(sections, BorderLayout.NORTH);
        card.add(sectionDeck, BorderLayout.CENTER);
        return card;
    }

    private void addSection(String name, JComponent page, String toolTip) {
        sectionNames.add(name);
        sections.add(name, null, toolTip);
        sectionDeck.add(page, name);
    }

    /** The Controls section: the gamepads as the OpMode sees them, and which keys and buttons do what. */
    private JComponent buildControlsTab() {
        gamepadView = new GamepadView(runner.gamepad1, runner.gamepad2, gamepads, 0, () -> keyboardRobot == 1,
                partner != null ? "Robot 1 · " : "");
        JLabel help = Ui.label(KeyboardGamepad.help(partner != null));
        help.setVerticalAlignment(JLabel.TOP);
        help.setBorder(BorderFactory.createEmptyBorder(8, 6, 10, 6));
        JPanel controlsTab = new JPanel(new BorderLayout());
        controlsTab.setOpaque(false);
        controlsTab.add(gamepadView, BorderLayout.NORTH);
        controlsTab.add(help, BorderLayout.CENTER);
        return scrollDown(controlsTab);
    }

    /** A page that scrolls up and down and is always as wide as the section (never cut off at the right). */
    private static JScrollPane scrollDown(JComponent page) {
        final class FitWidth extends JPanel implements javax.swing.Scrollable {
            FitWidth() {
                super(new BorderLayout());
                setOpaque(false);
                add(page, BorderLayout.CENTER);
            }

            @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
            @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
            @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - 16; }
            @Override public boolean getScrollableTracksViewportWidth() { return true; }

            @Override
            public boolean getScrollableTracksViewportHeight() {
                // Fill the section when the page is shorter than it; scroll when it's taller.
                return getParent() instanceof javax.swing.JViewport v && v.getHeight() > getPreferredSize().height;
            }
        }
        JScrollPane scroll = Ui.scroll(new FitWidth());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        return scroll;
    }

    private static JPanel column() {
        return Ui.stack(0);
    }

    // ------------------------------------------------------------------ setup

    /**
     * Setup, as a page of its own (as Settings is in a Windows app): robot and field, parts, the
     * 3D model, and appearance, picked along the top.
     */
    private JComponent buildSetupView() {
        JPanel v = new JPanel(new BorderLayout(0, 14)) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Fluent.layer(Appearance.theme()));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        v.setOpaque(true);
        v.setBorder(BorderFactory.createEmptyBorder(14, 28, 16, 28));
        setupPages = new SelectorBar(FluentButton.Size.REGULAR, false);
        setupPages.getAccessibleContext().setAccessibleName("Setup pages");
        setupDeck.setOpaque(false);
        modelPanel = new ModelPanel(sim.robotConfig, robotFile, view3d, this::log);
        Ui.clear(modelPanel);
        PartsPanel parts = new PartsPanel(sim.robotConfig, projectDir, this::log);
        Ui.clear(parts);
        addSetupPage("General", scrollDown(Ui.maxWidth(buildGeneralPage(), 860)), "Robot, field, partner robot and options");
        addSetupPage("Parts", parts, "The parts catalog: motors, servos, wheels, slides and sensors");
        addSetupPage("Model", modelPanel, "The robot's 3D model and which parts move");
        addSetupPage("Appearance", scrollDown(Ui.maxWidth(new AppearancePanel(), 860)), "Theme, transparency effects and text size");
        setupPages.addActionListener(e -> setupCards.show(setupDeck, setupNames.get(setupPages.selectedIndex())));
        v.add(setupPages, BorderLayout.NORTH);
        v.add(setupDeck, BorderLayout.CENTER);
        return v;
    }

    private void addSetupPage(String name, JComponent page, String toolTip) {
        setupNames.add(name);
        setupPages.add(name, null, toolTip);
        setupDeck.add(page, name);
    }

    /** Opens Setup, or goes back to the page shown before it. */
    private void showSetup(boolean show) {
        if (show) showView("Setup");
        else if ("Setup".equals(currentView)) showView(lastView);
    }

    /** Setup > General as Windows Settings lays it out: a card per setting, the control at its right. */
    private JComponent buildGeneralPage() {
        JPanel form = Ui.stack(6);
        FluentButton reloadButton = new FluentButton(null, Icons.RELOAD, FluentButton.Style.STANDARD, FluentButton.Size.REGULAR);
        reloadButton.setToolTipText(Os.text("Reload the robot and field configs, and recompile a robot package's code (⌥⌘R)"));
        FluentButton importRobot = new FluentButton("Import robot…", Icons.IMPORT, FluentButton.Style.STANDARD, FluentButton.Size.REGULAR);
        importRobot.setToolTipText(Os.text("Make a robot package from your team code and CAD model (⌘O)"));
        partnerBox.setSelected(partner != null);
        partnerBox.setToolTipText("<html>A second robot on the field for a second driver: on your alliance (or the other one),<br>"
                + "with its own OpMode, controller and Driver Station (the Robot 2 section)</html>");
        robotBox.getAccessibleContext().setAccessibleName("Robot");
        fieldBox.getAccessibleContext().setAccessibleName("Field");
        for (JComboBox<Path> b : List.of(robotBox, fieldBox)) b.setPreferredSize(new Dimension(280, b.getPreferredSize().height));
        JPanel robotRow = new JPanel(new BorderLayout(6, 0));
        robotRow.setOpaque(false);
        robotRow.add(robotBox, BorderLayout.CENTER);
        robotRow.add(reloadButton, BorderLayout.EAST);
        form.add(Ui.heading("Robot and field"));
        form.add(Ui.settingsCard(Icons.ROBOT, "Robot", "A robot config, or a robot package with its own code", robotRow));
        form.add(Ui.settingsCard(Icons.MAP, "Field", "The season's field, game pieces and start positions", fieldBox));
        form.add(Ui.settingsCard(Icons.PEOPLE, "Partner robot", "A second robot for a second driver (opens the simulator again)", partnerBox));
        form.add(Ui.settingsCard(Icons.IMPORT, "Import robot", "Your team code and CAD model as a robot package", importRobot));

        autoTimerBox.setToolTipText("An AUTO started with Init / Start stops after 30 seconds, like on the Driver Station");
        soundsBox.setToolTipText("The field's match sounds: the start charge, end of AUTO, \"pick up your controllers\", "
                + "the countdown, the TELEOP bell, the 30 second whistle and the end buzzer");
        recordBox.setToolTipText("While an OpMode runs, save the robot's pose every 10 ms to logs/<OpMode>-<time>.csv");
        FluentButton clearTrail = new FluentButton("Clear", Icons.PATH, FluentButton.Style.STANDARD, FluentButton.Size.SMALL);
        clearTrail.setToolTipText("Erase the path drawn in the 2D view");
        JPanel trailRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        trailRow.setOpaque(false);
        trailRow.add(clearTrail);
        trailRow.add(trailBox);
        form.add(Box.createVerticalStrut(10));
        form.add(Ui.heading("Options"));
        form.add(Ui.settingsCard(Icons.CLOCK, "Stop an AUTO after 30 seconds", "Like the Driver Station's AUTO timer", autoTimerBox));
        form.add(Ui.settingsCard(Icons.SPEAKER, "Match sounds", "The field's start, end, countdown and whistle sounds", soundsBox));
        form.add(Ui.settingsCard(Icons.LIST, "Record the robot's pose", "A CSV in logs/ every time an OpMode runs", recordBox));
        form.add(Ui.settingsCard(Icons.PATH, "Robot's path", "Drawn on the 2D field", trailRow));

        soundsBox.setSelected(prefs.getBoolean("matchSounds", true));
        sounds.setEnabled(soundsBox.isSelected());
        soundsBox.addActionListener(e -> {
            sounds.setEnabled(soundsBox.isSelected());
            prefs.putBoolean("matchSounds", soundsBox.isSelected());
        });
        autoTimerBox.addActionListener(e -> runner.setAutoTimer(autoTimerBox.isSelected()));
        trailBox.addActionListener(e -> setShowTrail(trailBox.isSelected()));
        clearTrail.addActionListener(e -> field.clearTrail());
        partnerBox.addActionListener(e -> {
            prefs.putBoolean("partner", partnerBox.isSelected());
            match.abort();
            dispose();
            relaunch.to(robotFile, partnerBox.isSelected() ? savedPartnerFile() : null, fieldFile);
        });
        importRobot.addActionListener(e -> importRobot());
        reloadButton.addActionListener(e -> reloadRobot());
        javax.swing.ListCellRenderer<Object> fileRenderer = new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                return super.getListCellRendererComponent(list, value instanceof Path p ? configLabel(p) : value, index, sel, focus);
            }
        };
        robotBox.setRenderer(fileRenderer);
        fieldBox.setRenderer(fileRenderer);
        for (Path p : configFiles(true)) robotBox.addItem(p);
        for (Path p : configFiles(false)) fieldBox.addItem(p);
        robotBox.setSelectedItem(robotFile);
        fieldBox.setSelectedItem(fieldFile);
        java.awt.event.ActionListener switchConfig = e -> {
            Path r = (Path) robotBox.getSelectedItem(), f = (Path) fieldBox.getSelectedItem();
            if (r == null || f == null || (r.equals(robotFile) && f.equals(fieldFile))) return;
            match.abort();
            dispose();
            relaunch.to(r, partnerFile, f);
        };
        robotBox.addActionListener(switchConfig);
        fieldBox.addActionListener(switchConfig);
        return form;
    }

    private void importRobot() {
        new ImportRobotDialog(this, projectDir, file -> {
            match.abort();
            runner.stop();
            dispose();
            relaunch.to(file.toAbsolutePath(), partnerFile, fieldFile);
        }).setVisible(true);
    }

    /** The agent API's "live" session: this window's simulation. */
    org.ftctestbed.viewer.agent.SimContext agentSession() {
        return agentSession;
    }

    /** The window as it looks now (the 3D view's last frame included), at 1x. */
    private java.awt.image.BufferedImage windowImage() {
        JRootPane root = getRootPane();
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(Math.max(1, root.getWidth()), Math.max(1, root.getHeight()),
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        root.paint(g);
        g.dispose();
        return img;
    }

    /** Copies the command that connects Claude Code to this window's agent API. */
    private void copyAgentConnection() {
        if (!AgentService.running()) {
            log(Os.text("The agent API is off: set \"enabled\": true in config/agent-api.jsonc, then reload (⌥⌘R) or reopen the app."));
            return;
        }
        String cmd = AgentService.connectCommand(projectDir);
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(cmd), null);
        log("Copied the command that connects Claude Code to this window (run it in a terminal; it holds this computer's agent token). "
                + "Other MCP clients: the same address with the header Authorization: Bearer <token>. Guide: docs/agent-api.md");
    }

    /** Opens the folder the app works in (config, TeamCode, robots) in File Explorer or the Finder. */
    private void showWorkingFolder() {
        try {
            java.awt.Desktop.getDesktop().open(projectDir.toFile());
        } catch (Exception e) {
            log("The working folder is " + projectDir);
        }
    }

    private void reloadRobot() {
        if (anyRobotBusy()) {
            log("Stop the OpMode before reloading the robot.");
            return;
        }
        dispose();
        reload.run();
    }

    /**
     * Pages inside a section, picked with a small selector bar along the top. Unlike tabs inside
     * tabs it takes no width from the pages.
     */
    static final class Pages extends JPanel {
        private final CardLayout cards = new CardLayout();
        private final JPanel deck = new JPanel(cards);
        private final SelectorBar bar = new SelectorBar(FluentButton.Size.SMALL, false);
        private final List<String> names = new ArrayList<>();

        Pages() {
            super(new BorderLayout(0, 6));
            setOpaque(false);
            deck.setOpaque(false);
            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            top.setOpaque(false);
            top.add(bar);
            add(top, BorderLayout.NORTH);
            add(deck, BorderLayout.CENTER);
            bar.addActionListener(e -> cards.show(deck, names.get(bar.selectedIndex())));
        }

        Pages page(String name, JComponent page) {
            names.add(name);
            deck.add(page, name);
            bar.add(name, null, null);
            return this;
        }

        /** Shows a page; false if there's none by that name. */
        boolean show(String name) {
            int i = names.indexOf(name);
            if (i < 0) return false;
            bar.select(i);
            cards.show(deck, name);
            return true;
        }
    }

    /** Table cells that let the card show through. */
    private static final class ClearCells extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, v, false, false, row, col);
            setOpaque(false);
            setForeground(Appearance.palette().text());
            setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            return this;
        }
    }

    // ------------------------------------------------------------------ menu bar

    /**
     * Every action, with its keyboard shortcut: in the macOS menu bar, or in the window's title bar
     * on Windows. Shortcuts are written the Mac way and Os.stroke turns them into the system's own
     * (Ctrl for ⌘; see Os), so a tooltip and its menu item can't disagree.
     */
    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(item("Import Robot…", Os.stroke("⌘O"), e -> importRobot()));
        file.add(item("Reload Robot", Os.stroke("⌥⌘R"), e -> reloadRobot()));
        file.add(item("Copy Agent Connection", null, e -> copyAgentConnection()));
        file.add(item("Show Working Folder", null, e -> showWorkingFolder()));
        bar.add(file);

        JMenu run = new JMenu("Run");
        JMenuItem init = item("Init", Os.stroke("⌘I"), e -> initOpMode());
        JMenuItem start = item("Start", Os.stroke("⌘↩"), e -> startOpMode());
        JMenuItem stop = item("Stop", Os.stroke("⌘."), e -> stopEverything());
        JMenuItem startMatch = item("Start Match", Os.stroke("⇧⌘M"), e -> startMatch());
        JMenuItem abortMatch = item("Abort Match", null, e -> abortMatch());
        JMenuItem sandboxItem = item("Drive in Sandbox", Os.stroke("⇧⌘D"), e -> {
            if (sandbox) leaveSandbox(); else enterSandbox();
        });
        JMenuItem saveRun = item("Save & Run Code", Os.stroke("⌘R"), e -> codePanel.saveAndRun(false));
        for (JMenuItem i : new JMenuItem[]{init, start, stop}) run.add(i);
        run.addSeparator();
        for (JMenuItem i : new JMenuItem[]{startMatch, abortMatch, sandboxItem}) run.add(i);
        run.addSeparator();
        run.add(saveRun);
        run.addMenuListener(onOpen(() -> {
            OpModeRunner.State st = runner.state();
            init.setEnabled(st == OpModeRunner.State.IDLE || st == OpModeRunner.State.STOPPED);
            start.setEnabled(st == OpModeRunner.State.INIT);
            startMatch.setEnabled(!match.running());
            abortMatch.setEnabled(match.running());
            sandboxItem.setText(sandbox ? "Leave Sandbox" : "Drive in Sandbox");
        }));
        bar.add(run);

        JMenu view = new JMenu("View");
        String[] viewNames = {"Field in 3D", "Field in 2D", "Code"};
        for (int i = 0; i < 3; i++) {
            String mode = VIEWS[i];
            view.add(item(viewNames[i], Os.stroke("⌘" + (i + 1)), e -> showView(mode)));
        }
        view.add(item("Switch Between Code and Field", Os.stroke("⌘E"),
                e -> showView("Code".equals(currentView) ? lastFieldView : "Code")));
        view.addSeparator();
        JMenu camera = new JMenu("3D Camera");
        view.add(camera);
        JCheckBoxMenuItem crowdItem = new JCheckBoxMenuItem("Crowd and Stands");
        crowdItem.addActionListener(e -> setCrowd(crowdItem.isSelected()));
        view.add(crowdItem);
        JCheckBoxMenuItem cameraItem = new JCheckBoxMenuItem("Robot Camera View");
        cameraItem.addActionListener(e -> setCameraView(cameraItem.isSelected()));
        if (robotHasCamera) view.add(cameraItem);
        JMenu view2dMenu = new JMenu("2D View");
        view.add(view2dMenu);
        JCheckBoxMenuItem trailItem = new JCheckBoxMenuItem("Robot's Path in 2D");
        trailItem.addActionListener(e -> setShowTrail(trailItem.isSelected()));
        view.add(trailItem);
        view.addSeparator();
        JCheckBoxMenuItem panelItem = new JCheckBoxMenuItem("Driver Station");
        panelItem.setAccelerator(Os.stroke("⌥⌘I"));
        panelItem.addActionListener(e -> togglePanel());
        view.add(panelItem);
        view.add(item("Setup", Os.stroke("⌘,"), e -> showSetup(!"Setup".equals(currentView))));
        view.add(item("Show Page Names", Os.stroke("⌃⌘L"),
                e -> shell.setNavExpanded(!nav.expanded())));
        JMenu sectionMenu = new JMenu("Go To");
        view.add(sectionMenu);
        view.addSeparator();
        JMenu themes = new JMenu("Theme");
        view.add(themes);
        JMenu textSize = new JMenu("Text Size");
        view.add(textSize);
        view.addMenuListener(onOpen(() -> {
            camera.removeAll();
            ButtonGroup cg = new ButtonGroup();
            for (String p : view3d.presets()) {
                JRadioButtonMenuItem i = new JRadioButtonMenuItem(p, p.equals(cameraPreset));
                i.addActionListener(e -> {
                    setCameraPreset(p);
                    showView("3D");
                });
                cg.add(i);
                camera.add(i);
            }
            view2dMenu.removeAll();
            ButtonGroup vg = new ButtonGroup();
            for (int i = 0; i < VIEWS_2D.length; i++) {
                int k = i;
                JRadioButtonMenuItem it = new JRadioButtonMenuItem(VIEWS_2D[i], i == view2d);
                it.addActionListener(e -> {
                    setView2d(k);
                    showView("2D");
                });
                vg.add(it);
                view2dMenu.add(it);
            }
            crowdItem.setSelected(crowd);
            cameraItem.setSelected(cameraView);
            trailItem.setSelected(showTrail);
            panelItem.setSelected(shell.paneVisible() && !"Setup".equals(currentView));
            sectionMenu.removeAll();
            for (String s : sectionNames) sectionMenu.add(item(s, null, e -> selectTab(s)));
            themes.removeAll();
            ButtonGroup tg = new ButtonGroup();
            JRadioButtonMenuItem auto = new JRadioButtonMenuItem("Automatic (match " + Os.system() + ")", Appearance.choice() == null);
            auto.addActionListener(e -> Appearance.setTheme(null));
            tg.add(auto);
            themes.add(auto);
            themes.addSeparator();
            for (Theme t : Theme.values()) {
                JRadioButtonMenuItem i = new JRadioButtonMenuItem(t.label, Appearance.choice() == t);
                i.addActionListener(e -> Appearance.setTheme(t));
                tg.add(i);
                themes.add(i);
            }
            textSize.removeAll();
            ButtonGroup sg = new ButtonGroup();
            for (int i = 0; i < Appearance.TEXT_SIZES.length; i++) {
                int k = i;
                JRadioButtonMenuItem it = new JRadioButtonMenuItem(Appearance.TEXT_SIZES[i], Appearance.textSize() == i);
                it.addActionListener(e -> Appearance.setTextSize(k));
                sg.add(it);
                textSize.add(it);
            }
        }));
        bar.add(view);

        JMenu simMenu = new JMenu("Simulation");
        JMenuItem pause = item("Pause", Os.stroke("⌘P"), e -> togglePause());
        simMenu.add(pause);
        simMenu.add(item("Step", Os.stroke("⌥⌘P"), e -> step()));
        JMenu speedMenu = new JMenu("Speed");
        simMenu.add(speedMenu);
        simMenu.addSeparator();
        simMenu.add(item("Reset Field", Os.stroke("⇧⌘R"), e -> resetField()));
        JMenu startMenu = new JMenu(partner != null ? "Robot 1 Start Position" : "Start Position");
        simMenu.add(startMenu);
        JMenu keys = new JMenu("Keyboard Drives");
        if (partner != null) simMenu.add(keys);
        simMenu.addMenuListener(onOpen(() -> {
            pause.setText(simRunner.isPaused() ? "Resume" : "Pause");
            speedMenu.removeAll();
            ButtonGroup g = new ButtonGroup();
            for (double s : SPEEDS) {
                JRadioButtonMenuItem i = new JRadioButtonMenuItem(speedLabel(s), s == simRunner.speed());
                i.addActionListener(e -> setSpeed(s));
                g.add(i);
                speedMenu.add(i);
            }
            startMenu.removeAll();
            ButtonGroup sg = new ButtonGroup();
            for (int i = 0; i < startBox.getItemCount(); i++) {
                FieldConfig.StartPosition sp = startBox.getItemAt(i);
                JRadioButtonMenuItem it = new JRadioButtonMenuItem(sp.toString(), sp.equals(sim.startPosition()));
                it.addActionListener(e -> startBox.setSelectedItem(sp));
                sg.add(it);
                startMenu.add(it);
            }
            keys.removeAll();
            ButtonGroup kg = new ButtonGroup();
            for (int n = 1; n <= 2; n++) {
                int robot = n;
                JRadioButtonMenuItem it = new JRadioButtonMenuItem("Robot " + n, keyboardRobot == n);
                it.addActionListener(e -> {
                    setKeyboardRobot(robot);
                    if (keyboardSwitch != null) keyboardSwitch.select(robot - 1);
                });
                kg.add(it);
                keys.add(it);
            }
        }));
        bar.add(simMenu);

        JMenu help = new JMenu("Help");
        help.add(item("Keyboard and Controllers", null, e -> selectTab("Controls")));
        help.add(item("Console", null, e -> selectTab("Console")));
        bar.add(help);
        if (!Os.MAC) {
            // Alt+F, Alt+R...: the menus from the keyboard, as on every Windows app.
            file.setMnemonic(KeyEvent.VK_F);
            run.setMnemonic(KeyEvent.VK_R);
            view.setMnemonic(KeyEvent.VK_V);
            simMenu.setMnemonic(KeyEvent.VK_S);
            help.setMnemonic(KeyEvent.VK_H);
        }

        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_PREFERENCES)) {
            java.awt.Desktop.getDesktop().setPreferencesHandler(e -> showSetup(true));
        }
        return bar;
    }

    private static JMenuItem item(String text, KeyStroke key, java.awt.event.ActionListener action) {
        JMenuItem i = new JMenuItem(text);
        if (key != null) i.setAccelerator(key);
        i.addActionListener(action);
        return i;
    }

    private static MenuListener onOpen(Runnable r) {
        return new MenuListener() {
            @Override public void menuSelected(MenuEvent e) { r.run(); }
            @Override public void menuDeselected(MenuEvent e) { }
            @Override public void menuCanceled(MenuEvent e) { }
        };
    }

    // ------------------------------------------------------------------ actions

    private void initOpMode() {
        OpModeInfo info = (OpModeInfo) opModeBox.getSelectedItem();
        OpModeRunner.State st = runner.state();
        if (info == null || !(st == OpModeRunner.State.IDLE || st == OpModeRunner.State.STOPPED)) return;
        runModes.select(0);
        field.clearTrail();
        runner.setAutoTimer(autoTimerBox.isSelected());
        runner.init(info);
    }

    private void startOpMode() {
        if (runner.state() == OpModeRunner.State.INIT) runner.start();
    }

    /** ⌘.: stops the sandbox, the match, or the OpMode, whichever is on. */
    private void stopEverything() {
        if (sandbox) leaveSandbox();
        else if (match.running()) abortMatch();
        else runner.stop();
    }

    private void startMatch() {
        if (match.running()) return;
        runModes.select(1);
        setSandbox(false);
        field.clearTrail();
        Object a = matchAutoBox.getSelectedItem(), t = matchTeleBox.getSelectedItem();
        if (partner == null) {
            match.start(opModeOf(a), opModeOf(t));
            log("Match started: " + a + " / " + t);
            return;
        }
        Object a2 = partnerAutoBox.getSelectedItem(), t2 = partnerTeleBox.getSelectedItem();
        match.start(java.util.Arrays.asList(opModeOf(a), opModeOf(a2)), java.util.Arrays.asList(opModeOf(t), opModeOf(t2)));
        log("Match started: Robot 1 " + a + " / " + t + ", Robot 2 " + a2 + " / " + t2);
    }

    private void abortMatch() {
        match.abort();
        log("Match aborted.");
    }

    private void changeStart() {
        FieldConfig.StartPosition sp = (FieldConfig.StartPosition) startBox.getSelectedItem();
        if (sp == null || sp.equals(sim.startPosition())) return;
        if (anyRobotBusy() || match.running()) {
            log("Stop the OpMode or match before changing the start position.");
            startBox.setSelectedItem(sim.startPosition());
            return;
        }
        sim.setStartPosition(sp);
        field.clearTrail();
        selectForAlliance();
    }

    // ------------------------------------------------------------------ code view

    /** The Code view's link to the window: the selected OpMode, logging, and reloading with new code. */
    private CodePanel.Host codeHost() {
        return new CodePanel.Host() {
            @Override
            public String selectedOpMode() {
                return opModeBox.getSelectedItem() instanceof OpModeInfo i ? i.name() : null;
            }

            @Override
            public void runNewCode(CodeSession.State state) {
                // Watch the field when an OpMode is about to start; otherwise keep editing.
                String view = state.runOpMode() != null ? lastFieldView : "Code";
                CodeSession.hand(new CodeSession.State(state.files(), state.selected(), state.carets(), view, state.runOpMode()));
                log("Reloading with the new code…");
                match.abort();
                for (OpModeRunner r : runners) r.stop();
                dispose();
                reload.run();
            }

            @Override
            public void log(String message) {
                MainWindow.this.log(message);
            }
        };
    }

    /** After "Save & Run" reloaded the window: bring the Code view back and start the OpMode. */
    void resumeAfterCodeReload() {
        CodeSession.State st = CodeSession.take();
        if (st == null) return;
        codePanel.restore(st);
        if (st.view() != null) showView(st.view());
        if (st.runOpMode() == null) return;
        for (int i = 0; i < opModeBox.getItemCount(); i++) {
            if (opModeBox.getItemAt(i).name().equals(st.runOpMode())) {
                opModeBox.setSelectedIndex(i);
                initOpMode();
                new Timer(800, e -> startOpMode()) {{ setRepeats(false); }}.start();
                log("Running " + st.runOpMode() + " with the new code.");
                return;
            }
        }
        log("\"" + st.runOpMode() + "\" isn't in the new code, so nothing was started.");
    }

    @Override
    public void dispose() {
        // Never lose edits: the Code view saves when the window closes or reloads.
        if (codePanel != null) {
            for (Path f : codePanel.saveAllQuietly()) originalErr.println("Couldn't save " + f);
        }
        saveBounds();
        super.dispose();
    }

    private void saveBounds() {
        if (!isDisplayable()) return;
        Rectangle b = getBounds();
        prefs.put("bounds", b.x + "," + b.y + "," + b.width + "," + b.height);
        if (split != null) prefs.putDouble("telemetryShare", split.share());
    }

    /** The window where it was last time (if that's still on a screen), else 1360 x 880 in the middle. */
    private void restoreBounds() {
        String[] b = prefs.get("bounds", "").split(",");
        if (b.length == 4) {
            try {
                Rectangle r = new Rectangle(Integer.parseInt(b[0]), Integer.parseInt(b[1]), Integer.parseInt(b[2]), Integer.parseInt(b[3]));
                for (java.awt.GraphicsDevice d : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                    if (d.getDefaultConfiguration().getBounds().intersects(r) && r.width >= 1000 && r.height >= 640) {
                        setBounds(r);
                        return;
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
        setSize(1360, 880);
        setLocationRelativeTo(null);
    }

    // ------------------------------------------------------------------ theme

    /** Colors and sizes that come from the theme and text size, after it changes. */
    private void restyle() {
        Theme.Palette p = Appearance.palette();
        double s = Appearance.textScale();
        timerLabel.setFont(Os.mono(Font.BOLD, (int) Math.round(30 * s)));
        timerLabel.setForeground(p.text());
        stateLabel.setFont(UIManagerFont(Font.BOLD, 1.0));
        stateLabel.setForeground(p.text());
        loopLabel.setFont(UIManagerFont(Font.PLAIN, 0.88));
        loopLabel.setForeground(p.secondary());
        telemetryArea.setFont(MONO.deriveFont((float) (13 * s)));
        consoleArea.setFont(MONO.deriveFont((float) (12 * s)));
        notesArea.setFont(MONO.deriveFont((float) (11 * s)));
        errorArea.setFont(MONO.deriveFont((float) (12 * s)));
        viewCards.setBackground(p.field());
        codeWrapper.setBackground(Fluent.layer(Appearance.theme()));
        codePanel.applyTheme(Appearance.theme());
        // The 3D and 2D readouts sit on small cards in the theme's colors.
        java.awt.Color hud = Contrast.alpha(Fluent.card(Appearance.theme()), 0.92);
        view3d.setHudTheme(p.text(), hud);
        field.setHudColors(p.text(), hud);
        Appearance.decorate(getRootPane());
        updateDrawing(); // the frame rate may have changed
        if (shell != null) {
            shell.revalidate();
            shell.repaint();
        }
    }

    private static Font UIManagerFont(int style, double scale) {
        Font f = javax.swing.UIManager.getFont("defaultFont");
        if (f == null) f = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        return f.deriveFont(style, (float) (f.getSize2D() * scale));
    }

    /** Robot or field config files in config/ and config/examples/, plus robot packages in robots/. */
    private List<Path> configFiles(boolean robots) {
        List<Path> out = new ArrayList<>();
        if (robots) {
            try (java.util.stream.Stream<Path> dirs = java.nio.file.Files.list(projectDir.resolve("robots"))) {
                dirs.sorted().map(d -> d.resolve("robot.jsonc")).filter(java.nio.file.Files::exists)
                        .forEach(f -> out.add(f.toAbsolutePath()));
            } catch (java.io.IOException ignored) {
            }
        }
        for (Path dir : new Path[]{projectDir.resolve("config"), projectDir.resolve("config/examples")}) {
            try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".jsonc")).sorted().forEach(f -> {
                    try {
                        boolean isRobot = java.nio.file.Files.readString(f).contains("\"drivetrain\"");
                        if (isRobot == robots) out.add(f.toAbsolutePath());
                    } catch (java.io.IOException ignored) {
                    }
                });
            } catch (java.io.IOException ignored) {
            }
        }
        Path current = robots ? robotFile : fieldFile;
        if (!out.contains(current)) out.add(0, current);
        return out;
    }

    /** "robot.jsonc", or "robots/MyBot" for a robot package. */
    private String configLabel(Path p) {
        Path parent = p.getParent();
        if (parent != null && parent.getParent() != null && parent.getParent().equals(projectDir.resolve("robots"))) {
            return "robots/" + parent.getFileName();
        }
        return p.getFileName().toString();
    }

    /** For the full match, prefer a driver TeleOp from the same group as the AUTO (not a tuner or test). */
    private static void selectMatchTeleOp(JComboBox<Object> autoBox, JComboBox<Object> teleBox) {
        String group = autoBox.getSelectedItem() instanceof OpModeInfo a ? a.group() : null;
        int fallback = -1;
        for (int k = 0; k < teleBox.getItemCount(); k++) {
            if (!(teleBox.getItemAt(k) instanceof OpModeInfo i)) continue;
            String n = i.name().toLowerCase(Locale.ROOT);
            if (n.contains("tune") || n.contains("test") || n.contains("calib") || n.contains("sweep")) continue;
            if (java.util.Objects.equals(i.group(), group)) {
                teleBox.setSelectedIndex(k);
                return;
            }
            if (fallback < 0) fallback = k;
        }
        if (fallback >= 0) teleBox.setSelectedIndex(fallback);
    }

    private static OpModeInfo opModeOf(Object item) {
        return item instanceof OpModeInfo i ? i : null;
    }

    // ------------------------------------------------------------------ partner robot

    /** True while any robot's OpMode is in INIT or running. */
    private boolean anyRobotBusy() {
        for (OpModeRunner r : runners) {
            OpModeRunner.State st = r.state();
            if (st == OpModeRunner.State.INIT || st == OpModeRunner.State.RUNNING) return true;
        }
        return false;
    }

    /** The runner of the robot the keyboard drives. */
    private OpModeRunner keyboardRunner() {
        return keyboardRobot == 2 && partner != null ? partner : runner;
    }

    private void setKeyboardRobot(int n) {
        if (n == keyboardRobot) return;
        keyboardRobot = n;
        gamepads.setKeyboardSlot(2 * (n - 1));
        log("The keyboard now drives Robot " + n + ".");
    }

    /** The robot last picked for the partner (the first robot's config if none was). */
    private Path savedPartnerFile() {
        String saved = prefs.get("partnerRobot", "");
        Path p = saved.isEmpty() ? robotFile : Path.of(saved);
        return java.nio.file.Files.exists(p) ? p : robotFile;
    }

    /** The partner's CAD model, if its robot has one (model.jsonc next to its robot.jsonc). */
    private void loadPartnerModel() {
        Path modelFile = org.ftctestbed.viewer.model.ModelConfig.fileFor(partnerFile);
        if (!java.nio.file.Files.exists(modelFile)) return;
        Thread t = new Thread(() -> {
            try {
                org.ftctestbed.viewer.model.ModelConfig cfg = org.ftctestbed.viewer.model.ModelConfig.load(modelFile);
                org.ftctestbed.viewer.model.ModelData data = org.ftctestbed.viewer.model.ModelLoader.load(partnerFile.resolveSibling(cfg.file));
                view3d.setModel(2, new org.ftctestbed.viewer.three.RobotModel(data, cfg, sim.robot(2).config));
            } catch (Exception e) {
                log("Could not load Robot 2's 3D model: " + e.getMessage());
            }
        }, "partner model loader");
        t.setDaemon(true);
        t.start();
    }

    /** Pre-select OpModes whose names mention the robot's alliance (e.g. "Auto Blue"). */
    private void selectForAlliance() {
        String a = sim.alliance();
        if (a == null) return;
        // The 3D camera moves to this alliance's drivers too (if it was at a driver station).
        if (cameraPreset.endsWith("driver")) setCameraPreset(a.equals("blue") ? "Blue driver" : "Red driver");
        String other = a.equals("red") ? "blue" : "red";
        for (JComboBox<?> box : new JComboBox<?>[]{opModeBox, matchAutoBox}) {
            Object sel = box.getSelectedItem();
            boolean wrong = sel instanceof OpModeInfo i && i.name().toLowerCase().contains(other);
            if (!wrong) continue;
            for (int k = 0; k < box.getItemCount(); k++) {
                if (box.getItemAt(k) instanceof OpModeInfo i && i.name().toLowerCase().contains(a)) {
                    box.setSelectedIndex(k);
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ debugging aids

    /**
     * -Dtestbed.autostart="OpMode name" INITs and STARTs that OpMode on launch.
     * -Dtestbed.match="Auto name|TeleOp name" starts a full match on launch.
     * -Dtestbed.screenshot=file.png -Dtestbed.screenshotDelay=seconds saves an image of the window.
     * -Dtestbed.tab="Model" / -Dtestbed.camera="Follow robot" pick a section or setup page / 3D camera.
     * -Dtestbed.view2d="Follow robot" shows the 2D field in that view.
     * -Dtestbed.theme="GRUVBOX_DARK" picks a theme for this run.
     * -Dtestbed.view="Code" / -Dtestbed.runMode="Match" show that view / run card page.
     * -Dtestbed.nav=open opens the navigation pane; -Dtestbed.menuShot / comboShot=file.png save the camera menu / OpMode list.
     */
    void runDebugOptions() {
        String theme = System.getProperty("testbed.theme");
        if (theme != null) {
            for (Theme t : Theme.values()) if (t.name().equalsIgnoreCase(theme) || t.label.equalsIgnoreCase(theme)) Appearance.preview(t);
        }
        String auto = System.getProperty("testbed.autostart");
        if (auto != null) {
            for (int i = 0; i < opModeBox.getItemCount(); i++) {
                if (opModeBox.getItemAt(i).name().equals(auto)) opModeBox.setSelectedIndex(i);
            }
            initOpMode();
            new Timer(800, e -> startOpMode()) {{ setRepeats(false); }}.start();
        }
        String matchOpModes = System.getProperty("testbed.match");
        if (matchOpModes != null) {
            String[] names = matchOpModes.split("\\|");
            for (int i = 0; i < matchAutoBox.getItemCount(); i++) {
                if (matchAutoBox.getItemAt(i) instanceof OpModeInfo info && info.name().equals(names[0])) matchAutoBox.setSelectedIndex(i);
            }
            for (int i = 0; i < matchTeleBox.getItemCount(); i++) {
                if (names.length > 1 && matchTeleBox.getItemAt(i) instanceof OpModeInfo info && info.name().equals(names[1])) matchTeleBox.setSelectedIndex(i);
            }
            startMatch();
        }
        String dialogShot = System.getProperty("testbed.importDialogShot");
        if (dialogShot != null) { // renders the Import robot dialog to an image (for docs)
            ImportRobotDialog d = new ImportRobotDialog(this, projectDir, f -> { });
            d.pack();
            java.awt.Container c = d.getContentPane();
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(c.getWidth(), c.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            c.paint(g);
            g.dispose();
            try {
                javax.imageio.ImageIO.write(img, "png", new java.io.File(dialogShot));
            } catch (java.io.IOException ex) {
                log("Could not save " + dialogShot);
            }
            d.dispose();
        }
        String modelShot = System.getProperty("testbed.modelPanelShot"); // file.png[|part name]
        if (modelShot != null) {
            String[] parts = modelShot.split("\\|", 2);
            new Timer(4000, e -> {
                try {
                    javax.imageio.ImageIO.write(modelPanel.debugRender(parts.length > 1 ? parts[1] : null), "png", new java.io.File(parts[0]));
                } catch (java.io.IOException ex) {
                    log("Could not save " + parts[0]);
                }
            }) {{ setRepeats(false); }}.start();
        }
        String cam = System.getProperty("testbed.camera");
        if (cam != null) setCameraPreset(cam.equals("Follow robot") && partner != null ? "Follow robot 1" : cam);
        String v2 = System.getProperty("testbed.view2d");
        if (v2 != null) {
            showView("2D");
            int i = List.of(VIEWS_2D).indexOf(v2);
            if (i >= 0) setView2d(i);
        }
        String tab = System.getProperty("testbed.tab");
        if (tab != null) selectTab(tab);
        String view = System.getProperty("testbed.view");
        if (view != null) showView(view);
        String mode = System.getProperty("testbed.runMode");
        if (mode != null) runModes.select(List.of("OpMode", "Match", "Sandbox").indexOf(mode));
        if ("open".equals(System.getProperty("testbed.nav"))) { // the navigation pane open on acrylic (for docs)
            new Timer(3000, e -> shell.setNavExpanded(true)) {{ setRepeats(false); }}.start();
        }
        String menuShot = System.getProperty("testbed.menuShot"); // the camera menu on acrylic, as an image (for docs)
        if (menuShot != null) {
            new Timer(3500, e -> {
                JPopupMenu m = viewOptionsMenu();
                m.show(viewMenuButton, 0, viewMenuButton.getHeight());
                new Timer(600, e2 -> {
                    java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(m.getWidth(), m.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g = img.createGraphics();
                    m.paint(g);
                    g.dispose();
                    try {
                        javax.imageio.ImageIO.write(img, "png", new java.io.File(menuShot));
                    } catch (java.io.IOException ex) {
                        log("Could not save " + menuShot);
                    }
                }) {{ setRepeats(false); }}.start();
            }) {{ setRepeats(false); }}.start();
        }
        String comboShot = System.getProperty("testbed.comboShot"); // a drop-down list open on acrylic, as an image (for docs)
        if (comboShot != null) {
            new Timer(3500, e -> {
                JComboBox<?> box = opModeBox.isShowing() ? opModeBox : matchAutoBox.isShowing() ? matchAutoBox : startBox;
                box.showPopup();
                new Timer(600, e2 -> {
                    if (!(box.getUI().getAccessibleChild(box, 0) instanceof JComponent pop)) return;
                    java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(pop.getWidth(), pop.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g = img.createGraphics();
                    pop.paint(g);
                    g.dispose();
                    try {
                        javax.imageio.ImageIO.write(img, "png", new java.io.File(comboShot));
                    } catch (java.io.IOException ex) {
                        log("Could not save " + comboShot);
                    }
                }) {{ setRepeats(false); }}.start();
            }) {{ setRepeats(false); }}.start();
        }
        String shot = System.getProperty("testbed.screenshot");
        if (shot != null) {
            int delay = (int) (Double.parseDouble(System.getProperty("testbed.screenshotDelay", "3")) * 1000);
            new Timer(delay, e -> {
                java.awt.Container c = getContentPane();
                // Drawn on the graphics card, as the window draws itself (so the 2D view's cached layers show).
                java.awt.image.VolatileImage vi = c.getGraphicsConfiguration().createCompatibleVolatileImage(c.getWidth(), c.getHeight());
                java.awt.Graphics2D vg = vi.createGraphics();
                c.paint(vg);
                vg.dispose();
                java.awt.image.BufferedImage img = vi.getSnapshot();
                vi.flush();
                try {
                    javax.imageio.ImageIO.write(img, "png", new java.io.File(shot));
                    log("Saved screenshot " + shot);
                } catch (java.io.IOException ex) {
                    log("Screenshot failed: " + ex);
                }
            }) {{ setRepeats(false); }}.start();
        }
    }

    /**
     * Shows a section by name ("Score", "Console"...), a page inside one ("Shots", "Motors"), or a
     * Setup page ("Setup", "General", "Parts", "Model", "Appearance"; "Robot & field" is General).
     */
    void selectTab(String name) {
        if (name.equals("Robot & field")) name = "General";
        int s = sectionNames.indexOf(name);
        if (s >= 0) {
            showSetup(false);
            sections.select(s);
            return;
        }
        if (name.equals("Setup") || setupNames.contains(name)) {
            showSetup(true);
            int p = setupNames.indexOf(name);
            if (p >= 0) setupPages.select(p);
            return;
        }
        for (Pages group : pageGroups) {
            if (group.show(name)) {
                for (int i = 0; i < sectionNames.size(); i++) {
                    if (SwingUtilities.isDescendingFrom(group, sectionDeck.getComponent(i))) {
                        showSetup(false);
                        sections.select(i);
                    }
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------ console

    private void installConsole() {
        SimHooks.install(null, null, (level, tag, message) -> {
            String line = "[" + level + "] " + tag + ": " + message;
            originalOut.println(line);
            appendConsole(line + "\n");
        });
        System.setOut(new PrintStream(new TeeStream(originalOut), true));
        System.setErr(new PrintStream(new TeeStream(originalErr), true));
        SimHooks.installSoundSink(new RobotSounds()); // SoundPlayer in robot code plays here
    }

    private final class TeeStream extends OutputStream {
        private final PrintStream target;
        private final StringBuilder pending = new StringBuilder();

        TeeStream(PrintStream target) {
            this.target = target;
        }

        @Override
        public synchronized void write(int b) {
            target.write(b);
            pending.append((char) b);
            if (b == '\n') flushPending();
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            target.write(b, off, len);
            pending.append(new String(b, off, len));
            if (pending.indexOf("\n") >= 0) flushPending();
        }

        private void flushPending() {
            appendConsole(pending.toString());
            pending.setLength(0);
        }
    }

    private void appendConsole(String text) {
        org.ftctestbed.viewer.agent.Console.add(text); // what agents read with console_read
        SwingUtilities.invokeLater(() -> {
            consoleArea.append(text);
            if (consoleArea.getDocument().getLength() > 200_000) consoleArea.replaceRange("", 0, 50_000);
            consoleArea.setCaretPosition(consoleArea.getDocument().getLength());
        });
    }

    private void log(String message) {
        appendConsole(message + "\n");
    }

    // ------------------------------------------------------------------ updates

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;

        OpModeRunner.State st = runner.state();
        if (gamepadView.isShowing() && ticks % 2 == 0) gamepadView.refresh();
        MatchState m = sim.match();
        MatchState.Phase phase = m.phase(); // before the time it started: enter() sets the time first
        sounds.update(phase, m.elapsedInPhase(sim.clock.seconds()));
        if (minimized || hidden) return; // nothing to show; the simulation runs on

        // With a partner: both robots at the same moment (one lock, one field state).
        List<Snapshot> shots = partner != null ? sim.snapshots() : null;
        Snapshot s = shots != null ? shots.get(0) : sim.snapshot();
        String simStatus = simRunner.isPaused() ? "   PAUSED" : simRunner.speed() != 1.0 ? String.format(Locale.US, "   %s speed", speedLabel(simRunner.speed())) : "";
        SeasonSnapshot season = s.season();
        // The clock, phase and score are in the title bar; the views show the simulated time.
        String statusLine = String.format(Locale.US, "t = %.1f s%s", s.time(), simStatus);
        field.setStatusLine(statusLine);
        if (show3d) {
            view3d.setStatus(statusLine);
            if (shots != null) view3d.offer(shots); else view3d.offer(s);
        }
        if (partnerPanel != null) partnerPanel.tick();
        // The start can change from the Robot 2 section too (two robots never share a start).
        FieldConfig.StartPosition sp1 = sim.startPosition();
        if (sp1 != null && !sp1.equals(startBox.getSelectedItem()) && !startBox.isPopupVisible()) {
            if (((javax.swing.DefaultComboBoxModel<FieldConfig.StartPosition>) startBox.getModel()).getIndexOf(sp1) < 0) startBox.addItem(sp1);
            startBox.setSelectedItem(sp1);
            selectForAlliance();
        }
        field.refresh(s); // keeps the trail up to date even while 3D is showing
        // The numbers in the pane change 15 times a second: plenty to read, and a quarter of the repainting.
        if (++ticks % 4 != 0) return;
        if (season != null) {
            scoreBoard.update(season, s.alliance());
            if (!season.notes().equals(lastNotes)) {
                lastNotes = new ArrayList<>(season.notes());
                notesArea.setText(String.join("\n", lastNotes));
            }
        }
        shotView.update(s.flights());

        List<String> lines = runner.telemetry().publishedLines();
        com.acmerobotics.dashboard.telemetry.TelemetryPacket packet = com.acmerobotics.dashboard.FtcDashboard.getInstance().simLastPacket();
        List<String> panels = com.bylazar.telemetry.PanelsTelemetry.INSTANCE.simLines();
        if (lines != lastTelemetry || packet != lastPacket || panels != lastPanels) {
            lastTelemetry = lines;
            lastPacket = packet;
            lastPanels = panels;
            StringBuilder text = new StringBuilder(String.join("\n", lines));
            if (packet != null && (!packet.simData().isEmpty() || !packet.simLines().isEmpty())) {
                text.append("\n\n── FTC Dashboard ──");
                packet.simData().forEach((k, v) -> text.append('\n').append(k).append(" : ").append(v));
                for (String l : packet.simLines()) text.append('\n').append(l);
            }
            if (!panels.isEmpty()) {
                text.append("\n\n── Panels ──");
                for (String l : panels) text.append('\n').append(l);
            }
            telemetryArea.setText(text.toString());
        }

        motorModel.update(s.motors());
        mechanismView.update(s.mechanisms(), s.servos(), s.parts(), s.lights());
        updateTimer(st);
        double loopMs = simRunner.robotLoopMillis();
        loopLabel.setText(Double.isNaN(loopMs) ? " " : String.format(Locale.US, "Loop %.1f ms (%.0f a second)", loopMs, 1000 / loopMs));
        if (!Double.isNaN(s.matchClock())) {
            double c = Math.max(0, s.matchClock());
            timerLabel.setText(String.format(Locale.US, "%d:%02d", (int) Math.ceil(c) / 60, (int) Math.ceil(c) % 60));
            stateLabel.setText("Match " + s.matchPhase() + (runner.current() != null ? " · " + runner.current().name() : ""));
        }
        if (match.running() != matchShownRunning) updateRunButtons();
        updateStatus(s, season, st);

        if (simRunner.physicsError() != null) {
            showError("Physics thread crashed: " + simRunner.physicsError());
        }
    }

    /** The status in the title bar. */
    private void updateStatus(Snapshot s, SeasonSnapshot season, OpModeRunner.State st) {
        OpModeInfo cur = runner.current();
        String name = cur != null ? cur.name() : "";
        StatusPill.Kind kind;
        String text, clock = "";
        if (!Double.isNaN(s.matchClock())) {
            double c = Math.max(0, s.matchClock());
            kind = StatusPill.Kind.RUNNING;
            text = "Match · " + s.matchPhase();
            clock = String.format(Locale.US, "%d:%02d", (int) Math.ceil(c) / 60, (int) Math.ceil(c) % 60);
        } else if (sandbox) {
            kind = StatusPill.Kind.RUNNING;
            text = "Sandbox" + (name.isEmpty() ? "" : " · " + name);
        } else {
            switch (st) {
                case INIT -> { kind = StatusPill.Kind.READY; text = "Ready · " + name; }
                case RUNNING -> { kind = StatusPill.Kind.RUNNING; text = name; clock = timerLabel.getText(); }
                case STOPPING -> { kind = StatusPill.Kind.STOPPED; text = "Stopping · " + name; }
                case STOPPED -> { kind = StatusPill.Kind.STOPPED; text = "Stopped · " + name; }
                default -> { kind = StatusPill.Kind.IDLE; text = "Practice"; }
            }
        }
        if (errorBanner.isVisible()) kind = StatusPill.Kind.ERROR;
        if (simRunner.isPaused()) {
            kind = StatusPill.Kind.PAUSED;
            text = "Paused · " + text;
        } else if (simRunner.speed() != 1) {
            text += " · " + speedLabel(simRunner.speed());
        }
        statusPill.set(new StatusPill.Status(kind, text, clock,
                season == null ? null : season.score("red"), season == null ? null : season.score("blue")));
    }

    private void updateTimer(OpModeRunner.State st) {
        if (sandbox) {
            timerLabel.setText("—:—");
            return;
        }
        double t = runner.elapsed();
        OpModeInfo cur = runner.current();
        if (st == OpModeRunner.State.RUNNING && cur != null && cur.autonomous() && runner.autoTimer()) {
            double left = Math.max(0, OpModeRunner.AUTONOMOUS_SECONDS - t);
            timerLabel.setText(String.format(Locale.US, "0:%02d", (int) Math.ceil(left)));
        } else if (st == OpModeRunner.State.RUNNING || st == OpModeRunner.State.INIT) {
            timerLabel.setText(String.format(Locale.US, "%d:%02d", (int) t / 60, (int) t % 60));
        }
    }

    private void onStateChange() {
        OpModeRunner.State st = runner.state();
        OpModeInfo cur = runner.current();
        String name = cur != null ? cur.name() : "";
        checkSandboxEnded();
        switch (st) {
            case IDLE -> stateLabel.setText(match.running() ? "Match starting…" : switch (runModes.selectedIndex()) {
                case 1 -> "Pick AUTO and TELEOP, then Start match";
                case 2 -> "Pick a TELEOP, then Drive";
                default -> "Pick an OpMode, then Init";
            });
            case INIT -> stateLabel.setText("Initialized · press Start");
            case RUNNING -> stateLabel.setText(sandbox ? "Sandbox · drive freely" : "Running " + name);
            case STOPPING -> stateLabel.setText("Stopping " + name + "…");
            case STOPPED -> stateLabel.setText("Stopped " + name);
        }
        opModeBox.setEnabled(st == OpModeRunner.State.IDLE || st == OpModeRunner.State.STOPPED);
        updateRunButtons();

        String err = runner.error() != null ? runner.error() : runner.warning();
        if (err != null) showError(err); else hideError();

        if (st == OpModeRunner.State.RUNNING && lastState != OpModeRunner.State.RUNNING && recordBox.isSelected()) {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path file = projectDir.resolve("logs").resolve(name.replaceAll("[^A-Za-z0-9_-]+", "_") + "-" + stamp + ".csv");
            logger = new PoseLogger(file, sim, 0.01);
            sim.addStepListener(logger);
        }
        if (st == OpModeRunner.State.STOPPED) {
            closeLogger();
            if (cur != null && cur.autonomous() && !cur.preselectTeleOp().isEmpty()) {
                for (int i = 0; i < opModeBox.getItemCount(); i++) {
                    if (opModeBox.getItemAt(i).name().equals(cur.preselectTeleOp())) opModeBox.setSelectedIndex(i);
                }
            }
        }
        lastState = st;
    }

    /**
     * The run card's main buttons follow what's happening: Init → Start → Stop for an OpMode,
     * Start match → Abort for a match, Drive → Leave for the sandbox.
     */
    private void updateRunButtons() {
        RunButtons.show(runButton, stopButton, runner.state(), opModeBox.getItemCount() > 0, true);
        matchShownRunning = match.running();
        if (matchShownRunning) {
            matchButton.set("Abort match", Icons.STOP);
            matchButton.setStyle(FluentButton.Style.ACCENT, FluentButton.Tint.STOP);
            matchButton.setToolTipText(Os.text("Abort the match (⌘.)"));
        } else {
            matchButton.set("Start match", Icons.FLAG);
            matchButton.setStyle(FluentButton.Style.ACCENT, FluentButton.Tint.ACCENT);
            matchButton.setToolTipText(Os.text("Start the match (⇧⌘M); it resets the field first"));
        }
        if (sandbox) {
            sandboxButton.set("Leave sandbox", Icons.STOP);
            sandboxButton.setStyle(FluentButton.Style.ACCENT, FluentButton.Tint.STOP);
        } else {
            sandboxButton.set("Drive", Icons.GAMEPAD);
            sandboxButton.setStyle(FluentButton.Style.ACCENT, FluentButton.Tint.GO);
        }
    }

    // ------------------------------------------------------------------ sandbox

    private volatile boolean sandboxStarting;

    /** The sandbox is over once every robot has stopped. */
    private void checkSandboxEnded() {
        if (sandbox && !sandboxStarting && !anyRobotBusy()) setSandbox(false);
    }

    /** Robot 2's TeleOp for the sandbox: the one chosen for it under Match, else the one picked in its section. */
    private OpModeInfo partnerSandboxTeleOp() {
        if (partner == null) return null;
        if (partnerTeleBox.getSelectedItem() instanceof OpModeInfo i) return i;
        return partnerPanel.teleOp();
    }

    /** The TeleOp to drive in the sandbox: the one chosen for the match, else the selected OpMode if it's a TeleOp. */
    private OpModeInfo sandboxTeleOp() {
        if (matchTeleBox.getSelectedItem() instanceof OpModeInfo i) return i;
        if (opModeBox.getSelectedItem() instanceof OpModeInfo i && i.teleOp()) return i;
        for (int k = 0; k < opModeBox.getItemCount(); k++) if (opModeBox.getItemAt(k).teleOp()) return opModeBox.getItemAt(k);
        return null;
    }

    /** Stop whatever runs, go to free practice and start the TeleOp straight away. */
    private void enterSandbox() {
        OpModeInfo tele = sandboxTeleOp(), tele2 = partnerSandboxTeleOp();
        if (tele == null && tele2 == null) {
            log("There's no TeleOp OpMode to drive in the sandbox.");
            return;
        }
        runModes.select(2);
        setSandbox(true);
        sandboxStarting = true;
        field.clearTrail();
        if (partner == null) {
            log("Sandbox: " + tele.name() + Os.text(" with no match clock. Press Leave sandbox (or ⌘.) to leave."));
        } else {
            log("Sandbox with no match clock: Robot 1 " + (tele != null ? tele.name() : "sits still") + ", Robot 2 "
                    + (tele2 != null ? tele2.name() : "sits still") + ". Leave sandbox stops both.");
        }
        List<OpModeInfo> teleops = java.util.Arrays.asList(tele, tele2);
        Thread t = new Thread(() -> {
            try {
                Sandbox.start(sim, match, runners, teleops);
            } finally {
                sandboxStarting = false;
                SwingUtilities.invokeLater(this::onStateChange);
            }
        }, "sandbox");
        t.setDaemon(true);
        t.start();
    }

    private void leaveSandbox() {
        setSandbox(false);
        for (OpModeRunner r : runners) r.stop();
        log("Left the sandbox.");
    }

    private void setSandbox(boolean on) {
        sandbox = on;
        SwingUtilities.invokeLater(() -> {
            updateRunButtons();
            if (!on) timerLabel.setText("0:00");
        });
    }

    private void closeLogger() {
        if (logger == null) return;
        sim.removeStepListener(logger);
        logger.close();
        log("Saved pose log: " + projectDir.relativize(logger.file()));
        logger = null;
    }

    private void showError(String text) {
        if (!text.equals(errorArea.getText())) errorArea.setText(text);
        if (!errorBanner.isVisible()) {
            errorBanner.setVisible(true);
            pane.revalidate();
        }
    }

    private void hideError() {
        errorArea.setText("");
        if (errorBanner.isVisible()) {
            errorBanner.setVisible(false);
            pane.revalidate();
        }
    }

    // ------------------------------------------------------------------ tables

    private static final class MotorTableModel extends AbstractTableModel {
        private static final String[] COLS = {"Motor", "Port", "Mode", "Power", "Ticks", "Ticks/s", "Amps"};
        private List<Snapshot.Motor> rows = new ArrayList<>();

        void update(List<Snapshot.Motor> motors) {
            if (motors.equals(rows)) return;
            boolean structural = motors.size() != rows.size();
            rows = motors;
            if (structural) fireTableDataChanged(); else fireTableRowsUpdated(0, rows.size() - 1);
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            Snapshot.Motor m = rows.get(r);
            return switch (c) {
                case 0 -> m.name();
                case 1 -> (m.hub().startsWith("Control") ? "CH" : "EH") + m.port();
                case 2 -> switch (m.mode()) {
                    case "RUN_WITHOUT_ENCODER" -> "RAW";
                    case "RUN_USING_ENCODER" -> "VEL";
                    case "RUN_TO_POSITION" -> "R2P";
                    case "STOP_AND_RESET_ENCODER" -> "RESET";
                    default -> m.mode();
                };
                case 3 -> String.format(Locale.US, "%+.2f", m.power());
                case 4 -> m.position();
                case 5 -> String.format(Locale.US, "%.0f", m.velocityTps());
                default -> String.format(Locale.US, "%.1f", m.currentA());
            };
        }
    }

}
