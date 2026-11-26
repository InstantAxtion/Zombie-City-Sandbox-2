package com.instantaxtion.zcs2;

import java.util.ArrayList;
import java.util.List;

/**
 * A small immediate-mode interface kit: every frame each screen draws its buttons and panels, and each button
 * leaves a hit area behind with an id. A tap goes to the last-drawn (topmost) area under the finger.
 */
public final class Ui {
    public static final int BG = 0xE0141A22, PANEL = 0xF01C232D, LINE = 0xFF2E3946, TEXT = 0xFFEDEFF2, DIM = 0xFF9AA4B0,
            ACCENT = 0xFFE0453A, ON = 0xFF2F6FDC, GOOD = 0xFF4FB061, WARN = 0xFFE0A030;

    private static final class Hit {
        String id;
        float x, y, w, h;
    }

    private final List<Hit> hits = new ArrayList<Hit>();
    private int used;
    public float d = 2; // pixels per dp
    public String pressed;
    public Gfx g;

    public void begin(Gfx g, float density) {
        this.g = g;
        d = density;
        used = 0;
    }

    private void hit(String id, float x, float y, float w, float h) {
        Hit t;
        if (used < hits.size()) t = hits.get(used);
        else {
            t = new Hit();
            hits.add(t);
        }
        used++;
        t.id = id;
        t.x = x;
        t.y = y;
        t.w = w;
        t.h = h;
    }

    /** Marks an area as taken (so taps on a panel don't fall through to the map). */
    public void block(float x, float y, float w, float h) {
        hit("", x, y, w, h);
    }

    /** A hit area with no drawing. */
    public void area(String id, float x, float y, float w, float h) {
        hit(id, x, y, w, h);
    }

    /** The middle of the hit area with this id (for scripted taps in tests), or null. */
    public float[] find(String id) {
        for (int i = used - 1; i >= 0; i--) {
            Hit t = hits.get(i);
            if (t.id.equals(id)) return new float[]{t.x + t.w / 2, t.y + t.h / 2};
        }
        return null;
    }

    public String at(float x, float y) {
        for (int i = used - 1; i >= 0; i--) {
            Hit t = hits.get(i);
            if (x >= t.x && y >= t.y && x < t.x + t.w && y < t.y + t.h) return t.id;
        }
        return null;
    }

    public float dp(float v) {
        return v * d;
    }

    public void panel(float x, float y, float w, float h) {
        g.color(0x60000000);
        g.fillRoundRect(x + dp(1), y + dp(2), w, h, dp(10));
        g.color(PANEL);
        g.fillRoundRect(x, y, w, h, dp(10));
        block(x, y, w, h);
    }

    public void button(String id, float x, float y, float w, float h, String label, boolean on) {
        button(id, x, y, w, h, label, on, 14);
    }

    public void button(String id, float x, float y, float w, float h, String label, boolean on, float textDp) {
        boolean down = id.equals(pressed);
        g.color(on ? ON : down ? 0xFF3A4656 : 0xFF283240);
        g.fillRoundRect(x, y, w, h, dp(8));
        if (!on) {
            g.color(LINE);
            g.fillRect(x + dp(6), y + h - dp(1), w - dp(12), dp(1));
        }
        g.color(TEXT);
        float ts = dp(textDp);
        while (g.textWidth(label, ts, true) > w - dp(8) && ts > dp(8)) ts *= 0.9f;
        g.text(label, x + w / 2, y + h / 2 + ts * 0.36f, ts, Gfx.ALIGN_CENTER, true);
        hit(id, x, y, w, h);
    }

    /** A big call-to-action button. */
    public void bigButton(String id, float x, float y, float w, float h, String label, int color) {
        boolean down = id.equals(pressed);
        g.color(down ? MapPainter.shade(color, 0.8f) : color);
        g.fillRoundRect(x, y, w, h, dp(10));
        g.color(0xFFFFFFFF);
        float ts = dp(17);
        g.text(label, x + w / 2, y + h / 2 + ts * 0.36f, ts, Gfx.ALIGN_CENTER, true);
        hit(id, x, y, w, h);
    }

    /** A tappable row with a title and a line of detail. */
    public void card(String id, float x, float y, float w, float h, String title, String detail, boolean on) {
        boolean down = id.equals(pressed);
        g.color(on ? 0xFF24426E : down ? 0xFF2C3746 : 0xFF222B36);
        g.fillRoundRect(x, y, w, h, dp(8));
        if (on) {
            g.color(ON);
            g.fillRoundRect(x, y, dp(4), h, dp(2));
        }
        g.color(TEXT);
        g.text(title, x + dp(12), y + dp(20), dp(14), Gfx.ALIGN_LEFT, true);
        if (detail != null) wrap(detail, x + dp(12), y + dp(37), w - dp(24), dp(11.5f), DIM, 3);
        hit(id, x, y, w, h);
    }

    /** Draws text wrapped to a width; returns the height used. */
    public float wrap(String text, float x, float y, float w, float size, int color, int maxLines) {
        g.color(color);
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        int lines = 0;
        float lh = size * 1.3f;
        for (int i = 0; i < words.length; i++) {
            String tryLine = line.length() == 0 ? words[i] : line + " " + words[i];
            if (g.textWidth(tryLine, size, false) > w && line.length() > 0) {
                if (lines == maxLines - 1) {
                    g.text(line + "...", x, y + lines * lh, size, Gfx.ALIGN_LEFT, false);
                    return (lines + 1) * lh;
                }
                g.text(line.toString(), x, y + lines * lh, size, Gfx.ALIGN_LEFT, false);
                lines++;
                line.setLength(0);
                line.append(words[i]);
            } else {
                line.setLength(0);
                line.append(tryLine);
            }
        }
        if (line.length() > 0) {
            g.text(line.toString(), x, y + lines * lh, size, Gfx.ALIGN_LEFT, false);
            lines++;
        }
        return lines * lh;
    }

    public static int lines(Gfx g, String text, float w, float size) {
        String[] words = text.split(" ");
        float x = 0;
        int lines = 1;
        float space = g.textWidth(" ", size, false);
        for (String word : words) {
            float ww = g.textWidth(word, size, false);
            if (x > 0 && x + ww > w) {
                lines++;
                x = 0;
            }
            x += ww + space;
        }
        return lines;
    }

    public void label(String s, float x, float y, float sizeDp, int color, boolean bold) {
        g.color(color);
        g.text(s, x, y, dp(sizeDp), Gfx.ALIGN_LEFT, bold);
    }

    public void title(String s, float x, float y) {
        g.color(TEXT);
        g.text(s, x, y, dp(20), Gfx.ALIGN_LEFT, true);
    }
}
