package org.ftctestbed.viewer;

import com.qualcomm.robotcore.hardware.Gamepad;

import org.ftctestbed.viewer.ui.Appearance;
import org.ftctestbed.viewer.ui.Contrast;
import org.ftctestbed.viewer.ui.Fluent;
import org.ftctestbed.viewer.ui.Theme;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Live picture of gamepad1 and gamepad2 exactly as the OpMode sees them (sticks, triggers,
 * buttons), plus which controller feeds each one, its LED color and rumble. The controllers are
 * drawn dark, as they are; the words around them and pressed buttons take the theme's colors,
 * and the glass shows through around them.
 */
final class GamepadView extends JComponent {
    // On the controller body (always dark).
    private static final Color BODY = new Color(0x2B2E33), OFF = new Color(0x55595F), TEXT = new Color(0xE6E8EA);
    private static final int ROW = 150;

    private final Gamepad[] gamepads;
    private final PhysicalGamepads physical;
    private final int firstSlot;
    private final java.util.function.BooleanSupplier keyboardHere;
    private final String robot;

    GamepadView(Gamepad gamepad1, Gamepad gamepad2, PhysicalGamepads physical) {
        this(gamepad1, gamepad2, physical, 0, () -> true, "");
    }

    /**
     * One robot's gamepads. firstSlot: its gamepad1's controller slot (0 or 2); keyboardHere:
     * true while the keyboard drives it; robot: "Robot 2 · " in front of each name, or "".
     */
    GamepadView(Gamepad gamepad1, Gamepad gamepad2, PhysicalGamepads physical, int firstSlot,
                java.util.function.BooleanSupplier keyboardHere, String robot) {
        this.gamepads = new Gamepad[]{gamepad1, gamepad2};
        this.physical = physical;
        this.firstSlot = firstSlot;
        this.keyboardHere = keyboardHere;
        this.robot = robot;
        setOpaque(false);
    }

    private String shown;

    /** Repaints if anything drawn changed since the last time (called many times a second). */
    void refresh() {
        String now = state();
        if (now.equals(shown)) return;
        shown = now;
        repaint();
    }

    /** Everything the picture shows, as text. */
    private String state() {
        StringBuilder b = new StringBuilder(physical.status()).append('|').append(keyboardHere.getAsBoolean());
        for (int i = 0; i < 2; i++) {
            Gamepad gp = gamepads[i];
            PhysicalGamepads.Pad pad = physical.assigned(firstSlot + i);
            b.append('|').append(pad == null ? "-" : pad.label() + pad.type())
                    .append(' ').append(gp.left_stick_x).append(' ').append(gp.left_stick_y)
                    .append(' ').append(gp.right_stick_x).append(' ').append(gp.right_stick_y)
                    .append(' ').append(gp.left_trigger).append(' ').append(gp.right_trigger)
                    .append(' ').append(gp.a).append(gp.b).append(gp.x).append(gp.y)
                    .append(gp.dpad_up).append(gp.dpad_down).append(gp.dpad_left).append(gp.dpad_right)
                    .append(gp.left_bumper).append(gp.right_bumper).append(gp.left_stick_button).append(gp.right_stick_button)
                    .append(gp.start).append(gp.back).append(gp.guide).append(gp.touchpad)
                    .append(java.util.Arrays.toString(gp.simRumble())).append(java.util.Arrays.toString(gp.simGetLedColor()));
        }
        return b.toString();
    }

    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) return super.getPreferredSize();
        return new Dimension(420, ROW * 2 + (int) Math.round(52 * Appearance.textScale()));
    }

    /** Pressed buttons and trigger fills: the theme's accent, made bright enough to stand out on the dark body. */
    private static Color lit(Theme.Palette p) {
        return Contrast.readableOn(p.accent(), BODY, 3.0);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        double s = Appearance.textScale();
        for (int i = 0; i < 2; i++) drawPad(g, i, 6 + i * ROW);
        g.setColor(Appearance.palette().secondary());
        g.setFont(getFont().deriveFont((float) (11 * s)));
        // One line per robot (and one for unassigned controllers), so a partner's doesn't run off the edge.
        String[] lines = physical.status().replace("   (", "\n(").split("   ·   |\n");
        for (int k = 0; k < lines.length; k++) g.drawString(lines[k], 10, ROW * 2 + (int) Math.round((20 + 15 * k) * s));
        g.dispose();
    }

    private void drawPad(Graphics2D g, int index, int top) {
        Gamepad gp = gamepads[index];
        PhysicalGamepads.Pad pad = physical.assigned(firstSlot + index);
        boolean sony = pad != null ? pad.type() == Gamepad.Type.SONY_PS4 || pad.type() == Gamepad.Type.SONY_PS4_SUPPORTED_BY_KERNEL
                : true; // label with PlayStation symbols by default, the controller most teams use
        int w = getWidth();
        Theme.Palette p = Appearance.palette();
        g.setFont(getFont().deriveFont(Font.BOLD, (float) (12 * Appearance.textScale())));
        g.setColor(p.text());
        String source = pad != null ? pad.label() : index == 0 && keyboardHere.getAsBoolean() ? "keyboard" : "no controller";
        // LED swatch and rumble readout (top right); the name is shortened to end before them.
        double[] rumble = gp.simRumble();
        boolean rumbling = rumble[0] > 0 || rumble[1] > 0;
        g.drawString(fit(g, robot + "gamepad" + (index + 1) + "  ·  " + source, (rumbling ? w - 196 : w - 56) - 10), 10, top + 14);
        double[] led = gp.simGetLedColor();
        g.setColor(new Color((float) led[0], (float) led[1], (float) led[2]));
        g.fillRoundRect(w - 46, top + 4, 36, 12, 6, 6);
        if (rumbling) {
            g.setColor(Contrast.readableOn(p.warn(), Fluent.card(Appearance.theme()), Contrast.TEXT));
            g.drawString(String.format("rumble %.0f%% / %.0f%%", rumble[0] * 100, rumble[1] * 100), w - 190, top + 14);
        }

        int cx = w / 2, cy = top + 82;
        g.setColor(BODY);
        g.fillRoundRect(cx - 170, cy - 52, 340, 104, 60, 60);

        // Triggers and bumpers
        bar(g, cx - 160, cy - 64, gp.left_trigger, sony ? "L2" : "LT");
        bar(g, cx + 100, cy - 64, gp.right_trigger, sony ? "R2" : "RT");
        pill(g, cx - 150, cy - 46, gp.left_bumper, sony ? "L1" : "LB");
        pill(g, cx + 110, cy - 46, gp.right_bumper, sony ? "R1" : "RB");

        // D-pad
        int dx = cx - 120, dy = cy - 8;
        button(g, dx, dy - 18, 14, gp.dpad_up, "");
        button(g, dx, dy + 18, 14, gp.dpad_down, "");
        button(g, dx - 18, dy, 14, gp.dpad_left, "");
        button(g, dx + 18, dy, 14, gp.dpad_right, "");

        // Face buttons
        int fx = cx + 120, fy = cy - 8;
        button(g, fx, fy - 20, 16, gp.y, sony ? "\u25B3" : "Y");
        button(g, fx + 20, fy, 16, gp.b, sony ? "\u25EF" : "B");
        button(g, fx, fy + 20, 16, gp.a, sony ? "\u2715" : "A");
        button(g, fx - 20, fy, 16, gp.x, sony ? "\u25A1" : "X");

        // Center: share/back, touchpad, options/start, guide
        pill(g, cx - 86, cy - 38, gp.back, sony ? "Create" : "Back");
        pill(g, cx + 34, cy - 38, gp.start, sony ? "Options" : "Start");
        g.setColor(gp.touchpad ? lit(p) : OFF);
        g.fillRoundRect(cx - 28, cy - 44, 56, 26, 8, 8);
        button(g, cx, cy + 4, 12, gp.guide, sony ? "PS" : "G");

        // Sticks
        stick(g, cx - 56, cy + 26, gp.left_stick_x, gp.left_stick_y, gp.left_stick_button);
        stick(g, cx + 56, cy + 26, gp.right_stick_x, gp.right_stick_y, gp.right_stick_button);
        g.setFont(getFont().deriveFont(10f));
        g.setColor(p.secondary());
        g.drawString(String.format("L (%.2f, %.2f)", gp.left_stick_x, gp.left_stick_y), cx - 165, cy + 62);
        g.drawString(String.format("R (%.2f, %.2f)", gp.right_stick_x, gp.right_stick_y), cx + 85, cy + 62);
    }

    /** The text, cut short with "…" if it's wider than width. */
    private static String fit(Graphics2D g, String text, int width) {
        java.awt.FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(text) <= width) return text;
        int n = text.length();
        while (n > 0 && fm.stringWidth(text.substring(0, n) + "…") > width) n--;
        return text.substring(0, n).stripTrailing() + "…";
    }

    private void button(Graphics2D g, int x, int y, int r, boolean on, String label) {
        Color lit = lit(Appearance.palette());
        g.setColor(on ? lit : OFF);
        g.fillOval(x - r / 2 - 2, y - r / 2 - 2, r + 4, r + 4);
        if (!label.isEmpty()) {
            g.setColor(on ? Contrast.textOn(lit) : TEXT);
            g.setFont(getFont().deriveFont(Font.BOLD, label.length() == 1 ? 12f : 10f));
            int tw = g.getFontMetrics().stringWidth(label);
            g.drawString(label, x - tw / 2, y + 4);
        }
    }

    private void pill(Graphics2D g, int x, int y, boolean on, String label) {
        g.setFont(getFont().deriveFont(Font.BOLD, 10f));
        int tw = g.getFontMetrics().stringWidth(label) + 12;
        Color lit = lit(Appearance.palette());
        g.setColor(on ? lit : OFF);
        g.fillRoundRect(x, y, Math.max(40, tw), 14, 10, 10);
        g.setColor(on ? Contrast.textOn(lit) : TEXT);
        g.drawString(label, x + (Math.max(40, tw) - tw) / 2 + 6, y + 11);
    }

    private void bar(Graphics2D g, int x, int y, float value, String label) {
        Theme.Palette p = Appearance.palette();
        g.setColor(Contrast.alpha(p.secondary(), 0.35));
        g.fillRoundRect(x, y, 60, 10, 6, 6);
        g.setColor(p.accent());
        g.fillRoundRect(x, y, Math.round(60 * Math.max(0, Math.min(1, value))), 10, 6, 6);
        g.setColor(p.secondary());
        g.setFont(getFont().deriveFont(10f));
        g.drawString(label, x + 64, y + 9);
    }

    private void stick(Graphics2D g, int x, int y, float sx, float sy, boolean pressed) {
        int r = 20;
        g.setColor(new Color(0x16181B));
        g.fillOval(x - r, y - r, 2 * r, 2 * r);
        g.setColor(OFF);
        g.setStroke(new BasicStroke(1f));
        g.drawOval(x - r, y - r, 2 * r, 2 * r);
        int px = x + Math.round(sx * (r - 6)), py = y + Math.round(sy * (r - 6)); // +y is down, as in FTC
        g.setColor(pressed ? lit(Appearance.palette()) : new Color(0xB0B4B8));
        g.fillOval(px - 7, py - 7, 14, 14);
    }
}
