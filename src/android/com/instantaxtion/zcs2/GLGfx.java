package com.instantaxtion.zcs2;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gfx on OpenGL ES 2.0, batched: every primitive becomes triangles in one big vertex buffer (position, texture
 * coordinates, colour), drawn in as few calls as possible. Untextured shapes use a 1x1 white texture, so a whole
 * frame of rectangles, circles, lines, dots and text runs through one shader. Transforms are applied on the CPU
 * (changing them never breaks a batch); clipping is a scissor rectangle. Colours are premultiplied, as Android's
 * bitmaps are. Text comes from glyph atlases painted at runtime with android.graphics.Paint.
 * Everything here runs on the GL thread.
 */
final class GLGfx implements Gfx {
    private static final String VS =
            "uniform vec2 u_scale;\n" +
            "attribute vec2 a_pos;\n" +
            "attribute vec2 a_uv;\n" +
            "attribute vec4 a_col;\n" +
            "varying vec2 v_uv;\n" +
            "varying vec4 v_col;\n" +
            "void main() {\n" +
            "  v_uv = a_uv;\n" +
            "  v_col = a_col;\n" +
            "  gl_Position = vec4(a_pos.x * u_scale.x - 1.0, 1.0 - a_pos.y * u_scale.y, 0.0, 1.0);\n" +
            "}\n";
    private static final String FS =
            "precision mediump float;\n" +
            "uniform sampler2D u_tex;\n" +
            "varying vec2 v_uv;\n" +
            "varying vec4 v_col;\n" +
            "void main() {\n" +
            "  gl_FragColor = v_col * texture2D(u_tex, v_uv);\n" +
            "}\n";

    private static final int FLOATS = 8; // x, y, u, v, r, g, b, a
    private static final int MAXV = 24576; // a multiple of 3
    private final float[] buf = new float[MAXV * FLOATS];
    private final FloatBuffer fb;
    private int nv;
    private int program, aPos, aUv, aCol, uScale, uTex;
    private int white;
    private int boundTex = -1;
    private int w, h;
    /** Bumped every time a GL context is (re)created: textures made in an older one are gone. */
    static int contextGen = 1;
    /** Textures to delete (images recycled anywhere): done here, on the GL thread. */
    static final List<Integer> toDelete = new ArrayList<Integer>();

    // current colour (premultiplied)
    private float cr, cg, cb, ca;
    // the transform: x' = a x + c y + tx, y' = b x + d y + ty
    private float ta = 1, tb, tc, td = 1, tx, ty;
    private final float[] stack = new float[6 * 64];
    private final int[] clipStack = new int[4 * 64];
    private int sp;
    private int clipX, clipY, clipW, clipH; // the scissor, in GL window coordinates (y up)
    private boolean clipOn;

    // text
    private final Paint measure = new Paint();
    private static final Typeface BOLD = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    private final Map<Integer, Atlas> atlases = new HashMap<Integer, Atlas>();
    private static final int[] BUCKETS = {12, 15, 18, 22, 27, 33, 40, 48, 58, 72, 90};

    GLGfx() {
        fb = ByteBuffer.allocateDirect(buf.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        measure.setAntiAlias(true);
    }

    /** Call when the GL context is new (start, or after it was lost): makes the shader and the white texture again. */
    void contextCreated() {
        contextGen++;
        program = link(VS, FS);
        aPos = GLES20.glGetAttribLocation(program, "a_pos");
        aUv = GLES20.glGetAttribLocation(program, "a_uv");
        aCol = GLES20.glGetAttribLocation(program, "a_col");
        uScale = GLES20.glGetUniformLocation(program, "u_scale");
        uTex = GLES20.glGetUniformLocation(program, "u_tex");
        Bitmap one = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        one.eraseColor(0xFFFFFFFF);
        white = upload(one, 0);
        one.recycle();
        atlases.clear(); // their textures went with the old context
        synchronized (toDelete) {
            toDelete.clear();
        }
        boundTex = -1;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static int link(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("program: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    /** Uploads a bitmap as a texture (into 'into' if non-zero), clamped and linear (works for any size). */
    static int upload(Bitmap b, int into) {
        int id = into;
        if (id == 0) {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            id = t[0];
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        return id;
    }

    // ------------------------------------------------------------------ frames

    /** Starts a frame of the given size. */
    void begin(int width, int height) {
        w = width;
        h = height;
        synchronized (toDelete) {
            if (!toDelete.isEmpty()) {
                int[] ids = new int[toDelete.size()];
                for (int i = 0; i < ids.length; i++) ids[i] = toDelete.get(i);
                GLES20.glDeleteTextures(ids.length, ids, 0);
                toDelete.clear();
            }
        }
        GLES20.glViewport(0, 0, w, h);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(program);
        GLES20.glUniform2f(uScale, 2f / w, 2f / h);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        boundTex = -1;
        ta = td = 1;
        tb = tc = tx = ty = 0;
        sp = 0;
        clipOn = false;
        nv = 0;
    }

    /** Ends the frame (draws what's left in the batch). */
    void end() {
        flush();
    }

    private void flush() {
        if (nv == 0) return;
        fb.clear();
        fb.put(buf, 0, nv * FLOATS);
        fb.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, FLOATS * 4, fb);
        GLES20.glEnableVertexAttribArray(aPos);
        fb.position(2);
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, FLOATS * 4, fb);
        GLES20.glEnableVertexAttribArray(aUv);
        fb.position(4);
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, FLOATS * 4, fb);
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, nv);
        nv = 0;
    }

    private void tex(int id) {
        if (id == boundTex) return;
        flush();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
        boundTex = id;
    }

    /** Room for n more vertices (flushing if the buffer's full). */
    private void room(int n) {
        if (nv + n > MAXV) flush();
    }

    private void vert(float x, float y, float u, float v, float r, float g, float b, float a) {
        int i = nv * FLOATS;
        buf[i] = ta * x + tc * y + tx;
        buf[i + 1] = tb * x + td * y + ty;
        buf[i + 2] = u;
        buf[i + 3] = v;
        buf[i + 4] = r;
        buf[i + 5] = g;
        buf[i + 6] = b;
        buf[i + 7] = a;
        nv++;
    }

    private void tri(float x1, float y1, float x2, float y2, float x3, float y3) {
        room(3);
        vert(x1, y1, 0, 0, cr, cg, cb, ca);
        vert(x2, y2, 0, 0, cr, cg, cb, ca);
        vert(x3, y3, 0, 0, cr, cg, cb, ca);
    }

    private void quad(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4) {
        room(6);
        vert(x1, y1, 0, 0, cr, cg, cb, ca);
        vert(x2, y2, 0, 0, cr, cg, cb, ca);
        vert(x3, y3, 0, 0, cr, cg, cb, ca);
        vert(x1, y1, 0, 0, cr, cg, cb, ca);
        vert(x3, y3, 0, 0, cr, cg, cb, ca);
        vert(x4, y4, 0, 0, cr, cg, cb, ca);
    }

    /** Circle segments for a radius in screen pixels. */
    private int segs(float r) {
        float s = r * (float) Math.sqrt(Math.abs(ta * td - tb * tc));
        return Math.max(8, Math.min(48, (int) (s * 0.9f)));
    }

    // ------------------------------------------------------------------ Gfx

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    public void color(int argb) {
        float a = (argb >>> 24) / 255f;
        cr = ((argb >> 16) & 255) / 255f * a;
        cg = ((argb >> 8) & 255) / 255f * a;
        cb = (argb & 255) / 255f * a;
        ca = a;
    }

    public void fillRect(float x, float y, float ww, float hh) {
        tex(white);
        quad(x, y, x + ww, y, x + ww, y + hh, x, y + hh);
    }

    public void strokeRect(float x, float y, float ww, float hh, float lw) {
        tex(white);
        float o = lw / 2;
        quad(x - o, y - o, x + ww + o, y - o, x + ww + o, y + o, x - o, y + o);
        quad(x - o, y + hh - o, x + ww + o, y + hh - o, x + ww + o, y + hh + o, x - o, y + hh + o);
        quad(x - o, y + o, x + o, y + o, x + o, y + hh - o, x - o, y + hh - o);
        quad(x + ww - o, y + o, x + ww + o, y + o, x + ww + o, y + hh - o, x + ww - o, y + hh - o);
    }

    public void fillRoundRect(float x, float y, float ww, float hh, float r) {
        r = Math.max(0, Math.min(r, Math.min(ww, hh) / 2));
        if (r < 0.5f) {
            fillRect(x, y, ww, hh);
            return;
        }
        tex(white);
        quad(x + r, y, x + ww - r, y, x + ww - r, y + hh, x + r, y + hh);
        quad(x, y + r, x + r, y + r, x + r, y + hh - r, x, y + hh - r);
        quad(x + ww - r, y + r, x + ww, y + r, x + ww, y + hh - r, x + ww - r, y + hh - r);
        corner(x + r, y + r, r, Math.PI, 1.5 * Math.PI);
        corner(x + ww - r, y + r, r, 1.5 * Math.PI, 2 * Math.PI);
        corner(x + ww - r, y + hh - r, r, 0, 0.5 * Math.PI);
        corner(x + r, y + hh - r, r, 0.5 * Math.PI, Math.PI);
    }

    private void corner(float cx, float cy, float r, double a0, double a1) {
        int n = Math.max(3, segs(r) / 4);
        float px = cx + (float) Math.cos(a0) * r, py = cy + (float) Math.sin(a0) * r;
        for (int i = 1; i <= n; i++) {
            double a = a0 + (a1 - a0) * i / n;
            float qx = cx + (float) Math.cos(a) * r, qy = cy + (float) Math.sin(a) * r;
            tri(cx, cy, px, py, qx, qy);
            px = qx;
            py = qy;
        }
    }

    public void line(float x1, float y1, float x2, float y2, float lw) {
        tex(white);
        float dx = x2 - x1, dy = y2 - y1, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f) {
            fillCircle(x1, y1, lw / 2);
            return;
        }
        float hw = Math.max(0.5f, lw / 2);
        float nx = -dy / len * hw, ny = dx / len * hw;
        // extend by half a width at each end (square caps: close to the round ones on Canvas)
        float ex = dx / len * hw, ey = dy / len * hw;
        quad(x1 - ex + nx, y1 - ey + ny, x2 + ex + nx, y2 + ey + ny, x2 + ex - nx, y2 + ey - ny, x1 - ex - nx, y1 - ey - ny);
    }

    public void lines(float[] xy, int n, float lw) {
        for (int i = 0; i < n; i++) line(xy[i * 4], xy[i * 4 + 1], xy[i * 4 + 2], xy[i * 4 + 3], lw);
    }

    public void fillCircle(float cx, float cy, float r) {
        if (r <= 0) return;
        tex(white);
        int n = segs(r);
        float px = cx + r, py = cy;
        for (int i = 1; i <= n; i++) {
            double a = 2 * Math.PI * i / n;
            float qx = cx + (float) Math.cos(a) * r, qy = cy + (float) Math.sin(a) * r;
            tri(cx, cy, px, py, qx, qy);
            px = qx;
            py = qy;
        }
    }

    public void strokeCircle(float cx, float cy, float r, float lw) {
        if (r <= 0) return;
        tex(white);
        int n = segs(r);
        float ri = Math.max(0, r - lw / 2), ro = r + lw / 2;
        float c0 = 1, s0 = 0;
        for (int i = 1; i <= n; i++) {
            double a = 2 * Math.PI * i / n;
            float c1 = (float) Math.cos(a), s1 = (float) Math.sin(a);
            quad(cx + c0 * ri, cy + s0 * ri, cx + c0 * ro, cy + s0 * ro, cx + c1 * ro, cy + s1 * ro, cx + c1 * ri, cy + s1 * ri);
            c0 = c1;
            s0 = s1;
        }
    }

    public void fillPoly(float[] xy, int n) {
        if (n < 3) return;
        tex(white);
        if (n == 3 || convex(xy, n)) {
            for (int i = 1; i < n - 1; i++) tri(xy[0], xy[1], xy[i * 2], xy[i * 2 + 1], xy[i * 2 + 2], xy[i * 2 + 3]);
            return;
        }
        // a concave shape (the lightning bolt): cut off one ear at a time
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        float area = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += xy[i * 2] * xy[j * 2 + 1] - xy[j * 2] * xy[i * 2 + 1];
        }
        float sign = area >= 0 ? 1 : -1;
        int m = n, guard = 0;
        while (m > 3 && guard++ < n * n) {
            boolean cut = false;
            for (int k = 0; k < m; k++) {
                int a = idx[(k + m - 1) % m], b = idx[k], c = idx[(k + 1) % m];
                float ax = xy[a * 2], ay = xy[a * 2 + 1], bx = xy[b * 2], by = xy[b * 2 + 1], cx = xy[c * 2], cy = xy[c * 2 + 1];
                float cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
                if (cross * sign <= 0) continue; // a reflex corner: not an ear
                boolean inside = false;
                for (int q = 0; q < m && !inside; q++) {
                    int p = idx[q];
                    if (p == a || p == b || p == c) continue;
                    inside = inTri(xy[p * 2], xy[p * 2 + 1], ax, ay, bx, by, cx, cy);
                }
                if (inside) continue;
                tri(ax, ay, bx, by, cx, cy);
                for (int q = k; q < m - 1; q++) idx[q] = idx[q + 1];
                m--;
                cut = true;
                break;
            }
            if (!cut) break;
        }
        if (m >= 3) for (int i = 1; i < m - 1; i++)
            tri(xy[idx[0] * 2], xy[idx[0] * 2 + 1], xy[idx[i] * 2], xy[idx[i] * 2 + 1], xy[idx[i + 1] * 2], xy[idx[i + 1] * 2 + 1]);
    }

    private static boolean convex(float[] xy, int n) {
        int pos = 0, neg = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n, k = (i + 2) % n;
            float cr = (xy[j * 2] - xy[i * 2]) * (xy[k * 2 + 1] - xy[j * 2 + 1]) - (xy[j * 2 + 1] - xy[i * 2 + 1]) * (xy[k * 2] - xy[j * 2]);
            if (cr > 0) pos++;
            else if (cr < 0) neg++;
        }
        return pos == 0 || neg == 0;
    }

    private static boolean inTri(float px, float py, float ax, float ay, float bx, float by, float cx, float cy) {
        float d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by);
        float d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy);
        float d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay);
        boolean neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(neg && pos);
    }

    public void points(float[] xy, int n, float size) {
        tex(white);
        float o = size / 2;
        for (int i = 0; i < n; i++) {
            float x = xy[i * 2], y = xy[i * 2 + 1];
            quad(x - o, y - o, x + o, y - o, x + o, y + o, x - o, y + o);
        }
    }

    public void image(GImage img, float dx, float dy, float dw, float dh) {
        AndroidImage ai = (AndroidImage) img;
        if (ai.needsUpload()) {
            // uploading binds the texture: draw what's batched with the current one first
            flush();
            boundTex = -1;
        }
        int id = ai.texture();
        if (id == 0) return;
        tex(id);
        room(6);
        float a = 1; // images draw as themselves (not tinted)
        vert(dx, dy, 0, 0, a, a, a, a);
        vert(dx + dw, dy, 1, 0, a, a, a, a);
        vert(dx + dw, dy + dh, 1, 1, a, a, a, a);
        vert(dx, dy, 0, 0, a, a, a, a);
        vert(dx + dw, dy + dh, 1, 1, a, a, a, a);
        vert(dx, dy + dh, 0, 1, a, a, a, a);
    }

    // ------------------------------------------------------------------ text

    /** One font size's glyphs, painted into a texture as they're needed. */
    private final class Atlas {
        final int px;
        final boolean bold;
        final Bitmap bmp;
        final Canvas canvas;
        final Paint paint = new Paint();
        int texId;
        boolean dirty = true;
        int penX = 1, penY = 1, rowH;
        final Map<Character, float[]> glyphs = new HashMap<Character, float[]>(); // u0, v0, u1, v1, advance, w, h
        final float ascent;

        Atlas(int px, boolean bold) {
            this.px = px;
            this.bold = bold;
            int side = px <= 33 ? 512 : 1024;
            bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bmp);
            paint.setAntiAlias(true);
            paint.setTextSize(px);
            paint.setTypeface(bold ? BOLD : Typeface.DEFAULT);
            paint.setColor(0xFFFFFFFF);
            ascent = -paint.ascent();
        }

        float[] glyph(char ch) {
            float[] g = glyphs.get(ch);
            if (g != null) return g;
            String s = String.valueOf(ch);
            float adv = paint.measureText(s);
            int gw = (int) Math.ceil(adv) + 2, gh = (int) Math.ceil(ascent + paint.descent()) + 2;
            if (penX + gw >= bmp.getWidth()) {
                penX = 1;
                penY += rowH + 1;
                rowH = 0;
            }
            if (penY + gh >= bmp.getHeight()) {
                // full: start again (rare: only for unusual characters at a big size)
                bmp.eraseColor(0);
                glyphs.clear();
                penX = penY = 1;
                rowH = 0;
            }
            canvas.drawText(s, penX + 1, penY + 1 + ascent, paint);
            float sw = bmp.getWidth(), sh = bmp.getHeight();
            g = new float[]{penX / sw, penY / sh, (penX + gw) / sw, (penY + gh) / sh, adv, gw, gh};
            glyphs.put(ch, g);
            penX += gw + 1;
            rowH = Math.max(rowH, gh);
            dirty = true;
            return g;
        }

        int texture() {
            if (dirty) {
                flush(); // anything already batched with the old texture
                texId = upload(bmp, texId);
                boundTex = texId;
                dirty = false;
            }
            return texId;
        }
    }

    private Atlas atlas(float size, boolean bold) {
        float s = size * (float) Math.sqrt(Math.abs(ta * td - tb * tc));
        int bucket = BUCKETS[BUCKETS.length - 1];
        for (int b : BUCKETS)
            if (b >= s * 0.95f) {
                bucket = b;
                break;
            }
        int key = bucket * 2 + (bold ? 1 : 0);
        Atlas a = atlases.get(key);
        if (a == null) {
            a = new Atlas(bucket, bold);
            atlases.put(key, a);
        }
        return a;
    }

    public void text(String s, float x, float y, float size, int align, boolean bold) {
        if (s == null || s.length() == 0 || size <= 0) return;
        Atlas at = atlas(size, bold);
        // make sure every glyph is in the atlas before drawing any (adding one may re-upload the texture)
        for (int i = 0; i < s.length(); i++) at.glyph(s.charAt(i));
        float k = size / at.px;
        float width = textWidth(s, size, bold);
        if (align == ALIGN_CENTER) x -= width / 2;
        else if (align == ALIGN_RIGHT) x -= width;
        int id = at.texture();
        tex(id);
        float top = y - at.ascent * k;
        float pen = x;
        for (int i = 0; i < s.length(); i++) {
            float[] g = at.glyph(s.charAt(i));
            float gx = pen - k, gy = top - k, gw = g[5] * k, gh = g[6] * k;
            room(6);
            vert(gx, gy, g[0], g[1], cr, cg, cb, ca);
            vert(gx + gw, gy, g[2], g[1], cr, cg, cb, ca);
            vert(gx + gw, gy + gh, g[2], g[3], cr, cg, cb, ca);
            vert(gx, gy, g[0], g[1], cr, cg, cb, ca);
            vert(gx + gw, gy + gh, g[2], g[3], cr, cg, cb, ca);
            vert(gx, gy + gh, g[0], g[3], cr, cg, cb, ca);
            pen += g[4] * k;
        }
    }

    public float textWidth(String s, float size, boolean bold) {
        // measured the way the atlas lays it out (glyph by glyph), so centring matches what's drawn
        Atlas at = atlas(size, bold);
        float k = size / at.px, wsum = 0;
        for (int i = 0; i < s.length(); i++) wsum += at.glyph(s.charAt(i))[4];
        return wsum * k;
    }

    // ------------------------------------------------------------------ transform and clip

    public void save() {
        if (sp >= 64) throw new IllegalStateException("save() nested too deep");
        int i = sp * 6;
        stack[i] = ta;
        stack[i + 1] = tb;
        stack[i + 2] = tc;
        stack[i + 3] = td;
        stack[i + 4] = tx;
        stack[i + 5] = ty;
        int j = sp * 4;
        clipStack[j] = clipOn ? clipX : -1;
        clipStack[j + 1] = clipY;
        clipStack[j + 2] = clipW;
        clipStack[j + 3] = clipH;
        sp++;
    }

    public void restore() {
        if (sp == 0) return;
        sp--;
        int i = sp * 6;
        ta = stack[i];
        tb = stack[i + 1];
        tc = stack[i + 2];
        td = stack[i + 3];
        tx = stack[i + 4];
        ty = stack[i + 5];
        int j = sp * 4;
        boolean on = clipStack[j] >= 0;
        if (on != clipOn || (on && (clipStack[j] != clipX || clipStack[j + 1] != clipY || clipStack[j + 2] != clipW || clipStack[j + 3] != clipH))) {
            flush();
            clipOn = on;
            clipX = clipStack[j];
            clipY = clipStack[j + 1];
            clipW = clipStack[j + 2];
            clipH = clipStack[j + 3];
            applyClip();
        }
    }

    public void translate(float x, float y) {
        tx += ta * x + tc * y;
        ty += tb * x + td * y;
    }

    public void scale(float s) {
        ta *= s;
        tb *= s;
        tc *= s;
        td *= s;
    }

    public void rotate(float r) {
        float c = (float) Math.cos(r), s = (float) Math.sin(r);
        float na = ta * c + tc * s, nb = tb * c + td * s, nc = -ta * s + tc * c, nd = -tb * s + td * c;
        ta = na;
        tb = nb;
        tc = nc;
        td = nd;
    }

    public void clip(float x, float y, float ww, float hh) {
        // the rectangle's bounding box on screen, intersected with the current clip
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        float[] px = {x, x + ww, x + ww, x}, py = {y, y, y + hh, y + hh};
        for (int i = 0; i < 4; i++) {
            float sx = ta * px[i] + tc * py[i] + tx, sy = tb * px[i] + td * py[i] + ty;
            x0 = Math.min(x0, sx);
            y0 = Math.min(y0, sy);
            x1 = Math.max(x1, sx);
            y1 = Math.max(y1, sy);
        }
        int left = Math.max(0, (int) Math.floor(x0)), right = Math.min(w, (int) Math.ceil(x1));
        int top = Math.max(0, (int) Math.floor(y0)), bottom = Math.min(h, (int) Math.ceil(y1));
        // GL's window y runs up from the bottom
        int gx = left, gy = h - bottom, gw = Math.max(0, right - left), gh = Math.max(0, bottom - top);
        if (clipOn) {
            int ax = Math.max(gx, clipX), ay = Math.max(gy, clipY);
            int bx = Math.min(gx + gw, clipX + clipW), by = Math.min(gy + gh, clipY + clipH);
            gx = ax;
            gy = ay;
            gw = Math.max(0, bx - ax);
            gh = Math.max(0, by - ay);
        }
        flush();
        clipOn = true;
        clipX = gx;
        clipY = gy;
        clipW = gw;
        clipH = gh;
        applyClip();
    }

    private void applyClip() {
        if (clipOn) {
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(clipX, clipY, clipW, clipH);
        } else GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }

    // ------------------------------------------------------------------ the self-test

    /**
     * Draws a test pattern and reads it back: true if the GPU drew what was asked. Called once, on the first
     * frame, before the game draws anything; a phone whose driver gets this wrong gets the Canvas renderer.
     */
    boolean selfTest(int width, int height) {
        begin(width, height);
        color(0xFF20C040);
        fillRect(4, 4, 16, 16);
        color(0x80FF0000);
        fillCircle(40, 12, 8);
        end();
        GLES20.glFinish();
        ByteBuffer px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        // (12, 12) in screen pixels: the green square. GL's rows count from the bottom.
        GLES20.glReadPixels(12, height - 1 - 12, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
        int r = px.get(0) & 255, g = px.get(1) & 255, b = px.get(2) & 255;
        boolean green = Math.abs(r - 0x20) < 24 && Math.abs(g - 0xC0) < 24 && Math.abs(b - 0x40) < 24;
        // and the background stayed black
        px.clear();
        GLES20.glReadPixels(width / 2, height / 2, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
        boolean black = (px.get(0) & 255) < 16 && (px.get(1) & 255) < 16 && (px.get(2) & 255) < 16;
        return green && black && GLES20.glGetError() == GLES20.GL_NO_ERROR;
    }
}
