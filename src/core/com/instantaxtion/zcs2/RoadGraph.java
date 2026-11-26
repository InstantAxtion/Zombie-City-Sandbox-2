package com.instantaxtion.zcs2;

import java.util.Arrays;

/**
 * The road network as a graph (junctions and the roads between them). Vehicles route and drive on this graph,
 * never on tiles, so they cannot wander off a road or get lost in a tile search.
 */
public final class RoadGraph {
    public static final byte STREET = 0, AVENUE = 1, HIGHWAY = 2;
    private static final float[] KIND_SPEED = {1f, 1.15f, 1.7f};

    public int nodeCount, edgeCount;
    public float[] nx = new float[256], ny = new float[256];
    public short[] nset = new short[256];
    public int[] ea = new int[256], eb = new int[256];
    public float[] elen = new float[256], epen = new float[256];
    public byte[] ekind = new byte[256];
    public boolean[] ebridge = new boolean[256], ealive = new boolean[256];
    /** Half the road's width in px (vehicles keep to their own side of it). */
    public float[] ehalf = new float[256];
    public int[] adjStart, adjEdge;

    private int[] bucketStart, bucketNode;
    private int bw, bh;
    /** Node bucket side in px: 256 on today's maps (so searches visit nodes in the same order as always), 2,048 on national maps. */
    private int bucketPx = 256;
    /** Worlds wider than this (px) are national maps and get the coarser node buckets. */
    public static final int NATIONAL_PX = 65536;
    /** Duplicate-edge index: (smaller node, larger node) to edge. Dropped by finish() and rebuilt if edges are added later. */
    private LongIntMap dup;

    public int addNode(float x, float y, int settlement) {
        if (nodeCount == nx.length) {
            int n = nx.length * 2;
            nx = Arrays.copyOf(nx, n);
            ny = Arrays.copyOf(ny, n);
            nset = Arrays.copyOf(nset, n);
        }
        nx[nodeCount] = x;
        ny[nodeCount] = y;
        nset[nodeCount] = (short) settlement;
        return nodeCount++;
    }

    public int addEdge(int a, int b, byte kind, float halfWidth) {
        if (a == b) return -1;
        if (dup == null) {
            dup = new LongIntMap(Math.max(16, edgeCount * 2));
            for (int e = 0; e < edgeCount; e++) if (dup.get(pairKey(ea[e], eb[e])) < 0) dup.put(pairKey(ea[e], eb[e]), e);
        }
        long key = pairKey(a, b);
        int have = dup.get(key);
        if (have >= 0) return have;
        dup.put(key, edgeCount);
        if (edgeCount == ea.length) {
            int n = ea.length * 2;
            ea = Arrays.copyOf(ea, n);
            eb = Arrays.copyOf(eb, n);
            elen = Arrays.copyOf(elen, n);
            epen = Arrays.copyOf(epen, n);
            ekind = Arrays.copyOf(ekind, n);
            ebridge = Arrays.copyOf(ebridge, n);
            ealive = Arrays.copyOf(ealive, n);
            ehalf = Arrays.copyOf(ehalf, n);
        }
        ea[edgeCount] = a;
        eb[edgeCount] = b;
        float dx = nx[b] - nx[a], dy = ny[b] - ny[a];
        elen[edgeCount] = (float) Math.sqrt(dx * dx + dy * dy);
        ekind[edgeCount] = kind;
        ealive[edgeCount] = true;
        ehalf[edgeCount] = halfWidth;
        return edgeCount++;
    }

    private static long pairKey(int a, int b) {
        return a < b ? ((long) a << 32) | (b & 0xFFFFFFFFL) : ((long) b << 32) | (a & 0xFFFFFFFFL);
    }

    /** Builds the adjacency lists and the node lookup grid. Call after adding everything. */
    public void finish(int worldPx) {
        dup = null;
        bucketPx = worldPx > NATIONAL_PX ? 2048 : 256;
        int[] deg = new int[nodeCount + 1];
        for (int e = 0; e < edgeCount; e++) {
            deg[ea[e]]++;
            deg[eb[e]]++;
        }
        adjStart = new int[nodeCount + 1];
        for (int i = 0; i < nodeCount; i++) adjStart[i + 1] = adjStart[i] + deg[i];
        adjEdge = new int[adjStart[nodeCount]];
        int[] fill = Arrays.copyOf(adjStart, nodeCount);
        for (int e = 0; e < edgeCount; e++) {
            adjEdge[fill[ea[e]]++] = e;
            adjEdge[fill[eb[e]]++] = e;
        }
        bw = worldPx / bucketPx + 1;
        bh = bw;
        int[] cnt = new int[bw * bh + 1];
        for (int i = 0; i < nodeCount; i++) cnt[bucket(nx[i], ny[i])]++;
        bucketStart = new int[bw * bh + 1];
        for (int i = 0; i < bw * bh; i++) bucketStart[i + 1] = bucketStart[i] + cnt[i];
        bucketNode = new int[nodeCount];
        int[] f2 = Arrays.copyOf(bucketStart, bw * bh);
        for (int i = 0; i < nodeCount; i++) bucketNode[f2[bucket(nx[i], ny[i])]++] = i;
        g = new float[nodeCount];
        prev = new int[nodeCount];
        stamp = new int[nodeCount];
        heap = new int[nodeCount + 1];
        hkey = new float[nodeCount + 1];
        closed = new int[nodeCount];
    }

    private int bucket(float x, float y) {
        int cx = Math.max(0, Math.min(bw - 1, (int) (x / bucketPx)));
        int cy = Math.max(0, Math.min(bh - 1, (int) (y / bucketPx)));
        return cy * bw + cx;
    }

    public int other(int e, int n) {
        return ea[e] == n ? eb[e] : ea[e];
    }

    public boolean nodeLive(int n) {
        for (int i = adjStart[n]; i < adjStart[n + 1]; i++) if (ealive[adjEdge[i]]) return true;
        return false;
    }

    /** The nearest junction with at least one usable road, or -1 if none within maxDist. */
    public int nearestNode(float x, float y, float maxDist) {
        int best = -1;
        float bd = maxDist * maxDist;
        int r = (int) (maxDist / bucketPx) + 1;
        int cx = (int) (x / bucketPx), cy = (int) (y / bucketPx);
        for (int by = Math.max(0, cy - r); by <= Math.min(bh - 1, cy + r); by++)
            for (int bx = Math.max(0, cx - r); bx <= Math.min(bw - 1, cx + r); bx++) {
                int b = by * bw + bx;
                for (int i = bucketStart[b]; i < bucketStart[b + 1]; i++) {
                    int n = bucketNode[i];
                    float dx = nx[n] - x, dy = ny[n] - y, d = dx * dx + dy * dy;
                    if (d < bd && nodeLive(n)) {
                        bd = d;
                        best = n;
                    }
                }
            }
        return best;
    }

    public int edgeBetween(int a, int b) {
        for (int i = adjStart[a]; i < adjStart[a + 1]; i++) {
            int e = adjEdge[i];
            if (other(e, a) == b) return e;
        }
        return -1;
    }

    public float edgeSpeed(int e) {
        return KIND_SPEED[ekind[e]];
    }

    // ---- A* ----
    private float[] g, hkey;
    private int[] prev, stamp, heap, closed;
    private int cur, hn;
    /** Junctions searched, for the performance probe. */
    public long searched;

    /** The junctions from a to b (both included), or null if b can't be reached. */
    public int[] route(int a, int b) {
        if (a < 0 || b < 0) return null;
        if (a == b) return new int[]{a};
        cur++;
        if (cur == Integer.MAX_VALUE) {
            Arrays.fill(stamp, 0);
            Arrays.fill(closed, 0);
            cur = 1;
        }
        hn = 0;
        g[a] = 0;
        prev[a] = -1;
        stamp[a] = cur;
        push(a, h(a, b));
        float vmax = KIND_SPEED[HIGHWAY];
        while (hn > 0) {
            int n = pop();
            if (closed[n] == cur) continue;
            closed[n] = cur;
            searched++;
            if (n == b) break;
            for (int i = adjStart[n]; i < adjStart[n + 1]; i++) {
                int e = adjEdge[i];
                if (!ealive[e]) continue;
                int m = other(e, n);
                if (closed[m] == cur) continue;
                float c = g[n] + elen[e] / KIND_SPEED[ekind[e]] + epen[e];
                if (stamp[m] != cur || c < g[m]) {
                    stamp[m] = cur;
                    g[m] = c;
                    prev[m] = n;
                    push(m, c + h(m, b) / vmax);
                }
            }
        }
        if (stamp[b] != cur || closed[b] != cur) return null;
        int len = 1;
        for (int n = b; prev[n] >= 0; n = prev[n]) len++;
        int[] out = new int[len];
        int i = len - 1;
        for (int n = b; n >= 0; n = prev[n]) {
            out[i--] = n;
            if (n == a) break;
        }
        return out;
    }

    private float h(int a, int b) {
        float dx = nx[a] - nx[b], dy = ny[a] - ny[b];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void push(int n, float k) {
        int i = ++hn;
        heap[i] = n;
        hkey[i] = k;
        while (i > 1 && hkey[i >> 1] > hkey[i]) {
            swap(i, i >> 1);
            i >>= 1;
        }
        if (hn >= heap.length - 1) {
            heap = Arrays.copyOf(heap, heap.length * 2);
            hkey = Arrays.copyOf(hkey, hkey.length * 2);
        }
    }

    private int pop() {
        int top = heap[1];
        heap[1] = heap[hn];
        hkey[1] = hkey[hn];
        hn--;
        int i = 1;
        while (true) {
            int l = i * 2, r = l + 1, m = i;
            if (l <= hn && hkey[l] < hkey[m]) m = l;
            if (r <= hn && hkey[r] < hkey[m]) m = r;
            if (m == i) break;
            swap(i, m);
            i = m;
        }
        return top;
    }

    private void swap(int a, int b) {
        int t = heap[a];
        heap[a] = heap[b];
        heap[b] = t;
        float k = hkey[a];
        hkey[a] = hkey[b];
        hkey[b] = k;
    }

    /** How many junctions can be reached from n (for tests: the network should be one piece). */
    public int reachable(int n) {
        boolean[] seen = new boolean[nodeCount];
        int[] q = new int[nodeCount];
        int h = 0, t = 0;
        q[t++] = n;
        seen[n] = true;
        while (h < t) {
            int a = q[h++];
            for (int i = adjStart[a]; i < adjStart[a + 1]; i++) {
                int e = adjEdge[i];
                if (!ealive[e]) continue;
                int b = other(e, a);
                if (!seen[b]) {
                    seen[b] = true;
                    q[t++] = b;
                }
            }
        }
        return t;
    }

    // ------------------------------------------------------------------ national maps
    // Everything below is used only by national maps (NationGen); today's maps never call it.

    /** Shrinks every array to exactly what is used (a national graph is built once and then only read). */
    public void trim() {
        nx = Arrays.copyOf(nx, Math.max(1, nodeCount));
        ny = Arrays.copyOf(ny, Math.max(1, nodeCount));
        nset = Arrays.copyOf(nset, Math.max(1, nodeCount));
        int n = Math.max(1, edgeCount);
        ea = Arrays.copyOf(ea, n);
        eb = Arrays.copyOf(eb, n);
        elen = Arrays.copyOf(elen, n);
        epen = Arrays.copyOf(epen, n);
        ekind = Arrays.copyOf(ekind, n);
        ebridge = Arrays.copyOf(ebridge, n);
        ealive = Arrays.copyOf(ealive, n);
        ehalf = Arrays.copyOf(ehalf, n);
    }

    // ---- edges by chunk: which roads to draw into a chunk, and which to check for barricades ----
    public int chunkPx, chunkW;
    public int[] chunkEdgeStart, chunkEdge;

    /** Buckets every edge into each chunk (chunkPx square) its road surface touches. */
    public void buildEdgeBuckets(int worldPx, int chunkPx) {
        this.chunkPx = chunkPx;
        chunkW = (worldPx + chunkPx - 1) / chunkPx;
        int nc = chunkW * chunkW;
        int[] cnt = new int[nc + 1];
        for (int pass = 0; pass < 2; pass++) {
            for (int e = 0; e < edgeCount; e++) {
                float ax = nx[ea[e]], ay = ny[ea[e]], bx = nx[eb[e]], by = ny[eb[e]], m = ehalf[e] + 1;
                int x0 = clampC((int) ((Math.min(ax, bx) - m) / chunkPx)), x1 = clampC((int) ((Math.max(ax, bx) + m) / chunkPx));
                int y0 = clampC((int) ((Math.min(ay, by) - m) / chunkPx)), y1 = clampC((int) ((Math.max(ay, by) + m) / chunkPx));
                boolean straight = x0 == x1 || y0 == y1;
                for (int cy = y0; cy <= y1; cy++)
                    for (int cx = x0; cx <= x1; cx++) {
                        if (!straight && segRectDist(ax, ay, bx, by, cx * chunkPx, cy * chunkPx, chunkPx) > m) continue;
                        int c = cy * chunkW + cx;
                        if (pass == 0) cnt[c]++;
                        else chunkEdge[cnt[c]++] = e;
                    }
            }
            if (pass == 0) {
                chunkEdgeStart = new int[nc + 1];
                for (int i = 0; i < nc; i++) chunkEdgeStart[i + 1] = chunkEdgeStart[i] + cnt[i];
                chunkEdge = new int[chunkEdgeStart[nc]];
                System.arraycopy(chunkEdgeStart, 0, cnt, 0, nc);
            }
        }
    }

    private int clampC(int v) {
        return v < 0 ? 0 : v >= chunkW ? chunkW - 1 : v;
    }

    /** The distance from segment a-b to the square at (x0, y0) of side s (0 if they touch). */
    private static float segRectDist(float ax, float ay, float bx, float by, float x0, float y0, float s) {
        float x1 = x0 + s, y1 = y0 + s;
        // Liang-Barsky: does the segment cross the square?
        float t0 = 0, t1 = 1, dx = bx - ax, dy = by - ay;
        float[] p = {-dx, dx, -dy, dy}, q = {ax - x0, x1 - ax, ay - y0, y1 - ay};
        boolean hit = true;
        for (int i = 0; i < 4 && hit; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) hit = false;
            } else {
                float r = q[i] / p[i];
                if (p[i] < 0) t0 = Math.max(t0, r);
                else t1 = Math.min(t1, r);
                if (t0 > t1) hit = false;
            }
        }
        if (hit) return 0;
        float d = Math.min(ptRect(ax, ay, x0, y0, x1, y1), ptRect(bx, by, x0, y0, x1, y1));
        d = Math.min(d, Math.min(ptSeg(x0, y0, ax, ay, bx, by), ptSeg(x1, y0, ax, ay, bx, by)));
        return Math.min(d, Math.min(ptSeg(x0, y1, ax, ay, bx, by), ptSeg(x1, y1, ax, ay, bx, by)));
    }

    private static float ptRect(float x, float y, float x0, float y0, float x1, float y1) {
        float dx = Math.max(Math.max(x0 - x, 0), x - x1), dy = Math.max(Math.max(y0 - y, 0), y - y1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float ptSeg(float x, float y, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        float t = l2 <= 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / l2));
        float px = ax + dx * t - x, py = ay + dy * t - y;
        return (float) Math.sqrt(px * px + py * py);
    }

    /** How many edges touch chunk (cx, cy); they are chunkEdge[chunkEdgeStart[cy * chunkW + cx] ...]. */
    public int chunkEdges(int cx, int cy) {
        int c = cy * chunkW + cx;
        return chunkEdgeStart[c + 1] - chunkEdgeStart[c];
    }

    // ---- level 1: highways, arterials and avenues, with their own adjacency ----
    /** Level-1 index of each junction (-1 if it has no level-1 road), and back. */
    public int[] l1Of, l1Node, l1AdjStart, l1AdjEdge;
    public int l1Count;

    public static boolean level1(byte kind) {
        return kind >= AVENUE;
    }

    /** Builds the level-1 adjacency. Call after finish(). */
    public void buildLevels() {
        l1Of = new int[nodeCount];
        Arrays.fill(l1Of, -1);
        l1Count = 0;
        int[] deg = new int[nodeCount];
        for (int e = 0; e < edgeCount; e++)
            if (level1(ekind[e])) {
                deg[ea[e]]++;
                deg[eb[e]]++;
            }
        for (int n = 0; n < nodeCount; n++) if (deg[n] > 0) l1Of[n] = l1Count++;
        l1Node = new int[l1Count];
        l1AdjStart = new int[l1Count + 1];
        for (int n = 0; n < nodeCount; n++)
            if (l1Of[n] >= 0) {
                l1Node[l1Of[n]] = n;
                l1AdjStart[l1Of[n] + 1] = deg[n];
            }
        for (int i = 0; i < l1Count; i++) l1AdjStart[i + 1] += l1AdjStart[i];
        l1AdjEdge = new int[l1AdjStart[l1Count]];
        int[] fill = Arrays.copyOf(l1AdjStart, l1Count);
        for (int e = 0; e < edgeCount; e++)
            if (level1(ekind[e])) {
                l1AdjEdge[fill[l1Of[ea[e]]]++] = e;
                l1AdjEdge[fill[l1Of[eb[e]]]++] = e;
            }
    }

    /** Trips shorter than this (px, straight line) are searched with plain A*: 3 km. */
    public float shortTrip = 12000f;
    /** Level-1 junctions gathered round each end, and the most junctions each gathering search may settle. */
    public int entryPoints = 8, entryBudget = 6000;
    private float[] bg, l1g, l1tc;
    private int[] bprev, bstamp, bclosed, l1prev, l1stamp, l1closed, l1tgt, seenAt, seenStamp;
    private int bcur, l1cur, seenCur;

    /**
     * Two-level route. Short trips use plain A*. Longer ones gather up to entryPoints level-1 junctions round each
     * end with a bounded Dijkstra over every road, search level 1 alone from all the starts to all the ends, and
     * join the three pieces. Falls back to plain A* when level 1 can't join the ends. Needs buildLevels().
     */
    public int[] route2(int a, int b) {
        if (a < 0 || b < 0) return null;
        if (a == b) return new int[]{a};
        if (l1Of == null || h(a, b) < shortTrip) return route(a, b);
        if (bg == null) {
            bg = new float[nodeCount];
            bprev = new int[nodeCount];
            bstamp = new int[nodeCount];
            bclosed = new int[nodeCount];
            l1g = new float[l1Count];
            l1tc = new float[l1Count];
            l1prev = new int[l1Count];
            l1stamp = new int[l1Count];
            l1closed = new int[l1Count];
            l1tgt = new int[l1Count];
        }
        // forward from a, in the A* arrays
        cur++;
        if (cur == Integer.MAX_VALUE) {
            Arrays.fill(stamp, 0);
            Arrays.fill(closed, 0);
            cur = 1;
        }
        int[] fwd = new int[entryPoints];
        int nf = gather(a, b, fwd, g, prev, stamp, closed, cur);
        if (nf < 0) return path(a, b, prev); // b turned up on the way
        if (++bcur == Integer.MAX_VALUE) {
            Arrays.fill(bstamp, 0);
            Arrays.fill(bclosed, 0);
            bcur = 1;
        }
        int[] bwd = new int[entryPoints];
        int nb = gather(b, -1, bwd, bg, bprev, bstamp, bclosed, bcur);
        if (nf == 0 || nb <= 0) return route(a, b);
        if (++l1cur == Integer.MAX_VALUE) {
            Arrays.fill(l1stamp, 0);
            Arrays.fill(l1closed, 0);
            Arrays.fill(l1tgt, 0);
            l1cur = 1;
        }
        float vmax = KIND_SPEED[HIGHWAY];
        hn = 0;
        for (int k = 0; k < nf; k++) {
            int s = fwd[k], i = l1Of[s];
            l1g[i] = g[s];
            l1prev[i] = -1;
            l1stamp[i] = l1cur;
            push(i, g[s] + h(s, b) / vmax);
        }
        for (int k = 0; k < nb; k++) {
            int i = l1Of[bwd[k]];
            l1tgt[i] = l1cur;
            l1tc[i] = bg[bwd[k]];
        }
        float best = Float.MAX_VALUE;
        int bestT = -1;
        while (hn > 0 && hkey[1] < best) {
            int i = pop();
            if (l1closed[i] == l1cur) continue;
            l1closed[i] = l1cur;
            searched++;
            int n = l1Node[i];
            if (l1tgt[i] == l1cur && l1g[i] + l1tc[i] < best) {
                best = l1g[i] + l1tc[i];
                bestT = i;
            }
            for (int q = l1AdjStart[i]; q < l1AdjStart[i + 1]; q++) {
                int e = l1AdjEdge[q];
                if (!ealive[e]) continue;
                int m = other(e, n), j = l1Of[m];
                if (l1closed[j] == l1cur) continue;
                float c = l1g[i] + elen[e] / KIND_SPEED[ekind[e]] + epen[e];
                if (l1stamp[j] != l1cur || c < l1g[j]) {
                    l1stamp[j] = l1cur;
                    l1g[j] = c;
                    l1prev[j] = i;
                    push(j, c + h(m, b) / vmax);
                }
            }
        }
        if (bestT < 0) return route(a, b);
        // stitch: a .. s (forward tree), s .. t (level 1), t .. b (backward tree)
        int len1 = 0;
        for (int i = bestT; i >= 0; i = l1prev[i]) len1++;
        int[] mid = new int[len1];
        int k = len1;
        for (int i = bestT; i >= 0; i = l1prev[i]) mid[--k] = l1Node[i];
        int[] head = path(a, mid[0], prev);
        int t = mid[len1 - 1], tail = 0;
        for (int n = t; n != b; n = bprev[n]) tail++;
        int[] out = new int[head.length + len1 - 1 + tail];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(mid, 1, out, head.length, len1 - 1);
        int o = head.length + len1 - 1;
        for (int n = t; n != b; ) {
            n = bprev[n];
            out[o++] = n;
        }
        return dropLoops(out);
    }

    /**
     * Dijkstra from 'from' over every live road, settling at most entryBudget junctions and collecting level-1
     * junctions into out. Returns how many, or -1 if it settled 'stop' first.
     */
    private int gather(int from, int stop, int[] out, float[] gg, int[] pp, int[] st, int[] cl, int cu) {
        hn = 0;
        gg[from] = 0;
        pp[from] = -1;
        st[from] = cu;
        push(from, 0);
        int found = 0, settled = 0;
        while (hn > 0) {
            int n = pop();
            if (cl[n] == cu) continue;
            cl[n] = cu;
            searched++;
            if (n == stop) return -1;
            if (l1Of[n] >= 0) {
                out[found++] = n;
                if (found == out.length) break;
            }
            if (++settled >= entryBudget) break;
            for (int i = adjStart[n]; i < adjStart[n + 1]; i++) {
                int e = adjEdge[i];
                if (!ealive[e]) continue;
                int m = other(e, n);
                if (cl[m] == cu) continue;
                float c = gg[n] + elen[e] / KIND_SPEED[ekind[e]] + epen[e];
                if (st[m] != cu || c < gg[m]) {
                    st[m] = cu;
                    gg[m] = c;
                    pp[m] = n;
                    push(m, c);
                }
            }
        }
        return found;
    }

    /** The junctions from a to b, following pp back from b. */
    private static int[] path(int a, int b, int[] pp) {
        int len = 1;
        for (int n = b; n != a; n = pp[n]) len++;
        int[] out = new int[len];
        int n = b;
        for (int i = len - 1; i >= 0; i--) {
            out[i] = n;
            if (i > 0) n = pp[n];
        }
        return out;
    }

    /** Cuts out any loop (a junction visited twice), keeping the route's ends. */
    private int[] dropLoops(int[] p) {
        if (seenAt == null) {
            seenAt = new int[nodeCount];
            seenStamp = new int[nodeCount];
        }
        if (++seenCur == Integer.MAX_VALUE) {
            Arrays.fill(seenStamp, 0);
            seenCur = 1;
        }
        int[] out = new int[p.length];
        int n = 0;
        for (int v : p) {
            if (seenStamp[v] == seenCur) {
                // back where we were: forget everything since
                for (int k = seenAt[v] + 1; k < n; k++) seenStamp[out[k]] = 0;
                n = seenAt[v] + 1;
                continue;
            }
            seenStamp[v] = seenCur;
            seenAt[v] = n;
            out[n++] = v;
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }

    /** The travel cost of a route (what route() and route2() minimise), or -1 if it isn't one. */
    public float routeCost(int[] p) {
        if (p == null) return -1;
        float c = 0;
        for (int i = 1; i < p.length; i++) {
            int e = edgeBetween(p[i - 1], p[i]);
            if (e < 0) return -1;
            c += elen[e] / KIND_SPEED[ekind[e]] + epen[e];
        }
        return c;
    }

    /** Bytes held by the graph's arrays, scratch included (for the memory budget). */
    public long memoryBytes() {
        long b = 4L * (nx.length + ny.length) + 2L * nset.length;
        b += 4L * (ea.length + eb.length + elen.length + epen.length + ehalf.length) + 3L * ekind.length;
        b += 4L * (len(adjStart) + len(adjEdge) + len(bucketStart) + len(bucketNode) + len(prev) + len(stamp) + len(heap) + len(closed));
        b += 4L * (len(g) + len(hkey) + len(bg) + len(bprev) + len(bstamp) + len(bclosed) + len(seenAt) + len(seenStamp));
        b += 4L * (len(chunkEdgeStart) + len(chunkEdge) + len(l1Of) + len(l1Node) + len(l1AdjStart) + len(l1AdjEdge));
        b += 4L * (len(l1g) + len(l1tc) + len(l1prev) + len(l1stamp) + len(l1closed) + len(l1tgt));
        if (dup != null) b += dup.bytes();
        return b;
    }

    private static int len(int[] a) {
        return a == null ? 0 : a.length;
    }

    private static int len(float[] a) {
        return a == null ? 0 : a.length;
    }

    /** A map from long to int by open addressing (-1 = absent). Never iterated, so its order can't reach a result. */
    public static final class LongIntMap {
        private long[] keys;
        private int[] vals;
        private int size, mask;

        public LongIntMap(int expected) {
            int cap = 16;
            while (cap < expected * 2) cap <<= 1;
            keys = new long[cap];
            vals = new int[cap];
            Arrays.fill(vals, -1);
            mask = cap - 1;
        }

        private int slot(long k) {
            return (int) (Rng.mix(k) & mask);
        }

        public int get(long k) {
            for (int i = slot(k); ; i = (i + 1) & mask) {
                if (vals[i] < 0) return -1;
                if (keys[i] == k) return vals[i];
            }
        }

        /** Sets k to v (v >= 0). */
        public void put(long k, int v) {
            if ((size + 1) * 2 > keys.length) grow();
            for (int i = slot(k); ; i = (i + 1) & mask) {
                if (vals[i] < 0) {
                    keys[i] = k;
                    vals[i] = v;
                    size++;
                    return;
                }
                if (keys[i] == k) {
                    vals[i] = v;
                    return;
                }
            }
        }

        private void grow() {
            long[] ok = keys;
            int[] ov = vals;
            keys = new long[ok.length * 2];
            vals = new int[ov.length * 2];
            Arrays.fill(vals, -1);
            mask = keys.length - 1;
            size = 0;
            for (int i = 0; i < ok.length; i++) if (ov[i] >= 0) put(ok[i], ov[i]);
        }

        public int size() {
            return size;
        }

        public long bytes() {
            return keys.length * 12L;
        }
    }
}
