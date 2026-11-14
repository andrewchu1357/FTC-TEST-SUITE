package org.ftctestbed.viewer.platform;

import javax.swing.KeyStroke;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What differs between the systems the app runs on: which key starts a shortcut and how
 * shortcuts are written, the fixed-width font, and what the system is called in the window.
 *
 * Shortcuts are written once, the way a Mac shows them ("⇧⌘R"), and turned into each system's
 * own here: ⌘ is Ctrl on Windows and Linux, and the few that use ⌥ get a key of their own there
 * (Ctrl+Alt is the AltGr key on many keyboards, so Windows apps stay away from it).
 */
public final class Os {
    private static final String NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    public static final boolean WINDOWS = NAME.contains("win");
    public static final boolean MAC = NAME.contains("mac");

    private Os() {}

    /** The system's name in labels such as "Automatic (follow Windows)". */
    public static String system() {
        return MAC ? "macOS" : WINDOWS ? "Windows" : "the system";
    }

    /** ⌘ on a Mac, Ctrl elsewhere. */
    public static int menuMask() {
        return MAC ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
    }

    /** Mac shortcuts that would need Ctrl+Alt elsewhere, and what they are there instead. */
    private static final Map<String, String> ELSEWHERE = new LinkedHashMap<>();

    static {
        ELSEWHERE.put("⌥⌘R", "F5");            // Reload Robot: the refresh key
        ELSEWHERE.put("⌥⌘P", "Ctrl+Shift+P");  // Step
        ELSEWHERE.put("⌥⌘I", "Ctrl+Shift+I");  // Driver Station pane
        ELSEWHERE.put("⌃⌘L", "Ctrl+Shift+L");  // Show Page Names
    }

    /** A shortcut written the Mac way ("⇧⌘R", "⌘↩", "⌘,"), as this system writes it ("Ctrl+Shift+R"). */
    public static String keys(String mac) {
        return keys(mac, MAC);
    }

    static String keys(String mac, boolean onMac) {
        if (onMac) return mac;
        String other = ELSEWHERE.get(mac);
        if (other != null) return other;
        StringBuilder b = new StringBuilder();
        if (mac.indexOf('⌘') >= 0 || mac.indexOf('⌃') >= 0) b.append("Ctrl+");
        if (mac.indexOf('⌥') >= 0) b.append("Alt+");
        if (mac.indexOf('⇧') >= 0) b.append("Shift+");
        String key = mac.replaceAll("[⌃⌥⇧⌘]", "");
        return b.append(key.equals("↩") ? "Enter" : key).toString();
    }

    /** The keystroke for a shortcut written the Mac way, on this system. */
    public static KeyStroke stroke(String mac) {
        return stroke(mac, MAC);
    }

    static KeyStroke stroke(String mac, boolean onMac) {
        String s = onMac ? mac : keys(mac, false);
        int mods = 0;
        String key;
        if (onMac) {
            if (s.indexOf('⌘') >= 0) mods |= InputEvent.META_DOWN_MASK;
            if (s.indexOf('⌃') >= 0) mods |= InputEvent.CTRL_DOWN_MASK;
            if (s.indexOf('⌥') >= 0) mods |= InputEvent.ALT_DOWN_MASK;
            if (s.indexOf('⇧') >= 0) mods |= InputEvent.SHIFT_DOWN_MASK;
            key = s.replaceAll("[⌃⌥⇧⌘]", "");
        } else {
            String[] parts = s.split("\\+");
            for (int i = 0; i < parts.length - 1; i++) {
                switch (parts[i]) {
                    case "Ctrl" -> mods |= InputEvent.CTRL_DOWN_MASK;
                    case "Alt" -> mods |= InputEvent.ALT_DOWN_MASK;
                    case "Shift" -> mods |= InputEvent.SHIFT_DOWN_MASK;
                    default -> throw new IllegalArgumentException(mac);
                }
            }
            key = parts[parts.length - 1];
        }
        return KeyStroke.getKeyStroke(keyCode(key, mac), mods);
    }

    private static int keyCode(String key, String shortcut) {
        if (key.equals("↩") || key.equals("Enter")) return KeyEvent.VK_ENTER;
        if (key.equals(",")) return KeyEvent.VK_COMMA;
        if (key.equals(".")) return KeyEvent.VK_PERIOD;
        if (key.matches("F\\d{1,2}")) return KeyEvent.VK_F1 + Integer.parseInt(key.substring(1)) - 1;
        if (key.length() == 1) {
            char c = Character.toUpperCase(key.charAt(0));
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) return c; // VK_A..VK_Z and VK_0..VK_9 are the characters
        }
        throw new IllegalArgumentException("Not a shortcut: " + shortcut);
    }

    private static final Pattern SHORTCUT = Pattern.compile("[⌃⌥⇧⌘]+(?:F\\d{1,2}|[A-Za-z0-9,.↩])");

    /** Text with shortcuts in it written the Mac way (a tooltip, a message), as this system writes them. */
    public static String text(String withMacShortcuts) {
        return text(withMacShortcuts, MAC);
    }

    static String text(String withMacShortcuts, boolean onMac) {
        if (onMac || withMacShortcuts == null) return withMacShortcuts;
        Matcher m = SHORTCUT.matcher(withMacShortcuts);
        StringBuilder b = new StringBuilder();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(keys(m.group(), false)));
        return m.appendTail(b).toString();
    }

    private static String monoFamily;

    /**
     * The fixed-width font for code, telemetry and the console: Menlo on a Mac, Cascadia Mono
     * (Windows 11) or Consolas on Windows, DejaVu Sans Mono on Linux. Java's own "Monospaced" is
     * Courier New on Windows, and a font the system doesn't have turns into a proportional one.
     */
    public static synchronized String monoFamily() {
        if (monoFamily == null) {
            String[] wanted = MAC ? new String[]{"Menlo", "Monaco"}
                    : WINDOWS ? new String[]{"Cascadia Mono", "Consolas"}
                    : new String[]{"DejaVu Sans Mono", "Liberation Mono", "Noto Sans Mono"};
            monoFamily = Font.MONOSPACED;
            try {
                for (String f : wanted) {
                    // A font the system doesn't have comes back as Dialog (asking for one name is
                    // far quicker than listing every installed font).
                    if (new Font(f, Font.PLAIN, 12).getFamily(Locale.ROOT).equalsIgnoreCase(f)) {
                        monoFamily = f;
                        break;
                    }
                }
            } catch (RuntimeException | Error e) {
                // no fonts to ask about (an unusual headless setup): Java's Monospaced
            }
        }
        return monoFamily;
    }

    /**
     * The fixed-width font in a style (Font.PLAIN, Font.BOLD) and size, for telemetry, the console
     * and readouts. On a Mac it stays Java's own Monospaced, as these always were there.
     */
    public static Font mono(int style, float size) {
        return new Font(MAC ? Font.MONOSPACED : monoFamily(), style, Math.round(size)).deriveFont(size);
    }
}
