package org.ftctestbed.sim;

import org.ftctestbed.sim.code.RobotPackages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class RobotPackagesTest {
    /** A team repository with TeamCode and the SDK module, like FtcRobotController. */
    static Path teamRepo(Path dir) throws Exception {
        Path repo = Files.createDirectories(dir.resolve("FtcRobotController-master"));
        Path src = Files.createDirectories(repo.resolve("TeamCode/src/main/java/org/firstinspires/ftc/teamcode"));
        Files.writeString(src.resolve("DriveForward.java"), RobotCodeTest.DRIVE_FORWARD);
        Path sdk = Files.createDirectories(repo.resolve("FtcRobotController/src/main/java/org/firstinspires/ftc/robotcontroller"));
        Files.writeString(sdk.resolve("Activity.java"), "package org.firstinspires.ftc.robotcontroller; class Activity extends android.app.Activity {}");
        return repo;
    }

    static Path project(Path dir) throws IOException {
        return Files.createDirectories(dir.resolve("project"));
    }

    @Test
    void linkedCodeIsCompiledFromWhereItIs(@TempDir Path dir) throws Exception {
        Path repo = teamRepo(dir), project = project(dir);
        RobotPackages.Result r = RobotPackages.create(new RobotPackages.Options(project, "Team 1234", repo, true, null));
        assertEquals(project.resolve("robots/Team 1234/robot.jsonc"), r.robotFile());
        assertEquals(1, r.javaFiles());
        assertEquals(6, r.devices().size());
        String cfg = Files.readString(r.robotFile());
        assertTrue(cfg.contains("\"code\": { \"dirs\": [\"../../../FtcRobotController-master\"] }"), cfg);
        assertFalse(Files.exists(r.robotFile().resolveSibling("code")), "linked, not copied");

        // Edit the team code: the next launch runs the new version.
        try (SimHarness sim = SimHarness.load(r.robotFile(), RobotCodeTest.FIELD)) {
            assertEquals("Drive Forward", sim.availableOpModes().get(0).name());
        }
        Path file = repo.resolve("TeamCode/src/main/java/org/firstinspires/ftc/teamcode/DriveForward.java");
        Files.writeString(file, Files.readString(file).replace("\"Drive Forward\"", "\"Drive Forward v2\""));
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        try (SimHarness sim = SimHarness.load(r.robotFile(), RobotCodeTest.FIELD)) {
            assertEquals("Drive Forward v2", sim.availableOpModes().get(0).name());
        }
    }

    @Test
    void zippedCodeIsUnpackedAndRuns(@TempDir Path dir) throws Exception {
        Path repo = teamRepo(dir), project = project(dir);
        Path zip = dir.resolve("code.zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            try (var walk = Files.walk(repo)) {
                for (Path f : walk.filter(Files::isRegularFile).toList()) {
                    z.putNextEntry(new ZipEntry(dir.relativize(f).toString().replace('\\', '/')));
                    Files.copy(f, z);
                    z.closeEntry();
                }
            }
        }
        RobotPackages.Result r = RobotPackages.create(new RobotPackages.Options(project, "Zipped", zip, true, null));
        assertTrue(Files.isDirectory(r.robotFile().resolveSibling("code")));
        assertFalse(Files.readString(r.robotFile()).contains("\"code\""), "copied code needs no code section");
        try (SimHarness sim = SimHarness.load(r.robotFile(), RobotCodeTest.FIELD)) {
            sim.init("Drive Forward").start().runUntilStopped(3);
            assertNull(sim.error());
        }
    }

    @Test
    void copiedCodeWithATemplateReportsMissingDevices(@TempDir Path dir) throws Exception {
        Path repo = teamRepo(dir), project = project(dir);
        RobotPackages.Result r = RobotPackages.create(new RobotPackages.Options(project, "FromBuzzBot", repo, false,
                Path.of("../config/robot.jsonc")));
        assertTrue(Files.exists(r.robotFile().resolveSibling("code/org/firstinspires/ftc/teamcode/DriveForward.java")));
        assertFalse(Files.exists(r.robotFile().resolveSibling("code/org/firstinspires/ftc/robotcontroller")), "SDK module not copied");
        // BuzzBot has the drive motors and a claw, but no distance sensor.
        assertEquals(1, r.report().stream().filter(l -> l.startsWith("CHECK: ")).count(), r.report().toString());
        assertTrue(r.report().stream().anyMatch(l -> l.startsWith("CHECK: DistanceSensor \"frontDistance\"")), r.report().toString());
    }

    @Test
    void badNamesAndExistingPackagesAreRefused(@TempDir Path dir) throws Exception {
        Path project = project(dir);
        assertThrows(IOException.class, () -> RobotPackages.create(new RobotPackages.Options(project, "../escape", null, false,
                Path.of("../config/examples/starter-robot.jsonc"))));
        RobotPackages.create(new RobotPackages.Options(project, "Bot", null, false, Path.of("../config/examples/starter-robot.jsonc")));
        assertThrows(IOException.class, () -> RobotPackages.create(new RobotPackages.Options(project, "Bot", null, false,
                Path.of("../config/examples/starter-robot.jsonc"))));
    }
}
