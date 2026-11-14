package com.qualcomm.robotcore.hardware;

public interface PwmControl {
    class PwmRange {
        public static final double usFrameDefault = 20000;
        public static final PwmRange defaultRange = new PwmRange(600, 2400);

        public final double usPulseLower;
        public final double usPulseUpper;
        public final double usFrame;

        public PwmRange(double usPulseLower, double usPulseUpper) {
            this(usPulseLower, usPulseUpper, usFrameDefault);
        }

        public PwmRange(double usPulseLower, double usPulseUpper, double usFrame) {
            this.usPulseLower = usPulseLower;
            this.usPulseUpper = usPulseUpper;
            this.usFrame = usFrame;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PwmRange)) return false;
            PwmRange r = (PwmRange) o;
            return usPulseLower == r.usPulseLower && usPulseUpper == r.usPulseUpper && usFrame == r.usFrame;
        }

        @Override
        public int hashCode() {
            return Double.valueOf(usPulseLower).hashCode() ^ Double.valueOf(usPulseUpper).hashCode() ^ Double.valueOf(usFrame).hashCode();
        }

        @Override
        public String toString() {
            return "PwmRange(" + usPulseLower + "-" + usPulseUpper + "us)";
        }
    }

    void setPwmRange(PwmRange range);

    PwmRange getPwmRange();

    void setPwmEnable();

    void setPwmDisable();

    boolean isPwmEnabled();

    /** SDK 11.2: the pulse width in microseconds, bypassing the PWM range (advanced use). */
    void setPulseWidth(double microseconds);

    double getPulseWidth();
}
