package org.ftctestbed.sim;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.Servo;

import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.game.MatchState;
import org.ftctestbed.sim.season.biobuzz.BioBuzzField;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSeason;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** BIOBUZZ game engine, using the shipped BuzzBot and BIOBUZZ config files. */
class BioBuzzTest {
    private static final double IN = 0.0254;

    Simulation sim;
    BioBuzzSeason season;

    @BeforeEach
    void setUp() {
        sim = new Simulation(RobotConfig.load(Path.of("../config/robot.jsonc")), FieldConfig.load(Path.of("../config/field.jsonc")));
        season = (BioBuzzSeason) sim.season();
    }

    BioBuzzSnapshot snap() {
        return (BioBuzzSnapshot) sim.snapshot().season();
    }

    void run(double seconds) {
        for (int i = 0; i < Math.round(seconds / Simulation.DT); i++) sim.step();
    }

    long count(String kind, GamePiece.State state) {
        return sim.pieces().all().stream().filter(p -> p.kind.id().startsWith(kind) && p.state() == state).count();
    }

    @Test
    void fieldIsStagedLikeFigure10_2() {
        assertEquals(40, sim.pieces().all().stream().filter(p -> p.kind.id().equals("pollen")).count());
        assertEquals(16, count("pollen", GamePiece.State.CONTAINED), "4 in each FLOWER");
        assertEquals(8 + 12, count("pollen", GamePiece.State.FLOOR), "4 in each GARDEN + absent robots' pre-loads");
        long inLoadingZones = sim.pieces().all().stream().filter(p -> p.state() == GamePiece.State.FLOOR
                && (BioBuzzField.inRect(BioBuzzField.RED_LOADING_ZONE, p.x(), p.y(), 0)
                || BioBuzzField.inRect(BioBuzzField.BLUE_LOADING_ZONE, p.x(), p.y(), 0))).count();
        assertEquals(12, inLoadingZones, "pre-loads of the 3 robots that aren't simulated");
        assertEquals(4, count("pollen", GamePiece.State.HELD), "4 pre-loaded in the robot");
        assertEquals(6, count("nectar", GamePiece.State.CONTAINED), "3 in each upward CELL");
        assertEquals(10, count("nectar", GamePiece.State.OFF_FIELD), "5 with each human player");
        BioBuzzSnapshot s = snap();
        assertEquals(4, s.red().garden());
        assertEquals(4, s.blue().garden());
        assertEquals(3, s.hives().get(0).contents().size());
        assertEquals(-1, s.hives().get(0).upSide(), "red's audience-side CELL starts up");
        assertEquals(1, s.hives().get(1).upSide(), "blue's far CELL starts up");
        assertEquals("red", sim.alliance());
    }

    /** Drop a piece straight down into the red HIVE's upward (audience-side) CELL opening. */
    void dropIntoRedCell(String kind) {
        GamePiece p = sim.pieces().create(kind.equals("pollen") ? season.pollen() : season.nectar(kind));
        sim.pieces().launch(p, -BioBuzzField.HIVE_X, -BioBuzzField.MOUTH_OFFSET_Y, BioBuzzField.MOUTH_HEIGHT + 0.25, 0, 0, -1, true, "red");
        run(0.4);
    }

    @Test
    void enoughWeightInTheUpwardCellTipsTheHive() {
        dropIntoRedCell("pollen");
        dropIntoRedCell("pollen");
        assertEquals(5, snap().hives().get(0).contents().size());
        assertEquals(0, snap().red().tips());
        dropIntoRedCell("pollen"); // 3 NECTAR + 3 POLLEN: more torque than the 190 g of POLLEN that just tip it
        run(4.0); // only just over: it starts slowly
        BioBuzzSnapshot s = snap();
        assertEquals(1, s.red().tipsTeleop(), "practice tips count as TELEOP");
        assertEquals(1, s.hives().get(0).upSide(), "the far CELL is now up");
        assertTrue(s.hives().get(0).contents().isEmpty());
        run(2.0);
        long spilled = sim.pieces().all().stream()
                .filter(p -> p.state() == GamePiece.State.FLOOR && Math.abs(p.x() + BioBuzzField.HIVE_X) < 0.4 && p.y() < -0.3)
                .count();
        assertEquals(6, spilled, "contents spill out under the audience-side CELL");
        // The tip earns the red human player one NECTAR, which they put in the LOADING ZONE.
        long inZone = sim.pieces().all().stream().filter(p -> p.kind.id().equals("nectar-red")
                && p.state() == GamePiece.State.FLOOR && BioBuzzField.inRect(BioBuzzField.RED_LOADING_ZONE, p.x(), p.y(), 0)).count();
        assertEquals(1, inZone);
        assertEquals(20, snap().red().total() - snap().red().garden());
    }

    @Test
    void flowerTakesPiecesFromTheTopAndGivesPollenFromTheBottom() {
        // FLOWER 4 is on the red alliance wall at y = -24 in.
        GamePiece n = sim.pieces().create(season.nectar("red"));
        sim.pieces().launch(n, -BioBuzzField.HALF + 3 * IN, -24 * IN, 25 * IN, 0, 0, 0, false, "red");
        run(0.5);
        BioBuzzSnapshot.FlowerState f = snap().flowers().get(3);
        assertEquals(5, f.stack().size());
        assertEquals("red", f.owner());
        assertEquals(4, f.scored(), "the bottom POLLEN sits in the retrieval opening, below the scoring volume");
        assertEquals(4 * 2, snap().red().ownedFlowerPieces() * 2);
        assertEquals(1, snap().red().bottomNectarBonuses());

        // Empty the hopper (spit the 4 pre-loads out), then back up to the FLOWER and intake.
        DcMotor intake = sim.hardwareMap.get(DcMotor.class, "intake");
        intake.setPower(-1);
        run(1.5);
        assertTrue(sim.handling().hopper().isEmpty());
        intake.setPower(0);
        // Up against the FLOWER, its base inside the intake's mouth (the mouth reaches back to 6 in ahead of center).
        sim.moveRobot(-BioBuzzField.HALF + BioBuzzField.FLOWER_BASE_DEPTH + 6.2 * IN, -BioBuzzField.FLOWER_ALONG_WALL, Math.PI);
        run(0.2);
        intake.setPower(1);
        run(1.5);
        assertEquals(4, sim.handling().hopper().size(), "4 POLLEN pulled out of the bottom");
        assertEquals(1, snap().flowers().get(3).stack().size(), "the NECTAR can't come out of the bottom");
    }

    @Test
    void launcherShootsAtFlywheelSurfaceSpeed() {
        DcMotorEx left = sim.hardwareMap.get(DcMotorEx.class, "shooterLeft");
        DcMotorEx right = sim.hardwareMap.get(DcMotorEx.class, "shooterRight");
        right.setDirection(DcMotor.Direction.REVERSE);
        for (DcMotorEx m : new DcMotorEx[]{left, right}) {
            m.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
            m.setVelocity(1200);
        }
        Servo kicker = sim.hardwareMap.get(Servo.class, "kicker");
        kicker.setPosition(0.2);
        run(5.0);
        double rpmBefore = sim.handling().parts().flywheelRpm();
        assertEquals(1200 / 28.0 * 60, rpmBefore, 0.04 * rpmBefore, "velocity control holds the flywheel at 1200 ticks/s");
        kicker.setPosition(0.8);
        GamePiece shot = null;
        for (int i = 0; i < 200 && shot == null; i++) {
            sim.step();
            shot = sim.pieces().all().stream().filter(p -> p.state() == GamePiece.State.AIRBORNE).findFirst().orElse(null);
        }
        assertNotNull(shot, "kicker fired a ball");
        assertEquals(3, sim.handling().hopper().size());
        double expected = 0.5 * rpmBefore * 2 * Math.PI / 60 * 0.048;
        double horizontal = Math.hypot(shot.vx(), shot.vy());
        assertTrue(horizontal > 0.3 * expected && horizontal < 0.6 * expected, "horizontal " + horizontal + " vs exit " + expected);
        double rpmAfter = sim.handling().parts().flywheelRpm();
        assertTrue(rpmAfter < rpmBefore - 50, "the shot slows the flywheel: " + rpmBefore + " -> " + rpmAfter);
        assertFalse(sim.snapshot().flights().isEmpty());
    }

    @Test
    void clawCarriesNectarIntoAFlower() {
        // Robot facing -y; the claw is behind it (toward the far wall).
        sim.moveRobot(-24 * IN, 50 * IN, -Math.PI / 2);
        GamePiece n = sim.pieces().create(season.nectar("red"));
        sim.pieces().placeOnFloor(n, -24 * IN, (50 + 10.8) * IN, 0, 0);
        Servo claw = sim.hardwareMap.get(Servo.class, "claw");
        claw.setPosition(0.8);
        run(0.6);
        claw.setPosition(0.2);
        run(0.6);
        assertEquals(GamePiece.State.HELD, n.state());

        DcMotorEx lift = sim.hardwareMap.get(DcMotorEx.class, "lift");
        lift.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        lift.setTargetPosition(2150);
        lift.setMode(DcMotor.RunMode.RUN_TO_POSITION);
        lift.setPower(1);
        run(2.0);
        assertTrue(sim.handling().parts().claws().get(0).height() > 22 * IN);
        // Back up until the raised claw is over FLOWER 1's opening (its axis 2.54 in from the far wall).
        sim.moveRobot(-BioBuzzField.FLOWER_ALONG_WALL, BioBuzzField.HALF - BioBuzzField.FLOWER_AXIS_FROM_WALL - 12 * IN, -Math.PI / 2);
        run(0.2);
        claw.setPosition(0.8);
        run(1.0);
        assertEquals(GamePiece.State.CONTAINED, n.state());
        assertEquals("red", snap().flowers().get(0).owner());
    }

    @Test
    void autoLeaveAndParkAreScoredAtTheEndOfAuto() {
        sim.match().enter(MatchState.Phase.AUTO, sim.clock.seconds());
        run(0.1);
        // Drive into the red LOADING ZONE, off the wall.
        sim.moveRobot(-60.5 * IN, 35 * IN, 0);
        run(0.1);
        sim.match().enter(MatchState.Phase.TRANSITION, sim.clock.seconds());
        run(0.1);
        BioBuzzSnapshot.Score red = snap().red();
        assertTrue(red.leave());
        assertTrue(red.autoPark());
        assertEquals(3 + 5, red.leavePoints() + red.parkPoints());
    }

    @Test
    void nectarInAFlowerBeforeTheLastMinuteIsAMajorFoul() {
        sim.match().enter(MatchState.Phase.TELEOP, sim.clock.seconds());
        GamePiece n = sim.pieces().create(season.nectar("red"));
        sim.pieces().launch(n, -BioBuzzField.HALF + 3 * IN, -24 * IN, 25 * IN, 0, 0, 0, false, "red");
        run(0.5);
        assertEquals(20, snap().blue().foulPoints());
        assertTrue(snap().notes().stream().anyMatch(s -> s.contains("G410")));
    }

    @Test
    void ballsShotOverTheWallComeBack() {
        GamePiece p = sim.pieces().create(season.pollen());
        sim.pieces().launch(p, 60 * IN, 0, 30 * IN, 6, 0, 1, false, "red");
        run(0.5);
        assertEquals(GamePiece.State.OFF_FIELD, p.state());
        run(3.5);
        assertEquals(GamePiece.State.FLOOR, p.state(), "field staff put POLLEN back in");
        assertTrue(Math.abs(p.x()) < BioBuzzField.HALF);
    }

    @Test
    void aBallLandingOnACellRollsOffInsteadOfHovering() {
        // Drop POLLEN onto the red HIVE: the sloped roof of its upward CELL (opening toward -y at
        // the start), and the top of its downward CELL.
        double in = 0.0254;
        for (double y : new double[]{-9, 14}) {
            GamePiece p = sim.pieces().all().stream()
                    .filter(g -> g.kind.id().equals("pollen") && g.state() == GamePiece.State.FLOOR).findFirst().orElseThrow();
            sim.edit(s -> s.pieces().launch(p, -12.75 * in, y * in, 75 * in, 0, 0, 0, false, null));
            double lowest = 75 * in;
            for (int i = 0; i < 2500 && p.state() == GamePiece.State.AIRBORNE; i++) {
                sim.step();
                lowest = Math.min(lowest, p.z());
            }
            assertTrue(lowest < 70 * in, "it reached the HIVE");
            assertNotEquals(GamePiece.State.AIRBORNE, p.state(), "dropped at y=" + y + " in: still in the air after 5 s at z=" + p.z() / in + " in");
        }
    }

    @Test
    void piecesPutDownOnTheSameSpotEndUpSideBySide() {
        List<GamePiece> two = sim.pieces().all().stream()
                .filter(g -> g.kind.id().equals("pollen") && g.state() == GamePiece.State.FLOOR).limit(2).toList();
        sim.edit(s -> {
            s.pieces().placeOnFloor(two.get(0), 0.5, -1.2, 0, 0);
            s.pieces().placeOnFloor(two.get(1), 0.5, -1.2, 0, 0);
        });
        double d = Math.hypot(two.get(0).x() - two.get(1).x(), two.get(0).y() - two.get(1).y());
        assertTrue(d >= 2 * two.get(0).kind.radius() - 0.003, "centers " + d + " m apart");
    }
}
