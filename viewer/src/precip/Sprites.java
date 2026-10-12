package precip;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Pre-rendered particle / decoration sprites, keyed by an int (kind, size, rotation bucket,
 * half-pixel phase...). Sprites are rendered once with the original vector drawing code at
 * the current device scale, cropped to their visible pixels and then just blitted.
 */
final class Sprites {
    /** A cropped sprite; (ox, oy) = offset of its top-left pixel from the integer anchor pixel. */
    static final class Sprite {
        final BufferedImage img;
        final int ox, oy;
        final int[] px;   // premultiplied ARGB pixels (w x h), for direct compositing
        final byte[] mask; // or: coverage only (w x h, 0..255), given its colour when it is blitted
        final int w, h;

        Sprite(BufferedImage img, int ox, int oy, int[] px) {
            this.img = img; this.ox = ox; this.oy = oy; this.px = px;
            this.mask = null;
            this.w = img == null ? 0 : img.getWidth();
            this.h = img == null ? 0 : img.getHeight();
        }

        /** A coverage mask of w x h bytes; (ox, oy) = offset of its top-left pixel from the anchor pixel. */
        Sprite(byte[] mask, int w, int h, int ox, int oy) {
            this.img = null; this.px = null; this.mask = mask; this.w = w; this.h = h; this.ox = ox; this.oy = oy;
        }
    }

    static final Sprite EMPTY = new Sprite(null, 0, 0, null);

    // open-addressing int -> Sprite map (no boxing on the per-particle hot path)
    private int[] keys = new int[1024];
    private Sprite[] vals = new Sprite[1024];
    private int size;
    double scale = -1; // device scale the sprites were rendered at

    Sprite get(int key) {
        int mask = keys.length - 1;
        int i = mix(key) & mask;
        while (true) {
            Sprite v = vals[i];
            if (v == null) return null;
            if (keys[i] == key) return v;
            i = (i + 1) & mask;
        }
    }

    void put(int key, Sprite s) {
        if (size * 2 >= keys.length) grow();
        int mask = keys.length - 1;
        int i = mix(key) & mask;
        while (vals[i] != null && keys[i] != key) i = (i + 1) & mask;
        if (vals[i] == null) size++;
        keys[i] = key;
        vals[i] = s;
    }

    void clear() {
        java.util.Arrays.fill(vals, null);
        size = 0;
    }

    int size() { return size; }

    private void grow() {
        int[] ok = keys;
        Sprite[] ov = vals;
        keys = new int[ok.length * 2];
        vals = new Sprite[ok.length * 2];
        size = 0;
        for (int k = 0; k < ok.length; k++) if (ov[k] != null) put(ok[k], ov[k]);
    }

    private static int mix(int h) {
        h *= 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    /** Crop an ARGB_PRE image to its non-transparent pixels; pad = anchor position inside img. */
    static Sprite crop(BufferedImage img, int padX, int padY) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if ((px[row + x] >>> 24) != 0) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
            }
        }
        if (x1 < 0) return EMPTY;
        int cw = x1 - x0 + 1, ch = y1 - y0 + 1;
        BufferedImage out = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB_PRE);
        int[] op = new int[cw * ch];
        for (int y = 0; y < ch; y++) System.arraycopy(px, (y0 + y) * w + x0, op, y * cw, cw);
        out.getRaster().setDataElements(0, 0, cw, ch, op); // keeps `out` a managed (cacheable) image
        return new Sprite(out, x0 - padX, y0 - padY, op);
    }
}
