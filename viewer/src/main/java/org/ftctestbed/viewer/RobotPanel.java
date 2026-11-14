package org.ftctestbed.viewer;

import org.ftctestbed.sim.SimRobot;
import org.ftctestbed.sim.SimRunner;
import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRegistry;
import org.ftctestbed.sim.opmode.OpModeRunner;
import org.ftctestbed.viewer.ui.Appearance;
import org.ftctestbed.viewer.ui.FluentButton;
import org.ftctestbed.viewer.ui.Icons;
import org.ftctestbed.viewer.ui.Ui;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The partner robot's Driver Station, in its own section: which robot it is and where it starts,
 * its OpMode with the same Init → Start → Stop button as robot 1's run card, its telemetry and
 * its gamepads. Full matches and the sandbox (in the run card) run it alongside the first robot.
 */
final class RobotPanel extends JPanel {
    private static final Font MONO = org.ftctestbed.viewer.platform.Os.mono(Font.PLAIN, 12);

    private final Simulation sim;
    private final SimRobot robot;
    private final OpModeRunner runner;
    private final SimRunner simRunner;
    private final BooleanSupplier busy;
    private final BooleanSupplier autoTimer;
    private final Consumer<String> log;

    final JComboBox<OpModeInfo> opModeBox = new JComboBox<>();
    final FluentButton runButton = new FluentButton("Init", Icons.POWER, FluentButton.Style.ACCENT, FluentButton.Size.REGULAR);
    final FluentButton stopButton = new FluentButton(null, Icons.STOP, FluentButton.Style.STANDARD, FluentButton.Size.REGULAR);
    final JComboBox<FieldConfig.StartPosition> startBox = new JComboBox<>();
    final JComboBox<Path> robotBox = new JComboBox<>();
    private final JLabel timerLabel = styled("0:00", 24, true, false);
    private final JLabel stateLabel = styled("Pick an OpMode, then Init", 1.0, false, false);
    private final JLabel loopLabel = styled(" ", 0.88, false, true);
    private final JTextArea errorArea = Ui.text(MONO);
    private final JComponent errorBanner;
    final JTextArea telemetryArea;
    private final GamepadView gamepadView;
    private List<String> lastTelemetry = List.of();

    /**
     * robotFiles/robotFile/switchRobot: the robot configs to pick from, this robot's, and what to
     * do when another is picked (relaunch). busy: true while any robot runs or a match is on.
     */
    RobotPanel(Simulation sim, SimRobot robot, OpModeRunner runner, SimRunner simRunner, OpModeRegistry.Result opModes,
               PhysicalGamepads gamepads, BooleanSupplier keyboardHere, BooleanSupplier busy, BooleanSupplier autoTimer,
               List<Path> robotFiles, Path robotFile, Function<Path, String> fileLabel, Consumer<Path> switchRobot,
               Consumer<String> log) {
        super(new BorderLayout(0, 12));
        this.sim = sim;
        this.robot = robot;
        this.runner = runner;
        this.simRunner = simRunner;
        this.busy = busy;
        this.autoTimer = autoTimer;
        this.log = log;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
        String name = "Robot " + robot.number;

        // Which robot, and where it starts.
        robotBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                return super.getListCellRendererComponent(list, value instanceof Path p ? fileLabel.apply(p) : value, index, sel, focus);
            }
        });
        for (Path p : robotFiles) robotBox.addItem(p);
        if (!robotFiles.contains(robotFile)) robotBox.insertItemAt(robotFile, 0);
        robotBox.setSelectedItem(robotFile);
        robotBox.setToolTipText("The partner's robot config (or robot package, with its own code)");
        robotBox.getAccessibleContext().setAccessibleName(name + " robot");
        robotBox.addActionListener(e -> {
            Path p = (Path) robotBox.getSelectedItem();
            if (p != null && !p.equals(robotFile)) switchRobot.accept(p);
        });
        for (FieldConfig.StartPosition sp : sim.fieldConfig.startPositions()) startBox.addItem(sp);
        // A start the field config doesn't list (the robot was put beside the first one).
        if (robot.startPosition() != null && !sim.fieldConfig.startPositions().contains(robot.startPosition())) {
            startBox.addItem(robot.startPosition());
        }
        startBox.setSelectedItem(robot.startPosition());
        startBox.setToolTipText("Start on the other alliance to practice against each other");
        startBox.getAccessibleContext().setAccessibleName(name + " start position");
        startBox.addActionListener(e -> {
            FieldConfig.StartPosition sp = (FieldConfig.StartPosition) startBox.getSelectedItem();
            if (sp == null || sp.equals(robot.startPosition())) return;
            if (busy.getAsBoolean()) {
                log.accept("Stop the OpModes or match before changing " + name + "'s start position.");
                startBox.setSelectedItem(robot.startPosition());
                return;
            }
            sim.setStartPosition(robot, sp);
            selectForAlliance();
        });

        // The Driver Station: the clock and what's happening, the OpMode, Init → Start → Stop.
        JPanel clock = new JPanel(new BorderLayout(10, 0));
        clock.setOpaque(false);
        timerLabel.getAccessibleContext().setAccessibleName(name + " clock");
        clock.add(timerLabel, BorderLayout.WEST);
        JPanel words = new JPanel(new java.awt.GridLayout(0, 1, 0, 0));
        words.setOpaque(false);
        words.add(stateLabel);
        words.add(loopLabel);
        clock.add(words, BorderLayout.CENTER);
        opModeBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                String text = value instanceof OpModeInfo i ? i.label() : "(no OpModes found)";
                return super.getListCellRendererComponent(list, text, index, sel, focus);
            }
        });
        opModeBox.setToolTipText(name + "'s OpMode");
        opModeBox.getAccessibleContext().setAccessibleName(name + " OpMode");
        for (OpModeInfo info : opModes.opModes()) opModeBox.addItem(info);
        selectForAlliance();
        JPanel buttons = new JPanel(new BorderLayout(6, 0));
        buttons.setOpaque(false);
        buttons.add(runButton, BorderLayout.CENTER);
        buttons.add(stopButton, BorderLayout.EAST);
        errorArea.setLineWrap(true);
        errorArea.setWrapStyleWord(true);
        errorArea.getAccessibleContext().setAccessibleName(name + " OpMode error");
        errorBanner = Ui.alert(errorArea);
        errorBanner.setVisible(false);

        JPanel top = Ui.stack(8);
        top.add(Ui.form(name, robotBox, "Starts at", startBox));
        top.add(Ui.heading("Driver Station"));
        top.add(clock);
        top.add(opModeBox);
        top.add(buttons);
        top.add(errorBanner);
        add(top, BorderLayout.NORTH);

        telemetryArea = Ui.text(MONO.deriveFont(13f), "Telemetry from " + name + "'s OpMode shows here.");
        telemetryArea.getAccessibleContext().setAccessibleName(name + " telemetry");
        JPanel telemetry = new JPanel(new BorderLayout(0, 4));
        telemetry.setOpaque(false);
        telemetry.add(Ui.heading("Telemetry"), BorderLayout.NORTH);
        JScrollPane telemetryScroll = Ui.scroll(telemetryArea);
        telemetryScroll.setMinimumSize(new Dimension(100, 100));
        telemetryScroll.setPreferredSize(new Dimension(300, 150));
        telemetry.add(telemetryScroll, BorderLayout.CENTER);
        gamepadView = new GamepadView(runner.gamepad1, runner.gamepad2, gamepads, 2 * (robot.number - 1), keyboardHere, name + " · ");
        JPanel pads = new JPanel(new BorderLayout(0, 4));
        pads.setOpaque(false);
        pads.add(Ui.heading("Controllers"), BorderLayout.NORTH);
        pads.add(gamepadView, BorderLayout.CENTER);
        add(telemetry, BorderLayout.CENTER);
        add(pads, BorderLayout.SOUTH);

        runButton.addActionListener(e -> RunButtons.press(runner.state(), () -> {
            OpModeInfo info = (OpModeInfo) opModeBox.getSelectedItem();
            if (info == null) return;
            runner.setAutoTimer(autoTimer.getAsBoolean());
            runner.init(info);
        }, runner::start, runner::stop));
        stopButton.addActionListener(e -> runner.stop());
        runner.addListener(() -> SwingUtilities.invokeLater(this::onStateChange));
        onStateChange();
    }

    /**
     * A label kept in the theme's colors and the text size: a bold mono clock of scale points, or
     * (bold, or plain in the secondary color) text at scale times the default size.
     */
    private static JLabel styled(String text, double scale, boolean mono, boolean secondary) {
        JLabel l = new JLabel(text) {
            @Override
            public void updateUI() {
                super.updateUI();
                Font base = javax.swing.UIManager.getFont("defaultFont");
                if (base == null) base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
                setFont(mono ? org.ftctestbed.viewer.platform.Os.mono(Font.BOLD, (int) Math.round(scale * Appearance.textScale()))
                        : base.deriveFont(secondary ? Font.PLAIN : Font.BOLD, (float) (base.getSize2D() * scale)));
                setForeground(secondary ? Appearance.palette().secondary() : Appearance.palette().text());
            }
        };
        l.updateUI();
        return l;
    }

    /** The OpMode chosen here, if it's a TeleOp (for the sandbox), else the first TeleOp. */
    OpModeInfo teleOp() {
        if (opModeBox.getSelectedItem() instanceof OpModeInfo i && i.teleOp()) return i;
        for (int k = 0; k < opModeBox.getItemCount(); k++) if (opModeBox.getItemAt(k).teleOp()) return opModeBox.getItemAt(k);
        return null;
    }

    /** Pre-select an OpMode whose name mentions this robot's alliance (e.g. "Auto Blue"). */
    void selectForAlliance() {
        String a = robot.alliance();
        if (a == null) return;
        String other = a.equals("red") ? "blue" : "red";
        Object sel = opModeBox.getSelectedItem();
        if (!(sel instanceof OpModeInfo i && i.name().toLowerCase(Locale.ROOT).contains(other))) return;
        for (int k = 0; k < opModeBox.getItemCount(); k++) {
            if (opModeBox.getItemAt(k).name().toLowerCase(Locale.ROOT).contains(a)) {
                opModeBox.setSelectedIndex(k);
                return;
            }
        }
    }

    /** Every UI frame. */
    void tick() {
        if (gamepadView.isShowing()) gamepadView.refresh();
        List<String> lines = runner.telemetry().publishedLines();
        if (lines != lastTelemetry) {
            lastTelemetry = lines;
            telemetryArea.setText(String.join("\n", lines));
        }
        // The start can change from the first robot's panel too (two robots never share a start).
        FieldConfig.StartPosition sp = robot.startPosition();
        if (sp != null && !sp.equals(startBox.getSelectedItem()) && !startBox.isPopupVisible()) {
            if (((javax.swing.DefaultComboBoxModel<FieldConfig.StartPosition>) startBox.getModel()).getIndexOf(sp) < 0) startBox.addItem(sp);
            startBox.setSelectedItem(sp);
            selectForAlliance();
        }
        double loopMs = simRunner.robotLoopMillis(robot);
        loopLabel.setText(Double.isNaN(loopMs) ? " " : String.format(Locale.US, "Loop %.1f ms (%.0f a second)", loopMs, 1000 / loopMs));
        OpModeRunner.State st = runner.state();
        double matchClock = sim.match().clock(sim.clock.seconds());
        if (sim.match().inMatch() && !Double.isNaN(matchClock)) {
            double c = Math.max(0, matchClock);
            timerLabel.setText(String.format(Locale.US, "%d:%02d", (int) Math.ceil(c) / 60, (int) Math.ceil(c) % 60));
            stateLabel.setText("Match " + sim.match().phase() + (runner.current() != null ? " · " + runner.current().name() : ""));
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
        switch (st) {
            case IDLE -> stateLabel.setText("Pick an OpMode, then Init");
            case INIT -> stateLabel.setText("Initialized · press Start");
            case RUNNING -> stateLabel.setText("Running " + name);
            case STOPPING -> stateLabel.setText("Stopping " + name + "…");
            case STOPPED -> stateLabel.setText("Stopped " + name);
        }
        boolean idle = st == OpModeRunner.State.IDLE || st == OpModeRunner.State.STOPPED;
        RunButtons.show(runButton, stopButton, st, opModeBox.getItemCount() > 0, false);
        opModeBox.setEnabled(idle);
        String err = runner.error() != null ? runner.error() : runner.warning();
        errorArea.setText(err != null ? err : "");
        if (errorBanner.isVisible() != (err != null)) {
            errorBanner.setVisible(err != null);
            revalidate();
        }
        // After an AUTO, line up the TeleOp it names, as the Driver Station does.
        if (st == OpModeRunner.State.STOPPED && cur != null && cur.autonomous() && !cur.preselectTeleOp().isEmpty()) {
            for (int i = 0; i < opModeBox.getItemCount(); i++) {
                if (opModeBox.getItemAt(i).name().equals(cur.preselectTeleOp())) opModeBox.setSelectedIndex(i);
            }
        }
    }
}
