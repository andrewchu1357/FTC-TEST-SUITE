package org.ftctestbed.sim.physics;

import org.dyn4j.collision.CategoryFilter;
import org.dyn4j.dynamics.Body;
import org.dyn4j.dynamics.BodyFixture;
import org.dyn4j.geometry.Convex;
import org.dyn4j.geometry.Geometry;
import org.dyn4j.geometry.Mass;
import org.dyn4j.geometry.MassType;
import org.dyn4j.geometry.Ray;
import org.dyn4j.geometry.Vector2;
import org.dyn4j.world.DetectFilter;
import org.dyn4j.world.World;
import org.dyn4j.world.result.RaycastResult;
import org.ftctestbed.sim.config.ConfigException;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;

import java.util.List;

/**
 * The dyn4j world seen from above: no gravity, walls around the field, field elements and
 * the robots (which run into each other). Game pieces on the floor are added by GamePieces.
 */
public final class FieldWorld {
    private static final double WALL_THICKNESS = 0.1;

    // Collision categories: field elements meet everything; some (the HIVE frame's legs, above
    // ball height) meet only the robot.
    private static final long ROBOT = 1, PIECE = 2, FIELD = 4, ROBOT_ONLY = 8;
    public static final CategoryFilter PIECE_FILTER = new CategoryFilter(PIECE, ROBOT | PIECE | FIELD);
    private static final CategoryFilter ROBOT_FILTER = new CategoryFilter(ROBOT, ROBOT | PIECE | FIELD | ROBOT_ONLY);
    private static final CategoryFilter FIELD_FILTER = new CategoryFilter(FIELD, ROBOT | PIECE);
    private static final CategoryFilter ROBOT_ONLY_FILTER = new CategoryFilter(ROBOT_ONLY, ROBOT);

    public final World<Body> world = new World<>();
    private final DetectFilter<Body, BodyFixture> rayFilter = new DetectFilter<>(true, true, null);

    public FieldWorld(FieldConfig field, double dt) {
        world.setGravity(World.ZERO_GRAVITY);
        world.getSettings().setStepFrequency(dt);
        world.getSettings().setAtRestDetectionEnabled(false);
        // At 500 Hz nothing on the floor moves more than ~1 cm per step (launched pieces fly outside
        // dyn4j), so swept collision tests can't catch anything the normal ones miss.
        world.getSettings().setContinuousDetectionMode(org.dyn4j.dynamics.ContinuousDetectionMode.NONE);

        double half = field.size() / 2;
        double span = field.size() + 2 * WALL_THICKNESS;
        addStatic(Geometry.createRectangle(span, WALL_THICKNESS), 0, half + WALL_THICKNESS / 2, 0, field.wallFriction(), field.wallRestitution(), "wall");
        addStatic(Geometry.createRectangle(span, WALL_THICKNESS), 0, -half - WALL_THICKNESS / 2, 0, field.wallFriction(), field.wallRestitution(), "wall");
        addStatic(Geometry.createRectangle(WALL_THICKNESS, span), half + WALL_THICKNESS / 2, 0, 0, field.wallFriction(), field.wallRestitution(), "wall");
        addStatic(Geometry.createRectangle(WALL_THICKNESS, span), -half - WALL_THICKNESS / 2, 0, 0, field.wallFriction(), field.wallRestitution(), "wall");

        for (FieldConfig.Shape o : field.obstacles()) {
            addStatic(convex(o), o.x(), o.y(), o.heading(), o.friction(), o.restitution(), o);
        }
    }

    /**
     * Takes every body out and puts it back in the same order. The collision tree and cached
     * contacts then don't depend on what happened before, so a match replayed from a field reset
     * plays out exactly as it did the first time (dyn4j's results depend on the order it finds
     * contacts in, which the tree's history changes).
     */
    public void rebuild() {
        java.util.List<Body> bodies = new java.util.ArrayList<>(world.getBodies());
        world.removeAllBodies();
        for (Body b : bodies) world.addBody(b);
    }

    /** Adds a robot's body (at the field center; the simulation puts it at its start). */
    public Body addRobot(RobotConfig robotCfg) {
        Body robot = new Body();
        for (double[] r : robotOutline(robotCfg)) {
            BodyFixture rf = robot.addFixture(Geometry.createRectangle(r[2], r[3]), 1.0, 0.3, 0.05);
            rf.getShape().translate(r[0], r[1]);
            rf.setRestitutionVelocity(0.1);
            rf.setFilter(ROBOT_FILTER);
        }
        robot.setMass(new Mass(new Vector2(0, 0), robotCfg.mass(), robotCfg.yawInertia()));
        robot.setUserData("robot");
        world.addBody(robot);
        return robot;
    }

    /**
     * The robot's footprint as rectangles {centerX, centerY, length, width} in the robot frame: its
     * frame (centered frameOffset from the robot's center), less a mouth where the intake zone
     * reaches into it, so pieces can roll in to the rollers (and the robot can push a piece along
     * in its mouth).
     */
    static List<double[]> robotOutline(RobotConfig cfg) {
        double ox = cfg.frameOffsetX(), oy = cfg.frameOffsetY();
        double front = ox + cfg.length() / 2, rear = ox - cfg.length() / 2;
        double left = oy + cfg.width() / 2, right = oy - cfg.width() / 2;
        RobotConfig.Intake in = cfg.intake();
        List<double[]> out = new java.util.ArrayList<>();
        if (in == null || in.xMin() >= front - 0.002) {
            out.add(new double[]{ox, oy, cfg.length(), cfg.width()});
            return out;
        }
        // The mouth goes back far enough for the largest piece's center to reach the zone.
        double back = Math.max(rear + 0.05, in.xMin() - in.mouthDepthMargin());
        double y0 = Math.max(right, in.yMin()), y1 = Math.min(left, in.yMax());
        out.add(new double[]{(back + rear) / 2, oy, back - rear, cfg.width()});
        if (left - y1 > 0.003) out.add(new double[]{(back + front) / 2, (y1 + left) / 2, front - back, left - y1});
        if (y0 - right > 0.003) out.add(new double[]{(back + front) / 2, (y0 + right) / 2, front - back, y0 - right});
        return out;
    }

    /** A fixed rectangular field element (centered at x, y). */
    public void addStaticRect(double x, double y, double width, double height, double heading, Object userData) {
        addStatic(Geometry.createRectangle(width, height), x, y, heading, 0.3, 0.1, userData);
    }

    /** A fixed field element only the robot runs into: it's above the game pieces (e.g. a leaning leg). */
    public void addRobotOnlyRect(double x, double y, double width, double height, double heading, Object userData) {
        Body b = addStatic(Geometry.createRectangle(width, height), x, y, heading, 0.3, 0.1, userData);
        b.getFixture(0).setFilter(ROBOT_ONLY_FILTER);
    }

    private Body addStatic(Convex shape, double x, double y, double heading, double friction, double restitution, Object userData) {
        Body b = new Body();
        b.addFixture(shape, 1.0, friction, restitution).setFilter(FIELD_FILTER);
        b.setMass(MassType.INFINITE);
        b.translate(x, y);
        if (heading != 0) b.rotate(heading, x, y);
        b.setUserData(userData);
        world.addBody(b);
        return b;
    }

    /** Builds the shape in local coordinates around (shape.x, shape.y). */
    private static Convex convex(FieldConfig.Shape s) {
        switch (s.shape()) {
            case "circle":
                return Geometry.createCircle(s.diameter() / 2);
            case "polygon": {
                List<double[]> pts = s.points();
                Vector2[] v = new Vector2[pts.size()];
                for (int i = 0; i < v.length; i++) v[i] = new Vector2(pts.get(i)[0] - s.x(), pts.get(i)[1] - s.y());
                double area = 0;
                for (int i = 0; i < v.length; i++) area += v[i].cross(v[(i + 1) % v.length]);
                if (area < 0) java.util.Collections.reverse(java.util.Arrays.asList(v));
                try {
                    return Geometry.createPolygon(v);
                } catch (IllegalArgumentException e) {
                    throw new ConfigException("Obstacle \"" + s.name() + "\": polygon must be convex (" + e.getMessage() + ")");
                }
            }
            default:
                return Geometry.createRectangle(s.width(), s.height());
        }
    }

    /** Distance to the nearest body (other than the robot the sensor is on) along a ray, or +infinity. */
    public double raycast(Body self, double x, double y, double heading, double maxRange) {
        return raycast(self, x, y, heading, maxRange, b -> false);
    }

    /** As raycast, skipping bodies the beam passes over (e.g. game pieces lower than the sensor). */
    public double raycast(Body self, double x, double y, double heading, double maxRange, java.util.function.Predicate<Body> passesOver) {
        List<RaycastResult<Body, BodyFixture>> hits = world.raycast(new Ray(new Vector2(x, y), heading), maxRange, rayFilter);
        double best = Double.POSITIVE_INFINITY;
        for (RaycastResult<Body, BodyFixture> h : hits) {
            if (h.getBody() == self || passesOver.test(h.getBody())) continue;
            best = Math.min(best, h.getRaycast().getDistance());
        }
        return best;
    }
}
