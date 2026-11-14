package org.ftctestbed.sim.vision;

import com.qualcomm.robotcore.util.SortOrder;

import org.firstinspires.ftc.vision.opencv.ColorBlobLocatorProcessor;
import org.firstinspires.ftc.vision.opencv.ColorRange;
import org.firstinspires.ftc.vision.opencv.ImageRegion;
import org.firstinspires.ftc.vision.opencv.PredominantColorProcessor;
import org.ftctestbed.shim.vision.SimFrame;
import org.ftctestbed.shim.vision.SimProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The SDK's color processors on simulated pictures: shaded balls on gray tiles. */
class ColorProcessorsTest {
    static final int POLLEN = 0xF2CF1D, RED_NECTAR = 0xD7263D, TILE = 0x6E7074;

    static SimFrame picture(SimFrame.Shape... shapes) {
        return new SimFrame(640, 480, 1, 622, 622, 320, 240, 1, 1.0, List.of(), List.of(shapes), (u, v) -> TILE);
    }

    static ColorBlobLocatorProcessor.Builder yellow() {
        return new ColorBlobLocatorProcessor.Builder().setTargetColorRange(ColorRange.YELLOW)
                .setContourMode(ColorBlobLocatorProcessor.ContourMode.EXTERNAL_ONLY);
    }

    static List<ColorBlobLocatorProcessor.Blob> blobs(ColorBlobLocatorProcessor p, SimFrame f) {
        p.init(640, 480, null);
        ((SimProcessor) p).simProcessFrame(f);
        return p.getBlobs();
    }

    @Test
    void aYellowBallIsOneRoundBlobWhereItIs() {
        List<ColorBlobLocatorProcessor.Blob> b = blobs(yellow().build(), picture(SimFrame.Shape.disc(320, 240, 20, POLLEN)));
        assertEquals(1, b.size());
        ColorBlobLocatorProcessor.Blob blob = b.get(0);
        assertEquals(Math.PI * 20 * 20, blob.getContourArea(), 0.1 * Math.PI * 400, "about the disc's area");
        assertEquals(320, blob.getCircle().getX(), 1.5);
        assertEquals(240, blob.getCircle().getY(), 1.5);
        assertEquals(20, blob.getCircle().getRadius(), 1.5);
        assertTrue(blob.getCircularity() > 0.85, "round: " + blob.getCircularity());
        assertTrue(blob.getDensity() > 0.95, "solid: " + blob.getDensity());
        assertEquals(1, blob.getAspectRatio(), 0.1);
        assertEquals(40, Math.max(blob.getBoxFit().size.width, blob.getBoxFit().size.height), 2.5);
    }

    @Test
    void theRangeTheRoiAndTheMorphologyDecideWhatsABlob() {
        SimFrame f = picture(SimFrame.Shape.disc(200, 240, 20, POLLEN), SimFrame.Shape.disc(250, 240, 20, POLLEN),
                SimFrame.Shape.disc(500, 100, 15, RED_NECTAR));
        assertEquals(2, blobs(yellow().build(), f).size(), "two balls 10 px apart: two blobs");
        assertEquals(1, blobs(yellow().setDilateSize(15).build(), f).size(), "dilated by 15 px: they join");
        assertEquals(1, blobs(yellow().setRoi(ImageRegion.asImageCoordinates(0, 0, 225, 480)).build(), f).size(), "only one in the ROI");
        assertEquals(1, blobs(new ColorBlobLocatorProcessor.Builder().setTargetColorRange(ColorRange.RED)
                .setContourMode(ColorBlobLocatorProcessor.ContourMode.EXTERNAL_ONLY).build(), f).size(), "the red one");
        assertTrue(blobs(new ColorBlobLocatorProcessor.Builder().setTargetColorRange(ColorRange.BLUE)
                .setContourMode(ColorBlobLocatorProcessor.ContourMode.EXTERNAL_ONLY).build(), f).isEmpty(), "nothing blue; the gray tiles aren't");
    }

    @Test
    void filtersAndSortsWorkAsInTheSdk() {
        SimFrame f = picture(SimFrame.Shape.disc(100, 240, 10, POLLEN), SimFrame.Shape.disc(300, 240, 30, POLLEN), SimFrame.Shape.disc(500, 240, 20, POLLEN));
        ColorBlobLocatorProcessor p = yellow().build();
        List<ColorBlobLocatorProcessor.Blob> b = blobs(p, f);
        assertEquals(3, b.size());
        assertTrue(b.get(0).getContourArea() > b.get(1).getContourArea() && b.get(1).getContourArea() > b.get(2).getContourArea(), "biggest first by default");
        p.addFilter(new ColorBlobLocatorProcessor.BlobFilter(ColorBlobLocatorProcessor.BlobCriteria.BY_CONTOUR_AREA, 500, 20000));
        p.setSort(new ColorBlobLocatorProcessor.BlobSort(ColorBlobLocatorProcessor.BlobCriteria.BY_CONTOUR_AREA, SortOrder.ASCENDING));
        b = blobs(p, f);
        assertEquals(2, b.size(), "the 10 px one is under 500 px");
        assertTrue(b.get(0).getContourArea() < b.get(1).getContourArea(), "ascending");
    }

    @Test
    void aBadBuilderFailsAsInTheSdk() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new ColorBlobLocatorProcessor.Builder().setTargetColorRange(ColorRange.BLUE).build());
        assertEquals("You must set a contour mode!", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new PredominantColorProcessor.Builder().setRoi(ImageRegion.entireFrame()).build());
    }

    @Test
    void thePredominantColorIsTheBallFillingTheRegionOrTheFloor() {
        PredominantColorProcessor p = new PredominantColorProcessor.Builder()
                .setRoi(ImageRegion.asUnityCenterCoordinates(-0.1, 0.1, 0.1, -0.1))
                .setSwatches(PredominantColorProcessor.Swatch.RED, PredominantColorProcessor.Swatch.BLUE,
                        PredominantColorProcessor.Swatch.YELLOW, PredominantColorProcessor.Swatch.BLACK, PredominantColorProcessor.Swatch.WHITE)
                .build();
        p.init(640, 480, null);
        assertEquals(PredominantColorProcessor.Swatch.BLACK, p.getAnalysis().closestSwatch, "before any frame");
        ((SimProcessor) p).simProcessFrame(picture(SimFrame.Shape.disc(320, 240, 80, POLLEN)));
        PredominantColorProcessor.Result r = p.getAnalysis();
        assertEquals(PredominantColorProcessor.Swatch.YELLOW, r.closestSwatch, "RGB " + r.RGB[0] + "," + r.RGB[1] + "," + r.RGB[2]);
        ((SimProcessor) p).simProcessFrame(picture(SimFrame.Shape.disc(320, 240, 80, RED_NECTAR)));
        assertEquals(PredominantColorProcessor.Swatch.RED, p.getAnalysis().closestSwatch);
        ((SimProcessor) p).simProcessFrame(picture());
        assertEquals(PredominantColorProcessor.Swatch.BLACK, p.getAnalysis().closestSwatch, "gray tiles: low saturation, not bright enough for WHITE");
    }
}
