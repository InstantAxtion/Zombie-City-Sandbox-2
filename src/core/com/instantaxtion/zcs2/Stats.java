package com.instantaxtion.zcs2;

import java.util.ArrayList;
import java.util.List;

/**
 * The numbers on screen and the after-action report: counts kept as things happen (never recounted by guess),
 * a timeline sampled every ten seconds, key events, the best fighters and the fallen.
 */
public final class Stats {
    private final World w;
    public int civDeaths, zombieKills, zombiesMade, risen, infectedTotal, shots, vehiclesLost, facilitiesLost,
            buildingsLost, airStrikes, bridgesBlown, supplyRuns, outsidePolice, outsideSoldiers, peakZombies, rotted, artillery, troopTrains, trades,
            fled, rumours, holdouts, militiaJoined, lootings, turnedBack;
    public final int[] killsBySide = new int[3]; // civilians, police, army
    public final int[] armedDeaths = new int[4];
    public final int[] zBySettlement, popBySettlement;
    /** People of each settlement bitten or killed there (counted as it happens). */
    public final int[] lostBySettlement;
    /** Civilians killed by our own guns (artillery on sheltering streets), and the dead put down by air. */
    public int shelledCivilians, strikeKills, returnedHome, containedTowns;
    /** Where people were lost (bitten or killed): indoors, on the roads, to hordes passing through, to our guns, to fire. */
    public final int[] lossBy = new int[5];
    public static final String[] LOSS_CAUSES = {"in their homes", "on the roads", "to hordes passing through", "to our own guns", "in fires"};
    /** Seconds between timeline samples: doubles when the timeline is full, so a month still fits. */
    public float tEvery = 10;
    public int startPolice, startSoldiers, startCivilians;
    /**
     * The population's ledger: everyone alive at the start (Audit.people), and the civilians who have turned
     * since (the bitten who rose, indoors or on the road). Alive + civDeaths + civTurned = startPeople, always.
     */
    public long startPeople, civTurned;
    /** Firefighters lost (they're not counted among the people: they come from the fire stations' crews). */
    public int crewDeaths;
    private int crewUnit = -2;

    /** Is entity i one of the people who live here (not a firefighter sent from a station)? */
    public boolean resident(int i) {
        if (crewUnit == -2) {
            Content.Unit u = w.c.unit("firefighter");
            crewUnit = u == null ? -1 : u.index;
        }
        return w.e.kind[i] == Ents.CIV && w.e.type[i] != crewUnit;
    }

    // timeline: every 10 s
    public static final int MAXT = 4000;
    public final float[] tTime = new float[MAXT];
    /** tPolice and tArmy are those out in squads; tPoliceAll and tArmyAll everyone (waiting at stations and bases too; -1 unknown). */
    public final int[] tZombies = new int[MAXT], tAlive = new int[MAXT], tPolice = new int[MAXT], tArmy = new int[MAXT],
            tPoliceAll = new int[MAXT], tArmyAll = new int[MAXT];
    public int tN;
    /** Why things happened: the tracer, towns, formations, moments, callouts (a module of its own, saved with the modules). */
    public final Records rec;

    public static final class Event {
        public final String text;
        public final float x, y;
        public final double time;

        Event(String text, float x, float y, double time) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.time = time;
        }
    }

    public final List<Event> events = new ArrayList<Event>();

    /** The fallen: name, role, kills, how they died, when. */
    public final List<String> fallen = new ArrayList<String>();
    /** The best fighters (uid -> description), refreshed as they score. */
    public final int[] bestUid = new int[10], bestKills = new int[10];
    public final String[] bestName = new String[10];

    Stats(World w) {
        this.w = w;
        zBySettlement = new int[w.r.settlements.size()];
        popBySettlement = new int[w.r.settlements.size()];
        lostBySettlement = new int[w.r.settlements.size()];
        java.util.Arrays.fill(tPoliceAll, -1);
        java.util.Arrays.fill(tArmyAll, -1);
        rec = new Records(w);
    }

    /** Police out in squads, and in all (out, and waiting at stations and in formations). The HUD, panel and report use these. */
    public int policeOut() {
        return w.sq.count(Ents.COP);
    }

    public int policeAll() {
        return rec.policeAll();
    }

    public int soldiersOut() {
        return w.sq.count(Ents.SOLDIER);
    }

    public int soldiersAll() {
        return rec.soldiersAll();
    }

    public void event(String text, float x, float y) {
        events.add(new Event(text, x, y, w.time));
        if (events.size() > 300) events.remove(0);
    }

    /** Where people were lost, biggest first ("412,000 in their homes, 31,000 on the roads"), or "" if nobody was. */
    public String lossSummary() {
        StringBuilder sb = new StringBuilder();
        // the counted causes, and those bitten in the streets one by one (kept by the records)
        int[] by = java.util.Arrays.copyOf(lossBy, lossBy.length + 1);
        by[lossBy.length] = rec.streetLost;
        boolean[] used = new boolean[by.length];
        for (int n = 0; n < by.length; n++) {
            int best = -1;
            for (int i = 0; i < by.length; i++) if (!used[i] && by[i] > 0 && (best < 0 || by[i] > by[best])) best = i;
            if (best < 0) break;
            used[best] = true;
            if (sb.length() > 0) sb.append(", ");
            sb.append(News.num(by[best])).append(' ').append(best < LOSS_CAUSES.length ? LOSS_CAUSES[best] : "in the streets");
        }
        return sb.toString();
    }

    void noteKiller(int i) {
        Ents e = w.e;
        int k = e.kills[i], uid = e.uid[i];
        // one line per person: found by who they are (their uid), and named in full so two people who share a
        // surname aren't taken for one fighter listed twice
        String name = (e.armed(i) ? Ranks.title(e.kind[i], e.rank[i]) + " " + Names.person(w.seed, uid) : w.nameOf(i))
                + (e.armed(i) ? ", " + w.unitOf(i).name : "");
        int slot = -1;
        for (int j = 0; j < 10; j++) if (bestKills[j] > 0 && bestUid[j] == uid) slot = j;
        if (slot < 0) {
            int low = 0;
            for (int j = 1; j < 10; j++) if (bestKills[j] < bestKills[low]) low = j;
            if (bestKills[low] >= k) return;
            slot = low;
        }
        bestUid[slot] = uid;
        bestKills[slot] = k;
        bestName[slot] = name;
    }

    void noteFallen(int i, int cause) {
        // why, from their last seconds (the tracer), and a callout where they fell
        String why = rec.died(i, cause);
        rec.callout(w.e.kind[i] == Ents.COP ? "Officer down" : "Man down", w.e.x[i], w.e.y[i], Records.C_LOSS);
        if (fallen.size() > 500) return;
        fallen.add(w.clock() + "  " + w.nameOf(i) + ": " + why + " (" + w.unitOf(i).name + ", " + w.e.kills[i] + " kills)");
    }

    /** The people and the dead in each settlement (also after loading a save, where they aren't stored). */
    public void recount() {
        java.util.Arrays.fill(zBySettlement, 0);
        java.util.Arrays.fill(popBySettlement, 0);
        // (people in sleeping districts from the running totals; the rest, and the dead, only in awake ones)
        SectorIndex idx = w.sec.idx;
        idx.popBySettlement(popBySettlement);
        for (int s = idx.first(); s >= 0; s = idx.next(s)) {
            int st = w.sec.settlement[s];
            if (st < 0) continue;
            zBySettlement[st] += w.sp.secZ[s] + w.sec.zIn[s];
            popBySettlement[st] += w.sp.secCiv[s];
        }
    }

    void update() {
        int z = w.totalZombies();
        if (z > peakZombies) peakZombies = z;
        recount();
        if (tN >= MAXT) {
            // full: keep every other sample and sample half as often
            int k = 0;
            for (int i = 0; i < tN; i += 2, k++) {
                tTime[k] = tTime[i];
                tZombies[k] = tZombies[i];
                tAlive[k] = tAlive[i];
                tPolice[k] = tPolice[i];
                tArmy[k] = tArmy[i];
                tPoliceAll[k] = tPoliceAll[i];
                tArmyAll[k] = tArmyAll[i];
            }
            tN = k;
            tEvery *= 2;
        }
        if (tN == 0 || w.time - tTime[tN - 1] >= tEvery - 0.01f) {
            tTime[tN] = (float) w.time;
            tZombies[tN] = z;
            tAlive[tN] = w.livingCivilians();
            tPolice[tN] = w.sq.count(Ents.COP);
            tArmy[tN] = w.sq.count(Ents.SOLDIER);
            tPoliceAll[tN] = policeAll();
            tArmyAll[tN] = soldiersAll();
            tN++;
        }
    }

    /** The moment the dead grew fastest: the outbreak's turning point. */
    public int turningPoint() {
        int best = -1, bg = 0;
        for (int i = 6; i < tN; i++) {
            int g = tZombies[i] - tZombies[i - 6];
            if (g > bg) {
                bg = g;
                best = i;
            }
        }
        return best;
    }
}
