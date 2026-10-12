package precip;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;

/**
 * {@code --selftest}: an end-to-end run of the app shell against the real Python simulation,
 * without a window. It drives the same {@link AppShell} / {@link GameSession} / {@link SaveStore}
 * code the menus call:
 * new simulation → run → save → leave → slot listed → load → time continues, history restored →
 * autosave → Save &amp; Leave → rename, duplicate → failed-save handling → delete.
 * <p>
 * It works in the real {@code saves/} folder under slots named "Selftest ..." and removes them at
 * the end; the player's settings file is neither read nor written.
 */
final class SelfTest {
    private final File root;
    private final AppShell app;
    private final SaveStore store;
    private final Settings settings;
    private final List<String> failures = new ArrayList<>();
    private final List<File> created = new ArrayList<>();
    private final List<SimProcess> sims = new ArrayList<>();
    private int step;

    /** The saves folder of this run when it is a temp folder made here (removed again at the end); null when the caller named one. */
    private File tempSaves;

    /**
     * @param savesDir where the test keeps its slots ({@code --saves-dir}); null = a fresh temp folder. It is never
     *                 the player's own {@code saves/}: a test that creates and deletes slots has no business there.
     */
    private SelfTest(File root, File savesDir) throws java.io.IOException {
        this.root = root;
        settings = new Settings(new File(root, "config"));
        settings.persist = false;
        settings.autosaveMinutes = 0;                 // the autosave step turns it on with a short interval
        File dir = savesDir;
        if (dir == null) {
            dir = java.nio.file.Files.createTempDirectory("precip-selftest-saves").toFile();
            tempSaves = dir;
        } else java.nio.file.Files.createDirectories(dir.toPath());
        File real = new File(root, "saves");
        if (dir.getCanonicalFile().equals(real.getCanonicalFile())) throw new java.io.IOException("--selftest refuses to run in the player's saves folder " + real + " (give another --saves-dir)");
        store = new SaveStore(dir);
        app = new AppShell(root, settings, store);
        System.out.println("[selftest] save slots of this run go to " + dir + (tempSaves != null ? " (temporary, removed at the end)" : ""));
    }

    static int run(File root, File savesDir) {
        SelfTest t;
        try {
            t = new SelfTest(root, savesDir);
        } catch (java.io.IOException ex) {
            System.out.println("[selftest] FAIL: " + ex.getMessage());
            return 1;
        }
        int code;
        try {
            t.go();
            code = t.failures.isEmpty() ? 0 : 1;
        } catch (Throwable ex) {
            ex.printStackTrace();
            t.failures.add("exception: " + ex);
            code = 1;
        } finally {
            t.cleanup();
        }
        if (code == 0) System.out.println("[selftest] PASS (" + t.step + " steps)");
        else {
            System.out.println("[selftest] FAIL");
            for (String f : t.failures) System.out.println("[selftest]   - " + f);
        }
        return code;
    }

    // ------------------------------------------------------------------ helpers

    private void say(String s) { System.out.println(String.format("[selftest] %2d  %s", ++step, s)); }

    private void check(boolean ok, String what) {
        if (!ok) {
            failures.add("step " + step + ": " + what);
            System.out.println("[selftest]     FAILED: " + what);
        }
    }

    private static void edt(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /** Tick the shell (autosave, timeouts) until the condition holds. */
    private boolean await(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            edt(app::tick);
            boolean[] ok = {false};
            edt(() -> ok[0] = cond.getAsBoolean());
            if (ok[0]) return true;
            if (System.currentTimeMillis() > end) return false;
            Thread.sleep(40);
        }
    }

    private static double simT(GameSession gs) { return gs.simTime(); }

    private static Map<String, Object> readJson(File f) throws Exception {
        return Json.obj(Json.parse(Files.readString(f.toPath(), StandardCharsets.UTF_8)));
    }

    private String saveNow(GameSession gs) throws InterruptedException {
        String[] res = {"(no answer)"};
        boolean[] done = {false};
        edt(() -> gs.requestSave("Saved", err -> { res[0] = err; done[0] = true; }));
        if (!await(() -> done[0], 15_000)) return "timed out waiting for the save callback";
        return res[0];
    }

    private SimProcess track(GameSession gs) {
        SimProcess sp = (SimProcess) gs.source;
        sims.add(sp);
        return sp;
    }

    // ------------------------------------------------------------------ the run

    private void go() throws Exception {
        String name = "Selftest " + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
        long seed = 4242;

        // 1 ---- new simulation
        NewSimScreen.Params p = new NewSimScreen.Params();
        p.name = name; p.seed = seed; p.sizeIdx = 0; p.terrain = "hilly"; p.settlements = "normal";
        edt(() -> app.startNew(p));
        GameSession g1 = app.session;
        SaveStore.Slot slot = g1.slot;
        created.add(slot.dir);
        SimProcess sp1 = track(g1);
        boolean up = await(() -> g1.simRunning() || g1.simError() != null, 30_000);
        Model.Hello h1;
        synchronized (g1.state) { h1 = g1.state.hello; }
        say("new simulation \"" + name + "\" -> slot " + slot.id + ", python pid " + sp1.pid() + ", hello: "
                + (h1 == null ? "none" : "v" + h1.version + " seed " + h1.seed + " world " + h1.w + "x" + h1.h + " " + h1.terrainKind + " loaded=" + h1.loaded
                + " settlements=" + h1.settlements.size() + " objects=" + h1.objects.size()));
        check(up && g1.simError() == null, "the simulation did not start: " + g1.simError());
        if (h1 == null) return;
        check(h1.seed == seed && h1.w == 1920 && h1.h == 900 && "hilly".equals(h1.terrainKind) && !h1.loaded, "hello does not match the requested world");

        // 2 ---- the new world is saved by itself right away
        boolean first = await(g1::everSaved, 20_000);
        say("first automatic save of the new world: " + (first ? "done at sim t=" + slot.simT + " (" + g1.saveStatus() + ")" : "did not happen"));
        check(first, "no automatic first save");

        // 3 ---- run ~5 s
        await(() -> simT(g1) >= 5.0, 30_000);
        long fr;
        int livesLive;
        synchronized (g1.state) { fr = g1.state.framesReceived; livesLive = g1.state.lives.size(); }
        say(String.format("ran: sim t=%.2f s, %d frames received, %d clouds in the timeline", simT(g1), fr, livesLive));
        check(simT(g1) >= 5.0, "the simulation did not reach t=5 s");

        // 4 ---- save
        String err = saveNow(g1);
        double savedT = slot.simT;
        long savedFrame = slot.frame;
        File meta = slot.file(SaveStore.META), st = slot.file(SaveStore.STATE), hist = slot.file(SaveStore.HISTORY), th = slot.file(SaveStore.THUMB), bak = slot.file(SaveStore.STATE_BAK);
        say(String.format("save: %s; saved t=%.2f frame=%d; state.sav %d bytes, state.sav.bak %s, history.json %d bytes, thumb.png %d bytes, meta.json %d bytes, leftover temp files: %d",
                err == null ? "ok" : "ERROR " + err, savedT, savedFrame, st.length(), bak.isFile() ? bak.length() + " bytes" : "missing", hist.length(), th.length(), meta.length(),
                countTemp(slot.dir)));
        check(err == null, "save failed: " + err);
        check(st.length() > 0 && hist.length() > 0 && th.length() > 0 && meta.length() > 0 && bak.isFile(), "a slot file is missing");
        check(countTemp(slot.dir) == 0, "temporary files were left in the slot folder");
        check(savedT >= 5.0, "saved time is too small");
        Map<String, Object> histJson = readJson(hist);
        int savedLives = Json.arr(histJson, "lives").size(), savedEvents = Json.arr(histJson, "events").size();
        List<Long> savedIds = new ArrayList<>();
        for (Object o : Json.arr(histJson, "lives")) savedIds.add((long) Json.num(Json.obj(o), "id", -1));

        // 5 ---- leave: back to the menu, python gone
        edt(app::leaveNow);
        app.awaitStops();
        boolean dead1 = !sp1.alive() && ProcessHandle.of(sp1.pid()).map(ph -> !ph.isAlive()).orElse(true);
        say("leave: back in the menu=" + (app.menu != null && app.session == null) + ", python pid " + sp1.pid() + " still running=" + !dead1);
        check(app.menu != null && app.session == null, "not back in the menu");
        check(dead1, "python process survived leaving the game");

        // 6 ---- the slot is listed with the right meta
        SaveStore.Slot listed = null;
        for (SaveStore.Slot s : app.menu.slots) if (s.id.equals(slot.id)) listed = s;
        SaveStore.Slot sel = app.menu.sel();
        say("menu lists " + app.menu.slots.size() + " slot(s); ours: " + (listed == null ? "MISSING" : String.format(
                "name=\"%s\" seed=%d world=%dx%d terrain=%s settlements=%s(%d) simT=%.2f frame=%d played=%.1fs clouds alive/born/dead=%d/%d/%d playable=%s; preselected=%s",
                listed.name, listed.seed, listed.worldW, listed.worldH, listed.terrain, listed.settlements, listed.settlementCount, listed.simT, listed.frame,
                listed.playSeconds, listed.alive, listed.born, listed.dead, listed.playable(), sel != null && sel.id.equals(slot.id))));
        check(listed != null, "the slot is not in the menu list");
        if (listed == null) return;
        check(listed.name.equals(name) && listed.seed == seed && listed.worldW == 1920 && listed.worldH == 900 && listed.terrain.equals("hilly")
                && listed.settlements.equals("normal") && Math.abs(listed.simT - savedT) < 0.011 && listed.frame == savedFrame && listed.playable()
                && listed.playSeconds > 4 && listed.born >= listed.alive && listed.alive > 0, "meta.json does not match what was saved");
        check(sel != null && sel.id.equals(slot.id), "the slot played last is not preselected");
        check(app.menu.slots.get(0).id.equals(slot.id), "the list is not sorted by last played");

        // 7 ---- load: time continues from the saved value, history restored
        final SaveStore.Slot toLoad = listed;
        edt(() -> app.startLoad(toLoad));
        GameSession g2 = app.session;
        SimProcess sp2 = track(g2);
        int importedLives;
        synchronized (g2.state) { importedLives = g2.state.lives.size(); }
        boolean up2 = await(() -> g2.simRunning() || g2.simError() != null, 30_000);
        Model.Hello h2;
        double firstT;
        synchronized (g2.state) { h2 = g2.state.hello; firstT = g2.state.cur != null ? g2.state.cur.t : -1; }
        await(() -> simT(g2) >= savedT + 2.0, 30_000);
        int missing = 0, livesNow, eventsNow;
        synchronized (g2.state) {
            for (long id : savedIds) if (!g2.state.byId.containsKey(id)) missing++;
            livesNow = g2.state.lives.size();
            eventsNow = g2.state.events.size();
        }
        say(String.format("load: python pid %d, hello loaded=%s t=%.2f (saved %.2f), first frame seen t=%.2f, now t=%.2f; history: %d lifetimes restored before the first frame"
                        + " (saved %d), %d of them missing now, timeline now %d, event log %d (saved %d)",
                sp2.pid(), h2 != null && h2.loaded, h2 == null ? -1 : h2.t, savedT, firstT, simT(g2), importedLives, savedLives, missing, livesNow, eventsNow, savedEvents));
        check(up2 && g2.simError() == null, "the loaded simulation did not start: " + g2.simError());
        check(h2 != null && h2.loaded && Math.abs(h2.t - savedT) < 0.05, "hello after --load does not continue at the saved time");
        check(firstT >= savedT - 0.05 && firstT < savedT + 1.5, "the first frames after the load are not just after the saved time");
        check(simT(g2) >= savedT + 2.0, "time does not advance after the load");
        check(importedLives == savedLives && missing == 0 && livesNow >= savedLives && eventsNow >= Math.min(savedEvents, ViewerState.MAX_EVENTS - 5), "history was not restored");
        check(h2 != null && h2.seed == seed && h2.w == 1920, "the loaded world is a different world");

        // 8 ---- autosave on a short interval
        double before = readNum(meta, "simT");
        edt(() -> settings.autosaveSecondsOverride = 3);
        long a0 = System.currentTimeMillis();
        boolean auto = await(() -> g2.saveStatus().startsWith("Autosaved"), 20_000);
        await(() -> !g2.saving(), 5_000);
        double after = readNum(meta, "simT");
        say(String.format("autosave (3 s interval): fired=%s after %.1f s, indicator \"%s\", meta.json simT %.2f -> %.2f", auto, (System.currentTimeMillis() - a0) / 1000.0,
                g2.saveStatus(), before, after));
        check(auto && after > before + 1.0, "autosave did not update the slot");
        boolean again = await(() -> readNumQuiet(meta, "simT") > after + 1.0, 15_000);
        say("autosave fires repeatedly: " + again + " (meta.json simT now " + readNumQuiet(meta, "simT") + ")");
        check(again, "autosave fired only once");
        edt(() -> settings.autosaveSecondsOverride = 0);
        edt(() -> settings.autosaveMinutes = 0);
        await(() -> !g2.saving(), 5_000);

        // 9 ---- a save that cannot be written leaves the previous one intact and reports it
        File blocker = slot.file(SaveStore.STATE_NEW);
        Files.createDirectories(new File(blocker, "in-the-way").toPath());
        long sizeBefore = st.length(), timeBefore = st.lastModified();
        double metaBefore = readNum(meta, "simT");
        String failErr = saveNow(g2);
        boolean intact = st.length() == sizeBefore && st.lastModified() == timeBefore && readNum(meta, "simT") == metaBefore;
        say("failed save (state.sav.new blocked by a folder): reported=\"" + failErr + "\", indicator \"" + g2.saveStatus() + "\", previous state.sav untouched=" + intact
                + ", slot still playable=" + store.read(slot.dir).playable());
        check(failErr != null && g2.saveFailed() && intact && store.read(slot.dir).playable(), "a failed save was not handled safely");
        Files.delete(new File(blocker, "in-the-way").toPath());
        Files.delete(blocker.toPath());

        // 10 ---- Save & Leave (the pause-menu path)
        edt(app::openPause);
        boolean paused = await(() -> { synchronized (g2.state) { return g2.state.cur != null && g2.state.cur.paused; } }, 5_000);
        edt(app::saveAndLeave);
        boolean left = await(() -> app.session == null && app.menu != null, 20_000);
        app.awaitStops();
        boolean dead2 = !sp2.alive();
        SaveStore.Slot afterLeave = store.read(slot.dir);
        say(String.format("pause menu pauses the sim=%s; Save & Leave: back in the menu=%s, python pid %d still running=%s, slot simT=%.2f, paused flag stored=%s, thumbnail=%s",
                paused, left, sp2.pid(), !dead2, afterLeave.simT, afterLeave.userPaused, afterLeave.file(SaveStore.THUMB).isFile()));
        check(paused && left && dead2 && afterLeave.simT > after && !afterLeave.userPaused, "Save & Leave did not save and stop cleanly");

        // 11 ---- rename and duplicate
        store.rename(afterLeave, name + " renamed");
        SaveStore.Slot dup = store.duplicate(afterLeave);
        created.add(dup.dir);
        edt(() -> app.reloadSlots(null));
        boolean renamed = false, dupListed = false;
        for (SaveStore.Slot s : app.menu.slots) {
            if (s.id.equals(slot.id) && s.name.equals(name + " renamed")) renamed = true;
            if (s.id.equals(dup.id) && s.playable() && s.name.equals(name + " renamed copy")) dupListed = true;
        }
        say("rename -> \"" + name + " renamed\" listed=" + renamed + "; duplicate -> slot " + dup.id + " \"" + dup.name + "\" listed and playable=" + dupListed
                + ", own state file=" + (dup.file(SaveStore.STATE).length() == afterLeave.file(SaveStore.STATE).length()));
        check(renamed && dupListed, "rename / duplicate not reflected in the menu");

        // 12 ---- the duplicate loads on its own
        final SaveStore.Slot dupSlot = dup;
        edt(() -> app.startLoad(dupSlot));
        GameSession g3 = app.session;
        SimProcess sp3 = track(g3);
        boolean up3 = await(() -> g3.simRunning() && simT(g3) > afterLeave.simT + 0.5, 30_000);
        say(String.format("duplicate loads independently: running=%s, t=%.2f (saved %.2f)", up3, simT(g3), afterLeave.simT));
        check(up3, "the duplicated slot does not load");
        edt(app::leaveNow);
        app.awaitStops();
        check(!sp3.alive(), "python process of the duplicate survived");

        // 13 ---- a simulation that never answers a save: visible error after the timeout, nothing left behind
        SaveStore.Slot ghost = store.create("Selftest silent");
        created.add(ghost.dir);
        FakeSim fake = new FakeSim(9, 0.4, 1920, 900);
        FrameSource silent = new FrameSource() {
            @Override public void start(ViewerState s) { s.acceptLine(fake.hello()); for (int k = 0; k < 40; k++) s.acceptLine(fake.step()); }

            @Override public void send(String json) { }

            @Override public void stop() { }

            @Override public String describe() { return "silent test source"; }
        };
        GameSession g4 = new GameSession(app, ghost, silent, false, ghost.name);
        silent.start(g4.state);
        edt(() -> app.session = g4);
        long t0 = System.currentTimeMillis();
        String silentErr = saveNow(g4);
        edt(() -> app.session = null);
        say(String.format("simulation that never answers: reported after %.1f s: \"%s\"; slot folder left behind=%s", (System.currentTimeMillis() - t0) / 1000.0, silentErr,
                ghost.dir.exists()));
        check(silentErr != null && silentErr.contains("did not confirm") && !ghost.dir.exists(), "save timeout not handled");

        // 14 ---- delete: only inside saves/
        String refuse1 = tryDelete(fakeSlot(new File(store.root, ".." + File.separator + "viewer")));
        String refuse2 = tryDelete(fakeSlot(store.root));
        String refuse3 = tryDelete(fakeSlot(new File(store.root, dup.id + File.separator + ".." + File.separator + ".." + File.separator + "sim")));
        boolean simStill = new File(root, "sim" + File.separator + "precip_sim.py").isFile() && new File(root, "viewer" + File.separator + "src").isDirectory();
        say("delete refuses paths outside saves/: ../viewer -> \"" + refuse1 + "\"; saves itself -> \"" + refuse2 + "\"; <slot>/../../sim -> \"" + refuse3 + "\"; sim/ untouched=" + simStill);
        check(refuse1 != null && refuse2 != null && refuse3 != null && simStill, "delete accepted a path outside the saves folder");
        store.delete(dup);
        store.delete(store.read(slot.dir));
        edt(() -> app.reloadSlots(null));
        boolean gone = !slot.dir.exists() && !dup.dir.exists();
        boolean unlisted = true;
        for (SaveStore.Slot s : app.menu.slots) if (s.id.equals(slot.id) || s.id.equals(dup.id)) unlisted = false;
        say("delete: both test slots removed from disk=" + gone + ", from the menu=" + unlisted + "; " + app.menu.slots.size() + " slot(s) remain");
        check(gone && unlisted, "delete did not remove the slot");

        // ---- v2.2: a region preset — the hello carries it, the slot remembers it, a load brings it back
        NewSimScreen.Params rp = new NewSimScreen.Params();
        rp.name = name + " region"; rp.seed = 11L; rp.sizeIdx = 1; rp.terrain = "flat"; rp.settlements = "sparse"; rp.preset = "tibet";
        edt(() -> app.startNew(rp));
        GameSession gr = app.session;
        created.add(gr.slot.dir);
        SimProcess spr = track(gr);
        boolean upR = await(() -> gr.simRunning() || gr.simError() != null, 40_000);
        Model.Hello hr;
        synchronized (gr.state) { hr = gr.state.hello; }
        Preset pr = hr == null ? null : hr.preset;
        say("region preset \"tibet\": started=" + upR + ", hello mode=" + (hr == null ? "?" : hr.mode) + " terrain_kind=" + (hr == null ? "?" : hr.terrainKind) + ", preset="
                + (pr == null ? "MISSING" : String.format("%s \"%s\", transect \"%s\", %.0f km, highest %.0f m, station %s %.1f °C %.0f mm/yr wind \"%s\", %d landmarks, %d sources",
                pr.id, pr.name, pr.transect, pr.spanKm, pr.maxElevM, pr.station, pr.tempC, pr.annualPrecipMm, pr.wind, pr.landmarks.size(), pr.sources.size())));
        check(upR && gr.simError() == null && pr != null && pr.id.equals("tibet") && "preset".equals(hr.terrainKind) && !pr.landmarks.isEmpty() && !pr.sources.isEmpty(),
                "the preset world did not start with a preset block");
        boolean lmOk = pr != null;
        if (pr != null) for (Preset.Landmark l : pr.landmarks) lmOk &= l.x >= 0 && l.x <= hr.w && !l.name.isEmpty();
        check(lmOk, "a landmark lies outside the world");
        boolean savedR = await(gr::everSaved, 25_000);
        Map<String, Object> metaR = savedR ? readJson(gr.slot.file(SaveStore.META)) : null;
        edt(app::leaveNow);
        app.awaitStops();
        SaveStore.Slot regionSlot = store.read(gr.slot.dir);
        say("its slot: saved=" + savedR + ", meta.json preset=\"" + Json.str(metaR, "preset", "") + "\" presetName=\"" + Json.str(metaR, "presetName", "") + "\" terrain=\""
                + Json.str(metaR, "terrain", "") + "\"; listed as presetId=\"" + regionSlot.presetId + "\" playable=" + regionSlot.playable());
        check(savedR && "tibet".equals(Json.str(metaR, "preset", "")) && regionSlot.presetId.equals("tibet") && !regionSlot.presetName.isEmpty() && regionSlot.playable(),
                "the slot does not remember its preset");
        edt(() -> app.startLoad(regionSlot));
        GameSession gr2 = app.session;
        SimProcess spr2 = track(gr2);
        boolean upR2 = await(() -> gr2.simRunning() || gr2.simError() != null, 40_000);
        Model.Hello hr2;
        synchronized (gr2.state) { hr2 = gr2.state.hello; }
        say("loading it again: running=" + upR2 + ", loaded=" + (hr2 != null && hr2.loaded) + ", preset in the hello=" + (hr2 == null || hr2.preset == null ? "MISSING" : hr2.preset.id));
        check(upR2 && hr2 != null && hr2.loaded && hr2.preset != null && hr2.preset.id.equals("tibet"), "the loaded preset world lost its preset");
        edt(app::leaveNow);
        app.awaitStops();
        check(!spr.alive() && !spr2.alive(), "a python process of the preset world survived");
        store.delete(store.read(gr.slot.dir));

        // ---- v2.2: the two lesson modes against the real simulation: no slot, no save, Esc-style leave stops python
        File[] dirsBefore = store.root.listFiles(File::isDirectory);
        HurricaneScreen[] hsRef = new HurricaneScreen[1];
        edt(() -> hsRef[0] = app.startHurricane());
        GameSession gh = app.session;
        SimProcess sph = track(gh);
        boolean upH = await(() -> { synchronized (gh.state) { return gh.state.hurricane != null && gh.state.hurricane.cur != null; } } , 40_000);
        HurricaneModel hm;
        Model.Hello hh;
        synchronized (gh.state) { hm = gh.state.hurricane; hh = gh.state.hello; }
        say("hurricane mode: started=" + upH + (hm == null ? "" : String.format(", hello mode=%s, domain %.0f km x %.0f km, %d stages, %d tour chapters, %d sources, first stage \"%s\"",
                hh.mode, hm.radiusKm, hm.topKm, hm.stages.size(), hm.lesson.size(), hm.sources.size(), hm.stages.isEmpty() ? "" : hm.stages.get(0).name)));
        check(upH && gh.simError() == null && hm != null && "hurricane".equals(hh.mode) && hm.stages.size() == 10 && hm.lesson.size() >= 6 && gh.slot == null && gh.mode.equals("hurricane"),
                "the hurricane mode did not start as specified");
        if (upH && hm != null) {
            HurricaneModel.Frame f0;
            synchronized (gh.state) { f0 = hm.cur; }
            boolean shaped = f0.hasSection() && f0.r.length > 4 && f0.wind.length == f0.r.length && f0.bands.length > 0 && f0.u.length == f0.cols * f0.rows;
            edt(() -> hsRef[0].pickStage("cat3"));
            boolean atCat3 = await(() -> { synchronized (gh.state) { return hm.cur.stage.equals("cat3") && hm.cur.category >= 3 && !hm.cur.auto; } }, 30_000);
            HurricaneModel.Frame f3;
            synchronized (gh.state) { f3 = hm.cur; }
            edt(() -> hsRef[0].toggleAuto());
            boolean autoOn = await(() -> { synchronized (gh.state) { return hm.cur.auto; } }, 5_000);
            edt(() -> hsRef[0].togglePause());
            boolean pausedH = await(() -> { synchronized (gh.state) { return hm.cur.paused; } }, 5_000);
            String[] saveErr = {null};
            edt(() -> gh.requestSave("Saved", e -> saveErr[0] = e));
            say(String.format("first frame: section %dx%d, %d profile points, %d bands, complete=%s; stage command -> cat3 reached=%s (%.0f kt, %.0f hPa, eye %.0f km, clarity %.2f); auto on=%s; pause=%s; save refused=\"%s\"",
                    f0.cols, f0.rows, f0.r.length, f0.bands.length, shaped, atCat3, f3.vmaxKt, f3.pressure, f3.eye, f3.eyeClarity, autoOn, pausedH, saveErr[0]));
            check(shaped && atCat3 && autoOn && pausedH && saveErr[0] != null, "the hurricane commands did not behave as specified");
        }
        edt(app::leaveNow);
        app.awaitStops();
        check(!sph.alive() && app.session == null && app.menu != null, "leaving the hurricane mode did not stop python / return to the menu");

        ShowcaseScreen[] scRef = new ShowcaseScreen[1];
        edt(() -> scRef[0] = app.startShowcase("cirrus"));
        GameSession gc = app.session;
        SimProcess spc = track(gc);
        boolean upC = await(() -> { synchronized (gc.state) { return gc.state.cur != null && gc.state.cur.showcaseType != null; } }, 40_000);
        Model.Hello hc;
        Model.Frame fc;
        synchronized (gc.state) { hc = gc.state.hello; fc = gc.state.cur; }
        Map<String, Object> lessons = hc == null ? null : Json.obj(hc.showcaseRaw, "lessons");
        say("cloud formations: started=" + upC + (hc == null || fc == null ? "" : String.format(", hello mode=%s world %dx%d, %d lessons, frame type=%s cycle=%d clouds=%d, wind grid %dx%d, %d streamlines",
                hc.mode, hc.w, hc.h, lessons == null ? 0 : lessons.size(), fc.showcaseType, fc.showcaseCycle, fc.clouds.size(), fc.wCols, fc.wRows, fc.streamlines.size())));
        check(upC && hc != null && "showcase".equals(hc.mode) && lessons != null && lessons.size() >= 8 && "cirrus".equals(fc.showcaseType) && fc.clouds.size() <= 1 && gc.slot == null,
                "cloud formations did not start as specified");
        if (upC) {
            edt(() -> scRef[0].pick("cumulus"));
            boolean sw = await(() -> { synchronized (gc.state) { return "cumulus".equals(gc.state.cur.showcaseType) && gc.state.cur.showcaseCycle == 1; } }, 15_000);
            boolean one = await(() -> { synchronized (gc.state) { Model.Frame f = gc.state.cur; return f.clouds.size() == 1 && f.clouds.get(0).type.equals("cumulus") && f.showcaseCloudId == f.clouds.get(0).id; } }, 15_000);
            say("tab switch -> cumulus: type switched=" + sw + ", exactly one cumulus on stage (cloud_id matches)=" + one);
            check(sw && one, "switching the showcase type did not work");
            // v2.3 (Viewer Sandbox): the ninth tab, the supercell, with its lifetime tour
            edt(() -> scRef[0].pick("supercell"));
            boolean sup = await(() -> { synchronized (gc.state) { Model.Frame f = gc.state.cur; return "supercell".equals(f.showcaseType) && f.tourStep != null && f.clouds.size() == 1
                    && f.clouds.get(0).kind == Model.SUPERCELL; } }, 20_000);
            List<Object> tour = Json.arr(Json.obj(hc.showcaseRaw, "tours"), "supercell");
            String stepNow;
            synchronized (gc.state) { stepNow = gc.state.cur.tourStep; }
            say("tab switch -> supercell: one supercell on stage with a tour step=" + sup + " (\"" + stepNow + "\"), lifetime tour steps in the hello=" + (tour == null ? 0 : tour.size())
                    + ", lessons=" + lessons.size());
            check(sup && tour != null && tour.size() == 8 && lessons.containsKey("supercell"), "the supercell tab of Cloud Formations or its lifetime tour is missing");
        }
        edt(app::leaveNow);
        app.awaitStops();
        File[] dirsAfter = store.root.listFiles(File::isDirectory);
        say("after both lesson modes: python gone=" + (!sph.alive() && !spc.alive()) + ", save folders " + (dirsBefore == null ? 0 : dirsBefore.length) + " -> " + (dirsAfter == null ? 0 : dirsAfter.length));
        check(!spc.alive() && (dirsBefore == null ? 0 : dirsBefore.length) == (dirsAfter == null ? 0 : dirsAfter.length), "a lesson mode left a python process or a save folder behind");

        // ---- v2.3: the world simulation against the real simulation; opening a location starts a sandbox of that preset in the map's month
        WorldScreen[] wsRef = new WorldScreen[1];
        edt(() -> wsRef[0] = app.startWorld());
        GameSession gw = app.session;
        SimProcess spw = track(gw);
        boolean upW = await(() -> { synchronized (gw.state) { return gw.state.world != null && gw.state.world.cur != null && gw.state.world.cur.hasGrid(); } }, 40_000);
        WorldModel wm;
        Model.Hello hw;
        synchronized (gw.state) { wm = gw.state.world; hw = gw.state.hello; }
        int unknownRegions = 0, strayDots = 0;
        if (wm != null) {
            WorldMap map = wsRef[0].map;
            for (String code : wm.regions.keySet()) if (map.region(code) == null) unknownRegions++;
            // every dot lies in (or, for a coastal station on the 110m map, right next to) the region the simulation says it belongs to
            for (WorldModel.Loc l : wm.locations) {
                WorldMap.Region r = map.region(l.region);
                if (r == null) continue;
                boolean near = false;
                for (double dx = -1.5; dx <= 1.5 && !near; dx += 0.75) for (double dy = -1.5; dy <= 1.5 && !near; dy += 0.75) near = r.contains(l.lon + dx, l.lat + dy);
                if (!near) strayDots++;
            }
        }
        say("world mode: started=" + upW + (wm == null || hw == null ? "" : String.format(", hello mode=%s, grid %dx%d, %d locations, %d regions (%d not in the map files, %d dots outside their region), year %s days of %s s, %d sources",
                hw.mode, wm.cols, wm.rows, wm.locations.size(), wm.regions.size(), unknownRegions, strayDots, wm.yearDays, wm.dayLengthS, wm.sources.size())));
        check(upW && gw.simError() == null && wm != null && "world".equals(hw.mode) && wm.cols == 72 && wm.rows == 36 && wm.locations.size() >= 6 && !wm.regions.isEmpty()
                && unknownRegions == 0 && strayDots == 0 && gw.slot == null && gw.mode.equals("world"), "the world mode did not start as specified: " + gw.simError());
        if (upW && wm != null) {
            WorldModel.Frame w0;
            synchronized (gw.state) { w0 = wm.cur; }
            boolean shaped = w0.temp.length == 72 * 36 && w0.pressure.length == 72 * 36 && w0.u.length == 72 * 36 && w0.now.size() == wm.locations.size() && w0.systems.length > 0;
            edt(() -> wsRef[0].setMonth(7.5));
            boolean july = await(() -> { synchronized (gw.state) { return Math.abs(wm.cur.month - 7.5) < 0.35; } }, 30_000);
            edt(() -> wsRef[0].changeSpeed(+1));
            boolean faster = await(() -> { synchronized (gw.state) { return wm.cur.speed > 1.5; } }, 5_000);
            String[] saveErrW = {null};
            edt(() -> gw.requestSave("Saved", e -> saveErrW[0] = e));
            WorldModel.Loc loc = wm.loc("washington") != null ? wm.loc("washington") : wm.locations.get(0);
            WorldModel.Now now;
            WorldModel.Frame wj;
            synchronized (gw.state) { wj = wm.cur; now = wj.now.get(loc.id); }
            say(String.format("first frame: grids complete=%s, %d systems, %d readouts; season command -> month %.2f (July reached=%s); speed x%s; save refused=\"%s\"; %s now: %s",
                    shaped, w0.systems.length, w0.now.size(), wj.month, july, wj.speed, saveErrW[0], loc.name,
                    now == null ? "no readout" : String.format("%.1f °C, %s, local %s, month %.2f", now.tempC, now.weather, WorldModel.clock(now.localHour), now.month)));
            check(shaped && july && faster && saveErrW[0] != null && now != null, "the world commands did not behave as specified");
            // open the location: the world sim stops, a new sandbox slot of that preset starts in the month the map showed
            edt(() -> wsRef[0].open(loc));
            GameSession go = app.session;
            boolean switched = go != gw && go != null && go.slot != null;
            if (switched) created.add(go.slot.dir);
            SimProcess spo = switched ? track(go) : null;
            boolean upO = switched && await(() -> go.simRunning() || go.simError() != null, 40_000);
            Model.Hello ho = null;
            if (switched) synchronized (go.state) { ho = go.state.hello; }
            List<String> la = app.lastWorldLaunch;
            String monthArg = la == null || la.indexOf("--month") < 0 ? "" : la.get(la.indexOf("--month") + 1), hourArg = la == null || la.indexOf("--hour") < 0 ? "" : la.get(la.indexOf("--hour") + 1);
            boolean argsOk = la != null && la.indexOf("--preset") >= 0 && loc.id.equals(la.get(la.indexOf("--preset") + 1)) && !monthArg.isEmpty() && now != null
                    && Math.abs(Double.parseDouble(monthArg) - now.month) < 0.3 && !hourArg.isEmpty();
            say("open " + loc.name + ": sandbox slot=" + (switched ? "\"" + go.slot.name + "\"" : "none") + ", args " + (la == null ? "none" : String.join(" ", la)) + ", started=" + upO
                    + ", hello preset=" + (ho == null || ho.preset == null ? "none" : ho.preset.id) + (switched && go.simError() != null ? ", error: " + go.simError().split("\n")[0] : ""));
            check(switched && argsOk && upO && go.simError() == null && ho != null && ho.preset != null && ho.preset.id.equals(loc.id) && go.mode.equals("sandbox")
                    && go.slot.presetId.equals(loc.id) && go.slot.name.startsWith(loc.name + " — "), "opening a location from the world map did not start a sandbox of that preset in the map's month");
            edt(app::leaveNow);
            app.awaitStops();
            check(!spw.alive() && (spo == null || !spo.alive()) && app.session == null && app.menu != null, "the world simulation or the sandbox opened from it left a python process behind");
            if (switched) { try { store.delete(store.read(go.slot.dir)); } catch (Exception ignored) { } }
        } else {
            edt(app::leaveNow);
            app.awaitStops();
        }

        // ---- v2.3 (Viewer Sandbox): a world started in a chosen month and at a chosen hour; the climate keys and commands work
        NewSimScreen.Params mp = new NewSimScreen.Params();
        mp.name = name + " july"; mp.seed = 11L; mp.sizeIdx = 0; mp.terrain = "rolling"; mp.settlements = "sparse"; mp.month = 7.5; mp.hour = 15.0;
        edt(() -> app.startNew(mp));
        GameSession g6 = app.session;
        created.add(g6.slot.dir);
        SimProcess sp6 = track(g6);
        boolean up6 = await(() -> g6.simRunning() && simT(g6) >= 0.5, 30_000);
        Model.Hello h6;
        Model.Frame f6;
        synchronized (g6.state) { h6 = g6.state.hello; f6 = g6.state.cur; }
        String args6 = String.join(" ", sp6.args());
        boolean climate6 = up6 && f6 != null && f6.hasCalendar() && f6.temp != null && f6.atmosphere != null && h6 != null && h6.climate != null;
        say("a world started in mid July at 15:00: args \"" + args6 + "\", started=" + up6 + (climate6 ? String.format(java.util.Locale.ROOT,
                ", month %.2f (%s), clock %.2f h, surface %.2f °C = base %.2f + season %.2f + day %.2f + gas %.2f + you %.2f, humidity %.2f, CO2 %.0f, spawn %.1f/min, %d roads, %d cars",
                f6.month, f6.season, f6.timeOfDay * 24, f6.surfaceTemp, f6.temp.baseC, f6.temp.seasonC, f6.temp.diurnalC, f6.temp.gasC, f6.temp.userC, f6.atmosphere.humidity,
                f6.atmosphere.co2, f6.atmosphere.spawnPerMin, h6.roads.size(), f6.cars.size()) : ", the frames carry no v2.3 climate keys"));
        check(args6.contains("--month 7.50") && args6.contains("--hour 15.00") && climate6 && Math.abs(f6.month - 7.5) < 0.25 && Math.abs(f6.timeOfDay * 24 - 15) < 0.6
                && "summer".equals(f6.season) && Math.abs(f6.temp.baseC + f6.temp.seasonC + f6.temp.diurnalC + f6.temp.gasC + f6.temp.userC - f6.surfaceTemp) < 0.06
                && Math.abs(g6.slot.startMonth - 7.5) < 1e-9, "a world started with --month / --hour did not open in that month and hour with the v2.3 climate keys");
        if (climate6) {
            g6.source.send("{\"cmd\":\"gas\",\"name\":\"co2\",\"value\":840}");
            boolean co = await(() -> { synchronized (g6.state) { Model.Frame f = g6.state.cur; return f.atmosphere != null && Math.abs(f.atmosphere.co2 - 840) < 1 && Math.abs(f.temp.gasC - 3.0) < 0.2; } }, 10_000);
            g6.source.send("{\"cmd\":\"spawn\",\"x\":900,\"y\":250,\"type\":\"supercell\"}");
            boolean spw6 = await(() -> { synchronized (g6.state) { for (Model.Cloud c : g6.state.cur.clouds) if (c.kind == Model.SUPERCELL && c.name.startsWith("Sp-")) return true; return false; } }, 10_000);
            g6.source.send("{\"cmd\":\"season\",\"month\":1.5}");
            boolean winter = await(() -> { synchronized (g6.state) { return "winter".equals(g6.state.cur.season) && Math.abs(g6.state.cur.month - 1.5) < 0.3; } }, 10_000);
            say("commands: CO2 doubled to 840 ppm adds 3 °C=" + co + ", spawn with type supercell makes an Sp- cloud=" + spw6 + ", season jumps to January (winter)=" + winter);
            check(co && spw6 && winter, "the gas / spawn-with-type / season commands did not behave as specified");
        }
        edt(app::leaveNow);
        app.awaitStops();
        check(!sp6.alive() && app.session == null, "leaving the July world did not stop python");
        try { store.delete(store.read(g6.slot.dir)); } catch (Exception ignored) { }

        // ---- quitting from a running game (window close / Quit to desktop): saves first, then exits, python gone
        NewSimScreen.Params q = new NewSimScreen.Params();
        q.name = name + " quit"; q.seed = 7L; q.sizeIdx = 0; q.terrain = "flat"; q.settlements = "none";
        boolean[] exited = {false};
        app.onExit = () -> exited[0] = true;
        edt(() -> app.startNew(q));
        GameSession g5 = app.session;
        created.add(g5.slot.dir);
        SimProcess sp5 = track(g5);
        await(() -> g5.simRunning() && simT(g5) >= 1.0, 30_000);
        edt(app::requestQuit);
        long q0 = System.currentTimeMillis();
        while (!exited[0] && System.currentTimeMillis() - q0 < 20_000) { edt(() -> { }); Thread.sleep(40); }
        SaveStore.Slot quitSlot = store.read(g5.slot.dir);
        say(String.format("quit from a running game: exit ran=%s after %.1f s, saved first=%s (simT=%.2f), python pid %d still running=%s",
                exited[0], (System.currentTimeMillis() - q0) / 1000.0, quitSlot.playable(), quitSlot.simT, sp5.pid(), sp5.alive()));
        check(exited[0] && quitSlot.playable() && quitSlot.simT >= 1.0 && !sp5.alive(), "quitting did not save and stop the simulation");
        store.delete(quitSlot);

        // ---- no python left
        boolean anyAlive = false;
        StringBuilder pids = new StringBuilder();
        for (SimProcess sp : sims) {
            boolean alive = sp.alive() || ProcessHandle.of(sp.pid()).map(ProcessHandle::isAlive).orElse(false);
            anyAlive |= alive;
            pids.append(sp.pid()).append(alive ? " ALIVE  " : " gone  ");
        }
        say("python processes started by this run: " + pids.toString().trim());
        check(!anyAlive, "a python process is still running");
    }

    private static int countTemp(File dir) {
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".tmp") || n.equals(SaveStore.STATE_NEW));
        return fs == null ? 0 : fs.length;
    }

    private static double readNum(File meta, String key) throws Exception { return Json.num(readJson(meta), key, Double.NaN); }

    private static double readNumQuiet(File meta, String key) {
        try { return readNum(meta, key); } catch (Exception ex) { return Double.NaN; }
    }

    private static SaveStore.Slot fakeSlot(File dir) {
        SaveStore.Slot s = new SaveStore.Slot();
        s.dir = dir;
        s.id = dir.getName();
        return s;
    }

    private String tryDelete(SaveStore.Slot s) {
        try {
            store.delete(s);
            return null;
        } catch (java.io.IOException ex) {
            return ex.getMessage();
        }
    }

    private void cleanup() {
        try {
            edt(() -> {
                if (app.session != null) app.leaveNow();
            });
        } catch (RuntimeException ignored) { }
        app.awaitStops();
        for (SimProcess sp : sims) if (sp.alive()) sp.stop();
        for (File d : created) {
            if (!d.exists()) continue;
            try {
                // undo the "blocked" folder of step 9 if the run stopped there
                File blocker = new File(d, SaveStore.STATE_NEW);
                if (blocker.isDirectory()) { new File(blocker, "in-the-way").delete(); blocker.delete(); }
                store.delete(store.read(d));
            } catch (Exception ex) {
                System.out.println("[selftest] could not remove " + d + ": " + ex.getMessage());
            }
        }
        app.io.shutdown();
        if (tempSaves != null) removeTemp(tempSaves);
    }

    /** Remove the temp saves folder this run made (only ever a folder created by {@code createTempDirectory} above). */
    private static void removeTemp(File dir) {
        try (java.util.stream.Stream<java.nio.file.Path> w = java.nio.file.Files.walk(dir.toPath())) {
            w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (java.io.IOException | RuntimeException ex) {
            System.out.println("[selftest] could not remove the temp saves folder " + dir + ": " + ex.getMessage());
        }
    }
}
