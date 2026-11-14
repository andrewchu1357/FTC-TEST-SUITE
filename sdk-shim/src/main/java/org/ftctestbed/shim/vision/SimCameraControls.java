package org.ftctestbed.shim.vision;

import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.CameraControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.ExposureControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.FocusControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.GainControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.PtzControl;
import org.firstinspires.ftc.robotcore.external.hardware.camera.controls.WhiteBalanceControl;
import org.firstinspires.ftc.robotcore.internal.collections.MutableReference;

import java.util.concurrent.TimeUnit;

/**
 * A webcam's UVC controls. Exposure and gain change the picture: in Manual mode, brightness goes
 * with exposure x (1 + gain / 170) relative to what auto exposure picks under venue lights
 * (AUTO_EXPOSURE_MS [ESTIMATE]), so the samples' 6 ms at gain 250 looks about as bright as auto,
 * with less motion blur and more noise. Too dark or washed out, tags stop decoding and colors
 * shift. Focus, white balance and pan/tilt/zoom are remembered but don't change the picture.
 * Settings last until the camera is power-cycled, as on a real webcam.
 */
public class SimCameraControls {
    /** What auto exposure settles on in a lit venue [ESTIMATE]. */
    public static final double AUTO_EXPOSURE_MS = 15;

    private final long minExposureNanos, maxExposureNanos;
    private final int minGain, maxGain;
    private final ExposureControl.Mode autoMode;

    private ExposureControl.Mode exposureMode;
    private long exposureNanos;
    private int gain;
    private boolean aePriority;
    private FocusControl.Mode focusMode;
    private double focusLength;
    private WhiteBalanceControl.Mode wbMode;
    private int wbTemperature;
    private int pan, tilt, zoom;

    private final ExposureControl exposure = new Exposure();
    private final GainControl gainControl = new Gain();
    private final FocusControl focus = new Focus();
    private final WhiteBalanceControl whiteBalance = new WhiteBalance();
    private final PtzControl ptz = new Ptz();

    /** Exposure range in 100 microsecond steps (UVC units), gain range, and the camera's auto exposure mode. */
    public SimCameraControls(int minExposure100us, int maxExposure100us, int minGain, int maxGain, ExposureControl.Mode autoMode) {
        this.minExposureNanos = minExposure100us * 100_000L;
        this.maxExposureNanos = maxExposure100us * 100_000L;
        this.minGain = minGain;
        this.maxGain = maxGain;
        this.autoMode = autoMode;
        powerCycle();
    }

    /** Defaults, as when the webcam is plugged in. */
    public synchronized void powerCycle() {
        exposureMode = autoMode;
        exposureNanos = (long) (AUTO_EXPOSURE_MS * 1e6);
        gain = minGain;
        aePriority = false;
        focusMode = FocusControl.Mode.ContinuousAuto;
        focusLength = 0;
        wbMode = WhiteBalanceControl.Mode.AUTO;
        wbTemperature = 4000;
        pan = tilt = 0;
        zoom = 100;
    }

    /** The control of this type (VisionPortal.getCameraControl), or null. */
    @SuppressWarnings("unchecked")
    public <T extends CameraControl> T get(Class<T> type) {
        for (CameraControl c : new CameraControl[]{exposure, gainControl, focus, whiteBalance, ptz}) {
            if (type.isInstance(c)) return (T) c;
        }
        return null;
    }

    /** How long the shutter is open (s). */
    public synchronized double exposureSeconds() {
        return exposureMode == ExposureControl.Mode.Manual ? exposureNanos / 1e9 : AUTO_EXPOSURE_MS / 1000;
    }

    /** Picture brightness relative to a well-exposed one. */
    public synchronized double brightness() {
        if (exposureMode != ExposureControl.Mode.Manual) return 1;
        return exposureNanos / 1e6 / AUTO_EXPOSURE_MS * (1 + gain / 170.0);
    }

    /** Sensor noise (0-255 units): gain amplifies it [ESTIMATE]. */
    public synchronized double noise() {
        return 1.0 + 2.0 * Math.max(0, gain - minGain) / Math.max(1, maxGain - minGain);
    }

    private class Exposure implements ExposureControl {
        @Override public Mode getMode() { synchronized (SimCameraControls.this) { return exposureMode; } }

        @Override
        public boolean setMode(Mode mode) {
            synchronized (SimCameraControls.this) {
                if (!isModeSupported(mode)) return false;
                exposureMode = mode;
                return true;
            }
        }

        @Override public boolean isModeSupported(Mode mode) { return mode == Mode.Manual || mode == autoMode; }
        @Override public long getMinExposure(TimeUnit unit) { return unit.convert(minExposureNanos, TimeUnit.NANOSECONDS); }
        @Override public long getMaxExposure(TimeUnit unit) { return unit.convert(maxExposureNanos, TimeUnit.NANOSECONDS); }
        @Override public long getExposure(TimeUnit unit) { return unit.convert((long) (exposureSeconds() * 1e9), TimeUnit.NANOSECONDS); }

        @Override
        public long getCachedExposure(TimeUnit unit, MutableReference<Boolean> refreshed, long permittedStaleness, TimeUnit permittedStalenessUnit) {
            if (refreshed != null) refreshed.setValue(true);
            return getExposure(unit);
        }

        /** Remembered in any mode; only used in Manual (auto exposure decides otherwise). */
        @Override
        public boolean setExposure(long duration, TimeUnit unit) {
            long nanos = TimeUnit.NANOSECONDS.convert(duration, unit);
            synchronized (SimCameraControls.this) {
                if (nanos < minExposureNanos || nanos > maxExposureNanos) return false;
                exposureNanos = Math.max(100_000L, nanos / 100_000L * 100_000L); // UVC steps of 100 microseconds
                return true;
            }
        }

        @Override public boolean isExposureSupported() { return true; }
        @Override public boolean getAePriority() { synchronized (SimCameraControls.this) { return aePriority; } }

        @Override
        public boolean setAePriority(boolean priority) {
            synchronized (SimCameraControls.this) {
                aePriority = priority;
                return true;
            }
        }
    }

    private class Gain implements GainControl {
        @Override public int getMinGain() { return minGain; }
        @Override public int getMaxGain() { return maxGain; }
        @Override public int getGain() { synchronized (SimCameraControls.this) { return gain; } }

        @Override
        public boolean setGain(int g) {
            synchronized (SimCameraControls.this) {
                if (g < minGain || g > maxGain) return false;
                gain = g;
                return true;
            }
        }
    }

    private class Focus implements FocusControl {
        @Override public Mode getMode() { synchronized (SimCameraControls.this) { return focusMode; } }

        @Override
        public boolean setMode(Mode mode) {
            synchronized (SimCameraControls.this) {
                if (!isModeSupported(mode)) return false;
                focusMode = mode;
                return true;
            }
        }

        @Override public boolean isModeSupported(Mode mode) { return mode == Mode.ContinuousAuto || mode == Mode.Fixed; }
        @Override public double getMinFocusLength() { return 0; }
        @Override public double getMaxFocusLength() { return 250; }
        @Override public double getFocusLength() { synchronized (SimCameraControls.this) { return focusLength; } }

        @Override
        public boolean setFocusLength(double length) {
            synchronized (SimCameraControls.this) {
                if (length < 0 || length > 250) return false;
                focusLength = length;
                return true;
            }
        }

        @Override public boolean isFocusLengthSupported() { return true; }
    }

    private class WhiteBalance implements WhiteBalanceControl {
        @Override public Mode getMode() { synchronized (SimCameraControls.this) { return wbMode; } }

        @Override
        public boolean setMode(Mode mode) {
            synchronized (SimCameraControls.this) {
                if (mode == Mode.UNKNOWN) return false;
                wbMode = mode;
                return true;
            }
        }

        @Override public int getMinWhiteBalanceTemperature() { return 2000; }
        @Override public int getMaxWhiteBalanceTemperature() { return 6500; }
        @Override public int getWhiteBalanceTemperature() { synchronized (SimCameraControls.this) { return wbTemperature; } }

        @Override
        public boolean setWhiteBalanceTemperature(int temperature) {
            synchronized (SimCameraControls.this) {
                if (temperature < 2000 || temperature > 6500) return false;
                wbTemperature = temperature;
                return true;
            }
        }
    }

    private class Ptz implements PtzControl {
        @Override
        public PanTiltHolder getPanTilt() {
            synchronized (SimCameraControls.this) {
                PanTiltHolder h = new PanTiltHolder();
                h.pan = pan;
                h.tilt = tilt;
                return h;
            }
        }

        @Override
        public boolean setPanTilt(PanTiltHolder h) {
            synchronized (SimCameraControls.this) {
                if (Math.abs(h.pan) > 36000 || Math.abs(h.tilt) > 36000) return false;
                pan = h.pan;
                tilt = h.tilt;
                return true;
            }
        }

        @Override
        public PanTiltHolder getMinPanTilt() {
            PanTiltHolder h = new PanTiltHolder();
            h.pan = -36000;
            h.tilt = -36000;
            return h;
        }

        @Override
        public PanTiltHolder getMaxPanTilt() {
            PanTiltHolder h = new PanTiltHolder();
            h.pan = 36000;
            h.tilt = 36000;
            return h;
        }

        @Override public int getZoom() { synchronized (SimCameraControls.this) { return zoom; } }

        @Override
        public boolean setZoom(int z) {
            synchronized (SimCameraControls.this) {
                if (z < 100 || z > 500) return false;
                zoom = z;
                return true;
            }
        }

        @Override public int getMinZoom() { return 100; }
        @Override public int getMaxZoom() { return 500; }
    }
}
