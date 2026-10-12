package precip;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase O (OW2, OW3): the pieces a tornado tears off a building. A piece is cut from the building's own picture (the
 * pixels that vanished between two still pictures of {@link BuildingPainter#paint}), is carried round the funnel by a
 * mirror of the simulation's debris law, is released when the funnel moves on or lifts, and falls to its designated
 * rubble pile ({@link Rubble#spotX}), where it ends in a puff of dust. Nothing here is on the wire or in a save.
 * <p>
 * The list is owned by a {@link Wreck} (one per {@link ViewerState}) and only changed on the reader thread under the
 * state's lock; the renderer draws immutable {@link Draw} rows of a {@link Wreck.Snap}.
 */
final class Chunks {

    // ------------------------------------------------------------------ the model table's constants (PLAN.md, Phase O)
    // Chunk source
    /** At most this many pieces from one step of the damage ladder (largest first; what is left over is simply gone: the painter's heap shows it). */
    static final int MAX_STEP = 8;
    /** At most this many pieces of one building / in the whole scene in the air at once. */
    static final int MAX_OBJ = 40, MAX_SCENE = 160;
    /**
     * OI (the swept slab): the step that sweeps a house off its slab ({@link BuildingPainter#swept}) takes the rest of its
     * walls at once, so it may make this many pieces, and a house that will end swept keeps {@link #SWEPT_RESERVE} of its
     * {@link #MAX_OBJ} free for that step (without the reserve the house had its 40 in the air by then and the walls
     * simply went: 2 to 8 pieces in scene "hit").
     */
    static final int MAX_SWEPT_STEP = 16, SWEPT_RESERVE = 14;
    /** Grid the vanished pixels are cut on (world px), by kind (OW3): houses and shops, barn-sized buildings, towers. */
    static final double CELL_SMALL = 8, CELL_MID = 14, CELL_TOWER = 22;
    /** A grid cell becomes a piece when this many world px of it vanished. */
    static final double CELL_MIN_PX = 12;
    /** The stills are painted at 2 px per world px while the picture is no larger than this (world px^2), else at 1. */
    static final double STILL_FINE_AREA = 45_000;
    // Chunk flight: mirror of sim/particles.py update_debris (and sim/tornado.py core_radius, axis_x, ground_factor)
    static final double GRAVITY = 480;                          // DEBRIS_GRAVITY, px/s^2
    static final double UPDRAFT = 900, UPDRAFT_WIND = 14;       // (900 + 14 wind) / mass, px/s^2, near the axis
    static final double UPDRAFT_VMAX = 420, FALL_VMAX = 520;    // px/s
    static final double PULL = 2.4, DRAG = 0.7, SWING = 260;    // 1/s towards the axis, 1/s, px/s^2 with the vortex phase
    static final double ZONE_K = 3.2, ZONE_PAD = 40, ZONE_OUT = 1.6;   // the swing zone: |dx| <= 1.6 (3.2 core + 40)
    static final double CORE_K = 2.0, CORE_PAD = 25;            // the updraft's width: exp(-(dx / (2 core + 25))^2)
    static final double BASE_PAD = 30;                          // no updraft above the cloud base + 30
    static final double GROUND_REACH = 0.9;                     // the vortex's effects fade in between this reach and 1
    /** mass = clamp(area / 60, 1, 6): light bits fly high, big ones tumble. */
    static final double MASS_AREA = 60, MASS_MIN = 1, MASS_MAX = 6;
    /** A piece that has been up this long is let go (s). */
    static final double T_LOFT_MAX = 40;
    /** A tower's pieces shear off and fall down the facade; they are lofted only in a wind of at least this (m/s, EF4). */
    static final double TOWER_LOFT_WIND = 75;
    /** A fresh piece stays where it was cut for this long (s): the tile that shows the hole is baked a moment after the row. */
    static final double T_HOLD = 0.20;
    // Fall and landing
    /** The shortest fall to the pile (s), and the puff of dust at the end (s). */
    static final double FALL_T_MIN = 0.30, T_DUST = 0.40;
    /** The speed a piece keeps of what it had when it was let go (px/s): upward at most this, sideways at most this. */
    static final double RELEASE_UP_MAX = 160, RELEASE_SIDE_MAX = 220;

    static final int HELD = 0, LOFT = 1, FALL = 2, DUST = 3;

    /** One piece. */
    static final class Chunk {
        long obj;
        int serial, state = HELD, pile;
        boolean back, tower;
        int[] px;                    // premultiplied ARGB, sw x sh
        int sw, sh;
        double src;                  // source px per world px
        double x, y, vx, vy, rot, spin, mass, area, half;
        double tState, tLoft;
        // the fall: from (fx0, fy0) with the speed (fvx, fvy) to the pile at (px1, py1) in fT seconds
        double fx0, fy0, fvx, fvy, px1, py1, fT;
        boolean hop;
    }

    /** One piece as drawn (immutable). */
    static final class Draw {
        final int[] px;
        final int sw, sh, state;
        final double src, x, y, vx, vy, rot, spin, half, fade;
        final boolean back;

        Draw(Chunk c) {
            px = c.px; sw = c.sw; sh = c.sh; state = c.state; src = c.src; x = c.x; y = c.y;
            vx = c.state == LOFT || c.state == FALL ? c.vx : 0; vy = c.state == LOFT || c.state == FALL ? c.vy : 0;
            rot = c.rot; spin = c.state == LOFT || c.state == FALL ? c.spin : 0; half = c.half; back = c.back;
            fade = c.state == DUST ? Model.clamp(c.tState / T_DUST, 0, 1) : 0;
        }
    }

    final List<Chunk> list = new ArrayList<>();
    /** Diagnostics and tests. */
    long born, landed, capped, lofted, released;
    private int serial;

    void clear() { list.clear(); }

    int size() { return list.size(); }

    int count(long obj) {
        int n = 0;
        for (Chunk c : list) if (c.obj == obj && c.state != DUST) n++;
        return n;
    }

    // ------------------------------------------------------------------ the still picture and the cut

    /** A still picture of one building at one damage, in its own image. */
    static final class Still {
        BufferedImage img;
        int[] px;
        int w, h;
        double x0, y0, scale, damage;
    }

    static boolean towerKind(String kind) { return kind.equals("apartment") || kind.equals("office") || kind.equals("skyscraper"); }

    static double cellOf(Model.Obj o) {
        return switch (o.kind) {
            case "apartment", "office", "skyscraper" -> CELL_TOWER;
            case "barn", "church", "factory", "water_tower", "windmill" -> CELL_MID;
            default -> CELL_SMALL;
        };
    }

    /** The scale the stills of a building are painted at (px per world px). */
    static double stillScale(Model.Obj o) { return (o.w + 80) * (o.h * 1.62 + 52) <= STILL_FINE_AREA ? 2 : 1; }

    /**
     * {@code BuildingPainter.paint(o.withDamage(d))} onto a transparent image, exactly as the ground tile paints it:
     * a back-row building at 0.9 of its size about its foot and through 0.36 of haze.
     */
    static Still still(Model.Obj o, double d, Lit lit) {
        Still s = new Still();
        s.scale = stillScale(o);
        s.damage = d;
        s.x0 = Math.floor(o.x - o.w / 2 - 40);
        s.y0 = Math.floor(o.y - o.h * 1.62 - 40);
        s.w = Math.max(1, (int) Math.ceil((o.w + 80) * s.scale) + 2);
        s.h = Math.max(1, (int) Math.ceil((o.h * 1.62 + 52) * s.scale) + 2);
        s.img = new BufferedImage(s.w, s.h, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = s.img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(s.scale, s.scale);
        g.translate(-s.x0, -s.y0);
        Model.Obj od = o.withDamage(d);
        if (o.layer == 1) {
            g.translate(o.x, o.y);
            g.scale(0.9, 0.9);
            g.translate(-o.x, -o.y);
            BuildingPainter.paint(g, od, lit, 0.36, 0);
        } else BuildingPainter.paint(g, od, lit, 0, 0);
        g.dispose();
        s.px = ((DataBufferInt) s.img.getRaster().getDataBuffer()).getData();
        return s;
    }

    /** One cell of the cut grid with what vanished in it. */
    private record Cell(int cx, int cy, int n, double mx, double my) {}

    /**
     * Cut what vanished between two stills of the same building (same size and scale) into pieces: the pixels solid in
     * {@code a} and gone in {@code b}, on a grid of {@link #cellOf} px, the largest cells first.
     * @param allow how many pieces this step may make (the caps and the pacing are the caller's)
     * @return the new pieces, not yet in the list (state {@link #HELD}, at the place they were cut from)
     */
    List<Chunk> cut(Model.Obj o, Still a, Still b, int allow) {
        List<Chunk> out = new ArrayList<>();
        if (allow <= 0 || a.w != b.w || a.h != b.h) return out;
        final double S = a.scale, cell = cellOf(o);
        final int cp = Math.max(2, (int) Math.round(cell * S)), gw = (a.w + cp - 1) / cp, gh = (a.h + cp - 1) / cp;
        // (the last few px above the ground are the painter's heap of rubble, whose flecks move with the damage: not a piece)
        final int yMax = Math.min(a.h, (int) Math.floor((o.y - 3 - a.y0) * S));
        int[] cnt = new int[gw * gh];
        long[] sx = new long[gw * gh], sy = new long[gw * gh];
        final int[] pa = a.px, pb = b.px;
        for (int y = 0; y < yMax; y++) {
            int row = y * a.w, gy = (y / cp) * gw;
            for (int x = 0; x < a.w; x++) {
                if ((pa[row + x] >>> 24) < 128 || (pb[row + x] >>> 24) >= 96) continue;
                int ci = gy + x / cp;
                cnt[ci]++; sx[ci] += x; sy[ci] += y;
            }
        }
        List<Cell> cells = new ArrayList<>();
        final int need = (int) Math.ceil(CELL_MIN_PX * S * S);
        for (int i = 0; i < cnt.length; i++) if (cnt[i] >= need) cells.add(new Cell(i % gw, i / gw, cnt[i], sx[i] / (double) cnt[i], sy[i] / (double) cnt[i]));
        // largest first; equal sizes from the top down, then left to right (so the same cut always gives the same pieces)
        cells.sort((p, q) -> p.n != q.n ? Integer.compare(q.n, p.n) : p.cy != q.cy ? Integer.compare(p.cy, q.cy) : Integer.compare(p.cx, q.cx));
        capped += Math.max(0, cells.size() - allow);
        for (int k = 0; k < cells.size() && k < allow; k++) {
            Cell c = cells.get(k);
            int x0 = c.cx * cp, y0 = c.cy * cp, w = Math.min(cp, a.w - x0), h = Math.min(cp, Math.min(a.h, yMax) - y0);
            if (w <= 0 || h <= 0) continue;
            Chunk ch = new Chunk();
            ch.obj = o.id;
            ch.serial = serial++;
            ch.back = o.layer == 1;
            ch.tower = towerKind(o.kind);
            ch.sw = w + 2; ch.sh = h + 2;                       // (a transparent rim, so the scaled edge is smooth)
            ch.px = new int[ch.sw * ch.sh];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int i = (y0 + y) * a.w + x0 + x;
                if ((pa[i] >>> 24) >= 128 && (pb[i] >>> 24) < 96) ch.px[(y + 1) * ch.sw + x + 1] = pa[i];
            }
            ch.src = S;
            ch.x = a.x0 + (x0 + w * 0.5) / S;
            ch.y = a.y0 + (y0 + h * 0.5) / S;
            ch.area = c.n / (S * S);
            ch.half = Math.hypot(w, h) * 0.5 / S + 1;
            ch.mass = Model.clamp(ch.area / MASS_AREA, MASS_MIN, MASS_MAX);
            out.add(ch);
        }
        return out;
    }

    /**
     * OI: make room in the air. Lets go of up to {@code n} pieces the vortex is carrying - the ones that have been up the
     * longest, none of building {@code keep} - so that they fall to their piles and the scene has room for the pieces of
     * the building that is coming apart now. (On the real simulation's hit the first four houses filled the scene's
     * {@link #MAX_SCENE} for the 40 s a piece may stay up, and the houses after them came apart without a single piece.)
     * @return how many were let go
     */
    int makeRoom(int n, long keep, Model.Terrain terrain) {
        int done = 0;
        while (done < n) {
            Chunk old = null;
            for (Chunk c : list) if (c.state == LOFT && c.obj != keep && (old == null || c.tLoft > old.tLoft)) old = c;
            if (old == null || old.tLoft < ROOM_MIN_LOFT_S) break;
            release(old, terrain);
            done++;
            madeRoom++;
        }
        return done;
    }

    /** A piece is not let go to make room before it has been up this long (s): every piece is seen to fly first. */
    static final double ROOM_MIN_LOFT_S = 3.0;
    /** Diagnostics: pieces let go early to make room. */
    long madeRoom;

    /** Put freshly cut pieces into the list (the caller has assigned their piles). */
    void add(List<Chunk> fresh) {
        list.addAll(fresh);
        born += fresh.size();
    }

    // ------------------------------------------------------------------ flight

    private static double unit(long n) { return Scene.hash01(n * 0x9E3779B97F4A7C15L + 0x5851F42D4C957F2DL); }

    static double groundFactor(Model.Tornado t) { return Lit.smooth((t.reach - GROUND_REACH) / (1 - GROUND_REACH)); }

    static double coreRadius(Model.Tornado t) { return Math.max(t.width * 0.5, 8) * 1.25; }

    /** x of the vortex axis at height y: the foot at ground_y, the top at base_y offset by the lean (sim/tornado.py axis_x). */
    static double axisX(Model.Tornado t, double y) {
        double span = t.groundY - t.baseY, f = span > 1 ? Model.clamp((t.groundY - y) / span, 0, 1) : 0;
        return t.x + t.lean * f;
    }

    /** True when a piece of this mass would rise at the axis of that tornado (its updraft beats gravity). */
    static boolean lifts(double mass, Model.Tornado t) { return (UPDRAFT + UPDRAFT_WIND * t.windMs) / mass * groundFactor(t) > GRAVITY * 1.05; }

    /**
     * Advance every piece by {@code dt} simulated seconds.
     * @param out the ids of the objects one of whose pieces has just landed are added here (one entry per piece)
     */
    void step(double dt, List<Model.Tornado> tornadoes, Model.Terrain terrain, List<Long> out) {
        if (list.isEmpty() || !(dt > 0)) return;
        int n = Math.max(1, (int) Math.ceil(dt * 60 - 1e-9));
        double h = dt / n;
        for (int s = 0; s < n; s++) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Chunk c = list.get(i);
                c.tState += h;
                switch (c.state) {
                    case HELD -> { if (c.tState >= T_HOLD) launch(c, tornadoes, terrain); }
                    case LOFT -> loft(c, h, tornadoes, terrain);
                    case FALL -> {
                        double u = Math.min(1, c.tState / c.fT), tt = Math.min(c.tState, c.fT);
                        double e = u * u * (3 - 2 * u), k = (1 - u) * (1 - u);
                        double nx = c.fx0 + (c.px1 - c.fx0) * e + c.fvx * tt * k, ny;
                        if (c.hop) ny = c.fy0 + (c.py1 - c.fy0) * u - 4 * u * (1 - u) * (5 + Math.abs(c.px1 - c.fx0) * 0.06);
                        else ny = c.fy0 + c.fvy * tt + 0.5 * GRAVITY * tt * tt;
                        c.vx = (nx - c.x) / h; c.vy = (ny - c.y) / h;
                        c.x = nx; c.y = ny;
                        c.rot += c.spin * h * (1 - u);
                        if (u >= 1) {                             // down: exactly at its pile
                            c.x = c.px1; c.y = c.py1; c.vx = 0; c.vy = 0;
                            c.state = DUST; c.tState = 0;
                            landed++;
                            out.add(c.obj);
                        }
                    }
                    default -> { if (c.tState >= T_DUST) list.remove(i); }
                }
            }
        }
    }

    /** The end of the hold: up into the vortex when one can carry it, else straight down to its pile. */
    private void launch(Chunk c, List<Model.Tornado> tornadoes, Model.Terrain terrain) {
        Model.Tornado best = null;
        for (Model.Tornado t : tornadoes) {
            if (groundFactor(t) <= 0) continue;
            double q = (c.x - axisX(t, c.y)) / (ZONE_K * coreRadius(t) + ZONE_PAD);
            if (Math.abs(q) > ZONE_OUT) continue;
            if (best == null || Math.abs(c.x - t.x) < Math.abs(c.x - best.x)) best = t;
        }
        boolean up = best != null && lifts(c.mass, best) && (!c.tower || best.windMs >= TOWER_LOFT_WIND);
        if (!up) { release(c, terrain); return; }
        c.state = LOFT; c.tState = 0; c.tLoft = 0;
        c.vx = (unit(c.serial * 31L + 1) - 0.5) * 60;
        c.vy = -(30 + 70 * unit(c.serial * 31L + 2)) / c.mass;
        c.spin = (unit(c.serial * 31L + 3) < 0.5 ? -1 : 1) * (2 + 4 * unit(c.serial * 31L + 4)) / Math.sqrt(c.mass);
        lofted++;
    }

    /** One step of the debris law (see the constants). */
    private void loft(Chunk c, double h, List<Model.Tornado> tornadoes, Model.Terrain terrain) {
        c.vy += GRAVITY * h;
        boolean inZone = false;
        for (Model.Tornado t : tornadoes) {
            double g = groundFactor(t);
            if (g <= 0) continue;
            double rc = coreRadius(t), dx = c.x - axisX(t, c.y), q = dx / (ZONE_K * rc + ZONE_PAD);
            if (q > ZONE_OUT || q < -ZONE_OUT) continue;
            inZone = true;
            double gw = Math.exp(-q * q), gc = Math.exp(-Math.pow(dx / (CORE_K * rc + CORE_PAD), 2));
            if (c.y > t.baseY + BASE_PAD) {
                c.vy -= (UPDRAFT + UPDRAFT_WIND * t.windMs) / c.mass * g * gc * h;      // the updraft beats gravity near the axis
                if (c.vy < -UPDRAFT_VMAX) c.vy = -UPDRAFT_VMAX;
            }
            c.vx += (-dx * PULL * g - c.vx * DRAG) * gw * h + SWING * g * gw * Math.sin(t.phase + c.y * 0.02 + c.rot) * h;
        }
        if (c.vy > FALL_VMAX) c.vy = FALL_VMAX;
        c.x += c.vx * h;
        c.y += c.vy * h;
        c.rot += c.spin * h;
        c.tLoft += h;
        // let go: the funnel has lifted or moved on, it has been up too long, or it came down by itself
        if (!inZone || c.tLoft > T_LOFT_MAX || (c.vy > 0 && c.y >= terrain.at(c.x) - 1)) release(c, terrain);
    }

    /** Let a piece go: from here it falls to its pile (gravity, and a horizontal ease that ends exactly there). */
    private void release(Chunk c, Model.Terrain terrain) {
        if (c.state == LOFT) released++;
        c.state = FALL; c.tState = 0;
        c.fx0 = c.x; c.fy0 = Math.min(c.y, terrain.at(c.x) - 1);
        c.y = c.fy0;
        c.fvx = Model.clamp(c.vx, -RELEASE_SIDE_MAX, RELEASE_SIDE_MAX);
        c.fvy = Model.clamp(c.vy, -RELEASE_UP_MAX, FALL_VMAX);
        double dy = c.py1 - c.fy0;
        // arrival after sqrt(2 dy / g) from rest; with the speed it has: the positive root of dy = v t + g t^2 / 2
        double disc = c.fvy * c.fvy + 2 * GRAVITY * dy, T = disc > 0 ? (-c.fvy + Math.sqrt(disc)) / GRAVITY : 0;
        c.hop = !(T >= FALL_T_MIN);
        if (c.hop) {                                              // already (nearly) down: it tumbles over the ground to the pile
            c.fvx = 0;
            T = Model.clamp(Math.abs(c.px1 - c.fx0) / 140.0, FALL_T_MIN, 1.4);
        }
        c.fT = T;
        if (c.spin == 0) c.spin = (unit(c.serial * 31L + 3) < 0.5 ? -1 : 1) * (1.5 + 3 * unit(c.serial * 31L + 4)) / Math.sqrt(c.mass);
    }

    // ------------------------------------------------------------------ drawing

    private static final Color DUST_COL = new Color(176, 164, 146);
    /** The puff of dust where a piece lands: how far a ball swells (world px, times the piece's size factor) and its opacity at the start (of 255). */
    static final double DUST_R = 8.0;
    static final int DUST_ALPHA = 170;
    /** Test switch (WreckTest): draw no piece, so a picture with them can be compared with the same picture without. */
    static volatile boolean hide;

    /**
     * Draw the pieces of this frame (band renderers, concurrently; nothing here is written). The pixels are composed by
     * a loop of our own, so a piece that straddles two bands is the same in both.
     * @param direct the render target's pixels (null = draw through {@code g}, world transform expected)
     * @param extrap seconds the picture is ahead of the last frame
     */
    static void paint(Graphics2D g, Wreck.Snap s, Camera.View v, Lit lit, Direct direct, double bandWy0, double bandWy1, double extrap) {
        paint(g, s, v, lit, direct, bandWy0, bandWy1, extrap, ALL, null);
    }

    /** The two places in the frame the pieces are drawn at (see {@link #early}); {@link #ALL} = every piece in one call. */
    static final int ALL = 0, EARLY = 1, LATE = 2;

    /**
     * Is this piece drawn in the early slot (behind the clouds, under the storm's veil)? One that is still held where it
     * was cut (it is part of the building's picture until the tile shows the hole), and one the vortex has taken above
     * the cloud base (the cloud swallows it, as it does the simulation's debris). Every other piece is in the open air
     * under the cloud and is drawn late, after the veil and after the simulation's own debris, so that it reads in the swirl.
     */
    static boolean early(Draw d, double x, double y, List<Model.Tornado> tornadoes) {
        if (d.state == HELD) return true;
        if (d.state == DUST || tornadoes == null || tornadoes.isEmpty()) return false;
        Model.Tornado near = null;
        for (Model.Tornado t : tornadoes) if (near == null || Math.abs(t.x - x) < Math.abs(near.x - x)) near = t;
        return y < near.baseY + 4;
    }

    /**
     * @param slot      {@link #EARLY}, {@link #LATE} or {@link #ALL}: which pieces this call draws (each piece is in exactly one slot)
     * @param tornadoes the frame's tornadoes (the cloud base a piece may be above); null = none
     */
    static void paint(Graphics2D g, Wreck.Snap s, Camera.View v, Lit lit, Direct direct, double bandWy0, double bandWy1, double extrap, int slot, List<Model.Tornado> tornadoes) {
        if (s == null || s.draws.length == 0 || hide) return;
        final double x0 = v.wx0, x1 = v.wx1, y0 = Math.max(v.wy0, bandWy0), y1 = Math.min(v.wy1, bandWy1);
        boolean dust = false;
        for (Draw d : s.draws) {
            double x = d.x + d.vx * extrap, y = d.y + d.vy * extrap;
            if (slot != ALL && early(d, x, y, tornadoes) != (slot == EARLY)) continue;
            if (x + d.half < x0 || x - d.half > x1 || y + d.half < y0 || y - d.half > y1) { if (d.state == DUST) dust = true; continue; }
            // a back-row piece is behind the front row: it shows only above the front roof line
            if (d.back && y + d.half * 0.5 > s.frontTop(x)) continue;
            if (d.state == DUST) dust = true;
            int a256 = d.state == DUST ? (int) Math.round(256 * (1 - d.fade)) : 256;
            if (a256 <= 0) continue;
            double rot = d.rot + d.spin * extrap;
            if (direct != null && direct.px != null) blit(direct, d, v.ws * x + v.wtx, v.ws * y + v.wty, rot, v.ws / d.src, a256);
            else {
                AffineTransform at = new AffineTransform();
                at.translate(x, y);
                at.rotate(rot);
                at.scale(1 / d.src, 1 / d.src);
                at.translate(-d.sw * 0.5, -d.sh * 0.5);
                BufferedImage img = new BufferedImage(d.sw, d.sh, BufferedImage.TYPE_INT_ARGB_PRE);
                img.setRGB(0, 0, d.sw, d.sh, unpre(d.px), 0, d.sw);
                java.awt.Composite keep = g.getComposite();
                if (a256 < 256) g.setComposite(java.awt.AlphaComposite.SrcOver.derive(a256 / 256f));
                g.drawImage(img, at, null);
                g.setComposite(keep);
            }
        }
        if (!dust || slot == EARLY) return;
        // the puff where a piece came down: a few soft balls that swell and thin out
        Color dc = lit.lit(DUST_COL);
        for (Draw d : s.draws) {
            if (d.state != DUST || d.x + 16 < x0 || d.x - 16 > x1 || d.y + 6 < y0 || d.y - 20 > y1) continue;
            if (d.back && d.y > s.frontTop(d.x)) continue;
            // (OI: larger and denser than the first cut - 5.5 px and alpha 120 were barely seen at zoom 1-2)
            double u = d.fade, r = 1.6 + DUST_R * Math.sqrt(u) * Math.min(1.6, 0.7 + d.half * 0.08);
            int al = (int) Math.round(DUST_ALPHA * (1 - u) * (1 - u));
            if (al <= 2) continue;
            g.setColor(Lit.alpha(dc, al));
            for (int k = 0; k < 3; k++) {
                double ox = (k - 1) * r * 0.9, oy = -r * 0.45 - (k == 1 ? r * 0.5 * u : 0);
                g.fill(new Ellipse2D.Double(d.x + ox - r, d.y + oy - r * 0.7 - 2 * u, r * 2, r * 1.4));
            }
        }
    }

    private static int[] unpre(int[] p) {
        int[] o = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            int a = p[i] >>> 24;
            if (a == 0) continue;
            int r = Math.min(255, ((p[i] >> 16) & 255) * 255 / a), g = Math.min(255, ((p[i] >> 8) & 255) * 255 / a), b = Math.min(255, (p[i] & 255) * 255 / a);
            o[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return o;
    }

    /**
     * SrcOver a piece, turned by {@code rot} about its centre at device (cx, cy) and scaled by {@code k} device px per
     * source px, into the target: every target pixel in the clip looks its colour up in the piece (bilinear).
     */
    private static void blit(Direct t, Draw d, double cx, double cy, double rot, double k, int a256) {
        final double cos = Math.cos(rot), sin = Math.sin(rot), hw = d.sw * 0.5, hh = d.sh * 0.5;
        final double ex = (Math.abs(cos) * hw + Math.abs(sin) * hh) * k, ey = (Math.abs(sin) * hw + Math.abs(cos) * hh) * k;
        final int x0 = Math.max(t.cx0, (int) Math.floor(cx - ex)), x1 = Math.min(t.cx1, (int) Math.ceil(cx + ex) + 1);
        final int y0 = Math.max(t.cy0, (int) Math.floor(cy - ey)), y1 = Math.min(t.cy1, (int) Math.ceil(cy + ey) + 1);
        if (x0 >= x1 || y0 >= y1) return;
        final double ik = 1 / k;
        final int[] src = d.px, dst = t.px;
        final int sw = d.sw, sh = d.sh;
        // device -> source: turn back, scale back, to the image's corner; -0.5 = sample positions are pixel centres.
        // Worked out afresh from the pixel's own coordinates (not summed along the row), so a band draws what one pass draws
        final double ux = cos * ik, wx = -sin * ik, uy = sin * ik, wy = cos * ik, u0 = hw - 0.5 - (cx - 0.5) * ux - (cy - 0.5) * uy, w0 = hh - 0.5 - (cx - 0.5) * wx - (cy - 0.5) * wy;
        final double iux = Math.abs(ux) > 1e-9 ? 1 / ux : 0, iwx = Math.abs(wx) > 1e-9 ? 1 / wx : 0;
        final int dU = (int) Math.round(ux * 65536.0), dW = (int) Math.round(wx * 65536.0), uMax = sw << 16, wMax = sh << 16;      // (a piece is a few dozen px: 16.16 fits an int)
        for (int yy = y0; yy < y1; yy++) {
            final double ur = u0 + yy * uy, wr = w0 + yy * wy;
            // the stretch of this row that can lie inside the piece (a pixel more on either side; every pixel is still tested)
            double xa = x0, xb = x1;
            if (ux > 1e-9) { xa = Math.max(xa, (-1 - ur) * iux); xb = Math.min(xb, (sw - ur) * iux); }
            else if (ux < -1e-9) { xa = Math.max(xa, (sw - ur) * iux); xb = Math.min(xb, (-1 - ur) * iux); }
            else if (!(ur > -1 && ur < sw)) continue;
            if (wx > 1e-9) { xa = Math.max(xa, (-1 - wr) * iwx); xb = Math.min(xb, (sh - wr) * iwx); }
            else if (wx < -1e-9) { xa = Math.max(xa, (sh - wr) * iwx); xb = Math.min(xb, (-1 - wr) * iwx); }
            else if (!(wr > -1 && wr < sh)) continue;
            if (xb < xa) continue;
            final int ra = Math.max(x0, (int) Math.floor(xa) - 1), rb = Math.min(x1, (int) Math.ceil(xb) + 2);
            int di = yy * t.w + ra;
            // 16.16 fixed point along the row, started afresh on every row from the row's own numbers (the bands split the
            // picture by rows only, so a row is the same whichever band draws it)
            int U = (int) Math.floor((ur + ra * ux) * 65536.0), W = (int) Math.floor((wr + ra * wx) * 65536.0);
            for (int xx = ra; xx < rb; xx++, di++, U += dU, W += dW) {
                if (U <= -65536 || W <= -65536 || U >= uMax || W >= wMax) continue;
                final int iu = U >> 16, iw = W >> 16;
                final int p00, p10, p01, p11;
                if (iu >= 0 && iw >= 0 && iu + 1 < sw && iw + 1 < sh) {
                    final int si = iw * sw + iu;
                    p00 = src[si]; p10 = src[si + 1]; p01 = src[si + sw]; p11 = src[si + sw + 1];
                } else { p00 = at(src, sw, sh, iu, iw); p10 = at(src, sw, sh, iu + 1, iw); p01 = at(src, sw, sh, iu, iw + 1); p11 = at(src, sw, sh, iu + 1, iw + 1); }
                if ((p00 | p10 | p01 | p11) == 0) continue;
                final int fu = (U >> 8) & 255, fw = (W >> 8) & 255, gu = 256 - fu, gw = 256 - fw;
                // bilinear, two channels at a time (alpha and green in one word, red and blue in the other)
                final int agT = (((p00 >>> 8) & 0xFF00FF) * gu + ((p10 >>> 8) & 0xFF00FF) * fu) >>> 8 & 0xFF00FF, rbT = ((p00 & 0xFF00FF) * gu + (p10 & 0xFF00FF) * fu) >>> 8 & 0xFF00FF;
                final int agB = (((p01 >>> 8) & 0xFF00FF) * gu + ((p11 >>> 8) & 0xFF00FF) * fu) >>> 8 & 0xFF00FF, rbB = ((p01 & 0xFF00FF) * gu + (p11 & 0xFF00FF) * fu) >>> 8 & 0xFF00FF;
                final int ag = (agT * gw + agB * fw) >>> 8 & 0xFF00FF, rb2 = (rbT * gw + rbB * fw) >>> 8 & 0xFF00FF;
                int a = ag >>> 16, gg = ag & 255, r = rb2 >>> 16, b = rb2 & 255;
                if (a == 0) continue;
                if (a256 < 256) { a = (a * a256) >> 8; r = (r * a256) >> 8; gg = (gg * a256) >> 8; b = (b * a256) >> 8; if (a == 0) continue; }
                if (a >= 255) { dst[di] = 0xFF000000 | (r << 16) | (gg << 8) | b; continue; }
                final int tp = dst[di], ia = 255 - a;
                r += mul8((tp >> 16) & 255, ia); gg += mul8((tp >> 8) & 255, ia); b += mul8(tp & 255, ia);
                dst[di] = 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, gg) << 8) | Math.min(255, b);
            }
        }
    }

    private static int at(int[] p, int w, int h, int x, int y) { return x < 0 || y < 0 || x >= w || y >= h ? 0 : p[y * w + x]; }

    private static int mul8(int a, int b) {
        int t = a * b + 128;
        return (t + (t >> 8)) >> 8;
    }
}
