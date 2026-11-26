package com.instantaxtion.zcs2;

import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Balance over many runs. A chaotic simulation lies in single runs, so this plays the same outbreak on many seeds
 * (headless, in strategic fast-forward) and reports the spread of what happens: how long until it's contained or
 * the country falls, how many die, the police and army lost, the most dead at once.
 *
 *   tools/balance.sh [sizes] [seeds] [map] [days] [threads]
 *   e.g. tools/balance.sh 2000,20000,250000 8 2304 30 2
 * Writes a table to the console and every run to build/balance.csv.
 */
public final class Balance {
    public static final int CONTAINED = 0, FALLEN = 1, ONGOING = 2;
    public static final String[] RESULTS = {"contained", "fallen", "ongoing"};

    public static final class Run {
        public int zombies, map, result;
        public long seed;
        public float days, lostShare;
        public int deaths, police, army, peak;
        public long people0;
    }

    /** One outbreak of n of the dead in the capital, played until it's over or the days run out. */
    public static Run play(Content c, int map, long seed, int n, int maxDays) {
        World w = Setup.newGame(c, map, RegionGen.COAST, seed, c.scenario("sandbox"), 1);
        Run r = new Run();
        r.zombies = n;
        r.map = map;
        r.seed = seed;
        r.people0 = Audit.people(w);
        Region.Settlement cap = w.r.city();
        float rad = Math.min(cap.radius * 16f * 0.8f, 200 + (float) Math.sqrt(n) * 14);
        w.maxIndividuals = 0;
        // spread the outbreak over the area in pieces (one huge pile in a single sector isn't an outbreak)
        int pieces = Math.max(1, n / 2000);
        for (int k = 0; k < pieces; k++) {
            float ang = Rng.unit(seed, k, 1) * 6.283f, d = (float) Math.sqrt(Rng.unit(seed, k, 2)) * rad;
            float x = cap.cx * 16f + (float) Math.cos(ang) * d, y = cap.cy * 16f + (float) Math.sin(ang) * d;
            int part = k == pieces - 1 ? n - (pieces - 1) * (n / pieces) : n / pieces;
            Tools.paintZombies(w, x, y, part, Math.max(150, rad / (float) Math.sqrt(pieces)), -1);
        }
        w.setStrategic(true);
        r.result = ONGOING;
        int steps = (int) (maxDays * 1440 / World.STRATEGIC_DT);
        for (int k = 1; k <= steps; k++) {
            w.step();
            if (k % 18 != 0) continue; // every three game hours
            long people = Audit.people(w);
            if (w.totalZombies() == 0 && w.strat.hordes.isEmpty()) {
                r.result = CONTAINED;
                break;
            }
            if (people < r.people0 * 0.05f) {
                r.result = FALLEN;
                break;
            }
        }
        r.days = (float) (w.time / 1440.0);
        // people still alive anywhere (at home, on the roads, or got out) against those at the start
        r.lostShare = 1 - Math.min(1f, Audit.people(w) / (float) r.people0);
        r.deaths = w.stats.civDeaths;
        r.police = w.stats.armedDeaths[Ents.COP];
        r.army = w.stats.armedDeaths[Ents.SOLDIER];
        r.peak = w.stats.peakZombies;
        w.shutdown();
        return r;
    }

    public static List<Run> batch(final Content c, int[] sizes, int seeds, final int map, final int days, int threads) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(Math.max(1, threads));
        List<Future<Run>> fs = new ArrayList<Future<Run>>();
        for (final int n : sizes)
            for (int s = 0; s < seeds; s++) {
                final long seed = 1000 + s * 7919L;
                fs.add(ex.submit(new java.util.concurrent.Callable<Run>() {
                    public Run call() {
                        return play(c, map, seed, n, days);
                    }
                }));
            }
        List<Run> out = new ArrayList<Run>();
        for (Future<Run> f : fs) out.add(f.get());
        ex.shutdown();
        return out;
    }

    static float pct(float[] v, float p) {
        float[] s = v.clone();
        Arrays.sort(s);
        if (s.length == 0) return 0;
        int i = Math.min(s.length - 1, Math.max(0, Math.round(p * (s.length - 1))));
        return s[i];
    }

    /** The table: one line per outbreak size. */
    public static String table(List<Run> runs, int[] sizes) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%9s %4s %9s %7s %7s  %-17s %-17s %-17s %-17s %-17s %-17s%n", "dead", "runs", "contained", "fallen", "ongoing",
                "days p10/50/90", "lost% p10/50/90", "deaths (k) p50", "police lost p50", "army lost p50", "peak dead p50"));
        for (int n : sizes) {
            List<Run> rs = new ArrayList<Run>();
            for (Run r : runs) if (r.zombies == n) rs.add(r);
            int[] res = new int[3];
            float[] days = new float[rs.size()], lost = new float[rs.size()], deaths = new float[rs.size()], pol = new float[rs.size()],
                    army = new float[rs.size()], peak = new float[rs.size()];
            for (int i = 0; i < rs.size(); i++) {
                Run r = rs.get(i);
                res[r.result]++;
                days[i] = r.days;
                lost[i] = r.lostShare * 100;
                deaths[i] = r.deaths / 1000f;
                pol[i] = r.police;
                army[i] = r.army;
                peak[i] = r.peak;
            }
            sb.append(String.format("%9d %4d %9d %7d %7d  %4.1f/%4.1f/%5.1f    %4.0f/%4.0f/%4.0f    %10.1f      %10.0f        %10.0f        %10.0f%n",
                    n, rs.size(), res[0], res[1], res[2], pct(days, 0.1f), pct(days, 0.5f), pct(days, 0.9f),
                    pct(lost, 0.1f), pct(lost, 0.5f), pct(lost, 0.9f), pct(deaths, 0.5f), pct(pol, 0.5f), pct(army, 0.5f), pct(peak, 0.5f)));
        }
        return sb.toString();
    }

    public static void main(String[] a) throws Exception {
        String[] sz = (a.length > 0 ? a[0] : "2000,5000,20000,50000,250000").split(",");
        int[] sizes = new int[sz.length];
        for (int i = 0; i < sz.length; i++) sizes[i] = Integer.parseInt(sz[i].trim());
        int seeds = a.length > 1 ? Integer.parseInt(a[1]) : 6;
        int map = a.length > 2 ? Integer.parseInt(a[2]) : 2304;
        int days = a.length > 3 ? Integer.parseInt(a[3]) : 30;
        int threads = a.length > 4 ? Integer.parseInt(a[4]) : 2;
        Content c = Content.load(new FileInputStream("assets/content.ini"));
        // rule=value arguments after the first five override content.ini (for trying numbers)
        for (int i = 5; i < a.length; i++) {
            String[] kv = a[i].split("=");
            c.setRule(kv[0], Float.parseFloat(kv[1]));
            System.out.println("rule " + kv[0] + " = " + kv[1]);
        }
        long t0 = System.nanoTime();
        List<Run> runs = batch(c, sizes, seeds, map, days, threads);
        System.out.print(table(runs, sizes));
        System.out.printf("%d runs in %.0f s%n", runs.size(), (System.nanoTime() - t0) / 1e9);
        new java.io.File("build").mkdirs();
        PrintWriter pw = new PrintWriter("build/balance.csv", "UTF-8");
        pw.println("zombies,map,seed,result,days,lost_share,civilian_deaths,police_lost,army_lost,peak_dead,people_at_start");
        for (Run r : runs)
            pw.printf("%d,%d,%d,%s,%.2f,%.4f,%d,%d,%d,%d,%d%n", r.zombies, r.map, r.seed, RESULTS[r.result], r.days, r.lostShare, r.deaths, r.police, r.army, r.peak, r.people0);
        pw.close();
    }
}
