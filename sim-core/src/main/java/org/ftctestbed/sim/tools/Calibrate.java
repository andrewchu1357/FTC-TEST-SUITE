package org.ftctestbed.sim.tools;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.DcMotorSimple;

import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.config.ConfigException;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.MotorSpec;
import org.ftctestbed.sim.config.RobotConfig;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.ToDoubleFunction;

/**
 * Fits the simulator to a real robot. Replays the commands a calibration OpMode logged on the
 * robot (TeamCode's calibration package) in the simulator, compares what the simulated sensors
 * read with what the real ones did, and adjusts the robot config until they match:
 *
 *   drive log:     drive motors' real free speed and torque, gearbox friction, wheel grip, roller
 *                  drag, how hard the robot is to turn, and the battery's internal resistance
 *   mechanism log: the mechanism's inertia (flywheel, intake) or mass (lift, arm), its friction,
 *                  and its motors' real free speed
 *
 * Usage: ./gradlew calibrate --args="[--robot config/robot.jsonc] logs/calibration/drive-....csv [mechanism-....csv]"
 * Prints how well the simulator matched before and after, and writes the calibrated config next
 * to the robot file as NAME.calibrated.jsonc (the original isn't changed).
 */
public final class Calibrate {
    /** An empty field with the robot in the middle: room for every calibration move. */
    static final FieldConfig FIELD = FieldConfig.parse("{ \"robotStart\": { \"xIn\": 0, \"yIn\": 0, \"headingDeg\": 0 } }", "calibration field");
    private static final double IN = 0.0254;
    private static final String[] DRIVE_KEYS = {"frontLeft", "frontRight", "backLeft", "backRight"};

    /** One value the fit adjusts. log: searched in proportion (for things like inertia). */
    record Param(String name, double lo, double hi, double start, boolean log) {}

    /** How closely a config reproduces a log. The cost is what the fit minimizes. */
    public record Score(double cost, Map<String, Double> errors, Map<String, Double> behaviour) {}

    /** A finished fit: the values found, the calibrated config, and before/after scores. */
    public record Fit(String kind, Map<String, Double> values, Map<String, Double> starts, ConfigTree tree, Score before, Score after,
                      List<String> notes) {}

    private Calibrate() {}

    // ------------------------------------------------------------------ drive

    private static final int SPEED = 0, TORQUE = 1, GEARBOX = 2, GRIP = 3, ROLLER = 4, INERTIA = 5, OHMS = 6;

    /** Fits the drivetrain and battery to a "Calibrate Drive" log. */
    public static Fit fitDrive(ConfigTree base, CalibrationData log, ExecutorService pool) {
        RobotConfig cfg = base.toConfig();
        RobotConfig.Drivetrain d = cfg.drivetrain();
        Param[] params = {
                new Param("drive motor speed (x spec)", 0.8, 1.15, 1.0, false),
                new Param("drive motor torque (x spec)", 0.55, 1.35, 1.0, false),
                new Param("gearboxFriction", 0, 0.25, d.gearboxFrictionFraction(), false),
                new Param("wheelFriction", 0.3, 1.5, d.wheelFriction(), false),
                new Param("rollerFriction", 0, 0.6, d.rollerFriction(), false),
                new Param("yawInertiaKgM2", cfg.yawInertia() * 0.4, cfg.yawInertia() * 2.5, cfg.yawInertia(), true),
                new Param("battery internalResistanceOhms", 0.02, 0.35, cfg.batteryResistanceOhms(), false),
        };
        double startVolts = clampVolts(firstVolts(log));
        ToDoubleFunction<double[]> cost = x -> scoreDrive(applyDrive(base, cfg, x, startVolts), log).cost();
        double[] start = new double[params.length];
        for (int i = 0; i < params.length; i++) start[i] = params[i].start();
        Score before = scoreDrive(applyDrive(base, cfg, start, startVolts), log);
        double[] x = search(params, start, cost, pool);
        ConfigTree tree = applyDrive(base, cfg, x, startVolts);
        // The calibrated config keeps the user's battery voltage, not this run's.
        tree.object("battery").put("volts", cfg.batteryVolts());
        Score after = scoreDrive(applyDrive(base, cfg, x, startVolts), log);
        List<String> notes = boundNotes(params, x);
        double[] imu = imuStill(log);
        if (!Double.isNaN(imu[0])) {
            notes.add(String.format(Locale.US, "IMU while standing still: drifts about %.2f deg/min, jitters %.3f deg (only %.0f s of"
                    + " standing still here; run \"Calibrate IMU\" to measure and set it)", Math.abs(imu[0]), imu[1], imu[2]));
        }
        return new Fit("drive", values(params, x), values(params, start), tree, before, after, notes);
    }

    static ConfigTree applyDrive(ConfigTree base, RobotConfig cfg, double[] x, double startVolts) {
        ConfigTree t = base.copy();
        Map<String, Object> dt = t.object("drivetrain");
        dt.put("gearboxFriction", ConfigTree.round(x[GEARBOX], 4));
        dt.put("wheelFriction", ConfigTree.round(x[GRIP], 4));
        dt.put("rollerFriction", ConfigTree.round(x[ROLLER], 4));
        RobotConfig.Motor[] motors = {cfg.drivetrain().frontLeft(), cfg.drivetrain().frontRight(), cfg.drivetrain().backLeft(), cfg.drivetrain().backRight()};
        for (int i = 0; i < 4; i++) t.scaleMotor(t.object("drivetrain", "motors", DRIVE_KEYS[i]), motors[i].spec(), x[SPEED], x[TORQUE]);
        t.put("yawInertiaKgM2", ConfigTree.round(x[INERTIA], 5));
        Map<String, Object> battery = t.object("battery");
        battery.put("internalResistanceOhms", ConfigTree.round(x[OHMS], 4));
        battery.put("volts", startVolts);
        return t;
    }

    /** Replays a drive log open-loop and compares wheel speeds, turn rate and battery voltage. */
    public static Score scoreDrive(ConfigTree tree, CalibrationData log) {
        RobotConfig cfg = tree.toConfig();
        Simulation sim = new Simulation(cfg, FIELD);
        sim.moveRobot(0, 0, 0);
        String[] names = log.list("motors");
        String[] rev = log.list("reversed");
        DcMotorEx[] m = new DcMotorEx[names.length];
        for (int k = 0; k < m.length; k++) {
            m[k] = sim.hardwareMap.get(DcMotorEx.class, names[k]);
            m[k].setDirection(Boolean.parseBoolean(rev[k]) ? DcMotorSimple.Direction.REVERSE : DcMotorSimple.Direction.FORWARD);
        }
        String[] tags = {"lf", "rf", "lb", "rb"};
        int n = log.rows(), tCol = log.column("t");
        int[] pCol = new int[4], vCol = new int[4];
        for (int k = 0; k < 4; k++) {
            pCol[k] = log.column("p_" + tags[k]);
            vCol[k] = log.column("vel_" + tags[k]);
        }
        int rateCol = log.column("yaw_rate_dps"), voltCol = log.column("volts");
        double[][] simVel = new double[4][n];
        double[] simRate = new double[n], simVolts = new double[n];
        replay(sim, log, (i) -> {
            for (int k = 0; k < 4; k++) simVel[k][i] = m[k].getVelocity();
            simRate[i] = Math.toDegrees(sim.robotOmega());
            simVolts[i] = sim.batteryVolts();
        }, (i) -> {
            boolean brake = "B".equals(log.text(i, "zpb"));
            for (int k = 0; k < 4; k++) {
                m[k].setZeroPowerBehavior(brake ? DcMotor.ZeroPowerBehavior.BRAKE : DcMotor.ZeroPowerBehavior.FLOAT);
                m[k].setPower(log.get(i, pCol[k]));
            }
        });

        double cost = 0, wheelSq = 0, wheelPctSq = 0, rateSq = 0, voltSq = 0;
        double[] norm = new double[4];
        for (int k = 0; k < 4; k++) {
            for (int i = 0; i < n; i++) norm[k] = Math.max(norm[k], Math.abs(log.get(i, vCol[k])));
            norm[k] = Math.max(norm[k], 200);
        }
        double rateNorm = 60;
        for (int i = 0; i < n; i++) rateNorm = Math.max(rateNorm, Math.abs(log.get(i, rateCol)));
        double voltNorm = voltRange(log, voltCol);
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < 4; k++) {
                double e = simVel[k][i] - log.get(i, vCol[k]);
                wheelSq += e * e / 4;
                wheelPctSq += (e / norm[k]) * (e / norm[k]) / 4;
            }
            double er = simRate[i] - log.get(i, rateCol), ev = simVolts[i] - log.get(i, voltCol);
            rateSq += er * er;
            voltSq += ev * ev;
        }
        // Each channel on its own scale (share of its range in this run); the battery counts less,
        // as its reading is the noisiest and matters least.
        cost = (wheelPctSq + rateSq / (rateNorm * rateNorm) + BATTERY_WEIGHT * voltSq / (voltNorm * voltNorm)) / n;
        Map<String, Double> errors = new LinkedHashMap<>();
        errors.put("wheel speed (ticks/s)", Math.sqrt(wheelSq / n));
        errors.put("wheel speed (% of top)", 100 * Math.sqrt(wheelPctSq / n));
        errors.put("turn rate (deg/s)", Math.sqrt(rateSq / n));
        errors.put("battery (V)", Math.sqrt(voltSq / n));

        // Robot-level numbers a team recognizes: top speeds, turn rate, lowest battery.
        boolean[] wheel = new boolean[4];
        for (int k = 0; k < 4; k++) {
            final String name = names[k];
            wheel[k] = cfg.odometryPods().stream().noneMatch(p -> p.name().equals(name));
        }
        RobotConfig.Motor fl = cfg.drivetrain().frontLeft();
        double inPerTick = 2 * Math.PI * cfg.drivetrain().wheelRadius() / (fl.spec().ticksPerRev() * cfg.drivetrain().gearRatio()) / IN;
        Map<String, Double> behaviour = new LinkedHashMap<>();
        behaviour.put("top speed forward (in/s)", topSpeed(log, simVel, wheel, new double[]{1, 1, 1, 1}, "forward", inPerTick, true));
        behaviour.put("top speed strafing (in/s)", topSpeed(log, simVel, wheel, new double[]{-1, 1, 1, -1}, "strafe", inPerTick, true));
        double turnReal = 0, turnSim = 0, lowReal = 99, lowSim = 99;
        for (int i = 0; i < n; i++) {
            if (log.text(i, "segment").equals("turn")) {
                turnReal = Math.max(turnReal, Math.abs(log.get(i, rateCol)));
                turnSim = Math.max(turnSim, Math.abs(simRate[i]));
            }
            lowReal = Math.min(lowReal, log.get(i, voltCol));
            lowSim = Math.min(lowSim, simVolts[i]);
        }
        behaviour.put("top speed forward (in/s), real", topSpeed(log, simVel, wheel, new double[]{1, 1, 1, 1}, "forward", inPerTick, false));
        behaviour.put("top speed strafing (in/s), real", topSpeed(log, simVel, wheel, new double[]{-1, 1, 1, -1}, "strafe", inPerTick, false));
        behaviour.put("top turn rate (deg/s)", turnSim);
        behaviour.put("top turn rate (deg/s), real", turnReal);
        behaviour.put("lowest battery (V)", lowSim);
        behaviour.put("lowest battery (V), real", lowReal);
        return new Score(cost, errors, behaviour);
    }

    private static double topSpeed(CalibrationData log, double[][] simVel, boolean[] wheel, double[] pattern, String segment,
                                   double inPerTick, boolean sim) {
        String[] tags = {"lf", "rf", "lb", "rb"};
        double best = 0;
        for (int i = 0; i < log.rows(); i++) {
            if (!log.text(i, "segment").equals(segment)) continue;
            double sum = 0;
            int count = 0;
            for (int k = 0; k < 4; k++) {
                if (!wheel[k]) continue;
                sum += pattern[k] * (sim ? simVel[k][i] : log.get(i, "vel_" + tags[k]));
                count++;
            }
            if (count > 0) best = Math.max(best, sum / count * inPerTick);
        }
        return best;
    }

    /** IMU drift (deg/min), jitter (deg) and seconds of data, from the log's standing-still periods. */
    static double[] imuStill(CalibrationData log) {
        int tCol = log.column("t"), yCol = log.column("yaw_deg");
        if (yCol < 0) return new double[]{Double.NaN, Double.NaN, 0};
        // Each still period: fit a line, keep slope and residuals.
        double slopeSum = 0, weight = 0, resSq = 0, seconds = 0;
        int resN = 0, i = 0, n = log.rows();
        while (i < n) {
            if (!log.text(i, "segment").equals("still")) { i++; continue; }
            int j = i;
            while (j < n && log.text(j, "segment").equals("still")) j++;
            int from = i + (j - i) / 4; // let the robot settle first
            if (j - from >= 20) {
                double st = 0, sy = 0, stt = 0, sty = 0;
                int m = j - from;
                for (int k = from; k < j; k++) {
                    double t = log.get(k, tCol), y = log.get(k, yCol);
                    st += t; sy += y; stt += t * t; sty += t * y;
                }
                double slope = (m * sty - st * sy) / (m * stt - st * st);
                double icpt = (sy - slope * st) / m;
                double dur = log.get(j - 1, tCol) - log.get(from, tCol);
                slopeSum += slope * dur;
                weight += dur;
                seconds += dur;
                for (int k = from; k < j; k++) {
                    double r = log.get(k, yCol) - (icpt + slope * log.get(k, tCol));
                    resSq += r * r;
                    resN++;
                }
            }
            i = j;
        }
        if (weight <= 0) return new double[]{Double.NaN, Double.NaN, 0};
        return new double[]{slopeSum / weight * 60, Math.sqrt(resSq / resN), seconds};
    }

    // ------------------------------------------------------------------ IMU

    /** Sets the simulated IMU's drift and jitter from a "Calibrate IMU" log (a minute standing still). */
    public static Fit fitImu(ConfigTree base, CalibrationData log) {
        double[] imu = imuStill(log);
        if (Double.isNaN(imu[0]) || imu[2] < 20) {
            throw new ConfigException(log.file + ": needs at least 20 s of standing still (got " + Math.round(imu[2]) + " s)");
        }
        ConfigTree tree = base.copy();
        RobotConfig cfg = base.toConfig();
        double drift = Math.abs(imu[0]), noise = Math.max(imu[1], 0.002);
        tree.object("imu").put("driftDegPerMin", ConfigTree.round(drift, 3));
        tree.object("imu").put("noiseDeg", ConfigTree.round(noise, 4));
        Map<String, Double> values = new LinkedHashMap<>(), starts = new LinkedHashMap<>();
        values.put("imu driftDegPerMin", drift);
        values.put("imu noiseDeg", noise);
        starts.put("imu driftDegPerMin", Math.toDegrees(cfg.imu().driftRadPerSec()) * 60);
        starts.put("imu noiseDeg", Math.toDegrees(cfg.imu().noiseRad()));
        Score none = new Score(0, Map.of(), Map.of());
        List<String> notes = new ArrayList<>();
        notes.add(String.format(Locale.US, "From %.0f s standing still. Heading turned %.2f deg in that time.", imu[2], imu[0] * imu[2] / 60));
        return new Fit("imu", values, starts, tree, none, none, notes);
    }

    // ------------------------------------------------------------------ turn

    /**
     * Sets the simulated IMU's scale error from a "Calibrate Turn" log. Each spin really was a
     * whole number of turns (the driver lined the robot back up with a tile seam), so whatever
     * the sensor reported beyond that is scale error. Drift adds the same way to a
     * counter-clockwise and a clockwise spin while scale error flips sign, so averaging the two
     * directions cancels it.
     */
    public static Fit fitTurn(ConfigTree base, CalibrationData log) {
        int tCol = log.column("t"), yCol = log.column("yaw_deg");
        if (yCol < 0) throw new ConfigException(log.file + ": not a Calibrate Turn log (no yaw_deg column)");
        List<int[]> blocks = new ArrayList<>(); // {from, to} runs of one segment
        for (int i = 0, n = log.rows(); i < n; ) {
            int j = i;
            while (j < n && log.text(j, "segment").equals(log.text(i, "segment"))) j++;
            blocks.add(new int[]{i, j});
            i = j;
        }
        List<double[]> spins = new ArrayList<>(); // {measured deg, true deg}
        List<String> notes = new ArrayList<>();
        int expected = (int) Math.round(Double.parseDouble(log.info.getOrDefault("turns", "0")));
        for (int b = 1; b + 2 < blocks.size(); b++) {
            if (!segment(log, blocks.get(b)).equals("spin")) continue;
            int[] before = blocks.get(b - 1), after = blocks.get(b + 2);
            if (!segment(log, blocks.get(b + 1)).equals("align") || !segment(log, after).equals("aligned")) continue;
            double start = meanYaw(log, before, tCol, yCol, 0.4), end = meanYaw(log, after, tCol, yCol, 0.4);
            double measured = end - start, turns = Math.round(measured / 360);
            if (turns == 0) continue;
            if (expected > 0 && Math.abs(turns) != expected) {
                notes.add(String.format(Locale.US, "A spin came out as %.0f turns, not %d: was the robot lined up a whole turn off?", Math.abs(turns), expected));
            }
            spins.add(new double[]{measured, turns * 360});
        }
        if (spins.isEmpty()) {
            throw new ConfigException(log.file + ": no complete spin (spin, line up, press A) in this log");
        }
        double sum = 0;
        boolean ccw = false, cw = false;
        for (double[] s : spins) {
            double scale = s[0] / s[1] - 1;
            sum += scale;
            ccw |= s[1] > 0;
            cw |= s[1] < 0;
            notes.add(String.format(Locale.US, "%.0f turns %s: the sensor said %.2f deg (%+.2f deg, %+.3f%%).",
                    Math.abs(s[1] / 360), s[1] > 0 ? "counter-clockwise" : "clockwise", Math.abs(s[0]),
                    Math.abs(s[0]) - Math.abs(s[1]), scale * 100));
        }
        double scale = sum / spins.size();
        if (!(ccw && cw)) notes.add("Only one direction was measured, so IMU drift isn't cancelled out. Run both if you can.");
        ConfigTree tree = base.copy();
        RobotConfig cfg = base.toConfig();
        String sensor = log.info.getOrDefault("heading", "imu"), device = log.info.getOrDefault("device", "");
        Map<String, Double> values = new LinkedHashMap<>(), starts = new LinkedHashMap<>();
        switch (sensor) {
            case "pinpoint" -> {
                // Measured with the Pinpoint's factory yaw scalar: what's left is yawScaleError.
                RobotConfig.Pinpoint pp = cfg.pinpoints().stream().filter(p -> p.name().equals(device)).findFirst()
                        .orElseThrow(() -> new ConfigException(log.file + ": the robot has no Pinpoint named \"" + device + "\""));
                tree.find("pinpoints", "name", device).put("yawScaleError", ConfigTree.round(scale, 5));
                values.put("pinpoint yawScaleError", scale);
                starts.put("pinpoint yawScaleError", pp.yawScaleError());
                notes.add(String.format(Locale.US, "On the robot, setYawScalar(%.5f) would cancel it (goBILDA advises keeping the factory value unless it's clearly off).",
                        1 / (1 + scale)));
            }
            case "otos" -> {
                RobotConfig.Otos o = cfg.otos().stream().filter(x -> x.name().equals(device)).findFirst()
                        .orElseThrow(() -> new ConfigException(log.file + ": the robot has no OTOS named \"" + device + "\""));
                tree.find("otos", "name", device).put("angularScaleError", ConfigTree.round(scale, 5));
                values.put("otos angularScaleError", scale);
                starts.put("otos angularScaleError", o.angularScaleError());
                notes.add(String.format(Locale.US, "On the robot, setAngularScalar(%.3f) corrects it.", 1 / (1 + scale)));
            }
            default -> {
                tree.object("imu").put("scaleError", ConfigTree.round(scale, 5));
                values.put("imu scaleError", scale);
                starts.put("imu scaleError", cfg.imu().scaleError());
            }
        }
        Score none = new Score(0, Map.of(), Map.of());
        return new Fit("turn", values, starts, tree, none, none, notes);
    }

    private static String segment(CalibrationData log, int[] block) {
        return log.text(block[0], "segment");
    }

    /** Mean heading over the last `seconds` of a block (the robot standing still). */
    private static double meanYaw(CalibrationData log, int[] block, int tCol, int yCol, double seconds) {
        double tEnd = log.get(block[1] - 1, tCol), sum = 0;
        int n = 0;
        for (int k = block[1] - 1; k >= block[0] && tEnd - log.get(k, tCol) <= seconds; k--) {
            sum += log.get(k, yCol);
            n++;
        }
        return sum / n;
    }

    // ------------------------------------------------------------------ mechanism

    /** Fits one mechanism (and its motors' free speed) to a "Calibrate Mechanism" log. */
    @SuppressWarnings("unchecked")
    public static Fit fitMechanism(ConfigTree base, CalibrationData log, ExecutorService pool) {
        String[] names = log.list("motors");
        if (names.length == 0) throw new ConfigException(log.file + ": no motors listed");
        RobotConfig cfg = base.toConfig();
        ConfigTree prepared = base.copy();
        Map<String, Object> mech = prepared.mechanismOf(names[0]);
        if (mech == null) {
            // A motor without a mechanism spins freely; give it one so its inertia can be set.
            mech = new LinkedHashMap<>();
            mech.put("name", names[0]);
            mech.put("type", "free");
            mech.put("motors", new ArrayList<>(List.of(names)));
            mech.put("inertiaKgM2", 0.0005);
            mech.put("gearboxFriction", 0.06);
            prepared.addToList("mechanisms", mech);
        }
        String type = String.valueOf(mech.getOrDefault("type", "free"));
        String mechName = String.valueOf(mech.get("name"));
        RobotConfig.Mechanism def = prepared.toConfig().mechanisms().stream().filter(x -> x.name().equals(mechName)).findFirst().orElseThrow();
        boolean free = type.equals("free");
        Param[] params = {
                free ? new Param("inertiaKgM2", def.inertia() * 0.2, def.inertia() * 5, def.inertia(), true)
                        : new Param("mass (kg)", def.mass() * 0.3, def.mass() * 4, def.mass(), true),
                new Param("gearboxFriction", 0, 0.3, def.frictionFraction(), false),
                new Param("motor speed (x spec)", 0.8, 1.15, 1.0, false),
        };
        Map<String, MotorSpec> specs = new LinkedHashMap<>();
        for (String name : names) {
            RobotConfig.Motor motor = cfg.motors().stream().filter(x -> x.name().equals(name)).findFirst()
                    .orElseThrow(() -> new ConfigException("Motor \"" + name + "\" from the log isn't in " + base.file));
            specs.put(name, motor.spec());
        }
        double startVolts = clampVolts(firstVolts(log));
        ToDoubleFunction<double[]> cost = x -> scoreMechanism(applyMechanism(prepared, mechName, free, specs, x, startVolts), log).cost();
        double[] start = {params[0].start(), params[1].start(), 1.0};
        Score before = scoreMechanism(applyMechanism(prepared, mechName, free, specs, start, startVolts), log);
        double[] x = search(params, start, cost, pool);
        Score after = scoreMechanism(applyMechanism(prepared, mechName, free, specs, x, startVolts), log);
        ConfigTree tree = applyMechanism(prepared, mechName, free, specs, x, startVolts);
        tree.object("battery").put("volts", cfg.batteryVolts());
        List<String> notes = boundNotes(params, x);
        notes.add(0, "Mechanism \"" + mechName + "\" (" + type + "), motors " + String.join(", ", names));
        return new Fit("mechanism", values(params, x), values(params, start), tree, before, after, notes);
    }

    private static ConfigTree applyMechanism(ConfigTree base, String mechName, boolean free, Map<String, MotorSpec> specs,
                                             double[] x, double startVolts) {
        ConfigTree t = base.copy();
        Map<String, Object> mech = t.find("mechanisms", "name", mechName);
        if (free) mech.put("inertiaKgM2", ConfigTree.round(x[0], 6));
        else ConfigTree.setMassKg(mech, "mass", x[0]);
        mech.put("gearboxFriction", ConfigTree.round(x[1], 4));
        specs.forEach((name, spec) -> t.scaleMotor(t.find("motors", "name", name), spec, x[2], 1.0));
        t.object("battery").put("volts", startVolts);
        return t;
    }

    public static Score scoreMechanism(ConfigTree tree, CalibrationData log) {
        Simulation sim = new Simulation(tree.toConfig(), FIELD);
        String[] names = log.list("motors"), rev = log.list("reversed");
        int k = names.length, n = log.rows();
        DcMotorEx[] m = new DcMotorEx[k];
        int[] vCol = new int[k];
        for (int j = 0; j < k; j++) {
            m[j] = sim.hardwareMap.get(DcMotorEx.class, names[j]);
            m[j].setDirection(Boolean.parseBoolean(rev[j]) ? DcMotorSimple.Direction.REVERSE : DcMotorSimple.Direction.FORWARD);
            vCol[j] = log.column("vel_" + j);
        }
        int pCol = log.column("power"), voltCol = log.column("volts");
        double[][] simVel = new double[k][n];
        double[] simVolts = new double[n];
        replay(sim, log, (i) -> {
            for (int j = 0; j < k; j++) simVel[j][i] = m[j].getVelocity();
            simVolts[i] = sim.batteryVolts();
        }, (i) -> {
            boolean brake = "B".equals(log.text(i, "zpb"));
            for (int j = 0; j < k; j++) {
                m[j].setZeroPowerBehavior(brake ? DcMotor.ZeroPowerBehavior.BRAKE : DcMotor.ZeroPowerBehavior.FLOAT);
                m[j].setPower(log.get(i, pCol));
            }
        });
        double velPctSq = 0, velSq = 0, voltSq = 0, topReal = 0, topSim = 0;
        double[] norm = new double[k];
        for (int j = 0; j < k; j++) {
            for (int i = 0; i < n; i++) norm[j] = Math.max(norm[j], Math.abs(log.get(i, vCol[j])));
            norm[j] = Math.max(norm[j], 50);
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < k; j++) {
                double e = simVel[j][i] - log.get(i, vCol[j]);
                velSq += e * e / k;
                velPctSq += (e / norm[j]) * (e / norm[j]) / k;
                topReal = Math.max(topReal, Math.abs(log.get(i, vCol[j])));
                topSim = Math.max(topSim, Math.abs(simVel[j][i]));
            }
            double ev = simVolts[i] - log.get(i, voltCol);
            voltSq += ev * ev;
        }
        Map<String, Double> errors = new LinkedHashMap<>();
        errors.put("motor speed (ticks/s)", Math.sqrt(velSq / n));
        errors.put("motor speed (% of top)", 100 * Math.sqrt(velPctSq / n));
        errors.put("battery (V)", Math.sqrt(voltSq / n));
        Map<String, Double> behaviour = new LinkedHashMap<>();
        behaviour.put("top motor speed (ticks/s)", topSim);
        behaviour.put("top motor speed (ticks/s), real", topReal);
        double voltNorm = voltRange(log, voltCol);
        return new Score((velPctSq + BATTERY_WEIGHT * voltSq / (voltNorm * voltNorm)) / n, errors, behaviour);
    }

    // ------------------------------------------------------------------ shared

    private interface RowAction { void at(int row); }

    /**
     * Steps the simulation through the log's timeline: at each row's time "t" read the simulated
     * sensors (as the OpMode read the real ones), and at "t_cmd" - when the OpMode's new powers
     * had reached the hub, a few ms later - apply that row's commands.
     */
    private static void replay(Simulation sim, CalibrationData log, RowAction read, RowAction command) {
        int tCol = log.column("t"), cCol = log.column("t_cmd");
        double t0 = log.get(0, tCol);
        long[] stepsDone = {0};
        java.util.function.DoubleConsumer advanceTo = time -> {
            long target = Math.round((time - t0) / Simulation.DT);
            while (stepsDone[0] < target) {
                sim.step();
                stepsDone[0]++;
            }
        };
        for (int i = 0; i < log.rows(); i++) {
            advanceTo.accept(log.get(i, tCol));
            read.at(i);
            if (cCol >= 0) advanceTo.accept(log.get(i, cCol));
            command.at(i);
        }
        advanceTo.accept(log.get(log.rows() - 1, tCol) + 0.01);
    }

    /**
     * Finds the values that best reproduce the log. The values pull on each other (a weaker
     * motor with more grip can look like a stronger one with less), so a simple one-at-a-time
     * search can stop in the wrong place. Instead: try 400 spread-out combinations across the
     * plausible ranges, start a Nelder-Mead downhill search from the six best, and polish the
     * winner one value at a time. Each replay takes a few ms, and they run in parallel.
     */
    static double[] search(Param[] params, double[] start, ToDoubleFunction<double[]> cost, ExecutorService pool) {
        int d = params.length;
        ToDoubleFunction<double[]> costU = u -> cost.applyAsDouble(fromUnit(params, u));
        List<double[]> samples = new ArrayList<>();
        samples.add(toUnit(params, start));
        java.util.Random rnd = new java.util.Random(17);
        for (int i = 0; i < 400; i++) {
            double[] u = new double[d];
            for (int k = 0; k < d; k++) u[k] = (halton(i + 1, PRIMES[k]) + rnd.nextDouble() * 0.02) % 1.0;
            samples.add(u);
        }
        double[] scores = evaluateAll(samples, costU, pool);
        Integer[] order = new Integer[samples.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(scores[a], scores[b]));

        List<Future<double[]>> runs = new ArrayList<>();
        for (int r = 0; r < Math.min(6, order.length); r++) {
            double[] u0 = samples.get(order[r]);
            runs.add(pool.submit(() -> nelderMead(costU, u0, 0.08, 500)));
        }
        double[] bestU = samples.get(order[0]);
        double best = scores[order[0]];
        for (Future<double[]> run : runs) {
            double[] u;
            try {
                u = run.get();
            } catch (Exception e) {
                throw new IllegalStateException("Simulation failed during calibration", e);
            }
            double c = costU.applyAsDouble(u);
            if (c < best) {
                best = c;
                bestU = u;
            }
        }
        // Polish: each value in turn, 8 settings in parallel, twice narrower each round.
        double[] u = bestU.clone();
        for (double span : new double[]{0.04, 0.012, 0.004}) {
            for (int k = 0; k < d; k++) {
                List<double[]> cand = new ArrayList<>();
                for (int j = -4; j <= 4; j++) {
                    if (j == 0) continue;
                    double[] c = u.clone();
                    c[k] = Math.max(0, Math.min(1, u[k] + span * j / 4));
                    cand.add(c);
                }
                double[] sc = evaluateAll(cand, costU, pool);
                for (int j = 0; j < cand.size(); j++) {
                    if (sc[j] < best) {
                        best = sc[j];
                        u = cand.get(j);
                    }
                }
            }
        }
        return fromUnit(params, u);
    }

    private static final int[] PRIMES = {2, 3, 5, 7, 11, 13, 17, 19, 23, 29};

    /** Halton low-discrepancy sequence: evenly spread samples in [0, 1). */
    private static double halton(int index, int base) {
        double f = 1, r = 0;
        while (index > 0) {
            f /= base;
            r += f * (index % base);
            index /= base;
        }
        return r;
    }

    private static double[] evaluateAll(List<double[]> points, ToDoubleFunction<double[]> cost, ExecutorService pool) {
        List<Future<Double>> results = new ArrayList<>();
        for (double[] p : points) results.add(pool.submit(() -> cost.applyAsDouble(p)));
        double[] out = new double[points.size()];
        for (int i = 0; i < out.length; i++) {
            try {
                out[i] = results.get(i).get();
            } catch (Exception e) {
                throw new IllegalStateException("Simulation failed during calibration", e);
            }
        }
        return out;
    }

    /** Values scaled to 0..1 across each one's plausible range (proportionally for log values). */
    private static double[] toUnit(Param[] params, double[] x) {
        double[] u = new double[x.length];
        for (int k = 0; k < x.length; k++) {
            Param p = params[k];
            u[k] = p.log() ? Math.log(x[k] / p.lo()) / Math.log(p.hi() / p.lo()) : (x[k] - p.lo()) / (p.hi() - p.lo());
            u[k] = Math.max(0, Math.min(1, u[k]));
        }
        return u;
    }

    private static double[] fromUnit(Param[] params, double[] u) {
        double[] x = new double[u.length];
        for (int k = 0; k < u.length; k++) {
            Param p = params[k];
            double v = Math.max(0, Math.min(1, u[k]));
            x[k] = p.log() ? p.lo() * Math.exp(v * Math.log(p.hi() / p.lo())) : p.lo() + v * (p.hi() - p.lo());
        }
        return x;
    }

    /** Nelder-Mead downhill simplex in the unit cube (points are clamped into it). */
    static double[] nelderMead(ToDoubleFunction<double[]> f, double[] x0, double step, int maxEvals) {
        int n = x0.length;
        double[][] s = new double[n + 1][];
        double[] fs = new double[n + 1];
        s[0] = x0.clone();
        for (int i = 0; i < n; i++) {
            double[] v = x0.clone();
            v[i] = v[i] + step <= 1 ? v[i] + step : v[i] - step;
            s[i + 1] = v;
        }
        int evals = 0;
        for (int i = 0; i <= n; i++) { fs[i] = f.applyAsDouble(clampUnit(s[i])); evals++; }
        while (evals < maxEvals) {
            Integer[] idx = new Integer[n + 1];
            for (int i = 0; i <= n; i++) idx[i] = i;
            final double[] fv = fs;
            java.util.Arrays.sort(idx, (a, b) -> Double.compare(fv[a], fv[b]));
            double[][] ss = new double[n + 1][];
            double[] ff = new double[n + 1];
            for (int i = 0; i <= n; i++) { ss[i] = s[idx[i]]; ff[i] = fs[idx[i]]; }
            s = ss;
            fs = ff;
            double spread = 0;
            for (int i = 1; i <= n; i++) for (int k = 0; k < n; k++) spread = Math.max(spread, Math.abs(s[i][k] - s[0][k]));
            if (spread < 1e-4) break;
            double[] c = new double[n];
            for (int i = 0; i < n; i++) for (int k = 0; k < n; k++) c[k] += s[i][k] / n;
            double[] xr = clampUnit(lerp(c, s[n], -1));
            double fr = f.applyAsDouble(xr); evals++;
            if (fr < fs[0]) {
                double[] xe = clampUnit(lerp(c, s[n], -2));
                double fe = f.applyAsDouble(xe); evals++;
                if (fe < fr) { s[n] = xe; fs[n] = fe; } else { s[n] = xr; fs[n] = fr; }
            } else if (fr < fs[n - 1]) {
                s[n] = xr;
                fs[n] = fr;
            } else {
                double[] xc = clampUnit(fr < fs[n] ? lerp(c, s[n], -0.5) : lerp(c, s[n], 0.5));
                double fc = f.applyAsDouble(xc); evals++;
                if (fc < Math.min(fr, fs[n])) {
                    s[n] = xc;
                    fs[n] = fc;
                } else {
                    for (int i = 1; i <= n; i++) {
                        s[i] = clampUnit(lerp(s[0], s[i], 0.5));
                        fs[i] = f.applyAsDouble(s[i]); evals++;
                    }
                }
            }
        }
        int best = 0;
        for (int i = 1; i <= n; i++) if (fs[i] < fs[best]) best = i;
        return clampUnit(s[best]);
    }

    /** c + t * (p - c) */
    private static double[] lerp(double[] c, double[] p, double t) {
        double[] out = new double[c.length];
        for (int k = 0; k < c.length; k++) out[k] = c[k] + t * (p[k] - c[k]);
        return out;
    }

    private static double[] clampUnit(double[] v) {
        double[] out = v.clone();
        for (int k = 0; k < out.length; k++) out[k] = Math.max(0, Math.min(1, out[k]));
        return out;
    }

    private static Map<String, Double> values(Param[] params, double[] x) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < params.length; i++) m.put(params[i].name(), x[i]);
        return m;
    }

    private static List<String> boundNotes(Param[] params, double[] x) {
        List<String> notes = new ArrayList<>();
        for (int i = 0; i < params.length; i++) {
            Param p = params[i];
            double range = p.log() ? Math.log(p.hi() / p.lo()) : p.hi() - p.lo();
            double fromLo = p.log() ? Math.log(x[i] / p.lo()) : x[i] - p.lo();
            double fromHi = p.log() ? Math.log(p.hi() / x[i]) : p.hi() - x[i];
            if (Math.min(fromLo, fromHi) < 0.02 * range && !(p.lo() == 0 && x[i] < 1e-9)) {
                notes.add("CHECK: " + p.name() + " ended at the edge of what's plausible (" + fmt(x[i]) + "). Something in robot.jsonc"
                        + " may not match the robot: weight, wheel size, motor types, or motor directions.");
            }
        }
        return notes;
    }

    private static final double BATTERY_WEIGHT = 0.3;

    /** How far the battery voltage moved during the run (at least 0.5 V). */
    private static double voltRange(CalibrationData log, int col) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 0; i < log.rows(); i++) {
            lo = Math.min(lo, log.get(i, col));
            hi = Math.max(hi, log.get(i, col));
        }
        return Math.max(0.5, hi - lo);
    }

    private static double firstVolts(CalibrationData log) {
        int c = log.column("volts");
        double sum = 0;
        int n = Math.min(20, log.rows());
        for (int i = 0; i < n; i++) sum += log.get(i, c);
        return sum / n;
    }

    private static double clampVolts(double v) {
        return Math.max(6.5, Math.min(19.5, v));
    }

    private static String fmt(double v) {
        return Math.abs(v) >= 100 ? String.format(Locale.US, "%.0f", v)
                : Math.abs(v) >= 1 ? String.format(Locale.US, "%.2f", v) : String.format(Locale.US, "%.4f", v);
    }

    // ------------------------------------------------------------------ report & CLI

    public static void report(Fit fit, PrintStream out) {
        out.printf(Locale.US, "%n== %s ==%n", switch (fit.kind()) {
            case "drive" -> "Drivetrain";
            case "imu" -> "IMU";
            case "turn" -> "Turn accuracy";
            default -> "Mechanism";
        });
        if (fit.kind().equals("imu") || fit.kind().equals("turn")) {
            for (Map.Entry<String, Double> e : fit.values().entrySet()) {
                out.printf(Locale.US, "  %-34s %10s  (was %s)%n", e.getKey(), fmt(e.getValue()), fmt(fit.starts().get(e.getKey())));
            }
            for (String note : fit.notes()) out.println("  " + note);
            return;
        }
        out.printf(Locale.US, "%-34s %12s %12s %12s%n", "", "real robot", "sim before", "sim after");
        for (Map.Entry<String, Double> e : fit.after().behaviour().entrySet()) {
            if (e.getKey().endsWith(", real")) continue;
            Double real = fit.after().behaviour().get(e.getKey() + ", real");
            out.printf(Locale.US, "%-34s %12s %12s %12s%n", e.getKey(), real != null ? fmt(real) : "",
                    fmt(fit.before().behaviour().get(e.getKey())), fmt(e.getValue()));
        }
        out.println();
        out.printf(Locale.US, "%-34s %12s %12s %12s%n", "average mismatch", "", "before", "after");
        for (Map.Entry<String, Double> e : fit.after().errors().entrySet()) {
            out.printf(Locale.US, "%-34s %12s %12s %12s%n", e.getKey(), "", fmt(fit.before().errors().get(e.getKey())), fmt(e.getValue()));
        }
        out.println();
        out.println("Fitted values:");
        for (Map.Entry<String, Double> e : fit.values().entrySet()) {
            out.printf(Locale.US, "  %-34s %10s  (was %s)%n", e.getKey(), fmt(e.getValue()), fmt(fit.starts().get(e.getKey())));
        }
        for (String note : fit.notes()) out.println("  " + note);
    }

    public static void main(String[] args) throws Exception {
        Path robot = Path.of("config/robot.jsonc");
        List<Path> logs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--robot") && i + 1 < args.length) robot = Path.of(args[++i]);
            else logs.add(Path.of(args[i]));
        }
        if (logs.isEmpty()) {
            System.out.println("Usage: ./gradlew calibrate --args=\"[--robot config/robot.jsonc] logs/calibration/drive-....csv [mechanism-....csv]\"");
            System.out.println("Run the \"Calibrate Drive\" (and Mechanism, IMU, Turn) OpModes on the robot first; see the README.");
            System.exit(1);
        }
        ConfigTree tree = ConfigTree.load(robot);
        System.out.println("Calibrating " + robot + " with " + logs.size() + " log(s). This takes a minute...");
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        try {
            // Drive logs first: they set the battery resistance the mechanism fits then use.
            logs.sort((a, b) -> Boolean.compare(!a.getFileName().toString().startsWith("drive"), !b.getFileName().toString().startsWith("drive")));
            for (Path p : logs) {
                CalibrationData data = CalibrationData.read(p);
                System.out.printf(Locale.US, "%n%s: %d samples, %.1f s%n", p, data.rows(), data.get(data.rows() - 1, "t"));
                Fit fit = switch (data.kind) {
                    case "drive" -> fitDrive(tree, data, pool);
                    case "mechanism" -> fitMechanism(tree, data, pool);
                    case "imu" -> fitImu(tree, data);
                    case "turn" -> fitTurn(tree, data);
                    default -> throw new ConfigException(p + ": unknown calibration log kind \"" + data.kind + "\"");
                };
                report(fit, System.out);
                tree = fit.tree();
            }
        } finally {
            pool.shutdown();
        }
        String name = robot.getFileName().toString().replaceFirst("\\.jsonc?$", "");
        Path outFile = robot.resolveSibling(name + ".calibrated.jsonc");
        Files.writeString(outFile, "// " + robot.getFileName() + " calibrated to the real robot by ./gradlew calibrate. Comments aren't kept;\n"
                + "// copy the values you want into " + robot.getFileName() + ", or open this file with Load robot.\n" + tree.toJson() + "\n");
        System.out.println("\nWrote " + outFile + " (" + robot + " itself is unchanged).");
    }
}
