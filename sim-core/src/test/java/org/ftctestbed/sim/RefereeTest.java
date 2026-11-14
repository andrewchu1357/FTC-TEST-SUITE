package org.ftctestbed.sim;

import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;

import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.game.MatchState;
import org.ftctestbed.sim.season.biobuzz.BioBuzzField;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSeason;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The BIOBUZZ referee, as of the TU05 manual (October 15, 2026). */
class RefereeTest {
    private static final double IN = 0.0254;

    Simulation sim;
    BioBuzzSeason season;

    /** BuzzBot with room for 6 pieces in its hopper (so a test can give it too many). */
    void setUp(int robots) throws Exception {
        String text = Files.readString(Path.of("../config/robot.jsonc")).replace("\"capacity\": 4", "\"capacity\": 6");
        RobotConfig robot = RobotConfig.parse(text, "robot.jsonc");
        List<RobotConfig> list = robots == 1 ? List.of(robot) : List.of(robot, robot);
        sim = new Simulation(FieldConfig.load(Path.of("../config/field.jsonc")), list);
        season = (BioBuzzSeason) sim.season();
        sim.match().enter(MatchState.Phase.TELEOP, sim.clock.seconds());
        run(0.1);
    }

    BioBuzzSnapshot snap() {
        return (BioBuzzSnapshot) sim.snapshot().season();
    }

    List<String> notes() {
        return snap().notes();
    }

    void run(double seconds) {
        for (int i = 0; i < Math.round(seconds / Simulation.DT); i++) sim.step();
    }

    /** Puts a piece into robot 1's hopper, as if it had picked it up. */
    GamePiece give(SimRobot robot, GamePiece p) {
        assertTrue(robot.handling().preload(p));
        return p;
    }

    /** Takes a piece out of the hopper and puts it on the floor beside the robot, as if spat out. */
    void spit(SimRobot robot, GamePiece p) {
        assertTrue(robot.handling().hopper().remove(p));
        sim.pieces().placeOnFloor(p, robot.x(), robot.y() + 20 * IN, 0, 0);
    }

    @Test
    void controllingFiveScoringElementsTwiceIsLikelyStrategic() throws Exception {
        setUp(1);
        SimRobot r = sim.robot(1);
        GamePiece extra = give(r, sim.pieces().create(season.pollen()));
        run(0.2);
        assertTrue(notes().stream().anyMatch(n -> n.contains("G407 VERBAL WARNING")), notes().toString());
        assertEquals(0, snap().blue().foulPoints(), "a first, momentary 5th is a warning");
        assertTrue(snap().red().cards().isEmpty());

        spit(r, extra);
        run(0.2);
        give(r, extra);
        run(0.2);
        // TU05: REPEATED instances of 5 are likely STRATEGIC, even with a keep-4 sorter.
        assertEquals(20, snap().blue().foulPoints(), "MAJOR FOUL: " + notes());
        assertEquals(1, snap().red().cards().size(), notes().toString());
        assertTrue(snap().red().cards().get(0).startsWith("YELLOW CARD"), snap().red().cards().toString());

        spit(r, extra);
        run(0.2);
        give(r, extra);
        run(0.2);
        assertEquals(20, snap().blue().foulPoints(), "the MAJOR FOUL and YELLOW CARD are per MATCH");
        assertEquals(1, snap().red().cards().size());
    }

    @Test
    void controllingOpponentNectarAgainIsAYellowCardEvenWithASorter() throws Exception {
        setUp(1);
        SimRobot r = sim.robot(1);
        spit(r, r.handling().hopper().get(3));
        GamePiece blue = give(r, sim.pieces().create(season.nectar("blue")));
        run(0.2);
        assertTrue(notes().stream().anyMatch(n -> n.contains("G408 VERBAL WARNING")), notes().toString());
        spit(r, blue); // BuzzBot's sorter spits it straight back out
        run(0.2);
        assertTrue(snap().red().cards().isEmpty());
        give(r, blue);
        run(0.2);
        assertEquals(1, snap().red().cards().size(), "REPEATED: " + notes());
        assertTrue(snap().red().cards().get(0).contains("G408"));
        assertEquals(0, snap().blue().foulPoints(), "G408 is a card, not a foul");
    }

    @Test
    void rammingTheHiveFrameIsLikelyStrategicButABumpIsAWarning() throws Exception {
        setUp(1);
        SimRobot r = sim.robot(1);
        // Driving at the +x foot of the HIVE frame, facing it.
        double footFace = BioBuzzField.FRAME_HALF_WIDTH;
        r.moveRobot(footFace + r.config.length() / 2 + 6 * IN, 0, Math.PI);
        drive(r, 0.3);
        run(1.0);
        drive(r, 0);
        run(0.3);
        assertTrue(notes().stream().anyMatch(n -> n.contains("G417 VERBAL WARNING")), "a bump: " + notes());
        assertEquals(0, snap().blue().foulPoints());

        r.moveRobot(footFace + r.config.length() / 2 + 30 * IN, 0, Math.PI);
        run(6.0); // long after the bump
        drive(r, 1.0);
        double fastest = 0;
        for (int i = 0; i < 150; i++) {
            run(0.01);
            fastest = Math.max(fastest, r.body.getLinearVelocity().getMagnitude());
        }
        assertTrue(fastest > 1.2, "full speed into the frame: " + fastest);
        drive(r, 0);
        run(0.3);
        assertEquals(20, snap().blue().foulPoints(), "rammed: " + notes());
        assertTrue(snap().red().cards().stream().anyMatch(c -> c.contains("G417")), snap().red().cards().toString());
    }

    @Test
    void catchingWhatATippingHiveSpillsIsAWarning() throws Exception {
        setUp(1);
        SimRobot r = sim.robot(1);
        r.moveRobot(0, -40 * IN, 0);
        run(0.1);
        GamePiece p = sim.pieces().create(season.pollen());
        sim.pieces().launch(p, r.x(), r.y(), 0.8, 0, 0, -1, false, null);
        p.setFromTip(true); // as Hive.release marks what it spills
        run(0.6);
        assertTrue(notes().stream().anyMatch(n -> n.contains("G409")), "it landed on the robot first: " + notes());

        GamePiece q = sim.pieces().create(season.pollen());
        sim.pieces().launch(q, 0, 30 * IN, 0.8, 0, 0, -1, false, null);
        q.setFromTip(true);
        run(0.8);
        assertFalse(q.fromTip(), "touching the tiles ends it");
        assertEquals(0, q.caughtBy());
    }

    @Test
    void pinningAnOpponentAgainstTheWallIsAMajorFoulEveryThreeSeconds() throws Exception {
        setUp(2);
        SimRobot one = sim.robot(1), two = sim.robot(2);
        sim.setStartPosition(two, sim.fieldConfig.startPositions().get(2)); // an opponent
        assertNotEquals(one.alliance(), two.alliance());
        sim.match().enter(MatchState.Phase.TELEOP, sim.clock.seconds());
        double half = sim.fieldConfig.size() / 2, len = one.config.length();
        // Robot 2 with its back to the far wall, robot 1 nose to nose with it, both driving at each other.
        two.moveRobot(0, half - len / 2 - 0.005, -Math.PI / 2);
        one.moveRobot(0, half - 1.5 * len - 0.01, Math.PI / 2);
        run(0.1);
        drive(one, 1.0);
        drive(two, 0.5); // robot 2 tries to drive out but is outpushed
        run(3.3);
        BioBuzzSnapshot.Score victim = two.alliance().equals("blue") ? snap().blue() : snap().red();
        assertEquals(20, victim.foulPoints(), "a MAJOR FOUL after the 3-count: " + notes());
        run(3.0);
        victim = two.alliance().equals("blue") ? snap().blue() : snap().red();
        assertEquals(40, victim.foulPoints(), "and another every 3 s: " + notes());
        BioBuzzSnapshot.Score pinner = one.alliance().equals("blue") ? snap().blue() : snap().red();
        assertEquals(0, pinner.foulPoints(), "robot 2 isn't pinning robot 1");
    }

    void drive(SimRobot r, double power) {
        String[] names = {"leftFront", "rightFront", "leftBack", "rightBack"};
        for (int i = 0; i < 4; i++) {
            DcMotorEx m = r.hardwareMap.get(DcMotorEx.class, names[i]);
            m.setDirection(i % 2 == 0 ? DcMotorSimple.Direction.REVERSE : DcMotorSimple.Direction.FORWARD);
            m.setPower(power);
        }
    }
}
