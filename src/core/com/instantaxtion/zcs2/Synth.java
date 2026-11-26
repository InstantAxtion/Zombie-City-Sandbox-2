package com.instantaxtion.zcs2;

import java.util.HashMap;
import java.util.Map;

/**
 * Every sound in the game, made by code (no audio files): gunfire near and far, explosions, voices, the dead,
 * sirens, engines, rotors, weather, the country's ambience beds, and the score's layered stems. Everything is
 * rendered once (on a background thread on the phone) into fixed buffers; the mixer only reads them.
 *
 * Effects are 22,050 samples a second, mono. Some low, dark beds and the music stems are kept at half that rate and
 * played back with interpolation (see {@link #step}). Loops join seamlessly: tonal ones have a whole number of cycles,
 * events that run over the end wrap round to the start, and noise beds are cross-faded into themselves.
 */
public final class Synth {
    public static final int RATE = 22050;
    /** The music stems' sample rate. */
    public static final int MUSIC_RATE = RATE / 2;

    // one-shots
    public static final int PISTOL = 0, RIFLE = 1, MG = 2, SHOTGUN = 3, SNIPER = 4, CANNON = 5, BOOM = 6, BIGBOOM = 7,
            PISTOL_FAR = 8, RIFLE_FAR = 9, MG_FAR = 10, SHOTGUN_FAR = 11, SNIPER_FAR = 12, CANNON_FAR = 13,
            BOOM_FAR = 14, BIGBOOM_FAR = 15, SCREAM = 16, SCREAM2 = 17, HURT = 18, BITE = 19, GROAN = 20, GROAN2 = 21,
            GROAN3 = 22, MOAN = 23, JET = 24, NEWS = 25, CLICK = 26, WHISTLE = 27, THUNDER = 28, THUNDER2 = 29,
            RADIO = 30, RADIO2 = 31, CARALARM = 32, HORN = 33;
    // loops
    public static final int L_SIREN_POLICE = 34, L_SIREN_FIRE = 35, L_SIREN_AMB = 36, L_ENGINE = 37, L_TRUCK = 38,
            L_TRACKS = 39, L_ROTOR = 40, L_AIRCRAFT = 41, L_CITY = 42, L_WIND = 43, L_BIRDS = 44, L_CRICKETS = 45,
            L_RAIN = 46, L_STORM = 47, L_FIRE = 48, L_CROWD = 49, L_HORDE = 50, L_BATTLE = 51;
    public static final int COUNT = 52;
    public static final String[] NAMES = {"pistol", "rifle", "mg", "shotgun", "sniper", "cannon", "boom", "bigboom",
            "pistol_far", "rifle_far", "mg_far", "shotgun_far", "sniper_far", "cannon_far", "boom_far", "bigboom_far",
            "scream", "scream2", "hurt", "bite", "groan", "groan2", "groan3", "moan", "jet", "news", "click", "whistle",
            "thunder", "thunder2", "radio", "radio2", "caralarm", "horn", "siren_police", "siren_fire", "siren_ambulance",
            "engine", "truck", "tracks", "rotor", "aircraft", "city", "wind", "birds", "crickets", "rain", "storm", "fire",
            "crowd", "horde", "battle"};

    // the score: stems that share one tempo and one chord grid, so any mix of them is in key and in time
    public static final int ST_CALM = 0, ST_DARK = 1, ST_PULSE = 2, ST_DRUMS = 3, ST_STRINGS = 4, ST_AFTER = 5,
            ST_MARCH = 6, STEMS = 7;
    public static final String[] STEM_NAMES = {"calm pad", "dark strings", "pulse", "drums", "high strings",
            "aftermath piano", "march"};
    /** Seconds in a loop of the score: 8 bars of 4/4 at 80 beats a minute. */
    public static final float MUSIC_LEN = 24f;

    private static final Map<String, Integer> IDS = new HashMap<String, Integer>();

    static {
        for (int i = 0; i < COUNT; i++) IDS.put(NAMES[i], i);
    }

    private final short[][] bufs = new short[COUNT][];
    private final short[][] stems = new short[STEMS][];

    /** A sound's id from its name (the names the simulation uses), or -1. Doesn't allocate. */
    public static int id(String name) {
        if (name == null) return -1;
        Integer i = IDS.get(name);
        return i == null ? -1 : i;
    }

    public static boolean isLoop(int id) {
        return id >= L_SIREN_POLICE;
    }

    /** Is this one kept at half rate (low, dark sounds)? */
    public static boolean halfRate(int id) {
        return id == L_ENGINE || id == L_TRUCK || id == L_AIRCRAFT || id == L_CITY || id == L_WIND || id == L_HORDE
                || id == L_BATTLE || id == BOOM_FAR || id == BIGBOOM_FAR || id == THUNDER;
    }

    /** Samples of the buffer per output sample at normal pitch. */
    public static float step(int id) {
        return halfRate(id) ? 0.5f : 1f;
    }

    /** The far version of a gun or blast, or the id itself. */
    public static int far(int id) {
        return id >= PISTOL && id <= BIGBOOM ? id + 8 : id;
    }

    /** The buffer if it has been made, else null (never makes it: safe on the game's thread). */
    public synchronized short[] peek(int id) {
        return id < 0 || id >= COUNT ? null : bufs[id];
    }

    public synchronized short[] stem(int k) {
        return stems[k];
    }

    /** The buffer, made now if it hasn't been (for tools and tests). */
    public short[] get(int id) {
        if (id < 0 || id >= COUNT) return null;
        short[] s = peek(id);
        if (s != null) return s;
        s = make(id);
        synchronized (this) {
            if (bufs[id] == null) bufs[id] = s;
            return bufs[id];
        }
    }

    public short[] get(String name) {
        return get(id(name));
    }

    public short[] makeStem(int k) {
        short[] s = stem(k);
        if (s != null) return s;
        s = renderStem(k);
        synchronized (this) {
            if (stems[k] == null) stems[k] = s;
            return stems[k];
        }
    }

    /** The order things are made in at start: what's heard first, first. */
    private static final int[] ORDER = {CLICK, PISTOL, RIFLE, MG, SHOTGUN, BOOM, SCREAM, HURT, GROAN, NEWS, L_CITY,
            L_WIND, SNIPER, CANNON, BIGBOOM, L_SIREN_POLICE, L_ENGINE, GROAN2, GROAN3, MOAN, BITE, RADIO, L_BIRDS,
            L_CRICKETS, L_RAIN, PISTOL_FAR, RIFLE_FAR, MG_FAR, SHOTGUN_FAR, BOOM_FAR, L_BATTLE, L_HORDE, L_TRUCK,
            L_SIREN_FIRE, L_ROTOR, JET, SCREAM2, L_FIRE, L_CROWD, L_STORM, THUNDER, THUNDER2, WHISTLE, L_TRACKS,
            L_AIRCRAFT, L_SIREN_AMB, CARALARM, HORN, RADIO2, SNIPER_FAR, CANNON_FAR, BIGBOOM_FAR};

    /** Makes everything (call off the game's thread). The score's first stems come early so music starts soon. */
    public void prerenderAll() {
        for (int k = 0; k < ORDER.length; k++) {
            get(ORDER[k]);
            if (k == 12) {
                makeStem(ST_CALM);
                makeStem(ST_DARK);
            }
        }
        for (int i = 0; i < COUNT; i++) get(i);
        for (int k = 0; k < STEMS; k++) makeStem(k);
    }

    // ------------------------------------------------------------------ the sounds

    private short[] make(int id) {
        Rng r = new Rng(9100 + id * 7919L);
        switch (id) {
            case PISTOL: return out(gun(r, 0.55f, 2600, 170, 0.8f, 0.35f), 0.85f);
            case RIFLE: return out(gun(r, 0.6f, 3400, 140, 0.7f, 0.45f), 0.85f);
            case MG: return out(gun(r, 0.42f, 3000, 125, 0.75f, 0.3f), 0.8f);
            case SHOTGUN: return out(gun(r, 0.75f, 1700, 95, 1.2f, 0.55f), 0.9f);
            case SNIPER: return out(gun(r, 1.1f, 4000, 150, 0.9f, 0.7f), 0.9f);
            case CANNON: return out(cannon(r), 0.95f);
            case BOOM: return out(boom(r, 2.2f, 52, 1f), 0.95f);
            case BIGBOOM: return out(boom(r, 3.8f, 38, 1.6f), 0.97f);
            case PISTOL_FAR: return out(distant(gun(r, 0.55f, 2600, 170, 0.8f, 0.35f), 1100, 0.8f), 0.7f);
            case RIFLE_FAR: return out(distant(gun(r, 0.6f, 3400, 140, 0.7f, 0.45f), 1100, 0.8f), 0.7f);
            case MG_FAR: return out(distant(gun(r, 0.42f, 3000, 125, 0.75f, 0.3f), 1000, 0.7f), 0.7f);
            case SHOTGUN_FAR: return out(distant(gun(r, 0.75f, 1700, 95, 1.2f, 0.55f), 900, 0.8f), 0.75f);
            case SNIPER_FAR: return out(distant(gun(r, 1.1f, 4000, 150, 0.9f, 0.7f), 1200, 1f), 0.75f);
            case CANNON_FAR: return out(distant(cannon(r), 500, 1.2f), 0.85f);
            case BOOM_FAR: return out(half(distant(boom(r, 2.2f, 52, 1f), 520, 1.4f)), 0.85f);
            case BIGBOOM_FAR: return out(half(distant(boom(r, 3.8f, 38, 1.6f), 420, 1.6f)), 0.9f);
            case SCREAM: return out(scream(r, 0.85f, 560, 780, 520, true), 0.8f);
            case SCREAM2: return out(scream(r, 0.75f, 290, 400, 260, false), 0.8f);
            case HURT: return out(grunt(r), 0.7f);
            case BITE: return out(bite(r), 0.8f);
            case GROAN: return out(groan(r, 1.5f, 84, 74, 0), 0.75f);
            case GROAN2: return out(groan(r, 1.9f, 96, 120, 1), 0.75f);
            case GROAN3: return out(groan(r, 0.8f, 70, 62, 2), 0.75f);
            case MOAN: return out(groan(r, 2.6f, 78, 92, 3), 0.75f);
            case JET: return out(jet(r), 0.85f);
            case NEWS: return out(chime(), 0.55f);
            case CLICK: return out(click(), 0.5f);
            case WHISTLE: return out(whistle(r), 0.8f);
            case THUNDER: return out(half(thunder(r, false)), 0.9f);
            case THUNDER2: return out(thunder(r, true), 0.92f);
            case RADIO: return out(radio(r, 1.1f), 0.6f);
            case RADIO2: return out(radio(r, 1.6f), 0.6f);
            case CARALARM: return out(carAlarm(r), 0.75f);
            case HORN: return out(horn(r), 0.8f);
            case L_SIREN_POLICE: return loopOut(policeSiren(r), 0.2f);
            case L_SIREN_FIRE: return loopOut(fireSiren(r), 0.2f);
            case L_SIREN_AMB: return loopOut(ambulance(r), 0.2f);
            case L_ENGINE: return loopOut(half(engine(r, 2f, 37, false)), 0.2f);
            case L_TRUCK: return loopOut(half(engine(r, 2f, 25, true)), 0.2f);
            case L_TRACKS: return loopOut(tracks(r), 0.2f);
            case L_ROTOR: return loopOut(rotor(r), 0.22f);
            case L_AIRCRAFT: return loopOut(half(aircraft(r)), 0.2f);
            case L_CITY: return loopOut(half(city(r)), 0.2f);
            case L_WIND: return loopOut(half(wind(r, 8, 1f)), 0.2f);
            case L_BIRDS: return loopOut(birds(r), 0.14f);
            case L_CRICKETS: return loopOut(crickets(r), 0.13f);
            case L_RAIN: return loopOut(rain(r, 6, false), 0.2f);
            case L_STORM: return loopOut(storm(r), 0.22f);
            case L_FIRE: return loopOut(fire(r), 0.2f);
            case L_CROWD: return loopOut(crowd(r), 0.2f);
            case L_HORDE: return loopOut(half(horde(r)), 0.22f);
            case L_BATTLE: return loopOut(half(battle(r)), 0.2f);
            default: return new short[2];
        }
    }

    // ------------------------------------------------------------------ building blocks

    private static final float TAU = 6.2831853f;
    private static final int SIN_N = 4096;
    private static final float[] SIN = new float[SIN_N + 1];

    static {
        for (int i = 0; i <= SIN_N; i++) SIN[i] = (float) Math.sin(i * 2 * Math.PI / SIN_N);
    }

    /** sin(2 pi phase), phase in cycles, from a table. */
    static float sn(float phase) {
        float p = phase - (float) Math.floor(phase);
        float f = p * SIN_N;
        int i = (int) f;
        return SIN[i] + (SIN[i + 1] - SIN[i]) * (f - i);
    }

    /** A sawtooth with the worst of its aliasing taken off (polyBLEP). dt = frequency / rate. */
    static float saw(float ph, float dt) {
        float v = 2 * ph - 1;
        if (ph < dt) {
            float t = ph / dt;
            v -= t + t - t * t - 1;
        } else if (ph > 1 - dt) {
            float t = (ph - 1) / dt;
            v -= t * t + t + t + 1;
        }
        return v;
    }

    /** A state-variable filter (the trapezoidal kind: stable at any setting). */
    static final class Svf {
        float ic1, ic2, a1, a2, a3, k, v1, v2;

        Svf set(float hz, float q, float rate) {
            if (hz > rate * 0.45f) hz = rate * 0.45f;
            if (hz < 5) hz = 5;
            float g = (float) Math.tan(Math.PI * hz / rate);
            k = 1 / q;
            a1 = 1 / (1 + g * (g + k));
            a2 = g * a1;
            a3 = g * a2;
            return this;
        }

        void tick(float x) {
            float v3 = x - ic2;
            v1 = a1 * ic1 + a2 * v3;
            v2 = ic2 + a2 * ic1 + a3 * v3;
            ic1 = 2 * v1 - ic1;
            ic2 = 2 * v2 - ic2;
        }

        float lp(float x) {
            tick(x);
            return v2;
        }

        float bp(float x) {
            tick(x);
            return v1;
        }

        float hp(float x) {
            tick(x);
            return x - k * v1 - v2;
        }
    }

    static Svf svf(float hz, float q) {
        return new Svf().set(hz, q, RATE);
    }

    private static float noise(Rng r) {
        return r.nextFloat() * 2 - 1;
    }

    private static float[] buf(float seconds) {
        return new float[Math.max(2, (int) (seconds * RATE))];
    }

    private static float exp(float v) {
        return (float) Math.exp(v);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    /** A one-pole low-pass, in place. */
    private static void lowpass(float[] x, float hz, int rate) {
        float a = 1 - exp(-TAU * hz / rate), s = 0;
        for (int i = 0; i < x.length; i++) {
            s += (x[i] - s) * a;
            x[i] = s;
        }
    }

    private static void highpass(float[] x, float hz, int rate) {
        float a = 1 - exp(-TAU * hz / rate), s = 0;
        for (int i = 0; i < x.length; i++) {
            s += (x[i] - s) * a;
            x[i] -= s;
        }
    }

    /** Adds src into dst at offset (in samples), scaled; what runs past the end wraps to the start when wrap. */
    private static void addAt(float[] dst, float[] src, int at, float gain, boolean wrap) {
        int n = dst.length;
        for (int i = 0; i < src.length; i++) {
            int j = at + i;
            if (j >= n) {
                if (!wrap) return;
                j %= n;
            }
            if (j < 0) continue;
            dst[j] += src[i] * gain;
        }
    }

    /**
     * A small reverb (four combs and two all-passes, after Schroeder and Moorer), returning the dry sound with its
     * tail: the result is longer by tail seconds.
     */
    private static float[] reverb(float[] in, int rate, float tail, float room, float damp, float wet) {
        int n = in.length + (int) (tail * rate);
        float[] out = new float[n];
        float sc = rate / 44100f;
        int[] cl = {(int) (1116 * sc), (int) (1277 * sc), (int) (1422 * sc), (int) (1557 * sc)};
        int[] al = {(int) (556 * sc), (int) (341 * sc)};
        float[][] cb = new float[4][];
        for (int k = 0; k < 4; k++) cb[k] = new float[cl[k]];
        float[][] ab = new float[2][];
        for (int k = 0; k < 2; k++) ab[k] = new float[al[k]];
        int[] ci = new int[4], ai = new int[2];
        float[] cs = new float[4];
        float fb = 0.7f + room * 0.28f;
        for (int i = 0; i < n; i++) {
            float x = i < in.length ? in[i] : 0;
            float acc = 0;
            for (int k = 0; k < 4; k++) {
                float y = cb[k][ci[k]];
                cs[k] = y * (1 - damp) + cs[k] * damp;
                cb[k][ci[k]] = x * 0.25f + cs[k] * fb;
                if (++ci[k] >= cl[k]) ci[k] = 0;
                acc += y;
            }
            for (int k = 0; k < 2; k++) {
                float b = ab[k][ai[k]];
                float y = -acc + b;
                ab[k][ai[k]] = acc + b * 0.5f;
                if (++ai[k] >= al[k]) ai[k] = 0;
                acc = y;
            }
            out[i] = x + acc * wet;
        }
        return out;
    }

    /** Far away: the highs gone, later, and mostly the echo off the buildings. */
    private static float[] distant(float[] x, float cut, float tail) {
        float[] y = new float[x.length + (int) (0.04f * RATE)];
        System.arraycopy(x, 0, y, (int) (0.04f * RATE), x.length);
        lowpass(y, cut, RATE);
        lowpass(y, cut * 1.3f, RATE);
        highpass(y, 45, RATE);
        return reverb(y, RATE, tail, 0.86f, 0.45f, 1.1f);
    }

    /** Down to half rate (filtered first). */
    private static float[] half(float[] x) {
        lowpass(x, 4200, RATE);
        lowpass(x, 4800, RATE);
        float[] y = new float[x.length / 2];
        for (int i = 0; i < y.length; i++) y[i] = (x[i * 2] + x[i * 2 + 1]) * 0.5f;
        return y;
    }

    /** To 16 bits at a peak level, with the ends faded so nothing clicks. */
    private static short[] out(float[] x, float peak) {
        float m = 1e-6f;
        for (float v : x) m = Math.max(m, Math.abs(v));
        float g = peak / m;
        int n = x.length, fade = Math.min(n / 4, 220);
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            float v = x[i] * g;
            if (i >= n - fade) v *= (n - i) / (float) fade;
            s[i] = (short) Math.round(Math.max(-1, Math.min(1, v)) * 32767);
        }
        return s;
    }

    /** To 16 bits at a peak level, the ends untouched (a loop). */
    private static short[] outNoFade(float[] x, float peak) {
        float m = 1e-6f;
        for (float v : x) m = Math.max(m, Math.abs(v));
        short[] s = new short[x.length];
        for (int i = 0; i < x.length; i++) s[i] = (short) Math.round(Math.max(-1, Math.min(1, x[i] * peak / m)) * 32767);
        return s;
    }

    /** A loop to 16 bits at a steady loudness (rms), never past 0.95. */
    private static short[] loopOut(float[] x, float rms) {
        double sum = 0;
        float m = 1e-6f;
        for (float v : x) {
            sum += v * v;
            m = Math.max(m, Math.abs(v));
        }
        float cur = (float) Math.sqrt(sum / Math.max(1, x.length)) + 1e-6f;
        float g = Math.min(rms / cur, 0.95f / m);
        short[] s = new short[x.length];
        for (int i = 0; i < x.length; i++) s[i] = (short) Math.round(Math.max(-1, Math.min(1, x[i] * g)) * 32767);
        return s;
    }

    /** Folds a sound rendered past its loop length back onto its start (events and tails wrap round). */
    private static float[] wrap(float[] x, int len) {
        float[] y = new float[len];
        for (int i = 0; i < x.length; i++) y[i % len] += x[i];
        return y;
    }

    /** Cross-fades the last xf samples into the first, so a noise bed loops without a seam. */
    private static float[] loopify(float[] x, int xf) {
        int n = x.length - xf;
        float[] y = new float[n];
        System.arraycopy(x, 0, y, 0, n);
        for (int i = 0; i < xf; i++) {
            float t = i / (float) xf;
            float a = (float) Math.sqrt(t), b = (float) Math.sqrt(1 - t);
            y[i] = x[i] * a + x[n + i] * b;
        }
        return y;
    }

    private static float[] loopBed(int n, int xf) {
        return new float[n + xf];
    }

    private static void add(float[] dst, float[] src) {
        for (int i = 0; i < dst.length && i < src.length; i++) dst[i] += src[i];
    }

    /** Phase (in cycles) per sample for a frequency curve, scaled so the loop holds a whole number of cycles. */
    private static float[] loopPhase(float[] hz, int rate) {
        double total = 0;
        for (float f : hz) total += f / rate;
        double k = Math.max(1, Math.round(total)) / total;
        float[] ph = new float[hz.length];
        double p = 0;
        for (int i = 0; i < hz.length; i++) {
            ph[i] = (float) (p - Math.floor(p));
            p += hz[i] * k / rate;
        }
        return ph;
    }

    // ------------------------------------------------------------------ weapons and blasts

    /** A gunshot: a sharp crack, the body's thump, and the street's echo. */
    private float[] gun(Rng r, float len, float crackHz, float bodyHz, float body, float tail) {
        float[] x = buf(len);
        Svf crack = svf(crackHz, 0.8f), bodyF = svf(bodyHz * 5, 0.7f), tailF = svf(1500, 0.7f), soft = svf(7500, 0.7f);
        float ph = 0;
        for (int i = 0; i < x.length; i++) {
            float t = i / (float) RATE, nz = noise(r);
            float eC = t < 0.0007f ? t / 0.0007f : exp(-t * 190);
            float c = crack.bp(nz) * eC * 1.7f + nz * eC * 0.3f;
            float eB = Math.min(1, t / 0.0015f) * exp(-t * 32);
            ph += bodyHz * (1 + 1.6f * exp(-t * 55)) / RATE;
            float b = (sn(ph) * 0.9f + bodyF.lp(nz) * 1.3f) * eB * body;
            if ((i & 31) == 0) tailF.set(250 + 1700 * exp(-t * 4), 0.7f, RATE);
            float tl = tailF.lp(nz) * exp(-t * (5 / len)) * tail * Math.min(1, t / 0.01f);
            x[i] = soft.lp(c + b + tl);
        }
        return reverb(x, RATE, 0.25f, 0.6f, 0.5f, 0.28f);
    }

    /** The tank gun: a blast of a shot. */
    private float[] cannon(Rng r) {
        float[] a = gun(r, 0.9f, 1400, 70, 1.6f, 0.8f);
        float[] b = boom(r, 1.4f, 45, 0.7f);
        float[] x = new float[Math.max(a.length, b.length)];
        addAt(x, a, 0, 1, false);
        addAt(x, b, 0, 0.6f, false);
        return x;
    }

    /** An explosion: a crack, a rolling roar that darkens as it goes, the ground's thump, and debris after. */
    private float[] boom(Rng r, float len, float subHz, float size) {
        float[] x = buf(len);
        Svf body = svf(2000, 0.75f), crack = svf(3200, 0.7f), deb = svf(2500, 6);
        float ph = 0, debEnv = 0;
        float roll1 = r.range(2.5f, 4f), roll2 = r.range(0.5f, 0.9f);
        for (int i = 0; i < x.length; i++) {
            float t = i / (float) RATE, nz = noise(r);
            float env = Math.min(1, t / 0.004f) * exp(-t * (2.6f / len));
            float rolling = 1 + 0.35f * Math.min(1, t * 4) * sn(roll1 * t + 0.3f * sn(roll2 * t));
            if ((i & 15) == 0) body.set(110 + 2600 * exp(-t * 8), 0.75f, RATE);
            float b = body.lp(nz) * env * rolling * 2.3f;
            float c = crack.lp(nz) * exp(-t * 70) * 1.1f;
            ph += subHz * (1 + 1.4f * exp(-t * 7)) / RATE;
            float sub = sn(ph) * exp(-t * (3f / len)) * Math.min(1, t / 0.006f) * 0.9f * size;
            if (t > 0.06f && t < len * 0.7f && r.nextFloat() < 0.0018f * exp(-t * 1.8f)) {
                debEnv = r.range(0.2f, 1f);
                deb.set(r.range(1200, 4200), 5, RATE);
            }
            debEnv *= 0.9965f;
            float d = deb.bp(nz) * debEnv * 0.9f;
            x[i] = b + c + sub + d;
        }
        return reverb(x, RATE, 0.9f, 0.82f, 0.55f, 0.3f);
    }

    /** An artillery shell coming in: a falling whistle that cuts off as it lands. */
    private float[] whistle(Rng r) {
        float len = 1.25f;
        float[] x = buf(len);
        Svf air = svf(1500, 3);
        float ph = 0;
        for (int i = 0; i < x.length; i++) {
            float t = i / (float) RATE, u = t / len;
            float f = 620 + 1450 * exp(-u * 2.4f) + 12 * sn(t * 6.5f);
            ph += f / RATE;
            if ((i & 31) == 0) air.set(f * 1.02f, 4, RATE);
            float tone = sn(ph) + 0.12f * sn(ph * 2);
            float a = 0.08f + 0.92f * u * u;
            float end = Math.min(1, (len - t) / 0.02f);
            x[i] = (tone * 0.8f + air.bp(noise(r)) * 0.5f) * a * end;
        }
        return x;
    }

    /** A jet passing over: a roar that brightens and darkens, swept by its own echo. */
    private float[] jet(Rng r) {
        float len = 4.2f;
        float[] x = buf(len);
        Svf lp = svf(800, 0.7f);
        float[] dl = new float[512];
        int di = 0;
        float ph = 0;
        for (int i = 0; i < x.length; i++) {
            float t = i / (float) RATE, u = t / len;
            float env = (float) Math.pow(Math.sin(Math.PI * Math.pow(u, 0.8)), 2.2);
            if ((i & 15) == 0) lp.set(400 + 3200 * env, 0.8f, RATE);
            float n = lp.lp(noise(r));
            ph += (2600 - 900 * u) / RATE;
            float s = n * 1.4f + sn(ph) * 0.05f * env;
            // a comb that sweeps: the sound off the ground arriving a little later
            float delay = 40 + 300 * (0.5f + 0.5f * sn(u * 0.9f - 0.25f));
            int j = (di - (int) delay + dl.length * 4) % dl.length;
            float y = s + dl[j] * 0.7f;
            dl[di] = s;
            di = (di + 1) % dl.length;
            x[i] = y * env;
        }
        return reverb(x, RATE, 0.6f, 0.8f, 0.5f, 0.25f);
    }

    /** Thunder: far, a long roll; near, a tearing crack first. */
    private float[] thunder(Rng r, boolean near) {
        float len = near ? 5f : 6f;
        float[] x = buf(len);
        Svf lp = svf(300, 0.7f), crackF = svf(2500, 0.7f);
        int bumps = r.range(5, 9);
        float[] bt = new float[bumps], ba = new float[bumps], bw = new float[bumps];
        for (int k = 0; k < bumps; k++) {
            bt[k] = r.range(near ? 0.2f : 0.3f, len * 0.7f);
            ba[k] = r.range(0.3f, 1f) * (1 - bt[k] / len * 0.6f);
            bw[k] = r.range(0.15f, 0.6f);
        }
        float ph = 0;
        for (int i = 0; i < x.length; i++) {
            float t = i / (float) RATE, nz = noise(r);
            float a = 0;
            for (int k = 0; k < bumps; k++) {
                float d = (t - bt[k]) / bw[k];
                a += ba[k] * exp(-d * d);
            }
            a *= Math.min(1, t / 0.25f) * Math.min(1, (len - t) / 1.2f);
            if ((i & 31) == 0) lp.set(170 + 480 * a, 0.8f, RATE);
            float roll = lp.lp(nz) * a * 3f;
            ph += (32 + 8 * a) / RATE;
            float sub = sn(ph) * a * 0.35f;
            float c = 0;
            if (near && t < 0.6f) {
                float ce = exp(-t * 9) * Math.min(1, t / 0.003f);
                float tear = r.nextFloat() < 0.08f ? noise(r) * 3 : 0;
                c = (crackF.hp(nz) * 1.2f + tear) * ce;
            }
            x[i] = roll + sub + c;
        }
        return reverb(x, RATE, 1.2f, 0.9f, 0.6f, 0.35f);
    }

    // ------------------------------------------------------------------ voices

    /**
     * A voice: a glottal buzz (with jitter, and a sub-harmonic for the dead's rasp) through three formants that move
     * from one vowel to another, with breath noise.
     */
    private float[] voice(Rng r, float len, float f0a, float f0b, float bump, float[] va, float[] vb, float jitter,
                          float breath, float rough, float sub, float attack, float vib, float rate) {
        float[] x = new float[Math.max(2, (int) (len * rate))];
        Svf f1 = new Svf(), f2 = new Svf(), f3 = new Svf(), tilt = new Svf().set(1400, 0.7f, rate);
        float ph = 0, ph2 = 0, jit = 0, roughPh = 0;
        for (int i = 0; i < x.length; i++) {
            float t = i / rate, u = t / len;
            jit += (noise(r) * jitter - jit) * 0.004f;
            float f = (f0a + (f0b - f0a) * u + bump * (float) Math.sin(Math.PI * u)) * (1 + jit + vib * sn(t * 5.3f));
            ph += f / rate;
            if (ph >= 1) ph -= 1;
            ph2 += f * 0.5f / rate;
            if (ph2 >= 1) ph2 -= 1;
            float src = tilt.lp(saw(ph, f / rate)) * 1.6f + saw(ph, f / rate) * 0.25f;
            src += sub * tilt.v2 * (ph2 < 0.5f ? 1 : -1) * 0.5f;
            roughPh += (28 + 9 * noise(r)) / rate;
            src *= 1 - rough * 0.5f * (1 + sn(roughPh));
            float nz = noise(r) * breath;
            if ((i & 15) == 0) {
                f1.set(va[0] + (vb[0] - va[0]) * u, 5, rate);
                f2.set(va[1] + (vb[1] - va[1]) * u, 7, rate);
                f3.set(va[2] + (vb[2] - va[2]) * u, 9, rate);
            }
            float s = src + nz;
            float y = f1.bp(s) + f2.bp(s) * 0.55f + f3.bp(s) * 0.28f;
            float env = Math.min(1, u / attack) * Math.min(1, (1 - u) * 5);
            x[i] = y * env;
        }
        return x;
    }

    private static final float[] V_A = {800, 1250, 2700}, V_AE = {700, 1700, 2600}, V_O = {500, 850, 2500},
            V_U = {340, 700, 2400}, V_UH = {620, 1150, 2550}, V_E = {450, 1900, 2600}, V_I = {320, 2200, 2900};
    private static final float[][] VOWELS = {V_A, V_AE, V_O, V_U, V_UH, V_E, V_I};

    private float[] scream(Rng r, float len, float f0a, float peak, float f0b, boolean high) {
        float[] v = voice(r, len, f0a, f0b, peak - (f0a + f0b) / 2, high ? V_AE : V_UH, V_A, 0.03f, 0.25f, 0.35f, 0, 0.08f, 0.012f, RATE);
        return reverb(v, RATE, 0.35f, 0.6f, 0.5f, 0.22f);
    }

    private float[] grunt(Rng r) {
        float[] v = voice(r, 0.32f, 190, 130, 10, V_UH, V_U, 0.04f, 0.35f, 0.25f, 0.1f, 0.12f, 0, RATE);
        return reverb(v, RATE, 0.15f, 0.4f, 0.5f, 0.15f);
    }

    /** The dead: low, wet, broken voices. kind picks the shape: groan, rising moan, growl, long moan. */
    private float[] groan(Rng r, float len, float f0a, float f0b, int kind) {
        float[] a = kind == 1 ? V_UH : kind == 2 ? V_A : V_O;
        float[] b = kind == 1 ? V_A : kind == 2 ? V_UH : V_U;
        float[] v = voice(r, len, f0a, f0b, kind == 3 ? 14 : 6, a, b, 0.09f, 0.55f, kind == 2 ? 0.8f : 0.55f, 0.6f,
                kind == 2 ? 0.1f : 0.3f, 0.02f, RATE);
        // a throat rattle
        Svf rat = svf(900, 2);
        float rp = 0;
        for (int i = 0; i < v.length; i++) {
            float t = i / (float) RATE;
            rp += (kind == 2 ? 34 : 22) / (float) RATE;
            float g = sn(rp) > 0.6f ? 1 : 0;
            v[i] += rat.bp(noise(r)) * g * 0.25f * Math.min(1, t * 4) * Math.min(1, (len - t) * 3);
        }
        return reverb(v, RATE, 0.4f, 0.65f, 0.6f, 0.2f);
    }

    private float[] bite(Rng r) {
        float[] x = buf(0.55f);
        Svf sq = svf(800, 3), cr = svf(2000, 2);
        float crE = 0;
        float thPh = 0;
        for (int i = 0; i < x.length; i++) {
            float t = i / (float) RATE, nz = noise(r);
            if (t < 0.28f && r.nextFloat() < 0.0012f) {
                crE = r.range(0.5f, 1);
                cr.set(r.range(1200, 3200), 2, RATE);
            }
            crE *= 0.996f;
            float crunch = cr.bp(nz) * crE * 1.5f;
            if ((i & 31) == 0) sq.set(900 - 600 * t / 0.55f, 3, RATE);
            float squelch = sq.bp(nz) * exp(-t * 5) * Math.min(1, t / 0.03f) * 0.8f;
            thPh += 85 / (float) RATE;
            float thud = sn(thPh) * exp(-t * 25) * 0.9f;
            x[i] = crunch + squelch + thud;
        }
        return reverb(x, RATE, 0.2f, 0.4f, 0.5f, 0.12f);
    }

    /** A radio: squelch, a few words through a small speaker, and the squelch closing. */
    private float[] radio(Rng r, float words) {
        float len = words + 0.35f;
        float[] x = buf(len);
        int at = (int) (0.09f * RATE);
        float t = 0.09f;
        while (t < words) {
            float sl = r.range(0.07f, 0.17f);
            float f0 = r.range(115, 165);
            float[] v = voice(r, sl, f0 * r.range(0.95f, 1.12f), f0 * r.range(0.85f, 1.05f), 0, VOWELS[r.nextInt(VOWELS.length)],
                    VOWELS[r.nextInt(VOWELS.length)], 0.02f, 0.3f, 0.1f, 0, 0.15f, 0, RATE);
            addAt(x, v, at, r.range(0.6f, 1f), false);
            float gap = r.chance(0.25f) ? r.range(0.08f, 0.16f) : r.range(0.0f, 0.03f);
            t += sl + gap;
            at = (int) (t * RATE);
        }
        Svf hp = svf(450, 0.7f), lp = svf(2600, 0.9f), hiss = svf(3200, 1.2f);
        for (int i = 0; i < x.length; i++) {
            float tt = i / (float) RATE, nz = noise(r);
            float v = lp.lp(hp.hp(x[i]));
            v = (float) Math.tanh(v * 2.2f) * 0.6f;
            float sqOpen = tt < 0.08f ? 1 : 0;
            float sqClose = tt > words && tt < words + 0.16f ? exp(-(tt - words) * 14) : 0;
            float bed = tt < words + 0.02f ? 0.035f : 0;
            x[i] = v + hiss.bp(nz) * (sqOpen * 0.5f + sqClose * 0.7f + bed);
        }
        return x;
    }

    /** A car alarm cycling through its patterns. */
    private float[] carAlarm(Rng r) {
        float len = 5.2f;
        float[] x = buf(len);
        Svf horn = svf(1500, 1.2f);
        float ph = 0;
        for (int i = 0; i < x.length; i++) {
            float t = i / (float) RATE;
            float f, g = 1;
            if (t < 1.3f) f = 750 + 800 * ((t % 0.325f) / 0.325f);
            else if (t < 2.6f) f = ((int) ((t - 1.3f) / 0.15f) % 2 == 0) ? 1150 : 1550;
            else if (t < 3.9f) {
                f = 1350;
                g = ((t - 2.6f) % 0.16f) < 0.09f ? 1 : 0;
            } else f = 1500 - 700 * (((t - 3.9f) % 0.325f) / 0.325f);
            ph += f / RATE;
            float tone = sn(ph) + sn(ph * 3) / 3.5f + sn(ph * 5) / 7f;
            x[i] = (horn.bp(tone) * 0.8f + tone * 0.25f) * g * Math.min(1, (len - t) / 0.15f);
        }
        lowpass(x, 5000, RATE);
        return reverb(x, RATE, 0.5f, 0.7f, 0.5f, 0.25f);
    }

    /** A fire engine's air horn: two blasts of a low chord. */
    private float[] horn(Rng r) {
        float len = 1.5f;
        float[] x = buf(len);
        Svf res = svf(620, 2.5f), lp = svf(1900, 0.7f);
        float p1 = 0, p2 = 0, p3 = 0;
        for (int i = 0; i < x.length; i++) {
            float t = i / (float) RATE;
            float g = (t < 0.55f ? Math.min(1, t / 0.03f) * Math.min(1, (0.55f - t) / 0.03f) : 0)
                    + (t > 0.72f && t < 1.42f ? Math.min(1, (t - 0.72f) / 0.03f) * Math.min(1, (1.42f - t) / 0.06f) : 0);
            p1 += 233 / (float) RATE;
            p2 += 294 / (float) RATE;
            p3 += 349 / (float) RATE;
            p1 -= (int) p1;
            p2 -= (int) p2;
            p3 -= (int) p3;
            float s = saw(p1, 233f / RATE) + saw(p2, 294f / RATE) * 0.8f + saw(p3, 349f / RATE) * 0.5f;
            float y = lp.lp(s) * 0.5f + res.bp(s) * 0.7f;
            x[i] = y * g;
        }
        return reverb(x, RATE, 0.6f, 0.75f, 0.5f, 0.3f);
    }

    private float[] chime() {
        float[] x = buf(1.4f);
        float[] notes = {880, 659.25f};
        for (int k = 0; k < 2; k++) {
            int at = (int) (k * 0.24f * RATE);
            for (int i = at; i < x.length; i++) {
                float t = (i - at) / (float) RATE;
                float f = notes[k];
                float e = Math.min(1, t / 0.004f);
                float v = sn(f * t) * exp(-t * 3.5f) + sn(f * 2.76f * t) * 0.3f * exp(-t * 7) + sn(f * 5.4f * t) * 0.12f * exp(-t * 12);
                x[i] += v * e * 0.5f;
            }
        }
        return reverb(x, RATE, 0.3f, 0.6f, 0.4f, 0.2f);
    }

    private float[] click() {
        float[] x = new float[(int) (0.03f * RATE)];
        for (int i = 0; i < x.length; i++) {
            float t = i / (float) RATE;
            x[i] = sn(1900 * t) * exp(-t * 260) + sn(3700 * t) * 0.3f * exp(-t * 400);
        }
        return x;
    }

    // ------------------------------------------------------------------ sirens and machines (loops)

    /** Shapes a tone like a siren's horn speaker. */
    private static float sirenTone(float ph) {
        return sn(ph) + 0.38f * sn(ph * 2) + 0.2f * sn(ph * 3) + 0.09f * sn(ph * 4) + 0.04f * sn(ph * 5);
    }

    /** A police wail: rising and falling over 4.8 seconds. */
    private float[] policeSiren(Rng r) {
        float len = 4.8f;
        int n = (int) (len * RATE);
        float[] hz = new float[n];
        for (int i = 0; i < n; i++) {
            float u = i / (float) n;
            float s = u < 0.42f ? (float) Math.sin(u / 0.42f * Math.PI / 2) : (float) Math.cos((u - 0.42f) / 0.58f * Math.PI / 2);
            hz[i] = 700 + 760 * s * s * (3 - 2 * s) / 1f;
        }
        float[] ph = loopPhase(hz, RATE);
        float[] x = new float[n];
        Svf spk = svf(1300, 0.8f);
        for (int i = 0; i < n; i++) {
            float tone = sirenTone(ph[i]);
            x[i] = spk.bp(tone) * 0.8f + tone * 0.35f;
        }
        lowpass(x, 5500, RATE);
        return wrap(reverb(x, RATE, 0.8f, 0.75f, 0.5f, 0.3f), n);
    }

    /** A fire engine's mechanical siren winding up and down, with its rotor's growl. */
    private float[] fireSiren(Rng r) {
        float len = 6f;
        int n = (int) (len * RATE);
        float[] hz = new float[n];
        for (int i = 0; i < n; i++) {
            float u = i / (float) n;
            float s = u < 0.5f ? 1 - exp(-u * 7) : exp(-(u - 0.5f) * 5.5f);
            hz[i] = 330 + 720 * s;
        }
        float[] ph = loopPhase(hz, RATE);
        float[] x = new float[n];
        Svf air = svf(900, 1.5f);
        for (int i = 0; i < n; i++) {
            float tone = Math.abs(sn(ph[i])) * 2 - 1.2732f;
            float growl = 1 + 0.12f * sn(ph[i] * 0.25f);
            x[i] = (tone * 0.8f + air.bp(noise(r) * (0.6f + 0.4f * tone)) * 0.5f + sn(ph[i] * 2) * 0.25f) * growl;
        }
        lowpass(x, 4000, RATE);
        highpass(x, 120, RATE);
        return wrap(reverb(x, RATE, 0.8f, 0.75f, 0.5f, 0.3f), n);
    }

    /** An ambulance's two tones. */
    private float[] ambulance(Rng r) {
        float len = 2.4f;
        int n = (int) (len * RATE);
        float[] hz = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float seg = (t % 0.6f) / 0.6f;
            float hi = (int) (t / 0.6f) % 2 == 0 ? 1 : 0;
            float glide = Math.min(1, seg / 0.03f);
            float from = hi > 0 ? 770 : 960, to = hi > 0 ? 960 : 770;
            hz[i] = from + (to - from) * glide;
        }
        float[] ph = loopPhase(hz, RATE);
        float[] x = new float[n];
        Svf spk = svf(1100, 0.8f);
        for (int i = 0; i < n; i++) {
            float p = ph[i];
            float tri = 4 * Math.abs(p - 0.5f) - 1;
            float tone = tri * 0.6f + sn(p) * 0.6f + sn(p * 3) * 0.12f;
            x[i] = spk.bp(tone) * 0.7f + tone * 0.4f;
        }
        return wrap(reverb(x, RATE, 0.8f, 0.75f, 0.5f, 0.3f), n);
    }

    /** An engine running: its firing pulses through the exhaust (diesel adds its knock). */
    private float[] engine(Rng r, float len, float fire, boolean diesel) {
        int n = (int) (len * RATE);
        float[] hz = new float[n];
        float drift = 0;
        for (int i = 0; i < n; i++) {
            drift += (noise(r) * 0.05f - drift) * 0.0005f;
            hz[i] = fire * (1 + drift + 0.02f * sn(i / (float) n * 2));
        }
        float[] ph = loopPhase(hz, RATE);
        float[] x = new float[n + (int) (0.2f * RATE)];
        Svf body = svf(diesel ? 420 : 520, 1.1f), exh = svf(diesel ? 1000 : 900, 0.8f), knock = svf(1300, 4);
        float last = 1, amp = 1, hum = 0;
        for (int i = 0; i < x.length; i++) {
            int k = i % n;
            float p = ph[k];
            if (p < last) amp = r.range(0.75f, 1f);
            last = p;
            float pulse = exp(-p * (diesel ? 7 : 5)) * amp;
            float nz = noise(r);
            hum += hz[k] * 2 / RATE;
            float y = body.lp(pulse * 2 - 0.5f) * 1.5f + exh.lp(nz) * pulse * 1.2f + sn(hum) * 0.08f + sn(hum * 2) * 0.05f;
            if (diesel) y += knock.bp(nz) * exp(-p * 30) * 0.6f;
            x[i] = y;
        }
        highpass(x, 60, RATE);
        return loopify(x, x.length - n);
    }

    /** A tank: the turbine's whine, its rumble, and the tracks' links clanking over the wheels. */
    private float[] tracks(Rng r) {
        float len = 3f;
        int n = (int) (len * RATE);
        float[] x = new float[n + RATE];
        Svf rum = svf(110, 0.9f), m1 = svf(1700, 14), m2 = svf(2900, 16), m3 = svf(780, 10);
        float wp = 0;
        int clanks = 27;
        for (int c = 0; c < clanks; c++) {
            int at = (int) ((c + r.range(-0.12f, 0.12f)) * n / (float) clanks);
            float a = r.range(0.5f, 1f);
            for (int i = 0; i < 40; i++) {
                int j = ((at + i) % n + n) % n;
                x[j] += noise(r) * a * (1 - i / 40f) * 1.2f;
            }
        }
        float[] y = new float[n];
        for (int i = 0; i < n + RATE; i++) {
            float s = x[i % n] * (i < n ? 1 : 0);
            float clank = m1.bp(s) * 0.9f + m2.bp(s) * 0.6f + m3.bp(s) * 0.7f;
            wp += (2950 + 30 * sn(i / (float) RATE * 0.5f)) / RATE;
            float whine = sn(wp) * 0.03f;
            y[i % n] += i < n ? clank + whine : clank;
        }
        float[] bed = loopBed(n, (int) (0.4f * RATE));
        for (int i = 0; i < bed.length; i++) bed[i] = rum.lp(noise(r)) * 1.6f;
        add(y, loopify(bed, (int) (0.4f * RATE)));
        return y;
    }

    /** A helicopter: the main rotor's slap, the turbine's whine, the tail rotor's buzz. */
    private float[] rotor(Rng r) {
        float len = 2f;
        int n = (int) (len * RATE);
        float[] x = new float[n];
        Svf slapF = svf(850, 0.9f), tail = svf(420, 0.8f), turb = svf(3000, 0.7f);
        float rate = 21 / len; // whole slaps in the loop
        float tp = 0, wp = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float cp = (t * rate) % 1f;
            float nz = noise(r);
            float slap = slapF.lp(nz) * exp(-cp * 9) * 2.2f + sn(t * 70) * exp(-cp * 7) * 0.9f;
            tp += 57f * 2 / RATE;
            tp -= (int) tp;
            float tailBuzz = tail.lp(saw(tp, 114f / RATE)) * 0.25f;
            wp += (1900 + 15 * sn(t * 3)) / RATE;
            float whine = sn(wp) * 0.035f + turb.lp(nz) * 0.08f;
            x[i] = slap + tailBuzz + whine;
        }
        return x;
    }

    /** A big aircraft overhead: a broad, slow roar. */
    private float[] aircraft(Rng r) {
        float len = 4.5f;
        float[] x = buf(len + 0.5f);
        Svf lp = svf(650, 0.7f), hum = svf(180, 2);
        for (int i = 0; i < x.length; i++) {
            float t = i / (float) RATE, nz = noise(r);
            float m = 1 + 0.15f * sn(t * 0.45f);
            x[i] = lp.lp(nz) * 1.4f * m + hum.bp(nz) * 0.8f;
        }
        return loopify(x, (int) (0.5f * RATE));
    }

    // ------------------------------------------------------------------ the country's beds (loops)

    /** A town's hum: traffic far off, a few cars passing, the mains' faint buzz. */
    private float[] city(Rng r) {
        float len = 6f;
        int n = (int) (len * RATE), xf = (int) (0.6f * RATE);
        float[] x = new float[n + xf];
        Svf rum = svf(320, 0.7f), pass = svf(800, 0.8f), mid = svf(450, 0.9f);
        float brown = 0;
        float[] cars = {r.range(0.5f, 2f), r.range(2.5f, 4f), r.range(4.3f, 5.6f)};
        for (int i = 0; i < x.length; i++) {
            float t = i / (float) RATE, nz = noise(r);
            brown = brown * 0.98f + nz * 0.1f;
            float p = 0;
            for (float c : cars) {
                float d = (t - c) / 0.7f;
                p += exp(-d * d);
            }
            x[i] = rum.lp(brown) * 3f + mid.bp(nz) * 0.25f + pass.lp(nz) * p * 0.6f + sn(t * 60) * 0.01f + sn(t * 120) * 0.012f;
        }
        highpass(x, 50, RATE);
        return loopify(x, xf);
    }

    /** Wind: gusting, with now and then a thin whistle. */
    private float[] wind(Rng r, float len, float strength) {
        int n = (int) (len * RATE), xf = (int) (1f * RATE);
        float[] x = new float[n + xf];
        Svf bp = svf(400, 1.4f), wh = svf(1000, 18), low = svf(150, 0.7f);
        float l1 = r.range(0.08f, 0.14f), l2 = r.range(0.17f, 0.29f), l3 = r.range(0.31f, 0.47f);
        for (int i = 0; i < x.length; i++) {
            float t = i / (float) RATE, nz = noise(r);
            float gust = 0.55f + 0.25f * sn(t * l1) + 0.15f * sn(t * l2 + 0.3f) + 0.08f * sn(t * l3 + 0.7f);
            if ((i & 31) == 0) {
                bp.set(260 + 520 * gust, 1.3f, RATE);
                wh.set(900 + 450 * gust, 18, RATE);
            }
            x[i] = (bp.bp(nz) * 1.6f * gust + wh.bp(nz) * 0.35f * gust * gust + low.lp(nz) * 0.9f) * gust * strength;
        }
        return loopify(x, xf);
    }

    /** Birds in the trees: chirps, short songs and trills, near and far. */
    private float[] birds(Rng r) {
        float len = 8f;
        int n = (int) (len * RATE);
        float[] x = new float[n + RATE];
        int calls = 16;
        for (int c = 0; c < calls; c++) {
            int at = r.nextInt(n);
            float amp = r.range(0.15f, 1f);
            int kind = r.nextInt(3);
            float base = r.range(2400, 4200);
            if (kind == 0) {
                int notes = r.range(3, 6);
                float nl = r.range(0.03f, 0.05f), gap = r.range(0.03f, 0.06f);
                for (int k = 0; k < notes; k++) {
                    int s0 = at + (int) (k * (nl + gap) * RATE);
                    float ph = 0;
                    for (int i = 0; i < nl * RATE; i++) {
                        float u = i / (nl * RATE);
                        ph += (base + 1600 * u) / RATE;
                        float e = (float) Math.sin(Math.PI * u);
                        x[(s0 + i) % x.length] += sn(ph) * e * e * amp;
                    }
                }
            } else if (kind == 1) {
                int notes = r.range(2, 4);
                int s0 = at;
                for (int k = 0; k < notes; k++) {
                    float nl = r.range(0.12f, 0.24f), f = base * r.range(0.7f, 1.0f);
                    float ph = 0;
                    for (int i = 0; i < nl * RATE; i++) {
                        float u = i / (nl * RATE);
                        ph += (f * (1 + 0.015f * sn(i * 24f / RATE))) / RATE;
                        float e = Math.min(1, u * 8) * Math.min(1, (1 - u) * 5);
                        x[(s0 + i) % x.length] += sn(ph) * e * amp * 0.7f;
                    }
                    s0 += (int) ((nl + r.range(0.04f, 0.12f)) * RATE);
                }
            } else {
                float nl = r.range(0.4f, 0.8f);
                float ph = 0;
                for (int i = 0; i < nl * RATE; i++) {
                    float u = i / (nl * RATE);
                    ph += (base * 1.1f + 300 * sn(i * 0.5f / RATE)) / RATE;
                    float am = 0.5f + 0.5f * sn(i * 38f / RATE);
                    float e = Math.min(1, u * 10) * Math.min(1, (1 - u) * 6);
                    x[(at + i) % x.length] += sn(ph) * am * e * amp * 0.6f;
                }
            }
        }
        float[] y = reverb(x, RATE, 0.5f, 0.6f, 0.4f, 0.3f);
        Svf leaves = svf(3000, 0.7f);
        float[] w = wrap(y, n);
        for (int i = 0; i < n; i++) w[i] += leaves.hp(noise(r)) * 0.02f;
        return w;
    }

    /** Crickets at night, each chirping at its own pace. */
    private float[] crickets(Rng r) {
        float len = 6f;
        int n = (int) (len * RATE);
        float[] x = new float[n];
        float[] periods = {0.5f, 0.6f, 0.75f, 1f, 1.2f};
        for (int c = 0; c < periods.length; c++) {
            float f = r.range(3700, 4700), amp = r.range(0.3f, 1f), off = r.nextFloat();
            int chirps = Math.round(len / periods[c]);
            int pulses = r.range(3, 4);
            for (int k = 0; k < chirps; k++) {
                int at = (int) ((k + off) * periods[c] * RATE);
                for (int p = 0; p < pulses; p++) {
                    int s0 = at + (int) (p * 0.032f * RATE);
                    int pl = (int) (0.02f * RATE);
                    for (int i = 0; i < pl; i++) {
                        float u = i / (float) pl;
                        float e = (float) Math.sin(Math.PI * u);
                        x[(s0 + i) % n] += sn(f * (s0 + i) / RATE) * e * e * amp;
                    }
                }
            }
        }
        float[] y = wrap(reverb(x, RATE, 0.3f, 0.5f, 0.5f, 0.2f), n);
        Svf hush = svf(400, 0.7f);
        for (int i = 0; i < n; i++) y[i] += hush.lp(noise(r)) * 0.03f;
        return y;
    }

    /** Rain: a hiss, the patter of drops, and (heavy) a low roar. */
    private float[] rain(Rng r, float len, boolean heavy) {
        int n = (int) (len * RATE), xf = (int) (0.5f * RATE);
        float[] x = new float[n + xf];
        Svf hp = svf(heavy ? 300 : 450, 0.7f), lp = svf(heavy ? 4200 : 3600, 0.7f), low = svf(260, 0.7f);
        Svf[] drop = new Svf[4];
        float[] de = new float[4];
        for (int k = 0; k < 4; k++) drop[k] = svf(r.range(1500, 4000), 4);
        float dens = heavy ? 0.03f : 0.012f;
        for (int i = 0; i < x.length; i++) {
            float t = i / (float) RATE, nz = noise(r);
            float sway = 0.9f + 0.1f * sn(t * 0.3f);
            float hiss = lp.lp(hp.hp(nz)) * 0.55f * sway;
            float y = hiss + low.lp(nz) * (heavy ? 1.3f : 0.6f);
            if (r.nextFloat() < dens) {
                int k = r.nextInt(4);
                de[k] = r.range(0.2f, 1f);
                drop[k].set(r.range(1200, 4200), 3, RATE);
            }
            for (int k = 0; k < 4; k++) {
                y += drop[k].bp(de[k] * nz) * 0.6f;
                de[k] *= 0.985f;
            }
            x[i] = y;
        }
        return loopify(x, xf);
    }

    private float[] storm(Rng r) {
        float[] rn = rain(r, 8.5f, true);
        float[] wd = wind(r, 8.5f, 1.6f);
        int n = Math.min(rn.length, wd.length);
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = rn[i] + wd[i] * 0.7f;
        return x;
    }

    /** A building burning: a low roar, cracks and pops. */
    private float[] fire(Rng r) {
        float len = 5f;
        int n = (int) (len * RATE);
        float[] x = new float[n + (int) (0.3f * RATE)];
        Svf roar = svf(240, 0.8f), pop = svf(650, 3), hiss = svf(3500, 0.7f);
        float popE = 0;
        for (int i = 0; i < x.length; i++) {
            float t = i / (float) RATE, nz = noise(r);
            float y = 0;
            if (i < n && r.nextFloat() < 0.0012f) {
                // a crackle: a few clicks close together
                int k = r.range(1, 5);
                float a = (float) Math.pow(r.nextFloat(), 2) * 1.6f + 0.2f;
                for (int c = 0; c < k; c++) {
                    int s0 = i + r.nextInt((int) (0.03f * RATE));
                    int cl = r.range(20, 120);
                    for (int j = 0; j < cl; j++) x[(s0 + j) % x.length] += noise(r) * a * 0.6f * (1 - j / (float) cl);
                }
            }
            if (i < n && r.nextFloat() < 0.00007f) popE = r.range(0.5f, 1.2f);
            popE *= 0.9985f;
            y += pop.bp(nz * popE) * 1.2f;
            x[i] += y;
        }
        lowpass(x, 3200, RATE);
        lowpass(x, 4500, RATE);
        highpass(x, 40, RATE);
        float[] w = wrap(x, n);
        int xf = (int) (0.4f * RATE);
        float[] bed = loopBed(n, xf);
        Svf flick = svf(500, 0.9f);
        for (int i = 0; i < bed.length; i++) {
            float t = i / (float) RATE, nz = noise(r);
            bed[i] = roar.lp(nz) * 2.2f * (0.75f + 0.25f * sn(t * 0.6f)) + flick.bp(nz) * 0.25f * (0.6f + 0.4f * sn(t * 3.1f)) + hiss.hp(nz) * 0.03f;
        }
        highpass(bed, 40, RATE);
        add(w, loopify(bed, xf));
        return w;
    }

    /** People panicking: shouts and screams over a murmur and running feet. */
    private float[] crowd(Rng r) {
        float len = 6f;
        int n = (int) (len * RATE);
        float[] x = new float[n + RATE];
        for (int k = 0; k < 16; k++) {
            boolean high = r.chance(0.5f);
            float f0 = high ? r.range(380, 620) : r.range(190, 320);
            float l = r.range(0.3f, 0.9f);
            float[] v = voice(r, l, f0, f0 * r.range(0.75f, 1.1f), f0 * r.range(0.05f, 0.25f), VOWELS[r.nextInt(3)], VOWELS[r.nextInt(5)],
                    0.03f, 0.3f, 0.3f, 0, 0.12f, 0.01f, RATE);
            addAt(x, v, r.nextInt(n), r.range(0.15f, 0.6f), true);
        }
        Svf mur = svf(550, 1.1f), feet = svf(900, 0.8f);
        float fe = 0;
        for (int i = 0; i < n; i++) {
            if (r.nextFloat() < 0.0004f) fe = r.range(0.3f, 0.8f);
            fe *= 0.993f;
            x[i] += feet.lp(noise(r) * fe) * 0.8f;
        }
        float[] w = wrap(reverb(x, RATE, 0.4f, 0.7f, 0.5f, 0.25f), n);
        int xf = (int) (0.5f * RATE);
        float[] bed = loopBed(n, xf);
        for (int i = 0; i < bed.length; i++) {
            float t = i / (float) RATE;
            bed[i] = mur.bp(noise(r)) * 0.35f * (0.6f + 0.4f * sn(t * 5.3f + 0.5f * sn(t * 1.7f)));
        }
        add(w, loopify(bed, xf));
        return w;
    }

    /** A horde: dozens of the dead groaning at once, their feet, and the ground's rumble under them. */
    private float[] horde(Rng r) {
        float len = 8f;
        int n = (int) (len * RATE);
        float[] x = new float[n + 3 * RATE];
        for (int k = 0; k < 30; k++) {
            float f0 = r.range(60, 115), l = r.range(0.8f, 2.2f);
            int kind = r.nextInt(4);
            float[] a = kind == 1 ? V_UH : kind == 2 ? V_A : V_O, b = kind == 1 ? V_A : V_U;
            float[] v = voice(r, l, f0, f0 * r.range(0.8f, 1.2f), r.range(0, 12), a, b, 0.09f, 0.55f, 0.6f, 0.6f, 0.3f, 0.02f, RATE);
            addAt(x, v, r.nextInt(n), r.range(0.25f, 1f), true);
        }
        lowpass(x, 1800, RATE);
        float[] w = wrap(reverb(x, RATE, 0.8f, 0.85f, 0.55f, 0.35f), n);
        Svf rum = svf(95, 0.8f), shuffle = svf(800, 0.8f);
        int xf = (int) (0.6f * RATE);
        float[] bed = loopBed(n, xf);
        for (int i = 0; i < bed.length; i++) {
            float t = i / (float) RATE, nz = noise(r);
            bed[i] = rum.lp(nz) * 1.2f + shuffle.lp(nz) * 0.25f * (0.6f + 0.4f * sn(t * 2.2f + 0.4f * sn(t * 0.6f)));
        }
        add(w, loopify(bed, xf));
        return w;
    }

    /** A fight a few streets away: rifles, a machine gun's bursts, now and then a blast, all through the echo. */
    private float[] battle(Rng r) {
        float len = 10f;
        int n = (int) (len * RATE);
        float[] x = new float[n + 3 * RATE];
        float[] rifle = gun(r, 0.6f, 3400, 140, 0.7f, 0.45f), mg = gun(r, 0.42f, 3000, 125, 0.75f, 0.3f),
                pistol = gun(r, 0.55f, 2600, 170, 0.8f, 0.35f), bm = boom(r, 2.2f, 52, 1f);
        float t = 0.1f;
        while (t < len) {
            int kind = r.nextInt(10);
            if (kind < 5) {
                int shots = r.range(1, 5);
                for (int k = 0; k < shots; k++) {
                    addAt(x, r.chance(0.7f) ? rifle : pistol, (int) (t * RATE), r.range(0.3f, 1f), true);
                    t += r.range(0.12f, 0.45f);
                }
            } else if (kind < 8) {
                int shots = r.range(6, 14);
                float a = r.range(0.3f, 0.9f);
                for (int k = 0; k < shots; k++) {
                    addAt(x, mg, (int) (t * RATE), a * r.range(0.8f, 1f), true);
                    t += 0.085f;
                }
            } else if (kind == 8) {
                addAt(x, bm, (int) (t * RATE), r.range(0.4f, 0.9f), true);
            }
            t += r.range(0.2f, 1.1f);
        }
        float[] d = distant(x, 900, 1.4f);
        return wrap(d, n);
    }

    // ------------------------------------------------------------------ the score

    private static float midi(float note) {
        return 440f * (float) Math.pow(2, (note - 69) / 12f);
    }

    /** The chords, two bars each: D minor, B flat, F, C (a sad, open progression). */
    private static final int[][] CHORDS = {{50, 53, 57, 64}, {46, 50, 53, 57}, {53, 57, 60, 67}, {48, 52, 55, 62}};
    private static final int[] ROOTS = {38, 34, 41, 36};

    private short[] renderStem(int k) {
        Rng r = new Rng(5500 + k * 104729L);
        final int R = MUSIC_RATE;
        int n = (int) (MUSIC_LEN * R);
        float[] x = new float[n + 5 * R];
        float beat = 0.75f, bar = 3f;
        switch (k) {
            case ST_CALM: {
                // a warm pad on the chords, and a few soft notes picked over it
                for (int c = 0; c < 4; c++) {
                    for (int v = 0; v < 4; v++) pad(x, R, c * 6f, 6.6f, midi(CHORDS[c][v]), 0.22f, 900, 1.6f, r);
                    pad(x, R, c * 6f, 6.6f, midi(ROOTS[c] + 12), 0.25f, 500, 1.2f, r);
                }
                int[] mel = {74, 72, 69, 67, 69, 65, 64, 62};
                for (int b = 0; b < 8; b++) {
                    if (b % 2 == 1 && r.chance(0.5f)) continue;
                    float at = b * bar + beat * (b % 2 == 0 ? 1 : 2);
                    pluck(x, R, at, midi(mel[b]), 0.22f, 2.2f, r);
                    if (r.chance(0.5f)) pluck(x, R, at + beat * 1.5f, midi(mel[b] - 5), 0.14f, 2f, r);
                }
                break;
            }
            case ST_DARK: {
                // low strings: root, fifth and octave, slow and shaded
                for (int c = 0; c < 4; c++) {
                    float t0 = c * 6f;
                    strings(x, R, t0, 6.4f, midi(ROOTS[c]), 0.3f, 700, 0.6f, r);
                    strings(x, R, t0, 6.4f, midi(ROOTS[c] + 7), 0.2f, 700, 0.6f, r);
                    strings(x, R, t0, 6.4f, midi(ROOTS[c] + 12), 0.18f, 900, 0.6f, r);
                    strings(x, R, t0 + 3, 3.4f, midi(CHORDS[c][1] - 12), 0.13f, 800, 0.6f, r);
                }
                break;
            }
            case ST_PULSE: {
                // eighth notes on the root, a heartbeat accent on each beat
                for (int s = 0; s < 64; s++) {
                    float at = s * beat / 2;
                    int c = (int) (at / 6f) % 4;
                    float acc = s % 2 == 0 ? 1 : 0.55f;
                    bass(x, R, at, midi(ROOTS[c] + 12), 0.32f * acc, 0.33f);
                }
                for (int b = 0; b < 32; b++) {
                    float at = b * beat;
                    drum(x, R, at, 52, 0.25f, 0.18f, 0, r);
                    drum(x, R, at + 0.2f, 47, 0.15f, 0.16f, 0, r);
                }
                break;
            }
            case ST_DRUMS: {
                // taiko-like drums, toms and a dry snare, a fill every second bar
                for (int b = 0; b < 8; b++) {
                    float t0 = b * bar, st = bar / 16;
                    int[] kick = {0, 6, 8, 11};
                    for (int s : kick) drum(x, R, t0 + s * st, 62, 0.75f, 0.5f, 0.15f, r);
                    snare(x, R, t0 + 4 * st, 0.45f, r);
                    snare(x, R, t0 + 12 * st, 0.5f, r);
                    for (int s = 0; s < 16; s++) shaker(x, R, t0 + s * st, s % 4 == 2 ? 0.12f : 0.06f, r);
                    if (b % 2 == 1) {
                        drum(x, R, t0 + 13 * st, 120, 0.45f, 0.25f, 0.1f, r);
                        drum(x, R, t0 + 14 * st, 100, 0.5f, 0.25f, 0.1f, r);
                        drum(x, R, t0 + 15 * st, 82, 0.55f, 0.3f, 0.1f, r);
                    }
                    if (b % 2 == 0) drum(x, R, t0, 44, 0.9f, 1.4f, 0.2f, r);
                }
                break;
            }
            case ST_STRINGS: {
                // tremolo strings climbing through each chord
                for (int c = 0; c < 4; c++) {
                    for (int v = 0; v < 3; v++) {
                        float f = midi(CHORDS[c][v] + 12);
                        tremolo(x, R, c * 6f, 6.2f, f, 0.12f, r);
                    }
                    tremolo(x, R, c * 6f + 3, 3.2f, midi(CHORDS[c][3] + 12), 0.1f, r);
                }
                break;
            }
            case ST_AFTER: {
                // a slow, sparse piano over a held low note
                int[] line = {69, 65, 62, 64, 65, 62, 60, 57, 69, 72, 69, 67, 65, 64, 62, 60};
                for (int i = 0; i < line.length; i++) {
                    float at = i * 1.5f;
                    if (i % 4 == 3 && r.chance(0.5f)) continue;
                    pluck(x, R, at, midi(line[i]), 0.3f, 3.2f, r);
                    if (i % 2 == 0) pluck(x, R, at, midi(line[i] - 12), 0.15f, 3.5f, r);
                }
                for (int c = 0; c < 4; c++) pad(x, R, c * 6f, 6.6f, midi(ROOTS[c] + 12), 0.15f, 400, 2f, r);
                break;
            }
            case ST_MARCH: {
                // a war room: low horns on a slow figure, a muffled snare, timpani on the bar
                int[] horn = {62, 62, 65, 69, 58, 62, 65, 65, 65, 65, 69, 72, 60, 64, 67, 64};
                for (int i = 0; i < 16; i++) {
                    float at = i * 1.5f;
                    hornNote(x, R, at, 1.55f, midi(horn[i] - 12), 0.22f, r);
                    hornNote(x, R, at, 1.55f, midi(horn[i] - 24), 0.14f, r);
                }
                for (int b = 0; b < 8; b++) {
                    float t0 = b * bar, st = bar / 16;
                    drum(x, R, t0, 48, 0.6f, 1f, 0.05f, r);
                    drum(x, R, t0 + 8 * st, 43, 0.4f, 0.9f, 0.05f, r);
                    int[] pat = {0, 2, 3, 4, 6, 8, 10, 11, 12, 14};
                    for (int s : pat) snare(x, R, t0 + s * st, s % 4 == 0 ? 0.18f : 0.09f, r);
                }
                break;
            }
            default:
        }
        float[] y = reverb(x, R, 0.1f, 0.88f, 0.5f, k == ST_DRUMS ? 0.15f : 0.32f);
        highpass(y, 30, R); // before the wrap, so the filter can't leave a step at the join
        float[] w = wrap(y, n);
        return outNoFade(w, 0.8f);
    }

    /** A pad: three detuned saws through a soft filter, slow in and out. */
    private void pad(float[] x, int R, float at, float len, float f, float amp, float cut, float att, Rng r) {
        int s0 = (int) (at * R), m = (int) (len * R);
        Svf lp = new Svf().set(cut, 0.8f, R);
        float[] ph = {r.nextFloat(), r.nextFloat(), r.nextFloat()};
        float[] det = {1, 1.004f, 0.9965f};
        for (int i = 0; i < m && s0 + i < x.length; i++) {
            float t = i / (float) R;
            float e = Math.min(1, t / att) * Math.min(1, (len - t) / 1.2f);
            float s = 0;
            for (int k = 0; k < 3; k++) {
                float fk = f * det[k];
                ph[k] += fk / R;
                if (ph[k] >= 1) ph[k] -= 1;
                s += saw(ph[k], fk / R);
            }
            x[s0 + i] += lp.lp(s) * amp * e * 0.4f;
        }
    }

    /** Bowed strings: a saw ensemble with a slow swell and vibrato. */
    private void strings(float[] x, int R, float at, float len, float f, float amp, float cut, float att, Rng r) {
        int s0 = (int) (at * R), m = (int) (len * R);
        Svf lp = new Svf().set(cut, 0.9f, R);
        float p1 = r.nextFloat(), p2 = r.nextFloat();
        for (int i = 0; i < m && s0 + i < x.length; i++) {
            float t = i / (float) R;
            float e = Math.min(1, t / att) * Math.min(1, (len - t) / 0.8f) * (0.85f + 0.15f * sn(t * 0.3f));
            float v = 1 + 0.004f * sn(t * 5.2f);
            p1 += f * v / R;
            p2 += f * 1.003f * v / R;
            p1 -= (int) p1;
            p2 -= (int) p2;
            x[s0 + i] += lp.lp(saw(p1, f / R) + saw(p2, f / R)) * amp * e * 0.5f;
        }
    }

    private void tremolo(float[] x, int R, float at, float len, float f, float amp, Rng r) {
        int s0 = (int) (at * R), m = (int) (len * R);
        Svf lp = new Svf().set(2400, 0.7f, R);
        float p1 = r.nextFloat(), p2 = r.nextFloat();
        for (int i = 0; i < m && s0 + i < x.length; i++) {
            float t = i / (float) R;
            float swell = 0.4f + 0.6f * t / len;
            float e = Math.min(1, t / 0.3f) * Math.min(1, (len - t) / 0.3f) * swell;
            // sixteenths at the tempo: 80 bpm x 4
            float trem = 0.6f + 0.4f * sn(t * 80 / 60f * 4);
            p1 += f / R;
            p2 += f * 1.005f / R;
            p1 -= (int) p1;
            p2 -= (int) p2;
            x[s0 + i] += lp.lp(saw(p1, f / R) + saw(p2, f / R)) * amp * e * trem * 0.5f;
        }
    }

    /** A plucked note (Karplus-Strong): something between a harp and a soft piano. */
    private void pluck(float[] x, int R, float at, float f, float amp, float len, Rng r) {
        int s0 = (int) (at * R), m = (int) (len * R);
        int p = Math.max(2, Math.round(R / f));
        float[] d = new float[p];
        Svf soft = new Svf().set(f * 3, 0.7f, R);
        for (int i = 0; i < p; i++) d[i] = soft.lp(noise(r));
        int j = 0;
        float last = 0;
        for (int i = 0; i < m && s0 + i < x.length; i++) {
            float v = d[j];
            float nv = (v + last) * 0.4985f;
            last = v;
            d[j] = nv;
            j = (j + 1) % p;
            float e = Math.min(1, (len - i / (float) R) / 0.3f);
            x[s0 + i] += v * amp * e * 1.3f + sn(f * i / R) * amp * 0.25f * exp(-i / (float) R * 2.5f);
        }
    }

    private void bass(float[] x, int R, float at, float f, float amp, float len) {
        int s0 = (int) (at * R), m = (int) (len * R);
        Svf lp = new Svf();
        float ph = 0;
        for (int i = 0; i < m && s0 + i < x.length; i++) {
            float t = i / (float) R;
            if ((i & 15) == 0) lp.set(180 + 900 * exp(-t * 14), 1.4f, R);
            ph += f / R;
            if (ph >= 1) ph -= 1;
            float e = Math.min(1, t / 0.005f) * exp(-t * 5) * Math.min(1, (len - t) / 0.04f);
            x[s0 + i] += lp.lp(saw(ph, f / R)) * amp * e;
        }
    }

    /** A drum: a pitched thump falling in pitch, with a little skin noise. */
    private void drum(float[] x, int R, float at, float f, float amp, float len, float noiseAmt, Rng r) {
        int s0 = (int) (at * R), m = (int) (len * R);
        Svf lp = new Svf().set(300, 0.7f, R);
        float ph = 0;
        for (int i = 0; i < m && s0 + i < x.length; i++) {
            float t = i / (float) R;
            ph += f * (1 + 0.6f * exp(-t * 30)) / R;
            float e = Math.min(1, t / 0.002f) * exp(-t * 4.5f / len);
            x[s0 + i] += (sn(ph) + lp.lp(noise(r)) * noiseAmt * exp(-t * 20)) * amp * e;
        }
    }

    private void snare(float[] x, int R, float at, float amp, Rng r) {
        int s0 = (int) (at * R), m = (int) (0.22f * R);
        Svf bp = new Svf().set(1700, 0.9f, R);
        for (int i = 0; i < m && s0 + i < x.length; i++) {
            float t = i / (float) R;
            float e = Math.min(1, t / 0.001f) * exp(-t * 22);
            x[s0 + i] += (bp.bp(noise(r)) * 1.3f + sn(190 * t) * 0.5f * exp(-t * 40)) * amp * e;
        }
    }

    private void shaker(float[] x, int R, float at, float amp, Rng r) {
        int s0 = (int) (at * R), m = (int) (0.06f * R);
        Svf hp = new Svf().set(3500, 0.7f, R);
        for (int i = 0; i < m && s0 + i < x.length; i++) {
            float t = i / (float) R;
            x[s0 + i] += hp.hp(noise(r)) * amp * Math.min(1, t / 0.01f) * exp(-t * 60);
        }
    }

    private void hornNote(float[] x, int R, float at, float len, float f, float amp, Rng r) {
        int s0 = (int) (at * R), m = (int) (len * R);
        Svf lp = new Svf();
        float p1 = r.nextFloat(), p2 = r.nextFloat();
        for (int i = 0; i < m && s0 + i < x.length; i++) {
            float t = i / (float) R;
            float e = Math.min(1, t / 0.12f) * Math.min(1, (len - t) / 0.2f);
            if ((i & 15) == 0) lp.set(300 + 700 * e, 0.8f, R);
            p1 += f / R;
            p2 += f * 1.002f / R;
            p1 -= (int) p1;
            p2 -= (int) p2;
            x[s0 + i] += lp.lp(saw(p1, f / R) + saw(p2, f / R) * 0.7f) * amp * e;
        }
    }
}
