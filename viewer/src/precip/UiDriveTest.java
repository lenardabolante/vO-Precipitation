package precip;

import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;

/**
 * {@code --selftest-ui} (also the first part of {@code --selftest}): drives the app shell the way
 * a player would — key presses, typed characters, mouse clicks and the wheel are fed to the game
 * panel's real listeners and the panel is painted off screen in between — without opening a window
 * and without Python (the built-in fake simulation stands in). It checks that input reaches the
 * right place: menus swallow it, a running game gets it, the edge tabs and panel keys slide the
 * panels, rebinding works from the Controls page, and a simulation that cannot start ends in the
 * in-window error with a way back to the menu.
 * <p>
 * Everything is written under a throw-away folder in the system temp directory.
 */
final class UiDriveTest {
    private static final int W = 1680, H = 760;

    private final File tmp;
    private final Settings settings;
    private final AppShell app;
    private final List<String> failures = new ArrayList<>();
    private final BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    private int step;

    private UiDriveTest(File tmp) {
        this.tmp = tmp;
        settings = new Settings(new File(tmp, "config"));     // real file round trip, but in the temp folder
        app = new AppShell(tmp, settings, new SaveStore(new File(tmp, "saves")));
    }

    static int run() {
        File tmp;
        try {
            tmp = Files.createTempDirectory("precip-uitest").toFile();
        } catch (java.io.IOException ex) {
            System.out.println("[ui-test] cannot create a temp folder: " + ex.getMessage());
            return 1;
        }
        UiDriveTest t = new UiDriveTest(tmp);
        try {
            t.go();
        } catch (Throwable ex) {
            ex.printStackTrace();
            t.failures.add("exception: " + ex);
        } finally {
            try {
                edt(() -> { if (t.app.session != null) t.app.leaveNow(); });
            } catch (RuntimeException ignored) { }
            t.app.awaitStops();
            edt(() -> { if (t.app.panel != null) t.app.panel.dispose(); });
            t.app.io.shutdown();
            deleteTree(tmp);
        }
        if (t.failures.isEmpty()) {
            System.out.println("[ui-test] PASS (" + t.step + " checks)");
            return 0;
        }
        System.out.println("[ui-test] FAIL");
        for (String f : t.failures) System.out.println("[ui-test]   - " + f);
        return 1;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    // ------------------------------------------------------------------ driving

    private void check(boolean ok, String what) {
        step++;
        System.out.println(String.format("[ui-test] %2d  %s  %s", step, ok ? "ok  " : "FAIL", what));
        if (!ok) failures.add(what);
    }

    /** v2.3: the climate keys and buttons reach the simulation, the Climate panel toggles, a click seeds the chosen cloud type. */
    private void sandboxV23(ViewerPanel gp, Renderer r) throws InterruptedException {
        final ViewerState st = app.session.state;
        frames(3);
        final Model.Frame f0;
        synchronized (st) { f0 = st.cur; }
        boolean climate = f0.atmosphere != null && f0.temp != null && f0.hasCalendar() && r.climateRect != null && r.climateRows != null;
        check(climate, "the Climate panel is on screen for a simulation that sends the v2.3 climate"
                + (climate ? String.format(" (%s, %.1f °C = base %.1f + season %.1f + day %.1f + gas %.1f + you %.1f)", f0.season, f0.surfaceTemp, f0.temp.baseC, f0.temp.seasonC,
                f0.temp.diurnalC, f0.temp.gasC, f0.temp.userC) : ""));
        if (!climate) return;
        final double hum0 = f0.atmosphere.humidity, co0 = f0.atmosphere.co2, aer0 = f0.atmosphere.aerosol, month0 = f0.month, tod0 = f0.timeOfDay;
        key(KeyEvent.VK_2, '2');
        boolean hum = until(() -> { synchronized (st) { return st.cur.atmosphere.humidity > hum0 + 0.04; } }, 3000);
        key(KeyEvent.VK_4, '4');
        boolean co2 = until(() -> { synchronized (st) { return st.cur.atmosphere.co2 > co0 + 30 && st.cur.temp.gasC > 0.1; } }, 3000);
        double gasC;
        synchronized (st) { gasC = st.cur.temp.gasC; }
        check(hum && co2, String.format("2 raises the humidity and 4 the CO2 (greenhouse term now %+.2f °C)", gasC));
        key(KeyEvent.VK_8, '8');
        boolean season = until(() -> { synchronized (st) { return Math.abs(((st.cur.month - month0) % 12 + 12) % 12 - 3) < 0.4; } }, 3000);
        key(KeyEvent.VK_0, '0');
        boolean hour = until(() -> { synchronized (st) { double d = (((st.cur.timeOfDay - tod0) * 24) % 24 + 24) % 24; return d > 0.85 && d < 1.3; } }, 5000);
        String when;
        synchronized (st) { when = Model.MONTHS[Model.monthIndex(st.cur.month)] + ", " + st.cur.season; }
        check(season && hour, "8 jumps three months on (now " + when + ") and 0 puts the clock one hour forward");
        double[] rows = r.climateRows;
        click((int) Math.round(rows[0] + rows[1] - 10 - 38 + 29), (int) Math.round(rows[6] - 4));       // the + of the Aerosol row
        boolean aer = until(() -> { synchronized (st) { return st.cur.atmosphere.aerosol > aer0 + 0.04; } }, 3000);
        check(aer, "clicking + on the Aerosol row of the Climate panel raises the aerosol");
        key(KeyEvent.VK_K, 'k');
        frames(3);
        boolean hidden = !r.showClimate && r.climateRect == null, hiddenKept = !settings.panelClimate;
        key(KeyEvent.VK_K, 'k');
        frames(3);
        check(hidden && r.showClimate && r.climateRect != null, "K hides the Climate panel and brings it back");
        check(hiddenKept && settings.panelClimate, "the Climate panel toggle is remembered in the settings like the other panels");
        try {
            // a settings.json from before the panel existed has no key for it: the panel is shown; a stored "off" is kept
            File od = java.nio.file.Files.createTempDirectory("precip-oldcfg").toFile();
            java.nio.file.Files.writeString(new File(od, "settings.json").toPath(), "{\"version\":1,\"panels\":{\"left\":false,\"right\":true,\"minimap\":true}}");
            Settings old = Settings.load(od);
            java.nio.file.Files.writeString(new File(od, "settings.json").toPath(), "{\"version\":1,\"panels\":{\"left\":true,\"right\":true,\"minimap\":true,\"climate\":false}}");
            Settings off = Settings.load(od);
            new File(od, "settings.json").delete();
            od.delete();
            check(!old.panelLeft && old.panelClimate && off.panelLeft && !off.panelClimate, "an old settings.json without panels.climate still loads (panel shown); a stored off is read back");
        } catch (java.io.IOException ex) {
            check(false, "old settings.json round trip: " + ex);
        }
        // the click-to-spawn type: C nine times arrives at the supercell; a click on clear sky seeds exactly that
        for (int k = 0; k < Model.CLOUD_TYPES.length; k++) key(KeyEvent.VK_C, 'c');
        frames(2);
        final java.util.Set<Long> before = new java.util.HashSet<>();
        synchronized (st) { for (Model.Cloud c : st.cur.clouds) before.add(c.id); }
        int[] at = null;
        Camera.View v = r.view;
        for (int sy = 70; sy < 260 && at == null; sy += 24) for (int sx = 300; sx < 960 && at == null; sx += 36)
            if (r.cloudAt(st, v.wx(sx), v.wy(sy)) < 0) at = new int[]{sx, sy};
        boolean spawned = false;
        if (at != null) {
            click(at[0], at[1]);
            spawned = until(() -> { synchronized (st) { for (Model.Cloud c : st.cur.clouds) if (!before.contains(c.id) && c.kind == Model.SUPERCELL) return true; return false; } }, 4000);
        }
        check("supercell".equals(gp.spawnTypeName()) && "supercell".equals(r.spawnTypeLabel) && spawned,
                "C steps the click-to-spawn type to supercell and a click on the sky seeds one (spawn with \"type\")");
        edt(() -> gp.setSpawnType(0));
    }

    private static void edt(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /** Paint the current panel off screen (this is what lays out the widgets and moves the animations on). */
    private void paint() {
        edt(() -> {
            ViewerPanel p = app.panel;
            if (p.getWidth() != W || p.getHeight() != H) p.setSize(W, H);
            Graphics2D g = canvas.createGraphics();
            p.paint(g);
            g.dispose();
        });
    }

    private void frames(int n) throws InterruptedException {
        for (int k = 0; k < n; k++) { paint(); Thread.sleep(16); }
    }

    private boolean until(BooleanSupplier cond, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            edt(app::tick);
            paint();
            boolean[] ok = {false};
            edt(() -> ok[0] = cond.getAsBoolean());
            if (ok[0]) return true;
            if (System.currentTimeMillis() > end) return false;
            Thread.sleep(16);
        }
    }

    private void key(int code) { key(code, KeyEvent.CHAR_UNDEFINED); }

    /** A key press (and, for a printable character, the typed event that follows it) through the panel's listeners. */
    private void key(int code, char ch) {
        paint();
        edt(() -> {
            ViewerPanel p = app.panel;
            KeyEvent press = new KeyEvent(p, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, ch);
            for (KeyListener l : p.getKeyListeners()) l.keyPressed(press);
            if (ch != KeyEvent.CHAR_UNDEFINED) {
                ViewerPanel now = app.panel;     // the press may have switched panels
                KeyEvent typed = new KeyEvent(now, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, ch);
                for (KeyListener l : now.getKeyListeners()) l.keyTyped(typed);
            }
        });
        paint();
    }

    private void click(int x, int y) {
        paint();
        edt(() -> {
            ViewerPanel p = app.panel;
            MouseEvent press = new MouseEvent(p, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1);
            MouseEvent release = new MouseEvent(p, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1);
            for (MouseListener l : p.getMouseListeners()) l.mousePressed(press);
            for (MouseListener l : p.getMouseListeners()) l.mouseReleased(release);
        });
        paint();
    }

    private void wheel(int x, int y, int rotation) {
        paint();
        edt(() -> {
            ViewerPanel p = app.panel;
            MouseWheelEvent e = new MouseWheelEvent(p, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, x, y, 0, false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation);
            for (MouseWheelListener l : p.getMouseWheelListeners()) l.mouseWheelMoved(e);
        });
        paint();
    }

    private Screen top() {
        Screen[] s = {null};
        edt(() -> s[0] = app.top());
        return s[0];
    }

    // ------------------------------------------------------------------ v2.3: the World Simulation, on the built-in fake world

    private void clickGeo(WorldScreen ws, double lon, double lat) {
        double[] p = new double[2];
        edt(() -> { p[0] = ws.painter.x(lon); p[1] = ws.painter.y(lat); });
        click((int) Math.round(p[0]), (int) Math.round(p[1]));
    }

    private void worldSimulation() throws Exception {
        key(KeyEvent.VK_W, 'w');
        check(app.menuMode == MainMenuScreen.WORLD && top() instanceof MainMenuScreen, "W selects World Simulation, the fourth game mode");
        key(KeyEvent.VK_ENTER);
        boolean up = top() instanceof WorldScreen && app.session != null && app.session.mode.equals("world") && app.session.slot == null;
        check(up, "Enter starts the world simulation (no save slot)");
        if (!up) return;
        key(KeyEvent.VK_ESCAPE);
        frames(3);
        check(app.session == null && top() instanceof MainMenuScreen && app.menuMode == MainMenuScreen.WORLD, "Esc leaves the world map for the main menu (same mode selected)");
        key(KeyEvent.VK_ENTER);
        if (!(top() instanceof WorldScreen)) { check(false, "the world simulation starts a second time"); return; }
        WorldScreen ws = (WorldScreen) top();
        ViewerPanel wp = app.panel;
        GameSession wgs = app.session;
        boolean fed = until(() -> { synchronized (wgs.state) { return wgs.state.world != null && wgs.state.world.framesSeen > 12 && wgs.state.world.cur.hasGrid(); } }, 8000);
        frames(6);
        WorldModel wm;
        synchronized (wgs.state) { wm = wgs.state.world; }
        check(fed && wm != null && wm.locations.size() >= 6 && ws.map.problem == null && ws.painter.rect.getWidth() > 600 && ws.painter.maskBuilds >= 1,
                "world frames arrive and the map is drawn (" + (wm == null ? 0 : wm.locations.size()) + " locations, " + ws.map.countries.size() + " countries, " + ws.map.states.size()
                        + " US states, map " + (int) ws.painter.rect.getWidth() + " x " + (int) ws.painter.rect.getHeight() + ")");
        if (!fed || wm == null) return;
        key(KeyEvent.VK_F12);
        File wshots = new File(tmp, "screenshots");
        // wait for the file to be complete (its size stops growing), not just to exist
        long[] size = {-1, 0};
        boolean wshot = until(() -> {
            String[] names = wshots.isDirectory() ? wshots.list() : null;
            if (names == null || names.length != 1) return false;
            long len = new File(wshots, names[0]).length();
            boolean stable = len > 0 && len == size[0] && ++size[1] >= 8;
            if (len != size[0]) size[1] = 0;
            size[0] = len;
            return stable;
        }, 8000);
        long shotBytes = Math.max(0, size[0]);
        check(wshot && shotBytes > 20_000 && top() == ws, "F12 writes a screenshot of the world map too (" + shotBytes + " bytes) and the map stays open");
        // the game's own screenshot check further down expects a fresh folder
        until(() -> { deleteTree(wshots); return !wshots.exists(); }, 5000);

        // the map files: a point resolves to a US state first, then a country, and to nothing over the sea
        String[][] probes = {{"Seattle", "-122.33", "47.61", "US-WA"}, {"Manila", "120.98", "14.60", "PH"}, {"London", "-0.13", "51.51", "GB"}, {"Moscow", "37.62", "55.75", "RU"},
                {"Munich", "11.58", "48.14", "DE"}, {"Nagqu", "92.05", "31.48", "CN"}, {"Paris", "2.35", "48.86", "FR"}, {"Oslo", "10.75", "59.91", "NO"}, {"mid-Atlantic", "-35.0", "30.0", ""}};
        StringBuilder got = new StringBuilder();
        boolean hitOk = true;
        for (String[] pr : probes) {
            WorldMap.Region r = ws.map.hit(Double.parseDouble(pr[1]), Double.parseDouble(pr[2]));
            String code = r == null ? "" : r.code;
            got.append(got.length() > 0 ? ", " : "").append(pr[0]).append(" → ").append(code.isEmpty() ? "none" : code);
            if (!code.equals(pr[3])) hitOk = false;
        }
        check(hitOk, "hit-testing the map: " + got);

        // a click on each of the six v2.2 location dots shows that preset's card
        String[] six = {"washington", "philippines", "tibet", "russia", "united_kingdom", "central_europe"};
        StringBuilder sel = new StringBuilder();
        boolean dotsOk = true;
        for (String id : six) {
            WorldModel.Loc l = wm.loc(id);
            if (l == null) { dotsOk = false; sel.append(id).append(" missing; "); continue; }
            clickGeo(ws, l.lon, l.lat);
            String now = ws.selId;
            sel.append(sel.length() > 0 ? ", " : "").append(now);
            if (!id.equals(now)) dotsOk = false;
        }
        check(dotsOk, "a click on each of the six location dots selects its preset: " + sel);

        // a click inside Washington state, away from its dot, goes through the region table
        key(KeyEvent.VK_BACK_SPACE);
        boolean cleared = ws.selId.isEmpty();
        clickGeo(ws, -119.2, 47.4);
        check(cleared && ws.selId.equals("washington") && ws.selRegion != null && ws.selRegion.code.equals("US-WA"),
                "Backspace closes the card; a click on the state of Washington opens the Washington preset (region " + (ws.selRegion == null ? "none" : ws.selRegion.code) + ")");
        // a region the simulation has no preset for: its card names the nearest location; Enter goes there
        WorldMap.Region bare = null;
        for (WorldMap.Region r : ws.map.countries) if (!r.code.isEmpty() && !wm.regions.containsKey(r.code) && r.contains(r.labelLon, r.labelLat) && r.e - r.w > 12 && Math.abs(r.labelLat) < 60) { bare = r; break; }
        if (bare != null) {
            WorldMap.Region target = bare;
            clickGeo(ws, target.labelLon, target.labelLat);
            WorldModel.Loc near = wm.nearest(ws.clickLat, ws.clickLon);
            boolean card = ws.noPreset && ws.selId.isEmpty() && ws.selRegion == target;
            key(KeyEvent.VK_ENTER);
            check(card && near != null && ws.selId.equals(near.id) && app.session == wgs,
                    "a click on " + target.name + " (no preset) shows the \"no preset here\" card; Enter goes to the nearest location, " + (near == null ? "?" : near.name));
        }
        clickGeo(ws, -35, 30);
        check(ws.selId.isEmpty() && !ws.noPreset, "a click on the open sea closes the card");

        // layers, zoom, pan, whole world
        key(KeyEvent.VK_4, '4');
        boolean wind = ws.painter.layer == WorldPainter.WIND;
        key(KeyEvent.VK_5, '5');
        frames(4);
        boolean pressure = ws.painter.layer == WorldPainter.PRESSURE;
        double fit = ws.painter.fitScale();
        wheel((int) ws.painter.rect.getCenterX() - 200, (int) ws.painter.rect.getCenterY() - 80, -3);
        frames(30);
        double zoomed = ws.painter.scale;
        double lon0 = ws.painter.clon;
        edt(() -> ws.mouse(MouseEvent.MOUSE_PRESSED, 400, 300, MouseEvent.BUTTON1));
        edt(() -> ws.mouse(MouseEvent.MOUSE_DRAGGED, 340, 300, 0));
        edt(() -> ws.mouse(MouseEvent.MOUSE_RELEASED, 340, 300, MouseEvent.BUTTON1));
        frames(3);
        double lon1 = ws.painter.clon;
        key(KeyEvent.VK_HOME);
        frames(50);
        check(wind && pressure && zoomed > fit * 1.3 && lon1 > lon0 + 1 && Math.abs(ws.painter.scale - fit) < fit * 0.01 && ws.selId.isEmpty(),
                String.format("4 and 5 switch the layer, the wheel zooms (×%.2f), a drag pans (%.1f° east) without selecting, Home shows the whole world", zoomed / fit, lon1 - lon0));

        // time: three months on, an hour back, pause
        double[] m0 = new double[2];
        synchronized (wgs.state) { m0[0] = wm.cur.month; m0[1] = wm.cur.utcHour; }
        key(KeyEvent.VK_CLOSE_BRACKET);
        boolean season = until(() -> { synchronized (wgs.state) { double d = ((wm.cur.month - m0[0]) % 12 + 12) % 12; return d > 2.9 && d < 3.2; } }, 5000);
        key(KeyEvent.VK_SPACE);
        boolean paused = until(() -> { synchronized (wgs.state) { return wm.cur.paused; } }, 3000);
        double[] h0 = new double[1];
        synchronized (wgs.state) { h0[0] = wm.cur.utcHour; }
        key(KeyEvent.VK_COMMA);
        boolean hour = until(() -> { synchronized (wgs.state) { double d = ((h0[0] - wm.cur.utcHour) % 24 + 24) % 24; return d > 0.95 && d < 1.05; } }, 5000);
        check(season && paused && hour, "] jumps three months on, Space pauses, , sets the clock back an hour (the map glides there)");

        // open a location: a sandbox of that preset, in the month and at the local hour the map showed
        WorldModel.Loc wa = wm.loc("washington");
        clickGeo(ws, wa.lon, wa.lat);
        WorldModel.Now now;
        synchronized (wgs.state) { now = wm.cur.now.get("washington"); }
        key(KeyEvent.VK_ENTER);
        frames(3);
        List<String> args = app.lastWorldLaunch;
        // the readout was taken a frame or two before the key: the clock may still have been gliding by a minute
        boolean argsOk = args != null && args.indexOf("--preset") >= 0 && "washington".equals(args.get(args.indexOf("--preset") + 1))
                && args.indexOf("--month") >= 0 && Math.abs(Double.parseDouble(args.get(args.indexOf("--month") + 1)) - now.month) < 0.03
                && args.indexOf("--hour") >= 0 && Math.abs(Double.parseDouble(args.get(args.indexOf("--hour") + 1)) - now.localHour) < 0.12
                && Math.abs(ws.openedMonth - now.month) < 0.03 && Math.abs(ws.openedHour - now.localHour) < 0.12;
        boolean sandbox = app.session != null && app.session != wgs && app.session.mode.equals("sandbox") && top() == null && wp.disposed();
        String label = app.session == null ? "" : app.session.label;
        check("washington".equals(ws.openedId) && argsOk && sandbox && label.equals("Washington — " + WorldModel.monthName(now.month)),
                "Enter on the Washington card opens a sandbox \"" + label + "\" with " + (args == null ? "no arguments" : String.join(" ", args.subList(Math.max(0, args.indexOf("--preset")), args.size()))));
        boolean feeds = sandbox && until(() -> { synchronized (app.session.state) { return app.session.state.cur != null; } }, 5000);
        Model.Hello sh = null;
        if (app.session != null) synchronized (app.session.state) { sh = app.session.state.hello; }
        check(feeds && sh != null && sh.preset != null && sh.preset.id.equals("washington"), "the sandbox that opens is a world of that preset (hello.preset.id = "
                + (sh == null || sh.preset == null ? "none" : sh.preset.id) + ")");
        edt(app::leaveNow);
        frames(3);
        check(app.session == null && top() instanceof MainMenuScreen, "leaving that sandbox returns to the main menu");
    }

    // ------------------------------------------------------------------ the lesson modes remember where the player was

    /** Open the hurricane mode from the menu (H, Enter) and wait for its first painted frames; null when it did not open. */
    private HurricaneScreen openHurricane() throws InterruptedException {
        key(KeyEvent.VK_H, 'h');
        key(KeyEvent.VK_ENTER);
        if (!(top() instanceof HurricaneScreen)) return null;
        final GameSession gs = app.session;
        until(() -> { synchronized (gs.state) { return gs.state.hurricane != null && gs.state.hurricane.framesSeen > 6; } }, 5000);
        frames(4);
        return (HurricaneScreen) top();
    }

    private boolean stormAt(String stage) throws InterruptedException {
        final GameSession gs = app.session;
        return until(() -> { synchronized (gs.state) { return gs.state.hurricane.cur.stage.equals(stage); } }, 5000);
    }

    private byte[] settingsBytes() throws java.io.IOException { return Files.readAllBytes(settings.file().toPath()); }

    /**
     * Runs right after the two lesson-mode blocks of {@link #go}: the hurricane mode was left in the stage explorer on
     * its first stage, Cloud Formations on the Supercell tab. Both must come back there, from the menu and from the file.
     */
    private void rememberedModes() throws Exception {
        // ---- Cloud Formations: the tab shown last
        String[] types = Model.CLOUD_TYPES;
        int supIdx = java.util.Arrays.asList(types).indexOf("supercell");
        check(top() instanceof MainMenuScreen && app.menu.cloudType == supIdx && "supercell".equals(settings.showcaseType),
                "after Esc the Cloud Formations page preselects the tab shown last (" + types[app.menu.cloudType] + ")");
        key(KeyEvent.VK_ENTER);
        boolean scUp = top() instanceof ShowcaseScreen;
        String before = "?", after = "?";
        if (scUp) {
            ShowcaseScreen sc = (ShowcaseScreen) top();
            final GameSession gs = app.session;
            boolean fed = until(() -> { synchronized (gs.state) { return gs.state.cur != null && "supercell".equals(gs.state.cur.showcaseType); } }, 5000);
            check(fed && sc.type.equals("supercell"), "Enter starts Cloud Formations on that tab again (the simulation is asked for supercell)");
            before = sc.type;
            key(KeyEvent.VK_LEFT);                           // the tab before it
            after = sc.type;
            key(KeyEvent.VK_ESCAPE);
            frames(3);
        } else check(false, "Enter starts Cloud Formations on that tab again");
        Settings disk = Settings.load(settings.dir);
        check(scUp && !after.equals(before) && after.equals(types[supIdx - 1]) && top() instanceof MainMenuScreen && app.menu.cloudType == supIdx - 1 && after.equals(disk.showcaseType),
                "← inside the mode changes the tab; the menu and a freshly loaded settings.json both carry it (" + disk.showcaseType + ")");
        key(KeyEvent.VK_RIGHT);
        boolean menuPick = app.menu.cloudType == supIdx && "supercell".equals(Settings.load(settings.dir).showcaseType);
        // a type given from outside (--cloud-type) wins over the remembered one and writes nothing
        byte[] file0 = settingsBytes();
        ShowcaseScreen[] flag = new ShowcaseScreen[1];
        edt(() -> flag[0] = app.startShowcase("cirrus"));
        frames(4);
        boolean flagWins = flag[0].type.equals("cirrus") && "supercell".equals(settings.showcaseType);
        edt(app::leaveNow);
        frames(3);
        check(menuPick && flagWins && java.util.Arrays.equals(file0, settingsBytes()) && app.menu.cloudType == supIdx,
                "→ in the menu picks and stores a type; a type given at start (--cloud-type cirrus) wins and settings.json is not rewritten");

        // ---- Hurricane Formation: left in the stage explorer on the first stage
        HurricaneScreen hs = openHurricane();
        if (hs == null) { check(false, "the hurricane mode opens a second time"); return; }
        HurricaneModel hm;
        synchronized (app.session.state) { hm = app.session.state.hurricane; }
        String first = hm.stages.get(0).id;
        check(!hs.tour && hs.selStage.equals(first) && settings.hurricaneExplore && first.equals(settings.hurricaneStage),
                "the hurricane mode reopens in the stage explorer on the stage chosen last (" + hs.selStage + ")");
        // the tour: chapter 4, and the view the chapter does not use by itself
        key(KeyEvent.VK_T, 't');
        key(KeyEvent.VK_RIGHT); key(KeyEvent.VK_RIGHT); key(KeyEvent.VK_RIGHT);
        HurricaneModel.Chapter c3 = hm.lesson.get(3);
        boolean inside = !c3.view.equals("inside");
        key(inside ? KeyEvent.VK_I : KeyEvent.VK_O, inside ? 'i' : 'o');
        int wantView = inside ? HurricaneScreen.INSIDE : HurricaneScreen.OUTSIDE;
        boolean setUp = hs.tour && hs.chapter == 3 && hs.view == wantView;
        key(KeyEvent.VK_ESCAPE);
        frames(3);
        disk = Settings.load(settings.dir);
        boolean onDisk = !disk.hurricaneExplore && disk.hurricaneChapter == 3 && disk.hurricaneView.equals(inside ? "inside" : "outside");
        hs = openHurricane();
        boolean back = hs != null && hs.tour && hs.chapter == 3 && hs.view == wantView && (c3.stage.isEmpty() || stormAt(c3.stage));
        check(setUp && onDisk && back, "tour chapter 4 with the " + (inside ? "inside" : "outside") + " view: written to settings.json, and the mode reopens there (storm sent to "
                + (c3.stage.isEmpty() ? "no stage" : c3.stage) + ")");
        if (hs == null) return;
        // the explorer: a stage further on, with the other single view
        key(KeyEvent.VK_S, 's');
        key(KeyEvent.VK_RIGHT); key(KeyEvent.VK_RIGHT);
        key(inside ? KeyEvent.VK_O : KeyEvent.VK_I, inside ? 'o' : 'i');
        String picked = hs.selStage;
        int wantView2 = inside ? HurricaneScreen.OUTSIDE : HurricaneScreen.INSIDE;
        key(KeyEvent.VK_ESCAPE);
        frames(3);
        disk = Settings.load(settings.dir);
        onDisk = disk.hurricaneExplore && disk.hurricaneStage.equals(picked) && disk.hurricaneChapter == 3 && disk.hurricaneView.equals(inside ? "outside" : "inside");
        hs = openHurricane();
        back = hs != null && !hs.tour && hs.selStage.equals(picked) && hm.stageIndex(picked) >= 0 && hs.view == wantView2 && stormAt(picked);
        check(!picked.isEmpty() && onDisk && back, "stage explorer on \"" + picked + "\" with the " + (inside ? "outside" : "inside") + " view: written, and the mode reopens there");
        if (hs == null) return;
        // "Replay the tour" still goes to chapter 1 (R), and that is what is remembered next
        key(KeyEvent.VK_R, 'r');
        boolean replay = hs.tour && hs.chapter == 0;
        frames(2);
        byte[] steady = settingsBytes();
        long stamp = settings.file().lastModified();
        frames(30);                                         // nothing changes: nothing is written
        boolean quiet = java.util.Arrays.equals(steady, settingsBytes()) && stamp == settings.file().lastModified();
        key(KeyEvent.VK_ESCAPE);
        frames(3);
        disk = Settings.load(settings.dir);
        check(replay && quiet && !disk.hurricaneExplore && disk.hurricaneChapter == 0, "R replays the tour from chapter 1 and that is remembered; 30 frames without a change do not touch settings.json");

        // ---- values a newer / older version left behind: a chapter past the end, unknown ids
        edt(() -> { settings.hurricaneExplore = false; settings.hurricaneChapter = 500; settings.hurricaneView = "sideways"; });
        hs = openHurricane();
        boolean clamped = hs != null && hs.tour && hs.chapter == hm.lesson.size() - 1;
        if (hs != null) { key(KeyEvent.VK_ESCAPE); frames(3); }
        edt(() -> { settings.hurricaneExplore = true; settings.hurricaneStage = "no-such-stage"; settings.hurricaneView = ""; settings.showcaseType = "no-such-cloud"; });
        hs = openHurricane();
        boolean ignored = hs != null && !hs.tour && hs.selStage.isEmpty() && hs.view == HurricaneScreen.BOTH;
        if (hs != null) { key(KeyEvent.VK_ESCAPE); frames(3); }
        boolean firstTab = app.menu.cloudType == 0 && app.rememberedCloudType().equals(types[0]);
        check(clamped && ignored && firstTab, "a remembered chapter past the end opens the last chapter (" + hm.lesson.size() + "), an unknown stage is ignored, an unknown cloud type falls back to " + types[0]);

        // ---- a snapshot (--mode hurricane --snapshot ... --chapter N --hview V) goes where its flags say and writes nothing
        edt(() -> { settings.hurricaneExplore = true; settings.hurricaneStage = first; settings.hurricaneChapter = 2; settings.hurricaneView = "outside"; settings.showcaseType = "supercell"; app.settingsChanged(); });
        byte[] file1 = settingsBytes();
        ModeSnapshot.Opts o = new ModeSnapshot.Opts();
        o.fake = true; o.chapter = 5; o.hview = "inside"; o.frames = 20; o.w = 1040; o.h = 620;
        o.out = new File(tmp, "snap-flags.png").getPath();
        Settings forSnap = Settings.load(settings.dir);
        int rc = ModeSnapshot.run(tmp, forSnap, o);
        check(rc == 0 && new File(o.out).length() > 5_000 && java.util.Arrays.equals(file1, settingsBytes()) && forSnap.hurricaneChapter == 2 && forSnap.hurricaneExplore
                && forSnap.hurricaneView.equals("outside"), "a hurricane snapshot with --chapter 6 --hview inside is drawn and leaves the remembered place (and settings.json) alone");

        // ---- the main menu's damaged-slot card names the real folder only when it is not the default one
        SaveStore.Slot sl = new SaveStore.Slot();
        sl.id = "old-experiment-x4";
        File gameRoot = new File(tmp, "game"), elsewhere = new File(tmp, "elsewhere" + File.separator + "my saves");
        String def = MainMenuScreen.folderLabel(gameRoot, new File(gameRoot, "saves"), sl);
        String in = MainMenuScreen.folderLabel(gameRoot, new File(gameRoot, "Testing" + File.separator + "_tmp" + File.separator + "saves"), sl);
        String out = MainMenuScreen.folderLabel(gameRoot, elsewhere, sl);
        check(def.equals("Folder: saves/old-experiment-x4") && in.equals("Folder: Testing/_tmp/saves/old-experiment-x4")
                        && out.equals("Folder: " + new File(elsewhere, sl.id).getAbsolutePath()),
                "the slot card's folder line: default \"" + def + "\", inside the game folder \"" + in + "\", elsewhere the full path");
        edt(() -> { settings.hurricaneExplore = false; settings.hurricaneChapter = 0; settings.hurricaneStage = ""; settings.hurricaneView = ""; settings.showcaseType = ""; app.settingsChanged(); });
        frames(2);
    }

    // ------------------------------------------------------------------ the script

    private void go() throws Exception {
        // ---- main menu
        edt(() -> { app.applySettings(); app.showMenu(null); });
        frames(4);
        check(top() instanceof MainMenuScreen && app.menu.slots.isEmpty() && app.session == null, "the app opens in the main menu (empty first-run list)");

        click(1500, 98);                                   // "New simulation" (Sandbox page)
        check(top() instanceof NewSimScreen, "clicking New simulation opens the dialog");
        key(KeyEvent.VK_ESCAPE);
        check(top() instanceof MainMenuScreen, "Esc closes the dialog");

        key(KeyEvent.VK_N, 'n');                           // keyboard shortcut; its character must not land in the name field
        boolean opened = top() instanceof NewSimScreen;
        String name0 = opened ? ((NewSimScreen) top()).name.value() : "?";
        check(opened && name0.equals("Simulation 1"), "N opens New simulation with the default name \"Simulation 1\" (got \"" + name0 + "\")");
        // v2.2: a region preset renames the (untouched) default name and travels in the parameters
        key(KeyEvent.VK_PAGE_DOWN);
        NewSimScreen nr = (NewSimScreen) top();
        check(nr.regionIdx == 1 && nr.name.value().equals("Washington") && nr.params().preset.equals("washington"),
                "Page Down picks the first region: name \"" + nr.name.value() + "\", preset \"" + nr.params().preset + "\"");
        key(KeyEvent.VK_PAGE_UP);
        check(nr.regionIdx == 0 && nr.name.value().equals("Simulation 1") && nr.params().preset.isEmpty(), "Page Up goes back to Custom and the default name");
        // v2.3: starting month and time of day (left alone they send nothing: the simulation's own defaults apply)
        boolean noArgs = nr.params().climateArgs().isEmpty();
        edt(() -> { nr.monthIdx = 7; nr.timeIdx = 4; });
        paint();
        String cargs = String.join(" ", nr.params().climateArgs());
        edt(() -> { nr.monthIdx = 0; nr.timeIdx = 1; });
        check(noArgs && cargs.equals("--month 7.50 --hour 18.50"), "Starts in = Jul and Time of day = Dusk become the simulation arguments \"" + cargs + "\" (Auto / Morning send none)");
        key(KeyEvent.VK_Q, 'Q');
        key(KeyEvent.VK_TAB);
        key(KeyEvent.VK_1, '1'); key(KeyEvent.VK_A, 'a'); key(KeyEvent.VK_7, '7');
        NewSimScreen ns = (NewSimScreen) top();
        check(ns.name.value().equals("Simulation 1Q") && ns.seed.value().equals("17"), "typing edits the name, Tab moves to the seed, the seed takes digits only (name \""
                + ns.name.value() + "\", seed \"" + ns.seed.value() + "\")");
        key(KeyEvent.VK_ESCAPE);

        // ---- settings: rebind a key from the Controls page with mouse + keyboard
        click(120, 488);                                   // "Settings" (under the four game modes)
        check(top() instanceof SettingsScreen, "clicking Settings opens the settings");
        click(480, 337);                                   // the "Controls" tab (the fifth row since Phase J put "Advanced" above it; a row is 44 px)
        SettingsScreen ss = (SettingsScreen) top();
        check(ss.tab == SettingsScreen.CONTROLS, "clicking the Controls tab shows the controls");
        click(858, 175);                                   // the key of the first action (Pause / resume)
        check(ss.capturing == Action.PAUSE, "clicking a key starts rebinding that action");
        key(KeyEvent.VK_P, 'p');
        check(ss.capturing == null && settings.keys.get(Action.PAUSE).equals(List.of(KeyEvent.VK_P)), "the next key pressed becomes the binding (Pause = P)");
        click(858, 175);
        key(KeyEvent.VK_ESCAPE);
        check(top() == ss && ss.capturing == null && settings.keys.get(Action.PAUSE).equals(List.of(KeyEvent.VK_P)), "Esc cancels a rebinding without closing the settings");
        click(858, 175 + (int) Math.round(SettingsScreen.controlsRowH(600 - 62 - 66) * 3));   // Pan left (the fourth row; rows are lower since v2.3 added actions)
        key(KeyEvent.VK_P, 'p');                           // conflict: P is Pause now
        check(settings.keys.get(Action.PAN_LEFT).equals(List.of(KeyEvent.VK_P)) && settings.keys.get(Action.PAUSE).isEmpty() && ss.keyWarning != null,
                "a key already in use moves over and a warning names the action that lost it: " + ss.keyWarning);
        key(KeyEvent.VK_ESCAPE);
        check(top() instanceof MainMenuScreen, "Esc closes the settings");
        until(() -> settings.file().isFile(), 3000);
        Settings again = Settings.load(settings.dir);
        check(again.keys.get(Action.PAN_LEFT).equals(List.of(KeyEvent.VK_P)) && again.keys.get(Action.PAUSE).isEmpty(), "the bindings were written to settings.json and read back");
        edt(() -> { settings.keys = KeyMap.defaults(); app.settingsChanged(); });

        // ---- v2.2: the two lesson modes, on the built-in fakes (the temp folder has no Python simulation)
        edt(() -> app.fakeModes = true);
        click(220, 281);                                   // the "Hurricane Formation" mode row
        check(app.menuMode == MainMenuScreen.HURRICANE && top() instanceof MainMenuScreen, "clicking the Hurricane Formation row selects that mode");
        click(600, 675);                                   // "Start Hurricane Formation"
        boolean hurUp = top() instanceof HurricaneScreen && app.session != null && app.session.mode.equals("hurricane") && app.session.slot == null;
        check(hurUp, "its Start button opens the hurricane mode (no save slot)");
        if (hurUp) {
            HurricaneScreen hs = (HurricaneScreen) top();
            ViewerPanel hp = app.panel;
            boolean fed = until(() -> { synchronized (app.session.state) { return app.session.state.hurricane != null && app.session.state.hurricane.framesSeen > 20; } }, 5000);
            frames(8);
            check(fed && hs.tour && hs.chapter == 0 && hs.painter.outRect.getWidth() > 100 && hs.painter.inRect.getWidth() > 100,
                    "hurricane frames arrive and both pictures are drawn (tour chapter 1)");
            key(KeyEvent.VK_RIGHT);
            String want;
            synchronized (app.session.state) { want = app.session.state.hurricane.lesson.get(1).stage; }
            boolean glided = until(() -> { synchronized (app.session.state) { return app.session.state.hurricane.cur.stage.equals(want); } }, 4000);
            check(hs.chapter == 1 && glided, "→ opens chapter 2 and the storm is sent to its stage (" + want + ")");
            key(KeyEvent.VK_S, 's');
            check(!hs.tour, "S skips to the stage explorer");
            click(60, H - 50);                              // the first stage chip
            String first;
            synchronized (app.session.state) { first = app.session.state.hurricane.stages.get(0).id; }
            boolean back = until(() -> { synchronized (app.session.state) { return app.session.state.hurricane.cur.stage.equals(first); } }, 4000);
            check(hs.selStage.equals(first) && back, "clicking a stage chip sends the storm there (" + first + ")");
            key(KeyEvent.VK_ESCAPE);
            frames(3);
            check(app.session == null && top() instanceof MainMenuScreen && app.menuMode == MainMenuScreen.HURRICANE && hp.disposed(),
                    "Esc leaves the hurricane mode for the main menu (same mode selected)");
        }
        key(KeyEvent.VK_C, 'c');
        key(KeyEvent.VK_ENTER);
        boolean scUp = top() instanceof ShowcaseScreen && app.session != null && app.session.mode.equals("showcase");
        check(app.menuMode == MainMenuScreen.CLOUDS && scUp, "C selects Cloud Formations and Enter starts it");
        if (scUp) {
            ShowcaseScreen sc = (ShowcaseScreen) top();
            ViewerPanel sp = app.panel;
            boolean fed = until(() -> { synchronized (app.session.state) { return app.session.state.cur != null && app.session.state.cur.showcaseType != null; } }, 5000);
            frames(6);
            Renderer sr = sp.renderer();
            check(fed && sr.showWind && sr.showArrows && sr.showLabels && !sr.showRightPanel && sp.camera().vw < W - 300 && sp.camera().vy > 40,
                    "the showcase world is drawn in its own part of the window with wind, arrows and labels on (view " + (int) sp.camera().vw + " x " + (int) sp.camera().vh + ")");
            key(KeyEvent.VK_2, '2');
            boolean switched = until(() -> { synchronized (app.session.state) { return "cumulonimbus".equals(app.session.state.cur.showcaseType) && app.session.state.cur.showcaseCycle == 1; } }, 4000);
            check(sc.type.equals("cumulonimbus") && switched, "2 opens the second tab and the simulation switches to cumulonimbus");
            // v2.3: the ninth tab is the supercell, with its lifetime tour
            key(KeyEvent.VK_9, '9');
            boolean sup = until(() -> { synchronized (app.session.state) { Model.Frame f = app.session.state.cur; return "supercell".equals(f.showcaseType) && f.tourStep != null && !f.clouds.isEmpty() && f.clouds.get(0).kind == Model.SUPERCELL; } }, 4000);
            frames(3);
            String step0;
            synchronized (app.session.state) { step0 = app.session.state.cur.tourStep; }
            check(sup && sc.type.equals("supercell") && sc.tour() != null && sc.tour().size() == 8 && sc.tour().get(0)[0].equals(step0),
                    "9 opens the Supercell tab: a lifetime tour of " + (sc.tour() == null ? 0 : sc.tour().size()) + " steps, the storm on stage is at \"" + step0 + "\"");
            key(KeyEvent.VK_ESCAPE);
            frames(3);
            check(app.session == null && top() instanceof MainMenuScreen && sp.disposed(), "Esc leaves Cloud Formations for the main menu");
        }
        rememberedModes();
        worldSimulation();
        key(KeyEvent.VK_S, 's');
        check(app.menuMode == MainMenuScreen.SANDBOX, "S goes back to the Sandbox page");

        // ---- a running game (fake simulation): input goes to the game, panels slide
        edt(() -> app.startDiagnostic(new FakeSim(1234, 0.38, 3840, 1080), "ui test"));
        ViewerPanel gp = app.panel;
        Renderer r = gp.renderer();
        Camera cam = gp.camera();
        until(() -> app.session.simRunning(), 5000);
        frames(6);
        check(top() == null && cam.vw == W - Renderer.PANEL_W && r.showRightPanel, "a game is on screen with the right panel docked (world view " + (int) cam.vw + " px wide)");

        double zoom0 = cam.tz;
        wheel(600, 300, -1);
        check(cam.tz > zoom0, "the mouse wheel zooms the world (no menu in the way)");

        key(KeyEvent.VK_CLOSE_BRACKET, ']');
        double mid = -1;
        for (int k = 0; k < 40; k++) { paint(); if (r.rightSlide > 0.05 && r.rightSlide < 0.95) mid = r.rightSlide; Thread.sleep(16); }
        check(!r.showRightPanel && r.rightSlide == 0 && cam.vw == W && mid > 0, "] slides the right panel away (seen part-way at " + String.format("%.2f", mid)
                + ") and the world view grows to " + (int) cam.vw + " px");
        check(!settings.panelRight, "the panel state is remembered in the settings");
        click(W - 10, H / 2);                               // the edge tab on the right
        frames(40);
        check(r.showRightPanel && r.rightSlide == 1 && cam.vw == W - Renderer.PANEL_W, "clicking the edge tab brings the right panel back");
        key(KeyEvent.VK_OPEN_BRACKET, '[');
        frames(30);
        check(!r.showLeftPanel && r.leftSlide == 0, "[ hides the left panel");
        click(8, 40);                                       // its tab, top left while hidden
        frames(30);
        check(r.showLeftPanel && r.leftSlide == 1, "its edge tab shows it again");
        key(KeyEvent.VK_M, 'm');
        frames(30);
        check(!r.showMinimap && r.miniSlide == 0 && r.minimapRect == null, "M hides the minimap");
        click((W - Renderer.PANEL_W) / 2, H - 8);           // its tab at the bottom edge
        frames(30);
        check(r.showMinimap && r.miniSlide == 1, "its edge tab shows it again");
        key(KeyEvent.VK_BACK_SLASH, '\\');
        frames(40);
        check(!r.showLeftPanel && !r.showRightPanel && !r.showMinimap && cam.vw == W, "\\ hides everything for a clean view");
        key(KeyEvent.VK_BACK_SLASH, '\\');
        frames(40);
        check(r.showLeftPanel && r.showRightPanel && r.showMinimap && settings.panelLeft && settings.panelRight && settings.panelMinimap, "\\ again shows everything");

        // ---- v2.2: going under a cloud dims the view smoothly; the setting turns it off
        Model.Cloud dark = null;
        synchronized (app.session.state) {
            for (Model.Cloud c : app.session.state.cur.clouds) if (dark == null || c.darkness * c.form * c.w > dark.darkness * dark.form * dark.w) dark = c;
        }
        final Model.Cloud dc = dark;
        edt(() -> cam.set(dc.x, dc.y + 160, 1.3, true));
        double prevLevel = r.shadeLevel, maxStep = 0, startLevel = prevLevel;
        for (int k = 0; k < 130; k++) {
            paint();
            double lv = r.shadeLevel;
            maxStep = Math.max(maxStep, Math.abs(lv - prevLevel));
            prevLevel = lv;
            Thread.sleep(16);
        }
        double wanted;
        synchronized (app.session.state) { wanted = CloudShade.target(app.session.state.cur, r.view); }
        check(r.shadeOn && wanted > 0.3 && Math.abs(prevLevel - wanted) < 0.06 && maxStep < 0.08, String.format(
                "under %s the dimming eases to the cloud cover: level %.2f → %.2f (cover %.2f), largest step per frame %.3f", dc.name, startLevel, prevLevel, wanted, maxStep));
        edt(() -> { settings.cloudShade = false; app.settingsChanged(); });
        frames(4);
        check(!r.shadeOn && r.shadeLevel < prevLevel, "turning \"Dim under clouds\" off in the settings stops it");
        edt(() -> { settings.cloudShade = true; app.settingsChanged(); cam.home(); cam.snap(); });
        frames(4);

        // ---- pause menu
        key(KeyEvent.VK_ESCAPE);
        boolean pauseOpen = top() instanceof PauseScreen;
        boolean paused = until(() -> { synchronized (app.session.state) { return app.session.state.cur.paused; } }, 3000);
        check(pauseOpen && paused, "Esc opens the pause menu and pauses the simulation");
        double zoom1 = cam.tz;
        wheel(600, 300, -1);
        key(KeyEvent.VK_CLOSE_BRACKET, ']');
        check(cam.tz == zoom1 && r.showRightPanel, "while the pause menu is open the game ignores wheel and keys");
        key(KeyEvent.VK_ESCAPE);
        boolean resumed = until(() -> { synchronized (app.session.state) { return !app.session.state.cur.paused; } }, 3000);
        check(top() == null && resumed, "Esc again resumes");
        click(1230, 24);                                    // the menu button at the top right of the world view
        check(top() instanceof PauseScreen, "the on-screen menu button opens the pause menu too");
        click(840, 310);                                    // "Resume"
        check(top() == null, "clicking Resume closes it");
        sandboxV23(gp, r);

        // ---- the save thumbnail is rendered by a second renderer while the live view keeps painting
        ViewerState liveState = app.session.state;
        BufferedImage[] thumb = {null};
        Throwable[] thumbErr = {null};
        Thread tt = new Thread(() -> {
            try {
                for (int k = 0; k < 4; k++) thumb[0] = GameSession.renderThumb(liveState, gp);
            } catch (Throwable ex) {
                thumbErr[0] = ex;
            }
        }, "thumb-test");
        tt.start();
        while (tt.isAlive()) { paint(); Thread.sleep(5); }
        check(thumbErr[0] == null && thumb[0] != null && thumb[0].getWidth() == 320 && thumb[0].getHeight() == 180,
                "thumbnails render beside the live view (4 in a row, " + (thumbErr[0] == null ? "no error" : thumbErr[0].toString()) + ")");

        key(KeyEvent.VK_F12);
        File shots = new File(tmp, "screenshots");
        boolean shot = until(() -> shots.isDirectory() && shots.list() != null && shots.list().length == 1, 5000);
        check(shot, "F12 writes a screenshot (" + (shot ? shots.list()[0] : "none") + ")");

        key(KeyEvent.VK_ESCAPE);
        key(KeyEvent.VK_DOWN); key(KeyEvent.VK_DOWN);        // Resume -> Settings -> Back to the main menu
        key(KeyEvent.VK_ENTER);
        frames(3);
        check(app.session == null && top() instanceof MainMenuScreen && app.panel != gp && gp.disposed(), "the pause menu leads back to the main menu (keyboard), the game view is disposed");

        // ---- a simulation that cannot start: error in the window, way back to the menu
        NewSimScreen.Params p = new NewSimScreen.Params();
        p.name = "Broken";
        edt(() -> app.startNew(p));                          // the temp folder has no sim/: the script is missing
        boolean shown = until(() -> app.top() instanceof Screen.Message, 5000);
        Screen.Message msg = shown ? (Screen.Message) top() : null;
        check(shown && msg.title.equals("Simulation problem") && msg.message.contains("Simulation script not found"),
                "a simulation that cannot start shows \"Simulation problem\" in the window");
        key(KeyEvent.VK_ENTER);                              // default button: Back to the main menu
        frames(3);
        // ---- fullscreen switch on a window that is never shown
        Settings s2 = new Settings(new File(tmp, "config2"));
        s2.persist = false;
        AppShell a2 = new AppShell(tmp, s2, new SaveStore(new File(tmp, "saves")));
        boolean[] fs = new boolean[3];
        edt(() -> {
            a2.createWindow();
            java.awt.Rectangle windowed = a2.frame.getBounds();
            a2.toggleFullscreen();
            fs[0] = a2.frame.isUndecorated() && a2.frame.getBounds().equals(a2.frame.getGraphicsConfiguration().getBounds()) && s2.fullscreen;
            a2.toggleFullscreen();
            fs[1] = !a2.frame.isUndecorated() && a2.frame.getBounds().equals(windowed) && !s2.fullscreen;
            fs[2] = !a2.frame.isVisible();
            a2.disposeWindow();
        });
        a2.io.shutdown();
        check(fs[0] && fs[1] && fs[2], "fullscreen switches the window to a borderless screen-sized frame and back to its old bounds");

        File[] slotDirs = new File(tmp, "saves").listFiles();
        check(app.session == null && top() instanceof MainMenuScreen && (slotDirs == null || slotDirs.length == 0),
                "its button returns to the main menu and no broken slot is left behind");
        lightning();
    }

    // ------------------------------------------------------------------ lightning: branches, and the channel inside the cloud

    private void branches(ViewerState st, ParallelRenderer p4, ParallelRenderer p1, Camera cam) {
        Model.Terrain terr = st.hello.terrain;
        // ---- geometry, on a sample of bolts all over the world (the --bolt test bolts, and short ones shaped like the simulation's)
        List<Model.Bolt> sample = new ArrayList<>();
        for (double x = 150; x < 3700; x += 90) sample.add(Main.testBolt(st.hello, st.cur, x, 1));
        java.util.Random sr = new java.util.Random(5);
        for (int k = 0; k < 40; k++) {
            double x = 200 + sr.nextDouble() * 3400, y = terr.at(x) - 250 - sr.nextDouble() * 250, xe = x + sr.nextDouble() * 60 - 30, ye = terr.at(xe);
            int steps = 4 + sr.nextInt(4);
            Model.Bolt b = new Model.Bolt();
            b.pts = new double[2 * steps + 2];
            b.pts[0] = x; b.pts[1] = y;
            for (int i = 1; i <= steps; i++) {
                double fx = x + (xe - x) * i / steps + (i == steps ? 0 : sr.nextDouble() * 36 - 18);
                b.pts[2 * i] = fx; b.pts[2 * i + 1] = i == steps ? ye : Math.min(y + (ye - y) * i / steps, terr.at(fx) - 1);
            }
            b.alpha = 1;
            sample.add(b);
        }
        boolean same = true, fromOrigin = true, down = true;
        int none = 0, under = 0, maxSeg = 0, maxDepth = 0, midAir = 0, tips = 0, onGround = 0, forks = 0, fewest = 999, most = 0;
        for (Model.Bolt b : sample) {
            Lightning.Tree t = Lightning.Tree.grow(b.pts, Lightning.key(b), terr, null, 0, 0, 0), t2 = Lightning.Tree.grow(b.pts, Lightning.key(b), terr, null, 0, 0, 0);
            same &= t.lines.size() == t2.lines.size() && t.segments == t2.segments;
            for (int k = 0; same && k < t.lines.size(); k++) same = java.util.Arrays.equals(t.lines.get(k), t2.lines.get(k));
            if (t.lines.isEmpty()) { none++; continue; }
            fromOrigin &= t.lines.get(0)[0] == b.pts[0] && t.lines.get(0)[1] == b.pts[1];
            maxSeg = Math.max(maxSeg, t.segments);
            fewest = Math.min(fewest, t.lines.size()); most = Math.max(most, t.lines.size());
            int segs = 0;
            for (int k = 0; k < t.lines.size(); k++) {
                double[] ln = t.lines.get(k);
                segs += ln.length / 2 - 1;
                maxDepth = Math.max(maxDepth, t.depth.get(k));
                if (t.depth.get(k) > 1) forks++;
                for (int q = 0; q + 1 < ln.length; q += 2) {
                    if (ln[q + 1] > terr.at(ln[q]) + 1e-6) under++;
                    if (q > 0 && ln[q + 1] <= ln[q - 1]) down = false;
                }
                tips++;
                double tx = ln[ln.length - 2], ty = ln[ln.length - 1];
                if (t.grounded.get(k)) { if (Math.abs(ty - terr.at(tx)) < 1e-6) onGround++; else under++; }
                else if (ty < terr.at(tx) - 3.5) midAir++;
            }
            same &= segs == t.segments;
        }
        check(same, "lightning branches: the same bolt grows the same tree every time (" + sample.size() + " bolts, built twice each)");
        check(none == 0 && fromOrigin && fewest >= 1 && most >= 6 && forks > 0 && maxDepth <= Lightning.Tree.MAX_DEPTH && maxDepth >= 2,
                String.format("every bolt has a branch from its point of origin; bolts differ (%d to %d branches a bolt), %d of the branches are forks of a branch, at most %d levels deep",
                        fewest, most, forks, maxDepth));
        check(under == 0 && down && midAir > tips * 0.8 && midAir + onGround == tips && maxSeg <= Lightning.Tree.MAX_SEG && maxSeg > 30,
                String.format("of %d branches %d end in mid-air and %d on the ground; no point of one lies under the terrain (%d do), all run downward; at most %d segments a bolt (cap %d)",
                        tips, midAir, onGround, under, maxSeg, Lightning.Tree.MAX_SEG));

        // ---- the real renderer: a bolt from the biggest storm cloud, the camera on the cloud
        Model.Cloud host = null;
        for (Model.Cloud c : st.cur.clouds) {
            boolean storm = c.kind == Model.CUMULONIMBUS || c.kind == Model.SUPERCELL;
            if (c.form < 0.5 || Main.boltHost(st.cur, c.x) != c) continue;
            if (host == null || (storm ? 1e6 : 0) + c.h > ((host.kind == Model.CUMULONIMBUS || host.kind == Model.SUPERCELL) ? 1e6 : 0) + host.h) host = c;
        }
        if (host == null) { check(false, "lightning in the cloud: the test world has no cloud to host a bolt"); return; }
        Model.Bolt bolt = Main.testBolt(st.hello, st.cur, host.x, 1);
        Main.applyCam(cam, String.format(java.util.Locale.ROOT, "%.0f,%.0f,0.75", host.x, bolt.pts[1] - 150), st);
        Camera.View cv = cam.view(W, H, 1, 0, 0);
        Renderer.epilepsySafe = false;
        st.cur.lightning = new ArrayList<>();
        int[] base = renderStill(p4, st, cam, BufferedImage.TYPE_INT_RGB), soft0 = renderStill(p4, st, cam, BufferedImage.TYPE_3BYTE_BGR);
        st.cur.lightning = new ArrayList<>(List.of(bolt));
        int[] flash = renderStill(p4, st, cam, BufferedImage.TYPE_INT_RGB);
        Lightning.Light l = p4.shared.lightning.lights.get(Lightning.key(bolt));
        Lightning.Frame f1 = p4.shared.bolts;
        int[] again = renderStill(p4, st, cam, BufferedImage.TYPE_INT_RGB), one = renderStill(p1, st, cam, BufferedImage.TYPE_INT_RGB), soft = renderStill(p4, st, cam, BufferedImage.TYPE_3BYTE_BGR);
        Lightning.Frame f2 = p4.shared.bolts;
        if (l == null || l.hostC == null || l.tree.hidden.isEmpty()) { check(false, "lightning in the cloud: a bolt from " + host.name + " gets a channel inside it"); return; }
        Lightning.Tree t = l.tree;
        boolean inside = true;
        int hiddenPts = 0;
        for (double[] ln : t.hidden) for (int q = 0; q + 1 < ln.length; q += 2) { hiddenPts++; inside &= l.cover(ln[q], ln[q + 1]) >= 0.5 && ln[q + 1] > l.topY && ln[q + 1] <= bolt.pts[1] + 1e-6; }
        double[] trunk = t.hidden.get(0);
        int tk = (trunk.length / 4) * 2;                     // a point half way up the trunk
        int gx = (int) Math.round(cv.ws * trunk[tk] + cv.wtx), gy = (int) Math.round(cv.ws * trunk[tk + 1] + cv.wty);
        int skyX = gx, skyY = (int) Math.round(cv.ws * (l.topY - 45) + cv.wty);
        boolean seen = gx > 8 && gx < W - 8 && gy > 8 && gy < H - 8 && skyY > 8;
        int glow = seen ? lum(flash[gy * W + gx]) - lum(base[gy * W + gx]) : -1, glowSoft = seen ? lum(soft[gy * W + gx]) - lum(soft0[gy * W + gx]) : -1;
        // 160 px to the side at the same height the cloud is lit less: the light follows the channel
        int sideX = gx + (gx < W / 2 ? 160 : -160), side = seen ? lum(flash[gy * W + sideX]) - lum(base[gy * W + sideX]) : -1;
        int skyDiff = 0, outside = 0, lit = 0;
        for (int y = Math.max(0, skyY - 6); seen && y <= skyY + 6; y++) for (int x = skyX - 6; x <= skyX + 6; x++) if (flash[y * W + x] != base[y * W + x]) skyDiff++;
        // above the reach of the point lights only the channel lights anything, and only the host cloud (8 px: one node of the light grid)
        double mainTop = bolt.pts[1];
        for (int q = 1; q < t.main.length; q += 2) mainTop = Math.min(mainTop, t.main[q]);
        int yLim = (int) Math.floor(cv.ws * (mainTop - 190) + cv.wty);
        for (int y = 0; y < Math.min(H, yLim); y++) for (int x = 0; x < W; x++) {
            if (flash[y * W + x] == base[y * W + x]) continue;
            lit++;
            double wx = (x - cv.wtx) / cv.ws, wy = (y - cv.wty) / cv.ws;
            if (l.cover(wx, wy) == 0 && l.cover(wx - 8, wy) == 0 && l.cover(wx + 8, wy) == 0 && l.cover(wx, wy - 8) == 0 && l.cover(wx, wy + 8) == 0) outside++;
        }
        check(inside && hiddenPts >= 4 && t.glow.length / 5 <= Lightning.Tree.MAX_CLOUD_SEG, String.format(
                "the discharge starts inside %s: a hidden channel of %d points in %d lines, every one where the cloud is opaque (%d light segments, cap %d)",
                host.name, hiddenPts, t.hidden.size(), t.glow.length / 5, Lightning.Tree.MAX_CLOUD_SEG));
        check(seen && glow > 60 && side < glow && skyDiff == 0, String.format(
                "the cloud glows from within: a pixel on the hidden channel gains %d, one 160 px beside it %d, and clear sky 45 px above the cloud top changes in %d of 169 pixels", glow, side, skyDiff));
        check(lit > 2000 && outside == 0, String.format("nothing shines out of the cloud: of %d pixels lit above the reach of the point lights, %d lie outside its silhouette", lit, outside));
        int bandDiff = 0;
        for (int k = 0; k < flash.length; k++) if (flash[k] != one[k]) bandDiff++;
        check(java.util.Arrays.equals(flash, again) && bandDiff == 0 && f1 != null && f2 != null && f1.trees.length == 1 && f1.trees[0] == f2.trees[0] && f1.trees[0] == t,
                "the forked bolt and the glow are the same picture every frame and join without a seam (4 bands against 1: " + bandDiff + " pixels differ); the tree is built once per bolt");
        check(glowSoft > 30, "the Java2D fallback shows the glow in the cloud too (the same pixel gains " + glowSoft + ")");
        // Epilepsy-safe: nothing in the cloud at all, the branches as dim as the bolt
        Renderer.epilepsySafe = true;
        int[] safe = renderStill(p4, st, cam, BufferedImage.TYPE_INT_RGB);
        Renderer.epilepsySafe = false;
        int oy = (int) Math.floor(cv.ws * bolt.pts[1] + cv.wty) - 12, safeAbove = 0, safeN = 0, safeMax = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int a = safe[y * W + x], b = base[y * W + x];
            if (a == b) continue;
            safeN++;
            if (y < oy) safeAbove++;
            safeMax = Math.max(safeMax, Math.max(Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)), Math.max(Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)), Math.abs((a & 255) - (b & 255)))));
        }
        check(safeN > 0 && safeAbove == 0 && safeMax < 110, String.format(
                "Epilepsy-safe: no glow in the cloud (%d pixels change above the point of origin) and the branches are as dim as the bolt (%d pixels, largest change %d of 255)", safeAbove, safeN, safeMax));
    }

    // ------------------------------------------------------------------ lightning: a local light with ray-traced shadows; the Epilepsy-safe setting

    private static int[] renderStill(ParallelRenderer pr, ViewerState st, Camera cam, int type) {
        BufferedImage img = new BufferedImage(W, H, type);
        double t;
        synchronized (st) { t = st.cur.t; }
        pr.render(img, 1, 0, 0, W, H, st, cam, -1, t, "test");
        int[] out = new int[W * H];
        img.getRGB(0, 0, W, H, out, 0, W);
        return out;
    }

    private static int lum(int rgb) { return ((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255); }

    private void lightning() throws Exception {
        // ---- the setting: off by default, off for a file without the key, written and read back, toggled on the Graphics page
        File od = Files.createTempDirectory("precip-ltcfg").toFile();
        Files.writeString(new File(od, "settings.json").toPath(), "{\"version\":1,\"graphics\":{\"cloudQuality\":2}}");
        Settings old = Settings.load(od);
        boolean absentOff = !new Settings(od).epilepsySafe && !old.epilepsySafe && old.cloudQuality == 2 && old.loadWarning == null;
        old.epilepsySafe = true;
        old.save();
        String written = Files.readString(new File(od, "settings.json").toPath());
        Settings back = Settings.load(od);
        new File(od, "settings.json").delete();
        od.delete();
        check(absentOff && back.epilepsySafe && back.cloudQuality == 2 && written.replace(" ", "").contains("\"epilepsySafe\":true"),
                "Epilepsy-safe lightning is off by default and for a settings.json without the key; switched on it is written as graphics.epilepsySafe and read back");
        edt(() -> app.openSettings(SettingsScreen.GRAPHICS));
        click(787, 498);                                    // the toggle of the last row of the Graphics page
        boolean on = settings.epilepsySafe && Renderer.epilepsySafe;
        click(787, 498);
        boolean off = !settings.epilepsySafe && !Renderer.epilepsySafe;
        key(KeyEvent.VK_ESCAPE);
        check(on && off && top() instanceof MainMenuScreen, "its toggle on Settings > Graphics switches the renderer over at once, and back");

        // ---- geometry: one house on flat ground, a straight bolt 100 px to its left (no scene, the light alone)
        Model.Obj shop = new Model.Obj();
        shop.kind = "shop"; shop.building = true; shop.x = 1100; shop.y = 900; shop.w = 60; shop.h = 80;
        Model.Terrain flat = Model.Terrain.flat(3840, 900);
        Model.Bolt bolt = new Model.Bolt();
        bolt.pts = new double[]{1000, 500, 1000, 900};
        bolt.alpha = 1;
        Model.Frame lf = new Model.Frame();
        lf.lightning = List.of(bolt);
        Camera lc = new Camera();
        lc.setWorld(3840, 1080);
        lc.setViewport(0, 0, 1280, 720);
        lc.set(1000, 740, 1.5, true);
        Camera.View lv = lc.view(1280, 720, 1, 0, 0);
        Lightning.State ls = new Lightning.State();
        Lightning.Frame fr = Lightning.prepare(ls, lf, new Model.Obj[]{shop}, flat, List.of(), lv, 1_000_000_000L, false, false, 0.35, 1);
        Lightning.Band band = new Lightning.Band();
        boolean lit = fr != null && fr.lights.length == 1 && band.compute(fr, lv, 0, 720);
        java.util.function.BiFunction<Double, Double, Integer> at = (wx, wy) -> band.at((int) Math.round(lv.ws * wx + lv.wtx), (int) Math.round(lv.ws * wy + lv.wty));
        // behind the house (its far side, near the ground) against the mirror point on the open side of the bolt
        int behind = lit ? at.apply(1142.0, 890.0) : -1, open = lit ? at.apply(858.0, 890.0) : -1;
        int faceNear = lit ? at.apply(1076.0, 860.0) : -1, faceFar = lit ? at.apply(1124.0, 860.0) : -1;
        int above = lit ? at.apply(1250.0, 600.0) : -1, aboveMirror = lit ? at.apply(750.0, 600.0) : -1;
        int soil = lit ? at.apply(900.0, 912.0) : -1, deep = lit ? at.apply(900.0, 965.0) : -1;
        BufferedImage grey = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        Graphics2D gg = grey.createGraphics();
        gg.setColor(new java.awt.Color(60, 60, 60));
        gg.fillRect(0, 0, 1280, 720);
        gg.dispose();
        int pxBehind = 0, pxOpen = 0;
        if (lit) {
            Direct d = new Direct();
            d.attach(grey);
            d.clip(0, 0, 1280, 720);
            band.apply(d, fr);
            pxBehind = lum(grey.getRGB((int) Math.round(lv.ws * 1142 + lv.wtx), (int) Math.round(lv.ws * 890 + lv.wty)));
            pxOpen = lum(grey.getRGB((int) Math.round(lv.ws * 858 + lv.wtx), (int) Math.round(lv.ws * 890 + lv.wty)));
        }
        check(lit && open > 60 && behind * 8 < open && pxBehind == 180 && pxOpen > 260,
                String.format("a house blocks the light of a bolt: light behind it %d against %d at the mirror point in the open (grey 180 stays %d there, becomes %d in the open)",
                        behind, open, pxBehind, pxOpen));
        check(lit && faceNear > faceFar && faceFar > behind && Math.abs(above - aboveMirror) <= 2 && above > 0,
                String.format("its wall facing the bolt is lit more than its far wall (%d > %d > %d behind); beside the shadow both sides are lit alike (%d and %d)",
                        faceNear, faceFar, behind, above, aboveMirror));
        check(lit && soil > 0 && deep == 0, String.format("the ground stops the light: %d just under the surface, %d deeper down", soil, deep));
        // the Java2D path (a target without direct pixel access) casts the same shadow
        BufferedImage soft = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB);
        gg = soft.createGraphics();
        gg.setColor(new java.awt.Color(60, 60, 60));
        gg.fillRect(0, 0, 1280, 720);
        if (lit) band.paint(gg, fr);
        gg.dispose();
        int sBehind = lum(soft.getRGB((int) Math.round(lv.ws * 1142 + lv.wtx), (int) Math.round(lv.ws * 890 + lv.wty)));
        int sOpen = lum(soft.getRGB((int) Math.round(lv.ws * 858 + lv.wtx), (int) Math.round(lv.ws * 890 + lv.wty)));
        check(lit && sBehind == 180 && sOpen > 220, String.format("the Java2D fallback draws the same light and shadow (grey 180: %d behind the house, %d in the open)", sBehind, sOpen));
        // a bolt the stream drops while still bright fades out on the viewer's clock
        Lightning.State gs = new Lightning.State();
        Lightning.prepare(gs, lf, new Model.Obj[]{shop}, flat, List.of(), lv, 1_000_000_000L, true, false, 0.35, 1);
        Model.Frame none = new Model.Frame();
        double prevA = 1, maxDrop = 0;
        int ghostFrames = 0;
        for (int k = 1; k < 120; k++) {
            Lightning.Frame g2 = Lightning.prepare(gs, none, new Model.Obj[]{shop}, flat, List.of(), lv, 1_000_000_000L + k * 16_666_667L, true, false, 0.35, 1);
            if (g2 == null) break;
            ghostFrames++;
            maxDrop = Math.max(maxDrop, prevA - g2.strokeA[0]);
            prevA = g2.strokeA[0];
        }
        check(ghostFrames > 10 && ghostFrames < 60 && maxDrop < 0.06, String.format("a bolt that vanishes from the stream at full brightness fades out over %d frames (largest step %.3f), it does not pop", ghostFrames, maxDrop));

        // ---- the real renderer on the fake world, at night, camera on the village
        FakeSim sim = new FakeSim(1234, 0.0, 3840, 1080);
        ViewerState st = new ViewerState();
        st.acceptLine(sim.hello());
        for (int k = 0; k < 60; k++) st.acceptLine(sim.step());
        st.cur.lightning = new ArrayList<>();               // only the bolts of this test
        ParallelRenderer p4 = new ParallelRenderer(4), p1 = new ParallelRenderer(1);
        boolean safeWas = Renderer.epilepsySafe;
        try {
            for (ParallelRenderer pr : new ParallelRenderer[]{p4, p1}) {
                pr.shared.setAsync(false);
                Renderer r = pr.primary();
                r.showRightPanel = false; r.showLeftPanel = false; r.showMinimap = false; r.showLabels = false; r.showArrows = false; r.showWind = false; r.showClimate = false;
            }
            Camera cam = new Camera();
            p4.layout(cam, st, W, H);
            Main.applyCam(cam, "settlement:village,1.5", st);
            Camera.View cv = cam.view(W, H, 1, 0, 0);
            double vx = (cv.wx0 + cv.wx1) / 2;
            Renderer.epilepsySafe = false;
            int[] base = renderStill(p4, st, cam, BufferedImage.TYPE_INT_RGB), again = renderStill(p4, st, cam, BufferedImage.TYPE_INT_RGB);
            // a bolt well beyond the light's reach from the edge of the view
            double farX = cv.wx1 + Lightning.REACH + 400;
            st.cur.lightning = new ArrayList<>(List.of(Main.testBolt(st.hello, st.cur, farX, 1)));
            int[] far = renderStill(p4, st, cam, BufferedImage.TYPE_INT_RGB);
            int farDiff = 0;
            for (int k = 0; k < base.length; k++) if (far[k] != base[k]) farDiff++;
            check(java.util.Arrays.equals(base, again) && farDiff == 0, String.format(
                    "a bolt %.0f px off screen changes no pixel of the view (%d differ; the old flash whitened all %d)", farX - cv.wx1, farDiff, base.length));
            // a bolt just off screen still spills light in from that edge
            Model.Bolt edge = Main.testBolt(st.hello, st.cur, cv.wx1 + 120, 1);
            st.cur.lightning = new ArrayList<>(List.of(edge));
            int[] spill = renderStill(p4, st, cam, BufferedImage.TYPE_INT_RGB);
            int spillRight = 0, spillLeft = 0;
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (spill[y * W + x] != base[y * W + x]) { if (x >= W / 2) spillRight++; else spillLeft++; }
            check(spillRight > 2000 && spillLeft * 50 < spillRight, String.format("a bolt 120 px beyond the right edge spills its light in from that edge: %d pixels lit in the right half, %d in the left", spillRight, spillLeft));
            // a bolt in view: light near it, darkness far from it, brighter the nearer
            Model.Bolt mid = Main.testBolt(st.hello, st.cur, vx, 1);
            st.cur.lightning = new ArrayList<>(List.of(mid));
            int[] flash = renderStill(p4, st, cam, BufferedImage.TYPE_INT_RGB), flash1 = renderStill(p1, st, cam, BufferedImage.TYPE_INT_RGB);
            long nearGain = 0, farGain = 0;
            int nearN = 0, farN = 0, sky = (int) Math.round(cv.ws * (mid.pts[1] + 160) + cv.wty);
            for (int x = 0; x < W; x++) {
                double wd = Math.abs((x - cv.wtx) / cv.ws - vx);
                int gain = lum(flash[sky * W + x]) - lum(base[sky * W + x]);
                if (wd > 40 && wd < 140) { nearGain += gain; nearN++; } else if (wd > 420) { farGain += gain; farN++; }
            }
            int bandDiff = 0;
            for (int k = 0; k < flash.length; k++) if (flash[k] != flash1[k]) bandDiff++;
            check(nearN > 0 && farN > 0 && nearGain / nearN > 3 * Math.max(1, farGain / farN) && farGain >= 0, String.format(
                    "the light falls off with distance: 160 px under the cloud base the sky gains %d near the bolt and %d beyond 420 px", nearGain / nearN, farGain / Math.max(1, farN)));
            check(bandDiff == 0, "the light joins without a seam across the render bands (4 bands against 1: " + bandDiff + " pixels differ)");
            // the same frame on a target the renderer has no direct access to
            int[] soft3 = renderStill(p4, st, cam, BufferedImage.TYPE_3BYTE_BGR);
            st.cur.lightning = new ArrayList<>();
            int[] soft0 = renderStill(p4, st, cam, BufferedImage.TYPE_3BYTE_BGR);
            int softLit = 0;
            for (int k = 0; k < soft3.length; k++) if (lum(soft3[k]) > lum(soft0[k]) + 6) softLit++;
            check(softLit > 20000, "the whole frame also renders through the Java2D fallback (a byte image: " + softLit + " pixels lit by the bolt)");
            // Epilepsy-safe: no light at all, only a dim line
            st.cur.lightning = new ArrayList<>(List.of(mid));
            Renderer.epilepsySafe = true;
            int[] safe = renderStill(p4, st, cam, BufferedImage.TYPE_INT_RGB);
            int safeN = 0, safeMax = 0, safeOut = 0, bx0 = W, bx1 = 0;
            for (int k = 0; k + 1 < mid.pts.length; k += 2) { int dx = (int) Math.round(cv.ws * mid.pts[k] + cv.wtx); bx0 = Math.min(bx0, dx); bx1 = Math.max(bx1, dx); }
            // (its branches are part of the bolt: the same tree as with the setting off)
            for (double[] ln : Lightning.Tree.grow(mid.pts, Lightning.key(mid), st.hello.terrain, null, 0, 0, 0).lines)
                for (int k = 0; k + 1 < ln.length; k += 2) { int dx = (int) Math.round(cv.ws * ln[k] + cv.wtx); bx0 = Math.min(bx0, dx); bx1 = Math.max(bx1, dx); }
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                int a = safe[y * W + x], b = base[y * W + x];
                if (a == b) continue;
                safeN++;
                if (x < bx0 - 12 || x > bx1 + 12) safeOut++;
                safeMax = Math.max(safeMax, Math.max(Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)), Math.max(Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)), Math.abs((a & 255) - (b & 255)))));
            }
            int flashN = 0;
            for (int k = 0; k < flash.length; k++) if (flash[k] != base[k]) flashN++;
            check(safeN > 0 && safeOut == 0 && safeMax < 110 && flashN > 50 * safeN, String.format(
                    "Epilepsy-safe: the bolt is a dim line and nothing else changes (%d pixels within 12 px of it and its branches, largest change %d of 255; with the setting off the bolt lights %d pixels)",
                    safeN, safeMax, flashN));
            branches(st, p4, p1, cam);
        } finally {
            Renderer.epilepsySafe = safeWas;
            p4.shutdown();
            p1.shutdown();
        }
        // ... and on the clock: every bolt eases in and out, and a burst of them cannot flash more than three times a second
        double maxStep = 0, peak = 0, prev = 0;
        for (double t = 0; t <= 1.5; t += 1 / 60.0) {
            double lv2 = Lightning.Safe.level(t);
            maxStep = Math.max(maxStep, Math.abs(lv2 - prev));
            peak = Math.max(peak, lv2);
            prev = lv2;
        }
        Lightning.Safe sf = new Lightning.Safe();
        List<Double> starts = new ArrayList<>();
        List<Model.Bolt> live = new ArrayList<>();
        int shownBefore = 0;
        double worstJump = 0;
        double[] lastLv = new double[0];
        for (int k = 0; k < 300; k++) {                      // 5 s at 60 Hz, a new bolt every 100 ms (ten a second), each listed for 0.45 s
            double t = k / 60.0;
            if (k % 6 == 0) { Model.Bolt b = new Model.Bolt(); b.pts = new double[]{100 + k, 300, 100 + k, 900}; b.alpha = 1; live.add(b); }
            for (Model.Bolt b : live) b.alpha -= 2.2 / 60;
            live.removeIf(b -> b.alpha <= 0);
            long clock = 5_000_000_000L + Math.round(t * 1e9);
            sf.advance(live, clock, true, null);
            double[] lvs = sf.levels(clock, true);
            if (lvs.length > shownBefore) starts.add(t);
            double sum = 0, was = 0;
            for (double x : lvs) sum += x;
            for (double x : lastLv) was += x;
            worstJump = Math.max(worstJump, Math.abs(sum - was));
            shownBefore = lvs.length; lastLv = lvs;
        }
        int worstSecond = 0;
        for (double a : starts) { int n = 0; for (double b : starts) if (b >= a && b < a + 1) n++; worstSecond = Math.max(worstSecond, n); }
        check(peak <= Lightning.Safe.PEAK + 1e-9 && peak > 0.2 && maxStep < 0.03 && Lightning.Safe.level(0) == 0 && Lightning.Safe.level(1.5) == 0,
                String.format("Epilepsy-safe: a bolt eases in and out (peak opacity %.2f, largest change per frame %.3f, 0 at both ends)", peak, maxStep));
        check(worstSecond <= 3 && worstSecond > 0 && worstJump < 0.06, String.format(
                "Epilepsy-safe: of ten bolts a second at most %d start in any one second, and the total opacity on screen never jumps (largest step %.3f)", worstSecond, worstJump));
    }
}
