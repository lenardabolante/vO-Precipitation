package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Debug mode, the two line-ups: <b>Buildings</b> (every building kind, every house variant and every
 * tree / plant variant, each intact and storm-damaged) and <b>Cars</b> (every variant, both
 * directions, stopped in the rain, snow-covered, thrown and wrecked). Phase O: the Buildings tab steps through the
 * damage ladder (k 0..24), boxes every floating piece of a damaged picture in red (there must be none), shows the
 * back row's version, any hour of the day, the people, and plays the wreck's {@code hit} scene. Every picture is drawn by
 * {@link BuildingPainter} / {@link CarPainter} from a {@link Model.Obj} / {@link Model.Car} exactly
 * as the world renderer hands them over; the tabs only add the sky, the ground or road under them
 * and the labels.
 */
final class DebugLineup {
    private DebugLineup() {}

    /** One thing in the Buildings line-up. */
    static final class Entry {
        /** {@code barn}, {@code house:cabin}, {@code tree:oak}: kind and variant as the protocol names them. */
        final String label, group, kind, variant;
        final double w, h;
        final int floors;

        Entry(String group, String kind, String variant, double w, double h, int floors) {
            this.group = group; this.kind = kind; this.variant = variant; this.w = w; this.h = h; this.floors = floors;
            label = variant.isEmpty() ? kind : kind + ":" + variant;
        }

        boolean tree() { return kind.equals("tree"); }
    }

    static final String[] GROUPS = {"BUILDINGS", "HOUSES", "TREES AND PLANTS"};

    /**
     * The line-up: one entry per name in {@link BuildingPainter#KINDS}, {@link BuildingPainter#HOUSE_VARIANTS} and
     * {@link BuildingPainter#TREE_VARIANTS}, at the sizes the built-in world builds them ({@code FakeSim.buildWorld}).
     */
    static List<Entry> entries() {
        List<Entry> l = new ArrayList<>();
        for (String k : BuildingPainter.KINDS) {
            double w, h;
            int floors = 1;
            String variant = "";
            switch (k) {
                case "barn" -> { w = 96; h = 70; }
                case "church" -> { w = 74; h = 132; }
                case "windmill" -> { w = 54; h = 118; }
                case "shop" -> { w = 72; h = 46; variant = "bakery"; }
                case "apartment" -> { floors = 5; w = 88; h = floors * 16 + 12; }
                case "office" -> { floors = 10; w = 100; h = floors * 14 + 12; }
                case "skyscraper" -> { floors = 22; w = 78; h = floors * 14 + 16; }
                case "factory" -> { w = 150; h = 84; }
                case "water_tower" -> { w = 44; h = 96; }
                default -> { w = 8; h = 34; }                    // streetlamp
            }
            l.add(new Entry(GROUPS[0], k, variant, w, h, floors));
        }
        for (String v : BuildingPainter.HOUSE_VARIANTS) {
            double w, h;
            int floors = 1;
            switch (v) {
                case "cabin" -> { w = 58; h = 42; }
                case "farmhouse" -> { w = 84; h = 62; floors = 2; }
                case "townhouse" -> { w = 46; h = 78; floors = 3; }
                case "bungalow" -> { w = 80; h = 40; }
                case "manor" -> { w = 112; h = 78; floors = 2; }
                case "adobe" -> { w = 64; h = 44; floors = 2; }
                case "hut" -> { w = 46; h = 37; }
                case "station" -> { w = 76; h = 44; }
                default -> { w = 62; h = 52; }                   // cottage
            }
            l.add(new Entry(GROUPS[1], "house", v, w, h, floors));
        }
        for (String v : BuildingPainter.TREE_VARIANTS) {
            double w, h;
            switch (v) {
                case "bush" -> { w = 34; h = 22; }
                case "birch" -> { w = 26; h = 72; }
                case "oak" -> { w = 46; h = 70; }
                case "palm" -> { w = 34; h = 112; }
                case "cactus" -> { w = 22; h = 66; }
                case "acacia" -> { w = 70; h = 62; }
                case "shrub" -> { w = 30; h = 20; }
                case "dead" -> { w = 20; h = 60; }
                default -> { w = 30; h = 84; }                   // pine
            }
            l.add(new Entry(GROUPS[2], "tree", v, w, h, 1));
        }
        return l;
    }

    /** The object the painters get for an entry: placed at (0, 0), its base on the ground line. */
    static Model.Obj obj(Entry e, int index, long seed, double damage, double snow, double wet, double sway) {
        Model.Obj o = new Model.Obj();
        o.id = index + 1;
        o.kind = e.kind;
        o.variant = e.variant;
        o.w = e.w; o.h = e.h;
        o.floors = e.floors;
        o.seed = (seed * 1_000_003L + index * 7919L + 17) & 0x3fffffffL;
        o.building = Model.isBuilding(e.kind);
        if (o.building) {
            int[][] c = FakeSim.debugColors(e.kind, e.variant, o.seed);
            o.wall = c[0]; o.roof = c[1];
        }
        o.damage = Model.clamp(damage, 0, 1);
        o.snow = snow; o.wet = wet; o.sway = sway;
        return o;
    }

    /** Draw one entry standing on (cx, groundY) at {@code s} px per world unit: the static part as the scenery tiles hold it. */
    static void paintObj(Graphics2D g, Entry e, Model.Obj o, Lit lit, double cx, double groundY, double s) { paintObj(g, e, o, lit, cx, groundY, s, 0); }

    /** @param windLevel the coarse wind level the rain beads on a wet building's panes are smeared with ({@link BuildingPainter#windLevel}) */
    static void paintObj(Graphics2D g, Entry e, Model.Obj o, Lit lit, double cx, double groundY, double s, int windLevel) {
        AffineTransform old = g.getTransform();
        g.translate(cx, groundY);
        g.scale(s, s);
        if (e.tree()) BuildingPainter.paintTree(g, o, lit, o.sway * 0.35, 0);
        else BuildingPainter.paint(g, o, lit, 0, windLevel);
        g.setTransform(old);
    }

    /** The rain running down the panes of a wet building in a wind of {@code windMs} m/s, as the renderer draws it every frame over the tiles. */
    static void paintDrops(Graphics2D g, Model.Obj o, Lit lit, double cx, double groundY, double s, double anim, double windMs) {
        if (!o.building) return;
        AffineTransform old = g.getTransform();
        g.translate(cx, groundY);
        g.scale(s, s);
        AffineTransform at = g.getTransform();
        double px = Math.hypot(at.getScaleX(), at.getShearY());
        if (BuildingPainter.hasDrops(o, px)) BuildingPainter.paintDrops(g, o, lit, anim, 0, px, -1e9, -1e9, 1e9, 1e9, windMs);
        g.setTransform(old);
    }

    /** The moving parts (windmill sails, factory smoke, the tower's aviation light), as the renderer draws them every frame. */
    static void paintLive(Graphics2D g, Model.Obj o, Lit lit, double cx, double groundY, double s, double anim) {
        if (!o.building || !BuildingPainter.hasLive(o.kind) || !BuildingPainter.liveWorks(o)) return;
        AffineTransform old = g.getTransform();
        g.translate(cx, groundY);
        g.scale(s, s);
        BuildingPainter.paintLive(g, o, lit, anim, 0);
        g.setTransform(old);
    }

    /** One picture of a single entry on a transparent canvas (tests: is it drawn, does it differ from another). */
    static BufferedImage portrait(Entry e, int index, long seed, double damage, double tod) {
        BufferedImage img = new BufferedImage(260, 260, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        DebugScreen.hints(g);
        double s = Math.min(1.6, Math.min(210 / (e.h * 1.3), 220 / (e.w * 1.3)));
        paintObj(g, e, obj(e, index, seed, damage, 0, 0, 0), new Lit(tod, 0), 130, 240, s);
        g.dispose();
        return img;
    }

    // =====================================================================================

    /** The Buildings and plants tab. */
    static final class Buildings extends DebugScreen.Tab {
        private final DebugScreen ds;
        final List<Entry> entries = entries();
        int light;
        double damage = 0.6, snow, wet, wind = 0.25;
        /** The wind slider's full scale in m/s (the built-in simulation leans a tree fully at 14 m/s): what the rain on the panes is given. */
        static final double WIND_MS = 14;
        private double scroll, contentH, viewH = 400;
        /** The cells as laid out for the last frame (content coordinates: y = 0 is the top of the line-up). */
        final List<Rectangle2D.Double> cellRects = new ArrayList<>();
        /** The labels of the entries drawn into the line-up picture (tests). */
        final List<String> drawn = new ArrayList<>();
        private BufferedImage sheet;
        private String sheetKey = "";
        private final List<double[]> headers = new ArrayList<>();   // y, group index
        private Rectangle2D.Double view = new Rectangle2D.Double();
        // ---- Phase O (OH7)
        /** Box every floating piece of the damaged copies in red ({@link FloatTest#scan}); paint the buildings as the back row has them; with their people. */
        boolean floats = true, backRow, people = true;
        /** The hour of the day 0..24 the line-up is lit for, or -1 = the Day / Dusk / Night buttons. */
        double hour = -1;
        /** How many floating pieces the overlay boxed in the picture last built (rule F: none, at every step of the ladder). */
        int floating;
        /** The wreck's {@code hit} scene while it plays in place of the line-up (the Hit button); null = the line-up. */
        DebugWorlds.Pane hit;

        Buildings(DebugScreen ds) { this.ds = ds; }

        @Override void leave() {
            sheet = null; sheetKey = "";
            if (hit != null) { hit.dispose(); hit = null; }
        }

        /** True while the tab holds a picture or a world of its own (leaving it lets go of both). */
        boolean cached() { return sheet != null || hit != null; }

        /** The step 0..24 of the damage ladder the slider stands on. */
        int k() { return (int) Math.round(damage * FloatTest.LEVELS); }

        void step(int by) { damage = Math.max(0, Math.min(FloatTest.LEVELS, k() + by)) / (double) FloatTest.LEVELS; }

        /** The Hit view's least zoom (world px to screen px): above the people's threshold ({@link Life#LIFE_MIN_PX} 1.6). */
        static final double HIT_MIN_ZOOM = 1.8;

        /** Play (or stop) the wreck's hit scene: a tornado walks along a street of twelve buildings. */
        void hit(boolean on) {
            if (hit != null) { hit.dispose(); hit = null; }
            if (!on) return;
            hit = new DebugWorlds.Pane(new FakeSim(ds.seed, DebugScreen.LIGHT_TOD[0], DebugWorlds.WORLD_W, DebugWorlds.WORLD_H, null, null, Double.NaN, Double.NaN, "hit"), ds.still);
            hit.placer = pn -> {
                // the street the funnel walks along: the first settlement of the scene, a little sky above it
                Model.Hello h = pn.hello();
                double x0 = h.w * 0.05, x1 = h.w * 0.30;
                if (!h.settlements.isEmpty()) { x0 = h.settlements.get(0).x0 - 60; x1 = h.settlements.get(0).x1 + 60; }
                // (OI: never further out than HIT_MIN_ZOOM - below Life.LIFE_MIN_PX the street has no people and the pieces are
                // specks; when the street does not fit at that zoom the view starts at its west end, where the funnel comes down)
                double fit = pn.cam.vw / (x1 - x0), z = Model.clamp(Math.max(fit, HIT_MIN_ZOOM), pn.cam.minZoom(), Camera.MAX_ZOOM);
                double xm = z > fit ? x0 + pn.cam.vw / z / 2 : (x0 + x1) / 2;
                pn.cam.set(xm, h.terrain.at(xm) - pn.cam.vh / z * 0.30, z, true);
            };
        }

        @Override void option(String key, String value) {
            switch (key) {
                case "light" -> light = Math.max(0, java.util.Arrays.asList("day", "dusk", "night").indexOf(value));
                case "damage" -> damage = Double.parseDouble(value);
                case "snow" -> snow = Double.parseDouble(value);
                case "wet" -> wet = Double.parseDouble(value);
                case "wind" -> wind = Double.parseDouble(value);
                case "scroll" -> scroll = Double.parseDouble(value);
                case "k" -> damage = Math.max(0, Math.min(FloatTest.LEVELS, Integer.parseInt(value))) / (double) FloatTest.LEVELS;
                case "floats" -> floats = !value.equals("off");
                case "back" -> backRow = !value.equals("off");
                case "people" -> people = !value.equals("off");
                case "hour" -> hour = Double.parseDouble(value);
                case "hit" -> hit(!value.equals("off"));
                default -> { }
            }
        }

        Lit lit() { return new Lit(hour >= 0 ? hour / 24.0 : DebugScreen.LIGHT_TOD[light], 0); }

        /** One building of the line-up as the tab's switches have it: front or back row, with or without its people. */
        private void paintOne(Graphics2D g, Entry e, Model.Obj o, Lit lit, double cx, double groundY, double s, int windLevel) {
            if (e.tree()) { paintObj(g, e, o, lit, cx, groundY, s, windLevel); return; }
            AffineTransform old = g.getTransform();
            g.translate(cx, groundY);
            g.scale(s, s);
            if (backRow) g.scale(0.9, 0.9);                  // (as Renderer.paintGround paints the back row: nine tenths, through haze)
            BuildingPainter.paint(g, o, lit, backRow ? 0.36 : 0, windLevel, people ? -1 : 0);
            g.setTransform(old);
        }

        private static final double CELL_H = 204, HEAD_H = 26, GAP = 6;

        /** Place the cells for a line-up {@code w} wide. */
        void layout(double w) {
            cellRects.clear();
            headers.clear();
            int cols = Math.max(2, (int) Math.floor((w + GAP) / 246));
            double cw = (w - GAP * (cols - 1)) / cols, y = 0;
            String group = "";
            int col = 0;
            for (Entry e : entries) {
                if (!e.group.equals(group)) {
                    if (col != 0) { y += CELL_H + GAP; col = 0; }
                    group = e.group;
                    headers.add(new double[]{y, java.util.Arrays.asList(GROUPS).indexOf(group)});
                    y += HEAD_H;
                }
                cellRects.add(new Rectangle2D.Double(col * (cw + GAP), y, cw, CELL_H));
                if (++col == cols) { col = 0; y += CELL_H + GAP; }
            }
            if (col != 0) y += CELL_H + GAP;
            contentH = y;
        }

        /** Where in its cell (0..1 of the width) the intact and the damaged copy of an entry stand. */
        private static final double TREE_INTACT_AT = 0.15, TREE_DAMAGED_AT = 0.60;
        private static double intactAt(Entry e) { return e.tree() ? TREE_INTACT_AT : 0.27; }
        private static double damagedAt(Entry e) { return e.tree() ? TREE_DAMAGED_AT : 0.75; }

        /** Scale of an entry inside its cell and the two places it stands on: {scale, x of the intact copy, x of the damaged copy, ground y}. */
        private static double[] place(Entry e, Rectangle2D.Double c) {
            double s = Math.min(1.9, Math.min((c.height - 66) / (e.h * 1.16), (c.width * 0.5 - 16) / (e.w * 1.12 + 12)));
            // (OI: a snapped tree falls to either side by up to its own height. Its damaged copy stands where the fall fits
            // on both sides - clear of the intact copy on the left and of the cell's edge on the right - and a tall tree is
            // drawn a little smaller for it)
            if (e.tree()) s = Math.min(s, Math.min(c.width * (1 - TREE_DAMAGED_AT) / (e.h * 1.05 + 4), (c.width * TREE_DAMAGED_AT - 8) / (e.w * 1.24 + e.h * 0.8)));
            // (2026-10-12: a broken tree now lies flat, its whole length along the ground - about 0.95 of its height plus
            // half its crown. That must fit to the right of its stump inside the cell, and to the left of it clear of the
            // intact copy's crown, whichever side the tree's seed makes it fall to)
            if (e.tree() && TreePainter.snaps(e.variant)) {
                double lies = e.h * 0.95 + e.w * 0.45;
                s = Math.min(s, Math.min((c.width * (1 - TREE_DAMAGED_AT) - 6) / lies, c.width * (TREE_DAMAGED_AT - TREE_INTACT_AT) / (lies + e.w * 0.6)));
            }
            // (a wide plant - the acacia, the bush - keeps its whole crown inside the cell)
            return new double[]{s, c.x + Math.max(c.width * intactAt(e), e.tree() ? e.w * s * 0.62 + 8 : 0), c.x + c.width * damagedAt(e), c.y + c.height - 34};
        }

        /** The whole line-up as one picture (the static part; rebuilt when a control changes). */
        private void buildSheet(double w, double scale) {
            int iw = Math.max(1, (int) Math.ceil(w * scale)), ih = Math.max(1, (int) Math.ceil(contentH * scale));
            sheet = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = sheet.createGraphics();
            DebugScreen.hints(g);
            g.scale(scale, scale);
            g.setColor(DebugScreen.BG);
            g.fillRect(0, 0, (int) w + 2, (int) contentH + 2);
            Lit lit = lit();
            drawn.clear();
            floating = 0;
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                Rectangle2D.Double c = cellRects.get(i);
                Shape clip = g.getClip();
                g.clip(new RoundRectangle2D.Double(c.x, c.y, c.width, c.height, 10, 10));
                DebugScreen.sky(g, c.x, c.y, c.width, c.height, lit);
                double[] p = place(e, c);
                // the ground the things stand on (snow lies on it when the slider says so)
                Color ground = Lit.mix(lit.lit(new Color(104, 146, 84)), lit.lit(new Color(244, 248, 255)), snow * 0.9);
                g.setColor(Lit.mix(ground, Lit.mul(ground, 0.75), wet * 0.6));
                g.fill(new Rectangle2D.Double(c.x, p[3], c.width, c.y + c.height - p[3]));
                // (the beads on wet panes are smeared at the wind level the slider gives, as the scenery tiles would hold them)
                final int wl = BuildingPainter.windLevel(wind * WIND_MS, 0);
                paintOne(g, e, obj(e, i, ds.seed, 0, snow, wet, wind), lit, p[1], p[3], p[0], wl);
                paintOne(g, e, obj(e, i, ds.seed, damage, snow, wet, wind), lit, p[2], p[3], p[0], wl);
                if (floats && !e.tree() && !e.kind.equals("streetlamp") && damage >= 0.04) {
                    // Phase O: the damaged copy once more, alone on a transparent picture of the cell, searched for pieces
                    // in the air exactly as FloatTest does; each one found is boxed in red (rule F: there is none)
                    final double ox = Math.floor(c.x * scale) / scale, oy = Math.floor(c.y * scale) / scale;
                    BufferedImage alone = new BufferedImage((int) Math.ceil(c.width * scale) + 2, (int) Math.ceil(c.height * scale) + 2, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D ag = alone.createGraphics();
                    DebugScreen.hints(ag);
                    ag.scale(scale, scale);
                    ag.translate(-ox, -oy);
                    paintOne(ag, e, obj(e, i, ds.seed, damage, snow, wet, wind), lit, p[2], p[3], p[0], wl);
                    ag.dispose();
                    final double ps = p[0] * (backRow ? 0.9 : 1);
                    FloatTest.Scan found = FloatTest.scan(alone, (int) Math.floor((p[3] - 2 * ps - oy) * scale), (int) Math.floor((p[3] - FloatTest.VIS_ABOVE * ps - oy) * scale));
                    floating += found.boxes.size();
                    g.setColor(Color.RED);
                    g.setStroke(new BasicStroke((float) (1.4 / scale)));
                    for (int[] b : found.boxes) g.draw(new Rectangle2D.Double(ox + (b[0] - 3) / scale, oy + (b[1] - 3) / scale, (b[2] - b[0] + 6) / scale, (b[3] - b[1] + 6) / scale));
                }
                g.setClip(clip);
                drawn.add(e.label);
            }
            g.dispose();
        }

        @Override void scene(Graphics2D g, BufferedImage buf, double scale, Rectangle2D.Double r, double dt) {
            view = DebugScreen.main(r);
            viewH = view.height;
            if (hit != null) {
                // the wreck's scene plays in place of the line-up
                hit.pump(dt);
                hit.render(buf, scale, DebugWorlds.below(view), dt);
                return;
            }
            layout(view.width - 10);
            scroll = Math.max(0, Math.min(scroll, Math.max(0, contentH - viewH)));
            String key = light + "|" + damage + "|" + snow + "|" + wet + "|" + wind + "|" + ds.seed + "|" + (view.width - 10) + "|" + scale
                    + "|" + floats + "|" + backRow + "|" + people + "|" + hour;
            if (sheet == null || !key.equals(sheetKey)) { buildSheet(view.width - 10, scale); sheetKey = key; }
            Graphics2D d = buf.createGraphics();
            d.setClip(new Rectangle2D.Double(view.x * scale, view.y * scale, view.width * scale, view.height * scale));
            d.drawImage(sheet, (int) Math.round(view.x * scale), (int) Math.round((view.y - scroll) * scale), null);
            d.dispose();
            // the moving parts, every frame, on top of the cached picture (as the game draws them over its scenery tiles)
            Shape clip = g.getClip();
            g.clip(view);
            Lit lit = lit();
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                // (the rain running down wet panes leans with the wind slider; a damaged or snowed-in building has none)
                final boolean live = BuildingPainter.hasLive(e.kind), drops = !e.tree() && wet > 0;
                if (!live && !drops) continue;
                Rectangle2D.Double c0 = cellRects.get(i), c = new Rectangle2D.Double(view.x + c0.x, view.y + c0.y - scroll, c0.width, c0.height);
                if (c.y > view.y + view.height || c.y + c.height < view.y) continue;
                Shape cc = g.getClip();
                g.clip(c);
                double[] p = place(e, c);
                if (drops && !backRow) paintDrops(g, obj(e, i, ds.seed, 0, snow, wet, wind), lit, p[1], p[3], p[0], ds.anim, wind * WIND_MS);
                if (live && !backRow) {
                    paintLive(g, obj(e, i, ds.seed, 0, snow, wet, wind), lit, p[1], p[3], p[0], ds.anim);
                    paintLive(g, obj(e, i, ds.seed, damage, snow, wet, wind), lit, p[2], p[3], p[0], ds.anim);
                }
                g.setClip(cc);
            }
            g.setClip(clip);
        }

        @Override void wheel(int x, int y, double rotation) { if (hit != null) hit.wheel(x, y, rotation); else scroll += rotation * 60; }

        @Override void mouse(int type, int x, int y, int button) { if (hit != null) hit.mouse(type, x, y, button); }

        @Override void key(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_LEFT -> step(-1);           // Phase O: down and up the damage ladder
                case KeyEvent.VK_RIGHT -> step(1);
                case KeyEvent.VK_DOWN -> scroll += 60;
                case KeyEvent.VK_UP -> scroll -= 60;
                case KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_SPACE -> scroll += viewH * 0.9;
                case KeyEvent.VK_PAGE_UP -> scroll -= viewH * 0.9;
                case KeyEvent.VK_HOME -> scroll = 0;
                case KeyEvent.VK_END -> scroll = contentH;
                case KeyEvent.VK_L -> light = (light + 1) % 3;
                default -> { }
            }
        }

        @Override void chrome(Ui ui, Rectangle2D.Double r) {
            if (hit != null) {
                // the hit scene: its caption above the picture, one button to go back
                Model.Frame f = hit.frame();
                Rectangle2D.Double pic = DebugWorlds.below(view);
                ui.text("HIT", pic.x + 2, pic.y - 8, Ui.F_BOLD, Ui.ACCENT);
                ui.textFit(f == null ? "" : String.format(Locale.ROOT, "t %.0f s, %d tornado%s on the map: the buildings come apart, the pieces fall back, the rubble and the road wear stay (drag to pan, wheel to zoom)",
                        f.t, f.tornadoes.size(), f.tornadoes.size() == 1 ? "" : "es"), pic.x + 40, pic.y - 8, pic.width - 44, Ui.F_SMALL, Ui.DIM);
                double[] sd = DebugScreen.side(ui, r);
                DebugScreen.label(ui, "THE WRECK'S HIT SCENE", sd[0], sd[1]);
                DebugScreen.pick(ui, "Back to the line-up", false, sd[0], sd[1] + 18, sd[2], 30, () -> hit(false));
                DebugScreen.pick(ui, "Play it again", false, sd[0], sd[1] + 54, sd[2], 30, () -> hit(true));
                return;
            }
            Shape clip = ui.g.getClip();
            ui.g.clip(view);
            for (double[] h : headers) {
                double y = view.y + h[0] - scroll;
                int gi = (int) h[1];
                int n = 0;
                for (Entry e : entries) if (e.group.equals(GROUPS[gi])) n++;
                ui.text(GROUPS[gi] + "   " + n + (gi == 0 ? " kinds" : " variants"), view.x + 2, y + 17, Ui.F_BOLD, Ui.ACCENT);
            }
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                Rectangle2D.Double c0 = cellRects.get(i), c = new Rectangle2D.Double(view.x + c0.x, view.y + c0.y - scroll, c0.width, c0.height);
                if (c.y > view.y + view.height || c.y + c.height < view.y) continue;
                ui.stroke(c.x, c.y, c.width, c.height, 10, new Color(255, 255, 255, 40), 1f);
                ui.g.setFont(Ui.F_MEDB);
                HurricanePainter.shadowText(ui.g, e.label, c.x + 9, c.y + 18, Color.WHITE);
                ui.g.setFont(Ui.F_SMALL);
                HurricanePainter.shadowText(ui.g, String.format(Locale.ROOT, "%.0f x %.0f px%s", e.w, e.h, e.floors > 1 ? ", " + e.floors + " floors" : ""), c.x + 9, c.y + 32, new Color(226, 234, 246));
                // (with a shadow: the ground under them is green by day, nearly white under snow)
                String dl = String.format(Locale.ROOT, "damage %.0f %%", damage * 100);
                HurricanePainter.shadowText(ui.g, "intact", Math.max(c.x + 4, place(e, c)[1] - ui.width("intact", Ui.F_SMALL) / 2.0), c.y + c.height - 9, new Color(244, 248, 255));
                HurricanePainter.shadowText(ui.g, dl, c.x + c.width * damagedAt(e) - ui.width(dl, Ui.F_SMALL) / 2.0, c.y + c.height - 9, new Color(244, 248, 255));
            }
            ui.g.setClip(clip);
            if (contentH > viewH + 1) {
                double th = Math.max(30, viewH * viewH / contentH), ty = view.y + (viewH - th) * (scroll / (contentH - viewH));
                ui.fill(view.x + view.width - 6, view.y, 6, viewH, 6, new Color(255, 255, 255, 18));
                ui.fill(view.x + view.width - 6, ty, 6, th, 6, new Color(255, 255, 255, 90));
            }

            double[] s = DebugScreen.side(ui, r);
            double x = s[0], y = s[1], w = s[2];
            DebugScreen.label(ui, "LIGHT  (windows light up at night)", x, y);
            y += 16;
            ui.seg(DebugScreen.LIGHTS, hour >= 0 ? -1 : light, x, y, w, 30, k -> { light = k; hour = -1; });
            y += 36;
            // Phase O: any hour of the day (the people go in at dusk, the windows light up one by one)
            DebugScreen.label(ui, hour >= 0 ? String.format(Locale.ROOT, "HOUR   %04.1f h", hour) : "HOUR   (as the buttons above)", x, y);
            y += 14;
            ui.slider(hour >= 0 ? hour / 24 : DebugScreen.LIGHT_TOD[light], x, y, w, v -> hour = Math.round(v * 96) / 4.0);
            y += 26;
            y += ds.seedRow(ui, "SEED  (windows, doors, chimneys, colours)", x, y, w) + 8;
            // the damage ladder: the slider, or one step at a time (the buttons, or the arrow keys)
            DebugScreen.label(ui, String.format(Locale.ROOT, "DAMAGE  %.0f %%   LADDER  k %d/%d", damage * 100, k(), FloatTest.LEVELS), x, y);
            DebugScreen.pick(ui, "-", false, x + w - 50, y - 4, 23, 18, () -> step(-1));
            DebugScreen.pick(ui, "+", false, x + w - 24, y - 4, 23, 18, () -> step(1));
            y += 16;
            ui.slider(damage, x, y, w, v -> damage = Math.round(v * 100) / 100.0);
            y += 26;
            double tw = (w - 12) / 3;
            DebugScreen.pick(ui, "roof 30 %", Math.abs(damage - 0.3) < 0.005, x, y, tw, 26, () -> damage = 0.3);
            DebugScreen.pick(ui, "torn 60 %", Math.abs(damage - 0.6) < 0.005, x + tw + 6, y, tw, 26, () -> damage = 0.6);
            DebugScreen.pick(ui, "ruin 100 %", Math.abs(damage - 1) < 0.005, x + 2 * (tw + 6), y, tw, 26, () -> damage = 1);
            y += 32;
            // Phase O: the floating-pieces overlay, the back row's picture, the people; the wreck's scene
            DebugScreen.pick(ui, floats ? "Floating: " + floating : "Floating", floats, x, y, tw, 26, () -> floats = !floats);
            DebugScreen.pick(ui, "Back row", backRow, x + tw + 6, y, tw, 26, () -> backRow = !backRow);
            DebugScreen.pick(ui, "People", people, x + 2 * (tw + 6), y, tw, 26, () -> people = !people);
            y += 30;
            DebugScreen.pick(ui, "Hit: play the tornado scene", false, x, y, w, 26, () -> hit(true));
            y += 34;
            DebugScreen.label(ui, String.format(Locale.ROOT, "SNOW COVER   %.0f %%", snow * 100), x, y);
            y += 14;
            ui.slider(snow, x, y, w, v -> snow = Math.round(v * 50) / 50.0);
            y += 26;
            DebugScreen.label(ui, String.format(Locale.ROOT, "WET   %.0f %%", wet * 100), x, y);
            y += 14;
            ui.slider(wet, x, y, w, v -> wet = Math.round(v * 50) / 50.0);
            y += 26;
            DebugScreen.label(ui, String.format(Locale.ROOT, "WIND   %.0f %%, %.0f m/s  (sails, trees, rain on wet panes)", wind * 100, wind * WIND_MS), x, y);
            y += 14;
            ui.slider(wind, x, y, w, v -> wind = Math.round(v * 50) / 50.0);
            y += 30;
            if (y + 34 < r.y + r.height - 8) {
                ui.textFit(entries.size() + " entries: " + BuildingPainter.KINDS.length + " kinds, " + BuildingPainter.HOUSE_VARIANTS.length + " houses, "
                        + BuildingPainter.TREE_VARIANTS.length + " plants", x, y + 4, w, Ui.F_SMALL, Ui.FAINT);
                ui.textFit("Wheel / ↑ ↓ / PgUp PgDn scroll  ·  ← → ladder  ·  L light", x, y + 19, w, Ui.F_SMALL, Ui.FAINT);
            }
        }
    }

    // =====================================================================================

    /** The Cars tab. */
    static final class Cars extends DebugScreen.Tab {
        static final String[] COLUMNS = {"driving →", "driving ←", "stopped", "snow-covered", "tossed", "wrecked"};
        private final DebugScreen ds;
        int light;
        boolean lights = true, wipers = true, glow = true, tumble = true;
        double wet = 0.6, snow = 0.8, lift = 0.35, spin = 0.2;
        /** The wind on the wet car of the stopped column, m/s (positive blows to the right): its beads and running drops lean with it. */
        double wind;
        static final double WIND_MAX = 14;
        /** {@code variant/column} of every car drawn in the last frame (tests). */
        final List<String> drawn = new ArrayList<>();
        private Rectangle2D.Double view = new Rectangle2D.Double();
        private double headH = 22, rowH = 70, colW = 120, labelW = 84;

        Cars(DebugScreen ds) { this.ds = ds; }

        @Override void option(String key, String value) {
            switch (key) {
                case "light" -> light = Math.max(0, java.util.Arrays.asList("day", "dusk", "night").indexOf(value));
                case "lights" -> lights = !value.equals("off");
                case "glow" -> glow = !value.equals("off");
                case "wet" -> wet = Double.parseDouble(value);
                case "wind" -> wind = Double.parseDouble(value);
                default -> { }
            }
        }

        /** The car of one cell, as the simulation would report it. */
        static Model.Car car(String variant, int column, long seed, boolean lights, boolean wipers, double wet, double snow) {
            Model.Car c = new Model.Car();
            c.id = java.util.Arrays.asList(CarPainter.VARIANTS).indexOf(variant) + 1;
            c.variant = variant;
            c.color = FakeSim.debugCarColor(variant, seed);
            c.dir = column == 1 ? -1 : 1;
            c.lights = lights;
            switch (column) {
                case 0, 1 -> { c.state = "driving"; c.speed = 34; }
                case 2 -> { c.state = "stopped"; c.speed = 0; c.wipers = wipers; c.wet = wet; }
                case 3 -> { c.state = "stopped"; c.speed = 0; c.snow = snow; }
                case 4 -> { c.state = "tossed"; c.speed = 0; }
                default -> { c.state = "wrecked"; c.speed = 0; c.lights = false; }
            }
            return c;
        }

        /** Draw one car with its wheels on (x, y), at {@code zoom}; the second pass is the lamps' glow (as under a storm cloud). */
        static void paintCar(Graphics2D g, Model.Car c, double x, double y, double zoom, double liftPx, double spinRad, Lit lit, double anim, boolean glowPass) {
            paintCar(g, c, x, y, zoom, liftPx, spinRad, lit, anim, glowPass, 0);
        }

        /** @param wind the wind at the car, m/s (the water on a wet car leans with it) */
        static void paintCar(Graphics2D g, Model.Car c, double x, double y, double zoom, double liftPx, double spinRad, Lit lit, double anim, boolean glowPass, double wind) {
            AffineTransform old = g.getTransform();
            g.translate(x, y);
            g.scale(zoom, zoom);
            CarPainter.paint(g, c, 0, 0, 0, liftPx, spinRad, lit, anim, zoom, false, wind);
            if (glowPass) CarPainter.paint(g, c, 0, 0, 0, liftPx, spinRad, lit, anim, zoom, true);
            g.setTransform(old);
        }

        @Override void scene(Graphics2D g, BufferedImage buf, double scale, Rectangle2D.Double r, double dt) {
            view = DebugScreen.main(r);
            Lit lit = new Lit(DebugScreen.LIGHT_TOD[light], 0);
            int rows = CarPainter.VARIANTS.length, cols = COLUMNS.length;
            labelW = view.width < 760 ? 70 : 92;
            colW = (view.width - labelW) / cols;
            rowH = Math.min(118, (view.height - headH) / rows);
            double zoom = Math.max(1.2, Math.min(3.2, Math.min((colW - 14) / 70.0, (rowH - 18) / 22.0)));
            drawn.clear();
            for (int i = 0; i < rows; i++) {
                String variant = CarPainter.VARIANTS[i];
                double y0 = view.y + headH + i * rowH;
                for (int k = 0; k < cols; k++) {
                    Rectangle2D.Double c = new Rectangle2D.Double(view.x + labelW + k * colW + 2, y0 + 2, colW - 4, rowH - 4);
                    Shape clip = g.getClip();
                    g.clip(new RoundRectangle2D.Double(c.x, c.y, c.width, c.height, 8, 8));
                    DebugScreen.sky(g, c.x, c.y, c.width, c.height, lit);
                    // the road: asphalt with a centre line (wet darkens it, snow lies on it in the snow column)
                    double roadY = c.y + c.height - 12;
                    Color asphalt = lit.lit(new Color(74, 76, 82));
                    if (k == 2) asphalt = Lit.mix(asphalt, Lit.mul(asphalt, 0.7), wet);
                    if (k == 3) asphalt = Lit.mix(asphalt, lit.lit(new Color(236, 240, 248)), snow * 0.8);
                    g.setColor(asphalt);
                    g.fill(new Rectangle2D.Double(c.x, roadY, c.width, 12));
                    g.setColor(Lit.alpha(lit.lit(new Color(226, 222, 200)), 170));
                    g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{9f, 9f}, (float) (k < 2 ? Math.floorMod((long) (ds.anim * 40 * (k == 0 ? 1 : -1) * 16), 18L * 16) / 16.0 : 0)));
                    g.draw(new Line2D.Double(c.x, roadY + 6, c.x + c.width, roadY + 6));
                    Model.Car car = car(variant, k, ds.seed, lights, wipers, wet, snow);
                    double lf = k == 4 ? lift * (c.height - 30) / zoom : 0;
                    double sp = k == 4 ? (tumble ? ds.anim * 2.4 : spin * Math.PI * 2) : k == 5 ? (car.id % 2 == 0 ? Math.PI : 0.18) : 0;
                    // a car facing left has its lamps on the left: it stands a little right of the middle, and the other way round
                    double cx = c.x + c.width / 2 - (k < 4 ? car.dir * c.width * 0.10 : 0);
                    paintCar(g, car, cx, roadY, zoom, lf, sp, lit, ds.anim, glow && car.lights, wind);
                    g.setClip(clip);
                    drawn.add(variant + "/" + k);
                }
            }
        }

        @Override void key(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_L -> light = (light + 1) % 3;
                case KeyEvent.VK_H -> lights = !lights;
                case KeyEvent.VK_SPACE -> tumble = !tumble;
                default -> { }
            }
        }

        @Override void chrome(Ui ui, Rectangle2D.Double r) {
            for (int k = 0; k < COLUMNS.length; k++)
                ui.textCenter(ui.fit(COLUMNS[k], Ui.F_BOLD, colW - 6), view.x + labelW + (k + 0.5) * colW, view.y + 15, Ui.F_BOLD, Ui.ACCENT);
            for (int i = 0; i < CarPainter.VARIANTS.length; i++) {
                String v = CarPainter.VARIANTS[i];
                double y = view.y + headH + (i + 0.5) * rowH;
                ui.text(v, view.x + 2, y, Ui.F_MEDB, Color.WHITE);
                ui.text(String.format(Locale.ROOT, "%.0f px long", CarPainter.length(v)), view.x + 2, y + 15, Ui.F_SMALL, Ui.DIM);
            }
            double[] s = DebugScreen.side(ui, r);
            double x = s[0], y = s[1], w = s[2];
            DebugScreen.label(ui, "LIGHT", x, y);
            y += 16;
            ui.seg(DebugScreen.LIGHTS, light, x, y, w, 30, k -> light = k);
            y += 40;
            y += DebugScreen.toggleRow(ui, "Headlights and tail lights", lights, x, y, w, () -> lights = !lights);
            y += DebugScreen.toggleRow(ui, "Lamp glow pass (storm gloom)", glow, x, y, w, () -> glow = !glow);
            y += DebugScreen.toggleRow(ui, "Wipers (the stopped column)", wipers, x, y, w, () -> wipers = !wipers);
            y += 6;
            y += ds.seedRow(ui, "SEED  (body colours)", x, y, w) + 10;
            DebugScreen.label(ui, String.format(Locale.ROOT, "WET   %.0f %%  (the stopped column)", wet * 100), x, y);
            y += 14;
            ui.slider(wet, x, y, w, v -> wet = Math.round(v * 50) / 50.0);
            y += 28;
            DebugScreen.label(ui, String.format(Locale.ROOT, "WIND   %+.0f m/s  (the rain on the wet car leans)", wind), x, y);
            y += 14;
            ui.slider(wind / (2 * WIND_MAX) + 0.5, x, y, w, v -> wind = Math.round((v - 0.5) * 2 * WIND_MAX));
            y += 28;
            DebugScreen.label(ui, String.format(Locale.ROOT, "SNOW   %.0f %%  (the snow column)", snow * 100), x, y);
            y += 14;
            ui.slider(snow, x, y, w, v -> snow = Math.round(v * 50) / 50.0);
            y += 28;
            DebugScreen.label(ui, String.format(Locale.ROOT, "TOSSED: LIFT   %.0f %%", lift * 100), x, y);
            y += 14;
            ui.slider(lift, x, y, w, v -> lift = Math.round(v * 50) / 50.0);
            y += 28;
            y += DebugScreen.toggleRow(ui, "Tossed: tumble", tumble, x, y, w, () -> tumble = !tumble);
            if (!tumble && y + 40 < r.y + r.height - 8) {
                DebugScreen.label(ui, String.format(Locale.ROOT, "TOSSED: SPIN   %.0f°", spin * 360), x, y);
                y += 14;
                ui.slider(spin, x, y, w, v -> spin = Math.round(v * 72) / 72.0);
                y += 28;
            }
            if (y + 20 < r.y + r.height - 8)
                ui.textFit(CarPainter.VARIANTS.length + " variants x " + COLUMNS.length + " states  ·  L light  ·  H lamps", x, y + 6, w, Ui.F_SMALL, Ui.FAINT);
        }
    }
}
