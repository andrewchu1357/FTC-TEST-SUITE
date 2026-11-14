package org.ftctestbed.sim.season.biobuzz;

import org.ftctestbed.sim.game.FlightTarget;
import org.ftctestbed.sim.game.GamePiece;
import org.ftctestbed.sim.game.GamePieces;
import org.ftctestbed.sim.game.Retrievable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.ftctestbed.sim.season.biobuzz.BioBuzzField.*;

/**
 * A FLOWER: a vertical tube on the wall. Pieces enter through the 4 in opening at the top
 * and stack up; only POLLEN can be pulled out of the retrieval opening at the bottom.
 */
final class Flower implements FlightTarget, Retrievable {
    final int index;
    final double wallX, wallY, nx, ny;
    final double axisX, axisY;
    final List<GamePiece> stack = new ArrayList<>(); // bottom first
    private final GamePieces pieces;
    Consumer<GamePiece> onEnter = p -> {};

    Flower(int index, double[] def, GamePieces pieces) {
        this.index = index;
        this.wallX = def[0];
        this.wallY = def[1];
        this.nx = def[2];
        this.ny = def[3];
        this.axisX = wallX + nx * FLOWER_AXIS_FROM_WALL;
        this.axisY = wallY + ny * FLOWER_AXIS_FROM_WALL;
        this.pieces = pieces;
    }

    @Override
    public String name() {
        return "FLOWER " + (index + 1);
    }

    /** Height of the top of the stack. */
    double stackTop() {
        double z = FLOWER_LOWER_RING_TOP;
        for (GamePiece p : stack) z += 2 * p.kind.radius();
        return z;
    }

    /** Re-position stacked pieces after a change. */
    void restack() {
        double z = FLOWER_LOWER_RING_TOP;
        for (GamePiece p : stack) {
            p.setPosition(axisX, axisY, z + p.kind.radius());
            z += 2 * p.kind.radius();
        }
    }

    /** At least partially between the middle ring and the top ring. */
    boolean inScoringVolume(GamePiece p) {
        return p.z() - p.kind.radius() < FLOWER_TOP && p.z() + p.kind.radius() > FLOWER_SCORING_BOTTOM;
    }

    /** Alliance of the top-most scored NECTAR, or null. */
    String owner() {
        for (int i = stack.size() - 1; i >= 0; i--) {
            GamePiece p = stack.get(i);
            if (p.kind.alliance() != null && inScoringVolume(p)) return p.kind.alliance();
        }
        return null;
    }

    /** Alliance of the bottom-most scored NECTAR, or null. */
    String bottomNectar() {
        for (GamePiece p : stack) {
            if (p.kind.alliance() != null && inScoringVolume(p)) return p.kind.alliance();
        }
        return null;
    }

    int scoredCount() {
        int n = 0;
        for (GamePiece p : stack) if (inScoringVolume(p)) n++;
        return n;
    }

    void addStaged(GamePiece p) {
        stack.add(p);
        pieces.contain(p, this, axisX, axisY, 0, name());
        restack();
    }

    @Override
    public boolean tryCapture(GamePiece p, double x0, double y0, double z0) {
        if (!(z0 > FLOWER_TOP && p.z() <= FLOWER_TOP)) return false;
        double t = (z0 - FLOWER_TOP) / (z0 - p.z());
        double hx = x0 + t * (p.x() - x0), hy = y0 + t * (p.y() - y0);
        double d = Math.hypot(hx - axisX, hy - axisY);
        double r = p.kind.radius();
        if (d > FLOWER_OPENING_RADIUS + r * 0.6) return false; // misses the top ring entirely
        if (d > FLOWER_OPENING_RADIUS - r * 0.4 || stackTop() + 2 * r > FLOWER_TOP + r) {
            // Hits the rim or the FLOWER is full: bounces off toward the field.
            p.setPosition(hx + nx * 0.02, hy + ny * 0.02, FLOWER_TOP + r);
            p.setVelocity(nx * 0.5, ny * 0.5, Math.abs(p.vz()) * 0.25);
            return false;
        }
        stack.add(p);
        pieces.contain(p, this, axisX, axisY, 0, name());
        restack();
        onEnter.accept(p);
        return true;
    }

    // ---- Retrieval opening ----

    @Override
    public List<GamePiece> exposed() {
        List<GamePiece> out = new ArrayList<>();
        if (!stack.isEmpty() && stack.get(0).kind.alliance() == null) {
            GamePiece p = stack.get(0);
            p.setPosition(axisX, axisY, p.z());
            out.add(p);
        }
        return out;
    }

    @Override
    public void taken(GamePiece p) {
        stack.remove(p);
        restack();
    }
}
