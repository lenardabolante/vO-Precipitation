package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Static landscape painting: parallax ridges, background mountains / hills, and the terrain itself
 * (soil body with strata, grass / rock / snow surface that follows slope, altitude and the sim's
 * ground cover). Everything here is pure: it reads an immutable {@link Scene} and paints a
 * rectangle, so {@link TileLayer} can call it from any thread.
 */
final class TerrainPainter {
    private TerrainPainter() {}

    // ------------------------------------------------------------------ far ridges (parallax layers)

    /** Height of ridge {@code layer} (0 = far, 1 = near) at layer coordinate u. */
    static double ridgeY(Scene sc, int layer, double u) {
        long s = sc.seed * 977 + layer * 31337L;
        if (layer == 0) {
            double m = Scene.fbm1(u * 0.0019 + 5, s, 4);
            return sc.groundY - sc.worldH * (0.13 + 0.32 * Math.pow(m, 1.25)) - 26 * Scene.noise1(u * 0.011, s + 5) - 8 * Scene.noise1(u * 0.04, s + 6);
        }
        double m = Scene.fbm1(u * 0.0034 + 50, s, 3);
        return sc.groundY - sc.worldH * (0.05 + 0.15 * m) - 12 * Scene.noise1(u * 0.021, s + 7);
    }

    static double ridgeTop(Scene sc, int layer) { return sc.groundY - sc.worldH * (layer == 0 ? 0.50 : 0.23) - 40; }

    static void paintRidge(Graphics2D g, Scene sc, int layer, double x0, double y0, double x1, double y1, double scale) {
        Lit lit = sc.lit;
        double step = Math.max(4, 3 / scale);
        Path2D.Double p = new Path2D.Double();
        double bottom = y1 + 4;
        p.moveTo(x0 - step, bottom);
        double top = 1e9;
        for (double x = x0 - step; x <= x1 + step; x += step) {
            double y = ridgeY(sc, layer, x);
            top = Math.min(top, y);
            p.lineTo(x, y);
        }
        p.lineTo(x1 + step, bottom);
        p.closePath();
        if (top > y1) return;
        Color c = sc.biome != null ? sc.biome.ridge(layer) : layer == 0 ? new Color(92, 112, 146) : new Color(70, 96, 108);
        c = Lit.mix(lit.lit(c), lit.skyBot, layer == 0 ? 0.58 : 0.36);
        double ty = ridgeTop(sc, layer);
        g.setPaint(new GradientPaint(0, (float) ty, Lit.mix(c, lit.skyTop, layer == 0 ? 0.12 : 0.04), 0, (float) sc.groundY, Lit.mix(c, lit.skyBot, 0.42)));
        g.fill(p);
        if (layer == 0) {
            // snow on the far peaks above the freezing level, with a ragged snow line
            double fz = sc.freezingY;
            if (top < fz + 20 && !sc.noSnow && (sc.biome == null || !sc.biome.id.equals("desert"))) {     // (a desert's far ranges stay bare rock)
                Path2D.Double cap = new Path2D.Double();
                cap.moveTo(x0 - step, y0 - 10);
                cap.lineTo(x1 + step, y0 - 10);
                for (double x = x1 + step; x >= x0 - step; x -= step * 2) {
                    cap.lineTo(x, fz - 14 + 34 * Scene.noise1(x * 0.03, sc.seed + 91) + 12 * Scene.noise1(x * 0.11, sc.seed + 92));
                }
                cap.closePath();
                Area a = new Area(p);
                a.intersect(new Area(cap));
                Color sn = Lit.mix(lit.lit(new Color(240, 244, 252)), lit.skyBot, 0.38);
                g.setPaint(new GradientPaint(0, (float) ty, sn, 0, (float) (fz + 20), Lit.alpha(sn, 150)));
                g.fill(a);
            }
        }
    }

    // ------------------------------------------------------------------ background mountains and hills

    static void paintScenery(Graphics2D g, Scene sc, double x0, double y0, double x1, double y1, double scale) {
        for (Model.Obj o : sc.objects) if (o.kind.equals("mountain") && o.x + o.w / 2 >= x0 && o.x - o.w / 2 <= x1) mountain(g, sc, o);
        for (Model.Obj o : sc.objects) if (o.kind.equals("hill") && o.x + o.w / 2 >= x0 && o.x - o.w / 2 <= x1) hill(g, sc, o);
    }

    private static void mountain(Graphics2D g, Scene sc, Model.Obj o) {
        Lit lit = sc.lit;
        Random r = new Random(o.seed * 131 + 7);
        double base = Math.max(o.y, sc.groundY) + 6;
        double x0 = o.x - o.w / 2, x1 = o.x + o.w / 2, peakX = o.x + (r.nextDouble() - 0.5) * o.w * 0.2, peakY = o.y - o.h;
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x0, base);
        int n = 13;
        List<double[]> ridge = new ArrayList<>();
        for (int k = 1; k < n; k++) {
            double t = k / (double) n;
            ridge.add(new double[]{x0 + (peakX - x0) * t, base + (peakY - base) * Math.pow(t, 0.9) + (r.nextDouble() - 0.5) * o.h * 0.07});
        }
        ridge.add(new double[]{peakX, peakY});
        for (int k = 1; k < n; k++) {
            double t = k / (double) n;
            ridge.add(new double[]{peakX + (x1 - peakX) * t, peakY + (base - peakY) * Math.pow(t, 1.1) + (r.nextDouble() - 0.5) * o.h * 0.07});
        }
        for (double[] q : ridge) p.lineTo(q[0], q[1]);
        p.lineTo(x1, base);
        p.closePath();
        double hz = 0.26;
        Color[] mc = sc.biome != null ? sc.biome.mountain() : null;      // v2.3: dune-coloured in a desert, ice in polar country
        Color rockTop = lit.lit(mc != null ? mc[0] : new Color(122, 122, 136), hz), rockBot = lit.lit(mc != null ? mc[1] : new Color(74, 84, 92), hz + 0.1);
        g.setPaint(new GradientPaint(0, (float) peakY, rockTop, 0, (float) base, rockBot));
        g.fill(p);
        // shadow face away from the light
        Path2D.Double sh = new Path2D.Double();
        sh.moveTo(peakX, peakY);
        boolean after = false;
        for (double[] q : ridge) { if (q[0] >= peakX) after = true; if (after) sh.lineTo(q[0], q[1]); }
        sh.lineTo(x1, base);
        sh.lineTo(peakX + o.w * 0.08, base);
        sh.closePath();
        g.setColor(new Color(10, 16, 30, 50));
        g.fill(sh);
        // snow cap: whatever the sim says, and always above the freezing level
        double line = sc.noSnow ? peakY : Math.max(peakY + o.h * (0.10 + 0.55 * o.snow) * (o.snow > 0.02 ? 1 : 0), sc.freezingY);
        if (line > peakY + 6) {
            Path2D.Double cap = new Path2D.Double();
            cap.moveTo(x0 - 5, peakY - 10);
            cap.lineTo(x1 + 5, peakY - 10);
            cap.lineTo(x1 + 5, line);
            for (double x = x1; x >= x0; x -= 14) cap.lineTo(x, line + (r.nextDouble() - 0.3) * 18);
            cap.closePath();
            Area a = new Area(p);
            a.intersect(new Area(cap));
            g.setPaint(new GradientPaint(0, (float) peakY, lit.lit(new Color(250, 252, 255), hz * 0.6), 0, (float) line, lit.lit(new Color(212, 224, 240), hz)));
            g.fill(a);
            Area s2 = new Area(sh);
            s2.intersect(a);
            g.setColor(new Color(60, 80, 130, 46));
            g.fill(s2);
        }
    }

    private static void hill(Graphics2D g, Scene sc, Model.Obj o) {
        Lit lit = sc.lit;
        double base = Math.max(o.y, sc.groundY) + 6;
        Path2D.Double p = new Path2D.Double();
        p.moveTo(o.x - o.w / 2, base);
        p.curveTo(o.x - o.w * 0.25, o.y - o.h * 1.05, o.x + o.w * 0.2, o.y - o.h * 1.3, o.x + o.w / 2, base);
        p.closePath();
        Color top = Lit.mix(new Color(104, 160, 82), new Color(62, 110, 60), o.wet * 0.6);
        Color bot = Lit.mix(new Color(64, 116, 62), new Color(40, 84, 48), o.wet * 0.6);
        if (sc.biome != null) {
            Color[] hc = sc.biome.hill();
            top = Lit.mix(hc[0], Lit.mul(hc[0], 0.66), o.wet * 0.6);
            bot = Lit.mix(hc[1], Lit.mul(hc[1], 0.70), o.wet * 0.6);
        }
        g.setPaint(new GradientPaint(0, (float) (o.y - o.h), lit.lit(top, 0.18), 0, (float) base, lit.lit(bot, 0.24)));
        g.fill(p);
        if (o.snow > 0.02) {
            g.setPaint(new GradientPaint(0, (float) (o.y - o.h), Lit.alpha(lit.lit(new Color(248, 250, 255), 0.1), (int) (240 * Math.min(1, o.snow * 1.3))),
                    0, (float) base, Lit.alpha(lit.lit(new Color(220, 230, 245), 0.1), (int) (200 * o.snow))));
            g.fill(p);
        }
    }

    // ------------------------------------------------------------------ roads (protocol v2.3)

    /**
     * The road spans of {@code hello.roads} painted along the terrain surface between x0 and x1: an
     * asphalt strip that follows the ground (darker and glossier where it is wet, greyed by slush
     * where snow lies), a pale kerb line along its upper edge and dashed lane markings. Painted
     * over the front row of buildings, so a street runs in front of the houses.
     */
    static void paintRoads(Graphics2D g, Scene sc, double x0, double y0, double x1, double y1, double scale) {
        if (sc.hello.roads.isEmpty()) return;
        Model.Terrain t = sc.terrain;
        Lit lit = sc.lit;
        double step = t.step * Math.max(1, Math.ceil(1.6 / (scale * t.step) - 1e-9));
        for (int pass = 0; pass < 2; pass++) {               // the highway first, streets over it
            for (Model.Road r : sc.hello.roads) {
                if (r.highway() != (pass == 0)) continue;
                double a = Math.max(r.x0, x0 - 2 * step), b = Math.min(r.x1, x1 + 2 * step);
                if (b <= a) continue;
                double depth = r.highway() ? 6.4 : 5.6, lift = 1.8;
                double xa = Math.max(r.x0, Math.floor(a / step) * step), xb = Math.min(r.x1, Math.ceil(b / step) * step);
                int n = Math.max(2, (int) Math.ceil((xb - xa) / step) + 1);
                double[] xs = new double[n], ys = new double[n];
                for (int i = 0; i < n; i++) { xs[i] = Math.min(xb, xa + i * step); ys[i] = t.at(xs[i]); }
                // asphalt, in runs of equal wetness / snow so the colour follows the ground cover
                Path2D.Double run = null;
                int runKey = -1;
                for (int i = 0; i < n - 1; i++) {
                    double xm = (xs[i] + xs[i + 1]) * 0.5;
                    int wq = (int) Math.round(sc.ground.wetAt(xm) * 5), sq = (int) Math.round(Math.min(1, sc.snowAt(xm, t.at(xm))) * 4);
                    int key = wq * 8 + sq;
                    if (key != runKey) {
                        if (run != null) g.fill(run);
                        runKey = key;
                        double wet = wq / 5.0, snow = sq / 4.0;
                        Color asphalt = Lit.mix(new Color(72, 74, 80), new Color(40, 44, 54), wet * 0.85);
                        asphalt = Lit.mix(asphalt, new Color(206, 212, 222), snow * 0.72);
                        g.setColor(lit.lit(asphalt));
                        run = new Path2D.Double();
                    }
                    run.moveTo(xs[i] - 0.3, ys[i] - lift);
                    run.lineTo(xs[i + 1] + 0.3, ys[i + 1] - lift);
                    run.lineTo(xs[i + 1] + 0.3, ys[i + 1] + depth);
                    run.lineTo(xs[i] - 0.3, ys[i] + depth);
                    run.closePath();
                }
                if (run != null) g.fill(run);
                // kerb line along the top, a shadow line under the strip
                Path2D.Double kerb = new Path2D.Double(), shade = new Path2D.Double();
                kerb.moveTo(xs[0], ys[0] - lift);
                shade.moveTo(xs[0], ys[0] + depth);
                for (int i = 1; i < n; i++) { kerb.lineTo(xs[i], ys[i] - lift); shade.lineTo(xs[i], ys[i] + depth); }
                g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
                g.setColor(Lit.alpha(lit.lit(new Color(170, 170, 166)), 210));
                g.draw(kerb);
                g.setColor(new Color(0, 0, 0, 70));
                g.draw(shade);
                // lane markings: dashes that follow the slope (too small to matter when zoomed far out)
                if (scale >= 0.42) {
                    double period = r.highway() ? 26 : 20, dash = r.highway() ? 11 : 7, mid = lift * -0.5 + depth * 0.5 - 0.4;
                    Color mark = r.highway() ? new Color(232, 200, 92) : new Color(226, 228, 226);
                    g.setColor(Lit.alpha(lit.lit(mark), 225));
                    Path2D.Double dashes = new Path2D.Double();
                    for (double dx = Math.ceil((a - r.x0) / period) * period + r.x0; dx + dash <= b; dx += period) {
                        if (sc.snowAt(dx, t.at(dx)) > 0.6) continue;
                        double ya = t.at(dx) + mid, yb = t.at(dx + dash) + mid;
                        dashes.moveTo(dx, ya - 0.7);
                        dashes.lineTo(dx + dash, yb - 0.7);
                        dashes.lineTo(dx + dash, yb + 0.7);
                        dashes.lineTo(dx, ya + 0.7);
                        dashes.closePath();
                    }
                    g.fill(dashes);
                }
                // a wet road mirrors the sky a little
                if (scale >= 0.42) {
                    Path2D.Double sheen = new Path2D.Double();
                    boolean any = false;
                    for (int i = 0; i < n - 1; i++) {
                        double xm = (xs[i] + xs[i + 1]) * 0.5;
                        if (sc.ground.wetAt(xm) < 0.35) continue;
                        any = true;
                        sheen.moveTo(xs[i], ys[i] - lift + 1.0);
                        sheen.lineTo(xs[i + 1], ys[i + 1] - lift + 1.0);
                        sheen.lineTo(xs[i + 1], ys[i + 1] - lift + 2.2);
                        sheen.lineTo(xs[i], ys[i] - lift + 2.2);
                        sheen.closePath();
                    }
                    if (any) {
                        g.setColor(Lit.alpha(Lit.mix(lit.skyBot, Color.WHITE, 0.25), 62));
                        g.fill(sheen);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ the terrain

    /** Soil body, strata and the surface cover between x0 and x1. */
    static void paintTerrain(Graphics2D g, Scene sc, double x0, double y0, double x1, double y1, double scale) {
        Model.Terrain t = sc.terrain;
        Lit lit = sc.lit;
        if (y1 < t.minY - 12) return;
        double step = t.step * Math.max(1, Math.ceil(1.6 / (scale * t.step) - 1e-9));
        double xa = Math.floor((x0 - 2 * step) / step) * step, xb = Math.ceil((x1 + 2 * step) / step) * step;
        int n = (int) Math.round((xb - xa) / step) + 1;
        double[] xs = new double[n], ys = new double[n];
        for (int i = 0; i < n; i++) { xs[i] = xa + i * step; ys[i] = t.at(xs[i]); }

        // ---- body
        Path2D.Double body = new Path2D.Double();
        double bottom = Math.max(y1 + 4, sc.worldH + 4);
        body.moveTo(xs[0], bottom);
        for (int i = 0; i < n; i++) body.lineTo(xs[i], ys[i]);
        body.lineTo(xs[n - 1], bottom);
        body.closePath();
        double gTop = Math.min(t.minY, sc.groundY - 60), gBot = sc.worldH;
        double span = Math.max(1, gBot - gTop);
        float fSoil = (float) Model.clamp((sc.groundY - sc.worldH * 0.11 - gTop) / span, 0.05, 0.9);
        float fLow = (float) Model.clamp((sc.groundY + 6 - gTop) / span, fSoil + 0.02f, 0.97f);
        Color rock = lit.lit(new Color(116, 110, 108)), mid = lit.lit(new Color(110, 90, 70)), soil = lit.lit(new Color(104, 78, 54)), deep = lit.lit(new Color(56, 44, 38));
        final Preset.Biome bio = sc.biome;
        if (bio != null) {
            // v2.3: the body takes the biome's ground near the surface and its soil below (sand over sandstone, snow over ice)
            if (bio.grass != null) {
                // under turf the body is earth, as in a temperate world, in the biome's soil colour
                rock = lit.lit(Lit.mix(bio.rock(), bio.soil, 0.25));
                mid = lit.lit(Lit.mix(bio.soil, bio.rock(), 0.22));
                soil = lit.lit(bio.soil);
                deep = lit.lit(Lit.mul(bio.soil, 0.54));
            } else if (bio.id.equals("polar")) {
                rock = lit.lit(bio.ground);
                mid = lit.lit(Lit.mix(bio.ground, bio.soil, 0.30));
                soil = lit.lit(bio.soil);
                deep = lit.lit(Lit.mix(Lit.mul(bio.soil, 0.80), new Color(96, 140, 196), 0.40));       // old blue ice far down
            } else {
                rock = lit.lit(Lit.mix(bio.rock(), bio.ground, 0.35));
                mid = lit.lit(Lit.mul(bio.ground, 0.90));
                soil = lit.lit(Lit.mix(bio.ground, bio.soil, 0.70));
                deep = lit.lit(Lit.mul(bio.soil, 0.52));
            }
        }
        g.setPaint(new LinearGradientPaint(0f, (float) gTop, 0f, (float) gBot, new float[]{0f, fSoil, fLow, 1f}, new Color[]{rock, mid, soil, deep}));
        g.fill(body);

        if (bio != null && bio.grass != null) {
            // v2.3: under the turf the earth keeps the biome's surface colour for a little way down (`ground`), then
            // gives way to its `soil`: golden under a savanna, dark green under a rain forest. A few stacked bands that
            // follow the surface make the fade.
            Color gc = lit.lit(Lit.mix(bio.ground, bio.soil, 0.12));
            final int bands = 9;
            final double depth = 86;
            for (int k = 0; k < bands; k++) {
                double d0 = depth * k / bands, d1 = depth * (k + 1) / bands + 0.6;
                Path2D.Double bp = new Path2D.Double();
                bp.moveTo(xs[0], ys[0] + d0);
                for (int i = 1; i < n; i++) bp.lineTo(xs[i], ys[i] + d0);
                for (int i = n - 1; i >= 0; i--) bp.lineTo(xs[i], ys[i] + d1);
                bp.closePath();
                double a = 1 - (k + 0.5) / bands;
                g.setColor(Lit.alpha(gc, (int) (215 * a * a)));
                g.fill(bp);
            }
        }

        // ---- strata following a smoothed surface, pebbles
        if (scale >= 0.28) {
            g.setStroke(new BasicStroke(1.2f));
            for (int k = 0; k < 6; k++) {
                Path2D.Double s = new Path2D.Double();
                double d = 26 + 40 * k + 12 * Scene.hash01(sc.seed + k * 13);
                boolean started = false;
                for (double x = xa; x <= xb; x += Math.max(step, 16)) {
                    double sm = (t.at(x - 90) + t.at(x - 30) + t.at(x + 30) + t.at(x + 90)) * 0.25;
                    double y = sm * 0.7 + sc.groundY * 0.3 + d + 5 * Math.sin(x * 0.011 + k * 2.1) + 6 * Scene.noise1(x * 0.02, sc.seed + k);
                    if (y < t.at(x) + 8) { started = false; continue; }
                    if (!started) { s.moveTo(x, y); started = true; } else s.lineTo(x, y);
                }
                g.setColor(new Color(0, 0, 0, 26));
                g.draw(s);
            }
            for (long c = (long) Math.floor(x0 / 34); c <= (long) Math.floor(x1 / 34); c++) {
                for (int j = 0; j < 2; j++) {
                    long h = c * 73856093L + j * 19349663L + sc.seed;
                    double px = (c + Scene.hash01(h)) * 34, py = t.at(px) + 12 + Scene.hash01(h + 1) * 190, sz = 1.4 + Scene.hash01(h + 2) * 2.8;
                    if (py < y0 - 4 || py > y1 + 4) continue;
                    g.setColor(new Color(0, 0, 0, 26 + (int) (30 * Scene.hash01(h + 3))));
                    if (bio != null && bio.id.equals("polar")) g.setColor(new Color(60, 100, 160, 14 + (int) (16 * Scene.hash01(h + 3))));   // faint blue flecks in ice, not pebbles
                    g.fill(new Ellipse2D.Double(px, py, sz * 1.6, sz));
                }
            }
        }

        // ---- surface cover: grass on gentle low ground, rock where steep or high, snow where cold, darker where wet
        Color grassDry = new Color(98, 158, 74), grassWet = new Color(56, 108, 56), rockC = new Color(132, 128, 124), rockWet = new Color(92, 92, 96);
        if (bio != null) {
            // turf of the biome's colour, or (grass = null) the bare ground itself: sand, rock, ice
            grassDry = bio.grass != null ? bio.grass : bio.ground;
            grassWet = Lit.mix(Lit.mul(grassDry, 0.62), new Color(56, 96, 60), bio.grass != null ? 0.25 : 0);
            rockC = bio.rock();
            rockWet = Lit.mul(rockC, 0.72);
        }
        Color snowC = lit.lit(new Color(246, 249, 255));
        Path2D.Double run = null, shade = new Path2D.Double(), snowP = null;
        int runKey = -1;
        double[] th = new double[n];
        int[] key = new int[n];
        double[] snow = new double[n];
        for (int i = 0; i < n; i++) {
            double x = xs[i], y = ys[i];
            double sl = Math.abs((t.at(x + step) - t.at(x - step)) / (2 * step));
            double rockiness = Math.max(Lit.smooth((sl - 0.45) / 0.5), 0.85 * Lit.smooth(((sc.groundY - y) - sc.worldH * 0.13) / (sc.worldH * 0.07)));
            double wet = sc.ground.wetAt(x);
            snow[i] = sc.snowAt(x, y) * (1 - 0.55 * Lit.smooth((sl - 1.0) / 0.9));
            int rq = (int) Math.round(rockiness * 6), wq = (int) Math.round(wet * 5);
            key[i] = rq * 8 + wq;
            th[i] = 6.5 - 2.5 * rockiness;
        }
        for (int i = 0; i < n - 1; i++) {
            if (key[i] != runKey) {
                if (run != null) { g.fill(run); }
                runKey = key[i];
                double rk = (runKey / 8) / 6.0, wk = (runKey % 8) / 5.0;
                Color grass = Lit.mix(grassDry, grassWet, wk * 0.8), rk2 = Lit.mix(rockC, rockWet, wk * 0.8);
                g.setColor(lit.lit(Lit.mix(grass, rk2, rk)));
                run = new Path2D.Double();
            }
            run.moveTo(xs[i] - 0.3, ys[i] - 1);
            run.lineTo(xs[i + 1] + 0.3, ys[i + 1] - 1);
            run.lineTo(xs[i + 1] + 0.3, ys[i + 1] + th[i + 1]);
            run.lineTo(xs[i] - 0.3, ys[i] + th[i]);
            run.closePath();
            shade.moveTo(xs[i] - 0.3, ys[i] + th[i] - 0.5);
            shade.lineTo(xs[i + 1] + 0.3, ys[i + 1] + th[i + 1] - 0.5);
            shade.lineTo(xs[i + 1] + 0.3, ys[i + 1] + th[i + 1] + 2.5);
            shade.lineTo(xs[i] - 0.3, ys[i] + th[i] + 2.5);
            shade.closePath();
        }
        if (run != null) g.fill(run);
        g.setColor(new Color(0, 0, 0, 34));
        g.fill(shade);
        // grass tufts (static; too small to see when zoomed far out)
        if (scale >= 0.55 && (bio == null || bio.grass != null)) {
            Path2D.Double blades = new Path2D.Double();
            for (double x = Math.floor(x0 / 5) * 5; x <= x1; x += 5) {
                int i = (int) Math.max(0, Math.min(n - 1, Math.round((x - xa) / step)));
                if (key[i] / 8 > 2 || snow[i] > 0.45) continue;
                double hh = 2.5 + 5 * Scene.hash01((long) x * 7 + sc.seed), y = t.at(x);
                blades.moveTo(x, y + 1);
                blades.lineTo(x + (Scene.hash01((long) x * 13 + sc.seed) - 0.5) * 3, y - hh);
            }
            g.setStroke(new BasicStroke(1f));
            g.setColor(lit.lit(bio != null ? Lit.mul(bio.grass, 0.78) : new Color(74, 128, 58)));
            g.draw(blades);
        }
        // snow blanket
        for (int i = 0; i < n - 1; i++) {
            double s0 = snow[i], s1 = snow[i + 1];
            if (s0 < 0.03 && s1 < 0.03) continue;
            if (snowP == null) snowP = new Path2D.Double();
            snowP.moveTo(xs[i] - 0.3, ys[i] - 1.2 - 3.2 * s0);
            snowP.lineTo(xs[i + 1] + 0.3, ys[i + 1] - 1.2 - 3.2 * s1);
            snowP.lineTo(xs[i + 1] + 0.3, ys[i + 1] + (th[i + 1] + 1) * Math.min(1, s1 * 1.5));
            snowP.lineTo(xs[i] - 0.3, ys[i] + (th[i] + 1) * Math.min(1, s0 * 1.5));
            snowP.closePath();
        }
        if (snowP != null) {
            g.setColor(snowC);
            g.fill(snowP);
        }
        // below the world's bottom edge: plain deep soil (visible only when the view is taller than the world)
        if (y1 > sc.worldH) {
            g.setColor(deep);
            g.fill(new Rectangle2D.Double(x0 - 2, sc.worldH - 0.5, x1 - x0 + 4, y1 - sc.worldH + 2));
        }
    }
}
