package org.ftctestbed.viewer.three;

import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.shape.MeshView;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import org.ftctestbed.sim.config.FieldConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The 3D view's see-through walls, soft shadows and foam tiles. */
class LooksTest {
    static final double IN = 0.0254;

    static double middleY(Node n) {
        var b = n.getBoundsInParent();
        return (b.getMinY() + b.getMaxY()) / 2;
    }

    static boolean isWallAlongX(Node n) {
        return n.getBoundsInParent().getWidth() > 3;
    }

    @Test
    void seeThroughThingsAreDrawnFarthestFirst() {
        Field3D f = new Field3D(FieldConfig.load(Path.of("config/field.jsonc")));
        assertEquals(6, f.clear.getChildren().size(), "four walls and both HIVES' CELL skins");
        // From the audience the far wall goes first and the audience wall last...
        f.cameraMoved(new double[]{0, -5, 2}, null, 1 / 60.0);
        List<Node> order = f.clear.getChildren();
        assertTrue(isWallAlongX(order.get(0)) && middleY(order.get(0)) > 1.5, "the far wall first");
        assertTrue(isWallAlongX(order.get(5)) && middleY(order.get(5)) < -1.5, "the audience wall last");
        // ...and the other way round from behind the far wall.
        f.cameraMoved(new double[]{0, 5, 2}, null, 1 / 60.0);
        order = f.clear.getChildren();
        assertTrue(middleY(order.get(0)) < -1.5, "now the audience wall first");
        assertTrue(middleY(order.get(5)) > 1.5, "and the far wall last");
    }

    @Test
    void aBallsShadowIsUnderItOnTheFloorAndFainterAndOffToTheSideInTheAir() {
        double r = 1.4 * IN;
        MeshView sh = Shapes.quad(-0.5, -0.5, 0.5, 0.5, 0, 1, null);
        sh.getTransforms().addAll(new Translate(), new Scale());
        Field3D.placeShadow(sh, 0.5, 0.2, r, r);
        Translate at = (Translate) sh.getTransforms().get(0);
        Scale size = (Scale) sh.getTransforms().get(1);
        assertEquals(0.5, at.getX(), r, "resting: right under it");
        assertEquals(0.2, at.getY(), r);
        assertTrue(at.getZ() > 0.002 && at.getZ() < 0.004, "just above the tape on the tiles");
        double restingSize = size.getX();
        var resting = sh.getMaterial();

        Field3D.placeShadow(sh, 0.5, 0.2, 1.0, r);
        double away = Math.hypot(at.getX() - 0.5, at.getY() - 0.2);
        assertEquals(1.0 * Math.hypot(Shapes.SUN[0], Shapes.SUN[1]) / -Shapes.SUN[2], away, 1e-9, "where the overhead light puts it");
        assertTrue(size.getX() > restingSize, "spread wider");
        assertNotSame(resting, sh.getMaterial(), "and fainter");
    }

    @Test
    void theTilesAreDarkerAtTheSeamsAndAlongTheWalls() {
        double size = 141.17 * IN;
        int tiles = 6, n = 2048;
        Image img = Textures.tiles(size, tiles, List.of(), List.of());
        assertEquals(n, (int) img.getWidth());
        PixelReader px = img.getPixelReader();
        double t = size / tiles, period = t / 8;
        // Where the zigzag seam crosses the tile line (half way through a tooth).
        int seamI = (int) Math.round(t / size * n - 0.5);
        int seamJ = (int) Math.round(n - (period / 2) / size * n - 0.5);
        double middle = brightness(px, (int) (1.5 * n / tiles), (int) (1.5 * n / tiles), 3);
        assertTrue(brightness(px, seamI, seamJ, 0) < 0.8 * middle, "a seam is a dark line");
        assertTrue(brightness(px, 0, n / 2, 0) < 0.8 * middle, "darker into the corner along the wall");
    }

    /** Average brightness (0..1) of the pixels within k of (i, j). */
    static double brightness(PixelReader px, int i, int j, int k) {
        double sum = 0;
        int count = 0;
        for (int a = i - k; a <= i + k; a++) {
            for (int b = j - k; b <= j + k; b++) {
                sum += px.getColor(a, b).getBrightness();
                count++;
            }
        }
        return sum / count;
    }
}
