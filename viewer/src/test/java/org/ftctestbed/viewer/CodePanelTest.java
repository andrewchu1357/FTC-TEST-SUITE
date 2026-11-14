package org.ftctestbed.viewer;

import org.ftctestbed.sim.code.RobotCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The Code tab's edit → Save & Run loop, without a window. */
class CodePanelTest {
    @Test
    void saveAndRunStopsAtErrorsThenReloadsWithTheNewCode(@TempDir Path project) throws Exception {
        Path pkg = Files.createDirectories(project.resolve("TeamCode/src/main/java/org/firstinspires/ftc/teamcode"));
        CodeWorkspace ws = new CodeWorkspace(List.of(RobotCode.forProjectTeamCode(project)), project);
        Path file = ws.newFile(pkg, "Lift", CodeWorkspace.Template.TELEOP);
        AtomicReference<CodeSession.State> reloaded = new AtomicReference<>();
        List<String> log = new ArrayList<>();
        CodePanel[] panel = new CodePanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new CodePanel(ws, new CodePanel.Host() {
            @Override public String selectedOpMode() { return "Lift"; }
            @Override public void runNewCode(CodeSession.State state) { reloaded.set(state); }
            @Override public void log(String message) { log.add(message); }
        }));
        CodePanel p = panel[0];
        SwingUtilities.invokeAndWait(() -> p.open(file, 0));
        assertTrue(p.editorText(file).contains("class Lift"));

        // A typo: Save & Run saves the file, compiles, marks the error, and doesn't reload.
        String broken = p.editorText(file).replace("waitForStart();", "waitForStart()");
        SwingUtilities.invokeAndWait(() -> {
            p.type(file, broken);
            p.saveAndRun(false);
        });
        waitFor(() -> p.statusText().contains("error"));
        assertEquals(broken, Files.readString(file), "saved before compiling");
        assertEquals(1, p.problemCount());
        assertNull(reloaded.get(), "no reload with a compile error");

        // Fixed: compiles and asks the window to reload, starting the selected OpMode.
        SwingUtilities.invokeAndWait(() -> {
            p.type(file, broken.replace("waitForStart()", "waitForStart();"));
            p.saveAndRun(false);
        });
        waitFor(() -> reloaded.get() != null);
        assertEquals(0, p.problemCount());
        CodeSession.State st = reloaded.get();
        assertEquals("Lift", st.runOpMode());
        assertEquals(List.of(file.toAbsolutePath().normalize()), st.files());
        assertEquals(file.toAbsolutePath().normalize(), st.selected());

        // The next window's Code tab comes back with the same file open.
        CodePanel[] next = new CodePanel[1];
        SwingUtilities.invokeAndWait(() -> {
            next[0] = new CodePanel(ws, p == null ? null : new CodePanel.Host() {
                @Override public String selectedOpMode() { return null; }
                @Override public void runNewCode(CodeSession.State state) { }
                @Override public void log(String message) { }
            });
            next[0].restore(st);
        });
        assertNotNull(next[0].editorText(file));
    }

    @Test
    void unsavedEditsAreSavedWhenTheWindowCloses(@TempDir Path project) throws Exception {
        Path pkg = Files.createDirectories(project.resolve("TeamCode/src/main/java/org/firstinspires/ftc/teamcode"));
        CodeWorkspace ws = new CodeWorkspace(List.of(RobotCode.forProjectTeamCode(project)), project);
        Path file = ws.newFile(pkg, "Arm", CodeWorkspace.Template.CLASS);
        CodePanel[] panel = new CodePanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new CodePanel(ws, null);
            panel[0].open(file, 0);
            panel[0].type(file, "package org.firstinspires.ftc.teamcode;\n\npublic class Arm { int x; }\n");
        });
        assertTrue(panel[0].hasUnsaved());
        assertEquals(List.of(), panel[0].saveAllQuietly());
        assertTrue(Files.readString(file).contains("int x;"));
        assertFalse(panel[0].hasUnsaved());
    }

    @Test
    void windowsLineEndingsAreKept(@TempDir Path project) throws Exception {
        Path pkg = Files.createDirectories(project.resolve("TeamCode/src/main/java/org/firstinspires/ftc/teamcode"));
        Path file = pkg.resolve("Crlf.java");
        Files.writeString(file, "package org.firstinspires.ftc.teamcode;\r\n\r\npublic class Crlf {\r\n}\r\n");
        CodeWorkspace ws = new CodeWorkspace(List.of(RobotCode.forProjectTeamCode(project)), project);
        CodePanel[] panel = new CodePanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new CodePanel(ws, null);
            panel[0].open(file, 0);
        });
        assertFalse(panel[0].editorText(file).contains("\r"), "edited with plain line breaks");
        assertFalse(panel[0].hasUnsaved(), "opening doesn't count as a change");
        SwingUtilities.invokeAndWait(() -> panel[0].type(file, panel[0].editorText(file).replace("}", "    int x;\n}")));
        panel[0].saveAllQuietly();
        String saved = Files.readString(file);
        assertEquals("package org.firstinspires.ftc.teamcode;\r\n\r\npublic class Crlf {\r\n    int x;\r\n}\r\n", saved);
    }

    static void waitFor(java.util.function.BooleanSupplier cond) throws Exception {
        long end = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < end) {
            boolean[] ok = {false};
            SwingUtilities.invokeAndWait(() -> ok[0] = cond.getAsBoolean());
            if (ok[0]) return;
            Thread.sleep(20);
        }
        fail("timed out");
    }
}
