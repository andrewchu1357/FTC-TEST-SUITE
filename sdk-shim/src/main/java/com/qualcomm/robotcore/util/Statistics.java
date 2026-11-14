package com.qualcomm.robotcore.util;

/** Running mean and variance of a stream of numbers (Welford's method), like the SDK's. */
public class Statistics {
    protected int n;
    protected double mean;
    protected double m2;

    public Statistics() {
        clear();
    }

    public int getCount() { return n; }

    public double getMean() { return mean; }

    /** The sample variance (divided by n - 1). */
    public double getVariance() { return n < 2 ? 0 : m2 / (n - 1); }

    public double getStandardDeviation() { return Math.sqrt(getVariance()); }

    public void clear() {
        n = 0;
        mean = 0;
        m2 = 0;
    }

    public void add(double x) {
        n++;
        double delta = x - mean;
        mean += delta / n;
        m2 += delta * (x - mean);
    }

    public void remove(double x) {
        if (n <= 1) {
            clear();
            return;
        }
        double oldMean = (n * mean - x) / (n - 1);
        m2 -= (x - mean) * (x - oldMean);
        mean = oldMean;
        n--;
    }
}
