package org.ftctestbed.viewer.platform;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;

/**
 * The few things the app asks Windows itself for: the power state, the user's light/dark and
 * transparency settings, a finer timer, and environment variables for the controller library.
 * Every call is a plain user-level one (nothing here needs administrator rights), and every
 * method answers "don't know" instead of failing when it isn't Windows or the call isn't there.
 */
public final class Win32 {
    private Win32() {}

    private interface Kernel32 extends Library {
        boolean GetSystemPowerStatus(Pointer status);

        boolean SetEnvironmentVariableW(WString name, WString value);

        int GetConsoleOutputCP();

        boolean SetConsoleOutputCP(int codePage);

        Pointer GetCurrentProcess();

        boolean SetProcessInformation(Pointer process, int informationClass, Pointer information, int size);
    }

    private interface Winmm extends Library {
        int timeBeginPeriod(int milliseconds);

        int timeEndPeriod(int milliseconds);
    }

    private interface Advapi32 extends Library {
        int RegGetValueW(Pointer key, WString subKey, WString value, int flags, IntByReference type, Pointer data, IntByReference size);
    }

    private static Kernel32 kernel32;
    private static Winmm winmm;
    private static Advapi32 advapi32;
    private static boolean loaded, available;

    private static synchronized boolean load() {
        if (!loaded) {
            loaded = true;
            if (Os.WINDOWS && !Boolean.getBoolean("testbed.noNative")) {
                try {
                    kernel32 = Native.load("kernel32", Kernel32.class);
                    winmm = Native.load("winmm", Winmm.class);
                    advapi32 = Native.load("advapi32", Advapi32.class);
                    available = true;
                } catch (Throwable t) { // no JNA on the classpath, or its library couldn't be unpacked
                    System.err.println("Windows integration is off (" + t + "): the app still runs, with default power and theme settings.");
                }
            }
        }
        return available;
    }

    /** True on Windows when the system calls here can be made. */
    public static boolean available() {
        return load();
    }

    /**
     * What the window needs from Windows, set up once as the app starts (nothing happens on other
     * systems): the 1 ms timer for as long as the physics paces itself against the wall clock,
     * and the controller library's settings.
     */
    public static void setUpApp() {
        if (!Os.WINDOWS) return;
        org.ftctestbed.sim.SimRunner.setPacingListener(pacing -> {
            if (pacing) beginPreciseTimer();
            else endPreciseTimer();
        });
        // SDL only rumbles PS4/PS5 controllers over Bluetooth when asked to (it switches them to
        // their full report mode). The launcher scripts set these; an .exe can't, so it's done here.
        setEnvironmentIfUnset("SDL_JOYSTICK_HIDAPI_PS4_RUMBLE", "1");
        setEnvironmentIfUnset("SDL_JOYSTICK_HIDAPI_PS5_RUMBLE", "1");
    }

    /** What Windows says about the computer's power. */
    public record Power(boolean known, boolean onBattery, boolean batterySaver, int percent) {
        public static final Power UNKNOWN = new Power(false, false, false, -1);
    }

    /** Mains or battery, and whether Battery saver (Energy saver in Windows 11) is on. */
    public static Power power() {
        if (!load()) return Power.UNKNOWN;
        try {
            // SYSTEM_POWER_STATUS: ACLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag (bytes), two DWORDs.
            Memory m = new Memory(12);
            m.clear();
            if (!kernel32.GetSystemPowerStatus(m)) return Power.UNKNOWN;
            int ac = m.getByte(0) & 0xFF, percent = m.getByte(2) & 0xFF, saver = m.getByte(3) & 0xFF;
            return new Power(true, ac == 0, saver == 1, percent == 255 ? -1 : percent);
        } catch (Throwable t) {
            return Power.UNKNOWN;
        }
    }

    private static final long HKEY_CURRENT_USER = (int) 0x80000001; // sign-extended to 64 bits, as in WinReg.h
    private static final int RRF_RT_REG_DWORD = 0x10;

    /** A number from the current user's part of the registry (read only), or fallback if it isn't there. */
    public static int userRegistryInt(String key, String value, int fallback) {
        if (!load()) return fallback;
        try {
            Memory data = new Memory(4);
            IntByReference size = new IntByReference(4);
            // (No JNA class is touched before load() says this is Windows: its native library is never unpacked elsewhere.)
            int rc = advapi32.RegGetValueW(new Pointer(HKEY_CURRENT_USER), new WString(key), new WString(value), RRF_RT_REG_DWORD, null, data, size);
            return rc == 0 ? data.getInt(0) : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static final String PERSONALIZE = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";

    /** Settings > Personalization > Colors: apps in dark mode. */
    public static boolean appsUseDarkTheme() {
        return userRegistryInt(PERSONALIZE, "AppsUseLightTheme", 1) == 0;
    }

    /** Settings > Personalization > Colors: Transparency effects. */
    public static boolean transparencyEffects() {
        return userRegistryInt(PERSONALIZE, "EnableTransparency", 1) != 0;
    }

    private static int timerUsers;

    /**
     * Asks Windows for its 1 ms timer while something needs it (call {@link #endPreciseTimer} when
     * done; calls nest). Without it a wait of 2 ms lasts until the next 15.6 ms tick: the physics
     * would run in bursts of eight steps, and a 60-a-second redraw at about 37. Windows 11 drops
     * the request while a window is minimized or covered, unless the process says not to, which
     * this does too: an OpMode keeps running the same in a minimized window.
     */
    public static synchronized void beginPreciseTimer() {
        if (!load()) return;
        if (timerUsers++ > 0) return;
        try {
            winmm.timeBeginPeriod(1);
            keepTimerWhenHidden(true);
        } catch (Throwable t) {
            timerUsers = 0;
        }
    }

    public static synchronized void endPreciseTimer() {
        if (!load() || timerUsers == 0) return;
        if (--timerUsers > 0) return;
        try {
            winmm.timeEndPeriod(1);
            keepTimerWhenHidden(false);
        } catch (Throwable t) {
            // nothing to undo
        }
    }

    /** True while the 1 ms timer is asked for (tests). */
    static synchronized boolean preciseTimer() {
        return timerUsers > 0;
    }

    private static final int PROCESS_POWER_THROTTLING = 4;                     // ProcessPowerThrottling
    private static final int IGNORE_TIMER_RESOLUTION = 0x4;                    // PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION

    /** Windows 11: honor this process's timer request when its window can't be seen (on: always; off: Windows decides). */
    private static void keepTimerWhenHidden(boolean on) {
        Memory state = new Memory(12); // PROCESS_POWER_THROTTLING_STATE: Version, ControlMask, StateMask
        state.setInt(0, 1);
        state.setInt(4, on ? IGNORE_TIMER_RESOLUTION : 0);
        state.setInt(8, 0);
        kernel32.SetProcessInformation(kernel32.GetCurrentProcess(), PROCESS_POWER_THROTTLING, state, 12); // false on Windows 10: not needed there
    }

    /**
     * Sets an environment variable for this process (Java can't by itself). The controller library
     * reads its settings that way, and an .exe launcher has no way to set them before Java starts.
     * A variable that is already set is left as it is.
     */
    public static boolean setEnvironmentIfUnset(String name, String value) {
        if (!load()) return false;
        try {
            if (System.getenv(name) != null) return true;
            return kernel32.SetEnvironmentVariableW(new WString(name), new WString(value));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Windows, for the command-line tools: prints as UTF-8, so the score lines' ✓ and · arrive as
     * themselves instead of ? in a Command Prompt, PowerShell or Windows Terminal window, through
     * Gradle, and in a file the output is sent to. (Windows consoles otherwise use an older code
     * page that has neither character.) Nothing changes on other systems.
     */
    public static void utf8Output() {
        if (!Os.WINDOWS) return;
        // In a console window only once it has been switched to UTF-8; anywhere else (a pipe, a file) always.
        if (System.console() != null && !consoleUtf8()) return;
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Makes a Command Prompt or PowerShell window show what Java prints as UTF-8. The window's own
     * setting is put back when Java exits. Returns false when there is no console window (output
     * piped to a file or a program) or it couldn't be switched.
     */
    public static boolean consoleUtf8() {
        if (!load()) return false;
        try {
            int before = kernel32.GetConsoleOutputCP();
            if (before == 0) return false; // no console
            if (before == 65001) return true;
            if (!kernel32.SetConsoleOutputCP(65001)) return false;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> kernel32.SetConsoleOutputCP(before), "console code page"));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
