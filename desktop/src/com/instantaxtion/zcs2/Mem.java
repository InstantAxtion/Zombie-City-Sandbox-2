package com.instantaxtion.zcs2;

import java.io.FileInputStream;

/**
 * Memory of a new World at each map size (heap in use after a full GC), and how long it took to build.
 *   java -Xmx3g ... Mem [size]
 */
public final class Mem {
    static long used() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(80);
            } catch (InterruptedException ignored) {
            }
        }
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    public static void main(String[] a) throws Exception {
        Content c = Content.load(new FileInputStream("assets/content.ini"));
        int[] sizes = a.length > 0 ? new int[]{Integer.parseInt(a[0])} : new int[]{1536, 2304, 3072};
        for (int size : sizes) {
            long base = used();
            long t0 = System.nanoTime();
            World w = Setup.newGame(c, size, RegionGen.COAST, 7, c.scenario("sandbox"), 1);
            long ms = (System.nanoTime() - t0) / 1000000;
            for (int k = 0; k < 40; k++) w.step();
            long u = used() - base;
            System.out.printf("%d tiles: %d MB world, built in %d ms, %d buildings, %d people%n", size, u >> 20, ms, w.r.bCount, w.sec.totalPop());
            w.shutdown();
        }
    }
}
