package precip;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * {@code --ui-snapshot <screen> <out.png>}: draw one screen of the app shell into a PNG without
 * opening a window, with the built-in fake simulation underneath and a handful of made-up save
 * slots, so layouts can be checked by looking at a file. The screens are painted by exactly the
 * code the live window uses ({@link AppShell#paintOverlay}).
 */
final class UiSnapshot {
    private UiSnapshot() {}

    static final String[] SCREENS = {"menu", "menu-empty", "menu-hurricane", "menu-clouds", "new", "new-region", "rename", "delete", "settings-background", "settings-graphics",
            "settings-advanced", "settings-advanced-on", "settings-game", "settings-controls", "settings-rebind", "game", "game-clean", "game-help", "game-preset", "pause", "leave", "saving", "error",
            "hurricane-tour", "hurricane-inside", "hurricane-explore", "clouds", "menu-world", "world", "world-card", "world-help",
            // v2.3 sandbox screens
            "new-month", "game-storm", "clouds-supercell",
            // the hidden Debug mode (Phase I): the menu before and after the unlock, then one screen per tab ("debug-<tab>[:key=value,...]")
            "menu-debug-locked", "menu-debug-unlocked", "debug-clouds", "debug-lightning", "debug-buildings", "debug-cars", "debug-weather", "debug-world",
            "debug-hurricane", "debug-worldmap",
            // v2.4 (Phase L): the Debug mode's Flood tab ("debug-flood:rate=8,rain=on,run=40");
            // v2.4.1 (Phase N) adds "slope=gentle|steep|cliff" (the road beyond the village) and "pick=village|city|hamlet"
            "debug-flood",
            // Phase P: the Sound tab of the settings
            "settings-sound"};

    static int run(File root, Settings settings, String screen, String out, int w, int h, double dpi, String backdropError) throws Exception {
        settings.persist = false;                        // a snapshot never writes settings
        if (screen.startsWith("debug-")) return DebugScreen.snapshot(root, settings, screen.substring(6), out, w, h, dpi);
        if (screen.equals("menu-debug-locked")) settings.debugUnlocked = false;      // (in memory only: the menu as a new player has it)
        if (screen.equals("menu-debug-unlocked")) settings.debugUnlocked = true;
        if (screen.equals("settings-advanced-on")) settings.adv = Advanced.allOn();  // (likewise in memory: every option at its other value)
        if (screen.startsWith("hurricane-") || screen.equals("clouds") || screen.equals("clouds-supercell") || screen.equals("world") || screen.startsWith("world-")) {
            // the two lesson modes and the world map, on the built-in fakes
            ModeSnapshot.Opts o = new ModeSnapshot.Opts();
            o.fake = true; o.out = out; o.w = w; o.h = h; o.dpi = dpi;
            switch (screen) {
                case "world" -> { o.mode = "world"; o.frames = 40; }
                case "world-card" -> { o.mode = "world"; o.frames = 40; o.select = "washington"; }
                case "world-help" -> { o.mode = "world"; o.frames = 40; o.help = true; }
                case "hurricane-tour" -> { o.chapter = 3; o.frames = 300; }
                case "hurricane-inside" -> { o.chapter = 7; o.frames = 300; }
                case "hurricane-explore" -> { o.explore = true; o.stage = "cat5"; o.frames = 300; }
                case "clouds" -> { o.mode = "showcase"; o.cloudType = "cumulonimbus"; o.untilStage = "precipitating"; }
                case "clouds-supercell" -> { o.mode = "showcase"; o.cloudType = "supercell"; Main.optUntil = "step:tornado+8"; }
                default -> { System.err.println("[viewer] unknown --ui-snapshot screen \"" + screen + "\". Known: " + String.join(", ", SCREENS)); return 2; }
            }
            return ModeSnapshot.run(root, settings, o);
        }
        AppShell app = new AppShell(root, settings, new SaveStore(new File(root, "saves")));
        app.loadStoredBackdrop(true);
        app.applySettings();
        if (backdropError != null) app.reportBackdropError(backdropError);
        boolean game = screen.startsWith("game") || screen.equals("pause") || screen.equals("leave") || screen.equals("saving") || screen.equals("error");

        ParallelRenderer pr = new ParallelRenderer(ParallelRenderer.defaultThreads());
        pr.shared.setAsync(false);
        Renderer r = pr.primary();
        Camera cam = new Camera();
        ViewerState state;
        String source;
        if (game) {
            boolean storm = screen.equals("game-storm");     // v2.3: the supercell scene with its tornado on the ground
            FakeSim sim = storm ? new FakeSim(1234, 0.38, 3840, 1080, null, null, Double.NaN, Double.NaN, "supercell")
                    : new FakeSim(1234, 0.38, 3840, 1080, null, screen.equals("game-preset") ? "washington" : null);
            SaveStore.Slot slot = fakeSlots(false).get(0);
            GameSession gs = new GameSession(app, slot, sim, true, slot.name);
            state = gs.state;
            feed(state, sim, storm ? 1250 : 300);
            app.session = gs;
            app.styleGame(r);
            if (screen.equals("game-clean")) { r.showLeftPanel = false; r.showRightPanel = false; r.showMinimap = false; }
            if (screen.equals("game-help")) r.showHelp = true;
            if (screen.equals("game-preset")) { r.showLeftPanel = true; r.showLabels = true; }
            source = sim.describe();
            if (screen.equals("error")) {
                String err = "The Python simulation stopped (exit code 1).\n\nLast stderr lines:\n  Traceback (most recent call last):\n"
                        + "  savestate.SaveError: state file version 7 is newer than this simulation (2)";
                synchronized (state) { state.error = err; }
                app.push(app.simProblem(gs, err));
            }
            switch (screen) {
                case "pause" -> { gs.pauseMenuOpen = true; app.push(new PauseScreen(app, gs)); }
                case "leave" -> { app.push(new PauseScreen(app, gs)); app.confirmLeaveWithoutSaving(); }
                case "saving" -> { app.push(new PauseScreen(app, gs)); app.push(new Screen.Busy("Saving")); }
                default -> { }
            }
        } else {
            FakeSim sim = new FakeSim(77, 0.40, 3200, 1080);
            state = new ViewerState();
            feed(state, sim, 260);
            app.styleMenu(r);
            source = sim.describe();
            app.menu = new MainMenuScreen(app);
            app.push(app.menu);
            app.slotsOverride = screen.equals("menu-empty") ? new ArrayList<>() : fakeSlots(true);
            app.reloadSlots(null);
            switch (screen) {
                case "menu-hurricane" -> app.menuMode = MainMenuScreen.HURRICANE;
                case "menu-world" -> app.menuMode = MainMenuScreen.WORLD;
                case "menu-clouds" -> { app.menuMode = MainMenuScreen.CLOUDS; if (settings.showcaseType == null || settings.showcaseType.isEmpty()) app.menu.cloudType = 1; }
                case "new-region" -> { app.openNewSimulation(); ((NewSimScreen) app.top()).pickRegion(1); }
                case "new" -> app.openNewSimulation();
                case "new-month" -> { app.openNewSimulation(); NewSimScreen ns = (NewSimScreen) app.top(); ns.pickRegion(1); ns.monthIdx = 7; ns.timeIdx = 4; }
                case "rename" -> app.menu.key(key(KeyEvent.VK_F2));
                case "delete" -> app.menu.key(key(KeyEvent.VK_DELETE));
                case "settings-background" -> app.openSettings(SettingsScreen.BACKGROUND);
                case "settings-graphics" -> app.openSettings(SettingsScreen.GRAPHICS);
                case "settings-advanced", "settings-advanced-on" -> app.openSettings(SettingsScreen.ADVANCED);
                case "settings-game" -> app.openSettings(SettingsScreen.GAME);
                case "settings-controls" -> app.openSettings(SettingsScreen.CONTROLS);
                case "settings-sound" -> app.openSettings(SettingsScreen.SOUND);
                case "settings-rebind" -> {
                    SettingsScreen s = new SettingsScreen(app, SettingsScreen.CONTROLS);
                    app.push(s);
                    s.bind(Action.TOGGLE_WIND, KeyEvent.VK_M);      // steals M from the minimap: shows the conflict warning
                    s.capturing = Action.TOGGLE_ARROWS;
                }
                case "menu", "menu-empty", "menu-debug-locked" -> { }
                case "menu-debug-unlocked" -> app.menuMode = MainMenuScreen.DEBUG;
                default -> {
                    // "new-region:<preset id>" (not in the list of names: any of the presets): the dialog with that region chosen
                    int pi = screen.startsWith("new-region:") ? java.util.Arrays.asList(Preset.IDS).indexOf(screen.substring(11)) : -1;
                    if (pi >= 0) { app.openNewSimulation(); ((NewSimScreen) app.top()).pickRegion(pi + 1); break; }
                    System.err.println("[viewer] unknown --ui-snapshot screen \"" + screen + "\". Known: " + String.join(", ", SCREENS));
                    pr.shutdown();
                    return 2;
                }
            }
        }

        double t;
        synchronized (state) { t = state.cur != null ? state.cur.t : 0; }
        pr.layout(cam, state, w, h);
        if (game) { cam.home(); cam.snap(); } else AppShell.menuCamera(cam);
        if (screen.equals("game-storm")) Main.applyCam(cam, "880,560,0.80", state);
        int dw = (int) Math.round(w * dpi), dh = (int) Math.round(h * dpi);
        BufferedImage img = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
        pr.render(img, dpi, 0, 0, w, h, state, cam, -1, t, source);
        Graphics2D g = img.createGraphics();
        g.scale(dpi, dpi);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        app.paintOverlay(g, w, h, r);
        g.dispose();
        pr.shutdown();
        File f = new File(out);
        if (f.getAbsoluteFile().getParentFile() != null) f.getAbsoluteFile().getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
        System.out.println("[viewer] ui snapshot \"" + screen + "\" written: " + f.getAbsolutePath() + " (" + dw + "x" + dh + ")");
        return 0;
    }

    private static KeyEvent key(int code) {
        return new KeyEvent(new java.awt.Canvas(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED);
    }

    private static void feed(ViewerState st, FakeSim sim, int frames) {
        st.acceptLine(sim.hello());
        for (int k = 0; k < frames; k++) st.acceptLine(sim.step());
    }

    /** A few invented slots: fresh, old, big, one without a picture, and (optionally) one damaged. */
    static List<SaveStore.Slot> fakeSlots(boolean withDamaged) {
        long now = System.currentTimeMillis();
        List<SaveStore.Slot> l = new ArrayList<>();
        l.add(slot("Stormy Coast", "stormy-coast-x1", 918273, 1, "hilly", "normal", 3, 1934.5, 2710, now - 4 * 60_000L, 11, 64, 53, 41.2, 6.3, thumb(11, 0.36)));
        SaveStore.Slot region = slot("Washington", "washington-x2", 4242, 1, "preset", "sparse", 1, 312.0, 420, now - 3 * 3_600_000L, 6, 14, 8, 3.4, 0.0, thumb(5, 0.72));
        region.presetId = "washington";
        region.presetName = "Washington";
        l.add(region);
        l.add(slot("The long night over Port Halden and the eastern mountain range", "the-long-night-x3", 77000123, 3, "mountainous", "dense", 9, 86400.0,
                93_000, now - 26 * 3_600_000L, 19, 412, 393, 880.4, 212.9, thumb(23, 0.02)));
        if (withDamaged) {
            SaveStore.Slot d = slot("Old experiment", "old-experiment-x4", 1, 1, "flat", "none", 0, 0, 0, now - 9 * 86_400_000L, 0, 0, 0, 0, 0, null);
            d.problem = "Damaged — the state file is missing";
            l.add(d);
        }
        l.add(slot("Flatlands test", "flatlands-test-x5", 5550123, 2, "flat", "none", 0, 45.0, 61, now - 40 * 86_400_000L, 4, 5, 1, 0.0, 0.0, null));
        return l;
    }

    private static SaveStore.Slot slot(String name, String id, long seed, int size, String terrain, String settlements, int nSettle, double simT, double play,
                                       long last, int alive, int born, int dead, double rain, double snow, BufferedImage thumb) {
        SaveStore.Slot s = new SaveStore.Slot();
        s.name = name; s.id = id; s.seed = seed;
        s.worldW = Settings.SIZES[size][0]; s.worldH = Settings.SIZES[size][1];
        s.terrain = terrain; s.settlements = settlements; s.settlementCount = nSettle;
        s.simT = simT; s.frame = (long) (simT * 30); s.playSeconds = play;
        s.created = last - 86_400_000L; s.lastPlayed = last;
        s.alive = alive; s.born = born; s.dead = dead; s.rainMm = rain; s.snowMm = snow;
        s.thumb = thumb;
        s.dir = new File("saves", id);
        return s;
    }

    private static BufferedImage thumb(long seed, double tod) {
        FakeSim sim = new FakeSim(seed, tod, 3840, 1080);
        ViewerState st = new ViewerState();
        feed(st, sim, 200);
        return GameSession.renderThumb(st, null);
    }
}
