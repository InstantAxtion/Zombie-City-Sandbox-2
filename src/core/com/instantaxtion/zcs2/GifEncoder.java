package com.instantaxtion.zcs2;

import java.io.IOException;
import java.io.OutputStream;

/**
 * An animated GIF (GIF89a) written frame by frame, in plain Java: each frame gets its own colour table of up to 256
 * colours (its exact colours when it has that few, else a median cut), and its pixels are LZW-compressed the way
 * the format's decoders expect (variable code width from the minimum code size + 1 up to 12 bits, a clear code when
 * the table fills). It loops forever. Frame delays are in hundredths of a second; {@link #addFrame(int[], double)}
 * carries the rounding over so a 15 fps clip keeps its length.
 */
final class GifEncoder {
    private final OutputStream out;
    private final int w, h;
    private int frames;
    private double owedCs;
    private final byte[] idx;
    private final int[] palette = new int[256];
    private boolean finished;

    GifEncoder(OutputStream out, int w, int h) throws IOException {
        if (w < 1 || h < 1 || w > 65535 || h > 65535) throw new IllegalArgumentException("size " + w + "x" + h);
        this.out = out;
        this.w = w;
        this.h = h;
        idx = new byte[w * h];
        ascii("GIF89a");
        short16(w);
        short16(h);
        out.write(0x70); // no global colour table; 8 bits of colour resolution
        out.write(0); // background
        out.write(0); // square pixels
        // NETSCAPE2.0: loop forever
        out.write(0x21);
        out.write(0xFF);
        out.write(11);
        ascii("NETSCAPE2.0");
        out.write(3);
        out.write(1);
        short16(0);
        out.write(0);
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
        int n = w * h;
        int colours = quantize(argb, n, palette, idx);
        int bits = 1;
        while ((1 << bits) < colours) bits++;
        // graphic control: the delay, no transparency, leave the frame in place
        out.write(0x21);
        out.write(0xF9);
        out.write(4);
        out.write(0x04);
        short16(Math.max(0, Math.min(65535, delayCs)));
        out.write(0);
        out.write(0);
        // the image, with its own colour table
        out.write(0x2C);
        short16(0);
        short16(0);
        short16(w);
        short16(h);
        out.write(0x80 | (bits - 1));
        for (int k = 0; k < (1 << bits); k++) {
            int c = k < colours ? palette[k] : 0;
            out.write((c >> 16) & 0xFF);
            out.write((c >> 8) & 0xFF);
            out.write(c & 0xFF);
        }
        int minSize = Math.max(2, bits);
        out.write(minSize);
        lzw(idx, n, minSize);
        out.write(0);
        frames++;
    }

    void finish() throws IOException {
        if (finished) return;
        finished = true;
        out.write(0x3B);
        out.flush();
    }

    // ------------------------------------------------------------------ colours

    /**
     * Fills palette (up to 256 colours) and idx (each pixel's entry) for a frame; returns how many colours. A frame of
     * 256 colours or fewer is kept exactly; otherwise a median cut over 15-bit colour counts picks them.
     */
    static int quantize(int[] argb, int n, int[] palette, byte[] idx) {
        int exact = exactColours(argb, n, palette, idx);
        if (exact > 0) return exact;
        int[] hist = new int[32768];
        for (int i = 0; i < n; i++) {
            int c = argb[i];
            hist[((c >> 9) & 0x7C00) | ((c >> 6) & 0x3E0) | ((c >> 3) & 0x1F)]++;
        }
        int colours = medianCut(hist, palette);
        // each 15-bit colour to its nearest entry, once
        short[] lut = new short[32768];
        for (int b = 0; b < 32768; b++) {
            if (hist[b] == 0) continue;
            int r = ((b >> 10) & 31) * 8 + 4, g = ((b >> 5) & 31) * 8 + 4, bl = (b & 31) * 8 + 4;
            int best = 0, bestD = Integer.MAX_VALUE;
            for (int k = 0; k < colours; k++) {
                int p = palette[k];
                int dr = ((p >> 16) & 0xFF) - r, dg = ((p >> 8) & 0xFF) - g, db = (p & 0xFF) - bl;
                int d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
                if (d < bestD) {
                    bestD = d;
                    best = k;
                }
            }
            lut[b] = (short) best;
        }
        for (int i = 0; i < n; i++) {
            int c = argb[i];
            idx[i] = (byte) lut[((c >> 9) & 0x7C00) | ((c >> 6) & 0x3E0) | ((c >> 3) & 0x1F)];
        }
        return colours;
    }

    /** The frame's own colours if there are 256 or fewer (idx filled), else 0. */
    private static int exactColours(int[] argb, int n, int[] palette, byte[] idx) {
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
                if (count == 256) return 0;
                keys[s] = c;
                vals[s] = (short) count;
                palette[count++] = c;
            }
            idx[i] = (byte) vals[s];
        }
        if (count < 2) palette[count++] = 0; // (a table has two entries at least)
        return count;
    }

    /** Median cut over a 15-bit histogram: boxes split along their longest side at the median, to 256 at most. */
    private static int medianCut(int[] hist, int[] palette) {
        int max = 256;
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
    private final byte[] block = new byte[255];
    private int blockN, bitBuf, bitN, nBits;

    /** The pixels' entries, LZW-compressed in sub-blocks (as in compress(1), which GIF decoders follow). */
    private void lzw(byte[] px, int n, int minSize) throws IOException {
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
            code(ent);
            if (free > maxCode && nBits < 12) maxCode = (1 << ++nBits) - 1;
            ent = c;
            if (free < MAX_CODES) {
                hkeys[s] = key;
                hcodes[s] = (short) free++;
            } else {
                // the table is full: start again
                code(clear);
                java.util.Arrays.fill(hkeys, -1);
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
