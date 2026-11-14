package com.qualcomm.robotcore.util;

import java.util.ArrayDeque;

/** Mean and variance of the last few numbers added (a fixed-size window). */
public class MovingStatistics {
    private final Statistics statistics = new Statistics();
    private final ArrayDeque<Double> samples = new ArrayDeque<Double>();
    private final int capacity;

    public MovingStatistics(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("MovingStatistics capacity must be positive");
        this.capacity = capacity;
    }

    public int getCount() { return statistics.getCount(); }

    public double getMean() { return statistics.getMean(); }

    public double getVariance() { return statistics.getVariance(); }

    public double getStandardDeviation() { return statistics.getStandardDeviation(); }

    public void clear() {
        statistics.clear();
        samples.clear();
    }

    public void add(double x) {
        statistics.add(x);
        samples.addLast(x);
        if (samples.size() > capacity) statistics.remove(samples.removeFirst());
    }
}
