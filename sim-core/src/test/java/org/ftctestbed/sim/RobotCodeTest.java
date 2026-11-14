package org.ftctestbed.sim;

import org.ftctestbed.sim.code.HardwareScanner;
import org.ftctestbed.sim.code.RobotCode;
import org.ftctestbed.sim.code.RobotConfigGenerator;
import org.ftctestbed.sim.code.SourceFinder;
import org.ftctestbed.sim.config.RobotConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RobotCodeTest {
    static final Path FIELD = Path.of("../config/examples/blank-field.jsonc");

    static final String DRIVE_FORWARD = """
            package org.firstinspires.ftc.teamcode;

            import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
            import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
            import com.qualcomm.robotcore.hardware.DcMotor;
            import com.qualcomm.robotcore.hardware.DcMotorSimple;
            import com.qualcomm.robotcore.hardware.DistanceSensor;
            import com.qualcomm.robotcore.hardware.Servo;

            @Autonomous(name = "Drive Forward", group = "Team")
            public class DriveForward extends LinearOpMode {
                static int runs; // a fresh class loader per launch resets this

                @Override
                public void runOpMode() {
                    DcMotor lf = hardwareMap.get(DcMotor.class, "leftFront");
                    DcMotor rf = hardwareMap.get(DcMotor.class, "rightFront");
                    DcMotor lb = hardwareMap.get(DcMotor.class, "leftBack");
                    DcMotor rb = hardwareMap.dcMotor.get("rightBack");
                    Servo claw = hardwareMap.servo.get("claw");
                    DistanceSensor front = hardwareMap.get(DistanceSensor.class, "frontDistance");
                    // DcMotor ghost = hardwareMap.get(DcMotor.class, "commentedOut");
                    lf.setDirection(DcMotorSimple.Direction.REVERSE);
                    lb.setDirection(DcMotorSimple.Direction.REVERSE);
                    waitForStart();
                    runs++;
                    claw.setPosition(1);
                    for (DcMotor m : new DcMotor[]{lf, rf, lb, rb}) m.setPower(0.5);
                    sleep(1000);
                    for (DcMotor m : new DcMotor[]{lf, rf, lb, rb}) m.setPower(0);
                    telemetry.addData("runs", runs);
                    telemetry.update();
                }
            }
            """;

    /** robots/demo with team code in the usual repository layout and a config generated from it. */
    static Path makePackage(Path dir, String source) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("robots/demo"));
        Path src = Files.createDirectories(pkg.resolve("code/TeamCode/src/main/java/org/firstinspires/ftc/teamcode"));
        Files.writeString(src.resolve("DriveForward.java"), source);
        // The SDK's own module, which must not be compiled.
        Path sdk = Files.createDirectories(pkg.resolve("code/FtcRobotController/src/main/java/org/firstinspires/ftc/robotcontroller"));
        Files.writeString(sdk.resolve("FtcRobotControllerActivity.java"), "package org.firstinspires.ftc.robotcontroller; class X { android.app.Activity a; }");
        return pkg;
    }

    @Test
    void scanGenerateCompileAndRunTeamCode(@TempDir Path dir) throws Exception {
        Path pkg = makePackage(dir, DRIVE_FORWARD);
        List<Path> roots = SourceFinder.roots(pkg.resolve("code"));
        assertEquals(List.of(pkg.resolve("code/TeamCode/src/main/java").toAbsolutePath().normalize()), roots);

        List<HardwareScanner.Device> devices = HardwareScanner.scan(roots);
        assertEquals(List.of("leftFront", "rightFront", "leftBack", "rightBack", "claw", "frontDistance"),
                devices.stream().map(HardwareScanner.Device::name).toList(), "commented-out code is ignored");
        assertEquals(HardwareScanner.Kind.SERVO, devices.get(4).kind());
        assertEquals("org/firstinspires/ftc/teamcode/DriveForward.java:16", devices.get(0).where());

        String generated = RobotConfigGenerator.generate("Demo", devices);
        Path robotFile = pkg.resolve("robot.jsonc");
        Files.writeString(robotFile, generated);
        RobotConfig cfg = RobotConfig.load(robotFile);
        assertTrue(cfg.warnings().isEmpty(), cfg.warnings() + "\n" + generated);
        assertEquals("leftFront", cfg.drivetrain().frontLeft().name());
        assertEquals("rightBack", cfg.drivetrain().backRight().name());
        assertTrue(HardwareScanner.missing(devices, cfg).isEmpty(), HardwareScanner.missing(devices, cfg).toString());

        RobotCode code = RobotCode.forRobot(robotFile, cfg);
        assertNotNull(code);
        RobotCode.Build build = code.build();
        assertTrue(build.ok(), String.join("\n", build.errors()));
        assertEquals(1, build.sourceFiles());
        assertEquals("Drive Forward", build.opModes().opModes().get(0).name());
        assertInstanceOf(org.ftctestbed.sim.code.TeamCodeClassLoader.class, build.opModes().opModes().get(0).type().getClassLoader());
        assertTrue(code.build().upToDate(), "no recompile when nothing changed");

        for (int launch = 0; launch < 2; launch++) {
            try (SimHarness sim = SimHarness.load(robotFile, FIELD)) {
                double x0 = sim.xInches(), y0 = sim.yInches();
                sim.init("Drive Forward").start().runUntilStopped(5);
                assertNull(sim.error());
                assertTrue(Math.hypot(sim.xInches() - x0, sim.yInches() - y0) > 15, "drove forward");
                assertTrue(sim.telemetry().contains("runs : 1"), sim.telemetry().toString());
            }
        }
    }

    @Test
    void compileErrorsPointAtTheLineAndExplain(@TempDir Path dir) throws Exception {
        Path pkg = makePackage(dir, DRIVE_FORWARD
                .replace("import com.qualcomm.robotcore.hardware.Servo;", "import com.qualcomm.robotcore.hardware.Servo;\nimport com.acmerobotics.roadrunner.Pose2d;")
                .replace("runs++;", "runs++; int oops = \"text\";"));
        Path robotFile = pkg.resolve("robot.jsonc");
        Files.writeString(robotFile, RobotConfigGenerator.generate("Demo", List.of()));
        RobotCode.Build build = RobotCode.forRobot(robotFile, RobotConfig.load(robotFile)).build();
        assertFalse(build.ok());
        String all = String.join("\n", build.errors());
        assertTrue(all.contains("DriveForward.java:9: package com.acmerobotics.roadrunner does not exist"), all);
        assertTrue(all.contains("incompatible types"), all);
        assertTrue(all.contains("add the libraries your code uses (Road Runner"), all);
    }

    /**
     * Java 8 language like the FTC SDK, but the library methods Android's compiler backports
     * (List.of...) compile, as they do in Android Studio (Pedro Pathing's tuners use them).
     */
    @Test
    void java8LanguageWithBackportedLibraryMethods(@TempDir Path dir) throws Exception {
        Path pkg = makePackage(dir, DRIVE_FORWARD.replace("runs++;", "runs += java.util.List.of(1, 2).size() - 1;"));
        Path robotFile = pkg.resolve("robot.jsonc");
        Files.writeString(robotFile, RobotConfigGenerator.generate("Demo", List.of()));
        RobotCode.Build build = RobotCode.forRobot(robotFile, RobotConfig.load(robotFile)).build();
        assertTrue(build.ok(), String.join("\n", build.errors()));
        assertEquals(List.of("Drive Forward"), build.opModes().opModes().stream().map(o -> o.name()).toList(), String.join("\n", build.messages()));

        // Newer language features aren't Java 8: that file is left out, as Android Studio would refuse it.
        Path src = pkg.resolve("code/TeamCode/src/main/java/org/firstinspires/ftc/teamcode");
        Files.writeString(src.resolve("NewSyntax.java"), """
                package org.firstinspires.ftc.teamcode;
                class NewSyntax { int f() { var x = 1; return x; } }
                """);
        build = RobotCode.forRobot(robotFile, RobotConfig.load(robotFile)).build();
        assertTrue(build.ok());
        assertTrue(String.join("\n", build.messages()).contains("NewSyntax.java"), String.join("\n", build.messages()));
    }

    @Test
    void filesThatDontCompileAreLeftOut(@TempDir Path dir) throws Exception {
        Path pkg = makePackage(dir, DRIVE_FORWARD);
        Path src = pkg.resolve("code/TeamCode/src/main/java/org/firstinspires/ftc/teamcode");
        Files.writeString(src.resolve("DashboardTuner.java"), """
                package org.firstinspires.ftc.teamcode;
                import com.acmerobotics.dashboard.config.Config;
                import com.acmerobotics.roadrunner.ftc.Actions;
                import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
                import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
                @Config
                @TeleOp(name = "Tuner")
                public class DashboardTuner extends LinearOpMode {
                    public static double KP = 0.1;
                    @Override public void runOpMode() { waitForStart(); }
                }
                """);
        Files.writeString(src.resolve("UsesTuner.java"), """
                package org.firstinspires.ftc.teamcode;
                class UsesTuner { double k = DashboardTuner.KP; }
                """);
        Path robotFile = pkg.resolve("robot.jsonc");
        Files.writeString(robotFile, RobotConfigGenerator.generate("Demo", HardwareScanner.scan(SourceFinder.roots(pkg.resolve("code")))));
        RobotCode code = RobotCode.forRobot(robotFile, RobotConfig.load(robotFile));
        RobotCode.Build build = code.build();
        assertTrue(build.ok(), String.join("\n", build.errors()));
        assertEquals(List.of("Drive Forward"), build.opModes().opModes().stream().map(i -> i.name()).toList());
        String messages = String.join("\n", build.messages());
        assertTrue(messages.contains("Left out 2 of 3 files"), messages);
        assertTrue(messages.contains("DashboardTuner.java:3: package com.acmerobotics.roadrunner.ftc does not exist"), messages);
        assertTrue(messages.contains("UsesTuner.java"), messages);
        assertFalse(code.problems().isEmpty());

        // Built again (the next launch, another agent compile): the classes are reused and the same
        // problems and files left out are reported.
        RobotCode again = RobotCode.forRobot(robotFile, RobotConfig.load(robotFile));
        RobotCode.Build reused = again.build();
        assertTrue(reused.upToDate());
        assertEquals(code.problems(), again.problems());
        assertEquals(build.messages().stream().filter(m -> m.startsWith("Left out") || m.startsWith("  ")).toList(),
                reused.messages().stream().filter(m -> m.startsWith("Left out") || m.startsWith("  ")).toList());
    }

    @Test
    void missingDevicesAreReported(@TempDir Path dir) throws Exception {
        Path pkg = makePackage(dir, DRIVE_FORWARD.replace("\"claw\"", "\"gripper\""));
        List<HardwareScanner.Device> devices = HardwareScanner.scan(SourceFinder.roots(pkg.resolve("code")));
        RobotConfig cfg = RobotConfig.parse(RobotConfigGenerator.generate("x", devices).replace("\"gripper\"", "\"claw\""), "robot");
        assertEquals(List.of("Servo \"gripper\" (org/firstinspires/ftc/teamcode/DriveForward.java:20) is not in the robot config"),
                HardwareScanner.missing(devices, cfg));
    }

    @Test
    void loosePackageFoldersAreFound(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("stuff/org/firstinspires/ftc/teamcode/auto"));
        Files.writeString(src.resolve("A.java"), "package org.firstinspires.ftc.teamcode.auto; class A {}");
        Files.writeString(dir.resolve("stuff/B.java"), "class B {}");
        assertEquals(List.of(dir.resolve("stuff").toAbsolutePath()), SourceFinder.roots(dir.resolve("stuff")));
    }
}
