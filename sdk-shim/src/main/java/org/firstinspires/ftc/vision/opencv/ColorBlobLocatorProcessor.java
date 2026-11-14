package org.firstinspires.ftc.vision.opencv;

import android.graphics.Color;

import com.qualcomm.robotcore.util.SortOrder;

import org.firstinspires.ftc.vision.VisionProcessor;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Finds blobs of one color range in each frame. */
public abstract class ColorBlobLocatorProcessor implements VisionProcessor {
    public enum ContourMode { EXTERNAL_ONLY, ALL_FLATTENED_HIERARCHY }

    public enum MorphOperationType { OPENING, CLOSING }

    public enum BlobCriteria { BY_CONTOUR_AREA, BY_DENSITY, BY_ASPECT_RATIO, BY_ARC_LENGTH, BY_CIRCULARITY }

    public static class BlobFilter {
        public final BlobCriteria criteria;
        public final double minValue;
        public final double maxValue;

        public BlobFilter(BlobCriteria criteria, double minValue, double maxValue) {
            this.criteria = criteria;
            this.minValue = minValue;
            this.maxValue = maxValue;
        }
    }

    public static class BlobSort {
        public final BlobCriteria criteria;
        public final SortOrder sortOrder;

        public BlobSort(BlobCriteria criteria, SortOrder sortOrder) {
            this.criteria = criteria;
            this.sortOrder = sortOrder;
        }
    }

    public static class Builder {
        private ColorRange colorRange;
        private ContourMode contourMode;
        private ImageRegion imageRegion = ImageRegion.entireFrame();
        private MorphOperationType morphOperationType = MorphOperationType.OPENING;
        private int erodeSize = -1;
        private int dilateSize = -1;
        private boolean drawContours = false;
        private int blurSize = -1;
        private int boundingBoxColor = Color.rgb(255, 120, 31);
        private int circleFitColor = 0;
        private int roiColor = Color.rgb(255, 255, 255);
        private int contourColor = Color.rgb(3, 227, 252);

        public Builder setDrawContours(boolean drawContours) {
            this.drawContours = drawContours;
            return this;
        }

        public Builder setBoxFitColor(int color) {
            this.boundingBoxColor = color;
            return this;
        }

        public Builder setCircleFitColor(int color) {
            this.circleFitColor = color;
            return this;
        }

        public Builder setRoiColor(int color) {
            this.roiColor = color;
            return this;
        }

        public Builder setContourColor(int color) {
            this.contourColor = color;
            return this;
        }

        public Builder setTargetColorRange(ColorRange colorRange) {
            this.colorRange = colorRange;
            return this;
        }

        public Builder setContourMode(ContourMode contourMode) {
            this.contourMode = contourMode;
            return this;
        }

        public Builder setRoi(ImageRegion roiImg) {
            this.imageRegion = roiImg;
            return this;
        }

        /** Gaussian blur (made odd) before thresholding; 0 or less for none. */
        public Builder setBlurSize(int blurSize) {
            this.blurSize = blurSize;
            return this;
        }

        public Builder setMorphOperationType(MorphOperationType morphOperationType) {
            this.morphOperationType = morphOperationType;
            return this;
        }

        public Builder setErodeSize(int erodeSize) {
            this.erodeSize = erodeSize;
            return this;
        }

        public Builder setDilateSize(int dilateSize) {
            this.dilateSize = dilateSize;
            return this;
        }

        public ColorBlobLocatorProcessor build() {
            if (imageRegion == null) throw new IllegalArgumentException("You must set a region of interest!");
            if (colorRange == null) throw new IllegalArgumentException("You must set a color range!");
            if (contourMode == null) throw new IllegalArgumentException("You must set a contour mode!");
            return new ColorBlobLocatorProcessorImpl(colorRange, imageRegion, contourMode, morphOperationType, erodeSize, dilateSize,
                    drawContours, blurSize, boundingBoxColor, circleFitColor, roiColor, contourColor);
        }
    }

    public abstract void addFilter(BlobFilter filter);

    public abstract void removeFilter(BlobFilter filter);

    public abstract void removeAllFilters();

    public abstract void setSort(BlobSort sort);

    /** The newest frame's blobs, filtered and sorted (biggest first unless setSort says otherwise). */
    public abstract List<Blob> getBlobs();

    public abstract static class Blob {
        public abstract MatOfPoint getContour();

        public abstract Point[] getContourPoints();

        public abstract MatOfPoint2f getContourAsFloat();

        public abstract int getContourArea();

        public abstract double getDensity();

        public abstract double getAspectRatio();

        public abstract RotatedRect getBoxFit();

        public abstract double getArcLength();

        public abstract double getCircularity();

        public abstract Circle getCircle();
    }

    public static class Util {
        public static void filterByCriteria(BlobCriteria criteria, double minValue, double maxValue, List<Blob> blobs) {
            List<Blob> toRemove = new ArrayList<Blob>();
            for (Blob b : blobs) {
                double v = value(criteria, b);
                if (v > maxValue || v < minValue) toRemove.add(b);
            }
            blobs.removeAll(toRemove);
        }

        public static void sortByCriteria(final BlobCriteria criteria, final SortOrder sortOrder, List<Blob> blobs) {
            Collections.sort(blobs, new Comparator<Blob>() {
                @Override
                public int compare(Blob a, Blob b) {
                    int c;
                    if (criteria == BlobCriteria.BY_CONTOUR_AREA) c = (int) Math.signum((float) (b.getContourArea() - a.getContourArea()));
                    else c = (int) Math.signum(value(criteria, b) - value(criteria, a));
                    return sortOrder == SortOrder.ASCENDING ? -c : c;
                }
            });
        }

        static double value(BlobCriteria criteria, Blob b) {
            switch (criteria) {
                case BY_CONTOUR_AREA: return b.getContourArea();
                case BY_DENSITY: return b.getDensity();
                case BY_ASPECT_RATIO: return b.getAspectRatio();
                case BY_ARC_LENGTH: return b.getArcLength();
                case BY_CIRCULARITY: return b.getCircularity();
                default: return 0;
            }
        }

        public static void filterByArea(double minArea, double maxArea, List<Blob> blobs) {
            filterByCriteria(BlobCriteria.BY_CONTOUR_AREA, minArea, maxArea, blobs);
        }

        public static void sortByArea(SortOrder sortOrder, List<Blob> blobs) {
            sortByCriteria(BlobCriteria.BY_CONTOUR_AREA, sortOrder, blobs);
        }

        public static void filterByDensity(double minDensity, double maxDensity, List<Blob> blobs) {
            filterByCriteria(BlobCriteria.BY_DENSITY, minDensity, maxDensity, blobs);
        }

        public static void sortByDensity(SortOrder sortOrder, List<Blob> blobs) {
            sortByCriteria(BlobCriteria.BY_DENSITY, sortOrder, blobs);
        }

        public static void filterByAspectRatio(double minAspect, double maxAspect, List<Blob> blobs) {
            filterByCriteria(BlobCriteria.BY_ASPECT_RATIO, minAspect, maxAspect, blobs);
        }

        public static void sortByAspectRatio(SortOrder sortOrder, List<Blob> blobs) {
            sortByCriteria(BlobCriteria.BY_ASPECT_RATIO, sortOrder, blobs);
        }
    }
}
