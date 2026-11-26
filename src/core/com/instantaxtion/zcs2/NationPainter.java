package com.instantaxtion.zcs2;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The far zooms of a real-scale map, painted from the coarse national layer alone (no chunk is generated for them):
 * <ul>
 * <li>the country picture, one pixel per 16-tile coarse cell (or per two on a phone), and a district density tint at
 * one pixel per sector, for the country view;</li>
 * <li>district pictures, 512 tiles (2 km) square, for the district view: at 8, 4 and 2 tiles per pixel as the zoom
 * grows (16, 64 and 256 KB each, in a 16 MB cache).</li>
 * </ul>
 * Every level paints the same ground the same way: land cover as ChunkGen lays it (the coarse cover under a jittered
 * point, the coast and lakes from the coarse water with the same wobble), the colour of a tile kind as MapPainter
 * gives it, rivers at their width, roads and railways from the graph by kind, sites, facility blocks and national
 * buildings, and each lattice block filled with roofs and yards as its district type and density build it. So from
 * the country to the streets the colours stay the same and only the detail grows.
 */
public final class NationPainter {
    /** Where the painter gets a real-scale map: the coarse layer, and the chunks already made. */
    public interface Source {
        Nation nation();

        /** The chunk if it's in memory, else null. Never generates on the calling thread. */
        Chunk chunk(int cx, int cy);

        /** Asks for the chunk to be made in the background (for drawing only: it never changes the simulation). */
        void want(int cx, int cy);
    }

    /** A source over a chunk cache: drawing peeks, and missing chunks go to the cache's background thread. */
    public static Source source(final Nation n, final ChunkCache cache) {
        return new Source() {
            public Nation nation() {
                return n;
            }

            public Chunk chunk(int cx, int cy) {
                if (cx < 0 || cy < 0 || cx >= n.sw || cy >= n.sw) return null;
                return cache.peek(cx, cy);
            }

            public void want(int cx, int cy) {
                cache.prefetch(cx, cy);
            }
        };
    }

    /** The zoom from which district pictures take over from the country picture, and street chunks from them. */
    public static final float Z_DISTRICT = 0.012f, Z_STREET = 0.2f;
    /** District pictures' side in tiles, and their levels: tiles per pixel, and the zoom from which each is used. */
    public static final int DISTRICT = 512;
    public static final int[] DTPP = {8, 4, 2};
    public static final float[] DFROM = {0, 0.02f, 0.045f};
    private static final long BUDGET_BYTES = 16L << 20;

    public final Source src;
    public final Nation n;
    private final Platform pf;
    private final long seed;
    /** The country picture (cells per pixel: countryStep) and the density tint (a pixel a sector). */
    public final GImage countryImage, tintImage;
    public final int countryStep;
    /** Districts per side. */
    public final int dw;

    // the coarse water as chunks have it: 1 water at each cell (and 1 deep), the dry cover under river corridors
    private final byte[] kw, kd, dry;
    /** Highway edges, long country roads (avenues between towns) and railway edges, for the vectors. */
    final int[] highways, countryRoads;
    private final ChunkGen.Index ix;
    /** The average colour of each land cover as tiles (ChunkGen's landTile, MapPainter's GROUND). */
    final int[] AVG = new int[11];
    private final LinkedHashMap<Integer, GImage> cache = new LinkedHashMap<Integer, GImage>(128, 0.75f, true);
    private long cacheBytes;
    private final GImage[] spare = new GImage[DTPP.length];
    /** District pictures painted, and the time spent painting them (ns), for probes. */
    public int painted;
    public long paintNs;

    public NationPainter(Source src, Platform pf) {
        this.src = src;
        this.pf = pf;
        n = src.nation();
        seed = n.seed;
        ix = ChunkGen.index(n);
        dw = (n.size + DISTRICT - 1) / DISTRICT;
        int cells = n.cw * n.cw;
        kw = new byte[cells];
        kd = new byte[cells];
        dry = new byte[cells];
        coarseWater();
        averages();
        // vectors
        RoadGraph g = n.roads;
        int nh = 0, nc = 0;
        for (int e = 0; e < g.edgeCount; e++) {
            if (g.ekind[e] == RoadGraph.HIGHWAY) nh++;
            else if (countryRoad(g, e)) nc++;
        }
        highways = new int[nh];
        countryRoads = new int[nc];
        nh = nc = 0;
        for (int e = 0; e < g.edgeCount; e++) {
            if (g.ekind[e] == RoadGraph.HIGHWAY) highways[nh++] = e;
            else if (countryRoad(g, e)) countryRoads[nc++] = e;
        }
        // the country picture: one pixel per cell where memory allows (1,792 on the Country map), else per two
        countryStep = pf.maxMemory() >= (768L << 20) ? 1 : 2;
        int side = n.cw / countryStep;
        int[] px = countryPixels(side);
        countryImage = pf.newImage(side, side);
        countryImage.setPixels(px, side, side);
        int[] tp = tintPixels();
        tintImage = pf.newImage(n.sw, n.sw);
        tintImage.setPixels(tp, n.sw, n.sw);
    }

    /** An avenue long enough to be a road between places rather than a town's street. */
    private static boolean countryRoad(RoadGraph g, int e) {
        return g.ekind[e] == RoadGraph.AVENUE && g.elen[e] > 70 * 16;
    }

    // ------------------------------------------------------------------ the coarse water

    /**
     * The water at each coarse cell as ChunkGen.terrain() makes it: a wet cell along a river (the river's corridor) is
     * land again unless open water lies within two cells; under it, the cover of a dry neighbour.
     */
    private void coarseWater() {
        int cw = n.cw, cells = cw * cw;
        boolean[] corr = new boolean[cells];
        for (int r = 0; r < n.rivers.size(); r++) {
            float[] p = n.rivers.get(r);
            float lim = n.riverWidth.get(r) / 2 + 4 + 16;
            for (int i = 0; i + 3 < p.length; i += 2) {
                float ax = p[i], ay = p[i + 1], bx = p[i + 2], by = p[i + 3];
                int x0 = Math.max(0, (int) ((Math.min(ax, bx) - lim) / 16) - 1), x1 = Math.min(cw - 1, (int) ((Math.max(ax, bx) + lim) / 16) + 1);
                int y0 = Math.max(0, (int) ((Math.min(ay, by) - lim) / 16) - 1), y1 = Math.min(cw - 1, (int) ((Math.max(ay, by) + lim) / 16) + 1);
                for (int y = y0; y <= y1; y++)
                    for (int x = x0; x <= x1; x++) {
                        int q = y * cw + x;
                        if (!corr[q] && Nation.wet(n.cover[q]) && segDist2(x * 16 + 8, y * 16 + 8, ax, ay, bx, by) < lim * lim) corr[q] = true;
                    }
            }
        }
        boolean[] sea = new boolean[cells];
        for (int q = 0; q < cells; q++) sea[q] = Nation.wet(n.cover[q]) && (n.cover[q] == Nation.C_DEEP || !corr[q]);
        for (int y = 0; y < cw; y++)
            for (int x = 0; x < cw; x++) {
                int q = y * cw + x;
                int c = n.cover[q];
                boolean wet = Nation.wet(c), keep = sea[q];
                if (wet && !keep)
                    for (int dj = -2; dj <= 2 && !keep; dj++)
                        for (int di = -2; di <= 2 && !keep; di++) {
                            int ii = x + di, jj = y + dj;
                            if (ii >= 0 && jj >= 0 && ii < cw && jj < cw && sea[jj * cw + ii]) keep = true;
                        }
                kw[q] = (byte) (keep ? 1 : 0);
                kd[q] = (byte) (keep && c == Nation.C_DEEP ? 1 : 0);
                byte d = (byte) c;
                if (wet) {
                    d = Nation.C_GRASS;
                    for (int dj = -1; dj <= 1; dj++)
                        for (int di = -1; di <= 1; di++) {
                            int ii = x + di, jj = y + dj;
                            if (ii < 0 || jj < 0 || ii >= cw || jj >= cw || Nation.wet(n.cover[jj * cw + ii])) continue;
                            int k2 = n.cover[jj * cw + ii];
                            if (k2 != Nation.C_ROCK && d == Nation.C_GRASS) d = (byte) k2;
                        }
                }
                dry[q] = d;
            }
    }

    private float kAt(byte[] a, int ci, int cj) {
        int cw = n.cw;
        ci = ci < 0 ? 0 : ci >= cw ? cw - 1 : ci;
        cj = cj < 0 ? 0 : cj >= cw ? cw - 1 : cj;
        return a[cj * cw + ci];
    }

    private int dryAt(float tx, float ty) {
        int ci = (int) Math.floor(tx / 16), cj = (int) Math.floor(ty / 16), cw = n.cw;
        ci = ci < 0 ? 0 : ci >= cw ? cw - 1 : ci;
        cj = cj < 0 ? 0 : cj >= cw ? cw - 1 : cj;
        return dry[cj * cw + ci];
    }

    static float segDist2(float x, float y, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        float u = l2 <= 0 ? 0 : ((x - ax) * dx + (y - ay) * dy) / l2;
        u = u < 0 ? 0 : u > 1 ? 1 : u;
        float px = ax + dx * u - x, py = ay + dy * u - y;
        return px * px + py * py;
    }

    // ------------------------------------------------------------------ tiles as ChunkGen lays the land

    /** The tile kind ChunkGen puts on land of cover k at (tx, ty) (its landTile; the tree clumps' noise given). */
    private int landTile(int k, int tx, int ty, float clump) {
        switch (k) {
            case Nation.C_SAND: return Tile.SAND;
            case Nation.C_FOREST: return Rng.unit(seed, tx, ty ^ 0x3F00000) < 0.04f ? Tile.GRASS : Tile.FOREST;
            case Nation.C_ROCK: return Tile.ROCK;
            case Nation.C_PARK: return Rng.unit(seed ^ 0x7EE, tx, ty) < 0.22f * (1 + clump * 2.2f) ? Tile.TREE : Tile.PARK;
            case Nation.C_FARM: {
                long fs = seed ^ 0xF1E1D5L;
                int row = fdiv(ty, 14), off = Rng.hashInt(fs, row, 0x51, 22), col = fdiv(tx + off, 22);
                boolean vEdge = fmod(tx + off, 22) == 0, hEdge = fmod(ty, 14) == 0;
                if (vEdge || hEdge) {
                    long h = Rng.hash(fs, col * 3 + (vEdge ? 1 : 2), row);
                    return (h & 3) == 0 ? Tile.GRASS : (h & 3) == 1 ? Tile.TREE : Tile.FARM;
                }
                return (Rng.hash(fs, col, row) & 7) == 0 ? Tile.GRASS : Tile.FARM;
            }
            case Nation.C_GRASS: return Rng.unit(seed ^ 0x7EE, tx, ty) < 0.05f * (1 + clump * 2.2f) ? Tile.TREE : Tile.GRASS;
            default: return Tile.GRASS;
        }
    }

    /** Each land cover's average colour, sampled over a patch of tiles. */
    private void averages() {
        for (int k = 0; k < AVG.length; k++) {
            if (Nation.wet(k)) {
                AVG[k] = k == Nation.C_DEEP ? WATER_DEEP : WATER_OPEN;
                continue;
            }
            long r = 0, g = 0, b = 0;
            int cnt = 0;
            for (int ty = 0; ty < 96; ty++)
                for (int tx = 0; tx < 96; tx++) {
                    int c = MapPainter.GROUND[landTile(k, tx * 7 + 3, ty * 5 + 1, 0)];
                    r += (c >> 16) & 255;
                    g += (c >> 8) & 255;
                    b += c & 255;
                    cnt++;
                }
            AVG[k] = 0xFF000000 | (int) (r / cnt) << 16 | (int) (g / cnt) << 8 | (int) (b / cnt);
        }
    }

    // the water's colours as MapPainter's ground has them away from the shore
    static final int WATER_OPEN = MapPainter.waterColorAt(6), WATER_DEEP = MapPainter.waterColorAt(8.5f), RIVER = MapPainter.waterColorAt(2.6f);

    // ------------------------------------------------------------------ blocks, as their district builds them

    // roof palettes (ChunkGen.roof's)
    private static final int[] P_HOUSE = {0xFF9A5B44, 0xFF7E5A4A, 0xFF6B6F78, 0xFF8C4A3C, 0xFF5E5A56, 0xFFA0785A};
    private static final int[] P_APT = {0xFF8A8580, 0xFF7C7670, 0xFF9C948A, 0xFF6E6A66, 0xFFA08A78};
    private static final int[] P_OFFICE = {0xFF7A8088, 0xFF8C9298, 0xFF6C737C, 0xFF9AA0A6};
    private static final int[] P_TOWER = {0xFF5E6A78, 0xFF6E7A88, 0xFF4E5866, 0xFF80909E};
    private static final int[] P_SHOP = {0xFFA89A84, 0xFF9C8C74, 0xFFB4A48A};
    private static final int[] P_SHED = {0xFF8E9296, 0xFF7E8488, 0xFF9AA0A0, 0xFF8A8270};
    private static final int P_SCHOOL = 0xFFA07A5A, P_CHURCH = 0xFF7A6A5A;

    private static int pick(int[] pal, long h) {
        return pal[(int) ((h >>> 33) % pal.length)];
    }

    /** A roof tile: the roof's colour, darker on the building's edge (as MapPainter paints a roof seen from far). */
    private static int roofTile(int c, boolean edge) {
        return edge ? MapPainter.shade(c, 0.8f) : c;
    }

    /**
     * The colour of tile (tx, ty) inside block (x0, y0)-(x1, y1) of a sector of district type d and density dn (0..1):
     * the layout each district's fillBlock makes (a sidewalk, then plazas and towers, perimeter flats round courtyards,
     * houses in gardens, sheds on yards...), from the block's own hash, so it's stable and cheap but not the chunk's
     * exact buildings.
     */
    int blockTile(int d, float dn, boolean city, boolean capital, int x0, int y0, int x1, int y1, int tx, int ty) {
        return blockTile(d, dn, city, capital, x0, y0, x1, y1, tx, ty, Rng.hash(seed ^ 0xB10CL, x0, y0));
    }

    /** The same, with the block's hash (Rng.hash(seed ^ 0xB10C, x0, y0)) given. */
    int blockTile(int d, float dn, boolean city, boolean capital, int x0, int y0, int x1, int y1, int tx, int ty, long h) {
        if (tx == x0 || tx == x1 || ty == y0 || ty == y1) return MapPainter.GROUND[d == Nation.D_RURAL ? Tile.GRASS : Tile.PAVEMENT];
        int u = tx - x0 - 1, v = ty - y0 - 1, iw = x1 - x0 - 1, ih = y1 - y0 - 1;
        boolean wide = iw >= ih;
        int along = wide ? u : v, across = wide ? v : u, len = wide ? iw : ih, dep = wide ? ih : iw;
        switch (d) {
            case Nation.D_CORE: {
                if ((h & 31) == 0) return Rng.unit(seed, tx, ty) < 0.25f ? MapPainter.GROUND[Tile.TREE] : MapPainter.GROUND[Tile.PLAZA];
                if (((h >>> 5) & 255) < (0.12f + 0.12f * dn) * 256) { // one building on the block
                    boolean tower = ((h >>> 13) & 255) < (0.5f + 0.4f * dn) * 256;
                    int in = (h >>> 21 & 1) == 0 ? 1 : 0;
                    if (u < in || v < in || u >= iw - in || v >= ih - in) return MapPainter.GROUND[Tile.PLAZA];
                    return roofTile(pick(tower ? P_TOWER : P_OFFICE, h), u == in || v == in || u == iw - 1 - in || v == ih - 1 - in);
                }
                int lw = 4 + (int) (h >>> 22 & 1), lu = u / (lw + 1), lv = v / (lw + 1), pu = u % (lw + 1), pv = v % (lw + 1);
                if (pu == lw || pv == lw) return MapPainter.GROUND[Tile.PLAZA];
                long lh = Rng.hash(h, lu, lv);
                if ((lh & 15) == 0) return MapPainter.GROUND[Tile.PLAZA];
                boolean tower = ((lh >>> 4) & 255) < (0.3f + 0.45f * dn) * 256 * (capital ? 1.2f : city ? 1 : 0.5f);
                int c = pick(tower ? P_TOWER : (lh >>> 12 & 3) == 0 ? P_SHOP : P_OFFICE, lh);
                return roofTile(c, pu == 0 || pv == 0 || pu == lw - 1 || pv == lw - 1 || u == iw - 1 || v == ih - 1);
            }
            case Nation.D_DENSE: {
                if (iw < 9 || ih < 9) return mixedTile(dn, city, h, u, v, iw, ih, tx, ty);
                int dp = 3 + (int) (h & 1);
                boolean ring = u < dp || v < dp || u >= iw - dp || v >= ih - dp;
                if (!ring) return Rng.unit(seed, tx, ty) < 0.08f ? MapPainter.GROUND[Tile.TREE] : MapPainter.GROUND[Tile.YARD];
                // flats along the street, a building every 4-8 tiles
                boolean horiz = v < dp || v >= ih - dp;
                int a = horiz ? u : v, seg = a / 6;
                long sh = Rng.hash(h, seg, horiz ? (v < dp ? 1 : 2) : (u < dp ? 3 : 4));
                int c = pick((sh & 7) < 1 ? P_SHOP : (sh & 7) < 2 ? P_OFFICE : P_APT, sh);
                boolean edge = a % 6 == 0 || a % 6 == 5 || u == 0 || v == 0 || u == iw - 1 || v == ih - 1
                        || (horiz ? (v == dp - 1 || v == ih - dp) : (u == dp - 1 || u == iw - dp));
                return roofTile(c, edge);
            }
            case Nation.D_MIXED:
                return mixedTile(dn, city, h, u, v, iw, ih, tx, ty);
            case Nation.D_SUBURB: {
                if ((h & 31) == 1) return Rng.unit(seed, tx, ty) < 0.1f ? MapPainter.GROUND[Tile.TREE] : MapPainter.GROUND[Tile.PARK];
                int lotW = dn > 0.6f ? 5 : 6;
                int rows = dep >= 9 ? 2 : 1, hd = 3 + (int) (h >>> 8 & 1);
                if (dep >= 19) {
                    // a lane down the middle, houses both sides
                    int mid = dep / 2;
                    if (across == mid) return MapPainter.GROUND[Tile.PAVEMENT];
                    if (across > mid) across -= mid + 1;
                    dep = across > mid ? dep - mid - 1 : mid;
                    rows = dep >= 9 ? 2 : 1;
                }
                int p = along % lotW, lot = along / lotW;
                long lh = Rng.hash(h, lot, across < dep / 2 ? 0 : 1);
                int hw = Math.min(lotW - 1, 3 + (int) (lh & 1)), off = (lotW - 1 - hw) / 2;
                boolean inRow = across >= 1 && across < 1 + hd || rows == 2 && across >= dep - 1 - hd && across < dep - 1;
                if (inRow && p >= off && p < off + hw && along + lotW - p <= len + 1)
                    return roofTile(pick(P_HOUSE, lh), p == off || p == off + hw - 1 || across == 1 || across == hd || across == dep - 1 - hd || across == dep - 2);
                return Rng.unit(seed, tx, ty) < 0.05f ? MapPainter.GROUND[Tile.TREE] : MapPainter.GROUND[Tile.YARD];
            }
            case Nation.D_INDUSTRIAL: {
                int lw = 8 + (int) (h & 3), lu = u / (lw + 1), lv = v / (lw + 1), pu = u % (lw + 1), pv = v % (lw + 1);
                if (pu == lw || pv == lw) return MapPainter.GROUND[Tile.LOT];
                long lh = Rng.hash(h, lu, lv);
                int k = (int) (lh & 15);
                if (k < 2) return MapPainter.GROUND[Tile.LOT];
                int apron = 2 + (int) (lh >>> 4 & 1);
                boolean vert = (lh >>> 5 & 1) == 0;
                int lot = Math.min(lw, (vert ? ih - lv * (lw + 1) : iw - lu * (lw + 1)));
                if ((vert ? pv : pu) >= lot - apron) return MapPainter.GROUND[Tile.LOT];
                int c = k < 4 ? pick(P_OFFICE, lh) : pick(P_SHED, lh);
                return roofTile(c, pu == 0 || pv == 0 || pu == lw - 1 || pv == lw - 1 || (vert ? pv == lot - apron - 1 : pu == lot - apron - 1));
            }
            case Nation.D_PARK: {
                int mx = iw / 2 + (int) (h & 3) - 1, my = ih / 2 + (int) (h >>> 2 & 3) - 1;
                if (u == mx || v == my) return MapPainter.GROUND[Tile.PLAZA];
                return Rng.unit(seed ^ 0x7EE, tx, ty) < 0.16f ? MapPainter.GROUND[Tile.TREE] : MapPainter.GROUND[Tile.PARK];
            }
            case Nation.D_CAMPUS: {
                int lw = 10 + (int) (h & 3), lu = u / (lw + 1), lv = v / (lw + 1), pu = u % (lw + 1), pv = v % (lw + 1);
                long lh = Rng.hash(h, lu, lv);
                int k = (int) (lh & 15);
                if (pu > 0 && pv > 0 && pu < lw - 1 && pv < lw - 1) {
                    if (k < 3) return MapPainter.GROUND[Tile.LOT];
                    if (k >= 5) return roofTile((lh >>> 8 & 1) == 0 ? P_SCHOOL : pick(P_OFFICE, lh), pu == 1 || pv == 1 || pu == lw - 2 || pv == lw - 2);
                }
                return Rng.unit(seed, tx, ty) < 0.08f ? MapPainter.GROUND[Tile.TREE] : MapPainter.GROUND[Tile.PARK];
            }
            default: { // the fringe: houses on big plots among fields
                if ((h & 7) == 0) return MapPainter.GROUND[Tile.FARM];
                int sp = 8, p = along % sp, lot = along / sp;
                long lh = Rng.hash(h, lot, across < dep / 2 ? 0 : 1);
                int rows = dep >= 12 ? 2 : 1;
                boolean row = across >= 0 && across <= 5 || rows == 2 && across >= dep - 6;
                if (row && (lh & 3) != 0 && p <= 5 && along + sp - p <= len + 2) {
                    boolean house = p >= 1 && p <= 4 && (across >= 1 && across <= 4 || across >= dep - 5 && across <= dep - 2);
                    if (house) return roofTile(pick(P_HOUSE, lh), p == 1 || p == 4 || across == 1 || across == 4 || across == dep - 5 || across == dep - 2);
                    return MapPainter.GROUND[Tile.YARD];
                }
                return Rng.unit(seed, tx, ty) < 0.06f ? MapPainter.GROUND[Tile.TREE] : MapPainter.GROUND[Tile.GRASS];
            }
        }
    }

    private int mixedTile(float dn, boolean city, long h, int u, int v, int iw, int ih, int tx, int ty) {
        int lw = 4, lu = u / (lw + 1), lv = v / (lw + 1), pu = u % (lw + 1), pv = v % (lw + 1);
        if (pu == lw || pv == lw) return MapPainter.GROUND[Tile.PAVEMENT];
        long lh = Rng.hash(h, lu, lv);
        int k = (int) (lh & 31);
        if (k < 2) return MapPainter.GROUND[Tile.LOT];
        int c = k < 3 ? P_CHURCH : k < (city ? 12 : 16) ? pick(P_SHOP, lh) : k < (city ? 16 : 19) ? pick(P_OFFICE, lh) : pick(P_APT, lh);
        return roofTile(c, pu == 0 || pv == 0 || pu == lw - 1 || pv == lw - 1 || u == iw - 1 || v == ih - 1);
    }

    /** Settlement flags for a sector's blocks. */
    private boolean cityOf(int s) {
        int set = n.secSettlement[s];
        return set >= 0 && n.settlements.get(set).kind == Region.S_CITY;
    }

    private boolean capitalOf(int s) {
        int set = n.secSettlement[s];
        return set >= 0 && n.settlements.get(set).capital;
    }

    /**
     * The average colour of a built sector seen from far: its streets (the share of tiles the kept lattice lines
     * cover) and its blocks (sampled).
     */
    private int sectorColour(int s) {
        int[] xs = n.lineOffsets(s, true), ys = n.lineOffsets(s, false);
        int X0 = (s % n.sw) * Nation.SECT, Y0 = (s / n.sw) * Nation.SECT;
        int d = n.district[s];
        float dn = (n.density[s] & 15) / 15f;
        boolean city = cityOf(s), cap = capitalOf(s);
        long r = 0, g = 0, b = 0;
        int cnt = 0;
        for (int j = 0; j + 1 < ys.length; j++)
            for (int i = 0; i + 1 < xs.length; i++) {
                int[] bl = Nation.block(X0, Y0, xs[i], xs[i + 1], ys[j], ys[j + 1]);
                if (bl[2] - bl[0] < 2 || bl[3] - bl[1] < 2) continue;
                int area = (bl[2] - bl[0] + 1) * (bl[3] - bl[1] + 1), k = Math.max(4, area / 24);
                for (int q = 0; q < k; q++) {
                    int tx = bl[0] + Rng.hashInt(seed, s * 64 + q, j * 8 + i, bl[2] - bl[0] + 1);
                    int ty = bl[1] + Rng.hashInt(seed + 1, s * 64 + q, j * 8 + i, bl[3] - bl[1] + 1);
                    int c = blockTile(d, dn, city, cap, bl[0], bl[1], bl[2], bl[3], tx, ty);
                    int wgt = area / k;
                    r += ((c >> 16) & 255) * wgt;
                    g += ((c >> 8) & 255) * wgt;
                    b += (c & 255) * wgt;
                    cnt += wgt;
                }
            }
        int blocks = cnt;
        // the streets: every tile not in a block
        int streets = Math.max(0, Nation.SECT * Nation.SECT - blocks);
        int rc = MapPainter.GROUND[Tile.ROAD];
        r += ((rc >> 16) & 255) * (long) streets;
        g += ((rc >> 8) & 255) * (long) streets;
        b += (rc & 255) * (long) streets;
        cnt += streets;
        if (cnt == 0) return MapPainter.GROUND[Tile.PAVEMENT];
        return 0xFF000000 | (int) (r / cnt) << 16 | (int) (g / cnt) << 8 | (int) (b / cnt);
    }

    // ------------------------------------------------------------------ the country picture and the tint

    private int[] secColour;

    /** Each cell's colour: its water or land cover, a built sector's streets and blocks, a site's ground. */
    private int[] cellColours() {
        int cw = n.cw, cells = cw * cw;
        int[] col = new int[cells];
        secColour = new int[n.sw * n.sw];
        for (int s = 0; s < secColour.length; s++) if (n.built(s)) secColour[s] = sectorColour(s);
        for (int y = 0; y < cw; y++)
            for (int x = 0; x < cw; x++) {
                int q = y * cw + x;
                int c;
                if (kw[q] != 0) {
                    // lighter along the shore
                    boolean shore = kAt(kw, x - 1, y) == 0 || kAt(kw, x + 1, y) == 0 || kAt(kw, x, y - 1) == 0 || kAt(kw, x, y + 1) == 0;
                    c = shore ? MapPainter.waterColorAt(3.2f) : kd[q] != 0 ? WATER_DEEP : WATER_OPEN;
                } else {
                    int s = (y / 4) * n.sw + x / 4;
                    c = secColour[s] != 0 ? secColour[s] : AVG[dry[q]];
                }
                col[q] = c;
            }
        // sites: their painted rectangles, by the share of each cell they cover
        for (int o = 0; o < n.siteOpCount; o++) {
            int k = o * Nation.OP_INTS, mode = n.siteOps[k + 1];
            if (mode == Nation.OP_OUTLINE) continue;
            int x0 = n.siteOps[k + 2], y0 = n.siteOps[k + 3], x1 = n.siteOps[k + 4], y1 = n.siteOps[k + 5];
            int c = MapPainter.GROUND[n.siteOps[k + 6]];
            for (int cy = Math.max(0, y0 / 16); cy <= Math.min(cw - 1, y1 / 16); cy++)
                for (int cx = Math.max(0, x0 / 16); cx <= Math.min(cw - 1, x1 / 16); cx++) {
                    int q = cy * cw + cx;
                    boolean wet = kw[q] != 0;
                    if (mode == Nation.OP_LAND && wet || mode == Nation.OP_WATER && !wet) continue;
                    int ox = Math.min(x1, cx * 16 + 15) - Math.max(x0, cx * 16) + 1, oy = Math.min(y1, cy * 16 + 15) - Math.max(y0, cy * 16) + 1;
                    if (ox > 0 && oy > 0) col[q] = MapPainter.mix(col[q], c, ox * oy / 256f);
                }
        }
        for (int b = 0; b < n.bCount; b++) {
            int x0 = n.bx[b], y0 = n.by[b], x1 = x0 + n.bw[b] - 1, y1 = y0 + n.bh[b] - 1;
            for (int cy = Math.max(0, y0 / 16); cy <= Math.min(cw - 1, y1 / 16); cy++)
                for (int cx = Math.max(0, x0 / 16); cx <= Math.min(cw - 1, x1 / 16); cx++) {
                    int ox = Math.min(x1, cx * 16 + 15) - Math.max(x0, cx * 16) + 1, oy = Math.min(y1, cy * 16 + 15) - Math.max(y0, cy * 16) + 1;
                    if (ox > 0 && oy > 0) col[cy * cw + cx] = MapPainter.mix(col[cy * cw + cx], n.broof[b], ox * oy / 256f * 0.9f);
                }
        }
        return col;
    }

    private int[] countryPixels(int side) {
        int[] col = cellColours();
        if (side == n.cw) return col;
        return downsample(col, n.cw, side);
    }

    private static int[] downsample(int[] col, int cw, int side) {
        int[] out = new int[side * side];
        float f = cw / (float) side;
        for (int y = 0; y < side; y++)
            for (int x = 0; x < side; x++) {
                int x0 = (int) (x * f), x1 = Math.max(x0 + 1, (int) ((x + 1) * f)), y0 = (int) (y * f), y1 = Math.max(y0 + 1, (int) ((y + 1) * f));
                int r = 0, g = 0, b = 0, k = 0;
                for (int yy = y0; yy < y1 && yy < cw; yy++)
                    for (int xx = x0; xx < x1 && xx < cw; xx++) {
                        int c = col[yy * cw + xx];
                        r += (c >> 16) & 255;
                        g += (c >> 8) & 255;
                        b += c & 255;
                        k++;
                    }
                out[y * side + x] = k == 0 ? 0xFF000000 : 0xFF000000 | (r / k) << 16 | (g / k) << 8 | (b / k);
            }
        return out;
    }

    /** The tint: built sectors warm and darker the denser, so towns and the capital's core read from far out. */
    private int[] tintPixels() {
        int[] px = new int[n.sw * n.sw];
        for (int s = 0; s < px.length; s++) {
            if (!n.built(s)) continue;
            float dn = (n.density[s] & 15) / 15f;
            int d = n.district[s];
            int c = d == Nation.D_PARK ? 0xFF5E8A4E : d == Nation.D_INDUSTRIAL ? 0xFF8A8C96 : MapPainter.mix(0xFFC8BCA4, 0xFF6E5C58, dn);
            float a = d == Nation.D_PARK ? 0.15f : 0.12f + 0.3f * dn;
            px[s] = MapPainter.alpha(c, a);
        }
        return px;
    }

    /** The New Game preview of a nation: side x side pixels from the coarse layer only (cells, sites, rivers). */
    public static int[] preview(Nation n, int side) {
        // the cells' colours as the country picture has them, at a pixel per cell, then scaled down
        NationPainter p = new NationPainter(n);
        int[] col = p.cellColours();
        // rivers over them, a cell wide at least
        int cw = n.cw;
        for (int r = 0; r < n.rivers.size(); r++) {
            float[] pts = n.rivers.get(r);
            float h = Math.max(8, n.riverWidth.get(r) / 2);
            for (int i = 0; i + 3 < pts.length; i += 2) {
                float ax = pts[i], ay = pts[i + 1], bx = pts[i + 2], by = pts[i + 3];
                int x0 = Math.max(0, (int) ((Math.min(ax, bx) - h) / 16)), x1 = Math.min(cw - 1, (int) ((Math.max(ax, bx) + h) / 16));
                int y0 = Math.max(0, (int) ((Math.min(ay, by) - h) / 16)), y1 = Math.min(cw - 1, (int) ((Math.max(ay, by) + h) / 16));
                for (int y = y0; y <= y1; y++)
                    for (int x = x0; x <= x1; x++)
                        if (segDist2(x * 16 + 8, y * 16 + 8, ax, ay, bx, by) <= h * h) col[y * cw + x] = RIVER;
            }
        }
        return downsample(col, cw, side);
    }

    /** For the preview only: the coarse water and averages, no pictures. */
    private NationPainter(Nation n) {
        this.n = n;
        src = null;
        pf = null;
        seed = n.seed;
        ix = ChunkGen.index(n);
        dw = (n.size + DISTRICT - 1) / DISTRICT;
        int cells = n.cw * n.cw;
        kw = new byte[cells];
        kd = new byte[cells];
        dry = new byte[cells];
        coarseWater();
        averages();
        highways = countryRoads = new int[0];
        countryImage = tintImage = null;
        countryStep = 1;
    }

    // ------------------------------------------------------------------ district pictures

    private static int key(int dx, int dy, int lvl) {
        return lvl << 28 | dy << 14 | dx;
    }

    private static long bytes(int lvl) {
        long s = DISTRICT / DTPP[lvl];
        return s * s * 4;
    }

    /** The level of district picture for a zoom. */
    public static int levelFor(float zoom) {
        int l = 0;
        for (int i = 1; i < DFROM.length; i++) if (zoom >= DFROM[i]) l = i;
        return l;
    }

    public boolean has(int dx, int dy, int lvl) {
        return cache.containsKey(key(dx, dy, lvl));
    }

    /** A district's picture at a level, painting it now if allowed (else null when it isn't cached). */
    public GImage district(int dx, int dy, int lvl, boolean mayPaint) {
        int k = key(dx, dy, lvl);
        GImage img = cache.get(k);
        if (img != null || !mayPaint) return img;
        int side = DISTRICT / DTPP[lvl];
        long t0 = System.nanoTime();
        int[] px = paint(dx * DISTRICT, dy * DISTRICT, DTPP[lvl], side);
        img = spare[lvl];
        spare[lvl] = null;
        if (img == null) img = pf.newImage(side, side);
        img.setPixels(px, side, side);
        paintNs += System.nanoTime() - t0;
        painted++;
        cache.put(k, img);
        cacheBytes += bytes(lvl);
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

    public long cacheBytes() {
        return cacheBytes;
    }

    public void clear() {
        for (GImage i : cache.values()) i.recycle();
        cache.clear();
        cacheBytes = 0;
        for (int l = 0; l < spare.length; l++) {
            if (spare[l] != null) spare[l].recycle();
            spare[l] = null;
        }
        if (countryImage != null) countryImage.recycle();
        if (tintImage != null) tintImage.recycle();
    }

    // the scratch arrays for painting (one picture at a time, on the drawing thread)
    private int[] out;
    private float[] wet, dist, wobL, jxL, jyL, trL;
    private byte[] deep, hard;

    /**
     * Paints the side x side picture whose top-left tile is (X0, Y0), at tpp tiles per pixel: the ground and water,
     * rivers, the blocks, sites, facility blocks, roads and railways, and national buildings. Pure: the same district
     * paints the same pixels.
     */
    int[] paint(int X0, int Y0, int tpp, int side) {
        int m = (int) Math.ceil(6f / tpp) + 1; // a margin for the distance from the shore
        int W = side + 2 * m, A = W * W;
        if (out == null || out.length < A) {
            out = new int[A];
            wet = new float[A];
            dist = new float[A];
            deep = new byte[A];
            hard = new byte[A];
        }
        Arrays.fill(wet, 0, A, 0);
        Arrays.fill(hard, 0, A, (byte) 0);
        Arrays.fill(deep, 0, A, (byte) 0);
        float ox = X0 - m * tpp, oy = Y0 - m * tpp; // the margin's top-left, tiles
        // the noise lattice: every 4 tiles (or every pixel when pixels are bigger), bilinear between
        int ls = Math.max(4, tpp), L = (int) Math.ceil(W * tpp / (float) ls) + 2;
        if (wobL == null || wobL.length < L * L) {
            wobL = new float[L * L];
            jxL = new float[L * L];
            jyL = new float[L * L];
            trL = new float[L * L];
        }
        int lx0 = (int) Math.floor(ox / ls), ly0 = (int) Math.floor(oy / ls);
        noiseGrid(ix.wob, 9f, 0, lx0, ly0, ls, L, wobL, 0.6f, false);
        noiseGrid(ix.wob, 3.1f, 40, lx0, ly0, ls, L, wobL, 0.25f, true);
        noiseGrid(ix.jitX, 11f, 0, lx0, ly0, ls, L, jxL, 7, false);
        noiseGrid(ix.jitY, 11f, 0, lx0, ly0, ls, L, jyL, 7, false);
        if (tpp <= 2) noiseGrid(ix.trees, 5f, 0, lx0, ly0, ls, L, trL, 1, false);
        int ns = Math.min(tpp, 2); // tiles sampled across a pixel, each way, for the land
        for (int py = 0; py < W; py++)
            for (int px = 0; px < W; px++) {
                float tx = ox + (px + 0.5f) * tpp, ty = oy + (py + 0.5f) * tpp; // the pixel's middle, tiles
                float gx = tx / ls - lx0, gy = ty / ls - ly0;
                int gi = Math.min(L - 2, Math.max(0, (int) gx)), gj = Math.min(L - 2, Math.max(0, (int) gy));
                float fx = gx - gi, fy = gy - gj;
                int a = gj * L + gi;
                float nw = lerp2(wobL[a], wobL[a + 1], wobL[a + L], wobL[a + L + 1], fx, fy);
                float cxf = tx / 16 - 0.5f, cyf = ty / 16 - 0.5f;
                int ci = (int) Math.floor(cxf), cj = (int) Math.floor(cyf);
                float ax = cxf - ci, ay = cyf - cj;
                float k = lerp2(kAt(kw, ci, cj), kAt(kw, ci + 1, cj), kAt(kw, ci, cj + 1), kAt(kw, ci + 1, cj + 1), ax, ay);
                int i = py * W + px;
                // the share of the pixel under water, from how far the wobbled level is past the shore
                float wv = k + nw * 0.35f - 0.5f;
                float wf = k <= 0 ? 0 : clamp01(wv * 8 / tpp + 0.5f);
                if (wf > 0) {
                    float kdv = lerp2(kAt(kd, ci, cj), kAt(kd, ci + 1, cj), kAt(kd, ci, cj + 1), kAt(kd, ci + 1, cj + 1), ax, ay);
                    if (kdv + nw * 0.35f > 0.5f) deep[i] = 1;
                }
                wet[i] = wf;
                // the land: the cover under a jittered point, its tiles' colours averaged
                float jx = lerp2(jxL[a], jxL[a + 1], jxL[a + L], jxL[a + L + 1], fx, fy);
                float jy = lerp2(jyL[a], jyL[a + 1], jyL[a + L], jyL[a + L + 1], fx, fy);
                int cov = dryAt(tx + jx, ty + jy);
                int col;
                if (tpp <= 2) {
                    float clump = lerp2(trL[a], trL[a + 1], trL[a + L], trL[a + L + 1], fx, fy);
                    int r = 0, g = 0, b = 0;
                    int bx = (int) Math.floor(tx - tpp / 2f), by = (int) Math.floor(ty - tpp / 2f);
                    for (int sy = 0; sy < ns; sy++)
                        for (int sx = 0; sx < ns; sx++) {
                            int c = MapPainter.GROUND[landTile(cov, bx + sx * tpp / ns, by + sy * tpp / ns, clump)];
                            r += (c >> 16) & 255;
                            g += (c >> 8) & 255;
                            b += c & 255;
                        }
                    int q = ns * ns;
                    col = 0xFF000000 | (r / q) << 16 | (g / q) << 8 | (b / q);
                } else col = AVG[cov];
                out[i] = col;
            }
        // rivers at their width (over land; the sea stays sea)
        for (int r = 0; r < n.rivers.size(); r++) {
            float[] p = n.rivers.get(r);
            float h = n.riverWidth.get(r) / 2;
            for (int s = 0; s + 3 < p.length; s += 2) {
                float ax = p[s], ay = p[s + 1], bx = p[s + 2], by = p[s + 3];
                if (Math.max(ax, bx) + h < ox || Math.min(ax, bx) - h > ox + W * tpp || Math.max(ay, by) + h < oy || Math.min(ay, by) - h > oy + W * tpp) continue;
                int x0 = Math.max(0, (int) ((Math.min(ax, bx) - h - ox) / tpp) - 1), x1 = Math.min(W - 1, (int) ((Math.max(ax, bx) + h - ox) / tpp) + 1);
                int y0 = Math.max(0, (int) ((Math.min(ay, by) - h - oy) / tpp) - 1), y1 = Math.min(W - 1, (int) ((Math.max(ay, by) + h - oy) / tpp) + 1);
                for (int y = y0; y <= y1; y++)
                    for (int x = x0; x <= x1; x++) {
                        float d = (float) Math.sqrt(segDist2(ox + (x + 0.5f) * tpp, oy + (y + 0.5f) * tpp, ax, ay, bx, by));
                        float c = clamp01((h - d) / tpp + 0.5f);
                        int i = y * W + x;
                        if (c > wet[i]) wet[i] = c;
                    }
            }
        }
        // the water's colour by its distance from land (as MapPainter shades it), over the land by its share
        final float D = 1.4142f * tpp, big = 1e4f;
        for (int i = 0; i < A; i++) dist[i] = wet[i] >= 0.5f ? big : 0;
        for (int y = 0; y < W; y++)
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                float v = dist[i];
                if (v == 0) continue;
                if (x > 0) v = Math.min(v, dist[i - 1] + tpp);
                if (y > 0) {
                    v = Math.min(v, dist[i - W] + tpp);
                    if (x > 0) v = Math.min(v, dist[i - W - 1] + D);
                    if (x < W - 1) v = Math.min(v, dist[i - W + 1] + D);
                }
                dist[i] = v;
            }
        for (int y = W - 1; y >= 0; y--)
            for (int x = W - 1; x >= 0; x--) {
                int i = y * W + x;
                float v = dist[i];
                if (v == 0) continue;
                if (x < W - 1) v = Math.min(v, dist[i + 1] + tpp);
                if (y < W - 1) {
                    v = Math.min(v, dist[i + W] + tpp);
                    if (x < W - 1) v = Math.min(v, dist[i + W + 1] + D);
                    if (x > 0) v = Math.min(v, dist[i + W - 1] + D);
                }
                dist[i] = v;
            }
        for (int i = 0; i < A; i++) {
            float wf = wet[i];
            if (wf <= 0) continue;
            // as MapPainter: the distance (to the pixel's middle) capped at 6 tiles, deep water 2.5 further
            float d = Math.min(6, Math.max(0.5f, dist[i] - tpp * 0.5f + 0.5f));
            if (deep[i] != 0) d += 2.5f;
            int wc = MapPainter.waterColorAt(d);
            out[i] = wf >= 1 ? wc : MapPainter.mix(out[i], wc, wf);
            if (wf >= 0.5f) hard[i] = 1;
        }
        // the blocks of built sectors
        int sx0 = Math.max(0, (int) Math.floor(ox / Nation.SECT)), sy0 = Math.max(0, (int) Math.floor(oy / Nation.SECT));
        int sx1 = Math.min(n.sw - 1, (int) ((ox + W * tpp) / Nation.SECT)), sy1 = Math.min(n.sw - 1, (int) ((oy + W * tpp) / Nation.SECT));
        int nsb = tpp <= 2 ? tpp : 3; // samples each way in a pixel (jittered in coarse pixels, so no lattice line is favoured)
        for (int sy = sy0; sy <= sy1; sy++)
            for (int sx = sx0; sx <= sx1; sx++) {
                int s = sy * n.sw + sx;
                if (!n.built(s)) continue;
                int d = n.district[s];
                float dn = (n.density[s] & 15) / 15f;
                boolean city = cityOf(s), cap = capitalOf(s);
                int[] xs = n.lineOffsets(s, true), ys = n.lineOffsets(s, false);
                int SX = sx * Nation.SECT, SY = sy * Nation.SECT;
                for (int j = 0; j + 1 < ys.length; j++)
                    for (int i = 0; i + 1 < xs.length; i++) {
                        int[] bl = Nation.block(SX, SY, xs[i], xs[i + 1], ys[j], ys[j + 1]);
                        if (reserved(s, bl)) continue;
                        // ChunkGen leaves blocks under four tiles wide unbuilt: a sidewalk at most
                        boolean tiny = bl[2] - bl[0] < 4 || bl[3] - bl[1] < 4;
                        long bh = Rng.hash(seed ^ 0xB10CL, bl[0], bl[1]);
                        int px0 = Math.max(0, (int) Math.floor((bl[0] - ox) / tpp)), px1 = Math.min(W - 1, (int) Math.floor((bl[2] + 1 - ox - 0.001f) / tpp));
                        int py0 = Math.max(0, (int) Math.floor((bl[1] - oy) / tpp)), py1 = Math.min(W - 1, (int) Math.floor((bl[3] + 1 - oy - 0.001f) / tpp));
                        for (int py = py0; py <= py1; py++)
                            for (int px = px0; px <= px1; px++) {
                                int pi = py * W + px;
                                if (hard[pi] != 0) continue;
                                int r = 0, g = 0, b = 0, in = 0;
                                for (int qy = 0; qy < nsb; qy++)
                                    for (int qx = 0; qx < nsb; qx++) {
                                        int tx, ty;
                                        if (tpp <= 2) {
                                            tx = (int) Math.floor(ox + px * tpp + qx);
                                            ty = (int) Math.floor(oy + py * tpp + qy);
                                        } else {
                                            int gx = (int) Math.floor(ox) + px * tpp, gy = (int) Math.floor(oy) + py * tpp;
                                            long jh = Rng.hash(seed ^ 0x5A3L, gx + qx, gy + qy);
                                            tx = gx + (int) ((qx + ((jh & 1023) / 1024f)) * tpp / nsb);
                                            ty = gy + (int) ((qy + ((jh >>> 10 & 1023) / 1024f)) * tpp / nsb);
                                        }
                                        if (tx < bl[0] || tx > bl[2] || ty < bl[1] || ty > bl[3]) continue;
                                        int c = tiny ? MapPainter.GROUND[Tile.PAVEMENT] : blockTile(d, dn, city, cap, bl[0], bl[1], bl[2], bl[3], tx, ty, bh);
                                        r += (c >> 16) & 255;
                                        g += (c >> 8) & 255;
                                        b += c & 255;
                                        in++;
                                    }
                                if (in == 0) continue;
                                int c = 0xFF000000 | (r / in) << 16 | (g / in) << 8 | (b / in);
                                int q = nsb * nsb;
                                out[pi] = in == q ? c : MapPainter.mix(out[pi], c, in / (float) q);
                            }
                    }
            }
        // the large sites' rectangles
        for (int o = 0; o < n.siteOpCount; o++) {
            int k = o * Nation.OP_INTS;
            int mode = n.siteOps[k + 1], x0 = n.siteOps[k + 2], y0 = n.siteOps[k + 3], x1 = n.siteOps[k + 4], y1 = n.siteOps[k + 5];
            if (x1 < ox || x0 > ox + W * tpp || y1 < oy || y0 > oy + W * tpp) continue;
            int tk = n.siteOps[k + 6], edge = n.siteOps[k + 7];
            if (mode == Nation.OP_OUTLINE || mode == Nation.OP_FRAME) {
                // the rim a tile wide, then (a frame) the inside
                int ec = MapPainter.GROUND[mode == Nation.OP_FRAME ? edge : tk];
                rectT(x0, y0, x1 + 1, y0 + 1, ec, ox, oy, tpp, W, 0);
                rectT(x0, y1, x1 + 1, y1 + 1, ec, ox, oy, tpp, W, 0);
                rectT(x0, y0 + 1, x0 + 1, y1, ec, ox, oy, tpp, W, 0);
                rectT(x1, y0 + 1, x1 + 1, y1, ec, ox, oy, tpp, W, 0);
                if (mode == Nation.OP_FRAME) rectT(x0 + 1, y0 + 1, x1, y1, MapPainter.GROUND[tk], ox, oy, tpp, W, 0);
            } else rectT(x0, y0, x1 + 1, y1 + 1, MapPainter.GROUND[tk], ox, oy, tpp, W, mode == Nation.OP_LAND ? 1 : mode == Nation.OP_WATER ? 2 : 0);
        }
        // facility blocks in towns: a paved rim round a yard (a park's lawn)
        for (Region.Facility f : n.facilities) {
            if (!ChunkGen.latticeFacility(n, f)) continue;
            if (f.bx1 < ox || f.bx0 > ox + W * tpp || f.by1 < oy || f.by0 > oy + W * tpp) continue;
            int inner = f.kind == Region.F_PARK ? MapPainter.mix(MapPainter.GROUND[Tile.PARK], MapPainter.GROUND[Tile.TREE], 0.25f) : MapPainter.GROUND[Tile.LOT];
            rectT(f.bx0, f.by0, f.bx1 + 1, f.by1 + 1, MapPainter.GROUND[Tile.PAVEMENT], ox, oy, tpp, W, 1);
            rectT(f.bx0 + 1, f.by0 + 1, f.bx1, f.by1, inner, ox, oy, tpp, W, 1);
        }
        // roads by kind at their width (bridges over water), from the edges bucketed in the district's chunks
        RoadGraph g = n.roads;
        if (stamp == null || stamp.length < g.edgeCount) stamp = new int[g.edgeCount];
        stampN++;
        int cx0 = Math.max(0, (int) Math.floor(ox / Chunk.SIZE)), cy0 = Math.max(0, (int) Math.floor(oy / Chunk.SIZE));
        int cx1 = Math.min(n.sw - 1, (int) ((ox + W * tpp) / Chunk.SIZE)), cy1 = Math.min(n.sw - 1, (int) ((oy + W * tpp) / Chunk.SIZE));
        boolean buckets = g.chunkEdgeStart != null && g.chunkW == n.sw;
        for (int pass = 0; pass < 2 && buckets; pass++) // the highways' grass verges first, then every road
            for (int cy = cy0; cy <= cy1; cy++)
                for (int cx = cx0; cx <= cx1; cx++) {
                    int c = cy * n.sw + cx;
                    for (int q = g.chunkEdgeStart[c]; q < g.chunkEdgeStart[c + 1]; q++) {
                        int e = g.chunkEdge[q];
                        boolean hw = g.ekind[e] == RoadGraph.HIGHWAY;
                        if (pass == 0) {
                            if (!hw || stamp[e] == -stampN) continue;
                            stamp[e] = -stampN;
                            road(g.nx[g.ea[e]], g.ny[g.ea[e]], g.nx[g.eb[e]], g.ny[g.eb[e]], g.ehalf[e] + 16, MapPainter.GROUND[Tile.GRASS], -1, ox, oy, tpp, W);
                            continue;
                        }
                        if (stamp[e] == stampN) continue;
                        stamp[e] = stampN;
                        int col = MapPainter.GROUND[hw ? Tile.HIGHWAY : Tile.ROAD];
                        road(g.nx[g.ea[e]], g.ny[g.ea[e]], g.nx[g.eb[e]], g.ny[g.eb[e]], g.ehalf[e], col, MapPainter.GROUND[Tile.BRIDGE], ox, oy, tpp, W);
                    }
                }
        // railways
        RoadGraph rl = n.rails;
        for (int e = 0; e < rl.edgeCount; e++) {
            float ax = rl.nx[rl.ea[e]], ay = rl.ny[rl.ea[e]], bx = rl.nx[rl.eb[e]], by = rl.ny[rl.eb[e]];
            if (Math.max(ax, bx) < ox * 16 - 64 || Math.min(ax, bx) > (ox + W * tpp) * 16 + 64 || Math.max(ay, by) < oy * 16 - 64 || Math.min(ay, by) > (oy + W * tpp) * 16 + 64) continue;
            capsule(ax, ay, bx, by, 13, MapPainter.GROUND[Tile.RAIL], MapPainter.GROUND[Tile.BRIDGE], ox, oy, tpp, W);
        }
        // national buildings: their roofs
        for (int b = 0; b < n.bCount; b++) {
            int x0 = n.bx[b], y0 = n.by[b], x1 = x0 + n.bw[b], y1 = y0 + n.bh[b];
            if (x1 < ox || x0 > ox + W * tpp || y1 < oy || y0 > oy + W * tpp) continue;
            int rc = n.broof[b];
            rectT(x0, y0, x1, y1, MapPainter.shade(rc, 0.8f), ox, oy, tpp, W, 0);
            if (n.bw[b] > 2 && n.bh[b] > 2) rectT(x0 + 1, y0 + 1, x1 - 1, y1 - 1, rc, ox, oy, tpp, W, 0);
        }
        // the picture without its margin
        int[] res = new int[side * side];
        for (int y = 0; y < side; y++) System.arraycopy(out, (y + m) * W + m, res, y * side, side);
        return res;
    }

    private float[] corners = new float[64];
    private int[] cxs = new int[64], cys = new int[64];
    private float[] fxs = new float[64], fys = new float[64];

    /**
     * nz.value(X / sc + off, Y / sc) times mul at the grid points X = (lx0 + i) * ls, Y = (ly0 + j) * ls (i, j < L),
     * into out (or added to it), with each of the noise's lattice corners hashed once: the same arithmetic as
     * Noise.value, so the same values.
     */
    private void noiseGrid(Noise nz, float sc, float off, int lx0, int ly0, int ls, int L, float[] out, float mul, boolean add) {
        if (cxs.length < L) {
            cxs = new int[L];
            cys = new int[L];
            fxs = new float[L];
            fys = new float[L];
        }
        for (int i = 0; i < L; i++) {
            float x = (lx0 + i) * ls / sc + off, y = (ly0 + i) * ls / sc;
            cxs[i] = (int) Math.floor(x);
            cys[i] = (int) Math.floor(y);
            fxs[i] = Noise.fade(x - cxs[i]);
            fys[i] = Noise.fade(y - cys[i]);
        }
        int bx = cxs[0], by = cys[0], cw = cxs[L - 1] - bx + 2, ch = cys[L - 1] - by + 2;
        if (corners.length < cw * ch) corners = new float[cw * ch];
        for (int j = 0; j < ch; j++)
            for (int i = 0; i < cw; i++) corners[j * cw + i] = nz.lattice(bx + i, by + j);
        for (int j = 0; j < L; j++) {
            int rj = (cys[j] - by) * cw;
            float fy = fys[j];
            for (int i = 0; i < L; i++) {
                int o = rj + cxs[i] - bx;
                float fx = fxs[i];
                float a = corners[o], b = corners[o + 1], c = corners[o + cw], d = corners[o + cw + 1];
                float top = a + (b - a) * fx, bot = c + (d - c) * fx;
                float v = (top + (bot - top) * fy) * mul;
                if (add) out[j * L + i] += v;
                else out[j * L + i] = v;
            }
        }
    }

    private int[] stamp;
    private int stampN;

    /** Is block bl of sector s a facility's (its ground is painted with the facility)? */
    private boolean reserved(int s, int[] bl) {
        long[] fa = ix.facs;
        long key = (long) s << 32;
        int lo = 0, hi = fa.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (fa[mid] < key) lo = mid + 1;
            else hi = mid;
        }
        for (int p = lo; p < fa.length && (int) (fa[p] >>> 32) == s; p++) {
            Region.Facility f = n.facilities.get((int) fa[p]);
            if (f.bx0 <= bl[2] && f.bx1 >= bl[0] && f.by0 <= bl[3] && f.by1 >= bl[1]) return true;
        }
        return false;
    }

    /** Fills tiles [x0, x1) x [y0, y1) with colour c, each pixel by its covered share; where 1 on land only, 2 on water only. */
    private void rectT(float x0, float y0, float x1, float y1, int c, float ox, float oy, int tpp, int W, int where) {
        float px0 = (x0 - ox) / tpp, px1 = (x1 - ox) / tpp, py0 = (y0 - oy) / tpp, py1 = (y1 - oy) / tpp;
        int ix0 = Math.max(0, (int) Math.floor(px0)), ix1 = Math.min(W - 1, (int) Math.ceil(px1) - 1);
        int iy0 = Math.max(0, (int) Math.floor(py0)), iy1 = Math.min(W - 1, (int) Math.ceil(py1) - 1);
        for (int y = iy0; y <= iy1; y++) {
            float cy = Math.min(y + 1, py1) - Math.max(y, py0);
            if (cy <= 0) continue;
            for (int x = ix0; x <= ix1; x++) {
                float cx = Math.min(x + 1, px1) - Math.max(x, px0);
                if (cx <= 0) continue;
                int i = y * W + x;
                if (where == 1 && wet[i] >= 0.5f || where == 2 && wet[i] < 0.5f) continue;
                float cov = cx * cy;
                out[i] = cov >= 0.999f ? c : MapPainter.mix(out[i], c, cov);
                if (cov >= 0.5f) hard[i] = 1;
            }
        }
    }

    /** A road edge (px), as ChunkGen marks it: along the lattice a band of whole tiles, else a capsule. */
    private void road(float ax, float ay, float bx, float by, float half, int c, int bridge, float ox, float oy, int tpp, int W) {
        if (ax == bx || ay == by) {
            int lh = (int) (half / 16);
            float x0 = (float) Math.floor(Math.min(ax, bx) / 16) - lh, x1 = (float) Math.floor(Math.max(ax, bx) / 16) + lh + 1;
            float y0 = (float) Math.floor(Math.min(ay, by) / 16) - lh, y1 = (float) Math.floor(Math.max(ay, by) / 16) + lh + 1;
            if (bridge == -1) {
                rectT(x0, y0, x1, y1, c, ox, oy, tpp, W, 1);
                return;
            }
            rectT(x0, y0, x1, y1, c, ox, oy, tpp, W, 1);
            rectT(x0, y0, x1, y1, bridge, ox, oy, tpp, W, 2);
            return;
        }
        capsule(ax, ay, bx, by, Math.max(half - 4, 24), c, bridge, ox, oy, tpp, W);
    }

    /** A capsule round segment a-b (px) of radius rad (px), each pixel by how far inside it is; bridge colour over water. */
    private void capsule(float ax, float ay, float bx, float by, float rad, int c, int bridge, float ox, float oy, int tpp, int W) {
        float k = 16f * tpp; // px per picture pixel
        float x0 = (Math.min(ax, bx) - rad) / k - ox / tpp, x1 = (Math.max(ax, bx) + rad) / k - ox / tpp;
        float y0 = (Math.min(ay, by) - rad) / k - oy / tpp, y1 = (Math.max(ay, by) + rad) / k - oy / tpp;
        int ix0 = Math.max(0, (int) Math.floor(x0)), ix1 = Math.min(W - 1, (int) Math.ceil(x1));
        int iy0 = Math.max(0, (int) Math.floor(y0)), iy1 = Math.min(W - 1, (int) Math.ceil(y1));
        float m = rad + k; // the reach of a pixel's coverage
        for (int y = iy0; y <= iy1; y++) {
            // the stretch of this row the band can touch: the segment's x between wy - m and wy + m, widened by m
            float wy = (oy + (y + 0.5f) * tpp) * 16;
            int rx0 = ix0, rx1 = ix1;
            if (Math.abs(by - ay) > 1) {
                float t0 = (wy - m - ay) / (by - ay), t1 = (wy + m - ay) / (by - ay);
                float ta = Math.max(0, Math.min(t0, t1)), tb = Math.min(1, Math.max(t0, t1));
                if (ta > tb) continue;
                float xa = ax + (bx - ax) * ta, xb = ax + (bx - ax) * tb;
                rx0 = Math.max(ix0, (int) Math.floor((Math.min(xa, xb) - m) / k - ox / tpp));
                rx1 = Math.min(ix1, (int) Math.ceil((Math.max(xa, xb) + m) / k - ox / tpp));
            }
            for (int x = rx0; x <= rx1; x++) {
                float wx = (ox + (x + 0.5f) * tpp) * 16;
                float d = (float) Math.sqrt(segDist2(wx, wy, ax, ay, bx, by));
                float cov = clamp01((rad - d) / k + 0.5f);
                if (cov <= 0) continue;
                int i = y * W + x;
                boolean w = wet[i] >= 0.5f;
                if (w && bridge == -1) continue;
                int col = w ? bridge : c;
                out[i] = cov >= 0.999f ? col : MapPainter.mix(out[i], col, cov);
                if (cov >= 0.5f) hard[i] = 1;
            }
        }
    }

    private static float lerp2(float a, float b, float c, float d, float fx, float fy) {
        float top = a + (b - a) * fx, bot = c + (d - c) * fx;
        return top + (bot - top) * fy;
    }

    private static int fdiv(int a, int b) {
        int q = a / b;
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q;
    }

    private static int fmod(int a, int b) {
        return a - fdiv(a, b) * b;
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }
}
