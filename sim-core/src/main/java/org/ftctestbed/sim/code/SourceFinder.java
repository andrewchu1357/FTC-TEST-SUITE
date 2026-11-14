package org.ftctestbed.sim.code;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Works out which folders hold a team's robot code, whatever folder they picked: the whole
 * FtcRobotController repository, the TeamCode module, or a source folder.
 */
public final class SourceFinder {
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private SourceFinder() {}

    /** Source roots (folders where package directories start) under the chosen folder. */
    public static List<Path> roots(Path chosen) throws IOException {
        chosen = chosen.toAbsolutePath().normalize();
        // The usual layouts first.
        for (Path p : new Path[]{chosen.resolve("TeamCode/src/main/java"), chosen.resolve("src/main/java")}) {
            if (Files.isDirectory(p)) return List.of(p);
        }
        if (chosen.endsWith(Path.of("src", "main", "java"))) return List.of(chosen);
        // Otherwise derive roots from each file's package declaration.
        Set<Path> roots = new LinkedHashSet<>();
        for (Path f : javaFiles(chosen)) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            Matcher m = PACKAGE.matcher(text);
            Path dir = f.getParent();
            if (m.find()) {
                String[] parts = m.group(1).split("\\.");
                Path root = dir;
                boolean matches = true;
                for (int i = parts.length - 1; i >= 0; i--) {
                    if (root == null || !root.getFileName().toString().equals(parts[i])) {
                        matches = false;
                        break;
                    }
                    root = root.getParent();
                }
                roots.add(matches && root != null ? root : dir);
            } else {
                roots.add(dir);
            }
        }
        return new ArrayList<>(roots);
    }

    /** .java files under a folder, skipping build output, tests and the SDK's FtcRobotController module. */
    public static List<Path> javaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .filter(p -> !skipped(root.relativize(p)))
                    .sorted()
                    .toList();
        }
    }

    private static boolean skipped(Path rel) {
        for (Path part : rel) {
            String n = part.toString();
            if (n.equals("build") || n.equals("test") || n.equals("androidTest") || n.equals("FtcRobotController")
                    || n.startsWith(".")) return true;
        }
        return false;
    }
}
