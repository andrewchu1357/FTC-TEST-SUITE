package org.ftctestbed.viewer;

import org.ftctestbed.sim.code.RobotCode;
import org.ftctestbed.sim.config.RobotConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The code the robots on the field run, for the Code tab: its source folders (the project's
 * TeamCode, a robot package's code/ folder, or team code it links to), new files from templates,
 * and compiling to check for errors before the simulator reloads.
 */
final class CodeWorkspace {
    /** A source folder (where package folders start) and how to label it. */
    record Root(String label, Path dir) {}

    /** The result of compiling: ok when every robot's code compiled with no errors. */
    record Check(boolean ok, List<RobotCode.Problem> problems, List<String> messages, double seconds) {}

    enum Template { TELEOP, AUTONOMOUS, ITERATIVE, CLASS }

    private static final Pattern JAVA_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private final List<RobotCode> codes;
    private final List<Root> roots;

    CodeWorkspace(List<RobotCode> codes, Path projectDir) {
        this.codes = codes;
        List<Root> rs = new ArrayList<>();
        for (RobotCode c : codes) {
            try {
                for (Path r : c.sourceRoots()) {
                    Path abs = r.toAbsolutePath().normalize();
                    if (rs.stream().anyMatch(x -> x.dir().equals(abs))) continue;
                    // Inside the project: the name as the docs and configs write it, with / on every system.
                    String label = abs.startsWith(projectDir.toAbsolutePath())
                            ? projectDir.toAbsolutePath().relativize(abs).toString().replace(java.io.File.separatorChar, '/') : abs.toString();
                    rs.add(new Root(label, abs));
                }
            } catch (IOException ignored) {
                // a missing linked folder shows up as a compile error instead
            }
        }
        this.roots = rs;
    }

    /** The code of the robots in this window: each robot's package code, or the project's TeamCode. */
    static CodeWorkspace forRobots(Path projectDir, Path robotFile, RobotConfig robot, Path partnerFile, RobotConfig partner) {
        Map<Path, RobotCode> distinct = new LinkedHashMap<>();
        for (Object[] r : new Object[][]{{robotFile, robot}, {partnerFile, partner}}) {
            if (r[0] == null || r[1] == null) continue;
            RobotCode c = RobotCode.forRobot((Path) r[0], (RobotConfig) r[1]);
            if (c == null) c = RobotCode.forProjectTeamCode(projectDir);
            if (c != null) distinct.putIfAbsent(c.classesDir(), c);
        }
        return new CodeWorkspace(new ArrayList<>(distinct.values()), projectDir);
    }

    List<Root> roots() { return roots; }

    boolean isEmpty() { return roots.isEmpty(); }

    /** The source folder a file or folder is in, or null. */
    Root rootOf(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        for (Root r : roots) if (abs.startsWith(r.dir())) return r;
        return null;
    }

    /** The Java package for a folder inside a source folder ("" for the folder itself). */
    String packageOf(Path folder) {
        Root r = rootOf(folder);
        if (r == null) return "";
        Path rel = r.dir().relativize(folder.toAbsolutePath().normalize());
        return rel.toString().isEmpty() ? "" : rel.toString().replace('/', '.').replace('\\', '.');
    }

    /** Compiles every robot's code (only what changed since the last build). */
    Check compile() {
        long t0 = System.nanoTime();
        boolean ok = true;
        List<RobotCode.Problem> problems = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        for (RobotCode c : codes) {
            RobotCode.Build b = c.build();
            problems.addAll(c.problems());
            if (!b.ok()) {
                ok = false;
                if (c.problems().isEmpty()) messages.addAll(b.errors()); // no file to point at (missing folder...)
            }
            for (String m : b.messages()) if (m.startsWith("Left out") || m.startsWith("  ")) messages.add(m);
        }
        return new Check(ok && problems.isEmpty(), problems, messages, (System.nanoTime() - t0) / 1e9);
    }

    /** Creates ClassName.java in a folder from a template; returns the new file. */
    Path newFile(Path folder, String className, Template template) throws IOException {
        if (!JAVA_NAME.matcher(className).matches()) throw new IOException("\"" + className + "\" isn't a valid Java class name");
        if (rootOf(folder) == null) throw new IOException("Pick a folder inside the robot's code");
        Path file = folder.resolve(className + ".java");
        if (Files.exists(file)) throw new IOException(file.getFileName() + " already exists");
        Files.createDirectories(folder);
        Files.writeString(file, template(packageOf(folder), className, template));
        return file;
    }

    static String template(String pkg, String name, Template t) {
        String header = pkg.isEmpty() ? "" : "package " + pkg + ";\n\n";
        String title = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        return switch (t) {
            case TELEOP -> header + """
                    import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
                    import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

                    @TeleOp(name = "%s", group = "Team")
                    public class %s extends LinearOpMode {
                        @Override
                        public void runOpMode() {
                            // Get your hardware here, e.g. DcMotor lift = hardwareMap.get(DcMotor.class, "lift");
                            telemetry.addData("Status", "Ready");
                            telemetry.update();
                            waitForStart();
                            while (opModeIsActive()) {
                                telemetry.addData("Left stick y", gamepad1.left_stick_y);
                                telemetry.update();
                            }
                        }
                    }
                    """.formatted(title, name);
            case AUTONOMOUS -> header + """
                    import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
                    import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

                    @Autonomous(name = "%s", group = "Team")
                    public class %s extends LinearOpMode {
                        @Override
                        public void runOpMode() {
                            // Get your hardware here, e.g. DcMotor lift = hardwareMap.get(DcMotor.class, "lift");
                            waitForStart();
                            telemetry.addData("Status", "Running");
                            telemetry.update();
                            sleep(1000);
                        }
                    }
                    """.formatted(title, name);
            case ITERATIVE -> header + """
                    import com.qualcomm.robotcore.eventloop.opmode.OpMode;
                    import com.qualcomm.robotcore.eventloop.opmode.TeleOp;

                    @TeleOp(name = "%s", group = "Team")
                    public class %s extends OpMode {
                        @Override
                        public void init() {
                            // Get your hardware here
                        }

                        @Override
                        public void loop() {
                            telemetry.addData("Runtime", getRuntime());
                        }
                    }
                    """.formatted(title, name);
            case CLASS -> header + """
                    public class %s {
                    }
                    """.formatted(name);
        };
    }
}
