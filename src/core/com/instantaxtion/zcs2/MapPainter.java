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
    /** Today's maps: the region (null on a real-scale map). */
    private final Region r;
    /** A real-scale map: its chunks come from here (null on today's maps). */
    private final NationPainter.Source src;
    private final Nation nat;
    private final long seed;
    private final Platform pf;
    public GImage regionImage;
    private int[] regionPixels;
    private final Map<Integer, GImage> cache;
    private long cacheBytes;
    private int seenChanges;
    public int painted;
    /** Time spent painting pictures, ns (for drawing budgets and probes). */
    public long paintNs;
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
        src = null;
        nat = null;
        seed = r.seed;
        cache = new LinkedHashMap<Integer, GImage>(128, 0.75f, true);
        buildRegionImage();
        seenChanges = r.changes;
    }

    /**
     * The street pictures of a real-scale map, painted from its cached chunks (a picture whose chunks aren't all in
     * memory isn't painted: they are asked for in the background, and the district picture stands in). The far
     * zooms' picture is the nation's country picture.
     */
    public MapPainter(NationPainter.Source src, Platform pf, GImage countryImage) {
        r = null;
        this.src = src;
        this.pf = pf;
        nat = src.nation();
        seed = nat.seed;
        cache = new LinkedHashMap<Integer, GImage>(128, 0.75f, true);
        regionImage = countryImage;
    }

    /** The map's side in tiles. */
    public int tiles() {
        return r != null ? r.W : nat.size;
    }

    // ------------------------------------------------------------------ tiles: the region's, or the chunks' round the picture

    /** The chunks round the picture being painted (3 x 3 from (winX, winY), in the nation's chunk grid). */
    private final Chunk[] win = new Chunk[9];
    private int winX, winY;

    private int t(int x, int y) {
        if (r != null) return r.t(x, y);
        if (x < 0 || y < 0 || x >= nat.size || y >= nat.size) return Tile.DEEP;
        int k = ((y >> 6) - winY) * 3 + (x >> 6) - winX;
        if (k < 0 || k > 8 || win[k] == null) return Tile.GRASS;
        return win[k].tile[(y & 63) << 6 | (x & 63)];
    }

    /**
     * Gathers the chunks a picture at tile (tx0, ty0) reads (it and the margin round it). False if any isn't in memory
     * yet; those are asked for.
     */
    private boolean window(int tx0, int ty0) {
        winX = (tx0 >> 6) - 1;
        winY = (ty0 >> 6) - 1;
        boolean all = true;
        int lo = -M - 2, hi = CHUNK + M + 2;
        for (int j = 0; j < 3; j++)
            for (int i = 0; i < 3; i++) {
                int cx = winX + i, cy = winY + j;
                win[j * 3 + i] = null;
                // only the chunks the picture and its margin reach
                if ((cx + 1) * 64 <= tx0 + lo || cx * 64 >= tx0 + hi || (cy + 1) * 64 <= ty0 + lo || cy * 64 >= ty0 + hi) continue;
                if (cx < 0 || cy < 0 || cx >= nat.sw || cy >= nat.sw) continue;
                Chunk c = src.chunk(cx, cy);
                if (c == null) {
                    src.want(cx, cy);
                    all = false;
                }
                win[j * 3 + i] = c;
            }
        return all;
    }

    /** Whether a picture's chunks are all in memory (else they're asked for). */
    public boolean ready(int pcx, int pcy) {
        return r != null || window(pcx * CHUNK, pcy * CHUNK);
    }

    /** The colour of a tile as the far pictures show it: a roof for a building, darker at its edge. */
    private int tileColorAt(int x, int y, boolean texture) {
        if (r != null) return tileColor(r, x, y, texture);
        int t = t(x, y);
        int c = GROUND[t];
        if (t == Tile.BUILDING) {
            int k = ((y >> 6) - winY) * 3 + (x >> 6) - winX;
            Chunk ch = k >= 0 && k <= 8 ? win[k] : null;
            if (ch != null) {
                int v = ch.bld[(y & 63) << 6 | (x & 63)];
                int bx, by, bw, bh;
                if (v > 0) {
                    int b = v - 1;
                    c = ch.broof[b];
                    bx = ch.cx * 64 + ch.bx[b];
                    by = ch.cy * 64 + ch.by[b];
                    bw = ch.bw[b];
                    bh = ch.bh[b];
                } else if (v < 0) {
                    int b = -v - 1;
                    c = nat.broof[b];
                    bx = nat.bx[b];
                    by = nat.by[b];
                    bw = nat.bw[b];
                    bh = nat.bh[b];
                } else bx = by = bw = bh = -10;
                if (x == bx || y == by || x == bx + bw - 1 || y == by + bh - 1) c = shade(c, 0.8f);
            }
        }
        if (texture && natural(t)) {
            float n = (Rng.unit(seed, x >> 1, y >> 1) - 0.5f) * 0.07f + (Rng.unit(seed, x, y) - 0.5f) * 0.04f;
            c = shade(c, 1 + n);
        }
        return c;
    }

    private static boolean natural(int t) {
        return t == Tile.GRASS || t == Tile.FOREST || t == Tile.FARM || t == Tile.PARK || t == Tile.YARD || t == Tile.TREE || t == Tile.SAND;
    }

    public static int tileColor(Region r, int x, int y, boolean texture) {
        int t = r.t(x, y);
        int c = GROUND[t];
        if (t == Tile.BUILDING) {
            int b = r.building(x, y);
            if (b >= 0) {
                c = r.broof(b);
                if (x == r.bx(b) || y == r.by(b) || x == r.bx(b) + r.bw(b) - 1 || y == r.by(b) + r.bh(b) - 1) c = shade(c, 0.8f);
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
    /** A national World's region whose changes (rubble, blown bridges) redraw the street pictures they touch. */
    private Region watched;

    /** Drawing a national World: its map edits drop the street pictures they touch (refresh()). */
    public void watch(Region region) {
        watched = region;
        seenChanges = region.changes;
    }

    public void refresh() {
        if (r == null && watched != null) {
            // a national map: no picture of the whole map to patch, only the street pictures to drop
            Region q = watched;
            if (q.changes == seenChanges) return;
            seenChanges = q.changes;
            for (int[] d : q.dirty)
                for (int cy = Math.max(0, d[1] - M) / CHUNK; cy <= Math.min(q.H - 1, Math.max(0, d[3] + M)) / CHUNK; cy++)
                    for (int cx = Math.max(0, d[0] - M) / CHUNK; cx <= Math.min(q.W - 1, Math.max(0, d[2] + M)) / CHUNK; cx++)
                        for (int lvl = 0; lvl < PPT.length; lvl++) {
                            GImage img = cache.remove(key(cx, cy, lvl));
                            if (img != null) {
                                cacheBytes -= bytes(lvl);
                                img.recycle();
                            }
                        }
            q.dirty.clear();
            return;
        }
        if (r == null || r.changes == seenChanges) return;
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
        int side = CHUNK * ppt;
        // a picture the cache let go of at this level is painted over rather than a new one made
        if (r == null && !window(cx * CHUNK, cy * CHUNK)) return null;
        long t0 = System.nanoTime();
        img = spare[lvl];
        spare[lvl] = null;
        if (img == null) img = pf.newImage(side, side);
        if (pix == null || pix.length < side * side) pix = new int[side * side];
        paintGround(pix, cx * CHUNK, cy * CHUNK, ppt, lvl);
        paintDetails(pix, cx * CHUNK, cy * CHUNK, ppt, lvl);
        if (nat != null) smoothDiagonals(pix, cx * CHUNK, cy * CHUNK, ppt, side);
        img.setPixels(pix, side, side);
        if (hasLanes(cx * CHUNK, cy * CHUNK, lvl)) paintLanes(img.gfx(), cx * CHUNK, cy * CHUNK, ppt, lvl);
        cache.put(k, img);
        cacheBytes += bytes(lvl);
        painted++;
        paintNs += System.nanoTime() - t0;
        while (cacheBytes > BUDGET_BYTES && cache.size() > 1) {
            Integer oldest = cache.keySet().iterator().next();
            GImage o = cache.remove(oldest);
            int ol = oldest >>> 28;
            cacheBytes -= bytes(ol);
            if (o == null) continue;
            if (spare[ol] == null) spare[ol] = o;
            else o.recycle();
        }
        return img;
    }

    // ------------------------------------------------------------------ diagonal roads, anti-aliased

    private int[] diagSrc;

    /**
     * National maps: roads that don't run along the grid (highways, roads between towns, the capital's diagonal
     * avenues) are marked on whole tiles, so their edges are staircases. Along each such road the pixels within
     * about a tile of its true edge are blended between the road (sampled towards its middle) and what lies beside
     * it (sampled outwards), by how much of the pixel the road covers: a straight, anti-aliased edge.
     */
    private void smoothDiagonals(int[] pix, int X0, int Y0, int ppt, int side) {
        RoadGraph g = nat.roads;
        if (g.chunkEdgeStart == null || g.chunkW != nat.sw) return;
        int c = (Y0 / Chunk.SIZE) * g.chunkW + X0 / Chunk.SIZE;
        float pxw = Tile.SIZE / (float) ppt; // world px a pixel
        float wx0 = X0 * Tile.SIZE, wy0 = Y0 * Tile.SIZE, wspan = side * pxw;
        boolean copied = false;
        for (int k = g.chunkEdgeStart[c]; k < g.chunkEdgeStart[c + 1]; k++) {
            int e = g.chunkEdge[k];
            float ax = g.nx[g.ea[e]], ay = g.ny[g.ea[e]], bx = g.nx[g.eb[e]], by = g.ny[g.eb[e]];
            if (ax == bx || ay == by) continue; // along the grid: already straight
            float dx = bx - ax, dy = by - ay, len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1) continue;
            float ux = dx / len, uy = dy / len;
            // ChunkGen marks tile centres within this of the line
            float R = Math.max(g.ehalf[e] - 4, 24), band = Tile.SIZE * 0.9f;
            float minx = Math.min(ax, bx) - R - band, maxx = Math.max(ax, bx) + R + band;
            float miny = Math.min(ay, by) - R - band, maxy = Math.max(ay, by) + R + band;
            int px0 = Math.max(0, (int) ((minx - wx0) / pxw)), px1 = Math.min(side - 1, (int) ((maxx - wx0) / pxw));
            int py0 = Math.max(0, (int) ((miny - wy0) / pxw)), py1 = Math.min(side - 1, (int) ((maxy - wy0) / pxw));
            if (px0 > px1 || py0 > py1) continue;
            if (!copied) {
                if (diagSrc == null || diagSrc.length < side * side) diagSrc = new int[side * side];
                System.arraycopy(pix, 0, diagSrc, 0, side * side);
                copied = true;
            }
            for (int py = py0; py <= py1; py++)
                for (int px = px0; px <= px1; px++) {
                    float wx = wx0 + (px + 0.5f) * pxw, wy = wy0 + (py + 0.5f) * pxw;
                    float rx = wx - ax, ry = wy - ay;
                    float along = rx * ux + ry * uy;
                    if (along < 0 || along > len) continue; // the ends meet other roads at junctions: leave them
                    float across = -rx * uy + ry * ux, d = Math.abs(across);
                    if (Math.abs(d - R) > band) continue;
                    float sgn = across < 0 ? -1 : 1;
                    // the road's own colour a little inside the edge, the ground's a little outside
                    float inD = Math.max(0, R - band - 2), outD = R + band + 2;
                    int ix = (int) ((ax + ux * along - uy * sgn * inD - wx0) / pxw), iy = (int) ((ay + uy * along + ux * sgn * inD - wy0) / pxw);
                    int ox = (int) ((ax + ux * along - uy * sgn * outD - wx0) / pxw), oy = (int) ((ay + uy * along + ux * sgn * outD - wy0) / pxw);
                    int self = diagSrc[py * side + px];
                    int in = ix >= 0 && iy >= 0 && ix < side && iy < side ? diagSrc[iy * side + ix] : self;
                    int out = ox >= 0 && oy >= 0 && ox < side && oy < side ? diagSrc[oy * side + ox] : self;
                    float cov = Math.max(0, Math.min(1, (R - d) / pxw + 0.5f));
                    pix[py * side + px] = mix(out, in, cov);
                }
        }
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
    /** One picture per level that the cache let go of, kept to be painted over. */
    private final GImage[] spare = new GImage[PPT.length];
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

    /** The water's colour at a distance from land (in tiles; 0.5 at the shore), as the ground paints it. */
    public static int waterColorAt(float d) {
        return waterColor(d);
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

    /** Smooth value noise's corner values for the window, so each costs one hash (lumpy() hashes four per tile). */
    private final float[] lumpA = new float[LG * LG], lumpB = new float[LG * LG];
    private static final int LG = (WIN + 3) / 4 + 2;

    private static void lumpyCorners(float[] out, long seed, int gx0, int gy0) {
        long s2 = seed ^ 0x5DEECE66DL;
        for (int j = 0; j < LG; j++)
            for (int i = 0; i < LG; i++) out[j * LG + i] = Rng.unit(s2, gx0 + i, gy0 + j);
    }

    /** lumpy(seed, tx, ty), with its corners from lumpyCorners (the same arithmetic, so the same value). */
    private static float lumpyAt(float[] cor, int gx0, int gy0, int tx, int ty) {
        int gx = tx >> 2, gy = ty >> 2;
        float fx = (tx - gx * 4 + 0.5f) / 4f, fy = (ty - gy * 4 + 0.5f) / 4f;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        int o = (gy - gy0) * LG + gx - gx0;
        float a = cor[o], b = cor[o + 1], c = cor[o + LG], d = cor[o + LG + 1];
        return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
    }

    /** Per window tile: 1 where another kind of ground is within four tiles (only there can its edge show). */
    private final byte[] edge = new byte[WIN * WIN];
    /** For a tile with an edge: each local kind's B-spline along the rows (five rows round the tile) at each pixel column. */
    private float[] rows;

    /** Paints the chunk's ground into out (CHUNK * p pixels square). */
    void paintGround(int[] out, int tx0, int ty0, int p, int lvl) {
        tables(p);
        int side = CHUNK * p;
        long seed = this.seed;
        if (rows == null || rows.length < NCLS * 5 * p) rows = new float[NCLS * 5 * p];
        int lgx = (tx0 - M) >> 2, lgy = (ty0 - M) >> 2;
        lumpyCorners(lumpA, seed + 17, lgx, lgy);
        int present = 0;
        for (int wy = 0; wy < WIN; wy++)
            for (int wx = 0; wx < WIN; wx++) {
                int tx = tx0 - M + wx, ty = ty0 - M + wy, i = wy * WIN + wx;
                int t = t(tx, ty);
                byte c = SOFT[t];
                wc[i] = c;
                if (c >= 0) present |= 1 << c;
                wd[i] = c == C_WATER ? 1e4f : 0;
                boolean near = wx >= M - 3 && wy >= M - 3 && wx < WIN - M + 3 && wy < WIN - M + 3;
                if (near) {
                    // a gentle mottling in broad patches, smooth from tile to tile
                    wm[i] = realistic && (c > C_WATER || natural(t)) ? 1 + (lumpyAt(lumpA, lgx, lgy, tx, ty) - 0.5f) * 0.09f + (Rng.unit(seed, tx, ty) - 0.5f) * 0.025f : 1;
                }
            }
        // where kinds meet: both tiles of each unlike pair, then spread four tiles (the blur and the look round a tile)
        java.util.Arrays.fill(edge, (byte) 0);
        boolean anyEdge = false;
        for (int wy = 0; wy < WIN; wy++)
            for (int wx = 0; wx < WIN; wx++) {
                int i = wy * WIN + wx;
                if (wx + 1 < WIN && wc[i] != wc[i + 1]) {
                    edge[i] = edge[i + 1] = 1;
                    anyEdge = true;
                }
                if (wy + 1 < WIN && wc[i] != wc[i + WIN]) {
                    edge[i] = edge[i + WIN] = 1;
                    anyEdge = true;
                }
            }
        if (anyEdge) {
            // the edges wander a little, slowly (over a few tiles)
            lumpyCorners(lumpB, seed, lgx, lgy);
            for (int wy = M - 3; wy < WIN - M + 3; wy++)
                for (int wx = M - 3; wx < WIN - M + 3; wx++)
                    wj[wy * WIN + wx] = (lumpyAt(lumpB, lgx, lgy, tx0 - M + wx, ty0 - M + wy) - 0.5f) * 0.5f;
            // spread by four each way: along the rows, then down the columns, with running counts
            byte[] e2 = edgeTmp;
            for (int wy = 0; wy < WIN; wy++) {
                int row = wy * WIN, n = 0;
                for (int x = 0; x < 4; x++) n += edge[row + x];
                for (int wx = 0; wx < WIN; wx++) {
                    if (wx + 4 < WIN) n += edge[row + wx + 4];
                    if (wx - 5 >= 0) n -= edge[row + wx - 5];
                    e2[row + wx] = (byte) (n > 0 ? 1 : 0);
                }
            }
            for (int wx = 0; wx < WIN; wx++) {
                int n = 0;
                for (int y = 0; y < 4; y++) n += e2[y * WIN + wx];
                for (int wy = 0; wy < WIN; wy++) {
                    if (wy + 4 < WIN) n += e2[(wy + 4) * WIN + wx];
                    if (wy - 5 >= 0) n -= e2[(wy - 5) * WIN + wx];
                    edge[wy * WIN + wx] = (byte) (n > 0 ? 1 : 0);
                }
            }
        }
        // how far each water tile is from land (a two-pass chamfer over the window), deeper out at sea; only
        // needed with water about
        if ((present & 1 << C_WATER) != 0) {
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
                    if (t(tx0 - M + wx, ty0 - M + wy) == Tile.DEEP) v += 2.5f;
                    wd[i] = v;
                }
        }
        // each kind's share, blurred ([1 4 6 4 1] / 16 each way) and partly kept sharp, wandering with the noise
        // (only needed where there's an edge, and only for the kinds there are)
        int lo0 = M - 2, hi0 = WIN - M + 2; // the tiles whose share is needed: the chunk and two round it
        if (anyEdge)
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
        float[] rw = rows;
        int[] lk = localKinds;
        for (int y = 0; y < CHUNK; y++)
            for (int x = 0; x < CHUNK; x++) {
                int wi = (y + M) * WIN + x + M;
                int tx = tx0 + x, ty = ty0 + y;
                int c0 = wc[wi];
                int base = y * p * side + x * p;
                if (c0 < 0) {
                    // man-made: square, one colour (gardens and parks mottled smoothly)
                    int t = t(tx, ty);
                    if (realistic && natural(t)) {
                        fillPlain(out, base, side, p, wi, GROUND[t], false, false);
                        continue;
                    }
                    int col = t == Tile.BUILDING && lvl >= 1 ? GROUND[Tile.BUILDING] : tileColorAt(tx, ty, realistic);
                    for (int j = 0; j < p; j++) {
                        int o = base + j * side;
                        for (int i = 0; i < p; i++) out[o + i] = col;
                    }
                    continue;
                }
                boolean furrows = lvl >= 1;
                // mixed: another kind comes near enough to this tile to matter (else it's all its own colour); with
                // no other kind within four tiles it can't be
                boolean mixed = false;
                int local = 0;
                if (edge[wi] != 0) {
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
                }
                local |= 1 << c0;
                // furrows run one way or the other, field by field (only fields need to know)
                boolean across = furrows && (local & 1 << C_FARM) != 0 && Rng.unit(seed, tx >> 3, ty >> 3) < 0.5f;
                if (!mixed) {
                    fillPlain(out, base, side, p, wi, c0 == C_WATER ? 0 : CLS_COLOR[c0], c0 == C_FARM && furrows, across);
                    continue;
                }
                // the kinds about, in order
                int nk = 0;
                for (int k = 0; k < NCLS; k++) if ((local & 1 << k) != 0) lk[nk++] = k;
                // each local kind's cubic B-spline along the five rows round the tile, at each pixel column (the
                // same sums the per-pixel spline makes, so the same values)
                for (int q = 0; q < nk; q++) {
                    int k = lk[q];
                    float[] f = share[k];
                    for (int rr = 0; rr < 5; rr++) {
                        int rb = wi + (rr - 2) * WIN, ob = (k * 5 + rr) * p;
                        for (int i = 0; i < p; i++) {
                            int n = rb + kb[i], ci = i * 4;
                            rw[ob + i] = f[n] * cw[ci] + f[n + 1] * cw[ci + 1] + f[n + 2] * cw[ci + 2] + f[n + 3] * cw[ci + 3];
                        }
                    }
                }
                for (int j = 0; j < p; j++) {
                    int o = base + j * side;
                    int ny = lo[j] * WIN;
                    float ay = lt[j];
                    int cj = j * 4, r0 = kb[j] + 2;
                    float y0 = cw[cj], y1 = cw[cj + 1], y2 = cw[cj + 2], y3 = cw[cj + 3];
                    for (int i = 0; i < p; i++) {
                        int nx = lo[i];
                        float ax = lt[i];
                        // the mottling and the depth, smooth across tiles
                        int i10 = wi + nx, i01 = wi + ny, i11 = i01 + nx;
                        float sh = (wm[wi] * (1 - ax) + wm[i10] * ax) * (1 - ay) + (wm[i01] * (1 - ax) + wm[i11] * ax) * ay;
                        int k1 = c0, k2 = -1;
                        float ep = 1e9f;
                        // each kind's share here, the two that lead
                        float s1 = -1, s2 = -1;
                        for (int q = 0; q < nk; q++) {
                            int k = lk[q];
                            int ob = (k * 5 + r0) * p + i;
                            float v = 0;
                            v += y0 * rw[ob];
                            v += y1 * rw[ob + p];
                            v += y2 * rw[ob + 2 * p];
                            v += y3 * rw[ob + 3 * p];
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

    private final byte[] edgeTmp = new byte[WIN * WIN];
    private final int[] localKinds = new int[NCLS];

    /**
     * A tile all of one kind: its colour (water if color is 0) mottled or shaded by depth, both smooth across tiles
     * and slow to change, so worked out once for each small block of pixels; furrows on fields.
     */
    private void fillPlain(int[] out, int base, int side, int p, int wi, int color, boolean furrows, boolean across) {
        int bl = p >= 24 ? 4 : 2;
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

    // ------------------------------------------------------------------ the details, into the same pixels

    /*
     * The marks on the ground (paving joints, rubble, fences, walls, barricades, rails, bridge edges, kerbs) and the
     * trees are painted straight into the chunk's pixels after the ground, anti-aliased by how much of each pixel
     * a shape covers: the same look the canvas gives, without a drawing call per shape. Only the lane markings
     * (long, often diagonal lines) are drawn on the canvas afterwards.
     */

    /** Lays colour c (its alpha times cover) over pixel i. */
    private static void blend(int[] out, int i, int c, float cover) {
        int a = (int) ((c >>> 24) * cover + 0.5f);
        if (a <= 0) return;
        if (a >= 255) {
            out[i] = c | 0xFF000000;
            return;
        }
        int d = out[i], b = 255 - a;
        int rr = (((c >> 16) & 255) * a + ((d >> 16) & 255) * b + 127) / 255;
        int gg = (((c >> 8) & 255) * a + ((d >> 8) & 255) * b + 127) / 255;
        int bb = ((c & 255) * a + (d & 255) * b + 127) / 255;
        out[i] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    /** A rectangle in chunk pixels, its edges anti-aliased. */
    private static void rect(int[] out, int side, float x, float y, float w, float h, int c) {
        float x1 = Math.min(side, x + w), y1 = Math.min(side, y + h);
        x = Math.max(0, x);
        y = Math.max(0, y);
        if (x1 <= x || y1 <= y) return;
        int ix0 = (int) x, ix1 = (int) Math.ceil(x1), iy0 = (int) y, iy1 = (int) Math.ceil(y1);
        for (int iy = iy0; iy < iy1; iy++) {
            float cy = Math.min(iy + 1, y1) - Math.max(iy, y);
            int row = iy * side;
            for (int ix = ix0; ix < ix1; ix++) {
                float cx = Math.min(ix + 1, x1) - Math.max(ix, x);
                blend(out, row + ix, c, cx * cy);
            }
        }
    }

    /** A disc in chunk pixels, its edge anti-aliased (each pixel by its centre's distance from the rim). */
    private static void disc(int[] out, int side, float cx, float cy, float rad, int c) {
        if (rad <= 0) return;
        int iy0 = Math.max(0, (int) Math.floor(cy - rad - 0.5f)), iy1 = Math.min(side - 1, (int) Math.ceil(cy + rad + 0.5f));
        float in = rad - 0.7072f, out2 = (rad + 0.5f) * (rad + 0.5f);
        float in2 = in > 0 ? in * in : -1;
        boolean opaque = c >>> 24 == 255;
        for (int iy = iy0; iy <= iy1; iy++) {
            float dy = iy + 0.5f - cy, dy2 = dy * dy;
            if (dy2 >= out2) continue;
            float span = (float) Math.sqrt(out2 - dy2);
            int ix0 = Math.max(0, (int) Math.floor(cx - span)), ix1 = Math.min(side - 1, (int) Math.ceil(cx + span));
            // fully inside between these (no square root per pixel there)
            float inSpan = in2 > dy2 ? (float) Math.sqrt(in2 - dy2) : -1;
            int row = iy * side;
            // the solid run (pixels whose centres are well inside), then the rim pixel by pixel
            int s0 = ix1 + 1, s1 = ix1;
            if (inSpan >= 0) {
                s0 = Math.max(ix0, (int) Math.ceil(cx - 0.5f - inSpan));
                s1 = Math.min(ix1, (int) Math.floor(cx - 0.5f + inSpan));
                if (s1 >= s0) {
                    if (opaque) java.util.Arrays.fill(out, row + s0, row + s1 + 1, c);
                    else for (int ix = s0; ix <= s1; ix++) blend(out, row + ix, c, 1);
                } else {
                    s0 = ix1 + 1;
                    s1 = ix1;
                }
            }
            for (int ix = ix0; ix <= ix1; ix++) {
                if (ix == s0) {
                    ix = s1;
                    continue;
                }
                float dx = ix + 0.5f - cx;
                float cover = rad + 0.5f - (float) Math.sqrt(dx * dx + dy2);
                if (cover > 0) blend(out, row + ix, c, Math.min(1, cover));
            }
        }
    }

    /** The marks on the ground and the trees, over the ground in out. */
    void paintDetails(int[] out, int tx0, int ty0, int p, int lvl) {
        boolean mid = lvl >= 1, high = lvl >= 2;
        if (!mid) return;
        int side = CHUNK * p;
        float lw = Math.max(1, p / 12f);
        for (int y = 0; y < CHUNK; y++)
            for (int x = 0; x < CHUNK; x++) {
                int tx = tx0 + x, ty = ty0 + y;
                int t = t(tx, ty);
                float px = x * p, py = y * p;
                int c = GROUND[t];
                switch (t) {
                    case Tile.PAVEMENT:
                    case Tile.PLAZA:
                        if (high) {
                            int sc2 = shade(c, 0.93f);
                            rect(out, side, px, py, p, lw * 0.7f, sc2);
                            rect(out, side, px, py, lw * 0.7f, p, sc2);
                        }
                        break;
                    case Tile.WATER:
                    case Tile.DEEP: {
                        // a few glints, away from the shore (the shallows are in the ground already)
                        boolean shore = !Tile.water(t(tx - 1, ty)) || !Tile.water(t(tx + 1, ty)) || !Tile.water(t(tx, ty - 1)) || !Tile.water(t(tx, ty + 1));
                        if (!shore && Rng.unit(seed, tx, ty * 3) < 0.07f) rect(out, side, px + p * 0.15f, py + p / 2f, p * 0.6f, lw, 0x40E8F4FF);
                        break;
                    }
                    case Tile.LOT:
                        if (high && (tx & 1) == 0) rect(out, side, px, py + p * 0.12f, lw, p * 0.76f, 0x90D8D8D0);
                        break;
                    case Tile.RUBBLE:
                        rect(out, side, px + p * 0.1f, py + p * 0.2f, p / 3f, p / 4f, 0xFF8A8076);
                        rect(out, side, px + p / 2f, py + p / 2f, p / 3f, p / 3f, 0xFF3E3832);
                        rect(out, side, px + p * 0.3f, py + p * 0.65f, p / 5f, p / 6f, 0xFF2A2420);
                        break;
                    case Tile.FENCE:
                        rect(out, side, px + p * 0.4f, py, p * 0.2f, p, 0xFF3A3A3A);
                        rect(out, side, px, py + p * 0.4f, p, p * 0.2f, 0xFF3A3A3A);
                        break;
                    case Tile.WALL:
                        rect(out, side, px, py, p, p, 0xFF7A6E64);
                        rect(out, side, px, py, p, lw, 0xFF9A8E82);
                        break;
                    case Tile.BARRICADE:
                        for (int k = 0; k < 4; k++) rect(out, side, px + k * p / 4f, py + p * 0.3f, p / 4f, p * 0.4f, (k & 1) == 0 ? 0xFFE0B030 : 0xFF2A2A2A);
                        break;
                    case Tile.RAIL:
                        for (int k = 0; k < 3; k++) rect(out, side, px + k * p / 3f, py + p * 0.1f, lw * 1.2f, p * 0.8f, 0xFF4A4038);
                        rect(out, side, px, py + p * 0.3f, p, lw, 0xFFB0B0B0);
                        rect(out, side, px, py + p * 0.66f, p, lw, 0xFFB0B0B0);
                        break;
                    case Tile.BRIDGE:
                        if (!Tile.roadish(t(tx, ty - 1)) && Tile.water(t(tx, ty - 1))) rect(out, side, px, py, p, lw * 2, 0xFF8A8480);
                        if (!Tile.roadish(t(tx, ty + 1)) && Tile.water(t(tx, ty + 1))) rect(out, side, px, py + p - lw * 2, p, lw * 2, 0xFF8A8480);
                        if (!Tile.roadish(t(tx - 1, ty)) && Tile.water(t(tx - 1, ty))) rect(out, side, px, py, lw * 2, p, 0xFF8A8480);
                        if (!Tile.roadish(t(tx + 1, ty)) && Tile.water(t(tx + 1, ty))) rect(out, side, px + p - lw * 2, py, lw * 2, p, 0xFF8A8480);
                        break;
                    default:
                }
            }
        // kerbs: a pale kerbstone on the pavement side, a dark gutter on the road side
        float kw = Math.max(1, p * 0.09f);
        for (int y = 0; y < CHUNK; y++)
            for (int x = 0; x < CHUNK; x++) {
                int tx = tx0 + x, ty = ty0 + y;
                int t = t(tx, ty);
                if (!Tile.roadish(t) || t == Tile.BRIDGE) continue;
                float px = x * p, py = y * p;
                for (int d = 0; d < 4; d++) {
                    int nx = tx + (d == 0 ? -1 : d == 1 ? 1 : 0), ny = ty + (d == 2 ? -1 : d == 3 ? 1 : 0);
                    int n = t(nx, ny);
                    if (Tile.roadish(n)) continue;
                    if (d == 0) rect(out, side, px, py, kw, p, 0x50000000);
                    else if (d == 1) rect(out, side, px + p - kw, py, kw, p, 0x50000000);
                    else if (d == 2) rect(out, side, px, py, p, kw, 0x50000000);
                    else rect(out, side, px, py + p - kw, p, kw, 0x50000000);
                    if (n == Tile.PAVEMENT || n == Tile.PLAZA) {
                        if (d == 0) rect(out, side, px - kw, py, kw, p, 0xFFC4C0B6);
                        else if (d == 1) rect(out, side, px + p, py, kw, p, 0xFFC4C0B6);
                        else if (d == 2) rect(out, side, px, py - kw, p, kw, 0xFFC4C0B6);
                        else rect(out, side, px, py + p, p, kw, 0xFFC4C0B6);
                    }
                }
            }
        // trees: clustered canopies with a shadow, a body and a sunlit top
        for (int y = 0; y < CHUNK; y++)
            for (int x = 0; x < CHUNK; x++) {
                int tx = tx0 + x, ty = ty0 + y;
                int t = t(tx, ty);
                boolean tree = t == Tile.TREE || (t == Tile.FOREST && Rng.unit(seed, tx * 7, ty) < 0.7f)
                        || ((t == Tile.PARK || t == Tile.YARD) && Rng.unit(seed, tx * 5, ty * 3) < 0.06f);
                if (!tree) continue;
                // the beach stays clear
                if (t == Tile.FOREST && (Tile.water(t(tx - 1, ty)) || Tile.water(t(tx + 1, ty)) || Tile.water(t(tx, ty - 1)) || Tile.water(t(tx, ty + 1)))) continue;
                float jx = (Rng.unit(seed, tx, ty + 11) - 0.5f) * p * 0.6f, jy = (Rng.unit(seed, tx + 5, ty) - 0.5f) * p * 0.6f;
                float rad = p * (0.5f + Rng.unit(seed, tx + 1, ty + 1) * 0.35f);
                float cx = x * p + p / 2f + jx, cy = y * p + p / 2f + jy;
                boolean forest = t == Tile.FOREST;
                int base = forest ? 0xFF33502F : 0xFF3D6034;
                if (Rng.unit(seed, tx + 3, ty + 9) < 0.25f) base = forest ? 0xFF2D4A33 : 0xFF4A6A36;
                disc(out, side, cx + rad * 0.35f, cy + rad * 0.45f, rad * 1.05f, 0x48000000);
                disc(out, side, cx, cy, rad, shade(base, 0.85f));
                if (high) {
                    disc(out, side, cx - rad * 0.3f, cy + rad * 0.1f, rad * 0.62f, base);
                    disc(out, side, cx + rad * 0.25f, cy - rad * 0.15f, rad * 0.6f, base);
                }
                disc(out, side, cx - rad * 0.28f, cy - rad * 0.3f, rad * 0.42f, shade(base, 1.22f));
            }
    }

    private RoadGraph roads() {
        return r != null ? r.roads : nat.roads;
    }

    // the edges near a picture on a real-scale map (from the nation's per-chunk buckets), each once
    private int[] edgeList, edgeStamp;
    private int edgeN, edgeStampN;

    /**
     * How many edges to look at for a picture at (tx0, ty0): on today's maps every edge (edgeList null: edge q is q),
     * on a real-scale map those bucketed in the chunks the picture and 60 px round it touch.
     */
    private int laneEdges(int tx0, int ty0) {
        RoadGraph rg = roads();
        if (r != null) {
            edgeList = null;
            return rg.edgeCount;
        }
        if (edgeList == null) edgeList = new int[256];
        if (edgeStamp == null || edgeStamp.length < rg.edgeCount) edgeStamp = new int[rg.edgeCount];
        edgeStampN++;
        edgeN = 0;
        if (rg.chunkEdgeStart == null || rg.chunkW != nat.sw) return 0;
        int cx0 = Math.max(0, (tx0 - 4) >> 6), cy0 = Math.max(0, (ty0 - 4) >> 6);
        int cx1 = Math.min(nat.sw - 1, (tx0 + CHUNK + 4) >> 6), cy1 = Math.min(nat.sw - 1, (ty0 + CHUNK + 4) >> 6);
        for (int cy = cy0; cy <= cy1; cy++)
            for (int cx = cx0; cx <= cx1; cx++) {
                int c = cy * nat.sw + cx;
                for (int k = rg.chunkEdgeStart[c]; k < rg.chunkEdgeStart[c + 1]; k++) {
                    int e = rg.chunkEdge[k];
                    if (edgeStamp[e] == edgeStampN) continue;
                    edgeStamp[e] = edgeStampN;
                    if (edgeN == edgeList.length) edgeList = java.util.Arrays.copyOf(edgeList, edgeN * 2);
                    edgeList[edgeN++] = e;
                }
            }
        return edgeN;
    }

    /** Does any road with markings at this level pass near the chunk? (Else the canvas needn't be touched.) */
    boolean hasLanes(int tx0, int ty0, int lvl) {
        if (lvl < 1) return false;
        RoadGraph rg = roads();
        float ox = tx0 * 16f, oy = ty0 * 16f, size = CHUNK * 16f;
        int ne = laneEdges(tx0, ty0);
        for (int q = 0; q < ne; q++) {
            int e = edgeList == null ? q : edgeList[q];
            if (!rg.ealive[e] || rg.ekind[e] == RoadGraph.STREET && lvl < 2) continue;
            float ax = rg.nx[rg.ea[e]], ay = rg.ny[rg.ea[e]], bx = rg.nx[rg.eb[e]], by = rg.ny[rg.eb[e]];
            if (Math.max(ax, bx) < ox - 60 || Math.min(ax, bx) > ox + size + 60 || Math.max(ay, by) < oy - 60 || Math.min(ay, by) > oy + size + 60) continue;
            return true;
        }
        return false;
    }

    /** Lane markings and crossings from the road graph, on the canvas (over everything else). */
    void paintLanes(Gfx g, int tx0, int ty0, int p, int lvl) {
        boolean high = lvl >= 2;
        RoadGraph rg = roads();
        float ox = tx0 * 16f, oy = ty0 * 16f, sc = p / 16f, size = CHUNK * 16f;
        int ne = laneEdges(tx0, ty0);
        for (int q = 0; q < ne; q++) {
            int e = edgeList == null ? q : edgeList[q];
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
        for (int l = 0; l < spare.length; l++) {
            if (spare[l] != null) spare[l].recycle();
            spare[l] = null;
        }
        cache.clear();
        cacheBytes = 0;
        if (regionImage != null && r != null) regionImage.recycle();
    }
}
