package precip;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Built-in stand-in for {@code python sim/precip_sim.py --mode hurricane} (protocol v2.2), so the
 * hurricane screen can be developed, snapshotted and tested without Python. It speaks the same
 * messages: a {@code hello} with ten stages and a guided tour, then {@code hurricane} frames whose
 * state, profile, bands and cross-section glide smoothly between stages.
 * <p>
 * <b>Nothing in here is real data.</b> The numbers are rough stand-ins and every sentence is
 * marked placeholder text; the real figures and the real lesson come from the simulation.
 */
final class FakeHurricane implements FrameSource, Scripted {
    static final double DT = 1.0 / 30.0;
    private static final double R = 600, TOP = 18, STAGE_SECONDS = 12;
    private static final int COLS = 48, ROWS = 16;

    private static final String[] IDS = {"disturbance", "depression", "storm", "cat1", "cat2", "cat3", "cat4", "cat5", "landfall", "dissipation"};
    private static final String[] NAMES = {"Tropical disturbance", "Tropical depression", "Tropical storm", "Category 1 hurricane",
            "Category 2 hurricane", "Category 3 hurricane", "Category 4 hurricane", "Category 5 hurricane", "Landfall", "Dissipation"};
    private static final int[][] WIND = {{0, 25}, {26, 33}, {34, 63}, {64, 82}, {83, 95}, {96, 112}, {113, 136}, {137, -1}, {60, 130}, {0, 60}};
    private static final int[] CAT = {-2, -1, 0, 1, 2, 3, 4, 5, 4, -2};
    /** Per stage: vmax kt, pressure, rmw, eye, r34, cloud top, rain, surge, organization, eye clarity, warm core, sst, over land. */
    private static final double[][] P = {
            {12, 1010, 90, 0, 0, 10, 6, 0.05, 0.12, 0, 0.6, 27.5, 0},
            {27, 1007, 67, 0, 0, 11, 9, 0.2, 0.30, 0, 1.8, 27.9, 0},
            {40, 1002, 53, 13, 104, 12.5, 13.5, 0.6, 0.50, 0.10, 3.5, 28.3, 0},
            {66, 989, 43, 21, 169, 14, 21, 1.3, 0.65, 0.35, 5.5, 28.7, 0},
            {88, 975, 36, 20, 180, 14.8, 28, 2.0, 0.75, 0.55, 7.2, 29.0, 0},
            {99, 963, 31, 19, 187, 15.5, 35, 2.9, 0.84, 0.72, 9, 29.4, 0},
            {120, 940, 24, 15, 175, 16.3, 42, 4.3, 0.92, 0.88, 11, 29.7, 0},
            {141, 918, 18, 12.5, 157, 17, 50, 5.8, 0.97, 0.97, 13, 30.0, 0},
            {130, 932, 21, 14.4, 165, 16, 44, 5.1, 0.82, 0.75, 11, 29.1, 1},
            {60, 990, 36, 23, 153, 12, 23, 1.1, 0.50, 0.25, 6, 28.1, 1}};
    private static final String[][] LESSON = {
            {"ingredients", "What a hurricane needs", "outside", "sst", "disturbance"},
            {"spin", "Why it spins", "outside", "coriolis", "depression"},
            {"organising", "Getting organised", "outside", "rainbands", "storm"},
            {"eye", "From outside: the eye", "outside", "eye", "cat3"},
            {"eyewall", "From outside: the eyewall", "outside", "eyewall", "cat3"},
            {"shield", "From outside: the cirrus shield", "outside", "outflow", "cat3"},
            {"inflow", "Inside: air spirals in", "inside", "inflow", "cat3"},
            {"updraft", "Inside: the rising eyewall", "inside", "eyewall", "cat3"},
            {"outflow", "Inside: outflow aloft", "inside", "outflow", "cat3"},
            {"core", "Inside: the warm, clear eye", "inside", "warm_core", "cat4"},
            {"surge", "Storm surge", "both", "surge", "landfall"},
            {"decay", "How it dies", "both", "decay", "dissipation"},
            {"explore", "Now explore", "both", "none", "cat3"}};

    private final double[] s = P[0].clone();      // the gliding state
    private int stage;
    private double stageT, t, speed = 1, rotation = 2.4;
    private boolean paused, auto;
    private long frameNo;
    private int sectionClock;
    private final ConcurrentLinkedQueue<String> commands = new ConcurrentLinkedQueue<>();
    private volatile boolean running;

    @Override public String describe() { return "built-in fake hurricane"; }

    @Override public void start(ViewerState state) {
        running = true;
        state.acceptLine(hello());
        Thread th = new Thread(() -> {
            long next = System.nanoTime();
            while (running) {
                String cmd;
                while ((cmd = commands.poll()) != null) handle(cmd);
                state.acceptLine(step());
                next += (long) (DT * 1e9);
                long sleep = next - System.nanoTime();
                if (sleep > 0) {
                    try { Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000)); } catch (InterruptedException e) { return; }
                } else next = System.nanoTime();
            }
        }, "fake-hurricane");
        th.setDaemon(true);
        th.start();
    }

    @Override public void send(String json) { commands.add(json); }

    @Override public void stop() { running = false; }

    void handle(String json) {
        Map<String, Object> m;
        try { m = Json.obj(Json.parse(json)); } catch (RuntimeException e) { return; }
        switch (Json.str(m, "cmd", "")) {
            case "pause" -> paused = true;
            case "resume" -> paused = false;
            case "speed" -> speed = Model.clamp(Json.num(m, "value", 1), 0.25, 8);
            case "stage" -> {
                String id = Json.str(m, "id", "");
                for (int k = 0; k < IDS.length; k++) if (IDS[k].equals(id)) { stage = k; stageT = 0; auto = false; }
            }
            case "auto" -> auto = Json.bool(m, "value", true);
            case "quit" -> running = false;
            default -> { }
        }
    }

    @Override public String hello() {
        StringBuilder sb = new StringBuilder(8000);
        sb.append("{\"type\":\"hello\",\"version\":2,\"seed\":1,\"loaded\":false,\"t\":0,\"mode\":\"hurricane\",")
                .append("\"world\":{\"w\":1920,\"h\":1080,\"ground_y\":1000,\"terrain_kind\":\"flat\",\"terrain\":{\"step\":960,\"y\":[1000,1000,1000]}},")
                .append("\"settlements\":[],\"objects\":[],\"hurricane\":{\"domain\":{\"radius_km\":").append((int) R).append(",\"top_km\":").append((int) TOP).append("},\"stages\":[");
        for (int k = 0; k < IDS.length; k++) {
            if (k > 0) sb.append(',');
            sb.append("{\"id\":\"").append(IDS[k]).append("\",\"name\":").append(Json.quote(NAMES[k])).append(",\"wind_kt\":[").append(WIND[k][0]).append(',')
                    .append(WIND[k][1] < 0 ? "null" : String.valueOf(WIND[k][1])).append("],\"pressure_hpa\":").append((int) P[k][1]).append(",\"category\":").append(CAT[k])
                    .append(",\"summary\":").append(Json.quote("[Fake simulation: placeholder text] The real simulation summarises the stage \"" + NAMES[k]
                            + "\" here in two to four sentences. This sentence shows how the summary wraps in the panel, and this one makes it a realistic length.")).append('}');
        }
        sb.append("],\"lesson\":[");
        for (int k = 0; k < LESSON.length; k++) {
            String[] l = LESSON[k];
            if (k > 0) sb.append(',');
            sb.append("{\"id\":\"").append(l[0]).append("\",\"title\":").append(Json.quote(l[1])).append(",\"text\":").append(Json.quote(
                    "[Fake simulation: placeholder text] The real simulation tells this chapter of the tour (\"" + l[1] + "\") in three to six sentences. "
                            + "The picture moves to the stage the chapter is about and highlights what it talks about. "
                            + "This sentence only shows how a paragraph of a realistic length wraps in the text panel. "
                            + "A fourth sentence follows, so that the panel is as full as it will be with the real lesson."))
                    .append(",\"view\":\"").append(l[2]).append("\",\"focus\":\"").append(l[3]).append("\",\"stage\":\"").append(l[4]).append("\"}");
        }
        sb.append("],\"sources\":[\"https://example.invalid/fake-source\"]}}");
        return sb.toString();
    }

    @Override public String step() {
        double dt = paused ? 0 : DT * speed;
        if (dt > 0) {
            t += dt;
            // like the real simulation: the storm only moves through a stage in auto-play; a stage that was picked stays at its start (stage_frac 0)
            if (auto) stageT += dt;
            if (auto && stageT >= STAGE_SECONDS) { stage = (stage + 1) % IDS.length; stageT = 0; }
            double k = 1 - Math.exp(-dt / 1.1);
            for (int i = 0; i < s.length; i++) s[i] += (P[stage][i] - s[i]) * k;
            rotation += dt * (0.10 + 0.5 * s[0] / 140.0);
        }
        frameNo++;
        return frameJson();
    }

    private static double sm(double x) {
        x = Model.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    private double windAt(double r) {
        double vmax = s[0] * 0.5144, rmw = s[2];
        double v = r < rmw ? vmax * Math.pow(r / rmw, 1.1) : vmax * Math.pow(rmw / r, 0.62);
        return Math.max(0.3, v * (1 - 0.6 * sm((r - 350) / 250)));
    }

    private double[] band(int k) {
        double rmw = s[2], org = s[8];
        double r0 = rmw * (1.8 + 0.42 * k), r1 = Math.min(582, r0 * (3.4 + 0.5 * k));
        double inten = Model.clamp((org - 0.18 - 0.085 * k) * 4.2, 0, 1) * (s[12] > 0.5 ? 0.75 : 1) * (0.6 + 0.4 * Math.abs(Math.sin(k * 2.3 + 1)));
        return new double[]{k * 1.05 - 0.14, r0, r1, 0.25 + 0.02 * ((k * 5) % 4), 26 + 4 * ((k * 3) % 5), inten};
    }

    private String frameJson() {
        double vmaxKt = s[0], vmax = vmaxKt * 0.5144, pc = s[1], rmw = s[2], eye = s[3], top = s[5], rain = s[6], org = s[8], clar = s[9], warm = s[10];
        StringBuilder sb = new StringBuilder(24_000);
        sb.append("{\"type\":\"hurricane\",\"frame\":").append(frameNo).append(",\"t\":");
        Json.num2(sb, t);
        sb.append(",\"speed\":").append(speed).append(",\"paused\":").append(paused).append(",\"stage\":\"").append(IDS[stage]).append("\",\"stage_index\":").append(stage)
                .append(",\"stage_frac\":");
        Json.num2(sb, Math.min(1, stageT / STAGE_SECONDS));
        sb.append(",\"auto\":").append(auto).append(",\"state\":{\"vmax_ms\":");
        Json.num(sb, vmax);
        int cat = vmaxKt >= 137 ? 5 : vmaxKt >= 113 ? 4 : vmaxKt >= 96 ? 3 : vmaxKt >= 83 ? 2 : vmaxKt >= 64 ? 1 : vmaxKt >= 34 ? 0 : vmaxKt >= 26 ? -1 : -2;
        sb.append(",\"vmax_kt\":").append(Math.round(vmaxKt)).append(",\"pressure_hpa\":").append(Math.round(pc)).append(",\"category\":").append(cat);
        String[] keys = {"rmw_km", "eye_km", "r34_km", "cloud_top_km", "rain_mmh", "surge_m", "organization", "eye_clarity", "warm_core_c", "sst_c"};
        int[] idx = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        for (int k = 0; k < keys.length; k++) { sb.append(",\"").append(keys[k]).append("\":"); Json.num2(sb, s[idx[k]]); }
        sb.append(",\"over_land\":").append(s[12] > 0.5).append(",\"rotation\":");
        sb.append(Math.round(rotation * 10000) / 10000.0).append(",\"hemisphere\":\"N\"}");

        // radial profiles
        StringBuilder rk = new StringBuilder(), wv = new StringBuilder(), pv = new StringBuilder(), rv = new StringBuilder(), tv = new StringBuilder();
        for (int i = 0; i <= 60; i++) {
            double r = i * 10;
            if (i > 0) { rk.append(','); wv.append(','); pv.append(','); rv.append(','); tv.append(','); }
            rk.append((int) r);
            Json.num(wv, windAt(Math.max(1, r)));
            Json.num(pv, pc + (1012 - pc) * Math.exp(-rmw / Math.max(1, r)));
            double rr = rain * Math.exp(-Math.pow((r - rmw) / (rmw * 0.9 + 20), 2)) * (r < eye ? 1 - clar * 0.9 : 1);
            for (int b = 0; b < 6; b++) { double[] bd = band(b); rr += rain * 0.35 * bd[5] * Math.exp(-Math.pow((r - bd[1] * 1.5) / bd[4], 2)); }
            Json.num(rv, rr);
            Json.num(tv, Math.max(3, top * (0.38 + 0.62 * Math.exp(-r / 330))) * (r < eye ? 1 - clar * 0.8 : 1));
        }
        sb.append(",\"profile\":{\"r_km\":[").append(rk).append("],\"wind_ms\":[").append(wv).append("],\"pressure_hpa\":[").append(pv)
                .append("],\"rain_mmh\":[").append(rv).append("],\"cloud_top_km\":[").append(tv).append("]}");

        sb.append(",\"bands\":[");
        for (int b = 0; b < 6; b++) {
            double[] bd = band(b);
            if (b > 0) sb.append(',');
            sb.append("{\"theta\":"); Json.num2(sb, bd[0]);
            sb.append(",\"r0_km\":"); Json.num(sb, bd[1]);
            sb.append(",\"r1_km\":"); Json.num(sb, bd[2]);
            sb.append(",\"pitch\":"); Json.num2(sb, bd[3]);
            sb.append(",\"width_km\":"); Json.num(sb, bd[4]);
            sb.append(",\"intensity\":"); Json.num2(sb, bd[5]);
            sb.append('}');
        }
        sb.append(']');

        // the cross-section: on the first frame, then about six times a second
        if (frameNo == 1 || ++sectionClock >= 5) {
            sectionClock = 0;
            double dr = R / COLS, dz = TOP / ROWS, orgK = sm((org - 0.1) / 0.5);
            StringBuilder u = new StringBuilder(), w = new StringBuilder(), v = new StringBuilder(), c = new StringBuilder(), ta = new StringBuilder();
            for (int row = 0; row < ROWS; row++) {
                double z = (row + 0.5) * dz;
                for (int col = 0; col < COLS; col++) {
                    double r = (col + 0.5) * dr;
                    double re = rmw + 1.1 * z;                                   // the eyewall leans outward with height
                    double wall = Math.exp(-Math.pow((r - re) / (9 + rmw * 0.22), 2)) * (z < top ? 1 : Math.exp(-(z - top) * 2));
                    double anvil = r > re - 12 ? 0.8 * Math.exp(-(r - re) / (60 + 150 * orgK)) * Math.exp(-Math.pow((z - (top - 1.6)) / 1.3, 2)) : 0;
                    double cloud = (0.45 + 0.55 * orgK) * wall + orgK * anvil;
                    for (int b = 0; b < 6; b++) {
                        double[] bd = band(b);
                        cloud += 0.8 * bd[5] * Math.exp(-Math.pow((r - bd[1] * 1.5) / (bd[4] * 0.5), 2)) * (z < top * 0.62 ? 1 : Math.exp(-(z - top * 0.62) * 1.6));
                    }
                    // scattered, unorganised convection while the storm is weak
                    double blob = 0.5 + 0.5 * Math.sin(r * 0.09 + z * 1.7 + 1.3) * Math.cos(r * 0.041 - z * 0.9);
                    cloud += (1 - orgK) * 0.75 * blob * (r < 260 ? 1 - r / 260 : 0) * (z < top * 0.9 ? 1 : 0);
                    if (r < eye) cloud *= 1 - clar * (0.95 - 0.5 * sm((1.6 - z) / 1.6));
                    cloud = Model.clamp(cloud, 0, 1);
                    double inflow = -0.28 * vmax * (r / (r + 2.5 * rmw)) * Math.exp(-r / 420) * Math.exp(-z / 1.3) * (r > eye * 0.6 ? 1 : 0.2);
                    double outflow = 0.22 * vmax * sm((r - re) / 40) * Math.exp(-(r - re) / 380) * Math.exp(-Math.pow((z - (top - 1.8)) / 1.6, 2));
                    double up = (0.07 * vmax + 0.25) * Math.exp(-Math.pow((r - re) / (7 + rmw * 0.18), 2)) * Math.sin(Math.PI * Model.clamp(z / top, 0, 1));
                    double sink = r < eye ? -0.03 * vmax * clar * Math.sin(Math.PI * Model.clamp(z / top, 0, 1)) * (1 - r / Math.max(1, eye)) : 0;
                    if (row + col > 0) { u.append(','); w.append(','); v.append(','); c.append(','); ta.append(','); }
                    Json.num2(u, inflow + outflow);
                    Json.num2(w, up + sink);
                    Json.num2(v, windAt(r) * Math.max(0, 1 - 0.85 * z / top));
                    Json.num2(c, cloud);
                    Json.num2(ta, warm * Math.exp(-Math.pow(r / (2.2 * rmw + 25), 2)) * Math.exp(-Math.pow((z - 0.62 * top) / (0.3 * top), 2)));
                }
            }
            sb.append(",\"section\":{\"cols\":").append(COLS).append(",\"rows\":").append(ROWS).append(",\"dr_km\":").append(dr).append(",\"dz_km\":").append(dz)
                    .append(",\"u\":[").append(u).append("],\"w\":[").append(w).append("],\"v\":[").append(v).append("],\"cloud\":[").append(c)
                    .append("],\"temp_anom\":[").append(ta).append("]}");
        }
        sb.append(",\"events\":[]}");
        return sb.toString();
    }
}
