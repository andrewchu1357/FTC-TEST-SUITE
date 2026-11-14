package org.firstinspires.ftc.vision.opencv;

import org.opencv.core.Point;

/** A blob's smallest enclosing circle (pixels). */
public class Circle {
    private final float x, y, radius;

    public Circle(Point center, float radius) {
        this((float) center.x, (float) center.y, radius);
    }

    public Circle(float x, float y, float radius) {
        this.x = x;
        this.y = y;
        this.radius = radius;
    }

    public Point getCenter() { return new Point(x, y); }
    public float getRadius() { return radius; }
    public float getX() { return x; }
    public float getY() { return y; }
}
