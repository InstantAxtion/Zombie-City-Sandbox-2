package com.instantaxtion.zcs2;

/**
 * Mixes sound effects and the music into a stereo stream. The platform's audio thread calls mix(); the game
 * calls play() and setMusic() from its own thread (they only touch the voice table under a lock).
 */
public final class Mixer {
    private static final int VOICES = 24;
    private final short[][] data = new short[VOICES][];
    private final int[] pos = new int[VOICES];
    private final float[] volL = new float[VOICES], volR = new float[VOICES];
    private final Synth synth;
    private short[] music;
    private int musicPos;
    private float musicVol, musicTarget;

    public Mixer(Synth synth) {
        this.synth = synth;
    }

    public void play(String name, float volume, float pan) {
        short[] s = synth.get(name);
        if (s == null || s.length < 2) return;
        synchronized (this) {
            // a free voice, or the quietest one nearest its end
            int v = -1;
            float best = Float.MAX_VALUE;
            for (int i = 0; i < VOICES; i++) {
                if (data[i] == null) {
                    v = i;
                    break;
                }
                float left = (data[i].length - pos[i]) * (volL[i] + volR[i]);
                if (left < best) {
                    best = left;
                    v = i;
                }
            }
            data[v] = s;
            pos[v] = 0;
            float p = Math.max(-1, Math.min(1, pan));
            volL[v] = volume * Math.min(1, 1 - p);
            volR[v] = volume * Math.min(1, 1 + p);
        }
    }

    public void setMusic(short[] track, float volume) {
        synchronized (this) {
            if (track != null && track != music) {
                music = track;
                musicPos = 0;
            }
            musicTarget = volume;
        }
    }

    /** Fills out with frames of interleaved stereo. */
    public void mix(short[] out, int frames) {
        synchronized (this) {
            for (int f = 0; f < frames; f++) {
                float l = 0, r = 0;
                if (music != null && (musicVol > 0.001f || musicTarget > 0)) {
                    musicVol += (musicTarget - musicVol) * 0.0001f;
                    float m = music[musicPos] / 32768f * musicVol;
                    l += m;
                    r += m;
                    if (++musicPos >= music.length) musicPos = 0;
                }
                for (int i = 0; i < VOICES; i++) {
                    short[] d = data[i];
                    if (d == null) continue;
                    float s = d[pos[i]] / 32768f;
                    l += s * volL[i];
                    r += s * volR[i];
                    if (++pos[i] >= d.length) data[i] = null;
                }
                // a soft limit instead of hard clipping
                l = l / (1 + Math.abs(l) * 0.6f);
                r = r / (1 + Math.abs(r) * 0.6f);
                out[f * 2] = (short) (l * 32000);
                out[f * 2 + 1] = (short) (r * 32000);
            }
        }
    }
}
