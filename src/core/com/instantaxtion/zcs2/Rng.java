package com.instantaxtion.zcs2;

/**
 * Deterministic randomness. Two kinds:
 * - a sequential stream (next...) for things done in a fixed order on the simulation thread;
 * - counter-based hashes (hash/unit) that depend only on their inputs, so work split across threads gives
 *   the same answer whatever order the threads run in.
 * No wall-clock time ever reaches the simulation.
 */
public final class Rng {
    private long s;

    public Rng(long seed) {
        s = seed ^ 0x9E3779B97F4A7C15L;
    }

    public long state() {
        return s;
    }

    public void setState(long v) {
        s = v;
    }

    public long nextLong() {
        long z = (s += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public int nextInt(int n) {
        if (n <= 1) return 0;
        return (int) ((nextLong() >>> 33) % n);
    }

    public int range(int lo, int hi) {
        return lo + nextInt(hi - lo + 1);
    }

    public float nextFloat() {
        return (nextLong() >>> 40) / (float) (1L << 24);
    }

    public float range(float lo, float hi) {
        return lo + (hi - lo) * nextFloat();
    }

    public boolean chance(float p) {
        return nextFloat() < p;
    }

    public float gauss() {
        float a = nextFloat() + nextFloat() + nextFloat() + nextFloat();
        return (a - 2f) * 1.7320508f;
    }

    // ---- counter-based ----

    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static long hash(long seed, long a, long b) {
        return mix(seed * 0x9E3779B97F4A7C15L + mix(a * 0xC2B2AE3D27D4EB4FL + b));
    }

    /** A number in [0,1) from the inputs alone. */
    public static float unit(long seed, long a, long b) {
        return (hash(seed, a, b) >>> 40) / (float) (1L << 24);
    }

    public static int hashInt(long seed, long a, long b, int n) {
        if (n <= 1) return 0;
        return (int) ((hash(seed, a, b) >>> 33) % n);
    }
}
