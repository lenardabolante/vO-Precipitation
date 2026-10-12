package precip;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the player can configure, kept in {@code config/settings.json} (versioned, written
 * atomically). A missing file means defaults; an unreadable one is moved aside and replaced by
 * defaults, with {@link #loadWarning} saying so.
 */
final class Settings {
    static final int VERSION = 1;

    static final String[] SIZE_NAMES = {"Small", "Medium", "Large", "Huge"};
    static final int[][] SIZES = {{1920, 900}, {3840, 1080}, {6400, 1200}, {9600, 1400}};
    static final String[] TERRAINS = {"flat", "rolling", "hilly", "mountainous"};
    static final String[] SETTLEMENTS = {"none", "sparse", "normal", "dense"};
    /** v2.4: how a new world's storm drains are sized ({@code --drains}). */
    static final String[] DRAINS = {"small", "normal", "large", "random"};
    static final int[] AUTOSAVE_MINUTES = {0, 1, 2, 5, 10};
    static final String[] QUALITY_NAMES = {"Low", "Medium", "High"};
    static final String[] SCALE_NAMES = {"Performance", "Balanced", "Native"};
    static final double[] SCALE_VALUES = {1.0, 1.5, 0};
    static final String[] FIT_NAMES = {"Cover", "Contain", "Stretch"};

    // ---- background
    /** File name inside {@code config/backgrounds/}; empty = the procedural sky. */
    String bgFile = "";
    int bgFit = Renderer.FIT_COVER;
    double bgDim = 0.2;
    boolean bgTint = true;

    // ---- graphics
    int cloudQuality = 1;
    /** Index into {@link #SCALE_VALUES}. */
    int renderScale = 1;
    boolean fullscreen;
    boolean showFps;
    /** Darken the view while the camera is under a cloud, and how strongly (0..1). */
    boolean cloudShade = true;
    double cloudShadeStrength = 0.65;
    /** Lightning without flashes: no light from the bolts, and the bolts themselves dim and eased (a file without the key = off). */
    boolean epilepsySafe;
    /** The Advanced tab ({@code graphics.advanced}). Written only when something differs from {@link Advanced#DEFAULT}: a file without the block = the defaults. */
    Advanced adv = Advanced.DEFAULT;

    // ---- sound (Phase P). The "sound" block is written only when something here differs from these defaults: a file without it = the defaults.
    static final double SOUND_MASTER = 0.7, SOUND_RAIN = 0.8, SOUND_THUNDER = 0.9, SOUND_TRAFFIC = 0.7, SOUND_TOWNS = 0.5, SOUND_STORM = 0.9;
    /** False = muted. */
    boolean soundOn = true;
    /** The master volume and the five groups, 0..1: both rain beds; thunder; passing cars; town ambience; siren, tornado and hurricane. */
    double soundMaster = SOUND_MASTER, soundRain = SOUND_RAIN, soundThunder = SOUND_THUNDER, soundTraffic = SOUND_TRAFFIC, soundTowns = SOUND_TOWNS, soundStorm = SOUND_STORM;

    /** True while every sound setting is at its default. */
    boolean soundDefault() {
        return soundOn && soundMaster == SOUND_MASTER && soundRain == SOUND_RAIN && soundThunder == SOUND_THUNDER && soundTraffic == SOUND_TRAFFIC
                && soundTowns == SOUND_TOWNS && soundStorm == SOUND_STORM;
    }

    // ---- game
    int autosaveMinutes = 2;
    int defaultSize = 1;
    String defaultTerrain = "hilly";
    String defaultSettlements = "normal";
    /** v2.4: the Drains choice of the New simulation dialog as made last. Written only when it is not "normal": a file without the key = normal. */
    String defaultDrains = "normal";

    // ---- side panels (remembered between sessions)
    boolean panelLeft = true, panelRight = true, panelMinimap = true;
    /** v2.3: the Climate panel (a settings file written before it existed has no key for it: the panel is then shown). */
    boolean panelClimate = true;

    // ---- window
    boolean hasWindow;
    int winX, winY, winW = 1680, winH = 760;
    boolean winMax;

    /** Id of the slot played last (preselected in the menu). */
    String lastSlot = "";

    // ---- the lesson modes: where the player was last (PLAN Phase F: "the last tab / stage is kept in settings.json").
    // A settings file written before these existed has no "modes" block: the modes then open at their beginning.
    /** Hurricane Formation: the tour chapter shown last (0 = the first), whether the stage explorer was open, its stage id ("" = the first) and the view ("" = the default). */
    int hurricaneChapter;
    boolean hurricaneExplore;
    String hurricaneStage = "", hurricaneView = "";
    /** Cloud Formations: the cloud type of the tab shown last ("" = the first tab). */
    String showcaseType = "";
    /** The hidden Debug game mode has been unlocked (ten clicks on the menu's cloud). Written only when true: a file without the key = locked. */
    boolean debugUnlocked;

    EnumMap<Action, List<Integer>> keys = KeyMap.defaults();

    // ---- not persisted
    final File dir;
    /** False = never touch the disk (self-test, UI snapshots). */
    boolean persist = true;
    /** Set when the settings file could not be read and defaults were used instead. */
    String loadWarning;
    /** Test hook: autosave every this many seconds instead of {@link #autosaveMinutes} (0 = off). */
    double autosaveSecondsOverride;

    Settings(File configDir) { this.dir = configDir; }

    File file() { return new File(dir, "settings.json"); }

    File backgroundsDir() { return new File(dir, "backgrounds"); }

    /** The stored background image file, or null when the default sky is used. */
    File backgroundFile() { return bgFile.isEmpty() ? null : new File(backgroundsDir(), bgFile); }

    double autosaveSeconds() { return autosaveSecondsOverride > 0 ? autosaveSecondsOverride : autosaveMinutes * 60.0; }

    static Settings load(File configDir) {
        Settings s = new Settings(configDir);
        File f = s.file();
        if (!f.isFile()) return s;
        try {
            Map<String, Object> m = Json.obj(Json.parse(Files.readString(f.toPath(), StandardCharsets.UTF_8)));
            if (m == null) throw new IllegalArgumentException("not a JSON object");
            s.read(m);
        } catch (IOException | RuntimeException ex) {
            Settings d = new Settings(configDir);
            d.loadWarning = "Settings file was unreadable — using defaults (the old file was kept as settings.json.corrupt)";
            try {
                Files.move(f.toPath(), new File(configDir, "settings.json.corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) { }
            System.err.println("[viewer] " + d.loadWarning + ": " + ex.getMessage());
            return d;
        }
        return s;
    }

    private static int clampi(double v, int lo, int hi) { return (int) Math.max(lo, Math.min(hi, Math.round(v))); }

    private static String oneOf(String v, String[] allowed, String def) {
        for (String a : allowed) if (a.equals(v)) return a;
        return def;
    }

    private void read(Map<String, Object> m) {
        Map<String, Object> bg = Json.obj(m, "background");
        if (bg != null) {
            bgFile = new File(Json.str(bg, "file", "")).getName(); // a bare file name, never a path
            bgFit = clampi(Json.num(bg, "fit", bgFit), 0, 2);
            bgDim = Model.clamp(Json.num(bg, "dim", bgDim), 0, 0.85);
            bgTint = Json.bool(bg, "tint", bgTint);
        }
        Map<String, Object> gr = Json.obj(m, "graphics");
        if (gr != null) {
            cloudQuality = clampi(Json.num(gr, "cloudQuality", cloudQuality), 0, 2);
            renderScale = clampi(Json.num(gr, "renderScale", renderScale), 0, 2);
            fullscreen = Json.bool(gr, "fullscreen", fullscreen);
            showFps = Json.bool(gr, "showFps", showFps);
            cloudShade = Json.bool(gr, "underCloudDim", cloudShade);
            cloudShadeStrength = Model.clamp(Json.num(gr, "underCloudDimStrength", cloudShadeStrength), 0.1, 1);
            epilepsySafe = Json.bool(gr, "epilepsySafe", epilepsySafe);
            adv = Advanced.read(Json.obj(gr, "advanced"));       // (anything but an object there is ignored)
        }
        Map<String, Object> so = Json.obj(m, "sound");
        if (so != null) {
            soundOn = Json.bool(so, "on", soundOn);
            soundMaster = Model.clamp(Json.num(so, "master", soundMaster), 0, 1);
            soundRain = Model.clamp(Json.num(so, "rain", soundRain), 0, 1);
            soundThunder = Model.clamp(Json.num(so, "thunder", soundThunder), 0, 1);
            soundTraffic = Model.clamp(Json.num(so, "traffic", soundTraffic), 0, 1);
            soundTowns = Model.clamp(Json.num(so, "towns", soundTowns), 0, 1);
            soundStorm = Model.clamp(Json.num(so, "storm", soundStorm), 0, 1);
        }
        Map<String, Object> ga = Json.obj(m, "game");
        if (ga != null) {
            int am = (int) Json.num(ga, "autosaveMinutes", autosaveMinutes);
            autosaveMinutes = 2;
            for (int v : AUTOSAVE_MINUTES) if (v == am) autosaveMinutes = am;
            defaultSize = clampi(Json.num(ga, "defaultSize", defaultSize), 0, SIZES.length - 1);
            defaultTerrain = oneOf(Json.str(ga, "defaultTerrain", defaultTerrain), TERRAINS, "hilly");
            defaultSettlements = oneOf(Json.str(ga, "defaultSettlements", defaultSettlements), SETTLEMENTS, "normal");
            defaultDrains = oneOf(Json.str(ga, "defaultDrains", defaultDrains), DRAINS, "normal");
        }
        Map<String, Object> pa = Json.obj(m, "panels");
        if (pa != null) {
            panelLeft = Json.bool(pa, "left", panelLeft);
            panelRight = Json.bool(pa, "right", panelRight);
            panelMinimap = Json.bool(pa, "minimap", panelMinimap);
            panelClimate = Json.bool(pa, "climate", panelClimate);
        }
        Map<String, Object> wi = Json.obj(m, "window");
        if (wi != null) {
            hasWindow = true;
            winX = (int) Json.num(wi, "x", 0);
            winY = (int) Json.num(wi, "y", 0);
            winW = clampi(Json.num(wi, "w", winW), 640, 20000);
            winH = clampi(Json.num(wi, "h", winH), 400, 20000);
            winMax = Json.bool(wi, "maximized", false);
        }
        lastSlot = Json.str(m, "lastSlot", "");
        Map<String, Object> mo = Json.obj(m, "modes");
        if (mo != null) {
            hurricaneChapter = clampi(Json.num(mo, "hurricaneChapter", 0), 0, 999);
            hurricaneExplore = Json.bool(mo, "hurricaneExplore", false);
            hurricaneStage = Json.str(mo, "hurricaneStage", "");
            hurricaneView = Json.str(mo, "hurricaneView", "");
            showcaseType = Json.str(mo, "showcaseType", "");
            debugUnlocked = Json.bool(mo, "debugUnlocked", false);
        }
        Map<String, Object> ks = Json.obj(m, "keys");
        if (ks != null) {
            EnumMap<Action, List<Integer>> out = KeyMap.defaults();
            java.util.Set<Integer> used = new java.util.HashSet<>();
            // actions named in the file take exactly the keys listed there (an empty list = unbound)
            for (Action a : Action.values()) {
                List<Object> l = Json.arr(ks, a.name());
                if (l == null) continue;
                List<Integer> codes = new ArrayList<>();
                for (Object o : l) {
                    int code = (int) Json.num(o, -1);
                    if (code > 0 && KeyMap.bindable(code) && used.add(code)) codes.add(code);
                }
                out.put(a, codes);
            }
            // actions the file does not know (added in a newer version) keep their defaults, minus keys already taken
            for (Action a : Action.values()) {
                if (Json.arr(ks, a.name()) != null) continue;
                out.get(a).removeIf(code -> !used.add(code));
            }
            keys = out;
        }
    }

    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", VERSION);
        Map<String, Object> bg = new LinkedHashMap<>();
        bg.put("file", bgFile);
        bg.put("fit", bgFit);
        bg.put("dim", Math.round(bgDim * 100) / 100.0);
        bg.put("tint", bgTint);
        m.put("background", bg);
        Map<String, Object> gr = new LinkedHashMap<>();
        gr.put("cloudQuality", cloudQuality);
        gr.put("renderScale", renderScale);
        gr.put("fullscreen", fullscreen);
        gr.put("showFps", showFps);
        gr.put("underCloudDim", cloudShade);
        gr.put("underCloudDimStrength", Math.round(cloudShadeStrength * 100) / 100.0);
        gr.put("epilepsySafe", epilepsySafe);
        if (!adv.equals(Advanced.DEFAULT)) gr.put("advanced", adv.toJson());   // a player who never opens the tab gets no new key
        m.put("graphics", gr);
        if (!soundDefault()) {                                  // a player who never opens the Sound tab gets no new key
            Map<String, Object> so = new LinkedHashMap<>();
            so.put("on", soundOn);
            so.put("master", Math.round(soundMaster * 100) / 100.0);
            so.put("rain", Math.round(soundRain * 100) / 100.0);
            so.put("thunder", Math.round(soundThunder * 100) / 100.0);
            so.put("traffic", Math.round(soundTraffic * 100) / 100.0);
            so.put("towns", Math.round(soundTowns * 100) / 100.0);
            so.put("storm", Math.round(soundStorm * 100) / 100.0);
            m.put("sound", so);
        }
        Map<String, Object> ga = new LinkedHashMap<>();
        ga.put("autosaveMinutes", autosaveMinutes);
        ga.put("defaultSize", defaultSize);
        ga.put("defaultTerrain", defaultTerrain);
        ga.put("defaultSettlements", defaultSettlements);
        if (!defaultDrains.equals("normal")) ga.put("defaultDrains", defaultDrains);     // a player who never changes it gets no new key
        m.put("game", ga);
        Map<String, Object> pa = new LinkedHashMap<>();
        pa.put("left", panelLeft);
        pa.put("right", panelRight);
        pa.put("minimap", panelMinimap);
        pa.put("climate", panelClimate);
        m.put("panels", pa);
        if (hasWindow) {
            Map<String, Object> wi = new LinkedHashMap<>();
            wi.put("x", winX);
            wi.put("y", winY);
            wi.put("w", winW);
            wi.put("h", winH);
            wi.put("maximized", winMax);
            m.put("window", wi);
        }
        m.put("lastSlot", lastSlot);
        Map<String, Object> mo = new LinkedHashMap<>();
        mo.put("hurricaneChapter", hurricaneChapter);
        mo.put("hurricaneExplore", hurricaneExplore);
        mo.put("hurricaneStage", hurricaneStage);
        mo.put("hurricaneView", hurricaneView);
        mo.put("showcaseType", showcaseType);
        if (debugUnlocked) mo.put("debugUnlocked", true);   // a locked player's file never gains the key
        m.put("modes", mo);
        Map<String, Object> ks = new LinkedHashMap<>();
        for (Action a : Action.values()) {
            List<Object> l = new ArrayList<>();
            for (int code : keys.get(a)) l.add(code);
            ks.put(a.name(), l);
        }
        m.put("keys", ks);
        return m;
    }

    /** Write the file (temp + atomic move). Failures are reported on stderr and returned, never thrown. */
    String save() {
        if (!persist) return null;
        try {
            SaveStore.atomicWrite(file(), pretty(toJson()).getBytes(StandardCharsets.UTF_8));
            return null;
        } catch (IOException ex) {
            System.err.println("[viewer] could not write " + file() + ": " + ex.getMessage());
            return String.valueOf(ex.getMessage());
        }
    }

    /** JSON with one top-level key per line, so the file is pleasant to read and diff. */
    static String pretty(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{\n");
        int k = 0;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            sb.append("  ").append(Json.quote(e.getKey())).append(": ");
            Json.write(sb, e.getValue());
            sb.append(++k < m.size() ? ",\n" : "\n");
        }
        return sb.append("}\n").toString();
    }
}
