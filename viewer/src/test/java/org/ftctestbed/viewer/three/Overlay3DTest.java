package org.ftctestbed.viewer.three;

import com.acmerobotics.dashboard.canvas.Canvas;

import javafx.collections.ObservableFloatArray;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Road Runner's and Pedro's field drawings, on the 3D field's tiles. */
class Overlay3DTest {
    static final double IN = 0.0254;

    /** What Road Runner's drawRobot and path drawing send (field inches, center origin). */
    static Canvas roadRunnerDrawing() {
        Canvas c = new Canvas();
        c.setStrokeWidth(1);
        c.setStroke("#4CAF50");
        c.strokePolyline(new double[]{-48, -24, 0, 24}, new double[]{-48, -24, 0, 12});
        c.setStroke("#3F51B5");
        c.strokeCircle(24, 12, 9);
        c.strokeLine(24, 12, 33, 12);
        c.setFill("#FF000080");
        c.fillRect(-60, 50, 10, 10);
        c.fillText("not drawn in 3D", 0, 0, "8px Arial", 0);
        return c;
    }

    @Test
    void theDrawingLiesOnTheTilesInFieldInchesWithItsColors() {
        Overlay3D o = new Overlay3D();
        Canvas c = roadRunnerDrawing();
        o.update(c, null);
        assertTrue(o.triangles() > 36 * 2, "a ribbon per segment of the path and the circle: " + o.triangles());
        assertTrue(o.colors().contains(0xFF4CAF50), "the path's green");
        assertTrue(o.colors().contains(0xFF3F51B5), "the robot's blue");
        assertTrue(o.colors().contains(0x80FF0000), "a half see-through red fill");

        TriangleMesh mesh = (TriangleMesh) ((MeshView) o.root.getChildren().get(0)).getMesh();
        ObservableFloatArray p = mesh.getPoints();
        double minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
        for (int i = 0; i < p.size(); i += 3) {
            minX = Math.min(minX, p.get(i));
            maxX = Math.max(maxX, p.get(i));
            minZ = Math.min(minZ, p.get(i + 2));
            maxZ = Math.max(maxZ, p.get(i + 2));
        }
        assertEquals(-60 * IN, minX, 0.4 * IN, "the fill's left edge (field inches to meters)");
        assertEquals(33 * IN, maxX, 0.5 * IN, "the heading line's tip, plus a little for the line's width");
        assertEquals(Overlay3D.Z, minZ, 1e-6, "flat on the tiles");
        assertEquals(Overlay3D.Z, maxZ, 1e-6);
        assertTrue(Overlay3D.Z > Field3D.ROBOT_SHADOW_Z, "over the shadows");

        int before = o.triangles();
        o.update(c, null);
        assertEquals(before, o.triangles(), "the same drawing isn't rebuilt");
        o.update(null, null);
        assertEquals(0, o.triangles(), "nothing drawn when the code stops drawing");
        assertFalse(o.root.getChildren().get(0).isVisible());
    }

    @Test
    void itFollowsTheDrawingsOwnTranslationAndRotation() {
        Overlay3D o = new Overlay3D();
        Canvas c = new Canvas();
        c.setTranslation(10, 20);
        c.setRotation(Math.PI / 2);
        c.strokeLine(0, 0, 10, 0); // along +x, turned 90 degrees: up +y from (10, 20)
        o.update(c, null);
        TriangleMesh mesh = (TriangleMesh) ((MeshView) o.root.getChildren().get(0)).getMesh();
        ObservableFloatArray p = mesh.getPoints();
        double maxY = -1e9, minY = 1e9;
        for (int i = 0; i < p.size(); i += 3) {
            maxY = Math.max(maxY, p.get(i + 1));
            minY = Math.min(minY, p.get(i + 1));
            assertEquals(10 * IN, p.get(i), 0.4 * IN, "stays at x = 10 in (give or take half the line's width)");
        }
        assertEquals(30 * IN, maxY, 0.4 * IN);
        assertEquals(20 * IN, minY, 0.4 * IN);
    }
}
