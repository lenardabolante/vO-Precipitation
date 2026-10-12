package precip;

/** Immutable snapshot of everything the static-scenery painters need (safe to use on worker threads). */
final class Scene {
    final Model.Hello hello;
    final Model.Terrain terrain;
    final Model.Obj[] objects;
    final Model.Ground ground;
    final Lit lit;
    final double worldW, worldH, groundY, freezingY;
    final long seed;
    /** v2.3: the preset's biome when it changes the look; null = temperate, painted exactly as before. */
    final Preset.Biome biome;
    /** Settings > Advanced "No snow": no snow cover anywhere, not even above the freezing level. */
    final boolean noSnow;
    /** Per object: the coarse wind level the rain beads on a building's panes are smeared with ({@code BuildingPainter.windLevel}); null = calm everywhere. */
    final byte[] wind;

    Scene(Model.Hello hello, Model.Obj[] objects, Model.Ground ground, Lit lit, double freezingY) { this(hello, objects, ground, lit, freezingY, false); }

    Scene(Model.Hello hello, Model.Obj[] objects, Model.Ground ground, Lit lit, double freezingY, boolean noSnow) { this(hello, objects, ground, lit, freezingY, noSnow, null); }

    Scene(Model.Hello hello, Model.Obj[] objects, Model.Ground ground, Lit lit, double freezingY, boolean noSnow, byte[] wind) {
        this.noSnow = noSnow;
        this.wind = wind != null && wind.length == objects.length ? wind : null;
        this.hello = hello;
        this.terrain = hello.terrain;
        this.objects = objects;
        this.ground = ground;
        this.lit = lit;
        this.worldW = hello.w;
        this.worldH = hello.h;
        this.groundY = Math.max(hello.groundY, hello.terrain.maxY);
        this.freezingY = freezingY;
        this.seed = hello.seed;
        Preset.Biome b = hello.preset != null ? hello.preset.biome : null;
        this.biome = b != null && !b.plain() ? b : null;
    }

    /** Snow cover 0..1 of the terrain at x: what the sim reports, or the altitude above the freezing level. */
    double snowAt(double x, double y) {
        if (noSnow) return 0;
        double alt = Lit.smooth((freezingY - y) / 46.0);
        return Math.max(ground.snowAt(x), alt);
    }

    static double hash01(long n) {
        n ^= n >>> 33; n *= 0xff51afd7ed558ccdL; n ^= n >>> 33; n *= 0xc4ceb9fe1a85ec53L; n ^= n >>> 33;
        return (n >>> 11) / (double) (1L << 53);
    }

    /** Smooth 1D value noise 0..1. */
    static double noise1(double x, long seed) {
        long i = (long) Math.floor(x);
        double f = x - i;
        double a = hash01(i * 0x9E3779B97F4A7C15L + seed), b = hash01((i + 1) * 0x9E3779B97F4A7C15L + seed);
        double t = f * f * (3 - 2 * f);
        return a + (b - a) * t;
    }

    static double fbm1(double x, long seed, int oct) {
        double sum = 0, amp = 0.5, norm = 0;
        for (int k = 0; k < oct; k++) {
            sum += amp * noise1(x, seed + k * 7919L);
            norm += amp;
            x = x * 2.07 + 13.7;
            amp *= 0.5;
        }
        return sum / norm;
    }
}
