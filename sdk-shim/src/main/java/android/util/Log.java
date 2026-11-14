package android.util;

import org.ftctestbed.shim.SimHooks;

public final class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    private Log() {}

    public static int println(int priority, String tag, String msg) {
        String level = priority <= VERBOSE ? "V" : priority == DEBUG ? "D" : priority == INFO ? "I" : priority == WARN ? "W" : "E";
        SimHooks.log(level, tag, msg);
        return 0;
    }

    public static int v(String tag, String msg) { SimHooks.log("V", tag, msg); return 0; }
    public static int d(String tag, String msg) { SimHooks.log("D", tag, msg); return 0; }
    public static int i(String tag, String msg) { SimHooks.log("I", tag, msg); return 0; }
    public static int w(String tag, String msg) { SimHooks.log("W", tag, msg); return 0; }
    public static int e(String tag, String msg) { SimHooks.log("E", tag, msg); return 0; }
    public static int e(String tag, String msg, Throwable tr) { SimHooks.log("E", tag, msg + ": " + tr); return 0; }
}
