package com.instantaxtion.zcs2;

/**
 * Short-lived things: tracers, blasts, muzzle flashes, blood (fixed ring buffers, oldest replaced first), the
 * noises the dead follow, and sound events for the audio. Only noises feed back into the simulation.
 */
public final class Effects {
    public static final int TRACER = 0, BLAST = 1, FLASH = 2, BLOOD = 3, SMOKE = 4, BIGBLAST = 5, SPARK = 6;
    public static final int MAX = 4096;
    public final float[] fx = new float[MAX], fy = new float[MAX], fx2 = new float[MAX], fy2 = new float[MAX],
            age = new float[MAX], life = new float[MAX];
    public final byte[] kind = new byte[MAX];
    public int head, count;

    // blood decals (stay longer)
    public static final int DECALS = 6000;
    public final float[] dx = new float[DECALS], dy = new float[DECALS];
    public final byte[] dsize = new byte[DECALS];
    public int dHead, dCount;

    // noises: where the dead are drawn to
    public static final int NOISES = 96;
    public final float[] noiseX = new float[NOISES], noiseY = new float[NOISES], noiseLoud = new float[NOISES],
            noiseT = new float[NOISES];
    public int noiseN;

    // sound events for the audio thread (copied out by the game each frame)
    public static final int SOUNDS = 256;
    public final float[] sx = new float[SOUNDS], sy = new float[SOUNDS];
    public final String[] sName = new String[SOUNDS];
    public int sHead, sCount;

    public void add(int k, float x, float y, float x2, float y2, float lifeS) {
        int i = head;
        head = (head + 1) % MAX;
        if (count < MAX) count++;
        kind[i] = (byte) k;
        fx[i] = x;
        fy[i] = y;
        fx2[i] = x2;
        fy2[i] = y2;
        age[i] = 0;
        life[i] = lifeS;
    }

    public void blood(float x, float y, int size) {
        int i = dHead;
        dHead = (dHead + 1) % DECALS;
        if (dCount < DECALS) dCount++;
        dx[i] = x;
        dy[i] = y;
        dsize[i] = (byte) size;
    }

    /** A noise the dead can hear: loud = how far it carries, in navigation cells. */
    public void noise(float x, float y, float loud, float seconds) {
        // merge with a nearby noise instead of filling the list
        for (int n = 0; n < noiseN; n++) {
            float ddx = noiseX[n] - x, ddy = noiseY[n] - y;
            if (ddx * ddx + ddy * ddy < 160 * 160) {
                if (loud > noiseLoud[n]) noiseLoud[n] = loud;
                if (seconds > noiseT[n]) noiseT[n] = seconds;
                return;
            }
        }
        if (noiseN == NOISES) {
            // replace the quietest
            int q = 0;
            for (int n = 1; n < noiseN; n++) if (noiseLoud[n] * noiseT[n] < noiseLoud[q] * noiseT[q]) q = n;
            if (noiseLoud[q] > loud) return;
            noiseX[q] = x;
            noiseY[q] = y;
            noiseLoud[q] = loud;
            noiseT[q] = seconds;
            return;
        }
        noiseX[noiseN] = x;
        noiseY[noiseN] = y;
        noiseLoud[noiseN] = loud;
        noiseT[noiseN] = seconds;
        noiseN++;
    }

    public void sound(String name, float x, float y) {
        int i = sHead;
        sHead = (sHead + 1) % SOUNDS;
        if (sCount < SOUNDS) sCount++;
        sName[i] = name;
        sx[i] = x;
        sy[i] = y;
    }

    public void update(float dt) {
        for (int k = 0; k < count; k++) {
            int i = (head - 1 - k + MAX) % MAX;
            age[i] += dt;
        }
        while (count > 0) {
            int oldest = (head - count + MAX) % MAX;
            if (age[oldest] >= life[oldest]) count--;
            else break;
        }
        for (int n = 0; n < noiseN; ) {
            noiseT[n] -= dt;
            if (noiseT[n] <= 0) {
                noiseN--;
                noiseX[n] = noiseX[noiseN];
                noiseY[n] = noiseY[noiseN];
                noiseLoud[n] = noiseLoud[noiseN];
                noiseT[n] = noiseT[noiseN];
            } else n++;
        }
    }
}
