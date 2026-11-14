package org.ftctestbed.sim;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;

import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.MotorSpec;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.game.PieceKind;
import org.ftctestbed.sim.hardware.EncoderSource;
import org.ftctestbed.sim.hardware.MotorPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Weight transfer, rolling resistance, the battery, encoder overflow and flying-ball physics. */
class PhysicsDetailTest {
    private static final double IN = 0.0254;
    private Simulation sim;
    private DcMotorEx lf, rf, lb, rb, lift;

    @BeforeEach
    void setUp() {
        sim = new Simulation(RobotConfig.parse(SimulationTest.ROBOT, "robot"), FieldConfig.parse(SimulationTest.FIELD, "field"));
        lf = sim.hardwareMap.get(DcMotorEx.class, "lf");
        rf = sim.hardwareMap.get(DcMotorEx.class, "rf");
        lb = sim.hardwareMap.get(DcMotorEx.class, "lb");
        rb = sim.hardwareMap.get(DcMotorEx.class, "rb");
        lift = sim.hardwareMap.get(DcMotorEx.class, "lift");
        lf.setDirection(DcMotor.Direction.REVERSE);
        lb.setDirection(DcMotor.Direction.REVERSE);
        sim.moveRobot(0, -50 * IN, Math.PI / 2);
    }

    private void run(double seconds) {
        for (int i = 0; i < Math.round(seconds / Simulation.DT); i++) sim.step();
    }

    private void drive(double p) {
        for (DcMotorEx m : new DcMotorEx[]{lf, rf, lb, rb}) m.setPower(p);
    }

    private double load(String wheel) {
        return sim.snapshot().wheels().stream().filter(w -> w.label().equals(wheel)).findFirst().orElseThrow().load();
    }

    @Test
    void accelerationShiftsWeightToTheBackWheels() {
        double weight = sim.robotConfig.mass() * 9.81;
        run(0.1);
        assertEquals(weight / 4, load("FL"), 0.5, "standing still: a quarter of the weight on each wheel");
        drive(1);
        run(0.15);
        double front = load("FL") + load("FR"), back = load("BL") + load("BR");
        assertTrue(back > 1.3 * front, "launching: front " + front + " N, back " + back + " N");
        assertEquals(weight, front + back, 0.5, "weight is conserved");
        run(1.0);
        drive(0); // BRAKE: weight moves to the front
        run(0.1);
        assertTrue(load("FL") > load("BL"), "braking loads the front wheels");
    }

    @Test
    void aTopHeavyRobotLiftsItsBackWheelsWhenBrakingHard() {
        Simulation tall = new Simulation(RobotConfig.parse(SimulationTest.ROBOT.replace("\"massLbs\": 30,", "\"massLbs\": 30, \"cgHeightIn\": 17,"), "robot"),
                FieldConfig.parse(SimulationTest.FIELD, "field"));
        DcMotorEx[] m = {tall.hardwareMap.get(DcMotorEx.class, "lf"), tall.hardwareMap.get(DcMotorEx.class, "rf"),
                tall.hardwareMap.get(DcMotorEx.class, "lb"), tall.hardwareMap.get(DcMotorEx.class, "rb")};
        m[0].setDirection(DcMotor.Direction.REVERSE);
        m[2].setDirection(DcMotor.Direction.REVERSE);
        tall.moveRobot(0, -60 * IN, Math.PI / 2);
        for (DcMotorEx motor : m) motor.setPower(1);
        for (int i = 0; i < 750; i++) tall.step();
        assertNull(tall.snapshot().physics().liftingWheels(), "driving steadily: all four wheels down");
        for (DcMotorEx motor : m) motor.setPower(-1); // full reverse to stop: the hardest braking there is
        String lifted = null;
        for (int i = 0; i < 300 && lifted == null; i++) {
            tall.step();
            lifted = tall.snapshot().physics().liftingWheels();
        }
        assertNotNull(lifted, "back wheels lift with the center of gravity 17 in up");
        assertTrue(lifted.contains("BL") && lifted.contains("BR"), "lifting: " + lifted);
    }

    @Test
    void raisingTheLiftRaisesTheCenterOfGravity() {
        double before = sim.cgHeight();
        lift.setPower(1);
        run(1.5);
        double raised = sim.snapshot().mechanisms().get(0).position() * IN;
        double expected = before + sim.robotConfig.mechanisms().get(0).mass() * raised / sim.robotConfig.mass();
        assertTrue(raised > 20 * IN, "lift went up " + raised / IN + " in");
        assertEquals(expected, sim.cgHeight(), 1e-3);
    }

    @Test
    void rollingResistanceStopsACoastingRobot() {
        for (DcMotorEx m : new DcMotorEx[]{lf, rf, lb, rb}) m.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.FLOAT);
        drive(0.5);
        run(1.0);
        drive(0);
        double y0 = sim.robotY();
        run(3.0);
        assertTrue(Math.hypot(sim.snapshot().vx(), sim.snapshot().vy()) < 0.01, "stopped");
        double coast = (sim.robotY() - y0) / IN;
        assertTrue(coast > 5 && coast < 40, "coasted " + coast + " in");
    }

    @Test
    void theBatteryDrainsAndSagsMoreUnderLoad() {
        double rest = sim.battery().restingVolts();
        drive(1);
        run(0.05);
        double sagging = sim.batteryVolts();
        assertTrue(sagging < rest - 1.0, "full-power launch sags the battery: " + rest + " -> " + sagging);
        sim.moveRobot(0, 60 * IN, Math.PI / 2); // against the far wall: the drive motors push hard
        run(10);
        double used = sim.battery().usedMah();
        assertTrue(used > 30 && used < 200, "10 s of pushing used " + used + " mAh");
        assertTrue(sim.battery().restingVolts() < rest, "resting voltage falls as charge is used");
        sim.resetField();
        assertEquals(0, sim.battery().usedMah(), 1e-9, "a new match gets a charged battery");
    }

    @Test
    void aHandPushesTheRobotWhereItIsPulled() {
        sim.moveRobot(0, 0, 0);
        double target = 24 * IN;
        sim.setHand(0, 0, target, 0); // grab the center, pull it 24 in forward
        run(4);
        sim.clearHand();
        assertEquals(target, sim.robotX(), 1.0 * IN, "pushed 24 in forward");
        assertEquals(0, sim.robotY(), 1.0 * IN, "straight");
        assertEquals(0, sim.robotSpeed(), 0.05, "and it stops there");
    }

    @Test
    void hubReportsVelocityAsA16BitNumber() {
        MotorPort port = new MotorPort("pod", new MotorSpec("e", "encoder", 2000, 6000, 1, 1, 0), "hub", 0);
        double[] t = {0};
        port.attachEncoderSource(new EncoderSource() {
            @Override public double ticks() { return 40_000 * t[0]; }
            @Override public double ticksPerSecond() { return 40_000; }
        });
        for (int i = 0; i < 30; i++) { // the hub counts ticks over the last 50 ms
            t[0] += 0.002;
            port.control(0.002);
        }
        assertEquals(40_000, port.liveVelocityTps(), 0.0);
        assertEquals(40_000 - 65_536, port.reportedVelocityTps(), 0.0, "wraps above 32767 like the REV hub");
    }

    private GamePiece ball() {
        PieceKind kind = new PieceKind("ball", "circle", 1.4 * IN, 2.8 * IN, 2.8 * IN, 0.025, 0xFFFF00, 0.08, 0.45, null);
        return sim.pieces().create(kind);
    }

    /** {highest point (m), time in the air (s)} for a ball launched at 55 degrees and 5 m/s with this spin. */
    private double[] flight(double spin) {
        GamePiece b = ball();
        double v = 5, a = Math.toRadians(55); // lands well short of the far wall
        sim.edit(s -> {
            s.pieces().launch(b, -60 * IN, 0, 15 * IN, v * Math.cos(a), 0, v * Math.sin(a), false, null);
            b.setSpin(0, -spin, 0); // about -y while flying +x: backspin for spin > 0
        });
        double apex = 0, time = 0;
        while (b.state() == GamePiece.State.AIRBORNE && b.z() > 1.4 * IN + 0.001 && time < 5) {
            sim.step();
            time += Simulation.DT;
            apex = Math.max(apex, b.z());
        }
        return new double[]{apex, time};
    }

    @Test
    void backspinFloatsAShotAndTopspinDropsIt() {
        // The Magnus force is square to the path: on a steep shot backspin lifts the ball (higher,
        // longer in the air) more than it changes the range.
        sim.moveRobot(0, 60 * IN, 0); // out of the way
        double[] plain = flight(0);
        double[] backspin = flight(5 / (1.4 * IN));   // rolling off a hood: spin x radius = speed
        double[] topspin = flight(-5 / (1.4 * IN));
        assertTrue(backspin[0] > plain[0] + 0.01, "backspin apex " + backspin[0] + " vs " + plain[0]);
        assertTrue(topspin[0] < plain[0] - 0.01, "topspin apex " + topspin[0] + " vs " + plain[0]);
        assertTrue(backspin[1] > plain[1] && plain[1] > topspin[1], "time in the air");
    }

    @Test
    void aBallDroppedOnTheRobotBouncesOffItsTop() {
        sim.moveRobot(0, 0, 0);
        run(0.1);
        GamePiece b = ball();
        sim.edit(s -> s.pieces().launch(b, 0, 0, 40 * IN, 0, 0, -2, false, null));
        double lowest = Double.MAX_VALUE;
        boolean bounced = false;
        for (int i = 0; i < 500 && b.state() == GamePiece.State.AIRBORNE; i++) {
            sim.step();
            lowest = Math.min(lowest, b.z());
            if (b.vz() > 0.5) bounced = true;
        }
        assertTrue(bounced, "it bounced");
        assertTrue(lowest > sim.robotConfig.height(), "never went into the robot: lowest " + lowest / IN + " in");
    }
}
