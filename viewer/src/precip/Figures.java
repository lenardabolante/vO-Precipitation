package precip;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/**
 * Phase O (OH5): the shapes of the people. Small and plain on purpose: a figure is 8 to 14 world px tall (a house door
 * is about 16), a flat-coloured shape outdoors by day and a dark shadow behind a lit window at night. Everything is
 * drawn from a few rectangles and one ellipse for the head, from a {@link Look} that comes from a seed: the same seed
 * always gives the same person. Nothing here keeps state or reads a clock; poses come in as parameters.
 */
final class Figures {
    private Figures() {}

    /** The kinds of figure. */
    static final int ADULT = 0, CHILD = 1, SEATED = 2, DOG = 3;

    // ---- sizes, world px (PLAN "People")
    /** A bust behind a window: head and shoulders. */
    static final double BUST_H0 = 7, BUST_H1 = 9;
    static final double STAND_H0 = 12, STAND_H1 = 14, CHILD_H0 = 8, CHILD_H1 = 9, SEATED_H = 9, DOG_LEN = 6;

    private static final int[][] SHIRTS = {{196, 62, 54}, {58, 110, 170}, {236, 196, 78}, {70, 140, 96}, {228, 228, 222}, {150, 92, 160}, {226, 132, 60}, {84, 96, 112}, {206, 108, 132}, {60, 150, 160}};
    private static final int[][] LEGS = {{52, 60, 84}, {70, 66, 62}, {96, 82, 64}, {40, 44, 52}, {110, 116, 126}};
    private static final int[][] SKIN = {{236, 200, 170}, {214, 170, 134}, {178, 130, 96}, {130, 92, 68}, {246, 216, 190}};
    private static final int[][] HAIR = {{50, 38, 30}, {96, 66, 40}, {196, 160, 90}, {30, 28, 30}, {150, 150, 150}, {130, 60, 36}};
    private static final int[][] COATS = {{120, 84, 52}, {40, 38, 36}, {226, 222, 210}, {196, 156, 92}, {92, 90, 88}};

    /** What one person (or dog) looks like. */
    static final class Look {
        final Color shirt, legs, skin, hair;
        /** Height standing, world px. */
        final double h;
        final boolean child, skirt, hat;

        Look(long seed, boolean child) {
            long s = mix(seed);
            this.child = child;
            shirt = rgb(SHIRTS[(int) Long.remainderUnsigned(s, SHIRTS.length)]);
            legs = rgb(LEGS[(int) Long.remainderUnsigned(s >>> 8, LEGS.length)]);
            skin = rgb(SKIN[(int) Long.remainderUnsigned(s >>> 16, SKIN.length)]);
            hair = rgb(HAIR[(int) Long.remainderUnsigned(s >>> 24, HAIR.length)]);
            double u = ((s >>> 32) & 0xffff) / 65535.0;
            h = child ? CHILD_H0 + (CHILD_H1 - CHILD_H0) * u : STAND_H0 + (STAND_H1 - STAND_H0) * u;
            skirt = ((s >>> 48) & 3) == 0;
            hat = ((s >>> 50) & 7) == 0;
        }
    }

    static long mix(long n) {
        n ^= n >>> 33; n *= 0xff51afd7ed558ccdL; n ^= n >>> 33; n *= 0xc4ceb9fe1a85ec53L; n ^= n >>> 33;
        return n;
    }

    private static Color rgb(int[] c) { return new Color(c[0], c[1], c[2]); }

    private static void rect(Graphics2D g, double x, double y, double w, double h) { g.fill(new Rectangle2D.Double(x, y, w, h)); }

    /**
     * Head and shoulders behind a window, in one colour (the shadow): its middle at {@code cx}, cut off at {@code bottomY}
     * (the sill), {@code h} tall. {@code turn} -1..1 shifts the head a little (someone walking past looks where they go).
     */
    static void bust(Graphics2D g, double cx, double bottomY, double h, Color shadow, long seed, double turn) {
        bust(g, cx, bottomY, h, shadow, seed, turn, -1e18, 1e18);
    }

    /**
     * The same, seen only between the world x {@code xl} and {@code xr} (the gap between the curtains): every part is cut
     * to that span by arithmetic, so no clip has to be set on the graphics.
     */
    static void bust(Graphics2D g, double cx, double bottomY, double h, Color shadow, long seed, double turn, double xl, double xr) {
        final double head = h * 0.46, sw = h * 0.92, sh = h * 0.50, hx = cx - head / 2 + turn * head * 0.18;
        g.setColor(shadow);
        // shoulders: a wide block with its top corners taken off, the neck, the head
        span(g, cx - sw / 2, bottomY - sh * 0.72, sw, sh * 0.72, xl, xr);
        span(g, cx - sw * 0.36, bottomY - sh, sw * 0.72, sh * 0.3, xl, xr);
        span(g, cx - head * 0.2 + turn * head * 0.1, bottomY - sh - head * 0.16, head * 0.4, head * 0.2, xl, xr);
        if (hx >= xl && hx + head <= xr) g.fill(new Ellipse2D.Double(hx, bottomY - h, head, head * 1.08));
        else {
            // (half behind a curtain: the round of the head as three bars)
            span(g, hx + head * 0.22, bottomY - h, head * 0.56, head * 1.08, xl, xr);
            span(g, hx + head * 0.06, bottomY - h + head * 0.14, head * 0.88, head * 0.80, xl, xr);
            span(g, hx, bottomY - h + head * 0.30, head, head * 0.48, xl, xr);
        }
        if ((mix(seed) & 3) == 0) span(g, cx - head * 0.56 + turn * head * 0.18, bottomY - h + head * 0.42, head * 1.12, head * 0.66, xl, xr);   // long hair
    }

    /** A rectangle, the part of it between xl and xr. */
    private static void span(Graphics2D g, double x, double y, double w, double h, double xl, double xr) {
        final double a = Math.max(x, xl), b = Math.min(x + w, xr);
        if (b > a) g.fill(new Rectangle2D.Double(a, y, b - a, h));
    }

    /**
     * A person standing with the feet at ({@code x}, {@code feetY}). {@code lean} shifts the head and shoulders sideways
     * (world px: the idle sway); {@code wave} 0..1 raises one arm.
     */
    static void standing(Graphics2D g, double x, double feetY, Look k, Lit lit, double haze, double lean, double wave) {
        final double h = k.h, head = h * (k.child ? 0.27 : 0.21), tw = h * (k.child ? 0.30 : 0.27);
        final double top = feetY - h, shoulder = top + head * 1.05, hip = feetY - h * 0.44;
        final Color shirt = lit.lit(k.shirt, haze), legs = lit.lit(k.legs, haze), skin = lit.lit(k.skin, haze), hair = lit.lit(k.hair, haze);
        // legs (or a skirt over them)
        g.setColor(k.skirt ? skin : legs);
        rect(g, x - tw * 0.42, hip, tw * 0.34, feetY - hip);
        rect(g, x + tw * 0.08, hip, tw * 0.34, feetY - hip);
        if (k.skirt) { g.setColor(shirt); rect(g, x - tw * 0.56, hip - 0.3, tw * 1.12, (feetY - hip) * 0.5); }
        // arms, body
        g.setColor(Lit.mul(shirt, 0.86));
        rect(g, x - tw * 0.72 + lean * 0.7, shoulder + 0.3, tw * 0.24, (hip - shoulder) * 0.86);
        if (wave > 0.02) rect(g, x + tw * 0.50 + lean * 0.7, shoulder + 0.4 - (hip - shoulder) * 0.8 * wave, tw * 0.24, (hip - shoulder) * 0.86);
        else rect(g, x + tw * 0.48 + lean * 0.7, shoulder + 0.3, tw * 0.24, (hip - shoulder) * 0.86);
        g.setColor(shirt);
        rect(g, x - tw / 2 + lean * 0.6, shoulder, tw, hip - shoulder + 0.4);
        // head, hair
        g.setColor(Lit.mul(skin, 0.62));
        g.fill(new Ellipse2D.Double(x - head / 2 + lean - 0.3, top - 0.2, head + 0.6, head * 1.06 + 0.5));
        g.setColor(skin);
        g.fill(new Ellipse2D.Double(x - head / 2 + lean, top, head, head * 1.06));
        g.setColor(hair);
        rect(g, x - head / 2 + lean - 0.15, top - 0.25, head + 0.3, head * 0.42);
        if (k.hat) { g.setColor(lit.lit(new Color(206, 178, 120), haze)); rect(g, x - head * 0.8 + lean, top + head * 0.12, head * 1.6, head * 0.2); }
    }

    /** A person walking: {@code dir} +1 / -1, {@code phase} the stride in radians. */
    static void walker(Graphics2D g, double x, double feetY, Look k, Lit lit, double haze, int dir, double phase) {
        final double h = k.h, head = h * (k.child ? 0.27 : 0.21), tw = h * (k.child ? 0.28 : 0.24);
        final double sw = Math.sin(phase), bob = Math.abs(Math.cos(phase)) * h * 0.02;
        final double top = feetY - h + bob, shoulder = top + head * 1.05, hip = feetY - h * 0.44 + bob, stride = h * 0.17 * sw;
        final Color shirt = lit.lit(k.shirt, haze), legs = lit.lit(k.legs, haze), skin = lit.lit(k.skin, haze), hair = lit.lit(k.hair, haze);
        final double lw = tw * 0.36;
        // the far leg, the near leg: each a slanted bar from the hip to its foot
        g.setColor(Lit.mul(k.skirt ? skin : legs, 0.82));
        leg(g, x, hip, x - stride, feetY, lw);
        g.setColor(k.skirt ? skin : legs);
        leg(g, x, hip, x + stride, feetY, lw);
        if (k.skirt) { g.setColor(shirt); rect(g, x - tw * 0.56, hip - 0.3, tw * 1.12, (feetY - hip) * 0.5); }
        g.setColor(shirt);
        rect(g, x - tw / 2, shoulder, tw, hip - shoulder + 0.4);
        // the near arm swings against the near leg
        g.setColor(Lit.mul(shirt, 0.84));
        leg(g, x, shoulder + 0.5, x - stride * 0.8, shoulder + (hip - shoulder) * 0.95, tw * 0.3);
        g.setColor(Lit.mul(skin, 0.62));
        g.fill(new Ellipse2D.Double(x - head / 2 + dir * head * 0.08 - 0.3, top - 0.2, head + 0.6, head * 1.06 + 0.5));
        g.setColor(skin);
        g.fill(new Ellipse2D.Double(x - head / 2 + dir * head * 0.08, top, head, head * 1.06));
        g.setColor(hair);
        rect(g, x - head / 2 + dir * head * 0.08 - dir * head * 0.14 - 0.15, top - 0.25, head + 0.3, head * 0.42);
        if (k.hat) { g.setColor(lit.lit(new Color(206, 178, 120), haze)); rect(g, x - head * 0.8 + dir * head * 0.08, top + head * 0.12, head * 1.6, head * 0.2); }
    }

    private static void leg(Graphics2D g, double x0, double y0, double x1, double y1, double w) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x0 - w / 2, y0); p.lineTo(x0 + w / 2, y0); p.lineTo(x1 + w / 2, y1); p.lineTo(x1 - w / 2, y1);
        p.closePath();
        g.fill(p);
    }

    /** A person sitting, facing {@code dir}: the seat is at {@code seatY}, the feet {@code drop} px under it. */
    static void seated(Graphics2D g, double x, double seatY, double drop, Look k, Lit lit, double haze, int dir, double lean) {
        final double h = SEATED_H, head = h * 0.27, tw = h * 0.36, torso = h * 0.44;
        final Color shirt = lit.lit(k.shirt, haze), legs = lit.lit(k.legs, haze), skin = lit.lit(k.skin, haze), hair = lit.lit(k.hair, haze);
        g.setColor(legs);
        rect(g, dir > 0 ? x : x - tw * 0.95, seatY - h * 0.16, tw * 0.95, h * 0.17);                 // thighs
        rect(g, dir > 0 ? x + tw * 0.70 : x - tw * 0.95, seatY - h * 0.16, tw * 0.25, drop + h * 0.16);   // shins
        g.setColor(shirt);
        rect(g, x - tw / 2 + lean * 0.5, seatY - h * 0.12 - torso, tw, torso);
        g.setColor(Lit.mul(shirt, 0.86));
        rect(g, x - tw * 0.1 + dir * tw * 0.3 + lean * 0.5, seatY - h * 0.12 - torso * 0.8, tw * 0.26, torso * 0.7);
        g.setColor(Lit.mul(skin, 0.62));
        g.fill(new Ellipse2D.Double(x - head / 2 + lean - 0.3, seatY - h * 0.12 - torso - head * 0.98 - 0.2, head + 0.6, head * 1.06 + 0.5));
        g.setColor(skin);
        g.fill(new Ellipse2D.Double(x - head / 2 + lean, seatY - h * 0.12 - torso - head * 0.98, head, head * 1.06));
        g.setColor(hair);
        rect(g, x - head / 2 + lean - 0.15, seatY - h * 0.12 - torso - head * 1.0 - 0.15, head + 0.3, head * 0.42);
    }

    /** A dog with its paws on {@code feetY}, heading {@code dir}; {@code phase} trots the legs and wags the tail. */
    static void dog(Graphics2D g, double x, double feetY, long seed, Lit lit, double haze, int dir, double phase) {
        final double len = DOG_LEN, bh = len * 0.36, leg = len * 0.34, sw = Math.sin(phase) * len * 0.09;
        final Color coat = lit.lit(rgb(COATS[(int) Long.remainderUnsigned(mix(seed), COATS.length)]), haze);
        g.setColor(Lit.mul(coat, 0.82));
        rect(g, x - len * 0.42 - sw, feetY - leg, len * 0.14, leg);
        rect(g, x + len * 0.28 + sw, feetY - leg, len * 0.14, leg);
        g.setColor(coat);
        rect(g, x - len * 0.30 + sw, feetY - leg, len * 0.14, leg);
        rect(g, x + len * 0.40 - sw, feetY - leg, len * 0.14, leg);
        rect(g, x - len / 2, feetY - leg - bh, len, bh);
        // head and ear at the front, the tail up at the back
        rect(g, x + dir * len * 0.5 - (dir > 0 ? len * 0.05 : len * 0.33), feetY - leg - bh - len * 0.20, len * 0.38, len * 0.34);
        rect(g, x - dir * len * 0.44 - len * 0.07, feetY - leg - bh - len * 0.26 - Math.abs(Math.cos(phase * 2)) * 0.3, len * 0.14, len * 0.42);
        g.setColor(Lit.mul(coat, 0.7));
        rect(g, x + dir * len * 0.5 + (dir > 0 ? len * 0.02 : -len * 0.16), feetY - leg - bh - len * 0.30, len * 0.14, len * 0.16);
    }
}
