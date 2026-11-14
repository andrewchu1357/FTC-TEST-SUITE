package org.ftctestbed.sim;

import com.qualcomm.robotcore.hardware.DcMotor;
import org.ftctestbed.sim.code.RobotCode;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.MatchState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Team code that ignores STOP or crashes while loading is ended and reported, not left running. */
class MisbehavingCodeTest {
    static final String HEAD = "package org.firstinspires.ftc.teamcode;\nimport com.qualcomm.robotcore.eventloop.opmode.*;\nimport com.qualcomm.robotcore.hardware.*;\n";

    @Test
    void stuckOpModeIsEndedAndCannotDriveTheNextOne(@TempDir Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("robots/Bad"));
        Path src = Files.createDirectories(pkg.resolve("code/org/firstinspires/ftc/teamcode"));
        Files.copy(Path.of("../config/examples/starter-robot.jsonc"), pkg.resolve("robot.jsonc"));
        Files.writeString(src.resolve("BusyLoop.java"), HEAD + "@TeleOp(name=\"Busy loop\") public class BusyLoop extends LinearOpMode { public void runOpMode() {"
                + " waitForStart(); DcMotor m = hardwareMap.get(DcMotor.class, \"leftFront\"); while (true) { m.setPower(1); } } }");
        Files.writeString(src.resolve("Idle.java"), HEAD + "@TeleOp(name=\"Idle\") public class Idle extends LinearOpMode { public void runOpMode() {"
                + " waitForStart(); while (opModeIsActive()) idle(); } }");
        Files.writeString(src.resolve("StaticBoom.java"), HEAD + "@TeleOp(name=\"Static boom\") public class StaticBoom extends LinearOpMode {"
                + " static int x = 1 / Integer.parseInt(\"0\"); public void runOpMode() {} }");
        RobotConfig robot = RobotConfig.load(pkg.resolve("robot.jsonc"));
        RobotCode.Build build = RobotCode.forRobot(pkg.resolve("robot.jsonc"), robot).build();
        assertTrue(build.ok(), String.join("\n", build.errors()));
        try (SimHarness h = SimHarness.of(robot, FieldConfig.load(Path.of("../config/examples/blank-field.jsonc")), build.opModes())) {
            h.init("Busy loop").start().runFor(0.3).stop();
            assertNotNull(h.opModes.warning(), "warned about ignoring STOP");
            Thread zombie = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("OpMode: Busy loop")).findFirst().orElse(null);
            if (zombie != null) zombie.join(2000);
            assertTrue(zombie == null || !zombie.isAlive(), "the stuck OpMode thread was ended");

            h.init("Idle").start().runFor(0.5);
            assertEquals(0.0, h.sim.hardwareMap.get(DcMotor.class, "leftFront").getPower(), "nothing drives the motor any more");
            h.stop();

            h.init("Static boom");
            assertTrue(h.error().contains("ArithmeticException"), h.error());
            h.stop();

            // A full match whose AUTO ignores STOP: the TELEOP still gets INITed and started.
            h.runMatch("Busy loop", "Idle");
            assertEquals("Idle", h.opModes.current().name());
            assertNull(h.error(), h.error());
            assertEquals(MatchState.Phase.ENDED, h.sim.match().phase());
        }
    }
}
