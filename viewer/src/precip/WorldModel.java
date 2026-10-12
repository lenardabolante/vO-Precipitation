package precip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed view of the world mode of protocol v2.3: the static part of {@code hello.world_map} (grid,
 * calendar, the location dots, which region opens which preset) and the latest {@code world} frame.
 * <p>
 * Parsed from the raw JSON so that {@link Model} stays as it is. Instances live in
 * {@link ViewerState#world} and are guarded by the ViewerState lock; a {@link Frame} is immutable
 * once published, so painters take the reference under the lock and read it without one.
 */
final class WorldModel {
    static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};

    /** One map dot: a preset of {@code sim/presets.py}. */
    static final class Loc {
        String id = "", name = "", region = "", country = "", kind = "place", summary = "", station = "";
        double lat, lon, annualTempC = Double.NaN, annualPrecipMm = Double.NaN;
        String[] highlights = new String[0];
        /** Twelve monthly means from {@code assets/world/presets.json} (empty when that file does not know the preset). */
        double[] monthlyTempC = new double[0];
    }

    /** The live readout of one location. */
    static final class Now {
        String id = "", weather = "clear";
        double tempC = Double.NaN, cloud, precip, windMs = Double.NaN, localHour = Double.NaN, month = Double.NaN;
    }

    static final class Sys {
        long id;
        String kind = "low", name = "";
        double lat, lon, strength, radiusDeg = 8;
    }

    /** One {@code world} frame, complete: the grids are carried over from the last frame that had them. */
    static final class Frame {
        long frame, recvNanos;
        double t, speed = 1, yearFrac, month = 1, utcHour, sunLat, sunLon;
        long day;
        boolean paused;
        int cols, rows;
        /** Row-major, row 0 = the northernmost band; empty until the first grid arrived. */
        float[] temp = new float[0], cloud = new float[0], precip = new float[0], u = new float[0], v = new float[0], pressure = new float[0], snow = new float[0];
        /** Changes whenever new grids arrived; {@code gridNanos} is when. */
        long gridVersion, gridNanos;
        /** The grids before this one and how long they were on show (for a glide between two grids); null on the first. */
        Frame prevGrid;
        double gridInterval = 0.25;
        Sys[] systems = new Sys[0];
        final Map<String, Now> now = new HashMap<>();
        double meanTempC = Double.NaN, cloudCover = Double.NaN;
        int storms;

        boolean hasGrid() { return cols > 0 && rows > 0 && temp.length >= cols * rows; }
    }

    int cols = 72, rows = 36;
    double dlon = 5, dlat = 5, yearDays = 8, dayLengthS = 300;
    final List<Loc> locations = new ArrayList<>();
    /** Region code → preset id. */
    final Map<String, String> regions = new HashMap<>();
    final List<String> sources = new ArrayList<>();
    /** The latest frame; null until the first one arrives. */
    Frame cur;
    long framesSeen;

    WorldModel(Map<String, Object> h) {
        Map<String, Object> g = Json.obj(h, "grid");
        cols = Math.max(2, (int) Json.num(g, "cols", 72));
        rows = Math.max(2, (int) Json.num(g, "rows", 36));
        dlon = Json.num(g, "dlon", 360.0 / cols);
        dlat = Json.num(g, "dlat", 180.0 / rows);
        yearDays = Math.max(1, Json.num(h, "year_days", 8));
        dayLengthS = Math.max(1, Json.num(h, "day_length_s", 300));
        List<Object> ls = Json.arr(h, "locations");
        if (ls != null) for (Object o : ls) {
            Map<String, Object> m = Json.obj(o);
            if (m == null) continue;
            Loc l = new Loc();
            l.id = Json.str(m, "id", "");
            l.lat = Json.num(m, "lat", Double.NaN);
            l.lon = Json.num(m, "lon", Double.NaN);
            if (l.id.isEmpty() || Double.isNaN(l.lat) || Double.isNaN(l.lon)) continue;
            l.name = Json.str(m, "name", Preset.nameOf(l.id));
            l.region = Json.str(m, "region", "");
            l.country = Json.str(m, "country", "");
            l.kind = Json.str(m, "kind", "place");
            l.summary = Json.str(m, "summary", "");
            l.station = Json.str(m, "station", "");
            l.annualTempC = Json.num(m, "annual_temp_c", Double.NaN);
            l.annualPrecipMm = Json.num(m, "annual_precip_mm", Double.NaN);
            List<Object> hl = Json.arr(m, "highlights");
            if (hl != null) {
                List<String> hs = new ArrayList<>();
                for (Object x : hl) if (x != null) hs.add(String.valueOf(x));
                l.highlights = hs.toArray(new String[0]);
            }
            // the monthly curve: from the hello if the simulation sends one, else from the viewer's copy of the presets
            double[] mt = Model.nums(Json.arr(m, "monthly_temp_c"));
            Preset.Loc pl = Preset.loc(l.id);
            if (mt.length == 12) l.monthlyTempC = mt;
            else if (pl != null && pl.monthlyTempC.length == 12) l.monthlyTempC = pl.monthlyTempC;
            locations.add(l);
        }
        Map<String, Object> rg = Json.obj(h, "regions");
        if (rg != null) for (Map.Entry<String, Object> e : rg.entrySet()) if (e.getValue() != null) regions.put(e.getKey(), String.valueOf(e.getValue()));
        List<Object> ss = Json.arr(h, "sources");
        if (ss != null) for (Object o : ss) if (o != null) sources.add(String.valueOf(o));
    }

    Loc loc(String id) {
        for (Loc l : locations) if (l.id.equals(id)) return l;
        return null;
    }

    /** The location a click on this region opens, or null. */
    Loc locOfRegion(String code) {
        String id = code == null ? null : regions.get(code);
        return id == null ? null : loc(id);
    }

    /** The location nearest to a point (great circle), or null when there are none. */
    Loc nearest(double lat, double lon) {
        Loc best = null;
        double bd = Double.MAX_VALUE;
        for (Loc l : locations) {
            double d = WorldMap.distanceKm(lat, lon, l.lat, l.lon);
            if (d < bd) { bd = d; best = l; }
        }
        return best;
    }

    /** Apply one {@code world} message. Caller holds the ViewerState lock. */
    void accept(Map<String, Object> m, long recvNanos) {
        Frame p = cur, f = new Frame();
        f.recvNanos = recvNanos;
        f.frame = (long) Json.num(m, "frame", 0);
        f.t = Json.num(m, "t", 0);
        f.speed = Json.num(m, "speed", 1);
        f.paused = Json.bool(m, "paused", false);
        f.yearFrac = Json.num(m, "year_frac", 0);
        f.month = Json.num(m, "month", 1 + 12 * f.yearFrac);
        f.day = (long) Json.num(m, "day", 0);
        f.utcHour = Json.num(m, "utc_hour", 0);
        Map<String, Object> sun = Json.obj(m, "sun");
        f.sunLat = Json.num(sun, "lat", 0);
        f.sunLon = Json.num(sun, "lon", (12 - f.utcHour) * 15);

        Map<String, Object> g = Json.obj(m, "grid");
        float[] temp = g == null ? null : grid(Json.arr(g, "temp_c"));
        if (temp != null) {
            f.cols = cols; f.rows = rows;
            f.temp = temp;
            f.cloud = orZero(grid(Json.arr(g, "cloud")));
            f.precip = orZero(grid(Json.arr(g, "precip")));
            f.u = orZero(grid(Json.arr(g, "u")));
            f.v = orZero(grid(Json.arr(g, "v")));
            f.pressure = grid(Json.arr(g, "pressure_hpa"));
            if (f.pressure == null) { f.pressure = new float[cols * rows]; java.util.Arrays.fill(f.pressure, 1013f); }
            f.snow = orZero(grid(Json.arr(g, "snow")));
            f.gridVersion = (p != null ? p.gridVersion : 0) + 1;
            f.gridNanos = recvNanos;
            if (p != null && p.hasGrid()) {
                f.gridInterval = Model.clamp((recvNanos - p.gridNanos) / 1e9, 0.05, 1.0);
                // keep one grid back only: drop the chain behind it
                Frame keep = new Frame();
                keep.cols = p.cols; keep.rows = p.rows;
                keep.temp = p.temp; keep.cloud = p.cloud; keep.precip = p.precip; keep.u = p.u; keep.v = p.v; keep.pressure = p.pressure; keep.snow = p.snow;
                keep.gridVersion = p.gridVersion;
                f.prevGrid = keep;
            }
        } else if (p != null) {
            f.cols = p.cols; f.rows = p.rows;
            f.temp = p.temp; f.cloud = p.cloud; f.precip = p.precip; f.u = p.u; f.v = p.v; f.pressure = p.pressure; f.snow = p.snow;
            f.gridVersion = p.gridVersion; f.gridNanos = p.gridNanos; f.prevGrid = p.prevGrid; f.gridInterval = p.gridInterval;
        }

        List<Object> sl = Json.arr(m, "systems");
        if (sl != null) {
            List<Sys> out = new ArrayList<>(sl.size());
            for (Object o : sl) {
                Map<String, Object> s = Json.obj(o);
                if (s == null) continue;
                Sys y = new Sys();
                y.id = (long) Json.num(s, "id", 0);
                y.kind = Json.str(s, "kind", "low");
                y.lat = Json.num(s, "lat", 0);
                y.lon = Json.num(s, "lon", 0);
                y.strength = Model.clamp(Json.num(s, "strength", 0.5), 0, 1);
                y.radiusDeg = Math.max(1, Json.num(s, "radius_deg", 8));
                Object nm = s.get("name");
                y.name = nm == null ? "" : String.valueOf(nm);
                out.add(y);
            }
            f.systems = out.toArray(new Sys[0]);
        } else if (p != null) f.systems = p.systems;

        List<Object> ll = Json.arr(m, "locations");
        if (ll != null) {
            for (Object o : ll) {
                Map<String, Object> a = Json.obj(o);
                if (a == null) continue;
                Now n = new Now();
                n.id = Json.str(a, "id", "");
                n.tempC = Json.num(a, "temp_c", Double.NaN);
                n.cloud = Json.num(a, "cloud", 0);
                n.precip = Json.num(a, "precip", 0);
                n.windMs = Json.num(a, "wind_ms", Double.NaN);
                n.weather = Json.str(a, "weather", "clear");
                n.localHour = Json.num(a, "local_hour", Double.NaN);
                n.month = Json.num(a, "month", f.month);
                f.now.put(n.id, n);
            }
        } else if (p != null) f.now.putAll(p.now);

        Map<String, Object> st = Json.obj(m, "stats");
        f.meanTempC = Json.num(st, "mean_temp_c", p != null ? p.meanTempC : Double.NaN);
        f.cloudCover = Json.num(st, "cloud_cover", p != null ? p.cloudCover : Double.NaN);
        f.storms = (int) Json.num(st, "storms", p != null ? p.storms : 0);
        cur = f;
        framesSeen++;
    }

    /** A grid of exactly cols*rows values, or null when the array is missing or of the wrong size. */
    private float[] grid(List<Object> a) {
        if (a == null || a.size() != cols * rows) return null;
        float[] d = new float[a.size()];
        for (int k = 0; k < d.length; k++) d[k] = (float) Json.num(a.get(k), 0);
        return d;
    }

    private float[] orZero(float[] a) { return a != null ? a : new float[cols * rows]; }

    // ------------------------------------------------------------------ calendar helpers (pure formatting)

    /** "mid April" for month 4.5 (1.0 = 1 January). */
    static String monthText(double month) {
        if (Double.isNaN(month)) return "";
        double m = ((month - 1) % 12 + 12) % 12;
        int idx = (int) m;
        double part = m - idx;
        return (part < 1 / 3.0 ? "early " : part < 2 / 3.0 ? "mid " : "late ") + MONTHS[idx];
    }

    static String monthName(double month) {
        if (Double.isNaN(month)) return "";
        return MONTHS[(int) (((month - 1) % 12 + 12) % 12)];
    }

    /** Meteorological season at a latitude. */
    static String season(double month, double lat) {
        int m = (int) (((month - 1) % 12 + 12) % 12) + 1;
        int k = m == 12 || m <= 2 ? 0 : m <= 5 ? 1 : m <= 8 ? 2 : 3;
        if (lat < 0) k = (k + 2) % 4;
        return new String[]{"winter", "spring", "summer", "autumn"}[k];
    }

    /** A value rounded to a whole number, as text; never "-0" (−0.4 °C reads "0", not "-0"). */
    static String whole(double v) { return String.valueOf(Math.round(v)); }

    /** "14:05" for 14.08. */
    static String clock(double hour) {
        if (Double.isNaN(hour)) return "--:--";
        double h = ((hour % 24) + 24) % 24;
        int mins = (int) Math.floor(h * 60 + 1e-6);
        return String.format("%02d:%02d", (mins / 60) % 24, mins % 60);
    }

    /** The monthly curve at a fractional month (linear between mid-month values, wrapping the year). */
    static double monthly(double[] m12, double month) {
        if (m12 == null || m12.length != 12) return Double.NaN;
        double x = ((month - 1.5) % 12 + 12) % 12;
        int a = (int) x, b = (a + 1) % 12;
        return m12[a] + (m12[b] - m12[a]) * (x - a);
    }
}
