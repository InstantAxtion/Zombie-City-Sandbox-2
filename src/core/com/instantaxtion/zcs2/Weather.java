package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The calendar, the seasons and the weather. Cold slows the dead and freezes them in winter; heat rots them;
 * rain and storms muffle noise and shorten sight; fog shortens it more; snow covers the ground.
 */
public final class Weather extends Module {
    public static final int CLEAR = 0, CLOUDY = 1, RAIN = 2, STORM = 3, FOG = 4, SNOW = 5;
    public static final String[] NAMES = {"Clear", "Cloudy", "Rain", "Storm", "Fog", "Snow"};
    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August",
            "September", "October", "November", "December"};
    private static final int[] MDAYS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    /** Day of the year the game starts on (0 = 1 January). */
    public int startDay = 243; // 1 September
    public int state = CLEAR;
    public float tempOffset; // the weather's own warmth or chill
    private float hourT;
    /** Snow lying on the ground, 0..1 (for drawing). */
    public float snowCover;

    public Weather(World w) {
        super(w);
    }

    @Override
    public String id() {
        return "weather";
    }

    public int dayOfYear() {
        return (startDay + w.day() - 1) % 365;
    }

    public String date() {
        int d = dayOfYear();
        int m = 0;
        while (d >= MDAYS[m]) {
            d -= MDAYS[m];
            m++;
        }
        return (d + 1) + " " + MONTHS[m];
    }

    /** Degrees C now: the season, the time of day, the weather. */
    public float temperature() {
        double season = -Math.cos((dayOfYear() - 15) / 365.0 * 2 * Math.PI); // -1 mid January, +1 mid July
        float mean = 11 + (float) season * 13;
        float h = w.hour();
        float daily = (float) -Math.cos((h - 3) / 24.0 * 2 * Math.PI) * 5;
        return mean + daily + tempOffset;
    }

    /** How fast the dead can move now (1 = normal). */
    public float zombieSpeed() {
        float t = temperature();
        float cold = w.c.rule("coldSpeed", 0.7f);
        if (t < -10) return cold * 0.7f;
        if (t < 0) return cold;
        return 1;
    }

    /** How far anyone can see (1 = normal). */
    public float sight() {
        switch (state) {
            case FOG: return 0.5f;
            case RAIN: return 0.8f;
            case STORM: return 0.65f;
            case SNOW: return 0.75f;
            default: return 1;
        }
    }

    /** How far noise carries (1 = normal). */
    public float noise() {
        return state == STORM ? 0.6f : state == RAIN ? 0.8f : state == SNOW ? 0.85f : 1;
    }

    public boolean wet() {
        return state == RAIN || state == STORM;
    }

    @Override
    public void update(float dt) {
        hourT += dt;
        // the weather changes by the game hour (60 s)
        while (hourT >= 60) {
            hourT -= 60;
            change();
        }
        float t = temperature();
        if (state == SNOW) snowCover = Math.min(1, snowCover + dt / 600f);
        else if (t > 2) snowCover = Math.max(0, snowCover - dt / 900f);
        // the heat rots the dead (counted ones; individuals lose health in the same proportion)
        if (t > 28) {
            float rot = w.c.rule("rotPerHour", 0.004f) * dt / 60f;
            Sectors sec = w.sec;
            for (int s = 0; s < sec.n; s++) {
                int z = sec.zIn[s];
                if (z <= 0) continue;
                int lost = (int) (z * rot) + (Rng.unit(w.seed, w.tick, s * 3L + 7) < (z * rot) % 1f ? 1 : 0);
                if (lost > 0) {
                    sec.zIn[s] -= Math.min(z, lost);
                    w.stats.rotted += Math.min(z, lost);
                }
            }
        }
    }

    private void change() {
        Rng r = w.rng;
        float t = temperature();
        boolean winter = t < 3;
        float roll = r.nextFloat();
        switch (state) {
            case CLEAR: state = roll < 0.25f ? CLOUDY : roll < 0.3f ? FOG : CLEAR; break;
            case CLOUDY: state = roll < 0.25f ? (winter ? SNOW : RAIN) : roll < 0.45f ? CLEAR : CLOUDY; break;
            case RAIN: state = roll < 0.12f ? STORM : roll < 0.4f ? CLOUDY : RAIN; break;
            case STORM: state = roll < 0.5f ? RAIN : STORM; break;
            case FOG: state = roll < 0.4f ? CLEAR : roll < 0.5f ? CLOUDY : FOG; break;
            case SNOW: state = roll < 0.3f ? CLOUDY : SNOW; break;
            default: state = CLEAR;
        }
        if (state == SNOW && !winter) state = RAIN;
        if (state == RAIN && t < 0) state = SNOW;
        tempOffset += (r.nextFloat() - 0.5f) * 1.5f;
        tempOffset *= 0.92f;
        tempOffset += state == CLEAR ? 0.3f : state == STORM ? -0.5f : 0;
    }

    @Override
    public void save(DataOutputStream o) throws IOException {
        o.writeInt(startDay);
        o.writeInt(state);
        o.writeFloat(tempOffset);
        o.writeFloat(hourT);
        o.writeFloat(snowCover);
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        startDay = in.readInt();
        state = in.readInt();
        tempOffset = in.readFloat();
        hourT = in.readFloat();
        snowCover = in.readFloat();
    }

    @Override
    public String status() {
        return date() + "  " + Math.round(temperature()) + "°C " + NAMES[state];
    }

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        int W = g.width(), H = g.height();
        if (snowCover > 0.05f && cam.zoom > 0.1f) {
            g.color(((int) (snowCover * 70)) << 24 | 0xF0F4FF);
            g.fillRect(0, 0, W, H);
        }
        if (state == FOG) {
            g.color(0x50C8CCD0);
            g.fillRect(0, 0, W, H);
        }
        if ((wet() || state == SNOW) && cam.zoom > 0.15f) {
            // streaks (or flakes) over the screen
            int n = state == STORM ? 160 : 90;
            float t = view.time;
            g.color(state == SNOW ? 0xC0FFFFFF : 0x60B0C8E0);
            for (int i = 0; i < n; i++) {
                float x = (Rng.unit(77, i, 1) * W + t * (state == SNOW ? 20 : 120)) % W;
                float y = (Rng.unit(77, i, 2) * H + t * (state == SNOW ? 60 : 900)) % H;
                if (state == SNOW) g.fillCircle(x, y, 1.6f);
                else g.line(x, y, x - 3, y + 14, 1);
            }
        }
    }
}
