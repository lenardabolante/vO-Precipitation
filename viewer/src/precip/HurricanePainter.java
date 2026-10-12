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
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Draws the two pictures of the hurricane mode from one {@link HurricaneModel.Frame}:
 * <ul>
 *   <li><b>outside</b> — the storm seen from above like a satellite image: ocean (and land once the
 *       storm is over it), the rotating cloud shield, spiral rainbands, the eye, drifting wind
 *       tracers that follow the surface inflow, and km rings;</li>
 *   <li><b>inside</b> — the vertical cross-section through the centre, mirrored to both sides: sea
 *       surface with the surge, cloud, rain, warm-core shading and tracers that follow the radial /
 *       vertical circulation, with height and radius axes.</li>
 * </ul>
 * Both are computed per pixel on a coarse grid (in parallel rows) and scaled up, using two
 * tileable noise textures for the cloud detail (the same {@link Noise} functions the cloud painter
 * uses). Everything that decides what the pictures show (where cloud is, how strong the bands
 * are, how clear the eye is, which way the air moves) comes from the frame; this class holds no
 * meteorological constants. One instance per hurricane screen, used on one thread at a time.
 */
final class HurricanePainter {
    // ------------------------------------------------------------------ noise textures (built once)
    private static final int TEX = 256, TMASK = TEX - 1;
    private static final float[] FBM = texture(0), CELLS = texture(1);

    /** A tileable TEX x TEX noise field, 0..1: kind 0 = smooth fractal, 1 = billowy cells. */
    private static float[] texture(int kind) {
        float[] t = new float[TEX * TEX];
        final float p = kind == 0 ? 5f : 9f;                 // noise periods across the tile
        // (sequential on purpose: this runs in the class initialiser, where parallel workers would wait for the class forever)
        for (int y = 0; y < TEX; y++) {
            for (int x = 0; x < TEX; x++) {
                float u = x / (float) TEX, v = y / (float) TEX;
                // blend the four wrapped copies so that the tile repeats without a seam
                float a = raw(kind, u * p, v * p), b = raw(kind, (u - 1) * p, v * p), c = raw(kind, u * p, (v - 1) * p), d = raw(kind, (u - 1) * p, (v - 1) * p);
                float wu = u * u * (3 - 2 * u), wv = v * v * (3 - 2 * v);
                float top = a + (b - a) * wu, bot = c + (d - c) * wu;
                t[y * TEX + x] = top + (bot - top) * wv;
            }
        }
        // stretch to the full 0..1 range (the blend flattens the contrast a little)
        float lo = 1e9f, hi = -1e9f;
        for (float f : t) { lo = Math.min(lo, f); hi = Math.max(hi, f); }
        for (int k = 0; k < t.length; k++) t[k] = (t[k] - lo) / Math.max(1e-6f, hi - lo);
        return t;
    }

    private static float raw(int kind, float x, float y) {
        return kind == 0 ? 0.5f + 0.5f * Noise.fbm2(x + 31.7f, y + 17.3f, 9173, 5) : Noise.billow2(x + 11.3f, y + 47.9f, 4421, 4);
    }

    /** Bilinear, wrapping texture lookup; (x, y) in texels. */
    private static float tex(float[] t, float x, float y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        int x0 = ix & TMASK, x1 = (ix + 1) & TMASK, y0 = (iy & TMASK) * TEX, y1 = ((iy + 1) & TMASK) * TEX;
        float a = t[y0 + x0], b = t[y0 + x1], c = t[y1 + x0], d = t[y1 + x1];
        float top = a + (b - a) * fx, bot = c + (d - c) * fx;
        return top + (bot - top) * fy;
    }

    private static double sm(double x) {
        x = x < 0 ? 0 : x > 1 ? 1 : x;
        return x * x * (3 - 2 * x);
    }

    private static float smf(float x) {
        x = x < 0 ? 0 : x > 1 ? 1 : x;
        return x * x * (3 - 2 * x);
    }

    private static final Font F_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11), F_BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 12),
            F_TINY = new Font(Font.SANS_SERIF, Font.PLAIN, 10), F_CAP = new Font(Font.SANS_SERIF, Font.BOLD, 11);

    // ------------------------------------------------------------------ state eased between frames
    private HurricaneModel model;
    private HurricaneModel.Frame f;
    /** The cross-section as drawn: eased toward the last one received so the ~6 updates a second never step. */
    private double[] sCloud = new double[0], sU = new double[0], sW = new double[0], sTemp = new double[0];
    private int cols, rows;
    private double dr = 12.5, dz = 1.125;
    private long seenSection = -1;
    /** 0 = open ocean .. 1 = the centre is over land (eased from {@code over_land}). */
    private double land;
    private boolean primed;
    private double time;
    private int hem = 1;

    // radial tables at 1 km (index = km from the centre), rebuilt every frame from the cross-section
    private float[] tHigh = new float[0], tCore = new float[0], tEnv = new float[0], tEye = new float[0], tRain = new float[0];
    private double uRef = 1, wRef = 1, tempMax = 0, shieldKm;
    /** Read off the cross-section for the labels: strongest updraft and where, strongest surface inflow, strongest sinking in the eye, the outermost tall tower. */
    double maxUp, maxUpR, maxUpZ, maxInflow, eyeSink, bandR = -1, bandTopZ;

    // ---- where the pictures go: the panel's own software buffer, written directly when its layout allows
    private int[] tgt;
    private int tgtW, tgtH;
    private double tgtScale = 1;
    /** Live frames recompute one of the two pictures per paint, in turn (each is only new 30 times a second anyway). */
    private long paints;
    private boolean stillNow;
    private HurricaneModel.Frame oFrame;

    /** Say which image the following paint calls draw into, and at which device scale (g must be scaled by the same factor, nothing else). */
    void setTarget(BufferedImage img, double scale) {
        tgt = null;
        tgtScale = scale;
        if (img != null && (img.getType() == BufferedImage.TYPE_INT_RGB || img.getType() == BufferedImage.TYPE_INT_ARGB_PRE)
                && img.getRaster().getDataBuffer() instanceof DataBufferInt db) {
            tgt = db.getData();
            tgtW = img.getWidth();
            tgtH = img.getHeight();
        }
    }

    /** Scale an opaque RGB raster (sw x sh) up to {@code rect} with bilinear filtering, straight into the target (parallel rows). */
    private void blit(Graphics2D g, BufferedImage img, int[] src, int sw, int sh, Rectangle2D rect) {
        double rw = rect.getWidth(), rh = rect.getHeight();
        if (tgt == null) {
            Object oh = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, new AffineTransform(rw / sw, 0, 0, rh / sh, rect.getX(), rect.getY()), null);
            if (oh != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oh);
            return;
        }
        final double s = tgtScale, dx0 = rect.getX() * s, dy0 = rect.getY() * s, dw = rw * s, dh = rh * s;
        final int x0 = Math.max(0, (int) Math.round(dx0)), y0 = Math.max(0, (int) Math.round(dy0));
        final int x1 = Math.min(tgtW, (int) Math.round(dx0 + dw)), y1 = Math.min(tgtH, (int) Math.round(dy0 + dh));
        if (x1 <= x0 || y1 <= y0) return;
        // per destination column: source column and its 8-bit blend weight
        final int[] cx = new int[x1 - x0], cf = new int[x1 - x0];
        for (int x = x0; x < x1; x++) {
            double u = (x + 0.5 - dx0) * sw / dw - 0.5;
            int i = (int) Math.floor(u);
            cx[x - x0] = Math.max(0, Math.min(sw - 2, i));
            cf[x - x0] = i < 0 ? 0 : i >= sw - 1 ? 256 : (int) ((u - i) * 256);
        }
        final int[] d = tgt;
        final int tw = tgtW;
        IntStream.range(y0, y1).parallel().forEach(y -> {
            double v = (y + 0.5 - dy0) * sh / dh - 0.5;
            int j = (int) Math.floor(v);
            int fy = j < 0 ? 0 : j >= sh - 1 ? 256 : (int) ((v - j) * 256), gy = 256 - fy;
            j = Math.max(0, Math.min(sh - 2, j));
            int r0 = j * sw, r1 = r0 + sw, o = y * tw + x0;
            for (int k = 0, n = x1 - x0; k < n; k++) {
                int i = cx[k], fx = cf[k], gx = 256 - fx;
                int a = src[r0 + i], b = src[r0 + i + 1], c = src[r1 + i], e = src[r1 + i + 1];
                int wa = gx * gy, wb = fx * gy, wc = gx * fy, we = fx * fy;
                int rr = (((a >> 16) & 255) * wa + ((b >> 16) & 255) * wb + ((c >> 16) & 255) * wc + ((e >> 16) & 255) * we) >> 16;
                int gg = (((a >> 8) & 255) * wa + ((b >> 8) & 255) * wb + ((c >> 8) & 255) * wc + ((e >> 8) & 255) * we) >> 16;
                int bb = ((a & 255) * wa + (b & 255) * wb + (c & 255) * wc + (e & 255) * we) >> 16;
                d[o + k] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
            }
        });
    }

    /** Geometry of the two pictures as last drawn (logical canvas px), for callouts and hit tests. */
    final Rectangle2D.Double outRect = new Rectangle2D.Double(), inRect = new Rectangle2D.Double();
    private double outCx, outCy, outPxPerKm = 1;
    private double inZTop = 19, inZBot = -2, inHalfKm = 600;
    /**
     * The cross-section's horizontal scale: screen position = (radius / domain radius) ^ STRETCH. With 1 the eye would be
     * a few pixels wide in a picture that has to reach the rim of the storm; below 1 the centre is drawn wider.
     */
    private static final double STRETCH = 0.62;

    /**
     * Take the frame to draw and move the eased state on by {@code dt} seconds
     * ({@code dt < 0}: a still picture — everything is shown settled).
     */
    void update(HurricaneModel m, HurricaneModel.Frame fr, double dt) {
        model = m;
        f = fr;
        if (fr == null) return;
        boolean still = dt < 0;
        stillNow = still;
        paints++;
        hem = "S".equalsIgnoreCase(fr.hemisphere) ? -1 : 1;
        int n = fr.cols * fr.rows;
        boolean reshape = fr.cols != cols || fr.rows != rows || sCloud.length != n;
        if (reshape) {
            cols = fr.cols; rows = fr.rows;
            sCloud = new double[n]; sU = new double[n]; sW = new double[n]; sTemp = new double[n];
        }
        dr = fr.dr; dz = fr.dz;
        double k = still || reshape || !primed ? 1 : 1 - Math.exp(-dt / 0.22);
        if (fr.hasSection()) {
            for (int i = 0; i < n; i++) {
                sCloud[i] += (fr.cloud[i] - sCloud[i]) * k;
                sU[i] += (fr.u[i] - sU[i]) * k;
                sW[i] += (fr.w[i] - sW[i]) * k;
                sTemp[i] += (fr.temp[i] - sTemp[i]) * k;
            }
            seenSection = fr.sectionVersion;
        }
        double landTarget = fr.overLand ? 1 : 0;
        land = still || !primed ? landTarget : land + (landTarget - land) * (1 - Math.exp(-dt / 1.3));
        if (!still) time += dt;
        primed = true;
        tables();
        if (still) { tracersOut = null; tracersIn = null; }       // stills start from freshly warmed-up tracers
        stepTracers(still ? -1 : dt);
    }

    private double sec(double[] a, double rk, double zk) {
        if (cols <= 0 || rows <= 0 || a.length < cols * rows) return 0;
        double gx = Math.abs(rk) / dr - 0.5, gy = zk / dz - 0.5;
        int c0 = (int) Math.floor(gx), r0 = (int) Math.floor(gy);
        double fx = gx - c0, fy = gy - r0;
        int ca = Math.max(0, Math.min(cols - 1, c0)), cb = Math.max(0, Math.min(cols - 1, c0 + 1));
        int ra = Math.max(0, Math.min(rows - 1, r0)), rb = Math.max(0, Math.min(rows - 1, r0 + 1));
        double top = a[ra * cols + ca] + (a[ra * cols + cb] - a[ra * cols + ca]) * fx;
        double bot = a[rb * cols + ca] + (a[rb * cols + cb] - a[rb * cols + ca]) * fx;
        return top + (bot - top) * fy;
    }

    /** Collapse the cross-section into what a view from above needs: how much high cloud, deep cloud and rain there is at each radius. */
    private void tables() {
        int nKm = (int) Math.ceil(model.radiusKm * 1.5) + 2;
        if (tHigh.length != nKm) { tHigh = new float[nKm]; tCore = new float[nKm]; tEnv = new float[nKm]; tEye = new float[nKm]; tRain = new float[nKm]; }
        double[] colHigh = new double[Math.max(1, cols)], colDeep = new double[Math.max(1, cols)];
        // "high" = the upper part of the cloud column (the outflow / anvil layer), "deep" = the towers below it
        double split = Math.max(dz, 0.66 * Math.max(f.cloudTop, dz * 4));
        double um = 0.5, wm = 0.05, tm = 0;
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                double v = sCloud[r * cols + c], z = (r + 0.5) * dz;
                if (z >= split) colHigh[c] = Math.max(colHigh[c], v); else colDeep[c] = Math.max(colDeep[c], v);
                um = Math.max(um, Math.abs(sU[r * cols + c]));
                wm = Math.max(wm, Math.abs(sW[r * cols + c]));
                tm = Math.max(tm, sTemp[r * cols + c]);
            }
        }
        uRef = um; wRef = wm; tempMax = tm;
        double rmw = Math.max(4, f.rmw), eye = f.eye, clar = f.eyeClarity, R = model.radiusKm;
        maxUp = 0; maxInflow = 0; eyeSink = 0; bandR = -1; bandTopZ = 0;
        double bandBest = 0.3;
        for (int c = 0; c < cols; c++) {
            double rc = (c + 0.5) * dr;
            maxInflow = Math.max(maxInflow, -sU[c]);
            for (int r = 0; r < rows; r++) {
                double w = sW[r * cols + c];
                if (w > maxUp) { maxUp = w; maxUpR = rc; maxUpZ = (r + 0.5) * dz; }
                if (rc < Math.max(eye, dr) && -w > eyeSink) eyeSink = -w;
            }
            if (rc > 2.4 * rmw + 20 && colDeep[c] > bandBest) {
                bandBest = colDeep[c]; bandR = rc;
                for (int r = 0; r < rows; r++) if (sCloud[r * cols + c] > 0.25) bandTopZ = (r + 0.5) * dz;
            }
        }
        double shield = 0;
        for (int km = 0; km < nKm; km++) {
            double g = km / dr - 0.5;
            int c0 = (int) Math.floor(g);
            double fx = g - c0;
            int ca = Math.max(0, Math.min(cols - 1, c0)), cb = Math.max(0, Math.min(cols - 1, c0 + 1));
            double hi = cols == 0 ? 0 : colHigh[ca] + (colHigh[cb] - colHigh[ca]) * fx;
            double deep = cols == 0 ? 0 : colDeep[ca] + (colDeep[cb] - colDeep[ca]) * fx;
            double edge = 1 - sm((km - 0.94 * R) / (0.22 * R));          // the model stops at the domain radius: fade out
            // the towers near the centre are the eyewall; farther out they are rainbands, which the bands draw as spirals
            double core = deep * (1 - sm((km - 1.7 * rmw) / (1.3 * rmw + 6)));
            double env = 0;                                             // a smoothed "is there any cloud at this radius"
            for (int d = -2; d <= 2; d++) {
                int cc = Math.max(0, Math.min(cols - 1, (int) Math.round(g) + d));
                env += cols == 0 ? 0 : Math.max(colDeep[cc], colHigh[cc]) * (d == 0 ? 0.3 : Math.abs(d) == 1 ? 0.22 : 0.13);
            }
            tHigh[km] = (float) (hi * edge);
            tCore[km] = (float) (core * edge);
            tEnv[km] = (float) (Math.max(0, env - 0.09) * 1.1 * edge);        // (a faint haze of near-zero values is not cloud)
            // the eye: clear to the degree the frame says, with a sharp inner edge of the eyewall
            tEye[km] = (float) (eye > 0.5 ? 1 - Math.pow(clar, 0.55) * (1 - sm((km - 0.70 * eye) / (0.36 * eye + 1))) : 1);
            tRain[km] = (float) f.profile(f.rainR, km);
            if (hi * edge > 0.16) shield = km;
        }
        shieldKm = shield;
    }

    // =====================================================================================
    // outside view

    private int ogw, ogh;
    private double ogKm = -1;
    private int ogHem;
    private float[] oR = new float[0], oLn, oPhi, oCos, oSin, oX, oY, oRx, oRy, oTau, oHgt, oAlpha;
    /**
     * Radial scale of the view from above: screen radius = (r / domain radius) ^ LENS. At 1 the picture is true to scale and
     * the eye of a strong storm is a few pixels wide; a little below 1 enlarges the middle. The km rings show the scale.
     */
    private static final double LENS = 0.78;

    /** Screen distance from the centre (logical px) of a place {@code rKm} from the centre. */
    private double scr(double rKm) {
        double R = model.radiusKm;
        return Math.pow(Math.max(0, rKm) / R, LENS) * R * outPxPerKm;
    }
    private BufferedImage oImg;
    private int[] oPx;
    /** Swirl of the cloud texture: streaks trail the rotation (radians of turn per e-fold of radius). */
    private static final float SWIRL = 0.38f;

    private void outsideGrid(int gw, int gh, double kmPerCell) {
        if (gw == ogw && gh == ogh && Math.abs(kmPerCell - ogKm) < 1e-9 && ogHem == hem && oR.length == gw * gh) return;
        ogw = gw; ogh = gh; ogKm = kmPerCell; ogHem = hem;
        int n = gw * gh;
        oR = new float[n]; oLn = new float[n]; oPhi = new float[n]; oCos = new float[n]; oSin = new float[n]; oX = new float[n]; oY = new float[n];
        oRx = new float[n]; oRy = new float[n];
        oTau = new float[n]; oHgt = new float[n]; oAlpha = new float[n];
        oImg = new BufferedImage(gw, gh, BufferedImage.TYPE_INT_RGB);
        oPx = ((DataBufferInt) oImg.getRaster().getDataBuffer()).getData();
        double cx = (gw - 1) / 2.0, cy = (gh - 1) / 2.0;
        for (int j = 0; j < gh; j++) for (int i = 0; i < gw; i++) {
            // x to the east, y to the north; the Southern Hemisphere is the mirror image
            double x = (i - cx) * kmPerCell * hem, y = -(j - cy) * kmPerCell;
            // (x, y) are screen km; the real distance from the centre follows from the lens
            double rs = Math.hypot(x, y), R = model.radiusKm;
            double r = Math.max(0.4, R * Math.pow(rs / R, 1 / LENS)), phi = Math.atan2(y, x), ln = Math.log(r);
            int k = j * gw + i;
            oR[k] = (float) r; oLn[k] = (float) ln; oPhi[k] = (float) phi;
            oCos[k] = (float) Math.cos(phi + SWIRL * ln); oSin[k] = (float) Math.sin(phi + SWIRL * ln);
            oX[k] = (float) ((i - cx) * kmPerCell); oY[k] = (float) ((j - cy) * kmPerCell);
            double q = rs > 1e-6 ? r / rs : 1;
            oRx[k] = (float) (oX[k] * q); oRy[k] = (float) (oY[k] * q);       // real west–east / north–south km
        }
    }

    /** West–east position (km from the centre, east positive) of the coast at north–south offset y, or -inf while at sea. */
    private double coastKm(double yKm) {
        if (land < 0.003) return -1e9;
        double R = model.radiusKm;
        double wob = (tex(FBM, (float) (yKm * 0.55 + 300), 77f) - 0.5) * 0.36 * R + (tex(FBM, (float) (yKm * 2.3 + 90), 140f) - 0.5) * 0.07 * R;
        return -1.75 * R + sm(land) * 1.95 * R + wob;
    }

    /** West–east position (km, east positive) where the coast crosses the line through the centre; NaN while the storm is at sea. */
    double coastKm() { return land < 0.003 ? Double.NaN : coastKm(0); }

    /**
     * The storm from above, drawn into {@code rect} (logical px) of a graphics whose transform maps
     * logical px to device px with factor {@code dev}.
     * @param sstGlow 0..1 tint the sea warm (the tour's "warm sea surface" chapter)
     */
    void paintOutside(Graphics2D g, Rectangle2D rect, double dev, double sstGlow) {
        outRect.setRect(rect);
        if (f == null || model == null) return;
        double rw = rect.getWidth(), rh = rect.getHeight();
        if (rw < 8 || rh < 8) return;
        // the domain radius fits the shorter side with a little margin
        double pxPerKm = Math.min(rw, rh) / 2 / (model.radiusKm * 1.03);
        outPxPerKm = pxPerKm; outCx = rect.getX() + rw / 2; outCy = rect.getY() + rh / 2;
        // coarse grid: about 2 device px per cell, capped so a big window costs no more than a small one
        double cell = Math.max(2.0 / dev, Math.sqrt(rw * rh / 105_000.0));
        int gw = Math.max(8, (int) Math.ceil(rw / cell)), gh = Math.max(8, (int) Math.ceil(rh / cell));
        double kmPerCell = rw / gw / pxPerKm;
        // a new picture when the storm has a new frame and it is this picture's turn (or there is none yet / a still is wanted);
        // otherwise the last one is shown again, stretched to the rectangle if that is gliding
        boolean fresh = stillNow || oImg == null || (f != oFrame && (paints & 1) == 0) || (f != oFrame && inRect.isEmpty());
        if (!fresh) {
            blit(g, oImg, oPx, ogw, ogh, rect);
            return;
        }
        oFrame = f;
        outsideGrid(gw, gh, kmPerCell);

        final float rot = (float) f.rotation, cosR = (float) Math.cos(rot), sinR = (float) Math.sin(rot);
        final float org = (float) sm((f.organization - 0.10) / 0.72);
        final int nb = f.bands.length;
        final float[] bTheta = new float[nb], bLn0 = new float[nb], bInvP = new float[nb], bSin = new float[nb], bW = new float[nb], bI = new float[nb], bR0 = new float[nb], bR1 = new float[nb];
        for (int b = 0; b < nb; b++) {
            HurricaneModel.Band bd = f.bands[b];
            double p = Math.max(0.05, Math.abs(bd.pitch));
            bTheta[b] = (float) bd.theta; bLn0[b] = (float) Math.log(bd.r0); bInvP[b] = (float) (1 / p);
            bSin[b] = (float) (p / Math.sqrt(1 + p * p)); bW[b] = (float) (bd.width * 1.15); bI[b] = (float) bd.intensity;
            bR0[b] = (float) bd.r0; bR1[b] = (float) bd.r1;
        }
        final float[] high = tHigh, core = tCore, env = tEnv, eyeM = tEye;
        final int nKm = high.length;
        final float f1 = TEX / 520f, f2 = TEX / 150f, f3 = TEX / 62f;       // texels per km: big blotches, cells, fine grain
        final float drift = (float) (time * 0.35);
        final float TWO_PI = (float) (Math.PI * 2);

        // ---- pass 1: optical thickness and cloud-top height of every cell
        IntStream.range(0, gh).parallel().forEach(j -> {
            for (int i = 0, k = j * gw; i < gw; i++, k++) {
                float r = oR[k];
                int km = (int) r;
                if (km >= nKm - 1) { oTau[k] = 0; oHgt[k] = 0; oAlpha[k] = 0; continue; }
                float fr = r - km;
                float hi = high[km] + (high[km + 1] - high[km]) * fr, co = core[km] + (core[km + 1] - core[km]) * fr;
                float en = env[km] + (env[km + 1] - env[km]) * fr, ey = eyeM[km] + (eyeM[km + 1] - eyeM[km]) * fr;
                // the cloud texture lives in a frame that turns with the storm and is wound into a trailing spiral
                float c = oCos[k] * cosR + oSin[k] * sinR, s = oSin[k] * cosR - oCos[k] * sinR;
                float px = r * c, py = r * s;
                float n1 = tex(FBM, px * f1 + 40, py * f1 + 90);
                float n2 = tex(CELLS, px * f2 + drift, py * f2 - drift * 0.6f);
                float n3 = tex(CELLS, px * f3 + 13, py * f3 + 57);
                float lump = 0.62f * n2 + 0.38f * n3;
                // fine streaks fanning out from the centre (a function of the angle, bent a little by the swirl): the fibrous edge of the high cloud
                float streak = tex(FBM, c * 74 + 128 + oLn[k] * 5, s * 74 + 128);
                // spiral rainbands: distance to each log spiral, measured across the band
                float bands = 0;
                float a = rot - oPhi[k], ln = oLn[k];
                for (int b = 0; b < nb; b++) {
                    if (bI[b] < 0.01f || r < bR0[b] * 0.8f || r > bR1[b] * 1.05f) continue;
                    float d = a - bTheta[b] - (ln - bLn0[b]) * bInvP[b];
                    d -= TWO_PI * (float) Math.floor(d / TWO_PI + 0.5f);
                    float across = r * Math.abs(d) * bSin[b] / bW[b];
                    if (across >= 1) continue;
                    float gB = 1 - across * across;
                    float taper = smf((r - bR0[b] * 0.82f) / (bR0[b] * 0.3f)) * (1 - smf((r - bR1[b] * 0.72f) / (bR1[b] * 0.3f)));
                    bands += bI[b] * gB * gB * taper;
                }
                float ragged = 0.34f + 0.5f * (1 - org);                       // weak storms have broken, lumpy bands
                float bandTau = bands * (1 - ragged + ragged * 2f * lump) * (0.8f + 0.4f * n1);
                float coreTau = co * (0.80f + 0.40f * lump);
                // the high cloud seen from above: a thin anvil in the section is already an opaque white deck from space,
                // so its density is pushed through a soft threshold; the streaks and blotches fray its rim
                float deck = smf((hi * (0.58f + 0.52f * streak + 0.36f * n1) - 0.115f) / 0.24f);
                // ... and farther out a thin veil of cirrus that the sea and the rainbands show through
                float veil = hi * (0.25f + 1.0f * streak) * (0.5f + 0.7f * n1);
                float shield = deck * (0.84f + 0.16f * n3) + 0.30f * veil * (1 - deck);
                // unorganised convection: separate cells and clusters wherever the section has cloud
                float cells = smf((n2 - 0.50f) / 0.22f) * smf((n1 - 0.30f) / 0.30f);
                float loose = en * cells * 1.5f;
                // small fair-weather clouds dotted over the open sea around the storm
                float popcorn = smf((n3 - 0.88f) / 0.07f) * smf((n1 - 0.60f) / 0.14f) * 0.28f * (1 - deck) * (1 - Math.min(1, (hi + en) * 4));
                float tau = org * (coreTau + 1.25f * shield) + 1.25f * bandTau + (1 - org) * (loose + 0.5f * deck * n1) + popcorn;
                float hgt = org * (0.50f * coreTau * (0.7f + 0.6f * n3) + 0.70f * deck + 0.2f * veil) + 0.70f * bandTau * (0.6f + 0.8f * lump) * (1 - 0.65f * org * deck)
                        + (1 - org) * 0.85f * loose + 0.2f * popcorn;
                tau *= ey;
                hgt *= ey;
                oTau[k] = tau;
                oHgt[k] = hgt + 0.07f * lump * Math.min(1, tau * 2);
                oAlpha[k] = 1 - 1 / (1 + 2.8f * tau + 4.2f * tau * tau);
            }
        });

        // ---- pass 2: light the cloud tops from the upper left, lay them and their shadow over the sea / land
        final double R = model.radiusKm;
        final float warm = (float) (0.42 * Model.clamp(sstGlow, 0, 1));
        final boolean anyLand = land > 0.003;
        final int sh = Math.max(1, (int) Math.round(5 / kmPerCell / 2.0));       // shadow offset in cells
        IntStream.range(0, gh).parallel().forEach(j -> {
            for (int i = 0, k = j * gw; i < gw; i++, k++) {
                float x = oX[k], y = oY[k];
                // sea: deep blue with slow large-scale variation and a fine ripple
                float w1 = tex(FBM, x * 0.42f + 200, y * 0.42f + 60), w2 = tex(CELLS, x * 3.1f, y * 3.1f + 99);
                float sea = 0.86f + 0.20f * w1 + 0.05f * (w2 - 0.5f);
                float cr = 7 * sea, cg = 27 * sea + 4, cb = 58 * sea + 10;
                if (warm > 0) { cr += (150 - cr) * warm; cg += (70 - cg) * warm * 0.8f; cb += (40 - cb) * warm * 0.9f; }
                float coastX = anyLand ? (float) coastKm(oRy[k]) : 0, xr = oRx[k];
                if (anyLand && xr < coastX) {
                    float in = coastX - xr;                                      // km inland
                    float tx = tex(FBM, x * 0.9f + 20, y * 0.9f + 310), ty = tex(CELLS, x * 2.2f + 7, y * 2.2f);
                    float veg = 0.55f + 0.45f * tx, beach = 1 - smf(in / 9f);
                    cr = (58 + 52 * ty) * veg + 60 * beach; cg = (84 + 34 * tx) * veg + 46 * beach; cb = (46 + 20 * ty) * veg + 20 * beach;
                } else if (anyLand && xr < coastX + 14) {
                    float surf = 1 - (xr - coastX) / 14f;                        // pale shallow water along the shore
                    cr += 60 * surf * surf; cg += 90 * surf * surf; cb += 80 * surf * surf;
                }
                int is = Math.max(0, i - sh), js = Math.max(0, j - sh);
                float shadow = 1 - 0.5f * oAlpha[js * gw + is];
                cr *= shadow; cg *= shadow; cb *= shadow;
                float al = oAlpha[k];
                if (al > 0.003f) {
                    int ka = Math.min(gh - 1, j + 2) * gw + Math.min(gw - 1, i + 2), kb = Math.max(0, j - 2) * gw + Math.max(0, i - 2);
                    int kc = Math.min(gh - 1, j + 1) * gw + Math.min(gw - 1, i + 1), kd = Math.max(0, j - 1) * gw + Math.max(0, i - 1);
                    // rising toward the lower right = the slope faces the light, which comes from the upper left
                    float relief = 0.5f * (oHgt[ka] - oHgt[kb]) * 0.5f + 0.5f * (oHgt[kc] - oHgt[kd]);
                    float lit = 0.74f + relief * 1.5f + 0.18f * Math.min(1, oHgt[k]);
                    lit = lit < 0.46f ? 0.46f : lit > 1 ? 1 : lit;
                    float wr = 96 + 159 * lit, wg = 110 + 144 * lit, wb = 136 + 119 * lit;
                    cr += (wr - cr) * al; cg += (wg - cg) * al; cb += (wb - cb) * al;
                }
                oPx[k] = 0xFF000000 | ((int) Math.min(255, cr) << 16) | ((int) Math.min(255, cg) << 8) | (int) Math.min(255, cb);
            }
        });

        blit(g, oImg, oPx, gw, gh, rect);
    }

    /** Canvas point of a place {@code rKm} from the centre at screen angle {@code deg} (0 = east, counter-clockwise). */
    double[] outsideXY(double rKm, double deg) {
        double a = Math.toRadians(deg), pr = scr(rKm);
        return new double[]{outCx + Math.cos(a) * pr, outCy - Math.sin(a) * pr};
    }

    double outsidePxPerKm() { return outPxPerKm; }

    /** How far the high cloud shield reaches (km), from the cross-section. */
    double shieldKm() { return shieldKm; }

    double warmMax() { return tempMax; }

    /** Distance rings with their km labels, and the frame of the picture. */
    void paintOutsideRings(Graphics2D g, boolean labels) { paintOutsideRings(g, labels, null); }

    /** As above; a km label that would be cut by {@code avoid} (the inset picture) is left out. */
    void paintOutsideRings(Graphics2D g, boolean labels, Rectangle2D avoid) {
        if (f == null || model == null) return;
        Rectangle2D rect = outRect;
        Shape clip = g.getClip();
        g.clip(new RoundRectangle2D.Double(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight(), 14, 14));
        double R = model.radiusKm, step = niceStep(R / 3.2);
        g.setFont(F_TINY);
        for (double r = step; r <= R + 1; r += step) {
            double pr = scr(r);
            g.setColor(new Color(255, 255, 255, 46));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{3, 5}, 0));
            g.draw(new Ellipse2D.Double(outCx - pr, outCy - pr, pr * 2, pr * 2));
            if (labels) {
                String s = String.format("%.0f km", r);
                double lx = outCx + pr * 0.7071 + 3, ly = outCy + pr * 0.7071 + 11;
                boolean hidden = avoid != null && avoid.intersects(lx - 2, ly - 12, g.getFontMetrics().stringWidth(s) + 6, 16);
                if (!hidden && lx + 40 < rect.getMaxX() && ly < rect.getMaxY() - 4) {
                    g.setColor(new Color(0, 0, 0, 110));
                    g.drawString(s, (float) lx + 1, (float) ly + 1);
                    g.setColor(new Color(226, 236, 248, 200));
                    g.drawString(s, (float) lx, (float) ly);
                }
            }
        }
        g.setClip(clip);
    }

    static double niceStep(double raw) {
        double p = Math.pow(10, Math.floor(Math.log10(Math.max(1e-9, raw)))), m = raw / p;
        return (m < 1.5 ? 1 : m < 3.5 ? 2 : m < 7.5 ? 5 : 10) * p;
    }

    // ------------------------------------------------------------------ wind tracers (outside)

    /** Per tracer: r (km), phi (rad, counter-clockwise on screen), age, life, then TRAIL (x, y) canvas-independent km pairs. */
    private double[][] tracersOut, tracersIn;
    private static final int TRAIL = 15;
    private final Random rnd = new Random(12);

    private void spawnOut(double[] t, boolean anywhere) {
        double R = model.radiusKm;
        double u = rnd.nextDouble();
        t[0] = Math.max(f.eye * 1.2 + 6, R * (anywhere ? 0.05 + 0.95 * Math.sqrt(u) : 0.25 + 0.75 * Math.sqrt(u)));
        t[1] = rnd.nextDouble() * Math.PI * 2;
        t[2] = 0;
        t[3] = 2.2 + rnd.nextDouble() * 3.2;
        for (int k = 0; k < TRAIL; k++) { t[4 + k * 2] = Double.NaN; t[5 + k * 2] = Double.NaN; }
    }

    private void spawnIn(double[] t) {
        double R = model.radiusKm, top = model.topKm, q = rnd.nextDouble();
        double rmw = Math.max(4, f.rmw);
        double x, z;
        if (q < 0.34) { x = R * (0.12 + 0.85 * rnd.nextDouble()); z = dz * (0.15 + 1.2 * rnd.nextDouble()); }                       // the inflow layer
        else if (q < 0.56) { x = rmw * (0.7 + 1.1 * rnd.nextDouble()); z = top * 0.75 * rnd.nextDouble(); }                         // the eyewall
        else if (q < 0.80) { x = rmw + R * 0.8 * rnd.nextDouble(); z = Math.max(1, f.cloudTop) * (0.72 + 0.26 * rnd.nextDouble()); } // the outflow layer
        else if (q < 0.88) { x = Math.max(2, f.eye) * rnd.nextDouble() * 0.8; z = top * (0.15 + 0.6 * rnd.nextDouble()); }           // the eye
        else { x = R * rnd.nextDouble(); z = top * 0.9 * rnd.nextDouble(); }
        t[0] = rnd.nextBoolean() ? x : -x;
        t[1] = z;
        t[2] = 0;
        t[3] = 1.8 + rnd.nextDouble() * 2.8;
        for (int k = 0; k < TRAIL; k++) { t[4 + k * 2] = Double.NaN; t[5 + k * 2] = Double.NaN; }
    }

    /** Signed power-law compression: keeps the direction, makes slow air visible next to fast air. */
    private static double comp(double v, double ref) {
        double a = Math.abs(v) / Math.max(1e-9, ref);
        return Math.signum(v) * Math.pow(Math.min(1, a), 0.55);
    }

    private void stepTracers(double dt) {
        if (f == null || model == null) return;
        if (tracersOut == null) {
            tracersOut = new double[190][4 + TRAIL * 2];
            for (double[] t : tracersOut) { spawnOut(t, true); t[2] = rnd.nextDouble() * t[3]; }
            tracersIn = new double[220][4 + TRAIL * 2];
            for (double[] t : tracersIn) { spawnIn(t); t[2] = rnd.nextDouble() * t[3]; }
            dt = -1;
        }
        int steps = dt < 0 ? 50 : 1;
        double h = dt < 0 ? 1 / 30.0 : Math.min(0.1, dt);
        double R = model.radiusKm, top = model.topKm;
        double vRef = Math.max(42, f.vmaxMs);
        for (int s = 0; s < steps; s++) {
            for (double[] t : tracersOut) {
                t[2] += h;
                double r = t[0];
                double vt = f.profile(f.wind, r), ur = sec(sU, r, dz * 0.5);
                // display speed: compressed, in km of picture per second; the inflow angle of the data is kept (and widened a little to read)
                double sp = 0.11 * R * Math.pow(Math.min(1, vt / vRef), 0.55);
                double ratio = vt > 0.2 ? Model.clamp(ur / vt, -0.9, 0.5) * 1.6 : 0;
                for (int k = TRAIL - 1; k > 0; k--) { t[4 + k * 2] = t[2 + k * 2]; t[5 + k * 2] = t[3 + k * 2]; }
                t[4] = r * Math.cos(t[1]); t[5] = r * Math.sin(t[1]);
                t[1] += hem * sp / Math.max(8, r) * h;
                t[0] += sp * ratio * h;
                if (t[2] > t[3] || t[0] < Math.max(4, f.eye * 0.9) || t[0] > R * 1.02) spawnOut(t, false);
            }
            for (double[] t : tracersIn) {
                t[2] += h;
                double x = t[0], z = t[1];
                double u = sec(sU, x, z) * Math.signum(x == 0 ? 1 : x), w = sec(sW, x, z);
                double vx = comp(u, uRef) * R * 0.11, vz = comp(w, wRef) * top * 0.16;
                for (int k = TRAIL - 1; k > 0; k--) { t[4 + k * 2] = t[2 + k * 2]; t[5 + k * 2] = t[3 + k * 2]; }
                t[4] = x; t[5] = z;
                t[0] += vx * h;
                t[1] += vz * h;
                boolean slow = Math.abs(vx) < R * 0.006 && Math.abs(vz) < top * 0.008;
                if (t[2] > t[3] || (slow && t[2] > 0.35) || Math.abs(t[0]) > R || t[1] < 0.05 || t[1] > top) spawnIn(t);
            }
        }
    }

    /** The drifting streaks that show the surface wind spiralling in around the centre. */
    void paintOutsideWind(Graphics2D g, double strength) {
        if (f == null || tracersOut == null || strength <= 0.01) return;
        Shape clip = g.getClip();
        g.clip(new RoundRectangle2D.Double(outRect.getX(), outRect.getY(), outRect.getWidth(), outRect.getHeight(), 14, 14));
        double vRef = Math.max(42, f.vmaxMs);
        g.setStroke(new BasicStroke(1.25f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D.Double path = new Path2D.Double();
        for (double[] t : tracersOut) {
            double fade = Math.min(1, t[2] / 0.4) * Math.min(1, (t[3] - t[2]) / 0.6);
            if (fade <= 0.02 || Double.isNaN(t[4 + (TRAIL - 1) * 2])) continue;
            double v = f.profile(f.wind, t[0]) / vRef;
            path.reset();
            double hr = scr(t[0]);
            path.moveTo(outCx + hr * Math.cos(t[1]), outCy - hr * Math.sin(t[1]));
            for (int k = 1; k < TRAIL; k += 2) {
                // the trail is kept as real km offsets from the centre: bring each point through the lens
                double tx = t[4 + k * 2], ty = t[5 + k * 2], tr = Math.hypot(tx, ty), q = tr > 1e-6 ? scr(tr) / tr : 0;
                path.lineTo(outCx + tx * q, outCy - ty * q);
            }
            g.setColor(windColor(v, (int) (205 * fade * strength)));
            g.draw(path);
        }
        g.setClip(clip);
    }

    /** White for light air through yellow and orange to red for the strongest wind of the storm. */
    static Color windColor(double frac, int alpha) {
        frac = Model.clamp(frac, 0, 1);
        Color a = new Color(214, 236, 255), b = new Color(255, 232, 130), c = new Color(255, 150, 70), d = new Color(255, 84, 96);
        Color m = frac < 0.33 ? Renderer.mix(a, b, frac / 0.33) : frac < 0.66 ? Renderer.mix(b, c, (frac - 0.33) / 0.33) : Renderer.mix(c, d, (frac - 0.66) / 0.34);
        return Renderer.alpha(m, alpha);
    }

    /**
     * Arrows on a few rings showing which way the surface wind blows there (direction from the
     * frame: the tangential wind of the profile turned inward by the section's surface inflow).
     */
    void paintOutsideArrows(Graphics2D g, double strength) {
        if (f == null || strength <= 0.01) return;
        Shape clip = g.getClip();
        g.clip(new RoundRectangle2D.Double(outRect.getX(), outRect.getY(), outRect.getWidth(), outRect.getHeight(), 14, 14));
        double R = model.radiusKm, vRef = Math.max(42, f.vmaxMs);
        double[] radii = {Math.max(f.rmw * 1.6, R * 0.14), R * 0.36, R * 0.60, R * 0.84};
        int[] counts = {6, 9, 12, 14};
        for (int q = 0; q < radii.length; q++) {
            double r = radii[q], vt = f.profile(f.wind, r), ur = sec(sU, r, dz * 0.5);
            if (vt < 0.4) continue;
            double len = (9 + 17 * Math.pow(Math.min(1, vt / vRef), 0.6));
            for (int k = 0; k < counts[q]; k++) {
                double phi = (k + 0.5 * (q % 2)) * Math.PI * 2 / counts[q] + 0.3 * q;
                // unit vectors on screen: radial outward and tangential (counter-clockwise for N)
                double rx = Math.cos(phi), ry = -Math.sin(phi), tx = -Math.sin(phi) * hem, ty = -Math.cos(phi) * hem;
                double dx = tx * vt + rx * ur * 1.6, dy = ty * vt + ry * ur * 1.6, m = Math.hypot(dx, dy);
                dx /= m; dy /= m;
                double cx = outCx + rx * scr(r), cy = outCy + ry * scr(r);
                arrow(g, cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2, windColor(vt / vRef, (int) (235 * strength)), 1.6f);
            }
        }
        g.setClip(clip);
    }

    static void arrow(Graphics2D g, double x1, double y1, double x2, double y2, Color c, float width) {
        double dx = x2 - x1, dy = y2 - y1, m = Math.hypot(dx, dy);
        if (m < 2) return;
        dx /= m; dy /= m;
        double hl = Math.min(m * 0.55, 4.5 + width * 2.6), hw = hl * 0.55;
        g.setStroke(new BasicStroke(width + 1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(4, 10, 22, Math.min(150, c.getAlpha())));
        g.draw(new Line2D.Double(x1, y1, x2 - dx * hl * 0.6, y2 - dy * hl * 0.6));
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(c);
        g.draw(new Line2D.Double(x1, y1, x2 - dx * hl * 0.6, y2 - dy * hl * 0.6));
        Path2D.Double head = new Path2D.Double();
        head.moveTo(x2, y2);
        head.lineTo(x2 - dx * hl - dy * hw, y2 - dy * hl + dx * hw);
        head.lineTo(x2 - dx * hl + dy * hw, y2 - dy * hl - dx * hw);
        head.closePath();
        g.fill(head);
    }

    /** A dashed circle at {@code rKm} (the eye, the radius of maximum wind ...). */
    void ring(Graphics2D g, double rKm, Color c, float width, double phase) {
        double pr = scr(rKm);
        if (pr < 2) return;
        Shape clip = g.getClip();
        g.clip(outRect);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{7, 6}, (float) (phase % 13)));
        g.setColor(c);
        g.draw(new Ellipse2D.Double(outCx - pr, outCy - pr, pr * 2, pr * 2));
        g.setClip(clip);
    }

    /** Trace the centre line of every rainband that is there (intensity above a few percent). */
    void paintBandLines(Graphics2D g, Color c, float width) {
        if (f == null) return;
        Shape clip = g.getClip();
        g.clip(outRect);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{2, 7}, 0));
        for (HurricaneModel.Band b : f.bands) {
            if (b.intensity < 0.08) continue;
            double p = Math.max(0.05, Math.abs(b.pitch));
            Path2D.Double path = new Path2D.Double();
            boolean first = true;
            for (double r = b.r0; r <= b.r1 * 0.9; r *= 1.035) {
                // band angle (clockwise in the storm's frame) where the spiral has radius r, turned to the screen
                double a = b.theta + Math.log(r / b.r0) / p, phi = f.rotation - a;
                double x = outCx + hem * Math.cos(phi) * scr(r), y = outCy - Math.sin(phi) * scr(r);
                if (first) { path.moveTo(x, y); first = false; } else path.lineTo(x, y);
            }
            g.setColor(Renderer.alpha(c, (int) (c.getAlpha() * Math.min(1, b.intensity * 1.4))));
            g.draw(path);
        }
        g.setClip(clip);
    }

    /** Big curved arrows around the storm showing its sense of rotation. */
    void paintRotation(Graphics2D g, double rKm, Color c) {
        if (f == null) return;
        double pr = scr(rKm);
        Shape clip = g.getClip();
        g.clip(outRect);
        for (int q = 0; q < 3; q++) {
            double a0 = q * 120 + 20 + Math.toDegrees(f.rotation) * 0.25 * hem, sweep = 62 * hem;
            g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(c);
            g.draw(new java.awt.geom.Arc2D.Double(outCx - pr, outCy - pr, pr * 2, pr * 2, a0, sweep, java.awt.geom.Arc2D.OPEN));
            double a1 = Math.toRadians(a0 + sweep);
            double ex = outCx + Math.cos(a1) * pr, ey = outCy - Math.sin(a1) * pr;
            double tx = -Math.sin(a1) * hem, ty = -Math.cos(a1) * hem;
            Path2D.Double head = new Path2D.Double();
            head.moveTo(ex + tx * 13, ey + ty * 13);
            head.lineTo(ex - ty * 7, ey + tx * 7);
            head.lineTo(ex + ty * 7, ey - tx * 7);
            head.closePath();
            g.fill(head);
        }
        g.setClip(clip);
    }

    // =====================================================================================
    // inside view (cross-section)

    private int igw, igh;
    private BufferedImage iImg;
    private int[] iPx;
    private float[] iSurf = new float[0], iGround = new float[0];

    /** Vertical exaggeration of the storm surge in the cross-section: km of picture per metre of water. */
    private static final double SURGE_KM_PER_M = 0.13;

    /** Sea-surface height (km, exaggerated) at west–east position x: calm sea, lifted under the storm by the surge. */
    double surfaceKm(double xKm) {
        double rmw = Math.max(6, f.rmw), s = f.surge * SURGE_KM_PER_M;
        double dome = Math.exp(-Math.pow(Math.abs(xKm) / (rmw * 2.4 + 30), 2));
        double pile = 0;
        if (land > 0.003) {                                   // against the coast the water piles up higher still
            double cx = coastKm(0);
            if (xKm >= cx) pile = 0.7 * Math.exp(-(xKm - cx) / (rmw * 1.5 + 25));
        }
        double waves = 0.012 * Math.sin(xKm * 0.05 + time * 2.3) + 0.007 * Math.sin(xKm * 0.13 - time * 3.1);
        return s * Math.min(1.25, dome + pile) + waves * (0.5 + Math.min(1.5, f.vmaxMs / 30));
    }

    /** Land surface (km) at x, or a large negative number at sea. */
    private double groundKm(double xKm) {
        if (land < 0.003) return -99;
        double cx = coastKm(0);
        if (xKm > cx) return -99;
        double in = cx - xKm;
        return -0.25 + 0.75 * sm(in / 60) + 0.35 * tex(FBM, (float) (xKm * 1.7), 9f) * sm(in / 120);
    }

    /**
     * The cross-section through the centre, drawn into {@code rect}.
     * @param warmGlow 0..1 extra weight for the warm-core shading (the tour's warm-core chapter)
     */
    void paintInside(Graphics2D g, Rectangle2D rect, double dev, double warmGlow) {
        inRect.setRect(rect);
        if (f == null || model == null) return;
        double rw = rect.getWidth(), rh = rect.getHeight();
        if (rw < 8 || rh < 8) return;
        inHalfKm = model.radiusKm;
        inZTop = model.topKm * 1.06;
        inZBot = -model.topKm * 0.11;
        double cell = Math.max(2.0 / dev, Math.sqrt(rw * rh / 90_000.0));
        int gw = Math.max(8, (int) Math.ceil(rw / cell)), gh = Math.max(8, (int) Math.ceil(rh / cell));
        boolean freshIn = stillNow || iImg == null || (paints & 1) == 1 || outRect.isEmpty();
        if (!freshIn) {
            blit(g, iImg, iPx, igw, igh, rect);
            return;
        }
        if (gw != igw || gh != igh || iImg == null) {
            igw = gw; igh = gh;
            iImg = new BufferedImage(gw, gh, BufferedImage.TYPE_INT_RGB);
            iPx = ((DataBufferInt) iImg.getRaster().getDataBuffer()).getData();
            iSurf = new float[gw]; iGround = new float[gw];
        }
        final double kmPerRow = (inZTop - inZBot) / gh;
        final double[] colX = new double[gw];
        for (int i = 0; i < gw; i++) {
            double u = (i + 0.5) / gw * 2 - 1;
            double x = Math.signum(u) * Math.pow(Math.abs(u), 1 / STRETCH) * inHalfKm;
            colX[i] = x;
            iSurf[i] = (float) surfaceKm(x);
            iGround[i] = (float) groundKm(x);
        }
        final double tRef = Math.max(5, tempMax), warmA = 0.46 + 0.34 * Model.clamp(warmGlow, 0, 1);
        final float fall = (float) (time * 26);
        final double rainRef = Math.max(12, f.rain);
        final float aspect = (float) (rw / gw / (rh / gh));
        final double day = 1;
        IntStream.range(0, gw).parallel().forEach(i -> {
            double x = colX[i], r = Math.abs(x);
            int km = Math.min(tRain.length - 1, (int) r);
            double rain = tRain.length == 0 ? 0 : tRain[km];
            double above = 0;                                   // km of cloud between this cell and space
            double surf = iSurf[i], ground = iGround[i];
            for (int j = 0; j < gh; j++) {
                double z = inZTop - (j + 0.5) * kmPerRow;
                float cr, cg, cb;
                if (z <= ground) {                              // land
                    float d = (float) Math.min(1, (ground - z) / 1.6);
                    float n = tex(FBM, i * 2.2f, j * 2.2f);
                    cr = (96 - 44 * d) * (0.8f + 0.4f * n); cg = (92 - 40 * d) * (0.8f + 0.4f * n); cb = (58 - 26 * d) * (0.8f + 0.4f * n);
                    if (ground - z < kmPerRow * 1.2) { cr = 96; cg = 126; cb = 70; }
                } else if (z <= surf - kmPerRow * 0.5) {        // sea
                    float d = (float) Math.min(1, (surf - z) / Math.max(0.4, surf - inZBot));
                    cr = 14 - 8 * d; cg = 70 - 40 * d; cb = 104 - 52 * d;
                    if (surf - z < kmPerRow * 1.6) { float e = (float) (1 - (surf - z) / (kmPerRow * 1.6)); cr += (150 - cr) * e; cg += (206 - cg) * e; cb += (226 - cb) * e; }
                } else {
                    // sky: darker aloft, hazy near the sea
                    float u = (float) Model.clamp((z - 0) / inZTop, 0, 1);
                    cr = 150 - 132 * u; cg = 186 - 150 * u; cb = 216 - 144 * u;
                    double c = sec(sCloud, r, z);
                    float n = tex(CELLS, i * 1.7f, j * 1.7f * aspect + 31) * 0.6f + tex(FBM, i * 3.9f + 70, j * 3.9f * aspect) * 0.4f;
                    double cd = c * (0.74 + 0.55 * n) + (n - 0.5) * 0.12 * Math.min(1, c * 5);
                    double a = sm((cd - 0.085) / 0.30);
                    // warm core: where the air is warmer than its surroundings
                    double tw = sec(sTemp, r, z) / tRef;
                    if (tw > 0.03) {
                        double ta = Math.min(1, Math.pow(tw, 0.85)) * warmA * (1 - 0.55 * a);
                        float hr = 255, hg = (float) (214 - 150 * Math.min(1, tw)), hb = (float) (96 - 60 * Math.min(1, tw));
                        cr += (hr - cr) * ta; cg += (hg - cg) * ta; cb += (hb - cb) * ta;
                    }
                    // rain under the cloud: a grey veil with streaks sliding down
                    if (rain > 0.3 && above > 0.25 && a < 0.6) {
                        float streak = tex(FBM, i * 5.2f + 11, (j * aspect - fall) * 0.42f);
                        double ra = Math.min(1, rain / rainRef) * Math.min(1, above / 1.2) * (1 - a) * (0.20 + 0.55 * smf((streak - 0.52f) / 0.2f));
                        cr += (176 - cr) * ra; cg += (198 - cg) * ra; cb += (226 - cb) * ra;
                    }
                    if (a > 0.004) {
                        // lit from above: bright tops, grey flanks, dark bases under a deep column
                        double lit = 0.30 + 0.70 * Math.exp(-above * 0.36) + 0.10 * (n - 0.5);
                        lit = Model.clamp(lit, 0.2, 1) * day;
                        float wr = (float) (84 + 170 * lit), wg = (float) (94 + 160 * lit), wb = (float) (116 + 138 * lit);
                        cr += (wr - cr) * a; cg += (wg - cg) * a; cb += (wb - cb) * a;
                        above += a * kmPerRow;
                    }
                    if (z < surf + kmPerRow * 0.5) {            // the cell the water line runs through: blend sea and air
                        float e = (float) Model.clamp((surf + kmPerRow * 0.5 - z) / kmPerRow, 0, 1);
                        cr += (150 - cr) * e; cg += (206 - cg) * e; cb += (226 - cb) * e;
                    }
                }
                iPx[j * gw + i] = 0xFF000000 | ((int) Math.max(0, Math.min(255, cr)) << 16) | ((int) Math.max(0, Math.min(255, cg)) << 8) | (int) Math.max(0, Math.min(255, cb));
            }
        });
        blit(g, iImg, iPx, gw, gh, rect);
    }

    /** Canvas point of (west–east km from the centre, height km) in the cross-section. */
    double[] insideXY(double xKm, double zKm) {
        double u = Math.signum(xKm) * Math.pow(Math.min(1.2, Math.abs(xKm) / inHalfKm), STRETCH);
        return new double[]{inRect.getX() + (0.5 + 0.5 * u) * inRect.getWidth(),
                inRect.getY() + (inZTop - zKm) / (inZTop - inZBot) * inRect.getHeight()};
    }

    /** Height where the warm core is strongest (km) and how warm it is there, from the cross-section. */
    double[] warmCorePeak() {
        double best = 0, bz = model == null ? 8 : model.topKm * 0.5;
        for (int r = 0; r < rows; r++) {
            double v = cols > 0 ? sTemp[r * cols] : 0;
            if (v > best) { best = v; bz = (r + 0.5) * dz; }
        }
        return new double[]{bz, best};
    }

    /** Height axis on the left, radius axis along the bottom. */
    void paintInsideAxes(Graphics2D g, boolean compact) {
        if (f == null || model == null) return;
        Rectangle2D rect = inRect;
        Shape clip = g.getClip();
        g.clip(new RoundRectangle2D.Double(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight(), 14, 14));
        g.setFont(F_TINY);
        FontMetrics fm = g.getFontMetrics();
        double zs = niceStep(model.topKm / (compact ? 2.4 : 4.2));
        for (double z = zs; z <= model.topKm + 0.01; z += zs) {
            double y = insideXY(0, z)[1];
            g.setColor(new Color(255, 255, 255, 34));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{2, 6}, 0));
            g.draw(new Line2D.Double(rect.getX(), y, rect.getMaxX(), y));
            String s = String.format("%.0f km", z);
            shadowText(g, s, rect.getX() + 7, y - 3, new Color(232, 240, 250, 220));
        }
        // radius ticks: doubling steps suit the stretched scale (close together near the centre in km, evenly spread on screen)
        double by = rect.getMaxY() - 6, R = model.radiusKm;
        java.util.List<Double> ticks = new java.util.ArrayList<>();
        ticks.add(0.0);
        // chosen on the right-hand side from the centre outward (a label needs room), mirrored to the left
        double edgeX = insideXY(0, 0)[0] + fm.stringWidth("centre") / 2.0 + 8;
        for (double t = niceStep(R / 12); t <= R * 1.001; t = t * 2 > R * 0.999 && t < R * 0.999 ? R : t * 2) {
            String s = String.format("%.0f km", t);
            double px = insideXY(t, 0)[0], left = Math.min(px - fm.stringWidth(s) / 2.0, rect.getMaxX() - fm.stringWidth(s) - 4);
            if (left >= edgeX) { ticks.add(t); ticks.add(-t); edgeX = left + fm.stringWidth(s) + (compact ? 14 : 8); }
            if (t >= R * 0.999) break;
        }
        for (double x : ticks) {
            double px = insideXY(x, 0)[0], sy = insideXY(0, 0)[1];
            g.setColor(new Color(255, 255, 255, 120));
            g.setStroke(new BasicStroke(1f));
            g.draw(new Line2D.Double(px, sy + 3, px, sy + 8));
            String s = Math.abs(x) < 1e-6 ? "centre" : String.format("%.0f km", Math.abs(x));
            double tx = Model.clamp(px - fm.stringWidth(s) / 2.0, rect.getX() + 4, rect.getMaxX() - fm.stringWidth(s) - 4);
            shadowText(g, s, tx, by, new Color(232, 240, 250, 220));
        }
        g.setClip(clip);
    }

    static void shadowText(Graphics2D g, String s, double x, double y, Color c) {
        g.setColor(new Color(2, 8, 18, Math.min(190, c.getAlpha())));
        g.drawString(s, (float) x + 1, (float) y + 1);
        g.setColor(c);
        g.drawString(s, (float) x, (float) y);
    }

    /**
     * The moving air of the cross-section: streaks that follow the radial and vertical wind of the
     * frame (in at the bottom, up the eyewall, out at the top, down in the eye), coloured by what
     * the air is doing.
     * @param emphasis one of "inflow", "eyewall", "outflow", "eye" (those streaks are drawn stronger), or anything else for all alike
     */
    void paintInsideFlow(Graphics2D g, double strength, String emphasis) {
        if (f == null || tracersIn == null || strength <= 0.01) return;
        Shape clip = g.getClip();
        g.clip(new RoundRectangle2D.Double(inRect.getX(), inRect.getY(), inRect.getWidth(), inRect.getHeight(), 14, 14));
        Path2D.Double path = new Path2D.Double();
        int n = 0;
        for (double[] t : tracersIn) {
            n++;
            double fade = Math.min(1, t[2] / 0.3) * Math.min(1, (t[3] - t[2]) / 0.5);
            if (fade <= 0.02 || Double.isNaN(t[4 + (TRAIL - 1) * 2])) continue;
            double x = t[0], z = t[1];
            double u = sec(sU, x, z), w = sec(sW, x, z);
            double cu = comp(u, uRef), cw = comp(w, wRef);
            String kind;
            Color c;
            if (cw > 0.30 && Math.abs(cw) > Math.abs(cu) * 0.8) { kind = "eyewall"; c = new Color(255, 170, 84); }       // rising
            else if (cw < -0.12 && Math.abs(cw) > Math.abs(cu) * 0.6) { kind = "eye"; c = new Color(255, 150, 190); }   // sinking
            else if (cu < 0) { kind = "inflow"; c = new Color(120, 220, 255); }                                          // toward the centre
            else { kind = "outflow"; c = new Color(244, 248, 255); }                                                     // away from it
            double k = emphasis.equals(kind) ? 1.25 : emphasis.isEmpty() ? 1 : 0.55;
            path.reset();
            double[] p0 = insideXY(x, z);
            path.moveTo(p0[0], p0[1]);
            double[] last = p0;
            for (int q = 1; q < TRAIL; q += 2) { last = insideXY(t[4 + q * 2], t[5 + q * 2]); path.lineTo(last[0], last[1]); }
            int a = (int) Model.clamp(215 * fade * strength * k, 0, 255);
            float sw = emphasis.equals(kind) ? 1.9f : 1.35f;
            if (strength >= 0.9) {                               // a dark edge so the streak also reads over white cloud (big picture only)
                g.setStroke(new BasicStroke(sw + 1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(4, 10, 22, a / 4));
                g.draw(path);
            }
            g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Renderer.alpha(c, a));
            g.draw(path);
            if (n % 3 == 0) {                                   // an arrowhead on some of them, pointing the way the air goes
                double[] p1 = insideXY(t[4], t[5]);
                double dx = p0[0] - p1[0], dy = p0[1] - p1[1], m = Math.hypot(dx, dy);
                if (m > 0.6) {
                    dx /= m; dy /= m;
                    Path2D.Double head = new Path2D.Double();
                    head.moveTo(p0[0] + dx * 4.5, p0[1] + dy * 4.5);
                    head.lineTo(p0[0] - dy * 3.2, p0[1] + dx * 3.2);
                    head.lineTo(p0[0] + dy * 3.2, p0[1] - dx * 3.2);
                    head.closePath();
                    g.fill(head);
                }
            }
        }
        g.setClip(clip);
    }

    /**
     * Four broad arrows that sum the circulation up, each pointing the way the frame's wind blows
     * at its place: surface inflow, eyewall updraft, outflow aloft and the sinking air of the eye.
     * Returns nothing; arrows whose wind is negligible are left out (a weak storm has no eye to sink in).
     */
    void paintInsideArrows(Graphics2D g, double strength, String emphasis) {
        if (f == null || strength <= 0.01) return;
        Shape clip = g.getClip();
        g.clip(inRect);
        double rmw = Math.max(4, f.rmw), top = Math.max(4, f.cloudTop), R = model.radiusKm;
        // the outflow arrow sits where the air leaves the centre fastest (found in the section), the others at fixed places
        double outR = Math.min(R * 0.6, rmw * 4 + 120), outZ = top * 0.86, outBest = 0;
        for (int c = 0; c < cols; c++) {
            double rc = (c + 0.5) * dr;
            if (rc < rmw * 2 + 30 || rc > R * 0.7) continue;
            for (int r = rows / 3; r < rows; r++) if (sU[r * cols + c] > outBest) { outBest = sU[r * cols + c]; outR = rc; outZ = (r + 0.5) * dz; }
        }
        // {kind, r km, z km}
        Object[][] spots = {{"inflow", Math.min(R * 0.55, rmw * 4 + 90), dz * 0.6}, {"eyewall", maxUp > 0 ? maxUpR : rmw + top * 0.4, maxUp > 0 ? maxUpZ : top * 0.42},
                {"outflow", outR, outZ}, {"eye", Math.max(1.5, f.eye * 0.3), top * 0.66}};
        for (Object[] s : spots) {
            String kind = (String) s[0];
            double r = (Double) s[1], z = (Double) s[2];
            if (kind.equals("eye") && (f.eye < 0.5 || f.eyeClarity < 0.05)) continue;       // no eye yet
            double u = sec(sU, r, z), w = sec(sW, r, z);
            // this arrow stands for the sinking air of the eye: while the air at the centre still rises (an eye that is only forming) there is none
            if (kind.equals("eye") && w >= 0) continue;
            double cu = comp(u, uRef), cw = comp(w, wRef);
            if (Math.hypot(cu, cw) < (kind.equals("eye") ? 0.10 : 0.16)) continue;
            // coloured by what the air does there, like the streaks: rising, sinking, toward the centre, away from it
            Color c = cw > 0.30 && Math.abs(cw) > Math.abs(cu) * 0.8 ? new Color(255, 170, 84) : cw < -0.12 && Math.abs(cw) > Math.abs(cu) * 0.6 ? new Color(255, 150, 190)
                    : cu < 0 ? new Color(120, 220, 255) : new Color(244, 248, 255);
            double k = emphasis.equals(kind) ? 1 : emphasis.isEmpty() ? 0.85 : 0.5;
            for (int side = -1; side <= 1; side += 2) {
                if (kind.equals("eye") && side > 0) continue;        // one arrow is enough in the narrow eye
                double x = kind.equals("eye") ? 0 : side * r;
                double[] p = insideXY(x, z);
                double dx = kind.equals("eye") ? 0 : cu * side, dy = -cw, m = Math.hypot(dx, dy);
                if (m < 1e-6) continue;
                // the picture is far wider (km) than it is tall, so compare the two in screen proportions
                double len = Math.min(Math.min(inRect.getWidth() * 0.10, inRect.getHeight() * 0.24), 30 + 46 * Math.min(1, m));
                dx /= m; dy /= m;
                float thick = (float) Model.clamp(inRect.getHeight() / 150.0, 0.6, 1);
                arrow(g, p[0] - dx * len / 2, p[1] - dy * len / 2, p[0] + dx * len / 2, p[1] + dy * len / 2, Renderer.alpha(c, (int) (245 * strength * k)),
                        (emphasis.equals(kind) ? 4.2f : 3.2f) * thick);
            }
        }
        g.setClip(clip);
    }

    /** A small title chip in the top-left corner of a picture. */
    static void caption(Graphics2D g, Rectangle2D rect, String title, String sub) {
        g.setFont(F_CAP);
        FontMetrics fm = g.getFontMetrics();
        g.setFont(F_SMALL);
        FontMetrics fs = g.getFontMetrics();
        int w = fm.stringWidth(title) + (sub.isEmpty() ? 0 : fs.stringWidth(sub) + 10) + 18;
        if (w > rect.getWidth() - 12) { sub = ""; w = fm.stringWidth(title) + 18; }
        double x = rect.getX() + 8, y = rect.getY() + 8;
        g.setColor(new Color(6, 10, 20, 170));
        g.fill(new RoundRectangle2D.Double(x, y, w, 20, 10, 10));
        g.setFont(F_CAP);
        g.setColor(Color.WHITE);
        g.drawString(title, (float) x + 9, (float) y + 14);
        if (!sub.isEmpty()) {
            g.setFont(F_SMALL);
            g.setColor(new Color(190, 204, 224));
            g.drawString(sub, (float) x + 9 + fm.stringWidth(title) + 10, (float) y + 14);
        }
    }

    /** The rounded outline of a picture ({@code strong}: the one the current chapter is about). */
    static void frame(Graphics2D g, Rectangle2D rect, boolean strong) {
        g.setStroke(new BasicStroke(strong ? 1.6f : 1f));
        g.setColor(strong ? new Color(255, 226, 140, 170) : new Color(120, 180, 255, 80));
        g.draw(new RoundRectangle2D.Double(rect.getX() - 0.5, rect.getY() - 0.5, rect.getWidth() + 1, rect.getHeight() + 1, 5, 5));
    }

    /**
     * A label with a leader line from {@code (ax, ay)}: a dot on the feature, a line, and a pill
     * with a title and an optional second line. The pill is kept inside {@code bounds}.
     */
    static Rectangle2D callout(Graphics2D g, double ax, double ay, double lx, double ly, String title, String sub, Rectangle2D bounds, double pulse) {
        return callout(g, ax, ay, lx, ly, title, sub, bounds, pulse, null);
    }

    /** As above; the pill is also kept off {@code avoid} (may be null). A feature hidden under {@code avoid} gets no label. */
    static Rectangle2D callout(Graphics2D g, double ax, double ay, double lx, double ly, String title, String sub, Rectangle2D bounds, double pulse, Rectangle2D avoid) {
        if (avoid != null && avoid.contains(ax, ay)) return null;
        g.setFont(F_BOLD);
        FontMetrics fm = g.getFontMetrics();
        g.setFont(F_SMALL);
        FontMetrics fs = g.getFontMetrics();
        boolean two = sub != null && !sub.isEmpty();
        double w = Math.max(fm.stringWidth(title), two ? fs.stringWidth(sub) : 0) + 18, h = two ? 36 : 21;
        double x = Model.clamp(lx - w / 2, bounds.getX() + 6, Math.max(bounds.getX() + 6, bounds.getMaxX() - w - 6));
        double y = Model.clamp(ly - h / 2, bounds.getY() + 32, Math.max(bounds.getY() + 32, bounds.getMaxY() - h - 22));
        if (avoid != null && avoid.intersects(x - 4, y - 4, w + 8, h + 8)) {
            // step aside: to the left of it or above it, whichever moves the label less
            double left = avoid.getX() - w - 10, up = avoid.getY() - h - 10;
            if (left >= bounds.getX() + 6 && Math.abs(left - x) <= Math.abs(up - y)) x = left;
            else if (up >= bounds.getY() + 32) y = up;
            else x = Math.max(bounds.getX() + 6, left);
        }
        // leader: from the dot to the nearest point of the pill
        double tx = Model.clamp(ax, x, x + w), ty = Model.clamp(ay, y, y + h);
        g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(4, 10, 22, 150));
        g.draw(new Line2D.Double(ax, ay, tx, ty));
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 226, 140, 235));
        g.draw(new Line2D.Double(ax, ay, tx, ty));
        double pr = 4.5 + 2.5 * pulse;
        g.setColor(new Color(255, 226, 140, 70));
        g.fill(new Ellipse2D.Double(ax - pr - 3, ay - pr - 3, (pr + 3) * 2, (pr + 3) * 2));
        g.setColor(new Color(255, 236, 170));
        g.fill(new Ellipse2D.Double(ax - 3.5, ay - 3.5, 7, 7));
        g.setColor(new Color(10, 14, 24, 228));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 12, 12));
        g.setColor(new Color(255, 226, 140, 200));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, 12, 12));
        g.setFont(F_BOLD);
        g.setColor(Color.WHITE);
        g.drawString(title, (float) x + 9, (float) y + 15);
        if (two) {
            g.setFont(F_SMALL);
            g.setColor(new Color(214, 224, 240));
            g.drawString(sub, (float) x + 9, (float) y + 29);
        }
        return new Rectangle2D.Double(x, y, w, h);
    }
}
