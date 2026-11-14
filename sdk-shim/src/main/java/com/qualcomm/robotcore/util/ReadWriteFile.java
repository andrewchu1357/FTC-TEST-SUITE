package com.qualcomm.robotcore.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * Reading and writing small files (calibration data, a saved pose). Files under the Control
 * Hub's /sdcard go to the simulator's sdcard/ folder (see AppUtil).
 */
public class ReadWriteFile {
    public static final String TAG = "ReadWriteFile";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public ReadWriteFile() {}

    public static String readFileOrThrow(File file) throws IOException {
        return new String(readFileBytesOrThrow(file), UTF8);
    }

    /** The file's text, or "" if it can't be read. */
    public static String readFile(File file) {
        try {
            return readFileOrThrow(file);
        } catch (IOException e) {
            RobotLog.ee(TAG, e, "error reading %s", file);
            return "";
        }
    }

    public static byte[] readFileBytesOrThrow(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    public static byte[] readFileBytes(File file) {
        try {
            return readFileBytesOrThrow(file);
        } catch (IOException e) {
            RobotLog.ee(TAG, e, "error reading %s", file);
            return new byte[0];
        }
    }

    public static void writeFileOrThrow(File file, String content) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("can't create " + parent);
        Files.write(file.toPath(), content.getBytes(UTF8));
    }

    public static void writeFileOrThrow(File directory, String fileName, String content) throws IOException {
        writeFileOrThrow(new File(directory, fileName), content);
    }

    public static void writeFile(File file, String content) {
        try {
            writeFileOrThrow(file, content);
        } catch (IOException e) {
            RobotLog.ee(TAG, e, "error writing %s", file);
        }
    }

    public static void writeFile(File directory, String fileName, String content) {
        writeFile(new File(directory, fileName), content);
    }

    public static void updateFileRequiringCommit(File file, String content) {
        writeFile(file, content);
    }

    /** On the robot these make sure the file reached the flash; files on a computer already have. */
    public static void ensureChangesAreCommitted(File file) {}

    public static void ensureAllChangesAreCommitted(File directory) {}
}
