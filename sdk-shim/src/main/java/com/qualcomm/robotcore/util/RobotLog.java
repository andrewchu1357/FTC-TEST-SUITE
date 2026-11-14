package com.qualcomm.robotcore.util;

import org.ftctestbed.shim.SimHooks;

public class RobotLog {
    public static final String TAG = "RobotCore";

    private RobotLog() {}

    private static String fmt(String format, Object... args) {
        return args == null || args.length == 0 ? format : String.format(format, args);
    }

    public static void v(String format, Object... args) { SimHooks.log("V", TAG, fmt(format, args)); }
    public static void d(String format, Object... args) { SimHooks.log("D", TAG, fmt(format, args)); }
    public static void i(String format, Object... args) { SimHooks.log("I", TAG, fmt(format, args)); }
    public static void w(String format, Object... args) { SimHooks.log("W", TAG, fmt(format, args)); }
    public static void e(String format, Object... args) { SimHooks.log("E", TAG, fmt(format, args)); }

    public static void vv(String tag, String format, Object... args) { SimHooks.log("V", tag, fmt(format, args)); }
    public static void dd(String tag, String format, Object... args) { SimHooks.log("D", tag, fmt(format, args)); }
    public static void ii(String tag, String format, Object... args) { SimHooks.log("I", tag, fmt(format, args)); }
    public static void ww(String tag, String format, Object... args) { SimHooks.log("W", tag, fmt(format, args)); }
    public static void ee(String tag, String format, Object... args) { SimHooks.log("E", tag, fmt(format, args)); }

    public static void ee(String tag, Throwable throwable, String format, Object... args) {
        SimHooks.log("E", tag, fmt(format, args) + ": " + throwable);
    }

    public static void logStackTrace(Throwable e) {
        SimHooks.log("E", TAG, e.toString());
        for (StackTraceElement el : e.getStackTrace()) SimHooks.log("E", TAG, "    at " + el);
    }

    /** Shows a warning on the Driver Station (in the simulator: the window's warning line and the log). */
    public static void addGlobalWarningMessage(String message) {
        SimHooks.log("W", "Warning", message);
        SimHooks.warn(message);
    }
}
