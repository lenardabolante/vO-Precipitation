package precip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Typed view of the hurricane mode of protocol v2.2: the static part of {@code hello.hurricane}
 * (domain, the ten stages, the guided tour) and the latest {@code hurricane} frame.
 * <p>
 * Nothing here knows any meteorology: every name, number and sentence the hurricane screen shows
 * is read from these messages. Instances live in {@link ViewerState#hurricane} and are guarded by
 * the ViewerState lock; a {@link Frame} is immutable once published, so painters take the
 * reference under the lock and read it without one.
 */
final class HurricaneModel {
    static final class Stage {
        String id = "", name = "", summary = "";
        /** 1-minute sustained wind range in knots; {@code windMax} is NaN when the stage is open-ended. */
        double windMin = Double.NaN, windMax = Double.NaN, pressure = Double.NaN;
        int category;

        /** "64–82 kt", "137+ kt". */
        String windRange() {
            if (Double.isNaN(windMin)) return "";
            if (Double.isNaN(windMax)) return String.format("%.0f+ kt", windMin);
            return String.format("%.0f–%.0f kt", windMin, windMax);
        }
    }

    static final class Chapter {
        String id = "", title = "", text = "", view = "both", focus = "none", stage = "";
    }

    static final class Band {
        double theta, r0, r1, pitch, width, intensity;
    }

    /** One {@code hurricane} frame, complete: the cross-section is carried over from the last frame that had one. */
    static final class Frame {
        long frame, recvNanos;
        double t, speed = 1, stageFrac;
        boolean paused, auto, overLand;
        String stage = "", hemisphere = "N";
        int stageIndex, category;
        double vmaxMs, vmaxKt, pressure = Double.NaN, rmw, eye, r34, sst = Double.NaN, warmCore, cloudTop, rain, surge, organization, eyeClarity, rotation;
        // radial profiles on the shared r axis
        double[] r = new double[0], wind = new double[0], pres = new double[0], rainR = new double[0], top = new double[0];
        Band[] bands = new Band[0];
        // vertical cross-section, row-major, row 0 = surface
        int cols, rows;
        double dr = 12.5, dz = 1.125;
        double[] u = new double[0], w = new double[0], v = new double[0], cloud = new double[0], temp = new double[0];
        /** Changes whenever a new cross-section arrived. */
        long sectionVersion;

        boolean hasSection() { return cols > 0 && rows > 0 && cloud.length >= cols * rows; }

        /** A radial profile value at radius r (km), linear between samples, clamped at the ends. */
        double profile(double[] a, double rk) {
            int n = Math.min(r.length, a.length);
            if (n == 0) return 0;
            if (rk <= r[0]) return a[0];
            for (int i = 1; i < n; i++) {
                if (rk <= r[i]) {
                    double f = (rk - r[i - 1]) / Math.max(1e-9, r[i] - r[i - 1]);
                    return a[i - 1] + (a[i] - a[i - 1]) * f;
                }
            }
            return a[n - 1];
        }

        /** A cross-section value at (radius km, height km), bilinear between cell centres, clamped at the edges. */
        double section(double[] a, double rk, double zk) {
            if (cols <= 0 || rows <= 0 || a.length < cols * rows) return 0;
            double gx = Math.abs(rk) / dr - 0.5, gy = zk / dz - 0.5;
            int c0 = (int) Math.floor(gx), r0 = (int) Math.floor(gy);
            double fx = gx - c0, fy = gy - r0;
            int ca = Math.max(0, Math.min(cols - 1, c0)), cb = Math.max(0, Math.min(cols - 1, c0 + 1));
            int ra = Math.max(0, Math.min(rows - 1, r0)), rb = Math.max(0, Math.min(rows - 1, r0 + 1));
            double top = a[ra * cols + ca] + (a[ra * cols + cb] - a[ra * cols + ca]) * fx;
            double bot = a[rb * cols + ca] + (a[rb * cols + cb] - a[rb * cols + ca]) * fx;
            return top + (bot - top) * fy;
        }
    }

    final double radiusKm, topKm;
    final List<Stage> stages = new ArrayList<>();
    final List<Chapter> lesson = new ArrayList<>();
    final List<String> sources = new ArrayList<>();
    /** The latest frame; null until the first one arrives. */
    Frame cur;
    long framesSeen;

    HurricaneModel(Map<String, Object> h) {
        Map<String, Object> d = Json.obj(h, "domain");
        radiusKm = Math.max(50, Json.num(d, "radius_km", 600));
        topKm = Math.max(4, Json.num(d, "top_km", 18));
        List<Object> sl = Json.arr(h, "stages");
        if (sl != null) for (Object o : sl) {
            Map<String, Object> m = Json.obj(o);
            if (m == null) continue;
            Stage s = new Stage();
            s.id = Json.str(m, "id", "");
            s.name = Json.str(m, "name", s.id);
            s.summary = Json.str(m, "summary", "");
            List<Object> wk = Json.arr(m, "wind_kt");
            if (wk != null && !wk.isEmpty()) {
                s.windMin = Json.num(wk.get(0), Double.NaN);
                if (wk.size() > 1) s.windMax = Json.num(wk.get(1), Double.NaN);
            }
            s.pressure = Json.num(m, "pressure_hpa", Double.NaN);
            s.category = (int) Json.num(m, "category", 0);
            stages.add(s);
        }
        List<Object> ll = Json.arr(h, "lesson");
        if (ll != null) for (Object o : ll) {
            Map<String, Object> m = Json.obj(o);
            if (m == null) continue;
            Chapter c = new Chapter();
            c.id = Json.str(m, "id", "");
            c.title = Json.str(m, "title", c.id);
            c.text = Json.str(m, "text", "");
            c.view = Json.str(m, "view", "both");
            c.focus = Json.str(m, "focus", "none");
            c.stage = Json.str(m, "stage", "");
            lesson.add(c);
        }
        List<Object> ss = Json.arr(h, "sources");
        if (ss != null) for (Object o : ss) if (o != null) sources.add(String.valueOf(o));
    }

    Stage stage(String id) {
        for (Stage s : stages) if (s.id.equals(id)) return s;
        return null;
    }

    int stageIndex(String id) {
        for (int k = 0; k < stages.size(); k++) if (stages.get(k).id.equals(id)) return k;
        return -1;
    }

    /** Apply one {@code hurricane} message. Caller holds the ViewerState lock. */
    void accept(Map<String, Object> m, long recvNanos) {
        Frame p = cur, f = new Frame();
        f.recvNanos = recvNanos;
        f.frame = (long) Json.num(m, "frame", 0);
        f.t = Json.num(m, "t", 0);
        f.speed = Json.num(m, "speed", 1);
        f.paused = Json.bool(m, "paused", false);
        f.stage = Json.str(m, "stage", "");
        f.stageIndex = (int) Json.num(m, "stage_index", Math.max(0, stageIndex(f.stage)));
        f.stageFrac = Model.clamp(Json.num(m, "stage_frac", 0), 0, 1);
        f.auto = Json.bool(m, "auto", false);
        Map<String, Object> s = Json.obj(m, "state");
        f.vmaxMs = Json.num(s, "vmax_ms", 0);
        f.vmaxKt = Json.num(s, "vmax_kt", f.vmaxMs * 3600 / 1852);
        f.pressure = Json.num(s, "pressure_hpa", Double.NaN);
        f.category = (int) Json.num(s, "category", 0);
        f.rmw = Json.num(s, "rmw_km", 40);
        f.eye = Json.num(s, "eye_km", 0);
        f.r34 = Json.num(s, "r34_km", 0);
        f.sst = Json.num(s, "sst_c", Double.NaN);
        f.warmCore = Json.num(s, "warm_core_c", 0);
        f.cloudTop = Json.num(s, "cloud_top_km", 0);
        f.rain = Json.num(s, "rain_mmh", 0);
        f.surge = Json.num(s, "surge_m", 0);
        f.organization = Model.clamp(Json.num(s, "organization", 0), 0, 1);
        f.eyeClarity = Model.clamp(Json.num(s, "eye_clarity", 0), 0, 1);
        f.overLand = Json.bool(s, "over_land", false);
        f.rotation = Json.num(s, "rotation", 0);
        f.hemisphere = Json.str(s, "hemisphere", "N");

        Map<String, Object> pr = Json.obj(m, "profile");
        if (pr != null) {
            f.r = Model.nums(Json.arr(pr, "r_km"));
            f.wind = Model.nums(Json.arr(pr, "wind_ms"));
            f.pres = Model.nums(Json.arr(pr, "pressure_hpa"));
            f.rainR = Model.nums(Json.arr(pr, "rain_mmh"));
            f.top = Model.nums(Json.arr(pr, "cloud_top_km"));
        } else if (p != null) { f.r = p.r; f.wind = p.wind; f.pres = p.pres; f.rainR = p.rainR; f.top = p.top; }

        List<Object> bl = Json.arr(m, "bands");
        if (bl != null) {
            List<Band> bs = new ArrayList<>(bl.size());
            for (Object o : bl) {
                Map<String, Object> b = Json.obj(o);
                if (b == null) continue;
                Band bd = new Band();
                bd.theta = Json.num(b, "theta", 0);
                bd.r0 = Math.max(1, Json.num(b, "r0_km", 60));
                bd.r1 = Math.max(bd.r0 + 1, Json.num(b, "r1_km", 300));
                bd.pitch = Json.num(b, "pitch", 0.28);
                bd.width = Math.max(1, Json.num(b, "width_km", 30));
                bd.intensity = Model.clamp(Json.num(b, "intensity", 0), 0, 1);
                bs.add(bd);
            }
            f.bands = bs.toArray(new Band[0]);
        } else if (p != null) f.bands = p.bands;

        Map<String, Object> sc = Json.obj(m, "section");
        boolean fresh = false;
        if (sc != null) {
            int cols = (int) Json.num(sc, "cols", 0), rows = (int) Json.num(sc, "rows", 0);
            double[] cloud = Model.nums(Json.arr(sc, "cloud"));
            if (cols > 0 && rows > 0 && cloud.length >= cols * rows) {
                f.cols = cols; f.rows = rows;
                f.dr = Math.max(0.01, Json.num(sc, "dr_km", radiusKm / cols));
                f.dz = Math.max(0.01, Json.num(sc, "dz_km", topKm / rows));
                f.cloud = cloud;
                f.u = grid(Json.arr(sc, "u"), cols * rows);
                f.w = grid(Json.arr(sc, "w"), cols * rows);
                f.v = grid(Json.arr(sc, "v"), cols * rows);
                f.temp = grid(Json.arr(sc, "temp_anom"), cols * rows);
                f.sectionVersion = (p != null ? p.sectionVersion : 0) + 1;
                fresh = true;
            }
        }
        if (!fresh && p != null) {
            f.cols = p.cols; f.rows = p.rows; f.dr = p.dr; f.dz = p.dz;
            f.u = p.u; f.w = p.w; f.v = p.v; f.cloud = p.cloud; f.temp = p.temp;
            f.sectionVersion = p.sectionVersion;
        }
        cur = f;
        framesSeen++;
    }

    /** A grid of exactly n values (missing ones are 0). */
    private static double[] grid(List<Object> a, int n) {
        double[] d = Model.nums(a);
        return d.length == n ? d : java.util.Arrays.copyOf(d, n);
    }
}
