package com.instantaxtion.zcs2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The coarse national layer of a real-scale country, built by NationGen: land cover and height on a 16-tile grid,
 * rivers, settlements with their district rasters and residents per sector, facilities and their national buildings,
 * large sites, the whole road graph (highways and every street of the lattice) and the railways.
 * Nothing here is per tile: tiles, blocks and ordinary buildings come later, chunk by chunk, from this data.
 */
public final class Nation {
    /** Map sides in tiles (a tile is 4 m): a state of 82 km, a country of 115 km, a large country of 147 km. */
    public static final int[] SIZES = {20480, 28672, 36864};
    public static final String[] SIZE_NAMES = {"State", "Country", "Large country"};
    /** Tiles per coarse cell side, per sector (and chunk) side, between streets, avenues and arterials. */
    public static final int CELL = 16, SECT = 64, STREET = 16, AVENUE = 64, ARTERIAL = 192;
    /** Tiles per km. */
    public static final float KM = 250f;

    // land cover, one byte per coarse cell
    public static final byte C_DEEP = 0, C_WATER = 1, C_SAND = 2, C_GRASS = 3, C_FOREST = 4, C_FARM = 5, C_ROCK = 6,
            C_URBAN = 7, C_PARK = 8, C_BASE = 9, C_AIRFIELD = 10;
    public static final String[] COVER_NAMES = {"Sea", "Water", "Sand", "Grass", "Forest", "Farmland", "Rock", "Built-up",
            "Park", "Base", "Airfield"};

    // district types, one byte per sector
    public static final byte D_NONE = 0, D_CORE = 1, D_DENSE = 2, D_MIXED = 3, D_SUBURB = 4, D_INDUSTRIAL = 5, D_PARK = 6,
            D_CAMPUS = 7, D_RURAL = 8;
    public static final String[] DISTRICT_NAMES = {"Countryside", "Centre", "Dense", "Mixed", "Suburb", "Industrial",
            "Park", "Campus", "Fringe"};

    // sector summaries: shares 0..255 of each sector's 16 coarse cells (road: cells a road runs through)
    public static final int SUM_WATER = 0, SUM_DEEP = 1, SUM_FOREST = 2, SUM_ROCK = 3, SUM_ROAD = 4, SUM_FARM = 5, SUM_BUILT = 6, SUMS = 7;

    // site painting operations: mode, then the rectangle in tiles (inclusive), then the tiles
    public static final int OP_FILL = 0, OP_FRAME = 1, OP_OUTLINE = 2, OP_LAND = 3, OP_WATER = 4;
    public static final int OP_INTS = 8; // settlement, mode, x0, y0, x1, y1, tile, edge tile

    /** Height bytes: h = byte / HSCALE - H0 (sea level is h = 0). */
    public static final float H0 = 0.6f, HSCALE = 127.5f;

    public final int size, preset;
    public final long seed;
    /** Coarse cells and sectors per side. */
    public final int cw, sw;
    public final byte[] cover, height;

    /** Per sector: the settlement (or site) it belongs to (-1 none), district type, density class (0-15), kept minor streets. */
    public final short[] secSettlement;
    public final byte[] district, density;
    /**
     * Per sector, which minor lattice streets are kept: bit i (0-2) the north-south street 16 * (i + 1) tiles from the
     * sector's west edge, bit 3 + i the east-west street 16 * (i + 1) tiles from its north edge. Avenues on the sector
     * edges are always there when a neighbouring sector is built.
     */
    public final byte[] lines;
    /** Residents per sector, worked out once here: the authoritative count. */
    public final int[] residents;
    /** Sector summaries, sums[SUM_x][sector]. */
    public final byte[][] sums;

    /** Rivers as polylines in tiles (x0, y0, x1, y1, ...) with their widths in tiles. */
    public final List<float[]> rivers = new ArrayList<float[]>();
    public final List<Float> riverWidth = new ArrayList<Float>();

    public final List<Region.Settlement> settlements = new ArrayList<Region.Settlement>();
    /** Per settlement: its nuclei as (x, y, radius) triples in tiles; the first is the centre. */
    public final List<int[]> nuclei = new ArrayList<int[]>();
    /** Per settlement: the people the generator aimed for (residents in its sectors add up to exactly this). */
    public final List<Integer> targetPop = new ArrayList<Integer>();
    public final List<Region.Facility> facilities = new ArrayList<Region.Facility>();
    public final List<Region.State> states = new ArrayList<Region.State>();
    public int gridCount, waterCount, phoneCount;

    // national buildings (facilities and sites), structure of arrays like Region's; ids 0..bCount-1
    public int bCount;
    public int[] bx = new int[256], by = new int[256], bw = new int[256], bh = new int[256];
    public byte[] bkind = new byte[256], bfloors = new byte[256];
    public short[] bset = new short[256];
    public int[] bres = new int[256], broof = new int[256];

    /** Site painting operations, OP_INTS ints each. */
    public int[] siteOps = new int[256];
    public int siteOpCount;

    public final RoadGraph roads = new RoadGraph(), rails = new RoadGraph();
    public final List<Integer> entryNodes = new ArrayList<Integer>();
    public final List<Integer> railEntries = new ArrayList<Integer>();
    public String name;
    /** How long the build took, ms (not part of the checksum). */
    public long buildMs;

    public Nation(int size, int preset, long seed) {
        this.size = size;
        this.preset = preset;
        this.seed = seed;
        cw = size / CELL;
        sw = size / SECT;
        cover = new byte[cw * cw];
        height = new byte[cw * cw];
        int ns = sw * sw;
        secSettlement = new short[ns];
        Arrays.fill(secSettlement, (short) -1);
        district = new byte[ns];
        density = new byte[ns];
        lines = new byte[ns];
        residents = new int[ns];
        sums = new byte[SUMS][ns];
    }

    public int px() {
        return size * Tile.SIZE;
    }

    public Region.Settlement capital() {
        return settlements.get(0);
    }

    /** Land cover at tile (tx, ty); off the map counts as sea. */
    public int coverAt(int tx, int ty) {
        if (tx < 0 || ty < 0 || tx >= size || ty >= size) return C_DEEP;
        return cover[(ty / CELL) * cw + tx / CELL];
    }

    public static boolean wet(int cover) {
        return cover == C_DEEP || cover == C_WATER;
    }

    public float cellHeight(int c) {
        return (height[c] & 255) / HSCALE - H0;
    }

    /** Height at tile (tx, ty), bilinear between coarse cell centres (what tile-level terrain starts from). */
    public float heightAt(float tx, float ty) {
        float fx = tx / CELL - 0.5f, fy = ty / CELL - 0.5f;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float ax = fx - x0, ay = fy - y0;
        float a = hc(x0, y0), b = hc(x0 + 1, y0), c = hc(x0, y0 + 1), d = hc(x0 + 1, y0 + 1);
        float top = a + (b - a) * ax, bot = c + (d - c) * ax;
        return top + (bot - top) * ay;
    }

    private float hc(int x, int y) {
        x = x < 0 ? 0 : x >= cw ? cw - 1 : x;
        y = y < 0 ? 0 : y >= cw ? cw - 1 : y;
        return cellHeight(y * cw + x);
    }

    public int sector(int tx, int ty) {
        return (ty / SECT) * sw + tx / SECT;
    }

    /** True if the sector has streets (it belongs to a town, city or village, not to a site or the countryside). */
    public boolean built(int s) {
        return secSettlement[s] >= 0 && district[s] != D_NONE;
    }

    /** The half-width in tiles of the lattice street on line coordinate v (tiles): 3 on arterials, 2 on avenues, 1 on streets. */
    public static int lineHalf(int v) {
        return v % ARTERIAL == 0 ? 3 : v % AVENUE == 0 ? 2 : 1;
    }

    /** The offsets (0..64, tiles) of the north-south (vertical) or east-west lines bounding blocks in sector s. */
    public int[] lineOffsets(int s, boolean vertical) {
        int m = lines[s] >> (vertical ? 0 : 3);
        int n = 2 + Integer.bitCount(m & 7);
        int[] out = new int[n];
        int k = 0;
        out[k++] = 0;
        for (int i = 0; i < 3; i++) if ((m >> i & 1) != 0) out[k++] = STREET * (i + 1);
        out[k] = SECT;
        return out;
    }

    /** The blocks of sector s as {x0, y0, x1, y1} in tiles (inclusive), inside the streets round them. */
    public List<int[]> blocks(int s) {
        List<int[]> out = new ArrayList<int[]>();
        int X0 = (s % sw) * SECT, Y0 = (s / sw) * SECT;
        int[] xs = lineOffsets(s, true), ys = lineOffsets(s, false);
        for (int j = 0; j + 1 < ys.length; j++)
            for (int i = 0; i + 1 < xs.length; i++) out.add(block(X0, Y0, xs[i], xs[i + 1], ys[j], ys[j + 1]));
        return out;
    }

    static int[] block(int X0, int Y0, int xa, int xb, int ya, int yb) {
        return new int[]{X0 + xa + lineHalf(X0 + xa) + 1, Y0 + ya + lineHalf(Y0 + ya) + 1,
                X0 + xb - lineHalf(X0 + xb) - 1, Y0 + yb - lineHalf(Y0 + yb) - 1};
    }

    public int sum(int which, int s) {
        return sums[which][s] & 255;
    }

    public int totalResidents() {
        int t = 0;
        for (int r : residents) t += r;
        return t;
    }

    /** Each national building's door tile (y * size + x), worked out by NationGen; -1 where only the chunk knows. */
    public int[] bdoor;

    int addBuilding(int x, int y, int w, int h, int kind, int floors, int settlement, int res, int roof) {
        if (bCount == bx.length) {
            int n = bx.length * 2;
            bx = Arrays.copyOf(bx, n);
            by = Arrays.copyOf(by, n);
            bw = Arrays.copyOf(bw, n);
            bh = Arrays.copyOf(bh, n);
            bkind = Arrays.copyOf(bkind, n);
            bfloors = Arrays.copyOf(bfloors, n);
            bset = Arrays.copyOf(bset, n);
            bres = Arrays.copyOf(bres, n);
            broof = Arrays.copyOf(broof, n);
        }
        int i = bCount++;
        bx[i] = x;
        by[i] = y;
        bw[i] = w;
        bh[i] = h;
        bkind[i] = (byte) kind;
        bfloors[i] = (byte) Math.max(1, Math.min(60, floors));
        bset[i] = (short) settlement;
        bres[i] = res;
        broof[i] = roof;
        return i;
    }

    void op(int settlement, int mode, int x0, int y0, int x1, int y1, int tile, int edge) {
        if ((siteOpCount + 1) * OP_INTS > siteOps.length) siteOps = Arrays.copyOf(siteOps, siteOps.length * 2);
        int o = siteOpCount++ * OP_INTS;
        siteOps[o] = settlement;
        siteOps[o + 1] = mode;
        siteOps[o + 2] = Math.min(x0, x1);
        siteOps[o + 3] = Math.min(y0, y1);
        siteOps[o + 4] = Math.max(x0, x1);
        siteOps[o + 5] = Math.max(y0, y1);
        siteOps[o + 6] = tile;
        siteOps[o + 7] = edge;
    }

    /** A checksum of everything generated, for tests. */
    public long checksum() {
        long h = 1469598103934665603L;
        h = fnv(h, cover);
        h = fnv(h, height);
        h = fnv(h, district);
        h = fnv(h, density);
        h = fnv(h, lines);
        for (int r : residents) h = (h ^ r) * 1099511628211L;
        for (byte[] s : sums) h = fnv(h, s);
        RoadGraph[] gs = {roads, rails};
        for (RoadGraph g : gs) {
            for (int i = 0; i < g.nodeCount; i++) h = (h ^ Float.floatToIntBits(g.nx[i]) ^ ((long) Float.floatToIntBits(g.ny[i]) << 32)) * 1099511628211L;
            for (int e = 0; e < g.edgeCount; e++) h = (h ^ ((long) g.ea[e] << 32 | g.eb[e]) ^ g.ekind[e] ^ (g.ebridge[e] ? 77 : 0)) * 1099511628211L;
        }
        for (Region.Settlement s : settlements) h = (h ^ s.cx * 31L ^ s.cy * 7919L ^ s.residents ^ s.hubNode * 131L) * 1099511628211L;
        for (Region.Facility f : facilities) h = (h ^ f.kind ^ Float.floatToIntBits(f.x) ^ (long) Float.floatToIntBits(f.y) << 20 ^ f.node) * 1099511628211L;
        for (int i = 0; i < bCount; i++) h = (h ^ bx[i] ^ (long) by[i] << 20 ^ bw[i] << 8 ^ bkind[i]) * 1099511628211L;
        for (int i = 0; i < siteOpCount * OP_INTS; i++) h = (h ^ siteOps[i]) * 1099511628211L;
        return h;
    }

    private static long fnv(long h, byte[] a) {
        for (byte b : a) h = (h ^ b) * 1099511628211L;
        return h;
    }

    /** Bytes held by the nation's arrays and graphs (the lists of settlements and facilities are a few KB more). */
    public long memoryBytes() {
        long b = cover.length + height.length;
        b += 2L * secSettlement.length + district.length + density.length + lines.length + 4L * residents.length;
        for (byte[] s : sums) b += s.length;
        for (float[] r : rivers) b += 4L * r.length;
        b += 4L * 7 * bx.length + 2L * bx.length + 2L * bset.length + 4L * siteOps.length;
        b += roads.memoryBytes() + rails.memoryBytes();
        b += settlements.size() * 200L + facilities.size() * 200L;
        return b;
    }
}
