package com.instantaxtion.zcs2;

import java.util.ArrayList;
import java.util.List;

/**
 * Emergency calls and the police response. People report the dead (a sector with both, after a delay); each
 * call needs officers in proportion to the dead near it, sent nearest-first. Once an outbreak in a town is too
 * big for street calls, the police throw a cordon round the infected sectors and leave the inside to the army.
 */
public final class Dispatch {
    public static final class Incident {
        public int id, sector, settlement, zombies, assigned, needed, armyAssigned;
        public float x, y, created, lastSeen;
        public boolean open = true, army;
        public final List<Integer> squads = new ArrayList<Integer>();
    }

    public static final class Post {
        public float x, y, fx, fy;
        public int squad = -1, settlement;
    }

    private final World w;
    public final List<Incident> incidents = new ArrayList<Incident>();
    public final List<Post> posts = new ArrayList<Post>();
    public int callsTotal;
    final int[] secIncident;
    float cordonT;
    final boolean[] perimeter;

    Dispatch(World w) {
        this.w = w;
        secIncident = new int[w.sec.n];
        java.util.Arrays.fill(secIncident, -1);
        perimeter = new boolean[64];
    }

    public int openCount() {
        int n = 0;
        for (Incident i : incidents) if (i.open) n++;
        return n;
    }

    /** True when a town's police have switched to holding a perimeter. */
    public boolean perimeterMode(int settlement) {
        return settlement >= 0 && settlement < perimeter.length && perimeter[settlement];
    }

    void update() {
        Sectors sec = w.sec;
        Spatial sp = w.sp;
        float now = (float) w.time;
        // new calls: the dead where people can see them, after the time it takes someone to call
        for (int s = 0; s < sec.n; s++) {
            int zHere = sp.secZ[s] + (sec.zIn[s] > 0 ? Math.max(1, sec.zIn[s] / 3) : 0);
            if (zHere == 0) continue;
            boolean witnesses = sp.secCiv[s] > 0 || sec.popIn[s] > 0 || sp.secCop[s] + sp.secSol[s] > 0;
            if (!witnesses) continue;
            float delay = sp.secCop[s] + sp.secSol[s] > 0 ? 2 : w.strat.lit(s) ? 18 : 35;
            // the dead only indoors: people hide and hope before anyone calls
            if (sp.secZ[s] == 0) delay += 100;
            if (sec.firstZ[s] < 0 || now - sec.firstZ[s] < delay) continue;
            int id = secIncident[s];
            if (id < 0 || !incidents.get(id).open) {
                Incident in = new Incident();
                in.id = incidents.size();
                in.sector = s;
                in.settlement = sec.settlement[s];
                in.created = now;
                place(in, s);
                incidents.add(in);
                secIncident[s] = in.id;
                callsTotal++;
                if (!sec.alerted[s]) {
                    sec.alerted[s] = true;
                    w.gov.firstReport(in);
                }
                if (callsTotal <= 3 || callsTotal % 25 == 0)
                    w.news.radio("911 call: attack reported" + where(in), in.x, in.y, News.CALL);
            }
        }
        // keep each call up to date; close the quiet ones
        for (Incident in : incidents) {
            if (!in.open) continue;
            int s = in.sector;
            in.zombies = sp.secZ[s] + sec.zIn[s];
            if (in.zombies > 0) {
                in.lastSeen = now;
                place(in, s);
            } else if (now - in.lastSeen > 25) close(in);
        }
        cordonT -= 1;
        perimeters();
        assign();
        if (cordonT <= 0) {
            cordons();
            cordonT = 12;
        }
    }

    private String where(Incident in) {
        if (in.settlement >= 0) return " in " + w.r.settlements.get(in.settlement).name;
        return " in the countryside";
    }

    /** The call's position: the middle of the dead on the map, or the sector's middle. */
    private void place(Incident in, int s) {
        Ents e = w.e;
        float sx = 0, sy = 0;
        int n = 0;
        float cs = Tile.SIZE * Region.SECT;
        float x0 = (s % w.sec.sw) * cs, y0 = (s / w.sec.sw) * cs;
        int c0 = w.sp.cellOf(x0, y0), c1 = w.sp.cellOf(x0 + cs - 1, y0 + cs - 1);
        int cx0 = c0 % w.sp.cw, cy0 = c0 / w.sp.cw, cx1 = c1 % w.sp.cw, cy1 = c1 / w.sp.cw;
        for (int cy = cy0; cy <= cy1; cy++)
            for (int cx = cx0; cx <= cx1; cx++) {
                int c = cy * w.sp.cw + cx;
                for (int j = w.sp.zStart[c]; j < w.sp.zStart[c + 1] && n < 200; j++) {
                    int z = w.sp.zList[j];
                    sx += e.x[z];
                    sy += e.y[z];
                    n++;
                }
            }
        if (n > 0) {
            in.x = sx / n;
            in.y = sy / n;
        } else {
            float[] p = w.sec.spot(s, 0);
            in.x = p != null ? p[0] : w.sec.cx(s);
            in.y = p != null ? p[1] : w.sec.cy(s);
        }
    }

    private void close(Incident in) {
        in.open = false;
        for (int id : in.squads) {
            Squads.Squad s = w.sq.get(id);
            if (s.alive && s.incident == in.id) {
                s.incident = -1;
                w.sq.goHome(s);
            }
        }
        in.squads.clear();
    }

    /** Per town: when the dead inside it pass 150, the police hold a perimeter instead of answering calls inside. */
    private void perimeters() {
        int[] z = new int[w.r.settlements.size()];
        for (int s = 0; s < w.sec.n; s++) {
            int st = w.sec.settlement[s];
            if (st >= 0) z[st] += w.sp.secZ[s] + w.sec.zIn[s];
        }
        for (int st = 0; st < z.length && st < perimeter.length; st++) {
            boolean was = perimeter[st];
            if (!was && z[st] > 150) {
                perimeter[st] = true;
                Region.Settlement S = w.r.settlements.get(st);
                w.news.radio("Command: " + S.name + " police to hold a perimeter. The army takes the inside.", S.cx * 16f, S.cy * 16f, News.COMMAND);
                w.mil.requestArmy(st, z[st]);
            } else if (was && z[st] < 40) perimeter[st] = false;
        }
    }

    private void assign() {
        for (Incident in : incidents) {
            if (!in.open) continue;
            // drop squads that are gone or went home
            for (int k = in.squads.size() - 1; k >= 0; k--) {
                Squads.Squad s = w.sq.get(in.squads.get(k));
                if (!s.alive || s.incident != in.id) in.squads.remove(k);
            }
            int have = 0;
            for (int id : in.squads) {
                Squads.Squad s = w.sq.get(id);
                if (s.faction == Ents.COP) have += s.n;
            }
            in.assigned = have;
            boolean big = perimeterMode(in.settlement) && in.zombies >= 4;
            in.army = big;
            in.needed = big ? 0 : Math.min(48, 2 + in.zombies / 2);
            if (in.zombies >= 8 && !big) w.mil.considerCall(in);
            if (big) {
                // inside the perimeter: police come away, the army comes in
                for (int k = in.squads.size() - 1; k >= 0; k--) {
                    Squads.Squad s = w.sq.get(in.squads.get(k));
                    if (s.faction == Ents.COP) {
                        s.incident = -1;
                        w.sq.goHome(s);
                        in.squads.remove(k);
                    }
                }
                w.mil.considerCall(in);
                continue;
            }
            int guard = 0;
            while (have < in.needed && guard++ < 6) {
                Squads.Squad s = nearestFree(in, Ents.COP, 9000);
                if (s == null) break;
                send(s, in);
                have += s.n;
            }
        }
    }

    /** Sends a squad to a call. */
    public void send(Squads.Squad s, Incident in) {
        s.incident = in.id;
        in.squads.add(s.id);
        float[] p = w.sq.where(s);
        float dx = in.x - p[0], dy = in.y - p[1], d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
        // hold a little short of the dead, facing them
        float hx = in.x - dx / d * 90, hy = in.y - dy / d * 90;
        if (!w.r.walkablePx(hx, hy)) {
            hx = in.x;
            hy = in.y;
        }
        s.faceX = dx / d;
        s.faceY = dy / d;
        w.sq.order(s, Squads.O_RESPOND, hx, hy);
    }

    Squads.Squad nearestFree(Incident in, int faction, float maxDist) {
        Squads.Squad best = null;
        float bd = maxDist * maxDist;
        for (Squads.Squad s : w.sq.list) {
            if (s.faction != faction || !w.sq.free(s) || s.task >= 0) continue;
            float[] p = w.sq.where(s);
            float dx = p[0] - in.x, dy = p[1] - in.y, d = dx * dx + dy * dy;
            // police stay with their own town unless it's next door
            if (faction == Ents.COP && s.home >= 0 && in.settlement >= 0 && w.r.facilities.get(s.home).settlement != in.settlement) d *= 4;
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        // or a station, base or formation that can send a squad from its pool
        Region.Facility from = null;
        Content.SquadT tmpl = null;
        for (Region.Facility f : w.r.facilities) {
            if (f.pool == null) continue;
            if (f.kind == Region.F_HQ) {
                Forces.Formation fm = w.forces.formationOf(f);
                if (fm == null || !fm.alive || fm.state == Forces.MOVING || fm.player) continue;
            }
            float dx = f.x - in.x, dy = f.y - in.y, d = dx * dx + dy * dy;
            if (faction == Ents.COP && in.settlement >= 0 && f.settlement != in.settlement && f.kind != Region.F_HQ) d *= 4;
            if (d >= bd) continue;
            Content.SquadT t = w.forces.templateFor(f, faction, in.zombies);
            if (t == null) continue;
            bd = d;
            from = f;
            tmpl = t;
        }
        if (from != null) {
            Squads.Squad s = w.forces.deploy(from, tmpl);
            if (s != null) return s;
        }
        return best;
    }

    /** A cordon of posts round each town's infected sectors, on the roads, held by police. */
    private void cordons() {
        // which sectors are infected
        boolean[] bad = new boolean[w.sec.n];
        for (int s = 0; s < w.sec.n; s++) bad[s] = w.sp.secZ[s] + w.sec.zIn[s] >= 3;
        List<Post> want = new ArrayList<Post>();
        RoadGraph g = w.r.roads;
        for (int st = 0; st < w.r.settlements.size() && st < perimeter.length; st++) {
            if (!perimeter[st]) continue;
            // junctions just outside infected sectors (in an uninfected one, next to an infected one)
            float sx = 0, sy = 0;
            int sn = 0;
            for (int s = 0; s < w.sec.n; s++)
                if (bad[s] && w.sec.settlement[s] == st) {
                    sx += w.sec.cx(s);
                    sy += w.sec.cy(s);
                    sn++;
                }
            if (sn == 0) continue;
            sx /= sn;
            sy /= sn;
            List<Post> mine = new ArrayList<Post>();
            for (int n = 0; n < g.nodeCount; n++) {
                if (g.nset[n] != st) continue;
                int s = w.r.sectorOfPx(g.nx[n], g.ny[n]);
                if (bad[s]) continue;
                boolean edge = false;
                for (int d = 0; d < 8 && !edge; d++) {
                    int t = w.sec.neighbour(s, d);
                    if (t >= 0 && bad[t]) edge = true;
                }
                if (!edge) continue;
                // keep posts apart
                boolean close = false;
                for (Post p : mine) {
                    float dx = p.x - g.nx[n], dy = p.y - g.ny[n];
                    if (dx * dx + dy * dy < 260 * 260) close = true;
                }
                if (close) continue;
                Post p = new Post();
                p.x = g.nx[n];
                p.y = g.ny[n];
                float dx = sx - p.x, dy = sy - p.y, dl = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
                p.fx = dx / dl;
                p.fy = dy / dl;
                p.settlement = st;
                mine.add(p);
                if (mine.size() >= 18) break;
            }
            want.addAll(mine);
        }
        // keep squads on posts that still stand; release the rest
        for (Post old : posts) {
            Post keep = null;
            for (Post p : want)
                if (p.squad < 0 && Math.abs(p.x - old.x) < 1 && Math.abs(p.y - old.y) < 1) keep = p;
            if (old.squad < 0) continue;
            Squads.Squad s = w.sq.get(old.squad);
            if (keep != null && s.alive && s.order == Squads.O_CORDON) keep.squad = old.squad;
            else if (s.alive && s.order == Squads.O_CORDON) {
                s.task = -1;
                w.sq.goHome(s);
            }
        }
        posts.clear();
        posts.addAll(want);
        for (int k = 0; k < posts.size(); k++) {
            Post p = posts.get(k);
            if (p.squad >= 0) {
                w.sq.get(p.squad).task = k;
                continue;
            }
            Incident fake = new Incident();
            fake.x = p.x;
            fake.y = p.y;
            fake.settlement = p.settlement;
            Squads.Squad s = nearestFree(fake, Ents.COP, 12000);
            if (s == null) break;
            p.squad = s.id;
            s.task = k;
            s.faceX = p.fx;
            s.faceY = p.fy;
            s.hold = 50;
            w.sq.order(s, Squads.O_CORDON, p.x - p.fx * 40, p.y - p.fy * 40);
        }
    }

    void squadGone(Squads.Squad s) {
        if (s.task >= 0 && s.task < posts.size() && posts.get(s.task).squad == s.id) posts.get(s.task).squad = -1;
    }

    /** An officer or soldier went down here: the call there needs more. */
    void unitDown(float x, float y) {
        int s = w.r.sectorOfPx(x, y);
        int id = secIncident[s];
        if (id >= 0 && incidents.get(id).open) incidents.get(id).needed += 2;
    }

    public Incident incidentAt(int sector) {
        int id = secIncident[sector];
        return id >= 0 ? incidents.get(id) : null;
    }
}
