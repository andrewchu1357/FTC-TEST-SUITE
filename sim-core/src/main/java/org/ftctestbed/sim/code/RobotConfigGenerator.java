package org.ftctestbed.sim.code;

import org.ftctestbed.sim.config.Jsonc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a first robot.jsonc for team code: every device the code asks the hardware map for,
 * with the four drive motors picked out by name, and catalog parts as placeholders. The team then
 * fixes sizes, motor types and mechanisms to match the real robot.
 */
public final class RobotConfigGenerator {
    private static final String[] CORNERS = {"frontLeft", "frontRight", "backLeft", "backRight"};

    private RobotConfigGenerator() {}

    public static String generate(String robotName, List<HardwareScanner.Device> devices) {
        List<HardwareScanner.Device> motors = new ArrayList<>();
        Map<String, HardwareScanner.Device> drive = new LinkedHashMap<>();
        for (HardwareScanner.Device d : devices) {
            if (d.kind() != HardwareScanner.Kind.MOTOR) continue;
            String corner = corner(d.name());
            if (corner != null && !drive.containsKey(corner)) drive.put(corner, d);
            else motors.add(d);
        }
        StringBuilder s = new StringBuilder();
        s.append("// =============================================================================\n");
        s.append("//  ").append(robotName).append(" - generated from the team code's hardwareMap calls.\n");
        s.append("//  Check everything marked TODO against the real robot: size, mass, motor and servo\n");
        s.append("//  types (see the Parts tab), and turn lift/arm motors into mechanisms.\n");
        s.append("//  Units go on the key (\"widthIn\", \"massLbs\"...). Robot frame: x forward, y left.\n");
        s.append("// =============================================================================\n");
        s.append("{\n");
        s.append("  \"lengthIn\": 18,   // TODO\n");
        s.append("  \"widthIn\": 18,    // TODO\n");
        s.append("  \"heightIn\": 16,   // TODO\n");
        s.append("  \"massLbs\": 30,    // TODO weigh the robot\n\n");
        s.append("  \"drivetrain\": {\n");
        s.append("    \"type\": \"mecanum\",\n");
        s.append("    \"wheel\": \"goBILDA-GripForce-104mm-mecanum\",   // TODO\n");
        s.append("    \"trackWidthIn\": 14.5,   // TODO distance between left and right wheel centers\n");
        s.append("    \"wheelbaseIn\": 13.0,    // TODO distance between front and back wheel centers\n");
        s.append("    \"motorType\": \"goBILDA-5203-312\",   // TODO\n");
        s.append("    \"motors\": {\n");
        for (int i = 0; i < 4; i++) {
            String c = CORNERS[i];
            HardwareScanner.Device d = drive.get(c);
            String name = d != null ? d.name() : c;
            s.append("      \"").append(c).append("\": { \"name\": ").append(Jsonc.quote(name));
            if (c.endsWith("Left")) s.append(", \"mountedReversed\": true");
            s.append(" }").append(i < 3 ? "," : "");
            if (d == null) s.append("   // TODO no drive motor for this corner found in the code");
            s.append('\n');
        }
        s.append("    }\n  },\n\n");

        s.append("  \"motors\": [");
        list(s, motors, d -> "{ \"name\": " + Jsonc.quote(d.name()) + ", \"type\": \"goBILDA-5203-312\" }   // TODO type; " + d.where());
        s.append(",\n\n");
        s.append("  // A motor without a mechanism just spins freely. For a lift or arm add e.g.\n");
        s.append("  // { \"name\": \"lift\", \"type\": \"slide\", \"motors\": [\"lift\"], \"kit\": \"goBILDA-Viper-4stage-336mm\", \"massLbs\": 2 }\n");
        s.append("  \"mechanisms\": [],\n\n");

        s.append("  \"servos\": [");
        list(s, of(devices, HardwareScanner.Kind.SERVO), d -> "{ \"name\": " + Jsonc.quote(d.name()) + ", \"type\": \"goBILDA-2000-25-3\" }   // TODO type; " + d.where());
        s.append(",\n");
        s.append("  \"crServos\": [");
        list(s, of(devices, HardwareScanner.Kind.CR_SERVO), d -> "{ \"name\": " + Jsonc.quote(d.name()) + ", \"type\": \"goBILDA-2000-25-3\" }   // TODO type; " + d.where());
        s.append(",\n");
        s.append("  \"distanceSensors\": [");
        list(s, of(devices, HardwareScanner.Kind.DISTANCE), d -> "{ \"name\": " + Jsonc.quote(d.name())
                + ", \"type\": \"" + (d.javaType().equals("AndyMarkTOF") ? "AndyMark-Distance-Sensor" : "REV-2m-Distance-Sensor")
                + "\", \"xIn\": 9, \"yIn\": 0, \"headingDeg\": 0 }   // TODO position; " + d.where());
        s.append(",\n");
        s.append("  \"colorSensors\": [");
        list(s, of(devices, HardwareScanner.Kind.COLOR), d -> "{ \"name\": " + Jsonc.quote(d.name())
                + (d.javaType().equals("AndyMarkColorSensor") ? ", \"type\": \"AndyMark-Color-Sensor\"" : "") + " }   // " + d.where());
        s.append(",\n");
        List<HardwareScanner.Device> limelights = of(devices, HardwareScanner.Kind.LIMELIGHT);
        if (!limelights.isEmpty()) {
            s.append("  // TODO where the Limelight is (xIn forward, yIn left, zIn up from the floor) and how it's tilted\n");
            s.append("  // (tiltUpDeg); pipelines default to 0 yellow, 1 red, 2 blue, 3 AprilTags.\n");
            s.append("  \"limelights\": [");
            list(s, limelights, d -> "{ \"name\": " + Jsonc.quote(d.name()) + ", \"zIn\": 10, \"tiltUpDeg\": 0 }   // " + d.where());
            s.append(",\n");
        }
        List<HardwareScanner.Device> webcams = of(devices, HardwareScanner.Kind.WEBCAM);
        if (!webcams.isEmpty()) {
            s.append("  // TODO which webcam (model: C920, C270, C310, goBILDA, LifeCam or generic), where it is\n");
            s.append("  // (xIn forward, yIn left, zIn up from the floor) and how it's tilted (tiltUpDeg).\n");
            s.append("  \"webcams\": [");
            list(s, webcams, d -> "{ \"name\": " + Jsonc.quote(d.name()) + ", \"model\": \"C920\", \"zIn\": 8, \"tiltUpDeg\": 0 }   // " + d.where());
            s.append(",\n");
        }
        List<HardwareScanner.Device> lights = of(devices, HardwareScanner.Kind.LIGHT);
        if (!lights.isEmpty()) {
            s.append("  \"lights\": [");
            list(s, lights, d -> "{ \"name\": " + Jsonc.quote(d.name()) + ", \"type\": \""
                    + (d.javaType().equals("RevBlinkinLedDriver") ? "blinkin" : d.javaType().equals("SparkFunLEDStick") ? "ledStick" : "led")
                    + "\" }   // " + d.where());
            s.append(",\n");
        }

        List<HardwareScanner.Device> touch = of(devices, HardwareScanner.Kind.TOUCH);
        if (!touch.isEmpty()) {
            s.append("  // Touch sensors / limit switches need the mechanism they detect, e.g.\n");
            s.append("  // { \"name\": \"liftDown\", \"mechanism\": \"lift\", \"pressedBelow\": 0.25 }\n");
            for (HardwareScanner.Device d : touch) s.append("  //   ").append(d.javaType()).append(" \"").append(d.name()).append("\" (").append(d.where()).append(")\n");
        }
        s.append("  \"limitSwitches\": [],\n\n");

        List<HardwareScanner.Device> imu = of(devices, HardwareScanner.Kind.IMU);
        List<HardwareScanner.Device> pinpoints = of(devices, HardwareScanner.Kind.PINPOINT);
        List<HardwareScanner.Device> otos = of(devices, HardwareScanner.Kind.OTOS);
        List<HardwareScanner.Device> octoquads = of(devices, HardwareScanner.Kind.OCTOQUAD);
        boolean more = !pinpoints.isEmpty() || !otos.isEmpty() || !octoquads.isEmpty();
        boolean andyMarkImu = !imu.isEmpty() && imu.get(0).javaType().equals("AndyMarkIMU");
        s.append("  \"imu\": { \"name\": ").append(Jsonc.quote(imu.isEmpty() ? "imu" : imu.get(0).name()))
                .append(andyMarkImu ? ", \"chip\": \"AndyMark\", \"logo\": \"UP\", \"i2cPort\": \"FORWARD\" }" : ", \"logo\": \"UP\", \"usb\": \"FORWARD\" }")
                .append(more ? "," : "").append(andyMarkImu ? "   // TODO how the IMU board is mounted\n" : "   // TODO the hub's mounting\n");
        if (!pinpoints.isEmpty()) {
            s.append("\n  // TODO where the Pinpoint's pods are (xPod tracks forward, yPod sideways), from the robot's center\n");
            s.append("  \"pinpoints\": [");
            list(s, pinpoints, d -> "{ \"name\": " + Jsonc.quote(d.name())
                    + ", \"xPod\": { \"type\": \"goBILDA-4-Bar-Odometry-Pod\", \"xIn\": 0, \"yIn\": 0 }"
                    + ", \"yPod\": { \"type\": \"goBILDA-4-Bar-Odometry-Pod\", \"xIn\": 0, \"yIn\": 0 } }   // " + d.where());
            s.append(otos.isEmpty() ? "\n" : ",\n");
        }
        if (!otos.isEmpty()) {
            s.append("\n  // TODO where the OTOS sits (and how it's turned, mountHeadingDeg); heightMm should be 10\n");
            s.append("  \"otos\": [");
            list(s, otos, d -> "{ \"name\": " + Jsonc.quote(d.name()) + ", \"xIn\": 0, \"yIn\": 0, \"mountHeadingDeg\": 0, \"heightMm\": 10 }   // " + d.where());
            s.append(octoquads.isEmpty() ? "\n" : ",\n");
        }
        if (!octoquads.isEmpty()) {
            s.append("\n  // TODO the dead wheels plugged into each OctoQuad channel and where they are (direction: forward or lateral)\n");
            s.append("  \"octoquads\": [");
            list(s, octoquads, d -> "{ \"name\": " + Jsonc.quote(d.name()) + ", \"channels\": ["
                    + "{ \"port\": 0, \"type\": \"goBILDA-4-Bar-Odometry-Pod\", \"xIn\": 0, \"yIn\": 0, \"direction\": \"forward\" }, "
                    + "{ \"port\": 1, \"type\": \"goBILDA-4-Bar-Odometry-Pod\", \"xIn\": 0, \"yIn\": 0, \"direction\": \"lateral\" }] }   // " + d.where());
            s.append("\n");
        }

        List<HardwareScanner.Device> unsupported = of(devices, HardwareScanner.Kind.UNSUPPORTED);
        if (!unsupported.isEmpty()) {
            s.append("\n  // Not simulated yet (calls to these will fail in the simulator):\n");
            for (HardwareScanner.Device d : unsupported) s.append("  //   ").append(d.javaType()).append(" \"").append(d.name()).append("\" (").append(d.where()).append(")\n");
        }
        s.append("}\n");
        return s.toString();
    }

    private static List<HardwareScanner.Device> of(List<HardwareScanner.Device> all, HardwareScanner.Kind kind) {
        return all.stream().filter(d -> d.kind() == kind).toList();
    }

    private static void list(StringBuilder s, List<HardwareScanner.Device> items, java.util.function.Function<HardwareScanner.Device, String> line) {
        if (items.isEmpty()) {
            s.append("]");
            return;
        }
        s.append('\n');
        for (int i = 0; i < items.size(); i++) {
            String l = line.apply(items.get(i));
            int comment = l.indexOf("   //");
            // The comma goes before the comment.
            String body = comment < 0 ? l : l.substring(0, comment), tail = comment < 0 ? "" : l.substring(comment);
            s.append("    ").append(body).append(i < items.size() - 1 ? "," : "").append(tail).append('\n');
        }
        s.append("  ]");
    }

    /** Which corner a drive motor name means ("leftFront", "fl", "front_left_drive", "rbMotor"...), or null. */
    static String corner(String name) {
        List<String> t = tokens(name);
        boolean front = t.contains("front") || t.contains("f"), back = t.contains("back") || t.contains("rear") || t.contains("b");
        boolean left = t.contains("left") || t.contains("l"), right = t.contains("right") || t.contains("r");
        for (String tok : t) {
            switch (tok) {
                case "fl", "lf" -> { front = true; left = true; }
                case "fr", "rf" -> { front = true; right = true; }
                case "bl", "lb", "rl", "lr" -> { back = true; left = true; }
                case "br", "rb", "rr" -> { back = true; right = true; }
                default -> { }
            }
        }
        if (front == back || left == right) return null;
        return (front ? "front" : "back") + (left ? "Left" : "Right");
    }

    private static List<String> tokens(String name) {
        String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("[^A-Za-z0-9]+", " ").trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(Arrays.asList(spaced.split(" ")));
        out.removeIf(s -> s.isEmpty() || s.equals("motor") || s.equals("drive") || s.equals("wheel") || s.matches("\\d+"));
        return out;
    }
}
