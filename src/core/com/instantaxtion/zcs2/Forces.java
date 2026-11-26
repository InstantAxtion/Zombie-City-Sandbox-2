package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Every armed force, at the scale of a country. Police wait at their stations and soldiers at their bases as
 * numbers (pools); a squad is made from a pool only when it's sent somewhere, and goes back into it when it comes
 * home and stands down. The army moves as formations (brigades, battalions, batteries) on the national map:
 * one icon on the roads, holding an area when it arrives. Where nobody is watching, fights are settled as numbers
 * (the station's officers against the dead counted in its streets); where something is happening, squads come out
 * and fight person by person. In strategic fast-forward, everything is numbers.
 */
public final class Forces extends Module {
    public static final int GARRISON = 0, MOVING = 1, HOLDING = 2, BROKEN = 3, AIRLIFT = 4, RAIL = 5;
    public static final String[] STATES = {"In garrison", "On the move", "Holding", "Broken, falling back", "In the air", "On a troop train"};

    /** Travelling: on the road, in the air or on a train (not fighting as numbers, not sending squads). */
    public static boolean travelling(Formation f) {
        return f.state == MOVING || f.state == AIRLIFT || f.state == RAIL;
    }

    public static final class Formation {
        public int id;
        public Content.FormationT t;
        public String name;
        public int faction;
        public Region.Facility hq;
        public int state;
        public int[] route;
        public int ri;
        public float gx, gy, gunT;
        public int start, kills, losses, tanksOut, home = -1, task = -1;
        public boolean alive = true, outside, player;

        public int strength() {
            return hq.poolTotal() + deployed;
        }

        public int deployed;
    }

    public final List<Formation> formations = new ArrayList<Formation>();
    private final List<Integer> freeSquads = new ArrayList<Integer>();
    private int nextFormationNo = 1;
    private float commandT;
    private final float[] tmp = new float[4];

    public Forces(World w) {
        super(w);
    }

    @Override
    public String id() {
        return "forces";
    }

    // ------------------------------------------------------------------ pools

    public void ensurePools(Region.Facility f) {
        if (f.pool == null) f.pool = new int[w.c.units.size()];
        if (f.vpool == null) f.vpool = new int[w.c.vtypes.size()];
    }

    /** The word a facility answers to in a squad's "homes" list. */
    public static String homeKind(Region.Facility f) {
        switch (f.kind) {
            case Region.F_POLICE: return "police";
            case Region.F_PRISON: return "prison";
            case Region.F_BASE:
            case Region.F_AIRBASE:
            case Region.F_PORT: return "base";
            case Region.F_ARMORY: return "armory";
            case Region.F_HQ: return "formation";
            case Region.F_FIRE: return "fire";
            default: return "";
        }
    }

    public void addSquadToPool(Region.Facility f, Content.SquadT t, int count) {
        ensurePools(f);
        for (int k = 0; k < count; k++) {
            for (Content.Unit u : t.units) f.pool[u.index]++;
            if (t.vehicle != null) f.vpool[t.vehicle.index]++;
        }
    }

    /** Armed people waiting at a facility (not deployed), for one side. */
    public int armedAt(Region.Facility f, int faction) {
        if (f.pool == null) return 0;
        int n = 0;
        for (Content.Unit u : w.c.units) {
            int k = u.kind == Content.COP ? Ents.COP : u.kind == Content.SOLDIER ? Ents.SOLDIER : -1;
            if (k == faction && u.weapon != null) n += f.pool[u.index];
        }
        return n;
    }

    /** The best squad this facility can send right now, or null. */
    public Content.SquadT templateFor(Region.Facility f, int faction, int zombies) {
        if (f.pool == null || !w.strat.online(f.id)) return null;
        String hk = homeKind(f);
        Content.SquadT best = null;
        int bestScore = -1;
        for (Content.SquadT t : w.c.squads) {
            int tf = t.faction.equals("police") ? Ents.COP : t.faction.equals("army") ? Ents.SOLDIER : -1;
            if (tf != faction) continue;
            boolean home = false;
            for (String h : t.homes) if (h.equals(hk)) home = true;
            if (!home) continue;
            // need at least half its people
            int have = 0;
            int[] need = new int[w.c.units.size()];
            for (Content.Unit u : t.units) need[u.index]++;
            for (int i = 0; i < need.length; i++) have += Math.min(need[i], f.pool[i]);
            if (have * 2 < t.units.length) continue;
            int score = have * 10;
            // heavier squads for bigger fights
            if (zombies >= 20 && t.units.length >= 8) score += 40;
            if (zombies < 20 && t.units.length <= 4) score += 25;
            if (t.vehicle != null && f.vpool[t.vehicle.index] > 0) score += 15;
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    /** Makes a squad from a facility's pool, at its yard. */
    public Squads.Squad deploy(Region.Facility f, Content.SquadT t) {
        ensurePools(f);
        int faction = t.faction.equals("police") ? Ents.COP : Ents.SOLDIER;
        Squads.Squad s = w.sq.create(faction, f.id, t.name);
        s.template = t.index;
        int k = 0;
        for (Content.Unit u : t.units) {
            if (f.pool[u.index] <= 0) continue;
            f.pool[u.index]--;
            float x = f.x + (k % 4) * 7 - 10, y = f.y + (k / 4) * 7;
            if (!w.r.walkablePx(x, y)) {
                x = f.x;
                y = f.y;
            }
            int i = w.spawnUnit(u, x, y);
            w.sq.add(s, i);
            k++;
        }
        if (t.vehicle != null && f.vpool[t.vehicle.index] > 0) {
            f.vpool[t.vehicle.index]--;
            float vx = f.x, vy = f.y;
            int n = w.r.roads.nearestNode(f.x, f.y, 300);
            if (f.kind == Region.F_HQ && n >= 0) {
                vx = w.r.roads.nx[n];
                vy = w.r.roads.ny[n];
            }
            int v = w.veh.add(t.vehicle, vx, vy, 0, f.id);
            s.vehicle = v;
            w.veh.squad[v] = s.id;
        }
        Formation fm = formationOf(f);
        if (fm != null) fm.deployed += s.n;
        if (s.n == 0) {
            s.alive = false;
            return null;
        }
        return s;
    }

    /** A squad back home and idle goes back into its facility's pool. */
    public void standDown(Squads.Squad s) {
        if (!s.alive) return;
        Region.Facility f = s.home >= 0 ? w.r.facilities.get(s.home) : null;
        Ents e = w.e;
        for (int k = s.n - 1; k >= 0; k--) {
            int m = s.mem[k];
            if (f != null) {
                ensurePools(f);
                f.pool[e.type[m]]++;
            }
            if (e.loc[m] == Ents.ABOARD) w.veh.dropCrew(e.vehicle[m], m);
            e.squad[m] = -1;
            if (e.loc[m] == Ents.ABOARD) {
                e.loc[m] = Ents.FREE; // (leaving the map from inside a vehicle: no need to step out first)
            }
            w.e.remove(m);
        }
        Formation fm = f != null ? formationOf(f) : null;
        if (fm != null) fm.deployed = Math.max(0, fm.deployed - s.n);
        s.n = 0;
        if (s.vehicle >= 0) {
            int v = s.vehicle;
            if (w.veh.usable(v) && f != null) f.vpool[w.veh.type[v]]++;
            w.veh.crewN[v] = 0;
            w.veh.state[v] = Vehicles.V_NONE;
            s.vehicle = -1;
        }
        s.alive = false;
        w.disp.squadGone(s);
        freeSquads.add(s.id);
    }

    /** A dead squad's slot, to be reused (keeps the list from growing over weeks). */
    public int reuseSquadSlot() {
        while (!freeSquads.isEmpty()) {
            int id = freeSquads.remove(freeSquads.size() - 1);
            if (id < w.sq.list.size() && !w.sq.list.get(id).alive) return id;
        }
        return -1;
    }

    void squadDied(Squads.Squad s) {
        Region.Facility f = s.home >= 0 && s.home < w.r.facilities.size() ? w.r.facilities.get(s.home) : null;
        Formation fm = f != null ? formationOf(f) : null;
        if (fm != null) fm.deployed = Math.max(0, fm.deployed - s.n);
        freeSquads.add(s.id);
    }

    public Formation formationOf(Region.Facility f) {
        if (f == null || f.kind != Region.F_HQ) return null;
        for (Formation fm : formations) if (fm.hq == f) return fm;
        return null;
    }

    // ------------------------------------------------------------------ formations

    public Formation createFormation(Content.FormationT t, float x, float y, boolean outside) {
        Formation f = new Formation();
        f.id = formations.size();
        f.t = t;
        f.faction = t.faction.equals("police") ? Ents.COP : Ents.SOLDIER;
        f.name = ordinal(nextFormationNo++) + " " + t.name;
        f.hq = w.r.addFacility(Region.F_HQ, -1, x, y, f.name);
        ensurePools(f.hq);
        for (Content.SquadT st : t.squads) addSquadToPool(f.hq, st, 1);
        Content.VType tank = w.c.vtype("tank");
        if (tank != null) f.hq.vpool[tank.index] += t.tanks;
        f.hq.ammo = 400f * t.squads.length + 2000;
        f.hq.fuel = 100;
        f.start = f.hq.poolTotal();
        f.outside = outside;
        f.gx = x;
        f.gy = y;
        f.state = GARRISON;
        formations.add(f);
        w.strat.extendFacilities();
        return f;
    }

    private static String ordinal(int n) {
        String suf = (n % 100 >= 11 && n % 100 <= 13) ? "th" : n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th";
        return n + suf;
    }

    /** Sends a formation by road to (x, y), where it will hold. */
    public boolean order(Formation f, float x, float y) {
        if (!f.alive) return false;
        if (byRail(f, x, y)) return true;
        RoadGraph g = w.r.roads;
        int a = g.nearestNode(f.hq.x, f.hq.y, 2000), b = g.nearestNode(x, y, 2000);
        if (a < 0 || b < 0) return false;
        int[] rt = g.route(a, b);
        if (rt == null) return false;
        f.route = rt;
        f.ri = 0;
        f.gx = x;
        f.gy = y;
        f.state = MOVING;
        // its squads come back to it first (they'll catch up)
        for (Squads.Squad s : w.sq.list) if (s.alive && s.home == f.hq.id && !s.player) w.sq.goHome(s);
        return true;
    }

    /** A long journey from near a railway to near another part of it goes by troop train. */
    private boolean byRail(Formation f, float x, float y) {
        RoadGraph rg = w.r.rails;
        if (rg.nodeCount < 2 || rg.adjStart == null) return false;
        float dx = x - f.hq.x, dy = y - f.hq.y;
        float over = w.c.rule("troopTrainOver", 4000);
        if (dx * dx + dy * dy < over * over) return false;
        int a = rg.nearestNode(f.hq.x, f.hq.y, 700), b = rg.nearestNode(x, y, 2500);
        if (a < 0 || b < 0 || a == b) return false;
        int[] rt = rg.route(a, b);
        if (rt == null || rt.length < 3) return false;
        f.route = rt;
        f.ri = 0;
        f.gx = x;
        f.gy = y;
        f.hq.x = rg.nx[a];
        f.hq.y = rg.ny[a];
        f.state = RAIL;
        for (Squads.Squad s : w.sq.list) if (s.alive && s.home == f.hq.id && !s.player) standDown(s);
        w.news.radio("The " + f.name + " is boarding a troop train", f.hq.x, f.hq.y, News.COMMAND);
        w.stats.troopTrains++;
        return true;
    }

    /** Along the railway at train speed; at the end, on by road. A cut line (a blown bridge) puts them off early. */
    private void rail(Formation f, float dt) {
        RoadGraph g = w.r.rails;
        float budget = w.c.rule("trainSpeed", 90) * dt;
        boolean cut = false;
        while (budget > 0 && f.route != null && f.ri < f.route.length - 1) {
            int nn = f.route[f.ri + 1];
            int e = g.edgeBetween(f.route[f.ri], nn);
            if (e >= 0 && !g.ealive[e]) {
                cut = true;
                break;
            }
            float dx = g.nx[nn] - f.hq.x, dy = g.ny[nn] - f.hq.y, d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d <= budget) {
                f.hq.x = g.nx[nn];
                f.hq.y = g.ny[nn];
                f.ri++;
                budget -= d;
            } else {
                f.hq.x += dx / d * budget;
                f.hq.y += dy / d * budget;
                budget = 0;
            }
        }
        if (cut || f.route == null || f.ri >= f.route.length - 1) {
            f.route = null;
            f.state = HOLDING;
            w.news.radio("The " + f.name + (cut ? " is off the train early: the line is cut" : " has arrived by train"), f.hq.x, f.hq.y, News.COMMAND);
            // the last stretch by road (close enough now that it won't take the train again)
            float gx = f.gx, gy = f.gy;
            if (!order(f, gx, gy)) f.state = HOLDING;
        }
    }

    private void move(Formation f, float dt) {
        RoadGraph g = w.r.roads;
        float budget = f.t.speed * dt * (w.weather.zombieSpeed() < 1 ? 0.85f : 1);
        while (budget > 0 && f.route != null && f.ri < f.route.length - 1) {
            int nn = f.route[f.ri + 1];
            int e = g.edgeBetween(f.route[f.ri], nn);
            if (e >= 0 && !g.ealive[e]) {
                // the road's cut: find another way
                if (!order(f, f.gx, f.gy)) {
                    f.state = HOLDING;
                    f.route = null;
                }
                return;
            }
            float dx = g.nx[nn] - f.hq.x, dy = g.ny[nn] - f.hq.y, d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d <= budget) {
                f.hq.x = g.nx[nn];
                f.hq.y = g.ny[nn];
                f.ri++;
                budget -= d;
            } else {
                f.hq.x += dx / d * budget;
                f.hq.y += dy / d * budget;
                budget = 0;
            }
            // the dead across the road: stop and fight
            int s = w.r.sectorOfPx(f.hq.x, f.hq.y);
            if (w.sec.zIn[s] + w.sp.secZ[s] > 60) {
                f.state = HOLDING;
                f.route = null;
                w.news.radio(f.name + ": contact on the road, deploying", f.hq.x, f.hq.y, News.COMMAND);
                return;
            }
        }
        if (f.route == null || f.ri >= f.route.length - 1) {
            f.state = HOLDING;
            f.route = null;
            w.news.radio(f.name + " in position", f.hq.x, f.hq.y, News.COMMAND);
        }
    }

    // ------------------------------------------------------------------ every second (or strategic step)

    @Override
    public void update(float dt) {
        // squads home and idle stand down
        float wait = w.c.rule("standDown", 20);
        Ents e = w.e;
        for (int q = 0; q < w.sq.list.size(); q++) {
            Squads.Squad s = w.sq.list.get(q);
            if (!s.alive || s.player || s.order != Squads.O_IDLE || s.mode != Squads.M_FOOT) {
                if (s.alive) s.idleT = 0;
                continue;
            }
            s.idleT += dt;
            if (s.idleT < wait || s.home < 0) continue;
            Region.Facility f = w.r.facilities.get(s.home);
            boolean near = true;
            for (int k = 0; k < s.n; k++) {
                int m = s.mem[k];
                float dx = e.x[m] - f.x, dy = e.y[m] - f.y;
                if (e.loc[m] == Ents.FREE && dx * dx + dy * dy > 200 * 200) near = false;
            }
            if (near) standDown(s);
        }
        for (Formation f : formations) {
            if (!f.alive) continue;
            if (f.state == MOVING) move(f, dt);
            else if (f.state == RAIL) rail(f, dt);
            if (f.strength() <= Math.max(4, f.start / 10)) {
                if (f.strength() == 0) {
                    f.alive = false;
                    w.news.headline(f.name + " has been destroyed", f.hq.x, f.hq.y);
                    continue;
                }
                if (f.state != BROKEN && !travelling(f)) {
                    f.state = BROKEN;
                    w.news.radio(f.name + " is broken and falling back", f.hq.x, f.hq.y, News.LOSS);
                }
            }
            if (f.state == HOLDING || f.state == BROKEN) {
                tanks(f);
                artillery(f, dt);
            }
        }
        aggregate(dt);
        commandT -= dt;
        if (commandT <= 0) {
            commandT = 30;
            command();
        }
    }

    /** Tanks leave the formation for the biggest fight near it, and come back when it's over. */
    private void tanks(Formation f) {
        Content.VType tank = w.c.vtype("tank");
        if (tank == null || w.strategic) return;
        int out = 0;
        for (int v = 0; v < w.veh.top; v++) if (w.veh.state[v] != Vehicles.V_NONE && w.veh.home[v] == f.hq.id && w.veh.type[v] == tank.index) out++;
        f.tanksOut = out;
        if (out >= 4 || f.hq.vpool[tank.index] <= 0) return;
        Dispatch.Incident best = null;
        for (Dispatch.Incident in : w.disp.incidents) {
            if (!in.open || in.zombies < 25) continue;
            float dx = in.x - f.hq.x, dy = in.y - f.hq.y;
            if (dx * dx + dy * dy > 3000 * 3000) continue;
            if (best == null || in.zombies > best.zombies) best = in;
        }
        if (best == null) return;
        int n = w.r.roads.nearestNode(f.hq.x, f.hq.y, 600);
        if (n < 0) return;
        f.hq.vpool[tank.index]--;
        int v = w.veh.add(tank, w.r.roads.nx[n], w.r.roads.ny[n], 0, f.hq.id);
        w.veh.homeX[v] = f.hq.x;
        w.veh.homeY[v] = f.hq.y;
        w.veh.driveTo(v, best.x, best.y, 60);
    }

    /** Guns: every so often a salvo on the biggest mass of the dead in range, away from the living. */
    private void artillery(Formation f, float dt) {
        if (f.t.guns <= 0) return;
        f.gunT -= dt;
        if (f.gunT > 0) return;
        f.gunT = 20;
        float range = 7000;
        int best = -1, bz = 0;
        Sectors sec = w.sec;
        float cs = Region.SECT * Tile.SIZE;
        int sx0 = Math.max(0, (int) ((f.hq.x - range) / cs)), sx1 = Math.min(sec.sw - 1, (int) ((f.hq.x + range) / cs));
        int sy0 = Math.max(0, (int) ((f.hq.y - range) / cs)), sy1 = Math.min(sec.sh - 1, (int) ((f.hq.y + range) / cs));
        for (int sy = sy0; sy <= sy1; sy++)
            for (int sx = sx0; sx <= sx1; sx++) {
                int s = sy * sec.sw + sx;
                int z = sec.zIn[s] + w.sp.secZ[s];
                if (z < 60 || sec.popIn[s] > 300 || w.sp.secCiv[s] + w.sp.secCop[s] + w.sp.secSol[s] > 0) continue;
                if (z > bz) {
                    bz = z;
                    best = s;
                }
            }
        if (best < 0) return;
        float tx = sec.cx(best), ty = sec.cy(best);
        Content.Weapon gun = w.c.weapon("tank_gun");
        if (!w.strategic && (sec.hot[best] > 0 || sec.cam[best])) {
            for (int k = 0; k < f.t.guns; k++) {
                float ox = (Rng.unit(w.seed, w.tick, k * 2L) - 0.5f) * 220, oy = (Rng.unit(w.seed, w.tick, k * 2L + 1) - 0.5f) * 220;
                if (w.sp.zombieCentroid(w.e, tx, ty, 300, tmp)) {
                    ox += tmp[0] - tx;
                    oy += tmp[1] - ty;
                }
                if (w.sp.livingNear(w.e, tx + ox, ty + oy, 90, 1) > 0) continue;
                w.combat.blast(tx + ox, ty + oy, 70, gun != null ? gun.blastDmg : 400, -1, Ents.SOLDIER);
            }
        } else {
            int kill = Math.min(sec.zIn[best], f.t.guns * 6);
            sec.zIn[best] -= kill;
            w.stats.killsBySide[2] += kill;
            w.stats.zombieKills += kill;
            f.kills += kill;
        }
        w.stats.artillery++;
        if (w.stats.artillery <= 3) w.news.radio(f.name + ": fire mission on a mass of the dead", tx, ty, News.COMMAND);
    }

    /** Fights nobody is watching: the people at each station and formation against the dead counted near it. */
    private void aggregate(float dt) {
        float kill = w.c.rule("aggregateKill", 0.02f), loss = w.c.rule("aggregateLoss", 0.0008f);
        Sectors sec = w.sec;
        for (Region.Facility f : w.r.facilities) {
            if (f.pool == null || !w.strat.online(f.id)) continue;
            boolean hq = f.kind == Region.F_HQ;
            Formation fm = hq ? formationOf(f) : null;
            if (fm != null && (travelling(fm) || !fm.alive)) continue;
            int faction = hq ? fm.faction : f.kind == Region.F_POLICE || f.kind == Region.F_PRISON ? Ents.COP : Ents.SOLDIER;
            int armed = armedAt(f, faction);
            if (armed <= 0) continue;
            Content.VType tankT = w.c.vtype("tank");
            if (tankT != null && f.vpool != null) armed += f.vpool[tankT.index] * 8; // a tank fights like a squad
            int rad = w.strategic ? (hq ? 4 : 3) : (hq ? 2 : 1);
            int s0 = w.r.sectorOfPx(f.x, f.y), sx = s0 % sec.sw, sy = s0 / sec.sw;
            int engaged = 0;
            for (int dy = -rad; dy <= rad; dy++)
                for (int dx = -rad; dx <= rad; dx++) {
                    int x = sx + dx, y = sy + dy;
                    if (x < 0 || y < 0 || x >= sec.sw || y >= sec.sh) continue;
                    int s = y * sec.sw + x;
                    if (sec.zIn[s] > 0 && sec.hot[s] <= 0) engaged++;
                }
            if (engaged == 0) continue;
            float share = armed / (float) engaged;
            float ammoK = f.ammo > 0 ? 1 : 0.25f;
            for (int dy = -rad; dy <= rad; dy++)
                for (int dx = -rad; dx <= rad; dx++) {
                    int x = sx + dx, y = sy + dy;
                    if (x < 0 || y < 0 || x >= sec.sw || y >= sec.sh) continue;
                    int s = y * sec.sw + x;
                    int z = sec.zIn[s];
                    if (z <= 0 || sec.hot[s] > 0) continue;
                    float dist = 1 + Math.max(Math.abs(dx), Math.abs(dy)) * 0.5f;
                    int k = Math.min(z, stoch(share * kill * dt * ammoK / dist, s * 17L + f.id));
                    sec.zIn[s] -= k;
                    sec.bodies[s] += k;
                    w.stats.killsBySide[faction == Ents.COP ? 1 : 2] += k;
                    w.stats.zombieKills += k;
                    if (fm != null) fm.kills += k;
                    f.ammo = Math.max(0, f.ammo - k * 10);
                    // and the cost: only the dead in contact (a few for each defender) can reach anyone
                    float contact = Math.min(z, share * 6);
                    int lost = Math.min(armed, stoch(contact * loss * dt / dist, s * 19L + f.id));
                    for (int l = 0; l < lost; l++) loseOne(f, faction, s);
                    armed -= lost;
                    if (fm != null) fm.losses += lost;
                    if (armed <= 0) return;
                }
        }
        // formations and stations near a horde fight it too
        for (Strategic.Horde h : w.strat.hordes) {
            if (!h.alive) continue;
            for (Formation fm : formations) {
                if (!fm.alive || travelling(fm)) continue;
                float dx = h.x - fm.hq.x, dy = h.y - fm.hq.y;
                if (dx * dx + dy * dy > 1500 * 1500) continue;
                int armed = armedAt(fm.hq, fm.faction);
                Content.VType tankT = w.c.vtype("tank");
                if (tankT != null) armed += fm.hq.vpool[tankT.index] * 8;
                if (armed <= 0) continue;
                int k = Math.min(h.count, stoch(armed * kill * dt * (fm.hq.ammo > 0 ? 1 : 0.25f), h.id * 7L + fm.id));
                h.count -= k;
                fm.kills += k;
                w.stats.killsBySide[2] += k;
                w.stats.zombieKills += k;
                fm.hq.ammo = Math.max(0, fm.hq.ammo - k * 10);
                int lost = Math.min(armed, stoch(Math.min(h.count, armed * 6) * loss * dt * 0.5f, h.id * 11L + fm.id));
                for (int l = 0; l < lost; l++) loseOne(fm.hq, fm.faction, w.r.sectorOfPx(fm.hq.x, fm.hq.y));
                fm.losses += lost;
            }
        }
    }

    private void loseOne(Region.Facility f, int faction, int sector) {
        // the first armed type with anyone left
        for (Content.Unit u : w.c.units) {
            int k = u.kind == Content.COP ? Ents.COP : u.kind == Content.SOLDIER ? Ents.SOLDIER : -1;
            if (k != faction || f.pool[u.index] <= 0) continue;
            f.pool[u.index]--;
            w.stats.armedDeaths[faction]++;
            // most of the fallen get up again
            if (Rng.unit(w.seed, w.tick, sector * 13L + u.index) < 0.6f) w.sec.zIn[sector]++;
            return;
        }
    }

    private int stoch(float v, long salt) {
        int f = (int) v;
        return f + (Rng.unit(w.seed, w.tick, salt) < v - f ? 1 : 0);
    }

    // ------------------------------------------------------------------ the military command

    /** Sends formations to the worst places, keeping one back for the capital. */
    private void command() {
        if (w.roles.holds(Roles.PRESIDENT, -1) || w.roles.holds(Roles.MILITARY, -1)) {
            // the player commands; idle formations only defend where they stand
            return;
        }
        // the general plans with what's been reported, not the truth
        int[] z = w.command.reportedZ();
        for (Formation f : formations) if (f.task == -2) f.task = -1; // couldn't get there last time: try again
        List<Region.Settlement> threats = new ArrayList<Region.Settlement>();
        for (Region.Settlement s : w.r.settlements)
            if ((s.kind == Region.S_CITY || s.kind == Region.S_TOWN) && z[s.id] > 150 && !w.command.isAbandoned(s.id)) threats.add(s);
        // the worst first: more of the dead, more people at stake
        java.util.Collections.sort(threats, new java.util.Comparator<Region.Settlement>() {
            public int compare(Region.Settlement a, Region.Settlement b) {
                float sa = w.command.repZ[a.id] + w.stats.popBySettlement[a.id] * 0.002f + (a.capital ? 5000 : 0);
                float sb = w.command.repZ[b.id] + w.stats.popBySettlement[b.id] * 0.002f + (b.capital ? 5000 : 0);
                return Float.compare(sb, sa);
            }
        });
        for (Region.Settlement s : threats) {
            int want = 1 + z[s.id] / 1500;
            int have = 0;
            for (Formation f : formations) if (f.alive && f.task == s.id && f.state != BROKEN) have++;
            while (have < want) {
                Formation best = null;
                float bd = Float.MAX_VALUE;
                for (Formation f : formations) {
                    if (!f.alive || f.task != -1 || f.state == BROKEN || f.player || f.faction != Ents.SOLDIER && z[s.id] > 600) continue;
                    float dx = f.hq.x - s.cx * 16f, dy = f.hq.y - s.cy * 16f, d = dx * dx + dy * dy;
                    if (d < bd) {
                        bd = d;
                        best = f;
                    }
                }
                if (best == null) return;
                // hold between the infection and the rest of the town
                float[] p = infectionCentre(s);
                float tx = p[0], ty = p[1];
                if (order(best, tx, ty)) {
                    best.task = s.id;
                    w.news.radio("Command: " + best.name + " ordered to " + s.name, tx, ty, News.COMMAND);
                    have++;
                } else best.task = -2;
            }
        }
        // formations whose town is quiet again go back to waiting
        for (Formation f : formations) {
            if (!f.alive || f.task < 0 || f.player) continue;
            if (z[f.task] < 30) f.task = -1;
        }
    }

    /** A fire mission ordered by the player: the nearest battery in range shells (x, y). False if none can. */
    public boolean fireMission(float x, float y) {
        Formation best = null;
        float bd = 7000 * 7000;
        for (Formation f : formations) {
            if (!f.alive || f.t.guns <= 0 || f.state == MOVING) continue;
            float dx = f.hq.x - x, dy = f.hq.y - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = f;
            }
        }
        if (best == null) return false;
        Content.Weapon gun = w.c.weapon("tank_gun");
        int s = w.r.sectorOfPx(x, y);
        if (!w.strategic && (w.sec.hot[s] > 0 || w.sec.cam[s])) {
            for (int k = 0; k < best.t.guns; k++) {
                float ox = (Rng.unit(w.seed, w.tick, k * 2L + 40) - 0.5f) * 200, oy = (Rng.unit(w.seed, w.tick, k * 2L + 41) - 0.5f) * 200;
                w.combat.blast(x + ox, y + oy, 70, gun != null ? gun.blastDmg : 400, -1, Ents.SOLDIER);
            }
        } else {
            int kill = Math.min(w.sec.zIn[s], best.t.guns * 6);
            w.sec.zIn[s] -= kill;
            w.stats.killsBySide[2] += kill;
            w.stats.zombieKills += kill;
            best.kills += kill;
            // and the people sheltering there
            int lost = Math.min(w.sec.popIn[s], best.t.guns * 3);
            w.sec.popIn[s] -= lost;
            w.stats.civDeaths += lost;
        }
        w.stats.artillery++;
        w.news.radio(best.name + ": fire mission, shot out", x, y, News.COMMAND);
        return true;
    }

    public float[] infectionCentre(Region.Settlement s) {
        float sx = 0, sy = 0;
        int n = 0;
        for (int k = 0; k < w.sec.n; k++) {
            if (w.sec.settlement[k] != s.id) continue;
            int z = w.sec.zIn[k] + w.sp.secZ[k];
            if (z <= 0) continue;
            sx += w.sec.cx(k) * z;
            sy += w.sec.cy(k) * z;
            n += z;
        }
        if (n == 0) return new float[]{s.cx * 16f, s.cy * 16f};
        return new float[]{sx / n, sy / n};
    }

    // ------------------------------------------------------------------ strategic fast-forward

    /** Everyone deployed goes back into their pools (the fights carry on as numbers). */
    void enterStrategic() {
        for (int q = 0; q < w.sq.list.size(); q++) {
            Squads.Squad s = w.sq.list.get(q);
            if (!s.alive || s.player) continue;
            standDown(s);
        }
        // tanks and aircraft go home too
        for (int v = 0; v < w.veh.top; v++) {
            if (w.veh.state[v] == Vehicles.V_NONE) continue;
            if (w.veh.crewN[v] > 0) continue;
            Content.VType t = w.veh.type(v);
            int h = w.veh.home[v];
            if (t.flies) {
                if (w.veh.flying(v)) {
                    if (w.veh.role[v] == Vehicles.R_STRIKE) w.veh.state[v] = Vehicles.V_NONE;
                    else {
                        w.veh.x[v] = w.veh.homeX[v];
                        w.veh.y[v] = w.veh.homeY[v];
                        w.veh.state[v] = Vehicles.V_PARKED;
                        w.veh.alt[v] = 0;
                        w.veh.fuel[v] = 1;
                    }
                }
                continue;
            }
            if (h >= 0 && w.veh.usable(v)) {
                Region.Facility f = w.r.facilities.get(h);
                ensurePools(f);
                f.vpool[t.index]++;
            }
            w.veh.state[v] = Vehicles.V_NONE;
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        if (cam.zoom > 0.6f) return;
        float s = cam.zoom < 0.08f ? 9 : 12;
        // the front: a line through formations holding near each other
        for (Formation a : formations) {
            if (!a.alive || a.state != HOLDING) continue;
            for (Formation b : formations) {
                if (b.id <= a.id || !b.alive || b.state != HOLDING) continue;
                float dx = a.hq.x - b.hq.x, dy = a.hq.y - b.hq.y;
                if (dx * dx + dy * dy > 4500 * 4500) continue;
                g.color(0xB0FFD040);
                g.line(cam.toScreenX(a.hq.x), cam.toScreenY(a.hq.y), cam.toScreenX(b.hq.x), cam.toScreenY(b.hq.y), 3);
            }
        }
        for (Formation f : formations) {
            if (!f.alive) continue;
            float x = cam.toScreenX(f.hq.x), y = cam.toScreenY(f.hq.y);
            if (x < -40 || y < -40 || x > cam.sw + 40 || y > cam.sh + 40) continue;
            if (f.state == RAIL && f.route != null) {
                // a troop train: carriages behind the engine, back along the line
                RoadGraph rg = w.r.rails;
                int back = Math.max(0, f.ri);
                float px = f.hq.x, py = f.hq.y;
                float bx = rg.nx[f.route[back]], by = rg.ny[f.route[back]];
                float dx = px - bx, dy = py - by, dl = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
                float carz = Math.max(3, 26 * cam.zoom);
                for (int k = 0; k < 6; k++) {
                    float cx = cam.toScreenX(px - dx / dl * k * 34), cy = cam.toScreenY(py - dy / dl * k * 34);
                    g.color(k == 0 ? 0xFF202020 : 0xFF4E5A3A);
                    g.fillRect(cx - carz / 2, cy - carz / 3, carz, carz * 0.66f);
                }
            }
            if ((f.state == MOVING || f.state == RAIL) && f.route != null) {
                // an arrow to where it's going
                float tx = cam.toScreenX(f.gx), ty = cam.toScreenY(f.gy);
                float dx = tx - x, dy = ty - y, dl = (float) Math.sqrt(dx * dx + dy * dy);
                if (dl > s * 2) {
                    g.color(0xB0FFFFFF);
                    g.line(x, y, tx, ty, 2);
                    float ux = dx / dl, uy = dy / dl, ah = Math.min(14, dl * 0.3f);
                    float[] head = {tx, ty, tx - ux * ah - uy * ah * 0.5f, ty - uy * ah + ux * ah * 0.5f, tx - ux * ah + uy * ah * 0.5f, ty - uy * ah - ux * ah * 0.5f};
                    g.fillPoly(head, 3);
                }
            }
            // a unit symbol: a box, coloured by side, marked by kind
            int col = f.faction == Ents.COP ? 0xFF3B6FD8 : 0xFF6E8A48;
            if (f.state == BROKEN) col = 0xFF8A5040;
            g.color(0xFF101010);
            g.fillRect(x - s * 1.4f - 1.5f, y - s - 1.5f, s * 2.8f + 3, s * 2 + 3);
            g.color(col);
            g.fillRect(x - s * 1.4f, y - s, s * 2.8f, s * 2);
            // NATO-style marks: infantry a cross, armour an oval track, artillery a dot, marines a cross and an anchor bar,
            // police a P; above the box, its size (II battalion, X brigade, I company or battery)
            g.color(0xFFFFFFFF);
            String id = f.t.id;
            if (id.equals("armour")) {
                g.strokeRect(x - s * 0.9f, y - s * 0.45f, s * 1.8f, s * 0.9f, 1.5f);
                g.strokeCircle(x - s * 0.45f, y, s * 0.4f, 1.2f);
                g.strokeCircle(x + s * 0.45f, y, s * 0.4f, 1.2f);
            } else if (id.equals("artillery")) g.fillCircle(x, y, s * 0.35f);
            else if (id.equals("police_reserve")) g.text("P", x, y + s * 0.4f, s * 1.1f, Gfx.ALIGN_CENTER, true);
            else {
                g.line(x - s * 1.4f, y - s, x + s * 1.4f, y + s, 1.5f);
                g.line(x - s * 1.4f, y + s, x + s * 1.4f, y - s, 1.5f);
                if (id.equals("marines")) g.fillRect(x - s * 0.8f, y + s * 0.55f, s * 1.6f, s * 0.25f);
            }
            String echelon = id.equals("guard_brigade") ? "X" : id.equals("artillery") || id.equals("police_reserve") ? "I" : "II";
            g.color(0xFF101010);
            g.text(echelon, x + 1, y - s - 2, s, Gfx.ALIGN_CENTER, true);
            g.color(0xFFFFFFFF);
            g.text(echelon, x, y - s - 3, s, Gfx.ALIGN_CENTER, true);
            // strength: a bar under the box
            float frac = f.start > 0 ? Math.min(1, f.strength() / (float) f.start) : 1;
            g.color(0xFF101010);
            g.fillRect(x - s * 1.4f, y + s + 2, s * 2.8f, 3);
            g.color(frac > 0.6f ? 0xFF60D060 : frac > 0.3f ? 0xFFE0C040 : 0xFFE04040);
            g.fillRect(x - s * 1.4f, y + s + 2, s * 2.8f * frac, 3);
            if (view.selectedFormation == f) {
                g.color(0xFFFFE040);
                g.strokeRect(x - s * 1.4f - 4, y - s - 4, s * 2.8f + 8, s * 2 + 8, 2);
            }
            if (cam.zoom > 0.05f || f.player) {
                g.color(0xE0FFFFFF);
                g.text(shortName(f) + "  " + f.strength(), x, y + s + 16, 10, Gfx.ALIGN_CENTER, false);
            }
        }
    }

    static String shortName(Formation f) {
        String n = f.name;
        return n.length() > 26 ? n.substring(0, 26) : n;
    }

    public Formation formationAt(float x, float y, float rad) {
        Formation best = null;
        float bd = rad * rad;
        for (Formation f : formations) {
            if (!f.alive) continue;
            float dx = f.hq.x - x, dy = f.hq.y - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = f;
            }
        }
        return best;
    }

    public int total(int faction) {
        int n = 0;
        for (Region.Facility f : w.r.facilities) n += armedAt(f, faction);
        return n;
    }

    // ------------------------------------------------------------------ saves

    @Override
    public void save(DataOutputStream o) throws IOException {
        o.writeInt(nextFormationNo);
        o.writeFloat(commandT);
        o.writeInt(formations.size());
        for (Formation f : formations) {
            o.writeInt(f.t.index);
            o.writeUTF(f.name);
            o.writeInt(f.faction);
            o.writeInt(f.hq.id);
            o.writeInt(f.state);
            o.writeInt(f.route == null ? -1 : f.route.length);
            if (f.route != null) for (int n : f.route) o.writeInt(n);
            o.writeInt(f.ri);
            o.writeFloat(f.gx);
            o.writeFloat(f.gy);
            o.writeFloat(f.gunT);
            o.writeInt(f.start);
            o.writeInt(f.kills);
            o.writeInt(f.losses);
            o.writeInt(f.deployed);
            o.writeInt(f.home);
            o.writeInt(f.task);
            o.writeBoolean(f.alive);
            o.writeBoolean(f.outside);
            o.writeBoolean(f.player);
        }
        o.writeInt(freeSquads.size());
        for (int id : freeSquads) o.writeInt(id);
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        nextFormationNo = in.readInt();
        commandT = in.readFloat();
        int n = in.readInt();
        formations.clear();
        for (int k = 0; k < n; k++) {
            Formation f = new Formation();
            f.id = k;
            f.t = w.c.formations.get(in.readInt());
            f.name = in.readUTF();
            f.faction = in.readInt();
            f.hq = w.r.facilities.get(in.readInt());
            f.state = in.readInt();
            int rl = in.readInt();
            if (rl >= 0) {
                f.route = new int[rl];
                for (int i = 0; i < rl; i++) f.route[i] = in.readInt();
            }
            f.ri = in.readInt();
            f.gx = in.readFloat();
            f.gy = in.readFloat();
            f.gunT = in.readFloat();
            f.start = in.readInt();
            f.kills = in.readInt();
            f.losses = in.readInt();
            f.deployed = in.readInt();
            f.home = in.readInt();
            f.task = in.readInt();
            f.alive = in.readBoolean();
            f.outside = in.readBoolean();
            f.player = in.readBoolean();
            formations.add(f);
        }
        n = in.readInt();
        freeSquads.clear();
        for (int k = 0; k < n; k++) freeSquads.add(in.readInt());
    }
}
