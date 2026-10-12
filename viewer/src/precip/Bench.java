package precip;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Locale;

/**
 * {@code --bench N}: renders N frames of the live scene at the logical canvas size (1680x720 by
 * default, {@code --size} to change) through the default camera and prints avg / p50 / p95 / max
 * ms per frame. Works with the built-in fake (deterministic) or the real Python sim (frames keep
 * streaming while we render back-to-back, exactly like the window does). {@code --bench-pan}
 * drags the camera sideways every frame to measure the cost of a moving view.
 */
final class Bench {
    private Bench() {}

    static int run(ViewerState state, FrameSource source, int n, int warm, double scale, boolean profile,
                   int spawn, double cold, double simSpeed, int feed, int lw, int lh, boolean pan) throws Exception {
        Scripted fake = source instanceof Scripted fs ? fs : null;
        // optional extra load: seed K clouds across the sky (+ optional cooling) via protocol commands
        java.util.List<String> cmds = new java.util.ArrayList<>();
        if (cold != 0) cmds.add(String.format(Locale.ROOT, "{\"cmd\":\"temp\",\"delta\":%.1f}", -Math.abs(cold)));
        if (simSpeed > 0) cmds.add(String.format(Locale.ROOT, "{\"cmd\":\"speed\",\"value\":%.2f}", simSpeed));
        for (int k = 0; k < spawn; k++) {
            cmds.add(String.format(Locale.ROOT, "{\"cmd\":\"spawn\",\"x\":%.1f,\"y\":%.1f}",
                    1000 + (k + 0.5) * 1800.0 / spawn, 250.0 + 120 * ((k * 7) % 3) / 2.0));
        }
        if (fake != null) {
            state.acceptLine(fake.hello());
            if (fake instanceof FakeSim fs) for (String c : cmds) fs.handle(c);
            if (fake instanceof FakeSim fs) for (String c : Main.optCmds) fs.handle(c);
            if (Main.optUntil != null) {
                // v2.3: warm up to a condition instead of a frame count (e.g. --until tornado:mature+4: cars and a tornado on screen)
                Main.Until until = new Main.Until(Main.optUntil);
                int k = 0;
                for (; k < 40_000; k++) {
                    state.acceptLine(fake.step());
                    if (until.reached(state)) break;
                }
                System.out.println("[bench] --until " + Main.optUntil + (k < 40_000 ? " reached after " + (k + 1) + " frames" : " NOT reached"));
            } else
            for (int k = 0; k < warm; k++) state.acceptLine(fake.step());
        } else {
            source.start(state);
            for (String c : cmds) source.send(c);
            // --cmd and --until work with the real simulation too (they were silently ignored here: a bench asked to
            // run "until a tornado is mature" timed an ordinary sky instead). Same handling as Main.snapshot.
            Main.Cmds timed = new Main.Cmds(Main.optCmds);
            for (String c : timed.due(Double.NaN)) source.send(c);
            Main.Until until = Main.optUntil == null ? null : new Main.Until(Main.optUntil);
            if (until != null) source.send("{\"cmd\":\"speed\",\"value\":4}");
            boolean met = false;
            long deadline = System.currentTimeMillis() + (until != null ? 300_000 : Math.max(30_000, warm * 100L));
            while (System.currentTimeMillis() < deadline) {
                synchronized (state) {
                    if (state.cur != null) for (String c : timed.due(state.cur.t)) source.send(c);
                    if (state.error != null) break;
                    if (until != null ? until.reached(state) : state.framesReceived >= warm) { met = true; break; }
                }
                Thread.sleep(10);
            }
            if (until != null) {
                source.send("{\"cmd\":\"speed\",\"value\":1}");
                System.out.println("[bench] --until " + Main.optUntil + (met ? " reached" : " NOT reached in time"));
            }
            synchronized (state) {
                if (state.error != null) { System.err.println("[bench] sim error: " + state.error); source.stop(); return 2; }
            }
        }

        int pw = (int) Math.round(lw * scale), ph = (int) Math.round(lh * scale);
        // --profile times one renderer's band, so it defaults to a single band
        ParallelRenderer pr = new ParallelRenderer(profile && ParallelRenderer.configuredThreads == 0 ? 1 : ParallelRenderer.defaultThreads());
        Renderer r = pr.primary();
        BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
        Camera cam = new Camera();
        pr.layout(cam, state, lw, lh);
        Main.applyShade(r);
        Main.injectBolt(state);
        if (Main.optCam != null) Main.applyCam(cam, Main.optCam, state);
        double shadeSum = 0;

        int jit = Math.min(120, Math.max(20, n / 4));
        double[] ms = new double[n];
        long parseNs = 0;
        long particles = 0, clouds = 0, maxParticles = 0, visClouds = 0, carsSeen = 0, tornadoFrames = 0;
        long anim0 = System.nanoTime();
        long recv0;
        double simT0;
        synchronized (state) { recv0 = state.framesReceived; simT0 = state.cur != null ? state.cur.t : 0; }
        long benchWall0 = 0;
        double panDir = 1;
        for (int k = -jit; k < n; k++) {
            if (k == 0) {
                if (profile) r.profNs = new long[Renderer.PROF_NAMES.length];
                synchronized (state) { recv0 = state.framesReceived; simT0 = state.cur != null ? state.cur.t : 0; }
                parseNs = 0;
                benchWall0 = System.nanoTime();
            }
            // scripted sources advance one sim frame every `feed` renders: the window repaints at
            // ~60 Hz while the sim streams 30 frames/s, so the default feed of 2 matches real use
            if (fake != null && Math.floorMod(k, feed) == 0) {
                String line = fake.step();
                long p0 = System.nanoTime();
                state.acceptLine(line);
                parseNs += System.nanoTime() - p0;
                Main.injectBolt(state);              // --bolt: the same test bolt in every frame (a steady worst case for the lightning slot)
            }
            long t0 = System.nanoTime();
            pr.layout(cam, state, lw, lh);
            if (pan) {
                double before = cam.cx;
                cam.dragBy(-4 * panDir, 0);
                if (cam.cx == before) panDir = -panDir;
            }
            cam.update(1 / 60.0);
            pr.render(img, scale, 0, 0, lw, lh, state, cam, System.nanoTime(), (System.nanoTime() - anim0) / 1e9, source.describe());
            long t1 = System.nanoTime();
            if (k >= 0) {
                ms[k] = (t1 - t0) / 1e6;
                shadeSum += r.shadeOn ? r.shadeLevel * r.shadeStrength * CloudShade.MAX_ALPHA : 0;
                synchronized (state) {
                    if (state.cur != null) {
                        particles += state.cur.particleCount;
                        maxParticles = Math.max(maxParticles, state.cur.particleCount);
                        clouds += state.cur.clouds.size();
                        tornadoFrames += state.cur.tornadoes.isEmpty() ? 0 : 1;
                        Camera.View cv = r.view;
                        if (cv != null) for (Model.Car c : state.cur.cars) if (c.x >= cv.wx0 && c.x <= cv.wx1) carsSeen++;
                        Camera.View v = r.view;
                        if (v != null) for (Model.Cloud c : state.cur.clouds) if (c.x + c.w / 2 >= v.wx0 && c.x - c.w / 2 <= v.wx1) visClouds++;
                    }
                }
            }
        }
        long benchWall = System.nanoTime() - benchWall0;
        long recv;
        double simT1;
        synchronized (state) { recv = state.framesReceived - recv0; simT1 = state.cur != null ? state.cur.t : 0; }
        if (fake == null) source.stop();
        pr.shutdown();

        double[] sorted = ms.clone();
        Arrays.sort(sorted);
        double sum = 0;
        for (double v : ms) sum += v;
        System.out.println(String.format(Locale.ROOT,
                "[bench] source=%s canvas %dx%d (scale %.2f) camera zoom %.2f%s threads=%d frames=%d feed=%d  avg=%.2f ms  p50=%.2f  p95=%.2f  max=%.2f  (%.0f fps)",
                fake != null ? source.describe() : "python", lw, lh, scale, cam.zoom, pan ? " panning" : "", pr.threads(), n, fake != null ? feed : 0,
                sum / n, pct(sorted, 50), pct(sorted, 95), sorted[n - 1], 1000.0 / (sum / n)));
        System.out.println(String.format(Locale.ROOT, "[bench] particles listed avg=%d max=%d  clouds in world avg=%.1f, in view avg=%.1f  under-cloud dimming %s avg veil %.3f",
                particles / n, maxParticles, clouds / (double) n, visClouds / (double) n, r.shadeOn ? "on" : "off", shadeSum / n));
        if (!Advanced.cur.equals(Advanced.DEFAULT)) System.out.println("[bench] advanced: " + Advanced.cur);
        System.out.println("[bench] particles left out because an opaque cloud hides them: " + Renderer.CULLED.sum() + " in " + (n + jit) + " frames (all bands, all passes)");
        if (carsSeen > 0 || tornadoFrames > 0) System.out.println(String.format(Locale.ROOT, "[bench] v2.3: cars in view avg=%.1f, a tornado in the world on %d of %d frames", carsSeen / (double) n, tornadoFrames, n));
        if (fake != null) {
            System.out.println(String.format(Locale.ROOT, "[bench] JSON parse+apply avg=%.2f ms per sim frame", parseNs / 1e6 / Math.max(1, (n + feed - 1) / feed)));
        } else {
            double wallS = benchWall / 1e9;
            double[] gaps = state.arrivalGapsMs();
            double parse = state.avgParseMs();
            System.out.println(String.format(Locale.ROOT,
                    "[bench] sim frames received during bench=%d over %.2f s (%.1f fps), sim time advanced %.2f s (rate %.2fx wall)",
                    recv, wallS, recv / wallS, simT1 - simT0, (simT1 - simT0) / wallS));
            if (gaps.length > 0) {
                double[] gs = gaps.clone();
                Arrays.sort(gs);
                System.out.println(String.format(Locale.ROOT,
                        "[bench] frame arrival gap p50=%.1f ms p95=%.1f ms max=%.1f ms; reader parse+apply avg=%.2f ms, lines skipped as stale=%d",
                        pct(gs, 50), pct(gs, 95), gs[gs.length - 1], parse, state.skippedStale));
            }
        }
        {
            StringBuilder bs = new StringBuilder(String.format(Locale.ROOT, "[bench] per-frame: prepare %.2f ms, bands", pr.prepNanos / 1e6 / (n + jit)));
            for (long v : pr.bandNanos) bs.append(String.format(Locale.ROOT, " %.2f", v / 1e6 / (n + jit)));
            System.out.println(bs.append(" ms"));
        }
        Shared sh = pr.shared;
        System.out.println(String.format(Locale.ROOT,
                "[bench] off-frame work: %d cloud renders, %.1f ms each (worker threads); %d scenery tiles painted, %.2f ms each",
                sh.clouds.builds, sh.clouds.buildNanos / 1e6 / Math.max(1, sh.clouds.builds), sh.tileBuilds(), sh.tileBuildNanos() / 1e6 / Math.max(1, sh.tileBuilds())));
        System.out.println("[bench] wind on wet panes: " + sh.windLevelChanges + " level changes of a building after the first look, " + sh.windRepaints + " repaints of a wet building for that alone");
        if (profile && r.profNs != null) {
            long tot = 0;
            for (long v : r.profNs) tot += v;
            StringBuilder sb = new StringBuilder("[bench] rebuilds: glow " + r.sunGlowBuilds + ", panel " + r.panelBuilds);
            sb.append("\n[bench] profile (avg ms/frame):");
            for (int k = 0; k < Renderer.PROF_NAMES.length; k++) {
                sb.append(String.format(Locale.ROOT, "\n    %-14s %6.2f  (%4.1f%%)", Renderer.PROF_NAMES[k], r.profNs[k] / 1e6 / n, 100.0 * r.profNs[k] / Math.max(1, tot)));
            }
            System.out.println(sb);
        }
        return 0;
    }

    private static double pct(double[] sorted, double p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }
}
