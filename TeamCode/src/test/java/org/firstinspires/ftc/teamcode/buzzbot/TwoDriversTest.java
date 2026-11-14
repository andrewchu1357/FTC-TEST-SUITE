package org.firstinspires.ftc.teamcode.buzzbot;

import org.ftctestbed.sim.SimHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Two people practicing at once: BuzzBot and a partner BuzzBot, each with its own driver and OpMode. */
class TwoDriversTest {
    SimHarness sim;

    @BeforeEach
    void setUp() {
        PoseStorage.pose = null;
        PoseStorage.alliance = Alliance.RED;
        sim = SimHarness.loadWithPartner();
    }

    @AfterEach
    void tearDown() {
        sim.close();
    }

    @Test
    void eachDriverDrivesTheirOwnRobot() {
        SimHarness.Driver one = sim.robot(1), two = sim.robot(2);
        assertEquals("Red Wall", one.robot.startPosition().name());
        assertEquals("Red Audience", two.robot.startPosition().name());
        one.init("BuzzBot TeleOp");
        two.init("BuzzBot TeleOp");
        one.start();
        two.start();
        sim.runFor(0.3);
        double x1 = one.xInches(), y1 = one.yInches(), x2 = two.xInches(), y2 = two.yInches();
        // Robot 1 faces +x (away from the red wall); robot 2 faces +y (away from the audience wall).
        one.gamepad1(g -> g.left_stick_y = -1);
        two.gamepad1(g -> g.left_stick_x = 1);
        sim.runFor(1.0);
        one.gamepad1(g -> g.left_stick_y = 0);
        two.gamepad1(g -> g.left_stick_x = 0);
        sim.runFor(0.5);
        assertTrue(one.xInches() - x1 > 20 && Math.abs(one.yInches() - y1) < 4,
                "robot 1 drove forward: " + (one.xInches() - x1) + ", " + (one.yInches() - y1));
        // Strafing right while facing +y is toward +x.
        assertTrue(two.xInches() - x2 > 12 && Math.abs(two.yInches() - y2) < 4,
                "robot 2 strafed right: " + (two.xInches() - x2) + ", " + (two.yInches() - y2));
        assertNull(one.error(), one.error());
        assertNull(two.error(), two.error());
        assertFalse(two.telemetry().isEmpty(), "robot 2 has its own telemetry");
    }

    @Test
    void eachRobotHasItsOwnCopyOfTheTeamCode() throws Exception {
        // Opponents this time: robot 2 plays the blue AUTO from the blue wall.
        sim.robot(2).startAt("Blue Wall");
        PoseStorage.alliance = Alliance.BLUE; // robot 1's AUTO has to set it back to RED
        double[] farthest = new double[2];
        sim.sim.addStepListener(s -> {
            for (int i = 0; i < 2; i++) {
                var r = s.robot(i + 1);
                var start = r.startPosition().pose();
                farthest[i] = Math.max(farthest[i], Math.hypot(r.x() - start.x(), r.y() - start.y()));
            }
        });
        sim.runMatch(List.of("BuzzBot Auto Red", "BuzzBot Auto Blue"), Arrays.asList(null, null));
        assertNull(sim.robot(1).error(), sim.robot(1).error());
        assertNull(sim.robot(2).error(), sim.robot(2).error());
        assertTrue(farthest[0] > 1.0 && farthest[1] > 1.0, "both robots drove their AUTOs (m): " + Arrays.toString(farthest));

        // Robot 1's code is this project's classes; robot 2's is a copy with its own static fields.
        Class<?> theirs = Class.forName(PoseStorage.class.getName(), true,
                sim.robot(2).availableOpModes().get(0).type().getClassLoader());
        assertNotSame(PoseStorage.class, theirs);
        assertEquals(Alliance.RED, PoseStorage.alliance, "robot 1's AUTO left RED for its TeleOp");
        assertEquals("BLUE", String.valueOf(theirs.getField("alliance").get(null)), "robot 2's AUTO left BLUE for its own TeleOp");
        Object theirPose = theirs.getField("pose").get(null);
        assertNotNull(PoseStorage.pose);
        assertNotNull(theirPose);
        double theirX = theirPose.getClass().getField("x").getDouble(theirPose);
        assertTrue(PoseStorage.pose.x < 0 && theirX > 0, "each AUTO saved its own robot's pose: " + PoseStorage.pose.x + " / " + theirX);
    }

    @Test
    void aPartnersOpModeDoesntSlowThisOneDown() {
        // Each robot's loop time on its own, then with the other one running too.
        sim.robot(1).init("BuzzBot TeleOp").start();
        sim.runFor(2.0);
        double oneAlone = sim.robot(1).loopMillis();
        sim.robot(2).init("BuzzBot TeleOp").start();
        sim.runFor(2.0);
        double oneTogether = sim.robot(1).loopMillis(), twoTogether = sim.robot(2).loopMillis();
        sim.robot(1).stop();
        sim.runFor(2.0);
        double twoAlone = sim.robot(2).loopMillis();
        assertTrue(oneAlone > 1 && oneAlone < 40 && twoAlone > 1 && twoAlone < 40, "real loop times: " + oneAlone + ", " + twoAlone);
        assertEquals(oneAlone, oneTogether, 0.1 * oneAlone, "robot 1's loop time with the partner running");
        assertEquals(twoAlone, twoTogether, 0.1 * twoAlone, "robot 2's loop time with robot 1 running");
    }

    @Test
    void stoppingOneRobotLeavesTheOtherDriving() {
        sim.robot(1).init("BuzzBot TeleOp").start();
        sim.robot(2).init("BuzzBot TeleOp").start();
        sim.runFor(0.3);
        sim.robot(1).stop();
        double x2 = sim.robot(2).xInches();
        sim.robot(2).gamepad1(g -> g.left_stick_x = 1);
        sim.runFor(0.8);
        assertTrue(sim.robot(2).xInches() - x2 > 8, "robot 2 still drives after robot 1 stopped: " + (sim.robot(2).xInches() - x2));
        assertEquals(org.ftctestbed.sim.opmode.OpModeRunner.State.STOPPED, sim.robot(1).opModes.state());
    }
}
