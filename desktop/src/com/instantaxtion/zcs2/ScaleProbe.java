package com.instantaxtion.zcs2;

import java.io.FileInputStream;

/**
 * Baselines for the move to real-scale maps (docs/SCALE_PLAN.md):
 *   world:  today's maps: memory by component, build time, the once-a-second update and fast-forward speed;
 *   nation: the national coarse layer at each national size: build time, memory, people, road graph, routing.
 *   java -Xmx2g ... ScaleProbe [world|nation|all] [sizes...]
 */
public final class ScaleProbe {
    public static void main(String[] a) throws Exception {
        String what = a.length > 0 ? a[0] : "all";
        if (what.equals("world") || what.equals("all")) {
            Content c = Content.load(new FileInputStream("assets/content.ini"));
            int[] sizes = a.length > 1 && what.equals("world") ? parse(a) : new int[]{1536, 2304, 3072};
            for (int size : sizes) world(c, size);
        }
        if (what.equals("nation") || what.equals("all")) {
            int[] sizes = a.length > 1 && what.equals("nation") ? parse(a) : Nation.SIZES;
            for (int size : sizes) nation(size);
        }
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
            int[] p1 = g.route(x, y);
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
}
