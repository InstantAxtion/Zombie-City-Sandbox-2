package com.instantaxtion.zcs2;

import java.io.IOException;
import java.io.OutputStream;

/**
 * An animated GIF (GIF89a) written frame by frame, in plain Java, kept small:
 * <ul>
 * <li>The first frame sets a global colour table (its exact colours when it has 255 or fewer, else a median cut),
 * which later frames use where it fits.</li>
 * <li>Each later frame is only the rectangle that differs from what's shown, its unchanged pixels transparent,
 * drawn over what's shown (disposal: leave in place). A frame whose changed pixels have colours the global table
 * hasn't got gets its own table: exact when they're 255 or fewer, else a median cut of them, unless the global
 * table is close enough (many-colour footage keeps the global one, so still parts stay still).</li>
 * <li>The pixels are LZW-compressed the way the format's decoders expect (variable code width from the minimum code
 * size + 1 up to 12 bits, a clear code when the table fills). In many-colour frames the coding is lossy, as
 * gifsicle's --lossy: a pixel may take a near colour that makes a longer match, and pixels near what's shown are left;
 * every pixel stays within a small error of its frame in every channel, and an error that grows is sent again.</li>
 * </ul>
 * There's no dithering: it would make still parts of the picture change from frame to frame and cost far more than
 * it shows. A frame of 255 colours or fewer comes back exactly. It loops forever. Delays are in hundredths of a second;
 * {@link #addFrame(int[], double)} carries the rounding over so a 15 fps clip keeps its length.
 */
final class GifEncoder {
    private final OutputStream out;
    private final int w, h;
    private int frames;
    private double owedCs;
    private boolean finished;
    /** Each pixel as the decoder shows it now (RGB). */
    private final int[] shown;
    /** Each pixel's entry (or the transparent one) in the frame being written. */
    private final byte[] idx, sub;
    /** The global colour table (from the first frame), how many, and its colours' entries. */
    private final int[] gPal = new int[256];
    private int gN, gBits;
    private final ColourMap gMap = new ColourMap();
    /** The global table's nearest entry for each 15-bit colour (-1 until it's needed). */
    private short[] gLut;
    private final int[] lPal = new int[256];
    /** The colour each pixel of the rectangle being written should be (for the lossy coder). */
    private final int[] want, was;
    private final ColourMap lMap = new ColourMap();

    /** The mean error (|dr| + |dg| + |db|) up to which a many-colour frame keeps the global table. */
    static final int GLOBAL_FITS = 14;
    /**
     * In a many-colour frame (drawn approximately anyway), a pixel this close (in every channel) to what's shown is
     * left as it is: shimmer in soft edges and glows doesn't cost a pixel a frame. Frames of few colours stay exact.
     */
    static final int STILL = 24;
    /** The colours a median cut picks (fewer, smaller codes: footage of the map needs no more). */
    static final int CUT = 127;
    /**
     * Lossy LZW in many-colour frames (as gifsicle's --lossy): where the dictionary can't go on with a pixel's entry,
     * a child whose colour is within this (in every channel) of the pixel's is taken instead, for a longer match
     * (a transparent child, if what's shown there is that near).
     */
    static final int LOSSY = 32;

    GifEncoder(OutputStream out, int w, int h) {
        if (w < 1 || h < 1 || w > 65535 || h > 65535) throw new IllegalArgumentException("size " + w + "x" + h);
        this.out = out;
        this.w = w;
        this.h = h;
        idx = new byte[w * h];
        sub = new byte[w * h];
        shown = new int[w * h];
        want = new int[w * h];
        was = new int[w * h];
    }

    int frames() {
        return frames;
    }

    /** A frame shown for this many hundredths of a second (fractions are carried to the next frame). */
    void addFrame(int[] argb, double delayCs) throws IOException {
        owedCs += delayCs;
        int d = (int) Math.max(1, Math.round(owedCs));
        owedCs -= d;
        addFrame(argb, d);
    }

    void addFrame(int[] argb, int delayCs) throws IOException {
        if (frames == 0) first(argb, delayCs);
        else next(argb, delayCs);
        frames++;
    }

    private static int bitsFor(int entries) {
        int bits = 1;
        while ((1 << bits) < entries) bits++;
        return bits;
    }

    /** The first frame: the header with the global table, then the whole frame in it. */
    private void first(int[] argb, int delayCs) throws IOException {
        int n = w * h;
        gN = quantize(argb, n, gPal, idx, 255, CUT);
        gBits = bitsFor(gN + 1); // (an entry spare for transparency)
        for (int k = 0; k < gN; k++) gMap.put(gPal[k] & 0xFFFFFF, k);
        for (int i = 0; i < n; i++) shown[i] = gPal[idx[i] & 0xFF] & 0xFFFFFF;
        ascii("GIF89a");
        short16(w);
        short16(h);
        out.write(0xF0 | (gBits - 1)); // a global table; 8 bits of colour resolution
        out.write(0);
        out.write(0);
        table(gPal, gN, gBits);
        // NETSCAPE2.0: loop forever
        out.write(0x21);
        out.write(0xFF);
        out.write(11);
        ascii("NETSCAPE2.0");
        out.write(3);
        out.write(1);
        short16(0);
        out.write(0);
        image(delayCs, -1, 0, 0, w, h, null, 0, 0, idx);
    }

    /** A later frame: the changed rectangle, unchanged pixels transparent, in the global table where it fits. */
    private void next(int[] argb, int delayCs) throws IOException {
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0, i = 0; y < h; y++)
            for (int x = 0; x < w; x++, i++)
                if ((argb[i] & 0xFFFFFF) != shown[i]) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
        if (x1 < 0) {
            // nothing changed: one transparent pixel keeps the time
            sub[0] = (byte) gN;
            image(delayCs, gN, 0, 0, 1, 1, null, 0, 0, sub);
            return;
        }
        // which table: the global one if it has every changed colour; else the changed colours exactly, if few;
        // else the global one if it's near enough, or a median cut of the changed pixels
        lMap.clear();
        boolean inGlobal = true;
        int distinct = 0;
        for (int y = y0; y <= y1; y++)
            for (int x = x0, i = y * w + x0; x <= x1; x++, i++) {
                if ((argb[i] & 0xFFFFFF) == shown[i]) continue;
                int c = argb[i] & 0xFFFFFF;
                if (inGlobal && gMap.get(c) < 0) inGlobal = false;
                if (distinct <= 255 && lMap.get(c) < 0) {
                    if (distinct < 255) lPal[distinct] = c;
                    lMap.put(c, distinct++);
                }
                if (!inGlobal && distinct > 255) break;
            }
        int mode; // 0 global exact, 1 local exact, 2 global nearest, 3 local median cut
        short[] lLut = null;
        if (inGlobal) mode = 0;
        else if (distinct <= 255) mode = 1;
        else {
            if (gLut == null) {
                gLut = new short[32768];
                java.util.Arrays.fill(gLut, (short) -1);
            }
            long err = 0, cnt = 0;
            for (int y = y0; y <= y1; y++)
                for (int x = x0, i = y * w + x0; x <= x1; x++, i++) {
                    if ((argb[i] & 0xFFFFFF) == shown[i]) continue;
                    int c = argb[i], p = gPal[nearestGlobal(c)];
                    err += Math.abs(((c >> 16) & 0xFF) - ((p >> 16) & 0xFF)) + Math.abs(((c >> 8) & 0xFF) - ((p >> 8) & 0xFF)) + Math.abs((c & 0xFF) - (p & 0xFF));
                    cnt++;
                }
            if (err <= (long) GLOBAL_FITS * cnt) mode = 2;
            else {
                int[] hist = new int[32768];
                for (int y = y0; y <= y1; y++)
                    for (int x = x0, i = y * w + x0; x <= x1; x++, i++)
                        if ((argb[i] & 0xFFFFFF) != shown[i]) hist[bin(argb[i])]++;
                distinct = medianCut(hist, lPal, CUT);
                lLut = nearestTable(hist, lPal, distinct);
                mode = 3;
            }
        }
        boolean local = mode == 1 || mode == 3;
        int[] pal = local ? lPal : gPal;
        int count = local ? distinct : gN, trans = count;
        // each pixel: transparent where it's unchanged (or comes out the colour already shown), and the drawn area tight
        int nx0 = w, ny0 = h, nx1 = -1, ny1 = -1;
        for (int y = y0; y <= y1; y++)
            for (int x = x0, i = y * w + x0; x <= x1; x++, i++) {
                int e = trans;
                if ((argb[i] & 0xFFFFFF) != shown[i] && (mode < 2 || dist(argb[i], shown[i]) > STILL)) {
                    int c = argb[i] & 0xFFFFFF;
                    int k = mode == 0 ? gMap.get(c) : mode == 1 ? lMap.get(c) : mode == 2 ? nearestGlobal(c) : lLut[bin(c)];
                    int col = pal[k] & 0xFFFFFF;
                    if (col != shown[i]) {
                        e = k;
                        if (x < nx0) nx0 = x;
                        if (x > nx1) nx1 = x;
                        if (y < ny0) ny0 = y;
                        if (y > ny1) ny1 = y;
                    }
                }
                idx[i] = (byte) e;
            }
        if (nx1 < 0) {
            sub[0] = (byte) gN;
            image(delayCs, gN, 0, 0, 1, 1, null, 0, 0, sub);
            return;
        }
        int rw = nx1 - nx0 + 1, rh = ny1 - ny0 + 1;
        for (int y = 0; y < rh; y++) {
            System.arraycopy(idx, (ny0 + y) * w + nx0, sub, y * rw, rw);
            for (int x = 0; x < rw; x++) {
                want[y * rw + x] = argb[(ny0 + y) * w + nx0 + x] & 0xFFFFFF;
                was[y * rw + x] = shown[(ny0 + y) * w + nx0 + x];
            }
        }
        image(delayCs, trans, nx0, ny0, rw, rh, local ? pal : null, count, local ? bitsFor(count + 1) : 0, sub,
                mode >= 2 ? pal : null, mode >= 2 ? LOSSY : 0);
        // what the decoder shows now (the lossy coder may have picked a near colour for a pixel)
        for (int y = 0; y < rh; y++)
            for (int x = 0; x < rw; x++) {
                int e = sub[y * rw + x] & 0xFF;
                if (e != trans) shown[(ny0 + y) * w + nx0 + x] = pal[e] & 0xFFFFFF;
            }
    }

    /** A graphic control block and an image: trans is the transparent entry or -1, table a local table or null. */
    private void image(int delayCs, int trans, int x, int y, int iw, int ih, int[] table, int count, int bits, byte[] px) throws IOException {
        image(delayCs, trans, x, y, iw, ih, table, count, bits, px, null, 0);
    }

    /** The same, with lossy coding: a pixel may take a near colour (within tol of want's) that makes a longer match. */
    private void image(int delayCs, int trans, int x, int y, int iw, int ih, int[] table, int count, int bits, byte[] px,
                       int[] lossyPal, int tol) throws IOException {
        out.write(0x21);
        out.write(0xF9);
        out.write(4);
        out.write(0x04 | (trans >= 0 ? 1 : 0)); // leave it in place
        short16(Math.max(0, Math.min(65535, delayCs)));
        out.write(trans >= 0 ? trans : 0);
        out.write(0);
        out.write(0x2C);
        short16(x);
        short16(y);
        short16(iw);
        short16(ih);
        if (table != null) {
            out.write(0x80 | (bits - 1));
            table(table, count, bits);
        } else out.write(0);
        int minSize = Math.max(2, table != null ? bits : gBits);
        out.write(minSize);
        lzw(px, iw * ih, minSize, lossyPal, trans, tol);
        out.write(0);
    }

    private void table(int[] pal, int count, int bits) throws IOException {
        for (int k = 0; k < (1 << bits); k++) {
            int c = k < count ? pal[k] : 0;
            out.write((c >> 16) & 0xFF);
            out.write((c >> 8) & 0xFF);
            out.write(c & 0xFF);
        }
    }

    /** The largest of |dr|, |dg|, |db| (a near colour is near in every channel: no shift of hue). */
    private static int dist(int a, int b) {
        return Math.max(Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)), Math.max(Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)), Math.abs((a & 0xFF) - (b & 0xFF))));
    }

    private static int bin(int c) {
        return ((c >> 9) & 0x7C00) | ((c >> 6) & 0x3E0) | ((c >> 3) & 0x1F);
    }

    private int nearestGlobal(int c) {
        int b = bin(c);
        if (gLut[b] < 0) gLut[b] = (short) nearest(b, gPal, gN);
        return gLut[b];
    }

    private static int nearest(int b, int[] pal, int count) {
        int r = ((b >> 10) & 31) * 8 + 4, g = ((b >> 5) & 31) * 8 + 4, bl = (b & 31) * 8 + 4;
        int best = 0, bestD = Integer.MAX_VALUE;
        for (int k = 0; k < count; k++) {
            int p = pal[k];
            int dr = ((p >> 16) & 0xFF) - r, dg = ((p >> 8) & 0xFF) - g, db = (p & 0xFF) - bl;
            int d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        return best;
    }

    private static short[] nearestTable(int[] hist, int[] pal, int count) {
        short[] lut = new short[32768];
        for (int b = 0; b < 32768; b++) if (hist[b] != 0) lut[b] = (short) nearest(b, pal, count);
        return lut;
    }

    void finish() throws IOException {
        if (finished) return;
        finished = true;
        if (frames == 0) throw new IOException("a GIF with no frames");
        out.write(0x3B);
        out.flush();
    }

    /** Colours (RGB) to entries: open addressing, for up to a few hundred. */
    private static final class ColourMap {
        private final int[] keys = new int[1024];
        private final short[] vals = new short[1024];

        ColourMap() {
            clear();
        }

        void clear() {
            java.util.Arrays.fill(keys, -1);
        }

        int get(int c) {
            int s = (c * 0x9E3779B1 >>> 22) & 1023;
            while (keys[s] != -1) {
                if (keys[s] == c) return vals[s];
                s = (s + 1) & 1023;
            }
            return -1;
        }

        void put(int c, int v) {
            int s = (c * 0x9E3779B1 >>> 22) & 1023;
            while (keys[s] != -1 && keys[s] != c) s = (s + 1) & 1023;
            keys[s] = c;
            vals[s] = (short) v;
        }
    }

    // ------------------------------------------------------------------ colours

    /**
     * Fills palette (up to max colours) and idx (each pixel's entry) for a frame; returns how many colours. A frame of
     * max colours or fewer is kept exactly; otherwise a median cut over 15-bit colour counts picks them.
     */
    static int quantize(int[] argb, int n, int[] palette, byte[] idx, int max, int cut) {
        int exact = exactColours(argb, n, palette, idx, max);
        if (exact > 0) return exact;
        int[] hist = new int[32768];
        for (int i = 0; i < n; i++) hist[bin(argb[i])]++;
        int colours = medianCut(hist, palette, cut);
        short[] lut = nearestTable(hist, palette, colours);
        for (int i = 0; i < n; i++) idx[i] = (byte) lut[bin(argb[i])];
        return colours;
    }

    /** The frame's own colours if there are max or fewer (idx filled), else 0. */
    private static int exactColours(int[] argb, int n, int[] palette, byte[] idx, int max) {
        int size = 1024, mask = size - 1;
        int[] keys = new int[size];
        short[] vals = new short[size];
        java.util.Arrays.fill(keys, -1);
        int count = 0;
        for (int i = 0; i < n; i++) {
            int c = argb[i] & 0xFFFFFF;
            int s = (c * 0x9E3779B1 >>> 22) & mask;
            while (keys[s] != -1 && keys[s] != c) s = (s + 1) & mask;
            if (keys[s] == -1) {
                if (count == max) return 0;
                keys[s] = c;
                vals[s] = (short) count;
                palette[count++] = c;
            }
            idx[i] = (byte) vals[s];
        }
        if (count < 2) palette[count++] = 0; // (a table has two entries at least)
        return count;
    }

    /** Median cut over a 15-bit histogram: boxes split along their longest side at the median, to max at most. */
    private static int medianCut(int[] hist, int[] palette, int max) {
        int[][] box = new int[max][]; // r0, r1, g0, g1, b0, b1, count
        int nb = 0;
        int[] all = shrink(hist, new int[]{0, 31, 0, 31, 0, 31, 0});
        if (all == null) {
            palette[0] = 0;
            palette[1] = 0;
            return 2;
        }
        box[nb++] = all;
        while (nb < max) {
            // half the splits go to the most crowded boxes, the rest to crowded and large ones (keeps small, vivid
            // things like the dead's red rims)
            int pick = -1;
            double best = -1;
            for (int k = 0; k < nb; k++) {
                int[] b = box[k];
                if (b[0] == b[1] && b[2] == b[3] && b[4] == b[5]) continue;
                double vol = (b[1] - b[0] + 1.0) * (b[3] - b[2] + 1) * (b[5] - b[4] + 1);
                double score = nb < max / 2 ? b[6] : b[6] * vol;
                if (score > best) {
                    best = score;
                    pick = k;
                }
            }
            if (pick < 0) break;
            int[] b = box[pick];
            int lr = b[1] - b[0], lg = b[3] - b[2], lb = b[5] - b[4];
            int axis = lg >= lr && lg >= lb ? 1 : lr >= lb ? 0 : 2;
            int lo = b[axis * 2], hi = b[axis * 2 + 1];
            // the plane where half the box's pixels lie below
            long half = b[6] / 2, acc = 0;
            int cut = lo;
            for (int v = lo; v < hi; v++) {
                acc += slab(hist, b, axis, v);
                cut = v;
                if (acc >= half) break;
            }
            int[] a1 = b.clone(), a2 = b.clone();
            a1[axis * 2 + 1] = cut;
            a2[axis * 2] = cut + 1;
            a1 = shrink(hist, a1);
            a2 = shrink(hist, a2);
            if (a1 == null || a2 == null) {
                // (can't happen with a cut inside the occupied range, but never loop forever)
                b[axis * 2] = b[axis * 2 + 1];
                continue;
            }
            box[pick] = a1;
            box[nb++] = a2;
        }
        for (int k = 0; k < nb; k++) {
            int[] b = box[k];
            long sr = 0, sg = 0, sb = 0, n = 0;
            for (int r = b[0]; r <= b[1]; r++)
                for (int g = b[2]; g <= b[3]; g++)
                    for (int bl = b[4]; bl <= b[5]; bl++) {
                        int c = hist[(r << 10) | (g << 5) | bl];
                        if (c == 0) continue;
                        sr += (long) c * (r * 8 + 4);
                        sg += (long) c * (g * 8 + 4);
                        sb += (long) c * (bl * 8 + 4);
                        n += c;
                    }
            if (n == 0) n = 1;
            palette[k] = (int) (sr / n) << 16 | (int) (sg / n) << 8 | (int) (sb / n);
        }
        if (nb < 2) palette[nb++] = 0;
        return nb;
    }

    private static long slab(int[] hist, int[] b, int axis, int v) {
        long s = 0;
        int r0 = axis == 0 ? v : b[0], r1 = axis == 0 ? v : b[1];
        int g0 = axis == 1 ? v : b[2], g1 = axis == 1 ? v : b[3];
        int b0 = axis == 2 ? v : b[4], b1 = axis == 2 ? v : b[5];
        for (int r = r0; r <= r1; r++)
            for (int g = g0; g <= g1; g++)
                for (int bl = b0; bl <= b1; bl++) s += hist[(r << 10) | (g << 5) | bl];
        return s;
    }

    /** The box shrunk to the colours in it, with its count; null if it's empty. */
    private static int[] shrink(int[] hist, int[] b) {
        int r0 = 32, r1 = -1, g0 = 32, g1 = -1, b0 = 32, b1 = -1, n = 0;
        for (int r = b[0]; r <= b[1]; r++)
            for (int g = b[2]; g <= b[3]; g++)
                for (int bl = b[4]; bl <= b[5]; bl++) {
                    int c = hist[(r << 10) | (g << 5) | bl];
                    if (c == 0) continue;
                    n += c;
                    if (r < r0) r0 = r;
                    if (r > r1) r1 = r;
                    if (g < g0) g0 = g;
                    if (g > g1) g1 = g;
                    if (bl < b0) b0 = bl;
                    if (bl > b1) b1 = bl;
                }
        if (n == 0) return null;
        return new int[]{r0, r1, g0, g1, b0, b1, n};
    }

    // ------------------------------------------------------------------ LZW

    private static final int MAX_CODES = 4096, HSIZE = 8192;
    private final int[] hkeys = new int[HSIZE];
    private final short[] hcodes = new short[HSIZE];
    /** The dictionary as a tree (each code's first child, next sibling and last entry), for the lossy search. */
    private final short[] first = new short[MAX_CODES], sib = new short[MAX_CODES], chr = new short[MAX_CODES];
    private final byte[] block = new byte[255];
    private int blockN, bitBuf, bitN, nBits;

    /** The pixels' entries, LZW-compressed in sub-blocks (as in compress(1), which GIF decoders follow). */
    private void lzw(byte[] px, int n, int minSize, int[] pal, int trans, int tol) throws IOException {
        boolean lossy = pal != null && tol > 0;
        if (lossy) java.util.Arrays.fill(first, (short) -1);
        int clear = 1 << minSize, eoi = clear + 1;
        nBits = minSize + 1;
        int maxCode = (1 << nBits) - 1, free = clear + 2;
        java.util.Arrays.fill(hkeys, -1);
        blockN = 0;
        bitBuf = 0;
        bitN = 0;
        code(clear);
        int ent = px[0] & 0xFF;
        for (int i = 1; i < n; i++) {
            int c = px[i] & 0xFF;
            int key = (c << 12) | ent;
            int s = (key * 0x9E3779B1 >>> 19) & (HSIZE - 1);
            boolean found = false;
            while (hkeys[s] != -1) {
                if (hkeys[s] == key) {
                    found = true;
                    break;
                }
                s = (s + 1) & (HSIZE - 1);
            }
            if (found) {
                ent = hcodes[s];
                continue;
            }
            if (lossy && c != trans) {
                // a near colour the dictionary can go on with (leaving the pixel as it's shown counts, if that's near)
                int best = -1, bestD = tol + 1, want0 = want[i];
                for (int k = first[ent]; k >= 0; k = sib[k]) {
                    int ck = chr[k];
                    int d = dist(ck == trans ? was[i] : pal[ck], want0);
                    if (d < bestD) {
                        bestD = d;
                        best = k;
                    }
                }
                if (best >= 0) {
                    px[i] = (byte) chr[best];
                    ent = best;
                    continue;
                }
            }
            code(ent);
            if (free > maxCode && nBits < 12) maxCode = (1 << ++nBits) - 1;
            ent = c;
            if (free < MAX_CODES) {
                hkeys[s] = key;
                hcodes[s] = (short) free;
                if (lossy) {
                    chr[free] = (short) c;
                    sib[free] = first[key & 0xFFF];
                    first[key & 0xFFF] = (short) free;
                    first[free] = -1;
                }
                free++;
            } else {
                // the table is full: start again
                code(clear);
                java.util.Arrays.fill(hkeys, -1);
                if (lossy) java.util.Arrays.fill(first, (short) -1);
                free = clear + 2;
                nBits = minSize + 1;
                maxCode = (1 << nBits) - 1;
            }
        }
        code(ent);
        if (free > maxCode && nBits < 12) ++nBits;
        code(eoi);
        if (bitN > 0) put(bitBuf & 0xFF);
        if (blockN > 0) flushBlock();
    }

    private void code(int c) throws IOException {
        bitBuf |= c << bitN;
        bitN += nBits;
        while (bitN >= 8) {
            put(bitBuf & 0xFF);
            bitBuf >>>= 8;
            bitN -= 8;
        }
    }

    private void put(int b) throws IOException {
        block[blockN++] = (byte) b;
        if (blockN == 255) flushBlock();
    }

    private void flushBlock() throws IOException {
        out.write(blockN);
        out.write(block, 0, blockN);
        blockN = 0;
    }

    private void short16(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private void ascii(String s) throws IOException {
        for (int k = 0; k < s.length(); k++) out.write(s.charAt(k));
    }
}
