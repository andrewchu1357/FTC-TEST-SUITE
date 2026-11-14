package org.ftctestbed.sim.hardware;

import com.qualcomm.robotcore.hardware.ColorRangeSensor;
import com.qualcomm.robotcore.hardware.I2cAddr;
import com.qualcomm.robotcore.hardware.NormalizedRGBA;
import com.qualcomm.robotcore.hardware.SwitchableLight;

import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit;

import java.util.function.IntSupplier;

/**
 * A REV Color Sensor V3 style sensor (color + proximity). The physics thread tells it the
 * color of whatever is in front of it (0xRRGGBB, or -1 for nothing).
 */
public class SimColorSensor implements ColorRangeSensor, SwitchableLight {
    public final String name;
    private final IntSupplier seen;
    private final Runnable i2c;
    private volatile float gain = 1;
    private volatile boolean light = true;
    private volatile I2cAddr address = I2cAddr.create7bit(0x52);

    /** i2c runs on every read (one I2C transaction, see HubTiming). */
    public SimColorSensor(String name, IntSupplier seenColor, Runnable i2c) {
        this.name = name;
        this.seen = seenColor;
        this.i2c = i2c;
    }

    /** Reflected light from a lit object of the given color, 0..1 per channel. */
    private float[] rgb() {
        int c = seen.getAsInt();
        if (c < 0) return new float[]{0.010f, 0.014f, 0.012f};
        float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
        float scale = light ? 0.35f : 0.05f; // objects reflect only part of the LED's light
        return new float[]{0.02f + r * scale, 0.02f + g * scale, 0.02f + b * scale};
    }

    @Override
    public NormalizedRGBA getNormalizedColors() {
        i2c.run();
        float[] c = rgb();
        NormalizedRGBA n = new NormalizedRGBA();
        n.red = Math.min(1, c[0] * gain);
        n.green = Math.min(1, c[1] * gain);
        n.blue = Math.min(1, c[2] * gain);
        n.alpha = Math.min(1, (c[0] + c[1] + c[2]) / 3 * gain);
        return n;
    }

    @Override public float getGain() { return gain; }
    @Override public void setGain(float newGain) { gain = newGain; }

    // Raw counts like the REV V3 (16-bit-ish range).
    // Each channel is its own I2C read, as with the SDK's REV Color Sensor V3 driver.
    @Override public int red() { i2c.run(); return Math.round(rgb()[0] * 4096); }
    @Override public int green() { i2c.run(); return Math.round(rgb()[1] * 4096); }
    @Override public int blue() { i2c.run(); return Math.round(rgb()[2] * 4096); }
    @Override public int alpha() { i2c.run(); float[] c = rgb(); return Math.round((c[0] + c[1] + c[2]) / 3 * 4096); }

    @Override
    public int argb() {
        return getNormalizedColors().toColor();
    }

    @Override public void enableLed(boolean enable) { light = enable; }
    @Override public void enableLight(boolean enable) { light = enable; }
    @Override public boolean isLightOn() { return light; }
    @Override public void setI2cAddress(I2cAddr newAddress) { address = newAddress; }
    @Override public I2cAddr getI2cAddress() { return address; }

    /** Proximity: about 1 cm when something is in front of the sensor, else beyond range. */
    @Override
    public double getDistance(DistanceUnit unit) {
        i2c.run();
        return seen.getAsInt() < 0 ? unit.fromCm(12) : unit.fromCm(1.2);
    }

    // As a light sensor: how much light comes back (0..1), and the raw count it's worked out from.
    @Override public double getLightDetected() { return getNormalizedColors().alpha; }
    @Override public double getRawLightDetected() { return alpha(); }
    @Override public double getRawLightDetectedMax() { return 4096; }
    @Override public String status() { return name + ": simulated color sensor"; }

    @Override public Manufacturer getManufacturer() { return Manufacturer.Broadcom; }
    @Override public String getDeviceName() { return "Simulated REV Color Sensor V3"; }
    @Override public String getConnectionInfo() { return "I2C"; }
    @Override public int getVersion() { return 3; }
    @Override public void resetDeviceConfigurationForOpMode() {}
    @Override public void close() {}
}
