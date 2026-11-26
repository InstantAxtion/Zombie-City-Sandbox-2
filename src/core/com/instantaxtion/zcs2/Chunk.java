package com.instantaxtion.zcs2;

import java.util.Arrays;

/**
 * One 64x64-tile page of a real-scale map, the same square as a sector, made by ChunkGen from the coarse national
 * layer: tiles, the building on each tile, 32x32 navigation cells, and its own buildings (structure of arrays, with
 * doors and residents). National buildings (facilities and sites, in Nation's arrays) are drawn into the tiles too and
 * are found through building(); they are not in the chunk's own arrays.
 * Generated chunks are pure functions of (nation, cx, cy): two chunks made from the same place are byte for byte equal.
 */
public final class Chunk {
    public static final int SIZE = Nation.SECT, AREA = SIZE * SIZE;
    /** Navigation cells per side (Region.NAV tiles each). */
    public static final int NAVS = SIZE / Region.NAV;
    /** Bits of the local building index in an encoded chunk building id. */
    public static final int LOCAL_BITS = 10, MAX_BUILDINGS = (1 << LOCAL_BITS) - 1;
    public static final int CHUNK_FLAG = 0x40000000;

    public final int cx, cy;
    /** Index in the nation's chunk (sector) grid: cy * sw + cx. */
    public final int index;
    public final byte[] tile = new byte[AREA];
    /**
     * The building on each tile: 0 none, k > 0 the chunk's own building k - 1, k < 0 the national building -k - 1.
     */
    public final short[] bld = new short[AREA];
    /** 0 blocked, 1 open, 2 road (as Region.nav). */
    public final byte[] nav = new byte[NAVS * NAVS];

    // the chunk's own buildings: tile coordinates within the chunk
    public int bCount;
    public byte[] bx, by, bw, bh, bkind, bfloors;
    /** The door: a walkable tile next to the building, in chunk tile coordinates (-1 if it has none). */
    public byte[] doorX, doorY;
    public int[] bres, broof;

    /** The settlement the chunk (its sector) belongs to, or -1. */
    public int settlement = -1;
    /** The district type and density class of the chunk's sector. */
    public byte district, density;
    /**
     * Residents of the sector who don't live in the chunk's own buildings: the people at a site (in its national
     * buildings), or, very rarely, a sector with no dry ground to build a house on. The chunk's buildings hold the rest,
     * so bres summed plus this is exactly Nation.residents of the sector.
     */
    public int elsewhere;
    /** The overlay version applied (ChunkCache), -1 for a chunk as generated. */
    public int overlayVersion = -1;

    Chunk(int cx, int cy, int index, int cap) {
        this.cx = cx;
        this.cy = cy;
        this.index = index;
        alloc(Math.max(8, cap));
    }

    private void alloc(int n) {
        bx = bx == null ? new byte[n] : Arrays.copyOf(bx, n);
        by = by == null ? new byte[n] : Arrays.copyOf(by, n);
        bw = bw == null ? new byte[n] : Arrays.copyOf(bw, n);
        bh = bh == null ? new byte[n] : Arrays.copyOf(bh, n);
        bkind = bkind == null ? new byte[n] : Arrays.copyOf(bkind, n);
        bfloors = bfloors == null ? new byte[n] : Arrays.copyOf(bfloors, n);
        doorX = doorX == null ? new byte[n] : Arrays.copyOf(doorX, n);
        doorY = doorY == null ? new byte[n] : Arrays.copyOf(doorY, n);
        bres = bres == null ? new int[n] : Arrays.copyOf(bres, n);
        broof = broof == null ? new int[n] : Arrays.copyOf(broof, n);
    }

    /** Trims the building arrays to their count (a generated chunk holds no spare room). */
    void trim() {
        if (bx.length != bCount) alloc(bCount);
    }

    /** Adds a building at chunk tile (x, y); the caller has checked the rectangle is free. Returns its local index. */
    int addBuilding(int x, int y, int w, int h, int kind, int floors, int roof) {
        if (bCount >= MAX_BUILDINGS) return -1;
        if (bCount == bx.length) alloc(bx.length * 2);
        int i = bCount++;
        bx[i] = (byte) x;
        by[i] = (byte) y;
        bw[i] = (byte) w;
        bh[i] = (byte) h;
        bkind[i] = (byte) kind;
        bfloors[i] = (byte) Math.max(1, Math.min(60, floors));
        broof[i] = roof;
        doorX[i] = doorY[i] = -1;
        for (int ty = y; ty < y + h; ty++)
            for (int tx = x; tx < x + w; tx++) {
                tile[ty * SIZE + tx] = Tile.BUILDING;
                bld[ty * SIZE + tx] = (short) (i + 1);
            }
        return i;
    }

    /** The tile at chunk coordinates (x, y), 0..63. */
    public int t(int x, int y) {
        return tile[y * SIZE + x];
    }

    /** The tile at map tile (tx, ty), which must lie in this chunk. */
    public int tAt(int tx, int ty) {
        return tile[(ty - cy * SIZE) * SIZE + tx - cx * SIZE];
    }

    /** The encoded id of the chunk's own building i (national buildings keep their small ids). */
    public int id(int i) {
        return CHUNK_FLAG | index << LOCAL_BITS | i;
    }

    public static boolean isChunkBuilding(int id) {
        return (id & CHUNK_FLAG) != 0;
    }

    public static int chunkOf(int id) {
        return (id & ~CHUNK_FLAG) >>> LOCAL_BITS;
    }

    public static int localOf(int id) {
        return id & MAX_BUILDINGS;
    }

    /** The building on chunk tile (x, y) as an id: a national building's index, an encoded chunk building, or -1. */
    public int building(int x, int y) {
        int v = bld[y * SIZE + x];
        if (v == 0) return -1;
        return v > 0 ? id(v - 1) : -v - 1;
    }

    /** People living in the chunk's own buildings. */
    public int residents() {
        int t = 0;
        for (int i = 0; i < bCount; i++) t += bres[i];
        return t;
    }

    /** Bytes held (arrays and headers, roughly). */
    public long memoryBytes() {
        long b = 16 * 8 + tile.length + 2L * bld.length + nav.length;
        b += bx.length * (6 + 2 + 8L) + 10 * 16;
        return b;
    }

    /** A hash of everything generated, for tests (identical chunks hash the same). */
    public long checksum() {
        long h = 1469598103934665603L ^ index;
        for (byte v : tile) h = (h ^ v) * 1099511628211L;
        for (short v : bld) h = (h ^ v) * 1099511628211L;
        for (byte v : nav) h = (h ^ v) * 1099511628211L;
        h = (h ^ bCount) * 1099511628211L;
        for (int i = 0; i < bCount; i++) {
            h = (h ^ bx[i] ^ by[i] << 8 ^ bw[i] << 16 ^ (long) bh[i] << 24) * 1099511628211L;
            h = (h ^ bkind[i] ^ bfloors[i] << 8 ^ doorX[i] << 16 ^ (long) doorY[i] << 24) * 1099511628211L;
            h = (h ^ bres[i] ^ (long) broof[i] << 32) * 1099511628211L;
        }
        h = (h ^ settlement ^ (long) district << 20 ^ (long) density << 28 ^ (long) elsewhere << 36) * 1099511628211L;
        return h;
    }
}
