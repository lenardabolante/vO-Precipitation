package precip;

import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Lightning as a local light in world space (it used to whiten the whole view, wherever the bolt was).
 * <p>
 * Every bolt carries up to three point lights: its point of origin at the cloud base, the middle of the
 * channel and a point just above the strike. Each light falls off smoothly with world distance and ends
 * at {@link #REACH}, so a bolt further than that from the view changes no pixel of it.
 * <p>
 * Occlusion is ray traced against the real geometry: the building silhouettes ({@link #silhouette}) and
 * the terrain surface. Per light a 1-D shadow map is built once (the bolt does not move): for each of
 * {@code 4 * FACE_N} directions, how far a ray from the light travels before it enters the first solid
 * thing and where it leaves it again. A point is lit when it lies before that entry; inside the first
 * building the facade is lit (brightest at the edge facing the light), the first few pixels of ground
 * are lit, and everything behind is in shadow. Directions are parametrised on the four faces of a
 * square (minor / major axis), so a lookup costs one division and no trigonometry, and neighbouring
 * directions are interpolated, which gives shadow edges a penumbra that widens with distance.
 * <p>
 * The light itself is evaluated on a coarse grid of the view ({@link Frame#texel} device pixels) by
 * every render band for its own rows ({@link Band}) and interpolated bilinearly per pixel: the grid is
 * anchored to the viewport, so bands join seamlessly. Frames without a bolt cost one empty-list test.
 * <p>
 * The stream sends one channel per bolt; the viewer forks it ({@link Tree}, built once per bolt from a seed
 * taken from the bolt, so it never flickers): side branches that leave the origin and the channel, fork
 * again a few levels deep, thin out and mostly end in mid-air. The discharge starts inside the host cloud:
 * a hidden channel with a few tendrils runs up into the cloud body. It is never stroked; it is a line
 * light in the same grid, without shadows and multiplied by the cloud's own drawn opacity, so the cloud
 * glows from within, brightest along the channel, and nothing shines out of its silhouette.
 * <p>
 * {@link Renderer#epilepsySafe}: no light at all (none in the cloud either), and the bolts and their
 * branches are drawn dim with a slow ease in and out on the viewer's own clock ({@link Safe}).
 */
final class Lightning {
    private Lightning() {}

    /** Directions per face of the shadow map. */
    static final int FACE_N = 384;
    /** No light reaches further than this from its bolt (world px). */
    static final double REACH = 950;
    /** How far the light gets into the ground / into the lit edge of a building (world px along the major axis). */
    private static final float PEN_GROUND = 30, PEN_BUILDING = 24;
    /** Share of the light the facade of the first building in the way receives all over. */
    private static final float FACADE = 0.5f;
    /** A bolt the stream dropped while still bright fades out at this rate (1/s) instead of popping. */
    private static final double GHOST_RATE = 2.4;
    private static final float INF = Float.POSITIVE_INFINITY;

    // ------------------------------------------------------------------ occluders

    /**
     * The solid outline of a building as convex polygons (x0, y0, x1, y1, ... world px), following
     * {@link BuildingPainter}: x is the centre, y the ground line, the body is w wide and h tall including
     * a pitched roof; back-row buildings ({@code layer == 1}) are drawn at 0.9 about (x, y). A street
     * lamp is too thin to cast a shadow worth tracing.
     */
    static List<double[]> silhouette(Model.Obj o) {
        List<double[]> out = new ArrayList<>(2);
        if (!o.building || o.kind.equals("streetlamp") || o.w < 4 || o.h < 4) return out;
        double sc = o.layer == 1 ? 0.9 : 1;
        // a ruin (damage past 0.8) keeps only its lower walls
        double w = o.w * sc, h = o.h * sc * (1 - 0.68 * Lit.smooth((o.damage - 0.80) / 0.20)), x = o.x, y = o.y, x0 = x - w / 2, x1 = x + w / 2;
        switch (o.kind) {
            case "shop", "apartment", "office", "skyscraper", "factory" -> out.add(box(x0, y - h, x1, y));
            case "barn" -> out.add(gable(x0, x1, y, h * 0.52, x, h));
            case "windmill" -> out.add(new double[]{x - w * 0.5, y, x - w * 0.3, y - h * 0.72, x + w * 0.3, y - h * 0.72, x + w * 0.5, y});
            case "water_tower" -> out.add(box(x0, y - h * 0.9, x1, y - h * 0.54));        // the tank; the legs are bars
            case "church" -> {
                boolean left = new Random(o.seed * 0x9E3779B97F4A7C15L + 77).nextBoolean();   // (the painter's first draw)
                double tw = w * 0.34, tx = left ? x0 : x1 - tw, nx = left ? x0 + tw : x0, nw = w - tw;
                out.add(gable(tx, tx + tw, y, h * 0.60, tx + tw / 2, h));
                out.add(gable(nx, nx + nw, y, h * 0.36, nx + nw / 2, h * 0.56));
            }
            default -> {
                switch (o.variant == null ? "" : o.variant) {
                    case "adobe" -> out.add(box(x0, y - h * 0.84, x1, y));
                    case "station" -> out.add(box(x0, y - h * 0.88, x1, y - h * 0.24));   // stands on stilts
                    case "hut" -> out.add(gable(x0, x1, y, h * 0.46, x, h));
                    case "townhouse" -> out.add(gable(x0, x1, y, h * 0.80, x, h));
                    case "cabin" -> out.add(gable(x0, x1, y, h * 0.62, x, h));
                    case "farmhouse", "manor" -> out.add(gable(x0, x1, y, h * 0.66, x, h));
                    case "bungalow" -> out.add(gable(x0, x1, y, h * 0.58, x, h));
                    default -> out.add(gable(x0, x1, y, h * 0.60, x, h));
                }
            }
        }
        return out;
    }

    private static double[] box(double x0, double y0, double x1, double y1) { return new double[]{x0, y1, x0, y0, x1, y0, x1, y1}; }

    private static double[] gable(double x0, double x1, double gy, double eave, double px, double peak) {
        return new double[]{x0, gy, x0, gy - eave, px, gy - peak, x1, gy - eave, x1, gy};
    }

    // ------------------------------------------------------------------ one light and its shadow map

    static final class Emitter {
        final double x, y, reach, gain;
        final float fx, fy, invReach2, invCore2;
        /** Nothing was put in its way (Settings > Advanced "Lightning detail off"): every direction is open, no lookup needed. */
        boolean open;
        /** Per direction (face * FACE_N + sample): major-axis distance to the entry into / exit from the first occluder, and its facade share. */
        final float[] tIn = new float[4 * FACE_N], tOut = new float[4 * FACE_N], fac = new float[4 * FACE_N];

        Emitter(double x, double y, double reach, double core, double gain) {
            this.x = x; this.y = y; this.reach = reach; this.gain = gain;
            fx = (float) x; fy = (float) y;
            invReach2 = (float) (1 / (reach * reach)); invCore2 = (float) (1 / (core * core));
            Arrays.fill(tIn, INF);
            Arrays.fill(tOut, INF);
        }

        // world offset -> (major, minor) of face f:  0 = +x, 1 = +y (down), 2 = -x, 3 = -y (up)
        private static double major(int f, double dx, double dy) { return f == 0 ? dx : f == 1 ? dy : f == 2 ? -dx : -dy; }

        private static double minor(int f, double dx, double dy) { return (f & 1) == 0 ? dy : dx; }

        /** A convex solid. One containing the light itself is ignored (a bolt that strikes a roof still lights the street). */
        void addSolid(double[] p, float facade) {
            int n = p.length / 2;
            double minX = 1e18, maxX = -1e18, minY = 1e18, maxY = -1e18;
            for (int i = 0; i < n; i++) { minX = Math.min(minX, p[2 * i]); maxX = Math.max(maxX, p[2 * i]); minY = Math.min(minY, p[2 * i + 1]); maxY = Math.max(maxY, p[2 * i + 1]); }
            if (maxX < x - reach || minX > x + reach || maxY < y - reach || minY > y + reach) return;
            boolean pos = false, neg = false;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double cr = (p[2 * j] - p[2 * i]) * (y - p[2 * i + 1]) - (p[2 * j + 1] - p[2 * i + 1]) * (x - p[2 * i]);
                if (cr > 0) pos = true; else if (cr < 0) neg = true;
            }
            if (!(pos && neg)) return;
            double[] a = new double[n], b = new double[n], ca = new double[n + 2], cb = new double[n + 2];
            final double eps = 0.5, hN = (FACE_N - 1) / 2.0;
            for (int f = 0; f < 4; f++) {
                for (int i = 0; i < n; i++) { a[i] = major(f, p[2 * i] - x, p[2 * i + 1] - y); b[i] = minor(f, p[2 * i] - x, p[2 * i + 1] - y); }
                // clip to the half plane in front of the light (Sutherland-Hodgman, one edge)
                int m = 0;
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    boolean in = a[i] >= eps, jn = a[j] >= eps;
                    if (in) { ca[m] = a[i]; cb[m++] = b[i]; }
                    if (in != jn) { double s = (eps - a[i]) / (a[j] - a[i]); ca[m] = eps; cb[m++] = b[i] + s * (b[j] - b[i]); }
                }
                if (m < 3) continue;
                double u0 = 1e18, u1 = -1e18;
                for (int i = 0; i < m; i++) { double u = cb[i] / ca[i]; u0 = Math.min(u0, u); u1 = Math.max(u1, u); }
                if (u1 < -1 || u0 > 1) continue;
                int k0 = Math.max(0, (int) Math.floor((Math.max(u0, -1) + 1) * hN)), k1 = Math.min(FACE_N - 1, (int) Math.ceil((Math.min(u1, 1) + 1) * hN));
                for (int k = k0; k <= k1; k++) {
                    double u = -1 + k / hN, tMin = 1e18, tMax = -1e18, gp = cb[m - 1] - u * ca[m - 1];
                    for (int i = 0, q = m - 1; i < m; q = i++) {
                        double gi = cb[i] - u * ca[i];
                        if ((gp <= 0) != (gi <= 0)) {
                            double t = ca[q] + gp / (gp - gi) * (ca[i] - ca[q]);
                            tMin = Math.min(tMin, t); tMax = Math.max(tMax, t);
                        }
                        gp = gi;
                    }
                    int idx = f * FACE_N + k;
                    if (tMax > tMin && tMin < tIn[idx]) { tIn[idx] = (float) tMin; tOut[idx] = (float) tMax; fac[idx] = facade; }
                }
            }
        }

        /** The terrain surface within reach: a ray that meets it enters the ground and does not come out. */
        void addTerrain(Model.Terrain terr) {
            double[] ty = terr.y;
            double step = terr.step;
            int i0 = Math.max(0, (int) Math.floor((x - reach) / step)), i1 = Math.min(ty.length - 2, (int) Math.ceil((x + reach) / step));
            final double eps = 0.5, hN = (FACE_N - 1) / 2.0;
            for (int i = i0; i <= i1; i++) {
                double ax = i * step - x, ay = ty[i] - y, bx = ax + step, by = ty[i + 1] - y;
                for (int f = 0; f < 4; f++) {
                    double a0 = major(f, ax, ay), b0 = minor(f, ax, ay), a1 = major(f, bx, by), b1 = minor(f, bx, by);
                    if (a0 < eps && a1 < eps) continue;
                    if (a0 < eps) { double s = (eps - a0) / (a1 - a0); b0 += s * (b1 - b0); a0 = eps; }
                    else if (a1 < eps) { double s = (eps - a1) / (a0 - a1); b1 += s * (b0 - b1); a1 = eps; }
                    double u0 = b0 / a0, u1 = b1 / a1;
                    if (u0 > u1) { double t = u0; u0 = u1; u1 = t; }
                    if (u1 < -1 || u0 > 1) continue;
                    int k0 = Math.max(0, (int) Math.floor((Math.max(u0, -1) + 1) * hN)), k1 = Math.min(FACE_N - 1, (int) Math.ceil((Math.min(u1, 1) + 1) * hN));
                    for (int k = k0; k <= k1; k++) {
                        double u = -1 + k / hN, g0 = b0 - u * a0, g1 = b1 - u * a1;
                        if ((g0 <= 0) == (g1 <= 0)) continue;
                        double t = a0 + g0 / (g0 - g1) * (a1 - a0);
                        int idx = f * FACE_N + k;
                        if (t < tIn[idx]) { tIn[idx] = (float) t; tOut[idx] = INF; fac[idx] = 0; }
                    }
                }
            }
        }

        /** Share 0..1 of this light that reaches the world point (wx, wy), occlusion only (no distance falloff). */
        double visibility(double wx, double wy) {
            float dx = (float) (wx - x), dy = (float) (wy - y), adx = Math.abs(dx), ady = Math.abs(dy), t, u;
            int base;
            if (adx >= ady) { if (adx <= 0) return 1; t = adx; u = dy / adx; base = dx > 0 ? 0 : 2 * FACE_N; }
            else { t = ady; u = dx / ady; base = dy > 0 ? FACE_N : 3 * FACE_N; }
            float fb = (u + 1) * ((FACE_N - 1) * 0.5f);
            int i = Math.min(FACE_N - 2, (int) fb);
            float fr = fb - i, v0 = vis(tIn, tOut, fac, base + i, t);
            return v0 + (vis(tIn, tOut, fac, base + i + 1, t) - v0) * fr;
        }
    }

    private static int tap(float f) { return f <= 0 ? 0 : f >= FACE_N - 1 ? FACE_N - 1 : (int) f; }

    private static float vis(float[] tIn, float[] tOut, float[] fac, int i, float t) {
        float a = tIn[i];
        if (t <= a) return 1f;
        if (t > tOut[i]) return 0f;
        float f = fac[i], q = 1 - (t - a) / (f > 0 ? PEN_BUILDING : PEN_GROUND);
        return q > 0 ? f + (1 - f) * q * q : f;
    }

    // ------------------------------------------------------------------ one bolt

    static final class Light {
        final long key;
        final double[] pts;
        /** Null until the bolt first comes within reach of a view (the shadow maps are the expensive part). */
        Emitter[] em;
        /** Brightness 0..1 as drawn this frame (the stream's alpha, or the viewer's own fade once the stream dropped the bolt). */
        double alpha;
        boolean present;
        /** World y of the cloud base the bolt hangs from and of that cloud's top: the light fades out between them, none above. */
        double baseY, topY;
        final double x0, y0, x1, y1;       // bounds of everything it can light
        /** The cloud it hangs from as drawn when the bolt appeared (null = none): its image is the mask of the glow inside it. */
        Model.Cloud hostC;
        SharedClouds.Entry hostE;
        double hostX, hostY;
        /** Its branches and its channel inside the cloud. */
        final Tree tree;

        Light(long key, double[] pts, Model.Terrain terr, List<Shared.DrawCloud> clouds) { this(key, pts, terr, clouds, true, true); }

        /**
         * @param inCloud false = no hidden channel in the host cloud, so the cloud does not glow from within
         * @param branching false = the channel alone ({@link Tree#grow})
         */
        Light(long key, double[] pts, Model.Terrain terr, List<Shared.DrawCloud> clouds, boolean inCloud, boolean branching) {
            this.key = key; this.pts = pts;
            double ax = 1e18, ay = 1e18, bx = -1e18, by = -1e18;
            for (int k = 0; k + 1 < pts.length; k += 2) { ax = Math.min(ax, pts[k]); bx = Math.max(bx, pts[k]); ay = Math.min(ay, pts[k + 1]); by = Math.max(by, pts[k + 1]); }
            baseY = pts[1]; topY = pts[1] - 150;
            if (clouds != null) host(this, clouds);
            // (the channel is always grown into the cloud: what it shows in clear air under an uneven base belongs to the bolt
            // as it is drawn. Without the light in the cloud only that light is dropped, so the bolt looks the same either way.)
            tree = Tree.grow(pts, key, terr, hostC == null ? null : this::cover, hostX, baseY, topY, branching);
            if (!inCloud) tree.dropGlow();
            ax -= REACH; bx += REACH; ay -= REACH; by += REACH;
            if (tree.glow.length > 0) {
                ax = Math.min(ax, tree.gx0 - Tree.GLOW_REACH); bx = Math.max(bx, tree.gx1 + Tree.GLOW_REACH);
                ay = Math.min(ay, tree.gy0 - Tree.GLOW_REACH); by = Math.max(by, tree.gy1 + Tree.GLOW_REACH);
            }
            x0 = ax; x1 = bx; y0 = ay; y1 = by;
        }

        /** How much of the host cloud covers a world point, 0..1: the opacity of its drawn image (its puffs while it has none). */
        float cover(double wx, double wy) {
            CloudPainter.Image im = hostE == null ? null : hostE.drawCur != null && hostE.drawCur.px != null ? hostE.drawCur : hostE.drawPrev;
            if (im != null && im.px != null) {
                double fx = (wx - hostX - im.relX) * im.scale, fy = (wy - hostY - im.relY) * im.scale;
                if (fx < 0 || fy < 0 || fx >= im.w || fy >= im.h) return 0;
                return (im.px[(int) fy * im.w + (int) fx] >>> 24) * (1 / 255f);
            }
            double best = 0;
            for (double[] p : hostC.puffs) if (p[2] > 0.3) best = Math.max(best, (1 - Math.hypot(wx - hostX - p[0], wy - hostY - p[1]) / (0.85 * p[2])) * 4);
            return (float) Lit.smooth(best);
        }

        void build(Model.Obj[] objs, Model.Terrain terr) { build(objs, terr, true); }

        /** @param shadows false = the three lights with their falloff only: no building and no ground is put in their way */
        void build(Model.Obj[] objs, Model.Terrain terr, boolean shadows) {
            double[] mid = along(pts, 0.5), low = along(pts, 0.93);
            if (terr != null) {
                low[1] = Math.min(low[1], terr.at(low[0]) - 14);
                mid[1] = Math.min(mid[1], terr.at(mid[0]) - 14);
            }
            em = new Emitter[]{
                    new Emitter(pts[0], terr != null ? Math.min(pts[1], terr.at(pts[0]) - 14) : pts[1], REACH, 135, 1.0),   // the point of origin
                    new Emitter(mid[0], mid[1], 720, 110, 0.5),
                    new Emitter(low[0], low[1], 720, 95, 0.8)};
            if (!shadows) { for (Emitter e : em) e.open = true; return; }
            List<double[]> solids = new ArrayList<>();
            // (a building the channel is drawn across stands behind the bolt: it is lit like the air around it and blocks nothing)
            if (objs != null) for (Model.Obj o : objs) if (o.building && o.x + o.w > x0 && o.x - o.w < x1)
                for (double[] p : silhouette(o)) if (!crosses(pts, p)) solids.add(p);
            for (Emitter e : em) {
                for (double[] p : solids) e.addSolid(p, FACADE);
                if (terr != null) e.addTerrain(terr);
            }
        }

        boolean sees(double wx0, double wy0, double wx1, double wy1) { return x1 > wx0 && x0 < wx1 && y1 > wy0 && y0 < wy1; }

        /**
         * The share of the point lights that gets up to world y: 1 below the cloud base, easing to 0 at the cloud top. When the
         * bolt has a channel inside the cloud they give out sooner ({@link #FADE} above the base): the cloud is lit by that channel.
         */
        float cloudAtt(double wy) {
            if (wy >= baseY) return 1;
            double top = tree.glow.length > 0 ? Math.max(topY, baseY - FADE) : topY;
            if (wy <= top) return 0;
            double s = (wy - top) / (baseY - top);
            return (float) (s * s * (3 - 2 * s));
        }

        private static final double FADE = 170;
    }

    /** Does the polyline (x0, y0, x1, y1, ...) touch the convex polygon {@code p}? */
    static boolean crosses(double[] line, double[] p) {
        int n = p.length / 2;
        for (int k = 0; k + 1 < line.length; k += 2) {
            double ax = line[k], ay = line[k + 1];
            boolean pos = false, neg = false;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double cr = (p[2 * j] - p[2 * i]) * (ay - p[2 * i + 1]) - (p[2 * j + 1] - p[2 * i + 1]) * (ax - p[2 * i]);
                if (cr > 0) pos = true; else if (cr < 0) neg = true;
            }
            if (!(pos && neg)) return true;                     // a vertex inside
            if (k + 3 >= line.length) break;
            double bx = line[k + 2], by = line[k + 3];
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double cx = p[2 * i], cy = p[2 * i + 1], dx = p[2 * j], dy = p[2 * j + 1];
                double d1 = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax), d2 = (bx - ax) * (dy - ay) - (by - ay) * (dx - ax);
                double d3 = (dx - cx) * (ay - cy) - (dy - cy) * (ax - cx), d4 = (dx - cx) * (by - cy) - (dy - cy) * (bx - cx);
                if ((d1 > 0) != (d2 > 0) && (d3 > 0) != (d4 > 0)) return true;
            }
        }
        return false;
    }

    /** The point a fraction {@code f} of the way along a polyline (x0, y0, x1, y1, ...). */
    static double[] along(double[] pts, double f) {
        double len = 0;
        for (int k = 2; k + 1 < pts.length; k += 2) len += Math.hypot(pts[k] - pts[k - 2], pts[k + 1] - pts[k - 1]);
        double want = len * f;
        for (int k = 2; k + 1 < pts.length; k += 2) {
            double seg = Math.hypot(pts[k] - pts[k - 2], pts[k + 1] - pts[k - 1]);
            if (want <= seg && seg > 0) { double s = want / seg; return new double[]{pts[k - 2] + s * (pts[k] - pts[k - 2]), pts[k - 1] + s * (pts[k + 1] - pts[k - 1])}; }
            want -= seg;
        }
        return new double[]{pts[pts.length - 2], pts[pts.length - 1]};
    }

    /** World px per unit of stroke width: bolts are drawn a little wider on screen the closer the view (the square root of the zoom). */
    static float strokeScale(Camera.View v) { return (float) (1 / Math.sqrt(Math.max(0.2, v.zoom))); }

    static long key(Model.Bolt b) {
        double[] p = b.pts;
        return Double.doubleToLongBits(p[0]) * 31 + Double.doubleToLongBits(p[1]) * 1_000_003L + Double.doubleToLongBits(p[p.length - 2]) * 7919 + p.length;
    }

    // ------------------------------------------------------------------ branches, and the channel inside the cloud

    /** How much of the host cloud covers a world point, 0..1. */
    interface Mask { double at(double wx, double wy); }

    /**
     * What the viewer adds to a bolt's channel, grown once from a seed taken from the bolt (the same bolt is the same tree
     * in every frame, band and picture). Below the cloud: jagged branches that head down and outward, one from the point of
     * origin and up to seven from the channel (more of them high up), each of which may fork again up to {@link #MAX_DEPTH}
     * levels; they get thinner and dimmer toward the tip and with every level, and end in mid-air above the terrain
     * (about one branch in ten off the channel is allowed down to the ground). Inside the cloud: a trunk from the origin up
     * into the body with a few sideways tendrils, kept where the cloud is opaque; only its light is drawn.
     */
    static final class Tree {
        static final int MAX_DEPTH = 3, MAX_SEG = 96, MAX_CLOUD_SEG = 40;
        /** The light of the hidden channel: no further than this from it (world px), two falloff radii and its strength. */
        static final double GLOW_REACH = 230, GLOW_NEAR = 22, GLOW_WIDE = 85, GLOW_GAIN = 1.2;

        /** The channel as stroked: the stream's points, led by what the trunk shows in clear air under an uneven cloud base. */
        double[] main;
        /** Every branch below the cloud (x0, y0, x1, y1, ...), its fork level (1 = off the channel) and whether it ends on the ground. */
        final List<double[]> lines = new ArrayList<>();
        final List<Integer> depth = new ArrayList<>();
        final List<Boolean> grounded = new ArrayList<>();
        /** Per branch and point: its strength against 1 for the channel (it falls toward the tip and with every fork level). */
        final List<double[]> strength = new ArrayList<>();
        /** The bounds of the branches (x0, y0, x1, y1). */
        final double[] box = {1e18, 1e18, -1e18, -1e18};
        /**
         * The branches as two outlines to fill, the soft glow and the white core: ribbons that taper with the strength (a core
         * thinner than a pixel is a dimmer line). Two fills for the whole tree, where stroking each piece at its own width cost
         * a dozen strokes; and one outline cannot add up where branches cross. See {@link #shape}.
         */
        Path2D.Double glowShape, coreShape;
        private float shapeIz = -1;
        /** Epilepsy-safe only: the channel and every branch as ONE path, so that strokes lying over each other cannot add up beyond the bolt's opacity. */
        Path2D.Double whole;
        double[] wholeBox;
        int segments;
        /** The hidden channel in the cloud, as polylines (trunk first) and as light segments (x0, y0, x1, y1, strength) with their bounds. */
        final List<double[]> hidden = new ArrayList<>();
        float[] glow = new float[0];
        double gx0 = 1e18, gy0 = 1e18, gx1 = -1e18, gy1 = -1e18;

        private static double side(Random r) { return r.nextBoolean() ? 1 : -1; }

        /**
         * @param mask the host cloud (null = no cloud, or no light wanted: nothing inside it)
         * @param cloudX the host's centre x; baseY and topY: the world y of its base and top
         */
        static Tree grow(double[] pts, long key, Model.Terrain terr, Mask mask, double cloudX, double baseY, double topY) {
            return grow(pts, key, terr, mask, cloudX, baseY, topY, true);
        }

        /**
         * @param branching false (Settings > Advanced "Lightning branching off"): the branches are grown and then dropped, so the
         *                  random numbers the channel in the cloud is made from, and with them its glow, are the same either way
         */
        static Tree grow(double[] pts, long key, Model.Terrain terr, Mask mask, double cloudX, double baseY, double topY, boolean branching) {
            Tree t = new Tree();
            t.main = pts;
            Random r = new Random(key * 0x9E3779B97F4A7C15L + 0x5851F42D4C957F2DL);
            int n = pts.length;
            double ox = pts[0], oy = pts[1], ey = pts[n - 1], H = Math.max(40, ey - oy), unit = Model.clamp(H / 16, 9, 26);
            double rich = r.nextDouble();
            rich *= rich;                                    // most bolts fork little, a few heavily
            // a branch to grow: x, y, heading (angle from straight down), length to spend, strength, fork level, 1 = may reach the ground
            ArrayDeque<double[]> q = new ArrayDeque<>();
            q.add(new double[]{ox, oy, side(r) * (0.6 + 0.6 * r.nextDouble()), H * (0.22 + 0.35 * r.nextDouble()), 0.84, 1, 0});
            for (int k = (int) (rich * 7); k > 0; k--) {
                double f = 0.06 + 0.78 * Math.pow(r.nextDouble(), 1.7);
                double[] p = along(pts, f);
                double ground = r.nextDouble() < 0.10 ? 1 : 0;
                q.add(new double[]{p[0], p[1], side(r) * (0.45 + 0.6 * r.nextDouble()), Math.max(3 * unit, (ey - p[1]) * (0.25 + 0.5 * r.nextDouble())), 0.82 - 0.15 * f, 1, ground});
            }
            double[] buf = new double[2 * (MAX_SEG + 2)], sv = new double[MAX_SEG + 2];
            while (!q.isEmpty() && t.segments < MAX_SEG) {      // level by level: the branches off the channel come before their forks
                double[] b = q.poll();
                double x = b[0], y = b[1], th = b[2], trend = b[2], budget = b[3], used = 0;
                boolean ground = b[6] > 0, hit = false;
                int level = (int) b[5], m = 1;
                buf[0] = x; buf[1] = y; sv[0] = b[4];
                double fork = (0.10 + 0.22 * rich) * Math.pow(0.6, level - 1);
                while ((ground || used < budget) && t.segments < MAX_SEG) {
                    double len = unit * (0.6 + 0.8 * r.nextDouble());
                    trend *= ground ? 0.8 : 0.95;            // outward at first, then more and more straight down
                    th = Model.clamp(0.55 * th + 0.45 * trend + r.nextGaussian() * 0.42, -1.35, 1.35);    // (never upward)
                    double nx = x + Math.sin(th) * len, ny = y + Math.cos(th) * len, g = terr != null ? terr.at(nx) : ey;
                    if (ny >= g - 4) {                       // the ground: most branches stop short of it
                        if (ground) { buf[2 * m] = nx; buf[2 * m + 1] = g; sv[m] = sv[m - 1]; m++; t.segments++; hit = true; }
                        break;
                    }
                    used += len; x = nx; y = ny;
                    buf[2 * m] = x; buf[2 * m + 1] = y;
                    sv[m] = b[4] * (ground ? 1 - 0.35 * Math.min(1, used / H) : 1 - 0.72 * Math.min(1, used / budget));
                    m++; t.segments++;
                    if (level < MAX_DEPTH && budget - used > 3 * unit && r.nextDouble() < fork)
                        q.add(new double[]{x, y, Model.clamp(th + side(r) * (0.5 + 0.5 * r.nextDouble()), -1.35, 1.35), (budget - used) * (0.4 + 0.5 * r.nextDouble()), sv[m - 1] * 0.75, level + 1, 0});
                }
                if (m < 2) continue;
                t.lines.add(Arrays.copyOf(buf, 2 * m));
                t.depth.add(level);
                t.grounded.add(hit);
                t.strength.add(Arrays.copyOf(sv, m));
                for (int k = 0; k < m; k++) {
                    t.box[0] = Math.min(t.box[0], buf[2 * k]); t.box[2] = Math.max(t.box[2], buf[2 * k]);
                    t.box[1] = Math.min(t.box[1], buf[2 * k + 1]); t.box[3] = Math.max(t.box[3], buf[2 * k + 1]);
                }
            }
            if (!branching) {
                t.lines.clear(); t.depth.clear(); t.grounded.clear(); t.strength.clear();
                t.box[0] = t.box[1] = 1e18; t.box[2] = t.box[3] = -1e18;
                t.segments = 0;
            }
            if (mask != null) t.intoCloud(r, pts, mask, cloudX, baseY, topY, buf);
            return t;
        }

        /** Forget the channel inside the cloud: no light comes from it (the part of it that shows in clear air stays in {@link #main}). */
        void dropGlow() {
            hidden.clear();
            glow = new float[0];
            gx0 = gy0 = 1e18; gx1 = gy1 = -1e18;
        }

        /** Build {@link #whole}. */
        Tree joined() {
            whole = new Path2D.Double();
            wholeBox = new double[]{1e18, 1e18, -1e18, -1e18};
            List<double[]> all = new ArrayList<>(lines);
            all.add(main);
            for (double[] p : all) for (int k = 0; k + 1 < p.length; k += 2) {
                if (k == 0) whole.moveTo(p[0], p[1]); else whole.lineTo(p[k], p[k + 1]);
                wholeBox[0] = Math.min(wholeBox[0], p[k]); wholeBox[2] = Math.max(wholeBox[2], p[k]);
                wholeBox[1] = Math.min(wholeBox[1], p[k + 1]); wholeBox[3] = Math.max(wholeBox[3], p[k + 1]);
            }
            return this;
        }

        /**
         * Make {@link #glowShape} and {@link #coreShape} for strokes of {@code iz} world px per unit of width (it follows the zoom:
         * {@link Lightning#strokeScale}). Frame thread only, before the bands draw; rebuilt only when the zoom changes.
         */
        void shape(float iz) {
            if (iz == shapeIz || lines.isEmpty()) return;
            Path2D.Double gl = new Path2D.Double(), co = new Path2D.Double();
            for (int n = 0; n < lines.size(); n++) {
                ribbon(gl, lines.get(n), strength.get(n), 2.5 * iz, 0.12);
                ribbon(co, lines.get(n), strength.get(n), 0.9 * iz, 0.22);
            }
            glowShape = gl; coreShape = co; shapeIz = iz;
        }

        /**
         * A polyline as one outline: down its left side and back up its right, mitred at the points (two edges a piece, which
         * is what makes the fill cheap). The half width at a point is {@code half} at strength 1 and {@code floor} of it at
         * strength 0. Every branch is wound the same way, so under the non-zero rule branches lying over each other stay filled.
         */
        private static void ribbon(Path2D.Double out, double[] p, double[] s, double half, double floor) {
            int m = p.length / 2;
            double[] ox = new double[m], oy = new double[m];
            for (int k = 0; k < m; k++) {
                // the mitre: the mean of the normals of the two pieces that meet here, stretched to keep the width (at most doubled)
                double ax = 0, ay = 0, bx = 0, by = 0;
                if (k > 0) { double dx = p[2 * k] - p[2 * k - 2], dy = p[2 * k + 1] - p[2 * k - 1], l = Math.max(1e-9, Math.hypot(dx, dy)); ax = -dy / l; ay = dx / l; }
                if (k + 1 < m) { double dx = p[2 * k + 2] - p[2 * k], dy = p[2 * k + 3] - p[2 * k + 1], l = Math.max(1e-9, Math.hypot(dx, dy)); bx = -dy / l; by = dx / l; }
                if (k == 0) { ax = bx; ay = by; } else if (k + 1 == m) { bx = ax; by = ay; }
                double nx = ax + bx, ny = ay + by, dot = Math.max(0.5, 1 + ax * bx + ay * by), h = half * (floor + (1 - floor) * s[k]);
                ox[k] = nx / dot * h; oy[k] = ny / dot * h;
            }
            out.moveTo(p[0] + ox[0], p[1] + oy[0]);
            for (int k = 1; k < m; k++) out.lineTo(p[2 * k] + ox[k], p[2 * k + 1] + oy[k]);
            for (int k = m - 1; k >= 0; k--) out.lineTo(p[2 * k] - ox[k], p[2 * k + 1] - oy[k]);
            out.closePath();
        }

        /** The hidden channel: a trunk from the origin up into the cloud body and a few tendrils off it, all where the cloud is opaque. */
        private void intoCloud(Random r, double[] pts, Mask mask, double cloudX, double baseY, double topY, double[] buf) {
            double ox = pts[0], oy = pts[1], x = ox, y = oy, ph = 0, tall = Math.max(40, baseY - topY);
            double rise = Math.min(420, tall * (0.35 + 0.35 * r.nextDouble())), step = Model.clamp(tall / 9, 12, 34);     // (kinks wider than the glow is soft, or the channel reads as a straight beam)
            int m = 1, first = mask.at(x, y) >= 0.5 ? 0 : -1;     // first: the first point the cloud covers
            buf[0] = x; buf[1] = y;
            while (oy - y < rise && m < 20) {
                double nx = 0, ny = 0;
                boolean ok = false, in = false;
                for (int tries = 0; tries < 4 && !ok; tries++) {
                    double a = Model.clamp(0.6 * ph + r.nextGaussian() * (first < 0 ? 0.2 : 0.5) + Model.clamp((cloudX - x) / 300, -0.4, 0.4), -1.1, 1.1), len = step * (0.7 + 0.7 * r.nextDouble());
                    nx = x + Math.sin(a) * len; ny = y - Math.cos(a) * len;
                    in = mask.at(nx, ny) >= 0.5;
                    ok = ny > topY + 12 && (in || (first < 0 && m < 7));     // (under an uneven base the first steps are in clear air)
                    if (ok) ph = a;
                }
                if (!ok) break;
                x = nx; y = ny;
                buf[2 * m] = x; buf[2 * m + 1] = y;
                if (first < 0 && in) first = m;
                m++;
            }
            if (first < 0 || m - first < 2) return;          // it never got into the cloud
            if (first > 0) {
                // what is in clear air is part of the visible channel
                main = new double[2 * first + pts.length];
                for (int k = 0; k < first; k++) { main[2 * k] = buf[2 * (first - k)]; main[2 * k + 1] = buf[2 * (first - k) + 1]; }
                System.arraycopy(pts, 0, main, 2 * first, pts.length);
            }
            List<float[]> segs = new ArrayList<>();
            double[] trunk = Arrays.copyOfRange(buf, 2 * first, 2 * m);
            int tn = m - first;
            hidden.add(trunk);
            for (int k = 0; k + 1 < tn; k++) segs.add(new float[]{(float) trunk[2 * k], (float) trunk[2 * k + 1], (float) trunk[2 * k + 2], (float) trunk[2 * k + 3], (float) (1 - 0.45 * (k + 0.5) / (tn - 1))});
            // tendrils: x, y, heading (angle from straight up), length to spend, strength, level
            ArrayDeque<double[]> q = new ArrayDeque<>();
            for (int k = 2 + r.nextInt(3); k > 0; k--) {
                int at = 1 + r.nextInt(tn - 1);
                q.add(new double[]{trunk[2 * at], trunk[2 * at + 1], side(r) * (0.9 + 1.0 * r.nextDouble()), 50 + 170 * r.nextDouble(), 0.8 - 0.3 * at / (tn - 1), 1});
            }
            while (!q.isEmpty() && segs.size() < MAX_CLOUD_SEG) {
                double[] b = q.poll();
                double a = b[2], used = 0;
                x = b[0]; y = b[1];
                m = 1;
                buf[0] = x; buf[1] = y;
                while (used < b[3] && segs.size() < MAX_CLOUD_SEG) {
                    double nx = 0, ny = 0, len = 0, na = 0;
                    boolean ok = false;
                    for (int tries = 0; tries < 3 && !ok; tries++) {
                        na = Model.clamp(0.7 * a + 0.3 * b[2] + r.nextGaussian() * 0.4, -2.0, 2.0);
                        len = step * (0.7 + 0.7 * r.nextDouble());
                        nx = x + Math.sin(na) * len; ny = y - Math.cos(na) * len;
                        ok = ny > topY + 12 && mask.at(nx, ny) >= 0.5;
                    }
                    if (!ok) break;
                    double s0 = b[4] * (1 - 0.65 * used / b[3]);
                    used += len; a = na;
                    double s1 = b[4] * (1 - 0.65 * Math.min(1, used / b[3]));
                    segs.add(new float[]{(float) x, (float) y, (float) nx, (float) ny, (float) ((s0 + s1) / 2)});
                    x = nx; y = ny;
                    buf[2 * m] = x; buf[2 * m + 1] = y;
                    m++;
                    if (b[5] < 2 && b[3] - used > 3 * step && r.nextDouble() < 0.2)
                        q.add(new double[]{x, y, Model.clamp(a + side(r) * (0.6 + 0.5 * r.nextDouble()), -2.0, 2.0), (b[3] - used) * 0.7, s1 * 0.8, b[5] + 1});
                }
                if (m >= 2) hidden.add(Arrays.copyOf(buf, 2 * m));
            }
            glow = new float[segs.size() * 5];
            for (int k = 0; k < segs.size(); k++) {
                float[] s = segs.get(k);
                System.arraycopy(s, 0, glow, 5 * k, 5);
                gx0 = Math.min(gx0, Math.min(s[0], s[2])); gx1 = Math.max(gx1, Math.max(s[0], s[2]));
                gy0 = Math.min(gy0, Math.min(s[1], s[3])); gy1 = Math.max(gy1, Math.max(s[1], s[3]));
            }
        }
    }

    // ------------------------------------------------------------------ per view (kept in Shared) and per frame

    /** What one view remembers between frames. Only touched by renderer 0 in {@link #prepare}. */
    static final class State {
        final Map<Long, Light> lights = new HashMap<>();
        final Safe safe = new Safe();
        long worldVer = Long.MIN_VALUE, lastClock;
        /** The {@code Shared.advVer} the lights and the safe bolts were made under: they are made again when an Advanced option changes. */
        long advVer;
        boolean started;
    }

    /** Everything the bands need to draw the lightning of one frame; read-only while they run. */
    static final class Frame {
        /** The lights within reach of the view (empty when {@link #safe}). */
        Light[] lights = new Light[0];
        /** Every bolt to stroke, and how strongly (0..1). */
        double[][] strokes = new double[0][];
        double[] strokeA = new double[0];
        /** Per bolt of {@link #strokes}: its branches. */
        Tree[] trees = new Tree[0];
        boolean safe;
        /** Settings > Advanced "Global lightning": {@link #lights} are all the bolts there are, and together they light the whole view by one level. */
        boolean global;
        /** "No tint": the light is a neutral white instead of the cold one. */
        boolean neutral;
        /** See {@link State#advVer}: a grid traced under other options is not reused. */
        long advVer;
        /** Device pixels per node of the light grid. */
        int texel = 4;
        /** Lit surfaces are multiplied by up to 1 + gain (dark scenes more: the flash shows the daylight colours), and the air gains {@code haze} of the light colour. */
        double gain = 2, haze = 0.26;
    }

    /**
     * Decide what this frame shows. Returns null when there is nothing at all to draw (the usual case).
     * @param live false for a single still picture: no history, bolts exactly as the frame lists them
     * @param sceneLight how bright the scene is drawn, 0..1 (daylight times what the under-cloud veil leaves)
     */
    static Frame prepare(State st, Model.Frame f, Model.Obj[] objs, Model.Terrain terr, List<Shared.DrawCloud> clouds, Camera.View v, long clock, boolean live,
                         boolean safe, double sceneLight, long worldVer) {
        return prepare(st, f, objs, terr, clouds, v, clock, live, safe, sceneLight, worldVer, Advanced.DEFAULT, 0);
    }

    /** How strongly "Global lightning" lights the view for one bolt at full strength, against 1 = right at a bolt's own light. */
    static final double GLOBAL_LEVEL = 0.5;

    /**
     * @param adv the Advanced options of this frame. Epilepsy-safe ({@code safe}) is tested first and wins over every one of
     *            them that concerns light; then "No flash" (no light, the bolts as always), then "Global lightning" (one even
     *            level, no shadows, no glow), then "Lightning detail off" (the local lights without shadows and glow).
     *            "Lightning branching off" takes the branches off the safe bolts too.
     * @param advVer changes whenever {@code adv} does (see {@link State#advVer})
     */
    static Frame prepare(State st, Model.Frame f, Model.Obj[] objs, Model.Terrain terr, List<Shared.DrawCloud> clouds, Camera.View v, long clock, boolean live,
                         boolean safe, double sceneLight, long worldVer, Advanced adv, long advVer) {
        if (f.lightning.isEmpty() && st.lights.isEmpty() && st.safe.idle()) { st.lastClock = clock; st.started = true; return null; }
        double dt = st.started ? Model.clamp((clock - st.lastClock) / 1e9, 0, 0.1) : 0;
        st.lastClock = clock; st.started = true;
        if (worldVer != st.worldVer || advVer != st.advVer) { st.lights.clear(); st.safe.clear(); st.worldVer = worldVer; st.advVer = advVer; }
        final boolean branching = !adv.branchingOff, noFlash = adv.noFlash, global = adv.globalLightning && !noFlash;
        final boolean plain = noFlash || global || adv.lightningDetailOff;     // no shadow maps, no channel in the cloud
        Frame out = new Frame();
        out.safe = safe;
        out.advVer = advVer;
        out.neutral = adv.noTint;
        if (safe) {
            st.lights.clear();
            st.safe.advance(f.lightning, clock, live, terr, branching);
            out.strokes = st.safe.pts();
            out.trees = st.safe.trees();
            out.strokeA = st.safe.levels(clock, live);
            return out.strokes.length == 0 ? null : out;
        }
        st.safe.clear();
        for (Light l : st.lights.values()) l.present = false;
        for (Model.Bolt b : f.lightning) {
            long key = key(b);
            Light l = st.lights.get(key);
            if (l == null) { l = new Light(key, b.pts, terr, clouds, !plain, branching); st.lights.put(key, l); }     // once: a bolt does not move (and its cloud hardly, while it lasts)
            l.present = true;
            l.alpha = Model.clamp(b.alpha, 0, 1);
        }
        List<Light> lit = new ArrayList<>();
        for (Iterator<Light> it = st.lights.values().iterator(); it.hasNext(); ) {
            Light l = it.next();
            if (!l.present) {
                // the stream dropped it (its cloud died, the stage was cleared): fade what was still showing, never pop
                if (!live) { it.remove(); continue; }
                l.alpha -= dt * GHOST_RATE;
            }
            if (l.alpha <= 0.004) { if (!l.present) it.remove(); continue; }
            if (noFlash) continue;                           // nothing is lit, so no shadow map is ever built
            if (global) { lit.add(l); continue; }            // wherever it is: no reach, no shadow map
            if (!l.sees(v.wx0, v.wy0, v.wx1, v.wy1)) continue;
            if (l.em == null) l.build(objs, terr, !plain);
            lit.add(l);
        }
        out.lights = lit.toArray(new Light[0]);
        out.global = global;
        int n = 0;
        out.strokes = new double[st.lights.size()][];
        out.strokeA = new double[st.lights.size()];
        out.trees = new Tree[st.lights.size()];
        float iz = strokeScale(v);
        for (Light l : st.lights.values()) if (l.alpha > 0.004) { l.tree.shape(iz); out.strokes[n] = l.tree.main; out.trees[n] = l.tree; out.strokeA[n++] = l.alpha; }
        out.strokes = Arrays.copyOf(out.strokes, n);
        out.trees = Arrays.copyOf(out.trees, n);
        out.strokeA = Arrays.copyOf(out.strokeA, n);
        if (n == 0) return null;
        double devW = v.dvx1 - v.dvx0, devH = v.dvy1 - v.dvy0;
        out.texel = (int) Model.clamp(Math.round(Math.sqrt(devW * devH / 60000.0)), 3, 8);
        out.gain = Model.clamp(1 / Math.max(0.2, sceneLight) - 0.8, 0.2, 4);
        return out;
    }

    /** The drawn cloud the bolt hangs from: its base and top bound the glow, its body holds the hidden channel. Without one the light simply ends a little above the origin. */
    private static void host(Light l, List<Shared.DrawCloud> clouds) {
        double ox = l.pts[0], oy = l.pts[1], best = 1e18;
        l.baseY = oy; l.topY = oy - 150;
        for (Shared.DrawCloud dc : clouds) {
            Model.Cloud c = dc.c;
            if (c.form <= 0.02) continue;
            double sx = dc.x - c.x, sy = dc.y - c.y, x0 = c.x - c.w / 2, x1 = c.x + c.w / 2;
            for (double[] p : c.puffs) { x0 = Math.min(x0, c.x + p[0] - p[2]); x1 = Math.max(x1, c.x + p[0] + p[2]); }
            if (ox < x0 + sx || ox > x1 + sx) continue;
            double top = Renderer.cloudTop(c) + sy, base = Renderer.cloudBase(c) + sy, miss = Math.abs(base - oy);
            if (oy < top - 20 || miss > Math.max(90, c.h * 0.6) || miss >= best) continue;
            best = miss;
            l.topY = Math.min(top, oy - 40);
            l.hostC = c; l.hostE = dc.e; l.hostX = dc.x; l.hostY = dc.y;
        }
    }

    // ------------------------------------------------------------------ epilepsy-safe bolts

    /**
     * The bolts of the epilepsy-safe mode: each one eases in and out on the viewer's clock whatever the
     * stream's alpha does (it jumps from 0 to 1 in one frame), stays dim, and at most one starts every
     * {@link #MIN_GAP} seconds, the others are left out: never more than three flashes a second.
     */
    static final class Safe {
        static final double RISE = 0.40, HOLD = 0.15, FALL = 0.75, PEAK = 0.34, MIN_GAP = 0.45;
        private static final class E { double[] pts; long t0; boolean shown; Tree tree; }
        private final Map<Long, E> known = new HashMap<>();
        private long lastStart;
        private boolean any;
        private final List<E> active = new ArrayList<>();

        boolean idle() { return known.isEmpty(); }

        void clear() { known.clear(); active.clear(); }

        /** (The branches of a safe bolt are the same as with the setting off, but as dim as its channel; it has no channel in the cloud: that is light.) */
        void advance(List<Model.Bolt> bolts, long clock, boolean live, Model.Terrain terr) { advance(bolts, clock, live, terr, true); }

        void advance(List<Model.Bolt> bolts, long clock, boolean live, Model.Terrain terr, boolean branching) {
            if (!live) {
                clear();
                for (Model.Bolt b : bolts) { E e = new E(); e.pts = b.pts; e.t0 = clock; e.shown = true; e.tree = Tree.grow(b.pts, key(b), terr, null, 0, 0, 0, branching).joined(); known.put(key(b), e); }
            } else {
                java.util.Set<Long> seen = new java.util.HashSet<>();
                for (Model.Bolt b : bolts) {
                    long k = key(b);
                    seen.add(k);
                    if (known.containsKey(k)) continue;
                    E e = new E();
                    e.pts = b.pts; e.t0 = clock;
                    e.shown = !any || (clock - lastStart) / 1e9 >= MIN_GAP;
                    if (e.shown) { any = true; lastStart = clock; e.tree = Tree.grow(b.pts, k, terr, null, 0, 0, 0, branching).joined(); }
                    known.put(k, e);
                }
                // an entry lives until its pulse is over AND the stream no longer lists the bolt (a paused sim keeps listing it: one pulse, not a loop)
                known.entrySet().removeIf(en -> !seen.contains(en.getKey()) && (!en.getValue().shown || (clock - en.getValue().t0) / 1e9 >= RISE + HOLD + FALL));
            }
            active.clear();
            for (E e : known.values()) if (e.shown && (!live || (clock - e.t0) / 1e9 < RISE + HOLD + FALL)) active.add(e);
        }

        double[][] pts() {
            double[][] p = new double[active.size()][];
            for (int k = 0; k < p.length; k++) p[k] = active.get(k).pts;
            return p;
        }

        Tree[] trees() {
            Tree[] t = new Tree[active.size()];
            for (int k = 0; k < t.length; k++) t[k] = active.get(k).tree;
            return t;
        }

        double[] levels(long clock, boolean live) {
            double[] a = new double[active.size()];
            for (int k = 0; k < a.length; k++) a[k] = live ? level((clock - active.get(k).t0) / 1e9) : PEAK;
            return a;
        }

        /** Opacity of a bolt {@code age} seconds after it was first seen. */
        static double level(double age) {
            if (age <= 0 || age >= RISE + HOLD + FALL) return 0;
            return PEAK * Lit.smooth(age / RISE) * (1 - Lit.smooth((age - RISE - HOLD) / FALL));
        }
    }

    // ------------------------------------------------------------------ the light of one render band

    /** Light colour (a cold white: red and green are 7/8 of the blue, see {@code Direct.lightRow}). */
    static final int TINT_R = 223, TINT_G = 223, TINT_B = 255;

    /**
     * One band's rows of the light grid, and their application to the pixels. One instance per {@link Renderer}.
     * <p>
     * The light of a bolt on the grid does not change while the view stands still (only its strength does), so it is
     * evaluated once per bolt and view ({@link Grid}) and afterwards only scaled and summed: the ray lookups are paid on the
     * bolt's first frame and on frames where the camera moves.
     */
    static final class Band {
        /** The light of one bolt at full strength on this band's grid rows, for one view. */
        private static final class Grid {
            float[] u = new float[0];
            double ws;
            int wtx, wty, ox, oy, S, nx, j0, rows;
            long advVer = Long.MIN_VALUE;
            boolean used;
        }

        private final Map<Long, Grid> grids = new HashMap<>();
        private float[] acc = new float[0];
        /** Light 0..4080 at the grid nodes, {@link #nx} per row. */
        private int[] k = new int[0];
        private int[] lo = new int[0], hi = new int[0];
        private int nx, rows, j0, S, ox, oy, ya, yb, xa, xb;
        /** How many times a bolt's light had to be ray traced onto the grid (diagnostics, tests). */
        long traces;

        /** No lightning this frame: forget the grids of the bolts that are over. */
        void idle() { if (!grids.isEmpty()) grids.clear(); }

        /** Evaluate the lights on the grid rows that device rows [by0, by1) need. False when none of them reaches this band. */
        boolean compute(Frame fr, Camera.View v, int by0, int by1) {
            S = fr.texel;
            ox = v.dvx0; oy = v.dvy0;
            xa = v.dvx0; xb = v.dvx1;
            ya = Math.max(by0, v.dvy0); yb = Math.min(by1, v.dvy1);
            if (ya >= yb || xa >= xb) { grids.clear(); return false; }
            nx = (xb - xa + S - 1) / S + 1;
            j0 = (ya - oy) / S;
            rows = (yb - 1 - oy) / S + 1 - j0 + 1;
            final double ws = v.ws, bwy0 = (ya - S - v.wty) / ws, bwy1 = (yb + S - v.wty) / ws;
            int size = nx * rows;
            if (fr.global) {
                // one level for every node: the unchanged apply / paint then spread it over the band like any other light
                if (!grids.isEmpty()) grids.clear();
                double sum = 0;
                for (Light l : fr.lights) sum += l.alpha * l.alpha;
                int q = (int) (4080 * GLOBAL_LEVEL * Math.min(1, sum));
                if (q < 8) return false;
                if (acc.length < size) { acc = new float[size]; k = new int[size]; }
                if (lo.length < rows) { lo = new int[rows]; hi = new int[rows]; }
                Arrays.fill(k, 0, size, q);
                Arrays.fill(lo, 0, rows, 0);
                Arrays.fill(hi, 0, rows, nx - 1);
                return true;
            }
            boolean any = false;
            for (Grid g : grids.values()) g.used = false;
            for (Light l : fr.lights) {
                if (l.y1 <= bwy0 || l.y0 >= bwy1) continue;
                Grid g = grids.get(l.key);
                if (g == null) { g = new Grid(); grids.put(l.key, g); }
                g.used = true;
                if (g.advVer != fr.advVer || g.ws != ws || g.wtx != v.wtx || g.wty != v.wty || g.ox != ox || g.oy != oy || g.S != S || g.nx != nx || g.j0 != j0 || g.rows != rows) {
                    g.advVer = fr.advVer;
                    g.ws = ws; g.wtx = v.wtx; g.wty = v.wty; g.ox = ox; g.oy = oy; g.S = S; g.nx = nx; g.j0 = j0; g.rows = rows;
                    if (g.u.length < size) g.u = new float[size]; else Arrays.fill(g.u, 0, size, 0f);
                    trace(l, g.u, v);
                    traces++;
                }
                if (!any) {
                    if (acc.length < size) { acc = new float[size]; k = new int[size]; }
                    Arrays.fill(acc, 0, size, 0f);
                    any = true;
                }
                final float power = (float) (l.alpha * l.alpha);
                final float[] u = g.u, a = acc;
                for (int o = 0; o < size; o++) a[o] += power * u[o];
            }
            grids.values().removeIf(g -> !g.used);
            if (!any) return false;
            if (lo.length < rows) { lo = new int[rows]; hi = new int[rows]; }
            boolean lit = false;
            for (int r = 0, o = 0; r < rows; r++) {
                int a = nx, b = -1;
                for (int i = 0; i < nx; i++, o++) {
                    float x = acc[o];
                    int q = x >= 1 ? 4080 : (int) (x * 4080);
                    if (q < 8) q = 0;                      // under half a grey level: nothing
                    else { if (i < a) a = i; b = i; }
                    k[o] = q;
                }
                lo[r] = a; hi[r] = b;
                lit |= b >= 0;
            }
            return lit;
        }

        /** Ray trace one bolt's lights onto the grid: per node the sum over its emitters of gain x falloff x visibility, and the glow inside its cloud. */
        private void trace(Light l, float[] out, Camera.View v) {
            final double ws = v.ws;
            if (l.tree.glow.length > 0) glow(l, out, v);
            for (Emitter e : l.em) {
                final float[] tIn = e.tIn, tOut = e.tOut, fac = e.fac;
                final float invR2 = e.invReach2, invC2 = e.invCore2, hN = (FACE_N - 1) * 0.5f;
                for (int r = 0; r < rows; r++) {
                    double wy = (oy + (j0 + r) * S - v.wty) / ws, ddy = wy - e.y;
                    if (Math.abs(ddy) >= e.reach) continue;
                    float rowGain = (float) e.gain * l.cloudAtt(wy);
                    if (rowGain <= 0) continue;
                    double half = Math.sqrt(e.reach * e.reach - ddy * ddy);
                    int ia = Math.max(0, (int) Math.ceil((ws * (e.x - half) + v.wtx - ox) / S)), ib = Math.min(nx - 1, (int) Math.floor((ws * (e.x + half) + v.wtx - ox) / S));
                    final float dy = (float) ddy, ady = Math.abs(dy), dy2 = dy * dy;
                    final float wxStep = (float) (S / ws), footK = wxStep * hN * 1.5f;
                    float dx = (float) ((ox + ia * S - v.wtx) / ws - e.x);
                    for (int i = ia, o = r * nx + ia; i <= ib; i++, o++, dx += wxStep) {
                        float d2 = dx * dx + dy2, w = 1 - d2 * invR2;
                        if (w <= 0) continue;
                        if (e.open) { out[o] += rowGain * w * w / (1 + d2 * invC2); continue; }     // nothing in the way
                        float adx = Math.abs(dx), t, u;
                        int base;
                        if (adx >= ady) { t = adx; u = adx > 0 ? dy / adx : 0; base = dx > 0 ? 0 : 2 * FACE_N; }
                        else { t = ady; u = dx / ady; base = dy > 0 ? FACE_N : 3 * FACE_N; }
                        float fb = (u + 1) * hN, foot = footK / t, vv;
                        if (foot <= 1) {
                            // far from the light a grid node is narrower than a direction: blend the two around it
                            int q = (int) fb;
                            if (q > FACE_N - 2) q = FACE_N - 2;
                            float v0 = vis(tIn, tOut, fac, base + q, t), v1 = vis(tIn, tOut, fac, base + q + 1, t);
                            vv = v0 + (v1 - v0) * (fb - q);
                        } else {
                            // near it a node spans many directions: four taps across its width, or shadow edges would crawl in steps
                            if (foot > 32) foot = 32;
                            float c = fb + 0.5f;
                            vv = 0.25f * (vis(tIn, tOut, fac, base + tap(c - 0.375f * foot), t) + vis(tIn, tOut, fac, base + tap(c - 0.125f * foot), t)
                                    + vis(tIn, tOut, fac, base + tap(c + 0.125f * foot), t) + vis(tIn, tOut, fac, base + tap(c + 0.375f * foot), t));
                        }
                        if (vv <= 0) continue;
                        out[o] += rowGain * vv * w * w / (1 + d2 * invC2);
                    }
                }
            }
        }

        /**
         * The channel inside the cloud: a line light without shadows, strongest along the channel, and only as strong as the
         * host cloud is opaque at the node. So the cloud glows from within and the clear sky around it stays dark.
         */
        private void glow(Light l, float[] out, Camera.View v) {
            final Tree t = l.tree;
            final float[] gs = t.glow;
            final double ws = v.ws, R = Tree.GLOW_REACH;
            final float R2 = (float) (R * R), invR2 = 1 / R2, invA = (float) (1 / (Tree.GLOW_NEAR * Tree.GLOW_NEAR)), invB = (float) (1 / (Tree.GLOW_WIDE * Tree.GLOW_WIDE));
            final float gain = (float) Tree.GLOW_GAIN;
            for (int r = 0; r < rows; r++) {
                double wy = (oy + (j0 + r) * S - v.wty) / ws;
                if (wy < t.gy0 - R || wy > t.gy1 + R) continue;
                int ia = Math.max(0, (int) Math.ceil((ws * (t.gx0 - R) + v.wtx - ox) / S)), ib = Math.min(nx - 1, (int) Math.floor((ws * (t.gx1 + R) + v.wtx - ox) / S));
                final float py = (float) wy;
                for (int i = ia, o = r * nx + ia; i <= ib; i++, o++) {
                    double wx = (ox + i * S - v.wtx) / ws;
                    float m = l.cover(wx, wy);
                    if (m < 0.02f) continue;
                    float best = 0, px = (float) wx;
                    for (int q = 0; q < gs.length; q += 5) {
                        float ax = gs[q], ay = gs[q + 1], bx = gs[q + 2] - ax, by = gs[q + 3] - ay, ux = px - ax, uy = py - ay;
                        float h = (ux * bx + uy * by) / (bx * bx + by * by + 1e-6f);
                        h = h < 0 ? 0 : h > 1 ? 1 : h;
                        float dx = ux - h * bx, dy = uy - h * by, d2 = dx * dx + dy * dy;
                        if (d2 >= R2) continue;
                        float w = 1 - d2 * invR2, val = gs[q + 4] * w * w * (0.6f / (1 + d2 * invA) + 0.4f / (1 + d2 * invB));
                        if (val > best) best = val;
                    }
                    out[o] += gain * best * m;
                }
            }
        }

        /** The light 0..255 at device pixel (x, y) of the band last computed (tests). */
        int at(int x, int y) {
            int gy = y - oy, r = gy / S - j0, fy = gy - (gy / S) * S, gx = x - ox, i = gx / S, fx = gx - i * S;
            int c0 = k[r * nx + i] * (S - fy) + k[(r + 1) * nx + i] * fy, c1 = k[r * nx + i + 1] * (S - fy) + k[(r + 1) * nx + i + 1] * fy;
            return ((c0 * S + (c1 - c0) * fx) * mul()) >> 16;
        }

        private int mul() { return (65536 + 8 * S * S) / (16 * S * S); }

        /** Light the band's pixels of an opaque int target in place. */
        void apply(Direct d, Frame fr) {
            int gm = (int) Math.round(fr.gain * 256), hz = (int) Math.round(fr.haze * 256), M = mul();
            int a = nx, b = -1;
            for (int r = 0; r < rows; r++) { a = Math.min(a, lo[r]); b = Math.max(b, hi[r]); }
            if (b < 0) return;
            // the columns any row of the band lights, and per grid row the light spread over them
            int bxa = Math.max(xa, ox + Math.max(0, a - 1) * S), bxb = Math.min(xb, ox + (b + 1) * S);
            int[] hA = d.lightScratch(0), hB = d.lightScratch(1);
            int have = -1;
            for (int y = Math.max(ya, d.cy0), ye = Math.min(yb, d.cy1); y < ye; y++) {
                int gy = y - oy, r = gy / S - j0, fy = gy - (gy / S) * S;
                int la = Math.min(lo[r], lo[r + 1]), lb = Math.max(hi[r], hi[r + 1]);
                if (lb < 0) continue;
                if (r != have) {
                    if (r == have + 1 && have >= 0) { int[] t = hA; hA = hB; hB = t; }
                    else d.lightSpread(k, r * nx, ox, S, bxa, bxb, hA);
                    d.lightSpread(k, (r + 1) * nx, ox, S, bxa, bxb, hB);
                    have = r;
                }
                d.lightRow(y, Math.max(bxa, ox + (la - 1) * S), Math.min(bxb, ox + (lb + 1) * S), hA, hB, S - fy, fy, M, gm, hz, fr.neutral);
            }
        }

        /**
         * The same light for a target without direct pixel access: a translucent image of the light colour
         * drawn over the band ({@code g} at the identity transform). Plain Java2D cannot multiply, so here
         * the light only brightens toward its colour.
         */
        void paint(Graphics2D g, Frame fr) {
            int a = nx, b = -1;
            for (int r = 0; r < rows; r++) { a = Math.min(a, lo[r]); b = Math.max(b, hi[r]); }
            if (b < 0) return;
            int px0 = Math.max(xa, ox + Math.max(0, a - 1) * S), px1 = Math.min(xb, ox + (b + 1) * S + 1), w = px1 - px0, h = yb - ya;
            if (w <= 0 || h <= 0) return;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
            int[] p = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
            int fa = (int) Math.round(Model.clamp(0.30 + 0.07 * fr.gain, 0, 0.6) * 256), M = mul();
            final int tr = fr.neutral ? 255 : TINT_R, tg = fr.neutral ? 255 : TINT_G;
            for (int y = ya; y < yb; y++) {
                int gy = y - oy, r = gy / S - j0, fy = gy - (gy / S) * S, o = (y - ya) * w;
                for (int x = px0; x < px1; x++, o++) {
                    int gx = x - ox, i = gx / S, fx = gx - i * S;
                    int c0 = k[r * nx + i] * (S - fy) + k[(r + 1) * nx + i] * fy, c1 = k[r * nx + i + 1] * (S - fy) + k[(r + 1) * nx + i + 1] * fy;
                    int al = ((((c0 * S + (c1 - c0) * fx) * M) >> 16) * fa) >> 8;
                    if (al <= 0) continue;
                    p[o] = (al << 24) | ((tr * al / 255) << 16) | ((tg * al / 255) << 8) | (TINT_B * al / 255);
                }
            }
            g.drawImage(img, px0, ya, null);
        }
    }
}
