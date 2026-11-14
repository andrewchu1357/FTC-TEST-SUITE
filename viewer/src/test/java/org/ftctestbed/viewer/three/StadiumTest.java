package org.ftctestbed.viewer.three;

import javafx.geometry.Bounds;
import org.ftctestbed.sim.config.FieldConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The stands round the field, and the crowd in them cheering. */
class StadiumTest {
    static final double IN = 0.0254;
    static final double HALF = 141.17 * IN / 2; // the field (config/field.jsonc)

    /** How far (m) a point on the floor is from the field and the two alliance areas. */
    static double clearance(double x, double y) {
        double field = Math.hypot(Math.max(Math.abs(x) - HALF, 0), Math.max(Math.abs(y) - HALF, 0));
        double ax = Math.max(Math.max(HALF + 5 * IN - Math.abs(x), Math.abs(x) - HALF - 55 * IN), 0);
        double area = Math.hypot(ax, Math.max(Math.abs(y) - 48.5 * IN, 0));
        return Math.min(field, area);
    }

    static double middleX(Bounds b) { return (b.getMinX() + b.getMaxX()) / 2; }

    static double middleY(Bounds b) { return (b.getMinY() + b.getMaxY()) / 2; }

    @Test
    void theStandsGoAllTheWayRoundCloseBehindTheFieldAndTheAllianceAreas() {
        Stadium s = new Stadium(HALF);
        double nearest = Double.MAX_VALUE, farthest = 0;
        for (int p = 0; p < 8; p++) {
            for (double t = 0; t <= 1; t += 0.05) {
                double[] q = s.at(p, t, 0);
                nearest = Math.min(nearest, clearance(q[0], q[1]));
                farthest = Math.max(farthest, clearance(q[0], q[1]));
            }
        }
        assertTrue(nearest > 0.5, "room to walk round the field and behind the drivers: " + nearest);
        assertTrue(farthest < 2, "but close, as in a gym: " + farthest);
        assertTrue(s.people() > 500, "a good crowd: " + s.people());
        // Red's supporters behind the red alliance area, blue's behind blue's, everybody else on the long sides.
        for (int i = 0; i < s.sections(); i++) {
            Bounds b = s.bounds(i);
            if (Math.abs(middleY(b)) < 1) assertEquals(middleX(b) < 0 ? "red" : "blue", s.cheersFor(i));
            if (Math.abs(middleX(b)) < 2 && Math.abs(middleY(b)) > 3) assertNull(s.cheersFor(i));
        }
    }

    @Test
    void theSectionsBetweenAPulledBackCameraAndTheFieldAreHidden() {
        Stadium s = new Stadium(HALF);
        s.cameraMoved(new double[]{0, 0, 8.3}, null); // Top: over the field
        for (int i = 0; i < s.sections(); i++) assertFalse(s.hidden(i), "nothing hidden from over the field");

        s.cameraMoved(new double[]{0, -25, 6}, null); // pulled far back behind the audience
        for (int i = 0; i < s.sections(); i++) {
            Bounds b = s.bounds(i);
            boolean audienceSide = Math.abs(middleX(b)) < 2 && middleY(b) < -3, farSide = middleY(b) > 3;
            if (audienceSide) assertTrue(s.hidden(i), "the stands in the way are hidden");
            if (farSide) assertFalse(s.hidden(i), "the ones across the field are not");
        }
        // Following a robot from behind it with the camera out past the red wall: the stands behind are hidden too.
        s.cameraMoved(new double[]{-4.6, 0, 0.9}, new double[]{-1.5, 0, 0.23});
        int hidden = 0;
        for (int i = 0; i < s.sections(); i++) if (s.hidden(i)) hidden++;
        assertTrue(hidden > 0 && hidden < 3, "just the red side: " + hidden);

        s.cameraMoved(new double[]{0, 0, 8.3}, null);
        for (int i = 0; i < s.sections(); i++) assertFalse(s.hidden(i), "all back once the camera is back");
    }

    @Test
    void whenAnAllianceScoresItsSupportersCheerAndTheBoardFlashesItsColor() {
        Stadium s = new Stadium(HALF);
        for (int i = 0; i < 60 * 5; i++) s.animate(1 / 60.0);
        assertEquals(0, s.armsUp("red"), "calm: a few hands up here and there, nobody's whole group");
        assertEquals("calm", s.board());

        s.scored("red", 20);
        double red = 0, blue = 0, most = 0, jump = 0;
        for (int i = 0; i < 60 * 2; i++) {
            s.animate(1 / 60.0);
            red += s.armsUp("red");
            blue += s.armsUp("blue");
            most = Math.max(most, s.armsUp("red"));
            jump = Math.max(jump, s.highestLift());
        }
        assertTrue(most > 0.5, "most of red's supporters up: " + most);
        assertTrue(red > 3 * blue, "red's, not blue's: " + red + " vs " + blue);
        assertTrue(jump > 0.04, "jumping: " + jump);
        assertEquals("red", s.board());

        for (int i = 0; i < 60 * 15; i++) s.animate(1 / 60.0);
        assertEquals(0, s.armsUp("red"), "and they settle down again");
        assertEquals("calm", s.board());
    }

    @Test
    void whenItsQuietTheCrowdDoesTheWave() {
        assertEquals(0, Stadium.waveLift(0, Double.NaN), "no wave");
        assertTrue(Stadium.waveLift(0, 0.3) > 0.9, "just passed: up");
        assertEquals(0, Stadium.waveLift(0.5, 0), "not there yet");
        assertEquals(0, Stadium.waveLift(0, 1.5), "long passed: sat down again");

        Stadium s = new Stadium(HALF);
        double highest = 0;
        for (int i = 0; i < 60 * 70; i++) {
            s.animate(1 / 60.0);
            highest = Math.max(highest, s.highestLift());
        }
        assertTrue(highest > 0.15, "people stood up for it: " + highest);
    }

    @Test
    void theCrowdCanBeTurnedOffForPlainDrape() {
        Field3D f = new Field3D(FieldConfig.load(Path.of("config/field.jsonc")));
        assertTrue(f.stadiumVisible(), "on to begin with");
        assertFalse(f.drapeVisible());
        f.setStadiumVisible(false);
        assertFalse(f.stadiumVisible());
        assertTrue(f.drapeVisible(), "black drape round the field instead");
        f.stadium.scored("red", 20);
        long now = 1;
        for (int i = 0; i < 120; i++) f.animate(now += 16_666_667);
        assertEquals(0, f.stadium.highestLift(), "nothing moving while it's off");
        f.setStadiumVisible(true);
        assertTrue(f.stadiumVisible(), "and back on");
        assertFalse(f.drapeVisible());
    }

    @Test
    void theStandsCameraShowsTheWholeFieldAndTheStandsAcrossIt() {
        OrbitCamera c = new OrbitCamera();
        c.setViewSize(1280, 800);
        c.preset(OrbitCamera.Preset.STANDS);
        double h = HALF + IN;
        double[][] field = {{-h, -h, 0}, {h, -h, 0}, {h, h, 0}, {-h, h, 0}, {-h, -h, 0.3}, {h, -h, 0.3}};
        assertTrue(c.fitDistance(field) <= OrbitCamera.STANDS_DISTANCE, "the whole field");
        double hy = HALF + 1.2;
        for (int row = 0; row < 2; row++) {
            double[][] head = {{0, hy + Stadium.WALL + (row + 0.6) * Stadium.TREAD, Stadium.FIRST + row * Stadium.RISE + 1.3}};
            assertTrue(c.fitDistance(head) <= OrbitCamera.STANDS_DISTANCE, "the people in row " + row + " across the field");
        }
        assertTrue(c.eye()[1] < -hy, "from the audience's side");
    }
}
