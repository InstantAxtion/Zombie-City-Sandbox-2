package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * Region's tiles and the building on each tile, kept in 64x64-tile pages (the Chunk square) instead of two dense
 * map-sized arrays. Page (cx, cy) holds tiles cx*64..cx*64+63 by cy*64..cy*64+63, row-major inside the page, in the
 * same layout as Chunk.tile and Chunk.bld, so a generated Chunk can stand in as a page as it is (attach()).
 * <ul>
 * <li>Building on a tile (short, as Chunk.bld): 0 none, v &lt; 0 Region's own building -v - 1 (the "national"
 * buildings, 0..32767), v &gt; 0 the page's Chunk's own building v - 1 (only on a page that is a Chunk).</li>
 * <li>Today's maps (RegionGen) allocate every page at the start and never drop one. Real-scale maps (P7) attach
 * Chunks from a ChunkCache as they become active; a page not resident reads as null in the tables.</li>
 * <li>Page index: cy * pw + cx. On a national map pw equals Nation.sw, so a page's index is its Chunk's index (the
 * one encoded in chunk building ids).</li>
 * </ul>
 * Region.t() reads the page table directly (one load more than a dense array) rather than calling in here.
 */
public final class ChunkStore {
    public static final int SHIFT = 6, SIZE = 1 << SHIFT, MASK = SIZE - 1, AREA = SIZE * SIZE;
    /** Navigation cells per page side (Region.NAV tiles each), and per page. */
    public static final int NAV_SHIFT = SHIFT - 1, NAVS = 1 << NAV_SHIFT, NAV_AREA = NAVS * NAVS;

    static {
        if (Chunk.SIZE != SIZE) throw new IllegalStateException("pages and chunks must be the same square");
        if (Chunk.NAVS != NAVS || Region.NAV != 2) throw new IllegalStateException("navigation pages must match chunks");
    }

    /** Map size in tiles, and in pages. */
    public final int W, H, pw, ph;
    /** The tiles of each page, or null where it isn't resident. */
    final byte[][] tile;
    /** The building on each tile of each page (see the class comment), or null where it isn't resident. */
    final short[][] bld;
    /** The navigation cells of each page (32x32, row-major, as Chunk.nav: 0 blocked, 1 open, 2 road), or null. */
    final byte[][] nav;
    /** The Chunk a page is, when it is one; null for a plain page (today's maps). */
    final Chunk[] chunk;

    ChunkStore(int w, int h) {
        W = w;
        H = h;
        pw = (w + MASK) >> SHIFT;
        ph = (h + MASK) >> SHIFT;
        tile = new byte[pw * ph][];
        bld = new short[pw * ph][];
        nav = new byte[pw * ph][];
        chunk = new Chunk[pw * ph];
    }

    /** Makes every page resident, all zeros (as the old dense arrays started). */
    void allocateAll() {
        for (int p = 0; p < tile.length; p++) {
            if (tile[p] == null) tile[p] = new byte[AREA];
            if (bld[p] == null) bld[p] = new short[AREA];
            if (nav[p] == null) nav[p] = new byte[NAV_AREA];
        }
    }

    /** Puts a chunk in as the page it covers (its arrays are used as they are, not copied). */
    void attach(Chunk c) {
        int p = c.cy * pw + c.cx;
        tile[p] = c.tile;
        bld[p] = c.bld;
        nav[p] = c.nav;
        chunk[p] = c;
    }

    /** Drops a page (P7: a chunk leaving the active set). */
    void detach(int p) {
        tile[p] = null;
        bld[p] = null;
        nav[p] = null;
        chunk[p] = null;
    }

    public int pages() {
        return tile.length;
    }

    public boolean resident(int p) {
        return tile[p] != null;
    }

    /** The Chunk page p is, or null (a plain page, or not resident). */
    public Chunk chunk(int p) {
        return p >= 0 && p < chunk.length ? chunk[p] : null;
    }

    /** The page index of tile (tx, ty), which must be on the map. */
    public int pageOf(int tx, int ty) {
        return (ty >> SHIFT) * pw + (tx >> SHIFT);
    }

    /** The index inside its page of tile (tx, ty). */
    public static int local(int tx, int ty) {
        return (ty & MASK) << SHIFT | (tx & MASK);
    }

    // the building on a tile, raw (no bounds checks: callers check)

    int bldRaw(int tx, int ty) {
        return bld[(ty >> SHIFT) * pw + (tx >> SHIFT)][(ty & MASK) << SHIFT | (tx & MASK)];
    }

    void setBldRaw(int tx, int ty, int v) {
        bld[(ty >> SHIFT) * pw + (tx >> SHIFT)][(ty & MASK) << SHIFT | (tx & MASK)] = (short) v;
    }

    /** Reads a save's tiles before version 8: row by row, top to bottom, W bytes a row (as the old dense array). */
    void readTiles(DataInputStream in) throws IOException {
        for (int ty = 0; ty < H; ty++) {
            int row = (ty & MASK) << SHIFT, base = (ty >> SHIFT) * pw;
            for (int cx = 0; cx < pw; cx++) in.readFully(tile[base + cx], row, Math.min(SIZE, W - (cx << SHIFT)));
        }
    }

    /** FNV over the tiles in row-major map order (the same as over the old dense array). */
    long hashTiles(long h) {
        for (int ty = 0; ty < H; ty++) {
            int row = (ty & MASK) << SHIFT, base = (ty >> SHIFT) * pw;
            for (int cx = 0; cx < pw; cx++) {
                byte[] t = tile[base + cx];
                int n = Math.min(SIZE, W - (cx << SHIFT));
                for (int i = row; i < row + n; i++) h = (h ^ t[i]) * 1099511628211L;
            }
        }
        return h;
    }
}
