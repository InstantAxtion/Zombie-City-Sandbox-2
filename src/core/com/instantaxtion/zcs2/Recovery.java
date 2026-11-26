package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * After the fighting: a town that has been clear of the dead for a while is declared safe (with what it cost), the
 * country is declared clear, outside units go home over the border once they're no longer needed, and the people
 * who fled come home (Strategic.homecoming). The alert level, curfews, shelter orders and evacuations come down
 * with the officials' picture (Command, Gov). Also keeps, in the save, the state the region-wide systems added.
 */
public final class Recovery extends Module {
    private final int[] peak;
    private final float[] clearT;
    private int natPeak;
    private float natClearT;

    public Recovery(World w) {
        super(w);
        int n = w.r.settlements.size();
        peak = new int[n];
        clearT = new float[n];
    }

    @Override
    public String id() {
        return "recovery";
    }

    @Override
    public void update(float dt) {
        for (Region.Settlement s : w.r.settlements) {
            if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN && s.kind != Region.S_VILLAGE) continue;
            int st = s.id, z = w.stats.zBySettlement[st];
            if (z > peak[st]) peak[st] = z;
            if (z > 0) {
                clearT[st] = 0;
                continue;
            }
            if (peak[st] < (s.kind == Region.S_VILLAGE ? 60 : 20)) continue;
            clearT[st] += dt;
            if (clearT[st] >= 120) {
                int lost = w.stats.lostBySettlement[st];
                w.news.headline("The outbreak in " + s.name + " is over. At its worst " + News.num(peak[st]) + " of the dead were in its streets"
                        + (lost > 0 ? "; " + News.num(lost) + " of its people were bitten or killed." : "."), s.cx * 16f, s.cy * 16f);
                w.stats.containedTowns++;
                w.gov.trust += 2;
                // it's official: the authorities' picture is clear, and the units sent there are free
                w.command.declaredClear(st);
                w.forces.townCleared(st);
                peak[st] = 0;
                clearT[st] = 0;
            }
        }
        int total = w.totalZombies();
        sweepT += dt;
        if (sweepT >= 60) {
            sweepT -= 60;
            sweep(total);
        }
        if (total > natPeak) natPeak = total;
        if (total > 0) natClearT = 0;
        else if (natPeak >= 50) {
            natClearT += dt;
            if (natClearT >= 360) {
                String lost = w.stats.lossSummary();
                w.news.headline("No more of the dead are reported anywhere in the country. The worst is over." + (lost.length() > 0 ? " People bitten or killed: " + lost + "." : ""), -1, -1);
                natPeak = 0;
                natClearT = 0;
            }
        }
        // outside units go home once the emergency has eased and there's nothing for them to do
        if (w.gov.level <= 2) {
            for (Forces.Formation f : w.forces.formations) {
                if (!f.alive || !f.outside || f.task != -1 || Forces.travelling(f) || !w.forces.aiMay(f)) continue;
                if (f.idleT < w.c.rule("leaveAfter", 720)) continue;
                leave(f);
            }
        }
    }

    private float sweepT;

    /**
     * Once a game hour, when the war is all but won: police, Guard patrols and hunters find the last scattered
     * dead (small nests in quiet streets and fields nobody is fighting over) and put them down, a share an hour,
     * so a few dozen stragglers don't hold the country's alert up forever.
     */
    private void sweep(int total) {
        if (total == 0 || total > 4000) return;
        Sectors sec = w.sec;
        int killed = 0, nests = 0;
        float bx = -1, by = -1;
        int bk = 0;
        for (int s = 0; s < sec.n; s++) {
            int z = sec.zIn[s];
            if (z <= 0 || z > 400 || sec.hot[s] > 0 && !w.strategic) continue;
            float share = sec.settlement[s] >= 0 ? 0.35f : 0.2f;
            int k = Math.min(z, Math.max(Rng.unit(w.seed, w.tick, s * 31L + 5) < share ? 1 : 0, (int) (z * share)));
            if (k <= 0) continue;
            sec.zIn[s] -= k;
            sec.bodies[s] += k;
            killed += k;
            if (sec.zIn[s] == 0) nests++;
            if (k > bk) {
                bk = k;
                bx = sec.cx(s);
                by = sec.cy(s);
            }
        }
        // straggling packs on the roads too
        for (Strategic.Horde h : w.strat.hordes) {
            if (!h.alive || h.count > 150) continue;
            int k = Math.max(1, (int) (h.count * 0.3f));
            h.count -= k;
            killed += k;
            if (h.count <= 0) {
                h.alive = false;
                nests++;
            }
        }
        if (killed == 0) return;
        w.stats.killsBySide[2] += killed;
        w.stats.zombieKills += killed;
        if (nests > 0) w.news.tally("sweep", "Sweeps: patrols are hunting down the last of the dead in the streets and fields"
                + "|Sweeps since the last report: {n} more nest{s} of the dead cleared, about {m} put down", nests, killed, bx, by, News.COMMAND);
    }

    /** An outside formation goes back over the border: its people leave the map with it. */
    private void leave(Forces.Formation f) {
        for (Squads.Squad s : w.sq.list) if (s.alive && s.home == f.hq.id && !s.player) w.forces.standDown(s);
        if (f.hq.pool != null) java.util.Arrays.fill(f.hq.pool, 0);
        if (f.hq.vpool != null) java.util.Arrays.fill(f.hq.vpool, 0);
        f.alive = false;
        f.deployed = 0;
        w.news.count("leave", "The " + f.name + " is going home: its job here is done|%d more outside unit{s} {have} gone home", 1, f.hq.x, f.hq.y);
    }

    // ------------------------------------------------------------------ saves (this module's, and the state added to others)

    @Override
    public void save(DataOutputStream o) throws IOException {
        int n = peak.length;
        o.writeInt(n);
        for (int i = 0; i < n; i++) {
            o.writeInt(peak[i]);
            o.writeFloat(clearT[i]);
        }
        o.writeInt(natPeak);
        o.writeFloat(natClearT);
        // Stats
        Stats st = w.stats;
        o.writeFloat(st.tEvery);
        o.writeInt(st.shelledCivilians);
        o.writeInt(st.strikeKills);
        o.writeInt(st.returnedHome);
        o.writeInt(st.containedTowns);
        o.writeInt(st.fled);
        o.writeInt(st.rumours);
        o.writeInt(st.holdouts);
        o.writeInt(st.lootings);
        o.writeInt(st.turnedBack);
        for (int v : st.lostBySettlement) o.writeInt(v);
        for (int v : st.lossBy) o.writeInt(v);
        // Strategic
        Strategic sg = w.strat;
        for (int i = 0; i < n; i++) {
            o.writeInt(sg.infPending[i]);
            o.writeInt(sg.outBy[i]);
            o.writeFloat(sg.leftThisHour[i]);
            o.writeInt(sg.lostSeen[i]);
            for (int j = 0; j < n; j++) o.writeInt(sg.refuge[i][j]);
        }
        o.writeFloat(sg.hourT);
        o.writeInt(sg.columns.size());
        for (Strategic.Column c : sg.columns) o.writeBoolean(c.home);
        // Gov
        Gov g = w.gov;
        o.writeInt(g.lastHourZ);
        o.writeFloat(g.hourT);
        o.writeFloat(g.improvingT);
        o.writeFloat(g.curfewT);
        // News tallies
        o.writeInt(w.news.tallies.size());
        for (News.Tally t : w.news.tallies) {
            o.writeUTF(t.key);
            o.writeUTF(t.text);
            o.writeFloat(t.x);
            o.writeFloat(t.y);
            o.writeInt(t.kind);
            o.writeInt(t.n);
            o.writeInt(t.m);
            o.writeDouble(t.since);
        }
        o.writeBoolean(w.watching);
        o.writeInt(sg.hordes.size());
        for (Strategic.Horde h : sg.hordes) o.writeFloat(h.waitT);
        o.writeInt(sg.byDead.length);
        for (boolean b : sg.byDead) o.writeBoolean(b);
        o.writeFloat(sweepT);
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            peak[i] = in.readInt();
            clearT[i] = in.readFloat();
        }
        natPeak = in.readInt();
        natClearT = in.readFloat();
        Stats st = w.stats;
        st.tEvery = in.readFloat();
        st.shelledCivilians = in.readInt();
        st.strikeKills = in.readInt();
        st.returnedHome = in.readInt();
        st.containedTowns = in.readInt();
        st.fled = in.readInt();
        st.rumours = in.readInt();
        st.holdouts = in.readInt();
        st.lootings = in.readInt();
        st.turnedBack = in.readInt();
        for (int i = 0; i < n; i++) st.lostBySettlement[i] = in.readInt();
        for (int i = 0; i < st.lossBy.length; i++) st.lossBy[i] = in.readInt();
        Strategic sg = w.strat;
        for (int i = 0; i < n; i++) {
            sg.infPending[i] = in.readInt();
            sg.outBy[i] = in.readInt();
            sg.leftThisHour[i] = in.readFloat();
            sg.lostSeen[i] = in.readInt();
            for (int j = 0; j < n; j++) sg.refuge[i][j] = in.readInt();
        }
        sg.hourT = in.readFloat();
        int c = in.readInt();
        for (int i = 0; i < c; i++) {
            boolean h = in.readBoolean();
            if (i < sg.columns.size()) sg.columns.get(i).home = h;
        }
        Gov g = w.gov;
        g.lastHourZ = in.readInt();
        g.hourT = in.readFloat();
        g.improvingT = in.readFloat();
        g.curfewT = in.readFloat();
        int k = in.readInt();
        w.news.tallies.clear();
        for (int i = 0; i < k; i++) {
            News.Tally t = new News.Tally();
            t.key = in.readUTF();
            t.text = in.readUTF();
            t.x = in.readFloat();
            t.y = in.readFloat();
            t.kind = in.readInt();
            t.n = in.readInt();
            t.m = in.readInt();
            t.since = in.readDouble();
            w.news.tallies.add(t);
        }
        w.watching = in.readBoolean();
        int nh = in.readInt();
        for (int i = 0; i < nh; i++) {
            float wt = in.readFloat();
            if (i < sg.hordes.size()) sg.hordes.get(i).waitT = wt;
        }
        int nb = in.readInt();
        sg.extendFacilities();
        for (int i = 0; i < nb; i++) {
            boolean b = in.readBoolean();
            if (i < sg.byDead.length) sg.byDead[i] = b;
        }
        if (in.available() > 0) sweepT = in.readFloat();
    }
}
