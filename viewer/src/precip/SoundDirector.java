package precip;

import java.util.Random;

/**
 * What should be heard right now: one frame of the simulation and where the view is, in; a target gain for every bed
 * and the one-shots to start, out (PLAN Phase P, PC1-PC8).
 * <p>
 * A pure function of its input and the little it remembers between frames (the bolts already heard, the cars already
 * scheduled, which supercells have their siren on). It knows nothing about audio devices or the window, so every rule
 * can be checked on made-up frames ({@code SoundTest}). The listener is the centre column of the view; distances are
 * world px along x.
 * <p>
 * The gains written here already hold the five group volumes of the settings. The master volume and the mute are the
 * mixer's ({@link SoundMixer#setMaster}), so that they also reach a sound that is already playing.
 */
final class SoundDirector {
    // =====================================================================================
    // The model in numbers (PLAN Phase P). Every value is a starting value: the listening check (PE3) re-tunes them by ear.

    /** Earshot of rain and traffic: {@code R = max(EAR_MIN, EAR_SPAN x visible width)}. */
    static final double EAR_MIN = 700, EAR_SPAN = 0.9;
    /** "Seen from afar": everything is quietened by up to FAR_CUT once the view shows more than FAR_FROM of the world's width. */
    static final double FAR_CUT = 0.55, FAR_FROM = 0.55, FAR_OVER = 0.45;

    /** Rain: a cloud counts from this rate on; its level is {@code RAIN_BASE + (1 - RAIN_BASE) min(1, rate)}, times its form. */
    static final double RAIN_RATE_MIN = 0.02, RAIN_BASE = 0.25;
    /** How much of the rain's level each kind of precipitation gives. Snow only ever feeds the light bed (question 115). */
    static final double RAIN_SNOW = 0.35, RAIN_MIXED = 0.8, RAIN_GRAUPEL = 1.0;

    /** Thunder: a new bolt is heard when it is at least this bright; reach {@code max(THUNDER_R_MIN, THUNDER_R_SPAN x width)}. */
    static final double THUNDER_ALPHA = 0.7, THUNDER_R_MIN = 2400, THUNDER_R_SPAN = 2.2, THUNDER_POWER = 1.5;
    /** Its delay: THUNDER_DELAY plus a second per THUNDER_PX_PER_S of distance, at most THUNDER_DELAY_MAX more. */
    static final double THUNDER_DELAY = 0.15, THUNDER_PX_PER_S = 900, THUNDER_DELAY_MAX = 6;
    /** Low-pass from near to far, Hz; how long a bolt is remembered; how many thunders may sound at once. */
    static final double THUNDER_LP_NEAR = 16000, THUNDER_LP_FAR = 900, THUNDER_FORGET = 10;
    static final int THUNDER_MAX = 3;

    /** Cars: slower ones are not heard; they are scheduled within CAR_REACH x R, CAR_LEAD seconds before the file has to start. */
    static final double CAR_MIN_SPEED = 8, CAR_REACH = 0.6, CAR_LEAD = 0.5;
    /** A car that is already closer than the file's lead-in starts that far into the file, up to CAR_LATE seconds; beyond that it is not heard. */
    static final double CAR_LATE = 1.0;
    /** One pass per car in this many seconds, and how many may sound at once. */
    static final double CAR_AGAIN = 60;
    static final int CAR_MAX = 3;
    /** Traffic: {@code TRAFFIC_BASE + (1 - TRAFFIC_BASE)(1 - exp(-N / TRAFFIC_N))}, N = the driving cars in earshot; low-pass near to far. */
    static final double TRAFFIC_BASE = 0.35, TRAFFIC_N = 5, CAR_LP_NEAR = 14000, CAR_LP_FAR = 1500;

    /** Town ambience: reach {@code max(CITY_R_MIN, CITY_R_SPAN x width)}; both beds at CITY_LEVEL x the largest size in earshot. */
    static final double CITY_R_MIN = 600, CITY_R_SPAN = 0.7, CITY_LEVEL = 0.7, SIZE_CITY = 1.0, SIZE_TOWN = 0.6, SIZE_VILLAGE = 0, SIZE_HAMLET = 0;

    /** Siren: on from this lowering of the wall cloud, off below the second unless the cloud has a tornado; reach as thunder. */
    static final double SIREN_ON = 0.35, SIREN_OFF = 0.20, SIREN_R_MIN = 2400, SIREN_R_SPAN = 2.2;

    /** Tornado: heard from this reach on (the simulation's GROUND_REACH), full at 1; the weakest one on the ground gives TORNADO_BASE. */
    static final double TORNADO_REACH = 0.9, TORNADO_BASE = 0.55, TORNADO_R_MIN = 1800, TORNADO_R_SPAN = 1.6;
    /** Its two layers: the tornado file and the hurricane-storm file under it. */
    static final double TORNADO_LAYER = 1.0, TORNADO_STORM_LAYER = 0.6;

    /** Hurricane screen: {@code HURRICANE_BASE + (1 - HURRICANE_BASE) min(1, vmax / HURRICANE_FULL_MS)}. */
    static final double HURRICANE_BASE = 0.45, HURRICANE_FULL_MS = 70;

    /** Above this simulation speed no thunder and no car is started (question 121); the beds go on. */
    static final double SHOT_MAX_SPEED = 2;

    // =====================================================================================

    /** The looping sounds, in the order of {@link Mix#bed}. {@code HURRICANE} is the storm file: the tornado's lower layer, and the hurricane screen. */
    enum Bed {
        RAIN_LIGHT("rain_light"), RAIN_HEAVY("rain_heavy"), CITY_A("city_a"), CITY_B("city_b"), SIREN("siren"), TORNADO("tornado"), HURRICANE("hurricane");

        final String id;

        Bed(String id) { this.id = id; }
    }

    static final int BEDS = Bed.values().length;
    private static final int LIGHT = Bed.RAIN_LIGHT.ordinal(), HEAVY = Bed.RAIN_HEAVY.ordinal(), CITY_A = Bed.CITY_A.ordinal(), CITY_B = Bed.CITY_B.ordinal(),
            SIREN = Bed.SIREN.ordinal(), TORNADO = Bed.TORNADO.ordinal(), HURRICANE = Bed.HURRICANE.ordinal();

    /** What a frame is judged from. One instance is filled again for every frame. */
    static final class Input {
        Model.Hello hello;
        /** The sandbox / Cloud Formations frame; null = none yet. */
        Model.Frame frame;
        /** The hurricane screen's frame; null in every other mode. */
        HurricaneModel.Frame hurricane;
        /** {@code sandbox}, {@code showcase} or {@code hurricane}; anything else is silent. */
        String mode = "sandbox";
        /** The listener's world x and the visible width in world px. */
        double lx, span;
        /** Seconds since the previous frame. */
        double dt;
        /** The game is paused (also read from the frame itself), or a dialog is open over it. */
        boolean paused, blocked;
        /** The group volumes of the settings, 0..1. */
        double volRain = 1, volThunder = 1, volTraffic = 1, volTowns = 1, volStorm = 1;
    }

    /** A one-shot to start. */
    static final class Shot {
        /** The clip ({@code thunder_3}, {@code car_1}). */
        String id;
        /** Which of the variants it is, from 0. */
        int variant;
        /** Gain 0..1, low-pass in Hz, and seconds until it starts (negative: start that far into the file). */
        float gain, cutoff, delay;
    }

    /** The answer for one frame. The same instance is returned every time. */
    static final class Mix {
        /** False = nothing may be heard (no frame, paused, a dialog): every target is 0 and the mixer is to hold. */
        boolean open;
        /** Target gain per {@link Bed}, 0..1. */
        final float[] bed = new float[BEDS];
        /** Low-pass per bed in Hz; 0 = none. */
        final float[] cutoff = new float[BEDS];
        int shots;
        final Shot[] shot = new Shot[THUNDER_MAX + CAR_MAX];

        Mix() { for (int i = 0; i < shot.length; i++) shot[i] = new Shot(); }

        private void clear() {
            open = false;
            shots = 0;
            for (int i = 0; i < BEDS; i++) { bed[i] = 0; cutoff[i] = 0; }
        }
    }

    static final String[] THUNDER_IDS = ids("thunder_", SoundBank.THUNDERS), CAR_IDS = ids("car_", SoundBank.CARS);

    private static String[] ids(String prefix, int n) {
        String[] a = new String[n];
        for (int k = 0; k < n; k++) a[k] = prefix + (k + 1);
        return a;
    }

    private final double[] thunderSeconds, carSeconds, carPeak;
    private final double carPeakMax;
    private final Mix mix = new Mix();
    private final Random rng = new Random(0);
    private Model.Hello hello;
    /** Seconds of frames that could be heard (it stands still while paused, as the mixer's one-shots do). */
    private double clock;
    /** The next audible frame only remembers what is there (after a reset, a load, a pause): it starts nothing. */
    private boolean prime = true;
    private int stamp;

    // bolts already heard: Lightning.key and when each was last seen
    private long[] boltKey = new long[32];
    private double[] boltAt = new double[32];
    private int bolts;
    private final double[] thunderUntil = new double[THUNDER_MAX];
    private int lastThunder = -1;

    // cars: id, the variant chosen for the coming pass (-1 = none yet), when it last sounded, the frame it was last seen in
    private long[] carId = new long[64];
    private int[] carVariant = new int[64], carSeen = new int[64];
    private double[] carAt = new double[64];
    private int cars;
    private final double[] carUntil = new double[CAR_MAX];
    private int lastCar = -1;

    // supercells whose siren is on
    private long[] sirenId = new long[8];
    private int[] sirenSeen = new int[8];
    private int sirens;

    /** A director for clips of these lengths: the seven thunders, the five cars, and where each car file is loudest (NaN = unknown: the middle). */
    SoundDirector(double[] thunderSeconds, double[] carSeconds, double[] carPeak) {
        this.thunderSeconds = thunderSeconds.clone();
        this.carSeconds = carSeconds.clone();
        this.carPeak = new double[SoundBank.CARS];
        double mx = 0;
        for (int k = 0; k < SoundBank.CARS; k++) {
            double p = k < carPeak.length ? carPeak[k] : Double.NaN;
            this.carPeak[k] = Double.isNaN(p) ? carSeconds[k] * 0.5 : Model.clamp(p, 0, carSeconds[k]);
            mx = Math.max(mx, this.carPeak[k]);
        }
        carPeakMax = mx;
    }

    /** A director for the clips of a bank. */
    static SoundDirector of(SoundBank bank) {
        SoundBank.Clip[] th = bank.thunders(), ca = bank.cars();
        double[] ts = new double[th.length], cs = new double[ca.length], cp = new double[ca.length];
        for (int k = 0; k < th.length; k++) ts[k] = th[k].seconds();
        for (int k = 0; k < ca.length; k++) { cs[k] = ca[k].seconds(); cp[k] = ca[k].peak; }
        return new SoundDirector(ts, cs, cp);
    }

    /** Where car file {@code variant} is loudest, seconds from its start. */
    double carPeak(int variant) { return carPeak[variant]; }

    /** Forget everything (a game was left or begun). */
    void reset() {
        bolts = 0; cars = 0; sirens = 0;
        lastThunder = -1; lastCar = -1;
        clock = 0;
        prime = true;
        java.util.Arrays.fill(thunderUntil, 0);
        java.util.Arrays.fill(carUntil, 0);
        rng.setSeed(hello != null ? hello.seed : 0);
    }

    static double smooth(double t) {
        t = t < 0 ? 0 : t > 1 ? 1 : t;
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    /** The whole-view factor: 1 close up, {@code 1 - FAR_CUT} with the whole world in view (as {@link CloudShade#target} does). */
    static double far(double span, double worldW) {
        return 1 - FAR_CUT * smooth((span / Math.max(1, worldW) - FAR_FROM) / FAR_OVER);
    }

    /** One frame. Allocates nothing. */
    Mix step(Input in) {
        Mix m = mix;
        m.clear();
        if (in.hello != hello) {                               // another world: nothing of the old one counts
            hello = in.hello;
            reset();
        }
        boolean hur = "hurricane".equals(in.mode);
        boolean ok = !in.blocked && !in.paused;
        if (hur) ok &= in.hurricane != null && !in.hurricane.paused;
        else ok &= ("sandbox".equals(in.mode) || "showcase".equals(in.mode)) && in.hello != null && in.frame != null && !in.frame.paused;
        if (!ok) {
            prime = true;
            return m;
        }
        m.open = true;
        clock += Math.max(0, Math.min(0.25, in.dt));
        if (hur) {
            // the hurricane screen: the storm bed by the wind, and nothing else
            m.bed[HURRICANE] = (float) Model.clamp((HURRICANE_BASE + (1 - HURRICANE_BASE) * Model.clamp(in.hurricane.vmaxMs / HURRICANE_FULL_MS, 0, 1)) * in.volStorm, 0, 1);
            return m;
        }
        Model.Frame f = in.frame;
        double lx = in.lx, span = Math.max(1, in.span);
        double far = far(span, in.hello.w), R = Math.max(EAR_MIN, EAR_SPAN * span);
        stamp++;
        rain(f, lx, R, far * in.volRain, m);
        towns(in.hello, lx, span, far * in.volTowns, m);
        siren(f, lx, span, far * in.volStorm, m);
        tornado(f, lx, span, far * in.volStorm, m);
        boolean shots = !prime && f.speed <= SHOT_MAX_SPEED + 1e-9;
        thunder(f, lx, span, far * in.volThunder, shots, m);
        cars(f, lx, R, far * in.volTraffic, shots, m);
        prime = false;
        return m;
    }

    // ------------------------------------------------------------------ PC2: rain

    private static void rain(Model.Frame f, double lx, double R, double vol, Mix m) {
        double clearLight = 1, clearHeavy = 1;
        for (int ci = 0, cn = f.clouds.size(); ci < cn; ci++) {              // (indexed: a frame must not cost an iterator)
            Model.Cloud c = f.clouds.get(ci);
            if (c.fair || c.precipRate <= RAIN_RATE_MIN || !c.stage.equals("precipitating")) continue;
            String p = c.precipType;
            double type = p.equals("rain") ? 1 : p.equals("mixed") ? RAIN_MIXED : p.equals("graupel") ? RAIN_GRAUPEL : p.equals("snow") ? RAIN_SNOW : 0;
            if (type <= 0) continue;                           // "none" (and anything unknown) makes no sound
            // the footprint is the cloud's own, widened by its puffs (as CloudShade takes "under a cloud")
            double x0 = c.x - c.w / 2, x1 = c.x + c.w / 2;
            for (double[] q : c.puffs) { x0 = Math.min(x0, c.x + q[0] - q[2]); x1 = Math.max(x1, c.x + q[0] + q[2]); }
            double d = Math.max(0, Math.max(x0 - lx, lx - x1));
            if (d >= R) continue;
            double near = 1 - smooth(d / R), level = (RAIN_BASE + (1 - RAIN_BASE) * Math.min(1, c.precipRate)) * c.form;
            double part = Model.clamp(near * level * type, 0, 1);
            boolean heavy = c.kind == Model.CUMULUS || c.kind == Model.CUMULONIMBUS || c.kind == Model.NIMBOSTRATUS || c.kind == Model.SUPERCELL;
            if (heavy && !p.equals("snow")) clearHeavy *= 1 - part;
            else clearLight *= 1 - part;
        }
        m.bed[LIGHT] = (float) Model.clamp((1 - clearLight) * vol, 0, 1);
        m.bed[HEAVY] = (float) Model.clamp((1 - clearHeavy) * vol, 0, 1);
    }

    // ------------------------------------------------------------------ PC5: town ambience

    private static void towns(Model.Hello h, double lx, double span, double vol, Mix m) {
        double rc = Math.max(CITY_R_MIN, CITY_R_SPAN * span), g = 0;
        for (int si = 0, sn = h.settlements.size(); si < sn; si++) {
            Model.Settlement s = h.settlements.get(si);
            double size = switch (s.kind) { case "city" -> SIZE_CITY; case "town" -> SIZE_TOWN; case "village" -> SIZE_VILLAGE; default -> SIZE_HAMLET; };
            if (size <= 0) continue;
            double d = Math.max(0, Math.max(s.x0 - lx, lx - s.x1));
            if (d < rc) g = Math.max(g, size * (1 - smooth(d / rc)));
        }
        float both = (float) Model.clamp(CITY_LEVEL * g * vol, 0, 1);       // the two files overlap: the same level, at once
        m.bed[CITY_A] = both;
        m.bed[CITY_B] = both;
    }

    // ------------------------------------------------------------------ PC6: the siren

    private void siren(Model.Frame f, double lx, double span, double vol, Mix m) {
        double rs = Math.max(SIREN_R_MIN, SIREN_R_SPAN * span), g = 0;
        for (int ci = 0, cn = f.clouds.size(); ci < cn; ci++) {              // (indexed: a frame must not cost an iterator)
            Model.Cloud c = f.clouds.get(ci);
            if (c.kind != Model.SUPERCELL) continue;
            int at = -1;
            for (int k = 0; k < sirens; k++) if (sirenId[k] == c.id) { at = k; break; }
            boolean raining = c.stage.equals("precipitating") && c.precipRate > RAIN_RATE_MIN && !c.precipType.equals("none");
            boolean on = raining && (c.wall >= SIREN_ON || (at >= 0 && (c.wall >= SIREN_OFF || hasTornado(f, c.id))));
            if (!on) {
                if (at >= 0) { sirens--; sirenId[at] = sirenId[sirens]; sirenSeen[at] = sirenSeen[sirens]; }
                continue;
            }
            if (at < 0) {
                if (sirens == sirenId.length) { sirenId = java.util.Arrays.copyOf(sirenId, sirens * 2); sirenSeen = java.util.Arrays.copyOf(sirenSeen, sirens * 2); }
                at = sirens++;
                sirenId[at] = c.id;
            }
            sirenSeen[at] = stamp;
            double d = Math.abs((Double.isNaN(c.mesoX) ? c.x : c.mesoX) - lx);
            if (d < rs) g = Math.max(g, 1 - smooth(d / rs));
        }
        for (int k = sirens - 1; k >= 0; k--) if (sirenSeen[k] != stamp) { sirens--; sirenId[k] = sirenId[sirens]; sirenSeen[k] = sirenSeen[sirens]; }   // the cloud is gone
        m.bed[SIREN] = (float) Model.clamp(g * vol, 0, 1);
    }

    private static boolean hasTornado(Model.Frame f, long cloudId) {
        for (int k = 0, n = f.tornadoes.size(); k < n; k++) if (f.tornadoes.get(k).cloud == cloudId) return true;
        return false;
    }

    /** True while the siren of this cloud is latched on (tests). */
    boolean sirenOn(long cloudId) {
        for (int k = 0; k < sirens; k++) if (sirenId[k] == cloudId) return true;
        return false;
    }

    // ------------------------------------------------------------------ PC7: tornadoes

    private static void tornado(Model.Frame f, double lx, double span, double vol, Mix m) {
        if (f.tornadoes.isEmpty()) return;
        double rt = Math.max(TORNADO_R_MIN, TORNADO_R_SPAN * span), clear = 1, best = 0, bestD = rt;
        for (int ti = 0, tn = f.tornadoes.size(); ti < tn; ti++) {
            Model.Tornado t = f.tornadoes.get(ti);
            double ground = smooth((t.reach - TORNADO_REACH) / (1 - TORNADO_REACH));
            double d = Math.abs(t.x - lx);
            if (ground <= 0 || d >= rt) continue;              // a funnel still hanging, a roping one that has lifted, or out of earshot
            double part = ground * (TORNADO_BASE + (1 - TORNADO_BASE) * t.strength) * (1 - smooth(d / rt));
            clear *= 1 - Model.clamp(part, 0, 1);
            if (part > best) { best = part; bestD = d; }
        }
        double g = (1 - clear) * vol;
        if (g <= 0) return;
        float lp = (float) lerp(THUNDER_LP_NEAR, THUNDER_LP_FAR, smooth(bestD / rt));       // by the distance of the one heard most
        m.bed[TORNADO] = (float) Model.clamp(TORNADO_LAYER * g, 0, 1);
        m.bed[HURRICANE] = (float) Model.clamp(TORNADO_STORM_LAYER * g, 0, 1);
        m.cutoff[TORNADO] = lp;
        m.cutoff[HURRICANE] = lp;
    }

    // ------------------------------------------------------------------ PC3: thunder

    private void thunder(Model.Frame f, double lx, double span, double vol, boolean shots, Mix m) {
        double rt = Math.max(THUNDER_R_MIN, THUNDER_R_SPAN * span);
        for (int bi = 0, bn = f.lightning.size(); bi < bn; bi++) {
            Model.Bolt b = f.lightning.get(bi);
            if (b.pts == null || b.pts.length < 4) continue;
            long key = Lightning.key(b);
            int at = -1;
            for (int k = 0; k < bolts; k++) if (boltKey[k] == key) { at = k; break; }
            if (at >= 0) { boltAt[at] = clock; continue; }     // the same bolt as in an earlier frame
            if (bolts == boltKey.length) { boltKey = java.util.Arrays.copyOf(boltKey, bolts * 2); boltAt = java.util.Arrays.copyOf(boltAt, bolts * 2); }
            boltKey[bolts] = key;
            boltAt[bolts++] = clock;
            if (!shots || b.alpha < THUNDER_ALPHA) continue;   // remembered, not heard: the frame after a pause, a bolt already fading, fast forward
            int slot = -1;
            for (int k = 0; k < THUNDER_MAX; k++) if (thunderUntil[k] <= clock) { slot = k; break; }
            if (slot < 0 || m.shots >= m.shot.length) continue;
            double d = Math.abs(b.pts[b.pts.length - 2] - lx);  // the strike point is the last one
            double gain = Math.pow(1 - smooth(d / rt), THUNDER_POWER) * vol;
            if (gain < 0.004) continue;
            int v = rng.nextInt(SoundBank.THUNDERS - (lastThunder >= 0 ? 1 : 0));
            if (lastThunder >= 0 && v >= lastThunder) v++;     // never the one before
            lastThunder = v;
            double delay = THUNDER_DELAY + Math.min(THUNDER_DELAY_MAX, d / THUNDER_PX_PER_S);
            thunderUntil[slot] = clock + delay + thunderSeconds[v];
            Shot s = m.shot[m.shots++];
            s.id = THUNDER_IDS[v]; s.variant = v;
            s.gain = (float) Math.min(1, gain);
            s.delay = (float) delay;
            s.cutoff = (float) lerp(THUNDER_LP_NEAR, THUNDER_LP_FAR, smooth(d / rt));
        }
        for (int k = bolts - 1; k >= 0; k--) if (clock - boltAt[k] > THUNDER_FORGET) { bolts--; boltKey[k] = boltKey[bolts]; boltAt[k] = boltAt[bolts]; }
    }

    // ------------------------------------------------------------------ PC4: cars

    /**
     * A car file for a pass that is {@code T} seconds away: at random among those whose loudest moment can still land on the
     * crossing (a file with a long lead-in is no use for a fast car that is nearly here), never the one used before.
     */
    private int pickCar(double T) {
        int n = 0;
        for (int v = 0; v < SoundBank.CARS; v++) if (v != lastCar && carPeak[v] <= T + CAR_LATE) n++;
        if (n > 0) {
            int r = rng.nextInt(n);
            for (int v = 0; v < SoundBank.CARS; v++) if (v != lastCar && carPeak[v] <= T + CAR_LATE && r-- == 0) return v;
        }
        int best = -1;                                         // none fits: the one with the shortest lead-in
        for (int v = 0; v < SoundBank.CARS; v++) if (v != lastCar && (best < 0 || carPeak[v] < carPeak[best])) best = v;
        return best;
    }

    private void cars(Model.Frame f, double lx, double R, double vol, boolean shots, Mix m) {
        if (f.cars.isEmpty()) { cars = 0; return; }
        int n = 0;
        int total = f.cars.size();
        for (int k = 0; k < total; k++) {
            Model.Car c = f.cars.get(k);
            if (c.state.equals("driving") && Math.abs(c.x - lx) <= R) n++;
        }
        double traffic = (TRAFFIC_BASE + (1 - TRAFFIC_BASE) * (1 - Math.exp(-n / TRAFFIC_N))) * vol;       // more cars: louder (question 117)
        double speedUp = Math.max(0.05, f.speed);
        for (int ck = 0; ck < total; ck++) {
            Model.Car c = f.cars.get(ck);
            int at = -1;
            for (int k = 0; k < cars; k++) if (carId[k] == c.id) { at = k; break; }
            if (at >= 0) carSeen[at] = stamp;
            if (!c.state.equals("driving") || c.speed < CAR_MIN_SPEED) continue;
            double dx = lx - c.x, d = Math.abs(dx);
            if (dx * c.dir <= 0 || d > CAR_REACH * R) continue;                // driving away, or not near yet
            double T = d / (c.speed * speedUp);                                // real seconds until it passes the listener
            if (T > carPeakMax + CAR_LEAD) continue;
            if (at < 0) {
                if (cars == carId.length) {
                    carId = java.util.Arrays.copyOf(carId, cars * 2); carVariant = java.util.Arrays.copyOf(carVariant, cars * 2);
                    carSeen = java.util.Arrays.copyOf(carSeen, cars * 2); carAt = java.util.Arrays.copyOf(carAt, cars * 2);
                }
                at = cars++;
                carId[at] = c.id; carVariant[at] = -1; carAt[at] = Double.NEGATIVE_INFINITY; carSeen[at] = stamp;
            }
            if (clock - carAt[at] < CAR_AGAIN) continue;                       // it has had its pass
            if (!shots) continue;                                              // (the frame after a pause, fast forward: a file that still fits is chosen later)
            if (carVariant[at] < 0) carVariant[at] = lastCar = pickCar(T);
            int v = carVariant[at];
            double delay = T - carPeak[v];                                     // start so that the file's loudest moment lands on the crossing
            if (delay > CAR_LEAD) continue;                                    // not yet
            carAt[at] = clock;
            carVariant[at] = -1;
            if (delay < -CAR_LATE) continue;                                   // far too late for this file: let it go by
            int slot = -1;
            for (int k = 0; k < CAR_MAX; k++) if (carUntil[k] <= clock) { slot = k; break; }
            if (slot < 0 || m.shots >= m.shot.length || traffic < 0.004) continue;
            carUntil[slot] = clock + delay + carSeconds[v];
            Shot s = m.shot[m.shots++];
            s.id = CAR_IDS[v]; s.variant = v;
            s.gain = (float) Math.min(1, traffic);
            s.delay = (float) delay;
            s.cutoff = (float) lerp(CAR_LP_NEAR, CAR_LP_FAR, smooth(d / R));
        }
        for (int k = cars - 1; k >= 0; k--) if (carSeen[k] != stamp) {         // the car left the frame
            cars--;
            carId[k] = carId[cars]; carVariant[k] = carVariant[cars]; carSeen[k] = carSeen[cars]; carAt[k] = carAt[cars];
        }
    }
}
