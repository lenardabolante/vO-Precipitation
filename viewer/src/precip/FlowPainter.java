package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Protocol v2.4.1 overland flow: the film of water that runs over the ground and over a settlement's street
 * ({@code frame.flow}), drawn as one continuous ribbon along the terrain. Its top edge stands the film's depth
 * {@code h} above the ground (times {@code sqrt(1 + slope²)}, so the thickness is measured across the stream), so the
 * stream is as thick as the simulation says the flow is: a thin sheet under a steady rain, several pixels off a
 * flooded street. It is never above the ground by more than that, never beyond its cells and never inside a body of
 * standing water: where a run meets a body the ribbon ends at the shoreline and glides onto the level surface.
 * <p>
 * The cells arrive about ten times a second. {@link #prepare} (renderer 0, once per frame, into {@link Shared#flow})
 * eases every cell's depth and flux towards the newest values, fades a new cell in and a vanished one out, and lays
 * the ribbons of this frame out; the bands only read the result ({@link #paint}), so every band draws the same water.
 * It is a live pass: nothing of it is in a baked tile. A stream without {@code hello.flow} draws nothing here.
 */
final class FlowPainter {
    private FlowPainter() {}

    /** Time constant of the easing of a cell's depth and flux, and the time a cell takes to fade in or out, seconds. */
    static final double EASE_S = 0.12, FADE_S = 0.30;
    /** A cell thinner than this is not drawn, world px (the wire sends from 0.05: a damp film does not show, a thin runoff does). */
    static final double MIN_H_DRAW = 0.15;
    /** White water: where the ground is at least this steep, or the stream at least this fast (px/s). */
    static final double WHITE_SLOPE = 0.6, WHITE_SPEED = 30;
    /**
     * Where the ground is at least this steep the water leaves it in a short free fall with mist at its foot.
     * {@code Double.POSITIVE_INFINITY} switches the fall off (the stream then clings to every slope).
     */
    static final double CLIFF_SLOPE = 2.0;
    /** The thickness factor {@code sqrt(1 + slope²)} is not taken beyond this (a wall would give a ribbon of any height). */
    static final double SEC_MAX = Math.sqrt(1 + Math.min(CLIFF_SLOPE, 3) * Math.min(CLIFF_SLOPE, 3));
    /** A shoreline within this many cells of the end cell of a wet stretch: the ribbon runs on to it at the end cell's depth. */
    static final double REACH_CELLS = 1.5;
    /** Where a ribbon meets a body its surface glides down to the level over this many times its depth (between the two limits, world px). */
    static final double TOE = 2.5, TOE_MIN = 1.5, TOE_MAX = 12;
    /** Where a body runs over its rim into a ribbon, the ribbon grows from nothing at the shoreline to its depth over this many world px. */
    static final double GROW = 10;
    /** The moving lights on the surface: one every {@code GLINT_S} seconds of travel, this much brighter than the line they ride on (of 255). */
    static final double GLINT_S = 0.9;
    static final int GLINT_ALPHA = 60;
    /** A light rides only where the film is at least this deep, world px. */
    static final double GLINT_MIN_H = 0.6;
    /** The bright line on the surface is at full strength from this depth (a film thinner than a pixel has only a faint one). */
    static final double SHEEN_FULL_H = 0.8;
    /** The film carries a bright line only where it is at least this deep, world px. */
    static final double SHEEN_MIN_H = 0.3;
    /** The ribbon's colour follows its depth in cuts this many device px wide (cut exactly between two pixel columns). */
    static final int FILL_PX = 48;
    /** The depth over which a film darkens from the surface colour to the deep colour, world px (a film is shallow: half of a body's). */
    static final double FILM_DEEP_PX = 6;
    static final int FOAM_PER_CELL = 3, SPRAY_PER_CELL = 2, SPRAY_MAX = 24;

    /** One ribbon of this frame: a stretch of wet cells between two dry ends or shorelines. World coordinates. */
    static final class Piece {
        /** Samples along the stretch: x, the ground, the top edge, the film's depth there, and the travel time from the first sample (signed by direction). */
        double[] x, ground, top, depth, s;
        double x0, x1, yMin, yMax, alphaMin;
        /**
         * The water as drawn: the ribbon in pieces of one colour each (their depth 0..1 of {@link #FILM_DEEP_PX}, whiteness and
         * opacity), and the bright line on its surface as a thin strip (null when the film is too thin for one) with its strength 0..1.
         */
        Path2D.Double[] fills;
        double[] fd, fw, fa;
        Path2D.Double line;
        double sheen;
        /** The values along x the colours are taken from: fraction of {@code x0..x1}, depth, how white (0..1), opacity (0..1). */
        float[] frac;
        double[] sh, sw, sa;
        /** White-water cells: centre x, speed, depth, opacity; direction. */
        double[] wx = NO_D, wu = NO_D, wh = NO_D, wa = NO_D;
        int[] wdir = new int[0], wk = new int[0];
        /** Free falls (one path for all of them) and the mist at their feet: x, the y it stands on, radius. */
        Path2D.Double fall;
        double[] mist = NO_D;
    }

    private static final double[] NO_D = new double[0];
    private static final Piece[] NONE = new Piece[0];

    /** What a view remembers of the film between frames. */
    static final class State {
        /** True when the stream carries the film ({@code hello.flow}). */
        boolean on;
        /** Width of a cell, world px; the number of cells across the world. */
        double cell = 32;
        int n;
        /** Per cell: the depth and flux as drawn, the newest values sent, the opacity, the direction (+1 / -1) and whether the newest list has it. */
        double[] h = NO_D, th = NO_D, q = NO_D, tq = NO_D, alpha = NO_D;
        byte[] dir = new byte[0];
        boolean[] live = new boolean[0];
        /** The ribbons of this frame; replaced every frame, never changed while the bands run. */
        Piece[] draw = NONE;
        private List<Model.Run> src;
        private Model.Hello hello;
        private long clock = Long.MIN_VALUE;

        /** The film's depth as drawn at world x: linear between cell centres (0 = none, or thinner than {@link #MIN_H_DRAW}). */
        double depthAt(double x) {
            if (!on || n == 0) return 0;
            double g = x / cell;
            int i = (int) Math.floor(g);
            double f = g - i;
            return shown(this, i) * (1 - f) + shown(this, i + 1) * f;
        }

        /** The cell at world x (the nearest centre), or -1 outside the world. */
        int cellAt(double x) {
            int k = (int) Math.round(x / cell);
            return k < 0 || k >= n ? -1 : k;
        }
    }

    private static double shown(State s, int k) {
        return k >= 0 && k < s.n && s.alpha[k] > 0.004 && s.h[k] >= MIN_H_DRAW ? s.h[k] : 0;
    }

    /**
     * Bring the view's film up to this frame and lay its ribbons out.
     * @param water the bodies of this frame (already eased): a ribbon ends at their shorelines
     * @param eased false = a still picture: every cell stands at its newest values, fully there
     */
    static void prepare(State s, Model.Hello hello, List<Model.Run> latest, WaterPainter.State water, Camera.View v, Model.Frame f, long clockNanos, boolean eased) {
        if (hello != s.hello) {
            s.hello = hello; s.src = null; s.clock = Long.MIN_VALUE;
            s.cell = hello != null && hello.flow != null ? hello.flow.cell : 32;
            s.n = hello != null && hello.flow != null ? (int) Math.ceil(hello.w / s.cell) + 2 : 0;
            s.h = new double[s.n]; s.th = new double[s.n]; s.q = new double[s.n]; s.tq = new double[s.n]; s.alpha = new double[s.n];
            s.dir = new byte[s.n]; s.live = new boolean[s.n];
        }
        s.on = hello != null && hello.flow != null;
        if (!s.on) { s.draw = NONE; return; }
        double dt = s.clock == Long.MIN_VALUE ? 0 : Model.clamp((clockNanos - s.clock) / 1e9, 0, 0.1);
        s.clock = clockNanos;
        step(s, latest, dt, !eased);
        s.draw = v == null ? NONE : layout(s, hello.terrain, water, v);
    }

    /** Take a new list (when it is one) and advance the easing by {@code dt} seconds. {@code snap} = no easing, no fading. */
    static void step(State s, List<Model.Run> latest, double dt, boolean snap) {
        if (latest != null && latest != s.src) {
            s.src = latest;
            Arrays.fill(s.live, false);
            Arrays.fill(s.th, 0);
            Arrays.fill(s.tq, 0);
            for (Model.Run r : latest) {
                for (int i = 0; i < r.h.length; i++) {
                    int k = r.k0 + r.dir * i;
                    if (k < 0 || k >= s.n) continue;
                    if (s.alpha[k] <= 0) { s.h[k] = r.h[i]; s.q[k] = r.q[i]; }       // a new cell appears at its depth and fades in
                    s.live[k] = true;
                    s.th[k] = r.h[i]; s.tq[k] = r.q[i];
                    s.dir[k] = (byte) r.dir;
                }
            }
        }
        final double k = snap ? 1 : 1 - Math.exp(-dt / EASE_S), fade = snap ? 1 : dt / FADE_S;
        for (int i = 0; i < s.n; i++) {
            if (!s.live[i]) {
                if (s.alpha[i] <= 0) continue;
                s.alpha[i] -= fade;
                if (s.alpha[i] <= 0) { s.alpha[i] = 0; s.h[i] = 0; s.q[i] = 0; }
                continue;
            }
            s.alpha[i] = Math.min(1, s.alpha[i] + fade);
            s.h[i] += (s.th[i] - s.h[i]) * k;
            s.q[i] += (s.tq[i] - s.q[i]) * k;
        }
    }

    private static double hash01(long n) {
        n ^= n >>> 33; n *= 0xff51afd7ed558ccdL; n ^= n >>> 33; n *= 0xc4ceb9fe1a85ec53L; n ^= n >>> 33;
        return (n >>> 11) / (double) (1L << 53);
    }

    /** The speed the viewer shows in a cell, px/s: {@code q / h}, kept between a crawl and a torrent. */
    static double speed(State s, int k) {
        return k < 0 || k >= s.n || s.h[k] <= 1e-6 ? 0 : Model.clamp(s.q[k] / s.h[k], 3, 90);
    }

    // ------------------------------------------------------------------ this frame's ribbons

    private static Piece[] layout(State s, Model.Terrain t, WaterPainter.State water, Camera.View v) {
        final double c = s.cell;
        final int k0 = (int) Math.max(0, Math.floor((v.wx0 - 3 * c) / c)), k1 = (int) Math.min(s.n - 1, Math.ceil((v.wx1 + 3 * c) / c));
        List<Piece> out = null;
        List<WaterPainter.Body> bodies = new ArrayList<>();
        if (water != null && water.on) for (WaterPainter.Body b : water.draw) if (b.alpha > 0.05 && b.x1 - b.x0 > 0.5) bodies.add(b);
        for (int k = k0; k <= k1; k++) {
            if (shown(s, k) <= 0) continue;
            final int a = k;
            while (k + 1 <= k1 && shown(s, k + 1) > 0) k++;
            final int b = k;
            final double ca = c * a, cb = c * b;
            // the ends: a dry end thins out over half a cell; a shoreline close by is where the ribbon ends, at the end cell's depth
            double x0 = ca - c / 2, x1 = cb + c / 2;
            boolean holdL = false, holdR = false;
            for (WaterPainter.Body w : bodies) {
                if (w.x1 < ca && w.x1 >= ca - REACH_CELLS * c && (!holdL || w.x1 > x0)) { x0 = w.x1; holdL = true; }
                if (w.x0 > cb && w.x0 <= cb + REACH_CELLS * c && (!holdR || w.x0 < x1)) { x1 = w.x0; holdR = true; }
            }
            // what is left of x0..x1 outside every body: {from, to, index of the body at the left end or -1, at the right end or -1}
            List<double[]> ivs = new ArrayList<>();
            ivs.add(new double[]{x0, x1, -1, -1});
            for (int j = 0; j < bodies.size(); j++) {
                WaterPainter.Body w = bodies.get(j);
                for (int i = ivs.size() - 1; i >= 0; i--) {
                    double[] iv = ivs.get(i);
                    if (w.x1 < iv[0] - 1e-9 || w.x0 > iv[1] + 1e-9) continue;
                    if (w.x1 <= iv[0] + 1e-9) { iv[2] = j; continue; }
                    if (w.x0 >= iv[1] - 1e-9) { iv[3] = j; continue; }
                    ivs.remove(i);
                    if (w.x1 < iv[1] - 0.5) ivs.add(i, new double[]{w.x1, iv[1], j, iv[3]});
                    if (w.x0 > iv[0] + 0.5) ivs.add(i, new double[]{iv[0], w.x0, iv[2], j});
                }
            }
            for (double[] iv : ivs) {
                Piece p = piece(s, t, v, a, b, holdL, holdR, iv[0], iv[1], iv[2] < 0 ? null : bodies.get((int) iv[2]), iv[3] < 0 ? null : bodies.get((int) iv[3]), bodies);
                if (p == null) continue;
                if (out == null) out = new ArrayList<>();
                out.add(p);
            }
        }
        return out == null ? NONE : out.toArray(NONE);
    }

    /** The film's depth at x inside the stretch of cells a..b ({@code hold}: the end cell's depth runs on to the end; else it thins out over half a cell). */
    private static double depth(State s, int a, int b, boolean holdL, boolean holdR, double x) {
        final double c = s.cell, ca = c * a, cb = c * b;
        if (x <= ca) return holdL ? s.h[a] : s.h[a] * Math.max(0, (x - (ca - c / 2)) / (c / 2));
        if (x >= cb) return holdR ? s.h[b] : s.h[b] * Math.max(0, ((cb + c / 2) - x) / (c / 2));
        double g = (x - ca) / c;
        int i = (int) Math.min(b - a - 1, Math.floor(g));
        double f = g - i;
        return s.h[a + i] * (1 - f) + s.h[a + i + 1] * f;
    }

    private static int cellOf(State s, int a, int b, double x) { return (int) Math.max(a, Math.min(b, Math.round(x / s.cell))); }

    /** How white the water is in a cell, 0..1: steep ground or a fast stream. */
    private static double white(State s, Model.Terrain t, int k) {
        final double c = s.cell, slope = Math.abs(t.at(c * k + c / 2) - t.at(c * k - c / 2)) / c, u = s.h[k] <= 1e-6 ? 0 : s.q[k] / s.h[k];
        return Math.max(Model.clamp((slope - WHITE_SLOPE * 0.75) / (WHITE_SLOPE * 0.5), 0, 1), Model.clamp((u - WHITE_SPEED * 0.8) / (WHITE_SPEED * 0.4), 0, 1));
    }

    private static Piece piece(State s, Model.Terrain t, Camera.View v, int a, int b, boolean holdL, boolean holdR, double x0, double x1,
                               WaterPainter.Body left, WaterPainter.Body right, List<WaterPainter.Body> bodies) {
        if (x1 - x0 < 0.25) return null;
        final double c = s.cell, st = t.step;
        // an end at a body the water runs AWAY from (the body is spilling over its rim there): the ribbon grows out of the shoreline
        final boolean growL = left != null && s.dir[cellOf(s, a, b, x0)] > 0, growR = right != null && s.dir[cellOf(s, a, b, x1)] < 0;
        // ---- the samples: both ends, every terrain vertex and every cell centre between them
        int cap = (int) ((x1 - x0) / st) + (int) ((x1 - x0) / c) + 8, n = 0;
        double[] xs = new double[cap];
        xs[n++] = x0;
        if (growL && x0 + GROW < x1) xs[n++] = x0 + GROW;
        if (growR && x1 - GROW > x0) xs[n++] = x1 - GROW;
        for (double x = (Math.floor(x0 / st) + 1) * st; x < x1 - 1e-6; x += st) if (x > x0 + 1e-6) xs[n++] = x;
        for (double x = (Math.floor(x0 / c) + 1) * c; x < x1 - 1e-6; x += c) if (x > x0 + 1e-6) xs[n++] = x;
        xs[n++] = x1;
        Arrays.sort(xs, 0, n);
        int m = 1;
        for (int i = 1; i < n; i++) if (xs[i] - xs[m - 1] > 1e-6) xs[m++] = xs[i];
        n = m;
        Piece p = new Piece();
        p.x = Arrays.copyOf(xs, n);
        p.ground = new double[n]; p.top = new double[n]; p.depth = new double[n]; p.s = new double[n];
        p.x0 = x0; p.x1 = x1;
        double maxD = 0, yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            final double x = p.x[i], g = t.at(x);
            double d = depth(s, a, b, holdL, holdR, x);
            if (growL) d *= Model.clamp((x - x0) / GROW, 0, 1);
            if (growR) d *= Model.clamp((x1 - x) / GROW, 0, 1);
            double r = x / st, sl = t.slope(x);
            if (Math.abs(r - Math.rint(r)) < 1e-6) sl = (t.slope(x - st * 0.5) + t.slope(x + st * 0.5)) * 0.5;      // at a kink: the mean of both sides
            p.ground[i] = g;
            p.depth[i] = d;
            p.top[i] = g - d * Math.min(SEC_MAX, Math.sqrt(1 + sl * sl));
            maxD = Math.max(maxD, d);
            yMin = Math.min(yMin, p.top[i]); yMax = Math.max(yMax, g);
        }
        if (maxD < MIN_H_DRAW * 0.5) return null;
        // ---- the travel time along the stretch (a light on the surface moves with the stream: dx = u dt)
        p.s[0] = hash01(a * 7919L + 11) * GLINT_S;
        for (int i = 1; i < n; i++) {
            int k = cellOf(s, a, b, (p.x[i] + p.x[i - 1]) * 0.5);
            p.s[i] = p.s[i - 1] + (s.dir[k] < 0 ? -1 : 1) * (p.x[i] - p.x[i - 1]) / Math.max(3, speed(s, k));
        }
        // ---- the colour stops: both ends and every cell centre between them
        int sn = 0;
        float[] fr = new float[b - a + 3];
        double[] sh = new double[fr.length], sw = new double[fr.length], sa = new double[fr.length];
        double aMin = 1;
        List<Integer> whiteCells = null;
        for (int j = -1; j <= b - a + 1; j++) {
            double x = j < 0 ? x0 : j > b - a ? x1 : c * (a + j);
            if (x < x0 - 1e-9 || x > x1 + 1e-9) continue;
            float f = (float) ((x - x0) / (x1 - x0));
            if (sn > 0 && f <= fr[sn - 1] + 1e-4f) continue;
            int k = cellOf(s, a, b, x);
            fr[sn] = f;
            sh[sn] = depth(s, a, b, true, true, x);
            sw[sn] = white(s, t, k);
            sa[sn] = s.alpha[k];
            aMin = Math.min(aMin, sa[sn]);
            sn++;
        }
        for (int k = a; k <= b; k++) {
            if (c * k < x0 - c / 2 || c * k > x1 + c / 2 || white(s, t, k) < 0.5) continue;
            if (whiteCells == null) whiteCells = new ArrayList<>();
            whiteCells.add(k);
        }
        p.frac = Arrays.copyOf(fr, sn); p.sh = Arrays.copyOf(sh, sn); p.sw = Arrays.copyOf(sw, sn); p.sa = Arrays.copyOf(sa, sn);
        p.alphaMin = aMin;
        if (whiteCells != null) {
            int wn = whiteCells.size();
            p.wx = new double[wn]; p.wu = new double[wn]; p.wh = new double[wn]; p.wa = new double[wn]; p.wdir = new int[wn]; p.wk = new int[wn];
            for (int i = 0; i < wn; i++) {
                int k = whiteCells.get(i);
                p.wk[i] = k; p.wx[i] = c * k; p.wu[i] = speed(s, k); p.wh[i] = s.h[k]; p.wa[i] = s.alpha[k]; p.wdir[i] = s.dir[k] < 0 ? -1 : 1;
            }
        }
        // ---- the outline: the top edge and the ground as two lines over the same knots, with the glide onto a body at an end
        final double sheen = WaterPainter.SHEEN_PX / v.ws;
        final double toeL = left == null || growL || p.depth[0] < 0.05 ? 0 : Math.min((left.x1 - left.x0) * 0.5, Model.clamp(TOE * p.depth[0], TOE_MIN, TOE_MAX));
        final double toeR = right == null || growR || p.depth[n - 1] < 0.05 ? 0 : Math.min((right.x1 - right.x0) * 0.5, Model.clamp(TOE * p.depth[n - 1], TOE_MIN, TOE_MAX));
        final int nk = n + (toeL > 0 ? 2 : 0) + (toeR > 0 ? 2 : 0);
        final double[] ox = new double[nk], ot = new double[nk], ob = new double[nk], od = new double[nk];
        int q = 0;
        if (toeL > 0) {
            // (the glide never goes below the body's level, so never into its water; it stands above it by less than the film's depth)
            double lv = Math.min(left.y, p.ground[0]);
            ox[q] = x0 - toeL; ot[q] = lv; ob[q] = lv; od[q++] = p.depth[0];
            ox[q] = x0 - 1e-6; ot[q] = p.top[0]; ob[q] = lv; od[q++] = p.depth[0];
            yMax = Math.max(yMax, lv);
        }
        for (int i = 0; i < n; i++) { ox[q] = p.x[i]; ot[q] = p.top[i]; ob[q] = p.ground[i]; od[q++] = p.depth[i]; }
        if (toeR > 0) {
            double lv = Math.min(right.y, p.ground[n - 1]);
            ox[q] = x1 + 1e-6; ot[q] = p.top[n - 1]; ob[q] = lv; od[q++] = p.depth[n - 1];
            ox[q] = x1 + toeR; ot[q] = lv; ob[q] = lv; od[q++] = p.depth[n - 1];
            yMax = Math.max(yMax, lv);
        }
        // ---- the fill: the ribbon cut at device-pixel columns every FILL_PX and each cut filled with one colour (its depth, whiteness
        // and opacity there, in steps); cuts of the same colour are one shape. A cut lies exactly between two pixel columns, so the
        // shapes meet without a seam, and plain colours and straight edges are drawn the same by every band.
        List<Path2D.Double> fills = new ArrayList<>();
        List<double[]> keys = new ArrayList<>();
        double from = ox[0];
        int key = Integer.MIN_VALUE;
        double[] kv = null;
        for (double dev = Math.floor((v.ws * ox[0] + v.wtx) / FILL_PX) * FILL_PX; ; dev += FILL_PX) {
            final double ua = Math.max(ox[0], (dev - v.wtx) / v.ws), ub = Math.min(ox[nk - 1], (dev + FILL_PX - v.wtx) / v.ws);
            if (ub > ua) {
                final float f = (float) Model.clamp(((ua + ub) * 0.5 - x0) / (x1 - x0), 0, 1);
                final int dq = (int) Math.round(Model.clamp(stop(p.frac, p.sh, f) / FILM_DEEP_PX, 0, 1) * 16), wq = (int) Math.round(stop(p.frac, p.sw, f) * 8), aq = (int) Math.round(stop(p.frac, p.sa, f) * 16);
                final int k2 = dq << 16 | wq << 8 | aq;
                if (k2 != key) {
                    if (kv != null) { fills.add(cut(ox, ot, ob, from, ua)); keys.add(kv); }
                    from = ua;
                    key = k2;
                    kv = new double[]{dq / 16.0, wq / 8.0, aq / 16.0};
                }
            }
            if (ub >= ox[nk - 1]) break;
        }
        if (kv != null) { fills.add(cut(ox, ot, ob, from, ox[nk - 1])); keys.add(kv); }
        p.fills = fills.toArray(new Path2D.Double[0]);
        p.fd = new double[keys.size()]; p.fw = new double[keys.size()]; p.fa = new double[keys.size()];
        for (int i = 0; i < keys.size(); i++) { p.fd[i] = keys.get(i)[0]; p.fw[i] = keys.get(i)[1]; p.fa[i] = keys.get(i)[2]; }
        // ---- the bright line on the surface, where the film is deep enough to carry one: a strip one device pixel deep under the
        // top edge (a filled shape: cheaper than a stroked line of as many points, and the same in every band)
        Path2D.Double line = null;
        for (int i = 0; i < nk; i++) {
            if (od[i] < SHEEN_MIN_H) continue;
            int j = i;
            while (j + 1 < nk && od[j + 1] >= SHEEN_MIN_H) j++;
            if (j > i) {
                if (line == null) line = new Path2D.Double();
                line.moveTo(ox[i], ot[i]);
                for (int k = i + 1; k <= j; k++) line.lineTo(ox[k], ot[k]);
                for (int k = j; k >= i; k--) line.lineTo(ox[k], ot[k] + sheen);
                line.closePath();
            }
            i = j;
        }
        p.line = line;
        p.sheen = aMin * Model.clamp(maxD / SHEEN_FULL_H, 0, 1);
        // ---- free falls: where the ground drops away steeper than CLIFF_SLOPE the water leaves it
        List<double[]> mist = null;
        for (int i = 0; i < n - 1; i++) {
            double sg = (p.ground[i + 1] - p.ground[i]) / (p.x[i + 1] - p.x[i]);
            if (Math.abs(sg) < CLIFF_SLOPE) continue;
            int ia = i;
            // (one fall is at most a cell wide: a longer steep run is a flank, not a cliff, and the water comes down it as a cascade,
            // every fall landing on the slope below; as one arc from its top to its bottom it stood in the air beside the mountain)
            while (i + 1 < n - 1 && (p.ground[i + 2] - p.ground[i + 1]) / (p.x[i + 2] - p.x[i + 1]) * Math.signum(sg) >= CLIFF_SLOPE && p.x[i + 2] - p.x[ia] <= c + 1e-6) i++;
            final int ib = i + 1, lip = sg > 0 ? ia : ib, foot = sg > 0 ? ib : ia, dirF = sg > 0 ? 1 : -1;
            int kc = cellOf(s, a, b, p.x[lip]);
            if ((s.dir[kc] < 0 ? -1 : 1) != dirF) continue;                 // (water does not run up a wall)
            final double hL = p.depth[lip], drop = p.ground[foot] - p.ground[lip];
            if (hL < MIN_H_DRAW || drop < 2) continue;
            final double reach = Model.clamp(1.5 + 0.9 * Math.sqrt(drop) * Math.min(1, speed(s, kc) / 20), 2, 14), w = Math.max(1.2, hL * 1.3);
            final double lx = p.x[lip], ly = p.ground[lip], fx = p.x[foot] + dirF * reach;
            double land = Math.max(t.at(fx), p.ground[foot]);
            for (WaterPainter.Body wb : bodies) if (fx >= wb.x0 && fx <= wb.x1) land = Math.min(land, wb.y);      // (it lands on a pool's surface, not in it)
            if (land < ly + 2) continue;                                    // (a pool that stands over the lip: nothing falls, and never upward)
            if (p.fall == null) p.fall = new Path2D.Double();
            // (the two arcs as short straight pieces: a curve may be flattened differently under different clips, and every band must draw the same)
            p.fall.moveTo(lx, ly - hL);
            arc(p.fall, lx, ly - hL, lx + dirF * reach, ly - hL, fx, land);
            final double ix = fx - dirF * Math.min(w, reach);
            p.fall.lineTo(ix, land);
            arc(p.fall, ix, land, lx + dirF * Math.max(0, reach - w), ly, lx, ly);
            p.fall.closePath();
            if (mist == null) mist = new ArrayList<>();
            for (int j = 0; j < 3; j++) mist.add(new double[]{fx + dirF * (j - 0.6) * 2.4, land, 2 + 0.45 * Math.sqrt(drop) + j * 0.7});      // (x, the y it stands on, radius)
            yMax = Math.max(yMax, land + 2);
            yMin = Math.min(yMin, Math.min(ly - hL, land - 16));
        }
        if (mist != null) {
            p.mist = new double[mist.size() * 3];
            for (int i = 0; i < mist.size(); i++) System.arraycopy(mist.get(i), 0, p.mist, i * 3, 3);
        }
        p.yMin = yMin - 8; p.yMax = yMax + 2;
        return p;
    }

    /** A quadratic arc from (x0, y0) by the control point (cx, cy) to (x1, y1), appended as eight straight pieces. */
    private static void arc(Path2D.Double path, double x0, double y0, double cx, double cy, double x1, double y1) {
        for (int i = 1; i <= 8; i++) {
            double t = i / 8.0, u = 1 - t;
            path.lineTo(u * u * x0 + 2 * u * t * cx + t * t * x1, u * u * y0 + 2 * u * t * cy + t * t * y1);
        }
    }

    /** A round blob as a twelve-sided shape (see {@link #arc}): centre, half width, half height. */
    private static Path2D.Double blob(double cx, double cy, double rx, double ry) {
        Path2D.Double p = new Path2D.Double();
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6, x = cx + rx * Math.cos(a), y = cy + ry * Math.sin(a);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }

    // ------------------------------------------------------------------ the bands

    /** The top edge of a piece at x (between its samples). */
    private static double topAt(Piece p, double x) {
        int i = Arrays.binarySearch(p.x, x);
        if (i >= 0) return p.top[i];
        i = -i - 1;
        if (i <= 0) return p.top[0];
        if (i >= p.x.length) return p.top[p.x.length - 1];
        double f = (x - p.x[i - 1]) / (p.x[i] - p.x[i - 1]);
        return p.top[i - 1] + (p.top[i] - p.top[i - 1]) * f;
    }

    private static double depthAt(Piece p, double x) {
        int i = Arrays.binarySearch(p.x, x);
        if (i >= 0) return p.depth[i];
        i = -i - 1;
        if (i <= 0) return p.depth[0];
        if (i >= p.x.length) return p.depth[p.x.length - 1];
        double f = (x - p.x[i - 1]) / (p.x[i] - p.x[i - 1]);
        return p.depth[i - 1] + (p.depth[i] - p.depth[i - 1]) * f;
    }

    private static int a255(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }

    /**
     * Draw the ribbons. {@code g} has the world transform.
     * @param wy0 the top and bottom of what this band can show, world y
     * @param water the biome's water colour, or null
     */
    static void paint(Graphics2D g, State s, Camera.View v, Lit lit, double anim, double wy0, double wy1, Advanced adv, Color water, boolean frozen, double dim) {
        if (!s.on || s.draw.length == 0) return;
        final Color[] pal = WaterPainter.palette(lit, water, frozen, dim);
        final Color top = pal[0], deep = pal[1];
        Color white = lit.lit(new Color(236, 244, 252));
        if (dim > 0) white = Renderer.mix(white, WaterPainter.VEIL, dim * WaterPainter.DIM_SHARE * 0.6);
        final int sheenA = (int) Math.round(WaterPainter.SHEEN_ALPHA * (1 - 0.4 * dim));
        final double sheen = WaterPainter.SHEEN_PX / v.ws;
        for (Piece p : s.draw) {
            if (p.yMax < wy0 || p.yMin > wy1 || p.x1 < v.wx0 - 16 || p.x0 > v.wx1 + 16) continue;
            // ---- the water itself: plain colours that follow the depth along the stretch
            for (int i = 0; i < p.fills.length; i++) {
                double d = p.fd[i] * 0.8, w = frozen ? 0 : p.fw[i];
                g.setColor(Renderer.alpha(Renderer.mix(Renderer.mix(top, deep, d), white, w * 0.5), a255((184 + 56 * d + 30 * w) * p.fa[i])));
                g.fill(p.fills[i]);
            }
            if (frozen) continue;
            if (p.line != null) {
                g.setColor(new Color(255, 255, 255, a255(sheenA * p.sheen)));
                g.fill(p.line);
            }
            g.setStroke(new BasicStroke((float) sheen, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL));
            glints(g, p, anim, sheen, sheenA);
            if (p.fall != null) {
                g.setColor(Renderer.alpha(Renderer.mix(top, white, 0.7), a255(205 * p.alphaMin)));
                g.fill(p.fall);
                for (int i = 0; i < p.mist.length; i += 3) {
                    double r = p.mist[i + 2] * (0.85 + 0.15 * Math.sin(anim * 3.1 + i));
                    g.setColor(Renderer.alpha(white, a255(46 * p.alphaMin)));
                    g.fill(blob(p.mist[i], p.mist[i + 1] - r * 0.8, r, r * 0.8));       // (above where the water lands: never in a pool)
                }
            }
            if (p.wk.length > 0) whiteWater(g, s, p, v, anim, white);
        }
    }

    /** The value at fraction {@code f} of a piece: linear between its stops. */
    private static double stop(float[] frac, double[] val, float f) {
        final int n = frac.length;
        if (n == 0) return 0;
        if (n == 1 || f <= frac[0]) return val[0];
        for (int i = 1; i < n; i++) if (f <= frac[i]) return val[i - 1] + (val[i] - val[i - 1]) * (f - frac[i - 1]) / (frac[i] - frac[i - 1]);
        return val[n - 1];
    }

    /** y at x on a line through the knots (kx ascending, ky). */
    private static double on(double[] kx, double[] ky, double x) {
        int i = Arrays.binarySearch(kx, x);
        if (i >= 0) return ky[i];
        i = -i - 1;
        if (i <= 0) return ky[0];
        if (i >= kx.length) return ky[kx.length - 1];
        return ky[i - 1] + (ky[i] - ky[i - 1]) * (x - kx[i - 1]) / (kx[i] - kx[i - 1]);
    }

    /** The part of the ribbon between ua and ub: along its top edge, down, back along the ground. */
    private static Path2D.Double cut(double[] kx, double[] top, double[] bot, double ua, double ub) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(ua, on(kx, top, ua));
        for (int i = 0; i < kx.length; i++) if (kx[i] > ua && kx[i] < ub) path.lineTo(kx[i], top[i]);
        path.lineTo(ub, on(kx, top, ub));
        path.lineTo(ub, on(kx, bot, ub));
        for (int i = kx.length - 1; i >= 0; i--) if (kx[i] > ua && kx[i] < ub) path.lineTo(kx[i], bot[i]);
        path.lineTo(ua, on(kx, bot, ua));
        path.closePath();
        return path;
    }

    /**
     * The lights that ride down the surface: short brighter strokes on the continuous bright line, one every {@link #GLINT_S} of
     * travel. All of a piece's lights are one path, drawn once.
     */
    private static void glints(Graphics2D g, Piece p, double anim, double sheen, int sheenA) {
        final int n = p.x.length;
        Path2D.Double path = null;
        for (int i = 0; i < n - 1; i++) {
            final double sa = p.s[i], sb = p.s[i + 1];
            if (sa == sb) continue;
            final double lo = Math.min(sa, sb), hi = Math.max(sa, sb), dx = p.x[i + 1] - p.x[i], u = dx / Math.abs(sb - sa);
            for (double m = Math.ceil((lo - anim) / GLINT_S); anim + m * GLINT_S < hi; m++) {
                double gx = p.x[i] + (anim + m * GLINT_S - sa) / (sb - sa) * dx;
                if (depthAt(p, gx) < GLINT_MIN_H) continue;
                double len = Model.clamp(u * GLINT_S * 0.3, 1.5, 9), xa = Math.max(p.x[0], gx - len / 2), xb = Math.min(p.x[n - 1], gx + len / 2);
                if (path == null) path = new Path2D.Double();
                path.moveTo(xa, topAt(p, xa) + sheen * 0.5);
                path.lineTo(xb, topAt(p, xb) + sheen * 0.5);
            }
        }
        if (path == null) return;
        g.setColor(new Color(255, 255, 255, a255(GLINT_ALPHA * p.alphaMin * sheenA / (double) WaterPainter.SHEEN_ALPHA)));
        g.draw(path);
    }

    /**
     * White water on steep ground or in a fast stream: flecks of foam that ride in the ribbon and a little spray above it
     * (all the flecks of a piece are one path, all its spray another).
     */
    private static void whiteWater(Graphics2D g, State s, Piece p, Camera.View v, double anim, Color white) {
        final double c = s.cell;
        final float fw = (float) Math.min(1.1 / v.ws, 0.9);
        Path2D.Double foam = null, spray = null;
        int dots = 0;
        double aSum = 0;
        for (int i = 0; i < p.wk.length; i++) {
            final long k = p.wk[i];
            final double u = p.wu[i], h = p.wh[i];
            aSum += p.wa[i];
            for (int j = 0; j < FOAM_PER_CELL; j++) {
                double ph = hash01(k * 131 + j * 17) + p.wdir[i] * anim * u / c;
                double x = p.wx[i] - c / 2 + c * (ph - Math.floor(ph));
                if (x < p.x[0] + 1 || x > p.x[p.x.length - 1] - 1) continue;
                double len = Model.clamp(u * 0.09, 1.2, 3.6), xa = x - len / 2, xb = x + len / 2, in = 0.15 + 0.5 * hash01(k * 733 + j * 29);
                if (foam == null) foam = new Path2D.Double();
                foam.moveTo(xa, topAt(p, xa) + depthAt(p, xa) * in);
                foam.lineTo(xb, topAt(p, xb) + depthAt(p, xb) * in);
            }
            for (int j = 0; j < SPRAY_PER_CELL && dots < SPRAY_MAX; j++, dots++) {
                double ph = hash01(k * 977 + j * 53) + anim * (1.1 + hash01(k * 389 + j));
                ph -= Math.floor(ph);
                double x = p.wx[i] + (hash01(k * 211 + j * 7) - 0.5) * c + p.wdir[i] * ph * 5;
                if (x < p.x[0] || x > p.x[p.x.length - 1]) continue;
                // (a drop hops up and falls back: it shrinks as it goes, so that one colour serves them all)
                double y = topAt(p, x) - 4 * ph * (1 - ph) * (1.2 + 0.5 * h), r = (0.4 + 0.25 * hash01(k * 57 + j)) * (1 - 0.6 * ph);
                if (spray == null) spray = new Path2D.Double();
                spray.append(blob(x, y, r, r), false);
            }
        }
        final double alpha = aSum / Math.max(1, p.wk.length);
        if (foam != null) {
            g.setStroke(new BasicStroke(fw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL));
            g.setColor(Renderer.alpha(white, a255(175 * alpha)));
            g.draw(foam);
        }
        if (spray != null) {
            g.setColor(Renderer.alpha(white, a255(150 * alpha)));
            g.fill(spray);
        }
    }
}
