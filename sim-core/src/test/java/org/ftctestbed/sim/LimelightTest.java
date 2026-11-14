package org.ftctestbed.sim;

import com.qualcomm.hardware.limelightvision.LLResult;
import com.qualcomm.hardware.limelightvision.LLResultTypes;

import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.hardware.SimLimelight;
import org.ftctestbed.sim.season.biobuzz.BioBuzzField;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSeason;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Limelight 3A: color pipelines on game pieces, AprilTags on BIOBUZZ's CELLs, timing. */
class LimelightTest {
    private static final double IN = 0.0254;

    Simulation sim;
    BioBuzzSeason season;
    SimLimelight ll;

    /** BuzzBot with a Limelight mounted as given (JSON fields), on an empty BIOBUZZ field. */
    void setUp(String mount, boolean emptyField) throws Exception {
        String text = Files.readString(Path.of("../config/robot.jsonc"))
                .replaceAll("(?m)^\\s*\"limelights\": \\[.*\\],\\n", "")
                .replace("\"limitSwitches\": [", "\"limelights\": [ { \"name\": \"limelight\"" + mount + " } ],\n  \"limitSwitches\": [");
        sim = new Simulation(FieldConfig.load(Path.of("../config/field.jsonc")), List.of(RobotConfig.parse(text, "robot.jsonc")));
        season = (BioBuzzSeason) sim.season();
        ll = sim.robot(1).limelights().get(0);
        if (emptyField) {
            for (GamePiece p : List.copyOf(sim.pieces().all())) {
                if (p.state() == GamePiece.State.FLOOR) sim.pieces().takeOffField(p);
            }
            sim.robot(1).handling().hopper().clear();
        }
        ll.start();
    }

    void run(double seconds) {
        for (int i = 0; i < Math.round(seconds / Simulation.DT); i++) sim.step();
    }

    /** The color target nearest this tx (the field's other pieces are blobs too). */
    static LLResultTypes.ColorResult nearest(LLResult r, double tx) {
        LLResultTypes.ColorResult best = null;
        for (LLResultTypes.ColorResult c : r.getColorResults()) {
            if (best == null || Math.abs(c.getTargetXDegrees() - tx) < Math.abs(best.getTargetXDegrees() - tx)) best = c;
        }
        return best;
    }

    GamePiece pollen(double xIn, double yIn) {
        GamePiece p = sim.pieces().create(season.pollen());
        sim.pieces().placeOnFloor(p, xIn * IN, yIn * IN, 0, 0);
        return p;
    }

    @Test
    void theDefaultMountAndPipelinesAreThePlaceholders() throws Exception {
        setUp("", true);
        RobotConfig.Limelight d = ll.def;
        assertEquals(10 * IN, d.z(), 1e-9, "10 in up");
        assertEquals(0, d.tiltUp(), 1e-9, "looking straight ahead");
        assertEquals(4, d.pipelines().size());
        assertEquals("fiducial", d.pipelines().get(3).type(), "slot 3: AprilTags, as in TRC's BIOBUZZ guide");
        assertNull(ll.getLatestResult(), "nothing until the first poll");
    }

    @Test
    void aPollenStraightAheadIsAtTheCrosshairAndOneToTheRightHasPositiveTx() throws Exception {
        setUp("", true);
        sim.robot(1).moveRobot(-40 * IN, -40 * IN, 0);
        pollen(-40 + 40, -40);
        run(0.2);
        LLResult r = ll.getLatestResult();
        assertNotNull(r);
        assertTrue(r.isValid(), r.toString());
        assertEquals(0, r.getPipelineIndex());
        assertEquals(0, r.getTx(), 0.5, "straight ahead");
        assertTrue(r.getTy() < 0, "below the lens (a ball on the floor, the camera 10 in up): " + r.getTy());
        double near = r.getTa();

        sim.robot(1).moveRobot(-40 * IN, -40 * IN, Math.toRadians(10)); // turn left: the ball is now to the right
        run(0.2);
        r = ll.getLatestResult();
        // The lens is 8 in ahead of the robot's center, so it swings sideways as the robot turns.
        double lens = ll.def.x() / IN, turn = Math.toRadians(10);
        double expected = Math.toDegrees(Math.atan2(lens * Math.sin(turn), 40 - lens * Math.cos(turn)) + turn);
        assertEquals(expected, r.getTx(), 0.3, "right of the crosshair");

        sim.robot(1).moveRobot(-60 * IN, -40 * IN, 0); // back off 20 in
        run(0.2);
        LLResultTypes.ColorResult ours = nearest(ll.getLatestResult(), 0);
        assertEquals(0, ours.getTargetXDegrees(), 0.5);
        assertTrue(ours.getTargetArea() < near * 0.6, "farther: smaller");
        // From back here the 4 POLLEN in the FLOWER beyond it make a bigger blob: that's the target now.
        assertNotEquals(0, ll.getLatestResult().getTx(), 2);
    }

    @Test
    void anotherRobotHidesWhatsBehindItAndBallsTouchingAreOneBlob() throws Exception {
        String text = Files.readString(Path.of("../config/robot.jsonc"))
                .replaceAll("(?m)^\\s*\"limelights\": \\[.*\\],\\n", "");
        RobotConfig withCam = RobotConfig.parse(text.replace("\"limitSwitches\": [",
                "\"limelights\": [ { \"name\": \"limelight\" } ],\n  \"limitSwitches\": ["), "robot.jsonc");
        sim = new Simulation(FieldConfig.load(Path.of("../config/field.jsonc")), List.of(withCam, RobotConfig.parse(text, "robot.jsonc")));
        season = (BioBuzzSeason) sim.season();
        for (GamePiece p : List.copyOf(sim.pieces().all())) if (p.state() == GamePiece.State.FLOOR) sim.pieces().takeOffField(p);
        ll = sim.robot(1).limelights().get(0);
        ll.start();
        sim.robot(1).moveRobot(-50 * IN, -10 * IN, 0);
        sim.robot(2).moveRobot(30 * IN, -60 * IN, 0); // out of the way
        pollen(0, -10);
        run(0.2);
        assertTrue(ll.getLatestResult().isValid(), "in view");
        sim.robot(2).moveRobot(-25 * IN, -10 * IN, 0); // in between
        run(0.2);
        assertTrue(ll.getLatestResult().getColorResults().stream().noneMatch(c -> Math.abs(c.getTargetXDegrees()) < 5),
                "hidden behind robot 2");

        sim.robot(2).moveRobot(30 * IN, -60 * IN, 0);
        double one = 0;
        run(0.2);
        one = ll.getLatestResult().getTa();
        pollen(0, -10 + 2.82); // touching, side by side (POLLEN is 2.8 in)
        run(0.2);
        LLResult r = ll.getLatestResult();
        double tx = r.getTx();
        long near = r.getColorResults().stream().filter(c -> Math.abs(c.getTargetXDegrees() - tx) < 6).count();
        assertEquals(1, near, "one contour (pieces in the FLOWERS across the field are blobs of their own)");
        assertTrue(r.getTa() > 1.7 * one, "twice the area: " + r.getTa() + " vs " + one);
    }

    @Test
    void aPipelineSwitchTakesAFrameOrTwoAndEachResultSaysWhichPipelineMadeIt() throws Exception {
        setUp("", true);
        sim.robot(1).moveRobot(-40 * IN, -40 * IN, 0);
        run(0.1);
        assertEquals(0, ll.getLatestResult().getPipelineIndex());
        ll.pipelineSwitch(1);
        run(0.012); // about one frame at 90 fps
        assertEquals(0, ll.getLatestResult().getPipelineIndex(), "still the old pipeline's frames");
        run(0.06);
        assertEquals(1, ll.getLatestResult().getPipelineIndex(), "now the red pipeline");
        assertTrue(ll.getLatestResult().getCaptureLatency() + ll.getLatestResult().getTargetingLatency() > 10, "latency in ms");
    }

    /** Under the red HIVE's audience CELL, 50 in toward the audience, looking up at its tags. */
    static final double UNDER_X = -BioBuzzField.HIVE_X / IN, UNDER_Y = -50;

    @Test
    void aCameraTiltedUpReadsTheTagsUnderACellAndFindsTheRobot() throws Exception {
        setUp(", \"tiltUpDeg\": 45, \"xIn\": 0", true);
        sim.robot(1).moveRobot(UNDER_X * IN, UNDER_Y * IN, Math.PI / 2);
        ll.pipelineSwitch(3);
        run(0.3);
        LLResult r = ll.getLatestResult();
        assertEquals(3, r.getPipelineIndex());
        List<Integer> ids = r.getFiducialResults().stream().map(LLResultTypes.FiducialResult::getFiducialId).sorted().toList();
        assertEquals(List.of(34, 35, 36, 37), ids, "the RED AUDIENCE cluster");
        double meanTx = r.getFiducialResults().stream().mapToDouble(LLResultTypes.FiducialResult::getTargetXDegrees).average().orElse(99);
        assertEquals(0, meanTx, 1, "the cluster is straight ahead (the primary target is its biggest tag)");
        assertTrue(r.getTy() > 0, "above the crosshair: " + r.getTy());
        var pose = r.getBotpose();
        double x = pose.getPosition().toUnit(org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.INCH).x;
        double y = pose.getPosition().toUnit(org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.INCH).y;
        assertEquals(UNDER_X, x, 2, "botpose x (in)");
        assertEquals(UNDER_Y, y, 2, "botpose y (in)");
        assertEquals(90, pose.getOrientation().getYaw(), 3, "botpose yaw");
        assertEquals(4, r.getBotposeTagCount());

        ll.updateRobotOrientation(90);
        run(0.1);
        var mt2 = ll.getLatestResult().getBotpose_MT2();
        assertEquals(UNDER_X, mt2.getPosition().toUnit(org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.INCH).x, 1.5);
        assertEquals(UNDER_Y, mt2.getPosition().toUnit(org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.INCH).y, 1.5);
    }

    @Test
    void aLevelCameraCantSeeTheTagsUnderACell() throws Exception {
        setUp("", true); // the placeholder: level, 10 in up
        sim.robot(1).moveRobot(UNDER_X * IN, UNDER_Y * IN, Math.PI / 2);
        ll.pipelineSwitch(3);
        run(0.3);
        assertTrue(ll.getLatestResult().getFiducialResults().isEmpty(), "they're 4 ft up and facing down");
    }

    @Test
    void afterTheHiveTipsItsTagsHaveMovedAndAPoseFromThemIsWrong() throws Exception {
        setUp(", \"tiltUpDeg\": 45, \"xIn\": 0", false);
        // Fill the red HIVE's upward (audience) CELL until it tips.
        for (int i = 0; i < 8 && ((BioBuzzSnapshot) sim.snapshot().season()).hives().get(0).upSide() < 0; i++) {
            GamePiece p = sim.pieces().create(season.pollen());
            sim.pieces().launch(p, -BioBuzzField.HIVE_X, -BioBuzzField.MOUTH_OFFSET_Y, BioBuzzField.MOUTH_HEIGHT + 0.25, 0, 0, -1, true, "red");
            run(1.5);
        }
        run(3);
        assertEquals(1, ((BioBuzzSnapshot) sim.snapshot().season()).hives().get(0).upSide(), "tipped: the far CELL is up now");
        // The audience CELL is down now, its tags lower and facing the pivot: look up at them from under the HIVE.
        sim.robot(1).moveRobot(UNDER_X * IN, 5 * IN, -Math.PI / 2);
        ll.pipelineSwitch(3);
        run(0.3);
        LLResult r = ll.getLatestResult();
        List<Integer> ids = r.getFiducialResults().stream().map(LLResultTypes.FiducialResult::getFiducialId).sorted().toList();
        assertTrue(ids.containsAll(List.of(34, 35, 36, 37)), "the same tags, moved: " + ids);
        var pos = r.getBotpose().getPosition().toUnit(org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.INCH);
        double error = Math.hypot(pos.x - UNDER_X, pos.y - 5);
        assertTrue(error > 8, "the field map has them where they started, so the pose is off by " + error + " in");
    }
}
