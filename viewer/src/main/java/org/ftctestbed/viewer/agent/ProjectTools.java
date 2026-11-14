package org.ftctestbed.viewer.agent;

import org.ftctestbed.sim.code.HardwareScanner;
import org.ftctestbed.sim.code.RobotCode;
import org.ftctestbed.sim.config.ConfigException;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.PartsCatalog;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.opmode.OpModeInfo;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** The project: its files, robot and field configs, team code builds and the parts catalog. */
final class ProjectTools {
    private static final List<String> SKIP_DIRS = List.of(".git", ".gradle", "build", "node_modules", ".idea", "out");

    private final AgentApi api;

    ProjectTools(AgentApi api) {
        this.api = api;
    }

    private Path project() {
        return api.project();
    }

    List<Tool> tools() {
        return List.of(
                new Tool("project_info", "Project overview", "Project",
                        "Start here. The simulator project: where it is, its robot and field configs, robot packages, team code folders, "
                                + "docs, the coordinate system and units every tool uses, open sessions, and what this server allows.",
                        Tool.Schema.of().build(), true, a -> info()),
                new Tool("list_files", "List files", "Project",
                        "Lists project files under a folder (skipping build output and .git). Use a glob such as \"**/*.java\" to filter.",
                        Tool.Schema.of().str("path", "Folder relative to the project (default: the project folder).")
                                .str("glob", "Glob on the path relative to that folder, e.g. \"**/*.java\" or \"*.jsonc\".")
                                .integer("max", "Most entries to return (default 500).").build(), true, this::listFiles),
                new Tool("read_file", "Read a file", "Project",
                        "Reads a text file in the project (robot code, configs, docs, logs). Long files come in pages: use start_line.",
                        Tool.Schema.of().str("path", "File path relative to the project (absolute paths inside it work too).")
                                .integer("start_line", "First line to return, from 1 (default 1).")
                                .integer("max_lines", "Most lines to return (default 2000).")
                                .bool("line_numbers", "Prefix each line with its number (default false).")
                                .required("path").build(), true, this::readFile),
                new Tool("write_file", "Write a file", "Project",
                        "Creates or changes a text file in the project: robot code (TeamCode/src/main/java/...), configs, test OpModes. "
                                + "Give \"content\" for the whole file, or \"edits\" ([{old, new}], each old text found exactly once) to change parts. "
                                + "Sessions already open keep the code they compiled: create a new session (or call compile_code) to try the change. "
                                + "Some paths are read-only (see project_info).",
                        Tool.Schema.of().str("path", "File path relative to the project.")
                                .str("content", "The whole new file.")
                                .array("edits", "Replacements to make in the existing file, in order.",
                                        Tool.Schema.of().str("old", "Exact text to find (must appear once).").str("new", "Replacement text.").item())
                                .required("path").build(), false, this::writeFile),
                new Tool("search_code", "Search files", "Project",
                        "Searches project files with a regular expression (Java regex) and returns matching lines with file and line number.",
                        Tool.Schema.of().str("pattern", "Regular expression, e.g. \"hardwareMap\\\\.get\\\\(\".")
                                .str("path", "Folder relative to the project (default: the project).")
                                .str("glob", "Only files whose path matches, e.g. \"**/*.java\" (default: all text files).")
                                .bool("ignore_case", "Case-insensitive (default false).")
                                .integer("max", "Most matches (default 200).").required("pattern").build(), true, this::search),
                new Tool("compile_code", "Compile team code", "Code",
                        "Compiles a robot's team code (its robot package's code, else TeamCode/src/main/java) plus optional extra Java sources, "
                                + "without starting a simulation. Returns errors with file/line/column, files left out because they don't compile "
                                + "(the rest still runs, as in the app), the OpModes found, and hardware names the code uses that the robot config lacks.",
                        Tool.Schema.of().str("robot", "Robot config whose code to compile (default config/robot.jsonc).")
                                .array("sources", "Extra Java files compiled with the team code (test OpModes, new mechanisms).", sourceItem())
                                .build(), true, this::compile),
                new Tool("validate_robot_config", "Check a robot config", "Code",
                        "Parses a robot config (a file, or JSONC text) the way the simulator does and reports errors and warnings, plus the "
                                + "parsed result (motors, servos, sensors, drivetrain, mechanisms...). Optionally checks the hardware names in the "
                                + "robot's code against it.",
                        Tool.Schema.of().str("path", "Robot config file relative to the project (default config/robot.jsonc).")
                                .str("text", "JSONC text to check instead of a file (read as if it were at \"path\").")
                                .bool("check_code", "Also compare against the hardware names the robot's code uses (default true).")
                                .bool("full", "Return every parsed field (default false: a summary).").build(), true, this::validate),
                new Tool("validate_field_config", "Check a field config", "Code",
                        "Parses a field config (file or JSONC text): start positions, season, obstacles, game pieces, warnings.",
                        Tool.Schema.of().str("path", "Field config relative to the project (default config/field.jsonc).")
                                .str("text", "JSONC text to check instead of a file.").build(), true, this::validateField),
                new Tool("parts_catalog", "Parts catalog", "Code",
                        "The motors, servos, wheels, slides, odometry pods and sensors robot configs can name by id (\"part\": \"...\"), with "
                                + "their specs. Filter by category or text; give an id for the full specs.",
                        Tool.Schema.of().oneOf("category", "Only this category.", PartsCatalog.CATEGORIES.toArray(new String[0]))
                                .str("query", "Text to look for in the id, name or vendor.")
                                .str("id", "One part's id: returns all its specs.")
                                .str("robot", "A robot config whose own parts folders to include (default: built-in parts only).").build(),
                        true, this::parts));
    }

    static Map<String, Object> sourceItem() {
        return Tool.Schema.of().str("path", "Path under the source root, e.g. org/firstinspires/ftc/teamcode/agent/DriveTest.java.")
                .str("content", "The Java source.").item();
    }

    // ------------------------------------------------------------------ paths

    /** A path inside the project (relative or absolute), refusing hidden files. */
    Path resolve(String path) {
        Path p = (path == null || path.isBlank() ? project() : project().resolve(path)).toAbsolutePath().normalize();
        if (!p.startsWith(project())) throw new Json.ToolError(path + " is outside the project (" + project() + ")");
        if (AgentConfig.hidden(project(), p)) throw new Json.ToolError(path + " isn't available to agents.");
        return p;
    }

    String rel(Path p) {
        return project().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    // ------------------------------------------------------------------ tools

    private Tool.Result info() throws IOException {
        Path pr = project();
        Map<String, Object> configs = Json.obj();
        configs.put("robots", globFiles("config", "*.jsonc", f -> !f.getFileName().toString().startsWith("field")
                && !f.getFileName().toString().startsWith("web-view")
                && !f.getFileName().toString().startsWith("agent-api") && !f.getFileName().toString().contains(".model.")));
        configs.put("fields", globFiles("config", "field*.jsonc", f -> true));
        configs.put("examples", globFiles("config/examples", "*.jsonc", f -> true));
        List<Object> packages = new ArrayList<>();
        Path robots = pr.resolve("robots");
        if (Files.isDirectory(robots)) {
            try (Stream<Path> s = Files.list(robots)) {
                for (Path d : s.sorted().toList()) {
                    Path f = d.resolve("robot.jsonc");
                    if (!Files.exists(f)) continue;
                    String codeInfo;
                    try {
                        RobotConfig cfg = RobotConfig.load(f);
                        RobotCode code = RobotCode.forRobot(f, cfg);
                        codeInfo = code == null ? "the project's TeamCode" : code.dirs().stream().map(this::rel).toList().toString();
                    } catch (RuntimeException e) {
                        codeInfo = "config error: " + e.getMessage();
                    }
                    packages.add(Json.obj("config", rel(f), "code", codeInfo, "readOnly", Files.isDirectory(d.resolve("repo")) && api.config().readOnly(pr, d.resolve("repo"))
                            ? List.of(rel(d.resolve("repo"))) : List.of()));
                }
            }
        }
        List<Object> sessions = new ArrayList<>();
        for (SimContext c : api.sessions().all()) sessions.add(c.describe());
        Path teamCode = pr.resolve("TeamCode/src/main/java");
        return Tool.Result.of(
                "project", pr.toString(),
                "server", Json.obj("name", AgentApi.NAME, "version", AgentApi.VERSION, "mode", api.mode(),
                        "liveWindow", api.sessions().live() != null, "javaEval", api.config().javaEval(),
                        "readOnlyPaths", api.config().readOnly()),
                "teamCode", Files.isDirectory(teamCode) ? rel(teamCode) : null,
                "configs", configs,
                "robotPackages", packages,
                "docs", List.of("docs/agent-api.md (this API: workflows, every tool, troubleshooting)",
                        "docs/agent-tools.md (generated reference of every tool's arguments)", "README.md (the simulator)"),
                "coordinates", Json.obj(
                        "origin", "field center",
                        "x", "toward the BLUE alliance wall (red drivers stand at -x, blue at +x)",
                        "y", "away from the audience",
                        "heading", "0 = facing +x, counter-clockwise positive",
                        "toolUnits", "inches and degrees in every tool's arguments and summaries",
                        "rawUnits", "meters and radians in sim_state's raw snapshot and java_eval (the simulator's own units)"),
                "time", "Headless sessions run in lockstep: simulated time moves only inside sim_step / opmode_run / match and similar tools, "
                        + "as fast as the computer allows, and runs are repeatable. The live session runs in real time.",
                "sessions", sessions);
    }

    private List<String> globFiles(String dir, String glob, java.util.function.Predicate<Path> keep) throws IOException {
        Path d = project().resolve(dir);
        if (!Files.isDirectory(d)) return List.of();
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        try (Stream<Path> s = Files.list(d)) {
            return s.filter(f -> m.matches(f.getFileName()) && keep.test(f) && !AgentConfig.hidden(project(), f)).sorted().map(this::rel).toList();
        }
    }

    private Tool.Result listFiles(Json.Args a) throws IOException {
        Path dir = resolve(a.str("path", ""));
        if (!Files.isDirectory(dir)) throw new Json.ToolError(a.str("path", "") + " is not a folder");
        String glob = a.str("glob", null);
        PathMatcher m = glob == null ? null : FileSystems.getDefault().getPathMatcher("glob:" + glob);
        int max = a.integer("max", 500);
        List<Object> out = new ArrayList<>();
        int[] total = {0};
        walk(dir, f -> {
            Path r = dir.relativize(f);
            if (m != null && !m.matches(r) && !m.matches(f.getFileName())) return;
            total[0]++;
            if (out.size() < max) {
                long size = -1;
                try {
                    size = Files.size(f);
                } catch (IOException ignored) {
                }
                out.add(Json.obj("path", rel(f), "bytes", size));
            }
        });
        return Tool.Result.of("folder", rel(dir).isEmpty() ? "." : rel(dir), "files", out, "total", total[0], "truncated", total[0] > out.size());
    }

    /** Every regular file under dir, skipping build output, VCS folders and hidden files. */
    private void walk(Path dir, java.util.function.Consumer<Path> each) throws IOException {
        Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (!d.equals(dir) && SKIP_DIRS.contains(d.getFileName().toString())) return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !AgentConfig.hidden(project(), f)) each.accept(f);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path f, IOException e) {
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private Tool.Result readFile(Json.Args a) throws IOException {
        Path f = resolve(a.str("path"));
        if (!Files.isRegularFile(f)) throw new Json.ToolError(a.str("path") + " not found (list_files shows what's there)");
        if (Files.size(f) > 20_000_000) throw new Json.ToolError(a.str("path") + " is " + Files.size(f) / 1_000_000 + " MB: too big to read here");
        byte[] bytes = Files.readAllBytes(f);
        for (int i = 0; i < Math.min(bytes.length, 8000); i++) {
            if (bytes[i] == 0) throw new Json.ToolError(a.str("path") + " is a binary file");
        }
        String[] lines = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).split("\n", -1);
        int start = Math.max(1, a.integer("start_line", 1));
        int max = Math.max(1, a.integer("max_lines", 2000));
        int end = Math.min(lines.length, start - 1 + max);
        boolean numbers = a.bool("line_numbers", false);
        StringBuilder b = new StringBuilder();
        for (int i = start - 1; i < end; i++) {
            if (numbers) b.append(String.format(Locale.US, "%6d  ", i + 1));
            b.append(lines[i]);
            if (i < end - 1) b.append('\n');
        }
        return Tool.Result.of("path", rel(f), "totalLines", lines.length, "startLine", start, "endLine", end,
                "more", end < lines.length, "readOnly", api.config().readOnly(project(), f), "content", b.toString());
    }

    private Tool.Result writeFile(Json.Args a) throws IOException {
        Path f = resolve(a.str("path"));
        if (api.config().readOnly(project(), f)) {
            throw new Json.ToolError(a.str("path") + " is read-only for agents (config/agent-api.jsonc \"readOnly\"). "
                    + "To try changes to that code, give the changed or new files to sim_create / compile_code as \"sources\", "
                    + "or write them under TeamCode/src/main/java.");
        }
        String text;
        int replaced = 0;
        if (a.has("content")) {
            text = a.str("content");
        } else if (a.has("edits")) {
            if (!Files.isRegularFile(f)) throw new Json.ToolError(a.str("path") + " doesn't exist: give \"content\" to create it");
            text = Files.readString(f);
            for (Object o : a.list("edits")) {
                Json.Args e = new Json.Args(o);
                String old = e.str("old"), neu = e.str("new", "");
                int at = text.indexOf(old);
                if (at < 0) throw new Json.ToolError("Edit " + (replaced + 1) + ": text not found: " + abbreviate(old));
                if (text.indexOf(old, at + 1) >= 0) throw new Json.ToolError("Edit " + (replaced + 1) + ": text appears more than once (include more context): " + abbreviate(old));
                text = text.substring(0, at) + neu + text.substring(at + old.length());
                replaced++;
            }
        } else {
            throw new Json.ToolError("Give \"content\" or \"edits\"");
        }
        boolean existed = Files.exists(f);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
        return Tool.Result.of("path", rel(f), "created", !existed, "edits", replaced, "bytes", text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                "lines", text.split("\n", -1).length);
    }

    private static String abbreviate(String s) {
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }

    private Tool.Result search(Json.Args a) throws IOException {
        Pattern p;
        try {
            p = Pattern.compile(a.str("pattern"), a.bool("ignore_case", false) ? Pattern.CASE_INSENSITIVE : 0);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new Json.ToolError("Bad regular expression: " + e.getDescription());
        }
        Path dir = resolve(a.str("path", ""));
        String glob = a.str("glob", null);
        PathMatcher m = glob == null ? null : FileSystems.getDefault().getPathMatcher("glob:" + glob);
        int max = a.integer("max", 200);
        List<Object> hits = new ArrayList<>();
        int[] files = {0};
        walk(dir, f -> {
            if (hits.size() >= max) return;
            Path r = dir.relativize(f);
            if (m != null && !m.matches(r) && !m.matches(f.getFileName())) return;
            try {
                if (Files.size(f) > 2_000_000) return;
                byte[] bytes = Files.readAllBytes(f);
                for (int i = 0; i < Math.min(bytes.length, 4000); i++) if (bytes[i] == 0) return;
                files[0]++;
                String[] lines = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).split("\n", -1);
                for (int i = 0; i < lines.length && hits.size() < max; i++) {
                    Matcher mm = p.matcher(lines[i]);
                    if (mm.find()) hits.add(Json.obj("path", rel(f), "line", i + 1, "text", abbreviate(lines[i].strip())));
                }
            } catch (IOException ignored) {
            }
        });
        return Tool.Result.of("matches", hits, "filesSearched", files[0], "truncated", hits.size() >= max);
    }

    private Tool.Result compile(Json.Args a) throws IOException {
        Path robotFile = resolve(a.str("robot", "config/robot.jsonc"));
        RobotConfig cfg = RobotConfig.load(robotFile);
        RobotCode code = RobotCode.forRobot(robotFile, cfg);
        if (code == null) code = RobotCode.forProjectTeamCode(project());
        if (code == null) throw new Json.ToolError("This robot has no code of its own and the project has no TeamCode/src/main/java");
        List<HeadlessSession.Source> extra = SimTools.sources(a.list("sources"));
        long t0 = System.nanoTime();
        HeadlessSession.Compiled c = HeadlessSession.compile(project(), code, cfg, extra);
        RobotCode.Build b = c.build();
        List<Object> problems = new ArrayList<>();
        Path given = c.dir().resolve("sources");
        for (RobotCode.Problem p : c.code().problems()) {
            String file = p.file() == null ? null : p.file().toAbsolutePath().normalize().startsWith(given)
                    ? "sources: " + given.relativize(p.file().toAbsolutePath().normalize()).toString().replace('\\', '/') : relOrAbs(p.file());
            problems.add(Json.obj("file", file, "line", p.line(), "column", p.column(), "message", p.message()));
        }
        List<Object> opModes = new ArrayList<>();
        for (OpModeInfo i : b.opModes().opModes()) opModes.add(Views.opModeInfo(i));
        List<String> leftOut = b.messages().stream().dropWhile(m -> !m.startsWith("Left out")).toList();
        List<String> hardware = List.of();
        if (b.ok()) {
            try {
                hardware = HardwareScanner.missing(HardwareScanner.scan(c.code().sourceRoots()), cfg);
            } catch (Exception e) {
                hardware = List.of("Could not scan the code's hardware names: " + e.getMessage());
            }
        }
        return Tool.Result.of("ok", b.ok(), "alreadyCompiled", b.upToDate(), "seconds", Views.r2((System.nanoTime() - t0) / 1e9),
                "sourceFiles", b.sourceFiles(), "sourceFolders", code.dirs().stream().map(this::relOrAbs).toList(),
                "errors", b.errors(), "problems", problems, "leftOut", leftOut, "opModes", opModes,
                "opModeWarnings", b.opModes().warnings(), "configCheck", hardware, "messages", b.messages());
    }

    String relOrAbs(Path p) {
        Path n = p.toAbsolutePath().normalize();
        return n.startsWith(project()) ? rel(n) : n.toString();
    }

    private Tool.Result validate(Json.Args a) throws IOException {
        Path f = resolve(a.str("path", "config/robot.jsonc"));
        RobotConfig cfg;
        try {
            cfg = a.has("text") ? RobotConfig.parse(a.str("text"), f.getFileName() + " (text)", PartsCatalog.forConfigFile(f)) : RobotConfig.load(f);
        } catch (ConfigException e) {
            return Tool.Result.of("ok", false, "error", e.getMessage());
        }
        List<String> check = List.of();
        if (a.bool("check_code", true)) {
            try {
                RobotCode code = RobotCode.forRobot(f, cfg);
                if (code == null) code = RobotCode.forProjectTeamCode(project());
                if (code != null) check = HardwareScanner.missing(HardwareScanner.scan(code.sourceRoots()), cfg);
            } catch (Exception e) {
                check = List.of("Could not scan the code: " + e.getMessage());
            }
        }
        Map<String, Object> parsed = a.bool("full", false) ? full(cfg) : summary(cfg);
        return Tool.Result.of("ok", true, "warnings", cfg.warnings(), "configCheck", check, "config", parsed,
                "units", "meters, kilograms, radians (the simulator's units; configs may be written in inches/mm with suffixes)");
    }

    private static Map<String, Object> full(RobotConfig cfg) {
        Map<String, Object> m = Json.obj();
        for (var c : RobotConfig.class.getRecordComponents()) {
            if (c.getName().equals("catalog") || c.getName().equals("source")) continue;
            try {
                m.put(c.getName(), c.getAccessor().invoke(cfg));
            } catch (ReflectiveOperationException e) {
                m.put(c.getName(), String.valueOf(e));
            }
        }
        return m;
    }

    private static Map<String, Object> summary(RobotConfig cfg) {
        List<Object> motors = new ArrayList<>();
        for (RobotConfig.Motor m : cfg.allMotors()) motors.add(Json.obj("name", m.name(), "hub", m.hub(), "port", m.port(), "reversed", m.mountedReversed()));
        List<String> servos = cfg.servos().stream().map(s -> s.name() + (s.continuous() ? " (CR)" : "")).toList();
        List<Object> mechs = new ArrayList<>();
        for (RobotConfig.Mechanism m : cfg.mechanisms()) mechs.add(Json.obj("name", m.name(), "type", m.type(), "motors", m.motors()));
        List<String> sensors = new ArrayList<>();
        cfg.distanceSensors().forEach(d -> sensors.add(d.name() + " (distance)"));
        cfg.colorSensors().forEach(d -> sensors.add(d.name() + " (color)"));
        cfg.pinpoints().forEach(d -> sensors.add(d.name() + " (goBILDA Pinpoint)"));
        cfg.otos().forEach(d -> sensors.add(d.name() + " (SparkFun OTOS)"));
        cfg.octoquads().forEach(d -> sensors.add(d.name() + " (OctoQuad)"));
        cfg.limelights().forEach(d -> sensors.add(d.name() + " (Limelight)"));
        cfg.webcams().forEach(d -> sensors.add(d.name() + " (webcam)"));
        cfg.switches().forEach(d -> sensors.add(d.name() + " (switch)"));
        if (cfg.imu() != null) sensors.add(cfg.imu().name() + " (IMU)");
        return Json.obj("sizeInches", List.of(Views.in(cfg.length()), Views.in(cfg.width()), Views.in(cfg.height())),
                "massKg", Views.r2(cfg.mass()), "drivetrain", cfg.drivetrain(), "hubs", cfg.hubs(), "motors", motors, "servos", servos,
                "mechanisms", mechs, "sensors", sensors, "odometryPods", cfg.odometryPods().stream().map(RobotConfig.OdometryPod::name).toList(),
                "lights", cfg.lights().stream().map(RobotConfig.Light::name).toList(),
                "gamePieceHandling", Json.obj("intake", cfg.intake() != null, "hopper", cfg.hopper() != null,
                        "launcher", cfg.launcher() != null, "grippers", cfg.grippers().size()),
                "code", cfg.code(), "partsUsed", cfg.partsUsed());
    }

    private Tool.Result validateField(Json.Args a) throws IOException {
        Path f = resolve(a.str("path", "config/field.jsonc"));
        FieldConfig fc;
        try {
            fc = a.has("text") ? FieldConfig.parse(a.str("text"), "field (text)") : FieldConfig.load(f);
        } catch (ConfigException e) {
            return Tool.Result.of("ok", false, "error", e.getMessage());
        }
        List<Object> starts = new ArrayList<>();
        for (FieldConfig.StartPosition sp : fc.startPositions()) {
            starts.add(Json.obj("name", sp.name(), "alliance", sp.alliance(), "x", Views.in(sp.pose().x()), "y", Views.in(sp.pose().y()),
                    "heading", Views.heading(sp.pose().heading())));
        }
        return Tool.Result.of("ok", true, "name", fc.name(), "season", fc.season(), "sizeInches", Views.in(fc.size()),
                "startPositions", starts, "obstacles", fc.obstacles().stream().map(FieldConfig.Shape::name).toList(),
                "gamePieces", fc.gamePieces().size(), "seasonSettings", fc.seasonSettings(), "warnings", fc.warnings());
    }

    private Tool.Result parts(Json.Args a) {
        PartsCatalog cat = a.has("robot") ? PartsCatalog.forConfigFile(resolve(a.str("robot"))) : PartsCatalog.builtIn();
        if (a.has("id")) {
            String id = a.str("id");
            for (PartsCatalog.Part p : cat.all()) {
                if (p.id().equals(id) && (!a.has("category") || p.category().equals(a.str("category")))) {
                    return Tool.Result.of("part", Json.obj("category", p.category(), "id", p.id(), "name", p.name(), "vendor", p.vendor(),
                            "sku", p.sku(), "note", p.note(), "summary", PartsCatalog.summary(p), "specs", p.specs(), "source", p.source()));
                }
            }
            throw new Json.ToolError("No part with id " + id);
        }
        String q = a.str("query", "").toLowerCase(Locale.ROOT);
        List<Object> out = new ArrayList<>();
        for (PartsCatalog.Part p : a.has("category") ? cat.all(a.str("category")) : cat.all()) {
            String hay = (p.id() + " " + p.name() + " " + p.vendor() + " " + p.sku()).toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !hay.contains(q)) continue;
            out.add(Json.obj("category", p.category(), "id", p.id(), "name", p.name(), "vendor", p.vendor(), "summary", PartsCatalog.summary(p)));
        }
        return Tool.Result.of("parts", out, "count", out.size(), "categories", PartsCatalog.CATEGORIES, "warnings", cat.warnings(),
                "use", "In a robot config, name a part by id, e.g. {\"name\": \"lift\", \"part\": \"<id>\"} under motors.");
    }
}
