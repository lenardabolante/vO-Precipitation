package precip;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoublePredicate;
import java.util.function.DoubleUnaryOperator;

/**
 * Phase O (OH8): the people. Checks of {@link Cast} (who is where, the presence rule), {@link Figures}, the people in the
 * still picture of a building ({@link BuildingPainter}) and the live pass ({@link Life}): the presence table, that the
 * same building always has the same people (render bands, a reload), that nothing is drawn below
 * {@link Life#LIFE_MIN_PX}, that walkers keep to the street spans of their settlement and go indoors for rain, night,
 * flood and tornado, that a shadow crossing a window never leaves it, and what the live pass costs.
 * <p>
 * Run by its own {@code main} ({@code java -cp viewer/out_houses precip.LifeTest}); {@link #run} is the entry for
 * {@code --selftest-ui}. The same class renders the evidence pictures ({@code --variants}, {@code --street}).
 */
final class LifeTest {
    private LifeTest() {}

    private static int fails;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) fails++;
    }

    static final double DAY = 0.45, EVENING = 0.725, DUSK = 0.76, NIGHT = 0.02;

    private static DebugLineup.Entry entry(String label) {
        for (DebugLineup.Entry e : DebugLineup.entries()) if (e.label.equals(label)) return e;
        throw new IllegalArgumentException(label);
    }

    private static int indexOf(DebugLineup.Entry e) {
        List<DebugLineup.Entry> all = DebugLineup.entries();
        for (int i = 0; i < all.size(); i++) if (all.get(i).label.equals(e.label)) return i;
        return 0;
    }

    /** A building of the line-up standing at world (x, y). */
    static Model.Obj house(String label, long seed, double x, double y, int layer, double damage, double wet, double snow) {
        DebugLineup.Entry e = entry(label);
        Model.Obj o = DebugLineup.obj(e, indexOf(e), seed, damage, snow, wet, 0);
        o.id = 1000 + seed * 131 + Math.round(x);
        o.x = x; o.y = y; o.layer = layer;
        return o;
    }

    /** A flat world: one settlement over the whole of it, a street from 100 to w - 100 and a highway beyond it on both sides. */
    static Scene scene(List<Model.Obj> objs, double w, double groundY, Lit lit, int population, double wet, double snow) {
        Model.Hello h = new Model.Hello();
        h.w = (int) w; h.h = 1080; h.groundY = groundY; h.seed = 7;
        h.terrain = Model.Terrain.flat((int) w, groundY);
        Model.Settlement s = new Model.Settlement();
        s.id = 1; s.kind = "town"; s.name = "Testby"; s.x0 = 60; s.x1 = w - 60; s.population = population;
        h.settlements.add(s);
        Model.Road st = new Model.Road();
        st.x0 = 100; st.x1 = w - 100; st.kind = "street";
        Model.Road hwL = new Model.Road(), hwR = new Model.Road();
        hwL.x0 = 0; hwL.x1 = 100; hwL.kind = "highway"; hwR.x0 = w - 100; hwR.x1 = w; hwR.kind = "highway";
        h.roads.add(hwL); h.roads.add(st); h.roads.add(hwR);
        h.objects.addAll(objs);
        Model.Ground gr = new Model.Ground();
        int n = (int) (w / gr.step) + 2;
        gr.wet = new double[n]; gr.snow = new double[n];
        java.util.Arrays.fill(gr.wet, wet); java.util.Arrays.fill(gr.snow, snow);
        return new Scene(h, objs.toArray(new Model.Obj[0]), gr, lit, -1e9);
    }

    /** The street of the pictures and of the tests: eight houses in the front row, four in the back, a lamp or two. */
    static List<Model.Obj> street(double groundY, double wet, double snow, double damage) {
        List<Model.Obj> l = new ArrayList<>();
        String[] back = {"apartment", "house:townhouse", "house:manor", "house:townhouse"};
        double[] bx = {250, 470, 700, 930};
        for (int i = 0; i < back.length; i++) l.add(house(back[i], 20 + i, bx[i], groundY - 6, 1, damage, wet, snow));
        String[] front = {"house:cottage", "house:farmhouse", "house:bungalow", "house:cabin", "house:townhouse", "house:cottage", "house:manor", "house:bungalow"};
        double x = 150;
        for (int i = 0; i < front.length; i++) {
            DebugLineup.Entry e = entry(front[i]);
            x += e.w / 2;
            l.add(house(front[i], 3 + i, x, groundY, 0, damage, wet, snow));
            x += e.w / 2 + 44;
        }
        l.add(house("streetlamp", 1, 128, groundY, 0, damage, wet, snow));
        l.add(house("streetlamp", 2, x - 16, groundY, 0, damage, wet, snow));
        return l;
    }

    static void hints(Graphics2D g) { FloatTest.hints(g); }

    /**
     * A picture of a scene as the game composes it: the sky, the ground tile painter ({@code Renderer.paintGround}: back
     * row, terrain, front row, roads), then the live people.
     */
    static BufferedImage picture(Scene sc, double wx0, double wy0, double wx1, double wy1, double scale, double anim, boolean live, DoublePredicate sheltered, DoubleUnaryOperator flood, int bands) {
        int iw = (int) Math.round((wx1 - wx0) * scale), ih = (int) Math.round((wy1 - wy0) * scale);
        BufferedImage img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
        for (int b = 0; b < bands; b++) {
            Graphics2D g = img.createGraphics();
            hints(g);
            int y0 = ih * b / bands, y1 = ih * (b + 1) / bands;
            g.setClip(0, y0, iw, y1 - y0);
            g.setPaint(new GradientPaint(0, 0, sc.lit.skyTop, 0, ih, sc.lit.skyBot));
            g.fillRect(0, 0, iw, ih);
            g.scale(scale, scale);
            g.translate(-wx0, -wy0);
            Renderer.paintGround(g, sc, wx0, wy0, wx1, wy1, scale, 1);
            if (live) Life.paintWorld(g, sc, scale, wx0, wx1, anim, sc.lit, sheltered, flood);
            g.dispose();
        }
        return img;
    }

    /** Only the live people of a scene, on a transparent canvas. */
    static BufferedImage liveOnly(Scene sc, double wx0, double wy0, double wx1, double wy1, double scale, double anim, DoublePredicate sheltered, DoubleUnaryOperator flood, int bands) {
        int iw = (int) Math.round((wx1 - wx0) * scale), ih = (int) Math.round((wy1 - wy0) * scale);
        BufferedImage img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
        for (int b = 0; b < bands; b++) {
            Graphics2D g = img.createGraphics();
            hints(g);
            g.setClip(0, ih * b / bands, iw, ih * (b + 1) / bands - ih * b / bands);
            g.scale(scale, scale);
            g.translate(-wx0, -wy0);
            Life.paintWorld(g, sc, scale, wx0, wx1, anim, sc.lit, sheltered, flood);
            g.dispose();
        }
        return img;
    }

    /** One building alone on a transparent canvas, as a ground tile paints it, at a life level (-1 = from the light). */
    static BufferedImage alone(Model.Obj o, double scale, Lit lit, int life, int bands) {
        double side = o.w / 2 + 30, up = o.h * 1.7 + 30;
        int iw = (int) Math.ceil(2 * side * scale), ih = (int) Math.ceil((up + 16) * scale);
        BufferedImage img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
        for (int b = 0; b < bands; b++) {
            Graphics2D g = img.createGraphics();
            hints(g);
            g.setClip(0, ih * b / bands, iw, ih * (b + 1) / bands - ih * b / bands);
            g.translate(Math.round(side * scale), Math.round(up * scale));
            g.scale(scale, scale);
            g.translate(-o.x, -o.y);
            BuildingPainter.paint(g, o, lit, 0, 0, life);
            g.dispose();
        }
        return img;
    }

    static int diff(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return Integer.MAX_VALUE;
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++) if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
        return n;
    }

    private static int opaque(BufferedImage a) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++) if ((a.getRGB(x, y) >>> 24) > 8) n++;
        return n;
    }

    // =====================================================================================

    static int run() {
        fails = 0;
        long t0 = System.nanoTime();
        System.out.println("LifeTest (Phase O, OH5-OH8): the people");
        presence();
        determinism();
        belowThreshold();
        walkers();
        crossing();
        paused();
        cost();
        System.out.printf(Locale.ROOT, "LifeTest: %s (%d failed, %.1f s)%n", fails == 0 ? "PASSED" : "FAILED", fails, (System.nanoTime() - t0) / 1e9);
        return fails == 0 ? 0 : 1;
    }

    /** The presence rule as a table, and what follows from it for the still picture. */
    private static void presence() {
        Model.Obj o = house("house:farmhouse", 3, 500, 900, 0, 0, 0, 0);
        Object[][] rows = {
                // name, tod, wet, snow, sheltered, damage, people outside wanted
                {"day, dry", DAY, 0.0, 0.0, false, 0.0, true}, {"early evening, dry", EVENING, 0.0, 0.0, false, 0.0, true}, {"dusk", DUSK, 0.0, 0.0, false, 0.0, false},
                {"night", NIGHT, 0.0, 0.0, false, 0.0, false}, {"day, rain (wet 0.6)", DAY, 0.6, 0.0, false, 0.0, false}, {"day, damp (wet 0.2)", DAY, 0.2, 0.0, false, 0.0, true},
                {"day, snow 0.7", DAY, 0.0, 0.7, false, 0.0, false}, {"day, tornado near", DAY, 0.0, 0.0, true, 0.0, false}, {"day, damaged 0.3", DAY, 0.0, 0.0, false, 0.3, false},
                {"rainy night", NIGHT, 0.8, 0.0, false, 0.0, false}};
        StringBuilder table = new StringBuilder("  presence (farmhouse): ");
        boolean ok = true;
        for (Object[] r : rows) {
            Model.Obj p = house("house:farmhouse", 3, 500, 900, 0, (Double) r[5], (Double) r[2], (Double) r[3]);
            int lv = Life.level(p, new Lit((Double) r[1], 0), (Boolean) r[4]);
            table.append(r[0]).append(" -> level ").append(lv).append("; ");
            ok &= (lv > 0) == (Boolean) r[6];
        }
        System.out.println(table);
        check(ok, "the outdoor life level is 0 at dusk, at night, in rain, in snow, near a tornado and at a damaged building, and above 0 by day and in the early evening");
        int l1 = Life.level(o, new Lit(EVENING, 0), false), l4 = Life.level(o, new Lit(0.5, 0), false);
        check(l4 == Cast.LEVELS && l1 >= 1 && l1 < l4, "the level follows the daylight in steps (noon " + l4 + ", early evening " + l1 + ")");
        // over many houses: the share of outdoor spots taken by level, the people a house has, the figure windows
        int[] taken = new int[Cast.LEVELS + 1];
        int spots = 0, windows = 0, figWindows = 0, minPeople = 99, maxPeople = 0, houses = 0, overCap = 0, withOutdoor = 0;
        for (String v : BuildingPainter.HOUSE_VARIANTS) for (long seed = 1; seed <= 40; seed++) {
            Model.Obj h = house("house:" + v, seed, 400, 900, 0, 0, 0, 0);
            Cast.Spot[] ss = Cast.spots(h);
            int n = Cast.people(h), fw = 0, out = 0;
            minPeople = Math.min(minPeople, n); maxPeople = Math.max(maxPeople, n);
            houses++;
            for (Cast.Spot s : ss) {
                if (s.type == Cast.WINDOW) { if (s.big) windows++; if (s.figure) { figWindows++; fw++; } continue;}
                spots++; out++;
                for (int lv = 1; lv <= Cast.LEVELS; lv++) if (Cast.out(h, s, lv)) taken[lv]++;
            }
            if (out > 0) withOutdoor++;
            if (fw > n) overCap++;
        }
        check(withOutdoor == houses, "every house variant has a place outdoors for someone (" + withOutdoor + " of " + houses + " houses; " + spots + " spots)");
        check(taken[1] < taken[2] && taken[2] < taken[3] && taken[3] < taken[4] && taken[4] > spots * 0.55 && taken[1] > spots * 0.2,
                String.format(Locale.ROOT, "more of the outdoor spots are taken the higher the level: %.0f / %.0f / %.0f / %.0f %% of %d", 100.0 * taken[1] / spots, 100.0 * taken[2] / spots, 100.0 * taken[3] / spots, 100.0 * taken[4] / spots, spots));
        check(minPeople >= 1 && maxPeople <= 5, "a house has 1 to 5 people (" + minPeople + " to " + maxPeople + ")");
        check(overCap == 0 && figWindows > windows * 0.3 && figWindows < windows * 0.62,
                String.format(Locale.ROOT, "about half of the windows big enough show a figure when lit, never more than the house has people (%d of %d, %.0f %%; %d houses over)", figWindows, windows, 100.0 * figWindows / windows, overCap));
        // a rainy night: shadows at the windows, nobody outside; a ruin: nobody at all
        Lit night = new Lit(NIGHT, 0);
        int shadows = 0, outside = 0, ruinFigures = 0;
        for (long seed = 1; seed <= 12; seed++) {
            Model.Obj wetHouse = house("house:farmhouse", seed, 400, 900, 0, 0, 0.8, 0), ruin = house("house:farmhouse", seed, 400, 900, 0, 0.5, 0, 0);
            int lv = Life.level(wetHouse, night, false);
            Cast.Spot[] ss = Cast.spots(wetHouse);
            for (int i = 0; i < ss.length; i++) {
                if (ss[i].type == Cast.WINDOW) { if (Cast.lit(wetHouse, ss[i], night) && Cast.windowFigure(wetHouse, i)) shadows++; }
                else if (Cast.out(wetHouse, ss[i], lv)) outside++;
            }
            Cast.Spot[] rs = Cast.spots(ruin);
            int rl = Life.level(ruin, new Lit(DAY, 0), false);
            for (int i = 0; i < rs.length; i++) if (rs[i].type == Cast.WINDOW ? Cast.windowFigure(ruin, i) : Cast.out(ruin, rs[i], rl)) ruinFigures++;
        }
        check(shadows > 0 && outside == 0, "a rainy night: " + shadows + " shadows at the lit windows of 12 farmhouses, " + outside + " people outside");
        check(ruinFigures == 0, "a building at damage 0.5 has no figure anywhere (" + ruinFigures + ")");
        // the picture: a figure outside changes the still picture by day and not at night
        Model.Obj f = house("house:farmhouse", 3, 500, 900, 0, 0, 0, 0);
        int byDay = diff(alone(f, 3, new Lit(DAY, 0), 0, 1), alone(f, 3, new Lit(DAY, 0), 4, 1)), auto = diff(alone(f, 3, new Lit(DAY, 0), -1, 1), alone(f, 3, new Lit(DAY, 0), 4, 1));
        check(byDay > 20 && auto == 0, "the still picture at life level 4 has people the picture at level 0 has not (" + byDay + " px), and level -1 takes it from the light (" + auto + " px differ)");
        BufferedImage plain = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB), zero = alone(f, 3, new Lit(DAY, 0), 0, 1);
        plain = new BufferedImage(zero.getWidth(), zero.getHeight(), BufferedImage.TYPE_INT_ARGB);
        {
            Graphics2D pg = plain.createGraphics();
            hints(pg);
            pg.translate(Math.round((f.w / 2 + 30) * 3), Math.round((f.h * 1.7 + 30) * 3));
            pg.scale(3, 3);
            pg.translate(-f.x, -f.y);
            BuildingPainter.paint(pg, f, new Lit(DAY, 0), 0, 0);
            pg.dispose();
        }
        check(diff(plain, zero) == 0, "a picture painted without a life level (the wreck's still pictures) has nobody outdoors: it equals level 0");
        Model.Obj ruin = house("house:farmhouse", 3, 500, 900, 0, 0.5, 0, 0);
        check(diff(alone(ruin, 3, new Lit(DAY, 0), 0, 1), alone(ruin, 3, new Lit(DAY, 0), 4, 1)) == 0, "a damaged building is painted the same at every life level (nobody stands outside a ruin)");
    }

    /** The same people in one band and in four, after a reload, at every call. */
    private static void determinism() {
        int bandDiff = 0, reloadDiff = 0, n = 0;
        for (String v : BuildingPainter.HOUSE_VARIANTS) for (double tod : new double[]{DAY, NIGHT}) {
            Model.Obj o = house("house:" + v, 5, 300.4, 900.3, 0, 0, 0, 0);
            Lit lit = new Lit(tod, 0);
            BufferedImage one = alone(o, 3, lit, -1, 1);
            bandDiff += diff(one, alone(o, 3, lit, -1, 4));
            // a reload: the same object read again from the save (a new instance with the same numbers)
            Model.Obj again = o.withDamage(o.damage);
            reloadDiff += diff(one, alone(again, 3, lit, -1, 1));
            n++;
        }
        check(bandDiff == 0, "a house has the same people painted in 1 band and in 4 (" + bandDiff + " differing px over " + n + " pictures)");
        check(reloadDiff == 0, "and after a reload (" + reloadDiff + " differing px)");
        Lit eve = new Lit(EVENING, 0);
        Scene sc = scene(street(900, 0, 0, 0), 1400, 900, eve, 900, 0, 0);
        int liveDiff = 0, sceneDiff = 0;
        for (double anim : new double[]{3.7, 41.2, 133.9}) {
            liveDiff += diff(liveOnly(sc, 100, 760, 1100, 915, 2, anim, null, null, 1), liveOnly(sc, 100, 760, 1100, 915, 2, anim, null, null, 4));
            sceneDiff += diff(picture(sc, 100, 760, 1100, 915, 2, anim, true, null, null, 1), picture(sc, 100, 760, 1100, 915, 2, anim, true, null, null, 4));
        }
        check(liveDiff == 0 && sceneDiff == 0, "the live pass and the whole street draw the same frame in 1 band and in 4 (" + liveDiff + " and " + sceneDiff + " differing px over 3 moments)");
        check(diff(liveOnly(sc, 100, 760, 1100, 915, 2, 41.2, null, null, 1), liveOnly(sc, 100, 760, 1100, 915, 2, 41.7, null, null, 1)) > 0, "and it moves: half a second later the frame is another");
    }

    /** Below LIFE_MIN_PX: no figure, no live pass. */
    private static void belowThreshold() {
        Lit day = new Lit(DAY, 0);
        int d = 0;
        for (String v : BuildingPainter.HOUSE_VARIANTS) {
            Model.Obj o = house("house:" + v, 4, 300, 900, 0, 0, 0, 0);
            d += diff(alone(o, 1.5, day, 0, 1), alone(o, 1.5, day, 4, 1)) + diff(alone(o, 1.0, day, 0, 1), alone(o, 1.0, day, 4, 1));
        }
        check(d == 0, "below " + Life.LIFE_MIN_PX + " device px per world px the still picture has no people at any life level (" + d + " differing px at scale 1 and 1.5)");
        Scene sc = scene(street(900, 0, 0, 0), 1400, 900, day, 900, 0, 0);
        check(opaque(liveOnly(sc, 100, 760, 1100, 915, 1.5, 12, null, null, 1)) == 0 && Life.stats().walkers == 0, "and the live pass draws nothing");
        check(opaque(liveOnly(sc, 100, 760, 1100, 915, 1.6, 12, null, null, 1)) > 0 && Life.stats().walkers > 0, "from " + Life.LIFE_MIN_PX + " on it does (" + Life.stats().walkers + " walkers, " + Life.stats().idle + " people at their houses)");
    }

    /** Walkers: how many, where, and when they stay in. */
    private static void walkers() {
        final double w = 1400, gy = 900;
        Lit day = new Lit(DAY, 0);
        Scene sc = scene(street(gy, 0, 0, 0), w, gy, day, 900, 0, 0);
        int want = Life.walkerCount(sc.hello.settlements.get(0));
        check(want == 10, "a settlement of 900 people has clamp(round(sqrt(900) / 3), 1, 12) = 10 walkers (" + want + ")");
        Model.Settlement big = new Model.Settlement(), small = new Model.Settlement();
        big.population = 40000; small.population = 3;
        check(Life.walkerCount(big) == Life.WALK_MAX && Life.walkerCount(small) == 1, "at most " + Life.WALK_MAX + ", at least 1");
        // a ten-minute walk: always on the street span, inside the settlement, on the kerb line, at walking pace
        double[] out = new double[1 + 64 * Life.WALKER_N], prev = null;
        int bad = 0, offKerb = 0, frames = 0, dogs = 0, tooFast = 0;
        double minX = 1e9, maxX = -1e9;
        for (double anim = 0; anim < 600; anim += 0.25, frames++) {
            out = Life.walkers(sc, anim, day, null, null, out);
            int n = (int) out[0];
            if (n != want) bad++;
            for (int i = 0; i < n; i++) {
                double x = out[1 + i * Life.WALKER_N], y = out[2 + i * Life.WALKER_N];
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                if (x < 100 + Life.WALK_MARGIN - 1e-6 || x > w - 100 - Life.WALK_MARGIN + 1e-6) bad++;
                if (Math.abs(y - (sc.terrain.at(x) - Life.KERB)) > 1e-9) offKerb++;
                if (prev != null && (int) prev[0] == n && Math.abs(x - prev[1 + i * Life.WALKER_N]) > Life.WALK_V1 * 0.25 + 1e-6) tooFast++;
                if (anim == 0 && out[5 + i * Life.WALKER_N] > 0) dogs++;
            }
            prev = out.clone();
        }
        check(bad == 0 && offKerb == 0, String.format(Locale.ROOT, "over a 600 s walk every walker stays on the street span inside the settlement (x %.0f to %.0f of the span 100 to %.0f, %d px inside its ends) and on the kerb line (%d and %d faults in %d frames)",
                minX, maxX, w - 100, (int) Life.WALK_MARGIN, bad, offKerb, frames));
        check(tooFast == 0 && maxX - minX > 800, "at 14 to 20 px/s, never faster (" + tooFast + " jumps), and they cover the street");
        check(dogs >= 1 && dogs <= 5, dogs + " of the " + want + " have a dog (one in five wanted)");
        // none on a highway, none in a settlement without a street
        Scene none = scene(street(gy, 0, 0, 0), w, gy, day, 900, 0, 0);
        none.hello.roads.removeIf(r -> !r.highway());
        check((int) Life.walkers(none, 10, day, null, null, out)[0] == 0, "nobody walks on a highway");
        // the weather and the hour
        Object[][] rows = {{"day, dry", DAY, 0.0, 0.0, true}, {"early evening", EVENING, 0.0, 0.0, true}, {"dusk", DUSK, 0.0, 0.0, false}, {"night", NIGHT, 0.0, 0.0, false},
                {"rain (ground wet 0.5)", DAY, 0.5, 0.0, false}, {"damp (wet 0.15)", DAY, 0.15, 0.0, true}, {"snow 0.8", DAY, 0.0, 0.8, false}};
        boolean ok = true;
        StringBuilder sb = new StringBuilder("  walkers out: ");
        for (Object[] r : rows) {
            Lit lit = new Lit((Double) r[1], 0);
            Scene s2 = scene(street(gy, 0, 0, 0), w, gy, lit, 900, (Double) r[2], (Double) r[3]);
            int n = (int) Life.walkers(s2, 33, lit, null, null, out)[0];
            sb.append(r[0]).append(" ").append(n).append("; ");
            ok &= (n > 0) == (Boolean) r[4];
        }
        System.out.println(sb);
        check(ok, "walkers are out by day and in the early evening on dry ground only: none at dusk, at night, in rain or in snow");
        // a flood over 1 px: nobody in it
        DoubleUnaryOperator flood = x -> x > 500 && x < 900 ? 2.5 : 0.4;
        int inFlood = 0, outside = 0;
        for (double anim = 0; anim < 200; anim += 0.5) {
            out = Life.walkers(sc, anim, day, null, flood, out);
            for (int i = 0; i < (int) out[0]; i++) { double x = out[1 + i * Life.WALKER_N]; if (x > 500 && x < 900) inFlood++; else outside++; }
        }
        check(inFlood == 0 && outside > 0, "nobody walks where the water is over " + Life.WALK_FLOOD + " px deep (" + inFlood + " in it, " + outside + " beside it over 200 s)");
        // a tornado on the ground at x = 700 from t = 20 to t = 50, the wreck's shelter test holding 5 s after it lifts
        final double[] now = {0};
        DoublePredicate sheltered = x -> now[0] >= 20 && now[0] < 50 + Wreck.SHELTER_HOLD_S && Math.abs(x - 700) < Wreck.SHELTER_PX;
        int before = 0, during = 0, justAfter = 0, back = 0, idleDuring = 0, idleBefore = 0;
        for (now[0] = 0; now[0] < 80; now[0] += 0.5) {
            int n = (int) Life.walkers(sc, now[0], day, sheltered, null, out)[0];
            if (now[0] < 20) before += n; else if (now[0] < 50) during += n; else if (now[0] < 50 + Wreck.SHELTER_HOLD_S) justAfter += n; else if (now[0] >= 58) back += n;
        }
        now[0] = 10; liveOnly(sc, 100, 760, 1300, 915, 2, 10, sheltered, null, 1); idleBefore = Life.stats().idle;
        now[0] = 30; liveOnly(sc, 100, 760, 1300, 915, 2, 30, sheltered, null, 1); idleDuring = Life.stats().idle;
        check(before > 0 && during == 0 && justAfter == 0 && back > 0, "walkers are gone while a tornado is on the ground within " + (int) Wreck.SHELTER_PX + " px and back 8 s after it lifts (" + before + " / " + during + " / " + justAfter + " / " + back + ")");
        check(idleBefore > 0 && idleDuring == 0, "and so is everyone at the houses (" + idleBefore + " outside before, " + idleDuring + " during)");
    }

    /** A shadow crossing a window stays inside its window in every frame of a 90 s run. */
    private static void crossing() {
        Lit night = new Lit(NIGHT, 0);
        List<Model.Obj> objs = street(900, 0, 0, 0);
        Scene sc = scene(objs, 1400, 900, night, 900, 0, 0);
        final double scale = 3, wx0 = 100, wy0 = 760, wx1 = 1100, wy1 = 915;
        // the panes of the front row, in device px
        List<double[]> panes = new ArrayList<>();
        for (Model.Obj o : objs) if (o.layer != 1) for (Cast.Spot s : Cast.spots(o)) if (s.type == Cast.WINDOW) panes.add(new double[]{(s.x - wx0) * scale, (s.y - wy0) * scale, (s.x + s.w - wx0) * scale, (s.y + s.h - wy0) * scale});
        int outside = 0, frames = 0, crossings = 0, maxAtOnce = 0, walkersAtNight = 0;
        for (double anim = 0; anim < 90; anim += 0.1, frames++) {
            BufferedImage img = liveOnly(sc, wx0, wy0, wx1, wy1, scale, anim, null, null, 1);
            crossings += Life.stats().crossings; maxAtOnce = Math.max(maxAtOnce, Life.stats().crossings); walkersAtNight += Life.stats().walkers + Life.stats().idle;
            for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) == 0) continue;
                boolean in = false;
                for (double[] p : panes) if (x + 1 > p[0] && x < p[2] && y + 1 > p[1] && y < p[3]) { in = true; break; }
                if (!in) outside++;
            }
        }
        check(crossings > 0 && outside == 0, "over a 90 s night (" + frames + " frames) every pixel of every crossing shadow lies inside its window pane (" + crossings + " shadow-frames, at most " + maxAtOnce + " at once, " + outside + " px outside)");
        check(walkersAtNight == 0, "and nobody else is drawn at night");
        // back-row windows get no live shadow, damaged buildings none
        Scene ruins = scene(street(900, 0, 0, 0.5), 1400, 900, night, 900, 0, 0);
        int n = 0;
        for (double anim = 0; anim < 90; anim += 0.5) { liveOnly(ruins, wx0, wy0, wx1, wy1, 2, anim, null, null, 1); n += Life.stats().crossings + Life.stats().idle; }
        check(n == 0, "no shadow crosses a window of a damaged building (" + n + ")");
    }

    /** What the live pass costs with 12 walkers and 20 crossing windows in view. */
    // ------------------------------------------------------------------ a paused simulation: everyone stands still

    private static final int PW = 1100, PH = 520;

    private static ParallelRenderer gameRenderer(int bands) {
        ParallelRenderer pr = new ParallelRenderer(bands);
        pr.shared.setAsync(false);
        Renderer r = pr.primary();
        r.showRightPanel = false; r.showLeftPanel = false; r.showMinimap = false; r.showLabels = false; r.showArrows = false; r.showWind = false; r.showClimate = false;
        return pr;
    }

    /** One frame of the game as the window draws it: {@code nowNanos} is the wall clock (-1 = a still), {@code animSec} the animation clock. */
    private static int[] gameShot(ParallelRenderer pr, ViewerState st, Camera cam, long nowNanos, double animSec) {
        Advanced.publish(Advanced.DEFAULT);
        BufferedImage img = new BufferedImage(PW, PH, BufferedImage.TYPE_INT_RGB);
        pr.render(img, 1, 0, 0, PW, PH, st, cam, nowNanos, animSec, "test");
        int[] out = new int[PW * PH];
        img.getRGB(0, 0, PW, PH, out, 0, PW);
        return out;
    }

    /**
     * One layer of the picture (the people, or the flying pieces) at two moments. {@code a} / {@code b} are the two
     * frames with the layer, {@code a0} / {@code b0} the same two frames without it.
     * @return {pixels the layer covers in a, pixels it covers in only one of the two frames, pixels it covers in both
     *          but in another colour although the picture under them is the same}
     */
    private static int[] layer(int[] a, int[] a0, int[] b, int[] b0) {
        int n = 0, moved = 0, recoloured = 0;
        for (int i = 0; i < a.length; i++) {
            boolean inA = a[i] != a0[i], inB = b[i] != b0[i];
            if (inA) n++;
            if (inA != inB) moved++;
            else if (inA && a[i] != b[i] && a0[i] == b0[i]) recoloured++;
        }
        return new int[]{n, moved, recoloured};
    }

    private static double[] walkerXs(ParallelRenderer pr) {
        Shared sh = pr.shared;
        double[] w = Life.walkers(sh.scene, sh.lifeAnim, sh.lit, null, null, new double[1 + 64 * Life.WALKER_N]);
        double[] xs = new double[(int) w[0]];
        for (int i = 0; i < xs.length; i++) xs[i] = w[1 + i * Life.WALKER_N];
        return xs;
    }

    private static double maxStep(double[] a, double[] b) {
        if (a.length != b.length) return Double.POSITIVE_INFINITY;
        double m = 0;
        for (int i = 0; i < a.length; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
        return m;
    }

    /**
     * The player's report (2026-10-12): "people dont stop when the simulation is paused". The game's own renderer on the
     * fake simulation: while the frame says paused, the people of two frames 3 s of wall time apart are the same pixels;
     * they go on from where they stood; a change of speed moves nobody. The pieces a tornado has in the air stand still too.
     */
    private static void paused() {
        ParallelRenderer pr = gameRenderer(1), pr4 = gameRenderer(4);
        try {
            FakeSim sim = new FakeSim(1234, 0.45, 3840, 1080, null, null, Double.NaN, Double.NaN, null);
            ViewerState st = new ViewerState();
            st.acceptLine(sim.hello());
            for (int k = 0; k < 30; k++) st.acceptLine(sim.step());
            Camera cam = new Camera();
            pr.layout(cam, st, PW, PH);
            pr4.layout(cam, st, PW, PH);
            // look at the street where most walkers are
            cam.set(1341, 850, 2, true);
            final double t0 = 41.3;
            gameShot(pr, st, cam, -1, t0);
            double[] xs0 = walkerXs(pr);
            double best = 1341;
            int most = -1;
            for (double x : xs0) { int n = 0; for (double y : xs0) if (Math.abs(x - y) < PW / 4.0 - 20) n++; if (n > most) { most = n; best = x; } }
            double gy;
            synchronized (st) { gy = st.hello.terrain.at(best); }
            cam.set(best, gy - 60, 2, true);
            // ---- running: the people move (so the check below has teeth)
            Life.hide = true;
            int[] r0 = gameShot(pr, st, cam, -1, t0), r3 = gameShot(pr, st, cam, -1, t0 + 3);
            Life.hide = false;
            int[] ra = gameShot(pr, st, cam, -1, t0);
            int onScreen = Life.stats().walkers;
            int[] rb = gameShot(pr, st, cam, -1, t0 + 3);
            int[] run = layer(ra, r0, rb, r3);
            gameShot(pr4, st, cam, -1, t0 + 3);                       // (the four-band renderer sees the same clocks as the one-band one)
            check(onScreen >= 2 && run[0] > 60 && run[1] > 30, "the game's renderer on the fake town by day: " + onScreen + " walkers in view (" + run[0] + " px of people); while the simulation runs, 3 s later they are elsewhere ("
                    + run[1] + " px of the people layer changed)");
            // ---- paused: frames 3 s of wall time apart
            sim.handle("{\"cmd\":\"pause\"}");
            st.acceptLine(sim.step());
            st.acceptLine(sim.step());
            boolean isPaused;
            synchronized (st) { isPaused = st.cur.paused; }
            double t1 = t0 + 3.02;
            gameShot(pr, st, cam, -1, t1);                            // (the first paused frame: the clock stops here)
            double[] xsA = walkerXs(pr);
            double lifeA = pr.shared.lifeAnim;
            int[] pb = gameShot(pr, st, cam, -1, t1 + 3);
            double[] xsB = walkerXs(pr);
            int walkersPaused = Life.stats().walkers;
            // (the rest of the picture goes on moving - trees, smoke - so each frame is compared with its own twin without people)
            Life.hide = true;
            int[] p0b = gameShot(pr, st, cam, -1, t1 + 3), p0c = gameShot(pr, st, cam, -1, t1 + 6);
            Life.hide = false;
            int[] pc = gameShot(pr, st, cam, -1, t1 + 6);
            int[] still = layer(pb, p0b, pc, p0c);
            double moved = Math.max(maxStep(xsA, xsB), Math.abs(pr.shared.lifeAnim - lifeA));
            check(isPaused && walkersPaused >= 2 && still[0] > 60 && still[1] == 0 && still[2] == 0 && moved == 0,
                    String.format(Locale.ROOT, "paused: two frames 3 s of wall time apart have the same people, pixel for pixel (%d walkers in view, %d px of people; %d px moved, %d px changed colour); "
                            + "over 6 s of pause no walker moved (%.3f px) and the life clock stood at %.2f s", walkersPaused, still[0], still[1], still[2], moved, lifeA));
            // 1 band = 4 bands while paused (every band reads the same clock)
            gameShot(pr4, st, cam, -1, t1);                           // (its clock stops at the same moment: see above)
            int[] q4 = gameShot(pr4, st, cam, -1, t1 + 6);
            int bandDiff = 0, bandPx = 0;
            for (int i = 0; i < pc.length; i++) if (pc[i] != p0c[i]) { bandPx++; if (pc[i] != q4[i]) bandDiff++; }
            check(bandPx > 60 && bandDiff == 0, "and 4 render bands draw the paused people where 1 band draws them (" + bandDiff + " of " + bandPx + " px of people differ)");
            // ---- resumed: they go on from where they stood
            sim.handle("{\"cmd\":\"resume\"}");
            st.acceptLine(sim.step());
            final double frame = 1 / 60.0;
            gameShot(pr, st, cam, -1, t1 + 6 + frame);
            double[] xsC = walkerXs(pr);
            double stepC = maxStep(xsB, xsC), lifeC = pr.shared.lifeAnim;
            // ---- a change of speed moves nobody
            sim.handle("{\"cmd\":\"speed\",\"value\":8}");
            st.acceptLine(sim.step());
            gameShot(pr, st, cam, -1, t1 + 6 + 2 * frame);
            double[] xsD = walkerXs(pr);
            double stepD = maxStep(xsC, xsD);
            double speed;
            synchronized (st) { speed = st.cur.speed; }
            check(stepC > 0 && stepC <= 0.5 && Math.abs(lifeC - (lifeA + frame)) < 1e-9,
                    String.format(Locale.ROOT, "resumed: in the first frame after the pause (1/60 s later) every walker is within %.2f px of where it stood (tolerance 0.5 px: at most 20 px/s x 1/60 s = 0.33 px), "
                            + "nobody appeared or vanished, and the life clock went on from %.2f s (not from the %.2f s of the animation clock)", stepC, lifeA, t1 + 6));
            check(speed == 8 && stepD > 0 && stepD <= 0.5, String.format(Locale.ROOT, "at speed x%.0f the next frame moves them %.2f px (tolerance 0.5 px): people walk in wall time, a change of speed makes nobody sprint or jump", speed, stepD));
        } finally {
            Life.hide = false;
            pr.shutdown();
            pr4.shutdown();
        }
        // ---- the pieces a tornado has in the air
        ParallelRenderer pw = gameRenderer(1);
        try {
            FakeSim sim = new FakeSim(1234L, 0.45, 3840, 1080, null, null, Double.NaN, Double.NaN, "hit");
            ViewerState st = new ViewerState();
            st.acceptLine(sim.hello());
            int flying = 0;
            for (int k = 0; k < 6000 && flying < 12; k++) {
                st.acceptLine(sim.step());
                flying = 0;
                synchronized (st) { for (Chunks.Chunk c : st.wreck.chunks.list) if (c.state == Chunks.LOFT || c.state == Chunks.FALL) flying++; }
            }
            double cx = 0, cy = 0;
            synchronized (st) {
                int n = 0;
                for (Chunks.Chunk c : st.wreck.chunks.list) if (c.state == Chunks.LOFT || c.state == Chunks.FALL) { cx += c.x; cy += c.y; n++; }
                if (n > 0) { cx /= n; cy /= n; }
            }
            Camera cam = new Camera();
            pw.layout(cam, st, PW, PH);
            cam.set(cx, cy, 1, true);
            long recv;
            double t;
            synchronized (st) { recv = st.cur.recvNanos; t = st.cur.t; }
            // running: 20 ms and 80 ms after the frame arrived the pieces have been carried on
            Chunks.hide = true;
            int[] c0a = gameShot(pw, st, cam, recv + 20_000_000L, t), c0b = gameShot(pw, st, cam, recv + 80_000_000L, t);
            Chunks.hide = false;
            int[] ca = gameShot(pw, st, cam, recv + 20_000_000L, t), cb = gameShot(pw, st, cam, recv + 80_000_000L, t);
            int[] run = layer(ca, c0a, cb, c0b);
            sim.handle("{\"cmd\":\"pause\"}");
            st.acceptLine(sim.step());
            double[] before = pieces(st);
            st.acceptLine(sim.step());
            st.acceptLine(sim.step());
            double[] after = pieces(st);
            boolean isPaused;
            synchronized (st) { recv = st.cur.recvNanos; t = st.cur.t; isPaused = st.cur.paused; }
            Chunks.hide = true;
            int[] h0a = gameShot(pw, st, cam, recv + 20_000_000L, t), h0b = gameShot(pw, st, cam, recv + 3_020_000_000L, t);
            Chunks.hide = false;
            int[] ha = gameShot(pw, st, cam, recv + 20_000_000L, t), hb = gameShot(pw, st, cam, recv + 3_020_000_000L, t);
            int[] held = layer(ha, h0a, hb, h0b);
            double drift = maxStep(before, after);
            check(flying >= 12 && run[0] > 40 && run[1] > 0, "scene \"hit\": " + flying + " pieces of buildings in the air (" + run[0] + " px); while the simulation runs they are carried on between two looks at the same frame (" + run[1] + " px moved)");
            check(isPaused && held[0] > 40 && held[1] == 0 && held[2] == 0 && drift == 0,
                    String.format(Locale.ROOT, "paused: the flying pieces are the same pixels 3 s of wall time later (%d px of pieces; %d px moved, %d changed colour), and two more paused frames move none of them (%.3f px)",
                            held[0], held[1], held[2], drift));
        } finally {
            Chunks.hide = false;
            pw.shutdown();
        }
    }

    /** x, y and turn of every piece in the air. */
    private static double[] pieces(ViewerState st) {
        synchronized (st) {
            double[] out = new double[st.wreck.chunks.list.size() * 3];
            int i = 0;
            for (Chunks.Chunk c : st.wreck.chunks.list) { out[i++] = c.x; out[i++] = c.y; out[i++] = c.rot; }
            return out;
        }
    }

    private static void cost() {
        Lit eve = new Lit(EVENING, 0);
        // a long street of houses: enough lit windows that about 20 are being crossed at a time
        List<Model.Obj> objs = new ArrayList<>();
        String[] kinds = {"house:manor", "house:farmhouse", "house:townhouse", "house:cottage"};
        double x = 140;
        for (int i = 0; i < 60; i++) { DebugLineup.Entry e = entry(kinds[i % kinds.length]); x += e.w / 2; objs.add(house(kinds[i % kinds.length], 1 + i, x, 900, 0, 0, 0, 0)); x += e.w / 2 + 30; }
        Scene sc = scene(objs, x + 200, 900, eve, 1500, 0, 0);
        BufferedImage img = new BufferedImage(1920, 600, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = img.createGraphics();
        hints(g);
        AffineTransform base = g.getTransform();
        double best = 1e9, sum = 0;
        double[] all = new double[250], allLoad = new double[250];
        int frames = 0, cross = 0, walk = 0, idle = 0;
        for (int rep = 0; rep < 400; rep++) {
            double anim = rep * 0.37, wx0 = 100;
            g.setTransform(base);
            g.scale(2, 2);
            g.translate(-wx0, -700);
            long t0 = System.nanoTime();
            Life.paintWorld(g, sc, 2, wx0, wx0 + 960, anim, eve, null, null);
            double ms = (System.nanoTime() - t0) / 1e6;
            if (rep >= 150) { all[rep - 150] = ms; best = Math.min(best, ms); sum += ms; frames++; cross += Life.stats().crossings; walk += Life.stats().walkers; idle += Life.stats().idle; }
        }
        g.dispose();
        java.util.Arrays.sort(all);
        costMs = all[all.length / 2];             // (the median: another program's burst on a busy machine is not the pass's cost)
        System.out.printf(Locale.ROOT, "  live pass at zoom 2, a 960 px view of a street of %d houses in the early evening: %.3f ms a frame (median of 250; best %.3f), drawing %.1f walkers, %.1f crossing shadows and %.1f people at their houses a frame%n",
                objs.size(), costMs, best, walk / (double) frames, cross / (double) frames, idle / (double) frames);
        check(costMs < 0.6, String.format(Locale.ROOT, "the live pass costs %.3f ms a frame (target 0.15 ms; the check allows 0.6 ms for a busy machine)", costMs));
        // the load the plan names: 12 walkers (two with a dog) and 20 shadows crossing their windows, all in view at once
        // (a street never has 20 crossings at a time: a window is crossed for 3-5 s every 20-90 s; so the figures are drawn directly)
        img = new BufferedImage(1920, 600, BufferedImage.TYPE_INT_ARGB_PRE);
        g = img.createGraphics();
        hints(g);
        g.scale(2, 2);
        int n = 0;
        java.awt.Color shadow = new java.awt.Color(42, 33, 28, 236);
        for (int rep = 0; rep < 400; rep++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 12; i++) {
                Figures.walker(g, 40 + i * 70, 280, new Figures.Look(i * 77L + 1, i % 6 == 0), eve, 0, i % 2 == 0 ? 1 : -1, rep * 0.2 + i);
                if (i % 5 == 0) Figures.dog(g, 30 + i * 70, 280, i, eve, 0, 1, rep * 0.3);
            }
            for (int i = 0; i < 20; i++) {
                Figures.bust(g, 30 + i * 44 + (rep % 12), 110, 8, shadow, i, 1, 30 + i * 44, 39 + i * 44);
            }
            if (rep >= 150) { allLoad[rep - 150] = (System.nanoTime() - t0) / 1e6; n++; }
        }
        g.dispose();
        java.util.Arrays.sort(allLoad);
        loadMs = allLoad[allLoad.length / 2];
        check(loadMs < 0.6, String.format(Locale.ROOT, "12 walkers, 3 dogs and 20 crossing shadows cost %.3f ms a frame (target 0.15 ms; the check allows 0.6 ms)", loadMs));
    }

    static double loadMs;

    static double costMs;

    // ===================================================================================== pictures

    private static void write(BufferedImage img, String file) throws java.io.IOException {
        java.io.File f = new java.io.File(file);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        javax.imageio.ImageIO.write(rgb, "png", f);
        System.out.println("written " + f + " (" + img.getWidth() + " x " + img.getHeight() + ")");
    }

    /** One variant in four lights side by side (day, early evening, dusk, night), with the live pass on top. */
    static BufferedImage variant(String label, long seed, double scale, int layer) {
        DebugLineup.Entry e = entry(label);
        double[] tods = {DAY, EVENING, DUSK, NIGHT};
        double cw = e.w + 64, chh = e.h * 1.45 + 34;
        int iw = (int) Math.round(cw * scale), ih = (int) Math.round((chh + 12) * scale);
        BufferedImage out = new BufferedImage(iw * tods.length, ih, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        for (int i = 0; i < tods.length; i++) {
            List<Model.Obj> one = new ArrayList<>();
            one.add(house(label, seed, 400, 900 - (layer == 1 ? 6 : 0), layer, 0, 0, 0));
            Scene sc = scene(one, 800, 900, new Lit(tods[i], 0), 0, 0, 0);
            sc.hello.roads.clear();
            g.drawImage(picture(sc, 400 - cw / 2, 900 - chh, 400 + cw / 2, 912, scale, 7.3, true, null, null, 1), i * iw, 0, null);
        }
        g.dispose();
        return out;
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        if (a.length >= 3 && a[0].equals("--variants")) {
            // --variants <scale> <dir> [seed]: one picture per house variant (and the apartment block), four lights each
            double scale = Double.parseDouble(a[1]);
            long seed = a.length > 3 ? Long.parseLong(a[3]) : 2;
            List<String> labels = new ArrayList<>();
            for (String v : BuildingPainter.HOUSE_VARIANTS) labels.add("house:" + v);
            labels.add("apartment");
            for (String l : labels) write(variant(l, seed, scale, 0), a[2] + "/" + l.replace("house:", "") + "_x" + (int) scale + "_seed" + seed + ".png");
            return;
        }
        if (a.length >= 4 && a[0].equals("--street")) {
            // --street <file> <tod> <scale> [wet] [damage] [anim] [x0] [x1]
            double tod = Double.parseDouble(a[2]), scale = Double.parseDouble(a[3]), wet = a.length > 4 ? Double.parseDouble(a[4]) : 0, dmg = a.length > 5 ? Double.parseDouble(a[5]) : 0;
            double anim = a.length > 6 ? Double.parseDouble(a[6]) : 21.4, x0 = a.length > 7 ? Double.parseDouble(a[7]) : 110, x1 = a.length > 8 ? Double.parseDouble(a[8]) : 1010;
            Scene sc = scene(street(900, wet, 0, dmg), 1400, 900, new Lit(tod, wet > 0.3 ? 0.8 : 0), 900, wet, 0);
            write(picture(sc, x0, 770, x1, 916, scale, anim, true, null, null, 1), a[1]);
            return;
        }
        System.exit(run());
    }
}
