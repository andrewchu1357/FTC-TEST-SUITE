package org.firstinspires.ftc.vision.apriltag;

import android.graphics.Canvas;

import com.qualcomm.robotcore.util.RobotLog;

import org.firstinspires.ftc.robotcore.external.matrices.GeneralMatrixF;
import org.firstinspires.ftc.robotcore.external.matrices.OpenGLMatrix;
import org.firstinspires.ftc.robotcore.external.matrices.VectorF;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Pose3D;
import org.firstinspires.ftc.robotcore.external.navigation.Position;
import org.firstinspires.ftc.robotcore.external.navigation.Quaternion;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibration;
import org.ftctestbed.shim.SimHooks;
import org.ftctestbed.shim.vision.PnpSolver;
import org.ftctestbed.shim.vision.SimFrame;
import org.ftctestbed.shim.vision.SimProcessor;
import org.opencv.core.Mat;
import org.opencv.core.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * The SDK's AprilTagProcessor on simulated frames. Decoding: a tag of the processor's family is
 * found if it's big enough in the picture for the decimation (about 10 px at decimation 1, 8.65 px
 * per decimation step above that, from the SDK sample's "2 inch tag at 10 / 6 / 4 feet"
 * [ESTIMATE]), not smeared by motion blur over most of a cell [ESTIMATE], and the picture isn't
 * far too dark or bright [ESTIMATE]. Poses are solved from the noisy pixel corners with the lens
 * numbers the processor has (yours, the SDK's built-in calibration, or its 578.272 px fallback):
 * then ftcPose, robotPose and clusters exactly as the SDK computes them.
 */
public class AprilTagProcessorImpl extends AprilTagProcessor implements SimProcessor {
    public static final String TAG = "AprilTagProcessorImpl";
    /** Below this brightness tags don't decode; above the upper one they're washed out [ESTIMATE]. */
    static final double MIN_BRIGHTNESS = 0.25, MAX_BRIGHTNESS = 4.0;

    private volatile ArrayList<AprilTagDetection> detections = new ArrayList<AprilTagDetection>();
    private ArrayList<AprilTagDetection> detectionsUpdate = new ArrayList<AprilTagDetection>();
    private final Object detectionsUpdateSync = new Object();
    private double fx, fy, cx, cy;
    private final boolean suppressCalibrationWarnings;
    private final AprilTagLibrary tagLibrary;
    private volatile float decimation = 3;
    private final DistanceUnit outputUnitsLength;
    private final AngleUnit outputUnitsAngle;
    private volatile PoseSolver poseSolver = PoseSolver.APRILTAG_BUILTIN;
    private final OpenGLMatrix robotInCameraFrame;
    private final TagFamily tagFamily;
    private volatile int solveMillis;

    public AprilTagProcessorImpl(OpenGLMatrix robotInCameraFrame, double fx, double fy, double cx, double cy,
                                 DistanceUnit outputUnitsLength, AngleUnit outputUnitsAngle, AprilTagLibrary tagLibrary,
                                 boolean drawAxes, boolean drawCube, boolean drawOutline, boolean drawTagID,
                                 TagFamily tagFamily, int threads, boolean suppressCalibrationWarnings) {
        this.robotInCameraFrame = robotInCameraFrame;
        this.fx = fx;
        this.fy = fy;
        this.cx = cx;
        this.cy = cy;
        this.tagLibrary = tagLibrary;
        this.outputUnitsLength = outputUnitsLength;
        this.outputUnitsAngle = outputUnitsAngle;
        this.suppressCalibrationWarnings = suppressCalibrationWarnings;
        this.tagFamily = tagFamily;
    }

    @Override
    public void init(int width, int height, CameraCalibration calibration) {
        if (fx != 0 && fy != 0 && cx != 0 && cy != 0) {
            SimHooks.log("D", TAG, String.format("User provided their own camera calibration fx=%7.3f fy=%7.3f cx=%7.3f cy=%7.3f", fx, fy, cx, cy));
        } else if (calibration != null && !calibration.isDegenerate()) {
            fx = calibration.focalLengthX;
            fy = calibration.focalLengthY;
            cx = calibration.principalPointX;
            cy = calibration.principalPointY;
            if (calibration.simScaledFromWidth > 0) {
                String msg = String.format("Camera has not been calibrated for [%dx%d]; applying a scaled calibration from [%dx%d].",
                        width, height, calibration.simScaledFromWidth, calibration.simScaledFromHeight);
                SimHooks.log("D", TAG, msg);
                if (!suppressCalibrationWarnings) RobotLog.addGlobalWarningMessage(msg);
            } else {
                SimHooks.log("D", TAG, String.format("User did not provide a camera calibration; but we DO have a built in calibration we can use.\n [%dx%d] (NOT scaled)\nfx=%7.3f fy=%7.3f cx=%7.3f cy=%7.3f",
                        width, height, fx, fy, cx, cy));
            }
        } else {
            String msg = "User did not provide a camera calibration, nor was a built-in calibration found for this camera. Pose estimates will likely be inaccurate.";
            SimHooks.log("D", TAG, msg);
            if (!suppressCalibrationWarnings) RobotLog.addGlobalWarningMessage(msg);
            fx = 578.272;
            fy = 578.272;
            cx = width / 2;
            cy = height / 2;
        }
    }

    @Override
    public Object processFrame(Mat input, long captureTimeNanos) {
        return detections; // the simulator calls simProcessFrame instead
    }

    @Override
    public void onDrawFrame(Canvas canvas, int onscreenWidth, int onscreenHeight, float scaleBmpPxToCanvasPx, float scaleCanvasDensity, Object userContext) {}

    @Override
    public double simCostMillis(SimFrame f) {
        double megapixels = f.width * (double) f.height / 307200.0;
        return megapixels * (8 + 37 / Math.pow(Math.max(1, decimation), 1.3)) + 1.0 * f.tags.size(); // [ESTIMATE]
    }

    /** A tag the detector decoded in this frame. */
    private static final class Found {
        final int id;
        final Point center;
        final Point[] corners;
        final float decisionMargin;

        Found(int id, Point center, Point[] corners, float decisionMargin) {
            this.id = id;
            this.center = center;
            this.corners = corners;
            this.decisionMargin = decisionMargin;
        }
    }

    @Override
    public void simProcessFrame(SimFrame f) {
        long t0 = System.nanoTime();
        double minSide = Math.max(10.4, 8.65 * Math.max(1, decimation));
        List<Found> found = new ArrayList<Found>();
        boolean exposed = f.brightness >= MIN_BRIGHTNESS && f.brightness <= MAX_BRIGHTNESS;
        for (SimFrame.Tag t : f.tags) {
            if (!exposed || !tagFamily.code.equals(t.family)) continue;
            double side = Math.sqrt(quadArea(t.corners));
            if (side < minSide) continue;
            double cell = side / tagFamily.cells;
            if (t.blur > 0.8 * cell) continue;
            double margin = 5 + 95 * Math.min(1, side / 60) * (1 - t.blur / (0.8 * cell)) * Math.min(1, f.brightness / 0.6);
            Point[] corners = new Point[4];
            for (int i = 0; i < 4; i++) corners[i] = new Point(t.corners[i][0], t.corners[i][1]);
            found.add(new Found(t.id, diagonalsCross(t.corners), corners, (float) margin));
        }
        // Tags that belong to a cluster are solved together; others alone.
        List<AprilTagClusterMetadata> clusters = new ArrayList<AprilTagClusterMetadata>();
        List<List<Found>> clusterTags = new ArrayList<List<Found>>();
        List<Found> singles = new ArrayList<Found>();
        for (Found d : found) {
            AprilTagClusterMetadata c = tagLibrary.lookupCluster(d.id);
            if (c == null) {
                singles.add(d);
                continue;
            }
            int i = clusters.indexOf(c);
            if (i < 0) {
                clusters.add(c);
                clusterTags.add(new ArrayList<Found>());
                i = clusters.size() - 1;
            }
            clusterTags.get(i).add(d);
        }
        ArrayList<AprilTagDetection> out = new ArrayList<AprilTagDetection>();
        PoseSolver solver = poseSolver;
        for (int k = 0; k < clusters.size(); k++) {
            AprilTagClusterMetadata c = clusters.get(k);
            List<Found> members = clusterTags.get(k);
            AprilTagPoseRaw raw = solveCluster(c, members);
            if (raw == null) continue;
            AprilTagPoseFtc ftc = rawPoseToFtcPose(raw);
            Pose3D robot = computeRobotPose(raw, c.fieldPosition, c.fieldOrientation, f.captureNanos);
            int percent = Math.round(members.size() / (float) c.clusterMembers.size() * 100f);
            out.add(new AprilTagClusterDetection(percent, c, outputUnitsLength, ftc, raw, robot, f.captureNanos));
        }
        for (Found d : singles) {
            AprilTagMetadata metadata = tagLibrary.lookupTag(d.id);
            AprilTagPoseRaw raw = null;
            AprilTagPoseFtc ftc = null;
            Pose3D robot = null;
            if (metadata != null) {
                raw = solveSingle(metadata, d.corners);
                if (raw != null) {
                    ftc = rawPoseToFtcPose(raw);
                    robot = computeRobotPose(raw, metadata.fieldPosition, metadata.fieldOrientation, f.captureNanos);
                }
            }
            out.add(new AprilTagSingleDetection(d.id, 0, d.decisionMargin, d.center, d.corners, metadata, ftc, raw, robot, f.captureNanos, outputUnitsLength));
        }
        if (!found.isEmpty()) solveMillis = (int) Math.max(1, (System.nanoTime() - t0) / 1_000_000L / found.size());
        detections = out;
        synchronized (detectionsUpdateSync) {
            detectionsUpdate = out;
        }
    }

    private AprilTagPoseRaw solveSingle(AprilTagMetadata metadata, Point[] corners) {
        double s = outputUnitsLength.fromUnit(metadata.distanceUnit, metadata.tagsize) / 2;
        double[][] obj = {{-s, s, 0}, {s, s, 0}, {s, -s, 0}, {-s, -s, 0}};
        double[][] img = new double[4][];
        for (int i = 0; i < 4; i++) img[i] = new double[]{corners[i].x, corners[i].y};
        return toRaw(PnpSolver.solve(obj, img, fx, fy, cx, cy));
    }

    private AprilTagPoseRaw solveCluster(AprilTagClusterMetadata c, List<Found> members) {
        double[][] obj = new double[members.size() * 4][];
        double[][] img = new double[members.size() * 4][];
        for (int i = 0; i < members.size(); i++) {
            Found d = members.get(i);
            AprilTagClusterMemberMetadata m = c.getMemberMetadata(d.id);
            double s = outputUnitsLength.fromUnit(c.distanceUnit, m.tagsize);
            double px = outputUnitsLength.fromUnit(c.distanceUnit, m.positionInClusterPlane.get(0));
            double py = outputUnitsLength.fromUnit(c.distanceUnit, m.positionInClusterPlane.get(1));
            double pz = outputUnitsLength.fromUnit(c.distanceUnit, m.positionInClusterPlane.get(2));
            obj[i * 4] = new double[]{-s / 2 + px, s / 2 + py, pz};
            obj[i * 4 + 1] = new double[]{s / 2 + px, s / 2 + py, pz};
            obj[i * 4 + 2] = new double[]{s / 2 + px, -s / 2 + py, pz};
            obj[i * 4 + 3] = new double[]{-s / 2 + px, -s / 2 + py, pz};
            for (int j = 0; j < 4; j++) img[i * 4 + j] = new double[]{d.corners[j].x, d.corners[j].y};
        }
        return toRaw(PnpSolver.solve(obj, img, fx, fy, cx, cy));
    }

    private static AprilTagPoseRaw toRaw(PnpSolver.Pose p) {
        if (p == null) return null;
        float[] r = new float[9];
        for (int i = 0; i < 9; i++) r[i] = (float) p.r[i];
        return new AprilTagPoseRaw(p.t[0], p.t[1], p.t[2], new GeneralMatrixF(3, 3, r));
    }

    private AprilTagPoseFtc rawPoseToFtcPose(AprilTagPoseRaw raw) {
        Orientation rot = Orientation.getOrientation(raw.R, AxesReference.INTRINSIC, AxesOrder.YXZ, outputUnitsAngle);
        return new AprilTagPoseFtc(
                raw.x, raw.z, -raw.y,
                -rot.firstAngle, rot.secondAngle, rot.thirdAngle,
                Math.hypot(raw.x, raw.z),
                outputUnitsAngle.fromUnit(AngleUnit.RADIANS, Math.atan2(-raw.x, raw.z)),
                outputUnitsAngle.fromUnit(AngleUnit.RADIANS, Math.atan2(-raw.y, raw.z)));
    }

    private Pose3D computeRobotPose(AprilTagPoseRaw raw, VectorF fieldPosition, Quaternion fieldOrientation, long acquisitionTime) {
        OpenGLMatrix tagRotation = new OpenGLMatrix(fieldOrientation.toMatrix());
        OpenGLMatrix tagInField = OpenGLMatrix.identityMatrix()
                .translated(fieldPosition.get(0), fieldPosition.get(1), fieldPosition.get(2)).multiplied(tagRotation);
        float x = (float) DistanceUnit.INCH.fromUnit(outputUnitsLength, raw.x);
        float y = (float) DistanceUnit.INCH.fromUnit(outputUnitsLength, raw.y);
        float z = (float) DistanceUnit.INCH.fromUnit(outputUnitsLength, raw.z);
        OpenGLMatrix tagRotationInCamera = new OpenGLMatrix(raw.R);
        OpenGLMatrix cameraInTag = OpenGLMatrix.identityMatrix().translated(x, y, z).multiplied(tagRotationInCamera).inverted();
        OpenGLMatrix robotInField = tagInField.multiplied(cameraInTag).multiplied(robotInCameraFrame);
        VectorF t = robotInField.getTranslation();
        Position position = new Position(DistanceUnit.INCH, t.get(0), t.get(1), t.get(2), acquisitionTime).toUnit(outputUnitsLength);
        Orientation rot = Orientation.getOrientation(robotInField, AxesReference.INTRINSIC, AxesOrder.ZXY, outputUnitsAngle);
        YawPitchRollAngles ypr = new YawPitchRollAngles(outputUnitsAngle, rot.firstAngle, rot.secondAngle, rot.thirdAngle, acquisitionTime);
        return new Pose3D(position, ypr);
    }

    private static double quadArea(double[][] p) {
        double a = 0;
        for (int i = 0; i < 4; i++) a += p[i][0] * p[(i + 1) % 4][1] - p[(i + 1) % 4][0] * p[i][1];
        return Math.abs(a) / 2;
    }

    /** The tag's center in the picture: where its diagonals cross. */
    private static Point diagonalsCross(double[][] c) {
        double x1 = c[0][0], y1 = c[0][1], x2 = c[2][0], y2 = c[2][1], x3 = c[1][0], y3 = c[1][1], x4 = c[3][0], y4 = c[3][1];
        double d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (Math.abs(d) < 1e-12) return new Point((x1 + x2 + x3 + x4) / 4, (y1 + y2 + y3 + y4) / 4);
        double a = x1 * y2 - y1 * x2, b = x3 * y4 - y3 * x4;
        return new Point((a * (x3 - x4) - (x1 - x2) * b) / d, (a * (y3 - y4) - (y1 - y2) * b) / d);
    }

    @Override
    public void setDecimation(float decimation) {
        this.decimation = decimation;
    }

    @Override
    public void setPoseSolver(PoseSolver poseSolver) {
        this.poseSolver = poseSolver;
    }

    @Override
    public int getPerTagAvgPoseSolveTime() {
        return solveMillis;
    }

    @Override
    public ArrayList<AprilTagDetection> getDetections() {
        return detections;
    }

    @Override
    public ArrayList<AprilTagDetection> getFreshDetections() {
        synchronized (detectionsUpdateSync) {
            ArrayList<AprilTagDetection> ret = detectionsUpdate;
            detectionsUpdate = null;
            return ret;
        }
    }
}
