package precip;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Cloud Formations" mode: a tab per cloud type over a live showcase world (protocol v2.2,
 * {@code --mode showcase}). The world itself is drawn by the ordinary {@link Renderer} into the
 * part of the window this screen leaves free, with the wind field, streamlines, arrows and labels
 * on; this screen adds the tabs, the lesson of the selected type (text from
 * {@code hello.showcase.lessons}) and the life-cycle strip of the cloud on stage.
 * <p>
 * Protocol v2.3: a ninth tab, {@code supercell}. A type that has an entry in
 * {@code hello.showcase.tours} gets its <b>lifetime tour</b> in place of the five-stage strip: the
 * ordered steps as chips, the step the cloud is at ({@code frame.showcase.tour_step}) lit, and that
 * step's text underneath. On the supercell stage the live numbers are rotation, wall cloud, updraft
 * and precipitation, and the tornado (while there is one) is named in the header.
 */
final class ShowcaseScreen extends Screen {
    /** The stage the simulation is asked for (it has to hold one whole cloud life in view). */
    static final int STAGE_W = 2000, STAGE_H = 900;
    private static final double[] SPEEDS = {0.25, 0.5, 1, 2, 4, 8};
    private static final Color BG = new Color(7, 11, 20);
    private static final String[] PARTS = {"formation", "life", "precipitation", "wind", "ending"};
    private static final String[] PART_TITLES = {"HOW IT FORMS", "ITS LIFE", "PRECIPITATION", "WIND", "HOW IT ENDS"};

    private final AppShell app;
    private final GameSession gs;
    /** The type whose tab is selected (what was asked for; the frames confirm it). */
    String type;
    /** Keep the tab the player picks in the settings (only for the mode opened from the app; a snapshot never writes). */
    boolean remember;
    private double scroll;
    private boolean fitted;
    private Map<String, Object> lessonsRaw;
    private final Map<String, Map<String, String>> lessons = new LinkedHashMap<>();
    /** v2.3 lifetime tours by type: rows of {id, title, text}, in order. */
    private final Map<String, List<String[]>> tours = new LinkedHashMap<>();
    /** The tour step last reported for the cloud on stage (kept through the pause between cycles so the strip does not blank). */
    private String lastStep;
    private List<String> types = new ArrayList<>(List.of(Model.CLOUD_TYPES));

    ShowcaseScreen(AppShell app, GameSession gs, String startType) {
        this.app = app;
        this.gs = gs;
        this.type = startType == null ? "cumulus" : startType;
    }

    @Override boolean dims() { return false; }

    // ------------------------------------------------------------------ layout shared with the renderer

    private static double topH() { return 86; }

    private static double rightW(int w) { return Model.clamp(w * 0.27, 316, 430); }

    private static double bottomH(int h) { return h < 660 ? 132 : 152; }

    /** Tell the world renderer which part of the canvas is its own (called before every frame is laid out). */
    void applyLayout(Renderer r, int w, int h) {
        r.insetLeft = 12;
        r.insetTop = topH();
        r.insetRight = rightW(w) + 24;
        r.insetBottom = bottomH(h) + 24;
    }

    /** The look of the showcase world: every explanatory overlay on, none of the sandbox's panels. */
    static void style(Renderer r) {
        r.showWind = true; r.showArrows = true; r.showLabels = true;
        r.showLegend = false; r.showLeftPanel = false; r.showRightPanel = false; r.showMinimap = false; r.showHelp = false;
        r.animatePanels = false;
        r.decorative = false;
        r.showErrorBox = false;
        r.hudReserveW = 0;
        r.shadeOn = false;                 // a lesson: the stage stays evenly lit
        r.showClimate = false;             // (the stage holds one fixed afternoon)
        r.backdropImage = null;
    }

    /** The camera always shows the whole stage. */
    void camera(Camera cam) {
        cam.fit();
        if (!fitted && cam.initialised) { cam.snap(); fitted = true; }
    }

    // ------------------------------------------------------------------ data

    private void readHello() {
        Model.Hello h;
        synchronized (gs.state) { h = gs.state.hello; }
        if (h == null || h.showcaseRaw == null || h.showcaseRaw == lessonsRaw) return;
        lessonsRaw = h.showcaseRaw;
        lessons.clear();
        List<Object> ts = Json.arr(h.showcaseRaw, "types");
        if (ts != null && !ts.isEmpty()) {
            types = new ArrayList<>();
            for (Object o : ts) if (o != null) types.add(String.valueOf(o));
        }
        Map<String, Object> ls = Json.obj(h.showcaseRaw, "lessons");
        if (ls != null) for (Map.Entry<String, Object> e : ls.entrySet()) {
            Map<String, Object> m = Json.obj(e.getValue());
            if (m == null) continue;
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> f : m.entrySet()) if (f.getValue() != null) out.put(f.getKey(), String.valueOf(f.getValue()));
            lessons.put(e.getKey(), out);
        }
        // v2.3: ordered lifetime tours (absent in a v2.2 hello: every tab then keeps its five-stage strip)
        tours.clear();
        Map<String, Object> ts2 = Json.obj(h.showcaseRaw, "tours");
        if (ts2 != null) for (Map.Entry<String, Object> e : ts2.entrySet()) {
            List<Object> steps = Json.arr(e.getValue());
            if (steps == null) continue;
            List<String[]> out = new ArrayList<>();
            for (Object o : steps) {
                Map<String, Object> m = Json.obj(o);
                if (m == null) continue;
                String id = Json.str(m, "id", "");
                if (!id.isEmpty()) out.add(new String[]{id, Json.str(m, "title", cap(id.replace('_', ' '))), Json.str(m, "text", "")});
            }
            if (!out.isEmpty()) tours.put(e.getKey(), out);
        }
    }

    /** The lifetime tour of the selected type, or null when it has none. */
    List<String[]> tour() { return tours.get(type); }

    private Model.Frame frame() {
        synchronized (gs.state) { return gs.state.cur; }
    }

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    // ------------------------------------------------------------------ actions

    void pick(String t) {
        if (t == null || t.equals(type)) return;
        type = t;
        lastStep = null;
        scroll = 0;
        gs.source.send("{\"cmd\":\"showcase\",\"type\":" + Json.quote(t) + "}");
        if (remember && !t.equals(app.settings.showcaseType)) {
            app.settings.showcaseType = t;
            app.settingsChanged();
        }
    }

    void restart() { gs.source.send("{\"cmd\":\"showcase\",\"restart\":true}"); }

    private void step(int dir) {
        int i = Math.max(0, types.indexOf(type));
        pick(types.get(Math.floorMod(i + dir, types.size())));
    }

    void togglePause() {
        Model.Frame f = frame();
        gs.source.send(f != null && f.paused ? "{\"cmd\":\"resume\"}" : "{\"cmd\":\"pause\"}");
    }

    void changeSpeed(int dir) {
        Model.Frame f = frame();
        double cur = f != null ? f.speed : 1;
        int idx = 2;
        double best = 1e9;
        for (int i = 0; i < SPEEDS.length; i++) if (Math.abs(SPEEDS[i] - cur) < best) { best = Math.abs(SPEEDS[i] - cur); idx = i; }
        idx = Math.max(0, Math.min(SPEEDS.length - 1, idx + dir));
        gs.source.send("{\"cmd\":\"speed\",\"value\":" + SPEEDS[idx] + "}");
    }

    @Override void key(KeyEvent e) {
        int code = e.getKeyCode();
        if (code >= KeyEvent.VK_1 && code <= KeyEvent.VK_9) {
            int k = code - KeyEvent.VK_1;
            if (k < types.size()) pick(types.get(k));
            return;
        }
        switch (code) {
            case KeyEvent.VK_ESCAPE -> app.leaveNow();
            case KeyEvent.VK_RIGHT, KeyEvent.VK_TAB -> step(+1);
            case KeyEvent.VK_LEFT -> step(-1);
            case KeyEvent.VK_SPACE, KeyEvent.VK_P -> togglePause();
            case KeyEvent.VK_R -> restart();
            case KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD -> changeSpeed(+1);
            case KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> changeSpeed(-1);
            default -> { }
        }
    }

    @Override void wheel(int x, int y, double rotation) { scroll = Math.max(0, scroll + rotation * 34); }

    // ------------------------------------------------------------------ painting

    @Override void paint(Ui ui, int w, int h) {
        readHello();
        Model.Frame f = frame();
        if (f != null && f.showcaseType != null && !types.contains(type)) type = f.showcaseType;
        double top = topH(), rw = rightW(w), bh = bottomH(h);
        double vx = 12, vy = top, vw = w - rw - 36, vh = h - top - bh - 24;
        // everything around the world view is this screen's
        ui.g.setColor(BG);
        ui.g.fill(new Rectangle2D.Double(0, 0, w, vy));
        ui.g.fill(new Rectangle2D.Double(0, vy, vx, h - vy));
        ui.g.fill(new Rectangle2D.Double(vx + vw, vy, w - vx - vw, h - vy));
        ui.g.fill(new Rectangle2D.Double(vx, vy + vh, vw, h - vy - vh));
        ui.stroke(vx - 1, vy - 1, vw + 2, vh + 2, 4, new Color(120, 180, 255, 80), 1f);

        topBar(ui, w, f);
        Model.Cloud c = null;
        if (f != null) {
            if (f.showcaseCloudId >= 0) c = f.cloud(f.showcaseCloudId);
            if (c == null && f.showcaseType == null && !f.clouds.isEmpty()) c = f.clouds.get(0);
        }
        List<String[]> tour = tour();
        if (tour != null && (f == null || f.showcaseType == null || f.showcaseType.equals(type))) tourStrip(ui, vx, vy + vh + 12, vw, bh, f, c, tour);
        else lifeStrip(ui, vx, vy + vh + 12, vw, bh, f, c);
        lessonPanel(ui, w - rw - 12, top, rw, h - top - 12);
        if (f == null && gs.simError() == null) ui.textCenter("Starting the simulation…", vx + vw / 2, vy + vh / 2, Ui.F_BIG, Ui.TEXT);
    }

    private void topBar(Ui ui, int w, Model.Frame f) {
        ui.text("CLOUD FORMATIONS", 16, 31, Ui.F_HEAD, Color.WHITE);
        double tx = 16 + ui.width("CLOUD FORMATIONS", Ui.F_HEAD) + 16;
        ui.textFit("how each cloud's life starts and ends — one cloud at a time, with its wind and precipitation", tx, 31, Math.max(0, w - tx - 400), Ui.F_SMALL, Ui.DIM);
        double rx = w - 12;
        ui.button("Menu", rx - 70, 10, 70, 30, Ui.NORMAL, true, false, app::leaveNow);
        rx -= 80;
        boolean paused = f != null && f.paused;
        ui.button("+", rx - 28, 10, 28, 30, Ui.NORMAL, true, false, () -> changeSpeed(+1));
        double sp = f != null ? f.speed : 1;
        ui.textCenter("×" + (sp == Math.rint(sp) ? String.valueOf((long) sp) : String.valueOf(sp)), rx - 28 - 22, 30, Ui.F_BOLD, Ui.TEXT);
        ui.button("−", rx - 28 - 44 - 28, 10, 28, 30, Ui.NORMAL, true, false, () -> changeSpeed(-1));
        rx -= 28 + 44 + 28 + 8;
        ui.button(paused ? "Resume" : "Pause", rx - 78, 10, 78, 30, paused ? Ui.PRIMARY : Ui.NORMAL, true, false, this::togglePause);
        rx -= 86;
        ui.button("Restart", rx - 78, 10, 78, 30, Ui.NORMAL, true, false, this::restart);
        // the tabs: one per cloud type
        String[] names = new String[types.size()];
        for (int k = 0; k < names.length; k++) names[k] = cap(types.get(k));
        ui.seg(names, types.indexOf(type), 12, 46, w - 24, 32, k -> pick(types.get(k)));
    }

    /** The five stages of the cloud on stage, its progress and its live numbers. */
    private void lifeStrip(Ui ui, double x, double y, double w, double h, Model.Frame f, Model.Cloud c) {
        ui.fill(x, y, w, h, 12, new Color(10, 14, 24, 240));
        ui.stroke(x, y, w, h, 12, new Color(120, 180, 255, 70), 1f);
        ui.text("LIFE CYCLE", x + 14, y + 21, Ui.F_SMALL.deriveFont(Font.BOLD), Ui.ACCENT);
        String head = f == null ? "" : c != null ? c.name + "   ·   cycle " + Math.max(1, f.showcaseCycle)
                : "the cloud is gone — the next " + type + " forms in a moment" + (f.showcaseCycle > 0 ? "   ·   after cycle " + f.showcaseCycle : "");
        ui.textFit(head, x + 96, y + 21, w - 110, Ui.F_BOLD, c != null ? Color.WHITE : Ui.AMBER);

        // (the boxes end 5 px above the "born … / gone …" line: with h - 32 - 34 their bottom edge ran through the top of that text)
        // (in a narrow window the live numbers give up 20 px so that the five stage titles fit their boxes)
        double liveW = Model.clamp(w * 0.27, 190, 300), sx = x + 14, sw = w - liveW - 40, sy = y + 32, boxH = h - 32 - 39;
        int cur = c != null ? Model.stageIndex(c.stage) : -1;
        double born = c != null && c.stageTimes.get("forming") != null ? c.stageTimes.get("forming") : f != null && c != null ? f.t - c.age : 0;
        double gap = 6, even = (sw - gap * 4) / 5;
        // the boxes are equal while every title fits an equal box; in a narrow window each is as wide as its own title needs
        // (plus an equal share of what is left), so that "4  Precipitating" and "5  Dissipating" are not cut
        final Font titleF = even < 112 ? Ui.F_BOLD : Ui.F_MEDB;
        double[] need = new double[5];
        double needSum = 0;
        boolean equal = true;
        for (int k = 0; k < 5; k++) {
            need[k] = ui.width((k + 1) + "  " + cap(Model.STAGES[k]), titleF) + 18;
            needSum += need[k];
            if (need[k] > even) equal = false;
        }
        final boolean byNeed = !equal && needSum <= sw - gap * 4;
        final double spare = byNeed ? (sw - gap * 4 - needSum) / 5 : 0;
        double bxNext = sx;
        for (int k = 0; k < 5; k++) {
            double bx = bxNext, bw = byNeed ? need[k] + spare : even;
            bxNext += bw + gap;
            Color sc = Renderer.STAGE_COL[k];
            Double at = c != null ? c.stageTimes.get(Model.STAGES[k]) : null;
            boolean now = k == cur, reached = at != null || k < cur, skipped = c != null && at == null && k < cur;
            ui.fill(bx, sy, bw, boxH, 9, now ? Renderer.alpha(sc, 70) : new Color(18, 24, 40, 240));
            ui.fill(bx, sy, bw, 5, 4, Renderer.alpha(sc, now ? 255 : reached && !skipped ? 200 : 70));
            ui.stroke(bx, sy, bw, boxH, 9, now ? Ui.ACCENT : new Color(255, 255, 255, 28), now ? 1.6f : 1f);
            Color ink = now ? Color.WHITE : reached && !skipped ? Ui.TEXT : Ui.FAINT;
            ui.textFit((k + 1) + "  " + cap(Model.STAGES[k]), bx + 9, sy + 23, bw - 14, titleF, ink);
            // every line has a short form for a box that is too narrow for the long one (it used to be cut: "began at 4…")
            final double room = bw - 14;
            String line;
            if (c == null) line = "";
            else if (skipped) line = ui.width("skipped — this cloud never did", Ui.F_SMALL) <= room ? "skipped — this cloud never did" : "skipped";
            else if (at != null) {
                // when the stage began (seconds after the cloud was born) and, once it is over, how long it lasted
                Double end = null;
                for (int j = k + 1; j < 5 && end == null; j++) end = c.stageTimes.get(Model.STAGES[j]);
                String len = end != null ? String.format("lasted %.0f s", end - at) : now ? String.format("%.0f s so far", f.t - at) : "";
                line = String.format("began at %.0f s", at - born) + (len.isEmpty() ? "" : " · " + len);
                if (!len.isEmpty() && ui.width(line, Ui.F_SMALL) > room) line = len;
            } else line = "not yet";
            if (boxH >= 46) ui.textFit(line, bx + 9, sy + 41, bw - 14, Ui.F_SMALL, now ? Ui.TEXT : Ui.DIM);
            if (now && boxH >= 60) ui.textFit(ui.width("← the cloud is here", Ui.F_SMALL) <= room ? "← the cloud is here" : "← here", bx + 9, sy + 57, room, Ui.F_SMALL, Ui.ACCENT);
        }
        // progress through the whole life
        double py = y + h - 22;
        ui.fill(sx, py, sw, 8, 8, new Color(255, 255, 255, 24));
        if (c != null) {
            double frac = Model.clamp(c.lifeFrac, 0, 1);
            ui.fill(sx, py, Math.max(8, sw * frac), 8, 8, Renderer.STAGE_COL[Math.max(0, cur)]);
            ui.g.setColor(Color.WHITE);
            ui.g.fill(new java.awt.geom.Ellipse2D.Double(sx + sw * frac - 5, py - 1, 10, 10));
            ui.text(String.format("born  ·  %.0f s old of about %.0f s", c.age, c.lifetime), sx, py - 5, Ui.F_SMALL, Ui.DIM);
            ui.textRight(String.format("gone  ·  %.0f %% of its life", frac * 100), sx + sw, py - 5, Ui.F_SMALL, Ui.DIM);
        }

        // live numbers
        double lx = x + w - liveW - 12, ly = y + 30, row = (h - 40) / 4;
        ui.g.setColor(new Color(255, 255, 255, 22));
        ui.g.fill(new Rectangle2D.Double(lx - 10, y + 12, 1, h - 24));
        meter(ui, "Water", c == null ? 0 : c.water, c == null ? "—" : String.format("%.2f", c.water), new Color(110, 180, 255), lx, ly, liveW);
        meter(ui, "Ice", c == null ? 0 : c.ice, c == null ? "—" : String.format("%.2f", c.ice), new Color(200, 230, 255), lx, ly + row, liveW);
        meter(ui, "Updraft", c == null ? 0 : Math.min(1, Math.abs(c.updraft) / 12), c == null ? "—" : String.format("%.1f m/s", c.updraft), new Color(255, 160, 84), lx, ly + 2 * row, liveW);
        // (only while the cloud is in its precipitating stage: afterwards the simulation releases no more particles, but its
        //  precip_rate stays on a small leftover value, and the meter went on saying "mixed 0.02" under a cloud that was gone)
        boolean pr = c != null && "precipitating".equals(c.stage) && c.precipRate > 0.005 && !"none".equals(c.precipType);
        meter(ui, "Precip", pr ? Math.min(1, c.precipRate) : 0, c == null ? "—" : pr ? c.precipType + String.format(" %.2f", c.precipRate) : (c.fair ? "none (fair)" : "none"),
                pr ? Renderer.precipColor(c.precipType) : new Color(120, 120, 130), lx, ly + 3 * row, liveW);
    }

    /**
     * v2.3 the lifetime tour: the ordered steps of this type's life as chips (done / here / to come), the text of the
     * step the cloud is at, its progress through life, and the live numbers that matter for a rotating storm.
     */
    private void tourStrip(Ui ui, double x, double y, double w, double h, Model.Frame f, Model.Cloud c, List<String[]> tour) {
        ui.fill(x, y, w, h, 12, new Color(10, 14, 24, 240));
        ui.stroke(x, y, w, h, 12, new Color(120, 180, 255, 70), 1f);
        ui.text("LIFETIME TOUR", x + 14, y + 21, Ui.F_SMALL.deriveFont(Font.BOLD), Ui.ACCENT);
        String step = f == null ? null : f.tourStep;
        if (step != null) lastStep = step;
        boolean pause = f != null && c == null;
        int cur = -1;
        for (int k = 0; k < tour.size(); k++) if (tour.get(k)[0].equals(pause ? null : lastStep)) cur = k;
        Model.Tornado tn = f != null && c != null ? f.tornadoOf(c.id) : null;
        if (tn == null && f != null && !f.tornadoes.isEmpty()) tn = f.tornadoes.get(0);
        String head = f == null ? "" : c != null ? c.name + "   ·   cycle " + Math.max(1, f.showcaseCycle) + (cur >= 0 ? "   ·   step " + (cur + 1) + " of " + tour.size() : "")
                : "the storm is gone — the next " + type + " forms in a moment" + (f.showcaseCycle > 0 ? "   ·   after cycle " + f.showcaseCycle : "");
        double hx = x + 118;
        if (tn != null) {
            // the tornado, while there is one: a red chip at the right of the header
            String tt = tn.onGround() ? String.format("TORNADO  EF%d  ·  %.0f m/s  ·  %s", tn.ef, tn.windMs, tn.stage.equals("roping") ? "roping out" : tn.stage)
                    : tn.stage.equals("roping") ? "TORNADO  lifting" : String.format("FUNNEL  %.0f %% down", Math.floor(tn.reach * 100));
            int tw = ui.width(tt, Ui.F_BOLD) + 22;
            double liveW0 = Model.clamp(w * 0.27, 210, 300);
            double tx = x + w - liveW0 - 30 - tw;
            ui.fill(tx, y + 7, tw, 20, 10, new Color(96, 30, 26, 240));
            ui.stroke(tx, y + 7, tw, 20, 10, new Color(255, 116, 92, 200), 1f);
            ui.text(tt, tx + 11, y + 21, Ui.F_BOLD, new Color(255, 232, 224));
            ui.textFit(head, hx, y + 21, tx - hx - 12, Ui.F_BOLD, c != null ? Color.WHITE : Ui.AMBER);
        } else ui.textFit(head, hx, y + 21, w - 130 - Model.clamp(w * 0.27, 210, 300), Ui.F_BOLD, c != null ? Color.WHITE : Ui.AMBER);

        double liveW = Model.clamp(w * 0.27, 210, 300), sx = x + 14, sw = w - liveW - 40, sy = y + 32;
        // ---- the steps (in a small window the step the storm is at gets the room for its title, the others show their number)
        final boolean tight = h < 140;
        int n = tour.size();
        double gap = 5, even = (sw - gap * (n - 1)) / n, chipH = tight ? 24 : 28;
        boolean narrow = even < 96 && cur >= 0;
        double curW = narrow ? Math.min(sw * 0.36, ui.width((cur + 1) + "  " + tour.get(cur)[1], Ui.F_BOLD) + 22) : even;
        double restW = narrow ? (sw - gap * (n - 1) - curW) / (n - 1) : even;
        // when every title fits the strip, each chip is as wide as its own title needs (plus an equal share of what is
        // left): with equal chips the longest one was cut ("3  Mature sup…") although the strip had room for all of them
        double[] need = new double[n];
        double needSum = 0;
        for (int k = 0; k < n; k++) { need[k] = ui.width((k + 1) + "  " + tour.get(k)[1], Ui.F_BOLD) + 22; needSum += need[k]; }
        final boolean roomy = !narrow && needSum <= sw - gap * (n - 1);
        final double spare = roomy ? (sw - gap * (n - 1) - needSum) / n : 0;
        double bxNext = sx;
        for (int k = 0; k < n; k++) {
            double bx = bxNext, bw = roomy ? need[k] + spare : narrow && k == cur ? curW : restW;
            bxNext += bw + gap;
            boolean now = k == cur, done = cur >= 0 && k < cur;
            Color sc = stepColor(tour.get(k)[0]);
            ui.fill(bx, sy, bw, chipH, 9, now ? Renderer.alpha(sc, 84) : new Color(18, 24, 40, 240));
            ui.fill(bx, sy, bw, 4, 4, Renderer.alpha(sc, now ? 255 : done ? 190 : 64));
            ui.stroke(bx, sy, bw, chipH, 9, now ? Ui.ACCENT : new Color(255, 255, 255, 28), now ? 1.6f : 1f);
            Color ink = now ? Color.WHITE : done ? Ui.TEXT : Ui.FAINT;
            if (narrow && !now && bw < 76) ui.textCenter(String.valueOf(k + 1), bx + bw / 2, sy + chipH - 8, Ui.F_BOLD, ink);
            else ui.textFit((k + 1) + "  " + tour.get(k)[1], bx + 8, sy + chipH - 8, bw - 12, now || bw >= 100 ? Ui.F_BOLD : Ui.F_NORM, ink);
            if (k < n - 1) {
                // a small arrow from one step to the next
                ui.g.setColor(new Color(255, 255, 255, done || now ? 110 : 40));
                java.awt.geom.Path2D.Double a = new java.awt.geom.Path2D.Double();
                a.moveTo(bx + bw + 1, sy + chipH / 2 - 3); a.lineTo(bx + bw + gap - 1, sy + chipH / 2); a.lineTo(bx + bw + 1, sy + chipH / 2 + 3);
                a.closePath();
                ui.g.fill(a);
            }
        }
        // ---- the text of the step the cloud is at
        final Font tf = tight ? Ui.F_SMALL : Ui.F_NORM;
        final double lh = tight ? 13 : 15;
        double ty = sy + chipH + (tight ? 13 : 16), py = y + h - 22;
        int maxLines = (int) Math.max(1, Math.floor((py - (tight ? 6 : 20) - ty) / lh) + 1);
        boolean statusLine = !tight;
        if (cur >= 0) {
            String[] st = tour.get(cur);
            List<String> lines = ui.wrap(st[2], tf, sw - 4);
            if (lines.size() > maxLines && statusLine) {
                // the step's text needs the line of the "N s old of about M s" status: the text wins (it was cut: "… travel a …")
                statusLine = false;
                maxLines = (int) Math.max(1, Math.floor((py - 6 - ty) / lh) + 1);
            }
            if (lines.size() > maxLines) {
                lines = new ArrayList<>(lines.subList(0, maxLines));
                lines.set(maxLines - 1, ui.fit(lines.get(maxLines - 1) + " …", tf, sw - 4));
            }
            double yy = ty;
            for (String l : lines) { ui.text(l, sx, yy, tf, Ui.TEXT); yy += lh; }
        } else {
            ui.text(f == null ? "" : pause ? "Every cycle runs the whole tour: the storm towers, starts to rotate, lowers a wall cloud and drops one tornado."
                    : "Waiting for the simulation to say which step this is…", sx, ty, Ui.F_NORM, Ui.DIM);
        }
        // ---- progress through the whole life
        ui.fill(sx, py, sw, 8, 8, new Color(255, 255, 255, 24));
        if (c != null) {
            double frac = Model.clamp(c.lifeFrac, 0, 1);
            ui.fill(sx, py, Math.max(8, sw * frac), 8, 8, cur >= 0 ? stepColor(tour.get(cur)[0]) : Renderer.STAGE_COL[Model.stageIndex(c.stage)]);
            ui.g.setColor(Color.WHITE);
            ui.g.fill(new java.awt.geom.Ellipse2D.Double(sx + sw * frac - 5, py - 1, 10, 10));
            if (statusLine) ui.textRight(String.format("%.0f s old of about %.0f s  ·  %s  ·  %.0f %% of its life", c.age, c.lifetime, c.stage, frac * 100), sx + sw, py - 5, Ui.F_SMALL, Ui.DIM);
        }

        // ---- live numbers of a rotating storm
        double lx = x + w - liveW - 12, ly = y + 30, row = (h - 40) / 4;
        ui.g.setColor(new Color(255, 255, 255, 22));
        ui.g.fill(new Rectangle2D.Double(lx - 10, y + 12, 1, h - 24));
        boolean sup = c != null && c.kind == Model.SUPERCELL;
        if (sup || c == null) {
            meter(ui, "Rotation", c == null ? 0 : c.rotation, c == null ? "—" : String.format("%.0f %%", c.rotation * 100), new Color(190, 150, 255), lx, ly, liveW);
            meter(ui, "Wall", c == null ? 0 : c.wall, c == null ? "—" : c.wall < 0.05 ? "not yet" : String.format("%.0f %% low", c.wall * 100), new Color(140, 150, 176), lx, ly + row, liveW);
        } else {
            meter(ui, "Water", c.water, String.format("%.2f", c.water), new Color(110, 180, 255), lx, ly, liveW);
            meter(ui, "Ice", c.ice, String.format("%.2f", c.ice), new Color(200, 230, 255), lx, ly + row, liveW);
        }
        meter(ui, "Updraft", c == null ? 0 : Math.min(1, Math.abs(c.updraft) / 12), c == null ? "—" : String.format("%.1f m/s", c.updraft), new Color(255, 160, 84), lx, ly + 2 * row, liveW);
        // (only while the cloud is in its precipitating stage: afterwards the simulation releases no more particles, but its
        //  precip_rate stays on a small leftover value, and the meter went on saying "mixed 0.02" under a cloud that was gone)
        boolean pr = c != null && "precipitating".equals(c.stage) && c.precipRate > 0.005 && !"none".equals(c.precipType);
        meter(ui, "Precip", pr ? Math.min(1, c.precipRate) : 0, c == null ? "—" : pr ? c.precipType + String.format(" %.2f", c.precipRate) : "none",
                pr ? Renderer.precipColor(c.precipType) : new Color(120, 120, 130), lx, ly + 3 * row, liveW);
    }

    /** A colour per tour step: it warms as the storm becomes dangerous and cools again as it dies. */
    private static Color stepColor(String id) {
        return switch (id) {
            case "towering" -> new Color(88, 206, 186);
            case "mesocyclone" -> new Color(170, 150, 255);
            case "mature" -> new Color(244, 204, 96);
            case "wall_cloud" -> new Color(250, 160, 84);
            case "funnel" -> new Color(255, 128, 96);
            case "tornado" -> new Color(255, 92, 80);
            case "rope_out" -> new Color(220, 130, 170);
            case "decay" -> new Color(150, 150, 190);
            default -> new Color(120, 180, 255);
        };
    }

    private static void meter(Ui ui, String label, double frac, String value, Color col, double x, double y, double w) {
        ui.text(label, x, y + 10, Ui.F_SMALL, Ui.DIM);
        double bx = x + 54, bw = Math.max(20, w - 54 - 96);
        ui.fill(bx, y + 2, bw, 9, 8, new Color(255, 255, 255, 24));
        if (frac > 0.005) ui.fill(bx, y + 2, Math.max(6, bw * Model.clamp(frac, 0, 1)), 9, 8, col);
        ui.textFit(value, bx + bw + 8, y + 11, 88, Ui.F_BOLD, Ui.TEXT);
    }

    /** The lesson of the selected type, from the simulation's hello. */
    private void lessonPanel(Ui ui, double x, double y, double w, double h) {
        ui.fill(x, y, w, h, 14, new Color(10, 14, 24, 240));
        ui.stroke(x, y, w, h, 14, new Color(120, 180, 255, 70), 1f);
        Map<String, String> l = lessons.get(type);
        double px = x + 16, pw = w - 32;
        int kind = Model.cloudKind(type);
        ui.text(Model.CLOUD_PREFIX[kind], px, y + 34, Ui.F_BIG, Ui.BLUE);
        String title = l != null && l.get("title") != null ? l.get("title") : cap(type);
        ui.textFit(title, px + 36, y + 34, pw - 36, Ui.F_BIG, Color.WHITE);
        if (l == null) {
            ui.text(lessonsRaw == null ? "Waiting for the simulation…" : "The simulation sent no lesson for this type.", px, y + 62, Ui.F_MED, Ui.DIM);
            return;
        }
        double ty = y + 54;
        String alt = l.getOrDefault("altitude", "");
        if (!alt.isEmpty()) {
            for (String s : ui.wrap("Altitude: " + alt, Ui.F_BOLD, pw)) { ui.text(s, px, ty, Ui.F_BOLD, Ui.ACCENT); ty += 16; }
            ty += 2;
        }
        // lay the five parts out; if they do not fit, use the smaller font, and scroll as the last resort
        double maxH = y + h - 14 - ty;
        Font f = Ui.F_MED;
        double lh = 18;
        List<String[]> rows = layout(ui, l, f, pw - 8);
        if (rows.size() * lh > maxH) { f = Ui.F_NORM; lh = 16; rows = layout(ui, l, f, pw - 8); }
        double total = rows.size() * lh, sc = total > maxH ? Math.min(scroll, total - maxH) : 0;
        scroll = sc;
        java.awt.Shape clip = ui.g.getClip();
        ui.g.clip(new Rectangle2D.Double(px - 2, ty - 2, pw + 4, maxH + 4));
        double yy = ty + 12 - sc;
        for (String[] r : rows) {
            if (yy > ty - 8 && yy < ty + maxH + lh) {
                if (r[0].equals("h")) ui.text(r[1], px, yy, Ui.F_SMALL.deriveFont(Font.BOLD), Ui.BLUE);
                else if (!r[1].isEmpty()) ui.text(r[1], px, yy, f, Ui.TEXT);
            }
            yy += lh;
        }
        ui.g.setClip(clip);
        if (total > maxH) {
            double th = Math.max(24, maxH * maxH / total), by = ty + (maxH - th) * (sc / (total - maxH));
            ui.fill(px + pw - 2, ty, 4, maxH, 4, new Color(255, 255, 255, 18));
            ui.fill(px + pw - 2, by, 4, th, 4, new Color(255, 255, 255, 110));
        }
    }

    /** Rows of the lesson text: {"h", heading}, {"t", line} and {"t", ""} as a blank line between parts. */
    private static List<String[]> layout(Ui ui, Map<String, String> l, Font f, double w) {
        List<String[]> rows = new ArrayList<>();
        for (int k = 0; k < PARTS.length; k++) {
            String s = l.get(PARTS[k]);
            if (s == null || s.isEmpty()) continue;
            if (!rows.isEmpty()) rows.add(new String[]{"t", ""});
            rows.add(new String[]{"h", PART_TITLES[k]});
            for (String line : ui.wrap(s, f, w)) rows.add(new String[]{"t", line});
        }
        return rows;
    }
}
