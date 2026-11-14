package org.ftctestbed.sim;

import org.ftctestbed.sim.game.RobotPieceHandling;
import org.ftctestbed.sim.game.SeasonSnapshot;

import java.util.List;

/** An immutable copy of everything the viewer needs to draw one frame. Field units: meters, radians. */
public record Snapshot(
        double time,
        double x, double y, double heading,
        double vx, double vy, double omega,
        double batteryVolts,
        int hubLedColor,
        List<Wheel> wheels,
        List<Piece> pieces,
        List<Ray> rays,
        List<Motor> motors,
        List<Servo> servos,
        List<Mechanism> mechanisms,
        RobotPieceHandling.Parts parts,
        List<Flight> flights,
        SeasonSnapshot season,
        String alliance,
        String matchPhase,
        double matchClock,
        Physics physics,
        List<Odometry> odometry,
        List<Light> lights,
        List<Camera> cameras) {

    /**
     * A camera on the robot: its lens's position and axes in field coordinates (x right, y down
     * in the picture, z out of the lens), its view, the pipeline it ran on the newest frame, and
     * what it found there (pixel corners).
     */
    public record Camera(String name, String kind, double[] position, double[] right, double[] down, double[] forward,
                         double hfov, double vfov, int width, int height, int pipeline, String pipelineType,
                         List<CameraTarget> targets) {}

    /** Something a camera found: a label (a tag's id, a color) and its corners in pixels; primary: the one tx/ty/ta are for. */
    public record CameraTarget(String label, double[][] corners, boolean primary) {}

    /** A light robot code switches (an LED, a Blinkin strip, an LED stick): each LED's color, 0xRRGGBB, 0 = off. */
    public record Light(String name, String type, int[] colors) {}

    /** load: normal force on the tiles (N); lifting: the wheel is off the tiles. */
    public record Wheel(String label, double x, double y, double duty, boolean slipping, double load, boolean lifting) {}

    /**
     * Battery and chassis state: charge left (0..1) and used, resting voltage, battery current (A),
     * center of gravity height (m), acceleration in the robot frame (m/s^2, x forward, y left), and
     * which wheels are lifting off the tiles (e.g. "FL, FR"; null when none).
     */
    /**
     * What an odometry device (goBILDA Pinpoint, SparkFun OTOS) reports, in its own frame (where
     * robot code set it, else where it was reset): m, m, rad. status: READY, CALIBRATING,
     * TRACKING or LOST SURFACE.
     */
    public record Odometry(String name, String kind, double x, double y, double heading, String status) {}

    public record Physics(double batteryCharge, double batteryUsedMah, double batteryRestingVolts, double batteryCurrent,
                          double cgHeight, double accelX, double accelY, String liftingWheels) {}

    /** A game piece. z is the height of its center; state is a GamePiece.State name. */
    public record Piece(int id, String kind, boolean ball, double radius, double width, double height, int color,
                        double x, double y, double z, double heading, String state) {}

    /** A recorded launch: points are {x, y, z} in field meters. */
    public record Flight(int color, List<double[]> points, String outcome) {}

    public record Ray(String name, double x0, double y0, double x1, double y1, boolean hit) {}

    /** Power, position and velocity as robot code sees them (after setDirection). */
    public record Motor(String name, String hub, int port, String mode, double power, double duty,
                        int position, double velocityTps, double currentA, double rawTicks, double ticksPerRev) {}

    public record Servo(String name, boolean continuous, double commanded, double angle, boolean enabled) {}

    /** position: inches for slides, degrees for arms, revolutions for free mechanisms. */
    public record Mechanism(String name, String type, double position, double min, double max) {}

    /**
     * Whether two snapshots show the same thing apart from the clock: every value (arrays and
     * nested records included) equal. A still field (nothing running, or paused) gives the same
     * scene step after step, so a view can skip drawing it again.
     */
    public static boolean sameScene(Snapshot a, Snapshot b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        try {
            for (java.lang.reflect.Method m : ACCESSORS.get(Snapshot.class)) {
                if (m.getName().equals("time")) continue;
                if (!same(m.invoke(a), m.invoke(b))) return false;
            }
        } catch (ReflectiveOperationException e) {
            return false;
        }
        return true;
    }

    /** Whether two parts of snapshots hold the same values (records, lists and arrays compared deeply). */
    public static boolean sameValues(Object a, Object b) {
        try {
            return same(a, b);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static final ClassValue<java.lang.reflect.Method[]> ACCESSORS = new ClassValue<>() {
        @Override
        protected java.lang.reflect.Method[] computeValue(Class<?> type) {
            java.lang.reflect.RecordComponent[] parts = type.getRecordComponents();
            java.lang.reflect.Method[] out = new java.lang.reflect.Method[parts.length];
            for (int i = 0; i < parts.length; i++) {
                out[i] = parts[i].getAccessor();
                out[i].setAccessible(true);
            }
            return out;
        }
    };

    private static boolean same(Object x, Object y) throws ReflectiveOperationException {
        if (x == y) return true;
        if (x == null || y == null || x.getClass() != y.getClass()) return false;
        if (x instanceof Record) {
            for (java.lang.reflect.Method m : ACCESSORS.get(x.getClass())) if (!same(m.invoke(x), m.invoke(y))) return false;
            return true;
        }
        if (x instanceof List<?> lx) {
            List<?> ly = (List<?>) y;
            if (lx.size() != ly.size()) return false;
            for (int i = 0; i < lx.size(); i++) if (!same(lx.get(i), ly.get(i))) return false;
            return true;
        }
        if (x instanceof double[] dx) return java.util.Arrays.equals(dx, (double[]) y);
        if (x instanceof int[] ix) return java.util.Arrays.equals(ix, (int[]) y);
        if (x instanceof Object[] ox) {
            Object[] oy = (Object[]) y;
            if (ox.length != oy.length) return false;
            for (int i = 0; i < ox.length; i++) if (!same(ox[i], oy[i])) return false;
            return true;
        }
        if (x.getClass().isArray()) return java.util.Objects.deepEquals(x, y);
        return x.equals(y);
    }
}
