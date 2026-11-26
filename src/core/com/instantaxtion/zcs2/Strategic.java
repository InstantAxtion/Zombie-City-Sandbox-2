package com.instantaxtion.zcs2;

import java.util.ArrayList;
import java.util.List;

/**
 * The region-wide systems: facilities that can be overrun (power, fuel, hospitals, stations, the base), the
 * power grids and which sectors have light, ammunition stocks, hordes marching across the countryside, refugee
 * columns on the highways, bridges blown to stop the dead, new strains, and each scenario's goal.
 */
public final class Strategic {
    public static final int AMMO_START = 4000;

    public static final class Horde {
        public int id, count, target;
        public float x, y, tx, ty;
        /** Seconds before it rethinks where it's going (after a split). */
        public float keepT;
        /** Seconds before it sets off at all (a later wave). */
        public float waitT;
        public boolean alive = true;
    }

    public static final class Column {
        public int count, infected, from, to = -1, exitNode = -1, ri;
        /** People going back home after an evacuation (from = where they sheltered, to = home). */
        public boolean home;
        /** In cars (refugeeCarSpeed), not on foot (refugeeSpeed): national maps split each column in two. */
        public boolean car;
        public int[] route;
        public float x, y;
        public boolean alive = true;
    }

    final World w;
    public boolean[] online;
    /** Lost to the dead (repair crews can bring these back once it's quiet; not ones out for other reasons). */
    public boolean[] byDead = new boolean[0];
    public float[] overrunT;
    public boolean[] supplyOnWay;
    public final boolean[] gridOn;
    public final List<Horde> hordes = new ArrayList<Horde>();
    public final List<Column> columns = new ArrayList<Column>();
    public int strain;
    /** Where the airliner came down (scenario), or -1. */
    public float crashX = -1, crashY = -1;
    float strainClock;
    /**
     * People who left the country (all told), people who reached a refuge town in it (all told), and people who
     * came back from abroad. Out of the country now: evacuatedOut - backFromAbroad (outBy, per home town).
     */
    public int evacuatedOut, evacuatedArrived, backFromAbroad;
    /** Game seconds since the country was last under threat (nothing reported, no big hordes). */
    public float clearT;
    /**
     * People bitten at the start who don't know it yet (a scenario's hidden spread): {sector, people, game second}.
     * At that time that many of the sector's people fall ill (become the infected, who turn hours later).
     */
    public final List<float[]> latent = new ArrayList<float[]>();

    /** The bitten who didn't know it fall ill, where they are, when their time comes. */
    private void latent() {
        for (int k = latent.size() - 1; k >= 0; k--) {
            float[] l = latent.get(k);
            if (w.time < l[2]) continue;
            latent.remove(k);
            int s = (int) l[0];
            int m = Math.min(w.sec.popIn[s], (int) l[1]);
            w.sec.popIn[w.sec.touch(s)] -= m;
            w.sec.inf[w.sec.touch(s)] += m;
            w.stats.infectedTotal += m;
        }
    }
    final int[] pending;
    /** The bitten among each settlement's people waiting to leave. */
    final int[] infPending;
    /** refuge[a][b]: people of settlement a sheltering in settlement b (counted once, wherever they went). */
    public final int[][] refuge;
    /** People of each settlement who left the country (by road, boat, over the border). */
    public final int[] outBy;
    /** People leaving each settlement in the last hour (so the roads out have a capacity). */
    final float[] leftThisHour;
    float hourT;
    final int[] lostSeen;
    float columnT;
    public float playerJoyX, playerJoyY;
    public boolean playerFire = true;
    public int startTownPop, startCityPop;
    int nextHorde;

    Strategic(World w) {
        this.w = w;
        int nf = w.r.facilities.size();
        online = new boolean[nf];
        byDead = new boolean[nf];
        overrunT = new float[nf];
        supplyOnWay = new boolean[nf];
        for (Region.Facility f : w.r.facilities) {
            online[f.id] = true;
        }
        gridOn = new boolean[Math.max(1, w.r.gridCount)];
        java.util.Arrays.fill(gridOn, true);
        int ns = w.r.settlements.size();
        pending = new int[ns];
        infPending = new int[ns];
        refuge = new int[ns][ns];
        outBy = new int[ns];
        leftThisHour = new float[ns];
        lostSeen = new int[ns];
        for (Region.Settlement s : w.r.settlements) {
            if (s.kind == Region.S_TOWN) startTownPop += s.residents;
            if (s.kind == Region.S_CITY) startCityPop += s.residents;
        }
    }

    // ------------------------------------------------------------------ facilities

    /** Runtime facilities (camps, headquarters, forward bases) were added: make room for them. */
    public void extendFacilities() {
        int nf = w.r.facilities.size();
        if (online.length >= nf) return;
        int old = online.length;
        online = java.util.Arrays.copyOf(online, nf);
        overrunT = java.util.Arrays.copyOf(overrunT, nf);
        supplyOnWay = java.util.Arrays.copyOf(supplyOnWay, nf);
        for (int i = old; i < nf; i++) online[i] = true;
        byDead = java.util.Arrays.copyOf(byDead, nf);
    }

    public boolean online(int f) {
        return f >= 0 && f < online.length && online[f];
    }

    /** Has this sector got electric light? Bases and the prison run their own generators. */
    public boolean lit(int s) {
        int g = w.sec.grid[s];
        if (g >= 0) return gridOn[g];
        int st = w.sec.settlement[s];
        return st >= 0 && (w.r.settlements.get(st).kind == Region.S_BASE || w.r.settlements.get(st).kind == Region.S_PRISON);
    }

    public boolean fuelOk(int facility) {
        if (facility < 0) return true;
        Region.Facility f = w.r.facilities.get(facility);
        if (f.kind == Region.F_BASE) return online[f.id] && f.fuel > 0;
        for (Region.Facility d : w.r.facilities)
            if (d.kind == Region.F_FUEL && d.settlement == f.settlement) return online[d.id] && d.fuel > 0;
        return true;
    }

    public int hospitalSector(int settlement) {
        for (Region.Facility f : w.r.facilities)
            if (f.kind == Region.F_HOSPITAL && f.settlement == settlement && online[f.id]) return w.r.sectorOfPx(f.x, f.y);
        return -1;
    }

    public int drawAmmo(int facility, int need) {
        if (facility < 0 || need <= 0 || !online(facility)) return 0;
        Region.Facility f = w.r.facilities.get(facility);
        int got = (int) Math.min(need, f.ammo);
        f.ammo -= got;
        return got;
    }

    public void deliverAmmo(int facility, int amount) {
        if (facility < 0) return;
        w.r.facilities.get(facility).ammo += amount;
        supplyOnWay[facility] = false;
        Region.Facility f = w.r.facilities.get(facility);
        if (w.stats.supplyRuns <= 3) w.news.radio("Supply truck delivered ammunition to " + f.name, f.x, f.y, News.UNIT);
    }

    private void facilities() {
        Ents e = w.e;
        for (Region.Facility f : w.r.facilities) {
            if (f.kind == Region.F_HELIPAD || f.kind == Region.F_PARK || f.kind == Region.F_HQ) continue;
            int s = w.r.sectorOfPx(f.x, f.y);
            float rad = f.kind == Region.F_BASE ? 500 : 170;
            int z, a;
            if (w.strategic) {
                // as numbers: the dead counted in its sector against whoever defends it (its own people, any
                // formation holding nearby, the town's police)
                z = w.sec.zIn[s];
                a = w.forces.armedAt(f, Ents.COP) + w.forces.armedAt(f, Ents.SOLDIER) + defendersNear(f.x, f.y, 1500);
            } else {
                z = w.sp.zombiesNear(e, f.x, f.y, rad, 40) + Math.min(60, w.sec.zIn[s] / 8);
                a = w.sp.livingNear(e, f.x, f.y, rad + 60, 40) - w.sp.secCiv[s] / 4 + w.forces.armedAt(f, Ents.COP) + w.forces.armedAt(f, Ents.SOLDIER);
            }
            float need = w.strategic ? (f.kind == Region.F_BASE ? 600 : 150) : (f.kind == Region.F_BASE ? 30 : 10);
            if (online[f.id]) {
                // overrun: real numbers of the dead at the gates, more than the defenders can hold, for a good while
                if (z >= need && a < z / 4) {
                    overrunT[f.id] += dt;
                    if (overrunT[f.id] > (w.strategic ? 60 : 20)) {
                        online[f.id] = false;
                        overrunT[f.id] = 0;
                        if (byDead.length > f.id) byDead[f.id] = true;
                        lost(f);
                    }
                } else overrunT[f.id] = Math.max(0, overrunT[f.id] - dt);
            } else {
                // retaken: the dead gone from round it, and someone there to take it back (soldiers or police near, or
                // the town's own people and repair crews once it's been quiet a few hours)
                int zNear = z + (w.strategic ? 0 : w.sec.zIn[s]);
                int t = f.settlement;
                boolean crews = f.id < byDead.length && byDead[f.id] && t >= 0 && w.stats.popBySettlement[t] > 200 && !w.command.isAbandoned(t) && w.stats.zBySettlement[t] < 20;
                boolean troops = (w.strategic ? defendersNear(f.x, f.y, 2000) : w.sp.secCop[s] + w.sp.secSol[s]) >= 3;
                if (zNear == 0 && (troops || crews)) {
                    overrunT[f.id] += dt;
                    if (overrunT[f.id] > (troops ? 120 : 360)) {
                        online[f.id] = true;
                        overrunT[f.id] = 0;
                        byDead[f.id] = false;
                        if (f.kind == Region.F_POWER && f.grid >= 0) {
                            gridOn[f.grid] = true;
                            w.news.headline("Power restored to " + gridName(f.grid), f.x, f.y);
                            w.gov.trust += 3;
                        } else w.news.count("retaken", f.name + " is back in our hands|%d more place{s} retaken and working again", 1, f.x, f.y);
                    }
                } else overrunT[f.id] = 0;
            }
        }
    }

    /** Armed people of formations holding (not on the move) near a point. */
    private int defendersNear(float x, float y, float reach) {
        int n = 0;
        for (Forces.Formation fm : w.forces.formations) {
            if (!fm.alive || Forces.travelling(fm)) continue;
            float dx = fm.hq.x - x, dy = fm.hq.y - y;
            if (dx * dx + dy * dy < reach * reach) n += w.forces.armedAt(fm.hq, fm.faction);
        }
        return n;
    }

    private void lost(Region.Facility f) {
        w.stats.facilitiesLost++;
        switch (f.kind) {
            case Region.F_POWER:
                if (f.grid >= 0) gridOn[f.grid] = false;
                w.news.headline("BLACKOUT: " + f.name + " overrun. " + gridName(f.grid) + " goes dark.", f.x, f.y);
                w.gov.trust -= 8;
                break;
            case Region.F_HOSPITAL:
                w.news.headline(f.name + " overrun: the sick inside have turned", f.x, f.y);
                w.gov.trust -= 6;
                break;
            case Region.F_BASE:
                w.news.headline(f.name + " has fallen", f.x, f.y);
                w.gov.trust -= 15;
                break;
            case Region.F_FUEL:
                w.news.radio(f.name + " lost: vehicles there can't refuel", f.x, f.y, News.LOSS);
                break;
            case Region.F_LAB:
                break;
            default:
                w.news.tally("overrun:" + f.settlement, f.name + " overrun|More places lost" + (f.settlement >= 0 ? " in " + w.r.settlements.get(f.settlement).name : "") + ": {n}", 1, 0, f.x, f.y, News.LOSS);
        }
    }

    public String gridName(int g) {
        StringBuilder sb = new StringBuilder();
        for (Region.Settlement s : w.r.settlements)
            if (s.grid == g && s.kind != Region.S_VILLAGE) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(s.name);
            }
        return sb.length() == 0 ? "the area" : sb.toString();
    }

    // ------------------------------------------------------------------ hordes

    public int hordeTotal() {
        int t = 0;
        for (Horde h : hordes) if (h.alive) t += h.count;
        return t;
    }

    public Horde biggestHorde() {
        Horde b = null;
        for (Horde h : hordes) if (h.alive && (b == null || h.count > b.count)) b = h;
        return b;
    }

    /** A mass of the dead in open country gets moving as a horde. */
    public Horde formHorde(int s, int count) {
        if (count <= 0) return null;
        count = Math.min(count, w.sec.zIn[s]);
        w.sec.zIn[s] -= count;
        Horde h = new Horde();
        h.id = nextHorde++;
        h.count = count;
        h.x = w.sec.cx(s);
        h.y = w.sec.cy(s);
        h.target = -1;
        hordes.add(h);
        w.ecology.hordeFormed(h, s);
        if (count >= 500) w.news.tally("horde", "Recon: a horde of about " + News.num(round(count)) + " is on the move|Recon: {n} more horde{s} on the move, about {m} of the dead", 1, count, h.x, h.y, News.COMMAND);
        return h;
    }

    /** Places a horde directly (scenarios, the player). */
    public Horde addHorde(float x, float y, int count) {
        Horde h = new Horde();
        h.id = nextHorde++;
        h.count = count;
        h.x = x;
        h.y = y;
        h.target = -1;
        hordes.add(h);
        return h;
    }

    private static int round(int n) {
        if (n > 1000) return n / 100 * 100;
        return n / 10 * 10;
    }

    private void hordes() {
        Sectors sec = w.sec;
        // (a horde passes through streets already overrun, a few holdouts among thousands of the dead)
        float overrun = w.rule("overrunRatio", 20);
        // (a national map: the few people in a field neither stop a horde nor draw it; 0 on today's maps)
        float arriveAt = w.rule("hordeArrivePeople", 0);
        for (int k = 0; k < hordes.size(); k++) {
            Horde h = hordes.get(k);
            if (!h.alive) continue;
            if (h.count <= 0) {
                h.alive = false;
                continue;
            }
            int s = w.r.sectorOfPx(h.x, h.y);
            // pick up stragglers on the way
            if (sec.zIn[s] > 0 && sec.popIn[s] == 0 && sec.hot[s] <= 0) {
                h.count += sec.zIn[s];
                sec.zIn[s] = 0;
            }
            // a horde on the march (keepT) goes through farms and villages, taking some of the people as it passes
            boolean marching = h.keepT > 0 && h.target >= 0 && s != h.target;
            int stl = sec.settlement[s];
            boolean town = stl >= 0 && (w.r.settlements.get(stl).kind == Region.S_CITY || w.r.settlements.get(stl).kind == Region.S_TOWN);
            // (a national map has people in nearly every field: a horde walks on through the thinly lived-in
            // countryside, taking some as it passes, and breaks up where it reaches a place with people in numbers)
            if (!marching && !town && sec.popIn[s] > 0 && sec.popIn[s] < arriveAt && sec.hot[s] <= 0 && !sec.cam[s]) marching = true;
            if (marching && !town && sec.popIn[s] > 0 && sec.hot[s] <= 0 && !sec.cam[s]) {
                int bitten = Math.min(sec.popIn[s], (int) (sec.popIn[s] * 0.02f * dt));
                sec.popIn[sec.touch(s)] -= bitten;
                sec.inf[sec.touch(s)] += bitten;
                w.stats.infectedTotal += bitten;
                if (stl >= 0) w.stats.lostBySettlement[stl] += bitten;
                w.stats.lossBy[2] += bitten;
            } else if (sec.popIn[s] > 0 && sec.zIn[s] < sec.popIn[s] * overrun || sec.hot[s] > 0 || sec.cam[s] || w.sp.secCiv[s] + w.sp.secCop[s] + w.sp.secSol[s] > 0) {
                // reached people, a fight, or a watched place: break up into the sector
                w.ecology.hordeArrived(h, s, sec.zIn[s]);
                // (watched: its front comes on as individuals, the rest is the mass behind them)
                sec.zIn[sec.touch(s)] += h.count - w.undead.watchedHorde(h, s);
                sec.hot[sec.touch(s)] = Math.max(sec.hot[s], 10);
                h.alive = false;
                continue;
            }
            if (h.waitT > 0) {
                h.waitT -= dt;
                continue;
            }
            // choose (and now and then rethink) where to go: the most attractive place within reach
            if (h.keepT > 0) h.keepT -= dt;
            int skip = -1;
            if (arriveAt > 0 && h.target >= 0 && s == h.target) {
                // (a national map: there, and nobody in numbers to stop it: it looks again at once, somewhere else)
                skip = s;
                h.target = -1;
                h.keepT = 0;
            }
            if (h.target < 0 || (w.tick % 600 == 0 && h.keepT <= 0)) {
                float best = 0, sense = w.rule("hordeSense", 12288) / (Tile.SIZE * w.r.sect);
                int sx = s % sec.sw, sy = s / sec.sw;
                // (within 24 sectors: the box round it, in map order, skipping districts nobody is in)
                int y0 = Math.max(0, sy - 24), y1 = Math.min(sec.sh - 1, sy + 24), x0 = Math.max(0, sx - 24), x1 = Math.min(sec.sw - 1, sx + 24);
                for (int yy = y0; yy <= y1; yy++) for (int xx = x0; xx <= x1; xx++) {
                    int t = yy * sec.sw + xx;
                    if (!sec.idx.mayLive(t)) {
                        xx |= SectorIndex.D - 1;
                        continue;
                    }
                    if (sec.popIn[t] == 0 && w.sp.secCiv[t] == 0) continue;
                    if (sec.popIn[t] < arriveAt && w.sp.secCiv[t] == 0 || t == skip) continue; // (a national map: not the fields' few)
                    int dx = t % sec.sw - sx, dy = t / sec.sw - sy;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    if (d > sense) continue;
                    float a = (sec.popIn[t] + 50 + (w.night() && lit(t) ? 200 : 0)) / (1 + d * d);
                    // (streets the dead already crowd draw them less: they go where the people are still many)
                    a /= 1 + sec.zIn[t] / (sec.popIn[t] + 50f);
                    if (a > best) {
                        best = a;
                        h.target = t;
                    }
                }
                if (h.target < 0 && arriveAt > 0) {
                    // a national map, nobody in numbers within reach: towards the nearest town's lights
                    float bdd = Float.MAX_VALUE;
                    for (Region.Settlement st : w.r.settlements) {
                        if (st.kind != Region.S_CITY && st.kind != Region.S_TOWN) continue;
                        // (not a town already emptied, nor the one it's standing in the middle of)
                        if (w.stats.popBySettlement[st.id] < arriveAt || w.r.sectorOfPx(st.cx * 16f, st.cy * 16f) == skip) continue;
                        float dd = dist(st.cx * 16f, st.cy * 16f, h.x, h.y);
                        if (dd < bdd) {
                            bdd = dd;
                            h.target = w.r.sectorOfPx(st.cx * 16f, st.cy * 16f);
                        }
                    }
                }
                if (h.target >= 0) {
                    h.tx = sec.cx(h.target);
                    h.ty = sec.cy(h.target);
                }
            }
            if (h.target < 0) continue;
            // walk sector by sector, only where the land connects (rivers stop them where bridges are gone)
            // (preferring easy ground: valleys, roads, rivers' banks; avoiding forest and mountains)
            int bd = -1;
            float here = dist(sec.cx(s), sec.cy(s), h.tx, h.ty), bscore = Float.MAX_VALUE;
            for (int d = 0; d < 8; d++) {
                if ((sec.connect[s] & (1 << d)) == 0) continue;
                int t = sec.neighbour(s, d);
                float dd = dist(sec.cx(t), sec.cy(t), h.tx, h.ty);
                if (dd >= here - 1) continue;
                float score = w.ecology.stepScore(t, dd);
                if (score < bscore) {
                    bscore = score;
                    bd = t;
                }
            }
            float gx = bd >= 0 ? sec.cx(bd) : h.tx, gy = bd >= 0 ? sec.cy(bd) : h.ty;
            if (bd < 0 && s != h.target) {
                // stuck at water: give up on that target for now
                h.target = -1;
                continue;
            }
            float dx = gx - h.x, dy = gy - h.y, d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
            float speed = w.rule("hordeSpeed", 11) * dt * w.weather.zombieSpeed();
            h.x += dx / d * Math.min(d, speed);
            h.y += dy / d * Math.min(d, speed);
        }
        // merge hordes that meet
        for (int a = 0; a < hordes.size(); a++) {
            Horde A = hordes.get(a);
            if (!A.alive) continue;
            for (int b = a + 1; b < hordes.size(); b++) {
                Horde B = hordes.get(b);
                if (B.alive && dist(A.x, A.y, B.x, B.y) < w.rule("hordeMerge", 200) && (A.keepT <= 0 || A.target == B.target)) {
                    w.ecology.hordesMerged(A, B);
                    A.count += B.count;
                    B.alive = false;
                }
            }
        }
        for (int k = hordes.size() - 1; k >= 0; k--) if (!hordes.get(k).alive) hordes.remove(k);
    }

    /** A blast catches part of any horde under it. */
    void hordeBlast(float x, float y, float rad) {
        for (Horde h : hordes) {
            if (!h.alive) continue;
            float d = dist(h.x, h.y, x, y);
            float spread = 120 + (float) Math.sqrt(h.count) * 6;
            if (d > spread + rad) continue;
            int killed = Math.min(h.count, (int) (h.count * Math.min(0.35f, rad * rad / (spread * spread * 3)) + rad / 4));
            h.count -= killed;
            w.stats.killsBySide[2] += killed;
            w.stats.zombieKills += killed;
        }
    }

    private static float dist(float ax, float ay, float bx, float by) {
        float dx = ax - bx, dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // ------------------------------------------------------------------ evacuation

    public int refugeeTotal() {
        int t = 0;
        for (Column c : columns) if (c.alive) t += c.count;
        for (int p : pending) t += p;
        return t;
    }

    /** People of a settlement who left the country altogether (-1: nobody knows where from; they come back to the capital). */
    void leftCountry(int st, int k) {
        evacuatedOut += k;
        if (st < 0 || st >= outBy.length) st = w.r.city().id;
        outBy[st] += k;
    }

    /** People out of the country now (they come home as it recovers). */
    public int abroad() {
        return evacuatedOut - backFromAbroad;
    }

    /** Is the country still under threat? While it is, people leave it; once it's over they stop, and come back. */
    public boolean threatened() {
        return clearT < 360;
    }

    /** Once a second: how long the country has been out of danger. */
    private void threat(float dt) {
        boolean danger = w.command.nationalReported() >= 30 || hordeTotal() >= 300 || w.gov.level >= 3;
        clearT = danger ? 0 : clearT + dt;
    }

    /** People of settlement a, sheltering elsewhere, out of the country, or on the road: safe from a's fate. */
    public int awayFrom(int a) {
        int n = outBy[a] + pending[a];
        for (int b = 0; b < refuge.length; b++) n += refuge[a][b];
        for (Column c : columns) if (c.alive && !c.home && c.from == a) n += c.count;
        return n;
    }

    /** Has settlement b taken (or got on the way to it) all the refugees it has room for? */
    public boolean full(int b) {
        int n = hosted(b);
        for (Column c : columns) if (c.alive && !c.home && c.to == b) n += c.count;
        for (Logistics.Convoy c : w.logistics.convoys) if (c.alive && c.refugees > 0 && c.toSettlement == b) n += c.refugees;
        return n > w.r.settlements.get(b).residents * w.c.rule("hostShare", 0.5f);
    }

    /** People sheltering in settlement b who belong elsewhere. */
    public int hosted(int b) {
        int n = 0;
        for (int a = 0; a < refuge.length; a++) n += refuge[a][b];
        return n;
    }

    /** Refugees arrived (a column or a train): counted once in refuge[from][to]. */
    void arrivedFrom(int from, int to, int healthy, int infected) {
        if (from >= 0 && from < refuge.length && to >= 0 && to != from) refuge[from][to] += healthy;
        evacuatedArrived += healthy + infected;
    }

    /** Where people of a settlement go to be evacuated: the edge junction towards the safest place. */
    public float[] evacPoint(int settlement) {
        Region.Settlement s = w.r.settlements.get(settlement);
        int dest = safest(settlement);
        float tx, ty;
        if (dest >= 0) {
            tx = w.r.settlements.get(dest).cx * 16f;
            ty = w.r.settlements.get(dest).cy * 16f;
        } else if (!w.r.entryNodes.isEmpty()) {
            int en = w.r.entryNodes.get(0);
            tx = w.r.roads.nx[en];
            ty = w.r.roads.ny[en];
        } else {
            tx = s.cx * 16f;
            ty = s.cy * 16f;
        }
        int best = s.hubNode;
        float bd = Float.MAX_VALUE;
        for (int n : s.edgeNodes) {
            float d = dist(w.r.roads.nx[n], w.r.roads.ny[n], tx, ty);
            if (d < bd) {
                bd = d;
                best = n;
            }
        }
        if (best < 0) return new float[]{s.cx * 16f, s.cy * 16f};
        return new float[]{w.r.roads.nx[best], w.r.roads.ny[best]};
    }

    /** The settlement with the fewest of the dead per person and room to take people, not this one; -1 = out of the region. */
    private int safest(int from) {
        int best = -1;
        float bs = Float.MAX_VALUE;
        int[] z = w.stats.zBySettlement;
        float room = w.c.rule("hostShare", 0.5f);
        for (Region.Settlement s : w.r.settlements) {
            if (s.id == from || (s.kind != Region.S_CITY && s.kind != Region.S_TOWN) || w.gov.evacuating(s.id) || w.command.quarantined(s.id)) continue;
            if (z[s.id] > 50) continue;
            if (full(s.id)) continue;
            float score = z[s.id] * 10f / (s.residents + 100) + dist(s.cx, s.cy, w.r.settlements.get(from).cx, w.r.settlements.get(from).cy) / 400f
                    + hosted(s.id) / (s.residents * room + 1f) * 3;
            if (score < bs) {
                bs = score;
                best = s.id;
            }
        }
        return best;
    }

    /**
     * Is a town's road out cut: a real number of the dead (in the streets round its evacuation point, or a horde
     * close by) and no army formation near enough to hold the road open?
     */
    public boolean corridorCut(int st) {
        float[] p = evacPoint(st);
        Sectors sec = w.sec;
        int s0 = w.r.sectorOfPx(p[0], p[1]), sx = s0 % sec.sw, sy = s0 / sec.sw, z = 0;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                int x = sx + dx, y = sy + dy;
                if (x < 0 || y < 0 || x >= sec.sw || y >= sec.sh) continue;
                z += sec.zIn[y * sec.sw + x] + w.sp.secZ[y * sec.sw + x];
            }
        for (Horde h : hordes) if (h.alive && dist(h.x, h.y, p[0], p[1]) < 2000) z += h.count;
        if (z < w.c.rule("corridorCutAt", 150)) return false;
        return !w.forces.escortNear(p[0], p[1], w.c.rule("escortReach", 2000));
    }

    /** People sent out of a town by road (a precinct evacuating its district): they leave in its next column. */
    public void sendOut(int st, int n) {
        if (st >= 0 && st < pending.length && n > 0) pending[st] += n;
    }

    /** One person reached the evacuation point. */
    void evacuee(int i) {
        int st = w.sec.settlement[w.r.sectorOfPx(w.e.x[i], w.e.y[i])];
        if (st < 0) {
            Region.Settlement near = null;
            float bd = Float.MAX_VALUE;
            for (Region.Settlement s : w.r.settlements) {
                float d = dist(s.cx * 16f, s.cy * 16f, w.e.x[i], w.e.y[i]);
                if (d < bd) {
                    bd = d;
                    near = s;
                }
            }
            st = near == null ? 0 : near.id;
        }
        if ((w.e.flags[i] & Ents.F_INFECTED) != 0) infPending[st]++;
        pending[st]++;
        w.retire(i);
    }

    /**
     * People queue on the roads out at this rate (a game hour), more for the big cities. On a national map, so many
     * for each highway out of the town (evacPerHourPerExit; half that for a town with none).
     */
    private float roadsOut(Region.Settlement s) {
        float perExit = w.rule("evacPerHourPerExit", 0);
        if (perExit > 0) return perExit * exits(s.id) * (0.5f + w.gov.trust / 100f);
        return w.c.rule(s.kind == Region.S_CITY ? "evacPerHourCity" : "evacPerHourTown", s.kind == Region.S_CITY ? 9000 : 2500) * (0.5f + w.gov.trust / 100f);
    }

    private float[] exits;

    /**
     * Highways out of a settlement (each crossing from its sectors to others'), at least 0.5 for a town reached by
     * lesser roads only. Worked out once, from the road graph.
     */
    public float exits(int st) {
        if (exits == null) {
            float[] e = new float[w.r.settlements.size()];
            RoadGraph g = w.r.roads;
            Sectors sec = w.sec;
            for (int k = 0; k < g.edgeCount; k++) {
                int a = sec.settlement[w.r.sectorOfPx(g.nx[g.ea[k]], g.ny[g.ea[k]])], b = sec.settlement[w.r.sectorOfPx(g.nx[g.eb[k]], g.ny[g.eb[k]])];
                if (a == b || g.ekind[k] != RoadGraph.HIGHWAY) continue;
                if (a >= 0) e[a]++;
                if (b >= 0) e[b]++;
            }
            for (int k = 0; k < e.length; k++) e[k] = Math.max(0.5f, e[k]);
            exits = e;
        }
        return exits[st];
    }

    /** A column sets off; on a national map those with cars (refugeeCarShare) go as a column of their own. */
    private void launch(Column c) {
        float share = w.rule("refugeeCarShare", 0);
        int cars = (int) (c.count * share);
        if (cars > 0 && cars < c.count) {
            Column k = new Column();
            k.car = true;
            k.count = cars;
            k.infected = (int) ((long) c.infected * cars / c.count);
            k.from = c.from;
            k.to = c.to;
            k.exitNode = c.exitNode;
            k.home = c.home;
            k.route = c.route;
            k.x = c.x;
            k.y = c.y;
            c.count -= k.count;
            c.infected -= k.infected;
            columns.add(k);
        }
        columns.add(c);
    }

    private void evacuation() {
        Sectors sec = w.sec;
        hourT += dt;
        if (hourT >= 60) {
            hourT -= 60;
            java.util.Arrays.fill(leftThisHour, 0);
            // refugees in a town that's being lost are lost with it
            for (int b = 0; b < refuge.length; b++) {
                int lost = w.stats.lostBySettlement[b] - lostSeen[b];
                lostSeen[b] = w.stats.lostBySettlement[b];
                if (lost <= 0) continue;
                float frac = Math.min(1, lost / (float) Math.max(1, w.stats.popBySettlement[b] + lost));
                for (int a = 0; a < refuge.length; a++) refuge[a][b] -= (int) (refuge[a][b] * frac);
            }
        }
        // people leave at the rate the roads out can take them
        int ns = pending.length;
        for (int st = 0; st < ns; st++) {
            if (!w.gov.evacuating(st)) continue;
            Region.Settlement set = w.r.settlements.get(st);
            float cap = roadsOut(set) / 60f * dt;
            // the dead on the road out, and no soldiers holding it: only a trickle gets through
            if (corridorCut(st)) cap *= 0.15f;
            float left = Math.max(0, Math.min(cap, roadsOut(set) - leftThisHour[st]));
            if (left <= 0) continue;
            int popNow = Math.max(1, w.stats.popBySettlement[st]);
            float share = Math.min(0.02f * dt, left / popNow);
            int moved = 0;
            for (int s : sec.idx.bySettlement[st]) {
                if (sec.popIn[s] == 0 || sec.zIn[s] > 0) continue;
                float want = sec.popIn[s] * share;
                int m = Math.min(sec.popIn[s], (int) want + (Rng.unit(w.seed, w.tick, s) < want % 1f ? 1 : 0));
                sec.popIn[sec.touch(s)] -= m;
                moved += m;
                // (a precinct's district counts its people who got out as safe)
                if (m > 0 && w.roles.inDistrict(s)) w.roles.districtOut += m;
            }
            pending[st] += moved;
            leftThisHour[st] += moved;
            // the refugees this town was sheltering go on with them (their count moves to this town's)
            if (moved > 0) for (int a = 0; a < ns; a++) refuge[a][st] -= (int) ((long) refuge[a][st] * moved / popNow);
        }
        columnT -= dt;
        if (columnT > 0) return;
        columnT = 30;
        for (int st = 0; st < pending.length; st++) {
            if (pending[st] <= 0) continue;
            if (w.command.quarantined(st)) {
                // turned back at the checkpoints
                Region.Settlement qs = w.r.settlements.get(st);
                int back = qs.hubNode >= 0 ? w.r.sectorOfPx(w.r.roads.nx[qs.hubNode], w.r.roads.ny[qs.hubNode]) : w.r.sectorOfPx(qs.cx * 16f, qs.cy * 16f);
                int inf = Math.min(pending[st], infPending[st]);
                infPending[st] -= inf;
                sec.popIn[sec.touch(back)] += pending[st] - inf;
                sec.inf[sec.touch(back)] += inf;
                w.stats.turnedBack += pending[st];
                pending[st] = 0;
                continue;
            }
            int dest = safest(st);
            if (dest < 0 && !threatened() && !w.infra.waterOut(st) && w.infra.shortage[st] < 2) {
                // nowhere in the country to go, and nothing to run from any more: they stay (go back indoors)
                int inf = Math.min(pending[st], infPending[st]);
                infPending[st] -= inf;
                int hs = home(st);
                if (hs >= 0) {
                    sec.popIn[sec.touch(hs)] += pending[st] - inf;
                    sec.inf[sec.touch(hs)] += inf;
                    pending[st] = 0;
                    continue;
                }
            }
            Column c = new Column();
            c.count = pending[st];
            c.infected = Math.min(c.count, infPending[st]);
            infPending[st] -= c.infected;
            c.from = st;
            pending[st] = 0;
            float[] p = evacPoint(st);
            int a = w.r.roads.nearestNode(p[0], p[1], 600);
            int b;
            if (dest >= 0) {
                c.to = dest;
                b = w.r.settlements.get(dest).hubNode;
            } else {
                b = w.r.entryNodes.isEmpty() ? -1 : w.r.entryNodes.get(Rng.hashInt(w.seed, w.tick, st, w.r.entryNodes.size()));
                c.exitNode = b;
            }
            c.route = a >= 0 && b >= 0 ? w.r.roads.route(a, b) : null;
            if (c.route == null) {
                // nowhere to go: they go back indoors
                int s = w.r.sectorOfPx(p[0], p[1]);
                sec.popIn[sec.touch(s)] += c.count - c.infected;
                sec.inf[sec.touch(s)] += c.infected;
                continue;
            }
            c.x = w.r.roads.nx[c.route[0]];
            c.y = w.r.roads.ny[c.route[0]];
            launch(c);
        }
        homecoming();
        fromAbroad();
    }

    /**
     * After an evacuation is called off and the town has been quiet a while, its people come home from wherever
     * they sheltered, a column at a time.
     */
    private void homecoming() {
        int ns = refuge.length;
        for (int a = 0; a < ns; a++) {
            if (w.gov.evacuating(a) || w.command.quarantined(a) || w.stats.zBySettlement[a] > 0 || !w.command.calm(a, 720)) continue;
            Region.Settlement home = w.r.settlements.get(a);
            if (home.hubNode < 0) continue;
            for (int b = 0; b < ns; b++) {
                if (refuge[a][b] <= 0 || b == a) continue;
                Region.Settlement host = w.r.settlements.get(b);
                int n = Math.min(refuge[a][b], (int) (roadsOut(host) * 0.25f));
                // take them from the host town's streets
                Sectors sec = w.sec;
                long total = 0;
                int[] bl = sec.idx.bySettlement[b];
                for (int s : bl) if (sec.zIn[s] == 0) total += sec.popIn[s];
                if (total <= 0) {
                    refuge[a][b] = 0;
                    continue;
                }
                n = (int) Math.min(n, total);
                int got = 0;
                for (int j = 0; j < bl.length && got < n; j++) {
                    int s = bl[j];
                    if (sec.zIn[s] > 0 || sec.popIn[s] == 0) continue;
                    int m = (int) Math.min(sec.popIn[s], Math.max(1, (long) sec.popIn[s] * n / total));
                    m = Math.min(m, n - got);
                    sec.popIn[sec.touch(s)] -= m;
                    got += m;
                }
                if (got <= 0) continue;
                int from = host.hubNode >= 0 ? host.hubNode : w.r.roads.nearestNode(host.cx * 16f, host.cy * 16f, w.reach(1500));
                int[] rt = from >= 0 ? w.r.roads.route(from, home.hubNode) : null;
                if (rt == null) {
                    settleHome(a, got);
                    refuge[a][b] -= got;
                    continue;
                }
                refuge[a][b] -= got;
                Column c = new Column();
                c.count = got;
                c.from = b;
                c.to = a;
                c.home = true;
                c.route = rt;
                c.x = w.r.roads.nx[rt[0]];
                c.y = w.r.roads.ny[rt[0]];
                launch(c);
                if (got >= 100) w.news.tally("home:" + a, "People are going home to " + home.name + "|Going home to " + home.name + ": {n} more column{s}, {m} {mpeople}", 1, got, home.cx * 16f, home.cy * 16f, News.CALL);
            }
        }
    }

    /** A sector of the settlement where people live (its hub's, or any), or -1. */
    private int home(int st) {
        Region.Settlement s = w.r.settlements.get(st);
        int best = -1;
        for (int k : w.sec.idx.bySettlement[st])
            if (w.sec.zIn[k] == 0 && (best < 0 || w.sec.home[k] > w.sec.home[best])) best = k;
        if (best < 0) best = s.hubNode >= 0 ? w.r.sectorOfPx(w.r.roads.nx[s.hubNode], w.r.roads.ny[s.hubNode]) : w.r.sectorOfPx(s.cx * 16f, s.cy * 16f);
        return best;
    }

    /**
     * Once the danger is over, people who left the country come back: columns from the border to their home
     * towns, as fast as the roads can take them.
     */
    private void fromAbroad() {
        if (threatened() || clearT < 720 || w.r.entryNodes.isEmpty()) return;
        int ns = outBy.length;
        for (int a = 0; a < ns; a++) {
            if (outBy[a] <= 0 || w.gov.evacuating(a) || w.command.quarantined(a) || w.stats.zBySettlement[a] > 0) continue;
            if (w.infra.waterOut(a) || w.infra.shortage[a] >= 2) continue;
            Region.Settlement home = w.r.settlements.get(a);
            if (home.hubNode < 0) {
                backFromAbroad += outBy[a];
                settleHome(a, outBy[a]);
                outBy[a] = 0;
                continue;
            }
            int n = Math.min(outBy[a], Math.max(100, (int) (roadsOut(home) * 0.25f)));
            // over the border nearest home
            int entry = -1;
            float bd = Float.MAX_VALUE;
            for (int en : w.r.entryNodes) {
                float d = dist(w.r.roads.nx[en], w.r.roads.ny[en], home.cx * 16f, home.cy * 16f);
                if (d < bd) {
                    bd = d;
                    entry = en;
                }
            }
            int[] rt = w.r.roads.route(entry, home.hubNode);
            outBy[a] -= n;
            backFromAbroad += n;
            if (rt == null) {
                settleHome(a, n);
                continue;
            }
            Column c = new Column();
            c.count = n;
            c.from = -1;
            c.to = a;
            c.home = true;
            c.route = rt;
            c.x = w.r.roads.nx[rt[0]];
            c.y = w.r.roads.ny[rt[0]];
            launch(c);
            if (n >= 100) w.news.tally("abroad:" + a, "People who fled abroad are coming home to " + home.name + "|Back from abroad to " + home.name + ": {n} more column{s}, {m} {mpeople}", 1, n, home.cx * 16f, home.cy * 16f, News.CALL);
        }
    }

    /** People back home: into their own streets, where they lived. */
    private void settleHome(int st, int count) {
        Sectors sec = w.sec;
        long room = 0;
        int[] sl = sec.idx.bySettlement[st];
        for (int s : sl) if (sec.zIn[s] == 0) room += Math.max(1, sec.home[s] - sec.popIn[s]);
        if (room <= 0) {
            List<Integer> any = new ArrayList<Integer>();
            for (int s : sl) any.add(s);
            if (any.isEmpty()) {
                leftCountry(st, count);
                return;
            }
            sec.popIn[sec.touch(any.get(0))] += count;
        } else {
            int put = 0;
            int last = -1;
            for (int s : sl) {
                if (sec.zIn[s] > 0) continue;
                int m = (int) ((long) count * Math.max(1, sec.home[s] - sec.popIn[s]) / room);
                sec.popIn[sec.touch(s)] += m;
                put += m;
                last = s;
            }
            if (last >= 0) sec.popIn[sec.touch(last)] += count - put;
        }
        w.stats.returnedHome += count;
    }

    private void moveColumns() {
        RoadGraph g = w.r.roads;
        for (int k = 0; k < columns.size(); k++) {
            Column c = columns.get(k);
            if (!c.alive) continue;
            if (!c.home && !threatened() && c.from >= 0 && c.route != null && c.ri < c.route.length - 1 && (c.to < 0 || w.command.calm(c.from, 720))
                    && !w.infra.waterOut(c.from) && w.infra.shortage[c.from] < 2) {
                // the danger is over: the column turns round and goes home
                int at = c.route[c.ri + 1 < c.route.length ? c.ri : c.route.length - 1];
                Region.Settlement home = w.r.settlements.get(c.from);
                int[] back = home.hubNode >= 0 ? w.r.roads.route(at, home.hubNode) : null;
                if (back != null) {
                    c.route = back;
                    c.ri = 0;
                    c.x = w.r.roads.nx[back[0]];
                    c.y = w.r.roads.ny[back[0]];
                    c.to = c.from;
                    c.home = true;
                    w.news.count("turnHome:" + c.from, "A refugee column turns round and heads home to " + home.name + "|%d more refugee column{s} turned round for home", 1, c.x, c.y);
                }
            }
            float budget = w.rule(c.car ? "refugeeCarSpeed" : "refugeeSpeed", 28) * dt; // px per second: on foot, or cars and buses
            while (budget > 0 && c.ri < c.route.length - 1) {
                int nn = c.route[c.ri + 1];
                int e = g.edgeBetween(c.route[c.ri], nn);
                if (e >= 0 && !g.ealive[e]) {
                    // the bridge is gone: turn back into the nearest town
                    c.route = null;
                    break;
                }
                float dx = g.nx[nn] - c.x, dy = g.ny[nn] - c.y, d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d <= budget) {
                    c.x = g.nx[nn];
                    c.y = g.ny[nn];
                    c.ri++;
                    budget -= d;
                } else {
                    c.x += dx / d * budget;
                    c.y += dy / d * budget;
                    budget = 0;
                }
            }
            int s = w.r.sectorOfPx(c.x, c.y);
            // soldiers holding the road nearby (or riding with it) keep most of the dead off it
            boolean escorted = w.forces.escortNear(c.x, c.y, w.c.rule("escortReach", 2000));
            // the dead on the road
            int z = w.sp.secZ[s] + w.sec.zIn[s];
            // a horde across its road falls on it (the column can't outrun the dead on a jammed road)
            for (Horde h : hordes) {
                if (!h.alive || dist(h.x, h.y, c.x, c.y) > 600 + (float) Math.sqrt(h.count) * 3) continue;
                z += h.count;
            }
            if (escorted && z > 0) {
                // the escort fights them off
                int kd = Math.min(w.sec.zIn[s], (int) (Math.min(z, 400) * 0.01f * dt) + 1);
                w.sec.zIn[s] -= kd;
                w.sec.bodies[s] += kd;
                w.stats.killsBySide[2] += kd;
                w.stats.zombieKills += kd;
            }
            if (z >= 20) {
                // the dead on the road catch some of them (a column keeps moving: only those at its edges)
                float want = Math.min(z, c.count) * 0.002f * dt * (escorted ? 0.1f : 1);
                int lost = Math.min(c.count, (int) want + (Rng.unit(w.seed, w.tick, k * 3L + 2) < want % 1f ? 1 : 0));
                c.count -= lost;
                int hadInf = Math.min(lost, c.infected);
                c.infected -= hadInf;
                w.sec.inf[w.sec.touch(s)] += lost;
                w.stats.infectedTotal += lost - hadInf;
                if (c.from >= 0) w.stats.lostBySettlement[c.from] += lost;
                w.stats.lossBy[1] += lost;
                if (lost > 20) w.news.tally("roadAttack", "Refugee column attacked on the road|Refugee columns attacked on the roads: {n} attack{s}, about {m} {mpeople} caught", 1, lost, c.x, c.y, News.LOSS);
            }
            // the infected among them turn on the way
            if (c.infected > 0) {
                float t = c.infected * dt / w.c.rule("turnTime", 180);
                int turned = Math.min(c.infected, (int) t + (Rng.unit(w.seed, w.tick, k * 3L + 1) < t % 1f ? 1 : 0));
                c.count -= turned;
                c.infected -= turned;
                w.sec.zIn[w.sec.touch(s)] += turned;
                w.stats.zombiesMade += turned;
                w.stats.civTurned += turned;
            }
            if (c.count <= 0) {
                c.alive = false;
                continue;
            }
            if (c.route == null || c.ri >= c.route.length - 1) {
                c.alive = false;
                if (c.home && c.route != null) {
                    settleHome(c.to, c.count - c.infected);
                    if (c.infected > 0) w.sec.inf[w.sec.touch(s)] += c.infected;
                } else if (c.to >= 0 && c.route != null) {
                    settle(c.to, c.count, c.infected, c.from);
                    arrivedFrom(c.from, c.to, c.count - c.infected, c.infected);
                    if (c.count >= 100) w.news.tally("arrive:" + c.to, News.num(c.count) + " refugees from " + w.r.settlements.get(c.from).name + " reach " + w.r.settlements.get(c.to).name
                            + "|Refugees reached " + w.r.settlements.get(c.to).name + ": {n} more column{s}, {m} {mpeople}", 1, c.count, c.x, c.y, News.CALL);
                } else if (c.route != null) {
                    leftCountry(c.from, c.count);
                    if (c.count >= 100) w.news.tally("border", News.num(c.count) + " refugees from " + w.r.settlements.get(c.from).name + " cross the border|Refugees over the border: {n} more column{s}, {m} {mpeople}", 1, c.count, c.x, c.y, News.CALL);
                } else {
                    w.sec.popIn[w.sec.touch(s)] += c.count - c.infected;
                    w.sec.inf[w.sec.touch(s)] += c.infected;
                }
            }
        }
        for (int k = columns.size() - 1; k >= 0; k--) if (!columns.get(k).alive) columns.remove(k);
    }

    private void settle(int st, int count, int infected, int from) {
        Sectors sec = w.sec;
        List<Integer> homes = new ArrayList<Integer>();
        for (int s : sec.idx.bySettlement[st]) if (sec.home[s] > 0 && sec.zIn[s] == 0) homes.add(s);
        int healthy = count - infected;
        if (homes.isEmpty()) {
            leftCountry(from, count);
            return;
        }
        int per = healthy / homes.size(), extra = healthy % homes.size();
        for (int k = 0; k < homes.size(); k++) sec.popIn[sec.touch(homes.get(k))] += per + (k < extra ? 1 : 0);
        if (infected > 0) sec.inf[sec.touch(homes.get(0))] += infected;
        w.society.arrived(st, count, infected);
    }

    // ------------------------------------------------------------------ bridges

    void considerBridges() {
        RoadGraph g = w.r.roads;
        for (Horde h : hordes) {
            if (!h.alive || h.count < 400 || h.target < 0) continue;
            float tx = w.sec.cx(h.target), ty = w.sec.cy(h.target);
            float hd = dist(h.x, h.y, tx, ty);
            for (int e = 0; e < g.edgeCount; e++) {
                if (!g.ebridge[e] || !g.ealive[e]) continue;
                float mx = (g.nx[g.ea[e]] + g.nx[g.eb[e]]) / 2, my = (g.ny[g.ea[e]] + g.ny[g.eb[e]]) / 2;
                if (dist(mx, my, h.x, h.y) < 1300 && dist(mx, my, tx, ty) < hd && dist(mx, my, tx, ty) > 300) {
                    blowBridge(e, false);
                    return;
                }
            }
        }
    }

    /** Engineers bring a bridge down: the road is cut, the river becomes a line the dead can't cross. */
    public boolean blowBridge(int edge, boolean byPlayer) {
        RoadGraph g = w.r.roads;
        if (edge < 0 || !g.ebridge[edge] || !g.ealive[edge]) return false;
        // the whole span: neighbouring bridge roads too
        List<Integer> span = new ArrayList<Integer>();
        span.add(edge);
        for (int k = 0; k < span.size(); k++) {
            int e = span.get(k);
            for (int end : new int[]{g.ea[e], g.eb[e]})
                for (int i = g.adjStart[end]; i < g.adjStart[end + 1]; i++) {
                    int o = g.adjEdge[i];
                    if (g.ebridge[o] && g.ealive[o] && !span.contains(o)) span.add(o);
                }
        }
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = 0, y1 = 0;
        for (int e : span) {
            g.ealive[e] = false;
            float ax = g.nx[g.ea[e]], ay = g.ny[g.ea[e]], bx = g.nx[g.eb[e]], by = g.ny[g.eb[e]];
            int steps = (int) (dist(ax, ay, bx, by) / 8) + 1;
            for (int sidx = 0; sidx <= steps; sidx++) {
                int tx = (int) ((ax + (bx - ax) * sidx / steps) / 16), ty = (int) ((ay + (by - ay) * sidx / steps) / 16);
                for (int dy = -4; dy <= 4; dy++)
                    for (int dx = -4; dx <= 4; dx++)
                        if (w.r.t(tx + dx, ty + dy) == Tile.BRIDGE) {
                            w.r.set(tx + dx, ty + dy, Tile.WATER);
                            x0 = Math.min(x0, tx + dx);
                            y0 = Math.min(y0, ty + dy);
                            x1 = Math.max(x1, tx + dx);
                            y1 = Math.max(y1, ty + dy);
                        }
            }
            w.combat.blast((ax + bx) / 2, (ay + by) / 2, 60, 200, -1, Ents.SOLDIER);
        }
        if (x0 <= x1) {
            w.r.rebuildNav(x0 - 2, y0 - 2, x1 + 2, y1 + 2);
            w.sec.connectivity(x0 - 2, y0 - 2, x1 + 2, y1 + 2);
            // anyone left standing over the water falls in
            Ents e = w.e;
            for (int i = 0; i < e.top; i++)
                if (e.loc[i] == Ents.FREE && Tile.water(w.r.tileAtPx(e.x[i], e.y[i]))) {
                    if (e.kind[i] == Ents.ZOMBIE) w.combat.killZombie(i, -1, Ents.SOLDIER, Combat.CAUSE_BLAST);
                    else w.combat.kill(i, Combat.CAUSE_BLAST, -1);
                }
            for (int v = 0; v < w.veh.top; v++)
                if (w.veh.usable(v) && !w.veh.flying(v) && Tile.water(w.r.tileAtPx(w.veh.x[v], w.veh.y[v]))) w.veh.hp[v] = 0;
        }
        w.mil.blownBridges.add(new int[]{edge, (int) w.time});
        w.stats.bridgesBlown++;
        float mx = (g.nx[g.ea[edge]] + g.nx[g.eb[edge]]) / 2, my = (g.ny[g.ea[edge]] + g.ny[g.eb[edge]]) / 2;
        w.news.headline((byPlayer ? "On your order, e" : "E") + "ngineers blow a bridge to stop the dead", mx, my);
        w.stats.rec.callout("Bridge blown", mx, my, Records.C_BLAST);
        return true;
    }

    // ------------------------------------------------------------------ strains

    private void strains() {
        // strains now arise in nests and spread from there (Ecology); this keeps the clock for old saves
        if (w.totalZombies() > 50) strainClock += dt;
    }

    // ------------------------------------------------------------------ the player as a survivor

    void playerDied(int cause) {
        if (w.mode == World.MODE_SURVIVOR && !w.ended) {
            w.ended = true;
            w.won = false;
            w.endTitle = "You didn't make it";
            w.endText = "You survived " + timeSince() + ".";
        }
    }

    private String timeSince() {
        int hours = (int) (w.time / 60), mins = (int) (w.time % 60);
        if (hours < 24) return hours + (hours == 1 ? " hour and " : " hours and ") + mins + " minutes";
        return hours / 24 + (hours / 24 == 1 ? " day and " : " days and ") + hours % 24 + " hours";
    }

    // ------------------------------------------------------------------ the scenario's goal

    private void goals() {
        Content.Scenario sc = w.scenario;
        if (sc == null || w.ended || w.watching || sc.goal == null || sc.goal.length() == 0) return;
        float deadline = sc.days * 1440f;
        boolean late = w.time >= deadline;
        String g = sc.goal;
        if (g.equals("hold")) {
            int alive = cityAlive();
            if (alive < startCityPop * 0.4f) end(false, "The city has fallen", "Fewer than 40% of its people are left.");
            else if (late) end(true, "The city held", alive + " of " + startCityPop + " people made it through " + sc.days + " days.");
        } else if (g.equals("evacuate")) {
            int out = evacuatedOut + evacuatedArrived;
            if (out >= startTownPop * 0.5f) end(true, "Evacuation complete", out + " people got out of the towns.");
            else if (late) end(false, "Too few got out", out + " of the " + startTownPop / 2 + " needed got out in time.");
        } else if (g.equals("retake")) {
            int z = w.totalZombies();
            if (z < 200 && w.time > 120) end(true, "The region is retaken", "The dead are down to " + z + ".");
            else if (late) end(false, "Out of time", z + " of the dead still walk the region.");
        } else if (g.equals("protect")) {
            Region.Settlement c = w.r.city();
            int z = w.stats.zBySettlement[c.id];
            if (z > 500) end(false, c.name + " is overrun", "More than 500 of the dead are inside the city.");
            else if (late) end(true, c.name + " stands", "The capital held for " + sc.days + " days.");
        } else if (g.equals("survive")) {
            if (w.playerEnt >= 0 && w.e.loc[w.playerEnt] == Ents.FREE) {
                float[] safe = safeAt(w.e.x[w.playerEnt], w.e.y[w.playerEnt]);
                if (safe != null)
                    end(true, "You reached safety", "You made it to " + SAFE_NAMES[(int) safe[2]] + " after " + timeSince() + ".");
                else if (late) end(true, "You survived", "You lived through " + sc.days + " days of it.");
            }
        }
    }

    /** Kinds of a place where one person is safe (the survivor's goal, and marks on the map). */
    public static final int SAFE_BASE = 0, SAFE_EVAC = 1, SAFE_COLONY = 2, SAFE_BOAT = 3;
    public static final String[] SAFE_NAMES = {"the army base", "an evacuation point", "a survivor settlement", "the boats on the shore"};

    /**
     * Where one person on foot is safe right now: the army base while it's ours, the evacuation point of every
     * town being evacuated, the survivor settlements still standing, and the waterfront where the coast guard
     * takes people off (one point a town). Each is {x, y, kind}.
     */
    public java.util.List<float[]> safePoints() {
        java.util.List<float[]> out = new java.util.ArrayList<float[]>();
        for (Region.Facility f : w.r.facilities)
            if (f.kind == Region.F_BASE && online(f.id)) out.add(new float[]{f.x, f.y, SAFE_BASE});
        for (Region.Settlement s : w.r.settlements) {
            if (!w.gov.evacuating(s.id)) continue;
            float[] p = evacPoint(s.id);
            out.add(new float[]{p[0], p[1], SAFE_EVAC});
            int best = -1;
            float bd = Float.MAX_VALUE;
            for (int q : w.sec.idx.bySettlement[s.id]) {
                if (!w.navy.pickup(q)) continue;
                float d = dist(w.sec.cx(q), w.sec.cy(q), s.cx * 16f, s.cy * 16f);
                if (d < bd) {
                    bd = d;
                    best = q;
                }
            }
            if (best >= 0) out.add(new float[]{w.sec.cx(best), w.sec.cy(best), SAFE_BOAT});
        }
        for (Society.Colony c : w.society.colonies) if (!c.fallen) out.add(new float[]{c.x, c.y, SAFE_COLONY});
        return out;
    }

    /** The safe place within reach of (x, y) (300 px; the army base's gates, 700 px from its middle), or null. */
    public float[] safeAt(float x, float y) {
        for (float[] p : safePoints()) if (dist(p[0], p[1], x, y) < (p[2] == SAFE_BASE ? 700 : 300)) return p;
        return null;
    }

    public int cityAlive() {
        Region.Settlement c = w.r.city();
        int n = 0;
        for (int s : w.sec.idx.bySettlement[c.id]) n += w.sec.popIn[s] + w.sp.secCiv[s];
        return n;
    }

    private void end(boolean win, String title, String text) {
        w.ended = true;
        w.won = win;
        w.endTitle = title;
        w.endText = text;
        w.news.headline(title, -1, -1);
    }

    void update(float dt) {
        this.dt = dt;
        extendFacilities();
        threat(dt);
        if (!latent.isEmpty()) latent();
        facilities();
        hordes();
        evacuation();
        moveColumns();
        strains();
        goals();
    }

    private float dt = 1;
}
