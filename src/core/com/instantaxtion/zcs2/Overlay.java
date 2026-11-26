package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Everything the game has done to the map since it was generated, per 64x64 page (a chunk on a national map), so a
 * save can hold the edits instead of every tile, and a chunk dropped from the ChunkCache comes back as it was left:
 * <ul>
 * <li>sparse tile edits: a page-local tile index (ChunkStore.local) and the tile now there. Barricades and walls
 * (Engineers), rubble where the dead broke through (Undead), blown bridges (Strategic), camps (Society), and any other
 * Region.set. An edit put back to the tile it replaced is dropped again;</li>
 * <li>destroyed buildings (blasts, air strikes, fires burning out): a bitset of the chunk's own buildings, and the
 * list of Region's ("national") buildings brought down on the page. Their rubble isn't stored tile by tile but drawn
 * again from these, on the tiles the building still held;</li>
 * <li>a version per page, raised by every change, for ChunkCache's overlay hook.</li>
 * </ul>
 * Applying a page: the destroyed national buildings first, then the chunk's own, then the tile edits (a tile edited
 * and later rubbled by a building coming down loses its edit when that happens, so the order always gives the tiles
 * as they were left), then the navigation cells worked out again from the tiles (as Region.rebuildNav does).
 * Region records into its overlay from when the World is made (Region.recordEdits); generation writes tiles raw.
 * Thread-safe: edits come from the game thread, version() and apply() also from ChunkCache's callers.
 */
public final class Overlay implements ChunkCache.Overlay {
    /** One page's edits. */
    static final class Page {
        int version;
        /** Tile edits, sorted by local index: the tile now there and the one it replaced (orig, not saved). */
        int n;
        short[] at = new short[4];
        byte[] tile = new byte[4], orig = new byte[4];
        /** The chunk's own buildings brought down (bit = local index), or null. */
        long[] gone;
        /** Region's buildings brought down that stood on this page, in the order they fell. */
        int nn;
        int[] national;

        boolean empty() {
            if (n > 0 || nn > 0) return false;
            if (gone != null) for (long v : gone) if (v != 0) return false;
            return true;
        }

        int find(int i) {
            return Arrays.binarySearch(at, 0, n, (short) i);
        }

        void remove(int k) {
            System.arraycopy(at, k + 1, at, k, n - k - 1);
            System.arraycopy(tile, k + 1, tile, k, n - k - 1);
            System.arraycopy(orig, k + 1, orig, k, n - k - 1);
            n--;
        }

        void insert(int k, int i, int now, int was) {
            if (n == at.length) {
                at = Arrays.copyOf(at, n * 2);
                tile = Arrays.copyOf(tile, n * 2);
                orig = Arrays.copyOf(orig, n * 2);
            }
            System.arraycopy(at, k, at, k + 1, n - k);
            System.arraycopy(tile, k, tile, k + 1, n - k);
            System.arraycopy(orig, k, orig, k + 1, n - k);
            at[k] = (short) i;
            tile[k] = (byte) now;
            orig[k] = (byte) was;
            n++;
        }

        boolean hasNational(int id) {
            for (int k = nn - 1; k >= 0; k--) if (national[k] == id) return true;
            return false;
        }

        void addNational(int id) {
            if (national == null) national = new int[2];
            if (nn == national.length) national = Arrays.copyOf(national, nn * 2);
            national[nn++] = id;
        }
    }

    private final Page[] page;

    /** An empty overlay over 'pages' pages (ChunkStore.pages(): on a national map, the chunk grid). */
    public Overlay(int pages) {
        page = new Page[pages];
    }

    public int pages() {
        return page.length;
    }

    private Page at(int p) {
        Page pg = page[p];
        if (pg == null) page[p] = pg = new Page();
        return pg;
    }

    // ------------------------------------------------------------------ recording (Region)

    /** Tile i of page p went from 'was' to 'now' (they differ). Returns the page's new version. */
    synchronized int tile(int p, int i, int was, int now) {
        Page pg = at(p);
        int k = pg.find(i);
        if (k >= 0) {
            if (pg.orig[k] == (byte) now) pg.remove(k);
            else pg.tile[k] = (byte) now;
        } else pg.insert(-k - 1, i, now, was);
        return ++pg.version;
    }

    /** Tile i of page p turned to rubble under Region's building id (a national one) coming down. */
    synchronized int rubbleNational(int p, int i, int id) {
        Page pg = at(p);
        int k = pg.find(i);
        if (k >= 0) pg.remove(k);
        if (!pg.hasNational(id)) pg.addNational(id);
        return ++pg.version;
    }

    /** Region's building id came down over page p, which isn't in memory: its rubble is drawn when it comes in. */
    synchronized void nationalOn(int p, int id) {
        Page pg = at(p);
        if (pg.hasNational(id)) return;
        pg.addNational(id);
        pg.version++;
    }

    /** Tile i of page p (a chunk) turned to rubble under the chunk's own building b coming down. */
    synchronized int rubbleLocal(int p, int i, int b) {
        Page pg = at(p);
        int k = pg.find(i);
        if (k >= 0) pg.remove(k);
        int words = (b >> 6) + 1;
        if (pg.gone == null) pg.gone = new long[Math.max(2, words)];
        else if (pg.gone.length < words) pg.gone = Arrays.copyOf(pg.gone, words);
        pg.gone[b >> 6] |= 1L << (b & 63);
        return ++pg.version;
    }

    // ------------------------------------------------------------------ reading

    /** The version of page c's edits (0: never edited). */
    public synchronized int version(int c) {
        Page pg = c >= 0 && c < page.length ? page[c] : null;
        return pg == null ? 0 : pg.version;
    }

    /** True if page p has anything to apply. */
    public synchronized boolean edited(int p) {
        Page pg = page[p];
        return pg != null && !pg.empty();
    }

    /** Tile edits on page p (local indices into at, the tiles into tile; the count is returned). For tools and P7. */
    public synchronized int edits(int p, short[] at, byte[] tile) {
        Page pg = page[p];
        if (pg == null) return 0;
        int n = Math.min(pg.n, at.length);
        System.arraycopy(pg.at, 0, at, 0, n);
        System.arraycopy(pg.tile, 0, tile, 0, n);
        return n;
    }

    /** True if the chunk's own building b on page p has been brought down. */
    public synchronized boolean gone(int p, int b) {
        Page pg = page[p];
        return pg != null && pg.gone != null && (b >> 6) < pg.gone.length && (pg.gone[b >> 6] & 1L << (b & 63)) != 0;
    }

    /** Tile edits held in all (tests, probes). */
    public synchronized int editCount() {
        int t = 0;
        for (Page pg : page) if (pg != null) t += pg.n;
        return t;
    }

    // ------------------------------------------------------------------ applying

    /** Applies page c's edits to the chunk (as generated), and works its navigation cells out again. */
    public synchronized void apply(Chunk c) {
        Page pg = c.index < page.length ? page[c.index] : null;
        if (pg == null || pg.empty()) return;
        applyPage(pg, c.tile, c.bld, c, false);
        for (int y = 0; y < Chunk.NAVS; y++)
            for (int x = 0; x < Chunk.NAVS; x++) c.nav[y * Chunk.NAVS + x] = navCell(c.tile, x, y);
    }

    /**
     * Lays every page's edits over a region that is as generated (loading a save; resident plain pages only), taking
     * each edit's replaced tile from it. The caller works the navigation grid out again.
     */
    synchronized void applyTo(Region r) {
        ChunkStore s = r.store;
        for (int p = 0; p < page.length; p++) {
            Page pg = page[p];
            if (pg == null || pg.empty() || !s.resident(p)) continue;
            applyPage(pg, s.tile[p], s.bld[p], s.chunk(p), true);
        }
    }

    /**
     * National maps, a save just read: works out each edit's replaced tile (not saved) by laying the page's edits
     * over a freshly generated copy of its chunk, as applyTo does for today's maps.
     */
    synchronized void takeOriginals(Nation n) {
        for (int p = 0; p < page.length; p++) {
            Page pg = page[p];
            if (pg == null || pg.n == 0) continue;
            Chunk c = ChunkGen.generate(n, p % n.sw, p / n.sw);
            applyPage(pg, c.tile, c.bld, c, true);
        }
    }

    private static void applyPage(Page pg, byte[] tile, short[] bld, Chunk c, boolean takeOrig) {
        for (int k = 0; k < pg.nn; k++) {
            short v = (short) -(pg.national[k] + 1);
            for (int i = 0; i < ChunkStore.AREA; i++)
                if (bld[i] == v) {
                    tile[i] = Tile.RUBBLE;
                    bld[i] = 0;
                }
        }
        if (pg.gone != null && c != null)
            for (int b = 0; b < c.bCount; b++) {
                if ((b >> 6) >= pg.gone.length || (pg.gone[b >> 6] & 1L << (b & 63)) == 0) continue;
                short v = (short) (b + 1);
                for (int y = c.by[b]; y < c.by[b] + c.bh[b]; y++)
                    for (int x = c.bx[b]; x < c.bx[b] + c.bw[b]; x++) {
                        int i = y * Chunk.SIZE + x;
                        if (bld[i] == v) {
                            tile[i] = Tile.RUBBLE;
                            bld[i] = 0;
                        }
                    }
                c.bfloors[b] = 0;
            }
        for (int k = 0; k < pg.n; k++) {
            int i = pg.at[k];
            if (takeOrig) pg.orig[k] = tile[i];
            tile[i] = pg.tile[k];
        }
    }

    /** A navigation cell from its four tiles: the rule in Region.rebuildNav and ChunkGen. */
    private static byte navCell(byte[] tile, int x, int y) {
        int open = 0, road = 0;
        for (int dy = 0; dy < Region.NAV; dy++)
            for (int dx = 0; dx < Region.NAV; dx++) {
                int k = tile[(y * Region.NAV + dy) * Chunk.SIZE + x * Region.NAV + dx];
                if (Tile.walkable(k)) open++;
                if (Tile.roadish(k)) road++;
            }
        return (byte) (open < 2 ? 0 : road >= 2 ? 2 : 1);
    }

    // ------------------------------------------------------------------ from a full map (older saves)

    /**
     * The overlay of a region whose tiles were loaded whole (a save before version 8) over 'generated', its tiles as
     * generated (page by page, as ChunkStore.tile), before its buildings brought down are cleared off their tiles. Region's buildings with no floors left were brought down: their
     * rubble is drawn from that, and every other tile that differs from the generated map becomes an edit.
     */
    static Overlay diff(Region r, byte[][] generated) {
        Overlay o = new Overlay(r.store.pages());
        ChunkStore s = r.store;
        byte[] expect = new byte[ChunkStore.AREA];
        for (int p = 0; p < s.pages(); p++) {
            if (generated[p] == null || !s.resident(p)) continue;
            System.arraycopy(generated[p], 0, expect, 0, expect.length);
            short[] gb = s.bld[p]; // buildings on tiles aren't saved: still as generated
            for (int i = 0; i < ChunkStore.AREA; i++) {
                int v = gb[i];
                if (v < 0 && -v - 1 < r.bCount && r.bfloors[-v - 1] == 0) {
                    Page pg = o.at(p);
                    if (!pg.hasNational(-v - 1)) pg.addNational(-v - 1);
                    expect[i] = Tile.RUBBLE;
                }
            }
            byte[] now = s.tile[p];
            for (int i = 0; i < ChunkStore.AREA; i++)
                if (now[i] != expect[i]) {
                    Page pg = o.at(p);
                    pg.insert(pg.n, i, now[i], expect[i]);
                }
            if (o.page[p] != null) o.page[p].version = 1;
        }
        return o;
    }

    // ------------------------------------------------------------------ saves

    /** Writes the pages with anything on them, in page order. */
    synchronized void write(DataOutputStream o) throws IOException {
        int count = 0;
        for (Page pg : page) if (pg != null && !pg.empty()) count++;
        writeVar(o, page.length);
        writeVar(o, count);
        int last = -1;
        for (int p = 0; p < page.length; p++) {
            Page pg = page[p];
            if (pg == null || pg.empty()) continue;
            writeVar(o, p - last - 1);
            last = p;
            writeVar(o, pg.n);
            int prev = -1;
            for (int k = 0; k < pg.n; k++) {
                writeVar(o, pg.at[k] - prev - 1);
                prev = pg.at[k];
                o.writeByte(pg.tile[k]);
            }
            writeVar(o, pg.nn);
            for (int k = 0; k < pg.nn; k++) writeVar(o, pg.national[k]);
            int words = 0;
            if (pg.gone != null) for (int k = 0; k < pg.gone.length; k++) if (pg.gone[k] != 0) words = k + 1;
            writeVar(o, words);
            for (int k = 0; k < words; k++) o.writeLong(pg.gone[k]);
        }
    }

    /** Reads what write() wrote (every page read gets version 1). */
    static Overlay read(DataInputStream in) throws IOException {
        Overlay ov = new Overlay(readVar(in));
        int count = readVar(in), p = -1;
        for (int c = 0; c < count; c++) {
            p += readVar(in) + 1;
            Page pg = ov.at(p);
            pg.version = 1;
            int n = readVar(in), prev = -1;
            for (int k = 0; k < n; k++) {
                prev += readVar(in) + 1;
                pg.insert(k, prev, in.readByte(), 0);
            }
            int nn = readVar(in);
            for (int k = 0; k < nn; k++) pg.addNational(readVar(in));
            int words = readVar(in);
            if (words > 0) {
                pg.gone = new long[words];
                for (int k = 0; k < words; k++) pg.gone[k] = in.readLong();
            }
        }
        return ov;
    }

    /** An unsigned number in 7-bit groups, low first. */
    static void writeVar(DataOutputStream o, int v) throws IOException {
        while ((v & ~0x7F) != 0) {
            o.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        o.writeByte(v);
    }

    static int readVar(DataInputStream in) throws IOException {
        int v = 0, shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
            if (shift > 28) throw new IOException("save is damaged");
        }
    }
}
