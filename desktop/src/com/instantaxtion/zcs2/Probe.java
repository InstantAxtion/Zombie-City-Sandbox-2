package com.instantaxtion.zcs2;

import java.io.FileInputStream;

/**
 * A headless game for measuring: paints the dead into a region and prints what happens.
 *   java ... Probe [zombies] [seconds] [seed] [size] [scenario] [threads]
 */
public final class Probe {
    public static void main(String[] a) throws Exception {
        int zombies = a.length > 0 ? Integer.parseInt(a[0]) : 400;
        int secs = a.length > 1 ? Integer.parseInt(a[1]) : 180;
        long seed = a.length > 2 ? Long.parseLong(a[2]) : 7;
        int size = a.length > 3 ? Integer.parseInt(a[3]) : 1024;
        String scen = a.length > 4 ? a[4] : "sandbox";
        int threads = a.length > 5 ? Integer.parseInt(a[5]) : 4;
        Content c = Content.load(new FileInputStream("assets/content.ini"));
        long t0 = System.nanoTime();
        World w = Setup.newGame(c, size, RegionGen.COAST, seed, c.scenario(scen), threads);
        System.out.printf("built in %d ms: %s, %d people, %d police, %d soldiers, %d vehicles%n",
                (System.nanoTime() - t0) / 1000000, w.r.name, w.sec.totalPop(), w.stats.startPolice, w.stats.startSoldiers, w.veh.top);
        if (zombies > 0) Tools.paintZombies(w, Setup.originX, Setup.originY, zombies, 600, -1);
        // a camera over the start, as a player would have
        w.camX0 = Setup.originX - 700; w.camX1 = Setup.originX + 700; w.camY0 = Setup.originY - 1200; w.camY1 = Setup.originY + 1200;
        w.camDetail = true;
        long stepNs = 0, worst = 0;
        // steps that ran the once-a-second update, and those that didn't (after a warm-up)
        long secNs = 0, plainNs = 0;
        int secN = 0, plainN = 0;
        int steps = secs * 20;
        for (int k = 1; k <= steps; k++) {
            boolean secondly = (w.tick + 1) % 20 == 0;
            long s0 = System.nanoTime();
            w.step();
            long d = System.nanoTime() - s0;
            stepNs += d;
            if (k > 40) {
                worst = Math.max(worst, d);
                if (secondly) {
                    secNs += d;
                    secN++;
                } else {
                    plainNs += d;
                    plainN++;
                }
            }
            if (k % 600 == 0) {
                String audit = Audit.check(w);
                System.out.printf("%s t=%4ds zombies %6d (ind %5d) civ %7d out %4d police %4d army %4d kills P%5d A%5d deaths civ %5d cop %3d sol %3d calls %3d gov %d ent %6d  %.2f ms/step%s%n",
                        w.clock(), (int) w.time, w.totalZombies(), w.sp.zCount, w.sec.totalPop(), w.sp.civCount, w.sq.count(Ents.COP), w.sq.count(Ents.SOLDIER),
                        w.stats.killsBySide[1], w.stats.killsBySide[2], w.stats.civDeaths, w.stats.armedDeaths[1], w.stats.armedDeaths[2],
                        w.disp.callsTotal, w.gov.level, w.e.live, stepNs / 600 / 1e6, audit.isEmpty() ? "" : "\nAUDIT: " + audit);
                stepNs = 0;
            }
        }
        System.out.printf("worst step %.1f ms%n", worst / 1e6);
        if (secN > 0 && plainN > 0)
            System.out.printf("once a second: %.2f ms more (steps with the update %.2f ms, without %.2f ms)%n",
                    (secNs / (double) secN - plainNs / (double) plainN) / 1e6, secNs / 1e6 / secN, plainNs / 1e6 / plainN);
        System.out.println(fastForward(w, 72));
        String strat = System.getenv("STRAT");
        if (strat != null) {
            // strategic fast-forward for that many game days, printing twice a day
            w.camDetail = false;
            w.setStrategic(true);
            int days = Integer.parseInt(strat);
            long t1 = System.nanoTime();
            for (int k = 1; k <= days * 1440 / 10; k++) {
                w.step();
                if (k % 72 == 0) {
                    int alive = 0;
                    System.out.printf("%s %s  dead %7d  people %8d  police %5d  army %5d  formations %d  kills P%7d A%7d  lost civ %7d cop %5d sol %5d  hordes %d  evacuated %d  gov %d trust %d%s%n",
                            w.clock(), w.weather.status(), w.totalZombies(), w.livingCivilians(), w.forces.total(Ents.COP), w.forces.total(Ents.SOLDIER), w.forces.formations.size(),
                            w.stats.killsBySide[1], w.stats.killsBySide[2], w.stats.civDeaths, w.stats.armedDeaths[1], w.stats.armedDeaths[2], w.strat.hordes.size(),
                            w.strat.evacuatedOut + w.strat.evacuatedArrived, w.gov.level, (int) w.gov.trust, Audit.check(w).isEmpty() ? "" : " AUDIT " + Audit.check(w));
                }
            }
            System.out.printf("%d strategic days in %.1f s%n", days, (System.nanoTime() - t1) / 1e9);
        }
        for (int i = Math.max(0, w.news.log.size() - 25); i < w.news.log.size(); i++) System.out.println("  " + w.news.log.get(i).text);
        w.shutdown();
    }

    /**
     * Strategic fast-forward for 'steps' ten-second steps, which is the once-a-second update alone (with dt = 10):
     * its cost per step and how many steps a second that makes. Leaves the world in fast-forward.
     */
    static String fastForward(World w, int steps) {
        w.camDetail = false;
        w.setStrategic(true);
        for (int k = 0; k < 10; k++) w.step();
        long t0 = System.nanoTime();
        for (int k = 0; k < steps; k++) w.step();
        double ms = (System.nanoTime() - t0) / 1e6 / steps;
        return String.format("fast-forward: %.2f ms per update (%d sectors), %.0f steps a second, a game day in %.2f s", ms, w.sec.n, 1000 / ms, ms * 144 / 1000);
    }
}
