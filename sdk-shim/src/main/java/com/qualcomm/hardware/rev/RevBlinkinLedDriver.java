package com.qualcomm.hardware.rev;

import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.ServoControllerEx;

/**
 * The REV Blinkin LED Driver on a servo port. Each pattern is a pulse width: the SDK sends
 * servo position 0.2525 + 0.005 x the pattern's number, which on the Blinkin's 500-2500 us
 * range is 1005, 1015, ... 1995 us, as in REV's pattern table.
 */
public class RevBlinkinLedDriver implements HardwareDevice {
    public enum BlinkinPattern {
        RAINBOW_RAINBOW_PALETTE, RAINBOW_PARTY_PALETTE, RAINBOW_OCEAN_PALETTE, RAINBOW_LAVA_PALETTE,
        RAINBOW_FOREST_PALETTE, RAINBOW_WITH_GLITTER, CONFETTI, SHOT_RED, SHOT_BLUE, SHOT_WHITE,
        SINELON_RAINBOW_PALETTE, SINELON_PARTY_PALETTE, SINELON_OCEAN_PALETTE, SINELON_LAVA_PALETTE,
        SINELON_FOREST_PALETTE, BEATS_PER_MINUTE_RAINBOW_PALETTE, BEATS_PER_MINUTE_PARTY_PALETTE,
        BEATS_PER_MINUTE_OCEAN_PALETTE, BEATS_PER_MINUTE_LAVA_PALETTE, BEATS_PER_MINUTE_FOREST_PALETTE,
        FIRE_MEDIUM, FIRE_LARGE, TWINKLES_RAINBOW_PALETTE, TWINKLES_PARTY_PALETTE,
        TWINKLES_OCEAN_PALETTE, TWINKLES_LAVA_PALETTE, TWINKLES_FOREST_PALETTE,
        COLOR_WAVES_RAINBOW_PALETTE, COLOR_WAVES_PARTY_PALETTE, COLOR_WAVES_OCEAN_PALETTE,
        COLOR_WAVES_LAVA_PALETTE, COLOR_WAVES_FOREST_PALETTE, LARSON_SCANNER_RED, LARSON_SCANNER_GRAY,
        LIGHT_CHASE_RED, LIGHT_CHASE_BLUE, LIGHT_CHASE_GRAY, HEARTBEAT_RED, HEARTBEAT_BLUE,
        HEARTBEAT_WHITE, HEARTBEAT_GRAY, BREATH_RED, BREATH_BLUE, BREATH_GRAY, STROBE_RED, STROBE_BLUE,
        STROBE_GOLD, STROBE_WHITE, CP1_END_TO_END_BLEND_TO_BLACK, CP1_LARSON_SCANNER, CP1_LIGHT_CHASE,
        CP1_HEARTBEAT_SLOW, CP1_HEARTBEAT_MEDIUM, CP1_HEARTBEAT_FAST, CP1_BREATH_SLOW, CP1_BREATH_FAST,
        CP1_SHOT, CP1_STROBE, CP2_END_TO_END_BLEND_TO_BLACK, CP2_LARSON_SCANNER, CP2_LIGHT_CHASE,
        CP2_HEARTBEAT_SLOW, CP2_HEARTBEAT_MEDIUM, CP2_HEARTBEAT_FAST, CP2_BREATH_SLOW, CP2_BREATH_FAST,
        CP2_SHOT, CP2_STROBE, CP1_2_SPARKLE_1_ON_2, CP1_2_SPARKLE_2_ON_1, CP1_2_COLOR_GRADIENT,
        CP1_2_BEATS_PER_MINUTE, CP1_2_END_TO_END_BLEND_1_TO_2, CP1_2_END_TO_END_BLEND,
        CP1_2_NO_BLENDING, CP1_2_TWINKLES, CP1_2_COLOR_WAVES, CP1_2_SINELON, HOT_PINK, DARK_RED, RED,
        RED_ORANGE, ORANGE, GOLD, YELLOW, LAWN_GREEN, LIME, DARK_GREEN, GREEN, BLUE_GREEN, AQUA,
        SKY_BLUE, DARK_BLUE, BLUE, BLUE_VIOLET, VIOLET, WHITE, GRAY, DARK_GRAY, BLACK;

        private static final BlinkinPattern[] ELEMENTS = values();

        /** The pattern with this number (0-99), wrapping around. */
        public static BlinkinPattern fromNumber(int number) {
            return ELEMENTS[((number % ELEMENTS.length) + ELEMENTS.length) % ELEMENTS.length];
        }

        public BlinkinPattern next() {
            return ELEMENTS[(ordinal() + 1) % ELEMENTS.length];
        }

        public BlinkinPattern previous() {
            return ELEMENTS[(ordinal() - 1 + ELEMENTS.length) % ELEMENTS.length];
        }
    }

    protected static final double PULSE_WIDTH_INCREMENTOR = 0.0005;
    protected static final double BASE_SERVO_POSITION = 0.2525;

    private final ServoControllerEx controller;
    private final int port;

    public RevBlinkinLedDriver(ServoControllerEx controller, int port) {
        this.controller = controller;
        this.port = port;
    }

    public void setPattern(BlinkinPattern pattern) {
        controller.setServoPosition(port, BASE_SERVO_POSITION + 10 * pattern.ordinal() * PULSE_WIDTH_INCREMENTOR);
    }

    @Override public Manufacturer getManufacturer() { return Manufacturer.Lynx; }
    @Override public String getDeviceName() { return "REV Blinkin LED Driver"; }
    @Override public String getConnectionInfo() { return controller.getConnectionInfo() + "; port " + port; }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() {}
    @Override public void close() {}
}
