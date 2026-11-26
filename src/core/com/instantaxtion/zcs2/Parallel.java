package com.instantaxtion.zcs2;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small fixed pool of worker threads for the per-entity phases. Work is handed out in chunks; each entity's
 * result depends only on what was true before the phase started, so the outcome is the same however the
 * chunks land on threads. With one thread it runs inline (tests compare both).
 */
public final class Parallel {
    public interface Job {
        /** Processes entities [from, to) using the thread's own scratch space. */
        void run(int from, int to, Scratch s);
    }

    /** Per-thread scratch, so the phases never share temporary arrays. */
    public static final class Scratch {
        public final float[] f = new float[16];
        public final int[] n = new int[16];
    }

    private static final class Batch {
        final Job job;
        final int total, chunk;
        final AtomicInteger next = new AtomicInteger(), done = new AtomicInteger();
        volatile Throwable error;

        Batch(Job job, int total, int chunk) {
            this.job = job;
            this.total = total;
            this.chunk = chunk;
        }
    }

    private final int threads;
    private final Thread[] workers;
    private final Scratch[] scratch;
    private final Object lock = new Object();
    private volatile Batch batch;
    private int generation;
    private volatile boolean stopped;

    public Parallel(int threads) {
        this.threads = Math.max(1, threads);
        scratch = new Scratch[this.threads];
        for (int i = 0; i < this.threads; i++) scratch[i] = new Scratch();
        workers = new Thread[this.threads - 1];
        for (int i = 0; i < workers.length; i++) {
            final int id = i + 1;
            workers[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    workerLoop(id);
                }
            }, "sim-" + id);
            workers[i].setDaemon(true);
            workers[i].start();
        }
    }

    public int threads() {
        return threads;
    }

    private void workerLoop(int id) {
        int seen = 0;
        while (!stopped) {
            Batch b;
            synchronized (lock) {
                while (generation == seen && !stopped) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                seen = generation;
                b = batch;
            }
            if (stopped) return;
            if (b != null) work(b, id);
        }
    }

    private void work(Batch b, int id) {
        while (true) {
            int k = b.next.getAndIncrement();
            int from = k * b.chunk;
            if (from >= b.total) break;
            int to = Math.min(b.total, from + b.chunk);
            try {
                b.job.run(from, to, scratch[id]);
            } catch (Throwable t) {
                b.error = t;
            } finally {
                b.done.addAndGet(to - from);
            }
        }
    }

    /** Runs job over [0, n) and returns when every entity is done. */
    public void run(Job j, int n) {
        if (n <= 0) return;
        if (threads == 1 || n < 512) {
            j.run(0, n, scratch[0]);
            return;
        }
        Batch b = new Batch(j, n, Math.max(256, n / (threads * 6)));
        synchronized (lock) {
            batch = b;
            generation++;
            lock.notifyAll();
        }
        work(b, 0);
        while (b.done.get() < n) Thread.yield();
        if (b.error != null) throw new RuntimeException("simulation worker failed", b.error);
    }

    public void shutdown() {
        stopped = true;
        synchronized (lock) {
            lock.notifyAll();
        }
    }
}
