package precip;

import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.SwingUtilities;

/**
 * Phase P (PE1): the sound. Checks of {@link SoundDirector} (every rule of the plan's table on made-up frames, read with
 * {@link Model#parseFrame} from the JSON the simulation writes), of {@link SoundMixer} through {@link SoundMixer#render}
 * (no audio device is needed, and none is opened), of {@link SoundBank} on WAV files written here and on the real
 * {@code assets/sounds} when it is there, of the settings block and the Sound tab, and of the wiring in {@link AppShell}:
 * who is told what when a game begins, is paused and is left. The last checks measure the cost (PE2).
 * <p>
 * Run by its own {@code main} ({@code java -cp viewer/out precip.SoundTest}); {@link #run} is the entry for
 * {@code --selftest-ui}. {@code SoundTest --line [seconds]} is the one thing here that opens the audio line: it plays
 * silence through it (master 0, eight voices mixed) and prints what the mixer thread cost. It is never part of {@link #run}.
 */
final class SoundTest {
    private SoundTest() {}

    private static int fails, checks;

    private static void check(boolean ok, String what) {
        checks++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) fails++;
    }

    static int run() {
        fails = 0; checks = 0;
        long t0 = System.nanoTime();
        System.out.println("SoundTest (Phase P): the director, the mixer, the sound files, the settings and the wiring");
        File tmp = null;
        try {
            tmp = Files.createTempDirectory("precip-soundtest").toFile();
            gate();
            rain();
            thunder();
            cars();
            towns();
            siren();
            tornado();
            hurricane();
            scene();
            mixer();
            bank(tmp);
            assets();
            settings(tmp);
            silence();
            wiring(tmp);
            cost();
        } catch (Throwable ex) {
            ex.printStackTrace(System.out);
            check(false, "no exception (" + ex + ")");
        } finally {
            if (tmp != null) { System.gc(); deleteTree(tmp); }
        }
        check(SoundMixer.linesOpened == 0 && !threadNamed("sound-mixer"), "after all of it no audio line has been opened and no mixer thread runs (" + SoundMixer.linesOpened + " lines)");
        System.out.printf(Locale.ROOT, "SoundTest: %s (%d checks, %d failed, %.1f s)%n", fails == 0 ? "PASSED" : "FAILED", checks, fails, (System.nanoTime() - t0) / 1e9);
        return fails == 0 ? 0 : 1;
    }

    private static boolean threadNamed(String name) {
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().equals(name) && t.isAlive()) return true;
        return false;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        if (!f.delete()) f.deleteOnExit();                     // (a mapped file cannot go while its buffer lives)
    }

    // ------------------------------------------------------------------ made-up frames

    static final double LX = 4000, SPAN = 1200, WORLD = 8000;
    /** The earshot of rain and traffic at this view. */
    static final double R = Math.max(SoundDirector.EAR_MIN, SoundDirector.EAR_SPAN * SPAN);
    /** The real files' lengths and loudest moments (assets/sounds/manifest.json of 2026-10-12), so the car rules are tried on what ships. */
    static final double[] CAR_SECONDS = {3.347, 6.239, 5.769, 26.402, 8.728}, CAR_PEAK = {1.365, 3.305, 1.375, 12.615, 4.385};
    static final int LIGHT = SoundDirector.Bed.RAIN_LIGHT.ordinal(), HEAVY = SoundDirector.Bed.RAIN_HEAVY.ordinal(), CITY_A = SoundDirector.Bed.CITY_A.ordinal(),
            CITY_B = SoundDirector.Bed.CITY_B.ordinal(), SIREN = SoundDirector.Bed.SIREN.ordinal(), TORNADO = SoundDirector.Bed.TORNADO.ordinal(),
            HURRICANE = SoundDirector.Bed.HURRICANE.ordinal();

    private static SoundDirector director(double thunderSeconds) {
        double[] ts = new double[SoundBank.THUNDERS];
        java.util.Arrays.fill(ts, thunderSeconds);
        return new SoundDirector(ts, CAR_SECONDS, CAR_PEAK);
    }

    private static Model.Hello hello(long seed, String... settlements) {
        Model.Hello h = new Model.Hello();
        h.seed = seed; h.w = (int) WORLD; h.h = 1080;
        for (String s : settlements) {                         // "kind:x0:x1"
            String[] p = s.split(":");
            Model.Settlement st = new Model.Settlement();
            st.id = h.settlements.size() + 1; st.kind = p[0]; st.x0 = Double.parseDouble(p[1]); st.x1 = Double.parseDouble(p[2]);
            h.settlements.add(st);
        }
        return h;
    }

    private static String f(String fmt, Object... a) { return String.format(Locale.ROOT, fmt, a); }

    static String cloud(long id, String type, String stage, String precip, double rate, double x, double w, String extra) {
        return f("{\"id\":%d,\"type\":\"%s\",\"stage\":\"%s\",\"precip_type\":\"%s\",\"precip_rate\":%.3f,\"x\":%.1f,\"y\":300,\"w\":%.1f,\"h\":200,\"form\":1%s}",
                id, type, stage, precip, rate, x, w, extra == null || extra.isEmpty() ? "" : "," + extra);
    }

    static String bolt(double x, double alpha) { return f("{\"pts\":[[%.1f,320],[%.1f,640],[%.1f,1000]],\"alpha\":%.3f}", x, x + 14, x + 3, alpha); }

    static String car(long id, double x, double speed, int dir, String state) {
        return f("{\"id\":%d,\"x\":%.2f,\"y\":1000,\"speed\":%.2f,\"dir\":%d,\"state\":\"%s\",\"variant\":\"sedan\"}", id, x, speed, dir, state);
    }

    static String twister(long id, long cloud, double x, double reach, double strength, String stage) {
        return f("{\"id\":%d,\"cloud\":%d,\"x\":%.1f,\"reach\":%.3f,\"strength\":%.3f,\"stage\":\"%s\",\"ef\":3,\"width\":60}", id, cloud, x, reach, strength, stage);
    }

    /** A frame as the simulation writes it; each argument is a comma-separated list of the builders above (or empty). */
    static Model.Frame frame(double speed, boolean paused, String clouds, String lightning, String cars, String tornadoes) {
        String json = f("{\"type\":\"frame\",\"frame\":1,\"t\":1,\"speed\":%.2f,\"paused\":%s,\"world\":{\"w\":%.0f,\"h\":1080,\"ground_y\":1000},"
                + "\"clouds\":[%s],\"lightning\":[%s],\"cars\":[%s],\"tornadoes\":[%s]}", speed, paused, WORLD, clouds, lightning, cars, tornadoes);
        return Model.parseFrame(Json.obj(Json.parse(json)));
    }

    static Model.Frame sky(String clouds) { return frame(1, false, clouds, "", "", ""); }

    private static SoundDirector.Input input(Model.Hello h, Model.Frame fr) {
        SoundDirector.Input in = new SoundDirector.Input();
        in.hello = h; in.frame = fr; in.lx = LX; in.span = SPAN; in.dt = 1 / 30.0;
        return in;
    }

    private static boolean silent(SoundDirector.Mix m) {
        for (float b : m.bed) if (b != 0) return false;
        return m.shots == 0;
    }

    // ------------------------------------------------------------------ PC1: the gate, the listener

    private static void gate() throws Exception {
        Model.Hello h = hello(5, "city:3800:4400");
        String wet = cloud(1, "nimbostratus", "precipitating", "rain", 0.8, LX, 900, "");
        SoundDirector d = director(3);
        SoundDirector.Input in = input(h, frame(1, false, wet, "", "", ""));
        d.step(in);
        SoundDirector.Mix on = d.step(in);
        boolean heard = on.open && on.bed[HEAVY] > 0.5 && on.bed[CITY_A] > 0;
        in.frame = null;
        SoundDirector.Mix m = d.step(in);
        boolean none = !m.open && silent(m);
        in.frame = frame(1, true, wet, bolt(LX, 1), car(1, LX - 60, 40, 1, "driving"), twister(1, 1, LX, 1, 1, "mature"));
        m = d.step(in);
        boolean paused = !m.open && silent(m);
        in.frame = frame(1, false, wet, bolt(LX + 9, 1), car(1, LX - 60, 40, 1, "driving"), twister(1, 1, LX, 1, 1, "mature"));
        in.blocked = true;
        m = d.step(in);
        boolean blocked = !m.open && silent(m);
        in.blocked = false; in.paused = true;
        m = d.step(in);
        boolean told = !m.open && silent(m);
        in.paused = false; in.mode = "world";
        m = d.step(in);
        boolean other = !m.open && silent(m);
        in.mode = "debug";
        other &= silent(d.step(in));
        check(heard && none && paused && blocked && told && other,
                "the gate: a running frame is heard; no frame, a paused frame, a dialog in front, a pause told by the app, and the modes world and debug give exactly 0 on every bed and no shot");
        // seen from afar, and the group volumes
        in = input(h, sky(wet));
        float near = d.step(in).bed[HEAVY];
        in.span = WORLD;
        float far = d.step(in).bed[HEAVY];
        in.span = SPAN; in.volRain = 0.5;
        float half = d.step(in).bed[HEAVY];
        check(Math.abs(far / near - (1 - SoundDirector.FAR_CUT)) < 1e-3 && Math.abs(half / near - 0.5) < 1e-4 && Math.abs(SoundDirector.far(WORLD * 0.5, WORLD) - 1) < 1e-9,
                f("seen from afar: the whole world in view leaves %.2f of the level (the factor of the under-cloud dimming), half of it in view all of it; the Rain volume at 50 %% halves the bed", far / near));
        File src = new File(Main.projectRoot(), "viewer/src/precip/SoundDirector.java");
        if (src.isFile()) {
            String text = Files.readString(src.toPath(), StandardCharsets.UTF_8);
            check(!text.contains("javax.sound") && !text.contains("java.awt") && !text.contains("javax.swing"), "SoundDirector names nothing of javax.sound, java.awt or javax.swing");
        }
    }

    // ------------------------------------------------------------------ PC2: rain

    private static float[] rainOf(String clouds, double lx) {
        SoundDirector d = director(3);
        SoundDirector.Input in = input(hello(1), sky(clouds));
        in.lx = lx;
        SoundDirector.Mix m = d.step(in);
        return new float[]{m.bed[LIGHT], m.bed[HEAVY]};
    }

    private static void rain() {
        float[] dry = rainOf("", LX);
        float[] fair = rainOf(cloud(1, "cumulus", "precipitating", "rain", 0.6, LX, 600, "\"fair\":true"), LX);
        float[] none = rainOf(cloud(1, "altocumulus", "precipitating", "none", 0.6, LX, 600, ""), LX);
        float[] forming = rainOf(cloud(1, "nimbostratus", "forming", "rain", 0.6, LX, 600, "") + "," + cloud(2, "stratus", "dissipating", "rain", 0.6, LX, 600, "")
                + "," + cloud(3, "cumulonimbus", "mature", "rain", 0.6, LX, 600, ""), LX);
        float[] away = rainOf(cloud(1, "nimbostratus", "precipitating", "rain", 1, LX + 300 + 3 * R, 600, ""), LX);
        float[] drizzle = rainOf(cloud(1, "stratus", "precipitating", "rain", 0.01, LX, 600, ""), LX);
        boolean zero = true;
        for (float[] a : new float[][]{dry, fair, none, forming, away, drizzle}) zero &= a[0] == 0 && a[1] == 0;
        check(zero, "rain is exactly 0 on both beds under a dry sky, a fair cumulus, a precipitating cloud with precip_type none, forming, mature and dissipating clouds, "
                + "a raining cloud 3 R away and a rate of 0.01");
        StringBuilder sb = new StringBuilder();
        boolean ok = true;
        for (String t : Model.CLOUD_TYPES) {
            float[] g = rainOf(cloud(1, t, "precipitating", "rain", 0.6, LX, 600, ""), LX);
            boolean heavy = t.equals("cumulus") || t.equals("cumulonimbus") || t.equals("nimbostratus") || t.equals("supercell");
            ok &= heavy ? g[1] > 0.5 && g[0] == 0 : g[0] > 0.5 && g[1] == 0;
            sb.append(t).append(heavy ? " heavy" : " light").append(f(" %.2f; ", heavy ? g[1] : g[0]));
        }
        check(ok, "each raining type sounds on its own bed only (donrain for cumulus, cumulonimbus, nimbostratus, supercell; Liecio for the rest): " + sb);
        float[] both = rainOf(cloud(1, "stratus", "precipitating", "rain", 0.6, LX, 600, "") + "," + cloud(2, "nimbostratus", "precipitating", "rain", 0.6, LX, 600, ""), LX);
        float[] st = rainOf(cloud(1, "stratus", "precipitating", "rain", 0.6, LX, 600, ""), LX), stSnow = rainOf(cloud(1, "stratus", "precipitating", "snow", 0.6, LX, 600, ""), LX);
        float[] nsSnow = rainOf(cloud(1, "nimbostratus", "precipitating", "snow", 0.6, LX, 600, ""), LX), mixed = rainOf(cloud(1, "stratus", "precipitating", "mixed", 0.6, LX, 600, ""), LX);
        float[] two = rainOf(cloud(1, "stratus", "precipitating", "rain", 0.6, LX, 600, "") + "," + cloud(2, "stratus", "precipitating", "rain", 0.6, LX, 600, ""), LX);
        check(both[0] > 0.5 && both[1] > 0.5 && Math.abs(stSnow[0] / st[0] - SoundDirector.RAIN_SNOW) < 1e-4 && stSnow[1] == 0 && nsSnow[1] == 0
                        && Math.abs(nsSnow[0] - stSnow[0]) < 1e-5 && Math.abs(mixed[0] / st[0] - SoundDirector.RAIN_MIXED) < 1e-4 && two[0] > st[0] && two[0] <= 1,
                f("a stratus and a nimbostratus together sound on both beds; snow gives %.0f %% of the rain's level on the light bed and nothing on the heavy one (also from a "
                        + "nimbostratus), mixed %.0f %%; two clouds are louder than one and stay below 1 (%.2f, %.2f)", stSnow[0] / st[0] * 100, mixed[0] / st[0] * 100, st[0], two[0]));
        // moving under the cloud: no step, and it only ever rises
        String c = cloud(1, "nimbostratus", "precipitating", "rain", 1, 5000, 800, "");
        SoundDirector d = director(3);
        SoundDirector.Input in = input(hello(1), sky(c));
        double worst = 0, prev = -1, first = -1, last = 0;
        boolean rising = true;
        for (double lx = 5000 - 400 - R - 40; lx <= 5000; lx += 1) {
            in.lx = lx;
            double g = d.step(in).bed[HEAVY];
            if (prev >= 0) { worst = Math.max(worst, Math.abs(g - prev)); rising &= g >= prev - 1e-6; }
            if (first < 0) first = g;
            prev = g; last = g;
        }
        check(first == 0 && last > 0.99 && rising && worst <= 0.05, f("the bed rises smoothly as the view moves under a cloud: 0 beyond R = %.0f px, %.2f under it, the largest step %.4f per px", R, last, worst));
    }

    // ------------------------------------------------------------------ PC3: thunder

    private static void thunder() {
        Model.Hello h = hello(11);
        // one bolt, in 17 frames
        SoundDirector d = director(3);
        SoundDirector.Input in = input(h, sky(""));
        d.step(in);
        int n = 0;
        for (int k = 0; k < 17; k++) {
            in.frame = frame(1, false, "", bolt(LX + 300, 1 - k * 0.06), "", "");
            n += d.step(in).shots;
        }
        for (int k = 0; k < 30; k++) { in.frame = sky(""); n += d.step(in).shots; }
        check(n == 1, "a bolt that is in 17 frames in a row is heard exactly once (" + n + ")");
        // 200 bolts: all seven variants, never the same twice in a row
        d = director(0.01);
        in = input(h, sky(""));
        in.dt = 0.25;
        d.step(in);
        int[] used = new int[SoundBank.THUNDERS];
        int prev = -1, repeats = 0, fired = 0;
        for (int k = 0; k < 200; k++) {
            in.frame = frame(1, false, "", bolt(LX + k * 0.37, 1), "", "");
            SoundDirector.Mix m = d.step(in);
            for (int s = 0; s < m.shots; s++) {
                int v = m.shot[s].variant;
                if (v == prev) repeats++;
                prev = v; used[v]++; fired++;
                if (!m.shot[s].id.equals("thunder_" + (v + 1))) repeats += 1000;
            }
        }
        boolean all = true;
        for (int u : used) all &= u > 0;
        check(fired == 200 && all && repeats == 0, "200 bolts: 200 thunders, all seven files used " + java.util.Arrays.toString(used) + ", never the same one twice in a row");
        // near and far
        SoundDirector.Shot a = oneThunder(h, LX, 1, 1), b = oneThunder(h, LX + 2000, 1, 1);
        check(a != null && b != null && a.gain > b.gain && a.delay < b.delay && a.cutoff > b.cutoff && Math.abs(a.delay - SoundDirector.THUNDER_DELAY) < 0.01 && a.gain > 0.999f,
                a == null || b == null ? "a bolt at 0 px and one at 2000 px are both heard" : f("a bolt at 0 px is louder, earlier and brighter than one at 2000 px: gain %.2f / %.2f, delay %.2f / %.2f s, "
                        + "low-pass %.0f / %.0f Hz", a.gain, b.gain, a.delay, b.delay, a.cutoff, b.cutoff));
        // the first frame of a session, a bolt already fading, fast forward, the frame after a pause
        d = director(3);
        in = input(h, frame(1, false, "", bolt(LX, 1), "", ""));
        int first = d.step(in).shots + d.step(in).shots;
        check(first == 0 && oneThunder(h, LX, 0.5, 1) == null && oneThunder(h, LX, 1, 4) == null && oneThunder(h, LX, 1, 2) != null,
                "no thunder for a bolt that is already there in the first frame of a session, for one first seen at alpha 0.5, and at 4x speed (at 2x there is one)");
        d = director(3);
        in = input(h, sky(""));
        d.step(in);
        in.frame = frame(1, true, "", "", "", "");
        d.step(in);
        in.frame = frame(1, false, "", bolt(LX + 50, 1), "", "");
        int afterPause = d.step(in).shots + d.step(in).shots;
        in.frame = frame(4, false, "", bolt(LX + 90, 1), "", "");
        d.step(in);
        in.frame = frame(1, false, "", bolt(LX + 90, 0.9), "", "");
        int afterFast = d.step(in).shots;
        in.frame = frame(1, false, "", bolt(LX + 10, 1) + "," + bolt(LX + 20, 1) + "," + bolt(LX + 30, 1) + "," + bolt(LX + 40, 1) + "," + bolt(LX + 60, 1), "", "");
        int burst = d.step(in).shots;
        in.frame = frame(1, false, "", bolt(LX + 70, 1), "", "");
        int full = d.step(in).shots;
        check(afterPause == 0 && afterFast == 0 && burst == SoundDirector.THUNDER_MAX && full == 0,
                "the frame after a pause starts none, a bolt first met during fast forward stays unheard, and five bolts at once give three thunders (the fourth waits for none)");
    }

    /** The thunder of one new bolt at world x (null = none). */
    private static SoundDirector.Shot oneThunder(Model.Hello h, double x, double alpha, double speed) {
        SoundDirector d = director(3);
        SoundDirector.Input in = input(h, frame(speed, false, "", "", "", ""));
        d.step(in);
        in.frame = frame(speed, false, "", bolt(x, alpha), "", "");
        SoundDirector.Mix m = d.step(in);
        if (m.shots == 0) {
            in.frame = frame(1, false, "", bolt(x, alpha * 0.9), "", "");      // (and not later either)
            return d.step(in).shots == 0 ? null : d.step(in).shot[0];
        }
        SoundDirector.Shot s = new SoundDirector.Shot();
        s.id = m.shot[0].id; s.variant = m.shot[0].variant; s.gain = m.shot[0].gain; s.delay = m.shot[0].delay; s.cutoff = m.shot[0].cutoff;
        return s;
    }

    // ------------------------------------------------------------------ PC4: cars

    /** Drive cars past the listener for {@code seconds}; every car is "id:x0:speed:dir:state". Returns the shots as {variant, gain, delay, T at the moment, cutoff}. */
    private static List<double[]> drive(SoundDirector d, Model.Hello h, double seconds, double simSpeed, String... cars) {
        List<double[]> out = new ArrayList<>();
        SoundDirector.Input in = input(h, sky(""));
        int n = cars.length;
        double[] x = new double[n], sp = new double[n];
        int[] dir = new int[n];
        long[] id = new long[n];
        String[] state = new String[n];
        for (int k = 0; k < n; k++) {
            String[] p = cars[k].split(":");
            id[k] = Long.parseLong(p[0]); x[k] = Double.parseDouble(p[1]); sp[k] = Double.parseDouble(p[2]); dir[k] = Integer.parseInt(p[3]); state[k] = p[4];
        }
        for (double t = 0; t < seconds; t += in.dt) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < n; k++) sb.append(k > 0 ? "," : "").append(car(id[k], x[k], sp[k], dir[k], state[k]));
            in.frame = frame(simSpeed, false, "", "", sb.toString(), "");
            SoundDirector.Mix m = d.step(in);
            for (int s = 0; s < m.shots; s++) {
                SoundDirector.Shot sh = m.shot[s];
                double T = Double.NaN;
                // the car this shot belongs to is the approaching one whose crossing fits the file best
                for (int k = 0; k < n; k++) {
                    double tk = (LX - x[k]) * dir[k] / (sp[k] * simSpeed);
                    if (tk >= 0 && (Double.isNaN(T) || Math.abs(sh.delay + d.carPeak(sh.variant) - tk) < Math.abs(sh.delay + d.carPeak(sh.variant) - T))) T = tk;
                }
                out.add(new double[]{sh.variant, sh.gain, sh.delay, T, sh.cutoff});
                if (!sh.id.equals("car_" + (sh.variant + 1))) out.add(new double[]{-1, 0, 0, 0, 0});
            }
            for (int k = 0; k < n; k++) if (state[k].equals("driving")) x[k] += sp[k] * dir[k] * simSpeed * in.dt;
        }
        return out;
    }

    private static void cars() {
        Model.Hello h = hello(3);
        int quiet = drive(director(3), h, 30, 1).size()
                + drive(director(3), h, 30, 1, "1:" + (LX - 300) + ":40:1:stopped", "2:" + (LX - 200) + ":40:1:tossed", "3:" + (LX - 100) + ":40:1:wrecked", "4:" + (LX - 50) + ":3:1:driving").size()
                + drive(director(3), h, 30, 1, "1:" + (LX + 20) + ":40:1:driving", "2:" + (LX - 20) + ":40:-1:driving").size();
        check(quiet == 0, "no car, cars that are stopped, tossed or wrecked, one that crawls (3 px/s) and cars driving away start nothing");
        List<double[]> one = drive(director(3), h, 30, 1, "1:" + (LX - 900) + ":40:1:driving");
        double[] s = one.size() == 1 ? one.get(0) : new double[]{0, 0, 99, 0, 0};
        double miss = s[2] + CAR_PEAK[(int) s[0]] - s[3];
        check(one.size() == 1 && Math.abs(miss) <= 0.3 && s[2] <= SoundDirector.CAR_LEAD + 1e-6 && s[2] >= -SoundDirector.CAR_LATE,
                f("one car crossing starts one voice (%d): file car_%d, started %.2f s ahead, its loudest moment %.2f s from the crossing", one.size(), (int) s[0] + 1, s[2], miss));
        List<double[]> left = drive(director(3), h, 30, 1, "1:" + (LX + 500) + ":60:-1:driving");
        check(left.size() == 1 && left.get(0)[2] + CAR_PEAK[(int) left.get(0)[0]] - left.get(0)[3] <= 0.3, "a car coming from the right at highway speed is heard the same way (" + left.size() + ")");
        // more cars: louder
        String[] few = new String[2], many = new String[20];
        for (int k = 0; k < many.length; k++) {
            String c = k == 0 ? "1:" + (LX - 400) + ":40:1:driving" : (k + 1) + ":" + (LX + 30 + k * 4) + ":2:1:driving";      // the others idle past, away from the listener
            many[k] = c;
            if (k < 2) few[k] = c;
        }
        List<double[]> g2 = drive(director(3), h, 12, 1, few), g20 = drive(director(3), h, 12, 1, many);
        double want2 = SoundDirector.TRAFFIC_BASE + (1 - SoundDirector.TRAFFIC_BASE) * (1 - Math.exp(-2 / SoundDirector.TRAFFIC_N));
        check(g2.size() == 1 && g20.size() == 1 && g20.get(0)[1] > g2.get(0)[1] + 0.2 && Math.abs(g2.get(0)[1] - want2) < 1e-3,
                g2.size() == 1 && g20.size() == 1 ? f("20 cars in earshot give a louder pass than 2: traffic gain %.2f against %.2f", g20.get(0)[1], g2.get(0)[1]) : "20 cars against 2: one pass each");
        // eight at once
        String[] eight = new String[8];
        for (int k = 0; k < 8; k++) eight[k] = (k + 1) + ":" + (LX - 700) + ":40:1:driving";
        List<double[]> e = drive(director(3), h, 30, 1, eight);
        check(e.size() == SoundDirector.CAR_MAX, "eight cars crossing at the same moment start three voices (" + e.size() + ")");
        // the same car again
        SoundDirector d = director(3);
        int a = drive(d, h, 12, 1, "7:" + (LX - 300) + ":40:1:driving", "8:" + (LX + 3000) + ":1:1:stopped").size();
        int b = drive(d, h, 12, 1, "7:" + (LX - 300) + ":40:1:driving", "8:" + (LX + 3000) + ":1:1:stopped").size();
        drive(d, h, 45, 1, "7:" + (LX + 2000) + ":1:1:stopped", "8:" + (LX + 3000) + ":1:1:stopped");
        int c = drive(d, h, 12, 1, "7:" + (LX - 300) + ":40:1:driving", "8:" + (LX + 3000) + ":1:1:stopped").size();
        check(a == 1 && b == 0 && c == 1, f("the same car id is not heard twice within %.0f s (first pass %d, again 12 s later %d, again after 69 s %d)", SoundDirector.CAR_AGAIN, a, b, c));
        // variants, fast forward, a fast car and the long file
        d = director(3);
        int prev = -1, repeats = 0, n = 0, late = 0;
        int[] used = new int[SoundBank.CARS];
        for (int k = 0; k < 40; k++) {
            boolean slow = k % 2 == 0;
            for (double[] sh : drive(d, h, slow ? 28 : 10, 1, (100 + k) + ":" + (LX - (slow ? 640 : 300)) + ":" + (slow ? 26 : 60) + ":1:driving")) {
                if ((int) sh[0] == prev) repeats++;
                if (sh[0] < 0) repeats += 1000;
                if (sh[2] < -SoundDirector.CAR_LATE - 1e-6) late++;
                prev = (int) sh[0]; used[prev]++; n++;
            }
            drive(d, h, 0.1, 1);
        }
        boolean all = true;
        for (int u : used) all &= u > 0;
        check(n == 40 && repeats == 0 && all && late == 0, "40 cars one after another, slow and fast: 40 passes, all five files used " + java.util.Arrays.toString(used)
                + ", never the same one twice in a row, none started too late for its loudest moment");
        check(drive(director(3), h, 8, 4, "1:" + (LX - 600) + ":40:1:driving").isEmpty() && drive(director(3), h, 15, 2, "1:" + (LX - 600) + ":40:1:driving").size() == 1,
                "at 4x speed no pass is started; at 2x there is one");
    }

    // ------------------------------------------------------------------ PC5: town ambience

    private static void towns() {
        SoundDirector d = director(3);
        StringBuilder sb = new StringBuilder();
        double[] got = new double[4];
        String[] kinds = {"hamlet", "village", "town", "city"};
        boolean equal = true;
        for (int k = 0; k < 4; k++) {
            SoundDirector.Input in = input(hello(1, kinds[k] + ":3800:4400"), sky(""));
            in.volTowns = Settings.SOUND_TOWNS;
            SoundDirector.Mix m = d.step(in);
            got[k] = m.bed[CITY_A];
            equal &= m.bed[CITY_A] == m.bed[CITY_B];
            sb.append(kinds[k]).append(f(" %.3f; ", got[k]));
        }
        check(got[0] == 0 && got[1] == 0 && Math.abs(got[2] - SoundDirector.CITY_LEVEL * SoundDirector.SIZE_TOWN * Settings.SOUND_TOWNS) < 1e-5
                        && Math.abs(got[3] - SoundDirector.CITY_LEVEL * Settings.SOUND_TOWNS) < 1e-5 && equal,
                "with the default Towns volume a hamlet and a village are silent, a town and a city are not, and both files always have the same level: " + sb);
        Model.Hello h = hello(1, "city:3800:4400", "town:6500:6900");
        SoundDirector.Input in = input(h, sky(""));
        double rc = Math.max(SoundDirector.CITY_R_MIN, SoundDirector.CITY_R_SPAN * SPAN);
        in.lx = 3800 - rc - 5;
        boolean beyond = silent(d.step(in));
        double worst = 0, prev = -1, peak = 0;
        in.dt = 1 / 30.0;
        for (double lx = 2000; lx <= 7600; lx += 400 * in.dt) {
            in.lx = lx;
            SoundDirector.Mix m = d.step(in);
            if (prev >= 0) worst = Math.max(worst, Math.abs(m.bed[CITY_A] - prev));
            prev = m.bed[CITY_A];
            peak = Math.max(peak, prev);
        }
        check(beyond && worst <= 0.05 && Math.abs(peak - SoundDirector.CITY_LEVEL) < 1e-5,
                f("beyond Rc = %.0f px both are 0; a view panned at 400 px/s past a city and a town never changes them by more than 0.05 a frame (largest step %.4f, peak %.2f)", rc, worst, peak));
    }

    // ------------------------------------------------------------------ PC6: the siren

    private static void siren() {
        Model.Hello h = hello(1);
        SoundDirector d = director(3);
        SoundDirector.Input in = input(h, sky(""));
        String rain = "precipitating";
        // {cloud type, stage, precip, wall, a tornado of this cloud} -> on?
        Object[][] steps = {
                {"supercell", rain, "rain", 0.30, false, false, "a supercell at wall 0.30 that was never on"},
                {"supercell", rain, "rain", 0.40, false, true, "wall 0.40"},
                {"supercell", rain, "rain", 0.25, false, true, "back at 0.25 it stays on"},
                {"supercell", rain, "rain", 0.15, false, false, "at 0.15 without a tornado it goes off"},
                {"supercell", rain, "rain", 0.25, false, false, "and 0.25 does not bring it back"},
                {"supercell", rain, "rain", 0.40, false, true, "0.40 does"},
                {"supercell", rain, "rain", 0.15, true, true, "at 0.15 with a tornado it stays on"},
                {"supercell", rain, "rain", 0.05, true, true, "also at 0.05"},
                {"supercell", rain, "rain", 0.05, false, false, "the tornado gone: off"},
                {"supercell", rain, "rain", 0.90, false, true, "wall 0.90"},
                {"supercell", "mature", "rain", 0.90, false, false, "not precipitating: off"},
                {"supercell", rain, "none", 0.90, false, false, "precip_type none: off"},
                {"supercell", rain, "rain", 0.30, false, false, "the latch did not survive that"},
                {"cumulonimbus", rain, "rain", 1.00, false, false, "a cumulonimbus with wall 1.0"}};
        StringBuilder bad = new StringBuilder();
        for (Object[] s : steps) {
            String c = cloud(1, (String) s[0], (String) s[1], (String) s[2], 0.7, LX + 200, 900, f("\"wall\":%.2f,\"rotation\":0.8,\"meso_x\":%.1f", (Double) s[3], LX + 150));
            in.frame = frame(1, false, c, "", "", (Boolean) s[4] ? twister(1, 1, LX + 150, 0.5, 0.5, "funnel") : "");
            SoundDirector.Mix m = d.step(in);
            boolean on = m.bed[SIREN] > 0;
            if (on != (Boolean) s[5] || on != d.sirenOn(1)) bad.append(" [").append(s[6]).append(" -> ").append(on ? "on" : "off").append(']');
        }
        check(bad.length() == 0, "the siren's latch, 14 steps on one supercell: on from wall 0.35, held down to 0.20 and by a tornado below that, off when the cloud stops precipitating; never for "
                + "a cumulonimbus" + bad);
        // distance: to the wall cloud (meso_x), to the cloud's middle when that is not sent
        String c = cloud(1, "supercell", rain, "rain", 0.7, LX + 1500, 900, "\"wall\":0.6,\"meso_x\":" + LX);
        in.frame = sky(c);
        float atWall = director(3).step(in).bed[SIREN];
        in.frame = sky(cloud(1, "supercell", rain, "rain", 0.7, LX + 1500, 900, "\"wall\":0.6"));
        float byCloud = director(3).step(in).bed[SIREN];
        in.frame = sky(cloud(1, "supercell", rain, "rain", 0.7, LX + 5000, 900, "\"wall\":0.6"));
        SoundDirector far = director(3);
        float out = far.step(in).bed[SIREN];
        check(atWall > 0.999f && byCloud < atWall && byCloud > 0.2f && out == 0 && far.sirenOn(1), f("its level follows the distance to the wall cloud: %.2f at it, %.2f at 1500 px, 0 beyond %.0f px",
                atWall, byCloud, Math.max(SoundDirector.SIREN_R_MIN, SoundDirector.SIREN_R_SPAN * SPAN)));
    }

    // ------------------------------------------------------------------ PC7: tornadoes

    private static float[] tornadoOf(String tornadoes) {
        SoundDirector.Input in = input(hello(1), frame(1, false, "", "", "", tornadoes));
        SoundDirector.Mix m = director(3).step(in);
        return new float[]{m.bed[TORNADO], m.bed[HURRICANE], m.cutoff[TORNADO]};
    }

    private static void tornado() {
        float[] hang = tornadoOf(twister(1, 1, LX, 0.5, 1, "funnel")), edge = tornadoOf(twister(1, 1, LX, 0.9, 1, "touchdown")), full = tornadoOf(twister(1, 1, LX, 1, 1, "mature"));
        float[] weak = tornadoOf(twister(1, 1, LX, 1, 0, "mature"));
        check(hang[0] == 0 && hang[1] == 0 && edge[0] == 0 && edge[1] == 0 && Math.abs(full[0] - SoundDirector.TORNADO_LAYER) < 1e-5 && Math.abs(full[1] - SoundDirector.TORNADO_STORM_LAYER) < 1e-5
                        && Math.abs(weak[0] - SoundDirector.TORNADO_BASE) < 1e-5,
                f("a tornado at reach 0.5 and at 0.9 is silent; on the ground at strength 1 both layers are full (tornado %.2f, storm %.2f); at strength 0 the tornado layer is %.2f",
                        full[0], full[1], weak[0]));
        double prev = 2;
        boolean falling = true;
        StringBuilder sb = new StringBuilder();
        for (double reach = 1; reach >= 0.84; reach -= 0.02) {
            float g = tornadoOf(twister(1, 1, LX, reach, 0.8, "roping"))[0];
            falling &= g <= prev + 1e-6 && (reach > 0.9 + 1e-9 || g == 0);
            prev = g;
            sb.append(f("%.2f ", g));
        }
        check(falling && prev == 0, "a roping tornado fades out as its reach falls through 0.9: " + sb);
        float[] one = tornadoOf(twister(1, 1, LX + 600, 1, 0.6, "mature")), two = tornadoOf(twister(1, 1, LX + 600, 1, 0.6, "mature") + "," + twister(2, 1, LX - 500, 1, 0.6, "mature"));
        float[] many = tornadoOf(twister(1, 1, LX, 1, 1, "mature") + "," + twister(2, 1, LX, 1, 1, "mature") + "," + twister(3, 1, LX, 1, 1, "mature"));
        float[] near = tornadoOf(twister(1, 1, LX + 100, 1, 1, "mature")), far = tornadoOf(twister(1, 1, LX + 1400, 1, 1, "mature"));
        float[] gone = tornadoOf(twister(1, 1, LX + 2000, 1, 1, "mature"));
        check(two[0] > one[0] && many[0] <= 1 && many[1] <= 1 && near[0] > far[0] && far[0] > 0 && near[2] > far[2] && far[2] > 0 && gone[0] == 0,
                f("two tornadoes are louder than one (%.2f / %.2f) and three at the listener stay at 1; distance lowers the level (%.2f / %.2f) and the low-pass (%.0f / %.0f Hz); beyond %.0f px: 0",
                        two[0], one[0], near[0], far[0], near[2], far[2], Math.max(SoundDirector.TORNADO_R_MIN, SoundDirector.TORNADO_R_SPAN * SPAN)));
    }

    // ------------------------------------------------------------------ PC8: the hurricane screen

    private static void hurricane() {
        SoundDirector d = director(3);
        SoundDirector.Input in = new SoundDirector.Input();
        in.mode = "hurricane";
        in.hurricane = new HurricaneModel.Frame();
        in.dt = 1 / 30.0;
        double[] winds = {0, 35, 70, 90};
        float[] got = new float[winds.length];
        boolean alone = true;
        for (int k = 0; k < winds.length; k++) {
            in.hurricane.vmaxMs = winds[k];
            SoundDirector.Mix m = d.step(in);
            got[k] = m.bed[HURRICANE];
            alone &= m.open && m.shots == 0;
            for (int b = 0; b < m.bed.length; b++) if (b != HURRICANE) alone &= m.bed[b] == 0;
        }
        in.hurricane.paused = true;
        SoundDirector.Mix p = d.step(in);
        boolean paused = !p.open && silent(p);
        in.hurricane = null;
        boolean none = silent(d.step(in));
        // a sandbox frame that happens to be there changes nothing in this mode
        in.hurricane = new HurricaneModel.Frame();
        in.hurricane.vmaxMs = 70;
        in.hello = hello(1, "city:3800:4400");
        in.frame = frame(1, false, cloud(1, "nimbostratus", "precipitating", "rain", 1, LX, 900, ""), bolt(LX, 1), "", twister(1, 1, LX, 1, 1, "mature"));
        in.lx = LX; in.span = SPAN;
        SoundDirector.Mix both = d.step(in);
        boolean only = both.bed[HEAVY] == 0 && both.bed[TORNADO] == 0 && both.bed[CITY_A] == 0 && both.shots == 0 && both.bed[HURRICANE] > 0.99f;
        check(Math.abs(got[0] - SoundDirector.HURRICANE_BASE) < 1e-5 && Math.abs(got[1] - 0.725) < 1e-5 && got[2] > 0.9999f && got[3] > 0.9999f && alone && paused && none && only,
                f("the hurricane screen: the storm bed alone, %.2f at 0 m/s, %.3f at 35, %.2f at 70 and beyond; 0 while its simulation is paused or has sent nothing", got[0], got[1], got[2]));
    }

    // ------------------------------------------------------------------ the made-up minute (the PC check), through the mixer

    private static short[] noise(double seconds, double amp, long seed) {
        java.util.SplittableRandom r = new java.util.SplittableRandom(seed);
        short[] a = new short[(int) (seconds * SoundBank.RATE) * 2];
        for (int i = 0; i < a.length; i++) a[i] = (short) ((r.nextDouble() * 2 - 1) * amp * 32767);
        return a;
    }

    private static short[] sine(double seconds, double hz, double amp) {
        int n = (int) Math.round(seconds * SoundBank.RATE);
        short[] a = new short[n * 2];
        for (int i = 0; i < n; i++) a[i * 2] = a[i * 2 + 1] = (short) Math.round(Math.sin(2 * Math.PI * hz * i / SoundBank.RATE) * amp * 32767);
        return a;
    }

    private static void scene() {
        Model.Hello h = hello(42, "town:3800:4400");
        SoundDirector d = new SoundDirector(new double[]{5.1, 8.0, 9.4, 15.6, 11.1, 11.0, 6.5}, CAR_SECONDS, CAR_PEAK);
        // every clip as loud as a file can be, with the largest gains of the real manifest: the limiter has to hold
        SoundBank.Clip[] beds = new SoundBank.Clip[SoundDirector.BEDS];
        double[] bedGain = {0.38, 0.28, 1.17, 6.03, 0.31, 0.18, 0.26};
        for (int b = 0; b < beds.length; b++) beds[b] = SoundBank.of(SoundDirector.Bed.values()[b].id, true, bedGain[b], noise(0.7 + b * 0.13, 0.95, b));
        SoundBank.Clip thunderClip = SoundBank.of("thunder_1", false, 1.17, noise(2, 0.95, 77)), carClip = SoundBank.of("car_1", false, 2.03, noise(2, 0.95, 78));
        SoundMixer mx = new SoundMixer(beds, 9);
        mx.setMaster(1);
        SoundDirector.Input in = input(h, sky(""));
        int fps = 30, per = SoundBank.RATE / fps, thunders = 0, carShots = 0;
        float[] out = new float[per * 2];
        float lo = 0, hi = 0;
        double energy = 0;
        float[] peak = new float[SoundDirector.BEDS];
        float[][] at = new float[3][];
        double[] cx = {LX - 1500, LX + 900, LX - 300};
        double[] cs = {44, 30, 58};
        int[] cd = {1, -1, 1};
        for (int k = 0; k < 60 * fps; k++) {
            double t = k / (double) fps;
            double reach = Model.clamp((t - 15) / 10, 0, 1), wall = Model.clamp(t / 40, 0, 0.5);
            String clouds = cloud(1, "supercell", "precipitating", "rain", 0.8, LX + 200, 900, f("\"wall\":%.3f,\"rotation\":0.8,\"meso_x\":%.1f", wall, LX + 150))
                    + "," + cloud(2, "stratus", "precipitating", "rain", 0.5, LX - 500, 700, "");
            int strike = (int) (t / 6);
            double age = t - strike * 6;
            String bolts = strike >= 1 && age < 17.0 / fps ? bolt(LX - 1200 + strike * 310, 1 - age * 1.8) : "";
            StringBuilder cars = new StringBuilder();
            for (int c = 0; c < 3; c++) {
                cx[c] += cs[c] * cd[c] / fps;
                if (Math.abs(cx[c] - LX) > 2200) cx[c] = LX - cd[c] * 2200;
                cars.append(c > 0 ? "," : "").append(car(c + 1, cx[c], cs[c], cd[c], "driving"));
            }
            in.frame = frame(1, false, clouds, bolts, cars.toString(), twister(1, 1, LX + 180, reach, 0.9, reach < 1 ? "funnel" : "mature"));
            SoundDirector.Mix m = d.step(in);
            for (int b = 0; b < m.bed.length; b++) { mx.setBed(b, m.bed[b], m.cutoff[b]); peak[b] = Math.max(peak[b], m.bed[b]); }
            for (int s = 0; s < m.shots; s++) {
                boolean th = m.shot[s].id.startsWith("thunder");
                if (th) thunders++; else carShots++;
                mx.fire(th ? thunderClip : carClip, m.shot[s].gain, m.shot[s].cutoff, m.shot[s].delay);
            }
            mx.setHold(!m.open);
            mx.touch();
            mx.render(out, per);
            for (float v : out) { lo = Math.min(lo, v); hi = Math.max(hi, v); energy += v * v; }
            if (k == 10 * fps || k == 30 * fps || k == 59 * fps) at[k == 10 * fps ? 0 : k == 30 * fps ? 1 : 2] = m.bed.clone();
        }
        System.out.println("  the made-up minute (a precipitating supercell whose wall cloud lowers to 0.5 in 20 s, a raining stratus, a tornado touching down at 24 s, a town, three cars, a bolt every 6 s):");
        System.out.println("         bed          t=10 s  t=30 s  t=59 s   peak");
        for (SoundDirector.Bed b : SoundDirector.Bed.values())
            System.out.println(f("         %-11s  %.3f   %.3f   %.3f   %.3f", b.id, at[0][b.ordinal()], at[1][b.ordinal()], at[2][b.ordinal()], peak[b.ordinal()]));
        System.out.println(f("         one-shots in the minute: %d thunders, %d car passes", thunders, carShots));
        check(at[0][SIREN] == 0 && at[1][SIREN] > 0.9f && at[0][TORNADO] == 0 && at[2][TORNADO] > 0.8f && at[2][HURRICANE] > 0.4f && at[2][HEAVY] > 0.5f && at[2][LIGHT] > 0.05f
                        && Math.abs(at[2][CITY_A] - SoundDirector.CITY_LEVEL * SoundDirector.SIZE_TOWN) < 1e-4 && thunders >= 6 && carShots >= 2,
                "the minute reads as the table says: the siren comes on when the wall cloud passes 0.35, the tornado layers when the funnel reaches the ground, both rain beds, "
                        + "the town at 0.42, a thunder for the bolts and a pass for the cars");
        check(lo >= -1f && hi <= 1f && energy > 1000 && hi > 0.5f, f("60 s of it rendered through the mixer with every clip at full scale never leaves -1..1 (%.3f .. %.3f)", lo, hi));
    }

    // ------------------------------------------------------------------ PB2: the mixer, without a line

    /** Render {@code seconds}, block by block; returns {peak, peak of the last tenth of a second}. */
    private static float[] play(SoundMixer mx, double seconds, boolean touch) {
        float[] buf = new float[SoundMixer.BLOCK * 2];
        int frames = (int) Math.round(seconds * SoundBank.RATE), tail = SoundBank.RATE / 10;
        float pk = 0, end = 0;
        for (int done = 0; done < frames; ) {
            int n = Math.min(SoundMixer.BLOCK, frames - done);
            if (touch) mx.touch();
            mx.render(buf, n);
            for (int i = 0; i < n * 2; i++) {
                float a = Math.abs(buf[i]);
                pk = Math.max(pk, a);
                if (done + i / 2 >= frames - tail) end = Math.max(end, a);
            }
            done += n;
        }
        return new float[]{pk, end};
    }

    private static void mixer() {
        // a loop across its seam
        SoundBank.Clip loop = SoundBank.of("rain_light", true, 1, sine(0.5, 110, 0.5));         // 55 whole periods: the file itself loops cleanly
        SoundMixer mx = new SoundMixer(new SoundBank.Clip[]{loop}, 1);
        mx.setHold(false);
        mx.setBed(0, 1, 0);
        play(mx, 2, true);
        mx.seekBed(0, loop.frames - 3000);
        float[] buf = new float[6000 * 2];
        mx.touch();
        mx.render(buf, 6000);
        double step = 0;
        for (int i = 1; i < 6000; i++) step = Math.max(step, Math.abs(buf[i * 2] - buf[i * 2 - 2]));
        check(mx.bedPosition(0) == 3000 && step < 0.04 && step > 0.001 && Math.abs(mx.bedGain(0) - 1) < 1e-3,
                f("a looping bed rendered across its seam has no step: the largest between two samples is %.4f of full scale (the bound of the files is 0.04)", step));
        // two beds at full scale
        SoundMixer two = new SoundMixer(new SoundBank.Clip[]{SoundBank.of("city_a", true, 1, noise(0.4, 1, 1)), SoundBank.of("city_b", true, 1, noise(0.9, 1, 2))}, 2);
        two.setHold(false);
        two.setBed(0, 1, 0);
        two.setBed(1, 1, 0);
        float[] pk = play(two, 3, true);
        check(pk[0] <= 1f && pk[0] > 0.95f && SoundMixer.limit(5) <= 1 && SoundMixer.limit(-5) >= -1 && SoundMixer.limit(0.5f) == 0.5f,
                f("two beds at full scale sum to at most 1.0 after the limiter (peak %.4f); below 0.8 it changes nothing", pk[0]));
        // one-shots: delay, end, a start inside the file
        SoundBank.Clip tone = SoundBank.of("thunder_1", false, 1, sine(0.2, 440, 0.5));
        mx = new SoundMixer(new SoundBank.Clip[]{loop}, 1);
        mx.setHold(false);
        play(mx, 0.5, true);
        mx.fire(tone, 1, 0, 0.25f);
        float before = play(mx, 0.2, true)[0];
        int waiting = mx.activeVoices();
        float during = play(mx, 0.2, true)[0];
        float after = play(mx, 0.3, true)[1];
        mx.fire(tone, 1, 0, -0.1f);
        mx.touch();
        mx.render(new float[64], 32);
        int inside = mx.voicePosition();
        play(mx, 0.3, true);
        check(before == 0 && waiting == 1 && during > 0.4f && after == 0 && mx.activeVoices() == 0 && inside >= 4410,
                f("a one-shot waits for its delay (silent for 0.2 s of 0.25), sounds (peak %.2f), ends and frees its voice; a negative delay starts inside the file (frame %d)", during, inside));
        // low-pass
        SoundBank.Clip hiss = SoundBank.of("thunder_2", false, 1, sine(0.5, 9000, 0.5));
        mx.fire(hiss, 1, 0, 0);
        float bright = play(mx, 0.6, true)[0];
        mx.fire(hiss, 1, 900, 0);
        float dull = play(mx, 0.6, true)[0];
        check(bright > 0.45f && dull < bright * 0.2f && dull > 0, f("the low-pass muffles: a 9 kHz tone at a 900 Hz cutoff keeps %.0f %% of its level", dull / bright * 100));
        // the hold freezes a one-shot and lets the bed move on
        SoundBank.Clip longTone = SoundBank.of("thunder_3", false, 1, sine(4, 220, 0.5));
        mx.setBed(0, 0.5f, 0);
        play(mx, 1, true);
        mx.fire(longTone, 1, 0, 0);
        play(mx, 0.3, true);
        mx.setHold(true);
        play(mx, 0.5, true);
        int p1 = mx.voicePosition(), b1 = mx.bedPosition(0);
        float held = play(mx, 1.13, true)[0];
        int p2 = mx.voicePosition(), b2 = mx.bedPosition(0);
        mx.setHold(false);
        float back = play(mx, 0.5, true)[0];
        int p3 = mx.voicePosition();
        check(p1 > 0 && p2 == p1 && held == 0 && mx.gate() == 1 && b2 != b1 && back > 0.4f && p3 > p2 && mx.activeVoices() == 1,
                f("a hold fades everything out and freezes a thunder in flight (frame %d a second later as before) while the bed keeps moving; at the release it goes on (frame %d)", p2, p3));
        // a preview passes the hold; the master does not
        mx.setHold(true);
        play(mx, 0.6, true);
        mx.preview(tone, 1, 0);
        float prev = play(mx, 0.1, true)[0];
        play(mx, 0.3, true);
        mx.preview(loop, 1, 0.3f);
        float part = play(mx, 0.2, true)[0];
        float partEnd = play(mx, 0.3, true)[1];
        int afterPart = mx.activeVoices();
        mx.setMaster(0);
        play(mx, 0.5, true);
        mx.preview(tone, 1, 0);
        float muted = play(mx, 0.1, true)[0];
        mx.setMaster(1);
        play(mx, 0.5, true);
        check(prev > 0.4f && part > 0.3f && partEnd == 0 && afterPart == 1 && muted < 1e-3f,
                f("a Test preview is heard through a hold (peak %.2f), a preview of part of a bed ends by itself, and with the master at 0 nothing is heard (%.5f)", prev, muted));
        // leaving: every voice goes
        mx.setHold(false);
        play(mx, 0.5, true);
        int flying = mx.activeVoices();
        mx.fire(tone, 1, 0, 3);
        play(mx, 0.1, true);
        mx.flush();
        play(mx, 0.6, true);
        int afterFlush = mx.activeVoices();
        mx.fire(longTone, 1, 0, 0);
        play(mx, 0.2, true);
        mx.setHold(true);
        play(mx, 0.5, true);
        mx.flush();
        play(mx, 0.05, true);
        check(flying == 1 && afterFlush == 0 && mx.activeVoices() == 0, "leaving a game ends the thunder in flight and the one still waiting (faded within 0.4 s; behind a closed gate at once)");
        // the dead-man
        mx = new SoundMixer(new SoundBank.Clip[]{loop}, 1);
        mx.setHold(false);
        mx.setBed(0, 1, 0);
        float alive = play(mx, 1.5, true)[1];
        float stillOn = play(mx, 0.8, false)[1];
        float dead = play(mx, 1.2, false)[1];
        float again = play(mx, 1, true)[1];
        check(alive > 0.4f && stillOn > 0.4f && dead == 0 && mx.gate() == 1 && again > 0.4f,
                "the dead-man: 0.8 s without a painted frame changes nothing, after 1 s the mixer fades to 0, and the next frame brings it back");
        // a bed that is not there
        SoundMixer empty = new SoundMixer(new SoundBank.Clip[]{SoundBank.silent().clip("siren")}, 1);
        empty.setHold(false);
        empty.setBed(0, 1, 0);
        empty.fire(SoundBank.silent().clip("thunder_1"), 1, 0, 0);
        check(play(empty, 0.5, true)[0] == 0 && empty.activeVoices() == 0 && !empty.running(), "a stand-in clip is silence and takes no voice; a mixer without start() has no thread");
    }

    // ------------------------------------------------------------------ PB1: the bank, on files written here

    static void writeWav(File f, int rate, int channels, int bits, double seconds, double hz, int cutBytes) throws Exception {
        int n = (int) Math.round(seconds * rate), bytes = n * channels * bits / 8;
        ByteBuffer b = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.ISO_8859_1)).putInt(36 + bytes).put("WAVE".getBytes(StandardCharsets.ISO_8859_1));
        b.put("fmt ".getBytes(StandardCharsets.ISO_8859_1)).putInt(16).putShort((short) 1).putShort((short) channels).putInt(rate).putInt(rate * channels * bits / 8)
                .putShort((short) (channels * bits / 8)).putShort((short) bits);
        b.put("data".getBytes(StandardCharsets.ISO_8859_1)).putInt(bytes);
        for (int i = 0; i < n; i++) {
            double v = Math.sin(2 * Math.PI * hz * i / rate) * 0.5;
            for (int c = 0; c < channels; c++) {
                if (bits == 16) b.putShort((short) Math.round(v * 32767));
                else b.put((byte) (128 + Math.round(v * 127)));
            }
        }
        byte[] all = b.array();
        Files.write(f.toPath(), cutBytes > 0 ? java.util.Arrays.copyOf(all, all.length - cutBytes) : all);
    }

    private static String manifest(String form) {
        StringBuilder sb = new StringBuilder(form.equals("list") ? "{\"sounds\":[" : "{");
        for (int k = 0; k < SoundBank.IDS.length; k++) {
            String id = SoundBank.IDS[k];
            boolean car = id.startsWith("car");
            String body = f("\"file\":\"%s.wav\",\"group\":\"%s\",\"seconds\":0.25,\"loop\":%s,\"gain\":%.2f%s", id, SoundBank.GROUPS[SoundBank.defaultGroup(id)],
                    SoundBank.defaultLoop(id), 0.5 + k * 0.05, car ? ",\"peak\":0.1" : "");
            sb.append(k > 0 ? "," : "").append(form.equals("list") ? "{\"id\":\"" + id + "\"," + body + "}" : "\"" + id + "\":{" + body + "}");
        }
        return sb.append(form.equals("list") ? "]}" : "}").toString();
    }

    private static void bank(File tmp) throws Exception {
        File dir = new File(tmp, "sounds");
        dir.mkdirs();
        for (String id : SoundBank.IDS) writeWav(new File(dir, id + ".wav"), 44100, 2, 16, 0.25, 220, 0);
        Files.writeString(new File(dir, "manifest.json").toPath(), manifest("map"), StandardCharsets.UTF_8);
        SoundBank b = SoundBank.load(dir);
        boolean ok = b.manifestFound && b.loaded() == 19 && b.problems.isEmpty();
        for (String id : SoundBank.IDS) {
            SoundBank.Clip c = b.clip(id);
            ok &= !c.standIn && !c.mapped() && c.frames == 11025 && Math.abs(c.seconds() - c.manifestSeconds) < 0.05 && c.loop == SoundBank.defaultLoop(id) && c.group == SoundBank.defaultGroup(id);
        }
        ok &= Math.abs(b.clip("car_3").peak - 0.1) < 1e-9 && Double.isNaN(b.clip("siren").peak) && Math.abs(b.clip("rain_light").gain - 0.55) < 1e-9 && b.thunders().length == 7 && b.cars().length == 5
                && Math.abs(b.clip("rain_heavy").sample(50, 0) - Math.round(Math.sin(2 * Math.PI * 220 * 50 / 44100.0) * 0.5 * 32767)) <= 1;
        check(ok, "a folder of 19 WAVs and a manifest in the shipped shape loads whole: 44100 Hz stereo frames, group, loop, gain and the cars' peak as written");
        Files.writeString(new File(dir, "manifest.json").toPath(), manifest("list"), StandardCharsets.UTF_8);
        check(SoundBank.load(dir).loaded() == 19, "a manifest that lists its entries ({\"sounds\":[{\"id\":..}]}) is read as well");
        Files.writeString(new File(dir, "manifest.json").toPath(), manifest("map"), StandardCharsets.UTF_8);
        // files that are missing, not sound, cut off, or of another format
        new File(dir, "thunder_4.wav").delete();
        Files.writeString(new File(dir, "car_2.wav").toPath(), "this is not a sound file at all, only some text that is long enough to have a header's length", StandardCharsets.UTF_8);
        Files.write(new File(dir, "siren.wav").toPath(), new byte[0]);
        writeWav(new File(dir, "tornado.wav"), 44100, 2, 16, 0.25, 220, 20000);      // the header promises 44100 bytes, 20000 of them are missing
        writeWav(new File(dir, "city_a.wav"), 22050, 1, 16, 0.5, 220, 0);
        writeWav(new File(dir, "city_b.wav"), 11025, 1, 8, 0.5, 220, 0);
        writeWav(new File(dir, "hurricane.wav"), 48000, 2, 16, 0.5, 220, 0);
        java.io.PrintStream err = System.err;
        java.io.ByteArrayOutputStream said = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(said, true));
        SoundBank odd;
        try { odd = SoundBank.load(dir); } finally { System.setErr(err); }
        SoundBank.Clip mono = odd.clip("city_a"), eight = odd.clip("city_b"), fast = odd.clip("hurricane"), cut = odd.clip("tornado");
        double monoPeak = 0, eightPeak = 0;
        for (int i = 0; i < mono.frames; i++) monoPeak = Math.max(monoPeak, Math.abs(mono.sample(i, 1)));
        for (int i = 0; i < eight.frames; i++) eightPeak = Math.max(eightPeak, Math.abs(eight.sample(i, 0)));
        check(odd.clip("thunder_4").standIn && odd.clip("car_2").standIn && odd.clip("siren").standIn && odd.clip("thunder_4").frames == SoundBank.RATE && odd.loaded() == 16
                        && odd.problems.size() == 3 && said.toString().split("\n").length == 3 && odd.clip("no_such_sound").standIn,
                "a deleted file, a text file and an empty file each become one second of silence and one line on stderr; nothing is thrown, the other 16 load");
        check(!cut.standIn && cut.frames == (44100 - 20000) / 4 && Math.abs(mono.frames - 22050) <= 1 && Math.abs(eight.frames - 22050) <= 2 && Math.abs(fast.frames - 22050) <= 1
                        && Math.abs(monoPeak / 32767 - 0.5) < 0.02 && Math.abs(eightPeak / 32767 - 0.5) < 0.03 && mono.sample(900, 0) == mono.sample(900, 1),
                f("a cut-off file plays as far as it goes; mono 22050 Hz, mono 8-bit 11025 Hz and 48000 Hz files are brought to 44100 Hz stereo (%d, %d, %d frames for half a second)",
                        mono.frames, eight.frames, fast.frames));
        // the memory map
        writeWav(new File(dir, "rain_heavy.wav"), 44100, 2, 16, 0.5, 330, 0);
        SoundBank heap = SoundBank.load(new File(dir, "."), SoundBank.MAP_BYTES);
        System.setErr(new java.io.PrintStream(said, true));
        SoundBank mapped;
        try { mapped = SoundBank.load(dir, 60000); } finally { System.setErr(err); }
        SoundBank.Clip m = mapped.clip("rain_heavy"), hp = heap.clip("rain_heavy");
        boolean same = m.frames == hp.frames;
        short[] x = new short[2000], y = new short[2000];
        m.read(m.frames - 1000, x, 0, 1000);
        hp.read(hp.frames - 1000, y, 0, 1000);
        same &= java.util.Arrays.equals(x, y);
        check(m.mapped() && !hp.mapped() && same && m.heapBytes() == 0 && hp.heapBytes() == hp.frames * 4L && !mapped.clip("thunder_1").mapped() && mapped.mappedBytes() == m.frames * 4L,
                "a file in the mixer's own format above the size limit is memory-mapped (no heap) and gives the same samples as the one read into memory");
        // no manifest at all
        File none = new File(tmp, "nothing");
        none.mkdirs();
        System.setErr(new java.io.PrintStream(said, true));
        SoundBank nb;
        try { nb = SoundBank.load(none); } finally { System.setErr(err); }
        check(!nb.manifestFound && nb.loaded() == 0 && nb.clip("rain_heavy").standIn && SoundBank.silent().loaded() == 0, "a folder without a manifest gives 19 silent stand-ins, not an exception");
    }

    /** The real {@code assets/sounds}, when it is there. */
    private static void assets() throws Exception {
        File dir = new File(Main.projectRoot(), "assets/sounds");
        if (!new File(dir, "manifest.json").isFile()) {
            System.out.println("  note  assets/sounds/manifest.json is not there: the checks of the real sound files were skipped");
            return;
        }
        SoundBank.load(dir);                                   // (once to warm the disk cache; the second one is timed)
        SoundBank b = SoundBank.load(dir);
        StringBuilder bad = new StringBuilder();
        double worstSeam = 0, worstLen = 0;
        int mappedN = 0;
        for (String id : SoundBank.IDS) {
            SoundBank.Clip c = b.clip(id);
            if (c.standIn) { bad.append(' ').append(id).append(":missing"); continue; }
            File wav = new File(dir, id + ".wav");
            if (wav.isFile()) try (RandomAccessFile raf = new RandomAccessFile(wav, "r")) {
                SoundBank.Wav w = SoundBank.header(raf);
                if (!w.exact()) bad.append(' ').append(id).append(f(":%d Hz %d-bit %d ch", w.rate, w.bits, w.channels));
            }
            double off = Math.abs(c.seconds() - c.manifestSeconds);
            if (!(off <= 0.05)) bad.append(' ').append(id).append(f(":%.3f s against %.3f", c.seconds(), c.manifestSeconds));
            else worstLen = Math.max(worstLen, off);
            if (c.loop) {
                double seam = Math.max(Math.abs(c.sample(0, 0) - c.sample(c.frames - 1, 0)), Math.abs(c.sample(0, 1) - c.sample(c.frames - 1, 1))) / 32768.0;
                worstSeam = Math.max(worstSeam, seam);
                if (seam > 0.04) bad.append(' ').append(id).append(f(":seam %.3f", seam));
            }
            if (c.mapped()) mappedN++;
            if (id.startsWith("car") && !(c.peak > 0 && c.peak < c.seconds())) bad.append(' ').append(id).append(":peak");
        }
        check(b.loaded() == 19 && bad.length() == 0 && b.problems.isEmpty(),
                f("assets/sounds: all 19 files load, each 44100 Hz 16-bit stereo and within 0.05 s of the manifest's length (largest difference %.4f s); every loop's seam is below "
                        + "4 %% of full scale (largest %.2f %%); every car has its peak", worstLen, worstSeam * 100) + bad);
        check(b.heapBytes() < 30L << 20 && mappedN == 6 && b.loadMs < 1000,
                f("the six long beds are memory-mapped (%.1f MB off the heap), the 13 short sounds take %.1f MB of it (limit 30); loading took %.0f ms from a warm disk (limit 1000)",
                        b.mappedBytes() / 1048576.0, b.heapBytes() / 1048576.0, b.loadMs));
        // a few seconds of each real bed, at its manifest gain and full target: what the limiter gets
        SoundBank.Clip[] beds = new SoundBank.Clip[SoundDirector.BEDS];
        for (int k = 0; k < beds.length; k++) beds[k] = b.clip(SoundDirector.Bed.values()[k].id);
        SoundMixer mx = new SoundMixer(beds, 3);
        mx.setHold(false);
        StringBuilder levels = new StringBuilder();
        boolean heard = true;
        for (int k = 0; k < beds.length; k++) {
            for (int j = 0; j < beds.length; j++) mx.setBed(j, j == k ? 1 : 0, 0);
            play(mx, 1.5, true);
            float[] buf = new float[SoundBank.RATE * 2 * 2];
            mx.touch();
            mx.render(buf, SoundBank.RATE);
            mx.touch();
            mx.render(buf, SoundBank.RATE * 2);
            double sum = 0;
            for (float v : buf) sum += v * v;
            double rms = Math.sqrt(sum / buf.length);
            heard &= rms > 0.002 && rms < 0.5;
            levels.append(SoundDirector.Bed.values()[k].id).append(f(" %.3f; ", rms));
        }
        check(heard, "each real bed alone at full target comes out of the mixer at a sane level (RMS of 2 s, full scale = 1): " + levels);
        // the worst case: all seven beds at full target, three thunders and three cars on top, every gain of the manifest applied
        double bound = 0;
        double[] shotPeak = new double[SoundBank.IDS.length];
        for (int k = 0; k < SoundBank.IDS.length; k++) {
            SoundBank.Clip c = b.clip(SoundBank.IDS[k]);
            int pk = 0;
            short[] chunk = new short[8192 * 2];
            for (int at = 0; at < c.frames; at += 8192) {
                int n = Math.min(8192, c.frames - at);
                c.read(at, chunk, 0, n);
                for (int i = 0; i < n * 2; i++) pk = Math.max(pk, Math.abs(chunk[i]));
            }
            double lvl = pk / 32768.0 * c.gain;
            if (c.loop) bound += lvl; else shotPeak[k] = lvl;
        }
        for (String prefix : new String[]{"thunder", "car"}) {
            double[] top = new double[3];
            for (int k = 0; k < SoundBank.IDS.length; k++) if (SoundBank.IDS[k].startsWith(prefix)) {
                double v = shotPeak[k];
                for (int j = 0; j < 3; j++) if (v > top[j]) { double t = top[j]; top[j] = v; v = t; }
            }
            bound += top[0] + top[1] + top[2];
        }
        for (int j = 0; j < beds.length; j++) mx.setBed(j, 1, 0);
        play(mx, 1.5, true);
        float worst = 0;
        for (int round = 0; round < 3; round++) {
            for (SoundBank.Clip c : b.thunders()) if (mx.activeVoices() < 3) mx.fire(c, 1, 0, 0);
            for (int k = 0; k < 3; k++) mx.fire(b.cars()[k], 1, 0, 0);
            worst = Math.max(worst, play(mx, 6, true)[0]);
            mx.flush();
            play(mx, 0.6, true);
        }
        check(worst <= 1f && worst > 0.3f, f("the worst-case mix of the real files (seven beds at full target, three thunders, three cars, the manifest's gains from %.2f to %.2f): the sum of "
                + "their peaks is %.2f of full scale before the limiter, the mixer's output peaks at %.4f", b.clip("tornado").gain, b.clip("city_b").gain, bound, worst));
    }

    // ------------------------------------------------------------------ PD1: the settings block

    private static void settings(File tmp) throws Exception {
        File dir = new File(tmp, "cfg");
        dir.mkdirs();
        Settings def = new Settings(dir);
        String plain = Settings.pretty(def.toJson());
        check(!plain.contains("\"sound\"") && def.soundOn && def.soundMaster == 0.7 && def.soundRain == 0.8 && def.soundThunder == 0.9 && def.soundTraffic == 0.7 && def.soundTowns == 0.5
                && def.soundStorm == 0.9 && def.soundDefault() && Settings.VERSION == 1, "a new player's settings have the sound on at master 70 % and write no \"sound\" key; the file version is still 1");
        Files.writeString(new File(dir, "settings.json").toPath(), "{\"version\":1,\"graphics\":{\"cloudQuality\":2}}", StandardCharsets.UTF_8);
        Settings old = Settings.load(dir);
        old.soundTowns = 0.35;
        old.soundOn = false;
        old.save();
        String written = Files.readString(new File(dir, "settings.json").toPath(), StandardCharsets.UTF_8).replace(" ", "");
        Settings back = Settings.load(dir);
        Files.writeString(new File(dir, "settings.json").toPath(), "{\"version\":1,\"sound\":{\"master\":7.5,\"rain\":-2,\"thunder\":\"loud\",\"storm\":0.25,\"someFutureKey\":1}}", StandardCharsets.UTF_8);
        Settings odd = Settings.load(dir);
        Files.writeString(new File(dir, "settings.json").toPath(), "{\"version\":1,\"sound\":7}", StandardCharsets.UTF_8);
        Settings notObj = Settings.load(dir);
        check(old.loadWarning == null && old.cloudQuality == 2 && written.contains("\"sound\":{\"on\":false,\"master\":0.7,\"rain\":0.8,\"thunder\":0.9,\"traffic\":0.7,\"towns\":0.35,\"storm\":0.9}")
                        && !back.soundOn && back.soundTowns == 0.35 && back.soundMaster == 0.7 && back.cloudQuality == 2
                        && odd.soundMaster == 1.0 && odd.soundRain == 0 && odd.soundThunder == 0.9 && odd.soundStorm == 0.25 && odd.soundOn && notObj.soundDefault() && notObj.loadWarning == null,
                "a file from before the tab loads the defaults; a changed value and the mute round-trip under \"sound\"; 7.5 reads as 1.0, -2 as 0, a wrong type keeps the default");
    }

    // ------------------------------------------------------------------ PB3: where there must be no sound

    private static void silence() {
        File root = Main.projectRoot();
        Settings s = new Settings(new File(root, "config"));
        s.persist = false;
        int lines = SoundMixer.linesOpened;
        String keepOff = System.getProperty("precip.nosound"), keepHead = System.getProperty("java.awt.headless");
        Sound a, b;
        String whyA, whyB;
        try {
            System.setProperty("precip.nosound", "true");
            a = Sound.open(root, s);
            whyA = Sound.problem;
            System.clearProperty("precip.nosound");
            System.setProperty("java.awt.headless", "true");
            b = Sound.open(root, s);
            whyB = Sound.problem;
        } finally {
            if (keepOff != null) System.setProperty("precip.nosound", keepOff); else System.clearProperty("precip.nosound");
            if (keepHead != null) System.setProperty("java.awt.headless", keepHead); else System.clearProperty("java.awt.headless");
            Sound.problem = null;
        }
        check(a == Sound.NONE && b == Sound.NONE && whyA != null && whyB != null && !whyA.equals(whyB) && !Sound.NONE.available() && SoundMixer.linesOpened == lines,
                "Sound.open gives Sound.NONE with -Dprecip.nosound (--no-sound, PRECIP_NOSOUND) and with java.awt.headless=true, each with its reason, and opens no line");
        AppShell shell = new AppShell(root, s, new SaveStore(new File(root, "saves")));
        Sound.NONE.session(null);
        Sound.NONE.frame(null, 0);
        Sound.NONE.apply(s);
        Sound.NONE.preview("thunder");
        Sound.NONE.close();
        shell.io.shutdown();
        check(shell.sound == Sound.NONE && shell.soundBlocked(), "a new AppShell has Sound.NONE (as every test and snapshot builds it), and Sound.NONE takes every call");
    }

    // ------------------------------------------------------------------ PD2-PD5: the tab and the wiring

    /** A sound that only writes down what it is told. */
    private static final class Probe extends Sound {
        int sessions, frames, closes, previews, applies;
        GameSession last;
        boolean blocked;
        double master = -1;
        String previewed = "";

        @Override void session(GameSession gs) { sessions++; last = gs; }

        @Override void frame(ViewerPanel p, double dt) { frames++; }

        @Override void blocked(boolean b) { blocked = b; }

        @Override void apply(Settings s) { applies++; master = s.soundOn ? s.soundMaster : 0; }

        @Override void preview(String what) { previews++; previewed = what; }

        @Override boolean available() { return true; }

        @Override void close() { closes++; }
    }

    private static void edt(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static BufferedImage canvas = new BufferedImage(1680, 760, BufferedImage.TYPE_INT_RGB);

    private static void paint(AppShell app, int w, int h) {
        edt(() -> {
            ViewerPanel p = app.panel;
            if (canvas.getWidth() != w || canvas.getHeight() != h) canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            if (p.getWidth() != w || p.getHeight() != h) p.setSize(w, h);
            Graphics2D g = canvas.createGraphics();
            p.paint(g);
            g.dispose();
        });
    }

    private static void paint(AppShell app) { paint(app, 1680, 760); }

    private static void click(AppShell app, double x, double y) {
        paint(app);
        final int ix = (int) Math.round(x), iy = (int) Math.round(y);
        edt(() -> {
            ViewerPanel p = app.panel;
            MouseEvent press = new MouseEvent(p, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, ix, iy, 1, false, MouseEvent.BUTTON1);
            MouseEvent release = new MouseEvent(p, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, ix, iy, 1, false, MouseEvent.BUTTON1);
            for (MouseListener l : p.getMouseListeners()) l.mousePressed(press);
            for (MouseListener l : p.getMouseListeners()) l.mouseReleased(release);
        });
        paint(app);
    }

    private static void key(AppShell app, int code) {
        paint(app);
        edt(() -> {
            ViewerPanel p = app.panel;
            KeyEvent press = new KeyEvent(p, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, KeyEvent.CHAR_UNDEFINED);
            for (KeyListener l : p.getKeyListeners()) l.keyPressed(press);
        });
        paint(app);
    }

    /** Paint until the game has a frame (the fake simulation delivers on its own thread). */
    private static boolean awaitFrame(AppShell app, boolean hurricane) {
        for (int k = 0; k < 400; k++) {
            paint(app);
            GameSession gs = app.session;
            if (gs != null) synchronized (gs.state) {
                if (hurricane ? gs.state.hurricane != null && gs.state.hurricane.cur != null : gs.state.cur != null && gs.state.hello != null) return true;
            }
            try { Thread.sleep(10); } catch (InterruptedException ignored) { }
        }
        return false;
    }

    private static void wiring(File tmp) throws Exception {
        File root = Main.projectRoot();
        File real = new File(root, "config/settings.json");
        String before = real.isFile() ? real.length() + "@" + real.lastModified() : "-";
        Advanced keepAdv = Advanced.cur;
        boolean keepSafe = Renderer.epilepsySafe;
        int keepQuality = CloudPainter.quality;
        double keepScale = ViewerPanel.maxRenderScale;
        Settings settings = new Settings(new File(tmp, "config"));      // a real file, in the temp folder
        AppShell app = new AppShell(root, settings, new SaveStore(new File(tmp, "saves")));
        app.fakeModes = true;
        Probe probe = new Probe();
        try {
            // ---- the menu
            edt(() -> { app.sound = probe; app.applySettings(); app.showMenu(null); });
            for (int k = 0; k < 4; k++) paint(app);
            check(probe.sessions >= 1 && probe.last == null && probe.frames == 0 && probe.applies >= 1 && Math.abs(probe.master - 0.7) < 1e-9 && app.soundBlocked(),
                    "the main menu: the sound is told there is no game, hears of no frame of the decorative sky, and has the settings' volumes");

            // ---- the Sound tab
            edt(() -> app.openSettings(SettingsScreen.CONTROLS));
            SettingsScreen[] top = {null};
            edt(() -> top[0] = (SettingsScreen) app.top());
            final SettingsScreen ss = top[0];
            key(app, KeyEvent.VK_TAB);
            boolean byKey = ss.tab == SettingsScreen.SOUND;
            key(app, KeyEvent.VK_TAB);
            boolean wraps = ss.tab == SettingsScreen.BACKGROUND;
            double dx = Math.round((1680 - 900) / 2.0), dy = Math.round((760 - 600) / 2.0);
            click(app, dx + 16 + 75, dy + 62 + 44 * SettingsScreen.GAME + 19);
            boolean oldRow = ss.tab == SettingsScreen.GAME;
            click(app, dx + 16 + 75, dy + 62 + 44 * SettingsScreen.SOUND + 19);
            check(byKey && wraps && oldRow && ss.tab == SettingsScreen.SOUND && SettingsScreen.TABS.length == 6 && SettingsScreen.TABS[5].equals("Sound")
                            && SettingsScreen.TABS[SettingsScreen.CONTROLS].equals("Controls"),
                    "Settings has a sixth tab \"Sound\" after Controls: Tab reaches it from Controls and goes on to Background, a click on its row selects it, and the older rows are where they were");
            boolean painted = true;
            for (int[] wh : new int[][]{{1040, 620}, {1680, 760}}) {
                paint(app, wh[0], wh[1]);
                double w = Math.min(900, wh[0] - 24), h = Math.min(600, wh[1] - 24), x0 = Math.round((wh[0] - w) / 2), y0 = Math.round((wh[1] - h) / 2);
                for (double[] r : ss.soundRects) painted &= r != null && r[0] >= x0 + 204 && r[0] + r[2] <= x0 + w - 24 + 0.5 && r[1] >= y0 + 62 && r[1] + r[3] <= y0 + h - 66 + 0.5;
            }
            check(painted, "the Sound page paints at 1040 x 620 and at 1680 x 760, every control inside the page");
            paint(app);
            File file = new File(tmp, "config/settings.json");
            double[] m = ss.soundRects[1];
            click(app, m[0] + 8 + (m[2] - 16) * 0.25, m[1] + 12);
            String afterMaster = file.isFile() ? Files.readString(file.toPath(), StandardCharsets.UTF_8).replace(" ", "") : "";
            double[] st = ss.soundRects[6];
            click(app, st[0] + 8 + (st[2] - 16) * 0.5, st[1] + 12);
            boolean storm = Math.abs(settings.soundStorm - 0.5) < 0.02 && settings.soundRain == 0.8;
            double[] bt = ss.soundRects[7], br = ss.soundRects[8];
            click(app, bt[0] + 20, bt[1] + 10);
            String firstPreview = probe.previewed;
            click(app, br[0] + 20, br[1] + 10);
            int previews = probe.previews;
            double[] tg = ss.soundRects[0];
            click(app, tg[0] + 20, tg[1] + 12);
            String afterMute = Files.readString(file.toPath(), StandardCharsets.UTF_8).replace(" ", "");
            click(app, bt[0] + 20, bt[1] + 10);                 // muted: the Test buttons are off
            check(Math.abs(settings.soundMaster - 0.25) < 0.02 && afterMaster.contains("\"sound\":{\"on\":true,\"master\":" + settings.soundMaster) && storm && !settings.soundOn
                            && afterMute.contains("\"on\":false") && probe.master == 0 && previews == 2 && probe.previews == 2 && firstPreview.equals("thunder") && probe.previewed.equals("rain"),
                    f("a click on the Master slider sets it (%.2f) and writes config/settings.json at once, the Storm slider moves only Storm, the switch mutes (master 0 at the mixer), "
                            + "and the two Test buttons ask for a thunder and for rain while it is on (%d previews)", settings.soundMaster, probe.previews));
            edt(() -> { settings.soundOn = true; settings.soundMaster = 0.7; settings.soundStorm = 0.9; app.settingsChanged(); app.pop(ss); });
            check(!Files.readString(file.toPath(), StandardCharsets.UTF_8).contains("\"sound\""), "back at the defaults the file loses its \"sound\" key again");

            // ---- a sandbox: the hook, pause, dialogs, leaving
            edt(() -> app.startDiagnostic(new FakeSim(7, 0.4, 3840, 1080, null, null, Double.NaN, Double.NaN, "supercell"), "sound test"));
            GameSession game = app.session;
            boolean running = awaitFrame(app, false);
            int f0 = probe.frames;
            for (int k = 0; k < 5; k++) paint(app);
            boolean perFrame = probe.frames == f0 + 5 && probe.last == game && game != null && !probe.blocked && !app.soundBlocked();
            edt(app::openPause);
            paint(app);
            boolean paused = probe.blocked;
            edt(() -> app.openSettings(SettingsScreen.SOUND));
            paint(app);
            paused &= probe.blocked;
            edt(() -> app.pop(app.top()));
            edt(app::confirmLeaveWithoutSaving);
            paint(app);
            paused &= probe.blocked && app.top() instanceof Screen.Confirm;
            edt(() -> app.pop(app.top()));
            edt(app::resumeFromPause);
            paint(app);
            boolean resumed = !probe.blocked;
            int f1 = probe.frames;
            edt(app::leaveNow);
            for (int k = 0; k < 3; k++) paint(app);
            check(running && perFrame && paused && resumed && probe.last == null && probe.frames == f1 && app.soundBlocked(),
                    "a sandbox: the sound gets the session and one call per painted frame; the pause menu, Settings over it and a question over it block it, Resume unblocks it; "
                            + "Leave tells it the game is over and no frame follows");

            // ---- the lesson modes
            edt(() -> app.startShowcase("cumulonimbus"));
            GameSession sc = app.session;
            boolean scRun = awaitFrame(app, false);
            int f2 = probe.frames;
            for (int k = 0; k < 3; k++) paint(app);
            boolean showcase = scRun && probe.last == sc && probe.frames == f2 + 3 && !probe.blocked;
            edt(app::leaveNow);
            edt(app::startHurricane);
            GameSession hu = app.session;
            boolean huRun = awaitFrame(app, true);
            int f3 = probe.frames;
            for (int k = 0; k < 3; k++) paint(app);
            boolean hurricane = huRun && probe.last == hu && probe.frames == f3 + 3 && !probe.blocked && app.panel.scene != null;
            edt(() -> app.openSettings(SettingsScreen.SOUND));
            paint(app);
            hurricane &= probe.blocked;
            edt(() -> app.pop(app.top()));
            edt(app::leaveNow);
            boolean left = probe.last == null;
            int f4 = probe.frames;
            edt(app::startWorld);
            for (int k = 0; k < 3; k++) paint(app);
            boolean world = probe.last == null && probe.frames == f4 && app.panel.frameHook == null;
            edt(app::leaveNow);
            edt(app::startDebug);
            for (int k = 0; k < 3; k++) paint(app);
            boolean debug = probe.last == null && probe.frames == f4 && app.panel.frameHook == null;
            edt(app::leaveNow);
            check(showcase && hurricane && left && world && debug,
                    "Cloud Formations and Hurricane Formation (a Scene) reach the sound once per painted frame, and Settings over the hurricane screen blocks it; "
                            + "the World map and the Debug mode never do");

            // ---- the real facade on a mixer without a line: from a frame of the game to samples
            SoundBank.Clip[] beds = new SoundBank.Clip[SoundDirector.BEDS];
            for (int b = 0; b < beds.length; b++) beds[b] = SoundBank.of(SoundDirector.Bed.values()[b].id, true, 1, noise(0.5 + b * 0.1, 0.5, b));
            SoundMixer mx = new SoundMixer(beds, 5);
            Sound live = Sound.live(SoundBank.silent(), mx, SoundDirector.of(SoundBank.silent()));
            edt(() -> { app.sound = live; app.applySettings(); });
            edt(() -> app.startDiagnostic(new FakeSim(7, 0.4, 3840, 1080, null, null, Double.NaN, Double.NaN, "supercell"), "sound test"));
            boolean heldAtStart = mx.hold();
            awaitFrame(app, false);
            paint(app);
            boolean open = !mx.hold() && live.available();
            // bring the view under whatever rains in this scene and listen
            float heard = 0;
            for (int k = 0; k < 400 && heard == 0; k++) {
                Model.Cloud wet = null;
                synchronized (app.session.state) {
                    for (Model.Cloud c : app.session.state.cur.clouds) if (c.stage.equals("precipitating") && c.precipRate > 0.1 && !c.precipType.equals("none") && !c.fair) wet = c;
                }
                if (wet != null) {
                    final double x = wet.x;
                    edt(() -> { app.panel.camera().centerOn(x, 700); app.panel.camera().snap(); });
                    paint(app);
                    paint(app);
                    heard = play(mx, 0.6, true)[1];
                } else {
                    paint(app);
                    try { Thread.sleep(25); } catch (InterruptedException ignored) { }
                }
            }
            edt(app::openPause);
            paint(app);
            boolean heldInPause = mx.hold();
            float quiet = play(mx, 0.8, true)[1];
            edt(app::resumeFromPause);
            for (int k = 0; k < 300 && (k == 0 || mx.hold()); k++) {            // (the simulation answers the resume on its own thread: until then its frames still say "paused")
                paint(app);
                if (mx.hold()) try { Thread.sleep(10); } catch (InterruptedException ignored) { }
            }
            boolean openAgain = !mx.hold();
            edt(app::leaveNow);
            boolean heldInMenu = mx.hold();
            float menu = play(mx, 0.8, true)[1];
            check(heldAtStart && open && heard > 0.01f && heldInPause && quiet == 0 && openAgain && heldInMenu && menu == 0 && mx.activeVoices() == 0,
                    f("the real facade on a mixer without a line: under the raining cloud of the fake supercell scene samples come out (peak %.3f), the pause menu silences and holds it, "
                            + "Resume opens it, and back in the menu it is silent with no voice left", heard));

            // ---- quit
            edt(() -> { app.sound = probe; app.onExit = () -> { }; app.exitNow(); });
            check(probe.closes == 1, "Quit closes the sound, once");
            String after = real.isFile() ? real.length() + "@" + real.lastModified() : "-";
            check(before.equals(after), "the game's own config/settings.json was not written by these checks");
        } finally {
            edt(() -> { if (app.panel != null) app.panel.dispose(); });
            app.io.shutdown();
            app.awaitStops();
            Advanced.publish(keepAdv);
            Renderer.epilepsySafe = keepSafe;
            CloudPainter.quality = keepQuality;
            ViewerPanel.maxRenderScale = keepScale;
        }
    }

    // ------------------------------------------------------------------ PE2: cost

    /** The frame of the plan's budget: 12 clouds, 40 cars, 6 bolts, 3 tornadoes. */
    private static Model.Frame busyFrame() {
        StringBuilder clouds = new StringBuilder(), cars = new StringBuilder(), bolts = new StringBuilder();
        StringBuilder puffs = new StringBuilder();
        for (int p = 0; p < 30; p++) puffs.append(p > 0 ? "," : "").append(f("[%d,%d,%d,0.5]", p * 20 - 300, -p % 7 * 10, 40 + p % 5 * 8));
        for (int k = 0; k < 12; k++)
            clouds.append(k > 0 ? "," : "").append(cloud(k + 1, Model.CLOUD_TYPES[k % Model.CLOUD_TYPES.length], "precipitating", k % 3 == 0 ? "snow" : "rain", 0.3 + k * 0.05, LX - 1800 + k * 330, 700,
                    "\"wall\":0.5,\"puffs\":[" + puffs + "]"));
        for (int k = 0; k < 40; k++) cars.append(k > 0 ? "," : "").append(car(k + 1, LX - 1500 + k * 77, 30 + k, k % 2 == 0 ? 1 : -1, k % 9 == 0 ? "stopped" : "driving"));
        for (int k = 0; k < 6; k++) bolts.append(k > 0 ? "," : "").append(bolt(LX - 900 + k * 333, 1 - k * 0.1));
        return frame(1, false, clouds.toString(), bolts.toString(), cars.toString(),
                twister(1, 9, LX + 200, 1, 0.9, "mature") + "," + twister(2, 9, LX - 700, 0.95, 0.5, "roping") + "," + twister(3, 9, LX + 900, 0.6, 0.4, "funnel"));
    }

    private static long allocated() {
        try {
            return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().threadId());
        } catch (Throwable ex) {
            return -1;
        }
    }

    /** Milliseconds one director step takes on the busy frame, and the bytes it allocates. */
    static double[] directorCost() {
        Model.Hello h = hello(9, "city:3800:4400", "town:1000:1500", "village:6000:6300");
        Model.Frame fr = busyFrame();
        SoundDirector d = director(3);
        SoundDirector.Input in = input(h, fr);
        in.dt = 1 / 60.0;
        double sink = 0;
        for (int k = 0; k < 30000; k++) { in.lx = LX + (k % 500) - 250; sink += d.step(in).bed[HEAVY]; }
        long a0 = allocated();
        double best = Double.MAX_VALUE;
        for (int r = 0; r < 5; r++) {
            long t0 = System.nanoTime();
            for (int k = 0; k < 20000; k++) { in.lx = LX + (k % 500) - 250; sink += d.step(in).bed[HEAVY]; }
            best = Math.min(best, (System.nanoTime() - t0) / 1e6 / 20000);
        }
        long a1 = allocated();
        return new double[]{best, a0 < 0 || a1 < 0 ? -1 : (a1 - a0) / 100000.0, sink};
    }

    /** A mixer with eight voices sounding: five beds (two of them low-passed) and three long one-shots. */
    private static SoundMixer eightVoices(SoundBank bank) {
        SoundBank.Clip[] beds = new SoundBank.Clip[SoundDirector.BEDS];
        for (int b = 0; b < beds.length; b++) {
            SoundBank.Clip c = bank.clip(SoundDirector.Bed.values()[b].id);
            beds[b] = c.standIn ? SoundBank.of(c.id, true, 0.3, noise(3 + b * 0.37, 0.5, b)) : c;
        }
        SoundMixer mx = new SoundMixer(beds, 1);
        for (int b = 0; b < 5; b++) mx.setBed(b, 0.6f, b >= 3 ? 2500 : 0);
        return mx;
    }

    private static void refill(SoundMixer mx, SoundBank.Clip longShot) {
        while (mx.activeVoices() < 3) {
            mx.fire(longShot, 0.5f, mx.activeVoices() == 1 ? 3000 : 0, 0);
            mx.touch();
            mx.render(new float[2], 1);
        }
    }

    /** Percent of one core the mixing of eight voices takes (CPU seconds per second of sound). */
    static double mixerCost() {
        File dir = new File(Main.projectRoot(), "assets/sounds");
        SoundBank bank = new File(dir, "manifest.json").isFile() ? SoundBank.load(dir) : SoundBank.silent();
        SoundMixer mx = eightVoices(bank);
        mx.setHold(false);
        SoundBank.Clip longShot = SoundBank.of("thunder_1", false, 1, noise(30, 0.4, 5));
        float[] buf = new float[SoundMixer.BLOCK * 2];
        byte[] bytes = new byte[SoundMixer.BLOCK * 4];
        double best = Double.MAX_VALUE;
        for (int r = 0; r < 4; r++) {
            refill(mx, longShot);
            int blocks = 10 * SoundBank.RATE / SoundMixer.BLOCK;
            long t0 = System.nanoTime();
            for (int k = 0; k < blocks; k++) {
                mx.touch();
                mx.render(buf, SoundMixer.BLOCK);
                for (int i = 0, j = 0; i < buf.length; i++) { int s = (int) (buf[i] * 32767f); bytes[j++] = (byte) s; bytes[j++] = (byte) (s >> 8); }     // as the thread does
            }
            double seconds = (System.nanoTime() - t0) / 1e9;
            if (r > 0) best = Math.min(best, seconds / (blocks * SoundMixer.BLOCK / (double) SoundBank.RATE) * 100);
        }
        return best;
    }

    private static void cost() {
        double[] dc = directorCost();
        check(dc[0] < 0.05 && dc[1] >= -1 && dc[1] < 1,
                f("cost: the director takes %.4f ms for a frame of 12 clouds, 40 cars, 6 bolts and 3 tornadoes (limit 0.05) and allocates %.2f bytes a frame", dc[0], dc[1]));
        double mc = mixerCost();
        check(mc < 1, f("cost: mixing eight voices (five beds, three one-shots, three of them low-passed) takes %.2f %% of one core (limit 1)", mc));
    }

    // ------------------------------------------------------------------ the line (never part of run())

    /** Open the real line, play silence through it for {@code seconds} (master 0, eight voices mixed), and say what the thread cost. */
    private static int line(double seconds) throws Exception {
        File dir = new File(Main.projectRoot(), "assets/sounds");
        SoundBank bank = new File(dir, "manifest.json").isFile() ? SoundBank.load(dir) : SoundBank.silent();
        SoundMixer mx = eightVoices(bank);
        mx.setMaster(0);                                       // nothing is heard: this only proves the line opens and measures the thread
        SoundBank.Clip longShot = SoundBank.of("thunder_1", false, 1, noise(Math.max(4, seconds + 2), 0.4, 5));
        try {
            mx.start();
        } catch (javax.sound.sampled.LineUnavailableException | IllegalArgumentException | SecurityException ex) {
            System.out.println("[sound-line] no audio line: " + ex);
            return 1;
        }
        mx.setHold(false);
        for (int k = 0; k < 3; k++) mx.fire(longShot, 0.5f, k == 1 ? 3000 : 0, 0);
        long c0 = -1, t0 = 0;
        long end = System.nanoTime() + (long) (seconds * 1e9);
        while (System.nanoTime() < end) {
            mx.touch();
            Thread.sleep(16);
            if (c0 < 0 && System.nanoTime() > end - (long) (seconds * 0.5e9)) { c0 = mx.cpuNanos(); t0 = System.nanoTime(); }     // (the second half: the first is the JIT warming up)
        }
        long c1 = mx.cpuNanos(), t1 = System.nanoTime();
        boolean ran = mx.running();
        long q0 = System.nanoTime();
        mx.close();
        double closeMs = (System.nanoTime() - q0) / 1e6;
        System.out.println(f("[sound-line] the line opened (%d), the mixer thread ran: %s; %d bank files; eight voices at master 0 for %.1f s cost %.2f %% of one core; close() took %.0f ms; thread alive after it: %s",
                SoundMixer.linesOpened, ran, bank.loaded(), (t1 - t0) / 1e9, c0 < 0 || c1 < 0 ? -1 : (c1 - c0) * 100.0 / (t1 - t0), closeMs, mx.running()));
        return ran && !mx.running() ? 0 : 1;
    }

    public static void main(String[] a) throws Exception {
        if (a.length >= 1 && a[0].equals("--line")) {
            System.exit(line(a.length > 1 ? Double.parseDouble(a[1]) : 3));
            return;
        }
        if (a.length >= 1 && a[0].equals("--cost")) {
            double[] dc = directorCost();
            System.out.println(f("[sound-cost] director %.4f ms a frame, %.2f bytes a frame; mixer %.2f %% of one core for eight voices", dc[0], dc[1], mixerCost()));
            return;
        }
        System.exit(run());
    }
}
