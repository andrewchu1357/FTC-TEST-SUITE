package org.ftctestbed.sim.game;

import org.ftctestbed.sim.config.ConfigException;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSeason;

import java.util.Map;

/** Season plug-ins by the name used in field.jsonc ("season": "..."). */
public final class Seasons {
    private Seasons() {}

    public static Season create(String name, Map<String, Object> settings) {
        return switch (name.toLowerCase()) {
            case "biobuzz" -> new BioBuzzSeason(settings);
            default -> throw new ConfigException("Unknown season \"" + name + "\". Known seasons: biobuzz");
        };
    }
}
