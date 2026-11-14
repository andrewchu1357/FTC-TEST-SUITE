package org.ftctestbed.viewer.three;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.DirectionalLight;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.transform.Rotate;

import org.ftctestbed.sim.SimRobot;
import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.Snapshot;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The 3D field view, embedded in the Swing window. Snapshots are handed over from the Swing
 * timer and drawn on the JavaFX thread at the display's frame rate. With a partner robot both
 * robots are drawn, numbered on their signs, and the Follow camera can follow either one.
 *
 * Every frame JavaFX draws here is copied from the graphics card into the Swing window (the
 * whole view, at the screen's resolution), so the view changes only when something does: the
 * readout at most ten times a second, the robot's camera picture when the robot or what's in
 * front of it moved, and in efficient mode the field 30 times a second. A still field costs
 * next to nothing.
 */
public final class View3D extends JPanel {
    static {
        Platform.setImplicitExit(false);
    }

    private final JFXPanel fx = new JFXPanel();
    private final AtomicReference<List<Snapshot>> latest = new AtomicReference<>();
    private final Simulation sim;
    private Field3D field;
    private final List<Robot3D> robots = new ArrayList<>();
    private volatile int followed; // index of the robot the Follow camera follows
    private OrbitCamera camera;
    private Label hud, help;
    static final String HELP = "drag: rotate · right-drag/shift: pan · scroll: zoom";
    static final String FOLLOW_HELP = "following the robot · drag: swing around it · right-drag/shift: shift the view · scroll: zoom";
    private volatile String status = "";
    private volatile boolean active = true;
    private volatile boolean efficient;
    private Group world;
    private Consumer<javafx.scene.Node> pickListener;
    private AnimationTimer timer;
    private volatile boolean disposed;
    private volatile boolean stadium = true;
    /** The picture-in-picture of what the robot's camera sees (shown when the robot has one). */
    private CameraView lens;
    private Group scene3d;
    private volatile boolean lensWanted = true;

    public View3D(Simulation sim) {
        super(new BorderLayout());
        this.sim = sim;
        add(fx, BorderLayout.CENTER);
        Platform.runLater(this::build);
    }

    /**
     * JavaFX draws 3D with the graphics card only (Direct3D on Windows). Where it can't use the
     * card, it draws in software and leaves 3D shapes out: an old or blocked graphics driver, a
     * Remote Desktop session, a virtual machine. The view then says so instead of staying empty.
     */
    private void say3dUnavailable() {
        String keys = org.ftctestbed.viewer.platform.Os.keys("⌘2");
        javax.swing.JLabel note = new javax.swing.JLabel("<html><b>The 3D view isn't available on this computer right now.</b> Its graphics "
                + "driver (or a Remote Desktop session, or a virtual machine) doesn't give Java 3D drawing. "
                + "Everything else works: use the 2D field (" + keys + ").</html>");
        note.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 14, 10, 14));
        add(note, BorderLayout.NORTH);
        revalidate();
        System.out.println("3D view: this computer's graphics can't draw 3D in Java (software drawing is in use). Use the 2D field.");
    }

    private void build() {
        if (disposed) return;
        if (!Platform.isSupported(javafx.application.ConditionalFeature.SCENE3D)) {
            javax.swing.SwingUtilities.invokeLater(this::say3dUnavailable);
        }
        field = new Field3D(sim.fieldConfig);
        field.setStadiumVisible(stadium);
        for (SimRobot r : sim.robots()) {
            Robot3D r3 = new Robot3D(r.config);
            if (sim.robots().size() > 1) r3.setNumber(r.number);
            robots.add(r3);
        }
        camera = new OrbitCamera();
        // The field presets fit the field, its 12 in walls (1 in thick) and its tallest structures.
        camera.setScenery(sim.fieldConfig.size() / 2 + 0.0254, 12 * 0.0254, field.structureTop());

        // Field coordinates (z up) -> JavaFX coordinates (y down): rotate 90 degrees about x.
        // The see-through walls and CELL skins go in last (they blend with what's behind them).
        world = new Group(field.root, camera.camera);
        for (Robot3D r3 : robots) world.getChildren().add(r3.root);
        world.getChildren().add(field.clear);
        world.getTransforms().add(new Rotate(90, Rotate.X_AXIS));
        // Venue lighting: a warm overhead key light (the one the floor shadows follow), a cooler
        // fill from the other side, and a faint bounce off the floor onto the undersides.
        AmbientLight ambient = new AmbientLight(Color.color(0.31, 0.32, 0.35));
        DirectionalLight sun = new DirectionalLight(Color.color(0.75, 0.72, 0.67));
        sun.setDirection(toFx(Shapes.SUN));
        DirectionalLight fill = new DirectionalLight(Color.color(0.22, 0.25, 0.30));
        fill.setDirection(toFx(new double[]{-0.45, -0.55, -0.7}));
        DirectionalLight bounce = new DirectionalLight(Color.color(0.12, 0.12, 0.13));
        bounce.setDirection(toFx(new double[]{0.1, -0.2, 1}));
        Group lights = new Group(ambient, sun, fill, bounce);
        scene3d = new Group(world, lights);
        lens = new CameraView(world);

        SubScene sub = new SubScene(scene3d, 800, 800, true, SceneAntialiasing.BALANCED);
        sub.setFill(Color.web("#0E0F12"));
        sub.setCamera(camera.camera);
        camera.attach(sub);
        sub.setOnMouseClicked(e -> {
            if (pickListener != null && e.isStillSincePress() && e.getPickResult().getIntersectedNode() != null) {
                pickListener.accept(e.getPickResult().getIntersectedNode());
            }
        });

        hud = new Label();
        hud.setFont(Font.font(org.ftctestbed.viewer.platform.Os.monoFamily(), 12));
        hud.setTextFill(Color.web("#E6E8EB"));
        hud.setStyle("-fx-background-color: rgba(0,0,0,0.55); -fx-background-radius: 8; -fx-padding: 6 10 6 10;");
        hud.setMouseTransparent(true);
        help = new Label(HELP);
        help.setTextFill(Color.web("#9AA0A6"));
        help.setFont(Font.font(11));
        help.setMouseTransparent(true);

        // A soft darkening toward the corners, as a camera lens gives, draws the eye to the field.
        javafx.scene.layout.Region vignette = new javafx.scene.layout.Region();
        vignette.setStyle("-fx-background-color: radial-gradient(center 50% 45%, radius 75%, transparent 55%, rgba(0,0,0,0.38) 100%);");
        vignette.setMouseTransparent(true);
        StackPane rootPane = new StackPane(sub, vignette, hud, help, lens.box);
        StackPane.setAlignment(lens.box, Pos.TOP_RIGHT);
        StackPane.setMargin(lens.box, new Insets(8));
        StackPane.setAlignment(hud, Pos.TOP_LEFT);
        StackPane.setMargin(hud, new Insets(8));
        StackPane.setAlignment(help, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(help, new Insets(8));
        applySafeArea();
        applyHudTheme();
        sub.widthProperty().bind(rootPane.widthProperty());
        sub.heightProperty().bind(rootPane.heightProperty());
        Scene scene = new Scene(rootPane, 800, 800);
        scene.setFill(Color.web("#0E0F12"));
        fx.setScene(scene);

        boolean logFps = Boolean.getBoolean("testbed.fps");
        timer = new AnimationTimer() {
            long frames, pulses, hudAt, lensAt, since = System.nanoTime();
            int lensSeen;

            @Override
            public void handle(long now) {
                if (logFps && ++frames % 120 == 0) {
                    System.out.printf("3D view: %.1f fps%n", 120 / ((now - since) / 1e9));
                    since = now;
                }
                // Efficient: change the scene every other frame, so JavaFX draws 30 times a second.
                // (Turning the camera with the mouse still draws at once.)
                if (efficient && (++pulses & 1) == 1) return;
                List<Snapshot> shots = latest.getAndSet(null);
                if (!active) return;
                field.animate(now);
                if (shots == null || shots.isEmpty()) return;
                field.update(shots);
                for (int i = 0; i < Math.min(robots.size(), shots.size()); i++) robots.get(i).update(shots.get(i));
                // The x-ray outlines clear the view to the robot being followed.
                Snapshot f = shots.get(Math.min(followed, shots.size() - 1));
                camera.track(f.x(), f.y(), f.heading());
                field.cameraMoved(camera.eye(), camera.following() ? new double[]{f.x(), f.y(), 0.23} : null);
                // The readout, at most ten times a second.
                if (now - hudAt >= 100_000_000L) {
                    hudAt = now;
                    hud.setText(hudText(shots, status)); // the same text changes nothing
                }
                // What robot 1's camera sees: about ten times a second while it or what's in front of
                // it moves, and once a second when nothing does (for the crowd in the background).
                Snapshot.Camera cam = shots.get(0).cameras() == null || shots.get(0).cameras().isEmpty() ? null : shots.get(0).cameras().get(0);
                boolean lensOn = lensWanted && cam != null;
                if (lens.box.isVisible() != lensOn) lens.box.setVisible(lensOn);
                if (lensOn && now - lensAt >= 95_000_000L) {
                    int seen = lensScene(shots, cam);
                    if (seen != lensSeen || now - lensAt >= 1_000_000_000L) {
                        lens.render(scene3d, cam);
                        lensSeen = seen;
                        lensAt = now;
                    }
                }
            }
        };
        if (active) timer.start();
    }

    /**
     * A fingerprint of what the robot's camera would see: where it is and looks, the robots and
     * the game pieces (to the millimeter). The same fingerprint: the same picture.
     */
    static int lensScene(List<Snapshot> shots, Snapshot.Camera cam) {
        int h = 1;
        for (double[] v : new double[][]{cam.position(), cam.forward()}) for (double d : v) h = 31 * h + (int) Math.round(d * 1000);
        h = 31 * h + cam.targets().size();
        for (Snapshot s : shots) {
            h = 31 * h + (int) Math.round(s.x() * 1000);
            h = 31 * h + (int) Math.round(s.y() * 1000);
            h = 31 * h + (int) Math.round(s.heading() * 1000);
            for (Snapshot.Piece p : s.pieces()) {
                h = 31 * h + (int) Math.round(p.x() * 1000);
                h = 31 * h + (int) Math.round(p.y() * 1000);
                h = 31 * h + (int) Math.round(p.z() * 1000);
            }
        }
        return h;
    }

    /** A direction in field coordinates (z up) as one in the scene's (y down, z = field y). */
    private static javafx.geometry.Point3D toFx(double[] d) {
        return new javafx.geometry.Point3D(d[0], -d[2], d[1]);
    }

    /** Stops drawing and lets go of the scene (the window is closing). */
    public void dispose() {
        disposed = true;
        latest.set(null);
        Platform.runLater(() -> {
            if (timer != null) timer.stop();
            timer = null;
            pickListener = null;
            fx.setScene(null);
        });
    }

    /** Hand over the newest snapshot (any thread). */
    public void offer(Snapshot s) {
        latest.set(List.of(s));
    }

    /** Hand over the newest snapshot of every robot, the first robot first (any thread). */
    public void offer(List<Snapshot> robots) {
        latest.set(List.copyOf(robots));
    }

    /** Where the robots are (one line each, numbered when there are two) and the status line. */
    static String hudText(List<Snapshot> shots, String status) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < shots.size(); i++) {
            Snapshot s = shots.get(i);
            if (shots.size() > 1) b.append("Robot ").append(i + 1).append("   ");
            b.append(String.format(java.util.Locale.US, "x %7.2f in   y %7.2f in   heading %6.1f°%n",
                    s.x() / 0.0254, s.y() / 0.0254, Math.toDegrees(s.heading())));
            // What each odometry device reports, in its own frame (zero where the code last reset it).
            if (s.odometry() != null) {
                for (Snapshot.Odometry o : s.odometry()) {
                    b.append(String.format(java.util.Locale.US, "  %s (%s)  x %7.2f in  y %7.2f in  heading %6.1f°  %s%n", o.name(), o.kind(),
                            o.x() / 0.0254, o.y() / 0.0254,
                            Math.toDegrees(org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.normalizeRadians(o.heading())), o.status()));
                }
            }
        }
        return b.append(status).toString();
    }

    /** Shows the robot's camera view in the corner, when the robot has a camera (any thread). */
    public void setCameraViewVisible(boolean on) {
        lensWanted = on;
    }

    /** Shows the stands and crowd round the field, or (false) plain black drape instead (any thread). */
    public void setStadiumVisible(boolean on) {
        stadium = on;
        Platform.runLater(() -> {
            if (field != null) field.setStadiumVisible(on);
        });
    }

    public void setStatus(String s) {
        status = s;
    }

    // Pixels covered by the window's floating panels (top, right, bottom, left), and the HUD's colors.
    private double[] safeArea = {0, 0, 0, 0};
    private java.awt.Color hudText = new java.awt.Color(0xE6E8EB), hudBack = new java.awt.Color(0, 0, 0, 140);

    /**
     * Pixels at each edge covered by panels floating over the view: the readouts move clear of
     * them and the camera frames the field in the rest (any thread).
     */
    public void setSafeArea(double top, double right, double bottom, double left) {
        safeArea = new double[]{top, right, bottom, left};
        Platform.runLater(this::applySafeArea);
    }

    private void applySafeArea() {
        if (hud == null) return;
        double[] a = safeArea;
        StackPane.setMargin(hud, new Insets(a[0] + 10, 0, 0, a[3] + 12));
        StackPane.setMargin(lens.box, new Insets(a[0] + 10, a[1] + 12, 0, 0));
        StackPane.setMargin(help, new Insets(0, a[1] + 12, a[2] + 10, 0));
        if (camera != null) camera.setSafeArea(a[0], a[1], a[2], a[3]);
    }

    /** The position readout's and help's colors, from the app's theme (any thread). */
    public void setHudTheme(java.awt.Color text, java.awt.Color background) {
        hudText = text;
        hudBack = background;
        Platform.runLater(this::applyHudTheme);
    }

    private void applyHudTheme() {
        if (hud == null) return;
        String back = String.format(java.util.Locale.US, "rgba(%d,%d,%d,%.2f)", hudBack.getRed(), hudBack.getGreen(), hudBack.getBlue(), hudBack.getAlpha() / 255.0);
        hud.setTextFill(fxColor(hudText));
        hud.setStyle("-fx-background-color: " + back + "; -fx-background-radius: 12; -fx-padding: 8 12 8 12;");
        help.setTextFill(fxColor(hudText));
        help.setStyle("-fx-background-color: " + back + "; -fx-background-radius: 10; -fx-padding: 4 10 4 10;");
    }

    private static Color fxColor(java.awt.Color c) {
        return Color.rgb(c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha() / 255.0);
    }

    /** Draws while active; inactive (another view shown, the window minimized) the frame timer stops, so JavaFX idles. Any thread. */
    public void setActive(boolean active) {
        this.active = active;
        Platform.runLater(() -> {
            if (timer == null || disposed) return;
            if (this.active) timer.start(); else timer.stop();
        });
    }

    /** Efficient: draw the field 30 times a second instead of 60 (about half the work); any thread. */
    public void setEfficient(boolean efficient) {
        this.efficient = efficient;
    }

    public void preset(OrbitCamera.Preset p) {
        Platform.runLater(() -> {
            if (camera != null) camera.preset(p);
            if (help != null) help.setText(p == OrbitCamera.Preset.FOLLOW ? FOLLOW_HELP : HELP);
        });
    }

    /** Camera presets by display name. */
    public static final String[] PRESETS = {"Red driver", "Blue driver", "Audience", "Top", "Stands", "Follow robot"};

    /** This view's camera presets: with a partner, "Follow robot 1" and "Follow robot 2" in place of "Follow robot". */
    public String[] presets() {
        return presets(sim.robots().size());
    }

    static String[] presets(int robots) {
        if (robots < 2) return PRESETS.clone();
        List<String> out = new ArrayList<>(List.of(PRESETS).subList(0, PRESETS.length - 1));
        for (int n = 1; n <= robots; n++) out.add("Follow robot " + n);
        return out.toArray(String[]::new);
    }

    /** "Follow robot" follows the first robot, "Follow robot 2" the second. */
    public void preset(String name) {
        if (name.startsWith("Follow robot")) {
            followed = followIndex(name);
            preset(OrbitCamera.Preset.FOLLOW);
            return;
        }
        OrbitCamera.Preset p = switch (name) {
            case "Blue driver" -> OrbitCamera.Preset.BLUE_DRIVER;
            case "Audience" -> OrbitCamera.Preset.AUDIENCE;
            case "Top" -> OrbitCamera.Preset.TOP;
            case "Stands" -> OrbitCamera.Preset.STANDS;
            default -> OrbitCamera.Preset.RED_DRIVER;
        };
        preset(p);
    }

    /** Which robot (0 = the first) a "Follow robot n" preset follows. */
    static int followIndex(String name) {
        String n = name.substring("Follow robot".length()).trim();
        try {
            return n.isEmpty() ? 0 : Math.max(0, Integer.parseInt(n) - 1);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    void setPickListener(Consumer<javafx.scene.Node> listener) {
        pickListener = listener;
    }

    /** The first robot (null until the scene is built). */
    Robot3D robot() {
        return robots.isEmpty() ? null : robots.get(0);
    }

    /** Shows a CAD model in place of the first robot's generated one (null goes back to the generated one). */
    public void setModel(RobotModel model) {
        setModel(1, model);
    }

    /** Shows a CAD model on robot number n (1 or 2). */
    public void setModel(int number, RobotModel model) {
        onFx(() -> {
            if (number - 1 < robots.size()) robots.get(number - 1).setCadModel(model);
        });
    }

    /** The first robot's configured size as a translucent box plus a front arrow (for aligning a CAD model). */
    public void setOutlineVisible(boolean visible) {
        onFx(() -> robot().setOutlineVisible(visible));
    }

    /** Clicks on a CAD part (the part, or null for a click elsewhere). Called on the JavaFX thread. */
    public void setPartPickListener(Consumer<org.ftctestbed.viewer.model.ModelData.Node> listener) {
        setPickListener(n -> listener.accept(RobotModel.partOf(n)));
    }

    /** Run something on the JavaFX thread once the scene is built. */
    void onFx(Runnable r) {
        Platform.runLater(r);
    }
}
