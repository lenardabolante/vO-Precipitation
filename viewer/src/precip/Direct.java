package precip;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Direct SrcOver compositing into an opaque TYPE_INT_RGB render target, for the hot paths
 * (thousands of tiny particle sprites, cloud layers) where Java2D's per-call overhead dominates.
 * Results match Java2D's SrcOver of premultiplied ARGB onto RGB up to +-1 rounding.
 */
final class Direct {
    int[] px;
    int w, h;
    // device clip rectangle [x0, x1) x [y0, y1)
    int cx0, cy0, cx1, cy1;

    boolean attach(BufferedImage img) {
        // opaque targets only: INT_RGB, or INT_ARGB_PRE whose pixels are all alpha 255 (same int values)
        if (img == null || (img.getType() != BufferedImage.TYPE_INT_RGB && img.getType() != BufferedImage.TYPE_INT_ARGB_PRE)
                || !(img.getRaster().getDataBuffer() instanceof DataBufferInt db)) {
            px = null;
            return false;
        }
        px = db.getData();
        w = img.getWidth();
        h = img.getHeight();
        return true;
    }

    void clip(int x0, int y0, int x1, int y1) {
        cx0 = Math.max(0, x0); cy0 = Math.max(0, y0);
        cx1 = Math.min(w, x1); cy1 = Math.min(h, y1);
    }

    /** a*b/255 with exact rounding, for 0..255 inputs. */
    private static int mul8(int a, int b) {
        int t = a * b + 128;
        return (t + (t >> 8)) >> 8;
    }

    /** SrcOver a premultiplied ARGB image (sw x sh, row stride sw) with its top-left at (x, y). */
    void over(int[] src, int sw, int sh, int x, int y) {
        int x0 = Math.max(cx0, x), y0 = Math.max(cy0, y), x1 = Math.min(cx1, x + sw), y1 = Math.min(cy1, y + sh);
        if (x0 >= x1 || y0 >= y1) return;
        int[] d = px;
        for (int yy = y0; yy < y1; yy++) {
            int si = (yy - y) * sw + (x0 - x), di = yy * w + x0;
            for (int xx = x0; xx < x1; xx++, si++, di++) {
                int s = src[si];
                int a = s >>> 24;
                if (a == 0) continue;
                if (a == 255) { d[di] = s; continue; }
                int t = d[di], ia = 255 - a;
                int r = ((s >> 16) & 255) + mul8((t >> 16) & 255, ia);
                int g = ((s >> 8) & 255) + mul8((t >> 8) & 255, ia);
                int b = (s & 255) + mul8(t & 255, ia);
                d[di] = 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
            }
        }
    }

    /** SrcOver a coverage mask (0..255 per pixel, row stride sw) in one colour; {@code a256} = its opacity 0..256. */
    void overMask(byte[] src, int sw, int sh, int x, int y, int rgb, int a256) {
        int x0 = Math.max(cx0, x), y0 = Math.max(cy0, y), x1 = Math.min(cx1, x + sw), y1 = Math.min(cy1, y + sh);
        if (x0 >= x1 || y0 >= y1) return;
        int[] d = px;
        final int sr = (rgb >> 16) & 255, sg = (rgb >> 8) & 255, sb = rgb & 255;
        for (int yy = y0; yy < y1; yy++) {
            int si = (yy - y) * sw + (x0 - x), di = yy * w + x0;
            for (int xx = x0; xx < x1; xx++, si++, di++) {
                int m = src[si] & 255;
                if (m == 0) continue;
                int a = (m * a256) >> 8;
                if (a == 0) continue;
                int t = d[di], ia = 255 - a;
                int r = mul8(sr, a) + mul8((t >> 16) & 255, ia);
                int g = mul8(sg, a) + mul8((t >> 8) & 255, ia);
                int b = mul8(sb, a) + mul8(t & 255, ia);
                d[di] = 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
            }
        }
    }

    /** {@link #over} with the whole image faded: {@code a256} = its opacity 0..256. */
    void overAlpha(int[] src, int sw, int sh, int x, int y, int a256) {
        if (a256 >= 256) { over(src, sw, sh, x, y); return; }
        if (a256 <= 0) return;
        int x0 = Math.max(cx0, x), y0 = Math.max(cy0, y), x1 = Math.min(cx1, x + sw), y1 = Math.min(cy1, y + sh);
        if (x0 >= x1 || y0 >= y1) return;
        int[] d = px;
        for (int yy = y0; yy < y1; yy++) {
            int si = (yy - y) * sw + (x0 - x), di = yy * w + x0;
            for (int xx = x0; xx < x1; xx++, si++, di++) {
                int s = src[si];
                int a = ((s >>> 24) * a256) >> 8;
                if (a == 0) continue;
                int t = d[di], ia = 255 - a;
                int r = ((((s >> 16) & 255) * a256) >> 8) + mul8((t >> 16) & 255, ia);
                int g = ((((s >> 8) & 255) * a256) >> 8) + mul8((t >> 8) & 255, ia);
                int b = (((s & 255) * a256) >> 8) + mul8(t & 255, ia);
                d[di] = 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
            }
        }
    }

    /**
     * Cross-fade two premultiplied ARGB images (either may be null) and SrcOver the blend:
     * {@code src = a * (1 - f) + b * f}. Unlike drawing the two with partial alpha one after the
     * other, a pixel that is opaque in both stays fully opaque for the whole fade.
     * @param f256 blend factor 0..256
     */
    void overFade(int[] a, int aw, int ah, int ax, int ay, int[] b, int bw, int bh, int bx, int by, int f256) {
        if (a == null && b == null) return;
        if (f256 >= 256) { if (b != null) over(b, bw, bh, bx, by); return; }
        if (f256 <= 0) { if (a != null) over(a, aw, ah, ax, ay); return; }
        int ux0, uy0, ux1, uy1;
        if (a != null) { ux0 = ax; uy0 = ay; ux1 = ax + aw; uy1 = ay + ah; }
        else { ux0 = bx; uy0 = by; ux1 = bx + bw; uy1 = by + bh; }
        if (a != null && b != null) { ux0 = Math.min(ux0, bx); uy0 = Math.min(uy0, by); ux1 = Math.max(ux1, bx + bw); uy1 = Math.max(uy1, by + bh); }
        int x0 = Math.max(cx0, ux0), y0 = Math.max(cy0, uy0), x1 = Math.min(cx1, ux1), y1 = Math.min(cy1, uy1);
        if (x0 >= x1 || y0 >= y1) return;
        int[] d = px;
        int fa = 256 - f256;
        for (int yy = y0; yy < y1; yy++) {
            boolean ra = a != null && yy >= ay && yy < ay + ah, rb = b != null && yy >= by && yy < by + bh;
            if (!ra && !rb) continue;
            int di = yy * w + x0;
            int ai = a != null ? (yy - ay) * aw - ax : 0, bi = b != null ? (yy - by) * bw - bx : 0;
            int axl = ra ? ax : Integer.MAX_VALUE, axr = ra ? ax + aw : Integer.MIN_VALUE;
            int bxl = rb ? bx : Integer.MAX_VALUE, bxr = rb ? bx + bw : Integer.MIN_VALUE;
            for (int xx = x0; xx < x1; xx++, di++) {
                int sa = xx >= axl && xx < axr ? a[ai + xx] : 0;
                int sb = xx >= bxl && xx < bxr ? b[bi + xx] : 0;
                if ((sa | sb) == 0) continue;
                int s;
                if (sa == sb) s = sa;
                else {
                    int al = ((sa >>> 24) * fa + (sb >>> 24) * f256 + 128) >> 8;
                    int r = (((sa >> 16) & 255) * fa + ((sb >> 16) & 255) * f256 + 128) >> 8;
                    int g = (((sa >> 8) & 255) * fa + ((sb >> 8) & 255) * f256 + 128) >> 8;
                    int bl = ((sa & 255) * fa + (sb & 255) * f256 + 128) >> 8;
                    s = (al << 24) | (r << 16) | (g << 8) | bl;
                }
                int al = s >>> 24;
                if (al == 0) continue;
                if (al >= 255) { d[di] = s; continue; }
                int t = d[di], ia = 255 - al;
                int r = ((s >> 16) & 255) + mul8((t >> 16) & 255, ia);
                int g = ((s >> 8) & 255) + mul8((t >> 8) & 255, ia);
                int bl = (s & 255) + mul8(t & 255, ia);
                d[di] = 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, bl);
            }
        }
    }

    /**
     * Blit a w x h window of a premultiplied ARGB image (row stride {@code stride}, window origin
     * (sx, sy)) to (dx, dy). Opaque images are copied row by row.
     */
    void blit(int[] src, int stride, int sx, int sy, int bw, int bh, int dx, int dy, boolean opaque) { blit(src, stride, sx, sy, bw, bh, dx, dy, opaque, null); }

    /** As above; {@code rows[y]} (window rows) may say 0 = fully transparent, 2 = fully opaque, 1 = mixed. */
    void blit(int[] src, int stride, int sx, int sy, int bw, int bh, int dx, int dy, boolean opaque, byte[] rows) {
        int x0 = Math.max(cx0, dx), y0 = Math.max(cy0, dy), x1 = Math.min(cx1, dx + bw), y1 = Math.min(cy1, dy + bh);
        if (x0 >= x1 || y0 >= y1) return;
        int[] d = px;
        for (int yy = y0; yy < y1; yy++) {
            int si = (yy - dy + sy) * stride + (x0 - dx + sx), di = yy * w + x0;
            if (rows != null) {
                byte rk = rows[yy - dy];
                if (rk == 0) continue;
                if (rk == 2) { System.arraycopy(src, si, d, di, x1 - x0); continue; }
            }
            if (opaque) { System.arraycopy(src, si, d, di, x1 - x0); continue; }
            for (int xx = x0; xx < x1; xx++, si++, di++) {
                int s = src[si];
                int a = s >>> 24;
                if (a == 0) continue;
                if (a == 255) { d[di] = s; continue; }
                int t = d[di], ia = 255 - a;
                int r = ((s >> 16) & 255) + mul8((t >> 16) & 255, ia);
                int g = ((s >> 8) & 255) + mul8((t >> 8) & 255, ia);
                int b = (s & 255) + mul8(t & 255, ia);
                d[di] = 0xFF000000 | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
            }
        }
    }

    // ---- the lightning light (Lightning.Band): a coarse grid of light values, bilinear per pixel.
    // Written as a handful of very plain loops over arrays indexed by the same x: the JIT turns each into SIMD code,
    // which one loop doing everything at once (or one indexing the target as row + x) did not get (4 ms against 1 ms a frame).
    private int[] lh0 = new int[0], lh1 = new int[0], lm = new int[0], la = new int[0], lt = new int[0];

    /** Scratch row h (0 or 1) for {@link #lightSpread}; the two are swapped by the caller as it walks down the grid rows. */
    int[] lightScratch(int h) {
        if (lh0.length < w) { lh0 = new int[w]; lh1 = new int[w]; lm = new int[w]; la = new int[w]; lt = new int[w]; }
        return h == 0 ? lh0 : lh1;
    }

    /**
     * One grid row spread over the device columns [xa, xb): nodes are S pixels apart starting at device x = ox, node i holds
     * {@code k[off + i]} (0..4080); {@code h[x]} becomes S times the value interpolated at x.
     */
    void lightSpread(int[] k, int off, int ox, int S, int xa, int xb, int[] h) {
        xa = Math.max(xa, cx0); xb = Math.min(xb, cx1);
        for (int i = (xa - ox) / S, xs = ox + i * S; xs < xb; i++, xs += S) {
            int c0 = k[off + i], dv = k[off + i + 1] - c0, x0 = Math.max(xa, xs), x1 = Math.min(xb, xs + S);
            for (int x = x0, v = c0 * S + dv * (x0 - xs); x < x1; x++, v += dv) h[x] = v;
        }
    }

    /**
     * Light device row y, columns [xa, xb): the light kk (0..255) of a pixel is {@code (hA * gy + hB * fy) * M / 65536} from
     * the two spread grid rows around it. It multiplies the colour by {@code 1 + gm * kk / 65536} and adds
     * {@code hz * kk / 256} of a cold white (blue in full, red and green 7/8 of it). kk = 0 leaves a pixel exactly as it is.
     */
    void lightRow(int y, int xa, int xb, int[] hA, int[] hB, int gy, int fy, int M, int gm, int hz) { lightRow(y, xa, xb, hA, hB, gy, fy, M, gm, hz, false); }

    /** @param neutral Settings > Advanced "No tint": the added light is a plain white (red and green in full, like the blue) */
    void lightRow(int y, int xa, int xb, int[] hA, int[] hB, int gy, int fy, int M, int gm, int hz, boolean neutral) {
        if (y < cy0 || y >= cy1) return;
        xa = Math.max(xa, cx0); xb = Math.min(xb, cx1);
        if (xa >= xb) return;
        final int[] lm = this.lm, la = this.la, t0 = lt;
        for (int x = xa; x < xb; x++) lm[x] = ((hA[x] * gy + hB[x] * fy) * M) >> 16;
        for (int x = xa; x < xb; x++) la[x] = (hz * lm[x]) >> 8;
        for (int x = xa; x < xb; x++) lm[x] = 256 + ((gm * lm[x]) >> 8);
        System.arraycopy(px, y * w + xa, t0, xa, xb - xa);
        for (int x = xa; x < xb; x++) {
            int t = t0[x], m = lm[x], a = la[x], a8 = neutral ? a : a - (a >> 3);
            int b = Math.min(255, (((t & 255) * m) >> 8) + a);
            int g = Math.min(255, ((((t >> 8) & 255) * m) >> 8) + a8);
            int r = Math.min(255, ((((t >> 16) & 255) * m) >> 8) + a8);
            t0[x] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        System.arraycopy(t0, xa, px, y * w + xa, xb - xa);
    }

    /** Fill device row y, columns [x0, x1) (clipped) with a 2-pixel dither pattern of two opaque colours. */
    void fillRow(int y, int x0, int x1, int rgbA, int rgbB) {
        if (y < cy0 || y >= cy1) return;
        x0 = Math.max(cx0, x0); x1 = Math.min(cx1, x1);
        if (x0 >= x1) return;
        int[] d = px;
        int o = y * w;
        if (rgbA == rgbB) { java.util.Arrays.fill(d, o + x0, o + x1, rgbA); return; }
        boolean odd = ((x0 + y) & 1) != 0;
        for (int x = x0; x < x1; x++, odd = !odd) d[o + x] = odd ? rgbB : rgbA;
    }
}
