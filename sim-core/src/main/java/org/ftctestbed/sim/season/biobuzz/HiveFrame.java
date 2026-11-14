package org.ftctestbed.sim.season.biobuzz;

import org.ftctestbed.sim.game.Contact;
import org.ftctestbed.sim.game.FlightObstacle;
import org.ftctestbed.sim.game.GamePiece;

import java.util.ArrayList;
import java.util.List;

import static org.ftctestbed.sim.season.biobuzz.BioBuzzField.*;

/**
 * The HIVE structure's fixed frame (CAD am-5874..5879) as flying pieces see it: four leaning
 * legs and the top bar (tubes), the two ACM panels below the pivots and the feet. A shot that
 * clips a leg on its way to a CELL bounces off it.
 */
final class HiveFrame implements FlightObstacle {
    private record Tube(double[] a, double[] b, double radius) {}

    private record Box(double[] lo, double[] hi) {}

    private final List<Tube> tubes = new ArrayList<>();
    private final List<Box> boxes = new ArrayList<>();

    HiveFrame() {
        for (int sx : new int[]{-1, 1}) {
            for (int sy : new int[]{-1, 1}) {
                tubes.add(new Tube(new double[]{sx * LEG_FOOT[0], sy * LEG_FOOT[1], 0},
                        new double[]{sx * LEG_TOP[0], sy * LEG_TOP[1], LEG_TOP[2]}, LEG_THICKNESS / 2));
            }
            double fx = sx * (FRAME_HALF_WIDTH - FRAME_FOOT_THICKNESS / 2);
            boxes.add(box(fx, 0, FRAME_FOOT_THICKNESS, 2 * FRAME_HALF_DEPTH, 0, 2.15 * IN));
            double ay = (FOOT_A[2] + FOOT_A[3]) / 2 * sx; // blue's at the far end, red's at the audience end
            boxes.add(box(sx * (FOOT_A[0] + FOOT_A[1]) / 2, ay, FOOT_A[1] - FOOT_A[0], FOOT_A[3] - FOOT_A[2], 0, 2.5 * IN));
            // ACM panels (am-5877): 30.2 in wide, 34-40 in up, 1.55-4.28 in either side of the pivot line.
            boxes.add(box(0, sx * 2.9 * IN, 30.16 * IN, 2.73 * IN, 34.07 * IN, 40.02 * IN));
        }
        tubes.add(new Tube(new double[]{-12 * IN, 0, 41.45 * IN}, new double[]{12 * IN, 0, 41.45 * IN}, 0.5 * IN));
    }

    private static Box box(double cx, double cy, double w, double d, double z0, double z1) {
        return new Box(new double[]{cx - w / 2, cy - d / 2, z0}, new double[]{cx + w / 2, cy + d / 2, z1});
    }

    @Override
    public void collide(GamePiece p, double x0, double y0, double z0) {
        double r = p.kind.radius();
        double e = Math.min(p.kind.restitution(), 0.5); // aluminum tube and sheet metal: lively
        double[] q = {p.x(), p.y(), p.z()};
        for (Tube t : tubes) {
            double[] ab = {t.b[0] - t.a[0], t.b[1] - t.a[1], t.b[2] - t.a[2]};
            double len2 = ab[0] * ab[0] + ab[1] * ab[1] + ab[2] * ab[2];
            double s = Math.max(0, Math.min(1, ((q[0] - t.a[0]) * ab[0] + (q[1] - t.a[1]) * ab[1] + (q[2] - t.a[2]) * ab[2]) / len2));
            double[] d = {q[0] - t.a[0] - s * ab[0], q[1] - t.a[1] - s * ab[1], q[2] - t.a[2] - s * ab[2]};
            double dist = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            if (dist >= r + t.radius || dist < 1e-9) continue;
            Contact.resolve(p, new double[]{d[0] / dist, d[1] / dist, d[2] / dist}, r + t.radius - dist, e, null);
            return;
        }
        for (Box b : boxes) {
            double[] lo = {b.lo[0] - r, b.lo[1] - r, b.lo[2] - r}, hi = {b.hi[0] + r, b.hi[1] + r, b.hi[2] + r};
            boolean in = true;
            for (int i = 0; i < 3; i++) in &= q[i] > lo[i] && q[i] < hi[i];
            if (!in) continue;
            double[] a = {x0, y0, z0};
            int k = 0;
            double sign = 1, best = Double.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                // Prefer the face it came through; else the nearest.
                if (a[i] <= lo[i]) { k = i; sign = -1; best = -1; break; }
                if (a[i] >= hi[i]) { k = i; sign = 1; best = -1; break; }
                if (q[i] - lo[i] < best) { best = q[i] - lo[i]; k = i; sign = -1; }
                if (hi[i] - q[i] < best) { best = hi[i] - q[i]; k = i; sign = 1; }
            }
            double[] n = {0, 0, 0};
            n[k] = sign;
            Contact.resolve(p, n, sign > 0 ? hi[k] - q[k] : q[k] - lo[k], e, null);
            return;
        }
    }
}
