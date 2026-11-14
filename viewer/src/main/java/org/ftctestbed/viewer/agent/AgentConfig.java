package org.ftctestbed.viewer.agent;

import org.ftctestbed.sim.config.ConfigNode;
import org.ftctestbed.sim.config.Jsonc;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * config/agent-api.jsonc: whether the window serves agents over HTTP (and on which port), which
 * project files agents may only read, and whether they may run Java snippets. Missing file:
 * the defaults below.
 */
public record AgentConfig(boolean enabled, int port, String token, List<String> readOnly, boolean javaEval) {
    public static final int DEFAULT_PORT = 8767;
    /** Clones of a team's own repository stay as they are unless the config says otherwise. */
    public static final List<String> DEFAULT_READ_ONLY = List.of("robots/*/repo/**");
    /** Never readable or writable through the API, whatever the config says. */
    private static final List<String> HIDDEN = List.of("config/agent-api.jsonc");

    public static AgentConfig defaults() {
        return new AgentConfig(false, DEFAULT_PORT, "", DEFAULT_READ_ONLY, true);
    }

    public static AgentConfig load(Path project) {
        Path file = project.resolve("config/agent-api.jsonc");
        if (!Files.exists(file)) return defaults();
        try {
            ConfigNode n = ConfigNode.fromObject(file.getFileName().toString(), Jsonc.parse(Files.readString(file), file.toString()));
            List<String> ro = n.has("readOnly") ? new ArrayList<>(n.getStringList("readOnly")) : new ArrayList<>(DEFAULT_READ_ONLY);
            return new AgentConfig(n.getBoolean("enabled", false), n.getInt("port", DEFAULT_PORT), n.getString("token", ""),
                    ro, n.getBoolean("javaEval", true));
        } catch (Exception e) {
            throw new IllegalArgumentException("config/agent-api.jsonc: " + e.getMessage());
        }
    }

    /** True for files agents may not see at all. */
    public static boolean hidden(Path project, Path file) {
        return matches(project, file, HIDDEN);
    }

    public boolean readOnly(Path project, Path file) {
        return matches(project, file, readOnly);
    }

    private static boolean matches(Path project, Path file, List<String> globs) {
        Path rel;
        try {
            rel = project.relativize(file.toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            return false;
        }
        String r = rel.toString().replace('\\', '/');
        for (String g : globs) {
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + g);
            if (m.matches(Path.of(r))) return true;
            // "dir/**" also covers the folder itself
            if (g.endsWith("/**") && FileSystems.getDefault().getPathMatcher("glob:" + g.substring(0, g.length() - 3)).matches(Path.of(r))) return true;
        }
        return false;
    }
}
