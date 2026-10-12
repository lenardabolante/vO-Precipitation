package precip;

import java.awt.geom.Path2D;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The map geometry of the World Simulation: coastlines, countries and US states from
 * {@code assets/world/*.json} (Natural Earth 110m, public domain; written by
 * {@code tools/make_world_map.py}). The simulation never sends polygons: the viewer owns them.
 * <p>
 * Everything is in degrees. The ready-made paths use x = longitude (−180..180, east positive) and
 * y = <b>minus</b> latitude, so that north is up once a painter scales them uniformly. Region codes
 * are ISO 3166-1 alpha-2 for countries and {@code US-XX} for US states, the codes of
 * {@code hello.world_map.regions}.
 * <p>
 * Loaded once per project root and immutable afterwards, so any thread may read it.
 */
final class WorldMap {
    /** A country or a US state. */
    static final class Region {
        String code = "", name = "";
        boolean state;
        /** west, south, east, north. */
        double w, s, e, n;
        /** polygons → rings (outer first, then holes) → flat lon,lat pairs. */
        float[][][] polys = new float[0][][];
        /** A point inside the biggest polygon, for a label. */
        double labelLon, labelLat;
        /** Filled shape (even-odd) in map coordinates. */
        Path2D.Float shape;
        /** The same rings as open strokes, without the artificial edges along the antimeridian and the south pole (Russia, Fiji, Antarctica). */
        Path2D.Float outline;

        boolean contains(double lon, double lat) {
            if (lon < w || lon > e || lat < s || lat > n) return false;
            for (float[][] poly : polys) {
                if (poly.length == 0 || !inRing(poly[0], lon, lat)) continue;
                boolean hole = false;
                for (int k = 1; k < poly.length; k++) if (inRing(poly[k], lon, lat)) hole = true;
                if (!hole) return true;
            }
            return false;
        }
    }

    final List<Region> countries = new ArrayList<>(), states = new ArrayList<>();
    private final Map<String, Region> byCode = new HashMap<>();
    private float[][][] land = new float[0][][];
    /** All land, filled (even-odd), and the outlines to stroke: coasts, country borders, state borders. */
    final Path2D.Float landShape = new Path2D.Float(Path2D.WIND_EVEN_ODD);
    final Path2D.Float coastLines = new Path2D.Float(), countryLines = new Path2D.Float(), stateLines = new Path2D.Float();
    /** Null when everything loaded; otherwise what is missing (the screen says so instead of drawing an empty sea). */
    String problem;
    int landPoints, countryPoints, statePoints;

    private static final Map<String, WorldMap> CACHE = new HashMap<>();

    /** The map of this project root (read on first use). */
    static synchronized WorldMap get(File root) {
        String key = root.getAbsolutePath();
        WorldMap m = CACHE.get(key);
        if (m == null) {
            m = new WorldMap();
            File dir = new File(root, "assets" + File.separator + "world");
            // a root without the map (tests run in a temporary folder): the files of the installation
            if (!new File(dir, "land.json").isFile()) dir = new File(Main.projectRoot(), "assets" + File.separator + "world");
            m.load(dir);
            CACHE.put(key, m);
        }
        return m;
    }

    private void load(File dir) {
        try {
            Map<String, Object> l = read(new File(dir, "land.json"));
            land = polys(Json.arr(l, "polygons"));
            for (float[][] poly : land) {
                for (float[] ring : poly) { append(landShape, ring, true); outline(coastLines, ring); landPoints += ring.length / 2; }
            }
            regions(read(new File(dir, "countries.json")), countries, false);
            regions(read(new File(dir, "states.json")), states, true);
            for (Region r : countries) for (float[][] poly : r.polys) for (float[] ring : poly) { outline(countryLines, ring); countryPoints += ring.length / 2; }
            for (Region r : states) for (float[][] poly : r.polys) for (float[] ring : poly) { outline(stateLines, ring); statePoints += ring.length / 2; }
            if (land.length == 0 || countries.isEmpty()) problem = "The map files in assets/world are empty.";
        } catch (Exception | StackOverflowError ex) {
            problem = "The world map could not be read from " + dir + " (" + ex.getMessage() + ").\nRun: python tools/make_world_map.py";
            System.err.println("[viewer] " + problem);
        }
    }

    private static Map<String, Object> read(File f) throws java.io.IOException {
        Map<String, Object> m = Json.obj(Json.parse(Files.readString(f.toPath(), StandardCharsets.UTF_8)));
        if (m == null) throw new java.io.IOException(f.getName() + " is not a JSON object");
        return m;
    }

    private void regions(Map<String, Object> file, List<Region> out, boolean state) {
        List<Object> rs = Json.arr(file, "regions");
        if (rs == null) return;
        for (Object o : rs) {
            Map<String, Object> m = Json.obj(o);
            if (m == null) continue;
            Region r = new Region();
            r.code = Json.str(m, "code", "");
            r.name = Json.str(m, "name", r.code);
            r.state = state;
            r.polys = polys(Json.arr(m, "polygons"));
            if (r.polys.length == 0) continue;
            r.w = 999; r.s = 999; r.e = -999; r.n = -999;
            r.shape = new Path2D.Float(Path2D.WIND_EVEN_ODD);
            r.outline = new Path2D.Float();
            double best = -1;
            for (float[][] poly : r.polys) {
                for (float[] ring : poly) { append(r.shape, ring, true); outline(r.outline, ring); }
                float[] outer = poly[0];
                for (int k = 0; k + 1 < outer.length; k += 2) {
                    r.w = Math.min(r.w, outer[k]); r.e = Math.max(r.e, outer[k]);
                    r.s = Math.min(r.s, outer[k + 1]); r.n = Math.max(r.n, outer[k + 1]);
                }
                double[] c = centroid(outer);
                if (c[2] > best) { best = c[2]; r.labelLon = c[0]; r.labelLat = c[1]; }
            }
            out.add(r);
            if (!r.code.isEmpty()) byCode.putIfAbsent(r.code, r);
        }
    }

    private static float[][][] polys(List<Object> a) {
        if (a == null) return new float[0][][];
        List<float[][]> out = new ArrayList<>();
        for (Object po : a) {
            List<Object> rings = Json.arr(po);
            if (rings == null) continue;
            List<float[]> rs = new ArrayList<>();
            for (Object ro : rings) {
                List<Object> flat = Json.arr(ro);
                if (flat == null || flat.size() < 6) continue;
                float[] f = new float[flat.size() & ~1];
                for (int k = 0; k < f.length; k++) f[k] = (float) Json.num(flat.get(k), 0);
                rs.add(f);
            }
            if (!rs.isEmpty()) out.add(rs.toArray(new float[0][]));
        }
        return out.toArray(new float[0][][]);
    }

    private static void append(Path2D.Float p, float[] ring, boolean close) {
        p.moveTo(ring[0], -ring[1]);
        for (int k = 2; k + 1 < ring.length; k += 2) p.lineTo(ring[k], -ring[k + 1]);
        if (close) p.closePath();
    }

    /** A ring as a stroke, without the artificial edges along the antimeridian and the south pole. */
    private static void outline(Path2D.Float p, float[] ring) {
        int n = ring.length / 2;
        boolean pen = false;
        for (int i = 0; i <= n; i++) {
            int a = (i % n) * 2, b = ((i + 1) % n) * 2;
            float x0 = ring[a], y0 = ring[a + 1], x1 = ring[b], y1 = ring[b + 1];
            if (i == n) break;
            boolean seam = (Math.abs(x0) >= 179.98f && Math.abs(x1) >= 179.98f) || (y0 <= -89.9f && y1 <= -89.9f);
            if (seam) { pen = false; continue; }
            if (!pen) { p.moveTo(x0, -y0); pen = true; }
            p.lineTo(x1, -y1);
        }
    }

    /** Area centroid of a ring: {lon, lat, |area|}. */
    private static double[] centroid(float[] ring) {
        double a = 0, cx = 0, cy = 0;
        int n = ring.length / 2;
        for (int i = 0; i < n; i++) {
            double x0 = ring[i * 2], y0 = ring[i * 2 + 1], x1 = ring[((i + 1) % n) * 2], y1 = ring[((i + 1) % n) * 2 + 1];
            double c = x0 * y1 - x1 * y0;
            a += c; cx += (x0 + x1) * c; cy += (y0 + y1) * c;
        }
        if (Math.abs(a) < 1e-9) return new double[]{ring[0], ring[1], 0};
        return new double[]{cx / (3 * a), cy / (3 * a), Math.abs(a) / 2};
    }

    static boolean inRing(float[] ring, double lon, double lat) {
        boolean in = false;
        int n = ring.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = ring[i * 2], yi = ring[i * 2 + 1], xj = ring[j * 2], yj = ring[j * 2 + 1];
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    // ------------------------------------------------------------------ queries

    static double wrapLon(double lon) {
        lon = (lon + 180) % 360;
        if (lon < 0) lon += 360;
        return lon - 180;
    }

    Region region(String code) { return code == null ? null : byCode.get(code); }

    Region stateAt(double lon, double lat) {
        lon = wrapLon(lon);
        for (Region r : states) if (r.contains(lon, lat)) return r;
        return null;
    }

    Region countryAt(double lon, double lat) {
        lon = wrapLon(lon);
        for (Region r : countries) if (r.contains(lon, lat)) return r;
        return null;
    }

    /** The most specific region under a point: a US state first, then a country; null over the sea. */
    Region hit(double lon, double lat) {
        Region r = stateAt(lon, lat);
        return r != null ? r : countryAt(lon, lat);
    }

    boolean isLand(double lon, double lat) {
        lon = wrapLon(lon);
        for (float[][] poly : land) {
            if (!inRing(poly[0], lon, lat)) continue;
            boolean hole = false;
            for (int k = 1; k < poly.length; k++) if (inRing(poly[k], lon, lat)) hole = true;
            if (!hole) return true;
        }
        return false;
    }

    private float[] landGrid;
    private int landCols, landRows;

    /** Land fraction 0..1 of every cell of a cols x rows grid (row 0 = north), as the protocol lays its grids out. */
    synchronized float[] landFraction(int cols, int rows) {
        if (landGrid != null && landCols == cols && landRows == rows) return landGrid;
        float[] g = new float[cols * rows];
        double dlon = 360.0 / cols, dlat = 180.0 / rows;
        // bounding boxes first: most cells are tested against a handful of polygons only
        double[][] bb = new double[land.length][4];
        for (int p = 0; p < land.length; p++) {
            double w = 999, s = 999, e = -999, n = -999;
            float[] o = land[p][0];
            for (int k = 0; k + 1 < o.length; k += 2) { w = Math.min(w, o[k]); e = Math.max(e, o[k]); s = Math.min(s, o[k + 1]); n = Math.max(n, o[k + 1]); }
            bb[p] = new double[]{w, s, e, n};
        }
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            int in = 0;
            for (int sy = 0; sy < 3; sy++) for (int sx = 0; sx < 3; sx++) {
                double lon = -180 + (c + (sx + 0.5) / 3) * dlon, lat = 90 - (r + (sy + 0.5) / 3) * dlat;
                for (int p = 0; p < land.length; p++) {
                    if (lon < bb[p][0] || lon > bb[p][2] || lat < bb[p][1] || lat > bb[p][3]) continue;
                    if (inRing(land[p][0], lon, lat)) { in++; break; }
                }
            }
            g[r * cols + c] = in / 9f;
        }
        landGrid = g; landCols = cols; landRows = rows;
        return g;
    }

    /** Great-circle distance in km. */
    static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin((p2 - p1) / 2) * Math.sin((p2 - p1) / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
    }
}
