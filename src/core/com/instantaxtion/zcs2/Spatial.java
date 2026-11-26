package com.instantaxtion.zcs2;

import java.util.Arrays;

/**
 * A grid of 64 px cells holding everyone on the map, rebuilt every step by a counting sort (no allocation).
 * The living and the dead are kept in separate lists so "nearest zombie" never walks past civilians.
 * Read-only while the parallel phases run.
 * <p>
 * The cells are kept per active page (ActiveSet): 16x16 cells a page, plus the row and column just past the map's
 * last page (positions are clamped into the grid, as they always were). Find a cell with cell(cx, cy) or cellOf(x, y),
 * never by arithmetic: a cell on a page that isn't active is an empty one shared by all of them. Only the cells,
 * blocks and sectors used by the last build are cleared.
 */
public final class Spatial implements ActiveSet.Paged {
    public static final int CELL = 64;
    /** Cells per page side (a page is 1024 px). */
    static final int PS = ActiveSet.PX / CELL;
    /** The grid's size in cells (cw x ch, the map plus one row and column). */
    public final int cw, ch;
    /** Each cell's range in zList / lList is [zStart[c], zEnd[c]) (empty cells: start == end). */
    public int[] zStart, zEnd, lStart, lEnd;
    public int[] zList = new int[1024], lList = new int[1024];
    /** Cells with anyone in them since the last build: only these (and their blocks) are cleared. */
    private int[] touched = new int[1024];
    private int touchedN;
    /** Sectors with anyone in them since the last build: only these are cleared. */
    private int[] secTouched = new int[256];
    private int secTouchedN;
    public int zCount, lCount, civCount;
    /** Per sector: zombies, civilians, police, soldiers on the map (rebuilt each step). */
    public final int[] secZ, secCiv, secCop, secSol;
    /** 256 px blocks (4 x 4 cells): how many of the living and the dead are in each, so a search that would
     *  only find empty cells stops at once (most of the dead are nowhere near anyone). */
    static final int BLK = 4;
    private final int bw;
    private int[] lBlk, zBlk;
    // the page table: cells past ex / ey (and blocks past bex / bey) are the edge cells, numbered 1..; 0 is the empty
    // cell (and block) every page that isn't active shares; page cells start at cellOff (blocks at blkOff)
    private final int[] slotOf;
    private final int ppw, ex, ey, bex, bey, edgeW, edgeH, bedgeW, bedgeH, cellOff, blkOff;
    /** Every page active, always (today's maps): the cells and blocks are laid out row by row (1 + cy * cw + cx,
     *  1 + by * bw + bx), exactly the old dense grid, and never paged. */
    private final boolean dense;

    public Spatial(Region r, ActiveSet act) {
        cw = r.px() / CELL + 1;
        ch = cw;
        int ns = r.secW * r.secH;
        secZ = new int[ns];
        secCiv = new int[ns];
        secCop = new int[ns];
        secSol = new int[ns];
        secBuild = new int[ns];
        bw = cw / BLK + 1;
        this.r = r;
        slotOf = act.slotOf;
        ppw = act.pw;
        dense = !act.sparse;
        ex = Math.min(cw, act.pw * PS);
        ey = Math.min(ch, act.ph * PS);
        edgeW = cw - ex;
        edgeH = ch - ey;
        cellOff = 1 + edgeW * ch + edgeH * ex;
        bex = Math.min(bw, act.pw * (PS / BLK));
        bey = Math.min(bw, act.ph * (PS / BLK));
        bedgeW = bw - bex;
        bedgeH = bw - bey;
        blkOff = 1 + bedgeW * bw + bedgeH * bex;
        act.register(this);
    }

    @Override
    public void slotsGrown(int cap) {
        int n = dense ? 1 + cw * ch : cellOff + cap * PS * PS, nb = dense ? 1 + bw * bw : blkOff + cap * (PS / BLK) * (PS / BLK);
        zStart = zStart == null ? new int[n] : Arrays.copyOf(zStart, n);
        zEnd = zEnd == null ? new int[n] : Arrays.copyOf(zEnd, n);
        lStart = lStart == null ? new int[n] : Arrays.copyOf(lStart, n);
        lEnd = lEnd == null ? new int[n] : Arrays.copyOf(lEnd, n);
        lBlk = lBlk == null ? new int[nb] : Arrays.copyOf(lBlk, nb);
        zBlk = zBlk == null ? new int[nb] : Arrays.copyOf(zBlk, nb);
    }

    @Override
    public void slotAssigned(int s, int p) {
        if (dense) return;
        int a = cellOff + s * PS * PS, b = blkOff + s * (PS / BLK) * (PS / BLK);
        Arrays.fill(zStart, a, a + PS * PS, 0);
        Arrays.fill(zEnd, a, a + PS * PS, 0);
        Arrays.fill(lStart, a, a + PS * PS, 0);
        Arrays.fill(lEnd, a, a + PS * PS, 0);
        Arrays.fill(lBlk, b, b + (PS / BLK) * (PS / BLK), 0);
        Arrays.fill(zBlk, b, b + (PS / BLK) * (PS / BLK), 0);
    }

    /** The cell (cx, cy) of the grid (0 &lt;= cx &lt; cw, 0 &lt;= cy &lt; ch): an index into zStart... (an empty one off the grid
     *  or on a page that isn't active). */
    public int cell(int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= cw || cy >= ch) return 0;
        return cellIn(cx, cy);
    }

    /** cell() for a cell known to be on the grid. */
    private int cellIn(int cx, int cy) {
        if (dense) return 1 + cy * cw + cx;
        if (cx < ex && cy < ey) {
            int s = slotOf[(cy >> 4) * ppw + (cx >> 4)];
            return s < 0 ? 0 : cellOff + (s << 8 | (cy & 15) << 4 | (cx & 15));
        }
        if (cx >= ex) return 1 + (cx - ex) * ch + cy;
        if (cy >= ey) return 1 + edgeW * ch + (cy - ey) * ex + cx;
        int s = dense ? (cy >> 4) * ppw + (cx >> 4) : slotOf[(cy >> 4) * ppw + (cx >> 4)];
        return s < 0 ? 0 : cellOff + (s << 8 | (cy & 15) << 4 | (cx & 15));
    }

    /** The block (bx, by) (0 .. bw - 1 each way): an index into lBlk / zBlk. */
    private int block(int bx, int by) {
        if (dense) return 1 + by * bw + bx;
        if (bx >= bex) return 1 + (bx - bex) * bw + by;
        if (by >= bey) return 1 + bedgeW * bw + (by - bey) * bex + bx;
        int s = slotOf[(by >> 2) * ppw + (bx >> 2)];
        return s < 0 ? 0 : blkOff + (s << 4 | (by & 3) << 2 | (bx & 3));
    }

    /** Is anyone (lBlk) or any of the dead (zBlk) in the blocks covering the square of radius rad round (x, y)? */
    private boolean anyIn(int[] blk, float x, float y, float rad) {
        float bs = CELL * BLK;
        int x0 = Math.max(0, (int) ((x - rad) / bs)), x1 = Math.min(bw - 1, (int) ((x + rad) / bs));
        int y0 = Math.max(0, (int) ((y - rad) / bs)), y1 = Math.min(bw - 1, (int) ((y + rad) / bs));
        if (dense) {
            for (int by = y0; by <= y1; by++) {
                int row = 1 + by * bw;
                for (int bx = x0; bx <= x1; bx++)
                    if (blk[row + bx] > 0) return true;
            }
            return false;
        }
        for (int by = y0; by <= y1; by++)
            for (int bx = x0; bx <= x1; bx++)
                if (blk[block(bx, by)] > 0) return true;
        return false;
    }

    private final Region r;

    /** The column of the cell holding x (clamped onto the grid). */
    public int cellX(float x) {
        int cx = (int) (x / CELL);
        return cx < 0 ? 0 : cx >= cw ? cw - 1 : cx;
    }

    /** The row of the cell holding y (clamped onto the grid). */
    public int cellY(float y) {
        int cy = (int) (y / CELL);
        return cy < 0 ? 0 : cy >= ch ? ch - 1 : cy;
    }

    public int cellOf(float x, float y) {
        return cellIn(cellX(x), cellY(y));
    }

    public void build(Ents e) {
        // empty the cells (and their blocks) and the sectors used last time
        for (int k = 0; k < touchedN; k++) {
            int c = touched[k];
            zStart[c] = zEnd[c] = lStart[c] = lEnd[c] = 0;
        }
        for (int k = 0; k < touchedN; k++) {
            int b = touchedBlk[k];
            lBlk[b] = zBlk[b] = 0;
        }
        touchedN = 0;
        for (int k = 0; k < secTouchedN; k++) {
            int s = secTouched[k];
            secZ[s] = secCiv[s] = secCop[s] = secSol[s] = 0;
        }
        secTouchedN = 0;
        if (++builds == 0) {
            Arrays.fill(secBuild, 0);
            builds = 1;
        }
        // count (in zEnd / lEnd), noting each cell the first time it's used
        int nz = 0, nl = 0, nc = 0;
        for (int i = 0; i < e.top; i++) {
            if (e.loc[i] != Ents.FREE) continue;
            int cx = cellX(e.x[i]), cy = cellY(e.y[i]);
            int c = cellIn(cx, cy);
            int s = r.sectorOfPx(e.x[i], e.y[i]);
            if (secBuild[s] != builds) {
                secBuild[s] = builds;
                if (secTouchedN == secTouched.length) secTouched = Arrays.copyOf(secTouched, secTouchedN * 2);
                secTouched[secTouchedN++] = s;
            }
            boolean z = e.kind[i] == Ents.ZOMBIE;
            switch (e.kind[i]) {
                case Ents.ZOMBIE: nz++; secZ[s]++; break;
                case Ents.CIV: nl++; secCiv[s]++; nc++; break;
                case Ents.COP: nl++; secCop[s]++; break;
                default: nl++; secSol[s]++;
            }
            // someone on a page that isn't active (only for a moment, if ever: the active set follows everyone)
            // is counted but in no cell
            if (c == 0) continue;
            int b = block(cx / BLK, cy / BLK);
            if (zEnd[c] == 0 && lEnd[c] == 0) {
                if (touchedN == touched.length) {
                    touched = Arrays.copyOf(touched, touchedN * 2);
                    touchedBlk = Arrays.copyOf(touchedBlk, touchedN * 2);
                }
                touchedBlk[touchedN] = b;
                touched[touchedN++] = c;
            }
            if (z) {
                zEnd[c]++;
                zBlk[b]++;
            } else {
                lEnd[c]++;
                lBlk[b]++;
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
            if (c == 0) continue;
            if (e.kind[i] == Ents.ZOMBIE) zList[zEnd[c]++] = i;
            else lList[lEnd[c]++] = i;
        }
        zCount = nz;
        lCount = nl;
        civCount = nc;
    }

    /** The build each sector was last counted in (so its counts are cleared once, and only if used). */
    private int[] secBuild;
    private int builds;

    /** The block of each cell in touched (cleared with it). */
    private int[] touchedBlk = new int[1024];

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
                        int c = cellIn(xx, yy);
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
                int c = cellIn(cx, cy);
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
                int c = cellIn(cx, cy);
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
