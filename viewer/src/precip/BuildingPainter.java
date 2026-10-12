package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Random;

/**
 * Every building kind and variant, plus the tree variants. The static part of a building
 * ({@link #paint}) is pure and is cached in scenery tiles; the few moving parts (windmill blades,
 * chimney smoke, aviation lights) are drawn per frame by {@link #paintLive}.
 * Per-building detail (window rows, door side, chimney, roof furniture, which windows are lit at
 * night) is derived from the object's {@code seed}; unknown kinds and variants fall back to the
 * kind's default look.
 * <p>Rain on the glass: a wet building has water beads on its panes. The beads that sit still are
 * part of the static picture ({@link Ctx#beads}, as many as the wetness level the scenery tiles are
 * keyed by); the few that run down a pane are drawn per frame by {@link #paintDrops}. Both follow the
 * wind: a running drop slants somewhere between straight down and the way the wind drives the rain
 * ({@link #slant}, its own share of the way per drop), and the still beads are smeared the way the rain
 * hit the glass ({@link #windLevel}: three coarse steps either way of a slowly averaged wind, so the
 * tiles are seldom repainted).
 */
final class BuildingPainter {
    private BuildingPainter() {}

    /** Every building kind {@link #paintIntact} has a painter for (besides {@code house}, whose look is its variant). The Debug mode's line-up draws one of each. */
    static final String[] KINDS = {"barn", "church", "windmill", "shop", "apartment", "office", "skyscraper", "factory", "water_tower", "streetlamp"};
    /** Every house variant; {@code cottage} is what an unknown or empty variant falls back to. */
    static final String[] HOUSE_VARIANTS = {"cottage", "cabin", "farmhouse", "townhouse", "bungalow", "manor", "adobe", "hut", "station"};
    /** Every tree / plant variant of {@link #paintTree}; {@code pine} is what an unknown variant falls back to. */
    static final String[] TREE_VARIANTS = {"pine", "bush", "birch", "oak", "palm", "cactus", "acacia", "shrub", "dead"};

    private static final int[][] DEF_WALL = {{222, 200, 164}, {196, 120, 96}, {228, 218, 198}, {186, 196, 204}, {200, 186, 150}};
    private static final int[][] DEF_ROOF = {{150, 60, 50}, {92, 70, 62}, {70, 84, 100}, {120, 84, 60}};

    // ---- rain beads on the panes (world px unless said otherwise)
    /** Below this many device px per world px a bead would be smaller than about a pixel: none are drawn. */
    static final double BEAD_MIN_PX = 1.6;
    private static final double BEAD_DENSITY = 0.10;            // beads per square world px of glass, on a soaked building ...
    private static final int BEAD_MIN = 2, BEAD_MAX = 16;       // ... but this many per pane at least / at most
    private static final double BEAD_R0 = 0.26, BEAD_R1 = 0.60; // bead radius (most are near the small end)
    // A bead under BEAD_LENS_PX device px across is one soft square (and only every other one is drawn: specks that small
    // read as wet glass at half the number); from there on it has a highlight and, from BEAD_SHADE_PX, a dark underside,
    // built from a few rectangles (measured: an antialiased rectangle costs a fifth of a microsecond, an ellipse six times
    // that, a path of several shapes more again), and from BEAD_ROUND_PX on from true ellipses.
    private static final double BEAD_SHADE_PX = 4.0;
    private static final double BEAD_LENS_PX = 3.0, BEAD_ROUND_PX = 5.5;
    private static final Color BEAD_SHADE = new Color(6, 12, 24, 60), BEAD_SHINE_LIT = new Color(255, 255, 244, 230);
    // ---- the drops that run down a pane (paintDrops)
    /** Below this many device px per world px the running drops are not drawn. */
    static final double RUN_MIN_PX = 1.6;
    private static final double RUN_WET0 = 0.25;                // no drop runs on a building drier than this
    private static final double RUN_SHARE = 0.40;               // share of the panes that can have a running drop ...
    private static final int RUN_MAX = 48;                      // ... but not more panes than this per building
    private static final double RUN_DUTY = 0.55;                // part of its cycle a drop is on the pane (the rest it is gone)
    private static final double RUN_T0 = 5, RUN_T1 = 12;        // seconds per cycle
    private static final double RUN_R0 = 0.34, RUN_R1 = 0.54;   // radius of a running drop
    // ---- the wind on the glass (m/s: the u of the frame's wind grid; positive blows to the right)
    /** In air slower than this a drop runs straight down, exactly as it does without any wind. */
    static final double WIND_CALM = 1.0;
    /** From this wind on a drop that goes all the way with the wind runs at {@link #SLANT_MAX_DEG} from the vertical. */
    static final double WIND_FULL = 9.0;
    /** The most a drop ever leans from the vertical, in degrees: it never runs sideways, let alone upwards. */
    static final double SLANT_MAX_DEG = 50;
    private static final double SLANT_MAX = Math.tan(Math.toRadians(SLANT_MAX_DEG));
    // the still beads are baked into the scenery tiles, so they only know the wind in coarse levels -3 .. 3: a level is
    // WIND_LEVEL_STEP m/s wide and is only left when the wind is WIND_LEVEL_HOLD levels away from its middle, and the wind
    // it is taken from is an average over WIND_SMOOTH_S seconds (Renderer.prepareShared), so the gusts do not flip it and a
    // gusty day does not repaint the tiles. A bead of level L is smeared along the lean of a wind of L x WIND_LEVEL_STEP
    static final double WIND_LEVEL_STEP = 3.0, WIND_LEVEL_HOLD = 0.75, WIND_SMOOTH_S = 8.0;
    static final int WIND_LEVEL_MAX = 3;

    /**
     * How far the wind can make a drop on glass lean: the tangent of its angle from the vertical when it goes all the way
     * with the wind (signed like {@code u}; 0 in calm air, at most tan {@link #SLANT_MAX_DEG}). Stronger wind, more lean.
     */
    static double slant(double u) {
        double a = Math.abs(u);
        if (!(a > WIND_CALM)) return 0;
        double k = Math.min(1, (a - WIND_CALM) / (WIND_FULL - WIND_CALM)) * SLANT_MAX;
        return u < 0 ? -k : k;
    }

    /**
     * The lean of one drop: {@code share} (0..1, the drop's own) of the way from the gravity's angle (straight down) to
     * the wind's ({@code full}, from {@link #slant}). Returns the tangent of its angle from the vertical.
     */
    static double lean(double full, double share) { return full == 0 ? 0 : Math.tan(Math.atan(full) * share); }

    /** The coarse wind level -3 .. 3 the still beads are painted with, given the level they have now (see above). */
    static int windLevel(double u, int was) {
        double l = u / WIND_LEVEL_STEP;
        if (Math.abs(l - was) <= WIND_LEVEL_HOLD) return was;
        return (int) Math.max(-WIND_LEVEL_MAX, Math.min(WIND_LEVEL_MAX, Math.round(l)));
    }

    // ---- Phase O: house detail and people
    /**
     * Below this many device px per world px a building is painted exactly as before Phase O: no siding, shingles or
     * window frames, no yard, no people (a figure would be a speck). The beads' threshold, on purpose.
     */
    static final double LIFE_MIN_PX = 1.6;
    /** The finest lines of the detail (half a world px wide) are drawn from this many device px per world px on. */
    static final double FINE_MIN_PX = 3.0;
    /** What a window gets besides its pane ({@code Ctx.win}): frame, sill, shutters, curtains, a figure when lit, a flower box. */
    static final int W_FRAME = 1, W_SILL = 2, W_SHUTTER = 4, W_CURTAIN = 8, W_FIGURE = 16, W_BOX = 32;
    private static final int D_PANEL = 1, D_KNOB = 2, D_STEP = 4, D_PLATE = 8, D_LAMP = 16, D_FRAME = 32, D_PLANK = 64;
    private static final int P_GRILL = 0, P_SWING = 1, P_SHED = 2, P_BIKE = 3, P_TRAMPOLINE = 4, P_WASHING = 5, P_WOOD = 6, P_BARREL = 7, P_BENCH = 8, P_POT = 9, P_CHAIR = 10;
    /** What may stand in a yard (0-2 a house, by its seed). */
    private static final int[] YARD_COMMON = {P_GRILL, P_SWING, P_SHED, P_BIKE, P_TRAMPOLINE, P_WASHING}, YARD_FARM = {P_SHED, P_BIKE, P_SWING, P_BARREL};
    /** A row of shingles is this high, a shingle this wide (world px). */
    private static final double SHINGLE_ROW = 3, SHINGLE_W = 5;
    private static final Color SHINGLE_SHADOW = new Color(0, 0, 0, 40), SHINGLE_LIGHT = new Color(255, 255, 255, 20), SHINGLE_DARK = new Color(0, 0, 0, 24);
    private static final Color BRICK_BED = new Color(0, 0, 0, 26), BRICK_JOINT = new Color(0, 0, 0, 20), DOOR_PANEL = new Color(0, 0, 0, 44);
    private static final int[][] CURTAINS = {{236, 226, 206}, {214, 170, 170}, {170, 190, 214}, {226, 206, 150}};
    private static final int[][] FLOWERS = {{226, 84, 96}, {240, 200, 80}, {226, 130, 190}, {240, 240, 236}};

    /** One step of the beads' private number stream (never the building's {@code Random}: that would change its look). */
    private static long step(long s) { return s * 6364136223846793005L + 1442695040888963407L; }

    /** 0..1 from a stream state. */
    private static double unit(long s) { return (s >>> 40) / (double) (1 << 24); }

    private static long mix(long n) {
        n ^= n >>> 33; n *= 0xff51afd7ed558ccdL; n ^= n >>> 33; n *= 0xc4ceb9fe1a85ec53L; n ^= n >>> 33;
        return n;
    }

    /** Drawing context for one building: lighting, haze, wetness and its private random stream. */
    private static final class Ctx {
        final Graphics2D g;
        final Lit lit;
        final double haze, wet, snow, night;
        final Random r;
        final Model.Obj o;
        final Color wall, roof;
        final double x0, y, w, h;
        /** Device px per world px of this paint, and how much of the rain beads shows on the panes (0 = none). */
        final double px, beadWet;
        /** The lean of the smear of the still beads (tangent from the vertical, signed like the wind; 0 = round beads). */
        final double smear;
        /** While {@link #panes} measures the building: the panes drawn so far (x, y, w, h each). Null = just paint. */
        float[] rec;
        int recN;
        // the bead colours for the pane colour they were last made for (the unlit panes of a building all share one)
        private int beadFor;
        private Color beadBody, beadSpeck, beadShine;

        // ---- Phase O
        /** The house detail and the people are drawn (the paint is big enough, or the building is being measured). */
        final boolean detail;
        /** The paint is big enough for the finest of it too (shutter slats, the upright joints of brickwork): {@link #FINE_MIN_PX}. */
        final boolean fine;
        /** The building is being measured ({@link #parts}): nothing is seen, the panes and the outdoor spots are noted. */
        final boolean measuring;
        /** The outdoor life level 0 .. {@link Cast#LEVELS} this picture is painted at ({@link Cast#level}). */
        final int life;
        /** The style of the panes painted next (W_ bits; 0 = a bare pane, as before Phase O), and whether {@link #gable} lays shingles. */
        int win;
        boolean roofTex;
        Color trim;
        /** The number of panes painted so far. */
        int paneN;
        /** While measuring, per pane: what decides whether it is lit (threshold, damage number) and its style; the outdoor spots (type, x, y, dir, drop). */
        float[] recLit, recSpot;
        byte[] recStyle;
        int recSpotN;
        private double lastThr, lastDu;
        /** The detail's own number stream (never the building's {@code r}: that would move its doors and windows). */
        private long ds;

        Ctx(Graphics2D g, Model.Obj o, Lit lit, double haze) { this(g, o, lit, haze, 0); }

        Ctx(Graphics2D g, Model.Obj o, Lit lit, double haze, int wind) { this(g, o, lit, haze, wind, 0, false); }

        /** @param life the outdoor life level, or -1 = work it out from the object and the light (nobody sheltering); measure = see {@link #measuring} */
        Ctx(Graphics2D g, Model.Obj o, Lit lit, double haze, int wind, int life, boolean measure) {
            this.g = g; this.o = o; this.lit = lit; this.haze = haze;
            smear = slant(wind * WIND_LEVEL_STEP);
            wet = Model.clamp(o.wet, 0, 1); snow = Model.clamp(o.snow, 0, 1);
            night = lit.night;
            r = new Random(o.seed * 0x9E3779B97F4A7C15L + 77);
            int[] wc = o.wall != null ? o.wall : DEF_WALL[(int) Math.floorMod(o.seed, DEF_WALL.length)];
            int[] rc = o.roof != null ? o.roof : DEF_ROOF[(int) Math.floorMod(o.seed / 7, DEF_ROOF.length)];
            wall = new Color(wc[0], wc[1], wc[2]);
            roof = new Color(rc[0], rc[1], rc[2]);
            w = o.w; h = o.h; x0 = o.x - o.w / 2; y = o.y;
            AffineTransform at = g.getTransform();
            px = Math.hypot(at.getScaleX(), at.getShearY());
            // (the levels the scenery tiles are repainted at, so every tile of a building shows the same beads; snow wins)
            beadWet = px >= BEAD_MIN_PX ? Math.round(wet * 6) / 6.0 * Model.clamp(1 - Math.round(snow * 12) / 12.0 * 4, 0, 1) : 0;
            measuring = measure;
            detail = measure || px >= LIFE_MIN_PX;
            fine = measure || px >= FINE_MIN_PX;
            this.life = !detail || measure ? 0 : life >= 0 ? life : Cast.level(o, lit, false);
            ds = mix(o.seed * 0x9E3779B97F4A7C15L + 0xDE7A11L);
            if (measure) { rec = new float[64]; recLit = new float[32]; recStyle = new byte[16]; recSpot = new float[20]; }
        }

        /** Surface colour: wet darkening, lighting, haze. */
        Color c(Color base) { return lit.lit(Lit.mix(base, Lit.mul(base, 0.72), wet * 0.55), haze); }

        Color c(int r, int g, int b) { return c(new Color(r, g, b)); }

        void rect(double x, double y, double w, double h, Color col) {
            g.setColor(col);
            g.fill(new Rectangle2D.Double(x, y, w, h));
        }

        void poly(Color col, double... pts) {
            Path2D.Double p = new Path2D.Double();
            p.moveTo(pts[0], pts[1]);
            for (int k = 2; k + 1 < pts.length; k += 2) p.lineTo(pts[k], pts[k + 1]);
            p.closePath();
            g.setColor(col);
            g.fill(p);
        }

        Color snowCol() { return lit.lit(new Color(246, 249, 255), haze * 0.7); }

        /** Is this window lit right now? Each window has its own threshold, so they come on one by one at dusk. */
        boolean on(double share) {
            double u = r.nextDouble(), thr = 0.22 + u * 0.75 / Math.max(0.05, share);
            // storm damage puts the lights out, window by window between a fifth and two thirds damage
            // (a ruin used to keep its lit windows: glowing panes in the rubble at night)
            lastThr = thr; lastDu = (u * 7.13) % 1.0;
            if (o.damage > 0.2 + 0.45 * ((u * 7.13) % 1.0)) return false;
            return night > thr;
        }

        /** One window pane; {@code share} = fraction of windows that end up lit deep in the night. */
        void window(double x, double y, double w, double h, double share, boolean cool) {
            boolean lit2 = on(share);
            int warm = r.nextInt(3);
            final Color pane;
            if (lit2) {
                Color wc = cool ? new Color(214, 232, 255) : warm == 0 ? new Color(255, 214, 120) : warm == 1 ? new Color(255, 232, 170) : new Color(255, 196, 96);
                pane = Lit.mix(wc, lit.haze, haze * 0.6);
            } else {
                Color glass = Lit.mix(lit.lit(new Color(96, 124, 156)), lit.skyBot, 0.30);
                pane = Lit.mix(glass, lit.haze, haze);
            }
            // Phase O: what is behind the glass (curtains, a shadow at a lit window), then the rain on it, then its frame
            final boolean dressed = detail && win != 0 && w >= 2.5 && h >= 3;
            if (dressed && (win & W_FRAME) != 0) { g.setColor(trimCol()); g.fill(new Rectangle2D.Double(x - 0.7, y - 0.7, w + 1.4, h + 1.4)); }
            g.setColor(pane);
            g.fill(new Rectangle2D.Double(x, y, w, h));
            final int idx = paneN++;
            if (rec != null) {
                if (recN + 4 > rec.length) rec = java.util.Arrays.copyOf(rec, rec.length * 2);
                rec[recN++] = (float) x; rec[recN++] = (float) y; rec[recN++] = (float) w; rec[recN++] = (float) h;
                if (recLit != null) {
                    if (idx * 2 + 2 > recLit.length) recLit = java.util.Arrays.copyOf(recLit, recLit.length * 2);
                    if (idx + 1 > recStyle.length) recStyle = java.util.Arrays.copyOf(recStyle, recStyle.length * 2);
                    recLit[idx * 2] = (float) lastThr; recLit[idx * 2 + 1] = (float) lastDu; recStyle[idx] = (byte) win;
                }
            }
            if (dressed) { inside(x, y, w, h, lit2, pane, idx); g.setColor(pane); }
            if (beadWet > 0) beads(x, y, w, h, lit2);
            if (dressed) frame(x, y, w, h);
        }

        /**
         * Rain beads on the pane just filled (its colour is still the current one): the wetter the building the more of
         * them, always the same ones in the same places (from the building's seed and where the pane is, nothing random).
         * In wind ({@link #smear}) each bead keeps its place and gets a fainter tail up and upwind of it, where the drop
         * that made it came from: it reads as smeared along the slant of the rain.
         */
        void beads(double x, double y, double w, double h, boolean lit2) {
            if (w < 1.6 || h < 1.6) return;
            int n = Math.max(BEAD_MIN, Math.min(BEAD_MAX, (int) Math.round(w * h * BEAD_DENSITY)));
            long s = mix(o.seed * 0x9E3779B97F4A7C15L + Math.round((x - o.x) * 8) * 73856093L + Math.round((y - o.y) * 8) * 19349663L);
            Color glass = g.getColor();
            if (beadBody == null || glass.getRGB() != beadFor) {
                beadFor = glass.getRGB();
                // a bead is a little lens: paler than a dark pane, darker than a lit one
                beadBody = Lit.alpha(lit2 ? Lit.mul(glass, 0.78) : Lit.mix(glass, Lit.mix(lit.skyBot, Color.WHITE, 0.55), 0.55), 215);
                beadShine = lit2 ? BEAD_SHINE_LIT : new Color(255, 255, 255, (int) (90 + 150 * lit.light));
                beadSpeck = Lit.alpha(beadBody, 165);        // (the smallest ones are fainter: a pane full of them is not to look like static)
            }
            final Color body = beadBody, shine = beadShine, speck = beadSpeck;
            final double sm = smear;
            Rectangle2D.Double q = new Rectangle2D.Double();
            g.setColor(speck);
            for (int k = 0; k < n; k++) {
                double th = unit(s = step(s)), ur = unit(s = step(s)), ux = unit(s = step(s)), uy = unit(s = step(s));
                if (beadWet < 0.10 + 0.85 * th) continue;
                double r = Math.min(BEAD_R0 + (BEAD_R1 - BEAD_R0) * ur * ur, Math.min(w, h) * 0.3), ry = r * 1.15, d = r * 2 * px;
                double bx = x + r + (w - 2 * r) * ux, by = y + ry + (h - 2 * ry) * uy;
                if (d < BEAD_LENS_PX) {
                    if ((k & 1) == 0) {
                        q.setRect(bx - r * 0.85, by - r * 0.85, r * 1.7, r * 1.7); g.fill(q);
                        if (sm != 0 && onPane(bx - r * 0.6 - sm * r * 1.1, by - r * 1.7, r * 1.2, x, y, w)) { q.setRect(bx - r * 0.6 - sm * r * 1.1, by - r * 1.7, r * 1.2, r * 1.2); g.fill(q); }
                    }
                    continue;
                }
                if (d < BEAD_ROUND_PX) {
                    if (sm != 0) {
                        if (onPane(bx - r * 0.55 - sm * ry * 1.0, by - ry * 1.55, r * 1.1, x, y, w)) { q.setRect(bx - r * 0.55 - sm * ry * 1.0, by - ry * 1.55, r * 1.1, ry * 1.1); g.fill(q); }
                        if (onPane(bx - r * 0.32 - sm * ry * 1.9, by - ry * 2.2, r * 0.64, x, y, w)) { q.setRect(bx - r * 0.32 - sm * ry * 1.9, by - ry * 2.2, r * 0.64, ry * 0.64); g.fill(q); }
                    }
                    if (d >= BEAD_SHADE_PX) { g.setColor(BEAD_SHADE); q.setRect(bx - r * 0.62, by - ry * 0.45, r * 1.4, ry * 1.7); g.fill(q); }
                    g.setColor(body);
                    q.setRect(bx - r, by - ry * 0.55, r * 2, ry * 1.1); g.fill(q);
                    q.setRect(bx - r * 0.58, by - ry, r * 1.16, ry * 2); g.fill(q);
                    g.setColor(shine);
                    q.setRect(bx - r * 0.55, by - ry * 0.60, r * 0.6, r * 0.6); g.fill(q);
                } else {
                    if (sm != 0) {
                        if (onPane(bx - r * 0.62 - sm * ry * 1.0, by - ry * 1.62, r * 1.24, x, y, w)) g.fill(new Ellipse2D.Double(bx - r * 0.62 - sm * ry * 1.0, by - ry * 1.62, r * 1.24, ry * 1.24));
                        if (onPane(bx - r * 0.36 - sm * ry * 1.9, by - ry * 2.26, r * 0.72, x, y, w)) g.fill(new Ellipse2D.Double(bx - r * 0.36 - sm * ry * 1.9, by - ry * 2.26, r * 0.72, ry * 0.72));
                    }
                    g.setColor(BEAD_SHADE);
                    g.fill(new Ellipse2D.Double(bx - r * 0.92, by - ry * 0.72, r * 2, ry * 2));
                    g.setColor(body);
                    g.fill(new Ellipse2D.Double(bx - r, by - ry, r * 2, ry * 2));
                    g.setColor(shine);
                    g.fill(new Ellipse2D.Double(bx - r * 0.62, by - ry * 0.66, r * 0.62, r * 0.62));
                }
                g.setColor(speck);
            }
        }

        /** Is a piece of a bead's tail ({@code tw} wide, its top-left corner at tx, ty) still on the pane? (It never reaches below it.) */
        private static boolean onPane(double tx, double ty, double tw, double x, double y, double w) { return tx >= x && tx + tw <= x + w && ty >= y; }

        /** A regular grid of windows inside the given facade rectangle. */
        void grid(double fx, double fy, double fw, double fh, int cols, int rows, double fillX, double fillY, double share, boolean cool) {
            if (cols <= 0 || rows <= 0) return;
            double cw = fw / cols, ch = fh / rows, ww = cw * fillX, wh = ch * fillY;
            for (int j = 0; j < rows; j++) for (int i = 0; i < cols; i++)
                window(fx + i * cw + (cw - ww) / 2, fy + j * ch + (ch - wh) / 2, ww, wh, share, cool);
        }

        /** Snow lying on a flat roof edge. */
        void snowFlat(double x, double y, double w) {
            if (snow < 0.04) return;
            double t = 1.5 + 5 * snow;
            g.setColor(snowCol());
            g.fill(new java.awt.geom.RoundRectangle2D.Double(x - 1, y - t, w + 2, t + 1, t, t));
        }

        /** Snow on a gable roof given its three corners. */
        void snowGable(double lx, double ly, double px, double py, double rx, double ry) {
            if (snow < 0.04) return;
            double t = 2 + 6 * snow;
            poly(snowCol(), lx, ly, px, py - t * 0.6, rx, ry, rx - t, ry - 1, px, py + t, lx + t, ly - 1);
        }

        /** A pitched roof with a slight left-right shading. Returns nothing; draws snow too. */
        void gable(double lx, double rx, double eaveY, double peakX, double peakY, Color col) {
            Path2D.Double p = new Path2D.Double();
            p.moveTo(lx, eaveY);
            p.lineTo(peakX, peakY);
            p.lineTo(rx, eaveY);
            p.closePath();
            g.setPaint(new GradientPaint((float) lx, 0, Lit.mul(col, 1.1), (float) rx, 0, Lit.mul(col, 0.8)));
            g.fill(p);
            if (detail && roofTex) shingles(lx, rx, eaveY, peakX, peakX, peakY);
            snowGable(lx, eaveY, peakX, peakY, rx, eaveY);
        }

        void door(double x, double w, double h, Color col) { rect(x, y - h, w, h + 1, col); }

        void shadeRight(double x, double yTop, double w, double h, double frac) {
            g.setColor(new Color(0, 0, 0, 36));
            g.fill(new Rectangle2D.Double(x + w * (1 - frac), yTop, w * frac, h));
        }

        // ------------------------------------------------------------ Phase O: house detail and people
        // (drawn only when `detail` is set, i.e. from LIFE_MIN_PX device px per world px; none of it touches the stream r,
        // so the building keeps its doors, windows and lights; what varies comes from the detail stream ds)

        /** The next number 0..1 of the detail stream. */
        double du() { ds = step(ds); return unit(ds); }

        /** While the building is measured: note an outdoor place for a person ({@link Cast}). */
        void spot(int type, double x, double y, double dir, double drop) {
            if (!measuring) return;
            if (recSpotN + 5 > recSpot.length) recSpot = java.util.Arrays.copyOf(recSpot, recSpot.length * 2);
            recSpot[recSpotN++] = type; recSpot[recSpotN++] = (float) x; recSpot[recSpotN++] = (float) y; recSpot[recSpotN++] = (float) dir; recSpot[recSpotN++] = (float) drop;
        }

        /** The colour of window frames, sills and door frames (white paint unless the variant set its own). */
        Color trimCol() { if (trim == null) trim = c(238, 234, 226); return trim; }

        /** Behind the glass of the pane just filled: the curtains, and the shadow of whoever stands at a lit window. */
        void inside(double x, double y, double w, double h, boolean lit2, Color pane, int idx) {
            final long hs = mix(o.seed * 31 + idx * 1013L + 7);
            if ((win & W_CURTAIN) != 0 && w >= 4) {
                final int[] hue = CURTAINS[(int) ((hs >>> 8) & 3)];
                final Color cur = lit2 ? Lit.mix(Lit.mul(pane, 0.84), new Color(hue[0], hue[1], hue[2]), 0.40) : Lit.mix(pane, Lit.mix(lit.lit(new Color(hue[0], hue[1], hue[2])), this.lit.haze, haze), 0.34);
                final double cw = w * (0.17 + 0.09 * unit(hs));
                if ((win & W_FRAME) == 0 || !fine) {
                    // (plain strips: an apartment block has many, and from afar the drape does not show)
                    rect(x, y, cw * 0.8, h, cur);
                    rect(x + w - cw * 0.8, y, cw * 0.8, h, cur);
                } else {
                    poly(cur, x, y, x + cw, y, x + cw * 0.5, y + h, x, y + h);
                    poly(cur, x + w, y, x + w - cw, y, x + w - cw * 0.5, y + h, x + w, y + h);
                    rect(x, y, w, Math.min(1.1, h * 0.12), Lit.mul(cur, 0.9));
                }
            }
            if (lit2 && (win & W_FIGURE) != 0 && !measuring && h >= Cast.PANE_MIN_H && w >= Cast.PANE_MIN_W && Cast.windowFigure(o, idx)) {
                final long look = Cast.windowLook(o, idx);
                final boolean child = (look & 7) == 0;
                double bh = Math.min(Math.min(h * 0.9, w * 1.02), child ? Figures.BUST_H0 * 0.8 : Figures.BUST_H0 + (Figures.BUST_H1 - Figures.BUST_H0) * unit(look));
                double bw = bh * 0.92, cx = x + bw / 2 + (w - bw) * (0.2 + 0.6 * unit(look * 31 + 5));
                Figures.bust(g, cx, y + h, bh, Lit.alpha(Lit.mix(Lit.mul(pane, 0.2), new Color(34, 24, 30), 0.5), 236), look, 0);
            }
        }

        /** Around the pane (its frame was laid under it): sill, shutters, a window box with flowers (what the style of the moment asks for). */
        void frame(double x, double y, double w, double h) {
            final Color tr = trimCol();
            if ((win & W_SILL) != 0) rect(x - 1.3, y + h - 0.05, w + 2.6, 1.15, Lit.mul(tr, 0.88));
            if ((win & W_SHUTTER) != 0 && w >= 4) {
                final double sw = Math.max(1.6, w * 0.26);
                final Color sh = c(Lit.mix(roof, new Color(60, 84, 70), 0.45)), slat = Lit.mul(sh, 0.76);
                for (int k = 0; k < 2; k++) {
                    final double sx = k == 0 ? x - 0.9 - sw : x + w + 0.9;
                    rect(sx, y - 0.4, sw, h + 0.8, sh);
                    if (fine) for (double yy = y + 1; yy < y + h - 0.5; yy += 1.7) rect(sx + 0.3, yy, sw - 0.6, 0.45, slat);
                }
            }
            if ((win & W_BOX) != 0 && w >= 3) {
                rect(x - 0.6, y + h + 1.0, w + 1.2, 1.7, c(118, 82, 56));
                final long hs = mix(o.seed * 17 + Math.round((x - o.x) * 8) * 31L + Math.round((y - o.y) * 8));
                final Color leaf = c(70, 132, 66), bloom = c(FLOWERS[(int) ((hs >>> 4) % FLOWERS.length)][0], FLOWERS[(int) ((hs >>> 4) % FLOWERS.length)][1], FLOWERS[(int) ((hs >>> 4) % FLOWERS.length)][2]);
                int k = 0;
                for (double xx = x - 0.3; xx < x + w - 0.6; xx += 1.25, k++) rect(xx, y + h + (k % 2 == 0 ? 0.0 : 0.35), 1.1, 1.1, k % 2 == 0 ? bloom : leaf);
            }
        }

        /**
         * Rows of shingles on a roof face whose lower edge runs from xl0 to xr0 at y0 and whose upper edge from xl1 to
         * xr1 at y1 (a gable: xl1 = xr1): the shadow under every row and single shingles a tone lighter or darker.
         */
        void shingles(double xl0, double xr0, double y0, double xl1, double xr1, double y1) {
            final double rise = y0 - y1;
            if (rise < 4) return;
            final Rectangle2D.Double q = new Rectangle2D.Double();
            int row = 0;
            for (double yy = y0 - SHINGLE_ROW; yy > y1 + 1.2; yy -= SHINGLE_ROW, row++) {
                // (a row is as wide as the roof is at its upper edge, so it stays on the roof)
                final double t = (y0 - yy) / rise, xl = xl0 + (xl1 - xl0) * t + 0.4, xr = xr0 + (xr1 - xr0) * t - 0.4;
                if (xr - xl < 2) break;
                g.setColor(SHINGLE_SHADOW);
                q.setRect(xl, yy + SHINGLE_ROW - 0.55, xr - xl, 0.55); g.fill(q);
                long s = mix(o.seed * 0x9E3779B97F4A7C15L + row * 7919L + 3);
                for (double xx = xl + ((row & 1) == 0 ? 0 : SHINGLE_W / 2); xx < xr - 1; xx += SHINGLE_W) {
                    s = step(s);
                    final int pick = (int) ((s >>> 40) & 7);
                    if (pick > 2) continue;
                    g.setColor(pick == 0 ? SHINGLE_LIGHT : SHINGLE_DARK);
                    q.setRect(xx, yy, Math.min(SHINGLE_W - 0.5, xr - xx), SHINGLE_ROW - 0.55); g.fill(q);
                }
            }
        }

        /** The gutter along an eave and a downpipe from it to the ground. */
        void gutter(double xl, double xr, double eaveY, double pipeX, double groundY) {
            final Color zinc = c(186, 190, 196);
            rect(xl, eaveY - 0.35, xr - xl, 1.25, zinc);
            rect(pipeX - 0.55, eaveY + 0.9, 1.1, groundY - eaveY - 2.4, Lit.mul(zinc, 0.92));
            rect(pipeX - 0.9, eaveY + (groundY - eaveY) * 0.5, 1.8, 0.6, Lit.mul(zinc, 0.72));
        }

        /** The furniture of a door already painted at (dx, y - dh), dw wide: panels or planks, knob, frame, step, number plate, lamp. */
        void doorTrim(double dx, double dw, double dh, int what) {
            final double top = y - dh;
            if ((what & D_FRAME) != 0) {
                final Color tr = trimCol();
                rect(dx - 0.8, top - 0.8, dw + 1.6, 0.85, tr);
                rect(dx - 0.8, top, 0.85, dh, tr);
                rect(dx + dw - 0.05, top, 0.85, dh, tr);
            }
            if ((what & D_PANEL) != 0) {
                g.setColor(DOOR_PANEL);
                g.fill(new Rectangle2D.Double(dx + dw * 0.18, top + dh * 0.10, dw * 0.64, dh * 0.34));
                g.fill(new Rectangle2D.Double(dx + dw * 0.18, top + dh * 0.53, dw * 0.64, dh * 0.37));
            }
            if ((what & D_PLANK) != 0) {
                g.setColor(DOOR_PANEL);
                for (int k = 1; k < 4; k++) g.fill(new Rectangle2D.Double(dx + dw * k / 4.0 - 0.25, top + 0.6, 0.5, dh - 0.6));
                g.fill(new Rectangle2D.Double(dx + 0.5, top + dh * 0.22, dw - 1, 0.7));
                g.fill(new Rectangle2D.Double(dx + 0.5, top + dh * 0.72, dw - 1, 0.7));
            }
            if ((what & D_KNOB) != 0) rect(dx + dw * 0.76, y - dh * 0.47, 0.95, 0.95, c(226, 194, 112));
            if ((what & D_STEP) != 0) rect(dx - 1.7, y - 1.3, dw + 3.4, 2.3, c(160, 156, 150));
            if ((what & D_PLATE) != 0) {
                rect(dx + dw + 1.5, top + 1.2, 2.3, 1.6, c(242, 242, 238));
                rect(dx + dw + 2.1, top + 1.65, 1.1, 0.7, c(40, 40, 46));
            }
            if ((what & D_LAMP) != 0) lamp(dx - 2.1, top + 0.4);
        }

        /** A small lantern with its top at (x, y): dark by day, lit with a little glow at night (never on a damaged building). */
        void lamp(double x, double y) {
            final double on = Lit.smooth((night - 0.25) / 0.3) * (o.damage < 0.04 ? 1 : 0);
            if (on > 0.02) {
                g.setColor(new Color(255, 214, 140, (int) (30 * on)));
                g.fill(new Ellipse2D.Double(x - 5.5, y + 1.1 - 5.5, 11, 11));
                g.setColor(new Color(255, 220, 150, (int) (58 * on)));
                g.fill(new Ellipse2D.Double(x - 2.7, y + 1.1 - 2.7, 5.4, 5.4));
            }
            rect(x - 1.1, y - 0.5, 2.2, 0.7, c(48, 48, 54));
            rect(x - 0.8, y + 0.2, 1.6, 2.0, on > 0.02 ? Lit.mix(c(120, 120, 110), new Color(255, 236, 180), on) : c(150, 156, 160));
        }

        /** A fence from xa to xb on the ground: white pickets (with a gate in the middle if asked), or two low boards on posts. */
        void fence(double xa, double xb, double hgt, boolean picket, boolean gate) {
            if (picket) {
                final Color col = c(236, 232, 224);
                rect(xa, y - hgt * 0.72, xb - xa, 0.8, col);
                rect(xa, y - hgt * 0.26, xb - xa, 0.8, col);
                for (double xx = xa; xx < xb - 0.6; xx += 2.3) rect(xx, y - hgt, 1.05, hgt + 0.7, col);
                if (gate) {
                    final double mid = (xa + xb) / 2;
                    rect(mid - 3.3, y - hgt - 1.5, 1.4, hgt + 2.2, Lit.mul(col, 0.9));
                    rect(mid + 1.9, y - hgt - 1.5, 1.4, hgt + 2.2, Lit.mul(col, 0.9));
                }
            } else {
                final Color col = c(146, 112, 80), dark = Lit.mul(col, 0.8);
                rect(xa, y - hgt, xb - xa, 1.15, col);
                rect(xa, y - hgt * 0.46, xb - xa, 1.15, col);
                final double gap = (xb - xa - 1.2) / 2;
                for (int k = 0; k < 3; k++) rect(xa + k * gap, y - hgt - 0.6, 1.2, hgt + 1.3, dark);
            }
        }

        /** A clipped hedge on the ground from xa to xb. */
        void hedge(double xa, double xb) {
            if (xb - xa < 4) return;
            final Color green = snow > 0.3 ? Lit.mix(c(58, 104, 62), snowCol(), snow * 0.6) : c(58, 104, 62);
            rect(xa, y - 3.4, xb - xa, 4.2, green);
            int k = 0;
            for (double xx = xa; xx < xb - 0.5; xx += 3.1, k++) {
                final double up = 0.5 + 1.4 * du();
                rect(xx, y - 3.4 - up, Math.min(3.3, xb - xx), up + 0.2, Lit.mul(green, k % 2 == 0 ? 1.12 : 0.92));
            }
        }

        /** Courses of brick over a wall: the bed joints, the staggered upright joints of every other course, a few bricks a tone off. */
        void bricks(double x, double top, double w, double h) {
            final Rectangle2D.Double q = new Rectangle2D.Double();
            g.setColor(BRICK_BED);
            for (double yy = top + 3; yy < top + h - 1; yy += 3) { q.setRect(x, yy, w, 0.5); g.fill(q); }
            g.setColor(BRICK_JOINT);
            int row = 0;
            for (double yy = top; yy < top + h - 3; yy += 3, row++) {
                if ((row & 1) == 1 || !fine) continue;
                for (double xx = x + ((row & 2) == 0 ? 3 : 6.5); xx < x + w - 1; xx += 7) { q.setRect(xx, yy + 0.5, 0.5, 2.5); g.fill(q); }
            }
            for (int k = 0; k < 12; k++) {
                final double bx = x + Math.floor(du() * (w - 7) / 3.5) * 3.5, by = top + Math.floor(du() * (h - 4) / 3) * 3;
                g.setColor(k % 2 == 0 ? SHINGLE_LIGHT : SHINGLE_DARK);
                q.setRect(bx, by + 0.5, 6.4, 2.5); g.fill(q);
            }
        }

        /** One thing in the yard, standing on the ground with its middle at x (it is at most 13 px wide); {@code dir} = the side it faces. */
        void prop(int kind, double x, int dir) {
            final double gy = y;
            switch (kind) {
                case P_GRILL -> {
                    Color leg = c(60, 60, 66);
                    rect(x - 2.1, gy - 4.2, 0.7, 4.8, leg);
                    rect(x + 1.4, gy - 4.2, 0.7, 4.8, leg);
                    rect(x - 2.7, gy - 6.3, 5.4, 2.4, c(38, 38, 42));
                    rect(x - 2.4, gy - 7.5, 4.8, 1.3, c(64, 64, 70));
                    rect(x - 0.5, gy - 8.1, 1.0, 0.7, c(150, 150, 150));
                }
                case P_SWING -> {
                    Color frame = c(70, 110, 160);
                    rect(x - 5.6, gy - 11, 1.0, 11.6, frame);
                    rect(x + 4.6, gy - 11, 1.0, 11.6, frame);
                    rect(x - 5.6, gy - 11.7, 11.2, 1.0, frame);
                    Color rope = c(90, 86, 80);
                    rect(x - 2.0, gy - 10.7, 0.5, 7.0, rope);
                    rect(x + 1.5, gy - 10.7, 0.5, 7.0, rope);
                    rect(x - 2.5, gy - 3.9, 5.0, 0.95, c(196, 62, 54));
                }
                case P_SHED -> {
                    Color wood = c(150, 120, 90);
                    rect(x - 6, gy - 9, 12, 9.6, wood);
                    g.setColor(DOOR_PANEL);
                    for (double xx = x - 4; xx < x + 6; xx += 2) g.fill(new Rectangle2D.Double(xx, gy - 9, 0.45, 9.4));
                    poly(c(Lit.mul(roof, 0.82)), x - 7, gy - 8.8, x, gy - 12.6, x + 7, gy - 8.8);
                    rect(x - 1.9, gy - 6.6, 3.8, 7.2, Lit.mul(wood, 0.7));
                }
                case P_BIKE -> {
                    g.setColor(c(40, 40, 46));
                    g.setStroke(new BasicStroke(0.6f));
                    g.draw(new Ellipse2D.Double(x - 5.4, gy - 4.2, 4.4, 4.4));
                    g.draw(new Ellipse2D.Double(x + 1.0, gy - 4.2, 4.4, 4.4));
                    g.setColor(c(196, 62, 54));
                    g.draw(new Line2D.Double(x - 3.2, gy - 2.0, x - 0.4, gy - 4.6));
                    g.draw(new Line2D.Double(x - 0.4, gy - 4.6, x + 2.6, gy - 4.4));
                    g.draw(new Line2D.Double(x + 2.6, gy - 4.4, x + 3.2, gy - 2.0));
                    g.draw(new Line2D.Double(x - 0.4, gy - 4.6, x + 0.2, gy - 2.0));
                    g.draw(new Line2D.Double(x - 3.2, gy - 2.0, x + 0.2, gy - 2.0));
                    g.draw(new Line2D.Double(x + 2.6, gy - 4.4, x + 2.8, gy - 5.6));
                    rect(x - 1.4, gy - 5.2, 1.9, 0.8, c(40, 40, 46));
                    rect(x + 2.0, gy - 6.0, 1.6, 0.7, c(40, 40, 46));
                }
                case P_TRAMPOLINE -> {
                    Color leg = c(70, 72, 78);
                    rect(x - 5.6, gy - 3.4, 0.8, 4.0, leg);
                    rect(x + 4.8, gy - 3.4, 0.8, 4.0, leg);
                    rect(x - 6.5, gy - 4.1, 13, 1.1, c(34, 38, 54));
                    rect(x - 6.5, gy - 4.5, 13, 0.55, c(70, 124, 196));
                }
                case P_WASHING -> {
                    Color pole = c(150, 146, 138);
                    rect(x - 6.5, gy - 10, 0.9, 10.6, pole);
                    rect(x + 5.6, gy - 10, 0.9, 10.6, pole);
                    rect(x - 6.5, gy - 9.7, 13, 0.4, c(210, 210, 210));
                    final int[][] cloth = {{238, 238, 234}, {120, 160, 210}, {214, 96, 88}, {236, 208, 110}};
                    for (int k = 0; k < 3; k++) {
                        final int[] cc = cloth[(int) Math.min(3, du() * 4)];
                        rect(x - 5.0 + k * 3.6, gy - 9.5, 2.7, 3.0 + 1.8 * du(), c(cc[0], cc[1], cc[2]));
                    }
                }
                case P_WOOD -> {
                    Color end = c(196, 160, 112), bark = c(104, 76, 52);
                    for (int row = 0; row < 3; row++) for (int k = 0; k < 5 - row; k++) {
                        final double lx = x - 5 + row * 1.0 + k * 2.05, ly = gy - 1.6 - row * 1.95;
                        rect(lx, ly, 1.9, 1.9, bark);
                        rect(lx + 0.35, ly + 0.35, 1.2, 1.2, end);
                    }
                }
                case P_BARREL -> {
                    Color wood = c(126, 92, 62), hoop = c(64, 64, 70);
                    rect(x - 2.5, gy - 6.6, 5.0, 7.2, wood);
                    rect(x - 2.8, gy - 5.4, 5.6, 0.7, hoop);
                    rect(x - 2.8, gy - 1.9, 5.6, 0.7, hoop);
                    rect(x - 2.2, gy - 7.0, 4.4, 0.7, c(70, 96, 120));
                }
                case P_BENCH, P_CHAIR -> {
                    final double hw = kind == P_BENCH ? 5.2 : 2.8;
                    Color wood = c(138, 104, 72);
                    rect(x - hw, gy - 7.4, hw * 2, 0.9, wood);
                    rect(x - hw, gy - 7.4, 0.9, 3.4, wood);
                    rect(x + hw - 0.9, gy - 7.4, 0.9, 3.4, wood);
                    rect(x - hw, gy - 4.3, hw * 2, 1.15, Lit.mul(wood, 1.1));
                    rect(x - hw + 0.4, gy - 3.2, 0.9, 3.8, Lit.mul(wood, 0.8));
                    rect(x + hw - 1.3, gy - 3.2, 0.9, 3.8, Lit.mul(wood, 0.8));
                    spot(Cast.SEAT, x + (kind == P_BENCH ? -dir * 1.6 : 0), gy - 4.3, dir, 4.8);
                }
                default -> {   // P_POT: a clay jar
                    Color clay = c(176, 108, 74);
                    rect(x - 2.0, gy - 4.3, 4.0, 4.9, clay);
                    rect(x - 2.5, gy - 3.6, 5.0, 2.6, clay);
                    rect(x - 1.6, gy - 5.0, 3.2, 0.8, Lit.mul(clay, 0.8));
                }
            }
        }

        /** Maybe (with chance p) one thing from {@code kinds} in the yard on one side of the building, and a place for someone at it. */
        void yard(int side, int[] kinds, double p) {
            final double u = du(), v = du();
            if (u >= p) return;
            final int kind = kinds[(int) Math.min(kinds.length - 1, v * kinds.length)];
            prop(kind, side < 0 ? x0 - 10.3 : x0 + w + 10.3, -side);
            spot(Cast.YARD, side < 0 ? x0 - 4.4 : x0 + w + 4.4, y + 0.6, side, 0);
        }
    }

    /**
     * The static look of one building at its world position. Protocol v2.3: {@code o.damage} 0..1
     * strips the roof, breaks windows, scatters debris at the foot and finally tears a corner of the
     * building away. The damage grows in place: more damage adds to what is already broken.
     */
    static void paint(Graphics2D g, Model.Obj o, Lit lit, double haze) { paint(g, o, lit, haze, 0); }

    /** @param wind the coarse wind level -3 .. 3 the rain beads on the panes are smeared with ({@link #windLevel}; 0 = calm) */
    static void paint(Graphics2D g, Model.Obj o, Lit lit, double haze, int wind) { paint(g, o, lit, haze, wind, 0); }

    /**
     * Phase O: the still picture of a building is a pure function of (object, damage, wet, snow, wind level, life level):
     * no clock, no history.
     * @param life the outdoor life level 0 .. {@link Cast#LEVELS} ({@link Life#level}: who stands outside; 0 = nobody, which
     *             is what the forms without it paint, so a picture made without a level never has anyone outdoors), or
     *             -1 = taken from the object and the light with nobody sheltering from a tornado
     */
    static void paint(Graphics2D g, Model.Obj o, Lit lit, double haze, int wind, int life) {
        if (o.damage < 0.04) { paintIntact(new Ctx(g, o, lit, haze, wind, life, false)); return; }
        double d = Model.clamp(o.damage, 0, 1);
        Random r = new Random(o.seed * 0x5DEECE66DL + 0xD4);
        final boolean swept = swept(o);
        if (o.kind.equals("streetlamp")) {
            if (swept) {
                // Phase O: swept off: the stump of the post on its base, nothing else
                Color steel = lit.lit(Lit.mix(new Color(86, 92, 100), Lit.mul(new Color(86, 92, 100), 0.72), Model.clamp(o.wet, 0, 1) * 0.55), haze);
                g.setColor(steel);
                g.fill(new Rectangle2D.Double(o.x - 1.6, o.y - 3, 3.2, 4));
                g.fill(new Rectangle2D.Double(o.x - 0.85, o.y - 5.6, 1.7, 3));
                return;
            }
            // a lamp post is bent over, then flattened
            AffineTransform old = g.getTransform();
            g.rotate((r.nextBoolean() ? 1 : -1) * Lit.smooth(d) * 1.35, o.x, o.y);
            paintIntact(new Ctx(g, o, lit, haze, wind, 0, false));
            g.setTransform(old);
            return;
        }
        AffineTransform tx = g.getTransform();
        double sc = Model.clamp(Math.hypot(tx.getScaleX(), tx.getShearY()), 0.2, 4);
        double bx0 = o.x - o.w / 2 - HEAP_SIDE - 2, bx1 = o.x + o.w / 2 + HEAP_SIDE + 2, by0 = o.y - o.h * 1.62 - 36, by1 = o.y + 11;
        // Phase O: where the tile is a plain scaled picture (it always is) the building's own image is laid on whole device
        // pixels and copied one to one: what rule F leaves joined in the image is joined on the screen, pixel for pixel
        final boolean exact = tx.getShearX() == 0 && tx.getShearY() == 0 && tx.getScaleX() == sc && tx.getScaleY() == sc;
        int dx0 = 0, dy0 = 0;
        if (exact) {
            dx0 = (int) Math.floor(bx0 * sc + tx.getTranslateX()); dy0 = (int) Math.floor(by0 * sc + tx.getTranslateY());
            bx0 = (dx0 - tx.getTranslateX()) / sc; by0 = (dy0 - tx.getTranslateY()) / sc;
        }
        int iw = (int) Math.ceil((bx1 - bx0) * sc) + 2, ih = (int) Math.ceil((by1 - by0) * sc) + 2;
        if ((long) iw * ih > 5_000_000L) { paintIntact(new Ctx(g, o, lit, haze, wind, 0, false)); return; }
        // the building is painted whole into its own image, pieces are cut out of that, what the cuts leave in the air is
        // taken out and laid on the heap at its foot (rule F), and the result is drawn
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(iw, ih, java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE);
        final int[] px = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
        Graphics2D ig = img.createGraphics();
        ig.setRenderingHints(g.getRenderingHints());
        ig.scale(sc, sc);
        ig.translate(-bx0, -by0);
        if (swept) paintSlab(new Ctx(ig, o, lit, haze, wind, 0, false)); else paintIntact(new Ctx(ig, o, lit, haze, wind, 0, false));
        final double fsc = sc, fbx0 = bx0, fby0 = by0;
        final int fiw = iw, fih = ih;
        // (what hangs or sticks out in the building as it was built - the tip of a barn's eave, a swing seat, washing on the
        // line - is noted before anything is cut: rule F's third pass leaves it alone)
        final Scratch scr = SCRATCH.get();
        final int band = Math.max(0, Math.min(ih - 1, (int) Math.floor((o.y - 2 - by0) * sc)));
        builtHanging(px, iw, ih, band, scr, (int) Math.round(OVERHANG_MAX * sc), (int) Math.round(SPAN_MAX * sc));
        double x0 = o.x - o.w / 2, top = o.y - o.h, w = o.w, h = o.h;
        // roof bites: each has its own threshold, so they open one after another as the damage grows
        int bites = 6;
        double[][] bite = new double[bites][];
        double cutLow = by0;                           // the lowest world y a cut reaches
        ig.setComposite(java.awt.AlphaComposite.DstOut);
        ig.setColor(Color.BLACK);
        for (int k = 0; k < bites; k++) {
            double cx = x0 + (0.06 + 0.88 * r.nextDouble()) * w, cy = top + h * (0.02 + 0.30 * r.nextDouble());
            double thr = 0.04 + 0.13 * k + 0.05 * r.nextDouble(), amt = Lit.smooth((d - thr) / 0.30);
            double rw = w * (0.10 + 0.16 * r.nextDouble()) + 4, rh = h * (0.10 + 0.20 * r.nextDouble()) + 4;
            long shapeSeed = r.nextLong();
            bite[k] = new double[]{cx, cy, rw * amt, rh * amt};
            if (amt <= 0.01 || swept) continue;
            ig.fill(jagged(cx, cy, rw * amt, rh * amt, shapeSeed));
            cutLow = Math.max(cutLow, cy + rh * amt * 1.2);
        }
        // from heavy damage on, one upper corner is torn away
        boolean left = r.nextBoolean();
        double torn = Lit.smooth((d - 0.55) / 0.45);
        long tearSeed = r.nextLong();
        if (torn > 0.01 && !swept) {
            Random jr = new Random(tearSeed);
            double sx = left ? x0 - 20 : x0 + w + 20, dir = left ? 1 : -1;
            Path2D.Double p = new Path2D.Double();
            p.moveTo(sx, by0 - 4);
            p.lineTo(sx + dir * (20 + w * 0.62 * torn), by0 - 4);
            int steps = 7;
            for (int k = 0; k <= steps; k++) {
                double u = k / (double) steps;
                p.lineTo(sx + dir * (20 + w * 0.62 * torn * (1 - u) + (jr.nextDouble() - 0.5) * w * 0.07), top + h * (0.74 * torn) * u + (jr.nextDouble() - 0.5) * h * 0.05);
            }
            p.lineTo(sx, top + h * 0.74 * torn);
            p.closePath();
            ig.fill(p);
            cutLow = Math.max(cutLow, top + h * 0.74 * torn + h * 0.03);
        }
        // at the very end only a ruin of the lower walls stands: everything above a ragged line is gone
        double ruin = Lit.smooth((d - 0.80) / 0.20);
        long ruinSeed = r.nextLong();
        double[] ruinX = null, ruinY = null;
        if (ruin > 0.01 && !swept) {
            Random jr = new Random(ruinSeed);
            int steps = Math.max(6, (int) (w / 9));
            ruinX = new double[steps + 1];
            ruinY = new double[steps + 1];
            Path2D.Double p = new Path2D.Double();
            p.moveTo(x0 - 22, by0 - 4);
            p.lineTo(x0 + w + 22, by0 - 4);
            p.lineTo(x0 + w + 22, o.y - h * 0.30);
            for (int k = steps; k >= 0; k--) {
                double u = k / (double) steps;
                // the line drops from above the roof to a broken wall top between a fifth and half of the height
                double keep = h * (0.20 + 0.34 * jr.nextDouble() * jr.nextDouble() + (jr.nextInt(4) == 0 ? 0.18 : 0));
                ruinX[k] = x0 + u * w + (jr.nextDouble() - 0.5) * 5;
                ruinY[k] = (by0 - 4) + ((o.y - keep) - (by0 - 4)) * ruin;
                // (Phase O: the line ends beyond the walls, so no sliver of the outer edge is left standing above the break)
                if (k == 0) ruinX[k] = Math.min(ruinX[k], x0 - 1.5); else if (k == steps) ruinX[k] = Math.max(ruinX[k], x0 + w + 1.5);
                p.lineTo(ruinX[k], ruinY[k]);
            }
            p.lineTo(x0 - 22, o.y - h * 0.30);
            p.closePath();
            ig.fill(p);
            cutLow = o.y;
        }
        // ---- broken windows and soot: on what is left of the walls only (never on the sky a cut has opened)
        ig.setComposite(java.awt.AlphaComposite.SrcAtop);
        int nWin = 3 + (int) (w / 14);
        for (int k = 0; k < nWin; k++) {
            double wx = x0 + (0.08 + 0.84 * r.nextDouble()) * w, wy = o.y - h * (0.16 + 0.5 * r.nextDouble()), thr = r.nextDouble() * 0.8;
            double ww = 3 + 3 * r.nextDouble(), wh = 4 + 4 * r.nextDouble();
            if (d < thr || swept) continue;
            if (ruin > 0.2 && wy < o.y - h * 0.22) continue;
            ig.setColor(Lit.alpha(lit.lit(new Color(20, 20, 26), haze), 215));
            ig.fill(new Rectangle2D.Double(wx - ww / 2, wy - wh / 2, ww, wh));
        }
        ig.setColor(new Color(18, 14, 12, (int) (60 * d * (1 - ruin))));
        for (int k = 0; k < 5; k++) {
            double sx = x0 + r.nextDouble() * w, sw = 3 + r.nextDouble() * 7, sh = h * (0.15 + 0.4 * r.nextDouble()) * d;
            if (!swept) ig.fill(new Rectangle2D.Double(sx, o.y - h * 0.62, sw, sh));
        }
        // ---- rule F: whatever is no longer joined to the foundation comes out of the picture and goes onto the heap
        // (before the rafters and the studs are drawn: a beam across a hole never holds up what hangs beyond it)
        final long t0 = System.nanoTime();
        final int thr = exact ? JOIN_ALPHA : JOIN_ALPHA_SCALED;
        // (everything that is not joined at all; then the heavy pieces that hang by a thread: a solid group of HANG_MIN_AREA
        // or more with no solid path down)
        int loose = prune(px, iw, ih, band, 0, thr, scr, true, 0);
        final int low = Math.max(0, Math.min(band, (int) Math.floor((cutLow - by0) * sc) + 2));
        final int hung = hung(px, iw, low, scr, (int) Math.ceil(HANG_MIN_AREA * sc * sc), loose);
        if (hung > loose) loose = prune(px, iw, ih, band, 0, thr, scr, true, hung);       // (what hung on those goes too)
        // (and what is joined but stands on nothing: a slab left sticking out sideways over a hole, further than a roof's eave would)
        final int over = unsupported(px, iw, low, scr, (int) Math.round(OVERHANG_MAX * sc), (int) Math.round(SPAN_MAX * sc), loose);
        if (over > loose) loose = prune(px, iw, ih, band, 0, thr, scr, true, over);
        // ---- what is left in the holes: bare rafters, each from wood to wood across its hole; studs on the broken wall top
        ig.setComposite(java.awt.AlphaComposite.SrcOver);
        Color beam = lit.lit(new Color(96, 70, 48), haze), beamDark = lit.lit(new Color(52, 40, 32), haze);
        ig.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (ruin < 0.5 && !swept) {
            java.util.ArrayList<double[]> lines = new java.util.ArrayList<>();
            for (double[] bt : bite) {
                if (bt[2] < 5) continue;
                int n = 1 + (int) (bt[2] / 8);
                for (int k = 0; k < n; k++) {
                    double u = (k + 0.5) / n * 2 - 1, rx = bt[0] + u * bt[2] * 0.7, hh = Math.min(7, bt[3] * Math.sqrt(Math.max(0, 1 - u * u)) * 0.55);
                    // (looked for on the cut picture, before any rafter is drawn: a rafter never hangs on another one)
                    double len = Math.hypot(1.8, hh), ux = 1.8 / len, uy = -hh / len, reach = Math.min(RAFTER_MAX, bt[3] * 1.3 + 6);
                    double ta = wood(px, fiw, fih, fsc, fbx0, fby0, rx, bt[1], ux, uy, reach), tb = wood(px, fiw, fih, fsc, fbx0, fby0, rx, bt[1], -ux, -uy, reach);
                    if (ta < 0 || tb < 0 || ta + tb > RAFTER_MAX) continue;
                    lines.add(new double[]{rx - ux * (tb + 0.8), bt[1] - uy * (tb + 0.8), rx + ux * (ta + 0.8), bt[1] + uy * (ta + 0.8), k});
                }
            }
            for (double[] l : lines) {
                ig.setColor(Lit.alpha((int) l[4] % 2 == 0 ? beam : beamDark, (int) (255 * (1 - ruin * 2))));
                ig.draw(new Line2D.Double(l[0], l[1], l[2], l[3]));
            }
        }
        if (ruinX != null) {
            // splintered studs standing on the broken wall top
            Random sr = new Random(ruinSeed * 31 + 1);
            for (int k = 0; k < ruinX.length; k++) {
                // (only where a wall is left standing right under the break: while the line is still coming down it is in the air)
                final boolean skip = sr.nextInt(3) == 0;
                double len = (3 + 9 * sr.nextDouble()) * ruin, tilt = (sr.nextDouble() - 0.5) * 5;
                final boolean dark = sr.nextBoolean();
                // (its foot is set into the wall: the first solid point within 4 px under the break)
                final double foot = skip ? -1 : wood(px, fiw, fih, fsc, fbx0, fby0, ruinX[k], ruinY[k] - 0.5, 0, 1, 4.5);
                if (foot < 0) continue;
                ig.setColor(dark ? beamDark : beam);
                ig.draw(new Line2D.Double(ruinX[k], ruinY[k] - 0.5 + foot + 1.0, ruinX[k] + tilt, ruinY[k] - len));
            }
        }
        // ---- the heap at the foot: every piece rests on the ground or on the pieces under it (a height-field), so none floats
        final double hx0 = o.x - w / 2 - HEAP_SIDE, hx1 = o.x + w / 2 + HEAP_SIDE, base = o.y + 1.4;
        final int cols = (int) Math.ceil(hx1 - hx0) + 1;
        final float[] field = scr.field(cols);
        double heapTop = base;
        Path2D.Double q = new Path2D.Double();
        int rubble = (int) (6 + 22 * d + w / 10 * d + w / 3 * ruin);
        Color wallC = o.wall != null ? new Color(o.wall[0], o.wall[1], o.wall[2]) : new Color(200, 186, 160);
        Color roofC = o.roof != null ? new Color(o.roof[0], o.roof[1], o.roof[2]) : new Color(130, 70, 56);
        // (first what fell out of the picture, in its own colour, under where it hung; then the rubble of the damage itself)
        Random hr = new Random(o.seed * 0x2545F4914F6CDD1DL + 0xF1);
        for (int c = 0; c < loose; c++) {
            final double area = scr.compN[c] / (sc * sc), cxw = bx0 + scr.compX[c] / sc, half = scr.compW[c] / sc / 2;
            final int m = (int) Model.clamp(Math.round(area / HEAP_PIECE_AREA), 1, HEAP_PER_PIECE_MAX);
            final Color col = new Color(scr.compRGB[c]);
            for (int j = 0; j < m; j++) {
                double s = Model.clamp(Math.sqrt(area / m) * 0.62, HEAP_S0, HEAP_S1), a = hr.nextDouble() * Math.PI;
                double pxw = cxw + (hr.nextDouble() - 0.5) * 2 * Math.min(half, w * 0.5) * 0.9;
                ig.setColor(Lit.mul(col, 0.80 + 0.2 * hr.nextDouble()));
                heapTop = Math.min(heapTop, heapPiece(ig, q, field, cols, hx0, base, pxw, s, a));
            }
        }
        for (int k = 0; k < rubble; k++) {
            double u = r.nextGaussian() * 0.42, pxw = o.x + u * (w * 0.5 + 16) * (0.6 + 0.6 * d);
            double s = HEAP_S0 + r.nextDouble() * (HEAP_S1 - HEAP_S0), a = r.nextDouble() * Math.PI;
            Color col = switch (r.nextInt(4)) { case 0 -> wallC; case 1 -> roofC; case 2 -> new Color(104, 78, 54); default -> new Color(120, 118, 114); };
            ig.setColor(lit.lit(Lit.mul(col, 0.62 + 0.3 * r.nextDouble()), haze));
            heapTop = Math.min(heapTop, heapPiece(ig, q, field, cols, hx0, base, pxw, s, a));
        }
        ig.dispose();
        // (the heap is built to rest on itself; this second, small pass over its rows makes sure of it)
        final int heapRow = Math.max(0, Math.min(band, (int) Math.floor((heapTop - by0) * sc) - 2));
        prune(px, iw, ih, band, heapRow, thr, scr, false, 0);
        pruneNanos += System.nanoTime() - t0; pruneCount++;
        if (exact) {
            g.setTransform(new AffineTransform());
            g.drawImage(img, dx0, dy0, null);
            g.setTransform(tx);
        } else {
            Object oh = g.getRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, new AffineTransform(1 / sc, 0, 0, 1 / sc, bx0, by0), null);
            if (oh != null) g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, oh);
        }
    }

    // ---- Phase O, rule F and the heap (PLAN "The model in numbers"; world px unless said otherwise)
    /** A pixel of the building's own image counts as there from this alpha on; pieces are joined through such pixels (8 neighbours). */
    static final int JOIN_ALPHA = 40;
    /** The same where the image is stretched onto the tile (zoom above 4): a faint bridge would not survive the stretch. */
    static final int JOIN_ALPHA_SCALED = 112;
    /** A loose piece of this many image px or more becomes rubble on the heap; smaller ones just go. */
    static final int LOOSE_MIN_PX = 6;
    /** A solid group (alpha {@link #HANG_ALPHA} or more) of this many square px or more must be joined through solid pixels: it does not hang by an antialiased thread. */
    static final int HANG_ALPHA = 128;
    static final double HANG_MIN_AREA = 14;
    /** The heap lies within the footprint and this far to either side of it. */
    static final double HEAP_SIDE = 16;
    /** Half-length of a heap piece, smallest and largest. */
    static final double HEAP_S0 = 1.4, HEAP_S1 = 4.8;
    /** A loose piece gives one heap piece per this many square px of it, at most {@link #HEAP_PER_PIECE_MAX}. */
    static final double HEAP_PIECE_AREA = 26;
    static final int HEAP_PER_PIECE_MAX = 9;
    /** How far a piece sinks into what it lands on, and what part of its height the heap grows by under it (the rest settles). */
    static final double HEAP_SINK = 0.45, HEAP_GROW = 0.42;
    /**
     * Above the lowest cut, a part of the picture must stand on something: on what is under it, or as a span between two
     * such places, or sticking out sideways from one by at most this much (an eave, an aerial). What sticks out further
     * comes down - unless it hung or stuck out like that in the building as it was built.
     */
    static final double OVERHANG_MAX = 10;
    /** A span between two carried places holds up to this length; of a longer one only the overhang at each end stays. */
    static final double SPAN_MAX = 22;
    /** A rafter longer than this is not drawn (a hole that wide has nothing left to carry it). */
    static final double RAFTER_MAX = 22;
    /** Diagnostics: time spent in rule F (both passes and the heap) and how many damaged pictures it was (benign races). */
    static long pruneNanos, pruneCount;

    /** What rule F needs besides the image, kept per thread (the tile workers paint side by side). */
    private static final class Scratch {
        byte[] mark = new byte[0];
        /** Per pixel of the image (row by row, no border): 1 = it stood on nothing in the building as built ({@link #builtHanging}). */
        byte[] built = new byte[0];
        int[] stack = new int[0];
        float[] field = new float[0];
        int[] compN = new int[64], compRGB = new int[64];
        float[] compX = new float[64], compW = new float[64];

        float[] field(int n) {
            if (field.length < n) field = new float[n];
            java.util.Arrays.fill(field, 0, n, 0f);
            return field;
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /**
     * From a point in a hole, how far (world px) along a direction the picture is solid again; -1 when the point is not in
     * a hole, or nothing solid comes within {@code reach}.
     */
    private static double wood(int[] px, int iw, int ih, double sc, double bx0, double by0, double wx, double wy, double ux, double uy, double reach) {
        final double step = 0.5 / sc;
        for (double t = 0; t <= reach; t += step) {
            int x = (int) Math.floor((wx + ux * t - bx0) * sc), y = (int) Math.floor((wy + uy * t - by0) * sc);
            if (x < 0 || y < 0 || x >= iw || y >= ih) return -1;
            int a = px[y * iw + x] >>> 24;
            if (a > 150) return t == 0 ? -1 : t;
        }
        return -1;
    }

    /**
     * One piece of rubble dropped at world x onto the heap's height-field: it comes to rest on the highest thing under it,
     * sunk a little into it, and the field rises under it. Returns the world y of its top.
     */
    private static double heapPiece(Graphics2D ig, Path2D.Double q, float[] field, int cols, double hx0, double base, double pxw, double s, double a) {
        final double ca = Math.cos(a), sa = Math.sin(a);
        final double hx = Math.max(Math.abs(ca) * s, Math.abs(sa) * s * 0.4), hy = Math.max(Math.abs(sa) * s * 0.5, Math.abs(ca) * s * 0.4 + 0.6);
        pxw = Model.clamp(pxw, hx0 + hx + 0.5, hx0 + cols - 1 - hx - 0.5);
        final int c0 = Math.max(0, (int) Math.floor(pxw - hx - hx0)), c1 = Math.min(cols - 1, (int) Math.ceil(pxw + hx - hx0));
        float f = 0;
        for (int c = c0; c <= c1; c++) f = Math.max(f, field[c]);
        final double py = base - f + HEAP_SINK - hy;                  // its middle: its underside HEAP_SINK below what it rests on
        final float up = (float) (f + 2 * hy * HEAP_GROW);
        // (the field rises under the middle of the piece, less under its ends: a heap, not a stack)
        for (int c = c0; c <= c1; c++) {
            double e = 1 - Math.abs((c + hx0 - pxw) / (hx + 0.5));
            field[c] = Math.max(field[c], (float) (f + (up - f) * Model.clamp(0.35 + e, 0, 1)));
        }
        q.reset();
        q.moveTo(pxw - ca * s, py - sa * s * 0.5);
        q.lineTo(pxw + sa * s * 0.4, py - ca * s * 0.4 - 0.6);
        q.lineTo(pxw + ca * s, py + sa * s * 0.5);
        q.lineTo(pxw - sa * s * 0.4, py + ca * s * 0.4 + 0.6);
        q.closePath();
        ig.fill(q);
        return py - hy;
    }

    /**
     * Rule F on the building's own image (premultiplied ARGB, {@code iw} x {@code ih}): every pixel from row {@code y0}
     * down that is not joined, through pixels of alpha {@code thr} or more, to the foundation band (the rows from
     * {@code band} down) or to the row {@code y0} itself is cleared. A faint pixel stays only beside a joined one.
     * Leaves the marks in the scratch (2 = joined) for {@link #hung}.
     * @param collect note every loose piece of {@link #LOOSE_MIN_PX} px or more in the scratch (size, middle x, width, mean colour)
     * @param comps the number of pieces already noted in the scratch
     * @return the number of pieces noted, those before included
     */
    private static int prune(int[] px, int iw, int ih, int band, int y0, int thr, Scratch sx, boolean collect, int comps) {
        final int W = iw + 2, rows = ih - y0, n = W * (rows + 2);
        if (rows <= 0) return comps;
        if (sx.mark.length < n) { sx.mark = new byte[n]; sx.stack = new int[n]; }
        final byte[] m = sx.mark;
        final int[] st = sx.stack;
        // 0 = nothing (or faint), 1 = there, not yet reached; 2 = joined; 3 = loose, taken
        java.util.Arrays.fill(m, 0, W + 1, (byte) 0);
        java.util.Arrays.fill(m, n - W - 1, n, (byte) 0);
        boolean faint = false;
        for (int y = 0; y < rows; y++) {
            int i = (y + y0) * iw, j = (y + 1) * W;
            m[j++] = 0;
            for (int x = 0; x < iw; x++, i++, j++) {
                final int v = px[i];
                if ((v >>> 24) >= thr) m[j] = 1; else { m[j] = 0; faint |= v != 0; }
            }
            m[j] = 0;
        }
        // the seeds: one for every run of the band's rows (and of the top row of a part pass)
        int sp = 0;
        for (int y = 0; y < rows; y++) {
            if (!(y + y0 >= band || (y == 0 && y0 > 0))) continue;
            int j = (y + 1) * W + 1;
            for (int x = 0; x < iw; x++, j++) {
                if (m[j] != 1) continue;
                m[j] = 2; st[sp++] = j;
                while (x + 1 < iw && m[j + 1] == 1) { x++; j++; }
            }
        }
        spans(m, st, sp, W, (byte) 1, (byte) 2);
        // the loose pieces
        final int[] nb = {-W - 1, -W, -W + 1, -1, 1, W - 1, W, W + 1};
        for (int y = 0; y < rows; y++) {
            int j = (y + 1) * W + 1;
            for (int x = 0; x < iw; x++, j++) {
                if (m[j] != 1) continue;
                m[j] = 3; st[0] = j;
                int top = 1;
                for (int t = 0; t < top; t++) {
                    final int c = st[t];
                    for (int k = 0; k < 8; k++) { final int q = c + nb[k]; if (m[q] == 1) { m[q] = 3; st[top++] = q; } }
                }
                comps = take(px, iw, y0, W, st, top, sx, collect, comps);
            }
        }
        // the faint pixels (an antialiased rim): they stay beside something joined, and go with what went
        if (faint) for (int y = (y0 > 0 ? 1 : 0); y < rows; y++) {
            int i = (y + y0) * iw, j = (y + 1) * W + 1;
            for (int x = 0; x < iw; x++, i++, j++) {
                if (m[j] != 0 || px[i] == 0) continue;
                if (m[j - W - 1] == 2 || m[j - W] == 2 || m[j - W + 1] == 2 || m[j - 1] == 2 || m[j + 1] == 2 || m[j + W - 1] == 2 || m[j + W] == 2 || m[j + W + 1] == 2) continue;
                px[i] = 0;
            }
        }
        return comps;
    }

    /**
     * Flood by runs: from the seeds on the stack (already marked {@code to}) through every 8-connected pixel marked
     * {@code from}. The marks have a border of one pixel that is never {@code from}.
     */
    private static void spans(byte[] m, int[] st, int sp, int W, byte from, byte to) {
        while (sp > 0) {
            final int j = st[--sp];
            int l = j, r = j;
            while (m[l - 1] == from) m[--l] = to;
            while (m[r + 1] == from) m[++r] = to;
            for (int dir = -W; dir <= W; dir += 2 * W) {
                for (int q = l - 1 + dir, e = r + 1 + dir; q <= e; q++) {
                    if (m[q] != from) continue;
                    m[q] = to; st[sp++] = q;
                    while (q < e && m[q + 1] == from) q++;
                }
            }
        }
    }

    /** Clear the {@code top} pixels listed on the stack (indices into the marks) and note them as one loose piece. */
    private static int take(int[] px, int iw, int y0, int W, int[] st, int top, Scratch sx, boolean collect, int comps) {
        long sa = 0, sr = 0, sg = 0, sb = 0, sxx = 0;
        int minX = iw, maxX = 0;
        for (int t = 0; t < top; t++) {
            final int c = st[t], cx = c % W - 1, idx = (c / W - 1 + y0) * iw + cx, v = px[idx];
            sa += v >>> 24; sr += (v >> 16) & 255; sg += (v >> 8) & 255; sb += v & 255; sxx += cx;
            if (cx < minX) minX = cx;
            if (cx > maxX) maxX = cx;
            px[idx] = 0;
        }
        if (!collect || top < LOOSE_MIN_PX || sa == 0) return comps;
        if (comps == sx.compN.length) {
            sx.compN = java.util.Arrays.copyOf(sx.compN, comps * 2); sx.compRGB = java.util.Arrays.copyOf(sx.compRGB, comps * 2);
            sx.compX = java.util.Arrays.copyOf(sx.compX, comps * 2); sx.compW = java.util.Arrays.copyOf(sx.compW, comps * 2);
        }
        sx.compN[comps] = top; sx.compX[comps] = sxx / (float) top; sx.compW[comps] = maxX - minX + 1;
        // (premultiplied: the mean colour is the sums over the alpha)
        sx.compRGB[comps] = 0xff000000 | (Lit.ci((int) (sr * 255 / sa)) << 16) | (Lit.ci((int) (sg * 255 / sa)) << 8) | Lit.ci((int) (sb * 255 / sa));
        return comps + 1;
    }

    /**
     * After a whole-image {@link #prune}: the heavy pieces that hang by a thread. Above row {@code low} (the lowest row a
     * cut reached; what is under it is as it was built) every group of solid pixels (alpha {@link #HANG_ALPHA} or more) of
     * {@code minPx} or more that has no path of solid pixels down to that row is cleared and noted as a loose piece.
     * @return the number of pieces noted, those before included (more than {@code comps}: run {@link #prune} again)
     */
    private static int hung(int[] px, int iw, int low, Scratch sx, int minPx, int comps) {
        final int W = iw + 2;
        final byte[] m = sx.mark;
        final int[] st = sx.stack;
        // 4 = solid and joined, not yet reached by a solid path; 5 = reached; 6 = looked at
        int sp = 0;
        for (int y = 0; y <= low; y++) {
            int i = y * iw, j = (y + 1) * W + 1;
            for (int x = 0; x < iw; x++, i++, j++) if (m[j] == 2 && (px[i] >>> 24) >= HANG_ALPHA) m[j] = 4;
        }
        for (int x = 0, j = (low + 1) * W + 1; x < iw; x++, j++) if (m[j] == 4) { m[j] = 5; st[sp++] = j; }
        // (the row under the part looked at stops the flood: its marks are 2 or 0, never 4)
        spans(m, st, sp, W, (byte) 4, (byte) 5);
        final int[] nb = {-W - 1, -W, -W + 1, -1, 1, W - 1, W, W + 1};
        for (int y = 0; y < low; y++) {
            int j = (y + 1) * W + 1;
            for (int x = 0; x < iw; x++, j++) {
                if (m[j] != 4) continue;
                m[j] = 6; st[0] = j;
                int top = 1;
                for (int t = 0; t < top; t++) {
                    final int c = st[t];
                    for (int k = 0; k < 8; k++) { final int q = c + nb[k]; if (m[q] == 4) { m[q] = 6; st[top++] = q; } }
                }
                if (top >= minPx) comps = take(px, iw, 0, W, st, top, sx, true, comps);
            }
        }
        return comps;
    }

    /**
     * Which pixels of the building as built (the image before any cut) stand on nothing by the rule of
     * {@link #unsupported}: noted in the scratch, so that pass does not take them for storm damage.
     */
    private static void builtHanging(int[] px, int iw, int ih, int band, Scratch sx, int reach, int span) {
        final int n = iw * ih;
        if (sx.built.length < n) sx.built = new byte[n];
        final byte[] c = sx.built;                 // while this runs: 2 = stands on something, 3 = carried as an overhang, 1 = there and not carried, 0 = nothing
        for (int i = band * iw; i < n; i++) c[i] = (byte) ((px[i] >>> 24) >= JOIN_ALPHA ? 2 : 0);
        for (int y = band - 1; y >= 0; y--) {
            final int row = y * iw, below = row + iw;
            for (int x = 0; x < iw; x++) {
                if ((px[row + x] >>> 24) < JOIN_ALPHA) { c[row + x] = 0; continue; }
                final int a = x;
                int prev = -1;
                for (; x < iw && (px[row + x] >>> 24) >= JOIN_ALPHA; x++) {
                    if (!(c[below + x] == 2 || (x > 0 && c[below + x - 1] == 2) || (x < iw - 1 && c[below + x + 1] == 2))) {
                        // (over an overhang it is part of that overhang: it reaches no further than what is under it)
                        c[row + x] = (byte) (c[below + x] == 3 ? 3 : 1);
                        continue;
                    }
                    // (it stands on something: with what leads up to it, as a span from the last such pixel or as an overhang)
                    if (prev >= 0 && x - prev <= span) for (int k = prev + 1; k < x; k++) c[row + k] = 2;
                    else {
                        for (int k = Math.max(Math.max(a, prev + 1), x - reach); k < x; k++) c[row + k] = 3;
                        if (prev >= 0) for (int k = prev + 1; k <= prev + reach; k++) c[row + k] = 3;
                    }
                    c[row + x] = 2;
                    prev = x;
                }
                if (prev >= 0) for (int k = prev + 1; k <= Math.min(x - 1, prev + reach); k++) c[row + k] = 3;
                if (x < iw) c[row + x] = 0;
            }
        }
        for (int i = 0; i < n; i++) c[i] = (byte) (c[i] == 1 ? 1 : 0);
    }

    /**
     * After a whole-image {@link #prune} (and {@link #hung}): what is joined but stands on nothing. From row {@code low}
     * upwards, row by row, a pixel stands when one of the three pixels under it does; in every run of pixels along a row
     * everything between two carried pixels no more than {@code span} apart is carried too (a span over a hole), and
     * {@code reach} pixels beyond a carried one (an overhang); what stood on nothing in the building as built ({@link #builtHanging}) counts as
     * carried. The groups of pixels that are not carried are cleared and noted as loose pieces.
     * @return the number of pieces noted, those before included (more than {@code comps}: run {@link #prune} again)
     */
    private static int unsupported(int[] px, int iw, int low, Scratch sx, int reach, int span, int comps) {
        final int W = iw + 2;
        final byte[] m = sx.mark, built = sx.built;
        final int[] st = sx.stack;
        // 2, 5, 6 = joined (as prune and hung left it); 7 = stands on something; 9 = carried as an overhang; 8 = not carried, looked at
        for (int x = 0, j = (low + 1) * W + 1; x < iw; x++, j++) if (m[j] >= 2 && m[j] != 3) m[j] = 7;
        boolean any = false;
        for (int y = low - 1; y >= 0; y--) {
            final int row = (y + 1) * W + 1, base = y * iw;
            for (int x = 0; x < iw; x++) {
                if (m[row + x] < 2 || m[row + x] == 3) continue;
                // a run of the row: its first and last pixel with something carried under it
                final int a = x;
                int prev = -1;
                for (; x < iw && m[row + x] >= 2 && m[row + x] != 3; x++) {
                    final int below = row + x + W;
                    if (!(m[below - 1] == 7 || m[below] == 7 || m[below + 1] == 7)) {
                        // (over an overhang it is part of that overhang: it reaches no further than what is under it)
                        if (m[below] == 9) m[row + x] = 9;
                        continue;
                    }
                    if (prev >= 0 && x - prev <= span) for (int k = prev + 1; k < x; k++) m[row + k] = 7;
                    else {
                        for (int k = Math.max(Math.max(a, prev + 1), x - reach); k < x; k++) m[row + k] = 9;
                        if (prev >= 0) for (int k = prev + 1; k <= prev + reach; k++) m[row + k] = 9;
                    }
                    m[row + x] = 7;
                    prev = x;
                }
                final int b = x - 1;
                if (prev >= 0) for (int k = prev + 1; k <= Math.min(b, prev + reach); k++) m[row + k] = 9;
                for (int k = a; k <= b; k++) {
                    if (m[row + k] == 7 || m[row + k] == 9) continue;
                    // (as it was built: it hung there before the storm, and it carries what stands on it)
                    if (built[base + k] != 0) m[row + k] = 7; else any = true;
                }
            }
        }
        if (!any) return comps;
        final int[] nb = {-W - 1, -W, -W + 1, -1, 1, W - 1, W, W + 1};
        for (int y = 0; y < low; y++) {
            int j = (y + 1) * W + 1;
            for (int x = 0; x < iw; x++, j++) {
                if (m[j] < 2 || m[j] == 3 || m[j] >= 7) continue;
                m[j] = 8; st[0] = j;
                int top = 1;
                for (int t = 0; t < top; t++) {
                    final int c = st[t];
                    for (int k = 0; k < 8; k++) {
                        final int q = c + nb[k];
                        if (m[q] < 2 || m[q] == 3 || m[q] >= 7 || q / W - 1 >= low) continue;
                        m[q] = 8; st[top++] = q;
                    }
                }
                comps = take(px, iw, 0, W, st, top, sx, true, comps);
            }
        }
        return comps;
    }

    /**
     * Phase O: what is left of a swept building: its footing, the floor slab with the stumps of the wall plates, the
     * steps to where the door was. No walls and no roof (the heap is added by {@link #paint}).
     */
    private static void paintSlab(Ctx c) {
        final Model.Obj o = c.o;
        final double x0 = c.x0, gy = c.y, w = c.w;
        Random sr = new Random(o.seed * 0x9E3779B97F4A7C15L + 0x51AB);
        final String v = o.kind.equals("house") && o.variant != null ? o.variant : "";
        Color foot = switch (v) { case "adobe" -> c.c(Lit.mul(c.wall, 0.72)); case "hut" -> c.c(Lit.mul(c.wall, 0.60)); default -> c.c(96, 92, 90); };
        if (v.equals("station")) {
            // the stilts are snapped off short; their pads stay
            Color steel = c.c(128, 134, 142), steelDark = c.c(84, 90, 100);
            int legs = Math.max(3, (int) Math.round(w / 26));
            for (int k = 0; k < legs; k++) {
                double lx = x0 + 6 + (w - 12) * k / (legs - 1.0), lh = 2 + 5 * sr.nextDouble();
                c.rect(lx - 1.4, gy - lh, 2.8, lh + 1, steel);
                c.rect(lx - 3.6, gy - 0.6, 7.2, 2.4, steelDark);
            }
            return;
        }
        final double ix = v.equals("hut") ? x0 + w * 0.11 : x0, fw = v.equals("hut") ? w * 0.78 : w;
        c.rect(ix - 1, gy - 2, fw + 2, 9, foot);
        // the floor: a pale slab, cracked, with a few stumps of the wall plate along its edge
        Color slab = c.c(Lit.mix(new Color(168, 164, 158), c.wall, 0.18));
        c.rect(ix, gy - 3.4, fw, 1.9, slab);
        c.g.setColor(new Color(0, 0, 0, 60));
        c.g.setStroke(new BasicStroke(0.6f));
        for (int k = 0; k < 3; k++) { double cxk = ix + fw * (0.15 + 0.7 * sr.nextDouble()); c.g.draw(new Line2D.Double(cxk, gy - 3.4, cxk + (sr.nextDouble() - 0.5) * 3, gy - 1.5)); }
        Color plate = c.c(Lit.mul(c.wall, 0.86));
        for (double xx = ix + 1; xx < ix + fw - 4; ) {
            double len = 3 + 7 * sr.nextDouble(), gap = 2 + 9 * sr.nextDouble() * sr.nextDouble();
            c.rect(xx, gy - 3.4 - (0.8 + 1.6 * sr.nextDouble()), Math.min(len, ix + fw - xx), 3, plate);
            xx += len + gap;
        }
        // the steps where the door was
        double dw = Math.max(7, fw * 0.2), dx = ix + (fw - dw) * (0.15 + 0.7 * sr.nextDouble());
        Color step = c.c(150, 148, 144);
        c.rect(dx - 2.5, gy - 1.2, dw + 5, 2.4, Lit.mul(step, 0.86));
        c.rect(dx, gy - 3.0, dw, 2.2, step);
    }

    // ---- Phase O: the swept slab (PLAN "Swept")
    /** From this damage on a small building may be swept off its foundation ... */
    // (OI: the top step of the ladder, k = round(damage x 24) = 24, not 0.96: 0.96 lies inside step 23, and a tile is baked
    // when the step changes, so the same step showed walls or a bare slab depending on when it was last painted - a ruin in
    // the live session, a slab after the reload)
    static final double SWEPT_D = 23.5 / 24;
    /** ... which this share of them is (by seed). */
    static final double SWEPT_SHARE = 0.55;

    /**
     * Phase O: at the top of the damage ladder a house, a shop or a lamp may be swept away altogether: no walls, only the
     * foundation, the steps and the heap. Whether it is depends on the object alone (its kind, its seed and its damage).
     */
    static boolean swept(Model.Obj o) {
        if (!(o.damage >= SWEPT_D)) return false;
        switch (o.kind) {
            case "barn", "church", "windmill", "apartment", "office", "skyscraper", "factory", "water_tower" -> { return false; }
            default -> { return Scene.hash01(o.seed * 0x9E3779B97F4A7C15L + 0x5E9) < SWEPT_SHARE; }
        }
    }

    /** A ragged hole: an ellipse whose radius jitters around its rim. */
    private static Path2D.Double jagged(double cx, double cy, double rw, double rh, long seed) {
        Random r = new Random(seed);
        Path2D.Double p = new Path2D.Double();
        int n = 11;
        for (int k = 0; k < n; k++) {
            double a = k * 2 * Math.PI / n, rr = 0.62 + 0.55 * r.nextDouble();
            double x = cx + Math.cos(a) * rw * rr, y = cy + Math.sin(a) * rh * rr;
            if (k == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }

    /** True while the moving parts of a building (sails, smoke, the aviation light) still work. */
    static boolean liveWorks(Model.Obj o) { return o.damage < 0.55; }

    private static void paintIntact(Ctx c) {
        final Model.Obj o = c.o;
        // a foundation hides the gap where the ground slopes under the building
        // (v2.3: the biome houses bring their own footing - earth under adobe and thatch, stilts under a polar station)
        if (!o.kind.equals("streetlamp") && !o.kind.equals("water_tower") && !o.kind.equals("windmill") && !biomeHouse(o))
            c.rect(c.x0 - 1, c.y - 2, c.w + 2, 9, c.c(96, 92, 90));
        switch (o.kind) {
            case "barn" -> barn(c);
            case "church" -> church(c);
            case "windmill" -> windmill(c);
            case "shop" -> shop(c);
            case "apartment" -> apartment(c);
            case "office" -> office(c);
            case "skyscraper" -> skyscraper(c);
            case "factory" -> factory(c);
            case "water_tower" -> waterTower(c);
            case "streetlamp" -> streetlamp(c);
            default -> {
                switch (o.variant) {
                    case "cabin" -> cabin(c);
                    case "farmhouse" -> farmhouse(c);
                    case "townhouse" -> townhouse(c);
                    case "bungalow" -> bungalow(c);
                    case "manor" -> manor(c);
                    case "adobe" -> adobe(c);
                    case "hut" -> hut(c);
                    case "station" -> station(c);
                    default -> cottage(c);
                }
            }
        }
        // Phase O: the people standing outside (the shadows at the lit windows were painted with their panes)
        if (c.detail && !c.measuring && c.life > 0) Cast.paintBaked(c.g, o, c.lit, c.haze, c.life);
    }

    // ------------------------------------------------------------------ houses

    /** v2.3: a house variant of a preset biome (adobe, hut, station), which stands on its own kind of footing. */
    private static boolean biomeHouse(Model.Obj o) {
        if (o.variant == null) return false;
        switch (o.kind) {
            case "barn", "church", "windmill", "shop", "apartment", "office", "skyscraper", "factory", "water_tower", "streetlamp" -> { return false; }
            default -> { return o.variant.equals("adobe") || o.variant.equals("hut") || o.variant.equals("station"); }
        }
    }

    /** The parapet of a flat earth roof: a low rim with raised corners, snow lying on it. */
    private static void parapet(Ctx c, double x, double top, double w, Color cap) {
        c.rect(x - 1.5, top - 3, w + 3, 4.2, cap);
        c.rect(x - 1.5, top - 5.6, 5, 3, cap);
        c.rect(x + w - 3.5, top - 5.6, 5, 3, cap);
        c.snowFlat(x - 1, top - 3, w + 2);
    }

    /** v2.3 desert house: sun-dried brick, a flat roof behind a parapet, beam ends under it, small deep-set windows. */
    private static void adobe(Ctx c) {
        double w = c.w, h = c.h, x0 = c.x0, gy = c.y;
        boolean flip = c.r.nextBoolean(), two = c.o.floors >= 2, blueDoor = c.r.nextInt(3) == 0;
        Color wall = c.c(c.wall), cap = c.c(Lit.mul(c.roof, 0.90)), beam = c.c(98, 70, 48);
        c.win = W_FRAME | W_CURTAIN | W_FIGURE;
        c.trim = beam;
        c.rect(x0 - 1, gy - 2, w + 2, 9, c.c(Lit.mul(c.wall, 0.72)));
        double bh = two ? h * 0.62 : h * 0.84;
        c.rect(x0, gy - bh, w, bh, wall);
        c.shadeRight(x0, gy - bh, w, bh, 0.28);
        double ux = x0 + (flip ? w * 0.06 : w * 0.46), uw = w * 0.48, ut = gy - h * 0.95;
        if (two) {
            // an upper room set back on one side, with the roof terrace beside it
            c.rect(ux, ut, uw, gy - bh - ut + 1, wall);
            c.shadeRight(ux, ut, uw, gy - bh - ut, 0.28);
            parapet(c, ux, ut, uw, cap);
            double ws = Math.min(uw * 0.22, 7);
            c.window(ux + uw * 0.39, ut + (gy - bh - ut) * 0.30, ws, ws * 1.15, 0.8, false);
        }
        parapet(c, x0, gy - bh, w, cap);
        for (double bx = x0 + 5; bx < x0 + w - 5; bx += 8.5) c.rect(bx, gy - bh + 4.8, 2.4, 2.4, beam);      // beam ends (vigas)
        double dw = w * 0.15, dh = bh * (two ? 0.62 : 0.52), dx = x0 + w * (flip ? 0.64 : 0.18);
        c.rect(dx - 1.5, gy - dh - 2, dw + 3, 2.4, beam);
        c.door(dx, dw, dh, blueDoor ? c.c(64, 112, 150) : c.c(104, 72, 48));
        if (c.detail) c.doorTrim(dx, dw, dh, D_PLANK | D_KNOB | D_STEP | D_LAMP);
        double ws = Math.min(w * 0.12, 8), wy = gy - bh * 0.66;
        for (int k = 0; k < 2; k++) {
            double wx = x0 + w * ((flip ? 0.14 : 0.48) + 0.22 * k);
            c.rect(wx - 1.2, wy - 1.2, ws + 2.4, ws * 1.15 + 2.4, c.c(Lit.mul(c.wall, 0.80)));
            c.window(wx, wy, ws, ws * 1.15, 0.8, false);
        }
        c.win = 0;
        if (c.detail) {
            // Phase O: a vent under the parapet, a water spout, a bench under the windows, a rain barrel at the corner, pots by the door
            double vx = x0 + w * (flip ? 0.86 : 0.07);
            c.rect(vx, gy - bh + 9, 3.4, 2.4, c.c(60, 46, 36));
            c.rect(vx + 0.4, gy - bh + 9.9, 2.6, 0.5, wall);
            c.rect(flip ? x0 - 2.6 : x0 + w - 0.4, gy - bh + 3.6, 3, 1.1, beam);
            double bxm = x0 + w * (flip ? 0.31 : 0.65);
            c.prop(P_BENCH, bxm, flip ? 1 : -1);
            c.prop(P_BARREL, flip ? x0 - 5 : x0 + w + 5, 1);
            c.prop(P_POT, flip ? dx + dw + 3.4 : dx - 3.4, 1);
            c.spot(Cast.PORCH, flip ? dx - 4.2 : dx + dw + 4.2, gy + 0.6, flip ? -1 : 1, 0);
            c.spot(Cast.YARD, flip ? x0 - 11.5 : x0 + w + 11.5, gy + 0.6, flip ? 1 : -1, 0);
        }
    }

    /** v2.3 tropical and savanna hut: a timber wall under a deep, shaggy thatched roof. */
    private static void hut(Ctx c) {
        double w = c.w, h = c.h, x0 = c.x0, gy = c.y, bh = h * 0.46, ix = x0 + w * 0.11, iw = w * 0.78;
        Color wall = c.c(c.wall), thatch = c.c(c.roof);
        c.rect(ix - 1.5, gy - 1.5, iw + 3, 8, c.c(Lit.mul(c.wall, 0.60)));
        c.rect(ix, gy - bh, iw, bh, wall);
        c.shadeRight(ix, gy - bh, iw, bh, 0.3);
        c.g.setColor(new Color(40, 24, 12, 62));
        c.g.setStroke(new BasicStroke(0.8f));
        for (double xx = ix + 4; xx < ix + iw - 1; xx += 4.5) c.g.draw(new Line2D.Double(xx, gy - bh + 2, xx, gy));
        boolean flip = c.r.nextBoolean();
        c.door(ix + iw * (flip ? 0.56 : 0.18), iw * 0.25, bh * 0.80, c.c(54, 38, 28));
        if (c.detail) c.doorTrim(ix + iw * (flip ? 0.56 : 0.18), iw * 0.25, bh * 0.80, D_PLANK | D_STEP);
        c.win = W_FRAME | W_CURTAIN | W_FIGURE;
        c.trim = c.c(Lit.mul(c.wall, 0.62));
        c.window(ix + iw * (flip ? 0.16 : 0.60), gy - bh * 0.74, iw * 0.2, bh * 0.36, 0.85, false);
        c.win = 0;
        if (c.detail) {
            // Phase O: a log bench under the window, a water jar and a bundle of firewood at the corner
            c.prop(P_BENCH, ix + iw * (flip ? 0.26 : 0.70), flip ? 1 : -1);
            c.prop(P_POT, flip ? ix + iw + 3.6 : ix - 3.6, 1);
            c.prop(P_WOOD, flip ? ix - 6 : ix + iw + 6, 1);
            c.spot(Cast.PORCH, ix + iw * (flip ? 0.47 : 0.52), gy + 0.6, flip ? -1 : 1, 0);
            c.spot(Cast.YARD, flip ? ix + iw + 9 : ix - 9, gy + 0.6, flip ? -1 : 1, 0);
        }
        double ey = gy - bh + 2, px = c.o.x, py = gy - h;
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x0 - 5, ey + 3);
        p.quadTo(x0 + w * 0.30, ey - (ey - py) * 0.52, px - 2.5, py);
        p.lineTo(px + 2.5, py);
        p.quadTo(x0 + w * 0.70, ey - (ey - py) * 0.52, x0 + w + 5, ey + 3);
        int teeth = Math.max(6, (int) (w / 5));
        for (int k = teeth; k >= 0; k--) p.lineTo(x0 - 5 + (w + 10) * k / teeth, ey + 3 + (k % 2 == 0 ? 0 : 2.8));
        p.closePath();
        c.g.setPaint(new GradientPaint((float) x0, 0, Lit.mul(thatch, 1.08), (float) (x0 + w), 0, Lit.mul(thatch, 0.78)));
        c.g.fill(p);
        c.g.setColor(new Color(60, 40, 10, 58));
        c.g.setStroke(new BasicStroke(0.8f));
        for (int k = 1; k < 8; k++) {
            double xx = x0 - 5 + (w + 10) * k / 8.0;
            c.g.draw(new Line2D.Double(px + (xx - px) * 0.14, py + (ey - py) * 0.18, xx, ey + 2));
        }
        if (c.detail) {
            // the thatch in layers: two darker bands across it, shorter strands between the long ones
            c.g.setColor(new Color(60, 40, 10, 44));
            for (int k = 0; k < 16; k++) {
                double xx = x0 - 5 + (w + 10) * (k + 0.5) / 16.0, t0 = 0.45 + 0.3 * c.du();
                c.g.draw(new Line2D.Double(px + (xx - px) * t0, py + (ey - py) * (t0 + 0.04), px + (xx - px) * 0.96, ey + 1));
            }
        }
        c.rect(px - 3.5, py - 1.5, 7, 3.2, c.c(Lit.mul(c.roof, 0.72)));
        c.snowGable(x0 - 5, ey + 2, px, py, x0 + w + 5, ey + 2);
    }

    /** v2.3 polar station: a bright prefabricated module on stilts, with steps, a radio mast and a radome. */
    private static void station(Ctx c) {
        double w = c.w, h = c.h, x0 = c.x0, gy = c.y;
        double leg = h * 0.24, top = gy - h * 0.88, bot = gy - leg, bh = bot - top;
        boolean flip = c.r.nextBoolean();
        Color steel = c.c(128, 134, 142), steelDark = c.c(84, 90, 100), wall = c.c(c.wall), roof = c.c(c.roof);
        int legs = Math.max(3, (int) Math.round(w / 26));
        c.g.setStroke(new BasicStroke(1.0f));
        double prev = 0;
        for (int k = 0; k < legs; k++) {
            double lx = x0 + 6 + (w - 12) * k / (legs - 1.0);
            if (k > 0) {
                c.g.setColor(steelDark);
                c.g.draw(new Line2D.Double(prev, bot + 1, lx, gy - 1));
            }
            c.rect(lx - 1.4, bot - 1, 2.8, leg + 5, steel);
            c.rect(lx - 3.6, gy - 0.6, 7.2, 2.4, steelDark);
            prev = lx;
        }
        c.g.setPaint(new GradientPaint((float) x0, 0, Lit.mul(wall, 1.06), (float) (x0 + w), 0, Lit.mul(wall, 0.82)));
        c.g.fill(new java.awt.geom.RoundRectangle2D.Double(x0, top, w, bh, 7, 7));
        // a pale band along the middle carries the row of windows
        double by = top + bh * 0.24, bandH = bh * 0.36;
        c.rect(x0, by, w, bandH, c.c(Lit.mix(c.wall, new Color(244, 244, 240), 0.82)));
        if (c.detail) {
            // Phase O: the seams of the wall panels
            c.g.setColor(new Color(0, 0, 0, 30));
            for (double xx = x0 + 9; xx < x0 + w - 5; xx += 12.5) c.g.fill(new Rectangle2D.Double(xx, top + 1.5, 0.5, bh - 3));
        }
        double dw = Math.max(7, w * 0.11), dx = flip ? x0 + w * 0.06 : x0 + w * 0.94 - dw;
        double wx0 = flip ? dx + dw + w * 0.05 : x0 + w * 0.05, ww = w * 0.90 - dw - w * 0.05;
        c.win = W_FRAME | W_FIGURE;
        c.trim = steelDark;
        c.grid(wx0, by + 1, ww, bandH - 2, Math.max(3, (int) (ww / 13)), 1, 0.56, 0.78, 0.9, true);
        c.win = 0;
        c.rect(dx, bot - bh * 0.70, dw, bh * 0.70, c.c(230, 232, 236));
        c.window(dx + dw * 0.24, bot - bh * 0.60, dw * 0.52, bh * 0.20, 0.9, true);
        for (int s = 0; s < 4; s++) c.rect(dx - 1 + (flip ? -1 : 1) * s * 2.8, bot + (s + 0.4) * leg / 4, dw + 2, 1.5, steelDark);
        if (c.detail) {
            // Phase O: a handrail down the steps, a lamp over the door, fuel drums under the module, vents on the roof
            final int sd = flip ? -1 : 1;
            c.g.setColor(steelDark);
            c.g.setStroke(new BasicStroke(0.7f));
            double rx0 = dx + dw / 2 + sd * (dw / 2 + 1), rx1 = rx0 + sd * 3 * 2.8;
            c.g.draw(new Line2D.Double(rx0, bot - 5.5, rx1, gy - 5.2));
            c.rect(rx0 - 0.35, bot - 5.5, 0.7, 5.5, steelDark);
            c.rect(rx1 - 0.35, gy - 5.2, 0.7, 5.2, steelDark);
            c.lamp(dx + dw / 2, bot - bh * 0.70 - 2.6);
            double fx = x0 + w * (flip ? 0.74 : 0.26);
            c.rect(fx - 5.2, gy - 6.6, 4.6, 6.6, c.c(188, 62, 50));
            c.rect(fx - 5.2, gy - 4.6, 4.6, 0.7, c.c(120, 40, 34));
            c.rect(fx + 0.4, gy - 6.6, 4.6, 6.6, c.c(60, 96, 150));
            c.rect(fx + 0.4, gy - 4.6, 4.6, 0.7, c.c(38, 62, 100));
            for (double vf : new double[]{0.44, 0.56}) {
                c.rect(x0 + w * vf, top - 5.4, 3.2, 2.9, steel);
                c.rect(x0 + w * vf - 0.5, top - 6.1, 4.2, 0.9, steelDark);
            }
            c.spot(Cast.PORCH, Model.clamp(rx1 + sd * 3.5, x0 - 15, x0 + w + 15), gy + 0.6, sd, 0);
        }
        // roof deck, mast with its aerials, radome
        c.rect(x0 + 2, top - 2.6, w - 4, 3.6, roof);
        c.snowFlat(x0 + 2, top - 2.6, w - 4);
        double mx = x0 + w * (flip ? 0.80 : 0.20), mh = h * 0.60;
        c.rect(mx - 0.7, top - 2.6 - mh, 1.4, mh, steelDark);
        for (int k = 0; k < 3; k++) c.rect(mx - 4.5 + k, top - 2.6 - mh * (0.50 + 0.17 * k), 9 - 2 * k, 1, steelDark);
        c.rect(mx - 1.3, top - 2.6 - mh - 2.2, 2.6, 2.6, c.c(214, 60, 48));
        double rx = x0 + w * (flip ? 0.30 : 0.70), rr = Math.min(8, h * 0.17);
        c.rect(rx - rr * 0.55, top - 4.4, rr * 1.1, 2.2, steel);
        c.g.setPaint(new GradientPaint((float) (rx - rr), 0, c.c(246, 248, 250), (float) (rx + rr), 0, c.c(204, 212, 224)));
        c.g.fill(new Ellipse2D.Double(rx - rr, top - 4 - rr * 1.9, rr * 2, rr * 2));
    }

    private static void chimney(Ctx c, double x, double topY, double w, double h) {
        c.rect(x, topY, w, h, c.c(124, 84, 72));
        c.rect(x - 1, topY - 2, w + 2, 3, c.c(92, 66, 60));
        if (c.detail) {
            // Phase O: brick courses and a clay pot on top
            c.g.setColor(new Color(0, 0, 0, 34));
            for (double yy = topY + 3; yy < topY + h; yy += 2.6) c.g.fill(new Rectangle2D.Double(x, yy, w, 0.5));
            c.rect(x + w * 0.5 - 1.1, topY - 3.6, 2.2, 1.8, c.c(150, 96, 76));
        }
        if (c.snow > 0.05) c.rect(x - 1, topY - 2 - 2 * c.snow, w + 2, 2 * c.snow + 0.5, c.snowCol());
    }

    private static void cottage(Ctx c) {
        double w = c.w, bh = c.h * 0.6, x0 = c.x0, gy = c.y;
        boolean flip = c.r.nextBoolean();
        c.rect(x0, gy - bh, w, bh, c.c(c.wall));
        c.shadeRight(x0, gy - bh, w, bh, 0.3);
        chimney(c, x0 + w * (flip ? 0.22 : 0.68), gy - c.h - 2, w * 0.1, c.h * 0.35);
        c.roofTex = true;
        c.gable(x0 - 6, x0 + w + 6, gy - bh, c.o.x, gy - c.h, c.c(c.roof));
        double dx = x0 + w * (flip ? 0.60 : 0.26);
        if (c.detail) c.gutter(x0 - 6.5, x0 + w + 6.5, gy - bh, flip ? x0 + 1.4 : x0 + w - 1.4, gy);
        c.door(dx, w * 0.16, bh * 0.52, c.c(92, 60, 40));
        if (c.detail) c.doorTrim(dx, w * 0.16, bh * 0.52, D_PANEL | D_KNOB | D_STEP | D_PLATE | D_LAMP | D_FRAME);
        double wx = x0 + w * (flip ? 0.14 : 0.56);
        for (int k = 0; k < 2; k++) {
            double xx = k == 0 ? wx : x0 + w * (flip ? 0.34 : 0.06), ww = w * (k == 0 ? 0.22 : 0.14), wh = bh * 0.32;
            if (k == 1 && w < 60) break;
            c.win = W_FRAME | W_SILL | W_CURTAIN | W_FIGURE | (k == 0 && !(flip && w >= 60) ? W_SHUTTER : 0);
            c.window(xx, gy - bh * 0.76, ww, wh, 0.8, false);
            c.g.setColor(new Color(60, 40, 30, 150));
            c.g.setStroke(new BasicStroke(0.9f));
            c.g.draw(new Line2D.Double(xx + ww / 2, gy - bh * 0.76, xx + ww / 2, gy - bh * 0.76 + wh));
        }
        c.win = 0;
        if (c.detail) {
            // Phase O: someone by the door; up to two things in the yard, one to each side
            c.yard(-1, YARD_COMMON, 0.6);
            c.yard(1, YARD_COMMON, 0.6);
            c.spot(Cast.PORCH, flip ? dx - w * 0.06 : dx + w * 0.23, gy + 0.6, flip ? -1 : 1, 0);
        }
    }

    private static void cabin(Ctx c) {
        double w = c.w, bh = c.h * 0.62, x0 = c.x0, gy = c.y;
        Color log = c.c(Lit.mix(new Color(134, 92, 58), c.wall, 0.25));
        c.rect(x0, gy - bh, w, bh, log);
        c.g.setColor(new Color(40, 24, 12, 70));
        c.g.setStroke(new BasicStroke(0.9f));
        for (double yy = gy - bh + 5; yy < gy - 1; yy += 5.5) c.g.draw(new Line2D.Double(x0, yy, x0 + w, yy));
        if (c.detail) {
            // Phase O: the chinking between the logs, and the round of each log
            c.g.setColor(new Color(236, 222, 190, 46));
            for (double yy = gy - bh + 5; yy < gy - 1; yy += 5.5) c.g.fill(new Rectangle2D.Double(x0, yy + 0.5, w, 0.6));
            c.g.setColor(new Color(255, 240, 210, 20));
            for (double yy = gy - bh + 0.8; yy < gy - 2; yy += 5.5) c.g.fill(new Rectangle2D.Double(x0, yy, w, 1.5));
        }
        // log ends at the corners
        for (double yy = gy - bh + 2.5; yy < gy; yy += 5.5) {
            c.g.setColor(c.c(168, 126, 86));
            c.g.fill(new Ellipse2D.Double(x0 - 2, yy - 1.8, 3.6, 3.6));
            c.g.fill(new Ellipse2D.Double(x0 + w - 1.6, yy - 1.8, 3.6, 3.6));
            if (c.detail) {
                // (the rings of the cut ends)
                c.g.setColor(c.c(120, 84, 54));
                c.g.fill(new Rectangle2D.Double(x0 - 0.7, yy - 0.5, 1.0, 1.0));
                c.g.fill(new Rectangle2D.Double(x0 + w - 0.3, yy - 0.5, 1.0, 1.0));
            }
        }
        boolean flip = c.r.nextBoolean();
        // stone chimney on the outside
        double cx = flip ? x0 - 5 : x0 + w - 4;
        c.rect(cx, gy - c.h - 6, 9, c.h + 6, c.c(126, 124, 122));
        if (c.detail) {
            // (its stones)
            c.g.setColor(new Color(0, 0, 0, 34));
            int row = 0;
            for (double yy = gy - c.h - 3; yy < gy - 1; yy += 3.4, row++) {
                c.g.fill(new Rectangle2D.Double(cx, yy, 9, 0.5));
                c.g.fill(new Rectangle2D.Double(cx + (row % 2 == 0 ? 3 : 5.6), yy, 0.5, 3.4));
            }
        }
        c.rect(cx - 1, gy - c.h - 8, 11, 3, c.c(98, 96, 96));
        c.roofTex = true;
        c.gable(x0 - 9, x0 + w + 9, gy - bh + 1, c.o.x, gy - c.h, c.c(Lit.mix(c.roof, new Color(74, 58, 46), 0.5)));
        double dx = x0 + w * (flip ? 0.58 : 0.2);
        c.door(dx, w * 0.2, bh * 0.6, c.c(84, 54, 34));
        if (c.detail) c.doorTrim(dx, w * 0.2, bh * 0.6, D_PLANK | D_KNOB | D_STEP | D_LAMP);
        c.win = W_FRAME | W_SILL | W_CURTAIN | W_FIGURE;
        c.trim = c.c(96, 66, 42);
        c.window(x0 + w * (flip ? 0.16 : 0.56), gy - bh * 0.72, w * 0.24, bh * 0.34, 0.9, false);
        c.win = 0;
        if (c.detail) {
            // Phase O: the woodpile on the side away from the chimney, someone at it, someone by the door
            final int side = flip ? 1 : -1;
            c.prop(P_WOOD, side < 0 ? x0 - 8 : x0 + w + 8, 1);
            c.spot(Cast.PORCH, flip ? dx - w * 0.09 : dx + w * 0.28, gy + 0.6, flip ? -1 : 1, 0);
            c.spot(Cast.YARD, side < 0 ? x0 - 14.5 : x0 + w + 14.5, gy + 0.6, -side, 0);
        }
    }

    private static void farmhouse(Ctx c) {
        double w = c.w, bh = c.h * 0.66, x0 = c.x0, gy = c.y;
        c.rect(x0, gy - bh, w, bh, c.c(c.wall));
        c.shadeRight(x0, gy - bh, w, bh, 0.25);
        // clapboard lines
        c.g.setColor(new Color(0, 0, 0, 22));
        c.g.setStroke(new BasicStroke(0.7f));
        for (double yy = gy - bh + 4; yy < gy; yy += 4) c.g.draw(new Line2D.Double(x0, yy, x0 + w, yy));
        if (c.detail) {
            // Phase O: lap siding: every board catches the light along its lower edge and throws a shadow on the next
            for (double yy = gy - bh + 2; yy < gy; yy += 2) {
                c.g.setColor(((int) Math.round((yy - gy + bh) / 2) & 1) == 0 ? new Color(255, 255, 255, 30) : new Color(0, 0, 0, 16));
                c.g.fill(new Rectangle2D.Double(x0, yy - 0.75, w, 0.55));
            }
            c.rect(x0, gy - bh, 1.3, bh, c.trimCol());                 // corner boards
            c.rect(x0 + w - 1.3, gy - bh, 1.3, bh, Lit.mul(c.trimCol(), 0.88));
        }
        boolean flip = c.r.nextBoolean();
        chimney(c, x0 + w * (flip ? 0.16 : 0.74), gy - c.h - 3, w * 0.09, c.h * 0.3);
        c.roofTex = true;
        c.gable(x0 - 5, x0 + w + 5, gy - bh, c.o.x, gy - c.h, c.c(c.roof));
        if (c.detail) c.gutter(x0 - 5.5, x0 + w + 5.5, gy - bh, flip ? x0 + w - 1.4 : x0 + 1.4, gy);
        // upper windows
        c.win = W_FRAME | W_SILL | W_SHUTTER | W_CURTAIN | W_FIGURE;
        c.grid(x0 + w * 0.08, gy - bh * 0.95, w * 0.84, bh * 0.36, 3, 1, 0.5, 0.8, 0.7, false);
        // porch across the front
        double py = gy - bh * 0.5;
        c.poly(c.c(Lit.mul(c.roof, 0.85)), x0 - 6, py + 4, x0 + 4, py - 2, x0 + w - 4, py - 2, x0 + w + 6, py + 4);
        c.snowFlat(x0 - 2, py - 1, w + 4);
        Color post = c.c(238, 234, 226);
        for (int k = 0; k <= 4; k++) c.rect(x0 - 3 + k * (w + 4) / 4.0, py + 4, 2.2, gy - py - 4, post);
        c.door(x0 + w * 0.44, w * 0.13, bh * 0.44, c.c(98, 56, 44));
        if (c.detail) c.doorTrim(x0 + w * 0.44, w * 0.13, bh * 0.44, D_PANEL | D_KNOB | D_FRAME | D_LAMP);
        c.win = W_FRAME | W_SILL | W_CURTAIN | W_FIGURE;
        c.window(x0 + w * 0.12, gy - bh * 0.40, w * 0.18, bh * 0.26, 0.8, false);
        c.window(x0 + w * 0.70, gy - bh * 0.40, w * 0.18, bh * 0.26, 0.8, false);
        c.win = 0;
        if (c.detail) {
            // Phase O: the porch: its eave board, a rail with balusters between the posts (not across the door), a swing seat
            c.rect(x0 - 6, py + 3.4, w + 12, 1.1, c.c(Lit.mul(c.roof, 0.66)));
            final double bay = (w + 4) / 4.0, doorMid = x0 + w * 0.505;
            Color rail = c.c(238, 234, 226), wood = c.c(150, 110, 76);
            for (int k = 0; k < 4; k++) {
                double a = x0 - 3 + k * bay + 2.2, b = x0 - 3 + (k + 1) * bay;
                if (doorMid > a - 2.2 && doorMid < b) continue;
                c.rect(a, gy - 6.0, b - a, 0.9, rail);
                c.rect(a, gy - 1.9, b - a, 0.8, rail);
                for (double xx = a + 1.6; xx < b - 0.8; xx += 2.6) c.rect(xx, gy - 5.2, 0.65, 3.4, rail);
            }
            final int sb = flip ? 0 : 3;
            final double sxm = x0 - 3 + (sb + 0.5) * bay + 1.1;
            c.rect(sxm - 3.9, py + 4, 0.5, gy - 6.4 - py - 4, c.c(70, 66, 62));
            c.rect(sxm + 3.4, py + 4, 0.5, gy - 6.4 - py - 4, c.c(70, 66, 62));
            c.rect(sxm - 4.6, gy - 9.6, 9.2, 0.9, wood);
            c.rect(sxm - 4.6, gy - 6.6, 9.2, 1.3, wood);
            c.spot(Cast.SEAT, sxm, gy - 6.6, flip ? 1 : -1, 5.4);
            // the washing on the line at one side, a picket fence with a gate at both
            final int ws = flip ? 1 : -1;
            c.prop(P_WASHING, ws < 0 ? x0 - 10.5 : x0 + w + 10.5, 1);
            c.yard(-ws, YARD_FARM, 0.5);
            c.fence(x0 - 17, x0 - 4.5, 5.2, true, ws > 0);
            c.fence(x0 + w + 4.5, x0 + w + 17, 5.2, true, ws < 0);
            c.spot(Cast.PORCH, x0 + w * 0.57 + 4.4, gy + 0.6, 1, 0);
            c.spot(Cast.YARD, ws < 0 ? x0 - 6.5 : x0 + w + 6.5, gy + 0.6, ws, 0);
        }
    }

    private static void townhouse(Ctx c) {
        double w = c.w, x0 = c.x0, gy = c.y, h = c.h;
        int floors = Math.max(2, c.o.floors);
        boolean stepped = c.r.nextBoolean();
        double bh = h * (stepped ? 0.86 : 0.78);
        c.rect(x0, gy - bh, w, bh, c.c(c.wall));
        c.shadeRight(x0, gy - bh, w, bh, 0.22);
        if (stepped) { // Dutch stepped gable
            Color wc = c.c(c.wall);
            double sw = w / 6;
            for (int k = 0; k < 3; k++) c.rect(x0 + sw * k, gy - bh - (k + 1) * (h - bh) / 3, w - 2 * sw * k, (h - bh) / 3 + 1, wc);
            c.snowFlat(x0 + sw * 2, gy - h, w - 4 * sw);
            if (c.detail) {
                // Phase O: a stone coping on every step of the gable
                Color cope = c.c(Lit.mix(c.wall, new Color(226, 222, 212), 0.6));
                for (int k = 0; k < 3; k++) c.rect(x0 + sw * k - 0.6, gy - bh - (k + 1) * (h - bh) / 3 - 0.9, w - 2 * sw * k + 1.2, 1.1, cope);
            }
        } else {
            c.roofTex = true;
            c.gable(x0 - 2, x0 + w + 2, gy - bh, c.o.x, gy - h, c.c(c.roof));
        }
        if (c.detail) c.bricks(x0, gy - bh, w, bh);
        double fh = (bh - 4) / floors;
        c.win = W_FRAME | W_SILL | W_BOX | W_CURTAIN | W_FIGURE;
        for (int f = 0; f < floors - 1; f++) c.grid(x0 + w * 0.12, gy - bh + 3 + f * fh, w * 0.76, fh, 2, 1, 0.52, 0.68, 0.75, false);
        // ground floor: door with steps + one window
        boolean flip = c.r.nextBoolean();
        double dx = x0 + w * (flip ? 0.58 : 0.16);
        c.door(dx, w * 0.24, fh * 0.82, c.c(70, 60, 86));
        if (c.detail) c.doorTrim(dx, w * 0.24, fh * 0.82, D_PANEL | D_KNOB | D_FRAME | D_PLATE | D_LAMP);
        c.win = W_FRAME | W_SILL | W_CURTAIN | W_FIGURE;
        c.window(x0 + w * (flip ? 0.14 : 0.52), gy - fh * 0.78, w * 0.3, fh * 0.52, 0.75, false);
        c.win = 0;
        c.rect(x0 + w * (flip ? 0.54 : 0.12), gy - 2, w * 0.32, 3, c.c(150, 148, 144));
        if (c.detail) {
            // Phase O: the stoop: a second step and an iron rail post with its knob on either side
            double sx = x0 + w * (flip ? 0.54 : 0.12), sw = w * 0.32;
            c.rect(sx - 1.6, gy + 0.4, sw + 3.2, 1.8, c.c(128, 126, 122));
            Color iron = c.c(40, 40, 46);
            for (double rx : new double[]{sx + 0.2, sx + sw - 0.9}) {
                c.rect(rx, gy - 8.2, 0.7, 6.4, iron);
                c.rect(rx - 0.45, gy - 9.3, 1.6, 1.3, iron);
            }
            c.spot(Cast.PORCH, dx + w * 0.12, gy - 1.6, flip ? -1 : 1, 0);
        }
        // string courses
        c.g.setColor(new Color(255, 255, 255, 40));
        for (int f = 1; f < floors; f++) c.g.fill(new Rectangle2D.Double(x0, gy - f * fh - 1, w, 1.2));
    }

    private static void bungalow(Ctx c) {
        double w = c.w, bh = c.h * 0.58, x0 = c.x0, gy = c.y;
        c.rect(x0, gy - bh, w, bh, c.c(c.wall));
        c.shadeRight(x0, gy - bh, w, bh, 0.25);
        // hip roof
        Color rc = c.c(c.roof);
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x0 - 7, gy - bh);
        p.lineTo(x0 + w * 0.2, gy - c.h);
        p.lineTo(x0 + w * 0.8, gy - c.h);
        p.lineTo(x0 + w + 7, gy - bh);
        p.closePath();
        c.g.setPaint(new GradientPaint((float) x0, 0, Lit.mul(rc, 1.1), (float) (x0 + w), 0, Lit.mul(rc, 0.82)));
        c.g.fill(p);
        if (c.detail) c.shingles(x0 - 7, x0 + w + 7, gy - bh, x0 + w * 0.2, x0 + w * 0.8, gy - c.h);
        if (c.snow > 0.04) {
            double t = 2 + 5 * c.snow;
            c.poly(c.snowCol(), x0 + w * 0.2 - 2, gy - c.h - 1, x0 + w * 0.8 + 2, gy - c.h - 1, x0 + w * 0.8 + t * 2, gy - c.h + t, x0 + w * 0.2 - t * 2, gy - c.h + t);
        }
        boolean flip = c.r.nextBoolean();
        double dx = x0 + w * (flip ? 0.66 : 0.24);
        c.door(dx, w * 0.11, bh * 0.66, c.c(88, 72, 60));
        if (c.detail) c.doorTrim(dx, w * 0.11, bh * 0.66, D_PANEL | D_KNOB | D_STEP | D_FRAME | D_LAMP);
        c.win = W_FRAME | W_SILL | W_CURTAIN | W_FIGURE;
        double pwx = x0 + w * (flip ? 0.12 : 0.46);
        c.window(pwx, gy - bh * 0.72, w * 0.36, bh * 0.4, 0.85, false); // picture window
        if (c.detail) {
            c.g.setColor(Lit.alpha(c.trimCol(), 210));
            c.g.fill(new Rectangle2D.Double(pwx + w * 0.12 - 0.3, gy - bh * 0.72, 0.6, bh * 0.4));
            c.g.fill(new Rectangle2D.Double(pwx + w * 0.24 - 0.3, gy - bh * 0.72, 0.6, bh * 0.4));
        }
        c.window(x0 + w * (flip ? 0.82 : 0.08), gy - bh * 0.7, w * 0.1, bh * 0.34, 0.6, false);
        c.win = 0;
        if (c.detail) {
            // Phase O: the veranda: an eave board on slim posts, a chair under the picture window; a low fence, the grill
            c.gutter(x0 - 7.5, x0 + w + 7.5, gy - bh, flip ? x0 + 1.4 : x0 + w - 1.4, gy);
            c.rect(x0 - 5, gy - bh + 1.0, w + 10, 1.5, c.c(Lit.mul(c.roof, 0.72)));
            Color post = c.c(238, 234, 226);
            for (int k = 0; k < 4; k++) {
                double pxk = x0 - 4.2 + k * (w + 7.0) / 3.0;
                if (pxk + 1.4 > dx - 1 && pxk < dx + w * 0.11 + 1) pxk = dx - 3.6;         // (never in the doorway)
                c.rect(pxk, gy - bh + 2.5, 1.4, bh - 2.5, post);
                c.rect(pxk - 0.8, gy - bh + 2.5, 3.0, 0.9, post);
            }
            c.prop(P_CHAIR, pwx + w * 0.18, flip ? 1 : -1);
            final int gs = flip ? -1 : 1;
            c.prop(P_GRILL, gs < 0 ? x0 - 11 : x0 + w + 11, 1);
            c.yard(-gs, YARD_COMMON, 0.5);
            c.fence(x0 - 17, x0 - 5.5, 3.8, false, false);
            c.fence(x0 + w + 5.5, x0 + w + 17, 3.8, false, false);
            c.spot(Cast.PORCH, x0 + w * (flip ? 0.57 : 0.405), gy + 0.6, flip ? 1 : -1, 0);
            c.spot(Cast.YARD, gs < 0 ? x0 - 6.2 : x0 + w + 6.2, gy + 0.6, gs, 0);
        }
    }

    private static void manor(Ctx c) {
        double w = c.w, bh = c.h * 0.66, x0 = c.x0, gy = c.y;
        c.rect(x0, gy - bh, w, bh, c.c(c.wall));
        c.shadeRight(x0, gy - bh, w, bh, 0.2);
        chimney(c, x0 + w * 0.12, gy - c.h - 4, w * 0.06, c.h * 0.3);
        chimney(c, x0 + w * 0.82, gy - c.h - 4, w * 0.06, c.h * 0.3);
        Color rc = c.c(c.roof);
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x0 - 5, gy - bh);
        p.lineTo(x0 + w * 0.14, gy - c.h);
        p.lineTo(x0 + w * 0.86, gy - c.h);
        p.lineTo(x0 + w + 5, gy - bh);
        p.closePath();
        c.g.setPaint(new GradientPaint((float) x0, 0, Lit.mul(rc, 1.1), (float) (x0 + w), 0, Lit.mul(rc, 0.82)));
        c.g.fill(p);
        if (c.detail) c.shingles(x0 - 5, x0 + w + 5, gy - bh, x0 + w * 0.14, x0 + w * 0.86, gy - c.h);
        if (c.snow > 0.04) {
            double t = 2 + 5 * c.snow;
            c.poly(c.snowCol(), x0 + w * 0.14 - 2, gy - c.h - 1, x0 + w * 0.86 + 2, gy - c.h - 1, x0 + w * 0.86 + t * 2, gy - c.h + t, x0 + w * 0.14 - t * 2, gy - c.h + t);
        }
        // dormers
        c.win = W_FRAME | W_CURTAIN | W_FIGURE;
        for (double dxr : new double[]{0.28, 0.66}) {
            double dx = x0 + w * dxr, dw = w * 0.09, dy = gy - bh - (c.h - bh) * 0.52;
            c.rect(dx, dy, dw, (c.h - bh) * 0.5, c.c(c.wall));
            c.poly(rc, dx - 2, dy, dx + dw / 2, dy - dw * 0.6, dx + dw + 2, dy);
            c.window(dx + dw * 0.2, dy + 2, dw * 0.6, (c.h - bh) * 0.32, 0.5, false);
        }
        if (c.detail) {
            // Phase O: a cornice under the eave, quoins at the corners
            c.rect(x0 - 1, gy - bh, w + 2, 1.6, c.c(Lit.mix(c.wall, new Color(240, 238, 232), 0.7)));
            Color quoin = c.c(Lit.mix(c.wall, new Color(240, 238, 232), 0.45));
            for (double yy = gy - bh + 3; yy < gy - 3; yy += 4.4) {
                c.rect(x0, yy, 2.6, 2.2, quoin);
                c.rect(x0 + w - 2.6, yy, 2.6, 2.2, Lit.mul(quoin, 0.9));
            }
        }
        double fh = bh / 2;
        c.win = W_FRAME | W_SILL | W_SHUTTER | W_CURTAIN | W_FIGURE;
        c.grid(x0 + w * 0.05, gy - bh + 2, w * 0.9, fh - 2, 6, 1, 0.42, 0.7, 0.7, false);
        c.grid(x0 + w * 0.05, gy - fh + 1, w * 0.34, fh - 3, 2, 1, 0.44, 0.7, 0.7, false);
        c.grid(x0 + w * 0.61, gy - fh + 1, w * 0.34, fh - 3, 2, 1, 0.44, 0.7, 0.7, false);
        c.win = 0;
        // portico
        double px = x0 + w * 0.39, pw = w * 0.22;
        Color white = c.c(240, 238, 232);
        c.poly(white, px - 3, gy - fh * 1.02, px + pw / 2, gy - fh * 1.34, px + pw + 3, gy - fh * 1.02);
        for (int k = 0; k < 4; k++) c.rect(px + k * (pw - 2.4) / 3.0, gy - fh * 1.02, 2.4, fh * 1.02, white);
        if (c.detail) {
            // Phase O: the columns get capitals, bases and a shaded side; over the entrance a balcony with its rail
            Color shade = Lit.mul(white, 0.84);
            for (int k = 0; k < 4; k++) {
                double cxk = px + k * (pw - 2.4) / 3.0;
                c.rect(cxk + 1.6, gy - fh * 1.02 + 1.2, 0.8, fh * 1.02 - 2.6, shade);
                c.rect(cxk - 0.7, gy - fh * 1.02, 3.8, 1.2, white);
                c.rect(cxk - 0.7, gy - 1.6, 3.8, 1.6, white);
            }
            double ry = gy - fh * 1.02;
            c.rect(px - 3.6, ry - 1.0, pw + 7.2, 1.2, shade);
            c.rect(px - 3.2, ry - 5.4, pw + 6.4, 0.9, white);
            for (double xx = px - 2.8; xx < px + pw + 2.6; xx += 2.5) c.rect(xx, ry - 4.6, 0.75, 3.7, white);
        }
        c.door(px + pw * 0.34, pw * 0.32, fh * 0.78, c.c(64, 46, 40));
        if (c.detail) c.doorTrim(px + pw * 0.34, pw * 0.32, fh * 0.78, D_PANEL | D_KNOB | D_FRAME);
        c.rect(px - 4, gy - 2, pw + 8, 3, c.c(160, 158, 154));
        if (c.detail) {
            // a clipped hedge before each wing, a lamp on the lawn, gutters with a downpipe at either end
            c.gutter(x0 - 5.5, x0 + w + 5.5, gy - bh, x0 + 1.4, gy);
            c.rect(x0 + w - 1.95, gy - bh + 0.9, 1.1, bh - 2.6, c.c(176, 180, 184));
            c.hedge(x0 + 1.5, px - 6.5);
            c.hedge(px + pw + 6.5, x0 + w - 1.5);
            final boolean lampLeft = c.du() < 0.5;
            final double lx = lampLeft ? x0 - 8 : x0 + w + 8;
            c.rect(lx - 0.55, gy - 9.6, 1.1, 10.2, c.c(52, 54, 60));
            c.lamp(lx, gy - 11.4);
            c.rect(px - 5.6, gy + 0.6, pw + 11.2, 1.7, c.c(140, 138, 134));
            c.spot(Cast.PORCH, px + pw * 0.19, gy - 1.8, 1, 0);
            c.spot(Cast.YARD, lampLeft ? x0 + w + 8 : x0 - 8, gy + 0.6, lampLeft ? -1 : 1, 0);
            c.spot(Cast.YARD, lampLeft ? x0 + w + 14.5 : x0 - 14.5, gy + 0.6, lampLeft ? -1 : 1, 0);
            c.spot(Cast.YARD, lampLeft ? x0 - 14 : x0 + w + 14, gy + 0.6, lampLeft ? 1 : -1, 0);
        }
    }

    // ------------------------------------------------------------------ rural buildings

    private static void barn(Ctx c) {
        double w = c.w, x0 = c.x0, gy = c.y, h = c.h, bh = h * 0.52;
        Color red = c.c(c.wall);
        c.rect(x0, gy - bh, w, bh, red);
        // gambrel roof
        Color rc = c.c(c.roof);
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x0 - 5, gy - bh + 2);
        p.lineTo(x0 + w * 0.12, gy - h * 0.82);
        p.lineTo(c.o.x, gy - h);
        p.lineTo(x0 + w * 0.88, gy - h * 0.82);
        p.lineTo(x0 + w + 5, gy - bh + 2);
        p.closePath();
        // gable end wall under the roof line
        c.poly(red, x0, gy - bh, x0 + w * 0.15, gy - h * 0.80, c.o.x, gy - h * 0.975, x0 + w * 0.85, gy - h * 0.80, x0 + w, gy - bh);
        c.g.setColor(rc);
        c.g.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D.Double edge = new Path2D.Double();
        edge.moveTo(x0 - 5, gy - bh + 2);
        edge.lineTo(x0 + w * 0.12, gy - h * 0.82);
        edge.lineTo(c.o.x, gy - h);
        edge.lineTo(x0 + w * 0.88, gy - h * 0.82);
        edge.lineTo(x0 + w + 5, gy - bh + 2);
        c.g.draw(edge);
        if (c.snow > 0.04) {
            c.g.setColor(c.snowCol());
            c.g.setStroke(new BasicStroke((float) (2 + 5 * c.snow), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            AffineTransform old = c.g.getTransform();
            c.g.translate(0, -2 - 2 * c.snow);
            c.g.draw(edge);
            c.g.setTransform(old);
        }
        c.shadeRight(x0, gy - bh, w, bh, 0.22);
        // plank lines
        c.g.setStroke(new BasicStroke(0.7f));
        c.g.setColor(new Color(0, 0, 0, 30));
        for (double xx = x0 + 5; xx < x0 + w; xx += 5) c.g.draw(new Line2D.Double(xx, gy - bh, xx, gy));
        // big double door with white cross braces
        double dw = w * 0.34, dh = bh * 0.82, dx = c.o.x - dw / 2;
        c.rect(dx, gy - dh, dw, dh, Lit.mul(red, 0.82));
        Color white = c.c(236, 232, 224);
        c.g.setColor(white);
        c.g.setStroke(new BasicStroke(1.5f));
        c.g.draw(new Rectangle2D.Double(dx, gy - dh, dw, dh));
        c.g.draw(new Line2D.Double(dx + dw / 2, gy - dh, dx + dw / 2, gy));
        c.g.draw(new Line2D.Double(dx, gy - dh, dx + dw / 2, gy));
        c.g.draw(new Line2D.Double(dx + dw / 2, gy, dx + dw, gy - dh));
        // hay loft window
        c.rect(c.o.x - w * 0.07, gy - h * 0.80, w * 0.14, h * 0.13, c.c(52, 36, 30));
        c.g.setColor(white);
        c.g.draw(new Rectangle2D.Double(c.o.x - w * 0.07, gy - h * 0.80, w * 0.14, h * 0.13));
    }

    private static void church(Ctx c) {
        double w = c.w, x0 = c.x0, gy = c.y, h = c.h;
        boolean left = c.r.nextBoolean();
        double tw = w * 0.34, tx = left ? x0 : x0 + w - tw;       // tower
        double nx = left ? x0 + tw - 2 : x0, nw = w - tw + 2;      // nave
        double nh = h * 0.36, nroof = h * 0.56;
        Color wall = c.c(c.wall);
        c.rect(nx, gy - nh, nw, nh, wall);
        c.gable(nx - 3, nx + nw + 3, gy - nh, nx + nw / 2, gy - nroof, c.c(c.roof));
        // arched nave windows
        int nwin = Math.max(2, (int) (nw / 16));
        for (int k = 0; k < nwin; k++) {
            double ax = nx + (k + 0.5) * nw / nwin - 2.5;
            boolean on = c.on(0.9);
            c.g.setColor(on ? new Color(255, 206, 120) : Lit.mix(c.lit.lit(new Color(70, 90, 130)), c.lit.haze, c.haze));
            c.g.fill(new Rectangle2D.Double(ax, gy - nh * 0.72, 5, nh * 0.46));
            c.g.fill(new Arc2D.Double(ax, gy - nh * 0.72 - 2.5, 5, 5, 0, 180, Arc2D.PIE));
        }
        // tower + steeple
        double th = h * 0.60;
        c.rect(tx, gy - th, tw, th, wall);
        c.shadeRight(tx, gy - th, tw, th, 0.3);
        c.rect(tx - 1.5, gy - th - 2, tw + 3, 3, c.c(Lit.mul(c.wall, 0.8)));
        Color spire = c.c(Lit.mix(c.roof, new Color(60, 70, 84), 0.4));
        double mx = tx + tw / 2;
        Path2D.Double sp = new Path2D.Double();
        sp.moveTo(tx - 1, gy - th - 2);
        sp.lineTo(mx, gy - h);
        sp.lineTo(tx + tw + 1, gy - th - 2);
        sp.closePath();
        c.g.setPaint(new GradientPaint((float) tx, 0, Lit.mul(spire, 1.15), (float) (tx + tw), 0, Lit.mul(spire, 0.75)));
        c.g.fill(sp);
        if (c.snow > 0.04) c.poly(c.snowCol(), tx - 1, gy - th - 2, mx, gy - h - 1, mx + 1.5, gy - h + 10 + 14 * c.snow, tx + 2, gy - th - 3);
        // cross
        c.g.setColor(c.c(226, 204, 120));
        c.g.setStroke(new BasicStroke(1.3f));
        c.g.draw(new Line2D.Double(mx, gy - h - 9, mx, gy - h + 1));
        c.g.draw(new Line2D.Double(mx - 3, gy - h - 6, mx + 3, gy - h - 6));
        // belfry opening, clock, door
        c.rect(mx - tw * 0.18, gy - th + 6, tw * 0.36, th * 0.14, c.c(44, 40, 46));
        c.g.setColor(c.c(236, 232, 214));
        c.g.fill(new Ellipse2D.Double(mx - tw * 0.2, gy - th * 0.66, tw * 0.4, tw * 0.4));
        c.g.setColor(new Color(40, 36, 40));
        c.g.setStroke(new BasicStroke(0.8f));
        c.g.draw(new Line2D.Double(mx, gy - th * 0.66 + tw * 0.2, mx, gy - th * 0.66 + tw * 0.06));
        c.g.draw(new Line2D.Double(mx, gy - th * 0.66 + tw * 0.2, mx + tw * 0.1, gy - th * 0.66 + tw * 0.2));
        double dw = tw * 0.44;
        c.g.setColor(c.c(84, 54, 40));
        c.g.fill(new Rectangle2D.Double(mx - dw / 2, gy - th * 0.2, dw, th * 0.2 + 1));
        c.g.fill(new Arc2D.Double(mx - dw / 2, gy - th * 0.2 - dw / 2, dw, dw, 0, 180, Arc2D.PIE));
    }

    private static void windmill(Ctx c) {
        double w = c.w, gy = c.y, h = c.h, cx = c.o.x;
        double bw = w * 0.5, tw = w * 0.3, th = h * 0.72;
        Color body = c.c(Lit.mix(c.wall, new Color(232, 224, 208), 0.5));
        Path2D.Double p = new Path2D.Double();
        p.moveTo(cx - bw, gy + 2);
        p.lineTo(cx - tw, gy - th);
        p.lineTo(cx + tw, gy - th);
        p.lineTo(cx + bw, gy + 2);
        p.closePath();
        c.g.setPaint(new GradientPaint((float) (cx - bw), 0, Lit.mul(body, 1.08), (float) (cx + bw), 0, Lit.mul(body, 0.74)));
        c.g.fill(p);
        // cap
        Color cap = c.c(c.roof);
        c.g.setColor(cap);
        c.g.fill(new Arc2D.Double(cx - tw - 3, gy - th - tw * 1.0, (tw + 3) * 2, tw * 2.0, 0, 180, Arc2D.PIE));
        if (c.snow > 0.04) {
            c.g.setColor(c.snowCol());
            c.g.fill(new Arc2D.Double(cx - tw - 3, gy - th - tw * 1.0 - 1, (tw + 3) * 2, tw * 2.0, 25, 130, Arc2D.CHORD));
        }
        // gallery
        c.rect(cx - bw * 0.86, gy - th * 0.42, bw * 1.72, 2.2, c.c(110, 84, 62));
        c.door(cx - 4, 8, 15, c.c(86, 58, 40));
        c.window(cx - 3, gy - th * 0.36, 6, 8, 0.8, false);
        c.window(cx - 2.5, gy - th * 0.74, 5, 7, 0.6, false);
    }

    /** Hub position of a windmill's sails. */
    static double[] windmillHub(Model.Obj o) { return new double[]{o.x, o.y - o.h * 0.72 - o.w * 0.08}; }

    private static void waterTower(Ctx c) {
        double w = c.w, gy = c.y, h = c.h, cx = c.o.x;
        double tankH = h * 0.36, tankY = gy - h * 0.9, tw = w;
        Color steel = c.c(128, 134, 142);
        c.g.setColor(steel);
        c.g.setStroke(new BasicStroke(2.2f));
        double legTop = tankY + tankH - 1;
        for (double f : new double[]{-0.42, -0.14, 0.14, 0.42}) c.g.draw(new Line2D.Double(cx + f * w * 1.15, gy, cx + f * w * 0.8, legTop));
        c.g.setStroke(new BasicStroke(1f));
        for (int k = 1; k <= 3; k++) {
            double yy = gy + (legTop - gy) * k / 4.0, hw = w * (0.48 - 0.035 * k);
            c.g.draw(new Line2D.Double(cx - hw, yy, cx + hw, yy));
            if (k < 3) {
                double y2 = gy + (legTop - gy) * (k + 1) / 4.0, hw2 = w * (0.48 - 0.035 * (k + 1));
                c.g.draw(new Line2D.Double(cx - hw, yy, cx + hw2, y2));
                c.g.draw(new Line2D.Double(cx + hw, yy, cx - hw2, y2));
            }
        }
        Color tank = c.c(Lit.mix(c.wall, new Color(196, 204, 210), 0.6));
        c.g.setPaint(new GradientPaint((float) (cx - tw / 2), 0, Lit.mul(tank, 1.12), (float) (cx + tw / 2), 0, Lit.mul(tank, 0.7)));
        c.g.fill(new java.awt.geom.RoundRectangle2D.Double(cx - tw / 2, tankY, tw, tankH, 8, 8));
        c.rect(cx - tw / 2, tankY + tankH * 0.42, tw, 2.4, c.c(Lit.mul(c.roof, 0.9)));
        c.poly(c.c(c.roof), cx - tw / 2 - 2, tankY + 1, cx, gy - h, cx + tw / 2 + 2, tankY + 1);
        c.snowGable(cx - tw / 2 - 2, tankY + 1, cx, gy - h, cx + tw / 2 + 2, tankY + 1);
        c.rect(cx - 1.2, tankY + tankH, 2.4, gy - tankY - tankH, steel); // riser pipe
    }

    // ------------------------------------------------------------------ town buildings

    private static void shop(Ctx c) {
        double w = c.w, x0 = c.x0, gy = c.y, h = c.h;
        Color wall = c.c(c.wall);
        c.rect(x0, gy - h * 0.9, w, h * 0.9, wall);
        c.rect(x0 - 1.5, gy - h, w + 3, h * 0.12, c.c(Lit.mul(c.wall, 0.78))); // parapet
        c.snowFlat(x0 - 1.5, gy - h, w + 3);
        c.shadeRight(x0, gy - h * 0.9, w, h * 0.9, 0.2);
        // sign board
        Color sign = c.c(Lit.mix(c.roof, new Color(40, 60, 90), 0.35));
        c.rect(x0 + w * 0.1, gy - h * 0.86, w * 0.8, h * 0.16, sign);
        String name = (c.o.variant == null || c.o.variant.isEmpty() ? "shop" : c.o.variant).toUpperCase();
        c.g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont((float) (h * 0.12)));
        c.g.setColor(c.night > 0.5 && c.o.damage < 0.5 ? new Color(255, 236, 180) : c.c(244, 240, 226));
        double sw = c.g.getFontMetrics().stringWidth(name);
        c.g.drawString(name, (float) (c.o.x - sw / 2), (float) (gy - h * 0.735));
        // display window + door
        boolean flip = c.r.nextBoolean();
        double dw = w * 0.18, dx = flip ? x0 + w * 0.74 : x0 + w * 0.08;
        double wx = flip ? x0 + w * 0.08 : x0 + w * 0.32, ww = w * 0.6;
        c.window(wx, gy - h * 0.5, ww, h * 0.38, 1.6, false);
        c.g.setColor(new Color(40, 40, 50, 150));
        c.g.setStroke(new BasicStroke(0.9f));
        for (int k = 1; k < 3; k++) c.g.draw(new Line2D.Double(wx + ww * k / 3.0, gy - h * 0.5, wx + ww * k / 3.0, gy - h * 0.12));
        c.door(dx, dw, h * 0.5, c.c(70, 84, 96));
        // striped awning
        double ay = gy - h * 0.62, ah = h * 0.16;
        Color a1 = c.c(c.roof), a2 = c.c(240, 236, 226);
        int stripes = Math.max(4, (int) (w / 8));
        for (int k = 0; k < stripes; k++) {
            double sx0 = x0 - 3 + k * (w + 6) / stripes, sx1 = x0 - 3 + (k + 1) * (w + 6) / stripes;
            c.poly(k % 2 == 0 ? a1 : a2, sx0 + 2, ay, sx1 + 2, ay, sx1 - 1, ay + ah, sx0 - 1, ay + ah);
        }
        if (c.snow > 0.04) c.rect(x0 - 1, ay - 1 - 2 * c.snow, w + 4, 1.5 + 2 * c.snow, c.snowCol());
    }

    private static void roofFurniture(Ctx c, double x0, double top, double w) {
        Color box = c.c(118, 122, 128);
        int n = 1 + c.r.nextInt(3);
        for (int k = 0; k < n; k++) {
            double bw = 6 + c.r.nextDouble() * 10, bh = 3 + c.r.nextDouble() * 5, bx = x0 + 3 + c.r.nextDouble() * Math.max(1, w - bw - 6);
            c.rect(bx, top - bh, bw, bh, box);
            if (c.snow > 0.05) c.rect(bx - 0.5, top - bh - 1.5 * c.snow, bw + 1, 1.5 * c.snow + 0.3, c.snowCol());
        }
    }

    private static void apartment(Ctx c) {
        double w = c.w, x0 = c.x0, gy = c.y, h = c.h;
        int floors = Math.max(2, c.o.floors);
        double top = gy - h + 4;
        Color wall = c.c(c.wall);
        c.rect(x0, top, w, gy - top, wall);
        c.shadeRight(x0, top, w, gy - top, 0.16);
        c.rect(x0 - 1.5, top - 3, w + 3, 4, c.c(Lit.mul(c.wall, 0.76)));
        c.snowFlat(x0 - 1.5, top - 3, w + 3);
        roofFurniture(c, x0, top - 3, w);
        int cols = Math.max(3, (int) Math.round(w / 15));
        double fh = (gy - top - 6) / floors;
        boolean balconies = c.r.nextBoolean();
        c.win = W_CURTAIN | W_FIGURE;                 // Phase O: curtains, and shadows at the lit windows
        for (int f = 0; f < floors; f++) {
            double fy = top + 3 + f * fh;
            boolean ground = f == floors - 1;
            for (int i = 0; i < cols; i++) {
                double cw = (w - 6) / cols, wx = x0 + 3 + i * cw + cw * 0.2;
                if (ground && i == cols / 2) { c.door(wx, cw * 0.6, fh * 0.8, c.c(66, 70, 82)); continue; }
                c.window(wx, fy + fh * 0.2, cw * 0.6, fh * 0.56, 0.62, false);
                if (balconies && !ground && (i + f) % 2 == 0) {
                    c.g.setColor(c.c(70, 74, 82));
                    c.g.fill(new Rectangle2D.Double(wx - cw * 0.12, fy + fh * 0.76, cw * 0.84, 1.6));
                    c.g.setStroke(new BasicStroke(0.6f));
                    for (int b = 0; b <= 4; b++) c.g.draw(new Line2D.Double(wx - cw * 0.12 + b * cw * 0.21, fy + fh * 0.56, wx - cw * 0.12 + b * cw * 0.21, fy + fh * 0.76));
                }
            }
        }
        c.win = 0;
        // floor bands
        c.g.setColor(new Color(0, 0, 0, 26));
        for (int f = 1; f < floors; f++) c.g.fill(new Rectangle2D.Double(x0, top + 3 + f * fh - 0.5, w, 1));
    }

    private static void office(Ctx c) {
        double w = c.w, x0 = c.x0, gy = c.y, h = c.h;
        int floors = Math.max(3, c.o.floors);
        double top = gy - h + 6;
        Color wall = c.c(c.wall);
        c.rect(x0, top, w, gy - top, wall);
        c.rect(x0 - 1, top - 2.5, w + 2, 3.5, c.c(Lit.mul(c.wall, 0.7)));
        c.snowFlat(x0 - 1, top - 2.5, w + 2);
        roofFurniture(c, x0, top - 2.5, w);
        double lobby = Math.min(16, (gy - top) * 0.14), fh = (gy - top - lobby - 3) / (floors - 1);
        boolean ribbon = c.r.nextBoolean();
        int cols = Math.max(4, (int) Math.round(w / 10));
        for (int f = 0; f < floors - 1; f++) {
            double fy = top + 3 + f * fh;
            if (ribbon) {
                // ribbon glazing: a continuous band cut by thin mullions, lit in segments
                double cw = (w - 6) / cols;
                for (int i = 0; i < cols; i++) c.window(x0 + 3 + i * cw + 0.4, fy + fh * 0.18, cw - 0.8, fh * 0.56, 0.5, true);
            } else {
                c.grid(x0 + 3, fy, w - 6, fh, cols, 1, 0.62, 0.66, 0.5, true);
            }
        }
        c.shadeRight(x0, top, w, gy - top, 0.18);
        // glazed lobby
        c.window(x0 + 3, gy - lobby, w - 6, lobby - 1, 2.0, false);
        c.door(c.o.x - 4, 8, lobby * 0.86, c.c(50, 56, 68));
        c.g.setColor(new Color(255, 255, 255, 22));
        c.g.fill(new Rectangle2D.Double(x0 + w * 0.08, top, w * 0.1, gy - top - lobby)); // glass sheen
    }

    private static void skyscraper(Ctx c) {
        double w = c.w, x0 = c.x0, gy = c.y, h = c.h;
        int floors = Math.max(8, c.o.floors);
        int style = c.r.nextInt(3);
        double crown = style == 2 ? h * 0.07 : 0, top = gy - h + 8 + crown;
        Color wall = c.c(c.wall), dark = c.c(Lit.mul(c.wall, 0.66));
        // setback massing: a narrower upper section
        double sb = style == 0 ? 0.36 : 0;
        double sbY = top + (gy - top) * sb, inset = style == 0 ? w * 0.14 : 0;
        c.rect(x0 + inset, top, w - 2 * inset, sbY - top + 1, wall);
        c.rect(x0, sbY, w, gy - sbY, wall);
        double fh = (gy - top - 14) / floors;
        int cols = Math.max(5, (int) Math.round(w / 7.5));
        for (int f = 0; f < floors; f++) {
            double fy = top + 2 + f * fh;
            double fx = fy < sbY - fh * 0.5 ? x0 + inset : x0, fw = fy < sbY - fh * 0.5 ? w - 2 * inset : w;
            int cc = Math.max(3, (int) Math.round(cols * fw / w));
            c.grid(fx + 2, fy, fw - 4, fh, cc, 1, 0.70, 0.62, 0.42, true);
        }
        // vertical piers
        c.g.setColor(Lit.alpha(dark, 150));
        int piers = Math.max(2, (int) (w / 20));
        for (int k = 0; k <= piers; k++) c.g.fill(new Rectangle2D.Double(x0 + k * (w - 1.6) / piers, sbY, 1.6, gy - sbY));
        c.shadeRight(x0, sbY, w, gy - sbY, 0.2);
        if (inset > 0) { c.shadeRight(x0 + inset, top, w - 2 * inset, sbY - top, 0.2); c.snowFlat(x0, sbY, inset); c.snowFlat(x0 + w - inset, sbY, inset); }
        // glass sheen
        c.g.setPaint(new GradientPaint((float) x0, 0, new Color(255, 255, 255, 34), (float) (x0 + w * 0.4), 0, new Color(255, 255, 255, 0)));
        c.g.fill(new Rectangle2D.Double(x0, sbY, w * 0.4, gy - sbY - 14));
        // roof
        double rx = x0 + inset, rw = w - 2 * inset;
        c.rect(rx - 1, top - 2, rw + 2, 3, dark);
        if (style == 2) {
            // stepped crown
            for (int k = 0; k < 3; k++) c.rect(rx + rw * 0.12 * (k + 1), top - 2 - crown * (k + 1) / 3, rw * (1 - 0.24 * (k + 1)), crown / 3 + 1, k % 2 == 0 ? wall : dark);
        } else {
            roofFurniture(c, rx, top - 2, rw);
        }
        c.snowFlat(rx - 1, top - 2, rw + 2);
        // antenna mast (the aviation light on top is drawn live)
        double[] tip = antennaTip(c.o);
        c.g.setColor(c.c(150, 154, 160));
        c.g.setStroke(new BasicStroke(1.3f));
        c.g.draw(new Line2D.Double(tip[0], top - 2 - crown, tip[0], tip[1]));
        // lobby
        c.window(x0 + 3, gy - 13, w - 6, 11, 2.2, false);
        c.door(c.o.x - 5, 10, 11, c.c(40, 46, 58));
    }

    /** Top of a skyscraper's antenna (where the aviation light blinks). */
    static double[] antennaTip(Model.Obj o) {
        double f = Scene.hash01(o.seed * 31 + 5);
        return new double[]{o.x + (f - 0.5) * o.w * 0.3, o.y - o.h - 10 - 18 * Scene.hash01(o.seed * 17 + 3)};
    }

    private static void factory(Ctx c) {
        double w = c.w, x0 = c.x0, gy = c.y, h = c.h;
        double hallH = h * 0.5;
        Color wall = c.c(c.wall), rc = c.c(c.roof);
        // chimneys behind the hall
        for (double[] ch : chimneys(c.o)) {
            double cx = ch[0], top = ch[1], cw = ch[2];
            Path2D.Double p = new Path2D.Double();
            p.moveTo(cx - cw * 0.62, gy);
            p.lineTo(cx - cw * 0.42, top);
            p.lineTo(cx + cw * 0.42, top);
            p.lineTo(cx + cw * 0.62, gy);
            p.closePath();
            Color brick = c.c(150, 92, 76);
            c.g.setPaint(new GradientPaint((float) (cx - cw), 0, Lit.mul(brick, 1.1), (float) (cx + cw), 0, Lit.mul(brick, 0.7)));
            c.g.fill(p);
            c.rect(cx - cw * 0.46, top + 5, cw * 0.92, 3, c.c(232, 228, 220));
            c.rect(cx - cw * 0.47, top + 11, cw * 0.94, 3, c.c(170, 60, 54));
            c.rect(cx - cw * 0.5, top - 1.5, cw, 2.5, c.c(70, 64, 64));
        }
        c.rect(x0, gy - hallH, w, hallH, wall);
        // sawtooth roof
        int teeth = Math.max(3, (int) (w / 34));
        double tw = w / teeth, th = h * 0.16;
        for (int k = 0; k < teeth; k++) {
            double tx = x0 + k * tw;
            c.poly(rc, tx, gy - hallH, tx, gy - hallH - th, tx + tw, gy - hallH);
            c.g.setColor(c.on(0.9) ? new Color(255, 226, 150) : Lit.mix(c.lit.lit(new Color(110, 140, 170)), c.lit.haze, c.haze));
            c.g.fill(new Rectangle2D.Double(tx + 0.8, gy - hallH - th * 0.86, 2.2, th * 0.8));
            if (c.snow > 0.04) c.poly(c.snowCol(), tx, gy - hallH - th - 1, tx + tw, gy - hallH - 1, tx + tw - 4, gy - hallH + 1 - 3 * c.snow * 0, tx + 2, gy - hallH - th + 3 + 4 * c.snow);
        }
        c.shadeRight(x0, gy - hallH, w, hallH, 0.14);
        int cols = Math.max(4, (int) (w / 16));
        c.grid(x0 + 4, gy - hallH + 4, w - 8, hallH * 0.42, cols, 1, 0.66, 0.8, 0.8, false);
        // loading door, pipes
        c.rect(x0 + w * 0.42, gy - hallH * 0.46, w * 0.16, hallH * 0.46, c.c(86, 92, 100));
        c.g.setColor(new Color(0, 0, 0, 40));
        c.g.setStroke(new BasicStroke(0.8f));
        for (int k = 1; k < 5; k++) c.g.draw(new Line2D.Double(x0 + w * 0.42, gy - hallH * 0.46 * k / 5.0, x0 + w * 0.58, gy - hallH * 0.46 * k / 5.0));
        c.rect(x0, gy - hallH * 0.5, w, 1.6, c.c(Lit.mul(c.wall, 0.7)));
    }

    /** Chimneys of a factory: rows of {x, topY, width}. */
    static double[][] chimneys(Model.Obj o) {
        boolean two = Scene.hash01(o.seed * 13 + 1) < 0.6;
        double a = o.x - o.w * 0.5 + o.w * (0.12 + 0.1 * Scene.hash01(o.seed + 2));
        double[][] out = new double[two ? 2 : 1][];
        out[0] = new double[]{a, o.y - o.h * 1.5, 11};
        if (two) out[1] = new double[]{a + 20, o.y - o.h * 1.22, 9};
        return out;
    }

    private static void streetlamp(Ctx c) {
        double gy = c.y, h = Math.max(20, c.h), x = c.o.x;
        boolean flip = c.r.nextBoolean();
        double arm = 8 * (flip ? -1 : 1);
        Color steel = c.c(86, 92, 100);
        c.g.setColor(steel);
        c.g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x, gy + 1);
        p.lineTo(x, gy - h + 4);
        p.quadTo(x, gy - h, x + arm, gy - h);
        c.g.draw(p);
        c.rect(x - 1.6, gy - 3, 3.2, 4, steel);
        double lx = x + arm, ly = gy - h + 1.2;
        // (a lamp the storm has bent over is dark: its glow and its pool of light used to be tipped over with the post)
        double on = Lit.smooth((c.night - 0.25) / 0.3) * (1 - Lit.smooth((c.o.damage - 0.04) / 0.2));
        if (on > 0.02) {
            c.g.setPaint(new RadialGradientPaint(new Point2D.Double(lx, ly + 6), (float) (h * 0.95), new float[]{0f, 0.25f, 1f},
                    new Color[]{new Color(255, 214, 140, (int) (120 * on)), new Color(255, 206, 130, (int) (46 * on)), new Color(255, 200, 120, 0)}));
            c.g.fill(new Ellipse2D.Double(lx - h, ly + 6 - h, h * 2, h * 2));
            // pool of light on the ground
            c.g.setColor(new Color(255, 214, 150, (int) (46 * on)));
            c.g.fill(new Ellipse2D.Double(lx - h * 0.7, gy - 2.5, h * 1.4, 5));
        }
        c.g.setColor(on > 0.02 ? Lit.mix(steel, new Color(255, 240, 200), on) : steel);
        c.g.fill(new java.awt.geom.RoundRectangle2D.Double(lx - 3, ly - 1.2, 6, 2.6, 2, 2));
        if (c.snow > 0.05) c.rect(lx - 3, ly - 1.2 - 1.4 * c.snow, 6, 1.4 * c.snow, c.snowCol());
    }

    // ------------------------------------------------------------------ live parts

    /** Moving parts of a building, drawn every frame on top of the cached static image. */
    static void paintLive(Graphics2D g, Model.Obj o, Lit lit, double anim, double haze) {
        switch (o.kind) {
            case "windmill" -> {
                double[] hub = windmillHub(o);
                double len = o.h * 0.50;
                double ang = anim * (0.25 + Math.abs(o.sway) * 3.2) * (o.sway >= 0 ? 1 : -1) + Scene.hash01(o.seed) * 6.28;
                AffineTransform old = g.getTransform();
                g.translate(hub[0], hub[1]);
                g.rotate(ang);
                Color wood = lit.lit(new Color(104, 76, 54), haze), sail = lit.lit(new Color(236, 230, 214), haze);
                for (int k = 0; k < 4; k++) {
                    g.setColor(wood);
                    g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new Line2D.Double(0, 0, len, 0));
                    g.setColor(Lit.alpha(sail, 225));
                    g.fill(new Rectangle2D.Double(len * 0.2, 1, len * 0.78, len * 0.2));
                    g.setColor(Lit.alpha(wood, 150));
                    g.setStroke(new BasicStroke(0.6f));
                    for (int j = 1; j < 5; j++) g.draw(new Line2D.Double(len * (0.2 + 0.156 * j), 1, len * (0.2 + 0.156 * j), 1 + len * 0.2));
                    g.rotate(Math.PI / 2);
                }
                g.setTransform(old);
                g.setColor(lit.lit(new Color(70, 52, 40), haze));
                g.fill(new Ellipse2D.Double(hub[0] - 2.4, hub[1] - 2.4, 4.8, 4.8));
            }
            case "factory" -> {
                // smoke rising from each chimney and bending downwind
                for (double[] ch : chimneys(o)) {
                    int n = 13;
                    double wind = o.sway * 46;
                    for (int k = n - 1; k >= 0; k--) {
                        double ph = ((anim * 0.16 + k / (double) n + Scene.hash01(o.seed + (long) ch[0])) % 1 + 1) % 1;
                        double rise = ph * 120, drift = wind * ph * ph * 3.2 + Math.sin(anim * 0.7 + k * 1.9) * 4 * ph;
                        double r = 5 + ph * 26;
                        int a = (int) (118 * (1 - ph) * (1 - ph) * (0.5 + 0.5 * Math.min(1, ph * 8)));
                        Color sc = Lit.alpha(lit.lit(new Color(176, 172, 172), haze), a);
                        g.setColor(sc);
                        g.fill(new Ellipse2D.Double(ch[0] + drift - r, ch[1] - 4 - rise - r * 0.8, r * 2, r * 1.6));
                    }
                }
            }
            case "skyscraper" -> {
                double[] tip = BuildingPainter.antennaTip(o);
                double blink = 0.5 + 0.5 * Math.sin(anim * 3.2 + Scene.hash01(o.seed) * 6.28);
                double on = blink > 0.72 ? 1 : 0.12;
                double vis = 0.35 + 0.65 * lit.night;
                g.setColor(new Color(255, 60, 50, (int) (70 * on * vis)));
                g.fill(new Ellipse2D.Double(tip[0] - 5, tip[1] - 5, 10, 10));
                g.setColor(new Color(255, 96, 80, (int) (255 * Math.max(0.3, on) * vis)));
                g.fill(new Ellipse2D.Double(tip[0] - 1.6, tip[1] - 1.6, 3.2, 3.2));
            }
            default -> { }
        }
    }

    static boolean hasLive(String kind) { return kind.equals("windmill") || kind.equals("factory") || kind.equals("skyscraper"); }

    // ------------------------------------------------------------------ rain running down the panes

    /**
     * One building as it was last measured, with what its geometry depends on: its panes (x, y, w, h each), per pane what
     * decides whether it is lit (threshold, damage number) and its style (W_ bits), and the outdoor places for people its
     * painter named (type, x, y, dir, drop each; {@link Cast}).
     */
    record Parts(long seed, String kind, String variant, double x, double y, double w, double h, int floors, float[] xywh, float[] lit, byte[] style, float[] spots) {
        boolean of(Model.Obj o) {
            return seed == o.seed && x == o.x && y == o.y && w == o.w && h == o.h && floors == o.floors && kind.equals(o.kind) && java.util.Objects.equals(variant, o.variant);
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<Long, Parts> PARTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Lit PANE_LIT = new Lit(0.5, 0);

    /**
     * The window panes of a building: x, y, width and height of each in world px, in the order they are painted.
     * Measured once per building by painting it into nothing (so it can never disagree with the painter) and kept.
     */
    static float[] panes(Model.Obj o) { return parts(o).xywh; }

    /** The measured parts of a building (see {@link Parts}); measured once per building and kept. */
    static Parts parts(Model.Obj o) {
        Parts p = PARTS.get(o.id);
        if (p != null && p.of(o)) return p;
        if (PARTS.size() > 8192) PARTS.clear();
        java.awt.image.BufferedImage sink = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D sg = sink.createGraphics();
        Ctx c = new Ctx(sg, o, PANE_LIT, 0, 0, 0, true);
        paintIntact(c);
        sg.dispose();
        p = new Parts(o.seed, o.kind, o.variant, o.x, o.y, o.w, o.h, o.floors, java.util.Arrays.copyOf(c.rec, c.recN),
                java.util.Arrays.copyOf(c.recLit, c.paneN * 2), java.util.Arrays.copyOf(c.recStyle, c.paneN), java.util.Arrays.copyOf(c.recSpot, c.recSpotN));
        PARTS.put(o.id, p);
        return p;
    }

    /**
     * True when {@link #paintDrops} has something to draw for this building: wet enough, close enough, its glass whole
     * (the storm has not broken into it) and not under snow.
     * @param px device px per world px
     */
    static boolean hasDrops(Model.Obj o, double px) { return px >= RUN_MIN_PX && o.wet > RUN_WET0 && o.damage < 0.04 && o.snow < 0.25; }

    /**
     * The drops that run down the panes of a wet building, drawn every frame over the cached static image: each starts at
     * the top of its pane, slides down in fits and starts with a thin wet trail above it, and is gone for the rest of its
     * cycle. Which panes have one, where on the pane and when all come from the building's seed and the pane's number,
     * the motion from {@code anim}: nothing random, so every render band draws the same drops.
     * <p>In wind a drop does not run straight down: it leans with the wind, each drop by its own share (0..1, from the same
     * number stream) of the way from the gravity's angle to the wind's ({@link #slant}, {@link #lean}), so some run nearly
     * straight down and some nearly along the rain. It starts nearer the upwind edge of its pane, and one that reaches the
     * frame on the far side runs down along it. Without wind every drop is exactly where it was before there was any of this.
     * @param px device px per world px; (x0, y0)-(x1, y1) the part of the world that is visible (panes outside are skipped)
     * @param wind the wind at the building, m/s (positive blows to the right)
     */
    static void paintDrops(Graphics2D g, Model.Obj o, Lit lit, double anim, double haze, double px, double x0, double y0, double x1, double y1, double wind) {
        final double full = slant(wind);
        float[] p = panes(o);
        int n = p.length / 4;
        if (n == 0) return;
        double wet = Model.clamp(o.wet, 0, 1) * Model.clamp(1 - o.snow * 4, 0, 1);
        double share = Math.min(RUN_SHARE, RUN_MAX / (double) n);
        Rectangle2D.Double q = null;
        for (int i = 0; i < n; i++) {
            double x = p[i * 4], y = p[i * 4 + 1], w = p[i * 4 + 2], h = p[i * 4 + 3];
            if (w < 2 || h < 3 || x > x1 || x + w < x0 || y > y1 || y + h < y0) continue;
            // a wide pane (a shop front, a lobby) has room for several
            int lanes = w > 14 ? Math.min(4, (int) (w / 7)) : 1;
            for (int j = 0; j < lanes; j++) {
                long s = mix(o.seed * 0x9E3779B97F4A7C15L + i * 7919L + j * 104729L + 11);
                if (unit(s = step(s)) >= (lanes > 1 ? 0.8 : share)) continue;
                double amt = Lit.smooth((wet - (RUN_WET0 + 0.6 * unit(s = step(s)))) / 0.12);
                if (amt <= 0) continue;
                double period = RUN_T0 + (RUN_T1 - RUN_T0) * unit(s = step(s)), ph = anim / period + unit(s = step(s));
                ph -= Math.floor(ph);
                if (ph > RUN_DUTY) continue;
                double t = ph / RUN_DUTY, fade = Math.min(1, Math.min(t / 0.08, (1 - t) / 0.10));
                double r = Math.min(RUN_R0 + (RUN_R1 - RUN_R0) * unit(s = step(s)), w * 0.3) * (0.6 + 0.4 * amt) * Math.sqrt(fade), ry = r * 1.25;
                double ub = unit(s = step(s));
                // fits and starts: it never runs back up (the slope of this stays above zero)
                double by = y + ry + (h - 2 * ry) * (t + 0.04 * Math.sin(t * 6 * Math.PI));
                // the wind: this drop's own lean; it starts as much nearer the upwind edge as it will drift across the pane
                final double lo = x + r, hi = x + w - r, k = lean(full, unit(s = step(s)));
                double sx = lo + (hi - lo) * ub;
                if (k != 0 && hi > lo) {
                    double room = 0.8 * Math.min(1, Math.abs(k) * (h - 2 * ry) / (hi - lo));
                    sx = k > 0 ? lo + (hi - lo) * ub * (1 - room) : hi - (hi - lo) * (1 - ub) * (1 - room);
                }
                final double sy = y + ry;
                double bx = k == 0 ? sx : Math.max(lo, Math.min(hi, sx + k * (by - sy)));
                if (q == null) {
                    q = new Rectangle2D.Double();
                    // (pale by day; at night a mid tone that shows on a dark pane and on a lit one alike)
                    g.setColor(Lit.alpha(Lit.mix(Lit.mix(lit.lit(new Color(206, 224, 246)), new Color(176, 190, 214), lit.night * 0.7), lit.haze, haze), 180));
                }
                // the wet trail above it, then the drop: a rounded blob of two rectangles (a true ellipse from 5.5 device px on)
                double tail = Math.min(by - y, Math.min(h * 0.5, r * 7));
                if (tail > ry) {
                    if (k == 0) { q.setRect(bx - r * 0.28, by - tail, r * 0.56, tail - ry * 0.9); g.fill(q); }
                    else {
                        // a leaning trail, as a stair of short pieces (one antialiased rectangle each: a path costs many times that)
                        double len = tail - ry * 0.9, ty = by - tail;
                        int m = Math.max(1, Math.min(10, (int) Math.ceil(Math.abs(k) * len / (r * 0.56))));
                        for (int e = 0; e < m; e++) {
                            double cy = ty + len * (e + 0.5) / m, cx = Math.max(lo, Math.min(hi, sx + k * (cy - sy)));
                            q.setRect(cx - r * 0.28, ty + len * e / m, r * 0.56, len / m); g.fill(q);
                        }
                    }
                }
                if (r * 2 * px >= BEAD_ROUND_PX) { g.fill(new Ellipse2D.Double(bx - r, by - ry, r * 2, ry * 2)); continue; }
                q.setRect(bx - r, by - ry * 0.5, r * 2, ry * 1.2); g.fill(q);
                q.setRect(bx - r * 0.58, by - ry, r * 1.16, ry * 2); g.fill(q);
            }
        }
    }

    // ------------------------------------------------------------------ trees

    /**
     * One tree; {@code lean} is the shear from wind (0 for the static, far-zoom version). Phase O: the trees are drawn by
     * {@link TreePainter} (branches and single leaves, by level of detail); this is the entry every caller keeps using.
     */
    static void paintTree(Graphics2D g, Model.Obj o, Lit lit, double leanIn, double haze) { TreePainter.paint(g, o, lit, leanIn, haze); }
}
