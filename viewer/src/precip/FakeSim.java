package precip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Built-in stand-in for the Python simulation, speaking protocol v2: a big world with a random
 * heightmap, a village and a city that use every building kind, all eight cloud types running
 * through complete lifetimes (with {@code form}, {@code shear} and {@code anvil}; v2.1: fair-weather
 * clouds that never rain or develop first, and cumulus that tower into cumulonimbus), object state
 * deltas, ground cover, {@code view} culling and a {@code saved} reply. v2.3: a day and season
 * clock with a temperature breakdown, gases that drive the spawn rate and the type mix, clouds that
 * merge (the absorbed cloud's puffs are appended at r = 0 and swell), supercells with a wall cloud
 * and a tornado that tosses cars and damages buildings, roads with cars, and the supercell's
 * lifetime tour in showcase mode. It is deliberately simple physics, not the real model; it exists
 * so the viewer can be developed and verified alone.
 */
final class FakeSim implements FrameSource, Scripted {
    static final double DT = 1.0 / 30.0;
    static final int DEFAULT_W = 3840, DEFAULT_H = 1080;

    private final int W, H;
    private double G;                 // lowest ground point (largest y)
    private double[] terrain;         // step 8
    private static final int TSTEP = 8, GSTEP = 32;
    private String terrainKind = "rolling";

    private long seed;
    private Random rng;
    private double tStart, t, speed = 1, windBase = 4, surfaceTemp = 6, tod0;
    private boolean paused;
    private long frameNo;
    private long nextId = 1;
    private final List<FCloud> clouds = new ArrayList<>();
    private final List<P> parts = new ArrayList<>();
    private final List<FBolt> bolts = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> deaths = new ArrayList<>();
    private final List<FObj> objs = new ArrayList<>();
    private final List<String> settlementsJson = new ArrayList<>();
    private double[] gSnow, gWet, gSnowSent, gWetSent;
    private boolean groundSentOnce;
    private double groundClock;
    private int born, dead;
    private double rainMm, snowMm;
    private double viewX0 = Double.NaN, viewX1 = Double.NaN;
    private final ConcurrentLinkedQueue<String> commands = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> outbox = new ConcurrentLinkedQueue<>();
    private volatile boolean running;
    private final java.util.Set<String> signatures = new java.util.HashSet<>();

    /** Index (in the initial cloud list) of the cumulus that always towers into a cumulonimbus early in a run. */
    private static final int EARLY_TOWER = 12;

    private static final String[] ADJ = {"Sullen", "Tall", "Quiet", "Restless", "Pale", "Heavy",
            "Towering", "Bright", "Tattered", "Velvet", "Iron", "Drifting", "Swollen",
            "Ragged", "Rolling", "Hushed", "Looming", "Howling"};
    private static final String[] NOUN = {"Ember", "Anvil", "Lantern", "Thistle", "Veil",
            "Tower", "Cradle", "Plume", "Squall", "Sleeve",
            "Gale", "Tempest", "Zephyr", "Halo", "Cairn", "Sentinel"};

    FakeSim(long seed, double timeOfDay) { this(seed, timeOfDay, DEFAULT_W, DEFAULT_H); }

    FakeSim(long seed, double timeOfDay, int w, int h) { this(seed, timeOfDay, w, h, null, null); }

    /**
     * @param showcaseType v2.2 "Cloud formations": one cloud of this type at a time on a flat stage (null = the ordinary world)
     * @param presetId     v2.2 region preset to pretend to be (null = none): the hello then carries a made-up {@code preset} block
     */
    FakeSim(long seed, double timeOfDay, int w, int h, String showcaseType, String presetId) {
        this(seed, timeOfDay, w, h, showcaseType, presetId, Double.NaN, Double.NaN, null);
    }

    /**
     * @param month v2.3 calendar month the world starts in (1..12.99; NaN = April, or late May on the showcase stage)
     * @param hour  v2.3 local solar hour it starts at (0..24; NaN = take {@code timeOfDay})
     * @param scene a scripted set-up for pictures and tests ({@link #SCENES}); null = the ordinary world
     */
    FakeSim(long seed, double timeOfDay, int w, int h, String showcaseType, String presetId, double month, double hour, String scene) {
        this.tod0 = Double.isNaN(hour) ? timeOfDay : frac(hour / 24.0);
        this.W = Math.max(1280, Math.min(9600, w));
        this.H = Math.max(720, Math.min(1440, h));
        this.scType = showcaseType;
        // v2.4: "<scene>@v23" (or "@v23" alone) is the same world sent as a v2.3 stream: no water model, no drains, the legacy puddles
        boolean old = scene != null && scene.endsWith(LEGACY);
        if (old) scene = scene.substring(0, scene.length() - LEGACY.length());
        this.legacy = old;
        // v2.4.1: "flood:steep" / "flood:cliff" = the flood scene with the road beyond the village falling steeply / over a cliff
        int run = 0;
        if (scene != null && scene.startsWith("flood:")) {
            run = Math.max(0, java.util.Arrays.asList(FLOOD_RUNS).indexOf(scene.substring(6)));
            scene = "flood";
        }
        this.floodRun = run;
        String sc0 = showcaseType != null || scene == null || scene.isEmpty() ? null : scene, pid = presetId, bio = null;
        if (sc0 != null && sc0.startsWith("biome:")) {
            // v2.3 "biome:<id>": an ordinary world dressed as that biome (as a region preset would be)
            bio = sc0.substring(6);
            sc0 = null;
            if (pid == null) pid = biomePreset(bio);
        } else if (pid != null && showcaseType == null) {
            Preset.Loc loc = Preset.loc(pid);
            bio = loc != null && !loc.biome.isEmpty() ? loc.biome : builtinBiomeOf(pid);
        }
        this.presetId = pid;
        this.month0 = Double.isNaN(month) ? (showcaseType != null ? 5.5 : 4.0) : Model.clamp(month, 1, 12.999);
        this.scene = sc0;
        this.fb = FBiome.of(bio);
        if (fb != null && !fb.plain()) { climBase = fb.base; climAmp = fb.amp; humidity = fb.hum; }
        if (showcaseType != null) { climBase = 6; climAmp = 0; climDiurnal = 0; }   // the stage: a fixed afternoon, no drift
        reseed(seed);
    }

    /** The scripted worlds of {@code --fake-scene} (and {@code biome:<id>} for each of {@link Preset.Biome#IDS}). */
    static final String[] SCENES = {"merge", "supercell", "cars", "flood", "hit"};
    /** Suffix of a scene name: send the world as a v2.3 stream (see the constructor). */
    static final String LEGACY = "@v23";

    /** v2.4.1: how the road falls away east of the flood scene's village ({@code flood:<run>}; plain {@code flood} = gentle). */
    static final String[] FLOOD_RUNS = {"gentle", "steep", "cliff"};

    static boolean knownScene(String s) {
        if (s.endsWith(LEGACY)) s = s.substring(0, s.length() - LEGACY.length());
        if (s.isEmpty()) return true;
        if (s.startsWith("flood:")) return java.util.Arrays.asList(FLOOD_RUNS).contains(s.substring(6));
        return java.util.Arrays.asList(SCENES).contains(s) || (s.startsWith("biome:") && java.util.Arrays.asList(Preset.Biome.IDS).contains(s.substring(6)));
    }

    /** A preset that stands for a biome (the fake's {@code biome:<id>} worlds borrow its name). */
    private static String biomePreset(String biome) {
        return switch (biome) {
            case "desert" -> "sahara";
            case "polar" -> "antarctica";
            case "tropical" -> "hawaii";
            case "savanna" -> "kenya";
            case "alpine" -> "colorado";
            case "tundra" -> "alaska";
            case "mediterranean" -> "california";
            default -> "washington";
        };
    }

    /** The biome of a preset when {@code assets/world/presets.json} does not say (a small built-in table). */
    private static String builtinBiomeOf(String preset) {
        return switch (preset) {
            case "sahara", "atacama" -> "desert";
            case "antarctica" -> "polar";
            case "hawaii", "amazon", "florida", "philippines", "meghalaya" -> "tropical";
            case "kenya", "australia" -> "savanna";
            case "colorado", "tibet" -> "alpine";
            case "alaska" -> "tundra";
            case "california" -> "mediterranean";
            default -> "temperate";
        };
    }

    /**
     * v2.3 preset biome of the fake: the wire block ({@code hello.preset.biome}) and how the fake dresses its world
     * for it. The colours mirror the simulation's; the rest is the fake's own stand-in.
     */
    private static final class FBiome {
        String id, style, city = "city";
        int[] ground, soil, grass, water;
        double trees = 1, snow, base = 9, amp = 9.5, hum = 0.6;
        boolean frozen;
        String[] mixN = {};
        double[] mixW = {};
        String high = "pine";

        boolean plain() { return id.equals("temperate"); }

        private FBiome look(int[] g, int[] s, int[] gr, int[] w) { ground = g; soil = s; grass = gr; water = w; return this; }

        private FBiome mix(Object... nw) {
            mixN = new String[nw.length / 2];
            mixW = new double[nw.length / 2];
            for (int k = 0; k < mixN.length; k++) { mixN[k] = (String) nw[2 * k]; mixW[k] = ((Number) nw[2 * k + 1]).doubleValue(); }
            return this;
        }

        String pick(Random r) {
            double sum = 0, acc = 0;
            for (double w : mixW) sum += w;
            double q = r.nextDouble() * sum;
            for (int k = 0; k < mixN.length; k++) { acc += mixW[k]; if (q < acc) return mixN[k]; }
            return mixN.length > 0 ? mixN[mixN.length - 1] : "bush";
        }

        static FBiome of(String id) {
            if (id == null) return null;
            FBiome b = new FBiome();
            b.id = id;
            switch (id) {
                case "mediterranean" -> { b.look(new int[]{166, 154, 90}, new int[]{124, 92, 62}, new int[]{150, 152, 84}, new int[]{70, 130, 170})
                        .mix("oak", 0.3, "shrub", 0.3, "bush", 0.2, "palm", 0.12, "pine", 0.08); b.trees = 0.6; b.base = 16; b.amp = 7; b.hum = 0.42; }
                case "tropical" -> { b.look(new int[]{70, 140, 64}, new int[]{96, 70, 48}, new int[]{60, 150, 60}, new int[]{60, 130, 150})
                        .mix("palm", 0.5, "oak", 0.2, "bush", 0.25, "birch", 0.05); b.trees = 1.0; b.style = "hut"; b.base = 25; b.amp = 2; b.hum = 0.82; }
                case "savanna" -> { b.look(new int[]{186, 164, 92}, new int[]{130, 96, 60}, new int[]{176, 168, 88}, new int[]{84, 120, 130})
                        .mix("acacia", 0.5, "shrub", 0.3, "dead", 0.08, "bush", 0.12); b.trees = 0.45; b.style = "hut"; b.base = 23; b.amp = 3; b.hum = 0.45; b.high = "acacia"; }
                case "desert" -> { b.look(new int[]{194, 160, 104}, new int[]{150, 112, 70}, null, new int[]{70, 120, 150})
                        .mix("cactus", 0.45, "shrub", 0.3, "dead", 0.1, "palm", 0.15); b.trees = 0.22; b.style = "adobe"; b.city = "adobe"; b.base = 24; b.amp = 9; b.hum = 0.14; b.high = "shrub"; }
                case "alpine" -> { b.look(new int[]{128, 132, 112}, new int[]{96, 88, 76}, new int[]{120, 140, 100}, new int[]{80, 130, 170})
                        .mix("pine", 0.6, "shrub", 0.25, "bush", 0.15); b.trees = 0.5; b.snow = 0.9; b.city = "town"; b.base = 3; b.amp = 10; b.hum = 0.45; }
                case "tundra" -> { b.look(new int[]{140, 138, 116}, new int[]{96, 84, 72}, new int[]{130, 140, 110}, new int[]{82, 118, 140})
                        .mix("shrub", 0.5, "pine", 0.25, "dead", 0.15, "birch", 0.1); b.trees = 0.35; b.snow = 1; b.city = "town"; b.base = -1; b.amp = 13; b.hum = 0.4; b.high = "shrub"; }
                case "polar" -> { b.look(new int[]{236, 242, 248}, new int[]{178, 196, 214}, null, new int[]{140, 180, 210});
                    b.trees = 0; b.snow = 1; b.frozen = true; b.style = "station"; b.city = "none"; b.base = -17; b.amp = 9; b.hum = 0.3; }
                default -> { b.id = "temperate"; b.look(new int[]{110, 146, 84}, new int[]{104, 78, 54}, new int[]{104, 160, 82}, new int[]{70, 120, 170}); }
            }
            return b;
        }

        void json(StringBuilder sb) {
            sb.append(",\"biome\":{\"id\":\"").append(id).append("\",\"ground\":").append(java.util.Arrays.toString(ground).replace(" ", ""))
                    .append(",\"soil\":").append(java.util.Arrays.toString(soil).replace(" ", ""))
                    .append(",\"grass\":").append(grass == null ? "null" : java.util.Arrays.toString(grass).replace(" ", ""))
                    .append(",\"water\":").append(java.util.Arrays.toString(water).replace(" ", ""))
                    .append(",\"trees\":").append(trees).append(",\"snow_start\":").append(snow).append(",\"frozen_water\":").append(frozen).append('}');
        }
    }

    /** The biome this world is dressed as; null = none was asked for (an ordinary world). */
    private final FBiome fb;

    /** The fake of {@code --mode showcase}: a 1600 x 900 stage. */
    static FakeSim showcase(long seed, double timeOfDay, String type) {
        return new FakeSim(seed, timeOfDay, 1600, 900, type != null && Model.CLOUD_TYPES[Model.cloudKind(type)].equals(type) ? type : "cumulus", null);
    }

    // ---- hooks of the Debug game mode (viewer only; nothing below is used by a running world)

    /**
     * The cloud this generator makes for {@code seed}, at {@code lifeFrac} (0..1) of its life, on the flat showcase stage.
     * A pure function of its three arguments: it builds its own stage and never touches a running world's random stream
     * (a world's own clouds are drawn in a loop until their signature is new, so their seeds cannot be asked for).
     * The cloud lives as the showcase's does: it never towers, and it rains if its type can.
     */
    static Model.Cloud debugCloud(String type, long seed, double lifeFrac) {
        String ty = type != null && Model.CLOUD_TYPES[Model.cloudKind(type)].equals(type) ? type : "cumulus";
        return new FakeSim(1, 0.40, 1600, 900, ty, null).debugCloudOn(ty, seed, lifeFrac);
    }

    private Model.Cloud debugCloudOn(String ty, long cs, double lifeFrac) {
        clouds.clear(); parts.clear(); bolts.clear(); tornadoes.clear(); events.clear();
        FCloud c = new FCloud(1, cs, W / 2.0, Double.NaN, ty, t, false);
        int kind = Model.cloudKind(ty);
        boolean canRain = kind != Model.CIRRUS && kind != Model.ALTOCUMULUS;
        c.towers = false;
        c.rains = canRain;
        c.developAt = !c.fairKind ? 0 : canRain ? 0.2 : 9;
        c.prog = Model.clamp(lifeFrac, 0, 1);
        c.age = c.prog * c.lifetime;
        c.born = t - c.age;
        c.recomputeStageTimes();
        c.stageIdx = c.stageForFrac();
        c.shear = c.shearTarget();                // (tick() eases these two; here they are simply where they would settle)
        c.darkShown = Double.NaN;
        // water, ice and rate as advance() sets them for this point of the life
        double f = c.frac(), fz = freezingY();
        c.water = Model.clamp(Math.sin(Math.min(1, f / 0.7) * Math.PI * 0.5) * c.capacity - (f > 0.55 ? (f - 0.55) * 1.6 : 0), 0.02, 1);
        c.ice = c.top() < fz ? Model.clamp(c.water * 0.6 * (fz - c.top()) / 200, 0, 1) : 0;
        c.rate = c.stageIdx == 3 ? Model.clamp(c.water * 1.2, 0.1, 1) : (c.stageIdx == 4 && c.rains ? 0.15 * c.form() : 0);
        StringBuilder sb = new StringBuilder(4096);
        c.json(sb, fz);
        return Model.parseCloud(Json.obj(Json.parse(sb.toString())));
    }

    /** Wall and roof colours as {@link #building} gives them to a building of this kind / variant ({wall, roof}). */
    static int[][] debugColors(String kind, String variant, long seed) {
        Random r = new Random(seed * 0x9E3779B97F4A7C15L + 11);
        boolean tower = kind.equals("office") || kind.equals("skyscraper");
        int[] wall = tower ? TOWER[r.nextInt(TOWER.length)] : WALLS[r.nextInt(WALLS.length)], roof = ROOFS[r.nextInt(ROOFS.length)];
        if (kind.equals("barn")) { wall = new int[]{150, 58, 48}; roof = new int[]{84, 78, 76}; }
        if (kind.equals("factory")) { wall = new int[]{150, 112, 96}; roof = new int[]{92, 96, 104}; }
        if (kind.equals("house")) switch (variant) {
            case "adobe" -> { wall = new int[][]{{212, 184, 140}, {200, 166, 120}, {222, 200, 160}}[r.nextInt(3)]; roof = new int[]{188, 160, 118}; }
            case "hut" -> { wall = new int[]{144 + r.nextInt(14), 108 + r.nextInt(10), 72 + r.nextInt(8)}; roof = new int[]{170 + r.nextInt(12), 146 + r.nextInt(10), 82}; }
            case "station" -> { wall = new int[][]{{204, 78, 46}, {196, 92, 52}, {214, 140, 60}}[r.nextInt(3)]; roof = new int[]{62, 66, 76}; }
            default -> { }
        }
        return new int[][]{wall, roof};
    }

    /** The body colour {@link FCar} gives a car of this variant (bus orange, truck grey, the rest from the palette). */
    static int[] debugCarColor(String variant, long seed) {
        return variant.equals("bus") ? new int[]{214, 152, 30} : variant.equals("truck") ? new int[]{196, 200, 204}
                : CAR_COLORS[(int) Math.floorMod(seed * 31 + variant.hashCode(), (long) CAR_COLORS.length)];
    }

    /** The variants the car spawner draws from (sedan and hatchback twice: they are the common ones). */
    static String[] debugCarVariants() { return CAR_VARIANTS.clone(); }

    // ---- v2.3: the climate (day, season, gases) and what it does to the spawner
    static final double DAY_S = 300;
    static final int YEAR_DAYS = 8;
    private final double month0;
    private final String scene;
    private double yearOffset, hourOffset, hourShown;
    private double humidity = 0.60, co2 = 420, aerosol = 0.30, userC, seasonShown, diurnalNow;
    private double climBase = 9.0, climAmp = 9.5, climDiurnal = 8.0;
    private double instability, shearIdx, spawnPerMin, cloudTarget = 14;
    private final double[] typeW = new double[Model.CLOUD_TYPES.length];
    /** The climate's base cloud mix (the nine types, sum 1). */
    private static final double[] BASE_MIX = {0.27, 0.085, 0.09, 0.09, 0.09, 0.09, 0.09, 0.18, 0.015};
    // ---- v2.3: merging, tornadoes, cars
    static final double MERGE_S = 7.5, ABSORB_S = 7.5;
    private double nextMergeT;
    private boolean scriptedMerges;
    private final List<FTornado> tornadoes = new ArrayList<>();
    private long nextTornadoId = 1, nextCarId = 1;
    private int tornadoesTotal;
    private final List<FCar> cars = new ArrayList<>();
    private final List<double[]> roads = new ArrayList<>();   // x0, x1, 1 = highway
    // ---- v2.4: standing water, drains and flooding (FakeWater mirrors sim/water.py); null in showcase mode and in a "@v23" world
    private FakeWater water;
    private final boolean legacy;
    /** v2.4.1: the flood scene's run beyond the village (an index into {@link #FLOOD_RUNS}). */
    private final int floodRun;

    /**
     * v2.4.1, the flood scene's ground from west to east, as heights below the city's street (px, positive = lower) at
     * scene x: two hollows on ground that falls away to the west (the western one takes the eastern one's overflow), a
     * low bank, <b>the city</b> on a shelf beside a deep hollow that can back up over its street, a short steep flank,
     * <b>the village</b> on the hilltop (it sheds: nothing runs onto it, its water runs off east), the road falling away
     * east of it (gentle 0.225, steep 0.9, or over a cliff) straight into a hollow at the bottom (12 px to its east rim), then a steeper stretch (0.9), a
     * cliff (3.0) and <b>the hamlet</b> on the floor of a hollow of its own, 8 px deep, with lower ground and a pond beyond its east wall (the pond takes what runs over).
     * Returns {x0, x1 of the city, of the village, of the hamlet, where the scene ends, the middle of the hollow at the bottom of
     * the road, the middle of the pond}; {@code knots} gets the profile.
     */
    private static double[] floodLayout(int run, List<double[]> knots) {
        final double[][] head = {{-200, -3.5 + 0.012 * 1136}, {936, -3.5}, {952, 0}, {1368, 0.4}, {1624, 0.4}, {1688, -38}, {1984, -37.6}, {2016, -37.6}};
        for (double[] k : head) knots.add(k);
        double xf;
        // (the road runs straight down into the hollow at the bottom: its bed starts where the fall ends, 50.4 px below the village)
        switch (run) {
            case 1 -> { xf = 2016 + 56; knots.add(new double[]{xf, 12.8}); }
            case 2 -> { xf = 2016 + 72; knots.add(new double[]{2016 + 8, -9.6}); knots.add(new double[]{xf, 12.8}); }
            default -> { xf = 2016 + 224; knots.add(new double[]{xf, 12.8}); }
        }
        final double[][] tail = {{48, 12.8}, {144, 0.8}, {192, 44}, {224, 45.8}, {232, 69.8}, {256, 72.2}, {384, 72.2}, {424, 64.2}, {488, 80}, {616, 80}};
        for (double[] k : tail) knots.add(new double[]{xf + k[0], k[1]});
        return new double[]{952, 1368, 1688, 1984, xf + 260, xf + 380, xf + 632, xf + 24, xf + 552};
    }

    private static double lerpKnots(List<double[]> knots, double x) {
        if (x <= knots.get(0)[0]) return knots.get(0)[1];
        for (int i = 1; i < knots.size(); i++) {
            double[] a = knots.get(i - 1), b = knots.get(i);
            if (x <= b[0]) return a[1] + (b[1] - a[1]) * (x - a[0]) / (b[0] - a[0]);
        }
        return knots.get(knots.size() - 1)[1];
    }
    private String drainMode = "normal";
    /** The fake's own {@code rain} command: hits a second on every 32 px bucket of the world; NaN = the rain is the clouds'. */
    private double rainRate = Double.NaN;
    /**
     * What one landed rain particle of the fake counts as, in the model's hits: the fake's clouds drop about two and a half
     * times as many particles on a bucket as the real simulation's, and its drains would never keep up otherwise.
     */
    static final double FAKE_HIT = 0.4;
    /** The flood scene rains by itself: this many hits a second per bucket from {@code FLOOD_RAIN_T0} to {@code FLOOD_RAIN_T1} seconds in. */
    static final double FLOOD_RAIN = 8, FLOOD_RAIN_T0 = 3, FLOOD_RAIN_T1 = 63;

    /** The rain rate in force (hits a second per bucket), or NaN when the rain is what the clouds drop. */
    private double rainNow() {
        if (!Double.isNaN(rainRate)) return rainRate;
        if ("flood".equals(scene)) return t - tStart >= FLOOD_RAIN_T0 && t - tStart < FLOOD_RAIN_T1 ? FLOOD_RAIN : 0;
        return Double.NaN;
    }

    /** Rain drops to look at while a set rate is in force (the water gets the rate itself, not these). */
    private void showRain(double rate, double dt) {
        if (rate <= 0 || parts.size() > 5200) return;
        double x0 = Double.isNaN(viewX0) ? 0 : Math.max(0, viewX0 - 200), x1 = Double.isNaN(viewX1) ? W : Math.min(W, viewX1 + 200);
        double n = rate * dt * (x1 - x0) / GSTEP;
        while (n > 0) {
            if (n < 1 && rng.nextDouble() > n) break;
            n -= 1;
            double x = x0 + rng.nextDouble() * (x1 - x0);
            P p = new P(1, x, groundAt(x) - 430 - rng.nextDouble() * 60, 1f + rng.nextFloat() * 1.5f);
            p.life = 40;
            parts.add(p);
        }
    }

    /** The water model of this world (null = none): tests and the Debug mode read it. */
    FakeWater water() { return water; }

    /** Wheel radius of a car variant, px ({@code CarPainter.dims}): a car stands still when the water reaches 1.8 radii. */
    static double wheelRadius(String variant) {
        return switch (variant) {
            case "hatchback" -> 3.1;
            case "pickup" -> 3.5;
            case "van" -> 3.4;
            case "truck", "bus" -> 3.9;
            default -> 3.2;
        };
    }

    /** {@code hello.settlements}: every entry with its {@code drain} when the world has the water model. */
    private String settlementsWire() {
        if (water == null) return String.join(",", settlementsJson);
        StringBuilder sb = new StringBuilder();
        for (String js : settlementsJson) {
            if (sb.length() > 0) sb.append(',');
            String d = water.drainJson((long) Json.num(Json.obj(Json.parse(js)), "id", -1));
            sb.append(d == null ? js : js.substring(0, js.length() - 1) + "," + d + "}");
        }
        return sb.toString();
    }

    private static double hollow(double x, double centre, double half, double depth) {
        double u = Math.abs(x - centre) / half;
        if (u >= 1) return 0;
        double c = Math.cos(u * Math.PI / 2);
        return depth * c * c;
    }

    private static double frac(double v) { return v - Math.floor(v); }

    // ---- v2.2
    private String scType;            // showcase mode: the type on stage (null = sandbox)
    private int scCycle;
    private double scPauseUntil = -1;
    private final String presetId;

    /** Showcase: put the next cloud of the current type on stage (it drifts through the middle and always rains if its type can). */
    private void startShowcaseCloud() {
        scCycle++;
        FCloud c = newCloud(W / 2.0, Double.NaN, scType, false);
        int kind = Model.cloudKind(scType);
        boolean canRain = kind != Model.CIRRUS && kind != Model.ALTOCUMULUS;
        c.towers = false;
        c.rains = canRain;
        c.developAt = !c.fairKind ? 0 : canRain ? 0.2 : 9;
        c.x = W / 2.0 - (windBase * 2.6 + c.drift) * c.lifetime / 2;
        c.wasFair = c.fair();
        c.tornadic = true;                   // on the supercell stage every cycle runs the whole tour
        clouds.add(c);
        scPauseUntil = -1;
    }

    private void appendV22(StringBuilder sb) {
        if (scType != null) {
            sb.append(",\"mode\":\"showcase\",\"showcase\":{\"type\":\"").append(scType).append("\",\"types\":[");
            for (int k = 0; k < Model.CLOUD_TYPES.length; k++) sb.append(k > 0 ? "," : "").append('"').append(Model.CLOUD_TYPES[k]).append('"');
            sb.append("],\"lessons\":{");
            for (int k = 0; k < Model.CLOUD_TYPES.length; k++) {
                String ty = Model.CLOUD_TYPES[k], cap = Character.toUpperCase(ty.charAt(0)) + ty.substring(1);
                if (k > 0) sb.append(',');
                sb.append('"').append(ty).append("\":{\"title\":").append(Json.quote(cap)).append(",\"altitude\":\"placeholder altitude (fake simulation)\"");
                for (String key : new String[]{"formation", "life", "precipitation", "wind", "ending"}) {
                    sb.append(",\"").append(key).append("\":").append(Json.quote("[Fake simulation: placeholder text] The real simulation explains the " + key + " of "
                            + ty + " here in one to three plain sentences. This second sentence only shows how a paragraph of that length wraps in the panel."));
                }
                sb.append('}');
            }
            sb.append("},\"tours\":{\"supercell\":[");
            for (int k = 0; k < TOUR.length; k++) {
                if (k > 0) sb.append(',');
                sb.append("{\"id\":\"").append(TOUR[k][0]).append("\",\"title\":").append(Json.quote(TOUR[k][1])).append(",\"text\":").append(Json.quote(TOUR[k][2])).append('}');
            }
            sb.append("]}}");
        } else sb.append(",\"mode\":\"sandbox\"");
        if (presetId != null) {
            String nm = Preset.nameOf(presetId);
            double mpp = 9.5;
            sb.append(",\"preset\":{\"id\":").append(Json.quote(presetId)).append(",\"name\":").append(Json.quote(nm))
                    .append(",\"summary\":\"[Fake simulation] A made-up stand-in for the real region data.\"")
                    .append(",\"transect\":\"placeholder transect, west coast → eastern plateau (west → east)\",\"span_km\":480,\"max_elev_m\":")
                    .append(Math.round((G - java.util.Arrays.stream(terrain).min().orElse(G)) * mpp)).append(",\"m_per_px\":").append(mpp)
                    .append(",\"lat\":47.4,\"lon\":-122.3,\"region\":\"XX-FAKE\",\"country\":\"XX\"")
                    .append(",\"climate\":{\"station\":\"Placeholder City\",\"temp_c\":").append(climBase + 4.5)
                    .append(",\"annual_precip_mm\":950,\"wind\":\"westerly\",\"note\":\"placeholder note: wet windward slopes, dry lee side\"")
                    .append(",\"lat\":47.4,\"diurnal_range_c\":").append(climDiurnal).append(",\"monthly_temp_c\":").append(monthlyJson(climBase + 4.5))
                    .append(",\"monthly_precip_mm\":[140,100,95,70,50,40,18,22,40,90,150,140]},\"landmarks\":[");
            double[] fx = {0.03, 0.23, 0.52, 0.70, 0.76, 0.83, 0.95};
            String[] fn = {"Placeholder Coast", "Fake Valley", "Plain City", "Foothill Town", "Mock Pass", "Mount Placeholder", "East Plateau"};
            // the tallest summit of the fake's mountain range gets the "Mount" name
            double best = fx[5] * W;
            for (double x = W * 0.66; x < W * 0.92; x += TSTEP) if (groundAt(x) < groundAt(best)) best = x;
            for (int k = 0; k < fx.length; k++) {
                double x = k == 5 ? best : fx[k] * W;
                if (k > 0) sb.append(',');
                sb.append("{\"x\":"); Json.num(sb, x);
                sb.append(",\"name\":").append(Json.quote(fn[k])).append(",\"elev_m\":").append(Math.round((G - groundAt(x)) * mpp)).append('}');
            }
            sb.append("],\"sources\":[\"https://example.invalid/fake-elevation\",\"https://example.invalid/fake-climate\"]");
            if (fb != null) fb.json(sb);
            sb.append('}');
        }
    }

    // ------------------------------------------------------------------ world generation

    private void reseed(long s) {
        seed = s;
        rng = new Random(s);
        t = 0;
        frameNo = 0;
        clouds.clear(); parts.clear(); bolts.clear(); signatures.clear(); objs.clear(); settlementsJson.clear();
        born = dead = 0;
        rainMm = snowMm = 0;
        nextId = 1;
        groundSentOnce = false;
        // v2.3 state
        tornadoes.clear(); cars.clear(); roads.clear();
        nextTornadoId = 1; nextCarId = 1; tornadoesTotal = 0;
        nextMergeT = 0; scriptedMerges = false;
        yearOffset = 0; hourOffset = 0; hourShown = 0; userC = 0;
        tStart = 0;
        seasonShown = seasonC(yearFrac());
        updateClimate(0);
        buildWorld();
        if (scType != null) {                // showcase: an empty stage, light steady wind, then the first cloud
            windBase = 1.4;
            t = 1;
            tStart = t;
            scCycle = 0;
            updateClimate(0);
            startShowcaseCloud();
            events.clear();
            return;
        }
        if (scene != null) {                 // a scripted world: its own few clouds
            t = 1;
            tStart = t;
            setupScene();
            updateClimate(0);
            for (FCloud c : clouds) {
                c.born = t - c.age;
                c.recomputeStageTimes();
                c.stageIdx = c.stageForFrac();
                c.shear = c.shearTarget();
                c.wasFair = c.fair();
            }
            events.clear();
            return;
        }

        // one cloud of every type at staggered ages so every look is visible early; ids 1..N are stable
        String[] ty = {"cumulus", "cumulonimbus", "nimbostratus", "stratocumulus", "altostratus", "altocumulus", "stratus",
                "cirrus", "cumulus", "cumulus", "cirrus", "altocumulus", "cumulus", "cumulus", "supercell"};
        // (the 13th is the guaranteed towering cumulus: it develops ~2.4 s and starts to tower ~4.8 s into the run;
        //  the 15th is a supercell west of the village: it lowers a wall cloud and drops a tornado about a minute in)
        final boolean drySky = fb != null && !fb.plain() && fb.hum < 0.35;
        if (drySky) {
            // v2.3: a desert or polar world opens under a dry sky - the same fifteen clouds, but none that rains
            ty = new String[]{"cumulus", "altocumulus", "cirrus", "altocumulus", "cirrus", "altocumulus", "cirrus",
                    "cirrus", "cumulus", "cumulus", "cirrus", "altocumulus", "cumulus", "cirrus", "cirrus"};
        }
        double[] ages = {0.40, 0.55, 0.60, 0.45, 0.50, 0.40, 0.50, 0.45, 0.0, 0.93, 0.6, 0.7, 0.27, 0.5, 0.26};
        double[] xs = {0.075, 0.50, 0.80, 0.30, 0.64, 0.20, 0.93, 0.40, 0.185, 0.615, 0.72, 0.57, 0.39, 0.88, 0.135};
        List<FCloud> init = new ArrayList<>();
        double maxAge = 0;
        for (int k = 0; k < ty.length; k++) {
            FCloud c = newCloud(xs[k] * W, Double.NaN, ty[k], k == EARLY_TOWER && !drySky);
            c.age = ages[k] * c.lifetime;
            c.prog = ages[k];
            if (k == 14 && !drySky) c.tornadic = true;
            c.wasFair = c.fair();
            maxAge = Math.max(maxAge, c.age);
            init.add(c);
        }
        t = Math.ceil(maxAge) + 1;          // keep every birth time >= 0
        tStart = t;
        for (FCloud c : init) {
            c.born = t - c.age;
            c.recomputeStageTimes();
            c.stageIdx = c.stageForFrac();
            c.shear = c.shearTarget();
            clouds.add(c);
        }
        events.clear();
    }

    private static double vnoise(double x, long s) {
        long i = (long) Math.floor(x);
        double f = x - i;
        double a = h01(i * 0x9E3779B97F4A7C15L + s), b = h01((i + 1) * 0x9E3779B97F4A7C15L + s);
        double u = f * f * f * (f * (f * 6 - 15) + 10);
        return a + (b - a) * u;
    }

    private static double h01(long n) {
        n ^= n >>> 33; n *= 0xff51afd7ed558ccdL; n ^= n >>> 33; n *= 0xc4ceb9fe1a85ec53L; n ^= n >>> 33;
        return (n >>> 11) / (double) (1L << 53);
    }

    private double groundAt(double x) {
        double g = x / TSTEP;
        if (g <= 0) return terrain[0];
        int i = (int) g;
        if (i >= terrain.length - 1) return terrain[terrain.length - 1];
        double f = g - i;
        return terrain[i] + (terrain[i + 1] - terrain[i]) * f;
    }

    private void buildWorld() {
        int n = (int) Math.ceil(W / (double) TSTEP) + 1;
        terrain = new double[n];
        water = null;
        double base = H - 80;
        if (scType != null) {                // the showcase stage: flat ground, nothing built on it
            java.util.Arrays.fill(terrain, base);
            G = base;
            terrainKind = "flat";
            int gn0 = (int) Math.ceil(W / (double) GSTEP) + 1;
            gSnow = new double[gn0]; gWet = new double[gn0]; gSnowSent = new double[gn0]; gWetSent = new double[gn0];
            return;
        }
        if (presetId != null) terrainKind = "preset";
        long s = seed * 31 + 7;
        // settlements first (spans), terrain is flattened under them
        double vx0 = W * 0.088, vx1 = W * 0.372;      // village
        double cx0 = W * 0.39, cx1 = W * 0.665;       // city
        double hx0 = W * 0.925, hx1 = W * 0.975;      // hamlet up in the hills
        // v2.4.1 flood scene: a city beside a hollow, a village on a hilltop with the road falling away east of it to a hollow
        // at the bottom, and a hamlet in a hollow of its own beyond a cliff (see floodLayout); two more hollows west of the city
        final boolean floodScene = "flood".equals(scene);
        final double fk = Math.min(1, W / 3300.0);
        final List<double[]> knots = new ArrayList<>();
        final double[] lay = floodScene ? floodLayout(floodRun, knots) : null;
        final double[] pondXs = floodScene ? new double[]{W * 0.034, lay[8] * fk} : new double[]{W * 0.034, W * 0.70};       // (flood scene: the second pond lies beyond the hamlet's wall)
        if (floodScene) { cx0 = lay[0] * fk; cx1 = lay[1] * fk; vx0 = lay[2] * fk; vx1 = lay[3] * fk; hx0 = lay[4] * fk; hx1 = lay[5] * fk; }
        for (int i = 0; i < n; i++) {
            double x = i * TSTEP, u = x / W;
            double roll = 70 * vnoise(x * 0.0022 + 3, s) + 34 * vnoise(x * 0.006 + 9, s + 1) + 12 * vnoise(x * 0.019, s + 2)
                    + 4 * vnoise(x * 0.06, s + 3);
            // a mountain range on the right third of the world
            double m = smooth((u - 0.66) / 0.1) * (1 - 0.55 * smooth((u - 0.9) / 0.1));
            double peaks = m * (H * 0.40 * (0.35 + 0.65 * vnoise(x * 0.0042 + 40, s + 4)) + 50 * vnoise(x * 0.015, s + 5)
                    + 16 * vnoise(x * 0.05, s + 6));
            terrain[i] = base - 20 - roll - peaks;
            if (floodScene) {
                double fx = x / fk, far = smooth((fx - lay[6]) / 300);
                double fy = base - 110 + lerpKnots(knots, fx) + hollow(fx, 520, 130, 16) + hollow(fx, 800, 110, 12) + hollow(fx, 1496, 124, 40);
                terrain[i] = far <= 0 ? fy : fy * (1 - far) + terrain[i] * far;
            }
        }
        if (!floodScene) {                   // (the flood scene's streets are laid out exactly: their ends decide where the water runs)
            flatten(vx0, vx1, 60);
            flatten(cx0, cx1, 90);
            flatten(hx0, hx1, 40);
        }
        for (double px : pondXs) flatten(px - 70, px + 70, 30);
        G = 0;
        for (double v : terrain) G = Math.max(G, v);

        int gn = (int) Math.ceil(W / (double) GSTEP) + 1;
        gSnow = new double[gn]; gWet = new double[gn]; gSnowSent = new double[gn]; gWetSent = new double[gn];
        double fz = freezingY();
        for (int i = 0; i < gn; i++) if (groundAt(i * GSTEP) < fz) gSnow[i] = Model.clamp((fz - groundAt(i * GSTEP)) / 60.0, 0, 1);

        // v2.3 biome: the building style of the houses, and what stands where the city would be
        final boolean dressed = fb != null && !fb.plain();
        final String style = dressed ? fb.style : null, cityKind = dressed ? fb.city : "city";
        final boolean polar = dressed && fb.id.equals("polar");
        if (polar) settlementsJson.add(settlement(1, "hamlet", "Coldwater Station", vx0, vx0 + (vx1 - vx0) * 0.5, 40));
        else {
            settlementsJson.add(settlement(1, "village", "Eldermoor", vx0, vx1, 340));
            if (cityKind.equals("city")) settlementsJson.add(settlement(2, "city", "Port Halden", cx0, cx1, 48200));
            else settlementsJson.add(settlement(2, "town", "Port Halden", cx0, cx1, cityKind.equals("adobe") ? 2400 : 5200));
            settlementsJson.add(settlement(3, "hamlet", "High Tarn", hx0, hx1, 35));
        }

        Random r = new Random(seed * 131 + 5);
        long id = 1;
        // ---- background scenery
        id = addObj(id, "mountain", "", W * 0.06, 760, 400, r, 0);
        id = addObj(id, "mountain", "", W * 0.33, 900, 470, r, 0);
        id = addObj(id, "hill", "", W * 0.22, 700, 150, r, 0);
        id = addObj(id, "hill", "", W * 0.60, 900, 120, r, 0);
        id = addObj(id, "hill", "", W * 0.10, 520, 110, r, 0);

        // ---- village: every house variant, barn, church, windmill, shop, water tower, lamps
        String[] hv = {"cottage", "cabin", "farmhouse", "townhouse", "bungalow", "manor"};
        double x = vx0 + 30;
        int k = 0;
        // Phase O scene "hit": a street of 12 buildings in two rows (4 behind, 8 in front) for a tornado to walk along
        final boolean hitScene = "hit".equals(scene);
        if (hitScene) {
            String[][] back = {{"house", "farmhouse", "118", "84", "62", "2"}, {"apartment", "", "262", "80", "92", "5"}, {"house", "manor", "452", "112", "78", "2"},
                    {"house", "cottage", "600", "62", "52", "1"}};
            for (String[] b : back) {
                FObj o = building(id++, b[0], b[1], vx0 + Double.parseDouble(b[2]), Double.parseDouble(b[3]), Double.parseDouble(b[4]), r, Integer.parseInt(b[5]), 1);
                o.settlement = 1;
                objs.add(o);
            }
        }
        // back row
        for (double bx = vx0 + 70; bx < vx1 - 60 && !polar && !hitScene; bx += 74 + r.nextInt(40)) {
            FObj o = building(id++, "house", style != null ? style : hv[r.nextInt(hv.length)], bx, 52 + r.nextInt(18), 44 + r.nextInt(16), r, 1, 1);
            o.settlement = 1;
            objs.add(o);
        }
        String[] vOrder = {"windmill:", "house:cottage", "barn:", "house:cabin", "house:farmhouse", "shop:bakery", "church:", "house:townhouse",
                "house:manor", "house:bungalow", "water_tower:", "house:cottage"};
        if ("adobe".equals(style)) vOrder = new String[]{"house:adobe", "house:adobe", "shop:bakery", "house:adobe", "house:adobe", "water_tower:", "house:adobe", "house:adobe", "house:adobe"};
        else if ("hut".equals(style)) vOrder = new String[]{"house:hut", "house:hut", "house:hut", "shop:market", "house:hut", "church:", "house:hut", "house:hut", "water_tower:", "house:hut", "house:hut"};
        else if ("station".equals(style)) vOrder = new String[]{"house:station", "house:station", "house:station", "water_tower:", "house:station"};
        if (hitScene) vOrder = new String[]{"house:cottage", "barn:", "church:", "office:", "house:cabin", "shop:bakery", "house:townhouse", "house:bungalow"};
        for (String spec : vOrder) {
            String kind = spec.substring(0, spec.indexOf(':')), var = spec.substring(spec.indexOf(':') + 1);
            double w, h;
            int floors = 1;
            switch (kind) {
                case "barn" -> { w = 96; h = 70; }
                case "church" -> { w = 74; h = 132; }
                case "windmill" -> { w = 54; h = 118; }
                case "shop" -> { w = 72; h = 46; }
                case "water_tower" -> { w = 44; h = 96; }
                case "office" -> { w = 86; h = 124; floors = 8; }          // (scene "hit" only: the village has no office otherwise)
                default -> {
                    switch (var) {
                        case "cabin" -> { w = 58; h = 42; }
                        case "farmhouse" -> { w = 84; h = 62; floors = 2; }
                        case "townhouse" -> { w = 46; h = 78; floors = 3; }
                        case "bungalow" -> { w = 80; h = 40; }
                        case "manor" -> { w = 112; h = 78; floors = 2; }
                        case "adobe" -> { w = 56 + r.nextInt(18); h = 38 + r.nextInt(12); floors = 1 + r.nextInt(2); }
                        case "hut" -> { w = 40 + r.nextInt(12); h = 33 + r.nextInt(9); }
                        case "station" -> { w = 62 + r.nextInt(30); h = 40 + r.nextInt(8); }
                        default -> { w = 62; h = 52; }
                    }
                }
            }
            x += w / 2 + 6;
            if (x + w / 2 > vx1 - 10) break;
            FObj o = building(id++, kind, var, x, w, h, r, floors, 0);
            o.settlement = 1;
            objs.add(o);
            x += w / 2 + 8 + r.nextInt(12);
            if (k++ % 3 == 1) { FObj l = building(id++, "streetlamp", "", x - 4, 8, 34, r, 1, 0); l.settlement = 1; objs.add(l); }
        }

        // ---- city: layer-1 skyline behind, then apartments / offices / skyscrapers / shops / factory in front
        final boolean bigCity = cityKind.equals("city"), smallTown = cityKind.equals("town");
        if (cityKind.equals("adobe")) {
            // a desert town: two rows of flat-roofed earth houses, a few shops, nothing taller than three floors
            for (double bx = cx0 + 50; bx < cx1 - 50; bx += 66 + r.nextInt(30)) {
                FObj o = building(id++, "house", "adobe", bx, 58 + r.nextInt(16), 44 + r.nextInt(10), r, 2, 1);
                o.settlement = 2;
                objs.add(o);
            }
            double ax = cx0 + 24;
            int ak = 0;
            while (ax < cx1 - 50) {
                boolean shop = ak % 5 == 3, apt = ak % 7 == 5;
                double w = shop ? 66 + r.nextInt(12) : apt ? 78 + r.nextInt(16) : 54 + r.nextInt(20), h = shop ? 46 : apt ? 3 * 16 + 12 : 36 + r.nextInt(14);
                ax += w / 2 + 3;
                FObj o = building(id++, shop ? "shop" : apt ? "apartment" : "house", shop ? "market" : apt ? "" : "adobe", ax, w, h, r, apt ? 3 : shop ? 1 : 1 + r.nextInt(2), 0);
                if (apt) { o.wall = new int[]{208, 180, 138}; o.roof = new int[]{184, 156, 116}; }
                o.settlement = 2;
                objs.add(o);
                ax += w / 2 + 6 + r.nextInt(14);
                if (ak++ % 3 == 0) { FObj l = building(id++, "streetlamp", "", ax - 3, 8, 34, r, 1, 0); l.settlement = 2; objs.add(l); }
            }
        }
        for (double bx = cx0 + 60; bx < cx1 - 60 && (bigCity || smallTown); ) {
            double u = (bx - cx0) / (cx1 - cx0), core = Math.exp(-Math.pow((u - 0.52) / 0.22, 2));
            String kind = r.nextDouble() < 0.25 + 0.5 * core ? "skyscraper" : r.nextDouble() < 0.6 ? "office" : "apartment";
            int floors = kind.equals("skyscraper") ? 16 + (int) (core * 14) + r.nextInt(8) : kind.equals("office") ? 7 + r.nextInt(8) : 4 + r.nextInt(5);
            if (smallTown) { if (kind.equals("skyscraper")) kind = "apartment"; floors = 3 + floors % 3; }
            double w = kind.equals("skyscraper") ? 62 + r.nextInt(30) : 70 + r.nextInt(46);
            FObj o = building(id++, kind, "", bx, w, floors * (kind.equals("apartment") ? 15 : 14) + 10, r, floors, 1);
            o.settlement = 2;
            objs.add(o);
            bx += w * 0.9 + r.nextInt(30);
        }
        x = cx0 + 20;
        k = 0;
        boolean factoryDone = false, churchDone = false;
        while (x < cx1 - 40 && (bigCity || smallTown)) {
            double u = (x - cx0) / (cx1 - cx0), core = Math.exp(-Math.pow((u - 0.5) / 0.2, 2));
            String kind, var = "";
            double w, h;
            int floors = 1;
            double q = r.nextDouble();
            if (!factoryDone && u < 0.08) { kind = "factory"; w = 150; h = 84; factoryDone = true; }
            else if (!churchDone && u > 0.2 && u < 0.3) { kind = "church"; var = "cathedral"; w = 84; h = 150; churchDone = true; }
            else if (u < 0.16 || u > 0.86) {
                if (q < 0.35) { kind = "house"; var = "townhouse"; w = 44 + r.nextInt(8); floors = 3; h = 76; }
                else if (q < 0.6) { kind = "shop"; var = r.nextBoolean() ? "grocer" : "cafe"; w = 66 + r.nextInt(14); h = 46; }
                else { kind = "apartment"; floors = 3 + r.nextInt(3); w = 74 + r.nextInt(26); h = floors * 16 + 12; }
            } else if (q < 0.18 + 0.42 * core) {
                kind = "skyscraper"; floors = 14 + (int) (core * 16) + r.nextInt(8); w = 64 + r.nextInt(34); h = floors * 14 + 16;
            } else if (q < 0.62) {
                kind = "office"; floors = 6 + r.nextInt(8); w = 80 + r.nextInt(40); h = floors * 14 + 12;
            } else if (q < 0.86) {
                kind = "apartment"; floors = 4 + r.nextInt(5); w = 74 + r.nextInt(30); h = floors * 16 + 12;
            } else {
                kind = "shop"; var = r.nextBoolean() ? "books" : "market"; w = 66 + r.nextInt(16); h = 46;
            }
            if (smallTown && (kind.equals("skyscraper") || kind.equals("office") || kind.equals("apartment"))) {
                // a small town: nothing above five floors, and no towers
                if (kind.equals("skyscraper")) kind = "apartment";
                floors = 2 + floors % 4;
                h = floors * 15 + 12;
            }
            if (style != null && kind.equals("house")) { var = style; w = 44 + r.nextInt(12); h = 36 + r.nextInt(8); floors = 1; }
            x += w / 2 + 3;
            FObj o = building(id++, kind, var, x, w, h, r, floors, 0);
            o.settlement = 2;
            objs.add(o);
            x += w / 2 + 4 + r.nextInt(10);
            if (k++ % 2 == 0) { FObj l = building(id++, "streetlamp", "", x - 2, 8, 36, r, 1, 0); l.settlement = 2; objs.add(l); }
        }
        // water tower at the city edge
        if (!polar && !floodScene) { FObj o = building(id++, "water_tower", "", cx1 + 46, 46, 104, r, 1, 0); o.settlement = 2; objs.add(o); }

        // ---- hamlet in the hills
        x = hx0 + 20;
        for (String var : polar ? new String[0] : style != null ? new String[]{style, style, style} : new String[]{"cabin", "cottage", "cabin"}) {
            x += 36;
            FObj o = building(id++, "house", var, x, 56, 42, r, 1, 0);
            o.settlement = 3;
            objs.add(o);
            x += 44 + r.nextInt(20);
        }

        // ---- trees (four variants), ponds, puddles, snowpacks
        String[] tv = {"pine", "oak", "birch", "bush"};
        for (double tx = 20; tx < W - 20; tx += 22 + r.nextInt(70)) {
            if (tx > cx0 - 20 && tx < cx1 + 20) { if (r.nextDouble() > 0.06) continue; }
            if (tx > vx0 && tx < vx1 && (hitScene || r.nextDouble() > 0.25)) continue;      // (scene "hit": no tree stands in front of the street)
            if (tx > hx0 && tx < hx1 && r.nextDouble() > 0.3) continue;
            if (Math.abs(tx - pondXs[0]) < 80 || Math.abs(tx - pondXs[1]) < 80) continue; // ponds
            double alt = G - groundAt(tx);
            String v = alt > 190 ? "pine" : alt > 110 ? (r.nextDouble() < 0.7 ? "pine" : "birch") : tv[r.nextInt(tv.length)];
            if (alt > H * 0.30) continue; // above the tree line
            double th = v.equals("bush") ? 16 + r.nextInt(12) : 46 + r.nextInt(44);
            double tw = v.equals("bush") ? th * 1.5 : th * (v.equals("pine") ? 0.42 : 0.62);
            if (dressed) {
                // the biome's own trees, as thick on the ground as it grows them (sizes as the simulation makes them)
                if (r.nextDouble() > fb.trees) continue;
                v = alt > 150 ? (r.nextDouble() < 0.8 ? fb.high : "bush") : fb.pick(r);
                switch (v) {
                    case "palm" -> { tw = 26 + r.nextInt(15); th = 88 + r.nextInt(43); }
                    case "cactus" -> { tw = 14 + r.nextInt(11); th = 40 + r.nextInt(41); }
                    case "acacia" -> { tw = 50 + r.nextInt(31); th = 48 + r.nextInt(23); }
                    case "shrub" -> { tw = 20 + r.nextInt(15); th = 12 + r.nextInt(11); }
                    case "dead" -> { tw = 14 + r.nextInt(9); th = 40 + r.nextInt(31); }
                    case "pine" -> { tw = 20 + r.nextInt(13); th = 55 + r.nextInt(41); }
                    case "oak" -> { tw = 28 + r.nextInt(19); th = 48 + r.nextInt(25); }
                    case "birch" -> { tw = 16 + r.nextInt(11); th = 52 + r.nextInt(27); }
                    default -> { tw = 20 + r.nextInt(15); th = 14 + r.nextInt(11); }
                }
            }
            FObj o = new FObj(id++, "tree", v, tx, groundAt(tx), tw, th);
            o.seed = r.nextInt(1 << 30);
            objs.add(o);
        }
        for (double px : pondXs) {
            if (dressed && fb.id.equals("desert") && px > W * 0.5) continue;       // one oasis is plenty
            FObj pond = new FObj(id++, "pond", "", px, groundAt(px), 130, 13);
            pond.seed = r.nextInt(1 << 30);
            objs.add(pond);
            if (dressed && fb.id.equals("desert")) for (int pk = 0; pk < 4; pk++) {  // ... with its palms
                FObj pt = new FObj(id++, "tree", "palm", px + (pk < 2 ? -1 : 1) * (74 + 20 * (pk % 2)), 0, 28 + r.nextInt(10), 84 + r.nextInt(36));
                pt.seed = r.nextInt(1 << 30);
                objs.add(pt);
            }
        }
        for (double px : new double[]{W * 0.12, W * 0.36, W * 0.52, W * 0.68}) objs.add(new FObj(id++, "puddle", "", px, groundAt(px), 6, 2));
        for (double px : new double[]{W * 0.775, W * 0.86}) objs.add(new FObj(id++, "snowpack", "", px, groundAt(px), 40, 3));
        // objects sit on the terrain surface
        for (FObj o : objs) o.y = groundAt(o.x);

        // ---- v2.3 roads: a street through every settlement and one highway from the west edge to the far side of the city
        if (polar) {
            roads.add(new double[]{vx0, vx0 + (vx1 - vx0) * 0.5, 0});     // a research station: one short track
            roads.add(new double[]{0, vx1, 1});
        } else {
            roads.add(new double[]{vx0, vx1, 0});
            roads.add(new double[]{cx0, cx1, 0});
            roads.add(new double[]{hx0, hx1, 0});
            // (flood scene: one road from the city over the hill, through the village and down to the hamlet)
            roads.add(new double[]{floodScene ? cx0 - 8 * fk : 0, floodScene ? Math.min(W, hx1 + 30 * fk) : Math.min(W, cx1 + 90), 1});
        }
        Random cr = new Random(seed * 977 + 3);
        for (double[] rd : roads) {
            int nc = (int) Math.max(1, Math.round((rd[1] - rd[0]) / (rd[2] > 0 ? 330.0 : 420.0)));
            for (int i = 0; i < nc; i++) {
                FCar car = new FCar(nextCarId++, cr, rd);
                car.x = rd[0] + (i + 0.2 + 0.6 * cr.nextDouble()) * (rd[1] - rd[0]) / nc;
                cars.add(car);
            }
        }
        // ---- v2.4: the water model over this terrain (the settlement spans are its streets, the ponds closed hollows)
        if (!legacy) {
            List<double[]> spans = new ArrayList<>(), ponds = new ArrayList<>();
            List<String> kinds = new ArrayList<>();
            for (String js : settlementsJson) {
                Map<String, Object> m = Json.obj(Json.parse(js));
                spans.add(new double[]{Json.num(m, "id", 0), Json.num(m, "x0", 0), Json.num(m, "x1", 0)});
                kinds.add(Json.str(m, "kind", "village"));
            }
            for (FObj o : objs) if (o.kind.equals("pond")) ponds.add(new double[]{o.x - o.w / 2, o.x + o.w / 2});
            water = new FakeWater(terrain, TSTEP, W, seed, spans, kinds, ponds, drainMode);
        }
    }

    /** Phase O scene "hit": a tower stays partly standing (the real simulation leaves towers at 0.2-0.94), everything else takes the full hit. */
    private double hitResist(FObj o) { return "hit".equals(scene) && Chunks.towerKind(o.kind) ? 0.12 : 1; }

    private long addObj(long id, String kind, String variant, double x, double w, double h, Random r, int layer) {
        FObj o = new FObj(id, kind, variant, x, groundAt(x), w, h);
        o.seed = r.nextInt(1 << 30);
        o.layer = layer;
        objs.add(o);
        return id + 1;
    }

    private static final int[][] WALLS = {{214, 196, 170}, {196, 120, 96}, {228, 218, 198}, {170, 150, 132}, {200, 186, 150},
            {186, 196, 204}, {224, 206, 184}, {164, 132, 110}, {206, 210, 200}};
    private static final int[][] ROOFS = {{142, 62, 48}, {92, 70, 62}, {70, 84, 100}, {120, 84, 60}, {84, 96, 84}, {150, 80, 60}};
    private static final int[][] TOWER = {{96, 118, 140}, {120, 132, 146}, {86, 100, 122}, {140, 146, 156}, {104, 110, 124},
            {150, 138, 124}, {78, 96, 110}};

    private FObj building(long id, String kind, String variant, double x, double w, double h, Random r, int floors, int layer) {
        FObj o = new FObj(id, kind, variant, x, 0, w, h);
        o.seed = r.nextInt(1 << 30);
        o.floors = floors;
        o.layer = layer;
        boolean tower = kind.equals("office") || kind.equals("skyscraper");
        o.wall = tower ? TOWER[r.nextInt(TOWER.length)] : WALLS[r.nextInt(WALLS.length)];
        o.roof = ROOFS[r.nextInt(ROOFS.length)];
        if (kind.equals("barn")) { o.wall = new int[]{150, 58, 48}; o.roof = new int[]{84, 78, 76}; }
        if (kind.equals("factory")) { o.wall = new int[]{150, 112, 96}; o.roof = new int[]{92, 96, 104}; }
        if (kind.equals("house")) switch (variant) {       // v2.3 biome houses, coloured as the simulation colours them
            case "adobe" -> { o.wall = new int[][]{{212, 184, 140}, {200, 166, 120}, {222, 200, 160}}[r.nextInt(3)]; o.roof = new int[]{188, 160, 118}; }
            case "hut" -> { o.wall = new int[]{144 + r.nextInt(14), 108 + r.nextInt(10), 72 + r.nextInt(8)}; o.roof = new int[]{170 + r.nextInt(12), 146 + r.nextInt(10), 82}; }
            case "station" -> { o.wall = new int[][]{{204, 78, 46}, {196, 92, 52}, {214, 140, 60}}[r.nextInt(3)]; o.roof = new int[]{62, 66, 76}; }
            default -> { }
        }
        o.building = true;
        return o;
    }

    private void flatten(double x0, double x1, double blend) {
        double sum = 0;
        int cnt = 0;
        for (int i = 0; i < terrain.length; i++) {
            double x = i * TSTEP;
            if (x >= x0 && x <= x1) { sum += terrain[i]; cnt++; }
        }
        if (cnt == 0) return;
        double level = sum / cnt;
        for (int i = 0; i < terrain.length; i++) {
            double x = i * TSTEP;
            double wgt = smooth((x - (x0 - blend)) / blend) * smooth(((x1 + blend) - x) / blend);
            terrain[i] += (level - terrain[i]) * wgt;
        }
    }

    private static String settlement(int id, String kind, String name, double x0, double x1, int pop) {
        return "{\"id\":" + id + ",\"kind\":\"" + kind + "\",\"name\":" + Json.quote(name) + ",\"x0\":" + Math.round(x0)
                + ",\"x1\":" + Math.round(x1) + ",\"population\":" + pop + "}";
    }

    // ------------------------------------------------------------------ FrameSource

    @Override public String describe() { return scType != null ? "built-in fake simulation (showcase)" : "built-in fake simulation"; }

    @Override
    public void start(ViewerState state) {
        running = true;
        state.acceptLine(hello());
        Thread th = new Thread(() -> {
            long next = System.nanoTime();
            while (running) {
                String cmd;
                while ((cmd = commands.poll()) != null) {
                    boolean reseeded = handle(cmd);
                    if (reseeded) state.acceptLine(hello());
                }
                String out;
                while ((out = outbox.poll()) != null) state.acceptLine(out);
                state.acceptLine(step());
                next += (long) (DT * 1e9);
                long sleep = next - System.nanoTime();
                if (sleep > 0) {
                    try { Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000)); } catch (InterruptedException e) { return; }
                } else next = System.nanoTime();
            }
        }, "fake-sim");
        th.setDaemon(true);
        th.start();
    }

    @Override public void send(String json) { commands.add(json); }

    @Override public void stop() { running = false; }

    /** Lines other than frames that the fake wants to say (the {@code saved} reply); null when none. */
    String pollOut() { return outbox.poll(); }

    /** Apply one command. Returns true when the world was regenerated (a new hello must be sent). */
    boolean handle(String json) {
        Map<String, Object> m;
        try { m = Json.obj(Json.parse(json)); } catch (RuntimeException e) { return false; }
        String c = Json.str(m, "cmd", "");
        if (scType != null) {
            switch (c) {
                case "spawn", "reseed" -> { return false; }          // ignored in showcase mode
                case "save" -> {
                    outbox.add("{\"type\":\"saved\",\"ok\":false,\"path\":" + Json.quote(Json.str(m, "path", "")) + ",\"t\":" + t + ",\"frame\":" + frameNo
                            + ",\"error\":\"saving is not available in showcase mode\"}");
                    return false;
                }
                case "season", "hour" -> { return false; }           // the stage holds its fixed afternoon
                case "showcase" -> {
                    String ty = Json.str(m, "type", scType);
                    if (!Json.bool(m, "restart", false) && Model.CLOUD_TYPES[Model.cloudKind(ty)].equals(ty)) scType = ty;
                    clouds.clear();                                  // dropped at once: no deaths entry
                    parts.clear();
                    bolts.clear();
                    tornadoes.clear();
                    scCycle = 0;
                    startShowcaseCloud();
                    return false;
                }
                default -> { }
            }
        }
        switch (c) {
            case "pause" -> paused = true;
            case "resume" -> paused = false;
            case "speed" -> speed = Model.clamp(Json.num(m, "value", 1), 0.25, 8);
            case "spawn" -> {
                double x = Json.num(m, "x", W / 2.0);
                // v2.3: an optional type (any of the nine); a bad type means the command is ignored
                String ty = m.get("type") instanceof String s ? s : null;
                if (ty != null && !Model.CLOUD_TYPES[Model.cloudKind(ty)].equals(ty)) return false;
                FCloud k = newCloud(x, Model.clamp(Json.num(m, "y", 300), 60, groundAt(Model.clamp(x, 0, W)) - 160), ty != null ? ty : pickType());
                clouds.add(k);
                events.add(String.format("t=%.1f %s spawned by user", t, k.name));
            }
            case "wind" -> windBase += Json.num(m, "delta", 0);
            // (only here, not in the real simulation: the prevailing wind swings by +-amp m/s every period seconds;
            // the benchmarks use it to see what gusty weather costs)
            case "gust" -> { gustAmp = Json.num(m, "amp", 0); gustPeriod = Math.max(1, Json.num(m, "period", 12)); }
            case "temp" -> userC += Json.num(m, "delta", 0);
            case "gas" -> {
                boolean set = m.get("value") instanceof Number;
                double v = Json.num(m, "value", 0), d = Json.num(m, "delta", 0);
                switch (Json.str(m, "name", "")) {
                    case "humidity" -> humidity = Model.clamp(set ? v : humidity + d, 0, 1);
                    case "co2" -> co2 = Model.clamp(set ? v : co2 + d, 180, 2000);
                    case "aerosol" -> aerosol = Model.clamp(set ? v : aerosol + d, 0, 1);
                    default -> { }
                }
            }
            case "season" -> {
                if (m.get("month") instanceof Number) {
                    double d = (Model.clamp(Json.num(m, "month", 1), 1, 12.999) - 1) / 12.0 - yearFrac();
                    yearOffset += d - Math.rint(d);              // the calendar jumps; the temperature glides there (seasonShown)
                    events.add(String.format("t=%.1f calendar set to %s", t, Model.MONTHS[Model.monthIndex(1 + 12 * yearFrac())]));
                }
            }
            case "hour" -> {
                if (m.get("value") instanceof Number) {
                    double d = frac(Json.num(m, "value", 12) / 24.0) - frac(tod0 + clockDays() + hourOffset);
                    hourOffset += d - Math.rint(d);              // the clock is eased over a second or two (hourShown)
                }
            }
            case "tornado" -> requestTornado(m.get("cloud") instanceof Number n ? n.longValue() : -1);
            // v2.4: the drains command of the protocol (ignored without the water model, so in showcase mode)
            case "drains" -> { if (water != null && water.command(m) && !water.mode.equals("custom")) drainMode = water.mode; }
            // (only here, not in the real simulation: rain at a set rate on the whole world, hits a second per 32 px bucket; null = the clouds' own again)
            case "rain" -> rainRate = m.get("rate") instanceof Number n ? Model.clamp(n.doubleValue(), 0, 16) : Double.NaN;
            case "reseed" -> {
                reseed((long) Json.num(m, "seed", System.nanoTime()));
                events.add("world reseeded");
                return true;
            }
            case "view" -> {
                if (m.containsKey("x0") && m.containsKey("x1")) { viewX0 = Json.num(m, "x0", 0); viewX1 = Json.num(m, "x1", W); }
                else { viewX0 = Double.NaN; viewX1 = Double.NaN; }
            }
            case "save" -> {
                String path = Json.str(m, "path", "");
                String err = null;
                try {
                    java.io.File f = new java.io.File(path);
                    if (path.isEmpty()) throw new java.io.IOException("no path given");
                    if (f.getAbsoluteFile().getParentFile() != null) f.getAbsoluteFile().getParentFile().mkdirs();
                    java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
                    java.nio.file.Files.writeString(tmp.toPath(), "{\"fake\":true,\"seed\":" + seed + ",\"t\":" + t + ",\"frame\":" + frameNo + "}\n");
                    java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ex) {
                    err = String.valueOf(ex.getMessage());
                }
                StringBuilder sb = new StringBuilder("{\"type\":\"saved\",\"ok\":").append(err == null).append(",\"path\":")
                        .append(Json.quote(path.replace('\\', '/'))).append(",\"t\":");
                Json.num2(sb, t);
                sb.append(",\"frame\":").append(frameNo).append(",\"error\":").append(err == null ? "null" : Json.quote(err)).append('}');
                outbox.add(sb.toString());
            }
            case "quit" -> running = false;
            default -> { }
        }
        return false;
    }

    // ------------------------------------------------------------------ simulation

    double freezingY() { return Model.clamp((H - 100) - surfaceTemp * 62, 70, H); }

    @Override public String hello() {
        StringBuilder sb = new StringBuilder(64_000);
        sb.append("{\"type\":\"hello\",\"version\":2,\"seed\":").append(seed).append(",\"loaded\":false,\"t\":");
        Json.num2(sb, t);
        sb.append(",\"world\":{\"w\":").append(W).append(",\"h\":").append(H).append(",\"ground_y\":");
        Json.num(sb, G);
        sb.append(",\"terrain_kind\":\"").append(terrainKind).append("\",\"terrain\":{\"step\":").append(TSTEP).append(",\"y\":[");
        for (int i = 0; i < terrain.length; i++) { if (i > 0) sb.append(','); Json.num(sb, terrain[i]); }
        sb.append("]}},\"settlements\":[").append(settlementsWire()).append("],\"objects\":[");
        for (int i = 0; i < objs.size(); i++) {
            if (i > 0) sb.append(',');
            FObj o = objs.get(i);
            sb.append("{\"id\":").append(o.id).append(",\"kind\":\"").append(o.kind).append('"');
            if (!o.variant.isEmpty()) sb.append(",\"variant\":\"").append(o.variant).append('"');
            sb.append(",\"x\":"); Json.num(sb, o.x);
            sb.append(",\"y\":"); Json.num(sb, o.y);
            sb.append(",\"w\":"); Json.num(sb, o.w);
            sb.append(",\"h\":"); Json.num(sb, o.h);
            sb.append(",\"seed\":").append(o.seed);
            if (o.wall != null) sb.append(",\"wall\":[").append(o.wall[0]).append(',').append(o.wall[1]).append(',').append(o.wall[2]).append(']');
            if (o.roof != null) sb.append(",\"roof\":[").append(o.roof[0]).append(',').append(o.roof[1]).append(',').append(o.roof[2]).append(']');
            if (o.building) sb.append(",\"floors\":").append(o.floors);
            if (o.settlement > 0) sb.append(",\"settlement\":").append(o.settlement);
            sb.append(",\"layer\":").append(o.layer);
            if (o.damage > 0.004) { sb.append(",\"damage\":"); Json.num2(sb, o.damage); }
            o.sDamage = o.damage;
            sb.append(",\"sway\":"); Json.num2(sb, o.sway);
            sb.append(",\"snow\":"); Json.num2(sb, o.snow);
            sb.append(",\"wet\":"); Json.num2(sb, o.wet);
            sb.append('}');
            o.sSway = o.sway; o.sSnow = o.snow; o.sWet = o.wet; o.sW = o.w; o.sH = o.h;
        }
        sb.append("]");
        appendV22(sb);
        appendV23(sb);
        if (water != null) { water.helloJson(sb); water.helloSent(); }      // v2.4 (the next frame carries the complete lists again)
        sb.append("}");
        return sb.toString();
    }

    /** Advance one tick (honouring pause/speed) and return the frame JSON line. */
    @Override public String step() {
        double dt = paused ? 0 : DT * speed;
        updateClimate(dt);
        if (dt > 0) advance(dt);
        frameNo++;
        String s = frameJson();
        events.clear();
        deaths.clear();
        return s;
    }

    private void advance(double dt) {
        t += dt;
        double fz = freezingY();
        // spawn: the climate sets how many clouds the sky holds and how fast new ones appear (v2.3)
        int target = (int) Math.round(cloudTarget);
        if (scType != null) {
            // showcase: exactly one cloud; after it is gone a short pause, then the next cycle
            if (clouds.isEmpty()) {
                if (scPauseUntil < 0) scPauseUntil = t + 3;
                else if (t >= scPauseUntil) startShowcaseCloud();
            }
        } else if (scene == null && clouds.size() < target && rng.nextDouble() < dt * spawnPerMin / 60.0 * (clouds.size() < target * 0.6 ? 3 : 1)) {
            double x = windBase >= 0 ? rng.nextDouble() * W * 0.8 : W - rng.nextDouble() * W * 0.8;
            clouds.add(newCloud(x, Double.NaN, pickType()));
        }
        tryMerges();
        // clouds
        for (int k = clouds.size() - 1; k >= 0; k--) {
            FCloud c = clouds.get(k);
            c.age += dt;
            c.prog += dt / c.lifetime;
            c.tick(t);
            double[] w = windAt(c.x, c.y);
            if (c.absorbedBy != null) { c.x = c.absorbedBy.x + c.relX; c.y = c.absorbedBy.y + c.relY; }   // rides along while it fades
            else c.x += (w[0] * 2.2 + c.drift) * dt;
            c.shear += (c.shearTarget() - c.shear) * Math.min(1, dt * 0.4);
            int st = c.absorbedBy != null ? 4 : c.stageForFrac();
            if (st != c.stageIdx) {
                c.stageIdx = st;
                c.stageTimes.put(Model.STAGES[st], t);
                String extra = st == 3 ? " (" + c.precipType(fz) + ")" : "";
                events.add(String.format("t=%.1f %s began %s%s", t, c.name, Model.STAGES[st], extra));
            }
            double f = c.frac();
            c.water = Model.clamp(Math.sin(Math.min(1, f / 0.7) * Math.PI * 0.5) * (c.capacity + c.capBonus * c.mergeProg()) - (f > 0.55 ? (f - 0.55) * 1.6 : 0), 0.02, 1);
            c.peakWater = Math.max(c.peakWater, c.water);
            c.ice = c.top() < fz ? Model.clamp(c.water * 0.6 * (fz - c.top()) / 200, 0, 1) : 0;
            c.rate = c.absorbedBy != null ? 0 : st == 3 ? Model.clamp(c.water * 1.2, 0.1, 1) : (st == 4 && c.rains ? 0.15 * c.form() : 0);
            if (c.rate > 0) {
                double mm = c.rate * dt * 0.2;
                c.totalPrecip += mm;
                String pt = c.precipType(fz);
                if (pt.equals("snow")) snowMm += mm; else rainMm += mm;
            }
            emit(c, dt, fz);
            boolean absorbed = c.absorbedBy != null && t - c.absorbT >= ABSORB_S;
            if (absorbed || c.frac() >= 1 || c.x < -500 || c.x > W + 500) {
                clouds.remove(k);
                dead++;
                c.stageTimes.putIfAbsent("dissipating", t);
                deaths.add(c.deathJson(t, fz));
                if (absorbed) events.add(String.format("t=%.1f %s is gone: merged into %s", t, c.name, c.absorbedBy.name));
                else events.add(String.format("t=%.1f %s dissipated after %.0f s", t, c.name, c.age));
                for (FCloud o : clouds) if (o.absorbedBy == c) o.absorbedBy = null;      // its absorber died first: it just fades out
            }
        }
        updateTornadoes(dt);
        updateCars(dt);
        // particles
        for (int k = parts.size() - 1; k >= 0; k--) {
            P p = parts.get(k);
            p.life -= dt;
            double[] w = windAt(p.x, p.y);
            switch (p.kind) {
                case 0 -> { p.vx = w[0] * 3 + rng.nextGaussian() * 4; p.vy = rng.nextGaussian() * 4; }
                case 1 -> { p.vx = w[0] * 7; p.vy = 380 + p.size * 50; }
                case 2 -> {
                    p.vx = w[0] * 7 + Math.sin(t * 2 + p.phase) * 14; p.vy = 46 + p.size * 2;
                    if (p.y > fz && surfaceTemp > 0.5) { p.kind = 6; p.life = 99; p.size = Math.min(p.size, 2.6f); }
                }
                case 3 -> { p.vx = w[0] * 4 + Math.sin(t + p.phase) * 6; p.vy = 8 + Math.cos(t * 1.3 + p.phase) * 6; }
                case 4 -> { p.vx = w[0] * 4; p.vy = 270; }
                case 5 -> { p.vy += 300 * dt; }
                case 6 -> {
                    p.vx = w[0] * 6; p.vy = 130 + (p.y - fz) * 1.4;
                    if (p.y > fz + 70) { p.kind = 1; p.size = 1.4f; }
                }
                case 7 -> {                                   // debris: flung up, swirls around its tornado, falls back
                    p.vy += 250 * dt;
                    p.vx += (p.phase - p.x) * 3.2 * dt;       // phase holds the x it orbits
                    p.vx *= 1 - Math.min(1, dt * 0.6);
                }
                default -> { }
            }
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.rot += p.spin * dt;
            if (p.kind == 7) {
                if (p.life <= 0 || p.y >= groundAt(p.x) + 2) parts.remove(k);
                continue;
            }
            if (p.kind != 5 && p.kind != 0 && p.kind != 3) {
                double gy = groundAt(p.x);
                if (p.y >= gy - 1) {
                    int gi = (int) Model.clamp(Math.round(p.x / GSTEP), 0, gWet.length - 1);
                    if (p.kind == 1 || p.kind == 6) {
                        if (water != null && Double.isNaN(rainNow())) water.rain(p.x, FAKE_HIT);      // v2.4: it adds to the hollow or street it fell on
                        gWet[gi] = Math.min(1, gWet[gi] + 0.02);
                        if (rng.nextDouble() < 0.35) {
                            P s = new P(5, p.x, gy - 1, 1.5f + rng.nextFloat() * 2);
                            s.vx = rng.nextGaussian() * 25; s.vy = -50 - rng.nextDouble() * 40; s.life = 0.35;
                            parts.add(s);
                        }
                    } else if (p.kind == 2) {
                        gSnow[gi] = Math.min(1, gSnow[gi] + 0.03);
                    } else if (p.kind == 4) {
                        P s = new P(5, p.x, gy - 2, 1.2f);
                        s.vx = rng.nextGaussian() * 40; s.vy = -90; s.life = 0.3;
                        parts.add(s);
                    }
                    parts.remove(k);
                    continue;
                }
            }
            if (p.life <= 0 || p.x < -40 || p.x > W + 40 || p.y > H) parts.remove(k);
        }
        // ground cover: wet dries, snow melts below the freezing level and lingers above it
        for (int i = 0; i < gWet.length; i++) {
            gWet[i] = Math.max(0, gWet[i] - dt * 0.012);
            double gy = groundAt(i * GSTEP);
            if (gy < fz) gSnow[i] = Math.min(1, Math.max(gSnow[i], Model.clamp((fz - gy) / 60.0, 0, 1) * 0.98));
            else if (surfaceTemp > 0.5) gSnow[i] = Math.max(0, gSnow[i] - dt * 0.01);
        }
        // objects: sway with the wind, snow / wet from the ground under them, puddles and snowpacks grow
        for (FObj o : objs) {
            int gi = (int) Model.clamp(Math.round(o.x / GSTEP), 0, gWet.length - 1);
            switch (o.kind) {
                case "tree", "windmill", "factory" -> {
                    double[] w = windAt(o.x, o.y - o.h);
                    o.sway = Model.clamp(w[0] / 14 + Math.sin(t * 1.7 + o.x * 0.01) * 0.05, -1, 1);
                }
                default -> { }
            }
            for (FTornado tn : tornadoes) {
                if (tn.reach < 1) continue;
                double d = Math.abs(o.x - tn.x), core = tn.width * 0.55 + o.w * 0.5 + 10;
                if (o.kind.equals("tree") && d < 260)        // trees flail towards the funnel
                    o.sway = Model.clamp(Math.signum(tn.x - o.x) * (1 - d / 260) * 1.3 + Math.sin(t * 11 + o.x) * 0.5 * (1 - d / 260), -1, 1);
                if (d < core && !o.kind.equals("mountain") && !o.kind.equals("hill") && !o.kind.equals("pond") && !o.kind.equals("puddle") && !o.kind.equals("snowpack"))
                    o.damage = Math.min(1, o.damage + dt * tn.strength * (o.building ? 0.42 : 0.9) * (1 - 0.5 * d / core) * hitResist(o));
            }
            switch (o.kind) {
                case "mountain", "hill" -> { }
                case "puddle" -> { o.wet = gWet[gi]; o.w = 6 + gWet[gi] * 70; o.h = 2 + gWet[gi] * 5; }
                case "snowpack" -> { o.snow = gSnow[gi]; o.w = 40 + gSnow[gi] * 200; o.h = 3 + gSnow[gi] * 16; }
                case "pond" -> { o.wet = gWet[gi]; o.snow = surfaceTemp < -1 ? 1 : 0; }
                default -> { o.snow = gSnow[gi]; o.wet = gWet[gi]; }
            }
        }
        // v2.4: the standing water (after this tick's rain)
        if (water != null) {
            double rr = rainNow();
            if (!Double.isNaN(rr)) { water.rainEverywhere(rr, dt); showRain(rr, dt); }
            water.update(dt);
        }
        // lightning
        for (int k = bolts.size() - 1; k >= 0; k--) {
            FBolt b = bolts.get(k);
            b.alpha -= dt * 2.2;
            if (b.alpha <= 0) bolts.remove(k);
        }
        for (FCloud c : clouds) {
            if ((c.type.equals("cumulonimbus") || c.superc) && c.stageIdx == 3 && (!c.towers || c.morph() > 0.6) && rng.nextDouble() < dt * (c.superc ? 0.45 : 0.3)) {
                bolts.add(bolt(c.x + (rng.nextDouble() - 0.5) * c.w * 0.4, c.base()));
                events.add(String.format("t=%.1f lightning from %s", t, c.name));
            }
        }
    }

    private void emit(FCloud c, double dt, double fz) {
        if (parts.size() > 5200) return;
        double form = c.form();
        // cloud droplets inside the cloud
        if (rng.nextDouble() < dt * 22 * c.water * form) {
            double[] q = c.randomPuffPoint(rng);
            P p = new P(0, q[0], q[1], 1f); p.life = 1.2; parts.add(p);
        }
        // ice crystals near the cold top
        if (c.top() < fz && rng.nextDouble() < dt * 10 * Math.max(0.1, c.ice) * form) {
            double[] q = c.randomPuffPoint(rng);
            if (q[1] < fz) {
                P p = new P(3, q[0], Math.min(q[1], c.y), 1.8f + rng.nextFloat() * 1.8f); p.life = 3 + rng.nextDouble() * 2;
                p.spin = (float) (rng.nextGaussian() * 0.8); parts.add(p);
            }
        }
        if (c.rate <= 0) return;
        String pt = c.precipType(fz);
        double n = c.rate * dt * ((pt.equals("snow") || pt.equals("mixed")) && c.base() < fz ? 14 : 70) * (c.w / 260);
        while (n > 0) {
            if (rng.nextDouble() > n) break;
            n -= 1;
            // a supercell rains from its forward (downwind) flank only: the base around the wall cloud stays dry
            double x = c.superc ? c.x + c.dirS * c.w0 * (0.03 + 0.36 * rng.nextDouble()) : c.x + (rng.nextDouble() - 0.5) * c.w * 0.7;
            double y = c.base() - 6;
            int kind;
            if (pt.equals("snow") || pt.equals("mixed")) kind = y < fz ? 2 : 1;
            else kind = 1;
            if ((c.type.equals("cumulonimbus") || c.superc) && rng.nextDouble() < (c.superc ? 0.24 : c.hail ? 0.2 : 0.05)) kind = 4;
            P p = new P(kind, x, y, kind == 1 ? 1f + rng.nextFloat() * 1.5f : kind == 2 ? 2.2f + rng.nextFloat() * 2.6f
                    : c.superc ? 3 + rng.nextFloat() * rng.nextFloat() * 6 : 2 + rng.nextFloat() * 2);
            p.spin = (float) (rng.nextGaussian() * 1.5);
            p.rot = (float) (rng.nextDouble() * Math.PI);
            p.phase = rng.nextDouble() * 6.28;
            p.life = 40;
            parts.add(p);
        }
    }

    private FBolt bolt(double x, double y) {
        FBolt b = new FBolt();
        b.alpha = 1;
        double cx = x, cy = y;
        b.pts.add(new double[]{cx, cy});
        while (true) {
            cy += 18 + rng.nextDouble() * 22;
            cx += rng.nextGaussian() * 16;
            double gy = groundAt(cx);
            if (cy >= gy) { b.pts.add(new double[]{cx, gy}); break; }
            b.pts.add(new double[]{cx, cy});
        }
        return b;
    }

    /** The prevailing wind right now, m/s (positive blows to the right): what the {@code wind} command changes. */
    double windBase() { return windBase; }

    private double gustAmp, gustPeriod = 12;

    double[] windAt(double x, double y) {
        double gy = groundAt(x);
        double hFrac = 1 - Model.clamp(y / gy, 0, 1);
        double base = gustAmp == 0 ? windBase : windBase + gustAmp * Math.sin(t * (2 * Math.PI / gustPeriod));
        double u = base * (0.5 + 1.0 * hFrac) + 1.4 * Math.sin(x * 0.004 + t * 0.2 + y * 0.008);
        double v = 0.6 * Math.cos(x * 0.006 - t * 0.15 + y * 0.004);
        // orographic lift: air follows the slope near the surface
        double slope = (groundAt(x + 40) - groundAt(x - 40)) / 80.0;
        double near = Math.exp(-Math.max(0, gy - y) / 220.0);
        v += u * slope * near * 1.4;
        for (FCloud c : clouds) {
            double dx = (x - c.x) / (c.w * 0.45);
            if (Math.abs(dx) > 2.2) continue;
            double below = y - c.base();
            if (below < -c.h || below > 380) continue;
            double g = Math.exp(-dx * dx) * c.form();
            if (c.stageIdx <= 2) v -= c.updraft() * g * 0.9;
            else if (c.stageIdx == 3) {
                v -= c.updraft() * 0.6 * Math.exp(-(dx + 1.1) * (dx + 1.1) * 3);
                v += c.updraft() * 0.8 * Math.exp(-(dx - 0.6) * (dx - 0.6) * 3) * (below > 0 ? 1 : 0.2);
            }
        }
        for (FTornado tn : tornadoes) {
            // a tornado on the ground: the air near the surface converges on it and is drawn upward around it
            if (tn.reach < 0.5) continue;
            double dx = x - tn.x, below = tn.groundY - y;
            if (Math.abs(dx) > 520 || below < -20 || below > 420) continue;
            double g = Math.exp(-(dx * dx) / (190.0 * 190.0)) * tn.strength * tn.reach, lowF = Math.exp(-Math.max(0, below) / 240.0);
            u -= Math.tanh(dx / 70.0) * 15 * g * lowF;
            v -= 9 * g * Math.exp(-(dx * dx) / (60.0 * 60.0));
        }
        if (y > gy) { u *= 0.2; v = 0; }
        return new double[]{u, v};
    }

    private FCloud newCloud(double x, double y, String forcedType) { return newCloud(x, y, forcedType, false); }

    private FCloud newCloud(double x, double y, String forcedType, boolean forceTower) {
        long cs;
        FCloud c;
        do {
            cs = rng.nextLong();
            c = new FCloud(nextId, cs, x, y, forcedType, t, forceTower);
        } while (!signatures.add(c.signature()));
        nextId++;
        born++;
        // v2.3: more condensation nuclei (aerosol) make longer-lived clouds; a rare supercell may be a tornado producer
        if (scType == null && scene == null) c.lifetime *= 0.88 + 0.4 * aerosol;
        if (c.superc) c.tornadic = rng.nextDouble() < 0.75;
        events.add(String.format("t=%.1f %s formed (%s%s)", t, c.name, c.type, c.fair() ? ", fair weather" : ""));
        return c;
    }

    // ------------------------------------------------------------------ JSON

    private String frameJson() {
        double fz = freezingY();
        StringBuilder sb = new StringBuilder(96_000 + parts.size() * 40);
        sb.append("{\"type\":\"frame\",\"frame\":").append(frameNo).append(",\"t\":");
        Json.num2(sb, t);
        sb.append(",\"speed\":").append(speed).append(",\"paused\":").append(paused);
        sb.append(",\"world\":{\"w\":").append(W).append(",\"h\":").append(H).append(",\"ground_y\":");
        Json.num(sb, G);
        sb.append(",\"freezing_y\":");
        Json.num(sb, fz);
        sb.append(",\"surface_temp_c\":");
        Json.num2(sb, surfaceTemp);
        sb.append(",\"time_of_day\":");
        sb.append(Math.round(tod() * 100000) / 100000.0);
        // v2.3: the calendar and what the surface temperature is made of
        double yf = yearFrac(), gasC = gasC(), dayMid = climBase + seasonShown + gasC + userC;
        sb.append(",\"year_frac\":").append(Math.round(yf * 100000) / 100000.0);
        sb.append(",\"month\":").append(Math.round((1 + 12 * yf) * 10000) / 10000.0);
        sb.append(",\"day\":").append((int) Math.floor(clockDays()));
        sb.append(",\"season\":\"").append(Model.seasonOf(1 + 12 * yf, "N")).append("\",\"hemisphere\":\"N\"");
        sb.append(",\"temp\":{\"base_c\":"); Json.num2(sb, climBase);
        sb.append(",\"season_c\":"); Json.num2(sb, seasonShown);
        sb.append(",\"diurnal_c\":"); Json.num2(sb, diurnalNow);
        sb.append(",\"gas_c\":"); Json.num2(sb, gasC);
        sb.append(",\"user_c\":"); Json.num2(sb, userC);
        sb.append(",\"day_min_c\":"); Json.num2(sb, dayMid - climDiurnal * 0.5);
        sb.append(",\"day_max_c\":"); Json.num2(sb, dayMid + climDiurnal * 0.5);
        sb.append("}}");
        sb.append(",\"atmosphere\":{\"humidity\":"); Json.num2(sb, humidity);
        sb.append(",\"co2_ppm\":"); Json.num(sb, co2);
        sb.append(",\"aerosol\":"); Json.num2(sb, aerosol);
        sb.append(",\"instability\":"); Json.num2(sb, instability);
        sb.append(",\"shear\":"); Json.num2(sb, shearIdx);
        sb.append(",\"spawn_per_min\":"); Json.num2(sb, spawnPerMin);
        sb.append(",\"cloud_target\":").append((int) Math.round(cloudTarget)).append(",\"type_weights\":{");
        for (int k = 0; k < typeW.length; k++) {
            if (k > 0) sb.append(',');
            sb.append('"').append(Model.CLOUD_TYPES[k]).append("\":").append(Math.round(typeW[k] * 1000) / 1000.0);
        }
        sb.append("}}");
        // wind grid
        int cell = 80, cols = (int) Math.ceil(W / (double) cell), rows = (int) Math.ceil(H / (double) cell);
        StringBuilder vs = new StringBuilder();
        sb.append(",\"wind\":{\"cols\":").append(cols).append(",\"rows\":").append(rows).append(",\"cell\":").append(cell).append(",\"u\":[");
        double sum = 0;
        for (int r = 0; r < rows; r++) for (int cc = 0; cc < cols; cc++) {
            double[] w = windAt((cc + 0.5) * cell, (r + 0.5) * cell);
            if (r + cc > 0) { sb.append(','); vs.append(','); }
            Json.num(sb, w[0]);
            Json.num(vs, w[1]);
            sum += Math.hypot(w[0], w[1]);
        }
        sb.append("],\"v\":[").append(vs).append("]}");
        // streamlines
        sb.append(",\"streamlines\":[");
        int sc = 0;
        for (int k = 0; k < 40; k++) {
            double sx = (windBase >= 0 ? -10 : W + 10) + (windBase >= 0 ? 1 : -1) * (k % 5) * W / 5.0;
            double sy = 60 + (k / 5) * (H - 260) / 8.0 + (k % 5) * 14;
            if (sy > groundAt(Model.clamp(sx, 0, W)) - 30) continue;
            StringBuilder pts = new StringBuilder();
            double x = sx, y = sy, sp = 0;
            int n = 0;
            for (int i = 0; i < 46; i++) {
                double[] w = windAt(x, y);
                double m = Math.hypot(w[0], w[1]);
                if (m < 0.05) break;
                if (n > 0) pts.append(',');
                pts.append('[');
                Json.num(pts, x);
                pts.append(',');
                Json.num(pts, y);
                pts.append(']');
                n++;
                sp += m;
                x += w[0] / m * 18;
                y += w[1] / m * 18;
                if (x < -20 || x > W + 20 || y < 0 || y > groundAt(Model.clamp(x, 0, W)) - 8) break;
            }
            if (n < 3) continue;
            if (sc++ > 0) sb.append(',');
            sb.append("{\"pts\":[").append(pts).append("],\"speed\":");
            Json.num(sb, sp / n);
            sb.append(",\"phase\":");
            Json.num2(sb, (k * 0.37) % 1);
            sb.append('}');
        }
        sb.append(']');
        // clouds
        sb.append(",\"clouds\":[");
        for (int k = 0; k < clouds.size(); k++) {
            if (k > 0) sb.append(',');
            clouds.get(k).json(sb, fz);
        }
        sb.append(']');
        // particles (honouring the view command)
        sb.append(",\"particles\":[");
        int ice = 0, listed = 0;
        boolean cull = !Double.isNaN(viewX0);
        for (int k = 0; k < parts.size(); k++) {
            P p = parts.get(k);
            if (p.kind == 3) ice++;
            if (cull && (p.x < viewX0 - 200 || p.x > viewX1 + 200)) continue;
            if (listed++ > 0) sb.append(',');
            sb.append('[').append(p.kind).append(',');
            Json.num(sb, p.x); sb.append(',');
            Json.num(sb, p.y); sb.append(',');
            Json.num(sb, p.vx); sb.append(',');
            Json.num(sb, p.vy); sb.append(',');
            Json.num(sb, p.size); sb.append(',');
            Json.num(sb, p.rot); sb.append(']');
        }
        sb.append(']');
        // arrows
        sb.append(",\"arrows\":[");
        List<String> arrows = new ArrayList<>();
        for (FCloud c : clouds) {
            if (c.form() < 0.5) continue;
            double b = c.base();
            if (c.stageIdx >= 1 && c.stageIdx <= 3 && c.updraft() > 2) {
                double u = c.updraft();
                arrows.add(arrow(c.x - c.w * 0.18, b + 70, c.x - c.w * 0.18, b + 70 - 12 * u, "updraft", u, String.format("updraft %.1f m/s", u)));
            }
            if (c.stageIdx == 3) {
                arrows.add(arrow(c.x + c.w * 0.2, b + 20, c.x + c.w * 0.2, b + 110, "fall", c.rate,
                        String.format("%s %.1f mm/h", c.precipType(fz), c.rate * 12)));
                if (c.type.equals("cumulonimbus") || c.superc)
                    arrows.add(arrow(c.x + c.w * 0.4, b + 30, c.x + c.w * 0.45, b + 140, "downdraft", 3.1, "downdraft 3.1 m/s"));
            }
            if (c.stageIdx >= 1 && c.stageIdx <= 2 && (c.type.equals("cumulus") || c.type.equals("cumulonimbus"))) {
                double tp = c.top();
                String lab = tp < fz ? "vapor → ice (deposition)" : "collision–coalescence";
                arrows.add(arrow(c.x + c.w * 0.42 + 30, tp - 10, c.x + c.w * 0.3, tp + 18, "growth", c.water, lab));
            }
        }
        sb.append(String.join(",", arrows)).append(']');
        // object deltas: only what changed since it was last listed
        sb.append(",\"objects\":[");
        int no = 0;
        for (FObj o : objs) {
            if (Math.abs(o.sway - o.sSway) < 0.008 && Math.abs(o.snow - o.sSnow) < 0.008 && Math.abs(o.wet - o.sWet) < 0.008
                    && Math.abs(o.w - o.sW) < 0.2 && Math.abs(o.h - o.sH) < 0.1) continue;
            if (no++ > 0) sb.append(',');
            sb.append('[').append(o.id).append(',');
            Json.num2(sb, o.sway); sb.append(',');
            Json.num2(sb, o.snow); sb.append(',');
            Json.num2(sb, o.wet); sb.append(',');
            Json.num(sb, o.w); sb.append(',');
            Json.num(sb, o.h); sb.append(']');
            o.sSway = o.sway; o.sSnow = o.snow; o.sWet = o.wet; o.sW = o.w; o.sH = o.h;
        }
        sb.append(']');
        // v2.3: damage, only for objects whose damage changed
        StringBuilder dm = null;
        for (FObj o : objs) {
            if (o.damage - o.sDamage < 0.01 && !(o.damage >= 1 && o.sDamage < 1)) continue;
            if (dm == null) dm = new StringBuilder(); else dm.append(',');
            dm.append('[').append(o.id).append(',');
            Json.num2(dm, o.damage);
            dm.append(']');
            o.sDamage = o.damage;
        }
        if (dm != null) sb.append(",\"damage\":[").append(dm).append(']');
        if (!tornadoes.isEmpty()) {
            sb.append(",\"tornadoes\":[");
            for (int k = 0; k < tornadoes.size(); k++) { if (k > 0) sb.append(','); tornadoes.get(k).json(sb); }
            sb.append(']');
        }
        if (!cars.isEmpty()) {
            sb.append(",\"cars\":[");
            for (int k = 0; k < cars.size(); k++) { if (k > 0) sb.append(','); cars.get(k).json(sb); }
            sb.append(']');
        }
        // ground cover: a few times a second at most, only when it changed
        groundClock += DT;
        if (!groundSentOnce || groundClock >= 0.3) {
            boolean changed = !groundSentOnce;
            for (int i = 0; i < gSnow.length && !changed; i++)
                if (Math.abs(gSnow[i] - gSnowSent[i]) > 0.01 || Math.abs(gWet[i] - gWetSent[i]) > 0.01) changed = true;
            if (changed) {
                sb.append(",\"ground\":{\"step\":").append(GSTEP).append(",\"snow\":[");
                for (int i = 0; i < gSnow.length; i++) { if (i > 0) sb.append(','); Json.num2(sb, gSnow[i]); gSnowSent[i] = gSnow[i]; }
                sb.append("],\"wet\":[");
                for (int i = 0; i < gWet.length; i++) { if (i > 0) sb.append(','); Json.num2(sb, gWet[i]); gWetSent[i] = gWet[i]; }
                sb.append("]}");
                groundSentOnce = true;
            }
            groundClock = 0;
        }
        if (water != null) water.frameJson(sb);          // v2.4: water / flood / drains, each by its own send rule
        // lightning
        sb.append(",\"lightning\":[");
        for (int k = 0; k < bolts.size(); k++) {
            FBolt b = bolts.get(k);
            if (k > 0) sb.append(',');
            sb.append("{\"pts\":[");
            for (int i = 0; i < b.pts.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('['); Json.num(sb, b.pts.get(i)[0]); sb.append(','); Json.num(sb, b.pts.get(i)[1]); sb.append(']');
            }
            sb.append("],\"alpha\":");
            Json.num2(sb, b.alpha);
            sb.append('}');
        }
        sb.append(']');
        // stats
        sb.append(",\"stats\":{\"clouds_alive\":").append(clouds.size()).append(",\"clouds_born\":").append(born)
                .append(",\"clouds_dead\":").append(dead).append(",\"rain_mm\":");
        Json.num(sb, rainMm);
        sb.append(",\"snow_mm\":");
        Json.num(sb, snowMm);
        sb.append(",\"particles\":").append(parts.size()).append(",\"ice_crystals\":").append(ice).append(",\"wind_mean\":");
        Json.num(sb, sum / (cols * rows));
        sb.append(",\"tornadoes\":").append(tornadoes.size()).append(",\"tornadoes_total\":").append(tornadoesTotal).append(",\"cars\":").append(cars.size());
        sb.append('}');
        if (scType != null) {
            sb.append(",\"showcase\":{\"type\":\"").append(scType).append("\",\"cycle\":").append(scCycle).append(",\"cloud_id\":")
                    .append(clouds.isEmpty() ? "null" : String.valueOf(clouds.get(0).id));
            String step = clouds.isEmpty() ? null : tourStep(clouds.get(0));
            if (TOUR_TYPE.equals(scType)) sb.append(",\"tour_step\":").append(step == null ? "null" : Json.quote(step));
            sb.append('}');
        }
        sb.append(",\"events\":[");
        for (int k = 0; k < events.size(); k++) { if (k > 0) sb.append(','); sb.append(Json.quote(events.get(k))); }
        sb.append("],\"deaths\":[").append(String.join(",", deaths)).append("]}");
        return sb.toString();
    }

    private static String arrow(double x1, double y1, double x2, double y2, String kind, double mag, String label) {
        StringBuilder sb = new StringBuilder("{\"x1\":");
        Json.num(sb, x1); sb.append(",\"y1\":"); Json.num(sb, y1);
        sb.append(",\"x2\":"); Json.num(sb, x2); sb.append(",\"y2\":"); Json.num(sb, y2);
        sb.append(",\"kind\":\"").append(kind).append("\",\"mag\":"); Json.num(sb, mag);
        sb.append(",\"label\":").append(Json.quote(label)).append('}');
        return sb.toString();
    }

    // ------------------------------------------------------------------ inner types

    private static final class FObj {
        final long id;
        final String kind, variant;
        final double x;
        double y, w, h, sway, snow, wet, damage;
        double sSway, sSnow, sWet, sW, sH, sDamage;     // state as last sent
        long seed, settlement = -1;
        int[] wall, roof;
        int floors = 1, layer;
        boolean building;

        FObj(long id, String kind, String variant, double x, double y, double w, double h) {
            this.id = id; this.kind = kind; this.variant = variant; this.x = x; this.y = y; this.w = w; this.h = h;
        }
    }

    private static final class P {
        int kind;
        double x, y, vx, vy, life = 5, phase;
        float size, rot, spin;
        P(int kind, double x, double y, float size) { this.kind = kind; this.x = x; this.y = y; this.size = size; }
    }

    private static final class FBolt {
        final List<double[]> pts = new ArrayList<>();
        double alpha;
    }

    private final class FCloud {
        final long id, cseed;
        String type, name;           // v2.1: both change when a cumulus towers into a cumulonimbus
        double w, h, lifetime;       // w / h glide to the cumulonimbus's during that change
        final double capacity, anvilMax, shearK;
        double drift;
        final boolean hail;
        boolean rains;
        final double[][] puffs;      // dx, dy, r, shade, delay [, role]
        // --- v2.3 ---
        /** Life progress 0..1. It advances by dt / lifetime, so extending the lifetime at a merge never moves it. */
        double prog;
        final boolean superc;
        final int dirS;              // +1 = the wind blew to the right when it formed (rear flank on the left)
        boolean tornadic, tornadoDone;
        double wallBoost, wallHold;  // a requested tornado lowers the wall cloud at once; it stays down while the funnel lives
        FTornado tornado;
        final List<double[]> extra = new ArrayList<>();   // absorbed puffs: dx, dy below the base line, r, shade, part, t0, delay
        final List<String[]> absorbedParts = new ArrayList<>();   // id, type, name
        double mergeT = -1e9, mergeDW, mergeDWDone, capBonus;
        FCloud absorbedBy, mergeWith;
        double absorbT, relX, relY, mergeDx = 30;
        double darkShown = Double.NaN, lastDarkT;
        final int[] tint;
        final double opacity;
        // --- v2.1: fair weather ---
        final boolean fairKind;      // a type that can be fair at all (never cumulonimbus / nimbostratus)
        double developAt;            // life fraction at which a fair cloud develops: 0 = never fair, 9 = fair all its life
        boolean wasFair;
        // --- v2.1: cumulus -> cumulonimbus ---
        final double w0, h0;         // the size it was born with (the cumulus)
        boolean towers, morphStarted;
        double morphAge, morphDur, cbW, cbH, cbAnvil;
        double[][] tower = new double[0][];   // appended puffs: dx, height above the base, r, shade, delay (in morph)
        int[] cbTint;
        double x, y, age, born, water, ice, rate, totalPrecip, peakWater, shear;
        int stageIdx;
        Map<String, Double> stageTimes = new LinkedHashMap<>();

        FCloud(long id, long cs, double x, double y, String forcedType, double now, boolean forceTower) {
            Random r = new Random(cs);
            this.id = id;
            this.cseed = cs & 0x7fffffffL;
            this.x = x;
            String[] types = {"cumulus", "cumulus", "cumulus", "cumulonimbus", "nimbostratus", "stratocumulus", "altostratus",
                    "altocumulus", "stratus", "cirrus", "cirrus"};
            type = forcedType != null ? forcedType : types[r.nextInt(types.length)];
            int kind = Model.cloudKind(type);
            name = String.format("%s-%04d \"%s %s\"", Model.CLOUD_PREFIX[kind], id, ADJ[r.nextInt(ADJ.length)], NOUN[r.nextInt(NOUN.length)]);
            double sw, sh, alt, life;
            double k = H / 1080.0;
            superc = kind == Model.SUPERCELL;
            dirS = windBase >= 0 ? 1 : -1;
            switch (kind) {
                case Model.SUPERCELL -> { sw = 500 + r.nextDouble() * 90; sh = 430 + r.nextDouble() * 50; alt = 340; life = 215 + r.nextDouble() * 40; }
                case Model.CUMULONIMBUS -> { sw = 400 + r.nextDouble() * 120; sh = 400 + r.nextDouble() * 70; alt = 330; life = 170 + r.nextDouble() * 90; }
                case Model.NIMBOSTRATUS -> { sw = 620 + r.nextDouble() * 260; sh = 130 + r.nextDouble() * 40; alt = 430; life = 150 + r.nextDouble() * 90; }
                case Model.STRATOCUMULUS -> { sw = 460 + r.nextDouble() * 180; sh = 84 + r.nextDouble() * 26; alt = 450; life = 90 + r.nextDouble() * 70; }
                case Model.ALTOSTRATUS -> { sw = 620 + r.nextDouble() * 280; sh = 54 + r.nextDouble() * 26; alt = 250; life = 90 + r.nextDouble() * 70; }
                case Model.ALTOCUMULUS -> { sw = 420 + r.nextDouble() * 180; sh = 66 + r.nextDouble() * 26; alt = 210; life = 80 + r.nextDouble() * 70; }
                case Model.STRATUS -> { sw = 640 + r.nextDouble() * 320; sh = 60 + r.nextDouble() * 26; alt = 520; life = 110 + r.nextDouble() * 80; }
                case Model.CIRRUS -> { sw = 440 + r.nextDouble() * 280; sh = 60 + r.nextDouble() * 40; alt = 100; life = 90 + r.nextDouble() * 80; }
                default -> { sw = 190 + r.nextDouble() * 150; sh = 120 + r.nextDouble() * 80; alt = 400; life = 70 + r.nextDouble() * 80; }
            }
            double yy = Double.isNaN(y) ? alt * k + r.nextGaussian() * 22 : y;
            // keep the base well above the terrain under the cloud
            double gmin = Double.MAX_VALUE;
            for (double gx = x - sw / 2; gx <= x + sw / 2; gx += 40) gmin = Math.min(gmin, groundAt(Model.clamp(gx, 0, W)));
            yy = Math.min(yy, gmin - sh / 2 - 90);
            this.y = Math.max(50 + sh / 2 * 0.3, yy);
            w = sw; h = sh;
            lifetime = life;
            capacity = 0.5 + r.nextDouble() * 0.5;
            hail = r.nextDouble() < 0.35;
            rains = switch (kind) {
                case Model.CUMULONIMBUS, Model.NIMBOSTRATUS, Model.SUPERCELL -> true;
                case Model.CUMULUS -> sw * sh > 44000;
                case Model.STRATOCUMULUS -> r.nextDouble() < 0.3;
                case Model.STRATUS -> r.nextDouble() < 0.25;
                default -> false;
            };
            drift = r.nextGaussian() * 3;
            anvilMax = kind == Model.CUMULONIMBUS ? 0.6 + r.nextDouble() * 0.4 : superc ? 0.88 + r.nextDouble() * 0.12 : 0;
            shearK = 0.6 + r.nextDouble() * 0.8;
            opacity = kind == Model.CIRRUS ? 0.8 : kind == Model.ALTOSTRATUS ? 0.86 : 0.96;
            List<double[]> pl = new ArrayList<>();
            double base = sh * 0.5;
            switch (kind) {
                case Model.SUPERCELL -> {
                    // flat rain-free base
                    int nb = 7;
                    for (int i = 0; i < nb; i++) {
                        double rad = sw * (0.088 + r.nextDouble() * 0.025);
                        double dx = ((i + 0.5) / nb - 0.5) * sw * 0.80 + r.nextGaussian() * sw * 0.012;
                        pl.add(new double[]{dx, base - rad, rad, 0, r.nextDouble() * 0.12, 0});
                    }
                    // the rotating updraft tower: narrow-waisted, leaning a little downshear
                    int nt = 24;
                    for (int i = 0; i < nt; i++) {
                        double u = (i + r.nextDouble()) / nt;
                        // (a broad barrel that flares a little where it meets the anvil)
                        double waist = (0.34 - 0.07 * Math.sin(Math.PI * Math.min(1, u * 1.15))) * (1 + 0.30 * smooth((u - 0.72) / 0.28));
                        double rad = sw * (0.105 + r.nextDouble() * 0.055) * (1 - 0.14 * u);
                        double dx = (r.nextDouble() * 2 - 1) * sw * waist * 0.8 + dirS * sw * 0.05 * (u - 0.4);
                        pl.add(new double[]{dx, base - rad - u * (sh - rad * 1.5), rad, 0, 0.08 + u * 0.55, 0});
                    }
                    // anvil, like the real skeleton: a row of flat puffs streaming far downwind and three smaller ones
                    // sheared back upwind (the painter fits its slab to them)
                    double top = base - sh;
                    for (int i = 0; i < 12; i++) {
                        boolean back = i >= 9;
                        double rad = sh * (back ? 0.085 - 0.026 * (i - 9) : 0.100 + r.nextDouble() * 0.022);
                        double dx = back ? -dirS * sw * (0.30 + 0.085 * (i - 9)) : dirS * sw * (0.06 + 0.112 * i);
                        pl.add(new double[]{dx, top + rad * 1.05 + r.nextDouble() * 5, rad, 0, 0.5, ROLE_ANVIL});
                    }
                    // overshooting dome above the updraft
                    double[][] dome = {{0.0, -0.62, 0.085}, {-0.050, -0.18, 0.066}, {0.055, -0.24, 0.060}};
                    for (double[] dm : dome) pl.add(new double[]{-dirS * sw * 0.05 + dm[0] * sw, top + dm[1] * sw * 0.085, dm[2] * sw, 0, 0.5, ROLE_DOME});
                    // flanking line: stepped cumulus towers on the rear flank, lower the further out they stand
                    for (int i = 0; i < 4; i++) {
                        double rad = sw * (0.084 - 0.013 * i);
                        double dx = -dirS * sw * (0.45 + 0.125 * i);
                        pl.add(new double[]{dx, base - rad, rad, 0, 0.3, ROLE_FLANK});
                        if (i < 3) pl.add(new double[]{dx + dirS * rad * 0.3, base - rad * (2.9 - 0.55 * i), rad * (0.86 - 0.1 * i), 0, 0.4, ROLE_FLANK});
                    }
                    // wall cloud: hangs below the base under the updraft (rear flank, upwind of the rain)
                    for (int i = 0; i < 5; i++) {
                        double rad = sw * 0.058 * (1 - 0.13 * Math.abs(i - 2));
                        pl.add(new double[]{-dirS * sw * MESO + (i - 2) * sw * 0.044, base + rad * (0.62 + 0.16 * (i % 2)), rad, 0, 0, ROLE_WALL});
                    }
                }
                case Model.CUMULUS -> {
                    int nb = 4 + r.nextInt(3);
                    for (int i = 0; i < nb; i++) {
                        double rad = sh * (0.20 + r.nextDouble() * 0.09);
                        double dx = ((i + 0.5) / nb - 0.5) * sw * 0.86 + r.nextGaussian() * sw * 0.03;
                        pl.add(new double[]{dx, base - rad, rad, 0, r.nextDouble() * 0.15});
                    }
                    int nh = 9 + r.nextInt(7);
                    double lean = r.nextGaussian() * 0.12;
                    for (int i = 0; i < nh; i++) {
                        double u = Math.pow(r.nextDouble(), 0.8);
                        double rad = sh * (0.19 + r.nextDouble() * 0.13) * (1 - 0.4 * u);
                        double dx = (r.nextDouble() * 2 - 1) * sw * 0.40 * (1 - Math.pow(u, 1.4)) + lean * sw * u;
                        pl.add(new double[]{dx, base - rad - u * (sh - rad * 1.2), rad, 0, 0.1 + u * 0.5});
                    }
                }
                case Model.CUMULONIMBUS -> {
                    int nb = 6 + r.nextInt(3);
                    for (int i = 0; i < nb; i++) {
                        double rad = sw * (0.11 + r.nextDouble() * 0.05);
                        double dx = ((i + 0.5) / nb - 0.5) * sw * 0.9 + r.nextGaussian() * sw * 0.02;
                        pl.add(new double[]{dx, base - rad, rad, 0, r.nextDouble() * 0.12});
                    }
                    int nt = 22 + r.nextInt(8);
                    for (int i = 0; i < nt; i++) {
                        double u = (i + r.nextDouble()) / nt;
                        double waist = 0.44 - 0.10 * Math.sin(Math.PI * Math.min(1, u * 1.15));
                        double rad = sw * (0.13 + r.nextDouble() * 0.07) * (1 - 0.25 * u);
                        double dx = (r.nextDouble() * 2 - 1) * sw * waist * 0.8;
                        pl.add(new double[]{dx, base - rad - u * (sh - rad * 1.5), rad, 0, 0.08 + u * 0.55});
                    }
                }
                case Model.CIRRUS -> {
                    int n = 8 + r.nextInt(5);
                    double slant = r.nextGaussian() * 0.12;
                    for (int i = 0; i < n; i++) {
                        double u = (i + 0.5) / n - 0.5;
                        double rad = sh * (0.26 + r.nextDouble() * 0.14);
                        pl.add(new double[]{u * sw * 0.9, u * sw * slant + r.nextGaussian() * sh * 0.1, rad, 0, r.nextDouble() * 0.4});
                    }
                }
                default -> { // sheets and rolls
                    boolean thick = kind == Model.NIMBOSTRATUS;
                    double rr = sh * (thick ? 0.36 : 0.5);
                    int n = (int) Math.max(8, sw / (rr * 0.9));
                    for (int i = 0; i < n; i++) {
                        double u = (i + 0.5) / n - 0.5;
                        double edge = 1 - 0.35 * Math.pow(Math.abs(u) * 2, 3);
                        double rad = rr * (0.85 + r.nextDouble() * 0.35) * edge;
                        pl.add(new double[]{u * sw * 0.94 + r.nextGaussian() * rr * 0.2, base - rad + r.nextGaussian() * sh * 0.05, rad, 0, r.nextDouble() * 0.3});
                        if (thick) {
                            double rad2 = rr * (0.8 + r.nextDouble() * 0.4) * edge;
                            pl.add(new double[]{u * sw * 0.88 + r.nextGaussian() * rr * 0.3, base - rad * 1.5 - rad2 * 0.6, rad2, 0, 0.2 + r.nextDouble() * 0.3});
                        }
                    }
                }
            }
            puffs = pl.toArray(new double[0][]);
            for (double[] p : puffs) p[3] = Model.clamp((p[1] + sh * 0.5) / sh, 0, 1);
            int g = (superc ? 204 : 226) + r.nextInt(24);
            tint = new int[]{g - r.nextInt(10), g - r.nextInt(7), Math.min(255, g + r.nextInt(8))};
            born = now;
            stageTimes.put("forming", now);

            // ---- v2.1 (these draws come last, so everything above is the cloud it always was)
            w0 = sw; h0 = sh;
            double baseY0 = this.y + sh * 0.5;
            // some cumulus tower into a cumulonimbus once in their life, if there is room above them
            boolean tw = kind == Model.CUMULUS && (forceTower || r.nextDouble() < 0.3) && baseY0 - 40 > sh * 1.7;
            fairKind = kind != Model.CUMULONIMBUS && kind != Model.NIMBOSTRATUS && !superc;
            double dv = r.nextDouble(), da = 0.14 + r.nextDouble() * 0.12;
            // cirrus / altocumulus / altostratus and the small ones stay fair; most rain-bearers are born fair and develop
            developAt = !fairKind ? 0 : forceTower ? 0.285 : !rains && !tw ? 9 : dv < 0.2 ? 0 : da;
            if (tw) {
                towers = true;
                rains = true;
                lifetime = forceTower ? 160 : Math.max(lifetime, 140 + r.nextDouble() * 50);
                morphAge = 0.30 * lifetime;      // right after it has grown up; it is precipitating by the end
                morphDur = 0.18 * lifetime;
                cbH = Math.min(380 + r.nextDouble() * 70, baseY0 - 40);
                cbW = sw * 1.2 + 80 + r.nextDouble() * 40;
                cbAnvil = 0.6 + r.nextDouble() * 0.4;
                List<double[]> tl = new ArrayList<>();
                int nb = 3 + r.nextInt(2);
                for (int i = 0; i < nb; i++) {       // a wider base
                    double rad = cbW * (0.10 + r.nextDouble() * 0.04);
                    double dx = ((i + 0.5) / nb - 0.5) * cbW * 0.9 + r.nextGaussian() * cbW * 0.02;
                    tl.add(new double[]{dx, rad, rad, 0, r.nextDouble() * 0.15});
                }
                int nt = 18 + r.nextInt(7);
                for (int i = 0; i < nt; i++) {       // the tower, bottom to top
                    double u = (i + r.nextDouble()) / nt;
                    double waist = 0.44 - 0.10 * Math.sin(Math.PI * Math.min(1, u * 1.15));
                    double rad = cbW * (0.13 + r.nextDouble() * 0.07) * (1 - 0.25 * u);
                    double dx = (r.nextDouble() * 2 - 1) * cbW * waist * 0.8;
                    tl.add(new double[]{dx, rad + u * (cbH - rad * 1.5), rad, 0, 0.1 + u * 0.6});
                }
                tower = tl.toArray(new double[0][]);
                cbTint = new int[]{tint[0] - 14, tint[1] - 11, tint[2] - 6};
            }
            wasFair = fair();
        }

        // ---- v2.1: fair weather, and the cumulus -> cumulonimbus change

        /** An ordinary fair-weather cloud that carries no rain (yet). */
        boolean fair() { return fairKind && frac() < developAt && !morphStarted && absorbedBy == null; }

        // ---- v2.3: merging

        /** Progress 0..1 of the newest merger (1 when it has absorbed nothing). */
        double mergeProg() { return absorbedParts.isEmpty() ? 1 : smooth((t - mergeT) / MERGE_S); }

        /** May this cloud take part in a merge right now? */
        boolean mergeable() {
            // (a supercell keeps to itself until it has had its tornado, so the show is not muddled by a merger)
            return absorbedBy == null && stageIdx >= 1 && stageIdx <= 3 && prog < 0.74 && form() > 0.7 && mergeProg() >= 1
                    && !(superc && (tornado != null || (tornadic && !tornadoDone)));
        }

        /** Absorb {@code b}: copies of its puffs are appended at r = 0 where they are now and swell with {@link #mergeProg}. */
        void absorb(FCloud b) {
            mergeDWDone += mergeDW;          // (the previous merger is complete: mergeable() checks it)
            int part = absorbedParts.size() + 1, n = b.puffCount(), room = 72 - puffCount();
            int step = Math.max(1, (int) Math.ceil(n / (double) Math.max(1, room)));
            double left = -w / 2, right = w / 2;
            for (int k = 0; k < n; k += step) {
                double[] q = b.puffNow(k);
                if (q[2] < 2.5) continue;
                double dx = b.x + q[0] - x, rad = q[2] * (step > 1 ? 1.12 : 1);
                extra.add(new double[]{dx, (b.y + q[1]) - (y + h * 0.5), rad, q[3], part, t, rng.nextDouble() * 0.25});
                left = Math.min(left, dx - rad); right = Math.max(right, dx + rad);
            }
            absorbedParts.add(new String[]{String.valueOf(b.id), b.type, b.name});
            mergeT = t;
            mergeDW = 2 * Math.max(-left, right) - w;
            capBonus += 0.35 * b.capacity;
            // the lifetime is extended, never shortened (prog does not move, so nothing jumps)
            double remA = (1 - prog) * lifetime, remB = (1 - b.prog) * b.lifetime;
            if (remB > remA) lifetime = Math.min(lifetime * 1.6, remB / Math.max(0.05, 1 - prog));
            if (fair() && !b.fair() && b.rains) { developAt = prog; rains = true; }   // a fair survivor that absorbs a rain cloud stops being fair
            b.absorbedBy = this;
            b.absorbT = t;
            b.relX = b.x - x; b.relY = b.y - y;
            boolean sheet = Model.sheetKind(Model.cloudKind(b.type)) && !Model.sheetKind(Model.cloudKind(type));
            events.add(String.format("t=%.1f %s absorbed %s (%s): %s", t, name, b.name, b.type,
                    sheet ? "its sheet now runs through it as a flat band" : "the two are merging into one cloud"));
        }

        // ---- v2.3: supercell (all smooth functions of the life progress)

        double rotation() { return !superc ? 0 : smooth((frac() - 0.16) / 0.20) * (1 - smooth((frac() - 0.76) / 0.14)); }

        double wall() { return !superc ? 0 : Math.max(Math.max(smooth((frac() - 0.34) / 0.13), wallBoost) * (1 - smooth((frac() - 0.73) / 0.10)), wallHold); }

        double overshoot() { return !superc ? 0 : smooth((frac() - 0.26) / 0.14) * (1 - smooth((frac() - 0.76) / 0.12)); }

        double hook() { return !superc ? 0 : smooth((frac() - 0.42) / 0.10) * (1 - smooth((frac() - 0.70) / 0.10)); }

        /** World x of the mesocyclone / wall cloud. */
        double mesoX() { return x - dirS * w0 * MESO * (0.7 + 0.3 * growth()); }

        /** How much of a puff with a role is there right now, 0..1. */
        double roleScale(double role) {
            if (role == ROLE_ANVIL) return smooth(anvil() / Math.max(1e-3, anvilMax));
            if (role == ROLE_DOME) return overshoot();
            if (role == ROLE_WALL) return wall();
            if (role == ROLE_FLANK) return smooth((frac() - 0.15) / 0.2);
            return 1;
        }

        /** Progress 0..1 of the change into a cumulonimbus. */
        double morph() { return !towers || age < morphAge ? 0 : smooth((age - morphAge) / morphDur); }

        /** How much of a cumulonimbus this cloud is, 0..1. */
        double cbShare() { return towers ? morph() : type.equals("cumulonimbus") ? 1 : 0; }

        /** Once per tick: fair -> developed, the start of the tower (type and name prefix swap), size gliding. */
        void tick(double now) {
            boolean fairNow = fair();
            if (wasFair && !fairNow && absorbedBy == null) events.add(String.format("t=%.1f %s developed: no longer fair weather", now, name));
            wasFair = fairNow;
            if (superc) {
                // a requested tornado lowers the wall cloud at once; the wall stays down while the funnel lives
                wallHold += ((tornado != null ? 1 : 0) - wallHold) * Math.min(1, DT * speed * (tornado != null ? 3 : 0.5));
            }
            if (!absorbedParts.isEmpty() && (!towers || !morphStarted)) w = w0 + mergeDWDone + mergeDW * mergeProg();   // the width glides to the combined cloud's
            if (!towers || age < morphAge) return;
            if (!morphStarted) {
                morphStarted = true;
                String old = name;
                type = "cumulonimbus";
                name = Model.CLOUD_PREFIX[Model.CUMULONIMBUS] + name.substring(name.indexOf('-'));
                events.add(String.format("t=%.1f %s is towering into a cumulonimbus: now %s", now, old, name));
            }
            double m = morph(), baseY0 = y + h * 0.5;    // the base stays where it is while the top climbs
            w = w0 + (cbW - w0) * m + mergeDWDone + mergeDW * mergeProg();
            h = h0 + (cbH - h0) * m;
            y = baseY0 - h * 0.5;
        }

        int[] tintNow() {
            if (!morphStarted) return tint;
            double m = morph();
            return new int[]{(int) Math.round(tint[0] + (cbTint[0] - tint[0]) * m), (int) Math.round(tint[1] + (cbTint[1] - tint[1]) * m),
                    (int) Math.round(tint[2] + (cbTint[2] - tint[2]) * m)};
        }

        /** Own puffs, then the tower puffs once it morphs, then whatever it absorbed: the list only ever grows at its end. */
        int ownCount() { return puffs.length + (morphStarted ? tower.length : 0); }

        int puffCount() { return ownCount() + extra.size(); }

        /** An appended tower / anvil puff at morph {@code m}: r is 0 at m = 0, it swells and rises into place. */
        double[] towerPuff(int k, double m, double baseOff, double shrink) {
            double[] p = tower[k];
            double grow = smooth((m * 1.6 - p[4]) / 0.6);
            double rad = p[2] * grow * shrink;
            double up = rad + (p[1] - p[2]) * (0.5 + 0.5 * grow);
            double hh = Math.max(1, h);
            return new double[]{p[0] * (0.75 + 0.25 * grow), baseOff - up, rad, Model.clamp(1 - up / hh, 0, 1)};
        }

        String signature() {
            return type + ":" + Math.round(w / 4) + ":" + Math.round(h / 4) + ":" + puffs.length + ":" + Math.round(puffs[0][0]);
        }

        double frac() { return Math.min(1, prog); }

        int stageForFrac() {
            double f = frac();
            if (f < 0.1) return 0;
            if (f < 0.3) return 1;
            if (!rains) return f < 0.82 ? 2 : 4;
            return f < 0.45 ? 2 : f < 0.82 ? 3 : 4;
        }

        void recomputeStageTimes() {
            double[] th = rains ? new double[]{0, 0.1, 0.3, 0.45, 0.82} : new double[]{0, 0.1, 0.3, 9, 0.82};
            stageTimes.clear();
            for (int k = 0; k < th.length; k++) if (frac() >= th[k]) stageTimes.put(Model.STAGES[k], born + th[k] * lifetime);
        }

        /** Condensation 0..1: rises smoothly out of haze, falls smoothly back to 0 before removal. */
        double form() {
            double f = frac();
            double v = Math.min(smooth(f / 0.24), 1 - smooth((f - 0.84) / 0.16));
            return absorbedBy == null ? v : v * (1 - smooth((t - absorbT) / ABSORB_S));   // crossfade into the survivor
        }

        /** Skeleton growth 0..1. */
        double growth() { return smooth(frac() / 0.32); }

        double anvil() {
            if (towers) return morphStarted ? cbAnvil * smooth((morph() - 0.55) / 0.45) : 0;   // only late in the change
            if (superc) return anvilMax * smooth((frac() - 0.20) / 0.20);
            return anvilMax * smooth((frac() - 0.3) / 0.22);
        }

        double shearTarget() { return Model.clamp(windBase * h * 0.022 * shearK, -w * 0.4, w * 0.4); }

        double base() { return y + h * 0.5; }

        double top() { return y + h * 0.5 - h * (0.35 + 0.65 * growth()); }

        double updraft() { return superc ? 6 + 5 * rotation() : towers ? 3.5 + 3.0 * morph() : type.equals("cumulonimbus") ? 6.5 : type.equals("cumulus") ? 3.5 : 1.2; }

        String precipType(double fz) {
            if (surfaceTemp <= 0.5) return "snow";
            if ((type.equals("cumulonimbus") || superc) && hail) return "graupel";
            if (base() < fz) return "mixed";
            return "rain";
        }

        double[] puffNow(int k) {
            double g = growth(), f = frac();
            double dis = smooth((f - 0.84) / 0.16);
            int own = ownCount();
            if (k >= own) {
                // an absorbed puff: r is 0 on the merge frame and swells as the merger proceeds
                double[] e = extra.get(k - own);
                double grow = Math.pow(smooth(smooth((t - e[5]) / MERGE_S) * 1.25 - e[6]), 0.62);
                return new double[]{e[0], h * 0.5 + e[1], e[2] * grow * (1 - 0.3 * dis), e[3]};
            }
            if (k >= puffs.length) return towerPuff(k - puffs.length, morph(), h * 0.5, 1 - 0.3 * dis);
            double[] p = puffs[k];
            double grow = smooth((0.3 + g * 1.4 - p[4] * 0.7) / 0.45);
            if (p.length > 5 && p[5] != 0) grow *= roleScale(p[5]);
            // heights are measured from the base (the skeleton was laid out for the birth height h0; the
            // origin sits h/2 above the base, and h grows while a cumulus towers)
            double baseY = h * 0.5, base0 = h0 * 0.5;
            double dy = baseY - (base0 - p[1]) * (0.45 + 0.55 * g);
            return new double[]{p[0] * (0.7 + 0.3 * g), dy, p[2] * grow * (1 - 0.3 * dis), p[3]};
        }

        double[] randomPuffPoint(Random r) {
            double[] p = puffNow(r.nextInt(puffCount()));
            double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * p[2] * 0.8;
            return new double[]{x + p[0] + Math.cos(a) * d, y + p[1] + Math.sin(a) * d};
        }

        void puffsJson(StringBuilder sb, boolean full) {
            sb.append('[');
            for (int k = 0, n = puffCount(), own = ownCount(); k < n; k++) {
                double[] p = !full ? puffNow(k) : k >= own ? new double[]{extra.get(k - own)[0], h0 * 0.5 + extra.get(k - own)[1], extra.get(k - own)[2], extra.get(k - own)[3]}
                        : k < puffs.length ? puffs[k] : towerPuff(k - puffs.length, morph(), h0 * 0.5, 1);
                if (k > 0) sb.append(',');
                sb.append('[');
                Json.num(sb, p[0]); sb.append(',');
                Json.num(sb, p[1]); sb.append(',');
                Json.num(sb, p[2]); sb.append(',');
                Json.num2(sb, p[3]); sb.append(']');
            }
            sb.append(']');
        }

        void json(StringBuilder sb, double fz) {
            double f = frac();
            // a fair cloud that has developed is a little thicker and darker than one that stayed fair
            double developed = fairKind && developAt < 1 ? 0.08 * smooth((f - developAt) / 0.08) : 0;
            double dark = Model.clamp(0.12 + water * (stageIdx == 3 && absorbedBy == null ? 0.65 : 0.3) + (type.equals("nimbostratus") ? 0.2 : 0)
                    + 0.15 * cbShare() + developed + (superc ? 0.16 * growth() : 0), 0, 1);
            if (Double.isNaN(darkShown) || lastDarkT > t) darkShown = dark;
            darkShown += (dark - darkShown) * Math.min(1, Math.max(0, t - lastDarkT) * 0.7);
            lastDarkT = t;
            dark = darkShown;
            int[] tint = tintNow();
            sb.append("{\"id\":").append(id).append(",\"seed\":").append(cseed).append(",\"name\":").append(Json.quote(name))
                    .append(",\"type\":\"").append(type).append('"');
            sb.append(",\"x\":"); Json.num(sb, x);
            sb.append(",\"y\":"); Json.num(sb, y);
            sb.append(",\"w\":"); Json.num(sb, w * (0.7 + 0.3 * growth()));
            sb.append(",\"h\":"); Json.num(sb, h * (0.45 + 0.55 * growth()));
            sb.append(",\"stage\":\"").append(Model.STAGES[stageIdx]).append('"');
            sb.append(",\"age\":"); Json.num(sb, age);
            sb.append(",\"lifetime\":"); Json.num(sb, lifetime);
            sb.append(",\"life_frac\":"); Json.num2(sb, f);
            sb.append(",\"form\":").append(Math.round(form() * 1000) / 1000.0);
            sb.append(",\"shear\":"); Json.num(sb, shear);
            sb.append(",\"anvil\":"); Json.num2(sb, anvil());
            sb.append(",\"water\":"); Json.num2(sb, water);
            sb.append(",\"ice\":"); Json.num2(sb, ice);
            sb.append(",\"updraft\":"); Json.num(sb, stageIdx <= 3 ? updraft() : 0);
            sb.append(",\"precip_type\":\"").append(rate > 0 ? precipType(fz) : "none").append('"');
            sb.append(",\"precip_rate\":"); Json.num2(sb, rate);
            sb.append(",\"total_precip_mm\":"); Json.num2(sb, totalPrecip);
            sb.append(",\"puffs\":"); puffsJson(sb, false);
            sb.append(",\"tint\":[").append(tint[0]).append(',').append(tint[1]).append(',').append(tint[2]).append(']');
            sb.append(",\"opacity\":"); Json.num2(sb, opacity);
            sb.append(",\"darkness\":"); Json.num2(sb, dark);
            sb.append(",\"fair\":").append(fair());
            if (morphStarted) sb.append(",\"from_type\":\"cumulus\",\"morph\":").append(Math.round(morph() * 100000) / 100000.0);
            // v2.3
            if (!absorbedParts.isEmpty()) {
                sb.append(",\"parts\":[");
                for (int i = 0; i < absorbedParts.size(); i++) {
                    String[] ap = absorbedParts.get(i);
                    if (i > 0) sb.append(',');
                    sb.append("{\"id\":").append(ap[0]).append(",\"type\":\"").append(ap[1]).append("\",\"name\":").append(Json.quote(ap[2])).append('}');
                }
                sb.append("],\"merge\":").append(Math.round(mergeProg() * 100000) / 100000.0).append(",\"puff_part\":[");
                for (int i = 0, n = puffCount(), own = ownCount(); i < n; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(i < own ? 0 : (int) extra.get(i - own)[4]);
                }
                sb.append(']');
            }
            if (absorbedBy != null) sb.append(",\"merging_into\":").append(absorbedBy.id);
            if (superc) {
                sb.append(",\"rotation\":"); Json.num2(sb, rotation());
                sb.append(",\"wall\":"); Json.num2(sb, wall());
                sb.append(",\"overshoot\":"); Json.num2(sb, overshoot());
                sb.append(",\"meso_x\":"); Json.num(sb, mesoX());
                sb.append(",\"hook\":"); Json.num2(sb, hook());
            }
            sb.append(",\"stage_times\":{");
            int k = 0;
            for (Map.Entry<String, Double> e : stageTimes.entrySet()) {
                if (k++ > 0) sb.append(',');
                sb.append('"').append(e.getKey()).append("\":");
                Json.num2(sb, e.getValue());
            }
            sb.append("}}");
        }

        String deathJson(double now, double fz) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"id\":").append(id).append(",\"seed\":").append(cseed).append(",\"name\":").append(Json.quote(name))
                    .append(",\"type\":\"").append(type).append('"');
            sb.append(",\"born\":"); Json.num2(sb, born);
            sb.append(",\"died\":"); Json.num2(sb, now);
            sb.append(",\"lifetime\":"); Json.num2(sb, now - born);
            sb.append(",\"peak_water\":"); Json.num2(sb, peakWater);
            sb.append(",\"total_precip_mm\":"); Json.num2(sb, totalPrecip);
            sb.append(",\"precip_type\":\"").append(totalPrecip > 0 ? precipType(fz) : "none").append('"');
            if (morphStarted) sb.append(",\"from_type\":\"cumulus\"");
            if (absorbedBy != null) sb.append(",\"merged_into\":").append(absorbedBy.id);
            int[] tint = tintNow();
            sb.append(",\"stage_times\":{");
            int k = 0;
            for (Map.Entry<String, Double> e : stageTimes.entrySet()) {
                if (k++ > 0) sb.append(',');
                sb.append('"').append(e.getKey()).append("\":");
                Json.num2(sb, e.getValue());
            }
            sb.append("},\"puffs\":");
            puffsJson(sb, true); // silhouette at full size for the life card
            sb.append(",\"tint\":[").append(tint[0]).append(',').append(tint[1]).append(',').append(tint[2]).append("]}");
            return sb.toString();
        }
    }

    private static double smooth(double x) {
        x = Model.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    // =====================================================================================
    // v2.3: climate

    private static final double ROLE_ANVIL = 1, ROLE_DOME = 2, ROLE_WALL = 3, ROLE_FLANK = 4;
    /** Where the mesocyclone sits: this share of the cloud's width behind (upwind of) its centre. */
    private static final double MESO = 0.20;

    /** Sim days since the world started (the showcase stage holds its clock still). */
    private double clockDays() { return scType != null ? 0 : (t - tStart) / DAY_S; }

    private double tod() { return frac(tod0 + clockDays() + hourShown); }

    private double yearFrac() { return frac((month0 - 1) / 12.0 + clockDays() / YEAR_DAYS + yearOffset); }

    /** This point of the year minus the annual mean: a sine, coldest in mid January. */
    private double seasonC(double yf) { return -climAmp * Math.cos(2 * Math.PI * (yf - 0.042)); }

    /** The day cycle -1..1: coldest just before sunrise (0.24), warmest mid-afternoon (0.62), never a step. */
    static double dayShape(double tod) {
        double ph = tod >= 0.24 && tod <= 0.62 ? (tod - 0.24) / 0.38 * 0.5 : 0.5 + frac(tod - 0.62) / 0.62 * 0.5;
        return -Math.cos(2 * Math.PI * ph);
    }

    private double gasC() { return 3.0 * Math.log(co2 / 420.0) / Math.log(2); }

    private String monthlyJson(double base) {
        StringBuilder sb = new StringBuilder("[");
        for (int k = 0; k < 12; k++) {
            if (k > 0) sb.append(',');
            sb.append(Math.round((base + seasonC((k + 0.5) / 12.0)) * 10) / 10.0);
        }
        return sb.append(']').toString();
    }

    /** Once per tick, also while paused: the surface temperature from its parts, then what the air does to the spawner. */
    private void updateClimate(double dt) {
        hourShown += (hourOffset - hourShown) * Math.min(1, dt * 2.4);
        if (Math.abs(hourOffset - hourShown) < 2e-5) hourShown = hourOffset;
        seasonShown += (seasonC(yearFrac()) - seasonShown) * Math.min(1, dt * 0.9);
        double tod = tod();
        diurnalNow = climDiurnal * 0.5 * dayShape(tod);
        surfaceTemp = climBase + seasonShown + diurnalNow + gasC() + userC;
        // derived: warm, humid air under a cold sky is unstable (more so by day); shear comes from the wind
        double warm = smooth((surfaceTemp + 4) / 28.0), sun = 0.5 + 0.5 * dayShape(tod), ws = Math.abs(windBase);
        instability = Model.clamp(0.05 + 0.55 * warm * (0.4 + 0.6 * sun) + 0.45 * (humidity - 0.45), 0, 1);
        shearIdx = Model.clamp(0.06 + ws / 11.5, 0, 1);
        // calm air spawns slowly, a moderate wind fastest, a gale tears small clouds apart again
        double windF = 0.35 + 0.65 * Math.exp(-Math.pow((ws - 6) / 5.5, 2));
        double f = windF * (0.25 + 1.25 * humidity) * (0.75 + 0.5 * aerosol) * (0.45 + 0.9 * warm) * (0.75 + 0.5 * instability);
        spawnPerMin = 12.0 * f * W / 3840.0;
        cloudTarget = Model.clamp(Math.round(W / 280.0 * Model.clamp(0.3 + 0.7 * f / 0.47, 0.3, 1.9)), 3, 60);
        // the next cloud's type: the base mix re-weighted (convection needs warmth and instability, sheets want cool
        // stable humid air, cirrus hardly cares, a supercell needs instability and shear)
        double conv = instability * (0.35 + 0.65 * warm), sum = 0;
        for (int k = 0; k < typeW.length; k++) {
            double m = switch (k) {
                case Model.CUMULUS -> 0.45 + 1.9 * conv;
                case Model.CUMULONIMBUS -> 0.10 + 5.5 * conv * conv + 1.2 * conv;
                case Model.SUPERCELL -> 0.02 + 46 * conv * conv * shearIdx * shearIdx;
                case Model.NIMBOSTRATUS, Model.STRATUS -> (0.35 + 1.5 * humidity) * (1.4 - 1.1 * instability);
                case Model.ALTOSTRATUS -> (0.5 + 1.0 * humidity) * (1.3 - 0.8 * instability);
                case Model.STRATOCUMULUS -> 0.7 + 0.6 * humidity;
                case Model.ALTOCUMULUS -> 0.8 + 0.4 * instability;
                default -> 1.0;
            };
            typeW[k] = BASE_MIX[k] * Math.max(0.01, m);
            sum += typeW[k];
        }
        for (int k = 0; k < typeW.length; k++) typeW[k] /= sum;
    }

    /** The type of the next spawned cloud, drawn from {@link #typeW}. */
    private String pickType() {
        double r = rng.nextDouble(), acc = 0;
        for (int k = 0; k < typeW.length; k++) {
            acc += typeW[k];
            if (r < acc) return Model.CLOUD_TYPES[k];
        }
        return "cumulus";
    }

    private void appendV23(StringBuilder sb) {
        sb.append(",\"climate\":{\"year_days\":").append(YEAR_DAYS).append(",\"day_length_s\":").append((int) DAY_S)
                .append(",\"hemisphere\":\"N\",\"lat\":47.4,\"monthly_temp_c\":").append(monthlyJson(climBase))
                .append(",\"diurnal_range_c\":").append(climDiurnal).append(",\"base_c\":").append(climBase)
                .append(",\"start_month\":").append(month0).append(",\"source\":\"").append(presetId != null ? "preset" : "generated").append("\"}");
        sb.append(",\"roads\":[");
        for (int k = 0; k < roads.size(); k++) {
            double[] r = roads.get(k);
            if (k > 0) sb.append(',');
            sb.append("{\"x0\":"); Json.num(sb, r[0]);
            sb.append(",\"x1\":"); Json.num(sb, r[1]);
            sb.append(",\"kind\":\"").append(r[2] > 0 ? "highway" : "street").append("\"}");
        }
        sb.append(']');
    }

    // =====================================================================================
    // v2.3: merging

    /** Two clouds that overlap enough (both condensed, neither dissipating) become one: the larger survives. */
    private void tryMerges() {
        if (scType != null) return;
        // scripted pairs (the scenes): merge when the two have drifted to within mergeDx of each other
        for (FCloud b : clouds) {
            FCloud a = b.mergeWith;
            if (a == null || b.absorbedBy != null) continue;
            if (!clouds.contains(a)) { b.mergeWith = null; continue; }
            if (Math.abs(b.x - a.x) <= b.mergeDx && a.mergeProg() >= 1 && a.absorbedParts.size() < 3) {
                b.mergeWith = null;
                b.drift = a.drift;
                a.absorb(b);
            }
        }
        if (scriptedMerges || t < nextMergeT || t - tStart < 8) return;
        for (int i = 0; i < clouds.size(); i++) {
            FCloud a = clouds.get(i);
            if (!a.mergeable()) continue;
            for (int j = i + 1; j < clouds.size(); j++) {
                FCloud b = clouds.get(j);
                if (!b.mergeable()) continue;
                double ox = Math.min(a.x + a.w * 0.45, b.x + b.w * 0.45) - Math.max(a.x - a.w * 0.45, b.x - b.w * 0.45);
                double oy = Math.min(a.base(), b.base()) - Math.max(a.top(), b.top());
                boolean sheetHeap = Model.sheetKind(Model.cloudKind(a.type)) != Model.sheetKind(Model.cloudKind(b.type));
                if (ox < 0.6 * Math.min(a.w, b.w) * 0.9 || oy < (sheetHeap ? 0.12 : 0.3) * Math.min(a.h, b.h)) continue;
                FCloud big = a.w * a.h >= b.w * b.h ? a : b, small = big == a ? b : a;
                // the survivor's puff list must stay append-only: a cumulus that is about to tower cannot absorb yet
                if ((big.towers && !big.morphStarted) || big.absorbedParts.size() >= 3 || big.puffCount() > 60) continue;
                big.absorb(small);
                nextMergeT = t + 20 + rng.nextDouble() * 18;
                return;
            }
        }
    }

    // =====================================================================================
    // v2.3: tornadoes

    /** Enhanced Fujita rating from the 3-second gust (m/s): EF0 from 29, EF1 38, EF2 50, EF3 61, EF4 75, EF5 90. */
    static int efOf(double windMs) {
        double[] th = {38, 50, 61, 75, 90};
        int ef = 0;
        for (double v : th) if (windMs >= v) ef++;
        return ef;
    }

    /** The {@code tornado} command: that supercell (or the one spinning hardest) produces one now. Ignored unless it is mature and has none. */
    private void requestTornado(long cloudId) {
        FCloud best = null;
        for (FCloud c : clouds) {
            if (!c.superc || c.tornado != null || c.absorbedBy != null || c.stageIdx < 2 || c.stageIdx > 3) continue;
            if (cloudId >= 0 ? c.id == cloudId : best == null || c.rotation() > best.rotation()) best = c;
        }
        if (best == null || best.rotation() < 0.5) return;
        startTornado(best);
    }

    private void startTornado(FCloud c) {
        FTornado tn = new FTornado(nextTornadoId++, c);
        tornadoes.add(tn);
        tornadoesTotal++;
        c.tornado = tn;
        c.tornadoDone = true;
        events.add(String.format("t=%.1f a funnel cloud is descending from the wall cloud of %s", t, c.name));
    }

    private void updateTornadoes(double dt) {
        for (FCloud c : clouds) {
            if (!c.superc || c.tornado != null || c.tornadoDone || !c.tornadic || c.absorbedBy != null) continue;
            if (c.frac() >= 0.50 && c.frac() < 0.60 && c.wall() > 0.9 && c.rotation() > 0.85) startTornado(c);
        }
        for (int k = tornadoes.size() - 1; k >= 0; k--) {
            FTornado tn = tornadoes.get(k);
            tn.update(dt);
            if (tn.done) {
                tornadoes.remove(k);
                tn.cloud.tornado = null;
                events.add(String.format("t=%.1f the tornado of %s is gone%s", t, tn.cloud.name,
                        Double.isNaN(tn.pathX0) ? " (it never touched down)" : String.format(": path %.0f px, peak EF%d", Math.abs(tn.x - tn.pathX0), tn.peakEf)));
            }
        }
    }

    private final class FTornado {
        final long id;
        final FCloud cloud;
        final double peak, tMature, leanDir, wMax;
        double age, phase, x, pathX0 = Double.NaN, reach, width, topWidth, lean, strength, debris, groundY, baseY;
        String stage = "funnel";
        int peakEf;
        boolean done, loggedTouch, loggedPeak, loggedRope;
        static final double T_FUNNEL = 7, T_TOUCH = 4, T_ROPE = 11;

        FTornado(long id, FCloud c) {
            this.id = id;
            this.cloud = c;
            Random r = new Random(c.cseed * 31 + id);
            final boolean hit = "hit".equals(scene);       // Phase O scene "hit": the measured hit - a 90 px funnel, about a minute on the ground
            double pk = 0.45 + r.nextDouble() * 0.45, tm = 20 + r.nextDouble() * 10;
            peak = hit ? 0.62 : pk;
            tMature = hit ? 58 : tm;
            leanDir = -c.dirS;                 // the top is carried downwind with the storm, the foot trails behind
            wMax = hit ? 90 : 28 + 44 * peak;
            x = c.mesoX();
            update(0);
        }

        double windMs() { return 22 + 78 * strength; }

        void update(double dt) {
            age += dt;
            double a = age, leanTo;
            if (a < T_FUNNEL) {
                stage = "funnel";
                double s = smooth(a / T_FUNNEL);
                reach = s;
                strength = 0.15 * s;
                width = 6 + 12 * s;
                leanTo = leanDir * 8;
            } else if (a < T_FUNNEL + T_TOUCH) {
                stage = "touchdown";
                double s = smooth((a - T_FUNNEL) / T_TOUCH);
                reach = 1;
                strength = 0.15 + (0.62 * peak - 0.15) * s;
                width = 18 + (0.9 * wMax - 18) * s;
                leanTo = leanDir * 12;
            } else if (a < T_FUNNEL + T_TOUCH + tMature) {
                stage = "mature";
                double s = (a - T_FUNNEL - T_TOUCH) / tMature, b = Math.sin(Math.PI * s);
                reach = 1;
                strength = peak * (0.62 + 0.38 * b);
                width = wMax * (0.9 + 0.1 * b);
                leanTo = leanDir * (14 + 10 * Math.sin(a * 0.33));
            } else {
                stage = "roping";
                double s = (a - T_FUNNEL - T_TOUCH - tMature) / T_ROPE, e = smooth(s);
                strength = peak * 0.62 * (1 - e);
                width = 0.9 * wMax * Math.pow(1 - e, 1.4);
                reach = 1 - 0.5 * smooth((s - 0.45) / 0.55);
                leanTo = leanDir * (24 + 150 * e);
                if (s >= 1 || width < 0.8) done = true;
            }
            lean += (leanTo - lean) * Math.min(1, dt * 1.6);
            topWidth = width * 2.2 + 36 * Math.min(1, width / 16.0);
            phase += dt * (5 + 9 * strength);
            // the foot follows the mesocyclone with a slow wander; while roping out it is left behind
            double target = cloud.mesoX() + 20 * Math.sin(a * 0.21 + id);
            x += (target - x) * Math.min(1, dt * (stage.equals("roping") ? 0.25 : 2.0));
            groundY = groundAt(Model.clamp(x, 0, W));
            baseY = cloud.base() + cloud.w0 * 0.058 * 1.25 * cloud.wall();   // the underside of the wall cloud
            boolean down = reach >= 0.995;
            debris += ((down ? 0.15 + 0.85 * strength / Math.max(0.2, peak) * Math.min(1, peak + 0.3) : 0) - debris) * Math.min(1, dt * 1.5);
            if (down && Double.isNaN(pathX0)) pathX0 = x;
            if (down) peakEf = Math.max(peakEf, efOf(windMs()));
            if (dt <= 0) return;
            if (down && !loggedTouch) {
                loggedTouch = true;
                events.add(String.format("t=%.1f tornado on the ground under %s", t, cloud.name));
            }
            if (stage.equals("roping") && !loggedRope) {
                loggedRope = true;
                events.add(String.format("t=%.1f the tornado of %s peaked at EF%d (%.0f m/s) and is roping out", t, cloud.name, peakEf, 22 + 78 * peak));
            }
            if (down && parts.size() < 5600) {
                // debris: thrown up around the foot (particle kind 7)
                double n = dt * 95 * debris;
                while (n > 0 && rng.nextDouble() < n) {
                    n -= 1;
                    P p = new P(7, x + rng.nextGaussian() * width * 0.55, groundY - rng.nextDouble() * 8, 1.4f + rng.nextFloat() * 3.2f);
                    p.vx = rng.nextGaussian() * (70 + 90 * strength);
                    p.vy = -(60 + rng.nextDouble() * 300 * (0.4 + strength));
                    p.life = 0.8 + rng.nextDouble() * 1.8;
                    p.spin = (float) (rng.nextGaussian() * 9);
                    p.rot = (float) (rng.nextDouble() * 6.28);
                    p.phase = x;
                    parts.add(p);
                }
            }
        }

        void json(StringBuilder sb) {
            sb.append("{\"id\":").append(id).append(",\"cloud\":").append(cloud.id).append(",\"stage\":\"").append(stage).append('"');
            sb.append(",\"x\":"); Json.num(sb, x);
            sb.append(",\"base_y\":"); Json.num(sb, baseY);
            sb.append(",\"ground_y\":"); Json.num(sb, groundY);
            sb.append(",\"reach\":").append(Math.round(reach * 10000) / 10000.0);
            sb.append(",\"width\":"); Json.num(sb, width);
            sb.append(",\"top_width\":"); Json.num(sb, topWidth);
            sb.append(",\"lean\":"); Json.num(sb, lean);
            sb.append(",\"strength\":").append(Math.round(strength * 1000) / 1000.0);
            sb.append(",\"ef\":").append(efOf(windMs()));
            sb.append(",\"wind_ms\":"); Json.num(sb, windMs());
            sb.append(",\"age\":"); Json.num2(sb, age);
            sb.append(",\"phase\":"); Json.num2(sb, phase);
            sb.append(",\"debris\":"); Json.num2(sb, debris);
            if (!Double.isNaN(pathX0)) { sb.append(",\"path_x0\":"); Json.num(sb, pathX0); }
            sb.append('}');
        }
    }

    // =====================================================================================
    // v2.3: cars

    private static final String[] CAR_VARIANTS = {"sedan", "sedan", "hatchback", "hatchback", "pickup", "van", "truck", "bus"};
    private static final int[][] CAR_COLORS = {{178, 34, 34}, {28, 78, 150}, {226, 226, 222}, {40, 44, 52}, {150, 156, 164}, {206, 168, 40},
            {36, 110, 78}, {124, 40, 110}, {214, 96, 36}, {88, 132, 176}};

    private double rainAt(double x) {
        double r = 0;
        for (FCloud c : clouds) if (c.rate > 0.04 && Math.abs(x - (c.superc ? c.x + c.dirS * c.w0 * 0.2 : c.x)) < c.w * (c.superc ? 0.22 : 0.38)) r = Math.max(r, c.rate);
        return r;
    }

    private boolean darkAt(double x) {
        for (FCloud c : clouds) if ((c.superc || c.type.equals("cumulonimbus") || c.type.equals("nimbostratus")) && c.form() > 0.6 && Math.abs(x - c.x) < c.w * 0.5) return true;
        return false;
    }

    private void updateCars(double dt) {
        boolean night = Math.cos(2 * Math.PI * tod()) > 0.05;       // the sun is down or low
        for (int k = cars.size() - 1; k >= 0; k--) {
            FCar c = cars.get(k);
            int gi = (int) Model.clamp(Math.round(c.x / GSTEP), 0, gWet.length - 1);
            double rain = rainAt(c.x);
            c.wet = gWet[gi];
            c.snow = gSnow[gi];
            c.depth = water == null ? 0 : water.depthAt(c.x);       // v2.4
            c.wipers = rain > 0.05 && !c.state.equals("wrecked");
            c.lights = (night || rain > 0.05 || c.snow > 0.3 || darkAt(c.x)) && !c.state.equals("wrecked");
            switch (c.state) {
                case "tossed" -> {
                    c.lift += c.vLift * dt;
                    c.vLift -= 300 * dt;
                    c.spin += c.vSpin * dt;
                    c.x = Model.clamp(c.x + c.vx * dt, 4, W - 4);
                    if (c.lift <= 0 && c.vLift < 0) {
                        c.lift = 0; c.state = "wrecked"; c.speed = 0; c.wreckT = t;
                        c.restSpin = Math.rint(c.spin / Math.PI) * Math.PI + (rng.nextDouble() - 0.5) * 0.5;
                        events.add(String.format("t=%.1f a %s was thrown by the tornado and wrecked", t, c.variant));
                    }
                }
                case "wrecked" -> {
                    c.spin += (c.restSpin - c.spin) * Math.min(1, dt * 5);      // it rocks to rest in half a second
                    if (t - c.wreckT > 150) cars.remove(k);
                }
                default -> {
                    double slope = (groundAt(c.x + 8) - groundAt(c.x - 8)) / 16.0;
                    double target = c.cruise * (1 - 0.4 * c.wet) * (1 - 0.55 * c.snow) * (1 - Math.min(0.6, Math.abs(slope) * 1.3));
                    // keep a gap to the car ahead
                    for (FCar o : cars) {
                        if (o == c || o.dir != c.dir || o.state.equals("tossed")) continue;
                        double gap = (o.x - c.x) * c.dir;
                        if (gap > 0 && gap < 78) target = Math.min(target, gap < 44 ? 0 : o.speed * 0.9);
                    }
                    if (c.wait > 0) { c.wait -= dt; target = 0; }
                    // v2.4: it slows as the water deepens and stands still when the water reaches the middle of its wheels
                    if (c.depth > 0) target *= Math.max(0, 1 - c.depth / (1.8 * wheelRadius(c.variant)));
                    c.speed += (target - c.speed) * Math.min(1, dt * 1.6);
                    c.state = c.speed < 1.5 ? "stopped" : "driving";
                    c.x += c.dir * c.speed * dt;
                    double x0 = c.road[0] + 14, x1 = c.road[1] - 14;
                    boolean hw = c.road[2] > 0;
                    if (hw && c.x < -40 && c.dir < 0) {           // left the world on the highway: another car drives in
                        cars.remove(k);
                        FCar n = new FCar(nextCarId++, rng, c.road);
                        n.x = -38; n.dir = 1;
                        cars.add(n);
                        continue;
                    }
                    if ((c.dir > 0 && c.x > x1) || (c.dir < 0 && c.x < x0 && !(hw && c.road[0] <= 0))) { c.dir = -c.dir; c.wait = 0.6 + rng.nextDouble(); c.speed = 0; }
                    // a tornado on the ground throws what it reaches
                    for (FTornado tn : tornadoes) {
                        if (tn.reach < 1 || Math.abs(c.x - tn.x) > tn.width * 0.55 + 20) continue;
                        c.state = "tossed";
                        c.vLift = 150 + 150 * tn.strength * (0.6 + 0.4 * rng.nextDouble());
                        c.vSpin = (rng.nextBoolean() ? 1 : -1) * (3.2 + rng.nextDouble() * 3.5);
                        c.vx = Math.signum(c.x - tn.x + 0.01) * (30 + rng.nextDouble() * 80);
                        break;
                    }
                }
            }
        }
    }

    private final class FCar {
        final long id;
        final String variant;
        final int[] color;
        final double[] road;
        final double cruise;
        double x, speed, lift, spin, vLift, vSpin, vx, wet, snow, wait, wreckT, restSpin, depth;
        int dir;
        boolean lights, wipers;
        String state = "driving";

        FCar(long id, Random r, double[] road) {
            this.id = id;
            this.road = road;
            variant = CAR_VARIANTS[r.nextInt(CAR_VARIANTS.length)];
            color = variant.equals("bus") ? new int[]{214, 152, 30} : variant.equals("truck") ? new int[]{196, 200, 204} : CAR_COLORS[r.nextInt(CAR_COLORS.length)];
            dir = r.nextBoolean() ? 1 : -1;
            cruise = (road[2] > 0 ? 46 : 30) * (0.8 + 0.4 * r.nextDouble()) * (variant.equals("truck") || variant.equals("bus") ? 0.8 : 1);
            speed = cruise;
        }

        void json(StringBuilder sb) {
            double cx = Model.clamp(x, 0, W), slope = (groundAt(Math.min(W, cx + 8)) - groundAt(Math.max(0, cx - 8))) / 16.0;
            sb.append("{\"id\":").append(id);
            sb.append(",\"x\":"); Json.num(sb, x);
            sb.append(",\"y\":"); Json.num(sb, groundAt(cx));
            sb.append(",\"angle\":").append(Math.round(Math.atan(slope) * 1000) / 1000.0);
            sb.append(",\"dir\":").append(dir);
            sb.append(",\"speed\":"); Json.num(sb, speed);
            sb.append(",\"variant\":\"").append(variant).append("\",\"color\":[").append(color[0]).append(',').append(color[1]).append(',').append(color[2]).append(']');
            sb.append(",\"lights\":").append(lights).append(",\"wipers\":").append(wipers);
            sb.append(",\"state\":\"").append(state).append('"');
            sb.append(",\"lift\":"); Json.num(sb, lift);
            sb.append(",\"spin\":").append(Math.round(spin * 1000) / 1000.0);
            sb.append(",\"wet\":"); Json.num2(sb, wet);
            sb.append(",\"snow\":"); Json.num2(sb, snow);
            if (depth >= 0.05) { sb.append(",\"depth\":"); Json.num(sb, depth); }      // v2.4 (left out at 0)
            sb.append('}');
        }
    }

    // =====================================================================================
    // v2.3: the supercell's lifetime tour (showcase) and the scripted scenes

    static final String TOUR_TYPE = "supercell";
    /** id, title, text. */
    static final String[][] TOUR = {
            {"towering", "Towering", "A cumulus tower climbs fast in warm, humid, unstable air. Winds that strengthen and turn with height tilt the updraft, "
                    + "so its rain will fall beside it instead of into it — the storm can keep feeding for hours."},
            {"mesocyclone", "Mesocyclone", "The wind shear rolls the air near the ground into a horizontal tube. The updraft tips that tube upright and the whole "
                    + "tower begins to turn: a mesocyclone, a rotating updraft a few kilometres across. It is what makes a storm a supercell."},
            {"mature", "Mature storm", "The updraft punches an overshooting top through the anvil, which streams far downwind. Rain and hail fall from the "
                    + "forward flank only; the base under the updraft stays dark, flat and rain-free."},
            {"wall_cloud", "Wall cloud", "Humid, rain-cooled air is drawn back into the updraft and condenses lower than the rest of the base: a wall cloud. "
                    + "When it rotates and lowers quickly, a tornado may be minutes away."},
            {"funnel", "Funnel", "The rear-flank downdraft wraps around the mesocyclone and stretches the rotation into a narrow, fast vortex. "
                    + "A funnel cloud descends from the wall cloud as the falling pressure inside it condenses the air."},
            {"tornado", "Tornado", "The vortex reaches the ground: a tornado. Dust and debris are thrown up around its foot. Afterwards the damage along its "
                    + "path is rated EF0 to EF5 from the wind it must have taken to cause it."},
            {"rope_out", "Rope-out", "Cold outflow cuts the tornado off from its warm inflow. The funnel thins, tilts and stretches into a rope, then breaks up — "
                    + "often still dangerous until the very end."},
            {"decay", "Decay", "With the updraft undercut the rotation fades, the wall cloud lifts and rain spreads under the whole base. "
                    + "The tower sinks back and the anvil drifts away as the storm dissipates."}};

    /** Which step of the lifetime tour the cloud on stage is at (null = the type has no tour). */
    private String tourStep(FCloud c) {
        if (!c.superc) return null;
        if (c.tornado != null) return switch (c.tornado.stage) { case "funnel" -> "funnel"; case "roping" -> "rope_out"; default -> "tornado"; };
        if (c.tornadoDone) return "decay";
        double f = c.frac();
        return f < 0.20 ? "towering" : f < 0.32 ? "mesocyclone" : f < 0.40 ? "mature" : f < 0.62 ? "wall_cloud" : "decay";
    }

    /** One scripted cloud: where, what, how far into its life, how long it lives. */
    private FCloud sceneCloud(String type, double x, double y, double prog, double life, double drift) {
        FCloud c = newCloud(x, y, type, false);
        c.lifetime = life;
        c.prog = prog;
        c.age = prog * life;
        c.drift = drift;
        c.towers = false;
        clouds.add(c);
        return c;
    }

    /** The worlds of {@code --fake-scene}: a few clouds placed so that one thing happens in plain view. */
    private void setupScene() {
        double k = H / 1080.0;
        scriptedMerges = true;
        switch (scene) {
            case "merge" -> {
                // a stratus sheet drifts into a fair-weather cumulus and is absorbed: its flat band then runs through the
                // cumulus. Further east two cumulus drift together into one wider cloud.
                windBase = 0.8;
                FCloud cu = sceneCloud("cumulus", W * 0.33, 400 * k, 0.40, 900, 0);
                for (int tries = 0; tries < 40 && (cu.w0 < 250 || cu.h0 < 165); tries++) { clouds.remove(cu); cu = sceneCloud("cumulus", W * 0.33, 400 * k, 0.40, 900, 0); }
                cu.rains = false; cu.developAt = 9;
                FCloud st = sceneCloud("stratus", cu.x - 640, cu.y + cu.h * 0.5 - 80, 0.42, 900, 42);
                for (int tries = 0; tries < 40 && (st.w0 < 760 || st.w0 * st.h0 > cu.w0 * cu.h0 * 1.2); tries++) { clouds.remove(st); st = sceneCloud("stratus", cu.x - 640, cu.y + cu.h * 0.5 - 80, 0.42, 900, 42); }
                st.rains = false; st.developAt = 9;
                st.mergeWith = cu; st.mergeDx = 24;
                FCloud a = sceneCloud("cumulus", W * 0.585, 380 * k, 0.40, 900, 0);
                a.rains = false; a.developAt = 9;
                FCloud b = sceneCloud("cumulus", a.x + a.w0 * 0.5 + 620, 392 * k, 0.42, 900, -26);     // (arrives a few seconds after the sheet has merged)
                b.rains = false; b.developAt = 9;
                b.mergeWith = a; b.mergeDx = a.w0 * 0.34 + b.w0 * 0.34;
                sceneCloud("cirrus", W * 0.47, 110 * k, 0.45, 900, 0);
                sceneCloud("altocumulus", W * 0.16, 215 * k, 0.45, 900, 0);
            }
            case "supercell" -> {
                // a supercell west of the village: wall cloud, then a tornado that crosses the houses, the street and the highway
                windBase = 3.2;
                FCloud sp = sceneCloud("supercell", W * 0.165, Double.NaN, 0.415, 250, 0);
                sp.tornadic = true;
                sceneCloud("cirrus", W * 0.52, 105 * k, 0.45, 900, 0);
                FCloud cu = sceneCloud("cumulus", W * 0.47, 410 * k, 0.4, 900, 0);
                cu.rains = false; cu.developAt = 9;
                sceneCloud("altocumulus", W * 0.62, 215 * k, 0.45, 900, 0);
            }
            case "cars" -> {
                // steady rain over the city: wet streets, headlights and wipers
                windBase = 1.6;
                FCloud ns = sceneCloud("nimbostratus", W * 0.50, Double.NaN, 0.50, 900, 0);
                ns.rains = true;
                FCloud ns2 = sceneCloud("nimbostratus", W * 0.26, Double.NaN, 0.52, 900, 0);
                ns2.rains = true;
                sceneCloud("stratocumulus", W * 0.70, 450 * k, 0.4, 900, 0);
                for (int i = 0; i < gWet.length; i++) {
                    double gx = i * GSTEP;
                    if (Math.abs(gx - ns.x) < ns.w0 * 0.4 || Math.abs(gx - ns2.x) < ns2.w0 * 0.4) gWet[i] = 0.75;
                }
            }
            case "hit" -> {
                // Phase O: a supercell walks its tornado along the village street at about 6 px/s (the storm's own drift plus
                // the wind); the funnel comes down just west of the first house and lifts about a minute later
                windBase = 2.0;
                FCloud sp = sceneCloud("supercell", W * 0.088, Double.NaN, 0.49, 420, 1.6);
                sp.tornadic = true;
                sp.x += W * 0.088 - 65 - sp.mesoX();
                sceneCloud("cirrus", W * 0.52, 105 * k, 0.45, 900, 0);
                sceneCloud("altocumulus", W * 0.62, 215 * k, 0.45, 900, 0);
            }
            case "flood" -> {
                // v2.4: an overcast sky over the two towns and the hollows. The rain here is not the clouds': it falls at a
                // set rate (the fake's rain command; by itself for a minute, from three seconds in)
                windBase = 0.6;
                double kx = Math.min(1, W / 3300.0);
                for (double cx : new double[]{560 * kx, 1440 * kx, 2250 * kx}) {
                    FCloud ns = sceneCloud("nimbostratus", cx, Double.NaN, 0.35, 3000, 0);
                    ns.rains = false;
                }
                sceneCloud("stratocumulus", 2300 * kx, 430 * k, 0.4, 3000, 0);
            }
            default -> scriptedMerges = false;
        }
    }
}
