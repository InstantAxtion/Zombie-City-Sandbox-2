package com.instantaxtion.zcs2;

import android.graphics.Bitmap;
import android.graphics.Canvas;

/** An off-screen picture as an Android Bitmap. */
final class AndroidImage implements GImage {
    Bitmap bitmap;

    AndroidImage(int w, int h) {
        bitmap = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
    }

    public int width() { return bitmap.getWidth(); }
    public int height() { return bitmap.getHeight(); }

    public Gfx gfx() {
        return new AndroidGfx().set(new Canvas(bitmap), bitmap.getWidth(), bitmap.getHeight());
    }

    public void setPixels(int[] argb, int w, int h) {
        bitmap.setPixels(argb, 0, w, 0, 0, w, h);
    }

    public void recycle() {
        // the hardware renderer may still be using it this frame: let the garbage collector free it
        bitmap = null;
    }
}
