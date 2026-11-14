package org.ftctestbed.viewer.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppHomeTest {
    private static final String[] PROPS = {"testbed.home", "testbed.starter", "testbed.version", "testbed.home.reset", "testbed.log", "testbed.sdcard"};

    @AfterEach
    void clear() {
        for (String p : PROPS) System.clearProperty(p);
    }

    private static Path starter(Path dir) throws IOException {
        Path s = dir.resolve("starter");
        Files.createDirectories(s.resolve("config"));
        Files.createDirectories(s.resolve("TeamCode/src/main/java"));
        Files.writeString(s.resolve("config/robot.jsonc"), "starter robot");
        Files.writeString(s.resolve("TeamCode/src/main/java/Example.java"), "starter example");
        return s;
    }

    @Test
    void withoutAHomeItIsTheFolderTheAppStartedIn() throws IOException {
        assertEquals(Path.of("").toAbsolutePath(), AppHome.prepare(new String[0]));
        assertNull(System.getProperty("testbed.sdcard"), "nothing else changes either");
    }

    @Test
    void theHomeIsMadeAndGetsTheStarterFiles(@TempDir Path dir) throws IOException {
        Path home = dir.resolve("FTC Sim/Workspaces/App");
        System.setProperty("testbed.home", home.toString());
        System.setProperty("testbed.starter", starter(dir).toString());
        System.setProperty("testbed.version", "1");
        assertEquals(home, AppHome.prepare(new String[0]));
        assertEquals("starter robot", Files.readString(home.resolve("config/robot.jsonc")));
        assertEquals("starter example", Files.readString(home.resolve("TeamCode/src/main/java/Example.java")));
        assertEquals(home.resolve("sdcard").toString(), System.getProperty("testbed.sdcard"));
    }

    @Test
    void yourFilesAreNeverReplacedAndDeletedExamplesStayDeleted(@TempDir Path dir) throws IOException {
        Path home = dir.resolve("home"), starter = starter(dir);
        System.setProperty("testbed.home", home.toString());
        System.setProperty("testbed.starter", starter.toString());
        System.setProperty("testbed.version", "1");
        AppHome.prepare(new String[0]);
        Files.writeString(home.resolve("config/robot.jsonc"), "my robot");
        Files.delete(home.resolve("TeamCode/src/main/java/Example.java"));

        AppHome.prepare(new String[0]); // the same version again: nothing is copied
        assertEquals("my robot", Files.readString(home.resolve("config/robot.jsonc")));
        assertFalse(Files.exists(home.resolve("TeamCode/src/main/java/Example.java")));

        // An update brings its new files and the missing ones, and still leaves yours alone.
        Files.writeString(starter.resolve("config/new.jsonc"), "new in version 2");
        System.setProperty("testbed.version", "2");
        AppHome.prepare(new String[0]);
        assertEquals("my robot", Files.readString(home.resolve("config/robot.jsonc")));
        assertEquals("new in version 2", Files.readString(home.resolve("config/new.jsonc")));
        assertTrue(Files.exists(home.resolve("TeamCode/src/main/java/Example.java")));
    }

    @Test
    void theDemoStartsFreshButKeepsCompiledCode(@TempDir Path dir) throws IOException {
        Path home = dir.resolve("demo");
        System.setProperty("testbed.home", home.toString());
        System.setProperty("testbed.starter", starter(dir).toString());
        System.setProperty("testbed.version", "1");
        System.setProperty("testbed.home.reset", "true");
        AppHome.prepare(new String[0]);
        Files.writeString(home.resolve("config/robot.jsonc"), "a visitor's change");
        Files.writeString(home.resolve("left-behind.txt"), "x");
        // Windows won't delete a read-only file as it is (a copied CD, a team's git clone).
        Files.createDirectories(home.resolve("copied"));
        Files.writeString(home.resolve("copied/read-only.txt"), "x");
        assertTrue(home.resolve("copied/read-only.txt").toFile().setReadOnly());
        Files.createDirectories(home.resolve("build/sim-teamcode"));
        Files.writeString(home.resolve("build/sim-teamcode/classes.stamp"), "compiled");

        AppHome.prepare(new String[0]);
        assertEquals("starter robot", Files.readString(home.resolve("config/robot.jsonc")));
        assertFalse(Files.exists(home.resolve("left-behind.txt")));
        assertFalse(Files.exists(home.resolve("copied")), "the read-only file went too");
        assertEquals("compiled", Files.readString(home.resolve("build/sim-teamcode/classes.stamp")));
    }

    @Test
    void homeOnTheCommandLineWinsAndPlaceholdersAreFilledIn(@TempDir Path dir) throws IOException {
        System.setProperty("testbed.home", dir.resolve("from-launcher").toString());
        Path usb = dir.resolve("usb stick");
        assertEquals(usb, AppHome.prepare(new String[]{"--robot", "x.jsonc", "--home", usb.toString()}));
        assertTrue(Files.isDirectory(usb));
        assertFalse(Files.exists(dir.resolve("from-launcher")));

        assertEquals(System.getProperty("user.home") + "/FTC Sim", AppHome.expand("${user.home}/FTC Sim"));
        String path = System.getenv("PATH") != null ? "PATH" : "Path";
        assertEquals(System.getenv(path) + "!", AppHome.expand("${env." + path + "}!"));
        assertEquals("plain", AppHome.expand("plain"));
        assertNull(AppHome.expand(null));
    }

    @Test
    void whatTheAppPrintsGoesToTheLog(@TempDir Path dir) throws IOException {
        java.io.PrintStream out = System.out, err = System.err;
        Path log = dir.resolve("Logs/FTC Test Suite.log");
        try {
            System.setProperty("testbed.log", log.toString());
            AppHome.prepare(new String[0]);
            System.out.println("hello from the app");
            System.err.println("and an error");
            System.out.flush();
        } finally {
            System.out.close(); // the log's stream (Windows won't delete an open file)
            System.setOut(out);
            System.setErr(err);
        }
        String text = Files.readString(log);
        assertTrue(text.startsWith("=== "), text);
        assertTrue(text.contains("hello from the app") && text.contains("and an error"), text);
    }
}
