package precip;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Draws the World Simulation map: an equirectangular Earth (north up) with one data layer, the
 * night side, pressure systems, cyclones and the location dots.
 * <p>
 * <b>How a frame is made.</b> The coloured map itself is composed pixel by pixel, in parallel rows,
 * straight into the panel's software buffer: a cached <i>mask</i> of the visible part of the world
 * (land coverage, dark lines = coasts and borders, light lines = graticule; drawn once per view by
 * Java2D from the Natural Earth paths of {@link WorldMap}) gives the base colour, the layer's field
 * (the 72 x 36 grid, first refined three times with a Catmull-Rom filter, then bilinear per pixel,
 * gliding between two grids in time) is blended over it through a colour table, the night side is
 * darkened from the subsolar point, and the lines go on top. While the view is panned or zoomed the
 * mask is simply resampled; it is redrawn only when the view leaves it or has settled at a new
 * scale. Everything else (isobars, wind streaks, glyphs, dots, labels) is ordinary vector drawing
 * on top.
 */
final class WorldPainter {
    static final int TEMP = 0, CLOUD = 1, PRECIP = 2, WIND = 3, PRESSURE = 4, SNOW = 5, PLAIN = 6;
    static final String[] LAYERS = {"Temperature", "Clouds", "Precipitation", "Wind", "Pressure", "Snow", "Map"};
    static final String[] LAYER_IDS = {"temp", "cloud", "precip", "wind", "pressure", "snow", "map"};
    static final double MAX_SCALE = 56;
    private static final int BG = 0xFF070B14;
    private static final int UP = 3, LUT_N = 512;

    // ------------------------------------------------------------------ colour tables

    /** Value range of each scalar layer and its colour table (straight-alpha ARGB). */
    static final double[] LO = {-45, 0, 0, 0, 968, 0}, HI = {45, 1, 1, 32, 1048, 1};
    private static final int[][] LUT = new int[6][];
    private static final int[] VEIL = new int[LUT_N], SNOW_SOFT = new int[LUT_N];

    private static int[] ramp(double[][] stops) {
        // stops: {position 0..1, r, g, b, alpha}
        int[] lut = new int[LUT_N];
        for (int i = 0; i < LUT_N; i++) {
            double x = i / (double) (LUT_N - 1);
            int k = 0;
            while (k < stops.length - 2 && x > stops[k + 1][0]) k++;
            double[] a = stops[k], b = stops[k + 1];
            double f = Model.clamp((x - a[0]) / Math.max(1e-9, b[0] - a[0]), 0, 1);
            int r = (int) Math.round(a[1] + (b[1] - a[1]) * f), g = (int) Math.round(a[2] + (b[2] - a[2]) * f), bl = (int) Math.round(a[3] + (b[3] - a[3]) * f);
            int al = (int) Math.round(a[4] + (b[4] - a[4]) * f);
            lut[i] = (al << 24) | (r << 16) | (g << 8) | bl;
        }
        return lut;
    }

    private static double tpos(double c) { return (c + 45) / 90.0; }

    static {
        // temperature: cold violet / blue, pale around +10 °C, warm yellow to dark red; lightness falls off to both ends
        LUT[TEMP] = ramp(new double[][]{
                {tpos(-45), 52, 22, 96, 202}, {tpos(-35), 74, 50, 158, 202}, {tpos(-25), 62, 92, 200, 202}, {tpos(-15), 66, 142, 222, 202},
                {tpos(-6), 104, 190, 230, 202}, {tpos(1), 160, 220, 226, 202}, {tpos(8), 206, 234, 198, 202}, {tpos(14), 244, 238, 164, 202},
                {tpos(20), 250, 208, 112, 202}, {tpos(26), 244, 158, 78, 202}, {tpos(32), 226, 100, 62, 202}, {tpos(38), 192, 52, 56, 202}, {tpos(45), 122, 20, 50, 202}});
        LUT[CLOUD] = ramp(new double[][]{{0, 236, 240, 246, 0}, {0.10, 236, 240, 246, 0}, {0.45, 240, 243, 248, 150}, {0.80, 248, 250, 253, 232}, {1, 255, 255, 255, 246}});
        LUT[PRECIP] = ramp(new double[][]{{0, 120, 200, 150, 0}, {0.04, 120, 200, 150, 0}, {0.14, 96, 190, 130, 150}, {0.30, 52, 160, 84, 205}, {0.46, 236, 224, 84, 220},
                {0.64, 244, 150, 52, 228}, {0.82, 226, 62, 52, 236}, {1, 198, 62, 196, 242}});
        LUT[WIND] = ramp(new double[][]{{0, 36, 52, 100, 120}, {0.16, 54, 118, 184, 178}, {0.32, 70, 182, 176, 196}, {0.48, 158, 212, 112, 204}, {0.64, 244, 212, 92, 210},
                {0.82, 240, 132, 70, 216}, {1, 214, 58, 92, 222}});
        LUT[PRESSURE] = ramp(new double[][]{{0, 84, 38, 138, 200}, {0.21, 66, 88, 190, 200}, {0.40, 104, 166, 218, 200}, {0.525, 196, 220, 230, 200}, {0.60, 236, 230, 212, 200},
                {0.70, 242, 196, 128, 200}, {0.825, 226, 130, 84, 200}, {1, 178, 68, 60, 200}});
        LUT[SNOW] = ramp(new double[][]{{0, 240, 246, 252, 0}, {0.06, 240, 246, 252, 0}, {0.5, 244, 248, 253, 190}, {1, 255, 255, 255, 244}});
        int[] veil = ramp(new double[][]{{0, 176, 184, 198, 0}, {0.15, 176, 184, 198, 0}, {1, 196, 202, 214, 150}});
        int[] soft = ramp(new double[][]{{0, 240, 246, 252, 0}, {0.08, 240, 246, 252, 0}, {1, 250, 252, 255, 205}});
        System.arraycopy(veil, 0, VEIL, 0, LUT_N);
        System.arraycopy(soft, 0, SNOW_SOFT, 0, LUT_N);
    }

    /** The opaque colour of a layer's table at a value (legend, dots). */
    static Color color(int layer, double value) {
        int c = LUT[layer][lutIndex(layer, value)];
        return new Color((c >> 16) & 255, (c >> 8) & 255, c & 255);
    }

    static int lutIndex(int layer, double value) {
        return (int) Math.round(Model.clamp((value - LO[layer]) / (HI[layer] - LO[layer]), 0, 1) * (LUT_N - 1));
    }

    static int lutArgb(int layer, int idx) { return LUT[layer][idx]; }

    // ------------------------------------------------------------------ the view

    final WorldMap map;
    /** Where the map is on the canvas (logical px). */
    final Rectangle2D.Double rect = new Rectangle2D.Double(0, 0, 100, 50);
    /** Centre (degrees) and scale (logical px per degree): as drawn, and where they are heading. */
    double clon, clat, scale = 1, tlon, tlat, tscale = 1;
    private boolean viewInit;
    int layer = TEMP;
    boolean showGraticule = true, showBorders = true, showNight = true, showSystems = true, showDots = true;
    /** Still pictures: nothing eases or animates. */
    boolean still;
    private double anim;

    WorldPainter(WorldMap map) { this.map = map; }

    double fitScale() { return Math.max(0.2, Math.min(rect.width / 360.0, rect.height / 180.0)); }

    /** The whole world fits the width: longitude neither scrolls nor wraps. */
    boolean lonFixed() { return 360 * scale <= rect.width + 0.5; }

    void setRect(double x, double y, double w, double h) {
        boolean first = !viewInit;
        double oldFit = fitScale();
        rect.setRect(x, y, Math.max(40, w), Math.max(20, h));
        if (first) { viewInit = true; home(); snap(); return; }
        // a resized window keeps the zoom relative to "whole world"
        double nf = fitScale();
        if (Math.abs(nf - oldFit) > 1e-9) { tscale = tscale / oldFit * nf; scale = scale / oldFit * nf; }
        clampTarget();
    }

    void home() { tscale = fitScale(); tlon = 0; tlat = 0; clampTarget(); }

    void snap() { clampTarget(); scale = tscale; clon = tlon; clat = tlat; }

    boolean settled() { return Math.abs(scale - tscale) < tscale * 1e-4 && Math.abs(clon - tlon) * scale < 0.3 && Math.abs(clat - tlat) * scale < 0.3; }

    private void clampTarget() {
        tscale = Model.clamp(tscale, fitScale(), Math.max(fitScale(), MAX_SCALE));
        if (360 * tscale <= rect.width + 0.5) tlon = 0;
        double half = rect.height / (2 * tscale);
        tlat = half >= 90 ? 0 : Model.clamp(tlat, -90 + half, 90 - half);
    }

    void update(double dt) {
        clampTarget();
        if (still || dt < 0) { snap(); return; }
        anim += dt;
        double k = 1 - Math.exp(-dt * 13);
        // zoom in log space so that it feels even
        scale = Math.exp(Math.log(scale) + (Math.log(tscale) - Math.log(scale)) * k);
        clon += (tlon - clon) * k;
        clat += (tlat - clat) * k;
        if (settledClose()) { scale = tscale; clon = tlon; clat = tlat; }
        // keep the unwrapped longitude near the globe: move both by whole turns
        if (clon > 180 || clon < -180) { double turn = 360 * Math.floor((clon + 180) / 360); clon -= turn; tlon -= turn; }
        if (lonFixed() && 360 * tscale <= rect.width + 0.5) clon = 0;
        double half = rect.height / (2 * scale);
        clat = half >= 90 ? 0 : Model.clamp(clat, -90 + half, 90 - half);
    }

    private boolean settledClose() { return Math.abs(scale - tscale) < tscale * 2e-4 && Math.abs(clon - tlon) * scale < 0.05 && Math.abs(clat - tlat) * scale < 0.05; }

    double x(double lon) {
        double d = lon - clon;
        if (!lonFixed()) d = WorldMap.wrapLon(d);
        return rect.getCenterX() + d * scale;
    }

    double y(double lat) { return rect.getCenterY() - (lat - clat) * scale; }

    double lonAt(double px) { return clon + (px - rect.getCenterX()) / scale; }

    double latAt(double py) { return clat - (py - rect.getCenterY()) / scale; }

    /** Is this canvas point on the map (inside the frame and on the globe)? */
    boolean onMap(double px, double py) {
        if (!rect.contains(px, py)) return false;
        double lat = latAt(py);
        if (lat > 90 || lat < -90) return false;
        return !lonFixed() || Math.abs(lonAt(px) - clon) <= 180;
    }

    /** Zoom by {@code factor} keeping the place under the pointer where it is. */
    void zoomAt(double px, double py, double factor) {
        double ns = Model.clamp(tscale * factor, fitScale(), Math.max(fitScale(), MAX_SCALE));
        double lon = tlon + (px - rect.getCenterX()) / tscale, lat = tlat - (py - rect.getCenterY()) / tscale;
        tscale = ns;
        tlon = lon - (px - rect.getCenterX()) / ns;
        tlat = lat + (py - rect.getCenterY()) / ns;
        clampTarget();
    }

    /** Drag the map by this many pixels (follows the pointer exactly). */
    void dragBy(double dx, double dy) {
        tlon -= dx / scale; tlat += dy / scale;
        clampTarget();
        clon = tlon; clat = tlat;
    }

    void nudge(double fx, double fy) {
        tlon += fx * rect.width / tscale; tlat -= fy * rect.height / tscale;
        clampTarget();
    }

    /** Bring a place to the middle, zooming in to at least {@code minZoom} times "whole world". */
    void centerOn(double lon, double lat, double minZoom) {
        tscale = Math.max(tscale, fitScale() * minZoom);
        clampTarget();
        tlon = clon + WorldMap.wrapLon(lon - clon);
        tlat = lat;
        clampTarget();
    }

    double zoomFactor() { return scale / fitScale(); }

    // ------------------------------------------------------------------ the refined fields

    private int fc, fr;
    private float[][] fine = new float[7][], finePrev = new float[7][], fineNow = new float[7][];
    private final long[] fineVer = new long[7], finePrevVer = new long[7];
    private float[] speedCur, speedPrev;
    private long speedVer = -1;

    /** Catmull-Rom refinement of a coarse grid (wraps in longitude, clamps at the poles). */
    private static float[] refine(float[] src, int cols, int rows, float[] out, boolean unit) {
        int oc = cols * UP, or = rows * UP;
        if (out == null || out.length != oc * or) out = new float[oc * or];
        float[] wx = new float[UP * 4];
        for (int k = 0; k < UP; k++) {
            float t = (k + 0.5f) / UP - 0.5f;          // position of the fine sample relative to the coarse centre
            float f = t < 0 ? t + 1 : t;
            wx[k * 4] = ((-f + 2) * f - 1) * f * 0.5f;
            wx[k * 4 + 1] = ((3 * f - 5) * f * f + 2) * 0.5f;
            wx[k * 4 + 2] = ((-3 * f + 4) * f + 1) * f * 0.5f;
            wx[k * 4 + 3] = (f - 1) * f * f * 0.5f;
        }
        float[] tmp = new float[oc * rows];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) for (int k = 0; k < UP; k++) {
                float t = (k + 0.5f) / UP - 0.5f;
                int base = t < 0 ? c - 1 : c;
                float s = 0;
                for (int q = 0; q < 4; q++) s += wx[k * 4 + q] * src[r * cols + Math.floorMod(base - 1 + q, cols)];
                tmp[r * oc + c * UP + k] = s;
            }
        }
        for (int r = 0; r < rows; r++) for (int k = 0; k < UP; k++) {
            float t = (k + 0.5f) / UP - 0.5f;
            int base = t < 0 ? r - 1 : r, orow = (r * UP + k) * oc;
            int r0 = Math.max(0, Math.min(rows - 1, base - 1)) * oc, r1 = Math.max(0, Math.min(rows - 1, base)) * oc,
                    r2 = Math.max(0, Math.min(rows - 1, base + 1)) * oc, r3 = Math.max(0, Math.min(rows - 1, base + 2)) * oc;
            float w0 = wx[k * 4], w1 = wx[k * 4 + 1], w2 = wx[k * 4 + 2], w3 = wx[k * 4 + 3];
            for (int x = 0; x < oc; x++) {
                float s = w0 * tmp[r0 + x] + w1 * tmp[r1 + x] + w2 * tmp[r2 + x] + w3 * tmp[r3 + x];
                out[orow + x] = unit ? (s < 0 ? 0 : s > 1 ? 1 : s) : s;
            }
        }
        return out;
    }

    private static float[] coarse(WorldModel.Frame f, int field) {
        return switch (field) { case 0 -> f.temp; case 1 -> f.cloud; case 2 -> f.precip; case 4 -> f.pressure; case 5 -> f.snow; default -> null; };
    }

    private static float[] speed(WorldModel.Frame f) {
        float[] s = new float[f.u.length];
        for (int k = 0; k < s.length; k++) s[k] = (float) Math.hypot(f.u[k], f.v[k]);
        return s;
    }

    /** The refined field of the frame, blended in time with the grid before it; null when there is no grid. */
    private float[] field(WorldModel.Frame f, int field, double blend) {
        if (!f.hasGrid()) return null;
        fc = f.cols * UP; fr = f.rows * UP;
        boolean unit = field == CLOUD || field == PRECIP || field == SNOW;
        if (fine[field] == null || fineVer[field] != f.gridVersion) {
            if (fine[field] != null && f.prevGrid != null && fineVer[field] == f.prevGrid.gridVersion) {
                float[] sw = finePrev[field]; finePrev[field] = fine[field]; fine[field] = sw;      // what was current is now the grid before
                finePrevVer[field] = fineVer[field];
            } else finePrevVer[field] = -1;
            float[] src = field == WIND ? speed(f) : coarse(f, field);
            fine[field] = refine(src, f.cols, f.rows, fine[field], unit);
            fineVer[field] = f.gridVersion;
            if (finePrevVer[field] < 0 && f.prevGrid != null && f.prevGrid.hasGrid() && f.prevGrid.cols == f.cols) {
                float[] ps = field == WIND ? speed(f.prevGrid) : coarse(f.prevGrid, field);
                finePrev[field] = refine(ps, f.cols, f.rows, finePrev[field], unit);
                finePrevVer[field] = f.prevGrid.gridVersion;
            }
        }
        if (blend >= 0.999 || finePrevVer[field] < 0 || finePrev[field] == null || finePrev[field].length != fine[field].length) return fine[field];
        float[] a = finePrev[field], b = fine[field], o = fineNow[field];
        if (o == null || o.length != b.length) o = fineNow[field] = new float[b.length];
        float w = (float) blend;
        for (int k = 0; k < o.length; k++) o[k] = a[k] + (b[k] - a[k]) * w;
        return o;
    }

    /** How far the picture has glided from the grid before to the latest one (0..1). */
    private double blendOf(WorldModel.Frame f) {
        if (still || f.prevGrid == null || f.paused) return 1;
        return Model.clamp((System.nanoTime() - f.gridNanos) / 1e9 / Math.max(0.05, f.gridInterval), 0, 1);
    }

    /** A coarse-grid value at a place, bilinear (wind for the streaks, readouts). */
    static double sample(float[] a, int cols, int rows, double lon, double lat) {
        if (a == null || a.length < cols * rows) return 0;
        double gx = (WorldMap.wrapLon(lon) + 180) / (360.0 / cols) - 0.5, gy = (90 - lat) / (180.0 / rows) - 0.5;
        int c0 = (int) Math.floor(gx), r0 = (int) Math.floor(gy);
        double fx = gx - c0, fy = Model.clamp(gy - r0, 0, 1);
        int ca = Math.floorMod(c0, cols), cb = Math.floorMod(c0 + 1, cols), ra = Math.max(0, Math.min(rows - 1, r0)), rb = Math.max(0, Math.min(rows - 1, r0 + 1));
        if (r0 < 0) fy = 0;
        double top = a[ra * cols + ca] + (a[ra * cols + cb] - a[ra * cols + ca]) * fx, bot = a[rb * cols + ca] + (a[rb * cols + cb] - a[rb * cols + ca]) * fx;
        return top + (bot - top) * fy;
    }

    // ------------------------------------------------------------------ the mask of the visible world

    private int[] mask = new int[0];
    private int mw, mh;
    private double mLon0, mTop, mScale, mSpan;
    private boolean mFull, mBorders, mGrat;
    private BufferedImage landImg, lineImg;
    /** Diagnostics: how often the mask was redrawn. */
    long maskBuilds;

    private boolean maskValid(double dev) {
        if (mask.length == 0 || mBorders != showBorders || mGrat != showGraticule) return false;
        double want = scale * dev, ratio = want / mScale;
        if (ratio < 0.6 || ratio > 1.7) return false;
        if (settled() && Math.abs(ratio - 1) > 0.004) return false;
        double halfLat = rect.height / (2 * scale), top = Math.min(90, clat + halfLat), bottom = Math.max(-90, clat - halfLat);
        if (top > mTop + 1e-6 || bottom < mTop - (mh - 1) / mScale - 1e-6) return false;
        if (mFull) return true;
        double halfLon = rect.width / (2 * scale), d = clon - halfLon - mLon0;
        d -= 360 * Math.floor(d / 360);
        return d + 2 * halfLon <= mSpan;
    }

    private void buildMask(double dev) {
        maskBuilds++;
        double ms = scale * dev;
        double halfLon = rect.width / (2 * scale), halfLat = rect.height / (2 * scale), grow = settled() ? 1.25 : 1.6;
        double span = 2 * halfLon * grow;
        mFull = span >= 360 || lonFixed();
        if (mFull) { mLon0 = -180; span = 360; } else mLon0 = clon - halfLon * grow;
        mSpan = span;
        mTop = Math.min(90, clat + halfLat * grow);
        double bottom = Math.max(-90, clat - halfLat * grow);
        mScale = ms;
        mw = Math.max(2, (int) Math.ceil(span * ms));
        mh = Math.max(2, (int) Math.ceil((mTop - bottom) * ms) + 1);
        mBorders = showBorders; mGrat = showGraticule;
        if (landImg == null || landImg.getWidth() != mw || landImg.getHeight() != mh) {
            landImg = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_RGB);
            lineImg = new BufferedImage(mw, mh, BufferedImage.TYPE_INT_RGB);
            mask = new int[mw * mh];
        }
        Graphics2D gl = landImg.createGraphics(), gs = lineImg.createGraphics();
        for (Graphics2D g : new Graphics2D[]{gl, gs}) {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, mw, mh);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        }
        double zoom = scale / fitScale();
        // light lines first (blue channel): the graticule
        if (showGraticule) {
            double step = scale >= 22 ? 5 : scale >= 9 ? 10 : scale >= 4.2 ? 15 : 30;
            gs.setStroke(new BasicStroke((float) (1.0 * dev)));
            for (double lat = -90 + step; lat < 90 - 1e-6; lat += step) {
                double yy = (mTop - lat) * ms;
                if (yy < -2 || yy > mh + 2) continue;
                gs.setColor(new Color(0, 0, Math.abs(lat) < 1e-6 ? 150 : 84));
                gs.draw(new Line2D.Double(0, yy, mw, yy));
            }
            double l0 = Math.ceil(mLon0 / step) * step;
            for (double lon = l0; lon <= mLon0 + span + 1e-6; lon += step) {
                double xx = (lon - mLon0) * ms;
                gs.setColor(new Color(0, 0, Math.abs(WorldMap.wrapLon(lon)) < 1e-6 ? 130 : 84));
                gs.draw(new Line2D.Double(xx, 0, xx, mh));
            }
        }
        for (int k = -2; k <= 2; k++) {
            double off = k * 360.0;
            if (off + 180 < mLon0 - 1 || off - 180 > mLon0 + span + 1) continue;
            AffineTransform at = new AffineTransform(ms, 0, 0, ms, (off - mLon0) * ms, mTop * ms);
            gl.setColor(new Color(255, 0, 0));
            gl.fill(at.createTransformedShape(map.landShape));
            if (showBorders) {
                gs.setStroke(new BasicStroke((float) (Math.min(1.3, 0.62 + 0.10 * zoom) * dev), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                gs.setColor(new Color(0, (int) Model.clamp(70 + 22 * zoom, 70, 190), 0));
                gs.draw(at.createTransformedShape(map.stateLines));
                gs.setStroke(new BasicStroke((float) (Math.min(1.5, 0.8 + 0.08 * zoom) * dev), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                gs.setColor(new Color(0, 165, 0));
                gs.draw(at.createTransformedShape(map.countryLines));
            }
            gs.setStroke(new BasicStroke((float) (Math.min(1.8, 1.05 + 0.08 * zoom) * dev), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            gs.setColor(new Color(0, 255, 0));
            gs.draw(at.createTransformedShape(map.coastLines));
        }
        gl.dispose();
        gs.dispose();
        int[] a = ((DataBufferInt) landImg.getRaster().getDataBuffer()).getData(), b = ((DataBufferInt) lineImg.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < mask.length; i++) mask[i] = (a[i] & 0xFF0000) | (b[i] & 0x00FFFF);
    }

    // ------------------------------------------------------------------ base colours

    private static final int[] LAND_NAT = new int[181], OCEAN_NAT = new int[181], LAND_DATA = new int[181], OCEAN_DATA = new int[181];

    private static int mix(int a, int b, double f) {
        int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * f), g = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * f),
                bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * f);
        return (r << 16) | (g << 8) | bl;
    }

    static {
        // "natural" land by latitude: rain forest, savanna and desert, temperate green, boreal, tundra, ice
        double[] at = {0, 9, 19, 28, 38, 50, 62, 70, 78, 90};
        int[] col = {0x3E7748, 0x5E8A4A, 0xA89C66, 0xBDA873, 0x8C9C5E, 0x5F8650, 0x4E7458, 0x7F8F7C, 0xC4CCCC, 0xEEF2F5};
        for (int k = 0; k <= 180; k++) {
            double al = Math.abs(k - 90);
            int i = 0;
            while (i < at.length - 2 && al > at[i + 1]) i++;
            LAND_NAT[k] = mix(col[i], col[i + 1], Model.clamp((al - at[i]) / (at[i + 1] - at[i]), 0, 1));
            OCEAN_NAT[k] = mix(0x1C4E86, 0x12305C, Math.pow(al / 90.0, 1.4));
            LAND_DATA[k] = mix(0x8C9198, 0x9AA0A8, al / 90.0);
            OCEAN_DATA[k] = mix(0x142744, 0x0E1C34, al / 90.0);
        }
    }

    // ------------------------------------------------------------------ the map itself

    private int[] colMask = new int[0], colCell = new int[0];
    private float[] colFrac = new float[0], colSun = new float[0];
    private final int[] nightLut = new int[256];
    private final ThreadLocal<float[][]> rowBuf = ThreadLocal.withInitial(() -> new float[2][]);
    /** Device pixels per logical pixel of the frame being drawn. */
    private double dev = 1;

    /**
     * Compose the map into {@code buf} (the panel's buffer, device pixels; {@code dev} device px per logical px).
     * {@code g} is a graphics of the same buffer scaled by {@code dev}; it is only used when the buffer cannot be written directly.
     */
    void paintMap(Graphics2D g, BufferedImage buf, double dev, WorldModel.Frame f, double dt) {
        this.dev = dev;
        if (map.problem != null) return;
        if (!maskValid(dev)) buildMask(dev);
        int[] tgt = null;
        int bw = buf.getWidth(), bh = buf.getHeight();
        BufferedImage own = null;
        if ((buf.getType() == BufferedImage.TYPE_INT_RGB || buf.getType() == BufferedImage.TYPE_INT_ARGB_PRE) && buf.getRaster().getDataBuffer() instanceof DataBufferInt db
                && db.getData().length == bw * bh) tgt = db.getData();
        final int x0 = Math.max(0, (int) Math.round(rect.x * dev)), y0 = Math.max(0, (int) Math.round(rect.y * dev));
        final int x1 = Math.min(bw, (int) Math.round((rect.x + rect.width) * dev)), y1 = Math.min(bh, (int) Math.round((rect.y + rect.height) * dev));
        if (x1 <= x0 || y1 <= y0) return;
        final int n = x1 - x0;
        final int tw;
        final int ox, oy;
        if (tgt == null) {
            own = new BufferedImage(n, y1 - y0, BufferedImage.TYPE_INT_RGB);
            tgt = ((DataBufferInt) own.getRaster().getDataBuffer()).getData();
            tw = n; ox = x0; oy = y0;
        } else { tw = bw; ox = 0; oy = 0; }
        final int[] d = tgt;

        // which fields this layer blends, through which tables
        final double blend = f == null ? 1 : blendOf(f);
        final float[] fa, fb;
        final int[] la, lb;
        final float loA, kA, loB, kB;
        boolean natural = layer == CLOUD || layer == PRECIP || layer == SNOW || layer == PLAIN;
        if (f == null || !f.hasGrid()) { fa = null; fb = null; la = null; lb = null; loA = 0; kA = 0; loB = 0; kB = 0; }
        else switch (layer) {
            case PRECIP -> { fa = field(f, CLOUD, blend); la = VEIL; loA = 0; kA = LUT_N - 1; fb = field(f, PRECIP, blend); lb = LUT[PRECIP]; loB = 0; kB = LUT_N - 1; }
            case CLOUD -> { fa = field(f, SNOW, blend); la = SNOW_SOFT; loA = 0; kA = LUT_N - 1; fb = field(f, CLOUD, blend); lb = LUT[CLOUD]; loB = 0; kB = LUT_N - 1; }
            case PLAIN -> { fa = field(f, SNOW, blend); la = SNOW_SOFT; loA = 0; kA = LUT_N - 1; fb = null; lb = null; loB = 0; kB = 0; }
            default -> {
                fa = field(f, layer, blend); la = LUT[layer];
                loA = (float) LO[layer]; kA = (float) ((LUT_N - 1) / (HI[layer] - LO[layer]));
                fb = null; lb = null; loB = 0; kB = 0;
            }
        }
        final int fcols = fc, frows = fr;
        final int[] landC = natural ? LAND_NAT : LAND_DATA, oceanC = natural ? OCEAN_NAT : OCEAN_DATA;
        // a data layer covers land and sea alike: the sea is drawn a little darker so that the continents still read
        final int seaDim = natural ? 0 : 40;

        // per column: where it reads the mask and the field, and the sun term
        if (colMask.length < n) { colMask = new int[n]; colCell = new int[n]; colFrac = new float[n]; colSun = new float[n]; }
        final double sv = scale * dev, cxDev = rect.getCenterX() * dev, cyDev = rect.getCenterY() * dev;
        final boolean fixed = lonFixed();
        final double sunLon = f == null ? 0 : f.sunLon, sunLat = f == null ? 0 : f.sunLat;
        final double flon = fcols > 0 ? 360.0 / fcols : 1, flat = frows > 0 ? 180.0 / frows : 1;
        for (int k = 0; k < n; k++) {
            double lon = clon + (x0 + k + 0.5 - cxDev) / sv;
            if (fixed && Math.abs(lon - clon) > 180) { colMask[k] = -1; continue; }
            double dm = lon - mLon0;
            dm -= 360 * Math.floor(dm / 360);
            int mx = (int) (dm * mScale);
            colMask[k] = mx >= mw ? (mFull ? mw - 1 : -1) : mx;
            double gx = (WorldMap.wrapLon(lon) + 180) / flon - 0.5;
            if (gx < 0) gx += fcols;
            int c0 = (int) gx;
            if (c0 >= fcols) c0 = fcols - 1;
            colCell[k] = c0;
            colFrac[k] = (float) (gx - c0);
            colSun[k] = (float) Math.cos(Math.toRadians(lon - sunLon));
        }
        final boolean night = showNight && f != null;
        final double deep = natural ? 0.80 : 0.50;
        for (int i = 0; i < 256; i++) {
            double cz = i / 127.5 - 1, dk = Model.clamp((0.05 - cz) / 0.24, 0, 1);
            nightLut[i] = (int) Math.round(dk * dk * (3 - 2 * dk) * deep * 256);
        }
        final double sinDec = Math.sin(Math.toRadians(sunLat)), cosDec = Math.cos(Math.toRadians(sunLat));
        final int[] msk = mask, cm = colMask, cc = colCell, nl = nightLut;
        final float[] cf = colFrac, cs = colSun;
        final int mwF = mw, mhF = mh;
        final double mTopF = mTop, mScaleF = mScale, clatF = clat;

        // the hand-drawn lines of this layer (isobars, wind streaks) go through the same pass
        final boolean lines = f != null && f.hasGrid() && (layer == PRESSURE || layer == WIND);
        if (lines) {
            int need = n * (y1 - y0);
            if (ovl.length < need) ovl = new int[need];
            java.util.Arrays.fill(ovl, 0, need, 0);
            ovW = n; ovH = y1 - y0; ovX = x0; ovY = y0;
            if (layer == PRESSURE) { float[] p = field(f, PRESSURE, blend); if (p != null) rasterIsobars(p, fc, fr); }
            else rasterStreaks(f, dt);
        }
        final int[] ov = lines ? ovl : null;

        IntStream.range(y0, y1).parallel().forEach(py -> {
            int o = (py - oy) * tw + (x0 - ox);
            double lat = clatF + (cyDev - (py + 0.5)) / sv;
            if (lat >= 90 || lat <= -90) { for (int k = 0; k < n; k++) d[o + k] = BG; return; }
            int my = (int) ((mTopF - lat) * mScaleF);
            int mrow = Math.max(0, Math.min(mhF - 1, my)) * mwF;
            int li = (int) Math.round(lat) + 90;
            int lc = landC[li], oc = oceanC[li];
            int lr = (lc >> 16) & 255, lg = (lc >> 8) & 255, lbl = lc & 255, or = (oc >> 16) & 255, og = (oc >> 8) & 255, ob = oc & 255;
            float[][] rb = rowBuf.get();
            float[] ra = null, rbb = null;
            if (fa != null) {
                double gy = (90 - lat) / flat - 0.5;
                int r0 = (int) Math.floor(gy);
                float fy = (float) (gy - r0);
                if (r0 < 0) { r0 = 0; fy = 0; }
                int r1 = Math.min(frows - 1, r0 + 1);
                if (r0 >= frows - 1) { r0 = frows - 1; r1 = r0; fy = 0; }
                if (rb[0] == null || rb[0].length != fcols + 1) { rb[0] = new float[fcols + 1]; rb[1] = new float[fcols + 1]; }
                ra = rb[0];
                int a0 = r0 * fcols, a1 = r1 * fcols;
                for (int c = 0; c < fcols; c++) ra[c] = fa[a0 + c] + (fa[a1 + c] - fa[a0 + c]) * fy;
                ra[fcols] = ra[0];
                if (fb != null) {
                    rbb = rb[1];
                    for (int c = 0; c < fcols; c++) rbb[c] = fb[a0 + c] + (fb[a1 + c] - fb[a0 + c]) * fy;
                    rbb[fcols] = rbb[0];
                }
            }
            double sinLat = Math.sin(Math.toRadians(lat)), cosLat = Math.cos(Math.toRadians(lat));
            float sA = (float) (sinLat * sinDec), sB = (float) (cosLat * cosDec);
            for (int k = 0; k < n; k++) {
                int mx = cm[k];
                if (mx < 0) { d[o + k] = BG; continue; }
                int m = msk[mrow + mx];
                int L = (m >> 16) & 255;
                L += L >> 7;
                int r = or + (((lr - or) * L) >> 8), gg = og + (((lg - og) * L) >> 8), b = ob + (((lbl - ob) * L) >> 8);
                if (ra != null) {
                    int c0 = cc[k];
                    float fx = cf[k];
                    float v = ra[c0] + (ra[c0 + 1] - ra[c0]) * fx;
                    int idx = (int) ((v - loA) * kA);
                    int c = la[idx < 0 ? 0 : idx >= LUT_N ? LUT_N - 1 : idx];
                    int a = c >>> 24;
                    if (a != 0) {
                        a += a >> 7;
                        r += ((((c >> 16) & 255) - r) * a) >> 8; gg += ((((c >> 8) & 255) - gg) * a) >> 8; b += (((c & 255) - b) * a) >> 8;
                    }
                    if (rbb != null) {
                        v = rbb[c0] + (rbb[c0 + 1] - rbb[c0]) * fx;
                        idx = (int) ((v - loB) * kB);
                        c = lb[idx < 0 ? 0 : idx >= LUT_N ? LUT_N - 1 : idx];
                        a = c >>> 24;
                        if (a != 0) {
                            a += a >> 7;
                            r += ((((c >> 16) & 255) - r) * a) >> 8; gg += ((((c >> 8) & 255) - gg) * a) >> 8; b += (((c & 255) - b) * a) >> 8;
                        }
                    }
                }
                if (seaDim != 0 && L < 256) { int q = 256 - (((256 - L) * seaDim) >> 8); r = (r * q) >> 8; gg = (gg * q) >> 8; b = (b * q) >> 8; }
                if (night) {
                    int ni = (int) ((sA + sB * cs[k] + 1f) * 127.5f);
                    int nd = nl[ni < 0 ? 0 : ni > 255 ? 255 : ni];
                    if (nd != 0) { r -= (r * 188 * nd) >> 16; gg -= (gg * 170 * nd) >> 16; b -= (b * 120 * nd) >> 16; }
                }
                int dark = (m >> 8) & 255, streak = 0;
                if (ov != null) {
                    int o2 = ov[(py - y0) * n + k];
                    if (o2 != 0) { int dk = (o2 >> 8) & 255; if (dk > dark) dark = dk; streak = o2 & 255; }
                }
                if (dark != 0) { int q = 256 - ((dark * 200) >> 8); r = (r * q) >> 8; gg = (gg * q) >> 8; b = (b * q) >> 8; }
                int light = m & 255;
                if (light != 0) { int q = (light * 70) >> 8; r += ((255 - r) * q) >> 8; gg += ((255 - gg) * q) >> 8; b += ((255 - b) * q) >> 8; }
                if (streak != 0) { int q = (streak * 236) >> 8; r += ((255 - r) * q) >> 8; gg += ((255 - gg) * q) >> 8; b += ((255 - b) * q) >> 8; }
                d[o + k] = 0xFF000000 | (r << 16) | (gg << 8) | b;
            }
        });
        if (own != null) {
            AffineTransform old = g.getTransform();
            g.setTransform(new AffineTransform());
            g.drawImage(own, x0, y0, null);
            g.setTransform(old);
        }
    }

    // ------------------------------------------------------------------ vector overlays

    /** The transforms that put a map-space path (x = lon, y = −lat) on the canvas: one per visible copy of the world. */
    private List<AffineTransform> copies() {
        List<AffineTransform> out = new ArrayList<>(3);
        double halfLon = rect.width / (2 * scale);
        for (int k = -1; k <= 1; k++) {
            double off = k * 360.0;
            if (lonFixed() && k != 0) continue;
            if (off + 180 < clon - halfLon || off - 180 > clon + halfLon) continue;
            out.add(new AffineTransform(scale, 0, 0, scale, rect.getCenterX() + (off - clon) * scale, rect.getCenterY() + clat * scale));
        }
        return out;
    }

    /** Fill and outline one region (hover, selection). */
    void paintRegion(Graphics2D g, WorldMap.Region r, Color fill, Color line, float width) {
        if (r == null || r.shape == null) return;
        Shape clip = g.getClip();
        g.clip(rect);
        for (AffineTransform at : copies()) {
            Shape s = at.createTransformedShape(r.shape);
            if (fill != null) { g.setColor(fill); g.fill(s); }
            if (line != null) {
                // the outline leaves out the edges that only exist because the map is cut at 180 degrees and at the pole
                g.setColor(line);
                g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(r.outline != null ? at.createTransformedShape(r.outline) : s);
            }
        }
        g.setClip(clip);
    }

    // ---- thin lines drawn by hand: isobars and wind streaks
    //
    // Thousands of short antialiased strokes per frame are far too slow through Java2D, so these go into
    // a coverage buffer of the map's own pixels (bits 8..15 = dark line, bits 0..7 = bright streak) which
    // the compositor above reads back; a few hundred microseconds instead of several milliseconds.

    private int[] ovl = new int[0];
    private int ovW, ovH, ovX, ovY;

    private void put(int x, int y, int shift, int v) {
        if (v <= 0 || x < 0 || y < 0 || x >= ovW || y >= ovH) return;
        int i = y * ovW + x, cur = ovl[i];
        if (((cur >> shift) & 255) < v) ovl[i] = (cur & ~(255 << shift)) | (v << shift);
    }

    /** An antialiased one-pixel line in map-buffer pixels. */
    private void wu(double x0, double y0, double x1, double y1, int shift, int inten) {
        int w = ovW, h = ovH;
        if ((x0 < 0 && x1 < 0) || (y0 < -1 && y1 < -1) || (x0 > w && x1 > w) || (y0 > h && y1 > h)) return;
        double dx = x1 - x0, dy = y1 - y0;
        if (Math.abs(dx) >= Math.abs(dy)) {
            if (x0 > x1) { double t = x0; x0 = x1; x1 = t; t = y0; y0 = y1; y1 = t; dx = -dx; dy = -dy; }
            if (dx < 1e-9) { put((int) Math.round(x0), (int) Math.round(y0), shift, inten); return; }
            double grad = dy / dx;
            int xa = Math.max(0, (int) Math.ceil(x0 - 0.5)), xb = Math.min(w - 1, (int) Math.floor(x1 + 0.5));
            double y = y0 + grad * (xa - x0);
            for (int x = xa; x <= xb; x++, y += grad) {
                int yi = (int) Math.floor(y);
                int fr = (int) ((y - yi) * inten);
                put(x, yi, shift, inten - fr);
                put(x, yi + 1, shift, fr);
            }
        } else {
            if (y0 > y1) { double t = x0; x0 = x1; x1 = t; t = y0; y0 = y1; y1 = t; dx = -dx; dy = -dy; }
            double grad = dx / dy;
            int ya = Math.max(0, (int) Math.ceil(y0 - 0.5)), yb = Math.min(h - 1, (int) Math.floor(y1 + 0.5));
            double x = x0 + grad * (ya - y0);
            for (int y = ya; y <= yb; y++, x += grad) {
                int xi = (int) Math.floor(x);
                int fr = (int) ((x - xi) * inten);
                put(xi, y, shift, inten - fr);
                put(xi + 1, y, shift, fr);
            }
        }
    }

    /** A line about {@code width} device pixels wide (1 = a single antialiased line). */
    private void stroke(double x0, double y0, double x1, double y1, int shift, int inten, double width) {
        if (width <= 1.15) { wu(x0, y0, x1, y1, shift, inten); return; }
        double dx = x1 - x0, dy = y1 - y0, len = Math.max(1e-9, Math.hypot(dx, dy)), nx = -dy / len * (width - 1) / 2, ny = dx / len * (width - 1) / 2;
        wu(x0 + nx, y0 + ny, x1 + nx, y1 + ny, shift, inten);
        wu(x0 - nx, y0 - ny, x1 - nx, y1 - ny, shift, inten);
        if (width > 2.2) wu(x0, y0, x1, y1, shift, inten);
    }

    /** Isobars every 4 hPa (1012 and every 16 hPa from there a little heavier), by marching squares over the refined pressure field. */
    private void rasterIsobars(float[] p, int cols, int rows) {
        double dl = 360.0 / cols, dla = 180.0 / rows, sv = scale * dev;
        double by = (rect.getCenterY() + clat * scale) * dev - ovY;
        double halfLat = rect.height / (2 * scale);
        int rTop = Math.max(0, (int) Math.floor((90 - (clat + halfLat)) / dla - 1)), rBot = Math.min(rows - 2, (int) Math.ceil((90 - (clat - halfLat)) / dla));
        double halfLon = rect.width / (2 * scale);
        for (int k = -1; k <= 1; k++) {
            double off = k * 360.0;
            if (lonFixed() && k != 0) continue;
            if (off + 181 < clon - halfLon || off - 181 > clon + halfLon) continue;
            double bx = (rect.getCenterX() + (off - clon) * scale) * dev - ovX;
            for (int r = rTop; r <= rBot; r++) {
                double ya = by - (90 - (r + 0.5) * dla) * sv, yb = ya + dla * sv;
                for (int c = 0; c < cols; c++) {
                    double xa = bx + (-180 + (c + 0.5) * dl) * sv, xb = xa + dl * sv;
                    if (xb < 0 || xa > ovW) continue;
                    int c1 = c + 1 == cols ? 0 : c + 1;
                    float a = p[r * cols + c], b = p[r * cols + c1], d = p[(r + 1) * cols + c], e = p[(r + 1) * cols + c1];
                    float lo = Math.min(Math.min(a, b), Math.min(d, e)), hi = Math.max(Math.max(a, b), Math.max(d, e));
                    int l0 = (int) Math.ceil(lo / 4.0), l1 = (int) Math.floor(hi / 4.0);
                    for (int lv = l0; lv <= l1; lv++) {
                        float t = lv * 4f;
                        if (t == lo && t == hi) continue;
                        double px0 = 0, py0 = 0, px1 = 0, py1 = 0, px2 = 0, py2 = 0, px3 = 0, py3 = 0;
                        int cnt = 0;
                        if ((a < t) != (b < t)) { px0 = xa + (xb - xa) * (t - a) / (b - a); py0 = ya; cnt++; }
                        if ((b < t) != (e < t)) { double x = xb, y = ya + (yb - ya) * (t - b) / (e - b); if (cnt == 0) { px0 = x; py0 = y; } else { px1 = x; py1 = y; } cnt++; }
                        if ((d < t) != (e < t)) {
                            double x = xa + (xb - xa) * (t - d) / (e - d), y = yb;
                            if (cnt == 0) { px0 = x; py0 = y; } else if (cnt == 1) { px1 = x; py1 = y; } else { px2 = x; py2 = y; }
                            cnt++;
                        }
                        if ((a < t) != (d < t)) {
                            double x = xa, y = ya + (yb - ya) * (t - a) / (d - a);
                            if (cnt == 1) { px1 = x; py1 = y; } else if (cnt == 2) { px2 = x; py2 = y; } else if (cnt == 3) { px3 = x; py3 = y; }
                            cnt++;
                        }
                        boolean bold = Math.floorMod(lv - 253, 4) == 0;
                        int inten = bold ? 235 : 150;
                        double wd = (bold ? 1.7 : 1.0) * Math.max(1, dev * 0.85);
                        if (cnt >= 2) stroke(px0, py0, px1, py1, 8, inten, wd);
                        if (cnt == 4) stroke(px2, py2, px3, py3, 8, inten, wd);
                    }
                }
            }
        }
    }

    // ---- wind streaks

    private static final int STREAKS = 1300;
    private final double[] pLon = new double[STREAKS], pLat = new double[STREAKS], pAge = new double[STREAKS], pLife = new double[STREAKS];
    private final Random rnd = new Random(7);
    private boolean streaksInit;

    private void respawn(int k, boolean anywhere) {
        double halfLon = Math.min(180, rect.width / (2 * scale)), halfLat = rect.height / (2 * scale);
        pLon[k] = clon + (rnd.nextDouble() * 2 - 1) * halfLon;
        pLat[k] = Model.clamp(clat + (rnd.nextDouble() * 2 - 1) * halfLat, -88, 88);
        pLife[k] = 2.2 + rnd.nextDouble() * 3.2;
        pAge[k] = anywhere ? rnd.nextDouble() * pLife[k] : 0;
    }

    /** Short streaks that drift with the wind (faster and brighter where it blows harder), into the line buffer. */
    private void rasterStreaks(WorldModel.Frame f, double dt) {
        if (!streaksInit || still) {
            if (still) rnd.setSeed(7);
            for (int k = 0; k < STREAKS; k++) respawn(k, true);
            streaksInit = true;
        }
        double halfLon = rect.width / (2 * scale) + 4, halfLat = rect.height / (2 * scale) + 4;
        double step = 1.15 / scale;                                 // degrees per (m/s) per second: 10 m/s is about 11 px/s on screen
        double cx = rect.getCenterX(), cy = rect.getCenterY();
        boolean fixed = lonFixed();
        for (int k = 0; k < STREAKS; k++) {
            double lon = pLon[k], lat = pLat[k];
            double u0 = sample(f.u, f.cols, f.rows, lon, lat), v0 = sample(f.v, f.cols, f.rows, lon, lat), sp = Math.hypot(u0, v0);
            if (!still && dt > 0) {
                double cl = Math.max(0.2, Math.cos(Math.toRadians(lat)));
                pLon[k] += u0 * step * dt * (0.6 / cl + 0.4);      // partly true to the globe, partly to the screen: no racing at the poles
                pLat[k] += v0 * step * dt;
                pAge[k] += dt;
                if (pAge[k] > pLife[k] || Math.abs(pLat[k]) > 89 || Math.abs(WorldMap.wrapLon(pLon[k] - clon)) > halfLon || Math.abs(pLat[k] - clat) > halfLat) { respawn(k, false); continue; }
                lon = pLon[k]; lat = pLat[k];
            }
            if (sp < 1.2) continue;
            double fade = Math.min(1, Math.min(pAge[k] / 0.5, (pLife[k] - pAge[k]) / 0.7));
            if (fade <= 0.05) continue;
            double tail = Model.clamp(sp * 0.9, 5, 30) / scale / 4;   // degrees per tail step
            double d = lon - clon;
            if (!fixed) d = WorldMap.wrapLon(d);
            double xx = (cx + d * scale) * dev - ovX, yy = (cy - (lat - clat) * scale) * dev - ovY;
            if (xx < -40 || yy < -40 || xx > ovW + 40 || yy > ovH + 40) continue;
            int base = sp > 15 ? 235 : sp > 7 ? 180 : 125;
            double wd = (sp > 15 ? 1.9 : sp > 7 ? 1.3 : 1.0) * Math.max(1, dev * 0.85);
            for (int q = 0; q < 4; q++) {
                double u = q == 0 ? u0 : sample(f.u, f.cols, f.rows, lon, lat), v = q == 0 ? v0 : sample(f.v, f.cols, f.rows, lon, lat), m = Math.max(0.5, Math.hypot(u, v));
                lon -= u / m * tail; lat -= v / m * tail;
                double nx = xx - u / m * tail * scale * dev, ny = yy + v / m * tail * scale * dev;
                // the tail thins out toward its end
                stroke(xx, yy, nx, ny, 0, (int) (base * fade * (1 - 0.2 * q)), wd);
                xx = nx; yy = ny;
            }
        }
    }

    // ---- small cached pictures: labels, glyphs and icons are drawn once and then only copied

    private static final Font F_GLYPH = new Font(Font.SANS_SERIF, Font.BOLD, 13), F_TAG = new Font(Font.SANS_SERIF, Font.BOLD, 11), F_TINY = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    private final java.util.HashMap<String, BufferedImage> sprites = new java.util.HashMap<>();
    private double spriteDev = -1;

    /** Draw (and remember) a small picture of {@code w} x {@code h} logical px with its top-left corner at (x, y). */
    private void sprite(Graphics2D g, String key, double x, double y, double w, double h, java.util.function.Consumer<Graphics2D> draw) {
        if (spriteDev != dev || sprites.size() > 900) { sprites.clear(); spriteDev = dev; }
        BufferedImage img = sprites.get(key);
        if (img == null) {
            img = new BufferedImage(Math.max(1, (int) Math.ceil(w * dev) + 2), Math.max(1, (int) Math.ceil(h * dev) + 2), BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D sg = img.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            sg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            sg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            sg.translate(1, 1);
            sg.scale(dev, dev);
            draw.accept(sg);
            sg.dispose();
            sprites.put(key, img);
        }
        AffineTransform old = g.getTransform();
        g.setTransform(IDENTITY);
        g.drawImage(img, (int) Math.round(x * dev) - 1, (int) Math.round(y * dev) - 1, null);
        g.setTransform(old);
    }

    private static final AffineTransform IDENTITY = new AffineTransform();
    private FontMetrics fmTag, fmTiny, fmGlyph;

    private void metrics(Graphics2D g) {
        if (fmTag == null) { fmTag = g.getFontMetrics(F_TAG); fmTiny = g.getFontMetrics(F_TINY); fmGlyph = g.getFontMetrics(F_GLYPH); }
    }

    /** A pill with a short text, centred on cx, its top at y. */
    private void tag(Graphics2D g, String s, double cx, double y, Font f, FontMetrics fm, Color fg, Color bg) {
        double w = fm.stringWidth(s) + 10, h = fm.getAscent() + fm.getDescent() + 2;
        sprite(g, "tag|" + s + "|" + f.getSize() + "|" + bg.getRGB() + "|" + fg.getRGB(), cx - w / 2, y, w, h, sg -> {
            sg.setColor(bg);
            sg.fill(new RoundRectangle2D.Double(0, 0, w, h, h, h));
            sg.setFont(f);
            sg.setColor(fg);
            sg.drawString(s, 5f, (float) (fm.getAscent() + 1));
        });
    }

    // ---- systems

    private void paintSystems(Graphics2D g, WorldModel.Frame f) {
        for (WorldModel.Sys s : f.systems) {
            double sx = x(s.lon), sy = y(s.lat);
            if (sx < rect.x - 60 || sx > rect.x + rect.width + 60 || sy < rect.y - 60 || sy > rect.y + rect.height + 60) continue;
            int hem = s.lat >= 0 ? 1 : -1;
            switch (s.kind) {
                case "low", "high" -> {
                    boolean low = s.kind.equals("low");
                    double r = Math.round((8.5 + 3 * s.strength) * 2) / 2.0, box = 2 * r + 10;
                    sprite(g, (low ? "L|" : "H|") + r, sx - box / 2, sy - box / 2, box, box, sg -> {
                        double c0 = box / 2;
                        Color c = low ? new Color(206, 58, 62) : new Color(52, 110, 214);
                        sg.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60));
                        sg.fill(new Ellipse2D.Double(c0 - r - 4, c0 - r - 4, 2 * r + 8, 2 * r + 8));
                        sg.setColor(c);
                        sg.fill(new Ellipse2D.Double(c0 - r, c0 - r, 2 * r, 2 * r));
                        sg.setColor(new Color(255, 255, 255, 230));
                        sg.setStroke(new BasicStroke(1.3f));
                        sg.draw(new Ellipse2D.Double(c0 - r, c0 - r, 2 * r, 2 * r));
                        sg.setFont(F_GLYPH);
                        String t = low ? "L" : "H";
                        sg.drawString(t, (float) (c0 - fmGlyph.stringWidth(t) / 2.0), (float) (c0 + fmGlyph.getAscent() / 2.0 - 1.5));
                    });
                    if (layer == PRESSURE && f.hasGrid())
                        tag(g, String.format("%.0f hPa", sample(f.pressure, f.cols, f.rows, s.lon, s.lat)), sx, sy + r + 3, F_TINY, fmTiny, Color.WHITE, new Color(8, 12, 22, 190));
                }
                case "cyclone" -> {
                    double r = Model.clamp(s.radiusDeg * scale * 0.55, 13, 54) * (0.7 + 0.3 * s.strength);
                    double rot = still ? 0.6 : anim * (1.6 + 1.4 * s.strength);
                    g.setColor(new Color(255, 255, 255, 46));
                    g.fill(new Ellipse2D.Double(sx - r, sy - r, 2 * r, 2 * r));
                    for (int pass = 0; pass < 2; pass++) {
                        g.setColor(pass == 0 ? new Color(40, 10, 20, 170) : new Color(255, 250, 244, 240));
                        g.setStroke(new BasicStroke((float) ((pass == 0 ? 4.4 : 2.4) * Math.max(1, r / 22)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        Path2D.Double p = new Path2D.Double();
                        for (int arm = 0; arm < 2; arm++) {
                            for (int i = 0; i <= 26; i += 2) {
                                // trailing arms: counter-clockwise in the north, clockwise in the south
                                double a = arm * Math.PI - i * 0.16 + rot, rr = r * (0.20 + 0.031 * i);
                                double px = sx + Math.cos(a) * rr, py = sy - hem * Math.sin(a) * rr;
                                if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
                            }
                        }
                        g.draw(p);
                    }
                    g.setColor(new Color(214, 50, 70));
                    g.fill(new Ellipse2D.Double(sx - r * 0.17, sy - r * 0.17, r * 0.34, r * 0.34));
                    g.setColor(Color.WHITE);
                    g.setStroke(new BasicStroke(1.2f));
                    g.draw(new Ellipse2D.Double(sx - r * 0.17, sy - r * 0.17, r * 0.34, r * 0.34));
                    String nm = s.name == null || s.name.isEmpty() || s.name.equals("null") ? "Tropical cyclone" : s.name;
                    tag(g, nm, sx, sy + r + 4, F_TAG, fmTag, Color.WHITE, new Color(150, 30, 52, 225));
                }
                case "front" -> {
                    // a cold front trailing toward the equator and the west of its low
                    double R = s.radiusDeg * scale;
                    Path2D.Double p = new Path2D.Double(), tris = new Path2D.Double();
                    int N = 14;
                    double[] fx = new double[N + 1], fy = new double[N + 1];
                    for (int i = 0; i <= N; i++) {
                        double t = i / (double) N * 2 - 1;
                        fx[i] = sx + t * R * 0.95;
                        fy[i] = sy - hem * (0.42 * R * t - 0.30 * R * (1 - t * t));
                        if (i == 0) p.moveTo(fx[i], fy[i]); else p.lineTo(fx[i], fy[i]);
                    }
                    for (int i = 1; i < N; i += 3) {
                        double dx = fx[i + 1] - fx[i - 1], dy = fy[i + 1] - fy[i - 1], len = Math.max(1e-6, Math.hypot(dx, dy));
                        double nx = -dy / len * hem, ny = dx / len * hem, tx = dx / len, ty = dy / len, sz = 5.5;
                        tris.moveTo(fx[i] - tx * sz, fy[i] - ty * sz);
                        tris.lineTo(fx[i] + tx * sz, fy[i] + ty * sz);
                        tris.lineTo(fx[i] + nx * sz * 1.5, fy[i] + ny * sz * 1.5);
                        tris.closePath();
                    }
                    g.setColor(new Color(8, 12, 24, 120));
                    g.setStroke(new BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(p);
                    g.setColor(new Color(58, 120, 232, (int) (150 + 90 * s.strength)));
                    g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(p);
                    g.fill(tris);
                }
                case "tornado_outbreak" -> {
                    double pulse = still ? 0.5 : 0.5 + 0.5 * Math.sin(anim * 4.2), r = Math.max(14, s.radiusDeg * scale * 0.8);
                    g.setColor(new Color(255, 190, 90, (int) (40 + 40 * pulse)));
                    g.fill(new Ellipse2D.Double(sx - r, sy - r, 2 * r, 2 * r));
                    g.setColor(new Color(255, 190, 90, 200));
                    g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 4f}, (float) (still ? 0 : anim * 10)));
                    g.draw(new Ellipse2D.Double(sx - r, sy - r, 2 * r, 2 * r));
                    sprite(g, "funnel", sx - 12, sy - 12, 24, 24, sg -> {
                        Path2D.Double fn = new Path2D.Double();
                        fn.moveTo(4, 4); fn.lineTo(20, 4); fn.lineTo(15, 11); fn.lineTo(13.5, 21); fn.lineTo(11, 11);
                        fn.closePath();
                        sg.setColor(new Color(40, 24, 8, 200));
                        sg.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        sg.draw(fn);
                        sg.setColor(new Color(255, 206, 120));
                        sg.fill(fn);
                    });
                    tag(g, "Tornado outbreak", sx, sy + r + 4, F_TAG, fmTag, new Color(40, 24, 8), new Color(255, 196, 100, 235));
                }
                default -> { }
            }
        }
    }

    // ---- weather icons

    private static final java.util.concurrent.ConcurrentHashMap<String, BufferedImage> ICONS = new java.util.concurrent.ConcurrentHashMap<>();

    /** A small weather drawing centred at (cx, cy), about 2·s wide (drawn once per kind and size, then copied). */
    static void weatherIcon(Graphics2D g, String weather, double cx, double cy, double s, boolean nightTime) {
        AffineTransform t = g.getTransform();
        double sc = Math.max(0.5, Math.abs(t.getScaleX()));
        if (t.getShearX() != 0 || t.getShearY() != 0) { drawWeatherIcon(g, weather, cx, cy, s, nightTime); return; }
        String key = weather + (nightTime ? "|n|" : "|d|") + Math.round(s * 10) + "|" + Math.round(sc * 100);
        int half = (int) Math.ceil(s * 1.5 * sc) + 2;
        BufferedImage img = ICONS.get(key);
        if (img == null) {
            if (ICONS.size() > 400) ICONS.clear();
            img = new BufferedImage(2 * half, 2 * half, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D ig = img.createGraphics();
            ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ig.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            ig.translate(half, half);
            ig.scale(sc, sc);
            drawWeatherIcon(ig, weather, 0, 0, s, nightTime);
            ig.dispose();
            ICONS.put(key, img);
        }
        java.awt.geom.Point2D.Double p = new java.awt.geom.Point2D.Double(cx, cy);
        t.transform(p, p);
        g.setTransform(IDENTITY);
        g.drawImage(img, (int) Math.round(p.x) - half, (int) Math.round(p.y) - half, null);
        g.setTransform(t);
    }

    /**
     * Is the sun below the horizon at this place? Worked out from the frame's subsolar point, the same geometry
     * that darkens the night side of the map, so an icon never shows a sun inside the drawn night (the local clock
     * alone gets the polar night and the midnight sun wrong).
     */
    static boolean nightAt(WorldModel.Frame f, double lat, double lon) {
        if (f == null) return false;
        double la = Math.toRadians(lat), de = Math.toRadians(f.sunLat);
        return Math.sin(la) * Math.sin(de) + Math.cos(la) * Math.cos(de) * Math.cos(Math.toRadians(lon - f.sunLon)) < 0;
    }

    private static void drawWeatherIcon(Graphics2D g, String weather, double cx, double cy, double s, boolean nightTime) {
        Color cloudC = new Color(236, 240, 246), dark = new Color(18, 24, 38, 190);
        boolean cloud = !weather.equals("clear") && !weather.equals("fog");
        if (weather.equals("clear")) {
            if (nightTime) {
                Path2D.Double moon = new Path2D.Double();
                java.awt.geom.Area a = new java.awt.geom.Area(new Ellipse2D.Double(cx - s * 0.7, cy - s * 0.7, s * 1.4, s * 1.4));
                a.subtract(new java.awt.geom.Area(new Ellipse2D.Double(cx - s * 0.25, cy - s * 0.95, s * 1.3, s * 1.3)));
                moon.append(a, false);
                g.setColor(dark); g.setStroke(new BasicStroke(2.2f)); g.draw(moon);
                g.setColor(new Color(226, 232, 250)); g.fill(moon);
            } else {
                g.setStroke(new BasicStroke((float) Math.max(1.2, s * 0.18), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(dark);
                g.fill(new Ellipse2D.Double(cx - s * 0.62, cy - s * 0.62, s * 1.24, s * 1.24));
                g.setColor(new Color(255, 214, 92));
                for (int k = 0; k < 8; k++) {
                    double a = k * Math.PI / 4;
                    g.draw(new Line2D.Double(cx + Math.cos(a) * s * 0.72, cy + Math.sin(a) * s * 0.72, cx + Math.cos(a) * s * 1.0, cy + Math.sin(a) * s * 1.0));
                }
                g.fill(new Ellipse2D.Double(cx - s * 0.5, cy - s * 0.5, s, s));
            }
            return;
        }
        if (weather.equals("fog")) {
            g.setStroke(new BasicStroke((float) Math.max(1.4, s * 0.22), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int pass = 0; pass < 2; pass++) {
                g.setColor(pass == 0 ? dark : new Color(206, 214, 226));
                double o = pass == 0 ? 0.8 : 0;
                for (int k = -1; k <= 1; k++) g.draw(new Line2D.Double(cx - s * (k == 0 ? 0.95 : 0.7), cy + k * s * 0.5 + o, cx + s * (k == 0 ? 0.95 : 0.7), cy + k * s * 0.5 + o));
            }
            return;
        }
        if (cloud) {
            double oy = weather.equals("cloudy") ? 0 : -s * 0.28;
            java.awt.geom.Area a = new java.awt.geom.Area(new RoundRectangle2D.Double(cx - s * 0.95, cy + oy - s * 0.05, s * 1.9, s * 0.6, s * 0.6, s * 0.6));
            a.add(new java.awt.geom.Area(new Ellipse2D.Double(cx - s * 0.62, cy + oy - s * 0.5, s * 0.8, s * 0.8)));
            a.add(new java.awt.geom.Area(new Ellipse2D.Double(cx - s * 0.1, cy + oy - s * 0.72, s * 0.95, s * 0.95)));
            g.setColor(dark); g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(a);
            g.setColor(weather.equals("storm") ? new Color(170, 178, 196) : cloudC); g.fill(a);
            g.setStroke(new BasicStroke((float) Math.max(1.3, s * 0.2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double by = cy + oy + s * 0.78;
            switch (weather) {
                case "rain" -> {
                    g.setColor(new Color(96, 176, 255));
                    for (int k = -1; k <= 1; k++) g.draw(new Line2D.Double(cx + k * s * 0.5 + s * 0.08, by, cx + k * s * 0.5 - s * 0.1, by + s * 0.42));
                }
                case "snow" -> {
                    g.setColor(Color.WHITE);
                    for (int k = -1; k <= 1; k++) { double r = Math.max(1.1, s * 0.15); g.fill(new Ellipse2D.Double(cx + k * s * 0.52 - r, by + (k == 0 ? s * 0.26 : 0.05 * s) - r + s * 0.1, 2 * r, 2 * r)); }
                }
                case "storm" -> {
                    Path2D.Double bolt = new Path2D.Double();
                    bolt.moveTo(cx + s * 0.12, by - s * 0.12); bolt.lineTo(cx - s * 0.22, by + s * 0.3); bolt.lineTo(cx + s * 0.04, by + s * 0.3); bolt.lineTo(cx - s * 0.12, by + s * 0.72);
                    g.setColor(new Color(255, 220, 90));
                    g.draw(bolt);
                }
                default -> { }
            }
        }
    }

    // ---- location dots

    /** Canvas position of every location of the model as last drawn (NaN = off the map). */
    double[] dotX = new double[0], dotY = new double[0];

    /** The location whose dot is under this canvas point (within {@code reach} px), or −1. */
    int dotAt(double px, double py, double reach) {
        int best = -1;
        double bd = reach * reach;
        for (int k = 0; k < dotX.length; k++) {
            if (Double.isNaN(dotX[k])) continue;
            double d = (dotX[k] - px) * (dotX[k] - px) + (dotY[k] - py) * (dotY[k] - py);
            if (d <= bd) { bd = d; best = k; }
        }
        return best;
    }

    /** A part of the map that labels keep clear of (the legend box); null = none. */
    Rectangle2D avoid;

    /**
     * Work out where every dot is on the canvas for the view as it is now. {@link #paintDots} does the same while it
     * draws; this is for the pointer, which is looked up before the map is painted (on the very first frame, and in a
     * one-frame snapshot, there would otherwise be no dots to find and a dot under the pointer read as its region).
     */
    void placeDots(WorldModel m) {
        int n = m.locations.size();
        if (dotX.length != n) { dotX = new double[n]; dotY = new double[n]; }
        for (int k = 0; k < n; k++) {
            WorldModel.Loc l = m.locations.get(k);
            double px = x(l.lon), py = y(l.lat);
            boolean in = rect.contains(px, py);
            dotX[k] = in ? px : Double.NaN;
            dotY[k] = in ? py : Double.NaN;
        }
    }

    private void paintDots(Graphics2D g, WorldModel m, WorldModel.Frame f, String hoverId, String selId) {
        int n = m.locations.size();
        if (dotX.length != n) { dotX = new double[n]; dotY = new double[n]; }
        List<Rectangle2D> taken = new ArrayList<>();
        if (avoid != null) taken.add(avoid);
        double zoom = zoomFactor();
        boolean pulseOn = !still;
        // dots first, so no label hides one
        for (int k = 0; k < n; k++) {
            WorldModel.Loc l = m.locations.get(k);
            double px = x(l.lon), py = y(l.lat);
            if (!rect.contains(px, py)) { dotX[k] = Double.NaN; dotY[k] = Double.NaN; continue; }
            dotX[k] = px; dotY[k] = py;
            taken.add(new Rectangle2D.Double(px - 8, py - 8, 16, 16));
        }
        // order: selected, hovered, then the rest
        int[] order = new int[n];
        int q = 0;
        for (int k = 0; k < n; k++) if (m.locations.get(k).id.equals(selId)) order[q++] = k;
        for (int k = 0; k < n; k++) if (m.locations.get(k).id.equals(hoverId) && !m.locations.get(k).id.equals(selId)) order[q++] = k;
        for (int k = 0; k < n; k++) if (!m.locations.get(k).id.equals(hoverId) && !m.locations.get(k).id.equals(selId)) order[q++] = k;
        for (int oi = n - 1; oi >= 0; oi--) {
            int k = order[oi];
            if (Double.isNaN(dotX[k])) continue;
            WorldModel.Loc l = m.locations.get(k);
            WorldModel.Now now = f == null ? null : f.now.get(l.id);
            boolean sel = l.id.equals(selId), hov = l.id.equals(hoverId);
            double px = dotX[k], py = dotY[k], r = sel ? 7 : hov ? 6.5 : 5.2;
            if (sel || hov) {
                double pr = r + 5 + (pulseOn && sel ? 2 * Math.sin(anim * 4) : 0);
                g.setColor(new Color(255, 226, 140, sel ? 90 : 60));
                g.fill(new Ellipse2D.Double(px - pr, py - pr, 2 * pr, 2 * pr));
            }
            boolean known = now != null && !Double.isNaN(now.tempC);
            int ci = known ? lutIndex(TEMP, now.tempC) / 6 * 6 : -1;
            double box = 2 * r + 8;
            sprite(g, "dot|" + ci + "|" + r, px - box / 2, py - box / 2, box, box, sg -> {
                double c0 = box / 2;
                sg.setColor(new Color(6, 10, 20, 150));
                sg.fill(new Ellipse2D.Double(c0 - r - 2.2, c0 - r - 2.2 + 0.8, 2 * r + 4.4, 2 * r + 4.4));
                sg.setColor(Color.WHITE);
                sg.fill(new Ellipse2D.Double(c0 - r - 1.4, c0 - r - 1.4, 2 * r + 2.8, 2 * r + 2.8));
                int c = ci < 0 ? 0xFF78B4FF : LUT[TEMP][ci];
                sg.setColor(new Color((c >> 16) & 255, (c >> 8) & 255, c & 255));
                sg.fill(new Ellipse2D.Double(c0 - r, c0 - r, 2 * r, 2 * r));
                sg.setColor(new Color(10, 16, 28, 200));
                sg.setStroke(new BasicStroke(1f));
                sg.draw(new Ellipse2D.Double(c0 - r, c0 - r, 2 * r, 2 * r));
            });
        }
        // labels: weather icon + temperature, and the name when there is room for it (or the pointer is on it)
        FontMetrics fm = fmTag;
        for (int oi = 0; oi < n; oi++) {
            int k = order[oi];
            if (Double.isNaN(dotX[k])) continue;
            WorldModel.Loc l = m.locations.get(k);
            WorldModel.Now now = f == null ? null : f.now.get(l.id);
            boolean sel = l.id.equals(selId), hov = l.id.equals(hoverId), named = sel || hov || zoom >= 1.9 || n <= 8;
            String temp = now == null || Double.isNaN(now.tempC) ? "" : WorldModel.whole(now.tempC) + "°";
            String name = named ? l.name : "";
            double iw = now == null ? 0 : 18, tw = fm.stringWidth(temp), nw = name.isEmpty() ? 0 : fm.stringWidth(name) + 7;
            double w = 7 + nw + iw + tw + (temp.isEmpty() ? 0 : 6), h = 19;
            if (w < 12) continue;
            // try right, left, above, below
            double[][] spots = {{dotX[k] + 11, dotY[k] - h / 2}, {dotX[k] - 11 - w, dotY[k] - h / 2}, {dotX[k] - w / 2, dotY[k] - 12 - h}, {dotX[k] - w / 2, dotY[k] + 12}};
            Rectangle2D.Double box = null;
            for (double[] sp : spots) {
                Rectangle2D.Double cand = new Rectangle2D.Double(sp[0], sp[1], w, h);
                if (cand.x < rect.x + 2 || cand.x + w > rect.x + rect.width - 2 || cand.y < rect.y + 2 || cand.y + h > rect.y + rect.height - 2) continue;
                boolean free = true;
                for (Rectangle2D t : taken) if (t.intersects(cand) && !(t.getWidth() == 16 && Math.abs(t.getCenterX() - dotX[k]) < 0.01 && Math.abs(t.getCenterY() - dotY[k]) < 0.01)) { free = false; break; }
                if (free) { box = cand; break; }
            }
            if (box == null) {
                if (!(sel || hov)) continue;
                box = new Rectangle2D.Double(Model.clamp(dotX[k] + 11, rect.x + 2, rect.x + rect.width - w - 2), Model.clamp(dotY[k] - h / 2, rect.y + 2, rect.y + rect.height - h - 2), w, h);
            }
            taken.add(box);
            String weather = now == null ? "" : now.weather;
            boolean nightThere = now != null && nightAt(f, l.lat, l.lon);
            sprite(g, "lab|" + name + "|" + temp + "|" + weather + "|" + nightThere + "|" + sel + "|" + hov, box.x, box.y, w, h, sg -> {
                sg.setColor(sel ? new Color(40, 58, 96, 240) : new Color(8, 12, 22, 215));
                sg.fill(new RoundRectangle2D.Double(0, 0, w, h, 10, 10));
                sg.setColor(sel || hov ? Ui.ACCENT : new Color(255, 255, 255, 60));
                sg.setStroke(new BasicStroke(sel || hov ? 1.3f : 1f));
                sg.draw(new RoundRectangle2D.Double(0.5, 0.5, w - 1, h - 1, 10, 10));
                double tx = 6;
                float base = (float) (h / 2 + fm.getAscent() / 2.0 - 1.5);
                sg.setFont(F_TAG);
                if (!name.isEmpty()) {
                    sg.setColor(Color.WHITE);
                    sg.drawString(name, (float) tx, base);
                    tx += nw;
                }
                if (!weather.isEmpty()) {
                    drawWeatherIcon(sg, weather, tx + 7, h / 2, 5.6, nightThere);
                    tx += iw;
                }
                if (!temp.isEmpty()) {
                    sg.setFont(F_TAG);
                    sg.setColor(new Color(236, 242, 250));
                    sg.drawString(temp, (float) tx, base);
                }
            });
        }
    }

    // ---- graticule labels and the sun

    private void edgeLabel(Graphics2D g, String s, double x, double y) {
        double w = fmTiny.stringWidth(s) + 4;
        sprite(g, "edge|" + s, x - 2, y, w, 13, sg -> {
            sg.setColor(new Color(6, 10, 20, 120));
            sg.fill(new RoundRectangle2D.Double(0, 0, w, 13, 6, 6));
            sg.setFont(F_TINY);
            sg.setColor(new Color(226, 234, 246, 150));
            sg.drawString(s, 2f, 10.5f);
        });
    }

    private void paintEdgeLabels(Graphics2D g) {
        double step = scale >= 22 ? 5 : scale >= 9 ? 10 : scale >= 4.2 ? 15 : 30;
        double left = Math.max(rect.x, x(clon - Math.min(180, rect.width / (2 * scale)))) + 4;
        for (double lat = -90 + step; lat < 90 - 1e-6; lat += step) {
            double py = y(lat);
            if (py < rect.y + 12 || py > rect.y + rect.height - 22) continue;
            edgeLabel(g, Math.abs(lat) < 1e-6 ? "0°" : String.format("%.0f°%s", Math.abs(lat), lat > 0 ? "N" : "S"), left, py - 7);
        }
        double halfLon = Math.min(180, rect.width / (2 * scale));
        double top = Math.max(rect.y, y(Math.min(90, clat + rect.height / (2 * scale)))) + 3;
        for (double lon = Math.ceil((clon - halfLon) / step) * step; lon <= clon + halfLon + 1e-6; lon += step) {
            double px = rect.getCenterX() + (lon - clon) * scale, wl = WorldMap.wrapLon(lon);
            if (px < left + 34 || px > rect.x + rect.width - 20) continue;
            String s = Math.abs(wl) < 1e-6 ? "0°" : Math.abs(Math.abs(wl) - 180) < 1e-6 ? "180°" : String.format("%.0f°%s", Math.abs(wl), wl > 0 ? "E" : "W");
            edgeLabel(g, s, px - fmTiny.stringWidth(s) / 2.0, top);
        }
    }

    private void paintSun(Graphics2D g, WorldModel.Frame f) {
        double sx = x(f.sunLon), sy = y(f.sunLat);
        if (!rect.contains(sx, sy)) return;
        sprite(g, "sun", sx - 16, sy - 16, 32, 32, sg -> {
            sg.setColor(new Color(255, 226, 120, 46));
            sg.fill(new Ellipse2D.Double(1, 1, 30, 30));
            sg.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int pass = 0; pass < 2; pass++) {
                sg.setColor(pass == 0 ? new Color(60, 40, 0, 150) : new Color(255, 222, 110));
                double o = pass == 0 ? 0.8 : 0;
                for (int k = 0; k < 8; k++) {
                    double a = k * Math.PI / 4;
                    sg.draw(new Line2D.Double(16 + Math.cos(a) * 7.5, 16 + Math.sin(a) * 7.5 + o, 16 + Math.cos(a) * 10.5, 16 + Math.sin(a) * 10.5 + o));
                }
                sg.fill(new Ellipse2D.Double(11, 11 + o, 10, 10));
            }
        });
    }

    /** Everything that is drawn over the coloured map. {@code hoverRegion}/{@code selRegion} may be null. */
    void paintOverlays(Graphics2D g, WorldModel m, WorldModel.Frame f, WorldMap.Region hoverRegion, boolean hoverClickable, WorldMap.Region selRegion,
                       String hoverId, String selId) {
        Shape clip = g.getClip();
        g.clip(rect);
        metrics(g);
        if (map.problem != null) {
            g.setColor(new Color(16, 28, 48));
            g.fill(rect);
            g.setFont(Ui.F_MED);
            g.setColor(Ui.RED);
            float yy = (float) rect.getCenterY() - 10;
            for (String line : map.problem.split("\n")) { g.drawString(line, (float) rect.x + 24, yy); yy += 19; }
            g.setClip(clip);
            return;
        }
        if (selRegion != null) paintRegion(g, selRegion, new Color(255, 226, 140, 46), new Color(255, 226, 140, 230), 1.8f);
        if (hoverRegion != null && hoverRegion != selRegion)
            paintRegion(g, hoverRegion, hoverClickable ? new Color(255, 255, 255, 62) : new Color(255, 255, 255, 30), hoverClickable ? new Color(255, 255, 255, 235) : new Color(255, 255, 255, 120),
                    hoverClickable ? 1.6f : 1.1f);
        if (showGraticule) paintEdgeLabels(g);
        if (f != null) {
            if (showNight) paintSun(g, f);
            if (showSystems) paintSystems(g, f);
        }
        if (showDots && m != null) paintDots(g, m, f, hoverId, selId);
        g.setClip(clip);
        g.setColor(new Color(120, 180, 255, 70));
        g.setStroke(new BasicStroke(1f));
        g.draw(new Rectangle2D.Double(rect.x + 0.5, rect.y + 0.5, rect.width - 1, rect.height - 1));
    }
}
