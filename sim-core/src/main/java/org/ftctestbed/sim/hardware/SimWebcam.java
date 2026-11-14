package org.ftctestbed.sim.hardware;

import com.qualcomm.robotcore.util.SerialNumber;

import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibration;
import org.firstinspires.ftc.vision.VisionPortalImpl;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.vision.Fiducial;
import org.ftctestbed.sim.vision.Frame3;
import org.ftctestbed.sim.vision.VisionScene;
import org.ftctestbed.sim.vision.WebcamModel;
import org.ftctestbed.shim.vision.SimCamera;
import org.ftctestbed.shim.vision.SimCameraControls;
import org.ftctestbed.shim.vision.SimFrame;
import org.ftctestbed.shim.vision.SimPortal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * A USB webcam on the robot, for the SDK's VisionPortal. Each frame it works out what the lens
 * sees: AprilTags it can see whole, facing it within 75 degrees [ESTIMATE] and unblocked (pixel
 * corners with 0.3 px of noise, more when blurred or dark [ESTIMATE]); game pieces as shaded
 * balls; robots (alliance-colored bumpers) and opaque field parts as flat boxes; and behind them
 * the gray tiles, the field walls and the dark venue. Motion blur is how far a tag's center moves
 * across the picture while the shutter is open (robot speed and turn rate, exposure time).
 * Physics thread: step(). Robot thread: the WebcamName methods and VisionPortal calls.
 */
public class SimWebcam implements SimCamera {
    static final double MAX_TAG_ANGLE = Math.toRadians(75);
    static final double CORNER_NOISE_PX = 0.3;
    static final double WALL_HEIGHT = 12 * 0.0254;
    static final int TILE = 0x6E7074, SEAM = 0x56585C, WALL = 0xB4B8BE, VENUE = 0x2B2D31;

    public final RobotConfig.Webcam def;
    public final WebcamModel model;
    private final SimCameraControls controls;
    private final double fieldHalf;
    private Random random;
    private final SerialNumber serial;

    private volatile SimPortal portal;

    // Physics thread: where the robot is this step.
    private double x, y, heading, vx, vy, omega;
    private long nanos;
    private VisionScene scene;
    private List<VisionScene.Box> occluders = List.of();
    private volatile Capture last;

    /** What the camera saw in its newest frame, for the camera window. */
    public record Capture(Frame3 camera, int width, int height, double fx, double fy, double cx, double cy, List<SimFrame.Tag> tags) {}

    public SimWebcam(RobotConfig.Webcam def, double fieldSize) {
        this.def = def;
        this.model = WebcamModel.find(def.model());
        this.controls = new SimCameraControls(model.minExposure100us(), model.maxExposure100us(), model.minGain(), model.maxGain(), model.autoMode());
        this.fieldHalf = fieldSize / 2;
        this.random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(def.name().hashCode() * 977L + 11));
        this.serial = SerialNumber.fromString(String.format("%08X", def.name().hashCode()));
    }

    /** The lens frame on the robot (x right, y down, z out of the lens; robot frame x forward, y left, z up). */
    public Frame3 mount() {
        return mount(def);
    }

    public static Frame3 mount(RobotConfig.Webcam def) {
        double yaw = def.heading(), tilt = def.tiltUp(), roll = def.roll();
        double[] f = {Math.cos(tilt) * Math.cos(yaw), Math.cos(tilt) * Math.sin(yaw), Math.sin(tilt)};
        double[] left = {-Math.sin(yaw), Math.cos(yaw), 0};
        double[] up = {f[1] * left[2] - f[2] * left[1], f[2] * left[0] - f[0] * left[2], f[0] * left[1] - f[1] * left[0]};
        double[] right = {-left[0], -left[1], -left[2]}, down = {-up[0], -up[1], -up[2]};
        double c = Math.cos(roll), s = Math.sin(roll);
        double[] ax = {c * right[0] + s * down[0], c * right[1] + s * down[1], c * right[2] + s * down[2]};
        double[] ay = {-s * right[0] + c * down[0], -s * right[1] + c * down[1], -s * right[2] + c * down[2]};
        return Frame3.ofAxes(ax, ay, f, new double[]{def.x(), def.y(), def.z()});
    }

    // ------------------------------------------------------------------ physics thread

    /** Physics thread: the robot is here, moving like this; a streaming portal takes its frames. */
    public void step(long nanos, double x, double y, double heading, double vx, double vy, double omega, VisionScene scene) {
        this.nanos = nanos;
        this.x = x;
        this.y = y;
        this.heading = heading;
        this.vx = vx;
        this.vy = vy;
        this.omega = omega;
        this.scene = scene;
        SimPortal p = portal;
        if (p != null) p.simStep(this, nanos);
    }

    /** The newest frame's view, for the camera window (null before the first). */
    public Capture lastCapture() {
        return last;
    }

    /** Power on: settings back to defaults; a portal that was streaming has lost its camera. */
    public void powerCycle() {
        controls.powerCycle();
        random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(def.name().hashCode() * 977L + 11));
        last = null;
        SimPortal p = portal;
        portal = null;
        if (p instanceof VisionPortalImpl v) v.simCameraLost();
    }

    @Override
    public SimFrame simCapture(int width, int height) {
        WebcamModel.Lens lens = model.lens(width, height);
        double fx = lens.fx(), fy = lens.fy(), cx = lens.cx(), cy = lens.cy();
        Frame3 cam = Frame3.planar(x, y, 0, heading).compose(mount());
        double exposure = controls.exposureSeconds();
        Frame3 earlier = Frame3.planar(x - vx * exposure, y - vy * exposure, 0, heading - omega * exposure).compose(mount());
        double brightness = controls.brightness();
        List<SimFrame.Tag> tags = new ArrayList<>();
        double[] eye = cam.origin();
        occluders = scene != null ? scene.occluders() : List.of();
        if (scene != null) {
            for (Fiducial f : scene.fiducials()) {
                double h = f.size() / 2;
                // The AprilTag library's corner order: the tag's bottom-left, bottom-right, top-right, top-left.
                double[][] world = {f.frame().apply(new double[]{-h, h, 0}), f.frame().apply(new double[]{h, h, 0}),
                        f.frame().apply(new double[]{h, -h, 0}), f.frame().apply(new double[]{-h, -h, 0})};
                double[][] px = new double[4][];
                boolean inView = true;
                for (int i = 0; i < 4 && inView; i++) {
                    px[i] = project(cam.toLocal(world[i]), fx, fy, cx, cy);
                    inView = px[i] != null && px[i][0] >= 1 && px[i][0] <= width - 1 && px[i][1] >= 1 && px[i][1] <= height - 1;
                }
                if (!inView) continue;
                double[] toCam = sub(eye, f.frame().origin());
                if (dot(f.facing(), toCam) / norm(toCam) < Math.cos(MAX_TAG_ANGLE)) continue;
                if (blocked(eye, f.frame().origin()) || blocked(eye, world[0]) || blocked(eye, world[2])) continue;
                double[] before = project(earlier.toLocal(f.frame().origin()), fx, fy, cx, cy);
                double[] now = project(cam.toLocal(f.frame().origin()), fx, fy, cx, cy);
                double blur = before == null || now == null ? 0 : Math.hypot(now[0] - before[0], now[1] - before[1]);
                double sigma = CORNER_NOISE_PX + 0.2 * blur + (brightness < 0.5 ? 0.6 : 0);
                for (double[] p : px) {
                    p[0] += random.nextGaussian() * sigma;
                    p[1] += random.nextGaussian() * sigma;
                }
                tags.add(new SimFrame.Tag(f.id(), f.family(), px, blur));
            }
        }
        List<SimFrame.Shape> shapes = scene != null ? shapes(cam, fx, fy, cx, cy, width, height) : List.of();
        last = new Capture(cam, width, height, fx, fy, cx, cy, tags);
        return new SimFrame(width, height, nanos, fx, fy, cx, cy, brightness, controls.noise(), tags, shapes, background(cam, fx, fy, cx, cy));
    }

    /** Balls and boxes in the picture, far to near. */
    private List<SimFrame.Shape> shapes(Frame3 cam, double fx, double fy, double cx, double cy, int width, int height) {
        record Item(double depth, SimFrame.Shape shape) {}
        List<Item> items = new ArrayList<>();
        for (VisionScene.Ball b : scene.balls()) {
            double[] c = cam.toLocal(new double[]{b.x(), b.y(), b.z()});
            if (c[2] <= b.radius()) continue;
            double[] p = project(c, fx, fy, cx, cy);
            double d2 = c[0] * c[0] + c[1] * c[1] + c[2] * c[2];
            double r = fx * b.radius() / Math.sqrt(Math.max(1e-9, d2 - b.radius() * b.radius()));
            if (p[0] + r < 0 || p[0] - r > width || p[1] + r < 0 || p[1] - r > height) continue;
            items.add(new Item(Math.sqrt(d2), SimFrame.Shape.disc(p[0], p[1], r, b.color())));
        }
        for (VisionScene.Box box : occluders) {
            double[][] corners = box.corners();
            List<double[]> pts = new ArrayList<>();
            boolean behind = false;
            for (double[] k : corners) {
                double[] c = cam.toLocal(k);
                if (c[2] < 0.03) {
                    behind = true;
                    break;
                }
                pts.add(project(c, fx, fy, cx, cy));
            }
            if (behind) continue; // too close to (or around) the lens to draw as one polygon
            double[][] hull = hull(pts);
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (double[] p : hull) {
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
            }
            if (maxX < 0 || minX > width || maxY < 0 || minY > height) continue;
            double[] xs = new double[hull.length], ys = new double[hull.length];
            for (int i = 0; i < hull.length; i++) {
                xs[i] = hull[i][0];
                ys[i] = hull[i][1];
            }
            double[] center = cam.toLocal(new double[]{box.x(), box.y(), (box.bottom() + box.top()) / 2});
            items.add(new Item(norm(center), SimFrame.Shape.polygon(xs, ys, box.color())));
        }
        items.sort(Comparator.comparingDouble(Item::depth).reversed());
        List<SimFrame.Shape> out = new ArrayList<>();
        for (Item i : items) out.add(i.shape);
        return out;
    }

    /** Behind everything: the tiles (seams every 24 in), the field walls, the venue. */
    private SimFrame.Background background(Frame3 cam, double fx, double fy, double cx, double cy) {
        double[] o = cam.origin();
        double[][] r = cam.r();
        double L = fieldHalf;
        double seam = 0.0254 * 0.25;
        return (u, v) -> {
            double a = (u - cx) / fx, b = (v - cy) / fy;
            double dx = r[0][0] * a + r[0][1] * b + r[0][2], dy = r[1][0] * a + r[1][1] * b + r[1][2], dz = r[2][0] * a + r[2][1] * b + r[2][2];
            double tExit = Double.MAX_VALUE;
            if (dx > 1e-9) tExit = Math.min(tExit, (L - o[0]) / dx);
            else if (dx < -1e-9) tExit = Math.min(tExit, (-L - o[0]) / dx);
            if (dy > 1e-9) tExit = Math.min(tExit, (L - o[1]) / dy);
            else if (dy < -1e-9) tExit = Math.min(tExit, (-L - o[1]) / dy);
            if (dz < -1e-9) {
                double tFloor = -o[2] / dz;
                if (tFloor < tExit) {
                    double fxw = o[0] + dx * tFloor + L, fyw = o[1] + dy * tFloor + L;
                    double mx = fxw % 0.6096, my = fyw % 0.6096;
                    boolean onSeam = mx < seam || mx > 0.6096 - seam || my < seam || my > 0.6096 - seam;
                    return onSeam ? SEAM : TILE;
                }
            }
            double zExit = o[2] + dz * Math.max(0, tExit);
            return zExit <= WALL_HEIGHT ? WALL : VENUE;
        };
    }

    private boolean blocked(double[] from, double[] to) {
        for (VisionScene.Box b : occluders) if (b.blocks(from, to)) return true;
        return false;
    }

    private static double[] project(double[] p, double fx, double fy, double cx, double cy) {
        if (p[2] <= 1e-4) return null;
        return new double[]{cx + fx * p[0] / p[2], cy + fy * p[1] / p[2]};
    }

    private static double[][] hull(List<double[]> pts) {
        double[][] p = pts.toArray(new double[0][]);
        java.util.Arrays.sort(p, (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        int n = p.length, k = 0;
        if (n < 3) return p;
        double[][] h = new double[2 * n][];
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
            h[k++] = p[i];
        }
        for (int i = n - 2, t = k + 1; i >= 0; i--) {
            while (k >= t && cross(h[k - 2], h[k - 1], p[i]) <= 0) k--;
            h[k++] = p[i];
        }
        return java.util.Arrays.copyOf(h, k - 1);
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    private static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double norm(double[] a) { return Math.sqrt(dot(a, a)); }

    // ------------------------------------------------------------------ SimCamera

    @Override public String simCheckSize(int width, int height) { return model.sizeError(width, height); }
    @Override public double simMaxFps(int width, int height, boolean mjpeg) { return model.maxFps(width, height, mjpeg); }

    @Override
    public CameraCalibration simCalibration(int width, int height) {
        WebcamModel.Lens lens = model.lens(width, height);
        if (lens.calibration() == null) return null;
        WebcamModel.Calibration c = lens.calibration();
        return new CameraCalibration(width, height, (float) c.fx(), (float) c.fy(), (float) c.cx(), (float) c.cy(),
                lens.scaledFromWidth(), lens.scaledFromHeight());
    }

    @Override public SimCameraControls simControls() { return controls; }

    @Override
    public synchronized boolean simClaim(SimPortal p) {
        if (portal != null && portal != p) return false;
        portal = p;
        return true;
    }

    @Override
    public synchronized void simRelease(SimPortal p) {
        if (portal == p) portal = null;
    }

    // ------------------------------------------------------------------ WebcamName / HardwareDevice

    @Override public SerialNumber getSerialNumber() { return serial; }
    @Override public String getUsbDeviceNameIfAttached() { return "/dev/bus/usb/001/" + String.format("%03d", 2 + Math.abs(def.name().hashCode() % 50)); }
    @Override public boolean isAttached() { return true; }
    @Override public boolean isWebcam() { return true; }
    @Override public boolean isCameraDirection() { return false; }
    @Override public boolean isSwitchable() { return false; }
    @Override public boolean isUnknown() { return false; }
    @Override public Manufacturer getManufacturer() { return Manufacturer.Other; }
    @Override public String getDeviceName() { return model.name(); }
    @Override public String getConnectionInfo() { return "USB " + serial; }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() {}
    @Override public void close() {}

    @Override
    public String toString() {
        return "Webcam(" + def.name() + ")";
    }
}
