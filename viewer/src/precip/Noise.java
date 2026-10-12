package precip;

/** Small, fast, seedable procedural noise (float maths, no tables to build, thread-safe). */
final class Noise {
    private Noise() {}

    private static final float[] GX = new float[32], GY = new float[32];

    static {
        for (int k = 0; k < 32; k++) {
            GX[k] = (float) Math.cos(k * Math.PI / 16 + 0.1);
            GY[k] = (float) Math.sin(k * Math.PI / 16 + 0.1);
        }
    }

    static int hash(int x, int y, int s) {
        int h = x * 0x27d4eb2d + y * 0x165667b1 + s * 0x9E3779B9;
        h ^= h >>> 15;
        h *= 0x2c1b3c6d;
        h ^= h >>> 12;
        h *= 0x297a2d39;
        h ^= h >>> 15;
        return h;
    }

    static int hash(int x, int y, int z, int s) { return hash(x + z * 0x632be5ab, y - z * 0x1b873593, s ^ (z * 0x85ebca6b)); }

    /** 0..1 from a hash. */
    static float h01(int h) { return (h >>> 8) * (1f / 16777216f); }

    private static int fastFloor(float v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    /** Gradient noise, roughly -1..1. */
    static float perlin2(float x, float y, int seed) {
        int ix = fastFloor(x), iy = fastFloor(y);
        float fx = x - ix, fy = y - iy;
        float u = fx * fx * fx * (fx * (fx * 6 - 15) + 10), v = fy * fy * fy * (fy * (fy * 6 - 15) + 10);
        int h00 = hash(ix, iy, seed) & 31, h10 = hash(ix + 1, iy, seed) & 31, h01 = hash(ix, iy + 1, seed) & 31, h11 = hash(ix + 1, iy + 1, seed) & 31;
        float n00 = GX[h00] * fx + GY[h00] * fy;
        float n10 = GX[h10] * (fx - 1) + GY[h10] * fy;
        float n01 = GX[h01] * fx + GY[h01] * (fy - 1);
        float n11 = GX[h11] * (fx - 1) + GY[h11] * (fy - 1);
        float a = n00 + (n10 - n00) * u, b = n01 + (n11 - n01) * u;
        return (a + (b - a) * v) * 1.5f;
    }

    private static float grad3(int h, float x, float y, float z) {
        h &= 15;
        float u = h < 8 ? x : y, v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    /** 3D gradient noise, roughly -1..1 (z is usually time). */
    static float perlin3(float x, float y, float z, int seed) {
        int ix = fastFloor(x), iy = fastFloor(y), iz = fastFloor(z);
        float fx = x - ix, fy = y - iy, fz = z - iz;
        float u = fx * fx * fx * (fx * (fx * 6 - 15) + 10), v = fy * fy * fy * (fy * (fy * 6 - 15) + 10), w = fz * fz * (3 - 2 * fz);
        float n000 = grad3(hash(ix, iy, iz, seed), fx, fy, fz), n100 = grad3(hash(ix + 1, iy, iz, seed), fx - 1, fy, fz);
        float n010 = grad3(hash(ix, iy + 1, iz, seed), fx, fy - 1, fz), n110 = grad3(hash(ix + 1, iy + 1, iz, seed), fx - 1, fy - 1, fz);
        float n001 = grad3(hash(ix, iy, iz + 1, seed), fx, fy, fz - 1), n101 = grad3(hash(ix + 1, iy, iz + 1, seed), fx - 1, fy, fz - 1);
        float n011 = grad3(hash(ix, iy + 1, iz + 1, seed), fx, fy - 1, fz - 1), n111 = grad3(hash(ix + 1, iy + 1, iz + 1, seed), fx - 1, fy - 1, fz - 1);
        float a = n000 + (n100 - n000) * u, b = n010 + (n110 - n010) * u, c = n001 + (n101 - n001) * u, d = n011 + (n111 - n011) * u;
        float e = a + (b - a) * v, f = c + (d - c) * v;
        return (e + (f - e) * w) * 0.95f;
    }

    /** Fractal gradient noise, -1..1. */
    static float fbm2(float x, float y, int seed, int oct) {
        float sum = 0, amp = 0.5f, norm = 0;
        for (int k = 0; k < oct; k++) {
            sum += amp * perlin2(x, y, seed + k * 131);
            norm += amp;
            x = x * 2.03f + 17.1f;
            y = y * 2.03f - 9.7f;
            amp *= 0.5f;
        }
        return sum / norm;
    }

    /** Cellular (Worley) F1 distance, 0 at a feature point, about 1 at the farthest. */
    static float worley2(float x, float y, int seed) {
        int ix = fastFloor(x), iy = fastFloor(y);
        float best = 9f;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int h = hash(ix + dx, iy + dy, seed);
                float px = ix + dx + (h & 0xffff) * (1f / 65536f), py = iy + dy + (h >>> 16) * (1f / 65536f);
                float ex = px - x, ey = py - y;
                float d = ex * ex + ey * ey;
                if (d < best) best = d;
            }
        }
        return (float) Math.sqrt(best);
    }

    /** Billowy fractal: round bumps at several scales, 0..1 (1 = centre of a bump). */
    static float billow2(float x, float y, int seed, int oct) {
        float sum = 0, amp = 0.55f, norm = 0;
        for (int k = 0; k < oct; k++) {
            float d = worley2(x, y, seed + k * 977) * 1.12f;
            float f = d >= 1 ? 0 : 1f - d * d;   // rounded domes with creases between them
            sum += amp * f;
            norm += amp;
            x = x * 2.13f + 5.3f;
            y = y * 2.13f + 11.9f;
            amp *= 0.5f;
        }
        return sum / norm;
    }
}
