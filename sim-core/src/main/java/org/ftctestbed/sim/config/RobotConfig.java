package org.ftctestbed.sim.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The simulated robot, loaded from config/robot.jsonc. All values are SI (meters, kg,
 * radians). Robot-frame positions use x = forward, y = left, origin at the robot center.
 */
public record RobotConfig(
        double length,
        double width,
        double height,
        double mass,
        double cgHeight,
        double yawInertia,
        double frameOffsetX,
        double frameOffsetY,
        double batteryVolts,
        double batteryResistanceOhms,
        double batteryCapacityAh,
        Timing timing,
        Drivetrain drivetrain,
        Imu imu,
        List<Hub> hubs,
        List<Motor> motors,
        List<OdometryPod> odometryPods,
        List<DistanceSensor> distanceSensors,
        List<Servo> servos,
        List<Mechanism> mechanisms,
        List<Switch> switches,
        Intake intake,
        Hopper hopper,
        Launcher launcher,
        List<Gripper> grippers,
        List<ColorSensor> colorSensors,
        List<Pinpoint> pinpoints,
        List<Otos> otos,
        List<Light> lights,
        List<OctoQuad> octoquads,
        List<Limelight> limelights,
        List<Webcam> webcams,
        double servoVolts,
        Code code,
        List<PartUse> partsUsed,
        PartsCatalog catalog,
        List<String> warnings,
        String source) {

    /**
     * The team code that drives this robot, if it isn't the project's TeamCode module: source
     * folders and jars (relative to the robot file; jars may use * wildcards) compiled when the
     * simulator starts. javaRelease is the Java version to compile for (the FTC SDK uses 8).
     */
    public record Code(List<String> dirs, List<String> libs, int javaRelease) {}

    /** A device on the robot that names a catalog part ("claw" uses servo "goBILDA-2000-25-3"). */
    public record PartUse(String device, String category, String partId) {}

    public record Hub(String name, boolean parent, int address) {}

    /**
     * How long hardware calls take, in milliseconds: one command to the Control Hub, one to an
     * Expansion Hub, and one I2C transaction, and how long a gamepad reading takes from the Driver
     * Station to the robot. 0 makes that kind of call free.
     */
    public record Timing(double controlHubCommandMs, double expansionHubCommandMs, double i2cMs, double gamepadDelayMs) {}

    public record Motor(String name, MotorSpec spec, boolean mountedReversed, String hub, int port) {}

    public record Drivetrain(
            String type,
            double wheelRadius,
            double trackWidth,
            double wheelbase,
            double gearRatio,
            double wheelFriction,
            double rollerFriction,
            double gearboxFrictionFraction,
            double rollingResistance,
            Motor frontLeft, Motor frontRight, Motor backLeft, Motor backRight) {}

    /**
     * chip: "BHI260AP" (fuses at 800 Hz) or "BNO055" (100 Hz). driftRadPerSec: heading drift while
     * still; movingDriftRadPerSec: extra drift while turning; scaleError: fraction of each turn
     * the heading over-reads.
     */
    public record Imu(String name, String logo, String usb, String chip, double driftRadPerSec, double movingDriftRadPerSec,
                      double noiseRad, double scaleError) {}

    /**
     * A dead wheel. If its name matches a motor, it replaces that motor port's encoder.
     * wheelRadius and ticksPerRev are the nominal values (what robot code assumes);
     * diameterScale is how far the wheel's real rolling diameter on foam is off that, and
     * mountAngle how far its axis is turned from the intended one. ideal: no errors at all.
     */
    public record OdometryPod(String name, double x, double y, boolean lateral, double wheelRadius,
                              double ticksPerRev, boolean reversed, double diameterScale, double mountAngle, boolean ideal) {}

    /**
     * A goBILDA Pinpoint odometry computer on an I2C port, with its two pods (xPod tracks forward,
     * yPod sideways). firmware 3 is a Pinpoint V2 (bulk-read scope, CRC, quaternion), 2 a V1.
     * yawScaleError: how far its heading over-reads every turn after goBILDA's factory
     * calibration; drift and noise as for the hub IMU. ideal: a perfect gyro.
     */
    public record Pinpoint(String name, int bus, int firmware, OdometryPod xPod, OdometryPod yPod,
                           double yawScaleError, double driftRadPerSec, double noiseRad, boolean ideal) {}

    /**
     * A SparkFun OTOS on an I2C port: where it sits on the robot (x, y, and the angle its own
     * x axis is turned from the robot's), its height above the tiles (it only tracks foam well
     * at 10 +/- 1 mm), and its errors before robot code calibrates it: linearScaleError (how far
     * distances over-read; negative = under-read) and angularScaleError (turns), plus gyro drift
     * and optical velocity noise. ideal: a perfect sensor.
     */
    public record Otos(String name, int bus, double x, double y, double mountHeading, double heightMm,
                       double linearScaleError, double angularScaleError, double driftRadPerSec,
                       double velocityNoise, boolean ideal) {}

    /**
     * noise + noiseFraction * distance is the standard deviation of a reading. z is the mounting
     * height (NaN: unknown, the beam sees every game piece); updatePeriod is how often it measures.
     */
    public record DistanceSensor(String name, double x, double y, double heading, double maxRange, double noise,
                                 double noiseFraction, double z, double updatePeriod, String model) {}

    /**
     * A Limelight 3A on the robot: where its lens is (robot frame: x forward, y left, z up from the
     * floor, meters), which way it looks (heading left of straight ahead, tilt up from level, roll;
     * radians), and what each pipeline slot is set up to find: "color" (game pieces of a color,
     * target "pollen"/"yellow", "red", "blue" or a piece kind) or "fiducial" (AprilTags of a family).
     */
    public record Limelight(String name, double x, double y, double z, double heading, double tiltUp, double roll,
                            List<Limelight.Pipeline> pipelines) {
        /** erode, dilate: pixels the color threshold is shrunk, then grown, by (blobs closer than 2 x (dilate - erode) px join). */
        public record Pipeline(int index, String type, String target, String family, double minAreaPct, double maxAreaPct,
                               int erode, int dilate) {}
    }

    /**
     * A USB webcam the code opens with a VisionPortal (hardwareMap.get(WebcamName.class, name)):
     * model is one of WebcamModel's ("C920", "C270", "C310", "goBILDA", "LifeCam", "generic");
     * where it is on the robot (x forward, y left from the robot's center, z up from the floor)
     * and how it's turned (heading left of forward, tilted up, rolled).
     */
    public record Webcam(String name, String model, double x, double y, double z, double heading, double tiltUp, double roll) {}

    /**
     * A light the robot code switches: "led" (REV Digital LED Indicator, one LED per digital port,
     * lit when the port is low), "blinkin" (REV Blinkin LED Driver on a servo port) or
     * "ledStick" (SparkFun Qwiic LED Stick on an I2C port). color: what an "led" shows when lit.
     */
    public record Light(String name, String type, String hub, int port, int color) {}

    /**
     * A Digital Chicken Labs OctoQuad (FTC Edition MK2) on an I2C port: dead wheels plugged into
     * its channels (pods[channel], null where nothing is plugged in) and its own IMU, whose heading
     * over-reads every turn by imuScaleError until the code sets the heading scalar, and creeps by
     * driftRadPerSec. ideal: a perfect IMU.
     */
    public record OctoQuad(String name, int bus, List<OdometryPod> pods, double imuScaleError,
                           double driftRadPerSec, double noiseRad, boolean ideal) {}

    public record Servo(String name, boolean continuous, double rangeRad, double secPer60Deg,
                        double usAtRangeStart, double usAtRangeEnd, double crMaxRpm, String hub, int port) {}

    /**
     * type: "slide" (linear, spool driven), "arm" (rotating, gravity acts on it), or "free"
     * (flywheel / intake roller: just inertia and friction).
     */
    public record Mechanism(String name, String type, List<String> motors, double gearRatio,
                            double spoolRadius, double travel, double mass, boolean vertical,
                            double armLength, double minAngle, double maxAngle, double startAngle,
                            double inertia, double frictionFraction) {}

    /** A limit switch: pressed when the named mechanism is at or beyond a threshold. */
    public record Switch(String name, String mechanism, double pressedBelow, double pressedAbove) {}

    // ---- Game-piece handling (all optional) ----

    /**
     * Rollers that pull game pieces into the hopper. Driven by a motor or CR servo ("device").
     * The zone (robot frame) is where the rollers touch a piece: they grip it with friction
     * (squeeze x grip, less for a piece only partly under the rollers' ends) and pull it in at up
     * to their surface speed; the piece goes in when its center passes xMin into a throat that
     * takes one piece at a time. The robot's frame has a mouth reaching mouthDepth behind xMin.
     * Pulling a piece loads the roller's motor.
     */
    public record Intake(String device, double xMin, double xMax, double yMin, double yMax, double rollerRadius,
                         double minSurfaceSpeed, boolean inwardIsPositive, List<String> accepts,
                         double throatWidth, double squeeze, double grip, double mouthDepthMargin) {}

    /** Where intaken pieces wait. "transfer" (motor or CR servo) moves the next one up to the launcher. */
    public record Hopper(int capacity, String transfer, double transferSeconds, boolean transferForwardIsPositive) {}

    /**
     * A flywheel shooter. The flywheel is a "free" mechanism; ball exit speed is
     * efficiency * flywheel surface speed. A shot fires when the feeder servo's actual
     * position rises past fireAbove with a piece staged.
     */
    public record Launcher(String flywheel, double wheelRadius, double efficiency, double angle,
                           double x, double y, double z, String feederServo, double fireAbove, double spin) {}

    /**
     * A claw that grabs one piece. Its height is baseHeight plus the extension of a slide
     * mechanism (if any). Closed when the servo's actual position is below closedBelow.
     * If the slide is mounted at an angle, the claw moves from x to xRaised as it rises to
     * raisedHeight.
     */
    public record Gripper(String name, String servo, double closedBelow, double x, double y, String heightMechanism,
                          double baseHeight, double grabRadius, List<String> accepts, double xRaised, double raisedHeight,
                          double gripForce) {}

    /** A color sensor looking at a hopper slot (0 = the piece staged at the launcher). model: a catalog sensor id. */
    public record ColorSensor(String name, int hopperSlot, String model) {}

    public static RobotConfig load(Path file) {
        try {
            return parse(Files.readString(file), displayName(file), PartsCatalog.forConfigFile(file));
        } catch (java.nio.file.NoSuchFileException e) {
            throw new ConfigException(file + " not found");
        } catch (java.io.IOException e) {
            throw new ConfigException("Cannot read " + file + ": " + e.getMessage());
        }
    }

    public static RobotConfig parse(String text, String sourceName) {
        return parse(text, sourceName, PartsCatalog.builtIn());
    }

    /** "config/robot.jsonc", "robots/MyBot/robot.jsonc": the path from the config or robots folder on. */
    static String displayName(Path file) {
        Path p = file.toAbsolutePath().normalize();
        for (int i = p.getNameCount() - 2; i >= 0; i--) {
            String n = p.getName(i).toString();
            if (n.equals("config") || n.equals("robots")) return p.subpath(i, p.getNameCount()).toString().replace('\\', '/');
        }
        return p.getFileName().toString();
    }

    public static RobotConfig parse(String text, String sourceName, PartsCatalog parts) {
        ConfigNode root = ConfigNode.fromObject(sourceName, Jsonc.parse(text, sourceName));
        double in = 0.0254;
        List<PartUse> used = new ArrayList<>();
        double servoVolts = root.getDouble("servoVolts", PartsCatalog.DEFAULT_SERVO_VOLTS);

        double length = root.length("length", 18 * in);
        double width = root.length("width", 18 * in);
        double height = root.length("height", 18 * in);
        double mass = root.mass("mass", 13.6);
        // Center of gravity above the floor with everything retracted. Mechanisms that rise (slides,
        // arms) raise it as they extend. Most FTC robots sit around a third of their height.
        double cgHeight = root.length("cgHeight", 0.35 * height);
        // How hard the robot is to spin (kg m^2). Default: its weight spread evenly over its
        // footprint. Robots with heavy parts near the middle turn faster; calibration measures it.
        double yawInertia = root.getDouble("yawInertiaKgM2", mass * (length * length + width * width) / 12.0);
        // Where the middle of the frame (lengthIn x widthIn) is from the robot's center, the middle
        // of its wheels, which it turns about and everything else is measured from: an intake
        // that sticks out past the wheels puts it forward.
        double frameOffsetX = root.length("frameOffsetX", 0), frameOffsetY = root.length("frameOffsetY", 0);

        ConfigNode battery = root.child("battery");
        double volts = battery == null ? 12.8 : battery.getDouble("volts", 12.8);
        // Battery plus wiring, fuse and switch. goBILDA suggests retiring a 12 V NiMH pack once
        // the battery alone measures more than 130-200 mOhm; 0.1 is a used-but-healthy pack.
        double resistance = battery == null ? DEFAULT_BATTERY_OHMS : battery.getDouble("internalResistanceOhms", DEFAULT_BATTERY_OHMS);
        double capacityAh = (battery == null ? 3000 : battery.getDouble("capacityMah", 3000)) / 1000.0;

        // Hardware call times (gm0: about 2 ms per command over the Control Hub's UART link,
        // 3 ms over USB/RS-485; I2C reads take longer).
        ConfigNode ti = root.child("hubTiming");
        Timing timing = new Timing(
                ti == null ? 2.0 : ti.getDouble("controlHubCommandMs", 2.0),
                ti == null ? 3.0 : ti.getDouble("expansionHubCommandMs", 3.0),
                ti == null ? 3.0 : ti.getDouble("i2cMs", 3.0),
                // [ESTIMATE] Driver Station to robot: the Wi-Fi link plus both apps. The DS shows
                // a ping of tens of ms (round trip); half of that plus processing.
                ti == null ? 20.0 : ti.getDouble("gamepadDelayMs", 20.0));

        ConfigNode dt = root.child("drivetrain");
        if (dt == null) throw new ConfigException(sourceName + ": missing \"drivetrain\"");
        String type = dt.getString("type", "mecanum");
        if (!type.equals("mecanum")) throw new ConfigException(dt.path() + ".type: only \"mecanum\" is supported so far");
        ConfigNode dm = dt.child("motors");
        if (dm == null) throw new ConfigException(dt.path() + ": missing \"motors\"");
        String defaultDriveMotor = dt.getString("motorType", "goBILDA-5203-312");
        double wheelDiameter = 0.104;
        String wheel = dt.getString("wheel", null);
        if (wheel != null) {
            wheelDiameter = parts.wheel(wheel, dt.path() + ".wheel")[0];
            used.add(new PartUse("drive wheels (×4)", "wheels", parts.find("wheels", wheel).id()));
        }
        Drivetrain drivetrain = new Drivetrain(type,
                dt.length("wheelDiameter", wheelDiameter) / 2,
                dt.length("trackWidth", 15 * in),
                dt.length("wheelbase", 13 * in),
                dt.getDouble("gearRatio", 1.0),
                dt.getDouble("wheelFriction", 0.9),
                dt.getDouble("rollerFriction", 0.2),
                dt.getDouble("gearboxFriction", 0.06),
                // Foam tiles squash under the wheels: a drag of about 2% of the load on each wheel.
                dt.getDouble("rollingResistance", 0.02),
                motor(dm.child("frontLeft"), defaultDriveMotor, true, parts, used),
                motor(dm.child("frontRight"), defaultDriveMotor, false, parts, used),
                motor(dm.child("backLeft"), defaultDriveMotor, true, parts, used),
                motor(dm.child("backRight"), defaultDriveMotor, false, parts, used));

        ConfigNode im = root.child("imu");
        // A hub IMU is never perfect: its heading creeps while still, creeps more while turning,
        // jitters, and reads a little too far on every turn. Defaults per chip from the datasheets
        // [ESTIMATE where noted]; measure yours with the Calibrate IMU and Calibrate Turn OpModes
        // (README, Calibration). chip: "BHI260AP" (Control Hubs since 2022), "BNO055" (older), or
        // "AndyMark" (the AndyMark IMU board, a BNO055: its mounting is "logo" and "i2cPort").
        String chip = im == null ? "BHI260AP" : im.getString("chip", "BHI260AP").toUpperCase();
        if (!chip.equals("BHI260AP") && !chip.equals("BNO055") && !chip.equals("ANDYMARK")) {
            throw new ConfigException(im.path() + ".chip: must be \"BHI260AP\", \"BNO055\" or \"AndyMark\"");
        }
        boolean bhi = chip.equals("BHI260AP");
        double drift = bhi ? BHI_STILL_DRIFT : BNO_STILL_DRIFT, moving = bhi ? BHI_MOVING_DRIFT : BNO_MOVING_DRIFT;
        double noise = IMU_NOISE_DEG, scale = IMU_SCALE_ERROR;
        Imu imu = im == null ? new Imu("imu", "UP", "FORWARD", chip, Math.toRadians(drift) / 60.0, Math.toRadians(moving) / 60.0,
                Math.toRadians(noise), scale) : new Imu(
                im.getString("name", "imu"),
                im.getString("logo", "UP").toUpperCase(),
                im.getString(chip.equals("ANDYMARK") ? "i2cPort" : "usb", "FORWARD").toUpperCase(),
                chip,
                Math.toRadians(im.getDouble("driftDegPerMin", drift)) / 60.0,
                Math.toRadians(im.getDouble("movingDriftDegPerMin", moving)) / 60.0,
                Math.toRadians(im.getDouble("noiseDeg", noise)),
                im.getDouble("scaleError", scale));

        List<Hub> hubs = new ArrayList<>();
        for (ConfigNode h : root.children("hubs")) {
            hubs.add(new Hub(h.requireString("name"), h.getBoolean("parent", hubs.isEmpty()), h.getInt("address", hubs.isEmpty() ? 173 : 2)));
        }

        List<Motor> motors = new ArrayList<>();
        for (ConfigNode m : root.children("motors")) motors.add(motor(m, "goBILDA-5203-312", false, parts, used));

        List<OdometryPod> pods = new ArrayList<>();
        for (ConfigNode p : root.children("odometryPods")) {
            String dir = p.getString("direction", "forward");
            if (!dir.equals("forward") && !dir.equals("lateral")) {
                throw new ConfigException(p.path() + ".direction: must be \"forward\" or \"lateral\"");
            }
            pods.add(pod(p, p.requireString("name"), dir.equals("lateral"), parts, used));
        }

        List<Pinpoint> pinpoints = new ArrayList<>();
        for (ConfigNode p : root.children("pinpoints")) {
            String name = p.getString("name", "pinpoint");
            ConfigNode xp = p.child("xPod"), yp = p.child("yPod");
            if (xp == null || yp == null) throw new ConfigException(p.path() + ": needs an \"xPod\" (tracks forward) and a \"yPod\" (tracks sideways)");
            String version = p.getString("version", "V2");
            if (!version.equals("V1") && !version.equals("V2")) throw new ConfigException(p.path() + ".version: must be \"V1\" or \"V2\"");
            boolean ideal = p.getBoolean("ideal", false);
            // [ESTIMATE] goBILDA: within 0.1% from the factory, tuned "down to 0.002%" per unit;
            // allow for temperature and mounting. Stationary drift: goBILDA calls > 1 deg/min a fault.
            double yawScale = p.getDouble("yawScaleError", ideal ? 0 : seeded(name + ".yaw", PINPOINT_YAW_SCALE_SIGMA));
            pinpoints.add(new Pinpoint(name, p.getInt("bus", 1), version.equals("V2") ? 3 : 2,
                    pod(xp, name + ".xPod", false, parts, used), pod(yp, name + ".yPod", true, parts, used),
                    yawScale, Math.toRadians(p.getDouble("driftDegPerMin", ideal ? 0 : PINPOINT_DRIFT_DEG_PER_MIN)) / 60.0,
                    Math.toRadians(p.getDouble("noiseDeg", ideal ? 0 : PINPOINT_NOISE_DEG)), ideal));
            used.add(new PartUse(name, "sensors", parts.find("sensors", "goBILDA-Pinpoint").id()));
        }

        List<Otos> otosList = new ArrayList<>();
        for (ConfigNode o : root.children("otos")) {
            String name = o.getString("name", "sensor_otos");
            boolean ideal = o.getBoolean("ideal", false);
            // [ESTIMATE] SparkFun: "typical error rate of 3-5% out of the box" (under-reads), gyro
            // sensitivity +/-1% (LSM6DSO), heading "within 1 degree for up to 5 minutes".
            otosList.add(new Otos(name, o.getInt("bus", 2), o.length("x", 0), o.length("y", 0),
                    o.angle("mountHeading", 0), o.getDouble("heightMm", 10),
                    o.getDouble("linearScaleError", ideal ? 0 : OTOS_LINEAR_SCALE_ERROR),
                    o.getDouble("angularScaleError", ideal ? 0 : seeded(name + ".gyro", OTOS_ANGULAR_SCALE_SIGMA)),
                    Math.toRadians(o.getDouble("driftDegPerMin", ideal ? 0 : OTOS_DRIFT_DEG_PER_MIN)) / 60.0,
                    o.length("velocityNoise", ideal ? 0 : OTOS_VELOCITY_NOISE), ideal));
            used.add(new PartUse(name, "sensors", parts.find("sensors", "SparkFun-OTOS").id()));
        }

        List<DistanceSensor> distance = new ArrayList<>();
        for (ConfigNode d : root.children("distanceSensors")) {
            double maxRange = 2.0;
            String sensorType = d.getString("type", null);
            String model = "REV-2m-Distance-Sensor";
            if (sensorType != null) {
                PartsCatalog.Part part = parts.sensor(sensorType, d.path() + ".type");
                if (!"distance".equals(part.node().getString("kind", ""))) throw new ConfigException(d.path() + ".type: " + sensorType + " isn't a distance sensor");
                maxRange = part.node().length("maxRange", maxRange);
                used.add(new PartUse(d.requireString("name"), "sensors", part.id()));
                model = part.id();
            }
            // [ESTIMATE] Time-of-flight sensors like the REV 2m (VL53L0X) measure about every 33 ms and
            // are good to a few millimeters plus 1-2% of the distance.
            distance.add(new DistanceSensor(d.requireString("name"), d.length("x", 0), d.length("y", 0),
                    d.angle("heading", 0), d.length("maxRange", maxRange), d.length("noise", 0.003),
                    d.getDouble("noisePercent", 1.5) / 100, d.length("z", Double.NaN),
                    d.getDouble("updateMs", 33) / 1000, model));
        }

        List<Servo> servos = new ArrayList<>();
        for (ConfigNode s : root.children("servos")) {
            PartsCatalog.ServoSpec spec = servoPart(s, parts, servoVolts, used);
            servos.add(new Servo(s.requireString("name"), false, s.angle("range", spec == null ? Math.toRadians(300) : spec.rangeRad()),
                    s.getDouble("secPer60Deg", spec == null ? 0.2 : spec.secPer60Deg()),
                    s.getDouble("usAtRangeStart", spec == null ? 500 : spec.usAtRangeStart()),
                    s.getDouble("usAtRangeEnd", spec == null ? 2500 : spec.usAtRangeEnd()),
                    0, s.getString("hub", null), s.getInt("port", -1)));
        }
        for (ConfigNode s : root.children("crServos")) {
            PartsCatalog.ServoSpec spec = servoPart(s, parts, servoVolts, used);
            if (spec != null && !spec.continuousMode()) {
                throw new ConfigException(s.path() + ".type: " + s.getString("type", "") + " has no continuous-rotation mode");
            }
            servos.add(new Servo(s.requireString("name"), true, 0, 0, 500, 2500,
                    s.getDouble("maxRpm", spec == null ? 150 : spec.crMaxRpm()), s.getString("hub", null), s.getInt("port", -1)));
        }

        List<Mechanism> mechanisms = new ArrayList<>();
        for (ConfigNode m : root.children("mechanisms")) {
            String mtype = m.getString("type", "free");
            if (!List.of("slide", "arm", "free").contains(mtype)) {
                throw new ConfigException(m.path() + ".type: must be \"slide\", \"arm\" or \"free\"");
            }
            double spool = 0.0382, travel = 0.6;
            String kit = m.getString("kit", null);
            if (kit != null) {
                if (!mtype.equals("slide")) throw new ConfigException(m.path() + ".kit: only \"slide\" mechanisms use a slide kit");
                double[] k = parts.slide(kit, m.path() + ".kit");
                travel = k[0];
                spool = k[2];
                used.add(new PartUse(m.requireString("name"), "slides", parts.find("slides", kit).id()));
            }
            mechanisms.add(new Mechanism(m.requireString("name"), mtype, m.getStringList("motors"),
                    m.getDouble("gearRatio", 1.0), m.length("spoolDiameter", spool) / 2, m.length("travel", travel),
                    m.mass("mass", 1.0), m.getBoolean("vertical", true), m.length("armLength", 0.3),
                    m.angle("min", Math.toRadians(-45)), m.angle("max", Math.toRadians(225)), m.angle("start", Math.toRadians(-45)),
                    m.getDouble("inertiaKgM2", 0.0005), m.getDouble("gearboxFriction", 0.06)));
        }

        List<Switch> switches = new ArrayList<>();
        for (ConfigNode s : root.children("limitSwitches")) {
            switches.add(new Switch(s.requireString("name"), s.requireString("mechanism"),
                    s.has("pressedBelow") ? s.getDouble("pressedBelow", 0) : Double.NEGATIVE_INFINITY,
                    s.has("pressedAbove") ? s.getDouble("pressedAbove", 0) : Double.POSITIVE_INFINITY));
        }

        Intake intake = null;
        ConfigNode in0 = root.child("intake");
        if (in0 != null) {
            ConfigNode zone = in0.child("zone");
            if (zone == null) throw new ConfigException(in0.path() + ": missing \"zone\"");
            intake = new Intake(in0.requireString("device"),
                    zone.length("xMin", 7 * in), zone.length("xMax", 11 * in), zone.length("yMin", -8 * in), zone.length("yMax", 8 * in),
                    in0.length("rollerDiameter", 0.048) / 2, in0.length("minSurfaceSpeedPerSec", 15 * in),
                    in0.getBoolean("inwardIsPositive", true), in0.getStringList("accepts"),
                    in0.length("throatWidth", 5 * in), in0.getDouble("squeezeN", 6), in0.getDouble("gripFriction", 0.8),
                    in0.length("mouthDepth", 2 * in));
        }

        Hopper hopper = null;
        ConfigNode ho = root.child("hopper");
        if (ho != null) {
            hopper = new Hopper(ho.getInt("capacity", 4), ho.getString("transfer", null),
                    ho.getDouble("transferSeconds", 0.25), ho.getBoolean("transferForwardIsPositive", true));
        }

        Launcher launcher = null;
        ConfigNode la = root.child("launcher");
        if (la != null) {
            launcher = new Launcher(la.requireString("flywheel"), la.length("wheelDiameter", 0.096) / 2,
                    la.getDouble("efficiency", 0.5), la.angle("angle", Math.toRadians(55)),
                    la.length("x", 0), la.length("y", 0), la.length("height", 12 * in),
                    la.requireString("feederServo"), la.getDouble("fireAbove", 0.6), launcherSpin(la));
        }

        List<Gripper> grippers = new ArrayList<>();
        for (ConfigNode g : root.children("grippers")) {
            double gx = g.length("x", 0);
            grippers.add(new Gripper(g.requireString("name"), g.requireString("servo"), g.getDouble("closedBelow", 0.5),
                    gx, g.length("y", 0), g.getString("heightMechanism", null),
                    g.length("baseHeight", 2 * in), g.length("grabRadius", 3 * in), g.getStringList("accepts"),
                    g.length("xRaised", gx), g.length("raisedHeight", 24 * in), g.getDouble("gripForceN", 1.5)));
        }

        List<ColorSensor> colorSensors = new ArrayList<>();
        for (ConfigNode c : root.children("colorSensors")) {
            String model = "REV-Color-Sensor-V3";
            String colorType = c.getString("type", null);
            if (colorType != null) {
                PartsCatalog.Part part = parts.sensor(colorType, c.path() + ".type");
                if (!"color".equals(part.node().getString("kind", ""))) throw new ConfigException(c.path() + ".type: " + colorType + " isn't a color sensor");
                model = part.id();
            }
            colorSensors.add(new ColorSensor(c.requireString("name"), c.getInt("hopperSlot", 0), model));
            used.add(new PartUse(c.requireString("name"), "sensors", model));
        }

        List<Light> lights = new ArrayList<>();
        for (ConfigNode l : root.children("lights")) {
            String lightType = l.getString("type", "led");
            if (!List.of("led", "blinkin", "ledStick").contains(lightType)) {
                throw new ConfigException(l.path() + ".type: must be \"led\", \"blinkin\" or \"ledStick\"");
            }
            int defaultColor = lightType.equals("led") ? 0x22FF44 : 0xFFFFFF;
            lights.add(new Light(l.requireString("name"), lightType, l.getString("hub", null),
                    l.getInt(lightType.equals("ledStick") ? "bus" : "port", lightType.equals("ledStick") ? 3 : -1),
                    l.has("color") ? colorValue(l, "color") : defaultColor));
        }

        List<Limelight> limelights = new ArrayList<>();
        for (ConfigNode l : root.children("limelights")) {
            // [PLACEHOLDER] Until the team picks a mount: the front of the robot, 10 in up, looking straight ahead.
            double front = length / 2 - 1 * IN_M;
            List<Limelight.Pipeline> pipes = new ArrayList<>();
            List<ConfigNode> pn = l.children("pipelines");
            if (pn.isEmpty()) {
                // The slots TRC's BIOBUZZ Limelight guide sets up: yellow, red and blue game pieces, then AprilTags.
                pipes.add(new Limelight.Pipeline(0, "color", "pollen", "", 0.05, 100, 1, 2));
                pipes.add(new Limelight.Pipeline(1, "color", "red", "", 0.05, 100, 1, 2));
                pipes.add(new Limelight.Pipeline(2, "color", "blue", "", 0.05, 100, 1, 2));
                pipes.add(new Limelight.Pipeline(3, "fiducial", "", "36h11", 0, 100, 0, 0));
            }
            for (int i = 0; i < pn.size(); i++) {
                ConfigNode p = pn.get(i);
                String pipeType = p.getString("type", "color");
                if (!pipeType.equals("color") && !pipeType.equals("fiducial")) {
                    throw new ConfigException(p.path() + ".type: must be \"color\" or \"fiducial\" (the simulator doesn't run neural or Python pipelines)");
                }
                int index = p.getInt("index", i);
                if (index < 0 || index > 9) throw new ConfigException(p.path() + ".index: a Limelight has pipeline slots 0-9");
                pipes.add(new Limelight.Pipeline(index, pipeType, p.getString("target", pipeType.equals("color") ? "pollen" : ""),
                        p.getString("family", "36h11"), p.getDouble("minAreaPct", pipeType.equals("color") ? 0.05 : 0),
                        p.getDouble("maxAreaPct", 100), p.getInt("erode", 1), p.getInt("dilate", 2)));
            }
            limelights.add(new Limelight(l.getString("name", "limelight"), l.length("x", front), l.length("y", 0),
                    l.length("z", 10 * IN_M), l.angle("heading", 0), l.angle("tiltUp", 0), l.angle("roll", 0), pipes));
            used.add(new PartUse(l.getString("name", "limelight"), "sensors", parts.find("sensors", "Limelight-3A").id()));
        }

        List<Webcam> webcams = new ArrayList<>();
        for (ConfigNode c : root.children("webcams")) {
            String name = c.getString("name", "Webcam 1");
            String modelId = c.getString("model", "C920");
            org.ftctestbed.sim.vision.WebcamModel model = org.ftctestbed.sim.vision.WebcamModel.find(modelId);
            if (model == null) {
                throw new ConfigException(c.path() + ".model: one of \"C920\", \"C270\", \"C310\", \"goBILDA\", \"LifeCam\" or \"generic\"");
            }
            // [PLACEHOLDER] Until the team picks a mount: the front of the robot, 8 in up, looking straight ahead.
            webcams.add(new Webcam(name, model.id(), c.length("x", length / 2 - 1 * IN_M), c.length("y", 0), c.length("z", 8 * IN_M),
                    c.angle("heading", 0), c.angle("tiltUp", 0), c.angle("roll", 0)));
            String part = switch (model.id()) {
                case "C920" -> "Logitech-C920";
                case "C270" -> "Logitech-C270";
                case "C310" -> "Logitech-C310";
                case "goBILDA" -> "goBILDA-USB-Camera";
                case "LifeCam" -> "Microsoft-LifeCam-HD-3000";
                default -> "Generic-USB-Webcam";
            };
            used.add(new PartUse(name, "sensors", parts.find("sensors", part).id()));
        }

        List<OctoQuad> octoquads = new ArrayList<>();
        for (ConfigNode o : root.children("octoquads")) {
            String name = o.getString("name", "octoquad");
            List<OdometryPod> channelPods = new ArrayList<>();
            for (int i = 0; i < 8; i++) channelPods.add(null);
            for (ConfigNode ch : o.children("channels")) {
                int port = ch.getInt("port", -1);
                if (port < 0 || port > 7) throw new ConfigException(ch.path() + ".port: an OctoQuad channel 0-7");
                if (channelPods.get(port) != null) throw new ConfigException(ch.path() + ".port: channel " + port + " is used twice");
                String dir = ch.getString("direction", "forward");
                if (!dir.equals("forward") && !dir.equals("lateral")) {
                    throw new ConfigException(ch.path() + ".direction: must be \"forward\" or \"lateral\"");
                }
                channelPods.set(port, pod(ch, name + ".channel" + port, dir.equals("lateral"), parts, used));
            }
            boolean ideal = o.getBoolean("ideal", false);
            // [ESTIMATE] Digital Chicken Labs: the IMU's zero-rate bias is calibrated by the device, its scale
            // factor isn't ("it is critical to calculate this scale factor"); a MEMS gyro is good to about 1%.
            octoquads.add(new OctoQuad(name, o.getInt("bus", 3), channelPods,
                    o.getDouble("imuScaleError", ideal ? 0 : seeded(name + ".gyro", OCTOQUAD_SCALE_SIGMA)),
                    Math.toRadians(o.getDouble("driftDegPerMin", ideal ? 0 : OCTOQUAD_DRIFT_DEG_PER_MIN)) / 60.0,
                    Math.toRadians(o.getDouble("noiseDeg", ideal ? 0 : PINPOINT_NOISE_DEG)), ideal));
            used.add(new PartUse(name, "sensors", parts.find("sensors", "DigitalChickenLabs-OctoQuad").id()));
        }

        Code code = null;
        ConfigNode co = root.child("code");
        if (co != null) {
            code = new Code(co.getStringList("dirs"), co.getStringList("libs"), co.getInt("javaRelease", 8));
            if (code.dirs().isEmpty()) throw new ConfigException(co.path() + ".dirs: list the folders with your Java sources");
        }

        List<String> warnings = new ArrayList<>(parts.warnings());
        root.collectUnused(warnings);
        RobotConfig cfg = new RobotConfig(length, width, height, mass, cgHeight, yawInertia, frameOffsetX, frameOffsetY, volts, resistance, capacityAh, timing, drivetrain, imu, hubs, motors, pods,
                distance, servos, mechanisms, switches, intake, hopper, launcher, grippers, colorSensors, pinpoints, otosList,
                lights, octoquads, limelights, webcams,
                servoVolts, code, used, parts, warnings, sourceName);
        cfg.validate();
        return cfg;
    }

    /**
     * Spin the ball leaves with, as a multiple of rolling without slipping (spin rate x radius =
     * ball speed). A ball pinched between a flywheel and a fixed hood rolls along the hood, so it
     * leaves with exactly that much spin: backspin when the wheel is below the ball (it floats a
     * little), topspin when the wheel is above (it dips).
     */
    private static double launcherSpin(ConfigNode la) {
        String spin = la.getString("spin", "backspin");
        return switch (spin) {
            case "backspin" -> 1;
            case "topspin" -> -1;
            case "none" -> 0;
            default -> throw new ConfigException(la.path() + ".spin: must be \"backspin\", \"topspin\" or \"none\"");
        };
    }

    // [ESTIMATE] Dead wheels on foam tiles: no published measurements. Each pod's rolling
    // diameter is off nominal by a fixed amount (sigma 0.3%, what multiplier tuning fixes) and its
    // axis is turned about 0.5 degrees; both are drawn once per pod name, so they don't change
    // between runs. Set "diameterScale" / "mountAngleDeg" to measured values, or "ideal": true.
    static final double POD_DIAMETER_SIGMA = 0.003;
    static final double POD_MOUNT_ANGLE_DEG = 0.5;
    static final double PINPOINT_YAW_SCALE_SIGMA = 0.0005;
    static final double PINPOINT_DRIFT_DEG_PER_MIN = 0.2;
    static final double PINPOINT_NOISE_DEG = 0.01;
    static final double OTOS_LINEAR_SCALE_ERROR = -0.03;
    static final double OTOS_ANGULAR_SCALE_SIGMA = 0.007;
    static final double OTOS_DRIFT_DEG_PER_MIN = 0.2;
    static final double OTOS_VELOCITY_NOISE = 0.076;
    static final double OCTOQUAD_SCALE_SIGMA = 0.01;
    static final double OCTOQUAD_DRIFT_DEG_PER_MIN = 0.2;

    /** A repeatable "random" value for a part: normally distributed, sigma, fixed by the key. */
    static double seeded(String key, double sigma) {
        return new java.util.Random(key.hashCode() * 0x9E3779B97F4A7C15L).nextGaussian() * sigma;
    }

    /** A color: "#RRGGBB", or "red" / "green" (the two colors of a REV Digital LED Indicator). */
    private static int colorValue(ConfigNode n, String key) {
        String c = n.getString(key, "");
        switch (c.toLowerCase()) {
            case "red": return 0xFF2A1A;
            case "green": return 0x22FF44;
            default:
                try {
                    return Integer.parseInt(c.replace("#", ""), 16);
                } catch (NumberFormatException e) {
                    throw new ConfigException(n.path() + "." + key + ": \"" + c + "\" isn't a color (use \"#RRGGBB\", \"red\" or \"green\")");
                }
        }
    }

    private static OdometryPod pod(ConfigNode p, String name, boolean lateral, PartsCatalog parts, List<PartUse> used) {
        double podWheel = 0.032, podTicks = 2000;
        String podType = p.getString("type", null);
        if (podType != null) {
            double[] pod = parts.odometryPod(podType, p.path() + ".type");
            if (!Double.isNaN(pod[0])) podWheel = pod[0];
            else if (!p.hasLength("wheelDiameter")) throw new ConfigException(p.path() + ": set wheelDiameter (" + podType + " doesn't include a wheel)");
            podTicks = pod[1];
            used.add(new PartUse(name, "odometryPods", parts.find("odometryPods", podType).id()));
        }
        boolean ideal = p.getBoolean("ideal", false);
        double scale = p.getDouble("diameterScale", ideal ? 1 : 1 + seeded(name + ".diameter", POD_DIAMETER_SIGMA));
        double mountDeg = p.getDouble("mountAngleDeg", ideal ? 0
                : (seeded(name + ".mount", 1) >= 0 ? POD_MOUNT_ANGLE_DEG : -POD_MOUNT_ANGLE_DEG));
        return new OdometryPod(name, p.length("x", 0), p.length("y", 0), lateral, p.length("wheelDiameter", podWheel) / 2,
                p.getDouble("ticksPerRev", podTicks), p.getBoolean("reversed", false), scale, Math.toRadians(mountDeg), ideal);
    }

    private static Motor motor(ConfigNode n, String defaultType, boolean defaultReversed, PartsCatalog parts, List<PartUse> used) {
        if (n == null) throw new ConfigException("drivetrain.motors needs frontLeft, frontRight, backLeft and backRight");
        MotorSpec spec = MotorSpec.parse(n, "type", defaultType, parts);
        String name = n.requireString("name");
        PartsCatalog.Part part = parts.find("motors", spec.id());
        used.add(new PartUse(name, "motors", part != null ? part.id() : spec.id()));
        return new Motor(name, spec, n.getBoolean("mountedReversed", defaultReversed), n.getString("hub", null), n.getInt("port", -1));
    }

    /** The catalog servo named by "type", or null if the servo is described by hand. */
    private static PartsCatalog.ServoSpec servoPart(ConfigNode s, PartsCatalog parts, double volts, List<PartUse> used) {
        String type = s.getString("type", null);
        if (type == null) return null;
        used.add(new PartUse(s.requireString("name"), "servos", parts.find("servos", type) != null ? parts.find("servos", type).id() : type));
        return parts.servo(type, volts, s.path() + ".type");
    }

    public List<Motor> allMotors() {
        List<Motor> all = new ArrayList<>(List.of(drivetrain.frontLeft, drivetrain.frontRight, drivetrain.backLeft, drivetrain.backRight));
        all.addAll(motors);
        return all;
    }

    private static final double IN_M = 0.0254;
    public static final double DEFAULT_BATTERY_OHMS = 0.1;
    // Bosch BHI260AP game rotation vector KPIs: static heading drift 0.01 typ / 0.03 max deg/min,
    // dynamic 0.6 typ / 3 max. BNO055: no published yaw-drift KPI [ESTIMATE 0.2 / 1.0]. Scale
    // error: BHI260AP sensitivity +/-1.3% PCB-compensated, BNO055 +/-1% typ [ESTIMATE 0.3%].
    static final double BHI_STILL_DRIFT = 0.03, BHI_MOVING_DRIFT = 0.6, BNO_STILL_DRIFT = 0.2, BNO_MOVING_DRIFT = 1.0;
    static final double IMU_NOISE_DEG = 0.03, IMU_SCALE_ERROR = 0.003;

    /** Throws a ConfigException unless lo < value < hi (exclusive), so typos don't break the physics. */
    private static void check(String what, double value, double lo, double hi, String hint) {
        if (!(value > lo && value < hi)) {
            throw new ConfigException(what + " must be " + hint + " (got " + (Double.isFinite(value) ? trim(value) : String.valueOf(value)) + ")");
        }
    }

    private static String trim(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e9 ? String.valueOf((long) v) : String.format(java.util.Locale.US, "%.4g", v);
    }

    private void checkRanges() {
        check("lengthIn", length / IN_M, 1, 60, "between 1 and 60");
        check("widthIn", width / IN_M, 1, 60, "between 1 and 60");
        check("heightIn", height / IN_M, 0.5, 60, "between 0.5 and 60");
        check("mass", mass, 0.1, 100, "between 0.1 and 100 kg (massLbs between 0.2 and 220)");
        check("battery.volts", batteryVolts, 6, 20, "between 6 and 20");
        check("battery.internalResistanceOhms", batteryResistanceOhms, -1e-12, 1, "between 0 and 1");
        check("battery.capacityMah", batteryCapacityAh * 1000, 100, 100000, "between 100 and 100000");
        check("cgHeight", cgHeight / IN_M, 0, height / IN_M + 1e-9, "between 0 and the robot's height");
        check("frameOffsetX", frameOffsetX / IN_M, -length / 2 / IN_M, length / 2 / IN_M, "within half the robot's length");
        check("frameOffsetY", frameOffsetY / IN_M, -width / 2 / IN_M, width / 2 / IN_M, "within half the robot's width");
        check("yawInertiaKgM2", yawInertia, 0, 100, "more than 0");
        check("drivetrain.rollingResistance", drivetrain.rollingResistance(), -1e-12, 1, "between 0 and 1");
        check("imu.scaleError", Math.abs(imu.scaleError()), -1e-12, 0.2, "between -0.2 and 0.2");
        check("hubTiming.controlHubCommandMs", timing.controlHubCommandMs(), -1e-12, 50, "between 0 and 50");
        check("hubTiming.expansionHubCommandMs", timing.expansionHubCommandMs(), -1e-12, 50, "between 0 and 50");
        check("hubTiming.i2cMs", timing.i2cMs(), -1e-12, 50, "between 0 and 50");
        check("servoVolts", servoVolts, 3, 9, "between 3 and 9 (5 on a hub servo port, 6 with a Servo Power Module)");
        Drivetrain d = drivetrain;
        check("drivetrain.wheelDiameter", d.wheelRadius() * 2 / IN_M, 0.5, 12, "between 0.5 and 12 in (12 and 300 mm)");
        check("drivetrain.trackWidth", d.trackWidth() / IN_M, 1, 60, "between 1 and 60 in");
        check("drivetrain.wheelbase", d.wheelbase() / IN_M, 1, 60, "between 1 and 60 in");
        check("drivetrain.gearRatio", d.gearRatio(), 0, 100, "more than 0");
        check("drivetrain.wheelFriction", d.wheelFriction(), 0, 5, "between 0 and 5 (exclusive)");
        check("drivetrain.rollerFriction", d.rollerFriction(), -1e-12, 5, "between 0 and 5");
        check("drivetrain.gearboxFriction", d.gearboxFrictionFraction(), -1e-12, 1, "between 0 and 1");
        for (Motor m : allMotors()) {
            MotorSpec sp = m.spec();
            String w = "Motor \"" + m.name() + "\" (" + sp.id() + ")";
            check(w + " ticksPerRev", sp.ticksPerRev(), 0, 1e7, "more than 0");
            check(w + " freeRpm", sp.freeRpm(), 0, 1e6, "more than 0");
            check(w + " stall torque", sp.stallTorqueNm(), 0, 1e4, "more than 0");
            check(w + " stallCurrentA", sp.stallCurrentA(), sp.freeCurrentA(), 1e4, "more than freeCurrentA");
            check(w + " freeCurrentA", sp.freeCurrentA(), -1e-12, 1e4, "0 or more");
        }
        List<OdometryPod> allPods = new ArrayList<>(odometryPods);
        for (Pinpoint pp : pinpoints) {
            allPods.add(pp.xPod());
            allPods.add(pp.yPod());
            check("Pinpoint \"" + pp.name() + "\" yawScaleError", pp.yawScaleError(), -0.05, 0.05, "between -0.05 and 0.05 (goBILDA calls more than 5% a bad device)");
        }
        for (OdometryPod p : allPods) {
            check("Odometry pod \"" + p.name() + "\" wheelDiameter", p.wheelRadius() * 2 / IN_M, 0.1, 12, "between 0.1 and 12 in");
            check("Odometry pod \"" + p.name() + "\" ticksPerRev", p.ticksPerRev(), 0, 1e7, "more than 0");
            check("Odometry pod \"" + p.name() + "\" diameterScale", p.diameterScale(), 0.8, 1.2, "between 0.8 and 1.2");
            check("Odometry pod \"" + p.name() + "\" mountAngleDeg", Math.toDegrees(p.mountAngle()), -15, 15, "between -15 and 15");
        }
        for (Otos o : otos) {
            check("OTOS \"" + o.name() + "\" heightMm", o.heightMm(), 0, 100, "between 0 and 100");
            check("OTOS \"" + o.name() + "\" linearScaleError", o.linearScaleError(), -0.5, 0.5, "between -0.5 and 0.5");
            check("OTOS \"" + o.name() + "\" angularScaleError", o.angularScaleError(), -0.2, 0.2, "between -0.2 and 0.2");
        }
        for (DistanceSensor ds : distanceSensors) {
            check("Distance sensor \"" + ds.name() + "\" maxRange", ds.maxRange(), 0, 100, "more than 0");
        }
        for (Servo sv : servos) {
            String w = (sv.continuous() ? "CR servo \"" : "Servo \"") + sv.name() + "\"";
            if (sv.continuous()) {
                check(w + " maxRpm", sv.crMaxRpm(), 0, 1e5, "more than 0");
            } else {
                check(w + " range", Math.toDegrees(sv.rangeRad()), 0, 3600, "more than 0 degrees");
                check(w + " secPer60Deg", sv.secPer60Deg(), 0, 100, "more than 0");
                if (sv.usAtRangeEnd() == sv.usAtRangeStart()) throw new ConfigException(w + ": usAtRangeStart and usAtRangeEnd must differ");
            }
        }
        for (Mechanism m : mechanisms) {
            String w = "Mechanism \"" + m.name() + "\"";
            if (m.gearRatio() == 0 || !Double.isFinite(m.gearRatio())) throw new ConfigException(w + " gearRatio must not be 0");
            check(w + " gearboxFriction", m.frictionFraction(), -1e-12, 1, "between 0 and 1");
            switch (m.type()) {
                case "slide" -> {
                    check(w + " travel", m.travel() / IN_M, 0, 1000, "more than 0");
                    check(w + " spoolDiameter", m.spoolRadius() * 2 / IN_M, 0, 100, "more than 0");
                    check(w + " mass", m.mass(), 0, 1000, "more than 0");
                }
                case "arm" -> {
                    if (!(m.maxAngle() > m.minAngle())) throw new ConfigException(w + ": max angle must be more than min angle");
                    if (m.startAngle() < m.minAngle() - 1e-9 || m.startAngle() > m.maxAngle() + 1e-9) {
                        throw new ConfigException(w + ": start angle must be between min and max");
                    }
                    check(w + " armLength", m.armLength() / IN_M, 0, 1000, "more than 0");
                    check(w + " mass", m.mass(), 0, 1000, "more than 0");
                }
                default -> check(w + " inertiaKgM2", m.inertia(), 0, 1e3, "more than 0");
            }
        }
        if (launcher != null) {
            check("launcher.efficiency", launcher.efficiency(), 0, 1 + 1e-12, "between 0 and 1");
            check("launcher.wheelDiameter", launcher.wheelRadius() * 2 / IN_M, 0, 100, "more than 0");
        }
        if (hopper != null) check("hopper.capacity", hopper.capacity(), 0, 1000, "at least 1");
        if (Math.max(length, width) > 40 * IN_M) {
            warnings.add("The robot is " + Math.round(Math.max(length, width) / IN_M) + " in long/wide; FTC robots must start within 18 in.");
        }
    }

    private void validate() {
        checkRanges();
        Set<String> driveMotors = new HashSet<>();
        for (Motor m : List.of(drivetrain.frontLeft(), drivetrain.frontRight(), drivetrain.backLeft(), drivetrain.backRight())) driveMotors.add(m.name());
        Set<String> motorNames = new HashSet<>();
        for (Motor m : allMotors()) {
            if (!motorNames.add(m.name())) throw new ConfigException("Motor name \"" + m.name() + "\" is used twice");
        }
        Set<String> mechanismMotors = new HashSet<>();
        for (Mechanism m : mechanisms) {
            if (m.motors().isEmpty()) throw new ConfigException("Mechanism \"" + m.name() + "\" has no motors");
            for (String name : m.motors()) {
                if (!motorNames.contains(name)) throw new ConfigException("Mechanism \"" + m.name() + "\" uses unknown motor \"" + name + "\"");
                if (driveMotors.contains(name)) throw new ConfigException("Mechanism \"" + m.name() + "\" uses drive motor \"" + name + "\"; list it under \"motors\" instead");
                if (!mechanismMotors.add(name)) throw new ConfigException("Motor \"" + name + "\" drives more than one mechanism");
            }
        }
        Set<String> mechanismNames = new HashSet<>();
        for (Mechanism m : mechanisms) mechanismNames.add(m.name());
        for (Motor m : motors) mechanismNames.add(m.name()); // motors without a mechanism get a "free" one of the same name
        if (launcher != null && !mechanismNames.contains(launcher.flywheel())) {
            throw new ConfigException("launcher.flywheel \"" + launcher.flywheel() + "\" is not a mechanism or motor");
        }
        for (Gripper g : grippers) {
            if (g.heightMechanism() != null && !mechanismNames.contains(g.heightMechanism())) {
                throw new ConfigException("Gripper \"" + g.name() + "\" uses unknown heightMechanism \"" + g.heightMechanism() + "\"");
            }
        }
        if (!colorSensors.isEmpty() && hopper == null) throw new ConfigException("colorSensors need a \"hopper\" to look at");
        Set<String> i2cNames = new HashSet<>();
        for (Pinpoint pp : pinpoints) {
            if (!i2cNames.add(pp.name()) || motorNames.contains(pp.name())) throw new ConfigException("Pinpoint name \"" + pp.name() + "\" is already used");
        }
        for (Otos o : otos) {
            if (!i2cNames.add(o.name()) || motorNames.contains(o.name())) throw new ConfigException("OTOS name \"" + o.name() + "\" is already used");
        }
        for (Switch s : switches) {
            if (!mechanismNames.contains(s.mechanism())) throw new ConfigException("Limit switch \"" + s.name() + "\" refers to unknown mechanism \"" + s.mechanism() + "\"");
        }
    }
}
