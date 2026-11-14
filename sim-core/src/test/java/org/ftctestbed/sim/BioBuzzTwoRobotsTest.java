package org.ftctestbed.sim;

import com.qualcomm.robotcore.hardware.DcMotorEx;

import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.game.MatchState;
import org.ftctestbed.sim.season.biobuzz.BioBuzzField;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** BIOBUZZ with a partner robot on the field: pre-loads, LEAVE and PARK per robot, and the referee's notes. */
class BioBuzzTwoRobotsTest {
    private static final double IN = 0.0254;

    Simulation sim;

    @BeforeEach
    void setUp() {
        RobotConfig robot = RobotConfig.load(Path.of("../config/robot.jsonc"));
        sim = new Simulation(FieldConfig.load(Path.of("../config/field.jsonc")), List.of(robot, robot));
    }

    BioBuzzSnapshot snap() {
        return (BioBuzzSnapshot) sim.snapshot().season();
    }

    void run(double seconds) {
        for (int i = 0; i < Math.round(seconds / Simulation.DT); i++) sim.step();
    }

    long stagedIn(double[] zone) {
        return sim.pieces().all().stream().filter(p -> p.state() == GamePiece.State.FLOOR
                && BioBuzzField.inRect(zone, p.x(), p.y(), 0)).count();
    }

    void enter(MatchState.Phase phase) {
        sim.match().enter(phase, sim.clock.seconds());
        run(0.1);
    }

    @Test
    void aPartnerBringsItsOwnPreloads() {
        assertEquals("red", sim.robot(2).alliance());
        assertEquals(40, sim.pieces().all().stream().filter(p -> p.kind.id().equals("pollen")).count(), "40 POLLEN, as always");
        for (SimRobot r : sim.robots()) assertEquals(4, r.handling().hopper().size(), r + " starts with its 4 pre-loads");
        assertEquals(0, stagedIn(BioBuzzField.RED_LOADING_ZONE), "both red robots are here: nothing staged for red");
        assertEquals(8, stagedIn(BioBuzzField.BLUE_LOADING_ZONE), "both blue robots are absent");

        // An opponent instead: each alliance is missing one robot.
        sim.setStartPosition(sim.robot(2), sim.fieldConfig.startPositions().get(2));
        assertEquals(4, sim.robot(2).handling().hopper().size());
        assertEquals(4, stagedIn(BioBuzzField.RED_LOADING_ZONE));
        assertEquals(4, stagedIn(BioBuzzField.BLUE_LOADING_ZONE));
    }

    @Test
    void eachRobotEarnsItsOwnLeaveAndPark() {
        enter(MatchState.Phase.AUTO);
        // Robot 1 parks in the red LOADING ZONE; robot 2 ends AUTO in the middle of the field.
        sim.robot(1).moveRobot(-60.5 * IN, 40 * IN, 0);
        sim.robot(2).moveRobot(0, -30 * IN, 0);
        run(0.1);
        enter(MatchState.Phase.TRANSITION);
        BioBuzzSnapshot.Score red = snap().red();
        assertEquals(2, red.leaves());
        assertEquals(1, red.autoParks());
        assertEquals(2 * 3 + 5, red.leavePoints() + red.parkPoints());
        assertEquals(0, snap().blue().leaves(), "blue has no robots here");

        enter(MatchState.Phase.TELEOP);
        // Both park at the end: robot 2 just reaches into the zone below robot 1.
        sim.robot(2).moveRobot(-60.5 * IN, 19 * IN, 0);
        run(0.1);
        enter(MatchState.Phase.ENDED);
        red = snap().red();
        assertEquals(2, red.teleopParks());
        assertEquals(2 * 3 + 3 * 5, red.leavePoints() + red.parkPoints());
        assertTrue(snap().breakdown("red").contains(String.format("%-20s %2d x %2d = %3d", "LEAVE", 2, 3, 6)), snap().breakdown("red").toString());
        assertTrue(snap().rankingPoints("red").get(0).startsWith("✓ SWARM"), "two robots can earn SWARM: " + snap().rankingPoints("red"));
    }

    @Test
    void anOpponentScoresForTheOtherAlliance() {
        sim.setStartPosition(sim.robot(2), sim.fieldConfig.startPositions().get(2)); // Blue Wall
        enter(MatchState.Phase.AUTO);
        sim.robot(2).moveRobot(40 * IN, 0, Math.PI); // off its wall; robot 1 stays against the red wall
        run(0.1);
        enter(MatchState.Phase.TRANSITION);
        assertEquals(1, snap().blue().leaves());
        assertEquals(0, snap().red().leaves());
    }

    @Test
    void ruleWarningsSayWhichRobot() {
        enter(MatchState.Phase.AUTO);
        enter(MatchState.Phase.TRANSITION);
        DcMotorEx lf = sim.robot(2).hardwareMap.get(DcMotorEx.class, "leftFront");
        lf.setPower(0.3);
        run(0.8);
        lf.setPower(0);
        List<String> notes = snap().notes();
        assertTrue(notes.stream().anyMatch(n -> n.contains("G403 warning: powered movement by Robot 2 during")), notes.toString());
        assertTrue(notes.stream().noneMatch(n -> n.contains("Robot 1")), "robot 1 sat still: " + notes);
    }

    @Test
    void theHumanPlayerNeverDropsNectarOntoEitherRobot() {
        // Robot 2 sits on the red human player's three NECTAR spots; robot 1 is at its start.
        sim.robot(2).moveRobot(-55 * IN, 35 * IN, 0);
        run(0.1);
        sim.humanPlayerAction("red");
        run(0.5);
        assertEquals(5, snap().humanPlayerNectar().get("red"), "no NECTAR was put down under robot 2");

        sim.robot(2).moveRobot(-20 * IN, 35 * IN, 0);
        run(0.1);
        sim.humanPlayerAction("red");
        run(0.5);
        assertEquals(4, snap().humanPlayerNectar().get("red"), "once it drove off, the human player entered one");
    }
}
