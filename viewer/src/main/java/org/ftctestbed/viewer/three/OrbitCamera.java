package org.ftctestbed.viewer.three;

import javafx.geometry.Point3D;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SubScene;
import javafx.scene.input.MouseButton;
import javafx.scene.transform.Affine;

/**
 * Orbit camera in field coordinates: drag to rotate around the target, right-drag (or
 * Shift-drag) to pan, scroll to zoom. Presets put the camera where people stand at a match.
 * The lens is a normal one (DIAGONAL_FOV) whatever the view's shape, so nothing looks stretched;
 * presets back the camera off along their line of sight until what they show fits.
 * In FOLLOW the camera stays on the robot whatever you do with the mouse: dragging swings it
 * around the robot, panning shifts the view (never so far that the robot leaves it) and
 * zooming moves it nearer or farther; only choosing another camera stops it following.
 */
final class OrbitCamera {
    enum Preset { RED_DRIVER, BLUE_DRIVER, AUDIENCE, TOP, STANDS, FOLLOW }

    /**
     * How much the picture takes in from corner to corner, in degrees. 50 is what a normal 50 mm
     * lens sees, and about the angle a window on a monitor fills at arm's length, so the picture
     * looks the way the eye sees things. A wider lens stretches things toward the edges and makes
     * near things look oversized. The vertical angle follows from the view's shape (verticalFov).
     */
    static final double DIAGONAL_FOV = 50;
    /** Follow robot: how much of the scene the picture is tall at the robot (m). */
    static final double FOLLOW_VIEW_HEIGHT = 1.4;
    /** Pictures fit in the middle 92% of the view. */
    private static final double FIT_MARGIN = 0.92;

    final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final Affine pose = new Affine();
    private double yaw = 180, pitch = 36, distance = 5.6;
    private double aspect = 16 / 10.0;  // the view's width / height
    private double viewW = 1600, viewH = 1000;
    // Pixels at each edge covered by floating panels (toolbar, inspector): presets frame what they
    // show in the rest of the picture.
    private double safeTop, safeRight, safeBottom, safeLeft;
    private Preset framed;               // the preset as chosen, until the mouse moves the camera
    private double fieldHalf = 1.83, wallTop = 0.32, sceneryTop = 0.32; // m: what the field presets fit
    private double tx, ty, tz;
    private final double[] eye = new double[3];
    private double lastX, lastY;
    private Preset follow = null;
    // Follow robot: the camera is locked behind the robot (no lag, no drift); dragging sideways swings it
    // around the robot (followYaw degrees from straight behind), panning shifts the view by
    // (offRight, offAway) m across and into the picture.
    private double followYaw, offRight, offAway;
    private double robotX, robotY, robotHeading; // where the robot was at the last frame
    static final double FOLLOW_LEAD = 0.3;          // m: look a little ahead of the robot
    /**
     * What the camera looks at is never farther from the robot than this share of half the
     * picture's height, so the robot always stays well inside it.
     */
    static final double FOLLOW_MAX_OFFSET = 0.6;

    OrbitCamera() {
        // Not closer than needed: the depth buffer's precision is spread from here to the far
        // plane, and too fine a near plane makes flat things on the tiles flicker at a distance.
        camera.setNearClip(0.06);
        camera.setFarClip(80);
        camera.setVerticalFieldOfView(true);
        camera.setFieldOfView(verticalFov(aspect));
        camera.getTransforms().add(pose);
        apply();
    }

    /** The vertical angle (degrees) that gives a view of this width/height the DIAGONAL_FOV lens. */
    static double verticalFov(double aspect) {
        double t = Math.tan(Math.toRadians(DIAGONAL_FOV / 2));
        return 2 * Math.toDegrees(Math.atan(t / Math.sqrt(1 + aspect * aspect)));
    }

    /** The view is now w x h pixels: same lens, and a preset nobody has moved is framed again. */
    void setViewSize(double w, double h) {
        if (w < 2 || h < 2 || (Math.abs(w / h - aspect) < 1e-6 && w == viewW)) return;
        aspect = w / h;
        viewW = w;
        viewH = h;
        camera.setFieldOfView(verticalFov(aspect));
        if (framed != null) {
            frame();
            if (follow == Preset.FOLLOW) placeTarget();
            apply();
        }
    }

    /** Pixels at the top, right, bottom and left covered by panels floating over the view. */
    void setSafeArea(double top, double right, double bottom, double left) {
        safeTop = top;
        safeRight = right;
        safeBottom = bottom;
        safeLeft = left;
        if (framed != null) {
            frame();
            if (follow == Preset.FOLLOW) placeTarget();
            apply();
        }
    }

    /**
     * The uncovered part of the picture as tangents of the angle from the middle: {center across,
     * center up, half width, half height}. It always contains the middle, so presets stay fittable.
     */
    private double[] window() {
        double tanV = Math.tan(Math.toRadians(camera.getFieldOfView() / 2)), tanH = tanV * aspect;
        double l = Math.min(safeLeft, viewW * 0.45), r = Math.min(safeRight, viewW * 0.45);
        double t = Math.min(safeTop, viewH * 0.45), b = Math.min(safeBottom, viewH * 0.45);
        double xl = (-1 + 2 * l / viewW) * tanH, xr = (1 - 2 * r / viewW) * tanH;
        double yb = (-1 + 2 * b / viewH) * tanV, yt = (1 - 2 * t / viewH) * tanV;
        return new double[]{(xl + xr) / 2, (yb + yt) / 2, (xr - xl) / 2, (yt - yb) / 2};
    }

    /** What the field presets fit in: the field (half its width, m) with its walls and its tallest structures. */
    void setScenery(double fieldHalf, double wallTop, double sceneryTop) {
        this.fieldHalf = fieldHalf;
        this.wallTop = wallTop;
        this.sceneryTop = Math.max(wallTop, sceneryTop);
        if (framed != null && framed != Preset.FOLLOW) {
            frame();
            apply();
        }
    }

    void attach(SubScene scene) {
        javafx.beans.InvalidationListener resized = o -> setViewSize(scene.getWidth(), scene.getHeight());
        scene.widthProperty().addListener(resized);
        scene.heightProperty().addListener(resized);
        setViewSize(scene.getWidth(), scene.getHeight());
        scene.setOnMousePressed(e -> {
            lastX = e.getSceneX();
            lastY = e.getSceneY();
        });
        scene.setOnMouseDragged(e -> {
            double dx = e.getSceneX() - lastX, dy = e.getSceneY() - lastY;
            lastX = e.getSceneX();
            lastY = e.getSceneY();
            if (e.getButton() == MouseButton.SECONDARY || e.isShiftDown() || e.getButton() == MouseButton.MIDDLE) {
                pan(dx, dy);
            } else {
                rotate(dx, dy);
            }
        });
        scene.setOnScroll(e -> zoom(e.getDeltaY()));
    }

    /** Drag: turn the camera around what it looks at (in FOLLOW, around the robot). */
    void rotate(double dx, double dy) {
        framed = null;
        yaw -= dx * 0.4;
        if (follow == Preset.FOLLOW) followYaw -= dx * 0.4;
        pitch = Math.max(3, Math.min(89.5, pitch + dy * 0.3));
        if (follow == Preset.FOLLOW) placeTarget();
        apply();
    }

    /** Scroll: nearer or farther (in FOLLOW, still on the robot). */
    void zoom(double deltaY) {
        framed = null;
        distance = Math.max(0.3, Math.min(30, distance * Math.pow(1.0015, -deltaY)));
        if (follow == Preset.FOLLOW) {
            clampOffset();
            placeTarget();
        }
        apply();
    }

    /** Grab-and-drag: the floor follows the mouse. In FOLLOW it shifts the view but keeps following. */
    void pan(double dx, double dy) {
        framed = null;
        double k = distance * 0.0018 * Math.tan(Math.toRadians(camera.getFieldOfView() / 2)) / Math.tan(Math.toRadians(21));
        if (follow == Preset.FOLLOW) {
            offRight -= dx * k;
            offAway += dy * k;
            clampOffset();
            placeTarget();
        } else {
            double y = Math.toRadians(yaw);
            double rightX = -Math.sin(y), rightY = Math.cos(y);   // screen-right on the floor
            double fwdX = -Math.cos(y), fwdY = -Math.sin(y);      // away from the camera on the floor
            tx += (-rightX * dx + fwdX * dy) * k;
            ty += (-rightY * dx + fwdY * dy) * k;
        }
        apply();
    }

    /** Keeps the pan shift small enough that the robot stays in the picture. */
    private void clampOffset() {
        double max = maxOffset(), len = Math.hypot(offRight, offAway);
        if (len > max) {
            offRight *= max / len;
            offAway *= max / len;
        }
    }

    /** FOLLOW: what the camera looks at = a little ahead of the robot plus the pan shift (across and into the picture), kept close to the robot. */
    private void placeTarget() {
        double y = Math.toRadians(yaw);
        double ox = Math.cos(robotHeading) * FOLLOW_LEAD - Math.sin(y) * offRight - Math.cos(y) * offAway;
        double oy = Math.sin(robotHeading) * FOLLOW_LEAD + Math.cos(y) * offRight - Math.sin(y) * offAway;
        double max = maxOffset(), len = Math.hypot(ox, oy);
        if (len > max) {
            ox *= max / len;
            oy *= max / len;
        }
        tx = robotX + ox;
        ty = robotY + oy;
        // Keep the robot in the middle of the part of the picture the panels leave free.
        double[] win = window();
        double[][] ax = axes();
        tx -= (ax[1][0] * win[0] + ax[2][0] * win[1]) * distance;
        ty -= (ax[1][1] * win[0] + ax[2][1] * win[1]) * distance;
    }

    /** Follow robot: how far the target may be from the robot (m), from the picture's height there. */
    private double maxOffset() {
        return FOLLOW_MAX_OFFSET * distance * Math.tan(Math.toRadians(camera.getFieldOfView() / 2));
    }

    // A driver's eyes are 1.6 m up and 0.75 m behind the alliance wall; the driver presets look
    // at the field center along that line (the field is 3.66 m across).
    private static final double DRIVER_BACK = 1.829 + 0.75, DRIVER_EYE = 1.6;
    static final double DRIVER_DISTANCE = Math.hypot(DRIVER_BACK, DRIVER_EYE);
    static final double DRIVER_PITCH = Math.toDegrees(Math.atan2(DRIVER_EYE, DRIVER_BACK));
    // Stands: high up behind the audience, looking at a point past the middle of the field, so
    // the field fills the lower part of the picture and the stands across it the upper part.
    static final double STANDS_PITCH = 23, STANDS_DISTANCE = 13.5, STANDS_TARGET = 1.3;

    void preset(Preset p) {
        follow = p == Preset.FOLLOW ? p : null;
        tx = ty = 0;
        tz = 0;
        switch (p) {
            case RED_DRIVER -> { yaw = 180; pitch = DRIVER_PITCH; }
            case BLUE_DRIVER -> { yaw = 0; pitch = DRIVER_PITCH; }
            case AUDIENCE -> { yaw = -90; pitch = 32; }
            case TOP -> { yaw = -90; pitch = 89.5; }
            case STANDS -> { yaw = -90; pitch = STANDS_PITCH; }
            // Behind the robot and low, like a driver crouching behind it; anything in the way is
            // hidden (Field3D.clearView).
            case FOLLOW -> { pitch = 14; followYaw = 0; offRight = offAway = 0; }
        }
        framed = p;
        frame();
        apply();
    }

    /**
     * Backs the camera off along its line of sight until the preset's subject fits: the whole
     * field (the driver views from the driver's angle, from at least the driver's eye), or for
     * Follow robot FOLLOW_VIEW_HEIGHT of scene at the robot.
     */
    private void frame() {
        double halfV = Math.toRadians(camera.getFieldOfView() / 2);
        if (framed == Preset.STANDS) {
            tx = 0;
            ty = STANDS_TARGET;
            distance = STANDS_DISTANCE;
            return;
        }
        if (framed == Preset.FOLLOW) {
            distance = FOLLOW_VIEW_HEIGHT / (2 * Math.tan(halfV));
            clampOffset();
            return;
        }
        double h = fieldHalf, c = 0.6;
        double[][] points = {
                {-h, -h, 0}, {h, -h, 0}, {h, h, 0}, {-h, h, 0},
                {-h, -h, wallTop}, {h, -h, wallTop}, {h, h, wallTop}, {-h, h, wallTop},
                {-c, -c, sceneryTop}, {c, -c, sceneryTop}, {c, c, sceneryTop}, {-c, c, sceneryTop}};
        // Fit, then slide the target along the floor until what's shown sits in the middle of the
        // picture (a tall HIVE would otherwise leave an empty band at the bottom), and fit again.
        double[] away = {-Math.cos(Math.toRadians(yaw)), -Math.sin(Math.toRadians(yaw))};
        double sinP = Math.sin(Math.toRadians(pitch));
        double[] win = window(), right = axes()[1];
        for (int i = 0; i < 12; i++) {
            distance = fitDistance(points);
            double mid = verticalMiddle(points) - win[1], across = horizontalMiddle(points) - win[0];
            if (Math.abs(mid) < 1e-4 && Math.abs(across) < 1e-4) break;
            double shift = mid * distance / sinP;
            tx += away[0] * shift + right[0] * across * distance;
            ty += away[1] * shift + right[1] * across * distance;
        }
        if (framed == Preset.RED_DRIVER || framed == Preset.BLUE_DRIVER) distance = Math.max(distance, DRIVER_DISTANCE);
    }

    /** The camera's directions in field coordinates: toward the eye from the target, screen right, screen up. */
    private double[][] axes() {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        double[] back = {Math.cos(p) * Math.cos(y), Math.cos(p) * Math.sin(y), Math.sin(p)};
        double n = Math.hypot(back[0], back[1]);
        double[] right = {-back[1] / n, back[0] / n, 0};
        double[] up = {-back[2] * back[0], -back[2] * back[1], 1 - back[2] * back[2]};
        double un = Math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]);
        return new double[][]{back, right, {up[0] / un, up[1] / un, up[2] / un}};
    }

    /** The least distance (at the current angle and target) at which every point is in the uncovered part of the picture. */
    double fitDistance(double[][] points) {
        double[][] a = axes();
        double[] win = window();
        double hx = win[2] * FIT_MARGIN, hy = win[3] * FIT_MARGIN;
        double need = 0;
        for (double[] q : points) {
            double[] r = {q[0] - tx, q[1] - ty, q[2] - tz};
            double toward = dot(r, a[0]); // how much nearer the camera than the target
            double across = dot(r, a[1]), high = dot(r, a[2]);
            // Inside [center - half, center + half] each way (the window contains the middle).
            double dx = across >= 0 ? across / (win[0] + hx) : across / (win[0] - hx);
            double dy = high >= 0 ? high / (win[1] + hy) : high / (win[1] - hy);
            need = Math.max(need, Math.max(dx, dy) + toward);
        }
        return need;
    }

    /** Where the points' left and right edges center in the picture (tangent of the angle right of the middle). */
    double horizontalMiddle(double[][] points) {
        double[][] a = axes();
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (double[] q : points) {
            double[] r = {q[0] - tx, q[1] - ty, q[2] - tz};
            double t = dot(r, a[1]) / (distance - dot(r, a[0]));
            lo = Math.min(lo, t);
            hi = Math.max(hi, t);
        }
        return (lo + hi) / 2;
    }

    /**
     * Where the points' top and bottom edges center in the picture, as the tangent of the angle
     * above the middle (negative: below), at the current distance.
     */
    double verticalMiddle(double[][] points) {
        double[][] a = axes();
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (double[] q : points) {
            double[] r = {q[0] - tx, q[1] - ty, q[2] - tz};
            double t = dot(r, a[2]) / (distance - dot(r, a[0]));
            lo = Math.min(lo, t);
            hi = Math.max(hi, t);
        }
        return (lo + hi) / 2;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /**
     * Called with every new frame; in FOLLOW mode the camera is locked to the robot: it moves and
     * turns exactly with it, so it never lags behind or drifts after the robot stops.
     */
    void track(double robotX, double robotY, double robotHeading) {
        if (follow != Preset.FOLLOW) return;
        this.robotX = robotX;
        this.robotY = robotY;
        this.robotHeading = robotHeading;
        yaw = Math.toDegrees(robotHeading) + 180 + followYaw;
        tz = 0.2;
        placeTarget();
        apply();
    }

    /** True while the camera follows the robot. */
    boolean following() {
        return follow == Preset.FOLLOW;
    }

    /** Where the camera is (field coordinates, m). */
    double[] eye() {
        return eye.clone();
    }

    /** The unit direction the camera looks in (field coordinates). */
    double[] forward() {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new double[]{-Math.cos(p) * Math.cos(y), -Math.cos(p) * Math.sin(y), -Math.sin(p)};
    }

    double fieldOfView() {
        return camera.getFieldOfView();
    }

    private void apply() {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        Point3D target = new Point3D(tx, ty, tz);
        Point3D eye = target.add(new Point3D(Math.cos(p) * Math.cos(y), Math.cos(p) * Math.sin(y), Math.sin(p)).multiply(distance));
        this.eye[0] = eye.getX();
        this.eye[1] = eye.getY();
        this.eye[2] = eye.getZ();
        Point3D f = target.subtract(eye).normalize();
        Point3D right = f.crossProduct(new Point3D(0, 0, 1)).normalize();
        Point3D down = f.crossProduct(right).normalize();
        pose.setToTransform(right.getX(), down.getX(), f.getX(), eye.getX(),
                right.getY(), down.getY(), f.getY(), eye.getY(),
                right.getZ(), down.getZ(), f.getZ(), eye.getZ());
    }
}
