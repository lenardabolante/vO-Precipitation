package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/**
 * Phase O (OW5): the damage a wrecked building leaves on the street beside it: potholes, cracks, patches of torn-up
 * asphalt and a spill of small rubble. A <b>pure function of the objects' damage</b> ({@link Wreck#level}, at its step
 * of the ladder) and the road spans: nothing is saved, a reload shows the same wear, and a road the tornado crossed
 * where no building stands gets none (the wire does not say where it went). Never on a highway.
 * <p>
 * Baked into the ground tile right after {@code TerrainPainter.paintRoads}; everything is clipped to the street's own
 * strip ({@link #LIFT} above the terrain line to {@link #DEPTH} below it).
 */
final class RoadWear {
    private RoadWear() {}

    // ------------------------------------------------------------------ the model table's constants (PLAN.md, Phase O)
    /** The wear of a building reaches this far beyond its sides along the road (px), fading out smoothly. */
    static final double REACH = 36;
    /** The street's strip, as TerrainPainter.paintRoads paints it: from 1.8 px above the terrain line to 5.6 px below. */
    static final double LIFT = 1.8, DEPTH = 5.6;
    /** Potholes: {@code round(1 + 5 (d - 0.3) / 0.7 x w / 50)}, 5-13 px wide, 2 px deep. */
    static final double HOLE_W0 = 5, HOLE_W1 = 13, HOLE_DEEP = 2;
    /** Cracks 14-40 px long; torn patches 10-30 px wide. */
    static final double CRACK_L0 = 14, CRACK_L1 = 40, TORN_W0 = 10, TORN_W1 = 30;

    private static double unit(long seed, int j, int salt) { return Scene.hash01(seed * 0xC2B2AE3D27D4EB4FL + j * 0x9E3779B97F4A7C15L + salt * 0x165667B19E3779F9L + 0x1D); }

    static int potholes(Model.Obj o, double d) { return (int) Math.round(1 + 5 * (d - Rubble.D_MIN) / (1 - Rubble.D_MIN) * o.w / 50); }

    static int cracks(Model.Obj o, double d) { return (int) Math.round(1 + 2 * d * o.w / 50); }

    static int torn(Model.Obj o, double d) { return d < 0.5 ? 0 : (int) Math.round(1 + 2 * (d - 0.5) / 0.5 * o.w / 50); }

    static int spill(Model.Obj o, double d) { return (int) Math.round(6 + 14 * d * o.w / 50); }

    /** How strongly the road at x is worn by building {@code o} at damage {@code d}: d beside it, fading over {@link #REACH}. */
    static double strength(Model.Obj o, double d, double x) { return d * Lit.smooth(1 - (Math.abs(x - o.x) - o.w / 2) / REACH); }

    /** World x of item {@code j} of one kind (the same at every damage: wear is added, it never moves). */
    private static double itemX(Model.Obj o, int j, int salt) { return o.x + (unit(o.seed, j, salt) * 2 - 1) * (o.w / 2 + REACH); }

    /** Paint the wear of every street span that reaches into [x0, x1]. Draws nothing (and changes nothing of {@code g}) where no building is wrecked. */
    static void paint(Graphics2D g, Scene sc, double x0, double x1, double scale) {
        if (sc.hello.roads.isEmpty()) return;
        Shape keep = null;
        boolean any = false;
        for (Model.Obj o : sc.objects) {
            if (!o.building || o.damage <= 0 || !Rubble.has(o)) continue;
            final double half = o.w / 2 + REACH;
            if (o.x + half + 22 < x0 || o.x - half - 22 > x1) continue;
            final double d = Wreck.k(Wreck.level(o)) / 24.0;
            for (Model.Road r : sc.hello.roads) {
                if (r.highway() || r.x1 < o.x - half || r.x0 > o.x + half) continue;
                final double a = Math.max(r.x0, o.x - half), b = Math.min(r.x1, o.x + half);
                if (b - a < 4) continue;
                if (!any) { any = true; keep = g.getClip(); }
                g.setClip(keep);
                g.clip(strip(sc.terrain, a, b));
                wear(g, sc, o, d, a, b, scale);
            }
        }
        if (any) g.setClip(keep);
    }

    /**
     * The parts of [a, b] that are not on a street span, left to right (the whole of it when there is no street): where a
     * tornado's scar is still drawn. On a street the road shows its wear instead.
     */
    static java.util.List<double[]> offStreet(Model.Hello h, double a, double b) {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        if (h == null || h.roads.isEmpty()) { out.add(new double[]{a, b}); return out; }
        java.util.List<Model.Road> streets = new java.util.ArrayList<>();
        for (Model.Road r : h.roads) if (!r.highway() && r.x1 > a && r.x0 < b) streets.add(r);
        streets.sort((p, q) -> Double.compare(p.x0, q.x0));
        double from = a;
        for (Model.Road r : streets) {
            if (r.x0 > from) out.add(new double[]{from, Math.min(b, r.x0)});
            from = Math.max(from, r.x1);
        }
        if (from < b) out.add(new double[]{from, b});
        return out;
    }

    /** The street's strip over [a, b] as a shape (everything is clipped to it). */
    static Path2D.Double strip(Model.Terrain t, double a, double b) {
        Path2D.Double p = new Path2D.Double();
        final double step = t.step;
        p.moveTo(a, t.at(a) - LIFT);
        for (double x = Math.floor(a / step) * step + step; x < b; x += step) p.lineTo(x, t.at(x) - LIFT);
        p.lineTo(b, t.at(b) - LIFT);
        p.lineTo(b, t.at(b) + DEPTH);
        for (double x = Math.ceil(b / step) * step - step; x > a; x -= step) p.lineTo(x, t.at(x) + DEPTH);
        p.lineTo(a, t.at(a) + DEPTH);
        p.closePath();
        return p;
    }

    private static void wear(Graphics2D g, Scene sc, Model.Obj o, double d, double a, double b, double scale) {
        final Model.Terrain t = sc.terrain;
        final Lit lit = sc.lit;
        // the asphalt as paintRoads colours it here (wet darkens it, snow whitens it); snow also hides the wear
        final double wet = Math.round(sc.ground.wetAt(o.x) * 5) / 5.0, snow = Math.round(Math.min(1, sc.snowAt(o.x, t.at(o.x))) * 4) / 4.0;
        Color asphalt = Lit.mix(new Color(72, 74, 80), new Color(40, 44, 54), wet * 0.85);
        asphalt = Lit.mix(asphalt, new Color(206, 212, 222), snow * 0.72);
        final int vis = (int) Math.round(255 * (1 - 0.85 * snow));
        if (vis <= 8) return;
        final Color dark = Lit.alpha(lit.lit(Lit.mul(asphalt, 0.5)), vis), rim = Lit.alpha(lit.lit(Lit.mix(asphalt, new Color(150, 146, 138), 0.55)), vis * 3 / 4);
        final Color dirt = Lit.alpha(lit.lit(Lit.mix(new Color(116, 100, 80), new Color(70, 60, 50), wet * 0.6)), vis);
        // ---- torn patches: the asphalt is gone, the bed of gravel and earth shows (under the cracks and holes)
        int nt = torn(o, d);
        for (int j = 0; j < nt; j++) {
            double x = itemX(o, j, 21), s = strength(o, d, x);
            if (x < a + 2 || x > b - 2 || s < 0.25) continue;
            double w = (TORN_W0 + (TORN_W1 - TORN_W0) * unit(o.seed, j, 22)) * (0.55 + 0.45 * s), top = unit(o.seed, j, 23) < 0.5 ? -LIFT : 0.6 + 2.2 * unit(o.seed, j, 24);
            Path2D.Double p = new Path2D.Double();
            int n = 9;
            for (int i = 0; i <= n; i++) {                       // ragged upper edge, left to right
                double xx = x - w / 2 + w * i / n, e = Math.sin(Math.PI * i / n);
                double yy = t.at(xx) + top + (1 - e) * 2.0 + (unit(o.seed, j * 16 + i, 25) - 0.5) * 1.3;
                if (i == 0) p.moveTo(xx, yy); else p.lineTo(xx, yy);
            }
            for (int i = n; i >= 0; i--) {                       // ... and the lower one back
                double xx = x - w / 2 + w * i / n, e = Math.sin(Math.PI * i / n);
                p.lineTo(xx, t.at(xx) + top + 1.6 + e * (2.4 + 2.2 * unit(o.seed, j, 26)) + (unit(o.seed, j * 16 + i, 27) - 0.5) * 1.1);
            }
            p.closePath();
            g.setColor(dirt);
            g.fill(p);
            g.setStroke(new BasicStroke(0.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Lit.alpha(dark, vis * 2 / 3));
            g.draw(p);
            // a few stones in the bed
            g.setColor(Lit.alpha(lit.lit(new Color(150, 142, 128)), vis * 4 / 5));
            for (int q = 0; q < 4; q++) {
                double sx = x + (unit(o.seed, j * 8 + q, 28) - 0.5) * w * 0.7, sy = t.at(sx) + top + 1.6 + unit(o.seed, j * 8 + q, 29) * 2.0;
                g.fill(new Rectangle2D.Double(sx - 0.6, sy - 0.4, 1.2, 0.8));
            }
        }
        // ---- cracks: thin dark lines that wander along the strip and fork once
        g.setStroke(new BasicStroke(0.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Lit.alpha(dark, vis * 9 / 10));
        int nc = scale >= 0.42 ? cracks(o, d) : 0;
        for (int j = 0; j < nc; j++) {
            double x = itemX(o, j, 31), s = strength(o, d, x);
            if (s < 0.12) continue;
            double len = (CRACK_L0 + (CRACK_L1 - CRACK_L0) * unit(o.seed, j, 32)) * (0.5 + 0.5 * s), y = -1.0 + 5.4 * unit(o.seed, j, 33);
            int n = Math.max(3, (int) (len / 4.5));
            Path2D.Double p = new Path2D.Double();
            double fx = 0, fy = 0;
            for (int i = 0; i <= n; i++) {
                double xx = x - len / 2 + len * i / n;
                y = Model.clamp(y + (unit(o.seed, j * 16 + i, 34) - 0.5) * 1.5, -1.3, 5.1);
                if (i == 0) p.moveTo(xx, t.at(xx) + y); else p.lineTo(xx, t.at(xx) + y);
                if (i == n / 2) { fx = xx; fy = y; }
            }
            double bl = 3 + 5 * unit(o.seed, j, 35), by = Model.clamp(fy + (unit(o.seed, j, 36) < 0.5 ? -1.8 : 1.8), -1.4, 5.2);
            p.moveTo(fx, t.at(fx) + fy);
            p.lineTo(fx + bl, t.at(fx + bl) + by);
            g.draw(p);
        }
        // ---- potholes: a dark hole 2 px deep with a pale broken rim on its lower edge
        int nh = potholes(o, d);
        for (int j = 0; j < nh; j++) {
            double x = itemX(o, j, 41), s = strength(o, d, x);
            if (x < a + 3 || x > b - 3 || s < 0.15) continue;
            double w = (HOLE_W0 + (HOLE_W1 - HOLE_W0) * unit(o.seed, j, 42)) * (0.6 + 0.4 * s), hh = HOLE_DEEP * (0.75 + 0.5 * unit(o.seed, j, 43));
            double cy = t.at(x) + 0.4 + 3.4 * unit(o.seed, j, 44);
            g.setColor(rim);
            g.fill(new Ellipse2D.Double(x - w / 2 - 0.6, cy - hh / 2 + 0.5, w + 1.2, hh + 0.5));
            g.setColor(dark);
            g.fill(new Ellipse2D.Double(x - w / 2, cy - hh / 2, w, hh));
        }
        // ---- spill: crumbs of the building on the asphalt (its own wall and roof colours)
        if (scale >= 0.42) {
            Color wallC = o.wall != null ? new Color(o.wall[0], o.wall[1], o.wall[2]) : new Color(200, 186, 160);
            Color roofC = o.roof != null ? new Color(o.roof[0], o.roof[1], o.roof[2]) : new Color(130, 70, 56);
            int ns = spill(o, d);
            for (int j = 0; j < ns; j++) {
                double x = itemX(o, j, 51), s = strength(o, d, x);
                if (s < 0.2 || unit(o.seed, j, 52) > s + 0.25) continue;
                double y = t.at(x) - 1.0 + 5.6 * unit(o.seed, j, 53), sz = 0.7 + 1.1 * unit(o.seed, j, 54);
                Color col = switch ((int) (unit(o.seed, j, 55) * 4)) { case 0 -> wallC; case 1 -> roofC; case 2 -> new Color(104, 78, 54); default -> new Color(120, 118, 114); };
                g.setColor(Lit.alpha(lit.lit(Lit.mul(col, 0.6 + 0.3 * unit(o.seed, j, 56))), vis));
                g.fill(new Rectangle2D.Double(x - sz * 0.6, y - sz * 0.35, sz * 1.2, sz * 0.7));
            }
        }
    }
}
