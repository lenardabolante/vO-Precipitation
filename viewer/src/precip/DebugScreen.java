package precip;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import javax.imageio.ImageIO;

/**
 * The hidden <b>Debug</b> game mode (PLAN Phase I): a set of tabs that show everything the game can
 * draw, one thing at a time, on the game's own painters ({@link CloudPainter}, {@link BuildingPainter},
 * {@link CarPainter}, {@link TornadoPainter}, the world {@link Renderer}, the hurricane and world-map
 * screens). Nothing here is a re-drawn imitation: what a tab shows is what the game shows.
 * <p>
 * Viewer only. There is no simulation process, no save slot and no protocol message of its own: the
 * world tabs step small built-in {@link FakeSim} worlds from {@link #paintScene} (on the event
 * thread, 30 steps a second), so there is no thread to stop either. Esc returns to the main menu
 * like the lesson modes. The mode is reached from the menu once the cloud in its upper left has been
 * clicked ten times ({@link AppShell#debugCloudClicked}).
 * <p>
 * One seed ({@link #seed}) is shared by the tabs: it is the cloud generator's seed on the Clouds
 * tab, the per-building variation on the line-ups and the world seed of the world tabs.
 */
final class DebugScreen extends Screen implements ViewerPanel.Scene {
    static final int CLOUDS = 0, LIGHTNING = 1, BUILDINGS = 2, CARS = 3, WEATHER = 4, WORLD = 5, HURRICANE = 6, WORLDMAP = 7, FLOOD = 8;
    static final String[] TABS = {"Clouds", "Lightning", "Buildings", "Cars", "Weather", "World", "Hurricane", "World map", "Flood"};
    static final String[] TAB_IDS = {"clouds", "lightning", "buildings", "cars", "weather", "world", "hurricane", "worldmap", "flood"};
    /** Height of the tab bar. */
    static final double TOP = 50;
    static final Color BG = new Color(7, 11, 20);
    /** Width of the control column on the right of most tabs. */
    static final double SIDE = 296;
    static final String[] LIGHTS = {"Day", "Dusk", "Night"};
    /** Time of day (0..1, 0.5 = noon) of the three light choices. */
    static final double[] LIGHT_TOD = {0.45, 0.76, 0.02};
    static final long MAX_SEED = 999_999_999L;

    /** The frame source of the mode: there is none (no process, no thread). Stopping it releases the mode's caches. */
    static final class NoSource implements FrameSource {
        volatile Runnable onStop;

        @Override public void start(ViewerState state) { }

        @Override public void send(String json) { }

        @Override public void stop() {
            Runnable r = onStop;
            if (r != null) r.run();
        }

        @Override public String describe() { return "Debug (built-in)"; }
    }

    /** One tab. Its picture is drawn into the frame buffer ({@link #scene}), its controls on top ({@link #chrome}). */
    abstract static class Tab {
        /** The tab was selected. */
        void enter() { }

        /** Another tab was selected: let go of worlds and caches. */
        void leave() { }

        /** Leaving the mode. */
        void dispose() { leave(); }

        /**
         * Draw the picture. {@code g} is scaled to logical pixels ({@code scale} device px each); {@code r} is the tab's area
         * in logical window coordinates; {@code dt} = seconds since the last frame (0 in a still picture).
         */
        abstract void scene(Graphics2D g, BufferedImage buf, double scale, Rectangle2D.Double r, double dt);

        abstract void chrome(Ui ui, Rectangle2D.Double r);

        void key(KeyEvent e) { }

        void wheel(int x, int y, double rotation) { }

        void mouse(int type, int x, int y, int button) { }

        /** Snapshots and tests: put the tab in a given state ({@code key=value} pairs). */
        void option(String key, String value) { }
    }

    final AppShell app;
    final GameSession gs;
    final Tab[] tabs;
    final DebugClouds clouds;
    final DebugLineup.Buildings buildings;
    final DebugLineup.Cars cars;
    final DebugWorlds.LightningTab lightning;
    final DebugWorlds.WeatherTab weather;
    final DebugWorlds.WorldTab world;
    final DebugWorlds.HurricaneTab hurricane;
    final DebugWorlds.WorldMapTab worldMap;
    /** v2.4 (Phase L): standing water, storm drains and flooding. */
    final DebugFlood.FloodTab flood;
    int tab;
    /** Still pictures (snapshots, tests): everything is rendered at once and nothing eases. */
    boolean still;
    /** The seed shared by the tabs. */
    long seed = 1234;
    final Ui.TextField seedField = new Ui.TextField("1234", 9, true);
    boolean seedFocus;
    /** The last exception a tab threw while painting (shown in its place; the tests want it null). */
    String lastError;
    /** Seconds the mode has been painting (the line-ups' moving parts). */
    double anim;
    private boolean disposed;
    private Rectangle2D.Double area = new Rectangle2D.Double(0, TOP, 1040, 520);

    DebugScreen(AppShell app, GameSession gs) {
        this.app = app;
        this.gs = gs;
        clouds = new DebugClouds(this);
        lightning = new DebugWorlds.LightningTab(this);
        buildings = new DebugLineup.Buildings(this);
        cars = new DebugLineup.Cars(this);
        weather = new DebugWorlds.WeatherTab(this);
        world = new DebugWorlds.WorldTab(this);
        hurricane = new DebugWorlds.HurricaneTab(this);
        worldMap = new DebugWorlds.WorldMapTab(this);
        flood = new DebugFlood.FloodTab(this);
        tabs = new Tab[]{clouds, lightning, buildings, cars, weather, world, hurricane, worldMap, flood};
        if (gs != null && gs.source instanceof NoSource ns) ns.onStop = this::dispose;
        tabs[tab].enter();
    }

    @Override boolean dims() { return false; }

    /** The area under the tab bar. */
    static Rectangle2D.Double content(int w, int h) { return new Rectangle2D.Double(0, TOP, w, Math.max(120, h - TOP)); }

    void setTab(int k) {
        k = Math.max(0, Math.min(tabs.length - 1, k));
        if (k == tab) return;
        tabs[tab].leave();
        tab = k;
        seedFocus = false;
        lastError = null;
        tabs[tab].enter();
    }

    void setSeed(long s) {
        seed = Math.max(0, Math.min(MAX_SEED, s));
        seedField.set(String.valueOf(seed));
    }

    void randomSeed() { setSeed(ThreadLocalRandom.current().nextLong(1, MAX_SEED + 1)); }

    /** Let go of every world, thread and cache. Safe to call twice and from any thread once the panel is gone. */
    void dispose() {
        if (disposed) return;
        disposed = true;
        for (Tab t : tabs) {
            try { t.dispose(); } catch (RuntimeException ignored) { }
        }
    }

    // ------------------------------------------------------------------ input

    @Override void key(KeyEvent e) {
        int c = e.getKeyCode();
        if (c == KeyEvent.VK_ESCAPE) { app.leaveNow(); return; }
        if (seedFocus) {
            if (c == KeyEvent.VK_ENTER || c == KeyEvent.VK_TAB) { seedFocus = false; setSeed(seed); return; }
            if (seedField.key(e)) readSeedField();
            return;
        }
        if (c >= KeyEvent.VK_1 && c <= KeyEvent.VK_9 && !e.isControlDown() && !e.isAltDown()) { setTab(c - KeyEvent.VK_1); return; }
        tabs[tab].key(e);
    }

    @Override void typed(char c) {
        if (!seedFocus) return;
        seedField.typed(c);
        readSeedField();
    }

    private void readSeedField() {
        String v = seedField.value();
        if (v.isEmpty()) return;                       // an empty box keeps the seed until a digit is typed
        try { seed = Math.min(MAX_SEED, Long.parseLong(v)); } catch (NumberFormatException ignored) { }
    }

    @Override void wheel(int x, int y, double rotation) { tabs[tab].wheel(x, y, rotation); }

    @Override void mouse(int type, int x, int y, int button) {
        if (type == MouseEvent.MOUSE_PRESSED && seedFocus) { seedFocus = false; setSeed(seed); }
        tabs[tab].mouse(type, x, y, button);
    }

    // ------------------------------------------------------------------ the picture (ViewerPanel.Scene)

    @Override public void paintScene(BufferedImage buf, double scale, int w, int h, double dt) {
        Graphics2D g = buf.createGraphics();
        try {
            hints(g);
            g.scale(scale, scale);
            g.setColor(BG);
            g.fillRect(0, 0, w + 1, h + 1);
            if (disposed) return;
            double d = still ? 0 : Math.max(0, dt);
            anim += d;
            area = content(w, h);
            try {
                tabs[tab].scene(g, buf, scale, area, d);
            } catch (RuntimeException ex) {
                problem(ex);
                g.setFont(Ui.F_MED);
                g.setColor(Ui.RED);
                g.drawString("This tab could not be drawn: " + lastError, 24f, (float) (TOP + 40));
            }
        } finally {
            g.dispose();
        }
    }

    private void problem(RuntimeException ex) {
        String msg = String.valueOf(ex);
        if (!msg.equals(lastError)) {
            System.err.println("[viewer] Debug, tab " + TAB_IDS[tab] + ": " + ex);
            ex.printStackTrace();
        }
        lastError = msg;
    }

    static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    /** The sky of a light, as the backdrop of a picture or a cell. */
    static void sky(Graphics2D g, double x, double y, double w, double h, Lit lit) {
        g.setPaint(new GradientPaint(0, (float) y, lit.skyTop, 0, (float) (y + h), lit.skyBot));
        g.fill(new Rectangle2D.Double(x, y, w, h));
    }

    // ------------------------------------------------------------------ chrome

    @Override void paint(Ui ui, int w, int h) {
        ui.text("DEBUG", 16, 31, Ui.F_HEAD, Ui.ACCENT);
        double x0 = 16 + ui.width("DEBUG", Ui.F_HEAD) + 16, mw = 70;
        ui.seg(TABS, tab, x0, 9, w - x0 - mw - 22, 32, this::setTab);
        ui.button("Menu", w - mw - 12, 10, mw, 30, Ui.NORMAL, true, false, app::leaveNow);
        if (disposed) return;
        area = content(w, h);
        try {
            tabs[tab].chrome(ui, area);
        } catch (RuntimeException ex) {
            problem(ex);
        }
    }

    /** The column of controls on the right of a tab: draws its panel and returns {x, y, w} of the inside. */
    static double[] side(Ui ui, Rectangle2D.Double r) {
        double x = r.x + r.width - SIDE - 12, y = r.y + 8, h = r.height - 20;
        ui.fill(x, y, SIDE, h, 14, new Color(10, 14, 24, 236));
        ui.stroke(x, y, SIDE, h, 14, new Color(120, 180, 255, 70), 1f);
        return new double[]{x + 12, y + 10, SIDE - 24};
    }

    /** The part of a tab left of the control column. */
    static Rectangle2D.Double main(Rectangle2D.Double r) {
        return new Rectangle2D.Double(r.x + 12, r.y + 8, Math.max(120, r.width - SIDE - 36), Math.max(80, r.height - 20));
    }

    static final Font F_LABEL = Ui.F_SMALL.deriveFont(Font.BOLD);

    static void label(Ui ui, String s, double x, double y) { ui.text(s, x, y + 10, F_LABEL, Ui.ACCENT); }

    /** A button that shows a choice as taken. */
    static void pick(Ui ui, String s, boolean on, double x, double y, double w, double h, Runnable r) {
        boolean hov = ui.hover(x, y, w, h);
        ui.fill(x, y, w, h, 9, on ? new Color(52, 104, 196, 245) : hov ? new Color(52, 68, 104, 245) : new Color(34, 44, 68, 240));
        ui.stroke(x, y, w, h, 9, on ? Ui.ACCENT : new Color(255, 255, 255, 34), on ? 1.3f : 1f);
        // a long name ("Cumulonimbus" in a third of the column) gets the smaller face instead of an ellipsis
        Font f = Ui.F_BOLD;
        if (ui.width(s, f) > w - 8) f = F_LABEL;
        if (ui.width(s, f) > w - 3) f = Ui.F_SMALL.deriveFont(10f);
        ui.g.setFont(f);
        java.awt.FontMetrics fm = ui.g.getFontMetrics();
        ui.textCenter(ui.fit(s, f, w - 4), x + w / 2, y + (h + fm.getAscent() - fm.getDescent()) / 2.0, f, on ? Color.WHITE : Ui.TEXT);
        ui.hit(x, y, w, h, r);
    }

    /** A caption at the bottom of a picture cell, on a dark band so it reads on any sky. */
    static void caption(Ui ui, String s, Rectangle2D.Double c) {
        java.awt.Shape clip = ui.g.getClip();
        ui.g.clip(new java.awt.geom.RoundRectangle2D.Double(c.x, c.y, c.width, c.height, 8, 8));
        ui.g.setColor(new Color(6, 10, 20, 150));
        ui.g.fill(new Rectangle2D.Double(c.x, c.y + c.height - 17, c.width, 17));
        ui.g.setClip(clip);
        ui.textCenter(ui.fit(s, Ui.F_SMALL, c.width - 6), c.x + c.width / 2, c.y + c.height - 5, Ui.F_SMALL, new Color(240, 245, 252));
    }

    /** A labelled on / off switch on one line; returns the height used. */
    static double toggleRow(Ui ui, String s, boolean on, double x, double y, double w, Runnable r) {
        ui.text(s, x, y + 17, Ui.F_NORM, Ui.TEXT);
        ui.toggle(on, x + w - 46, y, r);
        return 28;
    }

    /** The shared seed row: minus, the number (typed), plus, random. Returns the height used. */
    double seedRow(Ui ui, String what, double x, double y, double w) {
        label(ui, what, x, y);
        y += 16;
        double bw = 28, rw = 70, fw = w - 2 * bw - rw - 18;
        ui.button("−", x, y, bw, 30, Ui.NORMAL, seed > 0, false, () -> setSeed(seed - 1));
        ui.field(seedField, "seed", x + bw + 6, y, fw, 30, seedFocus, () -> seedFocus = true);
        ui.button("+", x + bw + fw + 12, y, bw, 30, Ui.NORMAL, seed < MAX_SEED, false, () -> setSeed(seed + 1));
        ui.button("Random", x + w - rw, y, rw, 30, Ui.NORMAL, true, false, this::randomSeed);
        return 16 + 30;
    }

    // ------------------------------------------------------------------ snapshots

    /**
     * {@code --ui-snapshot debug-<tab>[:key=value,...]}: one tab as a PNG, drawn by the code the window uses.
     * Never writes settings; touches no save.
     */
    static int snapshot(File root, Settings settings, String spec, String out, int w, int h, double dpi) throws Exception {
        settings.persist = false;
        String id = spec, opts = "";
        int c = spec.indexOf(':');
        if (c >= 0) { id = spec.substring(0, c); opts = spec.substring(c + 1); }
        int ti = java.util.Arrays.asList(TAB_IDS).indexOf(id);
        if (ti < 0) {
            System.err.println("[viewer] unknown --ui-snapshot screen \"debug-" + spec + "\". Debug tabs: " + String.join(", ", TAB_IDS));
            return 2;
        }
        AppShell app = new AppShell(root, settings, new SaveStore(new File(root, "saves")));
        app.applySettings();
        try {
            DebugScreen ds = open(app, true);
            ds.setTab(ti);
            for (Map.Entry<String, String> e : options(opts).entrySet()) {
                if (e.getKey().equals("seed")) ds.setSeed(Long.parseLong(e.getValue()));
                else ds.tabs[ti].option(e.getKey(), e.getValue());
            }
            BufferedImage img = ds.picture(w, h, dpi);
            ds.dispose();
            File f = new File(out);
            if (f.getAbsoluteFile().getParentFile() != null) f.getAbsoluteFile().getParentFile().mkdirs();
            ImageIO.write(img, "png", f);
            System.out.println("[viewer] ui snapshot \"debug-" + spec + "\" written: " + f.getAbsolutePath() + " (" + img.getWidth() + "x" + img.getHeight() + ")"
                    + (ds.lastError != null ? "  [tab error: " + ds.lastError + "]" : ""));
            return ds.lastError == null ? 0 : 1;
        } finally {
            app.io.shutdown();
        }
    }

    /** The mode on a shell that has no window (snapshots, tests): the session and the screen as {@code AppShell.beginMode} makes them. */
    static DebugScreen open(AppShell app, boolean still) {
        GameSession gs = new GameSession(app, null, new NoSource(), false, "Debug");
        gs.mode = "debug";
        app.session = gs;
        DebugScreen ds = new DebugScreen(app, gs);
        ds.still = still;
        app.modeScreen = ds;
        app.push(ds);
        return ds;
    }

    static Map<String, String> options(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String p : s.split(",")) {
            if (p.isBlank()) continue;
            int eq = p.indexOf('=');
            if (eq < 0) m.put(p.trim(), "");
            else m.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        return m;
    }

    /** The whole screen (picture and controls) as an image, the way the window paints it. */
    BufferedImage picture(int w, int h, double dpi) {
        BufferedImage img = new BufferedImage((int) Math.round(w * dpi), (int) Math.round(h * dpi), BufferedImage.TYPE_INT_RGB);
        paintScene(img, dpi, w, h, 0);
        Graphics2D g = img.createGraphics();
        g.scale(dpi, dpi);
        hints(g);
        app.paintOverlay(g, w, h, null);
        g.dispose();
        return img;
    }
}
