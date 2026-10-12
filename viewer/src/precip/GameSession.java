package precip;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * One game: a simulation process (or a diagnostic source), the viewer's state of it, the save slot
 * it belongs to, and the saving machinery.
 * <p>
 * <b>Saving never blocks a frame.</b> {@link #requestSave} only sends the {@code save} command.
 * The simulation writes {@code state.sav.new} and answers {@code saved}; that answer arrives on the
 * reader thread, where the history and the numbers for {@code meta.json} are captured at exactly
 * the saved instant, and a background thread then moves the state into place and writes history,
 * thumbnail and meta. Only one save is in flight at a time; a second request simply waits for the
 * one in progress. If the simulation does not answer within {@link #SAVE_TIMEOUT_MS} the request
 * fails visibly and whatever was saved before stays untouched.
 */
final class GameSession {
    static final long SAVE_TIMEOUT_MS = 6000;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    final AppShell app;
    /** The slot this game is saved in; null for diagnostic sessions (--fake, --replay), which are never saved. */
    final SaveStore.Slot slot;
    final ViewerState state = new ViewerState();
    final FrameSource source;
    final String label;
    /** v2.2: {@code sandbox} (a world, saved or diagnostic), {@code hurricane} or {@code showcase} (the two lesson modes: no slot, never saved). */
    String mode = "sandbox";
    ViewerPanel panel;

    private final long startNanos = System.nanoTime();
    private final double basePlaySeconds;
    /** False until the slot has a complete save on disk (a brand-new world before its first save). */
    private boolean everSaved;
    private long lastSaveNanos = startNanos;

    // ---- the save in flight (all on the event thread except replyReceived)
    private boolean inFlight;
    private volatile boolean replyReceived;
    private long flightStart;
    private String flightReason = "";
    private final List<Consumer<String>> waiters = new ArrayList<>();

    // ---- what the indicator says
    private String status = "";
    private long statusNanos = System.nanoTime();
    private boolean failed;
    private volatile boolean stopped;
    /** While the pause menu is open: whether the simulation was already paused before it opened. */
    boolean pauseMenuOpen, pausedBeforeMenu;
    boolean errorShown;

    GameSession(AppShell app, SaveStore.Slot slot, FrameSource source, boolean loaded, String label) {
        this.app = app;
        this.slot = slot;
        this.source = source;
        this.label = label;
        this.everSaved = loaded;
        this.basePlaySeconds = slot != null ? slot.playSeconds : 0;
        if (slot != null) status = loaded ? "Loaded" : "Not saved yet";
        state.savedListener = this::onSavedReply;
    }

    // ------------------------------------------------------------------ queries

    double simTime() {
        synchronized (state) { return state.cur != null ? state.cur.t : 0; }
    }

    String simError() {
        synchronized (state) { return state.error; }
    }

    boolean simRunning() {
        synchronized (state) { return state.error == null && state.hello != null && state.cur != null; }
    }

    boolean canSave() { return slot != null && !stopped && simRunning(); }

    boolean saving() { return inFlight; }

    boolean saveFailed() { return failed; }

    boolean everSaved() { return everSaved; }

    double playSeconds() { return basePlaySeconds + (System.nanoTime() - startNanos) / 1e9; }

    /** Text of the small indicator at the top of the game view; null = show nothing. */
    String indicatorText() {
        if (!mode.equals("sandbox")) return null;
        if (slot == null) return "Diagnostic session — not saved";
        return status.isEmpty() ? null : status;
    }

    /** Saving in progress or failed: keep the indicator fully visible. */
    boolean indicatorImportant() { return slot != null && (inFlight || failed); }

    double indicatorAge() { return (System.nanoTime() - statusNanos) / 1e9; }

    /** One line for the pause menu. */
    String saveStatus() { return status.isEmpty() ? "Not saved yet" : status; }

    private void setStatus(String s, boolean isFailure) {
        status = s;
        failed = isFailure;
        statusNanos = System.nanoTime();
    }

    // ------------------------------------------------------------------ saving

    /**
     * Ask for a save. {@code done} is called on the event thread with null on success or a message
     * for the player on failure. Never blocks.
     */
    void requestSave(String reason, Consumer<String> done) {
        if (slot == null) { if (done != null) done.accept("This session is not a saved simulation."); return; }
        if (!canSave()) { if (done != null) done.accept("The simulation is not running, so there is nothing to save."); return; }
        if (done != null) waiters.add(done);
        if (inFlight) return;                       // one save at a time: ride along with the one in progress
        inFlight = true;
        replyReceived = false;
        flightStart = System.currentTimeMillis();
        flightReason = reason;
        try {
            Files.createDirectories(slot.dir.toPath());
        } catch (IOException ex) {
            finish("Could not create the save folder: " + ex.getMessage());
            return;
        }
        try {
            Files.deleteIfExists(slot.file(SaveStore.STATE_NEW).toPath());   // a leftover of an interrupted save
        } catch (IOException ignored) { }                                    // if it is in the way the simulation reports it
        setStatus("Saving…", false);
        source.send("{\"cmd\":\"save\",\"path\":" + Json.quote(slot.file(SaveStore.STATE_NEW).getAbsolutePath().replace('\\', '/')) + "}");
    }

    /** The {@code saved} reply (reader thread: no frame has been applied since the simulation wrote its state). */
    private void onSavedReply(Model.Saved s) {
        if (slot == null) return;
        replyReceived = true;
        if (!s.ok) {
            SwingUtilities.invokeLater(() -> finish("The simulation could not save: " + (s.error == null ? "unknown error" : s.error)));
            return;
        }
        final Map<String, Object> history;
        final Model.Frame f;
        final Model.Hello h;
        synchronized (state) {
            history = state.exportHistory();
            f = state.cur;
            h = state.hello;
        }
        slot.simT = s.t;
        slot.frame = s.frame;
        if (f != null) {
            slot.alive = (int) f.stat("clouds_alive");
            slot.born = (int) f.stat("clouds_born");
            slot.dead = (int) f.stat("clouds_dead");
            slot.rainMm = f.stat("rain_mm");
            slot.snowMm = f.stat("snow_mm");
            slot.timeOfDay = f.timeOfDay;
            if (f.hasCalendar()) slot.month = f.month;          // v2.3: where the calendar stood
            slot.userPaused = pauseMenuOpen ? pausedBeforeMenu : f.paused;
        }
        if (h != null) {
            slot.seed = h.seed;
            slot.worldW = h.w;
            slot.worldH = h.h;
            slot.terrain = h.terrainKind;
            slot.settlementCount = h.settlements.size();
            if (h.preset != null) { slot.presetId = h.preset.id; slot.presetName = h.preset.name; }
        }
        slot.format = SaveStore.FORMAT;
        slot.protocol = Model.PROTOCOL_VERSION;
        slot.playSeconds = playSeconds();
        slot.lastPlayed = System.currentTimeMillis();
        ViewerPanel p = panel;
        if (p != null) {
            Camera cam = p.camera();
            if (cam.initialised && h != null) {
                slot.hasCamera = true;
                slot.camX = cam.tx; slot.camY = cam.ty; slot.camZoom = cam.tz;
            }
            Renderer r = p.renderer();
            slot.viewWind = r.showWind; slot.viewArrows = r.showArrows; slot.viewLabels = r.showLabels; slot.viewLegend = r.showLegend;
        }
        app.io.execute(() -> {
            String err = null;
            try {
                app.store.promoteState(slot);
                app.store.writeHistory(slot, history);
                try {
                    BufferedImage thumb = renderThumb(state, p);
                    if (thumb != null) app.store.writeThumb(slot, thumb);
                } catch (IOException | RuntimeException ex) {
                    System.err.println("[viewer] thumbnail not updated: " + ex);
                }
                app.store.writeMeta(slot);       // last: its presence marks a complete save
            } catch (IOException | RuntimeException ex) {
                err = "Could not write the save: " + ex.getMessage();
            }
            final String result = err;
            SwingUtilities.invokeLater(() -> finish(result));
        });
    }

    private void finish(String error) {
        boolean wasInFlight = inFlight;
        inFlight = false;
        if (error == null) {
            everSaved = true;
            lastSaveNanos = System.nanoTime();
            String verb = flightReason.isEmpty() ? "Saved" : flightReason;
            setStatus(verb + " " + LocalTime.now().format(CLOCK), false);
        } else {
            lastSaveNanos = System.nanoTime();     // do not retry in a tight loop; the next autosave interval tries again
            setStatus("Save failed — " + (everSaved ? "the previous save is intact" : "nothing saved yet"), true);
            System.err.println("[viewer] " + error);
            if (!everSaved && slot != null && slot.dir.isDirectory() && !slot.file(SaveStore.META).exists() && !slot.file(SaveStore.STATE).exists()) {
                // the very first save failed: do not leave an empty, "damaged" slot folder behind
                try { app.store.delete(slot); } catch (IOException ignored) { }
            }
        }
        if (!wasInFlight && waiters.isEmpty()) return;
        List<Consumer<String>> ws = new ArrayList<>(waiters);
        waiters.clear();
        for (Consumer<String> w : ws) w.accept(error);
    }

    /** Called a few times a second on the event thread: save timeout, the first save, the autosave timer. */
    void tick() {
        if (stopped || slot == null) return;
        if (inFlight && !replyReceived && System.currentTimeMillis() - flightStart > SAVE_TIMEOUT_MS) {
            finish("The simulation did not confirm the save within " + SAVE_TIMEOUT_MS / 1000 + " s.");
            app.toast("Save failed: the simulation did not answer. " + (everSaved ? "Your previous save is intact." : ""));
            return;
        }
        if (inFlight || !simRunning()) return;
        double since = (System.nanoTime() - lastSaveNanos) / 1e9;
        if (!everSaved) {
            // a new world is put on disk as soon as it exists, so it is listed even if something goes wrong later
            boolean ready;
            synchronized (state) { ready = state.framesReceived >= 30; }
            if (ready && since > 1.5) requestSave("Saved", err -> { if (err != null) app.toast("Could not save the new simulation: " + err); });
            return;
        }
        double every = app.settings.autosaveSeconds();
        // nothing to do while the clock has not moved since the last save (a long pause)
        if (every > 0 && since >= every && simTime() > slot.simT + 0.01) {
            requestSave("Autosaved", err -> { if (err != null) app.toast("Autosave failed: " + err); });
        }
    }

    // ------------------------------------------------------------------ stopping

    /** Stop the simulation process and wait for it to be gone (blocks up to ~1.5 s: call off the event thread). */
    void stop() {
        stopped = true;
        state.savedListener = null;
        source.stop();
    }

    boolean stopped() { return stopped; }

    // ------------------------------------------------------------------ thumbnail

    /**
     * A 320 x 180 picture of the world as the player was looking at it, without panels and labels.
     * Rendered with its own renderer so it never disturbs the live view.
     */
    static BufferedImage renderThumb(ViewerState st, ViewerPanel live) {
        final int w = 640, h = 360;
        synchronized (st) { if (st.hello == null || st.cur == null) return null; }
        ParallelRenderer pr = new ParallelRenderer(1);
        try {
            pr.shared.setAsync(false);
            Renderer r = pr.primary();
            r.showRightPanel = false; r.showLeftPanel = false; r.showMinimap = false;
            r.showLabels = false; r.showArrows = false; r.showWind = false; r.showErrorBox = false; r.decorative = true;
            Camera cam = new Camera();
            if (live != null) {
                Renderer lr = live.renderer();
                r.backdropImage = lr.backdropImage; r.backdropFit = lr.backdropFit; r.backdropDim = lr.backdropDim; r.backdropTint = lr.backdropTint;
            }
            pr.layout(cam, st, w, h);
            if (live != null && live.camera().initialised && live.camera().vw > 32) {
                Camera lc = live.camera();
                cam.set(lc.tx, lc.ty, lc.tz * w / lc.vw, true);     // the same span of the world, on a smaller canvas
            }
            double t;
            synchronized (st) { t = st.cur.t; }
            BufferedImage big = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            pr.render(big, 1, 0, 0, w, h, st, cam, -1, t, "");
            BufferedImage out = new BufferedImage(w / 2, h / 2, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(big, 0, 0, w / 2, h / 2, null);
            g.dispose();
            return out;
        } finally {
            pr.shutdown();
        }
    }
}
