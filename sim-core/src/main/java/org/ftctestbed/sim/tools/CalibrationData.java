package org.ftctestbed.sim.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A log written by a calibration OpMode (TeamCode's calibration package): "# key=value" lines,
 * a header line, then one CSV row per loop.
 */
public final class CalibrationData {
    public final Path file;
    public final String kind;
    public final Map<String, String> info;
    private final String[] columns;
    private final List<String[]> raw;
    private final double[][] values;

    private CalibrationData(Path file, Map<String, String> info, String[] columns, List<String[]> raw) {
        this.file = file;
        this.info = info;
        this.kind = info.getOrDefault("kind", "");
        this.columns = columns;
        this.raw = raw;
        values = new double[raw.size()][columns.length];
        for (int r = 0; r < raw.size(); r++) {
            String[] row = raw.get(r);
            for (int c = 0; c < columns.length; c++) {
                try {
                    values[r][c] = c < row.length ? Double.parseDouble(row[c]) : Double.NaN;
                } catch (NumberFormatException e) {
                    values[r][c] = Double.NaN;
                }
            }
        }
    }

    public static CalibrationData read(Path file) throws IOException {
        Map<String, String> info = new LinkedHashMap<>();
        String[] columns = null;
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                int eq = line.indexOf('=');
                if (eq > 0) info.put(line.substring(1, eq).trim(), line.substring(eq + 1).trim());
            } else if (columns == null) {
                columns = line.split(",");
            } else {
                rows.add(line.split(","));
            }
        }
        if (columns == null || rows.size() < 10) throw new IOException(file + ": not a calibration log (no data)");
        return new CalibrationData(file, info, columns, rows);
    }

    public int rows() { return values.length; }

    public int column(String name) {
        for (int i = 0; i < columns.length; i++) if (columns[i].equals(name)) return i;
        return -1;
    }

    public double get(int row, int column) { return values[row][column]; }

    public double get(int row, String name) { return values[row][column(name)]; }

    public String text(int row, String name) {
        String[] r = raw.get(row);
        int c = column(name);
        return c >= 0 && c < r.length ? r[c] : "";
    }

    /** A semicolon-separated info value as a list, e.g. motors=leftFront;rightFront. */
    public String[] list(String key) {
        String v = info.get(key);
        return v == null || v.isEmpty() ? new String[0] : v.split(";");
    }
}
