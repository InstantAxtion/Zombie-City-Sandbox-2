package com.instantaxtion.zcs2;

import java.util.Arrays;

/**
 * Paths for people on foot over the navigation grid (A*, 8 directions, a counted search budget so a step can't
 * stall). One path per squad, not per person: the squad's members follow their leader.
 */
public final class NavPath {
    private final Region r;
    private final int w, h;
    private final float[] g;
    private final int[] prev, stamp, closed;
    private int cur;
    private int[] heap = new int[1024];
    private float[] key = new float[1024];
    private int hn;
    public long searched;

    public NavPath(Region r) {
        this.r = r;
        w = r.navW;
        h = r.navH;
        g = new float[w * h];
        prev = new int[w * h];
        stamp = new int[w * h];
        closed = new int[w * h];
    }

    /** Waypoints (x0, y0, x1, y1, ...) in px from a to b, or null. budget = most cells to search. */
    public float[] find(float ax, float ay, float bx, float by, int budget) {
        float cs = Tile.SIZE * Region.NAV;
        int sx = (int) (ax / cs), sy = (int) (ay / cs), ex = (int) (bx / cs), ey = (int) (by / cs);
        if (!in(sx, sy) || !in(ex, ey)) return null;
        int start = sy * w + sx, goal = nearestOpen(ex, ey);
        if (goal < 0) return null;
        if (r.nav[start] == 0) {
            start = nearestOpen(sx, sy);
            if (start < 0) return null;
        }
        if (r.lineOfSight(ax, ay, bx, by) && straightWalkable(ax, ay, bx, by)) return new float[]{bx, by};
        cur++;
        hn = 0;
        g[start] = 0;
        prev[start] = -1;
        stamp[start] = cur;
        push(start, 0);
        int gx = goal % w, gy = goal / w;
        int found = -1, n = 0, bestC = start;
        float bestH = Float.MAX_VALUE;
        while (hn > 0 && n < budget) {
            int c = pop();
            if (closed[c] == cur) continue;
            closed[c] = cur;
            n++;
            if (c == goal) {
                found = c;
                break;
            }
            int cx = c % w, cy = c / w;
            float hh = octile(cx, cy, gx, gy);
            if (hh < bestH) {
                bestH = hh;
                bestC = c;
            }
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = cx + dx, ny = cy + dy;
                    if (!in(nx, ny)) continue;
                    int ni = ny * w + nx;
                    if (r.nav[ni] == 0 || closed[ni] == cur) continue;
                    if (dx != 0 && dy != 0 && (r.nav[cy * w + nx] == 0 || r.nav[ny * w + cx] == 0)) continue;
                    float step = (dx != 0 && dy != 0) ? 1.414f : 1f;
                    if (r.nav[ni] == 2) step *= 0.9f; // a slight liking for streets
                    float ng = g[c] + step;
                    if (stamp[ni] != cur || ng < g[ni]) {
                        stamp[ni] = cur;
                        g[ni] = ng;
                        prev[ni] = c;
                        push(ni, ng + octile(nx, ny, gx, gy));
                    }
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
                float jx = (cells[j] % w + 0.5f) * cs, jy = (cells[j] / w + 0.5f) * cs;
                if (straightWalkable(px, py, jx, jy)) {
                    far = j;
                    break;
                }
            }
            px = (cells[far] % w + 0.5f) * cs;
            py = (cells[far] / w + 0.5f) * cs;
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

    private int nearestOpen(int cx, int cy) {
        for (int rad = 0; rad < 6; rad++)
            for (int dy = -rad; dy <= rad; dy++)
                for (int dx = -rad; dx <= rad; dx++) {
                    int x = cx + dx, y = cy + dy;
                    if (in(x, y) && r.nav[y * w + x] != 0) return y * w + x;
                }
        return -1;
    }

    /** True if every navigation cell along the line is open. */
    public boolean straightWalkable(float ax, float ay, float bx, float by) {
        float cs = Tile.SIZE * Region.NAV;
        float dx = bx - ax, dy = by - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        int steps = (int) (len / (cs * 0.5f)) + 1;
        for (int s = 0; s <= steps; s++) {
            float x = ax + dx * s / steps, y = ay + dy * s / steps;
            int cx = (int) (x / cs), cy = (int) (y / cs);
            if (!in(cx, cy) || r.nav[cy * w + cx] == 0) return false;
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
