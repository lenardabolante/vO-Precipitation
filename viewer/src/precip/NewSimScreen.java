package precip;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.event.KeyEvent;
import java.util.concurrent.ThreadLocalRandom;

/** The "New simulation" dialog: name, seed, region (a real one or custom terrain), starting month and time of day (v2.3), world size, terrain, settlements and the size of their storm drains (v2.4). */
final class NewSimScreen extends Screen {
    /** What the player asked for. {@code seed == null} means "pick one at random". */
    static final class Params {
        String name = "";
        Long seed;
        int sizeIdx = 1;
        String terrain = "hilly", settlements = "normal";
        /** v2.2: a region preset id ({@link Preset#IDS}), or "" for a custom world with the terrain chosen above. */
        String preset = "";
        /** v2.3: the calendar month (1..12.99) and local solar hour (0..24) the world starts in; NaN = let the simulation choose (the region's usual month, mid-morning). */
        double month = Double.NaN, hour = Double.NaN;
        /** v2.4: how the settlements' storm drains are sized ({@link Settings#DRAINS}); "normal" is the simulation's own default and is not sent. */
        String drains = "normal";

        /**
         * The {@code --month} / {@code --hour} arguments for the simulation (empty when both are left to it), and since v2.4
         * {@code --drains <value>} when the choice is not Normal. (Normal is what the simulation does without the argument,
         * so a simulation older than v2.4, which does not know {@code --drains}, still starts.)
         */
        java.util.List<String> climateArgs() {
            java.util.List<String> a = new java.util.ArrayList<>();
            if (!drains.equals("normal") && java.util.Arrays.asList(Settings.DRAINS).contains(drains)) { a.add("--drains"); a.add(drains); }
            if (!Double.isNaN(month)) { a.add("--month"); a.add(String.format(java.util.Locale.ROOT, "%.2f", Model.clamp(month, 1, 12.99))); }
            if (!Double.isNaN(hour)) { a.add("--hour"); a.add(String.format(java.util.Locale.ROOT, "%.2f", Model.clamp(hour, 0, 24))); }
            return a;
        }

        int width() { return Settings.SIZES[sizeIdx][0]; }

        int height() { return Settings.SIZES[sizeIdx][1]; }
    }

    /** v2.3 "Starts in": Auto (the region's usual month, April for a custom world) or a calendar month (sent as its middle, e.g. 7.5 = mid July). */
    static final String[] MONTH_LABELS = {"Auto", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    /** v2.3 "Time of day": Morning is the simulation's own start (no argument sent). */
    static final String[] TIME_LABELS = {"Dawn", "Morning", "Noon", "Afternoon", "Dusk", "Night"};
    static final double[] TIME_HOURS = {5.5, Double.NaN, 12.0, 15.0, 18.5, 23.0};
    static final String[] TERRAIN_LABELS = {"Flat", "Rolling", "Hilly", "Mountainous"};
    static final String[] SETTLEMENT_LABELS = {"None", "Sparse", "Normal", "Dense"};
    static final String[] DRAIN_LABELS = {"Small", "Normal", "Large", "Random"};
    private static final String[] SIZE_NOTES = {
            "a compact valley — quick to take in, lightest on the computer",
            "the standard world: room for a village, a city and a dozen clouds",
            "a long stretch of country with several settlements",
            "the biggest world — a lot of sky; needs a fast computer"};

    private final AppShell app;
    final Ui.TextField name, seed;
    int sizeIdx, terrainIdx, settleIdx;
    /** 0 = Custom, 1.. = {@link Preset#IDS}[regionIdx - 1]. */
    int regionIdx;
    /** The name the dialog suggested last; while the field still holds it, choosing a region renames the simulation. */
    private String suggested;
    private int focus;   // 0 = name, 1 = seed
    /** v2.3: which page of region chips is shown when there are more regions than fit two rows. */
    private int regionPage;
    /** v2.3: index into {@link #MONTH_LABELS} (0 = Auto) and {@link #TIME_LABELS} (1 = Morning, the default). */
    int monthIdx, timeIdx = 1;
    /** v2.4: index into {@link Settings#DRAINS} (1 = Normal). */
    int drainIdx = 1;

    NewSimScreen(AppShell app) {
        this.app = app;
        Settings s = app.settings;
        suggested = app.store.nextDefaultName();
        name = new Ui.TextField(suggested, 48, false);
        seed = new Ui.TextField("", 10, true);
        sizeIdx = s.defaultSize;
        terrainIdx = Math.max(0, java.util.Arrays.asList(Settings.TERRAINS).indexOf(s.defaultTerrain));
        settleIdx = Math.max(0, java.util.Arrays.asList(Settings.SETTLEMENTS).indexOf(s.defaultSettlements));
        int di = java.util.Arrays.asList(Settings.DRAINS).indexOf(s.defaultDrains);
        drainIdx = di < 0 ? 1 : di;
    }

    Params params() {
        Params p = new Params();
        p.name = name.value();
        String sd = seed.value().trim();
        if (!sd.isEmpty()) {
            try { p.seed = Long.parseLong(sd); } catch (NumberFormatException ignored) { }
        }
        p.sizeIdx = sizeIdx;
        p.terrain = Settings.TERRAINS[terrainIdx];
        p.settlements = Settings.SETTLEMENTS[settleIdx];
        p.preset = regionIdx > 0 ? Preset.IDS[regionIdx - 1] : "";
        p.month = monthIdx <= 0 ? Double.NaN : monthIdx + 0.5;
        p.hour = TIME_HOURS[Math.max(0, Math.min(TIME_HOURS.length - 1, timeIdx))];
        p.drains = Settings.DRAINS[Math.max(0, Math.min(Settings.DRAINS.length - 1, drainIdx))];
        return p;
    }

    /** Choose a region (0 = Custom). The suggested name follows it unless the player has typed their own. */
    void pickRegion(int idx) {
        regionIdx = Math.max(0, Math.min(Preset.IDS.length, idx));
        if (regionIdx > 0) regionPage = (regionIdx - 1) / 6;      // the page of region chips that shows it (see paint)
        if (name.value().equals(suggested)) {
            suggested = regionIdx > 0 ? app.store.uniqueName(Preset.NAMES[regionIdx - 1]) : app.store.nextDefaultName();
            name.set(suggested);
        }
    }

    private void create() {
        app.pop(this);
        Params p = params();
        if (!p.drains.equals(app.settings.defaultDrains)) {       // v2.4: the choice is the default of the next new simulation
            app.settings.defaultDrains = p.drains;
            app.settingsChanged();
        }
        app.startNew(p);
    }

    private void dice() {
        seed.set(String.valueOf(ThreadLocalRandom.current().nextInt(1, 1_000_000_000)));
        focus = 1;
    }

    @Override void paint(Ui ui, int w, int h) {
        // at the smallest window the hints under the rows are dropped so that every control still fits
        // (v2.4 added the Drains row: the roomy layout needs 662 px now, so it starts at a window 690 px high)
        boolean tight = h < 690;
        double[] d = ui.dialog(w, h, 600, tight ? 520 : 662, "New simulation");
        double x = d[0] + 24, cw = d[2] - 48, lw = 118, fx = x + lw, fw = cw - lw;
        double y = d[1] + (tight ? 54 : 62);

        ui.text("Name", x, y + 23, Ui.F_MED, Ui.DIM);
        ui.field(name, "Simulation", fx, y, fw, 36, focus == 0, () -> focus = 0);
        y += tight ? 43 : 50;

        ui.text("Seed", x, y + 23, Ui.F_MED, Ui.DIM);
        ui.field(seed, "leave empty for a random world", fx, y, fw - 46, 36, focus == 1, () -> focus = 1);
        paintDice(ui, fx + fw - 38, y, 38, 36);
        y += 42;
        if (!tight) {
            ui.text("The same seed always grows the same terrain, towns and first clouds.", fx, y + 10, Ui.F_SMALL, Ui.FAINT);
            y += 28;
        } else y += 3;

        // ---- region: Custom or one of the real regions, as two rows of chips
        ui.text("Region", x, y + 20, Ui.F_MED, Ui.DIM);
        String[] regions = new String[Preset.NAMES.length + 1];
        regions[0] = "Custom";
        System.arraycopy(Preset.NAMES, 0, regions, 1, Preset.NAMES.length);
        int perRow = 4;
        double chipH = 30, gap = 4, chipW = (fw - gap * (perRow - 1)) / perRow;
        // v2.3: more regions than the two rows hold (the world-map locations): Custom + six per page + a chip that turns the page
        boolean paged = regions.length > 2 * perRow;
        int per = 2 * perRow - 2, pages = paged ? (regions.length - 1 + per - 1) / per : 1;
        if (regionPage >= pages) regionPage = 0;
        for (int slot = 0; slot < 2 * perRow; slot++) {
            int k = !paged ? slot : slot == 0 ? 0 : regionPage * per + slot;
            double cx = fx + (slot % perRow) * (chipW + gap), cy = y + (slot / perRow) * (chipH + gap);
            if (paged && slot == 2 * perRow - 1) {
                ui.button("More  " + (regionPage + 1) + "/" + pages + "  ▸", cx, cy, chipW, chipH, Ui.GHOST, true, false, () -> regionPage = (regionPage + 1) % pages);
                break;
            }
            if (k >= regions.length) continue;
            chip(ui, regions[k], cx, cy, chipW, chipH, k == regionIdx, k);
        }
        y += 2 * chipH + gap + 4;
        String regionHint = "Custom: pick the terrain yourself below.";
        if (regionIdx > 0) {
            String tail = Preset.LOCS[regionIdx - 1].biome.isEmpty() ? "." : " · " + Preset.LOCS[regionIdx - 1].biome + " biome.";
            regionHint = "Terrain and starting climate come from a real cross-section of " + Preset.NAMES[regionIdx - 1] + tail;
            // the full sentence does not fit beside most region names and lost its last word, the biome ("temperate b…"): say it shorter then
            if (ui.width(regionHint, Ui.F_SMALL) > fw) regionHint = "Real terrain and starting climate of " + Preset.NAMES[regionIdx - 1] + tail;
        }
        ui.textFit(regionHint, fx, y + 10, fw, Ui.F_SMALL, regionIdx == 0 ? Ui.FAINT : Ui.ACCENT);
        y += tight ? 20 : 28;

        // ---- v2.3: when the world starts — the calendar month and the time of day (--month / --hour)
        ui.text("Starts in", x, y + 20, Ui.F_MED, Ui.DIM);
        ui.seg(MONTH_LABELS, monthIdx, fx, y, fw, 30, k -> monthIdx = k);
        y += tight ? 36 : 37;
        ui.text("Time of day", x, y + 20, Ui.F_MED, Ui.DIM);
        ui.seg(TIME_LABELS, timeIdx, fx, y, fw, 30, k -> timeIdx = k);
        y += tight ? 37 : 36;
        if (!tight) {
            ui.textFit((monthIdx == 0 ? (regionIdx == 0 ? "Auto: a custom world starts in April." : "Auto: the usual starting month of " + Preset.NAMES[regionIdx - 1] + ".")
                    : "Starts in mid " + Model.MONTHS[monthIdx - 1] + " (" + Model.seasonOf(monthIdx + 0.5, "N") + " in the north).")
                    + "  A day lasts 5 minutes, a year 8 days.", fx, y + 10, fw, Ui.F_SMALL, monthIdx == 0 ? Ui.FAINT : Ui.ACCENT);
            y += 24;
        }

        ui.text("World size", x, y + 21, Ui.F_MED, Ui.DIM);
        ui.seg(Settings.SIZE_NAMES, sizeIdx, fx, y, fw, 34, k -> sizeIdx = k);
        y += 40;
        if (!tight) {
            ui.textFit(Settings.SIZES[sizeIdx][0] + " × " + Settings.SIZES[sizeIdx][1] + " px  ·  " + SIZE_NOTES[sizeIdx], fx, y + 10, fw, Ui.F_SMALL, Ui.FAINT);
            y += 28;
        } else y += 2;

        boolean custom = regionIdx == 0;
        ui.text("Terrain", x, y + 21, Ui.F_MED, custom ? Ui.DIM : Ui.FAINT);
        if (custom) ui.seg(TERRAIN_LABELS, terrainIdx, fx, y, fw, 34, k -> terrainIdx = k);
        else {
            // a region brings its own terrain: the row is shown, but locked
            ui.fill(fx, y, fw, 34, 10, new Color(20, 26, 42, 150));
            ui.stroke(fx, y, fw, 34, 10, new Color(255, 255, 255, 18), 1f);
            ui.textCenter("set by the region — the real profile of " + Preset.NAMES[regionIdx - 1], fx + fw / 2, y + 22, Ui.F_NORM, Ui.FAINT);
        }
        y += tight ? 42 : 50;

        ui.text("Settlements", x, y + 21, Ui.F_MED, Ui.DIM);
        ui.seg(SETTLEMENT_LABELS, settleIdx, fx, y, fw, 34, k -> settleIdx = k);
        y += tight ? 38 : 40;

        // ---- v2.4: the size of the settlements' storm drains (--drains): it decides whether a town floods in a storm
        boolean towns = settleIdx > 0;
        ui.text("Drains", x, y + 21, Ui.F_MED, towns ? Ui.DIM : Ui.FAINT);
        ui.seg(DRAIN_LABELS, drainIdx, fx, y, fw, 34, k -> drainIdx = k);
        y += 40;
        if (!tight) ui.textFit(!towns ? "No settlements, so no drains: nothing floods."
                : drainIdx == 0 ? "Small storm drains: the streets flood in a downpour."
                : drainIdx == 2 ? "Large storm drains: they take even a supercell."
                : drainIdx == 3 ? "Every town gets a size of its own: some flood in a storm another shrugs off."
                : "Each town's storm drains are sized by its kind: some flood in a storm, some do not.", fx, y + 10, fw, Ui.F_SMALL, Ui.FAINT);

        double by = d[1] + d[3] - (tight ? 52 : 58);
        ui.text("Enter creates  ·  Tab next field", x, by + 23, Ui.F_SMALL, Ui.FAINT);
        ui.button("Cancel", d[0] + d[2] - 24 - 130 - 10 - 190, by, 130, 38, Ui.NORMAL, true, false, () -> app.pop(this));
        ui.button("Create simulation", d[0] + d[2] - 24 - 190, by, 190, 38, Ui.PRIMARY, true, false, this::create);
    }

    /** One region chip. */
    private void chip(Ui ui, String label, double x, double y, double w, double h, boolean sel, int idx) {
        boolean hov = ui.hover(x, y, w, h);
        ui.fill(x, y, w, h, 9, sel ? new Color(58, 110, 200, 245) : hov ? new Color(52, 68, 104, 245) : new Color(26, 34, 54, 240));
        ui.stroke(x, y, w, h, 9, sel ? new Color(255, 255, 255, 90) : new Color(255, 255, 255, 30), 1f);
        java.awt.Font f = sel ? Ui.F_BOLD : Ui.F_NORM;
        ui.g.setFont(f);
        FontMetrics fm = ui.g.getFontMetrics();
        String s = ui.fit(label, f, w - 8);
        ui.text(s, x + (w - fm.stringWidth(s)) / 2.0, y + (h + fm.getAscent() - fm.getDescent()) / 2.0, f, sel ? Color.WHITE : Ui.TEXT);
        ui.hit(x, y, w, h, () -> pickRegion(idx));
    }

    /** The dice button: rolls a random seed into the field. */
    private void paintDice(Ui ui, double x, double y, double w, double h) {
        boolean hov = ui.hover(x, y, w, h);
        ui.fill(x, y, w, h, 8, hov ? new java.awt.Color(52, 68, 104, 245) : new java.awt.Color(34, 44, 68, 240));
        ui.stroke(x, y, w, h, 8, new java.awt.Color(255, 255, 255, 40), 1f);
        double s = 18, dx = x + (w - s) / 2, dy = y + (h - s) / 2;
        ui.fill(dx, dy, s, s, 5, Ui.TEXT);
        ui.g.setColor(new java.awt.Color(20, 28, 44));
        double[][] pips = {{0.27, 0.27}, {0.73, 0.27}, {0.5, 0.5}, {0.27, 0.73}, {0.73, 0.73}};
        for (double[] p : pips) ui.g.fill(new java.awt.geom.Ellipse2D.Double(dx + p[0] * s - 1.7, dy + p[1] * s - 1.7, 3.4, 3.4));
        ui.hit(x, y, w, h, this::dice);
    }

    @Override void key(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ESCAPE -> app.pop(this);
            case KeyEvent.VK_ENTER -> create();
            case KeyEvent.VK_TAB, KeyEvent.VK_UP, KeyEvent.VK_DOWN -> focus = 1 - focus;
            // Page Up / Page Down step through the regions without leaving the text field
            case KeyEvent.VK_PAGE_DOWN -> pickRegion((regionIdx + 1) % (Preset.IDS.length + 1));
            case KeyEvent.VK_PAGE_UP -> pickRegion((regionIdx + Preset.IDS.length) % (Preset.IDS.length + 1));
            default -> (focus == 0 ? name : seed).key(e);
        }
    }

    @Override void typed(char c) { (focus == 0 ? name : seed).typed(c); }
}
