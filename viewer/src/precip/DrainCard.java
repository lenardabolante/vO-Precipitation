package precip;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Map;

/**
 * The drains card of the game view (protocol v2.4): one settlement's storm drains and whether it floods. It stands
 * under the Climate panel and reads the settlement under the middle of the view, else the nearest one in view: its
 * name and kind, the pipe size and class, the rain reaching the drains against what they can take, the depth of
 * water on the street, the state as a chip and "flooded: yes / no" in words, and how the sizes were chosen. The
 * small buttons on it (pipe size, the four modes) belong to {@link GameHud}; every number is the simulation's.
 * <p>
 * One instance per renderer: the text is painted into an image that is refreshed only when something on it changed.
 */
final class DrainCard {
    static final int W = Renderer.CLIMATE_W, H = 138;
    /** Baseline of the pipe row and top of the mode chips, from the card's top. */
    static final int PIPE_ROW = 56, CHIPS_TOP = 112, CHIP_H = 18;
    static final String[] MODES = {"small", "normal", "large", "random"}, MODE_LABELS = {"Small", "Normal", "Large", "Random"};

    private static final Font F_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11), F_BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 12),
            F_NAME = new Font(Font.SANS_SERIF, Font.BOLD, 13), F_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 14);
    private static final Color INK = new Color(236, 242, 250), DIM = new Color(150, 170, 195), GOLD = new Color(255, 226, 140);

    private BufferedImage img;
    private String key = "";
    private double dev;

    /** The settlement the card is about: the one under the view's centre, else the nearest in view; null = none in view has drains. */
    static Model.Settlement pick(Model.Hello hello, Map<Long, Model.Drain> drains, Camera.View v) {
        if (hello == null || drains.isEmpty()) return null;
        Model.Settlement best = null;
        double bestD = Double.MAX_VALUE;
        for (Model.Settlement s : hello.settlements) {
            if (!drains.containsKey(s.id) || !v.seesX(s.x0, s.x1)) continue;
            double d = v.cx >= s.x0 && v.cx <= s.x1 ? -1 : Math.min(Math.abs(v.cx - s.x0), Math.abs(v.cx - s.x1));
            if (d < bestD) { bestD = d; best = s; }
        }
        return best;
    }

    /** "flooded: yes" while the state is flooded or overflowing, else "flooded: no". */
    static String floodedWord(Model.Flood fl) { return fl != null && fl.flooded() ? "flooded: yes" : "flooded: no"; }

    /**
     * The card's text: {name and kind, pipe, inflow against capacity, water on the street, state, flooded yes / no, mode}.
     * A settlement the simulation has not reported a flood state for yet reads as dry.
     */
    static String[] lines(Model.Settlement s, Model.Drain d, Model.Flood fl, String mode) {
        String name = (s.name.isEmpty() ? s.kind : s.name) + "  ·  " + s.kind;
        String pipe = String.format(Locale.ROOT, "%s px  ·  %s", DrainPainter.trim(d.size), d.cls);
        double cap = fl != null && fl.cap > 0 ? fl.cap : d.cap;
        String inflow = String.format(Locale.ROOT, "%.0f / %.0f px²/s", fl == null ? 0 : fl.inflow, cap);
        String water = fl == null || fl.depth < 0.05 ? "none standing" : String.format(Locale.ROOT, "%.1f px deep", fl.depth);
        return new String[]{name, pipe, inflow, water, fl == null ? "dry" : fl.state, floodedWord(fl), mode == null || mode.isEmpty() ? "normal" : mode};
    }

    /** Draw the card with its top-left corner at canvas (x, y). {@code g} has the screen transform. */
    void draw(Graphics2D g, Camera.View v, int x, int y, Model.Settlement s, Model.Drain d, Model.Flood fl, String mode, java.awt.geom.AffineTransform scrTx) {
        String[] l = lines(s, d, fl, mode);
        double cap = fl != null && fl.cap > 0 ? fl.cap : d.cap, load = fl == null || cap <= 0 ? 0 : fl.inflow / cap;
        String k = String.join("|", l) + "|" + Math.round(load * 80) + "|" + (fl != null && fl.flooded());
        int iw = (int) Math.ceil(W * v.dev) + 2, ih = (int) Math.ceil(H * v.dev) + 2;
        if (img == null || img.getWidth() != iw || img.getHeight() != ih || dev != v.dev || !k.equals(key)) {
            if (img == null || img.getWidth() != iw || img.getHeight() != ih) img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D cg = img.createGraphics();
            cg.setComposite(AlphaComposite.Clear);
            cg.fillRect(0, 0, iw, ih);
            cg.setComposite(AlphaComposite.SrcOver);
            cg.setRenderingHints(g.getRenderingHints());
            cg.scale(v.dev, v.dev);
            paint(cg, 0, 0, l, load);
            cg.dispose();
            key = k;
            dev = v.dev;
        }
        g.setTransform(new java.awt.geom.AffineTransform());
        g.drawImage(img, (int) Math.floor(v.devX + v.dev * x + 0.5), (int) Math.floor(v.devY + v.dev * y + 0.5), null);
        g.setTransform(scrTx);
    }

    /** The card itself (no caching): {@code l} = {@link #lines}, {@code load} = inflow / capacity. */
    static void paint(Graphics2D g, int x, int y, String[] l, double load) {
        final int w = W;
        g.setColor(new Color(8, 12, 22, 178));
        g.fill(new RoundRectangle2D.Double(x, y, w, H, 12, 12));
        g.setFont(F_TITLE);
        g.setColor(Color.WHITE);
        g.drawString("DRAINS", x + 10, y + 19);
        // the state as a chip in its own colour
        Color sc = DrainPainter.stateColor(l[4]);
        g.setFont(F_BOLD);
        int sw = g.getFontMetrics().stringWidth(l[4]) + 14;
        g.setColor(Renderer.alpha(sc, 46));
        g.fill(new RoundRectangle2D.Double(x + w - sw - 10, y + 6, sw, 18, 12, 12));
        g.setColor(sc);
        g.drawString(l[4], x + w - sw - 3, y + 19);
        g.setFont(F_NAME);
        g.setColor(GOLD);
        g.drawString(fit(l[0], g.getFontMetrics(), w - 20), x + 10, y + 37);
        g.setFont(F_SMALL);
        FontMetrics fm = g.getFontMetrics();
        row(g, "Pipe", l[1], x, y + PIPE_ROW);
        // the rain reaching the drains against what they take: the bar is full at capacity
        int by = y + 73;
        g.setColor(DIM);
        g.drawString("Inflow", x + 10, by);
        double bx = x + 62, bw = 84;
        g.setColor(new Color(255, 255, 255, 24));
        g.fill(new RoundRectangle2D.Double(bx, by - 8, bw, 7, 7, 7));
        if (load > 0.004) {
            g.setColor(DrainPainter.loadColor(load));
            g.fill(new RoundRectangle2D.Double(bx, by - 8, Math.max(4, bw * Math.min(1, load)), 7, 7, 7));
        }
        g.setColor(INK);
        g.drawString(fit(l[2], fm, (int) (w - 10 - (bx + bw + 8 - x))), (float) (bx + bw + 8), by);
        // the water on the street, and the answer in words
        int wy = y + 90;
        g.setColor(DIM);
        g.drawString("Water", x + 10, wy);
        g.setColor(INK);
        g.drawString(l[3], x + 62, wy);
        boolean yes = l[5].endsWith("yes");
        g.setFont(F_BOLD);
        g.setColor(yes ? DrainPainter.RED : DrainPainter.GREEN);
        g.drawString(l[5], x + w - 10 - g.getFontMetrics().stringWidth(l[5]), wy);
        // how the sizes were chosen: the four modes as chips (GameHud makes them clickable), the one in force lit
        g.setFont(F_SMALL);
        g.setColor(DIM);
        g.drawString("Sizes", x + 10, y + 105);
        g.setColor(l[6].equals("custom") ? DrainPainter.AMBER : INK);
        g.drawString(l[6].equals("custom") ? "custom (changed by hand)" : l[6].equals("normal") ? "normal: by the kind of settlement" : l[6] + " everywhere", x + 62, y + 105);
        double cw = chipWidth();
        for (int k = 0; k < MODES.length; k++) {
            double cx = x + 10 + k * (cw + 4), cy = y + CHIPS_TOP;
            boolean on = MODES[k].equals(l[6]);
            g.setColor(on ? new Color(58, 110, 200, 245) : new Color(34, 44, 68, 235));
            g.fill(new RoundRectangle2D.Double(cx, cy, cw, CHIP_H, 8, 8));
            g.setColor(new Color(255, 255, 255, on ? 90 : 44));
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Double(cx + 0.5, cy + 0.5, cw - 1, CHIP_H - 1, 8, 8));
            g.setFont(on ? F_BOLD : F_SMALL);
            FontMetrics cf = g.getFontMetrics();
            g.setColor(on ? Color.WHITE : INK);
            g.drawString(MODE_LABELS[k], (float) (cx + (cw - cf.stringWidth(MODE_LABELS[k])) / 2), (float) (cy + 13));
        }
    }

    /** Width of one of the four mode chips. */
    static double chipWidth() { return (W - 20 - 3 * 4) / 4.0; }

    private static void row(Graphics2D g, String label, String value, int x, int baseline) {
        g.setColor(DIM);
        g.drawString(label, x + 10, baseline);
        g.setColor(INK);
        g.drawString(value, x + 62, baseline);
    }

    private static String fit(String s, FontMetrics fm, int w) {
        if (fm.stringWidth(s) <= w) return s;
        int n = s.length();
        while (n > 0 && fm.stringWidth(s.substring(0, n).stripTrailing() + "…") > w) n--;
        return s.substring(0, n).stripTrailing() + "…";
    }

    /** The card's rectangle on the canvas for a top-left corner. */
    static Rectangle2D rect(int x, int y) { return new Rectangle2D.Double(x, y, W, H); }
}
