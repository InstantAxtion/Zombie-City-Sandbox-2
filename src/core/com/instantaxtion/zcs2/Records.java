package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * What makes the outbreak legible afterwards and while it happens, kept beside the counts in Stats. It only reads
 * the simulation (and is told of a few events), never changes it:
 * - a tracer per armed person: the last ten samples (one a game minute, ten seconds at 1x) of what they were doing,
 *   their target, the odds round them, ammunition and health, so a death can be explained ("out of ammo,
 *   surrounded by 6, fell back too late");
 * - why each town fell or was given up (its police gone, the phones down, a horde arrived, no army sent, the
 *   evacuation failed), and when; and which held;
 * - each formation's story: held or broke, when, what it cost and why;
 * - the decisive moments, the police and soldiers in all (not only those out in squads), the kill rate;
 * - callouts (short floating text where something happened) and alerts (what fast-forward should stop for).
 */
public final class Records extends Module {
    // ------------------------------------------------------------------ the tracer

    /** Samples kept per person (one a game minute: the last ten seconds at 1x). */
    public static final int TRACE = 10;
    private int[] trOwn = new int[0];
    private byte[] trAct = new byte[0];
    private short[] trNear = new short[0], trFriends = new short[0], trAmmo = new short[0], trTarget = new short[0];
    private byte[] trHp = new byte[0];
    private int[] trN = new int[0];
    /** Why each fallen fighter died, by uid (the latest few hundred). */
    private final java.util.LinkedHashMap<Integer, String> deathWhy = new java.util.LinkedHashMap<Integer, String>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Integer, String> e) {
            return size() > 600;
        }
    };

    // ------------------------------------------------------------------ towns

    public static final int T_NONE = 0, T_FELL = 1, T_ABANDONED = 2;
    /** Per settlement: fell or abandoned (T_*), when (game minutes, -1 never), why, and what it cost then. */
    public final int[] townHow;
    public final float[] townAt;
    public final String[] townWhy;
    public final int[] townPeakZ, townLostAt;
    /** Per settlement: the dead reached it; the army was asked for; a formation was sent; its police at the start. */
    private final boolean[] touched, armyAsked, armySent;
    private final int[] policeStart;
    /** When the last horde came within reach of each settlement (game minutes), and how big it was. */
    private final float[] hordeAt;
    private final int[] hordeSize;

    // ------------------------------------------------------------------ formations

    public static final class Unit {
        public int id, faction, start, kills, losses;
        public String name;
        public float firstAt, brokeAt = -1, goneAt = -1;
        /** 0 still holding (or home), 1 broke, 2 destroyed. */
        public int how;
        public String why = "";
        public float x, y;
    }

    public final List<Unit> units = new ArrayList<Unit>();

    // ------------------------------------------------------------------ moments, totals, rate

    public static final class Moment {
        public final String text;
        public final float x, y, at, weight;

        Moment(String text, float x, float y, float at, float weight) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.at = at;
            this.weight = weight;
        }
    }

    public final List<Moment> moments = new ArrayList<Moment>();
    /** People bitten in the streets, one by one (the counted losses indoors, on the roads... are in Stats.lossBy). */
    public int streetLost;
    /** Police and soldiers at the start, all of them (in squads and waiting at stations and bases). */
    public int startPoliceAll = -1, startSoldiersAll = -1;
    private final float[] rateT = new float[32];
    private final int[] rateK = new int[32];
    private int rateN;
    private boolean[] facOn = new boolean[0];
    private boolean[] gridWasOn = new boolean[0];
    private int strainSeen = -1;
    private final java.util.HashSet<Long> hordeTold = new java.util.HashSet<Long>();
    private float townT;

    // ------------------------------------------------------------------ callouts and alerts (not saved)

    public static final int C_LOSS = 0, C_BREACH = 1, C_HORDE = 2, C_BLAST = 3, C_FACILITY = 4, C_TOWN = 5;
    /** The priority of each kind of callout (higher wins a crowded spot). */
    public static final int[] C_PRIO = {2, 1, 3, 3, 4, 5};
    public static final int[] C_COLOR = {0xFFFF8A80, 0xFFFFC070, 0xFFFF6A5A, 0xFFFFE080, 0xFFFFB0A0, 0xFFFF5050};

    public static final class Callout {
        public final String text;
        public final float x, y;
        public final int kind;
        public final long seq;

        Callout(String text, float x, float y, int kind, long seq) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.kind = kind;
            this.seq = seq;
        }
    }

    /** The latest callouts (the view takes the new ones by seq). */
    public final Callout[] callouts = new Callout[64];
    public long calloutSeq;

    public static final int A_HORDE = 0, A_FACILITY = 1, A_STRAIN = 2, A_BLACKOUT = 3, A_TOWN = 4;
    public static final String[] A_NAMES = {"A horde near a town", "A facility lost", "A new strain", "A blackout", "A town lost"};

    public static final class Alert {
        public final String text;
        public final float x, y;
        public final int kind;
        public final long seq;

        Alert(String text, float x, float y, int kind, long seq) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.kind = kind;
            this.seq = seq;
        }
    }

    public final Alert[] alerts = new Alert[16];
    public long alertSeq;
    /** How near (px) a horde must come to a town to be worth stopping fast-forward for. */
    public static final float HORDE_NEAR = 2400;

    Records(World w) {
        super(w);
        int n = w.r.settlements.size();
        townHow = new int[n];
        townAt = new float[n];
        java.util.Arrays.fill(townAt, -1);
        townWhy = new String[n];
        townPeakZ = new int[n];
        townLostAt = new int[n];
        touched = new boolean[n];
        armyAsked = new boolean[n];
        armySent = new boolean[n];
        policeStart = new int[n];
        java.util.Arrays.fill(policeStart, -1);
        hordeAt = new float[n];
        java.util.Arrays.fill(hordeAt, -1e9f);
        hordeSize = new int[n];
    }

    @Override
    public String id() {
        return "records";
    }

    // ------------------------------------------------------------------ events told by the simulation

    /** A short line over the map where something happened. */
    public void callout(String text, float x, float y, int kind) {
        callouts[(int) (calloutSeq % callouts.length)] = new Callout(text, x, y, kind, calloutSeq);
        calloutSeq++;
    }

    void alert(String text, float x, float y, int kind) {
        alerts[(int) (alertSeq % alerts.length)] = new Alert(text, x, y, kind, alertSeq);
        alertSeq++;
    }

    void moment(String text, float x, float y, float weight) {
        moments.add(new Moment(text, x, y, (float) w.time, weight));
        if (moments.size() > 200) {
            // the least of them goes
            int low = 0;
            for (int k = 1; k < moments.size(); k++) if (moments.get(k).weight < moments.get(low).weight) low = k;
            moments.remove(low);
        }
    }

    // ------------------------------------------------------------------ the tracer

    private void grow(int cap) {
        if (trOwn.length >= cap) return;
        int n = Math.max(cap, trOwn.length * 3 / 2);
        trOwn = java.util.Arrays.copyOf(trOwn, n);
        trN = java.util.Arrays.copyOf(trN, n);
        trAct = java.util.Arrays.copyOf(trAct, n * TRACE);
        trHp = java.util.Arrays.copyOf(trHp, n * TRACE);
        trNear = java.util.Arrays.copyOf(trNear, n * TRACE);
        trFriends = java.util.Arrays.copyOf(trFriends, n * TRACE);
        trAmmo = java.util.Arrays.copyOf(trAmmo, n * TRACE);
        trTarget = java.util.Arrays.copyOf(trTarget, n * TRACE);
    }

    /** One sample for every armed person on foot (once a game minute, at normal speeds). */
    void sampleAll() {
        Ents e = w.e;
        grow(e.cap);
        for (int i = 0; i < e.top; i++) {
            if (e.loc[i] != Ents.FREE || !e.armed(i)) continue;
            sample(i);
        }
    }

    /** Records person i's state now (also called at the moment they fall, so the last sample is the end). */
    void sample(int i) {
        Ents e = w.e;
        if (i >= trOwn.length) grow(e.cap);
        if (trOwn[i] != e.uid[i]) {
            trOwn[i] = e.uid[i];
            trN[i] = 0;
        }
        int k = i * TRACE + trN[i] % TRACE;
        trN[i]++;
        trAct[k] = w.aai.has(i) ? w.aai.act[i] : 0;
        trHp[k] = (byte) Math.max(0, Math.min(100, Math.round(100 * Math.max(0, e.hp[i]) / Math.max(1, e.maxHp[i]))));
        trNear[k] = (short) w.sp.zombiesNear(e, e.x[i], e.y[i], 48, 99);
        trFriends[k] = (short) Math.max(0, w.sp.livingNear(e, e.x[i], e.y[i], 80, 99) - 1);
        trAmmo[k] = (short) Math.max(0, Math.min(Short.MAX_VALUE, e.ammo[i]));
        int t = e.target[i];
        trTarget[k] = (short) (t >= 0 && t < e.top && e.loc[t] == Ents.FREE && e.kind[t] == Ents.ZOMBIE
                ? Math.min(Short.MAX_VALUE, (int) Math.sqrt((e.x[t] - e.x[i]) * (e.x[t] - e.x[i]) + (e.y[t] - e.y[i]) * (e.y[t] - e.y[i]))) : -1);
    }

    /** How many samples person i has (up to TRACE), oldest first through sampleAt. */
    public int traceLength(int i) {
        if (i < 0 || i >= trOwn.length || trOwn[i] != w.e.uid[i]) return 0;
        return Math.min(TRACE, trN[i]);
    }

    /** Sample k (0 = oldest kept) of person i: {doing, hp %, dead within reach, living with them, ammo, target distance or -1}. */
    public int[] sampleAt(int i, int k) {
        int n = traceLength(i);
        int base = trN[i] - n;
        int s = i * TRACE + (base + k) % TRACE;
        return new int[]{trAct[s], trHp[s], trNear[s], trFriends[s], trAmmo[s], trTarget[s]};
    }

    /** Called as an armed person dies: the reason from their last samples, kept for the card and the report. */
    String died(int i, int cause) {
        sample(i);
        String why = explain(i, cause);
        deathWhy.put(w.e.uid[i], why);
        return why;
    }

    /** Why a person died, if they were traced ("" if not known). */
    public String deathReason(int uid) {
        String s = deathWhy.get(uid);
        return s == null ? "" : s;
    }

    /** The reason, in a few words joined by commas, from the samples (the last is the moment of death). */
    String explain(int i, int cause) {
        int n = traceLength(i);
        List<String> parts = new ArrayList<String>();
        if (n == 0) return cause == Combat.CAUSE_BITE ? "bitten" : "killed";
        int[] last = sampleAt(i, n - 1);
        Content.Unit u = w.unitOf(i);
        int full = u.weapon != null ? Math.max(1, u.weapon.ammo) : 1;
        if (last[4] == 0) parts.add("out of ammo");
        else if (last[4] < full / 8) parts.add("low on ammo");
        int near = 0, friends = 99;
        for (int k = Math.max(0, n - 3); k < n; k++) {
            int[] s = sampleAt(i, k);
            near = Math.max(near, s[2]);
            friends = Math.min(friends, s[3]);
        }
        if (near >= 3) parts.add("surrounded by " + near);
        else if (near >= 1 && cause == Combat.CAUSE_BITE) parts.add(near == 1 ? "caught by one of the dead" : "caught by " + near + " of the dead");
        if (friends == 0 && near > 0) parts.add("alone");
        // falling back, breaking, reloading: what the last seconds were spent doing
        boolean fellBack = false, fought = false, broke = false, reloading = false;
        for (int k = 0; k < n; k++) {
            int a = sampleAt(i, k)[0];
            if (a == ArmedAI.A_FALLBACK || a == ArmedAI.A_WOUNDED) fellBack = true;
            else if (a == ArmedAI.A_FLEE) broke = true;
            else if (a == ArmedAI.A_ENGAGE || a == ArmedAI.A_REARGUARD || a == ArmedAI.A_COVER) {
                if (!fellBack) fought = true;
            }
            if (a == ArmedAI.A_RELOAD && k >= n - 2) reloading = true;
        }
        if (broke) parts.add("broke and ran");
        else if (fellBack && fought) parts.add("fell back too late");
        else if (fellBack) parts.add("caught falling back");
        if (reloading && last[4] > 0) parts.add("caught reloading");
        int[] first = sampleAt(i, 0);
        if (first[1] < 50) parts.add("already wounded");
        if (last[0] == ArmedAI.A_REARGUARD) parts.add("holding the rear");
        if (cause == Combat.CAUSE_GUN) parts.add(0, "shot by our own side");
        else if (cause == Combat.CAUSE_BLAST) parts.add(0, "caught in a blast");
        else if (cause == Combat.CAUSE_VEHICLE) parts.add(0, "in a crash");
        if (parts.isEmpty()) parts.add(cause == Combat.CAUSE_BITE ? "bitten in the fight" : "killed");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ the counts

    /** Police in all: out in squads, and waiting at stations and in formations. */
    public int policeAll() {
        return w.sq.count(Ents.COP) + w.forces.total(Ents.COP);
    }

    public int soldiersAll() {
        return w.sq.count(Ents.SOLDIER) + w.forces.total(Ents.SOLDIER);
    }

    /** Of the dead destroyed, how many a game minute lately (over the last ten minutes, or what there is). */
    public float killRate() {
        if (rateN < 2) return 0;
        int last = (rateN - 1) % rateT.length;
        float now = rateT[last];
        int back = last;
        for (int k = 1; k < Math.min(rateN, rateT.length); k++) {
            int j = (rateN - 1 - k) % rateT.length;
            back = j;
            if (now - rateT[j] >= 10) break;
        }
        float span = now - rateT[back];
        return span <= 0 ? 0 : (rateK[last] - rateK[back]) / span;
    }

    // ------------------------------------------------------------------ once a game second

    @Override
    public void update(float dt) {
        if (startPoliceAll < 0) {
            startPoliceAll = policeAll();
            startSoldiersAll = soldiersAll();
        }
        int ri = rateN % rateT.length;
        rateT[ri] = (float) w.time;
        rateK[ri] = w.stats.zombieKills;
        rateN++;
        if (!w.strategic) sampleAll();
        watchFacilities();
        watchHordes();
        if (w.strat.strain > strainSeen) {
            if (strainSeen >= 0) {
                News.Item h = w.news.headline;
                float x = h != null ? h.x : 0, y = h != null ? h.y : 0;
                String name = w.strat.strain < Ecology.STRAIN_NEWS.length ? Ecology.STRAIN_NEWS[w.strat.strain] : "";
                String t = name.indexOf(':') > 0 ? name.substring(0, name.indexOf(':')) : "A new strain";
                alert("New strain: " + t.charAt(0) + t.substring(1).toLowerCase(), x, y, A_STRAIN);
                callout("New strain", x, y, C_HORDE);
                moment("A new strain appeared: " + t.toLowerCase(), x, y, 60);
            }
            strainSeen = w.strat.strain;
        }
        townT -= dt;
        if (townT <= 0) {
            townT = 5;
            watchTowns();
            watchFormations();
        }
    }

    private void watchFacilities() {
        List<Region.Facility> fs = w.r.facilities;
        boolean[] on = w.strat.online;
        if (facOn.length < on.length) {
            int old = facOn.length;
            facOn = java.util.Arrays.copyOf(facOn, on.length);
            for (int k = old; k < on.length; k++) facOn[k] = on[k];
        }
        for (int k = 0; k < Math.min(on.length, fs.size()); k++) {
            if (facOn[k] && !on[k]) {
                Region.Facility f = fs.get(k);
                if (!f.runtime && f.kind != Region.F_PARK && f.kind != Region.F_HELIPAD) {
                    alert(f.name + " has been overrun", f.x, f.y, A_FACILITY);
                    callout(f.name + " lost", f.x, f.y, C_FACILITY);
                    boolean big = f.kind == Region.F_POWER || f.kind == Region.F_BASE || f.kind == Region.F_HOSPITAL || f.kind == Region.F_POLICE;
                    moment(f.name + " was overrun", f.x, f.y, big ? 40 : 15);
                }
            }
            facOn[k] = on[k];
        }
        boolean[] g = w.strat.gridOn;
        if (gridWasOn.length != g.length) {
            gridWasOn = g.clone();
            return;
        }
        for (int k = 0; k < g.length; k++) {
            if (gridWasOn[k] && !g[k]) {
                float x = 0, y = 0;
                for (Region.Facility f : fs)
                    if (f.kind == Region.F_POWER && f.grid == k) {
                        x = f.x;
                        y = f.y;
                    }
                String name = w.strat.gridName(k);
                alert("Blackout: " + name + " goes dark", x, y, A_BLACKOUT);
                callout("Blackout", x, y, C_FACILITY);
                moment("Blackout: " + name + " went dark", x, y, 50);
            }
            gridWasOn[k] = g[k];
        }
    }

    private void watchHordes() {
        List<Region.Settlement> ss = w.r.settlements;
        for (Strategic.Horde h : w.strat.hordes) {
            if (!h.alive || h.waitT > 0 || h.count < 50) continue;
            for (Region.Settlement s : ss) {
                if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN && s.kind != Region.S_VILLAGE) continue;
                float dx = s.cx * 16f - h.x, dy = s.cy * 16f - h.y, reach = HORDE_NEAR + s.radius * 16f;
                if (dx * dx + dy * dy > reach * reach) continue;
                hordeAt[s.id] = (float) w.time;
                hordeSize[s.id] = Math.max(hordeSize[s.id], h.count);
                long key = ((long) h.id << 20) | s.id;
                if (!hordeTold.add(key)) continue;
                if (hordeTold.size() > 20000) hordeTold.clear();
                alert("A horde of " + News.num(h.count) + " is near " + s.name, h.x, h.y, A_HORDE);
                callout("Horde sighted", h.x, h.y, C_HORDE);
            }
        }
    }

    private int policeIn(int st) {
        int n = 0;
        for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_POLICE && f.settlement == st) n += w.forces.armedAt(f, Ents.COP);
        for (Squads.Squad q : w.sq.list) if (q.alive && q.faction == Ents.COP && q.home >= 0 && q.home < w.r.facilities.size() && w.r.facilities.get(q.home).settlement == st) n += q.n;
        return n;
    }

    private void watchTowns() {
        Stats s = w.stats;
        List<Region.Settlement> ss = w.r.settlements;
        for (Forces.Formation f : w.forces.formations)
            if (f.alive && f.task >= 0 && f.task < armySent.length) armySent[f.task] = true;
        for (Region.Settlement t : ss) {
            int st = t.id;
            if (t.kind != Region.S_CITY && t.kind != Region.S_TOWN && t.kind != Region.S_VILLAGE) continue;
            if (policeStart[st] < 0) policeStart[st] = policeIn(st);
            int z = s.zBySettlement[st], pop = s.popBySettlement[st];
            if (z > 0) touched[st] = true;
            townPeakZ[st] = Math.max(townPeakZ[st], z);
            if (st < w.mil.armyAsked.length && w.mil.armyAsked[st] > 0) armyAsked[st] = true;
            if (townHow[st] != T_NONE || !touched[st]) continue;
            boolean abandoned = w.command.isAbandoned(st);
            boolean fell = t.residents >= 200 && z >= 50 && z > pop && pop < t.residents * 0.25f;
            if (!abandoned && !fell) continue;
            townHow[st] = abandoned ? T_ABANDONED : T_FELL;
            townAt[st] = (float) w.time;
            townLostAt[st] = s.lostBySettlement[st];
            townWhy[st] = whyFell(t);
            float x = t.cx * 16f, y = t.cy * 16f;
            String head = t.name + (abandoned ? " was abandoned" : " fell");
            float weight = (t.capital ? 200 : t.kind == Region.S_CITY ? 100 : t.kind == Region.S_TOWN ? 40 : 10) + (float) Math.sqrt(t.residents) * 0.1f;
            moment(head + ": " + townWhy[st], x, y, weight);
            alert(head, x, y, A_TOWN);
            callout(t.name + (abandoned ? " abandoned" : " has fallen"), x, y, C_TOWN);
        }
    }

    /** The reasons a town was lost, as read off the world at that moment. */
    String whyFell(Region.Settlement t) {
        int st = t.id;
        List<String> why = new ArrayList<String>();
        int police = policeIn(st), p0 = Math.max(0, policeStart[st]);
        if (p0 > 0 && police < p0 * 0.3f) why.add("lost its police (" + police + " of " + p0 + " left)");
        else if (w.command.isAbandoned(st)) why.add("the police pulled out" + (police > 0 ? " (" + police + " officers)" : ""));
        else if (p0 == 0) why.add("had no police of its own");
        if (!w.command.phonesUp(st)) why.add("the phones were down, so calls didn't get through");
        if (w.time - hordeAt[st] < 240) why.add("a horde of " + News.num(hordeSize[st]) + " arrived");
        if (!armySent[st]) why.add(armyAsked[st] ? "the army was asked for but never came" : "no army was sent");
        if (w.gov.evacuating(st)) why.add("the evacuation didn't get people out in time");
        if (t.grid >= 0 && t.grid < w.strat.gridOn.length && !w.strat.gridOn[t.grid]) why.add("the power was out");
        int z = w.stats.zBySettlement[st], pop = w.stats.popBySettlement[st];
        if (pop <= 0) why.add("nobody was left to hold it");
        else if (z > pop) why.add("the dead outnumbered the living " + Math.max(1, Math.round(z / (float) pop)) + " to 1");
        else if (why.isEmpty()) why.add(News.num(z) + " of the dead got in among the people");
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < why.size(); k++) {
            if (k > 0) sb.append(k == why.size() - 1 ? " and " : ", ");
            sb.append(why.get(k));
        }
        return sb.toString();
    }

    /** Has this town been reached by the dead (so the report says whether it held)? */
    public boolean touched(int st) {
        return st >= 0 && st < touched.length && touched[st];
    }

    private Unit unit(Forces.Formation f) {
        for (Unit u : units) if (u.id == f.id) return u;
        if (units.size() >= 300) return null;
        Unit u = new Unit();
        u.id = f.id;
        u.name = f.name;
        u.faction = f.faction;
        u.start = f.start;
        u.firstAt = (float) w.time;
        units.add(u);
        return u;
    }

    private void watchFormations() {
        for (Forces.Formation f : w.forces.formations) {
            Unit u = unit(f);
            if (u == null || u.how == 2) continue;
            u.kills = f.kills;
            u.losses = f.losses;
            u.start = Math.max(u.start, f.start);
            u.x = f.hq.x;
            u.y = f.hq.y;
            if (!f.alive) {
                u.how = 2;
                u.goneAt = (float) w.time;
                if (u.why.length() == 0) u.why = reasonBroke(f);
                moment(f.name + " was destroyed: " + u.why, u.x, u.y, 70);
                callout(f.name + " destroyed", u.x, u.y, C_TOWN);
            } else if (f.state == Forces.BROKEN && u.how == 0) {
                u.how = 1;
                u.brokeAt = (float) w.time;
                u.why = reasonBroke(f);
                moment(f.name + " broke: " + u.why, u.x, u.y, 45);
                callout(f.name + " broken", u.x, u.y, C_LOSS);
            } else if (f.state != Forces.BROKEN && u.how == 1) {
                // rallied
                u.how = 0;
            }
        }
    }

    private String reasonBroke(Forces.Formation f) {
        List<String> why = new ArrayList<String>();
        if (f.hq.ammo <= 0) why.add("out of ammunition");
        int st = w.sec.settlement[w.r.sectorOfPx(f.hq.x, f.hq.y)];
        for (Strategic.Horde h : w.strat.hordes) {
            if (!h.alive) continue;
            float dx = h.x - f.hq.x, dy = h.y - f.hq.y;
            if (dx * dx + dy * dy < 2500 * 2500) {
                why.add("a horde of " + News.num(h.count) + " came at it");
                break;
            }
        }
        if (st >= 0 && w.stats.zBySettlement[st] > f.start * 20) why.add("outnumbered " + w.stats.zBySettlement[st] / Math.max(1, f.start) + " to 1 in " + w.r.settlements.get(st).name);
        if (f.losses > f.start / 2) why.add("lost " + f.losses + " of " + f.start);
        if (why.isEmpty()) why.add("worn down");
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < why.size(); k++) {
            if (k > 0) sb.append(", ");
            sb.append(why.get(k));
        }
        return sb.toString();
    }

    /** The most decisive moments, up to n, in the order they happened. */
    public List<Moment> decisive(int n) {
        List<Moment> all = new ArrayList<Moment>(moments);
        java.util.Collections.sort(all, new java.util.Comparator<Moment>() {
            public int compare(Moment a, Moment b) {
                return Float.compare(b.weight, a.weight);
            }
        });
        List<Moment> top = new ArrayList<Moment>(all.subList(0, Math.min(n, all.size())));
        java.util.Collections.sort(top, new java.util.Comparator<Moment>() {
            public int compare(Moment a, Moment b) {
                return Float.compare(a.at, b.at);
            }
        });
        return top;
    }

    // ------------------------------------------------------------------ saves

    @Override
    public void save(DataOutputStream o) throws IOException {
        o.writeInt(1); // this record's own version
        int n = townHow.length;
        o.writeInt(n);
        for (int k = 0; k < n; k++) {
            o.writeByte(townHow[k]);
            o.writeFloat(townAt[k]);
            o.writeUTF(townWhy[k] == null ? "" : townWhy[k]);
            o.writeInt(townPeakZ[k]);
            o.writeInt(townLostAt[k]);
            o.writeBoolean(touched[k]);
            o.writeBoolean(armyAsked[k]);
            o.writeBoolean(armySent[k]);
            o.writeInt(policeStart[k]);
            o.writeFloat(hordeAt[k]);
            o.writeInt(hordeSize[k]);
        }
        o.writeInt(units.size());
        for (Unit u : units) {
            o.writeInt(u.id); o.writeInt(u.faction); o.writeInt(u.start); o.writeInt(u.kills); o.writeInt(u.losses);
            o.writeUTF(u.name); o.writeFloat(u.firstAt); o.writeFloat(u.brokeAt); o.writeFloat(u.goneAt);
            o.writeInt(u.how); o.writeUTF(u.why); o.writeFloat(u.x); o.writeFloat(u.y);
        }
        o.writeInt(moments.size());
        for (Moment m : moments) {
            o.writeUTF(m.text); o.writeFloat(m.x); o.writeFloat(m.y); o.writeFloat(m.at); o.writeFloat(m.weight);
        }
        o.writeInt(startPoliceAll);
        o.writeInt(startSoldiersAll);
        o.writeInt(strainSeen);
        o.writeInt(deathWhy.size());
        for (java.util.Map.Entry<Integer, String> en : deathWhy.entrySet()) {
            o.writeInt(en.getKey());
            o.writeUTF(en.getValue());
        }
        Stats s = w.stats;
        o.writeInt(s.tN);
        for (int k = 0; k < s.tN; k++) {
            o.writeInt(s.tPoliceAll[k]);
            o.writeInt(s.tArmyAll[k]);
        }
        o.writeFloat(townT);
        o.writeInt(streetLost);
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        in.readInt();
        int n = in.readInt();
        for (int k = 0; k < n; k++) {
            int how = in.readByte();
            float at = in.readFloat();
            String why = in.readUTF();
            int pz = in.readInt(), la = in.readInt();
            boolean t = in.readBoolean(), aa = in.readBoolean(), as = in.readBoolean();
            int ps = in.readInt();
            float ha = in.readFloat();
            int hs = in.readInt();
            if (k >= townHow.length) continue;
            townHow[k] = how; townAt[k] = at; townWhy[k] = why.length() == 0 ? null : why; townPeakZ[k] = pz; townLostAt[k] = la;
            touched[k] = t; armyAsked[k] = aa; armySent[k] = as; policeStart[k] = ps; hordeAt[k] = ha; hordeSize[k] = hs;
        }
        units.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) {
            Unit u = new Unit();
            u.id = in.readInt(); u.faction = in.readInt(); u.start = in.readInt(); u.kills = in.readInt(); u.losses = in.readInt();
            u.name = in.readUTF(); u.firstAt = in.readFloat(); u.brokeAt = in.readFloat(); u.goneAt = in.readFloat();
            u.how = in.readInt(); u.why = in.readUTF(); u.x = in.readFloat(); u.y = in.readFloat();
            units.add(u);
        }
        moments.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) moments.add(new Moment(in.readUTF(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat()));
        startPoliceAll = in.readInt();
        startSoldiersAll = in.readInt();
        strainSeen = in.readInt();
        deathWhy.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) deathWhy.put(in.readInt(), in.readUTF());
        Stats s = w.stats;
        n = in.readInt();
        for (int k = 0; k < n; k++) {
            int p = in.readInt(), a = in.readInt();
            if (k < Stats.MAXT) {
                s.tPoliceAll[k] = p;
                s.tArmyAll[k] = a;
            }
        }
        // samples the save didn't hold (an older save): unknown
        for (int k = n; k < s.tN; k++) s.tPoliceAll[k] = s.tArmyAll[k] = -1;
        townT = in.readFloat();
        if (in.available() >= 4) streetLost = in.readInt();
        // what is watched for changes starts from what's there now (nothing is announced twice)
        facOn = w.strat.online.clone();
        gridWasOn = w.strat.gridOn.clone();
    }
}
