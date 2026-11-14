import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Draws the FTC Test Suite app icon: a white gear with an orange check mark on an orange
 * rounded square, laid out on Apple's 1024 px icon grid (824 px body, soft shadow).
 *
 *   java packaging/macos/IconGen.java <iconset dir> [png 512] [png 1024]
 */
public class IconGen {
    static final Color TOP = new Color(0xFFA64D), BOTTOM = new Color(0xEE5A12), CHECK = new Color(0xF2701F);

    public static void main(String[] args) throws Exception {
        File set = new File(args[0]);
        set.mkdirs();
        for (int s : new int[]{16, 32, 128, 256, 512}) {
            ImageIO.write(render(s), "png", new File(set, "icon_" + s + "x" + s + ".png"));
            ImageIO.write(render(s * 2), "png", new File(set, "icon_" + s + "x" + s + "@2x.png"));
        }
        if (args.length > 1) ImageIO.write(render(512), "png", new File(args[1]));
        if (args.length > 2) ImageIO.write(render(1024), "png", new File(args[2]));
    }

    static BufferedImage render(int size) {
        double k = size / 1024.0;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        hints(g);
        Shape body = squircle(100, 100, 824);

        // Soft shadow under the icon (skipped where it would just be a smudge).
        if (size >= 64) {
            BufferedImage sh = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D s = sh.createGraphics();
            hints(s);
            s.scale(k, k);
            s.translate(0, 14);
            s.setColor(Color.BLACK);
            s.fill(body);
            s.dispose();
            g.drawImage(blur(sh, Math.max(1, (int) Math.round(20 * k)), new Color(0, 0, 0, 90)), 0, 0, null);
        }

        g.scale(k, k);
        g.setClip(body);
        g.setPaint(new GradientPaint(0, 100, TOP, 0, 924, BOTTOM));
        g.fill(body);
        // A gentle sheen across the top half.
        g.setPaint(new GradientPaint(0, 100, new Color(255, 255, 255, 46), 0, 560, new Color(255, 255, 255, 0)));
        g.fill(body);

        Shape gear = gear(512, 512);
        if (size >= 64) {
            BufferedImage gs = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D s = gs.createGraphics();
            hints(s);
            s.scale(k, k);
            s.translate(0, 12);
            s.setColor(Color.BLACK);
            s.fill(gear);
            s.dispose();
            AffineTransform t = g.getTransform();
            g.setTransform(new AffineTransform());
            g.drawImage(blur(gs, Math.max(1, (int) Math.round(14 * k)), new Color(120, 40, 0, 70)), 0, 0, null);
            g.setTransform(t);
        }
        g.setPaint(new GradientPaint(0, 200, Color.WHITE, 0, 824, new Color(0xF4F1EE)));
        g.fill(gear);

        // The check mark.
        Path2D check = new Path2D.Double();
        check.moveTo(512 - 98, 512 + 6);
        check.lineTo(512 - 30, 512 + 74);
        check.lineTo(512 + 104, 512 - 72);
        g.setPaint(CHECK);
        g.setStroke(new BasicStroke(size < 64 ? 84 : 68, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(check);
        g.dispose();
        return img;
    }

    /** Apple-style rounded square: a superellipse (n = 5), so the corners curve smoothly into the sides. */
    static Shape squircle(double x, double y, double size) {
        Path2D p = new Path2D.Double();
        double a = size / 2, cx = x + a, cy = y + a, n = 5;
        for (int i = 0; i <= 720; i++) {
            double t = 2 * Math.PI * i / 720, c = Math.cos(t), s = Math.sin(t);
            double px = cx + a * Math.signum(c) * Math.pow(Math.abs(c), 2 / n);
            double py = cy + a * Math.signum(s) * Math.pow(Math.abs(s), 2 / n);
            if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
        }
        p.closePath();
        return p;
    }

    /** An 8-tooth gear with rounded teeth. */
    static Shape gear(double cx, double cy) {
        Area a = new Area(new Ellipse2D.Double(cx - 236, cy - 236, 472, 472));
        for (int i = 0; i < 8; i++) {
            Shape tooth = new RoundRectangle2D.Double(cx - 62, cy - 318, 124, 150, 52, 52);
            a.add(new Area(AffineTransform.getRotateInstance(Math.toRadians(i * 45 + 22.5), cx, cy).createTransformedShape(tooth)));
        }
        return a;
    }

    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
    }

    /** A soft shadow: the shape's opacity blurred (three box passes, about a Gaussian of radius r), in one color. */
    static BufferedImage blur(BufferedImage src, int r, Color color) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        double[] alpha = new double[w * h];
        for (int i = 0; i < px.length; i++) alpha[i] = (px[i] >>> 24) / 255.0;
        for (int pass = 0; pass < 3; pass++) {
            boxH(alpha, w, h, r);
            boxV(alpha, w, h, r);
        }
        int rgb = color.getRGB() & 0xFFFFFF;
        for (int i = 0; i < px.length; i++) {
            int A = (int) Math.round(Math.max(0, Math.min(1, alpha[i])) * color.getAlpha());
            px[i] = (A << 24) | rgb;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }

    static void boxH(double[] c, int w, int h, int r) {
        double[] row = new double[w];
        for (int y = 0; y < h; y++) {
            double sum = 0;
            for (int x = -r; x <= r; x++) sum += c[y * w + Math.max(0, Math.min(w - 1, x))];
            for (int x = 0; x < w; x++) {
                row[x] = sum / (2 * r + 1);
                sum += c[y * w + Math.min(w - 1, x + r + 1)] - c[y * w + Math.max(0, x - r)];
            }
            System.arraycopy(row, 0, c, y * w, w);
        }
    }

    static void boxV(double[] c, int w, int h, int r) {
        double[] col = new double[h];
        for (int x = 0; x < w; x++) {
            double sum = 0;
            for (int y = -r; y <= r; y++) sum += c[Math.max(0, Math.min(h - 1, y)) * w + x];
            for (int y = 0; y < h; y++) {
                col[y] = sum / (2 * r + 1);
                sum += c[Math.min(h - 1, y + r + 1) * w + x] - c[Math.max(0, y - r) * w + x];
            }
            for (int y = 0; y < h; y++) c[y * w + x] = col[y];
        }
    }
}
