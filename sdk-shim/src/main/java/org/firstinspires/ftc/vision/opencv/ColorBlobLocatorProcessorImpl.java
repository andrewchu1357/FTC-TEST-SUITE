package org.firstinspires.ftc.vision.opencv;

import android.graphics.Canvas;

import com.qualcomm.robotcore.util.SortOrder;

import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibration;
import org.ftctestbed.shim.vision.BlobGeometry;
import org.ftctestbed.shim.vision.SimFrame;
import org.ftctestbed.shim.vision.SimProcessor;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Size;

import java.util.ArrayList;
import java.util.List;

/**
 * The SDK's ColorBlobLocatorProcessor on simulated frames, step for step: the region of interest,
 * optional Gaussian blur, conversion to the range's color space, inRange, erode/dilate (OPENING
 * or CLOSING), findContours, the blob measures, then filters and sort. The picture is the
 * simulator's: game pieces (shaded balls), robots and field elements in flat colors over the
 * floor, so a blob is what a real camera would threshold from them.
 */
class ColorBlobLocatorProcessorImpl extends ColorBlobLocatorProcessor implements SimProcessor {
    private final ColorRange colorRange;
    private final ImageRegion roiImg;
    private Rect roi;
    private final boolean externalOnly;
    private final MorphOperationType morphOperationType;
    private final int erodeSize, dilateSize, blurSize;
    private final Object lockFilters = new Object();
    private final List<BlobFilter> filters = new ArrayList<BlobFilter>();
    private volatile BlobSort sort;
    private volatile ArrayList<Blob> userBlobs = new ArrayList<Blob>();

    ColorBlobLocatorProcessorImpl(ColorRange colorRange, ImageRegion roiImg, ContourMode contourMode, MorphOperationType morphOperationType,
                                  int erodeSize, int dilateSize, boolean drawContours, int blurSize, int boundingBoxColor,
                                  int circleFitColor, int roiColor, int contourColor) {
        this.colorRange = colorRange;
        this.roiImg = roiImg;
        this.externalOnly = contourMode == ContourMode.EXTERNAL_ONLY;
        this.morphOperationType = morphOperationType;
        this.erodeSize = erodeSize;
        this.dilateSize = dilateSize;
        this.blurSize = blurSize > 0 ? blurSize | 1 : 0;
    }

    @Override
    public void init(int width, int height, CameraCalibration calibration) {
        roi = roiImg.asOpenCvRect(width, height);
    }

    @Override
    public Object processFrame(Mat frame, long captureTimeNanos) {
        return userBlobs; // the simulator calls simProcessFrame instead
    }

    @Override
    public void onDrawFrame(Canvas canvas, int onscreenWidth, int onscreenHeight, float scaleBmpPxToCanvasPx, float scaleCanvasDensity, Object userContext) {}

    @Override
    public double simCostMillis(SimFrame f) {
        Rect r = roi != null ? roi : new Rect(0, 0, f.width, f.height);
        double megapixels = Math.max(0, r.width) * (double) Math.max(0, r.height) / 307200.0;
        return megapixels * (5 + (blurSize > 0 ? 0.6 * blurSize : 0) + (erodeSize > 0 || dilateSize > 0 ? 1.5 : 0)) + 0.5; // [ESTIMATE]
    }

    @Override
    public void simProcessFrame(SimFrame f) {
        if (roi == null) roi = roiImg.asOpenCvRect(f.width, f.height);
        int x0 = Math.max(0, roi.x), y0 = Math.max(0, roi.y);
        int w = Math.min(f.width, roi.x + roi.width) - x0, h = Math.min(f.height, roi.y + roi.height) - y0;
        if (w <= 0 || h <= 0) {
            userBlobs = new ArrayList<Blob>();
            return;
        }
        int[] px = f.render(x0, y0, w, h);
        if (blurSize > 1) BlobGeometry.gaussianBlur(px, w, h, blurSize);
        boolean[] mask = new boolean[w * h];
        for (int i = 0; i < mask.length; i++) mask[i] = colorRange.contains(px[i]);
        if (morphOperationType == MorphOperationType.CLOSING) {
            if (dilateSize > 0) BlobGeometry.dilate(mask, w, h, dilateSize);
            if (erodeSize > 0) BlobGeometry.erode(mask, w, h, erodeSize);
        } else {
            if (erodeSize > 0) BlobGeometry.erode(mask, w, h, erodeSize);
            if (dilateSize > 0) BlobGeometry.dilate(mask, w, h, dilateSize);
        }
        List<Blob> blobs = new ArrayList<Blob>();
        for (int[][] c : BlobGeometry.contours(mask, w, h, externalOnly)) {
            Point[] pts = new Point[c.length];
            for (int i = 0; i < c.length; i++) pts[i] = new Point(c[i][0] + x0, c[i][1] + y0);
            blobs.add(new BlobImpl(new MatOfPoint(pts)));
        }
        synchronized (lockFilters) {
            for (BlobFilter filter : filters) Util.filterByCriteria(filter.criteria, filter.minValue, filter.maxValue, blobs);
        }
        BlobSort s = sort;
        if (s != null) Util.sortByCriteria(s.criteria, s.sortOrder, blobs);
        else Util.sortByCriteria(BlobCriteria.BY_CONTOUR_AREA, SortOrder.DESCENDING, blobs);
        userBlobs = new ArrayList<Blob>(blobs);
    }

    @Override
    public void addFilter(BlobFilter filter) {
        synchronized (lockFilters) {
            filters.add(filter);
        }
    }

    @Override
    public void removeFilter(BlobFilter filter) {
        synchronized (lockFilters) {
            filters.remove(filter);
        }
    }

    @Override
    public void removeAllFilters() {
        synchronized (lockFilters) {
            filters.clear();
        }
    }

    @Override
    public void setSort(BlobSort sort) {
        this.sort = sort;
    }

    @Override
    public List<Blob> getBlobs() {
        return userBlobs;
    }

    static class BlobImpl extends Blob {
        private final MatOfPoint contour;
        private Point[] contourPts;
        private MatOfPoint2f contourAsFloat;
        private int area = -1;
        private double density = -1, aspectRatio = -1, arcLength = -1, circularity = -1;
        private RotatedRect rect;
        private Circle circle;

        BlobImpl(MatOfPoint contour) {
            this.contour = contour;
        }

        private double[][] points() {
            Point[] p = getContourPoints();
            double[][] out = new double[p.length][];
            for (int i = 0; i < p.length; i++) out[i] = new double[]{p[i].x, p[i].y};
            return out;
        }

        @Override public MatOfPoint getContour() { return contour; }

        @Override
        public Point[] getContourPoints() {
            if (contourPts == null) contourPts = contour.toArray();
            return contourPts;
        }

        @Override
        public MatOfPoint2f getContourAsFloat() {
            if (contourAsFloat == null) contourAsFloat = new MatOfPoint2f(getContourPoints());
            return contourAsFloat;
        }

        @Override
        public int getContourArea() {
            if (area < 0) area = Math.max(1, (int) BlobGeometry.area(points()));
            return area;
        }

        @Override
        public double getDensity() {
            if (density < 0) {
                double hull = Math.max(1, BlobGeometry.area(BlobGeometry.convexHull(points())));
                density = getContourArea() / hull;
            }
            return density;
        }

        @Override
        public double getAspectRatio() {
            if (aspectRatio < 0) {
                RotatedRect r = getBoxFit();
                double longer = Math.max(1, Math.max(r.size.width, r.size.height));
                double shorter = Math.max(1, Math.min(r.size.width, r.size.height));
                aspectRatio = longer / shorter;
            }
            return aspectRatio;
        }

        @Override
        public RotatedRect getBoxFit() {
            if (rect == null) {
                double[] b = BlobGeometry.minAreaRect(points());
                rect = new RotatedRect(new Point(b[0], b[1]), new Size(b[2], b[3]), b[4]);
            }
            return rect;
        }

        @Override
        public double getArcLength() {
            if (arcLength < 0) arcLength = BlobGeometry.arcLength(points());
            return arcLength;
        }

        @Override
        public double getCircularity() {
            if (circularity < 0) circularity = 4 * Math.PI * getContourArea() / Math.pow(getArcLength(), 2);
            return circularity;
        }

        @Override
        public Circle getCircle() {
            if (circle == null) {
                double[] c = BlobGeometry.minEnclosingCircle(points());
                circle = new Circle(new Point(c[0], c[1]), (float) c[2]);
            }
            return circle;
        }
    }
}
