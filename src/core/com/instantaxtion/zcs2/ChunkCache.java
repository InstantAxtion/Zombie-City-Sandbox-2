package com.instantaxtion.zcs2;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Generated chunks kept in memory: least recently used ones are dropped past the capacity, pinned ones never.
 * <ul>
 * <li>get() returns a chunk, generating it if needed (synchronously). If another thread is already generating that
 * chunk, it waits for that result instead of making a second copy (both would be identical: generation is pure).</li>
 * <li>A background thread prefetches chunks asked for with prefetch(). It only fills the cache: it never applies
 * overlays and never touches anything the simulation reads, so it can't change a result, only save time.</li>
 * <li>The overlay hook (P4): get() applies the overlay when a chunk is first used, or again when the overlay's version
 * for that chunk has changed (starting from a freshly generated chunk, so edits can also be taken back). peek() and
 * the prefetcher never apply it.</li>
 * </ul>
 * Thread-safe. Iteration order of the internal maps never reaches the simulation.
 */
public final class ChunkCache {
    /** Edits laid over generated chunks (barricades, craters, destroyed buildings). */
    public interface Overlay {
        /** The version of the edits for chunk index c (0 when it has none). */
        int version(int c);

        /** Applies every edit for the chunk to it (it is as generated when this is called). */
        void apply(Chunk chunk);
    }

    private final Nation n;
    private final int capacity;
    private final LinkedHashMap<Integer, Chunk> lru = new LinkedHashMap<Integer, Chunk>(256, 0.75f, true);
    private final Map<Integer, Integer> pins = new HashMap<Integer, Integer>();
    private final Set<Integer> inProgress = new HashSet<Integer>();
    private final ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
    private final Set<Integer> queued = new HashSet<Integer>();
    private Overlay overlay;
    private Thread prefetcher;
    private volatile boolean stopping;

    /** Counters for tests and probes. */
    public long generated, hits, waits, evicted, prefetched;

    public ChunkCache(Nation n, int capacity) {
        this.n = n;
        this.capacity = Math.max(16, capacity);
    }

    /** Three chunks per MB of heap (the plan's 768 for a 256 MB phone heap), at least 256 and at most 8192. */
    public static int capacityFor(long maxMemoryBytes) {
        long mb = maxMemoryBytes >> 20;
        return (int) Math.max(256, Math.min(8192, mb * 3));
    }

    public int capacity() {
        return capacity;
    }

    public synchronized void setOverlay(Overlay o) {
        overlay = o;
    }

    private int key(int cx, int cy) {
        return cy * n.sw + cx;
    }

    /** The chunk (generated now if it isn't cached), with the overlay applied. */
    public Chunk get(int cx, int cy) {
        Chunk ch = load(key(cx, cy), true);
        Overlay o;
        synchronized (this) {
            o = overlay;
        }
        if (o == null) return ch;
        int v = o.version(ch.index);
        if (ch.overlayVersion == v) return ch;
        synchronized (ch) {
            if (ch.overlayVersion == v) return ch;
            if (ch.overlayVersion != -1) {
                // edits changed since they were applied: start again from the chunk as generated
                Chunk fresh = ChunkGen.generate(n, cx, cy);
                o.apply(fresh);
                fresh.overlayVersion = v;
                synchronized (this) {
                    generated++;
                    if (lru.containsKey(ch.index)) lru.put(ch.index, fresh);
                }
                return fresh;
            }
            o.apply(ch);
            ch.overlayVersion = v;
            return ch;
        }
    }

    /** The cached chunk or null; never generates and never applies the overlay (for drawing). */
    public synchronized Chunk peek(int cx, int cy) {
        Chunk ch = lru.get(key(cx, cy));
        if (ch != null) hits++;
        return ch;
    }

    public synchronized boolean contains(int cx, int cy) {
        return lru.containsKey(key(cx, cy));
    }

    public synchronized int size() {
        return lru.size();
    }

    /** Keeps the chunk in memory until unpinned as often as pinned. Pinning doesn't generate it. */
    public synchronized void pin(int cx, int cy) {
        Integer k = key(cx, cy);
        Integer p = pins.get(k);
        pins.put(k, p == null ? 1 : p + 1);
    }

    public synchronized void unpin(int cx, int cy) {
        Integer k = key(cx, cy);
        Integer p = pins.get(k);
        if (p == null) return;
        if (p <= 1) pins.remove(k);
        else pins.put(k, p - 1);
        evict();
    }

    public synchronized boolean pinned(int cx, int cy) {
        return pins.containsKey(key(cx, cy));
    }

    /** Drops every unpinned chunk (tests; low memory). */
    public synchronized void clear() {
        Iterator<Integer> it = lru.keySet().iterator();
        while (it.hasNext())
            if (!pins.containsKey(it.next())) {
                it.remove();
                evicted++;
            }
    }

    /** Drops one chunk unless it's pinned; true if it was dropped. */
    public synchronized boolean evict(int cx, int cy) {
        Integer k = key(cx, cy);
        if (pins.containsKey(k) || lru.remove(k) == null) return false;
        evicted++;
        return true;
    }

    private void evict() {
        if (lru.size() <= capacity) return;
        Iterator<Map.Entry<Integer, Chunk>> it = lru.entrySet().iterator();
        while (lru.size() > capacity && it.hasNext()) {
            Map.Entry<Integer, Chunk> e = it.next();
            if (pins.containsKey(e.getKey())) continue;
            it.remove();
            evicted++;
        }
    }

    /** The cached chunk, or one generated now (waiting for another thread that is already making it). */
    private Chunk load(int k, boolean foreground) {
        synchronized (this) {
            while (true) {
                Chunk ch = lru.get(k);
                if (ch != null) {
                    if (foreground) hits++;
                    return ch;
                }
                if (!inProgress.contains(k)) break;
                waits++;
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            inProgress.add(k);
        }
        Chunk ch = null;
        try {
            ch = ChunkGen.generate(n, k % n.sw, k / n.sw);
        } finally {
            synchronized (this) {
                inProgress.remove(k);
                if (ch != null) {
                    lru.put(k, ch);
                    generated++;
                    if (!foreground) prefetched++;
                    evict();
                }
                notifyAll();
            }
        }
        return ch;
    }

    // ------------------------------------------------------------------ the prefetcher

    /** Asks the background thread to make this chunk if it isn't cached (the newest requests first). */
    public void prefetch(int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= n.sw || cy >= n.sw) return;
        Integer k = key(cx, cy);
        synchronized (this) {
            if (lru.containsKey(k) || inProgress.contains(k) || queued.contains(k)) return;
            queue.addFirst(k);
            queued.add(k);
            while (queue.size() > capacity / 2) queued.remove(queue.removeLast());
            if (prefetcher == null) startPrefetcher();
            notifyAll();
        }
    }

    /** Prefetches the chunks within 'ring' chunks of (cx, cy), nearest last so they are made first. */
    public void prefetchAround(int cx, int cy, int ring) {
        for (int r = ring; r >= 0; r--)
            for (int dy = -r; dy <= r; dy++)
                for (int dx = -r; dx <= r; dx++)
                    if (Math.max(Math.abs(dx), Math.abs(dy)) == r) prefetch(cx + dx, cy + dy);
    }

    public synchronized int queued() {
        return queue.size();
    }

    private void startPrefetcher() {
        prefetcher = new Thread(new Runnable() {
            public void run() {
                while (!stopping) {
                    Integer k;
                    synchronized (ChunkCache.this) {
                        while (queue.isEmpty() && !stopping) {
                            try {
                                ChunkCache.this.wait();
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                        if (stopping) return;
                        k = queue.removeFirst();
                        queued.remove(k);
                        if (lru.containsKey(k) || inProgress.contains(k)) continue;
                    }
                    load(k, false);
                }
            }
        }, "chunk-prefetch");
        prefetcher.setDaemon(true);
        prefetcher.setPriority(Thread.MIN_PRIORITY);
        prefetcher.start();
    }

    /** Waits until the prefetch queue is empty and nothing is being made (tests). */
    public void drain() {
        synchronized (this) {
            while (!queue.isEmpty() || !inProgress.isEmpty()) {
                try {
                    wait(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Stops the background thread (the cache stays usable from the calling threads). */
    public void shutdown() {
        Thread p;
        synchronized (this) {
            stopping = true;
            queue.clear();
            queued.clear();
            notifyAll();
            p = prefetcher;
        }
        if (p != null) {
            try {
                p.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Bytes held by the cached chunks. */
    public synchronized long memoryBytes() {
        long b = 0;
        for (Chunk ch : lru.values()) b += ch.memoryBytes();
        return b;
    }
}
