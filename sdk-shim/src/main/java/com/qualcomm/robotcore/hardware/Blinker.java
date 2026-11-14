package com.qualcomm.robotcore.hardware;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/** Something with a status light that can show a color or a blink pattern (a REV hub's LED). */
public interface Blinker {
    /** One step of a pattern: a color (0xAARRGGBB, lit when not black) for a time. */
    class Step {
        protected int color = 0;
        protected int msDuration = 0;

        public Step() {}

        public Step(int color, long duration, TimeUnit unit) {
            this.color = color & 0xFFFFFF;
            this.msDuration = (int) unit.toMillis(duration);
        }

        public static Step nullStep() {
            return new Step();
        }

        public boolean isLit() { return color != 0; }

        public void setLit(boolean isEnabled) { setColor(isEnabled ? 0xFFFFFF : 0); }

        public int getColor() { return color; }

        public void setColor(int color) { this.color = color & 0xFFFFFF; }

        public int getDurationMs() { return msDuration; }

        public void setDuration(long duration, TimeUnit unit) { msDuration = (int) unit.toMillis(duration); }

        @Override
        public boolean equals(Object o) {
            return o instanceof Step && equals((Step) o);
        }

        public boolean equals(Step other) {
            return other != null && color == other.color && msDuration == other.msDuration;
        }

        @Override
        public int hashCode() {
            return color * 31 + msDuration;
        }

        @Override
        public String toString() {
            return String.format("[%06x, %d]", color, msDuration);
        }
    }

    void setPattern(Collection<Step> steps);

    Collection<Step> getPattern();

    void pushPattern(Collection<Step> steps);

    boolean patternStackNotEmpty();

    boolean popPattern();

    void setConstant(int color);

    void stopBlinking();

    int getBlinkerPatternMaxLength();
}
