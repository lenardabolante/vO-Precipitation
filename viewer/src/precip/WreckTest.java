package precip;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * The checks of PLAN Phase O, part OW (step OW6), run by {@code --selftest-ui} and {@code --selftest} after the other
 * five: which damage rows belong to a tornado, that no piece is made without one, that a piece is made of the very
 * pixels the building lost, the caps, the release and the landing (every piece ends on the ground at its pile), the
 * airborne and sheltered bits, {@link Wreck#level}, the rubble piles (pure function of the damage, nothing floats),
 * the road wear (inside the street's strip, none beside an undamaged building, none on a highway), the same picture in
 * one band and in four, the cost of the live pass, and the scene {@code hit} from the first row to the last landing.
 * <p>
 * Opens no window, needs no Python and writes no file. {@code java -cp ... precip.WreckTest pictures DIR} writes the
 * evidence pictures of OW2 and OW3 instead (the cut of one step, and four buildings taking the same hit).
 */
final class WreckTest {
    private final List<String> failures = new ArrayList<>();
    private int step;
    private final List<String> notes = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (System.getProperty("sun.java2d.renderer.clip") == null) System.setProperty("sun.java2d.renderer.clip", "false");
        if (args.length >= 2 && args[0].equals("pictures")) { pictures(new File(args[1])); System.exit(0); }
        System.exit(run());
    }

    static int run() {
        WreckTest t = new WreckTest();
        Advanced keep = Advanced.cur;
        try {
            t.go();
        } catch (Throwable ex) {
            ex.printStackTrace();
            t.failures.add("exception: " + ex);
        } finally {
            Chunks.hide = false;
            Advanced.publish(keep);
        }
        for (String n : t.notes) System.out.println("[wreck-test]     " + n);
        if (t.failures.isEmpty()) {
            System.out.println("[wreck-test] PASS (" + t.step + " checks)   [not checked here: the real simulation's rows (the fake's are used), "
                    + "the live window with its debounced bakes]");
            return 0;
        }
        System.out.println("[wreck-test] FAIL");
        for (String f : t.failures) System.out.println("[wreck-test]   - " + f);
        return 1;
    }

    private void check(boolean ok, String what) {
        step++;
        System.out.println(String.format("[wreck-test] %2d  %s  %s", step, ok ? "ok  " : "FAIL", what));
        if (!ok) failures.add(step + ": " + what);
    }

    // ------------------------------------------------------------------ a small world made by hand

    private static Model.Hello world(double slope, boolean street, boolean highway) {
        Model.Hello h = new Model.Hello();
        h.w = 3000; h.h = 1080; h.groundY = 900;
        double[] y = new double[h.w / 8 + 1];
        for (int i = 0; i < y.length; i++) y[i] = 900 - slope * (i * 8 - 1500) + (slope != 0 ? 6 * Math.sin(i * 0.11) : 0);
        h.terrain = new Model.Terrain(8, y);
        if (street) { Model.Road r = new Model.Road(); r.x0 = 600; r.x1 = 2400; r.kind = "street"; h.roads.add(r); }
        if (highway) { Model.Road r = new Model.Road(); r.x0 = 0; r.x1 = 3000; r.kind = "highway"; h.roads.add(r); }
        return h;
    }

    private static Model.Obj obj(Model.Hello h, long id, String kind, String variant, double x, double w, double hh, int floors, int layer) {
        Model.Obj o = new Model.Obj();
        o.id = id; o.seed = 1000 + id * 7919; o.kind = kind; o.variant = variant; o.x = x; o.y = h.terrain.at(x); o.w = w; o.h = hh;
        o.floors = floors; o.layer = layer; o.building = Model.isBuilding(kind);
        boolean tower = kind.equals("office") || kind.equals("skyscraper");
        o.wall = tower ? new int[]{96, 118, 140} : kind.equals("barn") ? new int[]{150, 58, 48} : new int[]{214, 196, 170};
        o.roof = kind.equals("barn") ? new int[]{84, 78, 76} : new int[]{142, 62, 48};
        return o;
    }

    private static Model.Tornado tornado(long id, double x, double reach, double width, double wind, double groundY) {
        Model.Tornado t = new Model.Tornado();
        t.id = id; t.x = x; t.reach = reach; t.width = width; t.topWidth = width * 2.4; t.windMs = wind; t.strength = 0.6;
        t.baseY = groundY - 520; t.groundY = groundY; t.stage = reach >= 0.995 ? "mature" : "funnel"; t.ef = 3;
        return t;
    }

    private static Model.Frame frame(double t, Model.Tornado... ts) {
        Model.Frame f = new Model.Frame();
        f.t = t; f.frame = Math.round(t * 30); f.timeOfDay = 0.45;
        List<Model.Tornado> l = new ArrayList<>();
        for (Model.Tornado tn : ts) { tn.phase = t * 9; l.add(tn); }
        f.tornadoes = l;
        return f;
    }

    /** A hand-fed wreck: the objects, the clock, and what happened to every piece ever made. */
    private static final class Rig {
        final Model.Hello hello;
        Model.Obj[] objs;
        final Wreck w = new Wreck();
        double t = 10;
        int bitFlips, maxScene, maxObj, maxStep, maxSweep, sweeps, badEnd, airEnd, loftAfterLift = -1;
        double maxLoft;
        final Map<Chunks.Chunk, double[]> seen = new IdentityHashMap<>();      // last {state, x, y}
        final Map<Chunks.Chunk, Model.Obj> owner = new IdentityHashMap<>();
        final java.util.Set<Long> gave = new java.util.HashSet<>();           // the buildings that have given a piece
        final Map<Long, Integer> bornBy = new HashMap<>();                    // pieces made, by building

        Rig(Model.Hello hello, Model.Obj... objs) { this.hello = hello; this.objs = objs; }

        /** One frame of dt seconds; {@code rise} is added to the damage of every object in {@code hit} (one row each). */
        void step(double dt, double rise, int[] hit, Model.Tornado... ts) {
            t += dt;
            Model.Frame f = frame(t, ts);
            if (w.frame(f, hello, objs)) bitFlips++;
            if (rise > 0 && hit != null) {
                Model.Obj[] next = objs.clone();
                for (int i : hit) {
                    Model.Obj o = objs[i];
                    if (o.damage >= 1) continue;
                    next[i] = o.withDamage(o.damage + rise);
                    int before = w.chunks.size();
                    w.row(o, next[i], f);
                    // (the step that sweeps a house off its slab has its own, larger share)
                    if (BuildingPainter.swept(next[i]) && !BuildingPainter.swept(o)) { sweeps++; maxSweep = Math.max(maxSweep, w.chunks.size() - before); }
                    else maxStep = Math.max(maxStep, w.chunks.size() - before);
                }
                objs = next;
            }
            track();
        }

        private void track() {
            // what became of the pieces that are gone: each must have ended as dust lying at its pile
            Map<Chunks.Chunk, Boolean> now = new IdentityHashMap<>();
            for (Chunks.Chunk c : w.chunks.list) now.put(c, true);
            for (java.util.Iterator<Map.Entry<Chunks.Chunk, double[]>> it = seen.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Chunks.Chunk, double[]> e = it.next();
                if (now.containsKey(e.getKey())) continue;
                Chunks.Chunk c = e.getKey();
                double[] s = e.getValue();
                if (s[0] != Chunks.DUST) airEnd++;
                else if (Math.abs(s[1] - c.px1) > 1 || Math.abs(s[2] - c.py1) > 1 || Math.abs(c.py1 - (hello.terrain.at(c.px1) - 1)) > 1e-6) badEnd++;
                it.remove();
            }
            int scene = 0;
            Map<Long, Integer> per = new HashMap<>();
            for (Chunks.Chunk c : w.chunks.list) {
                if (seen.put(c, new double[]{c.state, c.x, c.y}) == null) bornBy.merge(c.obj, 1, Integer::sum);
                gave.add(c.obj);
                if (c.state != Chunks.DUST) { scene++; per.merge(c.obj, 1, Integer::sum); }
                if (c.state == Chunks.LOFT) maxLoft = Math.max(maxLoft, c.tLoft);
            }
            maxScene = Math.max(maxScene, scene);
            for (int n : per.values()) maxObj = Math.max(maxObj, n);
        }

        int lofting() {
            int n = 0;
            for (Chunks.Chunk c : w.chunks.list) if (c.state == Chunks.LOFT) n++;
            return n;
        }
    }

    private static int[] all(int n) { int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = i; return a; }

    // ------------------------------------------------------------------ the checks

    private void go() throws Exception {
        Advanced.publish(Advanced.DEFAULT);
        final Model.Hello flat = world(0, true, false);

        // ---- Wreck.level and the ladder
        {
            Model.Obj o = obj(flat, 1, "house", "cottage", 1000, 62, 52, 1, 0).withDamage(0.37);
            check(Wreck.level(o) == o.damage && Wreck.k(0.37) == 9 && Wreck.k(0) == 0 && Wreck.k(1) == 24 && Wreck.k(0.5) == 12,
                    "Wreck.level(o) is the object's damage today (the one function Phase M changes); the ladder is round(d x 24)");
        }

        // ---- a row and its tornado
        {
            Model.Obj house = obj(flat, 1, "house", "cottage", 1000, 62, 52, 1, 0), tree = obj(flat, 2, "tree", "oak", 1040, 30, 60, 1, 0),
                    lamp = obj(flat, 3, "streetlamp", "", 960, 8, 34, 1, 0);
            Rig none = new Rig(flat, house);
            for (int i = 0; i < 60; i++) none.step(1 / 30.0, 0.02, all(1));
            Rig aloft = new Rig(flat, house);
            for (int i = 0; i < 60; i++) aloft.step(1 / 30.0, 0.02, all(1), tornado(1, 1000, 0.6, 90, 60, 900));
            double reach = Wreck.MATCH_WIDTHS * 90 + Wreck.MATCH_PAD + 31;
            Rig far = new Rig(flat, house);
            for (int i = 0; i < 60; i++) far.step(1 / 30.0, 0.02, all(1), tornado(1, 1000 + reach + 2, 1, 90, 60, 900));
            Rig near = new Rig(flat, house);
            for (int i = 0; i < 60; i++) near.step(1 / 30.0, 0.02, all(1), tornado(1, 1000 + reach - 2, 1, 90, 60, 900));
            Rig other = new Rig(flat, tree, lamp);
            for (int i = 0; i < 60; i++) other.step(1 / 30.0, 0.02, all(2), tornado(1, 1000, 1, 90, 60, 900));
            check(none.w.rows == 50 && none.w.unmatched == 50 && none.w.chunks.born == 0 && none.bitFlips == 0 && none.w.eventCount() == 0 && none.objs[0].damage >= 0.99,
                    "no tornado (a replay, a load, a row of Phase M): 50 rows, " + none.w.unmatched + " plain tile changes, " + none.w.chunks.born + " pieces, no bit, no state kept");
            check(aloft.w.unmatched == aloft.w.rows && aloft.w.chunks.born == 0, "a funnel that has not reached the ground matches no row (" + aloft.w.chunks.born + " pieces)");
            check(far.w.matched == 0 && far.w.chunks.born == 0 && near.w.matched == near.w.rows && near.w.chunks.born > 0,
                    String.format(Locale.ROOT, "a grounded tornado matches a row within 2.2 x width + 90 px of the building's side: 2 px outside %d rows matched, 2 px inside %d of %d (%d pieces)",
                            far.w.matched, near.w.matched, near.w.rows, near.w.chunks.born));
            check(other.w.matched == 0 && other.w.chunks.born == 0 && other.w.rows > 0, "a tree and a lamp post under the funnel take their rows as plain tile changes (" + other.w.rows + " rows, 0 pieces)");
        }

        // ---- a piece is made of the pixels the building lost
        {
            Lit lit = new Lit(0.45, 0.3);
            String[] what = {"cottage k 8 -> 9", "office k 15 -> 16", "barn k 10 -> 11", "back-row farmhouse k 12 -> 17"};
            Model.Obj[] os = {obj(flat, 1, "house", "cottage", 1000, 62, 52, 1, 0), obj(flat, 2, "office", "", 1300, 86, 124, 8, 0), obj(flat, 3, "barn", "", 1600, 96, 70, 1, 0),
                    obj(flat, 4, "house", "farmhouse", 1900, 84, 62, 2, 1)};
            int[][] ks = {{8, 9}, {15, 16}, {10, 11}, {12, 17}};
            StringBuilder sb = new StringBuilder();
            boolean ok = true;
            for (int i = 0; i < os.length; i++) {
                Chunks.Still a = Chunks.still(os[i], ks[i][0] / 24.0, lit), b = Chunks.still(os[i], ks[i][1] / 24.0, lit);
                int gone = 0;
                for (int p = 0; p < a.px.length; p++) if ((a.px[p] >>> 24) >= 128 && (b.px[p] >>> 24) < 96) gone++;
                Chunks cs = new Chunks();
                List<Chunks.Chunk> all = cs.cut(os[i], a, b, 10_000), eight = new Chunks().cut(os[i], a, b, Chunks.MAX_STEP);
                int pix = 0, wrong = 0;
                double minArea = 1e9;
                for (Chunks.Chunk c : all) {
                    minArea = Math.min(minArea, c.area);
                    // where its window sits in the still: its centre is the window's centre
                    int x0 = (int) Math.round((c.x - a.x0) * a.scale - (c.sw - 2) * 0.5), y0 = (int) Math.round((c.y - a.y0) * a.scale - (c.sh - 2) * 0.5);
                    for (int y = 0; y < c.sh - 2; y++) for (int x = 0; x < c.sw - 2; x++) {
                        int v = c.px[(y + 1) * c.sw + x + 1];
                        if (v == 0) continue;
                        pix++;
                        int q = (y0 + y) * a.w + x0 + x;
                        if (v != a.px[q] || (b.px[q] >>> 24) >= 96) wrong++;
                    }
                }
                ok &= wrong == 0 && pix > 0 && pix <= gone && eight.size() <= Chunks.MAX_STEP && minArea >= Chunks.CELL_MIN_PX - 1e-9;
                sb.append(String.format(Locale.ROOT, "%s%s: %d px gone, %d of them in %d pieces (0 wrong: %s), 8 a step -> %d", i == 0 ? "" : "; ", what[i], gone, pix, all.size(), wrong == 0, eight.size()));
            }
            check(ok, "every pixel of a piece is a pixel the building had and has no more, at the place it was (" + sb + ")");
        }

        // ---- caps
        {
            Model.Obj[] os = new Model.Obj[9];
            for (int i = 0; i < os.length; i++) os[i] = obj(flat, i + 1, i % 3 == 0 ? "house" : i % 3 == 1 ? "barn" : "shop", i % 3 == 0 ? "cottage" : "", 900 + i * 60, 62 + (i % 3) * 14, 52 + (i % 2) * 12, 1, 0);
            Rig r = new Rig(flat, os);
            // a strong funnel standing on the street: everything light stays up, 9 buildings go 0 -> 1 in 24 steps
            for (int i = 0; i < 24 * 4; i++) r.step(1 / 30.0, i % 4 == 0 ? 1 / 24.0 + 1e-9 : 0, all(os.length), tornado(1, 1140, 1, 90, 80, 900));
            check(r.maxStep <= Chunks.MAX_STEP && r.maxSweep <= Chunks.MAX_SWEPT_STEP && r.maxObj <= Chunks.MAX_OBJ && r.maxScene <= Chunks.MAX_SCENE && r.w.chunks.born >= Chunks.MAX_SCENE,
                    String.format(Locale.ROOT, "caps: at most %d pieces a step (8), %d in the step that sweeps a house off its slab (16; %d such steps), %d of one building (40), %d in the scene (160) while 9 buildings were torn down (%d pieces made, %d cells left to the heap)",
                            r.maxStep, r.maxSweep, r.sweeps, r.maxObj, r.maxScene, r.w.chunks.born, r.w.chunks.capped));
        }

        // ---- OI: room in the air. Seven houses come down one after the other (6 s each) under a funnel that stays; the
        // first four fill the scene for the 40 s a piece may stay up. The real simulation's EF3 did exactly this, and the
        // houses after them came apart without a piece until the oldest pieces were let go for them
        {
            Model.Obj[] os = new Model.Obj[7];
            for (int i = 0; i < os.length; i++) os[i] = obj(flat, i + 1, "house", i % 2 == 0 ? "cottage" : "bungalow", 900 + i * 70, 62, 52, 1, 0);
            Rig r = new Rig(flat, os);
            for (int b = 0; b < os.length; b++)
                for (int i = 0; i < 24 * 8; i++) r.step(1 / 30.0, i % 8 == 0 ? 1 / 24.0 + 1e-9 : 0, new int[]{b}, tornado(1, 1110, 1, 90, 80, 900));
            int least = Integer.MAX_VALUE;
            for (Model.Obj o : os) least = Math.min(least, r.bornBy.getOrDefault(o.id, 0));
            long let = r.w.chunks.madeRoom;
            r.step(1 / 30.0, 0, null, tornado(1, 1110, 0.7, 60, 30, 900));
            for (int i = 0; i < 30 * 8; i++) r.step(1 / 30.0, 0, null);
            check(least >= 20 && let >= 40 && r.maxScene <= Chunks.MAX_SCENE && r.w.chunks.landed == r.w.chunks.born && r.airEnd == 0 && r.badEnd == 0,
                    String.format(Locale.ROOT, "room in the air: 7 houses come down in turn under a funnel that stays (45 s); every one gives at least %d pieces (20 wanted), %d of the oldest were let go early to fall to their piles, never more than %d in the scene (160), all %d landed",
                            least, let, r.maxScene, r.w.chunks.born));
        }

        // ---- flight, release, landing
        {
            final Model.Hello hill = world(0.05, true, false);
            String[][] kinds = {{"house", "cottage"}, {"barn", ""}, {"church", ""}, {"office", ""}, {"house", "cabin"}, {"shop", "bakery"}, {"house", "farmhouse"}, {"apartment", ""},
                    {"house", "manor"}, {"factory", ""}, {"house", "townhouse"}, {"windmill", ""}, {"house", "bungalow"}, {"water_tower", ""}, {"house", "cottage"}, {"barn", ""}};
            double[][] wh = {{62, 52}, {96, 70}, {74, 132}, {86, 124}, {58, 42}, {72, 46}, {84, 62}, {80, 92}, {112, 78}, {150, 84}, {46, 78}, {54, 118}, {80, 40}, {44, 96}, {62, 52}, {96, 70}};
            Model.Obj[] os = new Model.Obj[kinds.length];
            for (int i = 0; i < os.length; i++) os[i] = obj(hill, i + 1, kinds[i][0], kinds[i][1], 1000 + i * 110, wh[i][0], wh[i][1], i == 3 ? 8 : i == 7 ? 5 : 1, i == 6 || i == 7 ? 1 : 0);
            Rig r = new Rig(hill, os);
            double tx = 950;
            int lofted = 0;
            for (int i = 0; i < 30 * 20; i++) {                    // 20 s on the ground, walking along the street at 60 px/s
                tx += 3;
                r.step(1 / 30.0, i % 5 == 0 ? 1 / 24.0 + 1e-9 : 0, all(os.length), tornado(1, tx, 1, 90, 62, hill.terrain.at(tx)));
                lofted = Math.max(lofted, r.lofting());
            }
            int upAtLift = r.lofting();
            r.step(1 / 30.0, 0, null, tornado(1, tx, 0.7, 60, 30, hill.terrain.at(tx)));          // the funnel lifts
            int upAfter = r.lofting();
            for (int i = 0; i < 30 * 8; i++) r.step(1 / 30.0, 0, null);
            long made = r.w.chunks.born;
            // How many pieces: the funnel (90 px/s) is within Wreck.MATCH reach of the first 6 buildings while their damage
            // rises (4 s), so 6 give pieces and 10 are plain tile changes. The number each gives is what its still pictures
            // lose in cells of >= CELL_MIN_PX, so it moves with the painter: 200 before rule F (which was the old bound, with
            // no margin), 191 since - rule F brings a loose slab down inside the picture at the step that frees it, so the
            // office offers 87 cells where it offered 102. The bound that belongs to the model is the scene cap: more pieces
            // than may be in the air at once, so every one of them had to be let go and land for the next to be cut.
            check(made > Chunks.MAX_SCENE && r.gave.size() >= 6 && r.w.chunks.landed == made && r.w.chunks.size() == 0 && r.airEnd == 0 && r.badEnd == 0,
                    String.format(Locale.ROOT, "%d pieces (more than the %d the scene holds at once) of the %d buildings the funnel reached while they came down (16 on the slope): %d landed, each within 1 px of its pile and on the ground (%d ended in the air, %d off their pile), none left over",
                            made, Chunks.MAX_SCENE, r.gave.size(), r.w.chunks.landed, r.airEnd, r.badEnd));
            check(lofted >= 20 && upAtLift > 0 && upAfter == 0, "release when the funnel lifts: " + upAtLift + " pieces were up, " + upAfter + " still lofted one frame later (they are falling to their piles)");
            boolean outside = true;
            for (Model.Obj o : os) for (int j = 0; j < Rubble.MAX_PILES; j++) {
                double d = Math.abs(Rubble.spotX(o, j) - o.x);
                outside &= d >= o.w / 2 + Rubble.GAP - 1e-9 && d <= o.w / 2 + Rubble.GAP + Rubble.radius(o) + 1e-9;
            }
            check(outside, "a pile is never inside its own building: every spot lies between w/2 + 6 and w/2 + 6 + R from the middle (R = clamp(40 + 0.9 h, 60, 260))");
            // the funnel moves on (the piece leaves the swing zone), and a piece that is up too long
            Rig gone = new Rig(flat, obj(flat, 1, "house", "cottage", 1000, 62, 52, 1, 0));
            for (int i = 0; i < 90; i++) gone.step(1 / 30.0, i % 4 == 0 ? 0.05 : 0, all(1), tornado(1, 1000, 1, 90, 62, 900));
            int up0 = gone.lofting();
            gone.step(1 / 30.0, 0, null, tornado(1, 2600, 1, 90, 62, 900));
            int up1 = gone.lofting();
            Rig longUp = new Rig(flat, obj(flat, 1, "house", "cottage", 1000, 62, 52, 1, 0));
            for (int i = 0; i < 30 * 50; i++) longUp.step(1 / 30.0, i < 120 && i % 4 == 0 ? 0.05 : 0, all(1), tornado(1, 1000, 1, 90, 62, 900));
            check(up0 > 0 && up1 == 0 && longUp.maxLoft <= Chunks.T_LOFT_MAX + 0.05 && longUp.lofting() == 0,
                    String.format(Locale.ROOT, "release when the funnel has moved on (%d up -> %d) and after T_LOFT_MAX (longest loft %.1f s of 40)", up0, up1, longUp.maxLoft));
            // towers: nothing lofted below EF4 wind
            Rig tower = new Rig(flat, obj(flat, 1, "office", "", 1000, 86, 124, 8, 0));
            for (int i = 0; i < 200; i++) tower.step(1 / 30.0, i % 4 == 0 ? 0.02 : 0, all(1), tornado(1, 1000, 1, 90, 62, 900));
            Rig towerHi = new Rig(flat, obj(flat, 1, "office", "", 1000, 86, 124, 8, 0));
            int hiUp = 0;
            for (int i = 0; i < 200; i++) { towerHi.step(1 / 30.0, i % 4 == 0 ? 0.02 : 0, all(1), tornado(1, 1000, 1, 90, 110, 900)); hiUp = Math.max(hiUp, towerHi.lofting()); }
            check(tower.w.chunks.born > 0 && tower.w.chunks.lofted == 0 && tower.objs[0].damage <= 1,
                    "a tower's pieces shear off and fall down the facade: " + tower.w.chunks.born + " pieces at 62 m/s, " + tower.w.chunks.lofted + " lofted (at 110 m/s: " + towerHi.w.chunks.lofted + ")");
        }

        // ---- the bits
        {
            Model.Obj[] os = {obj(flat, 1, "house", "cottage", 1000, 62, 52, 1, 0), obj(flat, 2, "barn", "", 1100, 96, 70, 1, 0), obj(flat, 3, "house", "cabin", 2900, 58, 42, 1, 0)};
            Rig r = new Rig(flat, os);
            boolean setWithRow = false, sheltNear = false, sheltFar = true;
            for (int i = 0; i < 150; i++) {
                r.step(1 / 30.0, i % 4 == 0 ? 0.04 : 0, new int[]{0, 1}, tornado(1, 1050, 1, 90, 62, 900));
                if (i == 40) { setWithRow = r.w.airborne(1) && r.w.airborne(2) && !r.w.airborne(3); Wreck.Snap s = r.w.snap(); sheltNear = s.sheltered(1000) && s.sheltered(2540); sheltFar = s.sheltered(2560); }
            }
            long setsDuring = r.w.airSets;
            double tLift = r.t;
            boolean held = false, gone = true;
            for (int i = 0; i < 30 * 14; i++) {
                r.step(1 / 30.0, 0, null);
                if (Math.abs(r.t - tLift - 4.8) < 0.02) held = r.w.snap().sheltered(1000);
                if (Math.abs(r.t - tLift - 5.3) < 0.02) gone = r.w.snap().sheltered(1000);
            }
            check(setWithRow && setsDuring == 2 && r.w.airSets == 2 && r.w.airClears == 2 && !r.w.airborne(1) && !r.w.airborne(2),
                    "airborne bit: up with the row that made the first piece, down once after the last piece landed - " + r.w.airSets + " sets and " + r.w.airClears + " clears for 2 buildings hit (one extra re-bake each)");
            check(sheltNear && !sheltFar && held && !gone, "sheltered: a grounded tornado within 1500 px (not at 1510), and for 5 s after it lifted (yes at 4.8 s, no at 5.3 s)");
            check(r.w.eventCount() == 0 && r.w.chunks.size() == 0 && r.w.snap() == Wreck.Snap.NONE, "bounded: " + r.w.eventCount() + " events and " + r.w.chunks.size() + " pieces are kept once the hit is over");
            // another world, and a clock that went back
            Rig again = new Rig(flat, os[0]);
            for (int i = 0; i < 40; i++) again.step(1 / 30.0, i % 4 == 0 ? 0.04 : 0, all(1), tornado(1, 1000, 1, 90, 62, 900));
            int before = again.w.chunks.size();
            again.w.frame(frame(again.t + 0.03), world(0, true, false), again.objs);
            int afterHello = again.w.chunks.size() + again.w.eventCount();
            Rig back = new Rig(flat, os[0]);
            for (int i = 0; i < 40; i++) back.step(1 / 30.0, i % 4 == 0 ? 0.04 : 0, all(1), tornado(1, 1000, 1, 90, 62, 900));
            back.w.frame(frame(back.t - 5), flat, back.objs);
            check(before > 0 && afterHello == 0 && back.w.chunks.size() + back.w.eventCount() == 0 && !again.w.snap().airborne(1),
                    "cleared: " + before + " pieces in the air are gone with a new hello, and when the simulation's clock goes back (a reseed); a new game session has its own ViewerState and Wreck");
        }

        // ---- rubble piles
        {
            final Model.Hello hill = world(0.06, true, false);
            Model.Obj house = obj(hill, 1, "house", "cottage", 1200, 62, 52, 1, 0), office = obj(hill, 2, "office", "", 1700, 86, 124, 8, 0);
            boolean counts = Rubble.count(house, 0.29) == 0 && Rubble.count(house.withDamage(0), 7 / 24.0) == 0 && Rubble.count(house, 8 / 24.0) == Math.round(8 / 24.0 * (2 + 62 / 20.0))
                    && Rubble.count(house, 1) == 5 && Rubble.count(office, 1) == 6 && Rubble.count(obj(hill, 9, "factory", "", 500, 400, 84, 1, 0), 1) == 12
                    && Rubble.count(obj(hill, 8, "streetlamp", "", 500, 8, 34, 1, 0), 1) == 0;
            check(counts, "piles: none below step 8 (0.33; 0.30 on the ladder), min(12, round(d (2 + w/20))) from there: a 62 px house 3 at 0.33 and 5 at 1, an 86 px office 6, a 400 px factory 12, a lamp none");
            Scene sc = new Scene(hill, new Model.Obj[]{house.withDamage(1), office.withDamage(0.75)}, new Model.Ground(), new Lit(0.45, 0.2), 300);
            int[] a = layer(sc, 900, 2200, 2, g -> Rubble.paint(g, sc, null, 900, 2200, false)), b = layer(sc, 900, 2200, 2, g -> Rubble.paint(g, sc, Wreck.Snap.NONE, 900, 2200, false));
            int painted = count(a), floating = floating(a, sc, 900, 2200, 2);
            check(painted > 300 && diff(a, b) == 0 && floating == 0, "piles on a slope: " + painted + " px painted, the same twice, " + floating + " px not joined to the ground (a flood fill from the terrain line)");
            // at the table's reach, on the ground
            double reachOk = 0;
            boolean within = true;
            for (Model.Obj o : sc.objects) for (int j = 0; j < Rubble.count(o, o.damage); j++) {
                double d = Math.abs(Rubble.spotX(o, j) - o.x) - o.w / 2;
                within &= d >= Rubble.GAP && d <= Rubble.reach(o);
                reachOk = Math.max(reachOk, d);
            }
            int[] outside = layer(sc, 900, 2200, 2, g -> Rubble.paint(g, sc, null, 900, 2200, false));
            int beyond = 0;
            for (int y = 0; y < LH; y++) for (int x = 0; x < (2200 - 900) * 2; x++) {
                if ((outside[y * LW + x] >>> 24) == 0) continue;
                double wx = 900 + x / 2.0;
                boolean near = false;
                for (Model.Obj o : sc.objects) near |= Math.abs(wx - o.x) <= o.w / 2 + Rubble.reach(o);
                if (!near) beyond++;
            }
            check(within && beyond == 0, String.format(Locale.ROOT, "every pile stands within the reach the bake's dirty range is widened by (farthest %.0f px from a wall; %d px painted beyond)", reachOk, beyond));
            // part-built while the building has pieces in the air
            Wreck.Snap air = new Wreck.Snap(new Chunks.Draw[0], new long[]{1}, new double[0], new float[0], 0);
            int[] part = layer(sc, 900, 2200, 2, g -> Rubble.paint(g, sc, air, 900, 1500, false)), full = layer(sc, 900, 2200, 2, g -> Rubble.paint(g, sc, null, 900, 1500, false));
            check(count(part) > 0 && count(part) < count(full) * 0.6 && floating(part, sc, 900, 2200, 2) == 0,
                    "airborne: the house's piles are drawn part-built (" + count(part) + " px against " + count(full) + " in full), still on the ground");
            // back row: behind the terrain and the front row (painted in the back pass only), hazed
            Model.Obj backHouse = obj(hill, 3, "house", "farmhouse", 1400, 84, 62, 2, 1).withDamage(1);
            Scene sb = new Scene(hill, new Model.Obj[]{backHouse}, new Model.Ground(), new Lit(0.45, 0.2), 300);
            int[] inFront = layer(sb, 900, 2200, 2, g -> Rubble.paint(g, sb, null, 900, 2200, false)), inBack = layer(sb, 900, 2200, 2, g -> Rubble.paint(g, sb, null, 900, 2200, true));
            check(count(inFront) == 0 && count(inBack) > 100, "a back-row house's piles are painted with the back row (before the terrain and the front row: " + count(inBack) + " px), none in the front pass");
        }

        // ---- road wear
        {
            final Model.Hello hill = world(0.04, true, false);
            Model.Obj wrecked = obj(hill, 1, "house", "cottage", 1200, 62, 52, 1, 0).withDamage(1), whole = obj(hill, 2, "house", "cabin", 1800, 58, 42, 1, 0).withDamage(0.25);
            Scene sc = new Scene(hill, new Model.Obj[]{wrecked, whole}, new Model.Ground(), new Lit(0.45, 0.2), 300);
            int[] wear = layer(sc, 900, 2200, 2, g -> RoadWear.paint(g, sc, 900, 2200, 2));
            int nearWreck = 0, nearWhole = 0, off = 0, far = 0;
            for (int y = 0; y < LH; y++) for (int x = 0; x < (2200 - 900) * 2; x++) {
                if ((wear[y * LW + x] >>> 24) == 0) continue;
                double wx = 900 + (x + 0.5) / 2.0, wy = LY0 + (y + 0.5) / 2.0, gy = hill.terrain.at(wx);
                if (Math.abs(wx - 1800) < 58 / 2.0 + 60) nearWhole++;
                if (Math.abs(wx - 1200) <= 62 / 2.0 + RoadWear.REACH + 0.5) nearWreck++; else far++;
                if (wy < gy - RoadWear.LIFT - 0.75 || wy > gy + RoadWear.DEPTH + 0.75) off++;
            }
            check(nearWreck > 250 && nearWhole == 0 && far == 0 && off == 0,
                    "road wear: " + nearWreck + " px of the street beside a destroyed house are changed, " + nearWhole + " beside one at damage 0.25, " + far + " further than w/2 + 36 px, "
                            + off + " outside the strip (1.8 px above the terrain line to 5.6 below)");
            Scene hw = new Scene(world(0.04, false, true), sc.objects, new Model.Ground(), new Lit(0.45, 0.2), 300);
            Scene sc2 = new Scene(hill, new Model.Obj[]{wrecked.withDamage(1), whole}, new Model.Ground(), new Lit(0.45, 0.2), 300);
            check(count(layer(hw, 900, 2200, 2, g -> RoadWear.paint(g, hw, 900, 2200, 2))) == 0 && diff(wear, layer(sc2, 900, 2200, 2, g -> RoadWear.paint(g, sc2, 900, 2200, 2))) == 0,
                    "none on a highway; the same wear from the same damage in another scene object (a reload)");
            // the ladder only adds: what is worn at one step is still worn at the next
            boolean grows = true;
            int prev = 0;
            for (int k = 8; k <= 24; k++) {
                final Model.Obj o = wrecked.withDamage(k / 24.0);
                Scene sk = new Scene(hill, new Model.Obj[]{o}, new Model.Ground(), new Lit(0.45, 0.2), 300);
                int n = count(layer(sk, 900, 2200, 2, g -> RoadWear.paint(g, sk, 900, 2200, 2)));
                grows &= n >= prev * 0.97;
                prev = n;
            }
            check(grows && prev > 0, "the wear grows with the damage, step by step (k 8 .. 24), and is a function of the step alone");
            List<double[]> parts = RoadWear.offStreet(hill, 300, 2700);
            check(parts.size() == 2 && parts.get(0)[0] == 300 && parts.get(0)[1] == 600 && parts.get(1)[0] == 2400 && parts.get(1)[1] == 2700 && RoadWear.offStreet(world(0, false, true), 0, 100).size() == 1,
                    "the tornado's scar keeps off a street span (300..2700 across a street 600..2400 -> 300..600 and 2400..2700) and stays on a highway and on open ground");
        }

        // ---- the scene: from the first row to the last landing, in the real renderer
        sceneChecks();
    }

    private void sceneChecks() throws Exception {
        FakeSim fake = new FakeSim(1234L, 0.45, 3840, 1080, null, null, Double.NaN, Double.NaN, "hit");
        ViewerState st = new ViewerState();
        st.acceptLine(fake.hello());
        ParallelRenderer pr = renderer(1);
        Camera cam = new Camera();
        pr.layout(cam, st, 720, 360);
        cam.set(600, 760, 0.9, true);
        int frames = 0, expect = 0, maxAir = 0, peakFrame = -1;
        long rendered = 0;
        boolean done = false, lifted = false;
        double tFirst = Double.NaN, tLift = Double.NaN, tDone = Double.NaN;
        String peakLine = null;
        Map<Long, Double> was = new HashMap<>();
        for (Model.Obj o : st.objects) was.put(o.id, o.damage);
        // OI: nothing floats at any moment of the sequence, and a house that ends as a bare slab is seen leaving
        final Map<Chunks.Chunk, double[]> track = new IdentityHashMap<>();        // x, y, seconds without moving, seconds held
        final Map<Chunks.Chunk, double[]> sweptPieces = new IdentityHashMap<>();  // highest above the foot, furthest from the house, last x, last y, landed (1)
        final Map<Long, Model.Obj> sweptObjs = new java.util.LinkedHashMap<>();
        double hangMax = 0, heldMax = 0, prevT = Double.NaN, nextLook = Double.NaN;
        int dustOff = 0, looks = 0, stills = 0, floatingStills = 0;
        boolean lookedFirst = false, lookedLift = false;
        StringBuilder floatWhat = new StringBuilder();
        for (; frames < 4200 && !done; frames++) {
            String line = fake.step();
            st.acceptLine(line);
            {
                final double dtF = Double.isNaN(prevT) ? 0 : st.cur.t - prevT;
                prevT = st.cur.t;
                // a house that has just been swept off its slab: the pieces this row made are its walls leaving
                for (Model.Obj o : st.objects) {
                    if (!Wreck.wreckable(o) || !BuildingPainter.swept(o) || sweptObjs.containsKey(o.id)) continue;
                    sweptObjs.put(o.id, o);
                    for (Chunks.Chunk c : st.wreck.chunks.list) if (c.obj == o.id && !track.containsKey(c)) sweptPieces.put(c, new double[5]);
                }
                // every piece: in the air only while it moves; at rest only as dust on the ground
                for (Chunks.Chunk c : st.wreck.chunks.list) {
                    double[] p = track.get(c);
                    if (p == null) { track.put(c, new double[]{c.x, c.y, 0, 0}); p = track.get(c); }
                    else {
                        final double moved = Math.hypot(c.x - p[0], c.y - p[1]);
                        if (c.state == Chunks.HELD) { p[3] += dtF; heldMax = Math.max(heldMax, p[3]); }
                        else if (c.state == Chunks.DUST) { if (Math.abs(c.y - (st.hello.terrain.at(c.x) - 1)) > 1) dustOff++; }
                        else { p[2] = moved < 0.05 ? p[2] + dtF : 0; hangMax = Math.max(hangMax, p[2]); }
                        p[0] = c.x; p[1] = c.y;
                    }
                    double[] sp = sweptPieces.get(c);
                    if (sp != null) {
                        Model.Obj o = sweptObjs.get(c.obj);
                        sp[0] = Math.max(sp[0], o.y - c.y); sp[1] = Math.max(sp[1], Math.abs(c.x - o.x)); sp[2] = c.x; sp[3] = c.y;
                        if (c.state == Chunks.DUST) sp[4] = 1;
                    }
                }
                // the still pictures of everything damaged, by FloatTest's rule: at the first piece, at the lift, every 2 s between and after
                final boolean first = !lookedFirst && st.wreck.reached("first"), lift = !lookedLift && st.wreck.reached("lift");
                if (first) { lookedFirst = true; nextLook = st.cur.t; }
                if (lift) lookedLift = true;
                if (lookedFirst && (lift || st.cur.t >= nextLook)) {
                    nextLook = st.cur.t + 2;
                    looks++;
                    for (Model.Obj o : st.objects) {
                        if (!Wreck.wreckable(o) || o.damage <= 0) continue;
                        stills++;
                        String bad = floatsInStill(o, Wreck.litNear(st.cur, o.x));
                        if (bad != null) { floatingStills++; if (floatWhat.length() < 400) floatWhat.append(String.format(Locale.ROOT, " [t=%.1f %s %d k %d: %s]", st.cur.t, o.kind, o.id, Wreck.k(o.damage), bad)); }
                    }
                }
            }
            // which of this frame's rising rows had a grounded tornado in reach (worked out here, not asked of Wreck)
            for (Model.Obj o : st.objects) {
                double w = was.get(o.id);
                if (o.damage <= w) continue;
                was.put(o.id, o.damage);
                if (!o.building || o.kind.equals("streetlamp")) continue;
                for (Model.Tornado t : st.cur.tornadoes)
                    if (t.reach >= 0.995 && Math.abs(o.x - t.x) - o.w / 2 <= 2.2 * t.width + 90) { expect++; break; }
            }
            if (Double.isNaN(tFirst) && st.wreck.reached("first")) tFirst = st.cur.t;
            if (!lifted && st.wreck.reached("lift")) { lifted = true; tLift = st.cur.t; }
            if (st.wreck.chunks.size() > maxAir) { maxAir = st.wreck.chunks.size(); peakFrame = frames; peakLine = line; }
            if (frames % 3 == 0) { small(pr, st, cam); rendered++; }
            done = st.wreck.reached("done");
        }
        tDone = st.cur.t;
        small(pr, st, cam);
        Wreck w = st.wreck;
        {
            // hit:done itself
            looks++;
            for (Model.Obj o : st.objects) {
                if (!Wreck.wreckable(o) || o.damage <= 0) continue;
                stills++;
                String bad = floatsInStill(o, Wreck.litNear(st.cur, o.x));
                if (bad != null) { floatingStills++; if (floatWhat.length() < 400) floatWhat.append(String.format(Locale.ROOT, " [done %s %d k %d: %s]", o.kind, o.id, Wreck.k(o.damage), bad)); }
            }
            check(lookedFirst && lookedLift && looks >= 9 && stills >= 60 && floatingStills == 0,
                    String.format(Locale.ROOT, "nothing floats during the hit: at %d moments (the first piece, every 2 s after, the lift, all down) the %d still pictures of the damaged buildings pass FloatTest's rule (%d with a piece in the air or a slab on nothing)%s",
                            looks, stills, floatingStills, floatWhat));
            check(track.size() == w.chunks.born && hangMax <= 2 / 30.0 + 1e-9 && heldMax <= Chunks.T_HOLD + 1 / 30.0 + 1e-9 && dustOff == 0,
                    String.format(Locale.ROOT, "what is in the air is in flight: of %d pieces followed frame by frame none stood still in the air for longer than %.3f s (two frames allowed: the top of a throw), none was held where it was cut for longer than %.2f s (T_HOLD %.2f + a frame), and %d lay as dust anywhere but on the ground",
                            track.size(), hangMax, heldMax, Chunks.T_HOLD, dustOff));
            // the swept house
            int front = 0, pieces = 0, landed = 0, far = 0, least = Integer.MAX_VALUE;
            double upMin = 1e9, sideMax = 0;
            StringBuilder who = new StringBuilder();
            for (Model.Obj o : sweptObjs.values()) {
                if (o.layer != 1) front++;
                int n = 0;
                for (Map.Entry<Chunks.Chunk, double[]> e : sweptPieces.entrySet()) {
                    if (e.getKey().obj != o.id) continue;
                    double[] sp = e.getValue();
                    n++; pieces++;
                    upMin = Math.min(upMin, sp[0]); sideMax = Math.max(sideMax, sp[1]);
                    if (sp[4] == 1 && Math.abs(sp[3] - (st.hello.terrain.at(sp[2]) - 1)) <= 1) landed++;
                    if (Math.abs(sp[2] - o.x) > o.w / 2 + Rubble.GAP + Rubble.radius(o) + 1e-6 || Math.abs(sp[2] - o.x) < o.w / 2 + Rubble.GAP - 1e-6) far++;
                }
                least = Math.min(least, n);
                Model.Obj now = null;
                for (Model.Obj q : st.objects) if (q.id == o.id) now = q;
                who.append(String.format(Locale.ROOT, "%s%s %s %d: %d pieces, %d piles", who.length() == 0 ? "" : "; ", o.layer == 1 ? "back-row" : "front-row", o.variant.isEmpty() ? o.kind : o.variant, o.id, n, now == null ? 0 : Rubble.count(now, Wreck.level(now))));
                if (now == null || !BuildingPainter.swept(now) || Rubble.count(now, Wreck.level(now)) < 2) least = 0;
            }
            check(front >= 1 && least >= 10 && landed == pieces && far == 0 && upMin >= 60 && sideMax >= 30,
                    String.format(Locale.ROOT, "a house swept off its slab is seen leaving: %d houses end as a bare slab (%d in the front row; %s); the step that takes the walls makes at least %d pieces (10 wanted, 16 allowed), every one is lifted at least %.0f px above the foot of its house (60 wanted), they are carried up to %.0f px from it, and %d of %d come down as dust on the ground at one of its piles (%d elsewhere)",
                            sweptObjs.size(), front, who, least == Integer.MAX_VALUE ? 0 : least, upMin > 1e8 ? 0 : upMin, sideMax, landed, pieces, far));
        }
        int destroyed = 0, small = 0, hitN = 0, piled = 0;
        double towerMax = 0;
        for (Model.Obj o : st.objects) {
            if (!Wreck.wreckable(o) || o.damage <= 0) continue;
            hitN++;
            if (Rubble.has(o)) piled++;
            if (Chunks.towerKind(o.kind)) towerMax = Math.max(towerMax, o.damage);
            else { small++; if (o.damage >= 0.96) destroyed++; }
        }
        check(done && w.rows > 300 && w.matched == expect && w.matched + w.unmatched == w.rows,
                String.format(Locale.ROOT, "scene hit: done after %d frames (first piece t=%.1f, funnel lifted t=%.1f, all down t=%.1f); %d damage rows, %d matched to the tornado (%d expected), %d plain",
                        frames, tFirst, tLift, tDone, w.rows, w.matched, expect, w.unmatched));
        check(w.chunks.born >= 80 && w.chunks.landed == w.chunks.born && w.chunks.size() == 0 && maxAir <= Chunks.MAX_SCENE,
                String.format(Locale.ROOT, "scene hit: %d pieces made in %d cuts, %d lofted, %d landed, at most %d in the air at once, none left; a cut took %.2f ms on the reader thread",
                        w.chunks.born, w.cuts, w.chunks.lofted, w.chunks.landed, maxAir, w.cutNanos / 1e6 / Math.max(1, w.cuts)));
        check(destroyed >= 3 && towerMax > 0.15 && towerMax < 0.96, String.format(Locale.ROOT, "scene hit: %d of %d small buildings destroyed (4-6 s each), the towers stay partly standing (most damaged %.2f)", destroyed, small, towerMax));
        check(w.airSets == w.airClears && w.airSets >= destroyed && pr.shared.airRepaints <= w.airClears && pr.shared.airRepaints >= 1,
                "one event, one extra re-bake: " + hitN + " buildings damaged, the airborne bit went up " + w.airSets + " times and down " + w.airClears + ", and " + pr.shared.airRepaints
                        + " repaints were for that bit alone (" + rendered + " frames rendered)");
        notes.add(String.format(Locale.ROOT, "scene hit: %d buildings with rubble piles; ground tiles painted %d times, %.2f ms each", piled, pr.shared.ground.builds, pr.shared.ground.buildNanos / 1e6 / Math.max(1, pr.shared.ground.builds)));
        pr.shutdown();

        // ---- a reload: the hello of the same world carries the damage, nothing else
        ViewerState re = new ViewerState();
        re.acceptLine(fake.hello());
        ViewerState re2 = new ViewerState();
        re2.acceptLine(fake.hello());
        int kDiff = 0;
        Model.Obj[] live = st.objects.clone();
        for (int i = 0; i < live.length; i++) {
            if (Wreck.k(live[i].damage) != Wreck.k(re.objects[i].damage)) { kDiff++; live[i] = live[i].withDamage(re.objects[i].damage); }
        }
        Lit lit = new Lit(0.45, 0.2);
        Scene a = new Scene(st.hello, live, new Model.Ground(), lit, 300), b = new Scene(re.hello, re.objects, new Model.Ground(), lit, 300), c = new Scene(re2.hello, re2.objects, new Model.Ground(), lit, 300);
        int[] pa = wreckLayer(a, 300, 950), pb = wreckLayer(b, 300, 950), pc = wreckLayer(c, 300, 950);
        check(count(pb) > 500 && diff(pa, pb) == 0 && diff(pb, pc) == 0 && re.wreck.chunks.size() == 0 && re.wreck.snap() == Wreck.Snap.NONE,
                "after a reload: the piles and the road wear are the same (" + count(pb) + " px, " + diff(pa, pb) + " differ from the live session, " + diff(pb, pc) + " between two reloads), no piece and no bit"
                        + (kDiff > 0 ? "  [" + kDiff + " objects stood one step lower in the live session: the last row of less than 0.01 is not sent; compared at the saved damage]" : ""));

        // ---- one band and four, at the peak; the back row; the cost
        FakeSim f2 = new FakeSim(1234L, 0.45, 3840, 1080, null, null, Double.NaN, Double.NaN, "hit");
        ViewerState s2 = new ViewerState();
        s2.acceptLine(f2.hello());
        for (int i = 0; i <= peakFrame; i++) s2.acceptLine(f2.step());
        ParallelRenderer p1 = renderer(1), p4 = renderer(4);
        Camera c2 = new Camera();
        p1.layout(c2, s2, VW, VH);
        int worst = 0, pieces = s2.wreck.chunks.size(), changed = 0;
        for (double[] v : new double[][]{{600, 700, 1.3}, {560, 800, 2.0}, {520.37, 850.21, 3.17}}) {
            c2.set(v[0], v[1], v[2], true);
            int[] w1 = shot(p1, s2, c2), w4 = shot(p4, s2, c2);
            Chunks.hide = true;
            int[] d1 = shot(p1, s2, c2), d4 = shot(p4, s2, c2);
            Chunks.hide = false;
            int n = 0;
            for (int k = 0; k < w1.length; k++) if (w1[k] != w4[k] && d1[k] == d4[k]) n++;
            worst = Math.max(worst, n);
            changed = Math.max(changed, diff(w1, d1));
        }
        check(pieces >= 40 && changed > 200 && worst == 0, "one band and four draw the same pieces: with " + pieces + " in the air they change " + changed + " px of the picture, and " + worst
                + " px differ between 1 band and 4 that do not differ without them (three views)");
        p4.shutdown();
        // the back row: a piece of a back-row building is not drawn below the front roof line
        Wreck.Snap snap = s2.wreck.snap();
        Model.Obj front = null;
        for (Model.Obj o : s2.objects) if (o.building && o.layer != 1 && o.kind.equals("church")) front = o;
        boolean rule = false;
        if (front != null && snap.draws.length > 0) {
            double top = snap.frontTop(front.x);
            Chunks.Chunk ch = new Chunks.Chunk();
            ch.sw = 12; ch.sh = 12; ch.px = new int[144];
            java.util.Arrays.fill(ch.px, 0xFFFF00FF);
            ch.src = 1; ch.half = 9; ch.back = true; ch.state = Chunks.LOFT;
            int below = pixelsOf(ch, front.x, top + 20, snap, s2), above = pixelsOf(ch, front.x, top - 20, snap, s2);
            ch.back = false;
            int frontPiece = pixelsOf(ch, front.x, top + 20, snap, s2);
            rule = Math.abs(top - (front.y - front.h - 8)) < 1e-3 && below == 0 && above > 80 && frontPiece > 80;
            check(rule, String.format(Locale.ROOT, "back row: over the church the front roof line is its top + 8 px (y %.0f); a back-row piece 20 px below it draws %d px, 20 px above it %d, a front-row piece below it %d",
                    top, below, above, frontPiece));
        } else check(false, "back row: no front-row church or no piece in the air at the peak");
        // the cost of the live pass with 160 pieces in view
        {
            Chunks.Draw[] many = new Chunks.Draw[160];
            Chunks.Draw[] have = snap.draws;
            java.util.Random r = new java.util.Random(5);
            for (int i = 0; i < many.length; i++) {
                Chunks.Draw src = have[i % have.length];
                Chunks.Chunk ch = new Chunks.Chunk();
                ch.px = src.px; ch.sw = src.sw; ch.sh = src.sh; ch.src = src.src; ch.half = src.half; ch.state = Chunks.LOFT;
                ch.x = 300 + r.nextDouble() * 1000; ch.y = 400 + r.nextDouble() * 450; ch.rot = r.nextDouble() * 6; ch.spin = 3;
                many[i] = new Chunks.Draw(ch);
            }
            Wreck.Snap big = new Wreck.Snap(many, new long[0], new double[0], new float[0], 0);
            c2.set(800, 620, 1.2, true);
            Camera.View view = c2.view(VW, VH, 1, 0, 0);
            BufferedImage img = new BufferedImage(VW, VH, BufferedImage.TYPE_INT_RGB);
            Direct d = new Direct();
            d.attach(img);
            d.clip(0, 0, VW, VH);
            Graphics2D g = img.createGraphics();
            g.setTransform(view.worldTx());
            double best = 1e9;
            for (int rep = 0; rep < 15; rep++) {
                long n0 = System.nanoTime();
                for (int k = 0; k < 200; k++) Chunks.paint(g, big, view, lit, d, view.wy0, view.wy1, 0.01);
                best = Math.min(best, (System.nanoTime() - n0) / 1e6 / 200);
            }
            g.dispose();
            int inView = 0;
            for (Chunks.Draw q : many) if (q.x > view.wx0 && q.x < view.wx1 && q.y > view.wy0 && q.y < view.wy1) inView++;
            double area = 0;
            for (Chunks.Draw q : many) area += q.sw * q.sh * (view.ws / q.src) * (view.ws / q.src);
            notes.add(String.format(Locale.ROOT, "cost: the 160 pieces cover %.0f device px together (%.0f each), %.1f ns a pixel", area, area / 160, best * 1e6 / area));
            // (the plan's target is 0.4 ms; the bar here is twice that, so that a busy machine does not fail the suite: the figure is what to read)
            check(inView == 160 && best <= 0.8, String.format(Locale.ROOT, "cost: 160 pieces in view are drawn in %.3f ms a frame at zoom 1.2, one thread (target 0.4, fails above 0.8; best of 15 runs of 200)", best));
        }
        p1.shutdown();
        check(new Main.Until("hit:done").kind.equals("hit") && FakeSim.knownScene("hit") && java.util.Arrays.asList(FakeSim.SCENES).contains("hit"),
                "--fake-scene hit is listed and --until hit:first | hit:lift | hit:done are understood");
    }

    // ------------------------------------------------------------------ pictures of layers

    private static final int VW = 1400, VH = 700;
    /** A strip of world drawn onto a transparent image: x from the given left edge, y from {@link #LY0}. */
    private static final int LW = 2700, LH = 520;
    private static final double LY0 = 720;

    private interface Painter { void paint(Graphics2D g); }

    private static int[] layer(Scene sc, double x0, double x1, double scale, Painter p) {
        BufferedImage img = new BufferedImage(LW, LH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(scale, scale);
        g.translate(-x0, -LY0);
        p.paint(g);
        g.dispose();
        int[] out = new int[LW * LH];
        img.getRGB(0, 0, LW, LH, out, 0, LW);
        return out;
    }

    /**
     * OI: the still picture of one damaged building as the ground tile paints it ({@link Chunks#still}), searched by
     * {@link FloatTest}'s rule. Returns null when nothing floats, else what was found.
     */
    private static String floatsInStill(Model.Obj o, Lit lit) {
        Chunks.Still s = Chunks.still(o, o.damage, lit);
        final double ps = (o.layer == 1 ? 0.9 : 1) * s.scale, gy = (o.y - s.y0) * s.scale;
        final int band = (int) Math.floor(gy - 2 * ps), above = (int) Math.floor(gy - FloatTest.VIS_ABOVE * ps);
        FloatTest.Scan c = FloatTest.scan(s.img, band, above);
        int over = FloatTest.overhangs(s.img, band - (int) Math.round(FloatTest.HEAP_ZONE * ps), (int) Math.round(FloatTest.OVER_REACH * ps), (int) Math.round(FloatTest.CENSUS_MIN * ps * ps));
        if (c.clean() && over == 0) return null;
        return c.structural + " structural, " + c.visible + " visible, " + over + " standing on nothing";
    }

    /** The piles of both rows and the road wear of a scene, alone on a transparent strip (scale 2). */
    private static int[] wreckLayer(Scene sc, double x0, double x1) {
        return layer(sc, x0, x1, 2, g -> {
            Rubble.paint(g, sc, null, x0, x1, true);
            RoadWear.paint(g, sc, x0, x1, 2);
            Rubble.paint(g, sc, null, x0, x1, false);
        });
    }

    private static int count(int[] px) {
        int n = 0;
        for (int v : px) if ((v >>> 24) >= 40) n++;
        return n;
    }

    private static int diff(int[] a, int[] b) {
        int n = 0;
        for (int k = 0; k < a.length; k++) if (a[k] != b[k]) n++;
        return n;
    }

    /**
     * Rule F for the piles: the pixels (alpha 40 or more) that a flood fill from the terrain line does not reach
     * (8-connected). The ground itself is not in the picture, so the fill starts from every painted pixel within 1.5 px
     * of the line.
     */
    private static int floating(int[] px, Scene sc, double x0, double x1, double scale) {
        boolean[] solid = new boolean[px.length], seen = new boolean[px.length];
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (int y = 0; y < LH; y++) for (int x = 0; x < LW; x++) {
            int i = y * LW + x;
            solid[i] = (px[i] >>> 24) >= 40;
            if (!solid[i]) continue;
            double wx = x0 + (x + 0.5) / scale, wy = LY0 + (y + 0.5) / scale;
            if (wy >= sc.terrain.at(wx) - 1.5) { seen[i] = true; q.add(i); }
        }
        while (!q.isEmpty()) {
            int i = q.poll(), x = i % LW, y = i / LW;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= LW || yy >= LH) continue;
                int j = yy * LW + xx;
                if (solid[j] && !seen[j]) { seen[j] = true; q.add(j); }
            }
        }
        int n = 0;
        for (int i = 0; i < px.length; i++) if (solid[i] && !seen[i]) n++;
        return n;
    }

    private static ParallelRenderer renderer(int bands) {
        ParallelRenderer pr = new ParallelRenderer(bands);
        pr.shared.setAsync(false);
        DebugWorlds.style(pr.primary());
        pr.primary().showDrainCard = false;
        pr.primary().showDrains = false;
        pr.primary().showLabels = false;
        return pr;
    }

    private static int[] shot(ParallelRenderer pr, ViewerState st, Camera cam) {
        BufferedImage img = new BufferedImage(VW, VH, BufferedImage.TYPE_INT_RGB);
        double t;
        synchronized (st) { t = st.cur.t; }
        pr.render(img, 1, 0, 0, VW, VH, st, cam, -1, t, "test");
        int[] out = new int[VW * VH];
        img.getRGB(0, 0, VW, VH, out, 0, VW);
        return out;
    }

    /** A small picture (the scene is drawn, the pixels are not looked at): the bake counters are what is wanted. */
    private static void small(ParallelRenderer pr, ViewerState st, Camera cam) {
        BufferedImage img = new BufferedImage(720, 360, BufferedImage.TYPE_INT_RGB);
        pr.render(img, 1, 0, 0, 720, 360, st, cam, -1, st.cur.t, "test");
    }

    /** How many pixels one piece at world (x, y) changes on a black canvas (the real draw call, with the scene's roof line). */
    private static int pixelsOf(Chunks.Chunk ch, double x, double y, Wreck.Snap scene, ViewerState st) {
        ch.x = x; ch.y = y;
        float[] top = new float[(int) Math.ceil(st.hello.w / 8.0) + 1];
        for (int i = 0; i < top.length; i++) top[i] = (float) scene.frontTop(i * 8.0);
        Wreck.Snap one = new Wreck.Snap(new Chunks.Draw[]{new Chunks.Draw(ch)}, new long[0], new double[0], top, 0);
        Camera cam = new Camera();
        cam.setWorld(st.hello.w, st.hello.h);
        cam.setViewport(0, 0, 400, 300);
        cam.set(x, y, 2, true);
        Camera.View v = cam.view(400, 300, 1, 0, 0);
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Direct d = new Direct();
        d.attach(img);
        d.clip(0, 0, 400, 300);
        Graphics2D g = img.createGraphics();
        g.setTransform(v.worldTx());
        Chunks.paint(g, one, v, new Lit(0.45, 0), d, v.wy0, v.wy1, 0);
        g.dispose();
        int n = 0;
        for (int yy = 0; yy < 300; yy++) for (int xx = 0; xx < 400; xx++) if ((img.getRGB(xx, yy) & 0xFFFFFF) != 0) n++;
        return n;
    }

    // ------------------------------------------------------------------ evidence pictures (OW2, OW3)

    /** {@code precip.WreckTest pictures DIR}: the cut of one step (OW2), and five frames each of four buildings taking the same hit (OW3). */
    static void pictures(File dir) throws Exception {
        dir.mkdirs();
        Advanced.publish(Advanced.DEFAULT);
        Model.Hello flat = world(0, true, false);
        Lit lit = new Lit(0.45, 0.3);
        // ---- OW2: before | after | the pieces of that step where they were cut | after + pieces (= before, where a piece was cut)
        Object[][] pairs = {{"pair_cottage_k8_k9.png", obj(flat, 1, "house", "cottage", 1000, 62, 52, 1, 0), 8, 9}, {"pair_office_k15_k16.png", obj(flat, 2, "office", "", 1300, 86, 124, 8, 0), 15, 16},
                {"pair_barn_k10_k11.png", obj(flat, 3, "barn", "", 1600, 96, 70, 1, 0), 10, 11}};
        for (Object[] pr : pairs) {
            Model.Obj o = (Model.Obj) pr[1];
            Chunks.Still a = Chunks.still(o, (Integer) pr[2] / 24.0, lit), b = Chunks.still(o, (Integer) pr[3] / 24.0, lit);
            List<Chunks.Chunk> all = new Chunks().cut(o, a, b, 10_000), eight = new Chunks().cut(o, a, b, Chunks.MAX_STEP);
            int z = a.scale >= 2 ? 3 : 4, pw = a.w * z, ph = a.h * z;
            BufferedImage out = new BufferedImage(pw * 5 + 60, ph + 34, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setColor(new Color(150, 176, 204));
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            String[] cap = {"still, k " + pr[2], "still, k " + pr[3], "what vanished: " + all.size() + " cells of 12 px or more", "the " + eight.size() + " largest fly (8 a step)", "k " + pr[3] + " + all pieces = k " + pr[2]};
            for (int p = 0; p < 5; p++) {
                int ox = 10 + p * (pw + 10);
                g.setColor(new Color(132, 160, 190));
                g.fillRect(ox, 28, pw, ph);
                if (p == 0) g.drawImage(a.img, ox, 28, pw, ph, null);
                if (p == 1 || p == 4) g.drawImage(b.img, ox, 28, pw, ph, null);
                if (p >= 2) for (Chunks.Chunk c : p == 3 ? eight : all) {
                    BufferedImage ci = new BufferedImage(c.sw, c.sh, BufferedImage.TYPE_INT_ARGB_PRE);
                    ((java.awt.image.DataBufferInt) ci.getRaster().getDataBuffer()).getData();
                    System.arraycopy(c.px, 0, ((java.awt.image.DataBufferInt) ci.getRaster().getDataBuffer()).getData(), 0, c.px.length);
                    double cx = (c.x - a.x0) * a.scale, cy = (c.y - a.y0) * a.scale;
                    g.drawImage(ci, ox + (int) Math.round((cx - c.sw * 0.5) * z), 28 + (int) Math.round((cy - c.sh * 0.5) * z), c.sw * z, c.sh * z, null);
                }
                g.setColor(Color.BLACK);
                g.drawString(cap[p], ox + 2, 18);
            }
            g.dispose();
            ImageIO.write(out, "png", new File(dir, (String) pr[0]));
            System.out.println("[wreck-test] written " + pr[0] + ": " + all.size() + " cells, " + eight.size() + " fly");
        }
        // ---- OW3: the scene's cottage, barn, church and office (and a back-row house) at five moments of their own hit
        String[][] who = {{"house", "cottage", "0"}, {"barn", "", "0"}, {"church", "", "0"}, {"office", "", "0"}, {"house", "farmhouse", "1"}, {"apartment", "", "1"}};
        double[] marks = {0.08, 0.35, 0.65, 0.97, 2.0};          // (2.0 = when everything has come down)
        int tw = 520, th = 400;
        BufferedImage[][] tiles = new BufferedImage[who.length][marks.length];
        String[][] caps = new String[who.length][marks.length];
        // (a first run without pictures tells how far each building gets: a tower stops well short of 1, and its frames
        // are taken at the same shares of its own final damage)
        double[] fin = new double[who.length];
        {
            FakeSim dry = new FakeSim(1234L, 0.45, 3840, 1080, null, null, Double.NaN, Double.NaN, "hit");
            ViewerState ds = new ViewerState();
            ds.acceptLine(dry.hello());
            for (int f = 0; f < 4200 && !ds.wreck.reached("done"); f++) ds.acceptLine(dry.step());
            for (int i = 0; i < who.length; i++)
                for (Model.Obj q : ds.objects) if (q.kind.equals(who[i][0]) && q.variant.equals(who[i][1]) && q.layer == Integer.parseInt(who[i][2]) && q.settlement == 1) { fin[i] = q.damage; break; }
        }
        FakeSim fake = new FakeSim(1234L, 0.45, 3840, 1080, null, null, Double.NaN, Double.NaN, "hit");
        ViewerState st = new ViewerState();
        st.acceptLine(fake.hello());
        ParallelRenderer rr = new ParallelRenderer(1);
        rr.shared.setAsync(false);
        DebugWorlds.style(rr.primary());
        rr.primary().showLabels = false;
        int[] next = new int[who.length];
        double[] peak = new double[who.length];
        for (int f = 0; f < 4200; f++) {
            st.acceptLine(fake.step());
            boolean done = st.wreck.reached("done");
            for (int i = 0; i < who.length; i++) {
                Model.Obj o = null;
                for (Model.Obj q : st.objects) if (q.kind.equals(who[i][0]) && q.variant.equals(who[i][1]) && q.layer == Integer.parseInt(who[i][2]) && q.settlement == 1) { o = q; break; }
                if (o == null || next[i] >= marks.length) continue;
                peak[i] = o.damage;
                boolean due = marks[next[i]] > 1 ? done : o.damage >= marks[next[i]] * fin[i] - 1e-9 || done;
                // (a tower never gets to 0.97: its later frames are taken at the share of its own final damage, once known)
                if (!due) continue;
                Camera cam = new Camera();
                rr.layout(cam, st, tw, th);
                double zoom = o.h > 100 ? 1.9 : 2.6;
                cam.set(o.x, o.y - th / zoom * 0.36, zoom, true);
                BufferedImage img = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
                rr.render(img, 1, 0, 0, tw, th, st, cam, -1, st.cur.t, "pictures");
                tiles[i][next[i]] = img;
                caps[i][next[i]] = String.format(Locale.ROOT, "%s%s t=%.1f damage %.2f (k %d), %d pieces up", who[i][1].isEmpty() ? who[i][0] : who[i][1], who[i][2].equals("1") ? " (back row)" : "",
                        st.cur.t, o.damage, Wreck.k(o.damage), st.wreck.flying(o.id));
                next[i]++;
                if (done) i--;            // (take the remaining marks of this building now: nothing more will happen)
            }
            if (done) break;
        }
        // ---- OW7: the street after the hit as the live session shows it, and the same world loaded again (its hello carries
        // the damage and nothing else: no tornado log, no piece, no bit)
        ViewerState re = new ViewerState();
        re.acceptLine(fake.hello());
        re.acceptLine(fake.step());
        st.acceptLine(fake.step());
        for (double[] v : new double[][]{{640, 760, 1.0}, {540, 850, 2.0}}) {
            for (int which = 0; which < 2; which++) {
                ViewerState s = which == 0 ? st : re;
                Camera cam = new Camera();
                rr.layout(cam, s, 1500, 620);
                cam.set(v[0], v[1], v[2], true);
                BufferedImage img = new BufferedImage(1500, 620, BufferedImage.TYPE_INT_RGB);
                rr.render(img, 1, 0, 0, 1500, 620, s, cam, -1, s.cur.t, "pictures");
                String name = String.format(Locale.ROOT, "town_%s_zoom%.0f.png", which == 0 ? "after_hit_live" : "after_reload", v[2]);
                ImageIO.write(img, "png", new File(dir, name));
                System.out.println("[wreck-test] written " + name);
            }
        }
        rr.shutdown();
        for (int i = 0; i < who.length; i++) {
            BufferedImage out = new BufferedImage(tw * marks.length + 6 * (marks.length + 1), th + 34, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.setColor(Color.BLACK);
            for (int m = 0; m < marks.length; m++) {
                int ox = 6 + m * (tw + 6);
                if (tiles[i][m] != null) g.drawImage(tiles[i][m], ox, 28, null);
                g.drawString(caps[i][m] == null ? "(not reached)" : caps[i][m], ox + 2, 18);
            }
            g.dispose();
            String name = "ow3_" + (who[i][1].isEmpty() ? who[i][0] : who[i][1]) + (who[i][2].equals("1") ? "_backrow" : "") + ".png";
            ImageIO.write(out, "png", new File(dir, name));
            System.out.println(String.format(Locale.ROOT, "[wreck-test] written %s (final damage %.2f)", name, peak[i]));
        }
    }
}
