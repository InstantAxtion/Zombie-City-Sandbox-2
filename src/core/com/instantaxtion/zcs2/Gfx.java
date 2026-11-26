package com.instantaxtion.zcs2;

/**
 * Everything the game draws goes through this: Android's Canvas on a phone, Java2D on a desktop (for tests and
 * screenshots). Colours are ARGB ints. Coordinates are screen pixels after the current transform.
 */
public interface Gfx {
    int ALIGN_LEFT = 0, ALIGN_CENTER = 1, ALIGN_RIGHT = 2;

    int width();

    int height();

    void color(int argb);

    void fillRect(float x, float y, float w, float h);

    void strokeRect(float x, float y, float w, float h, float lineWidth);

    void fillRoundRect(float x, float y, float w, float h, float radius);

    void line(float x1, float y1, float x2, float y2, float lineWidth);

    /** n line segments: xy holds x1, y1, x2, y2 for each. */
    void lines(float[] xy, int n, float lineWidth);

    void fillCircle(float cx, float cy, float r);

    void strokeCircle(float cx, float cy, float r, float lineWidth);

    /** A filled polygon of n points (x0, y0, x1, y1, ...). */
    void fillPoly(float[] xy, int n);

    /** n square dots of the given size, centred on each point. */
    void points(float[] xy, int n, float size);

    void text(String s, float x, float y, float size, int align, boolean bold);

    float textWidth(String s, float size, boolean bold);

    void image(GImage img, float dx, float dy, float dw, float dh);

    /** The picture drawn faded (alpha 0..1): glow sprites, soft shadows, scaled heatmaps. */
    void image(GImage img, float dx, float dy, float dw, float dh, float alpha);

    /** Text with a dark outline round each letter (labels over the map), in the current colour. */
    void textHalo(String s, float x, float y, float size, int align, boolean bold, int haloColor, float haloWidth);

    void save();

    void restore();

    void translate(float x, float y);

    void scale(float s);

    void rotate(float radians);

    void clip(float x, float y, float w, float h);
}
