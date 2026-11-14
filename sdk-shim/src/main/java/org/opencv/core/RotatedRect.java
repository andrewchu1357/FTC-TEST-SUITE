package org.opencv.core;

/** OpenCV's rectangle turned by an angle (degrees): a blob's box fit. */
public class RotatedRect {
    public Point center;
    public Size size;
    public double angle;

    public RotatedRect() { this(new Point(), new Size(), 0); }
    public RotatedRect(Point center, Size size, double angle) { this.center = center; this.size = size; this.angle = angle; }

    /** The four corners. */
    public void points(Point[] pt) {
        double a = Math.toRadians(angle), c = Math.cos(a) * 0.5, s = Math.sin(a) * 0.5;
        pt[0] = new Point(center.x - s * size.height - c * size.width, center.y + c * size.height - s * size.width);
        pt[1] = new Point(center.x + s * size.height - c * size.width, center.y - c * size.height - s * size.width);
        pt[2] = new Point(2 * center.x - pt[0].x, 2 * center.y - pt[0].y);
        pt[3] = new Point(2 * center.x - pt[1].x, 2 * center.y - pt[1].y);
    }

    public Rect boundingRect() {
        Point[] p = new Point[4];
        points(p);
        double minX = p[0].x, maxX = p[0].x, minY = p[0].y, maxY = p[0].y;
        for (Point q : p) {
            minX = Math.min(minX, q.x); maxX = Math.max(maxX, q.x);
            minY = Math.min(minY, q.y); maxY = Math.max(maxY, q.y);
        }
        return new Rect((int) Math.floor(minX), (int) Math.floor(minY), (int) Math.ceil(maxX - minX), (int) Math.ceil(maxY - minY));
    }

    public RotatedRect clone() { return new RotatedRect(center.clone(), size.clone(), angle); }

    @Override public String toString() { return "{ " + center + " " + size + " * " + angle + " }"; }
}
