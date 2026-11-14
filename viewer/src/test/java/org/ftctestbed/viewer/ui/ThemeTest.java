package org.ftctestbed.viewer.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.*;

/** Every theme stays readable: text on every surface and on acrylic over anything, labels on buttons, code in the editor. */
class ThemeTest {
    static final Color[] BACKDROPS = {Color.BLACK, Color.WHITE, new Color(0x808080), new Color(0xE53935), new Color(0x1E88E5), new Color(0xF2C200)};

    @Test
    void textOnAcrylicIsReadableOverAnyBackground() {
        for (Theme t : Theme.values()) {
            Theme.Palette p = t.palette;
            for (Color behind : BACKDROPS) {
                double a = Acrylic.requiredAlpha(p, behind);
                Color acrylic = Contrast.over(Contrast.alpha(p.acrylic(), a), behind);
                assertTrue(Contrast.ratio(p.text(), acrylic) >= Contrast.TEXT, t + " text over " + behind + ": " + Contrast.ratio(p.text(), acrylic));
                assertTrue(Contrast.ratio(p.secondary(), acrylic) >= Contrast.TEXT, t + " secondary text over " + behind);
            }
            // Solid acrylic (transparency effects off).
            assertTrue(Contrast.ratio(p.text(), Acrylic.fallback(t)) >= 7, t + " text on solid acrylic");
            assertTrue(Contrast.ratio(p.secondary(), Acrylic.fallback(t)) >= Contrast.TEXT, t + " secondary text on solid acrylic");
        }
    }

    @Test
    void acrylicIsNoMoreOpaqueThanItNeedsToBe() {
        for (Theme t : Theme.values()) {
            if (t.highContrast()) continue;
            double overMatching = Acrylic.requiredAlpha(t.palette, t.dark ? Color.BLACK : Color.WHITE);
            assertEquals(t.palette.acrylic().getAlpha() / 255.0, overMatching, 1e-9, t + ": see-through over matching content");
            assertTrue(overMatching < 0.8, t + " is still see-through: " + overMatching);
        }
    }

    @Test
    void acrylicTintsACapturedBackdropEnoughForText() {
        for (Theme t : Theme.values()) {
            for (Color behind : BACKDROPS) {
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = img.createGraphics();
                g.setColor(behind);
                g.fillRect(0, 0, 8, 8);
                g.setColor(Color.GRAY); // a busier spot in the corner
                g.fillRect(0, 0, 2, 2);
                g.dispose();
                Acrylic.tint(img, t.palette);
                for (int[] xy : new int[][]{{0, 0}, {5, 5}}) {
                    Color shown = new Color(img.getRGB(xy[0], xy[1]));
                    assertTrue(Contrast.ratio(t.palette.secondary(), shown) >= Contrast.TEXT, t + " over " + behind + " at " + xy[0]);
                }
            }
        }
    }

    @Test
    void textIsReadableOnEverySurface() {
        for (Theme t : Theme.values()) {
            Theme.Palette p = t.palette;
            for (Fluent.Material m : Fluent.Material.values()) {
                Color surface = Fluent.fill(t, m);
                assertTrue(Contrast.ratio(p.text(), surface) >= 7, t + " text on " + m + ": " + Contrast.ratio(p.text(), surface));
                assertTrue(Contrast.ratio(p.secondary(), surface) >= Contrast.TEXT, t + " secondary text on " + m + ": " + Contrast.ratio(p.secondary(), surface));
            }
        }
    }

    @Test
    void buttonLabelsAreReadableOnEveryTint() {
        for (Theme t : Theme.values()) {
            Theme.Palette p = t.palette;
            for (Color tint : new Color[]{p.accent(), p.go(), p.stop(), p.warn()}) {
                Color fg = Contrast.textOn(tint);
                Color fill = Contrast.readableBehind(tint, fg, Contrast.TEXT);
                assertTrue(Contrast.ratio(fg, fill) >= Contrast.TEXT, t + " label on " + tint);
                // The fill stays recognizably the theme's color (not pushed to black or white).
                double drift = Math.abs(Contrast.luminance(fill) - Contrast.luminance(tint));
                assertTrue(drift < 0.35, t + " button color kept: " + tint + " -> " + fill);
            }
        }
    }

    @Test
    void theFocusRingAndSelectionPillsStandOut() {
        for (Theme t : Theme.values()) {
            Theme.Palette p = t.palette;
            Color ring = t.highContrast() ? p.warn() : p.text();
            Color pill = t.highContrast() ? p.warn() : p.accent();
            for (Fluent.Material m : Fluent.Material.values()) {
                Color surface = Fluent.fill(t, m);
                assertTrue(Contrast.ratio(ring, surface) >= 3, t + " focus ring on " + m);
                // The pill under the chosen item (selector bars, the navigation rail) is the only sign of it.
                assertTrue(Contrast.ratio(pill, surface) >= 3, t + " selection pill on " + m + ": " + Contrast.ratio(pill, surface));
            }
        }
    }

    @Test
    void codeIsReadableInEveryTheme() {
        for (Theme t : Theme.values()) {
            Theme.Code c = t.palette.code();
            assertTrue(Contrast.ratio(c.text(), c.background()) >= Contrast.TEXT, t + " code text");
            for (Color token : new Color[]{c.keyword(), c.string(), c.comment(), c.number(), c.type(), c.annotation()}) {
                Color shown = Contrast.readableOn(token, c.background(), Contrast.TEXT);
                assertTrue(Contrast.ratio(shown, c.background()) >= Contrast.TEXT, t + " token " + token);
            }
        }
    }

    @Test
    void contrastArithmetic() {
        assertEquals(21, Contrast.ratio(Color.BLACK, Color.WHITE), 1e-9);
        assertEquals(1, Contrast.ratio(Color.GRAY, Color.GRAY), 1e-9);
        assertEquals(new Color(128, 128, 128), Contrast.over(new Color(255, 255, 255, 128), Color.BLACK));
        assertEquals(Color.WHITE, Contrast.textOn(new Color(0x0A84FF).darker()));
        assertEquals(new Color(0x111111), Contrast.textOn(new Color(0xFABD2F)));
    }

    @Test
    void headingsKeepTheirSizeAcrossThemeChanges() {
        javax.swing.JLabel heading = Ui.heading("Telemetry"), note = Ui.note("An explanation");
        float h = heading.getFont().getSize2D(), n = note.getFont().getSize2D();
        for (int i = 0; i < 3; i++) {  // what every theme or text size change does to each control
            heading.updateUI();
            note.updateUI();
        }
        assertEquals(h, heading.getFont().getSize2D(), 0.01, "a heading doesn't shrink each time");
        assertEquals(n, note.getFont().getSize2D(), 0.01);
        assertTrue(heading.getFont().isBold());
    }

    @Test
    void automaticFrameRateSavesPowerOnBatteryAndInLowPowerMode() {
        assertFalse(Appearance.efficient(Appearance.FrameRate.AUTOMATIC, false, false), "on mains power: smooth");
        assertTrue(Appearance.efficient(Appearance.FrameRate.AUTOMATIC, true, false), "on battery");
        assertTrue(Appearance.efficient(Appearance.FrameRate.AUTOMATIC, false, true), "in Low Power Mode");
        assertFalse(Appearance.efficient(Appearance.FrameRate.SMOOTH, true, true), "Smooth stays smooth");
        assertTrue(Appearance.efficient(Appearance.FrameRate.EFFICIENT, false, false), "Efficient always");
    }
}
