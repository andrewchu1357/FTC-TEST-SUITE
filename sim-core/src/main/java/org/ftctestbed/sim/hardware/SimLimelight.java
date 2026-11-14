package org.ftctestbed.sim.hardware;

import com.qualcomm.hardware.limelightvision.LLFieldMap;
import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.hardware.limelightvision.LLResultTypes;
import com.qualcomm.hardware.limelightvision.LLStatus;
import com.qualcomm.hardware.limelightvision.Limelight3A;

import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.vision.CameraModel;
import org.ftctestbed.sim.vision.Fiducial;
import org.ftctestbed.sim.vision.Frame3;
import org.ftctestbed.sim.vision.VisionScene;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A Limelight 3A: a 640 x 480 camera (54.5 x 42 degrees) on the robot that runs one pipeline at a
 * time on every frame, and the SDK's polling of it.
 * - Color pipelines find game pieces of a color: each visible piece is a round blob; blobs that
 *   touch in the picture are one contour (as thresholded pixels would be); the largest is the
 *   target (tx, ty, ta). 90 frames a second.
 * - The AprilTag pipeline (36h11) reads tags that are wholly in the picture, big enough
 *   (about 24 px across, from the 2x-downscaled 640 x 480 it decodes [ESTIMATE]) and not seen too
 *   edge-on (70 degrees [ESTIMATE]); its pose estimates get noisier with distance. 50 frames a
 *   second. The robot's pose (botpose, MegaTag 1) comes from where the field map says the tags are;
 *   MegaTag 2 takes the heading from updateRobotOrientation(). On a field whose tags move (BIOBUZZ's
 *   tip with the HIVE), a pose from a moved tag is wrong, as on the real field.
 * - Robots and opaque field elements hide what's behind them.
 * - Each frame's result is ready a frame plus the pipeline's processing time after it was taken;
 *   the Control Hub asks for the newest one 100 times a second (setPollRateHz). A pipeline switch
 *   takes effect from the second frame after it [ESTIMATE: Limelight says a frame or two], and each
 *   result says which pipeline made it.
 * Physics thread: step(). Robot thread: the Limelight3A methods (no locks held while waiting).
 */
public class SimLimelight extends Limelight3A {
    static final double MIN_TAG_PIXELS = 24, MAX_TAG_ANGLE = Math.toRadians(70);
    static final double CORNER_NOISE_PX = 0.3;
    static final double COLOR_FPS = 90, TAG_FPS = 50;
    static final double COLOR_PROCESSING_MS = 3, TAG_PROCESSING_MS = 9;

    public final RobotConfig.Limelight def;
    public final CameraModel camera = CameraModel.LIMELIGHT_3A;
    private final Runnable network;
    private Random random;

    // Robot thread's commands
    private volatile boolean polling;
    private volatile int pollRateHz = 100;
    private volatile int requestedPipeline;
    private volatile double robotYawDeg;
    private volatile List<Fiducial> uploadedMap;

    // Physics thread
    private int activePipeline, pendingPipeline = -1, framesUntilSwitch;
    private double sinceFrame, sincePoll, uptime;
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private volatile LLResult latest;
    private volatile double lastResultTime = Double.NaN;
    private volatile Frame lastFrame;

    /** One processed frame: when it's ready (s), its JSON, and what it found (for the window). */
    public record Frame(double readyAt, Map<String, Object> json, int pipeline, List<Target> targets, Frame3 camera) {}

    /** Something a pipeline found, in pixels: a blob's box or a tag's corners, for drawing. */
    public record Target(String label, double[][] corners, boolean primary) {}

    /** network runs for each command the SDK sends the camera over USB (see HubTiming). */
    public SimLimelight(RobotConfig.Limelight def, Runnable network) {
        super(def.name());
        this.def = def;
        this.network = network;
        this.random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(def.name().hashCode() * 131L + 3));
        this.activePipeline = 0;
    }

    private RobotConfig.Limelight.Pipeline pipeline(int index) {
        for (RobotConfig.Limelight.Pipeline p : def.pipelines()) if (p.index() == index) return p;
        return null;
    }

    /** The camera's frame on the robot (robot frame: x forward, y left, z up from the floor). */
    public Frame3 mount() {
        return mount(def);
    }

    /** A Limelight's lens frame on the robot (x right, y down, z out of the lens, in robot coordinates). */
    public static Frame3 mount(RobotConfig.Limelight def) {
        double yaw = def.heading(), tilt = def.tiltUp(), roll = def.roll();
        double[] f = {Math.cos(tilt) * Math.cos(yaw), Math.cos(tilt) * Math.sin(yaw), Math.sin(tilt)};
        double[] left = {-Math.sin(yaw), Math.cos(yaw), 0};
        double[] up = {f[1] * left[2] - f[2] * left[1], f[2] * left[0] - f[0] * left[2], f[0] * left[1] - f[1] * left[0]};
        double[] right = {-left[0], -left[1], -left[2]}, down = {-up[0], -up[1], -up[2]};
        // Roll turns the picture about the lens axis.
        double c = Math.cos(roll), s = Math.sin(roll);
        double[] x = {c * right[0] + s * down[0], c * right[1] + s * down[1], c * right[2] + s * down[2]};
        double[] y = {-s * right[0] + c * down[0], -s * right[1] + c * down[1], -s * right[2] + c * down[2]};
        return Frame3.ofAxes(x, y, f, new double[]{def.x(), def.y(), def.z()});
    }

    // ------------------------------------------------------------------ physics thread

    /** Physics thread: time moves on by dt; the robot is at (x, y, heading). */
    public void step(double dt, double now, double x, double y, double heading, VisionScene scene) {
        uptime += dt;
        sinceFrame += dt;
        RobotConfig.Limelight.Pipeline p = pipeline(activePipeline);
        double fps = p != null && p.type().equals("fiducial") ? TAG_FPS : COLOR_FPS;
        if (sinceFrame >= 1 / fps - 1e-9) {
            sinceFrame = 0;
            // A switch the robot asked for takes effect from the second frame after it.
            int requested = requestedPipeline;
            if (requested != activePipeline && requested != pendingPipeline) {
                pendingPipeline = requested;
                framesUntilSwitch = 2;
            }
            if (pendingPipeline >= 0 && --framesUntilSwitch <= 0) {
                activePipeline = pendingPipeline;
                pendingPipeline = -1;
                p = pipeline(activePipeline);
                fps = p != null && p.type().equals("fiducial") ? TAG_FPS : COLOR_FPS;
            }
            Frame3 robot = Frame3.planar(x, y, 0, heading);
            Frame3 cam = robot.compose(mount());
            double processing = p != null && p.type().equals("fiducial") ? TAG_PROCESSING_MS : COLOR_PROCESSING_MS;
            double capture = 1000 / fps;
            Frame frame = process(p, cam, robot, scene, now, capture, processing);
            frames.addLast(new Frame(now + (capture + processing) / 1000, frame.json(), activePipeline, frame.targets(), cam));
            while (frames.size() > 8) frames.removeFirst();
        }
        // Frames become available when they're processed.
        while (!frames.isEmpty() && frames.peekFirst().readyAt() <= now) lastFrame = frames.removeFirst();
        // The SDK polls the camera.
        sincePoll += dt;
        if (polling && sincePoll >= 1.0 / pollRateHz - 1e-9) {
            sincePoll = 0;
            Frame f = lastFrame;
            if (f != null) {
                latest = LLResult.simCreate(f.json(), 0.2);
                lastResultTime = now;
            }
        }
    }

    /** The newest processed frame, for the camera window (or null). */
    public Frame lastFrame() {
        return lastFrame;
    }

    /** The pipeline running now. */
    public int activePipeline() {
        return activePipeline;
    }

    private Frame process(RobotConfig.Limelight.Pipeline p, Frame3 cam, Frame3 robot, VisionScene scene, double now,
                          double captureMs, double processingMs) {
        Map<String, Object> json = new LinkedHashMap<>();
        List<Target> targets = new ArrayList<>();
        json.put("pID", (double) activePipeline);
        json.put("pipelineType", p == null ? "pipe_color" : p.type().equals("fiducial") ? "pipe_fiducial" : "pipe_color");
        json.put("cl", captureMs);
        json.put("tl", processingMs);
        json.put("ts", now * 1000);
        json.put("v", 0.0);
        if (p == null) return new Frame(now, json, activePipeline, targets, cam);
        if (p.type().equals("fiducial")) fiducials(p, cam, robot, scene, json, targets);
        else color(p, cam, scene, json, targets);
        return new Frame(now, json, activePipeline, targets, cam);
    }

    private boolean blocked(double[] from, double[] to, VisionScene scene) {
        for (VisionScene.Box b : scene.occluders()) if (b.blocks(from, to)) return true;
        return false;
    }

    // ------------------------------------------------------------------ color pipelines

    /** A blob in the picture: center (px), radius (px), area (px^2), box. */
    private static final class Blob {
        double u, v, area, minU, minV, maxU, maxV;
        final List<double[]> circles = new ArrayList<>();
    }

    private boolean matches(RobotConfig.Limelight.Pipeline p, VisionScene.Ball b) {
        String t = p.target();
        return switch (t) {
            case "red", "blue" -> t.equals(b.alliance()) || (b.alliance() == null && colorFamily(b.color()).equals(t));
            case "yellow", "pollen" -> b.alliance() == null && colorFamily(b.color()).equals("yellow");
            default -> b.kind().equals(t) || b.kind().startsWith(t);
        };
    }

    /** Which hue a color is closest to: "yellow", "red", "blue", "green" or "other". */
    static String colorFamily(int rgb) {
        float[] hsb = java.awt.Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        double h = hsb[0] * 360;
        if (hsb[1] < 0.35) return "other";
        if (h < 20 || h >= 330) return "red";
        if (h < 70) return "yellow";
        if (h < 170) return "green";
        if (h < 260) return "blue";
        return "other";
    }

    private void color(RobotConfig.Limelight.Pipeline p, Frame3 cam, VisionScene scene, Map<String, Object> json, List<Target> targets) {
        double w = camera.width(), h = camera.height();
        List<Blob> blobs = new ArrayList<>();
        double[] eye = cam.origin();
        for (VisionScene.Ball b : scene.balls()) {
            if (!matches(p, b)) continue;
            double[] center = {b.x(), b.y(), b.z()};
            double[] c = cam.toLocal(center);
            if (c[2] <= b.radius()) continue;
            double[] px = camera.project(c);
            double rpx = camera.fx() * b.radius() / Math.sqrt(Math.max(1e-9, c[0] * c[0] + c[1] * c[1] + c[2] * c[2] - b.radius() * b.radius()));
            if (px[0] + rpx < 0 || px[0] - rpx > w || px[1] + rpx < 0 || px[1] - rpx > h) continue;
            if (blocked(eye, center, scene)) continue;
            Blob blob = new Blob();
            blob.circles.add(new double[]{px[0], px[1], rpx});
            blob.minU = Math.max(0, px[0] - rpx);
            blob.maxU = Math.min(w, px[0] + rpx);
            blob.minV = Math.max(0, px[1] - rpx);
            blob.maxV = Math.min(h, px[1] + rpx);
            double full = (2 * rpx) * (2 * rpx);
            blob.area = Math.PI * rpx * rpx * ((blob.maxU - blob.minU) * (blob.maxV - blob.minV) / full);
            blob.u = (blob.minU + blob.maxU) / 2;
            blob.v = (blob.minV + blob.maxV) / 2;
            blobs.add(blob);
        }
        // Pieces touching in the picture are one contour, and so are ones closer than the
        // threshold's net growth (dilate - erode, on each side) lets apart.
        double gap = 2 * Math.max(0, p.dilate() - p.erode());
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < blobs.size(); i++) {
                for (int j = i + 1; j < blobs.size(); j++) {
                    if (touch(blobs.get(i), blobs.get(j), gap)) {
                        Blob a = blobs.get(i), b = blobs.remove(j);
                        double overlap = overlap(a, b);
                        double area = a.area + b.area - overlap;
                        a.u = (a.u * a.area + b.u * b.area) / (a.area + b.area);
                        a.v = (a.v * a.area + b.v * b.area) / (a.area + b.area);
                        a.area = area;
                        a.minU = Math.min(a.minU, b.minU);
                        a.minV = Math.min(a.minV, b.minV);
                        a.maxU = Math.max(a.maxU, b.maxU);
                        a.maxV = Math.max(a.maxV, b.maxV);
                        a.circles.addAll(b.circles);
                        merged = true;
                        break outer;
                    }
                }
            }
        }
        double total = w * h;
        blobs.removeIf(b -> b.area / total * 100 < p.minAreaPct() || b.area / total * 100 > p.maxAreaPct());
        blobs.sort((a, b) -> Double.compare(b.area, a.area));
        List<Map<String, Object>> retro = new ArrayList<>();
        for (int i = 0; i < blobs.size(); i++) {
            Blob b = blobs.get(i);
            Map<String, Object> m = target(b.u, b.v, b.area / total * 100,
                    new double[][]{{b.minU, b.minV}, {b.maxU, b.minV}, {b.maxU, b.maxV}, {b.minU, b.maxV}});
            retro.add(m);
            targets.add(new Target(p.target(), new double[][]{{b.minU, b.minV}, {b.maxU, b.minV}, {b.maxU, b.maxV}, {b.minU, b.maxV}}, i == 0));
        }
        json.put("Retro", retro);
        if (!blobs.isEmpty()) primary(json, retro.get(0));
    }

    private static boolean touch(Blob a, Blob b, double gap) {
        for (double[] p : a.circles) {
            for (double[] q : b.circles) {
                if (Math.hypot(p[0] - q[0], p[1] - q[1]) < p[2] + q[2] + gap) return true;
            }
        }
        return false;
    }

    /** Area two circles share (px^2), summed over the pairs that overlap. */
    private static double overlap(Blob a, Blob b) {
        double sum = 0;
        for (double[] p : a.circles) {
            for (double[] q : b.circles) {
                double d = Math.hypot(p[0] - q[0], p[1] - q[1]), r1 = p[2], r2 = q[2];
                if (d >= r1 + r2) continue;
                if (d <= Math.abs(r1 - r2)) {
                    sum += Math.PI * Math.min(r1, r2) * Math.min(r1, r2);
                    continue;
                }
                double a1 = r1 * r1 * Math.acos((d * d + r1 * r1 - r2 * r2) / (2 * d * r1));
                double a2 = r2 * r2 * Math.acos((d * d + r2 * r2 - r1 * r1) / (2 * d * r2));
                double a3 = 0.5 * Math.sqrt(Math.max(0, (-d + r1 + r2) * (d + r1 - r2) * (d - r1 + r2) * (d + r1 + r2)));
                sum += a1 + a2 - a3;
            }
        }
        return sum;
    }

    private Map<String, Object> target(double u, double v, double areaPct, double[][] corners) {
        Map<String, Object> m = new LinkedHashMap<>();
        double tx = camera.txDegrees(u), ty = camera.tyDegrees(v);
        m.put("tx", tx);
        m.put("ty", ty);
        m.put("tx_nocross", tx);
        m.put("ty_nocross", ty);
        m.put("txp", u);
        m.put("typ", v);
        m.put("ta", areaPct);
        List<List<Double>> pts = new ArrayList<>();
        for (double[] c : corners) pts.add(List.of(c[0], c[1]));
        m.put("pts", pts);
        return m;
    }

    private static void primary(Map<String, Object> json, Map<String, Object> t) {
        json.put("v", 1.0);
        json.put("tx", t.get("tx"));
        json.put("ty", t.get("ty"));
        json.put("txnc", t.get("tx_nocross"));
        json.put("tync", t.get("ty_nocross"));
        json.put("ta", t.get("ta"));
    }

    // ------------------------------------------------------------------ AprilTags

    private void fiducials(RobotConfig.Limelight.Pipeline p, Frame3 cam, Frame3 robot, VisionScene scene,
                           Map<String, Object> json, List<Target> targets) {
        Map<Integer, Fiducial> map = new HashMap<>();
        List<Fiducial> uploaded = uploadedMap;
        for (Fiducial f : uploaded != null ? uploaded : scene.fieldMap()) map.put(f.id(), f);
        double[] eye = cam.origin();
        List<Map<String, Object>> found = new ArrayList<>();
        List<double[]> poses = new ArrayList<>(), posesMt2 = new ArrayList<>();
        double areaSum = 0, distSum = 0;
        List<double[]> tagCenters = new ArrayList<>();
        Frame3 camInRobot = mount();
        for (Fiducial f : scene.fiducials()) {
            if (!p.family().equalsIgnoreCase(f.family())) continue;
            double[][] corners = f.corners();
            double[][] px = new double[4][];
            boolean inView = true;
            for (int i = 0; i < 4; i++) {
                px[i] = camera.project(cam.toLocal(corners[i]));
                if (!camera.inside(px[i])) inView = false;
            }
            if (!inView) continue;
            // Facing the camera, not too edge-on.
            double[] toCam = sub(eye, f.frame().origin());
            double dist = norm(toCam);
            double cosView = dot(f.facing(), toCam) / dist;
            if (cosView < Math.cos(MAX_TAG_ANGLE)) continue;
            double quad = quadArea(px);
            if (Math.sqrt(quad) < MIN_TAG_PIXELS) continue;
            if (blocked(eye, f.frame().origin(), scene)) continue;

            // Pixel noise on the corners, and the pose noise it makes (worse with distance).
            double[][] noisy = new double[4][2];
            double cu = 0, cv = 0;
            for (int i = 0; i < 4; i++) {
                noisy[i][0] = px[i][0] + random.nextGaussian() * CORNER_NOISE_PX;
                noisy[i][1] = px[i][1] + random.nextGaussian() * CORNER_NOISE_PX;
                cu += noisy[i][0] / 4;
                cv += noisy[i][1] / 4;
            }
            // Distance comes from the tag's size in pixels, so it's the least certain.
            double sLateral = CORNER_NOISE_PX / camera.fx() * dist;
            double sDepth = dist * CORNER_NOISE_PX / Math.sqrt(quad);
            double sAngle = 2 * CORNER_NOISE_PX / Math.sqrt(quad);
            Frame3 tagInCam = cam.inverse().compose(f.frame());
            double[] dir = tagInCam.origin();
            double k = random.nextGaussian() * sDepth / Math.max(1e-6, norm(dir));
            Frame3 measured = tagInCam.perturbed(random.nextGaussian() * sAngle, random.nextGaussian() * sAngle,
                    random.nextGaussian() * sAngle * 0.5,
                    random.nextGaussian() * sLateral + dir[0] * k, random.nextGaussian() * sLateral + dir[1] * k, dir[2] * k);

            Map<String, Object> m = target(cu, cv, quad / (camera.width() * camera.height()) * 100, noisy);
            m.put("fID", (double) f.id());
            m.put("fam", "36H11");
            m.put("skew", 0.0);
            // Limelight's camera/target/robot-space arrays: x, y, z (m), then pitch, yaw, roll (degrees).
            m.put("t6t_cs", cameraStyle(measured));
            m.put("t6c_ts", cameraStyle(measured.inverse()));
            Frame3 tagInRobot = camInRobot.compose(measured);
            m.put("t6t_rs", robotSpace(tagInRobot));
            m.put("t6r_ts", cameraStyle(tagInRobot.inverse()));
            Fiducial mapped = map.get(f.id());
            if (mapped != null) {
                // Where the robot must be for the tag to be where the map says (MegaTag 1, this tag alone).
                Frame3 robotInField = mapped.frame().compose(measured.inverse()).compose(camInRobot.inverse());
                double[] pose = fieldPose(robotInField);
                m.put("t6r_fs", pose);
                poses.add(new double[]{pose[0], pose[1], pose[2], pose[5], quad});
                // MegaTag 2: heading from the robot, position from the tag's offset.
                double yaw = Math.toRadians(robotYawDeg);
                Frame3 flat = Frame3.planar(0, 0, 0, yaw).compose(camInRobot);
                double[] offset = flat.rotate(measured.origin());
                double[] camPos = sub(mapped.frame().origin(), offset);
                double[] mount = Frame3.planar(0, 0, 0, yaw).rotate(camInRobot.origin());
                posesMt2.add(new double[]{camPos[0] - mount[0], camPos[1] - mount[1], camPos[2] - mount[2], robotYawDeg, quad});
                tagCenters.add(mapped.frame().origin());
                areaSum += quad / (camera.width() * camera.height()) * 100;
                distSum += dist;
            }
            found.add(m);
            targets.add(new Target(String.valueOf(f.id()), noisy, false));
        }
        // The primary tag: the biggest in the picture.
        found.sort((a, b) -> Double.compare((Double) b.get("ta"), (Double) a.get("ta")));
        json.put("Fiducial", found);
        if (!found.isEmpty()) {
            primary(json, found.get(0));
            for (int i = 0; i < targets.size(); i++) {
                Target t = targets.get(i);
                if (t.label().equals(String.valueOf(((Double) found.get(0).get("fID")).intValue()))) {
                    targets.set(i, new Target(t.label(), t.corners(), true));
                }
            }
        }
        if (!poses.isEmpty()) {
            json.put("botpose", average(poses, false));
            json.put("botpose_orb", average(posesMt2, true));
            json.put("botpose_tagcount", (double) poses.size());
            double span = 0;
            for (double[] a : tagCenters) for (double[] b : tagCenters) span = Math.max(span, norm(sub(a, b)));
            json.put("botpose_span", span);
            json.put("botpose_avgdist", distSum / poses.size());
            json.put("botpose_avgarea", areaSum / poses.size());
            double d = distSum / poses.size(), n = Math.sqrt(poses.size());
            json.put("stdev_mt1", new double[]{0.02 * d * d / n, 0.02 * d * d / n, 0.02 * d * d / n, 1.5 * d / n, 1.5 * d / n, 1.5 * d / n});
            json.put("stdev_mt2", new double[]{0.01 * d * d / n, 0.01 * d * d / n, 0.01 * d * d / n, 0, 0, 0});
        }
    }

    /** Field pose {x, y, z, roll, pitch, yaw} (m, degrees) of a robot frame (x forward, y left, z up). */
    private static double[] fieldPose(Frame3 robot) {
        double[] rpy = robot.rollPitchYaw();
        double[] o = robot.origin();
        return new double[]{o[0], o[1], o[2], Math.toDegrees(rpy[0]), Math.toDegrees(rpy[1]), Math.toDegrees(rpy[2])};
    }

    /**
     * A pose in a camera- or target-space frame (x right, y down, z out of the lens / into the tag),
     * as Limelight's arrays have it: x, y, z, then pitch (about x), yaw (about y), roll (about z)
     * in degrees. A tag squarely facing the camera is all zero angles.
     */
    private static double[] cameraStyle(Frame3 f) {
        double[][] r = f.r();
        // Yaw about y, then pitch about x, then roll about z.
        double pitch = Math.asin(Math.max(-1, Math.min(1, -r[1][2])));
        double yaw = Math.atan2(r[0][2], r[2][2]);
        double roll = Math.atan2(r[1][0], r[1][1]);
        double[] o = f.origin();
        return new double[]{o[0], o[1], o[2], Math.toDegrees(pitch), Math.toDegrees(yaw), Math.toDegrees(roll)};
    }

    /**
     * A tag's pose in Limelight's robot space: x forward, y right, z up (the robot frame here has y
     * left), angles as pitch, yaw, roll (degrees) of the tag's frame about those axes.
     */
    private static double[] robotSpace(Frame3 tagInRobot) {
        double[] o = tagInRobot.origin();
        double[] rpy = new Frame3(flipY(tagInRobot.r()), o).rollPitchYaw();
        return new double[]{o[0], -o[1], o[2], Math.toDegrees(rpy[1]), Math.toDegrees(rpy[2]), Math.toDegrees(rpy[0])};
    }

    private static double[][] flipY(double[][] r) {
        double[][] m = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) m[i][j] = r[i][j] * (i == 1 ? -1 : 1) * (j == 1 ? -1 : 1);
        return m;
    }

    private static double[] average(List<double[]> poses, boolean mt2) {
        double w = 0, x = 0, y = 0, z = 0, sy = 0, cy = 0;
        for (double[] p : poses) {
            double k = p[4];
            w += k;
            x += p[0] * k;
            y += p[1] * k;
            z += p[2] * k;
            sy += Math.sin(Math.toRadians(p[3])) * k;
            cy += Math.cos(Math.toRadians(p[3])) * k;
        }
        return new double[]{x / w, y / w, z / w, 0, 0, Math.toDegrees(Math.atan2(sy, cy))};
    }

    private static double quadArea(double[][] p) {
        double a = 0;
        for (int i = 0; i < 4; i++) {
            double[] u = p[i], v = p[(i + 1) % 4];
            a += u[0] * v[1] - v[0] * u[1];
        }
        return Math.abs(a) / 2;
    }

    private static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double norm(double[] a) { return Math.sqrt(dot(a, a)); }

    /** Power on: pipeline 0, no polling, nothing uploaded. */
    public void powerCycle() {
        polling = false;
        requestedPipeline = 0;
        activePipeline = 0;
        pendingPipeline = -1;
        latest = null;
        lastFrame = null;
        frames.clear();
        uploadedMap = null;
        robotYawDeg = 0;
        uptime = 0;
        sinceFrame = sincePoll = 0;
        framesUntilSwitch = 0;
        lastResultTime = Double.NaN;
        // The same noise from power-on, so a match replayed from the same start plays out the same.
        random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(def.name().hashCode() * 131L + 3));
    }

    // ------------------------------------------------------------------ robot thread (the SDK API)

    @Override public synchronized void start() { polling = true; }
    @Override public synchronized void pause() { polling = false; }
    @Override public synchronized void stop() { polling = false; }
    @Override public boolean isRunning() { return polling; }
    @Override public synchronized void setPollRateHz(int rateHz) { pollRateHz = Math.max(1, Math.min(250, rateHz)); }

    @Override
    public long getTimeSinceLastUpdate() {
        LLResult r = latest;
        return r == null ? Long.MAX_VALUE : r.getStaleness();
    }

    @Override public boolean isConnected() { return true; }
    @Override public LLResult getLatestResult() { return latest; }

    @Override
    public LLStatus getStatus() {
        network.run();
        RobotConfig.Limelight.Pipeline p = pipeline(activePipeline);
        boolean tags = p != null && p.type().equals("fiducial");
        return LLStatus.simCreate(def.name(), activePipeline, p == null ? "" : tags ? "pipe_fiducial" : "pipe_color",
                tags ? TAG_FPS : COLOR_FPS, tags ? 61 : 38, 42 + Math.min(15, uptime / 60), robotYawDeg);
    }

    @Override public boolean reloadPipeline() { network.run(); return true; }

    @Override
    public boolean pipelineSwitch(int index) {
        network.run();
        if (index < 0 || index > 9) return false;
        requestedPipeline = index;
        return true;
    }

    @Override public boolean captureSnapshot(String snapname) { network.run(); return true; }
    @Override public boolean deleteSnapshots() { network.run(); return true; }
    @Override public boolean deleteSnapshot(String snapname) { network.run(); return true; }
    @Override public boolean updatePythonInputs(double[] inputs) { network.run(); return true; }

    @Override
    public boolean updateRobotOrientation(double yaw) {
        network.run();
        robotYawDeg = yaw;
        return true;
    }

    @Override public boolean uploadPipeline(String jsonString, Integer index) { network.run(); return true; }

    /** A field map from robot code replaces the default one (the season's tags as a match starts). */
    @Override
    public boolean uploadFieldmap(LLFieldMap fieldmap, Integer index) {
        network.run();
        if (fieldmap == null || !fieldmap.isValid()) return false;
        List<Fiducial> tags = new ArrayList<>();
        for (LLFieldMap.Fiducial f : fieldmap.getFiducials()) {
            List<Double> t = f.getTransform();
            double[] x = {t.get(0), t.get(4), t.get(8)}, y = {t.get(1), t.get(5), t.get(9)}, z = {t.get(2), t.get(6), t.get(10)};
            tags.add(new Fiducial(f.getId(), "36h11", f.getSize() / 1000, Frame3.ofAxes(x, y, z,
                    new double[]{t.get(3), t.get(7), t.get(11)}), ""));
        }
        uploadedMap = tags;
        return true;
    }

    @Override public boolean uploadPython(String pythonString, Integer index) { network.run(); return true; }

    private LLResultTypes.CalibrationResult calibration() {
        network.run();
        return new LLResultTypes.CalibrationResult("Limelight 3A (simulated)", camera.width(), camera.height(), 0.3,
                camera.matrix(), new double[]{0, 0, 0, 0, 0});
    }

    @Override public LLResultTypes.CalibrationResult getCalDefault() { return calibration(); }
    @Override public LLResultTypes.CalibrationResult getCalFile() { return calibration(); }
    @Override public LLResultTypes.CalibrationResult getCalEEPROM() { return calibration(); }
    @Override public LLResultTypes.CalibrationResult getCalLatest() { return calibration(); }

    @Override public String getConnectionInfo() { return "USB (simulated Limelight 3A \"" + def.name() + "\")"; }
}
