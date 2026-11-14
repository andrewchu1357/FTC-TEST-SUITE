package org.ftctestbed.sim.code;

import org.ftctestbed.sim.config.RobotConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads team code for the hardware device names it asks the hardware map for
 * ({@code hardwareMap.get(DcMotorEx.class, "leftFront")}, {@code hardwareMap.servo.get("claw")},
 * FTCLib's {@code new MotorEx(hardwareMap, "lift")}...), so the simulator can check them against the
 * robot config or write a config for them. Only names written as string literals are found.
 */
public final class HardwareScanner {
    /** What a device is, as far as the simulator's robot config is concerned. */
    public enum Kind { MOTOR, SERVO, CR_SERVO, DISTANCE, COLOR, TOUCH, IMU, HUB, PINPOINT, OTOS, OCTOQUAD, LIGHT, LIMELIGHT, WEBCAM, UNSUPPORTED, OTHER }

    public record Device(Kind kind, String javaType, String name, String where) {}

    private static final Pattern GET_CLASS = Pattern.compile("\\.\\s*(?:get|tryGet)\\s*\\(\\s*([\\w.]+)\\s*\\.\\s*class\\s*,\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern DEVICE_MAPPING = Pattern.compile(
            "\\.\\s*(dcMotor|servo|crservo|colorSensor|touchSensor|opticalDistanceSensor|ultrasonicSensor|digitalChannel|analogInput|voltageSensor|led|servoController|dcMotorController)\\s*\\.\\s*get\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern FTCLIB = Pattern.compile(
            "new\\s+(MotorEx|Motor|SimpleServo|ServoEx|CRServo|RevIMU|MotorGroup)\\s*\\(\\s*\\w+\\s*,\\s*\"([^\"]+)\"");

    private HardwareScanner() {}

    public static List<Device> scan(List<Path> sourceRoots) throws IOException {
        Map<String, Device> found = new LinkedHashMap<>();
        for (Path root : sourceRoots) {
            for (Path f : SourceFinder.javaFiles(root)) {
                String text = stripComments(Files.readString(f, StandardCharsets.UTF_8));
                String file = root.relativize(f).toString().replace('\\', '/');
                java.util.TreeMap<Integer, Device> inFile = new java.util.TreeMap<>(); // in order of appearance
                scan(text, file, GET_CLASS, inFile, false);
                scan(text, file, DEVICE_MAPPING, inFile, false);
                scan(text, file, FTCLIB, inFile, true);
                for (Device d : inFile.values()) found.putIfAbsent(d.name() + "|" + d.kind(), d);
            }
        }
        return new ArrayList<>(found.values());
    }

    private static void scan(String text, String file, Pattern p, Map<Integer, Device> found, boolean ftclib) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            String type = m.group(1);
            String simple = type.substring(type.lastIndexOf('.') + 1);
            Kind kind = ftclib ? ftclibKind(simple) : kind(simple);
            if (kind == null) continue; // a .get(SomeClass.class, "...") that isn't hardware
            String name = m.group(2);
            int line = 1;
            for (int i = 0; i < m.start(); i++) if (text.charAt(i) == '\n') line++;
            found.put(m.start(), new Device(kind, sdkTypeName(simple), name, file + ":" + line));
        }
    }

    /** hardwareMap.dcMotor → DcMotor and so on. */
    private static String sdkTypeName(String t) {
        return switch (t) {
            case "dcMotor" -> "DcMotor";
            case "servo" -> "Servo";
            case "crservo" -> "CRServo";
            case "colorSensor" -> "ColorSensor";
            case "touchSensor" -> "TouchSensor";
            case "digitalChannel" -> "DigitalChannel";
            case "analogInput" -> "AnalogInput";
            case "voltageSensor" -> "VoltageSensor";
            default -> t;
        };
    }

    static Kind kind(String type) {
        return switch (type) {
            case "DcMotor", "DcMotorEx", "DcMotorSimple", "DcMotorImplEx", "dcMotor" -> Kind.MOTOR;
            case "Servo", "ServoImplEx", "PwmControl", "servo" -> Kind.SERVO;
            case "CRServo", "CRServoImplEx", "crservo" -> Kind.CR_SERVO;
            case "DistanceSensor", "Rev2mDistanceSensor", "AndyMarkTOF" -> Kind.DISTANCE;
            case "ColorSensor", "NormalizedColorSensor", "RevColorSensorV3", "ColorRangeSensor", "AndyMarkColorSensor", "colorSensor" -> Kind.COLOR;
            case "TouchSensor", "RevTouchSensor", "DigitalChannel", "touchSensor", "digitalChannel" -> Kind.TOUCH;
            case "IMU", "BNO055IMU", "BNO055IMUNew", "BHI260IMU", "AndyMarkIMU" -> Kind.IMU;
            case "LynxModule" -> Kind.HUB;
            case "GoBildaPinpointDriver" -> Kind.PINPOINT;
            case "SparkFunOTOS" -> Kind.OTOS;
            case "OctoQuad" -> Kind.OCTOQUAD;
            case "LED", "led", "RevBlinkinLedDriver", "SparkFunLEDStick" -> Kind.LIGHT;
            case "Limelight3A" -> Kind.LIMELIGHT;
            case "WebcamName" -> Kind.WEBCAM;
            case "HuskyLens" -> Kind.UNSUPPORTED;
            case "VoltageSensor", "AnalogInput", "voltageSensor", "analogInput", "HardwareDevice",
                 "opticalDistanceSensor", "ultrasonicSensor", "servoController", "dcMotorController" -> Kind.OTHER;
            default -> null;
        };
    }

    private static Kind ftclibKind(String type) {
        return switch (type) {
            case "Motor", "MotorEx" -> Kind.MOTOR;
            case "SimpleServo", "ServoEx" -> Kind.SERVO;
            case "CRServo" -> Kind.CR_SERVO;
            case "RevIMU" -> Kind.IMU;
            default -> null;
        };
    }

    private static String stripComments(String s) {
        // Keep line breaks so line numbers still match.
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        boolean inString = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (inString) {
                b.append(c);
                if (c == '\\' && i + 1 < s.length()) b.append(s.charAt(++i));
                else if (c == '"') inString = false;
                i++;
            } else if (c == '"') {
                inString = true;
                b.append(c);
                i++;
            } else if (s.startsWith("//", i)) {
                while (i < s.length() && s.charAt(i) != '\n') i++;
            } else if (s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? s.length() : end + 2;
                for (int k = i; k < end; k++) if (s.charAt(k) == '\n') b.append('\n');
                i = end;
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }

    /** Devices the code uses that the robot config doesn't have (by name and kind). */
    public static List<String> missing(List<Device> devices, RobotConfig cfg) {
        Set<String> motors = new LinkedHashSet<>(), servos = new LinkedHashSet<>(), crServos = new LinkedHashSet<>(),
                distance = new LinkedHashSet<>(), color = new LinkedHashSet<>(), touch = new LinkedHashSet<>(), hubs = new LinkedHashSet<>();
        for (RobotConfig.Motor m : cfg.allMotors()) motors.add(m.name());
        for (RobotConfig.OdometryPod p : cfg.odometryPods()) motors.add(p.name());
        for (RobotConfig.Servo s : cfg.servos()) (s.continuous() ? crServos : servos).add(s.name());
        for (RobotConfig.DistanceSensor d : cfg.distanceSensors()) distance.add(d.name());
        for (RobotConfig.ColorSensor c : cfg.colorSensors()) color.add(c.name());
        for (RobotConfig.Switch s : cfg.switches()) touch.add(s.name());
        for (RobotConfig.Hub h : cfg.hubs()) hubs.add(h.name());
        List<String> out = new ArrayList<>();
        for (Device d : devices) {
            boolean ok = switch (d.kind()) {
                case MOTOR -> motors.contains(d.name());
                case SERVO -> servos.contains(d.name());
                case CR_SERVO -> crServos.contains(d.name());
                case DISTANCE -> distance.contains(d.name());
                case COLOR -> color.contains(d.name());
                case TOUCH -> touch.contains(d.name());
                case IMU -> cfg.imu().name().equals(d.name());
                case PINPOINT -> cfg.pinpoints().stream().anyMatch(p -> p.name().equals(d.name()));
                case OTOS -> cfg.otos().stream().anyMatch(o -> o.name().equals(d.name()));
                case OCTOQUAD -> cfg.octoquads().stream().anyMatch(o -> o.name().equals(d.name()));
                case LIGHT -> cfg.lights().stream().anyMatch(l -> l.name().equals(d.name()));
                case LIMELIGHT -> cfg.limelights().stream().anyMatch(l -> l.name().equals(d.name()));
                case WEBCAM -> cfg.webcams().stream().anyMatch(w -> w.name().equals(d.name()));
                case HUB -> hubs.isEmpty() ? d.name().startsWith("Control Hub") || d.name().startsWith("Expansion Hub") : hubs.contains(d.name());
                case UNSUPPORTED -> false;
                case OTHER -> true;
            };
            if (ok) continue;
            out.add(d.kind() == Kind.UNSUPPORTED
                    ? d.javaType() + " \"" + d.name() + "\" (" + d.where() + ") isn't simulated yet"
                    : d.javaType() + " \"" + d.name() + "\" (" + d.where() + ") is not in the robot config");
        }
        return out;
    }
}
