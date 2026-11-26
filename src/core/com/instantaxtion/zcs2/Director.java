package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Director mode: a record of everything the player did (and where the camera looked, which decides what is
 * simulated person by person), so a whole outbreak replays exactly from its seed; a timelapse of the national map
 * sampled every game hour; and heatmaps of where people died and where the dead were thickest.
 */
public final class Director extends Module {
    /** One input to the simulation. tick = the world's tick just before the step it comes before. */
    public static final class Event {
        public long tick;
        public int kind;
        public float[] f;
        public int[] i;
        public String s;
    }

    public final List<Event> events = new ArrayList<Event>();
    /** True while a replay is feeding events back in (nothing new is recorded). */
    public boolean replaying;
    /** How a game began: map size, preset, seed, role, the people simulated in detail, and the scenario. */
    public static final class Header {
        public int size, preset, role, maxInd;
        public long seed;
        public String scenarioIni = "";

        void write(DataOutputStream o) throws IOException {
            o.writeInt(size);
            o.writeInt(preset);
            o.writeLong(seed);
            o.writeInt(role);
            o.writeInt(maxInd);
            o.writeUTF(scenarioIni);
        }

        void read(DataInputStream in) throws IOException {
            size = in.readInt();
            preset = in.readInt();
            seed = in.readLong();
            role = in.readInt();
            maxInd = in.readInt();
            scenarioIni = in.readUTF();
        }
    }

    public final Header header = new Header();
    private float lastCamX0 = Float.NaN, lastCamY0, lastCamX1, lastCamY1;
    private boolean lastDetail;

    // timelapse: per game hour, the dead and the people in each sector (log scale bytes)
    public final List<byte[]> lapseDead = new ArrayList<byte[]>(), lapsePeople = new ArrayList<byte[]>();
    public final List<Float> lapseTime = new ArrayList<Float>();
    private double nextSample;
    // heatmaps
    public final float[] peak, deaths;

    /**
     * On national maps the heatmaps and the timelapse are kept by district (8x8 sectors: 6 KB a game hour instead of
     * 400 KB), sampled every 3 game hours after day 3. Today's maps keep them by sector.
     */
    public final boolean coarse;
    /** Cells of the heatmaps and the timelapse, and the grid they make (lapseW x lapseH). */
    public final int lapseN, lapseW, lapseH;

    public Director(World w) {
        super(w);
        SectorIndex idx = w.sec.idx;
        coarse = w.national;
        lapseN = coarse ? idx.dn : w.sec.n;
        lapseW = coarse ? idx.dw : w.sec.sw;
        lapseH = coarse ? idx.dh : w.sec.sh;
        peak = new float[lapseN];
        deaths = new float[lapseN];
    }

    /** The heatmaps' cell for sector s (the sector itself, or its district on national maps). */
    public int cell(int s) {
        return coarse ? w.sec.idx.district(s) : s;
    }

    @Override
    public String id() {
        return "director";
    }

    public void record(int kind, float[] f, int[] i, String s) {
        if (replaying) return;
        Event e = new Event();
        e.tick = w.tick;
        e.kind = kind;
        e.f = f == null ? new float[0] : f;
        e.i = i == null ? new int[0] : i;
        e.s = s == null ? "" : s;
        events.add(e);
    }

    /** Someone died here (people and the dead alike, for the heatmap). */
    public void died(float x, float y) {
        deaths[cell(w.r.sectorOfPx(x, y))] += 1;
    }

    @Override
    public void update(float dt) {
        // the camera as the simulation saw it this second (it decides which sectors are simulated in detail)
        if (!replaying && (w.camX0 != lastCamX0 || w.camY0 != lastCamY0 || w.camX1 != lastCamX1 || w.camY1 != lastCamY1 || w.camDetail != lastDetail)) {
            lastCamX0 = w.camX0;
            lastCamY0 = w.camY0;
            lastCamX1 = w.camX1;
            lastCamY1 = w.camY1;
            lastDetail = w.camDetail;
            Event e = new Event();
            // it has to be in place before this step's once-a-second update: the step before
            e.tick = w.tick - (w.strategic ? (long) (World.STRATEGIC_DT * 20) : 1);
            e.kind = Actions.CAMERA;
            e.f = new float[]{w.camX0, w.camY0, w.camX1, w.camY1};
            e.i = new int[]{w.camDetail ? 1 : 0};
            e.s = "";
            events.add(e);
        }
        Sectors sec = w.sec;
        SectorIndex idx = sec.idx;
        if (coarse) {
            // (the districts' sums from the start of this second; a sleeping district has none of the dead)
            for (int d = 0; d < idx.dn; d++) if (idx.districtAwake(d) && idx.dead[d] > peak[d]) peak[d] = idx.dead[d];
        } else {
            // (only awake sectors hold any of the dead)
            for (int s = idx.first(); s >= 0; s = idx.next(s)) {
                int z = sec.zIn[s] + w.sp.secZ[s];
                if (z > peak[s]) peak[s] = z;
            }
        }
        if (w.time >= nextSample) {
            int every = coarse && w.day() > 3 ? 180 : 60;
            nextSample = Math.floor(w.time / every) * every + every;
            byte[] zd = new byte[lapseN], pp = new byte[lapseN];
            if (coarse) {
                for (int d = 0; d < idx.dn; d++) {
                    zd[d] = idx.districtAwake(d) ? log(idx.dead[d]) : 0;
                    pp[d] = log(idx.living[d]);
                }
            } else {
                for (int s = 0; s < sec.n; s++) {
                    zd[s] = log(sec.zIn[s] + w.sp.secZ[s]);
                    pp[s] = log(sec.popIn[s] + w.sp.secCiv[s]);
                }
            }
            lapseDead.add(zd);
            lapsePeople.add(pp);
            lapseTime.add((float) w.time);
        }
    }

    /** 0..255 on a log scale: 0 = none, 255 = a million. */
    static byte log(int v) {
        if (v <= 0) return 0;
        int b = (int) (Math.log(1 + v) / Math.log(1e6) * 254) + 1;
        return (byte) Math.min(255, b);
    }

    static int unlog(byte b) {
        int v = b & 255;
        if (v == 0) return 0;
        return (int) Math.exp((v - 1) / 254.0 * Math.log(1e6));
    }

    // ------------------------------------------------------------------ saves

    @Override
    public void save(DataOutputStream o) throws IOException {
        header.write(o);
        o.writeInt(events.size());
        for (Event e : events) writeEvent(o, e);
        o.writeFloat(lastCamX0);
        o.writeFloat(lastCamY0);
        o.writeFloat(lastCamX1);
        o.writeFloat(lastCamY1);
        o.writeBoolean(lastDetail);
        o.writeDouble(nextSample);
        o.writeInt(lapseTime.size());
        for (int k = 0; k < lapseTime.size(); k++) {
            o.writeFloat(lapseTime.get(k));
            o.write(lapseDead.get(k));
            o.write(lapsePeople.get(k));
        }
        for (int s = 0; s < peak.length; s++) {
            o.writeFloat(peak[s]);
            o.writeFloat(deaths[s]);
        }
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        header.read(in);
        events.clear();
        int n = in.readInt();
        for (int k = 0; k < n; k++) events.add(readEvent(in));
        lastCamX0 = in.readFloat();
        lastCamY0 = in.readFloat();
        lastCamX1 = in.readFloat();
        lastCamY1 = in.readFloat();
        lastDetail = in.readBoolean();
        nextSample = in.readDouble();
        lapseTime.clear();
        lapseDead.clear();
        lapsePeople.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) {
            lapseTime.add(in.readFloat());
            byte[] a = new byte[lapseN], b = new byte[lapseN];
            in.readFully(a);
            in.readFully(b);
            lapseDead.add(a);
            lapsePeople.add(b);
        }
        for (int s = 0; s < peak.length; s++) {
            peak[s] = in.readFloat();
            deaths[s] = in.readFloat();
        }
    }

    static void writeEvent(DataOutputStream o, Event e) throws IOException {
        o.writeLong(e.tick);
        o.writeInt(e.kind);
        o.writeInt(e.f.length);
        for (float v : e.f) o.writeFloat(v);
        o.writeInt(e.i.length);
        for (int v : e.i) o.writeInt(v);
        o.writeUTF(e.s);
    }

    static Event readEvent(DataInputStream in) throws IOException {
        Event e = new Event();
        e.tick = in.readLong();
        e.kind = in.readInt();
        e.f = new float[in.readInt()];
        for (int k = 0; k < e.f.length; k++) e.f[k] = in.readFloat();
        e.i = new int[in.readInt()];
        for (int k = 0; k < e.i.length; k++) e.i[k] = in.readInt();
        e.s = in.readUTF();
        return e;
    }
}
