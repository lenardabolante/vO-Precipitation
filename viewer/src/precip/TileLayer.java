package precip;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A cache of pre-rendered static scenery (terrain, buildings, ridges) as square tiles in layer
 * space, so panning re-uses pixels and only newly exposed tiles are painted.
 * <ul>
 *   <li>At rest tiles are rendered at the exact device scale and blitted 1:1 (crisp).</li>
 *   <li>While the camera zooms, tiles of the nearest quarter-octave scale bucket are wanted; until a
 *       wanted tile has been painted (on a worker thread) the area is covered with tiles of a scale
 *       that is already there, drawn scaled. A zoom therefore never waits for painting unless it
 *       reveals ground no cached tile covers.</li>
 *   <li>When the content changes (lighting drift, snow / wet cover) stale tiles keep being shown
 *       while the worker repaints them.</li>
 * </ul>
 * {@link #prepare} runs once per frame on the frame thread; {@link #draw} may then be called
 * concurrently by the band renderers (it only reads the prepared lists).
 */
final class TileLayer {
    /** Paints layer content covering the given rectangle (layer / world units). Must be thread-safe. */
    interface Painter {
        void paint(Graphics2D g, double x0, double y0, double x1, double y1, double scale);
    }

    static final int T = 384, P = 2;

    private static final class Tile {
        final double scale;
        final int ix, iy;
        BufferedImage img;       // null = nothing to draw here
        int[] px;                // the same pixels, for direct compositing
        byte[] rows;             // per row: 0 transparent, 1 mixed, 2 opaque
        boolean opaque;
        boolean painted;         // false while the first paint is still on the worker
        long version;
        long lastUsed;
        boolean building;

        Tile(double scale, int ix, int iy) { this.scale = scale; this.ix = ix; this.iy = iy; }
    }

    private record Key(long sbits, int ix, int iy) {}

    private record Painted(BufferedImage img, int[] px, boolean opaque, byte[] rows) {}

    private record Done(Key key, Painted p, long version) {}

    private static final class Op {
        BufferedImage img;
        int[] px;
        byte[] rows;
        int dx0, dy0, dx1, dy1;
        boolean scaled, opaque;
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tile-painter");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    final String name;
    /** Horizontal parallax factor: 1 = moves with the world, smaller = further away. */
    final double parallax;
    /** Content bounds in layer units; tiles entirely outside are skipped. */
    double minX = -1e12, maxX = 1e12, minY = -1e12, maxY = 1e12;
    /** When false every tile is painted synchronously at the exact scale (snapshots). */
    boolean async = true;
    long builds, buildNanos;

    private final Map<Key, Tile> tiles = new HashMap<>();
    private final ConcurrentLinkedQueue<Done> done = new ConcurrentLinkedQueue<>();
    private final List<Op> under = new ArrayList<>(), ops = new ArrayList<>();
    private int nUnder, nOps;
    private long frameNo;
    private int ltx, lty;
    private long worldTag = Long.MIN_VALUE, curVersion = Long.MIN_VALUE;
    /** Scales that have tiles, most recently wanted first (fallbacks while a wanted tile is being painted). */
    private final List<Double> recent = new ArrayList<>();

    TileLayer(String name, double parallax) {
        this.name = name;
        this.parallax = parallax;
    }

    /** Device x translation of this layer for a view. */
    int layerTx(Camera.View v) {
        if (parallax == 1) return v.wtx;
        return (int) Math.floor(v.devX + v.dev * (v.vx + v.vw / 2) - v.ws * v.cx * parallax + 0.5);
    }

    static double bucket(double s) { return Math.pow(2, Math.round(Math.log(s) / Math.log(2) * 4) / 4.0); }

    /** Drop everything (new world). */
    void clear() {
        tiles.clear();
        done.clear();
        recent.clear();
        nOps = 0;
        nUnder = 0;
    }

    void prepare(Painter p, long version, long worldTag, Camera.View v) { prepare(p, version, worldTag, v, -1e18, 1e18); }

    /**
     * Decide what to draw for this view and (re)paint what is needed.
     * @param version  content version: tiles painted for another version are stale
     * @param worldTag changes when the world itself is replaced (all tiles are dropped)
     * @param dirtyX0  when the version moved on, only tiles overlapping [dirtyX0, dirtyX1] (layer units)
     *                 are considered changed; the others are carried over without repainting
     */
    void prepare(Painter p, long version, long worldTag, Camera.View v, double dirtyX0, double dirtyX1) {
        frameNo++;
        if (worldTag != this.worldTag) { clear(); this.worldTag = worldTag; curVersion = version; }
        if (version != curVersion) {
            for (Tile t : tiles.values()) {
                if (t.version != curVersion) continue;
                double tx0 = t.ix * (double) T / t.scale, tx1 = (t.ix + 1) * (double) T / t.scale;
                if (tx1 < dirtyX0 || tx0 > dirtyX1) t.version = version;
            }
            curVersion = version;
        }
        Done d;
        while ((d = done.poll()) != null) {
            Tile t = tiles.get(d.key);
            if (t == null) continue;
            t.building = false;
            set(t, d.p);
            t.version = d.version;
        }
        nOps = 0;
        nUnder = 0;
        final double s = v.ws;
        ltx = layerTx(v);
        lty = v.wty;
        final boolean exact = v.settled || !async;
        final double sUse = exact ? s : bucket(s);
        if (recent.isEmpty() || recent.get(0) != sUse) {
            recent.remove((Double) sUse);
            recent.add(0, sUse);
            while (recent.size() > 5) recent.remove(recent.size() - 1);
        }
        // visible rectangle in layer units
        final double u0 = (v.dvx0 - ltx) / s, u1 = (v.dvx1 - ltx) / s, w0 = (v.dvy0 - lty) / s, w1 = (v.dvy1 - lty) / s;
        if (u1 < minX || u0 > maxX || w1 < minY || w0 > maxY) return;
        final double a0 = Math.max(u0, minX), a1 = Math.min(u1, maxX), b0 = Math.max(w0, minY), b1 = Math.min(w1, maxY);
        int ix0 = (int) Math.floor(a0 * sUse / T), ix1 = (int) Math.floor(a1 * sUse / T);
        int iy0 = (int) Math.floor(b0 * sUse / T), iy1 = (int) Math.floor(b1 * sUse / T);
        long sbits = Double.doubleToLongBits(sUse);
        for (int iy = iy0; iy <= iy1; iy++) {
            for (int ix = ix0; ix <= ix1; ix++) {
                Key k = new Key(sbits, ix, iy);
                Tile t = tiles.get(k);
                if (t != null && t.painted) { use(p, k, t, version, s, false); continue; }
                if (!async) {
                    if (t == null) { t = new Tile(sUse, ix, iy); tiles.put(k, t); }
                    set(t, paintTile(p, sUse, ix, iy));
                    t.version = version;
                    use(p, k, t, version, s, false);
                    continue;
                }
                // not painted yet: cover the area with tiles of another scale if they are all there
                double x0 = ix * (double) T / sUse, x1 = (ix + 1) * (double) T / sUse, y0 = iy * (double) T / sUse, y1 = (iy + 1) * (double) T / sUse;
                boolean covered = cover(x0, y0, x1, y1, sUse, s, version);
                if (t == null) {
                    t = new Tile(sUse, ix, iy);
                    tiles.put(k, t);
                    if (covered) submit(p, k, t, version);
                }
                t.lastUsed = frameNo;
                if (!covered) {
                    // nothing to show instead: paint it now (a late worker result for it is simply applied on top)
                    set(t, paintTile(p, sUse, ix, iy));
                    t.version = version;
                    t.lastUsed = frameNo - 1;
                    use(p, k, t, version, s, false);
                }
            }
        }
        if ((frameNo & 127) == 0) evict();
    }

    /** Emit fallback ops covering a rectangle from the first other scale whose tiles are all painted. */
    private boolean cover(double x0, double y0, double x1, double y1, double wanted, double s, long version) {
        for (double sc : recent) {
            if (sc == wanted) continue;
            long bits = Double.doubleToLongBits(sc);
            int bx0 = (int) Math.floor(x0 * sc / T), bx1 = (int) Math.floor((x1 * sc - 1e-6) / T);
            int by0 = (int) Math.floor(y0 * sc / T), by1 = (int) Math.floor((y1 * sc - 1e-6) / T);
            if ((long) (bx1 - bx0 + 1) * (by1 - by0 + 1) > 64) continue;
            boolean all = true;
            for (int by = by0; by <= by1 && all; by++) for (int bx = bx0; bx <= bx1; bx++) {
                Tile t = tiles.get(new Key(bits, bx, by));
                if (t == null || !t.painted) { all = false; break; }
            }
            if (!all) continue;
            for (int by = by0; by <= by1; by++) for (int bx = bx0; bx <= bx1; bx++) {
                Key k = new Key(bits, bx, by);
                use(null, k, tiles.get(k), version, s, true);
            }
            return true;
        }
        return false;
    }

    private void use(Painter p, Key k, Tile t, long version, double s, boolean below) {
        boolean first = t.lastUsed != frameNo;
        t.lastUsed = frameNo;
        if (p != null && t.version != version && !t.building) {
            if (!async) { set(t, paintTile(p, t.scale, t.ix, t.iy)); t.version = version; }
            else submit(p, k, t, version);
        }
        if (t.img == null || !first) return;
        Op o;
        if (below) {
            if (nUnder < under.size()) o = under.get(nUnder); else { o = new Op(); under.add(o); }
            nUnder++;
        } else {
            if (nOps < ops.size()) o = ops.get(nOps); else { o = new Op(); ops.add(o); }
            nOps++;
        }
        o.img = t.img;
        o.px = t.px;
        o.rows = t.rows;
        o.opaque = t.opaque;
        if (t.scale == s) {
            o.scaled = false;
            o.dx0 = t.ix * T + ltx; o.dy0 = t.iy * T + lty;
            o.dx1 = o.dx0 + T; o.dy1 = o.dy0 + T;
        } else {
            double k2 = s / t.scale;
            o.scaled = true;
            o.dx0 = (int) Math.floor(t.ix * (double) T * k2 + ltx + 0.5); o.dy0 = (int) Math.floor(t.iy * (double) T * k2 + lty + 0.5);
            o.dx1 = (int) Math.floor((t.ix + 1) * (double) T * k2 + ltx + 0.5); o.dy1 = (int) Math.floor((t.iy + 1) * (double) T * k2 + lty + 0.5);
        }
    }

    private void submit(Painter p, Key k, Tile t, long version) {
        t.building = true;
        final double sc = t.scale;
        final int ix = t.ix, iy = t.iy;
        WORKER.submit(() -> {
            Painted img = null;
            try {
                img = paintTile(p, sc, ix, iy);
            } catch (RuntimeException ex) {
                System.err.println("[viewer] tile paint failed: " + ex);
            } finally {
                done.add(new Done(k, img, version));
            }
        });
    }

    private static void set(Tile t, Painted p) {
        t.img = p == null ? null : p.img;
        t.px = p == null ? null : p.px;
        t.rows = p == null ? null : p.rows;
        t.opaque = p != null && p.opaque;
        t.painted = true;
    }

    private Painted paintTile(Painter p, double sc, int ix, int iy) {
        long t0 = System.nanoTime();
        int size = T + 2 * P;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.translate(P - ix * (double) T, P - iy * (double) T);
        g.scale(sc, sc);
        double pad = (P + 1) / sc;
        double x0 = ix * (double) T / sc - pad, y0 = iy * (double) T / sc - pad, x1 = (ix + 1) * (double) T / sc + pad, y1 = (iy + 1) * (double) T / sc + pad;
        p.paint(g, x0, y0, x1, y1, sc);
        g.dispose();
        synchronized (this) { builds++; buildNanos += System.nanoTime() - t0; }
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        boolean any = false, opaque = true;
        byte[] rows = new byte[T];
        for (int y = 0; y < T; y++) {
            int o = (y + P) * size + P;
            boolean rowAny = false, rowOpaque = true;
            for (int x = 0; x < T; x++) {
                int a = px[o + x] >>> 24;
                if (a != 0) rowAny = true;
                if (a != 255) rowOpaque = false;
                if (rowAny && !rowOpaque) break;
            }
            rows[y] = (byte) (!rowAny ? 0 : rowOpaque ? 2 : 1);
            any |= rowAny;
            opaque &= rowOpaque;
        }
        if (!any) return null;
        // copy into a fresh image so Java2D may treat it as a managed (cacheable) image again
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB_PRE);
        out.getRaster().setDataElements(0, 0, size, size, px);
        return new Painted(out, px, opaque, rows);
    }

    private void evict() {
        int limit = tiles.size() < 320 ? 3000 : 300;
        tiles.values().removeIf(t -> frameNo - t.lastUsed > limit && !t.building);
    }

    /** Blit the prepared tiles. {@code g} must have the identity (device) transform. */
    void draw(Graphics2D g, Direct direct) { draw(g, direct, true); }

    /** @param smooth false = nearest-neighbour scaling (much faster; used while the camera is moving) */
    void draw(Graphics2D g, Direct direct, boolean smooth) {
        Object hint = null;
        for (int pass = 0; pass < 2; pass++) {
            List<Op> list = pass == 0 ? under : ops;
            int n = pass == 0 ? nUnder : nOps;
            for (int k = 0; k < n; k++) {
                Op o = list.get(k);
                if (!o.scaled && direct != null) {
                    direct.blit(o.px, T + 2 * P, P, P, T, T, o.dx0, o.dy0, o.opaque, o.rows);
                } else if (!o.scaled) {
                    g.drawImage(o.img, o.dx0, o.dy0, o.dx1, o.dy1, P, P, P + T, P + T, null);
                } else {
                    if (hint == null) {
                        hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, smooth ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    }
                    g.drawImage(o.img, o.dx0, o.dy0, o.dx1, o.dy1, P, P, P + T, P + T, null);
                }
            }
        }
        if (hint != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
    }

    int tileCount() { return tiles.size(); }
}
