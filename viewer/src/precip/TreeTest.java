package precip;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase O: the checks of {@link TreePainter} (run with {@code --selftest} / {@code --selftest-ui}), and the tool that
 * draws the evidence sheets of {@code tools/snap_v26_trees.sh}:
 *
 * <pre>
 *   java -cp viewer/out_trees precip.TreeTest --sheet out.png [--zoom 2] [--light day|dusk|night] [--rows dry,wind,wet,snow,bent,snapped,back]
 *                                             [--variants oak,pine] [--cols N] [--seed N]
 *   java -cp viewer/out_trees precip.TreeTest --lod oak out.png [--light day]        one variant at zoom 0.5, 1, 2 and 4
 * </pre>
 *
 * Every tree is drawn through {@link BuildingPainter#paintTree}, as the renderer and the Debug line-up draw it.
 */
final class TreeTest {
    private int step;
    private final List<String> failures = new ArrayList<>();

    private void check(boolean ok, String what) {
        step++;
        System.out.println(String.format("[tree-test] %2d  %s  %s", step, ok ? "ok  " : "FAIL", what));
        if (!ok) failures.add(what);
    }

    static int run() {
        TreeTest t = new TreeTest();
        Advanced keepAdv = Advanced.cur;
        try {
            t.go();
        } catch (Throwable ex) {
            ex.printStackTrace();
            t.failures.add("exception: " + ex);
        } finally {
            Advanced.publish(keepAdv);
        }
        if (t.failures.isEmpty()) {
            System.out.println("[tree-test] PASS (" + t.step + " checks)   [not checked here: how good the trees look (see viewer/snapshots/v26/trees), the frame time (--bench)]");
            return 0;
        }
        System.out.println("[tree-test] FAIL");
        for (String f : t.failures) System.out.println("[tree-test]   - " + f);
        return 1;
    }

    // ------------------------------------------------------------------ one tree on a transparent canvas

    private static final String[] LEAFY = {"pine", "bush", "birch", "oak", "palm", "acacia", "shrub"};

    private static DebugLineup.Entry entry(String variant) {
        for (DebugLineup.Entry e : DebugLineup.entries()) if (e.tree() && e.variant.equals(variant)) return e;
        throw new IllegalArgumentException("no tree variant " + variant);
    }

    private static Model.Obj obj(String variant, double damage, double snow, double wet) {
        DebugLineup.Entry e = entry(variant);
        return DebugLineup.obj(e, 3, 1234, damage, snow, wet, 0);
    }

    /** The picture of one tree: canvas of cw x ch device px, the root at (cw/2, ch - 12), painted in {@code bands} horizontal clip strips. */
    private static int[] shot(Model.Obj o, Lit lit, double zoom, double lean, double haze, int cw, int ch, int bands) {
        BufferedImage img = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        for (int b = 0; b < bands; b++) {
            Graphics2D g = img.createGraphics();
            DebugScreen.hints(g);
            int y0 = ch * b / bands, y1 = ch * (b + 1) / bands;
            g.setClip(0, y0, cw, y1 - y0);
            g.translate(cw / 2, ch - 12);
            g.scale(zoom, zoom);
            BuildingPainter.paintTree(g, o, lit, lean, haze);
            g.dispose();
        }
        int[] out = new int[cw * ch];
        img.getRGB(0, 0, cw, ch, out, 0, cw);
        return out;
    }

    /** The same through the live pass (the sprite cache). */
    private static int[] live(Model.Obj o, Lit lit, double zoom, double lean, boolean steady, int cw, int ch, int bands) {
        BufferedImage img = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        for (int b = 0; b < bands; b++) {
            Graphics2D g = img.createGraphics();
            DebugScreen.hints(g);
            int y0 = ch * b / bands, y1 = ch * (b + 1) / bands;
            g.setClip(0, y0, cw, y1 - y0);
            g.translate(cw / 2, ch - 12);
            g.scale(zoom, zoom);
            TreePainter.paintLive(g, o, lit, lean, steady);
            g.dispose();
        }
        int[] out = new int[cw * ch];
        img.getRGB(0, 0, cw, ch, out, 0, cw);
        return out;
    }

    private static int[] shot(Model.Obj o, Lit lit, double zoom, double lean, double haze) {
        return shot(o, lit, zoom, lean, haze, (int) ((o.w * 2.6 + o.h * 2.2) * zoom) + 40, (int) (o.h * 1.5 * zoom) + 40, 1);
    }

    private static int width(Model.Obj o, double zoom) { return (int) ((o.w * 2.6 + o.h * 2.2) * zoom) + 40; }

    private static int diff(int[] a, int[] b) {
        int n = 0;
        for (int k = 0; k < a.length; k++) if (a[k] != b[k]) n++;
        return n;
    }

    /** {covered pixels, mean r, g, b over them, mean x, top row, bottom row, mean x of the top third, mean x of the lowest tenth}. */
    private static double[] stats(int[] px, int w) {
        int h = px.length / w, top = h, bot = -1;
        long n = 0, r = 0, g = 0, b = 0, sx = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int p = px[y * w + x];
            if ((p >>> 24) < 128) continue;
            n++; r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; sx += x;
            top = Math.min(top, y); bot = Math.max(bot, y);
        }
        if (n == 0) return new double[9];
        long nt = 0, st = 0, nb = 0, sb = 0;
        int yt = top + (bot - top) / 3, yb = bot - (bot - top) / 10;
        for (int y = top; y <= bot; y++) for (int x = 0; x < w; x++) {
            if ((px[y * w + x] >>> 24) < 128) continue;
            if (y <= yt) { nt++; st += x; }
            if (y >= yb) { nb++; sb += x; }
        }
        return new double[]{n, r / (double) n, g / (double) n, b / (double) n, sx / (double) n, top, bot, st / (double) Math.max(1, nt), sb / (double) Math.max(1, nb)};
    }

    private static double lum(double[] s) { return s[1] + s[2] + s[3]; }

    /** Changes of tone along the rows of the covered area, per 100 covered pixels: a clump has few, a crown of single leaves many. */
    private static double texture(int[] px, int w) {
        int h = px.length / w;
        long n = 0, edges = 0;
        for (int y = 0; y < h; y++) for (int x = 1; x < w; x++) {
            int p = px[y * w + x], q = px[y * w + x - 1];
            if ((p >>> 24) < 250 || (q >>> 24) < 250) continue;
            n++;
            int d = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) + Math.abs((p & 255) - (q & 255));
            if (d > 30) edges++;
        }
        return n == 0 ? 0 : 100.0 * edges / n;
    }

    private static int greens(int[] px) {
        int n = 0;
        for (int p : px) {
            if ((p >>> 24) < 200) continue;
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            if (g > r + 14 && g > b + 14) n++;
        }
        return n;
    }

    private static int whites(int[] px) {
        int n = 0;
        for (int p : px) {
            if ((p >>> 24) < 200) continue;
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            if (r > 200 && g > 200 && b > 200) n++;
        }
        return n;
    }

    /**
     * "Nothing floats": the covered pixels that are not joined to the ground through the picture. The picture is laid
     * on a grid of cells a few px wide (leaves of one crown are not pixel to pixel neighbours, but they are cell to
     * cell); a cell with something in it is reached from the ground row through cells with something in them.
     */
    private static int loosePx(int[] px, int cw, int ch, int gy, double zoom) {
        final int c = Math.max(3, (int) Math.ceil(2.5 * zoom)), gw = (cw + c - 1) / c, gh = (ch + c - 1) / c;
        int[] n = new int[gw * gh];
        for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) if ((px[y * cw + x] >>> 24) > 128) n[(y / c) * gw + x / c]++;
        boolean[] seen = new boolean[gw * gh];
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (int gyc = Math.max(0, (gy - c) / c); gyc <= Math.min(gh - 1, (gy + 2) / c); gyc++)
            for (int gx = 0; gx < gw; gx++) if (n[gyc * gw + gx] > 0 && !seen[gyc * gw + gx]) { seen[gyc * gw + gx] = true; q.add(gyc * gw + gx); }
        while (!q.isEmpty()) {
            int i = q.poll(), x = i % gw, y = i / gw;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= gw || yy >= gh) continue;
                int j = yy * gw + xx;
                if (n[j] > 0 && !seen[j]) { seen[j] = true; q.add(j); }
            }
        }
        int loose = 0;
        for (int i = 0; i < n.length; i++) if (!seen[i]) loose += n[i];
        return loose;
    }

    /** The length of the covered run of a row about column x (0 when nothing is covered within 3 px of it). */
    private static int run(int[] px, int cw, int row, int x) {
        if (row < 0 || (row + 1) * cw > px.length) return 0;
        int at = -1;
        for (int d = 0; d <= 3 && at < 0; d++) {
            if (x + d < cw && (px[row * cw + x + d] >>> 24) > 128) at = x + d;
            else if (x - d >= 0 && (px[row * cw + x - d] >>> 24) > 128) at = x - d;
        }
        if (at < 0) return 0;
        int a = at, b = at;
        while (a > 0 && (px[row * cw + a - 1] >>> 24) > 128) a--;
        while (b < cw - 1 && (px[row * cw + b + 1] >>> 24) > 128) b++;
        return b - a + 1;
    }

    /** Is this pixel the fresh surface of a break (pale wood; the pale flesh of a cactus)? */
    private static boolean fresh(int p, String variant) {
        if ((p >>> 24) < 200) return false;
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return variant.equals("cactus") ? g > r + 10 && g > b + 30 && g > 160 : r >= g && g > b + 22 && r > 150;
    }

    // ------------------------------------------------------------------ the checks

    private void go() {
        final Lit day = new Lit(0.45, 0), night = new Lit(0.02, 0);
        final double[] zooms = {0.4, 1, 2, 4.2};

        // ---- every variant, every level: drawn, the same twice, the same through four clip bands, the transform put back
        StringBuilder bad = new StringBuilder();
        int pictures = 0;
        for (String v : BuildingPainter.TREE_VARIANTS) {
            for (double z : zooms) {
                for (int state = 0; state < 6; state++) {
                    Model.Obj o = obj(v, state == 2 ? 0.3 : state == 3 ? 0.85 : state == 4 ? 0.5 : state == 5 ? 0.85 : 0, state == 1 || state == 5 ? 0.7 : 0, state == 1 ? 0.6 : 0);
                    double lean = state == 0 ? -0.2 : 0;
                    int cw = width(o, z), ch = (int) (o.h * 1.5 * z) + 40;
                    int[] a = shot(o, day, z, lean, 0, cw, ch, 1), b = shot(o, day, z, lean, 0, cw, ch, 1), c = shot(o, day, z, lean, 0, cw, ch, 4), d = shot(o, day, z, lean, 0, cw, ch, 7);
                    pictures++;
                    if (stats(a, cw)[0] < 12) bad.append(String.format(Locale.ROOT, " %s@%.1f/state %d: nothing drawn;", v, z, state));
                    if (diff(a, b) != 0) bad.append(String.format(Locale.ROOT, " %s@%.1f/state %d: two renders differ in %d px;", v, z, state, diff(a, b)));
                    if (diff(a, c) != 0 || diff(a, d) != 0) bad.append(String.format(Locale.ROOT, " %s@%.1f/state %d: 4 bands differ in %d px, 7 bands in %d;", v, z, state, diff(a, c), diff(a, d)));
                }
            }
            BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.translate(32, 60);
            g.scale(0.5, 0.5);
            AffineTransform before = g.getTransform();
            BuildingPainter.paintTree(g, obj(v, 0.85, 0, 0), day, 0.2, 0);
            if (!before.equals(g.getTransform())) bad.append(' ').append(v).append(": the transform is not put back;");
            g.dispose();
        }
        check(bad.length() == 0, "all " + BuildingPainter.TREE_VARIANTS.length + " variants at 4 zooms (clumps, leaves, blades, outlines) x whole in wind / wet with snow / bent / down / hanging / down under snow: "
                + pictures + " pictures drawn, each the same on a second render and the same pixel for pixel through 4 and 7 clip bands" + bad);

        // ---- the level of detail goes by device pixels, and a change of level does not jump
        bad.setLength(0);
        double worstCol = 0, worstArea = 0;
        for (String v : LEAFY) {
            double unit = TreePainter.leafUnit(v);
            if (TreePainter.lod(v, 0.4) != 0 || TreePainter.lod(v, 4.2) != 3) bad.append(' ').append(v).append(": level ").append(TreePainter.lod(v, 0.4)).append(" at zoom 0.4, ").append(TreePainter.lod(v, 4.2)).append(" at 4.2;");
            for (double edge : new double[]{TreePainter.CLUMP_PX, TreePainter.BLADE_PX, TreePainter.FINE_PX}) {
                double z0 = edge / unit * 0.985, z1 = edge / unit * 1.015;
                Model.Obj o = obj(v, 0, 0, 0);
                int w0 = width(o, z0), w1 = width(o, z1);
                double[] s0 = stats(shot(o, day, z0, 0, 0), w0), s1 = stats(shot(o, day, z1, 0, 0), w1);
                double col = Math.max(Math.abs(s0[1] - s1[1]), Math.max(Math.abs(s0[2] - s1[2]), Math.abs(s0[3] - s1[3])));
                double area = Math.abs(s1[0] / (z1 * z1) - s0[0] / (z0 * z0)) / (s0[0] / (z0 * z0));
                double hgt = Math.abs((s1[6] - s1[5]) / z1 - (s0[6] - s0[5]) / z0);
                worstCol = Math.max(worstCol, col); worstArea = Math.max(worstArea, area);
                if (TreePainter.lod(v, z0) + 1 != TreePainter.lod(v, z1)) bad.append(String.format(Locale.ROOT, " %s: no change of level at %.1f px;", v, edge));
                if (col > 14 || area > 0.16 || hgt > 3.5) bad.append(String.format(Locale.ROOT, " %s at %.1f px: mean colour jumps by %.1f (%.0f,%.0f,%.0f to %.0f,%.0f,%.0f), covered area by %.0f %% (%.0f to %.0f), height by %.1f world px;",
                        v, edge, col, s0[1], s0[2], s0[3], s1[1], s1[2], s1[3], area * 100, s0[0] / (z0 * z0), s1[0] / (z1 * z1), hgt));
            }
        }
        check(bad.length() == 0, String.format(Locale.ROOT, "the level of detail follows the device size of a leaf (%.1f / %.1f / %.1f px), and across each change of level the tree keeps its mean colour "
                + "(worst jump %.1f of 255), its covered area (worst %.0f %%) and its height%s", TreePainter.CLUMP_PX, TreePainter.BLADE_PX, TreePainter.FINE_PX, worstCol, worstArea * 100, bad));

        // ---- single leaves can be told apart close up; far away the crown is calm
        bad.setLength(0);
        StringBuilder tex = new StringBuilder();
        for (String v : LEAFY) {
            Model.Obj o = obj(v, 0, 0, 0);
            double t05 = texture(shot(o, day, 0.5, 0, 0), width(o, 0.5)), t2 = texture(shot(o, day, 2, 0, 0), width(o, 2)), t4 = texture(shot(o, day, 4, 0, 0), width(o, 4));
            tex.append(String.format(Locale.ROOT, " %s %.0f/%.0f", v, t2, t4));
            if (t2 < 9 || t4 < 5) bad.append(String.format(Locale.ROOT, " %s: only %.1f / %.1f tone edges per 100 px at zoom 2 / 4;", v, t2, t4));
        }
        check(bad.length() == 0, "at zoom 2 and 4 the foliage of every leafy variant is made of separate leaves (tone edges per 100 covered px at zoom 2/4:" + tex + ")" + bad);

        // ---- what the old trees did, the new ones still do
        bad.setLength(0);
        for (String v : BuildingPainter.TREE_VARIANTS) {
            Model.Obj dry = obj(v, 0, 0, 0), wet = obj(v, 0, 0, 1), snow = obj(v, 0, 0.8, 0), bent = obj(v, 0.3, 0, 0), snapped = obj(v, 0.85, 0, 0);
            int w = width(dry, 2);
            int[] pd = shot(dry, day, 2, 0, 0), pw = shot(wet, day, 2, 0, 0), ps = shot(snow, day, 2, 0, 0), pn = shot(dry, night, 2, 0, 0), ph = shot(dry, day, 2, 0, 0.5);
            double[] sd = stats(pd, w), sw = stats(pw, w), sn = stats(pn, w), sh = stats(ph, w);
            if (lum(sw) > lum(sd) - 12) bad.append(' ').append(v).append(": wet is not darker;");
            if (whites(ps) < whites(pd) + 60 * (v.equals("dead") || v.equals("cactus") ? 0.3 : 1)) bad.append(' ').append(v).append(": no snow on it (").append(whites(ps)).append(" white px, dry ").append(whites(pd)).append(");");
            if (lum(sn) > lum(sd) * 0.62) bad.append(' ').append(v).append(": the night is not dark;");
            Color hz = day.haze;
            double dDry = Math.abs(sd[1] - hz.getRed()) + Math.abs(sd[2] - hz.getGreen()) + Math.abs(sd[3] - hz.getBlue());
            double dHaze = Math.abs(sh[1] - hz.getRed()) + Math.abs(sh[2] - hz.getGreen()) + Math.abs(sh[3] - hz.getBlue());
            if (dHaze > dDry * 0.62) bad.append(' ').append(v).append(": haze does not move its colour to the air's;");
            // wind: the top goes with the lean (a negative lean blows it to the right), the root stays
            double[] lr = stats(shot(dry, day, 2, -0.3, 0), w), ll = stats(shot(dry, day, 2, 0.3, 0), w);
            boolean low = v.equals("bush") || v.equals("shrub");
            double want = v.equals("cactus") ? 0.6 : low ? 1.5 : 3;
            if (lr[7] - sd[7] < want || sd[7] - ll[7] < want) bad.append(String.format(Locale.ROOT, " %s: the top moves %.1f / %.1f px in wind;", v, lr[7] - sd[7], ll[7] - sd[7]));
            if (!v.equals("bush") && !v.equals("shrub") && Math.abs(lr[8] - sd[8]) > 3) bad.append(String.format(Locale.ROOT, " %s: the root moves %.1f px in wind;", v, lr[8] - sd[8]));
            // storm: bent, then snapped with the stump left standing
            double[] sb = stats(shot(bent, day, 2, 0, 0), w), sx = stats(shot(snapped, day, 2, 0, 0), w);
            if (Math.max(Math.abs(sb[7] - sd[7]), Math.abs(sb[4] - sd[4])) < (low ? 2.5 : 4)) bad.append(String.format(Locale.ROOT, " %s: bent moves the top only %.1f px, the whole %.1f;", v, sb[7] - sd[7], sb[4] - sd[4]));
            if (!low) {
                // (the crown lies to one side of the stump: the picture's centre of mass has moved by a good part of the tree's height)
                // (a cactus only loses its top: less of it moves)
                if (Math.abs(sx[4] - sd[4]) < snapped.h * 2 * (v.equals("cactus") ? 0.05 : 0.22)) bad.append(String.format(Locale.ROOT, " %s: snapped moves the tree only %.0f px to the side;", v, sx[4] - sd[4]));
                int[] px = shot(snapped, day, 2, 0, 0);
                int h = px.length / w, stump = 0;
                for (int y = h - 12 - (int) (snapped.h * 0.10 * 2); y < h - 13; y++) if ((px[y * w + w / 2] >>> 24) > 200) stump++;
                if (stump < 3) bad.append(' ').append(v).append(": no stump left;");
            }
            int green = greens(pd);
            if (v.equals("dead") ? green > 0 : !v.equals("shrub") && green < 200) bad.append(' ').append(v).append(": ").append(green).append(" green px;");
        }
        check(bad.length() == 0, "every variant: wet darkens it, snow lies on it, the night darkens it, haze takes it towards the colour of the air, wind moves the top and not the root "
                + "(both ways), storm damage bends it and then snaps it leaving the stump (bush and shrub only bend), the snag stays bare" + bad);

        // ---- the storm ladder: nothing of a damaged tree floats, at any step (2026-10-12: the broken tree was redrawn)
        bad.setLength(0);
        StringBuilder poses = new StringBuilder();
        final double[] ladder = {0.30, 0.38, 0.44, 0.50, 0.60, 0.70, 0.85};
        int ladderPics = 0;
        double worstLoose = 0, worstContact = 1;
        for (String v : BuildingPainter.TREE_VARIANTS) {
            final boolean snaps = TreePainter.snaps(v);
            for (double z : new double[]{1, 2, 4}) {
                double lastTheta = 0;
                for (int k = 0; k <= ladder.length; k++) {
                    // (the last one through the live pass: the cached sprite, built from the damage in steps)
                    final boolean viaLive = k == ladder.length;
                    final double dmg = viaLive ? 0.85 : ladder[k];
                    Model.Obj o = obj(v, dmg, 0, 0);
                    int cw = (int) ((o.w * 2.6 + o.h * 2.6) * z) + 60, ch = (int) (o.h * 1.5 * z) + 40, gy = ch - 12;
                    int[] px = viaLive ? live(o, day, z, 0.1, true, cw, ch, 1) : shot(o, day, z, 0, 0, cw, ch, 1);
                    ladderPics++;
                    String at = String.format(Locale.ROOT, " %s@%.0f damage %.2f%s:", v, z, dmg, viaLive ? " (live)" : "");
                    // (a) nothing under the ground line (the foot of a trunk goes 1 world px into it)
                    int under = 0, total = 0;
                    for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) if ((px[y * cw + x] >>> 24) > 128) { total++; if (y > gy + Math.ceil(z) + 1) under++; }
                    if (under > 0) bad.append(at).append(' ').append(under).append(" px under the ground;");
                    // (b) nothing floats: every part of the picture is joined to the ground through the picture itself
                    int loose = loosePx(px, cw, ch, gy, z);
                    worstLoose = Math.max(worstLoose, loose / (double) Math.max(1, total));
                    if (loose > Math.max(4 * z * z, total * 0.012)) bad.append(at).append(' ').append(loose).append(" of ").append(total).append(" px are not joined to the ground;");
                    double[] pose = TreePainter.breakPose(o);
                    if (!snaps || dmg <= TreePainter.SNAP) {
                        if (pose != null) bad.append(at).append(" broken, and should not be;");
                        continue;
                    }
                    if (pose == null) { bad.append(at).append(" not broken;"); continue; }
                    // (c) the ladder: the top hangs lower with every step, and from DOWN on it lies
                    // (while it slides off the stump its broken end comes down too, which takes a few degrees back: not a step up)
                    final boolean sliding = pose[4] > 0;
                    if (!viaLive && !sliding) {
                        if (pose[3] < lastTheta - 1e-9) bad.append(String.format(Locale.ROOT, "%s the top stands up again (%.0f deg after %.0f);", at, Math.toDegrees(pose[3]), Math.toDegrees(lastTheta)));
                        lastTheta = pose[3];
                    }
                    if (dmg >= TreePainter.DOWN && pose[3] < 0.9 && !v.equals("palm") && !v.equals("cactus")) bad.append(String.format(Locale.ROOT, "%s down, but the trunk is only %.0f deg over;", at, Math.toDegrees(pose[3])));
                    // (d) the stump keeps the trunk's width: just under the break it is as wide as the whole tree is there
                    final double brk = pose[0], hw = pose[1];
                    int row = gy - (int) Math.round((brk - Math.min(1.5, brk * 0.3)) * z);
                    int[] whole = shot(obj(v, 0, 0, 0), day, z, 0, 0, cw, ch, 1);
                    int runB = run(px, cw, row, cw / 2), runW = run(whole, cw, row, cw / 2);
                    if (runB < runW - 1 || runB < 2 * hw * z * 0.75 - 1.5) bad.append(String.format(Locale.ROOT, "%s the stump is %d px wide under the break, the trunk %d px (2 x %.1f world px);", at, runB, runW, hw));
                    // (e) the break shows torn wood: pale splinters about the stump's top
                    int paleN = 0;
                    final double reach = (hw + 10) * z + 2;
                    for (int y = Math.max(0, gy - (int) ((brk + 11) * z)); y <= Math.min(ch - 1, gy - (int) ((brk - 3.5) * z)); y++)
                        for (int x = (int) Math.max(0, cw / 2 - reach); x <= Math.min(cw - 1, cw / 2 + reach); x++) if (fresh(px[y * cw + x], v)) paleN++;
                    if (paleN < Math.max(2, (int) (1.5 * z * z))) bad.append(at).append(" only ").append(paleN).append(" px of fresh wood at the break;");
                    // (f) from DOWN on, what fell rests on the ground along its length, not on one point
                    if (dmg >= TreePainter.DOWN && !(sliding && pose[4] < 0.999 && (v.equals("palm") || v.equals("cactus")))) {
                        final double side = pose[2];

                        int x0 = cw, x1 = -1;
                        for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++)
                            if ((px[y * cw + x] >>> 24) > 128 && (x - cw / 2) * side > (hw + 6) * z) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); }
                        int cols = 0, touching = 0;
                        for (int x = x0; x <= x1; x++) {
                            boolean any = false, low = false;
                            for (int y = 0; y < ch; y++) if ((px[y * cw + x] >>> 24) > 128) { any = true; if (y >= gy - (int) Math.ceil(2.5 * z) - 1) low = true; }
                            if (any) { cols++; if (low) touching++; }
                        }
                        double contact = cols == 0 ? 0 : touching / (double) cols;
                        if (!v.equals("palm") && !v.equals("cactus")) worstContact = Math.min(worstContact, contact);
                        double need = v.equals("palm") ? 0.30 : 0.45;
                        if (cols < o.h * 0.25 * z || contact < need) bad.append(String.format(Locale.ROOT, "%s what fell touches the ground under %.0f %% of its length (%d columns);", at, contact * 100, cols));
                        if (z == 2 && !viaLive && dmg == 0.85) poses.append(String.format(Locale.ROOT, " %s broke at %.0f px, %s, %.0f deg over, on the ground under %.0f %%;", v, brk, pose[4] > 0.5 ? "off the stump" : "hinged", Math.toDegrees(pose[3]), contact * 100));
                    }
                }
            }
        }
        check(bad.length() == 0, String.format(Locale.ROOT, "the storm ladder (damage 0.30 bent, 0.38 cracked, 0.44 / 0.50 / 0.60 hanging, 0.70 / 0.85 down, and 0.85 through the live pass; all %d variants at zoom 1, 2, 4: %d pictures): "
                + "nothing is drawn under the ground line; nothing floats - every part, the torn-off branches and leaves too, is joined to the ground through the picture (worst %.2f %% of a picture's px loose); "
                + "the top hangs lower at every step and lies from damage %.2f on; the stump is as wide under the break as the whole trunk is there; fresh wood shows at the break; "
                + "what fell lies on the ground along its length (least %.0f %% of its columns reach the ground line; the old picture stood on one point). At zoom 2:%s%s",
                BuildingPainter.TREE_VARIANTS.length, ladderPics, worstLoose * 100, TreePainter.DOWN, worstContact * 100, poses, bad));

        // ---- the plants that do not snap
        bad.setLength(0);
        for (String v : new String[]{"bush", "shrub"}) {
            Model.Obj bent = obj(v, 0.3, 0, 0), flat = obj(v, 0.85, 0, 0);
            int w = width(bent, 2);
            double[] sb = stats(shot(bent, day, 2, 0, 0), w), sf = stats(shot(flat, day, 2, 0, 0), w);
            if (sf[6] - sf[5] > (sb[6] - sb[5]) * 0.86) bad.append(String.format(Locale.ROOT, " %s: %.0f px high at damage 0.85, %.0f at 0.3;", v, sf[6] - sf[5], sb[6] - sb[5]));
        }
        check(bad.length() == 0, "a bush and a shrub do not snap: at damage 0.85 they are pressed flat (lower than at 0.3 by more than a seventh) and torn" + bad);

        // ---- the live pass: a cached picture per tree
        bad.setLength(0);
        TreePainter.clearCache();
        long relit = 0, built = 0;
        double worstLive = 0;
        for (String v : BuildingPainter.TREE_VARIANTS) {
            for (double z : new double[]{0.6, 1, 2, 4}) {
                Model.Obj o = obj(v, 0, 0, 0);
                o.x = 3.3; o.y = -1.7;                                // (not on a whole pixel)
                int cw = width(o, z), ch = (int) (o.h * 1.5 * z) + 40;
                long b0 = TreePainter.BUILDS.sum();
                int[] a = live(o, day, z, -0.2, true, cw, ch, 1);
                long b1 = TreePainter.BUILDS.sum();
                int[] b = live(o, day, z, -0.2, true, cw, ch, 1), c = live(o, day, z, -0.2, true, cw, ch, 4);
                long b2 = TreePainter.BUILDS.sum(), r0 = TreePainter.RELIGHTS.sum();
                int[] n = live(o, night, z, -0.2, true, cw, ch, 1);
                relit += TreePainter.RELIGHTS.sum() - r0; built += b1 - b0;
                if (b1 - b0 != 1 || b2 != b1 || TreePainter.BUILDS.sum() != b2) bad.append(String.format(Locale.ROOT, " %s@%.1f: %d builds for the first frame, %d after;", v, z, b1 - b0, TreePainter.BUILDS.sum() - b1));
                if (diff(a, b) != 0 || diff(a, c) != 0) bad.append(String.format(Locale.ROOT, " %s@%.1f: a second frame differs in %d px, 4 bands in %d;", v, z, diff(a, b), diff(a, c)));
                // the sprite is the tree the direct painter draws: same colour by day and by night, same place, same lean
                o.x = 0; o.y = 0;
                double[] sl = stats(a, cw), sn = stats(n, cw), sd = stats(shot(o, day, z, -0.2, 0, cw, ch, 1), cw), sdn = stats(shot(o, night, z, -0.2, 0, cw, ch, 1), cw);
                double col = Math.max(Math.abs(sl[1] - sd[1]), Math.max(Math.abs(sl[2] - sd[2]), Math.abs(sl[3] - sd[3])));
                col = Math.max(col, Math.max(Math.abs(sn[1] - sdn[1]), Math.max(Math.abs(sn[2] - sdn[2]), Math.abs(sn[3] - sdn[3]))));
                worstLive = Math.max(worstLive, col);
                if (col > 6 || Math.abs(sl[0] - sd[0]) > sd[0] * 0.06 + 20 || Math.abs(sl[7] - 3.3 * z - sd[7]) > 2.5 || Math.abs(sl[5] + 1.7 * z - sd[5]) > 2.5)
                    bad.append(String.format(Locale.ROOT, " %s@%.1f: sprite against direct: colour %.1f, area %.0f / %.0f px, top at x %.1f / %.1f, y %.0f / %.0f;", v, z, col, sl[0], sd[0], sl[7] - 3.3 * z, sd[7], sl[5], sd[5]));
                // while the zoom moves the picture of the nearest power of two is stretched: about the same tree
                o.x = 3.3; o.y = -1.7;
                double zz = z * 1.19;
                int cw2 = width(o, zz), ch2 = (int) (o.h * 1.5 * zz) + 40;
                double[] sz = stats(live(o, day, zz, -0.2, false, cw2, ch2, 1), cw2), se = stats(live(o, day, zz, -0.2, true, cw2, ch2, 1), cw2);
                if (Math.abs(sz[0] - se[0]) > se[0] * 0.2 + 30 || Math.abs((sz[6] - sz[5]) - (se[6] - se[5])) > 4 + 0.05 * (se[6] - se[5]))
                    bad.append(String.format(Locale.ROOT, " %s@%.2f moving: area %.0f against %.0f px at rest, height %.0f against %.0f;", v, zz, sz[0], se[0], sz[6] - sz[5], se[6] - se[5]));
            }
        }
        // the cache is bounded: many more trees than it holds
        for (int k = 0; k < 160; k++) {
            Model.Obj o = DebugLineup.obj(entry("oak"), 3, 5000 + k, 0, 0, 0, 0);
            live(o, day, 4, 0, true, 700, 420, 1);
        }
        long[] held = TreePainter.cacheSize();
        if (held[1] > TreePainter.CACHE_PX || held[0] < 4) bad.append(" the cache holds ").append(held[0]).append(" sprites, ").append(held[1]).append(" px;");
        TreePainter.clearCache();
        if (TreePainter.cacheSize()[0] != 0) bad.append(" clearCache leaves sprites;");
        check(bad.length() == 0, String.format(Locale.ROOT, "the live pass: a tree is drawn once (%d sprites for %d trees), a second frame and 4 clip bands copy the same pixels, a new light relights the sprite "
                + "without redrawing it (%d relights), the sprite is the tree the direct painter draws (mean colour within %.1f of 255, day and night), a moving zoom stretches the nearest picture; "
                + "after 160 more oaks at zoom 4 the cache holds %d sprites, %d px (cap %d)%s", built, built, relit, worstLive, held[0], held[1], TreePainter.CACHE_PX, bad));

        // ---- in the world: the same frame twice, and one render band against four
        bad.setLength(0);
        ParallelRenderer p1 = renderer(1), p4 = renderer(4);
        int trees = 0, onTrees = 0, elsewhere = 0;
        try {
            for (String scene : new String[]{null, "biome:tropical", "biome:savanna"}) {
                FakeSim sim = new FakeSim(1234, 0.45, 3840, 1080, null, null, Double.NaN, Double.NaN, scene);
                ViewerState st = new ViewerState();
                st.acceptLine(sim.hello());
                for (int k = 0; k < 40; k++) st.acceptLine(sim.step());
                // the place with the most trees about it
                Model.Obj best = null;
                int most = -1;
                Model.Obj[] objs;
                synchronized (st) { objs = st.objects; }
                for (Model.Obj o : objs) {
                    if (!o.kind.equals("tree")) continue;
                    int n = 0;
                    for (Model.Obj q : objs) if (q.kind.equals("tree") && Math.abs(q.x - o.x) < 220) n++;
                    if (n > most) { most = n; best = o; }
                }
                if (best == null) { bad.append(" no tree in scene ").append(scene).append(';'); continue; }
                for (double zoom : new double[]{0.6, 1, 2, 4}) {
                    Camera cam = new Camera();
                    p4.layout(cam, st, SW, SH);
                    p1.layout(cam, st, SW, SH);
                    cam.set(best.x, best.y - 150 / zoom, zoom, true);
                    int[] a = world(p4, st, cam), b = world(p4, st, cam), c = world(p1, st, cam);
                    trees += most;
                    if (diff(a, b) != 0) bad.append(String.format(Locale.ROOT, " %s zoom %.1f: two frames differ in %d px;", scene, zoom, diff(a, b)));
                    // (pixels that differ away from every tree are not the trees' doing: the build before Phase O differs in them too)
                    Camera.View cv = p4.primary().view;
                    for (int q = 0; q < a.length; q++) {
                        if (a[q] == c[q]) continue;
                        double wx = (q % SW - cv.wtx) / cv.ws, wy = (q / SW - cv.wty) / cv.ws;
                        boolean onTree = false;
                        for (Model.Obj o : objs) if (o.kind.equals("tree") && Math.abs(wx - o.x) < o.w * (o.variant.equals("palm") ? 1.8 : 0.95) + 4 && wy > o.y - o.h * 1.35 - 4 && wy < o.y + 4) onTree = true;
                        if (onTree) { onTrees++; if (bad.length() < 600) bad.append(String.format(Locale.ROOT, " %s zoom %.1f: 4 bands against 1 differ on a tree at world %.0f,%.0f;", scene, zoom, wx, wy)); }
                        else elsewhere++;
                    }
                }
            }
        } finally {
            p4.shutdown();
            p1.shutdown();
        }
        check(bad.length() == 0, "the world renderer on the three most wooded places (temperate, tropical, savanna) at zoom 0.6, 1, 2 and 4: a frame drawn twice is identical, "
                + "and 4 render bands give the picture of 1 band pixel for pixel on and about every tree (" + onTrees + " px differ there; " + elsewhere
                + " px differ elsewhere in the 12 pictures, away from any tree, as they do in the build before Phase O)" + bad);
    }

    private static final int SW = 1280, SH = 720;

    private static ParallelRenderer renderer(int bands) {
        ParallelRenderer pr = new ParallelRenderer(bands);
        pr.shared.setAsync(false);
        Renderer r = pr.primary();
        r.showRightPanel = false; r.showLeftPanel = false; r.showMinimap = false; r.showLabels = false; r.showArrows = false; r.showWind = false; r.showClimate = false;
        return pr;
    }

    private static int[] world(ParallelRenderer pr, ViewerState st, Camera cam) {
        Advanced.publish(Advanced.DEFAULT);
        BufferedImage img = new BufferedImage(SW, SH, BufferedImage.TYPE_INT_RGB);
        double t;
        synchronized (st) { t = st.cur.t; }
        pr.render(img, 1, 0, 0, SW, SH, st, cam, -1, t, "test");
        int[] out = new int[SW * SH];
        img.getRGB(0, 0, SW, SH, out, 0, SW);
        return out;
    }

    // ------------------------------------------------------------------ the evidence sheets

    /** A state of the sheets: {damage, snow, wet, lean, haze, scale}. */
    private static double[] state(String name) {
        return switch (name) {
            case "dry" -> new double[]{0, 0, 0, 0, 0, 1};
            case "wind" -> new double[]{0, 0, 0, -0.26, 0, 1};
            case "windleft" -> new double[]{0, 0, 0, 0.26, 0, 1};
            case "wet" -> new double[]{0, 0, 1, 0, 0, 1};
            case "snow" -> new double[]{0, 0.8, 0, 0, 0, 1};
            case "lightsnow" -> new double[]{0, 0.3, 0, 0, 0, 1};
            case "bent" -> new double[]{0.3, 0, 0, 0, 0, 1};
            case "cracked" -> new double[]{0.38, 0, 0, 0, 0, 1};      // bent, the wood split where it will break
            case "hanging" -> new double[]{0.50, 0, 0, 0, 0, 1};      // snapped: the top hangs over on its hinge
            case "lowered" -> new double[]{0.60, 0, 0, 0, 0, 1};      // ... nearly down
            case "snapped" -> new double[]{0.85, 0, 0, 0, 0, 1};      // down
            case "snappedsnow" -> new double[]{0.85, 0.8, 0, 0, 0, 1};
            case "snappedwet" -> new double[]{0.85, 0, 1, 0, 0, 1};
            case "snappedback" -> new double[]{0.85, 0, 0, 0, 0.36, 0.9};
            case "back" -> new double[]{0, 0, 0, 0, 0.36, 0.9};
            default -> throw new IllegalArgumentException("unknown state " + name + " (dry, wind, windleft, wet, snow, lightsnow, bent, cracked, hanging, lowered, snapped, snappedsnow, snappedwet, snappedback, back)");
        };
    }

    private static void cell(Graphics2D g, Lit lit, String variant, String stateName, double zoom, long seed, double x, double y, double cw, double ch, String label) {
        DebugLineup.Entry e = entry(variant);
        double[] s = state(stateName);
        java.awt.Shape clip = g.getClip();
        g.clip(new Rectangle2D.Double(x, y, cw - 2, ch - 2));
        DebugScreen.sky(g, x, y, cw, ch, lit);
        double ground = y + ch - 14;
        Color soil = Lit.mix(lit.lit(new Color(104, 146, 84)), lit.lit(new Color(244, 248, 255)), s[1] * 0.9);
        g.setColor(Lit.mix(soil, Lit.mul(soil, 0.75), s[2] * 0.6));
        g.fill(new Rectangle2D.Double(x, ground, cw, 14));
        Model.Obj o = DebugLineup.obj(e, java.util.Arrays.asList(BuildingPainter.TREE_VARIANTS).indexOf(variant), seed, s[0], s[1], s[2], 0);
        AffineTransform old = g.getTransform();
        // (a snapped tree falls to one side: its stump stands near the edge of the cell it falls away from)
        double side = Scene.hash01(o.seed * 77 + 5) < 0.5 ? -1 : 1;
        double off = TreePainter.broken(variant, s[0]) ? -side * Math.max(0, cw / 2 - (e.w * 0.55 + 12) * zoom * s[5]) : 0;
        g.translate(x + cw / 2 + off, ground);
        g.scale(zoom * s[5], zoom * s[5]);
        BuildingPainter.paintTree(g, o, lit, s[3], s[4]);
        g.setTransform(old);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.setColor(new Color(0, 0, 0, 150));
        g.drawString(label, (float) x + 5, (float) y + 14);
        g.setColor(Color.WHITE);
        g.drawString(label, (float) x + 4, (float) y + 13);
        g.setClip(clip);
    }

    private static double cellW(String variant, double zoom) { DebugLineup.Entry e = entry(variant); return Math.ceil((e.w * 1.9 + e.h * 0.62 + 10) * zoom + 8); }

    /** A cell wide enough for the tree lying on the ground beside its stump. */
    private static double cellWBroken(String variant, double zoom) {
        DebugLineup.Entry e = entry(variant);
        return TreePainter.snaps(variant) ? Math.max(cellW(variant, zoom), Math.ceil((e.w * 1.25 + e.h * 1.08 + 26) * zoom + 8)) : cellW(variant, zoom);
    }

    private static double cellH(String variant, double zoom) { DebugLineup.Entry e = entry(variant); return Math.ceil((e.h * 1.24 + 8) * zoom + 34); }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("sun.java2d.renderer.clip") == null) System.setProperty("sun.java2d.renderer.clip", "false");   // (as Main starts)
        System.setProperty("java.awt.headless", "true");
        String out = null, lodVariant = null, light = "day";
        String[] rows = {"dry", "wind", "wet", "snow", "bent", "snapped", "back"}, variants = BuildingPainter.TREE_VARIANTS;
        double zoom = 2;
        int cols = 0;
        long seed = 1234;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--sheet" -> out = args[++i];
                case "--lod" -> { lodVariant = args[++i]; out = args[++i]; }
                case "--zoom" -> zoom = Double.parseDouble(args[++i]);
                case "--light" -> light = args[++i];
                case "--rows" -> rows = args[++i].split(",");
                case "--variants" -> variants = args[++i].split(",");
                case "--cols" -> cols = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--check" -> { System.exit(run()); return; }
                default -> { System.err.println("[tree-test] unknown argument " + args[i]); System.exit(2); return; }
            }
        }
        if (out == null) { System.err.println("usage: precip.TreeTest --sheet out.png [--zoom Z] [--light day|dusk|night] [--rows ...] [--variants ...] [--cols N] | --lod VARIANT out.png | --check"); System.exit(2); return; }
        Lit lit = new Lit(DebugScreen.LIGHT_TOD[Math.max(0, java.util.Arrays.asList("day", "dusk", "night").indexOf(light))], 0);
        // the cells, left to right, top to bottom: {variant, state, zoom}
        List<Object[]> cells = new ArrayList<>();
        if (lodVariant != null) {
            for (double z : new double[]{0.5, 1, 2, 4}) cells.add(new Object[]{lodVariant, rows.length == 7 ? "dry" : rows[0], z});
            cols = 4;
        } else {
            for (String r : rows) for (String v : variants) cells.add(new Object[]{v, r, zoom});
            if (cols <= 0) cols = variants.length;
        }
        int n = cells.size(), nRows = (n + cols - 1) / cols;
        double[] colW = new double[cols], rowH = new double[nRows];
        for (int k = 0; k < n; k++) {
            Object[] c = cells.get(k);
            boolean lies = TreePainter.broken((String) c[0], state((String) c[1])[0]);
            colW[k % cols] = Math.max(colW[k % cols], lies ? cellWBroken((String) c[0], (Double) c[2]) : cellW((String) c[0], (Double) c[2]));
            rowH[k / cols] = Math.max(rowH[k / cols], cellH((String) c[0], (Double) c[2]));
        }
        double w = 0, h = 0;
        for (double c : colW) w += c;
        for (double r : rowH) h += r;
        BufferedImage img = new BufferedImage((int) w, (int) h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        DebugScreen.hints(g);
        g.setColor(DebugScreen.BG);
        g.fillRect(0, 0, (int) w, (int) h);
        double y = 0;
        for (int r = 0; r < nRows; r++) {
            double x = 0;
            for (int c = 0; c < cols && r * cols + c < n; c++) {
                Object[] cl = cells.get(r * cols + c);
                double z = (Double) cl[2];
                cell(g, lit, (String) cl[0], (String) cl[1], z, seed, x, y, colW[c], rowH[r],
                        cl[0] + "  " + cl[1] + String.format(Locale.ROOT, "  x%.1f  %s  (level %d)", z, light, TreePainter.lod((String) cl[0], z * state((String) cl[1])[5])));
                x += colW[c];
            }
            y += rowH[r];
        }
        g.dispose();
        File f = new File(out).getAbsoluteFile();
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        javax.imageio.ImageIO.write(img, "png", f);
        System.out.println("[tree-test] sheet written: " + f + " (" + img.getWidth() + "x" + img.getHeight() + ", " + n + " trees)");
    }
}
