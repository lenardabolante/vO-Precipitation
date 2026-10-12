package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The main menu: the title and the four game modes on the left, the chosen mode's page on the
 * right. <b>Sandbox</b> is the list of saved simulations (most recently played first) with
 * Continue / New simulation; <b>Hurricane Formation</b> and <b>Cloud Formations</b> are the two
 * lesson modes, each with a short description and its start button; <b>World Simulation</b> (v2.3)
 * is the whole-Earth map whose locations open as sandbox worlds. Drawn over the live sky of the
 * menu's decorative world.
 */
final class MainMenuScreen extends Screen {
    private static final int CARD_H = 108, GAP = 8;
    static final int SANDBOX = 0, HURRICANE = 1, CLOUDS = 2, WORLD = 3;
    /** The hidden fifth mode: its row exists only once {@code settings.debugUnlocked} is set (ten clicks on the cloud in the upper left). */
    static final int DEBUG = 4;
    static final String DEBUG_NAME = "Debug", DEBUG_NOTE = "every cloud, seed, building, car and effect";
    static final String[] MODE_NAMES = {"Sandbox", "Hurricane Formation", "Cloud Formations", "World Simulation"};
    private static final String[] MODE_NOTES = {"your own worlds — new, continue, load", "guided tour, inside and outside, every stage",
            "the whole life of each cloud type", "the whole Earth — click a place to open it"};

    private final AppShell app;
    List<SaveStore.Slot> slots = new ArrayList<>();
    int selected = -1;
    /** The cloud type Cloud Formations starts with. */
    int cloudType;
    private double scroll;
    private double viewH = 400;
    private long lastClickAt;
    private int lastClickIdx = -1;

    MainMenuScreen(AppShell app) {
        this.app = app;
        // the tab shown last (settings.json); an unknown or missing id leaves the first type selected
        for (int k = 0; k < Model.CLOUD_TYPES.length; k++) if (Model.CLOUD_TYPES[k].equals(app.settings.showcaseType)) cloudType = k;
    }

    /** Choose the type Cloud Formations starts with, and remember it (written only when it changes). */
    void pickCloudType(int k) {
        cloudType = Math.floorMod(k, Model.CLOUD_TYPES.length);
        String id = Model.CLOUD_TYPES[cloudType];
        if (id.equals(app.settings.showcaseType)) return;
        app.settings.showcaseType = id;
        app.settingsChanged();
    }

    /**
     * The last line of a damaged slot's card. With the default saves folder it is "Folder: saves/&lt;id&gt;"; with another
     * one ({@code --saves-dir}) it names the real folder: relative to the game folder when it is inside it, else in full.
     */
    static String folderLabel(java.io.File root, java.io.File savesRoot, SaveStore.Slot s) {
        java.io.File def = new java.io.File(root, "saves").getAbsoluteFile(), real = savesRoot.getAbsoluteFile();
        if (def.toPath().normalize().equals(real.toPath().normalize())) return "Folder: saves/" + s.id;
        java.nio.file.Path dir = new java.io.File(real, s.id).toPath().normalize(), base = root.getAbsoluteFile().toPath().normalize();
        return "Folder: " + (dir.startsWith(base) ? base.relativize(dir).toString().replace('\\', '/') : dir.toString());
    }

    /** {@code label} cut at the front ("Folder: …/saves/id") when it is too wide: the end of a path is the part that says which folder. */
    private static String fitFolder(Ui ui, String label, java.awt.Font f, double maxW) {
        String head = "Folder: ";
        if (!label.startsWith(head) || ui.width(label, f) <= maxW) return label;
        String path = label.substring(head.length());
        for (int i = 1; i < path.length(); i++) {
            char c = path.charAt(i - 1);
            if (c != '/' && c != '\\') continue;                 // cut whole folders, never through a name
            String cut = head + "…" + c + path.substring(i);
            if (ui.width(cut, f) <= maxW) return cut;
        }
        return label;                                           // (textFit then cuts the end, as before)
    }

    @Override boolean dims() { return false; }

    /** Four modes, or five once the Debug mode is unlocked. */
    int modeCount() { return app.settings.debugUnlocked ? MODE_NAMES.length + 1 : MODE_NAMES.length; }

    int mode() { return Math.max(0, Math.min(modeCount() - 1, app.menuMode)); }

    void setMode(int m) { app.menuMode = Math.max(0, Math.min(modeCount() - 1, m)); }

    static String modeName(int k) { return k == DEBUG ? DEBUG_NAME : MODE_NAMES[k]; }

    private static String modeNote(int k) { return k == DEBUG ? DEBUG_NOTE : MODE_NOTES[k]; }

    // ---- the left column's geometry (also read by the tests: the cloud must never touch the title or the mode rows)

    static double leftW(int w) { return Model.clamp(w * 0.24, 270, 360); }

    static final double LEFT_X = 40;

    /** Baseline of the title. */
    static double titleY(int h) { return h >= 640 ? 104 : 64; }

    static float titleSize(int w) { return leftW(w) >= 340 ? 40 : 33; }

    /** The small cloud in the upper left: {x, y, w, h} of the drawing. The click area is this plus {@link #LOGO_PAD} all round. */
    static double[] logoRect(int w, int h) {
        return h >= 640 ? new double[]{LEFT_X + 2, 20, 62, 36} : new double[]{LEFT_X + 2, 5, 40, 22};
    }

    static final double LOGO_PAD = 3;

    /** Row {@code k} of the mode list: {x, y, w, h}. */
    static double[] modeRowRect(int w, int h, int k) {
        boolean roomy = h >= 640;
        double mh = roomy ? 62 : 52;
        return new double[]{LEFT_X, titleY(h) + (roomy ? 74 : 46) + 2 + k * (mh + 8), leftW(w), mh};
    }

    /** Take a fresh slot list; keeps the selection on {@code selectId} (or the first slot). */
    void setSlots(List<SaveStore.Slot> list, String selectId) {
        slots = list;
        selected = list.isEmpty() ? -1 : 0;
        for (int k = 0; k < list.size(); k++) if (list.get(k).id.equals(selectId)) selected = k;
        ensureVisible();
    }

    SaveStore.Slot sel() { return selected >= 0 && selected < slots.size() ? slots.get(selected) : null; }

    SaveStore.Slot latestPlayable() {
        for (SaveStore.Slot s : slots) if (s.playable()) return s;
        return null;
    }

    private void ensureVisible() {
        if (selected < 0) { scroll = 0; return; }
        double top = selected * (CARD_H + GAP), bottom = top + CARD_H;
        if (top < scroll) scroll = top;
        if (bottom > scroll + viewH) scroll = bottom - viewH;
        clampScroll();
    }

    private void clampScroll() {
        double content = slots.size() * (CARD_H + GAP) - GAP;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, content - viewH)));
    }

    // ------------------------------------------------------------------ actions

    private void play(SaveStore.Slot s) {
        if (s == null) return;
        if (!s.playable()) { app.toast(s.problem + " — it can only be deleted."); return; }
        app.startLoad(s);
    }

    private void newSimulation() {
        setMode(SANDBOX);
        app.openNewSimulation();
    }

    private void startCurrent() {
        switch (mode()) {
            case HURRICANE -> app.startHurricane();
            case WORLD -> app.startWorld();
            case DEBUG -> app.startDebug();
            case CLOUDS -> app.startShowcase(Model.CLOUD_TYPES[Math.max(0, Math.min(Model.CLOUD_TYPES.length - 1, cloudType))]);
            default -> { if (sel() != null) play(sel()); else app.openNewSimulation(); }
        }
    }

    private void rename(SaveStore.Slot s) {
        if (s == null || !s.playable()) return;
        app.push(new Screen.Prompt(app, "Rename simulation", "Name", s.name, "Rename", name -> {
            try {
                app.store.rename(s, name);
            } catch (java.io.IOException ex) {
                app.toast("Could not rename: " + ex.getMessage());
            }
            app.reloadSlots(s.id);
        }));
    }

    private void duplicate(SaveStore.Slot s) {
        if (s == null || !s.playable()) return;
        try {
            SaveStore.Slot d = app.store.duplicate(s);
            app.reloadSlots(d.id);
            app.toast("Duplicated as \"" + d.name + "\"");
        } catch (java.io.IOException ex) {
            app.toast("Could not duplicate: " + ex.getMessage());
            app.reloadSlots(s.id);
        }
    }

    private void delete(SaveStore.Slot s) {
        if (s == null) return;
        app.push(new Screen.Confirm(app, "Delete simulation?",
                "\"" + s.name + "\" will be deleted for good: its world, its history and its picture.\nThis cannot be undone.",
                "Delete", "Keep it", true, () -> {
            try {
                app.store.delete(s);
                app.toast("Deleted \"" + s.name + "\"");
            } catch (java.io.IOException ex) {
                app.toast("Could not delete: " + ex.getMessage());
            }
            app.reloadSlots(null);
        }, null));
    }

    private void quit() {
        app.push(new Screen.Confirm(app, "Quit Precipitation?", "Close the game and return to the desktop.", "Quit", "Stay", false, app::requestQuit, null));
    }

    // ------------------------------------------------------------------ input

    @Override void key(KeyEvent e) {
        boolean list = mode() == SANDBOX && !slots.isEmpty();
        switch (e.getKeyCode()) {
            case KeyEvent.VK_TAB -> setMode((mode() + (e.isShiftDown() ? modeCount() - 1 : 1)) % modeCount());
            case KeyEvent.VK_W -> setMode(WORLD);
            case KeyEvent.VK_H -> setMode(HURRICANE);
            case KeyEvent.VK_C -> setMode(CLOUDS);
            case KeyEvent.VK_S -> setMode(SANDBOX);
            case KeyEvent.VK_UP -> { if (list) { selected = Math.max(0, selected - 1); ensureVisible(); } }
            case KeyEvent.VK_DOWN -> { if (list) { selected = Math.min(slots.size() - 1, selected + 1); ensureVisible(); } }
            case KeyEvent.VK_HOME -> { if (list) { selected = 0; ensureVisible(); } }
            case KeyEvent.VK_END -> { if (list) { selected = slots.size() - 1; ensureVisible(); } }
            case KeyEvent.VK_PAGE_UP -> { if (list) { selected = Math.max(0, selected - 3); ensureVisible(); } }
            case KeyEvent.VK_PAGE_DOWN -> { if (list) { selected = Math.min(slots.size() - 1, selected + 3); ensureVisible(); } }
            case KeyEvent.VK_LEFT -> { if (mode() == CLOUDS) pickCloudType(cloudType - 1); }
            case KeyEvent.VK_RIGHT -> { if (mode() == CLOUDS) pickCloudType(cloudType + 1); }
            case KeyEvent.VK_ENTER -> startCurrent();
            case KeyEvent.VK_DELETE -> { if (mode() == SANDBOX) delete(sel()); }
            case KeyEvent.VK_F2 -> { if (mode() == SANDBOX) rename(sel()); }
            case KeyEvent.VK_N -> newSimulation();
            case KeyEvent.VK_ESCAPE -> quit();
            default -> { }
        }
    }

    @Override void wheel(int x, int y, double rotation) {
        scroll += rotation * 58;
        clampScroll();
    }

    // ------------------------------------------------------------------ painting

    @Override void paint(Ui ui, int w, int h) {
        java.awt.Graphics2D g = ui.g;
        // a soft vignette on the left keeps the title readable over any sky or picture
        g.setPaint(new GradientPaint(0, 0, new Color(4, 8, 18, 190), (float) Math.min(w, 720), 0, new Color(4, 8, 18, 40)));
        g.fill(new Rectangle2D.Double(0, 0, w, h));

        double leftW = leftW(w), lx = LEFT_X;
        boolean roomy = h >= 640;
        double ty = titleY(h);
        ui.g.setFont(Ui.F_TITLE);
        java.awt.Font title = Ui.F_TITLE.deriveFont(titleSize(w));
        paintLogo(ui, w, h);
        ui.text("PRECIPITATION", lx, ty, title, Color.WHITE);
        ui.text("clouds, rain, snow, ice & wind", lx + 2, ty + 24, Ui.F_MED, Ui.DIM);
        if (roomy) ui.text("over a living world", lx + 2, ty + 41, Ui.F_MED, Ui.DIM);

        // ---- the four modes
        double by = ty + (roomy ? 74 : 46), mh = roomy ? 62 : 52, mg = 8;
        ui.text("GAME MODE", lx + 2, by - 8, Ui.F_SMALL.deriveFont(java.awt.Font.BOLD), Ui.ACCENT);
        by += 2;
        for (int k = 0; k < modeCount(); k++) {
            modeRow(ui, k, lx, by, leftW, mh);
            by += mh + mg;
        }
        by += 8;
        double half = (leftW - 10) / 2, bh = roomy ? 40 : 34;
        ui.button("Settings", lx, by, half, bh, Ui.NORMAL, true, false, () -> app.openSettings(0));
        ui.button("Quit", lx + half + 10, by, half, bh, Ui.NORMAL, true, false, this::quit);
        by += bh + 24;
        if (h - by > 60) {
            String[][] hints = mode() == SANDBOX
                    ? new String[][]{{"↑ ↓", "choose a simulation"}, {"Enter", "play it"}, {"N", "new simulation"}, {"F2 / Del", "rename / delete"}, {"Tab", "next game mode"}, {"Esc", "quit"}}
                    : new String[][]{{"Enter", "start " + modeName(mode())}, {"Tab", "next game mode"}, {"S  H  C  W", "sandbox / hurricane / clouds / world"}, {"Esc", "quit"}};
            for (String[] r : hints) {
                if (by > h - 14) break;
                ui.text(r[0], lx + 2, by, Ui.F_BOLD, Ui.ACCENT);
                ui.text(r[1], lx + 70, by, Ui.F_NORM, Ui.DIM);
                by += 18;
            }
        }

        // ---- the page of the chosen mode
        double px = lx + leftW + 32, py = 32, pw = w - px - 32, ph = h - 64;
        ui.fill(px, py, pw, ph, 16, new Color(8, 12, 22, 205));
        ui.stroke(px, py, pw, ph, 16, new Color(120, 180, 255, 60), 1f);
        switch (mode()) {
            case HURRICANE -> hurricanePage(ui, px, py, pw, ph);
            case CLOUDS -> cloudsPage(ui, px, py, pw, ph);
            case WORLD -> worldPage(ui, px, py, pw, ph);
            case DEBUG -> debugPage(ui, px, py, pw, ph);
            default -> sandboxPage(ui, px, py, pw, ph);
        }
    }

    /**
     * The cloud in the upper left, drawn the simple way (filled circles, as the empty list's cloud is). It is also the
     * way into the hidden Debug mode: every click is counted by the shell ({@link AppShell#debugCloudClicked}); it has no
     * hover cue and no label, so nothing gives the mode away before it is unlocked.
     */
    private void paintLogo(Ui ui, int w, int h) {
        double[] r = logoRect(w, h);
        double s = r[2] / 62.0, x0 = r[0], y0 = r[1];
        java.awt.Graphics2D g = ui.g;
        // puffs: {centre x, centre y, radius} in a 62 x 36 box
        double[][] puffs = {{12, 23, 9}, {25, 16, 13}, {41, 15, 12}, {51, 23, 9}, {31, 24, 11}};
        g.setColor(new Color(150, 176, 210, 150));
        g.fill(new RoundRectangle2D.Double(x0 + 6 * s, y0 + 21.5 * s, 50 * s, 12.5 * s, 12 * s, 12 * s));
        for (double[] p : puffs) g.fill(new Ellipse2D.Double(x0 + (p[0] - p[2]) * s, y0 + (p[1] - p[2] + 2) * s, p[2] * 2 * s, p[2] * 2 * s));
        g.setColor(new Color(240, 245, 252, 235));
        g.fill(new RoundRectangle2D.Double(x0 + 5 * s, y0 + 20 * s, 52 * s, 12 * s, 12 * s, 12 * s));
        for (double[] p : puffs) g.fill(new Ellipse2D.Double(x0 + (p[0] - p[2]) * s, y0 + (p[1] - p[2]) * s, p[2] * 2 * s, p[2] * 2 * s));
        ui.hit(r[0] - LOGO_PAD, r[1] - LOGO_PAD, r[2] + 2 * LOGO_PAD, r[3] + 2 * LOGO_PAD, app::debugCloudClicked);
    }

    /** One of the big mode buttons. */
    private void modeRow(Ui ui, int k, double x, double y, double w, double h) {
        boolean sel = k == mode(), hov = ui.hover(x, y, w, h);
        ui.fill(x, y, w, h, 12, sel ? new Color(46, 92, 176, 245) : hov ? new Color(44, 58, 92, 240) : new Color(24, 32, 52, 232));
        ui.stroke(x, y, w, h, 12, sel ? Ui.ACCENT : new Color(255, 255, 255, 36), sel ? 1.6f : 1f);
        modeIcon(ui.g, k, x + 28, y + h / 2, Math.min(17, h * 0.3), sel ? Color.WHITE : Ui.TEXT);
        ui.textFit(modeName(k), x + 56, y + h / 2 - 2, w - 64, Ui.F_HEAD, Color.WHITE);
        ui.textFit(modeNote(k), x + 56, y + h / 2 + 15, w - 64, Ui.F_SMALL, sel ? new Color(214, 228, 250) : Ui.DIM);
        final int idx = k;
        ui.hit(x, y, w, h, () -> setMode(idx));
    }

    /** Small line drawings: a landscape with a cloud, a spiral, a row of clouds, a globe. */
    private static void modeIcon(java.awt.Graphics2D g, int k, double cx, double cy, double r, Color c) {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (k) {
            case HURRICANE -> {
                for (int arm = 0; arm < 2; arm++) {
                    Path2D.Double p = new Path2D.Double();
                    for (int i = 0; i <= 24; i++) {
                        // trailing arms of a counter-clockwise turning spiral
                        double a = arm * Math.PI - i * 0.17, rr = r * (0.28 + 0.03 * i);
                        double x = cx + Math.cos(a) * rr, y = cy - Math.sin(a) * rr;
                        if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                    }
                    g.draw(p);
                }
                g.draw(new Ellipse2D.Double(cx - r * 0.2, cy - r * 0.2, r * 0.4, r * 0.4));
            }
            case WORLD -> {
                g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
                g.draw(new Ellipse2D.Double(cx - r * 0.45, cy - r, r * 0.9, 2 * r));
                g.draw(new java.awt.geom.Line2D.Double(cx - r, cy, cx + r, cy));
                g.draw(new java.awt.geom.QuadCurve2D.Double(cx - r * 0.84, cy - r * 0.52, cx, cy - r * 0.34, cx + r * 0.84, cy - r * 0.52));
                g.draw(new java.awt.geom.QuadCurve2D.Double(cx - r * 0.84, cy + r * 0.52, cx, cy + r * 0.34, cx + r * 0.84, cy + r * 0.52));
            }
            case DEBUG -> {
                // a cloud with a bolt under it
                double[][] puffs = {{-0.55, -0.05, 0.40}, {-0.1, -0.38, 0.52}, {0.48, -0.10, 0.42}};
                for (double[] p : puffs) g.fill(new Ellipse2D.Double(cx + (p[0] - p[2]) * r, cy + (p[1] - p[2]) * r - r * 0.15, p[2] * 2 * r, p[2] * 2 * r));
                Path2D.Double b = new Path2D.Double();
                b.moveTo(cx + r * 0.12, cy + r * 0.18); b.lineTo(cx - r * 0.22, cy + r * 0.66); b.lineTo(cx + r * 0.06, cy + r * 0.66); b.lineTo(cx - r * 0.12, cy + r * 1.12);
                g.setColor(Ui.ACCENT);
                g.draw(b);
            }
            case CLOUDS -> {
                double[][] puffs = {{-0.55, 0.25, 0.42}, {-0.1, -0.1, 0.55}, {0.48, 0.2, 0.44}};
                for (double[] p : puffs) g.fill(new Ellipse2D.Double(cx + (p[0] - p[2]) * r, cy + (p[1] - p[2]) * r - r * 0.15, p[2] * 2 * r, p[2] * 2 * r));
                for (int i = -1; i <= 1; i++) g.draw(new java.awt.geom.Line2D.Double(cx + i * r * 0.5, cy + r * 0.7, cx + i * r * 0.5 - r * 0.15, cy + r * 1.05));
            }
            default -> {
                Path2D.Double p = new Path2D.Double();
                p.moveTo(cx - r, cy + r * 0.8); p.lineTo(cx - r * 0.35, cy - r * 0.1); p.lineTo(cx + r * 0.05, cy + r * 0.4); p.lineTo(cx + r * 0.5, cy - r * 0.35); p.lineTo(cx + r, cy + r * 0.8);
                p.closePath();
                g.draw(p);
                g.fill(new Ellipse2D.Double(cx - r * 0.9, cy - r * 1.0, r * 0.55, r * 0.55));
            }
        }
    }

    // ---- Sandbox: the saved simulations

    private void sandboxPage(Ui ui, double px, double py, double pw, double ph) {
        java.awt.Graphics2D g = ui.g;
        ui.text("Your simulations", px + 20, py + 32, Ui.F_BIG, Color.WHITE);
        String count = slots.isEmpty() ? "" : slots.size() + (slots.size() == 1 ? " simulation" : " simulations") + "  ·  most recently played first";
        ui.textRight(count, px + pw - 20, py + 31, Ui.F_NORM, Ui.DIM);
        // Continue / New simulation
        SaveStore.Slot latest = latestPlayable();
        double bx = px + 16, bwid = pw - 32, by = py + 46, nw = Math.min(230, bwid * 0.34);
        ui.button(latest != null ? "Continue  ·  " + latest.name : "Continue", bx, by, bwid - nw - 10, 40, Ui.PRIMARY, latest != null, false, () -> play(latestPlayable()));
        ui.button("New simulation", bx + bwid - nw, by, nw, 40, latest == null ? Ui.PRIMARY : Ui.NORMAL, true, false, this::newSimulation);

        double vx = px + 12, vy = py + 98, vw = pw - 24;
        viewH = ph - 110;
        if (slots.isEmpty()) {
            paintEmpty(ui, vx, vy, vw, viewH);
            return;
        }
        clampScroll();
        double content = slots.size() * (CARD_H + GAP) - GAP;
        boolean bar = content > viewH + 0.5;
        double cw = bar ? vw - 12 : vw;
        Shape clip = g.getClip();
        g.clip(new Rectangle2D.Double(vx - 2, vy, vw + 4, viewH));
        long now = System.currentTimeMillis();
        for (int k = 0; k < slots.size(); k++) {
            double cy = vy + k * (CARD_H + GAP) - scroll;
            if (cy + CARD_H < vy || cy > vy + viewH) continue;
            paintCard(ui, slots.get(k), k, vx, cy, cw, vy, vy + viewH, now);
        }
        g.setClip(clip);
        if (bar) {
            double th = Math.max(30, viewH * viewH / content), tyy = vy + (viewH - th) * (scroll / (content - viewH));
            ui.fill(vx + vw - 6, vy, 6, viewH, 6, new Color(255, 255, 255, 18));
            ui.fill(vx + vw - 6, tyy, 6, th, 6, new Color(255, 255, 255, 90));
        }
    }

    // ---- the two lesson modes

    private void hurricanePage(Ui ui, double px, double py, double pw, double ph) {
        double x = px + 28, y = py + 46, w = pw - 56;
        ui.text("Hurricane Formation", x, y, Ui.F_BIG.deriveFont(24f), Color.WHITE);
        y += 26;
        for (String l : ui.wrap("A guided tour of how a hurricane works, told by the simulation in text and pictures, followed by a stage explorer.", Ui.F_MED, Math.min(w, 640))) {
            ui.text(l, x, y, Ui.F_MED, Ui.TEXT);
            y += 19;
        }
        y += 14;
        String[][] points = {
                {"Guided tour", "chapter by chapter, with Next / Back; the pictures follow the text and point at what it talks about"},
                {"Outside", "the storm from above, like a satellite picture: cloud shield, spiral rainbands, the eye, the wind spiralling in"},
                {"Inside", "a cross-section through the centre: inflow, the rising eyewall, outflow aloft, the warm core, the surge"},
                {"Stages", "afterwards, jump to any of the stages from first disturbance to dissipation, or let it play through"}};
        for (String[] p : points) {
            if (y > py + ph - 96) break;
            ui.g.setColor(Ui.ACCENT);
            ui.g.fill(new Ellipse2D.Double(x + 2, y - 9, 7, 7));
            ui.text(p[0], x + 18, y, Ui.F_MEDB, Color.WHITE);
            for (String l : ui.wrap(p[1], Ui.F_NORM, Math.min(w - 110, 760))) { ui.text(l, x + 110, y, Ui.F_NORM, Ui.DIM); y += 17; }
            y += 9;
        }
        double by = py + ph - 76;
        ui.button("Start Hurricane Formation", x, by, Math.min(300, w), 46, Ui.PRIMARY, true, true, app::startHurricane);
        ui.textFit("Not saved — it starts fresh every time. Esc brings you back here.", x + Math.min(300, w) + 18, by + 28, w - 330, Ui.F_SMALL, Ui.FAINT);
    }

    private void cloudsPage(Ui ui, double px, double py, double pw, double ph) {
        double x = px + 28, y = py + 46, w = pw - 56;
        ui.text("Cloud Formations", x, y, Ui.F_BIG.deriveFont(24f), Color.WHITE);
        y += 26;
        for (String l : ui.wrap("One cloud at a time on a small stage: watch each type form, grow, rain or snow (if it does) and dissolve, "
                + "with the wind field, streamlines and its life cycle spelled out.", Ui.F_MED, Math.min(w, 640))) {
            ui.text(l, x, y, Ui.F_MED, Ui.TEXT);
            y += 19;
        }
        y += 18;
        ui.text("Start with", x, y, Ui.F_SMALL.deriveFont(java.awt.Font.BOLD), Ui.ACCENT);
        y += 10;
        int n = Model.CLOUD_TYPES.length, perRow = w >= 640 ? 4 : 2;
        double gap = 8, cw = Math.min(190, (w - gap * (perRow - 1)) / perRow), ch = 46;
        for (int k = 0; k < n; k++) {
            double cx = x + (k % perRow) * (cw + gap), cy = y + (k / perRow) * (ch + gap);
            if (cy + ch > py + ph - 90) break;
            boolean sel = k == cloudType, hov = ui.hover(cx, cy, cw, ch);
            ui.fill(cx, cy, cw, ch, 10, sel ? new Color(46, 92, 176, 245) : hov ? new Color(44, 58, 92, 240) : new Color(24, 32, 52, 232));
            ui.stroke(cx, cy, cw, ch, 10, sel ? Ui.ACCENT : new Color(255, 255, 255, 36), sel ? 1.5f : 1f);
            ui.text(Model.CLOUD_PREFIX[k], cx + 12, cy + 20, Ui.F_MEDB, Ui.BLUE);
            String nm = Model.CLOUD_TYPES[k];
            ui.textFit(Character.toUpperCase(nm.charAt(0)) + nm.substring(1), cx + 38, cy + 20, cw - 46, Ui.F_MEDB, Color.WHITE);
            ui.textFit(Model.CLOUD_NOTE[k], cx + 12, cy + 37, cw - 20, Ui.F_SMALL, sel ? new Color(214, 228, 250) : Ui.DIM);
            final int idx = k;
            ui.hit(cx, cy, cw, ch, () -> pickCloudType(idx));
        }
        double by = py + ph - 76;
        ui.button("Start Cloud Formations", x, by, Math.min(300, w), 46, Ui.PRIMARY, true, true, this::startCurrent);
        ui.textFit("Every type has its own tab once inside. Not saved. Esc brings you back here.", x + Math.min(300, w) + 18, by + 28, w - 330, Ui.F_SMALL, Ui.FAINT);
    }

    // ---- Debug (hidden until unlocked)

    private void debugPage(Ui ui, double px, double py, double pw, double ph) {
        double x = px + 28, y = py + 46, w = pw - 56;
        ui.text("Debug", x, y, Ui.F_BIG.deriveFont(24f), Color.WHITE);
        y += 26;
        for (String l : ui.wrap("Everything the game can draw, one thing at a time, on the game's own painters: what you see here is what the game shows. "
                + "Nothing is simulated by Python in this mode and nothing is saved.", Ui.F_MED, Math.min(w, 660))) {
            ui.text(l, x, y, Ui.F_MED, Ui.TEXT);
            y += 19;
        }
        y += 14;
        String[][] points = {
                {"Clouds", "all nine types; change the seed, scrub or play the life cycle, see the forming and dissipating steps side by side and export them as PNGs"},
                {"Lightning", "strike on click, repeat, day or night, with the Epilepsy-safe rendering beside the normal one"},
                {"Buildings", "every building kind, house variant and tree, intact and storm-damaged, by day and with lit windows at night"},
                {"Cars", "every variant, both directions, headlights, wipers, thrown and wrecked"},
                {"Weather", "rain, snow, mixed, graupel, the tornado at EF0 to EF5, merging clouds, the supercell, wind and ground water"},
                {"World", "time of day, month, biomes and region presets, backdrop picture, under-cloud darkening"},
                {"Hurricane, World map", "every hurricane stage in both views and the world map's layers, on the built-in stand-ins"}};
        for (String[] p : points) {
            if (y > py + ph - 96) break;
            ui.g.setColor(Ui.ACCENT);
            ui.g.fill(new Ellipse2D.Double(x + 2, y - 9, 7, 7));
            ui.text(p[0], x + 18, y, Ui.F_MEDB, Color.WHITE);
            for (String l : ui.wrap(p[1], Ui.F_NORM, Math.min(w - 170, 760))) { ui.text(l, x + 170, y, Ui.F_NORM, Ui.DIM); y += 17; }
            y += 7;
        }
        double by = py + ph - 76;
        ui.button("Start Debug", x, by, Math.min(300, w), 46, Ui.PRIMARY, true, true, app::startDebug);
        ui.textFit("Not saved. Esc brings you back here.", x + Math.min(300, w) + 18, by + 28, w - 330, Ui.F_SMALL, Ui.FAINT);
    }

    // ---- World Simulation (v2.3)

    private BufferedImage worldPreview;

    /** A small picture of the map with the location dots (drawn once per size from the viewer's own map files). */
    private BufferedImage worldPreview(int w, int h) {
        if (worldPreview != null && worldPreview.getWidth() == w && worldPreview.getHeight() == h) return worldPreview;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(22, 58, 104), 0, h, new Color(12, 30, 60)));
        g.fillRect(0, 0, w, h);
        WorldMap map = WorldMap.get(app.root);
        double s = Math.min(w / 360.0, h / 180.0);
        java.awt.geom.AffineTransform at = new java.awt.geom.AffineTransform(s, 0, 0, s, w / 2.0, h / 2.0);
        g.setColor(new Color(255, 255, 255, 18));
        g.setStroke(new BasicStroke(1f));
        for (int lon = -150; lon <= 150; lon += 30) g.draw(new java.awt.geom.Line2D.Double(w / 2.0 + lon * s, h / 2.0 - 90 * s, w / 2.0 + lon * s, h / 2.0 + 90 * s));
        for (int lat = -60; lat <= 60; lat += 30) g.draw(new java.awt.geom.Line2D.Double(w / 2.0 - 180 * s, h / 2.0 - lat * s, w / 2.0 + 180 * s, h / 2.0 - lat * s));
        g.setColor(new Color(96, 132, 104));
        g.fill(at.createTransformedShape(map.landShape));
        g.setColor(new Color(10, 18, 34, 120));
        g.setStroke(new BasicStroke(0.7f));
        g.draw(at.createTransformedShape(map.countryLines));
        g.setColor(new Color(200, 222, 206, 170));
        g.setStroke(new BasicStroke(0.9f));
        g.draw(at.createTransformedShape(map.coastLines));
        for (Preset.Loc l : Preset.LOCS) {
            if (!l.onMap()) continue;
            double x = w / 2.0 + l.lon * s, y = h / 2.0 - l.lat * s;
            g.setColor(new Color(255, 226, 140, 70));
            g.fill(new Ellipse2D.Double(x - 7, y - 7, 14, 14));
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(x - 4, y - 4, 8, 8));
            g.setColor(new Color(255, 170, 70));
            g.fill(new Ellipse2D.Double(x - 2.8, y - 2.8, 5.6, 5.6));
        }
        g.dispose();
        worldPreview = img;
        return img;
    }

    private void worldPage(Ui ui, double px, double py, double pw, double ph) {
        double x = px + 28, y = py + 46, w = pw - 56;
        ui.text("World Simulation", x, y, Ui.F_BIG.deriveFont(24f), Color.WHITE);
        y += 26;
        for (String l : ui.wrap("The whole Earth on one map: temperature, clouds, rain, wind, pressure and snow moving with the day and the seasons. "
                + "Every dot is a real place with its own terrain and climate; click one and it opens as a sandbox world, in the season and at the time of day the map shows.",
                Ui.F_MED, Math.min(w, 700))) {
            ui.text(l, x, y, Ui.F_MED, Ui.TEXT);
            y += 19;
        }
        y += 10;
        int n = 0;
        for (Preset.Loc l : Preset.LOCS) if (l.onMap()) n++;
        double by = py + ph - 76;
        String[][] points = {
                {"Layers", "temperature, cloud cover, precipitation, wind, pressure with isobars, snow — one key each"},
                {"Day and season", "the night side crosses the map; jump three months or one hour, run up to 64 times faster"},
                {"Locations", n + " places with real data: click a dot, a country or a US state for its card, then open it"}};
        // the map preview takes the room that is left, at the map's own 2 : 1; the three points go beside it, or under it
        double availH = by - 16 - y;
        double mh = Math.min(availH, w / 2), mw = mh * 2;
        boolean beside = w - mw - 24 >= 230;
        if (!beside) { mh = Math.min(availH - 3 * 22 - 12, w / 2); mw = mh * 2; }
        if (mh >= 90) {
            BufferedImage img = worldPreview((int) Math.round(mw), (int) Math.round(mh));
            Shape clip = ui.g.getClip();
            ui.g.clip(new RoundRectangle2D.Double(x, y, mw, mh, 14, 14));
            ui.g.drawImage(img, (int) Math.round(x), (int) Math.round(y), null);
            ui.g.setClip(clip);
            ui.stroke(x, y, mw, mh, 14, new Color(120, 180, 255, 90), 1f);
            ui.hit(x, y, mw, mh, app::startWorld);
        } else { mh = 0; mw = 0; beside = false; }
        if (beside) {
            double tx = x + mw + 24, tw = x + w - tx, ty = y + 14;
            for (String[] p : points) {
                ui.g.setColor(Ui.ACCENT);
                ui.g.fill(new Ellipse2D.Double(tx, ty - 9, 7, 7));
                ui.text(p[0], tx + 16, ty, Ui.F_MEDB, Color.WHITE);
                ty += 18;
                for (String l : ui.wrap(p[1], Ui.F_NORM, tw - 16)) { ui.text(l, tx + 16, ty, Ui.F_NORM, Ui.DIM); ty += 17; }
                ty += 10;
            }
        } else {
            double ty = y + (mh > 0 ? mh + 22 : 8);
            for (String[] p : points) {
                if (ty > by - 10) break;
                ui.g.setColor(Ui.ACCENT);
                ui.g.fill(new Ellipse2D.Double(x + 2, ty - 9, 7, 7));
                ui.text(p[0], x + 18, ty, Ui.F_MEDB, Color.WHITE);
                ui.textFit(p[1], x + 130, ty, w - 130, Ui.F_NORM, Ui.DIM);
                ty += 22;
            }
        }
        ui.button("Start World Simulation", x, by, Math.min(300, w), 46, Ui.PRIMARY, true, true, app::startWorld);
        ui.textFit("Not saved itself — the places you open from it are. Esc brings you back here.", x + Math.min(300, w) + 18, by + 28, w - 330, Ui.F_SMALL, Ui.FAINT);
    }

    private void paintEmpty(Ui ui, double x, double y, double w, double h) {
        double cx = x + w / 2, cy = y + h / 2 - 20;
        java.awt.Graphics2D g = ui.g;
        // a little cloud, drawn the simple way
        g.setColor(new Color(236, 242, 250, 210));
        double[][] puffs = {{-44, 8, 26}, {-14, -10, 34}, {22, -2, 30}, {50, 12, 22}, {4, 14, 30}};
        for (double[] p : puffs) g.fill(new Ellipse2D.Double(cx + p[0] - p[2], cy - 86 + p[1] - p[2], p[2] * 2, p[2] * 2));
        g.setColor(new Color(150, 200, 255, 220));
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int k = -2; k <= 2; k++) g.draw(new java.awt.geom.Line2D.Double(cx + k * 20, cy - 44, cx + k * 20 - 5, cy - 30));
        ui.textCenter("No simulations yet", cx, cy + 8, Ui.F_BIG, Color.WHITE);
        ui.textCenter("Every world is grown from a seed: its hills, its towns and every single cloud are one of a kind.", cx, cy + 34, Ui.F_MED, Ui.DIM);
        ui.textCenter("Start one — it is saved automatically and will be listed here.", cx, cy + 53, Ui.F_MED, Ui.DIM);
        ui.button("Create your first simulation", cx - 150, cy + 78, 300, 46, Ui.PRIMARY, true, true, this::newSimulation);
    }

    private void paintCard(Ui ui, SaveStore.Slot s, int idx, double x, double y, double w, double clipY0, double clipY1, long now) {
        java.awt.Graphics2D g = ui.g;
        boolean sel = idx == selected;
        double hy0 = Math.max(y, clipY0), hy1 = Math.min(y + CARD_H, clipY1);
        boolean hov = ui.hover(x, hy0, w, hy1 - hy0);
        ui.fill(x, y, w, CARD_H, 12, sel ? new Color(40, 58, 96, 235) : hov ? new Color(30, 40, 64, 225) : new Color(20, 27, 44, 215));
        ui.stroke(x, y, w, CARD_H, 12, sel ? Ui.ACCENT : new Color(255, 255, 255, 26), sel ? 1.5f : 1f);
        ui.hit(x, hy0, w, hy1 - hy0, () -> {
            long t = System.currentTimeMillis();
            boolean dbl = lastClickIdx == idx && t - lastClickAt < 420;
            lastClickIdx = idx;
            lastClickAt = t;
            selected = idx;
            ensureVisible();
            if (dbl) play(s);
        });

        // thumbnail
        double tx = x + 9, tyy = y + 9, tw = 160, th = 90;
        Shape clip = g.getClip();
        g.clip(new RoundRectangle2D.Double(tx, tyy, tw, th, 8, 8));
        BufferedImage img = s.thumb;
        if (img != null) {
            Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, (int) Math.round(tx), (int) Math.round(tyy), (int) tw, (int) th, null);
            if (old != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old);
        } else {
            g.setPaint(new GradientPaint(0, (float) tyy, new Color(46, 66, 104), 0, (float) (tyy + th), new Color(24, 32, 52)));
            g.fill(new Rectangle2D.Double(tx, tyy, tw, th));
            ui.textCenter(s.playable() ? "no preview" : "?", tx + tw / 2, tyy + th / 2 + 4, Ui.F_NORM, Ui.FAINT);
        }
        if (!s.playable()) {
            g.setColor(new Color(40, 10, 14, 150));
            g.fill(new Rectangle2D.Double(tx, tyy, tw, th));
        }
        g.setClip(clip);
        ui.stroke(tx, tyy, tw, th, 8, new Color(255, 255, 255, 40), 1f);

        // text
        boolean buttons = sel && y >= clipY0 - 1 && y + CARD_H <= clipY1 + 1;
        double bx = x + w - 204, textX = x + 183, textW = (sel ? bx : x + w) - textX - 12;
        ui.textFit(s.name, textX, y + 27, textW, Ui.F_HEAD, Color.WHITE);
        if (s.playable()) {
            String size = sizeName(s.worldW, s.worldH);
            // a world built from a region preset says which region instead of a terrain kind
            String land = s.presetId.isEmpty() ? s.terrain : (s.presetName.isEmpty() ? Preset.nameOf(s.presetId) : s.presetName) + " (real region)";
            ui.textFit("Seed " + s.seed + "  ·  " + size + "  ·  " + land + (s.settlements.isEmpty() ? "" : "  ·  " + settlementsText(s)),
                    textX, y + 48, textW, Ui.F_NORM, Ui.TEXT);
            ui.textFit("Simulated " + SaveStore.duration(s.simT) + "  ·  played " + SaveStore.duration(s.playSeconds) + "  ·  last played " + SaveStore.ago(s.lastPlayed, now),
                    textX, y + 68, textW, Ui.F_NORM, Ui.DIM);
            ui.textFit(String.format("Clouds: %d alive · %d born · %d gone   ·   rain %.1f mm · snow %.1f mm", s.alive, s.born, s.dead, s.rainMm, s.snowMm)
                    + (s.usingBackup ? "   ·   restored from backup" : ""), textX, y + 88, textW, Ui.F_NORM, s.usingBackup ? Ui.AMBER : Ui.DIM);
        } else {
            ui.textFit(s.problem, textX, y + 48, textW, Ui.F_BOLD, Ui.RED);
            ui.textFit("This simulation cannot be opened. You can delete it to tidy the list.", textX, y + 68, textW, Ui.F_NORM, Ui.DIM);
            ui.textFit(fitFolder(ui, folderLabel(app.root, app.store.root, s), Ui.F_NORM, textW), textX, y + 88, textW, Ui.F_NORM, Ui.FAINT);
        }
        if (!buttons) return;
        double bw = 94, bh = 32;
        if (s.playable()) {
            ui.button("Play", bx, y + 18, bw, bh, Ui.PRIMARY, true, false, () -> play(s));
            ui.button("Rename", bx + bw + 8, y + 18, bw, bh, Ui.NORMAL, true, false, () -> rename(s));
            ui.button("Duplicate", bx, y + 58, bw, bh, Ui.NORMAL, true, false, () -> duplicate(s));
            ui.button("Delete", bx + bw + 8, y + 58, bw, bh, Ui.DANGER, true, false, () -> delete(s));
        } else {
            ui.button("Delete", bx + bw + 8, y + 38, bw, bh, Ui.DANGER, true, false, () -> delete(s));
        }
    }

    static String sizeName(int w, int h) {
        for (int k = 0; k < Settings.SIZES.length; k++)
            if (Settings.SIZES[k][0] == w && Settings.SIZES[k][1] == h) return Settings.SIZE_NAMES[k] + " " + w + "×" + h;
        return w + "×" + h;
    }

    private static String settlementsText(SaveStore.Slot s) {
        if (s.settlements.equals("none")) return "no settlements";
        return s.settlementCount > 0 ? s.settlementCount + (s.settlementCount == 1 ? " settlement" : " settlements") + " (" + s.settlements + ")" : s.settlements + " settlements";
    }
}
