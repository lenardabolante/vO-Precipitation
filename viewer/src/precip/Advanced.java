package precip;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings > Advanced: fourteen performance and look options, as one immutable value (stored as
 * {@code graphics.advanced} in settings.json). {@link #DEFAULT} is the game as it was before the
 * tab existed.
 * <p>
 * {@link #cur} is what {@code AppShell.applySettings} published last. {@code Renderer.prepareShared}
 * reads it once per frame into {@code Shared.adv}; the render bands and the painters only ever use
 * that snapshot, so every band of a frame draws with the same options. A change replaces the
 * instance (and bumps {@link #version}), which is how the baked caches notice it.
 */
final class Advanced {
    /** Share of the precipitation particles drawn, in percent. */
    static final int[] PARTICLES = {100, 75, 50, 25};
    static final String[] PARTICLE_NAMES = {"All", "75 %", "50 %", "25 %"};
    /** A cap on the render scale below the Graphics page's choice (0 = none); index = {@link #renderScale}. */
    static final double[] SCALES = {0, 0.75, 0.5};
    static final String[] SCALE_NAMES = {"Graphics page", "0.75×", "0.5×"};

    /** The switches, in the order of their bits; also their keys in settings.json. */
    static final int DETAIL_OFF = 0, GLOBAL = 1, BRANCH_OFF = 2, CULL = 3, SIMPLE = 4, NO_TINT = 5, NO_FLASH = 6, NO_SNOW = 7, NO_TRAILS = 8,
            NO_SHINE = 9, NO_PUDDLE = 10, GLINT_STAR = 11, FLAGS = 12;
    static final String[] KEYS = {"lightningDetailOff", "globalLightning", "branchingOff", "cullHidden", "simpleClouds", "noTint", "noFlash", "noSnow",
            "noTrails", "noShine", "noPuddleImpact", "glintStar"};
    /** The shapes of the rain glint (the {@link #GLINT_STAR} switch off / on), as the Advanced page and {@code --adv glint=} name them. */
    static final String[] GLINT_NAMES = {"Dot", "Star"};
    private static final int DEFAULT_BITS = 1 << CULL;

    /** Percent of the particles drawn: 100, 75, 50 or 25. */
    final int maxParticles;
    /** Index into {@link #SCALES}. */
    final int renderScale;
    private final int bits;
    /** #3: the lightning light has no shadows and the cloud does not glow from within. */
    final boolean lightningDetailOff;
    /** #4: every bolt, wherever it is, lights the whole view evenly. */
    final boolean globalLightning;
    /** #5: bolts are drawn without their side branches. */
    final boolean branchingOff;
    /** #6: skip what is hidden anyway (the only switch that is on by default; the picture is the same on and off). */
    final boolean cullHidden;
    /** #7: clouds as flat, evenly coloured shapes. */
    final boolean simpleClouds;
    /** #8: no colour casts from the time of day (the brightness still follows it). */
    final boolean noTint;
    /** #9: lightning lights nothing; the bolts are drawn as always. */
    final boolean noFlash;
    /** #10: nothing snow is drawn. */
    final boolean noSnow;
    /** #11: raindrops and sleet as dots, without their streaks. */
    final boolean noTrails;
    /** #12: no glints on the rain, no twinkling ice crystals, no sparkle on snow heaps. */
    final boolean noShine;
    /** #13: no ripples on ponds and puddles, no splashes. */
    final boolean noPuddleImpact;
    /** #14: the glint on the rain is the four-point star it used to be; off (the default) = a tiny bright dot. "No shining particles" removes it in both shapes. */
    final boolean glintStar;

    static final Advanced DEFAULT = new Advanced(100, 0, DEFAULT_BITS);

    /** The options every view draws with (never null). Set through {@link #publish}. */
    static volatile Advanced cur = DEFAULT;
    /** Counts the changes of {@link #cur}. */
    static volatile int version;

    private Advanced(int maxParticles, int renderScale, int bits) {
        this.maxParticles = maxParticles;
        this.renderScale = renderScale;
        this.bits = bits;
        lightningDetailOff = (bits >> DETAIL_OFF & 1) != 0;
        globalLightning = (bits >> GLOBAL & 1) != 0;
        branchingOff = (bits >> BRANCH_OFF & 1) != 0;
        cullHidden = (bits >> CULL & 1) != 0;
        simpleClouds = (bits >> SIMPLE & 1) != 0;
        noTint = (bits >> NO_TINT & 1) != 0;
        noFlash = (bits >> NO_FLASH & 1) != 0;
        noSnow = (bits >> NO_SNOW & 1) != 0;
        noTrails = (bits >> NO_TRAILS & 1) != 0;
        noShine = (bits >> NO_SHINE & 1) != 0;
        noPuddleImpact = (bits >> NO_PUDDLE & 1) != 0;
        glintStar = (bits >> GLINT_STAR & 1) != 0;
    }

    /** Make {@code a} the options in force. The instance (and the version) only changes when the value does. */
    static synchronized void publish(Advanced a) {
        if (a == null || a.equals(cur)) return;
        cur = a;
        version++;
    }

    boolean flag(int k) { return (bits >> k & 1) != 0; }

    Advanced with(int k, boolean on) { return new Advanced(maxParticles, renderScale, on ? bits | 1 << k : bits & ~(1 << k)); }

    Advanced toggle(int k) { return with(k, !flag(k)); }

    /** Any other value than 100, 75, 50 or 25 is taken as the nearest of them. */
    Advanced withParticles(double pct) {
        int best = PARTICLES[0];
        for (int p : PARTICLES) if (Math.abs(p - pct) < Math.abs(best - pct)) best = p;
        return new Advanced(best, renderScale, bits);
    }

    Advanced withRenderScale(int idx) { return new Advanced(maxParticles, Math.max(0, Math.min(SCALES.length - 1, idx)), bits); }

    /** Every option at the value that is not its default (pictures, tests). */
    static Advanced allOn() { return new Advanced(25, 2, (1 << FLAGS) - 1 & ~(1 << CULL)); }

    /** The index of {@link #maxParticles} in {@link #PARTICLES}. */
    int particleIndex() {
        for (int k = 0; k < PARTICLES.length; k++) if (PARTICLES[k] == maxParticles) return k;
        return 0;
    }

    /**
     * The render-scale cap in force: the Graphics page's value ({@code 0} = the display's own scale) lowered by this
     * one when it is set. The Graphics value stays an upper limit.
     */
    double capScale(double graphics) {
        double low = SCALES[renderScale];
        return low == 0 ? graphics : graphics == 0 ? low : Math.min(graphics, low);
    }

    // ---- settings.json

    /** Read {@code graphics.advanced}: a missing or unknown key keeps its default, a value out of range is clamped. */
    static Advanced read(Map<String, Object> m) {
        if (m == null) return DEFAULT;
        Advanced a = DEFAULT.withParticles(Json.num(m, "maxParticles", 100));
        double rs = Json.num(m, "renderScale", 0);
        a = a.withRenderScale(Double.isNaN(rs) ? 0 : (int) Math.round(Math.max(0, Math.min(SCALES.length - 1, rs))));
        for (int k = 0; k < FLAGS; k++) a = a.with(k, Json.bool(m, KEYS[k], a.flag(k)));
        return a.equals(DEFAULT) ? DEFAULT : a;
    }

    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxParticles", maxParticles);
        m.put("renderScale", renderScale);
        for (int k = 0; k < FLAGS; k++) m.put(KEYS[k], flag(k));
        return m;
    }

    // ---- the command line: --adv name[=value],...

    /**
     * {@code particles=50,render=0.5,noTrails,global,cullHidden=off,glint=star}: a switch is named by its settings key (or a
     * short form of it) and is switched on unless {@code =off} follows; {@code glint=dot|star} names the glint's shape;
     * {@code all} = {@link #allOn}.
     * @throws IllegalArgumentException for a name nobody knows
     */
    Advanced parse(String spec) {
        Advanced a = this;
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            String name = (eq < 0 ? p : p.substring(0, eq)).trim(), val = eq < 0 ? "" : p.substring(eq + 1).trim().toLowerCase();
            boolean on = !(val.equals("off") || val.equals("false") || val.equals("0") || val.equals("no"));
            String n = name.toLowerCase();
            switch (n) {
                case "all" -> a = allOn();
                case "particles", "maxparticles" -> a = a.withParticles(Double.parseDouble(val));
                case "render", "renderscale" -> {
                    double s = Double.parseDouble(val);
                    a = a.withRenderScale(s >= 1 || s <= 0 ? 0 : s > 0.62 ? 1 : 2);
                }
                case "glint" -> {
                    if (!val.equals("dot") && !val.equals("star")) throw new IllegalArgumentException("glint=" + val + " (dot or star)");
                    a = a.with(GLINT_STAR, val.equals("star"));
                }
                default -> {
                    int k = -1;
                    for (int i = 0; i < FLAGS; i++) if (KEYS[i].equalsIgnoreCase(name) || SHORT[i].equals(n)) k = i;
                    if (k < 0) throw new IllegalArgumentException("unknown option \"" + name + "\" (particles=, render=, glint=dot|star, all, " + String.join(", ", KEYS) + ")");
                    a = a.with(k, on);
                }
            }
        }
        return a;
    }

    private static final String[] SHORT = {"detailoff", "global", "nobranches", "cull", "simple", "notint", "noflash", "nosnow", "notrails", "noshine", "nopuddle", "star"};

    @Override public boolean equals(Object o) {
        return o instanceof Advanced a && a.maxParticles == maxParticles && a.renderScale == renderScale && a.bits == bits;
    }

    @Override public int hashCode() { return (maxParticles * 31 + renderScale) * 4099 + bits; }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("particles=" + maxParticles + ",render=" + (SCALES[renderScale] == 0 ? "1" : String.valueOf(SCALES[renderScale])));
        for (int k = 0; k < FLAGS; k++) if (flag(k) != DEFAULT.flag(k)) sb.append(',').append(KEYS[k]).append(flag(k) ? "" : "=off");
        return sb.toString();
    }
}
