package org.ftctestbed.sim.game;

import java.util.List;

/** Season state the viewer can show without knowing the game. */
public interface SeasonSnapshot {
    String title();

    int score(String alliance);

    /** Score lines for one alliance, e.g. "HIVE TIPS (auto)  1 x 20 = 20". */
    List<String> breakdown(String alliance);

    /** Ranking points earned so far, e.g. "POLLINATOR 1 (4 tips)". */
    List<String> rankingPoints(String alliance);

    /** Fouls, warnings and status messages, newest last. */
    List<String> notes();
}
