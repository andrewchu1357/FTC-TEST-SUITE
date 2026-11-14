package org.ftctestbed.sim.game;

import java.util.Random;

/**
 * A type of game piece. shape is "circle" (balls: radius) or "rect" (width x height).
 * alliance is "red", "blue" or null for neutral pieces. A piece made with variation has its
 * own copy with slightly different mass, size, bounce and rolling friction (see varied);
 * nominalRadius stays the type's.
 */
public record PieceKind(String id, String shape, double radius, double width, double height, double mass,
                        int color, double floorFriction, double restitution, String alliance, double nominalRadius) {

    public PieceKind(String id, String shape, double radius, double width, double height, double mass,
                     int color, double floorFriction, double restitution, String alliance) {
        this(id, shape, radius, width, height, mass, color, floorFriction, restitution, alliance, radius);
    }

    public static PieceKind ball(String id, double diameterMeters, double massKg, int color, String alliance) {
        return new PieceKind(id, "circle", diameterMeters / 2, diameterMeters, diameterMeters, massKg, color, 0.08, 0.45, alliance);
    }

    /**
     * How much pieces of one type differ from each other, as standard deviations (fractions):
     * every piece gets its own mass, diameter, restitution and rolling friction, within 2 of them.
     */
    public record Variation(double mass, double diameter, double restitution, double friction) {
        public static final Variation NONE = new Variation(0, 0, 0, 0);

        public boolean isNone() {
            return mass == 0 && diameter == 0 && restitution == 0 && friction == 0;
        }
    }

    /** This type with one piece's own values, drawn from the variation. */
    public PieceKind varied(Variation v, Random random) {
        if (v.isNone()) return this;
        double size = 1 + clipped(random) * v.diameter();
        return new PieceKind(id, shape, radius * size, width * size, height * size,
                mass * (1 + clipped(random) * v.mass()), color,
                floorFriction * Math.max(0.1, 1 + clipped(random) * v.friction()),
                Math.max(0, Math.min(0.95, restitution * (1 + clipped(random) * v.restitution()))), alliance, nominalRadius);
    }

    private static double clipped(Random random) {
        return Math.max(-2, Math.min(2, random.nextGaussian()));
    }

    public boolean isBall() {
        return shape.equals("circle");
    }

    /** Height of the piece when resting on the floor. */
    public double tallness() {
        return isBall() ? 2 * radius : Math.min(width, height);
    }
}
