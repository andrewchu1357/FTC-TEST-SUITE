package org.firstinspires.ftc.robotcore.external.navigation;

import java.util.Locale;

/** A magnetic field reading in tesla, with the time it was taken (System.nanoTime). */
public class MagneticFlux {
    public double x;
    public double y;
    public double z;
    public long acquisitionTime;

    public MagneticFlux() {
        this(0, 0, 0, 0);
    }

    public MagneticFlux(double x, double y, double z, long acquisitionTime) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.acquisitionTime = acquisitionTime;
    }

    @Override
    public String toString() {
        return String.format(Locale.getDefault(), "(%.3f %.3f %.3f)T", x, y, z);
    }
}
