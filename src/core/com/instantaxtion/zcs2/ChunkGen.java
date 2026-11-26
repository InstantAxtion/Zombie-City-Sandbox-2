package com.instantaxtion.zcs2;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Makes one street-level chunk (64x64 tiles, the same square as a sector) of a real-scale map from the coarse national
 * layer, on demand: terrain from the coarse cover with tile-level detail, rivers and lakes, every road edge bucketed
 * into the chunk (at its width, as a bridge over water) and the railways, the large sites' rectangle operations, the
 * national buildings and facility blocks that touch it, then each lattice block filled by its district type and
 * density, farmsteads in the fields, doors, navigation cells, and the sector's residents shared out exactly.
 * <p>
 * generate() is a pure function of (nation, cx, cy): every random choice comes from a hash of the seed and a position
 * (a block's own stream is seeded by its corner), nothing is shared between calls, and the only state kept is a
 * read-only index of the nation built once. So any thread, in any order, makes the same bytes.
 * Floating point is plain float arithmetic and StrictMath (Java's float maths is strict on every VM we run on).
 */
public final class ChunkGen {
    private static final int S = Chunk.SIZE, AREA = Chunk.AREA;
    private static final int G = 10, GM = 3; // the local coarse-cell window: the chunk's 4 cells and 3 more each side

    // ------------------------------------------------------------------ the nation's index (built once, read-only)

    /** Per nation: the things that aren't already bucketed by chunk, as sorted (chunk << 32 | item) lists. */
    static final class Index {
        long[] rivers, rails, nats, ops, facs;
        float[] riverHalf; // per river: half its width in tiles
        Noise jitX, jitY, wob, trees;
    }

    private static final Map<Nation, Index> INDEX = new WeakHashMap<Nation, Index>();

    static Index index(Nation n) {
        synchronized (INDEX) {
            Index ix = INDEX.get(n);
            if (ix == null) {
                ix = build(n);
                INDEX.put(n, ix);
            }
            return ix;
        }
    }

    private static final class Pairs {
        long[] a = new long[1024];
        int n;
        final int sw;

        Pairs(int sw) {
            this.sw = sw;
        }

        /** Adds item to every chunk the tile rectangle touches. */
        void rect(float x0, float y0, float x1, float y1, int item) {
            int cx0 = clamp((int) StrictMath.floor(x0 / S), 0, sw - 1), cx1 = clamp((int) StrictMath.floor(x1 / S), 0, sw - 1);
            int cy0 = clamp((int) StrictMath.floor(y0 / S), 0, sw - 1), cy1 = clamp((int) StrictMath.floor(y1 / S), 0, sw - 1);
            for (int cy = cy0; cy <= cy1; cy++)
                for (int cx = cx0; cx <= cx1; cx++) {
                    if (n == a.length) a = Arrays.copyOf(a, n * 2);
                    a[n++] = ((long) (cy * sw + cx) << 32) | (item & 0xFFFFFFFFL);
                }
        }

        long[] done() {
            long[] out = Arrays.copyOf(a, n);
            Arrays.sort(out);
            return out;
        }
    }

    private static Index build(Nation n) {
        Index ix = new Index();
        int sw = n.sw;
        Pairs p = new Pairs(sw);
        ix.riverHalf = new float[n.rivers.size()];
        for (int r = 0; r < n.rivers.size(); r++) {
            float[] pts = n.rivers.get(r);
            float h = n.riverWidth.get(r) / 2;
            ix.riverHalf[r] = h;
            float m = h + 4 + 16 + GM * Nation.CELL + 8; // as far as the corridor test in terrain() reaches
            for (int i = 0; i + 3 < pts.length; i += 2)
                p.rect(StrictMath.min(pts[i], pts[i + 2]) - m, StrictMath.min(pts[i + 1], pts[i + 3]) - m,
                        StrictMath.max(pts[i], pts[i + 2]) + m, StrictMath.max(pts[i + 1], pts[i + 3]) + m, r << 20 | i / 2);
        }
        ix.rivers = p.done();
        p = new Pairs(sw);
        RoadGraph rl = n.rails;
        for (int e = 0; e < rl.edgeCount; e++) {
            float ax = rl.nx[rl.ea[e]] / 16, ay = rl.ny[rl.ea[e]] / 16, bx = rl.nx[rl.eb[e]] / 16, by = rl.ny[rl.eb[e]] / 16;
            p.rect(StrictMath.min(ax, bx) - 2, StrictMath.min(ay, by) - 2, StrictMath.max(ax, bx) + 2, StrictMath.max(ay, by) + 2, e);
        }
        ix.rails = p.done();
        p = new Pairs(sw);
        for (int b = 0; b < n.bCount; b++) p.rect(n.bx[b], n.by[b], n.bx[b] + n.bw[b] - 1, n.by[b] + n.bh[b] - 1, b);
        ix.nats = p.done();
        p = new Pairs(sw);
        for (int o = 0; o < n.siteOpCount; o++) {
            int k = o * Nation.OP_INTS;
            p.rect(n.siteOps[k + 2], n.siteOps[k + 3], n.siteOps[k + 4], n.siteOps[k + 5], o);
        }
        ix.ops = p.done();
        p = new Pairs(sw);
        for (Region.Facility f : n.facilities)
            if (latticeFacility(n, f)) p.rect(f.bx0, f.by0, f.bx1, f.by1, f.id);
        ix.facs = p.done();
        ix.jitX = new Noise(n.seed + 0xC401);
        ix.jitY = new Noise(n.seed + 0xC402);
        ix.wob = new Noise(n.seed + 0xC403);
        ix.trees = new Noise(n.seed + 0xC404);
        return ix;
    }

    /** A facility on a lattice block of a town (not a site's own facility). */
    static boolean latticeFacility(Nation n, Region.Facility f) {
        if (f.settlement < 0 || f.bx1 <= f.bx0) return false;
        int k = n.settlements.get(f.settlement).kind;
        return k == Region.S_CITY || k == Region.S_TOWN || k == Region.S_VILLAGE;
    }

    /** The first position in sorted list a whose chunk is c (or where it would be). */
    private static int first(long[] a, int c) {
        long key = (long) c << 32;
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < key) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    // ------------------------------------------------------------------ one chunk

    private final Nation n;
    private final Index ix;
    private final Chunk c;
    private final int cx, cy, X0, Y0, sec, kindOfSet;
    private final long seed;
    private final byte[] t;
    private final short[] bld;
    private final boolean[] wet = new boolean[AREA];
    private final byte[] mark = new byte[AREA]; // road marks: 1 street or avenue, 2 highway, 4 rail, 8 highway verge
    private final boolean capital;

    private ChunkGen(Nation n, Index ix, int cx, int cy) {
        this.n = n;
        this.ix = ix;
        this.cx = cx;
        this.cy = cy;
        X0 = cx * S;
        Y0 = cy * S;
        sec = cy * n.sw + cx;
        seed = n.seed;
        c = new Chunk(cx, cy, sec, 64);
        t = c.tile;
        bld = c.bld;
        int set = n.secSettlement[sec];
        c.settlement = set;
        c.district = n.district[sec];
        c.density = n.density[sec];
        kindOfSet = set >= 0 ? n.settlements.get(set).kind : -1;
        capital = set >= 0 && n.settlements.get(set).capital;
    }

    /** The chunk at (cx, cy) in the nation's chunk grid (the same grid as its sectors). */
    public static Chunk generate(Nation n, int cx, int cy) {
        return generate(n, cx, cy, null);
    }

    /** The same, also copying which tiles are water (after rivers, before roads and buildings) into wetOut (tests). */
    static Chunk generate(Nation n, int cx, int cy, boolean[] wetOut) {
        if (cx < 0 || cy < 0 || cx >= n.sw || cy >= n.sw) throw new IllegalArgumentException("chunk " + cx + "," + cy + " off the map");
        ChunkGen g = new ChunkGen(n, index(n), cx, cy);
        g.terrain();
        g.rivers();
        if (wetOut != null) System.arraycopy(g.wet, 0, wetOut, 0, AREA);
        g.roadsAndRails();
        g.sites();
        g.facilityGround();
        g.nationalBuildings();
        g.blocks();
        g.farms();
        g.residents();
        g.doors();
        g.nav();
        g.c.trim();
        return g.c;
    }

    // ------------------------------------------------------------------ 1. terrain

    private void terrain() {
        int CX0 = cx * 4 - GM, CY0 = cy * 4 - GM;
        byte[] cov = new byte[G * G];
        boolean[] cwet = new boolean[G * G], corr = new boolean[G * G], sea = new boolean[G * G];
        for (int j = 0; j < G; j++)
            for (int i = 0; i < G; i++) {
                int k = n.coverAt((CX0 + i) * Nation.CELL, (CY0 + j) * Nation.CELL);
                cov[j * G + i] = (byte) k;
                cwet[j * G + i] = Nation.wet(k);
            }
        // river-carved cells: the coarse layer marks whole cells wet along a river, wider than the river itself;
        // those become land again (the river is drawn at its own width next) unless open water lies beside them
        long[] rv = ix.rivers;
        for (int p = first(rv, sec); p < rv.length && (int) (rv[p] >>> 32) == sec; p++) {
            int r = (int) (rv[p] >>> 20) & 0x7FF, seg = (int) rv[p] & 0xFFFFF;
            float[] pts = n.rivers.get(r);
            float ax = pts[seg * 2], ay = pts[seg * 2 + 1], bx = pts[seg * 2 + 2], by = pts[seg * 2 + 3];
            float lim = ix.riverHalf[r] + 4 + 16;
            for (int j = 0; j < G; j++)
                for (int i = 0; i < G; i++) {
                    int q = j * G + i;
                    if (!cwet[q] || corr[q]) continue;
                    if (segDist2((CX0 + i) * 16 + 8, (CY0 + j) * 16 + 8, ax, ay, bx, by) < lim * lim) corr[q] = true;
                }
        }
        for (int q = 0; q < G * G; q++) sea[q] = cwet[q] && (cov[q] == Nation.C_DEEP || !corr[q]);
        float[] K = new float[G * G], D = new float[G * G];
        byte[] dry = new byte[G * G];
        for (int j = 0; j < G; j++)
            for (int i = 0; i < G; i++) {
                int q = j * G + i;
                boolean keep = sea[q];
                if (cwet[q] && !keep)
                    for (int dj = -2; dj <= 2 && !keep; dj++)
                        for (int di = -2; di <= 2 && !keep; di++) {
                            int ii = i + di, jj = j + dj;
                            if (ii >= 0 && jj >= 0 && ii < G && jj < G && sea[jj * G + ii]) keep = true;
                        }
                K[q] = keep ? 1 : 0;
                D[q] = keep && cov[q] == Nation.C_DEEP ? 1 : 0;
                byte d = cov[q];
                if (cwet[q]) {
                    d = Nation.C_GRASS;
                    for (int dj = -1; dj <= 1; dj++)
                        for (int di = -1; di <= 1; di++) {
                            int ii = i + di, jj = j + dj;
                            if (ii < 0 || jj < 0 || ii >= G || jj >= G || cwet[jj * G + ii]) continue;
                            int k2 = cov[jj * G + ii];
                            if (k2 != Nation.C_ROCK && d == Nation.C_GRASS) d = (byte) k2;
                        }
                }
                dry[q] = d;
            }
        // noise on a 4-tile lattice (global positions, so neighbouring chunks agree), bilinear between
        int L = S / 4 + 1;
        float[] jx = new float[L * L], jy = new float[L * L], wn = new float[L * L];
        for (int j = 0; j < L; j++)
            for (int i = 0; i < L; i++) {
                float x = X0 + i * 4, y = Y0 + j * 4;
                jx[j * L + i] = ix.jitX.value(x / 11f, y / 11f) * 7;
                jy[j * L + i] = ix.jitY.value(x / 11f, y / 11f) * 7;
                wn[j * L + i] = ix.wob.value(x / 9f, y / 9f) * 0.6f + ix.wob.value(x / 3.1f + 40, y / 3.1f) * 0.25f;
            }
        long fs = seed ^ 0xF1E1D5L;
        for (int y = 0; y < S; y++) {
            int ly = y >> 2;
            float fy = (y & 3) / 4f;
            float cyf = (y + 0.5f) / 16 + (GM - 0.5f);
            int cj = (int) cyf;
            float ay = cyf - cj;
            int ty = Y0 + y;
            for (int x = 0; x < S; x++) {
                int lx = x >> 2;
                float fx = (x & 3) / 4f;
                int a = ly * L + lx;
                float nw = lerp2(wn[a], wn[a + 1], wn[a + L], wn[a + L + 1], fx, fy);
                float cxf = (x + 0.5f) / 16 + (GM - 0.5f);
                int ci = (int) cxf;
                float ax = cxf - ci;
                int q = cj * G + ci;
                float kw = lerp2(K[q], K[q + 1], K[q + G], K[q + G + 1], ax, ay);
                int i = y * S + x, tx = X0 + x;
                if (kw > 0 && kw + nw * 0.35f > 0.5f) {
                    float kd = lerp2(D[q], D[q + 1], D[q + G], D[q + G + 1], ax, ay);
                    t[i] = kd + nw * 0.35f > 0.5f ? Tile.DEEP : Tile.WATER;
                    continue;
                }
                // land: the cover of the cell under a jittered point, so edges between kinds wander
                float jxv = lerp2(jx[a], jx[a + 1], jx[a + L], jx[a + L + 1], fx, fy);
                float jyv = lerp2(jy[a], jy[a + 1], jy[a + L], jy[a + L + 1], fx, fy);
                int si = clamp((int) StrictMath.floor((x + jxv) / 16) + GM, 0, G - 1), sj = clamp((int) StrictMath.floor((y + jyv) / 16) + GM, 0, G - 1);
                int k = dry[sj * G + si];
                t[i] = landTile(k, tx, ty, fs);
            }
        }
    }

    private static float lerp2(float a, float b, float c2, float d, float fx, float fy) {
        float top = a + (b - a) * fx, bot = c2 + (d - c2) * fx;
        return top + (bot - top) * fy;
    }

    private byte landTile(int k, int tx, int ty, long fs) {
        switch (k) {
            case Nation.C_SAND: return Tile.SAND;
            case Nation.C_FOREST: return Rng.unit(seed, tx, ty ^ 0x3F00000) < 0.04f ? Tile.GRASS : Tile.FOREST;
            case Nation.C_ROCK: return Tile.ROCK;
            case Nation.C_PARK: return treeAt(tx, ty, 0.22f) ? Tile.TREE : Tile.PARK;
            case Nation.C_FARM: {
                // fields about 88 x 56 m in rows, hedges or tracks between, now and then a meadow
                int row = ty / 14, off = Rng.hashInt(fs, row, 0x51, 22), col = (tx + off) / 22;
                boolean vEdge = (tx + off) % 22 == 0, hEdge = ty % 14 == 0;
                if (vEdge || hEdge) {
                    long h = Rng.hash(fs, col * 3 + (vEdge ? 1 : 2), row);
                    return (h & 3) == 0 ? Tile.GRASS : (h & 3) == 1 ? Tile.TREE : Tile.FARM;
                }
                return (Rng.hash(fs, col, row) & 7) == 0 ? Tile.GRASS : Tile.FARM;
            }
            case Nation.C_GRASS:
                return treeAt(tx, ty, 0.05f) ? Tile.TREE : Tile.GRASS;
            default: // built-up, base and airfield cover: plain ground under what's built on it
                return Tile.GRASS;
        }
    }

    /** Trees in clumps: likelier where the clump noise is high. */
    private boolean treeAt(int tx, int ty, float p) {
        float v = ix.trees.value(tx / 5f, ty / 5f);
        return Rng.unit(seed ^ 0x7EE, tx, ty) < p * (1 + v * 2.2f);
    }

    // ------------------------------------------------------------------ 2. rivers

    private void rivers() {
        long[] rv = ix.rivers;
        for (int p = first(rv, sec); p < rv.length && (int) (rv[p] >>> 32) == sec; p++) {
            int r = (int) (rv[p] >>> 20) & 0x7FF, seg = (int) rv[p] & 0xFFFFF;
            float[] pts = n.rivers.get(r);
            float ax = pts[seg * 2], ay = pts[seg * 2 + 1], bx = pts[seg * 2 + 2], by = pts[seg * 2 + 3];
            float h = ix.riverHalf[r];
            int x0 = clamp((int) StrictMath.floor(StrictMath.min(ax, bx) - h) - X0, 0, S - 1), x1 = clamp((int) StrictMath.ceil(StrictMath.max(ax, bx) + h) - X0, 0, S - 1);
            int y0 = clamp((int) StrictMath.floor(StrictMath.min(ay, by) - h) - Y0, 0, S - 1), y1 = clamp((int) StrictMath.ceil(StrictMath.max(ay, by) + h) - Y0, 0, S - 1);
            if (x0 > x1 || y0 > y1) continue;
            if (StrictMath.max(ax, bx) + h < X0 || StrictMath.min(ax, bx) - h > X0 + S || StrictMath.max(ay, by) + h < Y0 || StrictMath.min(ay, by) - h > Y0 + S) continue;
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++) {
                    int i = y * S + x;
                    if (t[i] == Tile.DEEP) continue;
                    if (segDist2(X0 + x + 0.5f, Y0 + y + 0.5f, ax, ay, bx, by) <= h * h) t[i] = Tile.WATER;
                }
        }
        for (int i = 0; i < AREA; i++) wet[i] = Tile.water(t[i]);
    }

    /** Squared distance from (x, y) to segment a-b. */
    private static float segDist2(float x, float y, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        float u = l2 <= 0 ? 0 : ((x - ax) * dx + (y - ay) * dy) / l2;
        u = u < 0 ? 0 : u > 1 ? 1 : u;
        float px = ax + dx * u - x, py = ay + dy * u - y;
        return px * px + py * py;
    }

    // ------------------------------------------------------------------ 3. roads and railways

    private void roadsAndRails() {
        RoadGraph g = n.roads;
        if (g.chunkEdgeStart != null && g.chunkW == n.sw) {
            for (int k = g.chunkEdgeStart[sec]; k < g.chunkEdgeStart[sec + 1]; k++) {
                int e = g.chunkEdge[k];
                boolean hw = g.ekind[e] == RoadGraph.HIGHWAY;
                draw(g.nx[g.ea[e]], g.ny[g.ea[e]], g.nx[g.eb[e]], g.ny[g.eb[e]], g.ehalf[e], hw ? 2 : 1);
                if (hw) draw(g.nx[g.ea[e]], g.ny[g.ea[e]], g.nx[g.eb[e]], g.ny[g.eb[e]], g.ehalf[e] + 16, 8); // its verge
            }
        }
        RoadGraph rl = n.rails;
        long[] ra = ix.rails;
        for (int p = first(ra, sec); p < ra.length && (int) (ra[p] >>> 32) == sec; p++) {
            int e = (int) ra[p];
            drawCapsule(rl.nx[rl.ea[e]], rl.ny[rl.ea[e]], rl.nx[rl.eb[e]], rl.ny[rl.eb[e]], 13, 4);
        }
        // what each tile becomes depends only on which roads cover it, never on their order
        for (int i = 0; i < AREA; i++) {
            int m = mark[i];
            if (m == 0) continue;
            if (m == 8) { // a highway's verge: grass banks, nothing built
                if (!wet[i]) t[i] = Tile.GRASS;
                continue;
            }
            if (wet[i]) t[i] = Tile.BRIDGE;
            else if ((m & 1) != 0) t[i] = Tile.ROAD;
            else if ((m & 2) != 0) t[i] = Tile.HIGHWAY;
            else t[i] = Tile.RAIL;
        }
    }

    /** Marks a road edge (px): lattice edges along the grid as square-ended bands, others as capsules. */
    private void draw(float ax, float ay, float bx, float by, float half, int bit) {
        if (ax == bx || ay == by) {
            int lh = (int) (half / 16);
            int x0 = (int) StrictMath.floor(StrictMath.min(ax, bx) / 16) - lh, x1 = (int) StrictMath.floor(StrictMath.max(ax, bx) / 16) + lh;
            int y0 = (int) StrictMath.floor(StrictMath.min(ay, by) / 16) - lh, y1 = (int) StrictMath.floor(StrictMath.max(ay, by) / 16) + lh;
            x0 = StrictMath.max(x0 - X0, 0);
            x1 = StrictMath.min(x1 - X0, S - 1);
            y0 = StrictMath.max(y0 - Y0, 0);
            y1 = StrictMath.min(y1 - Y0, S - 1);
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++) mark[y * S + x] |= bit;
            return;
        }
        drawCapsule(ax, ay, bx, by, StrictMath.max(half - 4, 24), bit);
    }

    private void drawCapsule(float ax, float ay, float bx, float by, float r, int bit) {
        int x0 = clamp((int) StrictMath.floor((StrictMath.min(ax, bx) - r) / 16) - X0, 0, S), x1 = clamp((int) StrictMath.floor((StrictMath.max(ax, bx) + r) / 16) - X0, -1, S - 1);
        int y0 = clamp((int) StrictMath.floor((StrictMath.min(ay, by) - r) / 16) - Y0, 0, S), y1 = clamp((int) StrictMath.floor((StrictMath.max(ay, by) + r) / 16) - Y0, -1, S - 1);
        float r2 = r * r;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++)
                if (segDist2((X0 + x) * 16 + 8, (Y0 + y) * 16 + 8, ax, ay, bx, by) <= r2) mark[y * S + x] |= bit;
    }

    // ------------------------------------------------------------------ 4. sites, facility blocks, national buildings

    private void sites() {
        long[] op = ix.ops;
        int[] so = n.siteOps;
        for (int p = first(op, sec); p < op.length && (int) (op[p] >>> 32) == sec; p++) {
            int o = (int) op[p] * Nation.OP_INTS;
            int mode = so[o + 1], ax = so[o + 2], ay = so[o + 3], bx = so[o + 4], by = so[o + 5];
            byte tk = (byte) so[o + 6], edge = (byte) so[o + 7];
            int x0 = StrictMath.max(ax - X0, 0), x1 = StrictMath.min(bx - X0, S - 1), y0 = StrictMath.max(ay - Y0, 0), y1 = StrictMath.min(by - Y0, S - 1);
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++) {
                    int i = y * S + x, gx = X0 + x, gy = Y0 + y;
                    boolean rim = gx == ax || gx == bx || gy == ay || gy == by;
                    switch (mode) {
                        case Nation.OP_FILL: t[i] = tk; break;
                        case Nation.OP_FRAME: t[i] = rim ? edge : tk; break;
                        case Nation.OP_OUTLINE: if (rim) t[i] = tk; break;
                        case Nation.OP_LAND: if (!Tile.water(t[i])) t[i] = tk; break;
                        case Nation.OP_WATER: if (Tile.water(t[i])) t[i] = tk; break;
                        default:
                    }
                }
        }
    }

    private void facilityGround() {
        long[] fa = ix.facs;
        for (int p = first(fa, sec); p < fa.length && (int) (fa[p] >>> 32) == sec; p++) {
            Region.Facility f = n.facilities.get((int) fa[p]);
            int x0 = f.bx0 - X0, y0 = f.by0 - Y0, x1 = f.bx1 - X0, y1 = f.by1 - Y0;
            int mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
            for (int y = StrictMath.max(0, y0); y <= StrictMath.min(S - 1, y1); y++)
                for (int x = StrictMath.max(0, x0); x <= StrictMath.min(S - 1, x1); x++) {
                    boolean ring = x == x0 || x == x1 || y == y0 || y == y1;
                    byte k;
                    if (ring) k = Tile.PAVEMENT;
                    else if (f.kind != Region.F_PARK) k = Tile.LOT;
                    else if (x == mx || y == my || x - x0 == y - y0 || x1 - x == y - y0) k = Tile.PLAZA;
                    else k = treeAt(X0 + x, Y0 + y, 0.2f) ? Tile.TREE : Tile.PARK;
                    ground(x, y, k);
                }
        }
    }

    private void nationalBuildings() {
        long[] nb = ix.nats;
        for (int p = first(nb, sec); p < nb.length && (int) (nb[p] >>> 32) == sec; p++) {
            int b = (int) nb[p];
            int x0 = StrictMath.max(n.bx[b] - X0, 0), y0 = StrictMath.max(n.by[b] - Y0, 0);
            int x1 = StrictMath.min(n.bx[b] + n.bw[b] - 1 - X0, S - 1), y1 = StrictMath.min(n.by[b] + n.bh[b] - 1 - Y0, S - 1);
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++) {
                    t[y * S + x] = Tile.BUILDING;
                    bld[y * S + x] = (short) (-b - 1);
                }
        }
    }

    // ------------------------------------------------------------------ 5. lattice blocks

    /** Ground nothing is built on yet (roads, railways, water, buildings, walls and runways are kept). */
    private static boolean hard(int k) {
        return Tile.roadish(k) || k == Tile.RAIL || k == Tile.BRIDGE || Tile.water(k) || k == Tile.BUILDING || k == Tile.FENCE
                || k == Tile.WALL || k == Tile.RUNWAY || k == Tile.BASE;
    }

    /** Tiles a building may not touch (it may front a road, never stand in one). */
    private static boolean keepClear(int k) {
        return k == Tile.RAIL || k == Tile.BRIDGE || Tile.water(k) || k == Tile.FENCE || k == Tile.WALL || k == Tile.RUNWAY;
    }

    private void ground(int x, int y, byte k) {
        int i = y * S + x;
        if (!hard(t[i]) && mark[i] == 0) t[i] = k;
    }

    private void fill(int x0, int y0, int x1, int y1, byte k) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) ground(x, y, k);
    }

    /** Builds on chunk tiles (x, y, w, h) if they and the tiles round them allow it; returns the local index or -1. */
    private int build(int x, int y, int w, int h, int kind, int floors, int roof) {
        // a lot clipped by a road, railway or water loses the rows and columns in the way (not its middle)
        boolean trimmed = false;
        while (w >= (trimmed ? 3 : 2) && h >= (trimmed ? 3 : 2)) {
            int bad = badEdge(x, y, w, h);
            if (bad < 0) return c.addBuilding(x, y, w, h, kind, floors, roof);
            trimmed = true;
            if (bad == 0) {
                y++;
                h--;
            } else if (bad == 1) h--;
            else if (bad == 2) {
                x++;
                w--;
            } else if (bad == 3) w--;
            else return -1;
        }
        return -1;
    }

    /** -1 if (x, y, w, h) can be built on, else the side whose row or column is in the way (0 top, 1 bottom, 2 left, 3 right, 4 none). */
    private int badEdge(int x, int y, int w, int h) {
        if (x < 0 || y < 0 || x + w > S || y + h > S) return 4;
        for (int yy = y - 1; yy <= y + h; yy++)
            for (int xx = x - 1; xx <= x + w; xx++) {
                if (xx < 0 || yy < 0 || xx >= S || yy >= S) continue;
                boolean inside = xx >= x && yy >= y && xx < x + w && yy < y + h;
                int k = t[yy * S + xx];
                if (keepClear(k) || inside && (hard(k) || mark[yy * S + xx] != 0)) {
                    if (yy <= y) return 0;
                    if (yy >= y + h - 1) return 1;
                    if (xx <= x) return 2;
                    if (xx >= x + w - 1) return 3;
                    return 4;
                }
            }
        return -1;
    }

    private void blocks() {
        if (!n.built(sec)) return;
        int[] xs = n.lineOffsets(sec, true), ys = n.lineOffsets(sec, false);
        long[] fa = ix.facs;
        int f0 = first(fa, sec);
        for (int j = 0; j + 1 < ys.length; j++)
            for (int i = 0; i + 1 < xs.length; i++) {
                int[] b = Nation.block(X0, Y0, xs[i], xs[i + 1], ys[j], ys[j + 1]);
                boolean reserved = false;
                for (int p = f0; p < fa.length && (int) (fa[p] >>> 32) == sec && !reserved; p++) {
                    Region.Facility f = n.facilities.get((int) fa[p]);
                    reserved = f.bx0 <= b[2] && f.bx1 >= b[0] && f.by0 <= b[3] && f.by1 >= b[1];
                }
                if (reserved) continue;
                fillBlock(b[0] - X0, b[1] - Y0, b[2] - X0, b[3] - Y0, new Rng(Rng.hash(seed ^ 0xB10CL, b[0], b[1])));
            }
    }

    private void fillBlock(int x0, int y0, int x1, int y1, Rng r) {
        if (x1 - x0 < 4 || y1 - y0 < 4) return;
        int d = c.district;
        float dn = (c.density & 15) / 15f;
        // the sidewalk round the block
        byte ring = d == Nation.D_RURAL ? Tile.GRASS : Tile.PAVEMENT;
        for (int x = x0; x <= x1; x++) {
            ground(x, y0, ring);
            ground(x, y1, ring);
        }
        for (int y = y0; y <= y1; y++) {
            ground(x0, y, ring);
            ground(x1, y, ring);
        }
        int ix0 = x0 + 1, iy0 = y0 + 1, ix1 = x1 - 1, iy1 = y1 - 1;
        switch (d) {
            case Nation.D_CORE: core(ix0, iy0, ix1, iy1, r, dn); break;
            case Nation.D_DENSE: dense(ix0, iy0, ix1, iy1, r, dn); break;
            case Nation.D_MIXED: mixed(ix0, iy0, ix1, iy1, r, dn); break;
            case Nation.D_SUBURB: suburb(ix0, iy0, ix1, iy1, r, dn); break;
            case Nation.D_INDUSTRIAL: industrial(ix0, iy0, ix1, iy1, r, dn); break;
            case Nation.D_PARK: park(ix0, iy0, ix1, iy1, r); break;
            case Nation.D_CAMPUS: campus(ix0, iy0, ix1, iy1, r, dn); break;
            default: rural(ix0, iy0, ix1, iy1, r, dn);
        }
    }

    private boolean city() {
        return kindOfSet == Region.S_CITY;
    }

    /** Downtown: towers and office blocks on paved plazas, taller with density, tallest in the capital. */
    private void core(int x0, int y0, int x1, int y1, Rng r, float dn) {
        fill(x0, y0, x1, y1, Tile.PLAZA);
        if (r.chance(0.04f)) { // a square with trees
            for (int y = y0 + 1; y < y1; y++) for (int x = x0 + 1; x < x1; x++) if (treeAt(X0 + x, Y0 + y, 0.12f)) ground(x, y, Tile.TREE);
            return;
        }
        int top = capital ? 30 : city() ? 18 : 6;
        float mode = r.nextFloat();
        if (mode < 0.12f + 0.12f * dn) { // one big building on the whole block, set back behind a plaza
            int in = r.chance(0.5f) ? 1 : 0;
            boolean tower = r.chance(0.5f + 0.4f * dn);
            int kind = tower ? Region.B_TOWER : Region.B_OFFICE;
            int floors = tower ? 15 + r.nextInt(1 + (int) (top * (0.5f + dn * 0.7f))) : r.range(6, 9 + (int) (dn * 8));
            build(x0 + in, y0 + in, x1 - x0 + 1 - 2 * in, y1 - y0 + 1 - 2 * in, kind, floors, roof(kind, r));
            return;
        }
        for (int[] lot : split(r, x0, y0, x1, y1, r.range(3, 5), r.range(1, 3))) {
            int lw = lot[2] - lot[0] + 1, lh = lot[3] - lot[1] + 1;
            if (lw < 3 || lh < 3 || r.chance(0.06f)) continue;
            boolean tower = lw * lh >= 25 && r.chance(0.3f + 0.45f * dn);
            int kind, floors;
            if (tower) {
                kind = Region.B_TOWER;
                floors = 12 + r.nextInt(1 + (int) (top * (0.4f + dn * 0.6f)));
                if (r.chance(0.12f * dn)) floors += r.range(10, top);
            } else if (lw * lh <= 16 && r.chance(0.4f)) {
                kind = Region.B_SHOP;
                floors = r.range(2, 5);
            } else {
                kind = Region.B_OFFICE;
                floors = r.range(5, 7 + (int) (dn * 9));
            }
            build(lot[0], lot[1], lw, lh, kind, floors, roof(kind, r));
        }
    }

    /** Dense residential: perimeter blocks of flats round a courtyard, shops and offices among them. */
    private void dense(int x0, int y0, int x1, int y1, Rng r, float dn) {
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        if (w < 9 || h < 9) {
            mixed(x0, y0, x1, y1, r, dn);
            return;
        }
        fill(x0, y0, x1, y1, Tile.YARD);
        int dep = r.range(3, 4);
        int lo = city() ? 4 : 2, hi = city() ? 5 + (int) (dn * 7) : 4;
        row(x0, y0, x1, y0 + dep - 1, true, r, lo, hi);
        row(x0, y1 - dep + 1, x1, y1, true, r, lo, hi);
        row(x0, y0 + dep, x0 + dep - 1, y1 - dep, false, r, lo, hi);
        row(x1 - dep + 1, y0 + dep, x1, y1 - dep, false, r, lo, hi);
        // the courtyard: gardens, and in a big block a building or two inside
        int ix0 = x0 + dep + 1, iy0 = y0 + dep + 1, ix1 = x1 - dep - 1, iy1 = y1 - dep - 1;
        if (ix1 - ix0 >= 8 && iy1 - iy0 >= 8) {
            for (int[] lot : split(r, ix0 + 1, iy0 + 1, ix1 - 1, iy1 - 1, 5, 2)) {
                int lw = lot[2] - lot[0] + 1, lh = lot[3] - lot[1] + 1;
                if (lw < 4 || lh < 4 || r.chance(0.45f)) continue;
                int kind = r.chance(0.12f) ? Region.B_SCHOOL : Region.B_APARTMENT;
                build(lot[0], lot[1], lw, lh, kind, kind == Region.B_SCHOOL ? 3 : r.range(lo, hi), roof(kind, r));
            }
        }
        for (int y = iy0; y <= iy1; y++)
            for (int x = ix0; x <= ix1; x++) if (t[y * S + x] == Tile.YARD && treeAt(X0 + x, Y0 + y, 0.08f)) t[y * S + x] = Tile.TREE;
    }

    /** A row of attached buildings filling (x0, y0)-(x1, y1) along its long side. */
    private void row(int x0, int y0, int x1, int y1, boolean horiz, Rng r, int lo, int hi) {
        int a = horiz ? x0 : y0, end = horiz ? x1 : y1;
        while (a <= end) {
            int len = r.range(4, 8);
            if (end - (a + len - 1) < 4) len = end - a + 1;
            int kind, floors;
            float k = r.nextFloat();
            if (k < 0.16f) {
                kind = Region.B_SHOP;
                floors = r.range(2, 3);
            } else if (k < 0.27f) {
                kind = Region.B_OFFICE;
                floors = r.range(lo, hi);
            } else {
                kind = Region.B_APARTMENT;
                floors = r.range(lo, hi);
            }
            if (horiz) build(a, y0, len, y1 - y0 + 1, kind, floors, roof(kind, r));
            else build(x0, a, x1 - x0 + 1, len, kind, floors, roof(kind, r));
            a += len;
        }
    }

    /** Mixed: flats, shops and the odd office, church or school on lots; a town's centre is lower and has more shops. */
    private void mixed(int x0, int y0, int x1, int y1, Rng r, float dn) {
        fill(x0, y0, x1, y1, Tile.PAVEMENT);
        boolean town = !city();
        int lo = town ? 2 : 3, hi = town ? 3 + (int) (dn * 2) : 4 + (int) (dn * 5);
        for (int[] lot : split(r, x0, y0, x1, y1, 3, 4)) {
            int lw = lot[2] - lot[0] + 1, lh = lot[3] - lot[1] + 1;
            if (lw < 3 || lh < 3) continue;
            if (r.chance(0.07f)) {
                for (int y = lot[1]; y <= lot[3]; y++) for (int x = lot[0]; x <= lot[2]; x++) ground(x, y, Tile.LOT);
                continue;
            }
            int kind, floors;
            float k = r.nextFloat();
            if (k < 0.03f && lw * lh > 30) { kind = Region.B_MALL; floors = 2; }
            else if (k < 0.07f) { kind = r.chance(0.5f) ? Region.B_CHURCH : Region.B_SCHOOL; floors = 2; }
            else if (k < (town ? 0.5f : 0.33f)) { kind = Region.B_SHOP; floors = r.range(1, town ? 2 : 3); }
            else if (k < (town ? 0.58f : 0.43f)) { kind = Region.B_OFFICE; floors = r.range(lo, hi); }
            else { kind = Region.B_APARTMENT; floors = r.range(lo, hi); }
            build(lot[0], lot[1], lw, lh, kind, floors, roof(kind, r));
        }
    }

    /** Suburbs: detached houses in rows along the long sides of the block, gardens behind, now and then a school or church. */
    private void suburb(int x0, int y0, int x1, int y1, Rng r, float dn) {
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        fill(x0, y0, x1, y1, Tile.YARD);
        float k = r.nextFloat();
        boolean village = kindOfSet == Region.S_VILLAGE;
        if (k < 0.05f) { // a playing field or small park
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) ground(x, y, treeAt(X0 + x, Y0 + y, 0.1f) ? Tile.TREE : Tile.PARK);
            return;
        }
        if (k < (village ? 0.12f : 0.08f) && w >= 8 && h >= 8) { // a school, church or corner shops
            int kind = village ? (r.chance(0.6f) ? Region.B_CHURCH : Region.B_SHOP) : (r.chance(0.5f) ? Region.B_SCHOOL : Region.B_CHURCH);
            int bw = StrictMath.min(w - 2, kind == Region.B_SCHOOL ? 12 : 7), bh = StrictMath.min(h - 2, kind == Region.B_SCHOOL ? 7 : 5);
            fill(x0, y0, x1, y1, Tile.LOT);
            build(x0 + 1, y0 + 1, bw, bh, kind, kind == Region.B_SHOP ? 1 : 2, roof(kind, r));
            if (kind == Region.B_SCHOOL) for (int y = y0 + bh + 2; y <= y1; y++) for (int x = x0; x <= x1; x++) ground(x, y, Tile.PARK);
            return;
        }
        boolean wide = w >= h;
        int len = wide ? w : h, dep = wide ? h : w;
        if (dep >= 19) { // a deep superblock: a lane down the middle with houses both sides of it
            int mid = (wide ? y0 + y1 : x0 + x1) / 2;
            if (wide) {
                for (int x = x0; x <= x1; x++) ground(x, mid, Tile.PAVEMENT);
                houses(x0, y0, x1, mid - 1, r, dn);
                houses(x0, mid + 1, x1, y1, r, dn);
            } else {
                for (int y = y0; y <= y1; y++) ground(mid, y, Tile.PAVEMENT);
                houses(x0, y0, mid - 1, y1, r, dn);
                houses(mid + 1, y0, x1, y1, r, dn);
            }
        } else houses(x0, y0, x1, y1, r, dn);
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) if (t[y * S + x] == Tile.YARD && treeAt(X0 + x, Y0 + y, 0.05f)) t[y * S + x] = Tile.TREE;
    }

    /** Detached houses in one or two rows along the long sides of (x0, y0)-(x1, y1), drives to the front. */
    private void houses(int x0, int y0, int x1, int y1, Rng r, float dn) {
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        boolean wide = w >= h;
        int len = wide ? w : h, dep = wide ? h : w;
        int lotW = dn > 0.6f ? r.range(4, 5) : r.range(5, 6);
        int rows = dep >= 9 ? 2 : 1;
        for (int rw = 0; rw < rows; rw++) {
            for (int p = 0; p + 4 <= len; p += lotW) {
                int hw = StrictMath.min(lotW - 1, r.range(3, 4)), hd = StrictMath.min(r.range(3, 4), dep / rows - 1);
                if (hd < 3) continue;
                int along = p + (lotW - 1 - hw) / 2;
                int across = rw == 0 ? 1 : dep - 1 - hd;
                int x, y, bw, bh;
                if (wide) { x = x0 + along; y = y0 + across; bw = hw; bh = hd; }
                else { x = x0 + across; y = y0 + along; bw = hd; bh = hw; }
                if (x + bw - 1 > x1 || y + bh - 1 > y1) continue;
                int b = build(x, y, bw, bh, Region.B_HOUSE, r.chance(0.35f + dn * 0.3f) ? 2 : 1, roof(Region.B_HOUSE, r));
                if (b < 0) continue;
                // a drive from the house to the street
                if (wide && x + bw <= x1) for (int yy = rw == 0 ? y0 : y; yy <= (rw == 0 ? y + bh - 1 : y1); yy++) driveway(x + bw, yy);
                else if (!wide && y + bh <= y1) for (int xx = rw == 0 ? x0 : x; xx <= (rw == 0 ? x + bw - 1 : x1); xx++) driveway(xx, y + bh);
            }
        }
    }

    private void driveway(int x, int y) {
        if (x < 0 || y < 0 || x >= S || y >= S) return;
        if (t[y * S + x] == Tile.YARD) t[y * S + x] = Tile.LOT;
    }

    /** Industry: warehouses and sheds with loading yards on big lots, a few offices. */
    private void industrial(int x0, int y0, int x1, int y1, Rng r, float dn) {
        fill(x0, y0, x1, y1, Tile.LOT);
        for (int[] lot : split(r, x0, y0, x1, y1, 6, 3)) {
            int lw = lot[2] - lot[0] + 1, lh = lot[3] - lot[1] + 1;
            if (lw < 4 || lh < 4) continue;
            float k = r.nextFloat();
            if (lw * lh > 160) k = 0.3f + k * 0.7f; // big lots get sheds
            if (k < 0.12f) continue; // a yard
            if (k < 0.24f) {
                int bw = StrictMath.min(lw, r.range(4, 7)), bh = StrictMath.min(lh, r.range(4, 6));
                build(lot[0], lot[1], bw, bh, Region.B_OFFICE, r.range(2, 3), roof(Region.B_OFFICE, r));
                continue;
            }
            // the shed leaves an apron on one long side for lorries
            int bw = lw, bh = lh;
            if (lw >= lh) bh = StrictMath.max(3, lh - r.range(2, 3));
            else bw = StrictMath.max(3, lw - r.range(2, 3));
            build(lot[0], lot[1], bw, bh, Region.B_WAREHOUSE, r.chance(0.2f) ? 3 : r.range(1, 2), roof(Region.B_WAREHOUSE, r));
        }
    }

    /** Parks: lawns, clumps of trees, paths, sometimes a pond. */
    private void park(int x0, int y0, int x1, int y1, Rng r) {
        int mx = (x0 + x1) / 2 + r.range(-3, 3), my = (y0 + y1) / 2 + r.range(-3, 3);
        boolean pond = (x1 - x0) >= 20 && (y1 - y0) >= 20 && r.chance(0.5f);
        float px = mx + r.range(-6f, 6f), py = my + r.range(-6f, 6f), pr = r.range(4f, 7f);
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                byte k;
                if (x == mx || y == my) k = Tile.PLAZA;
                else if (pond && (x - px) * (x - px) + (y - py) * (y - py) * 1.4f < pr * pr) k = Tile.WATER;
                else k = treeAt(X0 + x, Y0 + y, 0.13f) ? Tile.TREE : Tile.PARK;
                ground(x, y, k);
            }
    }

    /** A campus: large teaching and office buildings on lawns, car parks. */
    private void campus(int x0, int y0, int x1, int y1, Rng r, float dn) {
        fill(x0, y0, x1, y1, Tile.PARK);
        for (int[] lot : split(r, x0, y0, x1, y1, 8, 3)) {
            int lw = lot[2] - lot[0] + 1, lh = lot[3] - lot[1] + 1;
            if (lw < 6 || lh < 6) continue;
            float k = r.nextFloat();
            if (k < 0.2f) {
                for (int y = lot[1] + 1; y < lot[3]; y++) for (int x = lot[0] + 1; x < lot[2]; x++) ground(x, y, Tile.LOT);
                continue;
            }
            if (k < 0.32f) continue;
            int kind = r.chance(0.55f) ? Region.B_SCHOOL : Region.B_OFFICE;
            build(lot[0] + 1, lot[1] + 1, lw - 2, lh - 2, kind, r.range(3, 4 + (int) (dn * 4)), roof(kind, r));
        }
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) if (t[y * S + x] == Tile.PARK && treeAt(X0 + x, Y0 + y, 0.08f)) t[y * S + x] = Tile.TREE;
    }

    /** The fringe: houses on big plots, smallholdings and fields. */
    private void rural(int x0, int y0, int x1, int y1, Rng r, float dn) {
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        if (r.chance(0.15f)) {
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) ground(x, y, Tile.FARM);
            return;
        }
        fill(x0, y0, x1, y1, Tile.GRASS);
        boolean wide = w >= h;
        int len = wide ? w : h, dep = wide ? h : w;
        int rows = dep >= 12 ? 2 : 1;
        for (int rw = 0; rw < rows; rw++)
            for (int p = r.range(0, 3); p + 5 <= len; p += r.range(6, 10)) {
                if (r.chance(0.25f)) continue;
                int hw = r.range(3, 4), hd = r.range(3, 4);
                int across = rw == 0 ? 1 : dep - 1 - hd;
                int x = wide ? x0 + p : x0 + across, y = wide ? y0 + across : y0 + p;
                int bw = wide ? hw : hd, bh = wide ? hd : hw;
                if (x + bw - 1 > x1 || y + bh - 1 > y1) continue;
                for (int yy = y - 1; yy <= y + bh; yy++)
                    for (int xx = x - 1; xx <= x + bw; xx++)
                        if (xx >= x0 && yy >= y0 && xx <= x1 && yy <= y1) ground(xx, yy, Tile.YARD);
                boolean farm = r.chance(0.15f);
                build(x, y, bw, bh, farm ? Region.B_FARMHOUSE : Region.B_HOUSE, r.chance(0.4f) ? 2 : 1, roof(farm ? Region.B_FARMHOUSE : Region.B_HOUSE, r));
            }
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) if (t[y * S + x] == Tile.GRASS && treeAt(X0 + x, Y0 + y, 0.06f)) t[y * S + x] = Tile.TREE;
    }

    /** Splits a rectangle into lots with a one-tile gap between them (RegionGen's split, on the block's own stream). */
    private static java.util.List<int[]> split(Rng r, int x0, int y0, int x1, int y1, int minSide, int depth) {
        java.util.List<int[]> out = new java.util.ArrayList<int[]>();
        splitInto(r, out, x0, y0, x1, y1, minSide, depth);
        return out;
    }

    private static void splitInto(Rng r, java.util.List<int[]> out, int x0, int y0, int x1, int y1, int minSide, int depth) {
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        if (depth <= 0 || (w < minSide * 2 + 1 && h < minSide * 2 + 1) || (depth < 3 && r.chance(0.15f))) {
            out.add(new int[]{x0, y0, x1, y1});
            return;
        }
        if (w >= h && w >= minSide * 2 + 1) {
            int cut = x0 + r.range(minSide, w - minSide - 1);
            splitInto(r, out, x0, y0, cut - 1, y1, minSide, depth - 1);
            splitInto(r, out, cut + 1, y0, x1, y1, minSide, depth - 1);
        } else if (h >= minSide * 2 + 1) {
            int cut = y0 + r.range(minSide, h - minSide - 1);
            splitInto(r, out, x0, y0, x1, cut - 1, minSide, depth - 1);
            splitInto(r, out, x0, cut + 1, x1, y1, minSide, depth - 1);
        } else out.add(new int[]{x0, y0, x1, y1});
    }

    static int roof(int kind, Rng r) {
        int[] pal;
        switch (kind) {
            case Region.B_HOUSE: pal = new int[]{0xFF9A5B44, 0xFF7E5A4A, 0xFF6B6F78, 0xFF8C4A3C, 0xFF5E5A56, 0xFFA0785A}; break;
            case Region.B_APARTMENT: pal = new int[]{0xFF8A8580, 0xFF7C7670, 0xFF9C948A, 0xFF6E6A66, 0xFFA08A78}; break;
            case Region.B_OFFICE: pal = new int[]{0xFF7A8088, 0xFF8C9298, 0xFF6C737C, 0xFF9AA0A6}; break;
            case Region.B_TOWER: pal = new int[]{0xFF5E6A78, 0xFF6E7A88, 0xFF4E5866, 0xFF80909E}; break;
            case Region.B_SHOP: pal = new int[]{0xFFA89A84, 0xFF9C8C74, 0xFFB4A48A}; break;
            case Region.B_WAREHOUSE: pal = new int[]{0xFF8E9296, 0xFF7E8488, 0xFF9AA0A0, 0xFF8A8270}; break;
            case Region.B_FARMHOUSE: pal = new int[]{0xFF8C4A3C, 0xFF6E5040}; break;
            case Region.B_BARN: return 0xFF9A3A2E;
            case Region.B_CHURCH: return 0xFF7A6A5A;
            case Region.B_SCHOOL: return 0xFFA07A5A;
            case Region.B_MALL: return 0xFFA8A8A0;
            default: return 0xFF888888;
        }
        return pal[r.nextInt(pal.length)];
    }

    // ------------------------------------------------------------------ 6. farmsteads

    private boolean natural(int k) {
        return k == Tile.GRASS || k == Tile.FARM || k == Tile.FOREST || k == Tile.TREE || k == Tile.SAND;
    }

    private void farms() {
        int set = n.secSettlement[sec];
        if (set >= 0 && n.district[sec] == Nation.D_NONE) return; // a site
        if (n.built(sec)) return;
        for (int j = 0; j < 4; j++)
            for (int i = 0; i < 4; i++) {
                int gx = cx * 4 + i, gy = cy * 4 + j;
                int k = n.cover[gy * n.cw + gx];
                float p = k == Nation.C_FARM ? 0.09f : k == Nation.C_GRASS ? 0.03f : 0;
                if (p == 0 || Rng.unit(seed ^ 0xFA4L, gx, gy) >= p) continue;
                Rng r = new Rng(Rng.hash(seed ^ 0xFA5L, gx, gy));
                farmstead(i * 16 + r.range(1, 3), j * 16 + r.range(1, 5), r);
            }
    }

    /** A farmhouse and barn round a yard at chunk tile (x, y) (13 x 9 tiles), if the ground is open. */
    private boolean farmstead(int x, int y, Rng r) {
        if (x < 1 || y < 1 || x + 13 > S - 1 || y + 9 > S - 1) return false;
        for (int yy = y; yy < y + 9; yy++) for (int xx = x; xx < x + 13; xx++) if (!natural(t[yy * S + xx])) return false;
        for (int yy = y; yy < y + 9; yy++) for (int xx = x; xx < x + 13; xx++) t[yy * S + xx] = Tile.YARD;
        build(x + 1, y + 2, 4, 4, Region.B_FARMHOUSE, r.chance(0.5f) ? 2 : 1, roof(Region.B_FARMHOUSE, r));
        build(x + 7, y + 1, 5, 6, Region.B_BARN, 2, roof(Region.B_BARN, r));
        return true;
    }

    // ------------------------------------------------------------------ 9. residents

    /** Residential weight per tile-floor, in thousandths, by building kind. */
    private static int weight(int kind) {
        switch (kind) {
            case Region.B_HOUSE:
            case Region.B_APARTMENT:
            case Region.B_FARMHOUSE: return 1000;
            case Region.B_TOWER: return 400;
            case Region.B_SHOP: return 250;
            case Region.B_OFFICE: return 40;
            default: return 0;
        }
    }

    private void residents() {
        int R = n.residents[sec];
        int set = n.secSettlement[sec];
        if (set >= 0 && n.district[sec] == Nation.D_NONE) { // a site: its people are in its national buildings
            c.elsewhere = R;
            return;
        }
        if (R <= 0) return;
        long[] w = new long[c.bCount];
        long sum = 0;
        for (int pass = 0; pass < 3 && sum == 0; pass++) {
            if (pass == 2) {
                // nowhere to live yet: a cottage on open ground
                if (!cottage()) {
                    c.elsewhere = R;
                    return;
                }
                w = new long[c.bCount];
            }
            for (int i = 0; i < c.bCount; i++) {
                long area = (long) c.bw[i] * c.bh[i] * c.bfloors[i];
                w[i] = area * (pass == 0 ? weight(c.bkind[i]) : 1);
                sum += w[i];
            }
        }
        // largest remainders, ties to the earlier building: exactly R in all
        int m = c.bCount;
        long given = 0;
        long[] rem = new long[m];
        for (int i = 0; i < m; i++) {
            long v = R * w[i];
            c.bres[i] = (int) (v / sum);
            given += c.bres[i];
            rem[i] = (v % sum) * 1024 + (1023 - StrictMath.min(1023, i)); // remainder, then the earlier first
        }
        int left = (int) (R - given);
        if (left > 0) {
            Integer[] order = new Integer[m];
            for (int i = 0; i < m; i++) order[i] = i;
            final long[] rr = rem;
            Arrays.sort(order, new java.util.Comparator<Integer>() {
                public int compare(Integer a, Integer b) {
                    if (rr[a] != rr[b]) return rr[a] > rr[b] ? -1 : 1;
                    return a - b;
                }
            });
            for (int k = 0; k < left; k++) c.bres[order[k % m]]++;
        }
    }

    /** A small house on the first open spot found (for a sector with people and nowhere for them). */
    private boolean cottage() {
        int start = Rng.hashInt(seed, sec, 0xC077, 64);
        for (int step : new int[]{8, 2})
            for (int k = 0; k < S * S / (step * step); k++) {
                int q = (k + start) % (S * S / (step * step));
                int x = (q % (S / step)) * step, y = (q / (S / step)) * step;
                if (x < 1 || y < 1 || x + 4 > S - 1 || y + 4 > S - 1) continue;
                boolean ok = true;
                for (int yy = y; yy < y + 4 && ok; yy++) for (int xx = x; xx < x + 4; xx++) if (hard(t[yy * S + xx]) || mark[yy * S + xx] != 0) ok = false;
                if (!ok) continue;
                if (build(x + 1, y + 1, 3, 3, Region.B_HOUSE, 1, 0xFF8C4A3C) >= 0) return true;
            }
        return false;
    }

    // ------------------------------------------------------------------ 7. doors, 8. nav

    private void doors() {
        for (int b = 0; b < c.bCount; b++) {
            int x0 = c.bx[b], y0 = c.by[b], x1 = x0 + c.bw[b] - 1, y1 = y0 + c.bh[b] - 1;
            int mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
            int[][] cand = {{mx, y1 + 1}, {mx, y0 - 1}, {x0 - 1, my}, {x1 + 1, my}};
            float best = -1;
            for (int[] p : cand) {
                if (p[0] < 0 || p[1] < 0 || p[0] >= S || p[1] >= S) continue;
                int k = t[p[1] * S + p[0]];
                if (!Tile.walkable(k)) continue;
                float score = (k == Tile.PAVEMENT || k == Tile.LOT || k == Tile.PLAZA) ? 2 : 1;
                if (score > best) {
                    best = score;
                    c.doorX[b] = (byte) p[0];
                    c.doorY[b] = (byte) p[1];
                }
            }
            if (c.doorX[b] < 0)
                for (int y = y0 - 1; y <= y1 + 1 && c.doorX[b] < 0; y++)
                    for (int x = x0 - 1; x <= x1 + 1; x++)
                        if (x >= 0 && y >= 0 && x < S && y < S && Tile.walkable(t[y * S + x])) {
                            c.doorX[b] = (byte) x;
                            c.doorY[b] = (byte) y;
                            break;
                        }
        }
    }

    private void nav() {
        int N = Chunk.NAVS, R = Region.NAV;
        for (int y = 0; y < N; y++)
            for (int x = 0; x < N; x++) {
                int open = 0, road = 0;
                for (int dy = 0; dy < R; dy++)
                    for (int dx = 0; dx < R; dx++) {
                        int k = t[(y * R + dy) * S + x * R + dx];
                        if (Tile.walkable(k)) open++;
                        if (Tile.roadish(k)) road++;
                    }
                c.nav[y * N + x] = (byte) (open < 2 ? 0 : road >= 2 ? 2 : 1);
            }
    }
}
