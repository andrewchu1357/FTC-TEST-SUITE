package org.ftctestbed.viewer;

import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.Snapshot;
import org.ftctestbed.sim.opmode.OpModeRunner;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The field view. While no OpMode is running you can drag the robot to a new spot and
 * scroll over it to rotate it (15 degrees per notch, hold Shift for 1 degree). While one runs,
 * dragging the robot pushes it by hand (tuning OpModes like Road Runner's ForwardPushTest ask
 * you to): the point you grab is pulled toward the mouse, so grabbing a corner turns it.
 */
public final class FieldPanel extends JPanel {
    private static final double IN = FieldRenderer.IN;
    private static final int MAX_TRAIL = 20000;

    private final Simulation sim;
    private final OpModeRunner runner;
    private final FieldRenderer renderer;
    private final List<double[]> trail = new ArrayList<>();
    private volatile Snapshot snapshot;
    private boolean showTrail = true;
    private Point2D.Double hover;
    private boolean dragging;
    private double dragDx, dragDy;
    private double[] pushGrab; // where the hand holds the robot (robot frame), while pushing
    private volatile boolean driverView = true;
    private volatile boolean follow;
    private String statusLine = "";
    private volatile boolean efficient;
    private int refreshes;
    // What the last paint showed, so a still field isn't drawn again (see refresh()).
    private Snapshot painted;
    private Object paintedPacket, paintedCanvas;
    private Point2D.Double paintedHover;
    private boolean paintedHand;
    private double[] paintedCam;
    private String paintedStatus = "";
    private int hudBottom;
    // The "follow robot" camera: where it looks (m), its rotation, how close it is.
    private double camX, camY, camRotation, camZoom = FOLLOW_ZOOM;
    private long camNanos;
    private boolean camPlaced;
    static final double FOLLOW_ZOOM = 2.3;      // about 60 in of field across the view
    static final double FOLLOW_LEAD = 14 * IN;  // look this far ahead of the robot
    static final double FOLLOW_SECONDS = 0.25;  // how quickly the camera catches up (time constant)
    static final double FOLLOW_TURN_SECONDS = 0.45;

    public FieldPanel(Simulation sim, OpModeRunner runner) {
        this.sim = sim;
        this.runner = runner;
        this.renderer = new FieldRenderer(sim.fieldConfig, sim.robotConfig);
        setBackground(FieldRenderer.BACKGROUND);
        setPreferredSize(new Dimension(800, 860));
        setFocusable(true);

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                Snapshot s = snapshot;
                if (s == null) return;
                Point2D.Double p = renderer.toField(e.getX(), e.getY());
                if (!canMoveRobot()) {
                    if (overRobot(s, p)) {
                        double c = Math.cos(s.heading()), sn = Math.sin(s.heading());
                        double dx = p.x - s.x(), dy = p.y - s.y();
                        pushGrab = new double[]{c * dx + sn * dy, -sn * dx + c * dy};
                        sim.setHand(pushGrab[0], pushGrab[1], p.x, p.y);
                        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    }
                    return;
                }
                if (overRobot(s, p)) {
                    dragging = true;
                    dragDx = s.x() - p.x;
                    dragDy = s.y() - p.y;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                hover = renderer.toField(e.getX(), e.getY());
                if (pushGrab != null) {
                    sim.setHand(pushGrab[0], pushGrab[1], hover.x, hover.y);
                    return;
                }
                if (!dragging) return;
                Snapshot s = snapshot;
                double half = sim.fieldConfig.size() / 2;
                double x = clamp(hover.x + dragDx, -half, half), y = clamp(hover.y + dragDy, -half, half);
                sim.moveRobot(x, y, s.heading());
                clearTrail();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragging = false;
                if (pushGrab != null) sim.clearHand();
                pushGrab = null;
                setCursor(Cursor.getDefaultCursor());
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                hover = renderer.toField(e.getX(), e.getY());
                Snapshot s = snapshot;
                boolean over = s != null && overRobot(s, hover);
                setCursor(Cursor.getPredefinedCursor(over ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = null;
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                Snapshot s = snapshot;
                if (s == null) return;
                Point2D.Double p = renderer.toField(e.getX(), e.getY());
                boolean rotateRobot = canMoveRobot() && overRobot(s, p);
                if (follow && !rotateRobot) { // zoom the follow camera
                    camZoom = clamp(camZoom * Math.pow(1.15, -e.getPreciseWheelRotation()), 1.2, 6);
                    repaint();
                    return;
                }
                if (!rotateRobot) return;
                double step = Math.toRadians(e.isShiftDown() ? 1 : 15);
                double h = Math.round((s.heading() - e.getPreciseWheelRotation() * step) / step) * step;
                sim.moveRobot(s.x(), s.y(), h);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    /**
     * Driver's view: the field as the driver of the robot's alliance sees it from their Driver
     * Station (their wall at the bottom), so stick forward is up the screen, as at a match. Top
     * view: +x to the right. Without an alliance (no season), always the top view.
     */
    public void setDriverView(boolean on) {
        driverView = on;
        repaint();
    }

    public boolean driverView() {
        return driverView;
    }

    /**
     * Follow robot: the camera stays just ahead of the robot and turns with it, so the robot's
     * front is always up the screen: with robot-centric driving, stick up is up the screen,
     * stick right is right. It eases after the robot instead of being bolted to it. Scroll zooms.
     */
    public void setFollow(boolean on) {
        follow = on;
        camPlaced = false;
        repaint();
    }

    public boolean follow() {
        return follow;
    }

    /** The renderer as last laid out (tests). */
    FieldRenderer renderer() {
        return renderer;
    }

    /** Eases the follow camera toward the robot, dt seconds after the last time. */
    private void moveCamera(Snapshot s, double dt) {
        // Hold still while the robot is dragged by the mouse, or the field slides under the cursor.
        if (dragging || pushGrab != null) return;
        double h = s.heading();
        double wantX = s.x() + Math.cos(h) * FOLLOW_LEAD, wantY = s.y() + Math.sin(h) * FOLLOW_LEAD;
        double wantRotation = FieldRenderer.driverView(h); // the robot's front up the screen
        if (!camPlaced || Math.hypot(wantX - camX, wantY - camY) > 1.5) { // first frame, or a jump (reset, teleport)
            camX = wantX;
            camY = wantY;
            camRotation = wantRotation;
            camPlaced = true;
            return;
        }
        double k = 1 - Math.exp(-dt / FOLLOW_SECONDS), kTurn = 1 - Math.exp(-dt / FOLLOW_TURN_SECONDS);
        camX += (wantX - camX) * k;
        camY += (wantY - camY) * k;
        camRotation += Math.IEEEremainder(wantRotation - camRotation, 2 * Math.PI) * kTurn;
    }

    private double viewRotation() {
        String a = sim.alliance();
        if (!driverView || a == null) return 0;
        // Red drivers stand at the -x wall looking toward +x, blue at the +x wall.
        return FieldRenderer.driverView(a.equals("blue") ? Math.PI : 0);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private boolean canMoveRobot() {
        OpModeRunner.State st = runner.state();
        return st != OpModeRunner.State.RUNNING && st != OpModeRunner.State.STOPPING;
    }

    private boolean overRobot(Snapshot s, Point2D.Double p) {
        double dx = p.x - s.x(), dy = p.y - s.y();
        double c = Math.cos(-s.heading()), sn = Math.sin(-s.heading());
        double rx = c * dx - sn * dy, ry = sn * dx + c * dy;
        return Math.abs(rx) <= sim.robotConfig.length() / 2 && Math.abs(ry) <= sim.robotConfig.width() / 2;
    }

    public void setShowTrail(boolean show) {
        showTrail = show;
        repaint();
    }

    public void clearTrail() {
        synchronized (trail) {
            trail.clear();
        }
        repaint();
    }

    public void setStatusLine(String s) {
        statusLine = s;
    }

    /** Called ~60 times a second on the Swing thread. */
    public void refresh(Snapshot s) {
        long now = System.nanoTime();
        double dt = camPlaced ? Math.min(0.1, (now - camNanos) / 1e9) : 0;
        camNanos = now;
        refresh(s, dt);
    }

    /** refresh(), dt seconds after the last one. */
    void refresh(Snapshot s, double dt) {
        snapshot = s;
        if (follow) moveCamera(s, dt);
        synchronized (trail) {
            double[] last = trail.isEmpty() ? null : trail.get(trail.size() - 1);
            if (last == null || Math.hypot(s.x() - last[0], s.y() - last[1]) > 0.25 * IN) {
                trail.add(new double[]{s.x(), s.y()});
                if (trail.size() > MAX_TRAIL) trail.remove(0);
            }
        }
        if (efficient && (++refreshes & 1) != 0) return;
        if (changedSincePaint(s)) {
            repaint();
        } else if (!statusLine.equals(paintedStatus)) {
            repaint(0, 0, getWidth(), hudBottom); // only the clock in the readout changed
        }
    }

    /** Whether anything drawn differs from the last paint (the status line aside). */
    private boolean changedSincePaint(Snapshot s) {
        double[] cam = follow ? new double[]{camX, camY, camRotation, camZoom} : null;
        return !Snapshot.sameScene(painted, s)
                || com.acmerobotics.dashboard.FtcDashboard.getInstance().simLastPacket() != paintedPacket
                || com.bylazar.field.PanelsField.INSTANCE.getField().simCanvas() != paintedCanvas
                || sim.handLine() != null || paintedHand
                || !java.util.Objects.equals(hover, paintedHover)
                || !java.util.Arrays.equals(cam, paintedCam);
    }

    /** Efficient: draw 30 times a second instead of 60 (the trail still gets every point). */
    public void setEfficient(boolean efficient) {
        this.efficient = efficient;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        if (follow && camPlaced) {
            renderer.setViewRotation(camRotation);
            renderer.setCamera(camX, camY, camZoom);
        } else {
            renderer.setViewRotation(viewRotation());
            renderer.clearCamera();
        }
        Insets a = safeArea;
        renderer.layout(a.left, a.top, getWidth() - a.left - a.right, getHeight() - a.top - a.bottom, 72);
        Snapshot s = snapshot;
        painted = s;
        paintedHover = hover == null ? null : (Point2D.Double) hover.clone();
        paintedCam = follow && camPlaced ? new double[]{camX, camY, camRotation, camZoom} : null;
        paintedStatus = statusLine;
        List<double[]> t;
        synchronized (trail) {
            t = showTrail ? new ArrayList<>(trail) : null;
        }
        renderer.paint(g, s, t);
        // What robot code drew on the FTC Dashboard / Panels field (Road Runner and Pedro draw the
        // robot's estimated pose and path there).
        var packet = com.acmerobotics.dashboard.FtcDashboard.getInstance().simLastPacket();
        paintedPacket = packet;
        if (packet != null) renderer.paintOverlay(g, packet.fieldOverlay().getOperations());
        var panels = com.bylazar.field.PanelsField.INSTANCE.getField().simCanvas();
        paintedCanvas = panels;
        if (panels != null) renderer.paintOverlay(g, panels.getOperations());
        double[] handLine = sim.handLine();
        paintedHand = handLine != null;
        if (handLine != null) {
            Graphics2D hg = renderer.fieldGraphics(g);
            hg.setColor(new java.awt.Color(255, 255, 255, 200));
            hg.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 5f}, 0f));
            hg.draw(new java.awt.geom.Line2D.Double(renderer.sx(handLine[0]), renderer.sy(handLine[1]), renderer.sx(handLine[2]), renderer.sy(handLine[3])));
            hg.fill(new java.awt.geom.Ellipse2D.Double(renderer.sx(handLine[0]) - 4, renderer.sy(handLine[1]) - 4, 8, 8));
            hg.dispose();
        }
        if (s != null) paintHud(g, s);
        g.dispose();
    }

    // Pixels at each edge covered by panels floating over the view, and the readouts' colors.
    private Insets safeArea = new Insets(0, 0, 0, 0);
    private Color hudText = new Color(0xE6E8EB), hudBack = new Color(0, 0, 0, 150);

    /** Keeps the field and the readouts clear of the panels floating over this view. */
    public void setSafeArea(Insets safe) {
        safeArea = (Insets) safe.clone();
        repaint();
    }

    /** The readouts' text and background colors (from the app's theme). */
    public void setHudColors(Color text, Color background) {
        hudText = text;
        hudBack = background;
        repaint();
    }

    private void paintHud(Graphics2D g, Snapshot s) {
        Insets a = safeArea;
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int left = a.left + 10, top = a.top + 10;
        g.setFont(org.ftctestbed.viewer.platform.Os.mono(Font.PLAIN, 12));
        Snapshot.Physics ph = s.physics();
        String[] lines = {
                String.format("x %7.2f in   y %7.2f in   heading %6.1f°", s.x() / IN, s.y() / IN, Math.toDegrees(s.heading())),
                String.format("speed %5.1f in/s   turn %6.1f °/s   battery %5.2f V (%2.0f%%, %3.0f mAh used, %4.1f A)",
                        Math.hypot(s.vx(), s.vy()) / IN, Math.toDegrees(s.omega()), s.batteryVolts(),
                        100 * ph.batteryCharge(), ph.batteryUsedMah(), ph.batteryCurrent()),
                String.format("accel %4.2f g fwd %5.2f g side   center of gravity %4.1f in up",
                        ph.accelX() / 9.81, ph.accelY() / 9.81, ph.cgHeight() / IN),
                statusLine
        };
        int w = 0;
        for (String l : lines) w = Math.max(w, g.getFontMetrics().stringWidth(l));
        g.setColor(hudBack);
        g.fill(new RoundRectangle2D.Double(left, top, w + 20, lines.length * 16 + 12, 14, 14));
        hudBottom = top + lines.length * 16 + 13;
        g.setColor(hudText);
        for (int i = 0; i < lines.length; i++) g.drawString(lines[i], left + 10, top + 19 + i * 16);

        if (ph.liftingWheels() != null) {
            String warn = "⚠ Wheels off the tiles: " + ph.liftingWheels() + " - the robot is tipping";
            int ww = g.getFontMetrics().stringWidth(warn);
            int y = top + lines.length * 16 + 18;
            g.setColor(new Color(176, 0, 32, 230));
            g.fill(new RoundRectangle2D.Double(left, y, ww + 20, 24, 12, 12));
            g.setColor(Color.WHITE);
            g.drawString(warn, left + 10, y + 17);
        }

        // What the odometry devices report (in robot code's coordinates), to compare with the
        // true pose above: the difference is the localization error.
        if (s.odometry() != null && !s.odometry().isEmpty()) {
            List<String> odo = new ArrayList<>();
            for (Snapshot.Odometry o : s.odometry()) {
                odo.add(String.format("%s (%s)  x %7.2f in  y %7.2f in  heading %6.1f°  %s", o.name(), o.kind(),
                        o.x() / IN, o.y() / IN, Math.toDegrees(org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.normalizeRadians(o.heading())),
                        o.status()));
            }
            int ow = 0;
            for (String l : odo) ow = Math.max(ow, g.getFontMetrics().stringWidth(l));
            int otop = getHeight() - a.bottom - odo.size() * 16 - 22;
            g.setColor(hudBack);
            g.fill(new RoundRectangle2D.Double(left, otop, ow + 20, odo.size() * 16 + 12, 14, 14));
            g.setColor(hudText);
            for (int i = 0; i < odo.size(); i++) g.drawString(odo.get(i), left + 10, otop + 19 + i * 16);
        }

        if (hover != null) {
            String h = String.format("cursor (%.1f, %.1f) in", hover.x / IN, hover.y / IN);
            int hw = g.getFontMetrics().stringWidth(h);
            int right = getWidth() - a.right - 10;
            g.setColor(hudBack);
            g.fill(new RoundRectangle2D.Double(right - hw - 20, top, hw + 20, 26, 13, 13));
            g.setColor(hudText);
            g.drawString(h, right - hw - 10, top + 18);
        }
    }
}
