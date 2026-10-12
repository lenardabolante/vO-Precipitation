package precip;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code hello.preset} block of protocol v2.2: the real region a world was built from. Every
 * value shown to the player (names, elevations, climate figures, the number of sources) comes from
 * the simulation; the viewer only lays it out.
 */
final class Preset {
    /** The six regions the New simulation dialog offers ({@code --preset <id>}), with the label shown on their button. */
    private static final String[] BUILTIN_IDS = {"washington", "philippines", "tibet", "russia", "united_kingdom", "central_europe"};
    private static final String[] BUILTIN_NAMES = {"Washington", "Philippines", "Tibet", "Russia", "United Kingdom", "Central Europe"};

    /**
     * v2.3: one entry of {@code assets/world/presets.json} (generated from {@code sim/presets.py}): a preset as a
     * place on the world map. Only {@code id} and {@code name} are known for a built-in fallback entry.
     */
    static final class Loc {
        String id = "", name = "", region = "", country = "", kind = "place", summary = "", station = "";
        double lat = Double.NaN, lon = Double.NaN, annualTempC = Double.NaN, annualPrecipMm = Double.NaN;
        /** Twelve monthly mean temperatures (January first); empty when the file has none. */
        double[] monthlyTempC = new double[0];
        String[] highlights = new String[0];
        /** v2.3: the biome id of the place ("desert", "polar", ...); empty when the file does not say. */
        String biome = "";

        boolean onMap() { return !Double.isNaN(lat) && !Double.isNaN(lon); }
    }

    /** True while {@code presets.json} is the hand-made stand-in (its monthly curve is not station data). */
    static boolean placeholderData;
    /** Every preset the simulation offers: the six v2.2 regions first (in their old order), then the world-map locations in file order. */
    static final Loc[] LOCS = loadLocs();
    static final String[] IDS = new String[LOCS.length];
    static final String[] NAMES = new String[LOCS.length];

    static {
        for (int k = 0; k < LOCS.length; k++) { IDS[k] = LOCS[k].id; NAMES[k] = LOCS[k].name; }
    }

    static Loc loc(String id) {
        for (Loc l : LOCS) if (l.id.equals(id)) return l;
        return null;
    }

    /** Read {@code assets/world/presets.json}; a missing or broken file leaves the six built-in regions (without map data). */
    private static Loc[] loadLocs() {
        List<Loc> file = new ArrayList<>();
        try {
            java.io.File f = new java.io.File(Main.projectRoot(), "assets" + java.io.File.separator + "world" + java.io.File.separator + "presets.json");
            if (f.isFile()) {
                Map<String, Object> root = Json.obj(Json.parse(java.nio.file.Files.readString(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)));
                placeholderData = Json.bool(root, "placeholder", false);
                List<Object> ps = Json.arr(root, "presets");
                if (ps != null) for (Object o : ps) {
                    Loc l = parseLoc(Json.obj(o));
                    if (l != null) file.add(l);
                }
            }
        } catch (Exception | StackOverflowError ex) {
            System.err.println("[viewer] assets/world/presets.json could not be read (" + ex + "): using the six built-in regions");
            file.clear();
            placeholderData = false;
        }
        List<Loc> out = new ArrayList<>();
        for (int k = 0; k < BUILTIN_IDS.length; k++) {
            Loc found = null;
            for (Loc l : file) if (l.id.equals(BUILTIN_IDS[k])) found = l;
            if (found == null) { found = new Loc(); found.id = BUILTIN_IDS[k]; found.name = BUILTIN_NAMES[k]; }
            out.add(found);
        }
        for (Loc l : file) {
            boolean dup = false;
            for (Loc o : out) if (o.id.equals(l.id)) dup = true;
            if (!dup) out.add(l);
        }
        return out.toArray(new Loc[0]);
    }

    static Loc parseLoc(Map<String, Object> m) {
        if (m == null) return null;
        Loc l = new Loc();
        l.id = Json.str(m, "id", "");
        if (l.id.isEmpty()) return null;
        l.name = Json.str(m, "name", l.id);
        l.lat = Json.num(m, "lat", Double.NaN);
        l.lon = Json.num(m, "lon", Double.NaN);
        l.region = Json.str(m, "region", "");
        l.country = Json.str(m, "country", "");
        l.kind = Json.str(m, "kind", "place");
        l.summary = Json.str(m, "summary", "");
        l.station = Json.str(m, "station", "");
        Object bo = m.get("biome");                      // the id, or a whole biome block
        l.biome = bo instanceof String s2 ? s2 : bo instanceof Map<?, ?> ? Json.str(Json.obj(bo), "id", "") : "";
        l.annualTempC = Json.num(m, "annual_temp_c", Double.NaN);
        l.annualPrecipMm = Json.num(m, "annual_precip_mm", Double.NaN);
        double[] mt = Model.nums(Json.arr(m, "monthly_temp_c"));
        if (mt.length == 12) l.monthlyTempC = mt;
        List<Object> hl = Json.arr(m, "highlights");
        if (hl != null) {
            List<String> hs = new ArrayList<>();
            for (Object o : hl) if (o != null) hs.add(String.valueOf(o));
            l.highlights = hs.toArray(new String[0]);
        }
        return l;
    }

    static String nameOf(String id) {
        for (int k = 0; k < IDS.length; k++) if (IDS[k].equals(id)) return NAMES[k];
        return id == null ? "" : id;
    }

    static boolean known(String id) {
        for (String s : IDS) if (s.equals(id)) return true;
        return false;
    }

    static final class Landmark {
        double x, elevM = Double.NaN;
        String name = "";
    }

    /**
     * Protocol v2.3 {@code hello.preset.biome}: what a region looks like, for the game's existing painters. The four
     * colours come from the simulation; the tints of the distant scenery (which the protocol does not send) are
     * derived here from the biome id. A world without one - or a {@code temperate} one - is drawn exactly as before.
     */
    static final class Biome {
        static final String[] IDS = {"temperate", "mediterranean", "tropical", "savanna", "desert", "alpine", "tundra", "polar"};
        String id = "temperate";
        Color ground = new Color(110, 146, 84), soil = new Color(104, 78, 54), grass = new Color(104, 160, 82), water = new Color(70, 120, 170);
        double trees = 1, snowStart;
        boolean frozenWater;

        /** The look this game has always had: nothing is re-coloured. */
        boolean plain() { return id.equals("temperate"); }

        /** Bare rock where the ground is steep or high. */
        Color rock() {
            return switch (id) {
                case "desert" -> new Color(178, 138, 98);
                case "savanna" -> new Color(154, 128, 96);
                case "mediterranean" -> new Color(162, 148, 122);
                case "tropical" -> new Color(108, 108, 100);
                case "tundra" -> new Color(126, 124, 120);
                case "polar" -> new Color(146, 160, 180);
                default -> new Color(132, 128, 124);
            };
        }

        /** The far (0) and near (1) parallax ridge. */
        Color ridge(int layer) {
            boolean far = layer == 0;
            return switch (id) {
                case "desert" -> far ? new Color(198, 160, 132) : new Color(178, 136, 98);
                case "savanna" -> far ? new Color(150, 142, 128) : new Color(142, 128, 88);
                case "mediterranean" -> far ? new Color(124, 126, 142) : new Color(116, 118, 92);
                case "tropical" -> far ? new Color(74, 112, 124) : new Color(44, 98, 82);
                case "alpine" -> far ? new Color(98, 110, 140) : new Color(86, 100, 106);
                case "tundra" -> far ? new Color(122, 132, 152) : new Color(112, 120, 116);
                case "polar" -> far ? new Color(204, 218, 236) : new Color(178, 198, 222);
                default -> far ? new Color(92, 112, 146) : new Color(70, 96, 108);
            };
        }

        /** Background mountain: its summit and foot colour. */
        Color[] mountain() {
            return switch (id) {
                case "desert" -> new Color[]{new Color(200, 152, 108), new Color(152, 106, 74)};
                case "savanna" -> new Color[]{new Color(152, 130, 112), new Color(106, 86, 72)};
                case "mediterranean" -> new Color[]{new Color(152, 140, 128), new Color(102, 98, 90)};
                case "tropical" -> new Color[]{new Color(96, 122, 112), new Color(52, 86, 72)};
                case "tundra" -> new Color[]{new Color(128, 130, 140), new Color(84, 90, 98)};
                case "polar" -> new Color[]{new Color(216, 228, 242), new Color(150, 172, 202)};
                default -> new Color[]{new Color(122, 122, 136), new Color(74, 84, 92)};
            };
        }

        /** Background hill: turf where the biome has any, else its bare ground (a dune, a snow dome). */
        Color[] hill() {
            Color top = grass != null ? grass : Lit.mix(ground, Color.WHITE, 0.10);
            return new Color[]{Lit.mix(top, Color.WHITE, 0.04), Lit.mul(grass != null ? grass : ground, grass != null ? 0.66 : 0.80)};
        }

        /** "desert" -> "Desert" (the word shown on the region card). */
        static String word(String id) { return id == null || id.isEmpty() ? "" : Character.toUpperCase(id.charAt(0)) + id.substring(1); }

        private static Color rgb(Map<String, Object> m, String key, Color def) {
            double[] v = Model.nums(Json.arr(m, key));
            return v.length == 3 ? new Color(ci(v[0]), ci(v[1]), ci(v[2])) : def;
        }

        private static int ci(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }

        static Biome parse(Map<String, Object> m) {
            if (m == null) return null;
            Biome b = new Biome();
            String id = Json.str(m, "id", "temperate");
            b.id = java.util.Arrays.asList(IDS).contains(id) ? id : "temperate";      // an unknown biome is drawn as today's
            b.ground = rgb(m, "ground", b.ground);
            b.soil = rgb(m, "soil", b.soil);
            b.grass = m.get("grass") == null ? (m.containsKey("grass") ? null : b.grass) : rgb(m, "grass", b.grass);
            b.water = rgb(m, "water", b.water);
            b.trees = Model.clamp(Json.num(m, "trees", 1), 0, 1);
            b.snowStart = Model.clamp(Json.num(m, "snow_start", 0), 0, 1);
            b.frozenWater = Json.bool(m, "frozen_water", false);
            return b;
        }
    }

    /** v2.3: the region's biome; null when the simulation sent none (= temperate, today's look). */
    Biome biome;

    String id = "", name = "", summary = "", transect = "";
    double spanKm = Double.NaN, maxElevM = Double.NaN, mPerPx = Double.NaN;
    String station = "", wind = "", note = "";
    double tempC = Double.NaN, annualPrecipMm = Double.NaN;
    final List<Landmark> landmarks = new ArrayList<>();
    final List<String> sources = new ArrayList<>();

    static Preset parse(Map<String, Object> m) {
        if (m == null) return null;
        Preset p = new Preset();
        p.id = Json.str(m, "id", "");
        p.name = Json.str(m, "name", nameOf(p.id));
        p.summary = Json.str(m, "summary", "");
        p.transect = Json.str(m, "transect", "");
        p.spanKm = Json.num(m, "span_km", Double.NaN);
        p.maxElevM = Json.num(m, "max_elev_m", Double.NaN);
        p.mPerPx = Json.num(m, "m_per_px", Double.NaN);
        Map<String, Object> c = Json.obj(m, "climate");
        if (c != null) {
            p.station = Json.str(c, "station", "");
            p.tempC = Json.num(c, "temp_c", Double.NaN);
            p.annualPrecipMm = Json.num(c, "annual_precip_mm", Double.NaN);
            p.wind = Json.str(c, "wind", "");
            p.note = Json.str(c, "note", "");
        }
        List<Object> ls = Json.arr(m, "landmarks");
        if (ls != null) for (Object o : ls) {
            Map<String, Object> a = Json.obj(o);
            if (a == null) continue;
            Landmark l = new Landmark();
            l.x = Json.num(a, "x", Double.NaN);
            l.name = Json.str(a, "name", "");
            l.elevM = Json.num(a, "elev_m", Double.NaN);
            if (!Double.isNaN(l.x) && !l.name.isEmpty()) p.landmarks.add(l);
        }
        List<Object> ss = Json.arr(m, "sources");
        if (ss != null) for (Object o : ss) if (o != null) p.sources.add(String.valueOf(o));
        p.biome = Biome.parse(Json.obj(m, "biome"));
        return p;
    }

    /** "4,392 m" — the elevation as the simulation gave it. */
    static String metres(double m) { return Double.isNaN(m) ? "" : String.format("%,d m", Math.round(m)); }
}
