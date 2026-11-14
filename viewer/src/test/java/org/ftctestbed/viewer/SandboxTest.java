package org.ftctestbed.viewer;

import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.game.MatchState;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRunner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The sandbox: the TeleOp with no match around it. */
class SandboxTest {
    static OpModeInfo find(SimHarness h, String name) {
        return h.availableOpModes().stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void itStopsAMatchAndDrivesWithNoClockForAsLongAsYouLike() {
        try (SimHarness h = SimHarness.load()) {
            h.startAt("Red Wall");
            h.match.start(find(h, "BuzzBot Auto Red"), find(h, "BuzzBot TeleOp"));
            while (h.match.busy()) Thread.onSpinWait();
            h.runFor(3);
            assertEquals(MatchState.Phase.AUTO, h.sim.match().phase(), "a match is on");

            Sandbox.start(h.sim, h.match, h.opModes, find(h, "BuzzBot TeleOp"));
            assertEquals(OpModeRunner.State.RUNNING, h.opModes.state());
            assertEquals("BuzzBot TeleOp", h.opModes.current().name());
            assertEquals(MatchState.Phase.PRACTICE, h.sim.match().phase(), "no match");
            assertTrue(Double.isNaN(h.sim.snapshot().matchClock()), "no match clock");

            double x0 = h.xInches(), y0 = h.yInches(), hd = Math.toRadians(h.headingDegrees());
            h.gamepad1(g -> g.left_stick_y = -1).runFor(0.6);
            h.gamepad1(g -> g.left_stick_y = 0).runFor(0.4);
            double forward = (h.xInches() - x0) * Math.cos(hd) + (h.yInches() - y0) * Math.sin(hd);
            assertTrue(forward > 15, "the sticks drive it: " + forward);

            h.runFor(MatchState.TELEOP_SECONDS + 10);
            assertEquals(OpModeRunner.State.RUNNING, h.opModes.state(), "still running past 2:00");
            assertEquals(MatchState.Phase.PRACTICE, h.sim.match().phase());
            assertNull(h.error(), h.error());
        }
    }

    @Test
    void withAPartnerBothRobotsDriveAndTheResetPutsBothBack() {
        try (SimHarness h = SimHarness.loadWithPartner()) {
            SimHarness.Driver one = h.robot(1), two = h.robot(2);
            OpModeInfo tele1 = find(h, "BuzzBot TeleOp");
            OpModeInfo tele2 = two.availableOpModes().stream().filter(i -> i.name().equals("BuzzBot TeleOp")).findFirst().orElseThrow();
            Sandbox.start(h.sim, h.match, java.util.List.of(one.opModes, two.opModes), java.util.List.of(tele1, tele2));
            assertEquals(OpModeRunner.State.RUNNING, one.opModes.state());
            assertEquals(OpModeRunner.State.RUNNING, two.opModes.state());

            double y2 = two.yInches();
            one.gamepad1(g -> g.left_stick_y = -1);
            two.gamepad1(g -> g.left_stick_y = -1);
            h.runFor(0.6);
            one.gamepad1(g -> g.left_stick_y = 0);
            two.gamepad1(g -> g.left_stick_y = 0);
            h.runFor(0.4);
            assertTrue(one.xInches() > -45, "robot 1 drove away from the red wall: " + one.xInches());
            assertTrue(two.yInches() - y2 > 15, "robot 2 drove away from the audience wall: " + (two.yInches() - y2));

            h.sim.resetFieldWhileDriving();
            h.runFor(0.2);
            for (SimHarness.Driver d : java.util.List.of(one, two)) {
                var start = d.robot.startPosition().pose();
                assertEquals(start.x() / 0.0254, d.xInches(), 0.5, d.robot + " back at its start");
                assertEquals(start.y() / 0.0254, d.yInches(), 0.5, d.robot + " back at its start");
                assertEquals(OpModeRunner.State.RUNNING, d.opModes.state(), "the sandbox keeps running");
            }
        }
    }
}
