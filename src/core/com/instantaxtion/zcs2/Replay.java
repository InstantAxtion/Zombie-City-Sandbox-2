package com.instantaxtion.zcs2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * A replay: the start of a game (map, seed, scenario, role) and everything the player did, tick by tick. Played
 * back, it rebuilds the outbreak from nothing and comes out exactly the same (the simulation is deterministic and
 * every input is in the record). Small: a whole campaign is a few kilobytes.
 *
 * A replay can be seeked: while it plays it keeps snapshots (a save's bytes, compressed) every few game minutes, up
 * to a share of the memory, and going to any moment loads the nearest snapshot at or before it and plays on from
 * there. A save plays on exactly as the game did, so a seek comes out exactly as playing straight through. A
 * replay may also carry a camera path (keyframes the view glides along), saved with it as a "director's cut".
 */
public final class Replay {
    private static final int MAGIC = 0x5A435250; // "ZCRP"
    /** 1: the start and the inputs; 2: and a camera path. */
    private static final int VERSION = 2;
    public World w;
    public final List<Director.Event> events = new ArrayList<Director.Event>();
    public int next;
    public long endTick;
    public String title = "";
    /** How the game began (for a director's cut, saved again from the replay). */
    public Director.Header header;
    public final CamPath path = new CamPath();
    private Content content;
    private int threads;

    // ------------------------------------------------------------------ snapshots

    /** A moment of the replay kept to seek from: the tick, the next input then, and the world's save (compressed). */
    public static final class Snap {
        public final long tick;
        public final int next;
        public final byte[] data;

        Snap(long tick, int next, byte[] data) {
            this.tick = tick;
            this.next = next;
            this.data = data;
        }
    }

    /** The snapshots, in tick order. */
    public final List<Snap> snaps = new ArrayList<Snap>();
    /** Game minutes between snapshots (doubles when the memory cap is reached and every other one is dropped). */
    public float snapEvery = 30;
    /** The most the snapshots may hold, in bytes. */
    public long snapCap = 48L << 20;
    private double nextSnap;
    private long snapBytes;

    /** A fresh world at the start of the game the director recorded, ready to play the events back. */
    public static Replay start(Content c, Director.Header d, List<Director.Event> events, long endTick, int threads) throws IOException {
        Replay r = new Replay();
        r.content = c;
        r.threads = threads;
        r.header = d;
        Content.Scenario sc = null;
        if (d.scenarioIni.length() > 0) {
            List<Content.Scenario> l = c.addScenarios(new ByteArrayInputStream(d.scenarioIni.getBytes("UTF-8")), false);
            if (!l.isEmpty()) sc = l.get(0);
        }
        r.w = Setup.newGame(c, d.size, d.preset, d.seed, sc, threads, d.role, d.maxInd);
        r.w.director.replaying = true;
        r.events.addAll(events);
        Actions.sort(r.events);
        r.endTick = endTick;
        r.title = (sc != null ? sc.name : "Sandbox") + ", " + r.w.r.name;
        // the start is a snapshot too: seeking back to it needs no new game built
        r.snapshot();
        return r;
    }

    /** A replay of the game so far. */
    public static Replay of(Content c, World source, int threads) throws IOException {
        return start(c, source.director.header, source.director.events, source.tick, threads);
    }

    /** Feeds in the events due before the next step. */
    public void beforeStep() {
        while (next < events.size() && events.get(next).tick <= w.tick) Actions.apply(w, events.get(next++));
    }

    /** After a step: a snapshot when one is due. */
    public void afterStep() {
        if (w.time >= nextSnap) snapshot();
    }

    /** One step of the replay (its inputs, the step, and a snapshot if one is due). */
    public void step() {
        beforeStep();
        w.step();
        afterStep();
    }

    /** Plays up to a tick (for tests and for skipping ahead). */
    public void runTo(long tick) {
        while (w.tick < tick) step();
    }

    public boolean done() {
        return w.tick >= endTick;
    }

    /** Keeps the world as it is now (between steps), compressed; thins the old ones out when there are too many. */
    public void snapshot() {
        if (!snaps.isEmpty() && snaps.get(snaps.size() - 1).tick >= w.tick) return;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 16);
            SaveIO.compress(SaveIO.snapshot(w), bo);
            byte[] data = bo.toByteArray();
            snaps.add(new Snap(w.tick, next, data));
            snapBytes += data.length;
        } catch (IOException e) {
            // (only memory can fail here: seeking then plays from the snapshot before)
        }
        nextSnap = Math.floor(w.time / snapEvery) * snapEvery + snapEvery;
        while (snapBytes > snapCap && snaps.size() > 2) thin();
    }

    /** Drops every other snapshot after the first (the start), and doubles the time between them. */
    private void thin() {
        List<Snap> keep = new ArrayList<Snap>();
        snapBytes = 0;
        for (int k = 0; k < snaps.size(); k++)
            if (k == 0 || k % 2 == 0 || k == snaps.size() - 1) {
                keep.add(snaps.get(k));
                snapBytes += snaps.get(k).data.length;
            }
        snaps.clear();
        snaps.addAll(keep);
        snapEvery *= 2;
    }

    public long snapBytes() {
        return snapBytes;
    }

    /** The latest snapshot at or before tick (the start at least). */
    public Snap nearest(long tick) {
        Snap best = snaps.get(0);
        for (Snap s : snaps) if (s.tick <= tick && s.tick > best.tick) best = s;
        return best;
    }

    /**
     * True if going to tick means loading a snapshot (it's behind the world, or a snapshot lies between the world and
     * it and is more than a minute ahead), false if playing on gets there.
     */
    public boolean needsLoad(long tick) {
        if (tick < w.tick) return true;
        Snap s = nearest(tick);
        return s.tick > w.tick + 1200; // (a game hour)
    }

    /** The world as it was at a snapshot (a fresh world: blocking, may run on another thread). */
    public World load(Snap s) throws IOException {
        World nw = SaveIO.load(content, new ByteArrayInputStream(s.data), threads);
        nw.director.replaying = true;
        return nw;
    }

    /** Puts a world loaded from snapshot s in place (the old one is the caller's to shut down). */
    public void adopt(World nw, Snap s) {
        w = nw;
        next = s.next;
        nextSnap = Math.floor(w.time / snapEvery) * snapEvery + snapEvery;
    }

    /** Goes to tick, loading a snapshot if it has to (blocking: for tests and tools). Returns the old world if replaced. */
    public World seek(long tick) throws IOException {
        tick = Math.min(tick, endTick);
        World old = null;
        if (needsLoad(tick)) {
            Snap s = nearest(tick);
            old = w;
            adopt(load(s), s);
        }
        runTo(tick);
        return old;
    }

    // ------------------------------------------------------------------ camera paths

    /** A path for the camera: keyframes (tick, centre, zoom) it glides through, smoothly, as the replay plays. */
    public static final class CamPath {
        public final List<float[]> keys = new ArrayList<float[]>();
        public final List<Long> ticks = new ArrayList<Long>();

        /** Adds (or replaces, within a game minute) a keyframe; keeps them in tick order. */
        public void add(long tick, float cx, float cy, float zoom) {
            for (int k = 0; k < ticks.size(); k++)
                if (Math.abs(ticks.get(k) - tick) < 20) {
                    ticks.remove(k);
                    keys.remove(k);
                    break;
                }
            int at = 0;
            while (at < ticks.size() && ticks.get(at) < tick) at++;
            ticks.add(at, tick);
            keys.add(at, new float[]{cx, cy, zoom});
        }

        public void remove(int k) {
            ticks.remove(k);
            keys.remove(k);
        }

        public int size() {
            return ticks.size();
        }

        public void clear() {
            ticks.clear();
            keys.clear();
        }

        /**
         * The camera at a tick: {cx, cy, zoom}, a Catmull-Rom curve through the keyframes (zoom on a log scale,
         * eased in and out at each key so a hold looks like a hold), or null with no keyframes. Before the first
         * key and after the last it holds there.
         */
        public float[] at(double tick) {
            int n = ticks.size();
            if (n == 0) return null;
            if (n == 1 || tick <= ticks.get(0)) return keys.get(0).clone();
            if (tick >= ticks.get(n - 1)) return keys.get(n - 1).clone();
            int k = 0;
            while (k < n - 2 && tick >= ticks.get(k + 1)) k++;
            double t0 = ticks.get(k), t1 = ticks.get(k + 1);
            float u = (float) ((tick - t0) / Math.max(1, t1 - t0));
            u = u * u * (3 - 2 * u);
            float[] p0 = keys.get(Math.max(0, k - 1)), p1 = keys.get(k), p2 = keys.get(k + 1), p3 = keys.get(Math.min(n - 1, k + 2));
            float[] out = new float[3];
            for (int c = 0; c < 2; c++) out[c] = catmull(p0[c], p1[c], p2[c], p3[c], u);
            float z = catmull(log(p0[2]), log(p1[2]), log(p2[2]), log(p3[2]), u);
            out[2] = (float) Math.exp(z);
            return out;
        }

        private static float log(float z) {
            return (float) Math.log(Math.max(0.0001f, z));
        }

        private static float catmull(float a, float b, float c, float d, float u) {
            float u2 = u * u, u3 = u2 * u;
            return 0.5f * (2 * b + (-a + c) * u + (2 * a - 5 * b + 4 * c - d) * u2 + (-a + 3 * b - 3 * c + d) * u3);
        }

        void write(DataOutputStream o) throws IOException {
            o.writeInt(ticks.size());
            for (int k = 0; k < ticks.size(); k++) {
                o.writeLong(ticks.get(k));
                for (float v : keys.get(k)) o.writeFloat(v);
            }
        }

        void read(DataInputStream in) throws IOException {
            clear();
            int n = in.readInt();
            for (int k = 0; k < n; k++) {
                long t = in.readLong();
                float x = in.readFloat(), y = in.readFloat(), z = in.readFloat();
                ticks.add(t);
                keys.add(new float[]{x, y, z});
            }
        }
    }

    // ------------------------------------------------------------------ files

    public static void save(World source, OutputStream os) throws IOException {
        write(os, source.director.header, source.director.events, source.tick, null);
    }

    /** The replay again with its camera path: a director's cut. */
    public void saveCut(OutputStream os) throws IOException {
        write(os, header, events, endTick, path);
    }

    private static void write(OutputStream os, Director.Header h, List<Director.Event> events, long end, CamPath path) throws IOException {
        DeflaterOutputStream z = new DeflaterOutputStream(os);
        DataOutputStream o = new DataOutputStream(z);
        o.writeInt(MAGIC);
        o.writeInt(VERSION);
        h.write(o);
        o.writeLong(end);
        o.writeInt(events.size());
        for (Director.Event e : events) Director.writeEvent(o, e);
        if (path == null) o.writeInt(0);
        else path.write(o);
        o.flush();
        z.finish();
    }

    public static Replay load(Content c, InputStream is, int threads) throws IOException {
        DataInputStream in = new DataInputStream(new InflaterInputStream(is));
        if (in.readInt() != MAGIC) throw new IOException("not a replay");
        int ver = in.readInt();
        if (ver > VERSION) throw new IOException("this replay is from a newer version of the game");
        Director.Header d = new Director.Header();
        d.read(in);
        long end = in.readLong();
        int n = in.readInt();
        List<Director.Event> ev = new ArrayList<Director.Event>();
        for (int k = 0; k < n; k++) ev.add(Director.readEvent(in));
        CamPath p = new CamPath();
        if (ver >= 2) p.read(in);
        Replay r = start(c, d, ev, end, threads);
        r.path.keys.addAll(p.keys);
        r.path.ticks.addAll(p.ticks);
        return r;
    }
}
