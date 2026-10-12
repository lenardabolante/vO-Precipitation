package precip;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase O (OH1): nothing floats. The census of floating pieces in the damage pictures, as a test.
 * <p>
 * Every {@link DebugLineup} entry that is not a tree (the nine house variants, the nine other kinds and the lamp) is
 * painted by {@link BuildingPainter#paint} onto a transparent canvas at every step {@code k = 0..24} of the damage
 * ladder ({@code damage = k / 24}), for seeds 1-6, at scale 1 and 2, and the picture is searched for pieces that hang
 * in the air:
 * <ul>
 *   <li><b>attached</b> = reachable from the foundation band (the rows from {@code y - 2} down) through 8-connected
 *       pixels of alpha {@value #A_VIS} or more;</li>
 *   <li><b>structural</b> floating piece = an 8-connected group of {@value #STRUCT_MIN} or more pixels of alpha
 *       {@value #A_SOLID} or more, none of them attached;</li>
 *   <li><b>visible</b> floating piece = an 8-connected group of pixels of alpha {@value #A_VIS} or more, not attached,
 *       with {@value #VIS_MIN} or more of its pixels more than {@value #VIS_ABOVE} world px above the ground;</li>
 *   <li><b>census</b> (the figure the plan was made from: 385 of 2 736 on the painter of 2026-10-11) = a group of
 *       {@value #CENSUS_MIN} or more pixels of alpha {@value #A_SOLID} or more with no path of such pixels to the band.</li>
 * </ul>
 * The test passes when no picture has a structural or a visible floating piece, in the main table and in the other
 * resting states it runs (scale 4, a scale and position that are not whole numbers, the back row, night, wet in wind,
 * snow, the swept slab). It is run by its own {@code main} ({@code java -cp viewer/out_houses precip.FloatTest}) until
 * it is added to {@code --selftest-ui}; {@link #run} is the entry for that.
 */
final class FloatTest {
    private FloatTest() {}

    static final int A_VIS = 40, A_SOLID = 128, STRUCT_MIN = 6, VIS_MIN = 10, CENSUS_MIN = 40;
    static final double VIS_ABOVE = 4;
    /** The heap at the foot of a building lies within this many world px of the ground: its pieces rest on one another, not on walls (not looked at for overhangs). */
    static final double HEAP_ZONE = 16;
    /** A part may stick out sideways this far (world px) from what carries it (the painter allows 10; an eave, an aerial). */
    static final double OVER_REACH = 12;
    static final int SEEDS = 6, LEVELS = 24;

    /** What a search of one picture found. */
    static final class Scan {
        int structural, visible, census, largest, largestCensus;
        /** x0, y0, x1, y1 (device px of the picture) of every structural or visible floating piece. */
        final List<int[]> boxes = new ArrayList<>();

        boolean clean() { return structural == 0 && visible == 0; }
    }

    /**
     * Search a picture on a transparent canvas for floating pieces.
     * @param bandRow the first device row of the foundation band; aboveRow the device row {@link #VIS_ABOVE} world px above the ground
     */
    static Scan scan(BufferedImage img, int bandRow, int aboveRow) { return scan(img, bandRow, aboveRow, CENSUS_MIN); }

    /** @param censusMin the smallest group the census counts, in device px ({@value #CENSUS_MIN} world px at the picture's scale) */
    static Scan scan(BufferedImage img, int bandRow, int aboveRow, int censusMin) {
        final int w = img.getWidth(), h = img.getHeight(), n = w * h;
        final int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        final byte[] a = new byte[n];       // 0 nothing, 1 visible, 2 solid
        for (int i = 0; i < n; i++) { int al = px[i] >>> 24; a[i] = (byte) (al >= A_SOLID ? 2 : al >= A_VIS ? 1 : 0); }
        final int[] stack = new int[n];
        final byte[] mark = new byte[n];    // 1 attached, 2 visited (floating, visible pass), 4 visited (solid pass), 8 census attached
        // attached: flood from the band through visible pixels
        int sp = 0;
        for (int y = Math.max(0, bandRow); y < h; y++) for (int x = 0; x < w; x++) { int i = y * w + x; if (a[i] != 0) { mark[i] = 1; stack[sp++] = i; } }
        sp = flood(a, mark, stack, sp, w, h, 1, (byte) 1, null);
        Scan s = new Scan();
        int[] box = new int[6];
        for (int i = 0; i < n; i++) {
            if (a[i] == 0 || (mark[i] & 3) != 0) continue;
            // a floating group of visible pixels
            mark[i] |= 2; stack[0] = i;
            box[0] = w; box[1] = h; box[2] = -1; box[3] = -1; box[4] = 0; box[5] = aboveRow;
            int size = flood(a, mark, stack, 1, w, h, 1, (byte) 2, box);
            if (box[4] >= VIS_MIN) { s.visible++; s.largest = Math.max(s.largest, size); s.boxes.add(new int[]{box[0], box[1], box[2], box[3]}); }
        }
        for (int i = 0; i < n; i++) {
            if (a[i] != 2 || (mark[i] & 1) != 0 || (mark[i] & 4) != 0) continue;
            mark[i] |= 4; stack[0] = i;
            box[0] = w; box[1] = h; box[2] = -1; box[3] = -1; box[4] = 0; box[5] = h;
            int size = floodSolid(a, mark, stack, w, h, box);
            if (size >= STRUCT_MIN) { s.structural++; s.largest = Math.max(s.largest, size); s.boxes.add(new int[]{box[0], box[1], box[2], box[3]}); }
        }
        // the census of the plan: solid groups with no solid path to the band
        java.util.Arrays.fill(mark, (byte) 0);
        sp = 0;
        for (int y = Math.max(0, bandRow); y < h; y++) for (int x = 0; x < w; x++) { int i = y * w + x; if (a[i] == 2) { mark[i] = 1; stack[sp++] = i; } }
        flood(a, mark, stack, sp, w, h, 2, (byte) 1, null);
        for (int i = 0; i < n; i++) {
            if (a[i] != 2 || mark[i] != 0) continue;
            mark[i] = 2; stack[0] = i;
            int size = flood(a, mark, stack, 1, w, h, 2, (byte) 2, null);
            if (size >= censusMin) { s.census++; s.largestCensus = Math.max(s.largestCensus, size); }
        }
        return s;
    }

    /**
     * Pieces that are joined but stand on nothing (the painter's own rule, a little looser): a pixel of alpha 64 or more
     * stands when one of the three under it does; along a row everything between two standing pixels is carried (a span),
     * and so is what sticks out up to {@code reach} px beyond one (an overhang) and what lies straight over such an
     * overhang; what hangs down from something carried by at most one and a half times {@code reach} is carried too (the
     * tip of a barn's eave). Returns the number of groups of {@code minPx} or more solid pixels that are not carried.
     */
    static int overhangs(BufferedImage img, int bandRow, int reach, int minPx) {
        final int w = img.getWidth(), h = img.getHeight();
        final int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        final boolean[] on = new boolean[w * h];
        final byte[] c = new byte[w * h];           // 2 = stands, 3 = carried as an overhang or hanging, 0 = not carried
        for (int i = 0; i < w * h; i++) on[i] = (px[i] >>> 24) >= 64;
        final int band = Math.max(0, Math.min(h - 1, bandRow));
        for (int y = band; y < h; y++) for (int x = 0; x < w; x++) if (on[y * w + x]) c[y * w + x] = 2;
        for (int y = band - 1; y >= 0; y--) for (int x = 0; x < w; x++) {
            if (!on[y * w + x]) continue;
            int a = x, prev = -1;
            for (; x < w && on[y * w + x]; x++) {
                int b = (y + 1) * w + x, i = y * w + x;
                if (!(c[b] == 2 || (x > 0 && c[b - 1] == 2) || (x < w - 1 && c[b + 1] == 2))) { if (c[b] == 3) c[i] = 3; continue; }
                if (prev >= 0) for (int k = prev + 1; k < x; k++) c[y * w + k] = 2;
                else for (int k = Math.max(a, x - reach); k < x; k++) c[y * w + k] = 3;
                c[i] = 2;
                prev = x;
            }
            if (prev >= 0) for (int k = prev + 1; k <= Math.min(x - 1, prev + reach); k++) c[y * w + k] = 3;
        }
        final boolean[] up = new boolean[w * h];
        for (int i = 0; i < w * h; i++) up[i] = c[i] != 0;
        // (what hangs down from something carried)
        final int[] depth = new int[w * h];
        for (int y = 1; y < band; y++) for (int x = 0; x < w; x++) {
            int i = y * w + x;
            if (!on[i] || up[i]) continue;
            int best = Integer.MAX_VALUE;
            for (int k = -1; k <= 1; k++) {
                if (x + k < 0 || x + k >= w) continue;
                int j = i - w + k;
                if (up[j]) best = Math.min(best, depth[j]);
            }
            if (best < reach * 3 / 2) { up[i] = true; depth[i] = best + 1; }
        }
        int groups = 0;
        final int[] stack = new int[w * h];
        final boolean[] seen = new boolean[w * h];
        for (int i = 0; i < w * h; i++) {
            if (!on[i] || up[i] || seen[i] || (px[i] >>> 24) < A_SOLID) continue;
            int sp = 0, size = 0;
            seen[i] = true; stack[sp++] = i;
            while (sp > 0) {
                int cc = stack[--sp], cx = cc % w, cy = cc / w;
                size++;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int xx = cx + dx, yy = cy + dy;
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                    int j = yy * w + xx;
                    if (!on[j] || up[j] || seen[j] || (px[j] >>> 24) < A_SOLID) continue;
                    seen[j] = true; stack[sp++] = j;
                }
            }
            if (size >= minPx) groups++;
        }
        return groups;
    }

    /** Flood through pixels of class {@code min} or more that carry none of the bits 1|2; returns the number of pixels taken from the stack. */
    private static int flood(byte[] a, byte[] mark, int[] stack, int sp, int w, int h, int min, byte bit, int[] box) {
        int count = 0;
        while (sp > 0) {
            int i = stack[--sp], x = i % w, y = i / w;
            count++;
            if (box != null) {
                if (x < box[0]) box[0] = x; if (y < box[1]) box[1] = y; if (x > box[2]) box[2] = x; if (y > box[3]) box[3] = y;
                if (y < box[5]) box[4]++;
            }
            for (int dy = -1; dy <= 1; dy++) {
                int yy = y + dy;
                if (yy < 0 || yy >= h) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= w) continue;
                    int j = yy * w + xx;
                    if (a[j] < min || (mark[j] & 3) != 0) continue;
                    mark[j] |= bit; stack[sp++] = j;
                }
            }
        }
        return count;
    }

    /** Flood through solid pixels that are not attached (bit 1) and not yet in a solid group (bit 4). */
    private static int floodSolid(byte[] a, byte[] mark, int[] stack, int w, int h, int[] box) {
        int count = 0, sp = 1;
        while (sp > 0) {
            int i = stack[--sp], x = i % w, y = i / w;
            count++;
            if (x < box[0]) box[0] = x; if (y < box[1]) box[1] = y; if (x > box[2]) box[2] = x; if (y > box[3]) box[3] = y;
            for (int dy = -1; dy <= 1; dy++) {
                int yy = y + dy;
                if (yy < 0 || yy >= h) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= w) continue;
                    int j = yy * w + xx;
                    if (a[j] != 2 || (mark[j] & 5) != 0) continue;
                    mark[j] |= 4; stack[sp++] = j;
                }
            }
        }
        return count;
    }

    /** One painted case: the picture and where its ground is. */
    static final class Shot {
        BufferedImage img;
        int bandRow, aboveRow;
        double cx, gy, scale;
    }

    /** How a case is painted besides (entry, seed, k, scale). */
    static final class Mode {
        final String name;
        double tod = 0.45, wet, snow, fracX, fracY;
        int wind;
        boolean back;

        Mode(String name) { this.name = name; }
    }

    static final Mode DAY = new Mode("day, dry");

    /** Paint one entry at step {@code k} of the ladder onto a transparent canvas, exactly as a ground tile paints it. */
    static Shot shot(DebugLineup.Entry e, int index, long seed, int k, double scale, Mode m) {
        Model.Obj o = DebugLineup.obj(e, index, seed, k / (double) LEVELS, m.snow, m.wet, 0);
        final double side = e.w / 2 + 70, up = e.h * 1.70 + 60, down = 26;
        Shot s = new Shot();
        s.scale = scale;
        int iw = (int) Math.ceil(2 * side * scale) + 2, ih = (int) Math.ceil((up + down) * scale) + 2;
        s.img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
        s.cx = Math.round(side * scale) + m.fracX; s.gy = Math.round(up * scale) + m.fracY;
        Graphics2D g = s.img.createGraphics();
        hints(g);
        g.translate(s.cx, s.gy);
        g.scale(scale, scale);
        Lit lit = new Lit(m.tod, 0);
        if (m.back) {
            AffineTransform old = g.getTransform();
            g.translate(o.x, o.y); g.scale(0.9, 0.9); g.translate(-o.x, -o.y);
            BuildingPainter.paint(g, o, lit, 0.36, m.wind);
            g.setTransform(old);
        } else BuildingPainter.paint(g, o, lit, 0, m.wind);
        g.dispose();
        final double ps = m.back ? scale * 0.9 : scale;
        s.bandRow = (int) Math.floor(s.gy - 2 * ps);
        s.aboveRow = (int) Math.floor(s.gy - VIS_ABOVE * ps);
        return s;
    }

    /** The hints a ground tile is painted with ({@code TileLayer.paintTile}). */
    static void hints(Graphics2D g) {
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE);
    }

    static List<DebugLineup.Entry> buildings() {
        List<DebugLineup.Entry> l = new ArrayList<>();
        for (DebugLineup.Entry e : DebugLineup.entries()) if (!e.tree()) l.add(e);
        return l;
    }

    /** The index an entry has in the line-up (its object's seed depends on it). */
    private static int indexOf(DebugLineup.Entry e) {
        List<DebugLineup.Entry> all = DebugLineup.entries();
        for (int i = 0; i < all.size(); i++) if (all.get(i).label.equals(e.label)) return i;
        return 0;
    }

    /** Totals of one sweep. */
    static final class Sweep {
        int cases, intact, structural, visible, census, bad, intactBad, largest, largestCensus, overhang;
        String firstOver = "";
        String worst = "", firstBad = "";
        final int[] byLevel = new int[LEVELS + 1], censusByLevel = new int[LEVELS + 1];
        final java.util.LinkedHashMap<String, int[]> byKind = new java.util.LinkedHashMap<>();   // label -> {cases, bad, census}
    }

    static Sweep sweep(double scale, Mode m, int seeds, int kStep) {
        Sweep t = new Sweep();
        List<DebugLineup.Entry> es = buildings();
        for (DebugLineup.Entry e : es) t.byKind.put(e.label, new int[3]);
        final int censusMin = (int) Math.round(CENSUS_MIN * scale * scale);
        // (the painter is pure and is called from the tile workers of the game in the same way: one entry per task)
        es.parallelStream().forEach(e -> {
            int idx = indexOf(e);
            int[] row = t.byKind.get(e.label);
            for (int seed = 1; seed <= seeds; seed++) for (int k = 0; k <= LEVELS; k += kStep) {
                Shot s = shot(e, idx, seed, k, scale, m);
                Scan c = scan(s.img, s.bandRow, s.aboveRow, censusMin);
                boolean lamp = e.kind.equals("streetlamp") && m.tod != 0.45;      // (a lit lamp has a glow in the air: exempt at night)
                boolean bad = !c.clean() && !lamp;
                // (a slab sticking out over nothing: looked for in the damaged pictures the painter copies one to one)
                final double ps = m.back ? scale * 0.9 : scale;
                int over = k > 0 && scale <= 4 && !e.kind.equals("streetlamp") ? overhangs(s.img, s.bandRow - (int) Math.round(HEAP_ZONE * ps), (int) Math.round(OVER_REACH * ps), (int) Math.round(CENSUS_MIN * ps * ps)) : 0;
                synchronized (t) {
                    if (over > 0) { t.overhang++; String id = e.label + " seed " + seed + " k " + k; if (t.firstOver.isEmpty() || id.compareTo(t.firstOver) < 0) t.firstOver = id; }
                    if (k == 0) { t.intact++; if (bad) t.intactBad++; if (!bad) continue; }
                    else t.cases++;
                    if (k > 0) row[0]++;
                    if (bad) {
                        t.bad++; t.byLevel[k]++; row[1]++;
                        t.structural += c.structural; t.visible += c.visible;
                        String id = e.label + " seed " + seed + " k " + k;
                        if (t.firstBad.isEmpty() || id.compareTo(t.firstBad) < 0) t.firstBad = id;
                        if (c.largest > t.largest || (c.largest == t.largest && id.compareTo(t.worst) < 0)) { t.largest = c.largest; t.worst = id; }
                    }
                    if (c.census > 0 && k > 0) { t.census++; t.censusByLevel[k]++; row[2]++; t.largestCensus = Math.max(t.largestCensus, c.largestCensus); }
                }
            }
        });
        return t;
    }

    private static int fails;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) fails++;
    }

    private static String levels(int[] by, int total) {
        int[][] bands = {{1, 6}, {7, 12}, {13, 18}, {19, 22}, {23, 24}};
        StringBuilder sb = new StringBuilder();
        for (int[] b : bands) {
            int n = 0;
            for (int k = b[0]; k <= b[1]; k++) n += by[k];
            int cases = total * (b[1] - b[0] + 1) / LEVELS;
            sb.append(String.format(Locale.ROOT, "  k %d-%d: %d of %d (%.0f %%)", b[0], b[1], n, cases, cases == 0 ? 0 : 100.0 * n / cases));
        }
        return sb.toString();
    }

    /** The share of seeded houses that are a swept slab at the top of the ladder, and whether any of them has a piece in the air. */
    private static void swept() {
        DebugLineup.Entry cottage = null;
        for (DebugLineup.Entry e : buildings()) if (e.label.equals("house:cottage")) cottage = e;
        int idx = indexOf(cottage), slabs = 0, bad = 0, n = 200;
        for (int seed = 1; seed <= n; seed++) {
            Model.Obj o = DebugLineup.obj(cottage, idx, seed, 1, 0, 0, 0);
            if (BuildingPainter.swept(o)) slabs++;
            Shot s = shot(cottage, idx, seed, LEVELS, 1, DAY);
            if (!scan(s.img, s.bandRow, s.aboveRow).clean()) bad++;
        }
        check(slabs >= 100 && slabs <= 120, "the swept slab: " + slabs + " of " + n + " seeded cottages at k 24 are a bare slab (50-60 % wanted)");
        check(bad == 0, "none of those " + n + " has a piece in the air (" + bad + ")");
    }

    /**
     * OH7: the Buildings tab of the Debug mode. It paints at the smallest and at a large window size without an error,
     * its floating-pieces overlay boxes nothing at any step of the ladder (front row and back row), the hit scene plays,
     * and leaving the tab leaves no picture and no world behind.
     */
    private static void debugTab() {
        java.io.File tmp;
        try { tmp = java.nio.file.Files.createTempDirectory("precip-floattest").toFile(); }
        catch (java.io.IOException ex) { check(false, "a temp folder for the Debug tab: " + ex.getMessage()); return; }
        try {
            Settings off = new Settings(new java.io.File(tmp, "config"));
            off.persist = false;
            AppShell shell = new AppShell(Main.projectRoot(), off, new SaveStore(new java.io.File(tmp, "saves")));
            DebugScreen ds = DebugScreen.open(shell, true);
            ds.setTab(DebugScreen.BUILDINGS);
            String err = null;
            int boxed = 0, pictures = 0;
            for (int[] size : new int[][]{{1040, 620, 1}, {1680, 760, 2}}) for (int back = 0; back < 2; back++) {
                ds.buildings.option("back", back == 1 ? "on" : "off");
                for (int k = 0; k <= LEVELS; k += size[2] == 2 && back == 1 ? 4 : 1) {
                    ds.buildings.option("k", String.valueOf(k));
                    ds.picture(size[0], size[1], size[2]);
                    pictures++;
                    boxed += ds.buildings.floating;
                    if (ds.lastError != null && err == null) err = ds.lastError + " (k " + k + ", " + size[0] + " x " + size[1] + ")";
                }
            }
            check(err == null, "the Buildings tab paints at 1040 x 620 and at 1680 x 760 (at 2 device px each) at every step of the ladder, front and back row, without an error (" + pictures + " pictures" + (err != null ? "; " + err : "") + ")");
            check(boxed == 0, "its floating-pieces overlay boxes nothing at any step (" + boxed + " boxes)");
            ds.buildings.option("back", "off");
            ds.buildings.option("people", "off");
            ds.buildings.option("hour", "17.4");
            ds.picture(1680, 760, 1);
            ds.buildings.option("people", "on");
            ds.buildings.option("k", "10");
            ds.buildings.key(new java.awt.event.KeyEvent(new java.awt.Canvas(), java.awt.event.KeyEvent.KEY_PRESSED, 0, 0, java.awt.event.KeyEvent.VK_RIGHT, java.awt.event.KeyEvent.CHAR_UNDEFINED));
            int kAfter = ds.buildings.k();
            ds.buildings.option("hit", "on");
            ds.picture(1040, 620, 1);
            boolean hitDrawn = ds.buildings.hit != null && ds.buildings.hit.frame() != null && ds.lastError == null;
            check(hitDrawn && kAfter == 11, "the hour slider, the People switch and the arrow keys work (the right arrow takes the ladder from k 10 to k " + kAfter + "), and the Hit button plays the wreck's scene"
                    + (ds.lastError != null ? " [" + ds.lastError + "]" : ""));
            ds.setTab(DebugScreen.CARS);
            check(!ds.buildings.cached(), "leaving the tab leaves no picture and no world behind");
            ds.dispose();
        } catch (Throwable t) {
            check(false, "the Buildings tab: " + t);
        } finally {
            java.io.File[] fs = tmp.listFiles();
            if (fs != null) for (java.io.File f : fs) f.delete();
            tmp.delete();
        }
    }

    /** Run the test; 0 = passed. */
    static int run() {
        fails = 0;
        System.out.println("FloatTest (Phase O, OH1): floating pieces in the damage pictures");
        long t0 = System.nanoTime();
        for (double scale : new double[]{1, 2}) {
            Sweep t = sweep(scale, DAY, SEEDS, 1);
            System.out.printf(Locale.ROOT, "  scale %.0f: %d damaged cases (%d kinds x %d seeds x %d levels), %d with a floating piece (%.1f %%); %d structural, %d visible pieces; largest %d px (%s)%n",
                    scale, t.cases, t.byKind.size(), SEEDS, LEVELS, t.bad, 100.0 * t.bad / t.cases, t.structural, t.visible, t.largest, t.worst.isEmpty() ? "-" : t.worst);
            System.out.printf(Locale.ROOT, "           the plan's census (solid groups of %d world px or more): %d of %d (%.1f %%), largest %d px; intact with a piece: %d of %d%n",
                    CENSUS_MIN, t.census, t.cases, 100.0 * t.census / t.cases, t.largestCensus, t.intactBad, t.intact);
            System.out.println("           by level (test):  " + levels(t.byLevel, t.cases));
            System.out.println("           by level (census):" + levels(t.censusByLevel, t.cases));
            StringBuilder sb = new StringBuilder("           by kind (test / census of " + SEEDS * LEVELS + "):");
            for (var en : t.byKind.entrySet()) sb.append(' ').append(en.getKey()).append(' ').append(en.getValue()[1]).append('/').append(en.getValue()[2]);
            System.out.println(sb);
            check(t.bad == 0, "scale " + (int) scale + ": no damaged picture has a floating piece (" + t.bad + " of " + t.cases + (t.bad > 0 ? ", first: " + t.firstBad : "") + ")");
            check(t.intactBad == 0, "scale " + (int) scale + ": no intact picture has one (" + t.intactBad + " of " + t.intact + ")");
            check(t.overhang == 0, "scale " + (int) scale + ": no damaged picture has a slab of " + CENSUS_MIN + " world px or more that is joined but stands on nothing (" + t.overhang + " of " + t.cases + (t.overhang > 0 ? ", first: " + t.firstOver : "") + ")");
        }
        // the other resting states
        Mode back = new Mode("back row (0.9, haze 0.36)"); back.back = true;
        Mode frac = new Mode("scale 3.3 at a fractional position"); frac.fracX = 0.37; frac.fracY = 0.61;
        Mode night = new Mode("night"); night.tod = 0.02;
        Mode wet = new Mode("wet 0.8 in wind level 2"); wet.wet = 0.8; wet.wind = 2;
        Mode snow = new Mode("snow 0.7"); snow.snow = 0.7;
        Object[][] more = {{4.0, DAY, 2, 1}, {3.3, frac, 2, 1}, {0.6, DAY, 3, 1}, {1.0, back, 3, 1}, {2.0, back, 3, 1}, {2.0, night, 3, 1}, {2.0, wet, 2, 1}, {2.0, snow, 2, 1}, {6.0, DAY, 1, 4}};
        for (Object[] r : more) {
            double scale = (Double) r[0];
            Mode m = (Mode) r[1];
            Sweep t = sweep(scale, m, (Integer) r[2], (Integer) r[3]);
            check(t.bad == 0 && t.intactBad == 0 && t.overhang == 0, String.format(Locale.ROOT, "%s, scale %.1f: %d of %d damaged and %d of %d intact pictures have a floating piece, %d a slab standing on nothing%s%s",
                    m.name, scale, t.bad, t.cases, t.intactBad, t.intact, t.overhang, t.bad > 0 ? " (largest " + t.largest + " px, " + t.worst + ")" : "", t.overhang > 0 ? " (first " + t.firstOver + ")" : ""));
        }
        swept();
        debugTab();
        System.out.printf(Locale.ROOT, "FloatTest: %s (%d failed, %.1f s)%n", fails == 0 ? "PASSED" : "FAILED", fails, (System.nanoTime() - t0) / 1e9);
        return fails == 0 ? 0 : 1;
    }

    /** What one search pass costs over an image the size the painter gives a building its own image ({@code --cost}). */
    private static void cost() {
        System.out.println("cost of one damaged paint and of one search pass over the painter's own image (ms, JIT warm, median of 15):");
        for (String label : new String[]{"house:cottage", "house:manor", "barn", "office", "skyscraper"}) {
            DebugLineup.Entry e = null;
            for (DebugLineup.Entry x : buildings()) if (x.label.equals(label)) e = x;
            int idx = indexOf(e);
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "  %-14s", label));
            for (double scale : new double[]{1, 2, 4}) {
                // the painter's own image: the footprint + 18 px a side, 1.62 heights + 36 px up, 11 px down
                int iw = (int) Math.ceil((e.w + 36) * scale) + 2, ih = (int) Math.ceil((e.h * 1.62 + 47) * scale) + 2;
                BufferedImage own = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = own.createGraphics();
                hints(g);
                g.scale(scale, scale);
                g.translate(e.w / 2 + 18, e.h * 1.62 + 36);
                Model.Obj o = DebugLineup.obj(e, idx, 1, 16 / 24.0, 0, 0, 0);
                BuildingPainter.paint(g, o, new Lit(0.45, 0), 0, 0);
                double[] paint = new double[15], pass = new double[15];
                for (int warm = 0; warm < 40; warm++) { BuildingPainter.paint(g, o, new Lit(0.45, 0), 0, 0); scan(own, ih - (int) (11 * scale), ih); }
                for (int i = 0; i < 15; i++) {
                    long a = System.nanoTime();
                    BuildingPainter.paint(g, o, new Lit(0.45, 0), 0, 0);
                    long b = System.nanoTime();
                    attachedOnly(own, ih - (int) (13 * scale));
                    long c = System.nanoTime();
                    paint[i] = (b - a) / 1e6; pass[i] = (c - b) / 1e6;
                }
                g.dispose();
                java.util.Arrays.sort(paint); java.util.Arrays.sort(pass);
                sb.append(String.format(Locale.ROOT, "  x%.0f %dx%d: paint %.2f, pass %.2f", scale, iw, ih, paint[7], pass[7]));
            }
            System.out.println(sb);
        }
    }

    /** The one pass the painter needs: which pixels are joined to the band (direct access to the raster, as the painter has). */
    private static int attachedOnly(BufferedImage img, int bandRow) {
        final int w = img.getWidth(), h = img.getHeight();
        final int[] px = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
        final byte[] mark = new byte[w * h];
        final int[] stack = new int[w * h];
        int sp = 0, count = 0;
        for (int i = Math.max(0, bandRow) * w; i < w * h; i++) if ((px[i] >>> 24) >= A_VIS) { mark[i] = 1; stack[sp++] = i; }
        while (sp > 0) {
            int i = stack[--sp], x = i % w, y = i / w;
            count++;
            for (int dy = -1; dy <= 1; dy++) {
                int yy = y + dy;
                if (yy < 0 || yy >= h) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= w) continue;
                    int j = yy * w + xx;
                    if (mark[j] != 0 || (px[j] >>> 24) < A_VIS) continue;
                    mark[j] = 1; stack[sp++] = j;
                }
            }
        }
        return count;
    }

    /** Write the ladder of one entry as a sheet (k 0, 4, 8 .. 24 left to right), floating pieces boxed in red ({@code --sheet label seed scale file [back]}). */
    static void sheet(String label, long seed, double scale, String file, boolean backRow, double tod) throws java.io.IOException {
        DebugLineup.Entry e = null;
        for (DebugLineup.Entry x : buildings()) if (x.label.equals(label)) e = x;
        if (e == null) throw new IllegalArgumentException("no entry " + label);
        int idx = indexOf(e);
        int[] ks = {0, 4, 8, 12, 16, 20, 24};
        Mode m = new Mode("sheet"); m.back = backRow; m.tod = tod;
        Shot first = shot(e, idx, seed, 0, scale, m);
        int cw = first.img.getWidth(), ch = first.img.getHeight();
        BufferedImage out = new BufferedImage(cw * ks.length, ch, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        Lit lit = new Lit(tod, 0);
        for (int i = 0; i < ks.length; i++) {
            Shot s = shot(e, idx, seed, ks[i], scale, m);
            g.setPaint(new java.awt.GradientPaint(0, 0, lit.skyTop, 0, (float) s.gy, lit.skyBot));
            g.fillRect(i * cw, 0, cw, ch);
            g.setColor(lit.lit(new java.awt.Color(104, 146, 84)));
            g.fillRect(i * cw, (int) s.gy, cw, ch - (int) s.gy);
            g.drawImage(s.img, i * cw, 0, null);
            Scan c = scan(s.img, s.bandRow, s.aboveRow);
            g.setColor(java.awt.Color.RED);
            for (int[] b : c.boxes) g.drawRect(i * cw + b[0] - 2, b[1] - 2, b[2] - b[0] + 4, b[3] - b[1] + 4);
            g.setColor(java.awt.Color.WHITE);
            g.drawString("k " + ks[i] + (c.clean() ? "" : "  FLOATING " + (c.structural + c.visible)), i * cw + 6, 14);
        }
        g.dispose();
        java.io.File f = new java.io.File(file);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        javax.imageio.ImageIO.write(out, "png", f);
        System.out.println("written " + f);
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        if (args.length > 0 && args[0].equals("--cost")) { cost(); return; }
        if (args.length >= 5 && args[0].equals("--sheet")) {
            sheet(args[1], Long.parseLong(args[2]), Double.parseDouble(args[3]), args[4], args.length > 5 && args[5].equals("back"), args.length > 6 ? Double.parseDouble(args[6]) : 0.45);
            return;
        }
        if (args.length > 1 && args[0].equals("--list")) {
            // every failing case at one scale, intact included
            double scale = Double.parseDouble(args[1]);
            for (DebugLineup.Entry e : buildings()) for (int seed = 1; seed <= SEEDS; seed++) for (int k = 0; k <= LEVELS; k++) {
                Shot s = shot(e, indexOf(e), seed, k, scale, DAY);
                Scan c = scan(s.img, s.bandRow, s.aboveRow);
                if (k > 0 && !e.kind.equals("streetlamp") && overhangs(s.img, s.bandRow - (int) Math.round(HEAP_ZONE * scale), (int) Math.round(OVER_REACH * scale), (int) Math.round(CENSUS_MIN * scale * scale)) > 0)
                    System.out.println(e.label + " seed " + seed + " k " + k + ": a slab standing on nothing");
                if (!c.clean()) {
                    StringBuilder sb = new StringBuilder();
                    for (int[] bx : c.boxes) sb.append(String.format(Locale.ROOT, " [%.1f..%.1f, %.1f..%.1f]", (bx[0] - s.cx) / scale, (bx[2] - s.cx) / scale, (bx[1] - s.gy) / scale, (bx[3] - s.gy) / scale));
                    System.out.println(e.label + " seed " + seed + " k " + k + ": " + c.structural + " structural, " + c.visible + " visible, largest " + c.largest + " px; world boxes (x, y from the ground)" + sb);
                }
            }
            return;
        }
        if (args.length > 0 && args[0].equals("--census")) {
            // every case the plan's census still counts, at scale 1 and 2
            for (double scale : new double[]{1, 2}) for (DebugLineup.Entry e : buildings()) for (int seed = 1; seed <= SEEDS; seed++) for (int k = 1; k <= LEVELS; k++) {
                Shot s = shot(e, indexOf(e), seed, k, scale, DAY);
                Scan c = scan(s.img, s.bandRow, s.aboveRow, (int) Math.round(CENSUS_MIN * scale * scale));
                if (c.census > 0) System.out.println("x" + (int) scale + " " + e.label + " seed " + seed + " k " + k + ": " + c.census + " group(s), largest " + c.largestCensus + " px");
            }
            return;
        }
        System.exit(run());
    }
}
