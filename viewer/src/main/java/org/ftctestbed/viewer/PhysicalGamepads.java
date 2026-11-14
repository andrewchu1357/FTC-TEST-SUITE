package org.ftctestbed.viewer;

import com.qualcomm.robotcore.hardware.Gamepad;
import com.studiohartman.jamepad.Configuration;
import com.studiohartman.jamepad.ControllerManager;
import com.studiohartman.jamepad.ControllerState;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Real controllers (PS5 DualSense, PS4, Xbox, Logitech...) over USB or Bluetooth, read through
 * SDL2 (Jamepad). They are assigned to gamepad1/gamepad2 like on the Driver Station: the first
 * controller to connect becomes gamepad1, the second gamepad2, and Options+Cross (Start+A) or
 * Options+Circle (Start+B) reassigns. With a partner robot the second controller drives robot 2
 * instead, and Options+Square (Start+X) / Options+Triangle (Start+Y) make a controller robot 2's
 * gamepad1 / gamepad2 (see ControllerSlots). Rumble requested by the OpMode is played on the controller.
 *
 * All SDL calls happen on one background thread. One instance is shared by every window.
 */
public final class PhysicalGamepads {
    private static final int MAX = 4;
    private static final long POLL_MS = 8;
    /** With no controller connected, only plugging one in is watched for: four times a second. */
    private static final long IDLE_POLL_MS = 250;
    private static PhysicalGamepads shared;

    /** One connected controller, as last polled. */
    public record Pad(int index, String name, Gamepad.Type type, ControllerState state) {
        public String label() {
            return name + " (" + switch (type) {
                case SONY_PS4, SONY_PS4_SUPPORTED_BY_KERNEL -> "PlayStation";
                case XBOX_360 -> "Xbox";
                case LOGITECH_F310 -> "Logitech";
                default -> "generic";
            } + ")";
        }
    }

    private final Pad[] pads = new Pad[MAX];
    private final ControllerSlots slots = new ControllerSlots();
    private volatile Gamepad[] targets = new Gamepad[ControllerSlots.SLOTS];
    private volatile int keyboardSlot = 0;
    private volatile Consumer<String> log = m -> {};
    private volatile String status = "Looking for controllers…";
    private volatile boolean available;

    public static synchronized PhysicalGamepads shared() {
        if (shared == null) {
            shared = new PhysicalGamepads();
            if (!Boolean.getBoolean("testbed.noGamepads")) shared.start();
            else shared.status = "Physical controllers disabled (testbed.noGamepads)";
        }
        return shared;
    }

    private PhysicalGamepads() {}

    /** The OpMode gamepads that controllers feed (rumble is read back from them). */
    public void attach(Gamepad gamepad1, Gamepad gamepad2, Consumer<String> log) {
        attach(List.<Gamepad[]>of(new Gamepad[]{gamepad1, gamepad2}), log);
    }

    /**
     * Each robot's {gamepad1, gamepad2}, the first robot first. Going from one robot to two (or
     * back) deals the connected controllers out again, so the second one drives the partner.
     */
    public void attach(List<Gamepad[]> robots, Consumer<String> log) {
        Gamepad[] t = new Gamepad[ControllerSlots.SLOTS];
        for (int r = 0; r < Math.min(2, robots.size()); r++) {
            t[2 * r] = robots.get(r)[0];
            t[2 * r + 1] = robots.get(r)[1];
        }
        this.targets = t;
        this.log = log;
        synchronized (this) {
            int before = slots.robots();
            slots.setRobots(robots.size());
            if (slots.robots() != before) {
                for (int s = 0; s < 2 * slots.robots(); s++) {
                    int i = slots.padIn(s);
                    if (i >= 0 && pads[i] != null) log.accept(pads[i].name() + " → " + slots.name(s));
                }
            }
        }
        updateStatus();
    }

    /** Which gamepad the keyboard drives (0: robot 1's gamepad1, 2: robot 2's), for the status line. */
    public void setKeyboardSlot(int slot) {
        keyboardSlot = slot;
        updateStatus();
    }

    public String status() { return status; }

    public boolean available() { return available; }

    /** The controller assigned to a slot (0/1: robot 1's gamepad1/gamepad2, 2/3: robot 2's), or null. */
    public synchronized Pad assigned(int gamepad) {
        int i = slots.padIn(gamepad);
        return i < 0 ? null : pads[i];
    }

    /**
     * Merges the assigned controller into a gamepad state that may already hold keyboard input:
     * larger stick deflection wins, buttons are OR'd. Returns false if no controller is assigned.
     */
    public boolean merge(int gamepad, Gamepad s) {
        Pad pad = assigned(gamepad);
        if (pad == null) return false;
        ControllerState c = pad.state();
        float deadzone = deadzone(pad.type());
        s.left_stick_x = larger(s.left_stick_x, dead(c.leftStickX, deadzone));
        s.left_stick_y = larger(s.left_stick_y, dead(c.leftStickY, deadzone));
        s.right_stick_x = larger(s.right_stick_x, dead(c.rightStickX, deadzone));
        s.right_stick_y = larger(s.right_stick_y, dead(c.rightStickY, deadzone));
        s.left_trigger = Math.max(s.left_trigger, clamp01(c.leftTrigger));
        s.right_trigger = Math.max(s.right_trigger, clamp01(c.rightTrigger));
        // SDL names buttons by position like an Xbox pad: A = bottom = Cross, B = right = Circle...
        s.a |= c.a;
        s.b |= c.b;
        s.x |= c.x;
        s.y |= c.y;
        s.left_bumper |= c.lb;
        s.right_bumper |= c.rb;
        s.dpad_up |= c.dpadUp;
        s.dpad_down |= c.dpadDown;
        s.dpad_left |= c.dpadLeft;
        s.dpad_right |= c.dpadRight;
        s.left_stick_button |= c.leftStickClick;
        s.right_stick_button |= c.rightStickClick;
        s.start |= c.start;   // Options on PlayStation
        s.back |= c.back;     // Share / Create
        s.guide |= c.guide;   // PS button
        s.touchpad |= c.touchpadButton;
        s.type = pad.type();
        return true;
    }

    /**
     * The Driver Station's stick deadzone for a type of controller. The DS (not the robot) applies
     * it, per controller type, and a generic 20% for controllers it doesn't know (SDK release
     * notes 7.1 and 9.2). [ESTIMATE] the per-type values: FIRST doesn't publish them.
     */
    static float deadzone(Gamepad.Type type) {
        return switch (type) {
            case SONY_PS4, SONY_PS4_SUPPORTED_BY_KERNEL -> 0.05f;
            case LOGITECH_F310, XBOX_360 -> 0.1f;
            default -> 0.2f;
        };
    }

    /**
     * Inside the deadzone: 0. Outside, scaled so the stick starts from 0 at the deadzone's edge
     * (no jump to 5-20% power the moment it leaves the deadzone) and reaches 1 at full deflection.
     */
    static float dead(float v, float deadzone) {
        float a = Math.abs(v);
        if (a < deadzone) return 0f;
        return Math.copySign(Math.min(1f, (a - deadzone) / (1f - deadzone)), v);
    }

    private static float larger(float a, float b) {
        return Math.abs(b) > Math.abs(a) ? b : a;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    static Gamepad.Type typeFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("xbox") || n.contains("x-box") || n.contains("xinput")) return Gamepad.Type.XBOX_360;
        if (n.contains("ps5") || n.contains("dualsense") || n.contains("ps4") || n.contains("dualshock")
                || n.contains("playstation") || n.contains("sony")
                // A PS4 DualShock 4 paired over Bluetooth calls itself just "Wireless Controller".
                || n.equals("wireless controller")) return Gamepad.Type.SONY_PS4_SUPPORTED_BY_KERNEL;
        if (n.contains("logitech") || n.contains("f310")) return Gamepad.Type.LOGITECH_F310;
        return Gamepad.Type.UNKNOWN;
    }

    // ------------------------------------------------------------------ polling thread

    private void start() {
        Thread t = new Thread(this::run, "gamepads");
        t.setDaemon(true);
        t.start();
    }

    private void run() {
        ControllerManager cm;
        try {
            Configuration config = new Configuration();
            config.maxNumControllers = MAX;
            cm = new ControllerManager(config);
            cm.initSDLGamepad();
        } catch (Throwable e) {
            status = "Physical controllers unavailable: " + e;
            log.accept(status);
            return;
        }
        available = true;
        status = "No controller connected. Plug one in, or pair it over Bluetooth: PS4 - hold PS + Share, PS5 - hold PS + Create.";
        double[][] lastRumble = new double[MAX][2];
        long[] rumbleSent = new long[MAX];
        boolean[] comboHeld = new boolean[MAX];
        while (true) {
            try {
                cm.update();
                for (int i = 0; i < MAX; i++) poll(cm, i, comboHeld);
                for (int g = 0; g < ControllerSlots.SLOTS; g++) rumble(cm, g, lastRumble, rumbleSent);
                updateStatus();
                boolean any = false;
                for (int i = 0; i < MAX; i++) any |= pads[i] != null;
                Thread.sleep(any ? POLL_MS : IDLE_POLL_MS);
            } catch (InterruptedException e) {
                cm.quitSDLGamepad();
                return;
            } catch (Throwable e) {
                status = "Controller error: " + e;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void poll(ControllerManager cm, int i, boolean[] comboHeld) {
        ControllerState st = cm.getState(i);
        Pad old = pads[i];
        if (!st.isConnected) {
            if (old != null) {
                synchronized (this) {
                    pads[i] = null;
                    slots.disconnected(i);
                }
                log.accept("Controller disconnected: " + old.name());
            }
            return;
        }
        String name = old != null ? old.name() : nameOf(cm, i);
        Pad pad = new Pad(i, name, old != null ? old.type() : typeFor(name), st);
        synchronized (this) {
            pads[i] = pad;
            if (old == null) {
                log.accept("Controller connected: " + pad.label());
                int g = slots.connected(i);
                if (g >= 0) log.accept("  → " + slots.name(g) + " (" + COMBOS[slots.robots() - 1] + " reassigns, like the Driver Station)");
            }
            // Driver Station style assignment: Start+A → gamepad1, Start+B → gamepad2; with a
            // partner, Start+X / Start+Y → robot 2's gamepad1 / gamepad2.
            int combo = !st.start ? -1 : st.a ? 0 : st.b ? 1 : st.x ? 2 : st.y ? 3 : -1;
            if (combo >= 0 && !comboHeld[i] && slots.assign(i, combo)) log.accept(name + " → " + slots.name(combo));
            comboHeld[i] = combo >= 0;
        }
    }

    private static String nameOf(ControllerManager cm, int i) {
        try {
            return cm.getControllerIndex(i).getName();
        } catch (Throwable e) {
            return "Controller " + (i + 1);
        }
    }

    private void rumble(ControllerManager cm, int g, double[][] last, long[] sent) {
        Pad pad = assigned(g);
        Gamepad target = targets[g];
        if (pad == null || target == null) return;
        double[] r = target.simRumble();
        double[] prev = last[pad.index()];
        long now = System.currentTimeMillis();
        boolean changed = Math.abs(r[0] - prev[0]) > 0.02 || Math.abs(r[1] - prev[1]) > 0.02;
        boolean on = r[0] > 0 || r[1] > 0;
        // Re-send every 100 ms with a 150 ms duration, so rumble stops by itself if we stop sending.
        if (changed || (on && now - sent[pad.index()] > 100)) {
            cm.doVibration(pad.index(), (float) r[0], (float) r[1], on ? 150 : 1);
            prev[0] = r[0];
            prev[1] = r[1];
            sent[pad.index()] = now;
        }
    }

    /** How to reassign a controller, with one robot and with two. */
    private static final String[] COMBOS = {"Options+Cross / Options+Circle",
            "Options+Cross / Circle for robot 1, Options+Square / Triangle for robot 2"};

    private synchronized void updateStatus() {
        if (!available) return;
        StringBuilder b = new StringBuilder();
        for (int g = 0; g < 2 * slots.robots(); g++) {
            int i = slots.padIn(g);
            Pad p = i < 0 ? null : pads[i];
            if (g > 0) b.append(g % 2 == 0 ? "   ·   " : "   ");
            if (slots.robots() > 1 && g % 2 == 0) b.append("Robot ").append(g / 2 + 1).append(" ");
            b.append("gamepad").append(g % 2 + 1).append(": ").append(p != null ? p.name() : g == keyboardSlot ? "keyboard" : "none");
        }
        int unassigned = 0;
        for (int i = 0; i < MAX; i++) if (pads[i] != null && !slots.isAssigned(i)) unassigned++;
        if (unassigned > 0) b.append("   (").append(unassigned).append(" unassigned: ").append(COMBOS[slots.robots() - 1]).append(")");
        status = b.toString();
    }
}
