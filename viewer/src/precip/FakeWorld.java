package precip;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Built-in stand-in for {@code python sim/precip_sim.py --mode world} (protocol v2.3), so the World
 * Simulation screen can be developed, snapshotted and tested without Python. It speaks the same
 * messages: a {@code hello} with {@code world_map} (grid, calendar, one location per preset of
 * {@code assets/world/presets.json}, the region → preset table), then {@code world} frames with
 * smooth global fields, drifting pressure systems, seasonal tropical cyclones and a readout for
 * every location.
 * <p>
 * <b>Nothing in here is real data or a real model.</b> The fields are a hand-shaped zonal climate
 * (warm equator, cold poles, a rain band that follows the sun, westerlies and trades) with moving
 * blobs on top; the real climate comes from the simulation.
 */
final class FakeWorld implements FrameSource, Scripted {
    static final double DT = 1.0 / 30.0;
    static final int COLS = 72, ROWS = 36;
    private static final double DAY_S = 300, YEAR_DAYS = 8;
    private static final String[] NAMES_N = {"Alba", "Brisa", "Corin", "Dalia", "Emeric", "Fenna", "Galen", "Hedda"};
    private static final String[] NAMES_S = {"Ilka", "Joren", "Kaia", "Lorcan", "Mirel", "Nuri", "Odile", "Pavo"};
    /** Tropical cyclone basins: start lat, start lon, life in sim days, phase at t = 0. */
    private static final double[][] BASINS = {{12, 152, 5.0, 0.46}, {13, -38, 5.6, 0.30}, {14, -104, 4.4, 0.72}, {-12, 82, 5.2, 0.42}, {-13, 166, 4.8, 0.18}};

    private final float[] land;
    private final Preset.Loc[] locs;
    /** Year fraction and UTC hour, both unwrapped; {@code wt} = weather clock in sim days (never jumps). */
    private double yf, utc, wt, t, speed = 1;
    private double yfGlide, utcGlide;
    private boolean paused;
    private long frameNo;
    private int gridClock = 1000;
    private final List<double[]> lows = new ArrayList<>(), highs = new ArrayList<>(), cyclones = new ArrayList<>();
    private final ConcurrentLinkedQueue<String> commands = new ConcurrentLinkedQueue<>();
    private volatile boolean running;

    /** @param month the calendar month to start in (1.0 = 1 January); @param utcHour the UTC clock to start at */
    FakeWorld(File root, double month, double utcHour) {
        float[] l;
        try { l = WorldMap.get(root).landFraction(COLS, ROWS); } catch (RuntimeException ex) { l = new float[COLS * ROWS]; }
        land = l;
        List<Preset.Loc> on = new ArrayList<>();
        for (Preset.Loc p : Preset.LOCS) if (p.onMap()) on.add(p);
        locs = on.toArray(new Preset.Loc[0]);
        yf = (Model.clamp(month, 1, 12.999) - 1) / 12.0;
        utc = ((utcHour % 24) + 24) % 24;
    }

    FakeWorld(File root) { this(root, 4.5, 13.0); }

    @Override public String describe() { return "built-in fake world"; }

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
        }, "fake-world");
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
            case "speed" -> speed = Model.clamp(Json.num(m, "value", 1), 0.25, 64);
            case "season" -> {
                double target = (Model.clamp(Json.num(m, "month", 1), 1, 12.999) - 1) / 12.0, cur = yf + yfGlide;
                double d = target - (cur - Math.floor(cur));
                if (d > 0.5) d -= 1;
                if (d < -0.5) d += 1;
                yfGlide += d;
            }
            case "hour" -> {
                double target = ((Json.num(m, "value", 12) % 24) + 24) % 24, cur = utc + utcGlide;
                double d = target - (cur - Math.floor(cur / 24) * 24);
                if (d > 12) d -= 24;
                if (d < -12) d += 24;
                utcGlide += d;
            }
            case "quit" -> running = false;
            default -> { }
        }
    }

    @Override public String hello() {
        StringBuilder sb = new StringBuilder(8000);
        sb.append("{\"type\":\"hello\",\"version\":2,\"seed\":1,\"loaded\":false,\"t\":0,\"mode\":\"world\",")
                .append("\"world\":{\"w\":1920,\"h\":1080,\"ground_y\":1000,\"terrain_kind\":\"flat\",\"terrain\":{\"step\":960,\"y\":[1000,1000,1000]}},")
                .append("\"settlements\":[],\"objects\":[],\"world_map\":{\"grid\":{\"cols\":").append(COLS).append(",\"rows\":").append(ROWS)
                .append(",\"dlon\":5.0,\"dlat\":5.0},\"year_days\":").append((int) YEAR_DAYS).append(",\"day_length_s\":").append((int) DAY_S).append(",\"locations\":[");
        Map<String, String> regions = new LinkedHashMap<>();
        for (int k = 0; k < locs.length; k++) {
            Preset.Loc l = locs[k];
            if (k > 0) sb.append(',');
            sb.append("{\"id\":").append(Json.quote(l.id)).append(",\"name\":").append(Json.quote(l.name)).append(",\"lat\":").append(l.lat).append(",\"lon\":").append(l.lon)
                    .append(",\"region\":").append(Json.quote(l.region)).append(",\"country\":").append(Json.quote(l.country)).append(",\"kind\":").append(Json.quote(l.kind))
                    .append(",\"summary\":").append(Json.quote(l.summary)).append(",\"station\":").append(Json.quote(l.station));
            if (!Double.isNaN(l.annualTempC)) sb.append(",\"annual_temp_c\":").append(l.annualTempC);
            if (!Double.isNaN(l.annualPrecipMm)) sb.append(",\"annual_precip_mm\":").append(l.annualPrecipMm);
            sb.append(",\"highlights\":[");
            for (int q = 0; q < l.highlights.length; q++) { if (q > 0) sb.append(','); sb.append(Json.quote(l.highlights[q])); }
            sb.append("]}");
            if (!l.region.isEmpty()) regions.putIfAbsent(l.region, l.id);
        }
        sb.append("],\"regions\":{");
        int n = 0;
        for (Map.Entry<String, String> e : regions.entrySet()) {
            if (n++ > 0) sb.append(',');
            sb.append(Json.quote(e.getKey())).append(':').append(Json.quote(e.getValue()));
        }
        sb.append("},\"sources\":[\"https://example.invalid/fake-world\"]}}");
        return sb.toString();
    }

    @Override public String step() {
        double dt = paused ? 0 : DT * speed;
        if (dt > 0) {
            t += dt;
            wt += dt / DAY_S;
            utc += dt * 24 / DAY_S;
            yf += dt / (DAY_S * YEAR_DAYS);
        }
        // a season / hour jump glides there in real time, also while paused
        double k = 1 - Math.exp(-DT / 0.30);
        if (Math.abs(yfGlide) > 1e-6) { double mv = yfGlide * k; yf += mv; yfGlide -= mv; } else if (yfGlide != 0) { yf += yfGlide; yfGlide = 0; }
        if (Math.abs(utcGlide) > 1e-4) { double mv = utcGlide * k; utc += mv; utcGlide -= mv; } else if (utcGlide != 0) { utc += utcGlide; utcGlide = 0; }
        frameNo++;
        return frameJson();
    }

    // ------------------------------------------------------------------ the pretend climate

    private static double g(double x) { return Math.exp(-x * x); }

    private static double sm(double x) {
        x = Model.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    private static double wrap(double d) {
        d = (d + 180) % 360;
        if (d < 0) d += 360;
        return d - 180;
    }

    private double yearFrac() { return yf - Math.floor(yf); }

    private double utcHour() { return utc - Math.floor(utc / 24) * 24; }

    private double decl() { return 23.44 * Math.sin(2 * Math.PI * (yearFrac() - 0.2164)); }

    /** Seasonal temperature phase (lags the sun by about a month): +1 = northern midsummer. */
    private double seasonN() { return Math.sin(2 * Math.PI * (yearFrac() - 0.2164 - 0.085)); }

    private void buildSystems() {
        lows.clear(); highs.clear(); cyclones.clear();
        double sN = seasonN();
        for (int k = 0; k < 6; k++) {
            int h = k < 3 ? 1 : -1;
            double lon = wrap(-150 + (k % 3) * 120 + (h < 0 ? 55 : 0) + 26 * wt * (1 + 0.07 * k));
            double lat = h * (52 + 7 * Math.sin(wt * 1.3 + k * 2.1));
            double st = (0.48 + 0.34 * Math.pow(Math.sin(wt * 0.9 + k * 1.7), 2)) * (1 - 0.28 * h * sN);
            lows.add(new double[]{k + 1, lat, lon, Model.clamp(st, 0.1, 1), 10 + 4 * st, h});
        }
        double[][] hs = {{35, -32, 1}, {34, -148, 1}, {-30, -92, -1}, {-29, -8, -1}, {-33, 86, -1}};
        for (int k = 0; k < hs.length; k++) {
            double lat = hs[k][0] + 0.22 * decl() + 3 * Math.sin(wt * 0.7 + k), lon = wrap(hs[k][1] + 7 * Math.sin(wt * 0.5 + k * 1.9));
            highs.add(new double[]{20 + k, lat, lon, 0.6 + 0.18 * Math.sin(wt * 0.8 + k * 2.3), 15, hs[k][2]});
        }
        double sib = sm((-sN - 0.25) / 0.6);
        if (sib > 0.08) highs.add(new double[]{26, 50 + 2 * Math.sin(wt), 98 + 5 * Math.sin(wt * 0.6), 0.9 * sib, 20, 1});
        for (int k = 0; k < BASINS.length; k++) {
            double[] b = BASINS[k];
            int h = b[0] > 0 ? 1 : -1;
            // tropical cyclone season: late summer and autumn of the basin's hemisphere
            double seas = sm((h * Math.sin(2 * Math.PI * (yearFrac() - 0.2164 - 0.21)) + 0.15) / 0.75);
            double cyc = wt / b[2] + b[3], ph = cyc - Math.floor(cyc), a = ph * b[2];
            double st = seas * Math.pow(Math.sin(Math.PI * ph), 0.8);
            if (st < 0.08) continue;
            double lat = h * (Math.abs(b[0]) + 1.3 * a + 0.55 * a * a), lon = wrap(b[1] - 8.5 * a + 2.0 * a * a);
            long cycle = (long) Math.floor(cyc);
            cyclones.add(new double[]{100 + k * 50 + Math.floorMod(cycle, 50), lat, lon, Model.clamp(st, 0, 1), 6 + 3 * st, h, Math.floorMod(cycle * 3 + k, 8)});
        }
    }

    /** Smooth moving noise, periodic in longitude, about −1..1. */
    private double noise(double lat, double lon, double ph) {
        double x = Math.toRadians(lon), y = Math.toRadians(lat);
        return 0.34 * Math.sin(3 * x + 2.2 * y + 0.9 * wt + ph) * Math.cos(2.6 * y - 0.4 * wt)
                + 0.28 * Math.sin(5 * x - 3.1 * y - 1.3 * wt + 1.7 * ph) + 0.22 * Math.sin(8 * x + 4.3 * y + 2.1 * wt + 0.6 * ph) * Math.sin(3.7 * y + ph)
                + 0.16 * Math.sin(13 * x - 6.1 * y + 3.0 * wt + 2.3 * ph);
    }

    /** One sample of every field: {temp, cloud, precip, u, v, pressure, snow}. {@code L} = land fraction. */
    private void sample(double lat, double lon, double L, double[] out) {
        double decl = decl(), sN = seasonN(), sinp = Math.sin(Math.toRadians(lat)), cosp = Math.cos(Math.toRadians(lat)), al = Math.abs(lat);
        double hs = lat >= 0 ? 1 : -1, seasHere = sN * hs;                       // +1 = this hemisphere's summer
        // temperature: zonal mean, the thermal equator leaning toward the sun, continents swinging harder
        double so = Math.sin(Math.toRadians(lat - 0.22 * decl)), sl = Math.sin(Math.toRadians(lat - 0.45 * decl));
        double to = 28 - 26 * so * so - 22 * so * so * so * so + 2.0 * seasHere * Math.min(1, al / 30);
        double tl = 28.5 - 26 * sl * sl - 22 * sl * sl * sl * sl + (3 + 19 * sinp * sinp) * seasHere * Math.min(1, al / 22);
        tl -= 15 * g((lat - 33) / 6.5) * g(wrap(lon - 90) / 14);              // a high plateau
        tl -= 16 * sm((-lat - 62) / 8);                                        // the southern ice sheet
        tl -= 13 * g((lat - 73) / 9) * g(wrap(lon + 40) / 16);               // the northern one
        double tBase = L * tl + (1 - L) * to + 2.2 * noise(lat, lon, 0.3);
        double localHour = utcHour() + lon / 15;
        double diurnal = (0.5 + 5.0 * L) * (0.35 + 0.65 * cosp) * Math.cos(2 * Math.PI * (localHour - 15) / 24);
        // pressure and wind: belts, then the systems
        double p = 1013 + 7 * g((al - 32) / 11) - 9 * g((al - 62) / 11) - 3 * g(lat / 9) + 4 * g((al - 88) / 10) - 4.5 * L * seasHere * Math.min(1, al / 25);
        double itcz = 4 + (0.50 + 0.28 * L) * decl;
        double u = -6.5 * g((al - 14) / 9) + 11 * g((al - 47) / 13) - 3 * g((al - 78) / 8), v = -Math.signum(lat - itcz) * 2.4 * g((lat - itcz) / 14);
        double cloud = 0.32 + 0.50 * g((lat - itcz) / 7.5) + 0.34 * g((al - 55) / 14) - 0.17 * g((al - 25) / 9) - 0.18 * L * g((al - 24) / 8) + 0.36 * noise(lat, lon, 2.1);
        double wet = 0;
        for (double[] s : lows) {
            double dx = wrap(lon - s[2]) * cosp, dy = lat - s[1], r = Math.hypot(dx, dy), q = r / s[4];
            if (q > 3.2) continue;
            double gg = g(q), th = Math.atan2(dy, dx), vt = 15 * s[3] * q * Math.exp(1 - q) * 0.9;
            p -= 22 * s[3] * gg;
            u += -s[5] * vt * dy / Math.max(0.5, r);
            v += s[5] * vt * dx / Math.max(0.5, r);
            // a comma of cloud: the head near the centre and a tail trailing toward the equator and the west
            double arm = 0.5 + 0.5 * Math.cos(th * s[5] - 1.9 * q + 0.6);
            double c = s[3] * (0.75 * g(q * 1.5) + 0.75 * g((q - 1.15) / 0.75) * arm);
            cloud += 0.62 * c;
            wet += 0.8 * c;
            tBase -= 2.0 * s[3] * gg;
        }
        for (double[] s : highs) {
            double dx = wrap(lon - s[2]) * cosp, dy = lat - s[1], r = Math.hypot(dx, dy), q = r / s[4];
            if (q > 3) continue;
            double gg = g(q), vt = 6 * s[3] * q * Math.exp(1 - q);
            p += 11 * s[3] * gg;
            u += s[5] * vt * dy / Math.max(0.5, r);
            v += -s[5] * vt * dx / Math.max(0.5, r);
            cloud -= 0.34 * s[3] * gg;
        }
        for (double[] s : cyclones) {
            double dx = wrap(lon - s[2]) * cosp, dy = lat - s[1], r = Math.hypot(dx, dy), q = r / s[4];
            if (q > 3.5) continue;
            double gg = g(q / 0.8), vt = 34 * s[3] * (q * 2.4) * Math.exp(1 - q * 2.4);
            p -= 40 * s[3] * g(q / 0.55);
            u += -s[5] * vt * dy / Math.max(0.3, r);
            v += s[5] * vt * dx / Math.max(0.3, r);
            cloud += 1.1 * s[3] * gg;
            wet += 1.4 * s[3] * gg;
        }
        cloud = Model.clamp(cloud, 0, 1);
        double precip = Model.clamp((cloud - 0.60) * 2.3 + 0.55 * wet * sm((cloud - 0.5) / 0.3) * 0.6, 0, 1);
        double snow = L * sm((1.5 - tBase) / 7) + (1 - L) * 0.85 * sm((-3.5 - tBase) / 6);
        out[0] = tBase + diurnal;
        out[1] = cloud;
        out[2] = precip;
        out[3] = u;
        out[4] = v;
        out[5] = p;
        out[6] = Model.clamp(snow, 0, 1);
    }

    private double landAt(double lat, double lon) {
        int c = (int) Math.floor((wrap(lon) + 180) / 5.0), r = (int) Math.floor((90 - lat) / 5.0);
        return land[Math.max(0, Math.min(ROWS - 1, r)) * COLS + Math.max(0, Math.min(COLS - 1, c))];
    }

    private String frameJson() {
        buildSystems();
        double decl = decl(), utcH = utcHour(), month = 1 + 12 * yearFrac();
        StringBuilder sb = new StringBuilder(110_000);
        sb.append("{\"type\":\"world\",\"frame\":").append(frameNo).append(",\"t\":");
        Json.num2(sb, t);
        sb.append(",\"speed\":").append(speed).append(",\"paused\":").append(paused).append(",\"year_frac\":").append(Math.round(yearFrac() * 100000) / 100000.0)
                .append(",\"month\":").append(Math.round(month * 1000) / 1000.0).append(",\"day\":").append((long) Math.floor(wt)).append(",\"utc_hour\":").append(Math.round(utcH * 1000) / 1000.0)
                .append(",\"sun\":{\"lat\":").append(Math.round(decl * 100) / 100.0).append(",\"lon\":").append(Math.round(wrap((12 - utcH) * 15) * 100) / 100.0).append('}');

        double[] s = new double[7];
        double meanT = 0, meanC = 0, wsum = 0;
        boolean grid = frameNo == 1 || ++gridClock >= 8;
        if (grid) {
            gridClock = 0;
            StringBuilder[] a = new StringBuilder[7];
            for (int k = 0; k < 7; k++) a[k] = new StringBuilder(COLS * ROWS * 6);
            for (int r = 0; r < ROWS; r++) {
                double lat = 90 - (r + 0.5) * 5.0, wgt = Math.cos(Math.toRadians(lat));
                for (int c = 0; c < COLS; c++) {
                    double lon = -180 + (c + 0.5) * 5.0;
                    sample(lat, lon, land[r * COLS + c], s);
                    meanT += s[0] * wgt; meanC += s[1] * wgt; wsum += wgt;
                    if (r + c > 0) for (StringBuilder b : a) b.append(',');
                    Json.num(a[0], s[0]); Json.num2(a[1], s[1]); Json.num2(a[2], s[2]); Json.num(a[3], s[3]); Json.num(a[4], s[4]); Json.num(a[5], s[5]); Json.num2(a[6], s[6]);
                }
            }
            lastMeanT = meanT / wsum; lastMeanC = meanC / wsum;
            sb.append(",\"grid\":{\"temp_c\":[").append(a[0]).append("],\"cloud\":[").append(a[1]).append("],\"precip\":[").append(a[2]).append("],\"u\":[").append(a[3])
                    .append("],\"v\":[").append(a[4]).append("],\"pressure_hpa\":[").append(a[5]).append("],\"snow\":[").append(a[6]).append("]}");
        }

        sb.append(",\"systems\":[");
        int n = 0;
        for (double[] y : lows) {
            n = sys(sb, n, (long) y[0], "low", y[1], y[2], y[3], y[4], null);
            if (y[3] > 0.6) n = sys(sb, n, (long) y[0] + 300, "front", y[1] - y[5] * 9, wrap(y[2] + 3), y[3], y[4] * 1.2, null);
        }
        for (double[] y : highs) n = sys(sb, n, (long) y[0], "high", y[1], y[2], y[3], y[4], null);
        for (double[] y : cyclones) n = sys(sb, n, (long) y[0], "cyclone", y[1], y[2], y[3], y[4], (y[5] > 0 ? NAMES_N : NAMES_S)[(int) y[6]]);
        // spring afternoons over the southern plains
        double plainsHour = ((utcH - 97.5 / 15) % 24 + 24) % 24;
        double outbreak = sm((Math.cos(2 * Math.PI * (yearFrac() - 0.37)) - 0.45) / 0.45) * sm((Math.cos(2 * Math.PI * (plainsHour - 17) / 24) - 0.2) / 0.6);
        if (outbreak > 0.12) n = sys(sb, n, 900, "tornado_outbreak", 36.5, -97.5, outbreak, 5, null);
        sb.append("],\"locations\":[");
        for (int k = 0; k < locs.length; k++) {
            Preset.Loc l = locs[k];
            sample(l.lat, l.lon, Math.max(0.6, landAt(l.lat, l.lon)), s);
            double lh = ((utcH + l.lon / 15) % 24 + 24) % 24;
            double temp = s[0];
            double normal = WorldModel.monthly(l.monthlyTempC, month);
            if (!Double.isNaN(normal)) {
                // anchored to the location's own monthly figures; the field only adds the day cycle and the passing weather
                double range = 1.5 + 3 * (1 - s[1]);
                temp = normal + range * Math.cos(2 * Math.PI * (lh - 15) / 24) + 1.6 * noise(l.lat, l.lon, 0.3) - 2.0 * s[2];
            }
            double wind = Math.hypot(s[3], s[4]);
            String weather = s[2] > 0.22 && temp <= 0.5 ? "snow" : s[2] > 0.62 && temp > 16 ? "storm" : s[2] > 0.16 ? "rain"
                    : s[1] > 0.62 ? "cloudy" : s[1] > 0.40 && wind < 3.2 && (lh < 8.5 || lh > 21) ? "fog" : "clear";
            if (k > 0) sb.append(',');
            sb.append("{\"id\":").append(Json.quote(l.id)).append(",\"temp_c\":");
            Json.num(sb, temp);
            sb.append(",\"cloud\":"); Json.num2(sb, s[1]);
            sb.append(",\"precip\":"); Json.num2(sb, s[2]);
            sb.append(",\"wind_ms\":"); Json.num(sb, wind);
            sb.append(",\"weather\":\"").append(weather).append("\",\"local_hour\":");
            Json.num2(sb, lh);
            sb.append(",\"month\":").append(Math.round(month * 100) / 100.0).append('}');
        }
        sb.append("],\"stats\":{\"mean_temp_c\":");
        Json.num(sb, lastMeanT);
        sb.append(",\"cloud_cover\":");
        Json.num2(sb, lastMeanC);
        sb.append(",\"storms\":").append(cyclones.size()).append("},\"events\":[");
        // one line per newly named storm
        boolean first = true;
        for (double[] y : cyclones) {
            long id = (long) y[0];
            if (announced.add(id)) {
                if (!first) sb.append(',');
                first = false;
                sb.append(Json.quote("day " + (long) Math.floor(wt) + ": tropical cyclone " + (y[5] > 0 ? NAMES_N : NAMES_S)[(int) y[6]] + " formed (fake)"));
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private double lastMeanT = 14, lastMeanC = 0.6;
    private final java.util.Set<Long> announced = new java.util.HashSet<>();

    private static int sys(StringBuilder sb, int n, long id, String kind, double lat, double lon, double strength, double radius, String name) {
        if (n > 0) sb.append(',');
        sb.append("{\"id\":").append(id).append(",\"kind\":\"").append(kind).append("\",\"lat\":");
        Json.num2(sb, lat);
        sb.append(",\"lon\":");
        Json.num2(sb, lon);
        sb.append(",\"strength\":");
        Json.num2(sb, strength);
        sb.append(",\"radius_deg\":");
        Json.num(sb, radius);
        sb.append(",\"name\":").append(name == null ? "null" : Json.quote(name)).append('}');
        return n + 1;
    }
}
