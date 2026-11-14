package org.ftctestbed.sim.hardware;

import com.qualcomm.robotcore.hardware.PwmControl;

import org.ftctestbed.sim.config.RobotConfig;

/**
 * One servo port. The commanded position becomes a PWM pulse (via the port's PwmRange), and
 * the pulse becomes a target angle (via the servo's own range). The horn slews toward it at
 * the servo's rated speed. Like a real hub, PWM stays off until the first position is sent.
 * Code can also send a pulse width directly (SDK 11.2 setPulseWidth); it can lie outside the
 * PWM range, and the commanded position then reads back as the matching 0..1 position.
 */
public final class ServoPort {
    public final RobotConfig.Servo def;
    public final String hubName;
    public final int portNumber;

    private volatile double commanded = 0.5;
    /** A pulse width (us) sent directly, or NaN while positions are being sent. */
    private volatile double pulse = Double.NaN;
    private volatile boolean pwmEnabled = false;
    private volatile PwmControl.PwmRange defaultRange = PwmControl.PwmRange.defaultRange;
    private volatile PwmControl.PwmRange range = PwmControl.PwmRange.defaultRange;
    private volatile double angle = 0;

    public ServoPort(RobotConfig.Servo def, String hubName, int portNumber) {
        this.def = def;
        this.hubName = hubName;
        this.portNumber = portNumber;
    }

    public void resetForOpMode() {
        pwmEnabled = false;
        range = defaultRange;
    }

    /** The range the port starts with: the device type's (a Blinkin's is 500-2500 us). */
    public void setDefaultRange(PwmControl.PwmRange r) {
        defaultRange = r;
        range = r;
    }

    public double getCommanded() {
        double us = pulse;
        if (Double.isNaN(us)) return commanded;
        return Math.max(0, Math.min(1, (us - range.usPulseLower) / (range.usPulseUpper - range.usPulseLower)));
    }

    public void setCommanded(double position) {
        commanded = Math.max(0, Math.min(1, position));
        pulse = Double.NaN;
        pwmEnabled = true;
    }

    /** Sends this pulse width (whole microseconds, as the hub takes it) and turns PWM on. */
    public void setPulseWidth(double us) {
        pulse = (int) us;
        pwmEnabled = true;
    }

    public boolean isPwmEnabled() { return pwmEnabled; }
    public void setPwmEnabled(boolean enabled) { pwmEnabled = enabled; }
    public PwmControl.PwmRange getRange() { return range; }
    public void setRange(PwmControl.PwmRange r) { range = r; }

    /** Horn angle in radians; 0 = center of travel. For CR servos, accumulated rotation. */
    public double angle() { return angle; }

    /**
     * Where the servo horn actually is, as a 0..1 servo position under the current PWM
     * range (it lags the commanded position while moving). Only meaningful for positional servos.
     */
    public double actualPosition() {
        if (def.continuous()) return getCommanded();
        double pulse = def.usAtRangeStart() + (angle / def.rangeRad() + 0.5) * (def.usAtRangeEnd() - def.usAtRangeStart());
        return (pulse - range.usPulseLower) / (range.usPulseUpper - range.usPulseLower);
    }

    public double pulseMicros() {
        double us = pulse;
        if (!Double.isNaN(us)) return us;
        return range.usPulseLower + commanded * (range.usPulseUpper - range.usPulseLower);
    }

    /** Continuous-rotation power the servo is currently running at, -1..1. */
    public double crPower() {
        if (!pwmEnabled || !def.continuous()) return 0;
        double p = (pulseMicros() - 1500) / 500.0;
        if (Math.abs(p) < 0.02) return 0;
        return Math.max(-1, Math.min(1, p));
    }

    public void step(double dt) {
        if (!pwmEnabled) return;
        if (def.continuous()) {
            angle += crPower() * def.crMaxRpm() * 2 * Math.PI / 60.0 * dt;
            return;
        }
        double frac = (pulseMicros() - def.usAtRangeStart()) / (def.usAtRangeEnd() - def.usAtRangeStart());
        double target = (Math.max(0, Math.min(1, frac)) - 0.5) * def.rangeRad();
        double maxStep = (Math.PI / 3) / def.secPer60Deg() * dt;
        double delta = target - angle;
        angle += Math.max(-maxStep, Math.min(maxStep, delta));
    }
}
