package com.instantaxtion.zcs2;

/**
 * Mixes the sound into a stereo stream: one-shot voices (with priorities, a cap on copies of one sound, and a short
 * fade on any voice taken over, so nothing clicks), looping beds whose volume, pan and pitch glide, the score's
 * stems cross-fading between cues, and a limiter at the end so the sum never clips.
 *
 * The platform's audio thread calls mix(); the game's thread calls the rest. They share the tables under this
 * object's lock, and nothing here allocates after construction.
 */
public final class Mixer {
    public static final int VOICES = 24, TAILS = 6, LOOPS = 20;

    public static final int CUE_CALM = 0, CUE_TENSION = 1, CUE_BATTLE = 2, CUE_AFTERMATH = 3, CUE_STRATEGIC = 4, CUES = 5;
    public static final String[] CUE_NAMES = {"calm", "tension", "battle", "aftermath", "strategic"};
    /** Each cue as a mix of the stems: calm pad, dark strings, pulse, drums, high strings, piano, march. */
    static final float[][] CUE_MIX = {
            {1f, 0, 0, 0, 0, 0.2f, 0},
            {0.12f, 0.9f, 0.7f, 0, 0.35f, 0, 0},
            {0, 0.7f, 0.85f, 1f, 0.75f, 0, 0},
            {0.45f, 0.3f, 0, 0, 0, 1f, 0},
            {0.2f, 0.45f, 0, 0, 0, 0, 1f}};

    private final Synth synth;

    private final short[][] vData = new short[VOICES][];
    private final float[] vPos = new float[VOICES], vStep = new float[VOICES], vL = new float[VOICES], vR = new float[VOICES];
    private final int[] vId = new int[VOICES], vPrio = new int[VOICES];

    private final short[][] tData = new short[TAILS][];
    private final float[] tPos = new float[TAILS], tStep = new float[TAILS], tL = new float[TAILS], tR = new float[TAILS];
    private final int[] tLeft = new int[TAILS];
    private static final int TAIL_LEN = 300;

    private final short[][] lData = new short[LOOPS][];
    private final int[] lId = new int[LOOPS];
    private final float[] lPos = new float[LOOPS], lStep = new float[LOOPS], lStepT = new float[LOOPS], lL = new float[LOOPS],
            lR = new float[LOOPS], lLT = new float[LOOPS], lRT = new float[LOOPS];

    private final short[][] sData = new short[Synth.STEMS][];
    private final float[] sVol = new float[Synth.STEMS], sT = new float[Synth.STEMS];
    private float mPos;
    private int cue = -1;
    private float musicMaster = 0.5f;

    private float sfxBus = 1, musicBus = 1;
    private boolean mono;
    private float limGain = 1;
    /** Sounds asked for (played or not), for tests and tools. */
    public int requests;

    public Mixer(Synth synth) {
        this.synth = synth;
        for (int k = 0; k < LOOPS; k++) {
            lId[k] = -1;
            lStep[k] = lStepT[k] = 1;
        }
    }

    public Synth synth() {
        return synth;
    }

    private static float panL(float p) {
        return (float) Math.sqrt((1 - p) * 0.5f) * 1.3f;
    }

    private static float panR(float p) {
        return (float) Math.sqrt((1 + p) * 0.5f) * 1.3f;
    }

    /** The old interface: a sound by name at a volume and pan. */
    public void play(String name, float volume, float pan) {
        play(Synth.id(name), volume, pan, 1, 5, 4);
    }

    /**
     * Plays a one-shot. prio: what may take whose voice (a sound only takes a voice of no higher priority); maxSame:
     * how many copies of this sound at once (beyond that the quietest copy gives way, if this one's louder). Returns
     * whether it plays.
     */
    public boolean play(int id, float volume, float pan, float pitch, int prio, int maxSame) {
        requests++;
        if (volume < 0.002f) return false;
        short[] s = synth.peek(id);
        if (s == null || s.length < 4) return false;
        float p = Math.max(-1, Math.min(1, pan));
        float step = Synth.step(id) * Math.max(0.25f, Math.min(4, pitch));
        synchronized (this) {
            int v = -1, same = 0, quietSame = -1;
            float qs = Float.MAX_VALUE;
            for (int i = 0; i < VOICES; i++) {
                if (vData[i] == null) {
                    if (v < 0) v = i;
                    continue;
                }
                if (vId[i] == id) {
                    same++;
                    float loud = loudness(i);
                    if (loud < qs) {
                        qs = loud;
                        quietSame = i;
                    }
                }
            }
            if (same >= maxSame) {
                if (quietSame < 0 || qs > volume * 1.3f) return false;
                v = quietSame;
            } else if (v < 0) {
                // every voice busy: take the least important, quietest, nearest its end
                float best = Float.MAX_VALUE;
                for (int i = 0; i < VOICES; i++) {
                    if (vPrio[i] > prio) continue;
                    float score = vPrio[i] * 10 + loudness(i);
                    if (score < best) {
                        best = score;
                        v = i;
                    }
                }
                if (v < 0) return false;
            }
            if (vData[v] != null) toTail(v);
            vData[v] = s;
            vId[v] = id;
            vPrio[v] = prio;
            vPos[v] = 0;
            vStep[v] = step;
            vL[v] = volume * panL(p);
            vR[v] = volume * panR(p);
            return true;
        }
    }

    /** How much of a voice is left to hear (volume times what's left). */
    private float loudness(int i) {
        float left = (vData[i].length - vPos[i]) / (float) vData[i].length;
        return (vL[i] + vR[i]) * (0.2f + left);
    }

    /** A voice that's been taken over fades out quickly instead of stopping dead. */
    private void toTail(int v) {
        int t = 0;
        for (int k = 1; k < TAILS; k++) if (tLeft[k] < tLeft[t]) t = k;
        tData[t] = vData[v];
        tPos[t] = vPos[v];
        tStep[t] = vStep[v];
        tL[t] = vL[v];
        tR[t] = vR[v];
        tLeft[t] = TAIL_LEN;
    }

    /** Sets a looping bed: its sound, volume (0 fades it out), pan and pitch. They glide to these values. */
    public void setLoop(int slot, int id, float volume, float pan, float pitch) {
        if (slot < 0 || slot >= LOOPS) return;
        short[] s = id >= 0 ? synth.peek(id) : null;
        float p = Math.max(-1, Math.min(1, pan));
        float v = Math.max(0, volume);
        synchronized (this) {
            if (s != lData[slot]) {
                if (s == null) v = 0;
                else if (lData[slot] == null || lL[slot] + lR[slot] < 0.002f) {
                    lData[slot] = s;
                    lId[slot] = id;
                    lPos[slot] = (lPos[slot] * 7.31f) % s.length;
                    lL[slot] = lR[slot] = 0;
                } else v = 0; // fade the old one out first; the new one starts once it's quiet
            }
            lLT[slot] = v * panL(p);
            lRT[slot] = v * panR(p);
            lStepT[slot] = Synth.step(id < 0 ? 0 : id) * Math.max(0.5f, Math.min(2, pitch));
            if (lL[slot] + lR[slot] < 0.002f) lStep[slot] = lStepT[slot];
        }
    }

    public synchronized void stopLoops() {
        for (int k = 0; k < LOOPS; k++) lLT[k] = lRT[k] = 0;
    }

    /** Chooses the score's cue; the stems cross-fade to it. */
    public synchronized void setCue(int c) {
        cue = Math.max(0, Math.min(CUES - 1, c));
        for (int k = 0; k < Synth.STEMS; k++) sT[k] = CUE_MIX[cue][k] * musicMaster;
    }

    public synchronized int cue() {
        return cue;
    }

    public synchronized void setMusicVolume(float v) {
        musicMaster = Math.max(0, v);
        if (cue >= 0) setCue(cue);
    }

    /** The old interface: a track number (now a cue) and a volume. */
    public void setMusic(int track, float volume) {
        setMusicVolume(volume);
        setCue(track);
    }

    /** The effects bus and the music bus (0..1 each, the player's settings). */
    public synchronized void setVolumes(float sfx, float music) {
        sfxBus = Math.max(0, sfx);
        musicBus = Math.max(0, music);
    }

    public synchronized void setMono(boolean m) {
        mono = m;
    }

    /** Voices playing now (for tests and the debug line). */
    public synchronized int busy() {
        int n = 0;
        for (int i = 0; i < VOICES; i++) if (vData[i] != null) n++;
        return n;
    }

    private static final float LIMIT = 0.89f, REL = 1f / (0.25f * Synth.RATE), LOOP_GLIDE = 1f / (0.12f * Synth.RATE),
            STEM_IN = 1f / (1.4f * Synth.RATE), STEM_OUT = 1f / (3f * Synth.RATE);

    /** Fills out with frames of interleaved stereo. */
    public void mix(short[] out, int frames) {
        synchronized (this) {
            for (int k = 0; k < Synth.STEMS; k++) if (sData[k] == null) sData[k] = synth.stem(k);
            int stemLen = 0;
            for (int k = 0; k < Synth.STEMS; k++) if (sData[k] != null) stemLen = sData[k].length;
            for (int f = 0; f < frames; f++) {
                float l = 0, r = 0;
                for (int i = 0; i < VOICES; i++) {
                    short[] d = vData[i];
                    if (d == null) continue;
                    float p = vPos[i];
                    int ip = (int) p;
                    if (ip + 1 >= d.length) {
                        vData[i] = null;
                        continue;
                    }
                    float s = d[ip] + (d[ip + 1] - d[ip]) * (p - ip);
                    l += s * vL[i];
                    r += s * vR[i];
                    vPos[i] = p + vStep[i];
                }
                for (int i = 0; i < TAILS; i++) {
                    short[] d = tData[i];
                    if (d == null) continue;
                    int ip = (int) tPos[i];
                    if (ip + 1 >= d.length || tLeft[i] <= 0) {
                        tData[i] = null;
                        tLeft[i] = 0;
                        continue;
                    }
                    float g = tLeft[i] / (float) TAIL_LEN;
                    float s = (d[ip] + (d[ip + 1] - d[ip]) * (tPos[i] - ip)) * g;
                    l += s * tL[i];
                    r += s * tR[i];
                    tPos[i] += tStep[i];
                    tLeft[i]--;
                }
                for (int k = 0; k < LOOPS; k++) {
                    short[] d = lData[k];
                    if (d == null) continue;
                    lL[k] += (lLT[k] - lL[k]) * LOOP_GLIDE;
                    lR[k] += (lRT[k] - lR[k]) * LOOP_GLIDE;
                    if (lL[k] + lR[k] < 1e-5f && lLT[k] + lRT[k] == 0) continue;
                    lStep[k] += (lStepT[k] - lStep[k]) * LOOP_GLIDE;
                    float p = lPos[k];
                    int ip = (int) p;
                    int nx = ip + 1 >= d.length ? 0 : ip + 1;
                    float s = d[ip] + (d[nx] - d[ip]) * (p - ip);
                    l += s * lL[k];
                    r += s * lR[k];
                    p += lStep[k];
                    if (p >= d.length) p -= d.length;
                    lPos[k] = p;
                }
                float m = 0;
                if (stemLen > 0) {
                    int ip = (int) mPos;
                    float fr = mPos - ip;
                    int nx = ip + 1 >= stemLen ? 0 : ip + 1;
                    for (int k = 0; k < Synth.STEMS; k++) {
                        float t = sT[k];
                        float v = sVol[k];
                        if (v != t) {
                            v += (t - v) * (t > v ? STEM_IN : STEM_OUT);
                            if (Math.abs(t - v) < 1e-5f) v = t;
                            sVol[k] = v;
                        }
                        short[] d = sData[k];
                        if (d == null || v < 1e-5f) continue;
                        m += (d[ip] + (d[nx] - d[ip]) * fr) * v;
                    }
                    mPos += 0.5f;
                    if (mPos >= stemLen) mPos -= stemLen;
                }
                l = (l * sfxBus + m * musicBus) * (1 / 32768f);
                r = (r * sfxBus + m * musicBus) * (1 / 32768f);
                if (mono) l = r = (l + r) * 0.5f;
                // the limiter: down at once when too loud, back up slowly
                float pk = Math.max(Math.abs(l), Math.abs(r));
                float want = pk > LIMIT ? LIMIT / pk : 1;
                if (want < limGain) limGain = want;
                else limGain += (want - limGain) * REL;
                l *= limGain;
                r *= limGain;
                out[f * 2] = (short) (soft(l) * 32767);
                out[f * 2 + 1] = (short) (soft(r) * 32767);
            }
        }
    }

    /** Leaves everything under 0.9 alone and rounds off above it (never past full scale). */
    private static float soft(float x) {
        float a = Math.abs(x);
        if (a <= 0.9f) return x;
        float y = 0.9f + 0.1f * (float) Math.tanh((a - 0.9f) * 10);
        return x < 0 ? -y : y;
    }
}
