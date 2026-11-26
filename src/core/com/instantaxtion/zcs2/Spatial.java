package com.instantaxtion.zcs2;

import java.util.Arrays;

/**
 * A grid of 64 px cells holding everyone on the map, rebuilt every step by a counting sort (no allocation).
 * The living and the dead are kept in separate lists so "nearest zombie" never walks past civilians.
 * Read-only while the parallel phases run.
 */
public final class Spatial {
    public static final int CELL = 64;
    public final int cw, ch;
    /** Each cell's range in zList / lList is [zStart[c], zEnd[c]) (empty cells: start == end). */
    public final int[] zStart, zEnd, lStart, lEnd;
    public int[] zList = new int[1024], lList = new int[1024];
    /** Cells with anyone in them since the last build: only these are cleared and summed (a country has
     *  hundreds of thousands of cells, nearly all empty). */
    private int[] touched = new int[1024];
    private int touchedN;
    public int zCount, lCount, civCount;
    /** Per sector: zombies, civilians, police, soldiers on the map (rebuilt each step). */
    public final int[] secZ, secCiv, secCop, secSol;
    /** 256 px blocks (4 x 4 cells): how many of the living and the dead are in each, so a search that would
     *  only find empty cells stops at once (most of the dead are nowhere near anyone). */
    static final int BLK = 4;
    private final int bw;
    private final int[] lBlk, zBlk;

    public Spatial(Region r) {
        cw = r.px() / CELL + 1;
        ch = cw;
        zStart = new int[cw * ch];
        zEnd = new int[cw * ch];
        lStart = new int[cw * ch];
        lEnd = new int[cw * ch];
        int ns = r.secW * r.secH;
        secZ = new int[ns];
        secCiv = new int[ns];
        secCop = new int[ns];
        secSol = new int[ns];
        bw = cw / BLK + 1;
        lBlk = new int[bw * bw];
        zBlk = new int[bw * bw];
        this.r = r;
    }

    /** Is anyone (lBlk) or any of the dead (zBlk) in the blocks covering the square of radius rad round (x, y)? */
    private boolean anyIn(int[] blk, float x, float y, float rad) {
        float bs = CELL * BLK;
        int x0 = Math.max(0, (int) ((x - rad) / bs)), x1 = Math.min(bw - 1, (int) ((x + rad) / bs));
        int y0 = Math.max(0, (int) ((y - rad) / bs)), y1 = Math.min(bw - 1, (int) ((y + rad) / bs));
        for (int by = y0; by <= y1; by++)
            for (int bx = x0; bx <= x1; bx++)
                if (blk[by * bw + bx] > 0) return true;
        return false;
    }

    private final Region r;

    public int cellOf(float x, float y) {
        int cx = (int) (x / CELL), cy = (int) (y / CELL);
        if (cx < 0) cx = 0;
        if (cy < 0) cy = 0;
        if (cx >= cw) cx = cw - 1;
        if (cy >= ch) cy = ch - 1;
        return cy * cw + cx;
    }

    public void build(Ents e) {
        // empty the cells used last time
        for (int k = 0; k < touchedN; k++) {
            int c = touched[k];
            zStart[c] = zEnd[c] = lStart[c] = lEnd[c] = 0;
        }
        touchedN = 0;
        Arrays.fill(secZ, 0);
        Arrays.fill(secCiv, 0);
        Arrays.fill(secCop, 0);
        Arrays.fill(secSol, 0);
        Arrays.fill(lBlk, 0);
        Arrays.fill(zBlk, 0);
        // count (in zEnd / lEnd), noting each cell the first time it's used
        int nz = 0, nl = 0, nc = 0;
        for (int i = 0; i < e.top; i++) {
            if (e.loc[i] != Ents.FREE) continue;
            int c = cellOf(e.x[i], e.y[i]);
            if (zEnd[c] == 0 && lEnd[c] == 0) {
                if (touchedN == touched.length) touched = Arrays.copyOf(touched, touchedN * 2);
                touched[touchedN++] = c;
            }
            int s = r.sectorOfPx(e.x[i], e.y[i]);
            int b = (c / cw / BLK) * bw + (c % cw) / BLK;
            switch (e.kind[i]) {
                case Ents.ZOMBIE: zEnd[c]++; nz++; secZ[s]++; zBlk[b]++; break;
                case Ents.CIV: lEnd[c]++; nl++; secCiv[s]++; nc++; lBlk[b]++; break;
                case Ents.COP: lEnd[c]++; nl++; secCop[s]++; lBlk[b]++; break;
                default: lEnd[c]++; nl++; secSol[s]++; lBlk[b]++;
            }
        }
        if (zList.length < nz) zList = new int[nz * 2];
        if (lList.length < nl) lList = new int[nl * 2];
        // give each used cell its range; zEnd / lEnd become the fill cursors
        int zp = 0, lp = 0;
        for (int k = 0; k < touchedN; k++) {
            int c = touched[k];
            zStart[c] = zp;
            zp += zEnd[c];
            zEnd[c] = zStart[c];
            lStart[c] = lp;
            lp += lEnd[c];
            lEnd[c] = lStart[c];
        }
        // fill, in entity order (so each cell lists its people in the same order as before)
        for (int i = 0; i < e.top; i++) {
            if (e.loc[i] != Ents.FREE) continue;
            int c = cellOf(e.x[i], e.y[i]);
            if (e.kind[i] == Ents.ZOMBIE) zList[zEnd[c]++] = i;
            else lList[lEnd[c]++] = i;
        }
        zCount = nz;
        lCount = nl;
        civCount = nc;
    }

    public int secCivTotal() {
        return civCount;
    }

    /** The nearest zombie within maxR of (x, y), or -1. */
    public int nearestZombie(Ents e, float x, float y, float maxR) {
        if (!anyIn(zBlk, x, y, maxR)) return -1;
        return nearest(e, x, y, maxR, zStart, zEnd, zList, -1, 0);
    }

    /** The nearest living person within maxR (not 'self'); kindMask bit k set = skip kind k. */
    public int nearestLiving(Ents e, float x, float y, float maxR, int self, int skipMask) {
        if (!anyIn(lBlk, x, y, maxR)) return -1;
        return nearest(e, x, y, maxR, lStart, lEnd, lList, self, skipMask);
    }

    private int nearest(Ents e, float x, float y, float maxR, int[] start, int[] end, int[] list, int self, int skipMask) {
        int cx = (int) (x / CELL), cy = (int) (y / CELL);
        int rings = (int) (maxR / CELL) + 1;
        int best = -1;
        float bd = maxR * maxR;
        for (int k = 0; k <= rings; k++) {
            if (best >= 0) {
                float edge = (k - 1) * CELL;
                if (edge > 0 && edge * edge > bd) break;
            }
            for (int yy = cy - k; yy <= cy + k; yy++) {
                if (yy < 0 || yy >= ch) continue;
                boolean edgeRow = yy == cy - k || yy == cy + k;
                for (int xx = cx - k; xx <= cx + k; xx += edgeRow ? 1 : 2 * k) {
                    if (xx >= 0 && xx < cw) {
                        int c = yy * cw + xx;
                        for (int j = start[c]; j < end[c]; j++) {
                            int o = list[j];
                            if (o == self || ((skipMask >> e.kind[o]) & 1) != 0) continue;
                            float dx = e.x[o] - x, dy = e.y[o] - y, d = dx * dx + dy * dy;
                            if (d < bd) {
                                bd = d;
                                best = o;
                            }
                        }
                    }
                    if (k == 0) break;
                }
            }
        }
        return best;
    }

    /** How many zombies are within rad of (x, y) (stops counting at cap). */
    public int zombiesNear(Ents e, float x, float y, float rad, int cap) {
        if (!anyIn(zBlk, x, y, rad)) return 0;
        return countNear(e, x, y, rad, cap, zStart, zEnd, zList);
    }

    public int livingNear(Ents e, float x, float y, float rad, int cap) {
        if (!anyIn(lBlk, x, y, rad)) return 0;
        return countNear(e, x, y, rad, cap, lStart, lEnd, lList);
    }

    private int countNear(Ents e, float x, float y, float rad, int cap, int[] start, int[] end, int[] list) {
        int x0 = Math.max(0, (int) ((x - rad) / CELL)), x1 = Math.min(cw - 1, (int) ((x + rad) / CELL));
        int y0 = Math.max(0, (int) ((y - rad) / CELL)), y1 = Math.min(ch - 1, (int) ((y + rad) / CELL));
        float r2 = rad * rad;
        int n = 0;
        for (int cy = y0; cy <= y1; cy++)
            for (int cx = x0; cx <= x1; cx++) {
                int c = cy * cw + cx;
                for (int j = start[c]; j < end[c]; j++) {
                    int o = list[j];
                    float dx = e.x[o] - x, dy = e.y[o] - y;
                    if (dx * dx + dy * dy < r2 && ++n >= cap) return n;
                }
            }
        return n;
    }

    /** The middle of the zombies within rad (for aiming grenades and choosing where to fall back from). */
    public boolean zombieCentroid(Ents e, float x, float y, float rad, float[] out) {
        int x0 = Math.max(0, (int) ((x - rad) / CELL)), x1 = Math.min(cw - 1, (int) ((x + rad) / CELL));
        int y0 = Math.max(0, (int) ((y - rad) / CELL)), y1 = Math.min(ch - 1, (int) ((y + rad) / CELL));
        float r2 = rad * rad, sx = 0, sy = 0;
        int n = 0;
        for (int cy = y0; cy <= y1; cy++)
            for (int cx = x0; cx <= x1; cx++) {
                int c = cy * cw + cx;
                for (int j = zStart[c]; j < zEnd[c]; j++) {
                    int o = zList[j];
                    float dx = e.x[o] - x, dy = e.y[o] - y;
                    if (dx * dx + dy * dy < r2) {
                        sx += e.x[o];
                        sy += e.y[o];
                        n++;
                    }
                }
            }
        if (n == 0) return false;
        out[0] = sx / n;
        out[1] = sy / n;
        out[2] = n;
        return true;
    }
}
