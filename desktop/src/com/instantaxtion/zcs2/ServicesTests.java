package com.instantaxtion.zcs2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Tests of the air and emergency services (Services, and their parts in Fires, Navy, Engineers): each feature on
 * today's maps, the main ones on a national map too, and saves and replays staying exact with them at work.
 * The game with their rules off is checked against the state before them by the golden test (SERVICES_OFF=1).
 */
final class ServicesTests {
    private ServicesTests() {
    }

    static final int NAT = Tests.NAT;

    static void check(boolean ok, String what) {
        Tests.check(ok, what);
    }

    static World game(long seed, int size, String scen) {
        return Tests.game(seed, size, scen, 2);
    }

    /** The camera over (x, y), each step. */
    static void watch(World w, float x, float y, float seconds) {
        for (int k = 0; k < (int) (seconds * 20); k++) {
            Tests.camOver(w, x, y);
            w.step();
        }
    }

    static int transport(World w) {
        for (int v = 0; v < w.veh.top; v++) if (w.veh.usable(v) && w.services.transport(v) && w.veh.state[v] == Vehicles.V_PARKED) return v;
        return -1;
    }

    /** A town or city some way from (x, y): 3.5 km on today's maps, 20 km on a national one. */
    static Region.Settlement townFrom(World w, float x, float y) {
        Region.Settlement best = null;
        float bd = Float.MAX_VALUE;
        for (Region.Settlement s : w.r.settlements) {
            if (s.kind != Region.S_TOWN && s.kind != Region.S_CITY) continue;
            float d = Math.abs((float) Math.hypot(s.cx * 16f - x, s.cy * 16f - y) - (w.national ? 20000 : 3500));
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    /** An air assault ordered by the player: the squad is flown in, set down near the spot, and the helicopter goes home. */
    static void airAssault(int size, long seed) {
        World w = game(seed, size, "sandbox");
        int v = transport(w);
        check(v >= 0, "no transport helicopter on a pad");
        Region.Settlement town = townFrom(w, w.veh.x[v], w.veh.y[v]);
        float[] p = w.r.openGroundNear(town.cx * 16f, town.cy * 16f, false);
        watch(w, p[0], p[1], 1);
        int soldiers0 = w.sq.count(Ents.SOLDIER);
        check(Actions.vehicleOrder(w, v, Orders.VO_ASSAULT, p[0], p[1]), "the order was refused");
        Services.AirTask t = w.services.airTask(v);
        check(t != null && t.kind == Services.T_ASSAULT, "no air task");
        int squad = -1;
        boolean seenAboard = false;
        for (int k = 0; k < 20 * 300 && w.services.airTask(v) != null; k++) {
            Tests.camOver(w, p[0], p[1]);
            w.step();
            Services.AirTask a = w.services.airTask(v);
            if (a != null && a.squad >= 0) squad = a.squad;
            if (w.veh.flying(v) && w.veh.crewN[v] > 0) seenAboard = true;
        }
        check(seenAboard, "nobody was flown");
        check(w.services.airTask(v) == null, "the assault never finished");
        check(squad >= 0, "no squad");
        Squads.Squad s = w.sq.get(squad);
        check(s.alive && s.faction == Ents.SOLDIER && s.n >= 3, "the squad isn't there: " + s.n);
        float[] at = w.sq.where(s);
        check(Math.hypot(at[0] - p[0], at[1] - p[1]) < 300, "set down " + (int) Math.hypot(at[0] - p[0], at[1] - p[1]) + " px from the spot");
        check(w.sq.count(Ents.SOLDIER) > soldiers0, "no more soldiers out than before");
        check(w.veh.state[v] == Vehicles.V_RTB || w.veh.state[v] == Vehicles.V_PARKED, "the helicopter didn't head home: " + Vehicles.STATE_NAMES[w.veh.state[v]]);
        check(w.veh.crewN[v] == 0, "people left aboard");
        check(Audit.check(w).isEmpty(), Audit.check(w));
        check(Audit.ledger(w) == 0, "ledger " + Audit.ledger(w));
        w.shutdown();
    }

    static void define() {
        Tests.add("services: an air assault flies a squad in and the helicopter goes home", new Tests.T() {
            public void run() {
                airAssault(1536, 7);
            }
        });
        Tests.add("services: on a national map too", new Tests.T() {
            public void run() {
                airAssault(NAT, 7);
            }
        });
        Tests.add("services: the general flies soldiers to a call no squad is near", new Tests.T() {
            public void run() {
                World w = game(7, 1536, "sandbox");
                int v = transport(w);
                // a town in the helicopters' reach with no soldiers of its own (no base or armory in it or near it)
                Region.Settlement town = null;
                float best = 0;
                for (Region.Settlement s : w.r.settlements) {
                    if (s.kind != Region.S_TOWN && s.kind != Region.S_CITY) continue;
                    float x = s.cx * 16f, y = s.cy * 16f;
                    if (Math.hypot(x - w.veh.x[v], y - w.veh.y[v]) > 8000) continue;
                    float far = Float.MAX_VALUE;
                    for (Region.Facility f : w.r.facilities)
                        if (f.kind == Region.F_BASE || f.kind == Region.F_ARMORY || f.kind == Region.F_AIRBASE || f.kind == Region.F_PORT) far = Math.min(far, (float) Math.hypot(f.x - x, f.y - y));
                    if (far > best) {
                        best = far;
                        town = s;
                    }
                }
                check(town != null && best > 3000, "no town far from the soldiers");
                float[] p = w.r.openGroundNear(town.cx * 16f, town.cy * 16f, false);
                w.gov.raiseTo(1);
                Tools.paintZombies(w, p[0], p[1], 40, 80, -1);
                for (int k = 0; k < 20 * 120 && w.services.assaults == 0; k++) {
                    Tests.camOver(w, p[0], p[1]);
                    w.step();
                }
                check(w.services.assaults > 0, "no air assault for " + w.disp.incidents.size() + " calls");
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.shutdown();
            }
        });
        Tests.add("services: a helicopter lifts a squad out and brings it home", new Tests.T() {
            public void run() {
                World w = game(7, 1536, "sandbox");
                int v = transport(w);
                Region.Settlement town = townFrom(w, w.veh.x[v], w.veh.y[v]);
                float[] p = w.r.openGroundNear(town.cx * 16f, town.cy * 16f, false);
                Squads.Squad s = Actions.squad(w, "rifleman", 6, p[0], p[1]);
                check(s != null && s.n == 6, "no squad");
                watch(w, p[0], p[1], 2);
                check(Actions.vehicleOrder(w, v, Orders.VO_EVAC, p[0], p[1]), "the lift was refused");
                int most = 0;
                for (int k = 0; k < 20 * 300 && w.services.airTask(v) != null; k++) {
                    Tests.camOver(w, p[0], p[1]);
                    w.step();
                    most = Math.max(most, w.veh.crewN[v]);
                }
                check(most >= 6, "only " + most + " got aboard");
                float[] at = w.sq.where(s);
                check(Math.hypot(at[0] - w.veh.homeX[v], at[1] - w.veh.homeY[v]) < 400, "the squad wasn't brought home");
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.shutdown();
            }
        });
        Tests.add("services: ambulances take the hurt and the bitten in, and the infection with them", new Tests.T() {
            public void run() {
                ambulances(1536);
            }
        });
        Tests.add("services: ambulances on a national map", new Tests.T() {
            public void run() {
                ambulances(NAT);
            }
        });
        Tests.add("services: fire crews get down, spray, and pull out when the dead come", new Tests.T() {
            public void run() {
                fireCrews(1536);
            }
        });
        Tests.add("services: fire crews on a national map", new Tests.T() {
            public void run() {
                fireCrews(NAT);
            }
        });
        Tests.add("services: coast guard boats come in to the shore", new Tests.T() {
            public void run() {
                World w = game(7, 1536, "evacuate");
                int best = -1;
                for (int k = 0; k < w.sec.n && best < 0; k++)
                    if (w.navy.coastal(k) && w.sec.settlement[k] >= 0 && w.sec.popIn[k] > 300) best = k;
                check(best >= 0, "no coastal street");
                Actions.evac(w, w.sec.settlement[best], true);
                int before = w.navy.rescued;
                watch(w, w.sec.cx(best), w.sec.cy(best), 60);
                check(w.navy.boatTrips > 0, "no boat reached the shore");
                check(w.navy.rescued > before, "nobody taken off");
                check(Audit.ledger(w) == 0, "ledger " + Audit.ledger(w));
                w.shutdown();
            }
        });
        Tests.add("services: aid convoys reach the refugee camps", new Tests.T() {
            public void run() {
                World w = game(7, 1536, "evacuate");
                w.setStrategic(true);
                for (int k = 0; k < 14 * 144 && w.services.aidDelivered == 0; k++) w.step();
                check(!w.society.camps.isEmpty(), "no camps");
                check(w.services.aidDelivered > 0, "no aid delivered");
                w.shutdown();
            }
        });
        Tests.add("services: checkpoints are manned, turn people back and hold the bitten", new Tests.T() {
            public void run() {
                checkpoints(1536);
                checkpoints(NAT);
            }
        });
        Tests.add("services: an escorted corridor keeps the dead off the road out", new Tests.T() {
            public void run() {
                World w = game(7, 1536, "sandbox");
                Region.Settlement town = null;
                for (Region.Settlement t : w.r.settlements) if (t.kind == Region.S_TOWN && (town == null || t.residents > town.residents)) town = t;
                check(!Actions.corridor(w, town.id) || w.gov.evacuating(town.id), "a corridor without an evacuation");
                Actions.evac(w, town.id, true);
                check(Actions.corridor(w, town.id), "no corridor");
                Services.Corridor c = w.services.corridors.get(0);
                check(c.pts.length >= 4 && c.escorts > 0, "no road or no escort");
                for (int k = 0; k + 1 < c.pts.length; k += 2) check(w.forces.escortNear(c.pts[k], c.pts[k + 1], 1), "a stretch without an escort");
                // a corridor follows the road: its points are junctions
                check(w.r.roads.nearestNode(c.pts[2], c.pts[3], 5) >= 0, "not on the road");
                Actions.evac(w, town.id, false);
                w.setStrategic(true);
                for (int k = 0; k < 3; k++) w.step();
                check(w.services.corridors.isEmpty(), "the corridor stayed open");
                w.shutdown();
            }
        });
        Tests.add("services: police and a bus carry people out of a street the dead hold", new Tests.T() {
            public void run() {
                Tests.content().setRule("airAssault", 0);
                World w;
                try {
                    w = game(7, 1536, "sandbox");
                } finally {
                    Tests.content().setRule("airAssault", 1);
                }
                Region.Settlement ct = w.r.city();
                int s = -1;
                float bd = Float.MAX_VALUE;
                for (int k = 0; k < w.sec.n; k++) {
                    if (w.sec.settlement[k] != ct.id || w.sec.popIn[k] < 120 || w.sec.popIn[k] > 1500) continue;
                    float d = (float) Math.hypot(w.sec.cx(k) - ct.cx * 16f, w.sec.cy(k) - ct.cy * 16f);
                    if (d < bd) {
                        bd = d;
                        s = k;
                    }
                }
                check(s >= 0, "no street");
                watch(w, w.sec.cx(s), w.sec.cy(s), 1);
                w.sec.zIn[w.sec.touch(s)] += Math.max(40, w.sec.popIn[s] / 2);
                for (int k = 0; k < 20 * 300 && w.services.rescuedTotal == 0; k++) {
                    Tests.camOver(w, w.sec.cx(s), w.sec.cy(s));
                    w.step();
                }
                check(w.services.rescuedTotal > 0, "nobody carried out");
                check(Audit.ledger(w) == 0, "ledger " + Audit.ledger(w));
                check(Audit.check(w).isEmpty(), Audit.check(w));
                w.shutdown();
            }
        });
        Tests.add("services: engineers build over time, stop for the dead, and blow a bridge", new Tests.T() {
            public void run() {
                engineers(1536);
            }
        });
        Tests.add("services: engineers on a national map", new Tests.T() {
            public void run() {
                engineers(NAT);
            }
        });
        Tests.add("services: martial law seizes vehicles, and the strains change fights and doctrine", new Tests.T() {
            public void run() {
                World w = game(7, 1536, "sandbox");
                float fuel0 = 0;
                for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_FUEL) fuel0 += f.fuel;
                w.gov.raiseTo(3);
                check(w.gov.martialLaw(), "no martial law");
                w.setStrategic(true);
                for (int k = 0; k < 6 * 6; k++) w.step();
                check(w.services.seized > 0, "nothing seized");
                boolean said = false;
                for (News.Item it : w.news.log) said |= it.text.contains("seized");
                check(said, "the news didn't say so");
                // the strains: fights as numbers go worse, grenades sooner against the armoured, no searching in the dark
                int s = w.r.sectorOfPx(Setup.originX, Setup.originY);
                check(w.services.strainKill(s) == 1 && w.services.strainLoss(s) == 1, "no strain, no change");
                w.ecology.strain[s] = 2;
                check(w.services.strainKill(s) < 1 && w.services.strainLoss(s) > 1, "armoured: no change");
                check(w.services.grenadePack(w.sec.cx(s), w.sec.cy(s)) < 6, "armoured: no more grenades");
                w.ecology.strain[s] = 1;
                check(w.services.strainLoss(s) > 1, "runners: no change");
                w.ecology.strain[s] = 3;
                w.time = Math.floor(w.time / 1440) * 1440 + 1440 + 60; // 01:00 the next night... (day starts at 08:00)
                check(w.night() == w.services.nightCaution(w.sec.cx(s), w.sec.cy(s)), "stalkers: caution only at night");
                w.ecology.strain[s] = 0;
                int n0 = w.news.log.size();
                w.strat.strain = 2;
                w.services.update(1);
                boolean doctrine = false;
                for (int k = n0; k < w.news.log.size(); k++) doctrine |= w.news.log.get(k).text.startsWith("Doctrine against the armoured");
                check(doctrine, "the news didn't explain the doctrine");
                w.shutdown();
            }
        });
        Tests.add("services: flights carry the bitten to other cities until the airport is closed", new Tests.T() {
            public void run() {
                // (as President: the player decides when the airport closes)
                World w = Setup.newGame(Tests.content(), 1536, RegionGen.COAST, 7, Tests.content().scenario("sandbox"), 2, Roles.PRESIDENT, 60000);
                Region.Facility ap = null;
                for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_AIRPORT) ap = f;
                check(ap != null, "no airport");
                // many hidden bitten in the capital
                int st = w.r.city().id; // (the passengers come from the whole country: the bitten among them by their share)
                for (int s : w.sec.idx.bySettlement[st]) {
                    int k = w.sec.popIn[s] / 5;
                    w.sec.popIn[w.sec.touch(s)] -= k;
                    w.sec.inf[s] += k;
                }
                w.setStrategic(true);
                for (int k = 0; k < 6 * 12 && w.services.seededTotal == 0; k++) w.step();
                check(w.services.flightsOut > 0, "no flights");
                check(w.services.seededTotal > 0, "nobody carried it");
                check(w.services.flightsStory() != null, "no story");
                check(Audit.ledger(w) == 0, "ledger " + Audit.ledger(w));
                Actions.airport(w, true);
                int out = w.services.flightsOut;
                for (int k = 0; k < 6 * 6; k++) w.step();
                check(w.services.flightsOut == out, "flights after the airport closed");
                w.shutdown();
                // the AI President closes it by himself once the alert is high enough
                w = game(7, 1536, "sandbox");
                w.setStrategic(true);
                for (int k = 0; k < 12; k++) w.step();
                check(!w.services.airportClosed && w.services.flightsOut > 0, "no flights in a quiet country");
                w.gov.raiseTo(2);
                for (int k = 0; k < 12; k++) w.step();
                check(w.services.airportClosed, "the President left the airport open at " + Gov.LEVELS[w.gov.level]);
                w.shutdown();
            }
        });
        Tests.add("services: saves and replays stay exact with the services at work", new Tests.T() {
            public void run() throws Exception {
                World w = Setup.newGame(Tests.content(), 1536, RegionGen.COAST, 7, Tests.content().scenario("sandbox"), 2, Roles.GOD, 60000);
                int v = transport(w);
                Region.Settlement town = townFrom(w, w.veh.x[v], w.veh.y[v]);
                float[] p = w.r.openGroundNear(town.cx * 16f, town.cy * 16f, false);
                watch(w, p[0], p[1], 1);
                // (everything through Actions, so the replay does it too)
                Actions.vehicleOrder(w, v, Orders.VO_ASSAULT, p[0], p[1]);
                Actions.engineer(w, Engineers.J_WALL, p[0] + 200, p[1]);
                Actions.paint(w, p[0] + 300, p[1] + 200, 60, 80, -1);
                Actions.people(w, "civilian", 6, p[0] - 100, p[1] - 60);
                Actions.quarantine(w, town.id, true);
                watch(w, p[0], p[1], 25);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                SaveIO.save(w, bo);
                World l = SaveIO.load(Tests.content(), new ByteArrayInputStream(bo.toByteArray()), 2);
                check(Audit.hash(l) == Audit.hash(w), "different straight after loading");
                for (int k = 0; k < 30 * 20; k++) {
                    Tests.camOver(w, p[0], p[1]);
                    Tests.camOver(l, p[0], p[1]);
                    w.step();
                    l.step();
                    if (k % 100 == 0) check(Audit.hash(l) == Audit.hash(w), "diverged " + k + " steps after loading");
                }
                check(l.news.log.size() == w.news.log.size(), "radio logs differ");
                Replay r = Replay.of(Tests.content(), w, 2);
                r.runTo(w.tick);
                check(Audit.hash(r.w) == Audit.hash(w), "the replay came out different");
                w.shutdown();
                l.shutdown();
                r.w.shutdown();
            }
        });
    }

    static void ambulances(int size) {
        World w = game(7, size, "sandbox");
        Region.Facility h = null;
        for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_HOSPITAL && f.settlement == w.r.city().id) h = f;
        check(h != null && h.vpool[w.c.vtype("ambulance").index] > 0, "no ambulances at the hospital");
        float[] p = w.r.openGroundNear(h.x + 700, h.y + 300, false);
        watch(w, p[0], p[1], 1);
        Content.Unit civ = w.c.unit("civilian");
        for (int k = 0; k < 6; k++) {
            float[] q = w.r.openGroundNear(p[0] + k * 30, p[1], false);
            int i = w.spawnCiv(q[0], q[1], civ);
            w.stats.startPeople++;
            w.e.hp[i] = w.e.maxHp[i] * 0.4f;
            if (k % 2 == 0) {
                w.e.flags[i] |= Ents.F_INFECTED;
                w.e.infT[i] = 400;
            }
        }
        int hs = w.r.sectorOfPx(h.x, h.y);
        float sick0 = w.sec.inf[hs], sick = sick0;
        boolean medics = false;
        for (int k = 0; k < 20 * 300 && w.services.carriedBitten == 0; k++) {
            Tests.camOver(w, p[0], p[1]);
            w.step();
            sick = Math.max(sick, w.sec.inf[hs]);
            for (int i = 0; i < w.e.top && !medics; i++)
                medics = w.e.loc[i] == Ents.FREE && (w.e.flags[i] & Ents.F_CREW) != 0 && w.unitOf(i).id.equals("paramedic");
        }
        check(medics, "no paramedics on foot");
        check(w.services.carriedBitten > 0, "nobody bitten taken in (" + w.services.carried + " carried)");
        boolean intoHospital = false;
        for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_HOSPITAL && w.r.sectorOfPx(f.x, f.y) == w.services.bittenInto) intoHospital = true;
        check(sick > sick0 || intoHospital, "the infection didn't reach a hospital");
        check(Audit.ledger(w) == 0, "ledger " + Audit.ledger(w));
        check(Audit.check(w).isEmpty(), Audit.check(w));
        // the decision: no more bitten taken in (the hurt still are)
        Actions.admitBitten(w, h.settlement, false);
        check(!w.services.admitsBitten(h.settlement), "the order didn't hold");
        // (those already in an ambulance are taken in; then no more)
        for (int k = 0; k < 20 * 120 && !w.services.ambs.isEmpty(); k++) {
            Tests.camOver(w, p[0], p[1]);
            w.step();
        }
        int bitten = w.services.carriedBitten;
        for (int k = 0; k < 3; k++) {
            float[] q = w.r.openGroundNear(p[0] - 60 - k * 30, p[1] + 40, false);
            int i = w.spawnCiv(q[0], q[1], civ);
            w.stats.startPeople++;
            w.e.flags[i] |= Ents.F_INFECTED;
            w.e.infT[i] = 400;
            w.e.hp[i] = w.e.maxHp[i] * 0.4f;
        }
        watch(w, p[0], p[1], 120);
        check(w.services.carriedBitten == bitten, "the bitten were still taken in");
        check(Audit.ledger(w) == 0, "ledger " + Audit.ledger(w));
        w.shutdown();
    }

    static void fireCrews(int size) {
        World w = game(7, size, "sandbox");
        Region.Facility st = null;
        for (Region.Facility f : w.r.facilities) if (f.kind == Region.F_FIRE && f.settlement == w.r.city().id) st = f;
        check(st != null, "no fire station");
        watch(w, st.x, st.y, 1);
        int b = -1;
        float bd = Float.MAX_VALUE;
        for (int s0 = 0; s0 < w.sec.n; s0++) {
            if (Math.abs(w.sec.cx(s0) - st.x) > 3000 || Math.abs(w.sec.cy(s0) - st.y) > 3000 || !w.act.activePx(w.sec.cx(s0), w.sec.cy(s0))) continue;
            for (int k : w.secBuildings[s0]) {
                if (w.r.bfloors(k) <= 0 || w.r.doorX(k) < 0 || w.r.bres(k) <= 0) continue;
                float d = (float) Math.hypot(w.r.doorX(k) - st.x, w.r.doorY(k) - st.y);
                if (d > 700 && d < bd) {
                    bd = d;
                    b = k;
                }
            }
        }
        check(b >= 0, "no building");
        float bx = w.r.doorX(b), by = w.r.doorY(b);
        watch(w, bx, by, 1);
        w.fires.ignite(b, 0.6f);
        int spray = 0;
        float peak = 0;
        for (int k = 0; k < 20 * 200 && spray < 3; k++) {
            Tests.camOver(w, bx, by);
            w.weather.state = Weather.CLEAR;
            w.step();
            peak = Math.max(peak, w.fires.heat(b));
            spray = 0;
            for (int i = 0; i < w.e.top; i++) if (w.e.loc[i] == Ents.FREE && (w.e.flags[i] & Ents.F_CREW) != 0 && w.e.state[i] == Services.CREW_SPRAY) spray++;
        }
        check(spray >= 3, "only " + spray + " firefighters at the hoses");
        for (int k = 0; k < 20 * 40; k++) {
            Tests.camOver(w, bx, by);
            w.weather.state = Weather.CLEAR;
            w.step();
        }
        check(w.fires.heat(b) < peak, "the hoses did nothing: " + w.fires.heat(b) + " vs " + peak);
        check(Audit.ledger(w) == 0, "ledger " + Audit.ledger(w));
        // the dead come: they run back to the engine and it pulls out
        Tools.paintZombies(w, bx + 120, by + 60, 25, 40, -1);
        int working = 1;
        boolean pulledOut = false;
        for (int k = 0; k < 20 * 40 && !pulledOut; k++) {
            Tests.camOver(w, bx, by);
            w.step();
            if (k == 20 * 3) {
                for (int i = 0; i < w.e.top; i++)
                    if (w.e.loc[i] == Ents.FREE && w.e.kind[i] == Ents.CIV && (w.e.flags[i] & Ents.F_CREW) != 0 && w.e.state[i] == Services.CREW_SPRAY) working++;
                check(working == 1, (working - 1) + " firefighters still at the hoses with the dead on them");
            }
            // the engine pulls out (and heads home)
            for (int v = 0; v < w.veh.top; v++)
                if (w.veh.usable(v) && w.veh.type(v).id.equals("fire_engine") && w.veh.state[v] == Vehicles.V_DRIVE && w.veh.destX[v] == w.veh.homeX[v] && w.veh.destY[v] == w.veh.homeY[v]
                        && Math.hypot(w.veh.x[v] - bx, w.veh.y[v] - by) < 900) pulledOut = true;
        }
        check(pulledOut, "the engine never pulled out");
        check(Audit.check(w).isEmpty(), Audit.check(w));
        check(Audit.ledger(w) == 0, "ledger " + Audit.ledger(w));
        // fast-forward takes the crews in
        w.camDetail = false;
        w.setStrategic(true);
        for (int i = 0; i < w.e.top; i++) check(w.e.loc[i] == Ents.NONE || (w.e.flags[i] & Ents.F_CREW) == 0, "a crew member left out in fast-forward");
        w.shutdown();
    }

    static void checkpoints(int size) {
        World w = game(7, size, "sandbox");
        Region.Settlement town = null;
        for (Region.Settlement t : w.r.settlements) if (t.kind == Region.S_TOWN && !t.edgeNodes.isEmpty() && (town == null || t.residents > town.residents)) town = t;
        int node = town.edgeNodes.get(0);
        float x = w.r.roads.nx[node], y = w.r.roads.ny[node];
        watch(w, x, y, 1);
        Actions.quarantine(w, town.id, true);
        boolean manned = false;
        for (int k = 0; k < 20 * 300 && !manned; k++) {
            Tests.camOver(w, x, y);
            w.step();
            for (Services.Checkpoint cp : w.services.checkpoints) {
                if (cp.node != node || cp.squad < 0) continue;
                Squads.Squad q = w.sq.get(cp.squad);
                int ld = q.leader(w.e);
                manned = ld >= 0 && w.e.loc[ld] == Ents.FREE && Math.hypot(w.e.x[ld] - x, w.e.y[ld] - y) < 100;
            }
        }
        check(manned, "the checkpoint wasn't manned (" + size + ")");
        Content.Unit civ = w.c.unit("civilian");
        for (int k = 0; k < 6; k++) {
            float[] q = w.r.openGroundNear(x + 30 + k * 8, y + 20, false);
            int i = w.spawnCiv(q[0], q[1], civ);
            w.stats.startPeople++;
            if (k < 2) w.e.flags[i] |= Ents.F_INFECTED;
            w.e.infT[i] = 900;
        }
        watch(w, x, y, 6);
        int caught = 0, stopped = 0;
        for (Services.Checkpoint cp : w.services.checkpoints) {
            caught += cp.caught;
            stopped += cp.stopped;
        }
        check(caught > 0, "no bitten held (" + size + ")");
        check(stopped > 0, "nobody turned back (" + size + ")");
        check(Audit.ledger(w) == 0, "ledger " + Audit.ledger(w));
        // lifted: the posts stand down
        Actions.quarantine(w, town.id, false);
        watch(w, x, y, 2);
        check(w.services.checkpoints.isEmpty(), "checkpoints left after the quarantine");
        w.shutdown();
    }

    static void engineers(int size) {
        World w = game(7, size, "sandbox");
        Region.Settlement ct = w.r.city();
        RoadGraph g = w.r.roads;
        int n = g.nearestNode(ct.cx * 16f + 1200, ct.cy * 16f, 2000);
        float x = g.nx[n], y = g.ny[n];
        for (int k = g.adjStart[n]; k < g.adjStart[n + 1]; k++) {
            int e = g.adjEdge[k];
            if (g.ebridge[e]) continue;
            int o = g.ea[e] == n ? g.eb[e] : g.ea[e];
            float mx = (g.nx[n] + g.nx[o]) / 2, my = (g.ny[n] + g.ny[o]) / 2;
            if (Tile.roadish(w.r.tileAtPx(mx, my))) {
                x = mx;
                y = my;
                break;
            }
        }
        watch(w, x, y, 1);
        check(Actions.engineer(w, Engineers.J_WALL, x, y), "the order was refused");
        check(w.r.tileAtPx(x, y) != Tile.WALL, "built at once");
        check(w.engineers.jobs.size() == 1, "no job");
        boolean squad = false;
        for (int k = 0; k < 20 * 600 && !w.engineers.jobs.isEmpty(); k++) {
            Tests.camOver(w, x, y);
            w.step();
            if (!w.engineers.jobs.isEmpty() && w.engineers.jobs.get(0).squad >= 0) squad = true;
        }
        check(squad, "no squad sent");
        check(w.engineers.jobsDone == 1, "the wall wasn't finished");
        check(w.r.tileAtPx(x, y) == Tile.WALL, "no wall at the spot");
        // the dead on the site stop the work
        float fx = x + 900, fy = y;
        float[] q = w.r.openGroundNear(fx, fy, false);
        check(Actions.engineer(w, Engineers.J_FOB, q[0], q[1]), "the forward base was refused");
        Engineers.Job j = w.engineers.jobs.get(0);
        for (int k = 0; k < 20 * 400 && j.done < j.need * 0.3f && !w.engineers.jobs.isEmpty(); k++) {
            Tests.camOver(w, q[0], q[1]);
            w.step();
        }
        check(j.done > 0, "no work on the base");
        Tools.paintZombies(w, q[0] + 40, q[1], 30, 30, -1);
        for (int k = 0; k < 20 * 3; k++) {
            Tests.camOver(w, q[0], q[1]);
            w.step();
        }
        check(j.stopped || j.squad < 0 || w.engineers.jobsStopped > 0, "the work went on with the dead on the site");
        // a bridge, blown by the engineers in their time
        int e = -1;
        for (int k = 0; k < g.edgeCount && e < 0; k++) if (g.ebridge[k] && g.ealive[k]) e = k;
        if (e >= 0) {
            float bx = (g.nx[g.ea[e]] + g.nx[g.eb[e]]) / 2, by = (g.ny[g.ea[e]] + g.ny[g.eb[e]]) / 2;
            check(Actions.engineer(w, Engineers.J_BRIDGE, bx, by), "the bridge order was refused");
            int edge = w.engineers.jobs.get(w.engineers.jobs.size() - 1).edge;
            check(g.ealive[edge], "blown at once");
            for (int k = 0; k < 20 * 900 && g.ealive[edge]; k++) {
                Tests.camOver(w, bx, by);
                w.step();
            }
            check(!g.ealive[edge], "the bridge still stands");
        }
        check(Audit.check(w).isEmpty(), Audit.check(w));
        w.shutdown();
    }
}
