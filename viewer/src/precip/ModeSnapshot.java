package precip;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * {@code --mode hurricane|showcase --snapshot out.png}: draw one of the two lesson modes into a PNG
 * without opening a window, with exactly the code the live window uses (the mode's screen and
 * {@link AppShell#paintOverlay}). The source is the real simulation, the built-in fake
 * ({@code --fake}) or a recorded stream ({@code --replay file.jsonl}).
 * <pre>
 *   --mode hurricane [--chapter N | --explore] [--stage ID] [--hview outside|inside|both] [--frames N]
 *   --mode showcase  [--cloud-type TYPE] [--until-stage STAGE] [--frames N]
 * </pre>
 * {@code --frames} is how many frames to let pass after the stage / type was asked for (the storm
 * glides to a stage over a few seconds). {@code --until-stage} runs the showcase until its cloud
 * is in that life stage (time is sped up for the real simulation).
 */
final class ModeSnapshot {
    private ModeSnapshot() {}

    static final class Opts {
        String mode = "hurricane", out, replay, stage, hview, cloudType, untilStage;
        boolean fake, explore, labelsOff;
        int frames = 240, chapter = -1, w = 1680, h = 760, bench;
        double dpi = 1;
        Long seed;
        // ---- v2.3 world mode: --month M --hour H (UTC) --layer temp|cloud|precip|wind|pressure|snow|map --select <preset id>
        //      --cam lon,lat[,zoom] (zoom 1 = whole world) --hover lon,lat --click lon,lat --show-help
        double month = Double.NaN, hour = Double.NaN;
        String layer, select, cam, hover, click;
        boolean help;
    }

    static int run(File root, Settings settings, Opts o) throws Exception {
        settings.persist = false;                        // a snapshot never writes settings
        AppShell app = new AppShell(root, settings, new SaveStore(new File(root, "saves")));
        app.applySettings();
        try {
            return o.mode.equals("hurricane") ? hurricane(root, app, o) : o.mode.equals("world") ? world(root, app, o) : showcase(root, app, o);
        } finally {
            app.io.shutdown();
        }
    }

    private static List<String> seedArgs(Opts o, String... first) {
        List<String> a = new ArrayList<>(List.of(first));
        if (o.seed != null) { a.add("--seed"); a.add(String.valueOf(o.seed)); }
        return a;
    }

    private static boolean waitFor(ViewerState st, java.util.function.BooleanSupplier cond, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            synchronized (st) { if (st.error != null || cond.getAsBoolean()) return st.error == null; }
            Thread.sleep(15);
        }
        return false;
    }

    // ------------------------------------------------------------------ hurricane

    private static int hurricane(File root, AppShell app, Opts o) throws Exception {
        FrameSource src = o.replay != null ? new ReplaySource(new File(o.replay)) : o.fake ? new FakeHurricane()
                : new SimProcess(root, 1920, 1080, seedArgs(o, "--mode", "hurricane"));
        GameSession gs = new GameSession(app, null, src, false, "Hurricane Formation");
        gs.mode = "hurricane";
        app.session = gs;
        HurricaneScreen screen = new HurricaneScreen(app, gs);
        screen.still = true;
        screen.labels = !o.labelsOff;
        app.modeScreen = screen;
        app.push(screen);
        ViewerState st = gs.state;
        String verified;
        if (src instanceof Scripted sc) {
            st.acceptLine(sc.hello());
            String stage = stageFor(st, o);
            if (stage != null && src instanceof FakeHurricane fh) fh.handle("{\"cmd\":\"stage\",\"id\":" + Json.quote(stage) + "}");
            for (int k = 0; k < Math.max(1, o.frames); k++) {
                String line = sc.step();
                if (line == null) break;
                st.acceptLine(line);
            }
            verified = o.replay != null ? "recorded stream " + new File(o.replay).getName() : "built-in fake";
        } else {
            src.start(st);
            if (!waitFor(st, () -> st.hurricane != null && st.hurricane.cur != null, 30_000)) {
                src.stop();
                System.err.println("[viewer] the hurricane simulation did not start" + (st.error != null ? ": " + st.error.split("\n")[0] : ""));
                return 2;
            }
            String stage = stageFor(st, o);
            if (stage != null) src.send("{\"cmd\":\"stage\",\"id\":" + Json.quote(stage) + "}");
            long f0;
            synchronized (st) { f0 = st.framesReceived; }
            waitFor(st, () -> st.framesReceived - f0 >= o.frames, Math.max(20_000, o.frames * 120L));
            src.stop();
            verified = "real simulation (" + src.describe() + ")";
        }
        HurricaneModel.Frame f;
        synchronized (st) { f = st.hurricane == null ? null : st.hurricane.cur; }
        if (f == null) { System.err.println("[viewer] no hurricane frame was received"); return 2; }

        // the screen as the player would have it: tour chapter N, or the stage explorer
        if (o.explore || (o.chapter < 0 && o.stage != null)) {
            screen.tour = false;
            screen.selStage = o.stage != null ? o.stage : f.stage;
        } else {
            screen.tour = true;
            screen.chapter = Math.max(0, o.chapter);
            HurricaneModel m = st.hurricane;
            if (!m.lesson.isEmpty()) {
                HurricaneModel.Chapter c = m.lesson.get(Math.min(m.lesson.size() - 1, screen.chapter));
                screen.view = switch (c.view) { case "outside" -> HurricaneScreen.OUTSIDE; case "inside" -> HurricaneScreen.INSIDE; default -> HurricaneScreen.BOTH; };
            }
        }
        screen.markStarted();
        if (o.hview != null) screen.view = switch (o.hview) { case "outside" -> HurricaneScreen.OUTSIDE; case "inside" -> HurricaneScreen.INSIDE; default -> HurricaneScreen.BOTH; };

        int dw = (int) Math.round(o.w * o.dpi), dh = (int) Math.round(o.h * o.dpi);
        BufferedImage img = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
        if (o.bench > 0) {
            // how long one live frame of the hurricane screen takes (both pictures + labels), as the window paints it
            screen.still = false;
            double[] ms = new double[o.bench];
            for (int k = -30; k < o.bench; k++) {
                // the simulation sends 30 frames a second while the window paints 60: a new frame every other paint
                if (src instanceof FakeHurricane fh && (k & 1) == 0) st.acceptLine(fh.step());
                long t0 = System.nanoTime();
                screen.paintScene(img, o.dpi, o.w, o.h, 1 / 60.0);
                if (k >= 0) ms[k] = (System.nanoTime() - t0) / 1e6;
            }
            double[] sorted = ms.clone();
            java.util.Arrays.sort(sorted);
            double sum = 0;
            for (double v : ms) sum += v;
            System.out.println(String.format(java.util.Locale.ROOT, "[bench] hurricane screen %dx%d (scale %.2f, view %d): frames=%d avg=%.2f ms p50=%.2f p95=%.2f max=%.2f",
                    o.w, o.h, o.dpi, screen.view, o.bench, sum / o.bench, sorted[o.bench / 2], sorted[(int) (o.bench * 0.95)], sorted[o.bench - 1]));
            screen.still = true;
        }
        screen.paintScene(img, o.dpi, o.w, o.h, -1);
        overlay(app, img, o, null);
        write(img, o.out);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[viewer] hurricane snapshot written: %s (%dx%d, source: %s, stage %s %.0f%%, %.0f kt, %.0f hPa, organization %.2f, eye clarity %.2f, %s)",
                new File(o.out).getAbsolutePath(), dw, dh, verified, f.stage, f.stageFrac * 100, f.vmaxKt, f.pressure, f.organization, f.eyeClarity,
                screen.tour ? "tour chapter " + (screen.chapter + 1) : "stage explorer"));
        return 0;
    }

    /** The stage to ask for: {@code --stage}, else the stage of {@code --chapter}'s chapter, else none. */
    private static String stageFor(ViewerState st, Opts o) {
        if (o.stage != null) return o.stage;
        HurricaneModel m;
        synchronized (st) { m = st.hurricane; }
        if (m == null || m.lesson.isEmpty() || o.explore) return null;
        String s = m.lesson.get(Math.max(0, Math.min(m.lesson.size() - 1, o.chapter))).stage;
        return s.isEmpty() ? null : s;
    }

    // ------------------------------------------------------------------ cloud formations

    private static int showcase(File root, AppShell app, Opts o) throws Exception {
        String type = o.cloudType == null ? "cumulus" : o.cloudType;
        FrameSource src = o.replay != null ? new ReplaySource(new File(o.replay)) : o.fake ? FakeSim.showcase(o.seed != null ? o.seed : 4242, 0.40, type)
                : new SimProcess(root, ShowcaseScreen.STAGE_W, ShowcaseScreen.STAGE_H, seedArgs(o, "--mode", "showcase", "--cloud-type", type));
        GameSession gs = new GameSession(app, null, src, false, "Cloud Formations");
        gs.mode = "showcase";
        app.session = gs;
        ShowcaseScreen screen = new ShowcaseScreen(app, gs, type);
        app.modeScreen = screen;
        app.push(screen);
        ViewerState st = gs.state;
        // v2.3: --until SPEC (e.g. step:tornado+6 for a step of the lifetime tour) works here like --until-stage
        final Main.Until until = Main.optUntil == null ? null : new Main.Until(Main.optUntil);
        final boolean waiting = o.untilStage != null || until != null;
        java.util.function.BooleanSupplier reached = () -> {
            Model.Frame f = st.cur;
            if (until != null) return until.reached(st);
            if (f == null || o.untilStage == null) return false;
            Model.Cloud c = f.showcaseCloudId >= 0 ? f.cloud(f.showcaseCloudId) : f.clouds.isEmpty() ? null : f.clouds.get(0);
            // a little way into the stage, so the picture shows it in full swing
            if (c == null || !c.stage.equals(o.untilStage)) return false;
            Double at = c.stageTimes.get(o.untilStage);
            if (at == null) return true;
            // "precipitating" is worth seeing once it really pours (or, for a light type, well into the stage)
            if (o.untilStage.equals("precipitating")) return f.t - at >= 9 && (c.precipRate >= 0.25 || f.t - at >= c.lifetime * 0.2);
            // (forming lasts 6 % of the lifetime and a supercell's "mature" about 5 %, so those two are caught earlier:
            //  with the 7 % dwell they were never reached and the run ended in the 150 s timeout)
            boolean brief = o.untilStage.equals("forming") || (c.kind == Model.SUPERCELL && o.untilStage.equals("mature"));
            return f.t - at >= Math.min(brief ? 2.5 : 10, c.lifetime * (brief ? 0.04 : 0.07));
        };
        String verified;
        if (src instanceof Scripted sc) {
            st.acceptLine(sc.hello());
            int max = waiting ? 40_000 : Math.max(1, o.frames);
            for (int k = 0; k < max; k++) {
                String line = sc.step();
                if (line == null) break;
                st.acceptLine(line);
                if (waiting && reached.getAsBoolean()) break;
            }
            verified = o.replay != null ? "recorded stream " + new File(o.replay).getName() : "built-in fake";
        } else {
            src.start(st);
            if (!waitFor(st, () -> st.cur != null, 30_000)) {
                src.stop();
                System.err.println("[viewer] the showcase simulation did not start" + (st.error != null ? ": " + st.error.split("\n")[0] : ""));
                return 2;
            }
            if (waiting) {
                src.send("{\"cmd\":\"speed\",\"value\":8}");
                if (!waitFor(st, reached, 150_000)) System.err.println("[viewer] the cloud did not reach \"" + (until != null ? Main.optUntil : o.untilStage) + "\" in time; taking the picture as it is");
            } else {
                long f0;
                synchronized (st) { f0 = st.framesReceived; }
                waitFor(st, () -> st.framesReceived - f0 >= o.frames, Math.max(20_000, o.frames * 120L));
            }
            src.stop();
            verified = "real simulation (" + src.describe() + ")";
        }
        Model.Frame f;
        Model.Hello hello;
        synchronized (st) { f = st.cur; hello = st.hello; }
        if (f == null) { System.err.println("[viewer] no frame was received"); return 2; }

        ParallelRenderer pr = new ParallelRenderer(ParallelRenderer.defaultThreads());
        pr.shared.setAsync(false);
        Renderer r = pr.primary();
        ShowcaseScreen.style(r);
        screen.applyLayout(r, o.w, o.h);
        Camera cam = new Camera();
        pr.layout(cam, st, o.w, o.h);
        cam.fit();
        cam.snap();
        int dw = (int) Math.round(o.w * o.dpi), dh = (int) Math.round(o.h * o.dpi);
        BufferedImage img = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
        pr.render(img, o.dpi, 0, 0, o.w, o.h, st, cam, -1, f.t, src.describe());
        overlay(app, img, o, r);
        pr.shutdown();
        write(img, o.out);
        Model.Cloud c = f.showcaseCloudId >= 0 ? f.cloud(f.showcaseCloudId) : f.clouds.isEmpty() ? null : f.clouds.get(0);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[viewer] cloud formations snapshot written: %s (%dx%d, source: %s, hello mode %s, world %dx%d, type %s%s, cycle %d, cloud %s, t=%.1f, particles %d, streamlines %d, arrows %d)",
                new File(o.out).getAbsolutePath(), dw, dh, verified, hello == null ? "?" : hello.mode, f.w, f.h, f.showcaseType,
                f.tourStep == null ? "" : ", tour step " + f.tourStep + (f.tornadoes.isEmpty() ? "" : ", tornado " + f.tornadoes.get(0).stage + " EF" + f.tornadoes.get(0).ef), f.showcaseCycle,
                c == null ? "none" : c.name + " " + c.stage + String.format(java.util.Locale.ROOT, " life %.0f%% precip %s %.2f", c.lifeFrac * 100, c.precipType, c.precipRate),
                f.t, f.particleCount, f.streamlines.size(), f.arrows.size()));
        return 0;
    }

    // ------------------------------------------------------------------ world simulation (v2.3)

    private static double[] pair(String s, int min) {
        String[] p = s.split(",");
        if (p.length < min) throw new IllegalArgumentException("expected " + min + " comma-separated numbers, got \"" + s + "\"");
        double[] d = new double[p.length];
        for (int k = 0; k < p.length; k++) d[k] = Double.parseDouble(p[k].trim());
        return d;
    }

    private static int world(File root, AppShell app, Opts o) throws Exception {
        double month = Double.isNaN(o.month) ? 4.5 : o.month, hour = Double.isNaN(o.hour) ? 13.0 : o.hour;
        FrameSource src = o.replay != null ? new ReplaySource(new File(o.replay)) : o.fake ? new FakeWorld(root, month, hour)
                : new SimProcess(root, 1920, 1080, seedArgs(o, "--mode", "world"));
        GameSession gs = new GameSession(app, null, src, false, "World Simulation");
        gs.mode = "world";
        app.session = gs;
        WorldScreen screen = new WorldScreen(app, gs);
        screen.still = true;
        app.modeScreen = screen;
        app.push(screen);
        ViewerState st = gs.state;
        String verified;
        if (src instanceof Scripted sc) {
            st.acceptLine(sc.hello());
            for (int k = 0; k < Math.max(1, o.frames); k++) {
                String line = sc.step();
                if (line == null) break;
                st.acceptLine(line);
            }
            verified = o.replay != null ? "recorded stream " + new File(o.replay).getName() : "built-in fake";
        } else {
            src.start(st);
            if (!waitFor(st, () -> st.world != null && st.world.cur != null, 30_000)) {
                src.stop();
                System.err.println("[viewer] the world simulation did not start" + (st.error != null ? ": " + st.error.split("\n")[0] : ""));
                return 2;
            }
            // the calendar and the clock asked for: the fields glide there, so give them a moment
            if (!Double.isNaN(o.month)) src.send(String.format(java.util.Locale.ROOT, "{\"cmd\":\"season\",\"month\":%.3f}", o.month));
            if (!Double.isNaN(o.hour)) src.send(String.format(java.util.Locale.ROOT, "{\"cmd\":\"hour\",\"value\":%.3f}", o.hour));
            long f0;
            synchronized (st) { f0 = st.framesReceived; }
            waitFor(st, () -> st.framesReceived - f0 >= o.frames, Math.max(20_000, o.frames * 120L));
            src.stop();
            verified = "real simulation (" + src.describe() + ")";
        }
        WorldModel.Frame f;
        WorldModel wm;
        synchronized (st) { wm = st.world; f = wm == null ? null : wm.cur; }
        if (f == null) { System.err.println("[viewer] no world frame was received"); return 2; }

        if (o.layer != null) {
            int li = java.util.Arrays.asList(WorldPainter.LAYER_IDS).indexOf(o.layer.toLowerCase());
            if (li < 0) { System.err.println("[viewer] unknown --layer \"" + o.layer + "\" (" + String.join(", ", WorldPainter.LAYER_IDS) + ")"); return 2; }
            screen.setLayer(li);
        }
        screen.layout(o.w, o.h);
        WorldPainter wp = screen.painter;
        if (o.cam != null) {
            double[] c = pair(o.cam, 2);
            wp.tscale = wp.fitScale() * (c.length > 2 ? c[2] : 3);
            wp.tlon = c[0]; wp.tlat = c[1];
        }
        wp.snap();
        if (o.select != null && !screen.select(o.select)) { System.err.println("[viewer] --select: the simulation has no location \"" + o.select + "\""); return 2; }
        if (o.click != null) { double[] c = pair(o.click, 2); screen.clickPlace(c[0], c[1]); }
        if (o.hover != null) { double[] c = pair(o.hover, 2); screen.pointAt(c[0], c[1]); }
        screen.help = o.help;

        int dw = (int) Math.round(o.w * o.dpi), dh = (int) Math.round(o.h * o.dpi);
        BufferedImage img = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
        if (o.bench > 0) {
            // how long one live frame of the world screen takes: the map (as the window paints it), then the chrome on top
            screen.still = false;
            double[] ms = new double[o.bench], ov = new double[o.bench];
            BufferedImage chrome = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
            for (int k = -40; k < o.bench; k++) {
                // the simulation sends 30 frames a second while the window paints 60: a new frame every other paint
                if (src instanceof FakeWorld fw && (k & 1) == 0) st.acceptLine(fw.step());
                long t0 = System.nanoTime();
                screen.paintScene(img, o.dpi, o.w, o.h, 1 / 60.0);
                long t1 = System.nanoTime();
                overlay(app, chrome, o, null);
                if (k >= 0) { ms[k] = (t1 - t0) / 1e6; ov[k] = (System.nanoTime() - t1) / 1e6; }
            }
            double[] sorted = ms.clone();
            java.util.Arrays.sort(sorted);
            double sum = 0, osum = 0;
            for (double v : ms) sum += v;
            for (double v : ov) osum += v;
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[bench] world screen %dx%d (scale %.2f, layer %s, zoom %.2f): frames=%d map avg=%.2f ms p50=%.2f p95=%.2f max=%.2f | chrome avg=%.2f ms | mask redraws %d",
                    o.w, o.h, o.dpi, WorldPainter.LAYER_IDS[wp.layer], wp.zoomFactor(), o.bench, sum / o.bench, sorted[o.bench / 2], sorted[(int) (o.bench * 0.95)], sorted[o.bench - 1],
                    osum / o.bench, wp.maskBuilds));
            System.out.println(String.format(java.util.Locale.ROOT, "[bench]   of which (avg over all %d paints): prepare %.2f ms, coloured map %.2f ms, drawn over it %.2f ms",
                    o.bench + 40, screen.profPrep / 1e6 / (o.bench + 40), screen.profMap / 1e6 / (o.bench + 40), screen.profOver / 1e6 / (o.bench + 40)));
            screen.still = true;
            synchronized (st) { f = st.world.cur; }
        }
        screen.paintScene(img, o.dpi, o.w, o.h, -1);
        overlay(app, img, o, null);
        write(img, o.out);
        int cyc = 0;
        StringBuilder names = new StringBuilder();
        for (WorldModel.Sys s : f.systems) if (s.kind.equals("cyclone")) { cyc++; names.append(names.length() > 0 ? ", " : "").append(s.name); }
        System.out.println(String.format(java.util.Locale.ROOT,
                "[viewer] world snapshot written: %s (%dx%d, source: %s, layer %s, month %.2f, UTC %.2f, sun %.1f/%.1f, grid %dx%d v%d, %d locations, %d regions, %d systems, %d cyclones%s, selected %s%s, map %.2f ms)",
                new File(o.out).getAbsolutePath(), dw, dh, verified, WorldPainter.LAYER_IDS[wp.layer], f.month, f.utcHour, f.sunLat, f.sunLon, f.cols, f.rows, f.gridVersion,
                wm.locations.size(), wm.regions.size(), f.systems.length, cyc, cyc > 0 ? " (" + names + ")" : "", screen.selId.isEmpty() ? "none" : screen.selId,
                screen.noPreset ? ", no-preset card \"" + screen.noPresetLabel + "\"" : "", screen.lastPaintMs));
        return 0;
    }

    private static void overlay(AppShell app, BufferedImage img, Opts o, Renderer r) {
        Graphics2D g = img.createGraphics();
        g.scale(o.dpi, o.dpi);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        app.paintOverlay(g, o.w, o.h, r);
        g.dispose();
    }

    private static void write(BufferedImage img, String out) throws java.io.IOException {
        File f = new File(out);
        if (f.getAbsoluteFile().getParentFile() != null) f.getAbsoluteFile().getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
    }
}
