package org.firstinspires.ftc.robotcore.external.hardware.camera.controls;

import org.firstinspires.ftc.robotcore.internal.collections.MutableReference;

import java.util.concurrent.TimeUnit;

/** How long each frame's shutter is open. */
public interface ExposureControl extends CameraControl {
    long unknownExposure = 0;

    enum Mode {
        Unknown, Auto, ContinuousAuto, Manual, ShutterPriority, AperturePriority;

        public static Mode fromId(int id) {
            return id >= 0 && id < values().length ? values()[id] : Unknown;
        }
    }

    Mode getMode();

    boolean setMode(Mode mode);

    boolean isModeSupported(Mode mode);

    long getMinExposure(TimeUnit resultUnit);

    long getMaxExposure(TimeUnit resultUnit);

    long getExposure(TimeUnit resultUnit);

    long getCachedExposure(TimeUnit resultUnit, MutableReference<Boolean> refreshed, long permittedStaleness, TimeUnit permittedStalenessUnit);

    boolean setExposure(long duration, TimeUnit durationUnit);

    boolean isExposureSupported();

    boolean getAePriority();

    boolean setAePriority(boolean priority);
}
