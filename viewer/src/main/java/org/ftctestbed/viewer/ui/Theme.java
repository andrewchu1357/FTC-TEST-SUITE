package org.ftctestbed.viewer.ui;

import java.awt.Color;
import java.util.Map;

/**
 * The app's color themes. Each has a FlatLaf look and feel for the standard controls and a
 * palette for the Fluent surfaces, buttons, editor and views drawn by the app itself. Text
 * colors stay readable on every surface and on acrylic over anything (see Contrast, Fluent and
 * Acrylic). Fluent Light and Dark use Windows 11's own colors.
 */
public enum Theme {
    FLUENT_LIGHT("Fluent Light", false, "com.formdev.flatlaf.FlatLightLaf",
            Map.of("@accentColor", "#005FB8", "@background", "#F3F3F3", "@foreground", "#1B1B1B",
                    "@selectionBackground", "#CCE1F4", "@selectionForeground", "#1B1B1B", "@menuSelectionBackground", "#E6E6E6"),
            new Palette(c(0xF3F3F3), c(0xF9F9F9, 0.70), c(0x1B1B1B), c(0x5C5C5C), c(0x005FB8), c(0x0F7B0F), c(0xC42B1C), c(0x9D5D00),
                    c(0x000000, 0.08), c(0xFFFFFF, 0.70), c(0xE9EAEE),
                    new Code("idea", c(0xFFFFFF), c(0x1B1B1B), c(0x0000FF), c(0xA31515), c(0x008000), c(0x098658), c(0x267F99), c(0x795E26),
                            c(0xADD6FF), c(0xF5F5F5), c(0x000000), c(0xF7F7F7), c(0x6E7681)))),
    FLUENT_DARK("Fluent Dark", true, "com.formdev.flatlaf.FlatDarkLaf",
            Map.of("@accentColor", "#60CDFF", "@background", "#202020", "@foreground", "#FFFFFF",
                    "@selectionBackground", "#1D4F6E", "@selectionForeground", "#FFFFFF", "@menuSelectionBackground", "#383838",
                    "CheckBox.icon.checkmarkColor", "#000000"),
            new Palette(c(0x202020), c(0x2C2C2C, 0.70), c(0xFFFFFF), c(0xCFCFCF), c(0x60CDFF), c(0x6CCB5F), c(0xFF99A4), c(0xFCE100),
                    c(0xFFFFFF, 0.09), c(0xFFFFFF, 0.09), c(0x15161A),
                    new Code("dark", c(0x1E1E1E), c(0xD4D4D4), c(0x569CD6), c(0xCE9178), c(0x6A9955), c(0xB5CEA8), c(0x4EC9B0), c(0xDCDCAA),
                            c(0x264F78), c(0x2A2A2A), c(0xAEAFAD), c(0x1E1E1E), c(0x858585)))),
    GRUVBOX_DARK("Gruvbox Dark", true, "com.formdev.flatlaf.intellijthemes.FlatGruvboxDarkMediumIJTheme", Map.of(),
            new Palette(c(0x282828), c(0x282828, 0.62), c(0xEBDBB2), c(0xD5C4A1), c(0xFABD2F), c(0xB8BB26), c(0xFB4934), c(0xFE8019),
                    c(0xEBDBB2, 0.16), c(0xFBF1C7, 0.22), c(0x1D2021),
                    new Code("dark", c(0x282828), c(0xEBDBB2), c(0xFB4934), c(0xB8BB26), c(0x928374), c(0xD3869B), c(0xFABD2F), c(0x8EC07C),
                            c(0x504945), c(0x32302F), c(0xEBDBB2), c(0x282828), c(0x7C6F64)))),
    GRUVBOX_LIGHT("Gruvbox Light", false, "com.formdev.flatlaf.FlatLightLaf",
            Map.of("@background", "#fbf1c7", "@foreground", "#3c3836", "@accentColor", "#af3a03",
                    "@selectionBackground", "#d5c4a1", "@selectionForeground", "#282828", "@componentBackground", "#f9f5d7"),
            new Palette(c(0xFBF1C7), c(0xFBF1C7, 0.70), c(0x282828), c(0x504945), c(0xAF3A03), c(0x79740E), c(0x9D0006), c(0xB57614),
                    c(0x3C3836, 0.18), c(0xFFFFFF, 0.70), c(0xEBDBB2),
                    new Code("idea", c(0xFBF1C7), c(0x3C3836), c(0x9D0006), c(0x79740E), c(0x7C6F64), c(0x8F3F71), c(0xB57614), c(0x427B58),
                            c(0xD5C4A1), c(0xF2E5BC), c(0x3C3836), c(0xEBDBB2), c(0x7C6F64)))),
    NORD("Nord", true, "com.formdev.flatlaf.intellijthemes.FlatNordIJTheme", Map.of(),
            new Palette(c(0x2E3440), c(0x2E3440, 0.60), c(0xECEFF4), c(0xD8DEE9), c(0x88C0D0), c(0xA3BE8C), c(0xBF616A), c(0xEBCB8B),
                    c(0xECEFF4, 0.15), c(0xECEFF4, 0.24), c(0x242933),
                    new Code("dark", c(0x2E3440), c(0xD8DEE9), c(0x81A1C1), c(0xA3BE8C), c(0x8690A0), c(0xB48EAD), c(0x8FBCBB), c(0xD08770),
                            c(0x434C5E), c(0x3B4252), c(0xECEFF4), c(0x2E3440), c(0x7B88A1)))),
    DRACULA("Dracula", true, "com.formdev.flatlaf.intellijthemes.FlatDraculaIJTheme", Map.of(),
            new Palette(c(0x282A36), c(0x282A36, 0.62), c(0xF8F8F2), c(0xE2E2DC), c(0xBD93F9), c(0x50FA7B), c(0xFF5555), c(0xFFB86C),
                    c(0xF8F8F2, 0.15), c(0xF8F8F2, 0.24), c(0x1E1F29),
                    new Code("dark", c(0x282A36), c(0xF8F8F2), c(0xFF79C6), c(0xF1FA8C), c(0x7E8BB8), c(0xBD93F9), c(0x8BE9FD), c(0x50FA7B),
                            c(0x44475A), c(0x343746), c(0xF8F8F2), c(0x282A36), c(0x7E8BB8)))),
    SOLARIZED_LIGHT("Solarized Light", false, "com.formdev.flatlaf.intellijthemes.FlatSolarizedLightIJTheme", Map.of(),
            new Palette(c(0xFDF6E3), c(0xFDF6E3, 0.70), c(0x073642), c(0x3E555C), c(0x2075B5), c(0x5F6E00), c(0xC1271F), c(0x8A6900),
                    c(0x073642, 0.16), c(0xFFFFFF, 0.70), c(0xEEE8D5),
                    new Code("idea", c(0xFDF6E3), c(0x3E555C), c(0x5F6E00), c(0x1F7A73), c(0x7A8A8F), c(0xAD3E14), c(0x1F6CA8), c(0x6C71C4),
                            c(0xE6DFC6), c(0xF3EDD9), c(0x073642), c(0xEEE8D5), c(0x7A8A8F)))),
    SOLARIZED_DARK("Solarized Dark", true, "com.formdev.flatlaf.intellijthemes.FlatSolarizedDarkIJTheme", Map.of(),
            new Palette(c(0x002B36), c(0x002B36, 0.62), c(0xEEE8D5), c(0xBFC6C3), c(0x3D9BE0), c(0x9DB000), c(0xF0544F), c(0xD1A000),
                    c(0xEEE8D5, 0.14), c(0xEEE8D5, 0.20), c(0x00212B),
                    new Code("dark", c(0x002B36), c(0x93A1A1), c(0x859900), c(0x2AA198), c(0x6B8A93), c(0xD33682), c(0x268BD2), c(0xCB4B16),
                            c(0x0C4A5A), c(0x073642), c(0xEEE8D5), c(0x002B36), c(0x6B8A93)))),
    ONE_DARK("One Dark", true, "com.formdev.flatlaf.intellijthemes.FlatOneDarkIJTheme", Map.of(),
            new Palette(c(0x282C34), c(0x282C34, 0.60), c(0xE6E9EF), c(0xC3C9D3), c(0x61AFEF), c(0x98C379), c(0xE06C75), c(0xE5C07B),
                    c(0xE6E9EF, 0.14), c(0xFFFFFF, 0.22), c(0x1F2228),
                    new Code("dark", c(0x282C34), c(0xABB2BF), c(0xC678DD), c(0x98C379), c(0x7F848E), c(0xD19A66), c(0xE5C07B), c(0x61AFEF),
                            c(0x3E4451), c(0x2C313A), c(0x528BFF), c(0x282C34), c(0x636D83)))),
    MONOKAI("Monokai Pro", true, "com.formdev.flatlaf.intellijthemes.FlatMonokaiProIJTheme", Map.of(),
            new Palette(c(0x2D2A2E), c(0x2D2A2E, 0.62), c(0xFCFCFA), c(0xD7D6D4), c(0xFFD866), c(0xA9DC76), c(0xFF6188), c(0xFC9867),
                    c(0xFCFCFA, 0.14), c(0xFFFFFF, 0.20), c(0x221F22),
                    new Code("monokai", c(0x2D2A2E), c(0xFCFCFA), c(0xFF6188), c(0xFFD866), c(0x8C8A8C), c(0xAB9DF2), c(0x78DCE8), c(0xA9DC76),
                            c(0x5B595C), c(0x363337), c(0xFCFCFA), c(0x2D2A2E), c(0x8C8A8C)))),
    HIGH_CONTRAST("High Contrast", true, "com.formdev.flatlaf.intellijthemes.FlatHighContrastIJTheme", Map.of(),
            new Palette(c(0x000000), c(0x000000, 1.0), c(0xFFFFFF), c(0xFFFFFF), c(0x1AEBFF), c(0x3DFF7A), c(0xFF6B6B), c(0xFFD60A),
                    c(0xFFFFFF, 1.0), c(0xFFFFFF, 0.0), c(0x000000),
                    new Code("dark", c(0x000000), c(0xFFFFFF), c(0xFF8BFF), c(0x8BFF8B), c(0xC8C8C8), c(0xFFD60A), c(0x7FE9FF), c(0xFFB86B),
                            c(0x1F4FFF), c(0x1A1A1A), c(0xFFFFFF), c(0x000000), c(0xC8C8C8))));

    /**
     * The colors the app draws with. window: the base (Mica) the window is made of; the layers and
     * cards on it are derived from it (see Fluent). acrylic: the tint of acrylic surfaces (menus,
     * the open navigation pane); its alpha is the least opaque they get. hairline: strokes and
     * dividers. highlight: the light top edge of controls.
     */
    public record Palette(Color window, Color acrylic, Color text, Color secondary, Color accent, Color go, Color stop, Color warn,
                          Color hairline, Color highlight, Color field, Code code) {}

    /** The Code tab's editor: base RSyntaxTextArea theme plus colors for each kind of token. */
    public record Code(String base, Color background, Color text, Color keyword, Color string, Color comment, Color number,
                       Color type, Color annotation, Color selection, Color currentLine, Color caret, Color gutter, Color gutterText) {}

    public final String label;
    public final boolean dark;
    final String laf;
    final Map<String, String> lafDefaults;
    public final Palette palette;

    Theme(String label, boolean dark, String laf, Map<String, String> lafDefaults, Palette palette) {
        this.label = label;
        this.dark = dark;
        this.laf = laf;
        this.lafDefaults = lafDefaults;
        this.palette = palette;
    }

    /** A theme saved by an older version under another name (Liquid Glass became Fluent), or null. */
    static Theme renamed(String name) {
        return switch (name) {
            case "GLASS_LIGHT" -> FLUENT_LIGHT;
            case "GLASS_DARK" -> FLUENT_DARK;
            default -> null;
        };
    }

    /** True for the theme meant for low vision: opaque surfaces, thick outlines. */
    public boolean highContrast() {
        return this == HIGH_CONTRAST;
    }

    @Override
    public String toString() {
        return label;
    }

    private static Color c(int rgb) {
        return new Color(rgb);
    }

    private static Color c(int rgb, double alpha) {
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, (int) Math.round(alpha * 255));
    }
}
