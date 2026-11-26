package com.instantaxtion.zcs2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a region from a seed: terrain, rivers, a city, towns, villages, an army base, a prison, highways between
 * them, and every building and facility. The same seed, size and preset always build the same region.
 */
public final class RegionGen {
    public static final int COAST = 0, VALLEY = 1, HIGHLANDS = 2, LAKES = 3;
    public static final String[] PRESETS = {"Coast", "River Valley", "Highlands", "Lake District"};
    public static final int[] SIZES = {768, 1024, 1536};
    public static final String[] SIZE_NAMES = {"Small", "Default", "Large"};

    private final Region r;
    private final Rng rng;
    private final Noise n1, n2, n3;
    private final float[] height;
    private final int W, H;
    private final float scale;
    private final List<int[]> highwayCells = new ArrayList<int[]>();

    private RegionGen(int size, int preset, long seed) {
        r = new Region(size, size, seed, preset);
        rng = new Rng(seed * 31 + preset * 7 + size);
        n1 = new Noise(seed + 1);
        n2 = new Noise(seed + 2);
        n3 = new Noise(seed + 3);
        W = size;
        H = size;
        height = new float[W * H];
        scale = size / 1024f;
    }

    public static Region generate(int size, int preset, long seed) {
        RegionGen g = new RegionGen(size, preset, seed);
        g.terrain();
        g.rivers();
        g.placeSettlements();
        for (Region.Settlement s : g.r.settlements) {
            if (s.kind == Region.S_BASE) g.layoutBase(s);
            else if (s.kind == Region.S_PRISON) g.layoutPrison(s);
            else g.layoutTown(s);
        }
        g.highways();
        g.countryside();
        g.r.roads.finish(g.r.px());
        g.r.rebuildNav(0, 0, g.W - 1, g.H - 1);
        g.powerGrids();
        g.r.name = g.r.city().name + " Region";
        return g.r;
    }

    // ------------------------------------------------------------------ terrain

    private void terrain() {
        int side = rng.nextInt(4);
        float riverBias = rng.range(0.35f, 0.65f);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                float base = n1.fbm(x / (190f * scale), y / (190f * scale), 5);
                float h;
                float fx = x / (float) W, fy = y / (float) H;
                switch (r.preset) {
                    case COAST: {
                        float d = side == 0 ? fx : side == 1 ? 1 - fx : side == 2 ? fy : 1 - fy;
                        h = 0.18f + base * 0.45f + Math.min(d - 0.2f, 0.22f) * 1.4f;
                        break;
                    }
                    case HIGHLANDS: {
                        float ridge = 1 - Math.abs(n3.fbm(x / (260f * scale), y / (260f * scale), 4)) * 2;
                        h = 0.3f + base * 0.5f + Math.max(0, ridge) * 0.45f;
                        break;
                    }
                    case LAKES: {
                        float lake = n3.fbm(x / (120f * scale), y / (120f * scale), 3);
                        h = 0.25f + base * 0.4f - Math.max(0, lake - 0.22f) * 2.5f;
                        break;
                    }
                    default: {
                        float c = (side & 1) == 0 ? fx : fy;
                        h = 0.25f + base * 0.45f + Math.abs(c - riverBias) * 0.5f;
                    }
                }
                height[y * W + x] = h;
                byte t;
                if (h < -0.1f) t = Tile.DEEP;
                else if (h < 0) t = Tile.WATER;
                else if (h < 0.025f) t = Tile.SAND;
                else if (h > 0.92f) t = Tile.ROCK;
                else t = Tile.GRASS;
                if (t == Tile.GRASS && h < 0.85f) {
                    float f = n2.fbm(x / (70f * scale), y / (70f * scale), 4);
                    if (f > 0.2f) t = Tile.FOREST;
                }
                r.tile[y * W + x] = t;
            }
        // the sea along the coast, deep further out
        if (r.preset == COAST) sideOfSea = side;
    }

    private int sideOfSea = -1;

    private void rivers() {
        int count = r.preset == VALLEY ? 1 : r.preset == LAKES ? 2 : r.preset == HIGHLANDS ? 1 : rng.range(1, 2);
        for (int i = 0; i < count; i++) {
            float x, y, dx, dy;
            float width = r.preset == VALLEY && i == 0 ? 7 : rng.range(4f, 5.5f);
            if (r.preset == COAST && sideOfSea >= 0) {
                // from deep inland to the sea
                int s = sideOfSea;
                float along = rng.range(0.25f, 0.75f);
                if (s == 0) { x = W * 0.95f; y = H * along; dx = -1; dy = 0; }
                else if (s == 1) { x = W * 0.05f; y = H * along; dx = 1; dy = 0; }
                else if (s == 2) { x = W * along; y = H * 0.95f; dx = 0; dy = -1; }
                else { x = W * along; y = H * 0.05f; dx = 0; dy = 1; }
            } else {
                // edge to edge
                boolean vertical = rng.chance(0.5f);
                float along = rng.range(0.3f, 0.7f);
                if (vertical) { x = W * along; y = 0; dx = 0; dy = 1; }
                else { x = 0; y = H * along; dx = 1; dy = 0; }
            }
            float phase = rng.range(0, 100);
            int steps = (int) (W * 1.6f);
            boolean hitWater = false;
            for (int s = 0; s < steps; s++) {
                float wob = n3.value(s / (55f * scale) + phase, phase) * 1.1f;
                float px = -dy, py = dx;
                float mx = dx + px * wob, my = dy + py * wob;
                float len = (float) Math.sqrt(mx * mx + my * my);
                x += mx / len * 1.5f;
                y += my / len * 1.5f;
                if (x < -4 || y < -4 || x > W + 4 || y > H + 4) break;
                if (s > 40 && r.preset == COAST) {
                    int t = r.t((int) x, (int) y);
                    if (t == Tile.DEEP) {
                        hitWater = true;
                        break;
                    }
                }
                disc((int) x, (int) y, width / 2, Tile.WATER, true);
            }
            if (hitWater) continue;
        }
        // sandy banks
        byte[] t = r.tile;
        for (int y = 1; y < H - 1; y++)
            for (int x = 1; x < W - 1; x++) {
                int i = y * W + x;
                if (t[i] == Tile.GRASS || t[i] == Tile.FOREST) {
                    if ((t[i - 1] == Tile.DEEP || t[i + 1] == Tile.DEEP || t[i - W] == Tile.DEEP || t[i + W] == Tile.DEEP))
                        t[i] = Tile.SAND;
                }
            }
    }

    private void disc(int cx, int cy, float rad, byte k, boolean onlyLand) {
        int ri = (int) Math.ceil(rad);
        for (int y = cy - ri; y <= cy + ri; y++)
            for (int x = cx - ri; x <= cx + ri; x++) {
                if (!r.in(x, y)) continue;
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy > rad * rad + 0.5f) continue;
                int cur = r.tile[y * W + x];
                if (onlyLand && cur == Tile.DEEP) continue;
                r.tile[y * W + x] = k;
            }
    }

    // ------------------------------------------------------------------ settlements

    private float landFrac(int cx, int cy, int rad) {
        int land = 0, all = 0;
        int step = Math.max(2, rad / 10);
        for (int y = cy - rad; y <= cy + rad; y += step)
            for (int x = cx - rad; x <= cx + rad; x += step) {
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy > rad * rad) continue;
                all++;
                int t = r.t(x, y);
                if (!Tile.water(t) && t != Tile.ROCK) land++;
            }
        return all == 0 ? 0 : land / (float) all;
    }

    private float waterNear(int cx, int cy, int rad) {
        int water = 0, all = 0;
        for (int a = 0; a < 24; a++) {
            double ang = a * Math.PI / 12;
            int x = cx + (int) (Math.cos(ang) * rad), y = cy + (int) (Math.sin(ang) * rad);
            all++;
            if (Tile.water(r.t(x, y))) water++;
        }
        return water / (float) all;
    }

    private boolean farFromOthers(int cx, int cy, int rad, int gap) {
        for (Region.Settlement s : r.settlements) {
            float dx = s.cx - cx, dy = s.cy - cy;
            float need = s.radius + rad + gap;
            if (dx * dx + dy * dy < need * need) return false;
        }
        return true;
    }

    private Region.Settlement addSettlement(int kind, int cx, int cy, int rad) {
        Region.Settlement s = new Region.Settlement();
        s.id = r.settlements.size();
        s.kind = kind;
        s.cx = cx;
        s.cy = cy;
        s.radius = rad;
        s.name = Names.place(rng, kind);
        r.settlements.add(s);
        return s;
    }

    private void placeSettlements() {
        // the city
        int rc = (int) (W * 0.14f);
        int bestX = W / 2, bestY = H / 2;
        float best = -9;
        for (int i = 0; i < 120; i++) {
            int x = rng.range((int) (W * 0.28f), (int) (W * 0.72f)), y = rng.range((int) (H * 0.28f), (int) (H * 0.72f));
            float lf = landFrac(x, y, rc);
            if (lf < 0.72f) continue;
            float score = lf + Math.min(0.25f, waterNear(x, y, (int) (rc * 1.3f))) * 1.2f + rng.nextFloat() * 0.15f;
            if (score > best) {
                best = score;
                bestX = x;
                bestY = y;
            }
        }
        addSettlement(Region.S_CITY, bestX, bestY, rc);
        // towns
        int towns = W <= 768 ? 3 : W <= 1024 ? 4 : 6;
        placeMany(Region.S_TOWN, towns, (int) (42 * scale), (int) (62 * scale) + 4, 70, 0.72f);
        // the army base, out of town
        int baseR = (int) (34 * Math.max(1f, scale * 0.9f));
        for (int i = 0; i < 400; i++) {
            double ang = rng.range(0f, 6.283f);
            float dist = rc + rng.range(70f, 200f) * scale + 40;
            int x = bestX + (int) (Math.cos(ang) * dist), y = bestY + (int) (Math.sin(ang) * dist);
            if (x < baseR + 20 || y < baseR + 20 || x > W - baseR - 20 || y > H - baseR - 20) continue;
            if (landFrac(x, y, baseR + 4) < 0.98f || !farFromOthers(x, y, baseR, 40)) continue;
            addSettlement(Region.S_BASE, x, y, baseR);
            break;
        }
        // the prison, near a town
        int prisonR = 22;
        outer:
        for (int i = 0; i < 400; i++) {
            Region.Settlement t = r.settlements.get(1 + rng.nextInt(Math.max(1, Math.min(towns, r.settlements.size() - 1))));
            double ang = rng.range(0f, 6.283f);
            float dist = t.radius + rng.range(40f, 90f);
            int x = t.cx + (int) (Math.cos(ang) * dist), y = t.cy + (int) (Math.sin(ang) * dist);
            if (x < 40 || y < 40 || x > W - 40 || y > H - 40) continue;
            if (landFrac(x, y, prisonR + 3) < 0.98f || !farFromOthers(x, y, prisonR, 30)) continue;
            addSettlement(Region.S_PRISON, x, y, prisonR);
            break outer;
        }
        // villages
        placeMany(Region.S_VILLAGE, W / 120, 15, 20, 34, 0.85f);
        // clear trees and rock inside settlements
        for (Region.Settlement s : r.settlements) {
            int rad = s.radius + 3;
            for (int y = s.cy - rad; y <= s.cy + rad; y++)
                for (int x = s.cx - rad; x <= s.cx + rad; x++) {
                    if (!r.in(x, y)) continue;
                    float dx = x - s.cx, dy = y - s.cy;
                    if (dx * dx + dy * dy > rad * rad) continue;
                    int t = r.tile[y * W + x];
                    if (t == Tile.FOREST || t == Tile.ROCK || t == Tile.SAND) r.tile[y * W + x] = Tile.GRASS;
                }
        }
    }

    private void placeMany(int kind, int count, int rmin, int rmax, int gap, float land) {
        for (int n = 0; n < count; n++) {
            for (int i = 0; i < 300; i++) {
                int rad = rng.range(rmin, rmax);
                int x = rng.range(rad + 24, W - rad - 24), y = rng.range(rad + 24, H - rad - 24);
                if (landFrac(x, y, rad) < land || !farFromOthers(x, y, rad, gap)) continue;
                addSettlement(kind, x, y, rad);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ towns and the city

    private static final class Block {
        int x0, y0, x1, y1, k, j;
        float d; // distance from the centre, 0..1 of the radius
        boolean used;
    }

    private int hwLine(Region.Settlement s, int k) {
        if (s.kind == Region.S_CITY) return k % 4 == 0 ? 3 : 2;
        if (s.kind == Region.S_TOWN) return k % 3 == 0 ? 2 : 1;
        return k == 0 ? 2 : 1;
    }

    private void layoutTown(Region.Settlement s) {
        int P = s.kind == Region.S_CITY ? 20 : s.kind == Region.S_TOWN ? 16 : 14;
        int R = s.radius;
        int kmax = R / P + 1;
        int n = kmax * 2 + 1;
        boolean[] in = new boolean[n * n];
        int[] node = new int[n * n];
        Arrays.fill(node, -1);
        float phase = s.id * 13.7f;
        for (int j = -kmax; j <= kmax; j++)
            for (int k = -kmax; k <= kmax; k++) {
                int x = s.cx + k * P, y = s.cy + j * P;
                float dx = x - s.cx, dy = y - s.cy;
                double ang = Math.atan2(dy, dx);
                float lim = R * (1 + 0.14f * n3.value((float) Math.cos(ang) * 1.7f + phase, (float) Math.sin(ang) * 1.7f + phase));
                if (s.kind == Region.S_VILLAGE && (Math.abs(j) > 1 || Math.abs(k) > 1)) continue;
                boolean ok = dx * dx + dy * dy <= lim * lim && r.in(x, y) && !Tile.water(r.t(x, y));
                in[(j + kmax) * n + (k + kmax)] = ok;
            }
        // streets
        for (int j = -kmax; j <= kmax; j++)
            for (int k = -kmax; k <= kmax; k++) {
                if (!in[(j + kmax) * n + (k + kmax)]) continue;
                if (k < kmax && in[(j + kmax) * n + (k + 1 + kmax)]) street(s, k, j, k + 1, j, P, node, kmax, n);
                if (j < kmax && in[(j + 1 + kmax) * n + (k + kmax)]) street(s, k, j, k, j + 1, P, node, kmax, n);
            }
        // the hub: the junction nearest the centre
        float bd = 1e9f;
        for (int i = 0; i < n * n; i++)
            if (node[i] >= 0) {
                float dx = r.roads.nx[node[i]] / 16 - s.cx, dy = r.roads.ny[node[i]] / 16 - s.cy;
                if (dx * dx + dy * dy < bd) {
                    bd = dx * dx + dy * dy;
                    s.hubNode = node[i];
                }
            }
        // the edge junctions, where highways join
        for (int j = -kmax; j <= kmax; j++)
            for (int k = -kmax; k <= kmax; k++) {
                int id = node[(j + kmax) * n + (k + kmax)];
                if (id < 0) continue;
                boolean edge = false;
                int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] d : nb) {
                    int kk = k + d[0], jj = j + d[1];
                    if (kk < -kmax || kk > kmax || jj < -kmax || jj > kmax || !in[(jj + kmax) * n + (kk + kmax)]) edge = true;
                }
                if (edge) s.edgeNodes.add(id);
            }
        // blocks
        List<Block> blocks = new ArrayList<Block>();
        for (int j = -kmax; j < kmax; j++)
            for (int k = -kmax; k < kmax; k++) {
                if (node[(j + kmax) * n + (k + kmax)] < 0 || node[(j + kmax) * n + (k + 1 + kmax)] < 0
                        || node[(j + 1 + kmax) * n + (k + kmax)] < 0 || node[(j + 1 + kmax) * n + (k + 1 + kmax)] < 0)
                    continue;
                Block b = new Block();
                b.k = k;
                b.j = j;
                b.x0 = s.cx + k * P + hwLine(s, k) + 1;
                b.x1 = s.cx + (k + 1) * P - hwLine(s, k + 1) - 1;
                b.y0 = s.cy + j * P + hwLine(s, j) + 1;
                b.y1 = s.cy + (j + 1) * P - hwLine(s, j + 1) - 1;
                int water = 0, all = 0;
                for (int y = b.y0; y <= b.y1; y++)
                    for (int x = b.x0; x <= b.x1; x++) {
                        all++;
                        if (!Tile.walkable(r.t(x, y)) || Tile.roadish(r.t(x, y))) water++;
                    }
                if (water > 0) continue;
                float cx = (b.x0 + b.x1) / 2f - s.cx, cy = (b.y0 + b.y1) / 2f - s.cy;
                b.d = (float) Math.sqrt(cx * cx + cy * cy) / R;
                blocks.add(b);
            }
        // pavement round every block
        for (Block b : blocks)
            for (int y = b.y0; y <= b.y1; y++)
                for (int x = b.x0; x <= b.x1; x++)
                    r.set(x, y, (x == b.x0 || x == b.x1 || y == b.y0 || y == b.y1) ? Tile.PAVEMENT : Tile.GRASS);
        facilitiesFor(s, blocks);
        for (Block b : blocks) if (!b.used) fillBlock(s, b);
        s.residents = 0;
        for (int i = 0; i < r.bCount; i++) if (r.bset[i] == s.id) s.residents += r.bres[i];
    }

    private void street(Region.Settlement s, int k0, int j0, int k1, int j1, int P, int[] node, int kmax, int n) {
        int x0 = s.cx + k0 * P, y0 = s.cy + j0 * P, x1 = s.cx + k1 * P, y1 = s.cy + j1 * P;
        boolean horiz = j0 == j1;
        int hw = horiz ? hwLine(s, j0) : hwLine(s, k0);
        // over water only on main streets (as bridges)
        int water = 0;
        for (int i = 0; i <= P; i++) {
            int x = horiz ? x0 + i : x0, y = horiz ? y0 : y0 + i;
            if (Tile.water(r.t(x, y))) water++;
        }
        if (water > 0 && hw < (s.kind == Region.S_CITY ? 3 : 2)) return;
        if (water > P - 2) return;
        int ha = horiz ? hwLine(s, k0) : hwLine(s, j0), hb = horiz ? hwLine(s, k1) : hwLine(s, j1);
        boolean bridge = false;
        if (horiz) {
            for (int x = x0 - ha; x <= x1 + hb; x++)
                for (int y = y0 - hw; y <= y0 + hw; y++) bridge |= paveRoad(x, y);
        } else {
            for (int y = y0 - ha; y <= y1 + hb; y++)
                for (int x = x0 - hw; x <= x0 + hw; x++) bridge |= paveRoad(x, y);
        }
        int a = nodeAt(s, k0, j0, P, node, kmax, n), b = nodeAt(s, k1, j1, P, node, kmax, n);
        int e = r.roads.addEdge(a, b, hw >= 3 || (hw >= 2 && s.kind != Region.S_CITY) ? RoadGraph.AVENUE : RoadGraph.STREET, (hw + 0.5f) * Tile.SIZE);
        if (e >= 0) r.roads.ebridge[e] = bridge;
    }

    /** Paves one road tile; true if it had to be a bridge. */
    private boolean paveRoad(int x, int y) {
        if (!r.in(x, y)) return false;
        int t = r.tile[y * W + x];
        if (Tile.water(t) || t == Tile.BRIDGE) {
            r.tile[y * W + x] = Tile.BRIDGE;
            return true;
        }
        if (t != Tile.BUILDING) r.tile[y * W + x] = Tile.ROAD;
        return false;
    }

    private int nodeAt(Region.Settlement s, int k, int j, int P, int[] node, int kmax, int n) {
        int i = (j + kmax) * n + (k + kmax);
        if (node[i] < 0) node[i] = r.roads.addNode((s.cx + k * P) * 16 + 8, (s.cy + j * P) * 16 + 8, s.id);
        return node[i];
    }

    private Block pickBlock(List<Block> blocks, float dmin, float dmax, int minSide) {
        Block best = null;
        float bs = -1;
        for (Block b : blocks) {
            if (b.used || b.d < dmin || b.d > dmax) continue;
            if (b.x1 - b.x0 + 1 < minSide || b.y1 - b.y0 + 1 < minSide) continue;
            // spread facilities apart: prefer blocks far from facilities already placed in this settlement
            float far = 1e9f;
            for (Region.Facility f : r.facilities) {
                float dx = f.x / 16 - (b.x0 + b.x1) / 2f, dy = f.y / 16 - (b.y0 + b.y1) / 2f;
                far = Math.min(far, dx * dx + dy * dy);
            }
            float score = (float) Math.sqrt(Math.min(far, 1e6f)) + rng.nextFloat() * 6;
            if (score > bs) {
                bs = score;
                best = b;
            }
        }
        return best;
    }

    private void facilitiesFor(Region.Settlement s, List<Block> blocks) {
        if (s.kind == Region.S_CITY) {
            int stations = W >= 1536 ? 8 : W >= 1024 ? 6 : 5;
            for (int i = 0; i < stations; i++) facility(s, pickBlock(blocks, 0.1f, 0.85f, 8), Region.F_POLICE);
            facility(s, pickBlock(blocks, 0.1f, 0.6f, 9), Region.F_HOSPITAL);
            facility(s, pickBlock(blocks, 0.4f, 0.9f, 9), Region.F_HOSPITAL);
            facility(s, pickBlock(blocks, 0.75f, 1.2f, 9), Region.F_POWER);
            facility(s, pickBlock(blocks, 0.7f, 1.2f, 8), Region.F_FUEL);
            facility(s, pickBlock(blocks, 0f, 0.3f, 8), Region.F_LAB);
            facility(s, pickBlock(blocks, 0.3f, 0.7f, 9), Region.F_DEPOT);
            // a big central park, where the festival happens
            Block park = null;
            float bd = 9;
            for (Block b : blocks)
                if (!b.used && Math.abs(b.d - 0.3f) < bd && b.x1 - b.x0 >= 9) {
                    bd = Math.abs(b.d - 0.3f);
                    park = b;
                }
            facility(s, park, Region.F_PARK);
        } else if (s.kind == Region.S_TOWN) {
            int stations = s.radius > 44 ? 2 : 1;
            for (int i = 0; i < stations; i++) facility(s, pickBlock(blocks, 0f, 0.8f, 7), Region.F_POLICE);
            facility(s, pickBlock(blocks, 0f, 0.7f, 8), Region.F_HOSPITAL);
            facility(s, pickBlock(blocks, 0.4f, 1.2f, 7), Region.F_FUEL);
            facility(s, pickBlock(blocks, 0.3f, 1.2f, 8), Region.F_ARMORY);
            if (s.id == largestTownId()) facility(s, pickBlock(blocks, 0.6f, 1.3f, 8), Region.F_POWER);
        }
    }

    private int largestTownId() {
        int best = -1, br = -1;
        for (Region.Settlement s : r.settlements)
            if (s.kind == Region.S_TOWN && s.radius > br) {
                br = s.radius;
                best = s.id;
            }
        return best;
    }

    private Region.Facility facility(Region.Settlement s, Block b, int kind) {
        if (b == null) return null;
        b.used = true;
        Region.Facility f = new Region.Facility();
        f.id = r.facilities.size();
        f.kind = kind;
        f.settlement = s.id;
        f.bx0 = b.x0;
        f.by0 = b.y0;
        f.bx1 = b.x1;
        f.by1 = b.y1;
        int w = b.x1 - b.x0 + 1, h = b.y1 - b.y0 + 1;
        // inner area (inside the pavement ring)
        int ix0 = b.x0 + 1, iy0 = b.y0 + 1, ix1 = b.x1 - 1, iy1 = b.y1 - 1;
        for (int y = iy0; y <= iy1; y++) for (int x = ix0; x <= ix1; x++) r.set(x, y, Tile.LOT);
        int bkind, floors, bwid, bhei;
        switch (kind) {
            case Region.F_POLICE: bkind = Region.B_POLICE; floors = 3; bwid = w / 2; bhei = h - 4; break;
            case Region.F_HOSPITAL: bkind = Region.B_HOSPITAL; floors = 7; bwid = w - 4; bhei = h / 2; break;
            case Region.F_POWER: bkind = Region.B_POWER; floors = 4; bwid = w / 2; bhei = h / 2; break;
            case Region.F_FUEL: bkind = Region.B_FUEL; floors = 2; bwid = w / 3; bhei = h / 3; break;
            case Region.F_LAB: bkind = Region.B_LAB; floors = 9; bwid = w - 4; bhei = h - 5; break;
            case Region.F_DEPOT: bkind = Region.B_DEPOT; floors = 2; bwid = w - 4; bhei = h / 3; break;
            case Region.F_ARMORY: bkind = Region.B_ARMORY; floors = 2; bwid = w / 2; bhei = h / 2; break;
            case Region.F_PARK: bkind = -1; floors = 0; bwid = 0; bhei = 0; break;
            default: bkind = Region.B_OFFICE; floors = 3; bwid = w / 2; bhei = h / 2;
        }
        if (kind == Region.F_PARK) {
            for (int y = iy0; y <= iy1; y++)
                for (int x = ix0; x <= ix1; x++) {
                    boolean path = (x - ix0) == (ix1 - ix0) / 2 || (y - iy0) == (iy1 - iy0) / 2;
                    r.set(x, y, path ? Tile.PLAZA : (rng.chance(0.12f) ? Tile.TREE : Tile.PARK));
                }
        } else {
            bwid = Math.max(3, bwid);
            bhei = Math.max(3, bhei);
            int bx = ix0 + 1, by = iy0 + 1;
            if (kind == Region.F_HOSPITAL || kind == Region.F_DEPOT) by = iy0 + 1;
            int res = kind == Region.F_HOSPITAL ? 180 : kind == Region.F_LAB ? 90 : 0;
            f.building = r.addBuilding(bx, by, Math.min(bwid, ix1 - bx), Math.min(bhei, iy1 - by), bkind, floors, s.id, res, roofFor(bkind));
            if (kind == Region.F_POWER) {
                // cooling towers and transformers as small round-ish blocks
                r.addBuilding(ix1 - 3, iy1 - 3, 3, 3, Region.B_POWER, 6, s.id, 0, 0xFFB8B4AA);
            }
        }
        // the yard: the far corner of the lot
        f.x = ((ix0 + ix1) / 2f + (kind == Region.F_POLICE ? (ix1 - ix0) / 4f : 0)) * 16 + 8;
        f.y = (iy1 - 1) * 16 + 8;
        if (kind == Region.F_PARK) {
            f.x = (ix0 + ix1) / 2f * 16 + 8;
            f.y = (iy0 + iy1) / 2f * 16 + 8;
        }
        f.name = s.name + " " + Region.FNAMES[kind];
        r.facilities.add(f);
        return f;
    }

    private int roofFor(int kind) {
        int[] pal;
        switch (kind) {
            case Region.B_HOUSE: pal = new int[]{0xFF9A5B44, 0xFF7E5A4A, 0xFF6B6F78, 0xFF8C4A3C, 0xFF5E5A56, 0xFFA0785A}; break;
            case Region.B_APARTMENT: pal = new int[]{0xFF8A8580, 0xFF7C7670, 0xFF9C948A, 0xFF6E6A66}; break;
            case Region.B_OFFICE: pal = new int[]{0xFF7A8088, 0xFF8C9298, 0xFF6C737C, 0xFF9AA0A6}; break;
            case Region.B_TOWER: pal = new int[]{0xFF5E6A78, 0xFF6E7A88, 0xFF4E5866, 0xFF80909E}; break;
            case Region.B_SHOP: pal = new int[]{0xFFA89A84, 0xFF9C8C74, 0xFFB4A48A}; break;
            case Region.B_WAREHOUSE: pal = new int[]{0xFF8E9296, 0xFF7E8488, 0xFF9AA0A0}; break;
            case Region.B_POLICE: return 0xFF4A5A7A;
            case Region.B_HOSPITAL: return 0xFFD8D8D4;
            case Region.B_POWER: return 0xFF8A7A6A;
            case Region.B_FUEL: return 0xFFB0A060;
            case Region.B_LAB: return 0xFFC8D0D4;
            case Region.B_BARRACKS: return 0xFF6A6E4E;
            case Region.B_HANGAR: return 0xFF7A7E6A;
            case Region.B_ARMORY: return 0xFF6E7250;
            case Region.B_DEPOT: return 0xFF8A8A70;
            case Region.B_CELLBLOCK: return 0xFF8E8A84;
            case Region.B_FARMHOUSE: pal = new int[]{0xFF8C4A3C, 0xFF6E5040}; break;
            case Region.B_BARN: return 0xFF9A3A2E;
            case Region.B_CHURCH: return 0xFF7A6A5A;
            case Region.B_SCHOOL: return 0xFFA07A5A;
            case Region.B_MALL: return 0xFFA8A8A0;
            default: return 0xFF888888;
        }
        return pal[rng.nextInt(pal.length)];
    }

    private void fillBlock(Region.Settlement s, Block b) {
        int ix0 = b.x0 + 1, iy0 = b.y0 + 1, ix1 = b.x1 - 1, iy1 = b.y1 - 1;
        int w = ix1 - ix0 + 1, h = iy1 - iy0 + 1;
        if (w < 3 || h < 3) return;
        boolean city = s.kind == Region.S_CITY;
        float d = b.d;
        if (rng.chance(city ? 0.05f : 0.07f)) { // a small park
            for (int y = iy0; y <= iy1; y++) for (int x = ix0; x <= ix1; x++) r.set(x, y, rng.chance(0.18f) ? Tile.TREE : Tile.PARK);
            return;
        }
        boolean industrial = n2.value(b.k * 0.6f + s.id, b.j * 0.6f) > 0.35f && d > 0.45f;
        if (city && d < 0.24f) {
            for (int y = iy0; y <= iy1; y++) for (int x = ix0; x <= ix1; x++) r.set(x, y, Tile.PLAZA);
            for (int[] lot : split(ix0, iy0, ix1, iy1, 4, 3)) {
                int lw = lot[2] - lot[0] + 1, lh = lot[3] - lot[1] + 1;
                if (lw < 3 || lh < 3) continue;
                boolean tower = lw * lh >= 40 && rng.chance(0.45f);
                int floors = tower ? rng.range(14, 40) : rng.range(5, 13);
                int kind = tower ? Region.B_TOWER : Region.B_OFFICE;
                if (rng.chance(0.08f)) {
                    for (int y = lot[1]; y <= lot[3]; y++) for (int x = lot[0]; x <= lot[2]; x++) r.set(x, y, Tile.PLAZA);
                    continue;
                }
                r.addBuilding(lot[0], lot[1], lw, lh, kind, floors, s.id, (int) (lw * lh * floors * 0.28f), roofFor(kind));
            }
            return;
        }
        if ((city && d < 0.6f) || (s.kind == Region.S_TOWN && d < 0.3f) || industrial) {
            for (int y = iy0; y <= iy1; y++) for (int x = ix0; x <= ix1; x++) r.set(x, y, industrial ? Tile.LOT : Tile.PAVEMENT);
            for (int[] lot : split(ix0, iy0, ix1, iy1, 3, 4)) {
                int lw = lot[2] - lot[0] + 1, lh = lot[3] - lot[1] + 1;
                if (lw < 3 || lh < 3) continue;
                int kind, floors;
                float per;
                if (industrial) { kind = Region.B_WAREHOUSE; floors = rng.range(1, 2); per = 0.01f; }
                else if (rng.chance(0.03f) && lw * lh > 30) { kind = Region.B_MALL; floors = 2; per = 0.02f; }
                else if (rng.chance(0.04f)) { kind = rng.chance(0.5f) ? Region.B_CHURCH : Region.B_SCHOOL; floors = 2; per = 0.02f; }
                else if (rng.chance(0.3f)) { kind = Region.B_SHOP; floors = rng.range(1, 3); per = 0.4f; }
                else { kind = Region.B_APARTMENT; floors = city ? rng.range(3, 9) : rng.range(2, 4); per = 1.2f; }
                r.addBuilding(lot[0], lot[1], lw, lh, kind, floors, s.id, Math.max(0, (int) (lw * lh * floors * per)), roofFor(kind));
            }
            return;
        }
        // houses in rows along the long sides, yards between
        for (int y = iy0; y <= iy1; y++) for (int x = ix0; x <= ix1; x++) r.set(x, y, Tile.YARD);
        boolean wide = w >= h;
        int len = wide ? w : h, dep = wide ? h : w;
        int lotW = rng.range(5, 6);
        int rows = dep >= 9 ? 2 : 1;
        for (int row = 0; row < rows; row++) {
            for (int p = 0; p + 4 <= len; p += lotW) {
                int hw2 = Math.min(lotW - 1, rng.range(3, 4)), hd = Math.min(rng.range(3, 4), dep / rows - 1);
                if (hd < 3) continue;
                int along = p + (lotW - 1 - hw2) / 2 + 1;
                int across = row == 0 ? 1 : dep - 1 - hd;
                int x, y, bw, bh;
                if (wide) { x = ix0 + along - 1; y = iy0 + across; bw = hw2; bh = hd; }
                else { x = ix0 + across; y = iy0 + along - 1; bw = hd; bh = hw2; }
                if (x + bw - 1 > ix1 || y + bh - 1 > iy1) continue;
                r.addBuilding(x, y, bw, bh, Region.B_HOUSE, rng.chance(0.3f) ? 2 : 1, s.id, rng.range(2, 6), roofFor(Region.B_HOUSE));
            }
        }
        for (int y = iy0; y <= iy1; y++)
            for (int x = ix0; x <= ix1; x++)
                if (r.t(x, y) == Tile.YARD && rng.chance(0.06f)) r.set(x, y, Tile.TREE);
    }

    /** Splits a rectangle into lots with a one-tile gap between them. */
    private List<int[]> split(int x0, int y0, int x1, int y1, int minSide, int depth) {
        List<int[]> out = new ArrayList<int[]>();
        splitInto(out, x0, y0, x1, y1, minSide, depth);
        return out;
    }

    private void splitInto(List<int[]> out, int x0, int y0, int x1, int y1, int minSide, int depth) {
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        if (depth <= 0 || (w < minSide * 2 + 1 && h < minSide * 2 + 1) || (depth < 3 && rng.chance(0.15f))) {
            out.add(new int[]{x0, y0, x1, y1});
            return;
        }
        if (w >= h && w >= minSide * 2 + 1) {
            int cut = x0 + rng.range(minSide, w - minSide - 1);
            splitInto(out, x0, y0, cut - 1, y1, minSide, depth - 1);
            splitInto(out, cut + 1, y0, x1, y1, minSide, depth - 1);
        } else if (h >= minSide * 2 + 1) {
            int cut = y0 + rng.range(minSide, h - minSide - 1);
            splitInto(out, x0, y0, x1, cut - 1, minSide, depth - 1);
            splitInto(out, x0, cut + 1, x1, y1, minSide, depth - 1);
        } else out.add(new int[]{x0, y0, x1, y1});
    }

    // ------------------------------------------------------------------ the army base and the prison

    private void layoutBase(Region.Settlement s) {
        int hw = s.radius, hh = (int) (s.radius * 0.8f);
        int x0 = s.cx - hw, y0 = s.cy - hh, x1 = s.cx + hw, y1 = s.cy + hh;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                boolean edge = x == x0 || x == x1 || y == y0 || y == y1;
                r.set(x, y, edge ? Tile.FENCE : Tile.BASE);
            }
        // a cross of roads with gates on all four sides
        int[] nodes = new int[5];
        nodes[0] = r.roads.addNode(s.cx * 16 + 8, s.cy * 16 + 8, s.id);
        int[][] ends = {{x0, s.cy}, {x1, s.cy}, {s.cx, y0}, {s.cx, y1}};
        for (int i = 0; i < 4; i++) {
            int ex = ends[i][0], ey = ends[i][1];
            nodes[i + 1] = r.roads.addNode(ex * 16 + 8, ey * 16 + 8, s.id);
            r.roads.addEdge(nodes[0], nodes[i + 1], RoadGraph.AVENUE, 2.5f * 16);
            int ax = Math.min(ex, s.cx), bx = Math.max(ex, s.cx), ay = Math.min(ey, s.cy), by = Math.max(ey, s.cy);
            for (int y = ay - 2; y <= by + 2; y++)
                for (int x = ax - 2; x <= bx + 2; x++)
                    if ((ax == bx ? Math.abs(x - ax) <= 2 : Math.abs(y - ay) <= 2)) r.set(x, y, Tile.ROAD);
            s.edgeNodes.add(nodes[i + 1]);
        }
        s.hubNode = nodes[0];
        // buildings in the four quarters
        int q = 0;
        int[][] quads = {{x0 + 2, y0 + 2, s.cx - 4, s.cy - 4}, {s.cx + 4, y0 + 2, x1 - 2, s.cy - 4},
                {x0 + 2, s.cy + 4, s.cx - 4, y1 - 2}, {s.cx + 4, s.cy + 4, x1 - 2, y1 - 2}};
        Region.Facility base = null;
        for (int[] qd : quads) {
            int qw = qd[2] - qd[0] + 1, qh = qd[3] - qd[1] + 1;
            if (q == 0) {
                // barracks rows
                for (int yy = qd[1]; yy + 5 <= qd[3]; yy += 8)
                    for (int xx = qd[0]; xx + 10 <= qd[2]; xx += 13)
                        r.addBuilding(xx, yy, 11, 5, Region.B_BARRACKS, 2, s.id, 0, roofFor(Region.B_BARRACKS));
            } else if (q == 1) {
                // motor pool: hangars along the top, a big lot below
                for (int xx = qd[0]; xx + 8 <= qd[2]; xx += 10)
                    r.addBuilding(xx, qd[1], 8, 7, Region.B_HANGAR, 2, s.id, 0, roofFor(Region.B_HANGAR));
                for (int yy = qd[1] + 8; yy <= qd[3]; yy++) for (int xx = qd[0]; xx <= qd[2]; xx++) r.set(xx, yy, Tile.LOT);
            } else if (q == 2) {
                // helipads
                for (int yy = qd[1] + 2; yy + 6 <= qd[3]; yy += 10)
                    for (int xx = qd[0] + 2; xx + 6 <= qd[2]; xx += 10) {
                        for (int y = yy; y < yy + 6; y++) for (int x = xx; x < xx + 6; x++) r.set(x, y, Tile.PLAZA);
                        Region.Facility f = new Region.Facility();
                        f.id = r.facilities.size();
                        f.kind = Region.F_HELIPAD;
                        f.settlement = s.id;
                        f.x = (xx + 3) * 16;
                        f.y = (yy + 3) * 16;
                        f.name = s.name + " Helipad";
                        r.facilities.add(f);
                    }
            } else {
                // depot: fuel and ammunition
                int bw2 = Math.min(14, qw - 2), bh2 = Math.min(8, qh - 2);
                r.addBuilding(qd[0] + 1, qd[1] + 1, bw2, bh2, Region.B_DEPOT, 2, s.id, 0, roofFor(Region.B_DEPOT));
                r.addBuilding(qd[0] + 1, qd[1] + bh2 + 3, 6, 4, Region.B_FUEL, 1, s.id, 0, roofFor(Region.B_FUEL));
            }
            q++;
        }
        base = new Region.Facility();
        base.id = r.facilities.size();
        base.kind = Region.F_BASE;
        base.settlement = s.id;
        base.x = (s.cx + 8) * 16;
        base.y = (s.cy + 8) * 16;
        base.bx0 = x0;
        base.by0 = y0;
        base.bx1 = x1;
        base.by1 = y1;
        base.name = s.name;
        r.facilities.add(base);
    }

    private void layoutPrison(Region.Settlement s) {
        int hw = s.radius, hh = (int) (s.radius * 0.8f);
        int x0 = s.cx - hw, y0 = s.cy - hh, x1 = s.cx + hw, y1 = s.cy + hh;
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                boolean edge = x == x0 || x == x1 || y == y0 || y == y1;
                boolean edge2 = x == x0 + 2 || x == x1 - 2 || y == y0 + 2 || y == y1 - 2;
                r.set(x, y, edge ? Tile.WALL : edge2 && (x > x0 + 1 && x < x1 - 1 && y > y0 + 1 && y < y1 - 1) ? Tile.FENCE : Tile.YARD);
            }
        // the gate road in from the south
        int gx = s.cx;
        for (int y = s.cy; y <= y1 + 1; y++) for (int x = gx - 2; x <= gx + 2; x++) r.set(x, y, Tile.ROAD);
        for (int y = s.cy - 3; y <= s.cy + 3; y++) for (int x = s.cx - 8; x <= s.cx + 8; x++) r.set(x, y, Tile.PLAZA);
        int hub = r.roads.addNode(gx * 16 + 8, s.cy * 16 + 8, s.id);
        int gate = r.roads.addNode(gx * 16 + 8, (y1 + 1) * 16 + 8, s.id);
        r.roads.addEdge(hub, gate, RoadGraph.STREET, 2.5f * 16);
        s.hubNode = hub;
        s.edgeNodes.add(gate);
        int res = 0;
        int[][] blocks = {{x0 + 5, y0 + 5}, {s.cx + 4, y0 + 5}, {x0 + 5, s.cy + 5}, {s.cx + 4, s.cy + 5}};
        for (int[] b : blocks) {
            int bw2 = Math.min(10, (x1 - 5) - b[0]), bh2 = Math.min(6, (y1 - 5) - b[1]);
            if (b[0] <= gx + 3 && b[0] + bw2 >= gx - 3) bw2 = Math.max(3, gx - 3 - b[0]);
            if (bw2 >= 3 && bh2 >= 3) {
                r.addBuilding(b[0], b[1], bw2, bh2, Region.B_CELLBLOCK, 3, s.id, 160, roofFor(Region.B_CELLBLOCK));
                res += 160;
            }
        }
        s.residents = res;
        Region.Facility f = new Region.Facility();
        f.id = r.facilities.size();
        f.kind = Region.F_PRISON;
        f.settlement = s.id;
        f.x = gx * 16 + 8;
        f.y = (s.cy + 1) * 16 + 8;
        f.bx0 = x0;
        f.by0 = y0;
        f.bx1 = x1;
        f.by1 = y1;
        f.name = s.name;
        r.facilities.add(f);
    }

    // ------------------------------------------------------------------ highways

    private static final int HC = 4; // tiles per highway search cell

    private void highways() {
        List<Region.Settlement> ss = r.settlements;
        int n = ss.size();
        // a minimum spanning tree over every settlement, plus the city to each town
        boolean[] inTree = new boolean[n];
        inTree[0] = true;
        List<int[]> pairs = new ArrayList<int[]>();
        for (int added = 1; added < n; added++) {
            int ba = -1, bb = -1;
            float bd = Float.MAX_VALUE;
            for (int a = 0; a < n; a++) {
                if (!inTree[a]) continue;
                for (int b = 0; b < n; b++) {
                    if (inTree[b]) continue;
                    float d = dist2(ss.get(a), ss.get(b));
                    if (d < bd) {
                        bd = d;
                        ba = a;
                        bb = b;
                    }
                }
            }
            if (bb < 0) break;
            inTree[bb] = true;
            pairs.add(new int[]{ba, bb});
        }
        for (int b = 1; b < n; b++)
            if (ss.get(b).kind == Region.S_TOWN || ss.get(b).kind == Region.S_BASE) {
                boolean have = false;
                for (int[] p : pairs) if ((p[0] == 0 && p[1] == b) || (p[1] == 0 && p[0] == b)) have = true;
                if (!have && Math.sqrt(dist2(ss.get(0), ss.get(b))) < W * 0.55f) pairs.add(new int[]{0, b});
            }
        // longer roads first, so short ones join them
        for (int[] p : pairs) connect(ss.get(p[0]), ss.get(p[1]));
        // roads out of the region: where outside help comes from
        int exits = W >= 1536 ? 4 : 3;
        int[] sides = {0, 1, 2, 3};
        for (int i = 3; i > 0; i--) {
            int j = rng.nextInt(i + 1), t = sides[i];
            sides[i] = sides[j];
            sides[j] = t;
        }
        int made = 0;
        for (int si = 0; si < 4 && made < exits; si++) {
            int side = sides[si];
            // the land point on that side nearest the city
            Region.Settlement c = r.city();
            int bx = -1, by = -1;
            float bd = Float.MAX_VALUE;
            for (int k = 8; k < W - 8; k += 4) {
                int x = side == 0 ? 2 : side == 1 ? W - 3 : k, y = side == 2 ? 2 : side == 3 ? H - 3 : k;
                if (Tile.water(r.t(x, y)) || r.t(x, y) == Tile.ROCK) continue;
                float dx = x - c.cx, dy = y - c.cy, d = dx * dx + dy * dy + rng.nextFloat() * 4000;
                if (d < bd) {
                    bd = d;
                    bx = x;
                    by = y;
                }
            }
            if (bx < 0) continue;
            // from the nearest settlement to that point
            Region.Settlement from = null;
            float fd = Float.MAX_VALUE;
            for (Region.Settlement s : ss) {
                if (s.kind == Region.S_PRISON || s.kind == Region.S_VILLAGE) continue;
                float dx = bx - s.cx, dy = by - s.cy, d = dx * dx + dy * dy;
                if (d < fd) {
                    fd = d;
                    from = s;
                }
            }
            int entry = r.roads.addNode(bx * 16 + 8, by * 16 + 8, -1);
            int a = nearestEdgeNode(from, bx, by);
            if (a < 0) continue;
            if (route(a, entry, from, null)) {
                r.entryNodes.add(entry);
                made++;
            }
        }
    }

    private float dist2(Region.Settlement a, Region.Settlement b) {
        float dx = a.cx - b.cx, dy = a.cy - b.cy;
        return dx * dx + dy * dy;
    }

    private int nearestEdgeNode(Region.Settlement s, float tx, float ty) {
        int best = -1;
        float bd = Float.MAX_VALUE;
        List<Integer> list = s.edgeNodes.isEmpty() ? java.util.Collections.singletonList(s.hubNode) : s.edgeNodes;
        for (int id : list) {
            if (id < 0) continue;
            float dx = r.roads.nx[id] / 16 - tx, dy = r.roads.ny[id] / 16 - ty, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = id;
            }
        }
        return best;
    }

    private void connect(Region.Settlement a, Region.Settlement b) {
        int na = nearestEdgeNode(a, b.cx, b.cy), nb = nearestEdgeNode(b, a.cx, a.cy);
        if (na < 0 || nb < 0) return;
        route(na, nb, a, b);
    }

    private final Map<Long, Integer> highwayNodeAt = new HashMap<Long, Integer>();

    /** Finds a way for a highway from junction a to junction b over the terrain, and builds it. */
    private boolean route(int na, int nb, Region.Settlement sa, Region.Settlement sb) {
        int cw = W / HC, ch = H / HC;
        int sx = (int) (r.roads.nx[na] / 16 / HC), sy = (int) (r.roads.ny[na] / 16 / HC);
        int ex = (int) (r.roads.nx[nb] / 16 / HC), ey = (int) (r.roads.ny[nb] / 16 / HC);
        sx = clamp(sx, 0, cw - 1);
        sy = clamp(sy, 0, ch - 1);
        ex = clamp(ex, 0, cw - 1);
        ey = clamp(ey, 0, ch - 1);
        float[] g = new float[cw * ch];
        Arrays.fill(g, Float.MAX_VALUE);
        int[] prev = new int[cw * ch];
        Arrays.fill(prev, -1);
        boolean[] done = new boolean[cw * ch];
        // a simple binary heap
        int[] heap = new int[cw * ch * 4 + 4];
        float[] key = new float[heap.length];
        int hn = 0;
        int start = sy * cw + sx, goal = ey * cw + ex;
        g[start] = 0;
        heap[++hn] = start;
        key[hn] = 0;
        int[] dxs = {1, -1, 0, 0, 1, 1, -1, -1}, dys = {0, 0, 1, -1, 1, -1, 1, -1};
        while (hn > 0) {
            int c = heap[1];
            heap[1] = heap[hn];
            key[1] = key[hn];
            hn--;
            for (int i = 1; ; ) {
                int l = i * 2, rr = l + 1, m = i;
                if (l <= hn && key[l] < key[m]) m = l;
                if (rr <= hn && key[rr] < key[m]) m = rr;
                if (m == i) break;
                int t = heap[i]; heap[i] = heap[m]; heap[m] = t;
                float tk = key[i]; key[i] = key[m]; key[m] = tk;
                i = m;
            }
            if (done[c]) continue;
            done[c] = true;
            if (c == goal) break;
            int cx = c % cw, cy = c / cw;
            for (int d = 0; d < 8; d++) {
                int nx = cx + dxs[d], ny = cy + dys[d];
                if (nx < 0 || ny < 0 || nx >= cw || ny >= ch) continue;
                int ni = ny * cw + nx;
                if (done[ni]) continue;
                float cost = cellCost(nx, ny, sa, sb) * (d >= 4 ? 1.414f : 1f);
                float ng = g[c] + cost;
                if (ng < g[ni]) {
                    g[ni] = ng;
                    prev[ni] = c;
                    float hx = nx - ex, hy = ny - ey;
                    if (hn + 1 >= heap.length) continue;
                    heap[++hn] = ni;
                    key[hn] = ng + (float) Math.sqrt(hx * hx + hy * hy) * 0.9f;
                    for (int i = hn; i > 1 && key[i >> 1] > key[i]; i >>= 1) {
                        int t = heap[i]; heap[i] = heap[i >> 1]; heap[i >> 1] = t;
                        float tk = key[i]; key[i] = key[i >> 1]; key[i >> 1] = tk;
                    }
                }
            }
        }
        if (!done[goal]) return false;
        List<float[]> pts = new ArrayList<float[]>();
        for (int c = goal; c >= 0; c = prev[c]) pts.add(0, new float[]{(c % cw) * HC + HC / 2f, (c / cw) * HC + HC / 2f});
        // smooth the corners (Chaikin), keep the ends on the junctions
        pts.set(0, new float[]{r.roads.nx[na] / 16, r.roads.ny[na] / 16});
        pts.set(pts.size() - 1, new float[]{r.roads.nx[nb] / 16, r.roads.ny[nb] / 16});
        for (int it = 0; it < 2; it++) {
            List<float[]> q = new ArrayList<float[]>();
            q.add(pts.get(0));
            for (int i = 0; i < pts.size() - 1; i++) {
                float[] p0 = pts.get(i), p1 = pts.get(i + 1);
                q.add(new float[]{p0[0] * 0.75f + p1[0] * 0.25f, p0[1] * 0.75f + p1[1] * 0.25f});
                q.add(new float[]{p0[0] * 0.25f + p1[0] * 0.75f, p0[1] * 0.25f + p1[1] * 0.75f});
            }
            q.add(pts.get(pts.size() - 1));
            pts = q;
        }
        // paint it, and lay junctions every ~10 tiles
        int prevNode = na;
        float acc = 0;
        for (int i = 1; i < pts.size(); i++) {
            float[] p0 = pts.get(i - 1), p1 = pts.get(i);
            float dx = p1[0] - p0[0], dy = p1[1] - p0[1];
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            int steps = Math.max(1, (int) (len * 2));
            for (int s = 0; s <= steps; s++) {
                float x = p0[0] + dx * s / steps, y = p0[1] + dy * s / steps;
                paveHighway(x, y);
            }
            acc += len;
            boolean last = i == pts.size() - 1;
            if (acc >= 10 || last) {
                int node = last ? nb : highwayNode(p1[0], p1[1]);
                if (node != prevNode) {
                    int e = r.roads.addEdge(prevNode, node, RoadGraph.HIGHWAY, 2.2f * 16);
                    if (e >= 0) r.roads.ebridge[e] = crossesWater(prevNode, node);
                }
                prevNode = node;
                acc = 0;
            }
        }
        return true;
    }

    private boolean crossesWater(int a, int b) {
        float ax = r.roads.nx[a] / 16, ay = r.roads.ny[a] / 16, bx = r.roads.nx[b] / 16, by = r.roads.ny[b] / 16;
        for (int i = 0; i <= 10; i++) {
            int t = r.t((int) (ax + (bx - ax) * i / 10), (int) (ay + (by - ay) * i / 10));
            if (t == Tile.BRIDGE) return true;
        }
        return false;
    }

    private int highwayNode(float tx, float ty) {
        // join a highway junction already close by, so highways that meet share it
        int cx = (int) (tx / 8), cy = (int) (ty / 8);
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                Integer id = highwayNodeAt.get(key(cx + dx, cy + dy));
                if (id != null) {
                    float ddx = r.roads.nx[id] / 16 - tx, ddy = r.roads.ny[id] / 16 - ty;
                    if (ddx * ddx + ddy * ddy < 36) return id;
                }
            }
        int id = r.roads.addNode(tx * 16, ty * 16, -1);
        highwayNodeAt.put(key(cx, cy), id);
        return id;
    }

    private static long key(int a, int b) {
        return ((long) a << 32) ^ (b & 0xFFFFFFFFL);
    }

    private void paveHighway(float x, float y) {
        int cx = (int) x, cy = (int) y;
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++) {
                if (dx * dx + dy * dy > 5) continue;
                int tx = cx + dx, ty = cy + dy;
                if (!r.in(tx, ty)) continue;
                int t = r.tile[ty * W + tx];
                if (t == Tile.BUILDING || t == Tile.FENCE || t == Tile.WALL) continue;
                if (Tile.water(t) || t == Tile.BRIDGE) r.tile[ty * W + tx] = Tile.BRIDGE;
                else if (t != Tile.ROAD) r.tile[ty * W + tx] = Tile.HIGHWAY;
            }
        highwayCells.add(new int[]{cx / HC, cy / HC});
    }

    private float cellCost(int cx, int cy, Region.Settlement sa, Region.Settlement sb) {
        int x = cx * HC + HC / 2, y = cy * HC + HC / 2;
        int t = r.t(x, y);
        float c;
        if (t == Tile.HIGHWAY || t == Tile.BRIDGE) c = 0.35f;
        else if (t == Tile.DEEP) c = 60;
        else if (t == Tile.WATER) c = 18;
        else if (t == Tile.ROCK) c = 40;
        else if (t == Tile.FENCE || t == Tile.WALL) c = 200;
        else if (t == Tile.BUILDING) c = 80;
        else if (t == Tile.FOREST) c = 1.6f;
        else c = 1;
        for (Region.Settlement s : r.settlements) {
            if (s == sa || s == sb) continue;
            float dx = x - s.cx, dy = y - s.cy;
            float rr = s.radius + 3;
            if (dx * dx + dy * dy < rr * rr) c += s.kind == Region.S_VILLAGE ? 4 : 30;
        }
        return c;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    // ------------------------------------------------------------------ countryside

    private void countryside() {
        // fields round the towns and villages
        for (Region.Settlement s : r.settlements) {
            if (s.kind != Region.S_TOWN && s.kind != Region.S_VILLAGE) continue;
            int fields = s.kind == Region.S_TOWN ? 14 : 8;
            for (int i = 0; i < fields; i++) {
                double ang = rng.range(0f, 6.283f);
                float d = s.radius + rng.range(8f, 50f);
                int fx = s.cx + (int) (Math.cos(ang) * d), fy = s.cy + (int) (Math.sin(ang) * d);
                int fw = rng.range(10, 28), fh = rng.range(8, 22);
                for (int y = fy; y < fy + fh; y++)
                    for (int x = fx; x < fx + fw; x++) {
                        int t = r.t(x, y);
                        if ((t == Tile.GRASS || t == Tile.FOREST) && r.settlementAt(x * 16, y * 16) == null) r.set(x, y, Tile.FARM);
                    }
            }
        }
        // farmhouses by the highways
        int farms = W / 20;
        RoadGraph g = r.roads;
        for (int i = 0; i < farms * 4 && farms > 0; i++) {
            int e = rng.nextInt(g.edgeCount);
            if (g.ekind[e] != RoadGraph.HIGHWAY || g.ebridge[e]) continue;
            float t = rng.nextFloat();
            float x = (g.nx[g.ea[e]] + (g.nx[g.eb[e]] - g.nx[g.ea[e]]) * t) / 16;
            float y = (g.ny[g.ea[e]] + (g.ny[g.eb[e]] - g.ny[g.ea[e]]) * t) / 16;
            float dx = g.nx[g.eb[e]] - g.nx[g.ea[e]], dy = g.ny[g.eb[e]] - g.ny[g.ea[e]];
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1) continue;
            float side = rng.chance(0.5f) ? 1 : -1;
            int hx = (int) (x - dy / len * 8 * side), hy = (int) (y + dx / len * 8 * side);
            if (r.settlementAt(hx * 16, hy * 16) != null) continue;
            boolean ok = true;
            for (int yy = hy - 2; yy <= hy + 8 && ok; yy++)
                for (int xx = hx - 2; xx <= hx + 12 && ok; xx++) {
                    int tt = r.t(xx, yy);
                    if (tt != Tile.GRASS && tt != Tile.FARM && tt != Tile.FOREST) ok = false;
                }
            if (!ok) continue;
            for (int yy = hy - 1; yy <= hy + 7; yy++) for (int xx = hx - 1; xx <= hx + 11; xx++) r.set(xx, yy, Tile.YARD);
            r.addBuilding(hx, hy, 4, 4, Region.B_FARMHOUSE, rng.chance(0.5f) ? 2 : 1, -1, rng.range(2, 6), roofFor(Region.B_FARMHOUSE));
            r.addBuilding(hx + 6, hy + 1, 5, 6, Region.B_BARN, 2, -1, 0, roofFor(Region.B_BARN));
            if (--farms <= 0) break;
        }
    }

    private void powerGrids() {
        List<Region.Facility> plants = new ArrayList<Region.Facility>();
        for (Region.Facility f : r.facilities) if (f.kind == Region.F_POWER) plants.add(f);
        r.gridCount = plants.size();
        for (int i = 0; i < plants.size(); i++) plants.get(i).grid = i;
        for (Region.Settlement s : r.settlements) {
            if (s.kind == Region.S_BASE || s.kind == Region.S_PRISON) continue; // own generators
            float bd = Float.MAX_VALUE;
            for (Region.Facility p : plants) {
                float dx = p.x / 16 - s.cx, dy = p.y / 16 - s.cy, d = dx * dx + dy * dy;
                if (d < bd) {
                    bd = d;
                    s.grid = p.grid;
                }
            }
        }
        for (Region.Facility f : r.facilities) {
            f.node = r.roads.nearestNode(f.x, f.y, 900);
            if (f.grid < 0 && f.settlement >= 0) {
                Region.Settlement s = r.settlements.get(f.settlement);
                if (f.kind != Region.F_POWER) f.grid = s.grid;
            }
        }
    }
}
