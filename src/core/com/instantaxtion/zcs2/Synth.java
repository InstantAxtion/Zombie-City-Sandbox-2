package com.instantaxtion.zcs2;

import java.util.HashMap;
import java.util.Map;

/**
 * Every sound in the game, made by code (no audio files): gunfire, explosions, screams, the dead, jets, the news
 * chime, and an ambient score. 22,050 samples a second, mono.
 */
public final class Synth {
    public static final int RATE = 22050;
    private final Rng rng = new Rng(1234);
    private final Map<String, short[]> cache = new HashMap<String, short[]>();

    public synchronized short[] get(String name) {
        short[] s = cache.get(name);
        if (s == null) {
            s = make(name);
            cache.put(name, s);
        }
        return s;
    }

    private short[] make(String name) {
        switch (name) {
            case "pistol": return shot(0.2f, 2200, 0.55f, 180);
            case "rifle": return shot(0.16f, 3200, 0.6f, 140);
            case "mg": return shot(0.1f, 3000, 0.5f, 120);
            case "shotgun": return shot(0.32f, 1400, 0.8f, 110);
            case "sniper": return shot(0.55f, 4200, 0.7f, 160);
            case "cannon": return boom(0.9f, 90, 0.9f);
            case "boom": return boom(1.3f, 70, 1f);
            case "bigboom": return boom(2.6f, 45, 1f);
            case "scream": return voice(0.7f, 620, 1100, 0.5f, true);
            case "hurt": return voice(0.28f, 220, 160, 0.4f, false);
            case "groan": return voice(1.3f, 95, 80, 0.35f, false);
            case "jet": return jet();
            case "news": return chime();
            case "click": return click();
            default: return new short[1];
        }
    }

    private short[] buf(float seconds) {
        return new short[(int) (seconds * RATE)];
    }

    private static short clip(float v) {
        if (v > 1) v = 1;
        if (v < -1) v = -1;
        return (short) (v * 32000);
    }

    /** A gunshot: a crack of bright noise and a thump, fading fast. */
    private short[] shot(float len, float bright, float vol, float thumpHz) {
        short[] s = buf(len);
        float lp = 0, a = bright / RATE;
        for (int i = 0; i < s.length; i++) {
            float t = i / (float) RATE;
            float env = (float) Math.exp(-t * 28);
            float n = rng.nextFloat() * 2 - 1;
            lp += (n - lp) * Math.min(1, a * 6);
            float thump = (float) Math.sin(2 * Math.PI * thumpHz * t * (1 - t)) * (float) Math.exp(-t * 18);
            float tail = (float) Math.exp(-t * 6) * lp * 0.25f;
            s[i] = clip((lp * env + thump * 0.7f + tail) * vol);
        }
        return s;
    }

    /** An explosion: deep noise with a slow tail. */
    private short[] boom(float len, float hz, float vol) {
        short[] s = buf(len);
        float lp = 0, lp2 = 0;
        for (int i = 0; i < s.length; i++) {
            float t = i / (float) RATE;
            float env = t < 0.01f ? t / 0.01f : (float) Math.exp(-t * (3.5f / len));
            float n = rng.nextFloat() * 2 - 1;
            lp += (n - lp) * 0.08f;
            lp2 += (lp - lp2) * 0.15f;
            float sub = (float) Math.sin(2 * Math.PI * hz * t * (1 - t * 0.3f)) * (float) Math.exp(-t * 3);
            s[i] = clip((lp2 * 3.2f + sub * 0.8f) * env * vol);
        }
        return s;
    }

    /** A voice-like sound: a buzzing source through a moving formant. */
    private short[] voice(float len, float f0, float f1, float vol, boolean scream) {
        short[] s = buf(len);
        float ph = 0, bp1 = 0, bp2 = 0;
        for (int i = 0; i < s.length; i++) {
            float t = i / (float) RATE, u = t / len;
            float f = f0 + (f1 - f0) * u + (scream ? (float) Math.sin(t * 38) * 25 : (float) Math.sin(t * 7) * 6);
            ph += f / RATE;
            float src = (ph % 1f) * 2 - 1 + (rng.nextFloat() - 0.5f) * (scream ? 0.4f : 0.8f);
            // a crude resonant band-pass round 900 Hz (scream) or 400 Hz (groan)
            float fc = scream ? 0.22f : 0.1f;
            bp1 += (src - bp1 - bp2 * 0.6f) * fc;
            bp2 += bp1 * fc;
            float env = Math.min(1, u * 8) * Math.min(1, (1 - u) * 4);
            s[i] = clip(bp2 * env * vol * 1.4f);
        }
        return s;
    }

    private short[] jet() {
        float len = 3.2f;
        short[] s = buf(len);
        float lp = 0;
        for (int i = 0; i < s.length; i++) {
            float t = i / (float) RATE, u = t / len;
            float env = (float) Math.sin(Math.PI * u);
            env *= env;
            float n = rng.nextFloat() * 2 - 1;
            lp += (n - lp) * (0.05f + 0.25f * env);
            s[i] = clip(lp * env * 1.6f);
        }
        return s;
    }

    private short[] chime() {
        short[] s = buf(0.7f);
        for (int i = 0; i < s.length; i++) {
            float t = i / (float) RATE;
            float f = t < 0.25f ? 880 : 660;
            float tt = t < 0.25f ? t : t - 0.25f;
            s[i] = clip((float) Math.sin(2 * Math.PI * f * t) * (float) Math.exp(-tt * 6) * 0.35f);
        }
        return s;
    }

    private short[] click() {
        short[] s = buf(0.025f);
        for (int i = 0; i < s.length; i++) s[i] = clip((float) Math.sin(i * 0.9f) * (1 - i / (float) s.length) * 0.3f);
        return s;
    }

    /**
     * The ambient score: a low drone under slow minor chords, with a heartbeat pulse. 40 seconds, looping.
     * tension 0..1 makes it darker and faster.
     */
    public short[] music(float tension) {
        float len = 40;
        short[] s = buf(len);
        float[][] chords = {{55f, 65.4f, 82.4f}, {49f, 58.3f, 73.4f}, {43.7f, 55f, 65.4f}, {41.2f, 49f, 61.7f}};
        float lp = 0;
        for (int i = 0; i < s.length; i++) {
            float t = i / (float) RATE;
            int c = (int) (t / 10) % chords.length;
            float within = (t % 10) / 10;
            float fade = Math.min(1, within * 4) * Math.min(1, (1 - within) * 4) * 0.7f + 0.3f;
            float v = 0;
            for (int k = 0; k < 3; k++) {
                float f = chords[c][k] * 2;
                v += (float) Math.sin(2 * Math.PI * f * t + Math.sin(t * 0.3 + k) * 0.5) * 0.18f;
                v += (float) Math.sin(2 * Math.PI * f * 2.003f * t) * 0.05f;
            }
            float drone = (float) Math.sin(2 * Math.PI * 27.5f * t) * 0.22f;
            float n = rng.nextFloat() * 2 - 1;
            lp += (n - lp) * 0.01f;
            float beatRate = 0.9f + tension * 0.8f;
            float bt = (t * beatRate) % 1f;
            float beat = (float) Math.sin(2 * Math.PI * 50 * bt) * (float) Math.exp(-bt * 14) * 0.35f * tension;
            float bt2 = (t * beatRate + 0.22f) % 1f;
            beat += (float) Math.sin(2 * Math.PI * 45 * bt2) * (float) Math.exp(-bt2 * 16) * 0.25f * tension;
            s[i] = clip((v * fade + drone + lp * 0.6f + beat) * 0.55f);
        }
        return s;
    }
}
