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
        float dp = cam.dp, t = view.time;
        if (snowCover > 0.05f && cam.zoom > 0.1f) {
            g.color(((int) (snowCover * 60)) << 24 | 0xF0F4FF);
            g.fillRect(0, 0, W, H);
        }
        if (state == RAIN || state == STORM || state == CLOUDY) {
            // a heavier sky
            g.color(state == STORM ? 0x50101824 : state == RAIN ? 0x34142030 : 0x18202830);
            g.fillRect(0, 0, W, H);
        }
        if (state == FOG) {
            // a haze, and banks of it drifting across
            float far = cam.zoom < 0.2f ? 0.5f : 1;
            g.color(MapPainter.alpha(0xFFC4C8CC, 0.35f * far));
            g.fillRect(0, 0, W, H);
            float s = Math.max(W, H) * 0.7f;
            for (int i = 0; i < 7; i++) {
                float x = ((Rng.unit(91, i, 1) * (W + s) + t * (8 + i * 3) * dp) % (W + s)) - s / 2;
                float y = Rng.unit(91, i, 2) * H;
                view.glow(g, view.glowWhite, x, y, s * (0.6f + Rng.unit(91, i, 3) * 0.6f), 0.5f * far);
            }
        }
        if (wet() || state == SNOW) {
            // streaks (or flakes) over the screen, slanted by the wind
            boolean snow = state == SNOW;
            int n = state == STORM ? 260 : snow ? 160 : 150;
            float far = cam.zoom < 0.15f ? 0.5f : 1;
            float len = (state == STORM ? 22 : 16) * dp, slant = state == STORM ? 0.35f : 0.18f;
            g.color(snow ? 0xD8FFFFFF : state == STORM ? (int) (0x90 * far) << 24 | 0xC0D4EC : (int) (0x78 * far) << 24 | 0xB8CCE4);
            for (int i = 0; i < n; i++) {
                float sp = 0.7f + Rng.unit(77, i, 3) * 0.6f;
                if (snow) {
                    float x = (Rng.unit(77, i, 1) * W + t * 14 * dp * sp + (float) Math.sin(t * 1.3f + i) * 6 * dp) % W;
                    float y = (Rng.unit(77, i, 2) * H + t * 38 * dp * sp) % H;
                    g.fillCircle(x < 0 ? x + W : x, y, (1 + Rng.unit(77, i, 4)) * dp);
                } else {
                    float x = (Rng.unit(77, i, 1) * W + t * 260 * dp * slant * sp) % W;
                    float y = (Rng.unit(77, i, 2) * H + t * 900 * dp * sp) % H;
                    g.line(x, y, x - len * slant, y + len, Math.max(1, 0.8f * dp));
                }
            }
        }
        if (state == STORM) {
            // lightning: a flash every few seconds, and the bolt
            float period = 7f;
            int k = (int) (t / period);
            float ph = t - k * period - Rng.unit(55, k, 0) * 4;
            if (ph >= 0 && ph < 0.35f) {
                float a = ph < 0.08f ? 1 : (0.35f - ph) / 0.27f;
                g.color(MapPainter.alpha(0xFFE8EEFF, a * 0.45f));
                g.fillRect(0, 0, W, H);
                g.color(MapPainter.alpha(0xFFF4F8FF, a));
                float x = Rng.unit(55, k, 1) * W, y = 0;
                for (int s = 0; s < 7; s++) {
                    float nx = x + (Rng.unit(55, k, 10 + s) - 0.5f) * 60 * dp, ny = y + H * 0.08f;
                    g.line(x, y, nx, ny, 2.2f * dp);
                    x = nx;
                    y = ny;
                }
            }
        }
    }
}
