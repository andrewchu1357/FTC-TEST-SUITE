package org.ftctestbed.sim.game;

/** Where we are in an FTC match. PRACTICE = free driving with no clock. */
public final class MatchState {
    public enum Phase { PRACTICE, AUTO, TRANSITION, TELEOP, ENDED }

    public static final double AUTO_SECONDS = 30;
    public static final double TRANSITION_SECONDS = 8;
    public static final double TELEOP_SECONDS = 120;

    private volatile Phase phase = Phase.PRACTICE;
    private volatile double phaseStart = 0;

    public Phase phase() { return phase; }
    public double phaseStart() { return phaseStart; }

    public void enter(Phase p, double now) {
        phaseStart = now;
        phase = p;
    }

    public double elapsedInPhase(double now) { return now - phaseStart; }

    public boolean inMatch() { return phase != Phase.PRACTICE; }

    /** Seconds on the match clock (2:30 at the start of AUTO), or NaN in practice. */
    public double clock(double now) {
        double e = now - phaseStart;
        return switch (phase) {
            case AUTO -> AUTO_SECONDS + TELEOP_SECONDS - e;
            case TRANSITION -> TELEOP_SECONDS;
            case TELEOP -> TELEOP_SECONDS - e;
            case ENDED -> 0;
            default -> Double.NaN;
        };
    }

    /** Seconds left in TELEOP (for endgame rules); +infinity before TELEOP, 0 after the match. */
    public double teleopRemaining(double now) {
        return switch (phase) {
            case TELEOP -> Math.max(0, TELEOP_SECONDS - (now - phaseStart));
            case ENDED -> 0;
            default -> Double.POSITIVE_INFINITY;
        };
    }
}
