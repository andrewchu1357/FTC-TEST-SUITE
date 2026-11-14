package org.ftctestbed.sim.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The field, loaded from config/field.jsonc. Field coordinates: origin at the field center,
 * +x to the right and +y up as drawn in the viewer, heading 0 = +x, counter-clockwise positive.
 */
public record FieldConfig(
        String name,
        double size,
        double tile,
        double wallFriction,
        double wallRestitution,
        Pose robotStart,
        List<StartPosition> startPositions,
        String season,
        Map<String, Object> seasonSettings,
        List<Shape> obstacles,
        List<Shape> gamePieces,
        List<String> warnings) {

    public record Pose(double x, double y, double heading) {}

    /** A named starting spot. alliance is "red", "blue" or null. */
    public record StartPosition(String name, String alliance, Pose pose) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** shape: "rect" (width x height), "circle" (diameter), or "polygon" (points, field coords). */
    public record Shape(String name, String shape, double x, double y, double heading, double width, double height,
                        double diameter, List<double[]> points, double mass, double friction, double restitution,
                        double floorFriction, int color) {}

    public static FieldConfig load(Path file) {
        try {
            return parse(Files.readString(file), file.getFileName().toString());
        } catch (java.nio.file.NoSuchFileException e) {
            throw new ConfigException(file + " not found");
        } catch (java.io.IOException e) {
            throw new ConfigException("Cannot read " + file + ": " + e.getMessage());
        }
    }

    public static FieldConfig parse(String text, String sourceName) {
        ConfigNode root = ConfigNode.fromObject(sourceName, Jsonc.parse(text, sourceName));
        double in = 0.0254;
        List<StartPosition> starts = new ArrayList<>();
        for (ConfigNode sp : root.children("startPositions")) {
            String alliance = sp.getString("alliance", null);
            if (alliance != null && !alliance.equals("red") && !alliance.equals("blue")) {
                throw new ConfigException(sp.path() + ".alliance: must be \"red\" or \"blue\"");
            }
            starts.add(new StartPosition(sp.requireString("name"), alliance,
                    new Pose(sp.length("x", 0), sp.length("y", 0), sp.angle("heading", 0))));
        }
        ConfigNode start = root.child("robotStart");
        Pose robotStart = start != null
                ? new Pose(start.length("x", 0), start.length("y", 0), start.angle("heading", Math.PI / 2))
                : !starts.isEmpty() ? starts.get(0).pose() : new Pose(0, 0, Math.PI / 2);
        if (starts.isEmpty()) starts.add(new StartPosition("Default", null, robotStart));

        String season = root.getString("season", null);
        Map<String, Object> seasonSettings = new LinkedHashMap<>();
        if (season != null) {
            Object raw = root.getRaw(season);
            if (raw instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) seasonSettings.put(String.valueOf(e.getKey()), e.getValue());
            }
        }

        List<Shape> obstacles = new ArrayList<>();
        for (ConfigNode o : root.children("obstacles")) obstacles.add(shape(o, 0, 0x6E7781));
        List<Shape> pieces = new ArrayList<>();
        for (ConfigNode p : root.children("gamePieces")) pieces.add(shape(p, 0.1, 0xF2C200));

        List<String> warnings = new ArrayList<>();
        FieldConfig cfg = new FieldConfig(
                root.getString("name", "FTC Field"),
                root.length("size", 144 * in),
                root.length("tile", 24 * in),
                root.getDouble("wallFriction", 0.3),
                root.getDouble("wallRestitution", 0.1),
                robotStart, starts, season, seasonSettings, obstacles, pieces, warnings);
        root.collectUnused(warnings);
        return cfg;
    }

    private static Shape shape(ConfigNode n, double defaultMass, int defaultColor) {
        String shape = n.getString("shape", "rect");
        List<double[]> points = new ArrayList<>();
        if (shape.equals("polygon")) {
            Object raw = n.getRaw("pointsIn");
            if (!(raw instanceof List<?> list) || list.size() < 3) {
                throw new ConfigException(n.path() + ".pointsIn: polygon needs at least 3 [x, y] points (inches)");
            }
            for (Object p : list) {
                if (!(p instanceof List<?> xy) || xy.size() != 2) throw new ConfigException(n.path() + ".pointsIn: each point must be [x, y]");
                points.add(new double[]{((Double) xy.get(0)) * 0.0254, ((Double) xy.get(1)) * 0.0254});
            }
        } else if (!shape.equals("rect") && !shape.equals("circle")) {
            throw new ConfigException(n.path() + ".shape: must be \"rect\", \"circle\" or \"polygon\"");
        }
        return new Shape(n.getString("name", ""), shape, n.length("x", 0), n.length("y", 0), n.angle("heading", 0),
                n.length("width", 0.1), n.length("height", 0.1), n.length("diameter", 0.1), points,
                n.mass("mass", defaultMass), n.getDouble("friction", 0.4), n.getDouble("restitution", 0.2),
                n.getDouble("floorFriction", 0.5), parseColor(n.getString("color", null), defaultColor));
    }

    /** Colors are "#RRGGBB" strings, stored as 0xRRGGBB. */
    private static int parseColor(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.replace("#", ""), 16);
        } catch (NumberFormatException e) {
            throw new ConfigException("Invalid color \"" + s + "\" (use \"#RRGGBB\")");
        }
    }
}
