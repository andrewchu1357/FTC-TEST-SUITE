package org.ftctestbed.viewer.ui;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

/**
 * When the Fluent controls draw their focus ring: once Tab has been used to move around, until the
 * next click (like :focus-visible on the web). Keyboard users always see where they are; a window
 * that just opened, or a control just clicked, doesn't wear a ring.
 */
public final class FocusRing {
    private static volatile boolean keyboard;
    private static boolean installed;

    private FocusRing() {}

    /** Starts watching for Tab presses and clicks (once; before the first window opens). */
    static synchronized void install() {
        if (installed || GraphicsEnvironment.isHeadless()) return;
        installed = true;
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            if (e instanceof KeyEvent k && k.getID() == KeyEvent.KEY_PRESSED && k.getKeyCode() == KeyEvent.VK_TAB) set(true);
            else if (e instanceof MouseEvent m && m.getID() == MouseEvent.MOUSE_PRESSED) set(false);
        }, AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
    }

    /** True if c has the keyboard focus and the ring should show it. */
    public static boolean shows(Component c) {
        return keyboard && c.isFocusOwner();
    }

    private static void set(boolean on) {
        if (keyboard == on) return;
        keyboard = on;
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (owner != null) owner.repaint();
    }
}
