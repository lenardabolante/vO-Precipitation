package precip;

/**
 * "Going under a cloud": how dark the world view should be because of the cloud cover above the
 * middle of the view, and the eased level actually drawn.
 * <p>
 * The target is recomputed every frame from the clouds; the level follows it with a critically
 * damped spring (no step and no kink, whatever the frame rate), settling in about a second and a
 * half in both directions. The shade itself is a plain dark veil drawn over the finished world
 * (sky / background picture, terrain, clouds) and under the HUD, so the picture underneath is the
 * same picture, only darker; rain and snow are drawn after it and therefore stand out more.
 * One instance per view ({@link Shared}).
 */
final class CloudShade {
    /** Time constant of the easing in seconds: ~90 % of the way after 1 s, ~98 % after 1.5 s. */
    static final double SMOOTH_TIME = 0.5;
    /** Opacity of the veil at level 1 and strength 1. */
    static final double MAX_ALPHA = 0.78;

    /** Eased cover 0..1. */
    double level;
    private double vel;
    private long lastClock;
    private boolean started;

    /**
     * Cloud cover 0..1 over the centre column of the view.
     * Each cloud counts by how thick it is (darkness, opacity, how condensed it is, whether it is
     * precipitating), by how far the view's centre is under it, and by how much of the view's width
     * it spans, so a thin fair cumulus dims a little, a raining nimbostratus a lot, and the whole
     * world seen from afar only mildly.
     */
    static double target(Model.Frame f, Camera.View v) {
        if (f == null || v == null) return 0;
        double cx = (v.wx0 + v.wx1) / 2, span = Math.max(1, v.wx1 - v.wx0);
        double clear = 1;
        for (Model.Cloud c : f.clouds) {
            if (c.form <= 0.02) continue;
            double x0 = c.x - c.w / 2, x1 = c.x + c.w / 2;
            for (double[] p : c.puffs) { x0 = Math.min(x0, c.x + p[0] - p[2]); x1 = Math.max(x1, c.x + p[0] + p[2]); }
            double cw = Math.max(20, x1 - x0), edge = Math.max(40, cw * 0.22);
            if (cx < x0 - edge || cx > x1 + edge) continue;
            // 1 while the centre column is under the cloud, easing to 0 a little way outside its edge
            double under = smooth((cx - (x0 - edge)) / (edge * 1.6)) * smooth(((x1 + edge) - cx) / (edge * 1.6));
            // the view has to look at the air below the cloud, not only at the sky above it
            double top = Renderer.cloudTop(c);
            double below = smooth((v.wy1 - top) / Math.max(60, c.h * 0.6));
            // thin grey sheets and fair-weather heaps hardly count; a dark, raining cloud counts fully
            double thick = Model.clamp((c.darkness - 0.06) * 1.5, 0, 1) * c.opacity * c.form * (c.fair ? 0.45 : 1);
            if (c.precipRate > 0.02) thick = Math.min(1, thick + 0.30 * Math.min(1, c.precipRate) * c.form);
            double overlap = Math.max(0, Math.min(x1, v.wx1) - Math.max(x0, v.wx0)) / span;
            double wide = smooth((overlap - 0.05) / 0.33);
            clear *= 1 - Model.clamp(under * below * thick * wide, 0, 1);
        }
        // seen from afar (most of the world in view) nobody is "under" anything: keep it mild
        double far = 1 - 0.55 * smooth((span / Math.max(1, v.worldW) - 0.55) / 0.45);
        return Model.clamp((1 - clear) * far, 0, 1);
    }

    /**
     * Move the level toward {@code target}. Live views ({@code live}) advance by the real time since
     * the previous frame, starting from 0 on the first one; a still picture shows the settled level,
     * or, with {@code stillSeconds >= 0}, the level that many seconds into a transition from 0.
     */
    void advance(double target, long clockNanos, boolean live, double stillSeconds) {
        if (!live) {
            if (stillSeconds >= 0) {
                level = 0; vel = 0;
                for (double t = 0; t < stillSeconds - 1e-9; t += 1 / 60.0) step(target, Math.min(1 / 60.0, stillSeconds - t));
            } else { level = target; vel = 0; }
            started = true;
            lastClock = clockNanos;
            return;
        }
        double dt = started ? Model.clamp((clockNanos - lastClock) / 1e9, 0, 0.1) : 0;
        started = true;
        lastClock = clockNanos;
        step(target, dt);
    }

    /** One step of a critically damped spring (stable for any dt). */
    void step(double target, double dt) {
        if (dt <= 0) return;
        double omega = 2 / SMOOTH_TIME, x = omega * dt;
        double e = 1 / (1 + x + 0.48 * x * x + 0.235 * x * x * x);
        double change = level - target, tmp = (vel + omega * change) * dt;
        vel = (vel - omega * tmp) * e;
        level = target + (change + tmp) * e;
        if (Math.abs(level - target) < 0.0008 && Math.abs(vel) < 0.002) { level = target; vel = 0; }
        level = Model.clamp(level, 0, 1);
    }

    private static double smooth(double x) {
        x = Model.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }
}
