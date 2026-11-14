package org.opencv.core;

/** OpenCV's 2D size. */
public class Size {
    public double width, height;

    public Size() { this(0, 0); }
    public Size(double width, double height) { this.width = width; this.height = height; }
    public Size(Point p) { this(p.x, p.y); }

    public double area() { return width * height; }
    public boolean empty() { return width <= 0 || height <= 0; }
    public Size clone() { return new Size(width, height); }

    @Override public String toString() { return width + "x" + height; }
}
