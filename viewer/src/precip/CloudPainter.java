package precip;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * Procedural, volumetric-style cloud renderer. One call turns a cloud's puff skeleton into a
 * premultiplied ARGB image:
 * <ol>
 *   <li>the skeleton becomes a smooth metaball thickness field (lobes merge into one body, no circles),
 *       leaned by {@code shear}, widened by an {@code anvil} slab for cumulonimbus;</li>
 *   <li>the field is eroded by domain-warped fractal noise seeded from the cloud's own seed (billowy
 *       cellular noise for heaped clouds, smooth stretched noise for sheets, ridged streaks for
 *       cirrus) that evolves slowly with time; {@code form} lowers the erosion and the threshold, so
 *       a cloud condenses from scattered translucent wisps into a solid body and thins out again;</li>
 *   <li>light comes from the real sun / moon direction: pseudo-normals of the thickness field shade
 *       every billow, a short march toward the light adds self-shadowing, the flat base is darker
 *       and bluer, thin edges facing the light get a bright rim;</li>
 *   <li>all of that is computed on a reduced grid; the final per-pixel pass interpolates the smooth
 *       fields, adds a last octave of fine detail and thresholds at full resolution, so edges stay
 *       crisp and anti-aliased at any zoom without grid or banding artefacts.</li>
 * </ol>
 * Rendering is pure (no shared state besides the caller's {@link Scratch}), so it runs on worker
 * threads; see {@link SharedClouds} for caching, scheduling and cross-fading.
 * <p>
 * <b>Phase C seam:</b> {@link #quality} (0 low, 1 medium, 2 high) may be set at any time.
 */
final class CloudPainter {
    private CloudPainter() {}

    /** 0 = low, 1 = medium, 2 = high: grid resolution, noise octaves and light-march steps. */
    static volatile int quality = 1;

    // per-kind character: cumulus, cumulonimbus, nimbostratus, stratocumulus, altostratus, altocumulus, stratus, cirrus, supercell
    private static final float[] BILLOW = {1.00f, 1.00f, 0.30f, 0.80f, 0.08f, 0.85f, 0.10f, 0.0f, 1.00f};
    private static final float[] ERODE = {1.40f, 1.30f, 0.36f, 0.70f, 0.32f, 0.70f, 0.30f, 0.72f, 1.30f};
    private static final float[] THRESH = {0.17f, 0.16f, 0.13f, 0.16f, 0.12f, 0.20f, 0.12f, 0.50f, 0.16f};
    private static final float[] SOFT = {0.075f, 0.085f, 0.30f, 0.15f, 0.42f, 0.15f, 0.38f, 0.60f, 0.085f};
    private static final float[] CONTRAST = {1.00f, 1.00f, 0.40f, 0.70f, 0.22f, 0.60f, 0.22f, 0.10f, 1.00f};
    private static final float[] BUMP = {0.92f, 0.92f, 0.55f, 0.95f, 0.30f, 0.95f, 0.30f, 0.0f, 0.92f};
    private static final float[] WARP = {0.26f, 0.26f, 0.40f, 0.28f, 0.45f, 0.22f, 0.45f, 0.30f, 0.26f};
    private static final float[] ANISO = {1.0f, 1.0f, 2.6f, 1.5f, 3.6f, 1.25f, 3.6f, 8.0f, 1.0f};
    private static final float[] RAG = {0.0f, 0.0f, 0.60f, 0.16f, 0.45f, 0.10f, 0.50f, 0.0f, 0.0f};
    private static final float[] BASE_EDGE = {0.060f, 0.055f, 0.26f, 0.10f, 0.30f, 0.12f, 0.28f, 0.0f, 0.050f};
    private static final float[] ALPHA_MAX = {0.99f, 1.00f, 0.97f, 0.96f, 0.82f, 0.93f, 0.90f, 0.66f, 1.00f};
    private static final float[] SIGMA = {0.30f, 0.42f, 0.34f, 0.32f, 0.18f, 0.26f, 0.20f, 0.05f, 0.46f};
    private static final float[] LAMBDA = {0.72f, 0.72f, 1.5f, 0.95f, 1.9f, 0.9f, 1.9f, 1.0f, 0.72f};
    /** Strength of the thin translucent fringe (veils and shreds just outside the body). */
    private static final float[] FRINGE = {1.00f, 1.00f, 0.70f, 0.85f, 0.60f, 0.80f, 0.65f, 0.55f, 1.00f};
    private static final float[] FINE = {0.80f, 0.80f, 0.40f, 0.65f, 0.30f, 0.65f, 0.30f, 0.10f, 0.80f};

    /** Direction and colours of the light on the clouds, derived from the time of day. */
    static final class Light {
        float lx = -0.5f, ly = -0.87f;          // unit vector toward the light (screen axes, y down)
        float sr = 1, sg = 0.97f, sb = 0.9f;    // direct light colour x intensity
        float tr = 0.62f, tg = 0.7f, tb = 0.84f; // ambient reaching cloud tops (sky)
        float br = 0.4f, bg = 0.45f, bb = 0.56f; // ambient reaching cloud bases
        float rim = 0.25f;                       // silver-lining strength
        double day = 1, dusk = 0;

        /** tod 0..1 (0.5 = noon), overcast 0..1. */
        static Light of(double tod, double overcast) { return of(tod, overcast, false); }

        /** @param flat Settings > Advanced "No tint": the colours of noon at this hour's brightness (no warm low sun, no dusk purple, no night blue) */
        static Light of(double tod, double overcast, boolean flat) {
            Light L = new Light();
            double e = -Math.cos(2 * Math.PI * tod);
            double day = sm((e + 0.12) / 0.5), dusk = Math.exp(-Math.pow(e / 0.22, 2));
            L.day = day;
            L.dusk = dusk;
            double ov = Model.clamp(overcast, 0, 1);
            // sun and moon ride the same arc the renderer draws them on
            double ps = (tod - 0.2) / 0.6, pm = (((tod + 0.5) % 1) - 0.2) / 0.6;
            double sunI = Math.min(1, day + 0.5 * dusk * (ps > 0 && ps < 1 ? 1 : 0.3)) * (1 - 0.45 * ov);
            double moonI = (pm >= 0 && pm <= 1 ? 1 : 0.4) * (1 - day) * 0.55 * (1 - 0.4 * ov);
            double a = Math.PI * Model.clamp(ps, 0.04, 0.96), b = Math.PI * Model.clamp(pm, 0.08, 0.92);
            double dx = sunI * -Math.cos(a) + moonI * 0.6 * -Math.cos(b);
            double dy = sunI * -Math.sin(a) + moonI * 0.6 * -Math.sin(b);
            dy = Math.min(dy, -0.16 * Math.hypot(dx, dy) - 1e-3);   // never lit from below
            double n = Math.hypot(dx, dy);
            if (n < 1e-6) { dx = -0.5; dy = -0.87; n = 1; }
            L.lx = (float) (dx / n);
            L.ly = (float) (dy / n);
            double w = flat ? 0 : Math.min(1, dusk * 1.05);  // warm low sun
            double sR = 1.0, sG = lerp(0.97, 0.58, w), sB = lerp(0.90, 0.30, w);
            double grey = 1 - 0.35 * ov;
            // (flat: the moon lights with the sun's colour, at the strength its own blue-white had)
            L.sr = (float) ((sR * sunI + (flat ? 0.70 * sR : 0.60) * moonI) * grey);
            L.sg = (float) ((sG * sunI + (flat ? 0.70 * sG : 0.68) * moonI) * grey);
            L.sb = (float) ((sB * sunI + (flat ? 0.70 * sB : 0.92) * moonI) * grey);
            double[] tDay = {0.64, 0.72, 0.86}, tDusk = {0.60, 0.48, 0.62}, tNight = {0.15, 0.18, 0.30};
            double[] bDay = {0.42, 0.47, 0.58}, bDusk = {0.36, 0.28, 0.38}, bNight = {0.08, 0.10, 0.18};
            double[] t = new double[3], bt = new double[3];
            if (flat) {
                // the day's sky colours, dimmed to what the night and the dusk colours add up to (their luma), hue unchanged
                double lt = luma(tNight) / luma(tDay), lb = luma(bNight) / luma(bDay), ld = luma(tDusk) / luma(tDay), lbd = luma(bDusk) / luma(bDay);
                for (int k = 0; k < 3; k++) {
                    tNight[k] = tDay[k] * lt; bNight[k] = bDay[k] * lb;
                    tDusk[k] = tDay[k] * ld; bDusk[k] = bDay[k] * lbd;
                }
            }
            for (int k = 0; k < 3; k++) {
                t[k] = lerp(lerp(tNight[k], tDay[k], day), tDusk[k], dusk * 0.7 * (0.35 + 0.65 * day));
                bt[k] = lerp(lerp(bNight[k], bDay[k], day), bDusk[k], dusk * 0.7 * (0.35 + 0.65 * day));
                double g = (t[0] + t[1] + t[2]) / 3;
                if (k == 2) {
                    for (int j = 0; j < 3; j++) {
                        t[j] = lerp(t[j], g * 0.92, ov * 0.6);
                        bt[j] = lerp(bt[j], (bt[0] + bt[1] + bt[2]) / 3 * 0.9, ov * 0.6);
                    }
                }
            }
            L.tr = (float) t[0]; L.tg = (float) t[1]; L.tb = (float) t[2];
            L.br = (float) bt[0]; L.bg = (float) bt[1]; L.bb = (float) bt[2];
            L.rim = (float) (0.22 + 0.6 * dusk);
            return L;
        }

        private static double luma(double[] c) { return c[0] * 0.30 + c[1] * 0.59 + c[2] * 0.11; }

        long key() {
            long h = 17;
            for (float v : new float[]{lx, ly, sr, sg, sb, tr, tg, tb, br, bg, bb, rim}) h = h * 31 + Math.round(v * 64);
            return h;
        }
    }

    /** Everything one render depends on. */
    static final class Spec {
        long seed;
        int kind;
        /**
         * v2.1 type change (cumulus -> cumulonimbus): the kind the cloud is growing out of, or -1.
         * While {@link #morph} is below 1 every per-kind constant is blended from this kind's value
         * to {@link #kind}'s, so the frame the type flips on looks exactly like the frame before it.
         */
        int fromKind = -1;
        /** Progress 0..1 of that change; ignored when {@link #fromKind} is -1. */
        double morph = 1;
        double[][] puffs = new double[0][];
        double form = 1, shear, anvil, darkness = 0.2, opacity = 1;
        int[] tint = {236, 238, 244};
        double time;            // seconds; the noise evolves slowly with it
        double wind;            // m/s, sign = drift direction of the fine structure
        Light light = new Light();
        double scale = 1;       // device pixels per world unit
        // rain shaft / virga under the cloud (optional)
        String precipType = "none";
        double precipRate, veilDrop, veilDrift;
        /** The cloud's previous render: its veil is reused when nothing about the veil changed. Not part of the signature. */
        Image reuse;
        /**
         * Settings > Advanced "Simple clouds": a tier below quality Low. The same silhouette from a much coarser grid, one even
         * colour instead of the lit and shaded relief, no fine detail, no fringe, no rim. Taken from the options in force when the
         * spec is made, so the signature and the render always agree.
         */
        boolean simple = Advanced.cur.simpleClouds;
        // ---- v2.3 merged clouds: which absorbed cloud each puff came from (0 = the cloud's own; null = no merge),
        // and the kind of every absorbed cloud. An absorbed flat layer is drawn as one straight slab through the
        // body instead of a row of lumps, so "a sheet merged into a cumulus" reads as a flat band through it.
        int[] puffPart;
        int[] partKind;
        // ---- v2.3 supercell: mesocyclone strength, wall cloud, overshooting top (0..1) and the wall cloud's x
        // relative to the cloud origin (NaN = unknown)
        double rotation, wall, overshoot, hook, mesoDx = Double.NaN;

        /** Take the merge and supercell state of a cloud. */
        void extrasOf(Model.Cloud c) {
            if (c.parts.length > 0 && c.puffPart != null && c.puffPart.length == c.puffs.length) {
                puffPart = c.puffPart;
                partKind = new int[c.parts.length];
                for (int k = 0; k < partKind.length; k++) partKind[k] = c.parts[k].kind;
            }
            if (c.kind == Model.SUPERCELL) {
                rotation = c.rotation; wall = c.wall; overshoot = c.overshoot; hook = c.hook;
                mesoDx = Double.isNaN(c.mesoX) ? Double.NaN : c.mesoX - c.x;
            }
        }

        boolean merged() { return puffPart != null && partKind != null && puffPart.length == puffs.length; }

        long signature() {
            long h = 1469598103934665603L;
            h = h * 1099511628211L + seed;
            h = h * 1099511628211L + kind;
            // a cloud in the middle of a type change is re-rendered as the blend moves (2 % steps);
            // one that is not morphing hashes exactly as it did before v2.1
            if (morphing()) h = h * 1099511628211L + (fromKind + 1) * 1009L + Math.round(morph * 50);
            h = h * 1099511628211L + Math.round(form * 200);
            h = h * 1099511628211L + Math.round(shear);
            h = h * 1099511628211L + Math.round(anvil * 100);
            h = h * 1099511628211L + Math.round(darkness * 50);
            h = h * 1099511628211L + Math.round(time * 1.5);   // the look is refreshed about every 2/3 s of sim time
            h = h * 1099511628211L + Double.doubleToLongBits(scale);
            h = h * 1099511628211L + light.key();
            h = h * 1099511628211L + quality;
            if (simple) h = h * 1099511628211L + 7;            // (only then: every other cloud hashes exactly as it did)
            h = h * 1099511628211L + tint[0] * 65536L + tint[1] * 256L + tint[2];
            h = h * 1099511628211L + precipType.hashCode() + Math.round(precipRate * 20) + Math.round(veilDrop / 4) * 31 + Math.round(veilDrift / 4) * 977;
            for (double[] p : puffs) {
                h = h * 1099511628211L + Math.round(p[0] * 2);
                h = h * 1099511628211L + Math.round(p[1] * 2);
                h = h * 1099511628211L + Math.round(p[2] * 4);
            }
            // v2.3: an unmerged, non-rotating cloud hashes exactly as it did before
            if (merged()) {
                for (int v : puffPart) h = h * 31 + v;
                for (int v : partKind) h = h * 131 + v;
            }
            if (kind == Model.SUPERCELL) {
                h = h * 1099511628211L + Math.round(rotation * 25) + Math.round(wall * 50) * 101 + Math.round(overshoot * 50) * 10007 + Math.round(hook * 25) * 1000003L;
                if (!Double.isNaN(mesoDx)) h = h * 1099511628211L + Math.round(mesoDx / 3);
            }
            return h;
        }

        boolean morphing() { return fromKind >= 0 && fromKind <= Model.LAST_KIND && fromKind != kind && morph < 1; }

        /** Take the type-change state of a cloud. */
        void morphOf(Model.Cloud c) {
            fromKind = c.fromKind;
            morph = c.fromKind < 0 ? 1 : c.morph;
        }
    }

    /** One per-kind constant, blended from the old kind toward the new one ({@code mk} = 1: just the new one). */
    private static float pk(float[] a, int from, int kind, float mk) {
        return mk >= 1f ? a[kind] : a[from] + (a[kind] - a[from]) * mk;
    }

    /** A finished render: the body and, for precipitating clouds, the veil hanging under it. */
    static final class Image {
        int[] px;               // premultiplied ARGB
        int w, h;
        BufferedImage img;
        double relX, relY;      // world offset of pixel (0,0) from the cloud origin
        double scale;           // image pixels per world unit
        int[] vpx;
        int vw, vh;
        BufferedImage veil;
        double vRelX, vRelY, vScale;
        long vSig;
        long signature;
        double baseY, topY;     // world offsets of the visual base and top
        /**
         * Settings > Advanced "Don't render what you can't see": which {@link #OCC_CELL} px cells of the body are opaque in every
         * pixel, as a summed-area table of (ocw + 1) x (och + 1) counts; null = not made yet. What is drawn before the cloud
         * and lies wholly behind such cells cannot show, so it need not be drawn ({@code Renderer.drawParticles}).
         */
        int[] opaqueSat;
        int ocw, och, opaqueCells;
        static final int OCC_CELL = 4;

        /** Make {@link #opaqueSat} (once; the pixels of a finished image never change). */
        void buildOpaque() {
            if (opaqueSat != null || px == null) return;
            int cw = w / OCC_CELL, ch = h / OCC_CELL, n = 0;
            int[] sat = new int[(cw + 1) * (ch + 1)];
            for (int cy = 0; cy < ch; cy++) {
                int run = 0;
                for (int cx = 0; cx < cw; cx++) {
                    boolean full = true;
                    for (int y = cy * OCC_CELL, ye = y + OCC_CELL; y < ye && full; y++) {
                        int o = y * w + cx * OCC_CELL;
                        for (int x = 0; x < OCC_CELL; x++) if ((px[o + x] >>> 24) != 255) { full = false; break; }
                    }
                    if (full) { run++; n++; }
                    sat[(cy + 1) * (cw + 1) + cx + 1] = sat[cy * (cw + 1) + cx + 1] + run;
                }
            }
            ocw = cw; och = ch; opaqueCells = n;
            opaqueSat = sat;
        }

        /** Is every pixel of the image rectangle [x0, x1] x [y0, y1] (inclusive, image pixels) inside the body and opaque? Conservative: a doubtful one is "no". */
        boolean opaque(int x0, int y0, int x1, int y1) {
            int[] sat = opaqueSat;
            if (sat == null || x0 < 0 || y0 < 0) return false;
            int c0 = x0 / OCC_CELL, r0 = y0 / OCC_CELL, c1 = x1 / OCC_CELL, r1 = y1 / OCC_CELL;
            if (c1 >= ocw || r1 >= och) return false;
            int s = ocw + 1;
            return sat[(r1 + 1) * s + c1 + 1] - sat[r0 * s + c1 + 1] - sat[(r1 + 1) * s + c0] + sat[r0 * s + c0] == (c1 - c0 + 1) * (r1 - r0 + 1);
        }
    }

    /** Per-thread work buffers. */
    static final class Scratch {
        float[] wx = new float[0], wy, s, an, v, rho, ld, la, hg;
        /** v2.3 planes, only allocated for merged clouds and supercells: absorbed-layer slab, wall cloud, absorbed heap. */
        float[] sh = new float[0], wl = new float[0], hp = new float[0];
        int[] xi = new int[0];
        float[] xf = new float[0];

        void ensure(int n) {
            if (wx.length >= n) return;
            int cap = Math.max(n, wx.length * 3 / 2);
            wx = new float[cap]; wy = new float[cap]; s = new float[cap]; an = new float[cap]; v = new float[cap];
            rho = new float[cap]; ld = new float[cap]; la = new float[cap]; hg = new float[cap];
        }

        void ensureExtra(int n) {
            if (sh.length >= n) return;
            int cap = Math.max(n, wx.length);
            sh = new float[cap]; wl = new float[cap]; hp = new float[cap];
        }
    }

    private static double sm(double x) {
        x = x < 0 ? 0 : (x > 1 ? 1 : x);
        return x * x * (3 - 2 * x);
    }

    private static float smf(float x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        return x * x * (3 - 2 * x);
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    /** Visual base offset (world units below the cloud origin) of a skeleton; used for rain shafts and labels. */
    static double baseOf(int kind, double[][] puffs) {
        double b = -1e9;
        double k = Model.heapKind(kind) ? 0.62 : 0.8;
        for (double[] p : puffs) if (p[2] > 0.3) b = Math.max(b, p[1] + k * p[2]);
        if (b == -1e9) return 0;
        return kind == Model.SUPERCELL ? coverLevel(puffs, null, k, b) : b;
    }

    /**
     * The rain-free base of a supercell: the lowest level that a good share of the lower body reaches.
     * The few small puffs hanging below it (the wall cloud) do not move it.
     * @param skip puffs to leave out (may be null)
     */
    private static double coverLevel(double[][] puffs, boolean[] skip, double k, double lowest) {
        double top = 1e9;
        for (double[] p : puffs) if (p[2] > 0.3) top = Math.min(top, p[1] - p[2]);
        double mid = lowest - (lowest - top) * 0.45, minX = 1e9, maxX = -1e9;
        int n = 0;
        for (int i = 0; i < puffs.length; i++) {
            double[] p = puffs[i];
            if (p[2] <= 0.3 || (skip != null && skip[i])) continue;
            n++;
            if (p[1] > mid) { minX = Math.min(minX, p[0] - p[2]); maxX = Math.max(maxX, p[0] + p[2]); }
        }
        if (n == 0 || maxX <= minX) return lowest;
        double need = 0.42 * (maxX - minX);
        double[][] bw = new double[n][];
        n = 0;
        for (int i = 0; i < puffs.length; i++) {
            double[] p = puffs[i];
            if (p[2] <= 0.3 || (skip != null && skip[i])) continue;
            bw[n++] = new double[]{p[1] + k * p[2], 2 * p[2]};
        }
        Arrays.sort(bw, (a, c) -> Double.compare(c[0], a[0]));
        double cover = 0;
        for (double[] e : bw) {
            cover += e[1];
            if (cover >= need) return e[0];
        }
        return lowest;
    }

    private static final byte P_OWN = 0, P_SHEET = 1, P_HEAP = 2, P_WALL = 3;

    /** Simple clouds: opacity against density 0..1 in 1024 steps, {@code (1 - exp(-3.4 rho)) * 1.0345}. */
    private static final float[] SIMPLE_ALPHA = new float[1025];
    static { for (int k = 0; k <= 1024; k++) SIMPLE_ALPHA[k] = (1f - (float) Math.exp(-3.4f * k / 1024f)) * 1.0345f; }

    /**
     * The full-resolution pass of a simple cloud: the edge value, the fringe strength and the two light planes are
     * interpolated from the grid and thresholded, with no fine detail, no wisps in the fringe (an even veil) and no rim.
     * A method of its own because {@link #render} is too long for the JIT to compile well: this loop is most of what a
     * simple cloud costs.
     * @param dr direct light x tint, per channel; ar: ambient weight x tint
     */
    private static void simpleFill(int[] dst, int iw, int ih, float y0, float inv, float gy0, float csf, int gw, int gh, int[] xi, float[] xf,
                                   float[] V, float[] AN, float[] LD, float[] LA, float aMax, float hazeA, float FR,
                                   float dr, float dg, float db, float ar, float ag, float ab, Light L) {
        final float tr = L.tr - L.br, tg = L.tg - L.bg, tb = L.tb - L.bb, br = L.br, bg = L.bg, bb = L.bb, iFR = 1f / FR, veil = hazeA * (0.25f + 0.75f * 0.62f);
        for (int j = 0; j < ih; j++) {
            float g = (y0 + (j + 0.5f) * inv - gy0) / csf - 0.5f;
            if (g < 0) g = 0;
            if (g > gh - 1.001f) g = gh - 1.001f;
            int yi = (int) g;
            float fy = g - yi;
            int r0 = yi * gw, r1 = r0 + gw, o = j * iw;
            for (int i = 0; i < iw; i++) {
                int c0 = r0 + xi[i], c1 = r1 + xi[i];
                float fx = xf[i];
                float v0 = V[c0] + (V[c0 + 1] - V[c0]) * fx, v1 = V[c1] + (V[c1 + 1] - V[c1]) * fx;
                float v = v0 + (v1 - v0) * fy;
                if (v <= -2.9f) continue;
                float haze = 0;
                if (v < 0.5f && v > -FR) {
                    float q0 = AN[c0] + (AN[c0 + 1] - AN[c0]) * fx, q1 = AN[c1] + (AN[c1 + 1] - AN[c1]) * fx;
                    float fr = q0 + (q1 - q0) * fy;
                    if (fr > 0.02f) {
                        float t = (v + FR) * iFR;
                        if (t > 1) t = 1;
                        haze = fr * veil * t * t;
                    }
                }
                float rho = v <= 0 ? 0 : (v >= 1 ? 1 : v * v * (3 - 2 * v));
                float alpha = aMax * SIMPLE_ALPHA[(int) (rho * 1024f)];
                alpha += (aMax - alpha) * haze;
                if (alpha < 0.004f) continue;
                if (alpha > 1) alpha = 1;
                float l0 = LD[c0] + (LD[c0 + 1] - LD[c0]) * fx, l1 = LD[c1] + (LD[c1 + 1] - LD[c1]) * fx;
                float ld = l0 + (l1 - l0) * fy;
                float a0 = LA[c0] + (LA[c0 + 1] - LA[c0]) * fx, a1 = LA[c1] + (LA[c1 + 1] - LA[c1]) * fx;
                float la = a0 + (a1 - a0) * fy;
                float r = dr * ld + (br + tr * la) * ar, gg = dg * ld + (bg + tg * la) * ag, b = db * ld + (bb + tb * la) * ab;
                if (r > 1) r = 1;
                if (gg > 1) gg = 1;
                if (b > 1) b = 1;
                float a255 = alpha * 255f;
                dst[o + i] = ((int) (a255 + 0.5f) << 24) | ((int) (r * a255 + 0.5f) << 16) | ((int) (gg * a255 + 0.5f) << 8) | (int) (b * a255 + 0.5f);
            }
        }
    }

    /** The flat base of a heap that has absorbed a layer: the layer's puffs do not count. */
    private static double baseOwn(int kind, double[][] puffs, boolean[] skip) {
        double b = -1e9;
        for (int i = 0; i < puffs.length; i++) if (!skip[i] && puffs[i][2] > 0.3) b = Math.max(b, puffs[i][1] + 0.62 * puffs[i][2]);
        if (b == -1e9) return baseOf(kind, puffs);
        return kind == Model.SUPERCELL ? coverLevel(puffs, skip, 0.62, b) : b;
    }

    /** Render one cloud. Returns null when there is nothing to draw. */
    static Image render(Spec sp, Scratch sc) {
        final int kind = Math.max(0, Math.min(Model.LAST_KIND, sp.kind));
        // type change in progress: fk is the kind it comes from, mk the eased blend toward `kind`
        // (mk = 1 and fk = kind for every ordinary cloud, which then renders exactly as before)
        final float mk = sp.morphing() ? (float) sm(sp.morph) : 1f;
        final int fk = mk < 1f ? sp.fromKind : kind;
        final int q = Math.max(0, Math.min(2, quality));
        final boolean simple = sp.simple;
        // ---- skeleton
        int np = 0;
        for (double[] p : sp.puffs) if (p[2] > 0.3) np++;
        if (np == 0 || sp.form <= 0.004) return null;
        float[] px = new float[np], py = new float[np], pr = new float[np];
        // v2.3: the class of every puff: OWN, an absorbed flat layer (drawn as a slab), an absorbed heap inside a
        // layered cloud (keeps its billows), or the wall cloud of a supercell (hangs below the base)
        final byte[] pcl = new byte[np];
        final int[] ppart = new int[np];
        final boolean heap = Model.heapKind(kind), superc = kind == Model.SUPERCELL;
        final boolean cirrus = kind == Model.CIRRUS;
        final boolean mergedSp = sp.merged();
        boolean anySheet = false, anyHeapPart = false;
        double minX = 1e9, maxX = -1e9, topY = 1e9, botY = -1e9, sumR = 0, sumR2 = 0;
        boolean[] skipBase = mergedSp ? new boolean[sp.puffs.length] : null;
        int k = 0;
        for (int idx = 0; idx < sp.puffs.length; idx++) {
            double[] p = sp.puffs[idx];
            if (p[2] <= 0.3) continue;
            px[k] = (float) p[0]; py[k] = (float) p[1]; pr[k] = (float) p[2];
            minX = Math.min(minX, p[0] - p[2]); maxX = Math.max(maxX, p[0] + p[2]);
            topY = Math.min(topY, p[1] - p[2]); botY = Math.max(botY, p[1] + p[2]);
            if (mergedSp) {
                int pk = sp.puffPart[idx];
                if (pk >= 1 && pk <= sp.partKind.length) {
                    int ptk = sp.partKind[pk - 1];
                    ppart[k] = pk;
                    if (Model.sheetKind(ptk) && !Model.sheetKind(kind) && !cirrus) { pcl[k] = P_SHEET; anySheet = true; skipBase[idx] = true; }
                    else if (Model.heapKind(ptk) && !heap && !cirrus) { pcl[k] = P_HEAP; anyHeapPart = true; }
                }
            }
            if (pcl[k] != P_SHEET) { sumR += p[2]; sumR2 += p[2] * p[2]; }
            k++;
        }
        if (sumR <= 0) { for (int i = 0; i < np; i++) { sumR += pr[i]; sumR2 += pr[i] * pr[i]; } }
        final float Rn = (float) Math.max(2, sumR2 / sumR);
        // the structural rules (heaped body, flat base, rolls, cirrus streaks) follow the current kind; the only
        // type change the protocol has, cumulus -> cumulonimbus, shares all of them, so nothing switches there
        // (an absorbed layer does not move a heap's base: the heap keeps its own flat underside)
        final double baseY = cirrus ? botY : anySheet && heap ? baseOwn(kind, sp.puffs, skipBase) : baseOf(kind, sp.puffs);
        boolean anyWall = false;
        if (superc) {
            for (int i = 0; i < np; i++) if (pcl[i] == P_OWN && py[i] + 0.62f * pr[i] > baseY + 2.5) { pcl[i] = P_WALL; anyWall = true; }
            // the body's top is the tower's, not the wall cloud's
            topY = 1e9;
            for (int i = 0; i < np; i++) if (pcl[i] != P_WALL) topY = Math.min(topY, py[i] - pr[i]);
            if (topY == 1e9) topY = baseY - Rn;
        }
        final boolean special = anySheet || anyHeapPart || anyWall;
        final double bodyW = maxX - minX, bodyCx = (minX + maxX) / 2;
        final float Hc = (float) Math.max(Rn, baseY - topY);
        final float fs = (float) sm(sp.form);
        final float shear = (float) sp.shear;
        final int windDir = Math.abs(shear) > 1 ? (shear > 0 ? 1 : -1) : (sp.wind >= 0 ? 1 : -1);

        // supercell: the storm's core is its tower (the lower two thirds of the body, without the wall cloud);
        // the anvil, the overshooting dome and the updraft striations are placed relative to it
        double coreMinX = minX, coreMaxX = maxX;
        if (superc) {
            double hi = topY + 0.24 * (baseY - topY), lo = topY + 0.74 * (baseY - topY);
            coreMinX = 1e9; coreMaxX = -1e9;
            for (int i = 0; i < np; i++) {
                if (pcl[i] == P_WALL || py[i] < hi || py[i] > lo) continue;
                coreMinX = Math.min(coreMinX, px[i] - pr[i]); coreMaxX = Math.max(coreMaxX, px[i] + pr[i]);
            }
            if (coreMaxX <= coreMinX) { coreMinX = minX; coreMaxX = maxX; }
        }
        final double coreW = coreMaxX - coreMinX, coreCx = (coreMinX + coreMaxX) / 2;
        final float updraftX = (float) Model.clamp(Double.isNaN(sp.mesoDx) ? coreCx - windDir * coreW * 0.12 : sp.mesoDx, coreMinX + coreW * 0.2, coreMaxX - coreW * 0.2);

        // anvil slab (cumulonimbus family): a wide, flat, fibrous cap that spreads downwind
        // (during a type change it also grows in with the blend, so it can never appear on the switch frame)
        final float anv = Model.anvilKind(kind) ? (float) sm(sp.anvil * 1.3) * (Model.anvilKind(fk) ? 1f : mk) : 0;
        float acx = 0, acy = 0, aw = 1, ah = 1;
        double anvTop = topY;
        if (anv > 0.02) {
            if (superc) {
                // a supercell's anvil is long: it streams far downwind and is sheared back a little way upwind. The
                // skeleton says how far: the slab spans the puffs of the top quarter (each counted as it swells, so
                // the span grows smoothly), and sits at the level most of them reach - the few puffs above that are
                // the overshooting dome, which stays a dome on top of the slab.
                double zone = topY + 0.24 * (baseY - topY), l = coreMinX, r = coreMaxX;
                int nz = 0;
                for (int i = 0; i < np; i++) {
                    if (pcl[i] == P_WALL || py[i] > zone) continue;
                    nz++;
                    double gs = sm(pr[i] / (0.045 * Hc));
                    l = Math.min(l, coreMinX + gs * (px[i] - pr[i] * 1.25 - coreMinX));
                    r = Math.max(r, coreMaxX + gs * (px[i] + pr[i] * 1.25 - coreMaxX));
                }
                aw = (float) ((r - l) * 0.5 * 1.03);
                acx = (float) ((l + r) * 0.5);
                ah = Hc * (0.095f + 0.065f * anv);
                if (nz > 0) {
                    double[][] tw = new double[nz][];
                    nz = 0;
                    for (int i = 0; i < np; i++) if (pcl[i] != P_WALL && py[i] <= zone) tw[nz++] = new double[]{py[i] - pr[i], 2 * pr[i]};
                    Arrays.sort(tw, (a, b2) -> Double.compare(a[0], b2[0]));
                    double cover = 0, need = 0.34 * (r - l);
                    for (double[] e : tw) {
                        cover += e[1];
                        anvTop = e[0];
                        if (cover >= need) break;
                    }
                }
            } else {
                aw = (float) (bodyW * (0.40 + 0.52 * anv));
                ah = Hc * (0.10f + 0.06f * anv);
                acx = (float) (bodyCx + windDir * anv * bodyW * 0.17);
            }
            acy = (float) (anvTop + ah * 0.85);
            minX = Math.min(minX, acx - aw); maxX = Math.max(maxX, acx + aw);
            topY = Math.min(topY, acy - ah);
        }
        // rear-flank downdraft (supercell `hook`): dry air wraps round the mesocyclone and scours a clear slot out of
        // the base just behind (upwind of) the wall cloud
        final float hookK = superc ? (float) sm(Model.clamp(sp.hook, 0, 1)) : 0f;
        final float slotX = (float) (updraftX - windDir * coreW * 0.30), slotY = (float) (baseY - Hc * 0.035), slotRx = (float) (coreW * 0.115), slotRy = Hc * 0.15f;

        // absorbed flat layers: one slab per part, fitted through its puffs (centre, slope, half-length, thickness)
        final int nParts = anySheet ? sp.partKind.length : 0;
        final float[] sbX = new float[nParts + 1], sbY = new float[nParts + 1], sbSl = new float[nParts + 1], sbHw = new float[nParts + 1], sbHh = new float[nParts + 1];
        if (anySheet) {
            double[] sw = new double[nParts + 1], sx = new double[nParts + 1], sy = new double[nParts + 1], sr2 = new double[nParts + 1];
            for (int i = 0; i < np; i++) {
                if (pcl[i] != P_SHEET) continue;
                int q0 = ppart[i];
                sw[q0] += pr[i]; sx[q0] += pr[i] * px[i]; sy[q0] += pr[i] * py[i]; sr2[q0] += pr[i] * pr[i];
            }
            for (int q0 = 1; q0 <= nParts; q0++) {
                if (sw[q0] <= 0) continue;
                double cx = sx[q0] / sw[q0], cy = sy[q0] / sw[q0], sxx = 0, sxy = 0, hw = 0;
                for (int i = 0; i < np; i++) {
                    if (pcl[i] != P_SHEET || ppart[i] != q0) continue;
                    double dx = px[i] - cx;
                    sxx += pr[i] * dx * dx; sxy += pr[i] * dx * (py[i] - cy);
                    hw = Math.max(hw, Math.abs(dx) + pr[i] * 1.1);
                }
                sbX[q0] = (float) cx; sbY[q0] = (float) cy;
                sbSl[q0] = sxx > 1 ? (float) Model.clamp(sxy / sxx, -0.35, 0.35) : 0;
                sbHw[q0] = (float) Math.max(1, hw);
                sbHh[q0] = (float) (sr2[q0] / sw[q0] * 0.98);
            }
        }

        // ---- grid
        final float warpA = pk(WARP, fk, kind, mk) * Rn;
        final float baseEdge = pk(BASE_EDGE, fk, kind, mk), erode = pk(ERODE, fk, kind, mk), billow = pk(BILLOW, fk, kind, mk);
        double m = Rn * (cirrus ? 1.7 : heap ? 0.8 : 0.5) + warpA;
        double x0 = minX - m + Math.min(0, shear), x1 = maxX + m + Math.max(0, shear);
        double y0 = topY - m, y1 = cirrus ? botY + m : baseY + Hc * (baseEdge * 1.6 + 0.05) + 2;
        if (special) y1 = Math.max(y1, botY + m * 0.8 + 2);   // a slab or a wall cloud may hang below the heap's base
        double bw = x1 - x0, bh = y1 - y0;
        double imgScale = sp.scale;
        double maxPx = 1_600_000.0;
        if (bw * bh * imgScale * imgScale > maxPx) imgScale = Math.sqrt(maxPx / (bw * bh));
        int iw = (int) Math.ceil(bw * imgScale) + 1, ih = (int) Math.ceil(bh * imgScale) + 1;
        if (iw < 3 || ih < 3) return null;
        double cellPx = simple ? 5.0 : q == 0 ? 3.4 : q == 1 ? 2.4 : 1.7;
        int cap = simple ? 6_000 : q == 0 ? 14_000 : q == 1 ? 34_000 : 80_000;
        double cs = cellPx / imgScale;                         // world units per grid cell
        if ((bw / cs) * (bh / cs) > cap) cs = Math.sqrt(bw * bh / cap);
        final int gw = (int) Math.ceil(bw / cs) + 2, gh = (int) Math.ceil(bh / cs) + 2;
        final int n = gw * gh;
        sc.ensure(n);
        final float[] WX = sc.wx, WY = sc.wy, S = sc.s, AN = sc.an, V = sc.v, RHO = sc.rho, LD = sc.ld, LA = sc.la, HG = sc.hg;
        if (special) sc.ensureExtra(n);
        final float[] SH = sc.sh, WL = sc.wl, HP = sc.hp;
        if (special) { Arrays.fill(SH, 0, n, 0f); Arrays.fill(WL, 0, n, 0f); Arrays.fill(HP, 0, n, 0f); }
        final float csf = (float) cs;
        final float gx0 = (float) (x0 - cs * 0.5), gy0 = (float) (y0 - cs * 0.5);   // centre of cell (0,0) = (gx0 + cs/2 ...)
        final int seed = (int) (sp.seed ^ (sp.seed >>> 32));
        final int oct = simple && !cirrus ? 2 : q == 0 || simple ? 3 : q == 1 ? 4 : 5;     // (cirrus is nothing but its streaks: it keeps the octaves of Low)

        // ---- warped sample positions (the warp field evolves with time: the body boils gently)
        final float fw = 1f / (Rn * 2.6f), tz = (float) (sp.time * 0.05);
        final float invH = 1f / Hc;
        for (int j = 0; j < gh; j++) {
            float y = gy0 + (j + 0.5f) * csf;
            float lean = (float) ((baseY - y) * invH);
            lean = lean < 0 ? 0 : (lean > 1.25f ? 1.25f : lean);
            int row = j * gw;
            for (int i = 0; i < gw; i++) {
                float x = gx0 + (i + 0.5f) * csf - shear * lean;
                WX[row + i] = x + warpA * Noise.perlin3(x * fw, y * fw, tz, seed + 11);
                WY[row + i] = y + warpA * Noise.perlin3(x * fw + 31.7f, y * fw - 17.3f, tz, seed + 23);
            }
        }
        Arrays.fill(S, 0, n, 0f);
        Arrays.fill(AN, 0, n, 0f);

        // ---- metaball thickness field: sum of soft kernels, so lobes merge into one body
        final float invRn = 1f / Rn;
        final float reach = warpA + Math.abs(shear) * 1.25f + csf;
        for (int p = 0; p < np; p++) {
            final byte cl = pcl[p];
            if (cl == P_SHEET) continue;                       // an absorbed layer is one slab, added below
            final boolean hk = heap || cl == P_HEAP;
            final float[] dst = cl == P_WALL ? WL : S;
            float R = pr[p] * (cirrus ? 2.6f : superc && cl == P_OWN ? 1.95f : hk ? 1.7f : 1.35f), R2 = R * R, wgt = pr[p] * invRn, iR2 = 1f / R2;
            int i0 = Math.max(0, (int) ((px[p] - R - reach - gx0) / csf)), i1 = Math.min(gw - 1, (int) ((px[p] + R + reach - gx0) / csf) + 1);
            int j0 = Math.max(0, (int) ((py[p] - R - warpA - csf - gy0) / csf)), j1 = Math.min(gh - 1, (int) ((py[p] + R + warpA + csf - gy0) / csf) + 1);
            for (int j = j0; j <= j1; j++) {
                int row = j * gw;
                for (int i = i0; i <= i1; i++) {
                    float dx = WX[row + i] - px[p], dy = WY[row + i] - py[p];
                    float d2 = dx * dx + dy * dy;
                    if (d2 >= R2) continue;
                    float t = 1f - d2 * iR2;
                    float a = hk ? wgt * t * t * t : wgt * t * t;
                    dst[row + i] += a;
                    if (cl == P_HEAP) HP[row + i] += a;
                }
            }
        }
        for (int q0 = 1; q0 <= nParts; q0++) {
            // an absorbed flat layer: a long super-ellipse slab along the line through its puffs. It is sampled
            // almost unwarped, so its top and bottom edges stay straight where the heap around it billows.
            final float hh = sbHh[q0], hw = sbHw[q0];
            if (hh <= 0.2f) continue;
            final float amp = 1.30f * smf(hh / (0.9f * csf)), cxs = sbX[q0], cys = sbY[q0], sl = sbSl[q0];
            final float ext = hh + Math.abs(sl) * hw + warpA * 0.4f + csf;
            int j0 = Math.max(0, (int) ((cys - ext - gy0) / csf)), j1 = Math.min(gh - 1, (int) ((cys + ext - gy0) / csf) + 1);
            int i0 = Math.max(0, (int) ((cxs - hw - reach - gx0) / csf)), i1 = Math.min(gw - 1, (int) ((cxs + hw + reach - gx0) / csf) + 1);
            for (int j = j0; j <= j1; j++) {
                int row = j * gw;
                float y = gy0 + (j + 0.5f) * csf;
                float lean = (float) ((baseY - y) * invH);
                lean = lean < 0 ? 0 : (lean > 1.25f ? 1.25f : lean);
                for (int i = i0; i <= i1; i++) {
                    float x = gx0 + (i + 0.5f) * csf - shear * lean;
                    float xs = x + 0.22f * (WX[row + i] - x), ys = y + 0.22f * (WY[row + i] - y);
                    float ex = (xs - cxs) / hw;
                    if (ex <= -1f || ex >= 1f) continue;
                    float ey = (ys - (cys + sl * (xs - cxs))) / hh;
                    float e2 = ex * ex, qs = e2 * e2 * e2 + ey * ey;
                    if (qs >= 1f) continue;
                    float t = 1f - qs;
                    SH[row + i] += amp * t * t * (0.5f + 0.5f * Math.min(1f, t * 3f));
                }
            }
        }
        if (anv > 0.02) {
            int j1 = Math.min(gh - 1, (int) ((acy + ah + warpA - gy0) / csf) + 2);
            for (int j = 0; j <= j1; j++) {
                int row = j * gw;
                for (int i = 0; i < gw; i++) {
                    float ex = (WX[row + i] - acx) / aw, ey = (WY[row + i] - acy) / ah;
                    float qq = ex * ex * ex * ex + ey * ey;   // flat, wide super-ellipse
                    if (qq >= 1) continue;
                    float t = 1 - qq;
                    float a = 1.15f * anv * t * t * (0.55f + 0.45f * Math.min(1, t * 3));
                    S[row + i] += a;
                    AN[row + i] = a / (S[row + i] + 1e-4f);
                }
            }
        }

        if (superc && anySheet) {
            // a layer swallowed by a storm is lost in its dark body: only what stands clear of the storm stays a
            // band of its own (inside, a smooth pale slab would show through the base like a lens)
            for (int c = 0; c < n; c++) if (SH[c] > 0) SH[c] *= 1f - smf((S[c] - 0.10f) / 0.50f);
        }

        // ---- erosion noise -> normalised edge value V (0..1 is the edge ramp), density and height
        final float lam = Rn * 0.95f * pk(LAMBDA, fk, kind, mk), invLam = 1f / lam;
        final float adv = (float) (sp.time * Math.signum(sp.wind) * Math.min(4, Math.abs(sp.wind)) * 0.35);
        final float aniso = pk(ANISO, fk, kind, mk);
        // a cloud only a few pixels across cannot carry deep erosion: it would break into hard chips
        final float tiny = (float) sm((Rn * imgScale - 4) / 12.0);
        final float Aform = (float) lerp(Math.max(1.9, erode * 1.5), erode, fs) * (0.45f + 0.55f * tiny), thr = (float) lerp(0.50, pk(THRESH, fk, kind, mk), fs);
        final float softF = (float) lerp(0.55, pk(SOFT, fk, kind, mk), fs);
        final float softMin = (float) (1.7 / (imgScale * Rn));          // the edge ramp is never thinner than ~1.7 px
        final float lgx = sp.light.lx, lgy = sp.light.ly, windSign = sp.wind >= 0 ? 1f : -1f;
        final float edgeK = billow, fringeK = pk(FRINGE, fk, kind, mk);
        final float dens = (float) lerp(0.30, 1.0, fs);
        final float bil0 = billow * (0.25f + 0.75f * fs);
        final float rag = pk(RAG, fk, kind, mk), bEdge = Math.max(0.02f, baseEdge) * Hc + 1.2f;
        final boolean rolls = kind == Model.STRATOCUMULUS || kind == Model.ALTOCUMULUS;
        final float rollLam = Hc * (kind == Model.ALTOCUMULUS ? 0.85f : 1.35f), rollBase = kind == Model.ALTOCUMULUS ? 0.30f : 0.58f;
        for (int j = 0; j < gh; j++) {
            int row = j * gw;
            float yCell = gy0 + (j + 0.5f) * csf;
            for (int i = 0; i < gw; i++) {
                int c = row + i;
                float sRaw = S[c];
                // v2.3: the absorbed-layer slab and the wall cloud are separate fields (neither is cut by the heap's base)
                final float shRaw = special ? SH[c] : 0f, wlRaw = special ? WL[c] : 0f;
                if (sRaw + shRaw + wlRaw < 0.012f) { V[c] = -4f; RHO[c] = 0; HG[c] = 0; AN[c] = 0; continue; }
                final float sAll = sRaw + shRaw + wlRaw;
                final float shr = shRaw > 0 ? shRaw / sAll : 0f;          // how much of this cell is the absorbed layer
                final float hps = special && HP[c] > 0 ? Math.min(1f, HP[c] / sAll) : 0f;   // ... the absorbed heap
                float wx = WX[c] - adv, wy = WY[c];
                float sC = sRaw / (1f + 0.6f * sRaw) * 1.6f;
                float hf = (float) ((baseY - wy) * invH);        // 0 at the base, 1 at the top
                float nv, soft = softF, A = Aform, lobe = 1f, hn, fringe = fringeK * 0.6f;
                if (cirrus) {
                    // fibrous streaks: strongly anisotropic ridged noise along the wind, with hooked tails
                    float yy = wy + 0.22f * Hc * (float) Math.sin(wx / (Hc * 2.4f) + (seed & 255) * 0.05f);
                    float st = Noise.fbm2(wx / (Hc * 4.2f), yy / (Hc * 0.26f), seed + 3, oct);
                    float rd = 1f - Math.abs(st) * 2.3f;
                    if (rd < 0) rd = 0;
                    float fine = 0.5f + 0.5f * Noise.fbm2(wx / (Hc * 1.6f), yy / (Hc * 0.13f), seed + 5, 3);
                    float patch = 0.5f + 0.5f * Noise.fbm2(wx / (Hc * 4.5f), wy / (Hc * 1.6f), seed + 9, 2);
                    nv = (0.62f * rd + 0.38f * fine) * (0.45f + 1.0f * patch);
                    if (nv > 1) nv = 1;
                    sC = Math.min(1f, sC * 0.9f) * (0.30f + nv);
                    nv = 1;
                    hn = 0.5f;
                } else {
                    float af = AN[c];
                    float u = wx * invLam, v = wy * invLam;
                    float an2 = aniso + af * 4.5f + shr * 3.4f;
                    float nw = 0.5f + 0.5f * Noise.fbm2(u * 0.55f / an2, v * 0.55f, seed + 19, oct);
                    nw = (nw - 0.24f) * 1.9f;
                    nw = nw < 0 ? 0 : (nw > 1 ? 1 : nw);
                    float bil = bil0 * (1f - 0.9f * af);
                    if (special) {
                        bil *= 1f - 0.95f * shr;                                    // a layer has no cauliflower
                        if (hps > 0) bil += ((0.25f + 0.75f * fs) - bil) * hps;     // a heap inside a layer keeps its own
                    }
                    if (bil > 0.02f) {
                        // two scales of billows: big cauliflower lobes carrying smaller bumps
                        float big = Noise.billow2(u * 0.40f + 7.7f, v * 0.40f - 3.1f, seed + 31, 2), small = Noise.billow2(u, v, seed, oct);
                        float nb = 0.50f * big + 0.50f * small;
                        nb = (nb - 0.32f) * 2.0f;
                        nb = nb < 0 ? 0 : (nb > 1 ? 1 : nb);
                        float npn = 0.5f + 0.5f * Noise.fbm2(u * 1.7f, v * 1.7f, seed + 7, oct - 1);
                        nv = nw + (nb * 0.84f + npn * 0.16f - nw) * bil;
                        // shading follows the lobes: the smooth dome of the big billows is the relief,
                        // and the gaps between domes are the crevices where shadow gathers
                        float dome = 0.80f * big + 0.20f * small;
                        hn = nw + (dome - nw) * bil;
                        lobe = 1f - bil + bil * smf(big * 1.55f + 0.08f);
                    } else { nv = nw; hn = nw; }
                    // which way does this stretch of rim face? crisp toward the light, soft and veiled away from it
                    int il = i > 0 ? c - 1 : c, ir = i < gw - 1 ? c + 1 : c, iu = j > 0 ? c - gw : c, id = j < gh - 1 ? c + gw : c;
                    float gxs = S[ir] - S[il], gys = S[id] - S[iu];
                    float gl = (float) Math.sqrt(gxs * gxs + gys * gys) + 1e-5f;
                    float shade = smf(0.5f + (gxs * lgx + gys * lgy) / gl * 0.62f);      // 0 = rim faces the sun, 1 = faces away
                    float lee = smf(0.15f - gxs * windSign / gl * 0.85f);                  // 1 = downwind side
                    float low = heap ? 1f - smf(hf / 0.24f) : 0f;                           // 1 at the base of a heap cloud
                    soft *= (0.50f + 1.7f * nw * nw * nw + af * 2.2f) * (1.0f + edgeK * (2.2f * shade + 0.9f * lee) + 2.2f * low);
                    fringe = fringeK * Math.min(1f, 0.22f + 0.62f * shade + 0.38f * lee + 0.12f * low + 0.5f * af);
                    if (heap) A *= 0.30f + 0.70f * smf(hf / 0.22f);   // little erosion along the base: it stays a flat line
                    if (special) {
                        if (shr > 0) {
                            // the absorbed layer: barely eroded and evenly soft, so its edges are two straight lines
                            A += (Aform * 0.10f - A) * shr;
                            soft += (softF * 2.1f + 0.05f - soft) * shr;
                            fringe += (fringeK * 0.5f - fringe) * shr;
                        }
                        if (hps > 0) A += (Aform * 2.4f - A) * hps * 0.8f;
                    }
                    if (rolls) {
                        float roll = 1f - Noise.worley2(wx / rollLam + 3.3f, wy / (rollLam * 0.9f) + 1.7f, seed + 41) * 1.15f;
                        sC *= rollBase + (1f - rollBase) * smf(roll * 1.5f);
                    }
                    if (rag > 0) A += rag * smf(1f - hf * 1.7f);
                    // flat (or ragged) base
                    float by = (float) baseY + (heap ? 0.012f : 0.16f) * Hc * Noise.perlin2(wx / (Rn * (heap ? 2.4f : 1.3f)), 0.37f, seed + 53);
                    float cut = 1f - smf((wy - (by - bEdge)) / (2 * bEdge));
                    sC *= cut;
                    if (hookK > 0.03f) {
                        float ex = (wx + adv - slotX) / slotRx, ey = (wy - slotY) / slotRy, qh = ex * ex + ey * ey;
                        if (qh < 1f) { float th = 1f - qh; sC *= 1f - 0.86f * hookK * th * th * (3f - 2f * th); }
                    }
                    if (special) {
                        // what hangs through or below the heap's base keeps its own shape
                        float extra = shRaw / (1f + 0.6f * shRaw) * 1.6f + wlRaw / (1f + 0.6f * wlRaw) * 1.6f * (0.55f + 0.45f * nw);
                        sC += extra;
                        fringe *= 1f - smf((wy - by) / (bEdge * 1.3f)) * (1f - Math.min(1f, extra * 2f));
                    } else
                    fringe *= 1f - smf((wy - by) / (bEdge * 1.3f));       // only a thin haze hangs under the base
                }
                // erosion bites the rim hard (deep cauliflower lobes) but never holes the thick interior
                float depth = 1f - smf((sC - 0.62f) * 1.5f);
                if (soft < softMin) soft = softMin;
                float vv = (sC - A * (1f - fs * 0.88f * (1f - depth)) * (1f - nv) - thr) / soft;
                V[c] = vv < -4f ? -4f : (vv > 6f ? 6f : vv);
                RHO[c] = smf(vv) * dens;
                HG[c] = sC * (0.58f + 0.80f * hn);
                AN[c] = fringe * smf(sAll * 9f - 0.1f);   // fades out with the field itself, so it can never be cut by the image edge
                // (the anvil share is no longer needed: the plane now carries the fringe strength)
                LA[c] = lobe;        // stashed for the lighting pass below
            }
        }

        // soften the height field a little: creases become broad soft shadows instead of thin lines
        for (int pass = 0; pass < 3; pass++) {
            for (int j = 0; j < gh; j++) {
                int row = j * gw;
                float prev = HG[row];
                for (int i = 1; i < gw - 1; i++) {
                    float cur = HG[row + i];
                    HG[row + i] = (prev + 2 * cur + HG[row + i + 1]) * 0.25f;
                    prev = cur;
                }
            }
            for (int i = 0; i < gw; i++) {
                float prev = HG[i];
                for (int j = 1; j < gh - 1; j++) {
                    int c = j * gw + i;
                    float cur = HG[c];
                    HG[c] = (prev + 2 * cur + HG[c + gw]) * 0.25f;
                    prev = cur;
                }
            }
        }

        // ---- lighting on the reduced grid
        final Light L = sp.light;
        final int steps = q == 0 ? 5 : q == 1 ? 7 : 9;
        final float[] sdx = new float[steps], sdy = new float[steps], sdw = new float[steps];
        {
            float d = 0, stp = Rn * 0.28f;
            for (int s = 0; s < steps; s++) {
                d += stp;
                sdx[s] = L.lx * d / csf;
                sdy[s] = L.ly * d / csf;
                sdw[s] = stp * invRn;
                stp *= 1.5f;
            }
        }
        final float sigma = pk(SIGMA, fk, kind, mk), bump = pk(BUMP, fk, kind, mk) * (0.4f + 0.6f * fs), contrast = pk(CONTRAST, fk, kind, mk);
        float l3x = L.lx * 0.85f, l3y = L.ly * 0.85f, l3z = 0.53f;
        {
            float nn = (float) Math.sqrt(l3x * l3x + l3y * l3y + l3z * l3z);
            l3x /= nn; l3y /= nn; l3z /= nn;
        }
        final float gk = Rn / (2 * csf) * bump;
        final float dark = (float) Model.clamp(sp.darkness, 0, 1);
        final float tintLuma = (sp.tint[0] * 0.30f + sp.tint[1] * 0.59f + sp.tint[2] * 0.11f) / 255f;
        final float liftK = superc ? (float) Model.clamp(0.86 / Math.max(0.2, tintLuma) - 1, 0, 1.0) : 0f;
        final float rotStr = superc ? (float) Model.clamp(sp.rotation, 0, 1) : 0f, iCoreHalf = (float) (2.0 / Math.max(8, coreW));
        final float rotPhase = (float) (sp.time * 0.55 * (0.3 + 0.7 * rotStr));
        final int[] upOff = {(int) Math.max(1, Math.round(Rn * 0.5 / cs)), (int) Math.max(2, Math.round(Rn * 1.2 / cs)), (int) Math.max(3, Math.round(Rn * 2.3 / cs))};
        for (int j = 0; j < gh; j++) {
            int row = j * gw;
            float yCell = gy0 + (j + 0.5f) * csf;
            float hf = (float) ((baseY - yCell) * invH);
            hf = hf < 0 ? 0 : (hf > 1 ? 1 : hf);
            for (int i = 0; i < gw; i++) {
                int c = row + i;
                if (V[c] <= -3.5f) { LD[c] = 1; LA[c] = 1; continue; }
                if (simple) {
                    // one even colour: no self-shadow, no relief, no striations; only a dark cloud darkens toward its base
                    float dks = dark * dark * (1f - hf) * (1f - 0.5f * hf) * 1.25f;
                    if (superc) dks = Math.min(1f, dks + (0.10f * (1f - hf) * (1f - hf) + (yCell > baseY ? 0.16f : 0f)) * (1f - 0.7f * dark));
                    LD[c] = 0.80f * (1f - 0.66f * dks);
                    LA[c] = 0.72f * (1f - 0.55f * dks);
                    continue;
                }
                float lobe = LA[c];
                // self-shadow: optical depth toward the light
                float tau = 0;
                for (int s = 0; s < steps; s++) {
                    int xi = (int) (i + sdx[s] + 0.5f), yi = (int) (j + sdy[s] + 0.5f);
                    if (xi < 0 || yi < 0 || xi >= gw || yi >= gh) break;
                    tau += RHO[yi * gw + xi] * sdw[s];
                }
                float tr = (float) Math.exp(-sigma * tau);
                // sky occlusion: cloud straight above
                float tu = 0;
                for (int s = 0; s < 3; s++) {
                    int yi = j - upOff[s];
                    if (yi < 0) break;
                    tu += RHO[yi * gw + i];
                }
                float occ = (float) Math.exp(-0.45f * tu);
                // pseudo-normal of the thickness field: every billow gets a lit and a shaded side
                int il = i > 0 ? c - 1 : c, ir = i < gw - 1 ? c + 1 : c, iu = j > 0 ? c - gw : c, id = j < gh - 1 ? c + gw : c;
                float gx = (HG[ir] - HG[il]) * gk, gy = (HG[id] - HG[iu]) * gk;
                float nz = 1f / (float) Math.sqrt(gx * gx + gy * gy + 1f);
                float diff = (-gx * l3x - gy * l3y + l3z) * nz;
                diff = smf(diff * 1.08f);
                float ld = (0.58f + 0.42f * diff) * (0.60f + 0.40f * tr) * (0.74f + 0.26f * lobe);
                float la = (0.35f + 0.65f * hf) * (0.55f + 0.45f * occ) * (0.84f + 0.16f * lobe);
                ld = 0.78f + (ld - 0.78f) * contrast;
                la = 0.70f + (la - 0.70f) * (0.45f + 0.55f * contrast);
                float dk = dark * dark * (1f - hf) * (1f - 0.5f * hf) * 1.25f;
                if (special && SH[c] > 0) {
                    // the absorbed layer is a flat grey deck: evenly lit, a shade darker than the heap it runs through
                    float s2 = SH[c] / (S[c] + SH[c] + WL[c] + 1e-4f);
                    s2 = s2 > 1 ? 1 : s2;
                    // (deep inside a dark host - a storm's shadowed flank - the deck must not glow: there it only ever
                    // darkens, and it is lifted to its own grey just where it stands clear of the host)
                    float clear = 1f - smf((S[c] - 0.10f) / 0.55f) * (0.35f + 0.65f * dark);
                    ld += (0.84f - ld) * 0.34f * s2 * (ld > 0.84f ? 1f : clear);
                    la += (0.66f - la) * 0.34f * s2 * (la > 0.66f ? 1f : clear);
                    ld *= 1f - 0.055f * s2;
                    la *= 1f - 0.045f * s2;
                }
                if (superc) {
                    // a dark, flat rain-free base and a darker wall cloud under it
                    float low = (1f - hf) * (1f - hf);
                    dk += (0.10f * low + (yCell > baseY ? 0.16f : 0f)) * (1f - 0.7f * dark);
                    if (dk > 1.0f) dk = 1.0f;
                    if (liftK > 0) {
                        float up = 1f + liftK * smf((hf - 0.16f) / 0.62f);
                        ld *= up;
                        la *= up;
                    }
                    if (rotStr > 0.02f) {
                        // striations: the updraft tower shows stacked, tilted bands that creep round as it rotates
                        float xc = gx0 + (i + 0.5f) * csf - shear * hf, xr = (xc - updraftX) * iCoreHalf;
                        float ax = xr < 0 ? -xr : xr;
                        if (ax < 1.15f && hf > 0.03f && hf < 0.74f) {
                            float env = smf((1.15f - ax) * 1.8f) * smf(hf / 0.10f) * (1f - smf((hf - 0.50f) / 0.24f));
                            float band = (float) Math.sin(hf * 31.0f + xr * 2.3f * windDir + rotPhase + 0.7f * Math.sin(xr * 2.6f + rotPhase * 0.5f));
                            band = band * (0.65f + 0.35f * band * band);       // broad light bands, narrower dark grooves
                            ld *= 1f + 0.27f * rotStr * env * band;
                            la *= 1f + 0.17f * rotStr * env * band;
                        }
                    }
                }
                LD[c] = ld * (1f - 0.66f * dk);
                LA[c] = la * (1f - 0.55f * dk);
            }
        }

        // ---- full-resolution pass: interpolate the smooth fields, add fine detail, threshold, shade
        Image out = new Image();
        out.w = iw; out.h = ih;
        out.relX = x0; out.relY = y0;
        out.scale = imgScale;
        out.baseY = baseY; out.topY = topY;
        int[] dst = new int[iw * ih];
        if (sc.xi.length < iw) { sc.xi = new int[iw + 64]; sc.xf = new float[iw + 64]; }
        final int[] xi = sc.xi;
        final float[] xf = sc.xf;
        final float inv = (float) (1.0 / imgScale);
        for (int i = 0; i < iw; i++) {
            float g = ((float) x0 + (i + 0.5f) * inv - gx0) / csf - 0.5f;
            if (g < 0) g = 0;
            if (g > gw - 1.001f) g = gw - 1.001f;
            xi[i] = (int) g;
            xf[i] = g - xi[i];
        }
        final float lamF = Rn * 0.21f;
        final boolean fine1 = lamF * imgScale >= 2.6, fine2 = lamF / 2.3f * imgScale >= 2.6;
        final float fineA = pk(FINE, fk, kind, mk) * (0.5f + 0.5f * fs), iLamF = 1f / lamF;
        // `opacity` thins the body only gently: condensation and evaporation are already carried by `form`
        final float aMax = (float) (pk(ALPHA_MAX, fk, kind, mk) * Model.clamp(0.52 + 0.5 * sp.opacity, 0, 1)) * dens / 0.9889f;
        // `tint` is a colour cast on the white of the cloud, not a paint colour: keep about half of its saturation
        final float luma = (sp.tint[0] * 0.30f + sp.tint[1] * 0.59f + sp.tint[2] * 0.11f) / 255f;
        final float aR = luma + (sp.tint[0] / 255f - luma) * 0.55f, aG = luma + (sp.tint[1] / 255f - luma) * 0.55f, aB = luma + (sp.tint[2] / 255f - luma) * 0.55f;
        final float dR = L.sr * 0.80f, dG = L.sg * 0.80f, dB = L.sb * 0.80f;
        final float rim = L.rim;
        final float advF = adv;
        final float FR = 2.6f;                                  // reach of the fringe, in edge-ramp widths
        final float lamW = Rn * 0.62f, iLamW = 1f / lamW, hazeA = 0.62f * (0.4f + 0.6f * tiny);
        final boolean wisp2 = lamW / 2.6f * imgScale >= 2.6;
        if (simple) simpleFill(dst, iw, ih, (float) y0, inv, gy0, csf, gw, gh, xi, xf, V, AN, LD, LA, aMax, hazeA, FR, aR * dR, aG * dG, aB * dB,
                aR * 0.54f, aG * 0.54f, aB * 0.54f, L);
        else
        for (int j = 0; j < ih; j++) {
            float yw = (float) y0 + (j + 0.5f) * inv;
            float g = (yw - gy0) / csf - 0.5f;
            if (g < 0) g = 0;
            if (g > gh - 1.001f) g = gh - 1.001f;
            int yi = (int) g;
            float fy = g - yi;
            int r0 = yi * gw, r1 = r0 + gw, o = j * iw;
            for (int i = 0; i < iw; i++) {
                int c0 = r0 + xi[i], c1 = r1 + xi[i];
                float fx = xf[i];
                float v0 = V[c0] + (V[c0 + 1] - V[c0]) * fx, v1 = V[c1] + (V[c1 + 1] - V[c1]) * fx;
                float v = v0 + (v1 - v0) * fy;
                if (v <= -2.9f) continue;
                float haze = 0;
                if (v < 2.4f) {
                    float xw = (float) x0 + (i + 0.5f) * inv - advF;
                    if (fine1) {
                        float f = Noise.perlin2(xw * iLamF, yw * iLamF, seed + 77);
                        if (fine2) f = f * 0.68f + 0.32f * Noise.perlin2(xw * iLamF * 2.3f, yw * iLamF * 2.3f, seed + 79);
                        v += fineA * f;
                    }
                    if (v < 0.5f && v > -FR) {
                        // thin translucent veil just outside the body, torn into wisps and shreds by its own noise
                        float q0 = AN[c0] + (AN[c0 + 1] - AN[c0]) * fx, q1 = AN[c1] + (AN[c1 + 1] - AN[c1]) * fx;
                        float fr = q0 + (q1 - q0) * fy;
                        if (fr > 0.02f) {
                            float t = (v + FR) * (1f / FR);
                            if (t > 1) t = 1;
                            float w = 0.5f + 0.5f * Noise.perlin2(xw * iLamW, yw * iLamW * 1.7f, seed + 83);
                            if (wisp2) w = w * 0.7f + 0.3f * (0.5f + 0.5f * Noise.perlin2(xw * iLamW * 2.6f, yw * iLamW * 3.4f, seed + 85));
                            w = w * 1.9f - 0.42f;
                            w = w < 0 ? 0 : (w > 1 ? 1 : w * w * (3 - 2 * w));
                            haze = fr * hazeA * t * t * (0.25f + 0.75f * w);
                        }
                    }
                }
                float rho = v <= 0 ? 0 : (v >= 1 ? 1 : v * v * (3 - 2 * v));
                float alpha = aMax * (1f - (float) Math.exp(-3.4f * rho)) * 1.0345f;
                alpha += (aMax - alpha) * haze;
                if (alpha < 0.004f) continue;
                if (alpha > 1) alpha = 1;
                float l0 = LD[c0] + (LD[c0 + 1] - LD[c0]) * fx, l1 = LD[c1] + (LD[c1 + 1] - LD[c1]) * fx;
                float ld = l0 + (l1 - l0) * fy;
                float a0 = LA[c0] + (LA[c0 + 1] - LA[c0]) * fx, a1 = LA[c1] + (LA[c1 + 1] - LA[c1]) * fx;
                float la = a0 + (a1 - a0) * fy;
                float dl = ld + rim * (1f - rho) * ld;
                float am = 0.54f;
                float r = aR * (dR * dl + (L.br + (L.tr - L.br) * la) * am);
                float gg = aG * (dG * dl + (L.bg + (L.tg - L.bg) * la) * am);
                float b = aB * (dB * dl + (L.bb + (L.tb - L.bb) * la) * am);
                if (r > 1) r = 1;
                if (gg > 1) gg = 1;
                if (b > 1) b = 1;
                int ia = (int) (alpha * 255f + 0.5f);
                dst[o + i] = (ia << 24) | ((int) (r * alpha * 255f + 0.5f) << 16) | ((int) (gg * alpha * 255f + 0.5f) << 8) | (int) (b * alpha * 255f + 0.5f);
            }
        }
        out.px = dst;
        out.img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB_PRE);
        out.img.getRaster().setDataElements(0, 0, iw, ih, dst);
        if (Advanced.cur.cullHidden) out.buildOpaque();        // (here, on the worker: the frame thread then only looks it up)

        if (sp.precipRate > 0.02 && sp.veilDrop > 24 && !"none".equals(sp.precipType)) {
            long vs = sp.seed * 31 + sp.precipType.hashCode();
            vs = vs * 1099511628211L + Math.round(sp.precipRate * 20);
            vs = vs * 1099511628211L + Math.round(sp.veilDrop) * 4099 + Math.round(sp.veilDrift);
            vs = vs * 1099511628211L + Math.round(sp.form * 40) * 131 + Math.round(sp.opacity * 50);
            vs = vs * 1099511628211L + sp.light.key();
            vs = vs * 1099511628211L + Double.doubleToLongBits(sp.scale);
            vs = vs * 1099511628211L + Math.round(bodyW / 6) * 8191 + Math.round(bodyCx / 6) * 127 + Math.round(baseY / 4) * 31 + Math.round(Hc / 6);
            Image r = sp.reuse;
            if (r != null && r.veil != null && r.vSig == vs) {
                out.veil = r.veil; out.vpx = r.vpx; out.vw = r.vw; out.vh = r.vh;
                out.vRelX = r.vRelX; out.vRelY = r.vRelY; out.vScale = r.vScale;
            } else if (superc) veil(sp, out, coreCx + windDir * coreW * 0.26, coreW * 0.86, baseY, Hc, seed, fs);
            else veil(sp, out, bodyCx, bodyW, baseY, Hc, seed, fs);
            out.vSig = vs;
        }
        return out;
    }

    /** The rain shaft / virga: a soft, streaked, wind-sheared veil hanging from the base. */
    private static void veil(Spec sp, Image out, double cx, double bodyW, double baseY, float Hc, int seed, float fs) {
        double hw = Math.max(18, bodyW * 0.34), drop = sp.veilDrop, drift = Model.clamp(sp.veilDrift, -drop * 0.6, drop * 0.6);
        double top = baseY - Hc * 0.10;
        double x0 = cx - hw * 1.25 + Math.min(0, drift), x1 = cx + hw * 1.25 + Math.max(0, drift);
        double y0 = top, y1 = baseY + drop + 4;
        double bw = x1 - x0, bh = y1 - y0;
        double cell = Math.max(3.0 / sp.scale, Math.sqrt(bw * bh / 9000.0));
        int gw = (int) Math.ceil(bw / cell) + 1, gh = (int) Math.ceil(bh / cell) + 1;
        float rate = (float) Model.clamp(sp.precipRate, 0, 1);
        boolean virga = rate < 0.3;
        float fadeAt = virga ? 0.30f + rate * 1.6f : 2f;
        int[] rgb = switch (sp.precipType) {
            case "snow" -> new int[]{236, 240, 250};
            case "mixed" -> new int[]{176, 200, 226};
            case "graupel" -> new int[]{186, 194, 212};
            default -> new int[]{128, 146, 176};
        };
        Light L = sp.light;
        float lr = Math.min(1, L.sr * 0.42f + L.tr * 0.62f), lg = Math.min(1, L.sg * 0.42f + L.tg * 0.62f), lb = Math.min(1, L.sb * 0.42f + L.tb * 0.62f);
        float cr = rgb[0] / 255f * lr, cg = rgb[1] / 255f * lg, cb = rgb[2] / 255f * lb;
        float aMax = (0.16f + 0.40f * rate) * fs * (float) Model.clamp(sp.opacity, 0, 1);
        float fall = 0;   // the streaks stay put (the falling is shown by the particles), so the veil can be reused between renders
        BufferedImage low = new BufferedImage(gw, gh, BufferedImage.TYPE_INT_ARGB_PRE);
        int[] lp = new int[gw * gh];
        for (int j = 0; j < gh; j++) {
            float y = (float) (y0 + (j + 0.5) * cell);
            float vf = (float) ((y - baseY) / drop);
            float vc = vf < 0 ? 0 : vf;
            float xc = (float) (cx + drift * Math.pow(vc, 1.35));
            float envY = smf((float) ((y - top) / (Hc * 0.16 + 4))) * (1f - 0.42f * vc);
            if (virga) envY *= 1f - smf((vc - fadeAt * 0.45f) / (fadeAt * 0.55f));
            if (envY <= 0.003f) continue;
            float spread = (float) (hw * (1 + 0.16 * vc));
            for (int i = 0; i < gw; i++) {
                float x = (float) (x0 + (i + 0.5) * cell);
                float ux = Math.abs(x - xc) / spread;
                if (ux >= 1.2f) continue;
                float envX = 1f - smf((ux - 0.45f) / 0.75f);
                float st = 0.5f + 0.5f * Noise.fbm2((x - xc) / (float) (hw * 0.11), (y - fall) / (float) (drop * 0.9 + 60), seed + 91, 3);
                float st2 = 0.5f + 0.5f * Noise.perlin2((x - xc) / (float) (hw * 0.035), (y - fall * 1.3f) / (float) (drop * 0.5 + 40), seed + 93);
                float s = (st * 0.7f + st2 * 0.3f) * 1.7f - 0.3f;
                s = s < 0 ? 0 : (s > 1 ? 1 : s);
                float a = aMax * envX * envY * (0.30f + 0.70f * s);
                if (a < 0.004f) continue;
                int ia = (int) (a * 255f + 0.5f);
                lp[j * gw + i] = (ia << 24) | ((int) (cr * a * 255f + 0.5f) << 16) | ((int) (cg * a * 255f + 0.5f) << 8) | (int) (cb * a * 255f + 0.5f);
            }
        }
        low.getRaster().setDataElements(0, 0, gw, gh, lp);
        double vs = sp.scale;
        if (bw * bh * vs * vs > 450_000) vs = Math.sqrt(450_000 / (bw * bh));
        int iw = Math.max(2, (int) Math.round(gw * cell * vs)), ih = Math.max(2, (int) Math.round(gh * cell * vs));
        BufferedImage hi = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = hi.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(low, 0, 0, iw, ih, null);
        g.dispose();
        int[] vp = new int[iw * ih];
        hi.getRaster().getDataElements(0, 0, iw, ih, vp);
        BufferedImage managed = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB_PRE);
        managed.getRaster().setDataElements(0, 0, iw, ih, vp);
        out.veil = managed;
        out.vpx = vp;
        out.vw = iw; out.vh = ih;
        out.vRelX = x0; out.vRelY = y0;
        out.vScale = iw / (gw * cell);
    }

    // ------------------------------------------------------------------ contact sheet (diagnostics)

    /**
     * {@code --cloud-sheet out.png}: every cloud type of the fake sim at form 0.12 / 0.4 / 0.7 / 1.0
     * on a sky gradient, for judging the look of the renderer in isolation. The last row follows one
     * cumulus of the fake sim as it towers into a cumulonimbus (morph 0 / 0.25 / 0.5 / 0.75 / 1) and
     * prints how far the switch frame is from the plain cumulus render of the same puffs.
     */
    static void sheet(String outPath, double tod, long seed) throws java.io.IOException {
        FakeSim fs = new FakeSim(seed, tod);
        ViewerState st = new ViewerState();
        st.acceptLine(fs.hello());
        for (int k = 0; k < 400; k++) st.acceptLine(fs.step());
        Model.Frame f = st.cur;
        double[] forms = {0.12, 0.4, 0.7, 1.0};
        int cw = 420, ch = 300;
        BufferedImage img = new BufferedImage(cw * forms.length, ch * 9, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        Light L = Light.of(tod, 0);
        java.awt.Color top = new java.awt.Color((int) (255 * Math.min(1, L.tr * 0.45)), (int) (255 * Math.min(1, L.tg * 0.72)), (int) (255 * Math.min(1, L.tb * 1.0)));
        java.awt.Color bot = new java.awt.Color((int) (255 * Math.min(1, L.tr * 1.05 + L.sr * 0.2)), (int) (255 * Math.min(1, L.tg * 1.05 + L.sg * 0.12)), (int) (255 * Math.min(1, L.tb * 1.08)));
        g.setPaint(new java.awt.GradientPaint(0, 0, top, 0, ch, bot, true));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        Scratch sc = new Scratch();
        for (int kind = 0; kind < 8; kind++) {
            Model.Cloud pick = null;
            for (Model.Cloud c : f.clouds) if (c.kind == kind && c.form > 0.95) { pick = c; break; }
            if (pick == null) for (Model.Cloud c : f.clouds) if (c.kind == kind) { pick = c; break; }
            if (pick == null) continue;
            double minx = 1e9, maxx = -1e9, miny = 1e9, maxy = -1e9;
            for (double[] p : pick.puffs) {
                minx = Math.min(minx, p[0] - p[2]); maxx = Math.max(maxx, p[0] + p[2]);
                miny = Math.min(miny, p[1] - p[2]); maxy = Math.max(maxy, p[1] + p[2]);
            }
            for (int k = 0; k < forms.length; k++) {
                Spec sp = new Spec();
                sp.seed = pick.seed; sp.kind = kind; sp.puffs = pick.puffs; sp.form = forms[k]; sp.shear = pick.shear;
                sp.anvil = kind == Model.CUMULONIMBUS ? 0.85 : 0; sp.darkness = pick.darkness; sp.opacity = pick.opacity; sp.tint = pick.tint;
                sp.time = f.t; sp.wind = 4; sp.light = L;
                sp.scale = Math.min((cw - 50) / ((maxx - minx) * (kind == Model.CUMULONIMBUS ? 1.5 : 1.08)), (ch - 40) / (maxy - miny + 20));
                sp.scale = Math.min(sp.scale, 2.2);
                long t0 = System.nanoTime();
                Image im = render(sp, sc);
                double ms = (System.nanoTime() - t0) / 1e6;
                if (im == null) continue;
                double ox = k * cw + cw / 2.0 - (minx + maxx) / 2 * sp.scale, oy = kind * ch + ch / 2.0 - (miny + maxy) / 2 * sp.scale + 6;
                g.drawImage(im.img, (int) Math.round(ox + im.relX * sp.scale), (int) Math.round(oy + im.relY * sp.scale), null);
                g.setColor(new java.awt.Color(255, 255, 255, 200));
                g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 11));
                g.drawString(Model.CLOUD_TYPES[kind] + "  form " + forms[k] + String.format("  %dx%d  %.1f ms", im.w, im.h, ms), k * cw + 8, kind * ch + 14);
            }
        }
        morphRow(g, fs, st, L, sc, 8 * ch, img.getWidth(), ch);
        g.dispose();
        java.io.File of = new java.io.File(outPath);
        if (of.getAbsoluteFile().getParentFile() != null) of.getAbsoluteFile().getParentFile().mkdirs();
        javax.imageio.ImageIO.write(img, "png", of);
        System.out.println("[viewer] cloud sheet written: " + of.getAbsolutePath());
    }

    static Spec sheetSpec(Model.Cloud c, double time, Light L, double scale) {
        Spec sp = new Spec();
        sp.seed = c.seed; sp.kind = c.kind; sp.puffs = c.puffs; sp.form = c.form; sp.shear = c.shear; sp.anvil = c.anvil;
        sp.darkness = c.darkness; sp.opacity = c.opacity; sp.tint = c.tint; sp.time = time; sp.wind = 4; sp.light = L; sp.scale = scale;
        sp.morphOf(c);
        sp.extrasOf(c);
        return sp;
    }

    /** Largest per-channel difference (0..255, premultiplied ARGB) between two renders; 999 when their sizes differ. */
    static int maxChannelDiff(Image a, Image b) {
        if (a == null || b == null) return a == b ? 0 : 999;
        if (a.w != b.w || a.h != b.h || a.relX != b.relX || a.relY != b.relY) return 999;
        int d = 0;
        for (int i = 0; i < a.px.length; i++) {
            int p = a.px[i], q = b.px[i];
            if (p == q) continue;
            for (int s = 0; s < 32; s += 8) d = Math.max(d, Math.abs(((p >>> s) & 255) - ((q >>> s) & 255)));
        }
        return d;
    }

    /**
     * The cumulus -> cumulonimbus row of the contact sheet: keeps stepping the fake sim, catches the
     * first cloud that reports a {@code from_type} at morph 0 (the switch frame), 0.25, 0.5, 0.75
     * and 1, and renders the five states at one scale with their bases on one line.
     */
    private static void morphRow(Graphics2D g, FakeSim fs, ViewerState st, Light L, Scratch sc, int y0, int width, int ch) {
        double[] targets = {0, 0.25, 0.5, 0.75, 1};
        Model.Cloud[] got = new Model.Cloud[targets.length];
        Model.Cloud before = null;   // the same cloud one frame before its type changed
        java.util.Map<Long, Model.Cloud> prev = new java.util.HashMap<>();
        long id = -1;
        int next = 0;
        for (int k = 0; k < 9000 && next < targets.length; k++) {
            for (Model.Cloud c : st.cur.clouds) {
                if (c.fromKind < 0 || (id >= 0 && c.id != id)) continue;
                if (id < 0) {
                    if (prev.containsKey(c.id) && prev.get(c.id).fromKind < 0) { id = c.id; before = prev.get(c.id); }
                    else continue;       // already morphing when first seen: wait for one caught on its switch frame
                }
                if (next < targets.length && c.morph >= targets[next]) got[next++] = c;
            }
            prev.clear();
            for (Model.Cloud c : st.cur.clouds) prev.put(c.id, c);
            if (next < targets.length) st.acceptLine(fs.step());
        }
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 11));
        g.setColor(new java.awt.Color(255, 255, 255, 200));
        if (next < targets.length || before == null) {
            g.drawString("no cumulus -> cumulonimbus morph seen in the fake sim", 8, y0 + 14);
            System.out.println("[viewer] cloud sheet: no complete morph found in the fake sim");
            return;
        }
        Model.Cloud end = got[targets.length - 1];
        double minx = 1e9, maxx = -1e9, top = 1e9;
        for (double[] p : end.puffs) {
            if (p[2] <= 0.3) continue;
            minx = Math.min(minx, p[0] - p[2]); maxx = Math.max(maxx, p[0] + p[2]);
            top = Math.min(top, p[1] - p[2]);
        }
        double endBase = baseOf(end.kind, end.puffs), time = st.cur.t;
        int cw = width / targets.length;
        double scale = Math.min(2.2, Math.min((cw - 30) / ((maxx - minx) * 1.9), (ch - 44) / (endBase - top + 20)));
        Image first = null;
        for (int k = 0; k < targets.length; k++) {
            Model.Cloud c = got[k];
            Spec sp = sheetSpec(c, time, L, scale);
            Image im = render(sp, sc);
            if (k == 0) first = im;
            if (im == null) continue;
            // origin x at the cell centre, cloud base 16 px above the bottom of the row
            double ox = k * cw + cw / 2.0, oy = y0 + ch - 16 - baseOf(c.kind, c.puffs) * scale;
            g.drawImage(im.img, (int) Math.round(ox + im.relX * scale), (int) Math.round(oy + im.relY * scale), null);
            g.setColor(new java.awt.Color(255, 255, 255, 200));
            g.drawString(String.format(java.util.Locale.ROOT, "%s from %s  morph %.3f  anvil %.2f  %d puffs  %dx%d", Model.CLOUD_PREFIX[c.kind],
                    Model.CLOUD_PREFIX[c.fromKind], c.morph, c.anvil, c.puffs.length, im.w, im.h), k * cw + 8, y0 + 14);
            g.drawString(c.name, k * cw + 8, y0 + 28);
        }
        // how far is the switch frame from (a) the plain cumulus render of its own puffs, (b) the frame before it?
        Model.Cloud sw = got[0];
        Spec plain = sheetSpec(sw, time, L, scale);
        plain.kind = sw.fromKind; plain.fromKind = -1; plain.morph = 1; plain.anvil = 0;
        Spec exact = sheetSpec(sw, time, L, scale);
        exact.morph = 0;
        Spec hard = sheetSpec(sw, time, L, scale);
        hard.fromKind = -1; hard.morph = 1;     // what an unblended switch of the per-kind constants would draw
        Image pi = render(plain, sc);
        int dPlain = maxChannelDiff(first, pi), dExact = maxChannelDiff(render(exact, sc), pi), dPrev = maxChannelDiff(first, render(sheetSpec(before, time, L, scale), sc));
        int dHard = maxChannelDiff(render(hard, sc), pi);
        System.out.println(String.format(java.util.Locale.ROOT, "[viewer] morph row: %s (id %d), switch frame morph=%.6f, %d puffs (%d before)", sw.name, sw.id, sw.morph,
                sw.puffs.length, before.puffs.length));
        System.out.println("[viewer] morph row: max channel diff, switch frame vs plain " + Model.CLOUD_TYPES[sw.fromKind] + " render of the same puffs = " + dPlain
                + " (at exactly morph 0: " + dExact + "); vs the frame before the switch = " + dPrev + "; an unblended switch would differ by " + dHard + "  [0..255, 999 = different size]");
    }

    public static void main(String[] a) throws Exception {
        sheet(a.length > 0 ? a[0] : "cloud_sheet.png", a.length > 1 ? Double.parseDouble(a[1]) : 0.42, a.length > 2 ? Long.parseLong(a[2]) : 1234L);
    }
}
