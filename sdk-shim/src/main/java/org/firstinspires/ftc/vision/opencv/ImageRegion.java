package org.firstinspires.ftc.vision.opencv;

import com.qualcomm.robotcore.util.Range;

import org.opencv.core.Rect;

/** Part of the picture: in pixels, or with (-1, -1) the bottom-left corner and (1, 1) the top-right. */
public class ImageRegion {
    final boolean imageCoords;
    final double left, top, right, bottom;

    private ImageRegion(boolean imageCoords, double left, double top, double right, double bottom) {
        this.imageCoords = imageCoords;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public static ImageRegion asImageCoordinates(int left, int top, int right, int bottom) {
        return new ImageRegion(true, left, top, right, bottom);
    }

    public static ImageRegion asUnityCenterCoordinates(double left, double top, double right, double bottom) {
        return new ImageRegion(false, left, top, right, bottom);
    }

    public static ImageRegion entireFrame() {
        return asUnityCenterCoordinates(-1, 1, 1, -1);
    }

    protected Rect asOpenCvRect(int imageWidth, int imageHeight) {
        Rect rect = new Rect();
        if (imageCoords) {
            rect.x = (int) left;
            rect.y = (int) top;
            rect.width = (int) (right - left);
            rect.height = (int) (bottom - top);
        } else {
            rect.x = (int) Range.scale(left, -1, 1, 0, imageWidth);
            rect.y = (int) (imageHeight - Range.scale(top, -1, 1, 0, imageHeight));
            rect.width = (int) Range.scale(right - left, 0, 2, 0, imageWidth);
            rect.height = (int) Range.scale(top - bottom, 0, 2, 0, imageHeight);
        }
        rect.x = Math.max(rect.x, 0);
        rect.x = Math.min(rect.x, imageWidth - rect.width);
        rect.y = Math.max(rect.y, 0);
        rect.y = Math.min(rect.y, imageHeight - rect.height);
        return rect;
    }
}
