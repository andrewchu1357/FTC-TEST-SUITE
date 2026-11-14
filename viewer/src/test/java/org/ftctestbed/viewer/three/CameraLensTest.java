package org.ftctestbed.viewer.three;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The 3D view's lens is a normal one whatever the window's shape, and the presets frame the field with it. */
class CameraLensTest {
    static final double HALF = 1.82, WALL = 0.305, TOP = 1.6; // BIOBUZZ: field half-width, walls, HIVE tops

    static final OrbitCamera.Preset[] FIELD_PRESETS = {
            OrbitCamera.Preset.RED_DRIVER, OrbitCamera.Preset.BLUE_DRIVER, OrbitCamera.Preset.AUDIENCE, OrbitCamera.Preset.TOP};

    static double[][] fieldPoints() {
        double c = 0.6;
        return new double[][]{
                {-HALF, -HALF, 0}, {HALF, -HALF, 0}, {HALF, HALF, 0}, {-HALF, HALF, 0},
                {-HALF, -HALF, WALL}, {HALF, -HALF, WALL}, {HALF, HALF, WALL}, {-HALF, HALF, WALL},
                {-c, -c, TOP}, {c, -c, TOP}, {c, c, TOP}, {-c, c, TOP}};
    }

    static OrbitCamera camera(double w, double h) {
        OrbitCamera cam = new OrbitCamera();
        cam.setViewSize(w, h);
        cam.setScenery(HALF, WALL, TOP);
        return cam;
    }

    /** Where a field point lands in the picture: {right, up} as tangents of the angle from the middle. */
    static double[] project(OrbitCamera cam, double[] p) {
        double[] e = cam.eye(), f = cam.forward();
        double[] right = norm(cross(f, new double[]{0, 0, 1}));
        double[] up = cross(right, f);
        double[] r = {p[0] - e[0], p[1] - e[1], p[2] - e[2]};
        double depth = dot(r, f);
        return new double[]{dot(r, right) / depth, dot(r, up) / depth};
    }

    @Test
    void theLensIsNormalForEveryWindowShape() {
        for (double aspect : new double[]{0.7, 1.0, 1.6, 1.78, 2.4}) {
            double v = Math.toRadians(OrbitCamera.verticalFov(aspect)) / 2;
            double diagonal = 2 * Math.toDegrees(Math.atan(Math.tan(v) * Math.sqrt(1 + aspect * aspect)));
            assertEquals(OrbitCamera.DIAGONAL_FOV, diagonal, 1e-9, "corner to corner at " + aspect);
            double horizontal = 2 * Math.toDegrees(Math.atan(Math.tan(v) * aspect));
            assertTrue(horizontal < 50, "never wider than a normal lens across: " + horizontal + " deg at " + aspect);
        }
        assertEquals(50, OrbitCamera.DIAGONAL_FOV, "a normal (50 mm) lens");
    }

    @Test
    void everyPresetShowsTheWholeFieldCentered() {
        for (double[] size : new double[][]{{1000, 620}, {800, 800}, {1600, 640}, {600, 900}}) {
            for (OrbitCamera.Preset p : FIELD_PRESETS) {
                OrbitCamera cam = camera(size[0], size[1]);
                cam.preset(p);
                double tanV = Math.tan(Math.toRadians(cam.fieldOfView() / 2)), tanH = tanV * size[0] / size[1];
                double lo = 9, hi = -9, widest = 0, tallest = 0;
                for (double[] q : fieldPoints()) {
                    double[] s = project(cam, q);
                    assertTrue(Math.abs(s[0]) <= tanH * 1.0001 && Math.abs(s[1]) <= tanV * 1.0001,
                            p + " at " + size[0] + "x" + size[1] + " shows " + java.util.Arrays.toString(q));
                    lo = Math.min(lo, s[1]);
                    hi = Math.max(hi, s[1]);
                    widest = Math.max(widest, Math.abs(s[0]) / tanH);
                    tallest = Math.max(tallest, Math.abs(s[1]) / tanV);
                }
                assertEquals(0, (lo + hi) / 2, 0.01 * tanV, p + ": the field sits in the middle of the picture");
                assertTrue(Math.max(widest, tallest) > 0.85, p + ": and fills it (" + widest + ", " + tallest + ")");
            }
        }
    }

    @Test
    void theDriverViewsLookFromTheDriversAngle() {
        for (OrbitCamera.Preset p : new OrbitCamera.Preset[]{OrbitCamera.Preset.RED_DRIVER, OrbitCamera.Preset.BLUE_DRIVER}) {
            OrbitCamera cam = camera(1000, 620);
            cam.preset(p);
            double[] f = cam.forward(), e = cam.eye();
            assertEquals(OrbitCamera.DRIVER_PITCH, Math.toDegrees(Math.asin(-f[2])), 1e-6, p + ": looking down like a driver");
            assertEquals(p == OrbitCamera.Preset.RED_DRIVER ? 1 : -1, Math.signum(f[0]), p + ": across the field from its wall");
            assertEquals(0, e[1], 1e-9, "straight down the middle");
            assertTrue(Math.abs(e[0]) > HALF + 0.75, p + ": from behind the alliance wall");
        }
    }

    @Test
    void resizingReframesAPresetUntilTheMouseMovesTheCamera() {
        OrbitCamera cam = camera(1000, 620);
        cam.preset(OrbitCamera.Preset.AUDIENCE);
        double[] wide = cam.eye();
        cam.setViewSize(620, 620);
        double[] square = cam.eye();
        assertTrue(Math.abs(square[1]) > Math.abs(wide[1]), "a narrower window: further back to keep the field in");
        cam.zoom(-200);
        double[] zoomed = cam.eye();
        cam.setViewSize(1000, 620);
        assertArrayEquals(zoomed, cam.eye(), 1e-9, "once you've zoomed, resizing leaves the camera where you put it");
        assertEquals(OrbitCamera.verticalFov(1000 / 620.0), cam.fieldOfView(), 1e-9, "but the lens still fits the window");
    }

    @Test
    void followRobotShowsTheSameAmountOfSceneAtTheRobotInAnyWindow() {
        for (double[] size : new double[][]{{1000, 620}, {700, 700}, {1600, 600}}) {
            OrbitCamera cam = camera(size[0], size[1]);
            cam.preset(OrbitCamera.Preset.FOLLOW);
            cam.track(0, 0, 0);
            double[] e = cam.eye();
            // It looks at a point FOLLOW_LEAD ahead of the robot.
            double dx = e[0] - OrbitCamera.FOLLOW_LEAD, toTarget = Math.sqrt(dx * dx + e[1] * e[1] + (e[2] - 0.2) * (e[2] - 0.2));
            double tall = 2 * toTarget * Math.tan(Math.toRadians(cam.fieldOfView() / 2));
            assertEquals(OrbitCamera.FOLLOW_VIEW_HEIGHT, tall, 0.01, "picture height there");
        }
    }

    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
    private static double[] norm(double[] a) {
        double n = Math.sqrt(dot(a, a));
        return new double[]{a[0] / n, a[1] / n, a[2] / n};
    }
}
