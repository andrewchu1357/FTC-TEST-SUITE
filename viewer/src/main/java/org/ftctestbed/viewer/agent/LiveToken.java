package org.ftctestbed.viewer.agent;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.prefs.Preferences;

/**
 * The token agents present to the window's HTTP server: the one in config/agent-api.jsonc if set,
 * else one made for this computer's user on first use and kept in their preferences (so
 * tools/ftc-sim-mcp --live finds it without anyone copying it around).
 */
public final class LiveToken {
    private static final Preferences PREFS = Preferences.userRoot().node("org/ftctestbed/agent");

    private LiveToken() {}

    public static synchronized String get(AgentConfig cfg) {
        if (cfg.token() != null && !cfg.token().isBlank()) return cfg.token().trim();
        String t = PREFS.get("token", "");
        if (t.length() < 32) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            t = HexFormat.of().formatHex(b);
            PREFS.put("token", t);
            try {
                PREFS.flush();
            } catch (java.util.prefs.BackingStoreException ignored) {
                // kept for this run at least
            }
        }
        return t;
    }

    /** A new random token (the old one stops working). */
    public static synchronized String renew() {
        PREFS.remove("token");
        return get(AgentConfig.defaults());
    }
}
