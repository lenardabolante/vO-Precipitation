package precip;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * The game view: a Swing surface that shows one world through a {@link Camera}, repaints at
 * ~60 fps and handles input. Each frame is rendered in software (in parallel horizontal bands)
 * into a device-resolution buffer and handed to Swing as one opaque blit.
 * <p>
 * <b>Phase C seams</b>
 * <ul>
 *   <li><b>Lifecycle:</b> construct with a {@link ViewerState} and a {@link FrameSource}; call
 *       {@link #dispose()} when leaving the game (stops the repaint timer and the render / cloud
 *       worker threads). Any number of panels may be created and disposed in one process; the panel
 *       never calls {@code System.exit} and keeps no static session state ({@link #fpsLog} is a
 *       diagnostic switch).</li>
 *   <li><b>Keys:</b> {@link #keyPressed} looks the key code up in the bindings table
 *       ({@link #setBindings}, default {@link Action#defaults()}) and calls {@link #perform(Action)};
 *       nothing else interprets keys. {@link #actionListener} sees every action first and may
 *       consume it (return true) — that is where a pause menu, quick save and fullscreen hook in.</li>
 *   <li><b>Menu / quit:</b> {@link Action#MENU} (Esc) runs the {@code onMenu} callback given to the
 *       constructor.</li>
 *   <li><b>Rendering settings:</b> {@link #renderer()} is the primary {@link Renderer} (panel
 *       visibility flags, backdrop image, help rows); {@link #camera()} the camera.</li>
 *   <li><b>Overlay:</b> {@link #overlay} is painted over the finished frame (menus, edge tabs, the
 *       save indicator) and gets every mouse and key event first; whatever it does not consume goes
 *       to the game view. The main menu is the same panel showing a decorative world with an
 *       overlay that consumes everything.</li>
 * </ul>
 */
final class ViewerPanel extends JPanel {
    /** Something drawn over the game view that sees input before it (see the class comment). */
    interface Overlay {
        /** Paint in logical window coordinates (0, 0, w, h). */
        void paint(Graphics2D g, int w, int h);

        /** {@code type} is a {@code MouseEvent.MOUSE_*} id (pressed, released, moved, dragged, exited). True = consumed. */
        boolean mouse(int type, int x, int y, int button);

        boolean wheel(int x, int y, double rotation);

        /** A key press. True = consumed (the game bindings do not see it). */
        boolean key(KeyEvent e);

        boolean typed(char c);
    }

    /** Something that paints the whole frame instead of the world renderer (the hurricane mode). */
    interface Scene {
        /** Paint the logical canvas (0, 0, w, h) into {@code buf} at {@code scale} device px per logical px; {@code dt} = seconds since the last frame. */
        void paintScene(java.awt.image.BufferedImage buf, double scale, int w, int h, double dt);
    }

    /** Something told about every painted frame (the game's sound). */
    interface FrameHook {
        /** {@code dt} = seconds since the previous painted frame (0 for the first). Runs on the event thread, at the end of the paint. */
        void frame(ViewerPanel p, double dt);
    }

    /** When set, this is drawn instead of the world (same software buffer, same overlay on top). */
    volatile Scene scene;
    /** Phase P: called once at the end of every painted frame, for the world and for a {@link Scene} alike. Null (every panel but a game's) = nothing happens. */
    transient volatile FrameHook frameHook;
    /** Runs with the canvas size before every frame is laid out (a surrounding screen sets the renderer's insets here). May be null. */
    volatile java.util.function.BiConsumer<Integer, Integer> layoutHook;
    volatile Overlay overlay;
    /** Told about every action after the panel has performed it (not about consumed ones). May be null. */
    volatile java.util.function.Consumer<Action> afterAction;
    /** Runs every frame after the layout and may place the camera (the menu's decorative world uses it). May be null. */
    volatile java.util.function.Consumer<Camera> cameraDirector;
    private boolean overlayHasMouse;
    private double[] pendingCam;
    private long fpsT0 = System.nanoTime();
    private int fpsFrames;
    private volatile double fps;

    private final ViewerState state;
    private final FrameSource source;
    private final ParallelRenderer bands = new ParallelRenderer(ParallelRenderer.defaultThreads());
    private final Renderer renderer = bands.primary(); // toggles + hit tests
    private final Camera cam = new Camera();
    private final long t0 = System.nanoTime();
    private final Runnable onMenu;
    private final javax.swing.Timer timer;
    private Map<Integer, Action> bindings = Action.defaults();
    /** Sees every action before the panel handles it; return true to consume it. May be null. */
    volatile Predicate<Action> actionListener;
    private static final double[] SPEEDS = {0.25, 0.5, 1, 2, 4, 8};
    /** v2.3: the cloud type a click on the sky asks for; 0 = let the simulation choose ("random"), k = {@code Model.CLOUD_TYPES[k - 1]}. */
    private int spawnType;

    /** The type a click seeds (null = random). */
    String spawnTypeName() { return spawnType == 0 ? null : Model.CLOUD_TYPES[spawnType - 1]; }

    void setSpawnType(int idx) {
        spawnType = Math.floorMod(idx, Model.CLOUD_TYPES.length + 1);
        renderer.spawnTypeLabel = spawnType == 0 ? "random" : Model.CLOUD_TYPES[spawnType - 1];
    }

    /**
     * Highest device scale the frame is rendered at. On a HiDPI display with a larger scale the frame is
     * rendered at this scale and stretched by the window blit: a 200% display would otherwise mean four
     * times the pixels of a 100% one for every frame. 0 = always native. A settings screen may change it.
     */
    static volatile double maxRenderScale = 1.5;
    /** --fps-log: print on-screen paints/s and paint cost every 2 s (diagnostics). */
    static boolean fpsLog;
    private long logT0 = System.nanoTime(), renderNs, renderMax, blitNs, lastFrames = -1;
    private int frames;
    private long lastPaintNanos;
    private boolean disposed;

    // mouse drag state
    private int dragButton, dragX, dragY;
    private boolean dragMinimap, dragMoved;

    // "view" command: tell the sim which span is visible so it can skip listing off-screen particles
    private double sentX0 = Double.NaN, sentX1 = Double.NaN;
    private long sentAt;

    /** Software back buffer at device resolution (window size x HiDPI scale). */
    private java.awt.image.BufferedImage buf;

    ViewerPanel(ViewerState state, FrameSource source, Runnable onMenu) {
        this.state = state;
        this.source = source;
        this.onMenu = onMenu;
        setBackground(Color.BLACK);
        setOpaque(true);
        setDoubleBuffered(true);
        setFocusable(true);
        setPreferredSize(new Dimension(1280 + Renderer.PANEL_W, 720));

        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                Overlay ov = overlay;
                overlayHasMouse = ov != null && ov.mouse(MouseEvent.MOUSE_PRESSED, e.getX(), e.getY(), e.getButton());
                if (overlayHasMouse) return;
                dragButton = e.getButton();
                dragX = e.getX(); dragY = e.getY();
                dragMoved = false;
                dragMinimap = e.getButton() == MouseEvent.BUTTON1 && !renderer.showHelp && renderer.minimapToWorld(e.getX(), e.getY(), false) != null;
                if (dragMinimap) {
                    double[] w = renderer.minimapToWorld(e.getX(), e.getY(), true);
                    cam.centerOn(w[0], w[1]);
                }
            }

            @Override public void mouseDragged(MouseEvent e) {
                Overlay ov = overlay;
                if (overlayHasMouse) { if (ov != null) ov.mouse(MouseEvent.MOUSE_DRAGGED, e.getX(), e.getY(), e.getButton()); return; }
                if (ov != null) ov.mouse(MouseEvent.MOUSE_MOVED, e.getX(), e.getY(), 0);
                int dx = e.getX() - dragX, dy = e.getY() - dragY;
                if (Math.abs(dx) + Math.abs(dy) > 2) dragMoved = true;
                if (dragMinimap) {
                    double[] w = renderer.minimapToWorld(e.getX(), e.getY(), true);
                    if (w != null) { cam.centerOn(w[0], w[1]); cam.snap(); }
                } else if (dragButton == MouseEvent.BUTTON2 || dragButton == MouseEvent.BUTTON3) {
                    cam.dragBy(dx, dy);
                }
                dragX = e.getX(); dragY = e.getY();
            }

            @Override public void mouseMoved(MouseEvent e) {
                Overlay ov = overlay;
                if (ov != null) ov.mouse(MouseEvent.MOUSE_MOVED, e.getX(), e.getY(), 0);
            }

            @Override public void mouseExited(MouseEvent e) {
                Overlay ov = overlay;
                if (ov != null) ov.mouse(MouseEvent.MOUSE_EXITED, -1, -1, 0);
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (overlayHasMouse) {
                    overlayHasMouse = false;
                    Overlay ov = overlay;
                    if (ov != null) ov.mouse(MouseEvent.MOUSE_RELEASED, e.getX(), e.getY(), e.getButton());
                    return;
                }
                if (!dragMinimap && !dragMoved) click(e.getX(), e.getY(), e.getButton());
                dragMinimap = false;
                dragButton = 0;
            }

            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                Overlay ov = overlay;
                if (ov != null && ov.wheel(e.getX(), e.getY(), e.getPreciseWheelRotation())) return;
                if (e.getX() >= renderer.panelX) {
                    renderer.timelineScroll = Math.max(0, renderer.timelineScroll - e.getWheelRotation() * 3);
                } else {
                    cam.zoomAt(e.getX(), e.getY(), Math.pow(1.2, -e.getPreciseWheelRotation()));
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                Overlay ov = overlay;
                if (ov != null && ov.key(e)) return;
                ViewerPanel.this.keyPressed(e.getKeyCode());
            }

            @Override public void keyTyped(KeyEvent e) {
                Overlay ov = overlay;
                if (ov != null) ov.typed(e.getKeyChar());
            }
        });
        setFocusTraversalKeysEnabled(false); // Tab belongs to the dialogs drawn by the overlay

        timer = new javax.swing.Timer(16, e -> repaint());
        timer.setCoalesce(true);
        timer.start();
    }

    Renderer renderer() { return renderer; }

    Camera camera() { return cam; }

    /** Put the camera here as soon as the world is known (a loaded save restores its view this way). */
    void restoreCamera(double x, double y, double zoom) { pendingCam = new double[]{x, y, zoom}; }

    /** On-screen paints per second, measured over the last half second. */
    double fps() { return fps; }

    boolean disposed() { return disposed; }

    /** Replace the key code → action table (see {@link Action#defaults()}). */
    void setBindings(Map<Integer, Action> table) { bindings = table; }

    Map<Integer, Action> bindings() { return bindings; }

    /** Stop repainting and release the worker threads. The panel must not be used afterwards. */
    void dispose() {
        if (disposed) return;
        disposed = true;
        timer.stop();
        bands.shutdown();
        buf = null;
    }

    /** Pixel format of the software back buffer (diagnostics: -Dprecip.buf=rgb|argbpre). */
    private static final int BUF_TYPE = "rgb".equals(System.getProperty("precip.buf"))
            ? java.awt.image.BufferedImage.TYPE_INT_RGB : java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE;

    @Override
    protected void paintComponent(Graphics g0) {
        if (disposed) return;
        Graphics2D screen = (Graphics2D) g0;
        java.awt.geom.AffineTransform base = screen.getTransform(); // HiDPI scale + position in Swing's buffer
        double native0 = Math.max(0.25, Math.abs(base.getScaleX()));
        double cap = maxRenderScale;
        double sx = cap > 0 && native0 > cap + 1e-6 ? cap : native0;
        long r0 = System.nanoTime();
        // Render the whole canvas in software into our own device-resolution image and hand Swing
        // one opaque blit. Drawing thousands of antialiased shapes straight into the accelerated
        // (D3D) back buffer costs a mask upload per shape and was the main source of lag.
        int dw = Math.max(1, (int) Math.ceil(getWidth() * sx)), dh = Math.max(1, (int) Math.ceil(getHeight() * sx));
        if (buf == null || buf.getWidth() != dw || buf.getHeight() != dh) {
            buf = new java.awt.image.BufferedImage(dw, dh, BUF_TYPE);
        }
        double dt = lastPaintNanos == 0 ? 0 : Math.min(0.1, (r0 - lastPaintNanos) / 1e9);
        lastPaintNanos = r0;
        Scene sc = scene;
        if (sc != null) sc.paintScene(buf, sx, getWidth(), getHeight(), dt);
        else {
        if (renderer.animatePanels) renderer.stepPanels(dt);
        java.util.function.BiConsumer<Integer, Integer> lh = layoutHook;
        if (lh != null) lh.accept(getWidth(), getHeight());
        // camera: follow the layout (panel toggles, window size), then ease toward its target
        bands.layout(cam, state, getWidth(), getHeight());
        if (pendingCam != null && cam.initialised) {
            boolean known;
            synchronized (state) { known = state.hello != null; }
            if (known) {
                cam.set(pendingCam[0], pendingCam[1], pendingCam[2], true);
                pendingCam = null;
            }
        }
        java.util.function.Consumer<Camera> cd = cameraDirector;
        if (cd != null) cd.accept(cam);
        cam.update(dt);
        // the canvas is drawn in parallel horizontal bands (see ParallelRenderer)
        bands.render(buf, sx, 0, 0, getWidth(), getHeight(), state, cam, System.nanoTime(), (System.nanoTime() - t0) / 1e9, source.describe());
        }
        long b0 = System.nanoTime();
        Graphics2D out = (Graphics2D) g0.create();
        out.setTransform(new java.awt.geom.AffineTransform(1, 0, 0, 1, base.getTranslateX(), base.getTranslateY()));
        if (sx == native0) out.drawImage(buf, 0, 0, null);
        else {
            out.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            out.drawImage(buf, 0, 0, (int) Math.ceil(getWidth() * native0), (int) Math.ceil(getHeight() * native0), 0, 0, dw, dh, null);
        }
        out.dispose();
        Overlay ov = overlay;
        if (ov != null) {
            Graphics2D og = (Graphics2D) g0.create();
            og.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            og.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            og.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE);
            try {
                ov.paint(og, getWidth(), getHeight());
            } finally {
                og.dispose();
            }
        }
        long b1 = System.nanoTime();
        fpsFrames++;
        if (b1 - fpsT0 >= 500_000_000L) {
            fps = fpsFrames * 1e9 / (b1 - fpsT0);
            fpsFrames = 0;
            fpsT0 = b1;
        }
        blitNs += b1 - b0;
        sendView(b1);
        FrameHook fh = frameHook;
        if (fh != null) fh.frame(this, dt);
        // a frame that took longer than a timer tick would otherwise wait for the tick after next
        if (b1 - r0 > 13_000_000L) repaint();
        if (fpsLog) logFrame(b0 - r0, sx, getWidth(), getHeight());
    }

    /** Throttled {@code view} command: only when the visible span really moved, at most ~4 times a second. */
    private void sendView(long now) {
        Camera.View v = renderer.view;
        if (scene != null || v == null || !v.settled && now - sentAt < 250_000_000L) return;
        double x0 = Math.floor(Math.max(0, v.wx0) / 20) * 20, x1 = Math.ceil(Math.min(v.worldW, v.wx1) / 20) * 20;
        if (x0 == sentX0 && x1 == sentX1) return;
        if (now - sentAt < 250_000_000L) return;
        sentX0 = x0; sentX1 = x1; sentAt = now;
        if (x0 <= 0 && x1 >= v.worldW) source.send("{\"cmd\":\"view\"}");
        else source.send(String.format(java.util.Locale.ROOT, "{\"cmd\":\"view\",\"x0\":%.0f,\"x1\":%.0f}", x0, x1));
    }

    private void logFrame(long ns, double sx, int w, int h) {
        frames++;
        renderNs += ns;
        renderMax = Math.max(renderMax, ns);
        long now = System.nanoTime();
        if (now - logT0 >= 2_000_000_000L) {
            double sec = (now - logT0) / 1e9;
            long fr;
            synchronized (state) { fr = state.framesReceived; }
            long bn = blitNs;
            blitNs = 0;
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[fps] %.1f frames/s  render avg %.1f ms max %.1f ms  blit to screen avg %.1f ms  sim frames/s %.1f  device scale %.2f  window %dx%d  bands %d  zoom %.2f",
                    frames / sec, renderNs / 1e6 / frames, renderMax / 1e6, bn / 1e6 / frames,
                    lastFrames < 0 ? 0 : (fr - lastFrames) / sec, sx, w, h, bands.threads(), cam.zoom));
            lastFrames = fr;
            logT0 = now;
            frames = 0;
            renderNs = 0;
            renderMax = 0;
        }
    }

    void toast(String s) {
        renderer.toast = s;
        renderer.toastUntilNanos = System.nanoTime() + 1_600_000_000L;
    }

    /** A click at canvas point (x, y). */
    private void click(double x, double y, int button) {
        if (renderer.showHelp) { renderer.showHelp = false; return; }
        if (button != MouseEvent.BUTTON1) return;
        java.awt.geom.Rectangle2D close = renderer.cardClose, card = renderer.cardRect;
        if (close != null && close.contains(x, y)) {
            synchronized (state) { state.selectedId = -1; }
            return;
        }
        if (card != null && card.contains(x, y)) return;
        java.awt.geom.Rectangle2D clim = renderer.climateRect;
        if (clim != null && clim.contains(x, y)) return;          // the Climate panel is not sky
        java.awt.geom.Rectangle2D drc = renderer.drainRect;
        if (drc != null && drc.contains(x, y)) return;            // nor is the drains card (v2.4)
        if (x >= renderer.panelX) {
            long id = renderer.timelineAt(x, y);
            if (id >= 0) synchronized (state) { state.selectedId = state.selectedId == id ? -1 : id; }
            return;
        }
        Camera.View v = renderer.view;
        if (v == null) return;
        double wx = v.wx(x), wy = v.wy(y);
        long id = renderer.cloudAt(state, wx, wy);
        if (id >= 0) {
            synchronized (state) { state.selectedId = id; }
            return;
        }
        synchronized (state) { state.selectedId = -1; }
        Model.Hello h;
        synchronized (state) { h = state.hello; }
        if (h != null && wx >= 0 && wx <= h.w && wy > 0 && wy < h.terrain.at(wx) - 4) {
            // v2.3: with a type chosen the spawn asks for exactly that; "random" sends the plain v2 command
            String type = spawnTypeName();
            source.send(String.format(java.util.Locale.ROOT, "{\"cmd\":\"spawn\",\"x\":%.1f,\"y\":%.1f%s}", wx, wy, type == null ? "" : ",\"type\":\"" + type + "\""));
            toast(String.format(type == null ? "Seeding a new cloud at (%.0f, %.0f)" : "Seeding " + aOrAn(type) + " at (%.0f, %.0f)", wx, wy));
            state.log(String.format("[viewer] spawn requested at (%.0f, %.0f)%s", wx, wy, type == null ? "" : " — " + type));
        }
    }

    /** The single entry point for keys: key code → bound action → {@link #perform}. */
    void keyPressed(int keyCode) {
        Action a = bindings.get(keyCode);
        if (a != null) perform(a);
    }

    /** Do what an action means. Safe to call from menus and tests as well as from keys (EDT). */
    void perform(Action a) {
        Predicate<Action> l = actionListener;
        if (l != null && l.test(a)) return;
        doAction(a);
        java.util.function.Consumer<Action> after = afterAction;
        if (after != null) after.accept(a);
    }

    private void doAction(Action a) {
        Model.Frame f;
        synchronized (state) { f = state.cur; }
        switch (a) {
            case PAUSE -> {
                boolean paused = f != null && f.paused;
                source.send(paused ? "{\"cmd\":\"resume\"}" : "{\"cmd\":\"pause\"}");
                toast(paused ? "Resumed" : "Paused");
            }
            case SPEED_UP -> changeSpeed(f, +1);
            case SPEED_DOWN -> changeSpeed(f, -1);
            case PAN_LEFT -> cam.nudge(-0.18, 0);
            case PAN_RIGHT -> cam.nudge(0.18, 0);
            case PAN_UP -> cam.nudge(0, -0.18);
            case PAN_DOWN -> cam.nudge(0, 0.18);
            case ZOOM_IN -> cam.zoomAt(cam.vx + cam.vw / 2, cam.vy + cam.vh / 2, 1.3);
            case ZOOM_OUT -> cam.zoomAt(cam.vx + cam.vw / 2, cam.vy + cam.vh / 2, 1 / 1.3);
            case FIT_WORLD -> { cam.fit(); toast("Whole world"); }
            case HOME_VIEW -> cam.home();
            case WIND_LEFT -> { source.send("{\"cmd\":\"wind\",\"delta\":-1.0}"); toast("Wind ← (−1 m/s)"); }
            case WIND_RIGHT -> { source.send("{\"cmd\":\"wind\",\"delta\":1.0}"); toast("Wind → (+1 m/s)"); }
            case WARMER -> { source.send("{\"cmd\":\"temp\",\"delta\":1.0}"); toast("Warmer (+1 °C): freezing level rises"); }
            case COLDER -> { source.send("{\"cmd\":\"temp\",\"delta\":-1.0}"); toast("Colder (−1 °C): freezing level drops"); }
            case RESEED -> {
                long seed = new Random().nextInt(1_000_000);
                source.send("{\"cmd\":\"reseed\",\"seed\":" + seed + "}");
                toast("Reseeding world with seed " + seed);
            }
            // ---- v2.3: gases, calendar, clock, spawn type, tornado
            case HUMIDITY_DOWN, HUMIDITY_UP -> gas(f, "humidity", a == Action.HUMIDITY_UP ? 0.05 : -0.05);
            case CO2_DOWN, CO2_UP -> gas(f, "co2", a == Action.CO2_UP ? 40 : -40);
            case AEROSOL_DOWN, AEROSOL_UP -> gas(f, "aerosol", a == Action.AEROSOL_UP ? 0.05 : -0.05);
            case SEASON_PREV, SEASON_NEXT -> {
                if (f == null || !f.hasCalendar()) { toast("This simulation has no calendar"); break; }
                double m = 1 + Math.floorMod(Math.round((f.month - 1 + (a == Action.SEASON_NEXT ? 3 : -3)) * 100), 1200L) / 100.0;
                source.send(String.format(java.util.Locale.ROOT, "{\"cmd\":\"season\",\"month\":%.2f}", m));
                toast((a == Action.SEASON_NEXT ? "Three months on: " : "Three months back: ") + Model.MONTHS[Model.monthIndex(m)] + " (" + Model.seasonOf(m, f.hemisphere) + ")");
            }
            case HOUR_BACK, HOUR_FORWARD -> {
                double h = f == null ? 12 : f.timeOfDay * 24;
                h = ((h + (a == Action.HOUR_FORWARD ? 1 : -1)) % 24 + 24) % 24;
                source.send(String.format(java.util.Locale.ROOT, "{\"cmd\":\"hour\",\"value\":%.3f}", h));
                toast(String.format("Clock set to %02d:%02d", (int) h, (int) ((h - (int) h) * 60)));
            }
            case SPAWN_TYPE -> {
                setSpawnType(spawnType + 1);
                toast(spawnType == 0 ? "A click on the sky seeds a random cloud" : "A click on the sky now seeds " + aOrAn(Model.CLOUD_TYPES[spawnType - 1]));
            }
            case TORNADO -> {
                boolean any = false;
                if (f != null) for (Model.Cloud c : f.clouds) if (c.kind == Model.SUPERCELL) any = true;
                source.send("{\"cmd\":\"tornado\"}");
                toast(any ? "Asked the strongest supercell for a tornado" : "No supercell in the sky — seed one first (cloud type: supercell, then click)");
            }
            case TOGGLE_CLIMATE -> { renderer.showClimate = !renderer.showClimate; toast("Climate panel " + onOff(renderer.showClimate)); }
            // ---- v2.4: the drain pipes overlay, and the pipe size of the settlement on the drains card
            case TOGGLE_DRAINS -> {
                renderer.showDrains = !renderer.showDrains;
                boolean any;
                synchronized (state) { any = !state.drains.isEmpty(); }
                toast(any ? "Drain pipes " + onOff(renderer.showDrains) : "This simulation has no storm drains to show");
            }
            case DRAIN_SMALLER, DRAIN_LARGER -> drainDelta(a == Action.DRAIN_LARGER ? DRAIN_STEP : -DRAIN_STEP);
            case TOGGLE_WIND -> { renderer.showWind = !renderer.showWind; toast("Wind field " + onOff(renderer.showWind)); }
            case TOGGLE_ARROWS -> { renderer.showArrows = !renderer.showArrows; toast("Arrows " + onOff(renderer.showArrows)); }
            case TOGGLE_LABELS -> { renderer.showLabels = !renderer.showLabels; toast("Labels " + onOff(renderer.showLabels)); }
            case TOGGLE_LEGEND -> { renderer.showLegend = !renderer.showLegend; toast("Legend " + onOff(renderer.showLegend)); }
            case TOGGLE_HELP -> renderer.showHelp = !renderer.showHelp;
            case TOGGLE_MINIMAP -> { renderer.showMinimap = !renderer.showMinimap; toast("Minimap " + onOff(renderer.showMinimap)); }
            case TOGGLE_LEFT_PANEL -> renderer.showLeftPanel = !renderer.showLeftPanel;
            case TOGGLE_RIGHT_PANEL -> renderer.showRightPanel = !renderer.showRightPanel;
            case TOGGLE_ALL_PANELS -> {
                boolean any = renderer.showLeftPanel || renderer.showRightPanel || renderer.showMinimap;
                renderer.showLeftPanel = !any;
                renderer.showRightPanel = !any;
                renderer.showMinimap = !any;
            }
            case MENU -> { if (onMenu != null) SwingUtilities.invokeLater(onMenu); }
            case QUICK_SAVE, FULLSCREEN, SCREENSHOT -> { }   // the app shell handles these through actionListener
        }
    }

    /** v2.4: how much one press of the pipe-size keys changes a settlement's drain pipe, px. */
    static final double DRAIN_STEP = 0.5;

    /** Change the pipe size of the settlement on the drains card by {@code delta} px ({@code drains} command; the card follows through {@code frame.drains}). */
    private void drainDelta(double delta) {
        long id = renderer.drainCardId;
        Model.Drain d;
        Model.Hello h;
        synchronized (state) { d = state.drains.get(id); h = state.hello; }
        if (d == null || h == null) { toast("No town with storm drains in view"); return; }
        source.send(String.format(java.util.Locale.ROOT, "{\"cmd\":\"drains\",\"settlement\":%d,\"delta\":%.1f}", id, delta));
        String name = "";
        for (Model.Settlement s : h.settlements) if (s.id == id) name = s.name.isEmpty() ? s.kind : s.name;
        double lo = h.drains != null ? h.drains.sizeMin : 1.5, hi = h.drains != null ? h.drains.sizeMax : 12;
        double now = Model.clamp(d.size + delta, lo, hi);
        toast(String.format(java.util.Locale.ROOT, now == d.size ? "%s: the drain pipe is at its %s (%.1f px)" : "%s: drain pipe %s to %.1f px",
                name, now == d.size ? (delta > 0 ? "largest" : "smallest") : (delta > 0 ? "widened" : "narrowed"), now));
    }

    /** v2.4: give every settlement the pipe size of a mode again ({@code small}, {@code normal}, {@code large} or {@code random}). */
    void drainMode(String mode) {
        boolean any;
        synchronized (state) { any = !state.drains.isEmpty(); }
        if (!any) { toast("This simulation has no storm drains"); return; }
        source.send("{\"cmd\":\"drains\",\"mode\":\"" + mode + "\"}");
        toast(mode.equals("normal") ? "Drains: every town its usual size" : mode.equals("random") ? "Drains: a random size for every town" : "Drains: " + mode + " pipes everywhere");
    }

    /** Send a {@code gas} command and say what the gas will be (clamped as the protocol says). */
    private void gas(Model.Frame f, String name, double delta) {
        source.send(String.format(java.util.Locale.ROOT, "{\"cmd\":\"gas\",\"name\":\"%s\",\"delta\":%s}", name, delta == Math.rint(delta) ? String.valueOf((long) delta) : String.valueOf(delta)));
        Model.Atmosphere at = f == null ? null : f.atmosphere;
        if (at == null) { toast("This simulation has no gases to change"); return; }
        switch (name) {
            case "humidity" -> toast(String.format("Humidity %.0f %%  (%s vapour: %s clouds)", Model.clamp(at.humidity + delta, 0, 1) * 100, delta > 0 ? "more" : "less", delta > 0 ? "more, wetter" : "fewer"));
            case "co2" -> {
                double v2 = Model.clamp(at.co2 + delta, 180, 2000);
                toast(String.format("CO2 %.0f ppm  (greenhouse %+.1f °C)", v2, 3.0 * Math.log(v2 / 420.0) / Math.log(2)));
            }
            default -> toast(String.format("Aerosol %.2f  (%s nuclei: clouds %s)", Model.clamp(at.aerosol + delta, 0, 1), delta > 0 ? "more" : "fewer", delta > 0 ? "last longer, rain later" : "rain sooner"));
        }
    }

    private void changeSpeed(Model.Frame f, int dir) {
        double cur = f != null ? f.speed : 1;
        int idx = 2;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < SPEEDS.length; i++) {
            double d = Math.abs(SPEEDS[i] - cur);
            if (d < best) { best = d; idx = i; }
        }
        idx = Math.max(0, Math.min(SPEEDS.length - 1, idx + dir));
        source.send("{\"cmd\":\"speed\",\"value\":" + SPEEDS[idx] + "}");
        toast("Speed ×" + SPEEDS[idx]);
    }

    private static String onOff(boolean b) { return b ? "on" : "off"; }

    /** "a cumulus", "an altostratus": the toasts name the cloud type with the right article. */
    private static String aOrAn(String noun) { return (!noun.isEmpty() && "aeiou".indexOf(noun.charAt(0)) >= 0 ? "an " : "a ") + noun; }
}
