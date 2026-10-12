package precip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Phase O (OW1): what the viewer makes of a tornado hitting a building. The wire carries only {@code frame.damage}
 * rows and {@code frame.tornadoes}; from those this class keeps, per {@link ViewerState}:
 * <ul>
 *   <li>for every rising damage row, the grounded tornado it belongs to (or none: then the tile simply changes);</li>
 *   <li>the pieces in the air ({@link Chunks}), cut whenever a matched row moves the building one step up the ladder;</li>
 *   <li>the <b>airborne</b> bit of a building (it has pieces in the air: its rubble piles are drawn part-built) and the
 *       <b>sheltered</b> test (a grounded tornado is near: people go indoors), both part of the ground tiles' bake key.</li>
 * </ul>
 * Nothing here is saved or sent: a reload shows the piles and the road wear, which are pure functions of the saved
 * damage ({@link Rubble}, {@link RoadWear}, through {@link #level}), and no piece in the air.
 * <p>
 * Threads: every method that changes the state is called by the reader thread with the ViewerState lock held
 * ({@code ViewerState.applyFrame}); {@link #snap()} is called under the same lock and returns an immutable picture
 * of the state for the renderers.
 */
final class Wreck {

    // ------------------------------------------------------------------ the model table's constants (PLAN.md, Phase O)
    /** A row belongs to a grounded tornado whose foot is within {@code 2.2 x width + 90} px of the building's side. */
    static final double MATCH_WIDTHS = 2.2, MATCH_PAD = 90;
    /** Sheltered: a grounded tornado within this many px, and for this many seconds after. */
    static final double SHELTER_PX = 1500, SHELTER_HOLD_S = 5;
    /** Room kept free in the scene for the building that is coming apart now, in steps of the ladder (see Chunks.makeRoom). */
    static final int ROOM_STEPS = 3;
    /** People shelter from a funnel that has come this far down from the cloud (0 = at the cloud base, 1 = on the ground). */
    static final double SHELTER_REACH = 0.35;
    /** The airborne bit stays up this long after the last piece landed and the last row came (s): one clear, one re-bake, per event. */
    static final double AIR_HOLD_S = 1.5;
    /** A building's event is forgotten this long after its last row once nothing of it is in the air (s); its still picture sooner. */
    static final double EVENT_KEEP_S = 12, STILL_KEEP_S = 3;
    /**
     * The two calls into the houses agent's {@code Life} (PLAN Phase O, "Coupling, two calls") are not wired yet: until
     * they are, nothing drawn depends on the sheltered bit, so it is kept out of the bake key (see Renderer.coverLevels).
     */
    static final boolean LIFE_WIRED = true;

    /**
     * The damage the rubble piles and the road wear are drawn from. Today it is the object's damage; Phase M (repair)
     * changes only this function: {@code d0 x (1 - p)} while a site is being cleared, 0 from the scaffold on.
     */
    static double level(Model.Obj o) { return o.damage; }

    /** The step 0..24 of the damage ladder a damage stands on. */
    static int k(double damage) { return (int) Math.round(Model.clamp(damage, 0, 1) * 24); }

    /** True for what can come apart: a building, but not a lamp post (that one bends over). */
    static boolean wreckable(Model.Obj o) { return o.building && !o.kind.equals("streetlamp"); }

    /** {@code -Dprecip.wreck.log=true}: one line per damage row with the tornado it matched (OW1). */
    static final boolean LOG = Boolean.getBoolean("precip.wreck.log");

    /** What is kept about one building while it is being hit. */
    private static final class Ev {
        Chunks.Still still;          // its picture at stillD (null = not painted yet, or dropped)
        double stillD, lastRowT;
        int flying, born;
        boolean airborne;
    }

    final Chunks chunks = new Chunks();
    private final Map<Long, Ev> events = new HashMap<>();
    private Model.Hello hello;
    private double lastT = Double.NaN;
    private List<Model.Tornado> tornadoes = List.of();
    /** Where a tornado was last on the ground: {x, t} per tornado id. */
    private final Map<Long, double[]> shelter = new HashMap<>();
    private long shelterSig;
    private float[] frontTop = new float[0];
    private Snap snap;
    private final List<Long> landedNow = new ArrayList<>();
    private long[] airborneIds = new long[0];

    // diagnostics and tests
    long rows, matched, unmatched, cuts, airSets, airClears;
    double cutNanos;
    boolean everHit;

    /** Forget everything (another world, or the simulation's clock went back). */
    void clear() {
        chunks.clear();
        events.clear();
        shelter.clear();
        tornadoes = List.of();
        lastT = Double.NaN;
        airborneIds = new long[0];
        shelterSig = 0;
        everHit = false;
        snap = null;
    }

    /**
     * A new frame: advance the pieces, note where the tornadoes are. Call before the frame's damage rows are applied.
     * @return true when a bit of the bake key changed (the caller bumps its cover version)
     */
    boolean frame(Model.Frame f, Model.Hello h, Model.Obj[] objects) {
        if (h != hello || (!Double.isNaN(lastT) && f.t < lastT - 1.0)) {
            clear();
            hello = h;
            frontTop = buildFrontTop(h, objects);
        }
        double dt = Double.isNaN(lastT) ? 0 : Model.clamp(f.t - lastT, 0, 0.5);
        lastT = f.t;
        tornadoes = f.tornadoes;
        snap = null;
        boolean changed = false;
        // sheltered: where a tornado stands on the ground now, kept for a few seconds after it lifted
        // (OI: people do not wait for the touchdown - the real simulation's funnel stood on the street for a second with the
        // porches still full; they go in once the funnel is SHELTER_REACH of the way down)
        for (Model.Tornado t : f.tornadoes) if (t.onGround() || t.reach >= SHELTER_REACH) shelter.put(t.id, new double[]{t.x, f.t});
        if (!shelter.isEmpty()) {
            long sig = 0;
            for (Iterator<Map.Entry<Long, double[]>> it = shelter.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Long, double[]> e = it.next();
                if (f.t - e.getValue()[1] >= SHELTER_HOLD_S) it.remove();
                else sig += (e.getKey() * 1_000_003L) ^ (long) Math.floor(e.getValue()[0] / 64);
            }
            if (shelter.size() > 16) shelter.clear();          // (never in practice: a handful of tornadoes at most)
            if (sig != shelterSig) { shelterSig = sig; changed |= LIFE_WIRED; }
        } else if (shelterSig != 0) { shelterSig = 0; changed |= LIFE_WIRED; }
        if (events.isEmpty() && chunks.size() == 0) return changed;
        // the pieces
        landedNow.clear();
        if (hello != null) chunks.step(dt, tornadoes, hello.terrain, landedNow);
        for (Long id : landedNow) {
            Ev e = events.get(id);
            if (e != null && e.flying > 0) e.flying--;
        }
        // the airborne bit, and housekeeping
        boolean air = false;
        for (Iterator<Map.Entry<Long, Ev>> it = events.entrySet().iterator(); it.hasNext(); ) {
            Ev e = it.next().getValue();
            boolean now = e.born > 0 && (e.flying > 0 || f.t - e.lastRowT < AIR_HOLD_S);
            if (now != e.airborne) {
                e.airborne = now;
                air = true;
                if (now) airSets++; else airClears++;
            }
            if (e.still != null && f.t - e.lastRowT > STILL_KEEP_S) e.still = null;
            if (!e.airborne && e.flying == 0 && f.t - e.lastRowT > EVENT_KEEP_S) it.remove();
        }
        if (air) { rebuildAirborne(); changed = true; }
        return changed;
    }

    private void rebuildAirborne() {
        int n = 0;
        for (Ev e : events.values()) if (e.airborne) n++;
        long[] ids = new long[n];
        n = 0;
        for (Map.Entry<Long, Ev> e : events.entrySet()) if (e.getValue().airborne) ids[n++] = e.getKey();
        Arrays.sort(ids);
        airborneIds = ids;
    }

    /** The grounded tornado a row of {@code o} belongs to (the nearest within reach), or null. */
    Model.Tornado match(Model.Obj o) {
        Model.Tornado best = null;
        double bd = Double.MAX_VALUE;
        for (Model.Tornado t : tornadoes) {
            if (!t.onGround()) continue;
            double d = Math.abs(o.x - t.x);
            if (d - o.w / 2 > MATCH_WIDTHS * t.width + MATCH_PAD) continue;
            if (d < bd) { bd = d; best = t; }
        }
        return best;
    }

    /**
     * One rising damage row has been applied: {@code before} is the object as it was, {@code after} as it is now.
     * A row with a grounded tornado near cuts pieces whenever it moves the building a step up the ladder; any other
     * row (a replay without its tornado, a load, a row of Phase M) is a plain tile change.
     */
    void row(Model.Obj before, Model.Obj after, Model.Frame f) {
        rows++;
        Model.Tornado t = wreckable(after) && after.damage > before.damage ? match(after) : null;
        if (LOG) System.out.println(String.format(java.util.Locale.ROOT, "[wreck] t=%.2f row %s %d %.2f -> %.2f (k %d -> %d): %s", f.t, after.kind, after.id,
                before.damage, after.damage, k(before.damage), k(after.damage),
                t == null ? (wreckable(after) ? "no grounded tornado near" : "not a building that comes apart") + " (plain tile change)" : String.format(java.util.Locale.ROOT, "tornado %d at dx %.0f (reach %.0f)", t.id, after.x - t.x, MATCH_WIDTHS * t.width + MATCH_PAD + after.w / 2)));
        if (t == null) { unmatched++; return; }
        matched++;
        everHit = true;
        Ev e = events.get(after.id);
        if (e == null) {
            if (events.size() >= 96) return;                    // (bounded: more buildings than this under one funnel do not happen)
            e = new Ev();
            e.stillD = before.damage;
            events.put(after.id, e);
        }
        e.lastRowT = f.t;
        int k0 = k(e.stillD), k1 = k(after.damage);
        if (k1 <= k0) return;
        // one step (or more) up the ladder: what vanished between the two pictures comes off
        long n0 = System.nanoTime();
        Lit lit = litNear(f, after.x);
        Model.Obj painted = Advanced.cur.noSnow && after.snow > 0 ? after.withState(after.sway, 0, after.wet, after.w, after.h) : after;
        Chunks.Still a = e.still != null && e.still.damage == e.stillD ? e.still : Chunks.still(painted, e.stillD, lit);
        Chunks.Still b = Chunks.still(painted, after.damage, lit);
        // OI (the swept slab): the step that takes a house off its slab makes more pieces, and a house that will end so
        // keeps room for them (both are functions of the object alone: BuildingPainter.swept)
        final boolean sweeps = BuildingPainter.swept(after) && !BuildingPainter.swept(after.withDamage(e.stillD));
        final int cap = Chunks.MAX_OBJ - (!sweeps && BuildingPainter.swept(after.withDamage(1)) ? Chunks.SWEPT_RESERVE : 0);
        // OI: when the scene is nearly full, the pieces that have been up the longest are let go (they fall to their piles
        // over the next second or two), so that the steps that follow have room: ROOM_STEPS steps' worth is kept free
        final int wantFree = ROOM_STEPS * (sweeps ? Chunks.MAX_SWEPT_STEP : Chunks.MAX_STEP);
        if (hello != null && Chunks.MAX_SCENE - chunks.size() < wantFree) chunks.makeRoom(wantFree - (Chunks.MAX_SCENE - chunks.size()), after.id, hello.terrain);
        int free = Math.min(cap - e.flying, Chunks.MAX_SCENE - chunks.size());
        // (paced, so that the walls still get their pieces after the roof took its share)
        int allow = sweeps ? Math.min(Chunks.MAX_SWEPT_STEP, free)
                : Math.min(Math.min(Chunks.MAX_STEP, free), Math.max(2, (int) Math.ceil((cap - e.flying) * 2.0 / Math.max(1, 24 - k0))));
        List<Chunks.Chunk> fresh = chunks.cut(after, a, b, allow);
        int piles = Math.max(1, Rubble.count(after, Math.max(level(after), Rubble.K_MIN / 24.0)));
        Model.Terrain terrain = hello != null ? hello.terrain : null;
        for (Chunks.Chunk c : fresh) {
            // its designated pile: a tower's pieces come down beside it, the rest are shared out over the piles
            c.pile = c.tower ? Rubble.nearest(after, piles, c.x) : Math.floorMod(c.serial, piles);
            c.px1 = Rubble.spotX(after, c.pile);
            c.py1 = (terrain != null ? terrain.at(c.px1) : after.y) - 1;
        }
        chunks.add(fresh);
        if (LOG) System.out.println(String.format(java.util.Locale.ROOT, "[wreck] t=%.2f cut %s %d k %d -> %d%s: %d pieces (allowed %d, %d of its own in the air, %d in the scene)", f.t, after.kind, after.id,
                k0, k1, BuildingPainter.swept(after) ? " SWEPT" : "", fresh.size(), allow, e.flying, chunks.size() - fresh.size()));
        e.flying += fresh.size();
        e.born += fresh.size();
        // (the bit goes up with the row that made the pieces: the tile is re-baked for the new step anyway)
        if (!e.airborne && e.born > 0) { e.airborne = true; airSets++; rebuildAirborne(); }
        e.still = b;
        e.stillD = after.damage;
        cuts++;
        cutNanos += System.nanoTime() - n0;
        snap = null;
    }

    /**
     * The light a still is painted in: the hour of the frame and the overcast above the building, worked out as the
     * renderer works it out for a view about this wide (it cannot be asked: the pieces are cut on the reader thread).
     */
    static Lit litNear(Model.Frame f, double x) {
        double cover = 0;
        for (Model.Cloud c : f.clouds) {
            if (c.x + c.w / 2 < x - 840 || c.x - c.w / 2 > x + 840) continue;
            cover += c.w * c.opacity * c.form * (0.3 + c.darkness);
        }
        cover /= 1680 * 1.6;
        cover = Math.round(Math.min(0.55, cover * 0.5) * 12) / 12.0;
        return new Lit(f.timeOfDay, cover, Advanced.cur.noTint);
    }

    /** Per 8 px of the world: the highest front-row roof line over x plus 8 px (the ground where no front-row building stands). */
    private static float[] buildFrontTop(Model.Hello h, Model.Obj[] objects) {
        if (h == null) return new float[0];
        int n = (int) Math.ceil(h.w / 8.0) + 1;
        float[] top = new float[n];
        for (int i = 0; i < n; i++) top[i] = (float) h.terrain.at(i * 8.0);
        for (Model.Obj o : objects) {
            if (!o.building || o.layer == 1 || o.kind.equals("streetlamp")) continue;
            int i0 = Math.max(0, (int) Math.floor((o.x - o.w / 2) / 8)), i1 = Math.min(n - 1, (int) Math.ceil((o.x + o.w / 2) / 8));
            float y = (float) (o.y - o.h);
            for (int i = i0; i <= i1; i++) top[i] = Math.min(top[i], y);
        }
        for (int i = 0; i < n; i++) top[i] -= 8;
        return top;
    }

    // ------------------------------------------------------------------ what the tests and --until ask

    boolean airborne(long id) { Ev e = events.get(id); return e != null && e.airborne; }

    int flying(long id) { Ev e = events.get(id); return e == null ? 0 : e.flying; }

    int eventCount() { return events.size(); }

    boolean groundedNow() {
        for (Model.Tornado t : tornadoes) if (t.onGround()) return true;
        return false;
    }

    /** {@code --until hit:first | lift | done}: the first piece is in the air / the funnel has left the ground after a hit / everything has come down and the bits are clear. */
    boolean reached(String what) {
        return switch (what) {
            case "first" -> chunks.born > 0;
            case "lift" -> everHit && !groundedNow();
            default -> everHit && tornadoes.isEmpty() && chunks.size() == 0 && airborneIds.length == 0;
        };
    }

    // ------------------------------------------------------------------ the renderer's picture of the state

    /** An immutable picture of the state for one rendered frame. */
    static final class Snap {
        static final Snap NONE = new Snap(new Chunks.Draw[0], new long[0], new double[0], new float[0], 0);

        final Chunks.Draw[] draws;
        /** Sorted ids of the buildings with pieces in the air. */
        final long[] airborne;
        /** x, t pairs: where a tornado was last on the ground. */
        final double[] shelter;
        private final float[] frontTop;
        final double t;

        Snap(Chunks.Draw[] draws, long[] airborne, double[] shelter, float[] frontTop, double t) {
            this.draws = draws; this.airborne = airborne; this.shelter = shelter; this.frontTop = frontTop; this.t = t;
        }

        boolean airborne(long id) { return airborne.length > 0 && Arrays.binarySearch(airborne, id) >= 0; }

        /** A grounded tornado is (or was within {@link #SHELTER_HOLD_S} s) within {@link #SHELTER_PX} of x. */
        boolean sheltered(double x) {
            for (int i = 0; i < shelter.length; i += 2) if (Math.abs(shelter[i] - x) < SHELTER_PX) return true;
            return false;
        }

        /** A fresh piece is still held where it was cut: the tile that shows its hole should be baked without delay. */
        boolean hot() {
            for (Chunks.Draw d : draws) if (d.state == Chunks.HELD) return true;
            return false;
        }

        /** The front roof line over x (world y; a back-row piece below it is behind the front row). */
        double frontTop(double x) {
            if (frontTop.length == 0) return Double.MAX_VALUE;
            int i = (int) Math.round(x / 8);
            return frontTop[Math.max(0, Math.min(frontTop.length - 1, i))];
        }
    }

    /** The picture of the state as of the last frame (cached until the next frame or cut). */
    Snap snap() {
        Snap s = snap;
        if (s != null) return s;
        if (chunks.size() == 0 && airborneIds.length == 0 && shelter.isEmpty()) return snap = Snap.NONE;
        Chunks.Draw[] d = new Chunks.Draw[chunks.size()];
        for (int i = 0; i < d.length; i++) d[i] = new Chunks.Draw(chunks.list.get(i));
        double[] sh = new double[shelter.size() * 2];
        int n = 0;
        for (double[] r : shelter.values()) { sh[n++] = r[0]; sh[n++] = r[1]; }
        return snap = new Snap(d, airborneIds, sh, frontTop, lastT);
    }
}
