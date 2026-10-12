package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Protocol v2.4 standing water: every body of {@code frame.water} drawn as a level surface over the terrain the
 * viewer already has. The body is the region between the surface line at {@code y} and the terrain polyline over
 * {@code [x0, x1]}, wherever the terrain is below {@code y}; nothing of it is drawn above {@code y} or beyond
 * {@code [x0, x1]}, so it cannot float whatever the simulation sends.
 * <p>
 * The list arrives about ten times a second. {@link #prepare} (renderer 0, once per frame, into {@link Shared#water})
 * eases every body's {@code y}, {@code x0} and {@code x1} towards the newest values and fades a new id in and a
 * vanished id out; the bands only read the result ({@link #paint}), so every band draws the same water. It is a live
 * pass: the level moves every frame, so none of it is in the baked scenery tiles.
 */
final class WaterPainter {
    private WaterPainter() {}

    /** Time constant of the easing of {@code y}, {@code x0}, {@code x1}, and the time a body takes to fade in or out, seconds. */
    static final double EASE_S = 0.12, FADE_S = 0.30;
    /** The depth over which the fill darkens from its surface colour to its deep colour, world px. */
    static final double DEEP_PX = 12;
    /** Thickness of the bright line on the surface, device px. */
    static final double SHEEN_PX = 1.0;
    /** Opacity (of 255) of that line, and how much of the under-cloud veil's darkening the water takes (see {@link #paint}). */
    static final int SHEEN_ALPHA = 112;
    static final double DIM_SHARE = 0.6;
    /**
     * A film: a body whose deepest point is at most {@code FILM_FULL_PX} (world px) under its surface is a line a pixel or two
     * thick, which the ordinary fill and sheen leave nearly invisible on a wet street. It is drawn with the same shape (so
     * it still cannot float or reach under the ground) but with a lighter, denser surface colour and a brighter sheen;
     * the change fades out towards {@code FILM_NONE_PX}, from where on a body is drawn exactly as before.
     */
    static final double FILM_FULL_PX = 1.5, FILM_NONE_PX = 3.0;
    /** Of a film at full strength: the opacity (of 255) of its sheen and of the top of its fill, and how far that top colour goes to white. */
    static final int FILM_SHEEN_ALPHA = 230, FILM_FILL_ALPHA = 240;
    static final double FILM_LIGHTEN = 0.30;

    /** 0..1: how much of the film look a body gets whose deepest point is {@code depth} world px under its surface. */
    static double film(double depth) { return Model.clamp((FILM_NONE_PX - depth) / (FILM_NONE_PX - FILM_FULL_PX), 0, 1); }
    /** How far a trickle runs from the end of a spilling body, world px. */
    static final double TRICKLE_PX = 64;
    /** Where a body ends against a curb, its surface falls to the ground over this many times the depth there. */
    static final double RAMP = 1.6;

    /** One body as drawn: eased towards the newest values of its id. */
    static final class Body {
        long id, settlement = -1;
        boolean street, spill, gone;
        /** As drawn this frame. */
        double y, x0, x1, alpha;
        /** The newest values sent. */
        double ty, tx0, tx1;
        /** 0..1: how hard it is raining on it (its ripples). */
        double rain;
        /** World y of its deepest point as sent ({@code y + depth}); what tells a film from deep water whatever part of it is in view. */
        double bed = Double.NaN;
    }

    /** What a view remembers of the water between frames. */
    static final class State {
        final Map<Long, Body> byId = new HashMap<>();
        /** The bodies of this frame, in id order; replaced every frame, never changed while the bands run. */
        Body[] draw = new Body[0];
        /** True when the stream uses the v2.4 water model ({@code hello.water}). */
        boolean on;
        /**
         * v2.4.1 ({@code hello.flow}): there is no virtual curb. A body ends where its level meets the ground, so its ends are
         * drawn as sent (no {@link #RAMP}), and what runs over its rim is the stream {@link FlowPainter} draws (no {@link #trickle}).
         */
        boolean flow;
        private List<Model.Body> src;
        private Model.Hello hello;
        private long clock = Long.MIN_VALUE;

        Body body(long id) { return byId.get(id); }
    }

    /**
     * Bring the view's water up to this frame.
     * @param eased false = a still picture: every body stands at its newest values, fully there
     */
    static void prepare(State s, Model.Hello hello, List<Model.Body> latest, Model.Frame f, long clockNanos, boolean eased) {
        if (hello != s.hello) { s.byId.clear(); s.hello = hello; s.src = null; s.clock = Long.MIN_VALUE; }
        s.on = hello != null && hello.water != null;
        s.flow = s.on && hello.flow != null;
        if (!s.on) { s.draw = NONE; return; }
        double dt = s.clock == Long.MIN_VALUE ? 0 : Model.clamp((clockNanos - s.clock) / 1e9, 0, 0.1);
        s.clock = clockNanos;
        step(s, latest, dt, !eased);
        rainOn(s, f);
    }

    private static final Body[] NONE = new Body[0];
    /** The colour of the under-cloud veil ({@code Renderer.drawShade}). */
    static final Color VEIL = new Color(5, 8, 16);

    /**
     * The water's colours in this light: {surface, deep}. The film of {@link FlowPainter} uses the same two, so a stream and
     * the pool it runs into are one water.
     */
    static Color[] palette(Lit lit, Color water, boolean frozen, double dim) {
        Color top, deep;
        if (frozen) { top = lit.lit(new Color(214, 232, 246)); deep = lit.lit(new Color(172, 200, 226)); }
        else if (water != null) { top = Renderer.mix(lit.lit(Lit.mul(water, 0.92)), lit.skyBot, 0.35); deep = lit.lit(Lit.mul(water, 0.50)); }
        else { top = Renderer.mix(lit.lit(new Color(78, 112, 156)), lit.skyBot, 0.35); deep = lit.lit(new Color(34, 56, 92)); }
        // (the water is drawn over the under-cloud veil: it is darkened too, a little less than the ground it lies on, so that a
        // flood still reads under a storm, as the rain drawn after the veil does)
        if (dim > 0) { top = Renderer.mix(top, VEIL, dim * DIM_SHARE); deep = Renderer.mix(deep, VEIL, dim * DIM_SHARE); }
        return new Color[]{top, deep};
    }

    /** Take a new list (when it is one) and advance the easing by {@code dt} seconds. {@code snap} = no easing, no fading. */
    static void step(State s, List<Model.Body> latest, double dt, boolean snap) {
        if (latest != null && latest != s.src) {
            s.src = latest;
            for (Body b : s.byId.values()) b.gone = true;
            for (Model.Body m : latest) {
                Body b = s.byId.get(m.id);
                if (b == null) {
                    b = new Body();
                    b.id = m.id;
                    b.y = m.y; b.x0 = m.x0; b.x1 = m.x1;       // a new body appears where it is and fades in
                    s.byId.put(m.id, b);
                }
                b.gone = false;
                b.street = m.street();
                b.settlement = m.settlement;
                b.spill = m.spill;
                b.ty = m.y; b.tx0 = m.x0; b.tx1 = m.x1;
                b.bed = m.y + m.depth;
            }
        }
        final double k = snap ? 1 : 1 - Math.exp(-dt / EASE_S), fade = snap ? 1 : dt / FADE_S;
        java.util.Iterator<Body> it = s.byId.values().iterator();
        while (it.hasNext()) {
            Body b = it.next();
            if (b.gone) {
                b.alpha -= fade;
                if (b.alpha <= 0) it.remove();
                continue;
            }
            b.alpha = Math.min(1, b.alpha + fade);
            b.y += (b.ty - b.y) * k;
            b.x0 += (b.tx0 - b.x0) * k;
            b.x1 += (b.tx1 - b.x1) * k;
        }
        Body[] d = s.byId.values().toArray(NONE);
        Arrays.sort(d, (a, b) -> Long.compare(a.id, b.id));
        s.draw = d;
    }

    /** How hard it rains on each body: the rain particles of the frame above its span (the stream lists those in view). */
    private static void rainOn(State s, Model.Frame f) {
        for (Body b : s.draw) b.rain = 0;
        if (f == null || s.draw.length == 0 || f.particleCount == 0) return;
        final float[] p = f.particles;
        final int[] hits = new int[s.draw.length];
        for (int k = 0, n = f.particleCount; k < n; k++) {
            int kind = (int) p[k * 7];
            if (kind != 1 && kind != 6) continue;
            double x = p[k * 7 + 1];
            for (int i = 0; i < s.draw.length; i++) if (x >= s.draw[i].x0 && x <= s.draw[i].x1) { hits[i]++; break; }
        }
        for (int i = 0; i < s.draw.length; i++) {
            Body b = s.draw[i];
            b.rain = Math.min(1, hits[i] * 30.0 / (b.x1 - b.x0 + 30));       // about one drop in the air per 30 px is "hard"
        }
    }

    private static double hash01(long n) {
        n ^= n >>> 33; n *= 0xff51afd7ed558ccdL; n ^= n >>> 33; n *= 0xc4ceb9fe1a85ec53L; n ^= n >>> 33;
        return (n >>> 11) / (double) (1L << 53);
    }

    /**
     * Draw the bodies. {@code g} has the world transform.
     * @param wy0 the top and bottom of what this band can show, world y
     * @param water the biome's water colour, or null
     */
    static void paint(Graphics2D g, State s, Model.Terrain t, Camera.View v, Lit lit, double anim, double wy0, double wy1, Advanced adv, Color water, boolean frozen, double dim) {
        if (!s.on || s.draw.length == 0) return;
        final Color[] pal = palette(lit, water, frozen, dim);
        final Color top = pal[0], deep = pal[1];
        final int sheenA = (int) Math.round(SHEEN_ALPHA * (1 - 0.4 * dim));
        final double st = t.step, sheen = SHEEN_PX / v.ws;
        for (Body b : s.draw) {
            if (b.alpha <= 0.004 || b.y > wy1) continue;
            final double xa = Math.max(b.x0, v.wx0 - 2 * st), xb = Math.min(b.x1, v.wx1 + 2 * st), y = b.y;
            if (xb - xa < 1e-6) continue;
            // the bed: the terrain samples between xa and xb, with the points where the level crosses the ground
            int cap = (int) ((xb - xa) / st) + 4, n = 0;
            double[] bx = new double[cap * 2], by = new double[cap * 2];
            double px = xa, pd = t.at(xa) - y, maxD = Math.max(0, pd);
            bx[n] = xa; by[n++] = y + Math.max(0, pd);
            Path2D.Double line = null;                       // the bright line, one piece per stretch of water
            // an end where the water stands against a curb (the ground there is below the level): the surface slopes down to the
            // ground over a short stretch instead of ending in a wall of water (it only ever draws less than the body)
            final double span = xb - xa, dR = t.at(xb) - y;
            // (v2.4.1: no curb, so no ramp; an end sent over lower ground would show as the straight cut it is)
            final double rampL = !s.flow && xa == b.x0 && pd > 0.3 ? Math.min(RAMP * pd, span / 3) : 0, rampR = !s.flow && xb == b.x1 && dR > 0.3 ? Math.min(RAMP * dR, span / 3) : 0;
            double runX = pd > 0 ? xa + rampL : Double.NaN;
            for (double x = (Math.floor(xa / st) + 1) * st; ; x += st) {
                boolean last = x >= xb - 1e-9;
                if (last) x = xb;
                double d = t.at(x) - y;
                if ((pd > 0) != (d > 0)) {
                    double xc = px + (x - px) * pd / (pd - d);
                    bx[n] = xc; by[n++] = y;
                    if (pd > 0) { if (line == null) line = new Path2D.Double(); line.moveTo(runX, y + sheen * 0.5); line.lineTo(xc, y + sheen * 0.5); runX = Double.NaN; }
                    else runX = xc;
                }
                bx[n] = x; by[n++] = y + Math.max(0, d);
                if (d > maxD) maxD = d;
                px = x; pd = d;
                if (last) break;
            }
            if (!Double.isNaN(runX) && xb - rampR > runX) { if (line == null) line = new Path2D.Double(); line.moveTo(runX, y + sheen * 0.5); line.lineTo(xb - rampR, y + sheen * 0.5); }
            if (maxD <= 0 || y + maxD < wy0) continue;       // nothing under the level here, or all of it above this band
            Path2D.Double body = new Path2D.Double();
            body.moveTo(xa + rampL, y);
            body.lineTo(xb - rampR, y);
            for (int i = n - 1; i >= 0; i--) body.lineTo(bx[i], by[i]);
            body.closePath();
            // (a film: lighter and denser at the surface, a brighter sheen; 0 for a body 3 px deep or more, which is drawn as ever.
            // The depth is the body's own, or what is in view of it when that is deeper, so that panning does not change the look)
            final double film = frozen ? 0 : film(Math.max(Double.isNaN(b.bed) ? 0 : b.bed - y, maxD));
            final Color surf = film > 0 ? Renderer.mix(top, Color.WHITE, FILM_LIGHTEN * film) : top;
            final double fillA = 176 + (FILM_FILL_ALPHA - 176) * film, lineA = sheenA + (FILM_SHEEN_ALPHA * (1 - 0.4 * dim) - sheenA) * film;
            g.setPaint(new GradientPaint(0, (float) y, Renderer.alpha(surf, (int) Math.round(fillA * b.alpha)), 0, (float) (y + DEEP_PX),
                    Renderer.alpha(deep, (int) Math.round(232 * b.alpha))));
            g.fill(body);
            if (line != null && !frozen) {
                g.setStroke(new BasicStroke((float) sheen, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                g.setColor(new Color(255, 255, 255, (int) Math.round(lineA * b.alpha)));
                g.draw(line);
            }
            if (!frozen && !adv.noPuddleImpact && b.rain > 0.02 && y <= wy1 && y + 4 >= wy0) ripples(g, b, t, v, anim);
            if (b.spill && !frozen && !s.flow) trickle(g, b, t, v, anim, top);
        }
    }

    /** Rings where drops land, seen edge-on: the lower half of a flat ellipse on the surface, never above it and never deeper than the water there. */
    private static void ripples(Graphics2D g, Body b, Model.Terrain t, Camera.View v, double anim) {
        final double span = b.x1 - b.x0;
        final int n = (int) Model.clamp(Math.round(span / 45 * (0.4 + 0.6 * b.rain)), 1, 40);
        g.setStroke(new BasicStroke(1f));
        for (int k = 0; k < n; k++) {
            long seed = b.id * 7919L + k * 17L;
            double turn = anim * 0.9 + hash01(seed), ph = turn - Math.floor(turn);
            double rx = b.x0 + span * (0.03 + 0.94 * hash01(seed * 3 + (long) Math.floor(turn) * 31L)), rr = 2 + ph * 10;
            if (rx + rr < v.wx0 || rx - rr > v.wx1 || rx - rr < b.x0 || rx + rr > b.x1) continue;
            double d = t.at(rx) - b.y;
            if (d < 0.4) continue;
            double ry = Math.min(rr * 0.3, d);
            g.setColor(new Color(220, 235, 255, (int) (140 * (1 - ph) * Math.min(1, b.rain * 2) * b.alpha)));
            g.draw(new Arc2D.Double(rx - rr, b.y - ry, 2 * rr, 2 * ry, 180, 180, Arc2D.OPEN));
        }
    }

    /** Water running over the lower end of a body that is spilling: a thin moving line down the ground beyond it. */
    private static void trickle(Graphics2D g, Body b, Model.Terrain t, Camera.View v, double anim, Color top) {
        double ya = t.at(b.x0), yb = t.at(b.x1);
        boolean right = yb > ya + 0.05 || (Math.abs(yb - ya) <= 0.05 && t.at(b.x1 + 16) >= t.at(b.x0 - 16));
        double xe = right ? b.x1 : b.x0, dir = right ? 1 : -1;
        if (xe + TRICKLE_PX < v.wx0 || xe - TRICKLE_PX > v.wx1) return;
        Path2D.Double p = new Path2D.Double();
        p.moveTo(xe, Math.max(b.y, t.at(xe) - 0.5));
        double x = xe + dir * 2.5, gy = t.at(x);
        p.lineTo(x, Math.max(b.y, gy - 0.5));
        for (double run = 2.5; run < TRICKLE_PX; run += 6) {
            double nx = x + dir * 6, ny = t.at(nx);
            if (ny < gy - 0.3 || nx < 0 || nx > v.worldW) break;      // the ground rises again: the water collects there
            x = nx; gy = ny;
            p.lineTo(x, gy - 0.5);
        }
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{5f, 3.5f}, (float) (8.5 - (anim * 26) % 8.5)));
        g.setColor(Renderer.alpha(Renderer.mix(top, Color.WHITE, 0.35), (int) Math.round(190 * b.alpha)));
        g.draw(p);
    }
}
