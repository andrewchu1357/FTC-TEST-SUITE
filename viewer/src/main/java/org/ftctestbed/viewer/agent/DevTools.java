package org.ftctestbed.viewer.agent;

import javax.imageio.ImageIO;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** For digging deeper: the console, Java run inside the simulator, the window, and these docs. */
final class DevTools {
    private static final AtomicInteger SNIPPETS = new AtomicInteger();
    private static final List<String> IMPORTS = List.of("java.util.*", "java.util.function.*", "java.util.stream.*",
            "com.qualcomm.robotcore.hardware.*", "com.qualcomm.robotcore.eventloop.opmode.*",
            "org.firstinspires.ftc.robotcore.external.navigation.*", "org.ftctestbed.sim.*", "org.ftctestbed.sim.game.*",
            "org.ftctestbed.sim.opmode.*");

    private final AgentApi api;

    DevTools(AgentApi api) {
        this.api = api;
    }

    List<Tool> tools() {
        return List.of(
                new Tool("console_read", "Read the console", "Debug",
                        "What robot code and the simulator printed: System.out/err, RobotLog, OpMode INIT/START/STOP, crashes with stack traces, "
                                + "config warnings. Each line has a number: pass the last one as \"since\" to read only what's new.",
                        Tool.Schema.of().integer("since", "Only lines numbered after this (default: the newest \"limit\" lines).")
                                .integer("limit", "Most lines (default 200).")
                                .str("contains", "Only lines containing this text.")
                                .str("thread", "Only lines from threads whose name contains this (an OpMode's thread is \"OpMode: <name>\").").build(),
                        true, this::console),
                new Tool("java_eval", "Run Java in the simulator", "Debug",
                        "Compiles and runs Java inside the simulator, with full access to the session and the robot's team code classes, for "
                                + "anything the other tools don't cover: read any field, call team code (a subsystem, a path follower, a constants "
                                + "class), change physics, inspect hardware objects, script a whole test. The code is a method body; these are in "
                                + "scope: sim (Simulation), robot (SimRobot), hardwareMap, runner (OpModeRunner), physics (SimRunner), match "
                                + "(MatchController), session (SimContext), out (PrintStream, returned as \"output\"), project (Path), and "
                                + "advance(seconds) to let simulated time run. Return a value to get it back as JSON (records and maps come out "
                                + "whole). A lone expression is returned as is. Units here are meters and radians. Imported: java.util.*, "
                                + "com.qualcomm.robotcore.hardware.*, com.qualcomm.robotcore.eventloop.opmode.*, the FTC navigation units, "
                                + "org.ftctestbed.sim.* (and .game, .opmode).",
                        Tool.Schema.of().session().robot()
                                .str("code", "Statements (a method body; may return a value) or one expression.")
                                .str("declarations", "Extra members for the snippet's class: helper methods, fields, nested classes.")
                                .strings("imports", "More imports, e.g. [\"org.firstinspires.ftc.teamcode.subsystems.*\"].")
                                .num("timeout_seconds", "Real seconds before it's interrupted (default 30, up to 600).")
                                .required("code").build(), false, this::eval),
                new Tool("ui", "Simulator window", "Debug",
                        "Live session only (the simulator's window): screenshot the window as the person sees it (3D view included); "
                                + "show a view (3D, 2D, Code, Setup); pick a 3D camera; pause, resume, set the speed, or step the physics while "
                                + "paused; reload the window's robots and code (after editing TeamCode or a config: it recompiles).",
                        Tool.Schema.of()
                                .oneOf("action", "What to do.", "screenshot", "show", "camera", "pause", "resume", "speed", "step", "reload", "info")
                                .str("view", "show: 3D, 2D, Code or Setup.")
                                .str("camera", "camera: a 3D camera preset name (info lists them).")
                                .num("speed", "speed: 0.25, 0.5, 1, 2 or 4.")
                                .integer("steps", "step: physics steps of 2 ms (default 50).")
                                .required("action").build(), false, this::ui),
                new Tool("docs", "Read these docs", "Project",
                        "This API's documentation: guide (workflows, conventions, recipes, troubleshooting), tools (every tool and argument), "
                                + "or readme (the simulator itself: configs, physics, robot packages).",
                        Tool.Schema.of().oneOf("topic", "Which document (default guide).", "guide", "tools", "readme").build(), true, this::docs));
    }

    // ------------------------------------------------------------------ console

    private Tool.Result console(Json.Args a) {
        int limit = Math.max(1, Math.min(5000, a.integer("limit", 200)));
        long since = a.has("since") ? (long) a.num("since", 0) : 0;
        String thread = a.str("thread", null);
        List<Object> out = new ArrayList<>();
        long last = since;
        for (Console.Line l : Console.since(since, thread == null ? limit : 20_000, a.str("contains", null))) {
            if (thread != null && !l.thread().contains(thread)) continue;
            out.add(Json.obj("n", l.seq(), "thread", l.thread(), "text", l.text()));
            last = Math.max(last, l.seq());
        }
        if (out.size() > limit) out = out.subList(out.size() - limit, out.size());
        return Tool.Result.of("lines", out, "last", Math.max(last, Console.next() - 1),
                "hint", "Pass \"since\": last to read only newer lines next time.");
    }

    // ------------------------------------------------------------------ java

    private Tool.Result eval(Json.Args a) throws Exception {
        if (!api.config().javaEval()) throw new Json.ToolError("java_eval is turned off in config/agent-api.jsonc (\"javaEval\": false)");
        SimContext c;
        try {
            c = api.sessions().get(a.str("session", null));
        } catch (Json.ToolError e) {
            if (a.has("session") || !api.sessions().all().isEmpty()) throw e;
            c = null; // no simulation: plain Java with the simulator's classes
        }
        SimContext ctx = c;
        int r = ctx == null ? 1 : RobotTools.robot(ctx, a);
        double timeout = Math.max(1, Math.min(600, a.num("timeout_seconds", 30)));
        if (ctx == null) return runSnippet(null, r, a, timeout);
        return api.sessions().with(ctx, () -> runSnippet(ctx, r, a, timeout));
    }

    private Tool.Result runSnippet(SimContext c, int r, Json.Args a, double timeout) throws Exception {
        ClassLoader parent = c == null ? getClass().getClassLoader() : c.codeLoader(r);
        int n = SNIPPETS.incrementAndGet();
        String cls = "Snippet" + n;
        Path dir = api.project().resolve("build/agent/eval/" + cls);
        HeadlessSession.deleteTree(dir);
        Files.createDirectories(dir);
        StringBuilder cp = new StringBuilder();
        if (parent instanceof URLClassLoader u) {
            for (URL url : u.getURLs()) {
                try {
                    cp.append(Path.of(url.toURI())).append(File.pathSeparator);
                } catch (Exception ignored) {
                    // not a file
                }
            }
        }
        cp.append(System.getProperty("java.class.path"));

        String code = a.str("code").strip();
        boolean expression = !code.endsWith(";") && !code.endsWith("}");
        String body = expression ? "return " + code + ";" : code;
        Compiled comp = compileSnippet(cls, a, body, dir, cp.toString());
        if (!comp.ok() && !expression && comp.missingReturn()) comp = compileSnippet(cls, a, body + "\nreturn null;", dir, cp.toString());
        if (!comp.ok()) return Tool.Result.of("ok", false, "compileErrors", comp.errors());

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        Object[] result = new Object[1];
        Throwable[] error = new Throwable[1];
        try (URLClassLoader loader = new URLClassLoader("java_eval", new URL[]{dir.toUri().toURL()}, parent)) {
            Snippet snippet = (Snippet) loader.loadClass("ftcagent.eval." + cls).getDeclaredConstructor().newInstance();
            Snippet.Context sc = c == null ? new Snippet.Context(null, null, null, null, null, null, null, parent, out, api.project())
                    : new Snippet.Context(c, c.sim(), c.sim().robot(r), c.hardwareMap(r), c.runner(r), c.physics(), c.match(), parent, out, api.project());
            Thread t = new Thread(() -> {
                try {
                    result[0] = snippet.run(sc);
                } catch (Throwable e) {
                    error[0] = e;
                }
            }, "java_eval " + cls);
            t.setContextClassLoader(loader);
            t.setDaemon(true);
            long t0 = System.nanoTime();
            t.start();
            t.join(Math.round(timeout * 1000));
            boolean timedOut = t.isAlive();
            if (timedOut) {
                t.interrupt();
                t.join(2000);
            }
            Map<String, Object> res = Json.obj("ok", error[0] == null && !timedOut, "returned", HardwareTools.value(result[0]),
                    "returnedType", result[0] == null ? null : result[0].getClass().getName(), "output", outBytes.toString(StandardCharsets.UTF_8),
                    "realSeconds", Views.r3((System.nanoTime() - t0) / 1e9));
            if (c != null) res.put("timeSeconds", Views.r3(c.sim().clock.seconds()));
            if (timedOut) res.put("error", "Timed out after " + timeout + " s (interrupted)");
            if (error[0] != null) {
                Throwable e = error[0];
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                String trace = sw.toString();
                res.put("error", e.toString());
                res.put("stackTrace", trace.length() > 6000 ? trace.substring(0, 6000) + "\n..." : trace);
            }
            return Tool.Result.of(res);
        } finally {
            HeadlessSession.deleteTree(dir);
        }
    }

    private record Compiled(boolean ok, List<String> errors, boolean missingReturn) {}

    private Compiled compileSnippet(String cls, Json.Args a, String body, Path dir, String classpath) throws Exception {
        StringBuilder src = new StringBuilder("package ftcagent.eval;\n");
        for (String i : IMPORTS) src.append("import ").append(i).append(";\n");
        for (Object i : a.list("imports")) {
            String s = String.valueOf(i).strip();
            if (s.startsWith("import ")) s = s.substring(7);
            if (s.endsWith(";")) s = s.substring(0, s.length() - 1);
            src.append("import ").append(s).append(";\n");
        }
        src.append("public final class ").append(cls).append(" implements org.ftctestbed.viewer.agent.Snippet {\n")
                .append("  private org.ftctestbed.viewer.agent.Snippet.Context ctx;\n")
                .append("  private org.ftctestbed.viewer.agent.SimContext session;\n")
                .append("  private Simulation sim;\n  private SimRobot robot;\n  private HardwareMap hardwareMap;\n")
                .append("  private OpModeRunner runner;\n  private SimRunner physics;\n  private MatchController match;\n")
                .append("  private java.io.PrintStream out;\n  private java.nio.file.Path project;\n")
                .append("  private void advance(double seconds) { ctx.advance(seconds); }\n");
        int declStart = lines(src) + 1;
        String decl = a.str("declarations", "");
        src.append(decl).append('\n');
        src.append("  public Object run(org.ftctestbed.viewer.agent.Snippet.Context ctx) throws Throwable {\n")
                .append("    this.ctx = ctx; session = ctx.session(); sim = ctx.sim(); robot = ctx.robot(); hardwareMap = ctx.hardwareMap();\n")
                .append("    runner = ctx.runner(); physics = ctx.physics(); match = ctx.match(); out = ctx.out(); project = ctx.project();\n");
        int codeStart = lines(src) + 1;
        src.append(body).append("\n  }\n}\n");
        Path file = dir.resolve(cls + ".java");
        Files.writeString(file, src.toString());

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new Json.ToolError("No Java compiler: the server must run on a JDK");
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        boolean ok;
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diags, Locale.US, StandardCharsets.UTF_8)) {
            ok = javac.getTask(null, fm, diags, List.of("-d", dir.toString(), "-classpath", classpath, "-proc:none", "-nowarn",
                    "-encoding", "UTF-8"), null, fm.getJavaFileObjects(file.toFile())).call();
        }
        List<String> errors = new ArrayList<>();
        boolean missingReturn = false;
        int declLines = decl.split("\n", -1).length;
        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR) continue;
            String msg = d.getMessage(Locale.US);
            if (msg.contains("missing return statement")) missingReturn = true;
            long line = d.getLineNumber();
            String where = line >= codeStart ? "code line " + (line - codeStart + 1)
                    : line >= declStart && line < declStart + declLines ? "declarations line " + (line - declStart + 1)
                    : line < declStart ? "imports" : "line " + line;
            errors.add(where + ": " + msg);
        }
        return new Compiled(ok, errors, missingReturn);
    }

    private static int lines(CharSequence s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    // ------------------------------------------------------------------ window

    private Tool.Result ui(Json.Args a) throws Exception {
        SimContext live = api.sessions().live();
        if (live == null) {
            throw new Json.ToolError("The ui tool works on the simulator's window: open the app with agent access on (config/agent-api.jsonc "
                    + "\"enabled\": true) and connect to it. Headless sessions can be drawn with sim_image.");
        }
        String action = a.str("action");
        if (action.equals("screenshot")) {
            BufferedImage img = live.screenshot(a.str("view", null));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bytes);
            return Tool.Result.of("session", live.id(), "width", img.getWidth(), "height", img.getHeight()).withPng(bytes.toByteArray());
        }
        String done = live.control(a.all());
        return Tool.Result.of("session", live.id(), "done", done);
    }

    // ------------------------------------------------------------------ docs

    private Tool.Result docs(Json.Args a) throws Exception {
        String topic = a.str("topic", "guide");
        String text = switch (topic) {
            case "guide" -> api.guide();
            case "tools" -> ToolDocs.markdown(api.tools());
            case "readme" -> {
                Path f = api.project().resolve("README.md");
                yield Files.exists(f) ? Files.readString(f) : "No README.md in this project.";
            }
            default -> throw new Json.ToolError("topic: guide, tools or readme");
        };
        return Tool.Result.of("topic", topic, "markdown", text);
    }
}
