package com.qualcomm.hardware.sparkfun;

import com.qualcomm.robotcore.hardware.HardwareDevice;
import com.qualcomm.robotcore.hardware.I2cDeviceSynchSimple;

/**
 * The SparkFun Qwiic LED Stick: 10 RGB LEDs, each with a brightness 0-31. Every call is one I2C
 * write. The simulator remembers what each LED shows.
 */
public class SparkFunLEDStick implements HardwareDevice {
    public static final int LEDS = 10;

    private final Runnable i2cWrite;
    private final int[] colors = new int[LEDS];
    private final int[] brightness = new int[LEDS];

    public SparkFunLEDStick(I2cDeviceSynchSimple deviceClient, boolean deviceClientIsOwned) {
        throw new UnsupportedOperationException("The simulator makes LED sticks from robot.jsonc (\"lights\")");
    }

    /** Simulator: i2cWrite runs for every command, as each is one I2C write. */
    public SparkFunLEDStick(Runnable i2cWrite) {
        this.i2cWrite = i2cWrite;
        for (int i = 0; i < LEDS; i++) brightness[i] = 31;
    }

    // Each write waits its I2C time before taking the lock (the simulator reads the colors under it).

    /** Sets LED number position (1-10) to a color (0xRRGGBB, as from android.graphics.Color). */
    public void setColor(int position, int color) {
        i2cWrite.run();
        synchronized (this) {
            if (position >= 1 && position <= LEDS) colors[position - 1] = color & 0xFFFFFF;
        }
    }

    public void setColor(int color) {
        i2cWrite.run();
        synchronized (this) {
            for (int i = 0; i < LEDS; i++) colors[i] = color & 0xFFFFFF;
        }
    }

    public void setColors(int[] newColors) {
        i2cWrite.run();
        synchronized (this) {
            for (int i = 0; i < LEDS && i < newColors.length; i++) colors[i] = newColors[i] & 0xFFFFFF;
        }
    }

    public void setBrightness(int position, int level) {
        i2cWrite.run();
        synchronized (this) {
            if (position >= 1 && position <= LEDS) brightness[position - 1] = Math.max(0, Math.min(31, level));
        }
    }

    public void setBrightness(int level) {
        i2cWrite.run();
        synchronized (this) {
            for (int i = 0; i < LEDS; i++) brightness[i] = Math.max(0, Math.min(31, level));
        }
    }

    public void turnAllOff() {
        i2cWrite.run();
        synchronized (this) {
            for (int i = 0; i < LEDS; i++) colors[i] = 0;
        }
    }

    /** Simulator: the color each LED shows (0xRRGGBB, dimmed by its brightness). */
    public synchronized int[] simColors() {
        int[] out = new int[LEDS];
        for (int i = 0; i < LEDS; i++) {
            double k = brightness[i] / 31.0;
            int c = colors[i];
            int r = (int) Math.round(((c >> 16) & 0xFF) * k), g = (int) Math.round(((c >> 8) & 0xFF) * k), b = (int) Math.round((c & 0xFF) * k);
            out[i] = (r << 16) | (g << 8) | b;
        }
        return out;
    }

    @Override public Manufacturer getManufacturer() { return Manufacturer.SparkFun; }
    @Override public String getDeviceName() { return "SparkFun Qwiic LED Stick"; }
    @Override public String getConnectionInfo() { return "I2C"; }
    @Override public int getVersion() { return 1; }
    @Override public void resetDeviceConfigurationForOpMode() {}
    @Override public void close() {}
}
