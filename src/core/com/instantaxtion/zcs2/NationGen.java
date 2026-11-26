package com.instantaxtion.zcs2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the coarse national layer (a Nation) of a real-scale country from a seed: terrain on a 16-tile grid with
 * its features sized in km, rivers and the coast, settlements sized for their people with a district raster and
 * residents for every 64-tile sector, facilities in lattice blocks, large sites, highways and railways found by A*
 * on a 64-tile cost grid, and the street lattice of every settlement as a graph (no tiles).
 * The same size, preset and seed always build the same nation, on any number of threads.
 */
public final class NationGen {
    private static final float CKM = Nation.KM / Nation.CELL; // coarse cells per km
    private static final float SECTOR_KM2 = (Nation.SECT / Nation.KM) * (Nation.SECT / Nation.KM);
    private static final int MODE_ROAD = 0, MODE_RAIL = 1;

    private final Nation n;
    private final RoadGraph roads;
    private final Rng rng;
    private final Noise nBase, nRidge, nLake, nForest, nFarm, nDist, nInd, nPark, nWob;
    private final int W, CW, SW, preset;
    private final long seed;
    private final int threads;
    private float[] hgt; // coarse heights while building
    private float[] farmMid; // farm noise on every other cell corner
    private int farmM;
    private int side;
    private float riverBias;

    // per sector, while building
    private int[] cWater, cDeep, cRock, cForest; // coarse cells of each kind (0..16)
    private float[] secD; // normalised distance from the settlement's nuclei (built sectors)
    private short[] reserved; // facility blocks taken in each sector (bit per block; -1 = the whole sector)
    private int[] roadNode; // a junction in the sector, for joining roads to
    private short[] roadOwner; // the settlement whose lattice that junction is (-1 highway, -2 country road)
    private boolean[] corridor, railCell;
    private boolean districtsDone;
    private RoadGraph.LongIntMap lat, hwNodeAt, railNodeAt;
    private final List<int[]> interstates = new ArrayList<int[]>(); // cell paths, then sa, sb appended
    private final List<List<Integer>> attach = new ArrayList<List<Integer>>(); // per settlement: nodes roads join at

    private NationGen(int size, int preset, long seed, int threads) {
        n = new Nation(size, preset, seed);
        roads = n.roads;
        this.preset = Data.terrain(preset); // (the kind of land: a preset of content.ini's may reuse one)
        this.seed = seed;
        this.threads = Math.max(1, threads);
        rng = new Rng(seed * 131 + preset * 17 + size);
        nBase = new Noise(seed + 101);
        nRidge = new Noise(seed + 102);
        nLake = new Noise(seed + 103);
        nForest = new Noise(seed + 104);
        nFarm = new Noise(seed + 105);
        nDist = new Noise(seed + 106);
        nInd = new Noise(seed + 107);
        nPark = new Noise(seed + 108);
        nWob = new Noise(seed + 109);
        W = size;
        CW = n.cw;
        SW = n.sw;
    }

    public static Nation generate(int size, int preset, long seed) {
        return generate(size, preset, seed, Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
    }

    public static Nation generate(int size, int preset, long seed, int threads) {
        long t0 = System.nanoTime();
        NationGen g = new NationGen(size, preset, seed, threads);
        g.terrain();
        g.lap("terrain");
        g.rivers();
        g.lap("rivers");
        g.finishTerrain();
        g.lap("finishTerrain");
        g.placeCities();
        g.lap("placeCities");
        g.interstatePaths();
        g.lap("interstatePaths");
        g.placeTowns();
        g.lap("placeTowns");
        g.placeSites();
        g.lap("placeSites");
        g.placeVillages();
        g.lap("placeVillages");
        g.districts();
        g.lap("districts");
        g.streetMasks();
        g.lap("streetMasks");
        g.facilities();
        g.lap("facilities");
        g.lattice();
        g.lap("lattice");
        g.bridges();
        g.lap("bridges");
        g.hubs();
        g.lap("hubs");
        g.sites();
        g.lap("sites");
        g.interstateRoads();
        g.lap("interstateRoads");
        g.connectors();
        g.lap("connectors");
        g.exits();
        g.lap("exits");
        g.joinIslands();
        g.lap("joinIslands");
        g.railways();
        g.lap("railways");
        g.landUse();
        g.lap("landUse");
        g.finishGraphs();
        g.lap("finishGraphs");
        g.summaries();
        g.lap("summaries");
        g.countryside();
        g.lap("countryside");
        g.statesAndNetworks();
        g.lap("statesAndNetworks");
        g.hgt = null;
        g.farmMid = null;
        g.n.buildMs = (System.nanoTime() - t0) / 1000000;
        return g.n;
    }

    // ------------------------------------------------------------------ helpers

    private static final boolean PROFILE = System.getenv("NATION_PROFILE") != null;
    private long lapT = System.nanoTime();

    private void lap(String what) {
        long t = System.nanoTime();
        if (PROFILE) System.out.printf("  %-18s %6.0f ms%n", what, (t - lapT) / 1e6);
        lapT = t;
    }


    private interface Rows {
        void row(int y);
    }

    /** Runs job for every row in 0..count-1, split over the threads (each row depends only on its inputs). */
    private void rows(final int count, final Rows job) {
        if (threads <= 1) {
            for (int y = 0; y < count; y++) job.row(y);
            return;
        }
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int t0 = t;
            ts[t] = new Thread(new Runnable() {
                public void run() {
                    for (int y = t0; y < count; y += threads) job.row(y);
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) {
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Octaves from..to-1 of fractal value noise (amplitude halving, frequency x2.03, like Noise.fbm). */
    private static float oct(Noise nz, float x, float y, int from, int to) {
        float sum = 0, amp = 1, f = 1;
        for (int i = 0; i < to; i++) {
            if (i >= from) sum += nz.value(x * f + i * 17.3f, y * f - i * 9.1f) * amp;
            amp *= 0.5f;
            f *= 2.03f;
        }
        return sum;
    }

    private static float norm(int octaves) {
        float s = 0, a = 1;
        for (int i = 0; i < octaves; i++) {
            s += a;
            a *= 0.5f;
        }
        return s;
    }

    private static float bilerp(float[] g, int gw, float gx, float gy) {
        int x0 = Math.min(gw - 2, (int) gx), y0 = Math.min(gw - 2, (int) gy);
        float ax = gx - x0, ay = gy - y0;
        int i = y0 * gw + x0;
        float top = g[i] + (g[i + 1] - g[i]) * ax, bot = g[i + gw] + (g[i + gw + 1] - g[i + gw]) * ax;
        return top + (bot - top) * ay;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    private int cellAt(float tx, float ty) {
        return clamp((int) ty / Nation.CELL, 0, CW - 1) * CW + clamp((int) tx / Nation.CELL, 0, CW - 1);
    }

    private int secAt(float tx, float ty) {
        return clamp((int) ty / Nation.SECT, 0, SW - 1) * SW + clamp((int) tx / Nation.SECT, 0, SW - 1);
    }

    private float secCX(int s) {
        return (s % SW) * Nation.SECT + Nation.SECT / 2f;
    }

    private float secCY(int s) {
        return (s / SW) * Nation.SECT + Nation.SECT / 2f;
    }

    private boolean landCell(int c) {
        int k = n.cover[c];
        return k != Nation.C_DEEP && k != Nation.C_WATER && k != Nation.C_ROCK;
    }

    private float landShare(int s) {
        return (16 - cWater[s] - cRock[s]) / 16f;
    }

    // ------------------------------------------------------------------ terrain

    private void terrain() {
        side = rng.nextInt(4);
        riverBias = rng.range(0.35f, 0.65f);
        hgt = new float[CW * CW];
        final int G = SW + 1; // the low octaves, on sector corners (every 4 cells)
        final float[] lb = new float[G * G], lr = new float[G * G], ll = new float[G * G], lf = new float[G * G];
        final float sb = 1f / (22 * CKM), sr = 1f / (30 * CKM), sl = 1f / (12 * CKM), sf = 1f / (7 * CKM);
        rows(G, new Rows() {
            public void row(int y) {
                for (int x = 0; x < G; x++) {
                    float cx = x * 4f, cy = y * 4f;
                    int i = y * G + x;
                    lb[i] = oct(nBase, cx * sb, cy * sb, 0, 4);
                    if (preset == RegionGen.HIGHLANDS) lr[i] = oct(nRidge, cx * sr, cy * sr, 0, 4);
                    if (preset == RegionGen.LAKES) ll[i] = oct(nLake, cx * sl, cy * sl, 0, 3);
                    lf[i] = oct(nForest, cx * sf, cy * sf, 0, 2);
                }
            }
        });
        // the finer octaves (down to 0.6 km) on every other cell corner
        final int M = CW / 2 + 1;
        final float[] mb = new float[M * M], mf = new float[M * M];
        farmMid = new float[M * M];
        final float sfarm = 1f / (4 * CKM);
        rows(M, new Rows() {
            public void row(int y) {
                for (int x = 0; x < M; x++) {
                    float cx = x * 2f, cy = y * 2f;
                    int i = y * M + x;
                    mb[i] = oct(nBase, cx * sb, cy * sb, 4, 6);
                    mf[i] = oct(nForest, cx * sf, cy * sf, 2, 4);
                    farmMid[i] = nFarm.value(cx * sfarm, cy * sfarm) * 0.5f + nFarm.value(cx * sfarm * 3.1f + 7, cy * sfarm * 3.1f) * 0.25f;
                }
            }
        });
        farmM = M;
        final float nb6 = norm(6), nr4 = norm(4), nl3 = norm(3), nf4 = norm(4);
        rows(CW, new Rows() {
            public void row(int y) {
                for (int x = 0; x < CW; x++) {
                    float cx = x + 0.5f, cy = y + 0.5f, gx = cx / 4, gy = cy / 4;
                    float base = (bilerp(lb, G, gx, gy) + bilerp(mb, M, cx / 2, cy / 2)) / nb6;
                    float fx = cx / CW, fy = cy / CW, h;
                    switch (preset) {
                        case RegionGen.COAST: {
                            float d = side == 0 ? fx : side == 1 ? 1 - fx : side == 2 ? fy : 1 - fy;
                            h = 0.18f + base * 0.45f + Math.min(d - 0.2f, 0.22f) * 1.4f;
                            break;
                        }
                        case RegionGen.HIGHLANDS: {
                            float ridge = 1 - Math.abs(bilerp(lr, G, gx, gy) / nr4) * 2;
                            h = 0.3f + base * 0.5f + Math.max(0, ridge) * 0.45f;
                            break;
                        }
                        case RegionGen.LAKES: {
                            float lake = bilerp(ll, G, gx, gy) / nl3;
                            h = 0.25f + base * 0.4f - Math.max(0, lake - 0.22f) * 2.5f;
                            break;
                        }
                        default: {
                            float c = (side & 1) == 0 ? fx : fy;
                            h = 0.25f + base * 0.45f + Math.abs(c - riverBias) * 0.5f;
                        }
                    }
                    int i = y * CW + x;
                    hgt[i] = h;
                    byte t;
                    if (h < -0.1f) t = Nation.C_DEEP;
                    else if (h < 0) t = Nation.C_WATER;
                    else if (h > 0.92f) t = Nation.C_ROCK;
                    else t = Nation.C_GRASS;
                    if (t == Nation.C_GRASS && h < 0.85f) {
                        float f = (bilerp(lf, G, gx, gy) + bilerp(mf, M, cx / 2, cy / 2)) / nf4;
                        if (f + (h - 0.45f) * 0.4f > (preset == RegionGen.LAKES ? 0.09f : 0.16f)) t = Nation.C_FOREST;
                    }
                    n.cover[i] = t;
                }
            }
        });
        // beaches: land next to open water
        byte[] c = n.cover;
        byte[] out = c.clone();
        for (int y = 1; y < CW - 1; y++)
            for (int x = 1; x < CW - 1; x++) {
                int i = y * CW + x;
                if (c[i] != Nation.C_GRASS && c[i] != Nation.C_FOREST) continue;
                if (Nation.wet(c[i - 1]) || Nation.wet(c[i + 1]) || Nation.wet(c[i - CW]) || Nation.wet(c[i + CW])) out[i] = Nation.C_SAND;
            }
        System.arraycopy(out, 0, c, 0, c.length);
    }

    private float hAt(int cx, int cy) {
        return hgt[clamp(cy, 0, CW - 1) * CW + clamp(cx, 0, CW - 1)];
    }

    private void rivers() {
        int mains = preset == RegionGen.VALLEY ? 1 : preset == RegionGen.LAKES ? 2 : preset == RegionGen.HIGHLANDS ? 1 : rng.range(1, 2);
        for (int i = 0; i < mains; i++) {
            float x, y, dx, dy;
            float width = preset == RegionGen.VALLEY && i == 0 ? rng.range(56f, 72f) : rng.range(26f, 40f);
            if (preset == RegionGen.COAST) {
                float along = rng.range(0.25f, 0.75f);
                if (side == 0) { x = W * 0.97f; y = W * along; dx = -1; dy = 0; }
                else if (side == 1) { x = W * 0.03f; y = W * along; dx = 1; dy = 0; }
                else if (side == 2) { x = W * along; y = W * 0.97f; dx = 0; dy = -1; }
                else { x = W * along; y = W * 0.03f; dx = 0; dy = 1; }
            } else if (preset == RegionGen.VALLEY && i == 0) {
                // down the valley the terrain made
                if ((side & 1) == 0) { x = W * riverBias; y = 0; dx = 0; dy = 1; }
                else { x = 0; y = W * riverBias; dx = 1; dy = 0; }
            } else {
                boolean vertical = rng.chance(0.5f);
                float along = rng.range(0.25f, 0.75f);
                if (vertical) { x = W * along; y = 0; dx = 0; dy = 1; }
                else { x = 0; y = W * along; dx = 1; dy = 0; }
            }
            List<float[]> pts = walkRiver(x, y, dx, dy, true);
            carve(pts, width);
        }
        int tribs = Math.round(W / 6000f) + (preset == RegionGen.VALLEY || preset == RegionGen.HIGHLANDS ? 2 : 0);
        int made = 0;
        for (int tries = 0; tries < tribs * 12 && made < tribs && !n.rivers.isEmpty(); tries++) {
            // traced upstream from a point on a river already there, so every tributary flows into something
            float[] main = n.rivers.get(rng.nextInt(n.rivers.size()));
            int np = main.length / 2;
            if (np < 20) continue;
            int at = rng.range(np / 8, np - np / 8 - 1);
            float x = main[at * 2], y = main[at * 2 + 1], tx = main[at * 2 + 2] - x, ty = main[at * 2 + 3] - y;
            float tl = (float) Math.sqrt(tx * tx + ty * ty);
            if (tl < 1e-3f) continue;
            float side = rng.chance(0.5f) ? 1 : -1;
            // leave at an angle, against the flow
            float dx = (-ty / tl * side) * 0.8f - tx / tl * 0.6f, dy = (tx / tl * side) * 0.8f - ty / tl * 0.6f;
            List<float[]> up = walkUp(x, y, dx, dy, rng.range(8f, 26f) * Nation.KM);
            if (up == null) continue;
            List<float[]> down = new ArrayList<float[]>();
            for (int i = up.size() - 1; i >= 0; i--) down.add(up.get(i));
            down.add(new float[]{x, y});
            carve(down, rng.range(14f, 22f));
            made++;
        }
    }

    /** A course uphill from (x, y) for up to len tiles; null if it ran into water or off the map too soon. */
    private List<float[]> walkUp(float x, float y, float dx, float dy, float len) {
        List<float[]> pts = new ArrayList<float[]>();
        float phase = rng.range(0f, 100f), meander = rng.range(150f, 350f);
        int steps = (int) (len / 8);
        float bx = x, by = y; // the course without its meanders
        final float dx0 = dx, dy0 = dy;
        for (int s = 0; s < steps; s++) {
            int cx = (int) (bx / 16), cy = (int) (by / 16);
            float gx = hAt(cx + 4, cy) - hAt(cx - 4, cy), gy = hAt(cx, cy + 4) - hAt(cx, cy - 4);
            float gl = (float) Math.sqrt(gx * gx + gy * gy);
            if (gl > 1e-4f) {
                float nx = dx * 0.98f + gx / gl * 0.02f, ny = dy * 0.98f + gy / gl * 0.02f;
                float dl = (float) Math.sqrt(nx * nx + ny * ny);
                nx /= dl;
                ny /= dl;
                if (nx * dx0 + ny * dy0 > 0.6f) { // never turning back on itself
                    dx = nx;
                    dy = ny;
                }
            }
            bx += dx * 8;
            by += dy * 8;
            // meanders grow from nothing at the mouth
            float wob = nWob.value(s * 8 / (3 * Nation.KM) + phase, phase) * meander * Math.min(1, s / 40f);
            x = bx - dy * wob;
            y = by + dx * wob;
            if (x < 64 || y < 64 || x > W - 64 || y > W - 64) break;
            int c = cellAt(x, y);
            if (s > 12 && Nation.wet(n.cover[c])) return pts.size() * 8 > 6 * Nation.KM ? pts : null;
            if (hgt[c] > 0.9f) break;
            pts.add(new float[]{x, y});
        }
        return pts.size() * 8 > 6 * Nation.KM ? pts : null;
    }

    private boolean nearWater(int cx, int cy, int r) {
        for (int y = cy - r; y <= cy + r; y += 3)
            for (int x = cx - r; x <= cx + r; x += 3) {
                if (x < 0 || y < 0 || x >= CW || y >= CW) continue;
                if (Nation.wet(n.cover[y * CW + x])) return true;
            }
        return false;
    }

    /** A river's course in tiles: a main river keeps its heading, a tributary runs downhill; null if it never got anywhere. */
    private List<float[]> walkRiver(float x, float y, float dx, float dy, boolean main) {
        List<float[]> pts = new ArrayList<float[]>();
        float phase = rng.range(0f, 100f);
        int steps = (int) (W * 1.8f / 8);
        float hx = dx, hy = dy;
        boolean reached = main;
        for (int s = 0; s < steps; s++) {
            float wob = nWob.value(s * 8 / (5 * Nation.KM) + phase, phase);
            int cx = (int) (x / 16), cy = (int) (y / 16);
            float gx = hAt(cx + 3, cy) - hAt(cx - 3, cy), gy = hAt(cx, cy + 3) - hAt(cx, cy - 3);
            float gl = (float) Math.sqrt(gx * gx + gy * gy);
            float ddx = gl > 1e-5f ? -gx / gl : 0, ddy = gl > 1e-5f ? -gy / gl : 0;
            float px = -hy, py = hx;
            float mx = hx + px * wob * 0.9f + ddx * (main ? 0.3f : 0.9f), my = hy + py * wob * 0.9f + ddy * (main ? 0.3f : 0.9f);
            float len = (float) Math.sqrt(mx * mx + my * my);
            mx /= len;
            my /= len;
            if (!main) {
                hx = hx * 0.8f + mx * 0.2f;
                hy = hy * 0.8f + my * 0.2f;
                float hl = (float) Math.sqrt(hx * hx + hy * hy);
                hx /= hl;
                hy /= hl;
            }
            x += mx * 8;
            y += my * 8;
            if (x < -8 || y < -8 || x > W + 8 || y > W + 8) {
                if (!main && s * 8 > 15 * Nation.KM) reached = true;
                break;
            }
            pts.add(new float[]{x, y});
            int k = n.cover[cellAt(x, y)];
            if (s > 30 && (k == Nation.C_DEEP || (!main && k == Nation.C_WATER))) {
                reached = true;
                break;
            }
        }
        if (!reached || pts.size() < 2 || (!main && pts.size() * 8 < 10 * Nation.KM)) return null;
        return pts;
    }

    private void carve(List<float[]> pts, float width) {
        if (pts == null) return;
        float[] line = new float[pts.size() * 2];
        for (int i = 0; i < pts.size(); i++) {
            line[i * 2] = pts.get(i)[0];
            line[i * 2 + 1] = pts.get(i)[1];
        }
        n.rivers.add(line);
        n.riverWidth.add(width);
        float rc = width / 2 + 4;
        for (float[] p : pts) {
            int x0 = (int) ((p[0] - rc) / 16), x1 = (int) ((p[0] + rc) / 16), y0 = (int) ((p[1] - rc) / 16), y1 = (int) ((p[1] + rc) / 16);
            for (int cy = y0; cy <= y1; cy++)
                for (int cx = x0; cx <= x1; cx++) {
                    if (cx < 0 || cy < 0 || cx >= CW || cy >= CW) continue;
                    float ddx = cx * 16 + 8 - p[0], ddy = cy * 16 + 8 - p[1];
                    boolean centre = cx == (int) (p[0] / 16) && cy == (int) (p[1] / 16);
                    if (!centre && ddx * ddx + ddy * ddy > rc * rc) continue;
                    int i = cy * CW + cx;
                    if (n.cover[i] != Nation.C_DEEP) n.cover[i] = Nation.C_WATER;
                    hgt[i] = Math.min(hgt[i], -0.03f);
                }
        }
    }

    /** Heights into bytes, and the per-sector counts the rest of the build reads. */
    private void finishTerrain() {
        for (int i = 0; i < hgt.length; i++) n.height[i] = (byte) clamp(Math.round((hgt[i] + Nation.H0) * Nation.HSCALE), 0, 255);
        int ns = SW * SW;
        cWater = new int[ns];
        cDeep = new int[ns];
        cRock = new int[ns];
        cForest = new int[ns];
        countCells();
        secD = new float[ns];
        reserved = new short[ns];
        roadNode = new int[ns];
        Arrays.fill(roadNode, -1);
        roadOwner = new short[ns];
        corridor = new boolean[ns];
        railCell = new boolean[ns];
    }

    private void countCells() {
        Arrays.fill(cWater, 0);
        Arrays.fill(cDeep, 0);
        Arrays.fill(cRock, 0);
        Arrays.fill(cForest, 0);
        for (int cy = 0; cy < CW; cy++)
            for (int cx = 0; cx < CW; cx++) {
                int k = n.cover[cy * CW + cx], s = (cy / 4) * SW + cx / 4;
                if (k == Nation.C_DEEP) {
                    cDeep[s]++;
                    cWater[s]++;
                } else if (k == Nation.C_WATER) cWater[s]++;
                else if (k == Nation.C_ROCK) cRock[s]++;
                else if (k == Nation.C_FOREST) cForest[s]++;
            }
    }

    // ------------------------------------------------------------------ settlements

    private float landFrac(int cx, int cy, int rad) {
        int land = 0, all = 0;
        int step = Math.max(16, rad / 10);
        for (int y = cy - rad; y <= cy + rad; y += step)
            for (int x = cx - rad; x <= cx + rad; x += step) {
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy > (float) rad * rad) continue;
                all++;
                if (x >= 0 && y >= 0 && x < W && y < W && landCell(cellAt(x, y))) land++;
            }
        return all == 0 ? 0 : land / (float) all;
    }

    private float waterNear(int cx, int cy, int rad) {
        int water = 0;
        for (int a = 0; a < 24; a++) {
            double ang = a * Math.PI / 12;
            if (Nation.wet(n.coverAt(cx + (int) (Math.cos(ang) * rad), cy + (int) (Math.sin(ang) * rad)))) water++;
        }
        return water / 24f;
    }

    private boolean farFromOthers(int cx, int cy, int rad, int gap) {
        for (Region.Settlement s : n.settlements) {
            float dx = s.cx - cx, dy = s.cy - cy, need = s.radius + rad + gap;
            if (dx * dx + dy * dy < need * need) return false;
        }
        return true;
    }

    private Region.Settlement addSettlement(int kind, int cx, int cy, int rad, int pop) {
        Region.Settlement s = new Region.Settlement();
        s.id = n.settlements.size();
        s.kind = kind;
        s.cx = cx;
        s.cy = cy;
        s.radius = rad;
        s.name = Names.place(rng, kind);
        n.settlements.add(s);
        n.nuclei.add(new int[]{cx, cy, rad});
        n.targetPop.add(pop);
        attach.add(new ArrayList<Integer>());
        return s;
    }

    /** The radius in tiles of a round place of this many people at this density (people per km2). */
    private static int radiusFor(int pop, float density) {
        return Math.max(24, (int) (Math.sqrt(pop / density / Math.PI) * Nation.KM));
    }

    private float scaleArea() {
        return (W / 28672f) * (W / 28672f);
    }

    private void placeCities() {
        int cap = (int) rng.range(1050000f, 1350000f);
        int rc = radiusFor(cap, 5300);
        Region.Settlement c = placeCity(cap, (int) (rc * 1.2f), 0.3f, 0.7f, 0);
        if (c == null) c = addSettlement(Region.S_CITY, W / 2, W / 2, (int) (rc * 1.2f), cap);
        c.capital = true;
        n.name = Names.country(rng);
        int cities = W <= 20480 ? 3 : W <= 28672 ? 5 : 7;
        int[] pops = new int[cities];
        for (int i = 0; i < cities; i++) pops[i] = (int) rng.range(160000f, 440000f);
        Arrays.sort(pops);
        for (int i = cities - 1; i >= 0; i--) {
            int r = radiusFor(pops[i], 3800);
            if (placeCity(pops[i], (int) (r * 1.1f), 0.07f, 0.93f, (int) (9 * Nation.KM)) == null)
                placeCity(pops[i], (int) (r * 1.1f), 0.05f, 0.95f, (int) (5 * Nation.KM));
        }
    }

    private Region.Settlement placeCity(int pop, int rc, float x0, float x1, int gap) {
        int bestX = -1, bestY = -1;
        float best = -9;
        for (int i = 0; i < 300; i++) {
            int x = rng.range((int) (W * x0), (int) (W * x1)), y = rng.range((int) (W * x0), (int) (W * x1));
            if (x < rc + 400 || y < rc + 400 || x > W - rc - 400 || y > W - rc - 400) continue;
            if (!farFromOthers(x, y, rc, gap)) continue;
            float lf = landFrac(x, y, rc);
            if (lf < 0.72f) continue;
            float score = lf + Math.min(0.25f, waterNear(x, y, (int) (rc * 1.2f))) * 1.2f + riverNear(x, y, rc / 2) * 0.3f + rng.nextFloat() * 0.15f;
            if (score > best) {
                best = score;
                bestX = x;
                bestY = y;
            }
        }
        if (bestX < 0) return null;
        return addSettlement(Region.S_CITY, bestX, bestY, rc, pop);
    }

    /** 1 if a river or shore is within rad tiles of (x, y), else 0 (sampled). */
    private float riverNear(int x, int y, int rad) {
        for (int a = 0; a < 16; a++) {
            double ang = a * Math.PI / 8;
            for (int k = 1; k <= 3; k++) {
                int px = x + (int) (Math.cos(ang) * rad * k / 3), py = y + (int) (Math.sin(ang) * rad * k / 3);
                if (Nation.wet(n.coverAt(px, py))) return 1;
            }
        }
        return 0;
    }

    /** Where the highways between the cities will run (cells), so towns can grow along them. */
    private void interstatePaths() {
        List<int[]> pairs = cityPairs();
        for (int[] p : pairs) {
            Region.Settlement a = n.settlements.get(p[0]), b = n.settlements.get(p[1]);
            int[] path = gridSearch(secAt(a.cx, a.cy), secAt(b.cx, b.cy), a.id, b.id, MODE_ROAD, 400000);
            if (path == null) continue;
            int[] rec = Arrays.copyOf(path, path.length + 2);
            rec[path.length] = a.id;
            rec[path.length + 1] = b.id;
            interstates.add(rec);
            for (int c : path) corridor[c] = true;
        }
    }

    /** A spanning tree over the capital and cities, plus every pair of cities not too far apart. */
    private List<int[]> cityPairs() {
        List<Integer> cs = new ArrayList<Integer>();
        for (Region.Settlement s : n.settlements) if (s.kind == Region.S_CITY) cs.add(s.id);
        int k = cs.size();
        boolean[] in = new boolean[k];
        in[0] = true;
        List<int[]> pairs = new ArrayList<int[]>();
        for (int added = 1; added < k; added++) {
            int ba = -1, bb = -1;
            float bd = Float.MAX_VALUE;
            for (int a = 0; a < k; a++) {
                if (!in[a]) continue;
                for (int b = 0; b < k; b++) {
                    if (in[b]) continue;
                    float d = dist2(cs.get(a), cs.get(b));
                    if (d < bd) {
                        bd = d;
                        ba = a;
                        bb = b;
                    }
                }
            }
            if (bb < 0) break;
            in[bb] = true;
            pairs.add(new int[]{cs.get(ba), cs.get(bb)});
        }
        for (int a = 0; a < k; a++)
            for (int b = a + 1; b < k; b++) {
                boolean have = false;
                for (int[] p : pairs) if ((p[0] == cs.get(a) && p[1] == cs.get(b)) || (p[1] == cs.get(a) && p[0] == cs.get(b))) have = true;
                if (!have && Math.sqrt(dist2(cs.get(a), cs.get(b))) < W * (a == 0 ? 0.6f : 0.42f)) pairs.add(new int[]{cs.get(a), cs.get(b)});
            }
        return pairs;
    }

    private float dist2(int a, int b) {
        Region.Settlement sa = n.settlements.get(a), sb = n.settlements.get(b);
        float dx = sa.cx - sb.cx, dy = sa.cy - sb.cy;
        return dx * dx + dy * dy;
    }

    /** Sector distances (in sectors, 8-neighbour steps) to the nearest sector marked in 'from'. */
    private int[] distanceField(boolean[] from) {
        int ns = SW * SW;
        int[] d = new int[ns];
        Arrays.fill(d, Integer.MAX_VALUE);
        int[] q = new int[ns];
        int h = 0, t = 0;
        for (int i = 0; i < ns; i++)
            if (from[i]) {
                d[i] = 0;
                q[t++] = i;
            }
        while (h < t) {
            int c = q[h++], cx = c % SW, cy = c / SW;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    int x = cx + dx, y = cy + dy;
                    if (x < 0 || y < 0 || x >= SW || y >= SW) continue;
                    int m = y * SW + x;
                    if (d[m] == Integer.MAX_VALUE) {
                        d[m] = d[c] + 1;
                        q[t++] = m;
                    }
                }
        }
        return d;
    }

    private int[] waterDist() {
        boolean[] w = new boolean[SW * SW];
        for (int i = 0; i < w.length; i++) w[i] = cWater[i] >= 3;
        return distanceField(w);
    }

    private void placeTowns() {
        int towns = Math.round(50 * scaleArea());
        int[] pops = new int[towns];
        for (int i = 0; i < towns; i++) pops[i] = (int) (5000 * Math.pow(8, Math.pow(rng.nextFloat(), 1.6)));
        Arrays.sort(pops);
        int[] road = distanceField(corridor), water = waterDist();
        float sectKm = Nation.SECT / Nation.KM;
        for (int i = towns - 1; i >= 0; i--) {
            int rad = radiusFor(pops[i], 2600);
            int bx = -1, by = -1;
            float best = -9;
            for (int k = 0; k < 40; k++) {
                int x = rng.range(rad + 300, W - rad - 300), y = rng.range(rad + 300, W - rad - 300);
                if (!farFromOthers(x, y, rad, (int) (3.5f * Nation.KM))) continue;
                float lf = landFrac(x, y, rad);
                if (lf < 0.75f) continue;
                int s = secAt(x, y);
                float dRoad = road[s] * sectKm, dWater = water[s] * sectKm;
                float score = lf + (dWater < 2.5f ? 0.45f : 0) + (dRoad < 3f ? 0.6f * (1 - dRoad / 3f) : 0)
                        - (hgt[cellAt(x, y)] > 0.75f ? 0.4f : 0) + rng.nextFloat() * 0.45f;
                if (score > best) {
                    best = score;
                    bx = x;
                    by = y;
                }
            }
            if (bx >= 0) addSettlement(Region.S_TOWN, bx, by, rad, pops[i]);
        }
    }

    // site sizes (half width, half height) in tiles
    private static final int BASE_HW = 150, BASE_HH = 120, PRISON_HW = 48, PRISON_HH = 38, AIRBASE_HW = 400, AIRPORT_HW = 360, NAVAL_R = 100;

    private void placeSites() {
        List<Region.Settlement> cities = new ArrayList<Region.Settlement>();
        for (Region.Settlement s : n.settlements) if (s.kind == Region.S_CITY) cities.add(s);
        int bases = 1 + (cities.size() - 1) / 2;
        for (int b = 0; b < bases; b++) placeNear(Region.S_BASE, cities.get(b % cities.size()), BASE_HW, 1000, 4000, 600, 0.98f);
        placeNear(Region.S_AIRBASE, cities.get(Math.min(1, cities.size() - 1)), AIRBASE_HW, 1500, 5000, 600, 0.98f);
        placeNear(Region.S_AIRPORT, cities.get(0), AIRPORT_HW, 400, 2500, 300, 0.98f);
        if (preset == RegionGen.COAST || preset == RegionGen.LAKES) placeCoastal(Region.S_NAVAL, NAVAL_R);
        int prisons = 1 + W / 15000;
        for (int p = 0; p < prisons; p++) {
            for (int i = 0; i < 400; i++) {
                Region.Settlement t = n.settlements.get(1 + rng.nextInt(Math.max(1, n.settlements.size() - 1)));
                if (t.kind != Region.S_TOWN) continue;
                if (placeNear(Region.S_PRISON, t, PRISON_HW, 300, 1500, 250, 0.98f) != null) break;
            }
        }
    }

    private Region.Settlement placeNear(int kind, Region.Settlement near, int rad, float dmin, float dmax, int gap, float land) {
        for (int i = 0; i < 400; i++) {
            double ang = rng.range(0f, 6.283f);
            float dist = near.radius + rad + rng.range(dmin, dmax);
            int x = near.cx + (int) (Math.cos(ang) * dist), y = near.cy + (int) (Math.sin(ang) * dist);
            if (x < rad + 300 || y < rad + 300 || x > W - rad - 300 || y > W - rad - 300) continue;
            if (landFrac(x, y, rad + 16) < land || !farFromOthers(x, y, rad, gap) || onCorridor(x, y, rad)) continue;
            int pop = kind == Region.S_PRISON ? (int) rng.range(1500f, 2500f) : kind == Region.S_AIRPORT ? 400 : 0;
            return addSettlement(kind, x, y, rad, pop);
        }
        return null;
    }

    private boolean onCorridor(int x, int y, int rad) {
        for (int sy = (y - rad) / Nation.SECT; sy <= (y + rad) / Nation.SECT; sy++)
            for (int sx = (x - rad) / Nation.SECT; sx <= (x + rad) / Nation.SECT; sx++)
                if (sx >= 0 && sy >= 0 && sx < SW && sy < SW && corridor[sy * SW + sx]) return true;
        return false;
    }

    private Region.Settlement placeCoastal(int kind, int rad) {
        for (int i = 0; i < 1500; i++) {
            int x = rng.range(rad + 600, W - rad - 600), y = rng.range(rad + 600, W - rad - 600);
            float lf = landFrac(x, y, rad);
            if (lf < 0.5f || lf > 0.85f || !farFromOthers(x, y, rad, 500) || onCorridor(x, y, rad)) continue;
            if (waterNear(x, y, rad + 60) < 0.25f) continue;
            return addSettlement(kind, x, y, rad, 0);
        }
        return null;
    }

    private void placeVillages() {
        int villages = Math.round(250 * scaleArea());
        int[] pops = new int[villages];
        for (int i = 0; i < villages; i++) pops[i] = (int) (300 * Math.pow(2500 / 300.0, Math.pow(rng.nextFloat(), 1.3)));
        Arrays.sort(pops);
        int[] road = distanceField(corridor), water = waterDist();
        boolean[] town = new boolean[SW * SW];
        for (Region.Settlement s : n.settlements) if (s.kind == Region.S_TOWN || s.kind == Region.S_CITY) town[secAt(s.cx, s.cy)] = true;
        int[] townD = distanceField(town);
        float sectKm = Nation.SECT / Nation.KM;
        for (int i = villages - 1; i >= 0; i--) {
            int rad = radiusFor(pops[i], 1800);
            int bx = -1, by = -1;
            float best = -9;
            for (int k = 0; k < 14; k++) {
                int x = rng.range(rad + 200, W - rad - 200), y = rng.range(rad + 200, W - rad - 200);
                if (!farFromOthers(x, y, rad, (int) (1.4f * Nation.KM))) continue;
                float lf = landFrac(x, y, rad + 32);
                if (lf < 0.8f) continue;
                int s = secAt(x, y);
                float score = lf + (water[s] * sectKm < 1.5f ? 0.35f : 0) + (road[s] * sectKm < 2f ? 0.3f : 0)
                        + (townD[s] * sectKm < 9f ? 0.25f : 0) - (hgt[cellAt(x, y)] > 0.8f ? 0.5f : 0) + rng.nextFloat() * 0.5f;
                if (score > best) {
                    best = score;
                    bx = x;
                    by = y;
                }
            }
            if (bx >= 0) addSettlement(Region.S_VILLAGE, bx, by, rad, pops[i]);
        }
    }

    private static boolean town(int kind) {
        return kind == Region.S_CITY || kind == Region.S_TOWN || kind == Region.S_VILLAGE;
    }

    private boolean site(int id) {
        return id >= 0 && !town(n.settlements.get(id).kind);
    }

    // ------------------------------------------------------------------ districts and residents

    private void districts() {
        // nuclei: the capital has 3-5, a city 1-2, everything else 1
        for (Region.Settlement s : n.settlements) {
            if (!town(s.kind)) continue;
            int R = radiusFor(n.targetPop.get(s.id), s.kind == Region.S_CITY ? (s.capital ? 5300 : 3800) : s.kind == Region.S_TOWN ? 2600 : 1800);
            int subs = s.capital ? rng.range(2, 4) : s.kind == Region.S_CITY ? rng.range(0, 1) : 0;
            int[] nu = new int[3 + subs * 3];
            nu[0] = s.cx;
            nu[1] = s.cy;
            nu[2] = (int) (R * (subs > 0 ? 0.78f : 1f));
            double a0 = rng.range(0f, 6.283f);
            for (int k = 0; k < subs; k++) {
                double ang = a0 + k * 6.283 / subs + rng.range(-0.5f, 0.5f);
                float d = R * rng.range(0.6f, 0.95f);
                nu[3 + k * 3] = s.cx + (int) (Math.cos(ang) * d);
                nu[4 + k * 3] = s.cy + (int) (Math.sin(ang) * d);
                nu[5 + k * 3] = (int) (R * (s.capital ? rng.range(0.28f, 0.42f) : rng.range(0.25f, 0.35f)));
            }
            n.nuclei.set(s.id, nu);
        }
        // sites own the sectors under their fences
        for (Region.Settlement s : n.settlements) {
            if (town(s.kind)) continue;
            int[] r = siteRect(s);
            for (int sy = r[1] / Nation.SECT; sy <= r[3] / Nation.SECT; sy++)
                for (int sx = r[0] / Nation.SECT; sx <= r[2] / Nation.SECT; sx++) {
                    if (sx < 0 || sy < 0 || sx >= SW || sy >= SW) continue;
                    int c = sy * SW + sx;
                    if (n.secSettlement[c] < 0) n.secSettlement[c] = (short) s.id;
                }
            int c = secAt(s.cx, s.cy);
            if (n.secSettlement[c] == s.id) n.residents[c] += n.targetPop.get(s.id);
            s.residents = n.targetPop.get(s.id);
        }
        // claim sectors, biggest first, scaling the nuclei until the built land matches the people
        for (int kind = Region.S_CITY; kind <= Region.S_VILLAGE; kind++)
            for (Region.Settlement s : n.settlements) {
                if (s.kind != kind) continue;
                int pop = n.targetPop.get(s.id);
                float dens = s.kind == Region.S_CITY ? (s.capital ? 5300 : 3800) : s.kind == Region.S_TOWN ? 2600 : 1800;
                float want = pop / dens / SECTOR_KM2;
                float f = 1;
                List<Integer> claim = null;
                for (int it = 0; it < 5; it++) {
                    claim = claimSet(s, f);
                    float got = 0;
                    for (int c : claim) got += landShare(c);
                    if (got <= 0) f *= 1.5f;
                    else f *= (float) Math.sqrt(want / got);
                    f = Math.max(0.3f, Math.min(3f, f));
                }
                claim = claimSet(s, f);
                if (claim.isEmpty()) {
                    int c = secAt(s.cx, s.cy);
                    if (n.secSettlement[c] < 0 && landShare(c) > 0.2f) claim.add(c);
                }
                int[] nu = n.nuclei.get(s.id);
                for (int k = 2; k < nu.length; k += 3) nu[k] = (int) (nu[k] * f);
                float area = 0;
                for (int c : claim) {
                    n.secSettlement[c] = (short) s.id;
                    area += landShare(c);
                }
                s.radius = Math.max(32, (int) (Math.sqrt(Math.max(1, area) * SECTOR_KM2 / Math.PI) * Nation.KM));
                classify(s, claim);
                spreadResidents(s, claim, pop);
            }
        districtsDone = true;
    }

    private int[] siteRect(Region.Settlement s) {
        int hw, hh;
        switch (s.kind) {
            case Region.S_BASE: hw = BASE_HW; hh = BASE_HH; break;
            case Region.S_PRISON: hw = PRISON_HW; hh = PRISON_HH; break;
            case Region.S_AIRBASE: hw = AIRBASE_HW; hh = (int) (AIRBASE_HW * 0.55f); break;
            case Region.S_AIRPORT: hw = AIRPORT_HW; hh = (int) (AIRPORT_HW * 0.55f); break;
            default: hw = NAVAL_R; hh = NAVAL_R;
        }
        return new int[]{s.cx - hw, s.cy - hh, s.cx + hw, s.cy + hh};
    }

    /** The normalised distance from s's nuclei (below 1 is inside), with a ragged edge. */
    private float dEff(Region.Settlement s, int[] nu, float f, float x, float y) {
        float best = Float.MAX_VALUE;
        for (int k = 0; k < nu.length; k += 3) {
            float dx = x - nu[k], dy = y - nu[k + 1], r = nu[k + 2] * f;
            best = Math.min(best, (float) Math.sqrt(dx * dx + dy * dy) / r);
        }
        float rag = nDist.value(x / (1.6f * Nation.KM) + s.id * 3.1f, y / (1.6f * Nation.KM)) * 0.7f
                + nDist.value(x / (0.6f * Nation.KM) - s.id * 1.7f, y / (0.6f * Nation.KM)) * 0.3f;
        return best * (1 + 0.24f * rag);
    }

    private List<Integer> claimSet(Region.Settlement s, float f) {
        int[] nu = n.nuclei.get(s.id);
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (int k = 0; k < nu.length; k += 3) {
            int r = (int) (nu[k + 2] * f * 1.35f) + Nation.SECT;
            x0 = Math.min(x0, nu[k] - r);
            y0 = Math.min(y0, nu[k + 1] - r);
            x1 = Math.max(x1, nu[k] + r);
            y1 = Math.max(y1, nu[k + 1] + r);
        }
        List<Integer> out = new ArrayList<Integer>();
        for (int sy = Math.max(0, y0 / Nation.SECT); sy <= Math.min(SW - 1, y1 / Nation.SECT); sy++)
            for (int sx = Math.max(0, x0 / Nation.SECT); sx <= Math.min(SW - 1, x1 / Nation.SECT); sx++) {
                int c = sy * SW + sx;
                if (n.secSettlement[c] >= 0 || landShare(c) < 0.4f) continue;
                if (dEff(s, nu, f, secCX(c), secCY(c)) < 1) out.add(c);
            }
        return out;
    }

    private void classify(Region.Settlement s, List<Integer> claim) {
        int[] nu = n.nuclei.get(s.id);
        boolean city = s.kind == Region.S_CITY;
        float kindBonus = s.capital ? 3 : city ? 1.5f : s.kind == Region.S_TOWN ? 0 : -2;
        float r0 = nu[2];
        for (int c : claim) {
            float x = secCX(c), y = secCY(c);
            float d = dEff(s, nu, 1, x, y);
            float d0 = (float) Math.sqrt((x - nu[0]) * (x - nu[0]) + (y - nu[1]) * (y - nu[1])) / r0;
            secD[c] = d;
            byte k;
            if (s.kind == Region.S_VILLAGE) k = d < 0.45f ? Nation.D_SUBURB : Nation.D_RURAL;
            else if (s.kind == Region.S_TOWN) k = d < 0.22f ? Nation.D_MIXED : d < 0.9f ? Nation.D_SUBURB : Nation.D_RURAL;
            else if (d0 < (s.capital ? 0.13f : 0.11f)) k = Nation.D_CORE;
            else if (d < 0.38f) k = Nation.D_DENSE;
            else if (d < 0.62f) k = Nation.D_MIXED;
            else if (d < 0.93f) k = Nation.D_SUBURB;
            else k = Nation.D_RURAL;
            if (k != Nation.D_CORE && s.kind != Region.S_VILLAGE) {
                float ind = nInd.value(x / (1.8f * Nation.KM) + s.id, y / (1.8f * Nation.KM));
                if (cWater[c] >= 2 || corridor[c]) ind += 0.12f;
                if (d > 0.35f && ind > (city ? 0.47f : 0.55f)) k = Nation.D_INDUSTRIAL;
                float park = nPark.value(x / (1.3f * Nation.KM) - s.id, y / (1.3f * Nation.KM));
                if (d > 0.1f && d < 0.95f && park > 0.62f) k = Nation.D_PARK;
                if (cWater[c] >= 6 && d < 0.9f && Rng.unit(seed, c, 0x51) < 0.5f) k = Nation.D_PARK;
            }
            n.district[c] = k;
            float dn = (1 - Math.min(1, d)) * 11 + 3 * nDist.value(x / 400f + 9, y / 400f) + kindBonus;
            n.density[c] = (byte) clamp(Math.round(dn), 1, 15);
        }
        // campuses: one for every 150,000 people, two-by-two sectors
        if (city) {
            int campuses = Math.max(1, n.targetPop.get(s.id) / 150000);
            for (int k = 0; k < campuses; k++)
                for (int t = 0; t < 30; t++) {
                    int c = claim.get(rng.nextInt(claim.size()));
                    if (secD[c] < 0.2f || secD[c] > 0.75f) continue;
                    if (n.district[c] != Nation.D_MIXED && n.district[c] != Nation.D_DENSE) continue;
                    int[] q = {c, c + 1, c + SW, c + SW + 1};
                    for (int m : q) if (m < SW * SW && n.secSettlement[m] == s.id && n.district[m] != Nation.D_CORE) n.district[m] = Nation.D_CAMPUS;
                    break;
                }
        }
    }

    /** People per km2 at density class 8, by district type. */
    private static final float[] DISTRICT_DENSITY = {0, 9000, 16000, 9000, 3500, 250, 0, 1500, 900};

    private void spreadResidents(Region.Settlement s, List<Integer> claim, int pop) {
        int m = claim.size();
        if (m == 0) {
            s.residents = 0;
            return;
        }
        double[] w = new double[m];
        double sum = 0;
        for (int i = 0; i < m; i++) {
            int c = claim.get(i);
            w[i] = DISTRICT_DENSITY[n.district[c]] * (0.5 + n.density[c] / 16.0) * landShare(c);
            sum += w[i];
        }
        if (sum <= 0) {
            Arrays.fill(w, 1);
            sum = m;
        }
        // largest remainders, ties by position, so the sum is exactly pop
        int given = 0;
        long[] frac = new long[m];
        for (int i = 0; i < m; i++) {
            double exact = pop * w[i] / sum;
            int fl = (int) Math.floor(exact);
            n.residents[claim.get(i)] = fl;
            given += fl;
            frac[i] = ((long) ((exact - fl) * 1e9) << 20) | (m - 1 - i);
        }
        Arrays.sort(frac);
        for (int k = 0; k < pop - given; k++) {
            int i = m - 1 - (int) (frac[m - 1 - k % m] & 0xFFFFF);
            n.residents[claim.get(i)]++;
        }
        s.residents = pop;
    }

    // ------------------------------------------------------------------ the street lattice

    /** Which minor streets each built sector keeps (superblocks merge lattice cells by dropping them). */
    private void streetMasks() {
        for (int c = 0; c < SW * SW; c++) {
            if (!n.built(c)) continue;
            float pv, ph;
            switch (n.district[c]) {
                case Nation.D_CORE: pv = ph = 1f; break;
                case Nation.D_DENSE: pv = ph = 0.88f; break;
                case Nation.D_MIXED: pv = ph = 0.72f; break;
                case Nation.D_SUBURB: {
                    boolean ns = Rng.unit(seed, c / SW / 3, 0x61) < 0.5f;
                    pv = ns ? 0.95f : 0.25f;
                    ph = ns ? 0.25f : 0.95f;
                    break;
                }
                case Nation.D_INDUSTRIAL: pv = ph = 0.15f; break;
                case Nation.D_CAMPUS: pv = 0; ph = 0.25f; break;
                case Nation.D_RURAL: pv = ph = 0.3f; break;
                default: pv = ph = 0;
            }
            int m = 0;
            for (int i = 0; i < 3; i++) {
                if (Rng.unit(seed, c, 0x100 + i) < pv) m |= 1 << i;
                if (Rng.unit(seed, c, 0x200 + i) < ph) m |= 8 << i;
            }
            n.lines[c] = (byte) m;
        }
    }

    private static long latKey(int X, int Y) {
        return ((long) (X / Nation.STREET) << 32) | (Y / Nation.STREET);
    }

    private int latNode(int X, int Y, int set) {
        long k = latKey(X, Y);
        int id = lat.get(k);
        if (id < 0) {
            id = roads.addNode(X * 16 + 8, Y * 16 + 8, set);
            lat.put(k, id);
        }
        return id;
    }

    private void lattice() {
        lat = new RoadGraph.LongIntMap(1 << 18);
        for (int s = 0; s < SW * SW; s++) {
            if (!n.built(s)) continue;
            int sx = s % SW, sy = s / SW, X0 = sx * Nation.SECT, Y0 = sy * Nation.SECT, set = n.secSettlement[s];
            int mv = n.lines[s] & 7, mh = (n.lines[s] >> 3) & 7;
            boolean up = sy > 0 && n.built(s - SW), left = sx > 0 && n.built(s - 1);
            boolean down = sy < SW - 1 && n.built(s + SW), right = sx < SW - 1 && n.built(s + 1);
            hLine(X0, Y0, mv | (up ? n.lines[s - SW] & 7 : 0), set, true);
            vLine(X0, Y0, mh | (left ? (n.lines[s - 1] >> 3) & 7 : 0), set, true);
            if (!down) hLine(X0, Y0 + Nation.SECT, mv, set, true);
            if (!right) vLine(X0 + Nation.SECT, Y0, mh, set, true);
            for (int i = 0; i < 3; i++) {
                if ((mh >> i & 1) != 0) hLine(X0, Y0 + Nation.STREET * (i + 1), mv, set, false);
                if ((mv >> i & 1) != 0) vLine(X0 + Nation.STREET * (i + 1), Y0, mh, set, false);
            }
            if (roadNode[s] < 0) {
                int id = lat.get(latKey(X0, Y0));
                if (id >= 0) {
                    roadNode[s] = id;
                    roadOwner[s] = (short) set;
                }
            }
        }
        // a sector whose top-left corner had no road still has a junction somewhere on its edge
        for (int s = 0; s < SW * SW; s++) {
            if (!n.built(s) || roadNode[s] >= 0) continue;
            int id = nearestCorner(s, secCX(s), secCY(s));
            if (id >= 0) {
                roadNode[s] = id;
                roadOwner[s] = n.secSettlement[s];
            }
        }
    }

    /** An east-west lattice line at Y across one sector from X0, split where the streets in 'split' meet it. */
    private void hLine(int X0, int Y, int split, int set, boolean avenue) {
        int a = 0;
        for (int i = 0; i <= 3; i++) {
            if (i < 3 && (split >> i & 1) == 0) continue;
            int b = Nation.STREET * (i + 1);
            segment(X0 + a, Y, X0 + b, Y, set, avenue);
            a = b;
        }
    }

    private void vLine(int X, int Y0, int split, int set, boolean avenue) {
        int a = 0;
        for (int i = 0; i <= 3; i++) {
            if (i < 3 && (split >> i & 1) == 0) continue;
            int b = Nation.STREET * (i + 1);
            segment(X, Y0 + a, X, Y0 + b, set, avenue);
            a = b;
        }
    }

    private void segment(int xa, int ya, int xb, int yb, int set, boolean avenue) {
        boolean horiz = ya == yb;
        boolean wet = segWet(xa, ya, xb, yb);
        if (wet && (!avenue || !shortCrossing(xa, ya, xb, yb))) return;
        int line = horiz ? ya : xa;
        int na = latNode(xa, ya, set), nb = latNode(xb, yb, set);
        int e = roads.addEdge(na, nb, avenue ? RoadGraph.AVENUE : RoadGraph.STREET, (Nation.lineHalf(line) + 0.5f) * 16);
        if (e >= 0) roads.ebridge[e] = wet;
    }

    /** True where the line runs over water: both coarse cells either side of it are wet somewhere along it. */
    private boolean segWet(int xa, int ya, int xb, int yb) {
        if (ya == yb) {
            int r1 = ya / Nation.CELL, r0 = r1 - 1;
            for (int cx = xa / Nation.CELL; cx < xb / Nation.CELL; cx++)
                if (wetCell(cx, r1) && (r0 < 0 || wetCell(cx, r0))) return true;
        } else {
            int c1 = xa / Nation.CELL, c0 = c1 - 1;
            for (int cy = ya / Nation.CELL; cy < yb / Nation.CELL; cy++)
                if (wetCell(c1, cy) && (c0 < 0 || wetCell(c0, cy))) return true;
        }
        return false;
    }

    private boolean wetCell(int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= CW || cy >= CW) return true;
        return Nation.wet(n.cover[cy * CW + cx]);
    }

    /** An avenue may bridge water only if dry land is near on both sides (a river, not the sea). */
    private boolean shortCrossing(int xa, int ya, int xb, int yb) {
        int max = 26; // cells: about 1.6 km of water at most
        boolean horiz = ya == yb;
        int a = (horiz ? xa : ya) / Nation.CELL, b = (horiz ? xb : yb) / Nation.CELL, line = horiz ? ya : xa;
        int len = b - a;
        for (int dir = -1; dir <= 1; dir += 2) {
            boolean dry = false;
            for (int k = 0; k < max && !dry; k++) {
                int c = dir < 0 ? a - 1 - k : b + k;
                if (!crossWet(horiz, line, c)) dry = true;
                else len++;
            }
            if (!dry) return false;
        }
        return len <= max;
    }

    /** Whether the lattice line at 'line' (tiles) is over water at coarse cell c along it. */
    private boolean crossWet(boolean horiz, int line, int c) {
        int r1 = line / Nation.CELL, r0 = r1 - 1;
        if (c < 0 || c >= CW) return true;
        return horiz ? wetCell(c, r1) && (r0 < 0 || wetCell(c, r0)) : wetCell(r1, c) && (r0 < 0 || wetCell(r0, c));
    }

    /** Bridges on avenues across water too wide to build on, between two parts of the same place. */
    private void bridges() {
        int maxGap = 8; // sectors (2 km)
        for (int pass = 0; pass < 2; pass++) {
            boolean horiz = pass == 0;
            for (int line = 1; line < SW; line++) {
                int Y = line * Nation.SECT;
                if (Y % (2 * Nation.AVENUE) != 0) continue; // every other avenue
                int k = 0;
                while (k < SW) {
                    // a built sector touching the line, then a gap of unbuilt ones, then another built
                    int s = horiz ? line * SW + k : k * SW + line;
                    if (!touchBuilt(horiz, line, k)) {
                        k++;
                        continue;
                    }
                    int g = k + 1;
                    while (g < SW && !touchBuilt(horiz, line, g)) g++;
                    if (g >= SW || g - k - 1 < 1 || g - k - 1 > maxGap) {
                        k = g;
                        continue;
                    }
                    int sa = owner(horiz, line, k), sb = owner(horiz, line, g);
                    if (sa != sb) {
                        k = g;
                        continue;
                    }
                    int a = horiz ? lat.get(latKey((k + 1) * Nation.SECT, Y)) : lat.get(latKey(Y, (k + 1) * Nation.SECT));
                    int b = horiz ? lat.get(latKey(g * Nation.SECT, Y)) : lat.get(latKey(Y, g * Nation.SECT));
                    boolean wetGap = horiz ? segWet((k + 1) * Nation.SECT, Y, g * Nation.SECT, Y) : segWet(Y, (k + 1) * Nation.SECT, Y, g * Nation.SECT);
                    if (a >= 0 && b >= 0 && wetGap) {
                        int e = roads.addEdge(a, b, RoadGraph.AVENUE, (Nation.lineHalf(Y) + 0.5f) * 16);
                        if (e >= 0) roads.ebridge[e] = true;
                    }
                    k = g;
                }
            }
        }
    }

    /** True if a built sector lies on either side of the line at position k along it. */
    private boolean touchBuilt(boolean horiz, int line, int k) {
        return owner(horiz, line, k) >= 0;
    }

    private int owner(boolean horiz, int line, int k) {
        int a = horiz ? (line - 1) * SW + k : k * SW + line - 1, b = horiz ? line * SW + k : k * SW + line;
        if (n.built(a)) return n.secSettlement[a];
        if (b < SW * SW && n.built(b)) return n.secSettlement[b];
        return -1;
    }

    /** The lattice junction on sector s's corners nearest (x, y) tiles, or -1. */
    private int nearestCorner(int s, float x, float y) {
        int X0 = (s % SW) * Nation.SECT, Y0 = (s / SW) * Nation.SECT;
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (int j = 0; j <= Nation.SECT; j += Nation.STREET)
            for (int i = 0; i <= Nation.SECT; i += Nation.STREET) {
                if (i % Nation.SECT != 0 && j % Nation.SECT != 0) continue; // on the avenues only
                int id = lat.get(latKey(X0 + i, Y0 + j));
                if (id < 0) continue;
                float dx = X0 + i - x, dy = Y0 + j - y, d = dx * dx + dy * dy;
                if (d < bd) {
                    bd = d;
                    best = id;
                }
            }
        return best;
    }

    /** Each place's central junction: the avenue junction nearest its centre. */
    private void hubs() {
        int ns = n.settlements.size();
        float[] bd = new float[ns];
        Arrays.fill(bd, Float.MAX_VALUE);
        for (int i = 0; i < roads.nodeCount; i++) {
            int s = roads.nset[i];
            if (s < 0) continue;
            int X = (int) (roads.nx[i] / 16), Y = (int) (roads.ny[i] / 16);
            if (X % Nation.SECT != 0 && Y % Nation.SECT != 0) continue;
            Region.Settlement st = n.settlements.get(s);
            float dx = X - st.cx, dy = Y - st.cy, d = dx * dx + dy * dy;
            if (d < bd[s]) {
                bd[s] = d;
                st.hubNode = i;
            }
        }
    }

    // ------------------------------------------------------------------ facilities

    private static final int[] FAC_SIDE = new int[Region.FKINDS];

    static {
        Arrays.fill(FAC_SIDE, 13);
        FAC_SIDE[Region.F_HOSPITAL] = FAC_SIDE[Region.F_POWER] = FAC_SIDE[Region.F_WATER] = FAC_SIDE[Region.F_FOODW] = 29;
        FAC_SIDE[Region.F_STATION] = FAC_SIDE[Region.F_DEPOT] = FAC_SIDE[Region.F_LAB] = FAC_SIDE[Region.F_GOV] = 29;
        FAC_SIDE[Region.F_PARK] = 45;
    }

    /** Police per 40,000 people, hospitals per 100,000, fire stations per 30,000 (every town has at least one). */
    public static int policeFor(int pop) {
        return Math.max(1, Math.round(pop / 40000f));
    }

    public static int hospitalsFor(int pop) {
        return Math.max(pop >= 8000 ? 1 : 0, Math.round(pop / 100000f));
    }

    public static int fireFor(int pop) {
        return Math.max(1, Math.round(pop / 30000f));
    }

    private final List<List<Integer>> claimOf = new ArrayList<List<Integer>>();
    private final List<List<Region.Facility>> facOf = new ArrayList<List<Region.Facility>>();

    private void facilities() {
        for (int i = 0; i < n.settlements.size(); i++) {
            claimOf.add(new ArrayList<Integer>());
            facOf.add(new ArrayList<Region.Facility>());
        }
        for (int c = 0; c < SW * SW; c++) if (n.built(c) && n.district[c] != Nation.D_PARK) claimOf.get(n.secSettlement[c]).add(c);
        for (Region.Settlement s : n.settlements) {
            int pop = n.targetPop.get(s.id);
            if (s.kind == Region.S_CITY) {
                many(s, Region.F_POLICE, policeFor(pop), 0.05f, 0.95f);
                many(s, Region.F_HOSPITAL, hospitalsFor(pop), 0.05f, 0.95f);
                many(s, Region.F_FIRE, fireFor(pop), 0.03f, 0.98f);
                place(s, Region.F_POWER, 0.6f, 1.2f);
                place(s, Region.F_WATER, 0.55f, 1.2f);
                place(s, Region.F_PHONE, 0.15f, 0.6f);
                place(s, Region.F_FUEL, 0.6f, 1.2f);
                place(s, Region.F_FOODW, 0.5f, 1.2f);
                place(s, Region.F_HALL, 0f, 0.15f);
                place(s, Region.F_STATION, 0.05f, 0.4f);
                if (s.capital) {
                    place(s, Region.F_LAB, 0.1f, 0.5f);
                    place(s, Region.F_GOV, 0f, 0.2f);
                }
                place(s, Region.F_DEPOT, 0.3f, 0.8f);
                place(s, Region.F_PARK, 0.15f, 0.5f);
            } else if (s.kind == Region.S_TOWN) {
                many(s, Region.F_POLICE, policeFor(pop), 0f, 0.8f);
                many(s, Region.F_HOSPITAL, hospitalsFor(pop), 0f, 0.7f);
                many(s, Region.F_FIRE, fireFor(pop), 0f, 0.9f);
                place(s, Region.F_FUEL, 0.4f, 1.2f);
                place(s, Region.F_ARMORY, 0.3f, 1.2f);
                place(s, Region.F_HALL, 0f, 0.4f);
                if (pop >= 20000) place(s, Region.F_STATION, 0.1f, 0.6f);
            }
        }
    }

    private void many(Region.Settlement s, int kind, int count, float dmin, float dmax) {
        for (int i = 0; i < count; i++) place(s, kind, dmin, dmax);
    }

    private Region.Facility place(Region.Settlement s, int kind, float dmin, float dmax) {
        List<Integer> cand = claimOf.get(s.id);
        if (cand.isEmpty()) return null;
        boolean big = FAC_SIDE[kind] > 13;
        for (int round = 0; round < 3; round++) {
            int best = -1;
            float bs = -Float.MAX_VALUE;
            int[] bestBlock = null;
            for (int t = 0; t < 48; t++) {
                int c = cand.get(rng.nextInt(cand.size()));
                float d = secD[c];
                if (round == 0 && (d < dmin || d > dmax)) continue;
                if (round < 2 && kind == Region.F_PARK && n.district[c] == Nation.D_INDUSTRIAL) continue;
                if (big ? reserved[c] != 0 : reserved[c] == -1) continue;
                int[] blk = big ? bigBlock(c, kind) : smallBlock(c);
                if (blk == null) continue;
                float x = (blk[0] + blk[2]) / 2f, y = (blk[1] + blk[3]) / 2f;
                float far = 1e5f;
                for (Region.Facility f : facOf.get(s.id)) {
                    float dx = f.x / 16 - x, dy = f.y / 16 - y, dd = (float) Math.sqrt(dx * dx + dy * dy);
                    far = Math.min(far, f.kind == kind ? dd : dd * 3);
                }
                float score = far + rng.nextFloat() * 40;
                int dk = n.district[c];
                if ((kind == Region.F_POWER || kind == Region.F_FUEL || kind == Region.F_FOODW || kind == Region.F_DEPOT || kind == Region.F_WATER) && dk == Nation.D_INDUSTRIAL) score += 2000;
                if ((kind == Region.F_HALL || kind == Region.F_GOV) && dk == Nation.D_CORE) score += 2000;
                if (kind == Region.F_LAB && dk == Nation.D_CAMPUS) score += 2000;
                if (score > bs) {
                    bs = score;
                    best = c;
                    bestBlock = blk;
                }
            }
            if (best < 0) continue;
            if (big) {
                reserved[best] = -1;
                if (kind == Region.F_PARK) n.lines[best] = 0;
                else n.lines[best] &= ~(1 | 8);
                bestBlock = Nation.block((best % SW) * Nation.SECT, (best / SW) * Nation.SECT, 0, n.lineOffsets(best, true)[1], 0, n.lineOffsets(best, false)[1]);
            } else {
                reserved[best] |= (short) (1 << bestBlock[4]);
            }
            Region.Facility f = facility(s, bestBlock, kind);
            facOf.get(s.id).add(f);
            return f;
        }
        return null;
    }

    /** The block a big facility would get in sector c (merging the first lattice cells), or null if it's wet. */
    private int[] bigBlock(int c, int kind) {
        int m = kind == Region.F_PARK ? 0 : n.lines[c] & ~(1 | 8);
        int xb = (m & 2) != 0 ? 32 : (m & 4) != 0 ? 48 : 64, yb = (m & 16) != 0 ? 32 : (m & 32) != 0 ? 48 : 64;
        int[] b = Nation.block((c % SW) * Nation.SECT, (c / SW) * Nation.SECT, 0, xb, 0, yb);
        return dryBlock(b) ? b : null;
    }

    /** A free, dry block in sector c, with its index in the sector as a fifth number, or null. */
    private int[] smallBlock(int c) {
        List<int[]> bl = n.blocks(c);
        int start = rng.nextInt(bl.size());
        for (int k = 0; k < bl.size(); k++) {
            int i = (start + k) % bl.size();
            if (i >= 15 || (reserved[c] >> i & 1) != 0) continue;
            int[] b = bl.get(i);
            if (!dryBlock(b)) continue;
            return new int[]{b[0], b[1], b[2], b[3], i};
        }
        return null;
    }

    private boolean dryBlock(int[] b) {
        for (int y = b[1] / Nation.CELL; y <= b[3] / Nation.CELL; y++)
            for (int x = b[0] / Nation.CELL; x <= b[2] / Nation.CELL; x++)
                if (!landCell(y * CW + x)) return false;
        return true;
    }

    /** The facility on block b, laid out as RegionGen lays out a facility's block. */
    private Region.Facility facility(Region.Settlement s, int[] b, int kind) {
        Region.Facility f = new Region.Facility();
        f.id = n.facilities.size();
        f.kind = kind;
        f.settlement = s.id;
        f.bx0 = b[0];
        f.by0 = b[1];
        f.bx1 = b[2];
        f.by1 = b[3];
        int w = b[2] - b[0] + 1, h = b[3] - b[1] + 1;
        int ix0 = b[0] + 1, iy0 = b[1] + 1, ix1 = b[2] - 1, iy1 = b[3] - 1;
        int bkind, floors, bwid, bhei;
        switch (kind) {
            case Region.F_POLICE: bkind = Region.B_POLICE; floors = 3; bwid = w / 2; bhei = h - 4; break;
            case Region.F_HOSPITAL: bkind = Region.B_HOSPITAL; floors = 7; bwid = w - 4; bhei = h / 2; break;
            case Region.F_POWER: bkind = Region.B_POWER; floors = 4; bwid = w / 2; bhei = h / 2; break;
            case Region.F_FUEL: bkind = Region.B_FUEL; floors = 2; bwid = w / 3; bhei = h / 3; break;
            case Region.F_LAB: bkind = Region.B_LAB; floors = 9; bwid = w - 4; bhei = h - 5; break;
            case Region.F_DEPOT: bkind = Region.B_DEPOT; floors = 2; bwid = w - 4; bhei = h / 3; break;
            case Region.F_ARMORY: bkind = Region.B_ARMORY; floors = 2; bwid = w / 2; bhei = h / 2; break;
            case Region.F_WATER: bkind = Region.B_WATER; floors = 2; bwid = w / 2; bhei = h / 2; break;
            case Region.F_PHONE: bkind = Region.B_PHONE; floors = 6; bwid = w / 2; bhei = h / 2; break;
            case Region.F_FOODW: bkind = Region.B_FOODW; floors = 2; bwid = w - 4; bhei = h / 2; break;
            case Region.F_FIRE: bkind = Region.B_FIRE; floors = 2; bwid = w / 2; bhei = h / 2; break;
            case Region.F_HALL: bkind = Region.B_HALL; floors = 5; bwid = w - 5; bhei = h - 6; break;
            case Region.F_STATION: bkind = Region.B_STATION; floors = 2; bwid = w - 4; bhei = h / 3; break;
            case Region.F_GOV: bkind = Region.B_GOV; floors = 6; bwid = w - 5; bhei = h - 6; break;
            case Region.F_PARK: bkind = -1; floors = 0; bwid = 0; bhei = 0; break;
            default: bkind = Region.B_OFFICE; floors = 3; bwid = w / 2; bhei = h / 2;
        }
        if (bkind >= 0) {
            bwid = Math.max(3, bwid);
            bhei = Math.max(3, bhei);
            int bx = ix0 + 1, by = iy0 + 1;
            int res = kind == Region.F_HOSPITAL ? 180 : kind == Region.F_LAB ? 90 : 0;
            f.building = n.addBuilding(bx, by, Math.min(bwid, ix1 - bx), Math.min(bhei, iy1 - by), bkind, floors, s.id, res, roof(bkind));
            if (kind == Region.F_POWER) n.addBuilding(ix1 - 5, iy1 - 5, 5, 5, Region.B_POWER, 6, s.id, 0, 0xFFB8B4AA);
        }
        f.x = ((ix0 + ix1) / 2f + (kind == Region.F_POLICE ? (ix1 - ix0) / 4f : 0)) * 16 + 8;
        f.y = (iy1 - 1) * 16 + 8;
        if (kind == Region.F_PARK) {
            f.x = (ix0 + ix1) / 2f * 16 + 8;
            f.y = (iy0 + iy1) / 2f * 16 + 8;
        }
        f.name = s.name + " " + Region.FNAMES[kind];
        n.facilities.add(f);
        return f;
    }

    private static int roof(int kind) {
        switch (kind) {
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
            case Region.B_WATER: return 0xFF6A8AA0;
            case Region.B_PHONE: return 0xFF7A7090;
            case Region.B_FOODW: return 0xFF9A8A60;
            case Region.B_FIRE: return 0xFFA83A30;
            case Region.B_HALL: return 0xFFB8AC90;
            case Region.B_STATION: return 0xFF8A6A50;
            case Region.B_TERMINAL: return 0xFFB0B8C0;
            case Region.B_DOCK: return 0xFF7A8088;
            case Region.B_GOV: return 0xFFD0C8B0;
            default: return 0xFF888888;
        }
    }

    // ------------------------------------------------------------------ large sites, as data

    private void sites() {
        for (Region.Settlement s : n.settlements) {
            switch (s.kind) {
                case Region.S_BASE: siteBase(s); break;
                case Region.S_PRISON: sitePrison(s); break;
                case Region.S_AIRBASE:
                case Region.S_AIRPORT: siteAirfield(s); break;
                case Region.S_NAVAL: siteNaval(s); break;
                default:
            }
        }
    }

    private int node(float tx, float ty, int set) {
        return roads.addNode(tx * 16 + 8, ty * 16 + 8, set);
    }

    private Region.Facility siteFacility(Region.Settlement s, int kind, float x, float y, int[] rect, String name) {
        Region.Facility f = new Region.Facility();
        f.id = n.facilities.size();
        f.kind = kind;
        f.settlement = s.id;
        f.x = x;
        f.y = y;
        if (rect != null) {
            f.bx0 = rect[0];
            f.by0 = rect[1];
            f.bx1 = rect[2];
            f.by1 = rect[3];
        }
        f.name = name;
        n.facilities.add(f);
        return f;
    }

    private void siteBase(Region.Settlement s) {
        int[] r = siteRect(s);
        int x0 = r[0], y0 = r[1], x1 = r[2], y1 = r[3], cx = s.cx, cy = s.cy;
        n.op(s.id, Nation.OP_FRAME, x0, y0, x1, y1, Tile.BASE, Tile.FENCE);
        n.op(s.id, Nation.OP_FILL, x0, cy - 2, x1, cy + 2, Tile.ROAD, 0);
        n.op(s.id, Nation.OP_FILL, cx - 2, y0, cx + 2, y1, Tile.ROAD, 0);
        int hub = node(cx, cy, s.id);
        int[][] ends = {{x0, cy}, {x1, cy}, {cx, y0}, {cx, y1}};
        for (int[] e : ends) {
            int g = node(e[0], e[1], s.id);
            roads.addEdge(hub, g, RoadGraph.AVENUE, 2.5f * 16);
            attach.get(s.id).add(g);
            s.edgeNodes.add(g);
        }
        s.hubNode = hub;
        // barracks to the north-west, the motor pool north-east, helipads south-west, the depot south-east
        for (int yy = y0 + 4; yy + 5 <= cy - 6; yy += 12)
            for (int xx = x0 + 4; xx + 11 <= cx - 6; xx += 16) n.addBuilding(xx, yy, 11, 5, Region.B_BARRACKS, 2, s.id, 0, roof(Region.B_BARRACKS));
        for (int xx = cx + 6; xx + 20 <= x1 - 4; xx += 26) n.addBuilding(xx, y0 + 4, 20, 12, Region.B_HANGAR, 2, s.id, 0, roof(Region.B_HANGAR));
        n.op(s.id, Nation.OP_FILL, cx + 6, y0 + 20, x1 - 4, cy - 6, Tile.LOT, 0);
        int pads = 0;
        for (int yy = cy + 8; yy + 8 <= y1 - 4 && pads < 6; yy += 24)
            for (int xx = x0 + 8; xx + 8 <= cx - 6 && pads < 6; xx += 24) {
                n.op(s.id, Nation.OP_FILL, xx, yy, xx + 7, yy + 7, Tile.PLAZA, 0);
                siteFacility(s, Region.F_HELIPAD, (xx + 4) * 16, (yy + 4) * 16, null, s.name + " Helipad");
                pads++;
            }
        n.addBuilding(cx + 8, cy + 8, 24, 12, Region.B_DEPOT, 2, s.id, 0, roof(Region.B_DEPOT));
        n.addBuilding(cx + 8, cy + 24, 8, 6, Region.B_FUEL, 1, s.id, 0, roof(Region.B_FUEL));
        siteFacility(s, Region.F_BASE, (cx + 8) * 16, (cy + 8) * 16, r, s.name);
    }

    private void sitePrison(Region.Settlement s) {
        int[] r = siteRect(s);
        int x0 = r[0], y0 = r[1], x1 = r[2], y1 = r[3], cx = s.cx, cy = s.cy;
        n.op(s.id, Nation.OP_FILL, x0, y0, x1, y1, Tile.YARD, 0);
        n.op(s.id, Nation.OP_OUTLINE, x0, y0, x1, y1, Tile.WALL, 0);
        n.op(s.id, Nation.OP_OUTLINE, x0 + 2, y0 + 2, x1 - 2, y1 - 2, Tile.FENCE, 0);
        n.op(s.id, Nation.OP_FILL, cx - 2, cy, cx + 2, y1 + 1, Tile.ROAD, 0);
        n.op(s.id, Nation.OP_FILL, cx - 10, cy - 4, cx + 10, cy + 4, Tile.PLAZA, 0);
        int hub = node(cx, cy, s.id), gate = node(cx, y1 + 1, s.id);
        roads.addEdge(hub, gate, RoadGraph.STREET, 2.5f * 16);
        s.hubNode = hub;
        s.edgeNodes.add(gate);
        attach.get(s.id).add(gate);
        int pop = n.targetPop.get(s.id);
        int[][] at = {{x0 + 6, y0 + 6}, {cx + 6, y0 + 6}, {x0 + 6, cy + 8}, {cx + 6, cy + 8}};
        for (int[] p : at) n.addBuilding(p[0], p[1], Math.min(18, cx - 10 - x0), 10, Region.B_CELLBLOCK, 3, s.id, pop / 4, roof(Region.B_CELLBLOCK));
        siteFacility(s, Region.F_PRISON, cx * 16 + 8, (cy + 1) * 16 + 8, r, s.name);
    }

    private void siteAirfield(Region.Settlement s) {
        boolean military = s.kind == Region.S_AIRBASE;
        int[] r = siteRect(s);
        int x0 = r[0], y0 = r[1], x1 = r[2], y1 = r[3], cx = s.cx, cy = s.cy, hh = cy - y0;
        n.op(s.id, Nation.OP_FRAME, x0, y0, x1, y1, military ? Tile.BASE : Tile.GRASS, Tile.FENCE);
        int ry = cy - hh / 2;
        n.op(s.id, Nation.OP_FILL, x0 + 24, ry - 6, x1 - 24, ry + 6, Tile.RUNWAY, 0);
        n.op(s.id, Nation.OP_FILL, x0 + 30, cy + 4, x1 - 30, cy + 30, Tile.LOT, 0);
        n.op(s.id, Nation.OP_FILL, cx - 4, ry + 7, cx + 4, cy + 4, Tile.LOT, 0);
        if (!military) n.addBuilding(cx - 40, cy + 34, 80, 20, Region.B_TERMINAL, 3, s.id, n.targetPop.get(s.id), roof(Region.B_TERMINAL));
        for (int bx = x0 + 30; bx + 30 <= x1 - 30; bx += 40) {
            if (!military && bx + 30 > cx - 44 && bx < cx + 44) continue;
            n.addBuilding(bx, cy + 34, 30, 20, Region.B_HANGAR, 2, s.id, 0, roof(Region.B_HANGAR));
        }
        n.op(s.id, Nation.OP_FILL, cx - 2, cy + 16, cx + 2, y1 + 1, Tile.ROAD, 0);
        int hub = node(cx, cy + 16, s.id), gate = node(cx, y1 + 1, s.id);
        roads.addEdge(hub, gate, RoadGraph.AVENUE, 2.5f * 16);
        s.hubNode = hub;
        s.edgeNodes.add(gate);
        attach.get(s.id).add(gate);
        siteFacility(s, military ? Region.F_AIRBASE : Region.F_AIRPORT, cx * 16 + 8, (cy + 16) * 16 + 8, r, s.name);
        if (military)
            for (int k = 0; k < 4; k++) {
                int hx = x0 + 40 + k * 20;
                n.op(s.id, Nation.OP_FILL, hx - 3, cy + 6, hx + 3, cy + 12, Tile.PLAZA, 0);
                siteFacility(s, Region.F_HELIPAD, hx * 16, (cy + 9) * 16, null, s.name + " Helipad");
            }
    }

    private void siteNaval(Region.Settlement s) {
        int rad = NAVAL_R, cx = s.cx, cy = s.cy;
        // the sea: the side with the most water
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int bd = 0, bw = -1;
        for (int d = 0; d < 4; d++) {
            int wc = 0;
            for (int k = 16; k <= rad + 400; k += 16) if (Nation.wet(n.coverAt(cx + dirs[d][0] * k, cy + dirs[d][1] * k))) wc++;
            if (wc > bw) {
                bw = wc;
                bd = d;
            }
        }
        int sx = dirs[bd][0], sy = dirs[bd][1];
        n.op(s.id, Nation.OP_LAND, cx - rad, cy - rad, cx + rad, cy + rad, Tile.BASE, 0);
        int shoreX = cx, shoreY = cy;
        for (int d = 0; d < rad + 400; d += 4) {
            int x = cx + sx * d, y = cy + sy * d;
            if (Nation.wet(n.coverAt(x, y))) {
                shoreX = x;
                shoreY = y;
                break;
            }
        }
        for (int p = -1; p <= 1; p++) {
            int ox = -sy * p * 24, oy = sx * p * 24;
            int ax = shoreX + ox - sx * 4, ay = shoreY + oy - sy * 4, bx = shoreX + ox + sx * 80, by = shoreY + oy + sy * 80;
            n.op(s.id, Nation.OP_WATER, Math.min(ax, bx) - (sx == 0 ? 3 : 0), Math.min(ay, by) - (sy == 0 ? 3 : 0),
                    Math.max(ax, bx) + (sx == 0 ? 3 : 0), Math.max(ay, by) + (sy == 0 ? 3 : 0), Tile.PLAZA, 0);
        }
        int lx = shoreX - sx * 50, ly = shoreY - sy * 50;
        n.addBuilding(lx - 26, ly - 8, 24, 16, Region.B_DOCK, 2, s.id, 0, roof(Region.B_DOCK));
        n.addBuilding(lx + 2, ly - 8, 24, 16, Region.B_DOCK, 2, s.id, 0, roof(Region.B_DOCK));
        int hub = node(shoreX - sx * 15, shoreY - sy * 15, s.id), gate = node(cx - sx * (rad + 2), cy - sy * (rad + 2), s.id);
        roads.addEdge(hub, gate, RoadGraph.STREET, 2f * 16);
        n.op(s.id, Nation.OP_LAND, Math.min(cx - sx * (rad + 2), shoreX - sx * 15) - 1, Math.min(cy - sy * (rad + 2), shoreY - sy * 15) - 1,
                Math.max(cx - sx * (rad + 2), shoreX - sx * 15) + 1, Math.max(cy - sy * (rad + 2), shoreY - sy * 15) + 1, Tile.ROAD, 0);
        s.hubNode = hub;
        s.edgeNodes.add(gate);
        attach.get(s.id).add(gate);
        Region.Facility f = siteFacility(s, Region.F_PORT, roads.nx[hub], roads.ny[hub], new int[]{cx - rad, cy - rad, cx + rad, cy + rad}, s.name);
        f.x = roads.nx[hub];
    }

    // ------------------------------------------------------------------ highways and roads

    private float[] gG;
    private int[] gPrev, gStamp, gClosed, gHeap;
    private float[] gKey;
    private int gCur;

    private short ownerOf(int c) {
        if (districtsDone) return n.secSettlement[c];
        float x = secCX(c), y = secCY(c);
        for (Region.Settlement s : n.settlements) {
            float dx = x - s.cx, dy = y - s.cy;
            if (dx * dx + dy * dy < (float) s.radius * s.radius) return (short) s.id;
        }
        return -1;
    }

    private float cellCost(int c, int sa, int sb, int mode) {
        float w = cWater[c] / 16f, d = cDeep[c] / 16f, r = cRock[c] / 16f, f = cForest[c] / 16f;
        float cost;
        if (mode == MODE_RAIL) {
            cost = 1.2f + f * 0.5f + r * 50;
            cost += w > 0.45f ? (d > 0.45f ? 70 : 25) : w * 6;
            if (railCell[c]) cost = Math.min(cost, 0.4f);
        } else {
            cost = 1 + f * 0.6f + r * 30;
            cost += w > 0.45f ? (d > 0.45f ? 60 : 16) : w * 4;
            if (roadNode[c] >= 0 && roadOwner[c] == -1) cost = Math.min(cost, 0.35f);
        }
        int o = ownerOf(c);
        if (o >= 0 && o != sa && o != sb) {
            int k = n.settlements.get(o).kind;
            cost += k == Region.S_VILLAGE ? 5 : !town(k) ? 400 : k == Region.S_TOWN ? 20 : 30;
        }
        return cost;
    }

    /**
     * A* over sectors from start to goal; with goal < 0, Dijkstra to the nearest sector with a road that isn't sa's.
     * Returns the cells, start first, or null.
     */
    private int[] gridSearch(int start, int goal, int sa, int sb, int mode, int limit) {
        int ns = SW * SW;
        if (gG == null) {
            gG = new float[ns];
            gPrev = new int[ns];
            gStamp = new int[ns];
            gClosed = new int[ns];
            gHeap = new int[1024];
            gKey = new float[1024];
        }
        gCur++;
        int hn = 0;
        gG[start] = 0;
        gPrev[start] = -1;
        gStamp[start] = gCur;
        gHeap[++hn] = start;
        gKey[hn] = 0;
        int ex = goal >= 0 ? goal % SW : 0, ey = goal >= 0 ? goal / SW : 0;
        float hw = mode == MODE_RAIL ? 1.1f : 0.9f;
        int found = -1, expanded = 0;
        while (hn > 0) {
            int c = gHeap[1];
            gHeap[1] = gHeap[hn];
            gKey[1] = gKey[hn];
            hn--;
            for (int i = 1; ; ) {
                int l = i * 2, rr = l + 1, m = i;
                if (l <= hn && gKey[l] < gKey[m]) m = l;
                if (rr <= hn && gKey[rr] < gKey[m]) m = rr;
                if (m == i) break;
                int t = gHeap[i]; gHeap[i] = gHeap[m]; gHeap[m] = t;
                float tk = gKey[i]; gKey[i] = gKey[m]; gKey[m] = tk;
                i = m;
            }
            if (gClosed[c] == gCur) continue;
            gClosed[c] = gCur;
            if (goal >= 0 ? c == goal : (roadNode[c] >= 0 && roadOwner[c] != sa && n.secSettlement[c] != sa && (roadOwner[c] < 0 || linked[roadOwner[c]]))) {
                found = c;
                break;
            }
            if (++expanded > limit) break;
            int cx = c % SW, cy = c / SW;
            for (int d = 0; d < 8; d++) {
                int nx = cx + DX[d], ny = cy + DY[d];
                if (nx < 0 || ny < 0 || nx >= SW || ny >= SW) continue;
                int ni = ny * SW + nx;
                if (gClosed[ni] == gCur) continue;
                float ng = gG[c] + cellCost(ni, sa, sb, mode) * (d >= 4 ? 1.414f : 1f);
                if (gStamp[ni] != gCur || ng < gG[ni]) {
                    gStamp[ni] = gCur;
                    gG[ni] = ng;
                    gPrev[ni] = c;
                    float key = ng;
                    if (goal >= 0) {
                        float hx = nx - ex, hy = ny - ey;
                        key += (float) Math.sqrt(hx * hx + hy * hy) * hw;
                    }
                    if (hn + 2 >= gHeap.length) {
                        gHeap = Arrays.copyOf(gHeap, gHeap.length * 2);
                        gKey = Arrays.copyOf(gKey, gKey.length * 2);
                    }
                    gHeap[++hn] = ni;
                    gKey[hn] = key;
                    for (int i = hn; i > 1 && gKey[i >> 1] > gKey[i]; i >>= 1) {
                        int t = gHeap[i]; gHeap[i] = gHeap[i >> 1]; gHeap[i >> 1] = t;
                        float tk = gKey[i]; gKey[i] = gKey[i >> 1]; gKey[i >> 1] = tk;
                    }
                }
            }
        }
        if (found < 0) return null;
        int len = 0;
        for (int c = found; c >= 0; c = gPrev[c]) len++;
        int[] out = new int[len];
        for (int c = found, i = len - 1; c >= 0; c = gPrev[c]) out[i--] = c;
        return out;
    }

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1}, DY = {0, 0, 1, -1, 1, -1, 1, -1};

    /** Smooths a polyline (Chaikin), keeping its ends. */
    private static List<float[]> smooth(List<float[]> pts, int times) {
        for (int it = 0; it < times; it++) {
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
        return pts;
    }

    private List<float[]> cellPoints(int[] cells, int from, int to) {
        List<float[]> pts = new ArrayList<float[]>();
        for (int i = from; i <= to; i++) pts.add(new float[]{secCX(cells[i]), secCY(cells[i])});
        return pts;
    }

    /**
     * Lays a road along pts (tiles) from junction a to junction b: junctions about every 'spacing' tiles, bridges
     * where it crosses water. Returns the junctions laid, a first and b last.
     */
    private List<Integer> layRoad(RoadGraph g, List<float[]> pts, int a, int b, byte kind, float half, float spacing,
                                  RoadGraph.LongIntMap share, short owner) {
        pts.set(0, new float[]{g.nx[a] / 16, g.ny[a] / 16});
        pts.set(pts.size() - 1, new float[]{g.nx[b] / 16, g.ny[b] / 16});
        pts = smooth(pts, 2);
        List<Integer> laid = new ArrayList<Integer>();
        laid.add(a);
        int prev = a;
        float acc = 0;
        for (int i = 1; i < pts.size(); i++) {
            float[] p0 = pts.get(i - 1), p1 = pts.get(i);
            float dx = p1[0] - p0[0], dy = p1[1] - p0[1];
            acc += (float) Math.sqrt(dx * dx + dy * dy);
            boolean last = i == pts.size() - 1;
            if (acc < spacing && !last) continue;
            int node = last ? b : shared(g, share, p1[0], p1[1], owner);
            if (node != prev) {
                int e = g.addEdge(prev, node, kind, half);
                if (e >= 0) g.ebridge[e] = crossesWater(g, prev, node);
                if (g == roads) mark(prev, node, owner);
                laid.add(node);
            }
            prev = node;
            acc = 0;
        }
        return laid;
    }

    /** A junction at (tx, ty), or one already within 40 tiles (so roads that meet share it). */
    private int shared(RoadGraph g, RoadGraph.LongIntMap share, float tx, float ty, short owner) {
        int cx = (int) (tx / 64), cy = (int) (ty / 64);
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                int id = share.get(((long) (cx + dx) << 32) | ((cy + dy) & 0xFFFFFFFFL));
                if (id >= 0) {
                    float ddx = g.nx[id] / 16 - tx, ddy = g.ny[id] / 16 - ty;
                    if (ddx * ddx + ddy * ddy < 40 * 40) return id;
                }
            }
        int id = g.addNode(tx * 16, ty * 16, -1);
        long k = ((long) cx << 32) | (cy & 0xFFFFFFFFL);
        if (share.get(k) < 0) share.put(k, id);
        return id;
    }

    private boolean crossesWater(RoadGraph g, int a, int b) {
        float ax = g.nx[a] / 16, ay = g.ny[a] / 16, bx = g.nx[b] / 16, by = g.ny[b] / 16;
        float len = (float) Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay));
        int steps = Math.max(2, (int) (len / 8));
        for (int i = 0; i <= steps; i++)
            if (Nation.wet(n.coverAt((int) (ax + (bx - ax) * i / steps), (int) (ay + (by - ay) * i / steps)))) return true;
        return false;
    }

    /** Records the sectors a new road edge runs through, so later roads can join it. */
    private void mark(int a, int b, short owner) {
        float ax = roads.nx[a] / 16, ay = roads.ny[a] / 16, bx = roads.nx[b] / 16, by = roads.ny[b] / 16;
        float len = (float) Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay));
        int steps = Math.max(1, (int) (len / 32));
        for (int i = 0; i <= steps; i++) {
            int c = secAt(ax + (bx - ax) * i / steps, ay + (by - ay) * i / steps);
            if (roadNode[c] < 0) {
                roadNode[c] = i * 2 <= steps ? a : b;
                roadOwner[c] = owner;
            }
        }
    }

    private boolean[] ramped; // places a highway has a ramp into
    private boolean[] linked; // places already joined to the capital's roads

    private void interstateRoads() {
        ramped = new boolean[n.settlements.size()];
        linked = new boolean[n.settlements.size()];
        linked[0] = true;
        hwNodeAt = new RoadGraph.LongIntMap(4096);
        for (int[] rec : interstates) {
            int len = rec.length - 2, sa = rec[len], sb = rec[len + 1];
            Region.Settlement a = n.settlements.get(sa), b = n.settlements.get(sb);
            if (a.hubNode < 0 || b.hubNode < 0) continue;
            List<Integer> laid = layRoad(roads, cellPoints(rec, 0, len - 1), a.hubNode, b.hubNode, RoadGraph.HIGHWAY, 3.5f * 16, 128, hwNodeAt, (short) -1);
            linked[sa] = linked[sb] = true;
            interchanges(laid);
        }
    }

    /** Ramps from a highway to the avenues of every place it runs through, about every 2 km. */
    private void interchanges(List<Integer> laid) {
        int run = -1, k = 0, lastRamp = -1;
        for (int i = 1; i < laid.size() - 1; i++) {
            int id = laid.get(i), c = secAt(roads.nx[id] / 16, roads.ny[id] / 16);
            int set = n.built(c) ? n.secSettlement[c] : -1;
            if (set != run) {
                run = set;
                k = 0;
            }
            if (set < 0) continue;
            boolean nextOut = i + 1 >= laid.size() - 1 || ownerBuilt(laid.get(i + 1)) != set;
            if (k % 4 == 0 || (nextOut && i - lastRamp > 2)) {
                int to = nearestCorner(c, roads.nx[id] / 16, roads.ny[id] / 16);
                if (to >= 0) {
                    roads.addEdge(id, to, RoadGraph.AVENUE, 2.5f * 16);
                    attach.get(set).add(to);
                    ramped[set] = true;
                    linked[set] = true;
                    lastRamp = i;
                }
            }
            k++;
        }
    }

    private int ownerBuilt(int node) {
        int c = secAt(roads.nx[node] / 16, roads.ny[node] / 16);
        return n.built(c) ? n.secSettlement[c] : -1;
    }

    /** Every town, site and village joins the nearest road that isn't its own. */
    private void connectors() {
        List<Region.Settlement> order = new ArrayList<Region.Settlement>();
        for (int kind : new int[]{Region.S_TOWN, -1, Region.S_VILLAGE})
            for (Region.Settlement s : n.settlements)
                if (kind == -1 ? !town(s.kind) : s.kind == kind) order.add(s);
        for (Region.Settlement s : order) {
            if (s.hubNode < 0) continue;
            if (s.kind == Region.S_TOWN && ramped[s.id]) continue; // a highway runs through it
            boolean siteKind = !town(s.kind);
            int from = siteKind ? s.edgeNodes.get(0) : s.hubNode;
            int start = secAt(roads.nx[from] / 16, roads.ny[from] / 16);
            int[] path = gridSearch(start, -1, s.id, -1, MODE_ROAD, 60000);
            if (path == null) continue;
            int i0 = 0;
            if (!siteKind) {
                while (i0 < path.length - 1 && n.secSettlement[path[i0]] == s.id) i0++;
                i0 = Math.max(0, i0 - 1);
                int c = path[i0];
                int corner = n.secSettlement[c] == s.id ? nearestCorner(c, secCX(path[Math.min(path.length - 1, i0 + 1)]), secCY(path[Math.min(path.length - 1, i0 + 1)])) : -1;
                if (corner >= 0) from = corner;
            }
            int t = path[path.length - 1];
            int to = roadNode[t];
            if (n.built(t)) {
                float px = path.length > 1 ? secCX(path[path.length - 2]) : secCX(t), py = path.length > 1 ? secCY(path[path.length - 2]) : secCY(t);
                int corner = nearestCorner(t, px, py);
                if (corner >= 0) to = corner;
                attach.get(n.secSettlement[t]).add(to);
            }
            if (to < 0 || to == from) continue;
            List<float[]> pts = cellPoints(path, i0, path.length - 1);
            if (pts.size() < 2) pts.add(new float[]{secCX(t), secCY(t)});
            float half = s.kind == Region.S_VILLAGE ? 1.5f * 16 : 2.5f * 16;
            layRoad(roads, pts, from, to, RoadGraph.AVENUE, half, 96, hwNodeAt, (short) -2);
            attach.get(s.id).add(from);
            linked[s.id] = true;
        }
    }

    /** Highways out of the country, where help from abroad comes in. */
    private void exits() {
        int[] sides = {0, 1, 2, 3};
        for (int i = 3; i > 0; i--) {
            int j = rng.nextInt(i + 1), t = sides[i];
            sides[i] = sides[j];
            sides[j] = t;
        }
        Region.Settlement cap = n.capital();
        int made = 0;
        for (int si = 0; si < 4 && made < 4; si++) {
            int side = sides[si];
            int bx = -1, by = -1;
            float bd = Float.MAX_VALUE;
            for (int k = 256; k < W - 256; k += 64) {
                int x = side == 0 ? 24 : side == 1 ? W - 25 : k, y = side == 2 ? 24 : side == 3 ? W - 25 : k;
                int cv = n.coverAt(x, y);
                if (Nation.wet(cv) || cv == Nation.C_ROCK || n.secSettlement[secAt(x, y)] >= 0) continue;
                float dx = x - cap.cx, dy = y - cap.cy, d = dx * dx + dy * dy + rng.nextFloat() * 4e6f;
                if (d < bd) {
                    bd = d;
                    bx = x;
                    by = y;
                }
            }
            if (bx < 0) continue;
            Region.Settlement from = null;
            float fd = Float.MAX_VALUE;
            for (Region.Settlement s : n.settlements) {
                if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN || s.hubNode < 0) continue;
                float dx = bx - s.cx, dy = by - s.cy, d = dx * dx + dy * dy;
                if (d < fd) {
                    fd = d;
                    from = s;
                }
            }
            if (from == null) continue;
            int[] path = gridSearch(secAt(bx, by), secAt(from.cx, from.cy), -1, from.id, MODE_ROAD, 400000);
            if (path == null) continue;
            int entry = roads.addNode(bx * 16 + 8, by * 16 + 8, -1);
            List<Integer> laid = layRoad(roads, cellPoints(path, 0, path.length - 1), entry, from.hubNode, RoadGraph.HIGHWAY, 3.5f * 16, 128, hwNodeAt, (short) -1);
            interchanges(laid);
            n.entryNodes.add(entry);
            made++;
        }
    }

    /** Joins every piece of road cut off from the capital (an island of streets across a river) to the nearest piece that isn't. */
    private void joinIslands() {
        int nn = roads.nodeCount;
        int[] parent = new int[nn];
        for (int i = 0; i < nn; i++) parent[i] = i;
        for (int e = 0; e < roads.edgeCount; e++) union(parent, roads.ea[e], roads.eb[e]);
        int main = find(parent, n.capital().hubNode);
        // the main network's junctions in a coarse grid, to find the nearest quickly
        int cell = 256, gw = W / cell + 1;
        int[] cnt = new int[gw * gw + 1];
        for (int i = 0; i < nn; i++) if (find(parent, i) == main && !inBlock(i)) cnt[gcell(i, cell, gw)]++;
        int[] start = new int[gw * gw + 1];
        for (int i = 0; i < gw * gw; i++) start[i + 1] = start[i] + cnt[i];
        int[] list = new int[start[gw * gw]];
        int[] fill = Arrays.copyOf(start, gw * gw);
        for (int i = 0; i < nn; i++) if (find(parent, i) == main && !inBlock(i)) list[fill[gcell(i, cell, gw)]++] = i;
        // one link per cut-off piece: from its junction nearest to the main network
        int[] bestFrom = new int[nn], bestTo = new int[nn];
        float[] bestD = new float[nn];
        Arrays.fill(bestD, Float.MAX_VALUE);
        Arrays.fill(bestFrom, -1);
        for (int i = 0; i < nn; i++) {
            int r = find(parent, i);
            if (r == main) continue;
            float x = roads.nx[i] / 16, y = roads.ny[i] / 16;
            if (inBlock(i)) continue;
            int gx = (int) (x / cell), gy = (int) (y / cell);
            // rings outward until something is found, then one ring more (a nearer junction may lie just beyond)
            int stopAt = Integer.MAX_VALUE;
            for (int ring = 0; ring <= gw && ring <= stopAt; ring++) {
                if (ring >= 2 && (ring - 1f) * cell * (ring - 1f) * cell > bestD[r]) break; // nothing nearer out there
                boolean any = false;
                for (int by = gy - ring; by <= gy + ring; by++)
                    for (int bx = gx - ring; bx <= gx + ring; bx++) {
                        if (Math.max(Math.abs(bx - gx), Math.abs(by - gy)) != ring || bx < 0 || by < 0 || bx >= gw || by >= gw) continue;
                        int b = by * gw + bx;
                        for (int k = start[b]; k < start[b + 1]; k++) {
                            int m = list[k];
                            any = true;
                            float ddx = roads.nx[m] / 16 - x, ddy = roads.ny[m] / 16 - y, d = ddx * ddx + ddy * ddy;
                            if (d < bestD[r]) {
                                bestD[r] = d;
                                bestFrom[r] = i;
                                bestTo[r] = m;
                            }
                        }
                    }
                if (any && stopAt == Integer.MAX_VALUE) stopAt = ring + 1;
            }
        }
        for (int r = 0; r < nn; r++) {
            if (bestFrom[r] < 0) continue;
            int e = roads.addEdge(bestFrom[r], bestTo[r], RoadGraph.AVENUE, 2.5f * 16);
            if (e >= 0) roads.ebridge[e] = crossesWater(roads, bestFrom[r], bestTo[r]);
        }
    }

    /** A lattice junction off the avenues (only minor streets meet there). */
    private boolean inBlock(int node) {
        int X = (int) (roads.nx[node] / 16), Y = (int) (roads.ny[node] / 16);
        return roads.nx[node] == X * 16 + 8 && roads.ny[node] == Y * 16 + 8 && X % Nation.STREET == 0 && Y % Nation.STREET == 0
                && X % Nation.AVENUE != 0 && Y % Nation.AVENUE != 0;
    }

    private int gcell(int node, int cell, int gw) {
        return (int) (roads.ny[node] / 16 / cell) * gw + (int) (roads.nx[node] / 16 / cell);
    }

    private static int find(int[] p, int a) {
        while (p[a] != a) {
            p[a] = p[p[a]];
            a = p[a];
        }
        return a;
    }

    private static void union(int[] p, int a, int b) {
        a = find(p, a);
        b = find(p, b);
        if (a != b) p[Math.max(a, b)] = Math.min(a, b);
    }

    // ------------------------------------------------------------------ railways

    private void railways() {
        railNodeAt = new RoadGraph.LongIntMap(1024);
        List<Region.Facility> stations = new ArrayList<Region.Facility>();
        for (Region.Facility f : n.facilities) if (f.kind == Region.F_STATION) stations.add(f);
        if (stations.size() < 2) return;
        for (Region.Facility f : stations) f.railNode = n.rails.addNode(f.x, f.y, f.settlement);
        int k = stations.size();
        boolean[] in = new boolean[k];
        int first = 0;
        for (int i = 0; i < k; i++) if (n.settlements.get(stations.get(i).settlement).capital) first = i;
        in[first] = true;
        List<int[]> pairs = new ArrayList<int[]>();
        for (int added = 1; added < k; added++) {
            int ba = -1, bb = -1;
            float bd = Float.MAX_VALUE;
            for (int a = 0; a < k; a++) {
                if (!in[a]) continue;
                for (int b = 0; b < k; b++) {
                    if (in[b]) continue;
                    float dx = stations.get(a).x - stations.get(b).x, dy = stations.get(a).y - stations.get(b).y, d = dx * dx + dy * dy;
                    if (d < bd) {
                        bd = d;
                        ba = a;
                        bb = b;
                    }
                }
            }
            if (bb < 0) break;
            in[bb] = true;
            pairs.add(new int[]{ba, bb});
        }
        for (int i = 0; i < k; i++) {
            if (i == first || n.settlements.get(stations.get(i).settlement).kind != Region.S_CITY) continue;
            boolean have = false;
            for (int[] p : pairs) if ((p[0] == first && p[1] == i) || (p[1] == first && p[0] == i)) have = true;
            if (!have) pairs.add(new int[]{first, i});
        }
        for (int[] p : pairs) railRoute(stations.get(p[0]), stations.get(p[1]));
        // international lines from the capital and one other city to the border
        int lines = 0;
        for (int i = 0; i < k && lines < 2; i++) {
            Region.Facility st = stations.get(i == 0 ? first : i);
            if (i > 0 && (i == first || n.settlements.get(st.settlement).kind != Region.S_CITY)) continue;
            float[] edge = landEdge(st.x / 16, st.y / 16, lines);
            if (edge == null) continue;
            Region.Facility border = new Region.Facility();
            border.settlement = -1;
            border.x = edge[0] * 16 + 8;
            border.y = edge[1] * 16 + 8;
            border.railNode = n.rails.addNode(border.x, border.y, -1);
            int before = n.rails.edgeCount;
            railRoute(st, border);
            if (n.rails.edgeCount > before) {
                n.railEntries.add(border.railNode);
                lines++;
            }
        }
    }

    private float[] landEdge(float x, float y, int skip) {
        float[] best = null;
        float bd = Float.MAX_VALUE;
        for (int side = 0; side < 4; side++)
            for (int k = 256; k < W - 256; k += 64) {
                int ex = side == 0 ? 24 : side == 1 ? W - 25 : k, ey = side == 2 ? 24 : side == 3 ? W - 25 : k;
                int cv = n.coverAt(ex, ey);
                if (Nation.wet(cv) || cv == Nation.C_ROCK || n.secSettlement[secAt(ex, ey)] >= 0) continue;
                float dx = ex - x, dy = ey - y, d = dx * dx + dy * dy;
                if (skip > 0 && !n.railEntries.isEmpty()) {
                    int prev = n.railEntries.get(0);
                    float px = n.rails.nx[prev] / 16 - ex, py = n.rails.ny[prev] / 16 - ey;
                    if (px * px + py * py < W * (float) W / 9f) continue;
                }
                if (d < bd) {
                    bd = d;
                    best = new float[]{ex, ey};
                }
            }
        return best;
    }

    private void railRoute(Region.Facility fa, Region.Facility fb) {
        int sa = fa.settlement, sb = fb.settlement;
        int[] path = gridSearch(secAt(fa.x / 16, fa.y / 16), secAt(fb.x / 16, fb.y / 16), sa, sb, MODE_RAIL, 400000);
        if (path == null) return;
        for (int c : path) railCell[c] = true;
        layRoad(n.rails, cellPoints(path, 0, path.length - 1), fa.railNode, fb.railNode, RoadGraph.HIGHWAY, 16, 160, railNodeAt, (short) -1);
    }

    // ------------------------------------------------------------------ land use, summaries, people

    private void landUse() {
        // built-up land, parks, sites
        for (int c = 0; c < SW * SW; c++) {
            int set = n.secSettlement[c];
            if (set < 0) continue;
            byte k;
            if (n.built(c)) k = n.district[c] == Nation.D_PARK ? Nation.C_PARK : Nation.C_URBAN;
            else continue;
            fillSector(c, k);
        }
        for (Region.Settlement s : n.settlements) {
            if (town(s.kind)) continue;
            int[] r = siteRect(s);
            byte k = s.kind == Region.S_AIRBASE || s.kind == Region.S_AIRPORT ? Nation.C_AIRFIELD : Nation.C_BASE;
            for (int cy = Math.max(0, r[1] / 16); cy <= Math.min(CW - 1, r[3] / 16); cy++)
                for (int cx = Math.max(0, r[0] / 16); cx <= Math.min(CW - 1, r[2] / 16); cx++)
                    if (landCell(cy * CW + cx) || n.cover[cy * CW + cx] == Nation.C_ROCK) n.cover[cy * CW + cx] = k;
        }
        // farmland: lowland open country, most of all round the towns
        boolean[] towns = new boolean[SW * SW];
        for (int c = 0; c < SW * SW; c++) towns[c] = n.built(c);
        final int[] td = distanceField(towns);
        final float sectKm = Nation.SECT / Nation.KM;
        rows(CW, new Rows() {
            public void row(int y) {
                for (int x = 0; x < CW; x++) {
                    int i = y * CW + x, k = n.cover[i];
                    if (k != Nation.C_GRASS && k != Nation.C_FOREST) continue;
                    float h = hgt[i];
                    float dk = td[(y / 4) * SW + x / 4] * sectKm;
                    float near = Math.max(0, 1 - dk / 10f);
                    float f = bilerp(farmMid, farmM, (x + 0.5f) / 2, (y + 0.5f) / 2);
                    float v = f + near * 0.55f + (0.55f - h) * 0.9f - Math.max(0, h - 0.62f) * 3; // high ground stays pasture and wood
                    if (k == Nation.C_FOREST) v -= 0.45f - near * 0.3f;
                    if (v > 0.32f) n.cover[i] = Nation.C_FARM;
                }
            }
        });
    }

    private void fillSector(int c, byte k) {
        int x0 = (c % SW) * 4, y0 = (c / SW) * 4;
        for (int y = y0; y < y0 + 4; y++)
            for (int x = x0; x < x0 + 4; x++) {
                int i = y * CW + x;
                if (!Nation.wet(n.cover[i])) n.cover[i] = k;
            }
    }

    private void finishGraphs() {
        roads.trim();
        roads.finish(n.px());
        roads.buildLevels();
        roads.buildEdgeBuckets(n.px(), Nation.SECT * Tile.SIZE);
        n.rails.trim();
        n.rails.finish(n.px());
        lat = null;
        hwNodeAt = null;
        railNodeAt = null;
        gG = null;
        gPrev = gStamp = gClosed = gHeap = null;
        gKey = null;
    }

    private void summaries() {
        int ns = SW * SW;
        int[][] cnt = new int[Nation.SUMS][ns];
        for (int cy = 0; cy < CW; cy++)
            for (int cx = 0; cx < CW; cx++) {
                int k = n.cover[cy * CW + cx], s = (cy / 4) * SW + cx / 4;
                if (k == Nation.C_DEEP) cnt[Nation.SUM_DEEP][s]++;
                if (k == Nation.C_DEEP || k == Nation.C_WATER) cnt[Nation.SUM_WATER][s]++;
                if (k == Nation.C_FOREST) cnt[Nation.SUM_FOREST][s]++;
                if (k == Nation.C_ROCK) cnt[Nation.SUM_ROCK][s]++;
                if (k == Nation.C_FARM) cnt[Nation.SUM_FARM][s]++;
                if (k == Nation.C_URBAN || k == Nation.C_BASE || k == Nation.C_AIRFIELD) cnt[Nation.SUM_BUILT][s]++;
            }
        // road: coarse cells a road runs through
        boolean[] road = new boolean[CW * CW];
        for (int e = 0; e < roads.edgeCount; e++) {
            float ax = roads.nx[roads.ea[e]] / 16, ay = roads.ny[roads.ea[e]] / 16, bx = roads.nx[roads.eb[e]] / 16, by = roads.ny[roads.eb[e]] / 16;
            int steps = Math.max(1, (int) (roads.elen[e] / 16 / 8));
            for (int i = 0; i <= steps; i++) road[cellAt(ax + (bx - ax) * i / steps, ay + (by - ay) * i / steps)] = true;
        }
        for (int i = 0; i < road.length; i++) if (road[i]) cnt[Nation.SUM_ROAD][((i / CW) / 4) * SW + (i % CW) / 4]++;
        for (int k = 0; k < Nation.SUMS; k++)
            for (int s = 0; s < ns; s++) n.sums[k][s] = (byte) Math.min(255, cnt[k][s] * 255 / 16);
    }

    /** People in the countryside: 20-50 per km2 on farmland and grass, a few in the woods. */
    private void countryside() {
        float[] perKm2 = new float[11];
        perKm2[Nation.C_FARM] = 45;
        perKm2[Nation.C_GRASS] = 28;
        perKm2[Nation.C_FOREST] = 6;
        perKm2[Nation.C_SAND] = 10;
        for (int s = 0; s < SW * SW; s++) {
            if (n.secSettlement[s] >= 0) continue;
            int x0 = (s % SW) * 4, y0 = (s / SW) * 4;
            float want = 0;
            for (int y = y0; y < y0 + 4; y++) for (int x = x0; x < x0 + 4; x++) want += perKm2[n.cover[y * CW + x]];
            want *= SECTOR_KM2 / 16;
            n.residents[s] = (int) Math.floor(want + Rng.unit(seed, s, 0x77));
        }
    }

    private void statesAndNetworks() {
        for (Region.Settlement s : n.settlements) {
            if (s.kind != Region.S_CITY) continue;
            Region.State st = new Region.State();
            st.id = n.states.size();
            st.capitalCity = s.id;
            st.name = Names.state(rng);
            n.states.add(st);
        }
        for (Region.Settlement s : n.settlements) {
            float bd = Float.MAX_VALUE;
            for (Region.State st : n.states) {
                Region.Settlement c = n.settlements.get(st.capitalCity);
                float dx = c.cx - s.cx, dy = c.cy - s.cy, d = dx * dx + dy * dy;
                if (d < bd) {
                    bd = d;
                    s.state = st.id;
                }
            }
        }
        n.gridCount = network(Region.F_POWER, 0);
        n.waterCount = network(Region.F_WATER, 1);
        n.phoneCount = network(Region.F_PHONE, 2);
        for (Region.Facility f : n.facilities) {
            f.node = roads.nearestNode(f.x, f.y, 16000);
            if (f.grid < 0 && f.settlement >= 0 && f.kind != Region.F_POWER) f.grid = n.settlements.get(f.settlement).grid;
        }
        for (Region.Settlement s : n.settlements) {
            for (int id : attach.get(s.id)) if (!s.edgeNodes.contains(id)) s.edgeNodes.add(id);
            if (s.edgeNodes.isEmpty() && s.hubNode >= 0) s.edgeNodes.add(s.hubNode);
        }
    }

    private int network(int kind, int which) {
        List<Region.Facility> plants = new ArrayList<Region.Facility>();
        for (Region.Facility f : n.facilities) if (f.kind == kind) plants.add(f);
        for (int i = 0; i < plants.size(); i++) {
            plants.get(i).network = i;
            if (which == 0) plants.get(i).grid = i;
        }
        for (Region.Settlement s : n.settlements) {
            if (!town(s.kind)) continue;
            float bd = Float.MAX_VALUE;
            int best = -1;
            for (Region.Facility p : plants) {
                float dx = p.x / 16 - s.cx, dy = p.y / 16 - s.cy, d = dx * dx + dy * dy;
                if (d < bd) {
                    bd = d;
                    best = p.network;
                }
            }
            if (which == 0) s.grid = best;
            else if (which == 1) s.water = best;
            else s.phone = best;
        }
        return plants.size();
    }
}
