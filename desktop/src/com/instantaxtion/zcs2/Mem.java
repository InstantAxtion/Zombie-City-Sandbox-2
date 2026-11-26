package com.instantaxtion.zcs2;

import java.io.FileInputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Memory of a new World at each map size (heap in use after a full GC), how long it took to build, and where the
 * memory goes: the big arrays of Region one by one, then every other part of the World (each counted once, by the
 * first part that holds it).
 *   java -Xmx2g ... Mem [size]
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
            for (String line : components(w)) System.out.println("    " + line);
            w.shutdown();
        }
    }

    /** "name  MB" lines, largest first: Region's fields one by one, then the World's other fields. */
    static List<String> components(World w) throws Exception {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
        seen.put(w, true);
        seen.put(w.c, true); // the rules are shared, not the world's
        final List<Object[]> rows = new ArrayList<Object[]>();
        seen.put(w.r, true);
        for (Field f : fields(w.r.getClass())) {
            Object v = f.get(w.r);
            if (v == null || seen.containsKey(v)) continue;
            rows.add(new Object[]{"r." + f.getName(), deep(v, seen, 0)});
        }
        for (Field f : fields(w.getClass())) {
            Object v = f.get(w);
            if (v == null || seen.containsKey(v)) continue;
            rows.add(new Object[]{f.getName(), deep(v, seen, 0)});
        }
        Collections.sort(rows, new Comparator<Object[]>() {
            public int compare(Object[] x, Object[] y) {
                return Long.compare((Long) y[1], (Long) x[1]);
            }
        });
        List<String> out = new ArrayList<String>();
        long total = 0;
        for (Object[] r : rows) total += (Long) r[1];
        for (Object[] r : rows) if ((Long) r[1] >= 256 * 1024) out.add(String.format("%-22s %7.1f MB", r[0], (Long) r[1] / 1048576.0));
        out.add(String.format("%-22s %7.1f MB", "(all counted)", total / 1048576.0));
        return out;
    }

    private static List<Field> fields(Class<?> c) {
        List<Field> out = new ArrayList<Field>();
        for (; c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    out.add(f);
                } catch (RuntimeException ignored) {
                    // a JDK class that won't open: skipped
                }
            }
        return out;
    }

    /** Bytes reachable from o not counted before (roughly: 16 B per object header, 8 per reference). */
    static long deep(Object o, IdentityHashMap<Object, Boolean> seen, int depth) throws Exception {
        if (o == null || seen.containsKey(o) || depth > 40) return 0;
        seen.put(o, true);
        Class<?> c = o.getClass();
        if (c.isArray()) {
            int n = Array.getLength(o);
            Class<?> e = c.getComponentType();
            if (e.isPrimitive()) {
                int sz = e == long.class || e == double.class ? 8 : e == int.class || e == float.class ? 4 : e == short.class || e == char.class ? 2 : 1;
                return 16 + (long) n * sz;
            }
            long b = 16 + 4L * n;
            for (int i = 0; i < n; i++) b += deep(Array.get(o, i), seen, depth + 1);
            return b;
        }
        if (c.getName().startsWith("java.lang.Thread") || o instanceof Class) return 0;
        long b = 16;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().isPrimitive()) {
                    b += f.getType() == long.class || f.getType() == double.class ? 8 : 4;
                    continue;
                }
                b += 4;
                try {
                    f.setAccessible(true);
                } catch (RuntimeException ex) {
                    continue;
                }
                b += deep(f.get(o), seen, depth + 1);
            }
        return b;
    }
}
