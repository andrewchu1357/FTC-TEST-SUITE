package org.ftctestbed.sim.season.biobuzz;

import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.GamePiece;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.ftctestbed.sim.season.biobuzz.BioBuzzField.*;
import static org.junit.jupiter.api.Assertions.*;

/** The HIVE as a rigid body on its pivot: torque, the swing, the dampers, pieces rolling out. */
class HivePhysicsTest {
    Simulation sim;
    BioBuzzSeason season;
    Hive red;

    @BeforeEach
    void setUp() {
        sim = new Simulation(RobotConfig.load(Path.of("../config/robot.jsonc")), FieldConfig.load(Path.of("../config/field.jsonc")));
        season = (BioBuzzSeason) sim.season();
        red = season.hives().get(0);
    }

    void run(double seconds) {
        for (int i = 0; i < Math.round(seconds / Simulation.DT); i++) sim.step();
    }

    /** Drop a piece straight down into the red HIVE's CELL on this side (-1 audience, +1 far). */
    GamePiece drop(String kind, int side) {
        GamePiece p = sim.pieces().create(kind.equals("pollen") ? season.pollen() : season.nectar(kind));
        sim.pieces().launch(p, -HIVE_X, side * MOUTH_OFFSET_Y, MOUTH_HEIGHT + 0.25, 0, 0, -1, true, "red");
        run(0.4);
        return p;
    }

    @Test
    void theStartingNectarSitWhereTheCadHasThem() {
        // CAD: red's pre-loaded NECTAR at (-21.0, -17.4, -13.75; -9.5; 49.9) in.
        List<GamePiece> in = red.upContents();
        assertEquals(3, in.size());
        for (GamePiece p : in) {
            assertEquals(-9.6, p.y() / IN, 0.4, "out from the pivot");
            assertEquals(49.8, p.z() / IN, 0.4, "height");
        }
        assertEquals(-21.0, in.get(0).x() / IN, 0.3, "against the side wall toward the red wall");
    }

    @Test
    void enoughTorqueTipsItAndTheSwingIsSmooth() {
        drop("pollen", -1);
        drop("pollen", -1);
        run(1.0);
        assertEquals(-1, red.upSide);
        assertTrue(red.tiltDegrees() < -29, "3 NECTAR + 2 POLLEN don't tip it: " + red.tiltDegrees());
        run(3.0);
        assertEquals(-30, red.tiltDegrees(), 0.01, "the dampers ease it back onto its stop");
        assertTrue(red.fill() < 1 && red.fill() > 0.8, "fill " + red.fill());

        GamePiece last = drop("pollen", -1);
        assertEquals(GamePiece.State.CONTAINED, last.state());
        double start = sim.clock.seconds() - 0.4;
        double prev = red.tiltDegrees(), moving = Double.NaN, arrived = Double.NaN;
        int leftBeforeArrival = 0;
        List<Double> angles = new ArrayList<>();
        for (int i = 0; i < 3000 && Double.isNaN(arrived); i++) {
            sim.step();
            double a = red.tiltDegrees();
            angles.add(a);
            if (Double.isNaN(moving) && a > -29.9) moving = sim.clock.seconds();
            assertTrue(a >= prev - 0.05 || a > 29, "the swing doesn't go backwards: " + prev + " -> " + a);
            prev = a;
            if (red.resting && red.upSide == 1) {
                arrived = sim.clock.seconds();
                leftBeforeArrival = (int) red.contents.stream().filter(c -> c.roll == 0).count();
            }
        }
        assertFalse(Double.isNaN(arrived), "it tipped");
        double swing = arrived - moving;
        System.out.printf("HIVE tip: starts %.2f s after the last POLLEN goes in, swings over in %.2f s%n", moving - start, swing);
        assertTrue(swing > 0.6 && swing < 3.0, "swing " + swing + " s");
        assertTrue(leftBeforeArrival < 6, "pieces start rolling out while it's still swinging (" + leftBeforeArrival + " still resting)");
        assertEquals(1, ((BioBuzzSnapshot) sim.snapshot().season()).red().tipsTeleop());

        run(2.0);
        long spilled = sim.pieces().all().stream()
                .filter(p -> p.state() == GamePiece.State.FLOOR && Math.abs(p.x() + HIVE_X) < 0.4 && p.y() < -0.3).count();
        assertEquals(6, spilled, "all six roll out of the CELL that went down and land on the audience side");
    }

    @Test
    void theSecondRowPullsHarderThanTheFirst() {
        drop("pollen", -1);
        drop("pollen", -1);
        drop("pollen", -1);
        run(4.0); // tipped: the far CELL is up and empty
        assertEquals(1, red.upSide);
        assertEquals(0, red.upContents().size());
        double[] fill = new double[9];
        for (int i = 1; i <= 7; i++) {
            drop("pollen", 1);
            fill[i] = red.fill();
        }
        run(1.0);
        assertEquals(1, red.upSide, "7 POLLEN don't tip it: " + fill[7]);
        double firstRow = fill[7] - fill[6];
        drop("pollen", 1);
        run(0.05);
        double secondRow = red.fill() - fill[7];
        assertTrue(secondRow > firstRow * 1.1, "the 8th POLLEN starts a row further out: " + firstRow + " vs " + secondRow);
        run(4.0);
        assertEquals(-1, red.upSide, "8 POLLEN tip it");
    }

    @Test
    void aHardShotRocksTheHiveButDoesNotTipIt() {
        // A POLLEN lobbed steeply into the CELL at 7 m/s: its momentum about the pivot kicks the
        // HIVE off its stop; with only 3 NECTAR + 1 POLLEN it falls back.
        GamePiece p = sim.pieces().create(season.pollen());
        double c = Math.cos(CELL_TILT), s = Math.sin(CELL_TILT);
        // Just outside the opening, coming in along the CELL (down and in toward the pivot).
        double y = -MOUTH_OFFSET_Y - 0.05 * c, z = MOUTH_HEIGHT + 0.05 * s;
        sim.pieces().launch(p, -HIVE_X, y, z, 0, 7 * c * 0.3, -7, false, "red");
        double maxOff = 0;
        for (int i = 0; i < 1500; i++) {
            sim.step();
            maxOff = Math.max(maxOff, Math.abs(red.tiltDegrees() + 30));
        }
        assertEquals(GamePiece.State.CONTAINED, p.state(), "it went in");
        System.out.printf("hard shot rocked the HIVE %.2f degrees%n", maxOff);
        assertTrue(maxOff > 0.1, "rocked " + maxOff);
        assertEquals(-1, red.upSide);
        assertEquals(-30, red.tiltDegrees(), 0.01, "back on its stop");
    }

    @Test
    void theInertiaFitsAMeasuredTipTime() {
        double r = POLLEN_DIAMETER / 2;
        double i = Hive.inertiaForTipSeconds(1.2, 0.19, 0.025, r);
        assertEquals(1.2, Hive.tipSeconds(0.19, 0.025, r, i), 0.01);
        double standard = Hive.tipSeconds(0.19, 0.025, r, 0.3);
        System.out.printf("with 0.3 kg m^2 a tip by one POLLEN too many takes %.2f s%n", standard);
        assertTrue(standard > 0.8 && standard < 2.5);
    }
}
