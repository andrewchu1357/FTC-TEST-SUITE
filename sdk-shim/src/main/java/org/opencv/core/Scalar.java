package org.opencv.core;

/** OpenCV's up-to-4 channel value (a color in some color space). */
public class Scalar {
    public double[] val;

    public Scalar(double v0, double v1, double v2, double v3) { val = new double[]{v0, v1, v2, v3}; }
    public Scalar(double v0, double v1, double v2) { this(v0, v1, v2, 0); }
    public Scalar(double v0, double v1) { this(v0, v1, 0, 0); }
    public Scalar(double v0) { this(v0, 0, 0, 0); }
    public Scalar(double[] vals) {
        val = new double[4];
        System.arraycopy(vals, 0, val, 0, Math.min(4, vals.length));
    }

    public static Scalar all(double v) { return new Scalar(v, v, v, v); }
    public Scalar clone() { return new Scalar(val.clone()); }

    @Override public String toString() { return "[" + val[0] + ", " + val[1] + ", " + val[2] + ", " + val[3] + "]"; }
}
