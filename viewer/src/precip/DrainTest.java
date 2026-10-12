package precip;

import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.SwingUtilities;

/**
 * The checks of protocol v2.4 in the viewer (PLAN Phase L, step LV8), run by {@code --selftest-ui} and
 * {@code --selftest} after the other three: the new keys and their absence, legacy puddles, the standing water
 * (never above its level or outside its span, the same in one band and in four, eased), the inlets, the pipe
 * overlay, the drains card and its buttons, the New simulation choice, the Debug mode's Flood tab, and
 * {@link FakeWater} against the model table's test vector.
 * <p>
 * Opens no window and needs no Python. Settings, saves and pictures go to a throw-away folder in the system temp
 * directory. What it cannot check is said in its last line: the real simulation, and the pixels of the build before
 * this phase (that comparison is made once, by hand, with {@code --replay} on both builds).
 */
final class DrainTest {
    private static final int W = 1400, H = 700;
    private final File root, tmp;
    private final Settings settings;
    private final AppShell app;
    private final List<String> failures = new ArrayList<>();
    private int step;

    private DrainTest(File root, File tmp) {
        this.root = root;
        this.tmp = tmp;
        settings = new Settings(new File(tmp, "config"));
        app = new AppShell(root, settings, new SaveStore(new File(tmp, "saves")));
    }

    static int run() {
        File tmp;
        try {
            tmp = Files.createTempDirectory("precip-draintest").toFile();
        } catch (java.io.IOException ex) {
            System.out.println("[drain-test] cannot create a temp folder: " + ex.getMessage());
            return 1;
        }
        Advanced keepAdv = Advanced.cur;
        DrainTest t = new DrainTest(Main.projectRoot(), tmp);
        try {
            t.go();
        } catch (Throwable ex) {
            ex.printStackTrace();
            t.failures.add("exception: " + ex);
        } finally {
            try {
                edt(() -> { if (t.app.session != null) t.app.leaveNow(); });
            } catch (RuntimeException ignored) { }
            t.app.awaitStops();
            edt(() -> { if (t.app.panel != null) t.app.panel.dispose(); });
            t.app.io.shutdown();
            Advanced.publish(keepAdv);
            deleteTree(tmp);
        }
        if (t.failures.isEmpty()) {
            System.out.println("[drain-test] PASS (" + t.step + " checks)   [not checked here: the real simulation's v2.4 stream; the picture of a v2.3 stream against the build before Phase L]");
            return 0;
        }
        System.out.println("[drain-test] FAIL");
        for (String f : t.failures) System.out.println("[drain-test]   - " + f);
        return 1;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private void check(boolean ok, String what) {
        step++;
        System.out.println(String.format("[drain-test] %2d  %s  %s", step, ok ? "ok  " : "FAIL", what));
        if (!ok) failures.add(what);
    }

    private static void edt(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void go() throws Exception {
        model();
        parsing();
        legacyAndUnknown();
        water();
        drainsAndCard();
        buttonsAndKeys();
        newSimulation();
        floodTab();
    }

    // ------------------------------------------------------------------ FakeWater against the model table

    private static double[] bowl(int n, double... hollows) {
        // flat at y 1000 with cosine hollows {centre, half-width, depth} and a slope given as the last odd value
        double[] t = new double[n];
        for (int i = 0; i < n; i++) {
            double x = i * 8.0, v = 1000;
            for (int k = 0; k + 2 < hollows.length; k += 3) {
                double u = Math.abs(x - hollows[k]) / hollows[k + 1];
                if (u < 1) { double c = Math.cos(u * Math.PI / 2); v += hollows[k + 2] * c * c; }
            }
            t[i] = v;
        }
        return t;
    }

    private void model() {
        // ---- the flat-street test vector: s = 400 px, size 4.7 (7 inlets, cap 41.2), Q = 100 px²/s
        // (Phase N, rewritten in place. Phase L checked this on FakeWater.flatStreet: every street held its water behind a
        // virtual curb 10 px high and "ran over the curb" at 10 px. Protocol v2.4.1 has no curb, so the vector is kept as a
        // fixture: FakeWater.walledPad, a street between two REAL walls 10 px high, is a hollow of 10 px and holds a body whose
        // rim is the wall. The numbers are Phase L's, unchanged; what a street WITHOUT walls does is in FlowTest, the sheet vector.)
        // (the plan's vector is defined at HIT_AREA 1.0: 100 hits/s are 100 px²/s. It is run at that value whatever the mirror
        // says, by giving the street the area itself; the shipped constant has the same check of its own below)
        final double PLAN_HIT_AREA = 1.0, HITS = 100;
        FakeWater w = FakeWater.walledPad(400, 4.7);
        FakeWater.Town tn = w.towns.get(0);
        final double dt = FakeSim.DT;
        double t3 = Double.NaN, tSpill = Double.NaN, d30 = 0;
        for (int k = 1; k <= 30 * 100; k++) {
            w.addToStreet(1, HITS * PLAN_HIT_AREA * dt);
            w.update(dt);
            if (Double.isNaN(t3) && tn.depth >= 3) t3 = k * dt;
            if (Double.isNaN(tSpill) && tn.body != null && tn.body.spill) tSpill = k * dt;
            if (k == 30 * 30) d30 = tn.depth;
        }
        double rise = d30 / 30;
        check(tn.inlets == 7 && Math.abs(tn.cap - 41.24) < 0.05 && Math.abs(rise - 0.1319) / 0.1319 < 0.01 && Math.abs(t3 - 22.7) / 22.7 < 0.01 && Math.abs(tSpill - 75.8) / 75.8 < 0.01
                        && tn.state.equals("overflowing") && Math.abs(tn.depth - 10) < 0.02 && tn.body != null && tn.body.street == 1 && !tn.backed && w.filmStored() > 0,
                String.format(Locale.ROOT, "FakeWater, the model table's flat street as the walled-pad fixture (400 px between real walls 10 px high, size 4.7, Q 100 px²/s): %d inlets, cap %.2f px²/s, "
                        + "the depth rises %.4f px/s (0.132), is 3 px after %.1f s (22.7) and runs over its real rim after %.1f s (75.8), each within 1 %%; then it stays at %.2f px, overflowing, "
                        + "and the excess runs on beyond the wall as film", tn.inlets, tn.cap, rise, t3, tSpill, tn.depth));
        FakeWater w2 = FakeWater.walledPad(400, 4.7);
        FakeWater.Town t2 = w2.towns.get(0);
        w2.fillStreet(1, 5 * 400);
        double tClear = Double.NaN, d10 = 0;
        for (int k = 1; k <= 30 * 80; k++) {
            w2.update(dt);
            if (k == 30 * 10) d10 = t2.depth;
            if (Double.isNaN(tClear) && k > 3 && t2.body == null) tClear = k * dt;
        }
        double fall = (5 - d10) / 10;
        check(Math.abs(fall - 0.1181) / 0.1181 < 0.01 && Math.abs(tClear - 42.3) / 42.3 < 0.01 && t2.state.equals("dry"),
                String.format(Locale.ROOT, "with no rain a 5 px flood (the same fixture) falls %.4f px/s (0.118) and is gone after %.1f s (42), within 1 %%; the street is then %s", fall, tClear, t2.state));
        // ---- the same street under the same 100 hits/s with the constant as shipped (sim/water.py: HIT_AREA 1.15), through the
        // model's own rain entry: d(t) = (hits x HIT_AREA / s - cap / s - SOAK) t while 0 < d < the rim
        // (Phase N, merged in place: session phase-l-viewer-fixes wrote this check on FakeWater.flatStreet and the 10 px curb;
        // it runs on the walled-pad fixture now, whose real walls are those 10 px.)
        FakeWater w3 = FakeWater.walledPad(400, 4.7);
        FakeWater.Town t3n = w3.towns.get(0);
        final double xMid = (t3n.x0 + t3n.x1) / 2, RIM = 10;
        final double riseC = (HITS * FakeWater.HIT_AREA - t3n.cap) / 400 - FakeWater.SOAK, t3C = FakeWater.FLOOD_DEPTH / riseC, tSpillC = RIM / riseC;
        double t3s = Double.NaN, tSpillS = Double.NaN, d15 = 0;
        for (int k = 1; k <= 30 * 80; k++) {
            w3.rain(xMid, HITS * dt);
            w3.update(dt);
            if (Double.isNaN(t3s) && t3n.depth >= 3) t3s = k * dt;
            if (Double.isNaN(tSpillS) && t3n.body != null && t3n.body.spill) tSpillS = k * dt;
            if (k == 30 * 15) d15 = t3n.depth;
        }
        double riseS = d15 / 15;
        check(FakeWater.HIT_AREA == 1.15 && Math.abs(riseS - riseC) / riseC < 0.01 && Math.abs(t3s - t3C) / t3C < 0.01 && Math.abs(tSpillS - tSpillC) / tSpillC < 0.01
                        && Math.abs(w3.totalIn - HITS * FakeWater.HIT_AREA * 80) < 1e-6 * w3.totalIn && t3n.state.equals("overflowing"),
                String.format(Locale.ROOT, "the same street with the constant as shipped (HIT_AREA %.2f, the mirror of sim/water.py; 100 hits/s are %.0f px²/s): the depth rises %.4f px/s (%.4f), "
                        + "is 3 px after %.1f s (%.1f) and runs over its rim after %.1f s (%.1f), each within 1 %% of the closed form; it floods %.1f s sooner than the plan's vector",
                        FakeWater.HIT_AREA, HITS * FakeWater.HIT_AREA, riseS, riseC, t3s, t3C, tSpillS, tSpillC, t3 - t3s));

        // ---- the seeded sizes, classes, inlets, capacity
        double u1 = FakeWater.unit("1234:1:drain"), u2 = FakeWater.unit("1234:2:drain-random");
        double sv = FakeWater.sizeFor("normal", 1234, 1, "village"), sc = FakeWater.sizeFor("normal", 1234, 2, "city"), sr = FakeWater.sizeFor("random", 1234, 2, "city");
        boolean ranges = true;
        for (long id = 1; id <= 40; id++) for (String kind : new String[]{"hamlet", "village", "town", "city"}) {
            double[] r = FakeWater.kindRange(kind);
            double s = FakeWater.sizeFor("normal", 77, id, kind), q = FakeWater.sizeFor("random", 77, id, kind);
            if (s < r[0] || s > r[1] || q < 2.5 || q > 10 || s != FakeWater.sizeFor("normal", 77, id, kind)) ranges = false;
        }
        check(Math.abs(u1 - 0.1041197838854661) < 1e-12 && Math.abs(u2 - 0.4055236223652138) < 1e-12 && Math.abs(sv - (3.0 + u1 * 2.5)) < 1e-9
                        && Math.abs(sc - (4.0 + 0.9481967656729677 * 4.0)) < 1e-9 && Math.abs(sr - (2.5 + u2 * 7.5)) < 1e-9 && ranges
                        && FakeWater.sizeFor("small", 1, 1, "city") == 3.0 && FakeWater.sizeFor("large", 1, 1, "hamlet") == 8.2
                        && FakeWater.classOf(3.75).equals("small") && FakeWater.classOf(3.76).equals("normal") && FakeWater.classOf(6.19).equals("normal") && FakeWater.classOf(6.2).equals("large")
                        && FakeWater.inletCount(0, 400) == 7 && FakeWater.inletCount(0, 20) == 1 && Math.abs(FakeWater.capOf(8, 4.7) - 8 * 0.2667 * 4.7 * 4.7) < 1e-9,
                String.format(Locale.ROOT, "drain sizes come from the seed, the settlement id and its kind as the model table says (crc32 as Python's zlib: u = %.6f for \"1234:1:drain\"; "
                        + "village %.2f px, city %.2f px, random %.2f px), stay in the kind's range for 160 settlements, and the class borders are 3.76 and 6.2", u1, sv, sc, sr));

        // ---- natural pools: no width cap, spill into the next hollow, conservation; then two hollows that merge
        double[] slope = bowl(200, 400, 150, 14, 760, 120, 10);
        for (int i = 0; i < slope.length; i++) slope[i] += 0.012 * i * 8;                 // the ground falls to the right: each hollow spills right
        FakeWater n = new FakeWater(slope, 8, 199 * 8, 5, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), "normal");
        double widest = 0, firstSpill = Double.NaN;
        long idA = 0;
        int most = 0;
        for (int k = 1; k <= 30 * 120; k++) {
            n.rainEverywhere(4.3, dt);
            n.update(dt);
            most = Math.max(most, n.pools.size());
            for (FakeWater.Pool p : n.pools) {
                if (p.id == 0) continue;
                if (idA == 0) idA = p.id;
                if (p.id == idA) widest = Math.max(widest, p.x1 - p.x0);
                if (p.id == idA && p.full && Double.isNaN(firstSpill)) firstSpill = k * dt;
            }
        }
        double balance = Math.abs(n.totalIn - n.stored() - n.totalSoak - n.totalDrain - n.totalLost) / n.totalIn;
        boolean rest = true;
        for (FakeWater.Pool p : n.pools) if (p.id != 0 && (Math.abs(n.at(p.x0) - p.level) > 0.05 || Math.abs(n.at(p.x1) - p.level) > 0.05 || n.at(p.xDeep) < p.level)) rest = false;
        check(n.basinCount() == 2 && widest > 140 && !Double.isNaN(firstSpill) && most == 2 && balance < 0.001 && rest,
                String.format(Locale.ROOT, "a hollow fills with no width cap: its pool grows to %.0f px wide (a puddle stopped at 140), is full after %.0f s and spills into the next hollow; "
                        + "both ends of every pool lie where its level meets the ground; rain in = stored + soaked + lost within %.3f %%", widest, firstSpill, balance * 100));
        FakeWater mg = new FakeWater(bowl(200, 500, 420, 30, 380, 110, 12, 640, 110, 12), 8, 199 * 8, 5, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), "normal");
        int before = mg.basinCount(), bodies = 0, after = 0;
        long big = 0, kept = -1;
        for (int k = 1; k <= 30 * 240 && after != 1; k++) {
            mg.rainEverywhere(k < 30 * 40 ? 6 : 3, dt);
            mg.rain(380, 6 * dt);                                                         // a little more on the left one: it is the larger when they meet
            mg.update(dt);
            int live = 0;
            for (FakeWater.Pool p : mg.pools) if (p.id != 0) live++;
            if (live == 2) {
                bodies = 2;
                FakeWater.Pool a = mg.pools.get(0), b = mg.pools.get(1);
                big = a.vol + a.pend >= b.vol + b.pend ? a.id : b.id;
            }
            if (bodies == 2 && live == 1) { after = 1; kept = mg.pools.get(0).id; }
        }
        check(before == 2 && bodies == 2 && after == 1 && kept == big && mg.pools.get(0).basins.size() == 2,
                "two hollows in one bowl fill to the rim between them and become one body, which keeps the id of the larger (" + kept + ")");
        double[] flat = new double[200];
        java.util.Arrays.fill(flat, 1000);
        FakeWater fw = new FakeWater(flat, 8, 199 * 8, 5, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), "normal");
        for (int k = 0; k < 300; k++) { fw.rainEverywhere(8, dt); fw.update(dt); }
        // (Phase N, rewritten in place: Phase L lost the rain of a flat world at once, "stored() == 0". Now it is a film on the
        // ground that runs off; there is still no body.)
        double wetRain = fw.filmDepth(fw.cellCount() / 2);
        boolean noBody = fw.basinCount() == 0 && fw.pools.isEmpty();
        for (int k = 0; k < 30 * 120; k++) fw.update(dt);
        double wetLater = fw.filmDepth(fw.cellCount() / 2);
        check(noBody && fw.pools.isEmpty() && wetRain > 0.3 && wetLater < wetRain * 0.75,
                String.format(Locale.ROOT, "a flat world has no hollow: it holds no standing water however hard it rains; the rain is a film on the ground (%.2f px after 10 s of rain) "
                        + "that runs off (%.2f px two minutes later)", wetRain, wetLater));
    }

    // ------------------------------------------------------------------ the wire

    private static final String HELLO_24 = "{\"type\":\"hello\",\"version\":2,\"seed\":5,\"world\":{\"w\":1600,\"h\":900,\"ground_y\":820,\"terrain\":{\"step\":8,\"y\":[TERRAIN]}},"
            + "\"water\":{\"model\":1,\"basin_min_depth\":1.0},"
            + "\"drains\":{\"mode\":\"normal\",\"inlet_spacing\":60.0,\"curb\":10.0,\"flood_depth\":3.0,\"size_min\":1.5,\"size_max\":12.0,\"classes\":{\"small\":3.0,\"normal\":4.7,\"large\":8.2}},"
            + "\"settlements\":[{\"id\":2,\"kind\":\"town\",\"name\":\"Testford\",\"x0\":800,\"x1\":1280,\"population\":900,\"drain\":{\"size\":4.7,\"class\":\"normal\",\"inlets\":8,\"cap\":47.1}},"
            + "{\"id\":1,\"kind\":\"hamlet\",\"name\":\"Olden\",\"x0\":100,\"x1\":180}],"
            + "\"objects\":[{\"id\":7,\"kind\":\"puddle\",\"x\":620,\"y\":820,\"w\":60,\"h\":6,\"wet\":0.9},{\"id\":8,\"kind\":\"gizmo\",\"x\":500,\"y\":820,\"w\":40,\"h\":40},"
            + "{\"id\":9,\"kind\":\"windsock\",\"x\":560,\"y\":820,\"w\":30,\"h\":50}]}";

    /** The terrain of the hand-written stream: level at y 820, one hollow 14 px deep around x = 400. */
    private static String terrain24() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= 200; i++) {
            double x = i * 8.0, u = Math.abs(x - 400) / 140;
            double v = 820 + (u < 1 ? 14 * Math.pow(Math.cos(u * Math.PI / 2), 2) : 0);
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.ROOT, "%.2f", v));
        }
        return sb.toString();
    }

    private static String frame(int n, String extra) {
        return "{\"type\":\"frame\",\"frame\":" + n + ",\"t\":" + (n / 30.0) + ",\"world\":{\"w\":1600,\"h\":900,\"ground_y\":820,\"surface_temp_c\":12,\"time_of_day\":0.45}" + extra + "}";
    }

    private void parsing() throws Exception {
        String hello = HELLO_24.replace("TERRAIN", terrain24());
        ViewerState st = new ViewerState();
        st.acceptLine(hello);
        st.acceptLine(frame(1, ",\"water\":[{\"id\":9,\"kind\":\"pool\",\"x\":400.0,\"x0\":322.5,\"x1\":477.5,\"y\":828.4,\"depth\":5.6,\"vol\":520.0},"
                + "{\"id\":2,\"kind\":\"street\",\"settlement\":2,\"x\":1010.0,\"x0\":800.0,\"x1\":1280.0,\"y\":815.4,\"depth\":4.6,\"vol\":2208.0,\"spill\":true}],"
                + "\"flood\":[{\"id\":2,\"state\":\"flooded\",\"depth\":4.6,\"inflow\":61.0,\"cap\":47.1},{\"id\":1,\"state\":\"wet\",\"depth\":0.0,\"inflow\":6.2,\"cap\":14.0}],"
                + "\"cars\":[{\"id\":1,\"x\":900,\"y\":820,\"depth\":3.4},{\"id\":2,\"x\":300,\"y\":820}]"));
        Model.Hello h = st.hello;
        Model.Settlement town = h.settlements.get(0), hamlet = h.settlements.get(1);
        Model.Body pool = st.water.size() == 2 ? st.water.get(0) : new Model.Body(), street = st.water.size() == 2 ? st.water.get(1) : new Model.Body();
        Model.Flood f2 = st.floodOf(2), f1 = st.floodOf(1);
        check(h.water != null && h.water.model == 1 && h.water.basinMinDepth == 1.0 && h.drains != null && h.drains.mode.equals("normal") && h.drains.inletSpacing == 60 && h.drains.curb == 10
                        && h.drains.floodDepth == 3 && h.drains.sizeMin == 1.5 && h.drains.sizeMax == 12 && h.drains.classes.get("large") == 8.2 && h.drains.classes.size() == 3
                        && town.drain != null && town.drain.size == 4.7 && town.drain.cls.equals("normal") && town.drain.inlets == 8 && town.drain.cap == 47.1 && hamlet.drain == null
                        && st.drains.size() == 1 && st.drainMode.equals("normal"),
                "hello: water, drains (mode, inlet spacing, curb, flood depth, size range, classes) and settlements[].drain are read; a settlement without a drain has none");
        check(st.water.size() == 2 && pool.id == 9 && pool.kind.equals("pool") && !pool.street() && pool.x0 == 322.5 && pool.x1 == 477.5 && pool.y == 828.4 && pool.depth == 5.6 && pool.vol == 520
                        && !pool.spill && pool.settlement == -1 && street.street() && street.settlement == 2 && street.spill && street.x == 1010
                        && f2 != null && f2.state.equals("flooded") && f2.flooded() && f2.depth == 4.6 && f2.inflow == 61 && f2.cap == 47.1 && f1 != null && !f1.flooded()
                        && st.cur.cars.get(0).depth == 3.4 && st.cur.cars.get(1).depth == 0,
                "frame: water (a pool and a spilling street body), flood (flooded / wet) and cars[].depth are read; a car without depth has 0");
        // ---- a frame without the keys keeps what there is; an empty list empties it; frame.drains replaces one settlement's drain
        st.acceptLine(frame(2, ""));
        boolean kept = st.water.size() == 2 && st.flood.size() == 2 && st.cur.water == null && st.cur.flood == null && st.cur.drains == null;
        st.acceptLine(frame(3, ",\"drains\":{\"mode\":\"custom\",\"settlements\":[{\"id\":2,\"size\":6.5,\"class\":\"large\",\"inlets\":8,\"cap\":90.1}]}"));
        Model.Drain nd = st.drains.get(2L);
        boolean drains = nd != null && nd.size == 6.5 && nd.cls.equals("large") && nd.cap == 90.1 && st.drainMode.equals("custom") && h.settlements.get(0).drain.size == 4.7;
        st.acceptLine(frame(4, ",\"water\":[],\"flood\":[{\"id\":2,\"state\":\"dry\",\"depth\":0,\"inflow\":0,\"cap\":90.1}]"));
        boolean emptied = st.water.isEmpty() && st.flood.size() == 1 && st.floodOf(2).state.equals("dry");
        st.acceptLine(hello);
        check(kept && drains && emptied && st.water.isEmpty() && st.flood.isEmpty() && st.drains.get(2L).size == 4.7 && st.drainMode.equals("normal"),
                "the viewer keeps the last water and flood lists over frames without them, an empty list empties them, frame.drains replaces that settlement's drain and the mode, "
                        + "and a new hello starts dry with the hello's drains again");
        // ---- a stream without any of it (v2.3)
        ViewerState old = new ViewerState();
        old.acceptLine(hello.replaceAll(",\"drain\":\\{[^}]*\\}", "").replaceAll("\"water\":\\{[^}]*\\},", "").replaceAll("\"drains\":\\{.*?\\}\\},", ""));
        old.acceptLine(frame(1, ",\"cars\":[{\"id\":1,\"x\":900,\"y\":820}]"));
        check(old.hello.water == null && old.hello.drains == null && old.hello.settlements.get(0).drain == null && old.hello.settlements.size() == 2 && old.water.isEmpty() && old.flood.isEmpty()
                        && old.drains.isEmpty() && old.drainMode.isEmpty() && old.cur.cars.get(0).depth == 0,
                "a stream without the v2.4 keys: no water model, no drains, no drain on any settlement, empty lists (nothing of v2.4 is drawn for it)");
        // ---- a replay file of the hand-written stream, and the fake's own output read back
        File rf = new File(tmp, "v24.jsonl");
        Files.writeString(rf.toPath(), hello + "\n" + frame(1, ",\"water\":[{\"id\":9,\"kind\":\"pool\",\"x\":400.0,\"x0\":322.5,\"x1\":477.5,\"y\":828.4,\"depth\":5.6,\"vol\":520.0}]") + "\n", StandardCharsets.UTF_8);
        ViewerState rs = new ViewerState();
        for (String line : Files.readAllLines(rf.toPath(), StandardCharsets.UTF_8)) rs.acceptLine(line);
        FakeSim sim = new FakeSim(1234, 0.45, 3840, 1080, null, null, Double.NaN, Double.NaN, "flood");
        ViewerState fs = new ViewerState();
        fs.acceptLine(sim.hello());
        int lists = 0, floods = 0, minGap = 99, minFloodGap = 99, lastW = -99, lastF = -99, maxBodies = 0;
        double worst = 0;
        boolean same = true;
        for (int k = 0; k < 30 * 50; k++) {
            fs.acceptLine(sim.step());
            if (fs.cur.flood != null) { floods++; if (lastF > -99) minFloodGap = Math.min(minFloodGap, k - lastF); lastF = k; }
            if (fs.cur.water == null) continue;
            lists++;
            if (lastW > -99) minGap = Math.min(minGap, k - lastW);
            lastW = k;
            FakeWater fwm = sim.water();
            int n = 0;
            // (Phase N: a street has no body of its own any more unless it is a hollow; a body is a street body when its bed lies on a settlement's span)
            for (FakeWater.Pool p : fwm.pools) if (p.id != 0 && p.vol >= FakeWater.MIN_VOL) {
                n++;
                Model.Body b = body(fs.cur.water, p.id);
                if (b == null || b.street() != (p.street >= 0) || (p.street >= 0 && (b.settlement != p.street || b.id != p.street))) { same = false; continue; }
                worst = Math.max(worst, Math.max(Math.abs(b.y - p.level), Math.max(Math.abs(b.x0 - p.x0), Math.abs(b.x1 - p.x1))));
            }
            if (n != fs.cur.water.size()) same = false;
            maxBodies = Math.max(maxBodies, n);
        }
        long poolIds = 0;
        for (Model.Body b : fs.water) if (!b.street()) poolIds = poolIds == 0 ? b.id : Math.min(poolIds, b.id);
        // (Phase N, rewritten in place: the fake's hello now says curb 0.0 and overflow_depth; the hand-written v2.4 stream above keeps curb 10)
        boolean noCurb = fs.hello.drains != null && fs.hello.drains.curb == 0 && fs.hello.drains.overflowDepth == 8 && fs.hello.flow != null
                && st.hello.drains.overflowDepth == 8 && st.hello.flow == null;
        check(noCurb && rs.water.size() == 1 && rs.hello.water != null && same && worst <= 0.0051 && maxBodies >= 3 && lists > 20 && minGap >= 3 && floods > 5 && minFloodGap >= 6 && poolIds > 3,
                String.format(Locale.ROOT, "a hand-written v2.4 replay file loads; the fake's own stream reads back to the bodies FakeWater holds (%d lists in 50 s, up to %d bodies, "
                        + "largest difference %.4f px = the rounding of the wire); water at most every 3rd frame (closest %d), flood at most every 6th (closest %d); "
                        + "street bodies carry their settlement's id, pools count on from %d", lists, maxBodies, worst, minGap, minFloodGap, poolIds));
        // ---- once everything is dry the flood list is sent one last time and then not again
        FakeSim dry = new FakeSim(1234, 0.45, 3840, 1080, null, null, Double.NaN, Double.NaN, "flood");
        dry.handle("{\"cmd\":\"rain\",\"rate\":0}");
        ViewerState dst = new ViewerState();
        dst.acceptLine(dry.hello());
        int floodKeys = 0, waterKeys = 0;
        for (int k = 0; k < 200; k++) { dst.acceptLine(dry.step()); if (dst.cur.flood != null) floodKeys++; if (dst.cur.water != null) waterKeys++; }
        check(floodKeys == 1 && waterKeys == 1 && dst.flood.size() == 3 && dst.water.isEmpty(),
                "a dry world: the first frame after the hello carries the (empty) water list and one all-dry flood list, and neither is sent again in 200 frames");
    }

    private static Model.Body body(List<Model.Body> l, long id) {
        for (Model.Body b : l) if (b.id == id) return b;
        return null;
    }

    // ------------------------------------------------------------------ pictures

    private static int[] shot(ParallelRenderer pr, ViewerState st, Camera cam) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        double t;
        synchronized (st) { t = st.cur.t; }
        pr.render(img, 1, 0, 0, W, H, st, cam, -1, t, "test");
        int[] out = new int[W * H];
        img.getRGB(0, 0, W, H, out, 0, W);
        return out;
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

    private static int diff(int[] a, int[] b) {
        int n = 0;
        for (int k = 0; k < a.length; k++) if (a[k] != b[k]) n++;
        return n;
    }

    private void legacyAndUnknown() {
        Advanced.publish(Advanced.DEFAULT);
        ParallelRenderer p1 = renderer(1);
        try {
            // ---- an object of a kind this viewer has no picture for is ignored: the frame is the same with and without it
            String hello = HELLO_24.replace("TERRAIN", terrain24());
            ViewerState with = new ViewerState(), without = new ViewerState();
            with.acceptLine(hello);
            with.acceptLine(frame(1, ""));
            without.acceptLine(hello.replaceAll(",\\{\"id\":8,\"kind\":\"gizmo\"[^}]*\\}", "").replaceAll(",\\{\"id\":9,\"kind\":\"windsock\"[^}]*\\}", ""));
            without.acceptLine(frame(1, ""));
            Camera c = new Camera();
            p1.layout(c, with, W, H);
            c.set(560, 700, 1.4, true);
            int[] a = shot(p1, with, c), b = shot(p1, without, c);
            check(with.objects.length == 3 && without.objects.length == 1 && diff(a, b) == 0, "objects of unknown kinds (\"gizmo\", an old save's \"windsock\") are ignored: the frame is pixel for pixel the one without them");

            // ---- legacy puddles: drawn for a stream without hello.water, not drawn with it
            FakeSim sim = new FakeSim(1234, 0.38, 3840, 1080, null, null, Double.NaN, Double.NaN, "cars" + FakeSim.LEGACY);
            ViewerState st = new ViewerState();
            st.acceptLine(sim.hello());
            for (int k = 0; k < 500; k++) st.acceptLine(sim.step());
            Camera pc = new Camera();
            p1.layout(pc, st, W, H);
            Main.applyCam(pc, "obj:puddle,2.5", st);
            Camera.View pv = pc.view(W, H, 1, 0, 0);
            int[] legacy = shot(p1, st, pc);
            boolean none = p1.shared.water.draw.length == 0 && !p1.shared.water.on && p1.shared.drains.isEmpty() && p1.primary().drainRect == null;
            int wet = 0;
            for (Model.Obj o : st.objects) if (o.kind.equals("puddle") && o.wet > 0.05 && o.x > pv.wx0 && o.x < pv.wx1) wet++;
            Model.Water keep = st.hello.water;
            st.hello.water = new Model.Water();              // the same stream, now said to use the water model
            int[] v24 = shot(p1, st, pc);
            st.hello.water = keep;
            int in = 0, out = 0;
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                if (legacy[y * W + x] == v24[y * W + x]) continue;
                double wx = (x - pv.wtx) / pv.ws, wy = (y - pv.wty) / pv.ws;
                boolean hit = false;
                for (Model.Obj o : st.objects) if (o.kind.equals("puddle") && Math.abs(wx - o.x) <= o.w / 2 + 16 && Math.abs(wy - o.y) <= Math.max(3, o.h) + 8) hit = true;
                if (hit) in++; else out++;
            }
            check(st.hello.water == null && none && wet > 0 && in > 150 && out == 0,
                    String.format("a v2.3 stream (the fake's \"cars%s\" world): no water model, no drains, nothing of v2.4 prepared; its %d wet legacy puddle(s) in view are drawn, "
                            + "and with hello.water present those %d pixels are gone and nothing else changes (%d elsewhere)", FakeSim.LEGACY, wet, in, out));
        } finally {
            p1.shutdown();
        }
    }

    /** A flood-scene world advanced {@code seconds}, raining {@code rate} hits/s per bucket for the first {@code rainS} of them. */
    private static ViewerState floodWorld(FakeSim[] simOut, double rate, double rainS, double seconds, String... cmds) {
        FakeSim sim = new FakeSim(1234, 0.45, 3840, 1080, null, null, Double.NaN, Double.NaN, "flood");
        ViewerState st = new ViewerState();
        st.acceptLine(sim.hello());
        sim.handle("{\"cmd\":\"rain\",\"rate\":" + rate + "}");
        for (String c : cmds) sim.handle(c);
        for (int k = 0; k < Math.round(seconds * 30); k++) {
            if (k == Math.round(rainS * 30)) sim.handle("{\"cmd\":\"rain\",\"rate\":0}");
            st.acceptLine(sim.step());
        }
        if (simOut != null) simOut[0] = sim;
        return st;
    }

    private static Model.Body bodyAt(double level, double x0, double x1, double deepX, Model.Terrain t) {
        Model.Body b = new Model.Body();
        b.id = 900;
        b.y = level; b.x0 = x0; b.x1 = x1; b.x = deepX;
        b.depth = t.at(deepX) - level;
        return b;
    }

    private void water() {
        Advanced.publish(Advanced.DEFAULT);
        ParallelRenderer p1 = renderer(1), p4 = renderer(4);
        try {
            // a dry flood-scene world; the bodies are put in by hand so that the level is known exactly
            ViewerState st = floodWorld(null, 0, 0, 2);
            Model.Terrain t = st.hello.terrain;
            double bed = 0, bedX = 520;
            for (double x = 400; x < 660; x += 8) if (t.at(x) > bed) { bed = t.at(x); bedX = x; }
            Camera cam = new Camera();
            p1.layout(cam, st, W, H);
            cam.set(bedX, bed - 60, 4, true);                       // 4 px per world px
            Camera.View v = cam.view(W, H, 1, 0, 0);
            synchronized (st) { st.water = List.of(); }
            int[] dry = shot(p1, st, cam);
            StringBuilder bad = new StringBuilder();
            int sizes = 0;
            double worstTop = 0, worstBed = 0, worstEnd = 0;
            for (double depth : new double[]{0.6, 5, 11.5}) {
                // where a level surface `depth` above the bed meets the ground on each side (found on the terrain itself)
                double level = bed - depth, x0 = bedX, x1 = bedX;
                while (t.at(x0 - 0.25) > level) x0 -= 0.25;
                while (t.at(x1 + 0.25) > level) x1 += 0.25;
                final Model.Body b = bodyAt(level, x0, x1, bedX, t);
                synchronized (st) { st.water = List.of(b); }
                int[] wet = shot(p1, st, cam), wet4 = shot(p4, st, cam);
                if (diff(wet, wet4) != 0) bad.append(String.format(Locale.ROOT, " depth %.1f: 1 band and 4 bands differ in %d pixels;", depth, diff(wet, wet4)));
                int changed = 0, above = 0, beside = 0, under = 0;
                double dy = v.ws * level + v.wty, dx0 = v.ws * x0 + v.wtx, dx1 = v.ws * x1 + v.wtx;
                for (int x = 0; x < W; x++) {
                    int top = -1, low = -1;
                    for (int y = 0; y < H; y++) {
                        if (wet[y * W + x] == dry[y * W + x]) continue;
                        changed++;
                        if (top < 0) top = y;
                        low = y;
                        if (y < Math.floor(dy) - 1) above++;
                        if (x < Math.floor(dx0) - 1 || x > Math.ceil(dx1) + 1) beside++;
                    }
                    if (top < 0) continue;
                    double wx = (x + 0.5 - v.wtx) / v.ws, ground = v.ws * t.at(wx) + v.wty;
                    if (low > ground + 1.5) under++;
                    if (x > dx0 + 6 && x < dx1 - 6) {
                        worstTop = Math.max(worstTop, Math.abs(top - dy));
                        worstBed = Math.max(worstBed, Math.abs(low + 1 - ground));
                    }
                }
                // the shoreline: the first and the last changed column against where the level meets the terrain
                int first = -1, last = -1;
                for (int x = 0; x < W && first < 0; x++) for (int y = 0; y < H; y++) if (wet[y * W + x] != dry[y * W + x]) { first = x; break; }
                for (int x = W - 1; x >= 0 && last < 0; x--) for (int y = 0; y < H; y++) if (wet[y * W + x] != dry[y * W + x]) { last = x; break; }
                // (a film ends in a sliver thinner than a pixel: its last visible column may lie a few pixels inside the true end)
                worstEnd = Math.max(worstEnd, Math.max(dx0 - first, last - dx1));
                if (changed < 40 || above > 0 || beside > 0 || under > 0 || first < dx0 - 1.5 || last > dx1 + 1.5)
                    bad.append(String.format(Locale.ROOT, " depth %.1f: %d px changed, %d above the level, %d beyond the ends, %d columns below the ground;", depth, changed, above, beside, under));
                sizes++;
            }
            check(bad.length() == 0 && sizes == 3 && worstTop <= 1.5 && worstBed <= 1.5,
                    String.format(Locale.ROOT, "a pool at three sizes (0.6, 5 and 11.5 px deep) at 4x: nothing is drawn above its level, beyond x0 / x1 or below the ground; its surface is within "
                            + "%.1f device px of y and its bed within %.1f of the terrain in every column; it never reaches more than %.1f px past where the level meets the ground; "
                            + "1 band and 4 bands agree pixel for pixel%s", worstTop, worstBed, Math.max(0, worstEnd), bad));

            // ---- a body the simulation would send wrongly (hanging over a rise, wider than its hollow) still cannot float
            double level = bed - 6;
            final Model.Body wide = bodyAt(level, bedX - 400, bedX + 400, bedX, t);
            synchronized (st) { st.water = List.of(wide); }
            Camera far = new Camera();
            p1.layout(far, st, W, H);
            far.set(bedX, bed - 120, 1.6, true);
            Camera.View fv = far.view(W, H, 1, 0, 0);
            int[] w1 = shot(p1, st, far);
            synchronized (st) { st.water = List.of(); }
            int[] d1 = shot(p1, st, far);
            int floating = 0, drawn = 0;
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                if (w1[y * W + x] == d1[y * W + x]) continue;
                drawn++;
                double wx = (x + 0.5 - fv.wtx) / fv.ws;
                if (y < Math.floor(fv.ws * level + fv.wty) - 1 || y > fv.ws * t.at(wx) + fv.wty + 1.5) floating++;
            }
            check(drawn > 200 && floating == 0, "a body sent 800 px wide across ground that rises above its level is drawn only where the terrain is below the level (" + drawn + " px, none above the level or under the ground)");

            // ---- a flooded town with pools, the overlay on, rain falling: 1 band = 4 bands
            ViewerState fl = floodWorld(null, 8, 40, 30);
            for (ParallelRenderer pr : new ParallelRenderer[]{p1, p4}) { pr.primary().showDrains = true; pr.primary().showLabels = true; pr.primary().showDrainCard = true; pr.primary().showClimate = true; }
            Camera fc = new Camera();
            p1.layout(fc, fl, W, H);
            fc.set(1150, 880, 1.3, true);
            int[] f1 = shot(p1, fl, fc), f4 = shot(p4, fl, fc);
            int streets = 0, pools = 0, rippled = 0;
            for (WaterPainter.Body b : p1.shared.water.draw) { if (b.street) streets++; else pools++; if (b.rain > 0.02) rippled++; }
            check(diff(f1, f4) == 0 && streets >= 1 && pools >= 1 && rippled >= 1 && p1.primary().drainRect != null,
                    String.format("a flooding town in the rain with the pipe overlay, the labels and the drains card: 1 render band and 4 give the same picture (%d street bodies, %d pools, %d with ripples)", streets, pools, rippled));
            for (ParallelRenderer pr : new ParallelRenderer[]{p1, p4}) { pr.primary().showDrains = false; pr.primary().showLabels = false; pr.primary().showDrainCard = false; pr.primary().showClimate = false; }

            // ---- the easing: never a jump, there in about a third of a second; a new body fades in, a vanished one out
            WaterPainter.State ws = new WaterPainter.State();
            ws.on = true;
            Model.Body a = bodyAt(1000, 100, 200, 150, t);
            WaterPainter.step(ws, List.of(a), 0, false);
            double alpha0 = ws.draw[0].alpha;
            for (int k = 0; k < 9; k++) WaterPainter.step(ws, null, 1 / 60.0, false);
            double alphaMid = ws.draw[0].alpha;
            for (int k = 0; k < 12; k++) WaterPainter.step(ws, null, 1 / 60.0, false);
            Model.Body a2 = bodyAt(990, 60, 260, 150, t);
            WaterPainter.step(ws, List.of(a2), 0, false);
            double maxStep = 0, prev = ws.draw[0].y, after12 = 0, after30 = 0;
            boolean mono = true;
            for (int k = 1; k <= 30; k++) {
                WaterPainter.step(ws, null, 1 / 60.0, false);
                double yy = ws.draw[0].y;
                if (yy > prev + 1e-12) mono = false;
                maxStep = Math.max(maxStep, prev - yy);
                prev = yy;
                if (k == 12) after12 = yy;
                if (k == 30) after30 = yy;
            }
            WaterPainter.step(ws, List.of(), 0, false);
            int alive = 0;
            double fadeMid = 0;
            for (int k = 1; k <= 24 && ws.draw.length > 0; k++) { WaterPainter.step(ws, null, 1 / 60.0, false); alive = k; if (k == 9 && ws.draw.length > 0) fadeMid = ws.draw[0].alpha; }
            check(alpha0 == 0 && Math.abs(alphaMid - 0.5) < 0.02 && mono && maxStep < 1.4 && maxStep > 0.5 && Math.abs(after12 - 990) < 2 && Math.abs(after30 - 990) < 0.2
                            && Math.abs(fadeMid - 0.5) < 0.02 && alive >= 17 && alive <= 19 && ws.draw.length == 0,
                    String.format(Locale.ROOT, "easing at 60 frames/s: a level that jumps 10 px moves at most %.2f px in a frame, is within %.2f px after 0.2 s and %.3f px after 0.5 s, never backwards; "
                            + "a new body is half there after 0.15 s, a vanished one is gone after %d frames (0.3 s)", maxStep, Math.abs(after12 - 990), Math.abs(after30 - 990), alive));
        } finally {
            p1.shutdown();
            p4.shutdown();
        }
    }

    private void drainsAndCard() {
        Advanced.publish(Advanced.DEFAULT);
        ParallelRenderer p1 = renderer(1);
        try {
            // ---- the inlets: where the protocol's formula puts them, and the only thing drains add to a dry frame
            ViewerState st = floodWorld(null, 0, 0, 2);
            Model.Settlement vil = st.hello.settlements.get(0), city = st.hello.settlements.get(1);
            Model.Drain d = st.drains.get(vil.id);
            Camera cam = new Camera();
            p1.layout(cam, st, W, H);
            cam.set((vil.x0 + vil.x1) / 2, st.hello.terrain.at(vil.x0) - 60, 3, true);
            Camera.View v = cam.view(W, H, 1, 0, 0);
            int[] with = shot(p1, st, cam);
            Map<Long, Model.Drain> keep;
            synchronized (st) { keep = st.drains; st.drains = Map.of(); }
            int[] without = shot(p1, st, cam);
            synchronized (st) { st.drains = keep; }
            boolean formula = d != null && d.inlets == Math.max(1, (int) Math.round((vil.x1 - vil.x0) / 60.0));
            int found = 0, stray = 0;
            double gw = DrainPainter.grateWidth(d.size);
            boolean[] seen = new boolean[d.inlets];
            for (int x = 0; x < W; x++) {
                boolean col = false;
                for (int y = 0; y < H; y++) if (with[y * W + x] != without[y * W + x]) { col = true; break; }
                if (!col) continue;
                double wx = (x + 0.5 - v.wtx) / v.ws;
                boolean at = false;
                for (int i = 0; i < d.inlets; i++) {
                    double ix = vil.x0 + (i + 0.5) * (vil.x1 - vil.x0) / d.inlets;
                    if (Math.abs(d.inletX(i, vil.x0, vil.x1) - ix) > 1e-9) formula = false;
                    if (Math.abs(wx - ix) <= gw / 2 + 0.6) { at = true; seen[i] = true; }
                }
                if (!at) stray++;
            }
            int inView = 0;
            for (int i = 0; i < d.inlets; i++) { if (seen[i]) found++; if (d.inletX(i, vil.x0, vil.x1) > v.wx0 + gw && d.inletX(i, vil.x0, vil.x1) < v.wx1 - gw) inView++; }
            check(formula && inView >= 3 && found == inView && stray == 0,
                    String.format("the %d inlets of %s stand at x0 + (i + 0.5)(x1 - x0) / inlets; on a dry street with the overlay and the card off they are all the drains add to the frame "
                            + "(%d of %d in view found, %d columns changed anywhere else)", d.inlets, vil.name, found, inView, stray));

            // ---- the overlay: each pipe as thick as its size (the fake's flood scene: village 3.0 px, city 8.2 px after the commands)
            ViewerState two = floodWorld(null, 0, 0, 2, "{\"cmd\":\"drains\",\"settlement\":" + vil.id + ",\"size\":3.0}", "{\"cmd\":\"drains\",\"settlement\":" + city.id + ",\"size\":8.2}");
            double[] rows = new double[2];
            for (int k = 0; k < 2; k++) {
                Model.Settlement s = k == 0 ? vil : city;
                Camera c2 = new Camera();
                p1.layout(c2, two, W, H);
                c2.set((s.x0 + s.x1) / 2 + 17, two.hello.terrain.at(s.x0) - 40, 3, true);        // (17 px off the middle: between two drop shafts)
                p1.primary().showDrains = false;
                int[] off = shot(p1, two, c2);
                p1.primary().showDrains = true;
                int[] on = shot(p1, two, c2);
                p1.primary().showDrains = false;
                int x = W / 2 + 270, n = 0;                                                     // (90 px east of the camera: clear of the pipe's label)
                for (int y = 0; y < H; y++) if (on[y * W + x] != off[y * W + x]) n++;
                rows[k] = n / 3.0;                                                               // world px of cutaway in that column
            }
            double sv = two.drains.get(vil.id).size, sc = two.drains.get(city.id).size;
            check(sv == 3.0 && sc == 8.2 && two.drainMode.equals("custom") && Math.abs(rows[0] - (sv + 16.5)) < 1.0 && Math.abs(rows[1] - (sc + 16.5)) < 1.0,
                    String.format(Locale.ROOT, "the pipe overlay: the cutaway under the 3.0 px village is %.1f px tall, under the 8.2 px city %.1f px: the difference is the difference of the pipe sizes (%.1f)",
                            rows[0], rows[1], rows[1] - rows[0]));

            // ---- the FLOODED chip comes and goes with the flood state
            p1.primary().showLabels = true;
            ViewerState fl = floodWorld(null, 8, 60, 45, "{\"cmd\":\"drains\",\"settlement\":" + vil.id + ",\"size\":3.0}", "{\"cmd\":\"drains\",\"settlement\":" + city.id + ",\"size\":8.2}");
            Camera c3 = new Camera();
            p1.layout(c3, fl, W, H);
            // (Phase N: the scene's city now lies west of the village with a hollow between them; the view is on the village, the city's east end in it)
            c3.set(vil.x0 + 100, fl.hello.terrain.at(vil.x0) - 150, 1.3, true);
            int redFlooded = chipRed(shot(p1, fl, c3));
            Model.Flood fv = fl.floodOf(vil.id), fc = fl.floodOf(city.id);
            List<Model.Flood> keepF;
            synchronized (fl) { keepF = fl.flood; fl.flood = List.of(); }
            int redNone = chipRed(shot(p1, fl, c3));
            synchronized (fl) { fl.flood = keepF; }
            p1.primary().showLabels = false;
            check(fv != null && fv.flooded() && fc != null && !fc.flooded() && redFlooded - redNone > 300,
                    String.format(Locale.ROOT, "8 hits/s for 45 s: %s (3.0 px pipe) is %s at %.1f px, %s (8.2 px) is %s at %.1f px; the red FLOODED chip is beside the village's name only (%d red chip pixels more than in the same frame without a flood state, which has %d red pixels of its own)",
                            vil.name, fv == null ? "?" : fv.state, fv == null ? 0 : fv.depth, city.name, fc == null ? "?" : fc.state, fc == null ? 0 : fc.depth, redFlooded - redNone, redNone));

            // ---- the card: which settlement, and its words for every state
            Camera.View cv = c3.view(W, H, 1, 0, 0);
            Model.Settlement under = DrainCard.pick(fl.hello, fl.drains, cv);
            Camera c4 = new Camera();
            p1.layout(c4, fl, W, H);
            c4.set(city.x0 - 300, 800, 1.3, true);
            Model.Settlement nearest = DrainCard.pick(fl.hello, fl.drains, c4.view(W, H, 1, 0, 0));
            Camera c5 = new Camera();
            p1.layout(c5, fl, W, H);
            c5.set(3300, 800, 3, true);
            StringBuilder words = new StringBuilder();
            boolean ok = true;
            for (String state : Model.FLOOD_STATES) {
                Model.Flood f = new Model.Flood();
                f.id = vil.id; f.state = state; f.cap = 30; f.inflow = state.equals("dry") ? 0 : 41;
                f.depth = switch (state) { case "ponding" -> 1.2; case "flooded" -> 4.6; case "overflowing" -> 10; default -> 0; };
                String[] l = DrainCard.lines(vil, fl.drains.get(vil.id), f, "custom");
                boolean flooded = state.equals("flooded") || state.equals("overflowing");
                if (!l[4].equals(state) || !l[5].equals(flooded ? "flooded: yes" : "flooded: no") || !l[0].contains(vil.name) || !l[0].contains("village") || !l[1].startsWith("3 px")
                        || !l[1].contains("small") || !l[2].equals(state.equals("dry") ? "0 / 30 px²/s" : "41 / 30 px²/s") || !l[6].equals("custom")
                        || l[3].equals("none standing") != (f.depth < 0.05)) ok = false;
                words.append(state).append(" → ").append(l[3]).append(", ").append(l[5]).append("; ");
            }
            String[] none = DrainCard.lines(vil, fl.drains.get(vil.id), null, "");
            check(ok && under != null && (under.id == vil.id || under.id == city.id) && cv.cx >= under.x0 && cv.cx <= under.x1 && nearest != null && nearest.id == city.id
                            && DrainCard.pick(fl.hello, fl.drains, c5.view(W, H, 1, 0, 0)) == null && none[4].equals("dry") && none[5].equals("flooded: no") && none[6].equals("normal"),
                    "the drains card is about the settlement under the middle of the view, else the nearest in view, and there is none without one; its text for each state: " + words);

            // ---- the card on screen under the Climate panel (and the Climate panel gives it the room)
            p1.primary().showDrainCard = true;
            p1.primary().showClimate = true;
            int[] cardOn = shot(p1, fl, c3);
            Rectangle2D rc = p1.primary().drainRect, clim = p1.primary().climateRect;
            long cardId = p1.primary().drainCardId;
            p1.primary().showDrainCard = false;
            int[] cardOff = shot(p1, fl, c3);
            int inside = 0, outside = 0;
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (cardOn[y * W + x] != cardOff[y * W + x]) {
                if (rc != null && rc.contains(x + 0.5, y + 0.5)) inside++;
                else if (clim == null || !new Rectangle2D.Double(clim.getX() - 2, clim.getY() - 2, clim.getWidth() + 4, H).contains(x, y)) outside++;
            }
            p1.primary().showClimate = false;
            check(rc != null && clim != null && rc.getY() >= clim.getMaxY() + 6 && rc.getMaxY() <= H && cardId == under.id && inside > 4000 && outside == 0 && p1.primary().drainRect == null,
                    "the card is drawn under the Climate panel, inside the view, for that settlement; switching it off changes nothing outside the two panels" + (rc == null ? " (no card!)" : ""));
        } finally {
            p1.shutdown();
        }
    }

    /** Pixels of the chip's red (a saturated dark red no other label uses). */
    private static int chipRed(int[] px) {
        int n = 0;
        for (int p : px) { int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255; if (r > 120 && r < 190 && g < 70 && b < 70 && r > g * 2.5) n++; }
        return n;
    }

    // ------------------------------------------------------------------ the buttons and the keys, on a running game view

    /** The fake simulation, with every command the viewer sends written down. */
    private static final class Recording implements FrameSource {
        final FakeSim sim;
        final List<String> sent = java.util.Collections.synchronizedList(new ArrayList<>());

        Recording(FakeSim sim) { this.sim = sim; }

        @Override public void start(ViewerState state) { sim.start(state); }

        @Override public void send(String json) { sent.add(json); sim.send(json); }

        @Override public void stop() { sim.stop(); }

        @Override public String describe() { return "recording fake"; }
    }

    private BufferedImage canvas = new BufferedImage(1680, 760, BufferedImage.TYPE_INT_RGB);

    private void paint() {
        edt(() -> {
            ViewerPanel p = app.panel;
            if (p.getWidth() != canvas.getWidth() || p.getHeight() != canvas.getHeight()) p.setSize(canvas.getWidth(), canvas.getHeight());
            Graphics2D g = canvas.createGraphics();
            p.paint(g);
            g.dispose();
        });
    }

    private void frames(int n) throws InterruptedException {
        for (int k = 0; k < n; k++) { paint(); Thread.sleep(16); }
    }

    private void click(double x, double y) throws InterruptedException {
        paint();
        final int ix = (int) Math.round(x), iy = (int) Math.round(y);
        edt(() -> {
            ViewerPanel p = app.panel;
            MouseEvent press = new MouseEvent(p, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, ix, iy, 1, false, MouseEvent.BUTTON1);
            MouseEvent release = new MouseEvent(p, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, ix, iy, 1, false, MouseEvent.BUTTON1);
            for (MouseListener l : p.getMouseListeners()) l.mousePressed(press);
            for (MouseListener l : p.getMouseListeners()) l.mouseReleased(release);
        });
        frames(8);
    }

    private void key(int code, char ch) throws InterruptedException {
        paint();
        edt(() -> {
            ViewerPanel p = app.panel;
            KeyEvent press = new KeyEvent(p, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, ch);
            for (KeyListener l : p.getKeyListeners()) l.keyPressed(press);
        });
        frames(8);
    }

    private void buttonsAndKeys() throws Exception {
        Recording rec = new Recording(new FakeSim(1234, 0.38, 3840, 1080));
        edt(() -> { app.applySettings(); app.startDiagnostic(rec, "drain test"); });
        frames(20);
        ViewerPanel p = app.panel;
        Renderer r = p.renderer();
        ViewerState st = app.session.state;
        Rectangle2D rc = r.drainRect;
        long id = r.drainCardId;
        Model.Drain d0;
        String mode0;
        synchronized (st) { d0 = st.drains.get(id); mode0 = st.drainMode; }
        check(rc != null && d0 != null && mode0.equals("normal") && st.hello.water != null,
                "a game view on the fake simulation shows the drains card for the settlement under the view (id " + id + (d0 == null ? "" : String.format(Locale.ROOT, ", pipe %.2f px", d0.size)) + ")");
        if (rc == null || d0 == null) return;
        // ---- the + button on the pipe row: the command, and the card follows through frame.drains
        double bx = rc.getX() + rc.getWidth() - 10 - 38, by = rc.getY() + DrainCard.PIPE_ROW - 11;
        click(bx + 20 + 9, by + 7);
        Model.Drain d1;
        String mode1;
        synchronized (st) { d1 = st.drains.get(id); mode1 = st.drainMode; }
        boolean plus = rec.sent.contains("{\"cmd\":\"drains\",\"settlement\":" + id + ",\"delta\":0.5}") && d1 != null && Math.abs(d1.size - Math.min(12, d0.size + 0.5)) < 0.006 && mode1.equals("custom");
        click(bx + 9, by + 7);
        Model.Drain d2;
        synchronized (st) { d2 = st.drains.get(id); }
        boolean minus = rec.sent.contains("{\"cmd\":\"drains\",\"settlement\":" + id + ",\"delta\":-0.5}") && d2 != null && Math.abs(d2.size - d0.size) < 0.006;
        check(plus && minus, String.format(Locale.ROOT, "the card's + sends {\"cmd\":\"drains\",\"settlement\":%d,\"delta\":0.5} and the pipe is %.2f px on the card a few frames later (mode custom); − takes it back to %.2f",
                id, d1 == null ? 0 : d1.size, d2 == null ? 0 : d2.size));
        // ---- the four mode chips
        StringBuilder bad = new StringBuilder();
        double cw = DrainCard.chipWidth();
        for (int k : new int[]{0, 2, 3, 1}) {
            click(rc.getX() + 10 + k * (cw + 4) + cw / 2, rc.getY() + DrainCard.CHIPS_TOP + DrainCard.CHIP_H / 2.0);
            String m;
            Model.Drain dk;
            synchronized (st) { m = st.drainMode; dk = st.drains.get(id); }
            double want = k == 0 ? 3.0 : k == 2 ? 8.2 : k == 1 ? d0.size : dk.size;
            if (!rec.sent.contains("{\"cmd\":\"drains\",\"mode\":\"" + DrainCard.MODES[k] + "\"}") || !m.equals(DrainCard.MODES[k]) || Math.abs(dk.size - want) > 0.006)
                bad.append(' ').append(DrainCard.MODES[k]).append(": mode ").append(m).append(String.format(Locale.ROOT, ", %.2f px", dk.size));
        }
        check(bad.length() == 0, "the chips Small, Large, Random and Normal each send {\"cmd\":\"drains\",\"mode\":…} and the card shows that mode and its size (3.0, 8.2, a random one, the seeded one again)" + bad);
        // ---- the keys: , and . change the size, D shows the pipes; rebinding works and persists
        int n0 = rec.sent.size();
        key(KeyEvent.VK_PERIOD, '.');
        key(KeyEvent.VK_COMMA, ',');
        boolean keys = rec.sent.size() == n0 + 2 && rec.sent.get(n0).contains("\"delta\":0.5") && rec.sent.get(n0 + 1).contains("\"delta\":-0.5");
        boolean before = r.showDrains;
        key(KeyEvent.VK_D, 'd');
        boolean toggled = r.showDrains != before;
        key(KeyEvent.VK_D, 'd');
        boolean defaults = Action.defaults().get(KeyEvent.VK_D) == Action.TOGGLE_DRAINS && Action.defaults().get(KeyEvent.VK_COMMA) == Action.DRAIN_SMALLER && Action.defaults().get(KeyEvent.VK_PERIOD) == Action.DRAIN_LARGER
                && Action.defaults().size() == Action.defaults().keySet().stream().distinct().count();
        String warn = KeyMap.assign(settings.keys, Action.DRAIN_LARGER, KeyEvent.VK_N);
        String w2 = KeyMap.assign(settings.keys, Action.TOGGLE_DRAINS, KeyEvent.VK_J);
        edt(app::settingsChanged);
        settings.save();
        Settings back = Settings.load(settings.dir);
        edt(() -> app.panel.setBindings(KeyMap.table(settings.keys)));
        int n1 = rec.sent.size();
        key(KeyEvent.VK_N, 'n');
        key(KeyEvent.VK_PERIOD, '.');                       // no longer bound
        boolean rebound = rec.sent.size() == n1 + 1 && rec.sent.get(n1).contains("\"delta\":0.5");
        key(KeyEvent.VK_J, 'j');
        boolean jOn = r.showDrains != before;
        key(KeyEvent.VK_J, 'j');
        String help = java.util.Arrays.deepToString(KeyMap.helpRows(KeyMap.defaults()));
        check(keys && toggled && defaults && warn == null && w2 == null && rebound && jOn && back.keys.get(Action.DRAIN_LARGER).equals(List.of(KeyEvent.VK_N))
                        && back.keys.get(Action.TOGGLE_DRAINS).equals(List.of(KeyEvent.VK_J)) && back.keys.get(Action.DRAIN_SMALLER).equals(List.of(KeyEvent.VK_COMMA))
                        && help.contains("[D, ") && help.contains("[, / ., ") && KeyMap.helpRows(KeyMap.defaults()).length == 27,
                "keys: . and , send delta +0.5 / −0.5 for the settlement on the card, D switches the pipe overlay; D , . are free defaults; rebound to N and J they work at once, "
                        + "the old key does nothing, the settings file keeps them, and the help overlay lists all three");
        // ---- the help overlay still fits the smallest window
        edt(() -> { settings.keys = KeyMap.defaults(); app.settingsChanged(); app.leaveNow(); });
        app.awaitStops();
        float pitch = (float) Model.clamp((581 - 16 - 80) / 27.0, 15, 20);
        check(60 + Math.round(27 * pitch) + 20 <= 581 - 8 && pitch >= 15, String.format(Locale.ROOT, "the help overlay's 27 rows fit a 581 px window (%.1f px a row)", pitch));
    }

    // ------------------------------------------------------------------ New simulation

    private void newSimulation() throws Exception {
        Settings fresh = new Settings(new File(tmp, "cfg2"));
        boolean noKey = !Json.write(fresh.toJson()).contains("defaultDrains") && fresh.defaultDrains.equals("normal");
        new File(tmp, "cfg2").mkdirs();
        Files.writeString(new File(tmp, "cfg2/settings.json").toPath(), "{\"version\":1,\"game\":{\"defaultSize\":1}}", StandardCharsets.UTF_8);
        Settings oldFile = Settings.load(new File(tmp, "cfg2"));
        fresh.defaultDrains = "large";
        fresh.save();
        Settings large = Settings.load(new File(tmp, "cfg2"));
        Files.writeString(new File(tmp, "cfg2/settings.json").toPath(), "{\"version\":1,\"game\":{\"defaultDrains\":\"enormous\"}}", StandardCharsets.UTF_8);
        Settings bad = Settings.load(new File(tmp, "cfg2"));
        check(noKey && oldFile.defaultDrains.equals("normal") && large.defaultDrains.equals("large") && Json.write(fresh.toJson()).contains("\"defaultDrains\":\"large\"") && bad.defaultDrains.equals("normal"),
                "settings: a file without the key loads as Normal, Normal is not written, Large is written and read back, an unknown value is Normal");

        boolean[] ok = new boolean[4];
        String[] args = new String[3];
        edt(() -> {
            settings.defaultDrains = "normal";
            NewSimScreen ns = new NewSimScreen(app);
            ok[0] = ns.drainIdx == 1 && ns.params().drains.equals("normal") && !ns.params().climateArgs().contains("--drains");
            ns.drainIdx = 2;
            NewSimScreen.Params pl = ns.params();
            args[0] = String.join(" ", pl.climateArgs());
            ns.drainIdx = 0;
            ns.monthIdx = 7;
            args[1] = String.join(" ", ns.params().climateArgs());
            ns.drainIdx = 3;
            args[2] = String.join(" ", ns.params().climateArgs());
            settings.defaultDrains = "random";
            ok[1] = new NewSimScreen(app).drainIdx == 3;
            settings.defaultDrains = "normal";
            // the argument list AppShell.startNew hands the simulation: --seed, --terrain, --settlements, then these
            List<String> extra = new ArrayList<>(List.of("--seed", "7", "--terrain", pl.terrain, "--settlements", pl.settlements));
            extra.addAll(pl.climateArgs());
            ok[2] = String.join(" ", new SimProcess(root, pl.width(), pl.height(), extra).args()).contains("--settlements " + pl.settlements + " --drains large");
        });
        check(ok[0] && args[0].equals("--drains large") && args[1].startsWith("--drains small --month 7.50") && args[2].startsWith("--drains random") && ok[1] && ok[2],
                "New simulation: the Drains choice starts at the remembered default (Normal), Large gives the simulation \"" + args[0] + "\" next to --settlements, Small and Random likewise, "
                        + "and Normal sends no argument (it is the simulation's own default)");
        // ---- the dialog at the smallest window and at the usual one (looked at as pictures too: viewer/snapshots/v24)
        int rc1 = UiSnapshot.run(root, new Settings(new File(tmp, "cfg3")), "new", new File(tmp, "new-min.png").getPath(), 1024, 581, 1, null);
        int rc2 = UiSnapshot.run(root, new Settings(new File(tmp, "cfg3")), "new", new File(tmp, "new.png").getPath(), 1680, 760, 1, null);
        BufferedImage small = javax.imageio.ImageIO.read(new File(tmp, "new-min.png"));
        // the Create button's blue must be in the picture, above the bottom edge: the last row of the dialog is on screen
        int blue = 0, lowest = 0;
        for (int y = 0; y < small.getHeight(); y++) for (int x = 0; x < small.getWidth(); x++) {
            int c = small.getRGB(x, y), rr = (c >> 16) & 255, gg = (c >> 8) & 255, bb = c & 255;
            if (Math.abs(rr - 52) < 8 && Math.abs(gg - 104) < 8 && Math.abs(bb - 196) < 10) { blue++; lowest = y; }
        }
        check(rc1 == 0 && rc2 == 0 && blue > 2000 && lowest < 581 - 12, "the dialog with the Drains row paints at 1024x581 and at 1680x760; at the smallest size its Create button is wholly inside the window (lowest row " + lowest + " of 581)");
    }

    // ------------------------------------------------------------------ the Debug mode's Flood tab

    private static int bandThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.isAlive() && t.getName().startsWith("render-band-")) n++;
        return n;
    }

    private void floodTab() throws Exception {
        boolean[] ok = new boolean[8];
        String[] say = new String[4];
        int threads0 = bandThreads();
        Object[] hold = new Object[1];
        edt(() -> {
            DebugScreen ds = DebugScreen.open(app, true);
            hold[0] = ds;
            ds.setTab(DebugScreen.FLOOD);
            DebugFlood.FloodTab ft = ds.flood;
            BufferedImage rest = ds.picture(1040, 620, 1);
            Model.Flood v0 = ft.flood(0), c0 = ft.flood(1);
            ok[0] = ds.lastError == null && ft.pane != null && ft.drain(0) != null && ft.drain(0).size == 3.0 && ft.drain(1).size == 8.2 && (v0 == null || v0.state.equals("dry")) && (c0 == null || c0.state.equals("dry"));
            // 8 hits/s on both towns for 45 s: the small-drained village floods, the large-drained city does not
            ft.option("rate", "8");
            ft.option("rain", "on");
            ft.option("run", "45");
            BufferedImage flooding = ds.picture(1040, 620, 1), big = ds.picture(1680, 760, 1);
            Model.Flood v1 = ft.flood(0), c1 = ft.flood(1);
            ok[1] = ds.lastError == null && v1 != null && v1.flooded() && c1 != null && !c1.flooded() && c1.depth < 0.05 && Math.abs(v1.inflow / 8 - c1.inflow / 8) < 60;
            say[0] = v1 == null || c1 == null ? "?" : String.format(Locale.ROOT, "village %s %.1f px (inflow %.0f, cap %.0f), city %s %.1f px (inflow %.0f, cap %.0f)", v1.state, v1.depth, v1.inflow, v1.cap, c1.state, c1.depth, c1.inflow, c1.cap);
            double widest = 0;
            synchronized (ft.pane.st) { for (Model.Body b : ft.pane.st.water) if (!b.street()) widest = Math.max(widest, b.x1 - b.x0); }
            ok[2] = widest > 140;
            say[1] = String.format(Locale.ROOT, "%.0f", widest);
            double peak = v1 == null ? 0 : v1.depth;
            // the rain stops: the flood drains
            // (Phase N, merged in place: session phase-l-viewer-fixes took the time to dry from the closed form of a body held by the
            // curb, cap / span + SOAK px/s. There is no curb any more: the village's flood is a sheet that also runs off its street,
            // so it falls faster than that and the fixed times of the first version hold again. The closed form is checked on the
            // walled-pad fixture, above.)
            ft.option("rain", "off");
            ft.option("run", "40");
            Model.Flood v2 = ft.flood(0);
            double mid = v2 == null ? 0 : v2.depth;
            ft.option("run", "120");
            BufferedImage drained = ds.picture(1040, 620, 1);
            Model.Flood v3 = ft.flood(0);
            ok[3] = ds.lastError == null && mid < peak - 1 && v3 != null && v3.depth < 0.05 && !v3.flooded() && ft.history.size() > 100;
            say[2] = String.format(Locale.ROOT, "%.1f px at the peak, %.1f px 40 s after the rain, %s after 160 s", peak, mid, v3 == null ? "?" : v3.state);
            // the controls: a storm ends by itself, the chips and the size slider send the real command
            ft.option("storm", "");
            boolean started = ft.raining && !Double.isNaN(ft.stormUntil);
            ft.option("run", "61");
            boolean ended = !ft.raining && Double.isNaN(ft.stormUntil);
            ft.option("drains", "large");
            ft.option("run", "1");
            String mode;
            synchronized (ft.pane.st) { mode = ft.pane.st.drainMode; }
            boolean large = mode.equals("large") && ft.drain(0).size == 8.2;
            ft.option("village", "5.5");
            ft.option("run", "1");
            synchronized (ft.pane.st) { mode = ft.pane.st.drainMode; }
            ok[4] = started && ended && large && mode.equals("custom") && ft.drain(0).size == 5.5 && ft.drain(0).cls.equals("normal");
            ok[5] = differs(rest, flooding) > 3000 && differs(flooding, drained) > 3000 && big.getWidth() == 1680 && DebugScreen.TABS.length == 9 && DebugScreen.TABS[DebugScreen.FLOOD].equals("Flood")
                    && DebugScreen.TAB_IDS[8].equals("flood") && java.util.Arrays.asList(UiSnapshot.SCREENS).contains("debug-flood");
            ds.setTab(DebugScreen.CLOUDS);
            ok[6] = ft.pane == null && ft.history.isEmpty();
            ds.dispose();
            app.leaveNow();
        });
        app.awaitStops();
        int threads1 = bandThreads();
        for (int k = 0; k < 40 && threads1 > threads0; k++) { Thread.sleep(50); threads1 = bandThreads(); }
        check(ok[0], "the Flood tab opens at rest: a dry street, the village on 3.0 px pipes, the city on 8.2 px, no exception at 1040x620");
        check(ok[1] && ok[2], "8 hits/s for 45 s in one picture: " + say[0] + "; the natural pool is " + say[1] + " px wide (past the 140 px a puddle was capped at); paints at 1040x620 and 1680x760");
        check(ok[3], "after the rain the flood drains: " + say[2] + "; the depth history has the whole rise and fall");
        check(ok[4] && ok[5], "the Storm button rains for 60 s and stops by itself; the chips and the size slider go through the protocol's drains command (large → 8.2 px; then 5.5 px, mode custom); "
                + "the tab is the ninth (key 9) and a --ui-snapshot screen");
        check(ok[6] && threads1 <= threads0, "leaving the tab lets go of its world, its renderers and their threads (" + threads0 + " band threads before, " + threads1 + " after)");
    }

    private static int differs(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y += 2) for (int x = 0; x < a.getWidth(); x += 2) if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
        return n;
    }
}
