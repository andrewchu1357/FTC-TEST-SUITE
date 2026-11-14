package org.ftctestbed.sim.hardware;

import com.qualcomm.hardware.rev.RevBlinkinLedDriver.BlinkinPattern;

/**
 * What a REV Blinkin strip looks like for each pattern number (0-99): the solid colors from REV's
 * pattern table, and for the animated patterns their main color (a rainbow shows as violet, the
 * "color 1/2" patterns as the Blinkin's factory colors) [ESTIMATE for the animations].
 */
public final class BlinkinColors {
    private BlinkinColors() {}

    public static int color(int number) {
        if (number < 0 || number >= BlinkinPattern.values().length) return 0;
        BlinkinPattern p = BlinkinPattern.values()[number];
        switch (p) {
            case HOT_PINK: return 0xFF69B4;
            case DARK_RED: return 0x8B0000;
            case RED: return 0xFF0000;
            case RED_ORANGE: return 0xFF4500;
            case ORANGE: return 0xFFA500;
            case GOLD: return 0xFFD700;
            case YELLOW: return 0xFFFF00;
            case LAWN_GREEN: return 0x7CFC00;
            case LIME: return 0x32CD32;
            case DARK_GREEN: return 0x006400;
            case GREEN: return 0x00C000;
            case BLUE_GREEN: return 0x0D98BA;
            case AQUA: return 0x00FFFF;
            case SKY_BLUE: return 0x87CEEB;
            case DARK_BLUE: return 0x00008B;
            case BLUE: return 0x0000FF;
            case BLUE_VIOLET: return 0x8A2BE2;
            case VIOLET: return 0xEE82EE;
            case WHITE: return 0xFFFFFF;
            case GRAY: return 0x808080;
            case DARK_GRAY: return 0x404040;
            case BLACK: return 0;
            default: break;
        }
        String n = p.name();
        if (n.startsWith("CP1_2")) return 0xFF8800;
        if (n.startsWith("CP1")) return 0xFF8800;   // color 1's factory setting [ESTIMATE]
        if (n.startsWith("CP2")) return 0x0088FF;   // color 2's factory setting [ESTIMATE]
        if (n.endsWith("_RED")) return 0xFF0000;
        if (n.endsWith("_BLUE")) return 0x0000FF;
        if (n.endsWith("_WHITE")) return 0xFFFFFF;
        if (n.endsWith("_GRAY")) return 0x808080;
        if (n.endsWith("_GOLD")) return 0xFFD700;
        if (n.contains("OCEAN")) return 0x0077BE;
        if (n.contains("LAVA") || n.startsWith("FIRE")) return 0xFF4500;
        if (n.contains("FOREST")) return 0x228B22;
        return 0xB040FF; // rainbow, party, confetti
    }
}
