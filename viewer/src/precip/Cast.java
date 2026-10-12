package precip;

import java.awt.Graphics2D;

/**
 * Phase O (OH5): who is where. The people of a building are decoration: they come from the object alone (its id and
 * seed), never from the simulation, a save or a clock, so the same building always has the same people in the same
 * places - in every render band, after a reload, on every machine.
 * <p>
 * A building has <b>spots</b> ({@link #spots}): its window panes (measured by painting the building once, exactly as
 * {@link BuildingPainter#panes} does, so they can never disagree with the painter), and the outdoor places its painter
 * names while it paints (by the door or on the porch, a seat, the yard). Who is in a spot is decided here:
 * <ul>
 *   <li>a <b>window</b> shows a shadow when it is lit and it is one of the building's figure windows (about
 *       {@link #WINDOW_SHARE} of them, at most as many as the building has people);</li>
 *   <li>an <b>outdoor</b> spot is taken when the outdoor life level ({@link #level}) is high enough for it: nobody is
 *       outside at night, in the rain, in snow, near a tornado or at a damaged building.</li>
 * </ul>
 * The standing figures are part of the building's still picture ({@link #paintBaked}, called by the painter); the live
 * pass ({@link Life}) adds what moves.
 */
final class Cast {
    private Cast() {}

    /** Kinds of spot. */
    static final int WINDOW = 0, PORCH = 1, SEAT = 2, YARD = 3;

    // ---- the presence rule (PLAN "People"; starting values)
    /** The share of a building's windows that show a figure when lit. */
    static final double WINDOW_SHARE = 0.55;
    /** An apartment block shows a figure in at most this share of its panes (a house: in as many as it has people). */
    static final double APARTMENT_SHARE = 0.25;
    /** Nobody is outdoors when it is darker / wetter / snowier than this ({@code Lit.night}, the object's wet and snow). */
    static final double NIGHT_MAX = 0.55, WET_MAX = 0.35, SNOW_MAX = 0.5;
    /** The share of the outdoor spots taken: {@code OUT_BASE + OUT_DAY x level / LEVELS}. */
    static final double OUT_BASE = 0.35, OUT_DAY = 0.5;
    /** The outdoor life levels 1 .. LEVELS (0 = nobody outside): the bake key carries the level, so a tile is repainted only when it changes. */
    static final int LEVELS = 4;
    /** A damaged building has nobody outside, and from {@link #WINDOW_DARK} on no figure at a window (its lights go out one by one from there). */
    static final double DAMAGE_OUT = 0.04, WINDOW_DARK = 0.2;
    /** A pane lower or narrower than this (world px) is too small for a figure. */
    static final double PANE_MIN_H = 4.5, PANE_MIN_W = 3.2;

    /** One place a person can be. */
    static final class Spot {
        final int type;
        /** A window: the pane (world px). Outdoors: {@code x}, {@code y} = where the feet (the seat, for {@link #SEAT}) are. */
        final float x, y, w, h;
        /** Outdoors: the way the figure faces (+1 / -1), and for a seat how far under it the feet are. */
        final float dir, drop;
        /** A window: the number of its pane, and what decides whether it is lit ({@code BuildingPainter.Ctx.on}). */
        final int pane;
        final float thr, du;
        /** A window: it shows a figure when lit. Outdoors: the kind of figure ({@link Figures#ADULT} ...). */
        final boolean figure;
        /** A window: the pane is big enough, and of a kind, to show a figure at all (standing there or walking past). */
        final boolean big;
        final int who;
        /** The person's own seed (their look, the moment they cross a window, the phase of their sway). */
        final long look;
        /** Outdoors: taken when this is under the share of the level; and the order in which the building's people come out. */
        final float u;
        int rank;

        Spot(int type, float x, float y, float w, float h, float dir, float drop, int pane, float thr, float du, boolean figure, boolean big, int who, long look, float u) {
            this.type = type; this.x = x; this.y = y; this.w = w; this.h = h; this.dir = dir; this.drop = drop;
            this.pane = pane; this.thr = thr; this.du = du; this.figure = figure; this.big = big; this.who = who; this.look = look; this.u = u;
        }

        boolean outdoor() { return type != WINDOW; }
    }

    private record Entry(BuildingPainter.Parts parts, Spot[] spots) {}

    private static final java.util.concurrent.ConcurrentHashMap<Long, Entry> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static double unit(long s) { return (Figures.mix(s) >>> 11) / (double) (1L << 53); }

    /** How many people live in a building: {@code clamp(round(w / 22 + u), 1, 4)}, one more from two floors. */
    static int people(Model.Obj o) {
        int n = (int) Model.clamp(Math.round(o.w / 22 + unit(o.seed * 0x9E3779B97F4A7C15L + o.id * 31 + 5)), 1, 4);
        return o.floors >= 2 ? n + 1 : n;
    }

    /** True for the kinds that have people at all: houses and apartment blocks. */
    static boolean lived(Model.Obj o) {
        if (!o.building) return false;
        switch (o.kind) {
            case "barn", "church", "windmill", "shop", "office", "skyscraper", "factory", "water_tower", "streetlamp" -> { return false; }
            default -> { return true; }
        }
    }

    /** The spots of a building, windows first (in the order the panes are painted), then the outdoor ones. Never null; kept per object. */
    static Spot[] spots(Model.Obj o) {
        if (!lived(o)) return NONE;
        BuildingPainter.Parts p = BuildingPainter.parts(o);
        Entry e = CACHE.get(o.id);
        if (e != null && e.parts == p) return e.spots;
        if (CACHE.size() > 8192) CACHE.clear();
        final long base = o.seed * 0x9E3779B97F4A7C15L + o.id * 0x632BE59BD9B4E019L;
        final int panes = p.xywh().length / 4, outs = p.spots().length / 5, n = people(o);
        Spot[] out = new Spot[panes + outs];
        // the figure windows: the panes big enough whose own number is under the share, the lowest numbers first, at most
        // as many as there are people (an apartment block: a quarter of its panes)
        final int cap = o.kind.equals("apartment") ? (int) Math.ceil(panes * APARTMENT_SHARE) : n;
        double[] rank = new double[panes];
        for (int i = 0; i < panes; i++) {
            boolean can = (p.style()[i] & BuildingPainter.W_FIGURE) != 0 && p.xywh()[i * 4 + 3] >= PANE_MIN_H && p.xywh()[i * 4 + 2] >= PANE_MIN_W;
            rank[i] = can ? unit(base + i * 7919L + 1) : 2;
        }
        for (int i = 0; i < panes; i++) {
            int lower = 0;
            for (int j = 0; j < panes; j++) if (rank[j] < rank[i] || (rank[j] == rank[i] && j < i)) lower++;
            boolean fig = rank[i] < WINDOW_SHARE && lower < cap;
            long look = Figures.mix(base + i * 104729L + 3);
            out[i] = new Spot(WINDOW, p.xywh()[i * 4], p.xywh()[i * 4 + 1], p.xywh()[i * 4 + 2], p.xywh()[i * 4 + 3], 0, 0, i, p.lit()[i * 2], p.lit()[i * 2 + 1],
                    fig, rank[i] < 2, (look & 7) == 0 ? Figures.CHILD : Figures.ADULT, look, 0);
        }
        for (int k = 0; k < outs; k++) {
            float[] s = p.spots();
            int type = (int) s[k * 5];
            long look = Figures.mix(base + k * 15485863L + 11);
            int who = type == SEAT ? Figures.SEATED : type == PORCH ? ((look & 7) == 1 ? Figures.CHILD : Figures.ADULT)
                    : switch ((int) ((look >>> 3) % 10)) { case 0, 1, 2 -> Figures.CHILD; case 3, 4 -> Figures.DOG; default -> Figures.ADULT; };
            out[panes + k] = new Spot(type, s[k * 5 + 1], s[k * 5 + 2], 0, 0, s[k * 5 + 3], s[k * 5 + 4], -1, 0, 0, true, false, who, look, (float) unit(base + k * 32452843L + 13));
        }
        for (int k = 0; k < outs; k++) {
            int lower = 0;
            for (int j = 0; j < outs; j++) if (out[panes + j].u < out[panes + k].u || (out[panes + j].u == out[panes + k].u && j < k)) lower++;
            out[panes + k].rank = lower;
        }
        CACHE.put(o.id, new Entry(p, out));
        return out;
    }

    private static final Spot[] NONE = new Spot[0];

    /**
     * The outdoor life level of a building, 0 .. {@link #LEVELS}: 0 = nobody outside (night, rain, snow, a tornado near,
     * storm damage), else the daylight in four steps. Part of the bake key of the ground tiles.
     * @param sheltered a grounded tornado is near (the wreck's test): people stay indoors
     */
    static int level(Model.Obj o, Lit lit, boolean sheltered) {
        if (sheltered || !lived(o) || o.damage >= DAMAGE_OUT) return 0;
        if (!(lit.night < NIGHT_MAX) || !(o.wet < WET_MAX) || !(o.snow < SNOW_MAX)) return 0;
        return 1 + (int) Math.min(LEVELS - 1, Math.floor((lit.day - (1 - NIGHT_MAX)) / NIGHT_MAX * LEVELS));
    }

    /** Is the outdoor spot taken at this level? (The building's people come out in a fixed order, and no more than it has.) */
    static boolean out(Model.Obj o, Spot s, int level) {
        return level > 0 && s.outdoor() && s.u < OUT_BASE + OUT_DAY * level / LEVELS && s.rank < people(o);
    }

    /** Is the window of this spot lit under this light? (The painter's own rule: {@code BuildingPainter.Ctx.on}.) */
    static boolean lit(Model.Obj o, Spot s, Lit lit) {
        return s.type == WINDOW && !(o.damage > 0.2 + 0.45 * s.du) && lit.night > s.thr;
    }

    /** Does the pane with this number show a figure when it is lit? (Asked by the painter for each lit pane.) */
    static boolean windowFigure(Model.Obj o, int pane) {
        if (o.damage >= WINDOW_DARK) return false;
        Spot[] s = spots(o);
        return pane >= 0 && pane < s.length && s[pane].type == WINDOW && s[pane].figure;
    }

    /** The seed of the person at a window pane. */
    static long windowLook(Model.Obj o, int pane) {
        Spot[] s = spots(o);
        return pane >= 0 && pane < s.length ? s[pane].look : 0;
    }

    /**
     * The standing people of a building's still picture: whoever is outdoors at this level, drawn where they stand.
     * (The shadows at the windows are drawn by the painter with each pane.)
     */
    static void paintBaked(Graphics2D g, Model.Obj o, Lit lit, double haze, int level) {
        if (level <= 0) return;
        for (Spot s : spots(o)) if (out(o, s, level)) figure(g, s, lit, haze, 0, 0, 0);
    }

    /** One outdoor figure in its spot. {@code lean} = its sway in world px, {@code wave} 0..1 a raised arm, {@code phase} a dog's wag. */
    static void figure(Graphics2D g, Spot s, Lit lit, double haze, double lean, double wave, double phase) {
        switch (s.who) {
            case Figures.SEATED -> Figures.seated(g, s.x, s.y, s.drop, new Figures.Look(s.look, false), lit, haze, s.dir < 0 ? -1 : 1, lean);
            case Figures.DOG -> Figures.dog(g, s.x, s.y, s.look, lit, haze, s.dir < 0 ? -1 : 1, phase);
            case Figures.CHILD -> Figures.standing(g, s.x, s.y, new Figures.Look(s.look, true), lit, haze, lean, wave);
            default -> Figures.standing(g, s.x, s.y, new Figures.Look(s.look, false), lit, haze, lean, wave);
        }
    }
}
