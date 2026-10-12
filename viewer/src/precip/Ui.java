package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * A tiny immediate-mode toolkit for the custom-drawn menus, in the game's own look (dark
 * translucent panels, white text, warm yellow accents). A screen draws its widgets every frame;
 * each widget registers the rectangle it occupies together with what a click (or a drag) on it
 * does, and mouse events are resolved against the rectangles of the frame last painted. Everything
 * runs on the Swing event thread.
 */
final class Ui {
    // ---- look
    static final Color TEXT = new Color(236, 242, 250), DIM = new Color(150, 170, 195), FAINT = new Color(110, 126, 150),
            ACCENT = new Color(255, 226, 140), BLUE = new Color(120, 180, 255), RED = new Color(255, 120, 120),
            AMBER = new Color(255, 190, 90), GREEN = new Color(120, 224, 160);
    static final Color PANEL = new Color(10, 14, 24, 250), PANEL_SOFT = new Color(8, 12, 22, 170), EDGE = new Color(120, 180, 255, 90);
    static final Font F_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11), F_NORM = new Font(Font.SANS_SERIF, Font.PLAIN, 12),
            F_BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 12), F_MED = new Font(Font.SANS_SERIF, Font.PLAIN, 13),
            F_MEDB = new Font(Font.SANS_SERIF, Font.BOLD, 13), F_HEAD = new Font(Font.SANS_SERIF, Font.BOLD, 15),
            F_BIG = new Font(Font.SANS_SERIF, Font.BOLD, 19), F_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 40),
            F_MONO = new Font(Font.MONOSPACED, Font.PLAIN, 11);

    static final int NORMAL = 0, PRIMARY = 1, DANGER = 2, GHOST = 3;

    /** A clickable (or draggable) rectangle of the current frame. */
    private static final class Hit {
        double x, y, w, h;
        Runnable click;
        DoubleConsumer drag;   // receives the pointer's 0..1 position across the rectangle

        boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }
    }

    /** One line of editable text (the caret is always at the end). */
    static final class TextField {
        final StringBuilder text = new StringBuilder();
        final int max;
        final boolean digitsOnly;

        TextField(String initial, int max, boolean digitsOnly) {
            this.max = max;
            this.digitsOnly = digitsOnly;
            set(initial);
        }

        void set(String s) {
            text.setLength(0);
            for (char c : s.toCharArray()) typed(c);
        }

        String value() { return text.toString(); }

        void typed(char c) {
            if (c < 0x20 || c == 0x7f || c == KeyEvent.CHAR_UNDEFINED || text.length() >= max) return;
            if (digitsOnly && !(c >= '0' && c <= '9')) return;
            text.append(c);
        }

        /** Editing keys. True when the key was used. */
        boolean key(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                if (e.isControlDown()) text.setLength(0);
                else if (text.length() > 0) text.setLength(text.length() - 1);
                return true;
            }
            if (e.getKeyCode() == KeyEvent.VK_V && e.isControlDown()) {
                try {
                    Object d = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                    for (char c : String.valueOf(d).toCharArray()) typed(c);
                } catch (Exception ignored) { }
                return true;
            }
            return false;
        }
    }

    Graphics2D g;
    /** Pointer position in logical window coordinates (-1 when outside). */
    int mx = -1, my = -1;
    /** False while a screen that is covered by another one is painted: it draws but takes no input. */
    boolean interactive = true;
    /**
     * Origin of the screen being painted, in window coordinates: a screen drawn inside another one (the Debug mode's
     * Hurricane and World map tabs) paints with {@code g} translated by this much, and its widgets are registered and
     * hovered at the shifted place. (0, 0) for every ordinary screen; reset by {@link #begin}.
     */
    double ox, oy;
    private final List<Hit> hits = new ArrayList<>();
    private Hit pressed;
    private boolean down;

    void begin(Graphics2D g) {
        this.g = g;
        hits.clear();
        interactive = true;
        ox = 0; oy = 0;
    }

    // ------------------------------------------------------------------ input

    /** Mouse press. True when it landed on a widget. */
    boolean press(int x, int y) {
        mx = x; my = y;
        pressed = null;
        for (int k = hits.size() - 1; k >= 0; k--) {
            if (hits.get(k).contains(x, y)) { pressed = hits.get(k); break; }
        }
        down = pressed != null;
        if (pressed != null && pressed.drag != null) pressed.drag.accept(frac(pressed, x));
        return pressed != null;
    }

    void drag(int x, int y) {
        mx = x; my = y;
        if (pressed != null && pressed.drag != null) pressed.drag.accept(frac(pressed, x));
    }

    void release(int x, int y) {
        mx = x; my = y;
        Hit p = pressed;
        pressed = null;
        down = false;
        if (p != null && p.click != null && p.contains(x, y)) p.click.run();
    }

    void move(int x, int y) { mx = x; my = y; }

    private static double frac(Hit h, double x) { return Model.clamp((x - h.x) / Math.max(1, h.w), 0, 1); }

    /** Is a widget of the current frame under this point? */
    boolean over(int x, int y) {
        for (Hit h : hits) if (h.contains(x, y)) return true;
        return false;
    }

    boolean hover(double x, double y, double w, double h) {
        return interactive && mx - ox >= x && mx - ox < x + w && my - oy >= y && my - oy < y + h;
    }

    /** Register a clickable rectangle without drawing anything. */
    void hit(double x, double y, double w, double h, Runnable click) {
        if (!interactive || click == null) return;
        Hit hh = new Hit();
        hh.x = x + ox; hh.y = y + oy; hh.w = w; hh.h = h; hh.click = click;
        hits.add(hh);
    }

    private void dragHit(double x, double y, double w, double h, DoubleConsumer drag) {
        if (!interactive) return;
        Hit hh = new Hit();
        hh.x = x + ox; hh.y = y + oy; hh.w = w; hh.h = h; hh.drag = drag;
        hits.add(hh);
    }

    // ------------------------------------------------------------------ drawing helpers

    void fill(double x, double y, double w, double h, double arc, Color c) {
        g.setColor(c);
        g.fill(new RoundRectangle2D.Double(x, y, w, h, arc, arc));
    }

    void stroke(double x, double y, double w, double h, double arc, Color c, float width) {
        g.setColor(c);
        g.setStroke(new BasicStroke(width));
        g.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 1, h - 1, arc, arc));
    }

    /** A dialog-style panel. */
    void panel(double x, double y, double w, double h) {
        fill(x, y, w, h, 16, PANEL);
        stroke(x, y, w, h, 16, EDGE, 1f);
    }

    void dim(int w, int h, int alpha) {
        g.setColor(new Color(2, 5, 12, alpha));
        g.fill(new Rectangle2D.Double(0, 0, w, h));
    }

    void text(String s, double x, double y, Font f, Color c) {
        g.setFont(f);
        g.setColor(c);
        g.drawString(s, (float) x, (float) y);
    }

    /** Text cut with an ellipsis so that it fits {@code maxW}. */
    void textFit(String s, double x, double y, double maxW, Font f, Color c) { text(fit(s, f, maxW), x, y, f, c); }

    void textCenter(String s, double cx, double y, Font f, Color c) {
        g.setFont(f);
        text(s, cx - g.getFontMetrics().stringWidth(s) / 2.0, y, f, c);
    }

    void textRight(String s, double right, double y, Font f, Color c) {
        g.setFont(f);
        text(s, right - g.getFontMetrics().stringWidth(s), y, f, c);
    }

    int width(String s, Font f) { return g.getFontMetrics(f).stringWidth(s); }

    String fit(String s, Font f, double maxW) {
        FontMetrics fm = g.getFontMetrics(f);
        if (fm.stringWidth(s) <= maxW) return s;
        String dots = "…";
        int n = s.length();
        while (n > 0 && fm.stringWidth(s.substring(0, n).stripTrailing() + dots) > maxW) n--;
        return s.substring(0, n).stripTrailing() + dots;
    }

    /** Word-wrap to lines no wider than {@code maxW}; explicit newlines are kept. */
    List<String> wrap(String s, Font f, double maxW) {
        FontMetrics fm = g.getFontMetrics(f);
        List<String> out = new ArrayList<>();
        for (String para : s.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String tryLine = line.length() == 0 ? word : line + " " + word;
                if (fm.stringWidth(tryLine) <= maxW || line.length() == 0) {
                    line.setLength(0);
                    line.append(tryLine);
                } else {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            out.add(fit(line.toString(), f, maxW));
        }
        return out;
    }

    // ------------------------------------------------------------------ widgets

    void button(String label, double x, double y, double w, double h, int style, boolean enabled, boolean focused, Runnable onClick) {
        boolean hov = enabled && hover(x, y, w, h);
        boolean press = hov && down && pressed != null && pressed.contains(x + ox + 1, y + oy + 1);
        Color base, hi;
        switch (style) {
            case PRIMARY -> { base = new Color(52, 104, 196, 245); hi = new Color(74, 130, 228, 250); }
            case DANGER -> { base = new Color(142, 44, 54, 245); hi = new Color(184, 58, 70, 250); }
            case GHOST -> { base = new Color(255, 255, 255, 14); hi = new Color(255, 255, 255, 40); }
            default -> { base = new Color(34, 44, 68, 240); hi = new Color(52, 68, 104, 245); }
        }
        Color fillC = !enabled ? new Color(30, 34, 46, 200) : press ? Renderer.mul(hi, 0.85) : hov ? hi : base;
        fill(x, y, w, h, 10, fillC);
        if (focused && enabled) stroke(x, y, w, h, 10, ACCENT, 1.6f);
        else stroke(x, y, w, h, 10, new Color(255, 255, 255, enabled ? 34 : 14), 1f);
        Font f = h >= 40 ? F_HEAD : h >= 30 ? F_MEDB : F_BOLD;
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        String s = fit(label, f, w - 14);
        text(s, x + (w - fm.stringWidth(s)) / 2.0, y + (h + fm.getAscent() - fm.getDescent()) / 2.0, f, enabled ? TEXT : FAINT);
        if (enabled) hit(x, y, w, h, onClick);
    }

    /** A row of mutually exclusive choices. */
    void seg(String[] labels, int selected, double x, double y, double w, double h, IntConsumer onPick) {
        fill(x, y, w, h, 10, new Color(20, 26, 42, 240));
        double cw = w / labels.length;
        for (int k = 0; k < labels.length; k++) {
            double cx = x + k * cw;
            boolean sel = k == selected, hov = hover(cx, y, cw, h);
            if (sel) fill(cx + 2, y + 2, cw - 4, h - 4, 8, new Color(58, 110, 200, 245));
            else if (hov) fill(cx + 2, y + 2, cw - 4, h - 4, 8, new Color(255, 255, 255, 26));
            Font f = sel ? F_BOLD : F_NORM;
            // a narrow cell (the 13 "Starts in" months): the bold label of the chosen cell must not be cut ("Au…"), so it stays in the regular weight
            if (sel && g.getFontMetrics(F_BOLD).stringWidth(labels[k]) > cw - 8) f = F_NORM;
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            String s = fit(labels[k], f, cw - 8);
            text(s, cx + (cw - fm.stringWidth(s)) / 2.0, y + (h + fm.getAscent() - fm.getDescent()) / 2.0, f, sel ? Color.WHITE : TEXT);
            final int idx = k;
            hit(cx, y, cw, h, () -> onPick.accept(idx));
        }
        stroke(x, y, w, h, 10, new Color(255, 255, 255, 30), 1f);
    }

    /** An on/off switch (46 x 24). */
    void toggle(boolean on, double x, double y, Runnable onClick) {
        double w = 46, h = 24;
        boolean hov = hover(x, y, w, h);
        fill(x, y, w, h, h, on ? new Color(58, 140, 210, 250) : new Color(44, 52, 72, 245));
        if (hov) stroke(x, y, w, h, h, new Color(255, 255, 255, 90), 1f);
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(on ? x + w - h + 3 : x + 3, y + 3, h - 6, h - 6));
        hit(x, y, w, h, onClick);
    }

    /** A horizontal slider; {@code onChange} gets 0..1 while the knob is dragged. */
    void slider(double frac, double x, double y, double w, DoubleConsumer onChange) {
        double h = 24, ty = y + h / 2;
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(44, 52, 72));
        g.draw(new Line2D.Double(x + 8, ty, x + w - 8, ty));
        double kx = x + 8 + (w - 16) * Model.clamp(frac, 0, 1);
        g.setColor(new Color(74, 130, 228));
        g.draw(new Line2D.Double(x + 8, ty, kx, ty));
        g.setColor(hover(x, y, w, h) ? Color.WHITE : new Color(226, 234, 246));
        g.fill(new Ellipse2D.Double(kx - 8, ty - 8, 16, 16));
        dragHit(x, y, w, h, v -> onChange.accept(Model.clamp((v * w - 8) / Math.max(1, w - 16), 0, 1)));
    }

    /** A text box. */
    void field(TextField tf, String placeholder, double x, double y, double w, double h, boolean focused, Runnable onFocus) {
        fill(x, y, w, h, 8, new Color(18, 24, 38, 250));
        stroke(x, y, w, h, 8, focused ? ACCENT : new Color(255, 255, 255, 50), focused ? 1.5f : 1f);
        Font f = F_MED;
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        double ty = y + (h + fm.getAscent() - fm.getDescent()) / 2.0;
        String v = tf.value();
        Shape clip = g.getClip();
        g.clip(new Rectangle2D.Double(x + 6, y, w - 12, h));
        double tw = fm.stringWidth(v), tx = x + 10 - Math.max(0, tw - (w - 26));
        if (v.isEmpty() && !focused) text(placeholder, x + 10, ty, f, FAINT);
        else text(v, tx, ty, f, TEXT);
        if (focused && System.currentTimeMillis() / 520 % 2 == 0) {
            g.setColor(ACCENT);
            g.fill(new Rectangle2D.Double(tx + tw + 1, y + 6, 1.4, h - 12));
        }
        g.setClip(clip);
        hit(x, y, w, h, onFocus);
    }

    /** Draw the standard dialog frame centred in the window; returns its {x, y, w, h}. */
    double[] dialog(int winW, int winH, double dw, double dh, String title) {
        double w = Math.min(dw, winW - 24), h = Math.min(dh, winH - 24);
        double x = Math.round((winW - w) / 2), y = Math.round((winH - h) / 2);
        panel(x, y, w, h);
        if (title != null) text(title, x + 24, y + 36, F_BIG, Color.WHITE);
        return new double[]{x, y, w, h};
    }
}
