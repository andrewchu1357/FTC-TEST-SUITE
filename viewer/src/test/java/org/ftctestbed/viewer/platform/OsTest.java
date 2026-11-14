package org.ftctestbed.viewer.platform;

import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OsTest {
    /** Every shortcut the window and the Code view use, written the Mac way. */
    private static final List<String> SHORTCUTS = List.of("⌘O", "⌥⌘R", "⌘I", "⌘↩", "⌘.", "⇧⌘M", "⇧⌘D", "⌘R", "⌘1", "⌘2", "⌘3",
            "⌘E", "⌥⌘I", "⌘,", "⌃⌘L", "⌘P", "⌥⌘P", "⇧⌘R", "⌘S", "⌘N", "⌘F", "⌘G", "⌘W");

    @Test
    void onAMacShortcutsStayAsWritten() {
        assertEquals("⇧⌘R", Os.keys("⇧⌘R", true));
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), Os.stroke("⇧⌘R", true));
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.META_DOWN_MASK | InputEvent.ALT_DOWN_MASK), Os.stroke("⌥⌘R", true));
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.META_DOWN_MASK | InputEvent.CTRL_DOWN_MASK), Os.stroke("⌃⌘L", true));
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK), Os.stroke("⌘↩", true));
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, InputEvent.META_DOWN_MASK), Os.stroke("⌘,", true));
        assertEquals("Pause the simulation (⌘P)", Os.text("Pause the simulation (⌘P)", true));
    }

    @Test
    void onWindowsCommandIsControl() {
        assertEquals("Ctrl+R", Os.keys("⌘R", false));
        assertEquals("Ctrl+Shift+R", Os.keys("⇧⌘R", false));
        assertEquals("Ctrl+Enter", Os.keys("⌘↩", false));
        assertEquals("Ctrl+,", Os.keys("⌘,", false));
        assertEquals("Ctrl+.", Os.keys("⌘.", false));
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK), Os.stroke("⌘R", false));
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), Os.stroke("⇧⌘M", false));
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), Os.stroke("⌘↩", false));
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_2, InputEvent.CTRL_DOWN_MASK), Os.stroke("⌘2", false));
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, InputEvent.CTRL_DOWN_MASK), Os.stroke("⌘.", false));
    }

    @Test
    void onWindowsNothingNeedsControlAlt() {
        // Ctrl+Alt is the AltGr key on many keyboards: those shortcuts get keys of their own.
        assertEquals("F5", Os.keys("⌥⌘R", false));
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), Os.stroke("⌥⌘R", false));
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), Os.stroke("⌥⌘P", false));
        for (String s : SHORTCUTS) {
            KeyStroke k = Os.stroke(s, false);
            int ctrlAlt = InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK;
            assertNotEquals(ctrlAlt, k.getModifiers() & ctrlAlt, s + " is " + Os.keys(s, false));
            assertEquals(0, k.getModifiers() & InputEvent.META_DOWN_MASK, s + " would need the Windows key");
        }
    }

    @Test
    void noTwoShortcutsShareAKey() {
        for (boolean mac : new boolean[]{true, false}) {
            Map<KeyStroke, String> seen = new HashMap<>();
            for (String s : SHORTCUTS) {
                String other = seen.put(Os.stroke(s, mac), s);
                assertNull(other, s + " and " + other + " are both " + Os.keys(s, mac));
            }
        }
    }

    @Test
    void shortcutsInTextAreRewritten() {
        assertEquals("Pause the simulation (Ctrl+P)", Os.text("Pause the simulation (⌘P)", false));
        assertEquals("Edit the robot's code and run it (Ctrl+3; Ctrl+E switches back)",
                Os.text("Edit the robot's code and run it (⌘3; ⌘E switches back)", false));
        assertEquals("Find in this file (Ctrl+F, then Enter / Ctrl+G for the next match)",
                Os.text("Find in this file (⌘F, then Enter / ⌘G for the next match)", false));
        assertEquals("Reload (F5) or step (Ctrl+Shift+P); start with Ctrl+Enter, stop with Ctrl+.",
                Os.text("Reload (⌥⌘R) or step (⌥⌘P); start with ⌘↩, stop with ⌘.", false));
        assertEquals("no shortcuts here", Os.text("no shortcuts here", false));
        assertNull(Os.text(null, false));
    }

    @Test
    void theFixedWidthFontIsFixedWidth() {
        Font f = Os.mono(Font.PLAIN, 12);
        assertEquals(12f, f.getSize2D());
        var frc = new java.awt.font.FontRenderContext(null, true, true);
        assertEquals(f.getStringBounds("iiii", frc).getWidth(), f.getStringBounds("MMMM", frc).getWidth(), 0.01,
                f.getFamily() + " isn't fixed-width");
        if (Os.WINDOWS) assertNotEquals("Dialog", new Font(Os.monoFamily(), Font.PLAIN, 12).getFamily(), "the code font must exist");
    }
}
