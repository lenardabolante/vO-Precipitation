package precip;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.SwingUtilities;

/**
 * Debug mode, Clouds tab: every cloud type of {@link Model#CLOUD_TYPES}, any seed, anywhere in its
 * life. The big picture is one {@link CloudPainter} render of the cloud the built-in generator makes
 * for the seed ({@link FakeSim#debugCloud}); the strip under it shows the forming steps over the
 * dissipating steps of the same cloud, and the two export buttons write those pictures as PNGs
 * ({@link DebugExport}).
 * <p>
 * Renders are slow (tens of milliseconds), so they are made on one background thread and kept in a
 * small cache; the previous picture stays up until the next is ready, and the controls never wait.
 */
final class DebugClouds extends DebugScreen.Tab {
    /** Where a click on a stage chip puts the life: the middle of forming, growing, mature, precipitating, dissipating. */
    static final double[] STAGE_AT = {0.05, 0.2, 0.38, 0.6, 0.92};
    static final double[] SPEEDS = {0.25, 0.5, 1, 2, 4, 8};
    /** Seconds one whole life takes at speed 1. */
    static final double CYCLE_S = 40;

    private final DebugScreen ds;
    int type;
    double life = DebugExport.MATURE;
    boolean playing;
    int speedIdx = 2, light;
    boolean veil = true;

    // ---- renders
    private final Map<String, Object> cache = new LinkedHashMap<>(64, 0.75f, true);
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private static final Object NOTHING = new Object();
    private ExecutorService worker;
    private volatile String wantMain = "", wantStrip = "";
    private CloudPainter.Image shown;
    private double shownScale, shownMinX, shownMinY;
    private String shownKey = "";
    private boolean exporting;
    private Rectangle2D.Double picRect = new Rectangle2D.Double(), stripRect = new Rectangle2D.Double();
    private final Rectangle2D.Double[] cells = new Rectangle2D.Double[DebugExport.STEPS];
    private final CloudPainter.Scratch scratch = new CloudPainter.Scratch();

    DebugClouds(DebugScreen ds) { this.ds = ds; }

    String typeName() { return Model.CLOUD_TYPES[type]; }

    double tod() { return DebugScreen.LIGHT_TOD[light]; }

    /** The cloud on show. */
    Model.Cloud cloud() { return FakeSim.debugCloud(typeName(), ds.seed, life); }

    @Override void leave() { playing = false; }

    @Override void dispose() {
        ExecutorService w = worker;
        worker = null;
        if (w != null) w.shutdownNow();
        synchronized (cache) { cache.clear(); pending.clear(); }
        shown = null;
    }

    @Override void option(String key, String value) {
        switch (key) {
            case "type" -> type = Model.cloudKind(value);
            case "life" -> life = Model.clamp(Double.parseDouble(value), 0, 1);
            case "stage" -> life = STAGE_AT[Model.stageIndex(value)];
            case "light" -> light = Math.max(0, java.util.Arrays.asList("day", "dusk", "night").indexOf(value));
            case "veil" -> veil = !value.equals("off");
            case "export" -> {
                // snapshots: write this cloud's sequence into the given folder right now (the button does it on the file thread)
                try {
                    List<File> files = DebugExport.exportOne(typeName(), ds.seed, tod(), new File(value));
                    System.out.println("[viewer] Debug export: " + files.size() + " pictures in " + files.get(0).getParentFile().getAbsolutePath());
                } catch (java.io.IOException ex) {
                    System.err.println("[viewer] Debug export failed: " + ex.getMessage());
                }
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ cache and worker

    private Object cached(String key) {
        synchronized (cache) { return cache.get(key); }
    }

    private void store(String key, Object v) {
        synchronized (cache) {
            cache.put(key, v == null ? NOTHING : v);
            pending.remove(key);
            // the newest two dozen renders are kept (a sequence of thirteen counts as one)
            java.util.Iterator<String> it = cache.keySet().iterator();
            while (cache.size() > 24 && it.hasNext()) { it.next(); it.remove(); }
        }
    }

    private void submit(String key, boolean main, java.util.function.Supplier<Object> job) {
        synchronized (cache) {
            if (pending.contains(key)) return;
            pending.add(key);
        }
        if (worker == null) worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "debug-clouds");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        worker.execute(() -> {
            // a render nobody is waiting for any more (the slider moved on) is dropped
            if (!key.equals(main ? wantMain : wantStrip)) { synchronized (cache) { pending.remove(key); } return; }
            Object v;
            try {
                v = job.get();
            } catch (RuntimeException ex) {
                System.err.println("[viewer] Debug clouds: render failed: " + ex);
                v = null;
            }
            store(key, v);
        });
    }

    /** The sequence of the chosen type, seed and light (null until it has been rendered). */
    DebugExport.Frames frames() {
        final String ty = typeName();
        final long seed = ds.seed;
        final double tod = tod();
        String key = "S|" + ty + "|" + seed + "|" + light + "|" + CloudPainter.quality + (Advanced.cur.simpleClouds ? "s" : "");
        wantStrip = key;
        Object v = cached(key);
        if (v instanceof DebugExport.Frames f) return f;
        if (ds.still) {
            DebugExport.Frames f = DebugExport.frames(ty, seed, tod, scratch);
            store(key, f);
            return f;
        }
        submit(key, false, () -> DebugExport.frames(ty, seed, tod, new CloudPainter.Scratch()));
        return null;
    }

    // ------------------------------------------------------------------ the picture

    @Override void scene(Graphics2D g, BufferedImage buf, double scale, Rectangle2D.Double r, double dt) {
        if (playing && dt > 0) {
            life += dt * SPEEDS[speedIdx] / CYCLE_S;
            if (life >= 1) life -= 1;
        }
        Rectangle2D.Double m = DebugScreen.main(r);
        double stripH = Model.clamp(m.height * 0.34, 150, 250);
        picRect = new Rectangle2D.Double(m.x, m.y, m.width, m.height - stripH - 8);
        stripRect = new Rectangle2D.Double(m.x, m.y + m.height - stripH, m.width, stripH);
        Color[] sky = DebugExport.skyOf(tod());

        // ---- the big picture
        Shape clip = g.getClip();
        g.setPaint(new java.awt.GradientPaint(0, (float) picRect.y, sky[0], 0, (float) (picRect.y + picRect.height), sky[1]));
        g.fill(new RoundRectangle2D.Double(picRect.x, picRect.y, picRect.width, picRect.height, 10, 10));
        final String ty = typeName();
        final long seed = ds.seed;
        final double tod = tod(), lifeQ = Math.round(life * 240) / 240.0;
        final boolean withVeil = veil;
        double[] b = DebugExport.box(ty, seed);
        double extra = withVeil ? 150 : 0;
        double bw = b[2] - b[0], bh = b[3] - b[1] + extra;
        // device pixels per world unit: the whole life of the cloud fits the picture
        final double cs = Math.round(Math.min(2.2 * scale, Math.min((picRect.width - 24) * scale / bw, (picRect.height - 24) * scale / bh)) * 64) / 64.0;
        String key = "M|" + ty + "|" + seed + "|" + lifeQ + "|" + light + "|" + CloudPainter.quality + (Advanced.cur.simpleClouds ? "s" : "") + "|" + cs + "|" + withVeil;
        wantMain = key;
        Object v = cached(key);
        if (v == null && ds.still) {
            v = DebugExport.render(FakeSim.debugCloud(ty, seed, lifeQ), tod, cs, withVeil, scratch);
            store(key, v);
            v = v == null ? NOTHING : v;
        }
        if (v == null) submit(key, true, () -> DebugExport.render(FakeSim.debugCloud(ty, seed, lifeQ), tod, cs, withVeil, new CloudPainter.Scratch()));
        else if (!key.equals(shownKey)) {
            shown = v instanceof CloudPainter.Image im ? im : null;
            shownKey = key;
            shownScale = cs;
            shownMinX = b[0] - (picRect.width * scale / cs - bw) / 2;
            shownMinY = b[1] - (picRect.height * scale / cs - bh) / 2;
        }
        if (shown != null) {
            Graphics2D d = buf.createGraphics();
            d.setClip(new Rectangle2D.Double(picRect.x * scale, picRect.y * scale, picRect.width * scale, picRect.height * scale));
            int ox = (int) Math.round(picRect.x * scale - shownMinX * shownScale), oy = (int) Math.round(picRect.y * scale - shownMinY * shownScale);
            if (shown.veil != null) {
                d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                double k = shownScale / shown.vScale;
                int vx = (int) Math.round(ox + shown.vRelX * shownScale), vy = (int) Math.round(oy + shown.vRelY * shownScale);
                d.drawImage(shown.veil, vx, vy, (int) Math.round(vx + shown.vw * k), (int) Math.round(vy + shown.vh * k), 0, 0, shown.vw, shown.vh, null);
            }
            d.drawImage(shown.img, (int) Math.round(ox + shown.relX * shownScale), (int) Math.round(oy + shown.relY * shownScale), null);
            d.dispose();
        } else if (key.equals(shownKey)) {
            g.setFont(Ui.F_MED);
            g.setColor(new Color(255, 255, 255, 200));
            String msg = "not formed yet — nothing to draw at this point of the life";
            g.drawString(msg, (float) (picRect.x + (picRect.width - g.getFontMetrics().stringWidth(msg)) / 2), (float) (picRect.y + picRect.height / 2));
        }
        g.setClip(clip);

        // ---- the strip: forming over dissipating, the mature cloud beside them
        DebugExport.Frames f = frames();
        int n = DebugExport.FORMING.length;
        double gap = 4, matW = Math.min(stripRect.width * 0.22, stripRect.height * 1.25), cw = (stripRect.width - matW - gap * n) / n, ch = (stripRect.height - gap) / 2;
        for (int i = 0; i < DebugExport.STEPS; i++) {
            Rectangle2D.Double c;
            if (i < n) c = new Rectangle2D.Double(stripRect.x + i * (cw + gap), stripRect.y, cw, ch);
            else if (i == n) c = new Rectangle2D.Double(stripRect.x + n * (cw + gap), stripRect.y, matW, stripRect.height);
            else c = new Rectangle2D.Double(stripRect.x + (i - n - 1) * (cw + gap), stripRect.y + ch + gap, cw, ch);
            cells[i] = c;
            g.setPaint(new java.awt.GradientPaint(0, (float) c.y, sky[0], 0, (float) (c.y + c.height), sky[1]));
            g.fill(new RoundRectangle2D.Double(c.x, c.y, c.width, c.height, 8, 8));
            if (f == null) continue;
            DebugExport.Step s = f.steps[i];
            double k = Math.min((c.width - 6) / f.cw, (c.height - 22) / f.ch);
            double iw = f.cw * k, ih = f.ch * k, ix = c.x + (c.width - iw) / 2, iy = c.y + 2 + (c.height - 22 - ih) / 2;
            Object oh = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(s.img, (int) Math.round(ix), (int) Math.round(iy), (int) Math.round(iw), (int) Math.round(ih), null);
            if (oh != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oh);
        }
    }

    // ------------------------------------------------------------------ controls

    private static String cap(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    void setLife(double v) { life = Model.clamp(v, 0, 1); }

    @Override void key(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_SPACE, KeyEvent.VK_P -> playing = !playing;
            case KeyEvent.VK_LEFT -> setLife(life - (e.isShiftDown() ? 0.1 : 0.01));
            case KeyEvent.VK_RIGHT -> setLife(life + (e.isShiftDown() ? 0.1 : 0.01));
            case KeyEvent.VK_UP -> type = Math.floorMod(type - 1, Model.CLOUD_TYPES.length);
            case KeyEvent.VK_DOWN, KeyEvent.VK_TAB -> type = Math.floorMod(type + 1, Model.CLOUD_TYPES.length);
            case KeyEvent.VK_OPEN_BRACKET -> ds.setSeed(ds.seed - 1);
            case KeyEvent.VK_CLOSE_BRACKET -> ds.setSeed(ds.seed + 1);
            case KeyEvent.VK_R -> ds.randomSeed();
            case KeyEvent.VK_HOME -> setLife(0);
            case KeyEvent.VK_END -> setLife(1);
            default -> { }
        }
    }

    @Override void chrome(Ui ui, Rectangle2D.Double r) {
        Model.Cloud c = cloud();
        ui.stroke(picRect.x, picRect.y, picRect.width, picRect.height, 10, new Color(120, 180, 255, 80), 1f);

        // ---- readout, in the corner of the picture
        boolean rains = c.kind != Model.CIRRUS && c.kind != Model.ALTOCUMULUS;
        String[] lines = {
                c.name + "   ·   seed " + c.seed,
                String.format(Locale.ROOT, "%s   ·   life %.0f %%   ·   form %.2f%s", c.stage, life * 100, c.form, Model.anvilKind(c.kind) ? String.format(Locale.ROOT, "   ·   anvil %.2f", c.anvil) : ""),
                String.format(Locale.ROOT, "precipitation: %s%s   ·   %d puffs   ·   %.0f x %.0f px", c.precipType,
                        c.precipRate > 0 ? String.format(Locale.ROOT, " %.2f", c.precipRate) : "", c.puffs.length, c.w, c.h),
                rains ? "" : cap(c.type) + " never rains: it has no precipitating stage."};
        double ty = picRect.y + 20;
        for (int k = 0; k < lines.length; k++) {
            if (lines[k].isEmpty()) continue;
            ui.g.setFont(k == 0 ? Ui.F_MEDB : Ui.F_NORM);
            HurricanePainter.shadowText(ui.g, ui.fit(lines[k], k == 0 ? Ui.F_MEDB : Ui.F_NORM, picRect.width - 24), picRect.x + 12, ty, k == 0 ? Color.WHITE : new Color(232, 240, 250));
            ty += 17;
        }
        if (!shownKey.equals(wantMain)) ui.textRight("rendering…", picRect.x + picRect.width - 12, picRect.y + 20, Ui.F_SMALL, new Color(255, 255, 255, 190));

        // ---- strip captions and clicks
        DebugExport.Frames f = frames();
        for (int i = 0; i < DebugExport.STEPS; i++) {
            Rectangle2D.Double cell = cells[i];
            if (cell == null) continue;
            int n = DebugExport.FORMING.length;
            double lf = i < n ? DebugExport.FORMING[i] : i == n ? DebugExport.MATURE : DebugExport.DISSIPATING[i - n - 1];
            boolean cur = Math.abs(lf - life) < 0.0125;
            ui.stroke(cell.x, cell.y, cell.width, cell.height, 8, cur ? Ui.ACCENT : new Color(255, 255, 255, 40), cur ? 1.6f : 1f);
            String head = i == 0 ? "FORMING" : i == n ? "MATURE" : i == n + 1 ? "DISSIPATING" : null;
            if (head != null) {
                // (on a dark tab so it reads over a white anvil too)
                ui.fill(cell.x + 3, cell.y + 3, ui.width(head, DebugScreen.F_LABEL) + 8, 15, 6, new Color(6, 10, 20, 150));
                ui.text(head, cell.x + 7, cell.y + 14.5, DebugScreen.F_LABEL, Ui.ACCENT);
            }
            String capt = f == null ? "…" : String.format(Locale.ROOT, cell.width < 84 ? "%.0f %%" : "%.0f %%  %s", lf * 100, f.steps[i].cloud.stage);
            DebugScreen.caption(ui, capt, cell);
            final double to = lf;
            ui.hit(cell.x, cell.y, cell.width, cell.height, () -> { playing = false; setLife(to); });
        }

        // ---- the control column
        double[] s = DebugScreen.side(ui, r);
        double x = s[0], y = s[1], w = s[2];
        DebugScreen.label(ui, "CLOUD TYPE", x, y);
        y += 16;
        double bw = (w - 12) / 3;
        for (int k = 0; k < Model.CLOUD_TYPES.length; k++) {
            final int idx = k;
            DebugScreen.pick(ui, cap(Model.CLOUD_TYPES[k]), k == type, x + (k % 3) * (bw + 6), y + (k / 3) * 32, bw, 28, () -> type = idx);
        }
        y += 3 * 32 + 6;
        y += ds.seedRow(ui, "SEED  (the same seed is always the same cloud)", x, y, w) + 8;

        DebugScreen.label(ui, String.format(Locale.ROOT, "LIFE   %.0f %%   ·   %s", life * 100, c.stage), x, y);
        y += 14;
        ui.slider(life, x, y, w, v -> { playing = false; life = v; });
        y += 26;
        for (int k = 0; k < Model.STAGES.length; k++) {
            final int idx = k;
            boolean none = k == 3 && !rains;
            double cw = k < 3 ? (w - 12) / 3 : (w - 6) / 2, cx = k < 3 ? x + k * (cw + 6) : x + (k - 3) * (cw + 6), cy = k < 3 ? y : y + 30;
            ui.button(Model.STAGES[k], cx, cy, cw, 26, Model.STAGES[k].equals(c.stage) ? Ui.PRIMARY : Ui.NORMAL, !none, false, () -> { playing = false; life = STAGE_AT[idx]; });
        }
        y += 60 + 6;
        ui.button(playing ? "Pause" : "Play", x, y, 76, 30, playing ? Ui.PRIMARY : Ui.NORMAL, true, false, () -> playing = !playing);
        String[] sp = new String[SPEEDS.length];
        for (int k = 0; k < sp.length; k++) sp[k] = SPEEDS[k] == 0.25 ? "¼×" : SPEEDS[k] == 0.5 ? "½×" : (long) SPEEDS[k] + "×";
        ui.seg(sp, speedIdx, x + 82, y, w - 82, 30, k -> speedIdx = k);
        y += 38;
        DebugScreen.label(ui, "LIGHT", x, y);
        ui.textRight("rain shaft", x + w - 54, y + 11, Ui.F_SMALL, Ui.DIM);
        ui.toggle(veil, x + w - 46, y - 6, () -> veil = !veil);
        y += 20;
        ui.seg(DebugScreen.LIGHTS, light, x, y, w, 30, k -> light = k);
        y += 38;
        DebugScreen.label(ui, "EXPORT PNG  (forming + mature + dissipating)", x, y);
        y += 16;
        double hw = (w - 6) / 2;
        ui.button("This cloud", x, y, hw, 32, Ui.PRIMARY, !exporting, false, () -> export(false));
        ui.button("All 9 types", x + hw + 6, y, hw, 32, Ui.NORMAL, !exporting, false, () -> export(true));
        y += 40;
        if (y + 30 < r.y + r.height - 8) {
            ui.textFit("to screenshots/debug/  ·  cloud quality " + Settings.QUALITY_NAMES[Math.max(0, Math.min(2, CloudPainter.quality))], x, y + 4, w, Ui.F_SMALL, Ui.FAINT);
            ui.textFit("Space play · ← → life · ↑ ↓ type · [ ] seed", x, y + 19, w, Ui.F_SMALL, Ui.FAINT);
        }
    }

    /** Write the PNG sequence(s) on the shell's file thread and say where they went. */
    void export(boolean all) {
        if (exporting) return;
        exporting = true;
        final String ty = typeName();
        final long seed = ds.seed;
        final double tod = tod();
        final File dir = DebugExport.folder(ds.app.root);
        ds.app.toast(all ? "Exporting all nine cloud types…" : "Exporting " + ty + ", seed " + seed + "…");
        ds.app.io.execute(() -> {
            String msg;
            try {
                List<File> files = all ? DebugExport.exportAll(seed, tod, dir, t -> SwingUtilities.invokeLater(() -> ds.app.toast("Exporting " + t + "…")))
                        : DebugExport.exportOne(ty, seed, tod, dir);
                msg = "Exported " + files.size() + " pictures to screenshots/debug/" + (all ? "" : files.get(0).getParentFile().getName());
            } catch (Exception | OutOfMemoryError ex) {
                msg = "Export failed: " + ex.getMessage();
            }
            final String m = msg;
            SwingUtilities.invokeLater(() -> { exporting = false; ds.app.toast(m); });
        });
    }
}
