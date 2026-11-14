package org.firstinspires.ftc.vision.opencv;

import android.graphics.Canvas;
import android.graphics.Color;

import org.firstinspires.ftc.robotcore.internal.camera.calibration.CameraCalibration;
import org.ftctestbed.shim.vision.SimFrame;
import org.ftctestbed.shim.vision.SimProcessor;
import org.opencv.core.Mat;
import org.opencv.core.Rect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * The SDK's PredominantColorProcessor on simulated frames, step for step: the region's mean Y,
 * k-means (k = 5, k-means++ starts, 10 rounds) on its pixels' Cr and Cb, the biggest cluster's
 * center back to RGB and HSV, then the SDK's swatch rule (WHITE: saturation under 0.15 and value
 * over 0.55; BLACK: value under 0.1, or saturation or value under 0.2 if BLACK is a swatch; else
 * the nearest hue).
 */
class PredominantColorProcessorImpl extends PredominantColorProcessor implements SimProcessor {
    static final int K = 5;

    private volatile Result result = new Result();
    private final ImageRegion roiImg;
    private Rect roi;
    private final ArrayList<Swatch> swatches;

    PredominantColorProcessorImpl(ImageRegion roiImg, Swatch[] swatches) {
        this.roiImg = roiImg;
        this.swatches = new ArrayList<Swatch>(Arrays.asList(swatches));
    }

    @Override
    public void init(int width, int height, CameraCalibration calibration) {
        roi = roiImg.asOpenCvRect(width, height);
    }

    @Override
    public Object processFrame(Mat frame, long captureTimeNanos) {
        return result; // the simulator calls simProcessFrame instead
    }

    @Override
    public void onDrawFrame(Canvas canvas, int onscreenWidth, int onscreenHeight, float scaleBmpPxToCanvasPx, float scaleCanvasDensity, Object userContext) {}

    @Override
    public double simCostMillis(SimFrame f) {
        Rect r = roi != null ? roi : new Rect(0, 0, f.width, f.height);
        return Math.max(0, r.width) * (double) Math.max(0, r.height) / 307200.0 * 25 + 0.5; // [ESTIMATE]
    }

    @Override
    public void simProcessFrame(SimFrame f) {
        if (roi == null) roi = roiImg.asOpenCvRect(f.width, f.height);
        int x0 = Math.max(0, roi.x), y0 = Math.max(0, roi.y);
        int w = Math.min(f.width, roi.x + roi.width) - x0, h = Math.min(f.height, roi.y + roi.height) - y0;
        if (w <= 0 || h <= 0) return;
        int[] px = f.render(x0, y0, w, h);
        int n = px.length;
        // Same (Cr, Cb) pixels behave the same in k-means: count each pair once, with a weight.
        int[] counts = new int[65536];
        long sumY = 0;
        for (int c : px) {
            int[] ycc = ColorRange.yCrCb((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
            sumY += ycc[0];
            counts[(ycc[1] << 8) | ycc[2]]++;
        }
        int meanY = (int) (sumY / (double) n);
        int distinct = 0;
        for (int c : counts) if (c > 0) distinct++;
        double[] cr = new double[distinct], cb = new double[distinct];
        int[] weight = new int[distinct];
        for (int i = 0, j = 0; i < counts.length; i++) {
            if (counts[i] == 0) continue;
            cr[j] = i >> 8;
            cb[j] = i & 0xFF;
            weight[j++] = counts[i];
        }
        double[][] centers = kmeans(cr, cb, weight, Math.min(K, distinct), new Random(f.captureNanos));
        // The cluster with the most pixels.
        int[] members = new int[centers.length];
        for (int j = 0; j < distinct; j++) members[nearest(centers, cr[j], cb[j])] += weight[j];
        int best = 0;
        for (int k = 1; k < centers.length; k++) if (members[k] > members[best]) best = k;
        int[] ycc = {meanY & 0xFF, ((int) centers[best][0]) & 0xFF, ((int) centers[best][1]) & 0xFF};
        int[] rgbArr = yCrCbToRgb(ycc[0], ycc[1], ycc[2]);
        float[] hsv = new float[3];
        int rgb = Color.rgb(rgbArr[0], rgbArr[1], rgbArr[2]);
        Color.colorToHSV(rgb, hsv);
        int[] hsvArr = {(int) (hsv[0] / 2), (int) (hsv[1] * 255), (int) (hsv[2] * 255)};
        float hue = hsv[0], sat = hsv[1], val = hsv[2];
        Swatch closest = null;
        if (sat < 0.15 && val > 0.55 && swatches.contains(Swatch.WHITE)) {
            closest = Swatch.WHITE;
        } else if (val < 0.1 || ((sat < 0.2 || val < 0.2) && swatches.contains(Swatch.BLACK))) {
            closest = Swatch.BLACK;
        } else {
            int bestDiff = 360;
            for (Swatch s : swatches) {
                if (s.hue < 0) continue;
                int d = Math.abs((int) hue - s.hue);
                if (d > 180) d = 360 - d;
                if (d < bestDiff) {
                    bestDiff = d;
                    closest = s;
                }
            }
        }
        result = new Result(closest, rgb, rgbArr, hsvArr, ycc);
    }

    /** Weighted k-means with k-means++ starts: OpenCV's KMEANS_PP_CENTERS, 10 rounds, stop when centers move under sqrt(2). */
    static double[][] kmeans(double[] x, double[] y, int[] w, int k, Random random) {
        int n = x.length;
        double[][] c = new double[k][2];
        long total = 0;
        for (int v : w) total += v;
        int first = pick(w, total, random);
        c[0][0] = x[first];
        c[0][1] = y[first];
        double[] d2 = new double[n];
        for (int i = 1; i < k; i++) {
            double sum = 0;
            for (int j = 0; j < n; j++) {
                double best = Double.MAX_VALUE;
                for (int m = 0; m < i; m++) best = Math.min(best, sq(x[j] - c[m][0]) + sq(y[j] - c[m][1]));
                d2[j] = best * w[j];
                sum += d2[j];
            }
            int chosen = 0;
            if (sum > 0) {
                double r = random.nextDouble() * sum;
                for (int j = 0; j < n; j++) {
                    r -= d2[j];
                    if (r <= 0) {
                        chosen = j;
                        break;
                    }
                }
            }
            c[i][0] = x[chosen];
            c[i][1] = y[chosen];
        }
        for (int iter = 0; iter < 10; iter++) {
            double[][] sums = new double[k][3];
            for (int j = 0; j < n; j++) {
                int m = nearest(c, x[j], y[j]);
                sums[m][0] += x[j] * w[j];
                sums[m][1] += y[j] * w[j];
                sums[m][2] += w[j];
            }
            double maxShift = 0;
            for (int m = 0; m < k; m++) {
                if (sums[m][2] == 0) continue;
                double nx = sums[m][0] / sums[m][2], ny = sums[m][1] / sums[m][2];
                maxShift = Math.max(maxShift, sq(nx - c[m][0]) + sq(ny - c[m][1]));
                c[m][0] = nx;
                c[m][1] = ny;
            }
            if (maxShift <= 2.0) break;
        }
        return c;
    }

    private static int pick(int[] w, long total, Random random) {
        long r = (long) (random.nextDouble() * total);
        for (int j = 0; j < w.length; j++) {
            r -= w[j];
            if (r < 0) return j;
        }
        return w.length - 1;
    }

    static int nearest(double[][] c, double x, double y) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int m = 0; m < c.length; m++) {
            double d = sq(x - c[m][0]) + sq(y - c[m][1]);
            if (d < bd) {
                bd = d;
                best = m;
            }
        }
        return best;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** OpenCV COLOR_YCrCb2RGB, 8-bit. */
    static int[] yCrCbToRgb(int y, int cr, int cb) {
        double r = y + 1.403 * (cr - 128), g = y - 0.714 * (cr - 128) - 0.344 * (cb - 128), b = y + 1.773 * (cb - 128);
        return new int[]{ColorRange.sat(r), ColorRange.sat(g), ColorRange.sat(b)};
    }

    @Override
    public Result getAnalysis() {
        return result;
    }
}
