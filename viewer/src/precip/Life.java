package precip;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.util.function.DoublePredicate;
import java.util.function.DoubleUnaryOperator;

/**
 * Phase O (OH6): the people that move. Drawn every frame over the ground tiles, after the trees and before the cars, in
 * front of both rows of buildings:
 * <ul>
 *   <li><b>walkers</b> (some with a dog) on the street spans of every settlement, pacing to and fro along the kerb;</li>
 *   <li>a <b>shadow crossing a lit window</b> now and then (front-row buildings only);</li>
 *   <li>the <b>idle sway</b> of whoever stands or sits outside a front-row building (their still figure is in the tile;
 *       this draws them leaning a pixel to and fro over it, and now and then raising an arm).</li>
 * </ul>
 * Everything is a pure function of the scene, the light and {@code anim} (the renderer's animation clock): no state is
 * kept from frame to frame and nothing is random, so every render band draws the same frame and a reload shows the same
 * people. Nothing is drawn below {@link #LIFE_MIN_PX} device px per world px.
 * <p>
 * <b>Wiring</b> (two calls, PLAN Phase O): {@link #level} goes into the bake key of the ground tiles and into
 * {@code BuildingPainter.paint(g, o, lit, haze, wind, life)}; {@link #paint} is called with the world transform set.
 */
final class Life {
    private Life() {}

    /** Below this many device px per world px nothing of this is drawn (the painter's own threshold for house detail). */
    static final double LIFE_MIN_PX = BuildingPainter.LIFE_MIN_PX;

    // ---- walkers (PLAN "Walkers"; starting values)
    /** {@code clamp(round(sqrt(population) / WALK_PER), 1, WALK_MAX)} walkers a settlement. */
    static final double WALK_PER = 3;
    static final int WALK_MAX = 12;
    /** Walking speed, px/s, and how far inside the ends of a street span a walker turns round. */
    static final double WALK_V0 = 14, WALK_V1 = 20, WALK_MARGIN = 12;
    /** A street span shorter than this (inside the settlement) has no walkers. */
    static final double WALK_SPAN_MIN = 60;
    /** Walkers are out only in daylight above this ({@code Lit.day}), on ground drier / less snowy than this, in water shallower than this (px). */
    static final double WALK_DAY = 0.4, WALK_WET = 0.3, WALK_SNOW = 0.6, WALK_FLOOD = 1.0;
    /** The width of the fade at each of those limits (so a walker fades in and out instead of popping). */
    static final double WALK_FADE_DAY = 0.05, WALK_FADE_WET = 0.04, WALK_FADE_SNOW = 0.05;
    /** One walker in this many has a dog, one in this many is a child. */
    static final int DOG_ONE_IN = 5, CHILD_ONE_IN = 6;
    /** The kerb line a walker's feet are on: this far above the terrain line. */
    static final double KERB = 1.0;

    // ---- the shadow crossing a lit window, the idle sway
    /** A window is crossed once every CROSS_P0 .. CROSS_P1 seconds, in CROSS_T0 .. CROSS_T1 seconds. */
    static final double CROSS_P0 = 20, CROSS_P1 = 90, CROSS_T0 = 3, CROSS_T1 = 5;
    /** The part of a pane's width (from each side) the curtains hide: the shadow is seen between them only. */
    static final double CROSS_INSET = 0.18;
    /** The idle sway: this many px to either side, once in this many seconds; an arm goes up for WAVE_T s every WAVE_P0 .. WAVE_P1 s. */
    static final double SWAY_PX = 1.0, SWAY_S = 3.0, WAVE_T = 1.4, WAVE_P0 = 11, WAVE_P1 = 23;
    private static final Color SHADOW = new Color(42, 33, 28, 236);

    /** Diagnostics (tests): what the last call of {@link #paintWorld} on this thread drew. */
    static final class Stats { int walkers, dogs, crossings, idle; }

    private static final ThreadLocal<Stats> STATS = ThreadLocal.withInitial(Stats::new);

    static Stats stats() { return STATS.get(); }

    /** Test switch (LifeTest): draw nobody, so a picture with the people can be compared with the same picture without. */
    static volatile boolean hide;

    /**
     * The outdoor life level of a building, 0 .. 4, for the bake key of the ground tiles ({@code << 24}) and for
     * {@code BuildingPainter.paint(..., life)}: 0 = nobody outside (night, rain, snow, storm damage, or
     * {@code sheltered}: a grounded tornado is near), else the daylight in four steps.
     */
    static int level(Model.Obj o, Lit lit, boolean sheltered) { return Cast.level(o, lit, sheltered); }

    /** The live people of this frame, with nobody sheltering and no flood known (see the full form). */
    static void paint(Graphics2D g, Scene scene, Camera.View view, double anim, Lit lit) { paint(g, scene, view, anim, lit, null, null); }

    /**
     * The live people of this frame. {@code g} must be in world coordinates (the renderer's {@code wTx}); its clip may be
     * a render band.
     * @param sheltered true at a world x where people stay indoors because a grounded tornado is near
     *                  ({@code sh.wreck::sheltered}); null = nowhere
     * @param flood     the depth of standing or running water at a world x, px; null = dry everywhere
     */
    static void paint(Graphics2D g, Scene scene, Camera.View view, double anim, Lit lit, DoublePredicate sheltered, DoubleUnaryOperator flood) {
        paintWorld(g, scene, view.ws, view.wx0, view.wx1, anim, lit, sheltered, flood);
    }

    /** {@link #paint} without a camera: {@code ws} = device px per world px, wx0 .. wx1 = the world x in view. */
    static void paintWorld(Graphics2D g, Scene scene, double ws, double wx0, double wx1, double anim, Lit lit, DoublePredicate sheltered, DoubleUnaryOperator flood) {
        final Stats st = STATS.get();
        st.walkers = st.dogs = st.crossings = st.idle = 0;
        if (!(ws >= LIFE_MIN_PX) || scene == null || hide) return;
        // (a band: only what can touch its rows)
        double cy0 = -1e18, cy1 = 1e18;
        final java.awt.Rectangle cb = g.getClipBounds();
        if (cb != null) { cy0 = cb.getMinY() - 2; cy1 = cb.getMaxY() + 2; }
        // ---- at the buildings of the front row: a shadow crossing a lit window; the sway of whoever is outside
        final boolean windows = lit.night > 0.2;
        for (Model.Obj o : scene.objects) {
            if (!o.building || o.layer == 1 || o.x + o.w / 2 + 20 < wx0 || o.x - o.w / 2 - 20 > wx1) continue;
            if (o.y + 3 < cy0 || o.y - o.h - 4 > cy1 || !Cast.lived(o) || o.damage >= Cast.WINDOW_DARK) continue;
            final int level = Cast.level(o, lit, sheltered != null && sheltered.test(o.x));
            if (!windows && level == 0) continue;
            for (Cast.Spot s : Cast.spots(o)) {
                if (s.type == Cast.WINDOW) {
                    if (!windows || !s.big || s.figure || !Cast.lit(o, s, lit)) continue;
                    final double u1 = unit(s.look + 1), u2 = unit(s.look + 2), u3 = unit(s.look + 3);
                    final double period = CROSS_P0 + (CROSS_P1 - CROSS_P0) * u1, across = CROSS_T0 + (CROSS_T1 - CROSS_T0) * u2;
                    final double tt = anim + u3 * period, cycle = Math.floor(tt / period), t = tt - cycle * period;
                    if (t >= across) continue;
                    // (which way they go this time comes from the number of the crossing)
                    final int dir = (Figures.mix(s.look + (long) cycle * 7919L) & 1) == 0 ? 1 : -1;
                    final double ix = s.x + s.w * CROSS_INSET, iw = s.w * (1 - 2 * CROSS_INSET);
                    final double bh = Math.min(s.h * 0.9, Figures.BUST_H0 + (Figures.BUST_H1 - Figures.BUST_H0) * u1), bw = bh * 0.92;
                    final double f = t / across, cx = dir > 0 ? ix - bw / 2 + (iw + bw) * f : ix + iw + bw / 2 - (iw + bw) * f;
                    // (seen between the curtains only; it is never taller than the pane, so it cannot leave it)
                    Figures.bust(g, cx, s.y + s.h, bh, SHADOW, s.look + (long) cycle, dir, ix, ix + iw);
                    st.crossings++;
                } else if (Cast.out(o, s, level)) {
                    final double u = unit(s.look + 5), lean = Math.sin(2 * Math.PI * (anim / SWAY_S + u)) * SWAY_PX;
                    final double wp = WAVE_P0 + (WAVE_P1 - WAVE_P0) * unit(s.look + 6), wt = (anim + u * wp) % wp;
                    final double wave = s.who == Figures.ADULT && wt < WAVE_T ? Math.sin(Math.PI * wt / WAVE_T) : 0;
                    Cast.figure(g, s, lit, 0, s.who == Figures.SEATED ? lean * 0.5 : lean, wave, anim * 7 + u * 6);
                    st.idle++;
                }
            }
        }
        // ---- the walkers on the streets
        final double[] w = walkers(scene, anim, lit, sheltered, flood, WALKERS.get());
        if (w != WALKERS.get()) WALKERS.set(w);
        final Composite comp = g.getComposite();
        for (int i = 0, n = (int) w[0]; i < n; i++) {
            final double x = w[1 + i * WALKER_N], y = w[2 + i * WALKER_N], alpha = w[4 + i * WALKER_N];
            if (x < wx0 - 14 || x > wx1 + 14 || y + 2 < cy0 || y - 16 > cy1) continue;
            final int dir = w[3 + i * WALKER_N] < 0 ? -1 : 1;
            final long look = (long) w[6 + i * WALKER_N];
            final double stride = w[7 + i * WALKER_N];
            if (alpha < 0.999) g.setComposite(AlphaComposite.SrcOver.derive((float) alpha));
            if (w[5 + i * WALKER_N] > 0) {
                final double dx = x - dir * 8.5;
                Figures.dog(g, dx, scene.terrain.at(dx) - KERB, look + 9, lit, 0, dir, stride * 1.7);
                st.dogs++;
            }
            Figures.walker(g, x, y, new Figures.Look(look, Long.remainderUnsigned(look >>> 20, CHILD_ONE_IN) == 0), lit, 0, dir, stride);
            if (alpha < 0.999) g.setComposite(comp);
            st.walkers++;
        }
    }

    /** Numbers per walker in the list {@link #walkers} fills: x, y of the feet, direction, opacity, dog (1 / 0), the look (a whole number under 2^52), the stride phase. */
    static final int WALKER_N = 7;
    private static final ThreadLocal<double[]> WALKERS = ThreadLocal.withInitial(() -> new double[1 + 64 * WALKER_N]);

    /** How many walkers a settlement has. */
    static int walkerCount(Model.Settlement s) { return (int) Model.clamp(Math.round(Math.sqrt(Math.max(0, s.population)) / WALK_PER), 1, WALK_MAX); }

    /**
     * Where every walker of the scene is at {@code anim}: {@code out[0]} = how many are out, then {@link #WALKER_N}
     * numbers each. A walker belongs to a settlement and to one street span inside it, which it paces end to end; it is
     * out only by day, on dry ground without snow or flood water, away from a tornado. The array is grown if needed and returned.
     */
    static double[] walkers(Scene scene, double anim, Lit lit, DoublePredicate sheltered, DoubleUnaryOperator flood, double[] out) {
        out[0] = 0;
        final double light = Lit.smooth((lit.day - WALK_DAY) / WALK_FADE_DAY);
        if (light <= 0 || scene.hello == null || scene.hello.roads.isEmpty()) return out;
        int n = 0;
        final Model.Ground gr = scene.ground;
        for (Model.Settlement s : scene.hello.settlements) {
            // the street spans inside the settlement
            double total = 0;
            for (Model.Road r : scene.hello.roads) total += span(r, s);
            if (total <= 0) continue;
            final int count = walkerCount(s);
            for (int i = 0; i < count; i++) {
                final long h = Figures.mix(s.id * 0x9E3779B97F4A7C15L + i * 0x632BE59BD9B4E019L + 0x57A1C);
                // (its span: by the length of the spans, so a long street has more of them)
                double pick = unit(h + 1) * total, a = 0, b = 0;
                for (Model.Road r : scene.hello.roads) {
                    final double len = span(r, s);
                    if (len <= 0) continue;
                    a = Math.max(r.x0, s.x0); b = Math.min(r.x1, s.x1);
                    if (pick < len) break;
                    pick -= len;
                }
                final double lo = a + WALK_MARGIN, len = b - a - 2 * WALK_MARGIN, v = WALK_V0 + (WALK_V1 - WALK_V0) * unit(h + 2);
                double ph = (anim * v + unit(h + 3) * 2 * len) % (2 * len);
                if (ph < 0) ph += 2 * len;
                final double x = lo + (ph < len ? ph : 2 * len - ph);
                double alpha = light;
                if (gr != null) alpha *= Lit.smooth((WALK_WET - gr.wetAt(x)) / WALK_FADE_WET) * Lit.smooth((WALK_SNOW - (scene.noSnow ? 0 : gr.snowAt(x))) / WALK_FADE_SNOW);
                if (alpha <= 0.004 || (sheltered != null && sheltered.test(x)) || (flood != null && flood.applyAsDouble(x) >= WALK_FLOOD)) continue;
                if (1 + (n + 1) * WALKER_N > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                final int k = 1 + n * WALKER_N;
                out[k] = x; out[k + 1] = scene.terrain.at(x) - KERB; out[k + 2] = ph < len ? 1 : -1; out[k + 3] = alpha;
                out[k + 4] = Long.remainderUnsigned(h >>> 8, DOG_ONE_IN) == 0 ? 1 : 0;
                out[k + 5] = (double) (h & ((1L << 52) - 1));
                out[k + 6] = ph * 0.55;
                n++;
            }
        }
        out[0] = n;
        return out;
    }

    /** The length of a street inside a settlement that walkers use; 0 for a highway or a piece too short. */
    private static double span(Model.Road r, Model.Settlement s) {
        if (r.highway()) return 0;
        final double len = Math.min(r.x1, s.x1) - Math.max(r.x0, s.x0);
        return len >= WALK_SPAN_MIN ? len : 0;
    }

    private static double unit(long s) { return (Figures.mix(s) >>> 11) / (double) (1L << 53); }
}
