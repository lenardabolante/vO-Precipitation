package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;

/**
 * Protocol v2.3 tornadoes, drawn in world coordinates: the condensation funnel hanging from the
 * wall cloud (tapered from {@code top_width} to {@code width}, bent by {@code lean}, as far down as
 * {@code reach} says), the bands that turn around it with {@code phase}, the dust and debris cloud
 * at its foot, and the scar it leaves on the ground between {@code path_x0} and {@code x}.
 * Everything is a pure function of the tornado's numbers, so the parallel render bands agree.
 */
final class TornadoPainter {
    private TornadoPainter() {}

    private static final int N = 24;            // samples along the funnel (for its bounding box)

    private static double sm(double x) {
        x = x < 0 ? 0 : (x > 1 ? 1 : x);
        return x * x * (3 - 2 * x);
    }

    /** Centre x of the funnel at fraction {@code g} of the way from the cloud base (0) to the ground (1). */
    static double axisX(Model.Tornado t, double g) {
        double up = 1 - g;
        double wob = Math.sin(t.phase * 0.55 + g * 4.4) * Math.min(t.width, 60) * 0.20 * g * up * 4 * (0.4 + 0.6 * t.reach);
        return t.x + t.lean * Math.pow(up, 1.55) + wob;
    }

    /** Half-width of the funnel at fraction {@code s} of its own length (0 = at the cloud, 1 = its lower end). */
    static double halfWidth(Model.Tornado t, double s) {
        double top = t.topWidth * 0.5;
        // aloft the lower end is a rounded point; on the ground it is the tornado's width
        double tip = t.width * 0.5 * (0.18 + 0.82 * sm((t.reach - 0.86) / 0.14));
        double hw = tip + (top - tip) * Math.pow(1 - s, 2.3);
        if (t.reach < 0.995) hw *= 0.35 + 0.65 * Math.sqrt(Math.max(0, 1 - Math.pow(s, 6)));   // round the hanging end off
        return Math.max(0.4, hw);
    }

    /** The condensation funnel of one tornado as a device-pixel image (built once per frame, blitted by every band). */
    static final class Funnel {
        /** Premultiplied ARGB, {@code w} x {@code h}, to be put at device ({@code dx}, {@code dy}). */
        int[] px;
        int w, h;
        private java.awt.image.BufferedImage img;
        int dx, dy;

        /** The same pixels as an image (only made when a band cannot blit the array directly). */
        synchronized java.awt.image.BufferedImage image() {
            if (img == null) {
                img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB_PRE);
                img.getRaster().setDataElements(0, 0, w, h, px);
            }
            return img;
        }
        /** What it was rendered for (simulation frame, view, light): the window repaints twice per simulation frame, so every other paint reuses it. */
        long sig;
    }

    // lookup tables for the scanline rasteriser: the angle and the facing of a point across the column, and a cosine
    private static final int UT = 512, CT = 2048;
    private static final float[] FACING = new float[UT + 1], BAND_LIGHT = new float[CT], BAND_DARK = new float[CT];
    /** Three times the angle of a point across the column, in cosine-table steps. */
    private static final int[] THETA3 = new int[UT + 1];

    static {
        for (int i = 0; i <= UT; i++) {
            double u = Math.max(-1, Math.min(1, i * 2.0 / UT - 1));
            THETA3[i] = (int) Math.round(3 * Math.asin(u * 0.985) * CT / (2 * Math.PI));
            FACING[i] = (float) Math.sqrt(Math.max(0, 1 - u * u));
        }
        for (int i = 0; i < CT; i++) {
            // six helices: three light (cos > 0), three dark (cos < 0); the fourth power makes them narrow bands
            double c = Math.cos(i * 2 * Math.PI / CT), c4 = c * c * c * c;
            BAND_LIGHT[i] = c > 0 ? (float) (c4 * 0.25) : 0f;
            BAND_DARK[i] = c < 0 ? (float) (c4 * 0.34) : 0f;
        }
    }

    /**
     * Render the funnel of {@code t} for this view, row by row straight into the image's pixels (no
     * Java2D shapes: a funnel is a few thousand to a few hundred thousand pixels and this runs on the
     * frame thread). Every row is one span between the two edges of the column: a left-to-right
     * shading so it reads as a round column lit from one side, the helical bands that turn round it
     * with {@code phase}, soft anti-aliased edges, and an alpha that thins toward the lower end while
     * the funnel is aloft and everywhere as it ropes out. Returns null when nothing shows.
     */
    static Funnel render(Model.Tornado t, Lit lit, Camera.View v) {
        final double span = t.groundY - t.baseY;
        if (span < 4 || t.width < 0.3) return null;
        final double yTop = t.baseY - Math.min(46, span * 0.14), yTip = t.baseY + span * t.reach;
        if (yTip - yTop < 1) return null;
        final double collar = Math.min(0.5, (t.baseY - yTop + Math.min(30, span * 0.09)) / (yTip - yTop));   // the top fades in over this share
        double minX = 1e18, maxX = -1e18;
        for (int i = 0; i <= N; i++) {
            double s = i / (double) N, y = yTop + (yTip - yTop) * s;
            double cx = axisX(t, Model.clamp((y - t.baseY) / span, 0, 1)), hw = halfWidth(t, s);
            minX = Math.min(minX, cx - hw); maxX = Math.max(maxX, cx + hw);
        }
        int x0 = (int) Math.floor(v.ws * (minX - 3) + v.wtx), x1 = (int) Math.ceil(v.ws * (maxX + 3) + v.wtx);
        int y0 = (int) Math.floor(v.ws * yTop + v.wty), y1 = (int) Math.ceil(v.ws * yTip + v.wty);
        // only the part inside the viewport (plus a margin) is needed
        x0 = Math.max(x0, v.dvx0 - 8); x1 = Math.min(x1, v.dvx1 + 8); y0 = Math.max(y0, v.dvy0 - 8); y1 = Math.min(y1, v.dvy1 + 8);
        final int w = x1 - x0, h = y1 - y0;
        if (w < 2 || h < 2 || (long) w * h > 8_000_000L) return null;

        // colours: storm grey, darker when strong; lifted towards the sky so the far side stays readable at night
        double dark = 0.35 + 0.4 * t.strength;
        Color core = Lit.mix(lit.lit(new Color(150, 156, 166)), lit.skyBot, 0.22);
        Color mid = Lit.mix(lit.lit(Lit.mix(new Color(112, 118, 130), new Color(70, 74, 84), dark)), lit.skyBot, 0.16);
        Color edge = Lit.mix(lit.lit(Lit.mix(new Color(84, 90, 102), new Color(40, 44, 54), dark)), lit.skyBot, 0.12);
        Color lightBand = Lit.mix(core, Color.WHITE, 0.30), darkBand = Lit.mul(edge, 0.72);
        final double lightSide = lit.day > 0.2 ? 0.30 : 0.36;
        final float[] pr = new float[UT + 1], pg = new float[UT + 1], pb = new float[UT + 1];
        for (int i = 0; i <= UT; i++) {
            double q = i / (double) UT;
            Color c = q < lightSide ? Lit.mix(edge, core, Lit.smooth(q / lightSide)) : q < 0.66 ? Lit.mix(core, mid, (q - lightSide) / (0.66 - lightSide)) : Lit.mix(mid, edge, Lit.smooth((q - 0.66) / 0.34));
            pr[i] = c.getRed(); pg[i] = c.getGreen(); pb[i] = c.getBlue();
        }
        final float lr = lightBand.getRed(), lg = lightBand.getGreen(), lb = lightBand.getBlue(), dr = darkBand.getRed(), dg = darkBand.getGreen(), db = darkBand.getBlue();
        final boolean bands = v.zoom > 0.22, down = t.onGround();
        final double life = sm(t.width / 5.0);                 // a roping tornado thins out of sight
        final double aTop = life * 0.97, aTip = life * (down ? 0.90 : 0.34);
        final double ws = v.ws, inv = 1 / ws, cK = CT / (2 * Math.PI);

        final int[] px = new int[w * h];
        for (int j = 0; j < h; j++) {
            double wy = (y0 + j + 0.5 - v.wty) * inv;
            if (wy < yTop || wy > yTip) continue;
            double s = (wy - yTop) / (yTip - yTop);
            double cx = axisX(t, Model.clamp((wy - t.baseY) / span, 0, 1)), hw = halfWidth(t, s);
            double xl = (cx - hw) * ws + v.wtx - x0, xr = (cx + hw) * ws + v.wtx - x0, hwPx = hw * ws, icx = cx * ws + v.wtx - x0;
            int i0 = Math.max(0, (int) Math.floor(xl)), i1 = Math.min(w - 1, (int) Math.floor(xr));
            if (i1 < i0) continue;
            // condensation: full near the cloud, thinner toward the lower end (much thinner while it still hangs in the air)
            double rowA = down ? aTop + (aTip - aTop) * s : s < 0.35 ? aTop * (1 - 0.1 * s / 0.35) : aTop * 0.9 + (aTip - aTop * 0.9) * (s - 0.35) / 0.65;
            rowA *= Math.min(1, (yTip - wy) * ws + 0.5);        // the last row fades instead of ending in a step
            rowA *= sm(s / collar);                             // ... and the top comes out of the cloud base without an edge
            // the edge is soft over an eighth of the half-width (at least a pixel and a half)
            final float softPx = (float) Math.max(1.5, hwPx * 0.125), uK = (float) (UT * 0.5 / Math.max(0.5, hwPx));
            final int a0 = (int) Math.round(3 * (t.phase + s * 9.5) * cK);
            final int row = j * w;
            final float rowAf = (float) rowA, hwF = (float) hwPx;
            // the pixels between the two soft edges all have the row's alpha: no coverage or edge maths for them
            final int in0 = (int) Math.ceil(xl + softPx), in1 = (int) Math.floor(xr - softPx) - 1;
            for (int i = i0; i <= i1; i++) {
                float dxp = (float) (i + 0.5 - icx);
                int ui = (int) ((dxp + hwF) * uK);
                ui = ui < 0 ? 0 : (ui > UT ? UT : ui);
                float al = rowAf;
                if (i < in0 || i > in1) {
                    double cov = Math.min(i + 1, xr) - Math.max(i, xl);
                    if (cov <= 0) continue;
                    float e = (hwF - (dxp < 0 ? -dxp : dxp)) / softPx + 0.15f;
                    al *= (float) (cov > 1 ? 1 : cov) * (e > 1 ? 1 : e);
                    if (al <= 0.004f) continue;
                }
                float r = pr[ui], g = pg[ui], bl = pb[ui];
                if (bands) {
                    // six helices wind round the column: three light, three dark; only their near side shows
                    int ci = (THETA3[ui] - a0) & (CT - 1);
                    float fc = FACING[ui], kl = fc * BAND_LIGHT[ci], kd = fc * BAND_DARK[ci];
                    r += (lr - r) * kl + (dr - r) * kd;
                    g += (lg - g) * kl + (dg - g) * kd;
                    bl += (lb - bl) * kl + (db - bl) * kd;
                }
                px[row + i] = ((int) (al * 255f + 0.5f) << 24) | ((int) (r * al + 0.5f) << 16) | ((int) (g * al + 0.5f) << 8) | (int) (bl * al + 0.5f);
            }
        }
        Funnel f = new Funnel();
        f.px = px;
        f.w = w; f.h = h;
        f.dx = x0; f.dy = y0;
        return f;
    }

    /**
     * What is drawn around the funnel image, in world coordinates ({@code g} has the world transform).
     * @param behind the far side of the debris cloud and the dust whirl (before the funnel); false = the near side (after it)
     */
    static void paint(Graphics2D g, Model.Tornado t, Lit lit, double zoom, boolean behind) {
        double span = t.groundY - t.baseY;
        if (span < 4 || t.width < 0.3) return;
        double life = sm(t.width / 5.0);                      // a roping tornado thins out of sight
        boolean down = t.onGround();
        if (!behind) {
            if (t.debris > 0.02) debris(g, t, lit, false);
            return;
        }
        if (t.debris > 0.02) debris(g, t, lit, true);
        // ---- the dust whirl on the ground before the funnel arrives
        if (!down && t.reach > 0.35) {
            double a = sm((t.reach - 0.35) / 0.5) * life;
            double r = 14 + 26 * a;
            Color d = lit.lit(new Color(150, 132, 108));
            g.setPaint(new RadialGradientPaint(new Point2D.Double(t.x, t.groundY - 3), (float) r, new float[]{0f, 1f},
                    new Color[]{Lit.alpha(d, (int) (120 * a)), Lit.alpha(d, 0)}));
            g.fill(new Ellipse2D.Double(t.x - r, t.groundY - 3 - r * 0.45, r * 2, r * 0.9));
        }
    }

    /** The dust and debris cloud around the foot: a skirt on the ground and lumps that swing round with the vortex. */
    private static void debris(Graphics2D g, Model.Tornado t, Lit lit, boolean behind) {
        double d = t.debris, w = Math.max(10, t.width);
        // (dust stays a little lighter than the storm light, so the cloud at the foot reads against the dark ground)
        Color dust = Lit.mix(lit.lit(new Color(150, 128, 100)), new Color(150, 130, 104), 0.35), dustDark = Lit.mix(lit.lit(new Color(92, 76, 60)), new Color(92, 78, 64), 0.3);
        double gy = t.groundY, rx = w * (1.0 + 1.7 * d) + 22 * d, ry = (12 + w * 0.5) * (0.5 + 0.8 * d);
        if (behind) {
            g.setPaint(new RadialGradientPaint(new Point2D.Double(t.x, gy - ry * 0.35), (float) rx, new float[]{0f, 0.55f, 1f},
                    new Color[]{Lit.alpha(dustDark, (int) (190 * d)), Lit.alpha(dust, (int) (120 * d)), Lit.alpha(dust, 0)}));
            g.fill(new Ellipse2D.Double(t.x - rx, gy - ry * 1.1, rx * 2, ry * 1.5));
        }
        int lumps = 12;
        for (int k = 0; k < lumps; k++) {
            double h1 = Scene.hash01(t.id * 131 + k * 17), h2 = Scene.hash01(t.id * 733 + k * 29);
            double a = t.phase * (0.75 + 0.3 * h1) + k * 2 * Math.PI / lumps;
            double depth = Math.cos(a);                           // > 0 = the near side
            if ((depth < 0) != behind) continue;
            double orbit = rx * (0.45 + 0.5 * h2);
            double px = t.x + Math.sin(a) * orbit;
            double py = gy - 4 - (0.25 + 0.75 * h1) * ry * 1.25 * (0.6 + 0.4 * Math.abs(Math.sin(a * 0.5 + h2 * 6)));
            double r = (9 + w * 0.30) * (0.55 + 0.75 * h2) * Math.sqrt(d);
            Color c = Lit.mix(dust, dustDark, 0.25 + 0.5 * h1 + (behind ? 0.2 : 0));
            g.setPaint(new RadialGradientPaint(new Point2D.Double(px - r * 0.2, py - r * 0.25), (float) (r * 1.15), new float[]{0f, 0.6f, 1f},
                    new Color[]{Lit.alpha(c, (int) ((behind ? 150 : 205) * d)), Lit.alpha(c, (int) ((behind ? 90 : 130) * d)), Lit.alpha(c, 0)}));
            g.fill(new Ellipse2D.Double(px - r * 1.15, py - r * 0.9, r * 2.3, r * 1.8));
        }
    }

    /**
     * The damage path of a tornado: a strip of torn, scoured ground along the terrain surface between
     * {@code x0} and {@code x1}, with scattered splinters. {@code strength} 0..1 fades an old path.
     */
    static void paintScar(Graphics2D g, Model.Terrain terrain, double x0, double x1, double strength, Lit lit, long seed, double vx0, double vx1, double zoom) {
        double a = Math.min(x0, x1) - 10, b = Math.max(x0, x1) + 10;
        if (b < vx0 || a > vx1 || strength <= 0.02) return;
        a = Math.max(a, vx0 - 20);
        b = Math.min(b, vx1 + 20);
        double step = Math.max(terrain.step, 3 / Math.max(0.2, zoom));
        Color soil = lit.lit(new Color(92, 72, 52)), dark = lit.lit(new Color(54, 44, 36));
        Path2D.Double p = new Path2D.Double();
        boolean first = true;
        for (double x = a; x <= b + step * 0.5; x += step) {
            double xx = Math.min(x, b), y = terrain.at(xx) - 2.2 - 1.6 * Scene.noise1(xx * 0.12, seed + 5);
            if (first) { p.moveTo(xx, y); first = false; } else p.lineTo(xx, y);
        }
        for (double x = b; x >= a - step * 0.5; x -= step) {
            double xx = Math.max(x, a);
            p.lineTo(xx, terrain.at(xx) + 4.2 + 1.8 * Scene.noise1(xx * 0.09, seed + 9));
        }
        p.closePath();
        g.setColor(Lit.alpha(soil, (int) (215 * strength)));
        g.fill(p);
        if (zoom < 0.35) return;
        // splinters and clods thrown along the path
        g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (long c = (long) Math.floor(a / 9); c <= (long) Math.floor(b / 9); c++) {
            double h = Scene.hash01(c * 7919L + seed), h2 = Scene.hash01(c * 104729L + seed * 3);
            double x = (c + h) * 9;
            if (x < Math.min(x0, x1) - 6 || x > Math.max(x0, x1) + 6) continue;
            double y = terrain.at(x) - 1 + 4 * h2, len = 2 + 5 * h2, ang = (h - 0.5) * 2.6;
            g.setColor(Lit.alpha(h2 > 0.55 ? dark : lit.lit(new Color(150, 128, 98)), (int) (230 * strength)));
            g.draw(new Line2D.Double(x, y, x + Math.cos(ang) * len, y - Math.abs(Math.sin(ang)) * len * 0.7));
        }
    }
}
