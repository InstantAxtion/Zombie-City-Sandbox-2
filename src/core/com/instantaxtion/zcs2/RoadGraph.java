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
    private static final int BUCKET = 256;

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
        for (int e = 0; e < edgeCount; e++) if ((ea[e] == a && eb[e] == b) || (ea[e] == b && eb[e] == a)) return e;
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

    /** Builds the adjacency lists and the node lookup grid. Call after adding everything. */
    public void finish(int worldPx) {
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
        bw = worldPx / BUCKET + 1;
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
        int cx = Math.max(0, Math.min(bw - 1, (int) (x / BUCKET)));
        int cy = Math.max(0, Math.min(bh - 1, (int) (y / BUCKET)));
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
        int r = (int) (maxDist / BUCKET) + 1;
        int cx = (int) (x / BUCKET), cy = (int) (y / BUCKET);
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
}
