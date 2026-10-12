package precip;

import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * The checks of the hidden Debug game mode (PLAN Phase I), run by {@code --selftest-ui} and
 * {@code --selftest} after {@link UiDriveTest}: the cloud in the menu and its ten clicks, the unlock
 * flag in the settings file, the mode's start and its way back, every tab, the seeded clouds and
 * their PNG export, and the line-ups against the painters' own lists.
 * <p>
 * Like {@link UiDriveTest} it opens no window and needs no Python. Settings, saves and exported
 * pictures go to a throw-away folder in the system temp directory; the game folder is only read
 * (the world map of the World map tab comes from {@code assets/}).
 */
final class DebugTest {
    private final File root, tmp;
    private final Settings settings;
    private final AppShell app;
    private final List<String> failures = new ArrayList<>();
    private int step;
    private int W = 1680, H = 760;

    private DebugTest(File root, File tmp) {
        this.root = root;
        this.tmp = tmp;
        settings = new Settings(new File(tmp, "config"));     // a real file round trip, in the temp folder
        app = new AppShell(root, settings, new SaveStore(new File(tmp, "saves")));
    }

    static int run() {
        File tmp;
        try {
            tmp = Files.createTempDirectory("precip-debugtest").toFile();
        } catch (java.io.IOException ex) {
            System.out.println("[debug-test] cannot create a temp folder: " + ex.getMessage());
            return 1;
        }
        File root = Main.projectRoot();
        boolean shotsBefore = new File(root, "screenshots").exists();
        boolean keepSafe = Renderer.epilepsySafe;
        int keepQuality = CloudPainter.quality;
        DebugTest t = new DebugTest(root, tmp);
        try {
            t.go();
            t.check(new File(root, "screenshots").exists() == shotsBefore, "the game's screenshots/ folder was not created or touched by the tests");
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
            Renderer.epilepsySafe = keepSafe;
            CloudPainter.quality = keepQuality;
            deleteTree(tmp);
        }
        if (t.failures.isEmpty()) {
            System.out.println("[debug-test] PASS (" + t.step + " checks)");
            return 0;
        }
        System.out.println("[debug-test] FAIL");
        for (String f : t.failures) System.out.println("[debug-test]   - " + f);
        return 1;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    // ------------------------------------------------------------------ driving (as UiDriveTest does)

    private void check(boolean ok, String what) {
        step++;
        System.out.println(String.format("[debug-test] %2d  %s  %s", step, ok ? "ok  " : "FAIL", what));
        if (!ok) failures.add(what);
    }

    private static void edt(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);

    private void size(int w, int h) {
        W = w; H = h;
        canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    }

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

    private void key(int code) { key(code, KeyEvent.CHAR_UNDEFINED, 0); }

    private void key(int code, char ch, int modifiers) {
        paint();
        edt(() -> {
            ViewerPanel p = app.panel;
            KeyEvent press = new KeyEvent(p, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, code, ch);
            for (KeyListener l : p.getKeyListeners()) l.keyPressed(press);
            if (ch != KeyEvent.CHAR_UNDEFINED) {
                ViewerPanel now = app.panel;
                KeyEvent typed = new KeyEvent(now, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, ch);
                for (KeyListener l : now.getKeyListeners()) l.keyTyped(typed);
            }
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

    private Screen top() {
        Screen[] s = {null};
        edt(() -> s[0] = app.top());
        return s[0];
    }

    private String toast() {
        String[] s = {null};
        edt(() -> s[0] = app.toastText());
        return s[0];
    }

    // ------------------------------------------------------------------ picture helpers

    private static boolean hits(double[] a, double[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    /** Number of pixels of {@code r} (window coordinates) that differ by more than {@code tol} in any channel. */
    private static int diff(BufferedImage a, BufferedImage b, Rectangle2D r, int tol) {
        int n = 0;
        int x0 = Math.max(0, (int) r.getX()), y0 = Math.max(0, (int) r.getY());
        int x1 = Math.min(Math.min(a.getWidth(), b.getWidth()), (int) (r.getX() + r.getWidth())), y1 = Math.min(Math.min(a.getHeight(), b.getHeight()), (int) (r.getY() + r.getHeight()));
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            int p = a.getRGB(x, y), q = b.getRGB(x, y);
            if (p == q) continue;
            if (Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) > tol || Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) > tol || Math.abs((p & 255) - (q & 255)) > tol
                    || Math.abs((p >>> 24) - (q >>> 24)) > tol) n++;
        }
        return n;
    }

    private static int diff(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return Integer.MAX_VALUE;
        return diff(a, b, new Rectangle2D.Double(0, 0, a.getWidth(), a.getHeight()), 0);
    }

    /** Largest brightening of a pixel of {@code r} from {@code a} to {@code b} (0..255). */
    private static int maxGain(BufferedImage a, BufferedImage b, Rectangle2D r) {
        int m = 0;
        for (int y = (int) r.getY(); y < (int) (r.getY() + r.getHeight()); y++) for (int x = (int) r.getX(); x < (int) (r.getX() + r.getWidth()); x++) {
            int p = a.getRGB(x, y), q = b.getRGB(x, y);
            m = Math.max(m, Math.max(((q >> 16) & 255) - ((p >> 16) & 255), Math.max(((q >> 8) & 255) - ((p >> 8) & 255), (q & 255) - (p & 255))));
        }
        return m;
    }

    private static double luma(BufferedImage a, Rectangle2D r) {
        double s = 0;
        int n = 0;
        for (int y = (int) r.getY(); y < (int) (r.getY() + r.getHeight()); y += 2) for (int x = (int) r.getX(); x < (int) (r.getX() + r.getWidth()); x += 2) {
            int p = a.getRGB(x, y);
            s += 0.30 * ((p >> 16) & 255) + 0.59 * ((p >> 8) & 255) + 0.11 * (p & 255);
            n++;
        }
        return n == 0 ? 0 : s / n;
    }

    private static double[] meanRgb(BufferedImage a, Rectangle2D r) {
        double[] s = new double[3];
        int n = 0;
        for (int y = (int) r.getY(); y < (int) (r.getY() + r.getHeight()); y += 2) for (int x = (int) r.getX(); x < (int) (r.getX() + r.getWidth()); x += 2) {
            int p = a.getRGB(x, y);
            s[0] += (p >> 16) & 255; s[1] += (p >> 8) & 255; s[2] += p & 255;
            n++;
        }
        for (int k = 0; k < 3; k++) s[k] /= Math.max(1, n);
        return s;
    }

    private static boolean allDistinct(List<BufferedImage> imgs) {
        for (int i = 0; i < imgs.size(); i++) for (int j = i + 1; j < imgs.size(); j++) if (diff(imgs.get(i), imgs.get(j)) == 0) return false;
        return true;
    }

    // ------------------------------------------------------------------ the checks

    private void go() throws Exception {
        menuAndUnlock();
        modeAndTabs();
        clouds();
        export();
        lineups();
        worlds();
    }

    /** The cloud in the upper left, its ten clicks, the fifth row and the flag in settings.json. */
    private void menuAndUnlock() throws Exception {
        edt(() -> { app.applySettings(); app.showMenu(null); });
        frames(3);
        boolean four = app.menu.modeCount() == 4 && !settings.debugUnlocked;
        for (int k = 0; k < 4; k++) key(KeyEvent.VK_TAB);
        int afterFour = app.menuMode;
        key(KeyEvent.VK_TAB);
        check(four && afterFour == 0 && app.menuMode == 1, "locked: the menu has four game modes and Tab cycles through four");

        // ---- geometry: the cloud never touches the title, the mode rows or the buttons, at any size the window can have
        java.awt.Graphics2D mg = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB).createGraphics();
        StringBuilder bad = new StringBuilder();
        for (int[] wh : new int[][]{{1040, 581}, {1040, 620}, {1280, 720}, {1680, 760}, {1920, 1080}, {1500, 600}}) {
            int w = wh[0], h = wh[1];
            double[] l = MainMenuScreen.logoRect(w, h), logo = {l[0] - MainMenuScreen.LOGO_PAD, l[1] - MainMenuScreen.LOGO_PAD, l[2] + 2 * MainMenuScreen.LOGO_PAD, l[3] + 2 * MainMenuScreen.LOGO_PAD};
            java.awt.Font f = Ui.F_TITLE.deriveFont(MainMenuScreen.titleSize(w));
            Rectangle2D vb = f.createGlyphVector(mg.getFontRenderContext(), "PRECIPITATION").getVisualBounds();
            double[] title = {MainMenuScreen.LEFT_X + vb.getX(), MainMenuScreen.titleY(h) + vb.getY(), vb.getWidth(), vb.getHeight()};
            if (hits(logo, title) || logo[1] < 0 || logo[0] < 0) bad.append(' ').append(w).append('x').append(h).append(":title");
            double[] last = null;
            for (int k = 0; k < 5; k++) {
                last = MainMenuScreen.modeRowRect(w, h, k);
                if (hits(logo, last)) bad.append(' ').append(w).append('x').append(h).append(":row").append(k);
            }
            // the Settings / Quit buttons under the fifth row still fit the window
            double bottom = last[1] + last[3] + 8 + 8 + (h >= 640 ? 40 : 34);
            if (bottom > h - 4) bad.append(' ').append(w).append('x').append(h).append(":buttons end at ").append(bottom);
        }
        mg.dispose();
        check(bad.length() == 0, "the cloud clears the title and the mode rows, and five rows plus Settings / Quit fit, at 1040x581 .. 1920x1080" + bad);

        // ---- clicks 1..9: nothing to see before the fifth, a countdown after, never a row
        double[] l = MainMenuScreen.logoRect(W, H);
        double cx = l[0] + l[2] / 2, cy = l[1] + l[3] / 2;
        boolean quiet = true, counting = true, noRow = true;
        for (int k = 1; k <= 9; k++) {
            click(cx, cy);
            String t = toast();
            if (k < 5 && t != null) quiet = false;
            if (k >= 5 && !((10 - k) + " more…").equals(t)) counting = false;
            if (app.menu.modeCount() != 4 || settings.debugUnlocked || app.menuMode == MainMenuScreen.DEBUG) noRow = false;
            if (k == 4) {
                // where the fifth row will be: a click there must do nothing while the mode is locked
                double[] r5 = MainMenuScreen.modeRowRect(W, H, 4);
                int before = app.menuMode;
                click(r5[0] + r5[2] / 2, r5[1] + r5[3] - 5);       // (its lower edge: below the Settings / Quit buttons of the four-row menu)
                if (app.menuMode != before || top() instanceof DebugScreen) noRow = false;
            }
        }
        check(app.debugClicks == 9 && quiet && noRow, "clicks 1-4 on the cloud show nothing, and nine clicks add no Debug row (click counter 9)");
        check(counting, "clicks 5-9 say how many are left (\"5 more…\" down to \"1 more…\")");
        // ---- a click elsewhere does not count
        double[] r1 = MainMenuScreen.modeRowRect(W, H, MainMenuScreen.HURRICANE);
        click(r1[0] + r1[2] / 2, r1[1] + r1[3] / 2);
        click(l[0] + l[2] + 60, l[1] + l[3] / 2);                // right beside the cloud
        check(app.debugClicks == 9 && app.menuMode == MainMenuScreen.HURRICANE && !settings.debugUnlocked, "a click on a mode row or beside the cloud is not counted");
        settings.save();
        String locked = settings.file().isFile() ? Files.readString(settings.file().toPath(), StandardCharsets.UTF_8) : "";
        check(!locked.isEmpty() && !locked.contains("debugUnlocked") && !Settings.load(settings.dir).debugUnlocked, "a locked player's settings.json has no debugUnlocked key, and loads locked");

        // ---- the tenth click
        click(cx, cy);
        String t10 = toast();
        check(settings.debugUnlocked && app.menu.modeCount() == 5 && app.menuMode == MainMenuScreen.DEBUG && t10 != null && t10.contains("unlocked"),
                "the tenth click unlocks: a fifth row, Debug, is there and selected (toast: " + t10 + ")");
        String unlocked = Files.readString(settings.file().toPath(), StandardCharsets.UTF_8);
        File old = new File(tmp, "oldcfg");
        old.mkdirs();
        Files.writeString(new File(old, "settings.json").toPath(), "{\"version\":1,\"modes\":{\"hurricaneChapter\":2,\"showcaseType\":\"cirrus\"}}");
        Settings absent = Settings.load(old);
        check(unlocked.contains("\"debugUnlocked\":true") && Settings.load(settings.dir).debugUnlocked && !absent.debugUnlocked && absent.showcaseType.equals("cirrus"),
                "the unlock is written to settings.json and read back; a file without the key loads locked");
        for (int k = 0; k < 4; k++) key(KeyEvent.VK_TAB);
        int m4 = app.menuMode;
        key(KeyEvent.VK_TAB);
        check(m4 == 3 && app.menuMode == MainMenuScreen.DEBUG, "unlocked: Tab cycles through five modes");
        click(cx, cy);
        check(app.debugClicks == 9 + 1 && settings.debugUnlocked, "further clicks on the cloud change nothing (they select the Debug row)");
    }

    /** Start, every tab at two window sizes, Esc. */
    private void modeAndTabs() throws Exception {
        edt(() -> app.menuMode = MainMenuScreen.DEBUG);
        key(KeyEvent.VK_ENTER);
        boolean up = top() instanceof DebugScreen && app.session != null && app.session.mode.equals("debug") && app.session.slot == null
                && app.session.source instanceof DebugScreen.NoSource;
        check(up, "Enter on the Debug row starts the mode: no save slot, no simulation process (source: " + (app.session == null ? "none" : app.session.source.describe()) + ")");
        if (!up) return;
        DebugScreen ds = (DebugScreen) top();
        boolean safeBefore = Renderer.epilepsySafe;
        StringBuilder bad = new StringBuilder();
        for (int[] wh : new int[][]{{1680, 760}, {1040, 581}}) {
            size(wh[0], wh[1]);
            for (int k = 0; k < DebugScreen.TABS.length; k++) {
                key(KeyEvent.VK_1 + k, (char) ('1' + k), 0);
                frames(4);
                if (ds.tab != k) bad.append(" key ").append(k + 1).append(" did not select ").append(DebugScreen.TAB_IDS[k]);
                if (ds.lastError != null) bad.append(' ').append(DebugScreen.TAB_IDS[k]).append('@').append(wh[0]).append('x').append(wh[1]).append(": ").append(ds.lastError);
                if (k == DebugScreen.LIGHTNING) {
                    key(KeyEvent.VK_B, 'b', 0);                      // a bolt, drawn in both pictures
                    frames(3);
                    if (ds.lastError != null) bad.append(" lightning strike: ").append(ds.lastError);
                }
            }
        }
        check(bad.length() == 0, "keys 1-8 select the eight tabs and each paints without an exception at 1680x760 and at 1040x581" + bad);
        check(Renderer.epilepsySafe == safeBefore && Renderer.epilepsySafe == settings.epilepsySafe && ds.lightning.strikeCount >= 2,
                "after the Lightning tab (two bolts struck) the Epilepsy-safe switch is where the settings put it");
        size(1680, 760);

        // ---- the seed box takes digits (and the tab keys are then not tab keys)
        key(KeyEvent.VK_1, '1', 0);
        frames(2);
        edt(() -> { ds.seedFocus = true; ds.seedField.set(""); });
        key(KeyEvent.VK_7, '7', 0);
        key(KeyEvent.VK_7, '7', 0);
        key(KeyEvent.VK_3, '3', 0);
        boolean typed = ds.seed == 773 && ds.tab == DebugScreen.CLOUDS;
        key(KeyEvent.VK_ENTER);
        key(KeyEvent.VK_CLOSE_BRACKET, ']', 0);
        check(typed && !ds.seedFocus && ds.seed == 774, "digits typed into the seed box set the seed (773) instead of switching tabs; ] steps it to 774");

        // ---- the World map tab cannot open a place (that would create a save and start Python)
        key(KeyEvent.VK_8, '8', 0);
        frames(3);
        boolean[] ok = {false};
        edt(() -> {
            WorldScreen ws = ds.worldMap.ws;
            WorldModel m = ws == null ? null : ws.model();
            if (m == null || m.locations.isEmpty()) return;
            ws.open(m.locations.get(0));
            ok[0] = app.session != null && app.session.mode.equals("debug") && app.top() == ds && app.store.list().isEmpty();
        });
        check(ok[0], "\"Open this location\" on the embedded world map stays in Debug: no save slot is created");

        key(KeyEvent.VK_ESCAPE);
        frames(3);
        check(app.session == null && top() instanceof MainMenuScreen && app.menuMode == MainMenuScreen.DEBUG && !app.fakeModes,
                "Esc leaves Debug for the main menu with the Debug row still selected");
    }

    /** The seeded generator and its pictures. */
    private void clouds() {
        // ---- the life cycle of every type
        StringBuilder bad = new StringBuilder();
        for (String type : Model.CLOUD_TYPES) {
            double prev = -1;
            boolean rises = true, falls = true;
            for (double f : new double[]{0.02, 0.06, 0.12, 0.18, 0.24}) {
                double form = FakeSim.debugCloud(type, 42, f).form;
                if (form <= prev) rises = false;
                prev = form;
            }
            boolean full = prev > 0.99 && FakeSim.debugCloud(type, 42, 0.5).form > 0.99;
            prev = 2;
            for (double f : new double[]{0.84, 0.88, 0.92, 0.96, 0.995}) {
                double form = FakeSim.debugCloud(type, 42, f).form;
                if (form >= prev && f > 0.84) falls = false;
                prev = form;
            }
            boolean rains = !type.equals("cirrus") && !type.equals("altocumulus");
            String[] want = {"forming", "growing", "mature", rains ? "precipitating" : "mature", "dissipating"};
            boolean stages = true;
            for (int k = 0; k < 5; k++) if (!FakeSim.debugCloud(type, 42, DebugClouds.STAGE_AT[k]).stage.equals(want[k])) stages = false;
            if (!rises || !falls || !full || prev > 0.02 || !stages) bad.append(' ').append(type).append(rises ? "" : ":rise").append(falls ? "" : ":fall").append(full ? "" : ":full").append(stages ? "" : ":stages");
        }
        check(bad.length() == 0, "all nine types: form rises to 1 by 24 % of the life and falls back to 0 from 84 %; the stage chips land in their stages "
                + "(cirrus and altocumulus have no precipitating stage)" + bad);

        // ---- same seed = same cloud, another seed = another cloud
        Model.Cloud a = FakeSim.debugCloud("cumulonimbus", 77, 0.38), b = FakeSim.debugCloud("cumulonimbus", 77, 0.38), c = FakeSim.debugCloud("cumulonimbus", 78, 0.38);
        boolean same = Arrays.deepEquals(a.puffs, b.puffs) && a.name.equals(b.name) && a.w == b.w && a.h == b.h && Arrays.equals(a.tint, b.tint) && a.seed == 77 && a.darkness == b.darkness;
        check(same && !Arrays.deepEquals(a.puffs, c.puffs), "the generator is a pure function of type, seed and life: seed 77 twice gives the same skeleton (" + a.name + "), seed 78 another ("
                + c.name + ")");
        CloudPainter.Scratch sc = new CloudPainter.Scratch();
        CloudPainter.Image ia = DebugExport.render(a, 0.45, 0.8, false, sc), ib = DebugExport.render(b, 0.45, 0.8, false, new CloudPainter.Scratch()), ic = DebugExport.render(c, 0.45, 0.8, false, sc);
        int dSame = CloudPainter.maxChannelDiff(ia, ib), dOther = CloudPainter.maxChannelDiff(ia, ic);
        check(ia != null && dSame == 0 && dOther > 0, "the same seed reproduces the picture pixel for pixel (max channel difference " + dSame + "); another seed changes it (" + dOther + ")");

        // ---- every type draws something while forming and while dissipating
        bad = new StringBuilder();
        for (String type : Model.CLOUD_TYPES) {
            for (double life : new double[]{0.12, 0.90}) {
                CloudPainter.Image im = DebugExport.render(FakeSim.debugCloud(type, 1234, life), 0.45, 0.6, false, sc);
                long alpha = 0;
                if (im != null) for (int p : im.px) alpha += p >>> 24;
                if (alpha < 2000) bad.append(' ').append(type).append('@').append(life).append('=').append(alpha);
            }
        }
        check(bad.length() == 0, "every one of the nine types renders non-empty in forming (life 12 %) and in dissipating (life 90 %)" + bad);
    }

    /** The PNG export, into the temp folder. */
    private void export() throws Exception {
        File out = new File(tmp, "export");
        long t0 = System.currentTimeMillis();
        List<File> files = DebugExport.exportAll(1234, 0.45, out, null);
        long ms = System.currentTimeMillis() - t0;
        int n = Model.CLOUD_TYPES.length * (DebugExport.STEPS + 1);
        StringBuilder bad = new StringBuilder();
        Set<String> names = new HashSet<>();
        int sheets = 0;
        for (File f : files) {
            BufferedImage img = f.isFile() && f.length() > 0 ? ImageIO.read(f) : null;
            if (img == null) { bad.append(' ').append(f.getName()).append(":unreadable"); continue; }
            names.add(f.getName());
            boolean sheet = f.getName().endsWith("_sheet.png");
            if (sheet) { sheets++; continue; }
            if (DebugExport.alphaSum(img) == 0) bad.append(' ').append(f.getName()).append(":empty");
            if (!img.getColorModel().hasAlpha()) bad.append(' ').append(f.getName()).append(":no alpha");
            String type = f.getParentFile().getName().replace("-seed1234", "");
            if (!f.getName().matches(type + "_s1234_(forming|mature|dissipating)_\\dof\\d_l\\d{3}\\.png")) bad.append(' ').append(f.getName()).append(":name");
        }
        check(files.size() == n && names.size() == n && sheets == Model.CLOUD_TYPES.length && bad.length() == 0,
                "\"Export all types\" writes " + n + " decodable PNGs (13 transparent steps + 1 contact sheet for each of 9 types, " + ms + " ms), none empty, named type_sSEED_part_KofN_lLIFE" + bad);

        // ---- growth and decay are in the pictures
        bad = new StringBuilder();
        for (String type : Model.CLOUD_TYPES) {
            DebugExport.Frames f = DebugExport.frames(type, 1234, 0.45, new CloudPainter.Scratch());
            long prev = 0;
            for (int k = 0; k < DebugExport.FORMING.length; k++) {
                long s = DebugExport.alphaSum(f.steps[k].img);
                if (s < prev * 0.95) bad.append(' ').append(type).append(":forming ").append(k + 1);
                prev = s;
            }
            prev = Long.MAX_VALUE;
            for (int k = DebugExport.FORMING.length + 1; k < DebugExport.STEPS; k++) {
                long s = DebugExport.alphaSum(f.steps[k].img);
                if (s > prev * 1.05) bad.append(' ').append(type).append(":dissipating ").append(k - DebugExport.FORMING.length);
                prev = s;
            }
        }
        check(bad.length() == 0, "in every type's sequence the cloud grows through the six forming steps and thins through the six dissipating steps" + bad);

        // ---- one type again: identical pixels, and nothing overwritten
        List<File> again = DebugExport.exportOne("cumulus", 1234, 0.45, out);
        boolean identical = again.size() == DebugExport.STEPS + 1, suffixed = true;
        for (int k = 0; k < again.size() && identical; k++) {
            File first = new File(again.get(k).getParentFile(), again.get(k).getName().replace("-2.png", ".png"));
            if (!again.get(k).getName().endsWith("-2.png") || !first.isFile()) suffixed = false;
            else if (diff(ImageIO.read(first), ImageIO.read(again.get(k))) != 0) identical = false;
        }
        check(identical && suffixed, "a second export of the same seed gives identical pixels and overwrites nothing (the new files end in -2)");

        // ---- the strip of the Clouds tab is these same pictures
        AppShell shell = new AppShell(root, offline(), new SaveStore(new File(tmp, "saves")));
        DebugScreen ds = DebugScreen.open(shell, true);
        ds.setSeed(1234);
        ds.picture(1280, 720, 1);
        DebugExport.Frames strip = ds.clouds.frames();
        boolean sameAsFiles = strip != null;
        for (int k = 0; sameAsFiles && k < DebugExport.STEPS; k++) {
            File f = new File(new File(out, "cumulus-seed1234"), strip.steps[k].fileName("cumulus", 1234));
            if (!f.isFile() || diff(ImageIO.read(f), strip.steps[k].img) != 0) sameAsFiles = false;
        }
        BufferedImage p1 = ds.picture(1280, 720, 1);
        ds.setSeed(1235);
        BufferedImage p2 = ds.picture(1280, 720, 1);
        ds.setSeed(1234);
        BufferedImage p3 = ds.picture(1280, 720, 1);
        Rectangle2D pic = new Rectangle2D.Double(20, DebugScreen.TOP + 60, 1280 - DebugScreen.SIDE - 60, 300);
        check(sameAsFiles && ds.lastError == null && diff(p1, p2, pic, 0) > 500 && diff(p1, p3) == 0,
                "the tab's forming / dissipating strip shows exactly the exported pictures; changing the seed changes the tab, changing it back restores it pixel for pixel");
        ds.dispose();
        shell.io.shutdown();
    }

    /** Settings that never touch a file (the still pictures). */
    private Settings offline() {
        Settings s = new Settings(new File(tmp, "config-offline"));
        s.persist = false;
        return s;
    }

    /** Buildings, plants and cars against the painters' own lists and against the built-in world. */
    private void lineups() {
        List<DebugLineup.Entry> es = DebugLineup.entries();
        Set<String> labels = new HashSet<>();
        for (DebugLineup.Entry e : es) labels.add(e.label);
        AppShell shell = new AppShell(root, offline(), new SaveStore(new File(tmp, "saves")));
        DebugScreen ds = DebugScreen.open(shell, true);
        ds.setTab(DebugScreen.BUILDINGS);
        BufferedImage day = ds.picture(1680, 760, 1);
        boolean cells = ds.buildings.cellRects.size() == es.size() && new HashSet<>(ds.buildings.drawn).equals(labels) && ds.buildings.drawn.size() == es.size();
        int want = BuildingPainter.KINDS.length + BuildingPainter.HOUSE_VARIANTS.length + BuildingPainter.TREE_VARIANTS.length;
        check(es.size() == want && labels.size() == want && cells && ds.lastError == null,
                "the Buildings line-up has exactly one cell for each of the " + BuildingPainter.KINDS.length + " kinds, " + BuildingPainter.HOUSE_VARIANTS.length + " house variants and "
                        + BuildingPainter.TREE_VARIANTS.length + " plants in BuildingPainter's lists, and draws them all (" + ds.buildings.drawn.size() + " drawn)");
        ds.buildings.option("light", "night");
        BufferedImage night = ds.picture(1680, 760, 1);
        ds.buildings.option("light", "day");
        ds.buildings.option("damage", "1");
        BufferedImage ruin = ds.picture(1680, 760, 1);
        Rectangle2D firstRow = new Rectangle2D.Double(14, DebugScreen.TOP + 40, 1300, 200);
        int warm = 0;
        for (int y = (int) firstRow.getY(); y < firstRow.getMaxY(); y++) for (int x = (int) firstRow.getX(); x < firstRow.getMaxX(); x++) {
            int p = night.getRGB(x, y);
            if (((p >> 16) & 255) > 235 && ((p >> 8) & 255) > 180 && (p & 255) < 190) warm++;            // a lit window pane
        }
        check(diff(day, night, firstRow, 8) > 20000 && warm > 150 && diff(day, ruin, firstRow, 8) > 3000,
                "night changes the line-up and lights windows (" + warm + " warm window pixels in the first row); the damage slider changes the damaged copies");
        // rain on the panes: the wind slider smears the beads and makes the running drops lean (dry, it only moves sails, smoke and trees)
        // (at twice the pixels: a bead is only drawn where a world px is at least 1.6 device px, as in the game, and most cells are below that at 1x)
        ds.buildings.option("damage", "0.6");
        ds.buildings.option("wet", "1");
        ds.buildings.option("wind", "0");
        BufferedImage wetCalm = ds.picture(1680, 760, 2);
        ds.buildings.option("wind", "1");
        BufferedImage wetGale = ds.picture(1680, 760, 2);
        ds.buildings.option("wet", "0");
        BufferedImage dryGale = ds.picture(1680, 760, 2);
        ds.buildings.option("wind", "0");
        BufferedImage dryCalm = ds.picture(1680, 760, 2);
        ds.buildings.option("wind", "0.25");
        Rectangle2D firstRow2 = new Rectangle2D.Double(firstRow.getX() * 2, firstRow.getY() * 2, firstRow.getWidth() * 2, firstRow.getHeight() * 2);
        int windWet = diff(wetCalm, wetGale, firstRow2, 8), windDry = diff(dryCalm, dryGale, firstRow2, 8), rainOn = diff(dryCalm, wetCalm, firstRow2, 8);
        check(rainOn > 1000 && windWet > windDry + 300 && ds.lastError == null,
                "the Buildings tab shows rain on the panes (" + rainOn + " px change when the first row is wet) and the wind slider leans it: " + windWet
                        + " px change between calm and 14 m/s when wet, " + windDry + " when dry");

        // ---- the lists are the game's: everything the built-in world builds is in them, and Model agrees on what a building is
        Set<String> kinds = new HashSet<>(Arrays.asList(BuildingPainter.KINDS)), houses = new HashSet<>(Arrays.asList(BuildingPainter.HOUSE_VARIANTS)),
                trees = new HashSet<>(Arrays.asList(BuildingPainter.TREE_VARIANTS)), cars = new HashSet<>(Arrays.asList(CarPainter.VARIANTS));
        Set<String> seenKinds = new HashSet<>(), seenHouses = new HashSet<>(), seenTrees = new HashSet<>(), seenCars = new HashSet<>();
        StringBuilder bad = new StringBuilder();
        List<String> scenes = new ArrayList<>();
        scenes.add(null);
        for (String id : Preset.Biome.IDS) scenes.add("biome:" + id);
        for (String scene : scenes) for (long seed = 1; seed <= 3; seed++) {
            FakeSim fs = new FakeSim(seed, 0.45, 3840, 1080, null, null, Double.NaN, Double.NaN, scene);
            ViewerState st = new ViewerState();
            st.acceptLine(fs.hello());
            st.acceptLine(fs.step());
            for (Model.Obj o : st.objects) {
                if (o.building != Model.isBuilding(o.kind)) bad.append(" building flag of ").append(o.kind);
                if (o.kind.equals("house")) { seenHouses.add(o.variant); if (!houses.contains(o.variant)) bad.append(" house:").append(o.variant); }
                else if (o.kind.equals("tree")) { seenTrees.add(o.variant); if (!trees.contains(o.variant)) bad.append(" tree:").append(o.variant); }
                else if (o.building) { seenKinds.add(o.kind); if (!kinds.contains(o.kind)) bad.append(' ').append(o.kind); }
            }
            for (Model.Car c : st.cur.cars) { seenCars.add(c.variant); if (!cars.contains(c.variant)) bad.append(" car:").append(c.variant); }
        }
        for (String k : BuildingPainter.KINDS) if (!Model.isBuilding(k)) bad.append(" Model.isBuilding(").append(k).append(") is false");
        if (!Model.isBuilding("house") || Model.isBuilding("tree") || Model.isBuilding("pond")) bad.append(" Model.isBuilding(house/tree/pond)");
        for (String v : FakeSim.debugCarVariants()) if (!cars.contains(v)) bad.append(" spawner car:").append(v);
        check(bad.length() == 0 && seenKinds.equals(kinds) && seenHouses.equals(houses) && seenCars.equals(cars) && seenTrees.size() >= 8,
                "the lists match the game: 27 built-in worlds (plain + 8 biomes, 3 seeds) build exactly the " + seenKinds.size() + " kinds and " + seenHouses.size()
                        + " house variants of the lists, " + seenTrees.size() + " of the 9 plants and all " + seenCars.size() + " car variants" + bad);

        // ---- every entry is its own picture
        bad = new StringBuilder();
        BufferedImage cottage = null, pine = null;
        List<BufferedImage> portraits = new ArrayList<>();
        for (int i = 0; i < es.size(); i++) {
            DebugLineup.Entry e = es.get(i);
            // (index 0 for all: the same seed, so only kind and variant differ)
            BufferedImage p = DebugLineup.portrait(e, 0, 1234, 0, 0.45);
            portraits.add(p);
            if (e.label.equals("house:cottage")) cottage = p;
            if (e.label.equals("tree:pine")) pine = p;
            if (DebugExport.alphaSum(p) < 8000) bad.append(' ').append(e.label).append(":blank");
            if (diff(p, DebugLineup.portrait(e, 0, 1234, 0.7, 0.45)) < 60) bad.append(' ').append(e.label).append(":damage changes nothing");
        }
        for (int i = 0; i < es.size(); i++) {
            DebugLineup.Entry e = es.get(i);
            BufferedImage ref = e.tree() ? pine : cottage;
            if (portraits.get(i) != ref && diff(portraits.get(i), ref) < 200) bad.append(' ').append(e.label).append(":same as the default");
        }
        check(bad.length() == 0 && allDistinct(portraits), "each of the " + es.size() + " entries paints a picture of its own (none blank, none the default cottage or pine), and storm damage changes every one" + bad);

        // ---- cars
        ds.setTab(DebugScreen.CARS);
        ds.cars.option("light", "night");
        BufferedImage carsGlow = ds.picture(1680, 760, 1);
        Set<String> wantCars = new HashSet<>();
        for (String v : CarPainter.VARIANTS) for (int k = 0; k < DebugLineup.Cars.COLUMNS.length; k++) wantCars.add(v + "/" + k);
        boolean all = new HashSet<>(ds.cars.drawn).equals(wantCars) && ds.cars.drawn.size() == wantCars.size() && ds.lastError == null;
        ds.cars.option("glow", "off");
        BufferedImage carsNoGlow = ds.picture(1680, 760, 1);
        ds.cars.option("lights", "off");
        BufferedImage carsDark = ds.picture(1680, 760, 1);
        Rectangle2D lane = new Rectangle2D.Double(100, DebugScreen.TOP + 30, 420, 700);
        check(all && diff(carsGlow, carsNoGlow, lane, 2) > 300 && diff(carsNoGlow, carsDark, lane, 6) > 1500,
                "the Cars tab draws all " + CarPainter.VARIANTS.length + " variants in all " + DebugLineup.Cars.COLUMNS.length + " states (" + ds.cars.drawn.size()
                        + " cars); at night the headlights and the glow pass change the picture");
        // the wind slider leans the water on the wet car of the stopped column, and does nothing to a dry one
        ds.cars.option("light", "day");
        ds.cars.option("wet", "1");
        ds.cars.option("wind", "0");
        BufferedImage carCalm = ds.picture(1680, 760, 1);
        ds.cars.option("wind", "12");
        BufferedImage carGale = ds.picture(1680, 760, 1);
        ds.cars.option("wet", "0");
        BufferedImage carDryGale = ds.picture(1680, 760, 1);
        ds.cars.option("wind", "0");
        BufferedImage carDryCalm = ds.picture(1680, 760, 1);
        ds.cars.option("wet", "0.6");
        Rectangle2D allCars = new Rectangle2D.Double(0, DebugScreen.TOP, 1340, 760 - DebugScreen.TOP);
        check(diff(carCalm, carGale, allCars, 4) > 100 && diff(carDryCalm, carDryGale, allCars, 0) == 0 && ds.lastError == null,
                "the Cars tab has a wind slider: 12 m/s changes " + diff(carCalm, carGale, allCars, 4) + " px of the wet cars and nothing of the dry ones");
        List<BufferedImage> cps = new ArrayList<>();
        bad = new StringBuilder();
        Lit noon = new Lit(0.45, 0);
        for (String v : CarPainter.VARIANTS) for (int col : new int[]{0, 1, 5}) {
            BufferedImage p = new BufferedImage(240, 100, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = p.createGraphics();
            DebugScreen.hints(g);
            DebugLineup.Cars.paintCar(g, DebugLineup.Cars.car(v, col, 5, false, false, 0, 0), 120, 80, 3, 0, col == 5 ? 0.2 : 0, noon, 0, false);
            g.dispose();
            if (DebugExport.alphaSum(p) < 20000) bad.append(' ').append(v).append('/').append(col).append(":blank");
            cps.add(p);
        }
        check(bad.length() == 0 && allDistinct(cps), "six variants x two directions x wrecked: 18 different, non-blank car pictures" + bad);
        ds.dispose();
        shell.io.shutdown();
    }

    /** The live-world tabs and the two embedded screens, as still pictures. */
    private void worlds() {
        final int w = 1280, h = 720;
        Settings off = offline();
        AppShell shell = new AppShell(root, off, new SaveStore(new File(tmp, "saves")));
        DebugScreen ds = DebugScreen.open(shell, true);
        Rectangle2D.Double main = DebugWorlds.below(DebugScreen.main(DebugScreen.content(w, h)));

        // ---- lightning: a strike shows in both pictures, bright in one and dim in the other; the switch is left alone
        ds.setTab(DebugScreen.LIGHTNING);
        BufferedImage before = ds.picture(w, h, 1);
        ds.lightning.strike(ds.lightning.normal.hello().w * 0.175);
        BufferedImage after = ds.picture(w, h, 1);
        Rectangle2D left = ds.lightning.normal.rect, right = ds.lightning.safe.rect;
        int dn = diff(before, after, left, 6), dsafe = diff(before, after, right, 6), gn = maxGain(before, after, left), gs = maxGain(before, after, right);
        boolean restored = !Renderer.epilepsySafe;
        Renderer.epilepsySafe = true;                      // as with the setting switched on
        ds.picture(w, h, 1);
        restored &= Renderer.epilepsySafe;
        Renderer.epilepsySafe = false;
        check(dn > 2000 && dsafe > 100 && dsafe < dn && gs < gn && gs <= 120 && restored && ds.lastError == null,
                "a strike lights the normal picture (" + dn + " px changed, brightest +" + gn + ") and is a dim line in the Epilepsy-safe one (" + dsafe + " px, brightest +" + gs
                        + "); Renderer.epilepsySafe is restored whether the setting is off or on");

        // ---- weather: the four kinds of precipitation
        ds.setTab(DebugScreen.WEATHER);
        StringBuilder bad = new StringBuilder(), said = new StringBuilder();
        List<BufferedImage> pics = new ArrayList<>();
        for (String kind : new String[]{"rain", "snow", "mixed", "graupel"}) {
            ds.weather.option("view", kind);
            pics.add(ds.picture(w, h, 1));
            Model.Frame f = ds.weather.pane.frame();
            boolean named = false;
            for (Model.Cloud c : f.clouds) if (kind.equals(c.precipType)) named = true;
            int[] n = new int[8];
            for (int k = 0; k < f.particleCount; k++) { int pk = (int) f.particles[k * 7]; if (pk >= 0 && pk < 8) n[pk]++; }
            said.append(String.format(Locale.ROOT, " %s: rain %d, snow %d, sleet %d, graupel %d;", kind, n[1], n[2], n[6], n[4]));
            boolean parts = switch (kind) { case "rain" -> n[1] > 40 && n[2] == 0; case "snow" -> n[2] > 40 && n[1] == 0; case "mixed" -> n[2] > 20 && n[1] + n[6] > 10; default -> n[4] > 3; };
            if (!named || !parts || ds.lastError != null) bad.append(' ').append(kind).append(named ? "" : ":no cloud says so").append(parts ? "" : ":particles");
        }
        check(bad.length() == 0 && allDistinct(pics), "rain, snow, mixed and graupel each fall as their own particles (" + said.toString().trim() + ")" + bad);

        // ---- the rain view: the glint as a dot or a star (this view only: the setting is not touched), and the wind buttons
        ds.weather.option("view", "rain");
        Advanced advWas = Advanced.cur, mineWas = off.adv;
        ds.weather.option("glint", "dot");
        BufferedImage glintDot = ds.picture(w, h, 1);
        boolean dotSel = !ds.weather.glintStar();
        ds.weather.option("glint", "star");
        BufferedImage glintStar = ds.picture(w, h, 1);
        boolean starSel = ds.weather.glintStar();
        ds.weather.option("glint", "settings");
        BufferedImage glintOwn = ds.picture(w, h, 1);
        double windWas = ds.weather.pane.sim.windBase();
        ds.weather.option("blow", "16");
        double windNow = ds.weather.pane.sim.windBase();
        ds.weather.pane.step(30);
        ds.picture(w, h, 1);
        Model.Frame bf = ds.weather.pane.frame();
        double atGround = bf.windU(bf.w * 0.5, bf.groundY - 20);
        ds.weather.option("view", "mixed");
        ds.picture(w, h, 1);
        double carried = ds.weather.pane.sim.windBase();
        ds.weather.option("blow", "scene");
        ds.weather.option("view", "rain");
        ds.picture(w, h, 1);
        // "Rain on glass": the same frame in still air and in a gale differs only where water sits on glass
        ds.weather.option("view", "glass");
        ds.picture(w, h, 1);
        Model.Frame gf = ds.weather.pane.frame();
        double[] uWas = gf.u;
        gf.u = new double[uWas.length];
        BufferedImage glassCalm = ds.picture(w, h, 1);
        double[] uGale = new double[uWas.length];
        java.util.Arrays.fill(uGale, 14);
        gf.u = uGale;
        BufferedImage glassGale = ds.picture(w, h, 1);
        gf.u = uWas;
        int glassPx = diff(glassCalm, glassGale, main, 4), wetCars = 0, wetHouses = 0;
        for (Model.Car c : gf.cars) if (c.wet > 0.3) wetCars++;
        synchronized (ds.weather.pane.st) { for (Model.Obj o : ds.weather.pane.st.objects) if (o.building && o.wet > 0.3) wetHouses++; }
        check(ds.weather.pane.cam.zoom >= 3.9 && wetHouses > 3 && glassPx > 60 && ds.lastError == null,
                "the \"Rain on glass\" view is a close look (zoom " + String.format(Locale.ROOT, "%.1f", ds.weather.pane.cam.zoom) + ") at " + wetHouses + " wet buildings and " + wetCars
                        + " wet cars; the same frame in a 14 m/s wind differs from still air in " + glassPx + " px (the water on the glass leans)");
        ds.weather.option("view", "rain");
        ds.picture(w, h, 1);
        check(dotSel && starSel && diff(glintDot, glintStar, main, 4) > 20 && diff(glintDot, glintOwn, main, 0) == 0 && Advanced.cur == advWas && off.adv == mineWas && !off.adv.glintStar
                        && Math.abs(windNow - 16) < 1e-9 && Math.abs(windWas - 16) > 1 && atGround > 5 && Math.abs(carried - 16) < 1e-9
                        && Math.abs(ds.weather.pane.sim.windBase() - windWas) < 1e-9 && ds.lastError == null,
                String.format(Locale.ROOT, "the rain view shows the glint as a dot or as a star (%d px differ) without changing the setting; its wind buttons set the world's wind "
                        + "(%.1f -> %.1f m/s, %.1f at the ground), the next view keeps it, and \"scene\" gives a new world its own wind back", diff(glintDot, glintStar, main, 4), windWas, windNow, atGround));

        // ---- the tornado row
        ds.weather.option("view", "tornado");
        List<BufferedImage> stages = new ArrayList<>();
        boolean efOk = true;
        for (String st : DebugWorlds.WeatherTab.TOR_STAGES) {
            ds.weather.option("stage", st);
            stages.add(ds.picture(w, h, 1));
            if (st.equals("mature")) for (int k = 0; k < 6; k++) {
                Model.Tornado t = ds.weather.tornadoes[k];
                if (t == null || t.ef != k || (k > 0 && t.width <= ds.weather.tornadoes[k - 1].width)) efOk = false;
            }
        }
        check(efOk && allDistinct(stages) && ds.lastError == null, "the tornado row is EF0 to EF5 (rating from the wind of each column, each wider than the last), and its four stages differ");

        // ---- merge, supercell, ground water
        ds.weather.option("view", "merge");
        ds.picture(w, h, 1);
        boolean merged = false;
        for (Model.Cloud c : ds.weather.pane.frame().clouds) if (c.parts.length > 0) merged = true;
        ds.weather.option("view", "supercell");
        ds.picture(w, h, 1);
        Model.Frame sf = ds.weather.pane.frame();
        boolean storm = !sf.tornadoes.isEmpty();
        for (Model.Cloud c : sf.clouds) if (c.kind == Model.SUPERCELL && c.rotation > 0.5) storm &= true;
        ds.weather.option("view", "water");
        ds.picture(w, h, 1);
        Set<String> objs = new HashSet<>();
        synchronized (ds.weather.pane.st) { for (Model.Obj o : ds.weather.pane.st.objects) objs.add(o.kind); }
        // ---- Ground water > Puddle: standing water the v2.4 way (a pool of the water model in a hollow), where the picture looks
        ds.weather.option("water", "puddle");
        BufferedImage pooled = ds.picture(w, h, 1);
        double[] hw = ds.weather.hollow;
        DebugWorlds.Pane wp = ds.weather.pane;
        Model.Body pool = null;
        boolean modelOn;
        synchronized (wp.st) {
            modelOn = wp.st.hello.water != null;
            if (hw != null) for (Model.Body b : wp.st.water) if (!b.street() && b.x0 <= hw[1] && b.x1 >= hw[1]) pool = b;
        }
        boolean poolOk = hw != null && pool != null && pool.depth >= 1.5 && pool.y >= hw[3] - 0.01 && pool.x0 >= hw[0] - 0.5 && pool.x1 <= hw[2] + 0.5
                && wp.cam.cx > hw[0] && wp.cam.cx < hw[2];
        synchronized (wp.st) { wp.st.water = List.of(); }
        int poolPx = diff(pooled, ds.picture(w, h, 1), main, 4);
        check(modelOn && poolOk && poolPx > 300 && ds.lastError == null, pool == null ? "Ground water > Puddle shows no pool" : String.format(Locale.ROOT,
                "Ground water > Puddle shows standing water the v2.4 way: a pool of the water model in a hollow (%.0f px wide, %.1f px deep, its level not above the rim, "
                        + "inside the hollow's ridges), the camera over it, %d pixels of the picture are that water", pool.x1 - pool.x0, pool.depth, poolPx));
        ds.weather.option("water", "pond");
        ds.weather.option("view", "wind");
        BufferedImage windOn = ds.picture(w, h, 1);
        ds.weather.option("wind", "off");
        ds.weather.option("arrows", "off");
        BufferedImage windOff = ds.picture(w, h, 1);
        check(merged && storm && objs.containsAll(Arrays.asList("pond", "puddle", "snowpack")) && diff(windOn, windOff, main, 4) > 5000 && ds.lastError == null,
                "the merge scene reaches a merged cloud, the supercell scene a tornado, the water scene has pond / puddle / snowpack, and the wind overlays change the picture");

        // ---- world: clock, biomes, backdrop, under-cloud darkening
        ds.setTab(DebugScreen.WORLD);
        ds.world.option("hour", "12");
        BufferedImage noon = ds.picture(w, h, 1);
        ds.world.option("hour", "0");
        ds.world.leave();
        BufferedImage midnight = ds.picture(w, h, 1);
        double ln = luma(noon, main), lm = luma(midnight, main);
        ds.world.option("hour", "12");
        ds.world.leave();
        ds.world.option("backdrop", "on");
        BufferedImage withPic = ds.picture(w, h, 1);
        ds.world.option("backdrop", "off");
        Rectangle2D sky = new Rectangle2D.Double(main.x + 20, main.y + 20, main.width - 40, 120);
        boolean backdrop = diff(noon, withPic, sky, 10) > 20000;
        ds.world.option("under", "");
        BufferedImage shaded = ds.picture(w, h, 1);
        double level = ds.world.pane.r().shadeLevel;
        ds.world.option("shade", "off");
        BufferedImage unshaded = ds.picture(w, h, 1);
        ds.world.option("shade", "on");
        check(ln > lm + 40 && backdrop && level > 0.2 && luma(unshaded, main) > luma(shaded, main) + 4 && ds.lastError == null,
                String.format(Locale.ROOT, "noon is brighter than midnight (mean %.0f against %.0f), the backdrop picture replaces the sky, and under a cloud the darkening (level %.2f) dims the view "
                        + "(mean %.0f with, %.0f without)", ln, lm, level, luma(shaded, main), luma(unshaded, main)));
        bad = new StringBuilder();
        List<double[]> grounds = new ArrayList<>();
        List<BufferedImage> biomePics = new ArrayList<>();
        List<java.awt.Color[]> declared = new ArrayList<>();
        // (the lower sixth of the picture: the surface with what grows and lies on it, and the soil under it)
        Rectangle2D soil = new Rectangle2D.Double(main.x + 10, main.y + main.height * 0.84, main.width - 20, main.height * 0.16 - 4);
        StringBuilder cols = new StringBuilder();
        for (String id : Preset.Biome.IDS) {
            ds.world.option("biome", id);
            BufferedImage p = ds.picture(w, h, 1);
            Model.Hello hl = ds.world.pane.hello();
            if (hl.preset == null || hl.preset.biome == null || !hl.preset.biome.id.equals(id) || ds.lastError != null) bad.append(' ').append(id);
            grounds.add(meanRgb(p, soil));
            biomePics.add(p);
            if (hl.preset != null && hl.preset.biome != null) declared.add(new java.awt.Color[]{hl.preset.biome.ground, hl.preset.biome.soil});
            cols.append(String.format(Locale.ROOT, " %s %.0f/%.0f/%.0f", id, grounds.get(grounds.size() - 1)[0], grounds.get(grounds.size() - 1)[1], grounds.get(grounds.size() - 1)[2]));
        }
        // a pair may only look alike in the mean where the game's own palette is nearly the same (alpine and tundra: soil 96/88/76 against 96/84/72)
        int alike = 0, unexplained = 0;
        String alikeNames = "";
        for (int i = 0; i < grounds.size(); i++) for (int j = i + 1; j < grounds.size(); j++) {
            double d = Math.abs(grounds.get(i)[0] - grounds.get(j)[0]) + Math.abs(grounds.get(i)[1] - grounds.get(j)[1]) + Math.abs(grounds.get(i)[2] - grounds.get(j)[2]);
            if (d >= 3) continue;
            alike++;
            alikeNames += " " + Preset.Biome.IDS[i] + "/" + Preset.Biome.IDS[j];
            if (declared.size() != grounds.size() || Lit.diff(declared.get(i)[1], declared.get(j)[1]) > 6) unexplained++;
        }
        boolean groundsDeclared = declared.size() == Preset.Biome.IDS.length;
        for (int i = 0; groundsDeclared && i < declared.size(); i++) for (int j = i + 1; j < declared.size(); j++)
            if (declared.get(i)[0].equals(declared.get(j)[0])) groundsDeclared = false;
        ds.world.option("preset", Preset.IDS.length > 0 ? Preset.IDS[0] : "");
        ds.picture(w, h, 1);
        boolean preset = Preset.IDS.length == 0 || (ds.world.pane.hello().preset != null && ds.world.pane.hello().preset.id.equals(Preset.IDS[0]));
        ds.world.option("terrain", "flat");
        ds.world.option("biome", "none");
        ds.picture(w, h, 1);
        boolean flat = ds.world.pane.hello().terrainKind.equals("flat");
        check(bad.length() == 0 && unexplained == 0 && alike <= 1 && groundsDeclared && allDistinct(biomePics) && preset && flat && ds.lastError == null,
                "each of the 8 biomes dresses the world as itself: 8 different pictures, 8 different ground colours in the hello; mean colour of the ground strip:" + cols
                        + (alike > 0 ? "; alike in the mean:" + alikeNames + " (their soil colours are nearly the same in the game's palette)" : "") + "; a region preset and the flat stage load" + bad);

        // ---- the hurricane screen: ten stages in three views
        ds.setTab(DebugScreen.HURRICANE);
        bad = new StringBuilder();
        Rectangle2D area = DebugScreen.content(w, h);
        int shots = 0;
        for (String view : new String[]{"outside", "inside", "both"}) {
            List<BufferedImage> ps = new ArrayList<>();
            ds.hurricane.option("view", view);
            ds.hurricane.option("stage", "disturbance");
            ds.picture(w, h, 1);
            HurricaneModel hm = ds.hurricane.egs.state.hurricane;
            for (HurricaneModel.Stage st : hm.stages) {
                ds.hurricane.pickStage(st.id);
                ps.add(ds.picture(w, h, 1));
                shots++;
                if (ds.lastError != null || !st.id.equals(hm.cur.stage)) bad.append(' ').append(view).append('/').append(st.id);
            }
            if (!allDistinct(ps) || ps.size() != 10) bad.append(' ').append(view).append(":pictures repeat");
            if (luma(ps.get(7), new Rectangle2D.Double(20, DebugScreen.TOP + 60, 600, 500)) < 12) bad.append(' ').append(view).append(":blank");
        }
        check(bad.length() == 0 && shots == 30, "the Hurricane tab shows all ten stages in the outside, inside and both views: 30 different pictures" + bad);

        // ---- the world map: seven layers
        ds.setTab(DebugScreen.WORLDMAP);
        bad = new StringBuilder();
        List<BufferedImage> layers = new ArrayList<>();
        for (String id : WorldPainter.LAYER_IDS) {
            ds.worldMap.option("layer", id);
            BufferedImage p = ds.picture(w, h, 1);
            layers.add(p);
            if (ds.lastError != null || ds.worldMap.ws == null || !WorldPainter.LAYER_IDS[ds.worldMap.ws.painter.layer].equals(id)) bad.append(' ').append(id);
        }
        boolean mapOk = ds.worldMap.ws != null && ds.worldMap.ws.map.problem == null;
        check(bad.length() == 0 && layers.size() == 7 && allDistinct(layers) && mapOk && luma(layers.get(0), new Rectangle2D.Double(100, DebugScreen.TOP + 80, 600, 400)) > 20,
                "the World map tab shows the seven layers (" + String.join(", ", WorldPainter.LAYER_IDS) + ") as seven different pictures" + bad + (mapOk ? "" : " [map assets missing]"));

        // ---- a click on a widget of the embedded screen lands where it is drawn (the tab bar shifts it down)
        ds.worldMap.option("layer", "temp");
        ds.picture(w, h, 1);
        shell.ui.press(150, (int) (DebugScreen.TOP + 26));       // nothing there but the embedded top bar's title
        shell.ui.release(150, (int) (DebugScreen.TOP + 26));
        int layerBefore = ds.worldMap.ws.painter.layer;
        // its layer buttons start right of the title, 12 px below the embedded screen's top: the second one is "Clouds"
        boolean hit = false;
        for (int x = 200; x < 700 && !hit; x += 12) {
            ds.picture(w, h, 1);
            if (shell.ui.press(x, (int) (DebugScreen.TOP + 27))) shell.ui.release(x, (int) (DebugScreen.TOP + 27));
            hit = ds.worldMap.ws.painter.layer != layerBefore;
        }
        ds.picture(w, h, 1);
        boolean above = shell.ui.press(400, 27);                  // the same x in Debug's own tab bar is a Debug tab, not a layer
        shell.ui.release(-1, -1);
        check(hit && above && area.getY() == DebugScreen.TOP, "a click on a layer button of the embedded world map picks that layer: the screen's widgets are shifted with its drawing");

        ds.dispose();
        shell.io.shutdown();
        check(!Renderer.epilepsySafe && !off.file().exists(), "the still pictures wrote no settings file and left the Epilepsy-safe switch off");
    }
}
