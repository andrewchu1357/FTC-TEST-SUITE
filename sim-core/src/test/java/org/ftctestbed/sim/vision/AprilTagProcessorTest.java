package org.ftctestbed.sim.vision;

import org.firstinspires.ftc.robotcore.external.matrices.OpenGLMatrix;
import org.firstinspires.ftc.robotcore.external.matrices.VectorF;
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit;
import org.firstinspires.ftc.robotcore.external.navigation.AxesOrder;
import org.firstinspires.ftc.robotcore.external.navigation.AxesReference;
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.firstinspires.ftc.robotcore.external.navigation.Orientation;
import org.firstinspires.ftc.robotcore.external.navigation.Position;
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles;
import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibration;
import org.firstinspires.ftc.vision.apriltag.AprilTagClusterDetection;
import org.firstinspires.ftc.vision.apriltag.AprilTagDetection;
import org.firstinspires.ftc.vision.apriltag.AprilTagGameDatabase;
import org.firstinspires.ftc.vision.apriltag.AprilTagLibrary;
import org.firstinspires.ftc.vision.apriltag.AprilTagMetadata;
import org.firstinspires.ftc.vision.apriltag.AprilTagProcessor;
import org.firstinspires.ftc.vision.apriltag.AprilTagSingleDetection;
import org.ftctestbed.shim.vision.SimFrame;
import org.ftctestbed.shim.vision.SimProcessor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The SDK's AprilTagProcessor on frames whose tag corners are worked out exactly (no noise). */
class AprilTagProcessorTest {
    /** The SDK's built-in Logitech C920 calibration at 640x480. */
    static final CameraCalibration C920 = new CameraCalibration(640, 480, 622.001f, 622.001f, 319.803f, 241.251f);

    /** Pixel corners, in the AprilTag library's order, of a tag (edge `size`) at R, t in the camera's frame. */
    static double[][] corners(double size, double[] R, double[] t, CameraCalibration c) {
        double h = size / 2;
        double[][] obj = {{-h, h, 0}, {h, h, 0}, {h, -h, 0}, {-h, -h, 0}};
        double[][] out = new double[4][];
        for (int i = 0; i < 4; i++) {
            double[] p = obj[i];
            double x = R[0] * p[0] + R[1] * p[1] + R[2] * p[2] + t[0];
            double y = R[3] * p[0] + R[4] * p[1] + R[5] * p[2] + t[1];
            double z = R[6] * p[0] + R[7] * p[1] + R[8] * p[2] + t[2];
            out[i] = new double[]{c.focalLengthX * x / z + c.principalPointX, c.focalLengthY * y / z + c.principalPointY};
        }
        return out;
    }

    static final double[] SQUARE = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    static double[] rotY(double deg) {
        double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, 0, s, 0, 1, 0, -s, 0, c};
    }

    static SimFrame frame(SimFrame.Tag... tags) {
        return new SimFrame(640, 480, 0, 622.001, 622.001, 319.803, 241.251, 1, 0, List.of(tags), List.of(), null);
    }

    static ArrayList<AprilTagDetection> run(AprilTagProcessor p, CameraCalibration calibration, SimFrame f) {
        p.init(640, 480, calibration);
        ((SimProcessor) p).simProcessFrame(f);
        return p.getDetections();
    }

    @Test
    void aTagSquarelyAheadIsStraightAheadAtItsDistance() {
        // Tag 583 is "Robbie" in the sample library: 2 in.
        SimFrame f = frame(new SimFrame.Tag(583, "36h11", corners(2, SQUARE, new double[]{0, 0, 36}, C920), 0));
        List<AprilTagDetection> d = run(AprilTagProcessor.easyCreateWithDefaults(), C920, f);
        assertEquals(1, d.size());
        AprilTagSingleDetection t = (AprilTagSingleDetection) d.get(0);
        assertEquals(583, t.id);
        assertEquals("Robbie", t.metadata.name);
        assertEquals(0, t.ftcPose.x, 1e-3);
        assertEquals(36, t.ftcPose.y, 1e-3, "y is out of the lens");
        assertEquals(0, t.ftcPose.z, 1e-3);
        assertEquals(36, t.ftcPose.range, 1e-3);
        assertEquals(0, t.ftcPose.bearing, 1e-3);
        assertEquals(0, t.ftcPose.yaw, 1e-2);
        assertEquals(0, t.ftcPose.pitch, 1e-2);
        assertEquals(0, t.ftcPose.roll, 1e-2);
        assertEquals(319.803, t.center.x, 1e-3, "the center pixel");
        assertEquals(DistanceUnit.INCH, t.distanceUnit);
    }

    @Test
    void offToTheSideAndTurnedTheFtcPoseFollowsTheSdksFormulas() {
        // 10 in right, 5 in below, 40 in ahead; turned 30 degrees about the camera's vertical.
        SimFrame f = frame(new SimFrame.Tag(585, "36h11", corners(6, rotY(30), new double[]{10, 5, 40}, C920), 0));
        AprilTagSingleDetection t = (AprilTagSingleDetection) run(AprilTagProcessor.easyCreateWithDefaults(), C920, f).get(0);
        assertEquals(10, t.ftcPose.x, 1e-3);
        assertEquals(40, t.ftcPose.y, 1e-3);
        assertEquals(-5, t.ftcPose.z, 1e-3, "z is up");
        assertEquals(Math.hypot(10, 40), t.ftcPose.range, 1e-3);
        assertEquals(Math.toDegrees(Math.atan2(-10, 40)), t.ftcPose.bearing, 1e-3, "positive bearing is to the left");
        assertEquals(Math.toDegrees(Math.atan2(-5, 40)), t.ftcPose.elevation, 1e-3);
        assertEquals(-30, t.ftcPose.yaw, 1e-2, "the SDK's yaw is minus YXZ's first angle");
        assertEquals(0, t.ftcPose.pitch, 1e-2);
        assertEquals(0, t.ftcPose.roll, 1e-2);
    }

    @Test
    void decimationSetsHowFarTagsAreSeen() {
        // A 2 in tag 9 ft away is 11.5 px across: the sample's comments say decimation 1 sees 2 in tags at
        // 10 ft, 2 at 6 ft, 3 (the default) at 4 ft.
        SimFrame f = frame(new SimFrame.Tag(583, "36h11", corners(2, SQUARE, new double[]{0, 0, 108}, C920), 0));
        AprilTagProcessor p = AprilTagProcessor.easyCreateWithDefaults();
        assertTrue(run(p, C920, f).isEmpty(), "decimation 3: too small");
        p.setDecimation(2);
        assertTrue(run(p, C920, f).isEmpty(), "decimation 2: too small");
        p.setDecimation(1);
        assertEquals(1, run(p, C920, f).size(), "decimation 1: found");
        // And one of another family isn't.
        SimFrame other = frame(new SimFrame.Tag(583, "25h9", corners(2, SQUARE, new double[]{0, 0, 30}, C920), 0));
        assertTrue(run(p, C920, other).isEmpty(), "a 25h9 tag to a 36h11 detector is just a square");
    }

    @Test
    void withoutACalibrationTheSdkGuessesAndTheRangeIsWrong() {
        SimFrame f = frame(new SimFrame.Tag(583, "36h11", corners(2, SQUARE, new double[]{0, 0, 36}, C920), 0));
        AprilTagSingleDetection t = (AprilTagSingleDetection) run(new AprilTagProcessor.Builder().setSuppressCalibrationWarnings(true).build(), null, f).get(0);
        assertEquals(36 * 578.272 / 622.001, t.ftcPose.range, 0.05, "the 578.272 px fallback");
        // setLensIntrinsics wins over everything.
        AprilTagProcessor own = new AprilTagProcessor.Builder().setLensIntrinsics(622.001, 622.001, 319.803, 241.251).build();
        assertEquals(36, ((AprilTagSingleDetection) run(own, null, f).get(0)).ftcPose.range, 1e-3);
    }

    @Test
    void aTagTheLibraryDoesntKnowHasNoPose() {
        SimFrame f = frame(new SimFrame.Tag(7, "36h11", corners(2, SQUARE, new double[]{0, 0, 30}, C920), 0));
        AprilTagSingleDetection t = (AprilTagSingleDetection) run(AprilTagProcessor.easyCreateWithDefaults(), C920, f).get(0);
        assertEquals(7, t.id);
        assertNull(t.metadata);
        assertNull(t.ftcPose);
        assertNull(t.rawPose);
        assertNull(t.robotPose);
    }

    @Test
    void aClusterIsOneDetectionSolvedFromTheTagsSeen() {
        // The sample library's "Center Goal": tags 581 and 582 (3 in), 2 in either side of its center.
        SimFrame both = frame(new SimFrame.Tag(581, "36h11", corners(3, SQUARE, new double[]{-2, 0, 48}, C920), 0),
                new SimFrame.Tag(582, "36h11", corners(3, SQUARE, new double[]{2, 0, 48}, C920), 0));
        AprilTagProcessor p = AprilTagProcessor.easyCreateWithDefaults();
        List<AprilTagDetection> d = run(p, C920, both);
        assertEquals(1, d.size(), "one cluster detection, not two tags");
        AprilTagClusterDetection c = (AprilTagClusterDetection) d.get(0);
        assertEquals("Center Goal", c.metadata.name);
        assertEquals(100, c.percentClusterFound);
        assertEquals(48, c.ftcPose.y, 1e-3);
        assertEquals(0, c.ftcPose.x, 1e-3);
        SimFrame one = frame(new SimFrame.Tag(582, "36h11", corners(3, SQUARE, new double[]{2, 0, 48}, C920), 0));
        c = (AprilTagClusterDetection) run(p, C920, one).get(0);
        assertEquals(50, c.percentClusterFound);
        assertEquals(48, c.ftcPose.y, 1e-3, "the cluster's center, from one of its tags");
    }

    @Test
    void robotPoseOnTheDecodeFieldComesBackFromItsBlueTarget() {
        // A robot at (-30, -20) in facing the target (its +y is forward: yaw 141.5 degrees), its camera 6 in
        // ahead and 10 in up, facing forward and tilted 10 degrees up (pitch -90 is level).
        AprilTagLibrary decode = AprilTagGameDatabase.getDecodeTagLibrary();
        AprilTagMetadata tag = decode.lookupTag(20);
        Position camPos = new Position(DistanceUnit.INCH, 0, 6, 10, 0);
        YawPitchRollAngles camYpr = new YawPitchRollAngles(AngleUnit.DEGREES, 0, -80, 0, 0);
        double rx = -30, ry = -20, yaw = 141.5;
        OpenGLMatrix robotInField = OpenGLMatrix.translation((float) rx, (float) ry, 0)
                .multiplied(new Orientation(AxesReference.INTRINSIC, AxesOrder.ZXY, AngleUnit.DEGREES, (float) yaw, 0, 0, 0).getRotationMatrix());
        OpenGLMatrix camInRobot = OpenGLMatrix.translation((float) camPos.x, (float) camPos.y, (float) camPos.z)
                .multiplied(new Orientation(AxesReference.INTRINSIC, AxesOrder.ZXZ, AngleUnit.DEGREES, 0, -80, 0, 0).getRotationMatrix());
        OpenGLMatrix tagInField = OpenGLMatrix.translation(tag.fieldPosition.get(0), tag.fieldPosition.get(1), tag.fieldPosition.get(2))
                .multiplied(new OpenGLMatrix(tag.fieldOrientation.toMatrix()));
        OpenGLMatrix tagInCam = robotInField.multiplied(camInRobot).inverted().multiplied(tagInField);
        double[] R = new double[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) R[i * 3 + j] = tagInCam.get(i, j);
        VectorF t = tagInCam.getTranslation();
        assertTrue(t.get(2) > 0, "the robot faces its target: " + t);
        SimFrame f = frame(new SimFrame.Tag(20, "36h11", corners(6.5, R, new double[]{t.get(0), t.get(1), t.get(2)}, C920), 0));
        AprilTagProcessor p = new AprilTagProcessor.Builder().setTagLibrary(decode).setCameraPose(camPos, camYpr).build();
        AprilTagSingleDetection d = (AprilTagSingleDetection) run(p, C920, f).get(0);
        Position pos = d.robotPose.getPosition();
        assertEquals(rx, pos.x, 0.05, "robot x");
        assertEquals(ry, pos.y, 0.05, "robot y");
        assertEquals(0, pos.z, 0.05, "robot z");
        assertEquals(AngleUnit.normalizeDegrees(yaw), d.robotPose.getOrientation().getYaw(AngleUnit.DEGREES), 0.05, "robot yaw");
    }

    @Test
    void blurAndDarknessStopTagsDecoding() {
        double[][] k = corners(2, SQUARE, new double[]{0, 0, 24}, C920); // 52 px: 6.5 px cells
        AprilTagProcessor p = AprilTagProcessor.easyCreateWithDefaults();
        assertEquals(1, run(p, C920, frame(new SimFrame.Tag(583, "36h11", k, 3))).size(), "3 px of blur: fine");
        assertTrue(run(p, C920, frame(new SimFrame.Tag(583, "36h11", k, 8))).isEmpty(), "8 px: smeared over a cell");
        SimFrame dark = new SimFrame(640, 480, 0, 622.001, 622.001, 319.803, 241.251, 0.1, 0,
                List.of(new SimFrame.Tag(583, "36h11", k, 0)), List.of(), null);
        assertTrue(run(p, C920, dark).isEmpty(), "too dark");
    }

    @Test
    void freshDetectionsAreHandedOutOnce() {
        AprilTagProcessor p = AprilTagProcessor.easyCreateWithDefaults();
        run(p, C920, frame(new SimFrame.Tag(583, "36h11", corners(2, SQUARE, new double[]{0, 0, 30}, C920), 0)));
        assertEquals(1, p.getFreshDetections().size());
        assertNull(p.getFreshDetections(), "nothing new since");
        assertEquals(1, p.getDetections().size(), "getDetections keeps the newest");
    }
}
