package org.ftctestbed.sim.game;

import org.dyn4j.dynamics.Body;

/**
 * One game piece. Owned by the physics thread; other threads only see it through Snapshot.
 *   FLOOR     - a dyn4j body on the tiles (pushed by robots, rolls)
 *   AIRBORNE  - flying (launched, dropped or spilled); ballistic with air drag
 *   HELD      - inside a robot (hopper or gripper)
 *   CONTAINED - inside a field element (HIVE cell, FLOWER, ...)
 *   OFF_FIELD - outside the field (left the field, or waiting with a human player)
 */
public final class GamePiece {
    public enum State { FLOOR, AIRBORNE, HELD, CONTAINED, OFF_FIELD }

    public final int id;
    public final PieceKind kind;

    State state = State.OFF_FIELD;
    Body body;
    double x, y, z, heading;
    double vx, vy, vz;
    double wx, wy, wz; // spin, rad/s (only while airborne)
    Object holder;
    GamePieces.Trajectory trajectory;
    /** Alliance of the robot that last launched or released this piece, or null. */
    String lastTouchedBy;
    /** Just launched or dropped by a robot (its body): passes out through that robot until clear of it. */
    Object exitingFrom;
    /** Spilled by a tipping HIVE and hasn't touched anything since (rule G409: robots mustn't catch it). */
    boolean fromTip;
    /** The robot (number) that a spilled piece hit before anything else, or 0. */
    int caughtBy;

    GamePiece(int id, PieceKind kind) {
        this.id = id;
        this.kind = kind;
    }

    public State state() { return state; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public double heading() { return heading; }
    public double vx() { return vx; }
    public double vy() { return vy; }
    public double vz() { return vz; }
    public Object holder() { return holder; }
    public String lastTouchedBy() { return lastTouchedBy; }
    public double speed() { return Math.sqrt(vx * vx + vy * vy + vz * vz); }

    /** Position bookkeeping for pieces moved by their holder (hopper, field element). */
    public void setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void setVelocity(double vx, double vy, double vz) {
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
    }

    /** Spin vector (rad/s), field frame. */
    public void setSpin(double wx, double wy, double wz) {
        this.wx = wx;
        this.wy = wy;
        this.wz = wz;
    }

    public double spinRate() { return Math.sqrt(wx * wx + wy * wy + wz * wz); }

    /** The robot (its body) the piece is still passing out of, or null. */
    public Object exitingFrom() { return exitingFrom; }
    public boolean fromTip() { return fromTip; }
    public void setFromTip(boolean spilled) { fromTip = spilled; }
    public int caughtBy() { return caughtBy; }

    /** A robot touched it: if a tipping HIVE spilled it and nothing else has touched it, that robot caught it. */
    public void touchedByRobot(int robot) {
        if (fromTip && caughtBy == 0) caughtBy = robot;
        fromTip = false;
    }

    public void clearCaught() { caughtBy = 0; }
    public void setExitingFrom(Object robot) { exitingFrom = robot; }

    @Override
    public String toString() {
        return kind.id() + "#" + id + "[" + state + "]";
    }
}
