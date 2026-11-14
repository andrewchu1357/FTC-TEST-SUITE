package org.firstinspires.ftc.teamcode.sim;

import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Road Runner 1.0's quickstart (robots/RoadRunnerQuickstart, unmodified) runs in the simulator:
 * it compiles against the shim, its tuning OpModes register, and once tuned, its own SplineTest
 * drives the robot along the path.
 */
class RoadRunnerTest {
    static final Path PACKAGE = Path.of("robots/RoadRunnerQuickstart");
    static final String FIELD = "{ \"robotStart\": { \"xIn\": -30, \"yIn\": -30, \"headingDeg\": 0 } }";

    private static Path field(Path dir) throws IOException {
        return Files.writeString(dir.resolve("field.jsonc"), FIELD);
    }

    @Test
    void theQuickstartCompilesAndRegistersItsTuningOpModes(@TempDir Path dir) throws Exception {
        try (SimHarness h = SimHarness.load(PACKAGE.resolve("robot.jsonc"), field(dir))) {
            Set<String> names = h.availableOpModes().stream().map(OpModeInfo::name).collect(Collectors.toSet());
            assertTrue(names.containsAll(Set.of("SplineTest", "LocalizationTest", "ManualFeedbackTuner")), names.toString());
            // Registered by TuningOpModes' @OpModeRegistrar method, as on the robot.
            assertTrue(names.containsAll(Set.of("ForwardPushTest", "LateralPushTest", "AngularRampLogger",
                    "ManualFeedforwardTuner", "MecanumMotorDirectionDebugger")), names.toString());
        }
    }

    /** What a team changes in the quickstart after tuning: motor directions, localizer, PARAMS. */
    static void tune(Path code) throws IOException {
        Path drive = code.resolve("org/firstinspires/ftc/teamcode/MecanumDrive.java");
        String s = Files.readString(drive);
        double inPerTick = 1 / (19.89436789 * 25.4); // a goBILDA 4-bar pod on the Pinpoint
        s = replace(s, "//   leftFront.setDirection(DcMotorSimple.Direction.REVERSE);",
                "leftFront.setDirection(DcMotorSimple.Direction.REVERSE); leftBack.setDirection(DcMotorSimple.Direction.REVERSE);");
        s = replace(s, "localizer = new DriveLocalizer(pose);", "localizer = new PinpointLocalizer(hardwareMap, PARAMS.inPerTick, pose);");
        s = replace(s, "public double inPerTick = 1;", "public double inPerTick = " + inPerTick + ";");
        s = replace(s, "public double trackWidthTicks = 0;", "public double trackWidthTicks = " + 27.0 / inPerTick + ";");
        s = replace(s, "public double kS = 0;", "public double kS = 0.7;");
        s = replace(s, "public double kV = 0;", "public double kV = " + 0.19 * inPerTick + ";");
        s = replace(s, "public double kA = 0;", "public double kA = " + 0.02 * inPerTick + ";");
        s = replace(s, "public double axialGain = 0.0;", "public double axialGain = 4.0;");
        s = replace(s, "public double lateralGain = 0.0;", "public double lateralGain = 4.0;");
        s = replace(s, "public double headingGain = 0.0; // shared with turn", "public double headingGain = 6.0;");
        Files.writeString(drive, s);
        Path pinpoint = code.resolve("org/firstinspires/ftc/teamcode/PinpointLocalizer.java");
        String p = Files.readString(pinpoint);
        p = replace(p, "public double parYTicks = 0.0;", "public double parYTicks = " + 3.5 / inPerTick + ";");
        p = replace(p, "public double perpXTicks = 0.0;", "public double perpXTicks = " + -2.0 / inPerTick + ";");
        Files.writeString(pinpoint, p);
    }

    private static String replace(String s, String from, String to) {
        assertTrue(s.contains(from), "the quickstart still has: " + from);
        return s.replace(from, to);
    }

    static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else if (!p.toString().contains("/build/")) Files.copy(p, target);
            }
        }
    }

    @Test
    void tunedItsSplineTestFollowsThePath(@TempDir Path dir) throws Exception {
        Path pkg = dir.resolve("TunedQuickstart");
        copy(PACKAGE, pkg);
        tune(pkg.resolve("code"));
        try (SimHarness h = SimHarness.load(pkg.resolve("robot.jsonc"), field(dir))) {
            // Wait in INIT as a driver would: the Pinpoint zeroes its gyro for 0.25 s after
            // PinpointLocalizer's resetPosAndIMU(), and moving meanwhile makes the heading drift.
            h.init("SplineTest").runFor(1.0).start();
            double t0 = h.seconds();
            h.runFor(8);
            assertNull(h.error(), h.error());
            System.out.printf("SplineTest ended at %.2f, %.2f, %.1f deg (%.1f s)%n", h.xInches(), h.yInches(), h.headingDegrees(), h.seconds() - t0);
            // The path ends at (0, 60) facing 180 deg from where it began: field (-30, 30).
            assertEquals(-30, h.xInches(), 1.5, "x");
            assertEquals(30, h.yInches(), 1.5, "y");
            assertEquals(180, Math.abs(h.headingDegrees()), 3, "heading");
        }
    }

    @Test
    void aConfigGeneratedFromItsCodeHasThePinpointAndOtos() throws Exception {
        String text = org.ftctestbed.sim.code.RobotConfigGenerator.generate("RR", org.ftctestbed.sim.code.HardwareScanner.scan(
                org.ftctestbed.sim.code.SourceFinder.roots(PACKAGE.resolve("code"))));
        org.ftctestbed.sim.config.RobotConfig cfg = org.ftctestbed.sim.config.RobotConfig.parse(text, "generated.jsonc");
        assertEquals("pinpoint", cfg.pinpoints().get(0).name(), text);
        assertEquals("sensor_otos", cfg.otos().get(0).name(), text);
    }
}
