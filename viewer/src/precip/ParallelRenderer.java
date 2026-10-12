package precip;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Renders one frame with several {@link Renderer}s in parallel, each drawing the same snapshot
 * into its own horizontal band of the (software) target image.
 * <p>
 * All renderers snapshot the shared state inside one lock acquisition (so bands cannot tear), the
 * caches every band needs (scenery tiles, cloud images, thumbnails, lighting, the camera view) are
 * prepared once in {@link Shared} before the bands start, and all bands get the same frame clock:
 * the bands join seamlessly. Renderer 0 is the "primary": the panel reads hit-test data and sets
 * UI toggles on it.
 * <p>
 * One instance belongs to one game view; call {@link #shutdown} when the view is disposed.
 */
final class ParallelRenderer {
    /** --threads K; 0 = automatic. */
    static int configuredThreads = 0;

    final Renderer[] rs;
    private final ExecutorService pool;
    final Shared shared = new Shared();
    final long[] bandNanos;   // diagnostics: accumulated draw time per band
    long prepNanos;

    ParallelRenderer(int threads) {
        int n = Math.max(1, threads);
        rs = new Renderer[n];
        bandNanos = new long[n];
        for (int i = 0; i < n; i++) rs[i] = new Renderer();
        if (n > 1) {
            int[] id = {0};
            pool = Executors.newFixedThreadPool(n - 1, r -> {
                Thread t = new Thread(r, "render-band-" + (++id[0]));
                t.setDaemon(true);
                return t;
            });
        } else {
            pool = null;
        }
    }

    static int defaultThreads() {
        if (configuredThreads > 0) return configuredThreads;
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(4, cores / 2)); // leave cores for the sim, the reader and Swing
    }

    Renderer primary() { return rs[0]; }

    int threads() { return rs.length; }

    /** Lay the camera's viewport out on a canvas of the given logical size (the right panel takes its column). */
    void layout(Camera cam, ViewerState st, double canvasW, double canvasH) {
        Model.Hello h;
        synchronized (st) { h = st.hello; }
        if (h != null) cam.setWorld(h.w, h.h);
        Renderer r = rs[0];
        double pw = r.rightPanelWidthNow(canvasW);
        // a surrounding screen (Cloud Formations) may take strips of the canvas for itself
        cam.setViewport(r.insetLeft, r.insetTop, canvasW - pw - r.insetLeft - r.insetRight, canvasH - r.insetTop - r.insetBottom);
    }

    /**
     * Render the logical canvas (0, 0, canvasW, canvasH) into {@code target} (an opaque int image).
     * {@code dev} is the device scale (HiDPI), (devX, devY) the device position of the canvas origin.
     * The camera must already be laid out ({@link #layout}) and advanced by the caller.
     */
    void render(BufferedImage target, double dev, int devX, int devY, double canvasW, double canvasH, ViewerState st, Camera cam,
                long nowNanos, double animSec, String sourceName) {
        Renderer r0 = rs[0];
        for (int i = 1; i < rs.length; i++) syncUi(r0, rs[i]);
        synchronized (st) {
            for (Renderer r : rs) r.snapshotLocked(st);
        }
        long clock = System.nanoTime();
        long p0 = clock;
        Camera.View view = cam.view(canvasW, canvasH, dev, devX, devY);
        for (Renderer r : rs) r.setTarget(target);
        // the people's clock stands still while the simulation is paused (every band reads the same value)
        Model.Frame cur = r0.snapshotFrame();
        shared.advanceLife(animSec, cur != null && cur.paused);
        r0.prepareShared(shared, view, nowNanos, clock);
        int x0 = Math.max(0, devX), y0 = Math.max(0, devY);
        int x1 = Math.min(target.getWidth(), (int) Math.floor(devX + dev * canvasW + 0.5)), y1 = Math.min(target.getHeight(), (int) Math.floor(devY + dev * canvasH + 0.5));
        int n = rs.length;
        prepNanos += System.nanoTime() - p0;
        if (n == 1) {
            band(0, target, x0, x1, y0, y1, animSec, sourceName, clock);
            return;
        }
        Future<?>[] fs = new Future<?>[n];
        final int fx0 = x0, fx1 = x1;
        for (int i = 1; i < n; i++) {
            final int k = i;
            final int by0 = y0 + (y1 - y0) * k / n, by1 = y0 + (y1 - y0) * (k + 1) / n;
            fs[i] = pool.submit(() -> band(k, target, fx0, fx1, by0, by1, animSec, sourceName, clock));
        }
        RuntimeException err = null;
        try {
            band(0, target, x0, x1, y0, y0 + (y1 - y0) / n, animSec, sourceName, clock);
        } catch (RuntimeException ex) {
            err = ex;
        }
        for (int i = 1; i < n; i++) {
            try {
                fs[i].get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException ex) {
                if (err == null) err = ex.getCause() instanceof RuntimeException re ? re : new RuntimeException(ex.getCause());
            }
        }
        if (err != null) throw err;
    }

    private void band(int k, BufferedImage target, int x0, int x1, int by0, int by1, double animSec, String sourceName, long clock) {
        if (by1 <= by0 || x1 <= x0) return;
        long t0 = System.nanoTime();
        Graphics2D g = target.createGraphics();
        try {
            g.setClip(x0, by0, x1 - x0, by1 - by0); // device space (identity transform)
            rs[k].draw(g, shared, x0, by0, x1, by1, animSec, sourceName, clock);
        } finally {
            g.dispose();
            bandNanos[k] += System.nanoTime() - t0;
        }
    }

    private static void syncUi(Renderer a, Renderer b) {
        b.showWind = a.showWind;
        b.glintShape = a.glintShape;
        b.showArrows = a.showArrows;
        b.showLabels = a.showLabels;
        b.showLegend = a.showLegend;
        b.showHelp = a.showHelp;
        b.showRightPanel = a.showRightPanel;
        b.showLeftPanel = a.showLeftPanel;
        b.showMinimap = a.showMinimap;
        b.showClimate = a.showClimate;
        b.spawnTypeLabel = a.spawnTypeLabel;
        b.backdropImage = a.backdropImage;
        b.backdropFit = a.backdropFit;
        b.backdropDim = a.backdropDim;
        b.backdropTint = a.backdropTint;
        b.showErrorBox = a.showErrorBox;
        b.decorative = a.decorative;
        b.hudReserveW = a.hudReserveW;
        b.animatePanels = a.animatePanels;
        b.rightSlide = a.rightSlide;
        b.leftSlide = a.leftSlide;
        b.miniSlide = a.miniSlide;
        b.helpRows = a.helpRows;
        b.toast = a.toast;
        b.toastUntilNanos = a.toastUntilNanos;
        b.timelineScroll = a.timelineScroll;
    }

    void shutdown() {
        if (pool != null) pool.shutdownNow();
        shared.shutdown();
    }
}
