package org.ftctestbed.sim.game;

import org.ftctestbed.sim.physics.FieldWorld;

import java.util.List;

/** What a Season can see and touch. */
public interface SeasonContext {
    GamePieces pieces();

    FieldWorld world();

    /** Simulated time, seconds. */
    double now();

    MatchState match();

    /** The first robot. */
    RobotView robot();

    /** Every robot on the field, the first robot first. */
    default List<RobotView> robots() { return List.of(robot()); }

    double fieldSize();

    /** Config warnings (unknown settings etc.) to show the user. */
    List<String> warnings();
}
