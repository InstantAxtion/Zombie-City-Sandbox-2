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
            B_DEPOT = 20, B_CELLBLOCK = 21, B_WATER = 22, B_PHONE = 23, B_FOODW = 24, B_FIRE = 25, B_HALL = 26,
            B_STATION = 27, B_TERMINAL = 28, B_DOCK = 29, B_GOV = 30, B_TENT = 31;
    public static final String[] BNAMES = {"House", "Apartments", "Offices", "Tower", "Shop", "Warehouse", "Farmhouse",
            "Barn", "Police station", "Hospital", "Power station", "Fuel depot", "Barracks", "Motor pool", "Prison",
            "Research lab", "Church", "School", "Mall", "Guard armory", "Bus depot", "Cell block", "Water works",
            "Telephone exchange", "Food warehouse", "Fire station", "City hall", "Railway station", "Terminal", "Dock warehouse",
            "State house", "Tent"};

    // facility kinds
    public static final int F_POLICE = 0, F_HOSPITAL = 1, F_POWER = 2, F_FUEL = 3, F_BASE = 4, F_ARMORY = 5, F_PRISON = 6,
            F_LAB = 7, F_DEPOT = 8, F_HELIPAD = 9, F_PARK = 10, F_WATER = 11, F_PHONE = 12, F_FOODW = 13, F_FIRE = 14,
            F_HALL = 15, F_STATION = 16, F_AIRBASE = 17, F_PORT = 18, F_AIRPORT = 19, F_GOV = 20, F_CAMP = 21, F_FOB = 22, F_HQ = 23;
    public static final int FKINDS = 24;
    public static final String[] FNAMES = {"Police station", "Hospital", "Power station", "Fuel depot", "Army base",
            "Guard armory", "Prison", "Research lab", "Bus depot", "Helipad", "Park", "Water works", "Telephone exchange",
            "Food warehouse", "Fire station", "City hall", "Railway station", "Air base", "Naval base", "Airport",
            "State house", "Refugee camp", "Forward operating base", "Headquarters"};

    public static final int S_CITY = 0, S_TOWN = 1, S_VILLAGE = 2, S_BASE = 3, S_PRISON = 4, S_AIRBASE = 5, S_NAVAL = 6,
            S_AIRPORT = 7, S_CAMP = 8, S_HOLDOUT = 9;
    public static final String[] SNAMES = {"City", "Town", "Village", "Army base", "Prison", "Air base", "Naval base",
            "Airport", "Refugee camp", "Survivor settlement"};

    public static final class Settlement {
        public int id, kind, cx, cy, radius; // tiles
        public String name;
        public int residents;
        public int grid = -1; // power grid
        public int water = -1, phone = -1; // water and telephone networks
        public int state = -1; // the state it belongs to
        public boolean capital;
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
        public int network = -1; // water works / telephone exchange: the network it runs
        public int railNode = -1; // railway stations: the junction on the rail graph
        /** People and vehicles kept here when not deployed, by content index (police, soldiers, firefighters). */
        public int[] pool, vpool;
        /** Stocks: ammunition (rounds), fuel, food (person-days), medicine (doses). */
        public float ammo, fuel, food, med;
        public boolean runtime; // made during the game (camps, forward bases)

        public int poolTotal() {
            int n = 0;
            if (pool != null) for (int v : pool) n += v;
            return n;
        }
    }

    public static final class State {
        public int id, capitalCity;
        public String name;
    }

    public final List<State> states = new ArrayList<State>();
    /** The railways, as a graph like the roads. */
    public final RoadGraph rails = new RoadGraph();
    public int waterCount, phoneCount;
    /** Railway junctions on the border: where trains from abroad come in. */
    public final List<Integer> railEntries = new ArrayList<Integer>();

    public final int W, H;
    public final long seed;
    public final int preset;
    public String name;
    public final byte[] tile;
    /** The building on each tile, stored as index + 1 (0 = none) in 16 bits: up to 65,534 buildings. */
    private final char[] bldAt;

    /** The building on tile index i (ty * W + tx), or -1. */
    public int building(int i) {
        return bldAt[i] - 1;
    }

    void clearBuilding(int i) {
        bldAt[i] = 0;
    }
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
        bldAt = new char[w * h];
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

    /** True when the tile and its eight neighbours can all be walked on (room to stand a few people). */
    private boolean openAround(int tx, int ty) {
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) if (!Tile.walkable(t(tx + dx, ty + dy))) return false;
        return true;
    }

    /**
     * Open ground near (x, y): the nearest walkable tile (if (x, y) is inside something), then, walking from
     * there, the nearest spot with room round it, or (road) the nearest street. Returns a tile's middle, or
     * the best found within about 30 tiles.
     */
    public float[] openGroundNear(float x, float y, boolean road) {
        int tx0 = (int) Math.floor(x / Tile.SIZE), ty0 = (int) Math.floor(y / Tile.SIZE);
        int sx = tx0, sy = ty0;
        if (!Tile.walkable(t(sx, sy))) {
            boolean found = false;
            for (int rad = 1; rad <= 30 && !found; rad++)
                for (int dy = -rad; dy <= rad && !found; dy++)
                    for (int dx = -rad; dx <= rad; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dy)) != rad) continue;
                        if (Tile.walkable(t(tx0 + dx, ty0 + dy))) {
                            sx = tx0 + dx;
                            sy = ty0 + dy;
                            found = true;
                            break;
                        }
                    }
            if (!found) return road ? null : new float[]{x, y};
        }
        // breadth-first over walkable tiles from there
        int R = 30, D = R * 2 + 1;
        int[] q = new int[D * D];
        boolean[] seen = new boolean[D * D];
        int head = 0, tail = 0;
        q[tail++] = (sy - ty0 + R) * D + (sx - tx0 + R);
        seen[q[0]] = true;
        int[] DX = {1, -1, 0, 0}, DY = {0, 0, 1, -1};
        while (head < tail) {
            int c = q[head++];
            int cx = c % D - R + tx0, cy = c / D - R + ty0;
            int tt = t(cx, cy);
            if (road ? (tt == Tile.ROAD || tt == Tile.HIGHWAY) : openAround(cx, cy))
                return new float[]{cx * Tile.SIZE + Tile.SIZE / 2f, cy * Tile.SIZE + Tile.SIZE / 2f};
            for (int d = 0; d < 4; d++) {
                int nx = cx + DX[d] - tx0 + R, ny = cy + DY[d] - ty0 + R;
                if (nx < 0 || ny < 0 || nx >= D || ny >= D) continue;
                int k = ny * D + nx;
                if (seen[k] || !Tile.walkable(t(cx + DX[d], cy + DY[d]))) continue;
                seen[k] = true;
                q[tail++] = k;
            }
        }
        return road ? null : new float[]{sx * Tile.SIZE + Tile.SIZE / 2f, sy * Tile.SIZE + Tile.SIZE / 2f};
    }

    /** Moves a facility's yard (where its people come out) onto open ground if it's inside a building. */
    public void snapToOpenGround(Facility f) {
        int tx = (int) Math.floor(f.x / Tile.SIZE), ty = (int) Math.floor(f.y / Tile.SIZE);
        if (openAround(tx, ty)) return;
        float[] p = openGroundNear(f.x, f.y, false);
        f.x = p[0];
        f.y = p[1];
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
        if (bCount >= 65534) throw new IllegalStateException("too many buildings");
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
                    bldAt[ty * W + tx] = (char) (i + 1);
                }
        return i;
    }

    /** Turns a building into rubble (blasts, air strikes): people inside are the sector's problem. */
    public void destroyBuilding(int i) {
        for (int ty = by[i]; ty < by[i] + bh[i]; ty++)
            for (int tx = bx[i]; tx < bx[i] + bw[i]; tx++)
                if (in(tx, ty) && bldAt[ty * W + tx] == i + 1) {
                    tile[ty * W + tx] = Tile.RUBBLE;
                    bldAt[ty * W + tx] = 0;
                }
        bfloors[i] = 0;
        rebuildNav(bx[i] - 2, by[i] - 2, bx[i] + bw[i] + 2, by[i] + bh[i] + 2);
    }

    /** Map changes (rubble, blown bridges) for the painter to redraw: a counter and the changed tile rectangles. */
    public int changes;
    public final List<int[]> dirty = new ArrayList<int[]>();

    public void rebuildNav(int x0, int y0, int x1, int y1) {
        changes++;
        dirty.add(new int[]{x0, y0, x1, y1});
        if (dirty.size() > 64) dirty.remove(0);
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

    /** The capital (the biggest city). */
    public Settlement city() {
        for (Settlement s : settlements) if (s.capital) return s;
        for (Settlement s : settlements) if (s.kind == S_CITY) return s;
        return settlements.get(0);
    }

    public Facility addFacility(int kind, int settlement, float x, float y, String name) {
        Facility f = new Facility();
        f.id = facilities.size();
        f.kind = kind;
        f.settlement = settlement;
        f.x = x;
        f.y = y;
        f.name = name;
        f.runtime = true;
        facilities.add(f);
        return f;
    }

    /** Roads crossed by a barricade are closed to routing (and opened again when it's gone). */
    public void closeBarricadedRoads() {
        closeBarricadedRoads(-1, -1, -1, -1);
    }

    /** The same, only for roads passing near the tiles (x0, y0)-(x1, y1) (all of them when x1 < 0). */
    public void closeBarricadedRoads(int x0, int y0, int x1, int y1) {
        RoadGraph g = roads;
        float px0 = x0 * Tile.SIZE - 48, py0 = y0 * Tile.SIZE - 48, px1 = (x1 + 1) * Tile.SIZE + 48, py1 = (y1 + 1) * Tile.SIZE + 48;
        for (int e = 0; e < g.edgeCount; e++) {
            float ax = g.nx[g.ea[e]], ay = g.ny[g.ea[e]], bx = g.nx[g.eb[e]], by = g.ny[g.eb[e]];
            if (x1 >= 0 && (Math.max(ax, bx) < px0 || Math.min(ax, bx) > px1 || Math.max(ay, by) < py0 || Math.min(ay, by) > py1)) continue;
            float dx = bx - ax, dy = by - ay, len = (float) Math.sqrt(dx * dx + dy * dy);
            int steps = (int) (len / 8) + 1;
            boolean blocked = false;
            float ux = dx / (len + 0.01f), uy = dy / (len + 0.01f);
            float h = g.ehalf[e] * 0.9f;
            // along the road and a little past each junction (a wall across the corner), across its whole width
            for (int k = -1; k <= steps + 1 && !blocked; k++) {
                float along = k < 0 ? -h * 0.5f : k > steps ? len + h * 0.5f : len * k / steps;
                float x = ax + ux * along, y = ay + uy * along;
                for (int j = -2; j <= 2 && !blocked; j++)
                    if (tileAtPx(x - uy * h * j * 0.5f, y + ux * h * j * 0.5f) == Tile.BARRICADE) blocked = true;
            }
            if (blocked) g.epen[e] = Math.max(g.epen[e], 1e6f);
            else if (g.epen[e] >= 1e6f) g.epen[e] = 0;
        }
    }

    /** A checksum of the map, for tests and saves. */
    public long checksum() {
        long h = 1469598103934665603L;
        for (byte b : tile) h = (h ^ b) * 1099511628211L;
        h ^= bCount * 31L + roads.nodeCount * 7L + roads.edgeCount;
        return h;
    }
}
