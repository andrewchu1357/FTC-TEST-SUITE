package org.ftctestbed.viewer.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DevLaunchTest {
    private static void setTime(Path f, long millis) throws IOException {
        Files.setLastModifiedTime(f, FileTime.fromMillis(millis));
    }

    @Test
    void aBuildIsNeededOnlyWhenASourceIsNewerThanTheLastOne(@TempDir Path dir) throws IOException {
        Path src = Files.createDirectories(dir.resolve("viewer/src/main/java/pkg"));
        Path file = Files.writeString(src.resolve("A.java"), "class A {}");
        Path buildFile = Files.writeString(dir.resolve("build.gradle"), "");
        Path stamp = dir.resolve("build/windows/.built");
        List<Path> watch = List.of(dir.resolve("viewer/src/main"), buildFile, dir.resolve("not/there"));
        long t = System.currentTimeMillis() - 100_000;
        setTime(file, t);
        setTime(buildFile, t);

        assertTrue(DevLaunch.stale(watch, stamp), "never built");
        Files.createDirectories(stamp.getParent());
        Files.createFile(stamp);
        setTime(stamp, t + 10_000);
        assertFalse(DevLaunch.stale(watch, stamp), "built after every source");

        setTime(file, t + 20_000);
        assertTrue(DevLaunch.stale(watch, stamp), "a source file deep in a watched folder changed");
        setTime(stamp, t + 30_000);
        assertFalse(DevLaunch.stale(watch, stamp));
        setTime(buildFile, t + 40_000);
        assertTrue(DevLaunch.stale(watch, stamp), "a watched build file changed");
    }

    @Test
    void itUsesNothingElseOfTheApp() throws IOException {
        // It runs from a folder of its own class files only (see the class comment), so its
        // source may not mention any other class of the simulator.
        Path source = Path.of("viewer/src/main/java/org/ftctestbed/viewer/platform/DevLaunch.java");
        if (!Files.exists(source)) source = Path.of("src/main/java/org/ftctestbed/viewer/platform/DevLaunch.java");
        String code = Files.readString(source);
        for (String line : code.lines().filter(l -> l.startsWith("import ")).toList()) {
            assertFalse(line.contains("org.ftctestbed"), line);
        }
        for (String name : List.of("Os.", "Win32.", "AppHome.")) {
            assertFalse(code.lines().anyMatch(l -> !l.trim().startsWith("*") && !l.trim().startsWith("//") && l.contains(name)),
                    "DevLaunch uses " + name);
        }
    }
}
