package com.instantaxtion.zcs2;

import java.util.Arrays;

/**
 * The scent of the living, as one distance field over the navigation grid (32 px cells), rebuilt twice a second.
 * Every zombie just walks downhill on it: thousands of the dead find their way round buildings to the living
 * for the cost of one breadth-first search, instead of thousands of path searches or steering checks.
 * Noise (gunfire, alarms, explosions) seeds it too, so the dead drift towards a fight they can't yet smell.
 */
public final class Flow {
    public static final short INF = Short.MAX_VALUE;
    public static final int MAXD = 56;
    private final Region r;
    private final int w, h;
    final short[] dist;
    final int[] stamp;
    int cur;
    private int[] q, q2;
    /** Cells searched in the last build (for the probe). */
    public int searched;

    public Flow(Region r) {
        this.r = r;
        w = r.navW;
        h = r.navH;
        dist = new short[w * h];
        stamp = new int[w * h];
        q = new int[4096];
        q2 = new int[4096];
    }

    public short at(float px, float py) {
        int cx = (int) (px / (Tile.SIZE * Region.NAV)), cy = (int) (py / (Tile.SIZE * Region.NAV));
        if (cx < 0 || cy < 0 || cx >= w || cy >= h) return INF;
        int i = cy * w + cx;
        return stamp[i] == cur ? dist[i] : INF;
    }

    public short atCell(int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= w || cy >= h) return INF;
        int i = cy * w + cx;
        return stamp[i] == cur ? dist[i] : INF;
    }

    public void build(World wd) {
        cur++;
        Ents e = wd.e;
        // sources by starting distance: the living at 0, noises further out the quieter they are
        int[][] levels = new int[MAXD + 1][];
        int[] counts = new int[MAXD + 1];
        float cs = Tile.SIZE * Region.NAV;
        for (int i = 0; i < e.top; i++) {
            if (e.loc[i] != Ents.FREE || e.kind[i] == Ents.ZOMBIE) continue;
            add(levels, counts, 0, (int) (e.x[i] / cs), (int) (e.y[i] / cs));
        }
        Effects fx = wd.fx;
        for (int n = 0; n < fx.noiseN; n++) {
            int d0 = Math.max(1, MAXD - (int) fx.noiseLoud[n]);
            add(levels, counts, d0, (int) (fx.noiseX[n] / cs), (int) (fx.noiseY[n] / cs));
        }
        int qn = 0;
        searched = 0;
        for (int d = 0; d <= MAXD; d++) {
            // this level's frontier: sources that start here plus cells reached from the level before
            int[] src = levels[d];
            for (int k = 0; k < counts[d]; k++) {
                int c = src[k];
                if (stamp[c] == cur && dist[c] <= d) continue;
                if (r.nav[c] == 0) continue;
                stamp[c] = cur;
                dist[c] = (short) d;
                if (qn == q.length) q = Arrays.copyOf(q, qn * 2);
                q[qn++] = c;
            }
            if (d == MAXD) break;
            int qn2 = 0;
            for (int k = 0; k < qn; k++) {
                int c = q[k];
                if (dist[c] != d) continue;
                int cx = c % w, cy = c / w;
                searched++;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = cx + dx, ny = cy + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int ni = ny * w + nx;
                        if (r.nav[ni] == 0 || (stamp[ni] == cur && dist[ni] <= d + 1)) continue;
                        // no cutting corners between two blocked cells
                        if (dx != 0 && dy != 0 && (r.nav[cy * w + nx] == 0 || r.nav[ny * w + cx] == 0)) continue;
                        stamp[ni] = cur;
                        dist[ni] = (short) (d + 1);
                        if (qn2 == q2.length) q2 = Arrays.copyOf(q2, qn2 * 2);
                        q2[qn2++] = ni;
                    }
            }
            int[] t = q;
            q = q2;
            q2 = t;
            qn = qn2;
        }
    }

    private void add(int[][] levels, int[] counts, int d, int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= w || cy >= h) return;
        int c = cy * w + cx;
        if (levels[d] == null) levels[d] = new int[64];
        if (counts[d] == levels[d].length) levels[d] = Arrays.copyOf(levels[d], counts[d] * 2);
        levels[d][counts[d]++] = c;
    }

    /**
     * Writes the downhill direction at (px, py) into out (unit vector) and returns the distance there,
     * or INF if no scent reaches this cell.
     */
    public int downhill(float px, float py, float[] out) {
        float cs = Tile.SIZE * Region.NAV;
        int cx = (int) (px / cs), cy = (int) (py / cs);
        short here = atCell(cx, cy);
        if (here == INF) return INF;
        int best = here, bx = 0, by = 0;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                short v = atCell(cx + dx, cy + dy);
                if (v < best) {
                    if (dx != 0 && dy != 0 && (!r.navOpen(cx + dx, cy) || !r.navOpen(cx, cy + dy))) continue;
                    best = v;
                    bx = dx;
                    by = dy;
                }
            }
        if (bx == 0 && by == 0) {
            out[0] = out[1] = 0;
            return here;
        }
        // aim for the middle of the next cell, which keeps the dead off building corners
        float tx = (cx + bx + 0.5f) * cs - px, ty = (cy + by + 0.5f) * cs - py;
        float len = (float) Math.sqrt(tx * tx + ty * ty);
        if (len < 0.01f) len = 1;
        out[0] = tx / len;
        out[1] = ty / len;
        return here;
    }
}
