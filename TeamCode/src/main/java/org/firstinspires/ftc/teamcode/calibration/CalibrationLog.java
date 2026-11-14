package org.firstinspires.ftc.teamcode.calibration;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A CSV log of one calibration run. On the robot it goes to /sdcard/FIRST/calibration/ on the
 * Control Hub; copy it to the computer with Android Studio's Device Explorer or
 * "adb pull /sdcard/FIRST/calibration". In the simulator it goes to logs/calibration/.
 *
 * Lines starting with # describe the run (key=value); then one header line and one row per loop.
 */
public final class CalibrationLog {
    /** Where the last log was written (for tests and telemetry). */
    public static volatile String lastFile;

    private final BufferedWriter out;
    private final String path;
    private int rows;

    public CalibrationLog(String kind) throws IOException {
        File dir;
        String override = System.getProperty("calibration.dir");
        if (override != null) dir = new File(override);
        else if (new File("/sdcard/FIRST").isDirectory()) dir = new File("/sdcard/FIRST/calibration");
        else dir = new File("logs/calibration");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create " + dir);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File file = new File(dir, kind + "-" + stamp + ".csv");
        path = file.getPath();
        out = new BufferedWriter(new FileWriter(file));
        info("kind", kind);
    }

    public String path() { return path; }

    public int rows() { return rows; }

    /** A "# key=value" line describing the run. */
    public void info(String key, Object value) throws IOException {
        out.write("# " + key + "=" + value);
        out.newLine();
    }

    public void header(String... columns) throws IOException {
        out.write(join(columns));
        out.newLine();
    }

    public void row(Object... values) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) b.append(',');
            Object v = values[i];
            if (v instanceof Double || v instanceof Float) b.append(String.format(Locale.US, "%.5f", ((Number) v).doubleValue()));
            else b.append(v);
        }
        out.write(b.toString());
        out.newLine();
        rows++;
    }

    public void close() throws IOException {
        out.close();
        lastFile = path;
    }

    private static String join(String[] parts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) b.append(',');
            b.append(parts[i]);
        }
        return b.toString();
    }
}
