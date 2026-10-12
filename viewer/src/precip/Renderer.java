package precip;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Draws one frame: the world through the camera ({@link Camera.View}), then the screen-space HUD,
 * minimap, life card and the right-hand timeline panel.
 * <p>
 * Several renderers draw horizontal bands of the same frame in parallel (see
 * {@link ParallelRenderer}); renderer 0 first fills the {@link Shared} caches in
 * {@link #prepareShared}, then every band calls {@link #draw}.
 * <p>
 * <b>Phase C seams (all on the primary renderer, {@code ParallelRenderer.primary()})</b>
 * <ul>
 *   <li><b>Backdrop:</b> {@link #drawBackdrop} is the single place the sky is drawn. Set
 *       {@link #backdropImage} (plus {@link #backdropFit} = {@link #FIT_COVER} / {@link #FIT_CONTAIN} /
 *       {@link #FIT_STRETCH} and {@link #backdropDim} 0..1) to show an image instead of the gradient
 *       and stars; sun, moon and the day/night dimming still apply. null restores the sky.</li>
 *   <li><b>Panels:</b> {@link #showRightPanel} (docked timeline + event log; the world viewport grows
 *       into its column when hidden, see {@link #rightPanelWidth}), {@link #showLeftPanel} (floating HUD
 *       stats + legend as one panel), {@link #showMinimap}. {@link #showLegend} folds the legend inside
 *       the left panel.</li>
 *   <li><b>Help text:</b> {@link #helpRows} is the key list shown by the help overlay; replace it
 *       when bindings change.</li>
 *   <li>No static mutable state: a renderer (and its {@link Shared} caches) belongs to one view and
 *       is garbage once that view is disposed.</li>
 * </ul>
 */
final class Renderer {
    static final int PANEL_W = 400;
    private static final int LABEL_W = 104;
    static final int FIT_COVER = 0, FIT_CONTAIN = 1, FIT_STRETCH = 2;

    // ---- UI state
    volatile boolean showWind = true, showArrows = true, showLabels = true, showLegend = true, showHelp = false;
    volatile boolean showRightPanel = true, showLeftPanel = true, showMinimap = true;
    /** v2.3: the Climate panel (date, clock, temperature breakdown, gases, spawn rate, type weights) at the top right of the world view. */
    volatile boolean showClimate = true;
    /** v2.3: the cloud type a click on the sky asks for, as shown in the Climate panel ("random" = the simulation chooses). */
    volatile String spawnTypeLabel = "random";
    /** Canvas rectangle of the Climate panel as last drawn (null when it is not shown). */
    volatile Rectangle2D climateRect;
    /**
     * Where the HUD puts its small buttons on the Climate panel: {x, w, yDate, yClock, yHumidity, yCO2, yAerosol, ySpawnType}
     * (canvas px, the baseline of each row; NaN = that row is not shown). null when the panel is not shown.
     */
    volatile double[] climateRows;
    /** v2.4: the cutaway pipe overlay under every settlement (the Drains key); and the drains card under the Climate panel. */
    volatile boolean showDrains = DrainPainter.FORCE_OVERLAY, showDrainCard = true;
    /** Canvas rectangle of the drains card as last drawn (null when it is not shown), and the id of the settlement it is about (-1 = none). */
    volatile Rectangle2D drainRect;
    volatile long drainCardId = -1;
    volatile String toast;
    volatile long toastUntilNanos; // System.nanoTime() deadline
    /** Optional image drawn instead of the sky gradient (see class comment). */
    volatile BufferedImage backdropImage;
    volatile int backdropFit = FIT_COVER;
    volatile double backdropDim = 0.2;
    /** Darken the backdrop image at night and warm it at sunset (off = the picture is shown as it is, only dimmed). */
    volatile boolean backdropTint = true;
    /** Draw the built-in "Simulation problem" box; the app shell turns it off and shows its own dialog. */
    volatile boolean showErrorBox = true;
    /** A world shown only as scenery (menu backdrop, save thumbnails): no freezing-level line, no annotations. */
    volatile boolean decorative;
    /** Darken the world view while the camera is under a cloud ({@link CloudShade}); {@link #shadeStrength} 0..1 scales it. */
    volatile boolean shadeOn = true;
    volatile double shadeStrength = 0.65;
    /** Still pictures only: show the dimming this many seconds into a transition from "not under a cloud" (-1 = settled). */
    volatile double shadeStillSeconds = -1;
    /** The eased cover level 0..1 of the last frame prepared (diagnostics, tests). */
    volatile double shadeLevel;
    /**
     * Settings > Graphics "Epilepsy-safe lightning": no lightning light at all, and the bolts are drawn dim with a slow ease in
     * and out ({@link Lightning.Safe}). One switch for every view (sandbox, lesson modes, menu backdrop, thumbnails), like
     * {@link CloudPainter#quality}; {@code AppShell.applySettings} sets it.
     */
    static volatile boolean epilepsySafe;
    /** Space the surrounding screen takes from the canvas (logical px): the world viewport is what is left (see ParallelRenderer.layout). */
    volatile double insetLeft, insetTop, insetRight, insetBottom;
    /** Width of a strip at the top right of the world view that labels keep clear (the shell's menu button and save indicator). */
    volatile double hudReserveW;
    /**
     * Slide positions of the three panels, 0 = fully hidden .. 1 = fully shown. Only used while
     * {@link #animatePanels} is on (the live game view eases them toward the show flags, see
     * {@link #stepPanels}); otherwise the panels simply follow the flags.
     */
    volatile double rightSlide = 1, leftSlide = 1, miniSlide = 1;
    volatile boolean animatePanels;

    double rightShown() { return animatePanels ? rightSlide : showRightPanel ? 1 : 0; }

    double leftShown() { return animatePanels ? leftSlide : showLeftPanel ? 1 : 0; }

    double miniShown() { return animatePanels ? miniSlide : showMinimap ? 1 : 0; }

    /** Width the docked right panel takes from a canvas of the given width right now (follows the slide). */
    double rightPanelWidthNow(double canvasW) { return canvasW >= 760 ? Math.round(PANEL_W * ease(rightShown())) : 0; }

    /** Jump the slides to the show flags (no animation). */
    void snapPanels() {
        rightSlide = showRightPanel ? 1 : 0;
        leftSlide = showLeftPanel ? 1 : 0;
        miniSlide = showMinimap ? 1 : 0;
    }

    /** Move the slides toward the show flags; returns true while something is still moving. */
    boolean stepPanels(double dt) {
        double k = Math.min(1, Math.max(0, dt) / 0.18);
        double r = slide(rightSlide, showRightPanel, k), l = slide(leftSlide, showLeftPanel, k), m = slide(miniSlide, showMinimap, k);
        boolean moving = r != rightSlide || l != leftSlide || m != miniSlide;
        rightSlide = r; leftSlide = l; miniSlide = m;
        return moving;
    }

    private static double slide(double cur, boolean show, double k) {
        double target = show ? 1 : 0;
        if (cur == target) return cur;
        // linear travel; the smooth-step look is applied where the slide is used
        return target > cur ? Math.min(1, cur + k) : Math.max(0, cur - k);
    }

    private static double ease(double x) { return x * x * (3 - 2 * x); }
    volatile String[][] helpRows = DEFAULT_HELP;

    /** The help rows for the default key bindings. */
    static final String[][] DEFAULT_HELP = KeyMap.helpRows(KeyMap.defaults());

    /** Width of the docked right panel for a canvas of the given width. */
    static double rightPanelWidth(boolean show, double canvasW) { return show && canvasW >= 760 ? PANEL_W : 0; }

    // ---- layout info for hit tests (filled during render; canvas = logical window coordinates)
    volatile int worldW = 3840, worldH = 1080;
    double groundY = 1000;
    /** The view of the last frame drawn: use it to convert canvas points to world points. */
    volatile Camera.View view;
    /** Canvas x where the right panel starts (= canvas width when it is hidden). */
    volatile double panelX = 1280;
    volatile Rectangle2D minimapRect;
    /** Canvas y of the bottom edge of the left panel as last drawn (0 when hidden). */
    volatile double leftPanelBottom = 0;
    private volatile List<long[]> timelineRows = new ArrayList<>(); // {id, y0, y1}
    volatile int tlTop, tlBottom;
    volatile int timelineScroll;       // rows scrolled up from the newest
    volatile Rectangle2D cardRect, cardClose;
    private volatile Map<Long, double[]> drawnClouds = new HashMap<>(); // id -> interpolated x,y (published per frame)

    static final Color[] STAGE_COL = {
            new Color(168, 186, 206), // forming
            new Color(88, 206, 186),  // growing
            new Color(244, 204, 96),  // mature
            new Color(82, 132, 250),  // precipitating
            new Color(170, 124, 190)  // dissipating
    };
    static final String[] STAGE_LABEL = {"forming", "growing", "mature", "precipitating", "dissipating"};

    static Color arrowColor(String kind) {
        return switch (kind) {
            case "updraft" -> new Color(255, 146, 70);
            case "downdraft" -> new Color(90, 170, 255);
            case "fall" -> new Color(140, 215, 255);
            case "growth" -> new Color(200, 140, 255);
            default -> new Color(245, 245, 245);
        };
    }

    static Color precipColor(String t) {
        return switch (t) {
            case "rain" -> new Color(90, 160, 255);
            case "snow" -> new Color(240, 244, 255);
            case "mixed" -> new Color(150, 220, 230);
            case "graupel" -> new Color(200, 200, 215);
            default -> new Color(120, 120, 130);
        };
    }

    private static final Font F_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
    private static final Font F_NORM = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private static final Font F_BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    private static final Font F_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 14);
    private static final Font F_BIG = new Font(Font.SANS_SERIF, Font.BOLD, 18);
    private static final Font F_MONO = new Font(Font.MONOSPACED, Font.PLAIN, 11);

    // optional section profiler (--bench --profile); null = off
    static final String[] PROF_NAMES = {"sky", "terrain", "wind-field", "objects", "rainshafts", "particles-bk",
            "clouds", "particles-fg", "arrows", "labels", "hud+legend", "panel", "overlays",
            "streamlines", "rain-streaks", "lightning", "frame-setup", "cars+tornado", "water+drains"};
    long[] profNs;
    private long profT;

    private void pm(int slot) {
        if (profNs == null) return;
        long n = System.nanoTime();
        profNs[slot] += n - profT;
        profT = n;
    }

    // optional direct pixel access to the render target (our own opaque int buffers)
    private final Direct direct = new Direct();
    private BufferedImage target;
    private boolean directOn;

    /** Declare that the Graphics passed to draw() draws into this image (enables fast paths). */
    void setTarget(BufferedImage img) {
        if (img != target) {
            target = img;
            direct.attach(img);
        }
    }

    // per-frame snapshot of the shared state (taken under the lock, drawn without it)
    private Model.Frame curFrame, prevFrame;
    private Model.Hello hello;
    private Model.Obj[] objsSnap = new Model.Obj[0];
    private Model.Ground groundSnap = new Model.Ground();
    private long coverVer, worldVer;
    private long selId = -1;
    private ViewerState.Life selLife;
    private String snapError, snapStatus = "";
    private final List<ViewerState.Life> livesSnap = new ArrayList<>();
    private final List<String> eventsSnap = new ArrayList<>();
    /** v2.3: the tornadoes seen so far (copies: the reader thread keeps updating the originals). */
    private final List<ViewerState.TornadoRec> tornadoSnap = new ArrayList<>();
    /** v2.4: the standing water, the flood states and the drains as the reader thread last published them. */
    private List<Model.Body> waterSnap = List.of();
    private List<Model.Flood> floodSnap = List.of();
    private List<Model.Run> flowSnap = List.of();
    private Map<Long, Model.Drain> drainSnap = Map.of();
    private String drainModeSnap = "";
    /** Phase O (OW): the wreck state (pieces in the air, airborne buildings) as the reader thread last published it. */
    private Wreck.Snap wreckSnap = Wreck.Snap.NONE;
    private final DrainCard drainCard = new DrainCard();
    /** The settlement the drains card is about in this frame (null = no card). */
    private Model.Settlement drainPick;
    private static final int MAX_LOG_LINES = (190 - 30) / 14;

    // per-frame view + lighting (copied from Shared at the start of draw)
    private Shared sh;
    private Camera.View v;
    private AffineTransform scrTx = new AffineTransform(), wTx = new AffineTransform();
    private Lit lit = new Lit(0.45, 0);
    private double day = 1, dusk = 0, light = 1, overcast = 0, anim;
    private Color skyTop = Color.BLACK, skyBot = Color.BLACK;
    private int panelXi = 1280, canvasHi = 720, vwI = 1280, vhI = 720;
    private double vRight = 1280, vBottom = 720;
    private final float[] stars;
    // this band's vertical extent in world and canvas units: lets a band skip what it cannot show
    private double bandWy0, bandWy1, bandSy0, bandSy1;
    private int bandDy0, bandDy1;      // ... and in device rows
    /** Frame clock (System.nanoTime of this frame); every time-based cache policy uses it. */
    private long clock;

    Renderer() {
        Random r = new Random(7);
        stars = new float[260 * 3];
        for (int k = 0; k < 260; k++) {
            stars[k * 3] = r.nextFloat();
            stars[k * 3 + 1] = (float) Math.pow(r.nextFloat(), 1.4);
            stars[k * 3 + 2] = r.nextFloat();
        }
    }

    Model.Frame snapshotFrame() { return curFrame; }

    Model.Hello snapshotHello() { return hello; }

    /** Copy the shared state this frame needs. Caller holds the ViewerState lock. */
    void snapshotLocked(ViewerState st) {
        curFrame = st.cur;
        prevFrame = st.prev;
        hello = st.hello;
        objsSnap = st.objects;
        groundSnap = st.ground;
        coverVer = st.coverVersion;
        worldVer = st.worldVersion;
        if (hello != null) { worldW = hello.w; worldH = hello.h; groundY = Math.max(hello.groundY, hello.terrain.maxY); }
        selId = st.selectedId;
        selLife = st.byId.get(selId);
        snapError = st.error;
        snapStatus = st.status;
        waterSnap = st.water;
        floodSnap = st.flood;
        flowSnap = st.flow;
        drainSnap = st.drains;
        drainModeSnap = st.drainMode;
        wreckSnap = st.wreck.snap();          // Phase O (OW)
        livesSnap.clear();
        livesSnap.addAll(st.lives);
        eventsSnap.clear();
        java.util.Iterator<String> it = st.events.descendingIterator();
        while (it.hasNext() && eventsSnap.size() < MAX_LOG_LINES) eventsSnap.add(it.next());
        java.util.Collections.reverse(eventsSnap);
        tornadoSnap.clear();
        for (ViewerState.TornadoRec r : st.tornadoLog) {
            ViewerState.TornadoRec c = new ViewerState.TornadoRec();
            c.id = r.id; c.cloud = r.cloud; c.cloudName = r.cloudName; c.stage = r.stage; c.startT = r.startT; c.touchT = r.touchT; c.endT = r.endT;
            c.peakWind = r.peakWind; c.peakEf = r.peakEf; c.pathX0 = r.pathX0; c.pathX1 = r.pathX1; c.peakWidth = r.peakWidth; c.alive = r.alive;
            tornadoSnap.add(c);
        }
    }

    // =====================================================================================
    // shared preparation (renderer 0, once per frame, before the bands)

    static boolean liveTrees(double deviceScale, double dev) { return TileLayer.bucket(deviceScale) >= 0.5 * dev * 0.999; }

    /** Static ground scenery of one tile: back-row buildings, the terrain, front-row buildings, far-zoom trees. */
    static void paintGround(Graphics2D g, Scene sc, double x0, double y0, double x1, double y1, double scale, double dev) {
        paintGround(g, sc, x0, y0, x1, y1, scale, dev, null);
    }

    /** @param wreck Phase O: which buildings have pieces in the air (their rubble piles are drawn part-built); null = none */
    static void paintGround(Graphics2D g, Scene sc, double x0, double y0, double x1, double y1, double scale, double dev, Wreck.Snap wreck) {
        final byte[] wind = sc.wind;                 // (the wind level the rain beads on the panes are smeared with)
        for (int i = 0; i < sc.objects.length; i++) {
            Model.Obj o = sc.objects[i];
            if (!o.building || o.layer != 1 || o.x + o.w / 2 + 70 < x0 || o.x - o.w / 2 - 70 > x1) continue;
            AffineTransform old = g.getTransform();
            g.translate(o.x, o.y);
            g.scale(0.9, 0.9);
            g.translate(-o.x, -o.y);
            BuildingPainter.paint(g, o, sc.lit, 0.36, wind == null ? 0 : wind[i], Life.level(o, sc.lit, wreck != null && wreck.sheltered(o.x)));
            g.setTransform(old);
        }
        Rubble.paint(g, sc, wreck, x0, x1, true);                     // Phase O (OW4): the back row's rubble piles, behind the terrain line and the front row
        TerrainPainter.paintTerrain(g, sc, x0, y0, x1, y1, scale);
        for (int i = 0; i < sc.objects.length; i++) {
            Model.Obj o = sc.objects[i];
            if (!o.building || o.layer == 1 || o.x + o.w / 2 + 70 < x0 || o.x - o.w / 2 - 70 > x1) continue;
            BuildingPainter.paint(g, o, sc.lit, 0, wind == null ? 0 : wind[i], Life.level(o, sc.lit, wreck != null && wreck.sheltered(o.x)));
        }
        TerrainPainter.paintRoads(g, sc, x0, y0, x1, y1, scale);      // v2.3: the street runs in front of the houses
        RoadWear.paint(g, sc, x0, x1, scale);                         // Phase O (OW5): potholes, cracks and torn asphalt beside a wrecked building
        Rubble.paint(g, sc, wreck, x0, x1, false);                    // Phase O (OW4): the front row's rubble piles (nothing below damage 0.30)
        if (!liveTrees(scale, dev)) {
            for (Model.Obj o : sc.objects) {
                if (!o.kind.equals("tree") || o.x + o.w < x0 || o.x - o.w > x1) continue;
                BuildingPainter.paintTree(g, o, sc.lit, 0, 0);
            }
        }
    }

    /** Fill the shared caches for this frame. Called on renderer 0 after {@link #snapshotLocked}. */
    void prepareShared(Shared s, Camera.View view, long nowNanos, long clockNanos) {
        s.view = view;
        // Settings > Advanced: read once per frame; the bands and the painters only ever see this snapshot
        final Advanced adv = Advanced.cur;
        final boolean restyle = adv != s.adv;                // (publish replaces the instance only when a value changed)
        if (restyle) { s.adv = adv; s.advVer++; s.funnelCache.clear(); }
        prepareBackdrop(s, view);
        Model.Frame f = curFrame;
        double cover = 0;
        if (f != null) {
            for (Model.Cloud c : f.clouds) {
                if (c.x + c.w / 2 < view.wx0 || c.x - c.w / 2 > view.wx1) continue;
                cover += c.w * c.opacity * c.form * (0.3 + c.darkness);
            }
            cover /= Math.max(600, view.wx1 - view.wx0) * 1.6;
        }
        double tod = f == null ? 0.45 : f.timeOfDay;
        // quantise the overcast a little so lighting does not shimmer as clouds drift in and out of view
        cover = Math.round(Math.min(0.55, cover * 0.5) * 12) / 12.0;
        s.lit = new Lit(tod, cover, adv.noTint);
        s.cloudLight = CloudPainter.Light.of(tod, cover * 0.5, adv.noTint);
        s.drawClouds.clear();
        s.drawCars.clear();
        s.funnels.clear();
        // under-cloud dimming: one eased level per frame, shared by every band
        boolean shading = shadeOn && !decorative && shadeStrength > 0.005;
        s.shade.advance(shading ? CloudShade.target(f, view) : 0, clockNanos, s.async, shadeStillSeconds);
        shadeLevel = s.shade.level;
        s.shadeAlpha = shading ? s.shade.level * Model.clamp(shadeStrength, 0, 1) * CloudShade.MAX_ALPHA : 0;
        s.cloudBoxes = NO_BOXES;
        s.bolts = null;
        // v2.4: the water of this frame, eased once here so that every band draws the same level; the flood and drain state the bands read
        s.flood = floodSnap; s.drains = drainSnap; s.drainMode = drainModeSnap;
        s.showDrains = showDrains; s.showDrainCard = showDrainCard;
        s.wreck = wreckSnap;                  // Phase O (OW): the pieces in the air and the airborne bits of this frame
        WaterPainter.prepare(s.water, hello, waterSnap, f, clockNanos, s.async && nowNanos >= 0);
        FlowPainter.prepare(s.flow, hello, flowSnap, s.water, view, f, clockNanos, s.async && nowNanos >= 0);      // v2.4.1 (after the bodies: a ribbon ends at a shoreline)
        if (hello == null) { s.scene = null; return; }
        double fz = f != null ? f.freezingY : hello.groundY * 0.5;
        // "No snow": the painters get the objects without their snow (a pond keeps it: that is ice, not painted snow)
        final Model.Obj[] objs = adv.noSnow ? snowless(s, objsSnap) : objsSnap;
        // the wind on the wet panes: the coarse level each building's still beads are painted with. Looked at about once a
        // second and only while zoomed in far enough to show beads; a change repaints the tiles of that building alone. The
        // level comes from the wind averaged over a few seconds of simulated time and has a wide dead band, so the gusts
        // do not flip it (BuildingPainter.windLevel). The first look at a world takes the wind as it is.
        if (s.objWind.length != objs.length || s.windHello != hello) {
            s.objWind = new byte[objs.length]; s.objWindAvg = new float[objs.length]; s.windClock = Long.MIN_VALUE; s.windT = Double.NaN; s.windHello = hello;
        }
        if (f != null && view.ws >= BuildingPainter.BEAD_MIN_PX
                && (!s.async || s.windClock == Long.MIN_VALUE || clockNanos - s.windClock > WIND_CHECK_NANOS)) {
            final boolean first = s.windClock == Long.MIN_VALUE;
            s.windClock = clockNanos;
            final double dt = f.t - s.windT, mixIn = !(dt >= 0 && dt < 60) ? 1 : 1 - Math.exp(-dt / BuildingPainter.WIND_SMOOTH_S);
            s.windT = f.t;
            final byte[] was = s.objWind;
            final float[] avg = s.objWindAvg;
            byte[] next = null;
            for (int i = 0; i < objs.length; i++) {
                Model.Obj o = objs[i];
                if (!o.building) continue;
                avg[i] += (float) ((f.windU(o.x, o.y - o.h * 0.5) - avg[i]) * mixIn);
                int lv = BuildingPainter.windLevel(avg[i], was[i]);
                if (lv != was[i]) { if (next == null) next = was.clone(); next[i] = (byte) lv; if (!first) s.windLevelChanges++; }
            }
            if (next != null) { s.objWind = next; s.windMoved = true; }
        }
        s.scene = new Scene(hello, objs, groundSnap, s.lit, fz, adv.noSnow, s.objWind);
        s.liveObjs = objs;

        // ---- baked scenery: re-bake when the world, the lighting or the snow / wet cover changed visibly
        // (and at once, without the debounce below, when an Advanced option changed: the tiles hold finished pixels)
        boolean stale = s.baked == null || worldVer != s.bakedWorld || restyle;
        s.dirtyX0 = -1e18; s.dirtyX1 = 1e18;
        if (stale) { s.ridgeVersion++; s.sceneVersion++; s.bakedGround = new int[0]; s.bakedObj = new int[0]; s.windMoved = false; coverLevels(s, true); }
        else {
            boolean drift = s.lit.differs(s.baked.lit) || Math.abs(fz - s.baked.freezingY) > 3;
            boolean cov = coverVer != s.bakedCover || s.windMoved;
            long since = clockNanos - s.bakedClock;
            if (!s.async ? drift : drift && since > 1_000_000_000L) {
                stale = true;
                s.ridgeVersion++; s.sceneVersion++;
                s.windMoved = false;
                coverLevels(s, true);
            } else if (!s.async ? cov : cov && since > (s.wreck.hot() ? WRECK_BAKE_NANOS : 700_000_000L)) {      // (Phase O: a piece just torn off waits for its hole)
                // snow / wet cover moved (or the wind on wet panes): repaint only the stretch of ground where the painted level changed
                s.bakedCover = coverVer;
                s.bakedClock = clockNanos;
                s.windMoved = false;
                if (coverLevels(s, false)) { stale = true; s.sceneVersion++; }
            }
        }
        if (stale) { s.baked = s.scene; s.bakedClock = clockNanos; s.bakedCover = coverVer; s.bakedWorld = worldVer; s.bakedWreck = s.wreck; }
        final Scene b = s.baked;
        final Wreck.Snap bw = s.bakedWreck;
        final double dev = view.dev;
        s.far.minY = TerrainPainter.ridgeTop(b, 0) - 30; s.far.maxY = b.groundY + 6;
        s.near.minY = TerrainPainter.ridgeTop(b, 1) - 20; s.near.maxY = b.groundY + 6;
        double scTop = 1e9, scX0 = 1e9, scX1 = -1e9, gTop = b.terrain.minY - 14;
        for (Model.Obj o : b.objects) {
            if (o.kind.equals("mountain") || o.kind.equals("hill")) {
                scTop = Math.min(scTop, o.y - o.h * 1.4 - 20);
                scX0 = Math.min(scX0, o.x - o.w / 2 - 10); scX1 = Math.max(scX1, o.x + o.w / 2 + 10);
            } else if (o.building) gTop = Math.min(gTop, o.y - o.h * 1.6 - 50);
            else if (o.kind.equals("tree")) gTop = Math.min(gTop, o.y - o.h - 12);
        }
        s.far.prepare((g, x0, y0, x1, y1, sc) -> TerrainPainter.paintRidge(g, b, 0, x0, y0, x1, y1, sc), s.ridgeVersion, worldVer, view);
        s.near.prepare((g, x0, y0, x1, y1, sc) -> TerrainPainter.paintRidge(g, b, 1, x0, y0, x1, y1, sc), s.ridgeVersion, worldVer, view);
        s.scenery.minY = scTop; s.scenery.maxY = b.groundY + 8; s.scenery.minX = scX0; s.scenery.maxX = scX1;
        if (scX1 > scX0) s.scenery.prepare((g, x0, y0, x1, y1, sc) -> TerrainPainter.paintScenery(g, b, x0, y0, x1, y1, sc), s.sceneVersion, worldVer, view, s.dirtyX0, s.dirtyX1);
        s.ground.minY = gTop;
        s.ground.prepare((g, x0, y0, x1, y1, sc) -> paintGround(g, b, x0, y0, x1, y1, sc, dev, bw), s.sceneVersion, worldVer, view, s.dirtyX0, s.dirtyX1);

        if (f == null) return;
        // ---- clouds: images + the draw list (interpolated between the last two frames)
        s.clouds.prepare(f, s.scene, s.cloudLight, view, clockNanos);
        double alpha = 1;
        s.extrap = 0;
        if (nowNanos >= 0 && prevFrame != null) {
            double span = (f.recvNanos - prevFrame.recvNanos) / 1e9;
            double since = (nowNanos - f.recvNanos) / 1e9;
            alpha = span > 1e-4 ? Model.clamp(since / span, 0, 1) : 1;
            s.extrap = f.paused ? 0 : Model.clamp(since, 0, 0.1) * f.speed;
        }
        for (Model.Cloud c : f.clouds) {
            Shared.DrawCloud dc = new Shared.DrawCloud();
            dc.c = c;
            dc.x = c.x; dc.y = c.y;
            Model.Cloud p = prevFrame != null && alpha < 1 ? prevFrame.cloud(c.id) : null;
            if (p != null) { dc.x = p.x + (c.x - p.x) * alpha; dc.y = p.y + (c.y - p.y) * alpha; }
            dc.e = s.clouds.get(c.id);
            s.drawClouds.add(dc);
        }
        // high clouds are behind low ones; thin cirrus is furthest back
        // (v2.3: a supercell stands in front of them all - the pale remains of a sheet drifting across its dark body
        // would read as holes in the storm)
        s.drawClouds.sort((a, c2) -> {
            boolean sa = a.c.kind == Model.SUPERCELL, sb = c2.c.kind == Model.SUPERCELL;
            return sa != sb ? (sa ? 1 : -1) : Double.compare(a.y, c2.y);
        });
        // v2.3 cars: interpolated like the clouds; the lane driving left is the far one, so it is drawn first
        if (!f.cars.isEmpty()) {
            Map<Long, Model.Car> before = null;
            if (prevFrame != null && alpha < 1 && !prevFrame.cars.isEmpty()) {
                before = new HashMap<>();
                for (Model.Car c : prevFrame.cars) before.put(c.id, c);
            }
            for (Model.Car c : f.cars) {
                if (c.x < view.wx0 - 150 || c.x > view.wx1 + 150) continue;
                Shared.DrawCar dc = new Shared.DrawCar();
                dc.c = adv.noSnow && c.snow > 0 ? snowless(c) : c;
                dc.x = c.x; dc.y = c.y; dc.angle = c.angle; dc.lift = c.lift; dc.spin = c.spin;
                dc.wind = f.windU(c.x, c.y - 6);
                Model.Car q = before == null ? null : before.get(c.id);
                if (q != null && Math.abs(q.x - c.x) < 80) {
                    dc.x = q.x + (c.x - q.x) * alpha; dc.y = q.y + (c.y - q.y) * alpha; dc.angle = q.angle + (c.angle - q.angle) * alpha;
                    dc.lift = q.lift + (c.lift - q.lift) * alpha; dc.spin = q.spin + (c.spin - q.spin) * alpha;
                }
                s.drawCars.add(dc);
            }
            s.drawCars.sort((a, c2) -> Integer.compare(a.c.dir, c2.c.dir));
        }
        // v2.3 tornado funnels: one image each, painted once here so that every band draws exactly the same funnel
        for (Model.Tornado t : f.tornadoes) {
            double reach = t.topWidth + Math.abs(t.lean) + t.width * 3 + 80;
            if (t.x + reach < view.wx0 || t.x - reach > view.wx1) continue;
            long sig = f.frame * 1_000_003L + Double.doubleToLongBits(view.ws) * 31 + view.wtx * 7919L + view.wty * 104729L
                    + Math.round(s.lit.light * 400) * 131 + Math.round(s.lit.day * 200) + Double.doubleToLongBits(t.phase);
            TornadoPainter.Funnel fn = s.funnelCache.get(t.id);
            if (fn == null || fn.sig != sig) {
                fn = TornadoPainter.render(t, s.lit, view);
                if (fn == null) { s.funnelCache.remove(t.id); continue; }
                fn.sig = sig;
                s.funnelCache.put(t.id, fn);
            }
            s.funnels.add(fn);
        }
        if (f.tornadoes.isEmpty() && !s.funnelCache.isEmpty()) s.funnelCache.clear();
        // "Don't render what you can't see": the clouds that will be copied over the particles pixel for pixel (see drawCloud)
        s.occN = 0;
        if (adv.cullHidden && f.particleCount > 0) {
            int nc = s.drawClouds.size();
            if (s.occImg.length < nc) {
                s.occImg = new CloudPainter.Image[nc]; s.occPrev = new CloudPainter.Image[nc];
                s.occX = new int[nc]; s.occY = new int[nc]; s.occPX = new int[nc]; s.occPY = new int[nc];
            }
            for (Shared.DrawCloud dc : s.drawClouds) {
                SharedClouds.Entry e = dc.e;
                CloudPainter.Image cur = e == null ? null : e.drawCur, prev = e == null ? null : e.drawPrev;
                if (cur == null || cur.px == null || cur.scale != view.ws) continue;
                // mid-fade the two images are blended (Direct.overFade): a pixel opaque in both stays opaque, any other does not count
                if (prev != null && (prev.px == null || prev.scale != view.ws)) continue;
                if (cur.opaqueSat == null) cur.buildOpaque();       // (an image rendered while the option was off)
                if (prev != null && prev.opaqueSat == null) prev.buildOpaque();
                if (cur.opaqueCells == 0 || (prev != null && prev.opaqueCells == 0)) continue;
                s.occImg[s.occN] = cur;
                s.occX[s.occN] = (int) Math.floor(view.ws * (dc.x + cur.relX) + view.wtx + 0.5);
                s.occY[s.occN] = (int) Math.floor(view.ws * (dc.y + cur.relY) + view.wty + 0.5);
                s.occPrev[s.occN] = prev;
                if (prev != null) {
                    s.occPX[s.occN] = (int) Math.floor(view.ws * (dc.x + prev.relX) + view.wtx + 0.5);
                    s.occPY[s.occN] = (int) Math.floor(view.ws * (dc.y + prev.relY) + view.wty + 0.5);
                }
                s.occN++;
            }
        }
        if (s.shadeAlpha > SHADE_MIN) {
            double[] bx = new double[s.drawClouds.size() * 4];
            int nb = 0;
            for (Shared.DrawCloud dc : s.drawClouds) {
                if (dc.c.form <= 0.02) continue;
                double[] cb = SharedClouds.bounds(dc.c);
                double ox = dc.x - dc.c.x, oy = dc.y - dc.c.y;
                bx[nb++] = cb[0] + ox; bx[nb++] = cb[1] + oy; bx[nb++] = cb[2] + ox; bx[nb++] = cb[3] + oy;
            }
            s.cloudBoxes = nb == bx.length ? bx : Arrays.copyOf(bx, nb);
        }
        // lightning: local lights with ray-traced shadows (null on every frame without a bolt)
        s.bolts = Lightning.prepare(s.lightning, f, objsSnap, hello.terrain, s.drawClouds, view, clockNanos, s.async, epilepsySafe,
                s.lit.light * (1 - s.shadeAlpha), worldVer, adv, s.advVer);
        prepareThumbs(s, clockNanos);
    }

    /** The objects with their snow taken off ("No snow"); made once per object list, which is replaced whenever a state changes. */
    private static Model.Obj[] snowless(Shared s, Model.Obj[] src) {
        if (s.snowSrc == src) return s.snowObjs;
        Model.Obj[] out = new Model.Obj[src.length];
        for (int i = 0; i < src.length; i++) {
            Model.Obj o = src[i];
            out[i] = o.snow > 0 && !o.kind.equals("pond") ? o.withState(o.sway, 0, o.wet, o.w, o.h) : o;
        }
        s.snowSrc = src;
        s.snowObjs = out;
        return out;
    }

    private static Model.Car snowless(Model.Car c) {
        Model.Car n = new Model.Car();
        n.id = c.id; n.x = c.x; n.y = c.y; n.angle = c.angle; n.speed = c.speed; n.lift = c.lift; n.spin = c.spin; n.wet = c.wet;
        n.dir = c.dir; n.variant = c.variant; n.state = c.state; n.color = c.color; n.lights = c.lights; n.wipers = c.wipers;
        return n;
    }

    /**
     * Compare the cover levels (as the painters quantise them) of the live scene with what the tiles
     * were last painted with; remember the new levels and the world x-range that changed.
     * @return true when something visible changed (always true for {@code all})
     */
    private static boolean coverLevels(Shared s, boolean all) {
        Scene sc = s.scene;
        Model.Ground gr = sc.ground;
        int n = Math.max(gr.snow.length, gr.wet.length);
        if (s.bakedGround.length != n) { s.bakedGround = new int[n]; all = true; }
        if (s.bakedObj.length != sc.objects.length) { s.bakedObj = new int[sc.objects.length]; all = true; }
        double x0 = 1e18, x1 = -1e18;
        for (int i = 0; i < n; i++) {
            double sn = i < gr.snow.length && !sc.noSnow ? gr.snow[i] : 0, wt = i < gr.wet.length ? gr.wet[i] : 0;
            int lv = (int) Math.round(sn * 12) * 16 + (int) Math.round(wt * 5);
            if (lv != s.bakedGround[i]) {
                s.bakedGround[i] = lv;
                x0 = Math.min(x0, (i - 1.5) * gr.step); x1 = Math.max(x1, (i + 1.5) * gr.step);
            }
        }
        for (int i = 0; i < sc.objects.length; i++) {
            Model.Obj o = sc.objects[i];
            if (!(o.building || o.kind.equals("mountain") || o.kind.equals("hill") || (o.damage > 0 && o.kind.equals("tree")))) continue;
            // (storm damage is part of the level, so a damaged building is repainted where it stands)
            int lv = (int) Math.round(o.snow * 12) * 16 + (int) Math.round(o.wet * 6) + (int) Math.round(o.damage * 24) * 4096;
            // (the wind level the rain beads are smeared with: only a building wet enough to have beads shows it)
            if (sc.wind != null && o.building && Math.round(o.wet * 6) > 0) lv += (sc.wind[i] + BuildingPainter.WIND_LEVEL_MAX + 1) << 20;
            // Phase O bake-key bits (PLAN "Bake key bits"): life level 0-4 << 24, sheltered << 27, airborne << 28
            if (o.building) {
                // HOOK OH (Life.level), wired in OI: who is outdoors is part of the still picture (BuildingPainter.paint(..., life))
                lv |= Life.level(o, sc.lit, s.wreck.sheltered(o.x)) << 24;
                if (s.wreck.sheltered(o.x)) lv |= 1 << 27;                          // a grounded tornado is near: everybody indoors
                if (s.wreck.airborne(o.id)) lv |= 1 << 28;                          // OW4: its piles are drawn part-built while its pieces fly
            }
            if (lv != s.bakedObj[i]) {
                final int diff = lv ^ s.bakedObj[i];
                if (!all && (diff & ((1 << 20) - 1)) == 0 && (diff & (15 << 20)) != 0) s.windRepaints++;      // (diagnostics: nothing but the wind level moved)
                if (!all && diff == 1 << 28) s.airRepaints++;                       // (diagnostics: repainted for the airborne bit alone)
                s.bakedObj[i] = lv;
                // (Phase O: a building with rubble piles is repainted as far as they reach, and so is its worn road)
                final double pad = o.w / 2 + 20 + (o.building && (Rubble.has(o) || (diff & (1 << 28)) != 0) ? Rubble.reach(o) : 0);
                x0 = Math.min(x0, o.x - pad); x1 = Math.max(x1, o.x + pad);
            }
        }
        if (all) { s.dirtyX0 = -1e18; s.dirtyX1 = 1e18; return true; }
        if (x1 < x0) return false;
        s.dirtyX0 = x0; s.dirtyX1 = x1;
        return true;
    }

    private static final CloudPainter.Light THUMB_LIGHT = CloudPainter.Light.of(0.40, 0);
    private static final double[] NO_BOXES = new double[0];
    /** How often the wind on the wet panes is compared with the level their beads were painted at (see prepareShared). */
    private static final long WIND_CHECK_NANOS = 1_000_000_000L;
    /** Phase O (OW2): while a freshly torn piece is still held in place, a changed building is re-baked after this long instead of 0.7 s (the piece hides the wait). */
    private static final long WRECK_BAKE_NANOS = 150_000_000L;
    /** Below this veil opacity the dimming is not drawn at all (and the particles keep their usual single pass). */
    private static final double SHADE_MIN = 0.004;

    private void prepareThumbs(Shared s, long clockNanos) {
        ViewerState.Life sel = selLife;
        if (sel != null && sel.puffs.length > 0) ensureThumb(s, s.cardThumbs, sel, 112, 76, clockNanos);
        int built = 0;
        for (int i = livesSnap.size() - 1; i >= Math.max(0, livesSnap.size() - 70) && (built < 1 || !s.async); i--) {
            ViewerState.Life l = livesSnap.get(i);
            if (l.puffs.length > 0 && ensureThumb(s, s.rowThumbs, l, 26, 13, clockNanos)) built++;
        }
        if (s.rowThumbs.size() > 400) { s.rowThumbs.clear(); s.cardThumbs.clear(); }
    }

    private boolean ensureThumb(Shared s, Map<Long, Shared.Thumb> map, ViewerState.Life l, double boxW, double boxH, long clockNanos) {
        long sig = l.puffs.length * 31L + Math.round(l.puffArea / 50) * 131 + (l.alive ? Model.stageIndex(l.stage) : 9) + Double.doubleToLongBits(s.view.dev)
                + l.type.hashCode() * 7L + (l.morphing() ? Math.round(l.morph * 10) * 977 : 0) + l.absorbed.size() * 7919L
                + (s.adv.simpleClouds ? 104729L : 0);         // (the portraits follow "Simple clouds"; without it the key is what it was)
        Shared.Thumb t = map.get(l.id);
        if (t != null && (t.sig == sig || (l.alive && s.async && clockNanos - t.builtClock < 2_500_000_000L))) return false;
        if (t == null) { t = new Shared.Thumb(); map.put(l.id, t); }
        double minx = 1e9, maxx = -1e9, miny = 1e9, maxy = -1e9;
        for (double[] p : l.puffs) {
            minx = Math.min(minx, p[0] - p[2]); maxx = Math.max(maxx, p[0] + p[2]);
            miny = Math.min(miny, p[1] - p[2]); maxy = Math.max(maxy, p[1] + p[2]);
        }
        int kind = Model.cloudKind(l.type);
        // a cloud still growing out of another type is drawn (and framed) as the blend it currently is
        double mph = l.morphing() ? l.morph : 1;
        CloudPainter.Spec sp = new CloudPainter.Spec();
        sp.seed = l.seed; sp.kind = kind; sp.puffs = l.puffs; sp.form = 1; sp.shear = l.shear;
        if (mph < 1) { sp.fromKind = Model.fromKind(l.fromType, l.type); sp.morph = mph; }
        sp.anvil = Model.anvilKind(kind) ? l.anvil + (Math.max(0.5, l.anvil) - l.anvil) * mph : 0;
        if (l.puffPart != null && l.puffPart.length == l.puffs.length && !l.absorbedTypes.isEmpty()) {
            // the portrait of a merged cloud shows the absorbed layer as the flat band it is
            sp.puffPart = l.puffPart;
            sp.partKind = new int[l.absorbedTypes.size()];
            for (int k = 0; k < sp.partKind.length; k++) sp.partKind[k] = Model.cloudKind(l.absorbedTypes.get(k));
        }
        if (kind == Model.SUPERCELL) { sp.rotation = l.peakRotation; sp.overshoot = 0.8; sp.wall = 0.8; }
        sp.darkness = l.alive ? darkFor(l.stage) : 0.2;
        sp.tint = l.tint;
        sp.opacity = kind == Model.CIRRUS ? 0.85 : 0.97;
        sp.light = THUMB_LIGHT;
        double wide = kind == Model.CUMULONIMBUS ? 1.12 + 0.38 * mph : kind == Model.SUPERCELL ? 1.7 : kind == Model.CIRRUS ? 1.5 : 1.12;
        sp.scale = Math.min(boxW / Math.max(1, (maxx - minx) * wide + Math.abs(l.shear)), boxH / Math.max(1, (maxy - miny) * 1.2)) * s.view.dev;
        t.img = CloudPainter.render(sp, s.thumbScratch);
        t.sig = sig;
        t.builtClock = clockNanos;
        t.minX = minx; t.maxX = maxx; t.minY = miny; t.maxY = maxy;
        s.thumbVersion++;
        return true;
    }

    /** Draw a thumbnail centred in the given box (user space of {@code g}, which may be the cached panel). */
    private void drawThumb(Graphics2D g, Shared.Thumb t, double bx, double by, double bw, double bh) {
        if (t == null || t.img == null || t.img.img == null) return;
        CloudPainter.Image im = t.img;
        double dev = v.dev, k = im.scale / dev;
        double cxw = im.relX + im.w / im.scale / 2, cyw = im.relY + im.h / im.scale / 2;
        double x = bx + bw / 2 - (cxw - im.relX) * k, y = by + bh / 2 - (cyw - im.relY) * k;
        Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(im.img, new AffineTransform(1 / dev, 0, 0, 1 / dev, x, y), null);
        if (old != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old);
    }

    // =====================================================================================
    // entry point (every band)

    /**
     * Draw the prepared frame. {@code g} has the identity transform and is clipped to this band's
     * device rectangle (bx0, by0)-(bx1, by1).
     * @param animSec free-running animation clock in seconds
     */
    void draw(Graphics2D g, Shared shared, int bx0, int by0, int bx1, int by1, double animSec, String sourceName, long clockNanos) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        anim = animSec;
        clock = clockNanos;
        profT = System.nanoTime();
        sh = shared;
        v = shared.view;
        view = v;
        lit = shared.lit;
        day = lit.day; dusk = lit.dusk; light = lit.light; overcast = lit.overcast;
        skyTop = lit.skyTop; skyBot = lit.skyBot;
        scrTx = v.screenTx();
        wTx = v.worldTx();
        panelX = v.vx + v.vw;
        panelXi = (int) Math.round(panelX);
        canvasHi = (int) Math.round(v.canvasH);
        vwI = (int) Math.round(v.vw); vhI = (int) Math.round(v.vh);
        vRight = v.vx + v.vw; vBottom = v.vy + v.vh;
        bandWy0 = (by0 - v.wty) / v.ws - 2; bandWy1 = (by1 - v.wty) / v.ws + 2;
        bandSy0 = (by0 - v.devY) / v.dev - 1; bandSy1 = (by1 - v.devY) / v.dev + 1;
        bandDy0 = by0; bandDy1 = by1;
        directOn = target != null && direct.px != null;
        if (directOn) direct.clip(Math.max(bx0, v.dvx0), Math.max(by0, v.dvy0), Math.min(bx1, v.dvx1), Math.min(by1, v.dvy1));

        Model.Frame f = curFrame;
        g.setTransform(scrTx);
        Shape bandClip = g.getClip();
        g.clip(new Rectangle2D.Double(v.vx, v.vy, v.vw, v.vh));
        if (f == null || shared.scene == null) drawEmptyWorld(g);
        else drawWorld(g, f);
        g.setTransform(scrTx);
        g.setClip(bandClip);
        if (panelX < v.canvasW - 1 && rightShown() > 0.0005) drawPanel(g, null, f);
        pm(11);
        if (snapError != null && showErrorBox) drawError(g, snapError);
        if (showHelp) drawHelp(g, sourceName);
        if (toast != null && clock / 1_000_000L < toastUntilNanos / 1_000_000L) drawToast(g, toast);
        pm(12);
    }

    // =====================================================================================
    // world

    private void drawEmptyWorld(Graphics2D g) {
        drawBackdrop(g, null);
        String msg = snapError == null ? snapStatus : "";
        g.setFont(F_BIG);
        g.setColor(new Color(255, 255, 255, 220));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(msg, (float) (v.vx + (v.vw - fm.stringWidth(msg)) / 2), (float) (v.vy + v.vh / 2 - 40));
    }

    private Color lit(Color c) { return lit.lit(c); }

    private int devX(double wx) { return (int) Math.floor(v.ws * wx + v.wtx + 0.5); }

    private int devY(double wy) { return (int) Math.floor(v.ws * wy + v.wty + 0.5); }

    private void drawWorld(Graphics2D g, Model.Frame f) {
        pm(16);
        drawBackdrop(g, f);                       // sky (or the custom image), stars, sun, moon
        pm(0);
        g.setTransform(IDENTITY);
        Direct dd = directOn ? direct : null;
        sh.far.draw(g, dd, v.settled);            // parallax ridges
        sh.near.draw(g, dd, v.settled);
        sh.scenery.draw(g, dd, v.settled);                       // background mountains and hills
        g.setTransform(scrTx);
        if (!decorative) drawFreezingLine(g, f);
        g.setTransform(IDENTITY);
        pm(1);
        for (Shared.DrawCloud dc : sh.drawClouds) drawVeil(g, dc);   // rain shafts hang behind the terrain
        pm(4);
        sh.ground.draw(g, dd, v.settled);         // terrain + buildings
        g.setTransform(wTx);
        pm(1);
        if (showWind) { drawWindField(g, f); pm(2); drawStreamlines(g, f); }
        pm(13);
        Model.Obj[] objs = sh.liveObjs;
        boolean trees = liveTrees(v.ws, v.dev);
        for (Model.Obj o : objs) {
            if (o.x + o.w + 40 < v.wx0 || o.x - o.w - 40 > v.wx1) continue;
            switch (o.kind) {
                case "pond" -> drawPond(g, o, f);
                case "puddle" -> { if (!sh.water.on) drawPuddle(g, o); }      // v2.4: legacy when the stream has the water model
                case "snowpack" -> { if (!sh.adv.noSnow) drawSnowpack(g, o); }
                default -> { }
            }
        }
        // rain running down the panes of the wet buildings in the front row (the beads that sit still are in their tiles);
        // before the trees, which stand in front of them. Not on the back row: what stands in front of it is in the tiles too.
        // The drops lean with the wind at their building (straight from this frame's wind grid: nothing is kept between frames)
        if (v.ws >= BuildingPainter.RUN_MIN_PX) {
            final double dy0 = Math.max(v.wy0, bandWy0), dy1 = Math.min(v.wy1, bandWy1);
            for (Model.Obj o : objs) {
                if (!o.building || o.layer == 1 || !BuildingPainter.hasDrops(o, v.ws)) continue;
                if (o.x + o.w / 2 + 8 < v.wx0 || o.x - o.w / 2 - 8 > v.wx1 || o.y < dy0 || o.y - o.h * 1.1 - 8 > dy1) continue;
                BuildingPainter.paintDrops(g, o, lit, anim, 0, v.ws, v.wx0, dy0, v.wx1, dy1, f.windU(o.x, o.y - o.h * 0.5));
            }
        }
        for (Model.Obj o : objs) {
            if (o.x + o.w + 140 < v.wx0 || o.x - o.w - 140 > v.wx1) continue;
            if (o.y + 12 < bandWy0 || o.y - o.h * 2.4 - 150 > bandWy1) continue;
            if (o.kind.equals("tree")) {
                if (!trees) continue;
                double wig = Math.sin(anim * (1.6 + (o.x % 5) * 0.1) + o.x) * (0.04 + Math.abs(o.sway) * 0.06);
                TreePainter.paintLive(g, o, lit, -(o.sway + wig) * 0.32, v.zoom == v.targetZoom);      // Phase O: a cached picture per tree
            } else if (o.building && BuildingPainter.hasLive(o.kind) && BuildingPainter.liveWorks(o)) {
                // (an object of any other kind - one that an older save or another version of the simulation still
                // sends and this viewer has no picture for - is simply not drawn)
                if (o.layer == 1) {
                    AffineTransform old = g.getTransform();
                    g.translate(o.x, o.y);
                    g.scale(0.9, 0.9);
                    g.translate(-o.x, -o.y);
                    BuildingPainter.paintLive(g, o, lit, anim, 0.36);
                    g.setTransform(old);
                } else BuildingPainter.paintLive(g, o, lit, anim, 0);
            }
        }
        DrainPainter.inlets(g, hello, sh.drains, v, lit, bandWy0, bandWy1);      // v2.4: the grates in the kerb (nothing without drains)
        pm(3);
        Map<Long, double[]> drawn = new HashMap<>();
        for (Shared.DrawCloud dc : sh.drawClouds) drawn.put(dc.c.id, new double[]{dc.x, dc.y});
        drawnClouds = drawn;

        // Under a cloud the world is dimmed by a veil drawn over the finished scene. Precipitation that no
        // cloud can hide is then drawn after the veil (brighter, a little longer) so the rain reads better
        // in the dark; what falls inside a cloud's outline keeps its place behind that cloud.
        boolean shaded = sh.shadeAlpha > SHADE_MIN;
        // HOOK OH (Life.paint), wired in OI: the live people go exactly here: after the trees, before the cars, in front of
        // both rows (PLAN Phase O, OH6). Life.paint wants world coordinates and leaves transform and composite as it found them.
        g.setTransform(wTx);
        // (their clock is the view's life clock, which stands still while the simulation is paused: Shared.lifeAnim)
        Life.paint(g, sh.scene, v, sh.lifeAnim, lit, sh.wreck::sheltered, this::waterDepthAt);
        g.setTransform(wTx);
        // v2.3: the scars of tornadoes, then the cars on the road (in front of the houses and trees)
        drawScars(g, f);
        drawCars(g, false, false);
        pm(17);
        drawParticles(g, f, sh.extrap, false, shaded ? 1 : 0);
        pm(5);
        // v2.3: a tornado hangs from the wall cloud, so it is drawn just before the clouds, which then cover its top
        drawTornadoes(g, f);
        drawCars(g, false, true);             // a car the vortex is carrying whirls round in front of the funnel
        // Phase O (OW2): the pieces torn off the buildings. Here, in front of the funnel and behind the cloud: the ones the
        // cloud swallows and the ones still held where they were cut; the rest after the veil and the debris (OI, below)
        if (sh.wreck.draws.length > 0) {
            g.setTransform(wTx);
            Chunks.paint(g, sh.wreck, v, lit, directOn ? direct : null, bandWy0, bandWy1, sh.extrap, Chunks.EARLY, f.tornadoes);
        }
        drawHooks(g);
        drawDebris(g, f, sh.extrap, true);    // what the vortex has lifted above the cloud base is inside the cloud
        pm(17);
        for (Shared.DrawCloud dc : sh.drawClouds) drawCloud(g, dc);
        g.setTransform(wTx);
        pm(6);
        drawParticles(g, f, sh.extrap, true, 0);
        pm(7);
        if (shaded) {
            drawShade(g);
            g.setTransform(wTx);
            drawParticles(g, f, sh.extrap, false, 2);
            pm(5);
            drawCars(g, true, false);             // headlights glow through the gloom under a storm
        }
        drawDebris(g, f, sh.extrap, false);
        // OI: the building pieces in the open air under the cloud, over the simulation's debris chips and not dimmed by the
        // veil (as the chips are not), so a roof that flies is seen flying
        if (sh.wreck.draws.length > 0) {
            final AffineTransform keep = g.getTransform();
            g.setTransform(wTx);
            Chunks.paint(g, sh.wreck, v, lit, directOn ? direct : null, bandWy0, bandWy1, sh.extrap, Chunks.LATE, f.tornadoes);
            g.setTransform(keep);
        }
        pm(17);
        // v2.4: the standing water, after the cars and the falling rain (a car stands in it, a drop that reaches it is covered)
        if (sh.water.on || !sh.drains.isEmpty()) {
            AffineTransform keep = g.getTransform();
            g.setTransform(wTx);
            final double wy0 = Math.max(v.wy0, bandWy0), wy1 = Math.min(v.wy1, bandWy1);
            Preset.Biome bio = sh.scene != null ? sh.scene.biome : null;
            WaterPainter.paint(g, sh.water, hello.terrain, v, lit, anim, wy0, wy1, sh.adv, bio != null ? bio.water : null,
                    (bio != null && bio.frozenWater) || f.surfaceTemp < -1, shaded ? sh.shadeAlpha : 0);
            // v2.4.1: the film that runs over the ground and the streets (nothing on a stream without hello.flow)
            FlowPainter.paint(g, sh.flow, v, lit, anim, wy0, wy1, sh.adv, bio != null ? bio.water : null,
                    (bio != null && bio.frozenWater) || f.surfaceTemp < -1, shaded ? sh.shadeAlpha : 0);
            DrainPainter.overWater(g, hello, sh.drains, sh.flood, sh.water, v, lit, anim, wy0, wy1, sh.showDrains && !decorative, sh.adv.noPuddleImpact);
            g.setTransform(keep);
            pm(18);
        }
        drawLightning(g, f);
        pm(15);

        // ---- screen space from here on
        g.setTransform(scrTx);
        for (Shared.DrawCloud dc : sh.drawClouds) if (dc.c.id == selId) drawSelection(g, dc);
        arrowPills = new ArrayList<>();      // (every mode: arrow labels keep clear of each other, name plates of them)
        if (showArrows && v.zoom >= 0.3) {
            for (Model.Arrow a : f.arrows) {
                if (!v.sees(Math.min(a.x1, a.x2) - 40, Math.min(a.y1, a.y2) - 40, Math.max(a.x1, a.x2) + 40, Math.max(a.y1, a.y2) + 40)) continue;
                if (Math.max(a.y1, a.y2) + 30 / v.zoom < bandWy0 || Math.min(a.y1, a.y2) - 40 / v.zoom > bandWy1) continue;
                Model.Arrow s = new Model.Arrow();
                s.x1 = v.sx(a.x1); s.y1 = v.sy(a.y1); s.x2 = v.sx(a.x2); s.y2 = v.sy(a.y2);
                s.kind = a.kind; s.mag = a.mag; s.label = v.zoom >= 0.55 ? a.label : "";
                drawArrow(g, s);
            }
        }
        pm(8);
        // panels first: labels are then placed around them
        if (leftShown() > 0.004) drawLeftPanel(g, f); else leftPanelBottom = 0;
        if (miniShown() > 0.004) drawMinimap(g, f); else minimapRect = null;
        // v2.4: which settlement the drains card is about (the Climate panel leaves room for it)
        // (not in a view with every panel put away: the card belongs to the panels)
        drainPick = sh.showDrainCard && !decorative && v.vw >= 620 && v.vh >= 250 && (showClimate || showLeftPanel || showRightPanel || showMinimap)
                ? DrainCard.pick(hello, sh.drains, v) : null;
        drawClimate(g, f);                        // v2.3 (draws nothing for a stream without the climate keys)
        drawDrainCard(g);
        pm(10);
        freezeLabelRect = null;
        if (!decorative) drawFreezingLabel(g, f);
        placedLabels.clear();
        if (arrowPills != null) placedLabels.addAll(arrowPills);
        // (v2.3 has seasons: in summer the 0-degree level is up among the clouds, so their labels keep clear of its label)
        if (freezeLabelRect != null && f.hasCalendar()) placedLabels.add(freezeLabelRect);
        if (showLeftPanel) placedLabels.add(new Rectangle2D.Double(v.vx + 8, v.vy + 8, 270, Math.max(0, leftPanelBottom - v.vy - 4)));
        if (minimapRect != null) placedLabels.add(minimapRect);
        if (hudReserveW > 0) placedLabels.add(new Rectangle2D.Double(v.vx + v.vw - hudReserveW, v.vy, hudReserveW, 42));
        Rectangle2D clim = climateRect;
        if (clim != null) placedLabels.add(new Rectangle2D.Double(clim.getX() - 4, clim.getY() - 4, clim.getWidth() + 8, clim.getHeight() + 8));
        Rectangle2D dcr = drainRect;
        if (dcr != null) placedLabels.add(new Rectangle2D.Double(dcr.getX() - 4, dcr.getY() - 4, dcr.getWidth() + 8, dcr.getHeight() + 8));
        if (showLabels) {
            drawSettlementLabels(g);
            if (!decorative) PresetOverlay.landmarks(g, v, hello, placedLabels, bandSy0, bandSy1);
            for (Model.Tornado tn : f.tornadoes) drawTornadoLabel(g, tn);
            for (Shared.DrawCloud dc : sh.drawClouds) drawCloudLabel(g, dc);
        }
        if (sh.showDrains && !decorative) DrainPainter.labels(g, hello, sh.drains, sh.flood, v, placedLabels, bandSy0, bandSy1);     // v2.4: what each pipe is
        pm(9);
        ViewerState.Life sel = selLife;
        if (sel != null) drawLifeCard(g, sel, f); else { cardRect = null; cardClose = null; }
        pm(10);
    }

    // ------------------------------------------------------------------------------ backdrop (sky)

    /** Fit {@link #backdropImage} to the canvas once per size / fit mode (renderer 0, before the bands). */
    private void prepareBackdrop(Shared s, Camera.View view) {
        BufferedImage custom = backdropImage;
        if (custom == null) { s.bdScaled = null; s.bdSource = null; return; }
        int cw = Math.max(1, (int) Math.round(view.canvasW * view.dev)), ch = Math.max(1, (int) Math.round(view.canvasH * view.dev));
        int fit = backdropFit;
        if (s.bdScaled != null && s.bdSource == custom && s.bdKeyW == cw && s.bdKeyH == ch && s.bdKeyFit == fit) return;
        double kx = cw / (double) custom.getWidth(), ky = ch / (double) custom.getHeight();
        double k = fit == FIT_COVER ? Math.max(kx, ky) : Math.min(kx, ky);
        int w = fit == FIT_STRETCH ? cw : Math.max(1, (int) Math.round(custom.getWidth() * k));
        int h = fit == FIT_STRETCH ? ch : Math.max(1, (int) Math.round(custom.getHeight() * k));
        BufferedImage img = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
        Graphics2D ig = img.createGraphics();
        ig.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        ig.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        ig.setColor(Color.BLACK);
        ig.fillRect(0, 0, cw, ch);
        ig.drawImage(custom, (cw - w) / 2, (ch - h) / 2, w, h, null);
        ig.dispose();
        s.bdScaled = img; s.bdSource = custom; s.bdKeyW = cw; s.bdKeyH = ch; s.bdKeyFit = fit;
    }

    /**
     * The one place the backdrop is drawn (screen space, clipped to the world viewport): the sky
     * gradient with stars, or {@link #backdropImage} when set, then sun and moon.
     */
    private void drawBackdrop(Graphics2D g, Model.Frame f) {
        BufferedImage custom = backdropImage;
        BufferedImage scaled = sh.bdScaled;
        if (custom != null && scaled != null) {
            // the picture is fitted to the whole canvas, so sliding a side panel uncovers more of it
            // instead of rescaling it
            g.setTransform(IDENTITY);
            g.drawImage(scaled, v.devX, v.devY, null);
            g.setTransform(scrTx);
            boolean tint = backdropTint && !sh.adv.noTint;
            int dim = clampi((int) (255 * Model.clamp(backdropDim + (tint ? (1 - light) * 0.62 : 0), 0, 0.92)));
            if (dim > 0) {
                g.setColor(new Color(4, 8, 20, dim));
                g.fill(new Rectangle2D.Double(v.vx, v.vy, v.vw, v.vh));
            }
            if (tint && dusk > 0.03) {
                g.setColor(new Color(255, 128, 48, clampi((int) (dusk * 64))));
                g.fill(new Rectangle2D.Double(v.vx, v.vy, v.vw, v.vh));
            }
        } else {
            // vertical gradient tied to world altitude, so it moves with the camera like the horizon does
            double topY = -worldH * 0.7, span = groundY - topY;
            Color mid = mix(skyTop, skyBot, 0.6);
            if (directOn) {
                int skyEnd = Math.min(direct.cy1, devY(groundY) + 2);   // below the lowest ground point everything is ground
                for (int dy = direct.cy0; dy < skyEnd; dy++) {
                    double t = Model.clamp(((dy + 0.5 - v.wty) / v.ws - topY) / span, 0, 1);
                    Color a, b;
                    double q;
                    if (t <= 0.65) { a = skyTop; b = mid; q = t / 0.65; } else { a = mid; b = skyBot; q = (t - 0.65) / 0.35; }
                    double r = a.getRed() + (b.getRed() - a.getRed()) * q, gg = a.getGreen() + (b.getGreen() - a.getGreen()) * q, bl = a.getBlue() + (b.getBlue() - a.getBlue()) * q;
                    int c0 = 0xFF000000 | ((int) (r + 0.5) << 16) | ((int) (gg + 0.5) << 8) | (int) (bl + 0.5), c1 = c0;
                    direct.fillRow(dy, v.dvx0, v.dvx1, c0, c1);
                }
            } else {
                float y0 = (float) v.sy(topY), y1 = (float) Math.max(y0 + 1, v.sy(groundY));
                g.setPaint(new LinearGradientPaint(0, y0, 0, y1, new float[]{0f, 0.65f, 1f}, new Color[]{skyTop, mid, skyBot}));
                g.fill(new Rectangle2D.Double(v.vx, v.vy, v.vw, v.vh));
            }
            // stars (screen space, drifting a touch with the camera)
            double starA = (1 - day) * (1 - overcast * 0.8);
            if (starA > 0.02) {
                spriteBeginScreen(g);
                double hz = Math.min(v.vy + v.vh, v.sy(groundY));
                double shift = v.cx * v.zoom * 0.03;
                for (int k = 0; k < stars.length / 3; k++) {
                    double sx = v.vx + ((stars[k * 3] * v.vw - shift) % v.vw + v.vw) % v.vw, sy = v.vy + stars[k * 3 + 1] * (hz - v.vy) * 0.9;
                    double tw = 0.55 + 0.45 * Math.sin(anim * (1.5 + stars[k * 3 + 2] * 3) + k);
                    int a = clampi((int) (255 * starA * tw * (0.35 + 0.65 * stars[k * 3 + 2])));
                    int aq = (int) Math.round(a / 255.0 * 31);
                    if (aq > 0) blit(g, 1, sizeQ((0.8 + stars[k * 3 + 2] * 1.6) * starScale), aq, sx, sy);
                }
                g.setTransform(scrTx);
            }
        }
        double tod = f == null ? 0.45 : f.timeOfDay;
        drawCelestial(g, tod, true);
        drawCelestial(g, (tod + 0.5) % 1, false);
    }

    /** Sun and moon ride an arc across the viewport; the ridges and terrain are drawn over them. */
    private void drawCelestial(Graphics2D g, double tod, boolean sun) {
        if (tod < 0.2 || tod > 0.8) return;
        double p = (tod - 0.2) / 0.6;                   // 0..1 across the sky
        double hz = Model.clamp(v.sy(groundY), v.vy + v.vh * 0.45, v.vy + v.vh + 160);
        double x = v.vx + v.vw * (0.05 + 0.9 * p);
        double y = hz - 24 - Math.sin(p * Math.PI) * (hz - v.vy) * 0.80;
        float vis = (float) (sun ? (1 - overcast * 0.7) : (1 - day) * (1 - overcast * 0.7));
        if (vis < 0.03) return;
        int vq = (int) Math.round(vis * 64) * 255 / 64;
        if (sun) {
            Color core = mix(new Color(255, 250, 225), new Color(255, 170, 90), dusk);
            drawGlow(g, true, core.getRGB(), vq, x, y);
            g.setColor(alpha(new Color(255, 253, 240), (int) (255 * vis)));
            g.fill(new Ellipse2D.Double(x - 16, y - 16, 32, 32));
        } else {
            drawGlow(g, false, 0, vq, x, y);
            if (moonShape == null) {
                Area moon = new Area(new Ellipse2D.Double(-13, -13, 26, 26));
                moon.subtract(new Area(new Ellipse2D.Double(-5, -17, 26, 26)));
                moonShape = moon;
            }
            g.setColor(new Color(236, 238, 250, (int) (240 * vis)));
            AffineTransform old = g.getTransform();
            g.translate(x, y);
            g.fill(moonShape);
            g.setTransform(old);
        }
    }

    private Shape moonShape;
    // cached sun / moon glow sprites (radial gradients), keyed by colour + visibility
    private BufferedImage sunGlow, moonGlow;
    private long sunKey = Long.MIN_VALUE, moonKey = Long.MIN_VALUE;

    /** The big soft radial glow behind the sun or moon, rendered once per colour/visibility/scale. */
    private void drawGlow(Graphics2D g, boolean sun, int rgb, int vq, double x, double y) {
        float vis = vq / 255f;
        float r = sun ? 120 : 60;
        AffineTransform tx = g.getTransform();
        if (!axisAligned(tx)) {
            g.setPaint(glowPaint(sun, rgb, vis, x, y));
            g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
            return;
        }
        double sc = tx.getScaleX();
        long key = ((long) rgb << 32) ^ ((long) vq << 20) ^ Double.doubleToLongBits(sc);
        BufferedImage img = sun ? sunGlow : moonGlow;
        int half = (int) Math.ceil(r * sc) + 2;
        if (img == null || (sun ? sunKey : moonKey) != key) {
            img = new BufferedImage(half * 2, half * 2, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D ig = img.createGraphics();
            ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ig.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            ig.translate(half, half);
            ig.scale(sc, sc);
            ig.setPaint(glowPaint(sun, rgb, vis, 0, 0));
            ig.fill(new Ellipse2D.Double(-r, -r, r * 2, r * 2));
            ig.dispose();
            if (sun) { sunGlow = img; sunKey = key; } else { moonGlow = img; moonKey = key; }
            sunGlowBuilds++;
        }
        int dx = (int) Math.floor(tx.getTranslateX() + sc * x + 0.5) - half;
        int dy = (int) Math.floor(tx.getTranslateY() + sc * y + 0.5) - half;
        g.setTransform(IDENTITY);
        g.drawImage(img, dx, dy, null);
        g.setTransform(tx);
    }

    private static Paint glowPaint(boolean sun, int rgb, float vis, double x, double y) {
        if (sun) {
            Color core = new Color(rgb);
            return new RadialGradientPaint(new Point2D.Double(x, y), 120,
                    new float[]{0f, 0.12f, 0.35f, 1f},
                    new Color[]{alpha(core, (int) (255 * vis)), alpha(core, (int) (170 * vis)), alpha(core, (int) (50 * vis)), alpha(core, 0)});
        }
        return new RadialGradientPaint(new Point2D.Double(x, y), 60, new float[]{0f, 0.25f, 1f},
                new Color[]{new Color(220, 230, 255, (int) (90 * vis)), new Color(200, 215, 255, (int) (35 * vis)), new Color(200, 215, 255, 0)});
    }

    static boolean axisAligned(AffineTransform tx) {
        return tx.getShearX() == 0 && tx.getShearY() == 0 && Math.abs(tx.getScaleX() - tx.getScaleY()) < 1e-9;
    }

    private static final AffineTransform IDENTITY = new AffineTransform();
    long sunGlowBuilds;

    private static void blitAt(Graphics2D g, AffineTransform tx, BufferedImage img, int dx, int dy) {
        g.setTransform(IDENTITY);
        g.drawImage(img, dx, dy, null);
        g.setTransform(tx);
    }

    private static double noise1(double x) {
        int i = (int) Math.floor(x);
        double f = x - i;
        double a = hash01(i), b = hash01(i + 1);
        double t = f * f * (3 - 2 * f);
        return a + (b - a) * t;
    }

    private static double hash01(long n) {
        n = (n << 13) ^ n;
        long v = (n * (n * n * 15731L + 789221L) + 1376312589L) & 0x7fffffffL;
        return v / (double) 0x7fffffffL;
    }

    // ------------------------------------------------------------------------------ freezing level

    private void drawFreezingLine(Graphics2D g, Model.Frame f) {
        double y = v.sy(f.freezingY);
        if (y <= v.vy - 60 || y >= v.vy + v.vh) return;
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{10, 7}, dph(-anim * 8, 17)));
        g.setColor(new Color(150, 230, 255, 170));
        g.draw(new Line2D.Double(v.vx, y, v.vx + v.vw, y));
        // soft band hinting at the melting layer
        double band = Math.max(20, 60 * v.zoom);
        g.setPaint(new GradientPaint(0, (float) y, new Color(150, 230, 255, 26), 0, (float) (y + band), new Color(150, 230, 255, 0)));
        g.fill(new Rectangle2D.Double(v.vx, y, v.vw, band));
    }

    /** Where the freezing-level label was drawn this frame (cloud and tornado labels keep clear of it), or null. */
    private Rectangle2D freezeLabelRect;

    private void drawFreezingLabel(Graphics2D g, Model.Frame f) {
        double y = v.sy(f.freezingY);
        if (y <= v.vy + 30 || y >= v.vy + v.vh - 20) return;
        String lab = "0 °C  freezing level";
        String sub = String.format("surface %.1f °C", f.surfaceTemp);
        // (at or below 0 C at the surface nothing melts on the way down: the old text promised rain over lying snow)
        String below = f.surfaceTemp > 0 ? "melting → rain" : "freezing to the ground";
        g.setFont(F_BOLD);
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(lab) + 16;
        // sit at the left edge unless the left panel is in the way, then just right of it
        double lx = v.vx + ((!showLeftPanel || y - 44 > leftPanelBottom) ? 14 : 290), ly = y - 24;
        // in a narrow window there is no room between the left panel and the Climate panel: the dashed line alone
        // marks the level then (2026-10-09: at the minimum window size the label was printed over the Climate panel)
        Rectangle2D climNow = climateRect;
        if (climNow != null && lx + Math.max(w, fm.stringWidth(below + "  (" + sub + ")") + 4) > climNow.getX() - 4 && lx < climNow.getMaxX()
                && y + 20 > climNow.getY() && y - 40 < climNow.getMaxY()) return;
        pill(g, lx, ly, w, 19, new Color(10, 40, 60, 170));
        g.setColor(new Color(170, 236, 255));
        g.drawString(lab, (float) lx + 8, (float) ly + 14);
        g.setFont(F_SMALL);
        g.setColor(new Color(170, 236, 255, 200));
        g.drawString("▲ snow & ice", (float) lx + 8, (float) y - 28);
        g.drawString("▼ " + below + "  (" + sub + ")", (float) lx + 8, (float) y + 15);
        freezeLabelRect = new Rectangle2D.Double(lx - 2, y - 40, Math.max(w, fm.stringWidth(below + "  (" + sub + ")") + 4), 60);
    }

    // ------------------------------------------------------------------------------ wind

    // Wind-field arrows are sprites keyed by speed bucket (1/8 m/s) and direction bucket (1.4 deg).
    // They keep their on-screen size at every zoom; when zoomed out only every n-th cell is drawn.
    private final Sprites windSprites = new Sprites();
    private static final int WIND_ANGLES = 256;
    private int windLenQ = -1;
    private static final BasicStroke WIND_STROKE = new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    private void drawWindField(Graphics2D g, Model.Frame f) {
        if (f.wCols <= 0) return;
        double cell = f.wCell;
        int stride = Math.max(1, (int) Math.ceil(34 / (cell * v.zoom)));
        double maxLen = Math.min(24, cell * stride * v.zoom * 0.44);
        int lq = (int) Math.round(maxLen * 2);
        if (windSprites.scale != v.dev || windLenQ != lq) { windSprites.clear(); windSprites.scale = v.dev; windLenQ = lq; }
        else if (windSprites.size() > 20_000) windSprites.clear();
        maxLen = lq / 2.0;
        Model.Terrain terrain = sh.scene.terrain;
        int c0 = Math.max(0, (int) Math.floor(v.wx0 / cell) - 1), c1 = Math.min(f.wCols - 1, (int) Math.ceil(v.wx1 / cell));
        int r0 = Math.max(0, (int) Math.floor(Math.max(v.wy0, bandWy0 - 30 / v.zoom) / cell) - 1), r1 = Math.min(f.wRows - 1, (int) Math.ceil(Math.min(v.wy1, bandWy1 + 30 / v.zoom) / cell));
        c0 -= c0 % stride;
        r0 -= r0 % stride;
        g.setTransform(IDENTITY);
        for (int r = r0; r <= r1; r += stride) {
            for (int c = c0; c <= c1; c += stride) {
                double cx = (c + 0.5) * cell, cy = (r + 0.5) * cell;
                if (cy > terrain.at(cx) - 8) continue;
                double u = f.u[r * f.wCols + c], vv = f.v[r * f.wCols + c];
                double m = Math.hypot(u, vv);
                if (m < 0.05) continue;
                int q = (int) Math.min(511, Math.round(m * 8));
                int aq = (int) Math.round(Math.atan2(vv, u) / (2 * Math.PI) * WIND_ANGLES) & (WIND_ANGLES - 1);
                double dxd = v.ws * cx + v.wtx, dyd = v.ws * cy + v.wty;
                int ix = (int) Math.floor(dxd), iy = (int) Math.floor(dyd);
                int phx = dxd - ix >= 0.5 ? 1 : 0, phy = dyd - iy >= 0.5 ? 1 : 0;
                int key = (((q << 8) | aq) << 2) | (phx << 1) | phy;
                Sprites.Sprite sp = windSprites.get(key);
                if (sp == null) {
                    sp = makeWindSprite(v.dev, q / 8.0, aq * 2 * Math.PI / WIND_ANGLES, phx * 0.5, phy * 0.5, maxLen);
                    windSprites.put(key, sp);
                }
                if (sp.img == null) continue;
                if (directOn) direct.over(sp.px, sp.w, sp.h, ix + sp.ox, iy + sp.oy);
                else g.drawImage(sp.img, ix + sp.ox, iy + sp.oy, null);
            }
        }
        g.setTransform(wTx);
    }

    /** One wind-field arrow centred on (cx, cy): shaft + head, exactly as drawn per grid cell. */
    private void drawWindArrow(Graphics2D g, double cx, double cy, double m, double ang, double maxLen) {
        double len = Math.min(maxLen, 3 + m * 2.4);
        double dx = Math.cos(ang) * len, dy = Math.sin(ang) * len;
        g.setColor(alpha(speedColor(m), 105));
        double x1 = cx - dx / 2, y1 = cy - dy / 2, x2 = cx + dx / 2, y2 = cy + dy / 2;
        g.draw(new Line2D.Double(x1, y1, x2, y2));
        double a = Math.atan2(dy, dx), hs = 4.2;
        Path2D.Double h = new Path2D.Double();
        h.moveTo(x2, y2);
        h.lineTo(x2 - hs * Math.cos(a - 0.5), y2 - hs * Math.sin(a - 0.5));
        h.lineTo(x2 - hs * Math.cos(a + 0.5), y2 - hs * Math.sin(a + 0.5));
        h.closePath();
        g.fill(h);
    }

    private Sprites.Sprite makeWindSprite(double sc, double m, double ang, double phx, double phy, double maxLen) {
        int pad = (int) Math.ceil((maxLen / 2 + 6) * sc) + 2;
        BufferedImage img = new BufferedImage(pad * 2 + 2, pad * 2 + 2, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D sg = img.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        sg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        sg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        sg.translate(pad + phx, pad + phy);
        sg.scale(sc, sc);
        sg.setStroke(WIND_STROKE);
        drawWindArrow(sg, 0, 0, m, ang, maxLen);
        sg.dispose();
        return Sprites.crop(img, pad, pad);
    }

    static Color speedColor(double m) {
        double t = Model.clamp(m / 16, 0, 1);
        if (t < 0.5) return mix(new Color(120, 205, 255), new Color(255, 232, 130), t * 2);
        return mix(new Color(255, 232, 130), new Color(255, 105, 90), (t - 0.5) * 2);
    }

    private void drawStreamlines(Graphics2D g, Model.Frame f) {
        float iz = (float) (1 / v.zoom);
        for (Model.Streamline s : f.streamlines) {
            double minx = 1e18, maxx = -1e18, miny = 1e18, maxy = -1e18;
            for (int k = 0; k + 1 < s.pts.length; k += 2) {
                minx = Math.min(minx, s.pts[k]); maxx = Math.max(maxx, s.pts[k]);
                miny = Math.min(miny, s.pts[k + 1]); maxy = Math.max(maxy, s.pts[k + 1]);
            }
            if (!v.sees(minx, miny, maxx, maxy) || maxy + 4 < bandWy0 || miny - 4 > bandWy1) continue;
            Path2D.Double p = new Path2D.Double();
            p.moveTo(s.pts[0], s.pts[1]);
            for (int k = 2; k + 1 < s.pts.length; k += 2) p.lineTo(s.pts[k], s.pts[k + 1]);
            float period = 34f * iz;
            float phase = dph(-(anim * (8 + s.speed * 7) + s.phase * 34) * iz, period);
            g.setStroke(new BasicStroke(2.0f * iz, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{12 * iz, period - 12 * iz}, phase));
            g.setColor(new Color(235, 248, 255, 62));
            g.draw(p);
        }
    }

    // ------------------------------------------------------------------------------ water, snow

    private void drawPond(Graphics2D g, Model.Obj o, Model.Frame f) {
        Ellipse2D.Double e = new Ellipse2D.Double(o.x - o.w / 2, o.y - 3, o.w, Math.max(8, o.h * 1.4));
        Preset.Biome bio = sh.scene != null ? sh.scene.biome : null;
        boolean ice = bio != null && bio.frozenWater;                // v2.3: the biome's ponds are ice whatever the hour
        boolean frozen = ice || o.snow > 0.5 || f.surfaceTemp < -1;
        Color a = frozen ? lit(new Color(210, 230, 245)) : mix(lit(new Color(40, 90, 150)), skyBot, 0.35);
        Color b = frozen ? lit(new Color(170, 200, 225)) : lit(new Color(24, 56, 100));
        if (bio != null && !frozen) {
            a = mix(lit(Lit.mul(bio.water, 0.86)), skyBot, 0.35);
            b = lit(Lit.mul(bio.water, 0.52));
        } else if (ice) {
            a = lit(new Color(226, 238, 248));                       // flat, pale: snow-dusted ice
            b = lit(new Color(196, 216, 236));
        }
        g.setPaint(new GradientPaint(0, (float) e.y, a, 0, (float) (e.y + e.height), b));
        g.fill(e);
        g.setColor(lit(new Color(70, 60, 40)));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(e);
        Shape clip = g.getClip();
        g.clip(e);
        if (ice) {
            // a few cracks across the sheet (no ripples: rain does not disturb ice)
            Random cr = new Random(o.seed * 31 + 9);
            g.setStroke(new BasicStroke(0.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int k = 0; k < 4; k++) {
                double cx = e.x + e.width * (0.14 + 0.72 * cr.nextDouble()), cy = e.y + e.height * (0.25 + 0.5 * cr.nextDouble());
                Path2D.Double c = new Path2D.Double();
                c.moveTo(cx, cy);
                double dx = (cr.nextDouble() - 0.5) * e.width * 0.30, dy = (cr.nextDouble() - 0.5) * e.height * 0.7;
                c.lineTo(cx + dx * 0.5 + 2 * (cr.nextDouble() - 0.5), cy + dy * 0.45);
                c.lineTo(cx + dx, cy + dy);
                g.setColor(k % 2 == 0 ? new Color(120, 150, 190, 150) : new Color(255, 255, 255, 170));
                g.draw(c);
            }
        } else if (frozen) {
            g.setColor(new Color(255, 255, 255, 120));
            g.draw(new Line2D.Double(e.x + e.width * 0.2, e.y + 3, e.x + e.width * 0.45, e.y + e.height - 3));
        } else {
            g.setColor(new Color(255, 255, 255, 50));
            g.fill(new Rectangle2D.Double(e.x + e.width * 0.15, e.y + 2, e.width * 0.3, 1.5));
            if (o.wet > 0.05) drawRipples(g, e, o.x, 6, o.wet);
        }
        g.setClip(clip);
    }

    private void drawRipples(Graphics2D g, Ellipse2D.Double e, double seed, int n, double wet) {
        if (sh.adv.noPuddleImpact) return;                 // the water keeps its shape and its highlight
        g.setStroke(new BasicStroke(1f));
        for (int k = 0; k < n; k++) {
            double ph = (anim * 0.9 + hash01((long) seed + k * 17L)) % 1;
            double rx = e.x + e.width * (0.1 + 0.8 * hash01((long) (seed * 3) + k * 31L + (long) (anim * 0.9 + hash01((long) seed + k * 17L))));
            double ry = e.y + e.height * 0.5;
            double rr = 2 + ph * 12;
            g.setColor(new Color(220, 235, 255, (int) (140 * (1 - ph) * Math.min(1, wet * 2))));
            g.draw(new Ellipse2D.Double(rx - rr, ry - rr * 0.3, rr * 2, rr * 0.6));
        }
    }

    private void drawPuddle(Graphics2D g, Model.Obj o) {
        if (o.w < 2 || o.wet <= 0) return;           // a dry spot is no puddle (it was drawn as water in a world where it never rained)
        Ellipse2D.Double e = new Ellipse2D.Double(o.x - o.w / 2, o.y - o.h / 2 + 1, o.w, Math.max(3, o.h));
        g.setPaint(new GradientPaint(0, (float) e.y, mix(lit(new Color(80, 110, 150)), skyBot, 0.35), 0, (float) (e.y + e.height), lit(new Color(40, 60, 90))));
        g.fill(e);
        g.setColor(new Color(255, 255, 255, 60));
        g.fill(new Rectangle2D.Double(e.x + e.width * 0.2, e.y + 1, e.width * 0.25, 1));
        if (o.wet > 0.05) {
            Shape clip = g.getClip();
            g.clip(new Rectangle2D.Double(e.x - 4, e.y - 6, e.width + 8, e.height + 12));
            drawRipples(g, e, o.x, 3, o.wet);
            g.setClip(clip);
        }
    }

    private void drawSnowpack(Graphics2D g, Model.Obj o) {
        if (o.h < 0.5 || o.w < 2 || o.snow <= 0 || sh.adv.noSnow) return;   // no snow on it: nothing to draw (it was a white heap on a summer street)
        Path2D.Double p = new Path2D.Double();
        p.moveTo(o.x - o.w / 2, o.y + 1);
        p.curveTo(o.x - o.w * 0.3, o.y - o.h * 1.2, o.x + o.w * 0.25, o.y - o.h * 1.4, o.x + o.w / 2, o.y + 1);
        p.closePath();
        g.setPaint(new GradientPaint(0, (float) (o.y - o.h), lit(new Color(252, 253, 255)), 0, (float) o.y, lit(new Color(200, 214, 236))));
        g.fill(p);
        if (sh.adv.noShine) return;                        // (the sparkle)
        Random r = new Random((long) o.x);
        for (int k = 0; k < (int) (o.w / 10); k++) {
            double x = o.x - o.w / 2 + r.nextDouble() * o.w, y = o.y - r.nextDouble() * o.h * 0.8;
            if (Math.sin(anim * 3 + k * 1.7) > 0.8) {
                g.setColor(new Color(255, 255, 255, 230));
                g.fill(new Ellipse2D.Double(x - 1, y - 1, 2, 2));
            }
        }
    }

    // ------------------------------------------------------------------------------ clouds

    static double cloudBase(Model.Cloud c) { return c.y + CloudPainter.baseOf(c.kind, c.puffs); }

    static double cloudTop(Model.Cloud c) {
        double t = c.y - c.h * 0.5;
        if (c.puffs.length > 0) {
            t = Double.MAX_VALUE;
            for (double[] p : c.puffs) t = Math.min(t, c.y + p[1] - p[2]);
        }
        return t;
    }

    /** One cloud: its cached image, cross-faded with the previous render while a new one settles in. */
    private void drawCloud(Graphics2D g, Shared.DrawCloud dc) {
        SharedClouds.Entry e = dc.e;
        if (e == null) return;
        CloudPainter.Image cur = e.drawCur, prev = e.drawPrev;
        if (cur == null && prev == null) return;
        if (cur != null && cur.px == null) cur = null;
        boolean fadeFromNothing = prev != null && prev.px == null;
        if (fadeFromNothing) prev = null;
        int f256 = e.drawPrev == null ? 256 : e.fade256;
        if (cur == null && prev == null) return;
        boolean exact = (cur == null || cur.scale == v.ws) && (prev == null || prev.scale == v.ws);
        if (directOn && exact) {
            if (e.drawPrev == null) direct.over(cur.px, cur.w, cur.h, devX(dc.x + cur.relX), devY(dc.y + cur.relY));
            else direct.overFade(prev == null ? null : prev.px, prev == null ? 0 : prev.w, prev == null ? 0 : prev.h,
                    prev == null ? 0 : devX(dc.x + prev.relX), prev == null ? 0 : devY(dc.y + prev.relY),
                    cur == null ? null : cur.px, cur == null ? 0 : cur.w, cur == null ? 0 : cur.h,
                    cur == null ? 0 : devX(dc.x + cur.relX), cur == null ? 0 : devY(dc.y + cur.relY), f256);
            return;
        }
        // general path: the image is at another scale (mid-zoom) or there is no direct target
        g.setTransform(IDENTITY);
        Composite oc = g.getComposite();
        Object oh = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, v.settled ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (prev != null && f256 < 256) {
            g.setComposite(AlphaComposite.SrcOver.derive((256 - f256) / 256f));
            blitScaled(g, prev.img, dc.x + prev.relX, dc.y + prev.relY, prev.w, prev.h, prev.scale);
        }
        if (cur != null) {
            g.setComposite(AlphaComposite.SrcOver.derive(e.drawPrev == null ? 1f : f256 / 256f));
            blitScaled(g, cur.img, dc.x + cur.relX, dc.y + cur.relY, cur.w, cur.h, cur.scale);
        }
        g.setComposite(oc);
        if (oh != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oh);
    }

    private void blitScaled(Graphics2D g, BufferedImage img, double wx, double wy, int w, int h, double scale) {
        int x0 = devX(wx), y0 = devY(wy);
        if (Math.abs(scale - v.ws) < v.ws * 0.012) { g.drawImage(img, x0, y0, null); return; }
        int x1 = devX(wx + w / scale), y1 = devY(wy + h / scale);
        g.drawImage(img, x0, y0, x1, y1, 0, 0, w, h, null);
    }

    /** The rain shaft / virga of a cloud; drawn before the terrain so the ground hides its foot. */
    private void drawVeil(Graphics2D g, Shared.DrawCloud dc) {
        SharedClouds.Entry e = dc.e;
        if (e == null) return;
        CloudPainter.Image cur = e.drawCur, prev = e.drawPrev;
        boolean hasPrev = prev != null && prev.veil != null, hasCur = cur != null && cur.veil != null;
        if (!hasPrev && !hasCur) return;
        float f = e.drawPrev == null ? 1f : e.fade256 / 256f;
        boolean exP = !hasPrev || Math.abs(prev.vScale - v.ws) < v.ws * 0.012, exC = !hasCur || Math.abs(cur.vScale - v.ws) < v.ws * 0.012;
        if (directOn && exP && exC) {
            if (hasPrev && hasCur && prev.vpx == cur.vpx) { direct.over(cur.vpx, cur.vw, cur.vh, devX(dc.x + cur.vRelX), devY(dc.y + cur.vRelY)); return; }
            if (e.drawPrev == null) direct.over(cur.vpx, cur.vw, cur.vh, devX(dc.x + cur.vRelX), devY(dc.y + cur.vRelY));
            else direct.overFade(hasPrev ? prev.vpx : null, hasPrev ? prev.vw : 0, hasPrev ? prev.vh : 0, hasPrev ? devX(dc.x + prev.vRelX) : 0, hasPrev ? devY(dc.y + prev.vRelY) : 0,
                    hasCur ? cur.vpx : null, hasCur ? cur.vw : 0, hasCur ? cur.vh : 0, hasCur ? devX(dc.x + cur.vRelX) : 0, hasCur ? devY(dc.y + cur.vRelY) : 0, e.fade256);
            return;
        }
        Composite oc = g.getComposite();
        Object oh = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        if (hasPrev && f < 1) {
            g.setComposite(AlphaComposite.SrcOver.derive(1 - f));
            blitScaled(g, prev.veil, dc.x + prev.vRelX, dc.y + prev.vRelY, prev.vw, prev.vh, prev.vScale);
        }
        if (hasCur) {
            g.setComposite(AlphaComposite.SrcOver.derive(f));
            blitScaled(g, cur.veil, dc.x + cur.vRelX, dc.y + cur.vRelY, cur.vw, cur.vh, cur.vScale);
        }
        g.setComposite(oc);
        if (oh != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oh);
    }

    private void drawSelection(Graphics2D g, Shared.DrawCloud dc) {
        Model.Cloud c = dc.c;
        double top = cloudTop(c) - c.y + dc.y, base = cloudBase(c) - c.y + dc.y, l = dc.x - c.w / 2, r = dc.x + c.w / 2;
        if (c.puffs.length > 0) {
            l = Double.MAX_VALUE; r = -Double.MAX_VALUE;
            for (double[] p : c.puffs) { l = Math.min(l, dc.x + p[0] - p[2]); r = Math.max(r, dc.x + p[0] + p[2]); }
        }
        l += Math.min(0, c.shear); r += Math.max(0, c.shear);
        double x0 = v.sx(l) - 10, y0 = v.sy(top) - 10, x1 = v.sx(r) + 10, y1 = v.sy(base) + 10;
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{6, 6}, dph(anim * 12, 12)));
        g.setColor(new Color(255, 236, 140, 220));
        g.draw(new RoundRectangle2D.Double(x0, y0, x1 - x0, y1 - y0, 22, 22));
    }

    private final List<Rectangle2D> placedLabels = new ArrayList<>();
    /** v2.3 supercell tab of the showcase: where the arrow labels were drawn this frame (the storm's long label keeps clear of them); else null. */
    private List<Rectangle2D> arrowPills;

    private void drawCloudLabel(Graphics2D g, Shared.DrawCloud dc) {
        Model.Cloud c = dc.c;
        if (c.form < 0.08) return;
        double cxs = v.sx(dc.x);
        if (cxs < v.vx - 60 || cxs > v.vx + v.vw + 60) return;
        double top = v.sy(cloudTop(c) - c.y + dc.y), base = v.sy(cloudBase(c) - c.y + dc.y);
        boolean compact = v.zoom < 0.45;
        String name = c.name;
        if (compact) { int q = name.indexOf(' '); if (q > 0) name = name.substring(0, q); }
        g.setFont(F_BOLD);
        FontMetrics fm = g.getFontMetrics();
        int si = Model.stageIndex(c.stage);
        String sub = c.stage + (c.fair ? " · fair weather" : "") + (("none".equals(c.precipType)) ? "" : " · " + c.precipType)
                + (c.fromKind < 0 ? "" : c.morphing() ? String.format(" · %s → %s %.0f%%", Model.CLOUD_PREFIX[c.fromKind], Model.CLOUD_PREFIX[c.kind], Math.floor(c.morph * 100))
                : " · from " + c.fromType)
                + String.format(" · %.0f/%.0fs", c.age, c.lifetime);
        // v2.3: what it has absorbed (or what is absorbing it), and how hard a supercell spins
        if (c.mergingInto >= 0) {
            Model.Cloud into = curFrame == null ? null : curFrame.cloud(c.mergingInto);
            sub = "merging into " + (into == null ? "#" + c.mergingInto : shortName(into.name)) + String.format(" · %.0f %% left", Math.floor(c.form * 100));
        } else if (c.parts.length > 0) {
            StringBuilder ps = new StringBuilder();
            for (Model.Part pt : c.parts) ps.append(ps.length() > 0 ? " + " : "").append(pt.shortName());
            sub += c.merging() ? String.format(" · absorbing %s %.0f%%", c.parts[c.parts.length - 1].shortName(), Math.floor(c.merge * 100)) : " · merged with " + ps;
        }
        if (c.kind == Model.SUPERCELL) sub += String.format(" · rotation %.0f%%", c.rotation * 100) + (c.wall > 0.3 ? " · wall cloud" : "");
        g.setFont(F_SMALL);
        FontMetrics fs = g.getFontMetrics();
        int w = compact ? fm.stringWidth(name) + 26 : Math.max(fm.stringWidth(name), fs.stringWidth(sub) + 14) + 14;
        int h = compact ? 19 : 38;
        double x = Model.clamp(cxs - w / 2.0, v.vx + 4, v.vx + v.vw - w - 4), y = Math.max(v.vy + 4, top - h - 8);
        if (showLeftPanel && y < leftPanelBottom + 4 && x < v.vx + 282) x = Math.min(v.vx + 282, v.vx + v.vw - w - 4);
        Rectangle2D clim = climateRect;
        if (clim != null && y < clim.getMaxY() + 4 && x + w > clim.getX() - 6) x = Math.max(v.vx + 4, clim.getX() - w - 8);
        double[] cands = curFrame != null && curFrame.atmosphere != null
                ? new double[]{y, Math.max(v.vy + 4, top - 2 * h - 12), base + 8, Math.max(v.vy + 4, top - 3 * h - 16), base + h + 12, base + 2 * h + 16, Math.max(v.vy + 4, top - 4 * h - 20)}
                : new double[]{y, Math.max(v.vy + 4, top - 2 * h - 12), base + 8, Math.max(v.vy + 4, top - 3 * h - 16), base + h + 12};
        boolean placed = false;
        for (double cy : cands) {
            Rectangle2D r = new Rectangle2D.Double(x - 2, cy - 2, w + 4, h + 4);
            boolean hit = false;
            for (Rectangle2D o : placedLabels) if (o.intersects(r)) { hit = true; break; }
            if (!hit) { y = cy; placed = true; break; }
        }
        // no free place: a label drawn over another one is unreadable twice over, so only the selected cloud's
        // and a supercell's label is still forced (2026-10-09: two mature clouds' labels were printed on top of each other)
        if (!placed && cands.length > 5 && c.id != selId && (c.kind != Model.SUPERCELL || si <= 1 || c.form < 0.6 || c.mergingInto >= 0)) return;
        if (y > v.vy + v.vh || y + h < v.vy) return;
        if (!placed) {
            // a forced label still never goes over a HUD panel (2026-10-09: at the minimum window size the storm's
            // label was printed over the left panel's title and first rows)
            Rectangle2D me = new Rectangle2D.Double(x, y, w, h), mmr = minimapRect;
            if ((showLeftPanel && me.intersects(v.vx + 8, v.vy + 8, 270, Math.max(0, leftPanelBottom - v.vy - 4))) || (clim != null && me.intersects(clim))
                    || (mmr != null && me.intersects(mmr))) return;
        }
        placedLabels.add(new Rectangle2D.Double(x - 2, y - 2, w + 4, h + 4));
        if (y > bandSy1 || y + h < bandSy0) return;
        int a = (int) (150 * Math.min(1, c.form * 3));
        pill(g, x, y, w, h, new Color(12, 16, 26, a));
        if (compact) {
            g.setColor(alpha(STAGE_COL[si], 255));
            g.fill(new Ellipse2D.Double(x + 7, y + 6, 7, 7));
            g.setFont(F_BOLD);
            g.setColor(new Color(255, 255, 255, 235));
            g.drawString(name, (float) (x + 19), (float) (y + 14));
            return;
        }
        g.setFont(F_BOLD);
        g.setColor(new Color(255, 255, 255, 235));
        g.drawString(name, (float) (x + 7), (float) (y + 14));
        g.setColor(STAGE_COL[si]);
        g.fill(new Ellipse2D.Double(x + 7, y + 19, 7, 7));
        g.setFont(F_SMALL);
        g.setColor(new Color(220, 228, 240));
        g.drawString(sub, (float) (x + 18), (float) (y + 26));
        // life progress bar
        double bx = x + 7, bw = w - 14, by = y + 31;
        g.setColor(new Color(255, 255, 255, 40));
        g.fill(new Rectangle2D.Double(bx, by, bw, 3));
        g.setColor(STAGE_COL[si]);
        g.fill(new Rectangle2D.Double(bx, by, bw * Model.clamp(c.lifeFrac, 0, 1), 3));
    }

    private static String shortName(String name) {
        int q = name.indexOf(' ');
        return q > 0 ? name.substring(0, q) : name;
    }

    // ------------------------------------------------------------------------------ v2.3: tornadoes, cars, debris

    private static final Color TORNADO_RED = new Color(255, 116, 92);

    private void drawTornadoes(Graphics2D g, Model.Frame f) {
        if (f.tornadoes.isEmpty()) return;
        for (int pass = 0; pass < 2; pass++) {
            for (Model.Tornado t : f.tornadoes) {
                double reach = t.topWidth + Math.abs(t.lean) + t.width * 3 + 80;
                if (t.x + reach < v.wx0 || t.x - reach > v.wx1) continue;
                if (t.groundY + 30 < bandWy0 || t.baseY - 40 > bandWy1) continue;
                TornadoPainter.paint(g, t, lit, v.zoom, pass == 0);
            }
            if (pass == 0 && !sh.funnels.isEmpty()) {
                // the funnels between the far and the near side of their debris clouds
                g.setTransform(IDENTITY);
                for (TornadoPainter.Funnel fn : sh.funnels) {
                    if (directOn) direct.over(fn.px, fn.w, fn.h, fn.dx, fn.dy);
                    else g.drawImage(fn.image(), fn.dx, fn.dy, null);
                }
                g.setTransform(wTx);
            }
        }
    }

    /**
     * Supercell {@code hook}: the rear-flank downdraft carries a thin curtain of rain round the back of the
     * mesocyclone. Seen from the side it hangs from the base behind (upwind of) the wall cloud and curls in under
     * it. Drawn before the clouds, so it comes out of the base; the clear slot above it is cut by the cloud painter.
     */
    private void drawHooks(Graphics2D g) {
        for (Shared.DrawCloud dc : sh.drawClouds) {
            Model.Cloud c = dc.c;
            if (c.kind != Model.SUPERCELL || c.hook < 0.12 || Double.isNaN(c.mesoX) || c.form < 0.5) continue;
            double k = smooth((c.hook - 0.12) / 0.5) * smooth((c.form - 0.5) / 0.4);
            int dir = Math.abs(c.shear) > 1 ? (c.shear > 0 ? 1 : -1) : (c.x >= c.mesoX ? 1 : -1);
            double mx = c.mesoX - c.x + dc.x, base = cloudBase(c) - c.y + dc.y;
            double R = Math.max(30, c.w * 0.075), drop = Math.max(40, c.h * 0.50);
            if (!v.sees(mx - R * 2.6, base - 12, mx + R * 2.6, base + drop + 12)) continue;
            if (base + drop + 12 < bandWy0 || base - 12 > bandWy1) continue;
            Color rc = lit.lit(new Color(196, 208, 224));
            g.setStroke(new BasicStroke((float) Math.max(1.3, 1.1 / v.zoom), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int s = 0; s < 11; s++) {
                double u = s / 10.0, h1 = Scene.hash01(c.id * 61 + s * 13);
                double xs = mx - dir * R * (1.05 + 0.80 * u);                  // where the strand leaves the base (the rear side)
                double len = drop * (0.55 + 0.45 * h1) * (1 - 0.35 * u);
                double xe = xs + dir * R * (0.50 + 0.40 * (1 - u)) + Math.sin(anim * 1.3 + s * 1.7) * 2.5;   // ... and curls in under the wall cloud
                Path2D.Double p = new Path2D.Double();
                p.moveTo(xs, base - 6);
                p.quadTo(xs - dir * R * 0.12, base + len * 0.62, xe, base + len);
                g.setPaint(new GradientPaint((float) xs, (float) (base + len * 0.15), alpha(rc, (int) (78 * k * (1 - 0.4 * u))), (float) xe, (float) (base + len), alpha(rc, 0)));
                g.draw(p);
            }
        }
    }

    /**
     * OI: how deep the water is at world x, px - the film that runs over the ground (v2.4.1) or a standing body (a puddle
     * that became a pool, a street under water). Asked by {@link Life}: nobody walks through it.
     */
    private double waterDepthAt(double x) {
        double d = sh.flow.depthAt(x);
        if (sh.water.on && hello != null) {
            for (WaterPainter.Body b : sh.water.draw) {
                if (b.gone || x < b.x0 || x > b.x1) continue;
                d = Math.max(d, (hello.terrain.at(x) - b.y) * b.alpha);
            }
        }
        return d;
    }

    /** The ground a tornado has crossed: under the live one, and (fading to a faint trace) where earlier ones went. */
    private void drawScars(Graphics2D g, Model.Frame f) {
        if (tornadoSnap.isEmpty() || sh.scene == null) return;
        Model.Terrain terrain = sh.scene.terrain;
        for (ViewerState.TornadoRec r : tornadoSnap) {
            if (Double.isNaN(r.pathX0) || Double.isNaN(r.pathX1)) continue;
            double a = Math.min(r.pathX0, r.pathX1), b = Math.max(r.pathX0, r.pathX1);
            if (b + 12 < v.wx0 || a - 12 > v.wx1) continue;
            double strength = r.alive || Double.isNaN(r.endT) ? 1 : Model.clamp(1 - (f.t - r.endT) / 360.0, 0.35, 1);
            // Phase O (OW5): not on a street span - there the asphalt shows its own wear and the rubble piles, which the soil
            // band would cover (open ground and the highway keep the scar)
            for (double[] part : RoadWear.offStreet(hello, a, b))
                TornadoPainter.paintScar(g, terrain, part[0], part[1], strength, lit, r.id * 7919L + 13, v.wx0, v.wx1, v.zoom);
        }
    }

    /** @param airborne false = the cars on the ground (before the tornado), true = the ones it has lifted (after its funnel) */
    private void drawCars(Graphics2D g, boolean lightsOnly, boolean airborne) {
        if (sh.drawCars.isEmpty()) return;
        for (Shared.DrawCar dc : sh.drawCars) {
            if (!lightsOnly && (dc.lift > 6) != airborne) continue;
            // (a car on a steep road points its beam up or down the slope: about 100 px along its axis. Phase N: the flood scene has such roads,
            // and a band that skipped the car lost the part of the beam that reaches into it)
            final double beam = dc.c.lights ? 100 * Math.abs(Math.sin(dc.angle)) : 0;
            if (dc.y + 14 + beam < bandWy0 || dc.y - dc.lift - 70 - beam > bandWy1) continue;
            if (lightsOnly && !dc.c.lights) continue;
            CarPainter.paint(g, dc.c, dc.x, dc.y, dc.angle, dc.lift, dc.spin, lit, anim, v.zoom, lightsOnly, dc.wind);
        }
    }

    /**
     * Particle kind 7: debris thrown up by a tornado, drawn over the funnel. The simulation carries it up past the
     * cloud base; those bits are drawn before the clouds ({@code inCloud}), so the storm swallows them.
     */
    private void drawDebris(Graphics2D g, Model.Frame f, double ex, boolean inCloud) {
        if (inCloud && f.tornadoes.isEmpty()) return;
        float[] p = f.particles;
        int n = f.particleCount;
        final double pad = 24 + 10 / v.zoom;
        final double x0 = v.wx0 - pad, x1 = v.wx1 + pad, y0 = Math.max(v.wy0, bandWy0) - pad, y1 = Math.min(v.wy1, bandWy1) + pad;
        boolean any = false;
        for (int k = 0; k < n; k++) {
            int b = k * 7;
            if ((int) p[b] != 7) continue;
            double x = p[b + 1] + p[b + 3] * ex, y = p[b + 2] + p[b + 4] * ex;
            if (x < x0 || x > x1 || y < y0 || y > y1) continue;
            boolean above = false;
            if (!f.tornadoes.isEmpty()) {
                Model.Tornado near = null;
                for (Model.Tornado t : f.tornadoes) if (near == null || Math.abs(t.x - x) < Math.abs(near.x - x)) near = t;
                above = y < near.baseY + 4;
            }
            if (above != inCloud) continue;
            if (!any) { spriteBegin(g); any = true; }
            double turn = (p[b + 6] + ex * 6) / Math.PI;
            blit(g, 8, sizeQ(Math.max(1.2, p[b + 5])), (int) ((turn - Math.floor(turn)) * ROT_BUCKETS) % ROT_BUCKETS, x, y);
        }
        if (any) g.setTransform(wTx);
    }

    private static void drawDebrisBit(Graphics2D g, double s, double rot) {
        // a splinter of wood, a roof tile or a clod, by size
        int kind = (int) Math.round(s * 10) % 3;
        g.rotate(rot);
        g.setColor(kind == 0 ? new Color(132, 100, 66, 240) : kind == 1 ? new Color(58, 50, 46, 240) : new Color(98, 84, 70, 240));
        if (kind == 0) g.fill(new Rectangle2D.Double(-s * 1.1, -s * 0.22, s * 2.2, s * 0.44));
        else g.fill(new Rectangle2D.Double(-s * 0.7, -s * 0.45, s * 1.4, s * 0.9));
    }

    static final String[] EF_WORD = {"weak", "weak", "strong", "strong", "violent", "violent"};

    /** The label beside a tornado: its rating and wind, its stage and how far it has travelled. */
    private void drawTornadoLabel(Graphics2D g, Model.Tornado t) {
        double span = t.groundY - t.baseY, gMid = Math.min(t.reach, 0.62);
        double sxm = v.sx(TornadoPainter.axisX(t, gMid)), sym = v.sy(t.baseY + span * gMid);
        if (sxm < v.vx - 80 || sxm > v.vx + v.vw + 80) return;
        boolean compact = v.zoom < 0.45;
        boolean ending = !t.onGround() && t.stage.equals("roping");
        String head = t.onGround() ? String.format("TORNADO  EF%d", t.ef) : ending ? "TORNADO  ending" : "FUNNEL CLOUD";
        String sub = t.onGround()
                ? String.format("%s · %.0f m/s · %s%s", EF_WORD[Math.max(0, Math.min(5, t.ef))], t.windMs, t.stage.equals("roping") ? "roping out" : t.stage,
                Double.isNaN(t.pathX0) ? "" : String.format(" · path %.0f px", Math.abs(t.x - t.pathX0)))
                : ending ? "roped out · lifting back into the cloud" + (Double.isNaN(t.pathX0) ? "" : String.format(" · path %.0f px", Math.abs(t.x - t.pathX0)))
                : String.format("descending · %.0f %% of the way down", Math.floor(t.reach * 100));
        g.setFont(F_BOLD);
        FontMetrics fm = g.getFontMetrics();
        g.setFont(F_SMALL);
        FontMetrics fs = g.getFontMetrics();
        int w = (compact ? fm.stringWidth(head) : Math.max(fm.stringWidth(head), fs.stringWidth(sub))) + 22, h = compact ? 19 : 33;
        double half = Math.max(8, TornadoPainter.halfWidth(t, gMid / Math.max(0.05, t.reach)) * v.zoom);
        double[][] cands = {{sxm + half + 12, sym - h / 2.0}, {sxm - half - 12 - w, sym - h / 2.0}, {sxm + half + 12, sym - h - 26}, {sxm - half - 12 - w, sym + 26}, {sxm - w / 2.0, v.sy(t.groundY) + 14}};
        double x = cands[0][0], y = cands[0][1];
        for (double[] cd : cands) {
            double cx = Model.clamp(cd[0], v.vx + 4, v.vx + v.vw - w - 4), cy = Model.clamp(cd[1], v.vy + 4, v.vy + v.vh - h - 4);
            Rectangle2D r = new Rectangle2D.Double(cx - 2, cy - 2, w + 4, h + 4);
            boolean hit = false;
            for (Rectangle2D o : placedLabels) if (o.intersects(r)) { hit = true; break; }
            if (!hit) { x = cx; y = cy; break; }
        }
        placedLabels.add(new Rectangle2D.Double(x - 2, y - 2, w + 4, h + 4));
        if (y > bandSy1 || y + h < bandSy0) return;
        int a = (int) (235 * Math.min(1, t.width / 6.0));
        pill(g, x, y, w, h, new Color(40, 12, 10, Math.min(210, a)));
        g.setColor(alpha(TORNADO_RED, Math.min(200, a)));
        g.setStroke(new BasicStroke(1.1f));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, 12, 12));
        g.fill(new Ellipse2D.Double(x + 7, y + 6, 7, 7));
        g.setFont(F_BOLD);
        g.setColor(new Color(255, 236, 230, a));
        g.drawString(head, (float) (x + 19), (float) (y + 14));
        if (!compact) {
            g.setFont(F_SMALL);
            g.setColor(new Color(244, 214, 204, a));
            g.drawString(sub, (float) (x + 9), (float) (y + 27));
        }
    }

    private void drawSettlementLabels(Graphics2D g) {
        if (hello == null) return;
        Model.Terrain t = hello.terrain;
        for (Model.Settlement s : hello.settlements) {
            if (!v.seesX(s.x0, s.x1)) continue;
            double low = 0;
            for (double x = s.x0; x <= s.x1; x += 24) low = Math.max(low, t.at(x));
            double cx = v.sx((s.x0 + s.x1) / 2), y = v.sy(low) + 12;
            Model.Drain pipe = sh.showDrains && !decorative ? sh.drains.get(s.id) : null;
            if (pipe != null) y = v.sy(low + DrainPainter.PIPE_DEPTH + pipe.size + 9) + 6;      // v2.4: under the cutaway pipe, not on it
            String name = s.name.isEmpty() ? s.kind : s.name;
            String sub = s.kind + (s.population > 0 ? String.format(" · pop %,d", s.population) : "");
            g.setFont(F_BOLD);
            FontMetrics fm = g.getFontMetrics();
            g.setFont(F_SMALL);
            FontMetrics fs = g.getFontMetrics();
            boolean compact = v.zoom < 0.45;
            int w = (compact ? fm.stringWidth(name) : Math.max(fm.stringWidth(name), fs.stringWidth(sub))) + 18, h = compact ? 19 : 33;
            double x = Model.clamp(cx - w / 2.0, Math.max(v.vx + 4, v.sx(s.x0)), Math.max(v.vx + 4, Math.min(v.vx + v.vw - w - 4, v.sx(s.x1) - w)));
            if (y + h > v.vy + v.vh - 4) y = v.vy + v.vh - h - 4;
            Rectangle2D mm = minimapRect;
            if (showMinimap && mm != null && mm.intersects(x - 6, y - 6, w + 12, h + 12)) y = mm.getY() - h - 12;
            // ... and out from under the left panel (a tall region card reaches down to the towns: the name lay across its last line)
            if (showLeftPanel && y < leftPanelBottom + 4 && x < v.vx + 8 + 270 + 6) x = Math.min(v.vx + 8 + 270 + 6, v.vx + v.vw - w - 4);
            // ... and clear of the freezing-level label when the 0 C level sits at town height (below it, else above it)
            Rectangle2D fr = freezeLabelRect;
            if (fr != null && fr.intersects(x - 4, y - 4, w + 8, h + 8)) {
                y = fr.getMaxY() + 6;
                if (y + h > v.vy + v.vh - 4) y = fr.getY() - h - 6;
            }
            // v2.4: a "FLOODED" chip beside the name while the settlement's state is flooded or overflowing
            Model.Flood fl = DrainPainter.floodOf(sh.flood, s.id);
            int chipW = DrainPainter.chipWidth(g, fl);
            double chipX = x + w + 6 + chipW <= v.vx + v.vw - 4 ? x + w + 6 : x - chipW - 6;
            placedLabels.add(new Rectangle2D.Double(Math.min(x, chipX) - 2, y - 2, w + 4 + (chipW > 0 ? chipW + 6 : 0), h + 4));
            if (y > bandSy1 || y + h < bandSy0) continue;
            if (chipW > 0) DrainPainter.chip(g, chipX, y + (compact ? 1 : 8), fl);
            pill(g, x, y, w, h, new Color(14, 12, 10, 150));
            g.setColor(new Color(255, 226, 170, 70));
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Double(x, y, w, h, 12, 12));
            g.setFont(F_BOLD);
            g.setColor(new Color(255, 240, 214));
            g.drawString(name, (float) (x + 9), (float) (y + 14));
            if (!compact) {
                g.setFont(F_SMALL);
                g.setColor(new Color(216, 204, 186));
                g.drawString(sub, (float) (x + 9), (float) (y + 27));
            }
        }
    }

    // ------------------------------------------------------------------------------ particles

    /** The dark veil of {@link CloudShade}: the world viewport only, same picture underneath, HUD drawn afterwards. */
    private void drawShade(Graphics2D g) {
        int a = clampi((int) Math.round(sh.shadeAlpha * 255));
        if (a <= 0) return;
        g.setTransform(scrTx);
        g.setColor(new Color(5, 8, 16, a));
        g.fill(new Rectangle2D.Double(v.vx, v.vy, v.vw, v.vh));
    }

    /** May a cloud drawn this frame hide a particle at this world point? */
    private boolean underCloudImage(double x, double y) {
        double[] b = sh.cloudBoxes;
        for (int i = 0; i < b.length; i += 4) if (x >= b[i] && x <= b[i + 2] && y >= b[i + 1] && y <= b[i + 3]) return true;
        return false;
    }

    /**
     * "Don't render what you can't see": will every device pixel of the rectangle [x0, x1] x [y0, y1] (inclusive) be
     * overwritten by an opaque pixel of a cloud drawn later? Only asked for what is drawn before the clouds ({@link #occlude}).
     * The answer depends on nothing but the frame, so every band gives the same one; a doubtful case is "no".
     */
    private boolean hiddenByCloud(int x0, int y0, int x1, int y1) {
        final Shared s = sh;
        for (int i = 0; i < s.occN; i++) {
            int ox = s.occX[i], oy = s.occY[i];
            if (!s.occImg[i].opaque(x0 - ox, y0 - oy, x1 - ox, y1 - oy)) continue;
            CloudPainter.Image pv = s.occPrev[i];
            if (pv != null && !pv.opaque(x0 - s.occPX[i], y0 - s.occPY[i], x1 - s.occPX[i], y1 - s.occPY[i])) continue;
            CULLED.increment();
            return true;
        }
        return false;
    }

    /** True while the particles that lie behind the clouds are drawn with "Don't render what you can't see" on: a sprite wholly behind an opaque part of a cloud is not blitted. */
    private boolean occlude;

    /** Sprites left out because a cloud hides them (all render bands together; diagnostics, tests). */
    static final java.util.concurrent.atomic.LongAdder CULLED = new java.util.concurrent.atomic.LongAdder();

    /**
     * @param part 0 = every particle of the pass; 1 = only those a cloud may hide; 2 = only the others,
     *             drawn over the under-cloud veil with more contrast
     */
    private void drawParticles(Graphics2D g, Model.Frame f, double ex, boolean inCloudPass, int part) {
        float[] p = f.particles;
        int n = f.particleCount;
        if (n == 0) return;
        // how much the precipitation is emphasised over the veil: 0 = as always .. 1 = fully under a dark cloud
        final double boost = part == 2 ? Model.clamp(sh.shadeAlpha / (CloudShade.MAX_ALPHA * 0.65), 0, 1) : 0;
        final double pad = 24 + 10 / v.zoom;
        final double x0 = v.wx0 - pad, x1 = v.wx1 + pad, y0 = Math.max(v.wy0, bandWy0) - pad, y1 = Math.min(v.wy1, bandWy1) + pad;
        final Advanced adv = sh.adv;
        final int pct = adv.maxParticles;
        // "Max particles": the ranks the particles carry from frame to frame (null = this frame has none: ranked by position)
        final byte[] rank = pct < 100 && f.particleRank != null && f.particleRank.length >= n ? f.particleRank : null;
        final boolean noShine = adv.noShine, noTrails = adv.noTrails;
        // what is drawn before the clouds (not part 2: that is drawn over them) can be hidden by one; the clouds are then
        // copied over it by Direct.over, which replaces a pixel outright where the cloud is opaque
        final boolean cull = adv.cullHidden && !inCloudPass && part != 2 && directOn && sh.occN > 0;
        if (inCloudPass) {
            // droplets (0) and ice crystals (3) are drawn over the clouds
            spriteBegin(g);
            for (int k = 0; k < n; k++) {
                int b = k * 7;
                int kind = (int) p[b];
                if (kind != 0 && kind != 3) continue;
                double x = p[b + 1] + p[b + 3] * ex, y = p[b + 2] + p[b + 4] * ex;
                if (x < x0 || x > x1 || y < y0 || y > y1) continue;
                if (pct < 100 && (rank != null ? rank[k] : particleRank(p, b, 0)) >= pct) continue;
                if (kind == 0) { blit(g, 0, 0, 0, x, y); continue; }
                double s = Math.max(1.5, p[b + 5]);
                int sq = sizeQ(s);
                blit(g, 3, sq, rotQ(p[b + 6]), x, y);
                if (noShine) continue;
                double tw = Math.sin(anim * 5.5 + k * 2.37 + x * 0.05);
                if (tw > 0.55) {
                    int aq = (int) Math.round((tw - 0.55) / 0.45 * 31);
                    if (aq > 0) blit(g, 7, sq, aq, x, y);
                }
            }
            g.setTransform(wTx);
            return;
        }
        // rain: every drop is a long motion-blurred streak (a cached mask tinted as it is blitted: tail fading out,
        // head brightest) with the drop itself as a small round dot at its head, and some carry a glint; sleet keeps its
        // short batched trail
        sleetPath.reset();
        boolean anySleet = false;
        spriteBegin(g);
        rainBegin(boost);
        occlude = cull;
        final double dev = v.dev;
        // the longest streak of this pass in device px, and how far (world px) a drop outside the band can reach into it
        final double capPx = RAIN_CAP_PX * dev, floorPx = RAIN_FLOOR_PX * dev, lenBoost = 1 + 0.45 * boost;
        final double reach = pad + (Math.min(capPx, Math.max(floorPx, RAIN_LEN_MAX * 1.5 * v.ws)) * 1.14 * lenBoost + 12 * dev) / v.ws;
        final double rx0 = v.wx0 - reach, rx1 = v.wx1 + reach, ry0 = Math.max(v.wy0, bandWy0) - reach, ry1 = Math.min(v.wy1, bandWy1) + reach;
        // the drop: a round dot a little wider than the head of its streak, so it only grows slightly with the zoom
        // (without trails the dot is all there is of the rain: it is never smaller than two device px across)
        final double dotMin = (noTrails ? RAIN_DOT_MIN_ALONE : RAIN_DOT_MIN) * dev, dotPad = RAIN_DOT_PAD * dev;
        final int rimQ = (int) Math.round(RAIN_RIM_PX * dev * 4);            // how far the blue rim reaches past the dot, quarter device px
        // the glint: a tiny bright dot on the drop, or (Settings > Advanced "Rain glint: Star") the four-point star it used to be
        final boolean glintStar = glintShape < 0 ? adv.glintStar : glintShape == 1;
        final int glintQ = (int) Math.round(dev * Model.clamp(2.6 + 1.6 * v.zoom, 3.5, 9) * 2);   // the star's arm, half device px
        final double tw = epilepsySafe ? Double.NaN : anim * 6.0;
        glintN = 0;
        boolean anyGlint = false;
        int minH = Integer.MAX_VALUE, minK = -1;
        for (int k = 0; k < n; k++) {
            int b = k * 7;
            int kind = (int) p[b];
            if (kind != 1 && kind != 6) continue;
            double vx = p[b + 3], vy = p[b + 4];
            double x = p[b + 1] + vx * ex, y = p[b + 2] + vy * ex;
            if (kind == 6) {
                if (x < x0 || x > x1 || y < y0 || y > y1) continue;
                if (part != 0 && underCloudImage(x, y) != (part == 1)) continue;
                if (noTrails) continue;                       // (the sleet drop itself is drawn with the sprites below)
                if (pct < 100 && (rank != null ? rank[k] : particleRank(p, b, 0)) >= pct) continue;
                double sp = Math.hypot(vx, vy);
                double len = Model.clamp(sp * 0.03, 2, 7) * lenBoost;
                double dx = sp > 0.1 ? vx / sp : 0, dy = sp > 0.1 ? vy / sp : 1;
                if (cull) {
                    // (the trail is stroked by Java2D: the box of the line, widened by the stroke and two pixels of antialiasing)
                    double ax = v.ws * x + v.wtx, ay = v.ws * y + v.wty, bx = v.ws * (x - dx * len) + v.wtx, by = v.ws * (y - dy * len) + v.wty;
                    double pd = Math.max(1.0, 0.85 / v.zoom) * v.ws + 3;
                    if (hiddenByCloud((int) Math.floor(Math.min(ax, bx) - pd), (int) Math.floor(Math.min(ay, by) - pd),
                            (int) Math.ceil(Math.max(ax, bx) + pd), (int) Math.ceil(Math.max(ay, by) + pd))) continue;
                }
                sleetPath.moveTo((float) x, (float) y);
                sleetPath.lineTo((float) (x - dx * len), (float) (y - dy * len));
                anySleet = true;
                continue;
            }
            if (x < rx0 || x > rx1) continue;
            boolean inBand = y >= ry0 && y <= ry1;
            // the glint is chosen among the drops of the whole view, so every render band picks the same ones
            boolean inView = x >= v.wx0 && x <= v.wx1 && y >= v.wy0 && y <= v.wy1;
            if (!inBand && !inView) continue;
            if (part != 0 && underCloudImage(x, y) != (part == 1)) continue;
            if (pct < 100 && (rank != null ? rank[k] : particleRank(p, b, 0)) >= pct) continue;
            float size = p[b + 5];
            // per-drop variation from what stays the same while it falls (its size and the line it falls along):
            // nothing random, nothing that depends on the list order, so no flicker from frame to frame
            double slope = Math.abs(vy) > 1 ? vx / vy : 0;
            int h = rainHash((int) Math.floor((x - slope * y) / RAIN_CELL), Float.floatToIntBits(size));
            boolean glint = !noShine && h % RAIN_GLINT_EVERY == 0;
            if (inView) {
                if (glint) anyGlint = true;
                else if (h < minH) { minH = h; minK = k; }
            }
            if (!inBand) continue;
            double sp = Math.hypot(vx, vy);
            double dx = sp > 0.1 ? vx / sp : 0, dy = sp > 0.1 ? vy / sp : 1;
            double sz = Model.clamp(size, 0.6, 3.0);
            double w = rnWidth * (0.7 + 0.2 * sz);
            double hx = spTx + spS * x, hy = spTy + spS * y;
            if (!noTrails) {                                  // ("No trails": the dot alone, no streak behind it)
                double lenW = Model.clamp(sp * RAIN_EXPOSURE, RAIN_LEN_MIN, RAIN_LEN_MAX) * (0.75 + 0.25 * sz);
                double lenPx = Model.clamp(lenW * v.ws, floorPx, capPx) * RAIN_LEN_JITTER[(h >>> 5) & 3] * lenBoost;
                // quantised in device pixels (length 2 px, direction 1.4 degrees, width 1/4 px): the same masks serve every zoom
                int lenQ = Math.max(2, Math.min(127, (int) Math.round(lenPx / 2)));
                int angQ = sp > 0.1 ? (int) Math.round(Math.atan2(vy, vx) * (128 / Math.PI)) & 255 : 64;
                blitStreak(g, lenQ, angQ, Math.max(2, Math.min(31, (int) Math.round(w * 4))), (h >>> 3) % 3, hx, hy);
            }
            // the dot sits with its leading edge on the head: white (the shine every drop has), on a thin blue rim once
            // it is big enough to show one
            double rc = Math.max(dotMin, 0.5 * w + dotPad);
            int rQ = Math.max(2, Math.min(100, (int) Math.round(rc * 4)));
            double cx = hx - dx * rc, cy = hy - dy * rc;
            if (rnRimA > 0) blitDot(g, rQ + rimQ, RAIN_RIM_RGB, rnRimA, cx, cy);
            blitDot(g, rQ, 0xFFFFFF, 256, cx, cy);
            if (glint) addGlint(cx, cy, glintLevel(h, tw), rQ);
        }
        // "no matter what": when none of the drops in view is a glint carrier, the one with the lowest hash becomes one
        if (!noShine && !anyGlint && minK >= 0) {
            int b = minK * 7;
            double vx = p[b + 3], vy = p[b + 4], hx = spTx + spS * (p[b + 1] + vx * ex), hy = spTy + spS * (p[b + 2] + vy * ex);
            if (glintStar) addGlint(hx, hy, glintLevel(minH, tw), 0);       // (the star sits on the head, as it always did)
            else {
                // the dot sits exactly on the drop's own dot
                double sp = Math.hypot(vx, vy), rc = Math.max(dotMin, 0.5 * rnWidth * (0.7 + 0.2 * Model.clamp(p[b + 5], 0.6, 3.0)) + dotPad);
                addGlint(hx - (sp > 0.1 ? vx / sp : 0) * rc, hy - (sp > 0.1 ? vy / sp : 1) * rc, glintLevel(minH, tw), Math.max(2, Math.min(100, (int) Math.round(rc * 4))));
            }
        }
        if (glintStar) for (int k = 0; k < glintN; k++) blitGlint(g, glintQ, (int) glintXY[k * 4 + 2], glintXY[k * 4], glintXY[k * 4 + 1]);
        else for (int k = 0; k < glintN; k++) blitGlintDot(g, (int) glintXY[k * 4 + 3], (int) glintXY[k * 4 + 2], glintXY[k * 4], glintXY[k * 4 + 1]);
        if (anySleet) {
            float thin = (float) Math.max(1.0, 0.85 / v.zoom);
            g.setTransform(wTx);
            g.setStroke(new BasicStroke(thin, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(boost > 0 ? alpha(SLEET_TRAIL, (int) Math.round(110 + 80 * boost)) : SLEET_TRAIL);
            g.draw(sleetPath);
        }
        pm(14);

        spriteBegin(g);
        for (int k = 0; k < n; k++) {
            int b = k * 7;
            int kind = (int) p[b];
            if (kind < 2 || kind == 3 || kind > 6) continue;
            double x = p[b + 1] + p[b + 3] * ex, y = p[b + 2] + p[b + 4] * ex, s = p[b + 5];
            if (x < x0 || x > x1 || y < y0 || y > y1) continue;
            if (part != 0 && underCloudImage(x, y) != (part == 1)) continue;
            if (kind == 2 ? adv.noSnow : kind == 5 && adv.noPuddleImpact) continue;      // "No snow", "No puddle impact" (the splashes)
            if (pct < 100 && (rank != null ? rank[k] : particleRank(p, b, 0)) >= pct) continue;
            switch (kind) {
                case 2 -> {
                    double ss = Math.max(1.2, s);
                    blit(g, 2, sizeQ(ss), ss < 2.3 ? 0 : rotQ(p[b + 6] + ex * 1.5), x, y);
                }
                case 4 -> blit(g, 4, sizeQ(Math.max(1.2, s)), 0, x, y);
                case 5 -> blit(g, 5, sizeQ(Math.max(1, s)), 0, x, y);
                case 6 -> blit(g, 6, sizeQ(Math.max(1.2, s)), 0, x, y);
                default -> { }
            }
        }
        occlude = false;
        g.setTransform(wTx);
    }

    /**
     * Settings > Advanced "Max particles": the rank 0..99 of a particle that has none yet; it is drawn while its rank is below
     * the percentage. Made from where the particle is and (for a new one in a running stream) the frame it appeared in:
     * nothing random and nothing that depends on the list order, so every band and every pass decide alike. From frame to
     * frame a particle keeps its rank ({@code ViewerState.rankParticles}); this alone is only used for a frame that was
     * never ranked (a still picture, or the frame on screen at the moment the option is switched on).
     * @param b index of the particle's first number in {@code p} (kind, x, y, vx, vy, size, rot)
     */
    static int particleRank(float[] p, int b, int salt) {
        return (rainHash(Float.floatToIntBits(p[b + 1]) * 31 + Float.floatToIntBits(p[b + 2]), Float.floatToIntBits(p[b + 5]) ^ salt) >>> 12) % 100;
    }

    private final GeneralPath sleetPath = new GeneralPath();
    private static final Color SLEET_TRAIL = new Color(200, 225, 255, 110);

    // ---- rain streaks ---------------------------------------------------------------------
    // A raindrop is drawn the way a camera sees it: a long thin streak along its velocity (what it travels in
    // RAIN_EXPOSURE seconds), brightest at the head and fading out along the tail. The drop itself is a small
    // round white dot at the head, a little wider than the streak at every zoom (zoomed in it gets a thin blue
    // rim, never a bigger shape), and one drop in RAIN_GLINT_EVERY carries a glint, so the rain always sparkles: a
    // tiny bright dot on the drop, a little larger than the drop's own, in a faint glow (Settings > Advanced
    // "Rain glint: Star" brings back the four-point star, which never shrinks below a few device pixels).
    //
    // Cost: nothing here is rebuilt when the camera zooms or the daylight changes. A streak is a cached coverage
    // mask (bytes) that depends only on its length, direction and width in device pixels; its colour and its
    // brightness (daylight, depth tier, the boost over the under-cloud veil) are applied while it is blitted.
    // The dot and the glint's glow are masks and the star a small sprite, all keyed by their size in device pixels,
    // so one zoom shares them with the next. No paint object and no allocation per drop.
    private static final double RAIN_EXPOSURE = 0.11;     // seconds of fall one streak shows
    private static final double RAIN_LEN_MIN = 14, RAIN_LEN_MAX = 70;   // world px, before the drop-size factor
    private static final double RAIN_FLOOR_PX = 20;       // a streak is never shorter than this on screen (logical px) ...
    private static final double RAIN_CAP_PX = 96;         // ... nor longer: a close-up is not a wall of lines
    private static final double RAIN_CELL = 6;            // world px: drops falling along the same line share their variation
    private static final int RAIN_GLINT_EVERY = 6;        // one drop in this many carries the glint
    private static final double[] RAIN_LEN_JITTER = {0.86, 0.95, 1.05, 1.14};
    private static final double[] RAIN_TIER_ALPHA = {0.62, 0.8, 1.0};   // nearer drops are brighter
    // the dot at the head: its radius is half the head width of the streak plus RAIN_DOT_PAD, at least RAIN_DOT_MIN
    // (logical px; RAIN_DOT_MIN_ALONE when "No trails" leaves the dot on its own)
    private static final double RAIN_DOT_PAD = 0.15, RAIN_DOT_MIN = 0.7, RAIN_DOT_MIN_ALONE = 1.0;
    private static final double RAIN_RIM_Z0 = 1.5, RAIN_RIM_Z1 = 2.8;   // the dot's blue rim fades in between these zooms ...
    private static final double RAIN_RIM_PX = 0.45;                     // ... and reaches this far past the dot (logical px)
    private static final int RAIN_RIM_RGB = 0x6C98E0, RAIN_RIM_ALPHA = 150;   // (alpha 0..256; "No trails": at every zoom)
    // the glint as a dot: its radius is GLINT_DOT_GROW times the drop's own dot plus GLINT_DOT_PAD, at least GLINT_DOT_MIN
    // (logical px), and pulses a little with its glow; the glow reaches GLINT_GLOW_REACH radii and fades out to its edge
    private static final double GLINT_DOT_GROW = 1.3, GLINT_DOT_PAD = 0.3, GLINT_DOT_MIN = 1.15, GLINT_GLOW_REACH = 2.3;
    private static final double[] GLINT_DOT_PULSE = {0.90, 0.97, 1.04, 1.10};      // by glint level 0..3
    private static final int GLINT_GLOW_RGB = 0xE1F0FF;
    private static final int[] GLINT_GLOW_ALPHA = {60, 92, 124, 156};              // (0..256) by glint level
    private static final int STREAK_KEY = 1 << 30, DOT_KEY = 1 << 29, GLOW_KEY = 1 << 28, GLINT_KEY = 1 << 27;
    private static final long RAIN_ATLAS_BYTES = 24L << 20;             // per render band; reached only by very varied rain

    private final Sprites rainSprites = new Sprites();
    private long rainBytes;
    // only when the target is not one of our int buffers: tinted copies of the masks, by (mask key, colour)
    private final HashMap<Long, BufferedImage> rainTinted = new HashMap<>();
    // diagnostics (all render bands together): rain sprites built, the time that took, their pixels, atlas clears
    static final java.util.concurrent.atomic.LongAdder RAIN_BUILDS = new java.util.concurrent.atomic.LongAdder(), RAIN_BUILD_NS = new java.util.concurrent.atomic.LongAdder(),
            RAIN_BUILD_PX = new java.util.concurrent.atomic.LongAdder(), RAIN_CLEARS = new java.util.concurrent.atomic.LongAdder();
    // per pass: head width before the size factor (device px), streak colour, alphas (0..256)
    private double rnWidth = 1;
    private int rnRgb, rnRimA;
    private final int[] rnTierA = new int[3];
    /** The shape of the rain glint in this view: -1 = as Settings > Advanced says, 0 = dot, 1 = star (the Debug mode shows both without touching the setting). */
    int glintShape = -1;
    private double[] glintXY = new double[4 * 128];              // device x, y, level and the drop's dot radius (quarter px) of the glints of this pass
    private int glintN;

    /** Start a rain pass (after {@link #spriteBegin}): the colour and the zoom-dependent sizes of this pass. */
    private void rainBegin(double boost) {
        if (rainBytes > RAIN_ATLAS_BYTES || rainSprites.size() > 40_000) { rainSprites.clear(); rainBytes = 0; RAIN_CLEARS.increment(); }
        double t = Model.clamp((v.zoom - RAIN_RIM_Z0) / (RAIN_RIM_Z1 - RAIN_RIM_Z0), 0, 1);
        // ("No trails": the rim at every zoom, so the lone dot also shows against a pale sky)
        rnRimA = (int) Math.round(RAIN_RIM_ALPHA * (sh.adv.noTrails ? 1 : t * t * (3 - 2 * t)));
        rnWidth = v.dev * Model.clamp(1.15 + 0.5 * (v.zoom - 1), 1.15, 2.6) * (1 + 0.22 * boost);
        Color base = mix(mix(new Color(170, 200, 240), new Color(215, 232, 255), Model.clamp(day, 0, 1)), new Color(236, 244, 255), boost);
        rnRgb = base.getRGB() & 0xFFFFFF;
        // (the streak keeps its strength at every zoom: it no longer thins out to make room for a big drop)
        double aH = (205 + 45 * boost) / 255;
        for (int k = 0; k < 3; k++) rnTierA[k] = (int) Math.round(256 * aH * RAIN_TIER_ALPHA[k]);
    }

    static int rainHash(int cell, int sizeBits) {
        int h = cell * 0x9E3779B1 + sizeBits * 0x85EBCA6B;
        h ^= h >>> 15; h *= 0x2C1B3C6D;
        h ^= h >>> 12; h *= 0x297A2D39;
        h ^= h >>> 15;
        return h & 0x7FFFFFFF;
    }

    /** How bright a glint is right now, 0..3: it pulses about once a second but never goes out (steady when Epilepsy-safe). */
    private static int glintLevel(int h, double tw) {
        if (Double.isNaN(tw)) return 2;
        return (int) ((0.5 + 0.5 * Math.sin(tw + (h >>> 8) * 0.0021)) * 3.999);
    }

    private void addGlint(double dx, double dy, int level, int rQ) {
        if (glintN * 4 + 4 > glintXY.length) glintXY = Arrays.copyOf(glintXY, glintXY.length * 2);
        glintXY[glintN * 4] = dx; glintXY[glintN * 4 + 1] = dy; glintXY[glintN * 4 + 2] = level; glintXY[glintN * 4 + 3] = rQ;
        glintN++;
    }

    private Sprites.Sprite rainPut(int key, Sprites.Sprite sp, long t0) {
        rainSprites.put(key, sp);
        rainBytes += (long) sp.w * sp.h * (sp.mask != null ? 1 : 8) + 64;
        RAIN_BUILDS.increment(); RAIN_BUILD_NS.add(System.nanoTime() - t0); RAIN_BUILD_PX.add((long) sp.w * sp.h);
        return sp;
    }

    /** Blit a coverage mask in one colour, its anchor at device (x, y). {@code a256} = opacity 0..256. */
    private void blitMask(Graphics2D g, int key, Sprites.Sprite sp, int rgb, int a256, double x, double y) {
        if (sp.mask == null || a256 <= 0) return;
        int ix = (int) Math.floor(x + 0.5) + sp.ox, iy = (int) Math.floor(y + 0.5) + sp.oy;
        if (occlude && hiddenByCloud(ix, iy, ix + sp.w - 1, iy + sp.h - 1)) return;
        if (directOn) { direct.overMask(sp.mask, sp.w, sp.h, ix, iy, rgb, a256); return; }
        // any other target: the same pixels as a tinted image (kept per mask and colour)
        long tk = ((long) key << 33) | ((long) Math.min(256, a256) << 24) | rgb;
        BufferedImage img = rainTinted.get(tk);
        if (img == null) {
            if (rainTinted.size() > 4000) rainTinted.clear();
            int[] px = new int[sp.w * sp.h];
            int r = (rgb >> 16) & 255, gg = (rgb >> 8) & 255, b = rgb & 255;
            for (int i = 0; i < px.length; i++) {
                int a = ((sp.mask[i] & 255) * a256) >> 8;
                px[i] = a << 24 | (r * a + 127) / 255 << 16 | (gg * a + 127) / 255 << 8 | (b * a + 127) / 255;
            }
            img = new BufferedImage(sp.w, sp.h, BufferedImage.TYPE_INT_ARGB_PRE);
            img.getRaster().setDataElements(0, 0, sp.w, sp.h, px);
            rainTinted.put(tk, img);
        }
        g.drawImage(img, ix, iy, null);
    }

    /** The streak of one drop: its head at device (hx, hy), {@code lenQ * 2} px long, {@code wQ / 4} px wide at the head. */
    private void blitStreak(Graphics2D g, int lenQ, int angQ, int wQ, int tier, double hx, double hy) {
        int key = STREAK_KEY | ((lenQ << 8 | angQ) << 5 | wQ);
        Sprites.Sprite sp = rainSprites.get(key);
        if (sp == null) {
            long t0 = System.nanoTime();
            sp = rainPut(key, makeStreakMask(lenQ * 2.0, angQ * (Math.PI / 128), wQ / 4.0), t0);
        }
        blitMask(g, key, sp, rnRgb, rnTierA[tier], hx, hy);
    }

    /** A round dot in one colour, centred on device (cx, cy): the drop at the head of a streak. {@code rQ / 4} = its radius, {@code a256} = opacity 0..256. */
    private void blitDot(Graphics2D g, int rQ, int rgb, int a256, double cx, double cy) {
        int key = DOT_KEY | rQ;
        Sprites.Sprite sp = rainSprites.get(key);
        if (sp == null) {
            long t0 = System.nanoTime();
            sp = rainPut(key, makeDotMask(rQ / 4.0), t0);
        }
        blitMask(g, key, sp, rgb, a256, cx, cy);
    }

    /**
     * The glint as a tiny dot, centred on the drop's own dot at device (cx, cy): a faint soft glow, then a white dot a little
     * larger than the drop's. No arms. {@code rQ / 4} = the radius of the drop's dot; the level (0..3) is the pulse.
     */
    private void blitGlintDot(Graphics2D g, int rQ, int level, double cx, double cy) {
        double r = Math.max(GLINT_DOT_MIN * v.dev, rQ / 4.0 * GLINT_DOT_GROW + GLINT_DOT_PAD * v.dev) * GLINT_DOT_PULSE[level];
        int glowQ = Math.max(4, Math.min(400, (int) Math.round(r * GLINT_GLOW_REACH * 2)));        // half device px
        int key = GLOW_KEY | glowQ;
        Sprites.Sprite sp = rainSprites.get(key);
        if (sp == null) {
            long t0 = System.nanoTime();
            sp = rainPut(key, makeGlowMask(glowQ / 2.0), t0);
        }
        blitMask(g, key, sp, GLINT_GLOW_RGB, GLINT_GLOW_ALPHA[level], cx, cy);
        blitDot(g, Math.max(2, Math.min(200, (int) Math.round(r * 4))), 0xFFFFFF, 256, cx, cy);
    }

    private void blitGlint(Graphics2D g, int lQ, int level, double cx, double cy) {
        int key = GLINT_KEY | lQ << 2 | level;
        Sprites.Sprite sp = rainSprites.get(key);
        if (sp == null) {
            long t0 = System.nanoTime();
            sp = rainPut(key, makeGlint(lQ / 2.0, 0.55 + 0.15 * level), t0);
        }
        if (sp.img == null) return;
        int ix = (int) Math.floor(cx + 0.5), iy = (int) Math.floor(cy + 0.5);
        if (occlude && hiddenByCloud(ix + sp.ox, iy + sp.oy, ix + sp.ox + sp.w - 1, iy + sp.oy + sp.h - 1)) return;
        if (directOn) direct.over(sp.px, sp.w, sp.h, ix + sp.ox, iy + sp.oy);
        else g.drawImage(sp.img, ix + sp.ox, iy + sp.oy, null);
    }

    private static Graphics2D spriteGraphics(BufferedImage img, int ax, int ay) {
        Graphics2D sg = img.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        sg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        sg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        sg.translate(ax, ay);
        return sg;
    }

    /**
     * The coverage of one streak, in device pixels: a sliver from the head (the anchor) to the tail {@code len} px behind
     * it, {@code w} wide at the head and narrowing to 70 % of that, full strength at the head and fading to nothing at
     * the tail. Computed directly (distance to the centre line), so a new one costs a few microseconds.
     * @param ang direction of fall (radians, screen axes)
     */
    private static Sprites.Sprite makeStreakMask(double len, double ang, double w) {
        double dx = Math.cos(ang), dy = Math.sin(ang);
        double ux = -dx, uy = -dy;                       // from the head towards the tail
        double h0 = w * 0.5, h1 = w * 0.35, m = h0 + 1.5;
        double tx = ux * len, ty = uy * len;
        int ax = (int) Math.ceil(Math.max(0, -tx) + m), ay = (int) Math.ceil(Math.max(0, -ty) + m);
        int mw = ax + (int) Math.ceil(Math.max(0, tx) + m) + 1, mh = ay + (int) Math.ceil(Math.max(0, ty) + m) + 1;
        byte[] a = new byte[mw * mh];
        for (int py = 0; py < mh; py++) {
            double y = py - ay + 0.5;                    // pixel centres: the anchor is the corner of its pixel, as for the drawn sprites
            for (int px = 0; px < mw; px++) {
                double x = px - ax + 0.5;
                double u = x * ux + y * uy;              // along the streak: 0 at the head, len at the tail
                if (u < -0.5 || u > len + 0.5) continue;
                double d = Math.abs(x * uy - y * ux);    // across it
                double f = u <= 0 ? 0 : u >= len ? 1 : u / len;
                double hw = h0 + (h1 - h0) * f;
                double c = Math.min(hw, d + 0.5) - Math.max(-hw, d - 0.5);
                if (c <= 0) continue;
                double grad = f < 0.35 ? 1 - 0.15 * (f / 0.35) : f < 0.75 ? 0.85 - 0.45 * ((f - 0.35) / 0.4) : 0.4 * (1 - (f - 0.75) / 0.25);
                double ends = Math.min(1, u + 0.5) * Math.min(1, len + 0.5 - u);
                int q = (int) Math.round(255 * Math.min(1, c) * grad * ends);
                if (q > 0) a[py * mw + px] = (byte) q;
            }
        }
        return new Sprites.Sprite(a, mw, mh, -ax, -ay);
    }

    /** A round antialiased dot of radius {@code r} device px around the anchor. */
    private static Sprites.Sprite makeDotMask(double r) {
        int pad = (int) Math.ceil(r + 0.5), n = pad * 2 + 1;
        byte[] a = new byte[n * n];
        for (int py = 0; py < n; py++) for (int px = 0; px < n; px++) {
            double c = r + 0.5 - Math.hypot(px - pad + 0.5, py - pad + 0.5);
            if (c > 0) a[py * n + px] = (byte) Math.round(255 * Math.min(1, c));
        }
        return new Sprites.Sprite(a, n, n, -pad, -pad);
    }

    /** A soft round glow of radius {@code r} device px around the anchor: full at the centre, fading out (squared) to the edge. */
    private static Sprites.Sprite makeGlowMask(double r) {
        int pad = (int) Math.ceil(r), n = pad * 2 + 1;
        byte[] a = new byte[n * n];
        for (int py = 0; py < n; py++) for (int px = 0; px < n; px++) {
            double f = 1 - Math.hypot(px - pad + 0.5, py - pad + 0.5) / r;
            if (f > 0) a[py * n + px] = (byte) Math.round(255 * f * f);
        }
        return new Sprites.Sprite(a, n, n, -pad, -pad);
    }

    /** The glint as a star ("Rain glint: Star"): four points (arm {@code L} device px, so it never shrinks away) with a soft glow and a solid white core. */
    private Sprites.Sprite makeGlint(double L, double a) {
        double t = Math.max(0.55 * v.dev, L * 0.14);
        int pad = (int) Math.ceil(L) + 2;
        BufferedImage img = new BufferedImage(pad * 2 + 1, pad * 2 + 1, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D sg = spriteGraphics(img, pad, pad);
        sg.setPaint(new RadialGradientPaint(new Point2D.Double(0, 0), (float) (L * 0.75), new float[]{0f, 1f},
                new Color[]{new Color(225, 240, 255, (int) (150 * a)), new Color(225, 240, 255, 0)}));
        sg.fill(new Ellipse2D.Double(-L * 0.75, -L * 0.75, L * 1.5, L * 1.5));
        Path2D.Double star = new Path2D.Double();
        star.moveTo(L, 0); star.lineTo(t, t); star.lineTo(0, L); star.lineTo(-t, t);
        star.lineTo(-L, 0); star.lineTo(-t, -t); star.lineTo(0, -L); star.lineTo(t, -t);
        star.closePath();
        sg.setColor(new Color(255, 255, 255, (int) (240 * a)));
        sg.fill(star);
        double rc = Math.max(0.9 * v.dev, L * 0.16);
        sg.setColor(Color.WHITE);
        sg.fill(new Ellipse2D.Double(-rc, -rc, rc * 2, rc * 2));
        sg.dispose();
        return Sprites.crop(img, pad, pad);
    }

    // ---- sprite atlas ---------------------------------------------------------------------
    // key = kind(3b) | sizeQ(9b, size*10) | rot/alpha bucket(5b) | half-pixel phase x,y (2b)
    private final Sprites sprites = new Sprites();
    private double spTx, spTy, spS;              // device transform of the current sprite pass
    private static final int ROT_BUCKETS = 12;   // per 60 degrees (flakes and crystals are 6-fold symmetric)
    private static final double SIXTH = Math.PI / 3;

    private static int sizeQ(double s) { return Math.min(511, (int) Math.round(s * 10)); }

    private static int rotQ(double rot) {
        double r = rot % SIXTH;
        if (r < 0) r += SIXTH;
        return (int) Math.round(r / SIXTH * ROT_BUCKETS) % ROT_BUCKETS;
    }

    private double spQ = 1;   // scale the sprites are rasterised at (a quarter-octave bucket of the world scale)

    /** Start a sprite pass in world coordinates. */
    private void spriteBegin(Graphics2D g) {
        spTx = v.wtx;
        spTy = v.wty;
        spS = v.ws;
        double q = Math.max(0.5 * v.dev, TileLayer.bucket(v.ws));
        if (spQ != q || spriteSpace != 0) { sprites.clear(); spQ = q; spriteSpace = 0; }
        else if (sprites.size() > 60_000) sprites.clear();
        g.setTransform(IDENTITY);
    }

    private int spriteSpace = -1;

    /** Start a sprite pass in screen (canvas) coordinates. */
    private void spriteBeginScreen(Graphics2D g) {
        spTx = v.devX;
        spTy = v.devY;
        spS = v.dev;
        // stars share the atlas: only valid when the world sprites are at the same raster scale
        double q = Math.max(0.5 * v.dev, TileLayer.bucket(v.ws));
        if (spQ != q || spriteSpace != 0) { sprites.clear(); spQ = q; spriteSpace = 0; }
        starScale = v.dev / spQ;
        g.setTransform(IDENTITY);
    }

    private double starScale = 1;

    /** Blit the sprite for (kind, sizeQ, bucket) anchored at logical (x, y), snapped to half device pixels. */
    private void blit(Graphics2D g, int kind, int sq, int bucket, double x, double y) {
        double dx = spTx + spS * x, dy = spTy + spS * y;
        int ix = (int) Math.floor(dx), iy = (int) Math.floor(dy);
        double fx = dx - ix, fy = dy - iy;
        int phx = 0, phy = 0;
        if (fx >= 0.75) ix++; else if (fx >= 0.25) phx = 1;
        if (fy >= 0.75) iy++; else if (fy >= 0.25) phy = 1;
        int key = ((((kind << 9) | sq) << 5 | bucket) << 2) | (phx << 1) | phy;
        Sprites.Sprite sp = sprites.get(key);
        if (sp == null) {
            sp = makeSprite(kind, sq / 10.0, bucket, phx * 0.5, phy * 0.5);
            sprites.put(key, sp);
        }
        if (sp.img == null) return;
        if (occlude && hiddenByCloud(ix + sp.ox, iy + sp.oy, ix + sp.ox + sp.w - 1, iy + sp.oy + sp.h - 1)) return;
        if (directOn) direct.over(sp.px, sp.w, sp.h, ix + sp.ox, iy + sp.oy);
        else g.drawImage(sp.img, ix + sp.ox, iy + sp.oy, null);
    }

    private Sprites.Sprite makeSprite(int kind, double s, int bucket, double phx, double phy) {
        double ext = 4 + s * 4.5;                      // generous logical half-extent (twinkle cross is ~3.8 s)
        int pad = (int) Math.ceil(ext * spQ) + 2;
        int size = pad * 2 + 2;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D sg = img.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        sg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        sg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        sg.translate(pad + phx, pad + phy);
        sg.scale(spQ, spQ);
        double rot = bucket * SIXTH / ROT_BUCKETS;
        switch (kind) {
            case 0 -> { sg.setColor(new Color(255, 255, 255, 90)); sg.fill(new Rectangle2D.Double(-0.7, -0.7, 1.4, 1.4)); }
            case 1 -> { // star: bucket = alpha / 255 * 31
                sg.setColor(new Color(255, 255, 240, clampi((int) Math.round(bucket * 255 / 31.0))));
                sg.fill(new Ellipse2D.Double(-s / 2, -s / 2, s, s));
            }
            case 2 -> drawSnowflake(sg, 0, 0, s, rot);
            case 3 -> drawIceBody(sg, 0, 0, s, rot);
            case 4 -> drawGraupel(sg, 0, 0, s);
            case 5 -> drawSplash(sg, 0, 0, s);
            case 6 -> drawSleet(sg, 0, 0, s);
            case 7 -> drawIceTwinkle(sg, 0, 0, s, bucket / 31.0);
            case 8 -> drawDebrisBit(sg, s, bucket * Math.PI / ROT_BUCKETS);
            default -> { }
        }
        sg.dispose();
        return Sprites.crop(img, pad, pad);
    }

    private static final Color SNOW = new Color(250, 252, 255, 235);

    private void drawSnowflake(Graphics2D g, double x, double y, double s, double rot) {
        if (s < 2.3) {
            g.setColor(SNOW);
            g.fill(new Ellipse2D.Double(x - s * 0.6, y - s * 0.6, s * 1.2, s * 1.2));
            return;
        }
        GeneralPath p = new GeneralPath();
        for (int k = 0; k < 6; k++) {
            double a = rot + k * Math.PI / 3, ca = Math.cos(a), sa = Math.sin(a);
            p.moveTo((float) x, (float) y);
            p.lineTo((float) (x + ca * s), (float) (y + sa * s));
            double bx = x + ca * s * 0.55, by = y + sa * s * 0.55, bl = s * 0.36;
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                double ba = a + sgn * 0.8;
                p.moveTo((float) bx, (float) by);
                p.lineTo((float) (bx + Math.cos(ba) * bl), (float) (by + Math.sin(ba) * bl));
            }
        }
        g.setStroke(new BasicStroke(s > 4 ? 1.1f : 0.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(180, 200, 235, 70));
        g.fill(new Ellipse2D.Double(x - s * 0.35, y - s * 0.35, s * 0.7, s * 0.7));
        g.setColor(SNOW);
        g.draw(p);
    }

    private void drawIceCrystal(Graphics2D g, double x, double y, double s, double rot, int idx) {
        drawIceBody(g, x, y, s, rot);
        double tw = Math.sin(anim * 5.5 + idx * 2.37 + x * 0.05);
        if (tw > 0.55 && !sh.adv.noShine) drawIceTwinkle(g, x, y, s, (tw - 0.55) / 0.45);
    }

    private static void drawIceBody(Graphics2D g, double x, double y, double s, double rot) {
        Path2D.Double h = new Path2D.Double();
        for (int k = 0; k < 6; k++) {
            double a = rot + k * Math.PI / 3;
            double px = x + Math.cos(a) * s, py = y + Math.sin(a) * s * 0.9;
            if (k == 0) h.moveTo(px, py); else h.lineTo(px, py);
        }
        h.closePath();
        g.setColor(new Color(170, 235, 255, 60));
        g.fill(h);
        g.setStroke(new BasicStroke(0.9f));
        g.setColor(new Color(200, 245, 255, 210));
        g.draw(h);
    }

    private static void drawIceTwinkle(Graphics2D g, double x, double y, double s, double a) {
        {
            double L = s * (1.6 + 2.2 * a);
            g.setColor(new Color(255, 255, 255, (int) (230 * a)));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(x - L, y, x + L, y));
            g.draw(new Line2D.Double(x, y - L, x, y + L));
            g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float) (L * 0.8), new float[]{0f, 1f},
                    new Color[]{new Color(220, 250, 255, (int) (160 * a)), new Color(220, 250, 255, 0)}));
            g.fill(new Ellipse2D.Double(x - L * 0.8, y - L * 0.8, L * 1.6, L * 1.6));
        }
    }

    private void drawGraupel(Graphics2D g, double x, double y, double s) {
        double r = s * 0.75;
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x - r * 0.3, y - r * 0.3), (float) (r * 1.3), new float[]{0f, 1f},
                new Color[]{new Color(255, 255, 255, 250), new Color(176, 188, 206, 250)}));
        g.fill(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
        g.setColor(new Color(110, 120, 140, 160));
        g.setStroke(new BasicStroke(0.7f));
        g.draw(new Ellipse2D.Double(x - r, y - r, r * 2, r * 2));
    }

    private void drawSplash(Graphics2D g, double x, double y, double s) {
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(210, 230, 255, 170));
        g.draw(new Line2D.Double(x, y, x - s * 1.4, y - s * 1.8));
        g.draw(new Line2D.Double(x, y, x + s * 1.4, y - s * 1.8));
        g.draw(new Line2D.Double(x, y, x, y - s * 2.2));
        g.setColor(new Color(210, 230, 255, 90));
        g.draw(new Ellipse2D.Double(x - s * 2, y - s * 0.4, s * 4, s * 0.8));
    }

    private void drawSleet(Graphics2D g, double x, double y, double s) {
        double r = Math.min(3, s * 0.5);
        g.setPaint(new RadialGradientPaint(new Point2D.Double(x, y - r * 0.3), (float) (r * 1.2), new float[]{0f, 0.6f, 1f},
                new Color[]{new Color(255, 255, 255, 240), new Color(190, 220, 255, 220), new Color(120, 170, 240, 160)}));
        g.fill(new Ellipse2D.Double(x - r, y - r * 1.1, r * 2, r * 2.2));
    }

    // ------------------------------------------------------------------------------ lightning / arrows

    private final Lightning.Band lightBand = new Lightning.Band();

    /**
     * The light of every bolt within reach of the view ({@link Lightning}: local, falling off with world distance, shadowed by
     * buildings and terrain), then the bolts themselves with their branches ({@link Lightning.Tree}). The part of a bolt inside
     * its cloud is not stroked: the clouds are already painted, and the light grid makes that cloud glow along the hidden
     * channel. A bolt far from the view draws nothing here.
     */
    private void drawLightning(Graphics2D g, Model.Frame f) {
        Lightning.Frame lf = sh.bolts;
        if (lf == null) { lightBand.idle(); return; }
        if (lf.lights.length == 0) lightBand.idle();
        else if (lightBand.compute(lf, v, bandDy0, bandDy1)) {
            if (directOn) lightBand.apply(direct, lf);     // plain loops over our own pixels
            else {
                g.setTransform(IDENTITY);
                lightBand.paint(g, lf);
                g.setTransform(wTx);
            }
        }
        float iz = Lightning.strokeScale(v);
        double pad = 14 * iz;
        for (int n = 0; n < lf.strokes.length; n++) {
            double[] pts = lf.strokes[n];
            double a = lf.strokeA[n], x0 = 1e18, x1 = -1e18, y0 = 1e18, y1 = -1e18;
            Path2D.Double p = new Path2D.Double();
            p.moveTo(pts[0], pts[1]);
            for (int k = 0; k + 1 < pts.length; k += 2) {
                if (k > 0) p.lineTo(pts[k], pts[k + 1]);
                x0 = Math.min(x0, pts[k]); x1 = Math.max(x1, pts[k]); y0 = Math.min(y0, pts[k + 1]); y1 = Math.max(y1, pts[k + 1]);
            }
            Lightning.Tree tree = lf.trees[n];
            if (lf.safe) {
                // epilepsy-safe: a dim line with a soft halo, no white core; a is the eased level (at most Lightning.Safe.PEAK).
                // The channel and its branches are one path: where they lie over each other they must not add up to more than that.
                double[] bb = tree.wholeBox;
                if (!v.sees(bb[0] - pad, bb[1] - pad, bb[2] + pad, bb[3] + pad) || bb[3] + pad < bandWy0 || bb[1] - pad > bandWy1) continue;
                g.setStroke(new BasicStroke(9f * iz, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(150, 140, 225, (int) (70 * a)));
                g.draw(tree.whole);
                g.setStroke(new BasicStroke(2.6f * iz, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(176, 170, 232, (int) (255 * a)));
                g.draw(tree.whole);
                continue;
            }
            // its branches first: two fills for the whole tree (Lightning.Tree.shape), tapering to the tips
            double[] tb = tree.box;
            if (tree.coreShape != null && v.sees(tb[0] - pad, tb[1] - pad, tb[2] + pad, tb[3] + pad) && tb[3] + pad >= bandWy0 && tb[1] - pad <= bandWy1) {
                g.setColor(new Color(190, 180, 255, (int) (95 * a)));
                g.fill(tree.glowShape);
                g.setColor(new Color(255, 255, 255, (int) (245 * a)));
                g.fill(tree.coreShape);
            }
            if (!v.sees(x0 - pad, y0 - pad, x1 + pad, y1 + pad) || y1 + pad < bandWy0 || y0 - pad > bandWy1) continue;
            g.setStroke(new BasicStroke(12f * iz, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(150, 130, 255, (int) (40 * a)));
            g.draw(p);
            g.setStroke(new BasicStroke(5f * iz, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(200, 190, 255, (int) (120 * a)));
            g.draw(p);
            g.setStroke(new BasicStroke(1.8f * iz, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(255, 255, 255, (int) (255 * a)));
            g.draw(p);
        }
    }

    private void drawArrow(Graphics2D g, Model.Arrow a) {
        double dx = a.x2 - a.x1, dy = a.y2 - a.y1, len = Math.hypot(dx, dy);
        if (len < 2) return;
        Color c = arrowColor(a.kind);
        double ang = Math.atan2(dy, dx), hs = Math.min(13, 6 + len * 0.15);
        double sx = a.x2 - Math.cos(ang) * hs * 0.7, sy = a.y2 - Math.sin(ang) * hs * 0.7;
        Path2D.Double head = new Path2D.Double();
        head.moveTo(a.x2, a.y2);
        head.lineTo(a.x2 - hs * Math.cos(ang - 0.42), a.y2 - hs * Math.sin(ang - 0.42));
        head.lineTo(a.x2 - hs * Math.cos(ang + 0.42), a.y2 - hs * Math.sin(ang + 0.42));
        head.closePath();
        boolean dashed = a.kind.equals("growth");
        // pulse along the shaft
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0, 0, 0, 90));
        g.draw(new Line2D.Double(a.x1, a.y1, sx, sy));
        g.setStroke(dashed ? new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{6, 5}, dph(-anim * 14, 11))
                : new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(c);
        g.draw(new Line2D.Double(a.x1, a.y1, sx, sy));
        g.setColor(new Color(0, 0, 0, 90));
        g.setStroke(new BasicStroke(2f));
        g.draw(head);
        g.setColor(c);
        g.fill(head);
        // travelling bead
        double ph = (anim * 0.8 + (a.x1 * 0.01)) % 1;
        g.setColor(alpha(Color.WHITE, 200));
        g.fill(new Ellipse2D.Double(a.x1 + dx * ph * 0.85 - 2, a.y1 + dy * ph * 0.85 - 2, 4, 4));
        if (showLabels && a.label != null && !a.label.isEmpty()) {
            g.setFont(F_SMALL);
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(a.label) + 10;
            double mx = (a.x1 + a.x2) / 2, my = (a.y1 + a.y2) / 2;
            double nx = -Math.sin(ang), ny = Math.cos(ang);
            if (nx < 0) { nx = -nx; ny = -ny; }
            double lx = mx + nx * 12, ly = my + ny * 12 - 8;
            if (Math.abs(dx) > Math.abs(dy)) { lx = mx - w / 2.0; ly = Math.min(a.y1, a.y2) - 22; }
            lx = Model.clamp(lx, 2, vRight - w - 2);
            ly = Model.clamp(ly, 2, vBottom - 20);
            // a label that would lie on an earlier arrow's label steps down (then up) until it is clear
            if (arrowPills != null) {
                double ly0 = ly;
                for (int k = 0; k < 7 && hitsArrowPill(lx, ly, w); k++) ly = Model.clamp(ly0 + (k < 4 ? (k + 1) * 19 : -(k - 3) * 19), 2, vBottom - 20);
            }
            pill(g, lx, ly, w, 16, new Color(10, 14, 22, 165));
            g.setColor(c);
            g.drawString(a.label, (float) lx + 5, (float) ly + 12);
            if (arrowPills != null) arrowPills.add(new Rectangle2D.Double(lx - 2, ly - 2, w + 4, 20));
        }
    }

    private boolean hitsArrowPill(double lx, double ly, int w) {
        for (Rectangle2D o : arrowPills) if (o.intersects(lx - 2, ly - 2, w + 4, 20)) return true;
        return false;
    }

    // ------------------------------------------------------------------------------ left panel: HUD + legend

    /** HUD stats and legend as one floating panel at the top-left of the viewport. Returns its bottom y. */
    private double drawLeftPanel(Graphics2D g, Model.Frame f) {
        int x = (int) Math.round(v.vx + 12 - (1 - ease(leftShown())) * 290), y = (int) v.vy + 12, w = 262;
        List<String[]> rows = new ArrayList<>();
        int hh = (int) (f.timeOfDay * 24), mm = (int) ((f.timeOfDay * 24 - hh) * 60);
        rows.add(new String[]{"Sim time", String.format("%.1f s   ×%s%s", f.t, trim(f.speed), f.paused ? "   ❚❚ PAUSED" : "")});
        // v2.3: the date joins the clock when the simulation keeps a calendar
        rows.add(new String[]{"Clock", String.format("%02d:%02d  %s", hh, mm, day > 0.6 ? "day" : day > 0.15 ? "twilight" : "night")
                + (f.hasCalendar() ? String.format("  ·  %d %s, %s", Model.dayOfMonth(f.month), Model.MONTHS_SHORT[Model.monthIndex(f.month)], f.season) : "")});
        rows.add(new String[]{"Surface", String.format("%.1f °C   0 °C at %.0f px AGL", f.surfaceTemp, Math.max(0, groundY - f.freezingY))});
        rows.add(new String[]{"Wind", String.format("mean %.1f m/s", f.stat("wind_mean"))});
        rows.add(new String[]{"Clouds", String.format("%d alive · %d born · %d gone", (int) f.stat("clouds_alive"), (int) f.stat("clouds_born"), (int) f.stat("clouds_dead"))});
        rows.add(new String[]{"Precip", String.format("rain %.1f mm · snow %.1f mm", f.stat("rain_mm"), f.stat("snow_mm"))});
        rows.add(new String[]{"Particles", String.format("%d  (ice crystals %d)", (int) f.stat("particles"), (int) f.stat("ice_crystals"))});
        if (f.stats.containsKey("tornadoes_total") || f.stats.containsKey("cars")) {
            int tn = (int) f.stat("tornadoes"), tt = (int) f.stat("tornadoes_total");
            rows.add(new String[]{"Storms", (tn > 0 ? tn + " tornado" + (tn > 1 ? "es" : "") + " now" : "no tornado") + " · " + tt + " so far · " + (int) f.stat("cars") + " cars"});
        }
        if (hello != null) {
            rows.add(new String[]{"World", String.format("%d×%d · %s · seed %d", hello.w, hello.h, hello.terrainKind, hello.seed)});
            String where = "open country";
            for (Model.Settlement s : hello.settlements) if (v.cx >= s.x0 && v.cx <= s.x1) where = s.name.isEmpty() ? s.kind : s.name + " (" + s.kind + ")";
            rows.add(new String[]{"View", String.format("×%.2f · x %.0f–%.0f · %s", v.zoom, Math.max(0, v.wx0), Math.min(worldW, v.wx1), where)});
        }
        int h = 30 + rows.size() * 16 + 6;
        // a region preset adds its card (name, transect, climate) between the stats and the legend
        Preset region = hello != null ? hello.preset : null;
        int cardH = region != null ? PresetOverlay.cardHeight(region, w) + 6 : 0;
        // v2.3: a stream with the climate keys also knows the ninth cloud type (one more row in the types block)
        int nTypes = f.atmosphere != null || (hello != null && hello.climate != null) ? Model.CLOUD_TYPES.length : 8, typeRows = (nTypes + 1) / 2;
        boolean legend = showLegend && v.vh > y + h + cardH + 330 + (typeRows - 4) * 15;
        // the minimap sits at the bottom centre: in a narrow window it reaches under the left panel, and the legend
        // then has no room (2026-10-09: at the minimum window size the minimap covered the cloud-types block)
        Rectangle2D.Double mini = legend && miniShown() > 0.004 ? miniBounds() : null;
        if (mini != null && mini.x < x + w + 6 && y + h + 6 + 200 + 6 + 24 + typeRows * 15 + 6 + 2 * 14 + 2 + cardH > mini.y - 26) legend = false;
        leftPanelBottom = (legend ? y + h + 6 + 200 + 6 + 24 + typeRows * 15 + 6 + 2 * 14 + 2 : y + h) + cardH;
        if (y > bandSy1 || leftPanelBottom < bandSy0) return leftPanelBottom;
        // the panel is text-heavy: paint it into an image and refresh that a few times a second
        long key = (legend ? 7 : 3) + (region != null ? System.identityHashCode(region) * 131L : 0) + nTypes * 1009L;
        for (String[] r : rows) key = key * 31 + r[1].hashCode();
        int iw = (int) Math.ceil(w * v.dev) + 2, ih = (int) Math.ceil((leftPanelBottom - y) * v.dev) + 2;
        if (leftImg == null || leftImg.getWidth() != iw || leftImg.getHeight() != ih || leftDev != v.dev
                || (key != leftKey && (clock - leftBuilt > 180_000_000L || clock < leftBuilt))) {
            if (leftImg == null || leftImg.getWidth() != iw || leftImg.getHeight() != ih) leftImg = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D lg = leftImg.createGraphics();
            lg.setComposite(AlphaComposite.Clear);
            lg.fillRect(0, 0, iw, ih);
            lg.setComposite(AlphaComposite.SrcOver);
            lg.setRenderingHints(g.getRenderingHints());
            lg.scale(v.dev, v.dev);
            lg.translate(-x, -y);
            double save = anim;
            anim = 0.9;
            paintLeft(lg, rows, x, y, w, h, legend, region, cardH, nTypes);
            anim = save;
            lg.dispose();
            leftKey = key; leftBuilt = clock; leftDev = v.dev;
        }
        g.setTransform(IDENTITY);
        g.drawImage(leftImg, (int) Math.floor(v.devX + v.dev * x + 0.5), (int) Math.floor(v.devY + v.dev * y + 0.5), null);
        g.setTransform(scrTx);
        return leftPanelBottom;
    }

    private BufferedImage leftImg;
    private long leftKey, leftBuilt;
    private double leftDev = -1;

    private void paintLeft(Graphics2D g, List<String[]> rows, int x, int y, int w, int h, boolean legend, Preset region, int cardH, int nTypes) {
        pill(g, x, y, w, h, new Color(8, 12, 22, 165));
        g.setFont(F_TITLE);
        g.setColor(new Color(255, 255, 255));
        g.drawString("PRECIPITATION", x + 10, y + 19);
        g.setFont(F_SMALL);
        g.setColor(new Color(170, 185, 205));
        g.drawString("H = help", x + w - 58, y + 18);
        int ry = y + 38;
        for (String[] r : rows) {
            g.setFont(F_SMALL);
            g.setColor(new Color(150, 170, 195));
            g.drawString(r[0], x + 10, ry);
            g.setColor(new Color(236, 242, 250));
            g.drawString(fit(g, r[1], w - 86), x + 78, ry);
            ry += 16;
        }
        double bottom = y + h;
        if (region != null) {
            PresetOverlay.paintCard(g, region, x, (int) bottom + 6, w);
            bottom += cardH;
        }
        if (legend) {
            drawLegend(g, (int) bottom + 6);
            bottom += 6 + 200;
            // cloud types
            int tr = (nTypes + 1) / 2;
            int ty = (int) bottom + 6, th = 24 + tr * 15 + 6 + 2 * 14 + 2;
            pill(g, x, ty, w, th, new Color(8, 12, 22, 150));
            g.setFont(F_BOLD);
            g.setColor(Color.WHITE);
            g.drawString("CLOUD TYPES", x + 10, ty + 17);
            for (int k = 0; k < nTypes; k++) {
                int cx = x + 10 + (k / tr) * 128, cy = ty + 33 + (k % tr) * 15;
                g.setFont(F_BOLD);
                g.setColor(new Color(170, 214, 255));
                g.drawString(Model.CLOUD_PREFIX[k], cx, cy);
                g.setFont(F_SMALL);
                g.setColor(new Color(225, 232, 244));
                g.drawString(Model.CLOUD_TYPES[k], cx + 22, cy);
            }
            g.setFont(F_SMALL);
            g.setColor(new Color(170, 185, 205));
            g.drawString("fair weather = no rain; some develop and rain", x + 10, ty + 33 + tr * 15 + 1);
            g.drawString(nTypes > 8 ? "Cu may tower into Cb; a supercell (Sp) rotates" : "a cumulus (Cu) may tower into a storm (Cb)", x + 10, ty + 33 + tr * 15 + 15);
        }
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(Math.round(d * 100) / 100.0);
    }

    private void drawLegend(Graphics2D g, int y) {
        int x = (int) v.vx + 12, w = 262, h = 200;
        pill(g, x, y, w, h, new Color(8, 12, 22, 150));
        g.setFont(F_BOLD);
        g.setColor(Color.WHITE);
        g.drawString("LEGEND", x + 10, y + 17);
        g.setFont(F_SMALL);
        String[] pn = {"cloud droplet", "raindrop", "snowflake", "ice crystal", "graupel / hail", "splash", "sleet (melting)"};
        double saveAnim = anim;
        for (int k = 0; k < pn.length; k++) {
            double ix = x + 18, iy = y + 34 + k * 22;
            switch (k) {
                case 0 -> { g.setColor(new Color(255, 255, 255, 200)); g.fill(new Rectangle2D.Double(ix - 1, iy - 1, 2, 2)); }
                case 1 -> { g.setStroke(new BasicStroke(1.4f)); g.setColor(new Color(190, 215, 250)); g.draw(new Line2D.Double(ix - 2, iy - 7, ix + 1, iy + 6)); }
                case 2 -> drawSnowflake(g, ix, iy, 6.5, anim * 0.6);
                case 3 -> { anim = 0.33; drawIceCrystal(g, ix, iy, 5, 0.3, 0); anim = saveAnim; }
                case 4 -> drawGraupel(g, ix, iy, 3.6);
                case 5 -> drawSplash(g, ix, iy + 4, 2.5);
                case 6 -> drawSleet(g, ix, iy, 3.5);
                default -> { }
            }
            g.setFont(F_SMALL);
            g.setColor(new Color(225, 232, 244));
            g.drawString(pn[k], (float) ix + 14, (float) iy + 4);
        }
        String[] ak = {"updraft", "downdraft", "wind", "fall", "growth"};
        for (int k = 0; k < ak.length; k++) {
            double ix = x + 140, iy = y + 34 + k * 22;
            Model.Arrow a = new Model.Arrow();
            a.x1 = ix - 6; a.y1 = iy; a.x2 = ix + 20; a.y2 = iy; a.kind = ak[k]; a.label = "";
            drawArrow(g, a);
            g.setFont(F_SMALL);
            g.setColor(new Color(225, 232, 244));
            g.drawString(ak[k], (float) ix + 28, (float) iy + 4);
        }
        // wind field swatch
        double iy = y + 34 + 5 * 22;
        for (int k = 0; k < 5; k++) {
            g.setColor(speedColor(k * 4));
            g.fill(new Rectangle2D.Double(x + 134 + k * 9, iy - 4, 9, 7));
        }
        g.setColor(new Color(225, 232, 244));
        g.drawString("wind 0→16 m/s", x + 184, (float) iy + 4);
    }

    // ------------------------------------------------------------------------------ v2.3: the Climate panel

    static final int CLIMATE_W = 262;
    private BufferedImage climImg;
    private long climKey, climBuilt;
    private double climDev = -1;
    private static final Color WARM = new Color(255, 170, 110), COOL = new Color(130, 190, 255);

    private static String signed(double v) { return (v >= 0 ? "+" : "−") + String.format("%.1f", Math.abs(v)); }

    private static String dayPart(double tod) {
        double h = tod * 24;
        return h < 5 ? "night" : h < 8 ? "dawn" : h < 12 ? "morning" : h < 17 ? "afternoon" : h < 20 ? "evening" : "night";
    }

    /**
     * Date and clock, the temperature and what it is made of, the gases, the spawn rate, the chance of each
     * cloud type and the click-to-spawn type: a floating panel at the top right of the world view. It only
     * exists for a simulation that sends the v2.3 climate keys. Sections are dropped from the bottom when the
     * view is too short (or the life card needs the room).
     */
    private void drawClimate(Graphics2D g, Model.Frame f) {
        if (!showClimate || decorative || (f.temp == null && f.atmosphere == null && !f.hasCalendar()) || v.vw < 620 || v.vh < 250) {
            climateRect = null;
            climateRows = null;
            return;
        }
        final int w = CLIMATE_W, x = (int) Math.round(v.vx + v.vw - w - 12), y = (int) Math.round(v.vy + (hudReserveW > 0 ? 46 : 12));
        // how far down may it reach? not into the life card, the minimap or the bottom edge
        Rectangle2D mm = minimapRect;
        double limit = (mm != null ? mm.getY() - 34 : v.vy + v.vh - 10);
        if (selLife != null) limit = Math.min(limit, Math.max(v.vy + 40, v.vy + v.vh - 262 - (mm != null ? mm.getHeight() + 30 : 14)) - 8);
        if (drainPick != null) limit -= DrainCard.H + 8;          // v2.4: the drains card stands under this panel
        Model.Temp tp = f.temp;
        Model.Atmosphere at = f.atmosphere;
        // ---- sections (heights), added while they fit
        int hHead = 28 + (f.hasCalendar() ? 16 : 0) + 16;
        int hTemp = tp != null ? 96 : 26;
        int hGas = at != null ? 5 * 15 + 8 : 0;
        int hSpawn = at != null && !Double.isNaN(at.spawnPerMin) ? 18 : 0;
        int nW = at == null ? 0 : at.typeWeights.size(), wRows = (nW + 2) / 3;
        int hWeights = nW > 0 ? 20 + wRows * 14 + 4 : 0;
        int hFoot = 24;
        int h = hHead + hTemp;
        boolean gas = hGas > 0 && y + h + hGas + 8 <= limit;
        if (gas) h += hGas;
        boolean spawn = gas && hSpawn > 0 && y + h + hSpawn + 8 <= limit;
        if (spawn) h += hSpawn;
        boolean foot = y + h + hFoot + 6 <= limit;
        boolean weights = foot && spawn && hWeights > 0 && y + h + hWeights + hFoot + 6 <= limit;
        if (weights) h += hWeights;
        if (foot) h += hFoot;
        h += 8;
        if (y + h > limit + 0.5 && !(tp == null && at == null)) {
            // not even the essentials fit
            if (y + hHead + 34 > limit) { climateRect = null; climateRows = null; return; }
            h = hHead + 34;
        }
        final boolean compact = h == hHead + 34;
        climateRect = new Rectangle2D.Double(x, y, w, h);
        // rows the HUD hangs its buttons on
        int yDate = f.hasCalendar() ? y + 40 : -1, yClock = y + 40 + (f.hasCalendar() ? 16 : 0);
        int yGas0 = y + hHead + hTemp + 12;
        int yFoot = y + h - 8 - hFoot + 16;
        climateRows = new double[]{x, w, yDate < 0 ? Double.NaN : yDate, yClock, gas ? yGas0 : Double.NaN, gas ? yGas0 + 15 : Double.NaN, gas ? yGas0 + 30 : Double.NaN,
                foot && !compact ? yFoot : Double.NaN};
        if (y > bandSy1 || y + h < bandSy0) return;

        // ---- text-heavy: painted into an image that is refreshed a few times a second
        int hh = (int) (f.timeOfDay * 24), mi = (int) ((f.timeOfDay * 24 - hh) * 60);
        long key = h * 31L + (gas ? 3 : 0) + (weights ? 5 : 0) + (foot ? 7 : 0) + hh * 61 + mi + spawnTypeLabel.hashCode() * 131L;
        key = key * 31 + Math.round(f.surfaceTemp * 10);
        if (f.hasCalendar()) key = key * 31 + Model.monthIndex(f.month) * 40 + Model.dayOfMonth(f.month) + f.day * 1009L + f.season.hashCode();
        if (tp != null) key = key * 31 + Math.round(tp.seasonC * 10) * 7 + Math.round(tp.diurnalC * 10) * 131 + Math.round(tp.gasC * 10) * 977 + Math.round(tp.userC * 10) * 7919 + Math.round(tp.baseC * 10);
        if (at != null) {
            key = key * 31 + Math.round(at.humidity * 100) + Math.round(at.co2) * 101 + Math.round(at.aerosol * 100) * 10007 + Math.round(at.instability * 100) * 7
                    + Math.round(at.shear * 100) * 131 + Math.round(at.spawnPerMin * 10) * 977 + Math.round(at.cloudTarget) * 13;
            for (double wv : at.typeWeights.values()) key = key * 31 + Math.round(wv * 100);
        }
        int iw = (int) Math.ceil(w * v.dev) + 2, ih = (int) Math.ceil(h * v.dev) + 2;
        if (climImg == null || climImg.getWidth() != iw || climImg.getHeight() != ih || climDev != v.dev
                || (key != climKey && (clock - climBuilt > 180_000_000L || clock < climBuilt))) {
            if (climImg == null || climImg.getWidth() != iw || climImg.getHeight() != ih) climImg = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D cg = climImg.createGraphics();
            cg.setComposite(AlphaComposite.Clear);
            cg.fillRect(0, 0, iw, ih);
            cg.setComposite(AlphaComposite.SrcOver);
            cg.setRenderingHints(g.getRenderingHints());
            cg.scale(v.dev, v.dev);
            cg.translate(-x, -y);
            paintClimate(cg, f, x, y, w, h, hHead, hTemp, gas, spawn, weights, foot && !compact, compact, yFoot);
            cg.dispose();
            climKey = key; climBuilt = clock; climDev = v.dev;
        }
        g.setTransform(IDENTITY);
        g.drawImage(climImg, (int) Math.floor(v.devX + v.dev * x + 0.5), (int) Math.floor(v.devY + v.dev * y + 0.5), null);
        g.setTransform(scrTx);
    }

    /** v2.4: the drains card, under the Climate panel (or in its place when that is hidden). Not drawn when there is no room for it. */
    private void drawDrainCard(Graphics2D g) {
        Model.Settlement s = drainPick;
        Model.Drain d = s == null ? null : sh.drains.get(s.id);
        if (d == null) { drainRect = null; drainCardId = -1; return; }
        Rectangle2D clim = climateRect, mm = minimapRect;
        final int x = (int) Math.round(v.vx + v.vw - DrainCard.W - 12);
        final int y = clim != null ? (int) Math.round(clim.getMaxY() + 8) : (int) Math.round(v.vy + (hudReserveW > 0 ? 46 : 12));
        double limit = mm != null ? mm.getY() - 34 : v.vy + v.vh - 10;
        if (selLife != null) limit = Math.min(limit, Math.max(v.vy + 40, v.vy + v.vh - 262 - (mm != null ? mm.getHeight() + 30 : 14)) - 8);
        if (y + DrainCard.H > limit + 0.5) { drainRect = null; drainCardId = -1; return; }
        drainRect = DrainCard.rect(x, y);
        drainCardId = s.id;
        if (y > bandSy1 || y + DrainCard.H < bandSy0) return;
        drainCard.draw(g, v, x, y, s, d, DrainPainter.floodOf(sh.flood, s.id), sh.drainMode, scrTx);
    }

    private void paintClimate(Graphics2D g, Model.Frame f, int x, int y, int w, int h, int hHead, int hTemp, boolean gas, boolean spawn, boolean weights,
                              boolean foot, boolean compact, int yFoot) {
        Model.Temp tp = f.temp;
        Model.Atmosphere at = f.atmosphere;
        pill(g, x, y, w, h, new Color(8, 12, 22, 178));
        g.setFont(F_TITLE);
        g.setColor(Color.WHITE);
        g.drawString("CLIMATE", x + 10, y + 19);
        // the season as a chip in its own colour
        if (!f.season.isEmpty()) {
            Color sc = switch (f.season) {
                case "winter" -> new Color(150, 200, 255); case "spring" -> new Color(140, 220, 150);
                case "summer" -> new Color(255, 214, 110); default -> new Color(240, 160, 96);
            };
            g.setFont(F_BOLD);
            int sw = g.getFontMetrics().stringWidth(f.season) + 14;
            pill(g, x + w - sw - 10, y + 6, sw, 18, alpha(sc, 46));
            g.setColor(sc);
            g.drawString(f.season, x + w - sw - 3, y + 19);
        }
        int ry = y + 40;
        g.setFont(F_SMALL);
        if (f.hasCalendar()) {
            g.setColor(new Color(150, 170, 195));
            g.drawString("Date", x + 10, ry);
            g.setColor(new Color(236, 242, 250));
            g.drawString(String.format("%d %s  ·  day %d", Model.dayOfMonth(f.month), Model.MONTHS[Model.monthIndex(f.month)], Math.max(0, f.day) + 1), x + 62, ry);
            ry += 16;
        }
        int hh = (int) (f.timeOfDay * 24), mi = (int) ((f.timeOfDay * 24 - hh) * 60);
        g.setColor(new Color(150, 170, 195));
        g.drawString("Clock", x + 10, ry);
        g.setColor(new Color(236, 242, 250));
        g.drawString(String.format("%02d:%02d  %s", hh, mi, dayPart(f.timeOfDay)), x + 62, ry);

        // ---- temperature now, today's range, and what it is made of
        int ty = y + hHead;
        g.setFont(F_BIG);
        g.setColor(Color.WHITE);
        String now = String.format("%.1f °C", f.surfaceTemp);
        g.drawString(now, x + 10, ty + 24);
        int nowW = g.getFontMetrics().stringWidth(now);
        g.setFont(F_SMALL);
        g.setColor(new Color(150, 170, 195));
        g.drawString("at the surface", x + 16 + nowW, ty + 24);
        if (compact || tp == null) return;
        boolean range = !Double.isNaN(tp.dayMinC) && !Double.isNaN(tp.dayMaxC) && tp.dayMaxC > tp.dayMinC;
        if (range) {
            // today's low to high as a bar, the marker is now
            double bx = x + 10, bw = w - 20, by = ty + 34;
            g.setPaint(new GradientPaint((float) bx, 0, alpha(COOL, 210), (float) (bx + bw), 0, alpha(WARM, 220)));
            g.fill(new RoundRectangle2D.Double(bx, by, bw, 5, 5, 5));
            double fr = Model.clamp((f.surfaceTemp - tp.dayMinC) / (tp.dayMaxC - tp.dayMinC), 0, 1);
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(bx + bw * fr - 4.5, by - 2, 9, 9));
            g.setColor(new Color(10, 14, 24));
            g.fill(new Ellipse2D.Double(bx + bw * fr - 2, by + 0.5, 4, 4));
            g.setFont(F_SMALL);
            g.setColor(COOL);
            g.drawString(String.format("night low %.1f", tp.dayMinC), (float) bx, (float) by + 18);
            String hi = String.format("day high %.1f °C", tp.dayMaxC);
            g.setColor(WARM);
            g.drawString(hi, (float) (bx + bw - g.getFontMetrics().stringWidth(hi)), (float) by + 18);
        }
        // the breakdown: five columns, label over value
        String[] lab = {"base", "season", "day", "gas", "you"};
        double[] val = {tp.baseC, tp.seasonC, tp.diurnalC, tp.gasC, tp.userC};
        double cw = (w - 20) / 5.0;
        int by2 = ty + 66;
        g.setColor(new Color(255, 255, 255, 14));
        g.fill(new RoundRectangle2D.Double(x + 8, by2 - 1, w - 16, 30, 8, 8));
        for (int k = 0; k < 5; k++) {
            double cx = x + 10 + k * cw;
            g.setFont(F_SMALL);
            g.setColor(new Color(150, 170, 195));
            g.drawString(lab[k], (float) (cx + (cw - g.getFontMetrics().stringWidth(lab[k])) / 2), by2 + 11);
            boolean zero = Math.abs(val[k]) < 0.05;
            String vs = String.format("%.1f", k == 0 ? val[k] : Math.abs(val[k]));
            Color vc = k == 0 ? new Color(236, 242, 250) : zero ? new Color(150, 160, 178) : val[k] > 0 ? WARM : COOL;
            g.setFont(F_BOLD);
            g.setColor(vc);
            g.drawString(vs, (float) (cx + (cw - g.getFontMetrics().stringWidth(vs)) / 2 + (k > 0 ? 3 : 0)), by2 + 25);
            if (k > 0) {
                g.setColor(zero ? new Color(110, 126, 150) : vc);
                g.drawString(val[k] < -0.049 ? "−" : "+", (float) (cx - 2), by2 + 25);
            }
        }
        if (!gas || at == null) return;

        // ---- gases and what follows from them
        int gy = y + hHead + hTemp + 12;
        String[] gl = {"Humidity", "CO2", "Aerosol", "Instability", "Shear"};
        double[] gf = {at.humidity, Math.log(Math.max(180, at.co2) / 180.0) / Math.log(2000 / 180.0), at.aerosol, at.instability, at.shear};
        String[] gv = {String.format("%.0f %%", at.humidity * 100), String.format("%.0f ppm", at.co2), String.format("%.2f", at.aerosol),
                String.format("%.2f", at.instability), String.format("%.2f", at.shear)};
        Color[] gc = {new Color(110, 180, 255), new Color(240, 150, 110), new Color(214, 196, 150), new Color(255, 204, 96), new Color(170, 150, 240)};
        for (int k = 0; k < 5; k++) {
            int yy = gy + k * 15;
            g.setFont(F_SMALL);
            g.setColor(k < 3 ? new Color(190, 204, 224) : new Color(150, 170, 195));
            g.drawString(gl[k], x + 10, yy);
            double bx = x + 76, bw = 76;
            g.setColor(new Color(255, 255, 255, 24));
            g.fill(new RoundRectangle2D.Double(bx, yy - 8, bw, 7, 7, 7));
            g.setColor(gc[k]);
            g.fill(new RoundRectangle2D.Double(bx, yy - 8, Math.max(4, bw * Model.clamp(gf[k], 0, 1)), 7, 7, 7));
            g.setColor(new Color(236, 242, 250));
            g.drawString(gv[k], (float) (bx + bw + 8), yy);
            if (k >= 3) {
                g.setColor(new Color(110, 126, 150));
                String d = "derived";
                g.drawString(d, x + w - 10 - g.getFontMetrics().stringWidth(d), yy);
            }
        }
        int sy = gy + 5 * 15 + 6;
        if (spawn) {
            g.setFont(F_SMALL);
            g.setColor(new Color(150, 170, 195));
            g.drawString("Spawning", x + 10, sy);
            g.setColor(new Color(236, 242, 250));
            g.drawString(String.format("%.1f clouds / min  ·  steering to %.0f", at.spawnPerMin, at.cloudTarget), x + 76, sy);
            sy += 18;
        }
        if (weights) {
            // the chance of each type for the next cloud, as small bars
            g.setFont(F_BOLD);
            g.setColor(new Color(170, 185, 205));
            g.drawString("NEXT CLOUD", x + 10, sy + 4);
            g.setFont(F_SMALL);
            g.setColor(new Color(110, 126, 150));
            String hint = "chance by type";
            g.drawString(hint, x + w - 10 - g.getFontMetrics().stringWidth(hint), sy + 4);
            double max = 0.01;
            for (double wv : at.typeWeights.values()) max = Math.max(max, wv);
            int k = 0;
            double cw3 = (w - 20) / 3.0;
            for (Map.Entry<String, Double> e : at.typeWeights.entrySet()) {
                double cx = x + 10 + (k % 3) * cw3;
                int yy = sy + 20 + (k / 3) * 14;
                int kind = Model.cloudKind(e.getKey());
                boolean known = Model.CLOUD_TYPES[kind].equals(e.getKey());
                g.setFont(F_BOLD);
                g.setColor(new Color(170, 214, 255));
                g.drawString(known ? Model.CLOUD_PREFIX[kind] : "?", (float) cx, yy);
                double bx = cx + 20, bw = cw3 - 20 - 30;
                g.setColor(new Color(255, 255, 255, 22));
                g.fill(new RoundRectangle2D.Double(bx, yy - 8, bw, 7, 7, 7));
                g.setColor(kind == Model.SUPERCELL ? TORNADO_RED : Model.heapKind(kind) ? new Color(255, 204, 96) : kind == Model.CIRRUS ? new Color(190, 226, 255) : new Color(150, 176, 214));
                g.fill(new RoundRectangle2D.Double(bx, yy - 8, Math.max(2, bw * e.getValue() / max), 7, 7, 7));
                g.setFont(F_SMALL);
                g.setColor(new Color(225, 232, 244));
                String pct = e.getValue() < 0.0095 ? "<1" : String.valueOf(Math.round(e.getValue() * 100));
                g.drawString(pct + "%", (float) (bx + bw + 4), yy);
                k++;
            }
        }
        if (foot) {
            g.setColor(new Color(255, 255, 255, 20));
            g.fill(new Rectangle2D.Double(x + 8, yFoot - 14, w - 16, 1));
            g.setFont(F_SMALL);
            g.setColor(new Color(150, 170, 195));
            g.drawString("Click sky", x + 10, yFoot);
            g.setFont(F_BOLD);
            g.setColor(spawnTypeLabel.equals("random") ? new Color(236, 242, 250) : new Color(255, 226, 140));
            g.drawString(spawnTypeLabel.equals("random") ? "random type" : spawnTypeLabel, x + 62, yFoot);
        }
    }

    // ------------------------------------------------------------------------------ minimap


    private Rectangle2D.Double miniBounds() {
        if (hello == null || v.vw < 420 || v.vh < 300) return null;
        double w = Math.min(620, v.vw - 320), h = Model.clamp(w * worldH / worldW, 44, 96);
        return new Rectangle2D.Double(v.vx + (v.vw - w) / 2, v.vy + v.vh - h - 12 + (1 - ease(miniShown())) * (h + 30), w, h);
    }

    /** Whole-world strip at the bottom of the viewport: terrain, settlements, clouds and the view rectangle. */
    private void drawMinimap(Graphics2D g, Model.Frame f) {
        Rectangle2D.Double rect = miniBounds();
        minimapRect = rect;
        if (rect == null || rect.y - 6 > bandSy1 || rect.y + rect.height + 6 < bandSy0) return;
        double x = rect.x, y = rect.y, w = rect.width, h = rect.height;
        double kx = w / worldW, ky = h / worldH;
        // static part (frame, sky, terrain, settlements): painted once per world / size / lighting step
        long bkey = worldVer * 1_000_003L + Double.doubleToLongBits(w) * 31 + Double.doubleToLongBits(h) + Double.doubleToLongBits(v.dev) * 17
                + skyTop.getRGB() * 131L + skyBot.getRGB() * 7L + Math.round(light * 30);
        int mw = (int) Math.ceil((w + 10) * v.dev) + 2, mh = (int) Math.ceil((h + 10) * v.dev) + 2;
        if (miniImg == null || miniBgKey != bkey || miniImg.getWidth() != mw || miniImg.getHeight() != mh) {
            miniImg = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D mg = miniImg.createGraphics();
            mg.setRenderingHints(g.getRenderingHints());
            mg.scale(v.dev, v.dev);
            mg.translate(-(x - 5), -(y - 5));
            paintMinimapStatic(mg, rect, kx, ky);
            mg.dispose();
            miniBgKey = bkey;
        }
        g.setTransform(IDENTITY);
        g.drawImage(miniImg, (int) Math.floor(v.devX + v.dev * (x - 5) + 0.5), (int) Math.floor(v.devY + v.dev * (y - 5) + 0.5), null);
        g.setTransform(scrTx);
        Shape clip = g.getClip();
        g.clip(rect);
        // freezing level
        double fy = y + f.freezingY * ky;
        g.setColor(new Color(150, 230, 255, 110));
        g.fill(new Rectangle2D.Double(x, fy, w, 0.8));
        // clouds
        for (Shared.DrawCloud dc : sh.drawClouds) {
            Model.Cloud c = dc.c;
            double cw = Math.max(4, c.w * kx), ch = Math.max(2.5, c.h * ky);
            int a = (int) (230 * Math.min(1, 0.15 + c.form));
            g.setColor(c.id == selId ? new Color(255, 236, 140, a) : alpha(mix(new Color(250, 252, 255), new Color(120, 130, 150), c.darkness), a));
            g.fill(new Ellipse2D.Double(x + dc.x * kx - cw / 2, y + dc.y * ky - ch / 2, cw, ch));
            if (c.precipRate > 0.05) {
                g.setColor(alpha(precipColor(c.precipType), 120));
                double gy2 = y + hello.terrain.at(dc.x) * ky;
                g.fill(new Rectangle2D.Double(x + dc.x * kx - cw * 0.25, y + dc.y * ky + ch / 2, cw * 0.5, Math.max(0, gy2 - (y + dc.y * ky + ch / 2))));
            }
        }
        // v2.3: a tornado on the map
        for (Model.Tornado tn : f.tornadoes) {
            double mx = x + tn.x * kx, my0 = y + tn.baseY * ky, my1 = y + (tn.baseY + (tn.groundY - tn.baseY) * tn.reach) * ky;
            Path2D.Double tri = new Path2D.Double();
            tri.moveTo(mx - 4, my0);
            tri.lineTo(mx + 4, my0);
            tri.lineTo(mx, Math.max(my0 + 3, my1));
            tri.closePath();
            g.setColor(alpha(TORNADO_RED, 240));
            g.fill(tri);
        }
        g.setClip(clip);
        // the view rectangle
        double rx0 = x + Math.max(0, v.wx0) * kx, rx1 = x + Math.min(worldW, v.wx1) * kx;
        double ry0 = y + Math.max(0, v.wy0) * ky, ry1 = y + Math.min(worldH, v.wy1) * ky;
        g.setColor(new Color(255, 255, 255, 34));
        g.fill(new Rectangle2D.Double(rx0, ry0, rx1 - rx0, ry1 - ry0));
        g.setStroke(new BasicStroke(1.4f));
        g.setColor(new Color(255, 236, 140, 235));
        g.draw(new Rectangle2D.Double(rx0, ry0, Math.max(2, rx1 - rx0), Math.max(2, ry1 - ry0)));
    }

    private BufferedImage miniImg;
    private long miniBgKey = Long.MIN_VALUE;

    private void paintMinimapStatic(Graphics2D g, Rectangle2D.Double rect, double kx, double ky) {
        double x = rect.x, y = rect.y, w = rect.width, h = rect.height;
        pill(g, x - 5, y - 5, w + 10, h + 10, new Color(8, 12, 22, 190));
        Shape clip = g.getClip();
        g.clip(rect);
        g.setPaint(new GradientPaint(0, (float) y, alpha(mul(skyTop, 0.8), 230), 0, (float) (y + h), alpha(mul(skyBot, 0.8), 230)));
        g.fill(rect);
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x, y + h + 2);
        for (double px = 0; px <= w + 0.5; px += 1.5) p.lineTo(x + px, y + hello.terrain.at(px / kx) * ky);
        p.lineTo(x + w, y + h + 2);
        p.closePath();
        Preset.Biome mb = hello.preset != null && hello.preset.biome != null && !hello.preset.biome.plain() ? hello.preset.biome : null;
        g.setColor(lit(mb != null ? Lit.mul(mb.grass != null ? mb.grass : mb.ground, 0.84) : new Color(92, 124, 82)));
        g.fill(p);
        for (Model.Settlement s : hello.settlements) {
            double sx0 = x + s.x0 * kx, sx1 = x + s.x1 * kx, sy = y + hello.terrain.at((s.x0 + s.x1) / 2) * ky;
            g.setColor(s.kind.equals("city") ? new Color(255, 214, 130, 230) : s.kind.equals("town") ? new Color(250, 200, 140, 220) : new Color(236, 178, 140, 210));
            double bh = s.kind.equals("city") ? 9 : s.kind.equals("town") ? 6 : 3.5;
            int n = Math.max(2, (int) ((sx1 - sx0) / 3));
            for (int k = 0; k < n; k++) {
                double hh2 = bh * (0.35 + 0.65 * hash01((long) (s.id * 131 + k)));
                g.fill(new Rectangle2D.Double(sx0 + k * (sx1 - sx0) / n, sy - hh2, (sx1 - sx0) / n * 0.7, hh2));
            }
        }
        g.setClip(clip);
    }

    // ------------------------------------------------------------------------------ life card

    private void drawLifeCard(Graphics2D g, ViewerState.Life l, Model.Frame f) {
        int w = 330, h = 262;
        int x = (int) (v.vx + v.vw) - w - 12, y = (int) Math.max(v.vy + 40, v.vy + v.vh - h - (minimapRect != null ? minimapRect.getHeight() + 30 : 14));
        cardRect = new Rectangle2D.Double(x, y, w, h);
        cardClose = new Rectangle2D.Double(x + w - 24, y + 6, 18, 18);
        pill(g, x, y, w, h, new Color(10, 14, 24, 225));
        g.setColor(new Color(255, 236, 140, 160));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, 14, 14));
        g.setFont(F_BOLD);
        g.setColor(new Color(170, 180, 200));
        g.drawString("×", (float) (x + w - 19), (float) (y + 19));
        g.setFont(F_TITLE);
        g.setColor(Color.WHITE);
        g.drawString(fit(g, l.name, w - 40), x + 12, y + 22);
        g.setFont(F_SMALL);
        String status = l.alive ? "alive · " + l.stage : String.format("dissipated at t=%.1f", l.died);
        // v2.1: what kind of cloud it is / was - still fair weather, and whether it grew out of another type
        String kindText = l.fromType.isEmpty() ? l.type
                : l.morphing() ? String.format("%s → %s %.0f%%", l.fromType, l.type, Math.floor(l.morph * 100)) : l.type + " (from " + l.fromType + ")";
        if (l.fair) kindText += l.alive ? "  ·  fair weather" : "  ·  fair weather, never rained";
        // v2.3: a merged cloud names what it is made of; an absorbed one says where it went
        if (!l.absorbed.isEmpty()) {
            StringBuilder ts = new StringBuilder();
            for (String t2 : l.absorbedTypes) ts.append(" + ").append(t2);
            int at = kindText.indexOf("  ·  fair weather");          // "cumulus + stratus  ·  fair weather"
            kindText = at < 0 ? kindText + ts : kindText.substring(0, at) + ts + kindText.substring(at);
        }
        if (l.mergedInto >= 0) {
            String into = l.mergedIntoName.isEmpty() ? "#" + l.mergedInto : shortName(l.mergedIntoName);
            status = l.alive ? "merging into " + into : String.format("merged into %s at t=%.1f", into, l.died);
        }
        g.setColor(l.alive ? STAGE_COL[Model.stageIndex(l.stage)] : new Color(170, 170, 185));
        g.drawString(fit(g, kindText + "  ·  " + status, w - 24), x + 12, y + 38);

        // silhouette thumbnail
        int tx = x + 12, ty = y + 48, tw = 130, th = 92;
        g.setPaint(new GradientPaint(0, ty, new Color(46, 96, 170), 0, ty + th, new Color(150, 196, 236)));
        g.fill(new RoundRectangle2D.Double(tx, ty, tw, th, 10, 10));
        if (l.puffs.length > 0) {
            Shape clip = g.getClip();
            g.clip(new RoundRectangle2D.Double(tx, ty, tw, th, 10, 10));
            drawThumb(g, sh.cardThumbs.get(l.id), tx, ty, tw, th);
            g.setClip(clip);
        }
        g.setFont(F_SMALL);
        g.setColor(new Color(200, 210, 225));
        g.drawString("unique silhouette · " + l.puffs.length + " puffs", tx, ty + th + 13);

        // facts
        double now = f.t;
        double end = l.end(now);
        String[][] facts = {
                {"Born", String.format("t = %.1f s", l.born)},
                {l.alive ? "Age" : "Lived", String.format("%.1f s", end - l.born)},
                {"Lifetime", String.format("%.0f s planned", l.lifetime)},
                {"Peak water", String.format("%.2f", l.peakWater)},
                {"Precip", l.precipType},
                {"Total", String.format("%.2f mm", l.totalPrecip)},
                null,
        };
        // v2.3: a seventh line when there is a merger or a tornado to report
        if (l.tornadoes > 0) facts[6] = new String[]{"Tornado", l.tornadoes + (l.peakEf >= 0 ? " · peak EF" + l.peakEf : " · funnel only")};
        else if (!l.absorbed.isEmpty()) {
            StringBuilder ab = new StringBuilder();
            for (String nm : l.absorbed) ab.append(ab.length() > 0 ? ", " : "").append(shortName(nm));
            facts[6] = new String[]{"Absorbed", ab.toString()};
        } else if (l.mergedInto >= 0) facts[6] = new String[]{"Merged into", l.mergedIntoName.isEmpty() ? "#" + l.mergedInto : shortName(l.mergedIntoName)};
        else if (l.peakRotation > 0.05) facts[6] = new String[]{"Rotation", String.format("peak %.0f %%", l.peakRotation * 100)};
        int fx = tx + tw + 12, fy = ty + 10;
        for (String[] r : facts) {
            if (r == null) continue;
            g.setColor(new Color(150, 168, 192));
            g.drawString(r[0], fx, fy);
            g.setColor(r[0].equals("Precip") ? precipColor(r[1]) : r[0].equals("Tornado") ? TORNADO_RED : new Color(236, 242, 250));
            g.drawString(fit(g, r[1], w - (fx - x) - 72 - 10), fx + 72, fy);
            fy += 15;
        }
        // stage bar + table
        int by = ty + th + 24;
        double span = Math.max(1e-3, end - l.born);
        double bx = x + 12, bw = w - 24;
        String[] names = Model.STAGES;
        double[] starts = new double[names.length];
        for (int k = 0; k < names.length; k++) starts[k] = l.stageTimes.getOrDefault(names[k], Double.NaN);
        for (int k = 0; k < names.length; k++) {
            if (Double.isNaN(starts[k])) continue;
            double s = starts[k], e = end;
            for (int j = k + 1; j < names.length; j++) if (!Double.isNaN(starts[j])) { e = starts[j]; break; }
            double sx = bx + (s - l.born) / span * bw, ex = bx + (e - l.born) / span * bw;
            g.setColor(STAGE_COL[k]);
            g.fill(new Rectangle2D.Double(sx, by, Math.max(1, ex - sx), 9));
        }
        g.setColor(new Color(255, 255, 255, 60));
        g.draw(new Rectangle2D.Double(bx, by, bw, 9));
        // marks on the bar: where a fair cloud developed (hollow), where it began to tower into its new type (solid)
        if (!Double.isNaN(l.developedT) && l.developedT >= l.born && l.developedT <= end) {
            double mx = bx + (l.developedT - l.born) / span * bw;
            g.setColor(new Color(255, 255, 255, 230));
            g.setStroke(new BasicStroke(1f));
            g.draw(new Line2D.Double(mx, by - 3, mx, by + 12));
        }
        if (!Double.isNaN(l.morphT) && l.morphT >= l.born && l.morphT <= end) lifeMark(g, bx + (l.morphT - l.born) / span * bw, by, 9, 3.5);
        int ly = by + 24;
        for (int k = 0; k < names.length; k++) {
            int col = k % 2, row = k / 2;
            double lx = bx + col * 156;
            double yy = ly + row * 16;
            g.setColor(STAGE_COL[k]);
            g.fill(new Ellipse2D.Double(lx, yy - 8, 8, 8));
            g.setColor(Double.isNaN(starts[k]) ? new Color(120, 128, 140) : new Color(225, 232, 244));
            String v = Double.isNaN(starts[k]) ? (k == 3 && !l.alive ? "never" : "—") : String.format("%.1f", starts[k]);
            g.drawString(names[k] + "  " + v + (Double.isNaN(starts[k]) ? "" : " s"), (float) lx + 12, (float) yy);
        }
        // sixth slot of the table: the fair-weather story of this cloud, or when it began to tower
        String note = null;
        boolean hollow = false;
        if (l.fair) { note = "fair weather"; hollow = true; }
        else if (!Double.isNaN(l.developedT)) { note = String.format("developed  %.1f s", l.developedT); hollow = true; }
        else if (!Double.isNaN(l.morphT)) note = String.format("towering  %.1f s", l.morphT);
        else if (l.everFair) { note = "developed"; hollow = true; }
        else if (!l.fromType.isEmpty()) note = "from " + l.fromType;
        if (note != null) {
            double lx = bx + 156, yy = ly + 2 * 16;
            g.setColor(new Color(235, 240, 250));
            g.setStroke(new BasicStroke(1.2f));
            if (hollow) g.draw(new Ellipse2D.Double(lx + 0.6, yy - 7.4, 6.8, 6.8));
            else lifeMark(g, lx + 4, yy - 8, 8, 3.5);
            g.setColor(new Color(225, 232, 244));
            g.drawString(note, (float) lx + 12, (float) yy);
        }
    }

    /** The "began to tower into a new type" mark: a small white up-pointing wedge sitting on a bar at x. */
    private static void lifeMark(Graphics2D g, double x, double by, double barH, double r) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x, by - 1);
        p.lineTo(x + r, by + barH);
        p.lineTo(x - r, by + barH);
        p.closePath();
        g.setColor(new Color(255, 255, 255, 235));
        g.fill(p);
        g.setColor(new Color(10, 14, 24, 200));
        g.setStroke(new BasicStroke(0.8f));
        g.draw(p);
    }

    private static double darkFor(String stage) {
        return switch (stage) {
            case "precipitating" -> 0.55;
            case "mature" -> 0.3;
            default -> 0.15;
        };
    }

    // ------------------------------------------------------------------------------ side panel

    // ------------------------------------------------------------------------------ side panel

    // side-panel cache: everything but the pulsing "alive" dots, re-rendered only when what it
    // shows changes (lives, stages, selection, scroll, events, or "now" moving the timeline >= 1 px)
    private BufferedImage panelImg;
    private long panelSig = Long.MIN_VALUE;
    private boolean recordPulses;
    private double[] pulses = new double[0]; // x, y, row index triples
    private int nPulses;
    long panelBuilds; // diagnostics

    private void drawPanel(Graphics2D g, ViewerState st, Model.Frame f) {
        AffineTransform tx = g.getTransform();
        if (!axisAligned(tx)) { recordPulses = false; drawPanelContent(g, f); return; }
        double sc = tx.getScaleX();
        int dx = (int) Math.floor(tx.getTranslateX() + sc * panelXi + 0.5), dy = (int) Math.floor(tx.getTranslateY() + 0.5);
        int dw = (int) Math.floor(tx.getTranslateX() + sc * (panelXi + PANEL_W) + 0.5) - dx;
        int dh = (int) Math.floor(tx.getTranslateY() + sc * canvasHi + 0.5) - dy;
        if (dw <= 0 || dh <= 0) return;
        long sig = panelSignature(f, tx) * 31 + g.getStroke().hashCode(); // the panel inherits the current stroke
        if (panelImg == null || panelImg.getWidth() != dw || panelImg.getHeight() != dh || sig != panelSig) {
            if (panelImg == null || panelImg.getWidth() != dw || panelImg.getHeight() != dh) {
                panelImg = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
            }
            Graphics2D pg = panelImg.createGraphics();
            pg.setRenderingHints(g.getRenderingHints());
            pg.setStroke(g.getStroke()); // e.g. the timeline gridlines use whatever stroke the world pass left set
            pg.setTransform(new AffineTransform(sc, 0, 0, sc, tx.getTranslateX() - dx, tx.getTranslateY() - dy));
            recordPulses = true;
            nPulses = 0;
            drawPanelContent(pg, f);
            recordPulses = false;
            pg.dispose();
            panelSig = sig;
            panelBuilds++;
        }
        blitAt(g, tx, panelImg, dx, dy);
        if (nPulses > 0) {
            Shape clip = g.getClip();
            g.clip(new Rectangle2D.Double(panelXi, tlTop, PANEL_W, tlBottom - tlTop));
            for (int k = 0; k < nPulses; k++) {
                double pulse = 0.5 + 0.5 * Math.sin(anim * 5 + pulses[k * 3 + 2]);
                g.setColor(new Color(255, 255, 255, (int) (120 + 120 * pulse)));
                g.fill(new Ellipse2D.Double(pulses[k * 3] - 3, pulses[k * 3 + 1] - 3, 6, 6));
            }
            // the dashed "now" line is drawn over the dots in the full render; keep that layering
            double nx = panelXi + PANEL_W - 12;
            g.setColor(new Color(255, 236, 140, 120));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{3, 3}, 0));
            for (int k = 0; k < nPulses; k++) {
                Shape c2 = g.getClip();
                g.clip(new Rectangle2D.Double(pulses[k * 3] - 4, pulses[k * 3 + 1] - 4, 8, 8));
                g.draw(new Line2D.Double(nx, tlTop, nx, tlBottom));
                g.setClip(c2);
            }
            g.setClip(clip);
        }
    }

    /** Everything the panel shows, as a cheap hash (see drawPanel). */
    private long panelSignature(Model.Frame f, AffineTransform tx) {
        long h = 1469598103934665603L;
        double sc = tx.getScaleX();
        h = h * 31 + Double.doubleToLongBits(sc);
        h = h * 31 + Double.doubleToLongBits(tx.getTranslateX());
        h = h * 31 + Double.doubleToLongBits(tx.getTranslateY());
        h = h * 31 + panelXi * 7919L + canvasHi;
        h = h * 31 + selId;
        h = h * 31 + timelineScroll;
        h = h * 31 + (sh == null ? 0 : sh.thumbVersion);
        double now = f != null ? f.t : 0, tMin = now;
        h = h * 31 + livesSnap.size();
        for (ViewerState.Life l : livesSnap) {
            tMin = Math.min(tMin, l.born);
            h = h * 31 + l.id;
            h = h * 31 + (l.alive ? 1 : 2);
            h = h * 31 + l.stageTimes.size();
            h = h * 31 + (l.precipType == null ? 0 : l.precipType.hashCode());
            h = h * 31 + l.name.hashCode();
            h = h * 31 + l.type.hashCode() + (Double.isNaN(l.morphT) ? 0 : Double.doubleToLongBits(l.morphT));
            h = h * 31 + Double.doubleToLongBits(l.born);
            if (!l.alive) h = h * 31 + Double.doubleToLongBits(l.died);
            h = h * 31 + l.mergeTs.size() * 7L + l.mergedInto;
        }
        for (ViewerState.TornadoRec r : tornadoSnap) h = h * 31 + r.id * 3 + (r.alive ? 1 : 2) + (Double.isNaN(r.touchT) ? 0 : 5);
        double span = Math.max(30, now - tMin);
        double pxPerSec = (PANEL_W - LABEL_W - 8 - 12) / span * sc;   // device px the timeline moves per sim second
        h = h * 31 + (long) Math.floor(now * pxPerSec);           // whole device-pixel steps
        h = h * 31 + eventsSnap.size();
        for (String e : eventsSnap) h = h * 31 + e.hashCode();
        return h;
    }

    private void drawPanelContent(Graphics2D g, Model.Frame f) {
        int x0 = panelXi, w = PANEL_W, h = canvasHi;
        g.setPaint(new GradientPaint(x0, 0, new Color(16, 20, 30), x0 + w, 0, new Color(22, 27, 40)));
        g.fill(new Rectangle2D.Double(x0, 0, w, h));
        g.setColor(new Color(255, 255, 255, 25));
        g.fill(new Rectangle2D.Double(x0, 0, 1, h));

        double now = f != null ? f.t : 0;
        List<ViewerState.Life> lives = livesSnap;
        g.setFont(F_TITLE);
        g.setColor(Color.WHITE);
        g.drawString("CLOUD LIFETIMES", x0 + 14, 24);
        g.setFont(F_SMALL);
        g.setColor(new Color(160, 175, 200));
        long alive = lives.stream().filter(l -> l.alive).count();
        String cnt = lives.size() + " clouds · " + alive + " alive";
        g.drawString(cnt, x0 + w - 14 - g.getFontMetrics().stringWidth(cnt), 24);
        // stage chips
        int cx = x0 + 14;
        for (int k = 0; k < 5; k++) {
            g.setColor(STAGE_COL[k]);
            g.fill(new RoundRectangle2D.Double(cx, 33, 10, 10, 3, 3));
            g.setColor(new Color(200, 210, 225));
            g.drawString(STAGE_LABEL[k], cx + 13, 42);
            cx += 14 + g.getFontMetrics().stringWidth(STAGE_LABEL[k]) + 10;
        }

        int logH = 190;
        tlTop = 54;
        tlBottom = h - logH - 30;
        int labelW = LABEL_W;
        double tx0 = x0 + labelW + 8, tx1 = x0 + w - 12;
        double tMin = now, tMax = now;
        for (ViewerState.Life l : lives) tMin = Math.min(tMin, l.born);
        if (tMax - tMin < 30) tMin = tMax - 30;
        double tSpan = tMax - tMin;
        List<long[]> rowsOut = new ArrayList<>();

        // gridlines + axis
        double step = niceStep(tSpan / 5);
        g.setFont(F_SMALL);
        for (double t = Math.ceil(tMin / step) * step; t <= tMax + 1e-6; t += step) {
            double xx = tx0 + (t - tMin) / tSpan * (tx1 - tx0);
            g.setColor(new Color(255, 255, 255, 18));
            g.draw(new Line2D.Double(xx, tlTop, xx, tlBottom));
            g.setColor(new Color(140, 155, 180));
            String s = String.format("%.0fs", Math.abs(t) < 0.5 ? 0.0 : t);      // (never "-0s" for the tick at zero)
            g.drawString(s, (float) (xx - g.getFontMetrics().stringWidth(s) / 2.0), tlBottom + 14);
        }

        int n = lives.size();
        int area = tlBottom - tlTop;
        double rowH = n == 0 ? 16 : Model.clamp(area / (double) n, 7, 18);
        int visible = (int) Math.floor(area / rowH);
        timelineScroll = Math.max(0, Math.min(timelineScroll, Math.max(0, n - visible)));
        int first = Math.max(0, n - visible - timelineScroll);
        int last = Math.min(n, first + visible);
        Shape clip = g.getClip();
        g.clip(new Rectangle2D.Double(x0, tlTop, w, area));
        for (int i = first; i < last; i++) {
            ViewerState.Life l = lives.get(i);
            double y = tlTop + (i - first) * rowH;
            rowsOut.add(new long[]{l.id, (long) y, (long) (y + rowH)});
            boolean sel = l.id == selId;
            if (sel) {
                g.setColor(new Color(255, 236, 140, 40));
                g.fill(new Rectangle2D.Double(x0 + 4, y, w - 8, rowH));
            } else if ((i & 1) == 0) {
                g.setColor(new Color(255, 255, 255, 7));
                g.fill(new Rectangle2D.Double(x0 + 4, y, w - 8, rowH));
            }
            if (rowH >= 9) {
                g.setFont(rowH >= 12 ? F_SMALL : new Font(Font.SANS_SERIF, Font.PLAIN, 9));
                g.setColor(l.alive ? new Color(235, 240, 250) : new Color(130, 140, 158));
                String nm = l.name;
                int q = nm.indexOf(' ');
                if (q > 0) nm = nm.substring(0, q);
                if (rowH >= 12 && sh != null) drawThumb(g, sh.rowThumbs.get(l.id), x0 + 6, y + rowH / 2 - 7, 28, 14);
                g.drawString(nm, x0 + (rowH >= 12 ? 38 : 10), (float) (y + rowH * 0.5 + 4));
            }
            double end = l.end(now);
            double barH = Math.max(3, rowH - 5), by = y + (rowH - barH) / 2;
            double[] starts = new double[5];
            boolean any = false;
            for (int k = 0; k < 5; k++) {
                starts[k] = l.stageTimes.getOrDefault(Model.STAGES[k], Double.NaN);
                any |= !Double.isNaN(starts[k]);
            }
            if (!any) starts[0] = l.born;
            for (int k = 0; k < 5; k++) {
                if (Double.isNaN(starts[k])) continue;
                double s = starts[k], e = end;
                for (int j = k + 1; j < 5; j++) if (!Double.isNaN(starts[j])) { e = starts[j]; break; }
                if (e <= s) continue;
                double sx = tx0 + (s - tMin) / tSpan * (tx1 - tx0), ex = tx0 + (e - tMin) / tSpan * (tx1 - tx0);
                Color c = STAGE_COL[k];
                if (!l.alive) c = mix(c, new Color(60, 66, 80), 0.35);
                g.setColor(c);
                g.fill(new Rectangle2D.Double(sx, by, Math.max(1, ex - sx), barH));
                if (k == 3 && !"none".equals(l.precipType) && barH >= 5) {
                    g.setColor(precipColor(l.precipType));
                    g.fill(new Rectangle2D.Double(sx, by + barH - 2, Math.max(1, ex - sx), 2));
                }
            }
            if (!Double.isNaN(l.morphT) && l.morphT <= end && barH >= 4)
                lifeMark(g, tx0 + (l.morphT - tMin) / tSpan * (tx1 - tx0), by, barH, Math.min(3.5, barH * 0.4));
            // v2.3: a white plus where it absorbed another cloud; a red line while it had a tornado
            if (barH >= 4) for (double mt : l.mergeTs) {
                if (mt < l.born || mt > end) continue;
                double mx = tx0 + (mt - tMin) / tSpan * (tx1 - tx0), cy = by + barH / 2, q = Math.min(3.2, barH * 0.42);
                g.setColor(new Color(10, 14, 24, 200));
                g.setStroke(new BasicStroke(3f));
                g.draw(new Line2D.Double(mx - q, cy, mx + q, cy));
                g.draw(new Line2D.Double(mx, cy - q, mx, cy + q));
                g.setColor(new Color(255, 255, 255, 240));
                g.setStroke(new BasicStroke(1.4f));
                g.draw(new Line2D.Double(mx - q, cy, mx + q, cy));
                g.draw(new Line2D.Double(mx, cy - q, mx, cy + q));
            }
            for (ViewerState.TornadoRec tr : tornadoSnap) {
                if (tr.cloud != l.id) continue;
                double s0 = tr.startT, e0 = tr.alive || Double.isNaN(tr.endT) ? now : tr.endT;
                double sx = tx0 + (s0 - tMin) / tSpan * (tx1 - tx0), ex2 = tx0 + (e0 - tMin) / tSpan * (tx1 - tx0);
                g.setColor(TORNADO_RED);
                g.fill(new Rectangle2D.Double(sx, by - 1, Math.max(2, ex2 - sx), Math.min(3, barH * 0.4)));
            }
            double ex = tx0 + (end - tMin) / tSpan * (tx1 - tx0);
            if (l.alive) {
                if (recordPulses) {
                    if (pulses.length < (nPulses + 1) * 3) pulses = Arrays.copyOf(pulses, (nPulses + 8) * 3);
                    pulses[nPulses * 3] = ex;
                    pulses[nPulses * 3 + 1] = by + barH / 2;
                    pulses[nPulses * 3 + 2] = i;
                    nPulses++;
                } else {
                    double pulse = 0.5 + 0.5 * Math.sin(anim * 5 + i);
                    g.setColor(new Color(255, 255, 255, (int) (120 + 120 * pulse)));
                    g.fill(new Ellipse2D.Double(ex - 3, by + barH / 2 - 3, 6, 6));
                }
            } else if (l.mergedInto >= 0) {
                // absorbed by another cloud: an arrow instead of the cross
                g.setColor(new Color(130, 224, 232, 220));
                g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                double cy = by + barH / 2, r = Math.min(3, barH / 2);
                g.draw(new Line2D.Double(ex + 1, cy, ex + 1 + 2.4 * r, cy));
                g.draw(new Line2D.Double(ex + 1 + 1.3 * r, cy - r, ex + 1 + 2.4 * r, cy));
                g.draw(new Line2D.Double(ex + 1 + 1.3 * r, cy + r, ex + 1 + 2.4 * r, cy));
            } else {
                g.setColor(new Color(200, 200, 215, 170));
                g.setStroke(new BasicStroke(1.1f));
                double cy = by + barH / 2, r = Math.min(3, barH / 2);
                g.draw(new Line2D.Double(ex + 1, cy - r, ex + 1 + 2 * r, cy + r));
                g.draw(new Line2D.Double(ex + 1, cy + r, ex + 1 + 2 * r, cy - r));
            }
            if (sel) {
                g.setColor(new Color(255, 236, 140, 200));
                g.setStroke(new BasicStroke(1f));
                g.draw(new Rectangle2D.Double(x0 + 4, y, w - 8, rowH));
            }
        }
        g.setClip(clip);
        timelineRows = rowsOut;
        // "now" line
        g.setColor(new Color(255, 236, 140, 120));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{3, 3}, 0));
        g.draw(new Line2D.Double(tx1, tlTop, tx1, tlBottom));
        if (n == 0) {
            g.setFont(F_SMALL);
            g.setColor(new Color(150, 160, 180));
            g.drawString("No clouds yet. Click the sky to seed one.", x0 + 14, tlTop + 20);
        } else if (n > visible) {
            g.setFont(F_SMALL);
            g.setColor(new Color(150, 160, 180));
            String s = timelineScroll > 0 ? "▲ " + first + " older  ·  scroll ▼ for newer" : "▲ " + first + " older (scroll)";
            g.drawString(s, x0 + 14, tlBottom + 26);
        }
        if (n > 0 && n <= visible) {
            g.setFont(F_SMALL);
            g.setColor(new Color(130, 140, 160));
            g.drawString("click a row or a cloud for its life card", x0 + 14, tlBottom + 26);
        }

        // event log
        int ly0 = h - logH;
        g.setColor(new Color(255, 255, 255, 20));
        g.fill(new Rectangle2D.Double(x0 + 8, ly0 - 2, w - 16, 1));
        g.setFont(F_BOLD);
        g.setColor(Color.WHITE);
        g.drawString("EVENT LOG", x0 + 14, ly0 + 16);
        g.setFont(F_MONO);
        FontMetrics fm = g.getFontMetrics();
        int lineH = 14;
        int maxLines = (logH - 30) / lineH;
        List<String> ev = eventsSnap;
        int start = Math.max(0, ev.size() - maxLines);
        int yy = ly0 + 34;
        for (int k = start; k < ev.size(); k++) {
            String s = ev.get(k);
            g.setColor(eventColor(s, k == ev.size() - 1));
            g.drawString(fitMono(fm, s, w - 28), x0 + 14, yy);
            yy += lineH;
        }
        if (ev.isEmpty()) {
            g.setColor(new Color(130, 140, 160));
            g.drawString("(waiting for events)", x0 + 14, yy);
        }
    }

    private static Color eventColor(String s, boolean newest) {
        String l = s.toLowerCase();
        Color c;
        if (l.contains("tornado") || l.contains("funnel")) c = new Color(255, 150, 120);
        else if (l.contains("absorbed") || l.contains("merged") || l.contains("merging")) c = new Color(130, 224, 232);
        else if (l.contains("lightning")) c = new Color(250, 230, 120);
        else if (l.contains("precipitat")) c = new Color(130, 170, 255);
        else if (l.contains("dissipat") || l.contains("died")) c = new Color(170, 140, 190);
        else if (l.contains("formed") || l.contains("born") || l.contains("spawn")) c = new Color(120, 220, 170);
        else if (l.contains("[viewer]")) c = new Color(240, 150, 120);
        else c = new Color(190, 200, 215);
        return newest ? c : alpha(c, 200);
    }

    private static String fitMono(FontMetrics fm, String s, int w) {
        if (fm.stringWidth(s) <= w) return s;
        while (s.length() > 2 && fm.stringWidth(s + "…") > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static String fit(Graphics2D g, String s, int w) { return fitMono(g.getFontMetrics(), s, w); }

    private static double niceStep(double raw) {
        double[] steps = {1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1200, 3600};
        for (double s : steps) if (s >= raw) return s;
        return 7200;
    }

    // ------------------------------------------------------------------------------ overlays

    private void drawHelp(Graphics2D g, String source) {
        String[][] rows = helpRows;
        // (v2.4 added two rows: at the smallest window the rows move a little closer so that the list still fits)
        final float pitch = (float) Model.clamp((v.canvasH - 16 - 80) / Math.max(1, rows.length), 15, 20);
        int w = 520, h = 60 + Math.round(rows.length * pitch) + 20;
        double x = v.vx + (v.vw - w) / 2, y = Math.max(8, (v.canvasH - h) / 2);
        g.setColor(new Color(0, 0, 0, 110));
        g.fill(new Rectangle2D.Double(0, 0, v.canvasW, v.canvasH));
        pill(g, x, y, w, h, new Color(12, 16, 28, 240));
        g.setColor(new Color(120, 180, 255, 160));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, 14, 14));
        g.setFont(F_BIG);
        g.setColor(Color.WHITE);
        g.drawString("Controls", (float) x + 18, (float) y + 30);
        g.setFont(F_SMALL);
        g.setColor(new Color(150, 165, 190));
        g.drawString("source: " + source, (float) x + 18, (float) y + 48);
        float yy = (float) y + 72;
        for (String[] r : rows) {
            g.setFont(F_BOLD);
            g.setColor(new Color(255, 226, 140));
            g.drawString(fit(g, r[0], 146), (float) x + 18, yy);
            g.setFont(F_NORM);
            g.setColor(new Color(225, 232, 244));
            g.drawString(r[1], (float) x + 170, yy);
            yy += pitch;
        }
    }

    private void drawError(Graphics2D g, String msg) {
        g.setColor(new Color(0, 0, 0, 150));
        g.fill(new Rectangle2D.Double(0, 0, vwI, vhI));
        String[] lines = msg.split("\n");
        g.setFont(F_NORM);
        FontMetrics fm = g.getFontMetrics();
        int w = 200;
        for (String l : lines) w = Math.max(w, fm.stringWidth(l));
        w = Math.min(vwI - 40, w + 40);
        int h = 60 + lines.length * 17;
        int x = (vwI - w) / 2, y = (vhI - h) / 2;
        pill(g, x, y, w, h, new Color(40, 12, 16, 240));
        g.setColor(new Color(255, 110, 110));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, 14, 14));
        g.setFont(F_BIG);
        g.drawString("Simulation problem", x + 20, y + 30);
        g.setFont(F_NORM);
        g.setColor(new Color(255, 225, 225));
        int yy = y + 54;
        for (String l : lines) { g.drawString(fit(g, l, w - 40), x + 20, yy); yy += 17; }
    }

    private void drawToast(Graphics2D g, String s) {
        g.setFont(F_BOLD);
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(s) + 24;
        int x = (vwI - w) / 2, y = vhI - 110;
        pill(g, x, y, w, 26, new Color(10, 14, 24, 200));
        g.setColor(Color.WHITE);
        g.drawString(s, x + 12, y + 17);
    }

    // ------------------------------------------------------------------------------ helpers

    private static void pill(Graphics2D g, double x, double y, double w, double h, Color c) {
        g.setColor(c);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 12, 12));
    }

    static Color mix(Color a, Color b, double t) {
        t = Model.clamp(t, 0, 1);
        return new Color(clampi((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t)),
                clampi((int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t)),
                clampi((int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t)),
                clampi((int) Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t)));
    }

    static Color mul(Color a, double k) {
        return new Color(clampi((int) (a.getRed() * k)), clampi((int) (a.getGreen() * k)), clampi((int) (a.getBlue() * k)), a.getAlpha());
    }

    static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), clampi(a)); }

    private static float dph(double v, double period) {
        double r = v % period;
        return (float) (r < 0 ? r + period : r);
    }

    static int clampi(int v) { return v < 0 ? 0 : Math.min(255, v); }

    private static double smooth(double x) {
        x = Model.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    // ------------------------------------------------------------------------------ hit tests

    /** Returns the id of the cloud under world point (x,y), or -1. Uses the drawn (interpolated) positions. */
    long cloudAt(ViewerState st, double x, double y) {
        Map<Long, double[]> drawn = drawnClouds;
        synchronized (st) {
            if (st.cur == null) return -1;
            long best = -1;
            double bestD = Double.MAX_VALUE;
            for (Model.Cloud c : st.cur.clouds) {
                if (c.form < 0.05) continue;
                double[] pos = drawn.getOrDefault(c.id, new double[]{c.x, c.y});
                for (double[] p : c.puffs) {
                    if (p[2] < 1) continue;
                    double top = Model.clamp((cloudBase(c) - c.y - p[1]) / Math.max(1, c.h), 0, 1);
                    double d = Math.hypot(x - (pos[0] + p[0] + c.shear * top), y - (pos[1] + p[1]));
                    if (d < p[2] * 1.1 && d / p[2] < bestD) { bestD = d / p[2]; best = c.id; }
                }
                if (c.puffs.length == 0 && Math.abs(x - pos[0]) < c.w / 2 && Math.abs(y - pos[1]) < c.h / 2) best = c.id;
            }
            return best;
        }
    }

    /** Timeline row under canvas point (x, y), or -1. */
    long timelineAt(double x, double y) {
        if (x < panelX || y < tlTop || y > tlBottom) return -1;
        for (long[] r : timelineRows) if (y >= r[1] && y < r[2]) return r[0]; // (a published snapshot)
        return -1;
    }

    /** World point for a canvas point on the minimap, or null when the point is not on it. */
    double[] minimapToWorld(double x, double y, boolean clampInside) {
        Rectangle2D r = minimapRect;
        if (r == null) return null;
        if (!clampInside && !r.contains(x, y)) return null;
        return new double[]{Model.clamp((x - r.getX()) / r.getWidth(), 0, 1) * worldW, Model.clamp((y - r.getY()) / r.getHeight(), 0, 1) * worldH};
    }
}
