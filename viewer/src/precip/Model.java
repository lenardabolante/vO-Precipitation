package precip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed view of the PROTOCOL.md v2 messages. Unknown keys are ignored everywhere. */
final class Model {
    private Model() {}

    static final int PROTOCOL_VERSION = 2;

    static final String[] STAGES = {"forming", "growing", "mature", "precipitating", "dissipating"};

    static int stageIndex(String s) {
        for (int k = 0; k < STAGES.length; k++) if (STAGES[k].equals(s)) return k;
        return 0;
    }

    // cloud types (index = "kind" used by the cloud renderer)
    static final int CUMULUS = 0, CUMULONIMBUS = 1, NIMBOSTRATUS = 2, STRATOCUMULUS = 3, ALTOSTRATUS = 4,
            ALTOCUMULUS = 5, STRATUS = 6, CIRRUS = 7, SUPERCELL = 8;
    /** The nine cloud types (v2.3 added {@code supercell}); the index is the "kind". */
    static final String[] CLOUD_TYPES = {"cumulus", "cumulonimbus", "nimbostratus", "stratocumulus", "altostratus",
            "altocumulus", "stratus", "cirrus", "supercell"};
    static final String[] CLOUD_PREFIX = {"Cu", "Cb", "Ns", "Sc", "As", "Ac", "St", "Ci", "Sp"};
    static final String[] CLOUD_NOTE = {"fair-weather heaps", "thunderstorm tower", "rain sheet", "low lumpy rolls",
            "mid grey veil", "mid-level cloudlets", "low grey layer", "high ice streaks", "rotating storm, tornadoes"};
    static final int LAST_KIND = SUPERCELL;

    /** A flat layer type: absorbed into another cloud it stays a straight band (see CloudPainter). */
    static boolean sheetKind(int kind) { return kind == STRATUS || kind == ALTOSTRATUS || kind == NIMBOSTRATUS; }

    /** A heaped, flat-based convective type. */
    static boolean heapKind(int kind) { return kind == CUMULUS || kind == CUMULONIMBUS || kind == SUPERCELL; }

    /** A type with an anvil (the cumulonimbus family). */
    static boolean anvilKind(int kind) { return kind == CUMULONIMBUS || kind == SUPERCELL; }

    static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August", "September",
            "October", "November", "December"};
    static final String[] MONTHS_SHORT = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    private static final int[] MONTH_DAYS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    static final String[] SEASONS = {"winter", "spring", "summer", "autumn"};

    /** Calendar month index 0..11 of a protocol {@code month} (1.0 = 1 January .. 12.99). */
    static int monthIndex(double month) { return (int) Math.floorMod((long) Math.floor(month - 1), 12L); }

    /** Day of the month 1..31 of a protocol {@code month}. */
    static int dayOfMonth(double month) {
        double fr = month - Math.floor(month);
        int n = MONTH_DAYS[monthIndex(month)];
        return Math.max(1, Math.min(n, (int) Math.floor(fr * n) + 1));
    }

    /** Meteorological season of a month (flipped in the southern hemisphere). */
    static String seasonOf(double month, String hemisphere) {
        int m = monthIndex(month);
        int s = m == 11 || m < 2 ? 0 : m < 5 ? 1 : m < 8 ? 2 : 3;
        if ("S".equals(hemisphere)) s = (s + 2) % 4;
        return SEASONS[s];
    }

    static int cloudKind(String type) {
        for (int k = 0; k < CLOUD_TYPES.length; k++) if (CLOUD_TYPES[k].equals(type)) return k;
        return CUMULUS;
    }

    /** Kind index of a {@code from_type}, or -1 when it is absent / unknown / the same as the current type. */
    static int fromKind(String fromType, String type) {
        if (fromType == null || fromType.isEmpty() || fromType.equals(type)) return -1;
        for (int k = 0; k < CLOUD_TYPES.length; k++) if (CLOUD_TYPES[k].equals(fromType)) return k;
        return -1;
    }

    /** The fixed terrain heightmap of a world. */
    static final class Terrain {
        final double step;
        final double[] y;
        final double minY, maxY;   // highest point (smallest y) and lowest point (largest y)

        Terrain(double step, double[] y) {
            this.step = Math.max(1, step);
            this.y = y;
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (double v : y) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
            minY = lo; maxY = hi;
        }

        /** Ground-surface y at world x (linear interpolation, clamped at the ends). */
        double at(double x) {
            double g = x / step;
            if (g <= 0) return y[0];
            int i = (int) g;
            if (i >= y.length - 1) return y[y.length - 1];
            double f = g - i;
            return y[i] + (y[i + 1] - y[i]) * f;
        }

        /** dy/dx at x (positive = surface descends to the right). */
        double slope(double x) {
            int i = (int) Math.max(0, Math.min(y.length - 2, Math.floor(x / step)));
            return (y[i + 1] - y[i]) / step;
        }

        static Terrain flat(double w, double groundY) {
            int n = (int) Math.ceil(w / 8) + 1;
            double[] a = new double[n];
            java.util.Arrays.fill(a, groundY);
            return new Terrain(8, a);
        }
    }

    static final class Settlement {
        long id;
        String kind = "village", name = "";
        double x0, x1;
        int population;
        /** v2.4: the settlement's storm drains as the hello gave them; null = none known (an older stream): draw none. */
        Drain drain;
    }

    /** v2.4 {@code hello.water}: its presence means the stream uses the standing-water model (legacy puddle objects are then not drawn). */
    static final class Water {
        int model = 1;
        double basinMinDepth = 1;
    }

    /** v2.4 {@code hello.drains}: the constants the viewer needs. */
    static final class Drains {
        String mode = "normal";
        double inletSpacing = 60, curb = 10, floodDepth = 3, sizeMin = 1.5, sizeMax = 12;
        /** v2.4.1: {@code overflowing} from this depth (the default 8 when the stream does not say). */
        double overflowDepth = 8;
        /** Pipe size in px of each class, in the order sent. */
        Map<String, Double> classes = new LinkedHashMap<>();
    }

    /** v2.4: the drains of one settlement ({@code settlements[].drain}, and an entry of {@code frame.drains}). Immutable once published. */
    static final class Drain {
        /** The settlement (only set for an entry of {@code frame.drains}). */
        long id = -1;
        double size, cap;
        String cls = "normal";
        int inlets = 1;

        /** World x of inlet {@code i} (0-based) of a settlement spanning x0..x1: {@code x0 + (i + 0.5) (x1 - x0) / inlets}. */
        double inletX(int i, double x0, double x1) { return x0 + (i + 0.5) * (x1 - x0) / Math.max(1, inlets); }
    }

    /** v2.4: one body of standing water ({@code frame.water}). */
    static final class Body {
        long id, settlement = -1;
        String kind = "pool";
        /** The surface (world y), where it meets the ground on the left and right, the deepest point, the depth there and the area. */
        double x, x0, x1, y, depth, vol;
        boolean spill;

        boolean street() { return kind.equals("street"); }
    }

    /** v2.4: the flood state of one settlement ({@code frame.flood}). */
    static final class Flood {
        long id;
        String state = "dry";
        double depth, inflow, cap;
        /** v2.4.1: a body that came from a hollow covers part of the settlement's span (false when not sent). */
        boolean backed;

        /** "The city floods" means flooded or overflowing. */
        boolean flooded() { return state.equals("flooded") || state.equals("overflowing"); }
    }

    /** v2.4 {@code frame.drains}: the full drain state after a {@code drains} command. */
    static final class DrainState {
        String mode = "custom";
        List<Drain> settlements = new ArrayList<>();
    }

    static final String[] FLOOD_STATES = {"dry", "wet", "ponding", "flooded", "overflowing"};

    /** v2.4.1 {@code hello.flow}: its presence means the stream carries the film of water on the ground (no virtual curb). */
    static final class Flow {
        int model = 1;
        /** Width of a film cell, px (cell {@code k} is centred at {@code x = cell * k}), and the thinnest film that is sent. */
        double cell = 32, minH = 0.05;
    }

    /**
     * v2.4.1: one run of {@code frame.flow}: a chain of neighbouring cells, downhill. Cell {@code i} is centred at
     * {@code x = cell * (k0 + dir * i)}; {@code h[i]} is the depth of the film there (px), {@code q[i]} the flux leaving it (px²/s).
     */
    static final class Run {
        int k0, dir = 1;
        /** The body (or the street's settlement) that feeds the first cell, 0 = rain only; the body the last cell runs into, -1 = a world edge, -2 = a pond. */
        long src, dst;
        double[] h = new double[0], q = new double[0];
    }

    static final class Hello {
        long seed;
        int version = PROTOCOL_VERSION;
        boolean loaded;
        double t;
        int w = 3840, h = 1080;
        double groundY = 1000;
        String terrainKind = "rolling";
        Terrain terrain = Terrain.flat(3840, 1000);
        List<Settlement> settlements = new ArrayList<>();
        List<Obj> objects = new ArrayList<>();
        // --- v2.2 ---
        /** {@code sandbox | showcase | hurricane}; absent on the wire = sandbox. */
        String mode = "sandbox";
        /** The region preset this world was built from; null for an ordinary world. */
        Preset preset;
        /** The raw {@code showcase} / {@code hurricane} blocks of the hello (parsed by ShowcaseModel / HurricaneModel); null when absent. */
        Map<String, Object> showcaseRaw, hurricaneRaw;
        // --- v2.3 ---
        /** The world's climate ({@code hello.climate}); null when the simulation sent none. */
        Climate climate;
        /** Road spans along the terrain surface ({@code hello.roads}); empty when there are none. */
        List<Road> roads = new ArrayList<>();
        // --- v2.4 ---
        /** {@code hello.water}; null = a stream without the standing-water model (v2.3 and older, replays, the lesson modes). */
        Water water;
        /** {@code hello.drains}; null when the stream has no drains. */
        Drains drains;
        // --- v2.4.1 ---
        /** {@code hello.flow}; null = a v2.4 (or older) stream: streets with a virtual curb, the viewer's own trickle. */
        Flow flow;
    }

    /** v2.3 {@code hello.climate}. */
    static final class Climate {
        double yearDays = 8, dayLengthS = 300, lat = Double.NaN, diurnalRangeC = Double.NaN, baseC = Double.NaN, startMonth = Double.NaN;
        String hemisphere = "N", source = "";
        double[] monthlyTempC = new double[0];
    }

    /** v2.3: a stretch of the terrain surface painted as road. */
    static final class Road {
        double x0, x1;
        String kind = "street";

        boolean highway() { return kind.equals("highway"); }
    }

    /** v2.3: a cloud this one absorbed. */
    static final class Part {
        long id = -1;
        String type = "cumulus", name = "";
        int kind;

        /** "St-0017" from {@code St-0017 "Low Veil"}. */
        String shortName() {
            int q = name.indexOf(' ');
            return q > 0 ? name.substring(0, q) : name.isEmpty() ? CLOUD_PREFIX[kind] : name;
        }
    }

    /** v2.3 {@code world.temp}: what the surface temperature is made of. */
    static final class Temp {
        double baseC, seasonC, diurnalC, gasC, userC, dayMinC = Double.NaN, dayMaxC = Double.NaN;
    }

    /** v2.3 {@code frame.atmosphere}. */
    static final class Atmosphere {
        double humidity, co2 = 420, aerosol, instability, shear, spawnPerMin = Double.NaN, cloudTarget = Double.NaN;
        /** Chance of each type for the next spawned cloud, in the order sent. */
        Map<String, Double> typeWeights = new LinkedHashMap<>();
    }

    /** v2.3: one tornado. */
    static final class Tornado {
        long id, cloud = -1;
        String stage = "funnel";
        double x, baseY, groundY, reach, width, topWidth, lean, strength, windMs, age, phase, debris, pathX0 = Double.NaN;
        int ef;

        boolean onGround() { return reach >= 0.995; }
    }

    /** v2.3: one car. */
    static final class Car {
        long id;
        double x, y, angle, speed, lift, spin, wet, snow;
        /** v2.4: the depth of standing water at the car, px (0 = none, or not sent). */
        double depth;
        int dir = 1;
        String variant = "sedan", state = "driving";
        int[] color = {178, 34, 34};
        boolean lights, wipers;
    }

    static final class Cloud {
        long id, seed;
        String name = "", type = "cumulus", stage = "forming", precipType = "none";
        int kind;
        double x, y, w, h, age, lifetime, lifeFrac, water, ice, updraft, precipRate, totalPrecip;
        double form = 1, shear, anvil;
        double[][] puffs = new double[0][];
        int[] tint = {230, 234, 240};
        double opacity = 0.9, darkness = 0.2;
        Map<String, Double> stageTimes = new LinkedHashMap<>();
        /** v2.1: an ordinary fair-weather cloud that carries no rain (yet). */
        boolean fair;
        /** v2.1: the type this cloud grew out of ("" = it has always been its {@link #type}). */
        String fromType = "";
        /** Kind index of {@link #fromType}, -1 when there is none. */
        int fromKind = -1;
        /** v2.1: progress 0..1 of the change from {@link #fromType} into {@link #type}. */
        double morph;
        // --- v2.3 ---
        /** Clouds this one has absorbed, in merge order (empty = none). */
        Part[] parts = NO_PARTS;
        /** Progress 0..1 of the newest merger (1 when there is none). */
        double merge = 1;
        /** Per puff: 0 = the cloud's own, k = it came from {@code parts[k-1]}; null when not sent. */
        int[] puffPart;
        /** The id of the cloud absorbing this one (-1 = it is not being absorbed). */
        long mergingInto = -1;
        /** Supercell: mesocyclone strength, wall-cloud lowering, overshooting top, rear-flank downdraft (all 0..1) and the wall cloud's world x (NaN = not sent). */
        double rotation, wall, overshoot, hook, mesoX = Double.NaN;

        /** True while the newest absorbed cloud is still swelling into this one. */
        boolean merging() { return parts.length > 0 && merge < 1; }

        /** True when puff {@code k} came from an absorbed flat layer. */
        boolean sheetPuff(int k) {
            if (puffPart == null || k >= puffPart.length) return false;
            int p = puffPart[k];
            return p >= 1 && p <= parts.length && sheetKind(parts[p - 1].kind);
        }

        /** True while the cloud is still turning from its old type into its new one. */
        boolean morphing() { return fromKind >= 0 && fromKind != kind && morph < 1; }
    }

    static final Part[] NO_PARTS = new Part[0];

    static final class Death {
        long id, seed;
        /** v2.3: the id of the cloud that absorbed it (-1 = it simply dissipated). */
        long mergedInto = -1;
        String name = "", type = "cumulus", precipType = "none", fromType = "";
        double born, died, lifetime, peakWater, totalPrecip;
        Map<String, Double> stageTimes = new LinkedHashMap<>();
        double[][] puffs;
        int[] tint;
    }

    static final class Arrow {
        double x1, y1, x2, y2, mag;
        String kind = "wind", label = "";
    }

    /** A world object. Instances are immutable once published: state changes replace the instance. */
    static final class Obj {
        long id, seed, settlement = -1;
        String kind = "tree", variant = "";
        double x, y, w, h, sway, snow, wet;
        /** v2.3: storm damage 0..1 (never decreases). */
        double damage;
        int[] wall, roof;
        int floors = 1, layer;
        boolean building;

        Obj withState(double sway, double snow, double wet, double w, double h) {
            Obj o = new Obj();
            o.id = id; o.seed = seed; o.settlement = settlement; o.kind = kind; o.variant = variant;
            o.x = x; o.y = y; o.wall = wall; o.roof = roof; o.floors = floors; o.layer = layer; o.building = building;
            o.sway = sway; o.snow = snow; o.wet = wet; o.w = w; o.h = h;
            o.damage = damage;
            return o;
        }

        Obj withDamage(double d) {
            Obj o = withState(sway, snow, wet, w, h);
            o.damage = clamp(d, 0, 1);
            return o;
        }
    }

    static boolean isBuilding(String kind) {
        return switch (kind) {
            case "house", "barn", "church", "windmill", "shop", "apartment", "office", "skyscraper", "factory",
                 "water_tower", "streetlamp" -> true;
            default -> false;
        };
    }

    /** Snow cover / wetness of the terrain surface, sampled every {@code step} px. */
    static final class Ground {
        double step = 32;
        double[] snow = new double[0], wet = new double[0];

        static double sample(double[] a, double step, double x) {
            if (a.length == 0) return 0;
            double g = x / step;
            if (g <= 0) return a[0];
            int i = (int) g;
            if (i >= a.length - 1) return a[a.length - 1];
            double f = g - i;
            return a[i] + (a[i + 1] - a[i]) * f;
        }

        double snowAt(double x) { return sample(snow, step, x); }

        double wetAt(double x) { return sample(wet, step, x); }
    }

    static final class Saved {
        boolean ok;
        String path = "", error;
        double t;
        long frame;
    }

    static final class Bolt {
        double[] pts; // x0,y0,x1,y1,...
        double alpha;
    }

    static final class Streamline {
        double[] pts;
        double speed, phase;
    }

    static final class Frame {
        long frame;
        double t, speed = 1, groundY = 1000, freezingY = 500, surfaceTemp, timeOfDay = 0.5;
        boolean paused;
        int w = 3840, h = 1080;
        int wCols, wRows;
        double wCell = 80;
        double[] u = new double[0], v = new double[0];
        List<Streamline> streamlines = Collections.emptyList();
        List<Cloud> clouds = Collections.emptyList();
        float[] particles = new float[0];
        int particleCount;
        /**
         * Settings > Advanced "Max particles": per particle a rank 0..99 that it keeps from frame to frame (it is drawn while its
         * rank is below the percentage). null = this frame was not ranked. See {@code ViewerState.rankParticles}.
         */
        byte[] particleRank;
        List<Arrow> arrows = Collections.emptyList();
        /** Object state deltas: rows of {id, sway, snow, wet, w, h}. */
        double[][] objDeltas = new double[0][];
        Ground ground; // null unless this frame carried one
        List<Bolt> lightning = Collections.emptyList();
        Map<String, Double> stats = new LinkedHashMap<>();
        List<String> events = Collections.emptyList();
        List<Death> deaths = Collections.emptyList();
        long recvNanos;
        /** v2.2 showcase mode: the type on stage, the cycle number and the cloud living it (-1 during the pause between cycles); null type = not a showcase frame. */
        String showcaseType;
        int showcaseCycle;
        long showcaseCloudId = -1;
        // --- v2.3 (everything here is absent in a v2.2 stream) ---
        /** Showcase: the id of the lifetime-tour step the cloud on stage is at; null = none. */
        String tourStep;
        /** Calendar: 0..1 through the year, month 1..12.99, whole sim days since the world started (NaN / -1 = not sent). */
        double yearFrac = Double.NaN, month = Double.NaN;
        int day = -1;
        String season = "", hemisphere = "N";
        /** The temperature breakdown; null when not sent. */
        Temp temp;
        /** Gases and what they do to the spawner; null when not sent. */
        Atmosphere atmosphere;
        List<Tornado> tornadoes = Collections.emptyList();
        List<Car> cars = Collections.emptyList();
        /** Damage deltas: rows of {id, damage}. */
        double[][] damage = new double[0][];
        // --- v2.4 (each is null unless this frame carried the key; an empty list is "everything is dry") ---
        /** The complete list of standing bodies. */
        List<Body> water;
        /** One entry per settlement. */
        List<Flood> flood;
        /** The drain state after a {@code drains} command. */
        DrainState drains;
        /** v2.4.1: the complete list of runs of the film (null unless this frame carried the key; empty = no flow). */
        List<Run> flow;

        boolean hasCalendar() { return !Double.isNaN(month); }

        Tornado tornadoOf(long cloudId) {
            for (Tornado t : tornadoes) if (t.cloud == cloudId) return t;
            return null;
        }

        Cloud cloud(long id) {
            for (Cloud c : clouds) if (c.id == id) return c;
            return null;
        }

        double stat(String k) {
            Double d = stats.get(k);
            return d == null ? 0 : d;
        }

        /** The horizontal wind u in m/s at a world point (positive blows to the right), bilinear; 0 without a wind grid. Allocates nothing. */
        double windU(double x, double y) {
            if (wCols <= 0 || wRows <= 0 || u.length < wCols * wRows) return 0;
            double gx = x / wCell - 0.5, gy = y / wCell - 0.5;
            int c0 = (int) Math.floor(gx), r0 = (int) Math.floor(gy);
            double fx = gx - c0, fy = gy - r0;
            int ca = Math.max(0, Math.min(wCols - 1, c0)), cb = Math.max(0, Math.min(wCols - 1, c0 + 1));
            int ra = Math.max(0, Math.min(wRows - 1, r0)) * wCols, rb = Math.max(0, Math.min(wRows - 1, r0 + 1)) * wCols;
            return (u[ra + ca] * (1 - fx) + u[ra + cb] * fx) * (1 - fy) + (u[rb + ca] * (1 - fx) + u[rb + cb] * fx) * fy;
        }

        /** Wind (u,v) in m/s at world point, bilinear. */
        double[] windAt(double x, double y) {
            if (wCols <= 0 || wRows <= 0 || u.length < wCols * wRows) return new double[]{0, 0};
            double gx = x / wCell - 0.5, gy = y / wCell - 0.5;
            int c0 = (int) Math.floor(gx), r0 = (int) Math.floor(gy);
            double fx = gx - c0, fy = gy - r0;
            double uu = 0, vv = 0;
            for (int dr = 0; dr <= 1; dr++) for (int dc = 0; dc <= 1; dc++) {
                int c = Math.max(0, Math.min(wCols - 1, c0 + dc));
                int r = Math.max(0, Math.min(wRows - 1, r0 + dr));
                double wgt = (dc == 0 ? 1 - fx : fx) * (dr == 0 ? 1 - fy : fy);
                uu += u[r * wCols + c] * wgt;
                vv += v[r * wCols + c] * wgt;
            }
            return new double[]{uu, vv};
        }
    }

    // ------------------------------------------------------------------ parsing

    static Hello parseHello(Map<String, Object> m) {
        Hello h = new Hello();
        h.seed = (long) Json.num(m, "seed", 0);
        h.version = (int) Json.num(m, "version", 1);
        h.loaded = Json.bool(m, "loaded", false);
        h.t = Json.num(m, "t", 0);
        Map<String, Object> w = Json.obj(m, "world");
        h.w = (int) Json.num(w, "w", 3840);
        h.h = (int) Json.num(w, "h", 1080);
        h.groundY = Json.num(w, "ground_y", h.h - 80);
        h.terrainKind = Json.str(w, "terrain_kind", "flat");
        Map<String, Object> tr = Json.obj(w, "terrain");
        double[] ty = tr == null ? new double[0] : nums(Json.arr(tr, "y"));
        h.terrain = ty.length >= 2 ? new Terrain(Json.num(tr, "step", 8), ty) : Terrain.flat(h.w, h.groundY);

        List<Object> sl = Json.arr(m, "settlements");
        if (sl != null) for (Object o : sl) {
            Map<String, Object> s = Json.obj(o);
            if (s == null) continue;
            Settlement st = new Settlement();
            st.id = (long) Json.num(s, "id", h.settlements.size() + 1);
            st.kind = Json.str(s, "kind", "village");
            st.name = Json.str(s, "name", "");
            st.x0 = Json.num(s, "x0", 0);
            st.x1 = Json.num(s, "x1", st.x0);
            st.population = (int) Json.num(s, "population", 0);
            st.drain = parseDrain(Json.obj(s, "drain"));
            h.settlements.add(st);
        }

        List<Object> ol = Json.arr(m, "objects");
        if (ol != null) {
            long autoId = -1;
            for (Object o : ol) {
                Map<String, Object> a = Json.obj(o);
                if (a == null) continue;
                Obj ob = new Obj();
                ob.id = (long) Json.num(a, "id", autoId--);
                ob.kind = Json.str(a, "kind", "tree");
                ob.variant = Json.str(a, "variant", "");
                ob.x = Json.num(a, "x", 0);
                ob.y = Json.num(a, "y", h.terrain.at(ob.x));
                ob.w = Json.num(a, "w", 30); ob.h = Json.num(a, "h", 30);
                ob.sway = Json.num(a, "sway", 0);
                ob.snow = Json.num(a, "snow", 0);
                ob.wet = Json.num(a, "wet", 0);
                ob.seed = (long) Json.num(a, "seed", (long) (ob.x * 7919 + ob.w * 31 + ob.id * 104729L));
                List<Object> c = Json.arr(a, "wall");
                if (c != null && c.size() >= 3) ob.wall = tint(c);
                c = Json.arr(a, "roof");
                if (c != null && c.size() >= 3) ob.roof = tint(c);
                ob.floors = Math.max(1, (int) Json.num(a, "floors", 1));
                ob.settlement = (long) Json.num(a, "settlement", -1);
                ob.layer = (int) Json.num(a, "layer", 0);
                ob.damage = clamp(Json.num(a, "damage", 0), 0, 1);
                ob.building = isBuilding(ob.kind);
                h.objects.add(ob);
            }
        }
        h.mode = Json.str(m, "mode", "sandbox");
        h.preset = Preset.parse(Json.obj(m, "preset"));
        h.showcaseRaw = Json.obj(m, "showcase");
        h.hurricaneRaw = Json.obj(m, "hurricane");
        // ---- v2.3
        Map<String, Object> cl = Json.obj(m, "climate");
        if (cl != null) {
            Climate c = new Climate();
            c.yearDays = Json.num(cl, "year_days", 8);
            c.dayLengthS = Json.num(cl, "day_length_s", 300);
            c.hemisphere = Json.str(cl, "hemisphere", "N");
            c.lat = Json.num(cl, "lat", Double.NaN);
            c.monthlyTempC = nums(Json.arr(cl, "monthly_temp_c"));
            c.diurnalRangeC = Json.num(cl, "diurnal_range_c", Double.NaN);
            c.baseC = Json.num(cl, "base_c", Double.NaN);
            c.startMonth = Json.num(cl, "start_month", Double.NaN);
            c.source = Json.str(cl, "source", "");
            h.climate = c;
        }
        List<Object> rl = Json.arr(m, "roads");
        if (rl != null) for (Object o : rl) {
            Map<String, Object> a = Json.obj(o);
            if (a == null) continue;
            Road r = new Road();
            r.x0 = Json.num(a, "x0", 0);
            r.x1 = Json.num(a, "x1", r.x0);
            r.kind = Json.str(a, "kind", "street");
            if (r.x1 < r.x0) { double t = r.x0; r.x0 = r.x1; r.x1 = t; }
            if (r.x1 - r.x0 >= 1) h.roads.add(r);
        }
        // ---- v2.4: standing water and drains (both absent in an older stream)
        Map<String, Object> wa = Json.obj(m, "water");
        if (wa != null) {
            h.water = new Water();
            h.water.model = (int) Json.num(wa, "model", 1);
            h.water.basinMinDepth = Json.num(wa, "basin_min_depth", 1);
        }
        Map<String, Object> dr = Json.obj(m, "drains");
        if (dr != null) {
            Drains d = new Drains();
            d.mode = Json.str(dr, "mode", "normal");
            d.inletSpacing = Math.max(1, Json.num(dr, "inlet_spacing", 60));
            d.curb = Json.num(dr, "curb", 10);
            d.floodDepth = Json.num(dr, "flood_depth", 3);
            d.overflowDepth = Json.num(dr, "overflow_depth", 8);
            d.sizeMin = Json.num(dr, "size_min", 1.5);
            d.sizeMax = Json.num(dr, "size_max", 12);
            Map<String, Object> cs = Json.obj(dr, "classes");
            if (cs != null) for (Map.Entry<String, Object> e : cs.entrySet()) if (e.getValue() instanceof Number n) d.classes.put(e.getKey(), n.doubleValue());
            h.drains = d;
        }
        // ---- v2.4.1: the film of water on the ground (absent in a v2.4 stream)
        Map<String, Object> fw = Json.obj(m, "flow");
        if (fw != null) {
            h.flow = new Flow();
            h.flow.model = (int) Json.num(fw, "model", 1);
            h.flow.cell = Math.max(1, Json.num(fw, "cell", 32));
            h.flow.minH = Math.max(0, Json.num(fw, "min_h", 0.05));
        }
        return h;
    }

    /** A {@code drain} block ({@code size, class, inlets, cap}); null when there is none. */
    static Drain parseDrain(Map<String, Object> m) {
        if (m == null) return null;
        Drain d = new Drain();
        d.id = (long) Json.num(m, "id", -1);
        d.size = Math.max(0, Json.num(m, "size", 0));
        d.cls = Json.str(m, "class", "normal");
        d.inlets = Math.max(1, (int) Json.num(m, "inlets", 1));
        d.cap = Math.max(0, Json.num(m, "cap", 0));
        return d;
    }

    static Saved parseSaved(Map<String, Object> m) {
        Saved s = new Saved();
        s.ok = Json.bool(m, "ok", false);
        s.path = Json.str(m, "path", "");
        Object e = m.get("error");
        s.error = e == null ? null : String.valueOf(e);
        s.t = Json.num(m, "t", 0);
        s.frame = (long) Json.num(m, "frame", 0);
        return s;
    }

    static Frame parseFrame(Map<String, Object> m) {
        Frame f = new Frame();
        f.frame = (long) Json.num(m, "frame", 0);
        f.t = Json.num(m, "t", 0);
        f.speed = Json.num(m, "speed", 1);
        f.paused = Json.bool(m, "paused", false);
        Map<String, Object> w = Json.obj(m, "world");
        f.w = (int) Json.num(w, "w", 3840);
        f.h = (int) Json.num(w, "h", 1080);
        f.groundY = Json.num(w, "ground_y", f.h - 80);
        f.freezingY = Json.num(w, "freezing_y", f.groundY * 0.5);
        f.surfaceTemp = Json.num(w, "surface_temp_c", 5);
        f.timeOfDay = Json.num(w, "time_of_day", 0.5);
        // ---- v2.3 calendar and temperature breakdown (all optional)
        if (w != null && w.get("month") instanceof Number) {
            f.month = clamp(Json.num(w, "month", 1), 1, 12.9999);
            f.yearFrac = Json.num(w, "year_frac", (f.month - 1) / 12);
            f.day = (int) Json.num(w, "day", 0);
            f.hemisphere = Json.str(w, "hemisphere", "N");
            f.season = Json.str(w, "season", seasonOf(f.month, f.hemisphere));
        }
        Map<String, Object> tp = Json.obj(w, "temp");
        if (tp != null) {
            Temp t = new Temp();
            t.baseC = Json.num(tp, "base_c", 0);
            t.seasonC = Json.num(tp, "season_c", 0);
            t.diurnalC = Json.num(tp, "diurnal_c", 0);
            t.gasC = Json.num(tp, "gas_c", 0);
            t.userC = Json.num(tp, "user_c", 0);
            t.dayMinC = Json.num(tp, "day_min_c", Double.NaN);
            t.dayMaxC = Json.num(tp, "day_max_c", Double.NaN);
            f.temp = t;
        }
        Map<String, Object> at = Json.obj(m, "atmosphere");
        if (at != null) {
            Atmosphere a = new Atmosphere();
            a.humidity = clamp(Json.num(at, "humidity", 0), 0, 1);
            a.co2 = Json.num(at, "co2_ppm", 420);
            a.aerosol = clamp(Json.num(at, "aerosol", 0), 0, 1);
            a.instability = clamp(Json.num(at, "instability", 0), 0, 1);
            a.shear = clamp(Json.num(at, "shear", 0), 0, 1);
            a.spawnPerMin = Json.num(at, "spawn_per_min", Double.NaN);
            a.cloudTarget = Json.num(at, "cloud_target", Double.NaN);
            Map<String, Object> tw = Json.obj(at, "type_weights");
            if (tw != null) for (Map.Entry<String, Object> e : tw.entrySet())
                if (e.getValue() instanceof Number n) a.typeWeights.put(e.getKey(), Math.max(0, n.doubleValue()));
            f.atmosphere = a;
        }

        Map<String, Object> wind = Json.obj(m, "wind");
        if (wind != null) {
            f.wCols = (int) Json.num(wind, "cols", 0);
            f.wRows = (int) Json.num(wind, "rows", 0);
            f.wCell = Math.max(1, Json.num(wind, "cell", 80));
            f.u = nums(Json.arr(wind, "u"));
            f.v = nums(Json.arr(wind, "v"));
            if (f.u.length < f.wCols * f.wRows || f.v.length < f.wCols * f.wRows) { f.wCols = 0; f.wRows = 0; }
        }

        List<Object> sl = Json.arr(m, "streamlines");
        if (sl != null) {
            f.streamlines = new ArrayList<>(sl.size());
            for (Object o : sl) {
                Map<String, Object> s = Json.obj(o);
                if (s == null) continue;
                Streamline st = new Streamline();
                st.pts = pts(Json.arr(s, "pts"));
                st.speed = Json.num(s, "speed", 3);
                st.phase = Json.num(s, "phase", 0);
                if (st.pts.length >= 4) f.streamlines.add(st);
            }
        }

        List<Object> cl = Json.arr(m, "clouds");
        if (cl != null) {
            f.clouds = new ArrayList<>(cl.size());
            for (Object o : cl) {
                Map<String, Object> c = Json.obj(o);
                if (c != null) f.clouds.add(parseCloud(c));
            }
        }

        List<Object> pl = Json.arr(m, "particles");
        if (pl != null) {
            float[] p = new float[pl.size() * 7];
            int n = 0;
            for (Object o : pl) {
                List<Object> a = Json.arr(o);
                if (a == null || a.size() < 3) continue;
                int b = n * 7;
                for (int k = 0; k < 7; k++) p[b + k] = k < a.size() ? (float) Json.num(a.get(k), 0) : 0f;
                if (a.size() < 6) p[b + 5] = 2f;
                n++;
            }
            f.particles = p;
            f.particleCount = n;
        }

        List<Object> al = Json.arr(m, "arrows");
        if (al != null) {
            f.arrows = new ArrayList<>(al.size());
            for (Object o : al) {
                Map<String, Object> a = Json.obj(o);
                if (a == null) continue;
                Arrow ar = new Arrow();
                ar.x1 = Json.num(a, "x1", 0); ar.y1 = Json.num(a, "y1", 0);
                ar.x2 = Json.num(a, "x2", 0); ar.y2 = Json.num(a, "y2", 0);
                ar.kind = Json.str(a, "kind", "wind");
                ar.mag = Json.num(a, "mag", 0);
                ar.label = Json.str(a, "label", "");
                f.arrows.add(ar);
            }
        }

        List<Object> ol = Json.arr(m, "objects");
        if (ol != null && !ol.isEmpty()) {
            List<double[]> rows = new ArrayList<>(ol.size());
            for (Object o : ol) {
                List<Object> a = Json.arr(o);
                if (a == null || a.size() < 4) continue;
                rows.add(new double[]{Json.num(a.get(0), -1), Json.num(a.get(1), 0), Json.num(a.get(2), 0), Json.num(a.get(3), 0),
                        a.size() > 4 ? Json.num(a.get(4), Double.NaN) : Double.NaN,
                        a.size() > 5 ? Json.num(a.get(5), Double.NaN) : Double.NaN});
            }
            f.objDeltas = rows.toArray(new double[0][]);
        }

        Map<String, Object> gr = Json.obj(m, "ground");
        if (gr != null) {
            Ground g = new Ground();
            g.step = Math.max(1, Json.num(gr, "step", 32));
            g.snow = nums(Json.arr(gr, "snow"));
            g.wet = nums(Json.arr(gr, "wet"));
            f.ground = g;
        }

        List<Object> ll = Json.arr(m, "lightning");
        if (ll != null) {
            f.lightning = new ArrayList<>(ll.size());
            for (Object o : ll) {
                Map<String, Object> a = Json.obj(o);
                if (a == null) continue;
                Bolt b = new Bolt();
                b.pts = pts(Json.arr(a, "pts"));
                b.alpha = Json.num(a, "alpha", 1);
                if (b.pts.length >= 4) f.lightning.add(b);
            }
        }

        Map<String, Object> st = Json.obj(m, "stats");
        if (st != null) for (Map.Entry<String, Object> e : st.entrySet()) f.stats.put(e.getKey(), Json.num(e.getValue(), 0));

        List<Object> ev = Json.arr(m, "events");
        if (ev != null && !ev.isEmpty()) {
            f.events = new ArrayList<>(ev.size());
            for (Object o : ev) if (o != null) f.events.add(String.valueOf(o));
        }

        Map<String, Object> sc = Json.obj(m, "showcase");
        if (sc != null) {
            f.showcaseType = Json.str(sc, "type", "cumulus");
            f.showcaseCycle = (int) Json.num(sc, "cycle", 1);
            f.showcaseCloudId = sc.get("cloud_id") instanceof Number n ? n.longValue() : -1;
            f.tourStep = sc.get("tour_step") instanceof String ts && !ts.isEmpty() ? ts : null;
        }

        // ---- v2.3: tornadoes, cars, damage
        List<Object> tl = Json.arr(m, "tornadoes");
        if (tl != null && !tl.isEmpty()) {
            f.tornadoes = new ArrayList<>(tl.size());
            for (Object o : tl) {
                Map<String, Object> a = Json.obj(o);
                if (a == null) continue;
                Tornado t = new Tornado();
                t.id = (long) Json.num(a, "id", f.tornadoes.size() + 1);
                t.cloud = (long) Json.num(a, "cloud", -1);
                t.stage = Json.str(a, "stage", "funnel");
                t.x = Json.num(a, "x", 0);
                t.groundY = Json.num(a, "ground_y", f.groundY);
                t.baseY = Json.num(a, "base_y", t.groundY - 300);
                t.reach = clamp(Json.num(a, "reach", 1), 0, 1);
                t.width = Math.max(0, Json.num(a, "width", 30));
                t.topWidth = Math.max(t.width, Json.num(a, "top_width", t.width * 2.5));
                t.lean = Json.num(a, "lean", 0);
                t.strength = clamp(Json.num(a, "strength", 0.5), 0, 1);
                t.ef = (int) clamp(Json.num(a, "ef", 0), 0, 5);
                t.windMs = Json.num(a, "wind_ms", 0);
                t.age = Json.num(a, "age", 0);
                t.phase = Json.num(a, "phase", t.age * 8);
                t.debris = clamp(Json.num(a, "debris", 0), 0, 1);
                t.pathX0 = Json.num(a, "path_x0", Double.NaN);
                f.tornadoes.add(t);
            }
        }
        List<Object> crs = Json.arr(m, "cars");
        if (crs != null && !crs.isEmpty()) {
            f.cars = new ArrayList<>(crs.size());
            for (Object o : crs) {
                Map<String, Object> a = Json.obj(o);
                if (a == null) continue;
                Car c = new Car();
                c.id = (long) Json.num(a, "id", f.cars.size() + 1);
                c.x = Json.num(a, "x", 0);
                c.y = Json.num(a, "y", f.groundY);
                c.angle = Json.num(a, "angle", 0);
                c.dir = Json.num(a, "dir", 1) < 0 ? -1 : 1;
                c.speed = Math.max(0, Json.num(a, "speed", 0));
                c.variant = Json.str(a, "variant", "sedan");
                List<Object> col = Json.arr(a, "color");
                if (col != null && col.size() >= 3) c.color = tint(col);
                c.lights = Json.bool(a, "lights", false);
                c.wipers = Json.bool(a, "wipers", false);
                c.state = Json.str(a, "state", "driving");
                c.lift = Math.max(0, Json.num(a, "lift", 0));
                c.spin = Json.num(a, "spin", 0);
                c.wet = clamp(Json.num(a, "wet", 0), 0, 1);
                c.snow = clamp(Json.num(a, "snow", 0), 0, 1);
                c.depth = Math.max(0, Json.num(a, "depth", 0));
                f.cars.add(c);
            }
        }
        List<Object> dml = Json.arr(m, "damage");
        if (dml != null && !dml.isEmpty()) {
            List<double[]> rows = new ArrayList<>(dml.size());
            for (Object o : dml) {
                List<Object> a = Json.arr(o);
                if (a == null || a.size() < 2) continue;
                rows.add(new double[]{Json.num(a.get(0), -1), clamp(Json.num(a.get(1), 0), 0, 1)});
            }
            f.damage = rows.toArray(new double[0][]);
        }
        // ---- v2.4: standing water, flood states, the drain state (a key that is absent leaves what the viewer has)
        List<Object> wl = Json.arr(m, "water");
        if (wl != null) {
            f.water = new ArrayList<>(wl.size());
            for (Object o : wl) {
                Map<String, Object> a = Json.obj(o);
                if (a == null || !(a.get("id") instanceof Number)) continue;
                Body b = new Body();
                b.id = (long) Json.num(a, "id", -1);
                b.kind = Json.str(a, "kind", "pool");
                b.settlement = (long) Json.num(a, "settlement", b.street() ? b.id : -1);
                b.y = Json.num(a, "y", f.groundY);
                b.x0 = Json.num(a, "x0", Json.num(a, "x", 0));
                b.x1 = Json.num(a, "x1", b.x0);
                if (b.x1 < b.x0) { double t = b.x0; b.x0 = b.x1; b.x1 = t; }
                b.x = Json.num(a, "x", (b.x0 + b.x1) * 0.5);
                b.depth = Math.max(0, Json.num(a, "depth", 0));
                b.vol = Math.max(0, Json.num(a, "vol", 0));
                b.spill = Json.bool(a, "spill", false);
                f.water.add(b);
            }
        }
        List<Object> fl = Json.arr(m, "flood");
        if (fl != null) {
            f.flood = new ArrayList<>(fl.size());
            for (Object o : fl) {
                Map<String, Object> a = Json.obj(o);
                if (a == null || !(a.get("id") instanceof Number)) continue;
                Flood x = new Flood();
                x.id = (long) Json.num(a, "id", -1);
                x.state = Json.str(a, "state", "dry");
                x.depth = Math.max(0, Json.num(a, "depth", 0));
                x.inflow = Math.max(0, Json.num(a, "inflow", 0));
                x.cap = Math.max(0, Json.num(a, "cap", 0));
                x.backed = Json.bool(a, "backed", false);
                f.flood.add(x);
            }
        }
        Map<String, Object> ds = Json.obj(m, "drains");
        if (ds != null) {
            DrainState s = new DrainState();
            s.mode = Json.str(ds, "mode", "custom");
            List<Object> sl2 = Json.arr(ds, "settlements");
            if (sl2 != null) for (Object o : sl2) {
                Drain d = parseDrain(Json.obj(o));
                if (d != null && d.id >= 0) s.settlements.add(d);
            }
            f.drains = s;
        }
        // ---- v2.4.1: the runs of the film (absent = keep what there is; a run without cells is dropped)
        List<Object> rl = Json.arr(m, "flow");
        if (rl != null) {
            f.flow = new ArrayList<>(rl.size());
            for (Object o : rl) {
                Map<String, Object> a = Json.obj(o);
                List<Object> hs = a == null ? null : Json.arr(a, "h");
                if (hs == null || hs.isEmpty() || !(a.get("k0") instanceof Number)) continue;
                List<Object> qs = Json.arr(a, "q");
                Run r = new Run();
                r.k0 = (int) Json.num(a, "k0", 0);
                r.dir = Json.num(a, "dir", 1) < 0 ? -1 : 1;
                r.src = (long) Json.num(a, "src", 0);
                r.dst = (long) Json.num(a, "dst", 0);
                r.h = new double[hs.size()];
                r.q = new double[hs.size()];
                for (int i = 0; i < r.h.length; i++) {
                    r.h[i] = Math.max(0, Json.num(hs.get(i), 0));
                    r.q[i] = qs != null && i < qs.size() ? Math.max(0, Json.num(qs.get(i), 0)) : 0;
                }
                f.flow.add(r);
            }
        }

        List<Object> dl = Json.arr(m, "deaths");
        if (dl != null && !dl.isEmpty()) {
            f.deaths = new ArrayList<>(dl.size());
            for (Object o : dl) {
                Map<String, Object> d = Json.obj(o);
                if (d == null) continue;
                Death de = new Death();
                de.id = (long) Json.num(d, "id", -1);
                de.seed = (long) Json.num(d, "seed", de.id * 7919L + 13);
                de.name = Json.str(d, "name", "cloud " + de.id);
                de.type = Json.str(d, "type", "cumulus");
                de.born = Json.num(d, "born", 0);
                de.died = Json.num(d, "died", f.t);
                de.lifetime = Json.num(d, "lifetime", de.died - de.born);
                de.peakWater = Json.num(d, "peak_water", 0);
                de.totalPrecip = Json.num(d, "total_precip_mm", 0);
                de.precipType = Json.str(d, "precip_type", "none");
                de.fromType = fromType(d, de.type);
                de.mergedInto = d.get("merged_into") instanceof Number mi ? mi.longValue() : -1;
                de.stageTimes = stageTimes(Json.obj(d, "stage_times"));
                List<Object> pu = Json.arr(d, "puffs");
                if (pu != null && !pu.isEmpty()) de.puffs = puffs(pu);
                List<Object> ti = Json.arr(d, "tint");
                if (ti != null && ti.size() >= 3) de.tint = tint(ti);
                f.deaths.add(de);
            }
        }
        return f;
    }

    static Cloud parseCloud(Map<String, Object> c) {
        Cloud k = new Cloud();
        k.id = (long) Json.num(c, "id", -1);
        k.seed = (long) Json.num(c, "seed", k.id * 7919L + 13);
        k.name = Json.str(c, "name", "cloud " + k.id);
        k.type = Json.str(c, "type", "cumulus");
        k.kind = cloudKind(k.type);
        k.x = Json.num(c, "x", 0); k.y = Json.num(c, "y", 0);
        k.w = Json.num(c, "w", 100); k.h = Json.num(c, "h", 60);
        k.stage = Json.str(c, "stage", "forming");
        k.age = Json.num(c, "age", 0);
        k.lifetime = Json.num(c, "lifetime", 100);
        k.lifeFrac = Json.num(c, "life_frac", k.lifetime > 0 ? k.age / k.lifetime : 0);
        k.form = clamp(Json.num(c, "form", 1), 0, 1);
        k.shear = Json.num(c, "shear", 0);
        k.anvil = clamp(Json.num(c, "anvil", 0), 0, 1);
        k.water = Json.num(c, "water", 0);
        k.ice = Json.num(c, "ice", 0);
        k.updraft = Json.num(c, "updraft", 0);
        k.precipType = Json.str(c, "precip_type", "none");
        k.precipRate = Json.num(c, "precip_rate", 0);
        k.totalPrecip = Json.num(c, "total_precip_mm", 0);
        List<Object> pu = Json.arr(c, "puffs");
        if (pu != null) k.puffs = puffs(pu);
        List<Object> ti = Json.arr(c, "tint");
        if (ti != null && ti.size() >= 3) k.tint = tint(ti);
        k.opacity = clamp(Json.num(c, "opacity", 0.9), 0, 1);
        k.darkness = clamp(Json.num(c, "darkness", 0.2), 0, 1);
        k.stageTimes = stageTimes(Json.obj(c, "stage_times"));
        k.fair = Json.bool(c, "fair", false);
        k.fromType = fromType(c, k.type);
        k.fromKind = fromKind(k.fromType, k.type);
        // no from_type = never morphed (0); a from_type without a morph value = the change is complete
        k.morph = k.fromKind < 0 ? 0 : clamp(Json.num(c, "morph", 1), 0, 1);
        // ---- v2.3: merging and the supercell keys (absent = an ordinary, unmerged cloud)
        List<Object> pl = Json.arr(c, "parts");
        if (pl != null && !pl.isEmpty()) {
            List<Part> ps = new ArrayList<>(pl.size());
            for (Object o : pl) {
                Map<String, Object> a = Json.obj(o);
                if (a == null) continue;
                Part p = new Part();
                p.id = (long) Json.num(a, "id", -1);
                p.type = Json.str(a, "type", "cumulus");
                p.kind = cloudKind(p.type);
                p.name = Json.str(a, "name", "");
                ps.add(p);
            }
            k.parts = ps.toArray(NO_PARTS);
            k.merge = clamp(Json.num(c, "merge", 1), 0, 1);
        }
        List<Object> pp = Json.arr(c, "puff_part");
        if (pp != null && !pp.isEmpty()) {
            int[] a = new int[pp.size()];
            for (int i = 0; i < a.length; i++) a[i] = (int) Json.num(pp.get(i), 0);
            k.puffPart = a;
        }
        k.mergingInto = c.get("merging_into") instanceof Number mi ? mi.longValue() : -1;
        k.rotation = clamp(Json.num(c, "rotation", 0), 0, 1);
        k.wall = clamp(Json.num(c, "wall", 0), 0, 1);
        k.overshoot = clamp(Json.num(c, "overshoot", 0), 0, 1);
        k.hook = clamp(Json.num(c, "hook", 0), 0, 1);
        k.mesoX = Json.num(c, "meso_x", Double.NaN);
        return k;
    }

    /** The {@code from_type} of a cloud / death record: "" unless it names a known type other than the current one. */
    private static String fromType(Map<String, Object> m, String type) {
        Object o = m.get("from_type");
        if (!(o instanceof String s) || fromKind(s, type) < 0) return "";
        return s;
    }

    static Map<String, Double> stageTimes(Map<String, Object> m) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (m == null) return out;
        for (String s : STAGES) {
            Object o = m.get(s);
            if (o instanceof Number n) out.put(s, n.doubleValue());
        }
        return out;
    }

    static double[][] puffs(List<Object> pu) {
        List<double[]> out = new ArrayList<>(pu.size());
        for (Object o : pu) {
            List<Object> a = Json.arr(o);
            if (a == null || a.size() < 3) continue;
            out.add(new double[]{Json.num(a.get(0), 0), Json.num(a.get(1), 0), Math.max(0, Json.num(a.get(2), 5)),
                    a.size() > 3 ? clamp(Json.num(a.get(3), 0.5), 0, 1) : 0.5});
        }
        return out.toArray(new double[0][]);
    }

    static int[] tint(List<Object> ti) {
        return new int[]{(int) clamp(Json.num(ti.get(0), 230), 0, 255), (int) clamp(Json.num(ti.get(1), 230), 0, 255),
                (int) clamp(Json.num(ti.get(2), 230), 0, 255)};
    }

    static double[] nums(List<Object> a) {
        if (a == null) return new double[0];
        double[] d = new double[a.size()];
        for (int k = 0; k < d.length; k++) d[k] = Json.num(a.get(k), 0);
        return d;
    }

    private static double[] pts(List<Object> a) {
        if (a == null) return new double[0];
        double[] d = new double[a.size() * 2];
        int n = 0;
        for (Object o : a) {
            List<Object> p = Json.arr(o);
            if (p == null || p.size() < 2) continue;
            d[n++] = Json.num(p.get(0), 0);
            d[n++] = Json.num(p.get(1), 0);
        }
        return n == d.length ? d : java.util.Arrays.copyOf(d, n);
    }

    static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
