package org.ftctestbed.viewer.three;

/**
 * Turns a fast spin into one the eye can follow. A flywheel at 6000 rpm turns 600 degrees
 * between two frames at 60 frames a second, an intake roller at 1150 rpm 115, so drawn as they
 * are they stand still, flicker, or seem to turn backwards (the wagon-wheel effect). This shows
 * the same direction at no more than maxStep degrees a frame; give it less than half of the
 * part's repeat angle (a wheel with 10 rollers looks the same every 36 degrees: under 18).
 */
final class Spin {
    private final double maxStep;
    private double last = Double.NaN, shown;

    Spin(double maxStep) {
        this.maxStep = maxStep;
    }

    /** The angle to draw, given the part's real angle (degrees, not wrapped). */
    double next(double degrees) {
        if (Double.isNaN(last)) {
            last = degrees;
            shown = degrees % 360;
            return shown;
        }
        double d = degrees - last;
        last = degrees;
        shown = (shown + Math.max(-maxStep, Math.min(maxStep, d))) % 360;
        return shown;
    }
}
