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
        /** Handed to the AI general by the player (when the general doesn't command everyone). */
        public boolean released;
        /** What the general sent it to do: JOB_* and which (a horde's id, a sector). */
        public int job, jobId = -1;
        /** Game seconds with nothing to do (it goes home after a while), and since it was last given a job. */
        public float idleT, jobT;

        public int strength() {
            return hq.poolTotal() + deployed;
        }

        public int deployed;
    }

    public final List<Formation> formations = new ArrayList<Formation>();
    private final List<Integer> freeSquads = new ArrayList<Integer>();
    private int nextFormationNo = 1;
    private float commandT;
    public static final int JOB_NONE = 0, JOB_TOWN = 1, JOB_HORDE = 2, JOB_NEST = 3, JOB_HOME = 4, JOB_ROAD = 5;
    /**
     * Does the AI general command the army? Yes, unless the player is the President or the Military commander and
     * hasn't handed it over; then the general commands only the formations the player released.
     */
    public boolean generalCommands = true;
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
        // the yard: open ground by the facility; the vehicle waits on the nearest street reachable on foot
        float[] yard = w.r.openGroundNear(f.x, f.y, false);
        boolean car = t.vehicle != null && f.vpool[t.vehicle.index] > 0;
        float[] kerb = null;
        if (car) {
            // a junction on the road graph near the yard (vehicles drive between junctions); else the nearest street
            int n = w.r.roads.nearestNode(yard[0], yard[1], 400);
            if (n >= 0 && w.r.walkablePx(w.r.roads.nx[n], w.r.roads.ny[n])) kerb = new float[]{w.r.roads.nx[n], w.r.roads.ny[n]};
            else kerb = w.r.openGroundNear(yard[0], yard[1], true);
        }
        float sx = kerb != null ? kerb[0] : yard[0], sy = kerb != null ? kerb[1] : yard[1];
        int k = 0;
        for (Content.Unit u : t.units) {
            if (f.pool[u.index] <= 0) continue;
            f.pool[u.index]--;
            float x = sx, y = sy;
            for (int a = 0; a < 12; a++) {
                float ang = (k * 5 + a) * 0.9f, rad = 12 + (k % 3) * 5 + a * 3;
                float px = sx + (float) Math.cos(ang) * rad, py = sy + (float) Math.sin(ang) * rad;
                if (w.r.walkablePx(px, py)) {
                    x = px;
                    y = py;
                    break;
                }
            }
            int i = w.spawnUnit(u, x, y);
            w.sq.add(s, i);
            k++;
        }
        if (car) {
            f.vpool[t.vehicle.index]--;
            float vx = sx, vy = sy;
            int n = w.r.roads.nearestNode(sx, sy, 300);
            if (kerb == null && n >= 0) {
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
        int a = g.nearestNode(f.hq.x, f.hq.y, w.reach(2000)), b = g.nearestNode(x, y, w.reach(2000));
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
        float over = w.rule("troopTrainOver", 4000);
        if (dx * dx + dy * dy < over * over) return false;
        if (w.navy.airliftable(f, x, y)) return false; // flown instead (Navy)
        int a = rg.nearestNode(f.hq.x, f.hq.y, w.reach(700)), b = rg.nearestNode(x, y, w.reach(2500));
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
        w.news.count("trains", "The " + f.name + " is boarding a troop train|%d more formation{s} boarded troop trains", 1, f.hq.x, f.hq.y);
        w.stats.troopTrains++;
        return true;
    }

    /** Along the railway at train speed; at the end, on by road. A cut line (a blown bridge) puts them off early. */
    private void rail(Formation f, float dt) {
        RoadGraph g = w.r.rails;
        float budget = w.rule("trainSpeed", 90) * dt;
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

    /** The dead in the formation's sector or the eight round it, or a horde as close. */
    private boolean contact(Formation f) {
        Sectors sec = w.sec;
        int s0 = w.r.sectorOfPx(f.hq.x, f.hq.y);
        for (int d = -1; d < 8; d++) {
            int s = d < 0 ? s0 : sec.neighbour(s0, d);
            if (s >= 0 && sec.zIn[s] + w.sp.secZ[s] > 0) return true;
        }
        float near = 1.5f * Tile.SIZE * w.r.sect;
        for (Strategic.Horde h : w.strat.hordes)
            if (h.alive && Math.abs(h.x - f.hq.x) < near && Math.abs(h.y - f.hq.y) < near) return true;
        return false;
    }

    private void move(Formation f, float dt) {
        RoadGraph g = w.r.roads;
        float speed = w.rule("formationSpeed", f.t.speed), tactical = w.rule("formationTactical", 0);
        // (a national map: with the dead about, across country at a fighting pace, not a road march)
        if (tactical > 0 && contact(f)) speed = Math.min(speed, tactical);
        float budget = speed * dt * (w.weather.zombieSpeed() < 1 ? 0.85f : 1);
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
                w.news.radio("contact:" + f.id, f.name + ": contact on the road" + placeName(f.hq.x, f.hq.y) + ", deploying", f.hq.x, f.hq.y, News.COMMAND, 720);
                return;
            }
        }
        if (f.route == null || f.ri >= f.route.length - 1) {
            f.state = HOLDING;
            f.route = null;
            w.news.radio("pos:" + f.id, f.name + " in position" + placeName(f.hq.x, f.hq.y), f.hq.x, f.hq.y, News.COMMAND, 1440);
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
            if (!s.alive) continue;
            if (s.player || s.home < 0 || (s.order != Squads.O_IDLE && s.order != Squads.O_RETURN)) {
                s.idleT = 0;
                continue;
            }
            s.idleT += dt;
            Region.Facility f = w.r.facilities.get(s.home);
            float[] p = w.sq.where(s);
            float hx = p[0] - f.x, hy = p[1] - f.y;
            boolean hqFar = f.kind == Region.F_HQ && hx * hx + hy * hy > 1000f * 1000f;
            if (s.order == Squads.O_RETURN) {
                // a long way home, or a formation that has moved on: the squad rejoins it (as numbers)
                if (s.idleT > 240 || (hqFar && s.idleT > 20)) standDown(s);
                continue;
            }
            if (s.idleT < wait) continue;
            if (hqFar || s.idleT > 120) {
                standDown(s);
                continue;
            }
            if (s.mode != Squads.M_FOOT) continue;
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
                    // (said when it happens in a fight, not when a worn-out unit is back in barracks)
                    if (w.sp.zombiesNear(w.e, f.hq.x, f.hq.y, 1500, 1) > 0 || w.sec.zIn[w.r.sectorOfPx(f.hq.x, f.hq.y)] > 0)
                        w.news.radio(f.name + " is broken and falling back", f.hq.x, f.hq.y, News.LOSS);
                }
            }
            if (f.state == HOLDING || f.state == BROKEN) {
                tanks(f);
                artillery(f, dt);
                resupply(f, dt);
            }
        }
        aggregate(dt);
        commandT -= dt;
        if (commandT <= 0) {
            commandT = 30;
            command();
        }
    }

    /** A formation running low draws ammunition from a depot close behind it (a base, an armory, a forward base). */
    private void resupply(Formation f, float dt) {
        float want = Math.max(4000, f.start * 60f);
        if (f.hq.ammo >= want * 0.6f) return;
        Region.Facility best = null;
        float bd = 3000 * 3000;
        for (Region.Facility d : w.r.facilities) {
            if (d.kind != Region.F_BASE && d.kind != Region.F_ARMORY && d.kind != Region.F_FOB && d.kind != Region.F_AIRBASE && d.kind != Region.F_PORT) continue;
            if (d.ammo < 2000 || !w.strat.online(d.id)) continue;
            float dx = d.x - f.hq.x, dy = d.y - f.hq.y, dd = dx * dx + dy * dy;
            if (dd < bd) {
                bd = dd;
                best = d;
            }
        }
        if (best == null) return;
        float give = Math.min(best.ammo - 1000, Math.min(want - f.hq.ammo, 150 * dt));
        if (give <= 0) return;
        best.ammo -= give;
        f.hq.ammo += give;
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
        f.gunT = w.c.rule("gunEvery", 60);
        if (f.hq.ammo < f.t.guns * 20) return;
        float range = 7000;
        int best = -1;
        float bscore = 0;
        Sectors sec = w.sec;
        float cs = w.r.sect * Tile.SIZE;
        int sx0 = Math.max(0, (int) ((f.hq.x - range) / cs)), sx1 = Math.min(sec.sw - 1, (int) ((f.hq.x + range) / cs));
        int sy0 = Math.max(0, (int) ((f.hq.y - range) / cs)), sy1 = Math.min(sec.sh - 1, (int) ((f.hq.y + range) / cs));
        for (int sy = sy0; sy <= sy1; sy++)
            for (int sx = sx0; sx <= sx1; sx++) {
                int s = sy * sec.sw + sx;
                int z = sec.zIn[s] + w.sp.secZ[s];
                if (z < 150 || w.sp.secCiv[s] + w.sp.secCop[s] + w.sp.secSol[s] > 0) continue;
                // empty ground first; a street with people still sheltering only when the dead there far outnumber them
                int people = sec.popIn[s];
                if (people > 0 && z < people * 4) continue;
                float score = z / (1f + people * 0.5f);
                if (score > bscore) {
                    bscore = score;
                    best = s;
                }
            }
        if (best < 0) return;
        float tx = sec.cx(best), ty = sec.cy(best);
        Content.Weapon gun = w.c.weapon("tank_gun");
        f.hq.ammo -= f.t.guns * 20;
        if (!w.strategic && (sec.hot[best] > 0 || sec.cam[best])) {
            for (int k = 0; k < f.t.guns; k++) {
                float ox = (Rng.unit(w.seed, w.tick, k * 2L) - 0.5f) * 220, oy = (Rng.unit(w.seed, w.tick, k * 2L + 1) - 0.5f) * 220;
                if (w.sp.zombieCentroid(w.e, tx, ty, 300, tmp)) {
                    ox += tmp[0] - tx;
                    oy += tmp[1] - ty;
                }
                if (w.sp.livingNear(w.e, tx + ox, ty + oy, 90, 1) > 0) continue;
                w.fx.sound("whistle", tx + ox, ty + oy); // heard only: the shell's whistle before the blast
                w.combat.blast(tx + ox, ty + oy, 70, gun != null ? gun.blastDmg : 400, -1, Ents.SOLDIER);
            }
        } else {
            int kill = Math.min(sec.zIn[best], f.t.guns * 12);
            sec.zIn[best] -= kill;
            sec.bodies[best] += kill;
            w.stats.killsBySide[2] += kill;
            w.stats.zombieKills += kill;
            f.kills += kill;
            shellCivilians(best, f.t.guns, f.name);
        }
        w.stats.artillery++;
        int st = sec.settlement[best];
        String where = st >= 0 ? " round " + w.r.settlements.get(st).name : " in open country";
        w.news.count("guns:" + f.id, f.name + ": fire mission on a mass of the dead" + where + "|" + f.name + ": %d more fire mission{s}" + where, 1, tx, ty);
    }

    /** Shells landing on a street where people are still sheltering kill some of them: said out loud, not hidden. */
    private void shellCivilians(int s, int guns, String who) {
        int people = w.sec.popIn[s];
        if (people <= 0) return;
        int lost = Math.min(people, stoch(guns * 1.5f * Math.min(1, people / 400f), s * 23L + 7));
        if (lost <= 0) return;
        w.sec.popIn[w.sec.touch(s)] -= lost;
        w.sec.bodies[s] += lost;
        w.stats.civDeaths += lost;
        w.stats.shelledCivilians += lost;
        w.stats.lossBy[3] += lost;
        int st = w.sec.settlement[s];
        if (st >= 0) w.stats.lostBySettlement[st] += lost;
        w.roles.lostAt(s, lost);
        w.gov.trust -= lost * 0.02f;
        w.news.count("shelled:" + st, "Shelling " + (st >= 0 ? "in " + w.r.settlements.get(st).name : "in the country") + " killed %d {people} sheltering in their homes", lost, w.sec.cx(s), w.sec.cy(s));
    }

    /** Fights nobody is watching: the people at each station and formation against the dead counted near it. */
    private void aggregate(float dt) {
        float kill = w.c.rule("aggregateKill", 0.02f), loss = w.c.rule("aggregateLoss", 0.0008f);
        float rounds = w.c.rule("roundsPerKill", 4), findHalf = w.c.rule("findHalf", 0);
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
                    // (the dead are harder to find where they're few: the last of them take the longest)
                    float find = z / (z + findHalf);
                    int k = Math.min(z, stoch(share * kill * w.orders.killMul(fm) * dt * ammoK * find / dist, s * 17L + f.id));
                    sec.zIn[s] -= k;
                    sec.bodies[s] += k;
                    w.stats.killsBySide[faction == Ents.COP ? 1 : 2] += k;
                    w.stats.zombieKills += k;
                    if (fm != null) fm.kills += k;
                    f.ammo = Math.max(0, f.ammo - k * rounds);
                    // and the cost: only the dead in contact (a few for each defender) can reach anyone
                    float contact = Math.min(z, share * 6);
                    int lost = Math.min(armed, stoch(contact * loss * w.orders.lossMul(fm) * dt / dist, s * 19L + f.id));
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
                // a horde in the open is a target, but only its front ranks are in reach at once
                float reach = Math.min(armed, (float) Math.sqrt(h.count) * 12);
                int k = Math.min(h.count, stoch(reach * kill * dt * (fm.hq.ammo > 0 ? 1 : 0.25f), h.id * 7L + fm.id));
                h.count -= k;
                fm.kills += k;
                w.stats.killsBySide[2] += k;
                w.stats.zombieKills += k;
                fm.hq.ammo = Math.max(0, fm.hq.ammo - k * rounds);
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
            if (Rng.unit(w.seed, w.tick, sector * 13L + u.index) < 0.6f) w.sec.zIn[w.sec.touch(sector)]++;
            return;
        }
    }

    private int stoch(float v, long salt) {
        int f = (int) v;
        return f + (Rng.unit(w.seed, w.tick, salt) < v - f ? 1 : 0);
    }

    // ------------------------------------------------------------------ the military command

    /** May the AI general move this formation? */
    public boolean aiMay(Formation f) {
        if (f.player) return false;
        return generalCommands || f.released;
    }

    /** Is anything at all left to the AI general? */
    public boolean aiCommands() {
        if (generalCommands) return true;
        for (Formation f : formations) if (f.alive && f.released && !f.player) return true;
        return false;
    }

    /** The player hands the whole army to the general (or takes it back). */
    public void setGeneralCommands(boolean on) {
        if (generalCommands == on) return;
        generalCommands = on;
        w.news.radio(on ? "The general takes command of the army." : "The army waits for your orders.", -1, -1, News.COMMAND);
    }

    /** A job for the general: a town, a horde or a nest of the dead in the country. */
    private static final class Job {
        int kind, id, want, have;
        float x, y, score;
        String name;
    }

    /**
     * The general's plan, every half minute: every place the dead are known to be (towns by what's reported,
     * hordes and big nests in the country by air reconnaissance) gets formations in proportion to its size, the
     * nearest free ones first, each to its own part of the fight; formations whose job is done look for another,
     * and go home after a quiet while.
     */
    private void command() {
        int[] z = w.command.reportedZ();
        List<Job> jobs = new ArrayList<Job>();
        float per = w.c.rule("deadPerFormation", 2500);
        for (Region.Settlement s : w.r.settlements) {
            if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN && s.kind != Region.S_VILLAGE) continue;
            if (z[s.id] < 15) continue;
            // a city whose incident commander is the player gets the army when the player asks for it
            if (w.roles.holds(Roles.INCIDENT, s.id) && !icAsked(s.id)) continue;
            Job j = new Job();
            j.kind = JOB_TOWN;
            j.id = s.id;
            j.want = Math.min(s.capital ? 10 : 6, 1 + (int) (z[s.id] / per));
            j.x = s.cx * 16f;
            j.y = s.cy * 16f;
            // (a town its police have pulled out of is the army's to take: it comes first after the capital)
            j.score = z[s.id] + w.stats.popBySettlement[s.id] * 0.01f + (s.capital ? 20000 : 0) + (w.command.isAbandoned(s.id) ? 5000 : 0);
            j.name = s.name;
            jobs.add(j);
        }
        for (Strategic.Horde h : w.strat.hordes) {
            if (!h.alive || h.count < 400) continue;
            Job j = new Job();
            j.kind = JOB_HORDE;
            j.id = h.id;
            j.want = Math.min(6, 1 + (int) (h.count / (per * 1.5f)));
            // meet it on its way, between it and where it's going
            float tx = h.target >= 0 ? h.tx : h.x, ty = h.target >= 0 ? h.ty : h.y;
            j.x = h.x + (tx - h.x) * 0.35f;
            j.y = h.y + (ty - h.y) * 0.35f;
            j.score = h.count * 1.2f;
            j.name = "a horde of " + News.num(h.count);
            jobs.add(j);
        }
        // a town being evacuated whose road out the dead threaten: a formation holds it open
        for (Region.Settlement s : w.r.settlements) {
            if (!w.gov.evacuating(s.id) || w.stats.popBySettlement[s.id] < 500) continue;
            float[] p = w.strat.evacPoint(s.id);
            boolean threat = w.strat.corridorCut(s.id);
            for (Formation f : formations) if (f.alive && f.job == JOB_ROAD && f.jobId == s.id) threat = true;
            if (!threat) continue;
            Job j = new Job();
            j.kind = JOB_ROAD;
            j.id = s.id;
            j.want = 1;
            j.x = p[0];
            j.y = p[1];
            j.score = 15000 + w.stats.popBySettlement[s.id] * 0.01f;
            j.name = "the road out of " + s.name;
            jobs.add(j);
        }
        // nests: the dead massed in the country, outside any town (the biggest few, not next to each other)
        Sectors sec = w.sec;
        int nestMin = jobs.size() > 4 ? 600 : jobs.isEmpty() ? 60 : 250;
        for (int pass = 0; pass < 6; pass++) {
            int best = -1, bz = nestMin;
            // (only awake sectors hold any of the dead)
            for (int s = sec.idx.first(); s >= 0; s = sec.idx.next(s)) {
                if (sec.settlement[s] >= 0 || sec.zIn[s] <= bz) continue;
                boolean taken = false;
                for (Job j : jobs)
                    if (j.kind == JOB_NEST && Math.abs(j.id % sec.sw - s % sec.sw) <= 3 && Math.abs(j.id / sec.sw - s / sec.sw) <= 3) taken = true;
                if (taken) continue;
                bz = sec.zIn[s];
                best = s;
            }
            if (best < 0) break;
            int around = 0;
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++) {
                    int x = best % sec.sw + dx, y = best / sec.sw + dy;
                    if (x >= 0 && y >= 0 && x < sec.sw && y < sec.sh && sec.settlement[y * sec.sw + x] < 0) around += sec.zIn[y * sec.sw + x];
                }
            Job j = new Job();
            j.kind = JOB_NEST;
            j.id = best;
            j.want = Math.min(4, 1 + (int) (around / (per * 2)));
            j.x = sec.cx(best);
            j.y = sec.cy(best);
            j.score = around * 0.8f;
            j.name = "the dead massed in open country";
            jobs.add(j);
        }
        // formations whose job is over are free again
        for (Formation f : formations) {
            if (!f.alive || !aiMay(f)) continue;
            if (f.task == -2) f.task = -1;
            f.jobT += 30;
            boolean done = false;
            if (f.job == JOB_TOWN && f.task >= 0) done = z[f.task] < 20 && f.jobT > 120;
            else if (f.job == JOB_HORDE) {
                done = true;
                for (Job j : jobs) if (j.kind == JOB_HORDE && j.id == f.jobId) done = false;
            } else if (f.job == JOB_ROAD) {
                done = !w.gov.evacuating(f.jobId);
            } else if (f.job == JOB_NEST) {
                done = f.jobId < 0 || f.jobId >= sec.n || sec.zIn[f.jobId] < 20 && f.state == HOLDING;
            } else if (f.job == JOB_NONE && f.task >= 0) {
                // sent somewhere by someone else (the president, the player before handing over)
                done = z[f.task] < 20 && f.jobT > 120;
            }
            if (done) {
                f.task = -1;
                f.job = JOB_NONE;
                f.jobId = -1;
            }
            for (Job j : jobs)
                if (f.job == j.kind && f.jobId == j.id && f.state != BROKEN) j.have++;
                else if (f.job == JOB_NONE && j.kind == JOB_TOWN && f.task == j.id && f.state != BROKEN) j.have++;
        }
        java.util.Collections.sort(jobs, new java.util.Comparator<Job>() {
            public int compare(Job a, Job b) {
                return Float.compare(b.score, a.score);
            }
        });
        for (Job j : jobs) {
            int guard = 0;
            while (j.have < j.want && guard++ < 8) {
                Formation best = null;
                float bd = Float.MAX_VALUE;
                for (Formation f : formations) {
                    if (!f.alive || f.task != -1 || f.job != JOB_NONE && f.job != JOB_HOME || f.state == BROKEN || !aiMay(f)) continue;
                    if (f.faction != Ents.SOLDIER && j.kind != JOB_TOWN) continue;
                    if (f.hq.ammo <= 0 && f.t.guns == 0) continue;
                    float dx = f.hq.x - j.x, dy = f.hq.y - j.y, d = dx * dx + dy * dy;
                    if (d < bd) {
                        bd = d;
                        best = f;
                    }
                }
                if (best == null) break;
                float[] p = j.kind == JOB_TOWN ? frontFor(w.r.settlements.get(j.id), j.have) : new float[]{j.x, j.y};
                if (order(best, p[0], p[1])) {
                    best.job = j.kind;
                    best.jobId = j.id;
                    best.task = j.kind == JOB_TOWN ? j.id : -3;
                    best.jobT = 0;
                    best.idleT = 0;
                    w.news.count("ordered:" + j.kind + ":" + j.id, "Command: the " + best.name + " is sent against " + j.name + "|Command: %d more formation{s} sent against " + j.name, 1, p[0], p[1]);
                    j.have++;
                } else best.task = -2;
            }
        }
        // whoever is still free joins the biggest fights, each to a part of the line nobody holds yet
        if (!jobs.isEmpty()) {
            int ji = 0;
            for (Formation f : formations) {
                if (!f.alive || f.task != -1 || f.job != JOB_NONE || f.state == BROKEN || !aiMay(f) || travelling(f)) continue;
                if (f.hq.ammo <= 0 && f.t.guns == 0) continue;
                Job j = jobs.get(ji % Math.min(3, jobs.size()));
                ji++;
                if (j.have >= j.want * 3) continue;
                if (f.faction != Ents.SOLDIER && j.kind != JOB_TOWN) continue;
                float[] p = j.kind == JOB_TOWN ? frontFor(w.r.settlements.get(j.id), j.have) : new float[]{j.x, j.y};
                if (order(f, p[0], p[1])) {
                    f.job = j.kind;
                    f.jobId = j.id;
                    f.task = j.kind == JOB_TOWN ? j.id : -3;
                    f.jobT = 0;
                    j.have++;
                }
            }
        }
        // a formation that stopped short (contact on the road) and has cleared the way pushes on to its place in the line
        for (Formation f : formations) {
            if (!f.alive || f.job != JOB_TOWN || f.task < 0 || f.state != HOLDING || !aiMay(f) || f.jobT < 60) continue;
            int s = w.r.sectorOfPx(f.hq.x, f.hq.y);
            if (sec.zIn[s] + w.sp.secZ[s] > 30 || sec.settlement[s] == f.task) continue;
            int k = 0;
            for (Formation g : formations) if (g != f && g.alive && g.task == f.task && g.id < f.id) k++;
            float[] p = frontFor(w.r.settlements.get(f.task), k);
            float dx = p[0] - f.hq.x, dy = p[1] - f.hq.y;
            if (dx * dx + dy * dy > 1200 * 1200) order(f, p[0], p[1]);
        }
        // nothing to do for a long while: home to barracks (and outside units back over the border, see Recovery)
        for (Formation f : formations) {
            if (!f.alive || !aiMay(f)) continue;
            boolean idle = f.task == -1 && (f.job == JOB_NONE || f.job == JOB_HOME);
            f.idleT = idle ? f.idleT + 30 : 0;
            if (idle && f.job == JOB_NONE && f.idleT > w.c.rule("goHomeAfter", 720) && f.home >= 0 && !f.outside && !travelling(f)) {
                Region.Facility h = w.r.facilities.get(f.home);
                float dx = h.x - f.hq.x, dy = h.y - f.hq.y;
                if (dx * dx + dy * dy > 800 * 800 && order(f, h.x, h.y)) {
                    f.job = JOB_HOME;
                    w.news.count("home", "The " + f.name + " returns to barracks|%d more formation{s} returned to barracks", 1, h.x, h.y);
                }
            }
        }
    }

    /** " at Elm Creek" / " near Elm Creek" / "": where a point is, for the radio. */
    String placeName(float x, float y) {
        Region.Settlement s = w.r.settlementAt(x, y);
        if (s != null) return " at " + s.name;
        Region.Settlement best = null;
        float bd = 3000f * 3000f;
        for (Region.Settlement t : w.r.settlements) {
            if (t.kind != Region.S_CITY && t.kind != Region.S_TOWN && t.kind != Region.S_VILLAGE) continue;
            float dx = t.cx * 16f - x, dy = t.cy * 16f - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = t;
            }
        }
        return best == null ? "" : " near " + best.name;
    }

    /** Has the player, as this city's incident commander, asked for the army (a formation sent there at their request)? */
    private boolean icAsked(int st) {
        if (w.mil.asked(st)) return true;
        for (Formation f : formations)
            if (f.alive && f.task == st && f.job == JOB_NONE) {
                w.mil.playerAsked(st);
                return true;
            }
        return false;
    }

    /** A town has been declared clear: whoever the general sent there is free for another job. */
    public void townCleared(int st) {
        for (Formation f : formations) {
            if (!f.alive || f.task != st || !aiMay(f)) continue;
            f.task = -1;
            f.job = JOB_NONE;
            f.jobId = -1;
        }
    }

    /** Is an army formation (not broken) within reach of (x, y), holding or on the road: an escort for a column? */
    public boolean escortNear(float x, float y, float reach) {
        for (Formation f : formations) {
            if (!f.alive || f.faction != Ents.SOLDIER || f.state == BROKEN || f.state == AIRLIFT || f.state == RAIL) continue;
            float dx = f.hq.x - x, dy = f.hq.y - y;
            if (dx * dx + dy * dy < reach * reach) return true;
        }
        return false;
    }

    /** Where the k-th formation sent to a town takes its place: the worst streets first, one formation to each. */
    float[] frontFor(Region.Settlement s, int k) {
        Sectors sec = w.sec;
        int[] top = new int[k + 1];
        java.util.Arrays.fill(top, -1);
        for (int q : sec.idx.bySettlement[s.id]) {
            int z = sec.zIn[q] + w.sp.secZ[q];
            if (z <= 0) continue;
            for (int i = 0; i <= k; i++) {
                if (top[i] < 0 || z > sec.zIn[top[i]] + w.sp.secZ[top[i]]) {
                    for (int m = k; m > i; m--) top[m] = top[m - 1];
                    top[i] = q;
                    break;
                }
            }
        }
        for (int i = k; i >= 0; i--) if (top[i] >= 0) return new float[]{sec.cx(top[i]), sec.cy(top[i])};
        return infectionCentre(s);
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
                w.fx.sound("whistle", x + ox, y + oy);
                w.combat.blast(x + ox, y + oy, 70, gun != null ? gun.blastDmg : 400, -1, Ents.SOLDIER);
            }
        } else {
            int kill = Math.min(w.sec.zIn[s], best.t.guns * 6);
            w.sec.zIn[s] -= kill;
            w.stats.killsBySide[2] += kill;
            w.stats.zombieKills += kill;
            best.kills += kill;
            // and the people sheltering there
            shellCivilians(s, best.t.guns, best.name);
        }
        w.stats.artillery++;
        w.news.radio(best.name + ": fire mission, shot out", x, y, News.COMMAND);
        return true;
    }

    public float[] infectionCentre(Region.Settlement s) {
        float sx = 0, sy = 0;
        int n = 0;
        for (int k : w.sec.idx.bySettlement[s.id]) {
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

    private final float[] head = new float[6];

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        if (cam.zoom > 0.6f) return;
        float dp = cam.dp;
        // a NATO-style frame, about 18 dp wide
        float s = (cam.zoom < 0.08f ? 6.5f : 7.5f) * dp;
        // the front: a line through formations holding near each other
        for (Formation a : formations) {
            if (!a.alive || a.state != HOLDING) continue;
            for (Formation b : formations) {
                if (b.id <= a.id || !b.alive || b.state != HOLDING) continue;
                float dx = a.hq.x - b.hq.x, dy = a.hq.y - b.hq.y;
                if (dx * dx + dy * dy > 4500 * 4500) continue;
                float ax = cam.toScreenX(a.hq.x), ay = cam.toScreenY(a.hq.y), bx = cam.toScreenX(b.hq.x), by = cam.toScreenY(b.hq.y);
                g.color(0x90000000);
                g.line(ax, ay, bx, by, 5 * dp);
                g.color(0xE0FFD040);
                WorldView.dashedLine(g, ax, ay, bx, by, 2.6f * dp, 7 * dp);
            }
        }
        for (Formation f : formations) {
            if (!f.alive) continue;
            float x = cam.toScreenX(f.hq.x), y = cam.toScreenY(f.hq.y);
            if (x < -60 * dp || y < -60 * dp || x > cam.sw + 60 * dp || y > cam.sh + 60 * dp) continue;
            if (f.state == RAIL && f.route != null) {
                // a troop train: carriages behind the engine, back along the line
                RoadGraph rg = w.r.rails;
                int back = Math.max(0, f.ri);
                float px = f.hq.x, py = f.hq.y;
                float bx = rg.nx[f.route[back]], by = rg.ny[f.route[back]];
                float dx = px - bx, dy = py - by, dl = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
                float carz = Math.max(5 * dp, 26 * cam.zoom), spacing = Math.max(34 * cam.zoom, carz * 1.25f) / cam.zoom;
                for (int k = 0; k < 6; k++) {
                    float cx = cam.toScreenX(px - dx / dl * k * spacing), cy = cam.toScreenY(py - dy / dl * k * spacing);
                    g.color(0xFF0C0C0C);
                    g.fillRect(cx - carz / 2 - dp * 0.6f, cy - carz / 3 - dp * 0.6f, carz + dp * 1.2f, carz * 0.66f + dp * 1.2f);
                    g.color(k == 0 ? 0xFF2A2A2A : 0xFF6E6840);
                    g.fillRect(cx - carz / 2, cy - carz / 3, carz, carz * 0.66f);
                }
            }
            if ((f.state == MOVING || f.state == RAIL) && f.route != null) {
                // an arrow to where it's going
                float tx = cam.toScreenX(f.gx), ty = cam.toScreenY(f.gy);
                float dx = tx - x, dy = ty - y, dl = (float) Math.sqrt(dx * dx + dy * dy);
                if (dl > s * 3) {
                    float ux = dx / dl, uy = dy / dl, ah = 9 * dp;
                    g.color(0x90000000);
                    g.line(x + ux * s * 1.6f, y + uy * s * 1.6f, tx, ty, 4 * dp);
                    g.color(0xE8FFFFFF);
                    WorldView.dashedLine(g, x + ux * s * 1.6f, y + uy * s * 1.6f, tx - ux * ah, ty - uy * ah, 2 * dp, 6 * dp);
                    head[0] = tx;
                    head[1] = ty;
                    head[2] = tx - ux * ah - uy * ah * 0.55f;
                    head[3] = ty - uy * ah + ux * ah * 0.55f;
                    head[4] = tx - ux * ah + uy * ah * 0.55f;
                    head[5] = ty - uy * ah - ux * ah * 0.55f;
                    g.fillPoly(head, 3);
                }
            }
            // a unit symbol: a frame, coloured by side (police blue, army khaki), marked by kind
            int col = f.faction == Ents.COP ? 0xFF3B6FD8 : 0xFF8A8250;
            if (f.state == BROKEN) col = 0xFF8A5040;
            float hw = s * 1.3f, hh = s * 0.85f;
            g.color(0xF0101010);
            g.fillRect(x - hw - 1.5f * dp, y - hh - 1.5f * dp, hw * 2 + 3 * dp, hh * 2 + 3 * dp);
            g.color(col);
            g.fillRect(x - hw, y - hh, hw * 2, hh * 2);
            // NATO-style marks: infantry a cross, armour an oval track, artillery a dot, marines a cross and an anchor bar,
            // police a P; above the frame, its size (II battalion, X brigade, I company or battery)
            g.color(0xFFFFFFFF);
            float lw = Math.max(1.2f, 1.1f * dp);
            String id = f.t.id;
            if (id.equals("armour")) {
                g.strokeRect(x - hw * 0.65f, y - hh * 0.45f, hw * 1.3f, hh * 0.9f, lw);
            } else if (id.equals("artillery")) g.fillCircle(x, y, hh * 0.35f);
            else if (id.equals("police_reserve")) g.text("P", x, y + hh * 0.5f, hh * 1.4f, Gfx.ALIGN_CENTER, true);
            else {
                g.line(x - hw, y - hh, x + hw, y + hh, lw);
                g.line(x - hw, y + hh, x + hw, y - hh, lw);
                if (id.equals("marines")) g.fillRect(x - hw * 0.6f, y + hh * 0.5f, hw * 1.2f, hh * 0.25f);
            }
            String echelon = id.equals("guard_brigade") ? "X" : id.equals("artillery") || id.equals("police_reserve") ? "I" : "II";
            view.label(g, echelon, x, y - hh - 3.5f * dp, 9.5f, 0xFFFFFFFF, Gfx.ALIGN_CENTER, true);
            // strength: a bar under the frame
            float frac = f.start > 0 ? Math.min(1, f.strength() / (float) f.start) : 1;
            g.color(0xFF101010);
            g.fillRect(x - hw - dp * 0.5f, y + hh + 2.5f * dp, hw * 2 + dp, 3.5f * dp);
            g.color(frac > 0.6f ? 0xFF60D060 : frac > 0.3f ? 0xFFE0C040 : 0xFFE04040);
            g.fillRect(x - hw, y + hh + 3 * dp, hw * 2 * frac, 2.5f * dp);
            if (view.selectedFormation == f) {
                g.color(0xFFFFE040);
                g.strokeRect(x - hw - 4 * dp, y - hh - 4 * dp, hw * 2 + 8 * dp, hh * 2 + 8 * dp, 2 * dp);
            }
            if (cam.zoom > 0.05f && view.unitNames || f.player || view.selectedFormation == f) {
                String lab = shortName(f) + "  " + f.strength();
                float tw = g.textWidth(lab, 10 * dp, false), ly = y + hh + 17 * dp;
                if (view.place(x - tw / 2, ly - 10 * dp, x + tw / 2, ly + 3 * dp) || view.selectedFormation == f)
                    view.label(g, lab, x, ly, 10, 0xFFF0F0F0, Gfx.ALIGN_CENTER, false);
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
            o.writeBoolean(f.released);
            o.writeInt(f.job);
            o.writeInt(f.jobId);
            o.writeFloat(f.idleT);
            o.writeFloat(f.jobT);
        }
        o.writeInt(freeSquads.size());
        for (int id : freeSquads) o.writeInt(id);
        o.writeBoolean(generalCommands);
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
            int ft = in.readInt();
            f.t = w.c.formations.get(w.loadRemap != null ? w.loadRemap.formation(ft) : ft); // by the save's content index
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
            f.released = in.readBoolean();
            f.job = in.readInt();
            f.jobId = in.readInt();
            f.idleT = in.readFloat();
            f.jobT = in.readFloat();
            formations.add(f);
        }
        n = in.readInt();
        freeSquads.clear();
        for (int k = 0; k < n; k++) freeSquads.add(in.readInt());
        generalCommands = in.readBoolean();
    }
}
