package org.ftctestbed.sim;

import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.DistanceSensor;

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.game.PieceKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A partner robot on the same field: its own hardware, and the two robots meet each other. */
class TwoRobotsTest {
    private static final double IN = 0.0254;

    private static Simulation twoRobots() {
        RobotConfig robot = RobotConfig.parse(SimulationTest.ROBOT, "robot");
        return new Simulation(FieldConfig.parse(SimulationTest.FIELD, "field"), List.of(robot, robot));
    }

    private static void run(Simulation sim, double seconds) {
        int steps = (int) Math.round(seconds / Simulation.DT);
        for (int i = 0; i < steps; i++) sim.step();
    }

    /** Full power forward on a robot's drive motors (its left side reversed, as robot code does). */
    private static void forward(SimRobot r, double power) {
        for (String name : new String[]{"lf", "lb"}) {
            DcMotorEx m = r.hardwareMap.get(DcMotorEx.class, name);
            m.setDirection(DcMotorSimple.Direction.REVERSE);
            m.setPower(power);
        }
        for (String name : new String[]{"rf", "rb"}) r.hardwareMap.get(DcMotorEx.class, name).setPower(power);
    }

    @Test
    void thePartnerStartsAtTheOtherStartOfItsAlliance() {
        Simulation sim = new Simulation(FieldConfig.load(Path.of("../config/field.jsonc")),
                List.of(RobotConfig.load(Path.of("../config/robot.jsonc")), RobotConfig.load(Path.of("../config/robot.jsonc"))));
        assertEquals(2, sim.robots().size());
        assertEquals("Red Wall", sim.robot(1).startPosition().name());
        assertEquals("Red Audience", sim.robot(2).startPosition().name());
        assertEquals("red", sim.robot(2).alliance());
        assertEquals(-38 * IN, sim.robot(2).x(), 1e-9);
        assertEquals(-61.585 * IN, sim.robot(2).y(), 1e-9);

        // Moving the first robot onto the partner's start swaps them: they never start on top of each other.
        sim.setStartPosition(sim.robot(2).startPosition());
        assertEquals("Red Audience", sim.robot(1).startPosition().name());
        assertEquals("Red Wall", sim.robot(2).startPosition().name());
        // A partner on the other alliance practices as an opponent.
        sim.setStartPosition(sim.robot(2), sim.fieldConfig.startPositions().get(2));
        assertEquals("blue", sim.robot(2).alliance());
        assertEquals("red", sim.robot(1).alliance());
    }

    @Test
    void eachRobotHasItsOwnHardware() {
        Simulation sim = twoRobots();
        SimRobot a = sim.robot(1), b = sim.robot(2);
        assertSame(a.hardwareMap, sim.hardwareMap);
        assertNotSame(a.hardwareMap.get(DcMotorEx.class, "lf"), b.hardwareMap.get(DcMotorEx.class, "lf"));
        a.moveRobot(-1, 0, 0);
        b.moveRobot(1, 0, Math.PI);
        forward(b, 0.6);
        run(sim, 1.0);
        assertEquals(-1, a.x(), 1e-6, "the first robot wasn't driven");
        assertTrue(b.x() < 0.6, "the partner drove forward (toward -x): " + b.x());
        assertEquals(0, a.speed(), 1e-6);
        assertTrue(b.batteryCurrent() > 1 && a.batteryCurrent() < 0.01, "each robot has its own battery");
    }

    @Test
    void robotsBumpIntoEachOtherInsteadOfDrivingThrough() {
        Simulation sim = twoRobots();
        SimRobot a = sim.robot(1), b = sim.robot(2);
        a.moveRobot(-0.6, 0, 0);
        b.moveRobot(0.1, 0, 0);
        for (String name : new String[]{"lf", "rf", "lb", "rb"}) {
            b.hardwareMap.get(DcMotorEx.class, name).setZeroPowerBehavior(com.qualcomm.robotcore.hardware.DcMotor.ZeroPowerBehavior.FLOAT);
        }
        forward(a, 1.0);
        double minGap = Double.MAX_VALUE;
        for (int i = 0; i < 1000; i++) {
            sim.step();
            minGap = Math.min(minGap, b.x() - a.x());
        }
        assertTrue(minGap > 18 * IN - 0.01, "the robots never overlap: centers " + minGap / IN + " in apart");
        assertTrue(b.x() > 0.2, "the partner was pushed along: " + b.x());
    }

    @Test
    void aDistanceSensorSeesTheOtherRobot() {
        Simulation sim = twoRobots();
        sim.robot(1).moveRobot(0, 0, 0);
        sim.robot(2).moveRobot(1.0, 0, Math.PI / 2);
        run(sim, 0.2);
        double seen = sim.robot(1).hardwareMap.get(DistanceSensor.class, "front").getDistance(DistanceUnit.METER);
        assertEquals(1.0 - 18 * IN, seen, 0.01, "from the sensor on its front to the partner's side");
    }

    @Test
    void aPiecePutDownNeverLandsOnEitherRobot() {
        Simulation sim = twoRobots();
        sim.robot(1).moveRobot(-0.5, 0, 0);
        sim.robot(2).moveRobot(0.5, 0, 0.4);
        PieceKind ball = new PieceKind("ball", "circle", 0.0635, 0.127, 0.127, 0.1, 0xFFFFFF, 0.5, 0.5, null);
        GamePiece p = sim.pieces().create(ball);
        sim.edit(s -> s.pieces().placeOnFloor(p, 0.5, 0, 0, 0));
        for (SimRobot r : sim.robots()) {
            assertFalse(r.covers(new double[]{p.x(), p.y(), 0.0635}), "not on " + r);
        }
    }

    @Test
    void aShotLeavingOneRobotIsntStoppedByTheOther() {
        Simulation sim = twoRobots();
        SimRobot a = sim.robot(1), b = sim.robot(2);
        a.moveRobot(0, 0, 0);
        b.moveRobot(1.2, 0, 0);
        PieceKind ball = new PieceKind("ball", "circle", 0.0635, 0.127, 0.127, 0.1, 0xFFFFFF, 0.5, 0.5, null);
        GamePiece p = sim.pieces().create(ball);
        sim.edit(s -> {
            s.pieces().launch(p, 0, 0, 0.15, 0, 0, 3.0, false, "red");
            p.setExitingFrom(a.body);
        });
        sim.step();
        assertSame(a.body, p.exitingFrom(), "still passing out of the robot that shot it");
        run(sim, 0.1);
        assertTrue(p.vz() > 1.5, "it flew on up instead of being pushed back: vz " + p.vz());
    }

    @Test
    void resettingTheFieldPutsBothRobotsBackAndSnapshotsShareTheField() {
        Simulation sim = new Simulation(FieldConfig.load(Path.of("../config/field.jsonc")),
                List.of(RobotConfig.load(Path.of("../config/robot.jsonc")), RobotConfig.load(Path.of("../config/robot.jsonc"))));
        sim.robot(1).moveRobot(0, 0, 0);
        sim.robot(2).moveRobot(0.5, 0.5, 1);
        sim.resetField();
        for (SimRobot r : sim.robots()) {
            assertEquals(r.startPosition().pose().x(), r.x(), 1e-9);
            assertEquals(r.startPosition().pose().y(), r.y(), 1e-9);
        }
        List<Snapshot> shots = sim.snapshots();
        assertEquals(2, shots.size());
        assertEquals(sim.robot(2).x(), shots.get(1).x(), 1e-12);
        assertSame(shots.get(0).pieces(), shots.get(1).pieces());
        assertEquals(shots.get(0).time(), shots.get(1).time());
        assertEquals(sim.snapshot().x(), shots.get(0).x(), 1e-12);
    }
}
