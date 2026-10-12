package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The "Hurricane Formation" mode: a guided tour through the simulation's lesson (text + pictures,
 * chapter by chapter), then a stage explorer. The pictures (the storm from outside and its inside
 * as a cross-section) are drawn by {@link HurricanePainter} into the game panel's buffer through
 * {@link ViewerPanel.Scene}; the text panel, the buttons and the stage strip are this screen.
 * <p>
 * Every fact on screen comes from the simulation: chapter titles and text, stage names, wind
 * ranges, categories and summaries from {@code hello.hurricane}, the numbers from the frames. The
 * viewer only adds the names of the things it points at (eye, eyewall, ...), which are the
 * protocol's own {@code focus} words.
 */
final class HurricaneScreen extends Screen implements ViewerPanel.Scene {
    static final int OUTSIDE = 0, INSIDE = 1, BOTH = 2;
    private static final String[] VIEW_NAMES = {"Outside", "Inside", "Both"};
    private static final double[] SPEEDS = {0.25, 0.5, 1, 2, 4, 8};
    private static final Color BG = new Color(7, 11, 20);

    private final AppShell app;
    private final GameSession gs;
    final HurricanePainter painter = new HurricanePainter();

    /** True while the guided tour is running; false = the stage explorer. */
    boolean tour = true;
    int chapter;
    int view = BOTH;
    /** The stage the explorer shows the summary of ("" = follow the storm). */
    String selStage = "";
    boolean labels = true;
    /** Still pictures (snapshots): nothing eases, everything is drawn settled. */
    boolean still;
    private boolean started;
    /**
     * Keep the tour-or-explorer choice, the chapter, the explorer's stage and the view in the settings
     * ({@link #restore}; only for the mode opened from the app — a snapshot sets its own place and never writes).
     */
    boolean remember;
    /** The view to come back to once the first frame is there ("" = the chapter's own / both). */
    private String restoreView = "";
    private static final String[] VIEW_IDS = {"outside", "inside", "both"};
    private double anim;
    private double textScroll;
    // the two picture rectangles as drawn (they glide when the layout changes)
    private Rectangle2D.Double curOut, curIn;
    private boolean outBig = true;
    private int lastW, lastH;
    /** The two tints a chapter can ask for (warm sea, warm core), eased so that opening or leaving such a chapter does not flash. */
    private double sstGlow, warmGlow;

    HurricaneScreen(AppShell app, GameSession gs) {
        this.app = app;
        this.gs = gs;
    }

    @Override boolean dims() { return false; }

    /** Snapshots set the chapter / stage themselves: do not send the opening chapter's stage on the first frame. */
    void markStarted() { started = true; }

    /** Open where the player was last (settings.json, "modes"). The chapter and the stage are checked against the simulation's hello on the first frame. */
    void restore(Settings s) {
        remember = true;
        tour = !s.hurricaneExplore;
        chapter = Math.max(0, s.hurricaneChapter);
        selStage = s.hurricaneStage == null ? "" : s.hurricaneStage;
        restoreView = s.hurricaneView == null ? "" : s.hurricaneView;
    }

    /** Write the place to the settings when it is not what they hold (called once per painted frame; it only writes on a change). */
    private void store() {
        if (!remember || !started) return;
        Settings s = app.settings;
        String v = VIEW_IDS[Math.max(0, Math.min(2, view))];
        if (s.hurricaneChapter == chapter && s.hurricaneExplore == !tour && selStage.equals(s.hurricaneStage) && v.equals(s.hurricaneView)) return;
        s.hurricaneChapter = chapter;
        s.hurricaneExplore = !tour;
        s.hurricaneStage = selStage;
        s.hurricaneView = v;
        app.settingsChanged();
    }

    // ------------------------------------------------------------------ model access

    private HurricaneModel model() {
        synchronized (gs.state) { return gs.state.hurricane; }
    }

    private HurricaneModel.Frame frame() {
        synchronized (gs.state) { return gs.state.hurricane == null ? null : gs.state.hurricane.cur; }
    }

    private HurricaneModel.Chapter chapter(HurricaneModel m) {
        return m == null || m.lesson.isEmpty() ? null : m.lesson.get(Math.max(0, Math.min(m.lesson.size() - 1, chapter)));
    }

    // ------------------------------------------------------------------ actions

    private void send(String json) { gs.source.send(json); }

    private void sendStage(String id) {
        if (id != null && !id.isEmpty()) send("{\"cmd\":\"stage\",\"id\":" + Json.quote(id) + "}");
    }

    /** Open chapter k of the tour: the storm glides to the chapter's stage and the picture it is about comes forward. */
    void enterChapter(int k) {
        HurricaneModel m = model();
        if (m == null || m.lesson.isEmpty()) return;
        tour = true;
        chapter = Math.max(0, Math.min(m.lesson.size() - 1, k));
        HurricaneModel.Chapter c = m.lesson.get(chapter);
        view = switch (c.view) { case "outside" -> OUTSIDE; case "inside" -> INSIDE; default -> BOTH; };
        textScroll = 0;
        sendStage(c.stage);
    }

    void next() {
        HurricaneModel m = model();
        if (m == null) return;
        if (!tour) { stepStage(+1); return; }
        if (chapter >= m.lesson.size() - 1) explore(); else enterChapter(chapter + 1);
    }

    void back() {
        if (!tour) { stepStage(-1); return; }
        if (chapter > 0) enterChapter(chapter - 1);
    }

    /** Leave the tour for the stage explorer. */
    void explore() {
        tour = false;
        view = BOTH;
        textScroll = 0;
        HurricaneModel.Frame f = frame();
        selStage = f != null ? f.stage : "";
    }

    void pickStage(String id) {
        tour = false;
        selStage = id;
        textScroll = 0;
        sendStage(id);
    }

    private void stepStage(int dir) {
        HurricaneModel m = model();
        HurricaneModel.Frame f = frame();
        if (m == null || m.stages.isEmpty()) return;
        int i = m.stageIndex(selStage.isEmpty() && f != null ? f.stage : selStage);
        pickStage(m.stages.get(Math.max(0, Math.min(m.stages.size() - 1, Math.max(0, i) + dir))).id);
    }

    void toggleAuto() {
        HurricaneModel.Frame f = frame();
        boolean on = f == null || !f.auto;
        send("{\"cmd\":\"auto\",\"value\":" + on + "}");
        if (on) { tour = false; selStage = ""; }
    }

    void togglePause() {
        HurricaneModel.Frame f = frame();
        send(f != null && f.paused ? "{\"cmd\":\"resume\"}" : "{\"cmd\":\"pause\"}");
    }

    void changeSpeed(int dir) {
        HurricaneModel.Frame f = frame();
        double cur = f != null ? f.speed : 1;
        int idx = 2;
        double best = 1e9;
        for (int i = 0; i < SPEEDS.length; i++) if (Math.abs(SPEEDS[i] - cur) < best) { best = Math.abs(SPEEDS[i] - cur); idx = i; }
        idx = Math.max(0, Math.min(SPEEDS.length - 1, idx + dir));
        send("{\"cmd\":\"speed\",\"value\":" + SPEEDS[idx] + "}");
    }

    @Override void key(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ESCAPE -> app.leaveNow();
            case KeyEvent.VK_RIGHT, KeyEvent.VK_ENTER, KeyEvent.VK_PAGE_DOWN -> next();
            case KeyEvent.VK_LEFT, KeyEvent.VK_PAGE_UP -> back();
            case KeyEvent.VK_SPACE, KeyEvent.VK_P -> togglePause();
            case KeyEvent.VK_O -> view = OUTSIDE;
            case KeyEvent.VK_I -> view = INSIDE;
            case KeyEvent.VK_B -> view = BOTH;
            case KeyEvent.VK_V, KeyEvent.VK_TAB -> view = (view + 1) % 3;
            case KeyEvent.VK_L -> labels = !labels;
            case KeyEvent.VK_A -> toggleAuto();
            case KeyEvent.VK_S, KeyEvent.VK_E -> explore();
            case KeyEvent.VK_T, KeyEvent.VK_R -> enterChapter(0);
            case KeyEvent.VK_HOME -> { if (tour) enterChapter(0); }
            case KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD -> changeSpeed(+1);
            case KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> changeSpeed(-1);
            default -> { }
        }
    }

    @Override void wheel(int x, int y, double rotation) { textScroll = Math.max(0, textScroll + rotation * 34); }

    // ------------------------------------------------------------------ layout

    private double textW(int w) { return Model.clamp(w * 0.27, 316, 440); }

    /** The area the pictures share: left of the text panel, between the top bar and the stage strip. */
    private Rectangle2D.Double pictures(int w, int h) {
        double tw = textW(w), top = 54, strip = stripH(h);
        return new Rectangle2D.Double(12, top, Math.max(120, w - tw - 36), Math.max(100, h - top - strip - 20));
    }

    private static double stripH(int h) { return h < 660 ? 74 : 86; }

    /** In a narrow window the ten stage chips need the full width (the text panel then ends above them). */
    private static boolean wideStrip(int w) { return w < 1320; }

    /** Target rectangles {outside, inside} for the current view and window. */
    private Rectangle2D.Double[] targets(int w, int h) {
        Rectangle2D.Double p = pictures(w, h);
        Rectangle2D.Double o, i;
        // too little room to split: "both" then means the view from above with the cross-section as its inset
        boolean cramped = view == BOTH && p.width < 1.85 * p.height && (p.height < 520 || p.width < 760);
        if (view == BOTH && !cramped) {
            if (p.width >= 1.85 * p.height) {            // side by side: a square from above, the section beside it
                o = new Rectangle2D.Double(p.x, p.y, p.height, p.height);
                i = new Rectangle2D.Double(p.x + p.height + 10, p.y, p.width - p.height - 10, p.height);
            } else {                                      // stacked
                double oh = Math.round((p.height - 10) * 0.57);
                o = new Rectangle2D.Double(p.x, p.y, p.width, oh);
                i = new Rectangle2D.Double(p.x, p.y + oh + 10, p.width, p.height - oh - 10);
            }
            outBig = true;
        } else if (view == OUTSIDE || cramped) {
            o = p;
            double iw = Model.clamp(p.width * 0.34, 230, 420), ih = Math.min(p.height * 0.42, iw * 0.52);
            i = new Rectangle2D.Double(p.x + p.width - iw - 10, p.y + p.height - ih - 10, iw, ih);
            outBig = true;
        } else {
            i = p;
            double oh = Model.clamp(p.height * 0.40, 150, 280), ow = Math.min(p.width * 0.34, oh * 1.2);
            o = new Rectangle2D.Double(p.x + p.width - ow - 10, p.y + 10, ow, oh);
            outBig = false;
        }
        return new Rectangle2D.Double[]{o, i};
    }

    private static void glide(Rectangle2D.Double cur, Rectangle2D.Double tgt, double k) {
        cur.x += (tgt.x - cur.x) * k; cur.y += (tgt.y - cur.y) * k;
        cur.width += (tgt.width - cur.width) * k; cur.height += (tgt.height - cur.height) * k;
        if (Math.abs(cur.x - tgt.x) + Math.abs(cur.y - tgt.y) + Math.abs(cur.width - tgt.width) + Math.abs(cur.height - tgt.height) < 0.8) cur.setRect(tgt);
    }

    // ------------------------------------------------------------------ the pictures (ViewerPanel.Scene)

    @Override public void paintScene(BufferedImage buf, double scale, int w, int h, double dt) {
        Graphics2D g = buf.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.scale(scale, scale);
            g.setColor(BG);
            g.fillRect(0, 0, w + 1, h + 1);
            HurricaneModel m = model();
            HurricaneModel.Frame f = frame();
            if (m == null || f == null) {
                String err = gs.simError();
                g.setFont(Ui.F_BIG);
                g.setColor(new Color(236, 242, 250, 220));
                String msg = err != null ? "" : "Starting the hurricane simulation…";
                g.drawString(msg, (float) ((w - g.getFontMetrics().stringWidth(msg)) / 2.0), h / 2f);
                return;
            }
            if (!started) {
                started = true;
                if (!tour && !selStage.isEmpty() && m.stageIndex(selStage) < 0) selStage = "";   // a remembered stage this simulation does not have
                if (tour) enterChapter(chapter);
                else if (!selStage.isEmpty()) sendStage(selStage);
                for (int k = 0; k < VIEW_IDS.length; k++) if (VIEW_IDS[k].equals(restoreView)) view = k;   // (after enterChapter, which picks the chapter's own view)
            }
            if (!still) anim += dt;
            Rectangle2D.Double[] t = targets(w, h);
            boolean jump = still || curOut == null || w != lastW || h != lastH;
            lastW = w; lastH = h;
            if (jump) { curOut = new Rectangle2D.Double(); curOut.setRect(t[0]); curIn = new Rectangle2D.Double(); curIn.setRect(t[1]); }
            else { double k = 1 - Math.exp(-dt * 10); glide(curOut, t[0], k); glide(curIn, t[1], k); }

            painter.setTarget(buf, scale);
            painter.update(m, f, still ? -1 : dt);
            HurricaneModel.Chapter c = tour ? chapter(m) : null;
            String focus = c != null ? c.focus : "";
            double gk = still ? 1 : 1 - Math.exp(-dt / 0.35);
            sstGlow += ((focus.equals("sst") ? 1 : 0) - sstGlow) * gk;
            warmGlow += ((focus.equals("warm_core") ? 1 : 0) - warmGlow) * gk;
            boolean overlap = curIn.intersects(curOut);
            // side by side, the picture the chapter is about gets the highlighted frame
            boolean aboutOut = view == BOTH && c != null && c.view.equals("outside"), aboutIn = view == BOTH && c != null && c.view.equals("inside");
            avoid = overlap ? (outBig ? curIn : curOut) : null;
            if (outBig) {
                drawOutside(g, curOut, scale, false, focus, f, m, aboutOut);
                drawInside(g, curIn, scale, overlap, focus, f, m, aboutIn);
            } else {
                drawInside(g, curIn, scale, false, focus, f, m, aboutIn);
                drawOutside(g, curOut, scale, overlap, focus, f, m, aboutOut);
            }
        } finally {
            g.dispose();
        }
    }

    private double pulse() { return 0.5 + 0.5 * Math.sin(anim * 3.2); }

    /** The inset picture, while one view is in front: labels of the big picture keep off it. */
    private Rectangle2D avoid;

    private void label(Graphics2D g, double ax, double ay, double lx, double ly, String title, String sub, Rectangle2D bounds, double pulse) {
        HurricanePainter.callout(g, ax, ay, lx, ly, title, sub, bounds, pulse, avoid);
    }

    private void drawOutside(Graphics2D g, Rectangle2D.Double r, double dev, boolean small, String focus, HurricaneModel.Frame f, HurricaneModel m, boolean strong) {
        if (small) shadow(g, r);
        painter.paintOutside(g, r, dev, sstGlow);
        painter.paintOutsideRings(g, !small, small ? null : avoid);
        painter.paintOutsideWind(g, (small ? 0.55 : focus.equals("inflow") || focus.equals("coriolis") ? 1 : 0.8) * Model.clamp(painter.outsidePxPerKm() / 0.40, 0.4, 1));
        if (!small) outsideLabels(g, r, focus, f, m);
        HurricanePainter.caption(g, r, "OUTSIDE", small ? "" : "seen from above");
        HurricanePainter.frame(g, r, strong);
    }

    private void drawInside(Graphics2D g, Rectangle2D.Double r, double dev, boolean small, String focus, HurricaneModel.Frame f, HurricaneModel m, boolean strong) {
        if (small) shadow(g, r);
        painter.paintInside(g, r, dev, warmGlow);
        String em = switch (focus) {
            case "inflow", "sst" -> "inflow";
            case "eyewall", "convection" -> "eyewall";
            case "outflow" -> "outflow";
            case "eye", "warm_core" -> "eye";
            default -> "";
        };
        painter.paintInsideAxes(g, small || r.height < 240);
        painter.paintInsideFlow(g, small ? 0.7 : 1, em);
        if (!small) {
            painter.paintInsideArrows(g, 1, em);
            insideLabels(g, r, focus, f, m);
        }
        HurricanePainter.caption(g, r, "INSIDE", small ? "" : "cross-section through the centre · height and the middle stretched");
        HurricanePainter.frame(g, r, strong);
    }

    private static void shadow(Graphics2D g, Rectangle2D r) {
        for (int k = 5; k >= 1; k--) {
            g.setColor(new Color(0, 0, 0, 22));
            g.fill(new RoundRectangle2D.Double(r.getX() - k, r.getY() - k + 2, r.getWidth() + 2 * k, r.getHeight() + 2 * k, 14 + k, 14 + k));
        }
    }

    /** The name the viewer gives a {@code focus} word of the protocol. */
    static String focusName(String focus) {
        return switch (focus) {
            case "sst" -> "Sea surface";
            case "coriolis" -> "Rotation";
            case "convection" -> "Thunderstorms";
            case "inflow" -> "Inflow";
            case "eye" -> "Eye";
            case "eyewall" -> "Eyewall";
            case "rainbands" -> "Spiral rainbands";
            case "outflow" -> "Outflow";
            case "warm_core" -> "Warm core";
            case "surge" -> "Storm surge";
            case "decay" -> "Over land";
            default -> "";
        };
    }

    private void outsideLabels(Graphics2D g, Rectangle2D.Double r, String focus, HurricaneModel.Frame f, HurricaneModel m) {
        double R = m.radiusKm, pl = pulse(), far = Math.min(r.width, r.height) * 0.19;
        boolean guide = tour && !focus.isEmpty() && !focus.equals("none");
        Color hi = new Color(255, 226, 140, (int) (150 + 90 * pl));
        if (guide) {
            switch (focus) {
                case "eye" -> {
                    if (f.eye > 0.5) painter.ring(g, f.eye, hi, 2f, anim * 10);
                    double[] a = painter.outsideXY(0, 0);
                    label(g, a[0], a[1], a[0] + far, a[1] - far, focusName(focus), f.eye > 0.5 ? String.format("radius %.0f km", f.eye) : "not formed yet", r, pl);
                }
                case "eyewall" -> {
                    painter.ring(g, f.rmw, hi, 2.2f, anim * 10);
                    double[] a = painter.outsideXY(f.rmw, 205);
                    label(g, a[0], a[1], a[0] - far, a[1] + far * 0.9, focusName(focus), String.format("strongest wind %.0f kt, %.0f km out", f.vmaxKt, f.rmw), r, pl);
                }
                case "rainbands" -> {
                    painter.paintBandLines(g, new Color(255, 226, 140, 230), 2.2f);
                    // the dot sits a little way round from the km ring labels (which are at the lower right, where the pill stays)
                    double[] a = painter.outsideXY(Math.max(f.rmw * 3, R * 0.36), 300), lp = painter.outsideXY(Math.max(f.rmw * 3, R * 0.36), 318);
                    int n = 0;
                    for (HurricaneModel.Band b : f.bands) if (b.intensity > 0.08) n++;
                    label(g, a[0], a[1], lp[0] + far, lp[1] + far * 0.8, focusName(focus), n == 0 ? "none yet" : n + (n == 1 ? " band" : " bands") + " spiralling in", r, pl);
                }
                case "outflow" -> {
                    double sk = Math.max(painter.shieldKm(), f.rmw * 2);
                    painter.ring(g, sk, Renderer.alpha(hi, 150), 1.6f, anim * 6);
                    double[] a = painter.outsideXY(sk * 0.82, 138);
                    label(g, a[0], a[1], a[0] - far * 0.8, a[1] - far * 0.9, focusName(focus), String.format("high cloud out to ~%.0f km, tops %.1f km", sk, f.cloudTop), r, pl);
                }
                case "inflow" -> {
                    painter.paintOutsideArrows(g, 1);
                    double[] a = painter.outsideXY(R * 0.5, -38);
                    label(g, a[0], a[1], a[0] + far * 0.7, a[1] + far, focusName(focus), String.format("surface air spirals in, up to %.0f m/s inward", painter.maxInflow), r, pl);
                }
                case "coriolis" -> {
                    painter.paintRotation(g, R * 0.74, new Color(255, 226, 140, 215));
                    painter.paintOutsideArrows(g, 0.8);
                    double[] a = painter.outsideXY(R * 0.74, 78);
                    label(g, a[0], a[1], a[0] + far * 0.9, a[1] - far * 0.6, focusName(focus),
                            (f.hemisphere.equalsIgnoreCase("S") ? "clockwise" : "counter-clockwise") + " · hemisphere " + f.hemisphere, r, pl);
                }
                case "sst" -> {
                    double[] a = painter.outsideXY(R * 0.84, 222);
                    label(g, a[0], a[1], a[0] + far * 0.4, a[1] - far * 0.9, focusName(focus), Double.isNaN(f.sst) ? "" : String.format("%.1f °C", f.sst), r, pl);
                }
                case "convection" -> {
                    double[] a = painter.outsideXY(Math.max(20, f.rmw * 0.5), 60);
                    label(g, a[0], a[1], a[0] + far, a[1] - far, focusName(focus), String.format("rain up to %.0f mm/h, tops %.1f km", f.rain, f.cloudTop), r, pl);
                }
                case "surge" -> {
                    painter.ring(g, f.rmw, hi, 2f, anim * 10);
                    // the water is pushed against the coast: point at the shore once there is one, else at the eyewall wind that drives it
                    double ck = painter.coastKm();
                    double[] a = Double.isNaN(ck) ? painter.outsideXY(f.rmw * 1.2, 15) : painter.outsideXY(Math.abs(ck), ck >= 0 ? 0 : 180);
                    label(g, a[0], a[1], a[0] + far, a[1] - far * 0.8, focusName(focus), String.format("%.1f m of water pushed ashore", f.surge), r, pl);
                }
                case "warm_core" -> {
                    double[] a = painter.outsideXY(0, 0);
                    label(g, a[0], a[1], a[0] + far, a[1] - far, focusName(focus), String.format("+%.1f °C at the centre aloft", f.warmCore), r, pl);
                }
                case "decay" -> {
                    double[] a = painter.outsideXY(f.overLand ? R * 0.7 : R * 0.2, f.overLand ? 160 : 200);
                    label(g, a[0], a[1], a[0] + far * 0.3, a[1] - far, f.overLand ? "Land" : "Open sea",
                            f.overLand ? String.format("the centre is over land · %.0f kt", f.vmaxKt) : String.format("%.0f kt", f.vmaxKt), r, pl);
                }
                default -> { }
            }
            return;
        }
        if (!labels) return;
        // no chapter to follow: name the parts that exist right now
        if (f.eye > 0.5 && f.eyeClarity > 0.2) {
            double[] a = painter.outsideXY(0, 0);
            label(g, a[0], a[1], a[0] + far * 0.9, a[1] - far * 0.9, "Eye", String.format("radius %.0f km", f.eye), r, 0);
        }
        if (f.eye > 0.5 && f.eyeClarity > 0.2) {
            double[] a = painter.outsideXY(f.rmw, 205);
            label(g, a[0], a[1], a[0] - far, a[1] + far * 0.8, "Eyewall", String.format("%.0f kt", f.vmaxKt), r, 0);
        }
        boolean anyBand = false;
        for (HurricaneModel.Band b : f.bands) if (b.intensity > 0.3) anyBand = true;
        if (anyBand) {
            double[] a = painter.outsideXY(Math.max(f.rmw * 3, R * 0.36), 300), lp = painter.outsideXY(Math.max(f.rmw * 3, R * 0.36), 318);
            label(g, a[0], a[1], lp[0] + far, lp[1] + far * 0.8, "Spiral rainbands", "", r, 0);
        }
        if (painter.shieldKm() > f.rmw * 3 && f.organization > 0.4) {
            double[] a = painter.outsideXY(painter.shieldKm() * 0.8, 138);
            label(g, a[0], a[1], a[0] - far * 0.8, a[1] - far * 0.9, "Outflow shield", "", r, 0);
        }
        if (f.overLand) {
            double[] a = painter.outsideXY(R * 0.72, 165);
            label(g, a[0], a[1], a[0], a[1] - far * 0.8, "Land", "", r, 0);
        }
    }

    private void insideLabels(Graphics2D g, Rectangle2D.Double r, String focus, HurricaneModel.Frame f, HurricaneModel m) {
        double R = m.radiusKm, pl = pulse(), top = Math.max(4, f.cloudTop), dx = r.width * 0.09, dy = r.height * 0.13;
        boolean guide = tour && !focus.isEmpty() && !focus.equals("none");
        double[] wc = painter.warmCorePeak();
        if (guide) {
            switch (focus) {
                case "eye" -> {
                    double[] a = painter.insideXY(0, top * 0.5);
                    label(g, a[0], a[1], a[0] - dx * 1.4, a[1] - dy * 1.6, focusName(focus),
                            f.eye > 0.5 ? (painter.eyeSink > 0.005 ? String.format("radius %.0f km · air sinking at %.2f m/s", f.eye, painter.eyeSink) : String.format("radius %.0f km", f.eye)) : "not formed yet", r, pl);
                }
                case "eyewall", "convection" -> {
                    double[] a = painter.insideXY(painter.maxUp > 0 ? painter.maxUpR : f.rmw, painter.maxUp > 0 ? painter.maxUpZ : top * 0.5);
                    label(g, a[0], a[1], a[0] + dx * 1.7, a[1] - dy * 0.2, focusName(focus), String.format("air rising at up to %.1f m/s", painter.maxUp), r, pl);
                }
                case "inflow", "sst" -> {
                    double[] a = painter.insideXY(focus.equals("sst") ? -R * 0.66 : R * 0.42, focus.equals("sst") ? -0.3 : 0.6);
                    if (focus.equals("sst")) label(g, a[0], a[1], a[0], a[1] - dy * 1.4, focusName(focus), Double.isNaN(f.sst) ? "" : String.format("%.1f °C", f.sst), r, pl);
                    else label(g, a[0], a[1], a[0] + dx * 0.6, a[1] - dy * 1.5, focusName(focus), String.format("toward the centre at up to %.0f m/s", painter.maxInflow), r, pl);
                }
                case "outflow" -> {
                    double[] a = painter.insideXY(Math.min(R * 0.55, f.rmw * 4 + 120), top * 0.88);
                    label(g, a[0], a[1], a[0] + dx * 0.8, a[1] - dy * 1.2, focusName(focus), String.format("away from the centre near %.0f km up", top * 0.88), r, pl);
                }
                case "rainbands" -> {
                    if (painter.bandR > 0) {
                        double[] a = painter.insideXY(painter.bandR, painter.bandTopZ * 0.6);
                        label(g, a[0], a[1], a[0] + dx * 1.3, a[1] - dy * 1.5, "Rainband", String.format("%.0f km from the centre", painter.bandR), r, pl);
                    }
                }
                case "warm_core" -> {
                    double[] a = painter.insideXY(0, wc[0]);
                    label(g, a[0], a[1], a[0] - dx * 1.6, a[1] - dy * 0.9, focusName(focus), String.format("+%.1f °C, %.0f km up", wc[1], wc[0]), r, pl);
                }
                case "surge" -> {
                    // the sea surface at the shore (a few km out, where the water is piled highest); under the centre while at sea
                    double ck = painter.coastKm(), sx = Double.isNaN(ck) ? 0 : ck + 4;
                    double[] a = painter.insideXY(sx, painter.surfaceKm(sx));
                    label(g, a[0], a[1], a[0] + dx * 1.6, a[1] - dy * 1.5, focusName(focus), String.format("%.1f m (drawn far taller than it is)", f.surge), r, pl);
                }
                case "decay" -> {
                    if (f.overLand) {
                        double[] a = painter.insideXY(-R * 0.6, 0.3);
                        label(g, a[0], a[1], a[0], a[1] - dy * 1.5, "Land", "the centre is over land", r, pl);
                    }
                }
                default -> { }
            }
            return;
        }
        if (!labels) return;
        if (f.eye > 0.5 && f.eyeClarity > 0.2) {
            double[] a = painter.insideXY(0, top * 0.42);
            label(g, a[0], a[1], a[0] - dx * 1.5, a[1] - dy * 0.4, "Eye", "", r, 0);
        }
        if (painter.maxUp > 0.3 && f.eye > 0.5 && f.eyeClarity > 0.2) {
            double[] a = painter.insideXY(painter.maxUpR, painter.maxUpZ);
            label(g, a[0], a[1], a[0] + dx * 1.6, a[1] + dy * 0.2, "Eyewall", String.format("rising %.1f m/s", painter.maxUp), r, 0);
        }
        if (painter.maxInflow > 0.5) {
            double[] a = painter.insideXY(R * 0.45, 0.6);
            label(g, a[0], a[1], a[0] + dx * 0.5, a[1] - dy * 1.4, "Inflow", "", r, 0);
        }
        if (painter.shieldKm() > f.rmw * 3 && f.organization > 0.4) {
            double[] a = painter.insideXY(-Math.min(R * 0.55, f.rmw * 4 + 120), top * 0.88);
            label(g, a[0], a[1], a[0] - dx * 0.6, a[1] - dy * 1.1, "Outflow", "", r, 0);
        }
        if (wc[1] > 2) {
            double[] a = painter.insideXY(0, wc[0]);
            label(g, a[0], a[1], a[0] - dx * 1.7, a[1] - dy * 1.5, "Warm core", String.format("+%.1f °C", wc[1]), r, 0);
        }
    }

    // ------------------------------------------------------------------ chrome (text, buttons, stages)

    static Color categoryColor(int cat) {
        return switch (cat) {
            case -2 -> new Color(132, 150, 178);
            case -1 -> new Color(104, 176, 226);
            case 0 -> new Color(98, 206, 160);
            case 1 -> new Color(250, 222, 112);
            case 2 -> new Color(255, 184, 84);
            case 3 -> new Color(255, 134, 72);
            case 4 -> new Color(240, 84, 94);
            default -> cat >= 5 ? new Color(214, 96, 224) : new Color(132, 150, 178);
        };
    }

    @Override void paint(Ui ui, int w, int h) {
        HurricaneModel m = model();
        HurricaneModel.Frame f = frame();
        store();
        topBar(ui, w, f);
        if (m == null || f == null) return;
        // the inset picture can be clicked to bring it forward
        if (curOut != null && curIn != null && curIn.intersects(curOut)) {
            Rectangle2D.Double small = outBig ? curIn : curOut;
            ui.hit(small.x, small.y, small.width, small.height, () -> view = outBig ? INSIDE : OUTSIDE);
        }
        stageStrip(ui, w, h, m, f);
        double tw = textW(w), x = w - tw - 12, y = 54, ph = h - y - 12 - (wideStrip(w) ? stripH(h) + 8 : 0);
        ui.fill(x, y, tw, ph, 14, new Color(10, 14, 24, 236));
        ui.stroke(x, y, tw, ph, 14, new Color(120, 180, 255, 70), 1f);
        double roH = readout(ui, x + 12, y + ph - 12, tw - 24, m, f, h < 660);
        double bodyBottom = y + ph - 12 - roH - 10;
        if (tour) tourPanel(ui, x, y, tw, bodyBottom, m);
        else explorePanel(ui, x, y, tw, bodyBottom, m, f);
    }

    private void topBar(Ui ui, int w, HurricaneModel.Frame f) {
        ui.text("HURRICANE FORMATION", 16, 33, Ui.F_HEAD, Color.WHITE);
        double x = 16 + ui.width("HURRICANE FORMATION", Ui.F_HEAD) + 20;
        boolean narrow = w < 1180;
        double vw = narrow ? 186 : 228;
        ui.seg(VIEW_NAMES, view, x, 12, vw, 30, k -> view = k);
        x += vw + 10;
        double mw = narrow ? 150 : 190;
        ui.seg(new String[]{"Guided tour", "Stages"}, tour ? 0 : 1, x, 12, mw, 30, k -> { if (k == 0) { if (!tour) enterChapter(0); } else explore(); });
        x += mw + 10;
        if (!narrow) {
            ui.button(labels ? "Labels on" : "Labels off", x, 12, 92, 30, labels ? Ui.NORMAL : Ui.GHOST, true, false, () -> labels = !labels);
            x += 102;
        }
        // right side: time controls and the way out
        double rx = w - 12;
        ui.button("Menu", rx - 70, 12, 70, 30, Ui.NORMAL, true, false, app::leaveNow);
        rx -= 80;
        boolean paused = f != null && f.paused;
        ui.button("+", rx - 28, 12, 28, 30, Ui.NORMAL, true, false, () -> changeSpeed(+1));
        String sp = "×" + trim(f != null ? f.speed : 1);
        ui.textCenter(sp, rx - 28 - 22, 32, Ui.F_BOLD, Ui.TEXT);
        ui.button("−", rx - 28 - 44 - 28, 12, 28, 30, Ui.NORMAL, true, false, () -> changeSpeed(-1));
        rx -= 28 + 44 + 28 + 8;
        ui.button(paused ? "Resume" : "Pause", rx - 78, 12, 78, 30, paused ? Ui.PRIMARY : Ui.NORMAL, true, false, this::togglePause);
    }

    private static String trim(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(Math.round(d * 100) / 100.0); }

    /** The ten stages as a row of chips under the pictures: click one and the storm glides there. */
    private void stageStrip(Ui ui, int w, int h, HurricaneModel m, HurricaneModel.Frame f) {
        Rectangle2D.Double p = pictures(w, h);
        if (wideStrip(w)) p = new Rectangle2D.Double(12, p.y, w - 24, p.height);
        int n = m.stages.size();
        if (n == 0) return;
        double sh = stripH(h), y = h - sh - 12, gap = 5, cw = (p.width - gap * (n - 1)) / n;
        boolean small = cw < 112;
        String shown = tour ? "" : selStage;
        for (int k = 0; k < n; k++) {
            HurricaneModel.Stage s = m.stages.get(k);
            double x = p.x + k * (cw + gap);
            boolean cur = s.id.equals(f.stage), sel = s.id.equals(shown), hov = ui.hover(x, y, cw, sh);
            Color cc = categoryColor(s.category);
            ui.fill(x, y, cw, sh, 10, cur ? new Color(34, 48, 80, 245) : hov ? new Color(28, 38, 62, 240) : new Color(16, 22, 38, 235));
            if (cur) {
                // how far through the stage the storm is
                java.awt.Shape clip = ui.g.getClip();
                ui.g.clip(new RoundRectangle2D.Double(x, y, cw, sh, 10, 10));
                ui.g.setColor(Renderer.alpha(cc, 46));
                ui.g.fill(new Rectangle2D.Double(x, y, cw * f.stageFrac, sh));
                ui.g.setClip(clip);
            }
            ui.fill(x + 8, y + 8, 5, sh - 16, 4, cc);
            ui.stroke(x, y, cw, sh, 10, sel ? Ui.ACCENT : cur ? new Color(255, 255, 255, 120) : new Color(255, 255, 255, 30), sel ? 1.6f : 1f);
            double tx = x + 20, twd = cw - 26;
            Font nf = small ? Ui.F_SMALL.deriveFont(Font.BOLD) : Ui.F_BOLD;
            List<String> nl = ui.wrap(s.name, nf, twd);
            int maxLines = 3;                                   // (three 14 px lines end 17 px above the bottom line even on the lower strip)
            double ty = y + 19;
            for (int q = 0; q < Math.min(maxLines, nl.size()); q++) {
                String line = q == maxLines - 1 && nl.size() > maxLines ? String.join(" ", nl.subList(q, nl.size())) : nl.get(q);
                ui.textFit(line, tx, ty, twd, nf, Color.WHITE);
                ty += 14;
            }
            // bottom line: the Saffir–Simpson category (hurricane stages only) and the wind range
            String cs = s.category >= 1 ? "Cat " + s.category : "";
            // a narrow chip has no room for both: the wind range stays whole and the category (already in the name and the colour bar) goes
            if (!cs.isEmpty() && ui.width(cs, Ui.F_SMALL.deriveFont(Font.BOLD)) + 8 + ui.width(s.windRange(), Ui.F_SMALL) > twd) cs = "";
            if (!cs.isEmpty()) ui.text(cs, tx, y + sh - 10, Ui.F_SMALL.deriveFont(Font.BOLD), cc);
            String wr = ui.fit(s.windRange(), Ui.F_SMALL, twd - (cs.isEmpty() ? 0 : ui.width(cs, Ui.F_SMALL) + 10));
            if (cs.isEmpty()) ui.text(wr, tx, y + sh - 10, Ui.F_SMALL, Ui.TEXT); else ui.textRight(wr, x + cw - 8, y + sh - 10, Ui.F_SMALL, Ui.TEXT);
            final String id = s.id;
            ui.hit(x, y, cw, sh, () -> pickStage(id));
        }
    }

    /** The live numbers, bottom of the text panel. {@code bottom} is the y of its lower edge; returns its height. */
    private double readout(Ui ui, double x, double bottom, double w, HurricaneModel m, HurricaneModel.Frame f, boolean compact) {
        int colsN = 2, rowsN = 3;
        double rowH = compact ? 28 : 36, hh = 24 + rowsN * rowH + 4, y = bottom - hh;
        ui.fill(x, y, w, hh, 12, new Color(18, 26, 44, 240));
        ui.stroke(x, y, w, hh, 12, new Color(255, 255, 255, 26), 1f);
        ui.text("RIGHT NOW", x + 12, y + 18, Ui.F_SMALL.deriveFont(Font.BOLD), Ui.ACCENT);
        HurricaneModel.Stage st = m.stage(f.stage);
        if (st != null) ui.textRight(ui.fit(st.name, Ui.F_SMALL, w - 110), x + w - 12, y + 18, Ui.F_SMALL, Ui.DIM);
        // the category in the simulation's own words: the name of the stage that carries this category
        String cat = "";
        for (HurricaneModel.Stage s : m.stages) if (s.category == f.category) { cat = s.name; break; }
        if (cat.isEmpty()) cat = String.valueOf(f.category);
        String[][] cells = {
                {"Category", cat},
                {"Max wind", String.format("%.0f kt · %.0f km/h", f.vmaxKt, f.vmaxKt * 1.852)},
                {"Central pressure", Double.isNaN(f.pressure) ? "—" : String.format("%.0f hPa", f.pressure)},
                // (the same test as the "Eye" label on the pictures: a radius is only quoted once there is an eye to see)
                {"Eye radius", f.eye > 0.5 && f.eyeClarity > 0.2 ? String.format("%.0f km", f.eye) : "no eye"},
                {"Storm surge", String.format("%.1f m", f.surge)},
                {"Sea surface", Double.isNaN(f.sst) ? "—" : String.format("%.1f °C", f.sst)}};
        double cw = (w - 24) / colsN;
        for (int k = 0; k < cells.length; k++) {
            double cx = x + 12 + (k % colsN) * cw, cy = y + 26 + (k / colsN) * rowH;
            ui.text(cells[k][0], cx, cy + (compact ? 8 : 10), Ui.F_SMALL, Ui.DIM);
            Color vc = k == 0 ? categoryColor(f.category) : Color.WHITE;
            String val = cells[k][1];
            Font vf = compact ? Ui.F_BOLD : Ui.F_MEDB;
            if (ui.width(val, vf) > cw - 8) {
                // too long for a narrow panel: first without its bracketed suffix ("(major)" is in the stage name above), then smaller
                int par = val.indexOf(" (");
                if (k == 0 && par > 0) val = val.substring(0, par);
                if (ui.width(val, vf) > cw - 8) vf = Ui.F_BOLD;
                if (ui.width(val, vf) > cw - 8) vf = Ui.F_SMALL.deriveFont(Font.BOLD);
            }
            ui.textFit(val, cx, cy + (compact ? 21 : 27), cw - 8, vf, vc);
        }
        return hh;
    }

    /** Wrapped body text that fits {@code maxH}: the larger font if it fits, else the smaller, else scrolled with the wheel. */
    private double body(Ui ui, String text, double x, double y, double w, double maxH) {
        Font f = Ui.F_MED;
        double lh = 19;
        List<String> lines = ui.wrap(text, f, w);
        if (lines.size() * lh > maxH) { f = Ui.F_NORM; lh = 17; lines = ui.wrap(text, f, w); }
        double total = lines.size() * lh;
        double scroll = total > maxH ? Math.min(textScroll, total - maxH) : 0;
        textScroll = scroll;
        java.awt.Shape clip = ui.g.getClip();
        ui.g.clip(new Rectangle2D.Double(x - 2, y - 2, w + 4, maxH + 4));
        double ty = y + 13 - scroll;
        for (String l : lines) { if (ty > y - 6 && ty < y + maxH + lh) ui.text(l, x, ty, f, Ui.TEXT); ty += lh; }
        ui.g.setClip(clip);
        if (total > maxH) {
            double th = Math.max(24, maxH * maxH / total), by = y + (maxH - th) * (scroll / (total - maxH));
            ui.fill(x + w + 4, y, 4, maxH, 4, new Color(255, 255, 255, 18));
            ui.fill(x + w + 4, by, 4, th, 4, new Color(255, 255, 255, 110));
        }
        return Math.min(total, maxH);
    }

    private void tourPanel(Ui ui, double x, double y, double w, double bottom, HurricaneModel m) {
        HurricaneModel.Chapter c = chapter(m);
        int n = m.lesson.size();
        double px = x + 16, pw = w - 32;
        ui.text("GUIDED TOUR", px, y + 24, Ui.F_SMALL.deriveFont(Font.BOLD), Ui.ACCENT);
        ui.textRight(n == 0 ? "" : (chapter + 1) + " / " + n, x + w - 16, y + 24, Ui.F_BOLD, Ui.DIM);
        if (c == null) { ui.text("The simulation sent no lesson.", px, y + 52, Ui.F_MED, Ui.DIM); return; }
        double ty = y + 50;
        for (String l : ui.wrap(c.title, Ui.F_BIG, pw)) { ui.text(l, px, ty, Ui.F_BIG, Color.WHITE); ty += 24; }
        // what the chapter looks at
        String fn = focusName(c.focus);
        HurricaneModel.Stage st = m.stage(c.stage);
        String tag = (c.view.equals("inside") ? "Inside view" : c.view.equals("outside") ? "Outside view" : "Both views") + (fn.isEmpty() ? "" : "  ·  " + fn)
                + (st == null ? "" : "  ·  " + st.name);
        ui.textFit(tag, px, ty - 4, pw, Ui.F_SMALL, Ui.BLUE);
        ty += 12;
        // controls sit at the bottom of the body: dots, then Back / Next, then Skip
        double btnY = bottom - 38, dotsY = btnY - 24;
        body(ui, c.text, px, ty, pw - 8, Math.max(40, dotsY - 14 - ty));
        if (n > 0) {
            double step = Math.min(18, pw / n), dx = px + (pw - step * (n - 1)) / 2;
            for (int k = 0; k < n; k++) {
                double cx = dx + k * step;
                boolean cur = k == chapter, hov = ui.hover(cx - step / 2, dotsY - 8, step, 16);
                double rr = cur ? 5 : hov ? 4.5 : 3.2;
                ui.g.setColor(cur ? Ui.ACCENT : k < chapter ? new Color(120, 180, 255, 220) : new Color(255, 255, 255, hov ? 150 : 70));
                ui.g.fill(new java.awt.geom.Ellipse2D.Double(cx - rr, dotsY - rr, rr * 2, rr * 2));
                final int idx = k;
                ui.hit(cx - step / 2, dotsY - 8, step, 16, () -> enterChapter(idx));
            }
        }
        boolean last = chapter >= n - 1;
        double bw = (pw - 16) / 3;
        ui.button("← Back", px, btnY, bw, 34, Ui.NORMAL, chapter > 0, false, this::back);
        ui.button(last ? "Explore the stages" : "Next →", px + bw + 8, btnY, bw * 1.25, 34, Ui.PRIMARY, true, true, this::next);
        ui.button("Skip", px + bw * 2.25 + 16, btnY, bw * 0.75, 34, Ui.GHOST, !last, false, this::explore);
    }

    private void explorePanel(Ui ui, double x, double y, double w, double bottom, HurricaneModel m, HurricaneModel.Frame f) {
        double px = x + 16, pw = w - 32;
        ui.text("STAGE EXPLORER", px, y + 24, Ui.F_SMALL.deriveFont(Font.BOLD), Ui.ACCENT);
        HurricaneModel.Stage s = m.stage(selStage.isEmpty() ? f.stage : selStage);
        if (s == null) s = m.stage(f.stage);
        if (s == null) { ui.text("Waiting for the simulation…", px, y + 52, Ui.F_MED, Ui.DIM); return; }
        int idx = m.stageIndex(s.id);
        ui.textRight((idx + 1) + " / " + m.stages.size(), x + w - 16, y + 24, Ui.F_BOLD, Ui.DIM);
        double ty = y + 50;
        for (String l : ui.wrap(s.name, Ui.F_BIG, pw)) { ui.text(l, px, ty, Ui.F_BIG, Color.WHITE); ty += 24; }
        Color cc = categoryColor(s.category);
        String facts = s.windRange() + (Double.isNaN(s.pressure) ? "" : String.format("  ·  about %.0f hPa", s.pressure)) + (s.category >= 1 ? "  ·  category " + s.category : "");
        ui.fill(px, ty - 12, 5, 14, 3, cc);
        ui.textFit(facts, px + 12, ty, pw - 12, Ui.F_BOLD, cc);
        ty += 12;
        if (!s.id.equals(f.stage)) {
            HurricaneModel.Stage now = m.stage(f.stage);
            ui.textFit("the storm is in: " + (now != null ? now.name : f.stage), px, ty + 6, pw, Ui.F_SMALL, Ui.AMBER);
            ty += 16;
        }
        double ctlY = bottom - 76;
        body(ui, s.summary, px, ty + 6, pw - 8, Math.max(40, ctlY - 14 - ty - 6));
        // auto-play, then replay
        ui.text("Auto-play", px, ctlY + 17, Ui.F_MED, Ui.TEXT);
        ui.toggle(f.auto, px + 74, ctlY, this::toggleAuto);
        ui.textFit(f.auto ? "running through every stage" : "off — pick a stage below", px + 130, ctlY + 17, pw - 130, Ui.F_SMALL, Ui.FAINT);
        double bw = (pw - 8) / 2;
        double wP = bw * 0.5 - 4, wN = bw * 0.5, wR = bw, xN = px + bw * 0.5, xR = px + bw + 8;
        double needP = ui.width("← Previous", Ui.F_MEDB) + 14, needN = ui.width("Next →", Ui.F_MEDB) + 14, needR = ui.width("Replay the tour", Ui.F_MEDB) + 14;
        if (wP < needP && needP + needN + needR + 12 <= pw) {
            // a narrow panel: share the row by what each label needs, so that none is cut off
            double extra = pw - 12 - needP - needN - needR;
            wP = needP + extra * 0.25; wN = needN + extra * 0.25; wR = needR + extra * 0.5;
            xN = px + wP + 4; xR = xN + wN + 8;
        }
        ui.button("← Previous", px, ctlY + 36, wP, 34, Ui.NORMAL, idx > 0, false, () -> stepStage(-1));
        ui.button("Next →", xN, ctlY + 36, wN, 34, Ui.NORMAL, idx < m.stages.size() - 1, false, () -> stepStage(+1));
        ui.button("Replay the tour", xR, ctlY + 36, wR, 34, Ui.PRIMARY, true, false, () -> enterChapter(0));
    }
}
