package org.ftctestbed.sim;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.Servo;

import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.game.PieceKind;
import org.ftctestbed.sim.season.biobuzz.BioBuzzField;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSeason;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BIOBUZZ realism with the shipped BuzzBot: the field as FIRST's CAD has it, the roller intake
 * pulling balls by friction (edge misses, jams, a full hopper), the claw losing a NECTAR in a
 * crash, and balls that aren't all the same.
 */
class GameRealismTest {
    private static final double IN = 0.0254;

    Simulation sim;
    BioBuzzSeason season;
    DcMotorEx[] drive;

    @BeforeEach
    void setUp() {
        sim = new Simulation(RobotConfig.load(Path.of("../config/robot.jsonc")), FieldConfig.load(Path.of("../config/field.jsonc")));
        season = (BioBuzzSeason) sim.season();
        String[] names = {"leftFront", "rightFront", "leftBack", "rightBack"};
        drive = new DcMotorEx[4];
        for (int i = 0; i < 4; i++) {
            drive[i] = sim.hardwareMap.get(DcMotorEx.class, names[i]);
            drive[i].setDirection(i % 2 == 0 ? DcMotorSimple.Direction.REVERSE : DcMotorSimple.Direction.FORWARD);
        }
    }

    void run(double seconds) {
        for (int i = 0; i < Math.round(seconds / Simulation.DT); i++) sim.step();
    }

    void driveForward(double power) {
        for (DcMotorEx m : drive) m.setPower(power);
    }

    /** A free POLLEN placed at robot-frame (lx, ly) inches, for a robot facing +x at (rx, ry). */
    GamePiece pollenAt(double rx, double ry, double lx, double ly) {
        GamePiece p = sim.pieces().create(season.pollen());
        sim.pieces().placeOnFloor(p, (rx + lx) * IN, (ry + ly) * IN, 0, 0);
        return p;
    }

    void emptyHopper() {
        DcMotor intake = sim.hardwareMap.get(DcMotor.class, "intake");
        intake.setPower(-1);
        run(1.5);
        intake.setPower(0);
        run(0.3);
        assertTrue(sim.handling().hopper().isEmpty());
        // Clear the spat-out balls away.
        for (GamePiece p : sim.pieces().all()) {
            if (p.state() == GamePiece.State.FLOOR && Math.hypot(p.x() - sim.robotX(), p.y() - sim.robotY()) < 0.6) {
                sim.pieces().placeOnFloor(p, 0.5, 1.2 + 0.1 * p.id % 5, 0, 0);
            }
        }
    }

    double intakeAmps() {
        return sim.snapshot().motors().stream().filter(m -> m.name().equals("intake")).findFirst().orElseThrow().currentA();
    }

    // ------------------------------------------------------------------ field geometry

    @Test
    void theFieldMeasuresWhatTheCadSays() {
        assertEquals(141.17 * IN, FieldConfig.load(Path.of("../config/field.jsonc")).size(), 1e-6);
        assertEquals(70.585 * IN, BioBuzzField.HALF, 1e-9);
        // Back into the red wall: the robot stops with its back on the wall's inside face.
        sim.moveRobot(-50 * IN, 0, 0);
        driveForward(-0.4);
        run(2.0);
        assertEquals(-70.585 + 9, sim.robotX() / IN, 0.3);
        BioBuzzSnapshot.FlowerState far = ((BioBuzzSnapshot) sim.snapshot().season()).flowers().get(0);
        assertEquals(-23.395, far.axisX() / IN, 1e-6, "FLOWER 1 along the far wall");
        assertEquals(70.585 - 2.54, far.axisY() / IN, 1e-6, "its axis 2.54 in from the wall");
    }

    @Test
    void theHiveLegsStopARobotButBallsRollUnderThem() {
        // Under the HIVES (their CELLS are 30 in up), between the frame's feet, backing toward red's
        // leg. Without the leg its back would stop at the foot bar (x = -22.75 in, center -13.75).
        sim.moveRobot(0, 14 * IN, 0);
        driveForward(-0.3);
        run(3.0);
        driveForward(0);
        double back = sim.robotX() / IN - 9;
        assertTrue(back > -20.5, "the leaning leg stops the robot before the foot bar (back at " + back + " in)");
        // A ball rolling the same way passes under the leg and stops at the foot bar.
        sim.moveRobot(40 * IN, -40 * IN, 0);
        GamePiece p = sim.pieces().create(season.pollen());
        sim.pieces().placeOnFloor(p, -5 * IN, 14 * IN, -1.5, 0);
        double farthest = 0;
        for (int i = 0; i < 1000; i++) {
            sim.step();
            farthest = Math.min(farthest, p.x() / IN);
        }
        assertTrue(farthest < -20.5 && farthest > -22.75, "rolled under the leg to the foot bar (x = -22.75): " + farthest);
    }

    // ------------------------------------------------------------------ intake

    @Test
    void rollersPullABallInByFriction() {
        emptyHopper();
        sim.moveRobot(0, -40 * IN, 0);
        run(0.2);
        GamePiece p = pollenAt(0, -40, 10.5, 0);
        DcMotor intake = sim.hardwareMap.get(DcMotor.class, "intake");
        intake.setPower(1);
        double t0 = sim.clock.seconds();
        for (int i = 0; i < 500 && p.state() != GamePiece.State.HELD; i++) sim.step();
        assertEquals(GamePiece.State.HELD, p.state());
        double took = sim.clock.seconds() - t0;
        assertTrue(took > 0.01 && took < 0.5, "pulled in after " + took + " s (the roller has to spin up)");
        assertEquals(1, sim.handling().hopper().size());
    }

    @Test
    void withTheRollersOffTheRobotPushesABallAlongInItsMouth() {
        emptyHopper();
        sim.moveRobot(-30 * IN, -40 * IN, 0);
        run(0.2);
        GamePiece p = pollenAt(-30, -40, 12, 0);
        driveForward(0.3);
        run(1.0);
        driveForward(0);
        assertEquals(GamePiece.State.FLOOR, p.state(), "not intaken");
        double lx = p.x() / IN - sim.robotX() / IN;
        // It bounces ahead and the robot catches up with it again.
        assertTrue(lx > 7.5 && lx < 16, "ball just ahead of the robot or in its mouth, " + lx + " in ahead");
        assertTrue(p.x() / IN > -10, "and moved with the robot: x " + p.x() / IN);
    }

    @Test
    void aBallOffTheEndOfTheRollersIsPushedAside() {
        emptyHopper();
        sim.moveRobot(0, -40 * IN, 0);
        run(0.2);
        GamePiece p = pollenAt(0, -40, 10, 8 + 0.4 * 1.4); // just a tenth of it under the rollers
        sim.hardwareMap.get(DcMotor.class, "intake").setPower(1);
        run(1.0);
        assertNotEquals(GamePiece.State.HELD, p.state());
        assertTrue(p.y() / IN + 40 > 8.6, "pushed out past the end: " + (p.y() / IN + 40));
    }

    @Test
    void twoBallsSideBySideJamTheThroat() {
        emptyHopper();
        sim.moveRobot(0, -40 * IN, 0);
        run(0.2);
        // Two identical balls, so they reach the throat together: with the 1% size variation one
        // can arrive a step ahead of the other and slip through alone.
        sim.pieces().setVariation(PieceKind.Variation.NONE, 0);
        GamePiece a = pollenAt(0, -40, 10.2, 1.45), b = pollenAt(0, -40, 10.2, -1.45);
        DcMotor intake = sim.hardwareMap.get(DcMotor.class, "intake");
        intake.setPower(1);
        run(0.5);
        double free = Double.NaN;
        boolean jammed = false;
        for (int i = 0; i < 50; i++) {
            sim.step();
            jammed |= sim.handling().parts().intakeJammed();
        }
        assertTrue(jammed, "wedged side by side");
        double loaded = intakeAmps();
        assertTrue(sim.handling().hopper().size() < 2, "they can't both go in together");
        // Reverse: the rollers push them back out.
        intake.setPower(-1);
        run(0.5);
        intake.setPower(0);
        run(0.3);
        assertFalse(sim.handling().parts().intakeJammed());
        for (GamePiece p : List.of(a, b)) sim.pieces().placeOnFloor(p, 0.5, 1.5 + 0.1 * p.id % 3, 0, 0);
        intake.setPower(1);
        run(1.0);
        free = intakeAmps();
        assertTrue(loaded > free + 0.1, "pulling on a jam loads the motor: " + loaded + " A vs " + free + " A spinning free");
    }

    @Test
    void aFullHopperLeavesTheNextBallWaitingAtTheThroat() {
        assertEquals(4, sim.handling().hopper().size(), "4 pre-loads");
        sim.moveRobot(0, -40 * IN, 0);
        run(0.2);
        GamePiece p = pollenAt(0, -40, 10.5, 0);
        sim.hardwareMap.get(DcMotor.class, "intake").setPower(1);
        run(1.0);
        assertEquals(GamePiece.State.FLOOR, p.state(), "no room");
        assertEquals(8, p.x() / IN - sim.robotX() / IN, 0.6, "waiting at the throat");
    }

    // ------------------------------------------------------------------ claw

    /** Grab a NECTAR behind the robot (facing +x at x0) and lift it 6 in. */
    GamePiece carryNectar(double x0) {
        sim.moveRobot(x0 * IN, -40 * IN, 0);
        GamePiece n = sim.pieces().create(season.nectar("red"));
        sim.pieces().placeOnFloor(n, (x0 - 9.6) * IN, -40 * IN, 0, 0);
        Servo claw = sim.hardwareMap.get(Servo.class, "claw");
        claw.setPosition(0.8);
        run(0.6);
        claw.setPosition(0.2);
        run(0.6);
        assertEquals(GamePiece.State.HELD, n.state());
        DcMotorEx lift = sim.hardwareMap.get(DcMotorEx.class, "lift");
        lift.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        lift.setTargetPosition(500);
        lift.setMode(DcMotor.RunMode.RUN_TO_POSITION);
        lift.setPower(0.6);
        run(1.5);
        assertEquals(GamePiece.State.HELD, n.state(), "lifting doesn't shake it loose");
        return n;
    }

    @Test
    void aGentleBumpKeepsTheNectarAndACrashShakesItLoose() {
        GamePiece n = carryNectar(40);
        driveForward(0.3);
        run(2.5);
        driveForward(0);
        run(0.3);
        assertTrue(sim.robotX() / IN > 61, "reached the wall");
        assertEquals(GamePiece.State.HELD, n.state(), "a slow bump into the wall");

        sim.resetField();
        n = carryNectar(30);
        driveForward(1.0);
        double peak = 0;
        for (int i = 0; i < 1000 && n.state() == GamePiece.State.HELD; i++) {
            sim.step();
            peak = Math.max(peak, sim.robotSpeed());
        }
        assertNotEquals(GamePiece.State.HELD, n.state(), "hitting the wall at " + peak / IN + " in/s");
    }

    // ------------------------------------------------------------------ balls

    @Test
    void noTwoBallsAreQuiteTheSame() {
        List<GamePiece> pollen = sim.pieces().all().stream().filter(p -> p.kind.id().equals("pollen")).toList();
        PieceKind nominal = season.pollen();
        double sum = 0, sum2 = 0, minD = 1, maxD = 0;
        for (GamePiece p : pollen) {
            double m = p.kind.mass() / nominal.mass() - 1;
            sum += m;
            sum2 += m * m;
            assertTrue(Math.abs(m) <= 0.0601, "mass within 2 standard deviations: " + m);
            minD = Math.min(minD, p.kind.radius());
            maxD = Math.max(maxD, p.kind.radius());
            assertEquals(nominal.radius(), p.kind.nominalRadius(), 1e-12);
        }
        double sd = Math.sqrt(sum2 / pollen.size() - Math.pow(sum / pollen.size(), 2));
        assertTrue(sd > 0.015 && sd < 0.045, "mass spread " + sd);
        assertTrue(maxD > minD, "sizes differ");
        // Repeatable: the same seed makes the same balls.
        Simulation again = new Simulation(RobotConfig.load(Path.of("../config/robot.jsonc")), FieldConfig.load(Path.of("../config/field.jsonc")));
        assertEquals(pollen.get(5).kind.mass(), again.pieces().all().stream().filter(p -> p.id == pollen.get(5).id).findFirst().orElseThrow().kind.mass());
        assertSame(nominal, nominal.varied(PieceKind.Variation.NONE, new Random(3)));
    }
}
