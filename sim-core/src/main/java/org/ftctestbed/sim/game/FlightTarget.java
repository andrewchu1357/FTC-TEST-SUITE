package org.ftctestbed.sim.game;

/** Something a flying piece can go into (a goal opening). */
public interface FlightTarget {
    /**
     * Called every step for each airborne piece that moved from (x0,y0,z0) to the piece's
     * current position. Return true if the piece was captured; the target is responsible for
     * calling {@link GamePieces#contain}.
     */
    boolean tryCapture(GamePiece p, double x0, double y0, double z0);

    String name();
}
