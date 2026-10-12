package precip;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.SwingUtilities;

/**
 * The checks of protocol v2.4.1 in the viewer (PLAN Phase N, step NV7), run by {@code --selftest-ui} and
 * {@code --selftest} after the other four: the new keys and their absence, a v2.4 stream still drawn with its ramp
 * and its trickle, the stream's thickness at a given flux and slope (sizes and ratios, not "a ribbon exists"), a
 * ribbon that rests on the ground and never lies in a body, no body end above lower ground, the easing, the same
 * picture in one band and in four, white water and the cliff, {@link FakeWater} against the model table's vectors,
 * and the Debug mode's Flood tab.
 * <p>
 * Opens no window and needs no Python. Settings, saves and files go to a throw-away folder in the system temp
 * directory. What it cannot check is said in its last line.
 */
final class FlowTest {
    private static final int W = 1400, H = 700;
    private final File root, tmp;
    private final Settings settings;
    private final AppShell app;
    private final List<String> failures = new ArrayList<>();
    private int step;

    private FlowTest(File root, File tmp) {
        this.root = root;
        this.tmp = tmp;
        settings = new Settings(new File(tmp, "config"));
        app = new AppShell(root, settings, new SaveStore(new File(tmp, "saves")));
    }

    static int run() {
        File tmp;
        try {
            tmp = Files.createTempDirectory("precip-flowtest").toFile();
        } catch (java.io.IOException ex) {
            System.out.println("[flow-test] cannot create a temp folder: " + ex.getMessage());
            return 1;
        }
        Advanced keepAdv = Advanced.cur;
        FlowTest t = new FlowTest(Main.projectRoot(), tmp);
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
            System.out.println("[flow-test] PASS (" + t.step + " checks)   [not checked here: the real simulation run live (one recorded v2.4.1 stream of it is read and drawn, check 10)]");
            return 0;
        }
        System.out.println("[flow-test] FAIL");
        for (String f : t.failures) System.out.println("[flow-test]   - " + f);
        return 1;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private void check(boolean ok, String what) {
        step++;
        System.out.println(String.format("[flow-test] %2d  %s  %s", step, ok ? "ok  " : "FAIL", what));
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
        thickness();
        oldStream();
        bodiesAndBands();
        easing();
        floodTab();
    }

    // ------------------------------------------------------------------ FakeWater against the model table

    /** The steady film on cell {@code k} of a uniform slope fed {@code q} px²/s at its head. */
    private static double steady(double slope, double q, int k) {
        FakeWater w = FakeWater.slope(slope, 1600);
        for (int i = 0; i < 30 * 90; i++) { w.addCell(4, q * FakeSim.DT); w.update(FakeSim.DT); }
        return w.filmDepth(k);
    }

    /** Seconds until the first water of a flux {@code q} put on the head of a slope reaches the cell {@code cells} further down. */
    private static double arrival(double slope, double q, int cells) {
        FakeWater w = FakeWater.slope(slope, 1600);
        for (int i = 1; i <= 30 * 120; i++) {
            w.addCell(4, q * FakeSim.DT);
            w.update(FakeSim.DT);
            if (w.filmDepth(4 + cells) >= FakeWater.WET_DEPTH) return i * FakeSim.DT;
        }
        return Double.NaN;
    }

    private void model() {
        final double dt = FakeSim.DT;
        // ---- N1, the film vector: h = (q / (KU sqrt(slope)))^0.6 on a uniform slope
        final double[] table22 = {0.63, 1.99, 4.39}, qs = {5, 34, 127}, slopes = {0.05, 0.10, 0.22, 0.60, 1.0}, table34 = {3.11, 2.52, 1.99, 1.47, 1.26};
        double worst = 0, worstTable = 0;
        StringBuilder got = new StringBuilder();
        for (int i = 0; i < qs.length; i++) {
            double h = steady(0.22, qs[i], 12);
            worst = Math.max(worst, Math.abs(h / FakeWater.steadyDepth(qs[i], 0.22, FakeWater.KU) - 1));
            worstTable = Math.max(worstTable, Math.abs(h - table22[i]) / table22[i]);
            got.append(String.format(Locale.ROOT, "%.2f ", h));
        }
        got.append("px on slope 0.22; q 34 on slopes 0.05 / 0.10 / 0.22 / 0.60 / 1.0: ");
        for (int i = 0; i < slopes.length; i++) {
            double h = steady(slopes[i], 34, 12);
            worst = Math.max(worst, Math.abs(h / FakeWater.steadyDepth(34, slopes[i], FakeWater.KU) - 1));
            worstTable = Math.max(worstTable, Math.abs(h - table34[i]) / table34[i]);
            got.append(String.format(Locale.ROOT, "%.2f ", h));
        }
        double dbl = steady(0.22, 68, 12) / steady(0.22, 34, 12);
        check(worst < 0.01 && worstTable < 0.012 && Math.abs(dbl - 1.516) < 0.016,
                String.format(Locale.ROOT, "FakeWater, the model table's film vector (KU %.0f): q 5 / 34 / 127 px²/s gives %s px, each within 1 %% of (q / (KU sqrt(slope)))^0.6 (worst %.2f %%, "
                        + "against the table's rounded figures %.2f %%); doubling the flux multiplies the depth by %.3f (1.52)", FakeWater.KU, got.toString().trim(), worst * 100, worstTable * 100, dbl));
        // ---- a thin overflow takes longer to arrive than a thick one (the run of N3: 112 px = 3.5 cells on slope 0.29)
        double thin = arrival(0.29, 5, 4), thick = arrival(0.29, 127, 4), mid = arrival(0.29, 34, 4);
        check(thin > mid && mid > thick && thick >= 1 && thin <= 30,
                String.format(Locale.ROOT, "the first water of a thin overflow arrives later than a thick one's: 4 cells down a slope of 0.29, q 5 takes %.1f s, q 34 %.1f s, q 127 %.1f s", thin, mid, thick));

        // ---- N2, the sheet vector: a level 814 px street without walls, net excess 0.1 px²/s per px
        FakeWater pad = FakeWater.flatPad(814, 4.7, false);
        FakeWater.Town tn = pad.towns.get(0);
        for (int i = 0; i < 30 * 300; i++) { pad.addOnSpan(1, 0.1 * dt); pad.update(dt); }
        final double c = FakeWater.KU_STREET * Math.sqrt(FakeWater.SLOPE_MIN);
        double low = pad.filmDepth(pad.cellAt(tn.x1)), worstCell = 0, storedSpan = 0;
        for (int k = tn.kA; k <= tn.kB; k++) {
            double want = Math.pow(0.1 * (k * 32 + 16 - tn.x0) / c, 0.6);          // the flux leaving the cell is the excess of everything upslope of its east edge
            worstCell = Math.max(worstCell, Math.abs(pad.filmDepth(k) / want - 1));
        }
        for (int k = 0; k < pad.cellCount(); k++) storedSpan += pad.filmDepth(k) * Math.max(0, Math.min(tn.x1, k * 32 + 16) - Math.max(tn.x0, k * 32 - 16));
        double at203 = pad.filmDepth(pad.cellAt(tn.x0 + 203 - 16)), mean = storedSpan / 814;
        check(Math.abs(low - 6.90) / 6.90 < 0.01 && worstCell < 0.015 && Math.abs(mean - 4.30) / 4.30 < 0.05 && Math.abs(storedSpan - 3502) / 3502 < 0.05 && pad.pools.isEmpty() && tn.body == null
                        && tn.state.equals("flooded") && !tn.backed,
                String.format(Locale.ROOT, "the sheet vector (a level 814 px street with no walls, net excess 0.1 px²/s per px, steady): %.2f px at the low end (6.90, within 1 %%), every cell within %.2f %% of "
                        + "(e x / (KU_STREET sqrt(SLOPE_MIN)))^0.6 at its east edge (the rain of three ticks is taken in together), %.2f px in the cell that ends 192 px down (2.90; the table's 3.0 is at 203 px); mean depth %.2f px (4.30) and %.0f px² stored (3 502): "
                        + "each %.1f %% above the table, the 32 px cells' share (within 5 %%, not 1 %%); no body forms: the street is %s as a sheet",
                        low, worstCell * 100, at203, mean, storedSpan, (storedSpan / 3502 - 1) * 100, tn.state));

        // ---- the states follow the depth bands 0.05 / 3 / 8 (the walled-pad fixture rises through all of them)
        FakeWater w = FakeWater.walledPad(400, 4.7);
        FakeWater.Town wt = w.towns.get(0);
        boolean bands = true;
        String first = wt.state;
        double seenPond = 0, seenFlood = 0, seenOver = 0;
        for (int i = 0; i < 30 * 70; i++) {
            w.addToStreet(1, 100 * dt);
            w.update(dt);
            String want = wt.depth < 0.05 ? "wet" : wt.depth < 3 ? "ponding" : wt.depth < 8 ? "flooded" : "overflowing";
            if (i > 6 && !wt.state.equals(want)) bands = false;
            if (wt.state.equals("ponding")) seenPond = Math.max(seenPond, wt.depth);
            if (wt.state.equals("flooded") && seenFlood == 0) seenFlood = wt.depth;
            if (wt.state.equals("overflowing") && seenOver == 0) seenOver = wt.depth;
        }
        check(bands && first.equals("dry") && seenPond > 2.9 && seenPond < 3 && seenFlood >= 3 && seenFlood < 3.1 && seenOver >= 8 && seenOver < 8.1 && !wt.body.spill,
                String.format(Locale.ROOT, "the flood states follow the depth bands: dry, ponding up to %.2f px, flooded from %.2f px, overflowing from %.2f px (overflow_depth 8) while the body is still below its rim", seenPond, seenFlood, seenOver));

        // ---- the fake flood scene: the hilltop village sheds, the hamlet's hollow holds, the hollow beside the city backs up over its street
        FakeSim sim = new FakeSim(1234, 0.45, 3840, 1080, null, null, Double.NaN, Double.NaN, "flood");
        sim.handle("{\"cmd\":\"drains\",\"mode\":\"small\"}");
        sim.handle("{\"cmd\":\"rain\",\"rate\":8}");
        FakeWater fw = sim.water();
        FakeWater.Town vil = fw.town(1), city = fw.town(2), ham = fw.town(3);
        boolean vilBody = false, levelRule = true, filmUnder = false, cityBacked = false;
        double vilPeak = 0, cityPeak = 0, worstEnd = 0;
        for (int i = 1; i <= 30 * 130; i++) {
            if (i == 30 * 100) sim.handle("{\"cmd\":\"rain\",\"rate\":0}");
            sim.step();
            if (i % 30 != 0) continue;
            vilPeak = Math.max(vilPeak, vil.depth);
            cityPeak = Math.max(cityPeak, city.depth);
            cityBacked |= city.backed;
            for (FakeWater.Pool p : fw.pools) {
                if (p.id == 0 || p.vol < FakeWater.MIN_VOL) continue;
                if (p.x1 > vil.x0 && p.x0 < vil.x1) vilBody = true;
                // the level rule: a body ends where its level meets the ground (no water stands against nothing)
                worstEnd = Math.max(worstEnd, Math.max(fw.at(p.x0) - p.level, fw.at(p.x1) - p.level));
                for (int k = 0; k < fw.cellCount(); k++) if (fw.filmDepth(k) > 0 && k * 32 > p.x0 && k * 32 < p.x1 && fw.at(k * 32) > p.level + 0.05) filmUnder = true;
            }
        }
        if (worstEnd > 0.1) levelRule = false;
        double balance = Math.abs(fw.totalIn - fw.stored() - fw.totalSoak - fw.totalDrain - fw.totalLost) / fw.totalIn;
        double vilAfter = vil.depth;
        for (int i = 0; i < 30 * 90; i++) sim.step();
        check(!vilBody && vilPeak >= 3 && vil.depth < 0.05 && vil.state.equals("dry") && !vil.backed && ham.body != null && ham.body.street == 3 && ham.depth > 3 && cityBacked && cityPeak >= 3
                        && levelRule && !filmUnder && balance < 0.001,
                String.format(Locale.ROOT, "the fake flood scene, 100 s of 8 hits/s on small drains: the hilltop village never holds a body; its sheet is %.1f px at the peak, %.1f px 30 s after the rain and gone "
                        + "(%s) 2 min after it; the hamlet's hollow holds a street body %.1f px deep; the hollow beside the city backs up over its street (backed, %.1f px); every body ends where its level "
                        + "meets the ground (worst %.2f px), no film cell lies under a body; rain in = stored + film + soaked + drained + lost within %.4f %%",
                        vilPeak, vilAfter, vil.state, ham.depth, cityPeak, worstEnd, balance * 100)
                        + (vilBody ? " [a body on the village's span]" : "") + (ham.body == null ? " [no hamlet body]" : "") + (filmUnder ? " [film under a body]" : "") + (cityBacked ? "" : " [city never backed]"));
        // ---- the size of the drains still decides: the same storm over the hilltop village with small and with large drains
        double[] peak = new double[2];
        for (int m = 0; m < 2; m++) {
            FakeSim s2 = new FakeSim(1234, 0.45, 3840, 1080, null, null, Double.NaN, Double.NaN, "flood");
            s2.handle("{\"cmd\":\"drains\",\"mode\":\"" + (m == 0 ? "small" : "large") + "\"}");
            s2.handle("{\"cmd\":\"rain\",\"rate\":8}");
            for (int i = 0; i < 30 * 50; i++) { s2.step(); peak[m] = Math.max(peak[m], s2.water().town(1).depth); }
        }
        check(peak[0] >= 3 && peak[1] < 0.05, String.format(Locale.ROOT, "the size of the drains decides whether the hilltop village floods: 50 s of 8 hits/s gives a sheet of %.1f px with small drains and %.2f px with large ones", peak[0], peak[1]));
    }

    // ------------------------------------------------------------------ the wire

    /** A hand-written hello: a 1600 px world whose ground is {@code terrain} (a value every 8 px). */
    private static String hello(double[] terrain, boolean flow, boolean curbKey) {
        StringBuilder sb = new StringBuilder("{\"type\":\"hello\",\"version\":2,\"seed\":5,\"world\":{\"w\":1600,\"h\":900,\"ground_y\":820,\"terrain\":{\"step\":8,\"y\":[");
        for (int i = 0; i < terrain.length; i++) { if (i > 0) sb.append(','); sb.append(String.format(Locale.ROOT, "%.3f", terrain[i])); }
        sb.append("]}},\"water\":{\"model\":1,\"basin_min_depth\":1.0},");
        sb.append("\"drains\":{\"mode\":\"normal\",\"inlet_spacing\":60.0,").append(curbKey ? flow ? "\"curb\":0.0," : "\"curb\":10.0," : "").append("\"flood_depth\":3.0,")
                .append(flow ? "\"overflow_depth\":7.5," : "").append("\"size_min\":1.5,\"size_max\":12.0,\"classes\":{\"small\":3.0,\"normal\":4.7,\"large\":8.2}},");
        if (flow) sb.append("\"flow\":{\"model\":1,\"cell\":32,\"min_h\":0.05},\"some_future_key\":{\"a\":[1,2]},");
        sb.append("\"settlements\":[{\"id\":2,\"kind\":\"town\",\"name\":\"Testford\",\"x0\":800,\"x1\":1280,\"population\":900,\"drain\":{\"size\":4.7,\"class\":\"normal\",\"inlets\":8,\"cap\":47.1}}],\"objects\":[]}");
        return sb.toString();
    }

    private static String frame(int n, String extra) {
        return "{\"type\":\"frame\",\"frame\":" + n + ",\"t\":" + (n / 30.0) + ",\"world\":{\"w\":1600,\"h\":900,\"ground_y\":820,\"surface_temp_c\":12,\"time_of_day\":0.45}" + extra + "}";
    }

    /** Level ground at y 820 with one hollow 14 px deep around x = 400 (as DrainTest's hand-written stream). */
    private static double[] hollowGround() {
        double[] t = new double[201];
        for (int i = 0; i <= 200; i++) {
            double u = Math.abs(i * 8.0 - 400) / 140;
            t[i] = 820 + (u < 1 ? 14 * Math.pow(Math.cos(u * Math.PI / 2), 2) : 0);
        }
        return t;
    }

    private static final String FLOW_2 = ",\"flow\":[{\"k0\":17,\"dir\":1,\"src\":2,\"dst\":4,\"h\":[2.05,2.1,1.9,1.4,0.9],\"q\":[36.0,35.2,34.8,30.1,22.0]},"
            + "{\"k0\":8,\"dir\":-1,\"src\":0,\"dst\":-1,\"h\":[0.35,0.55,0.8],\"q\":[2.1,4.0,6.3],\"later\":true},{\"k0\":3,\"dir\":1},{\"dir\":1,\"h\":[1]}]";

    private void parsing() throws Exception {
        String h241 = hello(hollowGround(), true, true);
        ViewerState st = new ViewerState();
        st.acceptLine(h241);
        st.acceptLine(frame(1, FLOW_2 + ",\"flood\":[{\"id\":2,\"state\":\"flooded\",\"depth\":4.6,\"inflow\":61.0,\"cap\":47.1,\"backed\":true}],\"unknown_list\":[1,2,3]"));
        Model.Hello h = st.hello;
        Model.Run a = st.flow.size() == 2 ? st.flow.get(0) : new Model.Run(), b = st.flow.size() == 2 ? st.flow.get(1) : new Model.Run();
        check(h.flow != null && h.flow.model == 1 && h.flow.cell == 32 && h.flow.minH == 0.05 && h.drains.curb == 0 && h.drains.overflowDepth == 7.5 && h.drains.floodDepth == 3 && h.water != null
                        && st.flow.size() == 2 && a.k0 == 17 && a.dir == 1 && a.src == 2 && a.dst == 4 && a.h.length == 5 && a.h[0] == 2.05 && a.h[4] == 0.9 && a.q[0] == 36.0 && a.q[4] == 22.0
                        && b.k0 == 8 && b.dir == -1 && b.src == 0 && b.dst == -1 && b.h.length == 3 && b.q[2] == 6.3 && st.floodOf(2).backed && st.floodOf(2).flooded(),
                "a hand-written v2.4.1 stream: hello.flow (model, cell, min_h), hello.drains with curb 0.0 and overflow_depth, frame.flow (two runs: k0, dir, src, dst, h[], q[]) and flood[].backed are read; "
                        + "unknown keys in the hello, the frame and a run are ignored, a run without cells or without k0 is dropped");
        // ---- a frame without the key keeps the runs, [] empties them, a new hello starts with none
        st.acceptLine(frame(2, ""));
        boolean kept = st.flow.size() == 2 && st.cur.flow == null;
        st.acceptLine(frame(3, ",\"flow\":[],\"flood\":[{\"id\":2,\"state\":\"wet\",\"depth\":0,\"inflow\":3,\"cap\":47.1}]"));
        boolean emptied = st.flow.isEmpty() && !st.floodOf(2).backed;
        st.acceptLine(frame(4, FLOW_2));
        st.acceptLine(h241);
        // ---- the keys absent: a v2.4 stream
        ViewerState old = new ViewerState();
        old.acceptLine(hello(hollowGround(), false, true));
        old.acceptLine(frame(1, ",\"flood\":[{\"id\":2,\"state\":\"flooded\",\"depth\":4.6,\"inflow\":61.0,\"cap\":47.1}]"));
        ViewerState bare = new ViewerState();
        bare.acceptLine(hello(hollowGround(), false, false));
        check(kept && emptied && st.flow.isEmpty() && old.hello.flow == null && old.hello.drains.curb == 10 && old.hello.drains.overflowDepth == 8 && old.flow.isEmpty() && !old.floodOf(2).backed
                        && bare.hello.drains.curb == 10 && bare.hello.flow == null,
                "the viewer keeps the last runs over a frame without the key, [] empties them, a new hello starts with none; a v2.4 stream has no hello.flow, its curb as sent (10), "
                        + "overflow_depth 8 by default, no runs and backed false");
        // ---- a replay file of the hand-written stream parses back to the same runs; so does the fake's own output
        File rf = new File(tmp, "v241.jsonl");
        Files.writeString(rf.toPath(), h241 + "\n" + frame(1, FLOW_2) + "\n" + frame(2, "") + "\n", StandardCharsets.UTF_8);
        ViewerState rs = new ViewerState();
        for (String line : Files.readAllLines(rf.toPath(), StandardCharsets.UTF_8)) rs.acceptLine(line);
        FakeSim sim = new FakeSim(1234, 0.45, 3840, 1080, null, null, Double.NaN, Double.NaN, "flood");
        ViewerState fs = new ViewerState();
        fs.acceptLine(sim.hello());
        FakeWater fw = sim.water();
        int lists = 0, minGap = 99, last = -99, maxRuns = 0, longest = 0, bytes = 0, maxBytes = 0;
        double worstH = 0, worstMiss = 0;
        boolean once = true, shape = true;
        for (int k = 0; k < 30 * 50; k++) {
            String line = sim.step();
            fs.acceptLine(line);
            // a cell the list last sent may have moved by less than the send rule since: h within 0.1 px (+ the 0.05 step), a cell that is not listed is below 0.05 px (+ the rule)
            boolean[] listed = new boolean[fw.cellCount()];
            for (Model.Run r : fs.flow) for (int i = 0; i < r.h.length; i++) {
                int c = r.k0 + r.dir * i;
                listed[c] = true;
                if (fs.cur.flow != null) worstH = Math.max(worstH, Math.abs(r.h[i] - fw.filmDepth(c)));
            }
            if (fs.cur.flow == null) continue;
            for (int c = 0; c < listed.length; c++) if (!listed[c]) worstMiss = Math.max(worstMiss, fw.filmDepth(c));
            lists++;
            if (last > -99) minGap = Math.min(minGap, k - last);
            last = k;
            maxRuns = Math.max(maxRuns, fs.cur.flow.size());
            int at = line.indexOf("\"flow\":["), n = line.indexOf("]}]", at) + 3 - at;
            bytes += n;
            maxBytes = Math.max(maxBytes, n);
            boolean[] seen = new boolean[fw.cellCount()];
            for (Model.Run r : fs.cur.flow) {
                longest = Math.max(longest, r.h.length);
                if (r.h.length > FakeWater.RUN_MAX || r.h.length != r.q.length) shape = false;
                for (int i = 0; i < r.h.length; i++) {
                    int c = r.k0 + r.dir * i;
                    if (seen[c] || r.h[i] < 0.05 - 1e-9 || fw.cellDir(c) != r.dir) once = false;          // a cell is in at most one run, downhill, never below min_h
                    seen[c] = true;
                }
            }
        }
        check(rs.flow.size() == 2 && rs.flow.get(0).h[2] == 1.9 && rs.hello.flow != null && fs.hello.flow != null && fs.hello.drains.curb == 0 && lists > 30 && minGap >= 3 && worstH <= 0.026 && worstMiss < 0.075
                        && once && shape && maxRuns >= 4,
                String.format(Locale.ROOT, "a hand-written v2.4.1 replay file parses back to its runs; the fake's own stream reads back to the film FakeWater holds (%d lists in 50 s, at most every 3rd frame "
                        + "(closest %d), up to %d runs, the longest %d cells; depths within %.3f px = the 0.05 px step; a cell is in at most one run, every run downhill); a list is %d bytes on average, "
                        + "%d at most", lists, minGap, maxRuns, longest, worstH, lists == 0 ? 0 : bytes / lists, maxBytes));
        // ---- a recorded v2.4.1 stream of the REAL simulation (seed 12, a supercell over its small-drained city, t = 170 s; kept as a
        // fixture): it reads as the protocol says and the viewer lays ribbons out for it that end at the pool's shoreline
        File real = new File(root, "viewer/snapshots/v241/fixtures/v241-real-city-end-supercell.jsonl");
        if (!real.isFile()) check(true, "the recorded v2.4.1 stream of the real simulation (viewer/snapshots/v241/fixtures) is not there: NOT CHECKED here");
        else {
            ViewerState rr = new ViewerState();
            for (String line : Files.readAllLines(real.toPath(), StandardCharsets.UTF_8)) if (!line.isBlank()) rr.acceptLine(line);
            int cells = 0;
            double deepest = 0;
            boolean downhill = true;
            for (Model.Run r : rr.flow) {
                cells += r.h.length;
                for (int i = 0; i < r.h.length; i++) {
                    deepest = Math.max(deepest, r.h[i]);
                    double xa = 32.0 * (r.k0 + r.dir * i), xb = xa + 32 * r.dir;
                    if (i + 1 < r.h.length && rr.hello.terrain.at(xb) < rr.hello.terrain.at(xa) - 1.0) downhill = false;      // (y grows down: the next cell may not lie more than a hollow's 1 px higher)
                }
            }
            Advanced.publish(Advanced.DEFAULT);
            ParallelRenderer pr = renderer(1);
            int pieces, inBody = 0;
            try {
                shot(pr, rr, vcam(pr, rr, 2830, 930, 2.4));
                pieces = pr.shared.flow.draw.length;
                for (FlowPainter.Piece p : pr.shared.flow.draw) for (WaterPainter.Body wb : pr.shared.water.draw) if (Math.min(p.x1, wb.x1) - Math.max(p.x0, wb.x0) > 0.01) inBody++;
            } finally {
                pr.shutdown();
            }
            Model.Flood city = rr.floodOf(3);
            check(rr.hello.flow != null && rr.hello.flow.cell == 32 && rr.hello.drains.curb == 0 && rr.hello.drains.overflowDepth == 8 && !rr.flow.isEmpty() && cells >= 10 && deepest > 1 && downhill
                            && city != null && city.flooded() && pieces >= 1 && inBody == 0,
                    String.format(Locale.ROOT, "a recorded v2.4.1 stream of the real simulation: hello.flow (cell 32), curb 0.0, overflow_depth 8, %d run(s) of %d cells running downhill, the film up to %.1f px deep, "
                            + "its city %s at %.1f px; the viewer lays %d ribbon(s) out for it, none inside a body", rr.flow.size(), cells, deepest, city == null ? "?" : city.state, city == null ? 0 : city.depth, pieces));
        }
        // ---- a dry world: one empty list after the hello, then none
        FakeSim dry = new FakeSim(1234, 0.45, 3840, 1080, null, null, Double.NaN, Double.NaN, "flood");
        dry.handle("{\"cmd\":\"rain\",\"rate\":0}");
        ViewerState dst = new ViewerState();
        dst.acceptLine(dry.hello());
        int flowKeys = 0;
        for (int k = 0; k < 200; k++) { dst.acceptLine(dry.step()); if (dst.cur.flow != null) flowKeys++; }
        // ... and the old worlds of the fake still write the old stream
        ViewerState v23 = new ViewerState();
        v23.acceptLine(new FakeSim(1234, 0.45, 3840, 1080, null, null, Double.NaN, Double.NaN, "flood" + FakeSim.LEGACY).hello());
        check(flowKeys == 1 && dst.flow.isEmpty() && v23.hello.flow == null && v23.hello.water == null && FakeSim.knownScene("flood:steep") && FakeSim.knownScene("flood:cliff") && !FakeSim.knownScene("flood:wall"),
                "a dry world: the first frame after the hello carries the empty flow list and it is not sent again in 200 frames; the fake's \"@v23\" world still has no water model and no flow; "
                        + "the scenes flood:steep and flood:cliff exist");
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

    /**
     * Pixels that differ between one band and four WITH the film and do not differ without it: what the film adds to the
     * difference between the two. (The scene under it is not this test's business: in rain the build before Phase N can
     * differ between one band and four in a stray pixel, more when Marlin's path clipping is left on, which {@code Main}
     * switches off. The film must add none either way.)
     */
    private static int bandsAdded(int[] wet1, int[] wet4, int[] dry1, int[] dry4) {
        int n = 0;
        for (int k = 0; k < wet1.length; k++) if (wet1[k] != wet4[k] && dry1[k] == dry4[k]) n++;
        return n;
    }

    /** The same state drawn without its film (and put back). */
    private static int[] shotDry(ParallelRenderer pr, ViewerState st, Camera cam) {
        List<Model.Run> keep;
        synchronized (st) { keep = st.flow; st.flow = List.of(); }
        int[] out = shot(pr, st, cam);
        synchronized (st) { st.flow = keep; }
        return out;
    }

    /** A stream on a hand-made slope: ground at y 500 falling {@code slope} from x 400 to x 1200, one run of 14 cells from x 448 with the given depth and flux. */
    private static ViewerState slopeStream(double slope, double h, double q) {
        double[] t = new double[201];
        for (int i = 0; i <= 200; i++) t[i] = 500 + slope * Model.clamp(i * 8.0 - 400, 0, 800);
        ViewerState st = new ViewerState();
        st.acceptLine(hello(t, true, true).replaceAll("\"settlements\":\\[.*?\\}\\}\\]", "\"settlements\":[]"));
        StringBuilder hs = new StringBuilder(), qv = new StringBuilder();
        for (int i = 0; i < 14; i++) { if (i > 0) { hs.append(','); qv.append(','); } hs.append(h); qv.append(q); }
        st.acceptLine(frame(1, ",\"flow\":[{\"k0\":14,\"dir\":1,\"src\":0,\"dst\":-1,\"h\":[" + hs + "],\"q\":[" + qv + "]}],\"water\":[]"));
        return st;
    }

    /** {mean rows changed by the film per column, rows above the ribbon's top edge, rows below the ground, columns without any changed pixel} over world x {@code xa..xb}. */
    private static double[] measure(int[] wet, int[] dry, Camera.View v, Model.Terrain t, FlowPainter.State fl, double xa, double xb) {
        int cols = 0, gaps = 0, above = 0, below = 0;
        long rows = 0;
        for (int x = (int) Math.ceil(v.ws * xa + v.wtx); x <= (int) Math.floor(v.ws * xb + v.wtx); x++) {
            if (x < 0 || x >= W) continue;
            // the top edge the painter laid out: the highest of the ribbon's outline over this pixel's width (the ground where there is no ribbon)
            double wx = (x + 0.5 - v.wtx) / v.ws, ground = v.ws * t.at(wx) + v.wty, top = ground;
            for (FlowPainter.Piece p : fl.draw) {
                for (double sx = wx - 0.5 / v.ws; sx <= wx + 0.5 / v.ws + 1e-9; sx += 0.25 / v.ws) {
                    if (sx < p.x0 || sx > p.x1) continue;
                    int i = java.util.Arrays.binarySearch(p.x, sx);
                    double ty = i >= 0 ? p.top[i] : p.top[-i - 2] + (p.top[-i - 1] - p.top[-i - 2]) * (sx - p.x[-i - 2]) / (p.x[-i - 1] - p.x[-i - 2]);
                    top = Math.min(top, v.ws * ty + v.wty);
                }
            }
            int n = 0;
            for (int y = 0; y < H; y++) {
                if (wet[y * W + x] == dry[y * W + x]) continue;
                n++;
                if (y + 1 < top - 1.5) above++;
                if (y > ground + 1.5) below++;
            }
            cols++;
            rows += n;
            if (n == 0) gaps++;
        }
        return new double[]{cols == 0 ? 0 : rows / (double) cols, above, below, gaps};
    }

    private void thickness() {
        Advanced.publish(Advanced.DEFAULT);
        ParallelRenderer p1 = renderer(1), p4 = renderer(4);
        try {
            // ---- q = 5 / 34 / 127 px²/s on slope 0.22: the table's 0.63 / 1.99 / 4.39 px, drawn that thick
            final double[] qs = {5, 34, 127}, hs = {0.63, 1.99, 4.39};
            final double sec = Math.sqrt(1 + 0.22 * 0.22);
            double[] geo = new double[3], px1 = new double[3], px4 = new double[3];
            StringBuilder bad = new StringBuilder();
            int bandDiff = 0;
            for (int i = 0; i < 3; i++) {
                ViewerState st = slopeStream(0.22, hs[i], qs[i]);
                Model.Terrain t = st.hello.terrain;
                for (double zoom : new double[]{1, 4}) {
                    Camera cam = new Camera();
                    p1.layout(cam, st, W, H);
                    cam.set(688, t.at(688) - 20, zoom, true);
                    Camera.View v = cam.view(W, H, 1, 0, 0);
                    int[] wet = shot(p1, st, cam), wet4 = shot(p4, st, cam), dry = shotDry(p1, st, cam);
                    shot(p1, st, cam);                                           // (the film's layout again: measure reads it)
                    bandDiff += diff(wet, wet4);
                    double xa = zoom == 1 ? 480 : 560, xb = zoom == 1 ? 860 : 820;
                    double[] m = measure(wet, dry, v, t, p1.shared.flow, xa, xb);
                    if (zoom == 1) px1[i] = m[0]; else px4[i] = m[0] / 4;
                    if (m[1] > 0 || m[2] > 0 || m[3] > 0) bad.append(String.format(Locale.ROOT, " q %.0f at %.0fx: %.0f px above the top edge, %.0f below the ground, %.0f empty columns;", qs[i], zoom, m[1], m[2], m[3]));
                    // nothing of it beyond the run: its cells reach from x 432 to x 880
                    for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                        if (wet[y * W + x] == dry[y * W + x]) continue;
                        double wx = (x + 0.5 - v.wtx) / v.ws;
                        if (wx < 432 - 1.5 / zoom || wx > 880 + 1.5 / zoom) { bad.append(" a pixel beyond the run;"); y = H; break; }
                    }
                }
                FlowPainter.Piece pc = p1.shared.flow.draw.length == 1 ? p1.shared.flow.draw[0] : null;
                if (pc == null) { bad.append(" not one ribbon;"); continue; }
                int mid = pc.x.length / 2;
                geo[i] = pc.ground[mid] - pc.top[mid];
            }
            boolean sized = true;
            for (int i = 0; i < 3; i++) {
                if (Math.abs(geo[i] / (hs[i] * sec) - 1) > 0.01) sized = false;                    // the outline: h sqrt(1 + slope²) above the ground
                if (Math.abs(px4[i] - hs[i] * sec) > 0.45) sized = false;                        // the pixels at 4x (the rows that changed, / 4)
                if (px1[i] < hs[i] * sec - 0.25 || px1[i] > hs[i] * sec + 1.6) sized = false;    // at zoom 1 a part-covered row at each edge, and the slope across the pixel, count as rows
            }
            double ratioGeo = geo[2] / geo[0], ratioPx = px4[2] / px4[0];
            check(sized && bad.length() == 0 && bandDiff == 0 && Math.abs(ratioGeo / (4.39 / 0.63) - 1) < 0.01 && ratioPx > 5 && ratioPx < 8 && px1[2] > px1[1] + 1.5 && px1[1] > px1[0] + 0.7,
                    String.format(Locale.ROOT, "a stream of q 5 / 34 / 127 px²/s on slope 0.22 (the table's 0.63 / 1.99 / 4.39 px): its outline stands %.2f / %.2f / %.2f px above the ground "
                            + "(h sqrt(1 + slope²), within 1 %%); at 4x it changes %.2f / %.2f / %.2f world px of every column, at zoom 1 %.1f / %.1f / %.1f rows; the thick one is %.2f times the thin one "
                            + "(6.97; %.1f in pixels at 4x); nothing above the top edge, below the ground or beyond the run, no column without water; 1 band = 4 bands%s",
                            geo[0], geo[1], geo[2], px4[0], px4[1], px4[2], px1[0], px1[1], px1[2], ratioGeo, ratioPx, bad));

            // ---- the sheet of the pad vector on a level street: 6.9 px at the low end, 3 px 203 px from the high end
            double[] flat = new double[201];
            java.util.Arrays.fill(flat, 600);
            ViewerState sh = new ViewerState();
            sh.acceptLine(hello(flat, true, true).replace("\"x0\":800,\"x1\":1280", "\"x0\":304,\"x1\":1118"));
            final double c = FakeWater.KU_STREET * Math.sqrt(FakeWater.SLOPE_MIN);
            StringBuilder hv = new StringBuilder(), qv = new StringBuilder();
            for (int k = 10; k <= 35; k++) {
                double q = 0.1 * (k * 32 - 304);
                if (k > 10) { hv.append(','); qv.append(','); }
                hv.append(String.format(Locale.ROOT, "%.2f", Math.pow(q / c, 0.6)));
                qv.append(String.format(Locale.ROOT, "%.1f", q));
            }
            sh.acceptLine(frame(1, ",\"flow\":[{\"k0\":10,\"dir\":1,\"src\":2,\"dst\":-1,\"h\":[" + hv + "],\"q\":[" + qv + "]}],\"water\":[]"));
            Camera cam = new Camera();
            p1.layout(cam, sh, W, H);
            cam.set(1010, 560, 4, true);
            Camera.View v = cam.view(W, H, 1, 0, 0);
            int[] wet = shot(p1, sh, cam), dry = shotDry(p1, sh, cam);
            shot(p1, sh, cam);
            FlowPainter.State fl = p1.shared.flow;
            double[] end = measure(wet, dry, v, sh.hello.terrain, fl, 1108, 1118);
            Camera cam2 = new Camera();
            p1.layout(cam2, sh, W, H);
            cam2.set(507, 560, 4, true);
            int[] wet2 = shot(p1, sh, cam2), dry2 = shotDry(p1, sh, cam2);
            shot(p1, sh, cam2);
            double[] at203 = measure(wet2, dry2, cam2.view(W, H, 1, 0, 0), sh.hello.terrain, fl, 502, 512);
            check(Math.abs(fl.depthAt(1118) - 6.90) < 0.07 && Math.abs(fl.depthAt(507) - 3.0) < 0.06 && Math.abs(end[0] / 4 - 6.9) < 0.45 && Math.abs(at203[0] / 4 - 3.0) < 0.45
                            && end[1] + end[2] + at203[1] + at203[2] == 0 && fl.depthAt(320) < fl.depthAt(700) && fl.depthAt(700) < fl.depthAt(1100),
                    String.format(Locale.ROOT, "the sheet of the pad vector on a level street (q = 0.1 x, 81 px²/s at the low end): drawn %.2f px deep at the low end (6.9; %.2f px in pixels at 4x) and "
                            + "%.2f px 203 px from the high end (3.0; %.2f in pixels), deepest at the low end, resting on the street", fl.depthAt(1118), end[0] / 4, fl.depthAt(507), at203[0] / 4));
        } finally {
            p1.shutdown();
            p4.shutdown();
        }
    }

    /** A v2.4 stream keeps its ramp and its dashed trickle; the same stream with hello.flow has neither. */
    private void oldStream() throws Exception {
        Advanced.publish(Advanced.DEFAULT);
        int[] differing = new int[1];
        String fx = fixtures(differing);
        check(fx == null || differing[0] == 0, fx == null
                ? "the recorded v2.4 fixtures (viewer/snapshots/v241/fixtures) are not there: NOT CHECKED here that a v2.4 replay draws as before Phase N"
                : "two recorded v2.4 streams (the real simulation's city end over its curb, the old fake's village end, ramp and trickle in both) draw pixel for pixel as the build before Phase N drew them (" + fx + ")");
        Advanced.publish(Advanced.DEFAULT);
        ParallelRenderer p1 = renderer(1);
        try {
            // a street body standing 4.6 px deep against nothing on level ground at y 820 (the v2.4 curb), spilling; the ground falls away east of it
            double[] t = hollowGround();
            for (int i = 0; i <= 200; i++) if (i * 8 > 1280) t[i] += 0.2 * (i * 8 - 1280);
            String body = ",\"water\":[{\"id\":2,\"kind\":\"street\",\"settlement\":2,\"x\":1010.0,\"x0\":800.0,\"x1\":1280.0,\"y\":815.4,\"depth\":4.6,\"vol\":2208.0,\"spill\":true}]";
            int[][] pic = new int[2][], dryPic = new int[2][];
            Camera.View v = null;
            boolean[] flowOn = new boolean[2], waterFlow = new boolean[2];
            int[] pieces = new int[2];
            for (int m = 0; m < 2; m++) {
                ViewerState st = new ViewerState();
                st.acceptLine(hello(t, m == 1, true));
                st.acceptLine(frame(1, body));
                Camera cam = new Camera();
                p1.layout(cam, st, W, H);
                cam.set(1300, 800, 4, true);
                v = cam.view(W, H, 1, 0, 0);
                pic[m] = shot(p1, st, cam);
                flowOn[m] = p1.shared.flow.on;
                waterFlow[m] = p1.shared.water.flow;
                pieces[m] = p1.shared.flow.draw.length;
                synchronized (st) { st.water = List.of(); }
                dryPic[m] = shot(p1, st, cam);
            }
            // beyond the body's end (x > 1280): the trickle of the v2.4 picture, nothing in the v2.4.1 one. At the end: the ramp against the straight cut
            int[] beyond = new int[2], corner = new int[2];
            for (int m = 0; m < 2; m++) for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                if (pic[m][y * W + x] == dryPic[m][y * W + x]) continue;
                double wx = (x + 0.5 - v.wtx) / v.ws, wy = (y + 0.5 - v.wty) / v.ws;
                if (wx > 1281) beyond[m]++;
                if (wx > 1280 - 3 && wx <= 1280 && wy > 815.4 && wy < 817.4) corner[m]++;      // the top corner of the end: cut away by the ramp, filled by the straight end
            }
            check(!flowOn[0] && !waterFlow[0] && pieces[0] == 0 && beyond[0] > 60 && corner[0] < corner[1] / 3 && flowOn[1] && waterFlow[1] && beyond[1] == 0 && corner[1] > 60
                            && diff(dryPic[0], dryPic[1]) == 0,
                    String.format("a v2.4 stream (no hello.flow) draws as it did: its spilling street body ends in the ramp (%d px in the top corner of its end) and the dashed trickle runs on down the "
                            + "ground (%d px beyond x1), FlowPainter prepares nothing; the same stream with hello.flow has neither: the end is drawn as sent (%d px in that corner), nothing beyond it (%d)",
                            corner[0], beyond[0], corner[1], beyond[1]));
        } finally {
            p1.shutdown();
        }
    }

    /**
     * A recorded stream drawn as a still: the world alone, four bands, 1400 x 700. The reference pictures beside the
     * fixtures in {@code viewer/snapshots/v241} were drawn in exactly this way by the build before Phase N.
     */
    private static int[] replayPicture(File jsonl, double x, double y, double zoom) throws java.io.IOException {
        ViewerState st = new ViewerState();
        for (String line : Files.readAllLines(jsonl.toPath(), StandardCharsets.UTF_8)) if (!line.isBlank()) st.acceptLine(line);
        ParallelRenderer pr = renderer(4);
        try {
            return shot(pr, st, vcam(pr, st, x, y, zoom));
        } finally {
            pr.shutdown();
        }
    }

    /**
     * The two recorded v2.4 streams kept as fixtures (a real simulation's city end with its water over the curb, and the old
     * fake's village end; both with the ramp and the dashed trickle): this build draws them pixel for pixel as the build
     * before Phase N did. Returns what was found, for the check's text; null = a fixture or its picture is not there.
     */
    private String fixtures(int[] differing) throws java.io.IOException {
        final Object[][] fx = {{"v24-real-city-end", 2830.0, 930.0, 2.4}, {"v24-fake-village-end", 1340.0, 985.0, 4.0}};
        File dir = new File(root, "viewer/snapshots/v241/fixtures");
        StringBuilder say = new StringBuilder();
        // (the reference pictures were drawn by a fresh process started as Main starts: Marlin's path clipping off, medium clouds,
        // no Epilepsy-safe, default Advanced. The tests before this one may have set those statics; they are put back afterwards.)
        final int keepQuality = CloudPainter.quality;
        final boolean keepSafe = Renderer.epilepsySafe;
        CloudPainter.quality = 1;
        Renderer.epilepsySafe = false;
        try {
        for (Object[] f : fx) {
            File jsonl = new File(dir, f[0] + ".jsonl"), png = new File(dir, f[0] + ".before-phase-n.png");
            if (!jsonl.isFile() || !png.isFile()) return null;
            BufferedImage ref = javax.imageio.ImageIO.read(png);
            if (ref == null || ref.getWidth() != W || ref.getHeight() != H) return null;
            int[] want = new int[W * H], got = replayPicture(jsonl, (Double) f[1], (Double) f[2], (Double) f[3]);
            ref.getRGB(0, 0, W, H, want, 0, W);
            for (int k = 0; k < want.length; k++) want[k] |= 0xff000000;
            for (int k = 0; k < got.length; k++) got[k] |= 0xff000000;
            int n = diff(want, got);
            differing[0] += n;
            say.append(say.length() > 0 ? ", " : "").append(f[0]).append(": ").append(n).append(" px differ");
        }
        } finally {
            CloudPainter.quality = keepQuality;
            Renderer.epilepsySafe = keepSafe;
        }
        return say.toString();
    }

    /** A flood-scene world advanced {@code seconds}, raining {@code rate} hits/s per bucket for the first {@code rainS} of them. */
    private static ViewerState floodWorld(String scene, double rate, double rainS, double seconds, String... cmds) {
        FakeSim sim = new FakeSim(1234, 0.45, 3840, 1080, null, null, Double.NaN, Double.NaN, scene);
        ViewerState st = new ViewerState();
        st.acceptLine(sim.hello());
        sim.handle("{\"cmd\":\"rain\",\"rate\":" + rate + "}");
        for (String c : cmds) sim.handle(c);
        for (int k = 0; k < Math.round(seconds * 30); k++) {
            if (k == Math.round(rainS * 30)) sim.handle("{\"cmd\":\"rain\",\"rate\":0}");
            st.acceptLine(sim.step());
        }
        return st;
    }

    private void bodiesAndBands() {
        Advanced.publish(Advanced.DEFAULT);
        ParallelRenderer p1 = renderer(1), p4 = renderer(4);
        try {
            // ---- the acceptance case on the fake: the village's east edge, the road falling away, the hollow at the bottom
            ViewerState heavy = floodWorld("flood", 8, 60, 40, "{\"cmd\":\"drains\",\"mode\":\"small\"}"), thin = floodWorld("flood", 3.4, 60, 40, "{\"cmd\":\"drains\",\"settlement\":1,\"size\":4.7}");
            Model.Settlement vil = heavy.hello.settlements.get(0);
            Model.Terrain t = heavy.hello.terrain;
            Model.Body pool = null;
            synchronized (heavy) { for (Model.Body b : heavy.water) if (b.x0 > vil.x1 && (pool == null || b.x0 < pool.x0)) pool = b; }
            StringBuilder bad = new StringBuilder();
            int bandDiff = 0, gaps = 0;
            double[] rows = new double[2];
            boolean padBody = false;
            synchronized (heavy) { for (Model.Body b : heavy.water) if (b.x1 > vil.x0 && b.x0 < vil.x1) padBody = true; }
            for (double zoom : new double[]{1, 2.4, 4}) {
                Camera cam = new Camera();
                p1.layout(cam, heavy, W, H);
                cam.set(vil.x1 + 130, t.at(vil.x1 + 130) - 30 / zoom, zoom, true);
                Camera.View v = cam.view(W, H, 1, 0, 0);
                for (int m = 0; m < 2; m++) {
                    ViewerState st = m == 0 ? heavy : thin;
                    int[] wet = shot(p1, st, cam), wet4 = shot(p4, st, cam), dry = shotDry(p1, st, cam), dry4 = shotDry(p4, st, cam);
                    shot(p1, st, cam);
                    bandDiff += bandsAdded(wet, wet4, dry, dry4);
                    // the road from the village's edge to just above the pool: water in every column (no dashes), none under the ground
                    double xa = Math.max(v.wx0 + 2, vil.x1 - 60), xb = Math.min(v.wx1 - 2, pool.x0 - 6);
                    double[] ms = measure(wet, dry, v, t, p1.shared.flow, xa, xb);
                    gaps += (int) ms[3];
                    if (ms[2] > 0 || ms[1] > 0) bad.append(String.format(Locale.ROOT, " %s at %.1fx: %.0f px above the top edge, %.0f below the ground;", m == 0 ? "heavy" : "thin", zoom, ms[1], ms[2]));
                    if (zoom == 2.4) rows[m] = measure(wet, dry, v, t, p1.shared.flow, vil.x1 + 60, vil.x1 + 160)[0] / zoom;
                    // never inside a body's water: within a body's span the film changes nothing below its level
                    List<Model.Body> bodies;
                    synchronized (st) { bodies = st.water; }
                    int inside = 0;
                    for (Model.Body b : bodies) {
                        for (int x = (int) Math.ceil(v.ws * (b.x0 + 0.5) + v.wtx); x <= (int) Math.floor(v.ws * (b.x1 - 0.5) + v.wtx); x++) {
                            if (x < 0 || x >= W) continue;
                            for (int y = (int) Math.ceil(v.ws * b.y + v.wty + 1.5); y < H; y++) if (y >= 0 && wet[y * W + x] != dry[y * W + x]) {
                                if (inside++ == 0) bad.append(String.format(Locale.ROOT, " [first: body %d %.1f..%.1f level %.2f, world %.1f,%.1f]", b.id, b.x0, b.x1, b.y, (x + 0.5 - v.wtx) / v.ws, (y + 0.5 - v.wty) / v.ws));
                            }
                        }
                    }
                    if (inside > 0) bad.append(String.format(Locale.ROOT, " %s at %.1fx: %d px of film inside a body;", m == 0 ? "heavy" : "thin", zoom, inside));
                }
            }
            // the ribbons themselves: none overlaps a body's span, none is more than its depth above the ground
            shot(p1, heavy, vcam(p1, heavy, vil.x1 + 130, t.at(vil.x1 + 130), 1));
            double worstOver = 0, worstLift = 0;
            for (FlowPainter.Piece p : p1.shared.flow.draw) {
                for (WaterPainter.Body b : p1.shared.water.draw) worstOver = Math.max(worstOver, Math.min(p.x1, b.x1) - Math.max(p.x0, b.x0));
                for (int i = 0; i < p.x.length; i++) worstLift = Math.max(worstLift, (p.ground[i] - p.top[i]) - p.depth[i] * FlowPainter.SEC_MAX);
            }
            double hThin = p1.shared.flow.depthAt(vil.x1 + 112);
            FlowPainter.State hs = p1.shared.flow;
            check(bad.length() == 0 && gaps == 0 && bandDiff == 0 && !padBody && pool != null && rows[0] > rows[1] * 1.8 && rows[1] > 0.5 && worstOver <= 0.01 && worstLift <= 1e-9
                            && Math.abs(t.at(pool.x0) - pool.y) < 0.1 && hs.depthAt(vil.x1 - 16) > 3,
                    String.format(Locale.ROOT, "the screenshot case on the fake (the village's east edge, the road falling away, 40 s into the rain) at zoom 1, 2.4 and 4: water in every column of the road "
                            + "from the pad to the pool (no gap, no dashes), %.1f px thick under 8 hits/s on small drains against %.1f px under 3.4 hits/s on 4.7 px drains; no body stands on the pad "
                            + "(its water is the sheet, %.1f px at the low end); the pool at the bottom ends where its level meets the ground; no film inside a body, none under the ground, none above "
                            + "its depth; 1 band = 4 bands in all six pictures (the film adds no differing pixel)%s", rows[0], rows[1], hs.depthAt(vil.x1 - 16), bad));

            // ---- no body end above lower ground: every body the viewer draws on the v2.4.1 fake, and of a hand-written stream
            ViewerState hand = new ViewerState();
            hand.acceptLine(hello(hollowGround(), true, true));
            hand.acceptLine(frame(1, ",\"water\":[{\"id\":9,\"kind\":\"pool\",\"x\":400.0,\"x0\":322.5,\"x1\":477.5,\"y\":828.4,\"depth\":5.6,\"vol\":520.0}],\"flow\":[]"));
            double worstEnd = 0;
            int bodies = 0;
            for (ViewerState st : new ViewerState[]{heavy, thin, hand, floodWorld("flood", 8, 100, 100, "{\"cmd\":\"drains\",\"mode\":\"small\"}")}) {
                shot(p1, st, vcam(p1, st, 1200, 800, 1));
                Model.Terrain tt = st.hello.terrain;
                for (WaterPainter.Body b : p1.shared.water.draw) {
                    bodies++;
                    worstEnd = Math.max(worstEnd, Math.max(tt.at(b.x0) - b.y, tt.at(b.x1) - b.y));          // how deep the water is at its own end
                }
            }
            check(bodies >= 12 && worstEnd < 0.15 && WaterPainter.RAMP > 0,
                    String.format(Locale.ROOT, "no water stands above lower ground on a v2.4.1 stream: all %d bodies drawn (the fake at 40 s and 100 s, a hand-written stream) end where their level "
                            + "meets the ground (the water is at most %.2f px deep at an end; behind the v2.4 curb it was up to 10 px)", bodies, worstEnd));

            // ---- white water on steep ground, none on a gentle run under the same thin stream; the cliff falls free
            ViewerState steep = floodWorld("flood:steep", 3.4, 60, 40, "{\"cmd\":\"drains\",\"settlement\":1,\"size\":4.7}"), cliff = floodWorld("flood:cliff", 8, 60, 40, "{\"cmd\":\"drains\",\"mode\":\"small\"}");
            double[] whiteMax = new double[2];
            int[] whiteCells = new int[2], falls = new int[3];
            ViewerState[] three = {thin, steep, cliff};
            int whiteDiff = 0;
            for (int m = 0; m < 3; m++) {
                Camera cam = vcam(p1, three[m], vil.x1 + 60, t.at(vil.x1) + 20, 3);
                int[] a = shot(p1, three[m], cam), b = shot(p4, three[m], cam);
                whiteDiff += bandsAdded(a, b, shotDry(p1, three[m], cam), shotDry(p4, three[m], cam));
                shot(p1, three[m], cam);
                for (FlowPainter.Piece p : p1.shared.flow.draw) {
                    if (p.fall != null && p.x1 > vil.x1 && p.x0 < vil.x1 + 120) falls[m]++;
                    if (m == 2 || p.x1 < vil.x1 + 32 || p.x0 > vil.x1 + 100) continue;
                    for (int i = 0; i < p.frac.length; i++) { double x = p.x0 + p.frac[i] * (p.x1 - p.x0); if (x > vil.x1 + 40 && x < vil.x1 + 84) whiteMax[m] = Math.max(whiteMax[m], p.sw[i]); }
                    for (double wx : p.wx) if (wx > vil.x1 + 32 && wx < vil.x1 + 90) whiteCells[m]++;
                }
            }
            check(whiteMax[0] == 0 && whiteCells[0] == 0 && whiteMax[1] >= 0.99 && whiteCells[1] >= 1 && falls[0] == 0 && falls[1] == 0 && falls[2] == 1 && whiteDiff == 0
                            && FlowPainter.CLIFF_SLOPE == 2.0 && FlowPainter.WHITE_SLOPE == 0.6,
                    String.format(Locale.ROOT, "the same thin stream is plain water on the gentle run (slope 0.225, whiteness %.2f) and white water with foam on the steep one (slope 0.9, whiteness %.2f, "
                            + "%d foaming cell(s)); over the cliff (slope 3.5 >= CLIFF_SLOPE 2.0) the water leaves the ground in a free fall (falls: gentle %d, steep %d, cliff %d); 1 band = 4 bands (the film adds %d differing px)",
                            whiteMax[0], whiteMax[1], whiteCells[1], falls[0], falls[1], falls[2], whiteDiff));

            // ---- a fall is no longer than its cliff: a long steep flank is a cascade of short falls (as one arc it stood in the air beside
            // the mountain, as tall as the mountain), and no fall goes up to a pool that stands above its lip
            double[] flankG = new double[201], notchG = new double[201];
            for (int i = 0; i <= 200; i++) {
                double x = i * 8.0;
                flankG[i] = 500 + 3 * Model.clamp(x - 800, 0, 96);                                              // (slope 3 over 96 px: steep enough to fall, not a cliff)
                notchG[i] = x <= 800 ? 600 : x <= 808 ? 800 : x <= 816 ? 490 : x < 1008 ? 510 : 490;            // (a 200 px cliff into a notch; behind the wall beyond it a pool, 100 px above the lip)
            }
            ViewerState flank = new ViewerState(), notch = new ViewerState();
            flank.acceptLine(hello(flankG, true, true));
            flank.acceptLine(frame(1, ",\"water\":[],\"flow\":[{\"k0\":22,\"dir\":1,\"src\":0,\"dst\":-1,\"h\":[2,2,2,2,2,2,2,2,2],\"q\":[60,60,60,60,60,60,60,60,60]}]"));
            notch.acceptLine(hello(notchG, true, true));
            notch.acceptLine(frame(1, ",\"water\":[{\"id\":9,\"kind\":\"pool\",\"x\":912.0,\"x0\":820.0,\"x1\":1004.0,\"y\":500.0,\"depth\":10.0,\"vol\":1800.0}],"
                    + "\"flow\":[{\"k0\":22,\"dir\":1,\"src\":0,\"dst\":9,\"h\":[2,2,2,2],\"q\":[60,60,60,60]}]"));
            double fallWide = 0, fallTall = 0, fallRise = 0;
            int flankFalls = 0, notchFalls = 0;
            ViewerState[] two = {flank, notch};
            for (int m = 0; m < 2; m++) {
                shot(p1, two[m], vcam(p1, two[m], 850, 650, 2));
                for (FlowPainter.Piece p : p1.shared.flow.draw) {
                    if (p.fall == null) continue;
                    for (double[] f : fallRows(p.fall)) {
                        if (m == 0) { flankFalls++; fallWide = Math.max(fallWide, f[1] - f[0]); fallTall = Math.max(fallTall, f[3] - f[2]); } else notchFalls++;
                        fallRise = Math.max(fallRise, f[4] - f[2]);
                    }
                }
            }
            final double fallReach = 14, fallW = 32 + fallReach;       // (a cell and the longest reach beyond the foot)
            check(flankFalls >= 3 && fallWide <= fallW + 1e-6 && fallTall <= 3 * fallW + 2 + 1e-6 && notchFalls >= 1 && fallRise < 1e-6,
                    String.format(Locale.ROOT, "a fall is no longer than its cliff: down a flank of slope 3 that is 96 px wide and 288 px tall the water falls in %d falls, the widest %.1f px (at most a cell and "
                            + "its reach, %.0f px) and the tallest %.1f px (as one arc: 1 fall, 110 px wide, 290 px tall, about 40 px off the slope); beside a pool that stands 100 px above the lip "
                            + "(%d fall(s) there) no fall rises above the water it starts from (%.1f px; it went 98 px up to that pool)", flankFalls, fallWide, fallW, fallTall, notchFalls, fallRise));

            // ---- the Advanced switches of Phase J leave the stream alone: the pixels the film changes are the same with all of them on
            ViewerState after = floodWorld("flood", 8, 30, 36, "{\"cmd\":\"drains\",\"mode\":\"small\"}");
            Camera cam = vcam(p1, after, vil.x1 + 130, t.at(vil.x1 + 130) - 12, 2.4);
            int[] w0 = shot(p1, after, cam), d0 = shotDry(p1, after, cam);
            Advanced.publish(Advanced.DEFAULT.parse("noPuddleImpact,noShine,noTrails,noFlash,lightningDetailOff,glintStar"));
            int[] w1 = shot(p1, after, cam), d1 = shotDry(p1, after, cam);
            Advanced.publish(Advanced.DEFAULT);
            int n0 = 0, n1 = 0, differ = 0;
            for (int k = 0; k < w0.length; k++) {
                boolean a = w0[k] != d0[k], b = w1[k] != d1[k];
                if (a) n0++;
                if (b) n1++;
                if (a != b || (a && d0[k] == d1[k] && w0[k] != w1[k])) differ++;
            }
            check(n0 > 2000 && n0 == n1 && differ == 0, "the Advanced switches (no puddle impacts, no shine, no trails, no flash, plain lightning, star glint) leave the stream alone: the film changes the same "
                    + n0 + " pixels, to the same colours, with all of them on");
        } finally {
            Advanced.publish(Advanced.DEFAULT);
            p1.shutdown();
            p4.shutdown();
        }
    }

    /** The free falls of a piece, one row each: {x0, x1, top, bottom, the y it starts at (the water surface at its lip)}. */
    private static List<double[]> fallRows(java.awt.geom.Path2D.Double path) {
        List<double[]> out = new ArrayList<>();
        double[] c = new double[6], cur = null;
        for (java.awt.geom.PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            int seg = it.currentSegment(c);
            if (seg == java.awt.geom.PathIterator.SEG_MOVETO) out.add(cur = new double[]{c[0], c[0], c[1], c[1], c[1]});
            else if (seg == java.awt.geom.PathIterator.SEG_LINETO && cur != null) { cur[0] = Math.min(cur[0], c[0]); cur[1] = Math.max(cur[1], c[0]); cur[2] = Math.min(cur[2], c[1]); cur[3] = Math.max(cur[3], c[1]); }
        }
        return out;
    }

    private static Camera vcam(ParallelRenderer pr, ViewerState st, double x, double y, double zoom) {
        Camera cam = new Camera();
        pr.layout(cam, st, W, H);
        cam.set(x, y, zoom, true);
        return cam;
    }

    private void easing() {
        FlowPainter.State s = new FlowPainter.State();
        s.on = true;
        s.n = 40;
        s.h = new double[40]; s.th = new double[40]; s.q = new double[40]; s.tq = new double[40]; s.alpha = new double[40];
        s.dir = new byte[40]; s.live = new boolean[40];
        Model.Run a = new Model.Run();
        a.k0 = 10; a.dir = 1; a.h = new double[]{1, 1, 1}; a.q = new double[]{10, 10, 10};
        FlowPainter.step(s, List.of(a), 0, false);
        double alpha0 = s.alpha[10], h0 = s.h[10];
        for (int k = 0; k < 9; k++) FlowPainter.step(s, null, 1 / 60.0, false);
        double alphaMid = s.alpha[11];
        for (int k = 0; k < 12; k++) FlowPainter.step(s, null, 1 / 60.0, false);
        Model.Run b = new Model.Run();
        b.k0 = 10; b.dir = 1; b.h = new double[]{3, 3}; b.q = new double[]{40, 40};
        FlowPainter.step(s, List.of(b), 0, false);
        double maxStep = 0, prev = s.h[10], after12 = 0, after30 = 0;
        boolean mono = true;
        for (int k = 1; k <= 30; k++) {
            FlowPainter.step(s, null, 1 / 60.0, false);
            if (s.h[10] < prev - 1e-12) mono = false;
            maxStep = Math.max(maxStep, s.h[10] - prev);
            prev = s.h[10];
            if (k == 12) after12 = prev;
            if (k == 30) after30 = prev;
        }
        // cell 12 left the list: it keeps its depth and fades out
        int alive = 0;
        double fadeMid = 0, keptH = 0;
        FlowPainter.State s2 = new FlowPainter.State();
        s2.on = true; s2.n = 40;
        s2.h = new double[40]; s2.th = new double[40]; s2.q = new double[40]; s2.tq = new double[40]; s2.alpha = new double[40]; s2.dir = new byte[40]; s2.live = new boolean[40];
        FlowPainter.step(s2, List.of(a), 0, true);
        boolean snapped = s2.alpha[10] == 1 && s2.h[12] == 1;
        FlowPainter.step(s2, List.of(), 0, false);
        for (int k = 1; k <= 24 && s2.alpha[12] > 0; k++) { FlowPainter.step(s2, null, 1 / 60.0, false); alive = k; if (k == 9) { fadeMid = s2.alpha[12]; keptH = s2.h[12]; } }
        check(alpha0 == 0 && h0 == 1 && Math.abs(alphaMid - 0.5) < 0.02 && mono && maxStep < 0.3 && maxStep > 0.1 && Math.abs(after12 - 3) < 0.4 && Math.abs(after30 - 3) < 0.04 && snapped
                        && Math.abs(fadeMid - 0.5) < 0.02 && keptH == 1 && alive >= 17 && alive <= 19 && s2.h[12] == 0 && FlowPainter.EASE_S == 0.12 && FlowPainter.FADE_S == 0.30,
                String.format(Locale.ROOT, "easing at 60 frames/s: a new cell appears at its depth and is half there after 0.15 s; a depth that jumps from 1 to 3 px moves at most %.2f px in a frame, "
                        + "is within %.2f px after 0.2 s and %.3f px after 0.5 s, never backwards; a cell that left the list keeps its depth and is gone after %d frames (0.3 s); a still picture snaps",
                        maxStep, Math.abs(after12 - 3), Math.abs(after30 - 3), alive));
    }

    // ------------------------------------------------------------------ the Debug mode's Flood tab

    private static int bandThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.isAlive() && t.getName().startsWith("render-band-")) n++;
        return n;
    }

    private void floodTab() throws Exception {
        boolean[] ok = new boolean[6];
        String[] say = new String[4];
        int threads0 = bandThreads();
        edt(() -> {
            DebugScreen ds = DebugScreen.open(app, true);
            ds.setTab(DebugScreen.FLOOD);
            DebugFlood.FloodTab ft = ds.flood;
            ds.picture(1040, 620, 1);
            ok[0] = ds.lastError == null && ft.pane != null && ft.town[0] >= 0 && ft.town[1] >= 0 && ft.town[2] >= 0 && ft.slopeIdx == 0 && ft.flowAt(ft.probeX()) == null
                    && ft.pane.hello().flow != null && ft.pane.hello().drains.curb == 0;
            // the same slope under the steady-rain mark and under the supercell mark
            double[] h = new double[2];
            for (int m = 0; m < 2; m++) {
                ft.option("rate", m == 0 ? "3.4" : "8");
                ft.option("rain", "on");
                ft.option("run", "40");
                ds.picture(1040, 620, 1);
                double[] c = ft.flowAt(ft.probeX());
                h[m] = c == null ? 0 : c[0];
            }
            BufferedImage big = ds.picture(1680, 760, 1);
            Model.Flood v = ft.flood(0), hm = ft.flood(2);
            boolean vilBody = false, hamBody = false;
            synchronized (ft.pane.st) {
                for (Model.Body b : ft.pane.st.water) { if (b.street() && b.settlement == ft.town[0]) vilBody = true; if (b.street() && b.settlement == ft.town[2]) hamBody = true; }
            }
            ok[1] = ds.lastError == null && h[0] > 0.3 && h[1] > h[0] * 1.4 && big.getWidth() == 1680;
            ok[2] = !vilBody && hamBody && v != null && !v.backed && hm != null && hm.depth > 2;
            say[0] = String.format(Locale.ROOT, "%.2f px under the steady-rain mark (3.4), %.2f px under the supercell mark (8)", h[0], h[1]);
            say[1] = v == null || hm == null ? "?" : String.format(Locale.ROOT, "the village is %s at %.1f px with no body of its own, the hamlet holds a street body (%s, %.1f px)", v.state, v.depth, hm.state, hm.depth);
            // the slope control builds the world again; the pickers move the view
            double x0 = ft.pane.cam.cx;
            ft.option("pick", "hamlet");
            BufferedImage ham = ds.picture(1040, 620, 1);
            boolean moved = ft.pick == 2 && Math.abs(ft.pane.cam.cx - x0) > 200;
            ft.option("slope", "steep");
            ft.option("pick", "village");
            ft.option("rate", "8");
            ft.option("rain", "on");
            ft.option("run", "30");
            BufferedImage steep = ds.picture(1040, 620, 1);
            double steepSlope = Math.abs(ft.pane.hello().terrain.slope(ft.probeX() - 20));
            ft.option("slope", "cliff");
            ft.option("rain", "on");
            ft.option("run", "30");
            ds.picture(1040, 620, 1);
            double cliffSlope = 0;
            Model.Settlement vs = null;
            for (Model.Settlement s : ft.pane.hello().settlements) if (s.id == ft.town[0]) vs = s;
            for (double x = vs.x1; x < vs.x1 + 80; x += 4) cliffSlope = Math.max(cliffSlope, Math.abs(ft.pane.hello().terrain.slope(x)));
            ok[3] = ds.lastError == null && moved && ft.slopeIdx == 2 && Math.abs(steepSlope - 0.9) < 0.01 && cliffSlope >= FlowPainter.CLIFF_SLOPE && differs(ham, steep) > 3000
                    && DebugFlood.FloodTab.SLOPE_NAMES.length == 3 && DebugFlood.FloodTab.PICKS.length == 3;
            say[2] = String.format(Locale.ROOT, "steep %.2f, cliff %.1f", steepSlope, cliffSlope);
            ds.setTab(DebugScreen.CLOUDS);
            ok[4] = ft.pane == null && ft.history.isEmpty();
            ds.dispose();
            app.leaveNow();
        });
        app.awaitStops();
        int threads1 = bandThreads();
        for (int k = 0; k < 40 && threads1 > threads0; k++) { Thread.sleep(50); threads1 = bandThreads(); }
        check(ok[0], "the Flood tab opens on the v2.4.1 fake at rest: three settlements, the gentle run, a dry road, no curb, no exception at 1040x620");
        check(ok[1], "on the same slope the stream beyond the village is " + say[0] + ": visibly thicker; the tab paints at 1040x620 and 1680x760");
        check(ok[2], "in the tab " + say[1] + ": the hilltop sheds, the hollow holds");
        check(ok[3], "the settlement picker moves the upper picture to the hamlet; the slope control builds the world again with the road beyond the village steep or a cliff (" + say[2] + ")");
        check(ok[4] && threads1 <= threads0, "leaving the tab lets go of its world, its renderers and their threads (" + threads0 + " band threads before, " + threads1 + " after)");
    }

    private static int differs(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y += 2) for (int x = 0; x < a.getWidth(); x += 2) if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
        return n;
    }
}
