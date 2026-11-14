package org.ftctestbed.viewer.demo;

import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.game.MatchController;
import org.ftctestbed.sim.game.MatchState;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRunner;
import org.ftctestbed.viewer.ViewerMain;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * FTC Test Suite Demo: the full simulator, opening in a self-running showcase.
 * BuzzBot plays its AUTO from alternating alliances while the 3D camera moves between shots.
 * A controller button or a key hands the robot to the visitor (free driving); a click just stops
 * the show so they can look around. After two quiet minutes the showcase starts again.
 *
 * It drives the normal app window from the outside, so the real app's code stays as it is.
 */
public final class DemoMain {
    private static final String[] STARTS = {"Red Wall", "Blue Wall"};
    private static final long IDLE_MS = 120_000;
    private static final double ROUND_TIMEOUT_S = 45;

    private enum Mode { WAITING, SHOW, HANDS_ON }

    private static JFrame window;
    private static Mode mode = Mode.WAITING;
    private static int round;
    private static long roundStart, nextRoundAt, lastInput = System.currentTimeMillis();
    private static boolean sawAuto;
    private static String shot = "";
    private static Banner banner;

    public static void main(String[] args) {
        ViewerMain.main(args);
        // The window appears after the team-code build.
        new Timer(250, e -> {
            if (window == null) {
                window = findWindow();
                if (window == null) return;
                setUp();
            }
            tick();
        }).start();
    }

    private static JFrame findWindow() {
        for (Frame f : Frame.getFrames()) {
            if (f.getClass().getSimpleName().equals("MainWindow") && f.isShowing()) return (JFrame) f;
        }
        return null;
    }

    private static void setUp() {
        window.setExtendedState(window.getExtendedState() | Frame.MAXIMIZED_BOTH);
        call("showView", "3D");
        banner = new Banner();
        JLayeredPane layers = window.getLayeredPane();
        layers.add(banner, JLayeredPane.POPUP_LAYER);
        layers.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                banner.place();
            }
        });
        Toolkit.getDefaultToolkit().addAWTEventListener(DemoMain::onInput,
                AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_WHEEL_EVENT_MASK);
        nextRoundAt = System.currentTimeMillis() + 1500;
        mode = Mode.SHOW;
        banner.place();
    }

    // ------------------------------------------------------------------ the showcase

    private static void tick() {
        long now = System.currentTimeMillis();
        if (controllerActive()) {
            lastInput = now;
            if (mode == Mode.SHOW) takeOver(true);
        }
        switch (mode) {
            case SHOW -> showTick(now);
            case HANDS_ON -> {
                if (now - lastInput > IDLE_MS) {
                    stopEverything();
                    mode = Mode.SHOW;
                    nextRoundAt = now + 2500;
                    banner.setVisible(true);
                    camera("Stands");
                }
            }
            default -> { }
        }
    }

    private static void showTick(long now) {
        MatchController match = get("match");
        Simulation sim = get("sim");
        if (nextRoundAt > 0) {
            if (now < nextRoundAt || busy()) return;
            nextRoundAt = 0;
            startRound(sim);
            return;
        }
        double t = (now - roundStart) / 1000.0;
        MatchState.Phase phase = sim.match().phase();
        if (phase == MatchState.Phase.AUTO) sawAuto = true;
        // The AUTO is over (or never got going): show the stands and the score, then the next round.
        if ((sawAuto && phase != MatchState.Phase.AUTO) || t > ROUND_TIMEOUT_S) {
            if (match.running()) call("abortMatch");
            camera("Stands");
            nextRoundAt = now + 5000;
            return;
        }
        // The camera moves between shots during the AUTO.
        String alliance = round % 2 == 1 ? "Red" : "Blue";
        camera(t < 11 ? "Follow robot" : t < 19 ? "Audience" : t < 25 ? alliance + " driver" : "Follow robot");
    }

    private static void startRound(Simulation sim) {
        String start = STARTS[round % STARTS.length];
        round++;
        for (FieldConfig.StartPosition sp : sim.fieldConfig.startPositions()) {
            if (sp.name().equals(start)) {
                JComboBox<?> startBox = get("startBox");
                startBox.setSelectedItem(sp); // the window moves the robot and picks that alliance's OpModes
            }
        }
        String alliance = start.startsWith("Red") ? "Red" : "Blue";
        select(get("matchAutoBox"), "BuzzBot Auto " + alliance);
        JComboBox<?> tele = get("matchTeleBox");
        if (tele.getItemCount() > 0) tele.setSelectedIndex(0); // (no TELEOP): the show ends with the AUTO
        sawAuto = false;
        roundStart = System.currentTimeMillis();
        shot = "";
        camera("Follow robot");
        call("startMatch");
    }

    /** A visitor took over: stop the show; a controller or a key starts free driving straight away. */
    private static void takeOver(boolean drive) {
        mode = Mode.HANDS_ON;
        nextRoundAt = 0;
        banner.setVisible(false);
        stopEverything();
        if (!drive) return;
        // Drive once the show's OpMode has stopped.
        Timer wait = new Timer(150, null);
        long until = System.currentTimeMillis() + 5000;
        wait.addActionListener(e -> {
            if (mode != Mode.HANDS_ON) { wait.stop(); return; }
            if (busy() && System.currentTimeMillis() < until) return;
            wait.stop();
            camera("Follow robot");
            call("enterSandbox");
        });
        wait.start();
    }

    private static void stopEverything() {
        MatchController match = get("match");
        if (Boolean.TRUE.equals(get("sandbox"))) call("leaveSandbox");
        else if (match.running()) call("abortMatch");
        for (OpModeRunner r : DemoMain.<List<OpModeRunner>>get("runners")) r.stop();
    }

    private static boolean busy() {
        MatchController match = get("match");
        if (match.busy() || match.running()) return true;
        for (OpModeRunner r : DemoMain.<List<OpModeRunner>>get("runners")) {
            OpModeRunner.State st = r.state();
            if (st == OpModeRunner.State.INIT || st == OpModeRunner.State.RUNNING) return true;
        }
        return false;
    }

    private static void camera(String name) {
        if (name.equals(shot)) return;
        shot = name;
        call("setCameraPreset", name);
    }

    // ------------------------------------------------------------------ input

    private static void onInput(AWTEvent e) {
        if (window == null) return;
        int id = e.getID();
        if (id == MouseEvent.MOUSE_MOVED) { // only keeps the hands-on time going
            if (mode == Mode.HANDS_ON) lastInput = System.currentTimeMillis();
            return;
        }
        boolean key = id == KeyEvent.KEY_PRESSED, click = id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_WHEEL
                || id == MouseEvent.MOUSE_DRAGGED;
        if (!key && !click) return;
        lastInput = System.currentTimeMillis();
        if (mode != Mode.SHOW) return;
        if (key) {
            KeyEvent k = (KeyEvent) e;
            if (k.isMetaDown() || isModifier(k.getKeyCode())) return;
        }
        // Keys drive the robot (the window's keyboard gamepad); a click only stops the show.
        takeOver(key);
    }

    private static boolean isModifier(int code) {
        return code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_ALT || code == KeyEvent.VK_META;
    }

    /** True while any connected controller has a button down or a stick or trigger pushed. */
    private static boolean controllerActive() {
        Object pads = get("gamepads");
        for (int slot = 0; slot < 4; slot++) {
            Object pad = invoke(pads, "assigned", slot);
            if (pad == null) continue;
            com.studiohartman.jamepad.ControllerState c = invoke(pad, "state");
            if (c == null || !c.isConnected) continue;
            if (c.a || c.b || c.x || c.y || c.lb || c.rb || c.start || c.back || c.guide
                    || c.dpadUp || c.dpadDown || c.dpadLeft || c.dpadRight || c.leftStickClick || c.rightStickClick
                    || c.leftTrigger > 0.3f || c.rightTrigger > 0.3f
                    || Math.abs(c.leftStickX) > 0.4f || Math.abs(c.leftStickY) > 0.4f
                    || Math.abs(c.rightStickX) > 0.4f || Math.abs(c.rightStickY) > 0.4f) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the window, from outside

    private static void select(JComboBox<?> box, String name) {
        for (int i = 0; i < box.getItemCount(); i++) {
            if (box.getItemAt(i) instanceof OpModeInfo info && info.name().equals(name)) box.setSelectedIndex(i);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(String field) {
        try {
            Field f = window.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return (T) f.get(window);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Demo: the window has no field " + field, e);
        }
    }

    private static void call(String method, Object... args) {
        invoke(window, method, args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Object target, String method, Object... args) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(method) || m.getParameterCount() != args.length) continue;
                try {
                    m.setAccessible(true);
                    return (T) m.invoke(target, args);
                } catch (ReflectiveOperationException e) {
                    System.err.println("Demo: " + method + " failed: " + e);
                    return null;
                }
            }
        }
        throw new IllegalStateException("Demo: no method " + method + " on " + target.getClass().getName());
    }

    /** The "how to take over" pill at the bottom of the window while the show runs. */
    private static final class Banner extends JComponent {
        private static final String TEXT = "DEMO  ·  Press any controller button (or a key) to drive  ·  Click to look around";
        private final Font font = new Font(Font.SANS_SERIF, Font.BOLD, 17);

        Banner() {
            setOpaque(false);
        }

        void place() {
            SwingUtilities.invokeLater(() -> {
                FontMetrics fm = getFontMetrics(font);
                Dimension d = new Dimension(fm.stringWidth(TEXT) + 48, fm.getHeight() + 22);
                JLayeredPane layers = window.getLayeredPane();
                setBounds((layers.getWidth() - d.width) / 2, layers.getHeight() - d.height - 36, d.width, d.height);
                repaint();
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(0, 0, 0, 170));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g.setFont(font);
            g.setColor(Color.WHITE);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(TEXT, (getWidth() - fm.stringWidth(TEXT)) / 2, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    private DemoMain() {}
}
