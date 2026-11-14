package org.ftctestbed.sim.code;

import org.ftctestbed.sim.config.Jsonc;
import org.ftctestbed.sim.config.RobotConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Creates robot packages ({@code robots/<name>/}) from a team's code and/or a config template. */
public final class RobotPackages {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _-]{0,40}");

    /**
     * code: a folder (repository, TeamCode module or source folder) or a .zip, or null for none.
     * link: use the code where it is instead of copying it (folders only).
     * template: a robot config to copy, or null to write one from the code's hardware names.
     */
    public record Options(Path projectDir, String name, Path code, boolean link, Path template) {}

    public record Result(Path robotFile, List<HardwareScanner.Device> devices, int javaFiles, List<String> report) {}

    private RobotPackages() {}

    public static Path packageDir(Path projectDir, String name) {
        return projectDir.resolve("robots").resolve(name.trim());
    }

    public static Result create(Options o) throws IOException {
        String name = o.name().trim();
        if (!NAME.matcher(name).matches()) {
            throw new IOException("Use a short robot name with letters, digits, spaces, - or _ (got \"" + name + "\")");
        }
        Path dir = packageDir(o.projectDir(), name);
        if (Files.exists(dir)) throw new IOException("robots/" + name + " already exists. Pick another name or delete that folder.");
        List<String> report = new ArrayList<>();
        Files.createDirectories(dir);

        List<Path> roots = new ArrayList<>();
        String codeDir = null; // for the config's "code" section when linking
        if (o.code() != null) {
            Path code = o.code().toAbsolutePath().normalize();
            boolean zip = Files.isRegularFile(code) && code.toString().toLowerCase().endsWith(".zip");
            if (zip) {
                unzip(code, dir.resolve("code"));
                roots.addAll(SourceFinder.roots(dir.resolve("code")));
                report.add("Unpacked " + code.getFileName() + " into robots/" + name + "/code.");
            } else if (!Files.isDirectory(code)) {
                throw new IOException("Code folder not found: " + code);
            } else if (o.link()) {
                roots.addAll(SourceFinder.roots(code));
                codeDir = relativeOrAbsolute(dir, code);
                report.add("Linked to your code in " + code + " (the simulator compiles whatever is there each time it opens this robot).");
            } else {
                for (Path root : SourceFinder.roots(code)) {
                    copyJavaTree(root, dir.resolve("code"));
                }
                roots.addAll(SourceFinder.roots(dir.resolve("code")));
                report.add("Copied the code from " + code + " into robots/" + name + "/code.");
            }
            if (roots.isEmpty()) report.add("WARNING: no .java files found in " + code);
        }
        int javaFiles = 0;
        for (Path r : roots) javaFiles += SourceFinder.javaFiles(r).size();
        List<HardwareScanner.Device> devices = HardwareScanner.scan(roots);

        String config;
        if (o.template() == null) {
            config = RobotConfigGenerator.generate(name, devices);
            report.add("Wrote robot.jsonc from " + devices.size() + " hardware names in the code. Check the TODOs in it.");
        } else {
            config = Files.readString(o.template());
            report.add("Copied " + o.template().getFileName() + " as robot.jsonc.");
        }
        if (codeDir != null) config = withCodeSection(config, codeDir);
        Path robotFile = dir.resolve("robot.jsonc");
        Files.writeString(robotFile, config);

        if (o.template() != null && !devices.isEmpty()) {
            try {
                for (String m : HardwareScanner.missing(devices, RobotConfig.load(robotFile))) report.add("CHECK: " + m);
            } catch (RuntimeException e) {
                report.add("CHECK: " + e.getMessage());
            }
        }
        return new Result(robotFile, devices, javaFiles, report);
    }

    /** Adds "code": { "dirs": [dir] } as the first setting of a robot config. */
    static String withCodeSection(String config, String dir) {
        int brace = config.indexOf("\n{");
        int at = brace >= 0 ? brace + 2 : config.indexOf('{') + 1;
        String section = "\n  // Your team code, compiled each time the simulator opens this robot.\n"
                + "  \"code\": { \"dirs\": [" + Jsonc.quote(dir) + "] },\n";
        return config.substring(0, at) + section + config.substring(at).replaceFirst("^\\n", "");
    }

    private static String relativeOrAbsolute(Path from, Path to) {
        try {
            if (from.getRoot() != null && from.getRoot().equals(to.getRoot())) return from.relativize(to).toString().replace('\\', '/');
        } catch (IllegalArgumentException ignored) {
        }
        return to.toString();
    }

    private static void copyJavaTree(Path root, Path target) throws IOException {
        for (Path f : SourceFinder.javaFiles(root)) {
            Path to = target.resolve(root.relativize(f).toString());
            Files.createDirectories(to.getParent());
            Files.copy(f, to, StandardCopyOption.REPLACE_EXISTING);
        }
        // Keep non-Java resources that sit with the sources (rare, but some code reads them).
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(p -> Files.isRegularFile(p) && !p.toString().endsWith(".java")).toList()) {
                Path rel = root.relativize(f);
                boolean skip = false;
                for (Path part : rel) skip |= part.toString().startsWith(".") || part.toString().equals("build");
                if (skip) continue;
                Path to = target.resolve(rel.toString());
                Files.createDirectories(to.getParent());
                Files.copy(f, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void unzip(Path zip, Path target) throws IOException {
        Path base = target.toAbsolutePath().normalize();
        Files.createDirectories(base);
        try (InputStream in = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                Path to = base.resolve(e.getName()).normalize();
                if (!to.startsWith(base)) throw new IOException("Unsafe path in zip: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(z, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
