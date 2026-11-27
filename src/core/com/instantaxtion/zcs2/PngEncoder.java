package com.instantaxtion.zcs2;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Writes a picture as a PNG (8-bit RGB, every row Paeth-filtered, zlib from java.util.zip): the same bytes on a phone
 * and a desktop, with no platform code, so the clip export runs anywhere, headless included.
 */
final class PngEncoder {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private PngEncoder() {
    }

    /** argb holds w x h pixels row by row; alpha is dropped (the frames are opaque). */
    static void write(int[] argb, int w, int h, OutputStream os) throws IOException {
        DataOutputStream o = new DataOutputStream(os);
        o.write(SIGNATURE);
        ByteArrayOutputStream hdr = new ByteArrayOutputStream(13);
        DataOutputStream ho = new DataOutputStream(hdr);
        ho.writeInt(w);
        ho.writeInt(h);
        ho.writeByte(8); // bits per channel
        ho.writeByte(2); // RGB
        ho.writeByte(0); // deflate
        ho.writeByte(0); // adaptive filtering
        ho.writeByte(0); // not interlaced
        chunk(o, "IHDR", hdr.toByteArray(), 13);
        ByteArrayOutputStream data = new ByteArrayOutputStream(w * h);
        Deflater def = new Deflater(6);
        DeflaterOutputStream z = new DeflaterOutputStream(data, def, 1 << 15);
        int stride = w * 3;
        byte[] prev = new byte[stride], cur = new byte[stride], row = new byte[stride + 1];
        row[0] = 4; // Paeth
        for (int y = 0; y < h; y++) {
            for (int x = 0, p = y * w; x < w; x++, p++) {
                int c = argb[p];
                cur[x * 3] = (byte) (c >> 16);
                cur[x * 3 + 1] = (byte) (c >> 8);
                cur[x * 3 + 2] = (byte) c;
            }
            for (int i = 0; i < stride; i++) {
                int a = i >= 3 ? cur[i - 3] & 0xFF : 0, b = prev[i] & 0xFF, c = i >= 3 ? prev[i - 3] & 0xFF : 0;
                row[i + 1] = (byte) ((cur[i] & 0xFF) - paeth(a, b, c));
            }
            z.write(row);
            byte[] t = prev;
            prev = cur;
            cur = t;
        }
        z.finish();
        def.end();
        byte[] idat = data.toByteArray();
        chunk(o, "IDAT", idat, idat.length);
        chunk(o, "IEND", new byte[0], 0);
        o.flush();
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    private static void chunk(DataOutputStream o, String type, byte[] data, int len) throws IOException {
        byte[] t = type.getBytes("US-ASCII");
        o.writeInt(len);
        o.write(t);
        o.write(data, 0, len);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data, 0, len);
        o.writeInt((int) crc.getValue());
    }
}
