package precip;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.ref.SoftReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.LongAdder;

/**
 * Phase O: the trees and plants ({@link BuildingPainter#TREE_VARIANTS}), each built the way the plant is: a tapering
 * trunk, limbs and twigs that reach into the crown, and the foliage as clusters of single leaves along them (needles on
 * drooping boughs for the pine, pinnate fronds with separate leaflets for the palm, ribs and spines for the cactus, bare
 * twigs for the snag).
 *
 * <p><b>Cost.</b> A tree has a few hundred leaves, and a fill per leaf would cost far more than a frame has. So the
 * leaves are never filled one by one: every leaf is a small polygon appended to one of a handful of paths (one per tone,
 * dark to light), and each path is filled once. A whole tree is 6 to 16 fills whatever its leaf count.
 *
 * <p><b>Level of detail</b> ({@link #lod}) goes by the size of one leaf in device pixels, read off the graphics'
 * transform: under {@link #CLUMP_PX} the clusters are drawn as soft clumps (no leaf could be told apart, and single
 * pixels would shimmer), from there on every leaf is a diamond, from {@link #BLADE_PX} a shaped blade with a lit and a
 * shaded half, from {@link #FINE_PX} its real outline with a midrib. The skeleton, the clusters, their light and every
 * leaf's place and tone are the same at every level (the random numbers are drawn the same way), so the silhouette and
 * the overall colour do not jump when the level changes.
 *
 * <p><b>Pure.</b> Nothing is cached and nothing is shared: the picture is a function of the {@link Model.Obj}, the
 * light, the lean and the transform alone, so it is the same on every frame and on every render band.
 */
final class TreePainter {
    private TreePainter() {}

    /** Device pixels of one leaf's length from which leaves are drawn singly / as shaped blades / with outline and midrib. */
    static final double CLUMP_PX = 1.8, BLADE_PX = 4.0, FINE_PX = 8.0;

    /** The length in world units of the variant's "leaf" (the pine's needle spray, the palm's leaflet): what {@link #lod} measures. */
    static double leafUnit(String variant) {
        return switch (variant) {
            case "oak" -> 3.4;
            case "birch" -> 2.5;
            case "bush" -> 2.7;
            case "acacia" -> 2.2;
            case "shrub" -> 2.1;
            case "palm" -> 3.0;
            case "cactus", "dead" -> 2.6;
            default -> 2.5;
        };
    }

    /** The level of detail (0 clumps, 1 leaves, 2 blades, 3 outlines) of a variant at {@code px} device pixels per world unit. */
    static int lod(String variant, double px) {
        double d = leafUnit(variant) * px;
        return d < CLUMP_PX ? 0 : d < BLADE_PX ? 1 : d < FINE_PX ? 2 : 3;
    }

    static String variant(Model.Obj o) {
        String v = o.variant;
        return v == null || v.isEmpty() ? (Math.floorMod(o.seed, 3) != 0 ? "pine" : "oak") : v;
    }

    // ------------------------------------------------------------------ tables (immutable)

    private static final int GN = 64;
    /** The golden-angle spiral: leaf and cluster places that fill a disc evenly. */
    private static final double[] GC = new double[GN], GS = new double[GN];
    private static final double[] RC = new double[24], RS = new double[24];
    static {
        for (int i = 0; i < GN; i++) { GC[i] = Math.cos(i * 2.399963229728653); GS[i] = Math.sin(i * 2.399963229728653); }
        for (int i = 0; i < 24; i++) { RC[i] = Math.cos(i * Math.PI / 12); RS[i] = Math.sin(i * Math.PI / 12); }
    }

    private static final int BLADE = 0, OAK = 1, BIRCH = 2, NARROW = 3, ROUND = 4;
    /** Leaf outlines, one side from the tip (1, 0) to the base (-1, 0): {u along the leaf, v across it}. */
    private static final float[][] SU = {
            {0.45f, -0.10f, -0.60f, -0.90f},
            {0.82f, 0.60f, 0.42f, 0.15f, -0.08f, -0.35f, -0.58f, -0.82f},
            {0.70f, 0.30f, -0.35f, -0.75f, -0.95f},
            {0.40f, -0.20f, -0.70f},
            {0.75f, 0.30f, -0.30f, -0.75f}};
    private static final float[][] SV = {
            {0.62f, 1.00f, 0.80f, 0.30f},
            {0.30f, 0.72f, 0.44f, 0.98f, 0.52f, 0.82f, 0.40f, 0.30f},
            {0.16f, 0.55f, 1.00f, 0.80f, 0.30f},
            {0.70f, 1.00f, 0.60f},
            {0.60f, 0.95f, 1.00f, 0.65f}};

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    static double hash(long z) { return (mix(z) >>> 11) * 0x1.0p-53; }

    // ------------------------------------------------------------------ the pen: transform state, bend, paths, colours

    private static final class Pen {
        static final int BN = 26, MAXSEG = 12;
        final Graphics2D g;
        final Lit lit;
        final double haze, px, wet, snow;
        final int lod;
        final boolean snowOn;
        /** Wind: every point is pushed sideways by its height above the root ({@link #swayAt}); swayK is the old painter's shear. */
        double swayK, swayH = 1;
        /** The share of the leaves a storm has left on the tree. */
        double keep = 1;
        // ---- the break: past SNAP of storm damage the trunk has snapped; what is above the break comes down
        /** The trunk is broken; {@link #up}: what is being drawn now belongs to the part that came down (else to the stump). */
        boolean broke, up;
        /** The current curve is the trunk: {@link #strip} cuts its pieces at the break, {@link #at} tells the two parts apart. */
        boolean onTrunk, inStrip;
        /** First pass over a broken tree: nothing is drawn, only how far what fell would reach under the ground and how high it lies. */
        boolean measure;
        /** Litter on the ground: points are taken as they are (plus {@link #ox}, {@link #oy}), no bend and no fall. */
        boolean raw;
        double ox, oy;
        /** The storm damage 0..1, the side the tree falls to (-1 left, +1 right), the seed of everything about the break. */
        double stage, side;
        long bseed;
        /** A plant that does not snap (bush, shrub) is pressed flat instead: its heights times this. */
        double flat = 1;
        /** The break on the trunk's curve, its height as y of the standing plant, the axis there, the trunk's half-width there, the bare trunk's end. */
        double tB, brkY, hx, bhw, tBare = 1;
        /** The hinge (the corner of the break on the side of the fall), the turn about it and the shift after it; the angle and how far it has come off the stump (0..1). */
        double pvx, pvy, fC = 1, fS, fTx, fTy, theta, off;
        /** What fell would reach this far under the ground ({@link #sunk}); it is pressed into the lowest {@link #band} px instead. {@link #heap}: how high it lies. */
        double sunk, band = 1, heap;
        /** How long the splinters of the break are (1 = wood; a cactus tears short and pulpy). */
        double splinter = 1;
        double mDown = -1e18, mTop = 1e18;
        /** Where the far end of what fell has come to rest (x), for the litter around it. */
        double restX;
        /** The trunk's axis over height (x at AX_N + 1 heights from the root to its top), and how far from it the pressing starts / is whole. */
        static final int AX_N = 12;
        final double[] axX = new double[AX_N + 1], axY = new double[AX_N + 1];
        double rig0, rig1;
        boolean axis;
        /** The leaves of this plant, as {@link TreePainter#crown} was last asked for them (the litter is made of the same). */
        double lfLen = 2.5, lfWr = 0.6, lfCr = 4, lfDroop;
        int lfShape;
        /** The clip in the tree's own coordinates: whole clusters outside it are skipped (the pixels inside do not change). */
        double cx0 = -1e18, cy0 = -1e18, cx1 = 1e18, cy1 = 1e18;
        // the bend: the centre line of the plant as a table over height
        boolean bent;
        double bStep;
        double[] bX, bY, bC, bS;
        /** Result of {@link #warp} / {@link #at}: the bent point, and the bend's rotation there. */
        double X, Y, C = 1, S = 0, TX, TY;
        long rs;
        // the current limb: a quadratic curve with a half-width that runs from w0 to w1 (plus a bulge in the middle)
        double qx0, qy0, qcx, qcy, qx1, qy1, qw0, qw1, qwb;
        final float[] ax = new float[MAXSEG + 1], ay = new float[MAXSEG + 1], bx = new float[MAXSEG + 1], by = new float[MAXSEG + 1];
        // the foliage tones, dark to light, with the shaded half and the midrib of each
        final Color[] tone = new Color[5], halfTone = new Color[5], ribTone = new Color[5];
        Color cBackUnder, cBackLo, cBackHi, cUnder, bark, barkDark, barkMark, barkLite, trunkC, snowC, snowShadeC;
        /** The wood a break lays bare: the pale splinters, their shaded side, and the torn edge of the bark over them. */
        Color cPale, cPaleShade, cTear;
        final Path2D.Float pale = new Path2D.Float(), paleShade = new Path2D.Float(), tear = new Path2D.Float();
        final Path2D.Float[] full = new Path2D.Float[5], half = new Path2D.Float[5], rib = new Path2D.Float[5];
        final Path2D.Float backUnder = new Path2D.Float(), backLo = new Path2D.Float(), backHi = new Path2D.Float(), wood = new Path2D.Float(),
                trunk = new Path2D.Float(), woodShade = new Path2D.Float(), woodLite = new Path2D.Float(), woodMark = new Path2D.Float(),
                under = new Path2D.Float(), snowP = new Path2D.Float(), snowShade = new Path2D.Float();
        /** Fills a variant keeps to itself (the body of the cactus): {path, colour}, painted first, in this order. */
        final java.util.ArrayList<Object[]> later = new java.util.ArrayList<>();

        Pen(Graphics2D g, Lit lit, double haze, double px, Model.Obj o, int lod) {
            this.g = g; this.lit = lit; this.haze = haze; this.px = px; this.lod = lod;
            wet = Model.clamp(o.wet, 0, 1);
            snow = Model.clamp(o.snow, 0, 1);
            snowOn = o.snow > 0.04;
            Color white = new Color(248, 250, 255);
            snowC = Lit.alpha(lit == null ? white : lit.lit(white, haze * 0.6), (int) (255 * Math.min(1, o.snow * 1.4)));
            Color rim = new Color(176, 194, 224);
            snowShadeC = Lit.alpha(lit == null ? rim : lit.lit(rim, haze * 0.6), (int) (255 * Math.min(1, o.snow * 1.4)));
            for (int k = 0; k < 5; k++) {
                full[k] = new Path2D.Float();
                if (lod >= 2) half[k] = new Path2D.Float();
                if (lod >= 3) rib[k] = new Path2D.Float();
            }
            Rectangle2D cb = g.getClipBounds();
            if (cb != null) { cx0 = cb.getMinX() - 1; cy0 = cb.getMinY() - 1; cx1 = cb.getMaxX() + 1; cy1 = cb.getMaxY() + 1; }
        }

        /** A colour under the light (as it is, when the picture is a sprite that is lit later: {@link #relight}). */
        Color c(Color col) { return lit == null ? col : lit.lit(col, haze); }

        /** The foliage tones from the leaf colour and its sunlit colour: wet leaves are darker, their highlights glossier. */
        void tones(Color leaf, Color hi) {
            Color l = Lit.mix(leaf, Lit.mul(leaf, 0.62), wet * 0.7), h = Lit.mix(hi, leaf, wet * 0.5);
            tone[0] = c(Lit.mul(l, 0.50));
            tone[1] = c(Lit.mul(l, 0.74));
            tone[2] = c(l);
            tone[3] = c(Lit.mix(l, h, 0.58));
            tone[4] = c(Lit.mix(h, new Color(216, 236, 224), wet * 0.2));
            for (int k = 0; k < 5; k++) {
                halfTone[k] = Lit.mul(tone[k], 0.84);
                ribTone[k] = k < 4 ? Lit.mix(tone[k], tone[k + 1], 0.7) : Lit.mul(tone[k], 0.8);
            }
            // (the far side of the crown: in the crown's own shade, but still foliage, not a hole)
            cBackUnder = tone[0];
            cBackLo = tone[1];
            cBackHi = lod == 0 ? Lit.mix(tone[0], tone[1], 0.75) : Lit.mix(tone[1], tone[2], 0.7);
            cUnder = Lit.mix(tone[0], tone[1], lod == 0 ? 1.0 : 0.45);
        }

        void bark(Color dry, Color soaked) {
            Color b = Lit.mix(dry, soaked, wet);
            bark = c(b);
            trunkC = bark;
            barkDark = c(Lit.mul(b, 0.72));
            barkMark = c(Lit.mul(b, 0.5));
            barkLite = c(Lit.mix(b, new Color(214, 200, 178), 0.3));
            // (fresh wood: pale and warm, darker when it is soaked)
            broken(Lit.mix(new Color(226, 196, 140), new Color(176, 142, 96), wet), Lit.mix(new Color(182, 142, 92), new Color(132, 100, 68), wet), Lit.mul(b, 0.58));
        }

        /** The colours of a break: the fresh surface, its shaded side, the torn edge of the skin. */
        void broken(Color fresh, Color shade, Color edge) { cPale = c(fresh); cPaleShade = c(shade); cTear = c(edge); }

        void seed(long s) { rs = mix(s); }

        double rnd() {
            rs = rs * 6364136223846793005L + 1442695040888963407L;
            long z = rs ^ (rs >>> 29);
            return ((z * 0x9E3779B97F4A7C15L) >>> 11) * 0x1.0p-53;
        }

        /**
         * Bend the plant: {@code k} is the shear the old painter used ({@code x' = x + k*y}), {@code h} the height that
         * shear was meant for. The top ends where the shear put it, but the stem curves there (stiff at the root, more
         * the higher: {@code e}) and keeps its length instead of being stretched.
         */
        void bend(double k, double h, double e) {
            double t = Model.clamp(-k * (e + 1), -1.35, 1.35);
            if (Math.abs(t) < 2e-4) return;
            bent = true;
            bStep = h * 1.45 / BN;
            bX = new double[BN + 2]; bY = new double[BN + 2]; bC = new double[BN + 2]; bS = new double[BN + 2];
            double x = 0, y = 0;
            for (int i = 0; i <= BN + 1; i++) {
                double u = i * bStep / h, th = t * Math.pow(u, e), um = (i + 0.5) * bStep / h, tm = t * Math.pow(um, e);
                bC[i] = Math.cos(th); bS[i] = Math.sin(th); bX[i] = x; bY[i] = y;
                x += Math.sin(tm) * bStep; y += Math.cos(tm) * bStep;
            }
        }

        /** The place of the straight plant's point (x, y) on the bent one. */
        void warp(double x, double y) {
            if (raw) { X = x + ox; Y = y + oy; C = 1; S = 0; return; }
            if (broke) {
                C = 1; S = 0;
                if (!up) { X = x; Y = y; return; }
                // what came down: turned about the hinge, shifted, and what would be under the ground pressed onto it
                double rx = x - pvx, ry = y - pvy;
                X = pvx + rx * fC - ry * fS + fTx;
                Y = pvy + rx * fS + ry * fC + fTy;
                C = fC; S = fS;
                if (measure) { if (Y > mDown) mDown = Y; if (Y < mTop) mTop = Y; return; }
                if (sunk > 0 && Y > -band) {
                    double w = loose(x, y);
                    if (w > 0) { double d = Y + band; Y -= w * (d - d / (1 + d * sunk / (band * (band + sunk)))); }
                }
                return;
            }
            if (!bent || y >= 0) { X = x + (swayK == 0 ? 0 : swayAt(swayK, swayH, -y)); Y = y * (y < 0 ? flat : 1); C = 1; S = 0; return; }
            double u = -y / bStep;
            int i = (int) u;
            if (i > BN) i = BN;
            double f = u - i;
            C = bC[i] + (bC[i + 1] - bC[i]) * f;
            S = bS[i] + (bS[i + 1] - bS[i]) * f;
            X = bX[i] + (bX[i + 1] - bX[i]) * f + x * C;
            Y = -(bY[i] + (bY[i + 1] - bY[i]) * f) + x * S;
            if (swayK != 0) X += swayAt(swayK, swayH, -Y);
            if (flat != 1 && Y < 0) Y *= flat;
        }

        /** True where a tip must not be drawn below the ground line: on what fell, and in the litter. */
        boolean ground() { return raw || (broke && up && !measure); }

        /**
         * How much of the pressing a point of the standing plant takes, 0..1: the trunk itself is stiff (it keeps its
         * thickness and lies where the pose put it), what stands off it - limbs, twigs, leaves - gives way.
         */
        double loose(double x, double y) {
            if (!axis) return 1;
            double ax;
            if (y >= axY[0]) ax = axX[0];
            else if (y <= axY[AX_N]) ax = axX[AX_N];
            else {
                int i = 0;
                while (i < AX_N - 1 && y < axY[i + 1]) i++;
                double f = (axY[i] - y) / Math.max(1e-9, axY[i] - axY[i + 1]);
                ax = axX[i] + (axX[i + 1] - axX[i]) * f;
            }
            return Lit.smooth((Math.abs(x - ax) - rig0) / (rig1 - rig0));
        }

        /** t on the current curve at {@code hgt} px above the root (the curve runs upwards). */
        double tAt(double hgt) {
            double lo = 0, hi = 1;
            if (-curveY(1) <= hgt) return 1;
            for (int k = 0; k < 26; k++) { double mid = (lo + hi) / 2; if (-curveY(mid) < hgt) lo = mid; else hi = mid; }
            return (lo + hi) / 2;
        }

        /** The height above the ground of the standing plant's point (bx, by) once it is turned by {@code th} about the hinge and shifted down by {@code ty}. */
        private double lies(double th, double ty, double bx, double by) {
            double a = side * th;
            return -(pvy + (bx - pvx) * Math.sin(a) + (by - pvy) * Math.cos(a) + ty);
        }

        /** The angle at which the point (bx, by) has come down to {@code rest} px above the ground (or as low as it gets). */
        private double settle(double ty, double bx, double by, double rest) {
            double best = 0.02, bestH = lies(best, ty, bx, by);
            for (double th = 0.04; th <= 3.05; th += 0.02) {
                double hgt = lies(th, ty, bx, by);
                if (hgt <= rest) {
                    double lo = th - 0.02, hi = th;
                    for (int k = 0; k < 24; k++) { double mid = (lo + hi) / 2; if (lies(mid, ty, bx, by) <= rest) hi = mid; else lo = mid; }
                    return hi;
                }
                if (hgt < bestH) { bestH = hgt; best = th; }
            }
            return best;
        }

        /**
         * The current curve is the trunk. On a broken tree this fixes the break and the pose of what came down, and
         * from here to {@link #endTrunk} every strip is cut at the break. The break is at a height between
         * {@code brkLo} and {@code brkHi} (the tree's own, by its seed); (bearX, bearY) is the point of the standing
         * plant that carries what fell on the ground (the end of a trunk that runs to the top, the middle of a crown
         * that sits on a short one), {@code rest} px above the ground; {@code bareTo} the height the trunk is bare to;
         * {@code baseHalf} the stump's half-width at the ground; {@code offShare} how many of these trees come off the
         * stump altogether at the end (the others stay hinged to it by a strip of bark).
         */
        void trunk(double brkLo, double brkHi, double bearX, double bearY, double rest, double bareTo, double baseHalf, double offShare) {
            onTrunk = true;
            double brkH = brkLo + (brkHi - brkLo) * hash(bseed + 17);
            tB = tAt(brkH);
            tBare = tAt(bareTo);
            brkY = curveY(tB);
            hx = curveX(tB);
            bhw = qw0 + (qw1 - qw0) * tB + qwb * 4 * tB * (1 - tB);
            if (!broke) return;
            for (int i = 0; i <= AX_N; i++) { axX[i] = curveX(i / (double) AX_N); axY[i] = curveY(i / (double) AX_N); }
            axis = true;
            rig0 = bhw * 1.2 + 0.3; rig1 = bhw * 3.4 + 2.5;
            pvx = hx + side * bhw; pvy = brkY;
            // hinged: turned about the corner until the far end rests. Off the stump: the same corner on the ground beside it
            double thH = settle(0, bearX, bearY, rest);
            boolean comesOff = hash(bseed + 23) < offShare;
            double gap = 1.2 + 2.2 * hash(bseed + 29) + bhw * 0.35;
            double tdx = side * (baseHalf + gap) - pvx, tdy = -pvy - 0.15, thD = settle(tdy, bearX, bearY, rest);
            double f = Lit.smooth((stage - SNAP) / (DOWN - SNAP));
            off = comesOff ? Lit.smooth((stage - DOWN) / (OFF - DOWN)) : 0;
            theta = HANG_0 + (thH - HANG_0) * f + (thD - thH) * off;
            if (thH < HANG_0) theta = thH + (thD - thH) * off;
            fC = Math.cos(side * theta); fS = Math.sin(side * theta);
            fTx = tdx * off; fTy = tdy * off;
            restX = pvx + (bearX - pvx) * fC - (bearY - pvy) * fS + fTx;
        }

        /** What is drawn next stands on the stump (the roots, the foot). */
        void stump() { if (broke) up = false; }

        /** What is drawn next hangs on the standing plant at height y (a negative number): above the break it came down with the rest. */
        void part(double y) { if (broke) up = y < brkY; }

        /** The end of the trunk: the break itself (splinters on both faces, torn bark, the fibres that still hold), a crack on a tree that is only bent. */
        void endTrunk() {
            if (!measure) {
                if (broke) breakFaces();
                else if (stage > CRACK) crack();
            }
            onTrunk = false;
            if (broke) up = true;
        }

        /** A tree that is bent but still whole: the wood has begun to split where it will break (the side under tension). */
        private void crack() {
            double len = Math.min(1, (stage - CRACK) / (SNAP - CRACK));
            double t0 = tAt(-brkY - 1.2 - 1.5 * len), t1 = tAt(-brkY + 2.5 + 5 * len), a = -side;
            if (t1 - t0 < 1e-4 || bhw * px < 0.8) return;
            // a split that runs up the grain: a dark line, pale wood showing in it, pointed at both ends
            double tm = t0 + (t1 - t0) * 0.42, wd = 0.10 + 0.14 * len;
            at(t0, a * 0.66); double x0 = X, y0 = Y;
            at(t1, a * 0.60); double x1 = X, y1 = Y;
            at(tm, a * (0.66 - wd * 1.3)); double xa = X, ya = Y;
            at(tm, a * (0.66 + wd * 1.3)); double xb = X, yb = Y;
            tri(tear, x0, y0, xa, ya, xb, yb);
            tri(tear, x1, y1, xa, ya, xb, yb);
            at(tm, a * (0.66 - wd * 0.5)); xa = X; ya = Y;
            at(tm, a * (0.66 + wd * 0.9)); xb = X; yb = Y;
            at(t0 + (t1 - t0) * 0.12, a * 0.66); x0 = X; y0 = Y;
            at(t0 + (t1 - t0) * 0.88, a * 0.62); x1 = X; y1 = Y;
            tri(pale, x0, y0, xa, ya, xb, yb);
            tri(pale, x1, y1, xa, ya, xb, yb);
        }

        /**
         * The two faces of the break. The wood tears: long pale splinters stand on the stump (the longest on the side
         * away from the fall, where it was pulled apart), their counterparts stick out of the end of what fell, the
         * bark is peeled back from both, and near the hinge a few fibres still run from one to the other.
         */
        private void breakFaces() {
            final double w = bhw, y0 = brkY;
            final int n = (int) Model.clamp(Math.round(w * 1.1 + 2), 3, 7);
            final double maxH = Model.clamp(w * 1.5 + 1.6, 2.6, 9.5) * splinter, b = w / n * 1.2, peel = Model.clamp(w * 0.45, 0.9, 2.6) * Math.min(1, splinter * 1.5);
            for (int part = 0; part < 2; part++) {
                up = part == 1;
                final double dir = part == 0 ? -1 : 1;                 // (the stump's splinters point up, the others down the standing plant)
                for (int i = 0; i < n; i++) {
                    double u1 = hash(bseed + part * 211 + i * 31 + 1), u2 = hash(bseed + part * 211 + i * 37 + 2), u3 = hash(bseed + part * 211 + i * 41 + 3);
                    double x = hx - w + (i + (part == 0 ? 0.5 : 1.0)) * 2 * w / n;
                    if (part == 1 && i == n - 1) x = hx - w + 0.45 * w / n;
                    double s = (x - hx) / w * side, bias = 0.80 - 0.42 * s;       // (s: -1 on the side that tore, +1 at the hinge)
                    double hgt = maxH * (0.14 + 0.86 * u1 * u1) * bias * (part == 0 ? 1 : 0.78);
                    if (part == 0 && i == (side > 0 ? 0 : n - 1)) hgt = maxH * (0.95 + 0.35 * u1);       // the long sliver at the torn edge
                    double tipX = x + (u2 - 0.5) * b * 1.1, tipY = y0 + dir * hgt;
                    triL(pale, x - b, y0 - dir * 0.35, x + b, y0 - dir * 0.35, tipX, tipY);
                    triL(paleShade, x + b * 0.1, y0 - dir * 0.35, x + b, y0 - dir * 0.35, tipX, tipY);
                    // the bark peeled back from the edge: bare wood a little way down the outside, ragged
                    triL(pale, x - b, y0, x + b, y0, x + (u3 - 0.5) * b, y0 - dir * peel * (0.5 + 0.9 * u3));
                }
                // the torn edge of the bark: a dark tooth at either side and one or two between
                for (int i = 0; i < 4; i++) {
                    double u1 = hash(bseed + part * 311 + i * 43 + 5), x = i == 0 ? hx - w + b * 0.3 : i == 1 ? hx + w - b * 0.3 : hx + (u1 - 0.5) * w * 1.4;
                    double hgt = maxH * (0.16 + 0.22 * u1);
                    triL(tear, x - b * 0.42, y0 - dir * (peel * 0.5 + 0.3), x + b * 0.42, y0 - dir * (peel * 0.5 + 0.3), x + (u1 - 0.5) * b * 0.6, y0 + dir * hgt);
                }
            }
            // the fibres that still hold: thin strips from the stump's face to the end of what fell, where the two are still close
            if (off < 0.35) {
                for (int i = 0; i < 4; i++) {
                    double dist = w * (0.12 + 0.50 * i * (0.7 + 0.6 * hash(bseed + i * 59 + 7))), gapPx = 2 * dist * Math.sin(Math.min(theta, Math.PI) / 2);
                    if (i > 0 && gapPx > 6.5 + w) break;
                    double x = pvx - side * dist, tw = Math.max(0.28, w * 0.09);
                    up = false; warp(x - tw, y0); double ax1 = X, ay1 = Y; warp(x + tw, y0); double ax2 = X, ay2 = Y;
                    up = true; warp(x - tw, y0); double bx1 = X, by1 = Y; warp(x + tw, y0); double bx2 = X, by2 = Y;
                    tri(i % 2 == 0 ? pale : paleShade, ax1, ay1, ax2, ay2, (bx1 + bx2) / 2, (by1 + by2) / 2);
                    tri(i % 2 == 0 ? pale : paleShade, bx1, by1, bx2, by2, (ax1 + ax2) / 2, (ay1 + ay2) / 2);
                }
                // and the bark that buckled on the inside of the fold
                double k = Model.clamp(w * 0.7, 1.4, 3.6);
                up = false; warp(pvx, y0 + k); double sx = X, sy = Y; warp(pvx - side * w * 0.5, y0); double mx = X, my = Y;
                up = true; warp(pvx, y0 - k);
                tri(bark != null ? wood : tear, sx, sy, mx, my, X, Y);         // (a cactus has no bark colour: its skin's torn edge)
            }
            // snow: a cap on the stump, and along the upper side of the bare trunk that lies
            if (snowOn) {
                up = false;
                driftW(hx, y0 - maxH * 0.25, w * (0.7 + 0.3 * snow), maxH * (0.25 + 0.3 * snow), bseed + 71);
                if (theta > 0.5 && tBare - tB > 0.03) {
                    // (heaps of it along the top of the log, not a plank)
                    onTrunk = false; up = true;
                    at(tB, -side); double ex0 = X, ey0 = Y;
                    at(tBare, -side); double ex1 = X, ey1 = Y;
                    double run = Math.hypot(ex1 - ex0, ey1 - ey0);
                    int heaps = (int) Model.clamp(Math.round(run / 5.5), 1, 6);
                    for (int i = 0; i < heaps; i++) {
                        double f = (i + 0.5 + (hash(bseed + i * 83 + 9) - 0.5) * 0.4) / heaps;
                        driftW(ex0 + (ex1 - ex0) * f, ey0 + (ey1 - ey0) * f + 0.5, run / heaps * (0.55 + 0.25 * hash(bseed + i * 89 + 10)), 0.9 + 2.2 * snow * (0.6 + 0.6 * hash(bseed + i * 97 + 11)), bseed + i * 131 + 12);
                    }
                }
            }
            up = true;
        }

        void curve(double x0, double y0, double cx, double cy, double x1, double y1, double w0, double w1, double bulge) {
            qx0 = x0; qy0 = y0; qcx = cx; qcy = cy; qx1 = x1; qy1 = y1; qw0 = w0; qw1 = w1; qwb = bulge;
        }

        double curveX(double t) { double m = 1 - t; return m * m * qx0 + 2 * m * t * qcx + t * t * qx1; }

        double curveY(double t) { double m = 1 - t; return m * m * qy0 + 2 * m * t * qcy + t * t * qy1; }

        /** The bent point of the current limb at {@code t}, {@code a} half-widths to its side; (TX, TY) is the bent direction there. */
        void at(double t, double a) {
            double m = 1 - t, x = m * m * qx0 + 2 * m * t * qcx + t * t * qx1, y = m * m * qy0 + 2 * m * t * qcy + t * t * qy1;
            double tx = m * (qcx - qx0) + t * (qx1 - qcx), ty = m * (qcy - qy0) + t * (qy1 - qcy), l = Math.sqrt(tx * tx + ty * ty);
            if (l < 1e-9) { tx = 0; ty = -1; } else { tx /= l; ty /= l; }
            double w = (qw0 + (qw1 - qw0) * t + qwb * 4 * t * m) * a;
            if (broke && onTrunk && !inStrip) up = t > tB;
            warp(x - ty * w, y + tx * w);
            TX = tx * C - ty * S; TY = tx * S + ty * C;
        }

        /** The piece of the current limb from t0 to t1, between a0 and a1 half-widths off its centre line, as one polygon. */
        void strip(Path2D.Float p, double t0, double t1, double a0, double a1, int segs) {
            if (!broke || !onTrunk || inStrip) { stripRaw(p, t0, t1, a0, a1, segs); return; }
            // the trunk of a broken tree: the piece below the break stands, the piece above it lies
            inStrip = true;
            if (t0 < tB - 1e-6 && t1 > tB + 1e-6) {
                up = false;
                stripRaw(p, t0, tB, a0, a1, Math.max(1, (int) Math.round(segs * (tB - t0) / (t1 - t0))));
                up = true;
                stripRaw(p, tB, t1, a0, a1, Math.max(1, (int) Math.round(segs * (t1 - tB) / (t1 - t0))));
            } else {
                up = (t0 + t1) / 2 > tB;
                stripRaw(p, t0, t1, a0, a1, segs);
            }
            inStrip = false;
        }

        private void stripRaw(Path2D.Float p, double t0, double t1, double a0, double a1, int segs) {
            if (segs > MAXSEG) segs = MAXSEG;
            for (int i = 0; i <= segs; i++) {
                double t = t0 + (t1 - t0) * i / segs;
                at(t, a0); ax[i] = (float) X; ay[i] = (float) Y;
                at(t, a1); bx[i] = (float) X; by[i] = (float) Y;
            }
            p.moveTo(ax[0], ay[0]);
            for (int i = 1; i <= segs; i++) p.lineTo(ax[i], ay[i]);
            for (int i = segs; i >= 0; i--) p.lineTo(bx[i], by[i]);
            p.closePath();
        }

        /** A triangle between three points that are already bent (turned the way every other polygon runs, so that overlaps add up). */
        static void tri(Path2D.Float p, double x1, double y1, double x2, double y2, double x3, double y3) {
            p.moveTo((float) x1, (float) y1);
            if ((x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1) < 0) { p.lineTo((float) x3, (float) y3); p.lineTo((float) x2, (float) y2); }
            else { p.lineTo((float) x2, (float) y2); p.lineTo((float) x3, (float) y3); }
            p.closePath();
        }

        /** A triangle between three points of the straight plant. */
        void triL(Path2D.Float p, double x1, double y1, double x2, double y2, double x3, double y3) {
            warp(x1, y1); double a = X, b = Y;
            warp(x2, y2); double c2 = X, d = Y;
            warp(x3, y3);
            tri(p, a, b, c2, d, X, Y);
        }

        /** A round blob (an n-gon, n = 8, 12 or 24) about a point of the straight plant; it turns with the bend. */
        void blob(Path2D.Float p, double x, double y, double rx, double ry, int n) {
            warp(x, y);
            double x0 = X, y0 = Y, c = C, s = S;
            int step = 24 / n;
            for (int i = 0; i < 24; i += step) {
                double ex = RC[i] * rx, ey = RS[i] * ry;
                float fx = (float) (x0 + ex * c - ey * s), fy = (float) (y0 + ex * s + ey * c);
                if (i == 0) p.moveTo(fx, fy); else p.lineTo(fx, fy);
            }
            p.closePath();
        }

        /** Snow lying on a twig, a bough or a top: a small deposit sitting on a point of the straight plant. */
        void pillow(double x, double y, double r) {
            snowAt(x, y, 0, r * (0.8 + 0.7 * snow), r * (0.5 + 0.9 * snow), Double.doubleToLongBits(x * 31 + y));
        }

        /**
         * Snow on the point (x, y) of the standing plant, {@code lift} px above it. Snow lies on what is up now: on a
         * part that came down it sits on the side that faces the sky there, not on the side that did while it stood.
         */
        void snowAt(double x, double y, double lift, double rx, double ry, long seed) {
            if (broke && up) {
                if (measure) return;
                warp(x, y);
                driftW(X, Y - lift, rx, ry, seed);
            } else drift(x, y - lift, rx, ry, seed);
        }

        /** {@link #drift} at a place that is already where it is drawn (nothing is bent or turned). */
        void driftW(double x, double y, double rx, double ry, long seed) {
            final int n = 10;
            for (int pass = 0; pass < 2; pass++) {
                Path2D.Float p = pass == 0 ? snowShade : snowP;
                double lift = pass == 0 ? 0 : ry * 0.2, wx = pass == 0 ? rx : rx * 0.94;
                for (int i = 0; i <= n; i++) {
                    double a = Math.PI * i / n, sn = Math.sin(a);
                    float fx = (float) (x - wx * Math.cos(a) + (hash(seed + i * 53 + 1) - 0.5) * rx * 0.12), fy = (float) (y - lift - ry * Math.sqrt(sn) * (0.84 + 0.32 * hash(seed + i * 37)));
                    if (i == 0) p.moveTo(fx, fy); else p.lineTo(fx, fy);
                }
                for (int i = n - 1; i >= 1; i--) {
                    double a = Math.PI * i / n;
                    p.lineTo((float) (x - wx * 0.9 * Math.cos(a)), (float) (y - lift - ry * Math.sin(a) * 0.3));
                }
                p.closePath();
            }
        }

        /**
         * Snow as it lies on something rounded (a leaf cluster, a bough): its underside follows the dome beneath it from
         * (x - rx, y) over the top to (x + rx, y), its upper edge is heaped unevenly up to ry above that, and the lower
         * rim is in its own blue-grey shade (the same shape, drawn first, a little lower and wider).
         */
        void drift(double x, double y, double rx, double ry, long seed) {
            final int n = 10;
            for (int pass = 0; pass < 2; pass++) {
                Path2D.Float p = pass == 0 ? snowShade : snowP;
                double lift = pass == 0 ? 0 : ry * 0.2, wx = pass == 0 ? rx : rx * 0.94;
                for (int i = 0; i <= n; i++) {
                    double a = Math.PI * i / n, sn = Math.sin(a);
                    warp(x - wx * Math.cos(a) + (hash(seed + i * 53 + 1) - 0.5) * rx * 0.12, y - lift - ry * Math.sqrt(sn) * (0.84 + 0.32 * hash(seed + i * 37)));
                    if (i == 0) p.moveTo((float) X, (float) Y); else p.lineTo((float) X, (float) Y);
                }
                for (int i = n - 1; i >= 1; i--) {
                    double a = Math.PI * i / n;
                    warp(x - wx * 0.9 * Math.cos(a), y - lift - ry * Math.sin(a) * 0.3);
                    p.lineTo((float) X, (float) Y);
                }
                p.closePath();
            }
        }

        /**
         * One leaf: centre (x, y) of the straight plant, pointing along the unit vector (dx, dy), half-length a, half-width b.
         * {@code pf} takes the blade, {@code ph} its shaded half (the lower one), {@code pr} its midrib and stalk.
         */
        void leaf(Path2D.Float pf, Path2D.Float ph, Path2D.Float pr, double x, double y, double dx, double dy, double a, double b, int shape) {
            warp(x, y);
            double c = dx * C - dy * S, s = dx * S + dy * C;
            if (broke && up) {
                // (on what came down the leaves hang towards the ground it lies on, not towards the foot of the tree)
                s += 0.5 * Math.min(1, theta);
                double l = Math.sqrt(c * c + s * s);
                if (l > 1e-6) { c /= l; s /= l; }
            }
            float x0 = (float) X, y0 = (float) Y, ux = (float) (c * a), uy = (float) (s * a), vx = (float) (-s * b), vy = (float) (c * b);
            if (lod <= 1) {
                pf.moveTo(x0 + ux, y0 + uy);
                pf.lineTo(x0 - 0.15f * ux + vx, y0 - 0.15f * uy + vy);
                pf.lineTo(x0 - ux, y0 - uy);
                pf.lineTo(x0 - 0.15f * ux - vx, y0 - 0.15f * uy - vy);
                pf.closePath();
                return;
            }
            float[] su = SU[shape], sv = SV[shape];
            int n = su.length;
            pf.moveTo(x0 + ux, y0 + uy);
            for (int i = 0; i < n; i++) pf.lineTo(x0 + su[i] * ux + sv[i] * vx, y0 + su[i] * uy + sv[i] * vy);
            pf.lineTo(x0 - ux, y0 - uy);
            for (int i = n - 1; i >= 0; i--) pf.lineTo(x0 + su[i] * ux - sv[i] * vx, y0 + su[i] * uy - sv[i] * vy);
            pf.closePath();
            if (ph != null) {
                // the half that faces the ground is the shaded one
                if (c >= 0) {
                    ph.moveTo(x0 + ux, y0 + uy);
                    for (int i = 0; i < n; i++) ph.lineTo(x0 + su[i] * ux + sv[i] * vx, y0 + su[i] * uy + sv[i] * vy);
                    ph.lineTo(x0 - ux, y0 - uy);
                } else {
                    ph.moveTo(x0 - ux, y0 - uy);
                    for (int i = n - 1; i >= 0; i--) ph.lineTo(x0 + su[i] * ux - sv[i] * vx, y0 + su[i] * uy - sv[i] * vy);
                    ph.lineTo(x0 + ux, y0 + uy);
                }
                ph.closePath();
            }
            if (pr != null) {
                pr.moveTo(x0 + 0.92f * ux, y0 + 0.92f * uy);
                pr.lineTo(x0 - 1.32f * ux + 0.07f * vx, y0 - 1.32f * uy + 0.07f * vy);
                pr.lineTo(x0 - 1.32f * ux - 0.07f * vx, y0 - 1.32f * uy - 0.07f * vy);
                pr.closePath();
            }
        }

        /**
         * One cluster of leaves about (cx, cy) of the straight plant, radius cr ({@code flat} squashes it), lit {@code light}
         * (0 deep inside the crown .. 1 on top of it). A {@code back} cluster hangs on the far side of the limbs: darker,
         * and drawn before them.
         */
        void cluster(long seed, double cx, double cy, double cr, double flat, double light, boolean back, int nL,
                     double len, double wr, int shape, double droop, boolean snowy) {
            warp(cx, cy);
            double m = cr * 1.5 + len;
            if (X + m < cx0 || X - m > cx1 || Y + m < cy0 || Y - m > cy1) return;
            seed(seed);
            double fy = cr * flat;
            // (on what came down the snow lies on the clusters that are on top of the heap now)
            final boolean lying = broke && up;
            if (lying) {
                snowy = -Y > heap * 0.45;
                // (and the light falls on the heap as it lies: its top is bright, what is pressed on the ground is in its shade)
                double lie = Math.min(1, theta / 1.2);
                light += lie * (0.16 + 0.80 * Model.clamp(-Y / heap, 0, 1) + (hash(seed + 9) - 0.5) * 0.24 - light);
            }
            if (lod == 0) {
                if (back) blob(backHi, cx, cy, cr, fy, 8);
                else {
                    // (the tone its leaves would have on average, and a lighter cap where they catch the light)
                    double t = light - 0.30;
                    int k = t < 0.18 ? 0 : t < 0.42 ? 1 : t < 0.68 ? 2 : t < 0.92 ? 3 : 4;
                    blob(full[k], cx, cy, cr * 0.97, fy * 0.97, 8);
                    if (k < 4) blob(full[k + 1], cx - cr * 0.14, cy - fy * 0.26, cr * 0.52, fy * 0.52, 8);
                }
                if (snowOn && snowy && hash(seed + 3) < 0.4 + snow) snowAt(cx, cy, fy * 0.35, cr * (0.42 + 0.5 * snow), fy * (0.2 + 0.4 * snow), seed);
                return;
            }
            blob(back ? backUnder : under, cx, cy, cr * (back ? 0.46 : 0.74), fy * (back ? 0.46 : 0.74), 12);
            // (snow gathers on the upper side of a cluster: on more of them, and deeper, the more has fallen)
            if (snowOn && snowy && hash(seed + 3) < 0.4 + snow) snowAt(cx + (hash(seed + 5) - 0.5) * cr * 0.3, cy, fy * 0.4, cr * (0.42 + 0.5 * snow), fy * (0.2 + 0.4 * snow), seed);
            double ph = rnd() * 6.283185307, cp = Math.cos(ph), sp = Math.sin(ph), a = len * 0.5, b = a * wr;
            int sh = lod == 2 && shape == OAK ? BLADE : shape;
            for (int i = 0; i < nL; i++) {
                double r1 = rnd(), r2 = rnd(), r3 = rnd(), r4 = rnd(), r5 = rnd();
                if (r4 >= keep) continue;
                int gi = i & (GN - 1);
                double gc = GC[gi] * cp - GS[gi] * sp, gs = GS[gi] * cp + GC[gi] * sp, rr = cr * Math.sqrt((i + 0.5) / nL);
                double lx = cx + gc * rr + (r1 - 0.5) * cr * 0.3, ly = cy + (gs * rr + (r3 - 0.5) * cr * 0.3) * flat;
                // a leaf points out of its cluster and hangs a little
                double dx = gc + (r2 - 0.5) * 1.7, dy = gs * flat + droop + (r1 - 0.5) * 1.1, dl = Math.sqrt(dx * dx + dy * dy);
                if (dl < 1e-6) { dx = 0; dy = 1; } else { dx /= dl; dy /= dl; }
                double sz = 0.78 + 0.44 * r3;
                // light from above (and a little from the left): the leaves on top of a cluster catch it
                double t = light + 0.30 * (cy - ly) / fy + 0.10 * (cx - lx) / cr + (r2 - 0.5) * 0.5;
                int k = t < 0.18 ? 0 : t < 0.42 ? 1 : t < 0.68 ? 2 : t < 0.92 ? 3 : 4;
                if (back) leaf(k <= 2 ? backLo : backHi, null, null, lx, ly, dx, dy, a * sz, b * sz, sh);
                else if (snowOn && snowy && r5 < snow * 0.85 && (lying ? r1 < 0.55 : ly < cy + fy * (snow - 0.45))) leaf(snowP, null, null, lx, ly, dx, dy, a * sz, b * sz, sh);
                else leaf(full[k], half[k], rib[k], lx, ly, dx, dy, a * sz, b * sz, sh);
            }
        }

        void fill(Path2D.Float p, Color col) {
            if (p == null || p.getCurrentPoint() == null) return;
            g.setColor(col);
            g.fill(p);
        }

        /** Paint everything gathered, back to front: far foliage, wood, near foliage dark to light, snow. */
        void flush() {
            for (Object[] f : later) fill((Path2D.Float) f[0], (Color) f[1]);
            fill(backUnder, cBackUnder);
            fill(backLo, cBackLo);
            fill(backHi, cBackHi);
            fill(wood, bark);
            fill(trunk, trunkC);
            fill(woodShade, barkDark);
            fill(woodLite, barkLite);
            fill(woodMark, barkMark);
            fill(pale, cPale);
            fill(paleShade, cPaleShade);
            fill(tear, cTear);
            fill(under, cUnder);
            for (int k = 0; k < 5; k++) {
                fill(full[k], tone[k]);
                fill(half[k], halfTone[k]);
                fill(rib[k], ribTone[k]);
            }
            fill(snowShade, snowShadeC);
            fill(snowP, snowC);
        }

        /** Every path gathered so far (what {@link #flush} will paint). */
        java.util.List<Path2D.Float> paths() {
            java.util.ArrayList<Path2D.Float> l = new java.util.ArrayList<>();
            for (Object[] f : later) l.add((Path2D.Float) f[0]);
            java.util.Collections.addAll(l, backUnder, backLo, backHi, wood, trunk, woodShade, woodLite, woodMark, pale, paleShade, tear, under, snowShade, snowP);
            for (int k = 0; k < 5; k++) { l.add(full[k]); if (half[k] != null) l.add(half[k]); if (rib[k] != null) l.add(rib[k]); }
            return l;
        }
    }

    // ------------------------------------------------------------------ entry

    /**
     * One tree, drawn straight onto {@code g} (the far-zoom version baked into the ground tiles, the Debug line-up, a
     * tree seen through haze): {@code leanIn} is the shear from wind (0 for the static version), {@code haze} 0..1 of
     * atmosphere in front of it.
     */
    static void paint(Graphics2D g, Model.Obj o, Lit lit, double leanIn, double haze) {
        String v = variant(o);
        AffineTransform old = g.getTransform();
        g.translate(o.x, o.y);
        // (a snapped crown lies up to its own height to one side; a palm's fronds reach past its width)
        double reach = (o.w + o.h) * 1.6 + 8;
        if (g.hitClip((int) Math.floor(-reach), (int) Math.floor(-reach), (int) Math.ceil(2 * reach), (int) Math.ceil(2 * reach))) {
            AffineTransform at = g.getTransform();
            core(g, o, v, lit, leanIn, haze, Math.hypot(at.getScaleX(), at.getShearY()));
        }
        g.setTransform(old);
    }

    /** The share of the wind's lean a variant takes at its top (a cactus hardly moves), as the old painter had it. */
    static double swayShare(String v) {
        return switch (v) {
            case "bush", "shrub" -> 0.4;
            case "birch" -> 1.1;
            case "cactus" -> 0.10;
            case "acacia" -> 0.8;
            case "dead" -> 0.35;
            default -> 1;                                   // oak, palm, pine
        };
    }

    /** The height (world px above the root) at which a variant's sway is the old painter's shear; below it the stem is stiffer. */
    static double swayHeight(String v, Model.Obj o) {
        return switch (v) {
            case "bush" -> o.h * 1.4;
            case "shrub" -> o.h * 1.3;
            case "pine" -> o.h * 1.115;
            default -> o.h;
        };
    }

    /**
     * The sideways push of the wind at {@code hh} world px above the root, for the old shear {@code k} ({@code x' = x + k*y}):
     * the same at height {@code h}, less below it, so a tree leans out of a root that stays where it is.
     */
    static double swayAt(double k, double h, double hh) { return hh <= 0 ? 0 : -k * hh * Math.sqrt(hh / h); }

    // ------------------------------------------------------------------ storm damage: bent, cracked, hanging, down

    /**
     * The ladder of storm damage (a pure function of {@code Obj.damage}, nothing is remembered): to {@link #CRACK} the
     * tree is bent and loses leaves and twigs; from there the wood splits where it will break; past {@link #SNAP} the
     * trunk has snapped and the top hangs over on the splintered hinge, lower and lower until at {@link #DOWN} it lies
     * on the ground; by {@link #OFF} the trees that come off their stump have slid off it. A bush and a shrub do not
     * snap: past SNAP they are pressed flat and torn.
     */
    static final double CRACK = 0.24, SNAP = 0.40, DOWN = 0.64, OFF = 0.80;
    /** The angle (radians from upright) the top hangs at the moment the trunk has snapped. */
    static final double HANG_0 = 0.22;

    /** Does this kind of plant snap (true) or is it only flattened (bush, shrub)? */
    static boolean snaps(String v) { return !"bush".equals(v) && !"shrub".equals(v); }

    /** Is a plant of this kind broken at this damage? */
    static boolean broken(String v, double damage) { return damage > SNAP + 1e-9 && snaps(v); }

    /** The tree about the origin of {@code g} (its root), at {@code px} device pixels per world unit; {@code lit} null = unlit colours. */
    private static void core(Graphics2D g, Model.Obj o, String v, Lit lit, double leanIn, double haze, double px) {
        final double dmg = Model.clamp(o.damage, 0, 1);
        final boolean broke = broken(v, dmg);
        double lean = leanIn, bend = 0, side = 0;
        if (dmg > 0.04) {
            // v2.3 storm damage: the tree is bent to the side it will fall to; a broken one neither bends nor sways
            side = Scene.hash01(o.seed * 77 + 5) < 0.5 ? -1 : 1;
            if (broke) lean = 0;
            else bend = side * dmg * 0.9;
        }
        double sunk = 0, heap = 0;
        if (broke) {
            // what fell is built once without drawing, to learn how far it would reach under the ground and how high it lies
            Pen m = pen(g, null, 0, px, o, v, 0, dmg, side, 0);
            m.measure = true;
            m.cx0 = m.cy0 = -1e18; m.cx1 = m.cy1 = 1e18;
            build(m, o, v, 0);
            sunk = Math.max(0, m.mDown);
            heap = Math.max(1, -m.mTop);
        }
        Pen p = pen(g, lit, haze, px, o, v, lod(v, px), dmg, side, lean);
        p.sunk = sunk; p.heap = heap; p.band = Math.max(2.4, heap * 0.24);
        if (!snaps(v)) { p.flat = 1 - 0.50 * Lit.smooth((dmg - SNAP) / 0.4); p.keep = 1 - 0.45 * dmg - 0.2 * Lit.smooth((dmg - SNAP) / 0.4); }

        build(p, o, v, bend);
        if (dmg > LITTER && p.lod >= 1) litter(p, o, v, dmg);
        if (dmg > LITTER) {
            // (nothing of a damaged tree is drawn under the ground line: what lies there is cut off flat, as the ground does)
            java.awt.Shape clip = g.getClip();
            g.clip(new Rectangle2D.Double(-1e5, -1e5, 2e5, 1e5 + 1.0));
            p.flush();
            g.setClip(clip);
        } else p.flush();
    }

    private static Pen pen(Graphics2D g, Lit lit, double haze, double px, Model.Obj o, String v, int lod, double dmg, double side, double lean) {
        Pen p = new Pen(g, lit, haze, px, o, lod);
        p.swayK = lean * swayShare(v);
        p.swayH = swayHeight(v, o);
        p.keep = 1 - 0.45 * dmg;                                    // a storm strips leaves
        p.stage = dmg; p.side = side == 0 ? 1 : side;
        p.bseed = mix(o.seed * 131 + 977);
        p.broke = broken(v, dmg);
        return p;
    }

    private static void build(Pen p, Model.Obj o, String v, double bend) {
        Random r = new Random(o.seed * 2654435761L + 3);
        switch (v) {
            case "bush" -> bush(p, o, r, bend);
            case "birch" -> birch(p, o, r, bend);
            case "oak" -> oak(p, o, bend);
            case "palm" -> palm(p, o, r, bend);
            case "cactus" -> cactus(p, o, r, bend);
            case "acacia" -> acacia(p, o, r, bend);
            case "shrub" -> shrub(p, o, r, bend);
            case "dead" -> dead(p, o, r, bend);
            default -> pine(p, o, bend);
        }
    }

    /**
     * How a broken tree of this kind and size lies (tests): {height of the break, half-width of the trunk there, side of
     * the fall, angle from upright, how far it has come off the stump 0..1, x and y of the hinge corner of what fell,
     * how far under the ground it would reach unpressed, height of the heap}; null when the tree is not broken.
     */
    static double[] breakPose(Model.Obj o) {
        String v = variant(o);
        double dmg = Model.clamp(o.damage, 0, 1);
        if (!broken(v, dmg)) return null;
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = img.createGraphics();
        Pen m = pen(g, null, 0, 1, o, v, 0, dmg, Scene.hash01(o.seed * 77 + 5) < 0.5 ? -1 : 1, 0);
        m.measure = true;
        m.cx0 = m.cy0 = -1e18; m.cx1 = m.cy1 = 1e18;
        build(m, o, v, 0);
        g.dispose();
        return new double[]{-m.brkY, m.bhw, m.side, m.theta, m.off, m.pvx + m.fTx, m.pvy + m.fTy, Math.max(0, m.mDown), -m.mTop};
    }

    /** From this much damage on there is litter under the tree. */
    static final double LITTER = 0.10;

    /**
     * What the storm tore off: leaves (needles, bits of frond, bare twigs) scattered on the ground about the tree, and
     * a few broken branches lying there, more of both the worse the damage. Everything lies ON the ground line.
     */
    private static void litter(Pen p, Model.Obj o, String v, double dmg) {
        final double amount = Model.clamp((dmg - LITTER) / 0.5, 0, 1), w = o.w, h = o.h, side = p.side;
        // where it lies: about the foot of a standing tree (more to the side it leans to), along what fell of a broken one
        final double x0, x1;
        if (p.broke) { double far = p.restX + side * w * 0.45; x0 = Math.min(-side * (w * 0.3 + 6), far); x1 = Math.max(-side * (w * 0.3 + 6), far); }
        else { x0 = -w * 0.55 - h * 0.12 + side * h * 0.12 * amount; x1 = w * 0.55 + h * 0.12 + side * h * 0.12 * amount; }
        final boolean wasBroke = p.broke, wasUp = p.up;
        p.raw = true; p.ox = 0; p.oy = 0;
        p.broke = false;                                            // (the litter is not part of what fell: it is drawn where it lies)
        final long s0 = mix(o.seed * 613 + 41);
        final int kind = switch (v) { case "pine" -> 1; case "palm" -> 2; case "dead" -> 3; case "cactus" -> 4; default -> 0; };
        if (kind == 4) { p.raw = false; p.broke = wasBroke; return; }      // (a cactus drops nothing but what breaks off it)
        // ---- single leaves / needle sprays / leaflets
        final int nLeaf = kind == 3 ? 0 : (int) Math.round((kind == 2 ? 3 : 5) + (kind == 2 ? 8 : 17) * amount);
        for (int i = 0; i < nLeaf; i++) {
            double u1 = hash(s0 + i * 71 + 1), u2 = hash(s0 + i * 73 + 2), u3 = hash(s0 + i * 79 + 3);
            double x = x0 + (x1 - x0) * u1, dir = u2 < 0.5 ? -1 : 1, tilt = (u3 - 0.5) * 0.5;
            int k = 1 + (int) (u3 * 2.99);
            if (kind == 1) spray(p, x, -0.5, dir * Math.cos(tilt), -Math.abs(Math.sin(tilt)), 3.4 + 2.2 * u2, k);
            else if (kind == 2) {
                double ll = 3 + 3.5 * u2;
                Pen.tri(p.full[k], x, -0.2, x + dir * ll, -0.25 - 0.9 * u3, x + dir * ll * 0.45, -1.3 - 0.5 * u3);
            } else {
                double a = p.lfLen * 0.5 * (0.8 + 0.4 * u2), b = a * p.lfWr;
                p.leaf(p.full[k], p.half[k], p.rib[k], x, -b * 0.75 - 0.15, dir * Math.cos(tilt), Math.sin(tilt) * 0.4, a, b, p.lod == 2 && p.lfShape == OAK ? BLADE : p.lfShape);
            }
        }
        // ---- broken branches
        final int nTwig = dmg < 0.18 ? 0 : 1 + (int) Math.floor(amount * (kind == 1 ? 1.4 : 2.6) + hash(s0 + 5) * 0.8);

        for (int i = 0; i < nTwig; i++) {
            double u1 = hash(s0 + i * 91 + 11), u2 = hash(s0 + i * 97 + 12), u3 = hash(s0 + i * 101 + 13);
            double x = x0 + (x1 - x0) * (0.12 + 0.76 * (i + u1) / nTwig), dir = u2 < 0.5 ? -1 : 1, len = Math.min(h * 0.26, 7 + 9 * u3 + w * 0.08);
            long cs = mix(s0 + i * 7919L + 3);
            switch (kind) {
                case 1 -> {
                    // a bough with its needles, lying on its side
                    p.ox = x; p.oy = 0;
                    len *= 0.62;
                    bough(p, -1.3 - 0.8 * u2, dir * len, -0.9, len, 0.45 + 0.3 * u1, 0.5, false);
                    p.ox = 0;
                    Pen.tri(p.pale, x - 0.5, -1.9 - 0.8 * u2, x + 0.5, -0.9 - 0.8 * u2, x - dir * 1.6, -1.6 - 0.8 * u2);
                }
                case 2 -> frond(p, x, -1.0, x + dir * len * 0.6, -1.0 - len * 0.10, x + dir * len * 1.2, -0.7, len * 1.2, false);
                case 3 -> {
                    branch(p, cs, x, -0.9, x + dir * len, -1.4 - len * 0.12, 0.5 + 0.5 * u1, 1, false);
                    Pen.tri(p.pale, x - 0.2, -1.5, x + 0.2, -0.4, x - dir * 1.5, -1.0);
                }
                default -> {
                    double tipX = x + dir * len, tipY = -1.0 - len * 0.05, wid = 0.45 + 0.5 * u1;
                    p.curve(x, -wid - 0.2, x + dir * len * 0.5, -wid - 0.6 - len * 0.10, tipX, tipY, wid, 0.2, 0);
                    p.strip(p.wood, 0, 1, -1, 1, 3);
                    // its torn end, and the leaves it still carries
                    Pen.tri(p.pale, x, -2 * wid - 0.3, x, -0.1, x - dir * (1.2 + wid), -wid - 0.3);
                    double cr = p.lfCr * (0.55 + 0.25 * u2);
                    p.cluster(cs, tipX - dir * cr * 0.2, -cr * 0.55 - 0.4, cr, 0.62, 0.55 + 0.3 * u3, false, 6 + (int) (u1 * 5), p.lfLen, p.lfWr, p.lfShape, p.lfDroop * 0.3, false);
                    if (len > 11) {
                        p.curve(x + dir * len * 0.45, -wid - 0.9, x + dir * len * 0.6, -wid - 2.6, x + dir * len * 0.72, -cr * 0.9 - 1.4, wid * 0.6, 0.16, 0);
                        p.strip(p.wood, 0, 1, -1, 1, 2);
                        p.cluster(cs ^ 0x9E37L, x + dir * len * 0.72, -cr * 0.9 - 1.2, cr * 0.8, 0.7, 0.75, false, 6, p.lfLen, p.lfWr, p.lfShape, p.lfDroop * 0.3, false);
                    }
                }
            }
        }
        p.raw = false;
        p.broke = wasBroke; p.up = wasUp;
    }

    // ------------------------------------------------------------------ the live pass: one cached picture per tree

    /** Sprites held at most: this many pixels in all (each costs 8 bytes: the unlit picture and the lit one), least recently drawn out first. */
    static final long CACHE_PX = 3_000_000;
    /** A tree whose picture would need a canvas larger than this is drawn straight onto the frame instead. */
    static final long CANVAS_MAX_PX = 2_600_000;
    private static final int WET_STEPS = 8, SNOW_STEPS = 12, DAMAGE_STEPS = 50, PHASES = 4;
    private static final AffineTransform IDENTITY = new AffineTransform();

    /** Everything a tree's unlit picture depends on. */
    private record Key(String variant, long seed, long w, long h, int wet, int snow, int damage, long scale, int phase) {}

    /** One tree's picture: the unlit pixels (premultiplied ARGB), and the same under the light it was last drawn in. */
    private static final class Sprite {
        final int[] master;
        final int w, h, ox, oy;
        final BufferedImage img;
        final int[] lit;
        long light = -1;

        Sprite(int[] master, int w, int h, int ox, int oy) {
            this.master = master; this.w = w; this.h = h; this.ox = ox; this.oy = oy;
            img = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB_PRE);
            lit = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        }
    }

    private static final class Cache {
        final LinkedHashMap<Key, Sprite> map = new LinkedHashMap<>(256, 0.75f, true);
        long pixels;
    }

    private static final Object LOCK = new Object();
    /** (softly held: the collector may take the whole cache back when memory is short; it is refilled as trees are drawn) */
    private static SoftReference<Cache> cacheRef = new SoftReference<>(null);
    /** Sprites built / relit since the start (diagnostics and tests). */
    static final LongAdder BUILDS = new LongAdder(), RELIGHTS = new LongAdder();
    /** {@code -Dprecip.trees.log=true}: one line on stderr per sprite built (what, how large, how long). */
    private static final boolean LOG = Boolean.getBoolean("precip.trees.log");

    private static Cache cache() {
        Cache c = cacheRef.get();
        if (c == null) { c = new Cache(); cacheRef = new SoftReference<>(c); }
        return c;
    }

    /** Forget every cached picture (nothing needs it for a new world: a key holds everything its picture depends on; tests). */
    static void clearCache() { synchronized (LOCK) { cacheRef = new SoftReference<>(null); } }

    /** {sprites, pixels} held now. */
    static long[] cacheSize() { synchronized (LOCK) { Cache c = cacheRef.get(); return c == null ? new long[2] : new long[]{c.map.size(), c.pixels}; } }

    /**
     * One tree of the live pass (drawn every frame, swaying). Its picture is drawn once with {@link #core}, unlit, into a
     * sprite; a frame then only copies the sprite's rows to the screen, each pushed sideways by the wind at its height.
     * The light is put on the sprite when it changes (a pass over its pixels, not a redraw). What is on screen is a
     * function of the arguments alone: a sprite is the same whoever builds it, and it is always built when it is missing.
     *
     * @param zoomSteady false while the camera's zoom is still moving: the picture of the nearest power-of-two scale is
     *                   stretched for those frames instead of a new one being drawn for every frame of the zoom
     */
    static void paintLive(Graphics2D g, Model.Obj o, Lit lit, double lean, boolean zoomSteady) {
        AffineTransform at = g.getTransform();
        double px = at.getScaleX();
        String v = variant(o);
        if (at.getShearX() != 0 || at.getShearY() != 0 || px != at.getScaleY() || px <= 0) { paint(g, o, lit, lean, 0); return; }
        double scale = zoomSteady ? px : Math.pow(2, Math.round(Math.log(px) / Math.log(2))), r = px / scale;
        double rx = px * o.x + at.getTranslateX(), ry = px * o.y + at.getTranslateY();
        // the root's whole device pixel, and (at rest) which quarter of a pixel it sits on
        int ix = (int) Math.floor(rx), iy = (int) Math.floor(ry), qx = 0, qy = 0;
        if (zoomSteady) {
            qx = (int) Math.round((rx - ix) * PHASES); qy = (int) Math.round((ry - iy) * PHASES);
            if (qx == PHASES) { qx = 0; ix++; }
            if (qy == PHASES) { qy = 0; iy++; }
        } else { ix = (int) Math.round(rx); iy = (int) Math.round(ry); }
        // what the picture shows of the tree's state: wetness, snow and damage in steps (each tree steps at its own moment)
        double jit = hash(o.seed * 91 + 7);
        int wet = (int) Model.clamp(Math.floor(Model.clamp(o.wet, 0, 1) * WET_STEPS + jit), 0, WET_STEPS);
        int snow = (int) Model.clamp(Math.floor(Model.clamp(o.snow, 0, 1) * SNOW_STEPS + jit), 0, SNOW_STEPS);
        int damage = (int) Math.round(Model.clamp(o.damage, 0, 1) * DAMAGE_STEPS);
        boolean fallen = broken(v, damage / (double) DAMAGE_STEPS);        // (the same question the picture itself asks: core)
        // the canvas the picture needs, in world px about the root
        double half = fallen ? o.h * 1.3 + o.w + 6 : o.w * 1.8 + (damage > 0 ? o.h * 0.55 : 0) + 6, top = o.h * 1.3 + 8, below = fallen ? o.w + 6 : 6;
        int padX = (int) Math.ceil(half * scale) + 2, padY = (int) Math.ceil(top * scale) + 2, cw = 2 * padX, ch = padY + (int) Math.ceil(below * scale) + 2;
        if ((long) cw * ch > CANVAS_MAX_PX) { paint(g, o, lit, lean, 0); return; }
        double reach = (half + Math.abs(lean) * o.h * 1.5) * px + 4;
        g.setTransform(IDENTITY);                                    // (from here on in device pixels)
        if (!g.hitClip((int) Math.floor(rx - reach), (int) Math.floor(ry - top * px - 4), (int) Math.ceil(2 * reach) + 1, (int) Math.ceil((top + below) * px) + 9)) { g.setTransform(at); return; }
        Key key = new Key(v, o.seed, Double.doubleToLongBits(o.w), Double.doubleToLongBits(o.h), wet, snow, damage, Double.doubleToLongBits(scale), qx * PHASES + qy);
        Sprite sp;
        synchronized (LOCK) { sp = cache().map.get(key); }
        if (sp == null) {
            Model.Obj q = o.withState(0, snow / (double) SNOW_STEPS, wet / (double) WET_STEPS, o.w, o.h);
            q.x = 0; q.y = 0; q.variant = v;
            q.damage = damage / (double) DAMAGE_STEPS;
            Sprite made = build(g, q, v, scale, padX, padY, cw, ch, qx / (double) PHASES, qy / (double) PHASES);
            synchronized (LOCK) {
                Cache c = cache();
                sp = c.map.get(key);
                if (sp == null) {
                    sp = made;
                    c.map.put(key, sp);
                    c.pixels += (long) sp.w * sp.h;
                    // the least recently drawn go first (never the one just made)
                    Iterator<Map.Entry<Key, Sprite>> it = c.map.entrySet().iterator();
                    while (c.pixels > CACHE_PX && c.map.size() > 1 && it.hasNext()) {
                        Map.Entry<Key, Sprite> e = it.next();
                        if (e.getValue() == sp) continue;
                        c.pixels -= (long) e.getValue().w * e.getValue().h;
                        it.remove();
                    }
                }
            }
        }
        if (sp.w == 0) { g.setTransform(at); return; }
        // the light as two colours: what black and what white become under it (every colour lies on the line between)
        Color lo = lit.lit(Color.BLACK), hi = lit.lit(Color.WHITE);
        long light = ((long) lo.getRed() << 40) | ((long) lo.getGreen() << 32) | ((long) lo.getBlue() << 24) | ((long) hi.getRed() << 16) | ((long) hi.getGreen() << 8) | hi.getBlue();
        double k = fallen ? 0 : lean * swayShare(v), sh = swayHeight(v, o);       // (what lies on the ground does not sway)
        Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        if (r != 1) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        synchronized (sp) {
            if (sp.light != light) relight(sp, lo, hi, light);
            // rows that the wind pushes equally far go in one copy
            int x0 = r == 1 ? ix + sp.ox : (int) Math.round(ix + sp.ox * r), x1 = r == 1 ? x0 + sp.w : (int) Math.round(ix + (sp.ox + sp.w) * r);
            int s0 = 0, off0 = 0;
            for (int s = 0; s <= sp.h; s++) {
                int off = 0;
                if (s < sp.h && k != 0) off = (int) Math.round(swayAt(k, sh, (ry - (iy + (sp.oy + s + 0.5) * r)) / px) * px);
                if (s == 0) { off0 = off; continue; }
                if (s < sp.h && off == off0) continue;
                int y0 = r == 1 ? iy + sp.oy + s0 : (int) Math.round(iy + (sp.oy + s0) * r), y1 = r == 1 ? iy + sp.oy + s : (int) Math.round(iy + (sp.oy + s) * r);
                if (y1 > y0) g.drawImage(sp.img, x0 + off0, y0, x1 + off0, y1, 0, s0, sp.w, s, null);
                s0 = s; off0 = off;
            }
        }
        if (r != 1 && hint != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
        g.setTransform(at);
    }

    /** Draw the unlit picture of a tree at {@code scale}, its root on (padX + fx, padY + fy) of a cw x ch canvas, and crop it. */
    private static Sprite build(Graphics2D like, Model.Obj q, String v, double scale, int padX, int padY, int cw, int ch, double fx, double fy) {
        BUILDS.increment();
        final long t0 = LOG ? System.nanoTime() : 0;
        BufferedImage img = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, like.getRenderingHint(RenderingHints.KEY_ANTIALIASING));
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, like.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL));
        g.translate(padX + fx, padY + fy);
        g.scale(scale, scale);
        core(g, q, v, null, 0, 0, scale);
        g.dispose();
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int x0 = cw, y0 = ch, x1 = -1, y1 = -1;
        for (int y = 0; y < ch; y++) {
            int row = y * cw;
            for (int x = 0; x < cw; x++) {
                if ((px[row + x] >>> 24) == 0) continue;
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                if (y < y0) y0 = y;
                if (y > y1) y1 = y;
            }
        }
        if (x1 < 0) return new Sprite(new int[0], 0, 0, 0, 0);
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        if (LOG) System.err.println(String.format(java.util.Locale.ROOT, "[trees] sprite %s seed %d at scale %.3f: %d x %d px, %.1f ms (%s)", v, q.seed, scale, w, h, (System.nanoTime() - t0) / 1e6, Thread.currentThread().getName()));
        int[] m = new int[w * h];
        for (int y = 0; y < h; y++) System.arraycopy(px, (y0 + y) * cw + x0, m, y * w, w);
        return new Sprite(m, w, h, x0 - padX, y0 - padY);
    }

    /**
     * Put a light on a sprite: {@link Lit#lit} is, channel by channel, a straight line from what it makes of black to
     * what it makes of white, so the lit picture is that line applied to every unlit pixel (premultiplied: the offset
     * goes in with the pixel's alpha).
     */
    private static void relight(Sprite sp, Color lo, Color hi, long light) {
        RELIGHTS.increment();
        final int ar = (hi.getRed() - lo.getRed()) * 65536 / 255, ag = (hi.getGreen() - lo.getGreen()) * 65536 / 255, ab = (hi.getBlue() - lo.getBlue()) * 65536 / 255;
        final int br = lo.getRed() * 65536 / 255, bg = lo.getGreen() * 65536 / 255, bb = lo.getBlue() * 65536 / 255;
        final int[] m = sp.master, out = sp.lit;
        for (int i = 0; i < m.length; i++) {
            int p = m[i], a = p >>> 24;
            if (a == 0) { out[i] = 0; continue; }
            int rr = (ar * ((p >> 16) & 255) + br * a + 32768) >> 16, gg = (ag * ((p >> 8) & 255) + bg * a + 32768) >> 16, bl = (ab * (p & 255) + bb * a + 32768) >> 16;
            if (rr > a) rr = a;
            if (gg > a) gg = a;
            if (bl > a) bl = a;
            out[i] = (a << 24) | (rr << 16) | (gg << 8) | bl;
        }
        sp.light = light;
    }

    // ------------------------------------------------------------------ broad-leaved crowns

    /**
     * A crown of leaf clusters: {@code blobs} are the masses of the crown ({cx, cy, rx, ry[, 1 = flat-bottomed pad]});
     * a limb runs from the fork ({@code fork}: one point, or one per blob) into each, twigs branch on to every cluster.
     */
    private static void crown(Pen p, long seed, double[][] blobs, double[] fork, double limbW, double cr, double len, double wr,
                              int shape, double droop, double dens, double backShare) {
        p.lfLen = len; p.lfWr = wr; p.lfShape = shape; p.lfCr = cr; p.lfDroop = droop;
        double top = 1e9, bot = -1e9, lft = 1e9, rgt = -1e9;
        for (double[] b : blobs) {
            boolean flat = b.length > 4 && b[4] > 0;
            top = Math.min(top, b[1] - b[3]); bot = Math.max(bot, flat ? b[1] : b[1] + b[3]);
            lft = Math.min(lft, b[0] - b[2]); rgt = Math.max(rgt, b[0] + b[2]);
        }
        double mx = (lft + rgt) / 2, hwid = Math.max(1, (rgt - lft) / 2), hgt = Math.max(1, bot - top);
        int nL = (int) Model.clamp(Math.round(dens * cr * cr / (len * len)), 5, 22);
        for (int j = 0; j < blobs.length; j++) {
            double[] b = blobs[j];
            boolean flat = b.length > 4 && b[4] > 0;
            double fx = fork[fork.length > 2 ? 2 * j : 0], fy = fork[fork.length > 2 ? 2 * j + 1 : 1];
            double ax = b[0], ay = flat ? b[1] - b[3] * 0.15 : b[1] + b[3] * 0.25;
            p.curve(fx, fy, fx + (ax - fx) * 0.25, ay + (fy - ay) * 0.35, ax, ay, limbW, Math.max(0.3, limbW * 0.4), 0);
            if (p.lod >= 1 || limbW * p.px > 0.7) p.strip(p.wood, 0, 1, -1, 1, p.lod >= 2 ? 5 : 3);
            int n = (int) Model.clamp(Math.round(0.95 * b[2] * b[3] / (cr * cr)), 2, 18);
            double[] xs = new double[n], ys = new double[n];
            double erx = Math.max(b[2] - cr * 0.75, b[2] * 0.35), ery = Math.max(b[3] - cr * 0.75, b[3] * 0.35);
            double ph = hash(seed * 31 + j * 1009 + 7) * 6.283185307, c0 = Math.cos(ph), s0 = Math.sin(ph);
            double tw0 = Math.max(0.24, limbW * 0.34);
            for (int i = 0; i < n; i++) {
                long cs = mix(seed * 31 + j * 1009L + i * 7919L + 11);
                p.seed(cs);
                double j1 = p.rnd(), j2 = p.rnd(), j3 = p.rnd(), j4 = p.rnd(), rr = Math.sqrt((i + 0.6) / n);
                double gc = GC[i] * c0 - GS[i] * s0, gs = GS[i] * c0 + GC[i] * s0;
                double x = b[0] + gc * rr * erx + (j1 - 0.5) * cr * 0.4;
                double y = flat ? b[1] - cr * 0.5 - Math.abs(gs) * rr * Math.max(0, b[3] - cr * 0.7) : b[1] + gs * rr * ery + (j2 - 0.5) * cr * 0.4;
                xs[i] = x; ys[i] = y;
                // the twig comes off the nearest cluster further in (or off the limb's end)
                double qx = ax, qy = ay, best = (x - ax) * (x - ax) + (y - ay) * (y - ay);
                for (int q = 0; q < i; q++) {
                    double d = (x - xs[q]) * (x - xs[q]) + (y - ys[q]) * (y - ys[q]);
                    if (d < best) { best = d; qx = xs[q]; qy = ys[q]; }
                }
                if (p.lod >= 1) {
                    p.curve(qx, qy, (qx + x) / 2 + (j3 - 0.5) * cr * 0.5, (qy + y) / 2 + cr * 0.2, x, y, tw0, 0.16, 0);
                    p.strip(p.wood, 0, 1, -1, 1, 2);
                }
                if (j4 < 0.07 && i > 0) continue;                       // a gap in the crown: sky, and the twig that would have carried it
                double light = 1 - 0.80 * (y - top) / hgt - 0.14 * (x - mx) / hwid + 0.18 * (rr - 0.5) + (j4 - 0.5) * 0.2;
                boolean back = j3 < backShare && rr < 0.78;
                p.cluster(cs ^ 0x5DEECE66DL, x, y, cr * (0.85 + 0.3 * j1), flat ? 0.74 : 0.9, light, back, back ? Math.max(4, nL * 2 / 3) : nL,
                        len, wr, shape, droop, y - b[1] < b[3] * 0.25);
            }
        }
    }

    /** A trunk's bark: the side away from the light, and from close up the furrows. */
    private static void barkOn(Pen p, long seed, int segs, int furrows) {
        if (p.lod >= 1) p.strip(p.woodShade, 0, 1, 0.2, 1, segs);
        if (p.lod >= 2) for (int j = 0; j < furrows; j++) {
            double a0 = -0.85 + 1.6 * (j + hash(seed + j * 13)) / furrows, t0 = hash(seed + j * 29 + 1) * 0.35, t1 = 0.55 + hash(seed + j * 31 + 2) * 0.45;
            p.strip(a0 > 0.2 ? p.woodMark : p.woodShade, t0, t1, a0, a0 + 0.11, 3);
        }
    }

    private static void oak(Pen p, Model.Obj o, double k) {
        double w = o.w, h = o.h, trunkH = h * 0.38, trunkW = Math.max(3, w * 0.15);
        p.bend(k, h * 1.05, 1.5);
        p.bark(new Color(96, 66, 42), new Color(60, 42, 30));
        p.tones(new Color(72, 132, 58), new Color(124, 180, 84));
        double cy = -trunkH - h * 0.28, lx = (hash(o.seed * 5 + 1) - 0.5) * w * 0.12;
        double[][] blobs = {{-w * 0.36, cy + 7, w * 0.46, w * 0.414}, {w * 0.36, cy + 6, w * 0.46, w * 0.414}, {-w * 0.14, cy - h * 0.15, w * 0.5, w * 0.45},
                {w * 0.18, cy - h * 0.13, w * 0.46, w * 0.414}, {0, cy + 4, w * 0.56, w * 0.504}};
        p.curve(0, 1, lx * 0.2, -trunkH * 0.55, lx, -trunkH - 1, trunkW * 0.8, trunkW * 0.42, 0);
        // (a short stout trunk under a heavy crown: it breaks low, and the crown's own limbs carry it on the ground)
        p.trunk(trunkH * 0.26, trunkH * 0.50, lx, cy, w * 0.20, trunkH, trunkW * 1.3, 0.5);
        p.strip(p.wood, 0, 1, -1, 1, 5);
        if (p.lod >= 1) {
            // the roots spread where the trunk meets the ground (they stay with the stump)
            p.stump();
            p.triL(p.wood, -trunkW * 0.75, 1, -trunkW * 1.3, 1, -trunkW * 0.7, -Math.min(trunkH * 0.2, -p.brkY * 0.8));
            p.triL(p.wood, trunkW * 0.75, 1, trunkW * 1.3, 1, trunkW * 0.7, -Math.min(trunkH * 0.2, -p.brkY * 0.8));
        }
        barkOn(p, o.seed, 5, 5);
        p.endTrunk();
        crown(p, o.seed, blobs, new double[]{lx, -trunkH}, trunkW * 0.34, Model.clamp(w * 0.14, 3.6, 7.5), 3.4, 0.6, OAK, 0.4, 4.2, 0.38);
    }

    private static void birch(Pen p, Model.Obj o, Random r, double k) {
        double w = o.w, h = o.h, tw = Math.max(2.2, w * 0.09);
        p.bend(k, h, 1.4);
        p.bark(new Color(74, 62, 54), new Color(46, 40, 38));
        p.trunkC = p.c(Lit.mix(new Color(232, 230, 220), new Color(190, 190, 184), p.wet));
        p.barkDark = p.c(Lit.mix(new Color(178, 180, 176), new Color(140, 144, 144), p.wet));
        p.barkMark = p.c(new Color(58, 54, 52));
        p.tones(new Color(118, 164, 70), new Color(178, 206, 104));
        double[][] blobs = new double[6][];
        double[] fork = new double[12];
        for (int i = 0; i < 6; i++) blobs[i] = new double[]{(r.nextDouble() - 0.5) * w * 0.3, -h * (0.48 + 0.09 * i), w * (0.52 - Math.abs(i - 2) * 0.07), h * 0.11};
        double tipX = blobs[5][0] * 0.6, topY = -h * 0.93;
        p.curve(0, 1, tipX * 0.3, topY * 0.5, tipX, topY, tw * 0.58, 0.25, 0);
        p.trunk(h * 0.12, h * 0.27, tipX, topY * 0.97, Math.max(1.4, w * 0.10), h * 0.34, tw * 0.62, 0.45);
        // (the wood under a birch's white skin is pale)
        p.broken(Lit.mix(new Color(236, 216, 170), new Color(186, 160, 118), p.wet), Lit.mix(new Color(196, 164, 112), new Color(142, 116, 84), p.wet), new Color(58, 54, 52));
        p.strip(p.trunk, 0, 1, -1, 1, 7);
        if (p.lod >= 1) {
            p.strip(p.woodShade, 0, 1, 0.3, 1, 7);
            p.strip(p.woodShade, 0, 0.035, -1, 1, 1);                // the rough grey foot of an old birch
        }
        // the dark lenticels across the white bark
        int marks = p.lod == 0 ? 5 : (int) Model.clamp(h / 4.2, 6, 22);
        for (int j = 0; j < marks; j++) {
            double t = 0.05 + 0.80 * (j + 0.2 + 0.6 * hash(o.seed + j * 17)) / marks, a0 = -1 + 1.3 * hash(o.seed + j * 23 + 5), a1 = Math.min(1, a0 + 0.5 + 0.7 * hash(o.seed + j * 41 + 9));
            p.strip(p.woodMark, t, t + 0.55 / (h * 0.93), a0, a1, 1);
        }
        p.endTrunk();
        for (int i = 0; i < 6; i++) {
            // each mass of the crown hangs on a branch that leaves the trunk a little below it
            double fy = Math.min(-h * 0.3, blobs[i][1] + h * 0.07), t = Math.min(1, -fy / -topY);
            fork[2 * i] = 2 * (1 - t) * t * tipX * 0.3 + t * t * tipX;
            fork[2 * i + 1] = fy;
        }
        crown(p, o.seed, blobs, fork, Math.max(0.45, tw * 0.2), Model.clamp(w * 0.17, 3.2, 5.5), 2.5, 0.72, BIRCH, 0.9, 3.2, 0.3);
    }

    private static void bush(Pen p, Model.Obj o, Random r, double k) {
        double w = o.w, h = o.h;
        p.bend(k, h, 0.5);
        p.bark(new Color(92, 70, 48), new Color(56, 44, 34));
        p.tones(new Color(66, 124, 60), new Color(112, 166, 82));
        int n = 3 + r.nextInt(3);
        double[][] blobs = new double[n][];
        double[] fork = new double[2 * n];
        for (int i = 0; i < n; i++) {
            double bx = (i / (double) (n - 1) - 0.5) * w * 0.75, br = h * (0.5 + r.nextDouble() * 0.35);
            blobs[i] = new double[]{bx, -br * 0.80, br, br * 0.78};
            fork[2 * i] = bx * 0.25; fork[2 * i + 1] = 0.5;
        }
        crown(p, o.seed, blobs, fork, 0.6, Model.clamp(h * 0.2, 3.0, 5.5), 2.7, 0.78, ROUND, 0.25, 3.4, 0.25);
    }

    private static void acacia(Pen p, Model.Obj o, Random r, double k) {
        // an umbrella thorn: limbs fan out from a short trunk to carry a wide, flat crown of tiny leaflets
        double w = o.w, h = o.h, trunkH = h * 0.50, tw = Math.max(2.6, w * 0.055), cy = -h * 0.70, tilt = (hash(o.seed * 7 + 2) - 0.5) * w * 0.08;
        p.bend(k, h, 1.3);
        p.bark(new Color(104, 80, 58), new Color(64, 50, 40));
        p.tones(new Color(96, 134, 62), new Color(162, 182, 90));
        p.curve(0, 1, tilt * 0.2, -trunkH * 0.5, tilt, -trunkH, tw * 0.9, tw * 0.45, 0);
        p.trunk(trunkH * 0.22, trunkH * 0.46, tilt, cy, w * 0.13, trunkH, tw * 1.0, 0.5);
        p.strip(p.wood, 0, 1, -1, 1, 4);
        barkOn(p, o.seed, 4, 3);
        p.endTrunk();
        for (double fx : new double[]{-0.34, -0.12, 0.14, 0.36}) {
            p.curve(tilt, -trunkH + 1, tilt + fx * w * 0.35, (cy - trunkH) * 0.5 - h * 0.02, fx * w, cy + 1, tw * 0.36, 0.3, 0);
            p.strip(p.wood, 0, 1, -1, 1, 4);
        }
        double[][] pads = {{-w * 0.29, cy + h * 0.05, w * 0.30, h * 0.13, 1}, {w * 0.31, cy + h * 0.04, w * 0.28, h * 0.12, 1},
                {(r.nextDouble() - 0.5) * w * 0.08, cy, w * 0.50, h * 0.19, 1}};
        crown(p, o.seed, pads, new double[]{tilt, -trunkH}, tw * 0.34, Model.clamp(h * 0.09, 3.4, 6), 2.2, 0.46, NARROW, 0.05, 4.4, 0.25);
    }

    private static void shrub(Pen p, Model.Obj o, Random r, double k) {
        // low dry scrub: bare twigs with small grey-green tufts at their ends
        double w = o.w, h = o.h;
        p.bend(k, h, 0.5);
        p.bark(new Color(124, 100, 72), new Color(78, 62, 48));
        p.tones(new Color(134, 140, 90), new Color(190, 186, 126));
        int n = 5 + r.nextInt(3);
        double[][] blobs = new double[n][];
        double[] fork = new double[2 * n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double a = Math.PI * (0.14 + 0.72 * i / (n - 1.0)) + (r.nextDouble() - 0.5) * 0.2;
            double tx = Math.cos(a) * w * 0.5 * (0.72 + 0.28 * r.nextDouble()), ty = -Math.sin(a) * h * (0.62 + 0.30 * r.nextDouble()) - 1, cr = h * (0.20 + 0.13 * r.nextDouble());
            p.curve(0, 0, tx * 0.35, ty * 0.6, tx, ty, 0.6, 0.3, 0);
            p.strip(p.wood, 0, 1, -1, 1, 3);
            if (p.lod >= 1) for (int q = 0; q < 2; q++) {
                // side twigs off the stem
                double t = 0.45 + 0.3 * q, sx = p.curveX(t), sy = p.curveY(t), sd = (q == 0) == (i % 2 == 0) ? -1 : 1, hx = hash(o.seed + i * 53 + q * 7);
                double ex = sx + sd * cr * (0.7 + 0.5 * hx), ey = sy - cr * (0.5 + 0.5 * hx);
                double kx0 = p.qx0, ky0 = p.qy0, kcx = p.qcx, kcy = p.qcy, kx1 = p.qx1, ky1 = p.qy1;
                p.curve(sx, sy, (sx + ex) / 2, sy, ex, ey, 0.32, 0.18, 0);
                p.strip(p.wood, 0, 1, -1, 1, 2);
                p.curve(kx0, ky0, kcx, kcy, kx1, ky1, 0.6, 0.3, 0);
            }
            blobs[i] = new double[]{tx, ty - cr * 0.05, cr * 1.5, cr * 0.95};
            fork[2 * i] = tx; fork[2 * i + 1] = ty;
            sum += cr;
        }
        crown(p, o.seed, blobs, fork, 0.3, Model.clamp(sum / n * 0.72, 2.4, 4), 2.1, 0.5, NARROW, -0.35, 2.7, 0.2);
    }

    // ------------------------------------------------------------------ pine

    /** Half the width of the pine's crown at height y: the four tiers of the old picture, softened towards a plain cone. */
    private static double pineEnv(double y, double w, double h, double trunkH, double top, double yb) {
        double e = 0;
        for (int k = 0; k < 4; k++) {
            double tb = -trunkH - k * h * 0.185 + 2, tt = tb - h * 0.36, hw = w * (0.62 - k * 0.11);
            if (y <= tb && y >= tt) e = Math.max(e, hw * (y - tt) / (tb - tt));
        }
        return 0.62 * e + 0.38 * w * 0.62 * Model.clamp((y - top) / (yb - top), 0, 1);
    }

    private static void pine(Pen p, Model.Obj o, double k) {
        double w = o.w, h = o.h, trunkH = h * 0.2, trunkW = Math.max(2.6, w * 0.14);
        double top = -(trunkH + 3 * h * 0.185 + h * 0.36) + 2, yb = -trunkH + 2;
        p.bend(k, -top, 1.4);
        p.bark(new Color(96, 66, 42), new Color(60, 42, 30));
        p.tones(new Color(44, 108, 68), new Color(104, 168, 104));
        p.curve(0, 1, 0, top * 0.5, 0, top + 1.5, trunkW * 0.5, 0.22, 0);
        // (a conifer snaps part way up its stem: the lowest whorls may stay on the stump)
        p.trunk(h * 0.15, h * 0.33, 0, top + 2, Math.max(1.3, w * 0.05), trunkH, trunkW * 0.55, 0.5);
        p.strip(p.wood, 0, 1, -1, 1, 7);
        if (p.lod >= 1) p.strip(p.woodShade, 0, 0.3, 0.2, 1, 3);
        if (p.lod >= 2) for (int j = 0; j < 4; j++) p.strip(p.woodMark, 0.01 + 0.03 * j, 0.2, -0.8 + 0.45 * j, -0.68 + 0.45 * j, 2);
        p.endTrunk();
        int nW = (int) Model.clamp(Math.round(h / 5.6), 8, 16);
        for (int i = 0; i < nW; i++) {
            double f = (i + 0.35) / nW, yT = yb + (top + 4 - yb) * f, env = pineEnv(yT, w, h, trunkH, top, yb);
            for (int s = 0; s < 4; s++) {
                // a whorl: a bough to either side, and two short ones towards the viewer that hide the trunk
                p.seed(mix(o.seed * 17 + i * 131L + s * 7 + 5));
                double r2 = p.rnd(), r3 = p.rnd();
                double sx = s % 2 == 0 ? -1 : 1;
                double reach = Math.max(1.6, s < 2 ? env * (0.86 + 0.2 * r2) : env * (0.16 + 0.4 * r2));
                double tipY = yT + (r3 - 0.5) * 1.5 + (s >= 2 ? reach * 0.55 + 2.5 : 0);
                double ya = Math.max(top + 2, yT - (s < 2 ? reach * (0.42 - 0.3 * f) : 2.5 + 2 * r3));
                p.part(ya);
                bough(p, ya, sx * reach, tipY, reach, 0.86 - 0.36 * (1 - f) - sx * 0.12 + (s >= 2 ? 0.05 : 0), 0.55 * (1 - f * 0.55), s >= 2);
            }
        }
        p.part(top);
        if (p.snowOn) p.pillow(0, top + 2.2, 1.1);                  // (sitting on the tip of the leader)
    }

    /** One bough of a conifer from the trunk at height ya to its tip, carrying its needle sprays. */
    private static void bough(Pen p, double ya, double tipX, double tipY, double len, double light, double w0, boolean front) {
        double sx = tipX < 0 ? -1 : 1, up = -sx;                        // (which side of the limb's centre line faces the sky)
        // the mass of the bough: one drooping blade in the shade of its own needles (far away, all there is of it)
        p.curve(0, ya, tipX * 0.5, ya + (tipY - ya) * 0.12, tipX, tipY, Math.max(0.5, len * 0.06), 0.1, Math.max(0.6, len * (front ? 0.24 : 0.15)));
        if (p.lod == 0) { p.qwb *= 1.45; p.qx1 *= 1.06; }
        p.strip(p.under, 0, 1, -1, 1, 4);
        if (p.lod == 0) {
            int k = light < 0.3 ? 1 : light < 0.62 ? 2 : 3;
            p.strip(p.full[k], 0.12, 1, up > 0 ? -0.1 : -1, up > 0 ? 1 : 0.1, 4);
            if (p.snowOn) {
                // (on a tree that lies, the snow is on the boughs that point at the sky now)
                if (p.broke && p.up && p.theta > 0.8) { if (sx == -p.side) p.strip(p.snowP, 0.5, 1, -0.6, 0.6, 4); }
                else p.strip(p.snowP, 0.2, 0.95, up > 0 ? 0.3 : -1.05, up > 0 ? 1.05 : -0.3, 4);
            }
            return;
        }
        p.curve(0, ya, tipX * 0.5, ya + (tipY - ya) * 0.12, tipX, tipY, w0, 0.15, 0);
        p.strip(p.wood, 0, 1, -1, 1, 3);
        int m = Math.max(front ? 1 : 2, (int) (len / 2.3));
        for (int q = 0; q <= m; q++) {
            double t = (front ? 0.4 : 0.2) + (front ? 0.6 : 0.8) * q / m;
            p.at(t, 0);
            double x = p.X, y = p.Y, tx = p.TX, ty = p.TY, nx = -ty, ny = tx;
            if (p.snowOn && q > 0) {
                double sr = 1.3 + 1.1 * p.snow;
                Path2D.Float sp = p.snowP;
                sp.moveTo((float) (x - sr), (float) (y - 0.3)); sp.lineTo((float) (x - sr * 0.5), (float) (y - 0.5 - sr * 0.45));
                sp.lineTo((float) (x + sr * 0.5), (float) (y - 0.5 - sr * 0.45)); sp.lineTo((float) (x + sr), (float) (y - 0.3)); sp.closePath();
            }
            for (int e = q == m ? 0 : -1; e <= 1; e += 2) {
                // the tip carries one spray along the bough; further in, one to either side of it
                double r1 = p.rnd(), r2 = p.rnd();
                double dx, dy;
                if (q == m) { dx = tx; dy = ty + 0.2; }
                else { dx = tx * 0.8 + e * nx * 0.62; dy = ty * 0.8 + e * ny * 0.62 + 0.36; }
                double dl = Math.sqrt(dx * dx + dy * dy);
                dx /= dl; dy /= dl;
                boolean upper = q == m || e * ny < 0;
                double tl = light + 0.30 * t - 0.25 + (upper ? 0.16 : -0.18) + (r1 - 0.5) * 0.36;
                int k = tl < 0.18 ? 0 : tl < 0.42 ? 1 : tl < 0.66 ? 2 : tl < 0.9 ? 3 : 4;
                spray(p, x, y, dx, dy, 5.0 * (0.72 + 0.5 * r2) * (1 - 0.2 * t), k);
            }
        }
    }

    /** One needle spray (already bent): a shoot with needles to either side of it. */
    private static void spray(Pen p, double x, double y, double dx, double dy, double len, int k) {
        double m = len + 1;
        if (x + m < p.cx0 || x - m > p.cx1 || y + m < p.cy0 || y - m > p.cy1) return;
        if (p.ground() && y + dy * len > -0.3) {
            // (a spray that would point into the ground lies along it instead)
            dy = Model.clamp((-0.3 - y) / len, -1, 1);
            dx = (dx < 0 ? -1 : 1) * Math.sqrt(Math.max(0, 1 - dy * dy));
        }
        double nx = -dy, ny = dx, b = len * 0.2;
        if (p.lod == 1) {
            Path2D.Float pf = p.full[k];
            pf.moveTo((float) x, (float) y);
            pf.lineTo((float) (x + dx * len * 0.38 - nx * b), (float) (y + dy * len * 0.38 - ny * b));
            pf.lineTo((float) (x + dx * len), (float) (y + dy * len));
            pf.lineTo((float) (x + dx * len * 0.38 + nx * b), (float) (y + dy * len * 0.38 + ny * b));
            pf.closePath();
            return;
        }
        // from close up: the shade inside the spray, its shoot, and the needles one by one
        Path2D.Float pu = k > 0 ? p.full[k - 1] : p.under;
        pu.moveTo((float) x, (float) y);
        pu.lineTo((float) (x + dx * len * 0.4 - nx * b * 0.95), (float) (y + dy * len * 0.4 - ny * b * 0.95));
        pu.lineTo((float) (x + dx * len * 0.92), (float) (y + dy * len * 0.92));
        pu.lineTo((float) (x + dx * len * 0.4 + nx * b * 0.95), (float) (y + dy * len * 0.4 + ny * b * 0.95));
        pu.closePath();
        Pen.tri(p.wood, x - nx * 0.16, y - ny * 0.16, x + nx * 0.16, y + ny * 0.16, x + dx * len * 0.9, y + dy * len * 0.9);
        int np = p.lod == 2 ? 4 : 6;
        double hb = p.lod == 2 ? 0.31 : 0.19;
        for (int j = 0; j < np; j++) {
            double s = (j + 0.6) / np, px = x + dx * len * s * 0.92, py = y + dy * len * s * 0.92, nl = len * 0.40 * (1 - 0.45 * s);
            for (int e = -1; e <= 1; e += 2) {
                boolean upper = e * ny < 0;
                int kk = upper ? k : Math.max(0, k - 1);
                Path2D.Float pf = p.snowOn && upper && ((j * 7 + k) % 10) < p.snow * 9 ? p.snowP : p.full[kk];
                Pen.tri(pf, px - dx * hb, py - dy * hb, px + dx * hb, py + dy * hb, px + (dx * 0.62 + e * nx * 0.78) * nl, py + (dy * 0.62 + e * ny * 0.78) * nl);
            }
        }
        Pen.tri(p.full[k], x + dx * len * 0.86 - nx * 0.2, y + dy * len * 0.86 - ny * 0.2, x + dx * len * 0.86 + nx * 0.2, y + dy * len * 0.86 + ny * 0.2,
                x + dx * len * 1.12, y + dy * len * 1.12);
    }

    // ------------------------------------------------------------------ palm

    private static void palm(Pen p, Model.Obj o, Random r, double k) {
        double w = o.w, h = o.h, trunkH = h * 0.84, tw = Math.max(2.4, w * 0.10), bend = (r.nextDouble() - 0.5) * w * 0.9;
        p.bend(k, trunkH * 1.15, 1.2);
        p.bark(new Color(150, 120, 86), new Color(96, 76, 58));
        p.barkMark = p.c(new Color(84, 60, 40));
        p.tones(new Color(52, 128, 66), new Color(118, 178, 84));
        p.curve(0, 1, bend * 0.15, -trunkH * 0.5, bend, -trunkH, tw * 0.75, tw * 0.36, 0);
        // (a palm's stem snaps higher than a tree's trunk: the head comes down on the ground, the stem hangs on the tall stump or slides off it)
        p.trunk(trunkH * 0.30, trunkH * 0.47, bend, -trunkH, Math.max(2.2, w * 0.12), trunkH, tw * 0.95, 0.35);
        p.broken(Lit.mix(new Color(222, 196, 146), new Color(168, 140, 100), p.wet), Lit.mix(new Color(176, 146, 102), new Color(126, 102, 76), p.wet), new Color(84, 60, 40));
        p.strip(p.wood, 0, 1, -1, 1, 9);
        if (p.lod >= 1) {
            p.strip(p.woodShade, 0, 1, 0.3, 1, 9);
            p.stump();
            p.blob(p.wood, 0, -1.2, tw * 0.95, 2.4, 8);              // the swollen foot
        }
        // the ring scars the fallen fronds left
        int rings = p.lod == 0 ? 8 : (int) Model.clamp(trunkH / (p.lod == 1 ? 6 : 3.2), 8, 40);
        for (int j = 1; j < rings; j++) { double t = j / (double) rings; p.strip(p.woodMark, t, t + (p.lod == 0 ? 0.6 : 0.45) / trunkH, -1, 1, 1); }
        p.endTrunk();
        final int n = 11;
        for (int pass = 0; pass < 2; pass++) {
            for (int j = 0; j < n; j++) {
                if ((j % 2 == 0) != (pass == 0)) continue;             // every other frond is on the far side: darker, drawn first
                p.seed(mix(o.seed * 13 + j * 101L + 3));
                double r1 = p.rnd(), r2 = p.rnd();
                double a = Math.PI * (1.12 - 1.24 * j / (n - 1.0)) + (r1 - 0.5) * 0.16, len = w * 0.95 * (0.84 + 0.30 * r2), ca = Math.cos(a), sa = Math.sin(a);
                if (Math.abs(ca) < 0.24) ca = j % 2 == 0 ? -0.24 : 0.24;       // (no frond stands straight up and falls back on itself)
                double tx = bend + ca * len, ty = -trunkH - sa * len * 0.62 + len * 0.42, mx = bend + ca * len * 0.55, my = -trunkH - sa * len * 0.80 - len * 0.16;
                frond(p, bend, -trunkH, mx, my, tx, ty, len, pass == 0);
            }
            if (pass == 0) {
                // the crown shaft and the nuts under it, between the far fronds and the near ones
                p.blob(p.woodMark, bend - 1.7, -trunkH + 2.1, 1.7, 1.7, 8);
                p.blob(p.woodMark, bend + 2.0, -trunkH + 2.6, 1.6, 1.6, 8);
                if (p.lod >= 2) p.blob(p.woodMark, bend + 0.2, -trunkH + 3.6, 1.5, 1.5, 8);
            }
        }
    }

    /** One pinnate frond: the rachis arching from the crown to its tip, a leaflet to either side all along it. */
    private static void frond(Pen p, double x0, double y0, double mx, double my, double tx, double ty, double len, boolean far) {
        boolean right = tx > x0;
        p.curve(x0, y0, mx, my, tx, ty, 0.45, 0.12, 0);
        if (p.lod == 0) {
            p.qw0 = len * 0.03; p.qw1 = 0.1; p.qwb = len * 0.105;
            p.strip(far ? p.backHi : p.full[2], 0, 1, -1, 1, 6);
            if (!far) p.strip(p.full[3], 0.05, 1, right ? -1 : 0.1, right ? -0.1 : 1, 6);
            if (p.snowOn) p.strip(p.snowP, 0.1, 0.95, right ? -1.05 : 0.45, right ? -0.45 : 1.05, 6);
            return;
        }
        p.strip(far ? p.backHi : p.full[3], 0, 1, -1, 1, 6);
        int m = (int) Model.clamp(len / (p.lod == 1 ? 2.1 : 1.45), 7, 26);
        for (int i = 0; i < m; i++) {
            double t = 0.08 + 0.9 * (i + 0.5) / m;
            p.at(t, 0);
            double x = p.X, y = p.Y, ux = p.TX, uy = p.TY, nx = -uy, ny = ux;
            double u = 0.5 * (t + Math.sqrt(t)), reach = len * 0.36 * Math.sin(Math.PI * u), phi = 1.2 - 0.6 * t, cp = Math.cos(phi), sp = Math.sin(phi);
            if (p.snowOn && i % 2 == 0) {
                double sr = 0.9 + 1.2 * p.snow;
                Path2D.Float sn = p.snowP;
                sn.moveTo((float) (x - sr), (float) (y - 0.2)); sn.lineTo((float) (x - sr * 0.5), (float) (y - 0.4 - sr * 0.5));
                sn.lineTo((float) (x + sr * 0.5), (float) (y - 0.4 - sr * 0.5)); sn.lineTo((float) (x + sr), (float) (y - 0.2)); sn.closePath();
            }
            for (int e = -1; e <= 1; e += 2) {
                double r1 = p.rnd(), r2 = p.rnd();
                if (r2 >= p.keep + 0.2) continue;
                // a leaflet leaves the rachis towards the tip and hangs under its own weight
                double dx = ux * cp + e * nx * sp, dy = uy * cp + e * ny * sp + (far ? 0.6 : 0.5) * (0.6 + 0.8 * t), dl = Math.sqrt(dx * dx + dy * dy);
                dx /= dl; dy /= dl;
                double ll = reach * (0.8 + 0.35 * r1), bw = p.lod == 1 ? 0.95 : 0.8, ex = x + dx * ll, ey = y + dy * ll;
                // (a leaflet that would hang into the ground lies on it instead, splayed out along it)
                if (ey > -0.3 && p.ground()) { ex += (dx < 0 ? -1 : 1) * Math.min(ll * 0.5, ey + 0.3) * 0.7; ey = -0.3 - 0.5 * r1; }
                boolean upper = e * ny < 0;
                Path2D.Float pf, ph = null;
                if (far) pf = upper ? p.backHi : p.backLo;
                else {
                    int kk = upper ? (r1 < 0.3 ? 4 : 3) : (r1 < 0.25 ? 1 : 2);
                    if (p.snowOn && upper && r2 < p.snow * 0.7) pf = p.snowP;
                    else { pf = p.full[kk]; ph = p.half[kk]; }
                }
                Pen.tri(pf, x - ux * bw, y - uy * bw, x + ux * bw, y + uy * bw, ex, ey);
                if (ph != null) Pen.tri(ph, x, y, x + ux * bw, y + uy * bw, ex, ey);    // (the fold along the leaflet: one half in shade)
            }
        }
    }

    // ------------------------------------------------------------------ cactus

    private static void cactus(Pen p, Model.Obj o, Random r, double k) {
        // a saguaro: a ribbed column with one or two upturned arms; it hardly moves in the wind
        p.bend(k, o.h, 1.0);
        Color body = new Color(78, 132, 86);
        body = Lit.mix(body, Lit.mul(body, 0.66), p.wet * 0.7);
        Color cBody = p.c(body), cHi = p.c(Lit.mix(new Color(124, 170, 106), new Color(78, 132, 86), p.wet * 0.5)), cDark = p.c(Lit.mul(body, 0.74));
        Color cRib = Lit.alpha(p.c(new Color(46, 92, 64)), 170), cSpine = Lit.alpha(p.c(new Color(232, 226, 196)), 220), cFlower = p.c(new Color(250, 246, 232));
        Path2D.Float[] ps = {new Path2D.Float(), new Path2D.Float(), new Path2D.Float(), new Path2D.Float(), new Path2D.Float(), new Path2D.Float()};
        double cw = Math.max(4.5, o.w * 0.34), hh = o.h;
        int arms = 1 + r.nextInt(2);
        boolean leftFirst = r.nextBoolean(), flowers = p.lod >= 2 && hash(o.seed * 3 + 9) < 0.55;
        // (a saguaro loses its top: the column breaks above the middle and the top lies beside it; an arm that grew
        //  from the part that broke off goes with it. Its flesh is pale green inside, pulpy rather than splintered)
        p.broken(Lit.mix(new Color(188, 214, 150), new Color(150, 180, 124), p.wet), Lit.mix(new Color(146, 182, 118), new Color(112, 150, 96), p.wet), new Color(46, 92, 64));
        p.curve(0, 1, 0, -hh / 2, 0, -hh + cw * 0.5, cw / 2, cw / 2, 0);
        p.splinter = 0.34;
        p.trunk(hh * 0.42, hh * 0.62, 0, -hh + cw * 0.5, cw * 0.5 + 0.4, hh, cw * 0.5, 0.85);
        p.onTrunk = false;
        for (int i = 0; i < arms; i++) {
            double side = (i == 0) == leftFirst ? -1 : 1, aw = cw * 0.62;
            double ay = hh * (0.32 + 0.18 * r.nextDouble()), ax = side * (cw * 0.5 + o.w * (0.24 + 0.10 * r.nextDouble()));
            double at = Math.min(hh * 0.88, ay + hh * (0.22 + 0.20 * r.nextDouble()));
            double ye = -ay - aw * 1.1, yt = Math.min(ye - 1, -at);
            p.part(-ay);
            p.curve(0, -ay, ax, -ay, ax, ye, aw / 2, aw / 2, 0);
            column(p, ps, o.seed + i * 977 + 1, Math.abs(ax) + aw, aw / 2, false, 6);
            p.curve(ax, ye, ax, (ye + yt) / 2, ax, yt, aw / 2, aw / 2, 0);
            column(p, ps, o.seed + i * 977 + 2, ye - yt, aw / 2, true, 3);
            top(p, ps, ax, yt, aw / 2, flowers && i == 0);
        }
        p.curve(0, 1, 0, -hh / 2, 0, -hh + cw * 0.5, cw / 2, cw / 2, 0);
        p.onTrunk = true;
        column(p, ps, o.seed, hh, cw / 2, true, 6);
        p.endTrunk();
        top(p, ps, 0, -hh + cw * 0.5, cw / 2, flowers);
        Color[] cs = {cBody, cHi, cDark, cRib, cSpine, cFlower};
        for (int i = 0; i < ps.length; i++) p.later.add(new Object[]{ps[i], cs[i]});
    }

    /** One limb of a cactus along the pen's current curve: body, the lit and the shaded flank, the grooves between its ribs, its spines. */
    private static void column(Pen p, Path2D.Float[] ps, long seed, double len, double hw, boolean flanks, int segs) {
        p.strip(ps[0], 0, 1, -1, 1, segs);
        if (flanks) { p.strip(ps[1], 0, 1, -1, -0.32, segs); p.strip(ps[2], 0, 1, 0.46, 1, segs); }
        double t0 = flanks ? 0 : 0.32;                                  // (an arm's elbow starts behind the stem)
        if (p.lod >= 1) for (double gv : new double[]{-0.62, -0.22, 0.2, 0.6}) p.strip(ps[3], t0, 1, gv - 0.05, gv + 0.05, segs);
        else if (hw * p.px > 1.6) { p.strip(ps[3], t0, 1, -0.34 - 0.08, -0.34 + 0.08, segs); p.strip(ps[3], t0, 1, 0.38 - 0.08, 0.38 + 0.08, segs); }
        if (p.lod < 2) return;
        // the areoles down every rib, each with its spines; the ones on the outline stand clear of it
        int rows = Math.max(2, (int) (len / 2.6));
        double[] ridge = {-1, -0.82, -0.42, 0, 0.4, 0.8, 1};
        for (int i = 0; i < rows; i++) {
            for (int q = 0; q < ridge.length; q++) {
                double t = t0 + (1 - t0) * (i + ((q & 1) == 0 ? 0.3 : 0.75)) / rows, a = ridge[q];
                if (t > 0.985) continue;
                p.at(t, a);
                double x = p.X, y = p.Y, ux = p.TX, uy = p.TY, nx = -uy, ny = ux;
                if (x + 3 < p.cx0 || x - 3 > p.cx1 || y + 3 < p.cy0 || y - 3 > p.cy1) continue;
                if (Math.abs(a) == 1) {
                    Pen.tri(ps[4], x - ux * 0.16, y - uy * 0.16, x + ux * 0.16, y + uy * 0.16, x + a * nx * 1.15 - ux * 0.25, y + a * ny * 1.15 - uy * 0.25);
                    continue;
                }
                Path2D.Float sp = ps[4];
                sp.moveTo((float) (x + ux * 0.3), (float) (y + uy * 0.3)); sp.lineTo((float) (x + nx * 0.26), (float) (y + ny * 0.26));
                sp.lineTo((float) (x - ux * 0.3), (float) (y - uy * 0.3)); sp.lineTo((float) (x - nx * 0.26), (float) (y - ny * 0.26)); sp.closePath();
                if (p.lod >= 3) for (int e = -1; e <= 1; e++) {
                    double dx = e * nx * 0.75 - ux * 0.65, dy = e * ny * 0.75 - uy * 0.65;
                    Pen.tri(sp, x - nx * 0.09, y - ny * 0.09, x + nx * 0.09, y + ny * 0.09, x + dx * 1.0, y + dy * 1.0);
                }
            }
        }
    }

    /** The rounded top of a cactus limb at (x, y) of the straight plant, lit from the left; white blossoms in season; snow when there is some. */
    private static void top(Pen p, Path2D.Float[] ps, double x, double y, double hw, boolean flowers) {
        p.blob(ps[0], x, y, hw, hw, 12);
        p.blob(ps[1], x - hw * 0.45, y - hw * 0.25, hw * 0.4, hw * 0.55, 8);
        p.blob(ps[2], x + hw * 0.55, y - hw * 0.2, hw * 0.35, hw * 0.5, 8);
        if (flowers) {
            p.blob(ps[5], x - hw * 0.4, y - hw * 0.92, 1.25, 1.0, 8);
            p.blob(ps[5], x + hw * 0.35, y - hw * 1.0, 1.15, 0.95, 8);
        }
        if (p.snowOn) p.snowAt(x, y, hw * 0.62, hw * (0.6 + 0.3 * p.snow), hw * (0.45 + 0.5 * p.snow), Double.doubleToLongBits(x * 17 + y));
    }

    // ------------------------------------------------------------------ the bare snag

    private static void dead(Pen p, Model.Obj o, Random r, double k) {
        // a weathered trunk broken off at the top, its limbs branching down to fine bare twigs
        double w = o.w, hh = o.h, tw = Math.max(2.8, w * 0.18), bend = (r.nextDouble() - 0.5) * w * 0.3;
        p.bend(k, hh, 1.2);
        p.bark(new Color(128, 114, 100), new Color(74, 64, 58));
        p.curve(0, 1, bend * 0.3, -hh * 0.45, bend, -hh * 0.84, tw * 0.75, tw * 0.25, 0);
        // (dry, brittle wood: the snag breaks low and what falls lies flat, its limbs bent under it)
        p.trunk(hh * 0.16, hh * 0.34, bend, -hh * 0.84, Math.max(1.6, w * 0.10), hh * 0.84, tw * 0.8, 0.6);
        p.broken(Lit.mix(new Color(226, 202, 158), new Color(158, 138, 112), p.wet), Lit.mix(new Color(172, 148, 114), new Color(112, 98, 86), p.wet), new Color(70, 60, 54));
        p.strip(p.wood, 0, 1, -1, 1, 6);
        if (p.lod >= 1) {
            p.strip(p.woodShade, 0, 1, 0.25, 1, 6);
            p.strip(p.woodLite, 0.04, 0.95, -0.9, -0.5, 6);              // the sun-bleached flank
        }
        if (p.lod >= 2) {
            for (int j = 0; j < 4; j++) {
                double a0 = -0.4 + 0.3 * j + 0.1 * hash(o.seed + j * 19), t0 = 0.05 + 0.5 * hash(o.seed + j * 37 + 3);
                p.strip(p.woodMark, t0, Math.min(1, t0 + 0.2 + 0.25 * hash(o.seed + j * 43 + 4)), a0, a0 + 0.1, 3);
            }
            p.part(p.curveY(0.36));
            p.blob(p.woodMark, p.curveX(0.36) + tw * 0.1, p.curveY(0.36), tw * 0.17, tw * 0.3, 8);                    // a knot hole
        }
        p.endTrunk();
        p.triL(p.wood, bend - tw * 0.25, -hh * 0.835, bend + tw * 0.25, -hh * 0.81, bend + tw * 0.06, -hh);
        if (p.lod >= 1) p.triL(p.wood, bend - tw * 0.25, -hh * 0.83, bend - tw * 0.02, -hh * 0.83, bend - tw * 0.2, -hh * 0.93);    // a second splinter
        int n = 4 + r.nextInt(2);
        int depth = p.lod == 0 ? 1 : p.lod == 1 ? 2 : 3;
        for (int i = 0; i < n; i++) {
            double u = 0.40 + 0.42 * i / (n - 1.0), side = i % 2 == 0 ? -1 : 1;
            double bx = bend * u, by = -hh * u, ex = bx + side * w * (0.50 + 0.45 * r.nextDouble()), ey = by - hh * (0.13 + 0.12 * r.nextDouble());
            r.nextDouble();
            branch(p, o.seed * 3 + i * 211L, bx, by, ex, ey, Math.max(0.5, tw * 0.20 * (1 - u * 0.5)), depth, true);
        }
        if (p.snowOn) p.pillow(bend, -hh * 0.86, tw * 0.4);
    }

    /** A bare limb from (x0, y0) to (x1, y1) of the straight plant, and the twigs that leave it. */
    private static void branch(Pen p, long seed, double x0, double y0, double x1, double y1, double w0, int depth, boolean limb) {
        double dx = x1 - x0, dy = y1 - y0, len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.6) return;
        // (a limb sags under its weight and turns up towards its end)
        p.curve(x0, y0, x0 + dx * 0.5, y0 + dy * 0.5 + len * 0.10, x1, y1, w0, Math.max(0.16, w0 * 0.3), 0);
        p.strip(p.wood, 0, 1, -1, 1, limb ? 4 : 2);
        if (limb && p.lod >= 1) p.strip(p.woodShade, 0, 0.8, dx > 0 ? 0.1 : -1, dx > 0 ? 1 : -0.1, 4);
        if (p.snowOn && w0 > 0.3) for (double t = 0.3; t < 1; t += 0.3) p.pillow(p.curveX(t), p.curveY(t) - w0 * 0.5, Math.max(0.9, w0 * 1.6));
        if (depth <= 0) return;
        int kids = limb ? 3 : 2;
        double[] sx = new double[kids], sy = new double[kids];
        for (int c = 0; c < kids; c++) { double t = 0.38 + 0.5 * (c + hash(seed + c * 31 + 7) * 0.6) / kids; sx[c] = p.curveX(t); sy[c] = p.curveY(t); }
        for (int c = 0; c < kids; c++) {
            double h1 = hash(seed + c * 53 + 1), h2 = hash(seed + c * 59 + 2), sd = (c % 2 == 0) == (hash(seed + 3) < 0.5) ? -1 : 1;
            // a twig leaves at an angle and reaches for the light
            double a = sd * (0.55 + 0.5 * h1), ca = Math.cos(a), sa = Math.sin(a), l2 = len * (0.5 - 0.09 * c) * (0.75 + 0.4 * h2);
            double tx = (dx * ca - dy * sa) / len, ty = (dx * sa + dy * ca) / len - 0.35;
            double tl = Math.sqrt(tx * tx + ty * ty);
            branch(p, mix(seed + c * 7919L + 13), sx[c], sy[c], sx[c] + tx / tl * l2, sy[c] + ty / tl * l2, Math.max(0.2, w0 * 0.5), depth - 1, false);
        }
    }
}
