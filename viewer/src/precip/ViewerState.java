package precip;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Everything the viewer knows about one world: the last two frames (for interpolation), the
 * object list with its per-id state, the terrain ground cover, the full lifetime history of every
 * cloud ever seen, and the event log. Mutated by the reader thread, read by the renderer; all
 * access is synchronized on this object.
 * <p>
 * <b>Phase C seams</b>
 * <ul>
 *   <li>{@link #exportHistory()} / {@link #importHistory(Map)} — the cloud lifetimes and the event
 *       log as plain data (Map / List / String / Double), ready for {@code Json.write} and
 *       {@code Json.parse}; this is what a save slot's {@code history.json} holds.</li>
 *   <li>{@link #savedListener} — called (on the reader thread, outside the lock) for every
 *       {@code saved} reply; {@link #pollSaved()} offers the same replies as a queue.</li>
 *   <li>One instance per session: create a new ViewerState for every game view.</li>
 * </ul>
 */
final class ViewerState {

    /** Lifetime record of one cloud, alive or dead. */
    static final class Life {
        long id, seed;
        String name = "", type = "cumulus", stage = "forming", precipType = "none";
        double born, died = Double.NaN, lastT, lifetime, peakWater, totalPrecip, lastX, lastY, shear, anvil;
        Map<String, Double> stageTimes = new LinkedHashMap<>();
        double[][] puffs = new double[0][];
        double puffArea;   // the silhouette kept is the cloud at its fullest
        int[] tint = {230, 234, 240};
        boolean alive = true;
        // --- v2.1 ---
        /** Still an ordinary fair-weather cloud (kept as last seen once the cloud is gone). */
        boolean fair;
        /** Was fair at some point of its life. */
        boolean everFair;
        /** Sim time at which a fair cloud developed (stopped being fair); NaN = never, or not witnessed. */
        double developedT = Double.NaN;
        /** The type it grew out of ("" = none), and how far that change got (0..1). */
        String fromType = "";
        double morph;
        /** Sim time at which the change of type began; NaN = none, or not witnessed. */
        double morphT = Double.NaN;

        // --- v2.3 ---
        /** Names of the clouds it absorbed, in merge order ({@code St-0017 "Low Veil"}), and their types. */
        List<String> absorbed = new ArrayList<>(), absorbedTypes = new ArrayList<>();
        /** Sim times at which it was seen absorbing a cloud (marks on its life bar). */
        List<Double> mergeTs = new ArrayList<>();
        /** The cloud that absorbed it (-1 = none) and that cloud's name as last known. */
        long mergedInto = -1;
        String mergedIntoName = "";
        /** Which absorbed cloud each puff of {@link #puffs} came from (null = not known), for the portrait. */
        int[] puffPart;
        /** Supercell: strongest rotation seen, tornadoes produced and the highest EF rating among them (-1 = none). */
        double peakRotation;
        int tornadoes, peakEf = -1;

        boolean morphing() { return !fromType.isEmpty() && morph < 1; }

        double end(double now) { return alive ? now : (Double.isNaN(died) ? lastT : died); }

        /** Kinds of the absorbed clouds, parallel to {@link #absorbed}. */
        boolean[] sheetParts() {
            boolean[] b = new boolean[absorbedTypes.size()];
            for (int k = 0; k < b.length; k++) b[k] = Model.sheetKind(Model.cloudKind(absorbedTypes.get(k)));
            return b;
        }
    }

    /** v2.3: what the viewer saw of one tornado (kept after it is gone: the event list and the scar on the ground). */
    static final class TornadoRec {
        long id, cloud = -1;
        String cloudName = "", stage = "funnel";
        double startT, touchT = Double.NaN, endT = Double.NaN, peakWind, pathX0 = Double.NaN, pathX1 = Double.NaN, peakWidth;
        int peakEf;
        boolean alive = true;

        double pathLength() { return Double.isNaN(pathX0) || Double.isNaN(pathX1) ? 0 : Math.abs(pathX1 - pathX0); }
    }

    /** Every tornado seen in this world, oldest first (capped). */
    final List<TornadoRec> tornadoLog = new ArrayList<>();
    private final Map<Long, TornadoRec> tornadoById = new HashMap<>();
    static final int MAX_TORNADO_LOG = 48;

    Model.Hello hello;
    /** v2.2 hurricane mode: the storm as last reported (null in every other mode). Guarded by this object's lock like everything else. */
    HurricaneModel hurricane;
    /** v2.3 world mode: the map's locations and the latest {@code world} frame (null in every other mode). Same lock. */
    WorldModel world;
    Model.Frame prev, cur;
    long framesReceived;
    final List<Life> lives = new ArrayList<>();
    final Map<Long, Life> byId = new HashMap<>();
    final ArrayDeque<String> events = new ArrayDeque<>();
    String error;          // fatal message shown on screen
    String status = "Waiting for simulation...";
    long selectedId = -1;
    int resets;

    // --- world objects: complete list from hello, state merged from frame deltas ---
    /** Current objects (immutable instances in a copy-on-write array: grab the reference under the lock). */
    Model.Obj[] objects = new Model.Obj[0];
    private final Map<Long, Integer> objIndex = new HashMap<>();
    private double[] bakedSnow = new double[0], bakedWet = new double[0];
    /** Latest ground cover (never null after the first hello). */
    Model.Ground ground = new Model.Ground();
    /** Bumped whenever something the cached static scenery shows has changed (ground cover, building snow/wet). */
    long coverVersion;
    /** Bumped with every hello: world geometry (terrain, objects) is new. */
    long worldVersion;
    /**
     * Phase O (OW): what a tornado is doing to the buildings as the viewer shows it (the pieces in the air, the bits of
     * the bake key). Fed from the damage rows and the tornadoes of every frame; never saved, never sent. Same lock.
     */
    final Wreck wreck = new Wreck();

    // --- v2.4: standing water, flood states and drains. Kept like the ground cover: a frame without the key leaves them,
    // an empty list empties them. The lists and the map are replaced, never changed in place (grab the reference under the lock).
    /** The bodies of standing water as last sent (never null; empty = everything is dry, or a stream without the water model). */
    List<Model.Body> water = List.of();
    /** The flood state of every settlement as last sent (never null). */
    List<Model.Flood> flood = List.of();
    /** The drains of every settlement by its id: from the hello, then as {@code frame.drains} changed them (never null). */
    Map<Long, Model.Drain> drains = Map.of();
    /** {@code hello.drains.mode}, then the mode of the last {@code frame.drains}; "" = the stream has no drains. */
    String drainMode = "";
    /** v2.4.1: the runs of the film of water on the ground as last sent (never null; empty = no flow, or a stream without {@code hello.flow}). */
    List<Model.Run> flow = List.of();

    Model.Flood floodOf(long settlement) {
        for (Model.Flood f : flood) if (f.id == settlement) return f;
        return null;
    }

    // --- save replies ---
    /** Called for every {@code saved} message (reader thread, no lock held). May be null. */
    volatile Consumer<Model.Saved> savedListener;
    private final ArrayDeque<Model.Saved> savedQueue = new ArrayDeque<>();

    synchronized Model.Saved pollSaved() { return savedQueue.pollFirst(); }

    static final int MAX_EVENTS = 400;

    // --- pipe instrumentation (used by --bench) ---
    private final double[] gapMs = new double[1024];
    private int gapN;
    private long lastArrival, parseNs, parseCount;
    long skippedStale;

    synchronized double[] arrivalGapsMs() { return java.util.Arrays.copyOf(gapMs, Math.min(gapN, gapMs.length)); }

    synchronized double avgParseMs() { return parseCount == 0 ? 0 : parseNs / 1e6 / parseCount; }

    /** Parse and apply one protocol line. Safe to call from any thread. */
    void acceptLine(String line) {
        long t0 = System.nanoTime();
        line = line.trim();
        if (line.isEmpty() || line.charAt(0) != '{') return;
        Map<String, Object> m;
        try {
            m = Json.obj(Json.parse(line));
        } catch (RuntimeException ex) {
            synchronized (this) { log("[viewer] bad JSON line ignored: " + ex.getMessage()); }
            return;
        }
        if (m == null) return;
        String type = Json.str(m, "type", "");
        if (type.equals("hello")) {
            Model.Hello h = Model.parseHello(m);
            synchronized (this) {
                if (h.version != Model.PROTOCOL_VERSION) {
                    error = "The simulation speaks protocol version " + h.version + ", this viewer needs version "
                            + Model.PROTOCOL_VERSION + ".\n\nUpdate the Python simulation (sim/) or run the viewer with --fake.";
                    status = "Protocol mismatch";
                    return;
                }
                if (hello != null) reset("new hello (seed " + h.seed + ")");
                applyHello(h);
                hurricane = h.hurricaneRaw != null ? new HurricaneModel(h.hurricaneRaw) : null;
                world = Json.obj(m, "world_map") != null ? new WorldModel(Json.obj(m, "world_map")) : null;
                status = "Connected - seed " + h.seed;
            }
        } else if (type.equals("frame")) {
            Model.Frame f = Model.parseFrame(m);
            f.recvNanos = System.nanoTime();
            synchronized (this) {
                if (error != null && hello == null) return; // protocol mismatch: ignore the stream
                applyFrame(f);
                if (lastArrival != 0) gapMs[gapN++ % gapMs.length] = (f.recvNanos - lastArrival) / 1e6;
                lastArrival = f.recvNanos;
                parseNs += System.nanoTime() - t0;
                parseCount++;
            }
        } else if (type.equals("hurricane")) {
            synchronized (this) {
                if (hurricane == null) return;                // a hurricane frame without its hello: ignore
                hurricane.accept(m, System.nanoTime());
                framesReceived++;
                List<Object> ev = Json.arr(m, "events");
                if (ev != null) for (Object o : ev) if (o != null) log(String.valueOf(o));
            }
        } else if (type.equals("world")) {
            synchronized (this) {
                if (world == null) return;                    // a world frame without its hello: ignore
                world.accept(m, System.nanoTime());
                framesReceived++;
                List<Object> ev = Json.arr(m, "events");
                if (ev != null) for (Object o : ev) if (o != null) log(String.valueOf(o));
            }
        } else if (type.equals("saved")) {
            Model.Saved s = Model.parseSaved(m);
            synchronized (this) {
                savedQueue.addLast(s);
                while (savedQueue.size() > 32) savedQueue.removeFirst();
                log(s.ok ? String.format("[viewer] world saved at t=%.1f", s.t) : "[viewer] save failed: " + s.error);
            }
            Consumer<Model.Saved> l = savedListener;
            if (l != null) l.accept(s);
        }
    }

    private void applyHello(Model.Hello h) {
        hello = h;
        objects = h.objects.toArray(new Model.Obj[0]);
        objIndex.clear();
        for (int k = 0; k < objects.length; k++) objIndex.put(objects[k].id, k);
        bakedSnow = new double[objects.length];
        bakedWet = new double[objects.length];
        for (int k = 0; k < objects.length; k++) { bakedSnow[k] = objects[k].snow; bakedWet[k] = objects[k].wet; }
        ground = new Model.Ground();
        // v2.4: a new world starts dry, with the drains its hello lists
        water = List.of();
        flood = List.of();
        flow = List.of();
        Map<Long, Model.Drain> dm = new HashMap<>();
        for (Model.Settlement s : h.settlements) if (s.drain != null) dm.put(s.id, s.drain);
        drains = dm;
        drainMode = h.drains != null ? h.drains.mode : "";
        worldVersion++;
        coverVersion++;
    }

    private void reset(String why) {
        lives.clear();
        byId.clear();
        tornadoLog.clear();
        tornadoById.clear();
        prev = null;
        cur = null;
        selectedId = -1;
        resets++;
        log("[viewer] history cleared: " + why);
    }

    /**
     * Settings > Advanced "Max particles": give every particle of {@code f} its rank ({@link Model.Frame#particleRank}).
     * The stream carries no particle id, and nothing in a particle's row stays the same but its size, so the viewer follows
     * the particles itself: one that stands where a particle of the same kind and size of the frame before was heading
     * takes over that one's rank; a new one draws its rank from where and when it appeared. A particle therefore stays
     * drawn or left out for as long as it lives, instead of flickering as it drifts.
     * @param a the frame before (null, or not ranked: every particle is new)
     */
    static void rankParticles(Model.Frame a, Model.Frame f) {
        final int n = f.particleCount;
        final float[] p = f.particles;
        final byte[] rank = new byte[n];
        final boolean track = a != null && a.particleRank != null && a.particleCount > 0 && a.particleRank.length >= a.particleCount && f.t >= a.t && f.t - a.t < 0.5;
        // the time between the two frames: the stream rounds t to 1/100 s, which is a third of a frame, so the frame
        // counter is used when it tells the same story (it does not while the simulation is paused)
        double dt = track ? f.t - a.t : 0, byCount = track ? (f.frame - a.frame) * f.speed / 30.0 : 0;
        if (track && !f.paused && Math.abs(byCount - dt) <= 0.011) dt = byCount;
        final float half = (float) dt * 0.5f, inv = 1 / 8f;       // 8 px cells: a match lies within one cell
        int[] head = null, next = null;
        boolean[] taken = null;
        int mask = 0;
        final float[] q = track ? a.particles : null;
        if (track) {
            int m = a.particleCount, cap = Integer.highestOneBit(Math.max(16, m * 2) - 1) << 1;
            mask = cap - 1;
            head = new int[cap];
            java.util.Arrays.fill(head, -1);
            next = new int[m];
            taken = new boolean[m];
            for (int i = 0; i < m; i++) {
                // where it is half a step on, at its own speed (the new frame's particles are looked up half a step back at theirs)
                int b = i * 7, h = cellHash((int) Math.floor((q[b + 1] + q[b + 3] * half) * inv), (int) Math.floor((q[b + 2] + q[b + 4] * half) * inv)) & mask;
                next[i] = head[h];
                head[h] = i;
            }
        }
        for (int k = 0; k < n; k++) {
            int b = k * 7, r = -1;
            if (track) {
                float x = p[b + 1] - p[b + 3] * half, y = p[b + 2] - p[b + 4] * half, bd = 1f;
                int cx = (int) Math.floor(x * inv), cy = (int) Math.floor(y * inv), best = -1;
                // how far off it may be: 2.5 px across its path, and along it what the rounded time can make of its speed
                float sp = (float) Math.hypot(p[b + 3], p[b + 4]), ux = sp > 1 ? p[b + 3] / sp : 0, uy = sp > 1 ? p[b + 4] / sp : 0;
                float tolA = Math.min(8f, 2.5f + 0.012f * sp), kA = 1 / tolA, kP = 1 / 2.5f;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    for (int i = head[cellHash(cx + dx, cy + dy) & mask]; i >= 0; i = next[i]) {
                        int ib = i * 7;
                        if (taken[i] || q[ib] != p[b] || q[ib + 5] != p[b + 5]) continue;
                        float ex = q[ib + 1] + q[ib + 3] * half - x, ey = q[ib + 2] + q[ib + 4] * half - y;
                        float al = (ex * ux + ey * uy) * kA, pe = (ex * uy - ey * ux) * kP, d = sp > 1 ? al * al + pe * pe : (ex * ex + ey * ey) * kP * kP;
                        if (d < bd) { bd = d; best = i; }
                    }
                }
                if (best >= 0) { taken[best] = true; r = a.particleRank[best]; }
            }
            rank[k] = (byte) (r >= 0 ? r : Renderer.particleRank(p, b, (int) f.frame));
        }
        f.particleRank = rank;
    }

    private static int cellHash(int cx, int cy) {
        int h = cx * 0x9E3779B1 + cy * 0x85EBCA6B;
        return h ^ (h >>> 15);
    }

    synchronized void log(String s) {
        events.addLast(s);
        while (events.size() > MAX_EVENTS) events.removeFirst();
    }

    private void applyFrame(Model.Frame f) {
        if (cur != null && f.t < cur.t - 1.0) reset("simulation time went backwards (reseed)");
        if (Advanced.cur.maxParticles < 100) rankParticles(cur, f);      // (before the frame is shown to anyone)
        prev = cur;
        cur = f;
        framesReceived++;
        if (hello == null) { // stream without a hello (should not happen): flat world of the frame's size
            Model.Hello h = new Model.Hello();
            h.w = f.w; h.h = f.h; h.groundY = f.groundY;
            h.terrain = Model.Terrain.flat(f.w, f.groundY);
            applyHello(h);
        }

        // object deltas -> per-id state (copy-on-write, so renderers can keep reading their snapshot)
        if (f.objDeltas.length > 0) {
            Model.Obj[] next = null;
            for (double[] d : f.objDeltas) {
                Integer idx = objIndex.get((long) d[0]);
                if (idx == null) continue;
                if (next == null) next = objects.clone();
                Model.Obj o = next[idx];
                Model.Obj n = o.withState(d[1], d[2], d[3], Double.isNaN(d[4]) ? o.w : d[4], Double.isNaN(d[5]) ? o.h : d[5]);
                next[idx] = n;
                boolean live = n.kind.equals("tree") || n.kind.equals("pond")
                        || n.kind.equals("puddle") || n.kind.equals("snowpack");
                if (!live && (Math.abs(n.snow - bakedSnow[idx]) > 0.02 || Math.abs(n.wet - bakedWet[idx]) > 0.03)) {
                    bakedSnow[idx] = n.snow;
                    bakedWet[idx] = n.wet;
                    coverVersion++;
                }
            }
            if (next != null) objects = next;
        }
        if (f.ground != null) {
            ground = f.ground;
            coverVersion++;
        }
        // v2.4: the newest lists replace the ones kept; the drain state of a drains command is merged in by settlement
        if (f.water != null) water = f.water;
        if (f.flood != null) flood = f.flood;
        if (f.flow != null) flow = f.flow;          // v2.4.1 (a frame without the key keeps the runs, [] empties them)
        if (f.drains != null) {
            Map<Long, Model.Drain> dm = new HashMap<>(drains);
            for (Model.Drain d : f.drains.settlements) dm.put(d.id, d);
            drains = dm;
            drainMode = f.drains.mode;
        }
        // Phase O (OW1): the pieces already in the air move on, the tornadoes of this frame are noted (before its rows are looked at)
        if (wreck.frame(f, hello, objects)) coverVersion++;
        // v2.3 storm damage: it only ever grows, and it is part of the baked scenery
        if (f.damage.length > 0) {
            Model.Obj[] next = null;
            for (double[] d : f.damage) {
                Integer idx = objIndex.get((long) d[0]);
                if (idx == null) continue;
                Model.Obj o = (next == null ? objects : next)[idx];
                if (d[1] <= o.damage + 1e-4) continue;
                if (next == null) next = objects.clone();
                next[idx] = o.withDamage(d[1]);
                coverVersion++;
                wreck.row(o, next[idx], f);          // Phase O (OW1): under a grounded tornado the row tears pieces off; else a plain tile change
            }
            if (next != null) objects = next;
        }

        Set<Long> seen = new HashSet<>();
        for (Model.Cloud c : f.clouds) {
            seen.add(c.id);
            Life l = byId.get(c.id);
            boolean firstSeen = l == null || l.lastT <= 0;
            if (l == null) {
                l = new Life();
                l.id = c.id;
                Double b = c.stageTimes.get("forming");
                l.born = b != null ? b : f.t - c.age;
                lives.add(l);
                byId.put(c.id, l);
            }
            l.seed = c.seed;
            // v2.1: the same id may come back with a new type and name prefix (cumulus -> cumulonimbus):
            // it is the same life, so everything but the name / type shown carries on untouched
            if (c.fair) { l.fair = true; l.everFair = true; }
            else if (l.fair) { l.fair = false; l.developedT = f.t; }
            if (!c.fromType.isEmpty()) {
                if (l.fromType.isEmpty() && l.lastT > 0 && l.type.equals(c.fromType) && c.morph < 0.15) l.morphT = f.t;   // seen as it started
                l.fromType = c.fromType;
                l.morph = c.morph;
            }
            l.name = c.name;
            l.type = c.type;
            l.stage = c.stage;
            l.alive = true;
            l.died = Double.NaN;
            l.lastT = f.t;
            l.lifetime = c.lifetime;
            l.peakWater = Math.max(l.peakWater, c.water);
            l.totalPrecip = c.totalPrecip;
            if (!"none".equals(c.precipType)) l.precipType = c.precipType;
            if (!c.stageTimes.isEmpty()) l.stageTimes = c.stageTimes;
            if (c.puffs.length > 0) {
                // remember the cloud at its fullest: that is its portrait in the life card and the timeline
                double area = 0;
                for (double[] p : c.puffs) area += p[2] * p[2];
                area *= 0.25 + 0.75 * c.form;
                if (area >= l.puffArea * 0.97 || l.puffs.length == 0) {
                    l.puffs = c.puffs;
                    l.puffArea = Math.max(area, l.puffArea);
                    l.shear = c.shear;
                    l.anvil = c.anvil;
                }
            }
            l.tint = c.tint;
            l.lastX = c.x;
            l.lastY = c.y;
            // v2.3: what it absorbed, what is absorbing it, how hard it spins
            if (c.parts.length > 0) {
                if (c.parts.length > l.absorbed.size() && firstSeen == false) l.mergeTs.add(f.t);
                if (c.parts.length != l.absorbed.size() || firstSeen) {
                    l.absorbed = new ArrayList<>(c.parts.length);
                    l.absorbedTypes = new ArrayList<>(c.parts.length);
                    for (Model.Part pt : c.parts) { l.absorbed.add(pt.name.isEmpty() ? pt.type : pt.name); l.absorbedTypes.add(pt.type); }
                }
            }
            if (c.puffs == l.puffs) l.puffPart = c.puffPart;
            if (c.mergingInto >= 0) {
                l.mergedInto = c.mergingInto;
                Life into = byId.get(c.mergingInto);
                if (into != null) l.mergedIntoName = into.name;
            }
            l.peakRotation = Math.max(l.peakRotation, c.rotation);
        }
        for (Model.Death d : f.deaths) {
            Life l = byId.get(d.id);
            if (l == null) {
                l = new Life();
                l.id = d.id;
                l.seed = d.seed;
                lives.add(l);
                byId.put(d.id, l);
            }
            l.name = d.name;
            l.type = d.type;
            if (!d.fromType.isEmpty() && l.fromType.isEmpty()) { l.fromType = d.fromType; l.morph = 1; }
            l.born = d.born;
            l.died = d.died;
            l.lastT = d.died;
            l.alive = false;
            l.stage = "dead";
            l.lifetime = d.lifetime;
            l.peakWater = Math.max(l.peakWater, d.peakWater);
            l.totalPrecip = d.totalPrecip;
            if (!"none".equals(d.precipType) || l.precipType == null) l.precipType = d.precipType;
            if (!d.stageTimes.isEmpty()) l.stageTimes = d.stageTimes;
            if (d.puffs != null && l.puffs.length == 0) l.puffs = d.puffs;
            if (d.tint != null) l.tint = d.tint;
            if (d.mergedInto >= 0) {
                l.mergedInto = d.mergedInto;
                Life into = byId.get(d.mergedInto);
                if (into != null) l.mergedIntoName = into.name;
            }
            seen.add(d.id);
        }
        // v2.3 tornadoes: follow each one, close those that left the list
        for (Model.Tornado tn : f.tornadoes) {
            TornadoRec r = tornadoById.get(tn.id);
            if (r == null) {
                r = new TornadoRec();
                r.id = tn.id;
                r.cloud = tn.cloud;
                r.startT = f.t - tn.age;
                tornadoById.put(tn.id, r);
                tornadoLog.add(r);
                while (tornadoLog.size() > MAX_TORNADO_LOG) tornadoById.remove(tornadoLog.remove(0).id);
                Life l = byId.get(tn.cloud);
                if (l != null) l.tornadoes++;
            }
            Life l = byId.get(tn.cloud);
            if (l != null) { r.cloudName = l.name; l.peakEf = Math.max(l.peakEf, tn.onGround() ? tn.ef : -1); }
            r.stage = tn.stage;
            r.alive = true;
            r.endT = Double.NaN;
            r.peakWind = Math.max(r.peakWind, tn.windMs);
            r.peakWidth = Math.max(r.peakWidth, tn.width);
            if (tn.onGround()) {
                r.peakEf = Math.max(r.peakEf, tn.ef);
                if (Double.isNaN(r.touchT)) r.touchT = f.t;
                r.pathX1 = tn.x;
            }
            if (!Double.isNaN(tn.pathX0)) {
                r.pathX0 = tn.pathX0;
                if (Double.isNaN(r.pathX1)) r.pathX1 = tn.x;
            }
        }
        for (TornadoRec r : tornadoLog) {
            if (!r.alive) continue;
            boolean here = false;
            for (Model.Tornado tn : f.tornadoes) if (tn.id == r.id) { here = true; break; }
            if (!here) { r.alive = false; r.endT = f.t; r.stage = "gone"; }
        }
        // Clouds that vanished without a death record: close them at last sighting.
        for (Life l : lives) {
            if (l.alive && !seen.contains(l.id)) {
                l.alive = false;
                l.died = l.lastT;
                l.stage = "dead";
            }
        }
        for (String e : f.events) log(e);
    }

    synchronized Life selected() { return byId.get(selectedId); }

    // ------------------------------------------------------------------ history as plain data

    /** Cloud lifetimes + event log as plain data: {@code {"version":1,"lives":[...],"events":[...]}}. */
    synchronized Map<String, Object> exportHistory() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", 1.0);
        List<Object> ls = new ArrayList<>();
        for (Life l : lives) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", (double) l.id);
            m.put("seed", (double) l.seed);
            m.put("name", l.name);
            m.put("type", l.type);
            m.put("stage", l.stage);
            m.put("precip_type", l.precipType);
            m.put("born", l.born);
            m.put("died", Double.isNaN(l.died) ? null : (Object) l.died);
            m.put("last_t", l.lastT);
            m.put("lifetime", l.lifetime);
            m.put("peak_water", l.peakWater);
            m.put("total_precip_mm", l.totalPrecip);
            m.put("shear", l.shear);
            m.put("anvil", l.anvil);
            m.put("alive", l.alive);
            m.put("fair", l.fair);
            m.put("ever_fair", l.everFair);
            m.put("developed_t", Double.isNaN(l.developedT) ? null : (Object) l.developedT);
            if (!l.fromType.isEmpty()) {
                m.put("from_type", l.fromType);
                m.put("morph", l.morph);
                m.put("morph_t", Double.isNaN(l.morphT) ? null : (Object) l.morphT);
            }
            // v2.3 (only written when there is something to say, so older viewers and files are unaffected)
            if (!l.absorbed.isEmpty()) {
                m.put("absorbed", new ArrayList<Object>(l.absorbed));
                m.put("absorbed_types", new ArrayList<Object>(l.absorbedTypes));
                m.put("merge_ts", new ArrayList<Object>(l.mergeTs));
                if (l.puffPart != null && l.puffPart.length == l.puffs.length) {
                    List<Object> pp = new ArrayList<>(l.puffPart.length);
                    for (int v : l.puffPart) pp.add((double) v);
                    m.put("puff_part", pp);
                }
            }
            if (l.mergedInto >= 0) {
                m.put("merged_into", (double) l.mergedInto);
                m.put("merged_into_name", l.mergedIntoName);
            }
            if (l.peakRotation > 0) m.put("peak_rotation", l.peakRotation);
            if (l.tornadoes > 0) {
                m.put("tornadoes", (double) l.tornadoes);
                m.put("peak_ef", (double) l.peakEf);
            }
            m.put("stage_times", new LinkedHashMap<String, Object>(l.stageTimes));
            List<Object> pu = new ArrayList<>();
            for (double[] p : l.puffs) {
                List<Object> row = new ArrayList<>(4);
                for (double v : p) row.add(v);
                pu.add(row);
            }
            m.put("puffs", pu);
            List<Object> ti = new ArrayList<>(3);
            for (int v : l.tint) ti.add((double) v);
            m.put("tint", ti);
            ls.add(m);
        }
        out.put("lives", ls);
        out.put("events", new ArrayList<Object>(events));
        if (!tornadoLog.isEmpty()) {
            List<Object> ts = new ArrayList<>();
            for (TornadoRec r : tornadoLog) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", (double) r.id);
                m.put("cloud", (double) r.cloud);
                m.put("cloud_name", r.cloudName);
                m.put("start_t", r.startT);
                m.put("touch_t", Double.isNaN(r.touchT) ? null : (Object) r.touchT);
                m.put("end_t", Double.isNaN(r.endT) ? null : (Object) r.endT);
                m.put("peak_ef", (double) r.peakEf);
                m.put("peak_wind_ms", r.peakWind);
                m.put("peak_width", r.peakWidth);
                m.put("path_x0", Double.isNaN(r.pathX0) ? null : (Object) r.pathX0);
                m.put("path_x1", Double.isNaN(r.pathX1) ? null : (Object) r.pathX1);
                m.put("alive", r.alive);
                ts.add(m);
            }
            out.put("tornadoes", ts);
        }
        return out;
    }

    /** Restore what {@link #exportHistory()} produced (e.g. after {@code --load}). Clouds still alive are re-linked by id. */
    synchronized void importHistory(Map<String, Object> data) {
        if (data == null) return;
        List<Object> ls = Json.arr(data, "lives");
        if (ls != null) {
            lives.clear();
            byId.clear();
            for (Object o : ls) {
                Map<String, Object> m = Json.obj(o);
                if (m == null) continue;
                Life l = new Life();
                l.id = (long) Json.num(m, "id", -1);
                l.seed = (long) Json.num(m, "seed", 0);
                l.name = Json.str(m, "name", "");
                l.type = Json.str(m, "type", "cumulus");
                l.stage = Json.str(m, "stage", "dead");
                l.precipType = Json.str(m, "precip_type", "none");
                l.born = Json.num(m, "born", 0);
                l.died = Json.num(m, "died", Double.NaN);
                l.lastT = Json.num(m, "last_t", l.born);
                l.lifetime = Json.num(m, "lifetime", 0);
                l.peakWater = Json.num(m, "peak_water", 0);
                l.totalPrecip = Json.num(m, "total_precip_mm", 0);
                l.shear = Json.num(m, "shear", 0);
                l.anvil = Json.num(m, "anvil", 0);
                l.alive = Json.bool(m, "alive", false);
                // v2.1 fields; a history written before them simply has none (not fair, never morphed)
                l.fair = Json.bool(m, "fair", false);
                l.everFair = Json.bool(m, "ever_fair", l.fair);
                l.developedT = Json.num(m, "developed_t", Double.NaN);
                String ft = Json.str(m, "from_type", "");
                if (Model.fromKind(ft, l.type) >= 0) {
                    l.fromType = ft;
                    l.morph = Model.clamp(Json.num(m, "morph", 1), 0, 1);
                    l.morphT = Json.num(m, "morph_t", Double.NaN);
                }
                // v2.3 fields; a history written before them has none of these keys
                List<Object> ab = Json.arr(m, "absorbed"), abt = Json.arr(m, "absorbed_types"), mts = Json.arr(m, "merge_ts");
                if (ab != null) for (int k = 0; k < ab.size(); k++) {
                    l.absorbed.add(String.valueOf(ab.get(k)));
                    l.absorbedTypes.add(abt != null && k < abt.size() ? String.valueOf(abt.get(k)) : "cumulus");
                }
                if (mts != null) for (Object o2 : mts) if (o2 instanceof Number n) l.mergeTs.add(n.doubleValue());
                l.mergedInto = m.get("merged_into") instanceof Number mi ? mi.longValue() : -1;
                l.mergedIntoName = Json.str(m, "merged_into_name", "");
                l.peakRotation = Json.num(m, "peak_rotation", 0);
                l.tornadoes = (int) Json.num(m, "tornadoes", 0);
                l.peakEf = (int) Json.num(m, "peak_ef", -1);
                l.stageTimes = Model.stageTimes(Json.obj(m, "stage_times"));
                List<Object> pu = Json.arr(m, "puffs");
                if (pu != null) l.puffs = Model.puffs(pu);
                List<Object> ppl = Json.arr(m, "puff_part");
                if (ppl != null && ppl.size() == l.puffs.length) {
                    l.puffPart = new int[ppl.size()];
                    for (int k = 0; k < l.puffPart.length; k++) l.puffPart[k] = (int) Json.num(ppl.get(k), 0);
                }
                for (double[] p : l.puffs) l.puffArea += p[2] * p[2];
                List<Object> ti = Json.arr(m, "tint");
                if (ti != null && ti.size() >= 3) l.tint = Model.tint(ti);
                lives.add(l);
                byId.put(l.id, l);
            }
        }
        List<Object> tsl = Json.arr(data, "tornadoes");
        if (tsl != null) {
            tornadoLog.clear();
            tornadoById.clear();
            for (Object o : tsl) {
                Map<String, Object> m = Json.obj(o);
                if (m == null) continue;
                TornadoRec r = new TornadoRec();
                r.id = (long) Json.num(m, "id", -1);
                r.cloud = (long) Json.num(m, "cloud", -1);
                r.cloudName = Json.str(m, "cloud_name", "");
                r.startT = Json.num(m, "start_t", 0);
                r.touchT = Json.num(m, "touch_t", Double.NaN);
                r.endT = Json.num(m, "end_t", Double.NaN);
                r.peakEf = (int) Json.num(m, "peak_ef", 0);
                r.peakWind = Json.num(m, "peak_wind_ms", 0);
                r.peakWidth = Json.num(m, "peak_width", 0);
                r.pathX0 = Json.num(m, "path_x0", Double.NaN);
                r.pathX1 = Json.num(m, "path_x1", Double.NaN);
                r.alive = Json.bool(m, "alive", false);
                r.stage = r.alive ? "mature" : "gone";
                tornadoLog.add(r);
                tornadoById.put(r.id, r);
            }
        }
        List<Object> ev = Json.arr(data, "events");
        if (ev != null) {
            events.clear();
            for (Object o : ev) if (o != null) events.addLast(String.valueOf(o));
            while (events.size() > MAX_EVENTS) events.removeFirst();
        }
    }
}
