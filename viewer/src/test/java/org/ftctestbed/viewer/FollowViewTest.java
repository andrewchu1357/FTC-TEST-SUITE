package org.ftctestbed.viewer;

import org.ftctestbed.sim.SimHarness;
import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/** The 2D "Follow robot" view: it stays on the robot, eases after it, and keeps its front up the screen. */
class FollowViewTest {
    static final double IN = 0.0254;

    /** Feeds the panel frames at 60 a second and draws it, so its renderer is laid out. */
    static void frames(SimHarness h, FieldPanel panel, int n) {
        for (int i = 0; i < n; i++) {
            if (h.opModes.state() == org.ftctestbed.sim.opmode.OpModeRunner.State.RUNNING) h.runFor(1 / 60.0);
            panel.refresh(h.sim.snapshot(), 1 / 60.0);
        }
        BufferedImage img = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        panel.paint(g);
        g.dispose();
    }

    /** Where a field point (m) is on the screen. */
    static Point2D.Double screen(FieldRenderer r, double x, double y) {
        // Invert toField numerically: probe two screen directions from the screen center.
        Point2D.Double c = r.toField(400, 430), right = r.toField(500, 430), down = r.toField(400, 530);
        double ax = (right.x - c.x) / 100, ay = (right.y - c.y) / 100, bx = (down.x - c.x) / 100, by = (down.y - c.y) / 100;
        double det = ax * by - ay * bx, dx = x - c.x, dy = y - c.y;
        return new Point2D.Double(400 + (dx * by - dy * bx) / det, 430 + (ax * dy - ay * dx) / det);
    }

    @Test
    void theRobotsFrontIsUpTheScreenAndTheViewEasesAfterIt() {
        try (SimHarness h = SimHarness.load()) {
            FieldPanel panel = new FieldPanel(h.sim, h.opModes);
            panel.setSize(800, 860);
            panel.setFollow(true);
            h.sim.moveRobot(-0.6, -0.4, Math.toRadians(30));
            frames(h, panel, 120);
            FieldRenderer r = panel.renderer();
            Point2D.Double center = screen(r, -0.6, -0.4);
            Point2D.Double front = screen(r, -0.6 + 0.3 * Math.cos(Math.toRadians(30)), -0.4 + 0.3 * Math.sin(Math.toRadians(30)));
            assertEquals(400, center.x, 2, "robot in the middle across");
            // The middle of the view (y 449 here) is 14 in ahead of the robot.
            assertTrue(center.y > 449 + 100 && center.y < 449 + 220, "robot below the middle (the view looks ahead): " + center);
            assertEquals(center.x, front.x, 1, "front straight up the screen");
            assertTrue(front.y < center.y - 50, "front up the screen: " + front + " vs " + center);
            assertTrue(r.scale() > 2.0 * 800 / (h.sim.fieldConfig.size() + 1), "zoomed in: " + r.scale());

            // The robot turns 90 degrees left: the view turns after it, not in one jump.
            h.sim.moveRobot(-0.6, -0.4, Math.toRadians(120));
            frames(h, panel, 6);
            Point2D.Double f1 = screen(r, -0.6 + 0.3 * Math.cos(Math.toRadians(120)), -0.4 + 0.3 * Math.sin(Math.toRadians(120)));
            double shown = Math.toDegrees(Math.atan2(screen(r, -0.6, -0.4).y - f1.y, f1.x - screen(r, -0.6, -0.4).x));
            assertTrue(shown > 100 && shown < 175, "0.1 s later the front is still turning back toward up: " + shown);
            frames(h, panel, 150);
            Point2D.Double c2 = screen(r, -0.6, -0.4);
            Point2D.Double f2 = screen(r, -0.6 + 0.3 * Math.cos(Math.toRadians(120)), -0.4 + 0.3 * Math.sin(Math.toRadians(120)));
            assertEquals(c2.x, f2.x, 1.5, "2.5 s later it's straight up again");
            assertTrue(f2.y < c2.y - 50);
        }
    }

    @Test
    void stickRightMovesTheRobotToTheRightOfTheScreen() {
        // Robot-centric driving + the follow view: the stick directions are the screen directions.
        try (SimHarness h = SimHarness.load()) {
            h.startAt("Red Wall").init("BuzzBot TeleOp").start();
            h.sim.moveRobot(-40 * IN, -36 * IN, Math.toRadians(-130));
            FieldPanel panel = new FieldPanel(h.sim, h.opModes);
            panel.setSize(800, 860);
            panel.setFollow(true);
            frames(h, panel, 60);
            double[][] sticks = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}}; // up, right, down, left
            double[][] want = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};   // screen direction (y down)
            for (int k = 0; k < sticks.length; k++) {
                double[] st = sticks[k];
                FieldRenderer r = panel.renderer();
                Point2D.Double before = screen(r, h.sim.robotX(), h.sim.robotY());
                double x0 = h.sim.robotX(), y0 = h.sim.robotY();
                h.gamepad1(g -> { g.left_stick_x = (float) st[0]; g.left_stick_y = (float) st[1]; });
                h.runFor(0.35);
                h.gamepad1(g -> { g.left_stick_x = 0; g.left_stick_y = 0; });
                h.runFor(0.3);
                // Measure in the view as it was (the follow view moves along with the robot).
                Point2D.Double after = screen(r, h.sim.robotX(), h.sim.robotY());
                double dx = after.x - before.x, dy = after.y - before.y, len = Math.hypot(dx, dy);
                assertTrue(len > 10, "moved on screen: " + len);
                assertTrue((dx * want[k][0] + dy * want[k][1]) / len > 0.9,
                        "stick " + java.util.Arrays.toString(st) + " moves the robot that way on the screen: " + dx + ", " + dy
                                + " (field " + (h.sim.robotX() - x0) / IN + ", " + (h.sim.robotY() - y0) / IN + ")");
                frames(h, panel, 90);
            }
            assertNull(h.error(), h.error());
        }
    }
}
