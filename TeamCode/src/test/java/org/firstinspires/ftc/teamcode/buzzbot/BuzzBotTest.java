package org.firstinspires.ftc.teamcode.buzzbot;

import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.game.MatchState;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** BuzzBot's OpModes played on the simulated BIOBUZZ field. */
class BuzzBotTest {
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

    BioBuzzSnapshot.Score score(String alliance) {
        return ((BioBuzzSnapshot) sim.season()).scoreOf(alliance);
    }

    void printScore() {
        for (String a : new String[]{"red", "blue"}) {
            System.out.println(a + " " + sim.score(a));
            for (String line : sim.season().breakdown(a)) System.out.println("  " + line);
        }
        for (String n : sim.season().notes()) System.out.println("  note: " + n);
        System.out.println("telemetry: " + sim.telemetry());
    }

    @Test
    void redAutoTipsTheHiveAndParks() {
        sim.startAt("Red Wall").runMatch("BuzzBot Auto Red", null);
        printScore();
        assertNull(sim.error(), sim.error());
        BioBuzzSnapshot.Score red = score("red");
        assertTrue(red.leave(), "LEAVE");
        assertTrue(red.autoPark(), "AUTO PARK");
        assertEquals(1, red.tipsAuto(), "one HIVE TIP in AUTO");
        // Bounces in the CELL are chaotic: with the start pose nudged by up to 1 in, 2 or 3 of the
        // second volley stay in (the tip happens every time).
        assertTrue(red.cellContents() >= 2, "half of the second volley stays in the far CELL: " + red.cellContents());
        assertEquals(4, sim.sim.handling().hopper().size(), "picked up the absent partner's pre-loads while parking");
        assertTrue(sim.season().notes().isEmpty(), "no fouls or warnings: " + sim.season().notes());
    }

    /** No AUTO before TeleOp: the driver picks blue (X) and the Audience start (Y) in INIT, and the pose is right. */
    @Test
    void teleOpWithoutAutoStartsWhereTheDriverSays() {
        sim.startAt("Blue Audience").init("BuzzBot TeleOp");
        sim.gamepad1(g -> { g.x = true; g.y = true; }).runFor(0.3);
        sim.gamepad1(g -> { g.x = false; g.y = false; }).runFor(0.2);
        assertTrue(String.join("\n", sim.telemetry()).contains("audience start"), sim.telemetry().toString());
        sim.start().runFor(1);
        assertNull(sim.error(), sim.error());
        String pose = sim.telemetry().stream().filter(l -> l.startsWith("Pose")).findFirst().orElseThrow();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((-?[\\d.]+), (-?[\\d.]+)").matcher(pose);
        assertTrue(m.find(), pose);
        assertEquals(sim.xInches(), Double.parseDouble(m.group(1)), 2, pose);
        assertEquals(sim.yInches(), Double.parseDouble(m.group(2)), 2, pose);
    }

    @Test
    void matchesCanBeRepeatedInOneSession() {
        sim.startAt("Red Wall").runMatch("BuzzBot Auto Red", null);
        int first = sim.score("red");
        sim.runMatch("BuzzBot Auto Red", null);
        assertEquals(first, sim.score("red"), "the second match resets the field and scores the same");
        assertTrue(score("red").leave(), "the second match really ran");
    }

    @Test
    void blueAutoIsTheMirrorImage() {
        sim.startAt("Blue Wall").runMatch("BuzzBot Auto Blue", null);
        printScore();
        assertNull(sim.error(), sim.error());
        BioBuzzSnapshot.Score blue = score("blue");
        assertTrue(blue.leave() && blue.autoPark(), "LEAVE + AUTO PARK");
        assertEquals(1, blue.tipsAuto());
        assertEquals(0, score("red").tips());
    }

    @Test
    void teleOpAimsAndShoots() {
        // As if AUTO had just ended at the audience-side shooting spot with 4 POLLEN.
        Pose spot = FieldConstants.START_CELL_SHOT;
        PoseStorage.pose = spot;
        sim.sim.moveRobot(spot.x * 0.0254, spot.y * 0.0254, spot.heading);
        sim.init("BuzzBot TeleOp").start();
        sim.gamepad1(g -> g.right_bumper = true).runFor(2.5);   // aim + spin up
        for (int i = 0; i < 3; i++) {
            sim.gamepad1(g -> g.a = true).runFor(0.1);
            sim.gamepad1(g -> g.a = false).runFor(0.6);
        }
        sim.runFor(4.0); // just over the tipping weight, it starts to swing slowly
        assertNull(sim.error(), sim.error());
        BioBuzzSnapshot.Score red = score("red");
        assertEquals(1, red.tips(), "3 POLLEN on top of the 3 starting NECTAR tip the HIVE");
    }

    /** Robot-frame {forward, left} inches and heading change (deg) while a stick is held. */
    double[] hold(java.util.function.Consumer<com.qualcomm.robotcore.hardware.Gamepad> stick) {
        double x0 = sim.xInches(), y0 = sim.yInches(), h0 = sim.headingDegrees(), h = Math.toRadians(h0);
        sim.gamepad1(stick).runFor(0.4);
        sim.gamepad1(g -> { g.left_stick_x = g.left_stick_y = g.right_stick_x = 0; }).runFor(0.4);
        double dx = sim.xInches() - x0, dy = sim.yInches() - y0;
        double turned = ((sim.headingDegrees() - h0) % 360 + 540) % 360 - 180;
        return new double[]{dx * Math.cos(h) + dy * Math.sin(h), -dx * Math.sin(h) + dy * Math.cos(h), turned};
    }

    @Test
    void theSticksMeanTheSameToTheRobotWhicheverWayItFaces() {
        sim.startAt("Red Wall").init("BuzzBot TeleOp").start();
        sim.runFor(0.3);
        for (double deg : new double[]{0, 90, 180, -90, 35}) {
            // Turned by hand behind the robot's back: its heading estimate is now wrong, which a
            // robot-centric drive doesn't care about.
            sim.sim.moveRobot(-40 * 0.0254, -38 * 0.0254, Math.toRadians(deg));
            sim.runFor(0.3);
            double[] up = hold(g -> g.left_stick_y = -1), down = hold(g -> g.left_stick_y = 1);
            double[] right = hold(g -> g.left_stick_x = 1), left = hold(g -> g.left_stick_x = -1);
            double[] turnRight = hold(g -> g.right_stick_x = 1), turnLeft = hold(g -> g.right_stick_x = -1);
            String at = " (facing " + deg + " deg)";
            assertTrue(up[0] > 6 && Math.abs(up[1]) < 0.25 * up[0], "stick up drives forward" + at + ": " + java.util.Arrays.toString(up));
            assertTrue(down[0] < -6 && Math.abs(down[1]) < 0.25 * -down[0], "stick down drives backward" + at + ": " + java.util.Arrays.toString(down));
            assertTrue(right[1] < -4 && Math.abs(right[0]) < 0.25 * -right[1], "stick right strafes right" + at + ": " + java.util.Arrays.toString(right));
            assertTrue(left[1] > 4 && Math.abs(left[0]) < 0.25 * left[1], "stick left strafes left" + at + ": " + java.util.Arrays.toString(left));
            assertTrue(turnRight[2] < -15, "right stick right turns right (clockwise)" + at + ": " + turnRight[2]);
            assertTrue(turnLeft[2] > 15, "right stick left turns left" + at + ": " + turnLeft[2]);
        }
        assertNull(sim.error(), sim.error());
    }

    @Test
    void theSandboxResetPutsTheRobotBackAndItStillDrivesRight() {
        // Sandbox: the TeleOp keeps running while the field is reset around it.
        sim.startAt("Red Wall").init("BuzzBot TeleOp").start();
        sim.gamepad1(g -> g.left_stick_y = -1).runFor(1.0);
        sim.gamepad1(g -> g.left_stick_y = 0).runFor(0.5);
        assertTrue(sim.xInches() > -40, "drove away from the wall: " + sim.xInches());
        sim.sim.resetFieldWhileDriving();
        sim.runFor(0.3);
        assertEquals(-61.585, sim.xInches(), 1.0, "back at the start");
        double x0 = sim.xInches();
        sim.gamepad1(g -> g.left_stick_y = -1).runFor(0.6);
        sim.gamepad1(g -> g.left_stick_y = 0).runFor(0.4);
        assertTrue(sim.xInches() - x0 > 15, "stick up still drives forward (motor directions kept): " + (sim.xInches() - x0));
        assertNull(sim.error(), sim.error());
    }

    @Test
    void endgameNectarGoesIntoAFlower() {
        // Robot near the far-wall FLOWER, facing away from it, with a red NECTAR behind it.
        double in = 0.0254;
        Pose p = Pose.degrees(-24, 50, -90);
        PoseStorage.pose = p;
        sim.sim.moveRobot(p.x * in, p.y * in, p.heading);
        org.ftctestbed.sim.season.biobuzz.BioBuzzSeason season =
                (org.ftctestbed.sim.season.biobuzz.BioBuzzSeason) sim.sim.season();
        org.ftctestbed.sim.game.GamePiece[] holder = new org.ftctestbed.sim.game.GamePiece[1];
        sim.sim.edit(s -> {
            holder[0] = s.pieces().create(season.nectar("red"));
            s.pieces().placeOnFloor(holder[0], -24 * in, (50 + 10.8) * in, 0, 0);
        });
        org.ftctestbed.sim.game.GamePiece n = holder[0];
        sim.init("BuzzBot TeleOp").start();
        sim.runFor(0.5);
        sim.gamepad1(g -> g.b = true).runFor(0.1);              // close claw
        sim.gamepad1(g -> g.b = false).runFor(0.5);
        sim.gamepad1(g -> g.dpad_up = true).runFor(0.1);        // lift to FLOWER height
        sim.gamepad1(g -> g.dpad_up = false).runFor(2.0);
        // Back up toward the FLOWER (+y: the robot faces -y, so stick down) until the raised
        // claw, 12 in behind the robot's centre, is over its axis - watching the robot, as a
        // driver does.
        double flowerY = ((BioBuzzSnapshot) sim.season()).flowers().get(0).axisY() / in;
        double stopAt = flowerY - 12.0 - 0.5; // it rolls on a little after the stick is let go
        for (int i = 0; i < 60 && sim.yInches() < stopAt; i++) {
            sim.gamepad1(g -> { g.left_stick_y = 0.4f; }).runFor(0.05);
        }
        sim.gamepad1(g -> { g.left_stick_y = 0; }).runFor(0.5);
        sim.gamepad1(g -> g.b = true).runFor(0.1);              // open claw
        sim.gamepad1(g -> g.b = false).runFor(1.0);
        assertNull(sim.error(), sim.error());
        assertEquals(org.ftctestbed.sim.game.GamePiece.State.CONTAINED, n.state(),
                "NECTAR in the FLOWER; robot at " + sim.xInches() + ", " + sim.yInches());
        assertEquals("red", ((BioBuzzSnapshot) sim.season()).flowers().get(0).owner());
    }
}
