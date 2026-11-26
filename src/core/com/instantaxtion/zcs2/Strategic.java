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
        public boolean alive = true;
    }

    public static final class Column {
        public int count, infected, from, to = -1, exitNode = -1, ri;
        public int[] route;
        public float x, y;
        public boolean alive = true;
    }

    private final World w;
    public boolean[] online;
    public float[] overrunT;
    public boolean[] supplyOnWay;
    public final boolean[] gridOn;
    public final List<Horde> hordes = new ArrayList<Horde>();
    public final List<Column> columns = new ArrayList<Column>();
    public int strain;
    /** Where the airliner came down (scenario), or -1. */
    public float crashX = -1, crashY = -1;
    float strainClock;
    public int evacuatedOut, evacuatedArrived;
    final int[] pending;
    float columnT;
    public float playerJoyX, playerJoyY;
    public boolean playerFire = true;
    public int startTownPop, startCityPop;
    int nextHorde;

    Strategic(World w) {
        this.w = w;
        int nf = w.r.facilities.size();
        online = new boolean[nf];
        overrunT = new float[nf];
        supplyOnWay = new boolean[nf];
        for (Region.Facility f : w.r.facilities) {
            online[f.id] = true;
        }
        gridOn = new boolean[Math.max(1, w.r.gridCount)];
        java.util.Arrays.fill(gridOn, true);
        pending = new int[w.r.settlements.size()];
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
            int z = w.sp.zombiesNear(e, f.x, f.y, rad, 40) + Math.min(30, w.sec.zIn[s] / 4);
            int a = w.sp.livingNear(e, f.x, f.y, rad + 60, 40) - w.sp.secCiv[s] / 4;
            if (online[f.id]) {
                if (z >= (f.kind == Region.F_BASE ? 30 : 8) && a < z / 3) {
                    overrunT[f.id] += dt;
                    if (overrunT[f.id] > 15) {
                        online[f.id] = false;
                        overrunT[f.id] = 0;
                        lost(f);
                    }
                } else overrunT[f.id] = Math.max(0, overrunT[f.id] - dt);
            } else {
                if (z == 0 && w.sp.secCop[s] + w.sp.secSol[s] >= 3) {
                    overrunT[f.id] += dt;
                    if (overrunT[f.id] > 30) {
                        online[f.id] = true;
                        overrunT[f.id] = 0;
                        if (f.kind == Region.F_POWER && f.grid >= 0) {
                            gridOn[f.grid] = true;
                            w.news.headline("Power restored to " + gridName(f.grid), f.x, f.y);
                        } else w.news.radio(f.name + " retaken", f.x, f.y, News.COMMAND);
                    }
                } else overrunT[f.id] = 0;
            }
        }
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
                w.news.radio(f.name + " overrun", f.x, f.y, News.LOSS);
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
        if (count >= 500) w.news.radio("Recon: a horde of about " + round(count) + " is on the move", h.x, h.y, News.COMMAND);
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
            // reached people, a fight, or a watched place: break up into the sector
            if (sec.popIn[s] > 0 || sec.hot[s] > 0 || sec.cam[s] || w.sp.secCiv[s] + w.sp.secCop[s] + w.sp.secSol[s] > 0) {
                sec.zIn[s] += h.count;
                sec.hot[s] = Math.max(sec.hot[s], 10);
                h.alive = false;
                continue;
            }
            // choose (and now and then rethink) where to go: the most attractive place within reach
            if (h.target < 0 || w.tick % 600 == 0) {
                float best = 0;
                int sx = s % sec.sw, sy = s / sec.sw;
                for (int t = 0; t < sec.n; t++) {
                    if (sec.popIn[t] == 0 && w.sp.secCiv[t] == 0) continue;
                    int dx = t % sec.sw - sx, dy = t / sec.sw - sy;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    if (d > 24) continue;
                    float a = (sec.popIn[t] + 50 + (w.night() && lit(t) ? 200 : 0)) / (1 + d * d);
                    if (a > best) {
                        best = a;
                        h.target = t;
                    }
                }
                if (h.target >= 0) {
                    h.tx = sec.cx(h.target);
                    h.ty = sec.cy(h.target);
                }
            }
            if (h.target < 0) continue;
            // walk sector by sector, only where the land connects (rivers stop them where bridges are gone)
            int bd = -1;
            float bdist = dist(sec.cx(s), sec.cy(s), h.tx, h.ty);
            for (int d = 0; d < 8; d++) {
                if ((sec.connect[s] & (1 << d)) == 0) continue;
                int t = sec.neighbour(s, d);
                float dd = dist(sec.cx(t), sec.cy(t), h.tx, h.ty);
                if (dd < bdist - 1) {
                    bdist = dd;
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
            float speed = 11 * dt * w.weather.zombieSpeed();
            h.x += dx / d * Math.min(d, speed);
            h.y += dy / d * Math.min(d, speed);
        }
        // merge hordes that meet
        for (int a = 0; a < hordes.size(); a++) {
            Horde A = hordes.get(a);
            if (!A.alive) continue;
            for (int b = a + 1; b < hordes.size(); b++) {
                Horde B = hordes.get(b);
                if (B.alive && dist(A.x, A.y, B.x, B.y) < 200) {
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

    /** The settlement with the fewest of the dead per person, not this one; -1 = out of the region. */
    private int safest(int from) {
        int best = -1;
        float bs = Float.MAX_VALUE;
        int[] z = w.stats.zBySettlement;
        for (Region.Settlement s : w.r.settlements) {
            if (s.id == from || (s.kind != Region.S_CITY && s.kind != Region.S_TOWN) || w.gov.evacuating(s.id)) continue;
            float score = z[s.id] * 10f / (s.residents + 100) + dist(s.cx, s.cy, w.r.settlements.get(from).cx, w.r.settlements.get(from).cy) / 400f;
            if (z[s.id] > 50) continue;
            if (score < bs) {
                bs = score;
                best = s.id;
            }
        }
        return best;
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
        if ((w.e.flags[i] & Ents.F_INFECTED) != 0) infectedPending++;
        pending[st]++;
        w.retire(i);
    }

    int infectedPending;

    private void evacuation() {
        Sectors sec = w.sec;
        for (int s = 0; s < sec.n; s++) {
            int st = sec.settlement[s];
            if (st < 0 || !w.gov.evacuating(st) || sec.popIn[s] == 0 || sec.zIn[s] > 0) continue;
            float rate = (0.004f + 0.004f * w.gov.trust / 100f) * dt;
            int m = Math.min(sec.popIn[s], (int) (sec.popIn[s] * rate) + (Rng.unit(w.seed, w.tick, s) < 0.5f ? 1 : 0));
            sec.popIn[s] -= m;
            pending[st] += m;
        }
        columnT -= dt;
        if (columnT > 0) return;
        columnT = 15;
        for (int st = 0; st < pending.length; st++) {
            if (pending[st] <= 0) continue;
            if (w.command.quarantined(st)) {
                // turned back at the checkpoints
                Region.Settlement qs = w.r.settlements.get(st);
                int back = qs.hubNode >= 0 ? w.r.sectorOfPx(w.r.roads.nx[qs.hubNode], w.r.roads.ny[qs.hubNode]) : w.r.sectorOfPx(qs.cx * 16f, qs.cy * 16f);
                int inf = Math.min(pending[st], infectedPending);
                infectedPending -= inf;
                sec.popIn[back] += pending[st] - inf;
                sec.inf[back] += inf;
                w.stats.turnedBack += pending[st];
                pending[st] = 0;
                continue;
            }
            Column c = new Column();
            c.count = pending[st];
            c.infected = Math.min(c.count, infectedPending);
            infectedPending -= c.infected;
            c.from = st;
            pending[st] = 0;
            float[] p = evacPoint(st);
            int a = w.r.roads.nearestNode(p[0], p[1], 600);
            int dest = safest(st);
            int b;
            if (dest >= 0) {
                c.to = dest;
                b = w.r.settlements.get(dest).hubNode;
            } else {
                b = w.r.entryNodes.isEmpty() ? -1 : w.r.entryNodes.get(Rng.hashInt(w.seed, w.tick, st, w.r.entryNodes.size()));
                c.exitNode = b;
            }
            c.route = w.r.roads.route(a, b);
            if (c.route == null) {
                // nowhere to go: they go back indoors
                int s = w.r.sectorOfPx(p[0], p[1]);
                sec.popIn[s] += c.count;
                continue;
            }
            c.x = w.r.roads.nx[c.route[0]];
            c.y = w.r.roads.ny[c.route[0]];
            columns.add(c);
        }
    }

    private void moveColumns() {
        RoadGraph g = w.r.roads;
        for (int k = 0; k < columns.size(); k++) {
            Column c = columns.get(k);
            if (!c.alive) continue;
            float budget = 28 * dt; // px per second: people on foot, cars, buses
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
            // the dead on the road
            int z = w.sp.secZ[s] + w.sec.zIn[s];
            if (z >= 5) {
                int lost = Math.min(c.count, Math.max(1, z / 6));
                c.count -= lost;
                w.sec.inf[s] += lost;
                w.stats.infectedTotal += lost;
                if (lost > 20) w.news.radio("Refugee column attacked on the road", c.x, c.y, News.LOSS);
            }
            // the infected among them turn on the way
            if (c.infected > 0 && Rng.unit(w.seed, w.tick, k) < 0.02f) {
                c.count--;
                c.infected--;
                w.sec.zIn[s]++;
            }
            if (c.count <= 0) {
                c.alive = false;
                continue;
            }
            if (c.route == null || c.ri >= c.route.length - 1) {
                c.alive = false;
                if (c.to >= 0 && c.route != null) {
                    settle(c.to, c.count, c.infected);
                    evacuatedArrived += c.count;
                    if (c.count >= 100) w.news.radio(c.count + " refugees reach " + w.r.settlements.get(c.to).name, c.x, c.y, News.CALL);
                } else if (c.route != null) {
                    evacuatedOut += c.count;
                } else {
                    w.sec.popIn[s] += c.count;
                    w.sec.inf[s] += c.infected;
                }
            }
        }
        for (int k = columns.size() - 1; k >= 0; k--) if (!columns.get(k).alive) columns.remove(k);
    }

    private void settle(int st, int count, int infected) {
        Sectors sec = w.sec;
        List<Integer> homes = new ArrayList<Integer>();
        for (int s = 0; s < sec.n; s++) if (sec.settlement[s] == st && sec.home[s] > 0 && sec.zIn[s] == 0) homes.add(s);
        if (homes.isEmpty()) {
            evacuatedOut += count;
            return;
        }
        for (int k = 0; k < count; k++) sec.popIn[homes.get(k % homes.size())]++;
        if (infected > 0) sec.inf[homes.get(0)] += infected;
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
            w.sec.connectivity();
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
        return true;
    }

    // ------------------------------------------------------------------ strains

    private void strains() {
        if (w.totalZombies() > 50) strainClock += dt;
        if (strain < 3 && strainClock > 2160 * (strain + 1)) {
            strain++;
            String[] what = {"", "RUNNERS: a fast strain of the dead is spreading", "ARMOURED: the dead in riot gear and helmets are shrugging off headshots", "STALKERS: a strain that hunts at night has appeared"};
            w.news.headline("New strain. " + what[strain], -1, -1);
        }
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
        if (sc == null || w.ended || sc.goal == null || sc.goal.length() == 0) return;
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
                Region.Facility b = w.mil.base();
                if (b != null && dist(w.e.x[w.playerEnt], w.e.y[w.playerEnt], b.x, b.y) < 300)
                    end(true, "You reached safety", "You made it to " + b.name + " after " + timeSince() + ".");
                else if (late) end(true, "You survived", "You lived through " + sc.days + " days of it.");
            }
        }
    }

    public int cityAlive() {
        Region.Settlement c = w.r.city();
        int n = 0;
        for (int s = 0; s < w.sec.n; s++) if (w.sec.settlement[s] == c.id) n += w.sec.popIn[s] + w.sp.secCiv[s];
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
        facilities();
        hordes();
        evacuation();
        moveColumns();
        strains();
        goals();
    }

    private float dt = 1;
}
