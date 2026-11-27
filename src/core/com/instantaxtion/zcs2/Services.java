package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The air and emergency services that come out as people and vehicles: transport helicopters flying squads into a
 * fight, pulling out squads cut off from their vehicles and lifting people trapped among the dead; ambulances and
 * paramedics taking the wounded and the bitten to hospital (and with the bitten, the infection: whether they still
 * take them is the officials' call, or the player's); police checkpoints manned on a quarantined town's roads;
 * escorted evacuation corridors; buses with a police escort carrying people out of streets the dead hold; aid
 * agencies' convoys to the refugee camps; the army seizing vehicles under martial law; what the strains change in
 * how fights go; and civilian flights carrying the hidden bitten to other cities until the airport is closed.
 * Every part has a rule in content.ini that is off (0) by default, so a game without them is exactly as before.
 */
public final class Services extends Module {
    // ------------------------------------------------------------------ switches (read once: content is fixed for a world)
    final boolean airOn, ambOn, aidOn, checkOn, corrOn, rescueOn, seizeOn, strainOn, flightsOn;

    public Services(World w) {
        super(w);
        airOn = w.rule("airAssault", 0) > 0;
        ambOn = w.rule("ambulances", 0) > 0;
        aidOn = w.rule("aidConvoys", 0) > 0;
        checkOn = w.rule("checkpoints", 0) > 0;
        corrOn = w.rule("corridors", 0) > 0;
        rescueOn = w.rule("rescues", 0) > 0;
        seizeOn = w.rule("vehicleSeizure", 0) > 0;
        strainOn = w.rule("strainEffects", 0) > 0;
        flightsOn = w.rule("civilianFlights", 0) > 0;
        int n = Math.max(1, w.r.settlements.size());
        noBitten = new boolean[n];
        Content.Unit pm = w.c.unit("paramedic");
        Content.VType am = w.c.vtype("ambulance");
        unitMedic = pm != null ? pm.index : -1;
        vAmb = am != null ? am.index : -1;
        if (ambOn && unitMedic >= 0 && vAmb >= 0) {
            // ambulances and their crews at every hospital, by the people it serves
            for (Region.Facility f : w.r.facilities) {
                if (f.kind != Region.F_HOSPITAL) continue;
                w.forces.ensurePools(f);
                int res = f.settlement >= 0 ? w.r.settlements.get(f.settlement).residents : 0;
                int amb = 2 + Math.min(10, res / 60000);
                f.vpool[vAmb] = amb;
                f.pool[unitMedic] = amb * 2 + 2;
            }
        }
    }

    @Override
    public String id() {
        return "services";
    }

    private int stoch(float v, long salt) {
        int f = (int) v;
        return f + (Rng.unit(w.seed, w.tick, salt) < v - f ? 1 : 0);
    }

    private static float dist(float ax, float ay, float bx, float by) {
        float dx = ax - bx, dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private boolean live(int v, int uid) {
        return v >= 0 && v < w.veh.top && w.veh.uid[v] == uid && w.veh.usable(v);
    }

    private boolean ent(int i, int uid) {
        Ents e = w.e;
        return i >= 0 && i < e.top && e.uid[i] == uid && (e.loc[i] == Ents.FREE || e.loc[i] == Ents.ABOARD);
    }

    /** Open ground near (x, y), rmin..rmax away, clear of the dead: fields, parks, plazas and yards before streets. */
    float[] landing(float x, float y, float rmin, float rmax, long salt) {
        float[] fallback = null;
        for (int ring = 0; ring < 5; ring++) {
            float rad = rmin + (rmax - rmin) * ring / 4f;
            for (int a = 0; a < 12; a++) {
                float ang = (a + Rng.unit(w.seed, salt, ring) * 12) * 0.5236f;
                float px = x + (float) Math.cos(ang) * rad, py = y + (float) Math.sin(ang) * rad;
                if (px < 32 || py < 32 || px >= w.r.px() - 32 || py >= w.r.px() - 32) continue;
                if (w.act.sparse && !w.act.activePx(px, py)) continue;
                int t = w.r.tileAtPx(px, py);
                if (!Tile.walkable(t) || w.sp.zombiesNear(w.e, px, py, 110, 1) > 0) continue;
                boolean open = t == Tile.GRASS || t == Tile.FARM || t == Tile.PARK || t == Tile.PLAZA || t == Tile.LOT || t == Tile.YARD;
                if (open) return new float[]{px, py};
                if (fallback == null) fallback = new float[]{px, py};
            }
        }
        return fallback;
    }

    /** Someone aboard or on foot goes indoors in sector s: counted there again (the bitten among the bitten). */
    private void indoors(int m, int s) {
        Ents e = w.e;
        Sectors sec = w.sec;
        if (e.loc[m] == Ents.ABOARD) {
            if (e.vehicle[m] >= 0) w.veh.dropCrew(e.vehicle[m], m);
            e.loc[m] = Ents.FREE;
        }
        if (w.stats.resident(m) && e.kind[m] == Ents.CIV) {
            if ((e.flags[m] & Ents.F_INFECTED) != 0) sec.inf[sec.touch(s)] += 1;
            else sec.popIn[sec.touch(s)]++;
        }
        if (e.squad[m] >= 0) w.sq.removeMember(e.squad[m], m);
        e.remove(m);
    }

    /** One of a service's crew (a paramedic) goes back into its facility's pool (or, bitten, is lost). */
    private void crewHome(int m, int facility) {
        Ents e = w.e;
        if (e.loc[m] == Ents.ABOARD) {
            if (e.vehicle[m] >= 0) w.veh.dropCrew(e.vehicle[m], m);
            e.loc[m] = Ents.FREE;
        }
        if ((e.flags[m] & Ents.F_INFECTED) != 0) w.stats.crewDeaths++;
        else if (facility >= 0 && facility < w.r.facilities.size()) {
            Region.Facility f = w.r.facilities.get(facility);
            w.forces.ensurePools(f);
            f.pool[e.type[m]]++;
        }
        e.remove(m);
    }

    /** Takes someone from sector s's count onto the street at (x, y): the bitten among them by their share. */
    private int fromCount(int s, float x, float y, long salt) {
        Sectors sec = w.sec;
        boolean sick = sec.inf[s] >= 1 && Rng.unit(w.seed, w.tick + salt, s * 3L + 1) < sec.inf[s] / (sec.inf[s] + sec.popIn[s] + 1);
        if (!sick && sec.popIn[s] <= 0) return -1;
        int i = w.spawnCiv(x, y, w.c.unit("civilian"));
        if (sick) {
            sec.inf[sec.touch(s)] -= 1;
            w.e.flags[i] |= Ents.F_INFECTED;
            w.e.infT[i] = 60 + Rng.unit(w.seed, w.e.uid[i], 21) * 120;
        } else sec.popIn[sec.touch(s)]--;
        return i;
    }

    // ================================================================== air: assaults, extraction, rescue

    public static final int T_ASSAULT = 0, T_EXTRACT = 1, T_RESCUE = 2;
    static final String[] T_NAMES = {"Air assault", "Pulling out a squad", "Lifting people out"};
    static final int P_TO_PICKUP = 0, P_LOAD = 1, P_TO_DROP = 2, P_UNLOAD = 3;

    public static final class AirTask {
        public int v, uid, kind, phase, squad = -1, sector = -1, home = -1;
        public float px, py, tx, ty, t;
        public boolean player;
    }

    public final List<AirTask> air = new ArrayList<AirTask>();
    public int assaults, extractions, lifted;
    private float airT;

    /** The air task a helicopter is on, or null. */
    public AirTask airTask(int v) {
        for (AirTask a : air) if (a.v == v && a.uid == w.veh.uid[v]) return a;
        return null;
    }

    /** A transport helicopter: flies, carries people, has no gun of its own. */
    public boolean transport(int v) {
        Content.VType t = w.veh.type(v);
        return t.flies && t.seats > 0 && t.weapon == null;
    }

    private boolean freeHeli(int v) {
        Vehicles vh = w.veh;
        return vh.usable(v) && vh.state[v] == Vehicles.V_PARKED && transport(v) && vh.fuel[v] > 0.5f && vh.crewN[v] == 0 && airTask(v) == null
                && vh.role[v] != Vehicles.R_LIFE && !w.orders.drives(v) && w.orders.vorder(v) == null;
    }

    private int nearestHeli(float x, float y) {
        Vehicles vh = w.veh;
        float reach = w.rule("airReach", 9000);
        int best = -1;
        float bd = reach * reach;
        for (int v = 0; v < vh.top; v++) {
            if (!freeHeli(v)) continue;
            float dx = vh.x[v] - x, dy = vh.y[v] - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = v;
            }
        }
        return best;
    }

    /** The player's order for a transport helicopter: an air assault (or a lift out) at (x, y). False if it can't. */
    public boolean orderAir(int v, int kind, float x, float y) {
        Vehicles vh = w.veh;
        if (v < 0 || v >= vh.top || !vh.usable(v) || !transport(v)) return false;
        AirTask a = airTask(v);
        if (a != null) {
            if (vh.crewN[v] > 0) {
                // people aboard: they go where the player says now
                a.tx = x;
                a.ty = y;
                a.phase = P_TO_DROP;
                a.player = true;
                vh.driveTo(v, x, y, 0);
                return true;
            }
            air.remove(a);
        }
        a = new AirTask();
        a.v = v;
        a.uid = vh.uid[v];
        a.kind = kind == Orders.VO_EVAC ? T_EXTRACT : T_ASSAULT;
        a.home = vh.home[v];
        a.player = true;
        if (a.kind == T_ASSAULT) {
            a.tx = x;
            a.ty = y;
            startAssault(a);
        } else {
            a.px = x;
            a.py = y;
            a.tx = vh.homeX[v];
            a.ty = vh.homeY[v];
            a.phase = P_TO_PICKUP;
            vh.driveTo(v, x, y, 0);
        }
        air.add(a);
        w.news.radio(vh.type(v).name + " " + Names.callsign(Ents.SOLDIER, vh.uid[v]) + ": " + T_NAMES[a.kind].toLowerCase(), x, y, News.UNIT);
        return true;
    }

    /** An assault takes its squad aboard where the helicopter is if it stands at home, else flies home for one first. */
    private void startAssault(AirTask a) {
        Vehicles vh = w.veh;
        int v = a.v;
        if (vh.crewN[v] > 0 || dist(vh.x[v], vh.y[v], vh.homeX[v], vh.homeY[v]) < 120 || a.home < 0) {
            a.phase = P_LOAD;
            if (vh.state[v] != Vehicles.V_PARKED && vh.state[v] != Vehicles.V_ARRIVED) {
                a.px = vh.x[v];
                a.py = vh.y[v];
            }
        } else {
            a.phase = P_TO_PICKUP;
            a.px = vh.homeX[v];
            a.py = vh.homeY[v];
            vh.goHome(v);
        }
    }

    /** A squad from the helicopter's home spawned aboard it (its pool's soldiers). Null if there's nobody to send. */
    private Squads.Squad squadAboard(int v, int home) {
        Region.Facility f = home >= 0 && home < w.r.facilities.size() ? w.r.facilities.get(home) : null;
        if (f == null || f.pool == null) return null;
        Content.SquadT t = null;
        for (String id : new String[]{"rifle", "fireteam", "guard"}) {
            Content.SquadT q = w.c.squadT(id);
            if (q == null) continue;
            int have = 0;
            for (Content.Unit u : q.units) if (f.pool[u.index] > 0) have++;
            if (have >= 3) {
                t = q;
                break;
            }
        }
        if (t == null) return null;
        Vehicles vh = w.veh;
        int seats = vh.type(v).seats - vh.crewN[v];
        Squads.Squad s = w.sq.create(Ents.SOLDIER, f.id, t.name);
        s.template = t.index;
        s.task = -2; // (flown in: no vehicle of its own to go back to)
        w.act.touchPx(vh.x[v], vh.y[v]);
        for (Content.Unit u : t.units) {
            if (seats <= 0 || f.pool[u.index] <= 0) continue;
            f.pool[u.index]--;
            int i = w.spawnUnit(u, vh.x[v], vh.y[v]);
            w.sq.add(s, i);
            vh.board(v, i);
            seats--;
        }
        if (s.n == 0) {
            s.alive = false;
            return null;
        }
        Forces.Formation fm = w.forces.formationOf(f);
        if (fm != null) fm.deployed += s.n;
        s.order = Squads.O_HOLD;
        s.mode = Squads.M_FOOT;
        return s;
    }

    private void airAI() {
        Vehicles vh = w.veh;
        // fast reaction: a call with many of the dead and no soldiers near gets a squad flown in
        int min = (int) w.rule("airAssaultAt", 12);
        for (Dispatch.Incident in : w.disp.incidents) {
            if (!in.open || in.zombies < min) continue;
            if (w.gov.level < 1 && !in.army) continue;
            boolean covered = false;
            for (AirTask a : air) if (a.kind == T_ASSAULT && dist(a.tx, a.ty, in.x, in.y) < 900) covered = true;
            if (covered) continue;
            // (soldiers already there, or nearly: no need)
            int soldiers = 0;
            for (int q = 0; q < w.sq.list.size() && soldiers == 0; q++) {
                Squads.Squad s = w.sq.list.get(q);
                if (!s.alive || s.faction != Ents.SOLDIER) continue;
                float[] p = w.sq.where(s);
                if (dist(p[0], p[1], in.x, in.y) < 2500) soldiers++;
            }
            if (soldiers > 0) continue;
            int v = nearestHeli(in.x, in.y);
            if (v < 0) return;
            float[] lz = landing(in.x, in.y, 160, 420, in.id * 31L + 7);
            if (lz == null) continue;
            AirTask a = new AirTask();
            a.v = v;
            a.uid = vh.uid[v];
            a.kind = T_ASSAULT;
            a.home = vh.home[v];
            a.tx = lz[0];
            a.ty = lz[1];
            startAssault(a);
            air.add(a);
            assaults++;
            w.news.radio("Air assault: " + vh.type(v).name + " " + Names.callsign(Ents.SOLDIER, vh.uid[v]) + " is flying a squad in to the fight", lz[0], lz[1], News.COMMAND);
            return; // one a second
        }
        // soldiers cut off (no vehicle, the dead round them, far from home): pulled out by air
        for (int q = 0; q < w.sq.list.size(); q++) {
            Squads.Squad s = w.sq.list.get(q);
            if (!s.alive || s.player || s.n == 0 || s.mode != Squads.M_FOOT || s.faction != Ents.SOLDIER || s.home < 0) continue;
            if (s.vehicle >= 0 && w.veh.usable(s.vehicle)) continue;
            int lead = s.leader(w.e);
            if (lead < 0 || w.e.loc[lead] != Ents.FREE) continue;
            float x = w.e.x[lead], y = w.e.y[lead];
            Region.Facility hf = s.home >= 0 ? w.r.facilities.get(s.home) : null;
            if (hf != null && dist(x, y, hf.x, hf.y) < 1500) continue;
            int press = w.sp.zombiesNear(w.e, x, y, 160, 40);
            if (press < Math.max(6, s.n * 2) || w.sq.ammoShare(s) > 0.3f && press < s.n * 4) continue;
            boolean taken = false;
            for (AirTask a : air) if (a.squad == s.id) taken = true;
            if (taken) continue;
            int v = nearestHeli(x, y);
            if (v < 0) return;
            float[] lz = landing(x, y, 60, 260, s.id * 17L + 3);
            if (lz == null) continue;
            AirTask a = new AirTask();
            a.v = v;
            a.uid = vh.uid[v];
            a.kind = T_EXTRACT;
            a.home = vh.home[v];
            a.squad = s.id;
            a.px = lz[0];
            a.py = lz[1];
            a.tx = vh.homeX[v];
            a.ty = vh.homeY[v];
            a.phase = P_TO_PICKUP;
            vh.driveTo(v, a.px, a.py, 0);
            air.add(a);
            extractions++;
            w.news.radio(s.name + " is cut off: a helicopter is coming to pull them out", lz[0], lz[1], News.COMMAND);
            return;
        }
        // people trapped in streets the dead hold, where it's being watched: lifted out by air
        if (!rescueOn) return;
        int s = trappedSector(true);
        if (s < 0) return;
        int v = nearestHeli(w.sec.cx(s), w.sec.cy(s));
        if (v < 0) return;
        float[] lz = landing(w.sec.cx(s), w.sec.cy(s), 40, 320, s * 13L + 11);
        if (lz == null) return;
        AirTask a = new AirTask();
        a.v = v;
        a.uid = vh.uid[v];
        a.kind = T_RESCUE;
        a.home = vh.home[v];
        a.sector = s;
        a.px = lz[0];
        a.py = lz[1];
        a.tx = vh.homeX[v];
        a.ty = vh.homeY[v];
        a.phase = P_TO_PICKUP;
        vh.driveTo(v, a.px, a.py, 0);
        air.add(a);
        w.news.radio("Rescue: a helicopter is going in for people trapped" + place(s), lz[0], lz[1], News.COMMAND);
    }

    /** A watched sector whose people are trapped by the dead (and no rescue on its way to it), or -1. */
    private int trappedSector(boolean byAir) {
        Sectors sec = w.sec;
        int best = -1, bz = 0;
        for (int s = sec.idx.first(); s >= 0; s = sec.idx.next(s)) {
            if (!sec.cam[s] && sec.hot[s] <= 0) continue;
            int z = sec.zIn[s] + w.sp.secZ[s];
            if (sec.popIn[s] < 12 || z < Math.max(25, sec.popIn[s] / 3)) continue;
            boolean going = false;
            for (AirTask a : air) if (a.sector == s) going = true;
            for (Rescue r : rescues) if (r.sector == s) going = true;
            if (going) continue;
            if (z > bz) {
                bz = z;
                best = s;
            }
        }
        return best;
    }

    private String place(int s) {
        int st = s >= 0 ? w.sec.settlement[s] : -1;
        return st >= 0 ? " in " + w.r.settlements.get(st).name : " in the countryside";
    }

    private void airTasks(float dt) {
        Vehicles vh = w.veh;
        Ents e = w.e;
        for (int k = air.size() - 1; k >= 0; k--) {
            AirTask a = air.get(k);
            int v = a.v;
            a.t += dt;
            if (!live(v, a.uid)) {
                // shot down or gone: whoever was aboard is on the ground (a wreck puts them out)
                air.remove(k);
                continue;
            }
            boolean down = vh.state[v] == Vehicles.V_ARRIVED || vh.state[v] == Vehicles.V_PARKED;
            switch (a.phase) {
                case P_TO_PICKUP:
                    if (down && dist(vh.x[v], vh.y[v], a.px, a.py) < 60) {
                        a.phase = P_LOAD;
                        a.t = 0;
                    } else if (vh.state[v] == Vehicles.V_PARKED && a.kind == T_ASSAULT) {
                        a.phase = P_LOAD;
                        a.t = 0;
                    } else if (!vh.flying(v) && !down) vh.driveTo(v, a.px, a.py, 0);
                    else if (down) vh.driveTo(v, a.px, a.py, 0);
                    break;
                case P_LOAD:
                    if (a.kind == T_ASSAULT) {
                        if (vh.crewN[v] == 0) {
                            Squads.Squad s = squadAboard(v, a.home);
                            if (s == null) {
                                if (a.player) w.news.radio("No soldiers at " + (a.home >= 0 ? w.r.facilities.get(a.home).name : "home") + " to fly in", vh.x[v], vh.y[v], News.UNIT);
                                air.remove(k);
                                continue;
                            }
                            a.squad = s.id;
                        }
                        a.phase = P_TO_DROP;
                        vh.driveTo(v, a.tx, a.ty, 0);
                    } else if (a.kind == T_EXTRACT) {
                        Squads.Squad s = a.squad >= 0 && a.squad < w.sq.list.size() ? w.sq.get(a.squad) : null;
                        boolean all = true;
                        if (s != null && s.alive) {
                            for (int j = 0; j < s.n; j++) {
                                int m = s.mem[j];
                                if (e.loc[m] != Ents.FREE) continue;
                                if (dist(e.x[m], e.y[m], vh.x[v], vh.y[v]) < 40) vh.board(v, m);
                                else {
                                    all = false;
                                    e.gx[m] = vh.x[v];
                                    e.gy[m] = vh.y[v];
                                    e.state[m] = 1;
                                }
                            }
                            s.gx = vh.x[v];
                            s.gy = vh.y[v];
                            s.order = Squads.O_MOVE;
                            s.path = null;
                        } else if (a.player) {
                            // the player's lift: anyone near (whole squads, and people)
                            all = loadNear(v, a.px, a.py);
                        }
                        if (all || a.t > 30 || vh.crewN[v] >= vh.type(v).seats) {
                            if (vh.crewN[v] == 0) {
                                vh.goHome(v);
                                air.remove(k);
                                continue;
                            }
                            a.phase = P_TO_DROP;
                            vh.driveTo(v, a.tx, a.ty, 0);
                        }
                    } else {
                        // people come out of the buildings to it, a few at a time, while the dead keep off
                        int s = a.sector;
                        boolean dead = w.sp.zombiesNear(e, vh.x[v], vh.y[v], 70, 1) > 0;
                        int seats = vh.type(v).seats;
                        for (int j = 0; j < 3 && !dead && vh.crewN[v] < seats; j++) {
                            int i = fromCount(s, vh.x[v] + (j - 1) * 8, vh.y[v] + 10, j * 7L + a.uid);
                            if (i < 0) break;
                            vh.board(v, i);
                            lifted++;
                        }
                        if (dead || a.t > 12 || vh.crewN[v] >= seats || w.sec.popIn[s] + w.sec.inf[s] < 1) {
                            if (vh.crewN[v] == 0) {
                                vh.goHome(v);
                                air.remove(k);
                                continue;
                            }
                            a.phase = P_TO_DROP;
                            vh.driveTo(v, a.tx, a.ty, 0);
                        }
                    }
                    break;
                case P_TO_DROP:
                    if (down && dist(vh.x[v], vh.y[v], a.tx, a.ty) < 60) a.phase = P_UNLOAD;
                    else if (down) vh.driveTo(v, a.tx, a.ty, 0);
                    break;
                case P_UNLOAD:
                    unload(a);
                    vh.goHome(v);
                    air.remove(k);
                    break;
                default:
            }
        }
    }

    /** The player's lift: whole squads standing near (x, y), then people, aboard. True when nobody near is left out. */
    private boolean loadNear(int v, float x, float y) {
        Ents e = w.e;
        Vehicles vh = w.veh;
        int seats = vh.type(v).seats;
        boolean all = true;
        for (int q = 0; q < w.sq.list.size(); q++) {
            Squads.Squad s = w.sq.list.get(q);
            if (!s.alive || s.mode != Squads.M_FOOT) continue;
            int lead = s.leader(e);
            if (lead < 0 || e.loc[lead] != Ents.FREE || dist(e.x[lead], e.y[lead], x, y) > 220) continue;
            if (s.n > seats - vh.crewN[v]) continue;
            for (int j = 0; j < s.n; j++) {
                int m = s.mem[j];
                if (e.loc[m] != Ents.FREE) continue;
                if (dist(e.x[m], e.y[m], vh.x[v], vh.y[v]) < 40) vh.board(v, m);
                else {
                    all = false;
                    e.gx[m] = vh.x[v];
                    e.gy[m] = vh.y[v];
                    e.state[m] = 1;
                }
            }
            s.gx = vh.x[v];
            s.gy = vh.y[v];
            s.order = Squads.O_MOVE;
            s.path = null;
        }
        int c = w.sp.cellOf(x, y);
        for (int j = w.sp.lStart[c]; j < w.sp.lEnd[c] && vh.crewN[v] < seats; j++) {
            int i = w.sp.lList[j];
            if (e.loc[i] != Ents.FREE || e.kind[i] != Ents.CIV || i == w.playerEnt || (e.flags[i] & (Ents.F_COMPANION | Ents.F_CREW)) != 0) continue;
            if (dist(e.x[i], e.y[i], vh.x[v], vh.y[v]) < 60) vh.board(v, i);
        }
        return all;
    }

    /** Everyone aboard gets out where it landed: the squad holds there (or goes home), people are taken in. */
    private void unload(AirTask a) {
        Vehicles vh = w.veh;
        Ents e = w.e;
        int v = a.v;
        int s0 = w.r.sectorOfPx(vh.x[v], vh.y[v]);
        // people carried out go indoors where they're set down (counted there)
        for (int k = vh.crewN[v] - 1; k >= 0; k--) {
            int m = vh.crew[v][k];
            if (e.kind[m] == Ents.CIV && e.squad[m] < 0) indoors(m, s0);
        }
        vh.alightAll(v);
        if (a.squad >= 0 && a.squad < w.sq.list.size()) {
            Squads.Squad s = w.sq.get(a.squad);
            if (s.alive) {
                if (a.kind == T_ASSAULT) {
                    s.task = -1;
                    w.sq.order(s, Squads.O_HOLD, a.tx, a.ty);
                    w.news.radio(s.name + ": on the ground, moving to the fight", a.tx, a.ty, News.UNIT);
                } else w.sq.goHome(s);
            }
        }
    }

    // ================================================================== ambulances and paramedics

    static final int A_DRIVE = 0, A_SCENE = 1, A_BACK = 2;

    public static final class Ambulance {
        public int v, uid, hospital, patient, puid, phase;
        public int[] medic = {-1, -1}, muid = new int[2];
        public float t, px, py;
    }

    public final List<Ambulance> ambs = new ArrayList<Ambulance>();
    /** Per settlement: the ambulances no longer take the bitten (the officials' or the player's decision). */
    public final boolean[] noBitten;
    public int carried, carriedBitten;
    /** The sector the last bitten patient was taken into (a hospital's: the infection goes with them). */
    public int bittenInto = -1;
    private final int unitMedic, vAmb;
    private float ambT;

    /** Do the town's ambulances (and its hospital) still take the bitten? */
    public boolean admitsBitten(int st) {
        return st < 0 || st >= noBitten.length || !noBitten[st];
    }

    /** The decision: ambulances stop (or start again) taking the bitten to the hospital in town st. */
    public void setAdmitBitten(int st, boolean admit, boolean player) {
        if (st < 0 || st >= noBitten.length || noBitten[st] == !admit) return;
        noBitten[st] = !admit;
        Region.Settlement s = w.r.settlements.get(st);
        String what = admit ? "the hospital in " + s.name + " takes the bitten again" : "ambulances in " + s.name + " will no longer take the bitten to the hospital; they are to be kept apart at home";
        if (player) w.news.headline("Order: " + what + ".", s.cx * 16f, s.cy * 16f);
        else {
            Command.Official o = w.command.official(Command.MAYOR, st);
            w.news.headline((o != null ? w.command.name(o) + " (" + s.name + "): " : "") + what + ".", s.cx * 16f, s.cy * 16f);
        }
    }

    private boolean onCall(int i) {
        for (Ambulance a : ambs) if (a.patient == i && a.puid == w.e.uid[i]) return true;
        return false;
    }

    private void ambulanceAI(float dt) {
        Ents e = w.e;
        Vehicles vh = w.veh;
        float reach = w.rule("ambulanceReach", 3500);
        for (Region.Facility f : w.r.facilities) {
            if (f.kind != Region.F_HOSPITAL || f.vpool == null || f.vpool[vAmb] <= 0 || f.pool[unitMedic] < 2 || !w.strat.online(f.id)) continue;
            if (w.act.sparse && !w.act.activePx(f.x, f.y)) continue;
            // the nearest wounded or bitten person on the street, in a quiet spot
            int best = -1;
            float bd = reach * reach;
            boolean bitten = admitsBitten(f.settlement);
            for (int i = 0; i < e.top; i++) {
                if (e.loc[i] != Ents.FREE || e.kind[i] != Ents.CIV || i == w.playerEnt) continue;
                if ((e.flags[i] & (Ents.F_COMPANION | Ents.F_CREW)) != 0 || !w.stats.resident(i)) continue;
                boolean inf = (e.flags[i] & Ents.F_INFECTED) != 0;
                boolean hurt = e.hp[i] < e.maxHp[i] * 0.75f;
                if (!(hurt || inf && bitten) || inf && !bitten) continue;
                float dx = e.x[i] - f.x, dy = e.y[i] - f.y, d = dx * dx + dy * dy;
                if (d >= bd || onCall(i) || w.sp.zombiesNear(e, e.x[i], e.y[i], 140, 1) > 0) continue;
                bd = d;
                best = i;
            }
            if (best < 0) continue;
            f.vpool[vAmb]--;
            int node = w.r.roads.nearestNode(f.x, f.y, 600);
            float[] yard = node >= 0 ? new float[]{w.r.roads.nx[node], w.r.roads.ny[node]} : w.r.openGroundNear(f.x, f.y, true);
            int v = vh.add(w.c.vtypes.get(vAmb), yard[0], yard[1], 0, f.id);
            if (!vh.driveTo(v, e.x[best], e.y[best], 0)) {
                vh.state[v] = Vehicles.V_NONE;
                f.vpool[vAmb]++;
                continue;
            }
            vh.siren[v] = true;
            e.flags[best] |= Ents.F_PATIENT;
            Ambulance a = new Ambulance();
            a.v = v;
            a.uid = vh.uid[v];
            a.hospital = f.id;
            a.patient = best;
            a.puid = e.uid[best];
            a.px = e.x[best];
            a.py = e.y[best];
            a.phase = A_DRIVE;
            ambs.add(a);
            w.news.count("amb:" + f.id, "Ambulance out from " + f.name + "|Ambulances from " + f.name + ": %d more call{s}", 1, e.x[best], e.y[best]);
            return; // one a second
        }
    }

    private void ambulances(float dt) {
        Ents e = w.e;
        Vehicles vh = w.veh;
        Content.Unit medic = unitMedic >= 0 ? w.c.units.get(unitMedic) : null;
        for (int k = ambs.size() - 1; k >= 0; k--) {
            Ambulance a = ambs.get(k);
            a.t += dt;
            int v = a.v;
            Region.Facility hf = w.r.facilities.get(a.hospital);
            boolean ok = live(v, a.uid);
            boolean pOk = ent(a.patient, a.puid) && e.kind[a.patient] == Ents.CIV;
            if (!ok) {
                // wrecked: the crew walk back
                for (int j = 0; j < 2; j++) if (ent(a.medic[j], a.muid[j]) && e.loc[a.medic[j]] == Ents.FREE) strays.add(new int[]{a.medic[j], a.muid[j], a.hospital});
                ambs.remove(k);
                continue;
            }
            switch (a.phase) {
                case A_DRIVE: {
                    if (!pOk || e.loc[a.patient] != Ents.FREE) {
                        back(a, false);
                        break;
                    }
                    float px = e.x[a.patient], py = e.y[a.patient];
                    boolean there = vh.arrived(v) || vh.state[v] == Vehicles.V_ARRIVED || dist(vh.x[v], vh.y[v], px, py) < 70;
                    if (there && dist(vh.x[v], vh.y[v], px, py) < 260) {
                        // the paramedics get down and go to them
                        vh.halt(v);
                        if (vh.state[v] == Vehicles.V_DRIVE) vh.state[v] = Vehicles.V_ARRIVED;
                        vh.siren[v] = false;
                        for (int j = 0; j < 2 && medic != null; j++) {
                            if (hf.pool[unitMedic] <= 0) break;
                            hf.pool[unitMedic]--;
                            float ox = (float) Math.cos(vh.head[v] + 1.57f + j * 3.14f) * 14, oy = (float) Math.sin(vh.head[v] + 1.57f + j * 3.14f) * 14;
                            float mx = vh.x[v] + ox, my = vh.y[v] + oy;
                            if (!w.r.walkablePx(mx, my)) {
                                mx = vh.x[v];
                                my = vh.y[v];
                            }
                            int m = w.spawnCiv(mx, my, medic);
                            e.flags[m] |= Ents.F_CREW;
                            e.state[m] = CREW_GO;
                            e.gx[m] = px;
                            e.gy[m] = py;
                            a.medic[j] = m;
                            a.muid[j] = e.uid[m];
                        }
                        a.phase = A_SCENE;
                        a.t = 0;
                    } else if (dist(px, py, a.px, a.py) > 200 || vh.state[v] == Vehicles.V_ARRIVED && !there) {
                        a.px = px;
                        a.py = py;
                        if (!vh.driveTo(v, px, py, 0)) back(a, false);
                        else vh.siren[v] = true;
                    }
                    if (a.t > 240) back(a, false);
                    break;
                }
                case A_SCENE: {
                    boolean danger = w.sp.zombiesNear(e, vh.x[v], vh.y[v], 130, 1) > 0;
                    boolean got = pOk && e.loc[a.patient] == Ents.ABOARD;
                    int mOn = 0, mOut = 0;
                    for (int j = 0; j < 2; j++) {
                        int m = a.medic[j];
                        if (!ent(m, a.muid[j]) || e.kind[m] != Ents.CIV) continue;
                        if (e.loc[m] == Ents.ABOARD) {
                            mOn++;
                            continue;
                        }
                        mOut++;
                        if (!danger && !got && pOk && e.loc[a.patient] == Ents.FREE) {
                            e.gx[m] = e.x[a.patient];
                            e.gy[m] = e.y[a.patient];
                            e.state[m] = CREW_GO;
                            // with them: the patient is helped to the ambulance and in
                            if (dist(e.x[m], e.y[m], e.x[a.patient], e.y[a.patient]) < 22) {
                                if (vh.board(v, a.patient)) got = true;
                            }
                        } else {
                            e.gx[m] = vh.x[v];
                            e.gy[m] = vh.y[v];
                            e.state[m] = CREW_BACK;
                            if (dist(e.x[m], e.y[m], vh.x[v], vh.y[v]) < 24) {
                                vh.board(v, m);
                                mOn++;
                                mOut--;
                            }
                        }
                    }
                    if ((got || danger || !pOk || a.t > 90) && mOut == 0) {
                        back(a, got);
                        break;
                    }
                    if (a.t > 120) {
                        // those still out make their own way
                        for (int j = 0; j < 2; j++) if (ent(a.medic[j], a.muid[j]) && e.loc[a.medic[j]] == Ents.FREE) strays.add(new int[]{a.medic[j], a.muid[j], a.hospital});
                        back(a, got);
                    }
                    break;
                }
                case A_BACK: {
                    // at the hospital: where its road ends (the junction nearest it), or close by
                    byte vs = vh.state[v];
                    boolean stopped = vs != Vehicles.V_DRIVE && vs != Vehicles.V_PULLIN && vs != Vehicles.V_PARKING;
                    boolean home = stopped && (a.t > 3 || dist(vh.x[v], vh.y[v], hf.x, hf.y) < 260);
                    if (home || a.t > 600) {
                        deliver(a);
                        ambs.remove(k);
                    }
                    break;
                }
                default:
            }
        }
    }

    /** Crew who lost their vehicle walk back to their station (and go back in its pool there). */
    private void strays() {
        Ents e = w.e;
        for (int k = strays.size() - 1; k >= 0; k--) {
            int[] s = strays.get(k);
            if (!ent(s[0], s[1]) || e.kind[s[0]] != Ents.CIV) {
                strays.remove(k);
                continue;
            }
            if (s[2] < 0 || s[2] >= w.r.facilities.size()) {
                crewHome(s[0], -1);
                strays.remove(k);
                continue;
            }
            Region.Facility f = w.r.facilities.get(s[2]);
            if (e.loc[s[0]] == Ents.FREE) {
                e.gx[s[0]] = f.x;
                e.gy[s[0]] = f.y;
                e.state[s[0]] = CREW_BACK;
                // home, or out of sight on the way there
                int sc = w.r.sectorOfPx(e.x[s[0]], e.y[s[0]]);
                if (dist(e.x[s[0]], e.y[s[0]], f.x, f.y) < 60 || !w.sec.cam[sc] && w.sec.hot[sc] <= 0) {
                    crewHome(s[0], s[2]);
                    strays.remove(k);
                }
            }
        }
    }

    /** Crew who lost their vehicle, walking back to their station: entity, uid, facility. */
    private final List<int[]> strays = new ArrayList<int[]>();

    /** One of a crew on foot with no vehicle to go back to (a firefighter whose engine left): walks to its station. */
    void stray(int i, int facility) {
        if (facility < 0) facility = Tools.nearestFacility(w, w.e.x[i], w.e.y[i], Ents.COP);
        strays.add(new int[]{i, w.e.uid[i], facility});
    }

    private void back(Ambulance a, boolean withPatient) {
        Vehicles vh = w.veh;
        Region.Facility hf = w.r.facilities.get(a.hospital);
        a.phase = A_BACK;
        a.t = 0;
        if (ent(a.patient, a.puid)) w.e.flags[a.patient] &= ~Ents.F_PATIENT;
        if (!withPatient) {
            vh.siren[a.v] = false;
            vh.goHome(a.v);
            vh.returning[a.v] = true;
        } else {
            vh.driveTo(a.v, hf.x, hf.y, 0);
            vh.siren[a.v] = true;
        }
    }

    /** At the hospital: the patient is taken in (the bitten too: and the infection with them), the crew and the ambulance back in. */
    private void deliver(Ambulance a) {
        Ents e = w.e;
        Vehicles vh = w.veh;
        int v = a.v;
        Region.Facility hf = w.r.facilities.get(a.hospital);
        int hs = w.r.sectorOfPx(hf.x, hf.y);
        for (int k = vh.crewN[v] - 1; k >= 0; k--) {
            int m = vh.crew[v][k];
            if ((e.flags[m] & Ents.F_CREW) != 0) crewHome(m, a.hospital);
            else {
                boolean inf = (e.flags[m] & Ents.F_INFECTED) != 0;
                carried++;
                if (inf) {
                    carriedBitten++;
                    bittenInto = hs;
                    w.news.count("ambInf:" + hf.id, "A bitten patient taken in at " + hf.name + "|" + hf.name + ": %d more bitten patient{s} taken in", 1, hf.x, hf.y);
                } else e.flags[m] &= ~Ents.F_INFECTED;
                indoors(m, hs);
            }
        }
        // medics who walked back on their own
        for (int j = 0; j < 2; j++) if (ent(a.medic[j], a.muid[j]) && e.loc[a.medic[j]] == Ents.FREE) strays.add(new int[]{a.medic[j], a.muid[j], a.hospital});
        vh.crewN[v] = 0;
        w.forces.ensurePools(hf);
        if (vh.usable(v)) hf.vpool[vAmb]++;
        vh.state[v] = Vehicles.V_NONE;
    }

    /** The officials' call: once the hospital itself has the dead in it, the ambulances stop taking the bitten there. */
    private void hospitalPolicy() {
        for (Region.Facility f : w.r.facilities) {
            if (f.kind != Region.F_HOSPITAL || f.settlement < 0 || !admitsBitten(f.settlement) || w.roles.holds(Roles.INCIDENT, f.settlement)) continue;
            int s = w.r.sectorOfPx(f.x, f.y);
            if (w.sec.zIn[s] + w.sp.secZ[s] >= 10 || !w.strat.online(f.id)) setAdmitBitten(f.settlement, false, false);
        }
    }

    // ================================================================== police checkpoints and corridors

    public static final class Checkpoint {
        public int settlement, node, squad = -1, stopped, caught;
    }

    public final List<Checkpoint> checkpoints = new ArrayList<Checkpoint>();
    public int stoppedTotal, caughtTotal;

    private void checkpoints() {
        Command cmd = w.command;
        // stand down the checkpoints of towns no longer quarantined
        for (int k = checkpoints.size() - 1; k >= 0; k--) {
            Checkpoint c = checkpoints.get(k);
            Squads.Squad s = c.squad >= 0 && c.squad < w.sq.list.size() ? w.sq.get(c.squad) : null;
            if (!cmd.quarantined(c.settlement)) {
                if (s != null && s.alive && s.task == TASK_CHECKPOINT) {
                    s.task = -1;
                    w.sq.goHome(s);
                }
                checkpoints.remove(k);
                continue;
            }
            if (s == null || !s.alive || s.task != TASK_CHECKPOINT) c.squad = -1;
        }
        int max = (int) w.rule("maxCheckpoints", 8);
        for (int st = 0; st < cmd.quarantine.length; st++) {
            if (!cmd.quarantined(st)) continue;
            Region.Settlement s = w.r.settlements.get(st);
            for (int node : s.edgeNodes) {
                float x = w.r.roads.nx[node], y = w.r.roads.ny[node];
                if (w.act.sparse && !w.act.activePx(x, y)) continue;
                Checkpoint c = null;
                for (Checkpoint o : checkpoints) if (o.node == node) c = o;
                if (c == null) {
                    if (checkpoints.size() >= max) break;
                    c = new Checkpoint();
                    c.settlement = st;
                    c.node = node;
                    checkpoints.add(c);
                }
                if (c.squad < 0) man(c, x, y);
            }
        }
        // the posts stop people on the road out, and find the bitten among them
        Ents e = w.e;
        for (Checkpoint c : checkpoints) {
            if (c.squad < 0) continue;
            Squads.Squad sq = w.sq.get(c.squad);
            int lead = sq.leader(e);
            if (lead < 0 || e.loc[lead] != Ents.FREE) continue;
            float x = w.r.roads.nx[c.node], y = w.r.roads.ny[c.node];
            if (dist(e.x[lead], e.y[lead], x, y) > 120) continue;
            boolean quiet = w.sp.nearestZombie(e, x, y, 140) < 0;
            int cx0 = Math.max(0, (int) ((x - 90) / Spatial.CELL)), cx1 = Math.min(w.sp.cw - 1, (int) ((x + 90) / Spatial.CELL));
            int cy0 = Math.max(0, (int) ((y - 90) / Spatial.CELL)), cy1 = Math.min(w.sp.ch - 1, (int) ((y + 90) / Spatial.CELL));
            for (int cy = cy0; cy <= cy1; cy++)
                for (int cx = cx0; cx <= cx1; cx++) {
                    int cell = w.sp.cell(cx, cy);
                    for (int j = w.sp.lStart[cell]; j < w.sp.lEnd[cell]; j++) {
                        int o = w.sp.lList[j];
                        if (e.loc[o] != Ents.FREE || e.kind[o] != Ents.CIV || o == w.playerEnt || (e.flags[o] & (Ents.F_COMPANION | Ents.F_CREW)) != 0) continue;
                        if (dist(e.x[o], e.y[o], x, y) > 90 || !quiet) continue;
                        if ((e.flags[o] & Ents.F_INFECTED) != 0) {
                            // the bitten found at the post: held there, apart, under guard (counted in the street's houses)
                            c.caught++;
                            caughtTotal++;
                            w.news.count("caught:" + c.settlement, "Checkpoint at " + w.r.settlements.get(c.settlement).name + ": someone bitten found and held|Checkpoints at "
                                    + w.r.settlements.get(c.settlement).name + ": %d more of the bitten found and held", 1, x, y);
                            w.sec.shelter(o);
                        } else if ((e.flags[o] & Ents.F_STOPPED) == 0) {
                            // turned back: home, and stay there
                            e.flags[o] |= Ents.F_STOPPED;
                            c.stopped++;
                            stoppedTotal++;
                            e.state[o] = CivAI.S_SHELTER;
                            Region.Settlement s = w.r.settlements.get(c.settlement);
                            e.gx[o] = x + (s.cx * 16f - x) * 0.3f;
                            e.gy[o] = y + (s.cy * 16f - y) * 0.3f;
                            e.timer[o] = 20;
                        }
                    }
                }
        }
    }

    static final int TASK_CHECKPOINT = -3, TASK_RESCUE = -4;

    /** A police squad from the town's station to man a checkpoint. */
    private void man(Checkpoint c, float x, float y) {
        Region.Facility best = null;
        float bd = Float.MAX_VALUE;
        Content.SquadT t = w.c.squadT("patrol");
        if (t == null) return;
        for (Region.Facility f : w.r.facilities) {
            if (f.kind != Region.F_POLICE || f.settlement != c.settlement || f.pool == null || !w.strat.online(f.id)) continue;
            if (f.pool[t.units[0].index] < 3) continue;
            float d = dist(f.x, f.y, x, y);
            if (d < bd) {
                bd = d;
                best = f;
            }
        }
        if (best == null) return;
        Squads.Squad s = w.forces.deploy(best, t);
        if (s == null) return;
        s.task = TASK_CHECKPOINT;
        w.sq.order(s, Squads.O_GUARD, x, y);
        c.squad = s.id;
        w.news.count("ckpt:" + c.settlement, w.r.settlements.get(c.settlement).name + ": police are manning a checkpoint on the road out|"
                + w.r.settlements.get(c.settlement).name + ": %d more checkpoint{s} manned", 1, x, y);
    }

    public static final class Corridor {
        public int settlement;
        public float ax, ay, bx, by, t;
        public int escorts;
        /** The road it follows (x, y pairs, from the town's way out to the town it leads to). */
        public float[] pts = new float[0];
    }

    public final List<Corridor> corridors = new ArrayList<Corridor>();

    /** Is (x, y) on an escorted corridor (police holding the road out of a town being evacuated)? */
    public boolean escorted(float x, float y) {
        if (corridors.isEmpty()) return false;
        float width = w.rule("corridorWidth", 1500);
        for (Corridor c : corridors) {
            if (c.escorts <= 0) continue;
            float[] p = c.pts;
            for (int k = 0; k + 3 < p.length; k += 2) {
                float dx = p[k + 2] - p[k], dy = p[k + 3] - p[k + 1], l2 = dx * dx + dy * dy + 1;
                float u = Math.max(0, Math.min(1, ((x - p[k]) * dx + (y - p[k + 1]) * dy) / l2));
                if (dist(x, y, p[k] + dx * u, p[k + 1] + dy * u) < width) return true;
            }
        }
        return false;
    }

    /** The road from (ax, ay) to town d, as at most 48 points (a straight line if there is no road). */
    private float[] roadPts(float ax, float ay, Region.Settlement d) {
        RoadGraph g = w.r.roads;
        int a = g.nearestNode(ax, ay, w.reach(1500)), b = d.hubNode >= 0 ? d.hubNode : g.nearestNode(d.cx * 16f, d.cy * 16f, w.reach(1500));
        int[] rt = a >= 0 && b >= 0 ? g.route(a, b) : null;
        if (rt == null || rt.length < 2) return new float[]{ax, ay, d.cx * 16f, d.cy * 16f};
        int step = Math.max(1, (rt.length + 46) / 47);
        int n = (rt.length - 1) / step + 1 + ((rt.length - 1) % step != 0 ? 1 : 0);
        float[] p = new float[n * 2];
        int k = 0;
        for (int i = 0; i < rt.length; i += step) {
            p[k++] = g.nx[rt[i]];
            p[k++] = g.ny[rt[i]];
        }
        if (k < p.length) {
            p[k++] = g.nx[rt[rt.length - 1]];
            p[k] = g.ny[rt[rt.length - 1]];
        }
        return p;
    }

    /** Opens an escorted corridor out of a town being evacuated (the mayor's, or the player's order). */
    public boolean openCorridor(int st, boolean player) {
        if (st < 0 || st >= w.r.settlements.size() || !w.gov.evacuating(st)) return false;
        for (Corridor c : corridors) if (c.settlement == st) return false;
        Region.Settlement s = w.r.settlements.get(st);
        float[] p = w.strat.evacPoint(st);
        int dest = -1;
        float bd = Float.MAX_VALUE;
        for (Region.Settlement o : w.r.settlements) {
            if (o.id == st || o.kind != Region.S_CITY && o.kind != Region.S_TOWN || w.command.repZ[o.id] > 5) continue;
            float d = dist(o.cx * 16f, o.cy * 16f, p[0], p[1]);
            if (d < bd) {
                bd = d;
                dest = o.id;
            }
        }
        if (dest < 0) return false;
        // police officers from the town's stations hold the road (taken from the pools while it's open)
        int need = 12, got = 0;
        Content.SquadT t = w.c.squadT("patrol");
        if (t == null) return false;
        for (Region.Facility f : w.r.facilities) {
            if (f.kind != Region.F_POLICE || f.settlement != st || f.pool == null) continue;
            int u = t.units[0].index;
            int take = Math.min(need - got, Math.max(0, f.pool[u] - 4));
            f.pool[u] -= take;
            got += take;
            if (got >= need) break;
        }
        if (got == 0) return false;
        Corridor c = new Corridor();
        c.settlement = st;
        c.ax = p[0];
        c.ay = p[1];
        Region.Settlement d = w.r.settlements.get(dest);
        c.bx = d.cx * 16f;
        c.by = d.cy * 16f;
        c.pts = roadPts(c.ax, c.ay, d);
        c.escorts = got;
        corridors.add(c);
        w.news.headline((player ? "Order" : "Police") + ": an escorted evacuation corridor is open from " + s.name + " to " + d.name + " (" + got + " officers on the road).", p[0], p[1]);
        return true;
    }

    private void corridorsUpdate(float dt) {
        for (int k = corridors.size() - 1; k >= 0; k--) {
            Corridor c = corridors.get(k);
            c.t += dt;
            // the dead on the road wear the escort down
            int s = w.r.sectorOfPx((c.ax + c.bx) / 2, (c.ay + c.by) / 2);
            int z = w.sec.zIn[s] + w.sp.secZ[s];
            if (z > 50 && Rng.unit(w.seed, w.tick, k * 7L + 5) < dt / 600f) {
                c.escorts--;
                w.stats.armedDeaths[Ents.COP]++;
            }
            if (!w.gov.evacuating(c.settlement) || c.escorts <= 0) {
                // closed: the officers go back to their station
                for (Region.Facility f : w.r.facilities)
                    if (f.kind == Region.F_POLICE && f.settlement == c.settlement && f.pool != null && c.escorts > 0) {
                        Content.SquadT t = w.c.squadT("patrol");
                        if (t != null) f.pool[t.units[0].index] += c.escorts;
                        c.escorts = 0;
                    }
                corridors.remove(k);
                w.news.radio("Police: the evacuation corridor from " + w.r.settlements.get(c.settlement).name + " is closed", c.ax, c.ay, News.COMMAND);
            }
        }
        // mayors open one for each town being evacuated that has officers to spare
        for (int st = 0; st < w.r.settlements.size(); st++) {
            if (!w.gov.evacuating(st) || w.roles.holds(Roles.INCIDENT, st)) continue;
            boolean have = false;
            for (Corridor c : corridors) if (c.settlement == st) have = true;
            if (!have && Rng.unit(w.seed, st, (long) (w.time / 600)) < 0.5f) openCorridor(st, false);
        }
    }

    // ================================================================== rescues of trapped people (a bus and a police escort)

    public static final class Rescue {
        public int sector, bus = -1, busUid, squad = -1, phase, facility;
        public float x, y, t;
        public int out;
    }

    public final List<Rescue> rescues = new ArrayList<Rescue>();
    public int rescuedTotal;

    private void rescueAI() {
        if (rescues.size() >= 2) return;
        int s = trappedSector(false);
        if (s < 0) return;
        Sectors sec = w.sec;
        int st = sec.settlement[s];
        Content.VType bus = w.c.vtype("bus");
        Content.SquadT riot = w.c.squadT("riot"), patrol = w.c.squadT("patrol"), t = null;
        if (bus == null || patrol == null || st < 0) return;
        Region.Facility police = null, depot = null;
        float bp = Float.MAX_VALUE, bd = Float.MAX_VALUE;
        float x = sec.cx(s), y = sec.cy(s);
        for (Region.Facility f : w.r.facilities) {
            if (f.settlement != st || !w.strat.online(f.id)) continue;
            float d = dist(f.x, f.y, x, y);
            // riot police where the station has them, else a patrol
            Content.SquadT ft = riot != null && f.pool != null && f.pool[riot.units[0].index] >= 4 ? riot : patrol;
            if (f.kind == Region.F_POLICE && f.pool != null && f.pool[ft.units[0].index] >= 4 && d < bp) {
                bp = d;
                police = f;
                t = ft;
            }
            if ((f.kind == Region.F_DEPOT || f.kind == Region.F_POLICE) && d < bd) {
                bd = d;
                depot = f;
            }
        }
        if (police == null || depot == null) return;
        // a building door in that street, the closest to the middle with a road near it
        float[] door = null;
        for (int b : w.secBuildings[s]) {
            if (w.r.doorX(b) < 0 || w.r.bres(b) <= 0) continue;
            float dx = w.r.doorX(b), dy = w.r.doorY(b);
            if (door == null || dist(dx, dy, x, y) < dist(door[0], door[1], x, y)) door = new float[]{dx, dy};
        }
        if (door == null) return;
        Squads.Squad sq = w.forces.deploy(police, t);
        if (sq == null) return;
        sq.task = TASK_RESCUE;
        w.sq.order(sq, Squads.O_HOLD, door[0], door[1]);
        float[] yard = w.r.openGroundNear(depot.x, depot.y, true);
        int v = w.veh.add(bus, yard[0], yard[1], 0, depot.id);
        if (!w.veh.driveTo(v, door[0], door[1], 0)) {
            w.veh.state[v] = Vehicles.V_NONE;
            w.sq.goHome(sq);
            sq.task = -1;
            return;
        }
        Rescue r = new Rescue();
        r.sector = s;
        r.bus = v;
        r.busUid = w.veh.uid[v];
        r.squad = sq.id;
        r.facility = police.id;
        r.x = door[0];
        r.y = door[1];
        rescues.add(r);
        w.news.radio("Rescue: police and a bus are going in for people trapped" + place(s), door[0], door[1], News.COMMAND);
    }

    private void rescues(float dt) {
        Vehicles vh = w.veh;
        Ents e = w.e;
        for (int k = rescues.size() - 1; k >= 0; k--) {
            Rescue r = rescues.get(k);
            r.t += dt;
            Squads.Squad sq = r.squad >= 0 && r.squad < w.sq.list.size() ? w.sq.get(r.squad) : null;
            boolean bus = live(r.bus, r.busUid);
            if (!bus || r.t > 900) {
                if (sq != null && sq.alive && sq.task == TASK_RESCUE) {
                    sq.task = -1;
                    w.sq.goHome(sq);
                }
                if (bus) {
                    // given up: whoever is aboard is set down in the street they were in, the bus towed back
                    for (int j = vh.crewN[r.bus] - 1; j >= 0; j--) indoors(vh.crew[r.bus][j], w.r.sectorOfPx(vh.x[r.bus], vh.y[r.bus]));
                    vh.state[r.bus] = Vehicles.V_NONE;
                }
                rescues.remove(k);
                continue;
            }
            int v = r.bus;
            if (r.phase == 0) {
                boolean there = (vh.state[v] == Vehicles.V_ARRIVED || vh.arrived(v)) && dist(vh.x[v], vh.y[v], r.x, r.y) < 320;
                if (!there) continue;
                vh.halt(v);
                boolean held = sq != null && sq.alive && w.sp.zombiesNear(e, vh.x[v], vh.y[v], 90, 1) == 0;
                // people come out of the buildings and get on, while the police hold the street
                int seats = vh.type(v).seats;
                for (int j = 0; j < 4 && held && vh.crewN[v] < seats; j++) {
                    int i = fromCount(r.sector, vh.x[v] + (j - 1.5f) * 6, vh.y[v] + 12, j * 5L + r.busUid);
                    if (i < 0) break;
                    vh.board(v, i);
                    r.out++;
                }
                Sectors sec = w.sec;
                if (vh.crewN[v] >= seats || sec.popIn[r.sector] + sec.inf[r.sector] < 1 || r.t > 240 || sq == null || !sq.alive) {
                    // out: to the police station, the escort behind
                    Region.Facility f = w.r.facilities.get(r.facility);
                    r.phase = 1;
                    if (vh.crewN[v] == 0 || !vh.driveTo(v, f.x, f.y, 0)) {
                        vh.goHome(v);
                        r.phase = 2;
                    }
                    if (sq != null && sq.alive) {
                        sq.task = -1;
                        w.sq.goHome(sq);
                    }
                }
            } else if (r.phase == 1) {
                Region.Facility f = w.r.facilities.get(r.facility);
                if (vh.state[v] != Vehicles.V_DRIVE && vh.state[v] != Vehicles.V_PULLIN && vh.state[v] != Vehicles.V_PARKING) {
                    int s0 = w.r.sectorOfPx(f.x, f.y);
                    int n = 0;
                    for (int j = vh.crewN[v] - 1; j >= 0; j--) {
                        int m = vh.crew[v][j];
                        indoors(m, s0);
                        n++;
                    }
                    rescuedTotal += n;
                    if (n > 0) w.news.count("rescued", "Rescue: " + n + " people carried out" + place(r.sector) + "|Rescues: %d more people carried out of streets the dead hold", n, f.x, f.y);
                    vh.goHome(v);
                    r.phase = 2;
                }
            } else {
                // back at the depot (or as near as its road goes): parked away
                byte vs = vh.state[v];
                if (vs != Vehicles.V_DRIVE && vs != Vehicles.V_PULLIN && vs != Vehicles.V_PARKING) {
                    vh.state[v] = Vehicles.V_NONE;
                    rescues.remove(k);
                }
            }
        }
    }

    // ================================================================== aid agencies

    public static final class Aid {
        public int camp, ri;
        public int[] route;
        public float x, y;
        public boolean alive = true;
    }

    public final List<Aid> aid = new ArrayList<Aid>();
    public int aidDelivered, aidLost;
    private float aidT, campT;

    private void aid(float dt) {
        Society so = w.society;
        aidT -= dt;
        if (aidT <= 0 && !so.camps.isEmpty() && !w.r.entryNodes.isEmpty()) {
            aidT = w.rule("aidEvery", 720);
            // the camp with the most people gets the next convoy
            int best = -1, bp = 0;
            for (int k = 0; k < so.camps.size(); k++) {
                Society.Camp c = so.camps.get(k);
                int p = w.sec.popIn[c.sector];
                if (p > bp) {
                    bp = p;
                    best = k;
                }
            }
            if (best >= 0) {
                Society.Camp c = so.camps.get(best);
                Region.Facility cf = w.r.facilities.get(c.facility);
                int en = w.r.entryNodes.get(Rng.hashInt(w.seed, (long) (w.time / 60), 77, w.r.entryNodes.size()));
                int to = cf.node >= 0 ? cf.node : w.r.roads.nearestNode(c.x, c.y, w.reach(900));
                int[] rt = to >= 0 ? w.r.roads.route(en, to) : null;
                if (rt != null) {
                    Aid a = new Aid();
                    a.camp = best;
                    a.route = rt;
                    a.x = w.r.roads.nx[en];
                    a.y = w.r.roads.ny[en];
                    aid.add(a);
                    w.news.radio("Aid agencies: a convoy of food and medicine is on its way to " + c.name, c.x, c.y, News.COMMAND);
                }
            }
        }
        float speed = w.rule("convoySpeed", 35);
        RoadGraph g = w.r.roads;
        for (int k = aid.size() - 1; k >= 0; k--) {
            Aid a = aid.get(k);
            float budget = speed * dt;
            while (budget > 0 && a.ri < a.route.length) {
                int n = a.route[a.ri];
                float dx = g.nx[n] - a.x, dy = g.ny[n] - a.y, d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d <= budget) {
                    a.x = g.nx[n];
                    a.y = g.ny[n];
                    a.ri++;
                    budget -= d;
                } else {
                    a.x += dx / d * budget;
                    a.y += dy / d * budget;
                    budget = 0;
                }
            }
            int s = w.r.sectorOfPx(a.x, a.y);
            if (w.sec.zIn[s] + w.sp.secZ[s] > 40 && Rng.unit(w.seed, w.tick, k * 11L + s) < 0.2f * dt) {
                aid.remove(k);
                aidLost++;
                w.news.radio("An aid convoy has been overrun by the dead on the road" + place(s), a.x, a.y, News.LOSS);
                continue;
            }
            if (a.ri >= a.route.length) {
                aid.remove(k);
                if (a.camp < so.camps.size()) {
                    Society.Camp c = so.camps.get(a.camp);
                    Region.Facility f = w.r.facilities.get(c.facility);
                    f.food += 40000;
                    f.med += 2000;
                    aidDelivered++;
                    w.gov.trust = Math.min(100, w.gov.trust + 0.5f);
                    w.news.count("aidIn", "Aid agencies: food and medicine delivered to " + c.name + "|Aid agencies: %d more convoy{s} delivered to the camps", 1, c.x, c.y);
                }
            }
        }
        // the camps eat what the aid brings; a camp with nothing left grows desperate
        campT += dt;
        if (campT >= 60) {
            for (Society.Camp c : so.camps) {
                Region.Facility f = w.r.facilities.get(c.facility);
                int p = w.sec.popIn[c.sector];
                f.food = Math.max(0, f.food - p * campT / 1440f);
                if (f.food <= 0 && p > 200) {
                    w.sec.panic[w.sec.touch(c.sector)] = Math.min(1, w.sec.panic[c.sector] + 0.05f);
                    w.gov.trust = Math.max(0, w.gov.trust - 0.01f * campT / 60f);
                    w.news.radio(News.keyOf(c.name + " has run out of food. Aid agencies are asking for convoys"), c.name + " has run out of food. Aid agencies are asking for convoys", c.x, c.y, News.COMMAND, 2880);
                }
            }
            campT = 0;
        }
    }

    // ================================================================== martial law: vehicles seized

    public int seized;
    private float seizeT;

    private void seize(float dt) {
        if (!w.gov.martialLaw()) return;
        seizeT += dt;
        if (seizeT < 60) return;
        float hours = seizeT / 60f;
        seizeT = 0;
        long people = w.sec.totalPop();
        int n = stoch(people / 6f * w.rule("seizeRate", 0.002f) * hours, 991);
        if (n <= 0) return;
        seized += n;
        // their tanks drained into the depots, the trucks and buses to the army
        for (Region.Facility f : w.r.facilities) {
            if (f.kind == Region.F_FUEL && w.strat.online(f.id)) f.fuel = Math.min(100, f.fuel + n * 0.002f);
            if (f.kind == Region.F_BASE && f.vpool != null) {
                Content.VType t = w.c.vtype("army_truck");
                if (t != null && t.index < f.vpool.length && seized / 40 > (seized - n) / 40) f.vpool[t.index]++;
            }
        }
        w.gov.trust = Math.max(0, w.gov.trust - n * 0.0005f);
        w.news.tally("seized", "Martial law: the army has seized {m} civilian vehicles (trucks, buses, fuel)|Martial law: {m} more civilian vehicles seized since the last report; their fuel drained into the depots", 1, n, -1, -1, News.COMMAND);
    }

    // ================================================================== the strains

    /** Fights as numbers in sector s: the share of the usual kills against the strain there (1 with no strain). */
    public float strainKill(int s) {
        if (!strainOn || s < 0) return 1;
        switch (w.ecology.strain[s]) {
            case 1: return 0.85f; // runners close the distance before they're shot
            case 2: return 0.65f; // the armoured shrug off headshots
            case 3: return w.night() ? 0.8f : 1f;
            default: return 1;
        }
    }

    /** ... and the share of the usual losses. */
    public float strainLoss(int s) {
        if (!strainOn || s < 0) return 1;
        switch (w.ecology.strain[s]) {
            case 1: return 1.5f;
            case 2: return 1.15f;
            case 3: return w.night() ? 1.6f : 1f;
            default: return 1;
        }
    }

    /** How many of the dead bunched together are worth a grenade (fewer against the armoured strain). Read-only. */
    int grenadePack(float x, float y) {
        if (!strainOn) return 6;
        int s = w.r.sectorOfPx(x, y);
        return w.ecology.strain[s] == 2 ? 3 : 6;
    }

    /** At night against the stalker strain, nobody goes searching for the dead in the dark. Read-only. */
    boolean nightCaution(float x, float y) {
        if (!strainOn || !w.night()) return false;
        return w.ecology.strain[w.r.sectorOfPx(x, y)] == 3;
    }

    private int strainSeen;
    static final String[] DOCTRINE = {"",
            "Doctrine against the runners: squads hold tighter lines and fall back sooner; fights as numbers now kill about 15% fewer and cost half as many again.",
            "Doctrine against the armoured: rifles do little to helmets, so grenades, launchers and blasts lead (a grenade for every three bunched together); fights cost a third more kills to win.",
            "Doctrine against the stalkers: no searching in the dark; after nightfall the forces hold their lines, and fights at night kill a fifth fewer and cost 60% more."};

    private void strains() {
        int st = w.strat.strain;
        if (st > strainSeen && st < DOCTRINE.length) {
            strainSeen = st;
            w.news.headline(DOCTRINE[st], -1, -1);
        }
    }

    // ================================================================== civilian flights

    public boolean airportClosed;
    public int flown, seededTotal, flightsOut;
    private float flightT;
    /** The last departure (for drawing it climbing away): the time it left and its heading. */
    private float departT = -1e9f, departHead;
    private int departFrom = -1;
    /** Cities the flights carried the hidden bitten to (said once each, after the fact, in the report). */
    private final List<int[]> seeded = new ArrayList<int[]>();

    private Region.Facility airport() {
        for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_AIRPORT) return f;
        return null;
    }

    /** The President's decision: the airport closed to civilian flights (or opened again). */
    public void setAirport(boolean closed, boolean player) {
        if (airportClosed == closed) return;
        airportClosed = closed;
        Region.Facility f = airport();
        String name = f != null ? f.name : "the airport";
        Command.Official p = w.command.official(Command.PRESIDENT, -1);
        String who = player || p == null ? "Order" : w.command.name(p);
        w.news.headline(who + ": " + (closed ? "all civilian flights from " + name + " are cancelled. The airport is closed." : name + " is open to civilian flights again."), f != null ? f.x : -1, f != null ? f.y : -1);
    }

    private void flights(float dt) {
        Region.Facility f = airport();
        if (f == null || airportClosed || !w.strat.online(f.id)) return;
        // the President closes it once the alert is high enough (unless the player makes that call)
        if (!w.roles.decidesNation() && w.gov.level >= w.rule("closeAirportAt", 2)) {
            setAirport(true, false);
            return;
        }
        flightT += dt;
        if (flightT < 60) return;
        flightT = 0;
        flightsOut++;
        int pass = (int) w.rule("flightPassengers", 400);
        flown += pass;
        departT = (float) w.time;
        departFrom = f.id;
        departHead = Rng.unit(w.seed, flightsOut, 31) * 6.283f;
        // the bitten among the passengers: as many as their share of the people round the airport's city
        float inf = 0;
        for (int s = w.sec.idx.first(); s >= 0; s = w.sec.idx.next(s)) inf += w.sec.inf[s];
        long all = Math.max(1, w.sec.totalPop());
        int k = stoch(pass * inf / all * 3f, 773);
        if (k <= 0) return;
        // to another city (a domestic flight), into a street of it: the bitten go home and nobody knows
        List<Region.Settlement> cities = new ArrayList<Region.Settlement>();
        for (Region.Settlement s : w.r.settlements) if ((s.kind == Region.S_CITY || s.kind == Region.S_TOWN && s.residents > 20000) && s.id != f.settlement) cities.add(s);
        if (cities.isEmpty()) return;
        Region.Settlement to = cities.get(Rng.hashInt(w.seed, flightsOut, 37, cities.size()));
        int[] sl = w.sec.idx.bySettlement[to.id];
        if (sl.length == 0) return;
        int s = sl[Rng.hashInt(w.seed, flightsOut, 41, sl.length)];
        k = Math.min(k, Math.max(0, w.sec.popIn[s]));
        if (k <= 0) return;
        w.sec.popIn[w.sec.touch(s)] -= k;
        w.sec.inf[s] += k;
        seededTotal += k;
        boolean said = false;
        for (int[] q : seeded) if (q[0] == to.id) {
            q[1] += k;
            said = true;
        }
        if (!said) seeded.add(new int[]{to.id, k});
    }

    /** Lines for the report: where the flights took the infection. */
    public String flightsStory() {
        if (seeded.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("Civilian flights carried " + News.num(seededTotal) + " of the hidden bitten to ");
        for (int k = 0; k < seeded.size(); k++) {
            if (k > 0) sb.append(k == seeded.size() - 1 ? " and " : ", ");
            sb.append(w.r.settlements.get(seeded.get(k)[0]).name);
        }
        sb.append(airportClosed ? " before the airport closed." : "; the airport is still open.");
        return sb.toString();
    }

    // ================================================================== every second

    @Override
    public void update(float dt) {
        if (flightsOn) flights(dt);
        if (seizeOn) seize(dt);
        if (strainOn) strains();
        if (aidOn) aid(dt);
        if (corrOn) corridorsUpdate(dt);
        if (w.strategic) return;
        if (airOn) {
            airTasks(dt);
            airT -= dt;
            if (airT <= 0) {
                airT = 2;
                airAI();
            }
        }
        if (ambOn && unitMedic >= 0 && vAmb >= 0) {
            ambulances(dt);
            ambT -= dt;
            if (ambT <= 0) {
                ambT = 3;
                ambulanceAI(dt);
                hospitalPolicy();
            }
        }
        if (!strays.isEmpty()) strays();
        if (checkOn) checkpoints();
        if (rescueOn) {
            rescues(dt);
            rescueAI();
        }
    }

    /** Before fast-forward: everyone the services carry is set down where they were going, and the crews go home. */
    void enterStrategic() {
        Vehicles vh = w.veh;
        Ents e = w.e;
        for (AirTask a : air) {
            if (!live(a.v, a.uid)) continue;
            int s0 = w.r.sectorOfPx(a.tx, a.ty);
            for (int k = vh.crewN[a.v] - 1; k >= 0; k--) {
                int m = vh.crew[a.v][k];
                if (e.kind[m] == Ents.CIV && e.squad[m] < 0) indoors(m, s0);
            }
        }
        air.clear();
        for (Ambulance a : ambs) {
            for (int j = 0; j < 2; j++) if (ent(a.medic[j], a.muid[j]) && e.kind[a.medic[j]] == Ents.CIV) crewHome(a.medic[j], a.hospital);
            if (live(a.v, a.uid)) {
                Region.Facility hf = w.r.facilities.get(a.hospital);
                int hs = w.r.sectorOfPx(hf.x, hf.y);
                for (int k = vh.crewN[a.v] - 1; k >= 0; k--) {
                    int m = vh.crew[a.v][k];
                    if ((e.flags[m] & Ents.F_CREW) != 0) crewHome(m, a.hospital);
                    else indoors(m, hs);
                }
            }
        }
        ambs.clear();
        for (int[] s : strays) if (ent(s[0], s[1]) && e.kind[s[0]] == Ents.CIV) crewHome(s[0], s[2]);
        strays.clear();
        for (Rescue r : rescues) {
            if (!live(r.bus, r.busUid)) continue;
            Region.Facility f = w.r.facilities.get(r.facility);
            int s0 = w.r.sectorOfPx(f.x, f.y);
            for (int k = vh.crewN[r.bus] - 1; k >= 0; k--) indoors(vh.crew[r.bus][k], s0);
            vh.state[r.bus] = Vehicles.V_NONE;
        }
        rescues.clear();
    }

    // ================================================================== crews on foot (paramedics; firefighters think in Fires)

    static final byte CREW_GO = 40, CREW_BACK = 41, CREW_SPRAY = 42;

    /** A crew member on foot (parallel think: writes only its own slot): to its goal, away from the dead near. */
    void crewThink(int i, Parallel.Scratch s, CivAI cai) {
        Ents e = w.e;
        if (((w.tick + i) & 1) != 0) return;
        Content.Unit u = w.unitOf(i);
        float x = e.x[i], y = e.y[i];
        int z = w.sp.nearestZombie(e, x, y, 90);
        float dx = e.gx[i] - x, dy = e.gy[i] - y, d = (float) Math.sqrt(dx * dx + dy * dy);
        if (z >= 0) {
            // run: away from it, towards the vehicle or the station if that's away from it too
            float ax = x - e.x[z], ay = y - e.y[z], al = (float) Math.sqrt(ax * ax + ay * ay) + 0.01f;
            ax /= al;
            ay /= al;
            if (d > 1 && (dx * ax + dy * ay) > 0) {
                ax = ax * 0.6f + dx / d * 0.4f;
                ay = ay * 0.6f + dy / d * 0.4f;
            }
            cai.steer(i, ax, ay, u.run, s);
            return;
        }
        if (e.state[i] == CREW_SPRAY) {
            // at the hose: stand and face the fire (gx, gy is the building)
            e.face[i] = (float) Math.atan2(dy, dx);
            e.dvx[i] = 0;
            e.dvy[i] = 0;
            return;
        }
        if (d < 10) {
            e.dvx[i] = 0;
            e.dvy[i] = 0;
            return;
        }
        cai.steer(i, dx / d, dy / d, e.state[i] == CREW_BACK || d > 120 ? u.run : u.walk, s);
    }

    // ================================================================== saves

    @Override
    public void save(DataOutputStream o) throws IOException {
        o.writeInt(air.size());
        for (AirTask a : air) {
            o.writeInt(a.v); o.writeInt(a.uid); o.writeInt(a.kind); o.writeInt(a.phase); o.writeInt(a.squad); o.writeInt(a.sector); o.writeInt(a.home);
            o.writeFloat(a.px); o.writeFloat(a.py); o.writeFloat(a.tx); o.writeFloat(a.ty); o.writeFloat(a.t); o.writeBoolean(a.player);
        }
        o.writeInt(assaults); o.writeInt(extractions); o.writeInt(lifted); o.writeFloat(airT);
        o.writeInt(ambs.size());
        for (Ambulance a : ambs) {
            o.writeInt(a.v); o.writeInt(a.uid); o.writeInt(a.hospital); o.writeInt(a.patient); o.writeInt(a.puid); o.writeInt(a.phase);
            o.writeInt(a.medic[0]); o.writeInt(a.medic[1]); o.writeInt(a.muid[0]); o.writeInt(a.muid[1]);
            o.writeFloat(a.t); o.writeFloat(a.px); o.writeFloat(a.py);
        }
        o.writeInt(strays.size());
        for (int[] s : strays) { o.writeInt(s[0]); o.writeInt(s[1]); o.writeInt(s[2]); }
        o.writeInt(noBitten.length);
        for (boolean b : noBitten) o.writeBoolean(b);
        o.writeInt(carried); o.writeInt(carriedBitten); o.writeFloat(ambT);
        o.writeInt(checkpoints.size());
        for (Checkpoint c : checkpoints) { o.writeInt(c.settlement); o.writeInt(c.node); o.writeInt(c.squad); o.writeInt(c.stopped); o.writeInt(c.caught); }
        o.writeInt(stoppedTotal); o.writeInt(caughtTotal);
        o.writeInt(corridors.size());
        for (Corridor c : corridors) {
            o.writeInt(c.settlement); o.writeFloat(c.ax); o.writeFloat(c.ay); o.writeFloat(c.bx); o.writeFloat(c.by); o.writeFloat(c.t); o.writeInt(c.escorts);
            o.writeInt(c.pts.length);
            for (float f : c.pts) o.writeFloat(f);
        }
        o.writeInt(rescues.size());
        for (Rescue r : rescues) {
            o.writeInt(r.sector); o.writeInt(r.bus); o.writeInt(r.busUid); o.writeInt(r.squad); o.writeInt(r.phase); o.writeInt(r.facility);
            o.writeFloat(r.x); o.writeFloat(r.y); o.writeFloat(r.t); o.writeInt(r.out);
        }
        o.writeInt(rescuedTotal);
        o.writeInt(aid.size());
        for (Aid a : aid) {
            o.writeInt(a.camp); o.writeInt(a.ri); SaveIO.intArr(o, a.route); o.writeFloat(a.x); o.writeFloat(a.y);
        }
        o.writeInt(aidDelivered); o.writeInt(aidLost); o.writeFloat(aidT); o.writeFloat(campT);
        o.writeInt(seized); o.writeFloat(seizeT);
        o.writeInt(strainSeen);
        o.writeBoolean(airportClosed); o.writeInt(flown); o.writeInt(seededTotal); o.writeInt(flightsOut); o.writeFloat(flightT);
        o.writeFloat(departT); o.writeFloat(departHead); o.writeInt(departFrom);
        o.writeInt(seeded.size());
        for (int[] q : seeded) { o.writeInt(q[0]); o.writeInt(q[1]); }
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        air.clear();
        int n = in.readInt();
        for (int k = 0; k < n; k++) {
            AirTask a = new AirTask();
            a.v = in.readInt(); a.uid = in.readInt(); a.kind = in.readInt(); a.phase = in.readInt(); a.squad = in.readInt(); a.sector = in.readInt(); a.home = in.readInt();
            a.px = in.readFloat(); a.py = in.readFloat(); a.tx = in.readFloat(); a.ty = in.readFloat(); a.t = in.readFloat(); a.player = in.readBoolean();
            air.add(a);
        }
        assaults = in.readInt(); extractions = in.readInt(); lifted = in.readInt(); airT = in.readFloat();
        ambs.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) {
            Ambulance a = new Ambulance();
            a.v = in.readInt(); a.uid = in.readInt(); a.hospital = in.readInt(); a.patient = in.readInt(); a.puid = in.readInt(); a.phase = in.readInt();
            a.medic[0] = in.readInt(); a.medic[1] = in.readInt(); a.muid[0] = in.readInt(); a.muid[1] = in.readInt();
            a.t = in.readFloat(); a.px = in.readFloat(); a.py = in.readFloat();
            ambs.add(a);
        }
        strays.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) strays.add(new int[]{in.readInt(), in.readInt(), in.readInt()});
        n = in.readInt();
        for (int k = 0; k < n; k++) {
            boolean b = in.readBoolean();
            if (k < noBitten.length) noBitten[k] = b;
        }
        carried = in.readInt(); carriedBitten = in.readInt(); ambT = in.readFloat();
        checkpoints.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) {
            Checkpoint c = new Checkpoint();
            c.settlement = in.readInt(); c.node = in.readInt(); c.squad = in.readInt(); c.stopped = in.readInt(); c.caught = in.readInt();
            checkpoints.add(c);
        }
        stoppedTotal = in.readInt(); caughtTotal = in.readInt();
        corridors.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) {
            Corridor c = new Corridor();
            c.settlement = in.readInt(); c.ax = in.readFloat(); c.ay = in.readFloat(); c.bx = in.readFloat(); c.by = in.readFloat(); c.t = in.readFloat(); c.escorts = in.readInt();
            c.pts = new float[in.readInt()];
            for (int j = 0; j < c.pts.length; j++) c.pts[j] = in.readFloat();
            corridors.add(c);
        }
        rescues.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) {
            Rescue r = new Rescue();
            r.sector = in.readInt(); r.bus = in.readInt(); r.busUid = in.readInt(); r.squad = in.readInt(); r.phase = in.readInt(); r.facility = in.readInt();
            r.x = in.readFloat(); r.y = in.readFloat(); r.t = in.readFloat(); r.out = in.readInt();
            rescues.add(r);
        }
        rescuedTotal = in.readInt();
        aid.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) {
            Aid a = new Aid();
            a.camp = in.readInt(); a.ri = in.readInt(); a.route = SaveIO.readIntArr(in); a.x = in.readFloat(); a.y = in.readFloat();
            aid.add(a);
        }
        aidDelivered = in.readInt(); aidLost = in.readInt(); aidT = in.readFloat(); campT = in.readFloat();
        seized = in.readInt(); seizeT = in.readFloat();
        strainSeen = in.readInt();
        airportClosed = in.readBoolean(); flown = in.readInt(); seededTotal = in.readInt(); flightsOut = in.readInt(); flightT = in.readFloat();
        departT = in.readFloat(); departHead = in.readFloat(); departFrom = in.readInt();
        seeded.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) seeded.add(new int[]{in.readInt(), in.readInt()});
    }

    // ================================================================== drawing

    private final float[] tri = new float[6], plane = new float[12], wing = new float[8];

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        float z = cam.zoom, dp = cam.dp;
        // escorted corridors: a green road out, with the police along it
        for (Corridor c : corridors) {
            float[] p = c.pts;
            int n = p.length / 2;
            if (n < 2) continue;
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int k = 0; k < n; k++) {
                float sx = cam.toScreenX(p[k * 2]), sy = cam.toScreenY(p[k * 2 + 1]);
                minX = Math.min(minX, sx);
                maxX = Math.max(maxX, sx);
                minY = Math.min(minY, sy);
                maxY = Math.max(maxY, sy);
            }
            if (maxX < -50 || minX > cam.sw + 50 || maxY < -50 || minY > cam.sh + 50) continue;
            // along the road: a dark edge, green dashes, and the police posted along it
            for (int pass = 0; pass < 2; pass++)
                for (int k = 0; k + 1 < n; k++) {
                    float ax = cam.toScreenX(p[k * 2]), ay = cam.toScreenY(p[k * 2 + 1]), bx = cam.toScreenX(p[k * 2 + 2]), by = cam.toScreenY(p[k * 2 + 3]);
                    if (pass == 0) {
                        g.color(0x90000000);
                        g.line(ax, ay, bx, by, 7 * dp);
                    } else {
                        g.color(0xE040D070);
                        g.line(ax, ay, bx, by, 3.5f * dp);
                    }
                }
            int every = Math.max(1, n / 6);
            for (int k = every; k < n - 1 && c.escorts > 0; k += every) {
                float px = cam.toScreenX(p[k * 2]), py = cam.toScreenY(p[k * 2 + 1]);
                g.color(0xFF101010);
                g.fillCircle(px, py, 5.5f * dp);
                g.color(0xFF2050C8);
                g.fillCircle(px, py, 4 * dp);
                g.color(0xFFE04040);
                g.fillCircle(px - 1.2f * dp, py - 1.2f * dp, 1.3f * dp);
            }
            float lx = cam.toScreenX(p[(n / 2) * 2]), ly = cam.toScreenY(p[(n / 2) * 2 + 1]);
            view.labelAlways(g, "Escorted corridor (" + c.escorts + " officers)", lx, ly - 12 * dp, 10, 0xFFB8FFC8, Gfx.ALIGN_CENTER, true);
        }
        // aid convoys: white trucks with a red cross
        for (Aid a : aid) {
            float x = cam.toScreenX(a.x), y = cam.toScreenY(a.y);
            if (x < -30 * dp || y < -30 * dp || x > cam.sw + 30 * dp || y > cam.sh + 30 * dp) continue;
            float s = Math.max(7 * dp, 24 * z);
            g.color(0xFF101010);
            g.fillRect(x - s - dp, y - s * 0.55f - dp, s * 2 + 2 * dp, s * 1.1f + 2 * dp);
            g.color(0xFFF2F2F2);
            g.fillRect(x - s, y - s * 0.55f, s * 2, s * 1.1f);
            g.color(0xFFD02020);
            g.fillRect(x - s * 0.15f, y - s * 0.45f, s * 0.3f, s * 0.9f);
            g.fillRect(x - s * 0.45f, y - s * 0.15f, s * 0.9f, s * 0.3f);
            if (z < 0.6f) view.label(g, "Aid convoy", x, y - s - 8 * dp, 9.5f, 0xFFFFFFFF, Gfx.ALIGN_CENTER, false);
        }
        // checkpoints manned: a barrier and the post's name
        for (Checkpoint c : checkpoints) {
            if (c.squad < 0) continue;
            float x = cam.toScreenX(w.r.roads.nx[c.node]), y = cam.toScreenY(w.r.roads.ny[c.node]);
            if (x < -40 * dp || y < -40 * dp || x > cam.sw + 40 * dp || y > cam.sh + 40 * dp) continue;
            float s = Math.max(9 * dp, 34 * z);
            g.color(0xFF101010);
            g.fillRect(x - s - dp, y - 2.5f * dp, 2 * s + 2 * dp, 5 * dp);
            for (int k = 0; k < 6; k++) {
                g.color((k & 1) == 0 ? 0xFFF0F0F0 : 0xFFD02020);
                g.fillRect(x - s + k * s / 3, y - 1.6f * dp, s / 3, 3.2f * dp);
            }
            if (z > 0.25f) view.label(g, "Checkpoint" + (c.caught > 0 ? " (" + c.caught + " bitten held)" : c.stopped > 0 ? " (" + c.stopped + " turned back)" : ""), x, y - 9 * dp, 9.5f, 0xFFFFE080, Gfx.ALIGN_CENTER, false);
        }
        // a flight climbing out of the airport
        float since = (float) w.time - departT;
        if (departFrom >= 0 && since < 25 && departFrom < w.r.facilities.size()) {
            Region.Facility f = w.r.facilities.get(departFrom);
            float u = since / 25f, d = 200 + u * 3000;
            float px = f.x + (float) Math.cos(departHead) * d, py = f.y + (float) Math.sin(departHead) * d;
            float x = cam.toScreenX(px), y = cam.toScreenY(py);
            if (x > -60 * dp && y > -60 * dp && x < cam.sw + 60 * dp && y < cam.sh + 60 * dp) {
                float L = Math.max(16 * dp, 70 * z) * (1 + u * 0.4f);
                g.save();
                g.translate(x, y);
                g.rotate(departHead);
                // an airliner: fuselage, swept wings, tailplane
                plane[0] = L * 0.5f; plane[1] = 0; plane[2] = L * 0.42f; plane[3] = -L * 0.06f; plane[4] = -L * 0.45f; plane[5] = -L * 0.05f;
                plane[6] = -L * 0.5f; plane[7] = 0; plane[8] = -L * 0.45f; plane[9] = L * 0.05f; plane[10] = L * 0.42f; plane[11] = L * 0.06f;
                wing[0] = L * 0.12f; wing[1] = -L * 0.04f; wing[2] = -L * 0.12f; wing[3] = -L * 0.5f; wing[4] = -L * 0.22f; wing[5] = -L * 0.5f; wing[6] = -L * 0.06f; wing[7] = -L * 0.04f;
                g.color(0xFF101418);
                g.save();
                g.scale(1.08f);
                g.fillPoly(plane, 6);
                g.restore();
                g.color(0xFFF4F4F8);
                g.fillPoly(wing, 4);
                for (int k = 1; k < 8; k += 2) wing[k] = -wing[k];
                g.fillPoly(wing, 4);
                g.fillRect(-L * 0.5f, -L * 0.16f, L * 0.1f, L * 0.32f);
                g.fillPoly(plane, 6);
                g.color(0xFF2050A0);
                g.fillRect(-L * 0.42f, -L * 0.02f, L * 0.8f, L * 0.04f);
                g.restore();
                if (z < 1.2f) view.label(g, "Civilian flight", x, y - L * 0.6f - 6 * dp, 9.5f, 0xFFFFFFFF, Gfx.ALIGN_CENTER, false);
            }
        }
        // rescue buses and ambulances: what they're doing, close in
        if (z > 0.3f) {
            for (Rescue r : rescues) {
                if (!live(r.bus, r.busUid)) continue;
                float x = cam.toScreenX(w.veh.x[r.bus]), y = cam.toScreenY(w.veh.y[r.bus]);
                if (x < -40 * dp || y < -40 * dp || x > cam.sw + 40 * dp || y > cam.sh + 40 * dp) continue;
                view.labelAlways(g, r.phase == 0 ? "Rescue bus" + (w.veh.crewN[r.bus] > 0 ? " (" + w.veh.crewN[r.bus] + " aboard)" : "") : "Rescue bus, " + w.veh.crewN[r.bus] + " carried out",
                        x, y - 16 * dp, 9.5f, 0xFFFFF0B0, Gfx.ALIGN_CENTER, false);
            }
            for (Ambulance a : ambs) {
                if (!live(a.v, a.uid)) continue;
                float x = cam.toScreenX(w.veh.x[a.v]), y = cam.toScreenY(w.veh.y[a.v]);
                if (x < -40 * dp || y < -40 * dp || x > cam.sw + 40 * dp || y > cam.sh + 40 * dp) continue;
                String what = a.phase == A_DRIVE ? "Ambulance" : a.phase == A_SCENE ? "Paramedics at work" : w.veh.crewN[a.v] > 2 ? "Ambulance, to hospital" : "Ambulance";
                view.label(g, what, x, y - 14 * dp, 9, 0xFFFFE0E0, Gfx.ALIGN_CENTER, false);
            }
        }
        // air tasks: a line from the helicopter to where it's going
        if (z > 0.04f)
            for (AirTask a : air) {
                if (!live(a.v, a.uid) || !w.veh.flying(a.v)) continue;
                float x = cam.toScreenX(w.veh.x[a.v]), y = cam.toScreenY(w.veh.y[a.v]);
                float gx = a.phase == P_TO_PICKUP ? a.px : a.tx, gy = a.phase == P_TO_PICKUP ? a.py : a.ty;
                float tx = cam.toScreenX(gx), ty = cam.toScreenY(gy);
                g.color(0x70FFE070);
                g.line(x, y, tx, ty, 1.5f * dp);
                g.color(0xC0FFE070);
                g.strokeCircle(tx, ty, 9 * dp, 2 * dp);
                if (z > 0.15f) view.labelAlways(g, T_NAMES[a.kind] + (w.veh.crewN[a.v] > 0 ? " (" + w.veh.crewN[a.v] + " aboard)" : ""), x, y - 22 * dp, 9.5f, 0xFFFFF0B0, Gfx.ALIGN_CENTER, false);
            }
    }

    @Override
    public String status() {
        return null;
    }
}
