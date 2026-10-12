package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;

/**
 * Phase O (OW4): the piles of rubble beside a wrecked building. They are a <b>pure function of the object and its
 * step on the damage ladder</b> ({@link Wreck#level}, quantised to 1/24): nothing is saved for them and a reload
 * shows the same piles. Baked into the ground tile ({@code Renderer.paintGround}): a front-row building's piles after
 * the front row and the roads, a back-row building's with the back row, before the terrain and the front row.
 * <p>
 * While a building has pieces in the air ({@link Wreck.Snap#airborne}) its piles are drawn part-built
 * ({@link #AIR_SHARE}); when the last piece has landed they are drawn in full.
 */
final class Rubble {
    private Rubble() {}

    // ------------------------------------------------------------------ the model table's constants (PLAN.md, Phase O)
    /** No pile below this damage; on the ladder that is from step {@link #K_MIN} (8 of 24 = 0.33) on. */
    static final double D_MIN = 0.30;
    static final int K_MIN = (int) Math.ceil(D_MIN * 24 - 1e-9);
    /** {@code min(12, round(d (2 + w / 20)))} piles. */
    static final int MAX_PILES = 12;
    /** A pile stands at {@code o.x + side (w/2 + 6 + R u^2)}, {@code R = clamp(40 + 0.9 h, 60, 260)}. */
    static final double GAP = 6, R_BASE = 40, R_H = 0.9, R_MIN = 60, R_MAX = 260;
    /** A pile is 6-16 px wide and 2-7 px high. */
    static final double W_MIN = 6, W_MAX = 16, H_MIN = 2, H_MAX = 7;
    /** While the building has pieces in the air: this share of each pile's size (and of its loose pieces). */
    static final double AIR_SHARE = 0.35;
    /** A back-row building is drawn at 0.9 of its size through 0.36 of haze (as its picture is): so are its piles. */
    static final double BACK_SCALE = 0.9, BACK_HAZE = 0.36;

    private static double unit(long seed, int j, int salt) { return Scene.hash01(seed * 0x9E3779B97F4A7C15L + j * 0xD1B54A32D192ED03L + salt * 0x2545F4914F6CDD1DL + 0x0B); }

    /** How many piles a building has at damage {@code d} (taken at its step of the ladder, so it only changes when the tile is re-baked). */
    static int count(Model.Obj o, double d) {
        int k = Wreck.k(d);
        if (k < K_MIN || !Wreck.wreckable(o)) return 0;
        return Math.max(1, Math.min(MAX_PILES, (int) Math.round(k / 24.0 * (2 + o.w / 20))));
    }

    /** True when the building has piles (and its road is worn): its tile's dirty range reaches as far as they do. */
    static boolean has(Model.Obj o) { return Wreck.wreckable(o) && Wreck.k(Wreck.level(o)) >= K_MIN; }

    static double radius(Model.Obj o) { return Model.clamp(R_BASE + R_H * o.h, R_MIN, R_MAX); }

    /** How far beyond the building's side its piles can reach (px): the bake's dirty range is widened by this. */
    static double reach(Model.Obj o) { return GAP + radius(o) + W_MAX / 2 + 3; }

    /** World x of pile {@code j} (the same at every damage: a pile appears, it never moves). Sides alternate, starting from the seed's side. */
    static double spotX(Model.Obj o, int j) {
        int side = ((j + (unit(o.seed, 0, 1) < 0.5 ? 0 : 1)) & 1) == 0 ? -1 : 1;
        double u = unit(o.seed, j, 2);
        return o.x + side * (o.w / 2 + GAP + radius(o) * u * u);
    }

    /** The pile among the first {@code n} that stands nearest to x. */
    static int nearest(Model.Obj o, int n, double x) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int j = 0; j < n; j++) {
            double d = Math.abs(spotX(o, j) - x);
            if (d < bd) { bd = d; best = j; }
        }
        return best;
    }

    /** Width and height of pile {@code j} when it is full (px). */
    static double[] size(Model.Obj o, int j) {
        double a = unit(o.seed, j, 3), b = unit(o.seed, j, 4), big = 0.6 * a + 0.4 * b;
        return new double[]{W_MIN + (W_MAX - W_MIN) * a, H_MIN + (H_MAX - H_MIN) * big};
    }

    /** Height of pile {@code j} above the ground at world x (0 outside it): a mound with a lumpy top. */
    static double heightAt(Model.Obj o, int j, double x, double share) {
        double[] s = size(o, j);
        double w = s[0] * (0.55 + 0.45 * share), h = s[1] * share, u = (x - spotX(o, j)) / (w * 0.5);
        if (u <= -1 || u >= 1) return 0;
        double lump = 0.86 + 0.14 * Math.sin(u * 7.3 + unit(o.seed, j, 5) * 6.283);
        return h * Math.pow(1 - u * u, 0.8) * lump;
    }

    /**
     * Paint the piles of every wrecked building of one row that reach into [x0, x1].
     * @param bits     which buildings have pieces in the air (null = none)
     * @param backRow  true = the back row's piles (call before the terrain), false = the front row's (after the roads)
     */
    static void paint(Graphics2D g, Scene sc, Wreck.Snap bits, double x0, double x1, boolean backRow) {
        boolean any = false;
        for (Model.Obj o : sc.objects) {
            if (!o.building || (o.layer == 1) != backRow || o.damage <= 0) continue;
            int n = count(o, Wreck.level(o));
            if (n == 0) continue;
            double r = o.w / 2 + reach(o);
            if (o.x + r < x0 || o.x - r > x1) continue;
            if (!any) { any = true; g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); }
            double share = bits != null && bits.airborne(o.id) ? AIR_SHARE : 1;
            for (int j = 0; j < n; j++) {
                double px = spotX(o, j);
                if (px + W_MAX < x0 || px - W_MAX > x1) continue;
                pile(g, sc, o, j, share, backRow);
            }
        }
    }

    /** One pile: a mound of dust and broken masonry on the ground, loose pieces resting on it, a splintered board or two. */
    static void pile(Graphics2D g, Scene sc, Model.Obj o, int j, double share, boolean backRow) {
        final Model.Terrain t = sc.terrain;
        final Lit lit = sc.lit;
        final double haze = backRow ? BACK_HAZE : 0, k = backRow ? BACK_SCALE : 1, sh = share * k;
        final double cx = spotX(o, j);
        double[] s = size(o, j);
        final double w = s[0] * (0.55 + 0.45 * share), half = w * 0.5;
        Color wallC = o.wall != null ? new Color(o.wall[0], o.wall[1], o.wall[2]) : new Color(200, 186, 160);
        Color roofC = o.roof != null ? new Color(o.roof[0], o.roof[1], o.roof[2]) : new Color(130, 70, 56);
        Color wood = new Color(104, 78, 54), grey = new Color(120, 118, 114);
        // the mound
        Color mound = Lit.mix(Lit.mix(grey, wallC, 0.35), new Color(70, 62, 54), 0.45);
        Path2D.Double p = new Path2D.Double();
        final int steps = Math.max(6, (int) Math.ceil(w / 1.5));
        p.moveTo(cx - half, t.at(cx - half) + 0.9);
        for (int i = 0; i <= steps; i++) {
            double x = cx - half + w * i / steps;
            p.lineTo(x, t.at(x) - heightAt(o, j, x, sh) + (i == 0 || i == steps ? 0.2 : 0));
        }
        p.lineTo(cx + half, t.at(cx + half) + 0.9);
        p.closePath();
        g.setColor(lit.lit(mound, haze));
        g.fill(p);
        // a lighter crust of dust along the top
        g.setColor(Lit.alpha(lit.lit(Lit.mix(mound, new Color(196, 186, 170), 0.5), haze), 150));
        Path2D.Double crust = new Path2D.Double();
        for (int i = 1; i < steps; i++) {
            double x = cx - half + w * i / steps, y = t.at(x) - heightAt(o, j, x, sh) + 0.5;
            if (i == 1) crust.moveTo(x, y); else crust.lineTo(x, y);
        }
        g.draw(crust);
        // loose pieces resting on the mound (each one sunk a little into it: none can float)
        int pieces = (int) Math.round((3 + s[0] * 0.45) * share);
        for (int q = 0; q < pieces; q++) {
            double u = unit(o.seed, j * 37 + q, 6) * 1.6 - 0.8, x = cx + u * half, hy = heightAt(o, j, x, sh);
            double sz = (0.9 + 1.6 * unit(o.seed, j * 37 + q, 7)) * k, a = unit(o.seed, j * 37 + q, 8) * Math.PI;
            double y = t.at(x) - hy + sz * 0.15;
            Color col = switch ((int) (unit(o.seed, j * 37 + q, 9) * 4)) { case 0 -> wallC; case 1 -> roofC; case 2 -> wood; default -> grey; };
            g.setColor(lit.lit(Lit.mul(col, 0.62 + 0.3 * unit(o.seed, j * 37 + q, 10)), haze));
            Path2D.Double f = new Path2D.Double();
            f.moveTo(x - Math.cos(a) * sz, y - Math.abs(Math.sin(a)) * sz * 0.5);
            f.lineTo(x + Math.sin(a) * sz * 0.4, y - sz * 0.45);
            f.lineTo(x + Math.cos(a) * sz, y + Math.abs(Math.sin(a)) * sz * 0.2);
            f.lineTo(x - Math.sin(a) * sz * 0.4, y + sz * 0.35);
            f.closePath();
            g.fill(f);
        }
        // a splintered board standing out of a pile big enough to hold it (its foot is inside the mound)
        if (share >= 1 && s[1] >= 4 && unit(o.seed, j, 11) < 0.6) {
            double u = unit(o.seed, j, 12) * 0.6 - 0.3, x = cx + u * half, base = t.at(x) - heightAt(o, j, x, sh) + 1.0;
            double len = (2.5 + 3 * unit(o.seed, j, 13)) * k, lean = (unit(o.seed, j, 14) - 0.5) * 2.4;
            g.setColor(lit.lit(Lit.mul(wood, 0.8), haze));
            g.draw(new Line2D.Double(x, base, x + lean, base - len));
        }
    }
}
