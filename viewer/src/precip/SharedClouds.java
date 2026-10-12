package precip;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The cached cloud images of one view, shared by all band renderers.
 * <p>
 * {@link #prepare} runs once per frame before the bands start. Every visible cloud owns up to two
 * images: the current one and the one it is cross-fading from. While a cloud is on screen its
 * image is re-rendered continuously on worker threads (the noise evolves with sim time, puffs grow,
 * {@code form} and the light change); a finished render is swapped in with a short cross-fade, so
 * the cloud seems to boil and reshape smoothly although each image is static. Nothing is rendered
 * on the frame thread except in synchronous mode (snapshots), so a slow cloud never costs a frame.
 */
final class SharedClouds {
    /** Cross-fade duration between two renders of the same cloud. */
    static final long FADE_NANOS = 500_000_000L;

    static final class Entry {
        // --- owned by prepare() (frame thread) ---
        CloudPainter.Image cur, prev;
        long curSince;
        long builtSig = Long.MIN_VALUE, lastSubmit;
        long lastSeen;
        // --- handed over by the worker ---
        volatile CloudPainter.Image ready;
        volatile boolean hasReady, building;
        volatile long readySig;
        // --- what the bands draw this frame (written in prepare, read-only afterwards) ---
        CloudPainter.Image drawCur, drawPrev;
        int fade256 = 256;
    }

    private final Map<Long, Entry> map = new HashMap<>();
    private final ExecutorService pool;
    private final ThreadLocal<CloudPainter.Scratch> scratch = ThreadLocal.withInitial(CloudPainter.Scratch::new);
    private final CloudPainter.Scratch syncScratch = new CloudPainter.Scratch();
    private long frame;
    /** When false (snapshots) every stale image is re-rendered synchronously and there is no fade. */
    boolean async = true;
    long builds, buildNanos; // diagnostics

    SharedClouds() {
        int[] id = {0};
        pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "cloud-painter-" + (++id[0]));
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    /** Build the render spec of one cloud for this frame. */
    static CloudPainter.Spec spec(Model.Cloud c, Model.Frame f, Scene scene, CloudPainter.Light light, double scale) {
        CloudPainter.Spec sp = new CloudPainter.Spec();
        sp.seed = c.seed;
        sp.kind = c.kind;
        sp.morphOf(c);
        sp.extrasOf(c);
        sp.puffs = c.puffs;
        sp.form = c.form;
        sp.shear = c.shear;
        sp.anvil = c.anvil;
        sp.darkness = c.darkness;
        sp.opacity = c.opacity;
        sp.tint = c.tint;
        sp.time = f.t;
        sp.light = light;
        sp.scale = scale;
        double[] w = f.windAt(c.x, c.y);
        sp.wind = w[0];
        // (Settings > Advanced "No snow": a cloud that snows hangs no shaft of snow under it either)
        if (c.precipRate > 0.02 && !"none".equals(c.precipType) && !(scene.noSnow && "snow".equals(c.precipType))) {
            double base = CloudPainter.baseOf(c.kind, c.puffs);
            double lowest = 0;
            for (double x = c.x - c.w * 0.4; x <= c.x + c.w * 0.4 + 1; x += Math.max(8, c.w * 0.1)) lowest = Math.max(lowest, scene.terrain.at(x));
            sp.precipType = c.precipType;
            sp.precipRate = c.precipRate;
            sp.veilDrop = Math.round((lowest - (c.y + base)) / 4) * 4.0;
            double[] wl = f.windAt(c.x, (c.y + base + lowest) / 2);
            sp.veilDrift = Math.round(Model.clamp(wl[0] * 9, -220, 220) / 4) * 4.0;
        }
        return sp;
    }

    /** Rough world bounding box of a cloud (generous: includes anvil, shear and the rain veil). */
    static double[] bounds(Model.Cloud c) {
        double minX = -c.w / 2, maxX = c.w / 2, minY = -c.h / 2, maxY = c.h / 2;
        for (double[] p : c.puffs) {
            minX = Math.min(minX, p[0] - p[2]); maxX = Math.max(maxX, p[0] + p[2]);
            minY = Math.min(minY, p[1] - p[2]); maxY = Math.max(maxY, p[1] + p[2]);
        }
        // the anvil margin of a cumulonimbus; one that is still growing out of a cumulus gains it gradually
        double side = c.kind == Model.CUMULONIMBUS ? 0.6 : c.kind == Model.SUPERCELL ? 0.75 : 0.2;
        if (c.morphing()) side = 0.2 + (side - 0.2) * c.morph;
        double grow = (maxX - minX) * side + Math.abs(c.shear) + 30;
        // a supercell's overshooting dome is drawn above its puffs
        double up = c.kind == Model.SUPERCELL ? 40 + (maxX - minX) * 0.12 * c.overshoot : 40;
        return new double[]{c.x + minX - grow, c.y + minY - up, c.x + maxX + grow, c.y + maxY + 30};
    }

    /** Give every visible cloud its images for this frame and keep the workers busy. */
    void prepare(Model.Frame f, Scene scene, CloudPainter.Light light, Camera.View v, long clock) {
        frame++;
        if (f == null) return;
        double scale = v.targetZoom * v.dev;
        for (Model.Cloud c : f.clouds) {
            if (c.puffs.length == 0) continue;
            double[] b = bounds(c);
            double mx = (v.wx1 - v.wx0) * 0.2 + 60;
            boolean vis = v.sees(b[0] - mx, b[1] - 60, b[2] + mx, b[3] + (c.precipRate > 0.02 ? 2000 : 60));
            Entry e = map.get(c.id);
            if (!vis) {
                if (e != null) { e.drawCur = null; e.drawPrev = null; }
                continue;
            }
            if (e == null) { e = new Entry(); map.put(c.id, e); }
            e.lastSeen = frame;
            CloudPainter.Spec sp = spec(c, f, scene, light, scale);
            long sig = sp.signature();
            sp.reuse = e.cur;
            if (!async) {
                if (sig != e.builtSig || e.cur == null) {
                    e.cur = build(sp, syncScratch);
                    e.builtSig = sig;
                }
                e.prev = null;
                e.drawCur = e.cur;
                e.drawPrev = null;
                e.fade256 = 256;
                continue;
            }
            // promote a finished render once the previous fade is over
            boolean fading = e.prev != null && clock - e.curSince < FADE_NANOS;
            if (e.hasReady && !fading) {
                CloudPainter.Image r = e.ready;
                boolean sameScale = e.cur != null && r != null && e.cur.px != null && r.px != null && e.cur.scale == r.scale;
                e.prev = sameScale || e.cur == null || e.cur.px == null ? e.cur : null;
                if (e.cur == null) e.prev = EMPTY; // first appearance: fade in from nothing
                e.cur = r == null ? EMPTY : r;
                e.curSince = clock;
                e.builtSig = e.readySig;
                e.ready = null;
                e.hasReady = false;
                fading = e.prev != null;
            }
            if (!fading) e.prev = null;
            if (!e.building && !e.hasReady && sig != e.builtSig && (e.cur == null || clock - e.lastSubmit > 90_000_000L)) {
                e.building = true;
                e.lastSubmit = clock;
                final Entry fe = e;
                pool.submit(() -> {
                    CloudPainter.Image img = null;
                    try {
                        img = build(sp, scratch.get());
                    } catch (RuntimeException ex) {
                        System.err.println("[viewer] cloud render failed: " + ex);
                    } finally {
                        fe.ready = img;
                        fe.readySig = sig;
                        fe.hasReady = true;
                        fe.building = false;
                    }
                });
            }
            e.drawCur = e.cur;
            e.drawPrev = e.prev;
            e.fade256 = e.prev == null ? 256 : (int) Math.max(0, Math.min(256, (clock - e.curSince) * 256 / FADE_NANOS));
        }
        if ((frame & 63) == 0) map.values().removeIf(e -> frame - e.lastSeen > 600 && !e.building);
    }

    /** Marker for "rendered, but there is nothing to draw" (a cloud whose form is ~0). */
    static final CloudPainter.Image EMPTY = new CloudPainter.Image();

    private CloudPainter.Image build(CloudPainter.Spec sp, CloudPainter.Scratch sc) {
        long t0 = System.nanoTime();
        CloudPainter.Image img = CloudPainter.render(sp, sc);
        synchronized (this) {
            builds++;
            buildNanos += System.nanoTime() - t0;
        }
        return img == null ? EMPTY : img;
    }

    /** The entry of a cloud (read-only use during the band phase), or null. */
    Entry get(long id) { return map.get(id); }

    void shutdown() { pool.shutdownNow(); }
}
