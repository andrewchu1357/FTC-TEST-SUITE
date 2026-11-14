package org.opencv.core;

/** OpenCV's 2D point (pixels). */
public class Point {
    public double x, y;

    public Point() { this(0, 0); }
    public Point(double x, double y) { this.x = x; this.y = y; }
    public Point(double[] vals) { this(vals.length > 0 ? vals[0] : 0, vals.length > 1 ? vals[1] : 0); }

    public Point clone() { return new Point(x, y); }
    public double dot(Point p) { return x * p.x + y * p.y; }
    public boolean inside(Rect r) { return r.contains(this); }

    @Override public boolean equals(Object o) { return o instanceof Point && ((Point) o).x == x && ((Point) o).y == y; }
    @Override public int hashCode() { return Double.hashCode(x) * 31 + Double.hashCode(y); }
    @Override public String toString() { return "{" + x + ", " + y + "}"; }
}
