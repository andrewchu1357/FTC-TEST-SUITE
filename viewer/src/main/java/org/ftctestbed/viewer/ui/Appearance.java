package org.ftctestbed.viewer.ui;

import com.formdev.flatlaf.FlatLaf;
import org.ftctestbed.viewer.platform.Os;
import org.ftctestbed.viewer.platform.Win32;

import javax.swing.JRootPane;
import javax.swing.LookAndFeel;
import javax.swing.RootPaneContainer;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.InsetsUIResource;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;

/**
 * How the app looks: the theme (or Automatic, which follows the system's light/dark: macOS or Windows), transparency
 * effects (acrylic menus and panes; Automatic follows macOS's Reduce Transparency or Windows'
 * Transparency effects), and the text size. Saved between runs; changes apply to every open window at once.
 */
public final class Appearance {
    public enum Transparency {
        AUTOMATIC("Automatic (follow " + Os.system() + ")"), REDUCED("Off: solid surfaces"), FULL("On");

        public final String label;

        Transparency(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** How often the field views draw: Automatic is Efficient on battery or in Low Power Mode, else Smooth. */
    public enum FrameRate {
        AUTOMATIC("Automatic (30 on battery)"), SMOOTH("Smooth: 60 a second"), EFFICIENT("Efficient: 30 a second");

        public final String label;

        FrameRate(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Text sizes: the factor applied to every font, and what the setting is called. */
    public static final double[] TEXT_SCALES = {1.0, 1.15, 1.3};
    public static final String[] TEXT_SIZES = {"Default", "Large", "Larger"};

    private static final Preferences PREFS = Preferences.userNodeForPackage(Appearance.class);
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static Theme choice;          // null: Automatic
    private static Theme current = Theme.FLUENT_LIGHT;
    private static Transparency transparency = Transparency.AUTOMATIC;
    private static FrameRate frameRate = FrameRate.AUTOMATIC;
    private static boolean systemOnBattery, systemLowPower;
    private static int textSize;
    private static boolean systemDark, systemReduceTransparency, systemIncreaseContrast, systemHighContrast;
    private static long systemReadAt;
    private static Font baseFont;

    private Appearance() {}

    /** Reads the saved settings and macOS's, and sets the look up (before the first window opens). */
    public static void init() {
        FocusRing.install();
        readSystem();
        choice = parseTheme(PREFS.get("theme", "auto"));
        try {
            transparency = Transparency.valueOf(PREFS.get("transparency", "AUTOMATIC"));
        } catch (IllegalArgumentException e) {
            transparency = Transparency.AUTOMATIC;
        }
        textSize = Math.max(0, Math.min(TEXT_SCALES.length - 1, PREFS.getInt("textSize", 0)));
        try {
            frameRate = FrameRate.valueOf(PREFS.get("frameRate", "AUTOMATIC"));
        } catch (IllegalArgumentException e) {
            frameRate = FrameRate.AUTOMATIC;
        }
        apply();
    }

    /** The theme in use (Automatic resolved to light or dark). */
    public static Theme theme() {
        return current;
    }

    /** The theme picked: null for Automatic. */
    public static Theme choice() {
        return choice;
    }

    public static Theme.Palette palette() {
        return current.palette;
    }

    public static Transparency transparency() {
        return transparency;
    }

    public static FrameRate frameRate() {
        return frameRate;
    }

    /** Draw the field views 30 times a second (chosen, or Automatic on battery or in Low Power Mode). */
    public static boolean efficient() {
        return efficient(frameRate, systemOnBattery, systemLowPower);
    }

    static boolean efficient(FrameRate f, boolean onBattery, boolean lowPower) {
        return f == FrameRate.EFFICIENT || (f == FrameRate.AUTOMATIC && (onBattery || lowPower));
    }

    public static void setFrameRate(FrameRate f) {
        frameRate = f;
        PREFS.put("frameRate", f.name());
        notifyListeners();
    }

    public static int textSize() {
        return textSize;
    }

    public static double textScale() {
        return TEXT_SCALES[textSize];
    }

    /** Solid surfaces instead of acrylic: asked for here or in macOS (Accessibility > Display), or the high contrast theme. */
    public static boolean reducedTransparency() {
        return current.highContrast() || transparency == Transparency.REDUCED
                || (transparency == Transparency.AUTOMATIC && systemReduceTransparency);
    }

    /** macOS's Increase Contrast, or the high contrast theme: outlines on every surface. */
    public static boolean increasedContrast() {
        return current.highContrast() || systemIncreaseContrast;
    }

    public static void setTheme(Theme t) {
        choice = t;
        PREFS.put("theme", t == null ? "auto" : t.name());
        apply();
    }

    /** Uses a theme for this run without remembering it (debug and screenshot options). */
    public static void preview(Theme t) {
        choice = t;
        apply();
    }

    public static void setTransparency(Transparency t) {
        transparency = t;
        PREFS.put("transparency", t.name());
        notifyListeners();
    }

    public static void setTextSize(int size) {
        textSize = Math.max(0, Math.min(TEXT_SCALES.length - 1, size));
        PREFS.putInt("textSize", textSize);
        apply();
    }

    /** Runs whenever the look changes (after every window's controls have been updated). */
    public static void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    public static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    /** How many listeners are registered (tests: a closed window must leave none behind). */
    static int listenerCount() {
        return LISTENERS.size();
    }

    /**
     * Matches a window's title bar to the theme: macOS's traffic lights and menus light or dark;
     * on Windows the title bar itself (drawn in the window, with the menus in it) in the theme's
     * Mica color, so it runs into the navigation rail below it as one surface.
     */
    public static void decorate(JRootPane root) {
        root.putClientProperty("apple.awt.windowAppearance", current.dark ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua");
        if (Os.WINDOWS) {
            root.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TITLE_BAR_BACKGROUND, Fluent.mica(current));
            root.putClientProperty(com.formdev.flatlaf.FlatClientProperties.TITLE_BAR_FOREGROUND, current.palette.text());
        }
    }

    /** Checks macOS's dark mode, accessibility and power settings again (they may have changed); applies them if so. */
    public static void refreshSystem() {
        if (System.nanoTime() - systemReadAt < 3_000_000_000L) return;
        boolean dark = systemDark, rt = systemReduceTransparency, ic = systemIncreaseContrast, hc = systemHighContrast, efficient = efficient();
        readSystem();
        if ((dark != systemDark || hc != systemHighContrast) && choice == null) apply();
        else if (rt != systemReduceTransparency || ic != systemIncreaseContrast || efficient != efficient()) notifyListeners();
    }

    /** The theme Automatic means: light or dark as the system is, or High Contrast with a Windows contrast theme on. */
    static Theme automatic(boolean dark, boolean highContrast) {
        return highContrast ? Theme.HIGH_CONTRAST : dark ? Theme.FLUENT_DARK : Theme.FLUENT_LIGHT;
    }

    static Theme parseTheme(String name) {
        try {
            return "auto".equals(name) ? null : Theme.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Theme.renamed(name);
        }
    }

    private static void apply() {
        current = choice != null ? choice : automatic(systemDark, systemHighContrast);
        try {
            FlatLaf.setGlobalExtraDefaults(current.lafDefaults.isEmpty() ? null : current.lafDefaults);
            UIManager.setLookAndFeel((LookAndFeel) Class.forName(current.laf).getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
            System.err.println("Couldn't set the " + current.label + " theme: " + e);
        }
        tweak();
        if (!GraphicsEnvironment.isHeadless()) {
            FlatLaf.updateUI();
            for (Window w : Window.getWindows()) if (w instanceof RootPaneContainer rpc) decorate(rpc.getRootPane());
        }
        notifyListeners();
    }

    /**
     * Fluent control shapes and sizes on top of the theme: 4 px corners on controls, 8 px on
     * menus and cards, chevron-only combo boxes, thin scroll bars.
     */
    private static void tweak() {
        UIManager.put("Button.arc", 8);
        UIManager.put("Component.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("CheckBox.arc", 6);
        UIManager.put("ProgressBar.arc", 4);
        UIManager.put("Component.focusWidth", 2);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("ComboBox.buttonStyle", "icon-only");
        UIManager.put("Component.arrowType", "chevron");
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new InsetsUIResource(3, 3, 3, 3));
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("ScrollBar.track", new ColorUIResource(new Color(0, 0, 0, 0)));
        UIManager.put("ScrollPane.smoothScrolling", true);
        UIManager.put("PopupMenu.borderCornerRadius", 8);
        UIManager.put("Popup.borderCornerRadius", 8);
        UIManager.put("ComboBox.borderCornerRadius", 8);
        UIManager.put("MenuItem.selectionArc", 8);
        UIManager.put("MenuItem.selectionInsets", new InsetsUIResource(0, 4, 0, 4));
        // Fluent's rounded, inset highlight on the chosen row of drop-down lists and lists too.
        UIManager.put("ComboBox.selectionArc", 8);
        UIManager.put("ComboBox.selectionInsets", new InsetsUIResource(1, 4, 1, 4));
        UIManager.put("List.selectionArc", 8);
        UIManager.put("List.selectionInsets", new InsetsUIResource(1, 4, 1, 4));
        UIManager.put("MenuItem.margin", new InsetsUIResource(5, 8, 5, 8));
        UIManager.put("ToolTip.borderCornerRadius", 6);
        UIManager.put("Table.showHorizontalLines", false);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.intercellSpacing", new java.awt.Dimension(0, 0));
        // Combo boxes' drop-down lists on acrylic, like the menus.
        UIManager.put("ComboBoxUI", AcrylicComboBoxUI.class.getName());
        Theme.Palette p = current.palette;
        UIManager.put("Component.focusColor", new ColorUIResource(p.accent()));
        UIManager.put("Component.focusedBorderColor", new ColorUIResource(p.accent()));
        if (current.highContrast()) UIManager.put("Component.focusWidth", 3);
        Font f = UIManager.getFont("defaultFont");
        if (f != null) {
            if (baseFont == null || !baseFont.getFamily().equals(f.getFamily())) baseFont = f;
            UIManager.put("defaultFont", new FontUIResource(baseFont.deriveFont((float) (baseFont.getSize2D() * textScale()))));
        }
    }

    private static void notifyListeners() {
        for (Runnable r : LISTENERS) r.run();
    }

    /**
     * The system's dark mode, its "less transparency" and "more contrast" settings, and whether the
     * computer runs on battery or is saving power (all off when they can't be read). macOS: dark
     * mode, Reduce Transparency, Increase Contrast, Low Power Mode. Windows: the app mode in
     * Settings > Personalization > Colors, Transparency effects, a contrast theme, Battery saver.
     */
    private static void readSystem() {
        systemReadAt = System.nanoTime();
        if (GraphicsEnvironment.isHeadless()) return;
        if (Os.WINDOWS) {
            readWindows();
            return;
        }
        if (!Os.MAC) return;
        systemDark = defaults("-g", "AppleInterfaceStyle").equalsIgnoreCase("Dark");
        systemReduceTransparency = defaults("com.apple.universalaccess", "reduceTransparency").equals("1");
        systemIncreaseContrast = defaults("com.apple.universalaccess", "increaseContrast").equals("1");
        systemOnBattery = run("pmset", "-g", "batt").contains("'Battery Power'");
        systemLowPower = run("pmset", "-g").matches("(?s).*\\blowpowermode\\s+1\\b.*");
    }

    /** Windows' settings, asked of Windows directly (no programs are started, so this takes microseconds). */
    private static void readWindows() {
        systemHighContrast = Boolean.TRUE.equals(java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("win.highContrast.on"));
        if (!Win32.available()) return;
        systemDark = Win32.appsUseDarkTheme();
        systemReduceTransparency = !Win32.transparencyEffects();
        Win32.Power power = Win32.power();
        systemOnBattery = power.onBattery();
        systemLowPower = power.batterySaver();
    }

    private static String defaults(String domain, String key) {
        return run("defaults", "read", domain, key);
    }

    /** A command's output, or "" if it fails. */
    private static String run(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (InputStream in = p.getInputStream()) {
                String out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                return p.waitFor() == 0 ? out : "";
            }
        } catch (Exception e) {
            return "";
        }
    }
}
