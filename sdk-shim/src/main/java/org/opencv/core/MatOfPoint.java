package org.opencv.core;

/** A contour: a list of integer pixel points. */
public class MatOfPoint extends Mat {
    private final Point[] points;

    public MatOfPoint(Point... points) {
        this.points = points;
    }

    public Point[] toArray() { return points.clone(); }
    public java.util.List<Point> toList() { return java.util.Arrays.asList(toArray()); }
}
