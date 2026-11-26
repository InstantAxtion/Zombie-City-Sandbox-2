package com.instantaxtion.zcs2;

import java.util.Arrays;

/**
 * Paths for people on foot over the navigation grid (A*, 8 directions, a counted search budget so a step can't
 * stall). One path per squad, not per person: the squad's members follow their leader.
 * The search state is kept per active page (ActiveSet, cells indexed as in Flow) and paths go only through active
 * pages: a goal beyond them gets a path to the first frontier cell the search reaches towards it, and the squad
 * plans again later, by when the pages round it are active.
 */
public final class NavPath implements ActiveSet.Paged {
    private final Region r;
    private final int w, h;
    private final int[] slotOf;
    private final int ppw;
    private final boolean dense;
    private float[] g;
    /** stamp: cur = seen in this search, cur + 1 = closed in this search (one array instead of two). */
    private int[] prev, stamp;
    private byte[][] navS;
    private int[] sx0, sy0;
    private int cur;
    private int[] heap = new int[1024];
    private float[] key = new float[1024];
    private int hn;
    public long searched;

    public NavPath(Region r, ActiveSet act) {
        this.r = r;
        w = r.navW;
        h = r.navH;
        slotOf = act.slotOf;
        ppw = act.pw;
        dense = !act.sparse;
        act.register(this);
    }

    @Override
    public void slotsGrown(int cap) {
        int n = cap << 10;
        g = g == null ? new float[n] : Arrays.copyOf(g, n);
        prev = prev == null ? new int[n] : Arrays.copyOf(prev, n);
        stamp = stamp == null ? new int[n] : Arrays.copyOf(stamp, n);
        navS = navS == null ? new byte[cap][] : Arrays.copyOf(navS, cap);
        sx0 = sx0 == null ? new int[cap] : Arrays.copyOf(sx0, cap);
        sy0 = sy0 == null ? new int[cap] : Arrays.copyOf(sy0, cap);
    }

    @Override
    public void slotAssigned(int s, int p) {
        // (stamps left in the slot are from earlier searches: always below cur)
        navS[s] = r.navPage(p);
        sx0[s] = (p % ppw) << 5;
        sy0[s] = (p / ppw) << 5;
    }

    /** The index of cell (cx, cy), which must be on the map, or -1 if its page isn't active. */
    private int index(int cx, int cy) {
        int s = dense ? (cy >> 5) * ppw + (cx >> 5) : slotOf[(cy >> 5) * ppw + (cx >> 5)];
        return s < 0 ? -1 : s << 10 | (cy & 31) << 5 | (cx & 31);
    }

    private int navOf(int i) {
        return navS[i >>> 10][i & 1023];
    }

    /** Cell (cx, cy) open, as the search sees it (off the map or on a page that isn't active: blocked). */
    private boolean open(int cx, int cy) {
        if (!in(cx, cy)) return false;
        int s = dense ? (cy >> 5) * ppw + (cx >> 5) : slotOf[(cy >> 5) * ppw + (cx >> 5)];
        return s >= 0 && navS[s][(cy & 31) << 5 | (cx & 31)] != 0;
    }

    private int cellX(int i) {
        return sx0[i >>> 10] + (i & 31);
    }

    private int cellY(int i) {
        return sy0[i >>> 10] + ((i >> 5) & 31);
    }

    /** Waypoints (x0, y0, x1, y1, ...) in px from a to b, or null. budget = most cells to search. */
    public float[] find(float ax, float ay, float bx, float by, int budget) {
        float cs = Tile.SIZE * Region.NAV;
        int sx = (int) (ax / cs), sy = (int) (ay / cs), ex = (int) (bx / cs), ey = (int) (by / cs);
        if (!in(sx, sy) || !in(ex, ey)) return null;
        // a goal on a page that isn't active: towards it, as far as the frontier
        boolean beyond = index(ex, ey) < 0;
        int start = index(sx, sy), goal = beyond ? -1 : nearestOpen(ex, ey);
        if (goal < 0 && !beyond) return null;
        if (start < 0 || navOf(start) == 0) {
            start = nearestOpen(sx, sy);
            if (start < 0) return null;
        }
        if (r.lineOfSight(ax, ay, bx, by) && straightWalkable(ax, ay, bx, by)) return new float[]{bx, by};
        cur += 2;
        if (cur > Integer.MAX_VALUE - 4) {
            java.util.Arrays.fill(stamp, 0);
            cur = 2;
        }
        hn = 0;
        g[start] = 0;
        prev[start] = -1;
        stamp[start] = cur;
        push(start, 0);
        int gx = beyond ? ex : cellX(goal), gy = beyond ? ey : cellY(goal);
        int found = -1, n = 0, bestC = start;
        float bestH = Float.MAX_VALUE;
        while (hn > 0 && n < budget) {
            int c = pop();
            if (stamp[c] == cur + 1) continue;
            stamp[c] = cur + 1;
            n++;
            if (c == goal) {
                found = c;
                break;
            }
            int l = c & 1023, lx = l & 31, ly = l >> 5;
            int cx = sx0[c >>> 10] + lx, cy = sy0[c >>> 10] + ly;
            float hh = octile(cx, cy, gx, gy);
            if (hh < bestH) {
                bestH = hh;
                bestC = c;
            }
            float gc = g[c];
            if (lx > 0 && lx < 31 && ly > 0 && ly < 31) {
                // all eight neighbours on this page
                byte[] nv = navS[c >>> 10];
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int off = dy * 32 + dx, ni = c + off;
                        byte nn = nv[l + off];
                        if (nn == 0 || stamp[ni] == cur + 1) continue;
                        if (dx != 0 && dy != 0 && (nv[l + dx] == 0 || nv[l + dy * 32] == 0)) continue;
                        float step = (dx != 0 && dy != 0) ? 1.414f : 1f;
                        if (nn == 2) step *= 0.9f; // a slight liking for streets
                        float ng = gc + step;
                        if (stamp[ni] != cur || ng < g[ni]) {
                            stamp[ni] = cur;
                            g[ni] = ng;
                            prev[ni] = c;
                            push(ni, ng + octile(cx + dx, cy + dy, gx, gy));
                        }
                    }
                continue;
            }
            boolean edge = false;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = cx + dx, ny = cy + dy;
                    if (!in(nx, ny)) continue;
                    int ni = index(nx, ny);
                    if (ni < 0) {
                        edge = true;
                        continue;
                    }
                    int nn = navOf(ni);
                    if (nn == 0 || stamp[ni] == cur + 1) continue;
                    if (dx != 0 && dy != 0 && (!open(nx, cy) || !open(cx, ny))) continue;
                    float step = (dx != 0 && dy != 0) ? 1.414f : 1f;
                    if (nn == 2) step *= 0.9f; // a slight liking for streets
                    float ng = gc + step;
                    if (stamp[ni] != cur || ng < g[ni]) {
                        stamp[ni] = cur;
                        g[ni] = ng;
                        prev[ni] = c;
                        push(ni, ng + octile(nx, ny, gx, gy));
                    }
                }
            // heading beyond the active pages: the first frontier cell reached is as far as this path goes
            if (beyond && edge) {
                found = c;
                break;
            }
        }
        searched += n;
        if (found < 0) found = bestC; // out of budget: go as far as we got
        int len = 0;
        for (int c = found; c >= 0; c = prev[c]) len++;
        int[] cells = new int[len];
        int i = len;
        for (int c = found; c >= 0; c = prev[c]) cells[--i] = c;
        // keep only the corners we can't see past
        float[] pts = new float[len * 2 + 2];
        int pn = 0;
        float px = ax, py = ay;
        int k = 0;
        while (k < len - 1) {
            int far = k + 1;
            for (int j = len - 1; j > k + 1; j--) {
                float jx = (cellX(cells[j]) + 0.5f) * cs, jy = (cellY(cells[j]) + 0.5f) * cs;
                if (straightWalkable(px, py, jx, jy)) {
                    far = j;
                    break;
                }
            }
            px = (cellX(cells[far]) + 0.5f) * cs;
            py = (cellY(cells[far]) + 0.5f) * cs;
            pts[pn++] = px;
            pts[pn++] = py;
            k = far;
        }
        if (found == goal) {
            pts[pn++] = bx;
            pts[pn++] = by;
        }
        if (pn == 0) return null;
        return Arrays.copyOf(pts, pn);
    }

    private boolean in(int x, int y) {
        return x >= 0 && y >= 0 && x < w && y < h;
    }

    /** The open cell nearest (cx, cy) (on an active page), or -1. */
    private int nearestOpen(int cx, int cy) {
        for (int rad = 0; rad < 6; rad++)
            for (int dy = -rad; dy <= rad; dy++)
                for (int dx = -rad; dx <= rad; dx++) {
                    int x = cx + dx, y = cy + dy;
                    if (open(x, y)) return index(x, y);
                }
        return -1;
    }

    /** True if every navigation cell along the line is open (and on an active page). */
    public boolean straightWalkable(float ax, float ay, float bx, float by) {
        float cs = Tile.SIZE * Region.NAV;
        float dx = bx - ax, dy = by - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        int steps = (int) (len / (cs * 0.5f)) + 1;
        for (int s = 0; s <= steps; s++) {
            float x = ax + dx * s / steps, y = ay + dy * s / steps;
            int cx = (int) (x / cs), cy = (int) (y / cs);
            if (!open(cx, cy)) return false;
        }
        return true;
    }

    private static float octile(int ax, int ay, int bx, int by) {
        int dx = Math.abs(ax - bx), dy = Math.abs(ay - by);
        return Math.max(dx, dy) + 0.414f * Math.min(dx, dy);
    }

    private void push(int c, float k) {
        if (hn + 2 >= heap.length) {
            heap = Arrays.copyOf(heap, heap.length * 2);
            key = Arrays.copyOf(key, key.length * 2);
        }
        int i = ++hn;
        heap[i] = c;
        key[i] = k;
        while (i > 1 && key[i >> 1] > key[i]) {
            int t = heap[i]; heap[i] = heap[i >> 1]; heap[i >> 1] = t;
            float tk = key[i]; key[i] = key[i >> 1]; key[i >> 1] = tk;
            i >>= 1;
        }
    }

    private int pop() {
        int top = heap[1];
        heap[1] = heap[hn];
        key[1] = key[hn];
        hn--;
        int i = 1;
        while (true) {
            int l = i * 2, rr = l + 1, m = i;
            if (l <= hn && key[l] < key[m]) m = l;
            if (rr <= hn && key[rr] < key[m]) m = rr;
            if (m == i) break;
            int t = heap[i]; heap[i] = heap[m]; heap[m] = t;
            float tk = key[i]; key[i] = key[m]; key[m] = tk;
            i = m;
        }
        return top;
    }
}
