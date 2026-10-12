package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;

/**
 * The "World Simulation" mode: the whole Earth on an equirectangular map, driven by the
 * {@code world} frames of protocol v2.3. The map (drawn by {@link WorldPainter} into the game
 * panel's buffer through {@link ViewerPanel.Scene}) shows one data layer at a time, the night side,
 * the pressure systems and a dot for every preset location; the top bar, the time strip, the legend
 * and the panel on the right are this screen.
 * <p>
 * A click on a dot, or on a country / US state that {@code hello.world_map.regions} lists, opens
 * that location's card; <b>Open this location</b> (Enter, or a double click on the dot) leaves the
 * world map and starts a new sandbox world with {@code --preset <id> --month <m> --hour <h>} — the
 * season and the local time the map was showing there. A region without a preset says so and offers
 * the nearest location.
 * <p>
 * Every figure on screen comes from the simulation ({@code hello.world_map}, the frames) or, for
 * the twelve-month curve, from {@code assets/world/presets.json}, which is generated from the
 * simulation's own preset table.
 */
final class WorldScreen extends Screen implements ViewerPanel.Scene {
    private static final double[] SPEEDS = {0.25, 0.5, 1, 2, 4, 8, 16, 32, 64};
    private static final Color BG = new Color(7, 11, 20);
    private static final double TOP = 54, STRIP = 56;
    private static final String[] SHORT_LAYERS = {"Temp", "Clouds", "Rain", "Wind", "Pressure", "Snow", "Map"};

    /** What is under a point of the map. */
    static final class Pick {
        /** The region to highlight: the one that opens {@link #loc}, else the most specific one; null over the sea. */
        WorldMap.Region region;
        /** The location a click there opens, or null. */
        WorldModel.Loc loc;
        String label = "";
    }

    private final AppShell app;
    private final GameSession gs;
    final WorldMap map;
    final WorldPainter painter;

    /** Still pictures (snapshots): nothing eases, everything is drawn settled. */
    boolean still;
    boolean help;
    /** The selected location ("" = none). */
    String selId = "";
    /** The region drawn as selected: the selected location's own, or the one without a preset that was clicked. */
    WorldMap.Region selRegion;
    /** A region without a preset was clicked: its card is shown ({@link #selRegion}, the click point). */
    boolean noPreset;
    String noPresetLabel = "";
    double clickLon, clickLat;
    // what the pointer is on (worked out with every frame)
    String hoverId = "";
    Pick hover;
    /** Snapshots and tests: where the pointer is; null = the real one. */
    int[] pointer;
    /** The launch the last "Open this location" asked for: preset id, month, local hour (tests). */
    String openedId;
    double openedMonth = Double.NaN, openedHour = Double.NaN;

    private boolean dragging, dragMoved;
    private int dragButton, dragX, dragY, pressX, pressY;
    private String lastClickId = "";
    private long lastClickAt;
    private double listScroll;
    private Rectangle2D.Double legendRect = new Rectangle2D.Double(), panelRect = new Rectangle2D.Double();
    private boolean handCursor;
    private BufferedImage legendBar;
    private int legendBarLayer = -1;
    /** How long the last map frame took to draw, ms (the bench reads it). */
    double lastPaintMs;
    /** Diagnostics (the bench): nanoseconds spent preparing, composing the coloured map, and drawing over it. */
    long profPrep, profMap, profOver;

    WorldScreen(AppShell app, GameSession gs) {
        this.app = app;
        this.gs = gs;
        map = WorldMap.get(app.root);
        painter = new WorldPainter(map);
    }

    @Override boolean dims() { return false; }

    // ------------------------------------------------------------------ model access

    WorldModel model() {
        synchronized (gs.state) { return gs.state.world; }
    }

    WorldModel.Frame frame() {
        synchronized (gs.state) { return gs.state.world == null ? null : gs.state.world.cur; }
    }

    private WorldModel.Loc sel(WorldModel m) { return m == null || selId.isEmpty() ? null : m.loc(selId); }

    // ------------------------------------------------------------------ actions

    private void send(String json) { gs.source.send(json); }

    void togglePause() {
        WorldModel.Frame f = frame();
        send(f != null && f.paused ? "{\"cmd\":\"resume\"}" : "{\"cmd\":\"pause\"}");
    }

    void changeSpeed(int dir) {
        WorldModel.Frame f = frame();
        double cur = f != null ? f.speed : 1;
        int idx = 2;
        double best = 1e9;
        for (int i = 0; i < SPEEDS.length; i++) if (Math.abs(SPEEDS[i] - cur) < best) { best = Math.abs(SPEEDS[i] - cur); idx = i; }
        idx = Math.max(0, Math.min(SPEEDS.length - 1, idx + dir));
        send("{\"cmd\":\"speed\",\"value\":" + SPEEDS[idx] + "}");
    }

    /** Jump the calendar by whole months (the fields glide there). */
    void jumpSeason(double months) {
        WorldModel.Frame f = frame();
        if (f == null) return;
        setMonth(f.month + months);
    }

    void setMonth(double month) {
        double m = ((month - 1) % 12 + 12) % 12 + 1;
        send(String.format(Locale.ROOT, "{\"cmd\":\"season\",\"month\":%.3f}", m));
    }

    void jumpHour(double hours) {
        WorldModel.Frame f = frame();
        if (f == null) return;
        double h = ((f.utcHour + hours) % 24 + 24) % 24;
        send(String.format(Locale.ROOT, "{\"cmd\":\"hour\",\"value\":%.3f}", h));
    }

    void setLayer(int k) { painter.layer = Math.max(0, Math.min(WorldPainter.LAYERS.length - 1, k)); }

    void clearSelection() { selId = ""; selRegion = null; noPreset = false; }

    /** Show a location's card. {@code region} is the region that was clicked, or null (a dot, the list, Tab). */
    void select(WorldModel.Loc l, WorldMap.Region region) {
        if (l == null) return;
        selId = l.id;
        noPreset = false;
        selRegion = region != null ? region : map.region(l.region);
        listScroll = 0;
    }

    /** Select by preset id (snapshots, tests). True when the simulation knows it. */
    boolean select(String id) {
        WorldModel m = model();
        WorldModel.Loc l = m == null ? null : m.loc(id);
        if (l == null) return false;
        select(l, null);
        return true;
    }

    private void cycle(int dir) {
        WorldModel m = model();
        if (m == null || m.locations.isEmpty()) return;
        int n = m.locations.size(), i = -1;
        for (int k = 0; k < n; k++) if (m.locations.get(k).id.equals(selId)) i = k;
        WorldModel.Loc l = m.locations.get(Math.floorMod(i < 0 ? (dir > 0 ? 0 : n - 1) : i + dir, n));
        select(l, null);
        painter.centerOn(l.lon, l.lat, 1);
    }

    /** What a click at this place means. */
    Pick pick(WorldModel m, double lon, double lat) {
        Pick p = new Pick();
        if (lat > 90 || lat < -90) return p;
        WorldMap.Region st = map.stateAt(lon, lat), co = map.countryAt(lon, lat);
        WorldModel.Loc ls = st == null || m == null ? null : m.locOfRegion(st.code), lc = co == null || m == null ? null : m.locOfRegion(co.code);
        if (ls != null) { p.region = st; p.loc = ls; }
        else if (lc != null) { p.region = co; p.loc = lc; }
        else p.region = st != null ? st : co;
        if (p.region != null) p.label = p.region == st && co != null ? st.name + ", " + co.name : p.region.name;
        return p;
    }

    /** A click on the map at a geographic point (the mouse ends up here; snapshots and tests call it directly). */
    void clickGeo(double lon, double lat) {
        WorldModel m = model();
        if (m == null) return;
        Pick p = pick(m, lon, lat);
        clickLon = WorldMap.wrapLon(lon); clickLat = lat;
        if (p.loc != null) select(p.loc, p.region);
        else if (p.region != null) { selId = ""; selRegion = p.region; noPreset = true; noPresetLabel = p.label; }
        else clearSelection();
    }

    /**
     * A click at a place given in degrees, as the mouse makes it there (snapshots: {@code --click lon,lat}): a dot
     * within reach of that point wins, like in {@link #clickAt}; otherwise the region under it. Without this a
     * click exactly on a coastal dot that the 110m map leaves in the sea (McMurdo) opened nothing.
     */
    void clickPlace(double lon, double lat) {
        WorldModel m = model();
        if (m == null) return;
        if (painter.showDots) {
            double px = painter.x(lon), py = painter.y(lat), best = 11 * 11;
            WorldModel.Loc hit = null;
            for (WorldModel.Loc l : m.locations) {
                double dx = painter.x(l.lon) - px, dy = painter.y(l.lat) - py, d = dx * dx + dy * dy;
                if (d <= best) { best = d; hit = l; }
            }
            if (hit != null) { lastClickId = hit.id; select(hit, null); return; }
        }
        clickGeo(lon, lat);
    }

    private void clickAt(int x, int y) {
        WorldModel m = model();
        if (m == null || !painter.onMap(x, y)) { if (painter.rect.contains(x, y)) clearSelection(); return; }
        int di = painter.showDots ? painter.dotAt(x, y, 11) : -1;
        if (di >= 0 && di < m.locations.size()) {
            WorldModel.Loc l = m.locations.get(di);
            long now = System.currentTimeMillis();
            boolean dbl = l.id.equals(lastClickId) && now - lastClickAt < 450;
            lastClickId = l.id;
            lastClickAt = now;
            select(l, null);
            if (dbl) open(l);
            return;
        }
        lastClickId = "";
        clickGeo(painter.lonAt(x), painter.latAt(y));
    }

    /** Leave the map and start a sandbox world of this location, in the season and at the local time shown. */
    void open(WorldModel.Loc l) {
        if (l == null) return;
        WorldModel.Frame f = frame();
        WorldModel.Now n = f == null ? null : f.now.get(l.id);
        double month = n != null && !Double.isNaN(n.month) ? n.month : f != null ? f.month : 4;
        double hour = n != null && !Double.isNaN(n.localHour) ? n.localHour : f != null ? ((f.utcHour + l.lon / 15) % 24 + 24) % 24 : 12;
        openedId = l.id; openedMonth = month; openedHour = hour;
        app.openWorldLocation(l.id, l.name, month, hour);
    }

    private void openSelected() {
        WorldModel m = model();
        WorldModel.Loc l = sel(m);
        if (l != null) { open(l); return; }
        if (noPreset && m != null) {
            WorldModel.Loc near = m.nearest(clickLat, clickLon);
            if (near != null) { select(near, null); painter.centerOn(near.lon, near.lat, 1); }
        }
    }

    // ------------------------------------------------------------------ input

    @Override void key(KeyEvent e) {
        if (help) { help = false; return; }
        int c = e.getKeyCode();
        switch (c) {
            case KeyEvent.VK_ESCAPE -> app.leaveNow();
            case KeyEvent.VK_H, KeyEvent.VK_F1 -> help = true;
            case KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4, KeyEvent.VK_5, KeyEvent.VK_6, KeyEvent.VK_7 -> setLayer(c - KeyEvent.VK_1);
            case KeyEvent.VK_0 -> setLayer(WorldPainter.PLAIN);
            case KeyEvent.VK_L -> setLayer(Math.floorMod(painter.layer + (e.isShiftDown() ? -1 : 1), WorldPainter.LAYERS.length));
            case KeyEvent.VK_LEFT -> painter.nudge(-0.16, 0);
            case KeyEvent.VK_RIGHT -> painter.nudge(0.16, 0);
            case KeyEvent.VK_UP -> painter.nudge(0, -0.16);
            case KeyEvent.VK_DOWN -> painter.nudge(0, 0.16);
            case KeyEvent.VK_PAGE_UP -> painter.zoomAt(painter.rect.getCenterX(), painter.rect.getCenterY(), 1.4);
            case KeyEvent.VK_PAGE_DOWN -> painter.zoomAt(painter.rect.getCenterX(), painter.rect.getCenterY(), 1 / 1.4);
            case KeyEvent.VK_HOME -> painter.home();
            case KeyEvent.VK_SPACE, KeyEvent.VK_P -> togglePause();
            case KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD -> changeSpeed(+1);
            case KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> changeSpeed(-1);
            case KeyEvent.VK_CLOSE_BRACKET -> jumpSeason(+3);
            case KeyEvent.VK_OPEN_BRACKET -> jumpSeason(-3);
            case KeyEvent.VK_PERIOD -> jumpHour(+1);
            case KeyEvent.VK_COMMA -> jumpHour(-1);
            case KeyEvent.VK_ENTER -> openSelected();
            case KeyEvent.VK_TAB -> cycle(e.isShiftDown() ? -1 : 1);
            case KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE -> clearSelection();
            case KeyEvent.VK_G -> painter.showGraticule = !painter.showGraticule;
            case KeyEvent.VK_B -> painter.showBorders = !painter.showBorders;
            case KeyEvent.VK_N -> painter.showNight = !painter.showNight;
            case KeyEvent.VK_D -> painter.showDots = !painter.showDots;
            case KeyEvent.VK_Y -> painter.showSystems = !painter.showSystems;
            default -> { }
        }
    }

    @Override void wheel(int x, int y, double rotation) {
        if (help) return;
        if (panelRect.contains(x, y)) { listScroll = Math.max(0, listScroll + rotation * 40); return; }
        if (painter.rect.contains(x, y)) painter.zoomAt(x, y, Math.pow(1.22, -rotation));
    }

    @Override void mouse(int type, int x, int y, int button) {
        switch (type) {
            case MouseEvent.MOUSE_PRESSED -> {
                if (help) { help = false; return; }
                dragging = painter.rect.contains(x, y) && !legendRect.contains(x, y);
                dragMoved = false;
                dragButton = button;
                dragX = pressX = x; dragY = pressY = y;
            }
            case MouseEvent.MOUSE_DRAGGED -> {
                if (!dragging) return;
                if (Math.abs(x - pressX) + Math.abs(y - pressY) > 4) dragMoved = true;
                if (dragMoved) painter.dragBy(x - dragX, y - dragY);
                dragX = x; dragY = y;
            }
            case MouseEvent.MOUSE_RELEASED -> {
                boolean click = dragging && !dragMoved && dragButton == MouseEvent.BUTTON1;
                dragging = false;
                if (click) clickAt(x, y);
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ layout

    private static double panelW(int w) { return Model.clamp(w * 0.215, 300, 372); }

    /** Place the map for a window of this size (also called before a snapshot sets its view). */
    void layout(int w, int h) {
        double pw = panelW(w);
        painter.still = still;
        painter.setRect(12, TOP, Math.max(200, w - pw - 36), Math.max(120, h - TOP - 12 - STRIP - 8));
        panelRect = new Rectangle2D.Double(w - pw - 12, TOP, pw, h - TOP - 12);
        // the legend covers a corner of the map: the dot labels keep out of it
        painter.avoid = painter.layer == WorldPainter.PLAIN ? null : legendBox(painter.rect);
    }

    private int mx() { return pointer != null ? pointer[0] : app.ui.mx; }

    private int my() { return pointer != null ? pointer[1] : app.ui.my; }

    /** Put the (pretend) pointer on a place of the map. */
    void pointAt(double lon, double lat) { pointer = new int[]{(int) Math.round(painter.x(lon)), (int) Math.round(painter.y(lat))}; }

    private void computeHover(WorldModel m) {
        hoverId = "";
        hover = null;
        int x = mx(), y = my();
        if (help || m == null || x < 0 || (dragging && dragMoved) || app.top() != this || !painter.onMap(x, y) || legendRect.contains(x, y)) return;
        int di = painter.showDots ? painter.dotAt(x, y, 11) : -1;
        if (di >= 0 && di < m.locations.size()) { hoverId = m.locations.get(di).id; return; }
        Pick p = pick(m, painter.lonAt(x), painter.latAt(y));
        if (p.region != null) hover = p;
    }

    // ------------------------------------------------------------------ the map (ViewerPanel.Scene)

    @Override public void paintScene(BufferedImage buf, double scale, int w, int h, double dt) {
        long t0 = System.nanoTime();
        Graphics2D g = buf.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.scale(scale, scale);
            WorldModel m = model();
            WorldModel.Frame f = frame();
            g.setColor(BG);
            if (m == null || f == null || map.problem != null) g.fillRect(0, 0, w + 1, h + 1);
            else {
                // the map covers its own rectangle: only the frame around it needs clearing
                layout(w, h);
                java.awt.geom.Rectangle2D.Double r = painter.rect;
                g.fill(new java.awt.geom.Rectangle2D.Double(0, 0, w + 1, r.y));
                g.fill(new java.awt.geom.Rectangle2D.Double(0, r.y + r.height, w + 1, h + 1 - r.y - r.height));
                g.fill(new java.awt.geom.Rectangle2D.Double(0, r.y, r.x, r.height));
                g.fill(new java.awt.geom.Rectangle2D.Double(r.x + r.width, r.y, w + 1 - r.x - r.width, r.height));
            }
            if (m == null || f == null) {
                String err = gs.simError();
                g.setFont(Ui.F_BIG);
                g.setColor(new Color(236, 242, 250, 220));
                String msg = err != null ? "" : "Starting the world simulation…";
                g.drawString(msg, (float) ((w - g.getFontMetrics().stringWidth(msg)) / 2.0), h / 2f);
                return;
            }
            layout(w, h);
            painter.update(still ? -1 : dt);
            painter.placeDots(m);
            computeHover(m);
            long t1 = System.nanoTime();
            painter.paintMap(g, buf, scale, f, still ? 0 : dt);
            long t2 = System.nanoTime();
            WorldMap.Region hr = hover != null ? hover.region : null;
            // a hovered dot lights its own region up too
            if (hr == null && !hoverId.isEmpty() && m.loc(hoverId) != null) hr = map.region(m.loc(hoverId).region);
            painter.paintOverlays(g, m, f, hr, hover == null || hover.loc != null, selRegion, hoverId, selId);
            profPrep += t1 - t0; profMap += t2 - t1; profOver += System.nanoTime() - t2;
        } finally {
            g.dispose();
            lastPaintMs = (System.nanoTime() - t0) / 1e6;
        }
    }

    // ------------------------------------------------------------------ chrome

    @Override void paint(Ui ui, int w, int h) {
        WorldModel m = model();
        WorldModel.Frame f = frame();
        topBar(ui, w, f);
        if (m == null || f == null) return;
        Rectangle2D.Double r = painter.rect;
        legend(ui, r);
        timeStrip(ui, r.x, r.y + r.height + 8, r.width, STRIP, f);
        Rectangle2D.Double p = panelRect;
        ui.fill(p.x, p.y, p.width, p.height, 14, new Color(10, 14, 24, 236));
        ui.stroke(p.x, p.y, p.width, p.height, 14, new Color(120, 180, 255, 70), 1f);
        WorldModel.Loc l = sel(m);
        if (l != null) card(ui, p, m, f, l);
        else if (noPreset && selRegion != null) noPresetCard(ui, p, m, f);
        else overview(ui, p, m, f);
        tooltip(ui, m, f, w);
        boolean hand = !hoverId.isEmpty() || (hover != null && hover.loc != null);
        if (hand != handCursor && app.panel != null && !still) {
            handCursor = hand;
            app.panel.setCursor(Cursor.getPredefinedCursor(hand ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        }
        if (still && pointer != null && !help) drawPointer(ui.g, pointer[0], pointer[1], hand);
        if (help) helpOverlay(ui, w, h);
    }

    private void topBar(Ui ui, int w, WorldModel.Frame f) {
        ui.text("WORLD SIMULATION", 16, 33, Ui.F_HEAD, Color.WHITE);
        double x = 16 + ui.width("WORLD SIMULATION", Ui.F_HEAD) + 20;
        boolean narrow = w < 1340;
        double rx = w - 12;
        ui.button("Menu", rx - 70, 12, 70, 30, Ui.NORMAL, true, false, app::leaveNow);
        rx -= 80;
        ui.button("?", rx - 30, 12, 30, 30, help ? Ui.PRIMARY : Ui.NORMAL, true, false, () -> help = !help);
        rx -= 40;
        if (w >= 1500) {
            ui.button("Whole world", rx - 104, 12, 104, 30, Ui.GHOST, painter.zoomFactor() > 1.02, false, painter::home);
            rx -= 114;
        }
        double sw = Math.min(narrow ? 470 : 640, rx - x - 8);
        ui.seg(narrow ? SHORT_LAYERS : WorldPainter.LAYERS, painter.layer, x, 12, sw, 30, this::setLayer);
    }

    private static String trim(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(Math.round(d * 100) / 100.0); }

    /** Date, clock, the twelve months (click one to go there) and the time controls, under the map. */
    private void timeStrip(Ui ui, double x, double y, double w, double hh, WorldModel.Frame f) {
        ui.fill(x, y, w, hh, 12, new Color(10, 14, 24, 236));
        ui.stroke(x, y, w, hh, 12, new Color(120, 180, 255, 60), 1f);
        boolean compact = w < 940;
        double dateW = compact ? 148 : 200;
        ui.textFit("Day " + (f.day + 1) + "  ·  " + WorldModel.monthText(f.month), x + 14, y + 23, dateW - 14, Ui.F_HEAD, Color.WHITE);
        ui.textFit(WorldModel.clock(f.utcHour) + " UTC  ·  " + (compact ? "" : "N ") + WorldModel.season(f.month, 45) + (compact ? " (N)" : ", S " + WorldModel.season(f.month, -45)),
                x + 14, y + 42, dateW - 14, Ui.F_SMALL, Ui.DIM);
        double by = y + (hh - 30) / 2, rx = x + w - 12;
        ui.button("+", rx - 28, by, 28, 30, Ui.NORMAL, f.speed < 64, false, () -> changeSpeed(+1));
        ui.textCenter("×" + trim(f.speed), rx - 28 - 23, by + 20, Ui.F_BOLD, Ui.TEXT);
        ui.button("−", rx - 28 - 46 - 28, by, 28, 30, Ui.NORMAL, f.speed > 0.25, false, () -> changeSpeed(-1));
        rx -= 28 + 46 + 28 + 8;
        double pwid = compact ? 66 : 78;
        ui.button(f.paused ? "Resume" : "Pause", rx - pwid, by, pwid, 30, f.paused ? Ui.PRIMARY : Ui.NORMAL, true, false, this::togglePause);
        rx -= pwid + 10;
        double hw = compact ? 38 : 50, sw = compact ? 38 : 58;
        ui.button(compact ? "+h" : "+1 h", rx - hw, by, hw, 30, Ui.NORMAL, true, false, () -> jumpHour(+1));
        ui.button(compact ? "−h" : "−1 h", rx - hw * 2 - 4, by, hw, 30, Ui.NORMAL, true, false, () -> jumpHour(-1));
        rx -= hw * 2 + 4 + 10;
        ui.button(compact ? "+3" : "+3 mo", rx - sw, by, sw, 30, Ui.NORMAL, true, false, () -> jumpSeason(+3));
        ui.button(compact ? "−3" : "−3 mo", rx - sw * 2 - 4, by, sw, 30, Ui.NORMAL, true, false, () -> jumpSeason(-3));
        rx -= sw * 2 + 4 + 12;
        // the year: twelve months, the marker where the calendar is
        double sx = x + dateW + 8, mw = rx - sx;
        if (mw < 120) return;
        double cw = mw / 12, sy = y + 12, sh = hh - 24;
        ui.fill(sx, sy, mw, sh, 9, new Color(20, 26, 42, 240));
        double mpos = ((f.month - 1) % 12 + 12) % 12;
        int curM = (int) mpos;
        for (int k = 0; k < 12; k++) {
            double cx = sx + k * cw;
            boolean cur = k == curM, hov = ui.hover(cx, sy, cw, sh);
            if (cur) ui.fill(cx + 1.5, sy + 1.5, cw - 3, sh - 3, 7, new Color(58, 110, 200, 235));
            else if (hov) ui.fill(cx + 1.5, sy + 1.5, cw - 3, sh - 3, 7, new Color(255, 255, 255, 28));
            String nm = WorldModel.MONTHS[k];
            String lab = cw >= 40 ? nm.substring(0, 3) : nm.substring(0, 1);
            ui.textCenter(lab, cx + cw / 2, sy + sh / 2 + 4.5, cur ? Ui.F_BOLD : Ui.F_NORM, cur ? Color.WHITE : Ui.TEXT);
            final int idx = k;
            ui.hit(cx, sy, cw, sh, () -> setMonth(idx + 1.5));
        }
        ui.stroke(sx, sy, mw, sh, 9, new Color(255, 255, 255, 30), 1f);
        double px = sx + mpos / 12 * mw;
        ui.g.setColor(Ui.ACCENT);
        ui.g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        ui.g.draw(new Line2D.Double(px, sy - 2, px, sy + sh + 2));
        Path2D.Double tri = new Path2D.Double();
        tri.moveTo(px - 4.5, sy - 6); tri.lineTo(px + 4.5, sy - 6); tri.lineTo(px, sy);
        tri.closePath();
        ui.g.fill(tri);
    }

    /** Where the legend sits: the map's lower left corner. */
    private static Rectangle2D.Double legendBox(Rectangle2D.Double r) {
        double lw = Math.min(292, r.width - 20), lh = 56;
        return new Rectangle2D.Double(r.x + 10, r.y + r.height - lh - 10, lw, lh);
    }

    /** The layer's colour bar with its unit, on the map's lower left corner. */
    private void legend(Ui ui, Rectangle2D.Double r) {
        int layer = painter.layer;
        String title, unit, note = "";
        double[] ticks;
        String[] labels;
        switch (layer) {
            case WorldPainter.TEMP -> { title = "Temperature"; unit = "°C"; ticks = new double[]{-40, -20, 0, 20, 40}; labels = new String[]{"−40", "−20", "0", "20", "40"}; }
            case WorldPainter.CLOUD -> { title = "Cloud cover"; unit = "%"; ticks = new double[]{0, 0.5, 1}; labels = new String[]{"0", "50", "100"}; }
            case WorldPainter.PRECIP -> { title = "Precipitation"; unit = "intensity"; ticks = new double[]{0.1, 0.5, 0.92}; labels = new String[]{"light", "moderate", "heavy"}; note = "grey = cloud"; }
            case WorldPainter.WIND -> { title = "Wind speed"; unit = "m/s"; ticks = new double[]{0, 8, 16, 24, 32}; labels = new String[]{"0", "8", "16", "24", "32"}; note = "streaks follow the wind"; }
            case WorldPainter.PRESSURE -> { title = "Sea-level pressure"; unit = "hPa"; ticks = new double[]{980, 1000, 1020, 1040}; labels = new String[]{"980", "1000", "1020", "1040"}; note = "isobars every 4 hPa"; }
            case WorldPainter.SNOW -> { title = "Snow and ice cover"; unit = "%"; ticks = new double[]{0, 0.5, 1}; labels = new String[]{"0", "50", "100"}; }
            default -> { legendRect = new Rectangle2D.Double(); return; }
        }
        legendRect = legendBox(r);
        double lw = legendRect.width, lh = legendRect.height, lx = legendRect.x, ly = legendRect.y;
        ui.fill(lx, ly, lw, lh, 10, new Color(8, 12, 22, 222));
        ui.stroke(lx, ly, lw, lh, 10, new Color(255, 255, 255, 40), 1f);
        ui.text(title, lx + 10, ly + 16, Ui.F_BOLD, Color.WHITE);
        double tw = ui.width(title, Ui.F_BOLD);
        ui.text(unit, lx + 14 + tw, ly + 16, Ui.F_SMALL, Ui.ACCENT);
        if (!note.isEmpty() && lw > 250) ui.textRight(note, lx + lw - 10, ly + 16, Ui.F_SMALL, Ui.FAINT);
        double bx = lx + 12, bw = lw - 24, bby = ly + 23, bh = 10;
        java.awt.Shape clip = ui.g.getClip();
        ui.g.clip(new java.awt.geom.RoundRectangle2D.Double(bx, bby, bw, bh, 6, 6));
        // the colour bar is one small picture per layer, stretched to the bar
        if (legendBar == null || legendBarLayer != layer) {
            legendBar = new BufferedImage(256, 1, BufferedImage.TYPE_INT_RGB);
            for (int k = 0; k < 256; k++) {
                int c = WorldPainter.lutArgb(layer, k * 2), a = c >>> 24;
                int cr = 44 + ((((c >> 16) & 255) - 44) * a) / 255, cg = 56 + ((((c >> 8) & 255) - 56) * a) / 255, cb = 74 + (((c & 255) - 74) * a) / 255;
                legendBar.setRGB(k, 0, (cr << 16) | (cg << 8) | cb);
            }
            legendBarLayer = layer;
        }
        Object oh = ui.g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        ui.g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        ui.g.drawImage(legendBar, new java.awt.geom.AffineTransform(bw / 256.0, 0, 0, bh, bx, bby), null);
        if (oh != null) ui.g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oh);
        ui.g.setClip(clip);
        for (int k = 0; k < ticks.length; k++) {
            double fx = (ticks[k] - WorldPainter.LO[layer]) / (WorldPainter.HI[layer] - WorldPainter.LO[layer]), tx = bx + fx * bw;
            ui.g.setColor(new Color(255, 255, 255, 170));
            ui.g.fill(new Rectangle2D.Double(tx - 0.5, bby + bh, 1, 3));
            double half = ui.width(labels[k], Ui.F_SMALL) / 2.0;
            ui.text(labels[k], Model.clamp(tx - half, bx - 2, bx + bw + 2 - 2 * half), ly + lh - 7, Ui.F_SMALL, Ui.TEXT);
        }
    }

    // ---- the panel: the world now, a location, a region without a preset

    private static String cap(String s) { return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    /** Is it night at this location? From the subsolar point, like the night side of the map (the clock alone is wrong in the polar night and day). */
    private static boolean nightAt(WorldModel.Frame f, WorldModel.Loc l) { return l != null && WorldPainter.nightAt(f, l.lat, l.lon); }

    private static String kindText(WorldModel.Loc l) {
        String k = switch (l.kind) { case "state" -> "US STATE"; case "country" -> "COUNTRY"; default -> "PLACE"; };
        return l.region.isEmpty() ? k : k + "  ·  " + l.region;
    }

    private static String coords(double lat, double lon) {
        return String.format("%.2f°%s  %.2f°%s", Math.abs(lat), lat >= 0 ? "N" : "S", Math.abs(lon), lon >= 0 ? "E" : "W");
    }

    private void overview(Ui ui, Rectangle2D.Double p, WorldModel m, WorldModel.Frame f) {
        double x = p.x + 16, w = p.width - 32, y = p.y;
        ui.text("THE WORLD NOW", x, y + 24, Ui.F_SMALL.deriveFont(Font.BOLD), Ui.ACCENT);
        ui.textRight(gs.source.describe().contains("fake") ? "built-in fake" : "", p.x + p.width - 16, y + 24, Ui.F_SMALL, Ui.FAINT);
        String[][] cells = {{"Mean temperature", Double.isNaN(f.meanTempC) ? "—" : String.format("%.1f °C", f.meanTempC)},
                {"Cloud cover", Double.isNaN(f.cloudCover) ? "—" : String.format("%.0f %%", f.cloudCover * 100)}, {"Storms", String.valueOf(f.storms)}};
        double cw = w / 3, cy = y + 34;
        ui.fill(x - 4, cy, w + 8, 50, 10, new Color(18, 26, 44, 240));
        for (int k = 0; k < 3; k++) {
            // a narrow panel (1280 px windows and smaller) has no room for the long caption: shorten it rather than cut it off
            String cap0 = k == 0 && ui.width(cells[k][0], Ui.F_SMALL) > cw - 6 ? "Mean temp." : cells[k][0];
            ui.textFit(cap0, x + 4 + k * cw, cy + 18, cw - 6, Ui.F_SMALL, Ui.DIM);
            ui.textFit(cells[k][1], x + 4 + k * cw, cy + 38, cw - 6, Ui.F_MEDB, k == 0 && !Double.isNaN(f.meanTempC) ? WorldPainter.color(WorldPainter.TEMP, f.meanTempC).brighter() : Color.WHITE);
        }
        y = cy + 72;
        ui.text("LOCATIONS", x, y, Ui.F_SMALL.deriveFont(Font.BOLD), Ui.ACCENT);
        ui.textRight(m.locations.size() + " presets", p.x + p.width - 16, y, Ui.F_SMALL, Ui.FAINT);
        double ly = y + 10, lh = p.y + p.height - 46 - ly, rowH = 38;
        double content = m.locations.size() * rowH;
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, content - lh)));
        boolean bar = content > lh + 0.5;
        double rw = bar ? w - 4 : w + 8;
        java.awt.Shape clip = ui.g.getClip();
        ui.g.clip(new Rectangle2D.Double(p.x + 4, ly, p.width - 8, lh));
        for (int k = 0; k < m.locations.size(); k++) {
            WorldModel.Loc l = m.locations.get(k);
            double ry = ly + k * rowH - listScroll;
            if (ry + rowH < ly || ry > ly + lh) continue;
            double hy0 = Math.max(ry, ly), hy1 = Math.min(ry + rowH - 2, ly + lh);
            boolean hov = hy1 > hy0 && ui.hover(x - 6, hy0, rw, hy1 - hy0), dotHov = l.id.equals(hoverId);
            if (hov || dotHov) ui.fill(x - 6, ry, rw, rowH - 2, 9, new Color(44, 58, 92, 235));
            WorldModel.Now n = f.now.get(l.id);
            if (n != null) WorldPainter.weatherIcon(ui.g, n.weather, x + 12, ry + rowH / 2 - 1, 8, nightAt(f, l));
            ui.textFit(l.name, x + 32, ry + 16, rw - 100, Ui.F_MEDB, Color.WHITE);
            ui.textFit((l.region.isEmpty() ? "" : l.region + "  ·  ") + (n == null ? "" : n.weather + (Double.isNaN(n.localHour) ? "" : "  ·  " + WorldModel.clock(n.localHour))),
                    x + 32, ry + 30, rw - 100, Ui.F_SMALL, Ui.DIM);
            if (n != null && !Double.isNaN(n.tempC)) {
                Color tc = WorldPainter.color(WorldPainter.TEMP, n.tempC);
                ui.fill(x - 6 + rw - 60, ry + 8, 52, rowH - 18, 9, new Color(tc.getRed(), tc.getGreen(), tc.getBlue(), 70));
                ui.textCenter(WorldModel.whole(n.tempC) + " °C", x - 6 + rw - 34, ry + rowH / 2 + 3, Ui.F_BOLD, Color.WHITE);
            }
            if (hy1 > hy0) ui.hit(x - 6, hy0, rw, hy1 - hy0, () -> { select(l, null); painter.centerOn(l.lon, l.lat, 1); });
        }
        ui.g.setClip(clip);
        if (bar) {
            double th = Math.max(24, lh * lh / content), by = ly + (lh - th) * (listScroll / (content - lh));
            ui.fill(p.x + p.width - 10, ly, 4, lh, 4, new Color(255, 255, 255, 18));
            ui.fill(p.x + p.width - 10, by, 4, th, 4, new Color(255, 255, 255, 110));
        }
        double hy = p.y + p.height - 34;
        ui.textFit("Click a dot, a country or a US state.", x, hy, w, Ui.F_SMALL, Ui.DIM);
        ui.textFit("Double-click a dot to open it  ·  H shows every key", x, hy + 15, w, Ui.F_SMALL, Ui.FAINT);
    }

    private void closeButton(Ui ui, Rectangle2D.Double p) {
        ui.button("×", p.x + p.width - 40, p.y + 10, 28, 26, Ui.GHOST, true, false, this::clearSelection);
    }

    private void card(Ui ui, Rectangle2D.Double p, WorldModel m, WorldModel.Frame f, WorldModel.Loc l) {
        double x = p.x + 16, w = p.width - 32, y = p.y;
        boolean tight = p.height < 640;
        WorldModel.Now n = f.now.get(l.id);
        closeButton(ui, p);
        ui.text(kindText(l), x, y + 24, Ui.F_SMALL.deriveFont(Font.BOLD), Ui.ACCENT);
        y += 50;
        for (String line : ui.wrap(l.name, Ui.F_BIG.deriveFont(22f), w)) { ui.text(line, x, y, Ui.F_BIG.deriveFont(22f), Color.WHITE); y += 26; }
        ui.text(coords(l.lat, l.lon), x, y - 6, Ui.F_SMALL, Ui.DIM);
        y += 12;
        if (!l.summary.isEmpty()) {
            List<String> lines = ui.wrap(l.summary, Ui.F_NORM, w);
            int max = tight ? 2 : 4;
            for (int k = 0; k < Math.min(max, lines.size()); k++) {
                String s = k == max - 1 && lines.size() > max ? ui.fit(lines.get(k) + " …", Ui.F_NORM, w) : lines.get(k);
                ui.text(s, x, y, Ui.F_NORM, Ui.TEXT);
                y += 16;
            }
            y += 4;
        }
        if (!l.station.isEmpty()) {
            ui.text("Station", x, y, Ui.F_SMALL, Ui.DIM);
            ui.textFit(l.station, x + 58, y, w - 58, Ui.F_BOLD, Ui.TEXT);
            y += 16;
        }
        String annual = (Double.isNaN(l.annualTempC) ? "" : String.format("%.1f °C", l.annualTempC))
                + (Double.isNaN(l.annualPrecipMm) ? "" : (Double.isNaN(l.annualTempC) ? "" : "  ·  ") + String.format("%,.0f mm a year", l.annualPrecipMm));
        if (!annual.isEmpty()) {
            ui.text("Annual", x, y, Ui.F_SMALL, Ui.DIM);
            ui.textFit(annual, x + 58, y, w - 58, Ui.F_BOLD, Ui.TEXT);
            y += 16;
        }
        y += 2;

        // ---- right now
        double rowH = tight ? 31 : 35, bh = 22 + 3 * rowH + 2;
        ui.fill(x - 4, y, w + 8, bh, 12, new Color(18, 26, 44, 240));
        ui.stroke(x - 4, y, w + 8, bh, 12, new Color(255, 255, 255, 26), 1f);
        ui.text("RIGHT NOW", x + 8, y + 17, Ui.F_SMALL.deriveFont(Font.BOLD), Ui.ACCENT);
        if (n == null) ui.text("no readout for this location yet", x + 8, y + 44, Ui.F_NORM, Ui.DIM);
        else {
            String[][] cells = {
                    {"Temperature", Double.isNaN(n.tempC) ? "—" : String.format("%.1f °C", n.tempC)},
                    {"Weather", cap(n.weather)},
                    {"Wind", Double.isNaN(n.windMs) ? "—" : String.format("%.1f m/s", n.windMs)},
                    {"Local time", WorldModel.clock(n.localHour) + (nightAt(f, l) ? "  night" : "  day")},
                    {"Month there", cap(WorldModel.monthText(n.month))},
                    {"Season", cap(WorldModel.season(n.month, l.lat))}};
            double cw = (w - 8) / 2;
            for (int k = 0; k < cells.length; k++) {
                double cx = x + 8 + (k % 2) * cw, cy = y + 22 + (k / 2) * rowH;
                ui.text(cells[k][0], cx, cy + 10, Ui.F_SMALL, Ui.DIM);
                double tx = cx;
                if (k == 1) { WorldPainter.weatherIcon(ui.g, n.weather, cx + 8, cy + (tight ? 20 : 22), 7, nightAt(f, l)); tx += 22; }
                Color vc = k == 0 && !Double.isNaN(n.tempC) ? WorldPainter.color(WorldPainter.TEMP, n.tempC).brighter() : Color.WHITE;
                ui.textFit(cells[k][1], tx, cy + (tight ? 24 : 27), cw - 8 - (tx - cx), Ui.F_MEDB, vc);
            }
        }
        y += bh + 10;

        // ---- controls sit at the bottom; the curve and the highlights share what is left
        double btnY = p.y + p.height - 70;
        double chipsH = l.highlights.length == 0 ? 0 : 26;
        double chartH = Math.min(196, btnY - 10 - chipsH - y);
        if (chartH >= 64) {
            chart(ui, x - 4, y, w + 8, chartH, l, n != null ? n.month : f.month, n == null ? Double.NaN : n.tempC);
            y += chartH + 8;
        }
        if (chipsH > 0 && y + 20 <= btnY - 4) {
            double cx = x;
            for (String hl : l.highlights) {
                double cw = ui.width(hl, Ui.F_SMALL) + 16;
                if (cx + cw > x + w + 4) break;
                ui.fill(cx, y, cw, 20, 20, new Color(120, 180, 255, 36));
                ui.stroke(cx, y, cw, 20, 20, new Color(120, 180, 255, 90), 1f);
                ui.text(hl, cx + 8, y + 14, Ui.F_SMALL, new Color(206, 226, 255));
                cx += cw + 6;
            }
        }
        double month = n != null && !Double.isNaN(n.month) ? n.month : f.month;
        ui.button("Open this location", x - 4, btnY, w + 8, 38, Ui.PRIMARY, true, true, () -> open(l));
        ui.textFit("Enter  ·  a new sandbox world, " + WorldModel.monthText(month) + ", " + (n == null ? "--:--" : WorldModel.clock(n.localHour)) + " local time",
                x - 2, btnY + 54, w + 4, Ui.F_SMALL, Ui.FAINT);
    }

    /** The twelve monthly means as a curve, coloured like the temperature layer, with the calendar's month marked. */
    private void chart(Ui ui, double x, double y, double w, double h, WorldModel.Loc l, double month, double nowTemp) {
        Graphics2D g = ui.g;
        ui.fill(x, y, w, h, 12, new Color(18, 26, 44, 240));
        ui.stroke(x, y, w, h, 12, new Color(255, 255, 255, 26), 1f);
        ui.text("THE YEAR HERE", x + 12, y + 17, Ui.F_SMALL.deriveFont(Font.BOLD), Ui.ACCENT);
        double[] t = l.monthlyTempC;
        if (t.length != 12) {
            ui.text("no monthly figures for this location", x + 12, y + h / 2 + 8, Ui.F_NORM, Ui.DIM);
            return;
        }
        ui.textRight(Preset.placeholderData ? "placeholder curve, not station data" : "monthly mean temperature, °C", x + w - 12, y + 17, Ui.F_SMALL,
                Preset.placeholderData ? Ui.AMBER : Ui.FAINT);
        double lo = 1e9, hi = -1e9;
        for (double v : t) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        if (!Double.isNaN(nowTemp)) { lo = Math.min(lo, nowTemp); hi = Math.max(hi, nowTemp); }
        double pad = Math.max(1.5, (hi - lo) * 0.12);
        lo -= pad; hi += pad;
        if (hi - lo < 8) { double mid = (hi + lo) / 2; lo = mid - 4; hi = mid + 4; }
        double px0 = x + 34, px1 = x + w - 12, py0 = y + 26, py1 = y + h - 20;
        double step = (hi - lo) > 36 ? 20 : (hi - lo) > 16 ? 10 : 5;
        g.setStroke(new BasicStroke(1f));
        for (double v = Math.ceil(lo / step) * step; v <= hi; v += step) {
            double yy = py1 - (v - lo) / (hi - lo) * (py1 - py0);
            g.setColor(new Color(255, 255, 255, Math.abs(v) < 1e-9 ? 70 : 26));
            g.draw(new Line2D.Double(px0, yy, px1, yy));
            ui.textRight(WorldModel.whole(v), px0 - 5, yy + 4, Ui.F_SMALL, Ui.DIM);
        }
        double cw = (px1 - px0) / 12;
        // the curve, sampled finely so that each piece takes the colour of its temperature
        int steps = 72;
        double prevX = 0, prevY = 0;
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int k = 0; k <= steps; k++) {
            double mm = 1 + 12.0 * k / steps, v = WorldModel.monthly(t, Math.min(12.9999, mm));
            double xx = px0 + (px1 - px0) * k / steps, yy = py1 - (v - lo) / (hi - lo) * (py1 - py0);
            if (k > 0) {
                g.setColor(WorldPainter.color(WorldPainter.TEMP, v));
                g.draw(new Line2D.Double(prevX, prevY, xx, yy));
            }
            prevX = xx; prevY = yy;
        }
        for (int k = 0; k < 12; k++) {
            double xx = px0 + (k + 0.5) * cw, yy = py1 - (t[k] - lo) / (hi - lo) * (py1 - py0);
            g.setColor(new Color(10, 14, 24));
            g.fill(new Ellipse2D.Double(xx - 3.4, yy - 3.4, 6.8, 6.8));
            g.setColor(WorldPainter.color(WorldPainter.TEMP, t[k]).brighter());
            g.fill(new Ellipse2D.Double(xx - 2.3, yy - 2.3, 4.6, 4.6));
            ui.textCenter(WorldModel.MONTHS[k].substring(0, 1), xx, y + h - 7, Ui.F_SMALL, Ui.DIM);
        }
        // where the calendar is
        if (!Double.isNaN(month)) {
            double mp = ((month - 1) % 12 + 12) % 12, xx = px0 + mp / 12 * (px1 - px0), v = WorldModel.monthly(t, month);
            double yy = py1 - (v - lo) / (hi - lo) * (py1 - py0);
            g.setColor(new Color(255, 226, 140, 170));
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{3f, 3f}, 0));
            g.draw(new Line2D.Double(xx, py0 - 2, xx, py1 + 2));
            g.setColor(Ui.ACCENT);
            g.fill(new Ellipse2D.Double(xx - 4.5, yy - 4.5, 9, 9));
            g.setColor(new Color(10, 14, 24));
            g.setStroke(new BasicStroke(1.4f));
            g.draw(new Ellipse2D.Double(xx - 4.5, yy - 4.5, 9, 9));
            String lab = String.format("%.1f°", v);
            double lw = ui.width(lab, Ui.F_BOLD), lx = xx + 8 + lw > px1 ? xx - 8 - lw : xx + 8;
            // the figure sits at the top of the marker line, clear of the curve
            ui.fill(lx - 3, py0 - 6, lw + 6, 15, 6, new Color(18, 26, 44, 235));
            ui.text(lab, lx, py0 + 6, Ui.F_BOLD, Ui.ACCENT);
            if (!Double.isNaN(nowTemp)) {
                double ny = py1 - (nowTemp - lo) / (hi - lo) * (py1 - py0);
                g.setColor(Color.WHITE);
                Path2D.Double d = new Path2D.Double();
                d.moveTo(xx, ny - 4.5); d.lineTo(xx + 4.5, ny); d.lineTo(xx, ny + 4.5); d.lineTo(xx - 4.5, ny);
                d.closePath();
                g.fill(d);
                g.setColor(new Color(10, 14, 24));
                g.setStroke(new BasicStroke(1f));
                g.draw(d);
            }
        }
    }

    private static String bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double b = Math.toDegrees(Math.atan2(Math.sin(dl) * Math.cos(p2), Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)));
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return names[Math.floorMod((int) Math.round(b / 45), 8)];
    }

    private void noPresetCard(Ui ui, Rectangle2D.Double p, WorldModel m, WorldModel.Frame f) {
        double x = p.x + 16, w = p.width - 32, y = p.y;
        closeButton(ui, p);
        ui.text("NO PRESET HERE", x, y + 24, Ui.F_SMALL.deriveFont(Font.BOLD), Ui.AMBER);
        y += 50;
        for (String line : ui.wrap(noPresetLabel, Ui.F_BIG.deriveFont(22f), w)) { ui.text(line, x, y, Ui.F_BIG.deriveFont(22f), Color.WHITE); y += 26; }
        ui.text(coords(clickLat, clickLon), x, y - 6, Ui.F_SMALL, Ui.DIM);
        y += 14;
        for (String line : ui.wrap("This region has no preset of its own, so there is no sandbox world to open here. Only the marked locations have real terrain and climate data behind them.",
                Ui.F_NORM, w)) { ui.text(line, x, y, Ui.F_NORM, Ui.TEXT); y += 16; }
        y += 10;
        WorldModel.Loc near = m.nearest(clickLat, clickLon);
        if (near == null) { ui.text("The simulation lists no locations.", x, y + 8, Ui.F_NORM, Ui.DIM); return; }
        double km = WorldMap.distanceKm(clickLat, clickLon, near.lat, near.lon);
        WorldModel.Now n = f.now.get(near.id);
        double bh = 92;
        ui.fill(x - 4, y, w + 8, bh, 12, new Color(18, 26, 44, 240));
        ui.stroke(x - 4, y, w + 8, bh, 12, new Color(255, 255, 255, 26), 1f);
        ui.text("NEAREST LOCATION", x + 8, y + 17, Ui.F_SMALL.deriveFont(Font.BOLD), Ui.ACCENT);
        if (n != null) WorldPainter.weatherIcon(ui.g, n.weather, x + 20, y + 45, 9, nightAt(f, near));
        ui.textFit(near.name, x + 40, y + 42, w - 110, Ui.F_HEAD, Color.WHITE);
        if (n != null && !Double.isNaN(n.tempC)) ui.textRight(WorldModel.whole(n.tempC) + " °C", x + w - 4, y + 42, Ui.F_HEAD, WorldPainter.color(WorldPainter.TEMP, n.tempC).brighter());
        ui.textFit(String.format("%,.0f km to the %s", km, bearing(clickLat, clickLon, near.lat, near.lon)), x + 40, y + 60, w - 48, Ui.F_BOLD, Ui.ACCENT);
        ui.textFit((near.region.isEmpty() ? "" : near.region + "  ·  ") + (near.station.isEmpty() ? coords(near.lat, near.lon) : near.station), x + 40, y + 77, w - 48, Ui.F_SMALL, Ui.DIM);
        y += bh + 12;
        ui.button("Show " + near.name, x - 4, y, w + 8, 38, Ui.PRIMARY, true, true, () -> { select(near, null); painter.centerOn(near.lon, near.lat, 1); });
        ui.textFit("Enter  ·  its card has the button that opens it", x - 2, y + 54, w + 4, Ui.F_SMALL, Ui.FAINT);
    }

    // ---- pointer text, help

    private void tooltip(Ui ui, WorldModel m, WorldModel.Frame f, int w) {
        if (help) return;
        String a, b;
        Color bc = Ui.DIM;
        if (!hoverId.isEmpty()) {
            WorldModel.Loc l = m.loc(hoverId);
            if (l == null) return;
            WorldModel.Now n = f.now.get(l.id);
            a = l.name + (n == null || Double.isNaN(n.tempC) ? "" : String.format("  ·  %.1f °C, %s", n.tempC, n.weather));
            b = l.id.equals(selId) ? "double-click or Enter opens it" : "click for its card  ·  double-click opens it";
            bc = Ui.ACCENT;
        } else if (hover != null && hover.region != null) {
            a = hover.label;
            if (hover.loc != null) { b = "click: " + hover.loc.name; bc = Ui.ACCENT; }
            else b = "no preset here";
        } else return;
        double tw = Math.max(ui.width(a, Ui.F_BOLD), ui.width(b, Ui.F_SMALL)) + 20, th = 40;
        double tx = mx() + 16, ty = my() + 18;
        Rectangle2D.Double r = painter.rect;
        if (tx + tw > r.x + r.width - 4) tx = mx() - tw - 12;
        if (ty + th > r.y + r.height - 4) ty = my() - th - 10;
        ui.fill(tx, ty, tw, th, 10, new Color(8, 12, 22, 238));
        ui.stroke(tx, ty, tw, th, 10, new Color(255, 255, 255, 60), 1f);
        ui.text(a, tx + 10, ty + 17, Ui.F_BOLD, Color.WHITE);
        ui.text(b, tx + 10, ty + 32, Ui.F_SMALL, bc);
    }

    /** Still pictures have no mouse pointer of their own: draw one where the pretend pointer is. */
    private static void drawPointer(Graphics2D g, double x, double y, boolean hand) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x, y); p.lineTo(x, y + 16); p.lineTo(x + 4.2, y + 12.4); p.lineTo(x + 7.2, y + 19); p.lineTo(x + 9.6, y + 17.9); p.lineTo(x + 6.7, y + 11.5); p.lineTo(x + 11.8, y + 11.2);
        p.closePath();
        g.setColor(hand ? Ui.ACCENT : Color.WHITE);
        g.fill(p);
        g.setColor(new Color(8, 12, 22));
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(p);
    }

    private void helpOverlay(Ui ui, int w, int h) {
        String shot = KeyMap.keysLabel(app.settings.keys.get(Action.SCREENSHOT)), full = KeyMap.keysLabel(app.settings.keys.get(Action.FULLSCREEN));
        String[][] rows = {
                {"Drag  /  ← ↑ → ↓", "pan the map"},
                {"Wheel  /  PgUp PgDn", "zoom in and out (at the pointer)"},
                {"Home", "the whole world"},
                {"1 2 3 4 5 6", "temperature, clouds, precipitation, wind, pressure, snow"},
                {"7 or 0  /  L", "plain map  /  next layer"},
                {"Click", "a dot, a country or a US state: its location card"},
                {"Double-click  /  Enter", "open the location as a new sandbox world"},
                {"Tab  /  Backspace", "next location  /  close the card"},
                {"Space", "pause or resume"},
                {"=  /  -", "faster / slower (×0.25 … ×64)"},
                {"]  /  [", "three months on / back"},
                {".  /  ,", "one hour on / back"},
                {"G  B  N  D  Y", "graticule, borders, night side, dots, weather systems"},
                {shot + "  /  " + full, "screenshot  /  fullscreen"},
                {"H  /  F1", "this list"},
                {"Esc", "back to the main menu"}};
        ui.dim(w, h, 120);
        double rh = h < 700 ? 20 : 22;
        double[] d = ui.dialog(w, h, 560, 78 + rows.length * rh + 14, "World Simulation — controls");
        ui.text("source: " + gs.source.describe(), d[0] + 24, d[1] + 54, Ui.F_SMALL, Ui.DIM);
        double y = d[1] + 82;
        for (String[] r : rows) {
            ui.textFit(r[0], d[0] + 24, y, 176, Ui.F_BOLD, Ui.ACCENT);
            ui.textFit(r[1], d[0] + 208, y, d[2] - 232, Ui.F_NORM, Ui.TEXT);
            y += rh;
        }
    }
}
