package org.ftctestbed.viewer.three;

import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.Snapshot;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The 3D view with a partner robot: numbered robots, a Follow camera for each, both in the HUD. */
class PartnerView3DTest {
    static final double IN = 0.0254;

    @Test
    void thereIsAFollowCameraForEachRobot() {
        assertArrayEquals(View3D.PRESETS, View3D.presets(1));
        List<String> two = Arrays.asList(View3D.presets(2));
        assertFalse(two.contains("Follow robot"));
        assertTrue(two.containsAll(List.of("Red driver", "Blue driver", "Audience", "Top", "Follow robot 1", "Follow robot 2")));
        assertEquals(0, View3D.followIndex("Follow robot"));
        assertEquals(0, View3D.followIndex("Follow robot 1"));
        assertEquals(1, View3D.followIndex("Follow robot 2"));
    }

    @Test
    void theHudShowsWhereEachRobotIs() {
        RobotConfig robot = RobotConfig.load(Path.of("config/robot.jsonc"));
        Simulation sim = new Simulation(FieldConfig.load(Path.of("config/field.jsonc")), List.of(robot, robot));
        String one = View3D.hudText(List.of(sim.snapshot()), "t = 0");
        assertTrue(one.startsWith("x  -61.59 in"), one);
        assertFalse(one.contains("Robot"), "no numbers with one robot");
        String both = View3D.hudText(sim.snapshots(), "t = 0");
        String[] lines = both.split("\n");
        assertTrue(lines[0].startsWith("Robot 1   x  -61.59 in"), both);
        assertTrue(lines[1].startsWith("Robot 2   x  -38.00 in   y  -61.59 in   heading   90.0°"), both);
        assertEquals("t = 0", lines[2]);
    }

    /**
     * Reads the digit on one side sign as someone standing outside the robot on that side sees
     * it: which of the seven segments are lit, by where they are (u to the viewer's right, v up).
     */
    static String seenFrom(Robot3D r, int side) {
        List<double[]> segs = new ArrayList<>();
        for (double[] p : r.numberSegments()) if (Math.signum(p[1]) == side) segs.add(p);
        double zMid = segs.stream().mapToDouble(p -> p[2]).average().orElseThrow();
        StringBuilder lit = new StringBuilder();
        for (double[] p : segs) {
            // Standing at +y the viewer looks toward -y, so their right is the robot's -x; at -y, +x.
            double u = side > 0 ? -p[0] : p[0], v = p[2] - zMid;
            boolean vertical = p[4] > p[3];
            if (!vertical) lit.append(v > 0.5 * IN ? 'a' : v < -0.5 * IN ? 'd' : 'g');
            else lit.append(u > 0 ? (v > 0 ? 'b' : 'c') : (v > 0 ? 'f' : 'e'));
        }
        char[] c = lit.toString().toCharArray();
        Arrays.sort(c);
        return new String(c);
    }

    @Test
    void theRobotsNumberReadsRightFromBothSides() {
        Robot3D r = new Robot3D(RobotConfig.load(Path.of("config/robot.jsonc")));
        assertTrue(r.numberSegments().isEmpty(), "no number unless there's a partner");
        r.setNumber(2);
        // A "2" is a b d e g; seen mirrored it would be a c d f g (a "5").
        assertEquals("abdeg", seenFrom(r, 1), "the left sign");
        assertEquals("abdeg", seenFrom(r, -1), "the right sign");
        for (double[] p : r.numberSegments()) {
            assertTrue(Math.abs(p[1]) > RobotConfig.load(Path.of("config/robot.jsonc")).width() / 2 - 0.05 * IN,
                    "on the outside of the sign");
        }
        r.setNumber(1);
        assertEquals(4, r.numberSegments().size(), "two bars on each side");
        for (double[] p : r.numberSegments()) {
            assertTrue(p[4] > p[3], "upright");
            assertEquals(0, p[0], 1e-9, "in the middle of the sign");
        }
        r.setNumber(0);
        assertTrue(r.numberSegments().isEmpty());
    }

    @Test
    void bothRobotsSnapshotsGoToTheView() {
        RobotConfig robot = RobotConfig.load(Path.of("config/robot.jsonc"));
        Simulation sim = new Simulation(FieldConfig.load(Path.of("config/field.jsonc")), List.of(robot, robot));
        Field3D field = new Field3D(sim.fieldConfig);
        List<Snapshot> shots = sim.snapshots();
        field.update(shots); // pieces and rays of both robots, no exception with two
        assertEquals(2, shots.size());
    }

    @Test
    void theRobotCameraPictureIsDrawnAgainOnlyWhenSomethingMoved() {
        RobotConfig robot = RobotConfig.load(Path.of("config/robot.jsonc"));
        Simulation sim = new Simulation(FieldConfig.load(Path.of("config/field.jsonc")), List.of(robot));
        Snapshot.Camera cam = new Snapshot.Camera("limelight", "Limelight 3A", new double[]{0, 0, 0.3}, new double[]{1, 0, 0},
                new double[]{0, 0, -1}, new double[]{0, 1, 0}, 1, 0.7, 640, 480, 0, "", List.of());
        int still = View3D.lensScene(List.of(sim.snapshot()), cam);
        assertEquals(still, View3D.lensScene(List.of(sim.snapshot()), cam), "nothing moved: the same picture");
        Snapshot s = sim.snapshot();
        sim.moveRobot(s.x() + 0.1, s.y(), s.heading());
        assertNotEquals(still, View3D.lensScene(List.of(sim.snapshot()), cam), "the robot moved");
        Snapshot.Camera turned = new Snapshot.Camera("limelight", "Limelight 3A", new double[]{0, 0, 0.3}, new double[]{1, 0, 0},
                new double[]{0, 0, -1}, new double[]{0.1, 1, 0}, 1, 0.7, 640, 480, 0, "", List.of());
        int moved = View3D.lensScene(List.of(sim.snapshot()), cam);
        assertNotEquals(moved, View3D.lensScene(List.of(sim.snapshot()), turned), "the camera turned");
    }
}
