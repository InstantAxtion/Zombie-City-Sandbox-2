package com.instantaxtion.zcs2;

import java.util.Arrays;

/**
 * The scent of the living, as one distance field over the navigation grid (32 px cells), rebuilt twice a second.
 * Every zombie just walks downhill on it: thousands of the dead find their way round buildings to the living
 * for the cost of one breadth-first search, instead of thousands of path searches or steering checks.
 * Noise seeds it too (gunfire, alarms, explosions, screams, an alpha's roar, a barricade being beaten), so the dead
 * drift towards a fight they can't yet smell.
 * The field is kept per active page (ActiveSet): 32x32 cells a page, cell index slot << 10 | y << 5 | x. The search
 * crosses page borders through the page table, and a page that isn't active counts as blocked; where every page is
 * active (today's maps) the distances are exactly those of one search over the whole grid.
 */
public final class Flow implements ActiveSet.Paged {
    public static final short INF = Short.MAX_VALUE;
    public static final int MAXD = 56;
    private final Region r;
    private final int w, h;
    /** The page table (ActiveSet.slotOf) and its row length. */
    private final int[] slotOf;
    private final int ppw;
    short[] dist;
    short[] stamp; // which build each cell's distance belongs to (16 bits: reset when it wraps)
    /** Each cell's downhill step, worked out once a build: (build & 0xFFF) << 4 | step (1 + 3 * (dy + 1) + dx + 1). */
    private short[] step;
    /** Each slot's navigation cells (its page's: Region's own array) and the page's corner in navigation cells. */
    private byte[][] navS;
    private int[] sx0, sy0;
    int cur;
    private int[] q, q2;
    /** Cells searched in the last build (for the probe). */
    public int searched;

    public Flow(Region r, ActiveSet act) {
        this.r = r;
        w = r.navW;
        h = r.navH;
        slotOf = act.slotOf;
        ppw = act.pw;
        q = new int[4096];
        q2 = new int[4096];
        act.register(this);
    }

    @Override
    public void slotsGrown(int cap) {
        int n = cap << 10;
        dist = dist == null ? new short[n] : Arrays.copyOf(dist, n);
        stamp = stamp == null ? new short[n] : Arrays.copyOf(stamp, n);
        step = step == null ? new short[n] : Arrays.copyOf(step, n);
        navS = navS == null ? new byte[cap][] : Arrays.copyOf(navS, cap);
        sx0 = sx0 == null ? new int[cap] : Arrays.copyOf(sx0, cap);
        sy0 = sy0 == null ? new int[cap] : Arrays.copyOf(sy0, cap);
    }

    @Override
    public void slotAssigned(int s, int p) {
        navS[s] = r.navPage(p);
        sx0[s] = (p % ppw) << 5;
        sy0[s] = (p / ppw) << 5;
        int a = s << 10;
        Arrays.fill(stamp, a, a + 1024, (short) 0);
        Arrays.fill(step, a, a + 1024, (short) 0);
    }

    /** The index of cell (cx, cy), which must be on the map, or -1 if its page isn't active. */
    int index(int cx, int cy) {
        int s = slotOf[(cy >> 5) * ppw + (cx >> 5)];
        return s < 0 ? -1 : s << 10 | (cy & 31) << 5 | (cx & 31);
    }

    /** The navigation cell (cx, cy) as the search sees it: 0 off the map or on a page that isn't active. */
    private int navG(int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= w || cy >= h) return 0;
        int s = slotOf[(cy >> 5) * ppw + (cx >> 5)];
        return s < 0 ? 0 : navS[s][(cy & 31) << 5 | (cx & 31)];
    }

    public short at(float px, float py) {
        int cx = (int) (px / (Tile.SIZE * Region.NAV)), cy = (int) (py / (Tile.SIZE * Region.NAV));
        return atCell(cx, cy);
    }

    public short atCell(int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= w || cy >= h) return INF;
        int i = index(cx, cy);
        return i >= 0 && stamp[i] == cur ? dist[i] : INF;
    }

    /** Sets cell (cx, cy) to d in the current build (a save loading); nothing if its page isn't active. */
    void setCell(int cx, int cy, short d) {
        if (cx < 0 || cy < 0 || cx >= w || cy >= h) return;
        int i = index(cx, cy);
        if (i < 0) return;
        dist[i] = d;
        stamp[i] = (short) cur;
    }

    /** The next build's stamp. */
    int nextStamp() {
        cur++;
        if (cur >= Short.MAX_VALUE) {
            Arrays.fill(stamp, (short) 0);
            cur = 1;
        }
        // the step cache keeps 12 bits of the build: emptied when they wrap
        if ((cur & 0xFFF) == 0 || cur == 1) Arrays.fill(step, (short) 0);
        return cur;
    }

    public void build(World wd) {
        nextStamp();
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
        short[] dist = this.dist, stamp = this.stamp;
        byte[][] navS = this.navS;
        short cur = (short) this.cur;
        int qn = 0;
        searched = 0;
        for (int d = 0; d <= MAXD; d++) {
            // this level's frontier: sources that start here plus cells reached from the level before
            int[] src = levels[d];
            for (int k = 0; k < counts[d]; k++) {
                int c = src[k];
                if (stamp[c] == cur && dist[c] <= d) continue;
                if (navS[c >>> 10][c & 1023] == 0) continue;
                stamp[c] = cur;
                dist[c] = (short) d;
                if (qn == q.length) q = Arrays.copyOf(q, qn * 2);
                q[qn++] = c;
            }
            if (d == MAXD) break;
            short d1 = (short) (d + 1);
            int qn2 = 0;
            for (int k = 0; k < qn; k++) {
                int c = q[k];
                if (dist[c] != d) continue;
                searched++;
                int l = c & 1023, lx = l & 31, ly = l >> 5;
                byte[] nv = navS[c >>> 10];
                if (lx > 0 && lx < 31 && ly > 0 && ly < 31) {
                    // all eight neighbours on this page
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) continue;
                            int off = dy * 32 + dx, ni = c + off;
                            if (nv[l + off] == 0 || (stamp[ni] == cur && dist[ni] <= d1)) continue;
                            // no cutting corners between two blocked cells
                            if (dx != 0 && dy != 0 && (nv[l + dx] == 0 || nv[l + dy * 32] == 0)) continue;
                            stamp[ni] = cur;
                            dist[ni] = d1;
                            if (qn2 == q2.length) q2 = Arrays.copyOf(q2, qn2 * 2);
                            q2[qn2++] = ni;
                        }
                    continue;
                }
                // on a page's edge: neighbours through the page table (a page that isn't active is blocked)
                int s = c >>> 10, cx = sx0[s] + lx, cy = sy0[s] + ly;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = cx + dx, ny = cy + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int ni = index(nx, ny);
                        if (ni < 0 || navS[ni >>> 10][ni & 1023] == 0 || (stamp[ni] == cur && dist[ni] <= d1)) continue;
                        if (dx != 0 && dy != 0 && (navG(nx, cy) == 0 || navG(cx, ny) == 0)) continue;
                        stamp[ni] = cur;
                        dist[ni] = d1;
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
        int c = index(cx, cy);
        if (c < 0) return;
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
        int bx = 0, by = 0;
        int ci = index(cx, cy), tag = (cur & 0xFFF) << 4;
        short cached = step[ci];
        if ((cached & 0xFFF0) == tag && cached != 0) {
            int k = (cached & 15) - 1;
            bx = k % 3 - 1;
            by = k / 3 - 1;
        } else {
            int best = here;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    short v = atCell(cx + dx, cy + dy);
                    if (v < best) {
                        if (dx != 0 && dy != 0 && (navG(cx + dx, cy) == 0 || navG(cx, cy + dy) == 0)) continue;
                        best = v;
                        bx = dx;
                        by = dy;
                    }
                }
            // (several threads may work the same cell out: they all write the same value)
            step[ci] = (short) (tag | (1 + 3 * (by + 1) + bx + 1));
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
