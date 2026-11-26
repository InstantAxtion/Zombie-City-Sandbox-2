package com.instantaxtion.zcs2;

import java.io.ByteArrayInputStream;
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
 */
public final class Replay {
    private static final int MAGIC = 0x5A435250; // "ZCRP"
    public World w;
    public final List<Director.Event> events = new ArrayList<Director.Event>();
    public int next;
    public long endTick;
    public String title = "";

    /** A fresh world at the start of the game the director recorded, ready to play the events back. */
    public static Replay start(Content c, Director.Header d, List<Director.Event> events, long endTick, int threads) throws IOException {
        Replay r = new Replay();
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

    /** Plays up to a tick (for tests and for skipping ahead). */
    public void runTo(long tick) {
        while (w.tick < tick) {
            beforeStep();
            w.step();
        }
    }

    public boolean done() {
        return w.tick >= endTick;
    }

    // ------------------------------------------------------------------ files

    public static void save(World source, OutputStream os) throws IOException {
        DeflaterOutputStream z = new DeflaterOutputStream(os);
        DataOutputStream o = new DataOutputStream(z);
        o.writeInt(MAGIC);
        o.writeInt(1);
        source.director.header.write(o);
        o.writeLong(source.tick);
        o.writeInt(source.director.events.size());
        for (Director.Event e : source.director.events) Director.writeEvent(o, e);
        o.flush();
        z.finish();
    }

    public static Replay load(Content c, InputStream is, int threads) throws IOException {
        DataInputStream in = new DataInputStream(new InflaterInputStream(is));
        if (in.readInt() != MAGIC) throw new IOException("not a replay");
        in.readInt();
        Director.Header d = new Director.Header();
        d.read(in);
        long end = in.readLong();
        int n = in.readInt();
        List<Director.Event> ev = new ArrayList<Director.Event>();
        for (int k = 0; k < n; k++) ev.add(Director.readEvent(in));
        return start(c, d, ev, end, threads);
    }
}
