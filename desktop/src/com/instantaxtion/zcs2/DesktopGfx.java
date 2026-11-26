package com.instantaxtion.zcs2;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/** Gfx on Java2D, for tests, screenshots and the desktop window. */
public final class DesktopGfx implements Gfx {
    final Graphics2D g;
    private final int w, h;
    private final Deque<AffineTransform> stack = new ArrayDeque<AffineTransform>();
    private final Deque<java.awt.Shape> clips = new ArrayDeque<java.awt.Shape>();
    private final Line2D.Float line = new Line2D.Float();
    private final Ellipse2D.Float oval = new Ellipse2D.Float();
    private final Rectangle2D.Float rect = new Rectangle2D.Float();

    public DesktopGfx(Graphics2D g, int w, int h) {
        this.g = g;
        this.w = w;
        this.h = h;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    public int width() { return w; }
    public int height() { return h; }

    public void color(int argb) {
        g.setColor(new Color(argb, true));
    }

    public void fillRect(float x, float y, float ww, float hh) {
        rect.setRect(x, y, ww, hh);
        g.fill(rect);
    }

    public void strokeRect(float x, float y, float ww, float hh, float lw) {
        g.setStroke(new BasicStroke(lw));
        rect.setRect(x, y, ww, hh);
        g.draw(rect);
    }

    public void fillRoundRect(float x, float y, float ww, float hh, float r) {
        g.fill(new RoundRectangle2D.Float(x, y, ww, hh, r * 2, r * 2));
    }

    public void line(float x1, float y1, float x2, float y2, float lw) {
        g.setStroke(new BasicStroke(lw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        line.setLine(x1, y1, x2, y2);
        g.draw(line);
    }

    public void lines(float[] xy, int n, float lw) {
        g.setStroke(new BasicStroke(lw));
        for (int i = 0; i < n; i++) {
            line.setLine(xy[i * 4], xy[i * 4 + 1], xy[i * 4 + 2], xy[i * 4 + 3]);
            g.draw(line);
        }
    }

    public void fillCircle(float cx, float cy, float r) {
        oval.setFrame(cx - r, cy - r, r * 2, r * 2);
        g.fill(oval);
    }

    public void strokeCircle(float cx, float cy, float r, float lw) {
        g.setStroke(new BasicStroke(lw));
        oval.setFrame(cx - r, cy - r, r * 2, r * 2);
        g.draw(oval);
    }

    public void fillPoly(float[] xy, int n) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(xy[0], xy[1]);
        for (int i = 1; i < n; i++) p.lineTo(xy[i * 2], xy[i * 2 + 1]);
        p.closePath();
        g.fill(p);
    }

    public void points(float[] xy, int n, float size) {
        for (int i = 0; i < n; i++) {
            rect.setRect(xy[i * 2] - size / 2, xy[i * 2 + 1] - size / 2, size, size);
            g.fill(rect);
        }
    }

    private Font font(float size, boolean bold) {
        return new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, 1).deriveFont(size);
    }

    public void text(String s, float x, float y, float size, int align, boolean bold) {
        g.setFont(font(size, bold));
        float tw = (float) g.getFontMetrics().getStringBounds(s, g).getWidth();
        if (align == ALIGN_CENTER) x -= tw / 2;
        else if (align == ALIGN_RIGHT) x -= tw;
        g.drawString(s, x, y);
    }

    public float textWidth(String s, float size, boolean bold) {
        g.setFont(font(size, bold));
        return (float) g.getFontMetrics().getStringBounds(s, g).getWidth();
    }

    public void image(GImage img, float dx, float dy, float dw, float dh) {
        BufferedImage b = ((Img) img).img;
        AffineTransform at = new AffineTransform();
        at.translate(dx, dy);
        at.scale(dw / b.getWidth(), dh / b.getHeight());
        g.drawImage(b, at, null);
    }

    public void image(GImage img, float dx, float dy, float dw, float dh, float alpha) {
        if (alpha <= 0) return;
        java.awt.Composite old = g.getComposite();
        g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, Math.min(1, alpha)));
        image(img, dx, dy, dw, dh);
        g.setComposite(old);
    }

    public void textHalo(String s, float x, float y, float size, int align, boolean bold, int halo, float hw) {
        Font f = font(size, bold);
        g.setFont(f);
        float tw = (float) g.getFontMetrics().getStringBounds(s, g).getWidth();
        if (align == ALIGN_CENTER) x -= tw / 2;
        else if (align == ALIGN_RIGHT) x -= tw;
        java.awt.font.TextLayout tl = new java.awt.font.TextLayout(s, f, g.getFontRenderContext());
        java.awt.Shape outline = tl.getOutline(AffineTransform.getTranslateInstance(x, y));
        java.awt.Paint fill = g.getPaint();
        g.setColor(new Color(halo, true));
        g.setStroke(new BasicStroke(hw * 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(outline);
        g.setPaint(fill);
        g.fill(outline);
    }

    public void save() {
        stack.push(g.getTransform());
        clips.push(g.getClip() == null ? new Rectangle2D.Float(-1e6f, -1e6f, 2e6f, 2e6f) : g.getClip());
    }

    public void restore() {
        g.setTransform(stack.pop());
        g.setClip(clips.pop());
    }

    public void translate(float x, float y) { g.translate(x, y); }
    public void scale(float s) { g.scale(s, s); }
    public void rotate(float r) { g.rotate(r); }

    public void clip(float x, float y, float ww, float hh) {
        g.clip(new Rectangle2D.Float(x, y, ww, hh));
    }

    /** An off-screen picture on Java2D. */
    public static final class Img implements GImage {
        final BufferedImage img;

        public Img(int w, int h) {
            img = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        }

        public int width() { return img.getWidth(); }
        public int height() { return img.getHeight(); }

        public Gfx gfx() {
            return new DesktopGfx(img.createGraphics(), img.getWidth(), img.getHeight());
        }

        public void setPixels(int[] argb, int w, int h) {
            // straight into the raster, row by row (setRGB converts each pixel through the colour model)
            int[] dst = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
            int iw = img.getWidth(), cw = Math.min(w, iw), ch = Math.min(h, img.getHeight());
            for (int y = 0; y < ch; y++) System.arraycopy(argb, y * w, dst, y * iw, cw);
        }

        public void updatePixels(int[] argb, int offset, int stride, int x, int y, int w, int h) {
            img.setRGB(x, y, w, h, argb, offset, stride);
        }

        public void recycle() {
        }
    }
}
