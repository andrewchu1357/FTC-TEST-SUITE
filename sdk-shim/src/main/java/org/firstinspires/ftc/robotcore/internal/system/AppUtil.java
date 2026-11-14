package org.firstinspires.ftc.robotcore.internal.system;

import android.app.Activity;
import android.app.Application;

import org.ftctestbed.shim.SimActivity;

import java.io.File;

/**
 * Robot Controller app utilities. ROOT_FOLDER is /sdcard on the Control Hub; in the simulator
 * it's the project's sdcard/ folder (the system property testbed.sdcard overrides it), so files
 * robot code saves there (Road Runner tuning data, logs, calibration) end up somewhere you can open.
 */
public class AppUtil {
    public static final File ROOT_FOLDER = new File(System.getProperty("testbed.sdcard", "sdcard")).getAbsoluteFile();
    public static final File FIRST_FOLDER = new File(ROOT_FOLDER, "FIRST");
    public static final File ROBOT_SETTINGS = new File(FIRST_FOLDER, "settings");
    public static final File ROBOT_DATA_DIR = new File(FIRST_FOLDER, "data");
    public static final File LOG_FOLDER = ROOT_FOLDER;
    public static final File MATCH_LOG_FOLDER = new File(FIRST_FOLDER, "matchlogs");
    public static final File CONFIG_FILES_DIR = FIRST_FOLDER;
    public static final File SOUNDS_DIR = new File(FIRST_FOLDER, "sounds");
    public static final File WEBCAM_CALIBRATIONS_DIR = new File(FIRST_FOLDER, "webcamcalibrations");

    private static final AppUtil INSTANCE = new AppUtil();

    public static AppUtil getInstance() {
        return INSTANCE;
    }

    public static Application getDefContext() {
        return SimActivity.APPLICATION;
    }

    public Application getApplication() {
        return SimActivity.APPLICATION;
    }

    public Activity getActivity() {
        return SimActivity.INSTANCE;
    }

    public Activity getRootActivity() {
        return SimActivity.INSTANCE;
    }

    /** A file in the robot settings folder (FIRST/settings), unless the name is already a full path. */
    public File getSettingsFile(String filename) {
        File file = new File(filename);
        if (!file.isAbsolute()) {
            ensureDirectoryExists(ROBOT_SETTINGS);
            file = new File(ROBOT_SETTINGS, filename);
        }
        return file;
    }

    public void ensureDirectoryExists(File directory) {
        ensureDirectoryExists(directory, true);
    }

    public void ensureDirectoryExists(File directory, boolean logFailure) {
        if (!directory.isDirectory() && !directory.mkdirs() && logFailure) {
            com.qualcomm.robotcore.util.RobotLog.ee("AppUtil", "failed to create directory %s", directory);
        }
    }
}
