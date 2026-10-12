package precip;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Debug mode, the tabs that show a live world: <b>Lightning</b>, <b>Weather</b> and <b>World</b> run
 * small built-in {@link FakeSim} worlds and draw them with the game's real {@link Renderer} (through
 * a {@link ParallelRenderer}, as the game view does); <b>Hurricane</b> and <b>World map</b> are the
 * game's own {@link HurricaneScreen} and {@link WorldScreen} on the built-in {@link FakeHurricane} /
 * {@link FakeWorld}, painted inside the tab.
 * <p>
 * Nothing runs on a thread of its own: every world is stepped from the paint (30 steps per second
 * of frame time), commands go straight into the fake ({@code handle}), and a tab lets go of its
 * world when another tab is chosen.
 */
final class DebugWorlds {
    private DebugWorlds() {}

    static final int WORLD_W = FakeSim.DEFAULT_W, WORLD_H = FakeSim.DEFAULT_H;

    /** The command channel of a screen that is embedded in a tab: straight into the fake, no thread, no process. */
    static final class Pump implements FrameSource {
        private final Consumer<String> handler;
        private final String name;

        Pump(String name, Consumer<String> handler) { this.name = name; this.handler = handler; }

        @Override public void start(ViewerState state) { }

        @Override public void send(String json) { handler.accept(json); }

        @Override public void stop() { }

        @Override public String describe() { return name; }
    }

    /** The look of a Debug world view: the world alone, none of the sandbox's panels. */
    static void style(Renderer r) {
        r.showRightPanel = false; r.showLeftPanel = false; r.showMinimap = false; r.showHelp = false; r.showLegend = false;
        r.showErrorBox = false; r.animatePanels = false; r.decorative = false; r.hudReserveW = 0;
        r.showClimate = false; r.backdropImage = null;
        r.showWind = false; r.showArrows = false; r.showLabels = true;
        r.shadeOn = false;
    }

    /**
     * One view onto a built-in world: the fake, its state, a renderer and a camera. It is drawn into its own image
     * (the size of its rectangle) by the game's renderer and then copied into the frame.
     */
    static final class Pane {
        final FakeSim sim;
        final ViewerState st;
        final ParallelRenderer pr;
        final Camera cam;
        final boolean still, owner;
        /** Frames still to run before the scene is what the tab wants to show (rain has to reach the ground first). */
        int warm;
        /** Puts the camera where the tab wants it; runs once the view has its size (and again after {@link #replace}). */
        Consumer<Pane> placer;
        private boolean placed;
        private BufferedImage img;
        Rectangle2D.Double rect = new Rectangle2D.Double();
        private double acc, animSec;
        private boolean dragging, moved;
        private int dragX, dragY, pressButton;

        Pane(FakeSim sim, boolean still) {
            this.sim = sim;
            this.still = still;
            owner = true;
            st = new ViewerState();
            cam = new Camera();
            st.acceptLine(sim.hello());
            st.acceptLine(sim.step());
            pr = new ParallelRenderer(ParallelRenderer.defaultThreads());
            if (still) pr.shared.setAsync(false);
            style(pr.primary());
        }

        /** A second view of the same world and the same camera with a renderer of its own (the Epilepsy-safe picture). */
        Pane(Pane twin) {
            sim = twin.sim; st = twin.st; cam = twin.cam; still = twin.still;
            owner = false;
            pr = new ParallelRenderer(ParallelRenderer.defaultThreads());
            if (still) pr.shared.setAsync(false);
            style(pr.primary());
        }

        Renderer r() { return pr.primary(); }

        Model.Frame frame() { synchronized (st) { return st.cur; } }

        Model.Hello hello() { synchronized (st) { return st.hello; } }

        /** A command, as the sandbox keys send them. */
        void cmd(String json) {
            if (sim.handle(json)) st.acceptLine(sim.hello());
        }

        void step(int n) {
            for (int k = 0; k < n; k++) st.acceptLine(sim.step());
        }

        /** Step until {@code done} says so (at most {@code max} frames). */
        int stepUntil(java.util.function.Predicate<Model.Frame> done, int max) {
            int k = 0;
            while (k < max && !done.test(frame())) { step(1); k++; }
            return k;
        }

        /** Advance the world by the time of one painted frame. */
        void pump(double dt) {
            if (!owner) return;
            if (warm > 0) {
                int n = still ? warm : Math.min(warm, 30);
                step(n);
                warm -= n;
                return;
            }
            acc += dt;
            int n = 0;
            while (acc >= FakeSim.DT && n < 3) { step(1); acc -= FakeSim.DT; n++; }
            if (acc > FakeSim.DT) acc = 0;                   // a slow frame: do not try to catch up
        }

        void replace() { placed = false; }

        /** Draw the world into {@code r} of the frame. */
        void render(BufferedImage buf, double scale, Rectangle2D.Double r, double dt) {
            int pw = Math.max(64, (int) Math.floor(r.width)), ph = Math.max(64, (int) Math.floor(r.height));
            int dw = (int) Math.ceil(pw * scale), dh = (int) Math.ceil(ph * scale);
            if (img == null || img.getWidth() != dw || img.getHeight() != dh) {
                img = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
                if (owner) placed = false;
            }
            rect = r;
            pr.layout(cam, st, pw, ph);
            if (owner) {
                if (!placed && cam.initialised) {
                    placed = true;
                    if (placer != null) placer.accept(this); else { cam.home(); cam.snap(); }
                }
                cam.update(dt);
                animSec += dt;
            }
            Model.Frame f = frame();
            pr.render(img, scale, 0, 0, pw, ph, st, cam, still ? -1 : System.nanoTime(), still && f != null ? f.t : animSec, "debug");
            Graphics2D g = buf.createGraphics();
            g.drawImage(img, (int) Math.round(r.x * scale), (int) Math.round(r.y * scale), null);
            g.dispose();
        }

        boolean contains(int x, int y) { return rect.contains(x, y); }

        double worldX(int x) { return cam.toWorldX(x - rect.x); }

        double worldY(int y) { return cam.toWorldY(y - rect.y); }

        /** Pan by dragging. Returns true when a left click (press and release without moving) ended inside the view. */
        boolean mouse(int type, int x, int y, int button) {
            switch (type) {
                case MouseEvent.MOUSE_PRESSED -> {
                    dragging = contains(x, y);
                    moved = false;
                    pressButton = button;
                    dragX = x; dragY = y;
                }
                case MouseEvent.MOUSE_DRAGGED -> {
                    if (!dragging) return false;
                    if (Math.abs(x - dragX) + Math.abs(y - dragY) > 3) moved = true;
                    if (moved) { cam.dragBy(x - dragX, y - dragY); dragX = x; dragY = y; }
                }
                case MouseEvent.MOUSE_RELEASED -> {
                    boolean click = dragging && !moved && pressButton == MouseEvent.BUTTON1 && contains(x, y);
                    dragging = false;
                    return click;
                }
                default -> { }
            }
            return false;
        }

        void wheel(int x, int y, double rotation) {
            if (contains(x, y)) cam.zoomAt(x - rect.x, y - rect.y, Math.pow(1.2, -rotation));
        }

        void dispose() { pr.shutdown(); }
    }

    private static String cmd(String name, String key, double v) {
        return String.format(Locale.ROOT, "{\"cmd\":\"%s\",\"%s\":%.3f}", name, key, v);
    }

    /** Height of the caption line above a world picture (the picture itself is never written on: the renderer's own labels live there). */
    static final double CAP = 24;

    /** The picture area of a world tab: the main area less the caption line. */
    static Rectangle2D.Double below(Rectangle2D.Double m) { return new Rectangle2D.Double(m.x, m.y + CAP, m.width, Math.max(60, m.height - CAP)); }

    /** The caption of a world picture, in the line above it: a name in the accent colour, then what is going on. */
    private static void caption(Ui ui, String name, String rest, Rectangle2D.Double pic) {
        double y = pic.y - 8;
        ui.text(name, pic.x + 2, y, Ui.F_BOLD, Ui.ACCENT);
        if (rest != null && !rest.isEmpty()) ui.textFit(rest, pic.x + 2 + ui.width(name, Ui.F_BOLD) + 14, y, pic.width - ui.width(name, Ui.F_BOLD) - 20, Ui.F_NORM, Ui.TEXT);
    }

    // =====================================================================================
    // Lightning

    /**
     * Bolts on demand over a night-time village, with the Epilepsy-safe rendering of the same bolts beside the normal
     * one. A strike is a {@link Main#testBolt} bolt (the {@code --bolt} hook) added to the frame's own list; from there on
     * it is the game's {@code Renderer.drawLightning} and {@link Lightning}. The Epilepsy-safe picture is a second render
     * with the static {@link Renderer#epilepsySafe} switched on for just that render; the player's setting is never touched.
     */
    static final class LightningTab extends DebugScreen.Tab {
        static final double[] INTERVALS = {0.5, 1, 2, 4};
        static final double[] HOURS = {12, 18.3, 0.5};
        private final DebugScreen ds;
        Pane normal, safe;
        int light = 2, intervalIdx = 2;
        boolean auto, free, paused;
        double strength = 0.85;
        /** Bolts struck so far (tests). */
        int strikeCount;
        private long builtSeed = -1;
        private double autoClock;
        private static final class Strike { double[] pts; double alpha; }
        private final List<Strike> strikes = new ArrayList<>();
        private Model.Frame baseFrame;
        private List<Model.Bolt> base = List.of();

        LightningTab(DebugScreen ds) { this.ds = ds; }

        @Override void leave() {
            if (normal != null) normal.dispose();
            if (safe != null) safe.dispose();
            normal = null; safe = null;
            strikes.clear();
            baseFrame = null;
            builtSeed = -1;
        }

        @Override void option(String key, String value) {
            switch (key) {
                case "light" -> { light = Math.max(0, java.util.Arrays.asList("day", "dusk", "night").indexOf(value)); leave(); }
                case "strike" -> pendingStrike = value.isEmpty() ? Double.NaN : Double.parseDouble(value);
                case "free" -> free = !value.equals("off");
                default -> { }
            }
        }

        /** Snapshots: strike here (world x, or the middle of the view) as soon as the world is up. */
        private Double pendingStrike;

        private void build() {
            leave();
            FakeSim sim = new FakeSim(ds.seed, DebugScreen.LIGHT_TOD[light], WORLD_W, WORLD_H);
            normal = new Pane(sim, ds.still);
            normal.warm = 45;
            normal.placer = p -> {
                // the west end of the village, the supercell's base above it: the cloud a bolt comes from and the roofs it lights
                Model.Hello h = p.hello();
                double x = h.w * 0.175, gy = h.terrain.at(x), z = Model.clamp(p.cam.vh / 700.0, p.cam.minZoom(), Camera.MAX_ZOOM);
                p.cam.set(x, gy - p.cam.vh / z * 0.42, z, true);
            };
            safe = new Pane(normal);
            builtSeed = ds.seed;
            paused = false;
        }

        void setLight(int k) {
            light = k;
            if (normal != null) normal.cmd(cmd("hour", "value", HOURS[k]));
        }

        /** A bolt down to the ground at world x: from the base of the cloud above it, or free-standing. */
        void strike(double x) {
            if (normal == null) return;
            Model.Frame f = normal.frame();
            Model.Hello h = normal.hello();
            if (f == null || h == null) return;
            x = Model.clamp(x, 4, h.w - 4);
            Strike s = new Strike();
            s.pts = Main.testBolt(h, free ? new Model.Frame() : f, x, 1).pts;
            s.alpha = strength;
            strikes.add(s);
            strikeCount++;
        }

        private double randomX() {
            Pane p = normal;
            return p.cam.cx + (Math.random() - 0.5) * p.cam.vw / p.cam.zoom * 0.8;
        }

        /** Put the tab's bolts into the frame that is about to be drawn (a copy of its list: the published one is never changed). */
        private void inject() {
            Model.Frame f = normal.frame();
            if (f == null) return;
            if (f != baseFrame) { baseFrame = f; base = f.lightning; }
            if (strikes.isEmpty()) { f.lightning = base; return; }
            List<Model.Bolt> all = new ArrayList<>(base);
            for (Strike s : strikes) {
                Model.Bolt b = new Model.Bolt();
                b.pts = s.pts;
                b.alpha = Model.clamp(s.alpha, 0, 1);
                all.add(b);
            }
            f.lightning = all;
        }

        @Override void scene(Graphics2D g, BufferedImage buf, double scale, Rectangle2D.Double r, double dt) {
            if (normal == null || builtSeed != ds.seed) build();
            normal.pump(dt);
            if (pendingStrike != null && normal.warm == 0) {
                // (the view has no size before its first render: the middle of the view is then the placer's x)
                strike(pendingStrike.isNaN() ? normal.hello().w * 0.175 : pendingStrike);
                pendingStrike = null;
            }
            if (!paused) {
                for (int k = strikes.size() - 1; k >= 0; k--) {
                    strikes.get(k).alpha -= dt * 2.2;          // as fast as the simulation's own bolts fade
                    if (strikes.get(k).alpha <= 0) strikes.remove(k);
                }
                if (auto && normal.warm == 0) {
                    autoClock += dt;
                    if (autoClock >= INTERVALS[intervalIdx]) { autoClock = 0; strike(randomX()); }
                }
            }
            inject();
            Rectangle2D.Double m = below(DebugScreen.main(r));
            double pw = Math.floor((m.width - 8) / 2);
            boolean keep = Renderer.epilepsySafe;
            try {
                Renderer.epilepsySafe = false;
                normal.render(buf, scale, new Rectangle2D.Double(m.x, m.y, pw, m.height), dt);
                Renderer.epilepsySafe = true;
                safe.render(buf, scale, new Rectangle2D.Double(m.x + pw + 8, m.y, pw, m.height), dt);
            } finally {
                Renderer.epilepsySafe = keep;       // whatever happens in a render, the switch is back where the settings put it
            }
        }

        @Override void mouse(int type, int x, int y, int button) {
            if (normal == null) return;
            // either picture: a click strikes at that place, a drag pans both (they share the camera)
            boolean inSafe = safe.contains(x, y);
            Pane p = inSafe ? safe : normal;
            if (type != MouseEvent.MOUSE_PRESSED) p = lastPane != null ? lastPane : p; else lastPane = p;
            if (p.mouse(type, x, y, button)) strike(p.worldX(x));
        }

        private Pane lastPane;

        @Override void wheel(int x, int y, double rotation) {
            if (normal == null) return;
            if (safe.contains(x, y)) safe.wheel(x, y, rotation); else normal.wheel(x, y, rotation);
        }

        @Override void key(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_SPACE, KeyEvent.VK_ENTER, KeyEvent.VK_B -> { if (normal != null) strike(randomX()); }
                case KeyEvent.VK_A -> auto = !auto;
                case KeyEvent.VK_L -> setLight((light + 1) % 3);
                case KeyEvent.VK_P -> togglePause();
                default -> { }
            }
        }

        private void togglePause() {
            paused = !paused;
            if (normal != null) normal.cmd(paused ? "{\"cmd\":\"pause\"}" : "{\"cmd\":\"resume\"}");
        }

        @Override void chrome(Ui ui, Rectangle2D.Double r) {
            if (normal == null) return;
            for (Pane p : new Pane[]{normal, safe}) {
                ui.stroke(p.rect.x, p.rect.y, p.rect.width, p.rect.height, 4, new Color(120, 180, 255, 80), 1f);
                caption(ui, p == normal ? "NORMAL" : "EPILEPSY-SAFE", p == normal ? "local light, shadows behind buildings" : "no light, a dim eased bolt", p.rect);
            }
            if (normal.warm > 0) ui.textCenter("preparing the world…", normal.rect.getCenterX(), normal.rect.getCenterY(), Ui.F_HEAD, Color.WHITE);
            double[] s = DebugScreen.side(ui, r);
            double x = s[0], y = s[1], w = s[2];
            ui.button("Strike", x, y, w, 40, Ui.PRIMARY, true, false, () -> strike(randomX()));
            y += 46;
            ui.textFit("or click in either picture to strike there", x, y + 6, w, Ui.F_SMALL, Ui.DIM);
            y += 20;
            y += DebugScreen.toggleRow(ui, "Repeat automatically", auto, x, y, w, () -> { auto = !auto; autoClock = 0; });
            String[] iv = new String[INTERVALS.length];
            for (int k = 0; k < iv.length; k++) iv[k] = (INTERVALS[k] == Math.rint(INTERVALS[k]) ? String.valueOf((long) INTERVALS[k]) : String.valueOf(INTERVALS[k])) + " s";
            ui.seg(iv, intervalIdx, x, y, w, 28, k -> intervalIdx = k);
            y += 40;
            DebugScreen.label(ui, "TIME OF DAY", x, y);
            y += 16;
            ui.seg(DebugScreen.LIGHTS, light, x, y, w, 30, this::setLight);
            y += 40;
            DebugScreen.label(ui, String.format(Locale.ROOT, "STRENGTH   %.0f %%", strength * 100), x, y);
            y += 14;
            ui.slider(strength, x, y, w, v -> strength = Math.max(0.1, Math.round(v * 20) / 20.0));
            y += 32;
            DebugScreen.label(ui, "ORIGIN", x, y);
            y += 16;
            ui.seg(new String[]{"Cloud above", "Free-standing"}, free ? 1 : 0, x, y, w, 30, k -> free = k == 1);
            y += 40;
            y += ds.seedRow(ui, "WORLD SEED", x, y, w) + 8;
            ui.button(paused ? "Resume" : "Pause", x, y, 90, 30, paused ? Ui.PRIMARY : Ui.NORMAL, true, false, this::togglePause);
            ui.textFit(strikeCount + (strikeCount == 1 ? " bolt" : " bolts") + " struck", x + 100, y + 20, w - 100, Ui.F_NORM, Ui.DIM);
            y += 38;
            if (y + 50 < r.y + r.height - 8) {
                for (String l : ui.wrap("Right: the same bolts as Settings › Graphics › Epilepsy-safe lightning draws them (your setting is "
                        + (ds.app.settings.epilepsySafe ? "on" : "off") + " and is not changed here). Drag to pan, wheel to zoom.", Ui.F_SMALL, w)) {
                    if (y + 14 > r.y + r.height - 8) break;
                    ui.text(l, x, y + 10, Ui.F_SMALL, Ui.FAINT);
                    y += 14;
                }
            }
        }
    }

    // =====================================================================================
    // Weather

    /**
     * The four precipitation kinds, the tornado at every EF level, merging clouds, the supercell, the wind overlays and
     * the ground water objects. Each view but the tornado row is a scripted built-in world set up with the commands the
     * sandbox keys send ({@code temp}, {@code tornado}); the tornado row is {@link TornadoPainter} on six tornadoes whose
     * wind is the middle of each EF band.
     */
    static final class WeatherTab extends DebugScreen.Tab {
        static final String[] VIEWS = {"rain", "snow", "mixed", "graupel", "tornado", "merge", "supercell", "wind", "water", "glass"};
        static final String[] VIEW_NAMES = {"Rain", "Snow", "Mixed", "Graupel", "Tornado EF", "Merging", "Supercell", "Wind", "Ground water", "Rain on glass"};
        static final String[] WATER = {"pond", "puddle", "snowpack"};
        static final String[] TOR_STAGES = {"funnel", "touchdown", "mature", "roping"};
        /** Mid-band gust of EF0 .. EF5 in m/s (bands from 29, 38, 50, 61, 75, 90: {@link FakeSim#efOf}). */
        static final double[] EF_WIND = {33.5, 44, 55.5, 68, 82.5, 96};
        private final DebugScreen ds;
        int view, water, torStage = 2, light;
        boolean wind, arrows, labels = true, legend, frost, paused;
        /** The rain glint in this tab's view: -1 = as Settings > Advanced has it, 0 = dot, 1 = star. The setting itself is not changed here. */
        int glint = -1;
        /** The prevailing wind asked for with the wind buttons, m/s (NaN = the scene's own); given to every world this tab builds. */
        double blow = Double.NaN;
        static final double BLOW_STEP = 4, BLOW_MAX = 40;
        Pane pane;
        private String built = "";
        private String note = "";
        private Rectangle2D.Double torRect = new Rectangle2D.Double();
        private final Rectangle2D.Double[] torCells = new Rectangle2D.Double[6];
        /** The hollow the "Ground water > Puddle" view has poured its pool into: {left ridge, bed, right ridge, rim y} (null = none). */
        double[] hollow;
        /** How full that hollow is poured, of what it holds to its rim. */
        static final double HOLLOW_FILL = 0.6;
        /** The least width and depth (px) of a hollow the view prefers. */
        static final double HOLLOW_MIN_W = 64, HOLLOW_MIN_D = 3;
        /** The six tornadoes of the EF row as drawn last (tests). */
        final Model.Tornado[] tornadoes = new Model.Tornado[6];

        WeatherTab(DebugScreen ds) { this.ds = ds; }

        @Override void leave() {
            if (pane != null) pane.dispose();
            pane = null;
            built = "";
        }

        @Override void option(String key, String value) {
            switch (key) {
                case "view" -> setView(Math.max(0, java.util.Arrays.asList(VIEWS).indexOf(value)));
                case "water" -> water = Math.max(0, java.util.Arrays.asList(WATER).indexOf(value));
                case "stage" -> torStage = Math.max(0, java.util.Arrays.asList(TOR_STAGES).indexOf(value));
                case "light" -> light = Math.max(0, java.util.Arrays.asList("day", "dusk", "night").indexOf(value));
                case "wind" -> wind = !value.equals("off");
                case "arrows" -> arrows = !value.equals("off");
                case "frost" -> frost = !value.equals("off");
                case "glint" -> glint = value.equals("star") ? 1 : value.equals("dot") ? 0 : -1;
                case "blow" -> blow(value.equals("scene") ? Double.NaN : Double.parseDouble(value));
                default -> { }
            }
        }

        /** Set the prevailing wind of the world on show (m/s, positive blows to the right; NaN = leave it as it is). */
        void blow(double to) {
            blow = Double.isNaN(to) ? to : Model.clamp(to, -BLOW_MAX, BLOW_MAX);
            if (pane != null && !Double.isNaN(blow)) pane.cmd(cmd("wind", "delta", blow - pane.sim.windBase()));
        }

        /** Is the rain glint of this view the star right now? */
        boolean glintStar() { return glint < 0 ? ds.app.settings.adv.glintStar : glint == 1; }

        void setView(int k) {
            view = k;
            if (VIEWS[k].equals("wind")) { wind = true; arrows = true; }
        }

        private FakeSim scene(String scene) {
            return new FakeSim(ds.seed, DebugScreen.LIGHT_TOD[light], WORLD_W, WORLD_H, null, null, Double.NaN, Double.NaN, scene);
        }

        /** Bring the world's surface temperature to {@code target} °C with the sandbox's {@code temp} command. */
        private static void temperature(Pane p, double target) {
            Model.Frame f = p.frame();
            if (f == null) return;
            p.cmd(cmd("temp", "delta", target - f.surfaceTemp));
        }

        // (found once per run: a world seed whose first thunderstorm carries hail)
        private static long graupelSeed = -1, graupelCloud = -1;

        /** A built-in world in which a cumulonimbus or supercell is precipitating graupel right away. */
        static long findGraupelSeed() {
            if (graupelSeed >= 0) return graupelSeed;
            for (long s = 1; s <= 160; s++) {
                FakeSim fs = new FakeSim(s, 0.45, WORLD_W, WORLD_H);
                ViewerState st = new ViewerState();
                st.acceptLine(fs.hello());
                st.acceptLine(fs.step());
                st.acceptLine(fs.step());
                for (Model.Cloud c : st.cur.clouds) {
                    // (id 2 is the world's own thunderstorm over the city: in the open, nothing in front of it)
                    if (c.id == 2 && "graupel".equals(c.precipType) && c.kind == Model.CUMULONIMBUS) { graupelSeed = s; graupelCloud = c.id; return s; }
                }
            }
            graupelSeed = 0;
            return 0;
        }

        /**
         * v2.4 standing water for the "Puddle" entry: a stream with the water model has no puddle objects to draw (they are the
         * legacy of v2.3), and the scene's rain falls on its two towns, whose drains take it. So water is poured, through the
         * model's own rain entry, into one hollow of this world: the lowest-lying one that is at least {@code HOLLOW_MIN_W} px
         * wide and {@code HOLLOW_MIN_D} px deep (low ground: the hollows of the mountains lie under snow), or, when there is
         * none, the most visible one as {@code min(depth, 10) x min(width, 200)}. From there on it is the model's pool: a level
         * surface over the hollow, on the wire as a {@code frame.water} body. (All that this tab knows of {@link FakeWater} is in this method and the next.)
         * @return {left ridge, bed, right ridge, rim y} of the hollow, or null when the world has no water model or no hollow
         */
        static double[] pour(FakeWater w) {
            if (w == null) return null;
            double[] best = null, low = null;
            double bestScore = 0;
            for (int k = 0; k < w.basinCount(); k++) {
                double[] b = w.basin(k);
                double width = b[2] - b[0], depth = w.at(b[1]) - b[3];
                if (width <= FakeWater.BUCKET || depth <= 0) continue;        // (narrower than a rain bucket: nothing can be rained into it alone)
                double score = Math.min(depth, 10) * Math.min(width, 200);
                if (score > bestScore) { bestScore = score; best = b; }
                if (width >= HOLLOW_MIN_W && depth >= HOLLOW_MIN_D && (low == null || w.at(b[1]) > w.at(low[1]))) low = b;      // (y grows down)
            }
            if (low != null) best = low;
            if (best == null) return null;
            // rain is tallied in buckets: the bucket strictly inside the hollow that is nearest its bed
            double x = Math.rint(best[1] / FakeWater.BUCKET) * FakeWater.BUCKET;
            if (x <= best[0]) x += FakeWater.BUCKET;
            if (x >= best[2]) x -= FakeWater.BUCKET;
            w.rain(x, HOLLOW_FILL * w.volume(best[0], best[2], best[3], null) / FakeWater.HIT_AREA);
            return best;
        }

        /** Keep the poured pool as it is while the view is open: give it back what soaks away ({@code SOAK} x its wetted width). */
        static void topUp(FakeWater w, double[] hollow, double dt) {
            if (w == null || hollow == null || dt <= 0) return;
            double depth = w.depthAt(hollow[1]);
            if (depth <= 0) return;
            double[] ext = new double[4];
            w.volume(hollow[0], hollow[2], w.at(hollow[1]) - depth, ext);
            double x = Math.rint(hollow[1] / FakeWater.BUCKET) * FakeWater.BUCKET;
            if (x <= hollow[0]) x += FakeWater.BUCKET;
            if (x >= hollow[2]) x -= FakeWater.BUCKET;
            w.rain(x, FakeWater.SOAK * ext[2] * Math.min(dt, 0.1) / FakeWater.HIT_AREA);
        }

        private void build() {
            leave();
            String v = VIEWS[view];
            note = "";
            hollow = null;
            switch (v) {
                case "rain", "snow", "mixed" -> {
                    pane = new Pane(scene("cars"), ds.still);
                    double t = v.equals("rain") ? 18 : v.equals("snow") ? -6 : 3;
                    temperature(pane, t);
                    pane.warm = v.equals("rain") ? 120 : v.equals("mixed") ? 330 : 520;
                    pane.placer = p -> {
                        Model.Hello h = p.hello();
                        double x = h.w * 0.50, gy = h.terrain.at(x), z = Model.clamp(p.cam.vh / 620.0, p.cam.minZoom(), Camera.MAX_ZOOM);
                        p.cam.set(x, gy - p.cam.vh / z * 0.44, z, true);
                    };
                    note = v.equals("rain") ? "Surface 18 °C: the freezing level is far above the cloud, everything falls as rain."
                            : v.equals("snow") ? "Surface −6 °C: snow all the way down, and it settles on the ground and the roofs."
                            : "Surface 3 °C: snow leaves the cloud, turns to sleet at the freezing level (the dashed line) and to rain below it.";
                }
                case "graupel" -> {
                    long gs = findGraupelSeed();
                    pane = new Pane(new FakeSim(gs, DebugScreen.LIGHT_TOD[light], WORLD_W, WORLD_H), ds.still);
                    pane.warm = 170;
                    pane.placer = p -> {
                        Model.Frame f = p.frame();
                        Model.Cloud c = f == null ? null : f.cloud(graupelCloud);
                        double x = c != null ? c.x : p.cam.worldW / 2, gy = p.hello().terrain.at(x), z = Model.clamp(p.cam.vh / 500.0, p.cam.minZoom(), Camera.MAX_ZOOM);
                        p.cam.set(x, gy - p.cam.vh / z * 0.45, z, true);
                    };
                    note = "A thunderstorm with hail in it (world seed " + gs + ", the first one found): graupel pellets fall among the rain and bounce.";
                }
                case "merge" -> {
                    pane = new Pane(scene("merge"), ds.still);
                    pane.placer = p -> {
                        double z = Model.clamp(p.cam.vw / 1500.0, p.cam.minZoom(), Camera.MAX_ZOOM);
                        p.cam.set(p.cam.worldW * 0.33 - 130, 470, z, true);
                    };
                    if (ds.still) {
                        pane.stepUntil(f -> { for (Model.Cloud c : f.clouds) if (c.parts.length > 0 && c.merge >= 0.7) return true; return false; }, 4000);
                        pane.step(40);
                    }
                    note = "A stratus sheet drifts into a cumulus and is absorbed (its flat band then runs through the heap); further east two cumulus become one. Restart to watch again.";
                }
                case "supercell" -> {
                    pane = new Pane(scene("supercell"), ds.still);
                    pane.placer = p -> {
                        double z = Model.clamp(p.cam.vh / 900.0, p.cam.minZoom(), Camera.MAX_ZOOM);
                        p.cam.set(p.cam.worldW * 0.165 + 90, 560, z, true);
                    };
                    if (ds.still) {
                        pane.stepUntil(f -> { for (Model.Tornado t : f.tornadoes) if (t.stage.equals("mature")) return true; return false; }, 4000);
                        pane.step(150);
                    }
                    note = "Wall cloud, overshooting top, anvil, then a tornado that crosses the village (about 20 s in). Cars are thrown, buildings damaged, a scar is left.";
                }
                case "wind" -> {
                    pane = new Pane(new FakeSim(ds.seed, DebugScreen.LIGHT_TOD[light], WORLD_W, WORLD_H), ds.still);
                    pane.warm = 30;
                    note = "The wind field (colour = speed), the arrows the simulation sends (updraft, downdraft, fall, growth) and the streamlines.";
                }
                case "water" -> {
                    pane = new Pane(scene("cars"), ds.still);
                    temperature(pane, frost ? -6 : 2);
                    pane.warm = 200;
                    final String kind = WATER[water];
                    // v2.4: the puddle of this stream is a pool of the water model in a hollow (the puddle objects are not drawn)
                    final double[] pool = kind.equals("puddle") ? pour(pane.sim.water()) : null;
                    hollow = pool;
                    pane.placer = p -> {
                        if (pool != null) {
                            // the hollow from ridge to ridge over about half the picture, its rim on the lower third
                            double z = Model.clamp(p.cam.vw * 0.5 / (pool[2] - pool[0]), 2.0, Camera.MAX_ZOOM);
                            p.cam.set((pool[0] + pool[2]) / 2, pool[3] - p.cam.vh / z * 0.28, z, true);
                            return;
                        }
                        // the one nearest the middle of the world, standing on the lower third of the picture
                        Model.Obj pick = null;
                        synchronized (p.st) {
                            for (Model.Obj o : p.st.objects) if (o.kind.equals(kind) && (pick == null || Math.abs(o.x - p.cam.worldW / 2) < Math.abs(pick.x - p.cam.worldW / 2))) pick = o;
                        }
                        if (pick == null) { p.cam.home(); p.cam.snap(); return; }
                        double z = kind.equals("pond") ? 2.0 : 2.8;
                        p.cam.set(pick.x, pick.y - p.cam.vh / z * 0.28, z, true);
                    };
                    note = pool != null ? "Standing water the v2.4 way: a level pool in a hollow of the ground, from the water model (poured in here and kept topped up; "
                            + "in a game the rain fills it, and it spills over its lower rim). It freezes below −1 °C."
                            : "Ponds ripple in the rain and freeze below −1 °C, puddles grow with the wet ground, snowpacks with the snow on it.";
                }
                case "glass" -> {
                    // steady rain over the city, seen from close by: wet panes and wet cars
                    pane = new Pane(scene("cars"), ds.still);
                    temperature(pane, 12);
                    pane.warm = 240;
                    pane.placer = p -> {
                        Model.Hello h = p.hello();
                        double x = h.w * 0.5;
                        for (Model.Settlement s : h.settlements) if (s.kind.equals("city")) x = (s.x0 + s.x1) / 2;
                        double z = Math.min(Camera.MAX_ZOOM, 4.0);
                        p.cam.set(x, h.terrain.at(x) - p.cam.vh / z * 0.36, z, true);
                    };
                    note = "Beads on the wet panes and cars, and drops running down them. With the wind buttons the beads smear and each running drop leans by its own share "
                            + "of the way from straight down to the wind; a moving car's own speed counts as head wind.";
                }
                default -> note = "The funnel, its turning bands, the debris cloud and the scar, by TornadoPainter. Wind per column is the middle of the EF band.";
            }
            if (pane != null && !Double.isNaN(blow)) pane.cmd(cmd("wind", "delta", blow - pane.sim.windBase()));
            built = key();
        }

        private String key() { return VIEWS[view] + "|" + ds.seed + "|" + light + "|" + (VIEWS[view].equals("water") ? water + "|" + frost : ""); }

        @Override void scene(Graphics2D g, BufferedImage buf, double scale, Rectangle2D.Double r, double dt) {
            if (!key().equals(built)) build();
            Rectangle2D.Double m = DebugScreen.main(r);
            if (pane == null) { tornadoRow(g, buf, scale, m); return; }
            if (hollow != null && !paused && pane.warm == 0) topUp(pane.sim.water(), hollow, dt);
            pane.pump(dt);
            Renderer rr = pane.r();
            rr.showWind = wind; rr.showArrows = arrows; rr.showLabels = labels; rr.showLegend = legend;
            rr.glintShape = glint;
            pane.render(buf, scale, below(m), dt);
        }

        /** The tornado of one EF column at a stage of its life, with the numbers {@code FakeSim.FTornado} gives it there. */
        static Model.Tornado tornado(int ef, int stage, double x, double groundY, double phase) {
            double peak = (EF_WIND[ef] - 22) / 78.0, wMax = 28 + 44 * peak;
            Model.Tornado t = new Model.Tornado();
            t.id = ef + 1;
            t.x = x;
            t.groundY = groundY;
            t.baseY = groundY - 300;
            t.stage = TOR_STAGES[stage];
            switch (stage) {
                case 0 -> { t.reach = 0.62; t.strength = 0.15 * 0.62; t.width = 6 + 12 * 0.62; t.lean = -8; }
                case 1 -> { t.reach = 1; t.strength = 0.15 + (0.62 * peak - 0.15) * 0.5; t.width = 18 + (0.9 * wMax - 18) * 0.5; t.lean = -12; t.debris = 0.35; }
                case 3 -> { t.reach = 0.99; t.strength = peak * 0.62 * 0.5; t.width = 0.9 * wMax * Math.pow(0.5, 1.4); t.lean = -99; t.debris = 0.2; }
                default -> { t.reach = 1; t.strength = peak; t.width = wMax; t.lean = -18; t.debris = 0.15 + 0.85 * Math.min(1, peak + 0.3); }
            }
            t.topWidth = t.width * 2.2 + 36 * Math.min(1, t.width / 16.0);
            t.windMs = 22 + 78 * t.strength;
            t.ef = FakeSim.efOf(t.windMs);
            t.phase = phase * (5 + 9 * t.strength);
            t.age = 20;
            if (t.onGround()) t.pathX0 = x - 150;
            return t;
        }

        private void tornadoRow(Graphics2D g, BufferedImage buf, double scale, Rectangle2D.Double m) {
            torRect = m;
            Lit lit = new Lit(DebugScreen.LIGHT_TOD[light], 0.55);
            // six in a row, or two rows of three: whichever draws the funnels larger
            double z6 = Math.min(m.height / 430.0, (m.width - 30) / 6 / 330.0), z3 = Math.min((m.height - 6) / 2 / 430.0, (m.width - 12) / 3 / 330.0);
            int cols = z6 >= z3 ? 6 : 3, rows = z6 >= z3 ? 1 : 2;
            double cw = (m.width - 6 * (cols - 1)) / cols, ch = (m.height - 6 * (rows - 1)) / rows;
            for (int k = 0; k < 6; k++) {
                Rectangle2D.Double c = new Rectangle2D.Double(m.x + (k % cols) * (cw + 6), m.y + (k / cols) * (ch + 6), cw, ch);
                torCells[k] = c;
                // one small world per cell, exactly as large as the cell shows: the camera cannot be anywhere else
                double z = Math.min(ch / 430.0, cw / 330.0), ww = cw / z, wh = ch / z, gy = wh - 40;
                Camera cam = new Camera();
                cam.setWorld(ww, wh);
                cam.setViewport(c.x, c.y, c.width, c.height);
                cam.set(ww / 2, wh / 2, z, true);
                Camera.View v = cam.view(m.x + m.width, m.y + m.height, scale, 0, 0);
                Model.Tornado t = tornado(k, torStage, ww / 2 + 20, gy, ds.still ? 1.3 : ds.anim);
                tornadoes[k] = t;
                Shape clip = g.getClip();
                g.clip(new RoundRectangle2D.Double(c.x, c.y, c.width, c.height, 10, 10));
                DebugScreen.sky(g, c.x, c.y, c.width, c.height, lit);
                g.setColor(lit.lit(new Color(98, 132, 80)));
                g.fill(new Rectangle2D.Double(c.x, v.sy(gy), c.width, c.y + c.height - v.sy(gy)));
                g.setClip(clip);
                Graphics2D d = buf.createGraphics();
                DebugScreen.hints(d);
                d.setClip(new Rectangle2D.Double(c.x * scale, c.y * scale, c.width * scale, c.height * scale));
                d.setTransform(v.worldTx());
                Model.Terrain flat = Model.Terrain.flat(ww, gy);
                if (!Double.isNaN(t.pathX0)) TornadoPainter.paintScar(d, flat, t.pathX0, t.x, 1, lit, 77 + k, v.wx0, v.wx1, v.zoom);
                TornadoPainter.paint(d, t, lit, v.zoom, true);
                TornadoPainter.Funnel f = TornadoPainter.render(t, lit, v);
                if (f != null) {
                    d.setTransform(new java.awt.geom.AffineTransform());
                    d.drawImage(f.image(), f.dx, f.dy, null);
                    d.setTransform(v.worldTx());
                }
                TornadoPainter.paint(d, t, lit, v.zoom, false);
                d.dispose();
            }
        }

        @Override void mouse(int type, int x, int y, int button) { if (pane != null) pane.mouse(type, x, y, button); }

        @Override void wheel(int x, int y, double rotation) { if (pane != null) pane.wheel(x, y, rotation); }

        @Override void key(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_RIGHT, KeyEvent.VK_TAB -> setView((view + 1) % VIEWS.length);
                case KeyEvent.VK_LEFT -> setView(Math.floorMod(view - 1, VIEWS.length));
                case KeyEvent.VK_W -> wind = !wind;
                case KeyEvent.VK_A -> arrows = !arrows;
                case KeyEvent.VK_L -> labels = !labels;
                case KeyEvent.VK_R -> built = "";
                case KeyEvent.VK_T -> { if (pane != null) pane.cmd("{\"cmd\":\"tornado\"}"); }
                case KeyEvent.VK_SPACE, KeyEvent.VK_P -> togglePause();
                default -> { }
            }
        }

        private void togglePause() {
            if (pane == null) return;
            paused = !paused;
            pane.cmd(paused ? "{\"cmd\":\"pause\"}" : "{\"cmd\":\"resume\"}");
        }

        @Override void chrome(Ui ui, Rectangle2D.Double r) {
            Rectangle2D.Double m = DebugScreen.main(r);
            String v = VIEWS[view];
            if (pane != null) {
                m = below(m);
                ui.stroke(m.x, m.y, m.width, m.height, 4, new Color(120, 180, 255, 80), 1f);
                Model.Frame f = pane.frame();
                String rest = "";
                if (f != null && (v.equals("rain") || v.equals("snow") || v.equals("mixed") || v.equals("graupel") || v.equals("water")))
                    rest = String.format(Locale.ROOT, "surface %.1f °C", f.surfaceTemp);
                if (f != null && v.equals("supercell"))
                    rest = f.tornadoes.isEmpty() ? "no tornado right now" : String.format(Locale.ROOT, "tornado %s, EF%d, %.0f m/s", f.tornadoes.get(0).stage, f.tornadoes.get(0).ef, f.tornadoes.get(0).windMs);
                if (f != null && v.equals("merge")) {
                    // (the merged cloud nearest the middle of the picture: the scene has two mergers)
                    Model.Cloud near = null;
                    for (Model.Cloud c : f.clouds) if (c.parts.length > 0 && (near == null || Math.abs(c.x - pane.cam.cx) < Math.abs(near.x - pane.cam.cx))) near = c;
                    if (near != null) rest = String.format(Locale.ROOT, "%s has absorbed %s (%.0f %%)", near.name, near.parts[near.parts.length - 1].shortName(), near.merge * 100);
                    if (rest.isEmpty()) rest = "the clouds are still drifting together";
                }
                if (v.equals("water")) rest = WATER[water] + "   ·   " + rest;
                if (v.equals("wind")) rest = "wind field " + (wind ? "on" : "off") + ", arrows " + (arrows ? "on" : "off");
                if (f != null && v.equals("glass")) rest = String.format(Locale.ROOT, "wind %+.1f m/s at the ground here", f.windU(pane.cam.cx, f.groundY - 20));
                caption(ui, VIEW_NAMES[view].toUpperCase(Locale.ROOT), rest + "   ·   drag to pan, wheel to zoom", m);
                if (pane.warm > 0) ui.textCenter("preparing the scene…", m.getCenterX(), m.getCenterY(), Ui.F_HEAD, Color.WHITE);
            } else {
                for (int k = 0; k < 6; k++) {
                    Rectangle2D.Double c = torCells[k];
                    Model.Tornado t = tornadoes[k];
                    if (c == null || t == null) continue;
                    ui.stroke(c.x, c.y, c.width, c.height, 10, new Color(255, 255, 255, 40), 1f);
                    ui.g.setFont(Ui.F_HEAD);
                    HurricanePainter.shadowText(ui.g, "EF" + k, c.x + 10, c.y + 22, Color.WHITE);
                    ui.g.setFont(Ui.F_SMALL);
                    HurricanePainter.shadowText(ui.g, String.format(Locale.ROOT, "peak gust %.0f m/s", EF_WIND[k]), c.x + 10, c.y + 37, new Color(232, 240, 250));
                    HurricanePainter.shadowText(ui.g, String.format(Locale.ROOT, "now %s: %.0f m/s (EF%d), %.0f px wide", t.stage, t.windMs, t.ef, t.width), c.x + 10, c.y + 51, new Color(232, 240, 250));
                }
            }
            double[] s = DebugScreen.side(ui, r);
            double x = s[0], y = s[1], w = s[2];
            DebugScreen.label(ui, "WHAT TO SHOW", x, y);
            y += 16;
            double bw = (w - 12) / 3;
            for (int k = 0; k < VIEWS.length; k++) {
                final int idx = k;
                DebugScreen.pick(ui, VIEW_NAMES[k], k == view, x + (k % 3) * (bw + 6), y + (k / 3) * 30, bw, 26, () -> setView(idx));
            }
            y += (VIEWS.length + 2) / 3 * 30 + 6;
            if (pane == null) {
                DebugScreen.label(ui, "TORNADO STAGE", x, y);
                y += 16;
                ui.seg(new String[]{"Funnel", "Touch", "Mature", "Roping"}, torStage, x, y, w, 30, k -> torStage = k);
                y += 40;
            } else if (v.equals("water")) {
                DebugScreen.label(ui, "LOOK AT", x, y);
                y += 16;
                ui.seg(new String[]{"Pond", "Puddle", "Snowpack"}, water, x, y, w, 30, k -> water = k);
                y += 36;
                y += DebugScreen.toggleRow(ui, "Frost (−6 °C: frozen pond, snow)", frost, x, y, w, () -> frost = !frost);
            }
            DebugScreen.label(ui, "LIGHT", x, y);
            y += 16;
            ui.seg(DebugScreen.LIGHTS, light, x, y, w, 30, k -> light = k);
            y += 38;
            if (pane != null) {
                // the wind of this world (the rain, and the water on wet panes and cars, lean with it) and the shape of the rain glint
                Model.Frame wf = pane.frame();
                ui.text(String.format(Locale.ROOT, "Wind %+.0f m/s", pane.sim.windBase()), x, y + 18, Ui.F_NORM, Ui.TEXT);
                double kw = 30, kx = x + w - 3 * kw - 8;
                if (wf != null) ui.textFit(String.format(Locale.ROOT, "(%+.1f ground)", wf.windU(pane.cam.cx, wf.groundY - 20)), x + 88, y + 18, kx - x - 94, Ui.F_SMALL, Ui.DIM);
                ui.button("◄", kx, y, kw, 26, Ui.NORMAL, true, false, () -> blow(pane.sim.windBase() - BLOW_STEP));
                ui.button("0", kx + kw + 4, y, kw, 26, Ui.NORMAL, true, false, () -> blow(0));
                ui.button("►", kx + 2 * (kw + 4), y, kw, 26, Ui.NORMAL, true, false, () -> blow(pane.sim.windBase() + BLOW_STEP));
                y += 32;
                if (!v.equals("water") && !v.equals("snow")) {       // (no room for it under the water view's own rows; no rain in the snow)
                    ui.text("Rain glint", x, y + 18, Ui.F_NORM, ds.app.settings.adv.noShine ? Ui.FAINT : Ui.TEXT);
                    ui.seg(Advanced.GLINT_NAMES, glintStar() ? 1 : 0, x + w - 124, y, 124, 26, k -> glint = k);
                    y += 34;
                }
                // (two to a row: the column has to hold the wind and glint rows as well)
                double tw = (w - 12) / 2;
                DebugScreen.toggleRow(ui, "Wind field", wind, x, y, tw, () -> wind = !wind);
                y += DebugScreen.toggleRow(ui, "Arrows", arrows, x + tw + 12, y, tw, () -> arrows = !arrows);
                DebugScreen.toggleRow(ui, "Labels", labels, x, y, tw, () -> labels = !labels);
                y += DebugScreen.toggleRow(ui, "Legend", legend, x + tw + 12, y, tw, () -> legend = !legend);
                y += 4;
                double hw = (w - 12) / 3;
                ui.button(paused ? "Resume" : "Pause", x, y, hw, 30, paused ? Ui.PRIMARY : Ui.NORMAL, true, false, this::togglePause);
                ui.button("Restart", x + hw + 6, y, hw, 30, Ui.NORMAL, true, false, () -> { built = ""; paused = false; });
                ui.button("Tornado", x + 2 * (hw + 6), y, hw, 30, Ui.NORMAL, v.equals("supercell") || v.equals("wind") || v.equals("graupel"), false, () -> pane.cmd("{\"cmd\":\"tornado\"}"));
                y += 38;
                if (!v.equals("graupel")) y += ds.seedRow(ui, "WORLD SEED", x, y, w) + 8;
            }
            for (String l : ui.wrap(note, Ui.F_SMALL, w)) {
                if (y + 14 > r.y + r.height - 8) break;
                ui.text(l, x, y + 10, Ui.F_SMALL, Ui.DIM);
                y += 14;
            }
        }
    }

    // =====================================================================================
    // World

    /**
     * One live world and everything about where and when it is: the clock, the month, the biome or region preset it is
     * dressed as, the terrain the built-in generator can make, a backdrop picture and the under-cloud darkening.
     */
    static final class WorldTab extends DebugScreen.Tab {
        static final String[] TERRAINS = {"Rolling", "Flat stage"};
        private final DebugScreen ds;
        Pane pane;
        /** 0 = none, k = {@code Preset.Biome.IDS[k - 1]}. */
        int biome;
        /** -1 = none, else an index into {@link Preset#IDS}. */
        int preset = -1;
        int terrain, month = 4, backdrop;
        double hour = 12, shadeStrength = 0.65;
        boolean shade = true, climate, labels = true, paused;
        private String built = "";
        private BufferedImage testBackdrop;

        WorldTab(DebugScreen ds) { this.ds = ds; }

        @Override void leave() {
            if (pane != null) pane.dispose();
            pane = null;
            built = "";
        }

        @Override void option(String key, String value) {
            switch (key) {
                case "biome" -> { biome = java.util.Arrays.asList(Preset.Biome.IDS).indexOf(value) + 1; preset = -1; }
                case "preset" -> { preset = java.util.Arrays.asList(Preset.IDS).indexOf(value); biome = 0; }
                case "terrain" -> terrain = value.equals("flat") ? 1 : 0;
                case "hour" -> hour = Double.parseDouble(value);
                case "month" -> month = Math.max(1, Math.min(12, Integer.parseInt(value)));
                case "backdrop" -> backdrop = value.equals("off") ? 0 : 1;
                case "shade" -> shade = !value.equals("off");
                case "climate" -> climate = !value.equals("off");
                case "under" -> wantUnder = true;
                default -> { }
            }
        }

        private boolean wantUnder;

        private String key() { return ds.seed + "|" + biome + "|" + preset + "|" + terrain; }

        private void build() {
            leave();
            FakeSim sim = terrain == 1 ? FakeSim.showcase(ds.seed, hour / 24.0, "cumulus")
                    : new FakeSim(ds.seed, hour / 24.0, WORLD_W, WORLD_H, null, preset >= 0 ? Preset.IDS[preset] : null, month + 0.5, hour,
                    biome > 0 ? "biome:" + Preset.Biome.IDS[biome - 1] : null);
            pane = new Pane(sim, ds.still);
            pane.warm = terrain == 1 ? 520 : 30;       // (the stage starts empty: give its one cloud time to form)
            built = key();
            paused = false;
        }

        void setHour(double h) {
            hour = Model.clamp(h, 0, 23.99);
            if (pane != null) pane.cmd(cmd("hour", "value", hour));
        }

        void setMonth(int m) {
            month = Math.max(1, Math.min(12, m));
            if (pane != null) pane.cmd(cmd("season", "month", month + 0.5));
        }

        /** A picture to stand in for the player's own: generated, held in memory, never read from or written to disk. */
        private BufferedImage testBackdrop() {
            if (testBackdrop != null) return testBackdrop;
            BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            DebugScreen.hints(g);
            g.setPaint(new GradientPaint(0, 0, new Color(250, 150, 90), 0, 720, new Color(60, 30, 110)));
            g.fillRect(0, 0, 1280, 720);
            g.setColor(new Color(255, 236, 170));
            g.fill(new Ellipse2D.Double(880, 120, 190, 190));
            g.setColor(new Color(255, 255, 255, 30));
            for (int k = 0; k < 24; k++) g.fillRect(k * 80 - 20 + (k % 2) * 30, 0, 26, 720);
            g.setColor(new Color(255, 255, 255, 150));
            g.setFont(Ui.F_TITLE);
            g.drawString("DEBUG BACKDROP", 60, 110);
            g.dispose();
            return testBackdrop = img;
        }

        /** Put the camera under the thickest cloud of the frame (the place the under-cloud darkening is for). */
        void goUnderCloud() {
            if (pane == null) return;
            Model.Frame f = pane.frame();
            Model.Cloud best = null;
            double bestV = -1;
            if (f != null) for (Model.Cloud c : f.clouds) {
                double v = c.darkness * c.form * c.opacity * (c.fair ? 0.45 : 1) + (c.precipRate > 0.02 ? 0.3 : 0);
                if (v > bestV) { bestV = v; best = c; }
            }
            if (best == null) return;
            Camera cam = pane.cam;
            double z = Model.clamp(cam.vh / 620.0, cam.minZoom(), Camera.MAX_ZOOM), gy = pane.hello().terrain.at(Model.clamp(best.x, 0, cam.worldW));
            cam.set(best.x, gy - cam.vh / z * 0.44, z, ds.still);
        }

        @Override void scene(Graphics2D g, BufferedImage buf, double scale, Rectangle2D.Double r, double dt) {
            if (pane == null || !key().equals(built)) build();
            pane.pump(dt);
            if (wantUnder && pane.warm == 0) {
                wantUnder = false;
                final Consumer<Pane> old = pane.placer;
                pane.placer = p -> { if (old != null) old.accept(p); else { p.cam.home(); p.cam.snap(); } goUnderCloud(); };
                pane.replace();
            }
            Renderer rr = pane.r();
            rr.backdropImage = backdrop == 1 ? testBackdrop() : backdrop == 2 ? ds.app.backdrop() : null;
            rr.backdropFit = ds.app.settings.bgFit;
            rr.backdropDim = ds.app.settings.bgDim;
            rr.backdropTint = ds.app.settings.bgTint;
            rr.shadeOn = shade;
            rr.shadeStrength = shadeStrength;
            rr.shadeStillSeconds = -1;
            rr.showClimate = climate;
            rr.showLabels = labels;
            pane.render(buf, scale, below(DebugScreen.main(r)), dt);
        }

        @Override void mouse(int type, int x, int y, int button) { if (pane != null) pane.mouse(type, x, y, button); }

        @Override void wheel(int x, int y, double rotation) { if (pane != null) pane.wheel(x, y, rotation); }

        @Override void key(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_RIGHT -> setHour((hour + 1) % 24);
                case KeyEvent.VK_LEFT -> setHour((hour + 23) % 24);
                case KeyEvent.VK_UP -> setMonth(month % 12 + 1);
                case KeyEvent.VK_DOWN -> setMonth((month + 10) % 12 + 1);
                case KeyEvent.VK_U -> goUnderCloud();
                case KeyEvent.VK_K -> climate = !climate;
                case KeyEvent.VK_L -> labels = !labels;
                case KeyEvent.VK_HOME, KeyEvent.VK_F -> { if (pane != null) pane.cam.home(); }
                case KeyEvent.VK_SPACE, KeyEvent.VK_P -> togglePause();
                default -> { }
            }
        }

        private void togglePause() {
            if (pane == null) return;
            paused = !paused;
            pane.cmd(paused ? "{\"cmd\":\"pause\"}" : "{\"cmd\":\"resume\"}");
        }

        @Override void chrome(Ui ui, Rectangle2D.Double r) {
            Rectangle2D.Double m = below(DebugScreen.main(r));
            ui.stroke(m.x, m.y, m.width, m.height, 4, new Color(120, 180, 255, 80), 1f);
            if (pane == null) return;
            Model.Frame f = pane.frame();
            Model.Hello h = pane.hello();
            boolean flat = terrain == 1;
            if (f != null && h != null) {
                double hr = f.timeOfDay * 24;
                caption(ui, "WORLD", String.format(Locale.ROOT, "%02d:%02d   ·   %s   ·   %.1f °C   ·   terrain %s%s   ·   under-cloud dimming %.0f %%   ·   drag to pan, wheel to zoom", (int) hr, (int) ((hr - (int) hr) * 60),
                        f.hasCalendar() ? Model.MONTHS[Model.monthIndex(f.month)] + " (" + f.season + ")" : "no calendar", f.surfaceTemp, h.terrainKind,
                        h.preset != null ? "   ·   " + h.preset.name : "", pane.r().shadeOn ? pane.r().shadeLevel * pane.r().shadeStrength * CloudShade.MAX_ALPHA * 100 : 0), m);
            }
            if (pane.warm > 0) ui.textCenter("building the world…", m.getCenterX(), m.getCenterY(), Ui.F_HEAD, Color.WHITE);

            double[] s = DebugScreen.side(ui, r);
            double x = s[0], y = s[1], w = s[2];
            boolean tight = r.height < 600;
            double gap = tight ? 4 : 8;
            DebugScreen.label(ui, String.format(Locale.ROOT, "TIME OF DAY   %02d:%02d%s", (int) hour, (int) ((hour - (int) hour) * 60), flat ? "  (fixed on the stage)" : ""), x, y);
            y += 14;
            ui.slider(hour / 24.0, x, y, w, v -> setHour(Math.round(v * 96) / 4.0));
            y += 26;
            double qw = (w - 18) / 4;
            String[] qn = {"Dawn", "Noon", "Dusk", "Night"};
            double[] qh = {6, 12, 18.25, 0};
            for (int k = 0; k < 4; k++) {
                final double to = qh[k];
                DebugScreen.pick(ui, qn[k], Math.abs(hour - to) < 0.13, x + k * (qw + 6), y, qw, 24, () -> setHour(to));
            }
            y += 24 + gap;
            DebugScreen.label(ui, "MONTH   " + Model.MONTHS[month - 1] + (flat ? "  (fixed on the stage)" : ""), x, y);
            y += 16;
            String[] ml = new String[12];
            for (int k = 0; k < 12; k++) ml[k] = Model.MONTHS[k].substring(0, 1);
            ui.seg(ml, month - 1, x, y, w, 26, k -> setMonth(k + 1));
            y += 26 + gap;
            DebugScreen.label(ui, "BIOME", x, y);
            y += 16;
            double bw = (w - 12) / 3;
            for (int k = 0; k <= Preset.Biome.IDS.length; k++) {
                final int idx = k;
                String nm = k == 0 ? "none" : Preset.Biome.IDS[k - 1];
                DebugScreen.pick(ui, nm, biome == k && (k > 0 || preset < 0), x + (k % 3) * (bw + 6), y + (k / 3) * 27, bw, 24, () -> { biome = idx; preset = -1; terrain = 0; });
            }
            y += 3 * 27 + gap;
            DebugScreen.label(ui, "REGION PRESET   " + (preset < 0 ? Preset.IDS.length + " to choose from" : (preset + 1) + " of " + Preset.IDS.length), x, y);
            y += 16;
            ui.button("‹", x, y, 26, 26, Ui.NORMAL, Preset.IDS.length > 0, false, () -> { preset = preset <= 0 ? Preset.IDS.length - 1 : preset - 1; biome = 0; terrain = 0; });
            ui.fill(x + 30, y, w - 60, 26, 8, new Color(20, 26, 42, 240));
            ui.textCenter(ui.fit(preset < 0 ? "none" : Preset.NAMES[preset], Ui.F_BOLD, w - 70), x + w / 2, y + 17.5, Ui.F_BOLD, preset < 0 ? Ui.DIM : Color.WHITE);
            ui.hit(x + 30, y, w - 60, 26, () -> preset = -1);
            ui.button("›", x + w - 26, y, 26, 26, Ui.NORMAL, Preset.IDS.length > 0, false, () -> { preset = (preset + 1) % Preset.IDS.length; biome = 0; terrain = 0; });
            y += 26 + gap;
            DebugScreen.label(ui, "TERRAIN  (hilly, mountainous: the Python sim only)", x, y);
            y += 16;
            ui.seg(TERRAINS, terrain, x, y, w, 26, k -> { terrain = k; if (k == 1) { biome = 0; preset = -1; } });
            y += 26 + gap;
            DebugScreen.label(ui, "BACKDROP PICTURE", x, y);
            y += 16;
            boolean mine = ds.app.backdrop() != null;
            ui.seg(mine ? new String[]{"Off", "Test picture", "Yours"} : new String[]{"Off", "Test picture"}, Math.min(backdrop, mine ? 2 : 1), x, y, w, 26, k -> backdrop = k);
            y += 26 + gap;
            ui.text(String.format(Locale.ROOT, "Under-cloud darkening  %.0f %%", shadeStrength * 100), x, y + 17, Ui.F_NORM, Ui.TEXT);
            ui.toggle(shade, x + w - 46, y, () -> shade = !shade);
            y += 26;
            ui.slider(shadeStrength, x, y, w - 104, v -> shadeStrength = Math.max(0.1, Math.round(v * 20) / 20.0));
            ui.button("Go under", x + w - 98, y - 1, 98, 26, Ui.NORMAL, true, false, this::goUnderCloud);
            y += 26 + gap;
            double hw = (w - 12) / 3;
            DebugScreen.pick(ui, "Climate", climate, x, y, hw, 26, () -> climate = !climate);
            DebugScreen.pick(ui, "Labels", labels, x + hw + 6, y, hw, 26, () -> labels = !labels);
            ui.button(paused ? "Resume" : "Pause", x + 2 * (hw + 6), y, hw, 26, paused ? Ui.PRIMARY : Ui.NORMAL, true, false, this::togglePause);
            y += 26 + gap;
            if (y + 46 <= r.y + r.height - 10) ds.seedRow(ui, "WORLD SEED", x, y, w);
        }
    }

    // =====================================================================================
    // Hurricane and World map: the game's own screens, inside a tab

    /** A lesson screen of the game painted inside the tab's rectangle, on its built-in fake, stepped from the paint. */
    abstract static class Embedded extends DebugScreen.Tab {
        final DebugScreen ds;
        Screen screen;
        ViewerPanel.Scene scenePainter;
        GameSession egs;
        Scripted fake;
        private BufferedImage off;
        private double acc;
        Rectangle2D.Double rect = new Rectangle2D.Double();

        Embedded(DebugScreen ds) { this.ds = ds; }

        abstract void build();

        @Override void enter() { }

        @Override void leave() {
            screen = null; scenePainter = null; egs = null; fake = null; off = null;
        }

        void feed(int n) {
            for (int k = 0; k < n; k++) egs.state.acceptLine(fake.step());
        }

        @Override void scene(Graphics2D g, BufferedImage buf, double scale, Rectangle2D.Double r, double dt) {
            if (screen == null) build();
            rect = r;
            acc += dt;
            int n = 0;
            while (acc >= FakeSim.DT && n < 3) { feed(1); acc -= FakeSim.DT; n++; }
            if (acc > FakeSim.DT) acc = 0;
            int w = (int) Math.floor(r.width), h = (int) Math.floor(r.height);
            int dw = (int) Math.ceil(w * scale), dh = (int) Math.ceil(h * scale);
            if (off == null || off.getWidth() != dw || off.getHeight() != dh) off = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
            scenePainter.paintScene(off, scale, w, h, ds.still ? -1 : dt);
            Graphics2D d = buf.createGraphics();
            d.drawImage(off, (int) Math.round(r.x * scale), (int) Math.round(r.y * scale), null);
            d.dispose();
        }

        @Override void chrome(Ui ui, Rectangle2D.Double r) {
            if (screen == null) return;
            // the screen paints itself as if the tab's rectangle were its window: drawing and click areas are shifted together
            java.awt.geom.AffineTransform old = ui.g.getTransform();
            ui.g.translate(r.x, r.y);
            ui.ox = r.x; ui.oy = r.y;
            try {
                screen.paint(ui, (int) Math.floor(r.width), (int) Math.floor(r.height));
            } finally {
                ui.g.setTransform(old);
                ui.ox = 0; ui.oy = 0;
            }
        }

        @Override void key(KeyEvent e) {
            // (Esc and the digits belong to the Debug screen: they never get here)
            if (screen != null) screen.key(e);
        }

        @Override void wheel(int x, int y, double rotation) {
            if (screen != null) screen.wheel((int) (x - rect.x), (int) (y - rect.y), rotation);
        }

        @Override void mouse(int type, int x, int y, int button) {
            if (screen != null) screen.mouse(type, (int) (x - rect.x), (int) (y - rect.y), button);
        }
    }

    /** {@link HurricaneScreen} on {@link FakeHurricane}: the stage explorer (ten stages, outside / inside / both) and the tour. */
    static final class HurricaneTab extends Embedded {
        String stage = "cat3";
        int view = HurricaneScreen.BOTH;
        HurricaneScreen hs;
        FakeHurricane fh;

        HurricaneTab(DebugScreen ds) { super(ds); }

        @Override void option(String key, String value) {
            switch (key) {
                case "stage" -> stage = value;
                case "view" -> view = switch (value) { case "outside" -> HurricaneScreen.OUTSIDE; case "inside" -> HurricaneScreen.INSIDE; default -> HurricaneScreen.BOTH; };
                default -> { }
            }
            leave();
        }

        @Override void leave() { super.leave(); hs = null; fh = null; }

        @Override void build() {
            fh = new FakeHurricane();
            fake = fh;
            egs = new GameSession(ds.app, null, new Pump(fh.describe(), fh::handle), false, "Hurricane Formation (Debug)");
            egs.mode = "hurricane";
            hs = new HurricaneScreen(ds.app, egs);
            hs.still = ds.still;
            hs.tour = false;                       // the stage explorer: every stage one click away
            hs.selStage = stage;
            hs.view = view;
            hs.markStarted();
            egs.state.acceptLine(fh.hello());
            fh.handle("{\"cmd\":\"stage\",\"id\":" + Json.quote(stage) + "}");
            feed(ds.still ? 300 : 120);            // the storm glides to a stage over a few seconds
            screen = hs;
            scenePainter = hs;
        }

        /** Jump to a stage (tests): as a click on its chip does. */
        void pickStage(String id) {
            stage = id;
            if (hs != null) { hs.pickStage(id); if (ds.still) feed(300); }
        }
    }

    /** {@link WorldScreen} on {@link FakeWorld}: the seven map layers, the calendar and the clock. Opening a place is off here. */
    static final class WorldMapTab extends Embedded {
        String layer = "temp";
        double month = 4.5, utcHour = 13;
        WorldScreen ws;
        FakeWorld fw;

        WorldMapTab(DebugScreen ds) { super(ds); }

        @Override void option(String key, String value) {
            switch (key) {
                case "layer" -> layer = value;
                case "month" -> month = Double.parseDouble(value);
                case "hour" -> utcHour = Double.parseDouble(value);
                default -> { }
            }
            leave();
        }

        @Override void leave() { super.leave(); ws = null; fw = null; }

        @Override void build() {
            fw = new FakeWorld(ds.app.root, month, utcHour);
            fake = fw;
            egs = new GameSession(ds.app, null, new Pump(fw.describe(), fw::handle), false, "World Simulation (Debug)");
            egs.mode = "world";
            ws = new WorldScreen(ds.app, egs);
            ws.still = ds.still;
            egs.state.acceptLine(fw.hello());
            feed(40);
            setLayer(layer);
            screen = ws;
            scenePainter = ws;
        }

        void setLayer(String id) {
            layer = id;
            int li = java.util.Arrays.asList(WorldPainter.LAYER_IDS).indexOf(id);
            if (ws != null && li >= 0) ws.setLayer(li);
        }
    }
}
