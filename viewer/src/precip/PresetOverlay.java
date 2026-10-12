package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * What a region preset (protocol v2.2, {@code hello.preset}) adds to the game view: the landmark
 * labels standing on the terrain and the small region card in the left panel. All text and numbers
 * are the simulation's; this class only places them.
 */
final class PresetOverlay {
    private PresetOverlay() {}

    private static final Font F_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11), F_BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 12),
            F_NAME = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private static final Color INK = new Color(236, 242, 250), DIM = new Color(150, 170, 195), SOFT = new Color(200, 212, 228),
            GOLD = new Color(255, 226, 140);
    /** Text measuring without a target (the card's height is needed before anything is painted). */
    private static final Graphics2D MEASURE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();

    // ------------------------------------------------------------------ landmarks

    /**
     * Label the preset's landmarks: a pin on the surface and "name · real elevation" above it. The
     * highest places are placed first; a label that would cover one already placed (panels, minimap,
     * settlement names, other landmarks) tries a little higher and is otherwise left out, so far-out
     * views keep only the labels that fit. Identical in every band (same order, same list).
     *
     * @param placed rectangles (canvas coordinates) already taken; the labels drawn are added to it
     */
    static void landmarks(Graphics2D g, Camera.View v, Model.Hello hello, List<Rectangle2D> placed, double bandSy0, double bandSy1) {
        Preset ps = hello == null ? null : hello.preset;
        if (ps == null || ps.landmarks.isEmpty()) return;
        List<Preset.Landmark> order = new ArrayList<>(ps.landmarks);
        order.sort((a, b) -> Double.compare(Double.isNaN(b.elevM) ? -1e9 : b.elevM, Double.isNaN(a.elevM) ? -1e9 : a.elevM));
        boolean compact = v.zoom < 0.42;
        FontMetrics fb = g.getFontMetrics(F_BOLD), fs = g.getFontMetrics(F_SMALL);
        for (Preset.Landmark l : order) {
            if (l.x < v.wx0 - 4 || l.x > v.wx1 + 4) continue;
            double sx = v.sx(l.x), sy = v.sy(hello.terrain.at(l.x));
            if (sy < v.vy - 4 || sy > v.vy + v.vh + 40) continue;
            String elev = Preset.metres(l.elevM);
            int nameW = fb.stringWidth(l.name), elevW = elev.isEmpty() ? 0 : fs.stringWidth(elev);
            int w = compact || elev.isEmpty() ? nameW + 16 : nameW + elevW + 26, h = 19;
            double x = Model.clamp(sx - w / 2.0, v.vx + 4, v.vx + v.vw - w - 4);
            double y = Double.NaN;
            for (double lift : new double[]{30, 54, 78, 102}) {
                double cy = Math.max(v.vy + 4, sy - lift - h);
                if (cy + h > v.vy + v.vh - 4) continue;
                Rectangle2D r = new Rectangle2D.Double(x - 3, cy - 3, w + 6, h + 6);
                boolean hit = false;
                for (Rectangle2D o : placed) if (o.intersects(r)) { hit = true; break; }
                if (!hit) { y = cy; break; }
            }
            if (Double.isNaN(y)) continue;                       // no room: this landmark stays unlabelled at this zoom
            placed.add(new Rectangle2D.Double(x - 3, y - 3, w + 6, h + 6));
            if (y > bandSy1 || sy + 6 < bandSy0) continue;
            // leader and pin
            g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(255, 236, 170, 150));
            g.draw(new Line2D.Double(sx, y + h, sx, sy - 5));
            Path2D.Double pin = new Path2D.Double();
            pin.moveTo(sx, sy - 7); pin.lineTo(sx - 4.5, sy + 1); pin.lineTo(sx + 4.5, sy + 1); pin.closePath();
            g.setColor(new Color(20, 16, 8, 190));
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(pin);
            g.setColor(GOLD);
            g.fill(pin);
            // label
            g.setColor(new Color(22, 18, 10, 178));
            g.fill(new RoundRectangle2D.Double(x, y, w, h, 12, 12));
            g.setColor(new Color(255, 226, 140, 120));
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Double(x, y, w, h, 12, 12));
            g.setFont(F_BOLD);
            g.setColor(new Color(255, 244, 214));
            g.drawString(l.name, (float) (x + 8), (float) (y + 14));
            if (!compact && !elev.isEmpty()) {
                g.setFont(F_SMALL);
                g.setColor(new Color(226, 214, 190));
                g.drawString(elev, (float) (x + 8 + nameW + 10), (float) (y + 14));
            }
        }
    }

    // ------------------------------------------------------------------ region card

    /** The card's lines as {style, text}: style 0 = label/value row "Label|value", 1 = soft wrapped text, 2 = dim wrapped text. */
    private static List<String[]> lines(Preset p, int w) {
        List<String[]> out = new ArrayList<>();
        FontMetrics fs = MEASURE.getFontMetrics(F_SMALL);
        int textW = w - 20;
        if (!p.transect.isEmpty()) for (String s : wrap(p.transect, fs, textW)) out.add(new String[]{"1", s});
        StringBuilder geo = new StringBuilder();
        if (!Double.isNaN(p.spanKm)) geo.append(String.format("%,.0f km across", p.spanKm));
        if (!Double.isNaN(p.maxElevM)) geo.append(geo.length() > 0 ? " · " : "").append("highest ").append(Preset.metres(p.maxElevM));
        if (geo.length() > 0) out.add(new String[]{"0", "Section|" + geo});
        if (p.biome != null) out.add(new String[]{"0", "Biome|" + Preset.Biome.word(p.biome.id)});          // v2.3
        if (!p.station.isEmpty()) out.add(new String[]{"0", "Station|" + p.station});
        if (!Double.isNaN(p.tempC)) out.add(new String[]{"0", "Mean temp|" + String.format("%.1f °C  (annual)", p.tempC)});
        if (!Double.isNaN(p.annualPrecipMm)) out.add(new String[]{"0", "Precip|" + String.format("%,.0f mm per year", p.annualPrecipMm)});
        if (!p.wind.isEmpty()) out.add(new String[]{"0", "Wind|" + p.wind});
        if (!p.note.isEmpty()) for (String s : wrap(p.note, fs, textW)) out.add(new String[]{"2", s});
        out.add(new String[]{"0", "Sources|" + (p.sources.isEmpty() ? "none listed" : p.sources.size() + (p.sources.size() == 1 ? " cited source" : " cited sources"))});
        return out;
    }

    /** Height of the region card for a panel {@code w} px wide. */
    static int cardHeight(Preset p, int w) { return 42 + lines(p, w).size() * 15 + 6; }

    /** The region card: a pill of its own under the stats, in the left panel's look. */
    static void paintCard(Graphics2D g, Preset p, int x, int y, int w) {
        List<String[]> ls = lines(p, w);
        int h = 42 + ls.size() * 15 + 6;
        g.setColor(new Color(8, 12, 22, 165));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 12, 12));
        g.setFont(F_BOLD);
        g.setColor(Color.WHITE);
        g.drawString("REGION", x + 10, y + 17);
        g.setFont(F_SMALL);
        g.setColor(DIM);
        String tag = "real terrain & climate";
        g.drawString(tag, x + w - 10 - g.getFontMetrics().stringWidth(tag), y + 17);
        g.setFont(F_NAME);
        g.setColor(GOLD);
        g.drawString(fit(p.name, g.getFontMetrics(), w - 20), x + 10, y + 35);
        int ry = y + 52;
        g.setFont(F_SMALL);
        FontMetrics fm = g.getFontMetrics();
        for (String[] l : ls) {
            switch (l[0]) {
                case "0" -> {
                    int bar = l[1].indexOf('|');
                    g.setColor(DIM);
                    g.drawString(l[1].substring(0, bar), x + 10, ry);
                    g.setColor(INK);
                    g.drawString(fit(l[1].substring(bar + 1), fm, w - 86), x + 78, ry);
                }
                case "1" -> { g.setColor(SOFT); g.drawString(l[1], x + 10, ry); }
                default -> { g.setColor(DIM); g.drawString(l[1], x + 10, ry); }
            }
            ry += 15;
        }
    }

    private static String fit(String s, FontMetrics fm, int w) {
        if (fm.stringWidth(s) <= w) return s;
        int n = s.length();
        while (n > 0 && fm.stringWidth(s.substring(0, n).stripTrailing() + "…") > w) n--;
        return s.substring(0, n).stripTrailing() + "…";
    }

    static List<String> wrap(String s, FontMetrics fm, int w) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.trim().split("\\s+")) {
            String t = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(t) <= w || line.length() == 0) { line.setLength(0); line.append(t); }
            else { out.add(line.toString()); line.setLength(0); line.append(word); }
        }
        if (line.length() > 0) out.add(fit(line.toString(), fm, w));
        return out;
    }
}
