package com.instantaxtion.zcs2;

import java.util.ArrayList;
import java.util.List;

/**
 * The army and everyone who comes from outside: the base's squads sent to the big fights, gunships over packs,
 * air strikes on hordes in open country, supply trucks to the stations, engineers blowing bridges, and the
 * waves of state police, National Guard and regular army that arrive by highway as the crisis grows.
 */
public final class Military {
    private final World w;
    public final boolean[] armyCalled;
    float strikeT, supplyT, waveT, guardT, regularT, bridgeT;
    public int strikesLeft = -1; // -1 = unlimited (sandbox); commander scenarios set a budget
    public int wavesArrived, guardsArrived, regularsArrived;
    public final List<int[]> blownBridges = new ArrayList<int[]>();

    Military(World w) {
        this.w = w;
        armyCalled = new boolean[64];
    }

    public Region.Facility base() {
        for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_BASE) return f;
        return null;
    }

    void requestArmy(int settlement, int zombies) {
        if (settlement < 0 || settlement >= armyCalled.length || armyCalled[settlement]) return;
        armyCalled[settlement] = true;
        Region.Settlement s = w.r.settlements.get(settlement);
        w.news.radio("Army: requested in " + s.name + " (" + zombies + " of the dead). Convoy rolling.", s.cx * 16f, s.cy * 16f, News.COMMAND);
    }

    /** A call big enough for soldiers: send the nearest free ones. */
    void considerCall(Dispatch.Incident in) {
        int want;
        if (in.army) want = Math.min(10, 1 + in.zombies / 12);
        else if (in.zombies >= 8 && in.settlement >= 0 && w.gov.level >= 1) want = 1 + in.zombies / 25;
        else return;
        int have = 0;
        for (int id : in.squads) if (w.sq.get(id).faction == Ents.SOLDIER) have++;
        in.armyAssigned = have;
        int guard = 0;
        while (have < want && guard++ < 3) {
            Squads.Squad s = w.disp.nearestFree(in, Ents.SOLDIER, 30000);
            if (s == null) break;
            w.disp.send(s, in);
            have++;
        }
        // gunships for big packs
        if (in.zombies >= 35) gunship(in.x, in.y);
    }

    private void gunship(float x, float y) {
        Vehicles v = w.veh;
        for (int i = 0; i < v.top; i++) {
            if (v.state[i] != Vehicles.V_PARKED || !v.type(i).flies || v.type(i).weapon == null || v.role[i] == Vehicles.R_STRIKE) continue;
            if (v.ammo[i] <= 0 || v.fuel[i] < 0.5f) continue;
            // one aircraft per fight
            for (int j = 0; j < v.top; j++)
                if ((v.state[j] == Vehicles.V_ORBIT || v.state[j] == Vehicles.V_FLY) && v.type(j).weapon != null) {
                    float dx = v.destX[j] - x, dy = v.destY[j] - y;
                    if (dx * dx + dy * dy < 400 * 400) return;
                }
            v.driveTo(i, x, y, 0);
            w.news.radio(v.type(i).name + " " + Names.callsign(Ents.SOLDIER, v.uid[i]) + " inbound", x, y, News.UNIT);
            return;
        }
    }

    void update(float dt) {
        strikeT -= dt;
        supplyT -= dt;
        waveT -= dt;
        guardT -= dt;
        regularT -= dt;
        bridgeT -= dt;
        // the army goes to every town that asked
        for (int st = 0; st < armyCalled.length && st < w.r.settlements.size(); st++) {
            if (!armyCalled[st]) continue;
            if (!w.disp.perimeterMode(st)) armyCalled[st] = false;
        }
        if (w.gov.level >= 3 && strikeT <= 0 && !w.strategic) autoStrike();
        if (supplyT <= 0 && !w.strategic) {
            supply();
            supplyT = 90;
        }
        // outside help, growing with the crisis
        int dead = w.totalZombies();
        // outside help arrives as formations on the highways, growing with the crisis
        if (w.gov.level >= 2 && waveT <= 0 && dead > 40 && wavesArrived < 6) {
            arrive("police_reserve", "State Police");
            waveT = 240;
        }
        if (w.gov.level >= 3 && guardT <= 0 && guardsArrived < 10 && dead > 300) {
            arrive("guard_brigade", "National Guard");
            guardsArrived++;
            guardT = 300;
        }
        // national mobilisation: a new formation every four game hours for as long as it takes
        if (w.gov.level >= 4 && regularT <= 0 && regularsArrived < 60 && dead > 1500) {
            String[] kinds = {"infantry", "armour", "infantry", "artillery"};
            arrive(kinds[regularsArrived % kinds.length], "Army");
            regularsArrived++;
            regularT = 240;
        }
        if (!w.strategic) retaskArmour();
        if (bridgeT <= 0) {
            bridgeT = 10;
            if (w.command.bridgesAuthorised) w.strat.considerBridges();
        }
    }

    /** A formation from outside arrives at a highway entry (or the naval base, for marines). */
    public Forces.Formation arrive(String formationId, String agency) {
        Content.FormationT t = w.c.formationT(formationId);
        if (t == null || w.r.entryNodes.isEmpty()) return null;
        int entry = w.r.entryNodes.get(w.rng.nextInt(w.r.entryNodes.size()));
        Forces.Formation f = w.forces.createFormation(t, w.r.roads.nx[entry], w.r.roads.ny[entry], true);
        if (t.faction.equals("police")) {
            wavesArrived++;
            w.stats.outsidePolice += f.strength();
        } else w.stats.outsideSoldiers += f.strength();
        w.news.radio(agency + ": the " + f.name + " (" + f.strength() + ") is entering the country", f.hq.x, f.hq.y, News.COMMAND);
        // straight to the capital until command has a job for it
        Region.Settlement c = w.r.city();
        w.forces.order(f, c.cx * 16f, c.cy * 16f);
        return f;
    }

    /** Air strikes on big masses of the dead where no one living is close. */
    private void autoStrike() {
        int best = -1, bz = 0;
        for (int s = 0; s < w.sec.n; s++) {
            int z = w.sp.secZ[s] + w.sec.zIn[s];
            if (z < 250 || w.sec.popIn[s] > 50) continue;
            if (w.sp.secCiv[s] + w.sp.secCop[s] + w.sp.secSol[s] > 0) continue;
            if (z > bz) {
                bz = z;
                best = s;
            }
        }
        float tx, ty;
        if (best < 0) {
            Strategic.Horde h = w.strat.biggestHorde();
            if (h == null || h.count < 600) return;
            tx = h.x;
            ty = h.y;
        } else {
            tx = w.sec.cx(best);
            ty = w.sec.cy(best);
        }
        if (w.sp.livingNear(w.e, tx, ty, 260, 1) > 0) return;
        callStrike(tx, ty, false);
        strikeT = 120;
    }

    /** An air strike on (x, y). Returns false if none can fly. */
    public boolean callStrike(float x, float y, boolean byPlayer) {
        if (byPlayer && strikesLeft == 0) return false;
        if (byPlayer && strikesLeft > 0) strikesLeft--;
        w.veh.strike(x, y);
        w.news.radio("Air strike called" + (byPlayer ? "" : " on a mass of the dead") + ". Clear the area!", x, y, News.COMMAND);
        return true;
    }

    /** Supply trucks from the base depot to stations running low. */
    private void supply() {
        Region.Facility b = base();
        if (b == null || !w.strat.online(b.id)) return;
        int sent = 0;
        for (Region.Facility f : w.r.facilities) {
            if (f.kind != Region.F_POLICE && f.kind != Region.F_ARMORY) continue;
            if (!w.strat.online(f.id) || f.ammo > Strategic.AMMO_START * 0.5f) continue;
            if (w.strat.supplyOnWay[f.id]) continue;
            Content.VType t = w.c.vtype("supply_truck");
            if (t == null) return;
            int v = w.veh.add(t, b.x, b.y, 0, b.id);
            w.veh.role[v] = Vehicles.R_SUPPLY;
            w.veh.cargo[v] = Strategic.AMMO_START;
            w.veh.target[v] = f.id;
            if (!w.veh.driveTo(v, f.x, f.y, 0)) {
                w.veh.state[v] = Vehicles.V_NONE;
                continue;
            }
            w.strat.supplyOnWay[f.id] = true;
            w.stats.supplyRuns++;
            if (++sent >= 3) break;
        }
    }

    /** A wave from outside the region: squads in vehicles arriving at a highway entry. */
    public void outsideWave(String unitId, String vehicleId, int squads, String agency, int faction) {
        Content.Unit u = w.c.unit(unitId);
        Content.VType vt = w.c.vtype(vehicleId);
        if (u == null || vt == null || w.r.entryNodes.isEmpty()) return;
        int entry = w.r.entryNodes.get(w.rng.nextInt(w.r.entryNodes.size()));
        float ex = w.r.roads.nx[entry], ey = w.r.roads.ny[entry];
        int homeFac = nearestHome(ex, ey, faction);
        for (int k = 0; k < squads; k++) {
            Squads.Squad s = w.sq.create(faction, homeFac, agency);
            s.outside = true;
            float ox = ex + (k % 2) * 12, oy = ey + k * 34;
            int v = w.veh.add(vt, ox, oy, 0, homeFac);
            if (homeFac >= 0) {
                Region.Facility f = w.r.facilities.get(homeFac);
                w.veh.homeX[v] = f.x + (k % 4) * 10;
                w.veh.homeY[v] = f.y;
            }
            s.vehicle = v;
            w.veh.squad[v] = s.id;
            int per = Math.max(2, Math.min(vt.seats, faction == Ents.COP ? 4 : 10));
            for (int m = 0; m < per; m++) {
                int i = w.spawnUnit(u, ox, oy);
                w.sq.add(s, i);
                w.veh.board(v, i);
            }
            // go where it's needed most: the biggest open call, or the base
            Dispatch.Incident target = null;
            for (Dispatch.Incident in : w.disp.incidents)
                if (in.open && (target == null || in.zombies > target.zombies)) target = in;
            if (target != null) w.disp.send(s, target);
            else w.sq.goHome(s);
            if (faction == Ents.COP) w.stats.outsidePolice += per;
            else w.stats.outsideSoldiers += per;
        }
        wavesArrived++;
        w.news.radio(agency + ": " + squads + " units arriving from outside the region", ex, ey, News.COMMAND);
    }

    private void armour() {
        Content.VType t = w.c.vtype("tank");
        if (t == null || w.r.entryNodes.isEmpty()) return;
        int entry = w.r.entryNodes.get(w.rng.nextInt(w.r.entryNodes.size()));
        float ex = w.r.roads.nx[entry], ey = w.r.roads.ny[entry];
        for (int k = 0; k < 2; k++) {
            int v = w.veh.add(t, ex, ey + k * 44, 0, nearestHome(ex, ey, Ents.SOLDIER));
            w.veh.timer[v] = 99;
        }
    }

    /** Tanks have no squad: they go to the biggest fight and stay until a bigger one needs them. */
    private void retaskArmour() {
        Vehicles v = w.veh;
        Dispatch.Incident target = null;
        for (Dispatch.Incident in : w.disp.incidents)
            if (in.open && in.zombies >= 20 && (target == null || in.zombies > target.zombies)) target = in;
        for (int i = 0; i < v.top; i++) {
            if (!v.type(i).tracked || v.squad[i] >= 0 || !v.usable(i)) continue;
            boolean idle = v.state[i] == Vehicles.V_ARRIVED || (v.state[i] == Vehicles.V_PARKED && w.gov.level >= 3);
            if (!idle || v.timer[i] < 25) continue;
            if (target == null) {
                if (v.state[i] == Vehicles.V_ARRIVED && w.sp.zombiesNear(w.e, v.x[i], v.y[i], 400, 1) == 0) v.goHome(i);
                continue;
            }
            float dx = v.x[i] - target.x, dy = v.y[i] - target.y;
            if (dx * dx + dy * dy < 500 * 500) continue;
            if (v.driveTo(i, target.x, target.y, 60)) v.siren[i] = false;
        }
    }

    private int nearestHome(float x, float y, int faction) {
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (Region.Facility f : w.r.facilities) {
            boolean ok = faction == Ents.COP ? f.kind == Region.F_POLICE : (f.kind == Region.F_BASE || f.kind == Region.F_ARMORY);
            if (!ok || !w.strat.online(f.id)) continue;
            float dx = f.x - x, dy = f.y - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = f.id;
            }
        }
        return best;
    }
}
