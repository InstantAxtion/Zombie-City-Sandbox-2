package com.instantaxtion.zcs2;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

/** Gfx on Android's Canvas (hardware accelerated when it's the view's canvas). */
final class AndroidGfx implements Gfx {
    private Canvas c;
    private int w, h;
    private final Paint fill = new Paint(), stroke = new Paint(), text = new Paint(), bmp = new Paint(), pts = new Paint();
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private static final Typeface BOLD = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);

    AndroidGfx() {
        fill.setStyle(Paint.Style.FILL);
        fill.setAntiAlias(true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setAntiAlias(true);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setAntiAlias(true);
        text.setSubpixelText(true);
        bmp.setFilterBitmap(true);
        pts.setStrokeCap(Paint.Cap.SQUARE);
    }

    AndroidGfx set(Canvas canvas, int width, int height) {
        c = canvas;
        w = width;
        h = height;
        return this;
    }

    public int width() { return w; }
    public int height() { return h; }

    public void color(int argb) {
        fill.setColor(argb);
        stroke.setColor(argb);
        text.setColor(argb);
        pts.setColor(argb);
    }

    public void fillRect(float x, float y, float ww, float hh) {
        c.drawRect(x, y, x + ww, y + hh, fill);
    }

    public void strokeRect(float x, float y, float ww, float hh, float lw) {
        stroke.setStrokeWidth(lw);
        c.drawRect(x, y, x + ww, y + hh, stroke);
    }

    public void fillRoundRect(float x, float y, float ww, float hh, float r) {
        rect.set(x, y, x + ww, y + hh);
        c.drawRoundRect(rect, r, r, fill);
    }

    public void line(float x1, float y1, float x2, float y2, float lw) {
        stroke.setStrokeWidth(lw);
        c.drawLine(x1, y1, x2, y2, stroke);
    }

    public void lines(float[] xy, int n, float lw) {
        stroke.setStrokeWidth(lw);
        c.drawLines(xy, 0, n * 4, stroke);
    }

    public void fillCircle(float cx, float cy, float r) {
        c.drawCircle(cx, cy, r, fill);
    }

    public void strokeCircle(float cx, float cy, float r, float lw) {
        stroke.setStrokeWidth(lw);
        c.drawCircle(cx, cy, r, stroke);
    }

    public void fillPoly(float[] xy, int n) {
        path.reset();
        path.moveTo(xy[0], xy[1]);
        for (int i = 1; i < n; i++) path.lineTo(xy[i * 2], xy[i * 2 + 1]);
        path.close();
        c.drawPath(path, fill);
    }

    public void points(float[] xy, int n, float size) {
        pts.setStrokeWidth(size);
        c.drawPoints(xy, 0, n * 2, pts);
    }

    public void text(String s, float x, float y, float size, int align, boolean bold) {
        text.setTextSize(size);
        text.setTypeface(bold ? BOLD : Typeface.DEFAULT);
        text.setTextAlign(align == ALIGN_CENTER ? Paint.Align.CENTER : align == ALIGN_RIGHT ? Paint.Align.RIGHT : Paint.Align.LEFT);
        c.drawText(s, x, y, text);
    }

    public float textWidth(String s, float size, boolean bold) {
        text.setTextSize(size);
        text.setTypeface(bold ? BOLD : Typeface.DEFAULT);
        return text.measureText(s);
    }

    public void image(GImage img, float dx, float dy, float dw, float dh) {
        Bitmap b = ((AndroidImage) img).bitmap;
        if (b == null || b.isRecycled()) return;
        rect.set(dx, dy, dx + dw, dy + dh);
        c.drawBitmap(b, null, rect, bmp);
    }

    public void save() { c.save(); }
    public void restore() { c.restore(); }
    public void translate(float x, float y) { c.translate(x, y); }
    public void scale(float s) { c.scale(s, s); }
    public void rotate(float r) { c.rotate((float) Math.toDegrees(r)); }

    public void clip(float x, float y, float ww, float hh) {
        c.clipRect(x, y, x + ww, y + hh);
    }
}
