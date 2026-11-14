package org.firstinspires.ftc.teamcode.buzzbot;

import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.season.biobuzz.BioBuzzField;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSeason;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BuzzBot's use of the HIVE AprilTags, as FIRST's kickoff suggests: lining up to the HIVE and
 * telling which way it's tipped.
 */
class HiveTagsTest {
    static final double IN = 0.0254;
    SimHarness sim;

    @BeforeEach
    void setUp() {
        PoseStorage.pose = null;
        PoseStorage.alliance = Alliance.RED;
        sim = SimHarness.load();
    }

    @AfterEach
    void tearDown() {
        sim.close();
    }

    String telemetry() {
        return String.join("\n", sim.telemetry());
    }

    /** Degrees the robot's front is off the line to a point (inches). */
    double offBy(double[] point) {
        double want = Math.toDegrees(Math.atan2(point[1] - sim.yInches(), point[0] - sim.xInches()));
        return ((sim.headingDegrees() - want) % 360 + 540) % 360 - 180;
    }

    /** True horizontal distance from the launcher (1 in ahead of center) to a point (in). */
    double launcherDistance(double[] point) {
        double h = Math.toRadians(sim.headingDegrees());
        return Math.hypot(point[0] - sim.xInches() - Math.cos(h), point[1] - sim.yInches() - Math.sin(h));
    }

    @Test
    void tagsMapToTheirCells() {
        assertEquals(HiveTags.Cell.RED_FAR, HiveTags.Cell.ofTag(30));
        assertEquals(HiveTags.Cell.RED_AUDIENCE, HiveTags.Cell.ofTag(37));
        assertEquals(HiveTags.Cell.BLUE_AUDIENCE, HiveTags.Cell.ofTag(38));
        assertEquals(HiveTags.Cell.BLUE_FAR, HiveTags.Cell.ofTag(45));
        assertNull(HiveTags.Cell.ofTag(20), "DECODE's tags aren't on a HIVE");
        assertEquals(HiveTags.Cell.RED_AUDIENCE, HiveTags.Cell.start(Alliance.RED), "red's start CELL faces the audience");
        assertEquals(HiveTags.Cell.BLUE_FAR, HiveTags.Cell.start(Alliance.BLUE));
        assertEquals(Alliance.BLUE, HiveTags.Cell.BLUE_FAR.hive());
    }

    @Test
    void aimingByTheTagsWorksWhenTheOdometryIsWrong() {
        // The robot is at the audience-side shooting spot, turned 25 degrees away, but its odometry
        // thinks it's 12 in to the side and turned the other way: aiming by the pose would miss.
        Pose spot = FieldConstants.START_CELL_SHOT;
        PoseStorage.pose = new Pose(spot.x + 12, spot.y - 6, spot.heading - Math.toRadians(15));
        sim.sim.moveRobot(spot.x * IN, spot.y * IN, spot.heading + Math.toRadians(25));
        sim.init("BuzzBot TeleOp").start().runFor(0.5);
        assertTrue(telemetry().contains("RED HIVE: audience CELL up"), telemetry());
        sim.gamepad1(g -> g.right_bumper = true).runFor(2.0);
        assertNull(sim.error(), sim.error());
        double[] opening = FieldConstants.START_CELL_OPENING;
        assertEquals(0, offBy(opening), 3, "facing the CELL's opening");
        assertTrue(telemetry().contains("aiming by"), telemetry());
        // The distance the launcher speed comes from: the tags', not the (wrong) pose's.
        String target = sim.telemetry().stream().filter(l -> l.startsWith("Target")).findFirst().orElse("");
        double fromTags = Double.parseDouble(target.replaceAll(".*CELL\\s+(\\d+) in.*", "$1"));
        System.out.printf("distance: tags %.1f in, true %.1f in%n", fromTags, launcherDistance(opening));
        assertEquals(launcherDistance(opening), fromTags, 2.0, "distance from the tags (in)");
    }

    @Test
    void afterTheHiveTipsTheTagsSwitchTheTargetToTheOtherCell() {
        Pose spot = FieldConstants.START_CELL_SHOT;
        PoseStorage.pose = spot;
        sim.sim.moveRobot(spot.x * IN, spot.y * IN, spot.heading);
        sim.init("BuzzBot TeleOp").start().runFor(0.5);
        assertTrue(sim.telemetry().stream().anyMatch(l -> l.startsWith("Target") && l.contains("STARTING")), telemetry());
        // Fill the red HIVE's up (audience) CELL until it tips.
        BioBuzzSeason season = (BioBuzzSeason) sim.sim.season();
        for (int i = 0; i < 8 && ((BioBuzzSnapshot) sim.season()).hives().get(0).upSide() < 0; i++) {
            sim.sim.edit(s -> {
                GamePiece p = s.pieces().create(season.pollen());
                s.pieces().launch(p, -BioBuzzField.HIVE_X, -BioBuzzField.MOUTH_OFFSET_Y, BioBuzzField.MOUTH_HEIGHT + 0.25, 0, 0, -1, true, "red");
            });
            sim.runFor(1.5);
        }
        sim.runFor(3);
        assertEquals(1, ((BioBuzzSnapshot) sim.season()).hives().get(0).upSide(), "tipped: the far CELL is up");
        assertNull(sim.error(), sim.error());
        // The camera saw the audience CELL's tags drop as the HIVE tipped. Down, they face the pivot,
        // so from here they're out of sight now, but the robot remembers.
        assertTrue(sim.telemetry().stream().anyMatch(l -> l.startsWith("Target") && l.contains("OTHER")),
                "the target followed the tip: " + telemetry());
        assertTrue(telemetry().contains("RED HIVE: far CELL up"), telemetry());
        // From the far side's shooting spot the up CELL's tags are in view, and aiming uses them.
        Pose far = FieldConstants.OTHER_CELL_SHOT;
        sim.sim.moveRobot(far.x * IN, far.y * IN, far.heading + Math.toRadians(-20));
        sim.gamepad1(g -> g.right_bumper = true).runFor(2.0);
        assertTrue(telemetry().contains("RED HIVE: far CELL up") && telemetry().contains("aiming by"), telemetry());
        assertEquals(0, offBy(FieldConstants.OTHER_CELL_OPENING), 3, "facing the far CELL's opening");
    }

    @Test
    void autoChecksTheTagsAndFiresAnotherBallWhenTheHiveHasntTipped(@TempDir Path dir) throws Exception {
        // A heavier HIVE: the 3 starting NECTAR and 3 POLLEN (198 g) don't tip it; a 4th POLLEN does.
        String field = Files.readString(Path.of("config/field.jsonc")).replace("\"tipThresholdG\": 190", "\"tipThresholdG\": 215");
        assertTrue(field.contains("\"tipThresholdG\": 215"));
        Files.writeString(dir.resolve("field.jsonc"), field);
        sim.close();
        sim = SimHarness.load(Path.of("config/robot.jsonc").toAbsolutePath(), dir.resolve("field.jsonc"));
        sim.startAt("Red Wall").runMatch("BuzzBot Auto Red", null);
        assertNull(sim.error(), sim.error());
        BioBuzzSnapshot.Score red = ((BioBuzzSnapshot) sim.season()).scoreOf("red");
        assertEquals(1, red.tipsAuto(), "the tags showed no tip after 3 balls, so it fired a 4th: " + telemetry());
        assertTrue(red.autoPark(), "and still parked in time");
    }
}
