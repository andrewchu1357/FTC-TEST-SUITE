package org.ftctestbed.sim.game;

/** A solid object above the floor that flying pieces bounce off. */
public interface FlightObstacle {
    /** If the piece moved into this obstacle, push it back and change its velocity. */
    void collide(GamePiece p, double x0, double y0, double z0);
}
