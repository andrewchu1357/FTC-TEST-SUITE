package org.ftctestbed.sim.hardware;

import com.qualcomm.robotcore.hardware.PwmControl;
import com.qualcomm.robotcore.hardware.ServoControllerEx;

/**
 * The six servo ports of one simulated hub. Each write the hub doesn't already have (a new
 * position, PWM on/off, a new range) is one hub command, like the SDK's LynxServoController.
 */
public class SimServoController implements ServoControllerEx {
    private final SimLynxModule hub;
    private final HubTiming timing;
    private final ServoPort[] ports = new ServoPort[6];

    public SimServoController(SimLynxModule hub, HubTiming timing) {
        this.hub = hub;
        this.timing = timing;
    }

    public void setPort(int index, ServoPort port) { ports[index] = port; }

    public ServoPort port(int servo) {
        ThreadFence.check();
        if (servo < 0 || servo >= ports.length || ports[servo] == null) {
            throw new IllegalArgumentException("servo port " + servo + " on " + hub.name() + " is not configured");
        }
        return ports[servo];
    }

    @Override
    public void pwmEnable() {
        setAllPwm(true);
    }

    @Override
    public void pwmDisable() {
        setAllPwm(false);
    }

    private void setAllPwm(boolean on) {
        for (ServoPort p : ports) {
            if (p == null || p.isPwmEnabled() == on) continue;
            timing.command(hub);
            p.setPwmEnabled(on);
        }
    }

    @Override
    public PwmStatus getPwmStatus() {
        boolean anyOn = false, anyOff = false;
        for (ServoPort p : ports) {
            if (p == null) continue;
            if (p.isPwmEnabled()) anyOn = true; else anyOff = true;
        }
        return anyOn && anyOff ? PwmStatus.MIXED : anyOn ? PwmStatus.ENABLED : PwmStatus.DISABLED;
    }

    @Override
    public void setServoPosition(int servo, double position) {
        ServoPort p = port(servo);
        if (!p.isPwmEnabled() || Math.max(0, Math.min(1, position)) != p.getCommanded()) timing.command(hub);
        p.setCommanded(position);
    }

    @Override public double getServoPosition(int servo) { return port(servo).getCommanded(); }

    @Override
    public void setPulseWidth(int servo, double microseconds) {
        ServoPort p = port(servo);
        if (!p.isPwmEnabled() || (int) microseconds != p.pulseMicros()) timing.command(hub);
        p.setPulseWidth(microseconds);
    }

    @Override public double getPulseWidth(int servo) { return port(servo).pulseMicros(); }

    @Override
    public void setServoPwmRange(int servo, PwmControl.PwmRange range) {
        ServoPort p = port(servo);
        if (!range.equals(p.getRange())) timing.command(hub);
        p.setRange(range);
    }

    @Override public PwmControl.PwmRange getServoPwmRange(int servo) { return port(servo).getRange(); }
    @Override public void setServoPwmEnable(int servo) { setPwm(servo, true); }
    @Override public void setServoPwmDisable(int servo) { setPwm(servo, false); }

    private void setPwm(int servo, boolean on) {
        ServoPort p = port(servo);
        if (p.isPwmEnabled() != on) timing.command(hub);
        p.setPwmEnabled(on);
    }
    @Override public boolean isServoPwmEnabled(int servo) { return port(servo).isPwmEnabled(); }

    @Override public Manufacturer getManufacturer() { return Manufacturer.Lynx; }
    @Override public String getDeviceName() { return "Simulated REV Hub Servo Controller"; }
    @Override public String getConnectionInfo() { return hub.getConnectionInfo(); }
    @Override public int getVersion() { return 1; }

    @Override
    public void resetDeviceConfigurationForOpMode() {
        for (ServoPort p : ports) if (p != null) p.resetForOpMode();
    }

    @Override public void close() {}
}
