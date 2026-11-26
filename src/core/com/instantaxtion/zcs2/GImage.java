package com.instantaxtion.zcs2;

/** An off-screen picture (map chunks, the region map). */
public interface GImage {
    int width();

    int height();

    /** Something to draw into it with. */
    Gfx gfx();

    /** Replaces its pixels (ARGB, row by row). */
    void setPixels(int[] argb, int w, int h);

    void recycle();
}
