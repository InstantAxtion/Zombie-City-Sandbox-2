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
            buildingsLost, airStrikes, bridgesBlown, supplyRuns, outsidePolice, outsideSoldiers, peakZombies, rotted, artillery;
    public final int[] killsBySide = new int[3]; // civilians, police, army
    public final int[] armedDeaths = new int[4];
    public final int[] zBySettlement, popBySettlement;
    public int startPolice, startSoldiers, startCivilians;

    // timeline: every 10 s
    public static final int MAXT = 4000;
    public final float[] tTime = new float[MAXT];
    public final int[] tZombies = new int[MAXT], tAlive = new int[MAXT], tPolice = new int[MAXT], tArmy = new int[MAXT];
    public int tN;

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
    }

    public void event(String text, float x, float y) {
        events.add(new Event(text, x, y, w.time));
        if (events.size() > 300) events.remove(0);
    }

    void noteKiller(int i) {
        Ents e = w.e;
        int k = e.kills[i], uid = e.uid[i];
        int slot = -1;
        for (int j = 0; j < 10; j++) if (bestUid[j] == uid) slot = j;
        if (slot < 0) {
            int low = 0;
            for (int j = 1; j < 10; j++) if (bestKills[j] < bestKills[low]) low = j;
            if (bestKills[low] >= k) return;
            slot = low;
        }
        bestUid[slot] = uid;
        bestKills[slot] = k;
        bestName[slot] = w.nameOf(i) + (e.armed(i) ? ", " + w.unitOf(i).name : "");
    }

    void noteFallen(int i, int cause) {
        if (fallen.size() > 500) return;
        String[] how = {"bitten", "shot", "in a blast", "in a crash", "turned", ""};
        fallen.add(w.clock() + "  " + w.nameOf(i) + " (" + w.unitOf(i).name + ", " + w.e.kills[i] + " kills), " + how[Math.min(5, cause)]);
    }

    void update() {
        int z = w.totalZombies();
        if (z > peakZombies) peakZombies = z;
        java.util.Arrays.fill(zBySettlement, 0);
        java.util.Arrays.fill(popBySettlement, 0);
        for (int s = 0; s < w.sec.n; s++) {
            int st = w.sec.settlement[s];
            if (st < 0) continue;
            zBySettlement[st] += w.sp.secZ[s] + w.sec.zIn[s];
            popBySettlement[st] += w.sec.popIn[s] + w.sp.secCiv[s];
        }
        if (w.tick % 200 == 0 && tN < MAXT) {
            tTime[tN] = (float) w.time;
            tZombies[tN] = z;
            tAlive[tN] = w.livingCivilians();
            tPolice[tN] = w.sq.count(Ents.COP);
            tArmy[tN] = w.sq.count(Ents.SOLDIER);
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
