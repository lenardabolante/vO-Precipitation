package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Protocol v2.3 cars, drawn in world coordinates on the road surface: six variants (sedan,
 * hatchback, pickup, van, truck, bus), rotated to the slope, facing {@code dir}, with headlights
 * and tail lights when {@code lights} is set, a wiper sweeping the windscreen when it rains on
 * them, a darker wet body with water beads on the glass and the bodywork (some running down the
 * windows) and a snow cap, thrown through the air ({@code lift}, {@code spin}) by a tornado and
 * left wrecked where they land.
 */
final class CarPainter {
    private CarPainter() {}

    /** The six car variants ({@link #dims} has a case for each; {@code sedan} is the fallback). The Debug mode's line-up draws one of each. */
    static final String[] VARIANTS = {"sedan", "hatchback", "pickup", "van", "truck", "bus"};

    /** Body length, height, wheel radius and the two wheel positions (fractions of the half-length) of a variant. */
    private static double[] dims(String variant) {
        return switch (variant) {
            case "hatchback" -> new double[]{26, 10.5, 3.1, -0.60, 0.62};
            case "pickup" -> new double[]{34, 11.5, 3.5, -0.58, 0.62};
            case "van" -> new double[]{33, 14.0, 3.4, -0.58, 0.62};
            case "truck" -> new double[]{48, 17.0, 3.9, -0.60, 0.70};
            case "bus" -> new double[]{56, 16.0, 3.9, -0.62, 0.64};
            default -> new double[]{30, 10.5, 3.2, -0.58, 0.60};
        };
    }

    static double length(String variant) { return dims(variant)[0]; }

    /** How high the middle of the body sits above the road for a given spin (so a tumbling or overturned car rests on the ground). */
    private static double restHeight(double[] d, double spin) {
        double a = d[0] * 0.5, b = d[1] * 0.5;
        return Math.abs(Math.sin(spin)) * a + Math.abs(Math.cos(spin)) * b;
    }

    private static Path2D.Double poly(double... pts) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(pts[0], pts[1]);
        for (int k = 2; k + 1 < pts.length; k += 2) p.lineTo(pts[k], pts[k + 1]);
        p.closePath();
        return p;
    }

    /**
     * One car at its interpolated place.
     * @param lightsOnly draw only the lamps and beams (the pass after the under-cloud dimming, so lights glow in the dark)
     */
    static void paint(Graphics2D g, Model.Car c, double x, double y, double angle, double lift, double spin, Lit lit, double anim, double zoom, boolean lightsOnly) {
        paint(g, c, x, y, angle, lift, spin, lit, anim, zoom, lightsOnly, 0);
    }

    /** @param wind the wind where the car is, m/s (positive blows to the right): the water on the car leans with it and with the car's own speed */
    static void paint(Graphics2D g, Model.Car c, double x, double y, double angle, double lift, double spin, Lit lit, double anim, double zoom, boolean lightsOnly, double wind) {
        double[] d = dims(c.variant);
        double L = d[0], H = d[1], wr = d[2], hl = L * 0.5;
        boolean wrecked = c.state.equals("wrecked"), tossed = c.state.equals("tossed");
        boolean simple = zoom < 0.42;
        AffineTransform old = g.getTransform();

        // ---- shadow on the road (stays on the ground while the car is in the air)
        if (!lightsOnly) {
            double sh = 1 / (1 + lift / 60.0);
            g.setColor(new Color(0, 0, 0, (int) (70 * sh * (0.4 + 0.6 * lit.light))));
            g.fill(new Ellipse2D.Double(x - hl * (0.9 + 0.2 * (1 - sh)), y - 1.2, L * (0.9 + 0.2 * (1 - sh)), 3.4));
        }

        // ---- body frame: origin at the middle of the body, +x = forward, y up negative
        double rest = restHeight(d, spin);
        g.translate(x, y);
        g.rotate(angle);
        g.translate(0, -lift - rest - (c.dir < 0 && !tossed && !wrecked ? 0.8 : 0));
        if (spin != 0) g.rotate(spin);
        if (c.dir < 0) g.scale(-1, 1);
        g.translate(0, H * 0.5);          // now y = 0 is the underside of the wheels

        boolean lamps = c.lights && !wrecked;
        if (lightsOnly) {
            if (lamps) lamps(g, c, L, H, simple, true);
            g.setTransform(old);
            return;
        }

        // ---- colours
        Color paint = new Color(c.color[0], c.color[1], c.color[2]);
        if (wrecked) paint = Lit.mix(paint, new Color(70, 62, 56), 0.28);
        Color body = lit.lit(Lit.mix(paint, Lit.mul(paint, 0.70), c.wet * 0.6));
        Color bodyHi = lit.lit(Lit.mix(Lit.mix(paint, Color.WHITE, 0.35), paint, c.wet * 0.3));
        Color bodyLo = Lit.mul(body, 0.72);
        boolean dark = lit.night > 0.55;
        Color glass = wrecked ? lit.lit(new Color(36, 40, 46)) : dark ? Lit.mix(lit.lit(new Color(70, 92, 120)), new Color(255, 226, 160), lamps ? 0.16 : 0) : Lit.mix(lit.lit(new Color(118, 150, 184)), lit.skyBot, 0.35);
        Color trim = lit.lit(new Color(36, 38, 44)), tyre = lit.lit(new Color(26, 26, 30)), hub = lit.lit(new Color(168, 172, 180));
        double base = -wr * 0.9;          // underside of the bodywork

        // ---- wheels (behind the body's lower edge)
        double[] wx = {d[3] * hl, d[4] * hl};
        int missing = wrecked ? (int) (c.id % 3) : 2;        // a wreck may have lost a wheel
        for (int k = 0; k < 2; k++) {
            if (k == missing) continue;
            g.setColor(tyre);
            g.fill(new Ellipse2D.Double(wx[k] - wr, -2 * wr, 2 * wr, 2 * wr));
            if (!simple) {
                g.setColor(hub);
                g.fill(new Ellipse2D.Double(wx[k] - wr * 0.48, -wr * 1.48, wr * 0.96, wr * 0.96));
                if (c.speed > 2 && !wrecked) {
                    double a = anim * c.speed / wr * 0.5;
                    g.setColor(trim);
                    g.setStroke(new BasicStroke(0.7f));
                    g.draw(new Line2D.Double(wx[k] - Math.cos(a) * wr * 0.45, -wr - Math.sin(a) * wr * 0.45, wx[k] + Math.cos(a) * wr * 0.45, -wr + Math.sin(a) * wr * 0.45));
                }
            }
        }

        // ---- bodywork per variant
        double top = -H;
        Path2D.Double shell;
        double[] win;                       // windscreen bottom-front x, top-front x (for the wiper)
        // the panes for the rain beads: bottom-left, top-left, top-right, bottom-right corner of each; the windscreen is the last
        double[][] gl;
        switch (c.variant) {
            case "hatchback" -> {
                shell = poly(-hl, base, -hl, top + H * 0.42, -hl + 2.2, top + 0.6, hl * 0.30, top, hl * 0.66, top + H * 0.40, hl - 0.6, top + H * 0.50, hl, base);
                fillBody(g, shell, body, bodyHi, bodyLo, top, base);
                gl = new double[][]{{-hl + 2.6, top + H * 0.40, -hl + 3.8, top + 1.6, -1.0, top + 1.6, -1.0, top + H * 0.40},
                        {0.4, top + H * 0.40, 0.4, top + 1.6, hl * 0.27, top + 1.6, hl * 0.60, top + H * 0.40}};
                if (!simple) {
                    g.setColor(glass);
                    g.fill(poly(gl[0]));
                    g.fill(poly(gl[1]));
                }
                win = new double[]{hl * 0.60, hl * 0.28};
            }
            case "pickup" -> {
                // open bed behind a short cab
                shell = poly(-hl, base, -hl, top + H * 0.48, -hl * 0.10, top + H * 0.48, -hl * 0.06, top, hl * 0.42, top, hl * 0.68, top + H * 0.42, hl - 0.6, top + H * 0.50, hl, base);
                fillBody(g, shell, body, bodyHi, bodyLo, top, base);
                gl = new double[][]{{hl * 0.00, top + H * 0.42, hl * 0.02, top + 1.6, hl * 0.40, top + 1.6, hl * 0.62, top + H * 0.42}};
                if (!simple) {
                    g.setColor(glass);
                    g.fill(poly(gl[0]));
                    g.setColor(Lit.alpha(trim, 150));
                    g.fill(new Rectangle2D.Double(-hl + 1.2, top + H * 0.50, hl * 0.86, 1.1));      // the bed's rim
                }
                win = new double[]{hl * 0.62, hl * 0.41};
            }
            case "van" -> {
                shell = poly(-hl, base, -hl, top + 1.0, -hl + 1.2, top, hl * 0.52, top, hl * 0.90, top + H * 0.46, hl, top + H * 0.56, hl, base);
                fillBody(g, shell, body, bodyHi, bodyLo, top, base);
                gl = new double[][]{{-hl * 0.30, top + 2.0 + H * 0.26, -hl * 0.30, top + 2.0, hl * 0.20, top + 2.0, hl * 0.20, top + 2.0 + H * 0.26},
                        {hl * 0.30, top + H * 0.46, hl * 0.30, top + 1.8, hl * 0.50, top + 1.8, hl * 0.84, top + H * 0.46}};
                if (!simple) {
                    g.setColor(glass);
                    g.fill(poly(gl[1]));
                    g.fill(new Rectangle2D.Double(-hl * 0.30, top + 2.0, hl * 0.50, H * 0.26));
                    g.setColor(Lit.alpha(trim, 90));
                    g.setStroke(new BasicStroke(0.6f));
                    g.draw(new Line2D.Double(hl * 0.24, top + 1.5, hl * 0.24, base - 0.5));
                }
                win = new double[]{hl * 0.84, hl * 0.51};
            }
            case "truck" -> {
                // a cargo box behind a tall cab
                Color box = lit.lit(Lit.mix(new Color(214, 216, 220), new Color(150, 152, 158), c.wet * 0.5 + (wrecked ? 0.4 : 0)));
                Path2D.Double cargo = poly(-hl, base + 1.2, -hl, top, hl * 0.36, top, hl * 0.36, base + 1.2);
                g.setPaint(new GradientPaint(0f, (float) top, Lit.mix(box, Color.WHITE, 0.10), 0f, (float) base, Lit.mul(box, 0.80)));
                g.fill(cargo);
                if (!simple) {
                    g.setColor(Lit.alpha(trim, 60));
                    g.setStroke(new BasicStroke(0.6f));
                    for (int k = 1; k < 5; k++) g.draw(new Line2D.Double(-hl + k * (hl * 1.36) / 5, top + 1, -hl + k * (hl * 1.36) / 5, base));
                    g.setColor(Lit.alpha(body, 220));
                    g.fill(new Rectangle2D.Double(-hl + 1.5, top + H * 0.34, hl * 1.30, 2.0));           // a stripe in the cab's colour
                }
                g.setColor(trim);
                g.fill(new Rectangle2D.Double(-hl, base, L - 1, 1.6));                                  // chassis rail
                shell = poly(hl * 0.40, base, hl * 0.40, top + H * 0.24, hl * 0.74, top + H * 0.24, hl * 0.94, top + H * 0.56, hl, top + H * 0.62, hl, base);
                fillBody(g, shell, body, bodyHi, bodyLo, top + H * 0.24, base);
                gl = new double[][]{{hl * 0.50, top + H * 0.54, hl * 0.50, top + H * 0.30, hl * 0.72, top + H * 0.30, hl * 0.90, top + H * 0.54}};
                if (!simple) {
                    g.setColor(glass);
                    g.fill(poly(gl[0]));
                }
                win = new double[]{hl * 0.90, hl * 0.73};
            }
            case "bus" -> {
                shell = poly(-hl, base, -hl, top + 1.4, -hl + 1.6, top, hl - 3.0, top, hl, top + H * 0.34, hl, base);
                fillBody(g, shell, body, bodyHi, bodyLo, top, base);
                int nw = 6;
                double ww = (L - 14) / nw;
                gl = new double[nw + 1][];
                for (int k = 0; k < nw; k++) {
                    double bx0 = -hl + 3 + k * ww, bx1 = bx0 + ww - 1.4;
                    gl[k] = new double[]{bx0, top + 2.4 + H * 0.30, bx0, top + 2.4, bx1, top + 2.4, bx1, top + 2.4 + H * 0.30};
                }
                // (of the windscreen, the part behind its raked front edge)
                gl[nw] = new double[]{hl - 7.2, top + H * 0.58, hl - 7.2, top + 2.4, hl - 3.2, top + 2.4, hl - 0.8, top + H * 0.58};
                if (!simple) {
                    g.setColor(glass);
                    for (int k = 0; k < nw; k++) g.fill(new Rectangle2D.Double(-hl + 3 + k * ww, top + 2.4, ww - 1.4, H * 0.30));
                    g.fill(poly(hl - 7.2, top + 2.4, hl - 3.2, top + 2.4, hl - 0.8, top + H * 0.36, hl - 0.8, top + H * 0.58, hl - 7.2, top + H * 0.58));
                    g.setColor(Lit.alpha(Color.WHITE, 150));
                    g.fill(new Rectangle2D.Double(-hl + 1, top + H * 0.56, L - 2, 1.2));               // a pale stripe under the windows
                    g.setColor(Lit.alpha(trim, 130));
                    g.setStroke(new BasicStroke(0.6f));
                    g.draw(new Rectangle2D.Double(hl * 0.42, top + H * 0.40, 4.2, (base - 0.6) - (top + H * 0.40)));        // the door
                } else {
                    g.setColor(glass);
                    g.fill(new Rectangle2D.Double(-hl + 3, top + 2.4, L - 7, H * 0.28));
                }
                win = new double[]{hl - 0.8, hl - 3.2};
            }
            default -> {   // sedan
                shell = poly(-hl, base, -hl, top + H * 0.52, -hl + 1.4, top + H * 0.42, -hl * 0.52, top + H * 0.40, -hl * 0.30, top, hl * 0.22, top, hl * 0.52, top + H * 0.40,
                        hl - 1.0, top + H * 0.50, hl, top + H * 0.66, hl, base);
                fillBody(g, shell, body, bodyHi, bodyLo, top, base);
                gl = new double[][]{{-hl * 0.46, top + H * 0.40, -hl * 0.27, top + 1.5, -1.2, top + 1.5, -1.2, top + H * 0.40},
                        {0.2, top + H * 0.40, 0.2, top + 1.5, hl * 0.19, top + 1.5, hl * 0.46, top + H * 0.40}};
                if (!simple) {
                    g.setColor(glass);
                    g.fill(poly(gl[0]));
                    g.fill(poly(gl[1]));
                }
                win = new double[]{hl * 0.46, hl * 0.20};
            }
        }
        if (simple) {
            // far away: just a glass band under the roof
            if (!c.variant.equals("bus") && !c.variant.equals("truck")) {
                g.setColor(glass);
                g.fill(new Rectangle2D.Double(-hl * 0.40, top + 1.4, hl * 0.86, H * 0.30));
            }
        } else {
            // sill shadow and a door line
            g.setColor(Lit.alpha(trim, 70));
            g.fill(new Rectangle2D.Double(-hl + 1, base - 1.3, L - 2, 1.3));
            if (!c.variant.equals("truck") && !c.variant.equals("bus")) {
                g.setStroke(new BasicStroke(0.5f));
                g.setColor(Lit.alpha(trim, 90));
                g.draw(new Line2D.Double(-0.5, top + H * 0.42, -0.5, base - 1.4));
            }
        }
        // ---- snow on the roof, a wet sheen along the top
        // (the flat of the roof: where the snow lies, and where the rain beads sit)
        final double sx0 = c.variant.equals("truck") ? -hl : c.variant.equals("pickup") ? -hl * 0.06 : c.variant.equals("sedan") ? -hl * 0.30 : -hl + 1.5;
        final double sx1 = c.variant.equals("truck") ? hl * 0.36 : c.variant.equals("pickup") ? hl * 0.42 : c.variant.equals("sedan") ? hl * 0.22 : c.variant.equals("hatchback") ? hl * 0.30 : hl * 0.52;
        if (c.snow > 0.05 && !tossed) {
            double t = 1 + 2.2 * c.snow;
            g.setColor(Lit.alpha(lit.lit(new Color(246, 249, 255)), (int) (255 * Math.min(1, c.snow * 1.6))));
            g.fill(new RoundRectangle2D.Double(sx0, top - t + 0.4, sx1 - sx0, t + 0.4, t, t));
        } else if (c.wet > 0.25 && !simple && !wrecked) {
            g.setColor(new Color(255, 255, 255, (int) (70 * c.wet)));
            g.fill(new Rectangle2D.Double(-hl * 0.25, top + 0.3, hl * 0.5, 0.7));
        }
        // ---- rain on the car: beads on the glass and the bodywork, a few of them running down the windows
        // (not far away, not on a wreck's broken glass or a car in the air; under snow the snow wins)
        if (!simple && !wrecked && !tossed && c.wet > 0.04) {
            double amt = Model.clamp(c.wet, 0, 1) * Model.clamp(1 - c.snow * 4, 0, 1), px = Math.hypot(old.getScaleX(), old.getShearY());
            // (the air the car meets: the wind along its own forward axis, less its own speed - driving pushes the water back)
            if (amt > 0.04 && px >= DROP_MIN_PX)
                droplets(g, c, gl, hl, H, top, sx0, sx1, body, glass, dark, anim, px, amt, BuildingPainter.slant(wind * c.dir - c.speed * SPEED_WIND));
        }
        // ---- wiper: sweeps the windscreen while it rains on the car
        if (c.wipers && !simple && !wrecked && !tossed) {
            double sw = 0.5 + 0.5 * Math.sin(anim * 7.5 + c.id);
            double bx = win[0] - 0.6, by = top + H * 0.40 - 0.2, tx = win[1] + (win[0] - win[1]) * (0.15 + 0.7 * sw), ty = top + 1.7 + (H * 0.38 - 1.7) * (0.15 + 0.5 * sw);
            g.setStroke(new BasicStroke(0.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(20, 22, 26, 230));
            g.draw(new Line2D.Double(bx, by, tx, ty));
        }
        // ---- wreck: crumpled panels, soot, a broken screen
        if (wrecked) {
            g.setColor(new Color(22, 18, 16, 120));
            double h1 = Scene.hash01(c.id * 31 + 7), h2 = Scene.hash01(c.id * 131 + 3);
            g.fill(poly(hl * (0.2 + 0.5 * h1), base, hl, top + H * (0.5 + 0.3 * h2), hl, base));
            g.fill(poly(-hl, top + H * 0.5, -hl * (0.5 + 0.3 * h2), top + H * 0.42, -hl * 0.7, base, -hl, base));
            if (!simple) {
                g.setStroke(new BasicStroke(0.6f));
                g.setColor(new Color(230, 236, 244, 150));
                g.draw(new Line2D.Double(win[1], top + 1.8, win[0] - 1, top + H * 0.36));
                g.draw(new Line2D.Double(win[1] + 1.5, top + H * 0.32, win[0] - 2.5, top + 2.2));
            }
        }
        // ---- spray behind the wheels on a wet road
        if (!simple && c.wet > 0.3 && c.speed > 14 && !tossed && !wrecked) {
            for (int k = 0; k < 5; k++) {
                double ph = (anim * 2.6 + k * 0.2 + (c.id % 7) * 0.13) % 1;
                g.setColor(new Color(214, 228, 244, (int) (120 * (1 - ph) * c.wet)));
                double r = 0.8 + 1.8 * ph;
                g.fill(new Ellipse2D.Double(wx[0] - 2 - ph * 9 - r, -1.0 - ph * 4.5 - r, r * 2, r * 2));
            }
        }
        if (lamps) lamps(g, c, L, H, simple, false);
        else if (!wrecked && !simple) {
            // unlit lamp lenses
            g.setColor(lit.lit(new Color(232, 232, 220)));
            g.fill(new Rectangle2D.Double(hl - 1.3, top + H * 0.60, 1.3, 1.7));
            g.setColor(lit.lit(new Color(150, 36, 32)));
            g.fill(new Rectangle2D.Double(-hl, top + H * 0.58, 1.1, 1.7));
        }
        g.setTransform(old);
    }

    // ---- rain on the car (world px unless said otherwise)
    /** Below this many device px per world px the beads would be smaller than about a pixel: none are drawn. */
    static final double DROP_MIN_PX = 2.0;
    private static final double BEAD_R0 = 0.20, BEAD_R1 = 0.42;      // bead radius (most are near the small end)
    private static final double GLASS_DENSITY = 0.30, BODY_DENSITY = 0.22;   // beads per square world px on a soaked car
    private static final int PANE_MAX = 7, PANEL_MAX = 18;           // ... at most this many per pane / per body panel
    private static final double WIPED = 0.45;                        // share of the beads the wipers leave on the windscreen
    private static final double RUN_T0 = 3.5, RUN_T1 = 8, RUN_DUTY = 0.6;    // a running drop: seconds per cycle, part of it on the glass
    /** What a car's own speed (px/s) counts as head wind, in m/s: a car at highway speed (72 px/s) meets about 14 m/s. */
    private static final double SPEED_WIND = 0.2;
    private static final double SMEAR_PX = 1.6;                      // a bead smaller than this many device px shows no smear
    // a bead under LENS_PX device px across is one soft square on its shadow, then a rounded blob of two rectangles, from ROUND_PX on an ellipse
    private static final double LENS_PX = 3, ROUND_PX = 5.5;
    private static final Color BEAD_SHADE = new Color(6, 10, 20, 70);

    private static long step(long s) { return s * 6364136223846793005L + 1442695040888963407L; }

    private static double unit(long s) { return (s >>> 40) / (double) (1 << 24); }

    private static long mix(long n) {
        n ^= n >>> 33; n *= 0xff51afd7ed558ccdL; n ^= n >>> 33; n *= 0xc4ceb9fe1a85ec53L; n ^= n >>> 33;
        return n;
    }

    /**
     * The water on a wet car, in the body frame: beads on every pane, along the roof, the bonnet and the upper flanks,
     * and a drop running down each pane now and then. Places and sizes come from the car's id (nothing random), a bead
     * swells in as the car gets wetter, the motion follows {@code anim}. Drawn as single small rectangles while the
     * beads are under 5.5 device px (a fifth of a microsecond each; ellipses and paths cost several times that).
     * <p>In moving air the water leans: a running drop goes its own share (0..1, from the same number stream) of the way from
     * straight down to the slant of the air ({@link BuildingPainter#lean}), and the beads get a short tail up and upwind.
     * @param gl the panes (four corners each: bottom-left, top-left, top-right, bottom-right), the windscreen last
     * @param px device px per world px; {@code amt} how wet, 0..1
     * @param full the lean of a drop that goes all the way with the air, as a tangent from the vertical in the body frame
     *             (positive = towards the front of the car; {@link BuildingPainter#slant})
     */
    private static void droplets(Graphics2D g, Model.Car c, double[][] gl, double hl, double H, double top, double sx0, double sx1,
                                 Color body, Color glass, boolean dark, double anim, double px, double amt, double full) {
        // a bead is a little lens: a dark underside, a body paler than what it sits on
        Color onGlass = Lit.alpha(Lit.mix(glass, dark ? new Color(168, 184, 210) : Color.WHITE, dark ? 0.45 : 0.58), 220);
        Color onBody = Lit.alpha(Lit.mix(body, dark ? new Color(150, 162, 184) : Color.WHITE, dark ? 0.40 : 0.50), 210);
        Rectangle2D.Double rc = new Rectangle2D.Double();
        long seed = c.id * 0x9E3779B97F4A7C15L + 5;
        for (int q = 0; q < gl.length; q++) {
            // (the wipers keep most of the windscreen clear)
            beads(g, rc, onGlass, gl[q], mix(seed + q), GLASS_DENSITY, PANE_MAX, c.wipers && q == gl.length - 1 ? amt * WIPED : amt, px, full);
            // one drop per pane runs from the top edge to the bottom edge, then the pane is clear of it for a while
            long s = mix(seed + 977 + q);
            double period = RUN_T0 + (RUN_T1 - RUN_T0) * unit(s = step(s)), ph = anim / period + unit(s = step(s));
            ph -= Math.floor(ph);
            double grow = Lit.smooth((amt - 0.3) / 0.2);
            if (ph > RUN_DUTY || grow <= 0) continue;
            double t = ph / RUN_DUTY, u = 0.15 + 0.7 * unit(s = step(s)), r = 0.34 * grow * Math.sqrt(Math.min(1, Math.min(t / 0.1, (1 - t) / 0.1)));
            double v1 = 0.08 + 0.84 * t, v0 = 0.08 + 0.84 * Math.max(0, t - 0.4);
            // the lean of this drop, as a drift along the pane per unit run down it; it starts nearer the upwind edge
            double u1 = u, u0 = u;
            final double k = BuildingPainter.lean(full, unit(s = step(s)));
            if (k != 0) {
                double[] pq = gl[q];
                double wide = ((pq[6] - pq[0]) + (pq[4] - pq[2])) * 0.5, tall = ((pq[1] - pq[3]) + (pq[7] - pq[5])) * 0.5;
                if (wide > 0.2 && tall > 0.2) {
                    double drift = k * tall / wide, room = 0.8 * Math.min(1, Math.abs(drift) * 0.84 / 0.7);
                    double us = drift > 0 ? 0.15 + (u - 0.15) * (1 - room) : 0.85 - (0.85 - u) * (1 - room);
                    u1 = Math.max(0.1, Math.min(0.9, us + drift * (v1 - 0.08)));
                    u0 = Math.max(0.1, Math.min(0.9, us + drift * (v0 - 0.08)));
                }
            }
            double ax = px(gl[q], u1, v1), ay = py(gl[q], u1, v1), bx = px(gl[q], u0, v0), by = py(gl[q], u0, v0);
            g.setColor(onGlass);
            g.fill(poly(bx - r * 0.25, by, bx + r * 0.25, by, ax + r * 0.25, ay, ax - r * 0.25, ay));       // its wet trail
            if (px * r * 2 < ROUND_PX) { rc.setRect(ax - r * 0.85, ay - r, r * 1.7, r * 2.2); g.fill(rc); }
            else g.fill(new Ellipse2D.Double(ax - r, ay - r * 1.2, r * 2, r * 2.4));
        }
        // the bodywork: the flat of the roof, the bonnet (the cab roof of a truck), the boot of a sedan, the upper flanks
        if (c.snow <= 0.05) beads(g, rc, onBody, strip(sx0 + 0.6, top, sx1 - 0.6, top), mix(seed + 101), BODY_DENSITY, PANEL_MAX, amt, px, full);
        switch (c.variant) {
            case "hatchback" -> beads(g, rc, onBody, strip(hl * 0.70, top + H * 0.415, hl - 0.9, top + H * 0.50), mix(seed + 102), BODY_DENSITY, PANEL_MAX, amt, px, full);
            case "pickup" -> beads(g, rc, onBody, strip(hl * 0.72, top + H * 0.435, hl - 0.9, top + H * 0.50), mix(seed + 102), BODY_DENSITY, PANEL_MAX, amt, px, full);
            case "truck" -> beads(g, rc, onBody, strip(hl * 0.42, top + H * 0.24, hl * 0.72, top + H * 0.24), mix(seed + 102), BODY_DENSITY, PANEL_MAX, amt, px, full);
            case "van", "bus" -> { }
            default -> {
                beads(g, rc, onBody, strip(hl * 0.56, top + H * 0.41, hl - 1.2, top + H * 0.50), mix(seed + 102), BODY_DENSITY, PANEL_MAX, amt, px, full);
                beads(g, rc, onBody, strip(-hl + 1.6, top + H * 0.42, -hl * 0.54, top + H * 0.40), mix(seed + 103), BODY_DENSITY, PANEL_MAX, amt, px, full);
            }
        }
        double fy0 = top + H * 0.57, fy1 = top + H * 0.68;
        if (c.variant.equals("truck")) {
            beads(g, rc, onBody, new double[]{-hl + 1.5, fy1, -hl + 1.5, fy0, hl * 0.34, fy0, hl * 0.34, fy1}, mix(seed + 104), BODY_DENSITY, PANEL_MAX, amt, px, full);
            beads(g, rc, onBody, new double[]{hl * 0.44, fy1, hl * 0.44, top + H * 0.60, hl - 1.6, top + H * 0.60, hl - 1.6, fy1}, mix(seed + 105), BODY_DENSITY, PANEL_MAX, amt, px, full);
        } else beads(g, rc, onBody, new double[]{-hl + 1.5, fy1, -hl + 1.5, fy0, hl - 1.6, fy0, hl - 1.6, fy1}, mix(seed + 104), BODY_DENSITY, PANEL_MAX, amt, px, full);
    }

    /** A strip of bodywork just under the line from (ax, ay) to (bx, by), as the four corners {@link #beads} takes. */
    private static double[] strip(double ax, double ay, double bx, double by) {
        return new double[]{ax, ay + 1.35, ax, ay + 0.30, bx, by + 0.30, bx, by + 1.35};
    }

    /** The x of the point of a four-cornered panel at {@code u} along it (0 = rear edge .. 1 = front edge) and {@code v} down it (0 = top .. 1 = bottom). */
    private static double px(double[] q, double u, double v) {
        double tx = q[2] + (q[4] - q[2]) * u, bx = q[0] + (q[6] - q[0]) * u;
        return tx + (bx - tx) * v;
    }

    /** The y of that point. */
    private static double py(double[] q, double u, double v) {
        double ty = q[3] + (q[5] - q[3]) * u, by = q[1] + (q[7] - q[1]) * u;
        return ty + (by - ty) * v;
    }

    /**
     * The beads of one panel, each with its dark underside: the wetter, the more and the bigger.
     * @param sm the lean of the air (tangent from the vertical, positive = towards the front): a bead big enough to show it
     *           gets a small tail up and upwind of it, the smear of the drop that made it
     */
    private static void beads(Graphics2D g, Rectangle2D.Double rc, Color light, double[] q, long s, double density, int max, double amt, double px, double sm) {
        double wide = ((q[6] - q[0]) + (q[4] - q[2])) * 0.5, tall = ((q[1] - q[3]) + (q[7] - q[5])) * 0.5;
        if (wide < 0.8 || tall < 0.8) return;
        int n = Math.max(2, Math.min(max, (int) Math.round(wide * tall * density)));
        for (int k = 0; k < n; k++) {
            double th = unit(s = step(s)), ur = unit(s = step(s)), u = unit(s = step(s)), v = unit(s = step(s));
            double grow = Lit.smooth((amt - (0.06 + 0.8 * th)) / 0.15);
            if (grow <= 0) continue;
            double r = Math.min(BEAD_R0 + (BEAD_R1 - BEAD_R0) * ur * ur, tall * 0.42) * (0.55 + 0.45 * grow);
            double mu = Math.min(0.45, r / wide), mv = Math.min(0.45, r / tall);
            u = mu + (1 - 2 * mu) * u; v = mv + (1 - 2 * mv) * v;
            double cx = px(q, u, v), cy = py(q, u, v), d = r * 2 * px;
            // (only where the tail stays on the panel)
            if (sm != 0 && d >= SMEAR_PX && v * tall >= r * 1.75 && u * wide >= (0.55 + 1.1 * sm) * r && (1 - u) * wide >= (0.55 - 1.1 * sm) * r) {
                g.setColor(light);
                rc.setRect(cx - r * 0.55 - sm * r * 1.1, cy - r * 1.75, r * 1.1, r * 1.1); g.fill(rc);
            }
            g.setColor(BEAD_SHADE);
            if (d >= ROUND_PX) {
                g.fill(new Ellipse2D.Double(cx - r * 0.94, cy - r * 0.66, r * 2, r * 2));
                g.setColor(light);
                g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
                continue;
            }
            rc.setRect(cx - r * 0.85, cy - r * 0.45, r * 1.7, r * 1.7); g.fill(rc);
            g.setColor(light);
            if (d < LENS_PX) { rc.setRect(cx - r * 0.85, cy - r * 0.85, r * 1.7, r * 1.7); g.fill(rc); continue; }
            rc.setRect(cx - r, cy - r * 0.55, r * 2, r * 1.1); g.fill(rc);
            rc.setRect(cx - r * 0.58, cy - r, r * 1.16, r * 2); g.fill(rc);
        }
    }

    private static void fillBody(Graphics2D g, Path2D.Double shell, Color body, Color hi, Color lo, double top, double base) {
        g.setPaint(new GradientPaint(0f, (float) top, hi, 0f, (float) (top + (base - top) * 0.45), body));
        g.fill(shell);
        g.setPaint(new GradientPaint(0f, (float) (top + (base - top) * 0.55), new Color(lo.getRed(), lo.getGreen(), lo.getBlue(), 0), 0f, (float) base, lo));
        g.fill(shell);
    }

    /** Headlights with their beams on the road ahead, and the tail lights. */
    private static void lamps(Graphics2D g, Model.Car c, double L, double H, boolean simple, boolean glowPass) {
        double hl = L * 0.5, top = -H, ly = top + H * 0.68;
        boolean stopped = c.speed < 1.5;
        // beam: a soft wedge on the road ahead
        double reach = simple ? 46 : 78;
        int a0 = glowPass ? 84 : 62;
        g.setPaint(new GradientPaint((float) hl, 0f, new Color(255, 240, 196, a0), (float) (hl + reach), 0f, new Color(255, 240, 196, 0)));
        g.fill(poly(hl - 0.5, ly - 1.0, hl + reach, ly - 9, hl + reach, 1.5, hl - 0.5, ly + 1.6));
        g.setColor(new Color(255, 250, 226, 250));
        g.fill(new Ellipse2D.Double(hl - 1.9, ly - 1.4, 2.6, 2.8));
        g.setColor(new Color(255, 244, 200, glowPass ? 90 : 60));
        g.fill(new Ellipse2D.Double(hl - 4.6, ly - 4.2, 8.4, 8.4));
        // tail lights (brighter when braked to a stop)
        g.setColor(new Color(255, 54, 44, stopped ? 255 : 215));
        g.fill(new Rectangle2D.Double(-hl - 0.2, ly - 1.3, 1.6, 2.4));
        g.setColor(new Color(255, 60, 48, stopped ? 80 : 44));
        g.fill(new Ellipse2D.Double(-hl - 3.4, ly - 3.6, 7.0, 7.0));
    }
}
