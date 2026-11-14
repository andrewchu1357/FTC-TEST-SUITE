package org.ftctestbed.sim;

import org.ftctestbed.sim.code.RobotCode;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The project's TeamCode compiled by the simulator, as the Code tab's "Save & Run" uses it. */
class ProjectTeamCodeTest {
    static String opMode(String name) {
        return """
                package org.firstinspires.ftc.teamcode;
                import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
                import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
                @TeleOp(name = "%s")
                public class Drive extends LinearOpMode {
                    static int runs;
                    @Override public void runOpMode() { waitForStart(); runs++; }
                }
                """.formatted(name);
    }

    @Test
    void editsAreCompiledAndErrorsPointAtTheLine(@TempDir Path project) throws Exception {
        Path src = Files.createDirectories(project.resolve("TeamCode/src/main/java/org/firstinspires/ftc/teamcode"));
        Path file = src.resolve("Drive.java");
        Files.writeString(file, opMode("Drive v1"));
        RobotCode code = RobotCode.forProjectTeamCode(project);
        assertNotNull(code);
        assertEquals(project.toAbsolutePath().normalize().resolve("build/sim-teamcode/classes"), code.classesDir(),
                "never Gradle's TeamCode/build output");

        RobotCode.Build b = code.build();
        assertTrue(b.ok(), String.join("\n", b.errors()));
        assertEquals(List.of("Drive v1"), b.opModes().opModes().stream().map(OpModeInfo::name).toList());
        assertTrue(code.problems().isEmpty());

        // An edit: recompiled, new name.
        Files.writeString(file, opMode("Drive v2"));
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2000));
        b = code.build();
        assertFalse(b.upToDate());
        assertEquals(List.of("Drive v2"), b.opModes().opModes().stream().map(OpModeInfo::name).toList());

        // A mistake on line 7 (column 47): reported with its place.
        Files.writeString(file, opMode("Drive v3").replace("runs++;", "runs++ oops;"));
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 4000));
        b = code.build();
        assertFalse(b.ok());
        assertFalse(code.problems().isEmpty());
        RobotCode.Problem p = code.problems().get(0);
        assertEquals(file.toAbsolutePath().normalize(), p.file().toAbsolutePath().normalize());
        assertEquals(7, p.line());
        assertTrue(p.column() > 0);
    }

    @Test
    void aPartnerGetsItsOwnCopyOfTheCompiledCode(@TempDir Path project) throws Exception {
        Path src = Files.createDirectories(project.resolve("TeamCode/src/main/java/org/firstinspires/ftc/teamcode"));
        Files.writeString(src.resolve("Drive.java"), opMode("Drive"));
        RobotCode code = RobotCode.forProjectTeamCode(project);
        RobotCode.Build first = code.build();
        OpModeRegistry.Result copy = RobotCode.projectTeamCodeCopy(code.classesDir());
        assertEquals(List.of("Drive"), copy.opModes().stream().map(OpModeInfo::name).toList());
        Class<?> a = first.opModes().opModes().get(0).type(), b = copy.opModes().get(0).type();
        assertEquals(a.getName(), b.getName());
        assertNotSame(a, b, "two robots never share static fields");
    }

    @Test
    void noTeamCodeFolderMeansNoProjectCode(@TempDir Path project) {
        assertNull(RobotCode.forProjectTeamCode(project));
    }
}
