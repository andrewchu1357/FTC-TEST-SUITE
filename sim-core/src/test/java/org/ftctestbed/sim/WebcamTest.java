package org.ftctestbed.sim;

import android.util.Size;

import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.ExposureControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.GainControl;
import org.firstinspires.ftc.vision.VisionPortal;
import org.firstinspires.ftc.vision.apriltag.AprilTagClusterDetection;
import org.firstinspires.ftc.vision.apriltag.AprilTagDetection;
import org.firstinspires.ftc.vision.apriltag.AprilTagProcessor;
import org.firstinspires.ftc.vision.opencv.ColorBlobLocatorProcessor;
import org.firstinspires.ftc.vision.opencv.ColorRange;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.hardware.SimWebcam;
import org.ftctestbed.sim.season.biobuzz.BioBuzzField;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSeason;
import org.ftctestbed.sim.vision.Fiducial;
import org.ftctestbed.shim.vision.SimVision;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** A webcam and the SDK's VisionPortal on the BIOBUZZ field. */
class WebcamTest {
    private static final double IN = 0.0254;

    Simulation sim;
    BioBuzzSeason season;
    SimWebcam cam;
    WebcamName webcam;

    /** BuzzBot with a webcam mounted as given (JSON fields), on a BIOBUZZ field cleared of floor pieces. */
    void setUp(String mount, boolean secondRobot) throws Exception {
        SimVision.installWebcamLister(List::of); // a fresh Robot Controller: no LiveViews or processors in use
        String text = Files.readString(Path.of("../config/robot.jsonc"));
        RobotConfig withCam = RobotConfig.parse(text.replace("\"limitSwitches\": [",
                "\"webcams\": [ { \"name\": \"Webcam 1\"" + mount + " } ],\n  \"limitSwitches\": ["), "robot.jsonc");
        List<RobotConfig> robots = secondRobot ? List.of(withCam, RobotConfig.parse(text, "robot.jsonc")) : List.of(withCam);
        sim = new Simulation(FieldConfig.load(Path.of("../config/field.jsonc")), robots);
        season = (BioBuzzSeason) sim.season();
        for (GamePiece p : List.copyOf(sim.pieces().all())) if (p.state() == GamePiece.State.FLOOR) sim.pieces().takeOffField(p);
        sim.robot(1).handling().hopper().clear();
        cam = sim.robot(1).webcams().get(0);
        webcam = sim.robot(1).hardwareMap.get(WebcamName.class, "Webcam 1");
    }

    void run(double seconds) {
        for (int i = 0; i < Math.round(seconds / Simulation.DT); i++) sim.step();
    }

    GamePiece pollen(double xIn, double yIn) {
        GamePiece p = sim.pieces().create(season.pollen());
        sim.pieces().placeOnFloor(p, xIn * IN, yIn * IN, 0, 0);
        return p;
    }

    @Test
    void theWebcamIsInTheHardwareMapAndStreamsAboutTwoSecondsAfterBuild() throws Exception {
        setUp("", false);
        assertSame(cam, webcam);
        assertTrue(webcam.isWebcam());
        assertEquals("Logitech HD Pro Webcam C920", webcam.getDeviceName(), "the default model");
        AprilTagProcessor tags = AprilTagProcessor.easyCreateWithDefaults();
        VisionPortal portal = VisionPortal.easyCreateWithDefaults(webcam, tags);
        assertEquals(VisionPortal.CameraState.OPENING_CAMERA_DEVICE, portal.getCameraState());
        assertThrows(IllegalStateException.class, () -> portal.getCameraControl(ExposureControl.class), "not streaming yet");
        assertThrows(RuntimeException.class, portal::stopStreaming, "the SDK won't stop a stream that isn't started");
        run(1.0);
        assertNotEquals(VisionPortal.CameraState.STREAMING, portal.getCameraState(), "still opening at 1 s");
        run(1.0);
        assertEquals(VisionPortal.CameraState.STREAMING, portal.getCameraState(), "streaming by 2 s");
        run(1.0);
        assertEquals(30, portal.getFps(), 2, "a C920 at 640x480: 30 frames a second, AprilTags at decimation 3 keep up");
        assertNotNull(portal.getCameraControl(ExposureControl.class));
        portal.stopStreaming();
        run(0.3);
        assertEquals(VisionPortal.CameraState.CAMERA_DEVICE_READY, portal.getCameraState());
        portal.close();
        run(0.3);
        assertEquals(VisionPortal.CameraState.CAMERA_DEVICE_CLOSED, portal.getCameraState());
    }

    @Test
    void sizeAndFormatSetTheFrameRateAndAnUnsupportedSizeFails() throws Exception {
        setUp("", false);
        VisionPortal hd = new VisionPortal.Builder().setCamera(webcam).setCameraResolution(new Size(1280, 720))
                .addProcessor(AprilTagProcessor.easyCreateWithDefaults()).build();
        run(3);
        assertTrue(hd.getFps() < 12, "YUY2 at 1280x720 is about 10 fps: " + hd.getFps());
        hd.close();
        run(0.5);
        VisionPortal mjpeg = new VisionPortal.Builder().setCamera(webcam).setCameraResolution(new Size(1280, 720))
                .setStreamFormat(VisionPortal.StreamFormat.MJPEG).addProcessor(AprilTagProcessor.easyCreateWithDefaults()).build();
        run(3);
        // MJPEG lets the C920 send 30 frames a second at 1280x720; then the AprilTag pipeline (about 56 ms a
        // frame at this size on a Control Hub) sets the rate.
        assertEquals(17, mjpeg.getFps(), 3, "MJPEG");
        mjpeg.close();
        run(0.5);
        VisionPortal odd = new VisionPortal.Builder().setCamera(webcam).setCameraResolution(new Size(1000, 1000))
                .addProcessor(AprilTagProcessor.easyCreateWithDefaults()).build();
        run(3);
        assertEquals(VisionPortal.CameraState.ERROR, odd.getCameraState());
    }

    @Test
    void theSdksPortalMistakesFailTheSameWay() throws Exception {
        setUp("", false);
        AprilTagProcessor tags = AprilTagProcessor.easyCreateWithDefaults();
        VisionPortal first = VisionPortal.easyCreateWithDefaults(webcam, tags);
        RuntimeException twice = assertThrows(RuntimeException.class, () -> new VisionPortal.Builder().addProcessor(tags));
        assertTrue(twice.getMessage().startsWith("This VisionProcessor has already been attached"));
        IllegalStateException views = assertThrows(IllegalStateException.class,
                () -> VisionPortal.easyCreateWithDefaults(webcam, AprilTagProcessor.easyCreateWithDefaults()));
        assertTrue(views.getMessage().contains("MUST use setLiveViewContainerId"), views.getMessage());
        VisionPortal second = new VisionPortal.Builder().setCamera(webcam).enableLiveView(false)
                .addProcessor(AprilTagProcessor.easyCreateWithDefaults()).build();
        run(2.5);
        assertEquals(VisionPortal.CameraState.STREAMING, first.getCameraState());
        assertEquals(VisionPortal.CameraState.ERROR, second.getCameraState(), "the webcam is the first portal's");
        assertThrows(UnsupportedOperationException.class, first::getActiveCamera, "not a switchable camera");
    }

    /** Under the red HIVE's audience CELL, 50 in toward the audience, looking up at its tags (as in LimelightTest). */
    static final double UNDER_X = -BioBuzzField.HIVE_X / IN, UNDER_Y = -50;

    @Test
    void lookingUpUnderACellTheProcessorReadsItsClusterAtTheRightRange() throws Exception {
        setUp(", \"tiltUpDeg\": 45, \"xIn\": 0", false);
        sim.robot(1).moveRobot(UNDER_X * IN, UNDER_Y * IN, Math.PI / 2);
        AprilTagProcessor tags = AprilTagProcessor.easyCreateWithDefaults();
        VisionPortal portal = VisionPortal.easyCreateWithDefaults(webcam, tags);
        run(2.5);
        List<AprilTagDetection> d = tags.getDetections();
        assertEquals(1, d.size(), "one cluster detection");
        AprilTagClusterDetection c = (AprilTagClusterDetection) d.get(0);
        assertEquals("RED AUDIENCE", c.metadata.name);
        assertEquals(100, c.percentClusterFound);
        // Where the cluster's origin really is: tag 34's frame, less its offset in the cluster.
        Fiducial t34 = season.fiducials().stream().filter(f -> f.id() == 34).findFirst().orElseThrow();
        double[] origin = t34.frame().apply(new double[]{6.5 * IN, -7.1874 * IN, 5.622 * IN});
        double[] inCam = cam.lastCapture().camera().toLocal(origin);
        double range = Math.hypot(inCam[0], inCam[2]) / IN;
        assertEquals(range, c.ftcPose.range, range * 0.03, "range (in), from the C920's built-in calibration");
        assertEquals(0, c.ftcPose.bearing, 3, "straight ahead");
        portal.close();
    }

    @Test
    void aPollenAheadIsAYellowBlobAndAnotherRobotsBumpersAreRed() throws Exception {
        setUp("", true);
        sim.robot(1).moveRobot(-40 * IN, -40 * IN, 0);
        sim.robot(2).moveRobot(40 * IN, 60 * IN, 0);
        pollen(-40 + 36, -40);
        ColorBlobLocatorProcessor yellow = new ColorBlobLocatorProcessor.Builder().setTargetColorRange(ColorRange.YELLOW)
                .setContourMode(ColorBlobLocatorProcessor.ContourMode.EXTERNAL_ONLY).setBlurSize(5).build();
        ColorBlobLocatorProcessor red = new ColorBlobLocatorProcessor.Builder().setTargetColorRange(ColorRange.RED)
                .setContourMode(ColorBlobLocatorProcessor.ContourMode.EXTERNAL_ONLY).build();
        VisionPortal portal = new VisionPortal.Builder().setCamera(webcam).addProcessors(yellow, red).build();
        run(2.5);
        // The ball: 36 in ahead of the robot's center, centered left-right, below the lens.
        double ahead = (36 - cam.def.x() / IN) * IN, down = cam.def.z() - season.pollen().radius();
        double fx = 622.001;
        double u = 319.803, v = 241.251 + fx * down / ahead;
        ColorBlobLocatorProcessor.Blob ball = yellow.getBlobs().get(0);
        assertEquals(u, ball.getCircle().getX(), 3, "in the middle");
        assertEquals(v, ball.getCircle().getY(), 4, "below the middle");
        double r = fx * season.pollen().radius() / Math.hypot(ahead, down);
        assertEquals(r, ball.getCircle().getRadius(), 2, "its size in the picture");
        assertTrue(red.getBlobs().isEmpty(), "nothing red in view");
        assertEquals("red", sim.robot(2).alliance(), "the field's second start position");
        sim.robot(2).moveRobot(10 * IN, -50 * IN, 0); // ahead and a little to the right
        run(0.3);
        assertFalse(red.getBlobs().isEmpty(), "robot 2's red bumpers");
        assertTrue(red.getBlobs().get(0).getContourArea() > 500, "a big blob: " + red.getBlobs().get(0).getContourArea());
        portal.close();
    }

    @Test
    void turningFastSmearsTagsUnlessTheExposureIsShort() throws Exception {
        setUp(", \"tiltUpDeg\": 45, \"xIn\": 0", false);
        sim.robot(1).moveRobot(UNDER_X * IN, UNDER_Y * IN, Math.PI / 2);
        run(0.01);
        AprilTagProcessor tags = AprilTagProcessor.easyCreateWithDefaults();
        tags.init(640, 480, cam.simCalibration(640, 480));
        // The camera's view while the robot turns at 3 rad/s, with auto exposure (15 ms).
        long now = sim.clock.nanos();
        cam.step(now, UNDER_X * IN, UNDER_Y * IN, Math.PI / 2, 0, 0, 0, sim.robot(1).visionScene());
        ((org.ftctestbed.shim.vision.SimProcessor) tags).simProcessFrame(cam.simCapture(640, 480));
        assertEquals(1, tags.getDetections().size(), "standing still: seen");
        cam.step(now, UNDER_X * IN, UNDER_Y * IN, Math.PI / 2, 0, 0, 3.0, sim.robot(1).visionScene());
        org.ftctestbed.shim.vision.SimFrame blurred = cam.simCapture(640, 480);
        assertTrue(blurred.tags.get(0).blur > 10, "smeared " + blurred.tags.get(0).blur + " px");
        ((org.ftctestbed.shim.vision.SimProcessor) tags).simProcessFrame(blurred);
        assertTrue(tags.getDetections().isEmpty(), "blurred away");
        // The sample's fix: a short manual exposure, with gain to make up the light.
        ExposureControl exposure = cam.simControls().get(ExposureControl.class);
        assertTrue(exposure.setMode(ExposureControl.Mode.Manual));
        assertTrue(exposure.setExposure(2, TimeUnit.MILLISECONDS));
        assertTrue(cam.simControls().get(GainControl.class).setGain(255));
        org.ftctestbed.shim.vision.SimFrame sharp = cam.simCapture(640, 480);
        assertTrue(sharp.brightness > 0.25, "bright enough: " + sharp.brightness);
        ((org.ftctestbed.shim.vision.SimProcessor) tags).simProcessFrame(sharp);
        assertEquals(1, tags.getDetections().size(), "2 ms, high gain: sharp enough (blur " + sharp.tags.get(0).blur + " px)");
        // Short with no gain: too dark.
        cam.simControls().get(GainControl.class).setGain(0);
        exposure.setExposure(1, TimeUnit.MILLISECONDS);
        ((org.ftctestbed.shim.vision.SimProcessor) tags).simProcessFrame(cam.simCapture(640, 480));
        assertTrue(tags.getDetections().isEmpty(), "1 ms at gain 0: too dark to decode");
    }
}
