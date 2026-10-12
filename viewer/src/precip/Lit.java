package precip;

import java.awt.Color;

/** Immutable snapshot of the scene lighting for one moment (time of day + overcast). */
final class Lit {
    final double tod, day, dusk, light, overcast, night;
    final Color skyTop, skyBot, haze;
    /** Settings > Advanced "No tint": surfaces keep their own hue at every hour (they still darken at night). */
    final boolean flat;

    Lit(double tod, double over) { this(tod, over, false); }

    Lit(double tod, double over, boolean flat) {
        this.tod = tod;
        this.flat = flat;
        double e = -Math.cos(2 * Math.PI * tod);              // sun elevation proxy -1..1
        day = smooth((e + 0.12) / 0.5);
        dusk = Math.exp(-Math.pow(e / 0.22, 2));
        overcast = Model.clamp(over, 0, 1);
        light = (0.38 + 0.62 * day) * (1 - 0.3 * overcast);
        night = 1 - day;
        Color nightTop = new Color(6, 10, 28), nightBot = new Color(22, 32, 66);
        Color dayTop = new Color(40, 104, 192), dayBot = new Color(170, 210, 240);
        Color duskTop = new Color(58, 64, 128), duskBot = new Color(250, 148, 92);
        Color top = mix(nightTop, dayTop, day);
        Color bot = mix(nightBot, dayBot, day);
        top = mix(top, duskTop, dusk * 0.45);
        bot = mix(bot, duskBot, dusk * 0.75);
        Color grey = new Color(118, 124, 134);
        top = mix(top, mul(grey, 0.35 + 0.65 * day), overcast * 0.55);
        bot = mix(bot, mul(grey, 0.4 + 0.6 * day), overcast * 0.5);
        skyTop = top;
        skyBot = bot;
        haze = mix(bot, top, 0.25);
    }

    /** A surface colour under this light. */
    Color lit(Color c) {
        Color d = mul(c, light);
        if (flat) return d;                                   // no night blue, no dusk orange
        Color r = mix(d, new Color(24, 32, 64), night * 0.25);
        return dusk > 0.05 ? mix(r, new Color(255, 170, 110), dusk * 0.12 * day) : r;
    }

    /** Lit colour seen through {@code h} (0..1) of atmospheric haze. */
    Color lit(Color c, double h) { return h <= 0 ? lit(c) : mix(lit(c), haze, h); }

    /** True when the two lightings would paint visibly different scenery. */
    boolean differs(Lit o) {
        return flat != o.flat || Math.abs(light - o.light) > 0.012 || Math.abs(day - o.day) > 0.01 || Math.abs(dusk - o.dusk) > 0.015
                || diff(skyBot, o.skyBot) > 2 || diff(skyTop, o.skyTop) > 3;
    }

    static int diff(Color a, Color b) {
        return Math.max(Math.abs(a.getRed() - b.getRed()), Math.max(Math.abs(a.getGreen() - b.getGreen()), Math.abs(a.getBlue() - b.getBlue())));
    }

    static Color mix(Color a, Color b, double t) {
        t = Model.clamp(t, 0, 1);
        return new Color(ci((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t)),
                ci((int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t)),
                ci((int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t)),
                ci((int) Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t)));
    }

    static Color mul(Color a, double k) {
        return new Color(ci((int) (a.getRed() * k)), ci((int) (a.getGreen() * k)), ci((int) (a.getBlue() * k)), a.getAlpha());
    }

    static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), ci(a)); }

    static int ci(int v) { return v < 0 ? 0 : Math.min(255, v); }

    static double smooth(double x) {
        x = Model.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }
}
