package org.ftctestbed.viewer;

import org.ftctestbed.sim.PoseLogger;
import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.Snapshot;
import org.ftctestbed.sim.code.RobotCode;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRegistry;
import org.ftctestbed.sim.opmode.OpModeRunner;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs one OpMode with no window, as fast as the robot code allows (lockstep), and prints
 * where the robot ended up. Exit code 1 if the OpMode crashed. Options:
 *   --opmode NAME      OpMode name as shown on the Driver Station
 *   --match            play a full match instead: --auto NAME and/or --teleop NAME
 *   --start NAME       start position from field.jsonc (e.g. "Blue Wall")
 *   --seconds N        stop after N seconds of simulated time (default 30)
 *   --realtime         run at wall-clock speed instead of lockstep
 *   --log FILE.csv     write pose/motor log
 *   --snapshot FILE.png  save an image of the field at the end, with the robot's path
 *   --driver-view      draw the snapshot as the robot's alliance drivers see the field
 *   --robot FILE / --field FILE   alternate config files (a robot package's code is compiled first)
 */
public final class HeadlessMain {
    private static boolean driverView;

    private static final double IN = 0.0254;

    public static void main(String[] args) throws Exception {
        org.ftctestbed.viewer.platform.Win32.utf8Output(); // Windows: the score's symbols as themselves, not ?
        org.ftctestbed.viewer.platform.AppHome.ensureStarterFiles(); // a packaged app's ftc-sim.exe: the starter files, the first time
        try {
            run(args);
        } catch (ArrayIndexOutOfBoundsException e) {
            fail("Option " + args[args.length - 1] + " needs a value");
        } catch (NumberFormatException e) {
            fail("Expected a number: " + e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException | org.ftctestbed.sim.config.ConfigException e) {
            fail(e.getMessage());
        }
    }

    private static void run(String[] args) throws Exception {
        String opModeName = null, autoName = null, teleopName = null, startName = null;
        boolean matchMode = false;
        double seconds = 30;
        boolean realtime = false;
        String logFile = null, snapshotFile = null;
        Path robotFile = Path.of("config/robot.jsonc"), fieldFile = Path.of("config/field.jsonc");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--opmode" -> opModeName = args[++i];
                case "--match" -> matchMode = true;
                case "--auto" -> autoName = args[++i];
                case "--teleop" -> teleopName = args[++i];
                case "--start" -> startName = args[++i];
                case "--seconds" -> seconds = Double.parseDouble(args[++i]);
                case "--realtime" -> realtime = true;
                case "--log" -> logFile = args[++i];
                case "--snapshot" -> snapshotFile = args[++i];
                case "--driver-view" -> driverView = true;
                case "--robot" -> robotFile = Path.of(args[++i]);
                case "--field" -> fieldFile = Path.of(args[++i]);
                default -> fail("Unknown option " + args[i]);
            }
        }

        OpModeRegistry.Result found = opModesFor(robotFile);
        if (matchMode) {
            runMatch(robotFile, fieldFile, found, autoName, teleopName, startName, logFile, snapshotFile);
            return;
        }
        if (opModeName == null) {
            System.out.println("Usage: ./gradlew headless --args=\"--opmode 'NAME' [--seconds N] [--log out.csv] [--snapshot out.png]\"");
            System.out.println("OpModes:");
            for (OpModeInfo i : found.opModes()) System.out.println("  " + (i.autonomous() ? "[Auto]    " : i.utility() ? "[Utility] " : "[TeleOp]  ") + i.name());
            System.exit(2);
        }
        OpModeInfo info = null;
        for (OpModeInfo i : found.opModes()) if (i.name().equals(opModeName)) info = i;
        if (info == null) fail("No OpMode named \"" + opModeName + "\"");

        RobotConfig robot = RobotConfig.load(robotFile);
        FieldConfig field = FieldConfig.load(fieldFile);
        for (String w : robot.warnings()) System.out.println("CONFIG WARNING: " + w);
        for (String w : field.warnings()) System.out.println("CONFIG WARNING: " + w);
        SimHarness harness = SimHarness.of(robot, field, found);
        harness.physics.setLockstep(!realtime);
        if (startName != null) harness.startAt(startName);
        Simulation sim = harness.sim;
        OpModeRunner runner = harness.opModes;

        List<double[]> trail = new ArrayList<>();
        sim.addStepListener(st -> {
            if (st.clock.tick() % 5 == 0) trail.add(new double[]{st.robotX(), st.robotY()});
        });
        Map<String, Double> loopMs = trackLoopTimes(harness);

        long wallStart = System.nanoTime();
        harness.init(info.name());
        PoseLogger logger = logFile != null ? new PoseLogger(Path.of(logFile), sim, 0.01) : null;
        if (logger != null) sim.addStepListener(logger);
        harness.start();
        double startTime = sim.clock.seconds();
        harness.runUntilStopped(seconds);
        harness.close();
        if (logger != null) logger.close();

        Snapshot s = sim.snapshot();
        double wall = (System.nanoTime() - wallStart) / 1e9;
        System.out.printf(Locale.US, "%n%s ran %.1f s of robot time in %.1f s of real time%n",
                info.name(), sim.clock.seconds() - startTime, wall);
        System.out.printf(Locale.US, "Final pose: x %.2f in, y %.2f in, heading %.1f deg%n",
                s.x() / IN, s.y() / IN, Math.toDegrees(s.heading()));
        printLoopTimes(loopMs);
        System.out.println("Last telemetry:");
        for (String line : runner.telemetry().publishedLines()) System.out.println("  " + line);
        if (logger != null) System.out.println("Pose log: " + logFile);

        if (snapshotFile != null) {
            BufferedImage img = new BufferedImage(900, 900, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(FieldRenderer.BACKGROUND);
            g.fillRect(0, 0, 900, 900);
            FieldRenderer r = new FieldRenderer(field, robot);
            if (driverView && sim.alliance() != null) r.setViewRotation(FieldRenderer.driverView("blue".equals(sim.alliance()) ? Math.PI : 0));
            r.layout(900, 900);
            r.paint(g, s, trail);
            g.dispose();
            ImageIO.write(img, "png", new File(snapshotFile));
            System.out.println("Snapshot: " + snapshotFile);
        }

        if (runner.error() != null) {
            System.out.println("\nERROR: " + runner.error());
            System.exit(1);
        }
        if (runner.warning() != null) System.out.println("\nWARNING: " + runner.warning());
        System.exit(0);
    }

    /** Latest average loop time of each OpMode that runs (hardware calls take time, see HubTiming). */
    private static Map<String, Double> trackLoopTimes(SimHarness h) {
        Map<String, Double> loopMs = new java.util.LinkedHashMap<>();
        h.sim.addStepListener(st -> {
            double ms = h.physics.robotLoopMillis();
            var cur = h.opModes.current();
            if (!Double.isNaN(ms) && cur != null) loopMs.put(cur.name(), ms);
        });
        return loopMs;
    }

    private static void printLoopTimes(Map<String, Double> loopMs) {
        loopMs.forEach((name, ms) -> System.out.printf(Locale.US, "Loop time: %.1f ms (%.0f loops/s) in %s%n", ms, 1000 / ms, name));
    }

    /** The project's TeamCode OpModes, or the robot package's own (compiled now). */
    private static OpModeRegistry.Result opModesFor(Path robotFile) {
        RobotCode code = RobotCode.forRobot(robotFile, RobotConfig.load(robotFile));
        if (code == null) return OpModeRegistry.scan();
        RobotCode.Build build = code.build();
        build.messages().forEach(System.out::println);
        if (!build.ok()) {
            System.out.println("Team code didn't compile:");
            build.errors().forEach(e -> System.out.println("  " + e));
            System.exit(1);
        }
        return build.opModes();
    }

    private static void runMatch(Path robotFile, Path fieldFile, OpModeRegistry.Result opModes, String auto, String teleop, String start,
                                 String logFile, String snapshotFile) throws Exception {
        RobotConfig robot = RobotConfig.load(robotFile);
        FieldConfig field = FieldConfig.load(fieldFile);
        SimHarness h = SimHarness.of(robot, field, opModes);
        if (start != null) h.startAt(start);
        List<double[]> trail = new ArrayList<>();
        h.sim.addStepListener(st -> {
            if (st.clock.tick() % 5 == 0 && st.match().inMatch()) trail.add(new double[]{st.robotX(), st.robotY()});
        });
        PoseLogger logger = logFile != null ? new PoseLogger(Path.of(logFile), h.sim, 0.01) : null;
        if (logger != null) h.sim.addStepListener(logger);
        Map<String, Double> loopMs = trackLoopTimes(h);
        long wallStart = System.nanoTime();
        h.runMatch(auto, teleop);
        if (logger != null) logger.close();
        double wall = (System.nanoTime() - wallStart) / 1e9;
        org.ftctestbed.sim.game.SeasonSnapshot s = h.season();
        System.out.printf(Locale.US, "%nMatch (%s / %s) played in %.1f s of real time, robot on %s%n",
                auto, teleop, wall, h.sim.alliance());
        printLoopTimes(loopMs);
        if (s != null) {
            for (String a : new String[]{"red", "blue"}) {
                System.out.printf(Locale.US, "%n%s  %d points%n", a.toUpperCase(), s.score(a));
                for (String line : s.breakdown(a)) System.out.println("  " + line);
                for (String rp : s.rankingPoints(a)) System.out.println("  " + rp);
            }
            if (!s.notes().isEmpty()) {
                System.out.println("\nReferee notes:");
                for (String n : s.notes()) System.out.println("  " + n);
            }
        }
        if (snapshotFile != null) {
            Snapshot snap = h.sim.snapshot();
            BufferedImage img = new BufferedImage(900, 900, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(FieldRenderer.BACKGROUND);
            g.fillRect(0, 0, 900, 900);
            FieldRenderer r = new FieldRenderer(field, robot);
            if (driverView && h.sim.alliance() != null) r.setViewRotation(FieldRenderer.driverView("blue".equals(h.sim.alliance()) ? Math.PI : 0));
            r.layout(900, 900);
            r.paint(g, snap, trail);
            g.dispose();
            ImageIO.write(img, "png", new File(snapshotFile));
            System.out.println("Snapshot: " + snapshotFile);
        }
        String err = h.opModes.error();
        h.close();
        if (err != null) {
            System.out.println("\nERROR: " + err);
            System.exit(1);
        }
        System.exit(0);
    }

    private static void fail(String msg) {
        System.err.println(msg);
        System.exit(2);
    }
}
