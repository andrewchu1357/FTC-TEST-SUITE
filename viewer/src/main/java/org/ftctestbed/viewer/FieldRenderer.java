package org.ftctestbed.viewer;

import org.ftctestbed.sim.Snapshot;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.RobotPieceHandling;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSnapshot;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.Transparency;
import java.awt.image.VolatileImage;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import java.util.List;

/**
 * Draws the field, robot and game pieces from a Snapshot. Shared by the window and the
 * headless PNG export. Field coordinates are meters; +y is up on screen.
 */
public final class FieldRenderer {
    static final double IN = 0.0254;

    public static final Color BACKGROUND = new Color(0x1E2023);
    private static final Color TILE_A = new Color(0x3B3F44);
    private static final Color TILE_B = new Color(0x41454B);
    private static final Color SEAM = new Color(0x2A2C30);
    private static final Color WALL = new Color(0xC9CDD2);
    private static final Color LABEL = new Color(0x8B9096);
    private static final Color ROBOT = new Color(0xF2B33D);
    private static final Color ROBOT_EDGE = new Color(0x7A5410);
    private static final Color FRONT = new Color(0x4FC3F7);
    private static final Color TRAIL = new Color(79, 195, 247, 150);
    private static final Color RAY = new Color(0xFF6B6B);
    private static final Color FORWARD = new Color(0x4CAF50);
    private static final Color BACKWARD = new Color(0xE53935);

    private final FieldConfig field;
    private final RobotConfig robot;

    private double scale;   // pixels per meter
    private double cx, cy;  // screen position of field origin
    private double viewRotation; // screen rotation (rad): a driver's view puts their alliance wall at the bottom
    private double centerX, centerY; // field point (m) at the middle of the view
    private double zoom = 1;          // 1: the whole field fits
    private boolean camera;           // looking at (centerX, centerY) instead of fitting the field
    private int areaW, areaH;         // the size of what the view covers (from its top left corner)
    private final Layers layers = new Layers();

    /**
     * The view: 0 for the classic top view (+x right), or a driver's view, where the driver's
     * alliance wall is at the bottom of the screen and pushing the stick away from you moves the
     * robot up the screen, as at a match.
     */
    public void setViewRotation(double radians) {
        viewRotation = radians;
    }

    public double viewRotation() {
        return viewRotation;
    }

    /**
     * Look at a field point (m) in the middle of the view, zoom times closer than the whole
     * field (the "follow robot" view). The view rotation turns the field about that point.
     */
    public void setCamera(double x, double y, double zoom) {
        centerX = x;
        centerY = y;
        this.zoom = zoom;
        camera = true;
    }

    /** Back to the whole field, centered. */
    public void clearCamera() {
        centerX = centerY = 0;
        zoom = 1;
        camera = false;
    }

    /**
     * The rotation that makes this field heading point up the screen: a driver standing at a wall
     * looking along it sees their wall at the bottom. (Graphics2D turns clockwise for positive
     * angles, y being down, and a heading of 0 starts out pointing right.)
     */
    public static double driverView(double driverForwardHeading) {
        return driverForwardHeading - Math.PI / 2;
    }

    /** g with the view rotation applied: draw field things on this (with sx/sy). */
    public Graphics2D fieldGraphics(Graphics2D g) {
        Graphics2D r = (Graphics2D) g.create();
        if (viewRotation != 0) r.rotate(viewRotation, cx, cy);
        return r;
    }

    /** Text that stays upright in any view, anchored at (x, y): anchor 0 = left, 0.5 = centered. */
    void text(Graphics2D g, String s, double x, double y, double anchor) {
        if (viewRotation == 0) {
            g.drawString(s, (float) (x - g.getFontMetrics().stringWidth(s) * anchor), (float) y);
            return;
        }
        AffineTransform saved = g.getTransform();
        g.rotate(-viewRotation, x, y);
        g.drawString(s, (float) (x - g.getFontMetrics().stringWidth(s) * anchor), (float) y);
        g.setTransform(saved);
    }

    public FieldRenderer(FieldConfig field, RobotConfig robot) {
        this.field = field;
        this.robot = robot;
    }

    /** Fits the field into a w x h area, leaving topInset pixels free above it. */
    public void layout(int w, int h, int topInset) {
        double margin = 34;
        double availH = h - topInset - margin;
        // Leave room beside the field for the alliance areas.
        double width = field.size() + (field.season() != null ? 30 * IN : 0), height = field.size();
        boolean sideways = Math.abs(Math.sin(viewRotation)) > 0.5;
        if (camera) {
            // A camera turns freely, so the scale mustn't depend on the rotation.
            width = height = field.size();
        } else if (sideways) {
            double t = width;
            width = height;
            height = t;
        }
        scale = Math.min((w - 2 * margin) / width, (availH - margin) / height) * (camera ? zoom : 1);
        cx = w / 2.0;
        cy = topInset + availH / 2.0;
        areaW = w;
        areaH = h;
    }

    public void layout(int w, int h) {
        layout(w, h, 0);
    }

    /** As layout(w, h, topInset), in the w x h area whose top left corner is (x, y). */
    public void layout(int x, int y, int w, int h, int topInset) {
        layout(w, h, topInset);
        cx += x;
        cy += y;
        areaW = x + w;
        areaH = y + h;
    }

    public double sx(double x) { return cx + (x - centerX) * scale; }
    public double sy(double y) { return cy - (y - centerY) * scale; }

    public Point2D.Double toField(double px, double py) {
        if (viewRotation != 0) { // undo the view's rotation about the middle of the view
            double c = Math.cos(-viewRotation), s = Math.sin(-viewRotation), dx = px - cx, dy = py - cy;
            px = cx + c * dx - s * dy;
            py = cy + s * dx + c * dy;
        }
        return new Point2D.Double((px - cx) / scale + centerX, (cy - py) / scale + centerY);
    }

    public double scale() { return scale; }

    /**
     * Draw order, bottom to top: tiles, floor markings, pieces on the floor, the robot,
     * field structures above the robot (drawn see-through), then pieces in the air.
     */
    public void paint(Graphics2D g0, Snapshot s, List<double[]> trail) {
        BioBuzzSnapshot bb = s != null && s.season() instanceof BioBuzzSnapshot b ? b : null;
        // On screen, the floor and the trail come from the cached layers; elsewhere they're drawn.
        boolean cached = layers.paint(g0, bb, trail);
        Graphics2D g = fieldGraphics(g0);
        try {
            hints(g);
            if (!cached) {
                paintUnder(g, bb);
                if (trail != null) paintTrail(g, trail);
            }
            paintAbove(g, g0, s, bb, cached);
        } finally {
            g.dispose();
        }
    }

    /** Just the robot in this snapshot (a partner, say), over a field this renderer's layout already drew. */
    public void paintRobotOnly(Graphics2D g0, Snapshot s) {
        Graphics2D g = fieldGraphics(g0);
        try {
            hints(g);
            paintRobot(g, s);
        } finally {
            g.dispose();
        }
    }

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    /** What lies under everything that moves: tiles, labels, tape and zones, fixed obstacles. */
    private void paintUnder(Graphics2D g, BioBuzzSnapshot bb) {
        paintField(g);
        if (bb != null) BioBuzzRenderer.paintFloor(g, this, bb);
        for (FieldConfig.Shape o : field.obstacles()) paintShape(g, o, o.x(), o.y(), o.heading(), 200);
    }

    /**
     * Everything that moves: pieces, sensor rays, the robot, the field's structures over it
     * (from their cached layer when the floor came from one), shots.
     */
    private void paintAbove(Graphics2D g, Graphics2D g0, Snapshot s, BioBuzzSnapshot bb, boolean cached) {
        if (s == null) return;
        for (Snapshot.Piece p : s.pieces()) if (p.state().equals("FLOOR")) paintPiece(g, p);
        for (Snapshot.Ray r : s.rays()) paintRay(g, r);
        paintRobot(g, s);
        if (bb != null && !(cached && layers.paintOverhead(g0, bb))) BioBuzzRenderer.paintOverhead(g, this, bb);
        for (Snapshot.Piece p : s.pieces()) if (p.state().equals("AIRBORNE")) paintFlying(g, p);
    }

    /**
     * Draws what robot code drew on the FTC Dashboard field (or Panels' field): drawing calls
     * in field inches, +y up, center origin. Widths are in screen pixels, as on the dashboard.
     */
    public void paintOverlay(Graphics2D g, List<com.acmerobotics.dashboard.canvas.Canvas.Op> ops) {
        if (ops == null || ops.isEmpty()) return;
        Graphics2D o = fieldGraphics(g);
        o.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        AffineTransform screen = new AffineTransform(scale * IN, 0, 0, -scale * IN, sx(0), sy(0)); // inches to pixels
        double tx = 0, ty = 0, rot = 0, scx = 1, scy = 1;
        Color fill = new Color(0x4FC3F7), stroke = new Color(0x4FC3F7);
        float width = 1, alpha = 1;
        o.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        for (com.acmerobotics.dashboard.canvas.Canvas.Op op : ops) {
            double[] v = op.values;
            AffineTransform user = new AffineTransform(screen);
            user.translate(tx, ty);
            user.rotate(rot);
            user.scale(scx, scy);
            switch (op.kind) {
                case "fill" -> fill = cssColor(op.text, fill);
                case "stroke" -> stroke = cssColor(op.text, stroke);
                case "strokeWidth" -> width = (float) Math.max(0.5, v[0]);
                case "alpha" -> alpha = (float) Math.max(0, Math.min(1, v[0]));
                case "translation" -> { tx = v[0]; ty = v[1]; }
                case "rotation" -> rot = v[0];
                case "scale" -> { scx = v[0]; scy = v[1]; }
                case "strokeCircle", "fillCircle" -> {
                    java.awt.Shape c = user.createTransformedShape(new Ellipse2D.Double(v[0] - v[2], v[1] - v[2], 2 * v[2], 2 * v[2]));
                    drawOverlay(o, c, op.kind.startsWith("fill"), fill, stroke, width, alpha);
                }
                case "strokeRect", "fillRect" -> {
                    java.awt.Shape r = user.createTransformedShape(new Rectangle2D.Double(v[0], v[1], v[2], v[3]));
                    drawOverlay(o, r, op.kind.startsWith("fill"), fill, stroke, width, alpha);
                }
                case "strokeLine" -> drawOverlay(o, user.createTransformedShape(new Line2D.Double(v[0], v[1], v[2], v[3])),
                        false, fill, stroke, width, alpha);
                case "strokePolyline", "strokePolygon", "fillPolygon" -> {
                    if (v.length < 4) break;
                    Path2D.Double path = new Path2D.Double();
                    path.moveTo(v[0], v[1]);
                    for (int i = 2; i + 1 < v.length; i += 2) path.lineTo(v[i], v[i + 1]);
                    if (!op.kind.equals("strokePolyline")) path.closePath();
                    drawOverlay(o, user.createTransformedShape(path), op.kind.startsWith("fill"), fill, stroke, width, alpha);
                }
                case "text" -> {
                    if (op.text == null) break;
                    Point2D p = user.transform(new Point2D.Double(v[0], v[1]), null);
                    o.setColor(withAlpha(fill, alpha));
                    text(o, op.text, p.getX(), p.getY(), 0);
                }
                default -> { }
            }
        }
        o.dispose();
    }

    private static void drawOverlay(Graphics2D g, java.awt.Shape shape, boolean filled, Color fill, Color stroke, float width, float alpha) {
        if (filled) {
            g.setColor(withAlpha(fill, alpha));
            g.fill(shape);
        } else {
            g.setColor(withAlpha(stroke, alpha));
            g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(shape);
        }
    }

    private static Color withAlpha(Color c, float alpha) {
        return alpha >= 1 ? c : new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(c.getAlpha() * alpha));
    }

    /** CSS colors as robot code writes them: "#3F51B5", "#fff", "red", "rgba(255, 0, 0, 0.5)". */
    public static Color cssColor(String css, Color fallback) {
        if (css == null) return fallback;
        String c = css.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            if (c.startsWith("#")) {
                String hex = c.substring(1);
                if (hex.length() == 3 || hex.length() == 4) {
                    StringBuilder b = new StringBuilder();
                    for (char ch : hex.toCharArray()) b.append(ch).append(ch);
                    hex = b.toString();
                }
                int rgb = (int) Long.parseLong(hex.substring(0, 6), 16);
                int a = hex.length() >= 8 ? Integer.parseInt(hex.substring(6, 8), 16) : 255;
                return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, a);
            }
            if (c.startsWith("rgb")) {
                String[] parts = c.substring(c.indexOf('(') + 1, c.indexOf(')')).split(",");
                int a = parts.length > 3 ? (int) Math.round(Double.parseDouble(parts[3].trim()) * 255) : 255;
                return new Color(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()), a);
            }
        } catch (RuntimeException e) {
            return fallback;
        }
        return switch (c) {
            case "transparent" -> new Color(0, 0, 0, 0);
            case "black" -> Color.BLACK;
            case "white" -> Color.WHITE;
            case "red" -> new Color(0xF44336);
            case "green" -> new Color(0x4CAF50);
            case "blue" -> new Color(0x2196F3);
            case "yellow" -> new Color(0xFFEB3B);
            case "orange" -> new Color(0xFF9800);
            case "purple" -> new Color(0x9C27B0);
            case "cyan" -> new Color(0x00BCD4);
            case "magenta", "fuchsia" -> new Color(0xE040FB);
            case "gray", "grey" -> new Color(0x9E9E9E);
            default -> fallback;
        };
    }

    void paintPiece(Graphics2D g, Snapshot.Piece p) {
        Color c = new Color(p.color());
        if (p.ball()) {
            double r = p.radius() * scale;
            Ellipse2D e = new Ellipse2D.Double(sx(p.x()) - r, sy(p.y()) - r, 2 * r, 2 * r);
            g.setColor(c);
            g.fill(e);
            g.setColor(c.darker().darker());
            g.setStroke(new BasicStroke(1f));
            g.draw(e);
            return;
        }
        AffineTransform saved = g.getTransform();
        g.translate(sx(p.x()), sy(p.y()));
        g.rotate(-p.heading());
        Rectangle2D r = new Rectangle2D.Double(-p.width() / 2 * scale, -p.height() / 2 * scale, p.width() * scale, p.height() * scale);
        g.setColor(c);
        g.fill(r);
        g.setColor(c.darker());
        g.draw(r);
        g.setTransform(saved);
    }

    /** A flying ball: a shadow on the floor, and the ball drawn larger the higher it is. */
    private void paintFlying(Graphics2D g, Snapshot.Piece p) {
        double r = p.radius() * scale;
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new Ellipse2D.Double(sx(p.x()) - r, sy(p.y()) - r * 0.7, 2 * r, 1.4 * r));
        double grow = 1 + Math.max(0, p.z()) / 1.2;
        double rr = r * grow;
        double lift = Math.max(0, p.z()) * scale * 0.25; // drawn slightly "up" the screen to read as height
        Ellipse2D e = new Ellipse2D.Double(sx(p.x()) - rr, sy(p.y()) - lift - rr, 2 * rr, 2 * rr);
        g.setColor(new Color(p.color()));
        g.fill(e);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(e);
    }

    private void paintField(Graphics2D g) {
        double half = field.size() / 2;
        int tiles = (int) Math.round(field.size() / field.tile());
        double t = field.size() / tiles;
        for (int i = 0; i < tiles; i++) {
            for (int j = 0; j < tiles; j++) {
                g.setColor(((i + j) & 1) == 0 ? TILE_A : TILE_B);
                g.fill(new Rectangle2D.Double(sx(-half + i * t), sy(-half + (j + 1) * t), t * scale, t * scale));
            }
        }
        g.setColor(SEAM);
        g.setStroke(new BasicStroke(1f));
        for (int i = 1; i < tiles; i++) {
            g.draw(new Line2D.Double(sx(-half + i * t), sy(half), sx(-half + i * t), sy(-half)));
            g.draw(new Line2D.Double(sx(-half), sy(-half + i * t), sx(half), sy(-half + i * t)));
        }
        g.setColor(WALL);
        g.setStroke(new BasicStroke(4f));
        g.draw(new Rectangle2D.Double(sx(-half), sy(half), field.size() * scale, field.size() * scale));

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        g.setColor(LABEL);
        for (int i = 0; i <= tiles; i++) {
            double v = -half + i * t;
            String label = String.valueOf((int) Math.round(v / IN));
            text(g, label, sx(v), sy(-half) + 16, 0.5);
            text(g, label, sx(-half) - 8, sy(v) + 4, 1);
        }
        text(g, viewRotation == 0 ? "+x →" : "+x", sx(half) - 12, sy(-half) + 27, 0);
        text(g, viewRotation == 0 ? "+y ↑" : "+y", sx(-half) - 26, sy(half) - 12, 0);
    }

    private void paintShape(Graphics2D g, FieldConfig.Shape shape, double x, double y, double heading, int alpha) {
        AffineTransform saved = g.getTransform();
        g.translate(sx(x), sy(y));
        g.rotate(-heading);
        Color c = new Color(shape.color());
        Color fill = new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
        java.awt.Shape s = switch (shape.shape()) {
            case "circle" -> new Ellipse2D.Double(-shape.diameter() / 2 * scale, -shape.diameter() / 2 * scale,
                    shape.diameter() * scale, shape.diameter() * scale);
            case "polygon" -> {
                Path2D.Double p = new Path2D.Double();
                List<double[]> pts = shape.points();
                for (int i = 0; i < pts.size(); i++) {
                    double px = (pts.get(i)[0] - x) * scale, py = -(pts.get(i)[1] - y) * scale;
                    if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
                }
                p.closePath();
                yield p;
            }
            default -> new Rectangle2D.Double(-shape.width() / 2 * scale, -shape.height() / 2 * scale,
                    shape.width() * scale, shape.height() * scale);
        };
        g.setColor(fill);
        g.fill(s);
        g.setColor(c.darker());
        g.setStroke(new BasicStroke(1.5f));
        g.draw(s);
        g.setTransform(saved);
    }

    /**
     * The window's 2D view repaints 60 times a second, but most of it doesn't change: the floor
     * (redrawn only when the view moves or resizes, or a human player's NECTAR count changes),
     * the trail (only its newest bit is added) and the HIVES and FLOWERS over the robot (redrawn
     * when one of them changes). Both are kept on the graphics card and
     * copied in each frame. Images (the browser view, PNG export) and a view that keeps moving
     * (Follow robot) are drawn directly.
     */
    private final class Layers {
        private VolatileImage under, trail, overhead;
        private int floorVersion, overheadVersion = -1, overheadState;
        private List<Object> key;
        private boolean moving;
        private int trailDrawn;
        private double[] trailFirst;

        /** Draws the floor and the trail from the layers; false if they have to be drawn directly. */
        boolean paint(Graphics2D g0, BioBuzzSnapshot bb, List<double[]> points) {
            GraphicsConfiguration gc = g0.getDeviceConfiguration();
            if (gc == null || gc.getDevice().getType() != GraphicsDevice.TYPE_RASTER_SCREEN || areaW <= 0 || areaH <= 0) return false;
            List<Object> k = Arrays.asList(gc, areaW, areaH, cx, cy, scale, centerX, centerY, viewRotation,
                    bb == null ? null : bb.humanPlayerNectar(), bb == null ? null : bb.humanPlayerAllowed());
            boolean redraw = !k.equals(key);
            if (redraw) {
                key = k;
                boolean wasMoving = moving;
                moving = true;
                if (wasMoving) {
                    trailDrawn = 0; // the trail layer is out of date too
                    return false; // the view moves every frame (Follow robot): caching would only add work
                }
            } else {
                moving = false;
            }
            int state = check(under, gc);
            if (state == VolatileImage.IMAGE_INCOMPATIBLE) under = gc.createCompatibleVolatileImage(areaW, areaH, Transparency.OPAQUE);
            if (state != VolatileImage.IMAGE_OK) redraw = true;
            if (redraw) {
                floorVersion++; // the overhead layer is out of date too
                Graphics2D ug = under.createGraphics();
                ug.setColor(BACKGROUND);
                ug.fillRect(0, 0, areaW, areaH);
                Graphics2D fg = fieldGraphics(ug);
                hints(fg);
                paintUnder(fg, bb);
                fg.dispose();
                ug.dispose();
            }
            if (under.contentsLost()) return false;
            g0.drawImage(under, 0, 0, null);
            if (points == null || points.size() < 2) {
                trailDrawn = 0;
                return true;
            }
            boolean full = redraw || points.size() < trailDrawn || points.get(0) != trailFirst;
            state = check(trail, gc);
            if (state == VolatileImage.IMAGE_INCOMPATIBLE) trail = gc.createCompatibleVolatileImage(areaW, areaH, Transparency.TRANSLUCENT);
            if (state != VolatileImage.IMAGE_OK) full = true;
            Graphics2D tg = trail.createGraphics();
            if (full) {
                tg.setComposite(AlphaComposite.Clear);
                tg.fillRect(0, 0, areaW, areaH);
                tg.setComposite(AlphaComposite.SrcOver);
            }
            int from = full ? 0 : trailDrawn - 1;
            if (points.size() - from >= 2) {
                // Opaque in the layer (overlapping segments don't darken), see-through when copied in.
                Graphics2D fg = fieldGraphics(tg);
                hints(fg);
                strokeTrail(fg, points.subList(from, points.size()), new Color(TRAIL.getRGB() | 0xFF000000));
                fg.dispose();
            }
            tg.dispose();
            trailDrawn = points.size();
            trailFirst = points.get(0);
            if (trail.contentsLost()) {
                trailDrawn = 0;
                return true;
            }
            Graphics2D cg = (Graphics2D) g0.create();
            cg.setComposite(AlphaComposite.SrcOver.derive(TRAIL.getAlpha() / 255f));
            cg.drawImage(trail, 0, 0, null);
            cg.dispose();
            return true;
        }

        /**
         * The HIVES, their frame and the FLOWERS (drawn over the robot), from a layer redrawn
         * only when one of them changes (a HIVE tips or fills, a FLOWER gets a piece). Only
         * after paint() drew the floor from its layer this frame; false to draw them directly.
         */
        boolean paintOverhead(Graphics2D g0, BioBuzzSnapshot bb) {
            GraphicsConfiguration gc = g0.getDeviceConfiguration();
            int now = overheadState(bb), state = check(overhead, gc);
            if (state == VolatileImage.IMAGE_INCOMPATIBLE) overhead = gc.createCompatibleVolatileImage(areaW, areaH, Transparency.TRANSLUCENT);
            if (state != VolatileImage.IMAGE_OK || now != overheadState || overheadVersion != floorVersion) {
                Graphics2D og = overhead.createGraphics();
                og.setComposite(AlphaComposite.Clear);
                og.fillRect(0, 0, areaW, areaH);
                og.setComposite(AlphaComposite.SrcOver);
                Graphics2D fg = fieldGraphics(og);
                hints(fg);
                BioBuzzRenderer.paintOverhead(fg, FieldRenderer.this, bb);
                fg.dispose();
                og.dispose();
                overheadState = now;
                overheadVersion = floorVersion;
            }
            if (overhead.contentsLost()) {
                overheadVersion = -1;
                return false;
            }
            g0.drawImage(overhead, 0, 0, null);
            return true;
        }

        /** A fingerprint of everything paintOverhead draws (the HIVES' contents are arrays, so records don't compare them). */
        private int overheadState(BioBuzzSnapshot bb) {
            int h = 31 * Boolean.hashCode(bb.flowersUnlocked()) + bb.flowers().hashCode();
            for (BioBuzzSnapshot.HiveState v : bb.hives()) {
                h = 31 * h + java.util.Objects.hash(v.alliance(), v.x(), v.upSide(), v.tiltDegrees(), v.tipping(), v.contents(),
                        v.tipsAuto(), v.tipsTeleop(), v.fill());
                h = 31 * h + Arrays.deepHashCode(v.contentsAt().toArray());
            }
            return h;
        }

        /** IMAGE_OK, IMAGE_RESTORED (its contents are gone) or IMAGE_INCOMPATIBLE (make a new one: missing, resized, or another screen). */
        private int check(VolatileImage img, GraphicsConfiguration gc) {
            if (img == null || img.getWidth() != areaW || img.getHeight() != areaH) return VolatileImage.IMAGE_INCOMPATIBLE;
            return img.validate(gc);
        }
    }

    private void paintTrail(Graphics2D g, List<double[]> trail) {
        if (trail.size() < 2) return;
        strokeTrail(g, trail, TRAIL);
    }

    private void strokeTrail(Graphics2D g, List<double[]> trail, Color color) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(sx(trail.get(0)[0]), sy(trail.get(0)[1]));
        for (int i = 1; i < trail.size(); i++) p.lineTo(sx(trail.get(i)[0]), sy(trail.get(i)[1]));
        g.setColor(color);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(p);
    }

    private void paintRay(Graphics2D g, Snapshot.Ray r) {
        Stroke dashed = new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{5f, 4f}, 0f);
        g.setStroke(dashed);
        g.setColor(new Color(RAY.getRed(), RAY.getGreen(), RAY.getBlue(), r.hit() ? 220 : 90));
        g.draw(new Line2D.Double(sx(r.x0()), sy(r.y0()), sx(r.x1()), sy(r.y1())));
        if (r.hit()) g.fill(new Ellipse2D.Double(sx(r.x1()) - 3, sy(r.y1()) - 3, 6, 6));
    }

    private void paintRobot(Graphics2D g, Snapshot s) {
        AffineTransform saved = g.getTransform();
        g.translate(sx(s.x()), sy(s.y()));
        g.rotate(-s.heading());
        // Now drawing in robot frame scaled to pixels: +x forward (screen right), +y left (screen up).
        double l = robot.length() * scale, w = robot.width() * scale;
        // The frame, whose middle may be offset from the robot's center (robot y is screen up: minus).
        Rectangle2D body = new Rectangle2D.Double(robot.frameOffsetX() * scale - l / 2, -robot.frameOffsetY() * scale - w / 2, l, w);
        g.setColor(new Color(ROBOT.getRed(), ROBOT.getGreen(), ROBOT.getBlue(), 215));
        g.fill(body);

        RobotConfig.Drivetrain dt = robot.drivetrain();
        double wheelL = dt.wheelRadius() * 2 * scale, wheelW = 1.5 * IN * scale;
        for (Snapshot.Wheel wh : s.wheels()) {
            double px = wh.x() * scale, py = -wh.y() * scale;
            Rectangle2D r = new Rectangle2D.Double(px - wheelL / 2, py - wheelW / 2, wheelL, wheelW);
            double d = Math.max(-1, Math.min(1, wh.duty()));
            Color base = d >= 0 ? FORWARD : BACKWARD;
            int a = (int) Math.round(60 + 195 * Math.abs(d));
            g.setColor(new Color(0x26, 0x28, 0x2B));
            g.fill(r);
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), a));
            g.fill(r);
            // Roller direction hint
            g.setColor(new Color(0, 0, 0, 90));
            g.setStroke(new BasicStroke(1f));
            boolean slashUp = wh.label().equals("FL") || wh.label().equals("BR");
            for (int k = -2; k <= 2; k++) {
                double ox = px + k * wheelL / 5;
                g.draw(new Line2D.Double(ox - wheelW / 2, py + (slashUp ? wheelW / 2 : -wheelW / 2),
                        ox + wheelW / 2, py + (slashUp ? -wheelW / 2 : wheelW / 2)));
            }
            if (wh.slipping()) {
                g.setColor(new Color(0xFF9800));
                g.setStroke(new BasicStroke(2f));
                g.draw(r);
            }
            if (wh.lifting()) {
                // Off the tiles: drawn hollow with a red outline.
                g.setColor(new Color(0x26, 0x28, 0x2B, 200));
                g.fill(r);
                g.setColor(new Color(0xE53935));
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{3f, 2f}, 0f));
                g.draw(r);
            }
        }

        RobotPieceHandling.Parts parts = s.parts();
        if (parts != null) paintParts(g, parts);

        g.setColor(ROBOT_EDGE);
        g.setStroke(new BasicStroke(2f));
        g.draw(body);
        g.setColor(FRONT);
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(l / 2 - 2, -w / 2 + 4, l / 2 - 2, w / 2 - 4));
        // Heading arrow
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0x20, 0x22, 0x25));
        double tip = l * 0.32;
        g.draw(new Line2D.Double(0, 0, tip, 0));
        Path2D.Double head = new Path2D.Double();
        head.moveTo(tip + 6, 0);
        head.lineTo(tip - 4, -6);
        head.lineTo(tip - 4, 6);
        head.closePath();
        g.fill(head);
        g.fill(new Ellipse2D.Double(-3, -3, 6, 6));
        g.setTransform(saved);
    }

    /** Robot-frame drawing of the intake zone, hopper, launcher and claws (called inside paintRobot). */
    private void paintParts(Graphics2D g, RobotPieceHandling.Parts parts) {
        if (parts.intakeZone() != null) {
            double[] z = parts.intakeZone();
            Rectangle2D r = new Rectangle2D.Double(z[0] * scale, -z[3] * scale, (z[1] - z[0]) * scale, (z[3] - z[2]) * scale);
            Color c = parts.intakeDirection() > 0 ? new Color(76, 175, 80, 150)
                    : parts.intakeDirection() < 0 ? new Color(255, 152, 0, 150) : new Color(255, 255, 255, 40);
            g.setColor(c);
            g.fill(r);
        }
        // Hopper: up to 4 balls in a 2x2 grid; the staged one (next to fire) is outlined.
        List<Integer> hopper = parts.hopperColors();
        double d = 2.6 * IN * scale;
        for (int i = 0; i < hopper.size(); i++) {
            double hx = (i < 2 ? 1 : -1) * d * 0.6 - d / 2, hy = (i % 2 == 0 ? -1 : 1) * d * 0.6 - d / 2;
            Ellipse2D e = new Ellipse2D.Double(hx, hy, d, d);
            g.setColor(new Color(hopper.get(i)));
            g.fill(e);
            g.setColor(i == 0 && parts.staged() ? Color.WHITE : new Color(0, 0, 0, 120));
            g.setStroke(new BasicStroke(i == 0 && parts.staged() ? 2f : 1f));
            g.draw(e);
        }
        if (parts.launcherPoint() != null) {
            double lx = parts.launcherPoint()[0] * scale, ly = -parts.launcherPoint()[1] * scale;
            double spin = Math.min(1, parts.flywheelRpm() / 3000);
            g.setColor(new Color(229, 57, 53, (int) (80 + 175 * spin)));
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(lx + 4 * IN * scale, ly - 3 * IN * scale, lx + 4 * IN * scale, ly + 3 * IN * scale));
        }
        for (RobotPieceHandling.ClawState c : parts.claws()) {
            double cx = c.x() * scale, cy = -c.y() * scale, half = 1.6 * IN * scale;
            if (c.heldColor() >= 0) {
                g.setColor(new Color(c.heldColor()));
                g.fill(new Ellipse2D.Double(cx - half, cy - half, 2 * half, 2 * half));
            }
            g.setColor(c.closed() ? new Color(0x37474F) : new Color(0x90A4AE));
            g.setStroke(new BasicStroke(2.5f));
            g.draw(new Line2D.Double(cx - half, cy - half * 1.1, cx + half * 0.4, cy - half * (c.closed() ? 0.5 : 1.1)));
            g.draw(new Line2D.Double(cx - half, cy + half * 1.1, cx + half * 0.4, cy + half * (c.closed() ? 0.5 : 1.1)));
            if (c.height() > 6 * IN) {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
                g.setColor(Color.WHITE);
                text(g, String.format("%.0f\"", c.height() / IN), cx - half * 2.6, cy + 3, 0);
            }
        }
    }
}
