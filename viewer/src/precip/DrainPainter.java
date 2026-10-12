package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Protocol v2.4 storm drains: the inlet grates along every settlement's street, the cutaway pipe overlay (toggled
 * with the Drains key) and the "FLOODED" chip beside a settlement's name. Everything is drawn from what the
 * simulation sends: the inlet positions are the protocol's formula, the pipe is as thick as {@code drain.size} and
 * as full as {@code flood.inflow / flood.cap}; the viewer computes no capacity of its own.
 */
final class DrainPainter {
    private DrainPainter() {}

    /** Below this zoom the grates are too small to see and are not drawn. */
    static final double INLET_MIN_ZOOM = 0.8;
    /** How far below the surface the top of the pipe runs, world px. */
    static final double PIPE_DEPTH = 14;
    /** The street's surface stands this far above the terrain line ({@code TerrainPainter.paintRoads}). */
    static final double STREET_LIFT = 1.8;
    /** {@code -Dprecip.drains=true}: the pipe overlay is on in every view from the start (diagnostic pictures). */
    static final boolean FORCE_OVERLAY = Boolean.getBoolean("precip.drains");

    private static final Font F_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11), F_CHIP = new Font(Font.SANS_SERIF, Font.BOLD, 10);
    static final Color GREEN = new Color(96, 206, 140), AMBER = new Color(255, 190, 90), RED = new Color(244, 96, 84);

    /** The colour of a load {@code inflow / cap}: green below 0.6, amber, red from 1. */
    static Color loadColor(double load) { return load >= 1 ? RED : load >= 0.6 ? AMBER : GREEN; }

    static Color stateColor(String state) {
        return switch (state) {
            case "overflowing", "flooded" -> RED;
            case "ponding" -> AMBER;
            case "wet" -> new Color(120, 180, 255);
            default -> new Color(150, 170, 195);
        };
    }

    static Model.Flood floodOf(List<Model.Flood> flood, long id) {
        for (Model.Flood f : flood) if (f.id == id) return f;
        return null;
    }

    /** The grate width of a pipe size, world px. */
    static double grateWidth(double size) { return Model.clamp(size * 1.5, 4.5, 16); }

    /**
     * The inlet grates in the kerb of every street in view. {@code g} has the world transform; drawn before the cars.
     */
    static void inlets(Graphics2D g, Model.Hello hello, Map<Long, Model.Drain> drains, Camera.View v, Lit lit, double wy0, double wy1) {
        if (v.zoom < INLET_MIN_ZOOM || drains.isEmpty()) return;
        final Model.Terrain t = hello.terrain;
        final Color frame = lit.lit(new Color(26, 28, 32)), slot = lit.lit(new Color(92, 96, 104));
        for (Model.Settlement s : hello.settlements) {
            Model.Drain d = drains.get(s.id);
            if (d == null || !v.seesX(s.x0, s.x1)) continue;
            final double w = grateWidth(d.size), h = 3.4;
            for (int i = 0; i < d.inlets; i++) {
                double x = d.inletX(i, s.x0, s.x1);
                if (x + w < v.wx0 || x - w > v.wx1) continue;
                double y = t.at(x) - STREET_LIFT + 0.2;
                if (y > wy1 || y + h < wy0) continue;
                g.setColor(frame);
                g.fill(new Rectangle2D.Double(x - w / 2, y, w, h));
                // the slots: a bar every 1.5 px or so, at least two
                int bars = (int) Math.max(2, Math.round(w / 1.6) / 2);
                double pitch = (w - 1) / bars;
                g.setColor(slot);
                for (int k = 0; k < bars; k++) g.fill(new Rectangle2D.Double(x - w / 2 + 0.5 + k * pitch + pitch * 0.2, y + 0.6, pitch * 0.42, h - 1.2));
            }
        }
    }

    /**
     * Drawn after the standing water (world transform): a small swirl over every inlet that is under water, and, with the
     * overlay on, the cutaway pipe under every settlement in view.
     */
    static void overWater(Graphics2D g, Model.Hello hello, Map<Long, Model.Drain> drains, List<Model.Flood> flood, WaterPainter.State water, Camera.View v, Lit lit,
                          double anim, double wy0, double wy1, boolean overlay, boolean noImpact) {
        if (drains.isEmpty()) return;
        final Model.Terrain t = hello.terrain;
        for (Model.Settlement s : hello.settlements) {
            Model.Drain d = drains.get(s.id);
            if (d == null || !v.seesX(s.x0 - 20, s.x1 + 20)) continue;
            Model.Flood fl = floodOf(flood, s.id);
            WaterPainter.Body body = water.body(s.id);
            // ---- swirls: the water is being drawn down the inlets
            if (!noImpact && body != null && body.street && body.alpha > 0.3 && v.zoom >= INLET_MIN_ZOOM) {
                g.setStroke(new BasicStroke(0.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < d.inlets; i++) {
                    double x = d.inletX(i, s.x0, s.x1), depth = t.at(x) - body.y;
                    if (x < v.wx0 - 8 || x > v.wx1 + 8 || x < body.x0 || x > body.x1 || depth < 0.6 || body.y > wy1 || body.y + 4 < wy0) continue;
                    double ph = (anim * 1.3 + i * 0.37) % 1, r = 1.4 + 2.6 * (1 - ph), ry = Math.min(depth, r * 0.45);
                    g.setColor(new Color(232, 242, 255, (int) (150 * ph * body.alpha)));
                    g.draw(new Arc2D.Double(x - r, body.y - ry, 2 * r, 2 * ry, 180, 180, Arc2D.OPEN));
                }
            }
            if (!overlay) continue;
            // ---- the cutaway: a darker window in the soil, the drop shafts, the pipe as thick as its size and as full as its load
            final double a = Math.max(s.x0, v.wx0 - 40), b = Math.min(s.x1, v.wx1 + 40);
            if (b <= a) continue;
            final double size = Math.max(0.5, d.size), load = fl == null || fl.cap <= 0 ? 0 : fl.inflow / fl.cap;
            double low = 0;
            for (double x = a; x <= b; x += 24) low = Math.max(low, t.at(x));
            if (low + PIPE_DEPTH + size + 14 < wy0) continue;
            Path2D.Double win = new Path2D.Double(), top = new Path2D.Double(), bore = new Path2D.Double(), wet = new Path2D.Double();
            final double fill = Model.clamp(load, 0, 1) * size;
            boolean first = true;
            for (double x = a; ; x += 16) {
                boolean last = x >= b;
                if (last) x = b;
                double gy = t.at(x), cy = gy + PIPE_DEPTH + size / 2;
                if (first) { win.moveTo(x, gy + 6.5); top.moveTo(x, cy); bore.moveTo(x, cy); wet.moveTo(x, gy + PIPE_DEPTH + size - fill / 2); }
                else { win.lineTo(x, gy + 6.5); top.lineTo(x, cy); bore.lineTo(x, cy); wet.lineTo(x, gy + PIPE_DEPTH + size - fill / 2); }
                first = false;
                if (last) break;
            }
            for (double x = b; ; x -= 16) {
                boolean last = x <= a;
                if (last) x = a;
                win.lineTo(x, t.at(x) + PIPE_DEPTH + size + 9);
                if (last) break;
            }
            win.closePath();
            g.setColor(new Color(8, 10, 16, 150));
            g.fill(win);
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(150, 158, 170, 200));
            for (int i = 0; i < d.inlets; i++) {
                double x = d.inletX(i, s.x0, s.x1);
                if (x < a || x > b) continue;
                g.draw(new Line2D.Double(x, t.at(x) + 1.6, x, t.at(x) + PIPE_DEPTH + 0.5));
            }
            g.setStroke(new BasicStroke((float) (size + 2.2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(150, 158, 170, 235));
            g.draw(top);
            g.setStroke(new BasicStroke((float) size, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(16, 18, 24, 245));
            g.draw(bore);
            if (fill > 0.15) {
                g.setStroke(new BasicStroke((float) fill, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
                g.setColor(Renderer.alpha(loadColor(load), 235));
                g.draw(wet);
            }
        }
    }

    /** The label of a settlement's pipe, e.g. {@code normal · 4.7 px · cap 41 px²/s}. */
    static String pipeLabel(Model.Drain d) {
        return String.format(Locale.ROOT, "%s · %s px · cap %.0f px²/s", d.cls, trim(d.size), d.cap);
    }

    static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** With the overlay on: the label under every pipe in view. {@code g} has the screen transform. */
    static void labels(Graphics2D g, Model.Hello hello, Map<Long, Model.Drain> drains, List<Model.Flood> flood, Camera.View v, List<Rectangle2D> placed, double sy0, double sy1) {
        if (drains.isEmpty()) return;
        final Model.Terrain t = hello.terrain;
        g.setFont(F_SMALL);
        FontMetrics fm = g.getFontMetrics();
        for (Model.Settlement s : hello.settlements) {
            Model.Drain d = drains.get(s.id);
            if (d == null || !v.seesX(s.x0, s.x1)) continue;
            Model.Flood fl = floodOf(flood, s.id);
            String text = pipeLabel(d);
            if (fl != null && fl.cap > 0) text += String.format(Locale.ROOT, " · load %.0f %%", 100 * Math.min(9.99, fl.inflow / fl.cap));
            int w = fm.stringWidth(text) + 16, h = 17;
            double xa = Math.max(s.x0, v.wx0), xb = Math.min(s.x1, v.wx1), xm = (xa + xb) / 2;
            double x = Model.clamp(v.sx(xm) - w / 2.0, v.vx + 4, v.vx + v.vw - w - 4);
            double y = v.sy(t.at(xm) + PIPE_DEPTH + d.size + 9) + 4;
            if (y + h > v.vy + v.vh - 2) continue;
            for (Rectangle2D o : placed) if (o.intersects(x - 2, y - 2, w + 4, h + 4)) y = o.getMaxY() + 4;
            if (y + h > v.vy + v.vh - 2) continue;
            placed.add(new Rectangle2D.Double(x - 2, y - 2, w + 4, h + 4));
            if (y > sy1 || y + h < sy0) continue;
            g.setColor(new Color(8, 12, 22, 190));
            g.fill(new RoundRectangle2D.Double(x, y, w, h, 10, 10));
            g.setColor(Renderer.alpha(loadColor(fl == null || fl.cap <= 0 ? 0 : fl.inflow / fl.cap), 150));
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Double(x, y, w, h, 10, 10));
            g.setColor(new Color(232, 240, 250));
            g.drawString(text, (float) (x + 8), (float) (y + 12.5));
        }
    }

    /** The width of the chip {@link #chip} draws for this flood state (0 = none: the settlement is not flooded). */
    static int chipWidth(Graphics2D g, Model.Flood fl) {
        if (fl == null || !fl.flooded()) return 0;
        return g.getFontMetrics(F_CHIP).stringWidth("FLOODED") + 14;
    }

    /** The "FLOODED" chip beside a settlement's name label, while its state is flooded or overflowing. {@code g} has the screen transform. */
    static void chip(Graphics2D g, double x, double y, Model.Flood fl) {
        int w = chipWidth(g, fl);
        if (w == 0) return;
        g.setColor(new Color(150, 30, 26, 235));
        g.fill(new RoundRectangle2D.Double(x, y, w, 17, 9, 9));
        g.setColor(new Color(255, 170, 160, 200));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(x, y, w, 17, 9, 9));
        g.setFont(F_CHIP);
        g.setColor(Color.WHITE);
        g.drawString("FLOODED", (float) (x + 7), (float) (y + 12.5));
    }
}
