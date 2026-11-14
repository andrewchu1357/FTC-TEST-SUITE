package org.ftctestbed.sim.vision;

import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.ExposureControl;

import java.util.ArrayList;
import java.util.List;

/**
 * A USB webcam teams use: its sizes, the SDK's built-in calibrations for it (from the SDK 12.0
 * builtinwebcamcalibrations.xml), and its exposure and gain ranges. The real lens at a size the
 * SDK has no calibration for is scaled from one of the same shape (as the SDK does), or else
 * worked out from the camera's field of view [ESTIMATE], and then the SDK falls back to its
 * 578.272 px guess, so AprilTag poses come out wrong, as on a real robot.
 */
public record WebcamModel(String id, String name, int vid, int pid, List<Calibration> calibrations, List<int[]> sizes,
                          double hfovDeg, int minExposure100us, int maxExposure100us, int minGain, int maxGain,
                          ExposureControl.Mode autoMode) {

    /** A calibration at one size: focal lengths and principal point (px). */
    public record Calibration(int width, int height, double fx, double fy, double cx, double cy) {}

    /** The lens at a size, and the calibrated size it was scaled from (0 x 0: not scaled; null calibration: none). */
    public record Lens(double fx, double fy, double cx, double cy, Calibration calibration, int scaledFromWidth, int scaledFromHeight) {}

    public static final List<WebcamModel> ALL = List.of(
            // Logitech HD Pro Webcam C920 (both PIDs). Calibrated by Robert Atkinson, 2018, 3DF Zephyr.
            new WebcamModel("C920", "Logitech HD Pro Webcam C920", 0x046D, 0x082D, List.of(
                    new Calibration(640, 480, 622.001, 622.001, 319.803, 241.251),
                    new Calibration(800, 600, 775.79, 775.79, 400.898, 300.79),
                    new Calibration(640, 360, 463.566, 463.566, 316.402, 176.412),
                    new Calibration(1920, 1080, 1385.92, 1385.92, 951.982, 534.084),
                    new Calibration(800, 448, 578.272, 578.272, 402.145, 221.506),
                    new Calibration(864, 480, 626.909, 626.909, 426.007, 236.834)),
                    sizes("160x90 160x120 176x144 320x180 320x240 352x288 432x240 640x360 640x480 800x448 800x600 864x480 "
                            + "960x720 1024x576 1280x720 1600x896 1920x1080 2304x1296 2304x1536"),
                    70.4, 3, 2047, 0, 255, ExposureControl.Mode.AperturePriority),
            // Logitech HD Webcam C270. Calibrated by Noah Andrews, 2019, 3DF Zephyr.
            new WebcamModel("C270", "Logitech HD Webcam C270", 0x046D, 0x0825, List.of(
                    new Calibration(640, 480, 822.317, 822.317, 319.495, 242.502)),
                    sizes("160x120 176x144 320x176 320x240 352x288 432x240 544x288 640x360 640x480 752x416 800x448 800x600 "
                            + "864x480 960x544 960x720 1024x576 1184x656 1280x720 1280x960"),
                    55, 1, 10000, 0, 255, ExposureControl.Mode.AperturePriority),
            // Logitech HD Webcam C310. Calibrated by Robert Atkinson, 2018, 3DF Zephyr.
            new WebcamModel("C310", "Logitech HD Webcam C310", 0x046D, 0x081B, List.of(
                    new Calibration(640, 480, 821.993, 821.993, 330.489, 248.997),
                    new Calibration(640, 360, 715.307, 715.307, 319.759, 188.917)),
                    sizes("160x120 176x144 320x176 320x240 352x288 432x240 544x288 640x360 640x480 752x416 800x448 800x600 "
                            + "864x480 960x544 960x720 1024x576 1184x656 1280x720 1280x960"),
                    55, 1, 10000, 0, 255, ExposureControl.Mode.AperturePriority),
            // goBILDA USB camera, SKU 3122-0003-0001. Calibrated by Ethan Doak, 2024, 3DF Zephyr.
            new WebcamModel("goBILDA", "goBILDA 3122-0003-0001 USB Camera", 0x0BDA, 0x5805, List.of(
                    new Calibration(1920, 1080, 1432.032, 1432.032, 997.085, 535.891),
                    new Calibration(1280, 720, 964.146, 964.146, 637.101, 369.345),
                    new Calibration(800, 600, 602.426, 602.4268, 416.250, 302.576),
                    new Calibration(640, 480, 481.985, 481.985, 334.203, 241.948)),
                    sizes("320x240 640x480 800x600 1280x720 1920x1080"),
                    67, 1, 5000, 0, 100, ExposureControl.Mode.AperturePriority),
            // Microsoft LifeCam HD-3000 (both versions). Calibrated by PTC.
            new WebcamModel("LifeCam", "Microsoft LifeCam HD-3000", 0x045E, 0x0779, List.of(
                    new Calibration(640, 480, 678.154, 678.17, 318.135, 228.374)),
                    sizes("160x120 176x144 320x240 352x288 424x240 640x360 640x480 800x448 800x600 960x544 1280x720"),
                    57, 5, 20000, 0, 255, ExposureControl.Mode.AperturePriority),
            // Any other UVC webcam: no built-in calibration.
            new WebcamModel("generic", "USB webcam", 0x1BCF, 0x284C, List.of(),
                    sizes("320x240 640x360 640x480 800x600 1280x720 1920x1080"),
                    70, 1, 5000, 0, 255, ExposureControl.Mode.AperturePriority));

    public static WebcamModel find(String id) {
        for (WebcamModel m : ALL) if (m.id.equalsIgnoreCase(id)) return m;
        return null;
    }

    private static List<int[]> sizes(String s) {
        List<int[]> out = new ArrayList<>();
        for (String p : s.trim().split("\\s+")) {
            String[] wh = p.split("x");
            out.add(new int[]{Integer.parseInt(wh[0]), Integer.parseInt(wh[1])});
        }
        return out;
    }

    public boolean supports(int width, int height) {
        for (int[] s : sizes) if (s[0] == width && s[1] == height) return true;
        return false;
    }

    /** EasyOpenCV's message for a size the camera can't stream. */
    public String sizeError(int width, int height) {
        if (supports(width, height)) return null;
        StringBuilder sb = new StringBuilder("Camera does not support requested resolution! Supported resolutions are ");
        for (int i = 0; i < sizes.size(); i++) sb.append(i > 0 ? ", " : "").append("[").append(sizes.get(i)[0]).append("x").append(sizes.get(i)[1]).append("]");
        return sb.toString();
    }

    /**
     * Frames per second: 30 for MJPEG; for uncompressed YUY2, limited by USB 2.0 bandwidth to
     * about 9.6 million pixels a second (a C920 does 640x480 at 30, 1280x720 at 10, 1920x1080 at
     * 5) [ESTIMATE].
     */
    public double maxFps(int width, int height, boolean mjpeg) {
        if (mjpeg) return 30;
        return Math.max(1, Math.min(30, 9.6e6 / ((double) width * height)));
    }

    /** The lens at this size, and the SDK's calibration for it (exact, scaled, or none). */
    public Lens lens(int width, int height) {
        for (Calibration c : calibrations) {
            if (c.width == width && c.height == height) return new Lens(c.fx, c.fy, c.cx, c.cy, c, 0, 0);
        }
        // The SDK scales a calibration of the same aspect ratio (the largest one here).
        Calibration best = null;
        for (Calibration c : calibrations) {
            if ((long) c.width * height == (long) c.height * width && (best == null || c.width > best.width)) best = c;
        }
        if (best != null) {
            double k = width / (double) best.width;
            Calibration scaled = new Calibration(width, height, best.fx * k, best.fy * k, best.cx * k, best.cy * k);
            return new Lens(scaled.fx, scaled.fy, scaled.cx, scaled.cy, scaled, best.width, best.height);
        }
        double fx = width / 2.0 / Math.tan(Math.toRadians(hfovDeg) / 2);
        return new Lens(fx, fx, width / 2.0, height / 2.0, null, 0, 0);
    }
}
