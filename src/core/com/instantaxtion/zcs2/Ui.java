package com.instantaxtion.zcs2;

import java.util.ArrayList;
import java.util.List;

/**
 * A small immediate-mode interface kit: every frame each screen draws its buttons and panels, and each button
 * leaves a hit area behind with an id. A tap goes to the last-drawn (topmost) area under the finger.
 */
public final class Ui {
    public static final int BG = 0xE0141A22, PANEL = 0xF2182029, LINE = 0xFF2E3946, TEXT = 0xFFEDEFF2, DIM = 0xFFA0AAB6,
            ACCENT = 0xFFE0453A, ON = 0xFF2F6FDC, GOOD = 0xFF4FB061, WARN = 0xFFE0A030, SCREEN = 0xFF0E1116, BAR = 0xF0121820;
    /** The type scale, in dp: small, body, large, title. Nothing is drawn smaller than S. */
    public static final float S = 11, M = 13, L = 15, XL = 20;

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
        g.fillRoundRect(x + dp(1), y + dp(2), w, h, dp(12));
        g.color(PANEL);
        g.fillRoundRect(x, y, w, h, dp(12));
        g.color(0x18FFFFFF);
        g.fillRect(x + dp(10), y + dp(0.5f), w - dp(20), dp(1));
        block(x, y, w, h);
    }

    /** The text cut to a width with an ellipsis (allocates only when it has to cut). */
    public static String fit(Gfx g, String s, float maxW, float size, boolean bold) {
        if (s == null) return "";
        if (g.textWidth(s, size, bold) <= maxW) return s;
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (g.textWidth(s.substring(0, mid) + "\u2026", size, bold) <= maxW) lo = mid;
            else hi = mid - 1;
        }
        return lo <= 0 ? "\u2026" : s.substring(0, lo).trim() + "\u2026";
    }

    /** A small rounded tag; returns its width. */
    public float chip(float x, float y, String text, float sizeDp, int bg, int fg, boolean bold) {
        float ts = dp(sizeDp), w = g.textWidth(text, ts, bold) + dp(12), h = ts * 1.75f;
        g.color(bg);
        g.fillRoundRect(x, y, w, h, h / 2);
        g.color(fg);
        g.text(text, x + dp(6), y + h / 2 + ts * 0.36f, ts, Gfx.ALIGN_LEFT, bold);
        return w;
    }

    /** A toolbar button: an icon over a short label. */
    public void iconButton(String id, float x, float y, float w, float h, String icon, String label, boolean on) {
        boolean down = id.equals(pressed);
        if (on || down) {
            g.color(on ? 0xFF24426E : 0xFF2C3746);
            g.fillRoundRect(x, y, w, h, dp(10));
        }
        if (on) {
            g.color(ON);
            g.fillRoundRect(x + w * 0.3f, y + dp(2), w * 0.4f, dp(3), dp(1.5f));
        }
        int col = on ? 0xFFFFFFFF : 0xFFC8D0DA;
        boolean labelled = label != null && label.length() > 0 && h > dp(40);
        float is = Math.min(dp(22), Math.min(w, h) * 0.5f);
        float iy = labelled ? y + h * 0.4f : y + h / 2;
        Icons.draw(g, icon, x + w / 2, iy, is, col);
        if (labelled) {
            float ts = dp(S);
            g.color(col);
            g.text(fit(g, label, w - dp(2), ts, false), x + w / 2, y + h - dp(7), ts, Gfx.ALIGN_CENTER, false);
        }
        hit(id, x, y, w, h);
    }

    /** An on/off switch drawn at the right of a row. */
    public void toggle(String id, float x, float y, float w, float h, boolean on) {
        float tw = dp(44), th = dp(24), tx = x + w - tw, ty = y + (h - th) / 2;
        g.color(on ? ON : 0xFF3A4452);
        g.fillRoundRect(tx, ty, tw, th, th / 2);
        g.color(0xFFFFFFFF);
        g.fillCircle(on ? tx + tw - th / 2 : tx + th / 2, ty + th / 2, th / 2 - dp(3));
        hit(id, x, y, w, h);
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
        // never smaller than the small size: a long label is shortened instead
        float ts = dp(Math.max(textDp, S));
        while (g.textWidth(label, ts, true) > w - dp(10) && ts > dp(S) + 0.5f) ts = Math.max(dp(S), ts * 0.92f);
        g.text(fit(g, label, w - dp(8), ts, true), x + w / 2, y + h / 2 + ts * 0.36f, ts, Gfx.ALIGN_CENTER, true);
        hit(id, x, y, w, h);
    }

    /** A button that does nothing now (greyed, not tappable). */
    public void disabled(float x, float y, float w, float h, String label, float textDp) {
        g.color(0xFF1E252E);
        g.fillRoundRect(x, y, w, h, dp(8));
        g.color(0xFF5E6874);
        float ts = dp(Math.max(textDp, S));
        g.text(fit(g, label, w - dp(8), ts, true), x + w / 2, y + h / 2 + ts * 0.36f, ts, Gfx.ALIGN_CENTER, true);
        block(x, y, w, h);
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
        g.text(fit(g, title, w - dp(24), dp(14), true), x + dp(12), y + dp(22), dp(14), Gfx.ALIGN_LEFT, true);
        if (detail != null) wrap(detail, x + dp(12), y + dp(40), w - dp(24), dp(12), DIM, Math.max(1, (int) ((h - dp(46)) / (dp(12) * 1.3f)) + 1));
        hit(id, x, y, w, h);
    }

    /** The height a card needs for its detail text at a width. */
    public float cardH(String detail, float w) {
        int n = detail == null ? 0 : lines(g, detail, w - dp(24), dp(12));
        return dp(34) + n * dp(12) * 1.3f + dp(14);
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
