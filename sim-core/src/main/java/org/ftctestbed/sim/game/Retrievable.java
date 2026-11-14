package org.ftctestbed.sim.game;

import java.util.List;

/** Pieces a robot's intake can pull out of a field element (e.g. the bottom of a FLOWER). */
public interface Retrievable {
    /** Pieces currently reachable at floor level; their x/y are where an intake must reach. */
    List<GamePiece> exposed();

    /** Called after a robot took one of the exposed pieces. */
    void taken(GamePiece p);
}
