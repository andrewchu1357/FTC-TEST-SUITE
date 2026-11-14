package org.opencv.core;

/** A contour with floating-point points. */
public class MatOfPoint2f extends Mat {
    private final Point[] points;

    public MatOfPoint2f(Point... points) {
        this.points = points;
    }

    public Point[] toArray() { return points.clone(); }
    public java.util.List<Point> toList() { return java.util.Arrays.asList(toArray()); }
}
