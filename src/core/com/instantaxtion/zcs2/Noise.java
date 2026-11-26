package com.instantaxtion.zcs2;

/** Smooth value noise for terrain. Deterministic from the seed. */
public final class Noise {
    private final long seed;

    public Noise(long seed) {
        this.seed = seed;
    }

    float lattice(int x, int y) {
        return Rng.unit(seed, x, y) * 2f - 1f;
    }

    static float fade(float t) {
        return t * t * (3 - 2 * t);
    }

    public float value(float x, float y) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        float fx = fade(x - x0), fy = fade(y - y0);
        float a = lattice(x0, y0), b = lattice(x0 + 1, y0), c = lattice(x0, y0 + 1), d = lattice(x0 + 1, y0 + 1);
        float top = a + (b - a) * fx, bot = c + (d - c) * fx;
        return top + (bot - top) * fy;
    }

    /** Fractal noise, roughly in [-1, 1]. */
    public float fbm(float x, float y, int octaves) {
        float sum = 0, amp = 1, norm = 0, f = 1;
        for (int i = 0; i < octaves; i++) {
            sum += value(x * f + i * 17.3f, y * f - i * 9.1f) * amp;
            norm += amp;
            amp *= 0.5f;
            f *= 2.03f;
        }
        return sum / norm;
    }
}
