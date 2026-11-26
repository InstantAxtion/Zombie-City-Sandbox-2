package com.instantaxtion.zcs2;

import java.io.FileInputStream;

/**
 * Baselines for the move to real-scale maps (docs/SCALE_PLAN.md):
 *   world:  today's maps: memory by component, build time, the once-a-second update and fast-forward speed;
 *   nation: the national coarse layer at each national size: build time, memory, people, road graph, routing;
 *   save:   save sizes (raw and compressed) of an outbreak with an air strike, dense and sparse, at 1024, 2304, 3072;
 *   sectors: the once-a-second update and a fast-forward game day on a national map's sectors (P3), for a quiet
 *            country, a war in the capital and the whole country collapsing.
 *   java -Xmx2g ... ScaleProbe [world|nation|save|sectors|all] [sizes...]
 */
public final class ScaleProbe {
    public static void main(String[] a) throws Exception {
        String what = a.length > 0 ? a[0] : "all";
        if (what.equals("world") || what.equals("all")) {
            Content c = Content.load(new FileInputStream("assets/content.ini"));
            int[] sizes = a.length > 1 && what.equals("world") ? parse(a) : new int[]{1536, 2304, 3072};
            for (int size : sizes) world(c, size);
        }
        if (what.equals("game")) {
            Content c = Content.load(new FileInputStream("assets/content.ini"));
            int size = a.length > 1 ? Integer.parseInt(a[1]) : 28672;
            int days = a.length > 2 ? Integer.parseInt(a[2]) : 5;
            String[] scen = a.length > 3 ? java.util.Arrays.copyOfRange(a, 3, a.length) : new String[]{"lab_leak", "hold_city"};
            for (String s : scen) game(c, size, days, s);
        }
        if (what.equals("save")) {
            Content c = Content.load(new FileInputStream("assets/content.ini"));
            int[] sizes = a.length > 1 ? parse(a) : new int[]{1024, 2304, 3072};
            for (int size : sizes) {
                save(c, size, false);
                save(c, size, true);
            }
        }
        if (what.equals("nation") || what.equals("all")) {
            int[] sizes = a.length > 1 && what.equals("nation") ? parse(a) : Nation.SIZES;
            for (int size : sizes) nation(size);
        }
        if (what.equals("sectors") || what.equals("all")) {
            Content c = Content.load(new FileInputStream("assets/content.ini"));
            int size = a.length > 1 && what.equals("sectors") ? Integer.parseInt(a[1]) : 28672;
            for (int k = 0; k < 4; k++) sectors(c, size, k);
        }
    }

    /**
     * A real game on a national map (P7): each scenario for two game hours at 1x with the camera over the start, then
     * days of fast-forward. Prints memory, step times, the active set and cache, and the outbreak's story: police
     * sent from which stations, formations and where they went, refugee columns, hordes and their speed in km/h.
     *   ScaleProbe game [size] [days] [scenario...]
     */
    static void game(Content c, int size, int days, String scen) throws Exception {
        System.out.printf("%n=== %s on %d tiles (%.0f km) ===%n", scen, size, size * 4 / 1000f);
        long base = Mem.used();
        long t0 = System.nanoTime();
        World w = Setup.newGame(c, size, RegionGen.COAST, 7, c.scenario(scen), 2);
        long ms = (System.nanoTime() - t0) / 1000000;
        Region r = w.r;
        Region.Settlement at = r.settlementAt(Setup.outbreakX, Setup.outbreakY);
        System.out.printf("built in %d ms: %s, %d people in %d settlements, %d facilities, %d junctions; starts at %s (%.1f, %.1f km)%n", ms, r.name,
                w.sec.totalPop(), r.settlements.size(), r.facilities.size(), r.roads.nodeCount, at != null ? at.name : "open country",
                Setup.outbreakX / 4000f, Setup.outbreakY / 4000f);
        System.out.printf("memory after the build: %d MB (heap in use)%n", (Mem.used() - base) >> 20);
        w.camX0 = Setup.originX - 1000; w.camX1 = Setup.originX + 1000; w.camY0 = Setup.originY - 1800; w.camY1 = Setup.originY + 1800;
        w.camDetail = true;
        int police0 = 0;
        for (Region.Facility f : r.facilities) if (f.kind == Region.F_POLICE) police0 += w.forces.armedAt(f, Ents.COP);
        System.out.printf("police waiting in %d stations: %d officers%n", count(r, Region.F_POLICE), police0);
        int newsSeen = 0;
        long stepNs = 0, worst = 0, peakMem = 0;
        int stepN = 0;
        java.util.Map<Integer, String> squadSeen = new java.util.HashMap<Integer, String>();
        for (int k = 1; k <= 2 * 60 * 20; k++) {
            long s0 = System.nanoTime();
            w.step();
            long d = System.nanoTime() - s0;
            if (k > 40) {
                stepNs += d;
                stepN++;
                worst = Math.max(worst, d);
            }
            // squads leaving stations: from where, how far from the start
            for (Squads.Squad s : w.sq.list)
                if (s.alive && s.home >= 0 && !squadSeen.containsKey(s.id)) {
                    Region.Facility f = r.facilities.get(s.home);
                    float km = (float) Math.hypot(f.x - Setup.outbreakX, f.y - Setup.outbreakY) / 4000f;
                    squadSeen.put(s.id, f.name);
                    if (squadSeen.size() <= 12) System.out.printf("  %s  %s sent from %s, %.1f km from the start%n", w.clock(), s.name, f.name, km);
                }
            if (k % 1200 == 0) {
                long mem = Mem.used() - base;
                peakMem = Math.max(peakMem, mem);
                System.out.printf("%s  dead %d (%d walking)  people %d  police out %d  active chunks %d  cache %d (%d made, %d misses)  %d MB  step %.2f ms (worst %.1f)  %s%n",
                        w.clock(), w.totalZombies(), w.sp.zCount, w.livingCivilians(), w.sq.count(Ents.COP), w.act.activeCount(), r.cache.size(), r.cache.generated,
                        r.misses, mem >> 20, stepNs / 1e6 / Math.max(1, stepN), worst / 1e6, Audit.check(w).isEmpty() ? "audit ok" : "AUDIT " + Audit.check(w));
                stepNs = worst = 0;
                stepN = 0;
                newsSeen = story(w, newsSeen, 14);
            }
        }
        java.util.Map<String, Integer> byStation = new java.util.TreeMap<String, Integer>();
        for (String f : squadSeen.values()) byStation.put(f, (byStation.containsKey(f) ? byStation.get(f) : 0) + 1);
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> en : byStation.entrySet()) {
            Region.Facility f = null;
            for (Region.Facility g : r.facilities) if (g.name.equals(en.getKey())) f = g;
            float km = f == null ? -1 : (float) Math.hypot(f.x - Setup.outbreakX, f.y - Setup.outbreakY) / 4000f;
            sb.append(String.format("%n    %3d from %s (%.1f km)", en.getValue(), en.getKey(), km));
        }
        System.out.printf("squads sent in two hours: %d, by station:%s%n", squadSeen.size(), sb);
        // fast-forward
        w.camDetail = false;
        w.setStrategic(true);
        java.util.Map<Integer, float[]> hordeAt = new java.util.HashMap<Integer, float[]>();
        long ffNs = 0, ffWorst = 0;
        int ffN = 0;
        for (int k = 1; k <= days * 144; k++) {
            long s0 = System.nanoTime();
            w.step();
            long d = System.nanoTime() - s0;
            ffNs += d;
            ffN++;
            ffWorst = Math.max(ffWorst, d);
            if (k % 72 == 0) {
                long mem = Mem.used() - base;
                peakMem = Math.max(peakMem, mem);
                // hordes: how far each moved in the last half day
                StringBuilder hs = new StringBuilder();
                int shown = 0;
                for (Strategic.Horde h : w.strat.hordes) {
                    if (!h.alive) continue;
                    float[] p = hordeAt.get(h.id);
                    if (p != null && shown < 4 && (p[0] != h.x || p[1] != h.y)) {
                        float kmh = (float) Math.hypot(h.x - p[0], h.y - p[1]) / 4000f / 12f;
                        hs.append(String.format(" [%d dead, %.2f km/h]", h.count, kmh));
                        shown++;
                    }
                    hordeAt.put(h.id, new float[]{h.x, h.y});
                }
                int cols = 0, colPeople = 0;
                for (Strategic.Column cl : w.strat.columns)
                    if (cl.alive) {
                        cols++;
                        colPeople += cl.count;
                    }
                int fm = 0;
                for (Forces.Formation f : w.forces.formations) if (f.alive) fm++;
                System.out.printf("%s  dead %d  people %d  hordes %d%s  refugee columns %d (%d people)  formations %d  gov %d  %d MB  update %.2f ms (worst %.1f)  awake districts %d%n",
                        w.clock(), w.totalZombies(), w.livingCivilians(), w.strat.hordes.size(), hs, cols, colPeople, fm, w.gov.level, mem >> 20,
                        ffNs / 1e6 / ffN, ffWorst / 1e6, w.sec.idx.awakeDistricts());
                ffNs = ffWorst = 0;
                ffN = 0;
                newsSeen = story(w, newsSeen, 10);
            }
        }
        for (Forces.Formation f : w.forces.formations)
            if (f.alive) {
                String home = f.home >= 0 ? r.facilities.get(f.home).name : "-";
                System.out.printf("  formation %s (home %s): %s%s at (%.1f, %.1f km)%n", f.name, home, Forces.STATES[f.state],
                        f.task >= 0 ? ", sent to " + r.settlements.get(f.task).name : "", f.gx / 4000f, f.gy / 4000f);
            }
        System.out.printf("peak memory %d MB, now %d MB; audit %s; %d chunks made, %d reads of pages not in memory%n", peakMem >> 20, (Mem.used() - base) >> 20,
                Audit.check(w).isEmpty() ? "ok" : Audit.check(w), r.cache.generated, r.misses);
        w.shutdown();
    }

    private static int count(Region r, int kind) {
        int n = 0;
        for (Region.Facility f : r.facilities) if (f.kind == kind) n++;
        return n;
    }

    /** The radio's new lines (at most max of them). */
    private static int story(World w, int from, int max) {
        int n = w.news.log.size();
        for (int i = Math.max(from, n - max); i < n; i++) System.out.println("      | " + w.news.log.get(i).text);
        return n;
    }

    private static int[] parse(String[] a) {
        int[] s = new int[a.length - 1];
        for (int i = 1; i < a.length; i++) s[i - 1] = Integer.parseInt(a[i]);
        return s;
    }

    static void world(Content c, int size) throws Exception {
        long base = Mem.used();
        long t0 = System.nanoTime();
        World w = Setup.newGame(c, size, RegionGen.COAST, 7, c.scenario("sandbox"), 2);
        long ms = (System.nanoTime() - t0) / 1000000;
        for (int k = 0; k < 40; k++) w.step();
        long u = Mem.used() - base;
        System.out.printf("world %d tiles: %d MB, built in %d ms, %d buildings, %d people, %d sectors, %d junctions%n", size, u >> 20, ms,
                w.r.bCount, w.sec.totalPop(), w.sec.n, w.r.roads.nodeCount);
        for (String line : Mem.components(w)) System.out.println("    " + line);
        // street level: the step with the once-a-second update against the others
        long sec = 0, plain = 0;
        int ns = 0, np = 0;
        for (int k = 0; k < 400; k++) {
            boolean s = (w.tick + 1) % 20 == 0;
            long s0 = System.nanoTime();
            w.step();
            long d = System.nanoTime() - s0;
            if (s) {
                sec += d;
                ns++;
            } else {
                plain += d;
                np++;
            }
        }
        System.out.printf("    street step %.2f ms; the once-a-second update adds %.2f ms%n", plain / 1e6 / np, (sec / (double) ns - plain / (double) np) / 1e6);
        System.out.println("    " + Probe.fastForward(w, 432));
        w.shutdown();
    }

    /**
     * A world with a national map's sectors, filled from a Nation (until P7 there are no chunks behind it: every
     * page is one shared page of grass, or of water where the nation's sector is mostly water, so the tiles cost
     * nothing; no facilities, roads or buildings). Then: 0 a quiet country, 1 the capital's streets full of the dead,
     * 2 every settlement's streets, 3 every sector where anyone lives.
     */
    static World nationalWorld(Content c, Nation n) {
        int size = n.size;
        Region r = new Region(size, size, n.seed, n.preset, Nation.SECT, false);
        byte[] grass = new byte[4096], water = new byte[4096], open = new byte[1024], shut = new byte[1024];
        java.util.Arrays.fill(grass, (byte) Tile.GRASS);
        java.util.Arrays.fill(water, (byte) Tile.WATER);
        java.util.Arrays.fill(open, (byte) 1);
        int ns = r.secW * r.secH;
        for (int s = 0; s < ns; s++) {
            boolean wet = n.sum(Nation.SUM_WATER, s) > 160;
            r.store.tile[s] = wet ? water : grass;
            r.store.nav[s] = wet ? shut : open;
        }
        r.name = n.name;
        for (Region.Settlement st : n.settlements) {
            st.hubNode = -1;
            st.edgeNodes.clear();
            st.grid = st.water = st.phone = -1;
            r.settlements.add(st);
        }
        World w = new World(c, r, n.seed, 2, true);
        Sectors sec = w.sec;
        for (int s = 0; s < sec.n; s++) {
            sec.settlement[s] = n.secSettlement[s];
            sec.home[s] = sec.popIn[s] = n.residents[s];
        }
        sec.connectivity();
        sec.idx.reindex();
        return w;
    }

    static void sectors(Content c, int size, int which) {
        Nation n = NationGen.generate(size, RegionGen.COAST, 7, 2);
        World w = nationalWorld(c, n);
        Sectors sec = w.sec;
        String what;
        if (which == 0) what = "quiet country";
        else if (which == 1) {
            // the capital's streets: about 5k sectors awake
            int cap = n.capital().id, put = 0;
            for (int s : sec.idx.bySettlement[cap])
                if (sec.home[s] > 0) {
                    sec.zIn[sec.touch(s)] += 40;
                    put++;
                }
            what = "war in the capital (" + put + " sectors with the dead)";
        } else {
            // 2: every town and city falls (the dead in each settlement's streets); 3: everywhere anyone lives, the
            // farms and the countryside too
            int put = 0;
            for (int s = 0; s < sec.n; s++)
                if (sec.home[s] > 0 && (which == 3 || sec.settlement[s] >= 0)) {
                    sec.zIn[sec.touch(s)] += Math.max(10, sec.home[s] / 20);
                    put++;
                }
            what = (which == 2 ? "every town falls (" : "the whole country, fields too (") + put + " sectors with the dead)";
        }
        System.out.printf("sectors %d tiles, %s: %d sectors, %d districts, %d people%n", size, what, sec.n, sec.idx.dn, sec.totalPop());
        // street speed: the step that runs the once-a-second update against those that don't
        for (int k = 0; k < 60; k++) w.step();
        long secNs = 0, plainNs = 0;
        int sn = 0, pn = 0;
        long awake = 0;
        for (int k = 0; k < 600; k++) {
            boolean s1 = (w.tick + 1) % 20 == 0;
            long t0 = System.nanoTime();
            w.step();
            long d = System.nanoTime() - t0;
            if (s1) {
                secNs += d;
                sn++;
                awake += sec.idx.lastAwakeSectors;
            } else {
                plainNs += d;
                pn++;
            }
        }
        System.out.printf("    1x: the once-a-second update %.2f ms (a plain step %.2f ms), %d sectors awake%n", (secNs / (double) sn - plainNs / (double) pn) / 1e6,
                plainNs / 1e6 / pn, awake / sn);
        for (int pass = 0; pass < 2; pass++) {
            boolean stag = pass == 1;
            sec.idx.stagger = stag;
            w.setStrategic(true);
            for (int k = 0; k < 20; k++) w.step();
            long t0 = System.nanoTime();
            awake = 0;
            for (int k = 0; k < 144; k++) {
                w.step();
                awake += sec.idx.lastAwakeSectors;
            }
            double ms = (System.nanoTime() - t0) / 1e6;
            System.out.printf("    fast-forward%s: %.2f ms an update, a game day in %.2f s, %d sectors awake, %d dead, %d people%n", stag ? " (staggered)" : "", ms / 144, ms / 1000,
                    awake / 144, w.totalZombies(), sec.totalPop());
            w.setStrategic(false);
        }
        w.shutdown();
    }

    static void nation(int size) {
        long best = Long.MAX_VALUE;
        Nation n = null;
        for (int i = 0; i < 2; i++) {
            n = null;
            long base = Mem.used();
            n = NationGen.generate(size, RegionGen.COAST, 7, 2);
            best = Math.min(best, n.buildMs);
            if (i == 1) {
                long u = Mem.used() - base;
                System.out.printf("nation %d tiles (%.0f km): built in %d ms (best of 2), %.1f MB measured, %.1f MB by its arrays%n", size, size / Nation.KM, best,
                        u / 1048576.0, n.memoryBytes() / 1048576.0);
            }
        }
        System.out.println("    " + NationShot.summary(n));
        int[] kinds = new int[Region.FKINDS];
        for (Region.Facility f : n.facilities) kinds[f.kind]++;
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < Region.FKINDS; k++) if (kinds[k] > 0) sb.append(Region.FNAMES[k]).append(' ').append(kinds[k]).append(", ");
        System.out.println("    facilities: " + sb);
        StringBuilder cs = new StringBuilder();
        for (Region.Settlement s : n.settlements)
            if (s.kind == Region.S_CITY) cs.append(s.name).append(' ').append(s.residents).append(" (").append(String.format("%.1f", s.radius * 2 / Nation.KM)).append(" km), ");
        System.out.println("    cities: " + cs);
        RoadGraph g = n.roads;
        System.out.printf("    roads: %d junctions, %d edges, level 1 %d junctions; edge buckets %d entries; graph %.1f MB%n", g.nodeCount, g.edgeCount, g.l1Count,
                g.chunkEdge.length, g.memoryBytes() / 1048576.0);
        Rng r = new Rng(5);
        double sum = 0, worst = 0;
        long t1 = 0, t2 = 0, s1 = 0, s2 = 0;
        int k = 0;
        while (k < 30) {
            int x = r.nextInt(g.nodeCount), y = r.nextInt(g.nodeCount);
            float dx = g.nx[x] - g.nx[y], dy = g.ny[x] - g.ny[y];
            if (dx * dx + dy * dy < g.shortTrip * g.shortTrip) continue;
            long q0 = g.searched, a0 = System.nanoTime();
            int[] p1 = g.astar(x, y);
            long q1 = g.searched, a1 = System.nanoTime();
            int[] p2 = g.route2(x, y);
            long a2 = System.nanoTime();
            t1 += a1 - a0;
            t2 += a2 - a1;
            s1 += q1 - q0;
            s2 += g.searched - q1;
            double ratio = g.routeCost(p2) / g.routeCost(p1);
            sum += ratio;
            worst = Math.max(worst, ratio);
            k++;
        }
        System.out.printf("    routes over 3 km: two-level cost %.4f of plain A* on average, %.4f at worst; plain %.1f ms (%d junctions), two-level %.1f ms (%d)%n",
                sum / k, worst, t1 / 1e6 / k, s1 / k, t2 / 1e6 / k, s2 / k);
    }

    /** An outbreak (2,000 dead painted round the origin) 40 s in, an air strike on it, 20 s more: the save's size before and after compression. */
    static void save(Content c, int size, boolean sparse) throws Exception {
        boolean was = ActiveSet.sparseDefault;
        ActiveSet.sparseDefault = sparse;
        try {
            World w = Setup.newGame(c, size, RegionGen.COAST, 7, c.scenario("lab_leak"), 2);
            w.camX0 = Setup.originX - 600;
            w.camX1 = Setup.originX + 600;
            w.camY0 = Setup.originY - 900;
            w.camY1 = Setup.originY + 900;
            w.camDetail = true;
            Tools.paintZombies(w, Setup.originX, Setup.originY, 2000, 600, -1);
            for (int k = 0; k < 40 * 20; k++) w.step();
            Actions.strike(w, Setup.originX + 300, Setup.originY + 200);
            for (int k = 0; k < 20 * 20; k++) w.step();
            long t0 = System.nanoTime();
            SaveIO.Snapshot raw = SaveIO.snapshot(w);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            SaveIO.compress(raw, bo);
            long ms = (System.nanoTime() - t0) / 1000000;
            System.out.printf("save %d %s: %d KB raw, %d KB compressed (%d ms); %d dead, %d buildings lost%n", size, sparse ? "sparse" : "dense ",
                    raw.length() >> 10, bo.size() >> 10, ms, w.totalZombies(), w.stats.buildingsLost);
            w.shutdown();
        } finally {
            ActiveSet.sparseDefault = was;
        }
    }
}
