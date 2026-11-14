package org.firstinspires.ftc.teamcode;

import org.ftctestbed.sim.SimHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Example automated tests for the starter-robot examples. Each one runs a real OpMode,
 * unmodified, in the simulator and checks where the robot ends up. Run with ./gradlew test.
 * Uses the starter robot on the blank field (config/examples/); the robot starts at
 * (-36, -60) facing +y.
 */
class ExampleOpModeTest {
    SimHarness sim;

    @BeforeEach
    void setUp() {
        sim = SimHarness.load("config/examples/starter-robot.jsonc", "config/examples/blank-field.jsonc");
    }

    @AfterEach
    void tearDown() {
        sim.close();
    }

    @Test
    void encoderAutoEndsNearTheRightWall() {
        sim.init("Encoder Drive Auto").start().runUntilStopped(30);
        assertNull(sim.error(), sim.error());
        // Drives forward 24, strafes right 24, turns to face +x, and drives at the wall until the
        // distance sensor reads 8 in (x = 55). It stops a few inches past that, as a real robot
        // does: the sensor measures every 33 ms, and braking from 25 in/s takes 2-3 in.
        assertEquals(57.5, sim.xInches(), 2.0, "x");
        assertEquals(-36, sim.yInches(), 3.0, "y");
        assertEquals(0, sim.headingDegrees(), 3.0, "heading");
    }

    @Test
    void timedSquareReturnsNearStart() {
        sim.init("Iterative Timed Square").start().runFor(8);
        assertNull(sim.error(), sim.error());
        assertEquals(-36, sim.xInches(), 2.0);
        assertEquals(-60, sim.yInches(), 2.0);
    }

    @Test
    void teleOpDrivesForwardWithLeftStick() {
        sim.init("Mecanum TeleOp").start();
        sim.gamepad1(g -> g.left_stick_y = -1).runFor(1.0);
        sim.gamepad1(g -> g.left_stick_y = 0).runFor(1.0);
        assertNull(sim.error(), sim.error());
        assertTrue(sim.yInches() > -60 + 30, "drove forward: y = " + sim.yInches());
        // The four setPower calls reach the hub ~2 ms apart, so a full-power launch drifts
        // sideways a little, as on a real robot.
        assertEquals(-36, sim.xInches(), 2.0);
        assertEquals(90, sim.headingDegrees(), 2.0);
    }

    @Test
    void teleOpLiftPresetRaisesLift() {
        sim.init("Mecanum TeleOp").start();
        sim.gamepad1(g -> g.y = true).runFor(0.1);
        sim.gamepad1(g -> g.y = false).runFor(2.0);
        assertEquals(2200, telemetryNumber("Lift"), 30, sim.telemetry().toString());
    }

    /** First number on the telemetry line with this caption. */
    private double telemetryNumber(String caption) {
        for (String line : sim.telemetry()) {
            if (line.startsWith(caption + " : ")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+(\\.\\d+)?").matcher(line.substring(caption.length()));
                if (m.find()) return Double.parseDouble(m.group());
            }
        }
        throw new AssertionError("No telemetry line \"" + caption + "\" in " + sim.telemetry());
    }

    @Test
    void ftcLibTeleOpStrafesRight() {
        sim.init("FTCLib TeleOp").start();
        sim.gamepad1(g -> g.left_stick_x = 1).runFor(1.0);
        assertNull(sim.error(), sim.error());
        assertTrue(sim.xInches() > -36 + 20, "strafed right: x = " + sim.xInches());
        assertEquals(-60, sim.yInches(), 2.0);
    }

    @Test
    void ftcLibCommandAutoFinishesWithLiftDown() {
        sim.init("FTCLib Command Auto").start().runFor(5);
        assertNull(sim.error(), sim.error());
        assertEquals(0, telemetryNumber("Lift"), 20, sim.telemetry().toString());
    }
}
