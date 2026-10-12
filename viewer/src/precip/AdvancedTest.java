package precip;

import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.swing.SwingUtilities;

/**
 * The checks of Settings &gt; Advanced (PLAN Phase J), run by {@code --selftest-ui} and {@code --selftest}
 * after {@link UiDriveTest} and {@link DebugTest}: the thirteen options in the settings file, the tab
 * and its controls at three window sizes, and what each option does to the picture, including that
 * flipping one while the game runs leaves no stale tile, light or cloud behind.
 * <p>
 * Like the other two it opens no window and needs no Python. Its settings file lives in a throw-away
 * folder in the system temp directory; the game's own {@code config/} and {@code saves/} are only
 * looked at (to prove they were not written).
 */
final class AdvancedTest {
    private static final int W = 1680, H = 760;

    private final File root, tmp;
    private final Settings settings;
    private final AppShell app;
    private final List<String> failures = new ArrayList<>();
    private int step;
    private int cw = W, ch = H;
    private BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);

    private AdvancedTest(File root, File tmp) {
        this.root = root;
        this.tmp = tmp;
        settings = new Settings(new File(tmp, "config"));     // a real file round trip, in the temp folder
        app = new AppShell(root, settings, new SaveStore(new File(tmp, "saves")));
    }

    static int run() {
        File tmp;
        try {
            tmp = Files.createTempDirectory("precip-advtest").toFile();
        } catch (java.io.IOException ex) {
            System.out.println("[advanced-test] cannot create a temp folder: " + ex.getMessage());
            return 1;
        }
        File root = Main.projectRoot();
        String realBefore = stamp(new File(root, "config/settings.json")) + " | " + stamp(new File(root, "saves"));
        Advanced keepAdv = Advanced.cur;
        boolean keepSafe = Renderer.epilepsySafe;
        int keepQuality = CloudPainter.quality;
        double keepScale = ViewerPanel.maxRenderScale;
        AdvancedTest t = new AdvancedTest(root, tmp);
        try {
            t.file();
            t.tab();
            t.pictures();
            t.lightning();
            t.clouds();
            t.check(realBefore.equals(stamp(new File(root, "config/settings.json")) + " | " + stamp(new File(root, "saves"))),
                    "the game's own config/settings.json and saves/ were not written by these checks (" + realBefore + ")");
        } catch (Throwable ex) {
            ex.printStackTrace();
            t.failures.add("exception: " + ex);
        } finally {
            edt(() -> { if (t.app.panel != null) t.app.panel.dispose(); });
            t.app.io.shutdown();
            Advanced.publish(keepAdv);
            Renderer.epilepsySafe = keepSafe;
            CloudPainter.quality = keepQuality;
            ViewerPanel.maxRenderScale = keepScale;
            deleteTree(tmp);
        }
        if (t.failures.isEmpty()) {
            System.out.println("[advanced-test] PASS (" + t.step + " checks)");
            return 0;
        }
        System.out.println("[advanced-test] FAIL");
        for (String f : t.failures) System.out.println("[advanced-test]   - " + f);
        return 1;
    }

    /** Size and time of a file, or the number of files and the newest time under a folder ("-" = not there). */
    private static String stamp(File f) {
        if (!f.exists()) return "-";
        if (f.isFile()) return f.length() + "b@" + f.lastModified();
        long[] acc = new long[3];
        walk(f, acc);
        return acc[0] + " files " + acc[1] + "b@" + acc[2];
    }

    private static void walk(File f, long[] acc) {
        File[] kids = f.listFiles();
        if (kids == null) { acc[0]++; acc[1] += f.length(); acc[2] = Math.max(acc[2], f.lastModified()); return; }
        for (File k : kids) walk(k, acc);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private void check(boolean ok, String what) {
        step++;
        System.out.println(String.format("[advanced-test] %2d  %s  %s", step, ok ? "ok  " : "FAIL", what));
        if (!ok) failures.add(what);
    }

    private static void edt(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    // ------------------------------------------------------------------ J2: the settings file

    private void file() throws Exception {
        File dir = new File(tmp, "filecfg");
        dir.mkdirs();
        Settings def = new Settings(dir);
        String plain = Settings.pretty(def.toJson());
        check(Settings.VERSION == 1 && def.adv == Advanced.DEFAULT && !plain.contains("advanced") && def.adv.cullHidden && def.adv.maxParticles == 100
                        && def.adv.renderScale == 0 && !def.adv.glintStar,
                "a new player's settings hold every Advanced option at its default (the rain glint a dot) and write no \"advanced\" key; the file version is still 1");
        Files.writeString(new File(dir, "settings.json").toPath(), "{\"version\":1,\"graphics\":{\"cloudQuality\":2}}", StandardCharsets.UTF_8);
        Settings old = Settings.load(dir);
        check(old.loadWarning == null && old.cloudQuality == 2 && old.adv.equals(Advanced.DEFAULT) && old.adv.cullHidden && !old.adv.glintStar,
                "a settings.json from before the tab loads with every option at its default (\"Don't render what you can't see\" on, the rain glint a dot)");
        old.adv = Advanced.allOn();
        old.save();
        String written = Files.readString(new File(dir, "settings.json").toPath(), StandardCharsets.UTF_8).replace(" ", "");
        Settings back = Settings.load(dir);
        boolean keys = written.contains("\"advanced\":{") && written.contains("\"maxParticles\":25") && written.contains("\"renderScale\":2");
        for (int k = 0; k < Advanced.FLAGS; k++) keys &= written.contains("\"" + Advanced.KEYS[k] + "\":" + (k != Advanced.CULL));
        boolean all = back.adv.equals(Advanced.allOn()) && back.adv.maxParticles == 25 && back.adv.renderScale == 2 && !back.adv.cullHidden && back.adv.lightningDetailOff
                && back.adv.globalLightning && back.adv.branchingOff && back.adv.simpleClouds && back.adv.noTint && back.adv.noFlash && back.adv.noSnow && back.adv.noTrails
                && back.adv.noShine && back.adv.noPuddleImpact && back.adv.glintStar;
        check(keys && all && back.cloudQuality == 2, "every option set to its other value is written under graphics.advanced (14 keys) and read back by a second load");
        // one option at a time: each key carries exactly its own switch
        boolean each = true;
        for (int k = 0; k < Advanced.FLAGS; k++) {
            Settings one = new Settings(dir);
            one.adv = Advanced.DEFAULT.toggle(k);
            one.save();
            Advanced got = Settings.load(dir).adv;
            each &= got.equals(Advanced.DEFAULT.toggle(k)) && got.flag(k) != Advanced.DEFAULT.flag(k);
            for (int j = 0; j < Advanced.FLAGS; j++) if (j != k) each &= got.flag(j) == Advanced.DEFAULT.flag(j);
        }
        check(each && Advanced.FLAGS == 12, "each of the twelve switches (the eleven on/off ones and the shape of the rain glint) round-trips on its own and moves no other");
        Files.writeString(new File(dir, "settings.json").toPath(),
                "{\"version\":1,\"graphics\":{\"advanced\":{\"maxParticles\":60,\"renderScale\":7,\"noSnow\":true,\"someFutureKey\":3,\"noTint\":\"yes\"}}}", StandardCharsets.UTF_8);
        Settings odd = Settings.load(dir);
        Files.writeString(new File(dir, "settings.json").toPath(), "{\"version\":1,\"graphics\":{\"advanced\":{\"maxParticles\":-40,\"renderScale\":-3}}}", StandardCharsets.UTF_8);
        Settings neg = Settings.load(dir);
        Files.writeString(new File(dir, "settings.json").toPath(), "{\"version\":1,\"graphics\":{\"cloudQuality\":0,\"advanced\":5}}", StandardCharsets.UTF_8);
        Settings notObj = Settings.load(dir);
        check(odd.loadWarning == null && odd.adv.maxParticles == 50 && odd.adv.renderScale == 2 && odd.adv.noSnow && !odd.adv.noTint && odd.adv.cullHidden
                        && neg.adv.maxParticles == 25 && neg.adv.renderScale == 0 && notObj.loadWarning == null && notObj.cloudQuality == 0 && notObj.adv.equals(Advanced.DEFAULT),
                "values out of range are clamped (60 % -> 50, scale 7 -> 0.5x, -40 % -> 25, scale -3 -> none), unknown keys and wrong types keep the default, a non-object \"advanced\" is ignored");
        // the render cap: the Graphics page and the Advanced page together, all nine combinations
        StringBuilder bad = new StringBuilder();
        double keepCap = ViewerPanel.maxRenderScale;
        Advanced keepAdv = settings.adv;
        int keepRs = settings.renderScale;
        for (int g = 0; g < 3; g++) for (int a = 0; a < 3; a++) {
            settings.renderScale = g;
            settings.adv = Advanced.DEFAULT.withRenderScale(a);
            edt(app::applySettings);
            double gv = Settings.SCALE_VALUES[g], av = Advanced.SCALES[a], want = av == 0 ? gv : gv == 0 ? av : Math.min(gv, av);
            if (ViewerPanel.maxRenderScale != want) bad.append(' ').append(Settings.SCALE_NAMES[g]).append('/').append(Advanced.SCALE_NAMES[a]).append('=').append(ViewerPanel.maxRenderScale);
        }
        settings.renderScale = keepRs;
        settings.adv = keepAdv;
        edt(app::applySettings);
        check(bad.length() == 0 && ViewerPanel.maxRenderScale == Settings.SCALE_VALUES[keepRs],
                "render resolution: the Advanced value only lowers the Graphics one, in all nine combinations (1.0 / 1.5 / native x none / 0.75 / 0.5)" + bad);
        ViewerPanel.maxRenderScale = keepCap;
        // the command-line form
        Advanced cli = Advanced.DEFAULT.parse("particles=50,render=0.5,noTrails,global,cullHidden=off");
        boolean threw = false;
        try { Advanced.DEFAULT.parse("noSuchThing"); } catch (IllegalArgumentException ex) { threw = true; }
        check(cli.maxParticles == 50 && cli.renderScale == 2 && cli.noTrails && cli.globalLightning && !cli.cullHidden && !cli.noSnow && threw
                        && Advanced.DEFAULT.parse("all").equals(Advanced.allOn()), "--adv reads names, short names and =off, and refuses a name nobody knows");
        // the rain glint: a dot unless the file or the command line says star
        Advanced gs = Advanced.DEFAULT.parse("glint=star"), gd = gs.parse("glint=dot");
        boolean badShape = false;
        try { Advanced.DEFAULT.parse("glint=blob"); } catch (IllegalArgumentException ex) { badShape = true; }
        Files.writeString(new File(dir, "settings.json").toPath(), "{\"version\":1,\"graphics\":{\"advanced\":{\"glintStar\":true}}}", StandardCharsets.UTF_8);
        Settings starFile = Settings.load(dir);
        Files.writeString(new File(dir, "settings.json").toPath(), "{\"version\":1,\"graphics\":{\"advanced\":{\"noShine\":true}}}", StandardCharsets.UTF_8);
        Settings dullFile = Settings.load(dir);
        starFile.save();
        String starText = Files.readString(new File(dir, "settings.json").toPath(), StandardCharsets.UTF_8).replace(" ", "");
        check(!Advanced.DEFAULT.glintStar && gs.glintStar && gs.equals(Advanced.DEFAULT.with(Advanced.GLINT_STAR, true)) && gd.equals(Advanced.DEFAULT)
                        && Advanced.DEFAULT.parse("glintStar").glintStar && Advanced.DEFAULT.parse("star").glintStar && !gs.parse("glintStar=off").glintStar && badShape
                        && Advanced.DEFAULT.parse(gs.toString()).equals(gs)
                        && starFile.loadWarning == null && starFile.adv.equals(gs) && !starFile.adv.noShine && dullFile.adv.noShine && !dullFile.adv.glintStar
                        && starText.contains("\"glintStar\":true") && starText.contains("\"noShine\":false"),
                "the rain glint is a dot unless told otherwise: \"glintStar\":true in the file or --adv glint=star (also glintStar, star) makes it the star, glint=dot or glintStar=off "
                        + "the dot again, any other shape is refused; a file that only switches \"No shining particles\" on leaves the shape a dot");
    }

    // ------------------------------------------------------------------ J4: the tab

    private void paint() {
        edt(() -> {
            ViewerPanel p = app.panel;
            if (p.getWidth() != cw || p.getHeight() != ch) p.setSize(cw, ch);
            Graphics2D g = canvas.createGraphics();
            p.paint(g);
            g.dispose();
        });
    }

    private void size(int w, int h) {
        cw = w; ch = h;
        canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    }

    private void key(int code) {
        paint();
        edt(() -> {
            ViewerPanel p = app.panel;
            KeyEvent press = new KeyEvent(p, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED);
            for (KeyListener l : p.getKeyListeners()) l.keyPressed(press);
        });
        paint();
    }

    private void click(double x, double y) {
        paint();
        final int ix = (int) Math.round(x), iy = (int) Math.round(y);
        edt(() -> {
            ViewerPanel p = app.panel;
            MouseEvent press = new MouseEvent(p, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, ix, iy, 1, false, MouseEvent.BUTTON1);
            MouseEvent release = new MouseEvent(p, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, ix, iy, 1, false, MouseEvent.BUTTON1);
            for (MouseListener l : p.getMouseListeners()) l.mousePressed(press);
            for (MouseListener l : p.getMouseListeners()) l.mouseReleased(release);
        });
        paint();
    }

    private void move(double x, double y) {
        final int ix = (int) Math.round(x), iy = (int) Math.round(y);
        edt(() -> {
            ViewerPanel p = app.panel;
            MouseEvent mv = new MouseEvent(p, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, ix, iy, 0, false, MouseEvent.NOBUTTON);
            for (java.awt.event.MouseMotionListener l : p.getMouseMotionListeners()) l.mouseMoved(mv);
        });
        paint();
    }

    private void tab() throws Exception {
        edt(() -> { app.applySettings(); app.showMenu(null); });
        paint();
        edt(() -> app.openSettings(SettingsScreen.GRAPHICS));
        paint();
        SettingsScreen[] top = {null};
        edt(() -> top[0] = (SettingsScreen) app.top());
        final SettingsScreen ss = top[0];
        key(KeyEvent.VK_TAB);
        boolean byKey = ss.tab == SettingsScreen.ADVANCED;
        key(KeyEvent.VK_TAB);
        boolean next = ss.tab == SettingsScreen.GAME;
        // the tab rows are 44 px apart from 62 px under the dialog's top; "Advanced" is the third
        double dx = Math.round((W - 900) / 2.0), dy = Math.round((H - 600) / 2.0);
        click(dx + 16 + 75, dy + 62 + 44 * SettingsScreen.ADVANCED + 19);
        check(byKey && next && ss.tab == SettingsScreen.ADVANCED && SettingsScreen.TABS.length == 6 && SettingsScreen.TABS[SettingsScreen.ADVANCED].equals("Advanced")
                        && SettingsScreen.TABS[SettingsScreen.CONTROLS].equals("Controls") && SettingsScreen.TABS[SettingsScreen.GAME].equals("Game"),
                "Settings has a fifth tab \"Advanced\" after Graphics: Tab reaches it from Graphics, goes on to Game, and a click on its row selects it");

        // ---- layout at the three sizes: every control inside the page, no label wider than its column, nothing overlapping
        StringBuilder bad = new StringBuilder();
        java.awt.FontMetrics fm = canvas.createGraphics().getFontMetrics(Ui.F_MED);
        int widest = 0;
        for (String l : SettingsScreen.ADV_LABELS) widest = Math.max(widest, fm.stringWidth(l));
        for (int[] wh : new int[][]{{1680, 760}, {1280, 720}, {1024, 581}}) {
            size(wh[0], wh[1]);
            paint();
            double dw = Math.min(900, wh[0] - 24), dh = Math.min(600, wh[1] - 24), x = Math.round((wh[0] - dw) / 2), y = Math.round((wh[1] - dh) / 2);
            double cx = x + 204, cy = y + 62, pw = dw - 204 - 24, ph = dh - 62 - 66;
            double[][] rc = ss.advRects;
            for (int k = 0; k < rc.length; k++) {
                double[] r = rc[k];
                if (r == null || r[0] < cx - 0.5 || r[1] < cy - 0.5 || r[0] + r[2] > cx + pw + 0.5 || r[1] + r[3] > cy + ph + 0.5) bad.append(' ').append(wh[0]).append('x').append(wh[1]).append(":control").append(k);
                for (int j = 0; j < k && r != null; j++) {
                    double[] q = rc[j];
                    if (q != null && r[0] < q[0] + q[2] && q[0] < r[0] + r[2] && r[1] < q[1] + q[3] && q[1] < r[1] + r[3]) bad.append(' ').append(wh[0]).append('x').append(wh[1]).append(":overlap").append(j).append('/').append(k);
                }
            }
            if (widest > pw / 2 - 58 - 12) bad.append(' ').append(wh[0]).append('x').append(wh[1]).append(":label ").append(widest).append(" > ").append(pw / 2 - 70);
            // the last switch row ends above the dialog's bottom line (the hint and the Done button)
            double lowest = 0;
            for (double[] r : rc) if (r != null) lowest = Math.max(lowest, r[1] + r[3]);
            if (lowest > y + dh - 62) bad.append(' ').append(wh[0]).append('x').append(wh[1]).append(":rows reach the bottom line");
        }
        check(bad.length() == 0 && ss.advRects.length == 14, "the Advanced page fits at 1680x760, 1280x720 and 1024x581: 14 controls inside the page, none overlapping, the widest label ("
                + widest + " px) inside its column" + bad);

        // ---- every control does what it says: a click flips the field, publishes it and writes the temp settings file
        size(W, H);
        paint();
        settings.adv = Advanced.DEFAULT;
        edt(app::applySettings);
        paint();
        StringBuilder wrong = new StringBuilder();
        for (int k = 0; k < SettingsScreen.ADV_ORDER.length; k++) {
            int bit = SettingsScreen.ADV_ORDER[k];
            double[] r = ss.advRects[2 + k];
            boolean before = settings.adv.flag(bit);
            click(r[0] + r[2] / 2, r[1] + r[3] / 2);
            boolean on = settings.adv.flag(bit) != before && Advanced.cur.equals(settings.adv) && settings.adv.equals(Advanced.DEFAULT.toggle(bit));
            click(r[0] + r[2] / 2, r[1] + r[3] / 2);
            if (!on || !settings.adv.equals(Advanced.DEFAULT) || !Advanced.cur.equals(Advanced.DEFAULT)) wrong.append(' ').append(Advanced.KEYS[bit]);
        }
        // the rain glint: a choice of two (Dot | Star) under the right column
        double[] gr = ss.advRects[2 + SettingsScreen.ADV_ORDER.length];
        click(gr[0] + gr[2] * 3 / 4, gr[1] + gr[3] / 2);
        String starDisk = settings.file().isFile() ? Files.readString(settings.file().toPath(), StandardCharsets.UTF_8).replace(" ", "") : "";
        boolean star = settings.adv.glintStar && Advanced.cur.glintStar && settings.adv.equals(Advanced.DEFAULT.with(Advanced.GLINT_STAR, true)) && starDisk.contains("\"glintStar\":true");
        click(gr[0] + gr[2] * 3 / 4, gr[1] + gr[3] / 2);         // (the chosen cell again: nothing changes)
        star &= settings.adv.glintStar;
        click(gr[0] + gr[2] / 4, gr[1] + gr[3] / 2);
        if (!star || !settings.adv.equals(Advanced.DEFAULT) || !Advanced.cur.equals(Advanced.DEFAULT)) wrong.append(" glintStar");
        double[] pr = ss.advRects[0], rr = ss.advRects[1];
        click(pr[0] + pr[2] * 5 / 8, pr[1] + pr[3] / 2);         // the third of four cells: 50 %
        boolean p50 = settings.adv.maxParticles == 50 && Advanced.cur.maxParticles == 50;
        int[] sharp = new int[W * H], soft = new int[W * H];
        canvas.getRGB(0, 0, W, H, sharp, 0, W);
        click(rr[0] + rr[2] * 5 / 6, rr[1] + rr[3] / 2);         // the third of three cells: 0.5x
        boolean half = settings.adv.renderScale == 2 && ViewerPanel.maxRenderScale == 0.5;
        // the very next frame is the same scene drawn at half size and stretched: no blank frame, and the dialog on top stays sharp
        canvas.getRGB(0, 0, W, H, soft, 0, W);
        int far = 0, blank = 0, differs = 0;
        for (int o = 0; o < sharp.length; o++) {
            int a = sharp[o], b = soft[o];
            if (a != b) differs++;
            if (lum(b) < 6) blank++;
            if (Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) > 70 || Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) > 70 || Math.abs((a & 255) - (b & 255)) > 70) far++;
        }
        half &= differs > W * H / 50 && far < W * H / 50 && blank < W * H / 10;
        final String scaleNote = differs + " pixels softer, " + far + " far off, " + blank + " black";
        String onDisk = settings.file().isFile() ? Files.readString(settings.file().toPath(), StandardCharsets.UTF_8).replace(" ", "") : "";
        boolean saved = onDisk.contains("\"maxParticles\":50") && onDisk.contains("\"renderScale\":2") && settings.file().getAbsolutePath().startsWith(tmp.getAbsolutePath());
        click(pr[0] + pr[2] / 8, pr[1] + pr[3] / 2);
        click(rr[0] + rr[2] / 6, rr[1] + rr[3] / 2);
        String after = Files.readString(settings.file().toPath(), StandardCharsets.UTF_8);
        check(wrong.length() == 0 && p50 && half && saved && settings.adv.equals(Advanced.DEFAULT) && !after.contains("advanced")
                        && ViewerPanel.maxRenderScale == Settings.SCALE_VALUES[settings.renderScale],
                "each of the 11 switches, the Dot | Star choice of the rain glint and both value rows changes its own setting at a click, is in force at once (Advanced.cur) and is saved; back at the defaults the file has no \"advanced\" key again; "
                        + "0.5x shows on the next frame as the same picture, stretched (" + scaleNote + ")" + wrong);
        // the hint line names the switch under the pointer
        double[] g = ss.advRects[2 + 1];
        move(g[0] + 80, g[1] + 12);
        int version = Advanced.version;
        paint();
        int hintW = 0;
        java.awt.FontMetrics fs = canvas.createGraphics().getFontMetrics(Ui.F_SMALL);
        for (String h : SettingsScreen.ADV_HINTS) hintW = Math.max(hintW, fs.stringWidth(h));
        hintW = Math.max(hintW, fs.stringWidth(SettingsScreen.GLINT_HINT));
        boolean switchHint = SettingsScreen.ADV_HINTS[1].equals(ss.advHint());
        move(gr[0] + gr[2] + 40, gr[1] + 12);
        paint();
        // (the bottom line is the page's width less the Done button: 522 px in the smallest window)
        check(Advanced.version == version && switchHint && SettingsScreen.GLINT_HINT.equals(ss.advHint()) && hintW <= 900 - 204 - 24 - 150 && settings.adv.equals(Advanced.DEFAULT),
                "pointing at a switch or at the rain glint row shows its explanation on the bottom line (the longest is " + hintW + " px, the line holds 522 in the smallest window) and changes nothing");
        edt(() -> app.pop(ss));
    }

    // ------------------------------------------------------------------ the pictures

    private static ParallelRenderer renderer(int bands) {
        ParallelRenderer pr = new ParallelRenderer(bands);
        pr.shared.setAsync(false);
        Renderer r = pr.primary();
        r.showRightPanel = false; r.showLeftPanel = false; r.showMinimap = false; r.showLabels = false; r.showArrows = false; r.showWind = false; r.showClimate = false;
        return pr;
    }

    private static ViewerState world(long seed, double tod, int frames, String scene, String... cmds) {
        FakeSim sim = new FakeSim(seed, tod, 3840, 1080, null, null, Double.NaN, Double.NaN, scene);
        ViewerState st = new ViewerState();
        st.acceptLine(sim.hello());
        for (String c : cmds) sim.handle(c);
        for (int k = 0; k < frames; k++) st.acceptLine(sim.step());
        return st;
    }

    /** One still picture of the world with the given options in force (they stay in force afterwards). */
    private static int[] shot(ParallelRenderer pr, ViewerState st, Camera cam, Advanced adv) { return shot(pr, st, cam, adv, BufferedImage.TYPE_INT_RGB); }

    private static int[] shot(ParallelRenderer pr, ViewerState st, Camera cam, Advanced adv, int type) {
        Advanced.publish(adv);
        BufferedImage img = new BufferedImage(W, H, type);
        double t;
        synchronized (st) { t = st.cur.t; }
        pr.render(img, 1, 0, 0, W, H, st, cam, -1, t, "test");
        int[] out = new int[W * H];
        img.getRGB(0, 0, W, H, out, 0, W);
        return out;
    }

    private static int diff(int[] a, int[] b) {
        int n = 0;
        for (int k = 0; k < a.length; k++) if (a[k] != b[k]) n++;
        return n;
    }

    /** How many pixels of {@code a} are brighter than in {@code b} by more than {@code by} (the sum of the three channels). */
    private static int brighter(int[] a, int[] b, int by) {
        int n = 0;
        for (int k = 0; k < a.length; k++) if (lum(a[k]) - lum(b[k]) > by) n++;
        return n;
    }

    private static int lum(int rgb) { return ((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255); }

    /** The frame's particles of the given kinds only (the others are put back by {@link #restore}). */
    private static Object[] only(ViewerState st, int... kinds) {
        Model.Frame f = st.cur;
        Object[] keep = {f.particles, f.particleCount, f.particleRank};
        f.particleRank = null;
        float[] out = new float[f.particleCount * 7];
        int n = 0;
        for (int k = 0; k < f.particleCount; k++) {
            int kind = (int) f.particles[k * 7];
            boolean want = false;
            for (int q : kinds) want |= q == kind;
            if (want) { System.arraycopy(f.particles, k * 7, out, n * 7, 7); n++; }
        }
        f.particles = out;
        f.particleCount = n;
        return keep;
    }

    private static void restore(ViewerState st, Object[] keep) {
        st.cur.particles = (float[]) keep[0];
        st.cur.particleCount = (Integer) keep[1];
        st.cur.particleRank = keep.length > 2 ? (byte[]) keep[2] : null;
    }

    /** How many of the differing pixels lie further than {@code reach} device px from every one of the points (x, y pairs). */
    private static int strays(int[] a, int[] b, double[] pts, double reach) {
        // a coarse grid of the points, so the search stays cheap
        final int cell = (int) Math.ceil(reach) + 1, gw = W / cell + 3, gh = H / cell + 3;
        List<List<double[]>> grid = new ArrayList<>();
        for (int k = 0; k < gw * gh; k++) grid.add(null);
        for (int k = 0; k + 1 < pts.length; k += 2) {
            int gx = (int) Math.floor(pts[k] / cell) + 1, gy = (int) Math.floor(pts[k + 1] / cell) + 1;
            if (gx < 0 || gy < 0 || gx >= gw || gy >= gh) continue;
            if (grid.get(gy * gw + gx) == null) grid.set(gy * gw + gx, new ArrayList<>());
            grid.get(gy * gw + gx).add(new double[]{pts[k], pts[k + 1]});
        }
        int far = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (a[y * W + x] == b[y * W + x]) continue;
            boolean near = false;
            int gx = x / cell + 1, gy = y / cell + 1;
            for (int j = gy - 1; j <= gy + 1 && !near; j++) for (int i = gx - 1; i <= gx + 1 && !near; i++) {
                if (i < 0 || j < 0 || i >= gw || j >= gh) continue;
                List<double[]> l = grid.get(j * gw + i);
                if (l != null) for (double[] p : l) if (Math.hypot(p[0] - x, p[1] - y) <= reach) { near = true; break; }
            }
            if (!near) far++;
        }
        return far;
    }

    /** Device positions (x, y pairs) of the frame's particles of the given kinds. */
    private static double[] places(ViewerState st, Camera.View v, int... kinds) {
        Model.Frame f = st.cur;
        double[] out = new double[f.particleCount * 2];
        int n = 0;
        for (int k = 0; k < f.particleCount; k++) {
            int kind = (int) f.particles[k * 7];
            boolean want = kinds.length == 0;
            for (int q : kinds) want |= q == kind;
            if (!want) continue;
            out[n++] = v.ws * f.particles[k * 7 + 1] + v.wtx;
            out[n++] = v.ws * f.particles[k * 7 + 2] + v.wty;
        }
        return Arrays.copyOf(out, n);
    }

    private void pictures() throws Exception {
        final Advanced D = Advanced.DEFAULT;
        ParallelRenderer p4 = renderer(4), p1 = renderer(1);
        try {
            // ---- the worlds: a spring morning with rain and a little snow, the same world 14 degrees colder, a night village
            ViewerState rain = world(1234, 0.38, 300, null), snow = world(1234, 0.38, 700, null, "{\"cmd\":\"temp\",\"delta\":-14}"), dusk = world(1234, 0.76, 300, null);
            Camera cam = new Camera();
            p4.layout(cam, rain, W, H);
            Main.applyCam(cam, null, rain);
            Camera.View cv = cam.view(W, H, 1, 0, 0);

            // ---- J3: a flip shows on the very next frame, and flipping back leaves nothing behind (tiles, sprites, clouds, funnels)
            int[] base = shot(p4, rain, cam, D), again = shot(p4, rain, cam, D);
            StringBuilder stale = new StringBuilder(), blind = new StringBuilder();
            int[] changed = new int[Advanced.FLAGS];
            for (int k = 0; k < Advanced.FLAGS; k++) {
                if (k == Advanced.DETAIL_OFF || k == Advanced.GLOBAL || k == Advanced.BRANCH_OFF || k == Advanced.NO_FLASH) continue;   // (no bolt here: see lightning())
                ViewerState w = k == Advanced.NO_TINT ? dusk : rain;       // (a spring morning has no colour cast to take away)
                int[] was = k == Advanced.NO_TINT ? shot(p4, w, cam, D) : base;
                int[] on = shot(p4, w, cam, D.toggle(k)), off = shot(p4, w, cam, D);
                changed[k] = diff(was, on);
                if (k == Advanced.NO_TINT) shot(p4, rain, cam, D);
                if (!Arrays.equals(off, was)) stale.append(' ').append(Advanced.KEYS[k]).append(" (").append(diff(off, was)).append(" px)");
                if (k != Advanced.CULL && changed[k] == 0) blind.append(' ').append(Advanced.KEYS[k]);
            }
            int[] quarter = shot(p4, rain, cam, D.withParticles(25));
            int[] backAll = shot(p4, rain, cam, D);
            check(Arrays.equals(base, again) && stale.length() == 0 && blind.length() == 0 && Arrays.equals(backAll, base) && diff(quarter, base) > 0, String.format(
                    "each picture option changes the very next frame and is gone again on the frame after it is switched back (pixels changed: simple clouds %d, no tint %d, no snow %d, no trails %d, "
                            + "no shining %d, no puddle impact %d, 25 %% particles %d); two frames with no change are identical%s%s",
                    changed[Advanced.SIMPLE], changed[Advanced.NO_TINT], changed[Advanced.NO_SNOW], changed[Advanced.NO_TRAILS], changed[Advanced.NO_SHINE], changed[Advanced.NO_PUDDLE],
                    diff(quarter, base), stale.length() > 0 ? "; STALE after" + stale : "", blind.length() > 0 ? "; NO EFFECT:" + blind : ""));

            // ---- every option on: the four bands join without a seam
            Advanced all = Advanced.allOn();
            int[] all4 = shot(p4, rain, cam, all), all1 = shot(p1, rain, cam, all), snow4 = shot(p4, snow, cam, all), snow1 = shot(p1, snow, cam, all);
            check(diff(all4, all1) == 0 && diff(snow4, snow1) == 0, "with every option switched over, 4 render bands against 1 differ in " + diff(all4, all1) + " pixels (rain) and "
                    + diff(snow4, snow1) + " (snow)");

            // ---- J12: "Don't render what you can't see" never changes a pixel
            ViewerState storm = world(1234, 0.38, 1250, "supercell");
            Camera scam = new Camera();
            p4.layout(scam, storm, W, H);
            Main.applyCam(scam, "880,560,0.80", storm);
            int cullDiff = 0, scenes = 0;
            for (Object[] sc : new Object[][]{{rain, cam}, {snow, cam}, {dusk, cam}, {storm, scam}}) {
                ViewerState st = (ViewerState) sc[0];
                Camera c = (Camera) sc[1];
                for (ParallelRenderer pr : new ParallelRenderer[]{p4, p1}) {
                    cullDiff += diff(shot(pr, st, c, D), shot(pr, st, c, D.with(Advanced.CULL, false)));
                    scenes++;
                }
            }
            // ... also when it does skip something: snowflakes put behind the opaque heart of a cloud
            shot(p1, rain, cam, D);
            Shared sh = p1.shared;
            int planted = 0;
            Object[] keepP = {rain.cur.particles, rain.cur.particleCount};
            float[] more = Arrays.copyOf(rain.cur.particles, (rain.cur.particleCount + 400) * 7);
            int pn = rain.cur.particleCount;
            for (int i = 0; i < sh.occN && planted < 400; i++) {
                CloudPainter.Image im = sh.occImg[i];
                for (int y = 40; y < im.h - 40 && planted < 400; y += 9) for (int x = 40; x < im.w - 40 && planted < 400; x += 9) {
                    if (!im.opaque(x - 14, y - 14, x + 14, y + 14)) continue;
                    double wx = (sh.occX[i] + x - cv.wtx) / cv.ws, wy = (sh.occY[i] + y - cv.wty) / cv.ws;
                    if (wx < cv.wx0 + 8 || wx > cv.wx1 - 8 || wy < cv.wy0 + 8 || wy > cv.wy1 - 8) continue;
                    int b = pn * 7;
                    more[b] = planted % 3 == 0 ? 1 : planted % 3 == 1 ? 2 : 4; more[b + 1] = (float) wx; more[b + 2] = (float) wy; more[b + 3] = 0; more[b + 4] = planted % 3 == 0 ? 3 : 0;
                    more[b + 5] = 1.6f; more[b + 6] = 0;
                    pn++; planted++;
                }
            }
            rain.cur.particles = more;
            rain.cur.particleCount = pn;
            long c0 = Renderer.CULLED.sum();
            int[] hidOn = shot(p1, rain, cam, D), hidOn4 = shot(p4, rain, cam, D);
            long culled = Renderer.CULLED.sum() - c0;
            int[] hidOff = shot(p1, rain, cam, D.with(Advanced.CULL, false));
            long culledOff = Renderer.CULLED.sum() - c0 - culled;
            restore(rain, keepP);
            check(cullDiff == 0 && planted > 20 && culled > 0 && culledOff == 0 && diff(hidOn, hidOff) == 0 && diff(hidOn, hidOn4) == 0, String.format(
                    "\"Don't render what you can't see\" on and off give the same picture in %d renders (rain, snow, dusk, supercell with tornado; 1 and 4 bands: %d pixels differ); with %d particles "
                            + "planted behind opaque cloud it leaves out %d sprite blits, none when off, and the picture is still the same (%d pixels differ)", scenes, cullDiff, planted, culled, diff(hidOn, hidOff)));

            // ---- J5: Max particles
            // a running stream, thinned: every particle carries a rank from frame to frame (ViewerState.rankParticles)
            Advanced.publish(D.withParticles(50));
            FakeSim sim = new FakeSim(1234, 0.38, 3840, 1080, null, null, Double.NaN, Double.NaN, null);
            ViewerState run = new ViewerState();
            run.acceptLine(sim.hello());
            for (int k = 0; k < 300; k++) run.acceptLine(sim.step());
            Model.Frame f = run.cur;
            StringBuilder shares = new StringBuilder();
            boolean shareOk = f.particleRank != null && f.particleRank.length == f.particleCount && rain.cur.particleRank == null;
            for (int pct : Advanced.PARTICLES) {
                int kept = 0, still = 0;
                for (int k = 0; shareOk && k < f.particleCount; k++) if (f.particleRank[k] < pct) kept++;
                // (a frame that was never ranked - a still picture - is thinned by position alone)
                for (int k = 0; k < rain.cur.particleCount; k++) if (Renderer.particleRank(rain.cur.particles, k * 7, 0) < pct) still++;
                double got = 100.0 * kept / Math.max(1, f.particleCount), gotStill = 100.0 * still / Math.max(1, rain.cur.particleCount);
                shares.append(String.format(Locale.ROOT, " %d %% -> %.1f %% (still picture %.1f %%)", pct, got, gotStill));
                shareOk &= Math.abs(got - pct) <= 5 && Math.abs(gotStill - pct) <= 5;
            }
            // from one sim frame to the next: a particle is matched by kind, size and where its velocity puts it; how many change sides?
            int matchedRain = 0, flipRain = 0, matchedRest = 0, flipRest = 0;
            for (int rep = 0; rep < 30; rep++) {
                Model.Frame a = run.cur;
                run.acceptLine(sim.step());
                Model.Frame b = run.cur;
                for (int i = 0; i < a.particleCount; i++) {
                    int ia = i * 7, kind = (int) a.particles[ia];
                    if (kind > 6) continue;
                    double ex = a.particles[ia + 1] + a.particles[ia + 3] * FakeSim.DT, ey = a.particles[ia + 2] + a.particles[ia + 4] * FakeSim.DT;
                    int best = -1;
                    double bd = 1.5;
                    for (int j = 0; j < b.particleCount; j++) {
                        int ib = j * 7;
                        if ((int) b.particles[ib] != kind || b.particles[ib + 5] != a.particles[ia + 5]) continue;
                        double d = Math.hypot(b.particles[ib + 1] - ex, b.particles[ib + 2] - ey);
                        if (d < bd) { bd = d; best = j; }
                    }
                    if (best < 0) continue;
                    boolean flip = (a.particleRank[i] < 50) != (b.particleRank[best] < 50);
                    if (kind == 1) { matchedRain++; if (flip) flipRain++; } else { matchedRest++; if (flip) flipRest++; }
                }
            }
            double fr = 100.0 * flipRain / Math.max(1, matchedRain), fo = 100.0 * flipRest / Math.max(1, matchedRest);
            // a paused stream repeats its frame: nothing changes sides
            Model.Frame pa = run.cur;
            run.acceptLine(sim.step());
            Model.Frame pb = run.cur;
            ViewerState.rankParticles(pb, pb);
            byte[] first = pb.particleRank.clone();
            Model.Frame twin = new Model.Frame();
            twin.t = pb.t; twin.frame = pb.frame + 1; twin.particles = pb.particles; twin.particleCount = pb.particleCount;
            ViewerState.rankParticles(pb, twin);
            boolean paused = Arrays.equals(first, twin.particleRank) && pa != pb;
            int[] full = shot(p4, rain, cam, D.withParticles(100)), half4 = shot(p4, rain, cam, D.withParticles(50)), half1 = shot(p1, rain, cam, D.withParticles(50));
            check(shareOk && Arrays.equals(full, base) && diff(half4, half1) == 0 && diff(half4, base) > 0 && matchedRain > 500 && fr < 3 && fo < 3 && paused, String.format(Locale.ROOT,
                    "Max particles draws the share it names (%d listed:%s); 100 %% is the unchanged picture; 4 bands against 1 at 50 %%: %d pixels differ; from one sim frame to the next "
                            + "%.2f %% of %d raindrops and %.2f %% of %d other particles change sides at 50 %%, and none while the stream repeats a frame",
                    f.particleCount, shares, diff(half4, half1), fr, matchedRain, fo, matchedRest));

            // ---- J16: No trails. Rain alone, against the same frame without any particle: what is left of a drop stays at the drop
            Object[] keep = only(rain, 1);
            int drops = rain.cur.particleCount;
            StringBuilder tr = new StringBuilder();
            boolean trailsOk = drops > 50;
            for (double zoom : new double[]{0.6, 1.0, 2.5}) {
                Camera zc = new Camera();
                p4.layout(zc, rain, W, H);
                Main.applyCam(zc, String.format(Locale.ROOT, "%.0f,%.0f,%.2f", cam.cx, zoom > 2 ? cam.cy + 150 : cam.cy, zoom), rain);
                Camera.View zv = zc.view(W, H, 1, 0, 0);
                Advanced nt = D.with(Advanced.NO_TRAILS, true).with(Advanced.NO_SHINE, true), ns = D.with(Advanced.NO_SHINE, true);
                int[] dots = shot(p4, rain, zc, nt), streaks = shot(p4, rain, zc, ns);
                int n = rain.cur.particleCount;
                rain.cur.particleCount = 0;
                int[] none = shot(p4, rain, zc, nt);
                rain.cur.particleCount = n;
                // the drop sprite reaches 5.3 radii back from its head, and a radius is at most 1.65 x max(zoom, 1.1) device px
                double reach = 5.3 * (1.65 * Math.max(zv.zoom, 1.1) + 0.25) + 4;
                double[] at = places(rain, zv, 1);
                int farDots = strays(dots, none, at, reach), farStreaks = strays(streaks, none, at, reach), lit = diff(dots, none);
                tr.append(String.format(Locale.ROOT, " zoom %.1f: %d px of drops, %d further than %.0f px from a drop (with trails: %d);", zoom, lit, farDots, reach, farStreaks));
                trailsOk &= lit > 0 && farDots == 0 && farStreaks > 100;
            }
            restore(rain, keep);
            // sleet: the batched trail lines are gone, the sleet drop stays
            ViewerState mixed = world(1234, 0.38, 520, null, "{\"cmd\":\"temp\",\"delta\":-5}");
            int sleetN = 0;
            for (int k = 0; k < mixed.cur.particleCount; k++) if ((int) mixed.cur.particles[k * 7] == 6) sleetN++;
            Object[] keepS = only(mixed, 6);
            int[] sl = shot(p4, mixed, cam, D), slNo = shot(p4, mixed, cam, D.with(Advanced.NO_TRAILS, true));
            int ms = mixed.cur.particleCount;
            mixed.cur.particleCount = 0;
            int[] slNone = shot(p4, mixed, cam, D);
            mixed.cur.particleCount = ms;
            double[] sat = places(mixed, cv, 6);
            int sleetFar = strays(slNo, slNone, sat, 6), sleetDots = diff(slNo, slNone), sleetTrails = diff(sl, slNo);
            restore(mixed, keepS);
            check(trailsOk && sleetN > 5 && sleetFar == 0 && sleetDots > 0 && sleetTrails > sleetN, String.format(Locale.ROOT,
                    "No trails: %d raindrops are drawn as dots, nothing of them further than the drop itself:%s %d sleet drops keep their dot (%d px, none further than 6 px from a drop) and lose their trail lines (%d px)",
                    drops, tr, sleetN, sleetDots, sleetTrails));

            // ---- J17: No shining particles. What it removes sits at a raindrop (the glint), at an ice crystal (the twinkle) or on a snow heap
            Advanced shine = D.with(Advanced.NO_SHINE, true);
            int[] dull = shot(p4, rain, cam, shine);
            double[] carriers = places(rain, cv, 1, 3);
            // (and the sparkle of a snow heap)
            List<Double> heaps = new ArrayList<>();
            for (Model.Obj o : rain.objects) if (o.kind.equals("snowpack"))
                for (double hx = o.x - o.w / 2; hx <= o.x + o.w / 2; hx += 12) { heaps.add(cv.ws * hx + cv.wtx); heaps.add(cv.ws * (o.y - o.h / 2) + cv.wty); }
            carriers = Arrays.copyOf(carriers, carriers.length + heaps.size());
            for (int k = 0; k < heaps.size(); k++) carriers[carriers.length - heaps.size() + k] = heaps.get(k);
            int glintPx = diff(dull, shot(p4, rain, cam, D)), glintFar = strays(dull, base, carriers, 40);
            Object[] keepI = only(rain, 3);
            int ice = rain.cur.particleCount;
            int[] iceOn = shot(p4, rain, cam, D), iceOff = shot(p4, rain, cam, shine);
            restore(rain, keepI);
            Object[] keepR = only(rain, 1);
            int[] rainOn = shot(p4, rain, cam, D), rainOff = shot(p4, rain, cam, shine);
            // (with the option on the rain no longer depends on the clock: the glints were the only thing that pulsed)
            restore(rain, keepR);
            check(glintPx > 200 && glintFar == 0 && ice > 20 && diff(iceOn, iceOff) > 50 && diff(rainOn, rainOff) > 50, String.format(
                    "No shining particles: %d pixels change, %d of them further than 40 px from a raindrop, an ice crystal or a snow heap; the twinkles of %d ice crystals account for %d px, the glints on the rain for %d",
                    glintPx, glintFar, ice, diff(iceOn, iceOff), diff(rainOn, rainOff)));

            // ---- the rain glint: a tiny dot by default, the star on request; "No shining particles" removes it in either shape
            {
                Advanced starA = D.with(Advanced.GLINT_STAR, true);
                Camera gc = new Camera();
                p4.layout(gc, rain, W, H);
                Main.applyCam(gc, String.format(Locale.ROOT, "%.0f,%.0f,%.2f", cam.cx, cam.cy + 150, 2.5), rain);
                Object[] keepG = only(rain, 1);
                int[] gDot = shot(p4, rain, gc, D), gDot1 = shot(p1, rain, gc, D), gStar = shot(p4, rain, gc, starA), gNone = shot(p4, rain, gc, shine),
                        gNoneStar = shot(p4, rain, gc, shine.with(Advanced.GLINT_STAR, true)), gDotAgain = shot(p4, rain, gc, D);
                restore(rain, keepG);
                int dotPx = diff(gDot, gNone), starPx = diff(gStar, gNone), dotBright = brighter(gDot, gNone, 120), starBright = brighter(gStar, gNone, 120);
                check(dotPx > 0 && dotBright > 0 && starPx > dotPx && starBright > dotBright * 2 && Arrays.equals(gNone, gNoneStar) && diff(gDot, gStar) > 0
                                && diff(gDot, gDot1) == 0 && Arrays.equals(gDot, gDotAgain), String.format(Locale.ROOT,
                        "the rain glint at zoom 2.5: as a dot it touches %d pixels of the rain (%d of them clearly brighter), as a star %d (%d); with \"No shining particles\" there is none "
                                + "in either shape (the two pictures are identical); 1 render band and 4 draw the same dots, and Dot after Star is the first picture again",
                        dotPx, dotBright, starPx, starBright));
            }

            // ---- an object of a kind this viewer has no picture for (a windsock in an old save, something a newer simulation adds) is ignored
            {
                FakeSim us = new FakeSim(1234, 0.38, 3840, 1080, null, null, Double.NaN, Double.NaN, null);
                String hello = us.hello();
                int at = hello.indexOf("\"objects\":[") + 11;
                String extra = "{\"id\":900001,\"kind\":\"windsock\",\"x\":1463,\"y\":900,\"w\":10,\"h\":70,\"seed\":5,\"layer\":0,\"sway\":0.6,\"snow\":0,\"wet\":0.5},"
                        + "{\"id\":900002,\"kind\":\"no_such_kind\",\"variant\":\"odd\",\"x\":1500,\"y\":900,\"w\":80,\"h\":120,\"seed\":6,\"layer\":1,\"sway\":0,\"snow\":0.5,\"wet\":1},";
                ViewerState plainW = new ViewerState(), oddW = new ViewerState();
                plainW.acceptLine(hello);
                oddW.acceptLine(hello.substring(0, at) + extra + hello.substring(at));
                boolean fed = at > 11;
                for (int k = 0; k < 40; k++) {
                    String line = us.step();
                    plainW.acceptLine(line);
                    // (their state and their damage keep arriving, as from a simulation that knows them)
                    int oa = line.indexOf("\"objects\":[");
                    fed &= oa > 0;
                    String tail = line.substring(oa + 11);
                    oddW.acceptLine(line.substring(0, oa) + "\"damage\":[[900002,0.5]],\"objects\":[[900001,0.9,0,0.7,10,70],[900002,0,0.2,1,80,120]" + (tail.startsWith("]") ? "" : ",") + tail);
                }
                Camera oc = new Camera();
                p4.layout(oc, plainW, W, H);
                Main.applyCam(oc, "1480,830,2.00", plainW);
                int[] plainPic = shot(p4, plainW, oc, D), oddPic = shot(p4, oddW, oc, D);
                boolean kept = false, moved = false;
                for (Model.Obj o : oddW.objects) { if (o.kind.equals("windsock")) { kept = true; moved = Math.abs(o.sway - 0.9) < 1e-6; } }
                check(fed && oddW.error == null && plainW.error == null && oddW.objects.length == plainW.objects.length + 2 && kept && moved && diff(plainPic, oddPic) == 0
                                && oddW.cur.frame == plainW.cur.frame,
                        "a world with a \"windsock\" and an object of a kind nobody knows (in the hello, in the state rows and in the damage rows) loads without an error and is drawn "
                                + "exactly like the world without them: " + diff(plainPic, oddPic) + " pixels differ where they stand");
            }

            // ---- the water on wet panes and cars leans with the wind: nothing in calm air, the same in every render band
            {
                ViewerState calm = world(77, 0.45, 900, "cars", "{\"cmd\":\"wind\",\"delta\":-1.6}"), gale = world(77, 0.45, 900, "cars", "{\"cmd\":\"wind\",\"delta\":-1.6}");
                // (the same world; the wind is set on the last frame's grid alone, so that nothing but the wind differs)
                double[] gu = gale.cur.u.clone();
                for (int k = 0; k < gu.length; k++) gu[k] = 12;
                Camera wc = new Camera();
                p4.layout(wc, calm, W, H);
                Main.applyCam(wc, "settlement:city,4.0", calm);
                double[] still = calm.cur.u;
                calm.cur.u = new double[still.length];
                int[] c4 = shot(p4, calm, wc, D), c1 = shot(p1, calm, wc, D);
                gale.cur.u = gu;
                int n = gale.cur.particleCount;
                int[] g4 = shot(p4, gale, wc, D), g1 = shot(p1, gale, wc, D), g4b = shot(p4, gale, wc, D);
                for (int k = 0; k < gu.length; k++) gu[k] = -12;
                int[] l4 = shot(p4, gale, wc, D);
                calm.cur.u = still;
                int wet = 0;
                for (Model.Obj o : calm.objects) if (o.building && o.wet > 0.3) wet++;
                check(wet > 5 && n == calm.cur.particleCount && diff(c4, c1) == 0 && diff(g4, g1) == 0 && Arrays.equals(g4, g4b) && diff(c4, g4) > 300 && diff(g4, l4) > 300, String.format(Locale.ROOT,
                        "rain on the glass of a wet town (%d wet buildings): a 12 m/s wind changes %d pixels against still air, the same wind from the other side %d more; "
                                + "1 render band and 4 agree in still air and in wind, and two frames of the same wind are identical", wet, diff(c4, g4), diff(g4, l4)));
            }

            // ---- J18: No puddle impact. A wet street under rain: the rings and the splashes go, the water stays
            ViewerState wet = world(1234, 0.38, 500, null);
            Camera pc = new Camera();
            p4.layout(pc, wet, W, H);
            Main.applyCam(pc, "obj:puddle,2.5", wet);
            Camera.View pv = pc.view(W, H, 1, 0, 0);
            int splashes = 0, water = 0;
            List<Double> spots = new ArrayList<>();
            for (int k = 0; k < wet.cur.particleCount; k++) if ((int) wet.cur.particles[k * 7] == 5) {
                splashes++;
                spots.add(pv.ws * wet.cur.particles[k * 7 + 1] + pv.wtx); spots.add(pv.ws * wet.cur.particles[k * 7 + 2] + pv.wty);
            }
            double[] sp = new double[spots.size()];
            for (int k = 0; k < sp.length; k++) sp[k] = spots.get(k);
            int[] pOn = shot(p4, wet, pc, D), pOff = shot(p4, wet, pc, D.with(Advanced.NO_PUDDLE, true));
            int inWater = 0, nearSplash = 0, elsewhere = 0;
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                if (pOn[y * W + x] == pOff[y * W + x]) continue;
                double wx = (x - pv.wtx) / pv.ws, wy = (y - pv.wty) / pv.ws;
                boolean in = false;
                for (Model.Obj o : wet.objects) {
                    if (!o.kind.equals("puddle") && !o.kind.equals("pond")) continue;
                    if (Math.abs(wx - o.x) <= o.w / 2 + 6 && Math.abs(wy - o.y) <= Math.max(3, o.h) + 8) { in = true; break; }
                }
                boolean near = false;
                for (int k = 0; k + 1 < sp.length && !near; k += 2) near = Math.hypot(sp[k] - x, sp[k + 1] - y) < 40;
                if (in) inWater++; else if (near) nearSplash++; else elsewhere++;
            }
            for (Model.Obj o : wet.objects) if ((o.kind.equals("puddle") || o.kind.equals("pond")) && o.wet > 0.05 && o.x > pv.wx0 && o.x < pv.wx1) water++;
            check(splashes > 3 && water > 0 && inWater > 20 && nearSplash > 50 && elsewhere == 0, String.format(
                    "No puddle impact: %d ripple pixels on %d wet puddles and ponds and %d splash pixels (%d splashes) are gone, and nothing else changes (%d pixels elsewhere)",
                    inWater, water, nearSplash, splashes, elsewhere));

            // ---- J15: No snow
            Advanced ns = D.with(Advanced.NO_SNOW, true);
            int[] sOn = shot(p4, snow, cam, D), sOff = shot(p4, snow, cam, ns);
            int flakes = 0, snowObjs = 0, covered = 0;
            for (int k = 0; k < snow.cur.particleCount; k++) if ((int) snow.cur.particles[k * 7] == 2) flakes++;
            for (Model.Obj o : snow.objects) if (o.snow > 0.05) snowObjs++;
            for (double s : snow.ground.snow) if (s > 0.05) covered++;
            // the snow colours are the brightest things on the ground: count near-white pixels below the cloud bases
            int whiteOn = 0, whiteOff = 0, y0 = (int) Math.round(cv.ws * 380 + cv.wty);
            for (int y = Math.max(0, y0); y < H; y++) for (int x = 0; x < W; x++) {
                int a = sOn[y * W + x], b = sOff[y * W + x];
                if (((a >> 16) & 255) > 225 && ((a >> 8) & 255) > 228 && (a & 255) > 232) whiteOn++;
                if (((b >> 16) & 255) > 225 && ((b >> 8) & 255) > 228 && (b & 255) > 232) whiteOff++;
            }
            // without flakes the rest of the precipitation is untouched: rain, sleet and graupel alone look the same either way
            // everything else is not snow: the picture is that of the other particles alone
            Object[] keepN = only(snow, 0, 1, 3, 4, 5, 6), keepRain = only(rain, 0, 1, 3, 4, 5, 6);
            int others = rain.cur.particleCount + snow.cur.particleCount;
            // (without the twinkles: an ice crystal twinkles by its place in the list, which the shortened list changes)
            Advanced nsDull = ns.with(Advanced.NO_SHINE, true);
            int[] rOn = shot(p4, rain, cam, nsDull), rAll;
            restore(rain, keepRain);
            restore(snow, keepN);
            rAll = shot(p4, rain, cam, nsDull);
            Object[] keepF = only(rain, 2);
            int rainFlakes = rain.cur.particleCount;
            restore(rain, keepF);
            check(flakes > 100 && snowObjs > 20 && covered > 20 && whiteOn > 800 && whiteOff < whiteOn / 6 && others > 100 && rainFlakes > 0 && Arrays.equals(rOn, rAll), String.format(
                    "No snow: a world with %d flakes, snow on %d objects and %d ground samples has %d near-white pixels below the clouds, %d with the option on; in the rain world "
                            + "the picture with its %d flakes left out is the picture of its rain, sleet, graupel, splashes, droplets and ice crystals alone", flakes, snowObjs, covered, whiteOn, whiteOff, rainFlakes));

            // ---- J14: No tint
            Lit duskLit = new Lit(0.76, 0), flat = new Lit(0.76, 0, true), nightFlat = new Lit(0.02, 0, true), night = new Lit(0.02, 0), noon = new Lit(0.5, 0, true);
            java.awt.Color wall = new java.awt.Color(222, 200, 164), grey = new java.awt.Color(180, 180, 180);
            java.awt.Color got = flat.lit(wall), want = Lit.mul(wall, flat.light), ng = nightFlat.lit(grey), nt = night.lit(grey), dg = noon.lit(grey);
            boolean litOk = Lit.diff(got, want) <= 2 && flat.light == duskLit.light && Lit.diff(duskLit.lit(wall), want) > 2
                    && ng.getBlue() - ng.getRed() <= dg.getBlue() - dg.getRed() && nt.getBlue() - nt.getRed() > 5 && flat.differs(duskLit) && !flat.differs(new Lit(0.76, 0, true));
            CloudPainter.Light cn = CloudPainter.Light.of(0.5, 0, true), cd = CloudPainter.Light.of(0.76, 0, true), cdT = CloudPainter.Light.of(0.76, 0), cm = CloudPainter.Light.of(0.02, 0, true);
            double hueDay = cn.sb / cn.sr, hueDusk = cd.sb / cd.sr, hueDuskT = cdT.sb / cdT.sr, ambDay = cn.tb / cn.tr, ambNight = cm.tb / cm.tr, ambDusk = cd.tb / cd.tr;
            boolean cloudOk = Math.abs(hueDusk - hueDay) < 0.01 && hueDuskT < hueDay - 0.05 && Math.abs(ambNight - ambDay) < 0.02 && Math.abs(ambDusk - ambDay) < 0.02
                    && CloudPainter.Light.of(0.76, 0).key() != cd.key() && CloudPainter.Light.of(0.5, 0).key() == CloudPainter.Light.of(0.5, 0, false).key();
            int[] dOn = shot(p4, dusk, cam, D), dOff = shot(p4, dusk, cam, D.with(Advanced.NO_TINT, true));
            // the lightning light: a neutral white instead of the cold one (the same grey pixel, lit by the same light)
            BufferedImage li = new BufferedImage(64, 1, BufferedImage.TYPE_INT_RGB);
            Direct d = new Direct();
            d.attach(li);
            d.clip(0, 0, 64, 1);
            int[] hA = d.lightScratch(0), hB = d.lightScratch(1);
            Arrays.fill(hA, 200 * 16); Arrays.fill(hB, 200 * 16);
            Arrays.fill(d.px, 0xFF404040);
            d.lightRow(0, 0, 64, hA, hB, 4, 0, (65536 + 8 * 16) / (16 * 16), 512, 66, true);
            int neutral = d.px[5];
            Arrays.fill(d.px, 0xFF404040);
            d.lightRow(0, 0, 64, hA, hB, 4, 0, (65536 + 8 * 16) / (16 * 16), 512, 66);
            int cold = d.px[5];
            boolean lightOk = ((neutral >> 16) & 255) == (neutral & 255) && ((neutral >> 8) & 255) == (neutral & 255) && (neutral & 255) > 0x40
                    && ((cold >> 16) & 255) < (cold & 255) && (cold & 255) == (neutral & 255);
            // a background picture: with No tint it is shown as with the Background tab's own tint switched off
            BufferedImage pic = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
            Graphics2D pg = pic.createGraphics();
            pg.setPaint(new java.awt.GradientPaint(0, 0, new java.awt.Color(90, 140, 200), 0, 180, new java.awt.Color(230, 200, 160)));
            pg.fillRect(0, 0, 320, 180);
            pg.dispose();
            Renderer r4 = p4.primary();
            r4.backdropImage = pic;
            r4.backdropTint = true;
            int[] bTint = shot(p4, dusk, cam, D), bNo = shot(p4, dusk, cam, D.with(Advanced.NO_TINT, true));
            r4.backdropTint = false;
            int[] bOwnOff = shot(p4, dusk, cam, D.with(Advanced.NO_TINT, true));
            r4.backdropImage = null;
            r4.backdropTint = true;
            // (the top rows are sky only: no cloud, no scenery at this camera)
            int skyDiff = 0, skySame = 0;
            for (int x = 0; x < W; x++) { if (bTint[2 * W + x] != bNo[2 * W + x]) skyDiff++; if (bNo[2 * W + x] == bOwnOff[2 * W + x]) skySame++; }
            edt(() -> { settings.bgTint = true; settings.adv = D.with(Advanced.NO_TINT, true); app.applySettings(); });
            boolean stored = settings.bgTint;
            edt(() -> { settings.adv = D; app.applySettings(); });
            if (!(litOk && cloudOk && lightOk)) System.out.println(String.format(Locale.ROOT, "[advanced-test]     (lit %b, cloud light %b: ambient blue/red %.3f noon, %.3f night, %.3f dusk, light %b; picture %d px, sky %d)",
                    litOk, cloudOk, ambDay, ambNight, ambDusk, lightOk, diff(dOn, dOff), skyDiff));
            check(litOk && cloudOk && lightOk && diff(dOn, dOff) > W * H / 10 && skyDiff > W / 2 && skySame == W && stored, String.format(Locale.ROOT,
                    "No tint: at dusk a wall is its own colour times the daylight (%s against %s, tinted %s); a grey wall at night is no bluer than at noon (blue minus red %d, tinted %d); "
                            + "the cloud light keeps the noon hue (blue/red %.2f at dusk, %.2f at noon, %.2f tinted); lightning adds a neutral white (%06x against the cold %06x); "
                            + "a background picture is shown as with its own tint off (%d of %d sky pixels equal) and the Background tab's stored switch stays on",
                    hex(got), hex(want), hex(duskLit.lit(wall)), ng.getBlue() - ng.getRed(), nt.getBlue() - nt.getRed(), hueDusk, hueDay, hueDuskT, neutral & 0xFFFFFF, cold & 0xFFFFFF, skySame, W));
        } finally {
            Advanced.publish(Advanced.DEFAULT);
            p4.shutdown();
            p1.shutdown();
        }
    }

    private static String hex(java.awt.Color c) { return String.format("%06x", c.getRGB() & 0xFFFFFF); }

    // ------------------------------------------------------------------ J7 - J10: the lightning options

    private void lightning() throws Exception {
        final Advanced D = Advanced.DEFAULT;
        ViewerState st = world(1234, 0.02, 300, null);
        st.cur.lightning = new ArrayList<>();
        ParallelRenderer p4 = renderer(4), p1 = renderer(1);
        // (no freezing-level label: it is drawn over the finished picture, light included, and would count as "not lit")
        p4.primary().decorative = true;
        p1.primary().decorative = true;
        boolean safeWas = Renderer.epilepsySafe;
        try {
            Renderer.epilepsySafe = false;
            Camera cam = new Camera();
            p4.layout(cam, st, W, H);
            Main.applyCam(cam, "settlement:village,1.3", st);
            Camera.View cv = cam.view(W, H, 1, 0, 0);
            double vx = (cv.wx0 + cv.wx1) / 2;
            Model.Bolt bolt = Main.testBolt(st.hello, st.cur, vx, 1);
            long key = Lightning.key(bolt);
            int[] dark = shot(p4, st, cam, D);
            st.cur.lightning = new ArrayList<>(List.of(bolt));
            int[] local = shot(p4, st, cam, D);
            Lightning.Light l0 = p4.shared.lightning.lights.get(key);
            int solids = 0;
            if (l0 != null && l0.em != null) for (Lightning.Emitter e : l0.em) for (float t : e.tIn) if (t != Float.POSITIVE_INFINITY) solids++;
            int glow0 = l0 == null ? -1 : l0.tree.glow.length, branches0 = l0 == null ? -1 : l0.tree.lines.size();

            // ---- J7: Lightning detail off, flipped while the bolt is showing
            int[] plain = shot(p4, st, cam, D.with(Advanced.DETAIL_OFF, true));
            Lightning.Light l1 = p4.shared.lightning.lights.get(key);
            boolean open = l1 != null && l1 != l0 && l1.em != null && l1.tree.glow.length == 0;
            int taps = 0;
            if (l1 != null && l1.em != null) for (Lightning.Emitter e : l1.em) { open &= e.open; for (float t : e.tIn) if (t != Float.POSITIVE_INFINITY) taps++; }
            // where the shadowed light left a pixel dark, the unshadowed one reaches it
            int shadowLit = 0, lostGlow = 0, litLocal = 0, litPlain = 0;
            int oy = (int) Math.floor(cv.ws * bolt.pts[1] + cv.wty);
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                int o = y * W + x;
                boolean a = local[o] != dark[o], b = plain[o] != dark[o];
                if (a) litLocal++;
                if (b) litPlain++;
                if (!a && b && y > oy) shadowLit++;
                if (a && y < oy - 200 * cv.ws && lum(local[o]) > lum(plain[o])) lostGlow++;
            }
            int[] localAgain = shot(p4, st, cam, D);
            check(solids > 0 && open && taps == 0 && shadowLit > 2000 && Arrays.equals(localAgain, local), String.format(
                    "Lightning detail off: the three lights keep their falloff but nothing is put in their way (shadow-map entries %d -> %d); %d pixels the shadows kept dark are lit; "
                            + "switched off again the picture is the shadowed one, pixel for pixel", solids, taps, shadowLit));
            // ... and the cloud the bolt hangs from no longer glows from within (a bolt from the tallest cloud that can host one)
            Model.Cloud host = null;
            for (Model.Cloud c : st.cur.clouds) {
                boolean storm = c.kind == Model.CUMULONIMBUS || c.kind == Model.SUPERCELL;
                if (c.form < 0.5 || Main.boltHost(st.cur, c.x) != c) continue;
                if (host == null || (storm ? 1e6 : 0) + c.h > ((host.kind == Model.CUMULONIMBUS || host.kind == Model.SUPERCELL) ? 1e6 : 0) + host.h) host = c;
            }
            int glowSeg = -1, glowOn = -1, glowOff = -1, segOff = -1;
            if (host != null) {
                Model.Bolt hb = Main.testBolt(st.hello, st.cur, host.x, 1);
                Camera hc = new Camera();
                p4.layout(hc, st, W, H);
                Main.applyCam(hc, String.format(Locale.ROOT, "%.0f,%.0f,0.75", host.x, hb.pts[1] - 150), st);
                Camera.View hv = hc.view(W, H, 1, 0, 0);
                st.cur.lightning = new ArrayList<>();
                int[] hDark = shot(p4, st, hc, D);
                st.cur.lightning = new ArrayList<>(List.of(hb));
                int[] hGlow = shot(p4, st, hc, D);
                Lightning.Light hl = p4.shared.lightning.lights.get(Lightning.key(hb));
                int[] hPlain = shot(p4, st, hc, D.with(Advanced.DETAIL_OFF, true));
                Lightning.Light hl2 = p4.shared.lightning.lights.get(Lightning.key(hb));
                if (hl != null && hl2 != null && !hl.tree.hidden.isEmpty()) {
                    glowSeg = hl.tree.glow.length / 5;
                    segOff = hl2.tree.glow.length / 5;
                    double[] trunk = hl.tree.hidden.get(0);
                    int tk = (trunk.length / 4) * 2;                 // a point half way up the hidden trunk
                    int gx = (int) Math.round(hv.ws * trunk[tk] + hv.wtx), gy = (int) Math.round(hv.ws * trunk[tk + 1] + hv.wty);
                    if (gx > 8 && gx < W - 8 && gy > 8 && gy < H - 8) { glowOn = lum(hGlow[gy * W + gx]) - lum(hDark[gy * W + gx]); glowOff = lum(hPlain[gy * W + gx]) - lum(hDark[gy * W + gx]); }
                }
                st.cur.lightning = new ArrayList<>(List.of(bolt));
                shot(p4, st, cam, D);
            }
            // (what is left there comes from the point lights, which without a channel fade out only at the cloud top)
            check(host != null && glowSeg > 0 && segOff == 0 && glowOn > 60 && glowOff < glowOn * 0.6, String.format(
                    "Lightning detail off: no channel inside the cloud (%d light segments -> %d), and a pixel on it that gained %d of 765 from the glow gains %d from the point lights alone",
                    glowSeg, segOff, glowOn, glowOff));

            // ---- J8: Global lightning. A bolt far off screen lights the whole view by one level; with the option off it lights nothing
            Model.Bolt far = Main.testBolt(st.hello, st.cur, cv.wx1 + 1350, 1);
            st.cur.lightning = new ArrayList<>(List.of(far));
            int[] farLocal = shot(p4, st, cam, D);
            Advanced glob = D.with(Advanced.GLOBAL, true);
            int[] farGlobal = shot(p4, st, cam, glob), farGlobal1 = shot(p1, st, cam, glob), farSoft = shot(p4, st, cam, glob, BufferedImage.TYPE_3BYTE_BGR), darkSoft;
            Lightning.Frame gf = p4.shared.bolts;
            Lightning.Light lg = p4.shared.lightning.lights.get(Lightning.key(far));
            st.cur.lightning = new ArrayList<>();
            darkSoft = shot(p4, st, cam, glob, BufferedImage.TYPE_3BYTE_BGR);
            int lit = 0, minGain = 999, maxGain = 0, darkPx = 0;
            for (int o = 0; o < dark.length; o++) {
                if (farGlobal[o] != dark[o]) lit++;
                // the light multiplies a colour and adds a little of its own: two pixels of the same colour gain the same
            }
            java.util.Map<Integer, Integer> gainOf = new java.util.HashMap<>();
            int uneven = 0;
            for (int o = 0; o < dark.length; o++) {
                Integer g = gainOf.putIfAbsent(dark[o], farGlobal[o]);
                if (g != null && g != farGlobal[o]) uneven++;
                if (lum(dark[o]) > 30 && lum(dark[o]) < 600) { int gn = lum(farGlobal[o]) - lum(dark[o]); minGain = Math.min(minGain, gn); maxGain = Math.max(maxGain, gn); darkPx++; }
            }
            // both together = global; with No flash nothing is lit
            st.cur.lightning = new ArrayList<>(List.of(far));
            int[] both = shot(p4, st, cam, glob.with(Advanced.DETAIL_OFF, true)), gNoFlash = shot(p4, st, cam, glob.with(Advanced.NO_FLASH, true));
            st.cur.lightning = new ArrayList<>();
            check(diff(farLocal, dark) == 0 && lit > W * H * 9 / 10 && uneven == 0 && minGain > 0 && diff(farGlobal, farGlobal1) == 0 && gf != null && gf.global && lg != null && lg.em == null
                            && diff(farSoft, darkSoft) > W * H / 2 && Arrays.equals(both, farGlobal) && diff(gNoFlash, dark) == 0, String.format(
                    "Global lightning: a bolt 1350 px beyond the edge of the view changes %d of %d pixels (0 with the option off), every one of its %d colours by one amount (%d pixels lit differently from another of the same colour; "
                            + "gain %d..%d of 765 on %d pixels), no shadow map is built, 4 bands against 1: %d pixels differ; the Java2D fallback lights %d pixels; with Lightning detail off "
                            + "as well it is the same picture, and with No flash nothing is lit",
                    lit, W * H, gainOf.size(), uneven, minGain, maxGain, darkPx, diff(farGlobal, farGlobal1), diff(farSoft, darkSoft)));

            // ---- J10: No flash. The bolt as always, no light at all
            st.cur.lightning = new ArrayList<>(List.of(bolt));
            Advanced nf = D.with(Advanced.NO_FLASH, true);
            int[] noFlash = shot(p4, st, cam, nf), noFlash1 = shot(p1, st, cam, nf);
            Lightning.Light l2 = p4.shared.lightning.lights.get(key);
            Lightning.Frame nfFrame = p4.shared.bolts;
            Lightning.Tree tree = l2 == null ? null : l2.tree;
            // every pixel that differs from the dark picture lies on the bolt: within its strokes' reach of the channel or a branch
            List<double[]> lines = new ArrayList<>();
            if (tree != null) { lines.add(tree.main); lines.addAll(tree.lines); }
            float iz = Lightning.strokeScale(cv);
            double reach = (7 * iz + 2) * cv.ws + 2;
            int onBolt = 0, offBolt = 0, sameCore = 0, coreN = 0;
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                int o = y * W + x;
                if (noFlash[o] == dark[o]) continue;
                double wx = (x + 0.5 - cv.wtx) / cv.ws, wy = (y + 0.5 - cv.wty) / cv.ws, best = 1e9;
                for (double[] ln : lines) for (int q = 0; q + 3 < ln.length; q += 2) best = Math.min(best, segDist(wx, wy, ln[q], ln[q + 1], ln[q + 2], ln[q + 3]));
                if (best * cv.ws <= reach) onBolt++; else offBolt++;
            }
            // the white core of the channel is the same white with and without the light
            for (int q = 0; tree != null && q + 1 < tree.main.length; q += 2) {
                int x = (int) Math.round(cv.ws * tree.main[q] + cv.wtx), y = (int) Math.round(cv.ws * tree.main[q + 1] + cv.wty);
                if (x < 2 || y < 2 || x >= W - 2 || y >= H - 2) continue;
                coreN++;
                if (lum(noFlash[y * W + x]) > 600 && lum(local[y * W + x]) > 600) sameCore++;
            }
            Renderer.epilepsySafe = true;
            int[] safe = shot(p4, st, cam, D), safeAll = shot(p4, st, cam, nf.with(Advanced.GLOBAL, true).with(Advanced.DETAIL_OFF, true));
            Renderer.epilepsySafe = false;
            check(l2 != null && l2.em == null && nfFrame != null && nfFrame.lights.length == 0 && onBolt > 500 && offBolt == 0 && diff(noFlash, noFlash1) == 0 && coreN > 4 && sameCore >= coreN * 3 / 4
                            && tree.lines.size() == branches0 && Arrays.equals(safe, safeAll), String.format(
                    "No flash: the bolt and its %d branches are drawn (%d pixels, all on the bolt; %d of %d points of the channel as white as with the light) and nothing else changes (%d pixels "
                            + "off the bolt); no shadow map is built; Epilepsy-safe is the same picture whatever the three light options say",
                    branches0, onBolt, sameCore, coreN, offBolt));

            // ---- J9: Lightning branching off
            int empty = 0, sameMain = 0, sameGlow = 0, branchy = 0, withGlow = 0;
            Lightning.Mask everywhere = (x, y) -> 1;
            for (int k = 0; k < 80; k++) {
                Model.Bolt b = Main.testBolt(st.hello, st.cur, 300 + k * 41.0, 1);
                long bk = Lightning.key(b);
                Lightning.Tree a = Lightning.Tree.grow(b.pts, bk, st.hello.terrain, everywhere, b.pts[0], b.pts[1], b.pts[1] - 300, true);
                Lightning.Tree c = Lightning.Tree.grow(b.pts, bk, st.hello.terrain, everywhere, b.pts[0], b.pts[1], b.pts[1] - 300, false);
                if (!a.lines.isEmpty()) branchy++;
                if (a.glow.length > 0) withGlow++;
                if (c.lines.isEmpty() && c.depth.isEmpty() && c.strength.isEmpty() && c.grounded.isEmpty() && c.segments == 0) empty++;
                if (Arrays.equals(a.main, c.main)) sameMain++;
                if (Arrays.equals(a.glow, c.glow) && a.hidden.size() == c.hidden.size()) sameGlow++;
                c.shape(1f);
                if (c.coreShape != null) empty--;
            }
            Advanced nb = D.with(Advanced.BRANCH_OFF, true);
            int[] bare = shot(p4, st, cam, nb), bare1 = shot(p1, st, cam, nb);
            Lightning.Light l3 = p4.shared.lightning.lights.get(key);
            // the light is the same; only the branches themselves are missing
            double pad = (7 * iz + 2) + 2 / cv.ws;
            int inBox = 0, outBox = 0;
            for (int y = 0; y < H && l0 != null; y++) for (int x = 0; x < W; x++) {
                int o = y * W + x;
                if (bare[o] == local[o]) continue;
                double wx = (x + 0.5 - cv.wtx) / cv.ws, wy = (y + 0.5 - cv.wty) / cv.ws, best = 1e9;
                for (double[] ln : l0.tree.lines) for (int q = 0; q + 3 < ln.length; q += 2) best = Math.min(best, segDist(wx, wy, ln[q], ln[q + 1], ln[q + 2], ln[q + 3]));
                if (best <= pad) inBox++; else outBox++;
            }
            Renderer.epilepsySafe = true;
            int[] safeBare = shot(p4, st, cam, nb);
            Lightning.Frame sfr = p4.shared.bolts;
            Renderer.epilepsySafe = false;
            int safeMax = 0;
            st.cur.lightning = new ArrayList<>();
            int[] safeDark = shot(p4, st, cam, nb);
            for (int o = 0; o < safeBare.length; o++) if (safeBare[o] != safeDark[o])
                safeMax = Math.max(safeMax, Math.max(Math.abs(((safeBare[o] >> 16) & 255) - ((safeDark[o] >> 16) & 255)), Math.max(Math.abs(((safeBare[o] >> 8) & 255) - ((safeDark[o] >> 8) & 255)),
                        Math.abs((safeBare[o] & 255) - (safeDark[o] & 255)))));
            check(empty == 80 && sameMain == 80 && sameGlow == 80 && branchy > 60 && withGlow > 60 && branches0 > 0 && l3 != null && l3.tree.lines.isEmpty() && Arrays.equals(l3.tree.glow, l0.tree.glow)
                            && inBox > 200 && outBox == 0 && diff(bare, bare1) == 0 && sfr != null && sfr.safe && sfr.trees.length == 1 && sfr.trees[0].lines.isEmpty() && diff(safeBare, safe) > 0
                            && safeMax > 20 && safeMax < 110, String.format(
                    "Lightning branching off: 80 bolts have no branch left (%d had some) and the same channel and the same glow in the cloud as with branches (%d / %d of 80); in the picture "
                            + "only the %d branches are missing (%d pixels on them, %d anywhere else); an Epilepsy-safe bolt is the channel alone, as dim as ever (largest change %d of 255)",
                    branchy, sameMain, sameGlow, branches0, inBox, outBox, safeMax));
        } finally {
            Renderer.epilepsySafe = safeWas;
            Advanced.publish(Advanced.DEFAULT);
            p4.shutdown();
            p1.shutdown();
        }
    }

    private static double segDist(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy, t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        return Math.hypot(px - ax - t * dx, py - ay - t * dy);
    }

    // ------------------------------------------------------------------ J13: Simple clouds

    private void clouds() {
        int keepQ = CloudPainter.quality;
        try {
            CloudPainter.Scratch sc = new CloudPainter.Scratch();
            CloudPainter.Light L = CloudPainter.Light.of(0.40, 0);
            StringBuilder bad = new StringBuilder(), note = new StringBuilder();
            long nsSimple = 0, nsLow = 0;
            int renders = 0, flatter = 0;
            double worst = 1;
            for (String type : Model.CLOUD_TYPES) {
                for (double life : new double[]{0.12, 0.5, 0.9}) {
                    Model.Cloud c = FakeSim.debugCloud(type, 7, life);
                    CloudPainter.quality = 0;
                    Advanced.publish(Advanced.DEFAULT);
                    CloudPainter.Spec lo = CloudPainter.sheetSpec(c, 40, L, 1.2);
                    Advanced.publish(Advanced.DEFAULT.with(Advanced.SIMPLE, true));
                    CloudPainter.Spec si = CloudPainter.sheetSpec(c, 40, L, 1.2);
                    Advanced.publish(Advanced.DEFAULT);
                    if (!si.simple || lo.simple || si.signature() == lo.signature()) bad.append(' ').append(type).append(":spec");
                    CloudPainter.render(lo, sc);
                    CloudPainter.render(si, sc);                 // (once unmeasured: the JIT)
                    long t0 = System.nanoTime();
                    CloudPainter.Image a = CloudPainter.render(lo, sc);
                    long t1 = System.nanoTime();
                    CloudPainter.Image b = CloudPainter.render(si, sc);
                    long t2 = System.nanoTime();
                    nsLow += t1 - t0; nsSimple += t2 - t1;
                    if (a == null && b == null) continue;
                    if (a == null || b == null || a.w != b.w || a.h != b.h) { bad.append(' ').append(type).append('@').append(life).append(":size"); continue; }
                    renders++;
                    int inA = 0, inBoth = 0, inB = 0;
                    // the body's shading: how much the brightness varies from a pixel to its neighbour inside the cloud
                    long varA = 0, varB = 0;
                    for (int o = 0; o < a.px.length; o++) {
                        boolean pa = (a.px[o] >>> 24) > 8, pb = (b.px[o] >>> 24) > 8;
                        if (pa) inA++;
                        if (pb) inB++;
                        if (pa && pb) inBoth++;
                        if (o % a.w > 0 && (a.px[o] >>> 24) > 250 && (a.px[o - 1] >>> 24) > 250 && (b.px[o] >>> 24) > 250 && (b.px[o - 1] >>> 24) > 250) {
                            varA += Math.abs(((a.px[o] >> 8) & 255) - ((a.px[o - 1] >> 8) & 255));
                            varB += Math.abs(((b.px[o] >> 8) & 255) - ((b.px[o - 1] >> 8) & 255));
                        }
                    }
                    double share = inA == 0 ? 1 : inBoth / (double) inA;
                    worst = Math.min(worst, share);
                    if (inB == 0 || share < 0.9) bad.append(String.format(Locale.ROOT, " %s@%.2f:overlap %.2f", type, life, share));
                    if (varB <= varA) flatter++;
                }
            }
            note.append(String.format(Locale.ROOT, "%.1f ms against %.1f ms at Low", nsSimple / 1e6 / Math.max(1, renders), nsLow / 1e6 / Math.max(1, renders)));
            // with the option off a render is what it always was: the same spec renders the same pixels, and its signature has no extra term
            Model.Cloud c = FakeSim.debugCloud("cumulonimbus", 7, 0.5);
            CloudPainter.quality = 1;
            CloudPainter.Spec s1 = CloudPainter.sheetSpec(c, 40, L, 1.2), s2 = CloudPainter.sheetSpec(c, 40, L, 1.2);
            s2.simple = true;
            long sigPlain = s1.signature();
            s2.simple = false;
            check(bad.length() == 0 && renders >= 24 && nsSimple < nsLow && flatter >= renders - 2 && s2.signature() == sigPlain
                            && CloudPainter.maxChannelDiff(CloudPainter.render(s1, sc), CloudPainter.render(s2, sc)) == 0, String.format(Locale.ROOT,
                    "Simple clouds: all nine types render at three ages (%d pictures), cover at least %.0f %% of the Low picture's outline, are flatter inside in %d of them, and cost %s%s",
                    renders, worst * 100, flatter, note, bad));
        } finally {
            CloudPainter.quality = keepQ;
            Advanced.publish(Advanced.DEFAULT);
        }
    }
}
