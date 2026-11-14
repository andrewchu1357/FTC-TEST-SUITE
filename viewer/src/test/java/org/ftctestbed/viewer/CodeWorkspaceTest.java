package org.ftctestbed.viewer;

import org.ftctestbed.sim.code.RobotCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeWorkspaceTest {
    @Test
    void newFilesFromEveryTemplateCompileAndShowUpAsOpModes(@TempDir Path project) throws Exception {
        Path pkg = Files.createDirectories(project.resolve("TeamCode/src/main/java/org/firstinspires/ftc/teamcode"));
        CodeWorkspace ws = new CodeWorkspace(List.of(RobotCode.forProjectTeamCode(project)), project);
        assertEquals(1, ws.roots().size());
        assertEquals("TeamCode/src/main/java", ws.roots().get(0).label());
        assertEquals("org.firstinspires.ftc.teamcode", ws.packageOf(pkg));

        ws.newFile(pkg, "LiftTeleOp", CodeWorkspace.Template.TELEOP);
        ws.newFile(pkg, "ParkAuto", CodeWorkspace.Template.AUTONOMOUS);
        ws.newFile(pkg, "ArmTest", CodeWorkspace.Template.ITERATIVE);
        ws.newFile(pkg.resolve("util"), "Helpers", CodeWorkspace.Template.CLASS);
        assertTrue(Files.readString(pkg.resolve("util/Helpers.java")).startsWith("package org.firstinspires.ftc.teamcode.util;"));
        assertTrue(Files.readString(pkg.resolve("LiftTeleOp.java")).contains("@TeleOp(name = \"Lift Tele Op\""));

        CodeWorkspace.Check c = ws.compile();
        assertTrue(c.ok(), c.problems() + " " + c.messages());
        RobotCode.Build b = RobotCode.forProjectTeamCode(project).build();
        assertEquals(List.of("Arm Test", "Lift Tele Op", "Park Auto"), b.opModes().opModes().stream().map(i -> i.name()).sorted().toList());
    }

    @Test
    void compileErrorsComeBackAsProblems(@TempDir Path project) throws Exception {
        Path pkg = Files.createDirectories(project.resolve("TeamCode/src/main/java/org/firstinspires/ftc/teamcode"));
        CodeWorkspace ws = new CodeWorkspace(List.of(RobotCode.forProjectTeamCode(project)), project);
        Path f = ws.newFile(pkg, "Broken", CodeWorkspace.Template.TELEOP);
        Files.writeString(f, Files.readString(f).replace("waitForStart();", "waitForStart()"));
        CodeWorkspace.Check c = ws.compile();
        assertFalse(c.ok());
        assertEquals(1, c.problems().size());
        assertEquals(f.toAbsolutePath().normalize(), c.problems().get(0).file().toAbsolutePath().normalize());
        assertTrue(c.problems().get(0).message().contains("';' expected"), c.problems().get(0).message());
    }

    @Test
    void badNamesAndFoldersOutsideTheCodeAreRefused(@TempDir Path project) throws Exception {
        Path pkg = Files.createDirectories(project.resolve("TeamCode/src/main/java/org/firstinspires/ftc/teamcode"));
        CodeWorkspace ws = new CodeWorkspace(List.of(RobotCode.forProjectTeamCode(project)), project);
        assertThrows(IOException.class, () -> ws.newFile(pkg, "2Fast", CodeWorkspace.Template.CLASS));
        assertThrows(IOException.class, () -> ws.newFile(pkg, "Bad Name", CodeWorkspace.Template.CLASS));
        assertThrows(IOException.class, () -> ws.newFile(project, "Outside", CodeWorkspace.Template.CLASS));
        ws.newFile(pkg, "Once", CodeWorkspace.Template.CLASS);
        assertThrows(IOException.class, () -> ws.newFile(pkg, "Once", CodeWorkspace.Template.CLASS), "won't overwrite");
    }

    @Test
    void aRobotPackageShowsItsOwnCodeAndThePartnersToo() {
        Path project = Path.of("").toAbsolutePath();
        Path example = project.resolve("robots/ExampleBot/robot.jsonc"), buzz = project.resolve("config/robot.jsonc");
        CodeWorkspace ws = CodeWorkspace.forRobots(project, example, org.ftctestbed.sim.config.RobotConfig.load(example),
                buzz, org.ftctestbed.sim.config.RobotConfig.load(buzz));
        assertEquals(List.of("robots/ExampleBot/code", "TeamCode/src/main/java"), ws.roots().stream().map(CodeWorkspace.Root::label).toList());
        assertEquals("org.firstinspires.ftc.teamcode", ws.packageOf(project.resolve("robots/ExampleBot/code/org/firstinspires/ftc/teamcode")));
    }

    @Test
    void theProjectWorkspaceIsTheTeamCodeModule() {
        Path project = Path.of("").toAbsolutePath();
        CodeWorkspace ws = new CodeWorkspace(List.of(RobotCode.forProjectTeamCode(project)), project);
        assertEquals("TeamCode/src/main/java", ws.roots().get(0).label());
        assertNotNull(ws.rootOf(project.resolve("TeamCode/src/main/java/org/firstinspires/ftc/teamcode/buzzbot/BuzzBot.java")));
        assertNull(ws.rootOf(project.resolve("README.md")));
    }
}
