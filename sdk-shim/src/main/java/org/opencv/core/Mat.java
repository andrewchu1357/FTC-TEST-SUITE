package org.opencv.core;

/**
 * An OpenCV image. The simulator doesn't make camera pictures, so a VisionProcessor's
 * processFrame() never gets one; this class exists so processors written for the SDK compile.
 */
public class Mat {
    private final int rows, cols;

    public Mat() { this(0, 0); }
    public Mat(int rows, int cols, int type) { this(rows, cols); }
    private Mat(int rows, int cols) { this.rows = rows; this.cols = cols; }

    public int rows() { return rows; }
    public int cols() { return cols; }
    public int width() { return cols; }
    public int height() { return rows; }
    public boolean empty() { return rows == 0 || cols == 0; }
    public Size size() { return new Size(cols, rows); }
    public void release() {}
}
