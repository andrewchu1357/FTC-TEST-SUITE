package org.opencv.core;

/** OpenCV's upright rectangle (pixels). */
public class Rect {
    public int x, y, width, height;

    public Rect() { this(0, 0, 0, 0); }
    public Rect(int x, int y, int width, int height) { this.x = x; this.y = y; this.width = width; this.height = height; }
    public Rect(Point p1, Point p2) {
        x = (int) Math.min(p1.x, p2.x);
        y = (int) Math.min(p1.y, p2.y);
        width = (int) Math.max(p1.x, p2.x) - x;
        height = (int) Math.max(p1.y, p2.y) - y;
    }

    public Point tl() { return new Point(x, y); }
    public Point br() { return new Point(x + width, y + height); }
    public Size size() { return new Size(width, height); }
    public double area() { return (double) width * height; }
    public boolean empty() { return width <= 0 || height <= 0; }
    public boolean contains(Point p) { return x <= p.x && p.x < x + width && y <= p.y && p.y < y + height; }
    public Rect clone() { return new Rect(x, y, width, height); }

    @Override public String toString() { return "{" + x + ", " + y + ", " + width + "x" + height + "}"; }
}
