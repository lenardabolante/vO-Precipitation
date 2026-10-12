package precip;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Protocol v2.4.1 water for the built-in fake simulation: standing water, the film that runs over the ground, storm
 * drains and flooding, as a Java copy of the water model of {@code sim/water.py} (PLAN.md, Phase N, "The model in
 * numbers"), so that the viewer and the Debug mode's Flood tab run without Python.
 * <p>
 * Water is area in the 2-D world (px²). <b>There is no virtual curb</b>: a street is ordinary ground with drains.
 * Rain is tallied in 32 px cells. A cell that lies under a body's surface (or on the floor of its hollow) hands its
 * rain to that body; every other cell, a street's included, keeps it as a <i>film</i> of depth {@code h = S / 32}
 * that runs downhill, cell by cell, at {@code u = KU sqrt(slope) h^(2/3)} to the lowest ground it can reach. A body
 * is a level surface over its hollow; it loses {@code SOAK x wetted width} a second and, where it covers a street,
 * what the inlets under its surface take; at its real rim the excess goes into the first cell beyond as film, and
 * two hollows that are both full to the rim between them become one body. A street's drains take water from the
 * film on its cells. A street holds standing water only if the terrain makes it a hollow.
 * <p>
 * <b>Simpler than the real model</b> (it is a stand-in, like the rest of {@link FakeSim}): bodies that merged stay
 * one body until it has dried up; a pond absorbs the film that reaches its cells (and the floor of a hollow whose bed
 * it is) and counts as lost water; nothing is saved.
 */
final class FakeWater {
    // ------------------------------------------------------------------ mirror of sim/water.py (one block: keep in step with the model table)
    static final double HIT_AREA = 1.15;         // px² of water one landed rain particle adds (as shipped: LS6 tuned it; the plan's model table says 1.0)
    static final double BASIN_MIN_DEPTH = 1.0;   // a hollow shallower than this is not a hollow; a cell this close to a hollow's bed is a sink
    static final double SOAK = 0.015;            // px/s lost per px of wetted width (soaking away, evaporation)
    static final double MIN_VOL = 0.3;           // a body below this is removed
    static final double CURB = 0;                // v2.4.1: there is no curb (hello.drains.curb is 0.0)
    static final double INLET_SPACING = 60, FLOOD_DEPTH = 3, OVERFLOW_DEPTH = 8;
    static final double WET_DEPTH = 0.05;        // below this a street counts as having no water, and a film cell is not sent
    static final double INLET_K = 0.2667;        // one inlet removes INLET_K x size² px²/s
    static final double SIZE_MIN = 1.5, SIZE_MAX = 12;
    static final double SMALL = 3.0, NORMAL = 4.7, LARGE = 8.2;
    static final double RANDOM_LO = 2.5, RANDOM_HI = 10;
    /** The class borders (nearest class by ratio): below {@code CLASS_SMALL_BELOW} small, from {@code CLASS_LARGE_FROM} large. */
    static final double CLASS_SMALL_BELOW = 3.76, CLASS_LARGE_FROM = 6.2;
    static final double SUB_STEP = 0.25;         // the longest step the volumes are advanced by, s
    static final int PROCESS_EVERY = 3, WATER_EVERY = 3, FLOOD_EVERY = 6, FLOW_EVERY = 3;
    static final double INFLOW_WINDOW_S = 2.0;
    static final int BUCKET = 32;                // the film's cell: cell k is centred at x = 32 k (the rain bucket k)
    // the film (Phase N; starting values of the model table, NS6 re-tunes them in sim/water.py)
    static final double KU = 23, KU_STREET = 23; // u = KU sqrt(slope) h^(2/3), px/s; a street's own constant
    static final double SLOPE_MIN = 0.02;        // level ground still runs, this slowly
    static final double FILM_SOAK = 0;           // px²/s lost per wet cell
    static final double U_MAX = BUCKET / SUB_STEP;       // the explicit step is safe while u dt / 32 <= 1
    static final double FLOW_H_STEP = 0.05, FLOW_SEND_H = 0.1, FLOW_SEND_Q = 0.10;
    static final int RUN_MAX = 40;               // cells in one run of frame.flow
    static final String[] MODES = {"small", "normal", "large", "random"};
    // ------------------------------------------------------------------ end of the mirror

    /** The size range of {@code normal} mode by settlement kind: {lo, hi} px. */
    static double[] kindRange(String kind) {
        return switch (kind) {
            case "hamlet" -> new double[]{2.0, 4.0};
            case "town" -> new double[]{3.5, 6.5};
            case "city" -> new double[]{4.0, 8.0};
            default -> new double[]{3.0, 5.5};       // village
        };
    }

    /** {@code (crc32(text) & 0xFFFFFF) / 0xFFFFFF}: the seeded 0..1 of the model table (never the world's random stream). */
    static double unit(String text) {
        CRC32 c = new CRC32();
        c.update(text.getBytes(StandardCharsets.UTF_8));
        return (c.getValue() & 0xFFFFFF) / (double) 0xFFFFFF;
    }

    /** The pipe size a mode gives a settlement, px. */
    static double sizeFor(String mode, long seed, long id, String kind) {
        switch (mode) {
            case "small": return SMALL;
            case "large": return LARGE;
            case "random": return RANDOM_LO + unit(seed + ":" + id + ":drain-random") * (RANDOM_HI - RANDOM_LO);
            default: {
                double[] r = kindRange(kind);
                return r[0] + unit(seed + ":" + id + ":drain") * (r[1] - r[0]);
            }
        }
    }

    static String classOf(double size) { return size < CLASS_SMALL_BELOW ? "small" : size >= CLASS_LARGE_FROM ? "large" : "normal"; }

    static int inletCount(double x0, double x1) { return (int) Math.max(1, Math.round((x1 - x0) / INLET_SPACING)); }

    static double capOf(int inlets, double size) { return inlets * INLET_K * size * size; }

    /** The steady depth of a film that carries {@code q} px²/s down a slope: {@code (q / (ku sqrt(slope)))^0.6} (the model table's vector). */
    static double steadyDepth(double q, double slope, double ku) { return Math.pow(q / (ku * Math.sqrt(Math.max(SLOPE_MIN, slope))), 0.6); }

    /** A settlement: its drains, and what the flood report says of its street. It owns no water: that is film on its cells, or a body. */
    static final class Town {
        final long id;
        final String kind;
        final double x0, x1;
        double size, cap;
        int inlets;
        /** The first and last cell whose centre lies on the span (kB < kA = none). */
        int kA, kB = -1;
        /** The greatest water depth on the span (film or body), px; px²/s entering the span, 2 s mean. */
        double depth, inflow;
        /** True while a body that came from a hollow covers part of the span. */
        boolean backed;
        /** The street's own body when the street is a hollow that holds water (else null). */
        Pool body;
        String state = "dry";
        private double tickIn, logDt, logSum;
        private final ArrayDeque<double[]> log = new ArrayDeque<>();     // {dt, px²} per tick, the last 2 s

        Town(long id, String kind, double x0, double x1) { this.id = id; this.kind = kind; this.x0 = x0; this.x1 = x1; }

        boolean flooded() { return state.equals("flooded") || state.equals("overflowing"); }
    }

    /** A hollow of the heightmap between two ridges (sample indices), as found once from the terrain. */
    private static final class Basin {
        int left, right, pit, next = -1;
        double sillY, vmax;
    }

    /** A body: one hollow, or several that have filled to the rims between them. */
    static final class Pool {
        long id;                       // 0 until it holds water
        final List<Integer> basins = new ArrayList<>();
        int left, right, pit, next = -1;
        double sillY, vmax, vol, pend;
        double level, depth, x0, x1, xDeep;
        /** Full to its rim; running over it (the excess leaves as film beyond the rim). */
        boolean full, spill, rained;
        /** The settlement whose street this hollow is (its bed lies on that span), or -1: a natural pool. */
        long street = -1;
    }

    private final double[] y;          // terrain, y grows down
    private final double step;
    private final int worldW;
    private final long seed;
    final List<Town> towns = new ArrayList<>();
    private final List<Basin> basins = new ArrayList<>();
    private Pool[] owner = new Pool[0];
    final List<Pool> pools = new ArrayList<>();
    // ---- the film: one volume per 32 px cell
    private final int nCells;
    /** Water on each cell, px² (depth = S / 32); the flux that left it in the last sub-step, px²/s. */
    private final double[] film, flux;
    private final double[] cellY, cellSlope;
    /** Per cell: the direction it runs (+1 / -1), the hollow it runs into (-1 = off the nearer world edge), its settlement (index, -1 = none), in a pond. */
    private final int[] cellDir, cellBasin, cellTown;
    private final boolean[] cellPond;
    private final double[] moved;
    String mode = "normal";
    private long nextId;
    private int ticks;
    private double accDt;
    // emit bookkeeping
    private boolean waterFirst = true, floodFirst = true, flowFirst = true, drainsDue, sentAllDry, sentNoFlow;
    private int sinceWater = WATER_EVERY, sinceFlood = FLOOD_EVERY, sinceFlow = FLOW_EVERY;
    private final Map<Long, double[]> sentWater = new HashMap<>();
    private final Map<Long, Object[]> sentFlood = new HashMap<>();
    private final double[] sentH, sentQ;
    /** Totals for the conservation check: rained in, soaked away, taken by the drains, lost over a world edge or into a pond. */
    double totalIn, totalSoak, totalDrain, totalLost;

    /**
     * @param terrain  ground-surface y every {@code step} px
     * @param settlements {id, x0, x1} with the kind in {@code kinds}, in id order
     * @param ponds    {x0, x1} of every pond object: a closed hollow, water that runs into it is absorbed
     */
    FakeWater(double[] terrain, double step, int worldW, long seed, List<double[]> settlements, List<String> kinds, List<double[]> ponds, String mode) {
        this.y = terrain;
        this.step = step;
        this.worldW = worldW;
        this.seed = seed;
        this.mode = java.util.Arrays.asList(MODES).contains(mode) ? mode : "normal";
        nCells = (int) Math.ceil(worldW / (double) BUCKET) + 1;
        film = new double[nCells]; flux = new double[nCells]; moved = new double[nCells];
        cellY = new double[nCells]; cellSlope = new double[nCells];
        cellDir = new int[nCells]; cellBasin = new int[nCells]; cellTown = new int[nCells];
        cellPond = new boolean[nCells];
        sentH = new double[nCells]; sentQ = new double[nCells];
        long maxId = 0;
        for (int k = 0; k < settlements.size(); k++) {
            double[] s = settlements.get(k);
            Town t = new Town((long) s[0], kinds.get(k), s[1], s[2]);
            t.inlets = inletCount(t.x0, t.x1);
            setSize(t, sizeFor(this.mode, seed, t.id, t.kind));
            towns.add(t);
            maxId = Math.max(maxId, t.id);
        }
        nextId = maxId + 1;
        findBasins();
        layCells(ponds);
    }

    /**
     * The Phase L test vector as a fixture: a flat street {@code span} px wide at world y 1000 between two real walls
     * 10 px high, so the street is a hollow of 10 px and holds a body (a street has no curb of its own any more).
     */
    static FakeWater walledPad(double span, double size) {
        int n = (int) Math.ceil(span + 640) + 1;
        double[] t = new double[n];
        java.util.Arrays.fill(t, 990);
        for (int i = 304; i <= 304 + (int) span; i++) t[i] = 1000;
        List<double[]> st = new ArrayList<>();
        st.add(new double[]{1, 304, 304 + span});
        FakeWater w = new FakeWater(t, 1, n - 1, 1, st, List.of("town"), new ArrayList<>(), "normal");
        w.setSize(w.towns.get(0), size);
        return w;
    }

    /**
     * The model table's sheet vector as a fixture: a level street {@code span} px wide with no walls and no hollow
     * (its water runs off the east end as a sheet), its first cell starting at the span's west end.
     * @param drains false = the drains take nothing (the vector is given as a net excess)
     */
    static FakeWater flatPad(double span, double size, boolean drains) {
        int n = (int) Math.ceil((span + 1200) / 8) + 1;
        double[] t = new double[n];
        java.util.Arrays.fill(t, 1000);
        List<double[]> st = new ArrayList<>();
        st.add(new double[]{1, 304, 304 + span});
        FakeWater w = new FakeWater(t, 8, (n - 1) * 8, 1, st, List.of("town"), new ArrayList<>(), "normal");
        w.setSize(w.towns.get(0), size);
        if (!drains) w.towns.get(0).cap = 0;
        return w;
    }

    /** A uniform slope with nothing on it, falling to the east (the model table's film vector): {@code len} px long. */
    static FakeWater slope(double slope, double len) {
        int n = (int) Math.ceil(len / 8) + 1;
        double[] t = new double[n];
        for (int i = 0; i < n; i++) t[i] = 500 + slope * i * 8;
        return new FakeWater(t, 8, (n - 1) * 8, 1, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), "normal");
    }

    private void setSize(Town t, double size) {
        t.size = Model.clamp(size, SIZE_MIN, SIZE_MAX);
        t.cap = capOf(t.inlets, t.size);
    }

    Town town(long id) {
        for (Town t : towns) if (t.id == id) return t;
        return null;
    }

    double at(double x) {
        double g = x / step;
        if (g <= 0) return y[0];
        int i = (int) g;
        if (i >= y.length - 1) return y[y.length - 1];
        double f = g - i;
        return y[i] + (y[i + 1] - y[i]) * f;
    }

    /**
     * The cross-section area of water between the terrain and a level surface at world y {@code lv}, over {@code xa..xb}.
     * @param ext when not null: {leftmost wet x, rightmost wet x, wetted width, x of the deepest point}
     */
    double volume(double xa, double xb, double lv, double[] ext) {
        double area = 0, w0 = Double.NaN, w1 = Double.NaN, width = 0, deep = -1e18, xd = xa;
        int i0 = (int) Math.max(0, Math.floor(xa / step)), i1 = (int) Math.min(y.length - 2, Math.ceil(xb / step) - 1);
        for (int i = i0; i <= i1; i++) {
            double sa = Math.max(xa, i * step), sb = Math.min(xb, (i + 1) * step);
            if (sb <= sa) continue;
            double ya = y[i] + (y[i + 1] - y[i]) * (sa - i * step) / step, yb = y[i] + (y[i + 1] - y[i]) * (sb - i * step) / step;
            double a = ya - lv, b = yb - lv;
            if (ya > deep) { deep = ya; xd = sa; }
            if (yb > deep) { deep = yb; xd = sb; }
            if (a <= 0 && b <= 0) continue;
            double l = sa, r = sb;
            if (a >= 0 && b >= 0) area += (a + b) * 0.5 * (sb - sa);
            else {
                double f = a / (a - b);                 // where the level crosses the ground
                if (a > 0) { r = sa + f * (sb - sa); area += a * 0.5 * (r - sa); }
                else { l = sa + f * (sb - sa); area += b * 0.5 * (sb - l); }
            }
            if (r > l) {
                if (Double.isNaN(w0) || l < w0) w0 = l;
                if (Double.isNaN(w1) || r > w1) w1 = r;
                width += r - l;
            }
        }
        if (ext != null) { ext[0] = Double.isNaN(w0) ? xd : w0; ext[1] = Double.isNaN(w1) ? xd : w1; ext[2] = width; ext[3] = xd; }
        return area;
    }

    /** The level (world y) at which {@code xa..xb} holds {@code vol}: bisection between the bed and the rim. */
    double levelFor(double xa, double xb, double vol, double yBed, double yRim) {
        if (vol <= 0) return yBed;
        double lo = yRim, hi = yBed;                    // lo = the higher level (more water)
        for (int k = 0; k < 40 && hi - lo > 0.001; k++) {
            double mid = (lo + hi) * 0.5;
            if (volume(xa, xb, mid, null) > vol) lo = mid; else hi = mid;
        }
        return (lo + hi) * 0.5;
    }

    // ------------------------------------------------------------------ the hollows of the heightmap

    private int argMinY(int a, int b) {
        int best = a;
        for (int i = a; i <= b; i++) if (y[i] < y[best]) best = i;
        return best;
    }

    /** The first ridge (the highest point west of the first hollow) and the last one: ground outside them runs off to that world edge. */
    private int ridgeWest, ridgeEast;

    /**
     * The hollows of the whole ground: local minima at least BASIN_MIN_DEPTH below their lower rim. A settlement's span
     * is ordinary ground here: it is a hollow of its own only if the terrain makes it one. Both world edges are outflows.
     */
    private void findBasins() {
        final int i0 = 0, i1 = y.length - 1;
        List<Integer> pits = new ArrayList<>();
        int prevDir = 0, runStart = i0;
        for (int i = i0 + 1; i <= i1; i++) {
            if (y[i] == y[i - 1]) continue;
            int d = y[i] > y[i - 1] ? 1 : -1;            // 1 = the ground descends to the right
            if (d < 0 && prevDir > 0) pits.add((runStart + i - 1) / 2);
            prevDir = d;
            runStart = i;
        }
        int[] ridge = {argMinY(i0, i1)};
        while (true) {
            int n = pits.size();
            if (n == 0) break;
            ridge = new int[n + 1];
            ridge[0] = argMinY(i0, pits.get(0));
            for (int k = 1; k < n; k++) ridge[k] = argMinY(pits.get(k - 1), pits.get(k));
            ridge[n] = argMinY(pits.get(n - 1), i1);
            int worst = -1;
            double least = BASIN_MIN_DEPTH;
            for (int k = 0; k < n; k++) {
                double depth = y[pits.get(k)] - Math.max(y[ridge[k]], y[ridge[k + 1]]);
                if (depth < least) { least = depth; worst = k; }
            }
            if (worst < 0) break;
            pits.remove(worst);                          // too shallow: water crosses it as if it were level ground
        }
        if (pits.isEmpty()) ridge = new int[]{argMinY(i0, i1)};
        ridgeWest = ridge[0];
        ridgeEast = ridge[ridge.length - 1];
        for (int k = 0; k < pits.size(); k++) {
            Basin b = new Basin();
            b.pit = pits.get(k);
            b.left = ridge[k];
            b.right = ridge[k + 1];
            b.sillY = Math.max(y[b.left], y[b.right]);
            boolean right = y[b.right] >= y[b.left];     // the lower rim
            b.next = right ? (k + 1 < pits.size() ? k + 1 : -1) : (k > 0 ? k - 1 : -1);
            basins.add(b);
        }
        owner = new Pool[basins.size()];
        for (Basin b : basins) b.vmax = volume(b.left * step, b.right * step, b.sillY, null);
    }

    /** The cells: where each one runs to, how steep, whose street it is. Static: derived once from the terrain. */
    private void layCells(List<double[]> ponds) {
        for (int k = 0; k < nCells; k++) {
            final double x = k * (double) BUCKET;
            cellY[k] = at(x);
            cellBasin[k] = -1;
            cellTown[k] = -1;
            for (int b = 0; b < basins.size(); b++) if (x >= basins.get(b).left * step && x <= basins.get(b).right * step) { cellBasin[k] = b; break; }
            if (cellBasin[k] >= 0) cellDir[k] = basins.get(cellBasin[k]).pit * step >= x ? 1 : -1;      // toward the bed of its hollow
            else cellDir[k] = x < ridgeWest * step ? -1 : x > ridgeEast * step ? 1 : (basins.isEmpty() ? 1 : x <= basins.get(0).left * step ? -1 : 1);
            for (int t = 0; t < towns.size(); t++) if (x >= towns.get(t).x0 && x <= towns.get(t).x1) {
                cellTown[k] = t;
                Town tn = towns.get(t);
                if (tn.kB < tn.kA) tn.kA = k;
                tn.kB = k;
                break;
            }
            for (double[] p : ponds) if (x >= p[0] && x <= p[1]) cellPond[k] = true;
        }
        for (int k = 0; k < nCells; k++) {
            int nx = k + cellDir[k];
            double yn = nx < 0 || nx >= nCells ? cellY[k] + SLOPE_MIN * BUCKET : cellY[nx];
            cellSlope[k] = Math.max(SLOPE_MIN, (yn - cellY[k]) / BUCKET);
        }
        // a hollow whose bed lies on a settlement's span is that street's own hollow
        pondBasin = new boolean[basins.size()];
        for (int b = 0; b < basins.size(); b++) {
            double px = basins.get(b).pit * step;
            for (double[] p : ponds) if (px >= p[0] && px <= p[1]) pondBasin[b] = true;
            for (Town t : towns) if (px >= t.x0 && px <= t.x1) streetOf.put(b, t);
        }
    }

    private final Map<Integer, Town> streetOf = new HashMap<>();
    /** Per hollow: its bed lies in a pond, which absorbs whatever reaches the floor of the hollow (no body forms there). */
    private boolean[] pondBasin = new boolean[0];

    int basinCount() { return basins.size(); }

    /** {x of the left ridge, x of the bed, x of the right ridge, y of the lower rim} of hollow {@code k} (tests, pictures). */
    double[] basin(int k) {
        Basin b = basins.get(k);
        return new double[]{b.left * step, b.pit * step, b.right * step, b.sillY};
    }

    private Pool poolOf(int basin) {
        Pool p = owner[basin];
        if (p != null) return p;
        Basin b = basins.get(basin);
        p = new Pool();
        p.basins.add(basin);
        p.left = b.left; p.right = b.right; p.pit = b.pit; p.sillY = b.sillY; p.vmax = b.vmax; p.next = b.next;
        p.level = y[b.pit];
        p.xDeep = b.pit * step;
        Town st = streetOf.get(basin);
        if (st != null) p.street = st.id;
        owner[basin] = p;
        pools.add(p);
        return p;
    }

    // ------------------------------------------------------------------ the film

    int cellCount() { return nCells; }

    /** The cell whose centre is nearest to world x. */
    int cellAt(double x) { return (int) Model.clamp(Math.round(x / BUCKET), 0, nCells - 1); }

    /** The depth of the film on cell {@code k}, px. */
    double filmDepth(int k) { return k < 0 || k >= nCells ? 0 : film[k] / BUCKET; }

    /** The flux that left cell {@code k} in the last sub-step, px²/s. */
    double filmFlux(int k) { return k < 0 || k >= nCells ? 0 : flux[k]; }

    int cellDir(int k) { return cellDir[k]; }

    double cellSlope(int k) { return cellSlope[k]; }

    /** All the water that is film, px². */
    double filmStored() {
        double v = 0;
        for (double s : film) v += s;
        return v;
    }

    /**
     * 0 = the cell keeps its water as film; 1 = it is a sink of its hollow (under the body's surface, or within
     * BASIN_MIN_DEPTH of the bed): what arrives goes to the body; 2 = a pond: absorbed.
     */
    private int sink(int k) {
        if (cellPond[k]) return 2;
        int b = cellBasin[k];
        if (b < 0) return 0;
        if (cellY[k] >= y[basins.get(b).pit] - BASIN_MIN_DEPTH) return pondBasin[b] ? 2 : 1;      // (a hollow whose bed is a pond: the pond takes it)
        Pool p = owner[b];
        return p != null && p.vol > 0 && cellY[k] >= p.level ? 1 : 0;
    }

    /** Water arrives on cell {@code k}: on its film, in the body it lies under, or in a pond. */
    private void arrive(int k, double area) {
        if (area <= 0) return;
        if (k < 0 || k >= nCells) { totalLost += area; return; }
        switch (sink(k)) {
            case 2 -> totalLost += area;
            case 1 -> { Pool p = poolOf(cellBasin[k]); p.pend += area; p.rained = true; }
            default -> film[k] += area;
        }
    }

    // ------------------------------------------------------------------ rain

    /** {@code hits} landed rain particles at world x. */
    void rain(double x, double hits) { addCell(cellAt(x), hits * HIT_AREA); }

    /** Rain of {@code rate} hits a second on every cell of the world, for {@code dt} seconds (the fake's {@code rain} command). */
    void rainEverywhere(double rate, double dt) {
        if (rate <= 0 || dt <= 0) return;
        for (int k = 0; k < nCells; k++) addCell(k, rate * dt * HIT_AREA);
    }

    /** {@code area} px² of water onto cell {@code k} (rain; tests: the model table's {@code q} at the head of a slope). */
    void addCell(int k, double area) {
        if (area <= 0 || k < 0 || k >= nCells) return;
        totalIn += area;
        if (cellTown[k] >= 0) towns.get(cellTown[k]).tickIn += area;
        arrive(k, area);
    }

    /** Water straight onto a street, shared evenly by its cells (tests: the model table's {@code Q}). */
    void addToStreet(long id, double area) {
        Town t = town(id);
        if (t == null || t.kB < t.kA) return;
        for (int k = t.kA; k <= t.kB; k++) addCell(k, area / (t.kB - t.kA + 1));
    }

    /** {@code perPx} px² of water on every px of a street's span (tests: the sheet vector's net excess): each cell gets its share of the span. */
    void addOnSpan(long id, double perPx) {
        Town t = town(id);
        if (t == null) return;
        for (int k = 0; k < nCells; k++) {
            double over = Math.min(t.x1, k * BUCKET + BUCKET / 2.0) - Math.max(t.x0, k * BUCKET - BUCKET / 2.0);
            if (over > 0) addCell(k, perPx * over);
        }
    }

    /** Tests: put {@code vol} px² into the body of the hollow a street lies in (the walled-pad fixture). */
    void fillStreet(long id, double vol) {
        for (int b = 0; b < basins.size(); b++) {
            Town t = streetOf.get(b);
            if (t != null && t.id == id) { Pool p = poolOf(b); p.vol += vol; p.rained = true; totalIn += vol; return; }
        }
    }

    // ------------------------------------------------------------------ one tick

    /** Advance by {@code dt} seconds of simulated time (call once per tick, after the tick's rain). */
    void update(double dt) {
        if (dt <= 0) return;
        accDt += dt;
        if (++ticks % PROCESS_EVERY == 0) {
            int n = (int) Math.max(1, Math.ceil(accDt / SUB_STEP - 1e-9));
            double h = accDt / n;
            accDt = 0;
            for (Pool p : pools) p.rained = p.pend > 0;
            for (int s = 0; s < n; s++) subStep(h, n - s);
            settle();
        }
        for (Town t : towns) {
            t.log.addLast(new double[]{dt, t.tickIn});
            t.logDt += dt; t.logSum += t.tickIn;
            t.tickIn = 0;
            while (t.log.size() > 1 && t.logDt - t.log.peekFirst()[0] >= INFLOW_WINDOW_S) {
                double[] o = t.log.pollFirst();
                t.logDt -= o[0]; t.logSum -= o[1];
            }
            t.inflow = Math.max(0, t.logSum) / Math.max(INFLOW_WINDOW_S, t.logDt);
        }
        if (ticks % PROCESS_EVERY == 0) for (Town t : towns) state(t);
    }

    private void subStep(double h, int left) {
        final double[] ext = new double[4];
        // ---- the film runs one cell on: every wet cell passes min(S, q dt) to the next cell in its direction
        for (int k = 0; k < nCells; k++) {
            moved[k] = 0;
            flux[k] = 0;
            if (film[k] <= 0) continue;
            if (sink(k) != 0) {                          // a body has risen over this cell (or it is a pond): its film is the body's now
                double s = film[k];
                film[k] = 0;
                if (cellPond[k] || pondBasin[cellBasin[k]]) totalLost += s; else { Pool p = poolOf(cellBasin[k]); p.pend += s; p.rained = true; }
                continue;
            }
            double d = film[k] / BUCKET, u = Math.min(U_MAX, (cellTown[k] >= 0 ? KU_STREET : KU) * Math.sqrt(cellSlope[k]) * Math.pow(d, 2.0 / 3.0));
            moved[k] = Math.min(film[k], u * d * h);
            flux[k] = moved[k] / h;
        }
        for (int k = 0; k < nCells; k++) {
            if (moved[k] <= 0) continue;
            film[k] -= moved[k];
            int nx = k + cellDir[k];
            if (nx >= 0 && nx < nCells && cellTown[nx] >= 0 && cellTown[nx] != cellTown[k]) towns.get(cellTown[nx]).tickIn += moved[k];      // film from upslope enters a street
            arrive(nx, moved[k]);
        }
        // ---- the drains take water from the film on the street's cells; a wet cell soaks a little
        for (Town t : towns) {
            if (t.kB < t.kA || t.cap <= 0) continue;
            final double per = t.cap * BUCKET / (t.x1 - t.x0) * h;
            for (int k = t.kA; k <= t.kB; k++) {
                if (film[k] <= 0) continue;
                double d = Math.min(film[k], per);
                film[k] -= d; totalDrain += d;
            }
        }
        if (FILM_SOAK > 0) for (int k = 0; k < nCells; k++) if (film[k] > 0) { double d = Math.min(film[k], FILM_SOAK * h); film[k] -= d; totalSoak += d; }
        // ---- the bodies: what reached them, soak, the inlets under their surface, the rim
        for (int k = 0; k < pools.size(); k++) {
            Pool p = pools.get(k);
            double in = p.pend / left;
            p.pend -= in;
            p.vol += in;
            if (p.vol <= 0) { p.vol = 0; p.full = false; p.spill = false; p.level = y[p.pit]; continue; }
            double lv = levelFor(p.left * step, p.right * step, Math.min(p.vol, p.vmax), y[p.pit], p.sillY);
            volume(p.left * step, p.right * step, lv, ext);
            double soak = Math.min(p.vol, SOAK * ext[2] * h);
            p.vol -= soak; totalSoak += soak;
            for (Town t : towns) {
                if (t.x1 < ext[0] || t.x0 > ext[1] || t.cap <= 0) continue;
                int under = 0;
                for (int i = 0; i < t.inlets; i++) {
                    double ix = t.x0 + (i + 0.5) * (t.x1 - t.x0) / t.inlets;
                    if (ix >= ext[0] && ix <= ext[1] && at(ix) > lv) under++;
                }
                double drained = Math.min(p.vol, under * INLET_K * t.size * t.size * h);
                p.vol -= drained; totalDrain += drained;
            }
            p.level = lv;
            p.full = p.vol >= p.vmax - 1e-9;
            p.spill = p.vol > p.vmax;
            if (p.spill) {
                // it clamps at its real rim; the excess enters the first cell beyond it and runs on as film
                double over = p.vol - p.vmax;
                p.vol = p.vmax;
                boolean right = y[p.right] >= y[p.left];
                int kc = right ? (int) Math.floor(p.right * step / BUCKET) + 1 : (int) Math.ceil(p.left * step / BUCKET) - 1;
                if (kc >= 0 && kc < nCells && cellTown[kc] >= 0) towns.get(cellTown[kc]).tickIn += over;      // a hollow's spill enters a street
                arrive(kc, over);
            }
        }
        merge();
    }

    /** After the sub-steps: the geometry for the wire, the ids, and the bodies that have dried up. */
    private void settle() {
        final double[] ext = new double[4];
        // a body that has risen over a cell in this step takes that cell's film (no film cell lies under a body)
        for (int k = 0; k < nCells; k++) {
            if (film[k] <= 0 || sink(k) == 0) continue;
            double s = film[k];
            film[k] = 0;
            flux[k] = 0;
            if (cellPond[k] || pondBasin[cellBasin[k]]) totalLost += s; else { Pool p = poolOf(cellBasin[k]); p.pend += s; p.rained = true; }
        }
        for (Town t : towns) t.body = null;
        for (int k = pools.size() - 1; k >= 0; k--) {
            Pool p = pools.get(k);
            if (p.vol < MIN_VOL && !p.rained && p.pend <= 0) {            // dried up: its id is retired, merged hollows are their own again
                totalSoak += p.vol;
                for (int b : p.basins) owner[b] = null;
                pools.remove(k);
                continue;
            }
            if (p.id == 0 && p.vol >= MIN_VOL) p.id = p.street >= 0 ? p.street : nextId++;
            p.level = levelFor(p.left * step, p.right * step, p.vol, y[p.pit], p.sillY);
            volume(p.left * step, p.right * step, p.level, ext);
            p.x0 = ext[0]; p.x1 = ext[1];
            p.xDeep = p.pit * step;
            p.depth = Math.max(0, y[p.pit] - p.level);
            if (p.street >= 0 && p.vol >= MIN_VOL) { Town t = town(p.street); if (t != null) t.body = p; }
        }
    }

    /** A settlement's flood report: the greatest depth on its span (film or body), whether a hollow has backed up over it, its state. */
    private void state(Town t) {
        double depth = 0;
        boolean backed = false, over = false;
        for (int k = t.kA; k <= t.kB; k++) depth = Math.max(depth, film[k] / BUCKET);
        for (Pool p : pools) {
            if (p.id == 0 || p.vol < MIN_VOL || p.x1 < t.x0 || p.x0 > t.x1) continue;
            double a = Math.max(t.x0, p.x0), b = Math.min(t.x1, p.x1), d = 0;
            for (double x = a; ; x += step) { if (x > b) x = b; d = Math.max(d, at(x) - p.level); if (x >= b) break; }
            if (d <= 0) continue;
            depth = Math.max(depth, d);
            if (p.street != t.id) backed = true;
            if (p.spill) over = true;
        }
        t.depth = depth >= WET_DEPTH ? depth : 0;
        t.backed = backed && t.depth > 0;
        t.state = t.depth >= WET_DEPTH ? (t.depth >= OVERFLOW_DEPTH || over ? "overflowing" : t.depth >= FLOOD_DEPTH ? "flooded" : "ponding")
                : t.inflow < 0.01 ? "dry" : "wet";
    }

    /** Two bodies that are both full to the rim between them become one: the volumes add, the larger keeps its id (a pool's before a street's). */
    private void merge() {
        for (int k = 0; k < pools.size(); k++) {
            Pool p = pools.get(k);
            if (!p.full || p.next < 0) continue;
            Pool q = owner[p.next];
            if (q == null || q == p || !q.full || q.next < 0 || owner[q.next] != p) continue;
            Pool m = new Pool();
            m.basins.addAll(p.basins);
            m.basins.addAll(q.basins);
            java.util.Collections.sort(m.basins);
            m.left = Math.min(p.left, q.left);
            m.right = Math.max(p.right, q.right);
            m.pit = y[p.pit] >= y[q.pit] ? p.pit : q.pit;
            m.sillY = Math.max(y[m.left], y[m.right]);
            boolean right = y[m.right] >= y[m.left];
            // the hollow beyond the lower rim (a merged body spans whole hollows)
            int beyond = right ? m.basins.get(m.basins.size() - 1) + 1 : m.basins.get(0) - 1;
            m.next = beyond >= 0 && beyond < basins.size() && (right ? basins.get(beyond).left == m.right : basins.get(beyond).right == m.left) ? beyond : -1;
            m.vmax = volume(m.left * step, m.right * step, m.sillY, null);
            m.vol = p.vol + q.vol;
            m.pend = p.pend + q.pend;
            m.rained = true;
            m.level = Math.min(p.level, q.level);
            // a street's hollow that joins a pool is covered by the pool: the merged body is a pool with the pool's id
            boolean keepP = p.street < 0 != q.street < 0 ? p.street < 0 : p.vol > q.vol || (p.vol == q.vol && (p.id != 0 && (q.id == 0 || p.id < q.id)));
            m.id = keepP ? p.id : q.id;
            if (m.id == 0) m.id = p.id != 0 && p.street < 0 ? p.id : q.street < 0 ? q.id : 0;
            m.street = p.street >= 0 && q.street >= 0 ? (keepP ? p.street : q.street) : -1;
            if (m.street < 0 && m.id != 0 && town(m.id) != null) m.id = 0;      // (never a settlement's id on a pool: it gets its own on the next settle)
            for (int b : m.basins) owner[b] = m;
            pools.remove(p);
            pools.remove(q);
            pools.add(m);
            k = -1;                                       // the list changed: look again from the start
        }
    }

    /** The depth of water at world x, px (0 = none): a body's, or the film's between the cell centres. */
    double depthAt(double x) {
        for (Pool p : pools) if (p.id != 0 && x >= p.x0 && x <= p.x1) { double d = at(x) - p.level; if (d > 0) return d; }
        double g = x / BUCKET;
        int i = (int) Math.floor(g);
        double f = g - i;
        return filmDepth(i) * (1 - f) + filmDepth(i + 1) * f;
    }

    double stored() {
        double v = filmStored();
        for (Pool p : pools) v += p.vol + p.pend;
        return v;
    }

    // ------------------------------------------------------------------ the drains command

    /** The {@code drains} command of PROTOCOL.md. True when it was accepted (one {@code frame.drains} follows). */
    boolean command(Map<String, Object> m) {
        Object md = m.get("mode"), st = m.get("settlement"), sz = m.get("size"), dl = m.get("delta");
        if (md != null) {
            if (!(md instanceof String s) || !java.util.Arrays.asList(MODES).contains(s)) return false;
            mode = s;
            for (Town t : towns) setSize(t, sizeFor(mode, seed, t.id, t.kind));
        } else if (st != null) {
            if (!(st instanceof Number n)) return false;
            Town t = town(n.longValue());
            if (t == null || n.doubleValue() != n.longValue()) return false;
            if (sz instanceof Number v && !Double.isNaN(v.doubleValue())) setSize(t, v.doubleValue());
            else if (sz == null && dl instanceof Number d && !Double.isNaN(d.doubleValue())) setSize(t, t.size + d.doubleValue());
            else return false;
            mode = "custom";
        } else if (dl instanceof Number d && !Double.isNaN(d.doubleValue()) && sz == null) {
            if (towns.isEmpty()) return false;
            for (Town t : towns) setSize(t, t.size + d.doubleValue());
            mode = "custom";
        } else return false;
        drainsDue = true;
        return true;
    }

    // ------------------------------------------------------------------ the wire

    /** A new {@code hello} goes out: the next frame carries the complete lists again. */
    void helloSent() { waterFirst = true; floodFirst = true; flowFirst = true; sentAllDry = false; sentNoFlow = false; }

    void helloJson(StringBuilder sb) {
        sb.append(",\"water\":{\"model\":1,\"basin_min_depth\":").append(BASIN_MIN_DEPTH).append('}');
        sb.append(",\"drains\":{\"mode\":\"").append(mode).append("\",\"inlet_spacing\":").append(INLET_SPACING).append(",\"curb\":").append(CURB)
                .append(",\"flood_depth\":").append(FLOOD_DEPTH).append(",\"overflow_depth\":").append(OVERFLOW_DEPTH)
                .append(",\"size_min\":").append(SIZE_MIN).append(",\"size_max\":").append(SIZE_MAX)
                .append(",\"classes\":{\"small\":").append(SMALL).append(",\"normal\":").append(NORMAL).append(",\"large\":").append(LARGE).append("}}");
        sb.append(",\"flow\":{\"model\":1,\"cell\":").append(BUCKET).append(",\"min_h\":").append(WET_DEPTH).append('}');
    }

    /** {@code "drain":{...}} of one settlement entry of the hello. */
    String drainJson(long id) {
        Town t = town(id);
        if (t == null) return null;
        StringBuilder sb = new StringBuilder("\"drain\":{");
        drainFields(sb, t);
        return sb.append('}').toString();
    }

    private static void drainFields(StringBuilder sb, Town t) {
        sb.append("\"size\":");
        Json.num2(sb, t.size);
        sb.append(",\"class\":\"").append(classOf(t.size)).append("\",\"inlets\":").append(t.inlets).append(",\"cap\":");
        Json.num(sb, t.cap);
    }

    private boolean waterChanged() {
        int n = 0;
        for (Pool p : pools) if (p.id != 0 && p.vol >= MIN_VOL) {
            n++;
            double[] s = sentWater.get(p.id);
            if (s == null || Math.abs(s[0] - p.level) >= 0.05 || Math.abs(s[1] - p.x0) >= 0.5 || Math.abs(s[2] - p.x1) >= 0.5 || (s[3] != 0) != p.spill) return true;
        }
        return n != sentWater.size();
    }

    private boolean floodChanged(boolean allDry) {
        for (Town t : towns) {
            Object[] s = sentFlood.get(t.id);
            if (s == null || !s[0].equals(t.state) || (Boolean) s[4] != t.backed) return true;
            if (allDry) continue;
            if (Math.abs((Double) s[1] - t.depth) >= 0.05 || Math.abs((Double) s[2] - t.inflow) >= 0.5 || Math.abs((Double) s[3] - t.cap) >= 0.05) return true;
        }
        return false;
    }

    /** A cell's depth as the wire carries it: steps of 0.05 px, nothing below 0.05. */
    private double wireH(int k) {
        double h = Math.round(film[k] / BUCKET / FLOW_H_STEP) * FLOW_H_STEP;
        return h >= WET_DEPTH - 1e-9 ? h : 0;
    }

    private boolean flowChanged() {
        for (int k = 0; k < nCells; k++) {
            double h = wireH(k);
            if ((h > 0) != (sentH[k] > 0)) return true;                          // a run appeared, vanished, grew or shrank by a cell
            if (h <= 0) continue;
            if (Math.abs(h - sentH[k]) >= FLOW_SEND_H - 1e-9 || Math.abs(flux[k] - sentQ[k]) > FLOW_SEND_Q * Math.max(sentQ[k], 0.5)) return true;
        }
        return false;
    }

    /** The body a cell's water ends in: its id, -1 = off a world edge, -2 = a pond, 0 = a hollow that holds no body yet. */
    private long destination(int k, int dir) {
        int nx = k + dir;
        if (nx < 0 || nx >= nCells) return -1;
        if (cellPond[nx]) return -2;
        int b = cellBasin[k];
        if (b < 0) return -1;
        Pool p = owner[b];
        return p != null && p.id != 0 && p.vol >= MIN_VOL ? p.id : 0;
    }

    /** {@code frame.flow}: the wet cells as runs, each walked downhill from its head; a run is cut at 40 cells, at a dry cell and where a body begins. */
    private void flowJson(StringBuilder sb) {
        java.util.Arrays.fill(sentH, 0);
        java.util.Arrays.fill(sentQ, 0);
        sb.append(",\"flow\":[");
        int runs = 0;
        boolean[] done = new boolean[nCells];
        for (int pass = 0; pass < 2; pass++) {
            for (int k0 = 0; k0 < nCells; k0++) {
                if (done[k0] || wireH(k0) <= 0) continue;
                final int dir = cellDir[k0], up = k0 - dir;
                // pass 0: the heads (nothing wet runs into them); pass 1: what is left (a run that was cut at 40 cells)
                if (pass == 0 && up >= 0 && up < nCells && wireH(up) > 0 && cellDir[up] == dir) continue;
                int k = k0;
                while (true) {
                    int n = 0, first = k;
                    StringBuilder hs = new StringBuilder(), qs = new StringBuilder();
                    while (k >= 0 && k < nCells && !done[k] && wireH(k) > 0 && cellDir[k] == dir && n < RUN_MAX) {
                        if (n > 0) { hs.append(','); qs.append(','); }
                        Json.num2(hs, wireH(k));
                        Json.num(qs, Math.round(flux[k] * 10) / 10.0);
                        sentH[k] = wireH(k); sentQ[k] = flux[k];
                        done[k] = true;
                        k += dir;
                        n++;
                    }
                    if (n == 0) break;
                    int last = k - dir;
                    long src = cellTown[first] >= 0 ? towns.get(cellTown[first]).id : 0;
                    for (Pool p : pools) {
                        if (!p.spill || p.id == 0) continue;
                        boolean right = y[p.right] >= y[p.left];
                        int kc = right ? (int) Math.floor(p.right * step / BUCKET) + 1 : (int) Math.ceil(p.left * step / BUCKET) - 1;
                        if (kc == first) src = p.id;
                    }
                    if (runs++ > 0) sb.append(',');
                    sb.append("{\"k0\":").append(first).append(",\"dir\":").append(dir).append(",\"src\":").append(src).append(",\"dst\":").append(destination(last, dir))
                            .append(",\"h\":[").append(hs).append("],\"q\":[").append(qs).append("]}");
                    if (n < RUN_MAX) break;                                      // (a full run: the next cell starts another)
                }
            }
        }
        sb.append(']');
        sentNoFlow = runs == 0;
    }

    /** The water keys of this tick's frame ({@code water}, {@code flood}, {@code flow}, {@code drains}), each only when its send rule says so. */
    void frameJson(StringBuilder sb) {
        sinceWater++;
        sinceFlood++;
        sinceFlow++;
        if (waterFirst || (sinceWater >= WATER_EVERY && waterChanged())) {
            waterFirst = false;
            sinceWater = 0;
            sentWater.clear();
            sb.append(",\"water\":[");
            int n = 0;
            for (Pool p : pools) {
                if (p.id == 0 || p.vol < MIN_VOL) continue;
                if (n++ > 0) sb.append(',');
                // a street body only while the street itself is a hollow that holds water; a pool that covers a street is a pool
                if (p.street >= 0) sb.append("{\"id\":").append(p.id).append(",\"kind\":\"street\",\"settlement\":").append(p.street);
                else sb.append("{\"id\":").append(p.id).append(",\"kind\":\"pool\"");
                body(sb, p.xDeep, p.x0, p.x1, p.level, p.depth, p.vol);
                if (p.street >= 0 || p.spill) sb.append(",\"spill\":").append(p.spill);
                sb.append('}');
                sentWater.put(p.id, new double[]{p.level, p.x0, p.x1, p.spill ? 1 : 0});
            }
            sb.append(']');
        }
        boolean allDry = true;
        for (Town t : towns) if (!t.state.equals("dry")) allDry = false;
        if (!towns.isEmpty() && (floodFirst || (sinceFlood >= FLOOD_EVERY && !(allDry && sentAllDry) && floodChanged(allDry)))) {
            floodFirst = false;
            sinceFlood = 0;
            sentAllDry = allDry;
            sb.append(",\"flood\":[");
            for (int k = 0; k < towns.size(); k++) {
                Town t = towns.get(k);
                if (k > 0) sb.append(',');
                sb.append("{\"id\":").append(t.id).append(",\"state\":\"").append(t.state).append("\",\"depth\":");
                Json.num2(sb, t.depth);
                sb.append(",\"inflow\":");
                Json.num(sb, t.inflow);
                sb.append(",\"cap\":");
                Json.num(sb, t.cap);
                if (t.backed) sb.append(",\"backed\":true");
                sb.append('}');
                sentFlood.put(t.id, new Object[]{t.state, t.depth, t.inflow, t.cap, t.backed});
            }
            sb.append(']');
        }
        if (flowFirst || (sinceFlow >= FLOW_EVERY && flowChanged())) {
            flowFirst = false;
            sinceFlow = 0;
            flowJson(sb);
        }
        if (drainsDue) {
            drainsDue = false;
            sb.append(",\"drains\":{\"mode\":\"").append(mode).append("\",\"settlements\":[");
            for (int k = 0; k < towns.size(); k++) {
                if (k > 0) sb.append(',');
                sb.append("{\"id\":").append(towns.get(k).id).append(',');
                drainFields(sb, towns.get(k));
                sb.append('}');
            }
            sb.append("]}");
        }
    }

    private static void body(StringBuilder sb, double x, double x0, double x1, double level, double depth, double vol) {
        sb.append(",\"x\":"); Json.num(sb, x);
        sb.append(",\"x0\":"); Json.num2(sb, x0);
        sb.append(",\"x1\":"); Json.num2(sb, x1);
        sb.append(",\"y\":"); Json.num2(sb, level);
        sb.append(",\"depth\":"); Json.num2(sb, depth);
        sb.append(",\"vol\":"); Json.num(sb, vol);
    }
}
