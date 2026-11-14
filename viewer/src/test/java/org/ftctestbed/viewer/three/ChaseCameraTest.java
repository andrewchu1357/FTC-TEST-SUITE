package org.ftctestbed.viewer.three;

import javafx.geometry.BoundingBox;
import javafx.scene.Node;
import org.ftctestbed.sim.config.FieldConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** What stands between the follow camera and the robot fades out of the way, smoothly and without flicker. */
class ChaseCameraTest {
    static final double[] ROBOT = {0, 0, 0.23};

    @Test
    void aBoxInTheWayBlocksAndOneBesideItDoesNot() {
        double[] eye = {-1.5, 0, 0.56};
        assertTrue(Field3D.hits(eye, ROBOT, new BoundingBox(-0.8, -0.1, 0, 0.2, 0.2, 0.6), 0.02), "a FLOWER between them");
        assertFalse(Field3D.hits(eye, ROBOT, new BoundingBox(-0.8, 0.4, 0, 0.2, 0.2, 0.6), 0.02), "one off to the side");
        assertFalse(Field3D.hits(eye, ROBOT, new BoundingBox(-1.0, -0.5, 0.8, 1.0, 1.0, 0.4), 0.02), "a HIVE CELL above the line");
    }

    @Test
    void aLegBlocksOnlyWhenTheLinePassesCloseToIt() {
        double[] foot = {-0.6, -0.5, 0}, top = {-0.35, 0, 1.05};
        double[] eye = {-1.5, -0.3, 0.56};
        double d = Field3D.segmentDistance(eye, ROBOT, foot, top);
        assertTrue(d > 0.05, "this line misses the leg by " + d);
        double[] behindLeg = {-1.2, -0.95, 0.4};
        assertTrue(Field3D.segmentDistance(behindLeg, new double[]{0, 0, 0.23}, foot, top) < 0.2);
        assertEquals(1.0, Field3D.segmentDistance(new double[]{0, 0, 0}, new double[]{1, 0, 0}, new double[]{0, 1, 0}, new double[]{1, 1, 0}), 1e-9);
    }

    static Field3D field() {
        return new Field3D(FieldConfig.load(Path.of("config/field.jsonc")));
    }

    static void frames(Field3D f, double[] eye, double[] robot, int n) {
        for (int i = 0; i < n; i++) f.cameraMoved(eye, robot, 1 / 60.0);
    }

    static Node nearestWall(Field3D f, double y) {
        Node best = null;
        for (Node n : f.occluderNodes()) {
            var b = n.getBoundsInParent();
            if (b.getWidth() > 3 && Math.abs((b.getMinY() + b.getMaxY()) / 2 - y) < 0.1) best = n; // runs along x
        }
        return best;
    }

    @Test
    void aWallStaysWhenTheCameraLooksOverItAndTurnsToAnOutlineWhenItsReallyInTheWay() {
        Field3D f = field();
        double half = 70.585 * 0.0254;
        Node audienceWall = nearestWall(f, -half - 0.0127);
        assertNotNull(audienceWall);
        double[] robot = {0.3, -half + 0.3, 0.23};
        frames(f, new double[]{0.3, -half - 2, 3.0}, robot, 30); // zoomed out, high above outside the wall
        assertFalse(f.xrayed(audienceWall), "looking over the wall: it stays as it is");
        frames(f, new double[]{0.3, -half - 1.2, 0.3}, robot, 30); // low behind the wall
        assertTrue(f.xrayed(audienceWall), "in the way: an outline");
    }

    @Test
    void aCellInTheWayTurnsToAnOutlineAndComesBackWithoutFlicker() {
        Field3D f = field();
        Node cell = null;
        for (Node n : f.occluderNodes()) {
            var b = f.boundsInField(n);
            if (b.getMaxZ() > 1.3 && (b.getMinY() + b.getMaxY()) / 2 < 0 && (b.getMinX() + b.getMaxX()) / 2 < 0) cell = n; // red HIVE, audience side
        }
        assertNotNull(cell);
        var b = f.boundsInField(cell);
        double[] robot = {(b.getMinX() + b.getMaxX()) / 2, (b.getMinY() + b.getMaxY()) / 2, 0.23}; // under it
        double[] above = {robot[0] + 0.2, robot[1] - 0.3, 3.0};                                     // looking down through it
        f.cameraMoved(above, robot, 1 / 60.0);
        assertTrue(f.xrayed(cell), "in the way: an outline");
        for (Node n : f.occluderNodes()) {
            if (n != cell && f.xrayed(n)) assertTrue(f.boundsInField(n).getMaxZ() > 0.5, "only what's in the way changes");
        }

        // A camera wobbling at the edge of the CELL doesn't make it flicker.
        double[] clearOfIt = {robot[0] - 1.6, robot[1] - 0.2, 0.5};
        int changes = 0;
        boolean was = true;
        for (int i = 0; i < 180; i++) {
            double k = 0.5 + 0.03 * Math.sin(i * 0.9);
            double[] eye = {above[0] + (clearOfIt[0] - above[0]) * k, above[1] + (clearOfIt[1] - above[1]) * k, above[2] + (clearOfIt[2] - above[2]) * k};
            f.cameraMoved(eye, robot, 1 / 60.0);
            if (f.xrayed(cell) != was) changes++;
            was = f.xrayed(cell);
        }
        assertTrue(changes <= 1, "flickered " + changes + " times");

        frames(f, clearOfIt, robot, 40);
        assertFalse(f.xrayed(cell), "solid again once it's out of the way");
        frames(f, above, null, 5);
        assertFalse(f.xrayed(cell), "not following: nothing changes");
    }
}
