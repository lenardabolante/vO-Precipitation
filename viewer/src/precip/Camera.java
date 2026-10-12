package precip;

import java.awt.geom.AffineTransform;

/**
 * The view onto the world: centre + zoom, clamped to the world, with eased motion.
 * <p>
 * All units are logical (canvas) pixels and world pixels; {@link View} adds the device mapping.
 * The camera is owned by whoever drives the view (the panel on the EDT, or a snapshot); renderers
 * only ever see the immutable {@link View} made by {@link #view}.
 */
final class Camera {
    static final double MAX_ZOOM = 4.0;

    double worldW = 3840, worldH = 1080;
    // viewport on the canvas (logical px)
    double vx, vy, vw = 1280, vh = 720;
    // current and target state
    double cx, cy, zoom = 1, tx, ty, tz = 1;
    boolean initialised;
    // zoom anchor: keeps one world point under one screen point while the zoom eases
    private boolean anchored;
    private double ax, ay, asx, asy;

    /** Immutable per-frame view: camera + canvas layout + device mapping. */
    static final class View {
        final double canvasW, canvasH;          // logical canvas
        final double vx, vy, vw, vh;            // world viewport on the canvas (logical)
        final double cx, cy, zoom, targetZoom;  // camera
        final double dev;                       // device px per logical px
        final int devX, devY;                   // device position of the canvas origin
        final double ws;                        // device px per world px
        final int wtx, wty;                     // world -> device translation (whole device pixels)
        final double wx0, wy0, wx1, wy1;        // visible world rectangle
        final int dvx0, dvy0, dvx1, dvy1;       // viewport in device px
        final boolean settled;                  // camera at rest
        final double worldW, worldH;

        View(Camera c, double canvasW, double canvasH, double dev, int devX, int devY, boolean settled) {
            this.canvasW = canvasW; this.canvasH = canvasH;
            vx = c.vx; vy = c.vy; vw = c.vw; vh = c.vh;
            cx = c.cx; cy = c.cy; zoom = c.zoom; targetZoom = c.tz;
            this.dev = dev; this.devX = devX; this.devY = devY;
            this.settled = settled;
            worldW = c.worldW; worldH = c.worldH;
            ws = zoom * dev;
            wtx = (int) Math.floor(devX + dev * (vx + vw / 2) - ws * cx + 0.5);
            wty = (int) Math.floor(devY + dev * (vy + vh / 2) - ws * cy + 0.5);
            dvx0 = (int) Math.floor(devX + dev * vx + 0.5); dvy0 = (int) Math.floor(devY + dev * vy + 0.5);
            dvx1 = (int) Math.floor(devX + dev * (vx + vw) + 0.5); dvy1 = (int) Math.floor(devY + dev * (vy + vh) + 0.5);
            wx0 = (dvx0 - wtx) / ws; wx1 = (dvx1 - wtx) / ws;
            wy0 = (dvy0 - wty) / ws; wy1 = (dvy1 - wty) / ws;
        }

        AffineTransform worldTx() { return new AffineTransform(ws, 0, 0, ws, wtx, wty); }

        AffineTransform screenTx() { return new AffineTransform(dev, 0, 0, dev, devX, devY); }

        /** World -> logical canvas coordinates (consistent with the snapped device mapping). */
        double sx(double wx) { return (ws * wx + wtx - devX) / dev; }

        double sy(double wy) { return (ws * wy + wty - devY) / dev; }

        double wx(double sx) { return (dev * sx + devX - wtx) / ws; }

        double wy(double sy) { return (dev * sy + devY - wty) / ws; }

        boolean inViewport(double sx, double sy) { return sx >= vx && sx < vx + vw && sy >= vy && sy < vy + vh; }

        boolean seesX(double x0, double x1) { return x1 >= wx0 && x0 <= wx1; }

        boolean sees(double x0, double y0, double x1, double y1) { return x1 >= wx0 && x0 <= wx1 && y1 >= wy0 && y0 <= wy1; }
    }

    View view(double canvasW, double canvasH, double dev, int devX, int devY) {
        return new View(this, canvasW, canvasH, dev, devX, devY, !moving());
    }

    void setWorld(double w, double h) {
        if (w == worldW && h == worldH) return;
        worldW = w; worldH = h;
        initialised = false;
    }

    /** Set the viewport; the first call (per world) also picks the opening view. */
    void setViewport(double x, double y, double w, double h) {
        boolean changed = w != vw || h != vh;
        vx = x; vy = y; vw = Math.max(16, w); vh = Math.max(16, h);
        if (!initialised) {
            initialised = true;
            home();
            snap();
        } else if (changed) {
            clampTarget();
            zoom = Math.max(zoom, minZoom());
            clampCurrent();
        }
    }

    /** Smallest zoom: the whole world fits the viewport. */
    double minZoom() { return Math.min(vw / worldW, vh / worldH); }

    /** Zoom at which the world's height fills the viewport (the opening view). */
    double fillZoom() { return Math.max(minZoom(), vh / worldH); }

    /** Opening view: world height fills the viewport, centred on the middle of the world. */
    void home() {
        anchored = false;
        tz = Math.min(MAX_ZOOM, fillZoom());
        tx = worldW / 2;
        ty = worldH / 2;
        clampTarget();
    }

    /** Fit the whole world into the viewport. */
    void fit() {
        anchored = false;
        tz = minZoom();
        tx = worldW / 2;
        ty = worldH / 2;
        clampTarget();
    }

    void snap() {
        anchored = false;
        zoom = tz; cx = tx; cy = ty;
    }

    /** Jump (or glide) to a view. */
    void set(double x, double y, double z, boolean immediate) {
        anchored = false;
        tz = Model.clamp(z, minZoom(), MAX_ZOOM);
        tx = x; ty = y;
        clampTarget();
        if (immediate) snap();
    }

    /** Glide so that world point (x, y) is centred, keeping the zoom. */
    void centerOn(double x, double y) {
        anchored = false;
        tx = x; ty = y;
        clampTarget();
    }

    /** Zoom by {@code factor} keeping the world point under canvas point (sx, sy) fixed. */
    void zoomAt(double sx, double sy, double factor) {
        double nz = Model.clamp(tz * factor, minZoom(), MAX_ZOOM);
        // the world point currently under the cursor
        ax = cx + (sx - (vx + vw / 2)) / zoom;
        ay = cy + (sy - (vy + vh / 2)) / zoom;
        asx = sx; asy = sy;
        anchored = true;
        tz = nz;
        tx = ax - (sx - (vx + vw / 2)) / nz;
        ty = ay - (sy - (vy + vh / 2)) / nz;
        clampTarget();
    }

    /** Pan by a canvas-pixel delta (dragging): immediate. */
    void dragBy(double dsx, double dsy) {
        anchored = false;
        cx -= dsx / zoom; cy -= dsy / zoom;
        clampCurrent();
        tx = cx; ty = cy;
    }

    /** Pan by a fraction of the view (keys): eased. */
    void nudge(double fx, double fy) {
        anchored = false;
        tx += fx * vw / tz;
        ty += fy * vh / tz;
        clampTarget();
    }

    boolean moving() {
        return Math.abs(zoom - tz) > 1e-9 || Math.abs(cx - tx) > 1e-6 || Math.abs(cy - ty) > 1e-6;
    }

    /** Ease toward the target. Returns true while still moving. */
    boolean update(double dt) {
        if (!moving()) return false;
        double k = 1 - Math.exp(-Math.max(0, dt) * 13);
        if (Math.abs(Math.log(zoom / tz)) < 0.0015) zoom = tz;
        else zoom = Math.exp(Math.log(zoom) + (Math.log(tz) - Math.log(zoom)) * k);
        if (anchored && zoom != tz) {
            cx = ax - (asx - (vx + vw / 2)) / zoom;
            cy = ay - (asy - (vy + vh / 2)) / zoom;
            clampCurrent();
        } else {
            double eps = 0.3 / zoom;
            cx = Math.abs(cx - tx) < eps ? tx : cx + (tx - cx) * k;
            cy = Math.abs(cy - ty) < eps ? ty : cy + (ty - cy) * k;
            if (zoom == tz && cx == tx && cy == ty) anchored = false;
        }
        return moving();
    }

    private void clampTarget() {
        double[] c = clamp(tx, ty, tz);
        tx = c[0]; ty = c[1];
    }

    private void clampCurrent() {
        double[] c = clamp(cx, cy, zoom);
        cx = c[0]; cy = c[1];
    }

    private double[] clamp(double x, double y, double z) {
        double hx = vw / (2 * z), hy = vh / (2 * z);
        if (2 * hx >= worldW) x = worldW / 2; else x = Model.clamp(x, hx, worldW - hx);
        if (2 * hy >= worldH) {
            double extra = 2 * hy - worldH;      // more view than world: 72% of the spare shows extra sky
            y = hy - 0.72 * extra;
        } else y = Model.clamp(y, hy, worldH - hy);
        return new double[]{x, y};
    }

    double toWorldX(double sx) { return cx + (sx - (vx + vw / 2)) / zoom; }

    double toWorldY(double sy) { return cy + (sy - (vy + vh / 2)) / zoom; }
}
