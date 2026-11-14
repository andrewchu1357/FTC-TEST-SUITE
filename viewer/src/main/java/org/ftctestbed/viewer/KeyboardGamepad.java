package org.ftctestbed.viewer;

import com.qualcomm.robotcore.hardware.Gamepad;

import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Drives gamepad1 from the keyboard. Sticks ramp up over ~0.1 s instead of jumping, and
 * holding Shift halves stick deflection for fine control.
 */
public final class KeyboardGamepad {
    /** The Controls tab's guide to the keys and controller buttons (HTML). partner: a second robot is on the field. */
    public static String help(boolean partner) {
        // Short lines: the guide has to fit the side panel's width.
        StringBuilder h = new StringBuilder("<html>");
        h.append(heading("Keyboard → gamepad1"));
        if (partner) h.append(note("of the robot picked above the field<br>(Keyboard → Robot 1 / Robot 2)"));
        h.append(table(new String[][]{
                {"W A S D", "left stick"},
                {"Q / E", "right stick X (turn)"},
                {"R / F", "right stick Y"},
                {"Arrows", "D-pad"},
                {"I J K L", "y x a b"},
                {"U / O", "left / right bumper"},
                {"Z / C", "left / right trigger"},
                {"1 / 2", "left / right stick button"},
                {"Enter", "start"},
                {"Backspace", "back"},
                {"G", "guide"},
                {"Shift", "half-speed sticks"}}));
        h.append(note("Keys reach the robot while an OpMode is in<br>"
                + "INIT or running and the window has focus.<br>"
                + "Typing in the Code tab never drives it."));

        h.append(heading("Controllers: PS5, PS4 or Xbox"));
        h.append(note(partner
                ? "The first controller drives Robot 1, the<br>second Robot 2. To move a controller, hold<br>Options (Start on Xbox) and press:"
                : "The first controller is gamepad1, the second<br>gamepad2. To swap them, hold Options<br>(Start on Xbox) and press:"));
        List<String[]> combos = new ArrayList<>(List.of(
                new String[]{"Cross ✕ (A)", partner ? "Robot 1's gamepad1" : "gamepad1"},
                new String[]{"Circle ○ (B)", partner ? "Robot 1's gamepad2" : "gamepad2"}));
        if (partner) {
            combos.add(new String[]{"Square □ (X)", "Robot 2's gamepad1"});
            combos.add(new String[]{"Triangle △ (Y)", "Robot 2's gamepad2"});
        }
        h.append(table(combos.toArray(new String[0][])));

        h.append(heading("PlayStation buttons in FTC code"));
        h.append(table(new String[][]{
                {"Cross ✕  Circle ○", "a  b"},
                {"Square □  Triangle △", "x  y"},
                {"Options / Create", "start / back"},
                {"PS / Touchpad click", "guide / touchpad"},
                {"L1 R1 / L2 R2", "bumpers / triggers"},
                {"L3 R3", "stick buttons"}}));
        h.append(note("USB or Bluetooth. A controller works even when<br>"
                + "the window doesn't have focus, and rumble from<br>"
                + "the OpMode plays on it. With the keyboard and a<br>"
                + "controller at once, the larger stick value wins."));
        return h.append("</html>").toString();
    }

    private static String heading(String text) {
        return "<p style='margin-top: 8px; margin-bottom: 3px'><b>" + text + "</b></p>";
    }

    private static String note(String text) {
        return "<p style='margin-top: 4px; color: #5f6368'>" + text + "</p>";
    }

    /** Two columns: what to press (bold), what it does. */
    private static String table(String[][] rows) {
        StringBuilder t = new StringBuilder("<table cellpadding='1' cellspacing='0'>");
        for (String[] r : rows) {
            t.append("<tr><td style='padding-right: 14px'><b>").append(r[0]).append("</b></td><td>").append(r[1]).append("</td></tr>");
        }
        return t.append("</table>").toString();
    }

    private static final double RAMP_PER_SECOND = 10.0;

    private final Set<Integer> down = new HashSet<>();
    private final BooleanSupplier active;
    private final java.awt.KeyEventDispatcher dispatcher;
    private double lx, ly, rx, ry, lt, rt;
    private boolean shift;

    public KeyboardGamepad(BooleanSupplier active) {
        this.active = active;
        dispatcher = e -> {
            if (!active.getAsBoolean()) {
                synchronized (down) {
                    down.clear();
                }
                return false;
            }
            // Shortcuts (⌘R, ⌘E..., Ctrl+R on Windows) aren't gamepad keys, and neither are Windows' Alt+letter menu keys.
            boolean menuKey = !org.ftctestbed.viewer.platform.Os.MAC && e.isAltDown();
            if ((e.isMetaDown() || e.isControlDown() || menuKey) && e.getID() == KeyEvent.KEY_PRESSED) return false;
            int code = e.getKeyCode();
            if (code == KeyEvent.VK_SHIFT) shift = e.getID() == KeyEvent.KEY_PRESSED;
            if (!isMapped(code)) return false;
            synchronized (down) {
                if (e.getID() == KeyEvent.KEY_PRESSED) down.add(code);
                else if (e.getID() == KeyEvent.KEY_RELEASED) down.remove(code);
            }
            return true;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
    }

    /** Stops listening to the keyboard (the window is closing). */
    public void close() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher);
    }

    private static boolean isMapped(int c) {
        return switch (c) {
            case KeyEvent.VK_W, KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D, KeyEvent.VK_Q, KeyEvent.VK_E,
                 KeyEvent.VK_R, KeyEvent.VK_F, KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT,
                 KeyEvent.VK_I, KeyEvent.VK_J, KeyEvent.VK_K, KeyEvent.VK_L, KeyEvent.VK_U, KeyEvent.VK_O,
                 KeyEvent.VK_Z, KeyEvent.VK_C, KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_ENTER,
                 KeyEvent.VK_BACK_SPACE, KeyEvent.VK_G -> true;
            default -> false;
        };
    }

    private boolean is(int code) {
        return down.contains(code);
    }

    private static double approach(double current, double target, double dt) {
        double step = RAMP_PER_SECOND * dt;
        if (Math.abs(target - current) <= step) return target;
        return current + Math.signum(target - current) * step;
    }

    /** Builds the next gamepad state and applies it (with edge detection) to the OpMode's gamepad. */
    public void update(Gamepad target, double dt) {
        Gamepad s = state(dt);
        s.id = target.id;
        target.simApply(s);
    }

    /** The next gamepad state from the keys held down (sticks ramp over time, so call once per frame). */
    public Gamepad state(double dt) {
        Gamepad s = new Gamepad();
        synchronized (down) {
            if (!active.getAsBoolean()) down.clear();
            double max = shift ? 0.5 : 1.0;
            lx = approach(lx, ((is(KeyEvent.VK_D) ? 1 : 0) - (is(KeyEvent.VK_A) ? 1 : 0)) * max, dt);
            ly = approach(ly, ((is(KeyEvent.VK_S) ? 1 : 0) - (is(KeyEvent.VK_W) ? 1 : 0)) * max, dt);
            rx = approach(rx, ((is(KeyEvent.VK_E) ? 1 : 0) - (is(KeyEvent.VK_Q) ? 1 : 0)) * max, dt);
            ry = approach(ry, ((is(KeyEvent.VK_F) ? 1 : 0) - (is(KeyEvent.VK_R) ? 1 : 0)) * max, dt);
            lt = approach(lt, is(KeyEvent.VK_Z) ? 1 : 0, dt);
            rt = approach(rt, is(KeyEvent.VK_C) ? 1 : 0, dt);
            s.left_stick_x = (float) lx;
            s.left_stick_y = (float) ly;
            s.right_stick_x = (float) rx;
            s.right_stick_y = (float) ry;
            s.left_trigger = (float) lt;
            s.right_trigger = (float) rt;
            s.dpad_up = is(KeyEvent.VK_UP);
            s.dpad_down = is(KeyEvent.VK_DOWN);
            s.dpad_left = is(KeyEvent.VK_LEFT);
            s.dpad_right = is(KeyEvent.VK_RIGHT);
            s.y = is(KeyEvent.VK_I);
            s.x = is(KeyEvent.VK_J);
            s.a = is(KeyEvent.VK_K);
            s.b = is(KeyEvent.VK_L);
            s.left_bumper = is(KeyEvent.VK_U);
            s.right_bumper = is(KeyEvent.VK_O);
            s.left_stick_button = is(KeyEvent.VK_1);
            s.right_stick_button = is(KeyEvent.VK_2);
            s.start = is(KeyEvent.VK_ENTER);
            s.back = is(KeyEvent.VK_BACK_SPACE);
            s.guide = is(KeyEvent.VK_G);
        }
        return s;
    }
}
