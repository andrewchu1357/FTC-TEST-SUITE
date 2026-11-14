package org.ftctestbed.viewer;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.image.BufferedImage;

/** The FTC Test Suite icon, for the windows and the taskbar. */
final class AppIcon {
    static final String NAME = "FTC Test Suite";
    private static BufferedImage image;
    private static boolean loaded;

    private AppIcon() {}

    /** The 512 px icon, or null if it's missing from the build. */
    static synchronized BufferedImage image() {
        if (!loaded) {
            loaded = true;
            try (var in = AppIcon.class.getResourceAsStream("/icon/app-icon.png")) {
                if (in != null) image = ImageIO.read(in);
            } catch (Exception ignored) {
            }
        }
        return image;
    }

    /** The icon scaled for dialogs (smooth). */
    static Image scaled(int size) {
        BufferedImage i = image();
        return i == null ? null : i.getScaledInstance(size, size, Image.SCALE_SMOOTH);
    }

    /** Shows the icon in the Dock (macOS) or taskbar, when the platform allows it. */
    static void installInDock() {
        BufferedImage i = image();
        if (i == null || !Taskbar.isTaskbarSupported()) return;
        Taskbar t = Taskbar.getTaskbar();
        if (t.isSupported(Taskbar.Feature.ICON_IMAGE)) t.setIconImage(i);
    }
}
