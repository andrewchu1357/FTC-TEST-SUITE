package org.firstinspires.ftc.vision.opencv;

import org.firstinspires.ftc.vision.VisionProcessor;

/** A camera used as a color sensor: the main color in a region, and the closest of some swatches. */
public abstract class PredominantColorProcessor implements VisionProcessor {
    public static class Builder {
        ImageRegion roi;
        Swatch[] swatches;

        public Builder setRoi(ImageRegion roi) {
            this.roi = roi;
            return this;
        }

        public Builder setSwatches(Swatch... swatches) {
            this.swatches = swatches;
            return this;
        }

        public PredominantColorProcessor build() {
            if (roi == null) throw new IllegalArgumentException("You must call setRoi()!");
            if (swatches == null) throw new IllegalArgumentException("You must call setSwatches()!");
            return new PredominantColorProcessorImpl(roi, swatches);
        }
    }

    public enum Swatch {
        RED(0), ORANGE(30), YELLOW(46), GREEN(120), ARTIFACT_GREEN(168), CYAN(180), BLUE(240), ARTIFACT_PURPLE(260),
        PURPLE(270), MAGENTA(300), BLACK(-1), WHITE(-2);

        final int hue;

        Swatch(int hue) {
            this.hue = hue;
        }

        public static Swatch valueOf(int hue) {
            for (Swatch s : values()) if (s.hue == hue) return s;
            return null;
        }
    }

    public static class Result {
        public final Swatch closestSwatch;
        /** The predominant color as an Android color int. */
        public final int rgb;
        public final int[] RGB;
        /** OpenCV 8-bit HSV: hue 0-180. */
        public final int[] HSV;
        public final int[] YCrCb;

        public Result() {
            this(Swatch.BLACK, 0, new int[3], new int[3], new int[3]);
        }

        public Result(Swatch closestSwatch, int rgb) {
            this(closestSwatch, rgb, new int[3], new int[3], new int[3]);
        }

        public Result(Swatch closestSwatch, int rgb, int[] RGB, int[] HSV, int[] YCrCb) {
            this.closestSwatch = closestSwatch;
            this.rgb = rgb;
            this.RGB = RGB;
            this.HSV = HSV;
            this.YCrCb = YCrCb;
        }
    }

    public abstract Result getAnalysis();
}
