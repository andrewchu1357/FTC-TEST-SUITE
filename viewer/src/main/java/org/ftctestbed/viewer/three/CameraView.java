package org.ftctestbed.viewer.three;

import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.transform.Affine;

import org.ftctestbed.sim.Snapshot;

import java.util.Locale;

/**
 * What the robot's camera sees, in a corner of the 3D view: the scene drawn again from the
 * camera's lens with its own view (Limelight 3A: 54.5 x 42 degrees; a webcam: its lens at the
 * size it streams), scaled to 320 x 240, with the crosshair and what was found on the newest
 * frame (a Limelight's targets, the primary in green; a webcam's AprilTags in view).
 */
final class CameraView {
    static final int W = 320, H = 240;

    final VBox box;
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final Affine lensPose = new Affine();
    private final WritableImage image = new WritableImage(W, H);
    private final Canvas overlay = new Canvas(W, H);
    private final Label label = new Label();
    private final SnapshotParameters params = new SnapshotParameters();

    /** world: the group in field coordinates the camera goes in (so its pose is in field meters). */
    CameraView(Group world) {
        camera.setVerticalFieldOfView(true);
        camera.setNearClip(0.01);
        camera.setFarClip(200);
        camera.getTransforms().add(lensPose);
        world.getChildren().add(camera);
        params.setCamera(camera);
        params.setDepthBuffer(true);
        params.setFill(Color.web("#0E0F12"));
        params.setViewport(new javafx.geometry.Rectangle2D(0, 0, W, H));

        ImageView view = new ImageView(image);
        StackPane picture = new StackPane(view, overlay);
        picture.setMaxSize(W, H);
        label.setTextFill(Color.web("#E6E8EB"));
        label.setFont(Font.font(org.ftctestbed.viewer.platform.Os.monoFamily(), 11));
        box = new VBox(4, label, picture);
        box.setPadding(new Insets(6));
        box.setMaxSize(W + 12, H + 32);
        box.setStyle("-fx-background-color: rgba(0,0,0,0.55); -fx-background-radius: 8;");
        box.setMouseTransparent(true);
        box.setVisible(false);
    }

    /** Draws the scene from this camera and marks what it found. */
    void render(Group scene, Snapshot.Camera cam) {
        double[] x = cam.right(), y = cam.down(), z = cam.forward(), o = cam.position();
        // A JavaFX camera looks along its +z with +y down: the same as the lens frame.
        lensPose.setToTransform(x[0], y[0], z[0], o[0], x[1], y[1], z[1], o[1], x[2], y[2], z[2], o[2]);
        camera.setFieldOfView(Math.toDegrees(cam.vfov()));
        scene.snapshot(params, image);
        drawOverlay(cam);
        if (cam.pipeline() < 0) {
            // A webcam: the tags in its newest frame (what the AprilTag processor gets to decode).
            label.setText(String.format(Locale.US, "%s  ·  %s  ·  %d tag%s in view%s", cam.name(), cam.kind(), cam.targets().size(),
                    cam.targets().size() == 1 ? "" : "s", cam.pipelineType().contains("not streaming") ? "  ·  not streaming" : ""));
        } else {
            String pipe = cam.pipelineType().contains("fiducial") ? "AprilTags" : cam.pipelineType().isEmpty() ? "" : "color";
            label.setText(String.format(Locale.US, "%s  ·  pipeline %d %s  ·  %d target%s", cam.name(), cam.pipeline(), pipe,
                    cam.targets().size(), cam.targets().size() == 1 ? "" : "s"));
        }
    }

    private void drawOverlay(Snapshot.Camera cam) {
        GraphicsContext g = overlay.getGraphicsContext2D();
        g.clearRect(0, 0, W, H);
        double sx = (double) W / cam.width(), sy = (double) H / cam.height();
        // Crosshair.
        g.setStroke(Color.web("#FFFFFFAA"));
        g.setLineWidth(1);
        g.strokeLine(W / 2.0 - 8, H / 2.0, W / 2.0 + 8, H / 2.0);
        g.strokeLine(W / 2.0, H / 2.0 - 8, W / 2.0, H / 2.0 + 8);
        g.setFont(Font.font(org.ftctestbed.viewer.platform.Os.monoFamily(), 10));
        for (Snapshot.CameraTarget t : cam.targets()) {
            double[][] c = t.corners();
            g.setStroke(t.primary() ? Color.web("#39FF6A") : Color.web("#4FC3F7"));
            g.setLineWidth(t.primary() ? 2 : 1.2);
            double[] xs = new double[c.length], ys = new double[c.length];
            for (int i = 0; i < c.length; i++) {
                xs[i] = c[i][0] * sx;
                ys[i] = c[i][1] * sy;
            }
            g.strokePolygon(xs, ys, c.length);
            if (cam.pipelineType().contains("fiducial") || cam.pipeline() < 0) {
                g.setFill(Color.web("#FFFFFF"));
                g.fillText(t.label(), xs[0] + 2, ys[0] - 3);
            }
        }
    }
}
