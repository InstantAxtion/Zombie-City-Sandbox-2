package com.instantaxtion.zcs2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The generated region: terrain tiles, buildings, settlements, facilities and roads. Static apart from damage. */
public final class Region {
    public static final int SECT = 32; // tiles per sector side
    public static final int NAV = 2;   // tiles per navigation cell side

    // building kinds
    public static final int B_HOUSE = 0, B_APARTMENT = 1, B_OFFICE = 2, B_TOWER = 3, B_SHOP = 4, B_WAREHOUSE = 5,
            B_FARMHOUSE = 6, B_BARN = 7, B_POLICE = 8, B_HOSPITAL = 9, B_POWER = 10, B_FUEL = 11, B_BARRACKS = 12,
            B_HANGAR = 13, B_PRISON = 14, B_LAB = 15, B_CHURCH = 16, B_SCHOOL = 17, B_MALL = 18, B_ARMORY = 19,
            B_DEPOT = 20, B_CELLBLOCK = 21;
    public static final String[] BNAMES = {"House", "Apartments", "Offices", "Tower", "Shop", "Warehouse", "Farmhouse",
            "Barn", "Police station", "Hospital", "Power station", "Fuel depot", "Barracks", "Motor pool", "Prison",
            "Research lab", "Church", "School", "Mall", "Guard armory", "Bus depot", "Cell block"};

    // facility kinds
    public static final int F_POLICE = 0, F_HOSPITAL = 1, F_POWER = 2, F_FUEL = 3, F_BASE = 4, F_ARMORY = 5, F_PRISON = 6,
            F_LAB = 7, F_DEPOT = 8, F_HELIPAD = 9, F_PARK = 10;
    public static final String[] FNAMES = {"Police station", "Hospital", "Power station", "Fuel depot", "Army base",
            "Guard armory", "Prison", "Research lab", "Bus depot", "Helipad", "Park"};

    public static final int S_CITY = 0, S_TOWN = 1, S_VILLAGE = 2, S_BASE = 3, S_PRISON = 4;

    public static final class Settlement {
        public int id, kind, cx, cy, radius; // tiles
        public String name;
        public int residents;
        public int grid = -1; // power grid
        public int hubNode = -1; // a central junction
        public final List<Integer> edgeNodes = new ArrayList<Integer>();
    }

    public static final class Facility {
        public int id, kind, settlement, building = -1;
        public float x, y; // px, the yard where vehicles and people gather
        public int node = -1; // nearest junction
        public int grid = -1; // power grid served (power stations)
        public String name;
        public int bx0, by0, bx1, by1; // block, tiles (inclusive)
    }

    public final int W, H;
    public final long seed;
    public final int preset;
    public String name;
    public final byte[] tile;
    public final int[] bldAt;
    public final List<Settlement> settlements = new ArrayList<Settlement>();
    public final List<Facility> facilities = new ArrayList<Facility>();
    public final RoadGraph roads = new RoadGraph();

    // buildings (structure of arrays)
    public int bCount;
    public int[] bx = new int[512], by = new int[512], bw = new int[512], bh = new int[512];
    public byte[] bkind = new byte[512], bfloors = new byte[512];
    public short[] bset = new short[512];
    public int[] bres = new int[512], broof = new int[512];

    // navigation grid (NAV x NAV tiles per cell): 0 blocked, 1 open, 2 road
    public final int navW, navH;
    public final byte[] nav;
    public final int secW, secH;
    public int gridCount; // power grids
    /** Edge entry points: junctions on the region border where outside help arrives. */
    public final List<Integer> entryNodes = new ArrayList<Integer>();

    public Region(int w, int h, long seed, int preset) {
        W = w;
        H = h;
        this.seed = seed;
        this.preset = preset;
        tile = new byte[w * h];
        bldAt = new int[w * h];
        Arrays.fill(bldAt, -1);
        navW = w / NAV;
        navH = h / NAV;
        nav = new byte[navW * navH];
        secW = (w + SECT - 1) / SECT;
        secH = (h + SECT - 1) / SECT;
    }

    public int px() {
        return W * Tile.SIZE;
    }

    public boolean in(int tx, int ty) {
        return tx >= 0 && ty >= 0 && tx < W && ty < H;
    }

    public int t(int tx, int ty) {
        if (tx < 0 || ty < 0 || tx >= W || ty >= H) return Tile.DEEP;
        return tile[ty * W + tx];
    }

    public void set(int tx, int ty, byte k) {
        if (tx >= 0 && ty >= 0 && tx < W && ty < H) tile[ty * W + tx] = k;
    }

    public int tileAtPx(float x, float y) {
        return t((int) Math.floor(x / Tile.SIZE), (int) Math.floor(y / Tile.SIZE));
    }

    public boolean walkablePx(float x, float y) {
        return Tile.walkable(tileAtPx(x, y));
    }

    public int sectorOfPx(float x, float y) {
        int sx = (int) (x / (Tile.SIZE * SECT)), sy = (int) (y / (Tile.SIZE * SECT));
        if (sx < 0) sx = 0;
        if (sy < 0) sy = 0;
        if (sx >= secW) sx = secW - 1;
        if (sy >= secH) sy = secH - 1;
        return sy * secW + sx;
    }

    public int addBuilding(int x, int y, int w, int h, int kind, int floors, int settlement, int residents, int roof) {
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
        bres[i] = residents;
        broof[i] = roof;
        for (int ty = y; ty < y + h; ty++)
            for (int tx = x; tx < x + w; tx++)
                if (in(tx, ty)) {
                    tile[ty * W + tx] = Tile.BUILDING;
                    bldAt[ty * W + tx] = i;
                }
        return i;
    }

    /** Turns a building into rubble (blasts, air strikes): people inside are the sector's problem. */
    public void destroyBuilding(int i) {
        for (int ty = by[i]; ty < by[i] + bh[i]; ty++)
            for (int tx = bx[i]; tx < bx[i] + bw[i]; tx++)
                if (in(tx, ty) && bldAt[ty * W + tx] == i) {
                    tile[ty * W + tx] = Tile.RUBBLE;
                    bldAt[ty * W + tx] = -1;
                }
        bfloors[i] = 0;
        rebuildNav(bx[i] - 2, by[i] - 2, bx[i] + bw[i] + 2, by[i] + bh[i] + 2);
    }

    public void rebuildNav(int x0, int y0, int x1, int y1) {
        int cx0 = Math.max(0, x0 / NAV), cy0 = Math.max(0, y0 / NAV);
        int cx1 = Math.min(navW - 1, x1 / NAV), cy1 = Math.min(navH - 1, y1 / NAV);
        for (int cy = cy0; cy <= cy1; cy++)
            for (int cx = cx0; cx <= cx1; cx++) {
                int open = 0, road = 0;
                for (int dy = 0; dy < NAV; dy++)
                    for (int dx = 0; dx < NAV; dx++) {
                        int k = t(cx * NAV + dx, cy * NAV + dy);
                        if (Tile.walkable(k)) open++;
                        if (Tile.roadish(k)) road++;
                    }
                nav[cy * navW + cx] = (byte) (open < 2 ? 0 : road >= 2 ? 2 : 1);
            }
    }

    public boolean navOpen(int cx, int cy) {
        return cx >= 0 && cy >= 0 && cx < navW && cy < navH && nav[cy * navW + cx] != 0;
    }

    /** True if nothing blocks a straight look from a to b (checked on the navigation grid). */
    public boolean lineOfSight(float ax, float ay, float bx2, float by2) {
        float cs = Tile.SIZE * NAV;
        int x0 = (int) (ax / cs), y0 = (int) (ay / cs), x1 = (int) (bx2 / cs), y1 = (int) (by2 / cs);
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy, n = dx + dy;
        int x = x0, y = y0;
        for (int i = 0; i < n; i++) {
            int e2 = err * 2;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            } else {
                err += dx;
                y += sy;
            }
            if (x == x1 && y == y1) break;
            if (x < 0 || y < 0 || x >= navW || y >= navH) return false;
            if (nav[y * navW + x] == 0) {
                // water doesn't block a look, buildings do
                int t = t(x * NAV, y * NAV), t2 = t(x * NAV + 1, y * NAV + 1);
                if (Tile.blocksSight(t) || Tile.blocksSight(t2)) return false;
            }
        }
        return true;
    }

    public Settlement settlementAt(float px, float py) {
        float tx = px / Tile.SIZE, ty = py / Tile.SIZE;
        for (Settlement s : settlements) {
            float dx = tx - s.cx, dy = ty - s.cy;
            if (dx * dx + dy * dy <= (float) s.radius * s.radius) return s;
        }
        return null;
    }

    public Settlement city() {
        for (Settlement s : settlements) if (s.kind == S_CITY) return s;
        return settlements.get(0);
    }

    /** A checksum of the map, for tests and saves. */
    public long checksum() {
        long h = 1469598103934665603L;
        for (byte b : tile) h = (h ^ b) * 1099511628211L;
        h ^= bCount * 31L + roads.nodeCount * 7L + roads.edgeCount;
        return h;
    }
}
