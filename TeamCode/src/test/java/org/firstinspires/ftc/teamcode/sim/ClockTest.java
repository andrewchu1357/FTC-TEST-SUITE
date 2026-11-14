package org.firstinspires.ftc.teamcode.sim;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

import org.ftctestbed.shim.ClockAgent;
import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Robot code and libraries (Road Runner, Pedro Pathing, FTCLib) time things with
 * System.nanoTime(), System.currentTimeMillis() and Thread.sleep() directly. They must run on
 * simulated time, or every timer, PID derivative and velocity estimate is wrong whenever the
 * simulator isn't running at exactly real time (tests run as fast as possible).
 */
class ClockTest {
    /** Measures with the JDK's own clock calls, as library code does. */
    public static class ClockProbe extends LinearOpMode {
        static volatile double nanoSeconds, milliSeconds, simSecondsAtEnd;

        @Override
        public void runOpMode() throws InterruptedException {
            waitForStart();
            long n0 = System.nanoTime();
            long m0 = System.currentTimeMillis();
            Thread.sleep(2000);
            nanoSeconds = (System.nanoTime() - n0) / 1e9;
            milliSeconds = (System.currentTimeMillis() - m0) / 1e3;
            simSecondsAtEnd = getRuntime();
            while (opModeIsActive()) idle();
        }
    }

    @Test
    void robotCodeClockCallsRunOnSimulatedTime() throws Exception {
        assertTrue(ClockAgent.isActive(), "the clock agent is loaded for tests (root build.gradle)");
        RobotConfig robot = RobotConfig.parse(Files.readString(Path.of("config/robot.jsonc")), "robot.jsonc");
        FieldConfig field = FieldConfig.parse("{ \"robotStart\": { \"xIn\": 0, \"yIn\": 0, \"headingDeg\": 0 } }", "field");
        OpModeRegistry.Result opModes = new OpModeRegistry.Result(
                List.of(new OpModeInfo("Clock Probe", "", false, null, ClockProbe.class)), List.of());
        try (SimHarness h = SimHarness.of(robot, field, opModes)) {
            h.init("Clock Probe").start().runFor(3);
            assertNull(h.error(), h.error());
        }
        assertEquals(2.0, ClockProbe.nanoSeconds, 0.03, "System.nanoTime() measured simulated time");
        assertEquals(2.0, ClockProbe.milliSeconds, 0.03, "System.currentTimeMillis() measured simulated time");
        assertTrue(ClockProbe.simSecondsAtEnd >= 2.0, "Thread.sleep(2000) slept 2 simulated seconds");
    }

    @Test
    void theJdksOwnClassesAreLeftAlone() {
        // A rewritten JDK class can't see SimTime: macOS's combo box threw NoClassDefFoundError and
        // swallowed every drop-down choice in the window. Robot code is still rewritten.
        assertFalse(org.ftctestbed.shim.ClockAgent.wanted("com/apple/laf/AquaComboBoxUI$1", null, null));
        assertFalse(org.ftctestbed.shim.ClockAgent.wanted("some/jdk/Thing", ClassLoader.getSystemClassLoader().getParent(), null));
        assertTrue(org.ftctestbed.shim.ClockAgent.wanted("org/firstinspires/ftc/teamcode/MyOpMode", ClockTest.class.getClassLoader(), null));
    }
}
