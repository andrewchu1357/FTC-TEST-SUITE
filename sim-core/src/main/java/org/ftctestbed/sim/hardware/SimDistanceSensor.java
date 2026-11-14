package org.ftctestbed.sim.hardware;

import com.qualcomm.robotcore.hardware.DistanceSensor;

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;
import org.ftctestbed.sim.config.RobotConfig;

import java.util.Random;

/**
 * A time-of-flight distance sensor (REV 2m style). It takes a measurement every updatePeriod
 * (about 33 ms) and robot code reads the latest one; each measurement has noise that grows
 * with distance. The physics thread does the raycasting.
 */
public class SimDistanceSensor implements DistanceSensor {
    public final RobotConfig.DistanceSensor def;
    private final Runnable i2c;
    private Random random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(42));
    private volatile double distanceMeters = DistanceUnit.infinity;
    private double sinceMeasurement = Double.POSITIVE_INFINITY;

    /** i2c runs on every read (one I2C transaction, see HubTiming). */
    public SimDistanceSensor(RobotConfig.DistanceSensor def, Runnable i2c) {
        this.def = def;
        this.i2c = i2c;
    }

    /** Power on: the same noise and measurement timing as the first time. */
    public void powerCycle() {
        random = new java.util.Random(org.ftctestbed.sim.SimSeed.mix(42));
        sinceMeasurement = Double.POSITIVE_INFINITY;
        distanceMeters = DistanceUnit.infinity;
    }

    /** Physics thread, every step: true when it's time for a new measurement. */
    public boolean measurementDue(double dt) {
        sinceMeasurement += dt;
        if (sinceMeasurement < def.updatePeriod() - 1e-9) return false;
        sinceMeasurement = 0;
        return true;
    }

    /** Physics thread: a new measurement of the true distance. Pass DistanceUnit.infinity when nothing is in range. */
    public void setMeasured(double meters) {
        if (meters == DistanceUnit.infinity) {
            distanceMeters = meters;
            return;
        }
        double sigma = def.noise() + def.noiseFraction() * meters;
        distanceMeters = Math.max(0, meters + (sigma > 0 ? random.nextGaussian() * sigma : 0));
    }

    public double measuredMeters() { return distanceMeters; }

    @Override
    public double getDistance(DistanceUnit unit) {
        i2c.run();
        double d = distanceMeters;
        if (d == DistanceUnit.infinity) return distanceOutOfRange;
        return unit.fromMeters(d);
    }

    @Override public Manufacturer getManufacturer() { return Manufacturer.STMicroelectronics; }
    @Override public String getDeviceName() { return "Simulated Distance Sensor"; }
    @Override public String getConnectionInfo() { return "I2C"; }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() {}
    @Override public void close() {}
}
