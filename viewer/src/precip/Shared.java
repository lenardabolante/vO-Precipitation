package precip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the band renderers of one view share: the scenery tile caches, the cloud images, the
 * thumbnails, and the per-frame decisions (lighting, which clouds are drawn where). Renderer 0
 * fills it once per frame in {@code Renderer.prepareShared}; during the parallel band phase it is
 * only read, so all bands draw exactly the same frame.
 */
final class Shared {
    final TileLayer far = new TileLayer("far ridge", 0.16), near = new TileLayer("near ridge", 0.42);
    final TileLayer scenery = new TileLayer("scenery", 1), ground = new TileLayer("ground", 1);
    final SharedClouds clouds = new SharedClouds();

    /** One cloud as drawn this frame (interpolated position). */
    static final class DrawCloud {
        Model.Cloud c;
        double x, y;
        SharedClouds.Entry e;
    }

    /** v2.3: one car as drawn this frame (interpolated between the last two frames). */
    static final class DrawCar {
        Model.Car c;
        double x, y, angle, lift, spin;
        /** The wind where the car is, m/s (positive blows to the right): the water on its glass leans with it. */
        double wind;
    }

    /** A small cached portrait of a cloud (life card, timeline rows). */
    static final class Thumb {
        CloudPainter.Image img;
        long sig = Long.MIN_VALUE, builtClock;
        double minX, maxX, minY, maxY;   // skeleton extents the portrait was fitted to
    }

    // ---- per frame (written by renderer 0 before the bands run)
    Camera.View view;
    /** Settings > Advanced as this frame is drawn: read once from {@link Advanced#cur}, so every band draws with the same options. */
    Advanced adv = Advanced.DEFAULT;
    /** Counts the changes of {@link #adv} seen by this view: whatever is cached from the options (lightning lights and grids) is keyed by it. */
    long advVer;
    Lit lit;
    CloudPainter.Light cloudLight;
    Scene scene;                 // live state for per-frame drawing
    Model.Obj[] liveObjs = new Model.Obj[0];
    final List<DrawCloud> drawClouds = new ArrayList<>();
    final List<DrawCar> drawCars = new ArrayList<>();
    /** v2.3: the funnel images of the tornadoes in view (parallel lists). */
    final List<TornadoPainter.Funnel> funnels = new ArrayList<>();
    /** The funnel image last rendered for each tornado id (see {@link TornadoPainter.Funnel#sig}). */
    final Map<Long, TornadoPainter.Funnel> funnelCache = new HashMap<>();
    double extrap;
    /** Under-cloud dimming: the eased cover (see {@link CloudShade}) and the veil opacity 0..1 drawn this frame. */
    final CloudShade shade = new CloudShade();
    double shadeAlpha;
    /** World rectangles (x0, y0, x1, y1, ...) of the clouds drawn this frame: a particle inside one may be hidden by that cloud. */
    double[] cloudBoxes = new double[0];
    /**
     * "Don't render what you can't see": the cloud images of this frame that are copied to the screen pixel for pixel (at the
     * view's own scale) and have opaque cells, with the device position of their top-left pixel; {@link #occN} of them. A
     * cloud in the middle of a cross-fade has its previous image in {@link #occPrev} (else null): it hides only what both do.
     */
    CloudPainter.Image[] occImg = new CloudPainter.Image[0], occPrev = new CloudPainter.Image[0];
    int[] occX = new int[0], occY = new int[0], occPX = new int[0], occPY = new int[0];
    int occN;
    /** Lightning: what the view remembers between frames, and this frame's lights and bolts (null = none, the usual case). */
    final Lightning.State lightning = new Lightning.State();
    Lightning.Frame bolts;
    /** v2.4: the standing water as drawn this frame (eased by renderer 0 in {@link WaterPainter#prepare}; the bands only read it). */
    final WaterPainter.State water = new WaterPainter.State();
    /** v2.4.1: the film of water on the ground as drawn this frame (eased and laid out by renderer 0 in {@link FlowPainter#prepare}; the bands only read it). */
    final FlowPainter.State flow = new FlowPainter.State();
    /** v2.4: the flood state of every settlement, the drains by settlement id and how their sizes were chosen, as of this frame. */
    List<Model.Flood> flood = List.of();
    Map<Long, Model.Drain> drains = Map.of();
    String drainMode = "";
    /** v2.4: the pipe overlay and the drains card are on in this frame (the primary renderer's switches, the same for every band). */
    boolean showDrains, showDrainCard;
    /**
     * Phase O (OW): the pieces a tornado has torn off, and which buildings have pieces in the air, as of this frame
     * ({@link Wreck#snap}; never null). The bands only read it.
     */
    Wreck.Snap wreck = Wreck.Snap.NONE;
    /**
     * The clock the people move by ({@link Life#paint}), seconds: the renderer's animation clock less all the time the
     * simulation has stood paused under this view. It stands still while the frame says {@code paused} and goes on
     * from the same value afterwards, so nobody walks through a paused world and nobody jumps when it resumes. It runs
     * in wall time, not simulated time: a change of the simulation's speed neither hurries nor moves anyone. While the
     * view has never been paused it IS the animation clock, bit for bit.
     */
    double lifeAnim;
    /** The animation clock of the frame before (NaN = none yet), and the paused time taken out of {@link #lifeAnim} so far. */
    private double lifeLast = Double.NaN, lifeHeld;

    /** Once per frame, before the bands run: move {@link #lifeAnim} on to this frame. */
    void advanceLife(double animSec, boolean paused) {
        if (paused && !Double.isNaN(lifeLast)) lifeHeld += animSec - lifeLast;
        lifeLast = animSec;
        lifeAnim = animSec - lifeHeld;
    }

    // ---- baked scenery (tiles)
    Scene baked;
    long sceneVersion, ridgeVersion, bakedClock, bakedCover = Long.MIN_VALUE, bakedWorld = Long.MIN_VALUE;

    // per ground sample / per object: the cover level the tiles were last painted with
    int[] bakedGround = new int[0];
    int[] bakedObj = new int[0];
    /**
     * Per object: the coarse wind level ({@code BuildingPainter.windLevel}) the rain beads on its panes are painted with.
     * Looked at about once a second; the array is replaced, never written in place (a {@link Scene} keeps the one it was made with).
     */
    byte[] objWind = new byte[0];
    /** Per object: the wind (m/s) averaged over a few seconds, which the levels are taken from; {@link #windT} = the sim time of its last sample (NaN = none yet). */
    float[] objWindAvg = new float[0];
    double windT = Double.NaN;
    /** The world those averages belong to (another world starts from its own wind). */
    Model.Hello windHello;
    long windClock = Long.MIN_VALUE;
    /** A wind level changed since the tiles were last compared with the live scene. */
    boolean windMoved;
    /** Diagnostics: how often a building's wind level changed after the first look, and how often a wet building was repainted for that alone. */
    long windLevelChanges, windRepaints;
    /** "No snow": the object list the snowless copy was made from, and that copy. */
    Model.Obj[] snowSrc, snowObjs;
    double dirtyX0, dirtyX1;
    /** Phase O (OW4): the airborne bits the tiles were last baked with (belongs to {@link #baked}), and how often a building was repainted for that bit alone. */
    Wreck.Snap bakedWreck = Wreck.Snap.NONE;
    long airRepaints;

    // ---- custom backdrop image fitted to the canvas (see Renderer.prepareBackdrop)
    java.awt.image.BufferedImage bdScaled, bdSource;
    int bdKeyW, bdKeyH, bdKeyFit;

    // ---- thumbnails
    final Map<Long, Thumb> cardThumbs = new HashMap<>(), rowThumbs = new HashMap<>();
    final CloudPainter.Scratch thumbScratch = new CloudPainter.Scratch();
    long thumbVersion;

    boolean async = true;

    void setAsync(boolean a) {
        async = a;
        clouds.async = a;
        far.async = a; near.async = a; scenery.async = a; ground.async = a;
    }

    long tileBuilds() { return far.builds + near.builds + scenery.builds + ground.builds; }

    long tileBuildNanos() { return far.buildNanos + near.buildNanos + scenery.buildNanos + ground.buildNanos; }

    void shutdown() { clouds.shutdown(); }
}
