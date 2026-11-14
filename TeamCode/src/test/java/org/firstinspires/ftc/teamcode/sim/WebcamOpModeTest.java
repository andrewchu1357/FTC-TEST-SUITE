package org.firstinspires.ftc.teamcode.sim;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

import org.firstinspires.ftc.robotcore.external.hardware.camera.WebcamName;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.ExposureControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.GainControl;
import org.firstinspires.ftc.vision.VisionPortal;
import org.firstinspires.ftc.vision.apriltag.AprilTagClusterDetection;
import org.firstinspires.ftc.vision.apriltag.AprilTagDetection;
import org.firstinspires.ftc.vision.apriltag.AprilTagProcessor;
import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRegistry;
import org.ftctestbed.sim.season.biobuzz.BioBuzzField;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Robot code using a webcam the way FIRST's samples do, run as an OpMode. */
class WebcamOpModeTest {
    static final double IN = 0.0254;

    /** ConceptAprilTag's setup plus RobotAutoDriveToAprilTagOmni's setManualExposure(6, 250). */
    public static class ReadCluster extends LinearOpMode {
        static volatile VisionPortal portal;
        static volatile boolean exposureSet;
        static volatile String seen;
        static volatile double range;
        static volatile int percent;

        @Override
        public void runOpMode() throws InterruptedException {
            AprilTagProcessor aprilTag = new AprilTagProcessor.Builder().build();
            portal = new VisionPortal.Builder().setCamera(hardwareMap.get(WebcamName.class, "Webcam 1")).addProcessor(aprilTag).build();
            exposureSet = false;
            seen = null;
            // The sample's setManualExposure(): wait for the stream, then set the exposure and gain.
            while (!isStopRequested() && portal.getCameraState() != VisionPortal.CameraState.STREAMING) sleep(20);
            if (!isStopRequested()) {
                ExposureControl exposureControl = portal.getCameraControl(ExposureControl.class);
                if (exposureControl.getMode() != ExposureControl.Mode.Manual) {
                    exposureControl.setMode(ExposureControl.Mode.Manual);
                    sleep(50);
                }
                exposureControl.setExposure(6, TimeUnit.MILLISECONDS);
                sleep(20);
                portal.getCameraControl(GainControl.class).setGain(250);
                sleep(20);
                exposureSet = true;
            }
            waitForStart();
            while (opModeIsActive()) {
                for (AprilTagDetection d : aprilTag.getDetections()) {
                    if (d instanceof AprilTagClusterDetection) {
                        AprilTagClusterDetection c = (AprilTagClusterDetection) d;
                        seen = c.metadata.shortName;
                        range = c.ftcPose.range;
                        percent = c.percentClusterFound;
                        telemetry.addLine(String.format("==== Tag Cluster (%s) %d%%", c.metadata.name, c.percentClusterFound));
                        telemetry.addLine(String.format("RBE %6.1f %6.1f %6.1f  (inch, deg, deg)", c.ftcPose.range, c.ftcPose.bearing, c.ftcPose.elevation));
                    }
                }
                telemetry.addData("FPS", "%.0f", portal.getFps());
                telemetry.update();
                sleep(20);
            }
        }
    }

    @Test
    void theSamplesWebcamCodeReadsACellsTagsAndTheCameraClosesAtStop() throws Exception {
        String text = Files.readString(Path.of("config/robot.jsonc")).replace("\"limitSwitches\": [",
                "\"webcams\": [ { \"name\": \"Webcam 1\", \"model\": \"C920\", \"xIn\": 0, \"zIn\": 8, \"tiltUpDeg\": 45 } ],\n  \"limitSwitches\": [");
        RobotConfig robot = RobotConfig.parse(text, "robot.jsonc");
        OpModeRegistry.Result ops = new OpModeRegistry.Result(List.of(new OpModeInfo("ReadCluster", "", false, null, ReadCluster.class)), List.of());
        try (SimHarness h = SimHarness.of(robot, FieldConfig.load(Path.of("config/field.jsonc")), ops)) {
            // Under the red HIVE's audience CELL, looking up at its tags.
            h.sim.robot(1).moveRobot(-BioBuzzField.HIVE_X, -50 * IN, Math.PI / 2);
            h.init("ReadCluster").runFor(2.5);
            assertNull(h.error(), h.error());
            assertTrue(ReadCluster.exposureSet, "exposure set once the camera streamed");
            h.start().runFor(1);
            assertNull(h.error(), h.error());
            assertEquals("RED AUDIENCE", ReadCluster.seen, String.join("\n", h.telemetry()));
            assertEquals(100, ReadCluster.percent);
            assertTrue(ReadCluster.range > 50 && ReadCluster.range < 75, "range " + ReadCluster.range + " in: the tags are 4 ft up, 50 in away");
            assertTrue(String.join("\n", h.telemetry()).contains("Tag Cluster (RED AUDIENCE)"), String.join("\n", h.telemetry()));
            h.stop().runFor(0.5);
            assertEquals(VisionPortal.CameraState.CAMERA_DEVICE_CLOSED, ReadCluster.portal.getCameraState(), "closed when the OpMode stopped");
        }
    }
}
