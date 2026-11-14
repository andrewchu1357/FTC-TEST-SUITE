package org.ftctestbed.viewer.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

class Win32Test {
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void elsewhereEverythingAnswersDontKnow() {
        assertFalse(Win32.available());
        assertEquals(Win32.Power.UNKNOWN, Win32.power());
        assertFalse(Win32.appsUseDarkTheme());
        assertTrue(Win32.transparencyEffects());
        assertEquals(7, Win32.userRegistryInt("Software\\Nothing", "Here", 7));
        Win32.beginPreciseTimer();
        assertFalse(Win32.preciseTimer());
        Win32.endPreciseTimer();
        assertFalse(Win32.setEnvironmentIfUnset("FTC_SIM_TEST", "1"));
        assertFalse(Win32.consoleUtf8());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsAnswers() {
        assertTrue(Win32.available(), "JNA loads on Windows");
        Win32.Power p = Win32.power();
        assertTrue(p.known());
        assertTrue(p.percent() >= -1 && p.percent() <= 100, "battery percent " + p.percent());
        // A value that isn't there comes back as the fallback; the real settings read without failing.
        assertEquals(7, Win32.userRegistryInt("Software\\FTC Test Suite\\Nothing", "Here", 7));
        Win32.appsUseDarkTheme();
        Win32.transparencyEffects();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void anEnvironmentVariableIsSetOnlyWhenItIsntAlready() {
        assertTrue(Win32.setEnvironmentIfUnset("FTC_SIM_TEST_" + System.nanoTime(), "1"));
        assertTrue(Win32.setEnvironmentIfUnset("PATH", "nothing"), "already set: left alone");
        assertNotEquals("nothing", System.getenv("PATH"));
    }

    private static double parkMillis(int times) {
        long t0 = System.nanoTime();
        for (int i = 0; i < times; i++) LockSupport.parkNanos(2_000_000L);
        return (System.nanoTime() - t0) / 1e6 / times;
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void thePreciseTimerMakesTheLoopsTwoMillisecondWaitShort() {
        Win32.beginPreciseTimer();
        Win32.beginPreciseTimer(); // nests
        try {
            assertTrue(Win32.preciseTimer());
            // Windows' usual timer makes this 15.6 ms; with the 1 ms timer it is about 3.
            double ms = parkMillis(60);
            assertTrue(ms < 8, "a 2 ms wait took " + ms + " ms with the precise timer on");
        } finally {
            Win32.endPreciseTimer();
            assertTrue(Win32.preciseTimer(), "still wanted by the first caller");
            Win32.endPreciseTimer();
        }
        assertFalse(Win32.preciseTimer());
        Win32.endPreciseTimer(); // one too many does no harm
        assertFalse(Win32.preciseTimer());
    }
}
