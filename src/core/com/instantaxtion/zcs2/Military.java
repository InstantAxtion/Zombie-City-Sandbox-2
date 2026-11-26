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
    /** Per settlement: the reported dead when the army was last asked for (asked again only once it is much worse), and when. */
    public final float[] armyAsked, armyAskedT;
    /** Per settlement: the player (its incident commander) asked for the army; the general plans for it then. */
    public final float[] playerAskedT;
    float strikeT, supplyT, waveT, guardT, regularT, bridgeT;
    public int strikesLeft = -1; // -1 = unlimited (sandbox); commander scenarios set a budget
    public int wavesArrived, guardsArrived, regularsArrived;
    public final List<int[]> blownBridges = new ArrayList<int[]>();

    Military(World w) {
        this.w = w;
        int n = Math.max(64, w.r.settlements.size());
        armyCalled = new boolean[n];
        armyAsked = new float[n];
        armyAskedT = new float[n];
        playerAskedT = new float[n];
        java.util.Arrays.fill(armyAskedT, -1e9f);
        java.util.Arrays.fill(playerAskedT, -1e9f);
    }

    /** Game seconds before a town asks for the army again, and how much worse it must be by then. */
    static final float ASK_AGAIN = 360, ASK_STEP = 2.5f;

    public Region.Facility base() {
        for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_BASE) return f;
        return null;
    }

    /**
     * A town's police ask for the army: once for each step the outbreak there gets worse (two and a half times what
     * it was when they last asked), and not again for a few hours. The incident commander's own city asks only when
     * the player does.
     */
    void requestArmy(int settlement, int zombies) {
        if (settlement < 0 || settlement >= armyCalled.length) return;
        if (w.roles.holds(Roles.INCIDENT, settlement)) return;
        if (zombies < armyAsked[settlement] * ASK_STEP || w.time - armyAskedT[settlement] < ASK_AGAIN) return;
        boolean again = armyAsked[settlement] > 0;
        armyCalled[settlement] = true;
        armyAsked[settlement] = zombies;
        armyAskedT[settlement] = (float) w.time;
        Region.Settlement s = w.r.settlements.get(settlement);
        w.news.radio("army:" + settlement, again ? "Army: " + s.name + " asks for more soldiers (about " + News.num(zombies) + " of the dead now)."
                : "Army: requested in " + s.name + " (about " + News.num(zombies) + " of the dead). Convoy rolling.", s.cx * 16f, s.cy * 16f, News.COMMAND);
    }

    /** The player (incident commander, or anyone with a say) asks for the army for a town. */
    public void playerAsked(int settlement) {
        if (settlement < 0 || settlement >= playerAskedT.length) return;
        playerAskedT[settlement] = (float) w.time;
        armyCalled[settlement] = true;
    }

    /** Has the town asked for the army recently (or its incident commander, if the player is)? */
    public boolean asked(int settlement) {
        if (settlement < 0 || settlement >= armyCalled.length) return false;
        if (w.roles.holds(Roles.INCIDENT, settlement)) return w.time - playerAskedT[settlement] < 1440;
        return true;
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
        // only so many crews and aircraft are ready at once: more go up as the alert rises
        int up = 0;
        for (int j = 0; j < v.top; j++)
            if ((v.state[j] == Vehicles.V_ORBIT || v.state[j] == Vehicles.V_FLY) && v.type(j).flies && v.type(j).weapon != null && v.role[j] != Vehicles.R_STRIKE) up++;
        if (up >= 1 + w.gov.level) return;
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
        // a town quiet for half a day starts afresh: the next outbreak there is asked for from the start
        for (int st = 0; st < armyCalled.length && st < w.r.settlements.size(); st++) {
            if (armyAsked[st] > 0 && w.command.calm(st, 720)) {
                armyAsked[st] = 0;
                armyCalled[st] = false;
            }
        }
        if (w.gov.level >= 3 && strikeT <= 0) autoStrike();
        // (supplies travel as convoys now: see Logistics)
        // outside help arrives as formations on the highways, as much as the reported picture calls for
        int dead = w.totalZombies();
        float nat = w.command.nationalReported();
        int soldiers = w.forces.total(Ents.SOLDIER), police = w.forces.total(Ents.COP);
        if (w.gov.level >= 2 && waveT <= 0 && dead > 40 && wavesArrived < 6 && police < 4000 + nat * 0.1f) {
            arrive("police_reserve", "State Police");
            waveT = 240;
        }
        int alive = 0;
        for (Forces.Formation f : w.forces.formations) if (f.alive) alive++;
        boolean room = alive < w.c.rule("maxFormations", 45);
        // one measure of how many soldiers the reported picture calls for, for the Guard and the regulars alike (a
        // smaller war gets the same kind of army, artillery and armour included, only less of it)
        float want = w.c.rule("soldiersBase", 2500) + nat * w.c.rule("soldiersPerDead", 0.1f);
        if (w.gov.level >= 3 && guardT <= 0 && room && dead > 100 && soldiers < want) {
            arrive("guard_brigade", "National Guard");
            guardsArrived++;
            guardT = w.c.rule("mobiliseEvery", 240) * 1.25f;
        }
        // national mobilisation: a new formation every few game hours while the army is short of that
        if (w.gov.level >= 4 && regularT <= 0 && room && dead > 1500 && soldiers < want) {
            String[] kinds = {"infantry", "armour", "infantry", "artillery"};
            arrive(kinds[regularsArrived % kinds.length], "Army");
            regularsArrived++;
            regularT = w.c.rule("mobiliseEvery", 240);
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
        if (t == null || w.r.entryNodes.isEmpty() || !w.rules.outsideHelp()) return null;
        Forces.Formation f;
        int k = guardsArrived + regularsArrived + wavesArrived;
        boolean train = !w.r.railEntries.isEmpty() && k % 2 == 1;
        // every third unit from outside is flown in, while an airfield is ours
        Region.Facility field = null;
        if (k % 3 == 2 && !t.faction.equals("police"))
            for (Region.Facility a : w.r.facilities)
                if ((a.kind == Region.F_AIRBASE || a.kind == Region.F_AIRPORT) && w.strat.online(a.id) && (field == null || a.kind == Region.F_AIRBASE)) field = a;
        if (field != null) {
            f = w.forces.createFormation(t, field.x, field.y, true);
            w.navy.airlifts++;
            w.news.count("flownIn", "Airlift: the " + f.name + " is flown in to " + field.name + "|Airlift: %d more unit{s} flown in from outside", 1, field.x, field.y);
        } else if (train) {
            // by train over the border
            int re = w.r.railEntries.get(w.rng.nextInt(w.r.railEntries.size()));
            f = w.forces.createFormation(t, w.r.rails.nx[re], w.r.rails.ny[re], true);
        } else {
            int entry = w.r.entryNodes.get(w.rng.nextInt(w.r.entryNodes.size()));
            f = w.forces.createFormation(t, w.r.roads.nx[entry], w.r.roads.ny[entry], true);
        }
        if (t.faction.equals("police")) {
            wavesArrived++;
            w.stats.outsidePolice += f.strength();
        } else w.stats.outsideSoldiers += f.strength();
        w.news.radio(agency + ": the " + f.name + " (" + News.num(f.strength()) + ") is entering the country", f.hq.x, f.hq.y, News.COMMAND);
        // the general sends it straight to the capital until there's a job for it; a player commanding the army
        // finds it waiting at the border for orders
        if (w.forces.aiMay(f)) {
            Region.Settlement c = w.r.city();
            w.forces.order(f, c.cx * 16f, c.cy * 16f);
        }
        return f;
    }

    /** Air strikes on big masses of the dead where no one living is close (jets from a working air base or airport). */
    private void autoStrike() {
        if (!airfield()) return;
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
        Strategic.Horde h = w.strat.biggestHorde();
        boolean horde = h != null && h.count >= 600 && h.count > bz;
        if (horde) {
            tx = h.x;
            ty = h.y;
        } else if (best >= 0) {
            tx = w.sec.cx(best);
            ty = w.sec.cy(best);
        } else return;
        strikeT = w.c.rule("strikeEvery", 180);
        if (w.strategic) {
            // in fast-forward the sortie is settled as numbers
            int killed;
            if (horde) {
                killed = Math.min(h.count, (int) (h.count * 0.08f) + 150);
                h.count -= killed;
            } else {
                killed = Math.min(w.sec.zIn[best], (int) (w.sec.zIn[best] * 0.25f) + 60);
                w.sec.zIn[best] -= killed;
                w.sec.bodies[best] += killed;
            }
            w.stats.killsBySide[2] += killed;
            w.stats.zombieKills += killed;
            w.stats.airStrikes++;
            w.stats.strikeKills += killed;
            w.news.tally("strikes", "Air strike on " + (horde ? "a horde" : "a mass of the dead") + ": about {m} destroyed|Air strikes since the last report: {n} sortie{s}, about {m} of the dead destroyed", 1, killed, tx, ty, News.COMMAND);
            return;
        }
        if (w.sp.livingNear(w.e, tx, ty, 260, 1) > 0) return;
        callStrike(tx, ty, false);
    }

    /** Somewhere jets can fly from: the air base, or the airport, while it's ours. */
    public boolean airfield() {
        for (Region.Facility f : w.r.facilities)
            if ((f.kind == Region.F_AIRBASE || f.kind == Region.F_AIRPORT || f.kind == Region.F_BASE) && w.strat.online(f.id)) return true;
        return false;
    }

    /** An air strike on (x, y). Returns false if none can fly. */
    public boolean callStrike(float x, float y, boolean byPlayer) {
        if (byPlayer && strikesLeft == 0) return false;
        if (byPlayer && strikesLeft > 0) strikesLeft--;
        w.veh.strike(x, y);
        w.news.radio("Air strike called" + (byPlayer ? "" : " on a mass of the dead") + ". Clear the area!", x, y, News.COMMAND);
        return true;
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
}
