package com.instantaxtion.zcs2;

import android.graphics.Bitmap;
import android.graphics.Canvas;

/**
 * An off-screen picture as an Android Bitmap. It's drawn into with Canvas (map chunks, the region picture); the
 * GPU renderer uploads it as a texture when it's first drawn, again after it changes, and again after the GL
 * context is lost.
 */
final class AndroidImage implements GImage {
    Bitmap bitmap;
    private int texId, texGen;
    private volatile boolean dirty = true;
    /** A changed rectangle still to upload (when only part changed): x0, y0, x1, y1, or x0 > x1 for none. */
    private int dx0 = 1, dy0, dx1 = 0, dy1;

    AndroidImage(int w, int h) {
        bitmap = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
    }

    public int width() { return bitmap != null ? bitmap.getWidth() : 1; }
    public int height() { return bitmap != null ? bitmap.getHeight() : 1; }

    public Gfx gfx() {
        // whoever draws into it changes it
        dirty = true;
        return new AndroidGfx().set(new Canvas(bitmap), bitmap.getWidth(), bitmap.getHeight());
    }

    public void setPixels(int[] argb, int w, int h) {
        bitmap.setPixels(argb, 0, w, 0, 0, w, h);
        dirty = true;
    }

    public void updatePixels(int[] argb, int offset, int stride, int x, int y, int w, int h) {
        if (bitmap == null) return;
        bitmap.setPixels(argb, offset, stride, x, y, w, h);
        synchronized (this) {
            if (dx0 > dx1) {
                dx0 = x;
                dy0 = y;
                dx1 = x + w;
                dy1 = y + h;
            } else {
                dx0 = Math.min(dx0, x);
                dy0 = Math.min(dy0, y);
                dx1 = Math.max(dx1, x + w);
                dy1 = Math.max(dy1, y + h);
            }
        }
    }

    public void getPixels(int[] argb) {
        Bitmap b = bitmap;
        if (b != null) b.getPixels(argb, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
    }

    /** True if drawing it now means uploading it first. */
    boolean needsUpload() {
        return bitmap != null && (dirty || texId == 0 || texGen != GLGfx.contextGen || dx0 <= dx1);
    }

    /** Its texture on the GL thread (0 if it's been recycled), uploading it first if needed. */
    int texture() {
        Bitmap b = bitmap;
        if (b == null) return 0;
        if (texGen != GLGfx.contextGen) {
            texId = 0; // made in a lost context
            texGen = GLGfx.contextGen;
            dirty = true;
        }
        if (dirty || texId == 0) {
            dirty = false;
            synchronized (this) {
                dx0 = 1;
                dx1 = 0;
            }
            texId = GLGfx.upload(b, texId);
        } else if (dx0 <= dx1) {
            // only a part changed: send just that rectangle
            int x0, y0, x1, y1;
            synchronized (this) {
                x0 = dx0; y0 = dy0; x1 = dx1; y1 = dy1;
                dx0 = 1;
                dx1 = 0;
            }
            GLGfx.uploadPart(b, texId, x0, y0, x1 - x0, y1 - y0);
        }
        return texId;
    }

    public void recycle() {
        // the hardware renderer may still be using it this frame: let the garbage collector free the bitmap,
        // and the GL thread delete the texture at the start of its next frame
        bitmap = null;
        if (texId != 0 && texGen == GLGfx.contextGen) {
            synchronized (GLGfx.toDelete) {
                GLGfx.toDelete.add(texId);
            }
        }
        texId = 0;
    }
}
