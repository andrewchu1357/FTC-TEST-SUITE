package com.qualcomm.robotcore.hardware;

public class PIDCoefficients {
    public double p, i, d;

    public PIDCoefficients() {}

    public PIDCoefficients(double p, double i, double d) {
        this.p = p;
        this.i = i;
        this.d = d;
    }

    public PIDCoefficients(PIDCoefficients other) {
        this(other.p, other.i, other.d);
    }

    @Override
    public String toString() {
        return String.format("%s(p=%f i=%f d=%f)", getClass().getSimpleName(), p, i, d);
    }
}
