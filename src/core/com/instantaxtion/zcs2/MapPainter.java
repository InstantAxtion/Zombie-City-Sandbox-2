package com.instantaxtion.zcs2;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The ground, drawn once into pictures and reused: a picture of the whole region (one pixel per tile, or per two
 * tiles on the biggest maps, to stay inside a GPU texture) for the far zooms, and 32x32-tile chunks at four levels
 * of detail for the near ones: plain asphalt with kerbs, lane markings and crossings, paving, fields, canopies of
 * trees, water shading from shallow to deep with a beach at the shore, the edges of natural ground smooth (see
 * paintGround). Chunks are painted within a time budget each frame and kept in a cache with a memory budget; where
 * one isn't ready, a coarser one (or the region picture) stands in.
 */
public final class MapPainter {
    public static final int CHUNK = 32;
    /** Pixels per tile at each level of detail. */
    public static final int[] PPT = {4, 9, 16, 28};
    /** The zoom from which each level is used. */
    public static final float[] FROM = {0, 0.28f, 0.62f, 1.5f};
    public static final int LOW = 4, HIGH = 16; // kept for older callers
    private static final long BUDGET_BYTES = 80L << 20;
    private final Region r;
    private final Platform pf;
    public GImage regionImage;
    private int[] regionPixels;
    private final Map<Integer, GImage> cache;
    private long cacheBytes;
    private int seenChanges;
    public int painted;
    public boolean realistic = true;

    /** The ground's colours: the map's, muted (about 30% less saturated), asphalt flat. */
    public static final int[] GROUND = new int[Tile.KINDS];

    static {
        for (int i = 0; i < Tile.KINDS; i++) GROUND[i] = Tile.MAP_COLOR[i];
        GROUND[Tile.GRASS] = 0xFF71845E;
        GROUND[Tile.FARM] = 0xFF9C9568;
        GROUND[Tile.FOREST] = 0xFF465D40;
        GROUND[Tile.WATER] = 0xFF3D6483;
        GROUND[Tile.DEEP] = 0xFF2C4D69;
        GROUND[Tile.SAND] = 0xFFCBBF98;
        GROUND[Tile.ROAD] = 0xFF4A4C50;
        GROUND[Tile.HIGHWAY] = 0xFF404246;
        GROUND[Tile.PAVEMENT] = 0xFFA19F99;
        GROUND[Tile.PARK] = 0xFF67845A;
        GROUND[Tile.BRIDGE] = 0xFF5E5C5A;
        GROUND[Tile.BASE] = 0xFF8C8872;
        GROUND[Tile.LOT] = 0xFF5C5E62;
        GROUND[Tile.YARD] = 0xFF778A5E;
        GROUND[Tile.TREE] = 0xFF4A6441;
        GROUND[Tile.PLAZA] = 0xFFB2AB9E;
        GROUND[Tile.ROCK] = 0xFF8A867F;
        GROUND[Tile.RUNWAY] = 0xFF4B4C4F;
        GROUND[Tile.RAIL] = 0xFF625A52;
        GROUND[Tile.RUBBLE] = 0xFF67605A;
        GROUND[Tile.CAMP] = 0xFFA39878;
        GROUND[Tile.BUILDING] = 0xFF57524C;
    }

    public MapPainter(Region r, Platform pf, int maxChunks) {
        this.r = r;
        this.pf = pf;
        cache = new LinkedHashMap<Integer, GImage>(128, 0.75f, true);
        buildRegionImage();
        seenChanges = r.changes;
    }

    private static boolean natural(int t) {
        return t == Tile.GRASS || t == Tile.FOREST || t == Tile.FARM || t == Tile.PARK || t == Tile.YARD || t == Tile.TREE || t == Tile.SAND;
    }

    public static int tileColor(Region r, int x, int y, boolean texture) {
        int t = r.t(x, y);
        int c = GROUND[t];
        if (t == Tile.BUILDING) {
            int b = r.building(y * r.W + x);
            if (b >= 0) {
                c = r.broof[b];
                if (x == r.bx[b] || y == r.by[b] || x == r.bx[b] + r.bw[b] - 1 || y == r.by[b] + r.bh[b] - 1) c = shade(c, 0.8f);
            }
        }
        if (texture && natural(t)) {
            // a gentle mottling on grass and fields; the streets stay plain
            float n = (Rng.unit(r.seed, x >> 1, y >> 1) - 0.5f) * 0.07f + (Rng.unit(r.seed, x, y) - 0.5f) * 0.04f;
            c = shade(c, 1 + n);
        }
        return c;
    }

    public static int shade(int c, float k) {
        int a = c >>> 24, rr = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        rr = Math.min(255, Math.max(0, (int) (rr * k)));
        g = Math.min(255, Math.max(0, (int) (g * k)));
        b = Math.min(255, Math.max(0, (int) (b * k)));
        return (a << 24) | (rr << 16) | (g << 8) | b;
    }

    public static int mix(int c1, int c2, float t) {
        int r1 = (c1 >> 16) & 255, g1 = (c1 >> 8) & 255, b1 = c1 & 255;
        int r2 = (c2 >> 16) & 255, g2 = (c2 >> 8) & 255, b2 = c2 & 255;
        return 0xFF000000 | ((int) (r1 + (r2 - r1) * t) << 16) | ((int) (g1 + (g2 - g1) * t) << 8) | (int) (b1 + (b2 - b1) * t);
    }

    /** The colour with its alpha replaced. */
    public static int alpha(int c, float a) {
        return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (c & 0xFFFFFF);
    }

    /** Tiles per pixel of the region picture (2 for very large maps, to keep it within a texture). */
    public final int step() {
        return r.W > 2048 ? 2 : 1;
    }

    private void buildRegionImage() {
        int st = step(), iw = r.W / st, ih = r.H / st;
        regionPixels = new int[iw * ih];
        for (int y = 0; y < ih; y++) for (int x = 0; x < iw; x++) regionPixels[y * iw + x] = tileColor(r, x * st, y * st, true);
        regionImage = pf.newImage(iw, ih);
        regionImage.setPixels(regionPixels, iw, ih);
    }

    /** Redraws what changed on the map since last time (rubble, blown bridges): only the changed rectangles. */
    public void refresh() {
        if (r.changes == seenChanges) return;
        seenChanges = r.changes;
        int st = step(), iw = r.W / st, ih = r.H / st;
        for (int[] d : r.dirty) {
            int x0 = Math.max(0, d[0]) / st, y0 = Math.max(0, d[1]) / st;
            int x1 = Math.min(r.W - 1, d[2]) / st, y1 = Math.min(r.H - 1, d[3]) / st;
            if (x1 < x0 || y1 < y0) continue;
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++) regionPixels[y * iw + x] = tileColor(r, x * st, y * st, true);
            regionImage.updatePixels(regionPixels, y0 * iw + x0, iw, x0, y0, Math.min(iw, x1 + 1) - x0, Math.min(ih, y1 + 1) - y0);
            // drop the chunks it touched (and those near enough for their shores and edges to change)
            for (int cy = Math.max(0, d[1] - M) / CHUNK; cy <= Math.min(r.H - 1, Math.max(0, d[3] + M)) / CHUNK; cy++)
                for (int cx = Math.max(0, d[0] - M) / CHUNK; cx <= Math.min(r.W - 1, Math.max(0, d[2] + M)) / CHUNK; cx++) {
                    for (int lvl = 0; lvl < PPT.length; lvl++) {
                        GImage img = cache.remove(key(cx, cy, lvl));
                        if (img != null) {
                            cacheBytes -= bytes(lvl);
                            img.recycle();
                        }
                    }
                }
        }
        r.dirty.clear();
    }

    private static int key(int cx, int cy, int lvl) {
        return (lvl << 28) | (cy << 14) | cx;
    }

    private static long bytes(int lvl) {
        long s = (long) CHUNK * PPT[lvl];
        return s * s * 4;
    }

    /** The level of detail for a zoom. */
    public static int levelFor(float zoom) {
        int l = 0;
        for (int i = 1; i < FROM.length; i++) if (zoom >= FROM[i]) l = i;
        return l;
    }

    public boolean has(int cx, int cy, int lvl) {
        return cache.containsKey(key(cx, cy, lvl));
    }

    /** A chunk's picture, painting it now if allowed (else null). */
    public GImage chunk(int cx, int cy, int lvl, boolean mayPaint) {
        int k = key(cx, cy, lvl);
        GImage img = cache.get(k);
        if (img != null || !mayPaint) return img;
        int ppt = PPT[lvl];
        img = pf.newImage(CHUNK * ppt, CHUNK * ppt);
        int side = CHUNK * ppt;
        if (pix == null || pix.length < side * side) pix = new int[side * side];
        paintGround(pix, cx * CHUNK, cy * CHUNK, ppt, lvl);
        img.setPixels(pix, side, side);
        paintChunk(img.gfx(), cx * CHUNK, cy * CHUNK, ppt, lvl);
        cache.put(k, img);
        cacheBytes += bytes(lvl);
        painted++;
        while (cacheBytes > BUDGET_BYTES && cache.size() > 1) {
            Integer oldest = cache.keySet().iterator().next();
            GImage o = cache.remove(oldest);
            cacheBytes -= bytes(oldest >>> 28);
            if (o != null) o.recycle();
        }
        return img;
    }

    // ------------------------------------------------------------------ the ground, pixel by pixel

    /*
     * Natural ground (water, sand, grass, forest, fields, rock) doesn't keep to the tile grid. Each kind's share of
     * the tiles round about is blurred a little (a binomial kernel two tiles each way, with some of the sharp share
     * kept so narrow strips survive), and each pixel takes whichever kind leads there, interpolated smoothly (a cubic
     * B-spline) between tile centres, the edges wandering slowly with a little noise. Staircases become curves, the
     * edges are anti-aliased, the shore gets a beach and the water a gradient from shallow to deep by its distance
     * from land.
     * Man-made ground (streets, buildings, paving, yards) keeps its square edges.
     */
    private static final int NCLS = 6, C_WATER = 0, C_SAND = 1, C_GRASS = 2, C_FOREST = 3, C_FARM = 4, C_ROCK = 5;
    private static final byte[] SOFT = new byte[Tile.KINDS];
    private static final int[] CLS_COLOR = new int[NCLS];
    private static final int SHALLOW = 0xFF5A8AA0, MIDWATER = 0xFF3D6685, DEEPWATER = 0xFF29496A, FOAM = 0xFFB4CCD2;
    private static final int WET_SAND = 0xFFA49A78, BEACH = 0xFFD0C49C;

    static {
        for (int i = 0; i < Tile.KINDS; i++) SOFT[i] = -1;
        SOFT[Tile.WATER] = SOFT[Tile.DEEP] = C_WATER;
        SOFT[Tile.SAND] = C_SAND;
        SOFT[Tile.GRASS] = C_GRASS;
        SOFT[Tile.FOREST] = C_FOREST;
        SOFT[Tile.FARM] = C_FARM;
        SOFT[Tile.ROCK] = C_ROCK;
        CLS_COLOR[C_WATER] = MIDWATER;
        CLS_COLOR[C_SAND] = GROUND[Tile.SAND];
        CLS_COLOR[C_GRASS] = GROUND[Tile.GRASS];
        CLS_COLOR[C_FOREST] = GROUND[Tile.FOREST];
        CLS_COLOR[C_FARM] = GROUND[Tile.FARM];
        CLS_COLOR[C_ROCK] = GROUND[Tile.ROCK];
    }

    /** The margin of tiles round a chunk that its ground looks at (for the distance from the shore). */
    private static final int M = 5, WIN = CHUNK + 2 * M;
    private final byte[] wc = new byte[WIN * WIN];
    private final float[] wd = new float[WIN * WIN], wm = new float[WIN * WIN], wj = new float[WIN * WIN];
    private final float[] sc = new float[NCLS], tmp = new float[WIN * WIN];
    /** Each kind's (blurred) share at each tile of the window. */
    private final float[][] share = new float[NCLS][WIN * WIN];
    /** Which way the noise moves each kind's edges. */
    private static final float[] WANDER = {1, -1, -1, 1, 1, -1};
    private int[] pix;
    // per pixel across a tile: the cubic weights of the four nearest centres (from kb), the bilinear neighbour
    // (-1 or 1) and its weight, and how much of it the furrows cover
    private int tabP = -1;
    private float[] cw, lt, fur;
    private int[] lo, kb;

    private void tables(int p) {
        if (tabP == p) return;
        tabP = p;
        cw = new float[p * 4];
        kb = new int[p];
        lt = new float[p];
        fur = new float[p];
        lo = new int[p];
        float lw = Math.max(1, p / 12f);
        for (int i = 0; i < p; i++) {
            float u = (i + 0.5f) / p, f = u - 0.5f;
            // the four nearest tile centres' cubic B-spline weights (for the edges: smooth to the second derivative)
            kb[i] = f < 0 ? -2 : -1;
            for (int k = 0; k < 4; k++) {
                float d = Math.abs(f - (kb[i] + k));
                cw[i * 4 + k] = d < 1 ? (4 - 6 * d * d + 3 * d * d * d) / 6 : d < 2 ? (2 - d) * (2 - d) * (2 - d) / 6 : 0;
            }
            lo[i] = u < 0.5f ? -1 : 1;
            lt[i] = Math.abs(f);
            // furrows: three a tile, lw wide; how much of this pixel row they cover
            float c = 0;
            for (int k = 1; k < 4; k++) {
                float a = k * p / 4f, b = a + lw;
                c += Math.max(0, Math.min(i + 1, b) - Math.max(i, a));
            }
            fur[i] = Math.min(1, c);
        }
    }

    private static int lerp(int c1, int c2, float t) {
        if (t <= 0) return c1;
        if (t >= 1) return c2;
        return mix(c1, c2, t);
    }

    /** The water's colour at a distance from land (in tiles; 0.5 at the shore). */
    private static int waterColor(float d) {
        if (d < 1.6f) return lerp(SHALLOW, MIDWATER, (d - 0.5f) / 1.1f);
        return lerp(MIDWATER, DEEPWATER, (d - 1.6f) / 4.5f);
    }

    private static boolean beachy(int k) {
        return k == C_SAND || k == C_GRASS || k == C_FOREST || k == C_FARM;
    }

    /** Smooth value noise over 4-tile cells, 0..1. */
    private static float lumpy(long seed, int tx, int ty) {
        int gx = tx >> 2, gy = ty >> 2;
        float fx = (tx - gx * 4 + 0.5f) / 4f, fy = (ty - gy * 4 + 0.5f) / 4f;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        long s2 = seed ^ 0x5DEECE66DL;
        float a = Rng.unit(s2, gx, gy), b = Rng.unit(s2, gx + 1, gy), c = Rng.unit(s2, gx, gy + 1), d = Rng.unit(s2, gx + 1, gy + 1);
        return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
    }

    /** Paints the chunk's ground into out (CHUNK * p pixels square). */
    void paintGround(int[] out, int tx0, int ty0, int p, int lvl) {
        tables(p);
        int side = CHUNK * p;
        long seed = r.seed;
        for (int wy = 0; wy < WIN; wy++)
            for (int wx = 0; wx < WIN; wx++) {
                int tx = tx0 - M + wx, ty = ty0 - M + wy, i = wy * WIN + wx;
                int t = r.t(tx, ty);
                byte c = SOFT[t];
                wc[i] = c;
                wd[i] = c == C_WATER ? 1e4f : 0;
                boolean near = wx >= M - 3 && wy >= M - 3 && wx < WIN - M + 3 && wy < WIN - M + 3;
                if (near) {
                    // a gentle mottling in broad patches, smooth from tile to tile
                    wm[i] = realistic && (c > C_WATER || natural(t)) ? 1 + (lumpy(seed + 17, tx, ty) - 0.5f) * 0.09f + (Rng.unit(seed, tx, ty) - 0.5f) * 0.025f : 1;
                    // the edges wander a little, slowly (over a few tiles)
                    wj[i] = (lumpy(seed, tx, ty) - 0.5f) * 0.5f;
                }
            }
        // how far each water tile is from land (a two-pass chamfer over the window), deeper out at sea
        final float D = 1.4142f;
        for (int wy = 0; wy < WIN; wy++)
            for (int wx = 0; wx < WIN; wx++) {
                int i = wy * WIN + wx;
                float v = wd[i];
                if (v == 0) continue;
                if (wx > 0) v = Math.min(v, wd[i - 1] + 1);
                if (wy > 0) {
                    v = Math.min(v, wd[i - WIN] + 1);
                    if (wx > 0) v = Math.min(v, wd[i - WIN - 1] + D);
                    if (wx < WIN - 1) v = Math.min(v, wd[i - WIN + 1] + D);
                }
                wd[i] = v;
            }
        for (int wy = WIN - 1; wy >= 0; wy--)
            for (int wx = WIN - 1; wx >= 0; wx--) {
                int i = wy * WIN + wx;
                float v = wd[i];
                if (v == 0) continue;
                if (wx < WIN - 1) v = Math.min(v, wd[i + 1] + 1);
                if (wy < WIN - 1) {
                    v = Math.min(v, wd[i + WIN] + 1);
                    if (wx < WIN - 1) v = Math.min(v, wd[i + WIN + 1] + D);
                    if (wx > 0) v = Math.min(v, wd[i + WIN - 1] + D);
                }
                v = Math.min(v, M + 1);
                if (r.t(tx0 - M + wx, ty0 - M + wy) == Tile.DEEP) v += 2.5f;
                wd[i] = v;
            }
        // each kind's share, blurred ([1 4 6 4 1] / 16 each way) and partly kept sharp, wandering with the noise
        int present = 0;
        for (int i = 0; i < WIN * WIN; i++) if (wc[i] >= 0) present |= 1 << wc[i];
        int lo0 = M - 2, hi0 = WIN - M + 2; // the tiles whose share is needed: the chunk and two round it
        for (int k = 0; k < NCLS; k++) {
            if ((present & 1 << k) == 0) continue;
            float[] f = share[k];
            for (int wy = lo0 - 2; wy < hi0 + 2; wy++)
                for (int wx = lo0; wx < hi0; wx++) {
                    int i = wy * WIN + wx;
                    tmp[i] = ((wc[i - 2] == k ? 1 : 0) + (wc[i + 2] == k ? 1 : 0) + 4 * ((wc[i - 1] == k ? 1 : 0) + (wc[i + 1] == k ? 1 : 0)) + (wc[i] == k ? 6 : 0)) / 16f;
                }
            for (int wy = lo0; wy < hi0; wy++)
                for (int wx = lo0; wx < hi0; wx++) {
                    int i = wy * WIN + wx;
                    float b = (tmp[i - 2 * WIN] + tmp[i + 2 * WIN] + 4 * (tmp[i - WIN] + tmp[i + WIN]) + 6 * tmp[i]) / 16f;
                    float v = 0.7f * b + (wc[i] == k ? 0.3f : 0);
                    f[i] = v * (1 + WANDER[k] * wj[i]) * (k == C_WATER ? 1.1f : 1);
                }
        }
        float[] scr = sc;
        for (int y = 0; y < CHUNK; y++)
            for (int x = 0; x < CHUNK; x++) {
                int wi = (y + M) * WIN + x + M;
                int tx = tx0 + x, ty = ty0 + y;
                int c0 = wc[wi];
                int base = y * p * side + x * p;
                if (c0 < 0) {
                    // man-made: square, one colour (gardens and parks mottled smoothly)
                    int t = r.t(tx, ty);
                    if (realistic && natural(t)) {
                        fillPlain(out, base, side, p, wi, GROUND[t], false, false);
                        continue;
                    }
                    int col = t == Tile.BUILDING && lvl >= 1 ? GROUND[Tile.BUILDING] : tileColor(r, tx, ty, realistic);
                    for (int j = 0; j < p; j++) {
                        int o = base + j * side;
                        for (int i = 0; i < p; i++) out[o + i] = col;
                    }
                    continue;
                }
                // mixed: another kind comes near enough to this tile to matter (else it's all its own colour)
                boolean mixed = false;
                int local = 0;
                float minOwn = 9, maxOther = 0;
                float[] own = share[c0];
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++) {
                        int n = wi + dy * WIN + dx;
                        minOwn = Math.min(minOwn, own[n]);
                        for (int k = 0; k < NCLS; k++) {
                            if (k == c0 || (present & 1 << k) == 0) continue;
                            float v = share[k][n];
                            if (v > 0) {
                                local |= 1 << k;
                                maxOther = Math.max(maxOther, v);
                            }
                        }
                    }
                if (minOwn - maxOther < 0.3f) mixed = true; // else the edge is further than the beach reaches
                local |= 1 << c0;
                boolean across = Rng.unit(seed, tx >> 3, ty >> 3) < 0.5f;
                boolean furrows = lvl >= 1;
                if (!mixed) {
                    fillPlain(out, base, side, p, wi, c0 == C_WATER ? 0 : CLS_COLOR[c0], c0 == C_FARM && furrows, across);
                    continue;
                }
                for (int j = 0; j < p; j++) {
                    int o = base + j * side;
                    int ny = lo[j] * WIN;
                    float ay = lt[j];
                    for (int i = 0; i < p; i++) {
                        int nx = lo[i];
                        float ax = lt[i];
                        // the mottling and the depth, smooth across tiles
                        int i10 = wi + nx, i01 = wi + ny, i11 = i01 + nx;
                        float sh = (wm[wi] * (1 - ax) + wm[i10] * ax) * (1 - ay) + (wm[i01] * (1 - ax) + wm[i11] * ax) * ay;
                        int k1 = c0, k2 = -1;
                        float ep = 1e9f;
                        if (mixed) {
                            // each kind's share here, a cubic B-spline over the 4x4 tile centres round it
                            int r0 = wi + kb[j] * WIN + kb[i], cj = j * 4, ci = i * 4;
                            float x0 = cw[ci], x1 = cw[ci + 1], x2 = cw[ci + 2], x3 = cw[ci + 3];
                            for (int k = 0; k < NCLS; k++) {
                                if ((local & 1 << k) == 0) {
                                    scr[k] = 0;
                                    continue;
                                }
                                float[] f = share[k];
                                float v = 0;
                                for (int dy = 0, n = r0; dy < 4; dy++, n += WIN)
                                    v += cw[cj + dy] * (f[n] * x0 + f[n + 1] * x1 + f[n + 2] * x2 + f[n + 3] * x3);
                                scr[k] = v;
                            }
                            float s1 = -1, s2 = -1;
                            for (int k = 0; k < NCLS; k++) {
                                float v = scr[k];
                                if (v <= 0) continue;
                                if (v > s1) {
                                    s2 = s1;
                                    k2 = k1;
                                    s1 = v;
                                    k1 = k;
                                } else if (v > s2) {
                                    s2 = v;
                                    k2 = k;
                                }
                            }
                            if (s2 <= 0) k2 = -1;
                            else ep = (s1 - s2) * 1.1f * p; // about the distance to the edge, in pixels
                        }
                        int col;
                        if (k1 == C_WATER) {
                            float d = (wd[wi] * (1 - ax) + wd[i10] * ax) * (1 - ay) + (wd[i01] * (1 - ax) + wd[i11] * ax) * ay;
                            col = waterColor(Math.max(0.5f, d));
                            if (k2 >= 0) {
                                // a pale line of surf along the shore
                                float fw = Math.max(1.2f, p * 0.1f);
                                if (ep < fw) col = lerp(lerp(col, FOAM, 0.55f), col, ep / fw);
                            }
                        } else {
                            col = shade(CLS_COLOR[k1], sh);
                            if (k1 == C_FARM && furrows) {
                                float cv = across ? fur[j] : fur[i];
                                if (cv > 0) col = lerp(col, shade(col, 0.88f), cv);
                            }
                            if (k2 == C_WATER && beachy(k1)) {
                                // a beach: wet sand at the water's edge, dry sand behind, then the land
                                float bw = Math.max(1.5f, p * 0.3f), s = ep / bw;
                                if (s < 1) col = lerp(lerp(WET_SAND, BEACH, s * 2.5f), col, s * s);
                            }
                        }
                        if (k2 >= 0) {
                            // anti-aliased against the other side: a pixel's width at the water, softer between kinds of land
                            float soft = k1 == C_WATER || k2 == C_WATER ? 1 : Math.max(1, p * 0.12f);
                            float a = 0.5f + ep / soft;
                            if (a < 1) {
                                int other;
                                if (k2 == C_WATER) other = lerp(SHALLOW, FOAM, 0.55f);
                                else if (k1 == C_WATER && beachy(k2)) other = WET_SAND;
                                else other = shade(CLS_COLOR[k2], sh);
                                col = lerp(other, col, a);
                            }
                        }
                        out[o + i] = col;
                    }
                }
            }
    }

    /**
     * A tile all of one kind: its colour (water if color is 0) mottled or shaded by depth, both smooth across tiles
     * and slow to change, so worked out once for each small block of pixels; furrows on fields.
     */
    private void fillPlain(int[] out, int base, int side, int p, int wi, int color, boolean furrows, boolean across) {
        int bl = p >= 24 ? 4 : p >= 14 ? 2 : 1;
        for (int j0 = 0; j0 < p; j0 += bl) {
            int jc = Math.min(p - 1, j0 + bl / 2), j1 = Math.min(p, j0 + bl);
            int i01 = wi + lo[jc] * WIN;
            float ay = lt[jc];
            for (int i0 = 0; i0 < p; i0 += bl) {
                int ic = Math.min(p - 1, i0 + bl / 2), i1 = Math.min(p, i0 + bl);
                float ax = lt[ic];
                int n = lo[ic];
                int col;
                if (color == 0) {
                    float d = (wd[wi] * (1 - ax) + wd[wi + n] * ax) * (1 - ay) + (wd[i01] * (1 - ax) + wd[i01 + n] * ax) * ay;
                    col = waterColor(Math.max(0.5f, d));
                } else {
                    float sh = (wm[wi] * (1 - ax) + wm[wi + n] * ax) * (1 - ay) + (wm[i01] * (1 - ax) + wm[i01 + n] * ax) * ay;
                    col = shade(color, sh);
                }
                if (!furrows) {
                    for (int j = j0; j < j1; j++) {
                        int o = base + j * side;
                        for (int i = i0; i < i1; i++) out[o + i] = col;
                    }
                } else {
                    int dark = shade(col, 0.88f);
                    for (int j = j0; j < j1; j++) {
                        int o = base + j * side;
                        for (int i = i0; i < i1; i++) {
                            float cv = across ? fur[j] : fur[i];
                            out[o + i] = cv <= 0 ? col : cv >= 1 ? dark : mix(col, dark, cv);
                        }
                    }
                }
            }
        }
    }

    private void paintChunk(Gfx g, int tx0, int ty0, int p, int lvl) {
        boolean mid = lvl >= 1, high = lvl >= 2;
        // the ground itself is already there (paintGround): what follows is drawn over it
        if (!mid) return;
        float lw = Math.max(1, p / 12f);
        for (int y = 0; y < CHUNK; y++)
            for (int x = 0; x < CHUNK; x++) {
                int tx = tx0 + x, ty = ty0 + y;
                int t = r.t(tx, ty);
                float px = x * p, py = y * p;
                int c = GROUND[t];
                switch (t) {
                    case Tile.PAVEMENT:
                    case Tile.PLAZA:
                        if (high) {
                            g.color(shade(c, 0.93f));
                            g.fillRect(px, py, p, lw * 0.7f);
                            g.fillRect(px, py, lw * 0.7f, p);
                        }
                        break;
                    case Tile.WATER:
                    case Tile.DEEP: {
                        // a few glints, away from the shore (the shallows are in the ground already)
                        boolean shore = !Tile.water(r.t(tx - 1, ty)) || !Tile.water(r.t(tx + 1, ty)) || !Tile.water(r.t(tx, ty - 1)) || !Tile.water(r.t(tx, ty + 1));
                        if (!shore && Rng.unit(r.seed, tx, ty * 3) < 0.07f) {
                            g.color(0x40E8F4FF);
                            g.fillRect(px + p * 0.15f, py + p / 2f, p * 0.6f, lw);
                        }
                        break;
                    }
                    case Tile.LOT:
                        if (high && (tx & 1) == 0) {
                            g.color(0x90D8D8D0);
                            g.fillRect(px, py + p * 0.12f, lw, p * 0.76f);
                        }
                        break;
                    case Tile.RUBBLE:
                        g.color(0xFF8A8076);
                        g.fillRect(px + p * 0.1f, py + p * 0.2f, p / 3f, p / 4f);
                        g.color(0xFF3E3832);
                        g.fillRect(px + p / 2f, py + p / 2f, p / 3f, p / 3f);
                        g.color(0xFF2A2420);
                        g.fillRect(px + p * 0.3f, py + p * 0.65f, p / 5f, p / 6f);
                        break;
                    case Tile.FENCE:
                        g.color(0xFF3A3A3A);
                        g.fillRect(px + p * 0.4f, py, p * 0.2f, p);
                        g.fillRect(px, py + p * 0.4f, p, p * 0.2f);
                        break;
                    case Tile.WALL:
                        g.color(0xFF7A6E64);
                        g.fillRect(px, py, p, p);
                        g.color(0xFF9A8E82);
                        g.fillRect(px, py, p, lw);
                        break;
                    case Tile.BARRICADE:
                        for (int k = 0; k < 4; k++) {
                            g.color((k & 1) == 0 ? 0xFFE0B030 : 0xFF2A2A2A);
                            g.fillRect(px + k * p / 4f, py + p * 0.3f, p / 4f, p * 0.4f);
                        }
                        break;
                    case Tile.RAIL:
                        g.color(0xFF4A4038);
                        for (int k = 0; k < 3; k++) g.fillRect(px + k * p / 3f, py + p * 0.1f, lw * 1.2f, p * 0.8f);
                        g.color(0xFFB0B0B0);
                        g.fillRect(px, py + p * 0.3f, p, lw);
                        g.fillRect(px, py + p * 0.66f, p, lw);
                        break;
                    case Tile.BRIDGE:
                        g.color(0xFF8A8480);
                        if (!Tile.roadish(r.t(tx, ty - 1)) && Tile.water(r.t(tx, ty - 1))) g.fillRect(px, py, p, lw * 2);
                        if (!Tile.roadish(r.t(tx, ty + 1)) && Tile.water(r.t(tx, ty + 1))) g.fillRect(px, py + p - lw * 2, p, lw * 2);
                        if (!Tile.roadish(r.t(tx - 1, ty)) && Tile.water(r.t(tx - 1, ty))) g.fillRect(px, py, lw * 2, p);
                        if (!Tile.roadish(r.t(tx + 1, ty)) && Tile.water(r.t(tx + 1, ty))) g.fillRect(px + p - lw * 2, py, lw * 2, p);
                        break;
                    default:
                }
            }
        // kerbs: a pale kerbstone on the pavement side, a dark gutter on the road side
        for (int y = 0; y < CHUNK; y++)
            for (int x = 0; x < CHUNK; x++) {
                int tx = tx0 + x, ty = ty0 + y;
                int t = r.t(tx, ty);
                if (!Tile.roadish(t) || t == Tile.BRIDGE) continue;
                float px = x * p, py = y * p;
                for (int d = 0; d < 4; d++) {
                    int nx = tx + (d == 0 ? -1 : d == 1 ? 1 : 0), ny = ty + (d == 2 ? -1 : d == 3 ? 1 : 0);
                    int n = r.t(nx, ny);
                    if (Tile.roadish(n)) continue;
                    float kw = Math.max(1, p * 0.09f);
                    g.color(0x50000000);
                    if (d == 0) g.fillRect(px, py, kw, p);
                    else if (d == 1) g.fillRect(px + p - kw, py, kw, p);
                    else if (d == 2) g.fillRect(px, py, p, kw);
                    else g.fillRect(px, py + p - kw, p, kw);
                    if (n == Tile.PAVEMENT || n == Tile.PLAZA) {
                        g.color(0xFFC4C0B6);
                        if (d == 0) g.fillRect(px - kw, py, kw, p);
                        else if (d == 1) g.fillRect(px + p, py, kw, p);
                        else if (d == 2) g.fillRect(px, py - kw, p, kw);
                        else g.fillRect(px, py + p, p, kw);
                    }
                }
            }
        // lane markings and crossings from the road graph
        RoadGraph rg = r.roads;
        float ox = tx0 * 16f, oy = ty0 * 16f, sc = p / 16f, size = CHUNK * 16f;
        for (int e = 0; e < rg.edgeCount; e++) {
            if (!rg.ealive[e]) continue;
            float ax = rg.nx[rg.ea[e]], ay = rg.ny[rg.ea[e]], bx = rg.nx[rg.eb[e]], by = rg.ny[rg.eb[e]];
            if (Math.max(ax, bx) < ox - 60 || Math.min(ax, bx) > ox + size + 60 || Math.max(ay, by) < oy - 60 || Math.min(ay, by) > oy + size + 60) continue;
            float dx = bx - ax, dy = by - ay, len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1) continue;
            float ux = dx / len, uy = dy / len;
            float half = rg.ehalf[e];
            // keep the markings out of the junction squares
            float trim = half + 6;
            if (len <= trim * 2) continue;
            float sx = ax + ux * trim, sy = ay + uy * trim, ex = bx - ux * trim, ey = by - uy * trim;
            byte kind = rg.ekind[e];
            float mw = Math.max(1, sc * 1.3f);
            if (kind == RoadGraph.STREET) {
                if (!high) continue;
                g.color(0xC8E6E6E0);
                drawLine(g, (sx - ox) * sc, (sy - oy) * sc, (ex - ox) * sc, (ey - oy) * sc, mw, 9 * sc);
            } else {
                // a double yellow line down the middle
                float o = 1.6f;
                g.color(0xFFD8B440);
                drawLine(g, (sx - uy * o - ox) * sc, (sy + ux * o - oy) * sc, (ex - uy * o - ox) * sc, (ey + ux * o - oy) * sc, mw, 0);
                drawLine(g, (sx + uy * o - ox) * sc, (sy - ux * o - oy) * sc, (ex + uy * o - ox) * sc, (ey - ux * o - oy) * sc, mw, 0);
                if (high && half > 12) {
                    // lane lines, and solid edges on the highway
                    g.color(0xB0E8E8E2);
                    float l = half * 0.5f;
                    drawLine(g, (sx - uy * l - ox) * sc, (sy + ux * l - oy) * sc, (ex - uy * l - ox) * sc, (ey + ux * l - oy) * sc, mw * 0.8f, 8 * sc);
                    drawLine(g, (sx + uy * l - ox) * sc, (sy - ux * l - oy) * sc, (ex + uy * l - ox) * sc, (ey - ux * l - oy) * sc, mw * 0.8f, 8 * sc);
                    if (kind == RoadGraph.HIGHWAY) {
                        float h2 = half - 3;
                        drawLine(g, (sx - uy * h2 - ox) * sc, (sy + ux * h2 - oy) * sc, (ex - uy * h2 - ox) * sc, (ey + ux * h2 - oy) * sc, mw, 0);
                        drawLine(g, (sx + uy * h2 - ox) * sc, (sy - ux * h2 - oy) * sc, (ex + uy * h2 - ox) * sc, (ey - ux * h2 - oy) * sc, mw, 0);
                    }
                }
            }
            // zebra crossings where town streets meet at a junction
            if (high && kind != RoadGraph.HIGHWAY && len > trim * 2 + 24) {
                for (int end = 0; end < 2; end++) {
                    int node = end == 0 ? rg.ea[e] : rg.eb[e];
                    if (rg.nset[node] < 0 || rg.adjStart == null || rg.adjStart[node + 1] - rg.adjStart[node] < 3) continue;
                    float cxp = end == 0 ? sx + ux * 4 : ex - ux * 4, cyp = end == 0 ? sy + uy * 4 : ey - uy * 4;
                    g.color(0xD0E8E8E2);
                    int stripes = Math.max(3, (int) (half * 2 / 4.5f));
                    for (int k = 0; k < stripes; k++) {
                        float o = -half + 2 + k * (half * 2 - 4) / (stripes - 1f);
                        float qx = cxp - uy * o, qy = cyp + ux * o;
                        drawLine(g, (qx - ux * 3 - ox) * sc, (qy - uy * 3 - oy) * sc, (qx + ux * 3 - ox) * sc, (qy + uy * 3 - oy) * sc, Math.max(1, sc * 1.8f), 0);
                    }
                }
            }
        }
        // trees: clustered canopies with a shadow, a body and a sunlit top
        for (int y = 0; y < CHUNK; y++)
            for (int x = 0; x < CHUNK; x++) {
                int tx = tx0 + x, ty = ty0 + y;
                int t = r.t(tx, ty);
                boolean tree = t == Tile.TREE || (t == Tile.FOREST && Rng.unit(r.seed, tx * 7, ty) < 0.7f)
                        || ((t == Tile.PARK || t == Tile.YARD) && Rng.unit(r.seed, tx * 5, ty * 3) < 0.06f);
                if (!tree) continue;
                // the beach stays clear
                if (t == Tile.FOREST && (Tile.water(r.t(tx - 1, ty)) || Tile.water(r.t(tx + 1, ty)) || Tile.water(r.t(tx, ty - 1)) || Tile.water(r.t(tx, ty + 1)))) continue;
                float jx = (Rng.unit(r.seed, tx, ty + 11) - 0.5f) * p * 0.6f, jy = (Rng.unit(r.seed, tx + 5, ty) - 0.5f) * p * 0.6f;
                float rad = p * (0.5f + Rng.unit(r.seed, tx + 1, ty + 1) * 0.35f);
                float cx = x * p + p / 2f + jx, cy = y * p + p / 2f + jy;
                boolean forest = t == Tile.FOREST;
                int base = forest ? 0xFF33502F : 0xFF3D6034;
                if (Rng.unit(r.seed, tx + 3, ty + 9) < 0.25f) base = forest ? 0xFF2D4A33 : 0xFF4A6A36;
                g.color(0x48000000);
                g.fillCircle(cx + rad * 0.35f, cy + rad * 0.45f, rad * 1.05f);
                g.color(shade(base, 0.85f));
                g.fillCircle(cx, cy, rad);
                if (high) {
                    g.color(base);
                    g.fillCircle(cx - rad * 0.3f, cy + rad * 0.1f, rad * 0.62f);
                    g.fillCircle(cx + rad * 0.25f, cy - rad * 0.15f, rad * 0.6f);
                }
                g.color(shade(base, 1.22f));
                g.fillCircle(cx - rad * 0.28f, cy - rad * 0.3f, rad * 0.42f);
            }
    }

    private static void drawLine(Gfx g, float x1, float y1, float x2, float y2, float w, float dash) {
        if (dash <= 0) {
            g.line(x1, y1, x2, y2, w);
            return;
        }
        float dx = x2 - x1, dy = y2 - y1, len = (float) Math.sqrt(dx * dx + dy * dy);
        int n = (int) (len / (dash * 2));
        for (int i = 0; i < n; i++) {
            float a = i * dash * 2 / len, b = (i * dash * 2 + dash) / len;
            g.line(x1 + dx * a, y1 + dy * a, x1 + dx * b, y1 + dy * b, w);
        }
    }

    public void clear() {
        for (GImage i : cache.values()) i.recycle();
        cache.clear();
        cacheBytes = 0;
        if (regionImage != null) regionImage.recycle();
    }
}
