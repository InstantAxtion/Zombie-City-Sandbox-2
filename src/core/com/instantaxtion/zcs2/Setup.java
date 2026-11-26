package com.instantaxtion.zcs2;

import java.util.ArrayList;
import java.util.List;

/** Builds a new game: the region, its police and army in their stations and bases, and the scenario's start. */
public final class Setup {
    /** Where the outbreak starts (for the camera), set by scenario(). */
    public static float originX, originY;
    /** Where the outbreak itself is (originX/Y moves to the player's person, if there is one). */
    public static float outbreakX, outbreakY;

    public static World newGame(Content c, int size, int preset, long seed, Content.Scenario sc, int threads) {
        return newGame(c, size, preset, seed, sc, threads, -1, 60000);
    }

    /** The role a scenario is played as unless the player picks another. */
    public static int defaultRole(Content.Scenario sc) {
        if (sc == null) return Roles.GOD;
        int r = Roles.byId(sc.role);
        if (r >= 0) return r;
        if (sc.mode.equals("commander")) return Roles.PRESIDENT;
        if (sc.mode.equals("survivor")) return Roles.SURVIVOR;
        return Roles.GOD;
    }

    /** A new game played as a role (-1 = the scenario's own), with maxInd people simulated in detail at most. */
    public static World newGame(Content c, int size, int preset, long seed, Content.Scenario sc, int threads, int role, int maxInd) {
        return newGame(c, size, preset, seed, sc, threads, role, maxInd, null);
    }

    /** As above, on a region already generated from the same size, preset and seed (the New Game preview's), or null. */
    public static World newGame(Content c, int size, int preset, long seed, Content.Scenario sc, int threads, int role, int maxInd, Region pre) {
        if (role < 0) role = defaultRole(sc);
        Region r = pre != null && pre.W == size && pre.preset == preset && pre.seed == seed ? pre : region(size, preset, seed, threads);
        World w = new World(c, r, seed, threads);
        w.maxIndividuals = maxInd;
        w.scenario = sc;
        Script.attach(w);
        w.mode = Roles.modeFor(role);
        Director.Header h = w.director.header;
        h.size = size;
        h.preset = preset;
        h.seed = seed;
        h.role = role;
        h.maxInd = maxInd;
        h.scenarioIni = sc == null ? "" : sc.toIni();
        if (sc != null && sc.season >= 0) w.weather.startDay = sc.season % 365;
        forces(w);
        Region.Settlement city = r.city();
        originX = city.cx * 16f;
        originY = city.cy * 16f;
        if (sc != null) {
            scenario(w, sc);
            extras(w, sc);
        }
        outbreakX = originX;
        outbreakY = originY;
        w.roles.assign(role, originX, originY);
        if (role == Roles.SURVIVOR && w.playerEnt < 0) survivor(w);
        // someone to play: hiding, scavenging, needs, weapons, cars and companions (Survival)
        if (w.playerEnt >= 0 && (role == Roles.SURVIVOR || role == Roles.OFFICER)) w.enableSurvival();
        if (w.playerEnt >= 0) {
            originX = w.e.x[w.playerEnt];
            originY = w.e.y[w.playerEnt];
        }
        w.stats.startPolice = w.sq.count(Ents.COP);
        w.stats.startSoldiers = w.sq.count(Ents.SOLDIER);
        w.stats.startCivilians = w.sec.totalPop();
        w.stats.startPeople = Audit.people(w);
        w.stats.event("The day begins in " + r.name, -1, -1);
        return w;
    }

    /** Map sides from this up are real-scale (national) maps, made by NationGen; today's sizes by RegionGen. */
    public static final int NATIONAL_FROM = 4096;

    public static boolean national(int size) {
        return size >= NATIONAL_FROM;
    }

    /** The map for a game: a nation (real scale) for the national sizes, else today's RegionGen map. */
    public static Region region(int size, int preset, long seed, int threads) {
        if (!national(size)) return RegionGen.generate(size, preset, seed);
        Nation n = NationGen.generate(size, preset, seed, Math.max(1, Math.min(4, threads)));
        return Region.national(n, null);
    }

    /** Parking places in a facility's lot (or round its yard). */
    static List<float[]> spots(World w, Region.Facility f, int want) {
        List<float[]> out = new ArrayList<float[]>();
        Region r = w.r;
        if (f.bx1 > f.bx0) {
            for (int y = f.by0 + 1; y <= f.by1 - 1 && out.size() < want; y += 2)
                for (int x = f.bx0 + 1; x <= f.bx1 - 1 && out.size() < want; x += 3) {
                    int t = r.t(x, y);
                    if (t == Tile.LOT || t == Tile.BASE || t == Tile.PLAZA) out.add(new float[]{x * 16 + 8, y * 16 + 8});
                }
        }
        for (int k = 0; out.size() < want && k < want * 8; k++) {
            float ang = k * 2.4f, rad = 20 + k * 3;
            float x = f.x + (float) Math.cos(ang) * rad, y = f.y + (float) Math.sin(ang) * rad;
            if (r.walkablePx(x, y)) out.add(new float[]{x, y});
        }
        while (out.size() < want) out.add(new float[]{f.x, f.y});
        return out;
    }

    private static Squads.Squad squad(World w, Region.Facility f, int faction, String label, String[] units, String vehicle, float[] at) {
        Squads.Squad s = w.sq.create(faction, f.id, label);
        for (int k = 0; k < units.length; k++) {
            Content.Unit u = w.c.unit(units[k]);
            if (u == null) continue;
            float x = f.x + (k % 4) * 7 - 10, y = f.y + (k / 4) * 7;
            if (!w.r.walkablePx(x, y)) {
                x = f.x;
                y = f.y;
            }
            int i = w.spawnUnit(u, x, y);
            w.sq.add(s, i);
        }
        if (vehicle != null && at != null) {
            Content.VType vt = w.c.vtype(vehicle);
            if (vt != null) {
                int v = w.veh.add(vt, at[0], at[1], -1.57f, f.id);
                s.vehicle = v;
                w.veh.squad[v] = s.id;
            }
        }
        return s;
    }

    private static String[] repeat(String id, int n) {
        String[] a = new String[n];
        for (int i = 0; i < n; i++) a[i] = id;
        return a;
    }

    static void forces(World w) {
        Region r = w.r;
        Forces fo = w.forces;
        float perRes = w.c.rule("policePerResident", 0.0022f);
        boolean hq;
        java.util.Set<Integer> hqDone = new java.util.HashSet<Integer>();
        int nStatic = r.facilities.size();
        for (int fi = 0; fi < nStatic; fi++) {
            Region.Facility f = r.facilities.get(fi);
            Region.Settlement st = f.settlement >= 0 ? r.settlements.get(f.settlement) : null;
            if (f.kind == Region.F_POLICE && st != null) {
                // officers wait at the station as numbers; a squad is made only when it's sent out
                int stations = 0;
                for (Region.Facility g : r.facilities) if (g.kind == Region.F_POLICE && g.settlement == f.settlement) stations++;
                int officers = (int) (st.residents * perRes / Math.max(1, stations)) + 8;
                int patrols = Math.max(2, officers / 4);
                // a station with a few cars has a K9 team and a traffic unit among them (same number of people)
                for (String special : new String[]{"k9", "traffic"})
                    if (patrols >= 4 && w.c.squadT(special) != null) {
                        fo.addSquadToPool(f, w.c.squadT(special), 1);
                        patrols--;
                    }
                fo.addSquadToPool(f, w.c.squadT("patrol"), patrols);
                hq = (st.kind == Region.S_CITY) && hqDone.add(st.id);
                if (hq) {
                    f.name = st.name + " Police HQ";
                    int teams = 1 + st.residents / 300000;
                    fo.addSquadToPool(f, w.c.squadT("swat"), teams);
                    fo.addSquadToPool(f, w.c.squadT("riot"), teams);
                    Content.VType heli = w.c.vtype("police_heli");
                    List<float[]> park = spots(w, f, 1);
                    if (heli != null) w.veh.add(heli, park.get(0)[0], park.get(0)[1], 0, f.id);
                }
                f.ammo = fo.armedAt(f, Ents.COP) * 150f;
            } else if (f.kind == Region.F_BASE) {
                fo.addSquadToPool(f, w.c.squadT("rifle"), 2);
                f.ammo = 40000;
                f.fuel = 100;
                // the base's battalion (and, at the first base, armour and guns)
                fo.createFormation(w.c.formationT("infantry"), f.x, f.y, false).home = f.id;
                if (firstBase(w, f)) {
                    if (r.W > 1024) fo.createFormation(w.c.formationT("armour"), f.x, f.y, false).home = f.id;
                    fo.createFormation(w.c.formationT("artillery"), f.x, f.y, false).home = f.id;
                }
                aircraft(w, f);
            } else if (f.kind == Region.F_AIRBASE) {
                fo.addSquadToPool(f, w.c.squadT("fireteam"), 3);
                f.ammo = 20000;
                aircraft(w, f);
            } else if (f.kind == Region.F_PORT) {
                fo.addSquadToPool(f, w.c.squadT("fireteam"), 2);
                f.ammo = 20000;
                fo.createFormation(w.c.formationT("marines"), f.x, f.y, false).home = f.id;
            } else if (f.kind == Region.F_ARMORY) {
                fo.addSquadToPool(f, w.c.squadT("guard"), 2);
                f.ammo = 6000;
            } else if (f.kind == Region.F_PRISON) {
                fo.addSquadToPool(f, w.c.squadT("patrol"), 3);
                f.ammo = 2000;
            }
        }
    }

    private static boolean firstBase(World w, Region.Facility f) {
        for (Region.Facility g : w.r.facilities) if (g.kind == Region.F_BASE) return g == f;
        return false;
    }

    /** Helicopters on the pads of a base's settlement. */
    private static void aircraft(World w, Region.Facility f) {
        int gun = 0;
        int scale = w.r.W >= 1536 ? 2 : 1;
        for (Region.Facility h : w.r.facilities) {
            if (h.kind != Region.F_HELIPAD || h.settlement != f.settlement) continue;
            Content.VType t = w.c.vtype(gun < 1 + scale ? "gunship" : "transport_heli");
            if (t != null) w.veh.add(t, h.x, h.y, 0, f.id);
            gun++;
        }
    }

    // ------------------------------------------------------------------ scenarios

    static void scenario(World w, Content.Scenario sc) {
        Region r = w.r;
        String o = sc.origin;
        // an origin of content.ini's may name a built-in start of its own (start = lab...)
        Data.Origin named = Data.origin(o);
        if (named != null && named.facility < 0 && !named.start.equals(o)) o = named.start;
        int n = sc.zombies;
        Region.Facility at = null;
        if (o.equals("random")) {
            // a city or town picked by the seed, a street in it: a few of the dead out, more indoors round about,
            // and people bitten who don't know what's coming
            List<Region.Settlement> places = new ArrayList<Region.Settlement>();
            for (Region.Settlement s : r.settlements) if (s.kind == Region.S_CITY || s.kind == Region.S_TOWN) places.add(s);
            Region.Settlement s = places.get(Rng.hashInt(w.seed, 5, 17, places.size()));
            float[] p = landNear(w, s.cx * 16f, s.cy * 16f, s.radius * 10f, 7);
            originX = p[0];
            originY = p[1];
            int ind = Math.min(n, 40);
            pack(w, p[0], p[1], ind, 8, 7);
            spreadIndoors(w, r.sectorOfPx(p[0], p[1]), n - ind, 1);
            hiddenBitten(w, s.id, r.sectorOfPx(p[0], p[1]), 8, 6, 18, 41);
            latent(w, s.id, 28, 9, 30, 120, 1690, 51);
            Region.Settlement next = nearestTown(w, s, s);
            if (next != null) latent(w, next.id, 8, 7, 21, 300, 1820, 52);
            return;
        }
        if (o.equals("port")) {
            // a ship came in to the docks of the biggest town on the coast: the dead come down onto the waterfront
            // and into the streets behind it (not the naval station, where the marines are)
            Region.Settlement best = null;
            Region.Facility navy = facility(w, Region.F_PORT);
            for (Region.Settlement s : r.settlements) {
                if (s.kind != Region.S_CITY && s.kind != Region.S_TOWN || !w.roles.coastal(s)) continue;
                if (navy != null && navy.settlement == s.id) continue;
                if (best == null || s.residents > best.residents) best = s;
            }
            if (best == null) best = r.city();
            float[] p = shore(w, best);
            originX = p[0];
            originY = p[1];
            // (the first few down the gangway at dawn; the rest still in the hold and the dock sheds)
            int ind = Math.min(n, 60);
            pack(w, p[0], p[1], ind, 9, 11);
            spreadIndoors(w, r.sectorOfPx(p[0], p[1]), n - ind, 1);
            // some of the crew got ashore in the night
            hiddenBitten(w, best.id, r.sectorOfPx(p[0], p[1]), 6, 6, 16, 43);
            latent(w, best.id, 32, 9, 27, 60, 1690, 53);
            Region.Settlement next = nearestTown(w, best, best);
            if (next != null) latent(w, next.id, 8, 7, 21, 300, 1820, 54);
            o = "done";
        }
        if (o.equals("state")) {
            // a whole state has fallen: the dead in its towns and countryside, its people mostly gone
            int st = r.states.size() > 1 ? 1 + Rng.hashInt(w.seed, 3, 4, r.states.size() - 1) : 0;
            if (r.states.isEmpty()) st = -1;
            w.roles.campaignState = st;
            List<Integer> secs = new ArrayList<Integer>();
            for (int s = 0; s < w.sec.n; s++) if (w.roles.stateOfSector(s) == st && w.sec.connect[s] != 0) secs.add(s);
            int placedHordes = 0;
            for (int k = 0; k < 6 && !secs.isEmpty(); k++) {
                int s = secs.get(Rng.hashInt(w.seed, k, 33, secs.size()));
                w.strat.addHorde(w.sec.cx(s), w.sec.cy(s), n / 12);
                placedHordes += n / 12;
            }
            int left = n - placedHordes;
            // the rest are in its towns (nests where people lived) and its fields; exactly as many as asked
            List<Integer> towns = new ArrayList<Integer>();
            long townHomes = 0;
            for (int s : secs) {
                int set = w.sec.settlement[s];
                if (set >= 0 && !r.settlements.get(set).capital && w.sec.home[s] > 0) {
                    towns.add(s);
                    townHomes += w.sec.home[s];
                }
            }
            int inTowns = towns.isEmpty() ? 0 : left * 2 / 3, placed = 0;
            for (int s : towns) {
                int m = (int) ((long) inTowns * w.sec.home[s] / Math.max(1, townHomes));
                w.sec.zIn[w.sec.touch(s)] += m;
                placed += m;
            }
            left -= placed;
            if (secs.isEmpty()) w.sec.zIn[w.sec.touch(r.sectorOfPx(r.city().cx * 16f, r.city().cy * 16f))] += left;
            else for (int k = 0; k < left; k += 50) w.sec.zIn[w.sec.touch(secs.get(Rng.hashInt(w.seed, k, 34, secs.size())))] += Math.min(50, left - k);
            // its people mostly got away before it fell: they're refugees in the capital now
            Region.Settlement cap = r.city();
            List<Integer> capSecs = new ArrayList<Integer>();
            for (int s : w.sec.idx.bySettlement[cap.id]) if (w.sec.home[s] > 0) capSecs.add(s);
            for (int s : secs) {
                int set = w.sec.settlement[s];
                if (set >= 0 && r.settlements.get(set).capital || capSecs.isEmpty()) continue;
                int fled = w.sec.popIn[s] * 4 / 5;
                if (fled <= 0) continue;
                w.sec.popIn[w.sec.touch(s)] -= fled;
                w.sec.popIn[w.sec.touch(capSecs.get(s % capSecs.size()))] += fled;
                if (set >= 0) w.strat.refuge[set][cap.id] += fled;
            }
            if (st >= 0) {
                Region.Settlement c = r.settlements.get(r.states.get(st).capitalCity);
                originX = c.cx * 16f;
                originY = c.cy * 16f;
            }
            w.gov.level = 3;
            o = "done";
        }
        if (o.startsWith("xy:")) {
            // a place on the map (custom scenarios): a pack of the dead there, exactly as many as asked
            String[] p = o.substring(3).split(",");
            float x = Float.parseFloat(p[0].trim()) * r.px(), y = Float.parseFloat(p[1].trim()) * r.px();
            float[] q = landNear(w, x, y, 400, 9);
            originX = q[0];
            originY = q[1];
            int ind = Math.min(n, 400);
            pack(w, q[0], q[1], ind, 8, 9);
            if (n > ind) w.sec.zIn[w.sec.touch(r.sectorOfPx(q[0], q[1]))] += n - ind;
            o = "done";
        }
        if (o.equals("lab")) at = facility(w, Region.F_LAB);
        else if (o.equals("prison")) at = facility(w, Region.F_PRISON);
        else if (o.equals("festival")) at = facility(w, Region.F_PARK);
        // an origin of content.ini's at a kind of facility (the hospital, the railway station...): street of the dead
        // out on the street there, the rest in the buildings round about
        int street = 300;
        Data.Origin og = o.equals("done") ? null : Data.origin(o);
        if (at == null && og != null && og.facility >= 0) {
            at = facility(w, og.facility);
            street = og.street;
            if (at == null) {
                // this map has none: the capital's centre
                float[] q = landNear(w, r.city().cx * 16f, r.city().cy * 16f, 400, 9);
                originX = q[0];
                originY = q[1];
                int ind = Math.min(n, street);
                pack(w, q[0], q[1], ind, 8, 9);
                if (n > ind) spreadIndoors(w, r.sectorOfPx(q[0], q[1]), n - ind, 2);
                o = "done";
            }
        }
        if (at != null && o.equals("prison")) {
            originX = at.x;
            originY = at.y;
            // it started inside, in the cell blocks; a few are already through the gates, and some who got out
            // before the lockdown are bitten and on their way to the nearest town
            int s = r.sectorOfPx(at.x, at.y);
            int out = Math.min(n, Math.max(1, n / 3));
            float[] gate = landNear(w, at.x, at.y + 120, 60, 13);
            pack(w, gate[0], gate[1], out, 8, 13);
            w.sec.zIn[w.sec.touch(s)] += n - out;
            w.sec.hot[w.sec.touch(s)] = 30;
            Region.Settlement town = nearestTown(w, r.settlements.get(Math.max(0, at.settlement)));
            if (town != null) {
                hiddenBitten(w, town.id, r.sectorOfPx(town.cx * 16f, town.cy * 16f), 5, 6, 14, 47);
                latent(w, town.id, 24, 7, 21, 120, 1690, 57);
            }
            at = null;
            o = "done";
        }
        if (at != null) {
            originX = at.x;
            originY = at.y;
            if (o.equals("festival")) {
                // the crowd (come out of the streets round the park: everyone is counted once)
                Content.Unit civ = w.c.unit("civilian");
                int s = r.sectorOfPx(at.x, at.y);
                for (int k = 0; k < 700; k++) {
                    float x = at.x + (Rng.unit(w.seed, k, 1) - 0.5f) * (at.bx1 - at.bx0) * 14;
                    float y = at.y + (Rng.unit(w.seed, k, 2) - 0.5f) * (at.by1 - at.by0) * 14;
                    if (!r.walkablePx(x, y)) continue;
                    int from = fullest(w, s);
                    if (from < 0) break;
                    w.sec.popIn[w.sec.touch(from)]--;
                    int i = w.spawnCiv(x, y, civ);
                    w.e.flags[i] |= Ents.F_AMBIENT;
                    w.e.timer[i] = 400 + Rng.unit(w.seed, k, 3) * 400;
                    w.e.home[i] = s;
                    w.e.gx[i] = x;
                    w.e.gy[i] = y;
                }
                // people bitten in the crush went home before anyone understood
                hiddenBitten(w, at.settlement, s, 10, 5, 16, 45);
                latent(w, at.settlement, 48, 7, 24, 60, 1690, 55);
                Region.Settlement home = at.settlement >= 0 ? r.settlements.get(at.settlement) : r.city();
                Region.Settlement next = nearestTown(w, home, home);
                if (next != null) latent(w, next.id, 12, 7, 21, 200, 1820, 56);
            }
            if (o.equals("lab")) {
                // the staff who were bitten went home before anyone knew: hidden outbreaks round the city, and in
                // the towns some of them commute from
                int c = r.city().id;
                hiddenBitten(w, c, r.sectorOfPx(at.x, at.y), 12, 8, 26, 91);
                Region.Settlement town = nearestTown(w, r.city(), r.city());
                if (town != null) hiddenBitten(w, town.id, r.sectorOfPx(town.cx * 16f, town.cy * 16f), 2, 5, 12, 93);
                latent(w, c, 40, 9, 30, 60, 1690, 95);
                if (town != null) latent(w, town.id, 12, 7, 21, 200, 1820, 96);
            }
            // the first on the street, as people (the first 1,500 of a big start); the rest are already inside
            // the buildings of the streets round about
            int ind = Math.min(n, o.equals("lab") && n > 300 ? 1500 : street);
            pack(w, at.x, at.y, ind, 7, 5);
            if (n > ind) spreadIndoors(w, r.sectorOfPx(at.x, at.y), n - ind, n > 2000 ? 4 : 2);
        } else if (o.equals("crash") || o.equals("countryside")) {
            Region.Settlement c = r.city();
            float[] p = null;
            // in the fields on the edge of town (the countryside far out, for a fallen countryside), near a road
            boolean crash = o.equals("crash");
            for (int a = 0; a < 400 && p == null; a++) {
                float ang = Rng.unit(w.seed, a, 5) * 6.283f, d = (c.radius + (crash ? 8 + Rng.unit(w.seed, a, 6) * 16 : 22 + Rng.unit(w.seed, a, 6) * 40)) * 16;
                float x = c.cx * 16 + (float) Math.cos(ang) * d, y = c.cy * 16 + (float) Math.sin(ang) * d;
                int t = r.tileAtPx(x, y);
                if ((t == Tile.GRASS || t == Tile.FARM) && r.settlementAt(x, y) == null && (o.equals("countryside") || r.roads.nearestNode(x, y, 900) >= 0)) p = new float[]{x, y};
            }
            if (p == null) p = new float[]{c.cx * 16f, c.cy * 16f};
            originX = p[0];
            originY = p[1];
            if (o.equals("crash")) {
                w.strat.crashX = p[0];
                w.strat.crashY = p[1];
                // the passengers round the wreck; the rest already walking away from it, towards the lights of
                // the nearest town
                int ind = Math.min(n, 100);
                pack(w, p[0], p[1], ind, 10, 15);
                Region.Settlement town = nearestTo(w, p[0], p[1]);
                if (town != null) {
                    // people from the town pulled survivors out of the wreck and took them to hospital: bitten
                    int hs = w.strat.hospitalSector(town.id);
                    if (hs >= 0) w.strat.latent.add(new float[]{hs, 25 + Rng.hashInt(w.seed, 7, 59, 30), (float) w.time + 90});
                    latent(w, town.id, 20, 7, 21, 120, 1560, 58);
                }
                if (n > ind) {
                    Strategic.Horde h = w.strat.addHorde(p[0], p[1], n - ind);
                    if (town != null) {
                        h.target = r.sectorOfPx(town.cx * 16f, town.cy * 16f);
                        h.tx = w.sec.cx(h.target);
                        h.ty = w.sec.cy(h.target);
                        h.keepT = 1440;
                    }
                }
                w.fx.add(Effects.SMOKE, p[0], p[1], 80, 0, 600);
            } else {
                // the countryside has fallen: the dead everywhere outside the towns, and hordes
                List<Integer> open = new ArrayList<Integer>();
                for (int s = 0; s < w.sec.n; s++)
                    if (w.sec.settlement[s] < 0 && w.sec.connect[s] != 0 && dryMiddle(w, s)) open.add(s);
                int hordes = 4;
                int left = n;
                for (int k = 0; k < hordes && !open.isEmpty(); k++) {
                    int s = open.get(Rng.hashInt(w.seed, k, 77, open.size()));
                    w.strat.addHorde(w.sec.cx(s), w.sec.cy(s), n / 8);
                    left -= n / 8;
                }
                if (open.isEmpty()) w.sec.zIn[w.sec.touch(r.sectorOfPx(p[0], p[1]))] += left;
                else for (int k = 0; k < left; k += 10) w.sec.zIn[w.sec.touch(open.get(Rng.hashInt(w.seed, k, 78, open.size())))] += Math.min(10, left - k);
                // the villages' people have mostly fled to the towns (counted as refugees there)
                for (int s = 0; s < w.sec.n; s++) {
                    int st = w.sec.settlement[s];
                    if (st < 0 || r.settlements.get(st).kind != Region.S_VILLAGE || w.sec.popIn[s] == 0) continue;
                    Region.Settlement to = nearestTown(w, r.settlements.get(st));
                    if (to == null) continue;
                    int fled = w.sec.popIn[s] * 2 / 3;
                    int ts = r.sectorOfPx(to.cx * 16f, to.cy * 16f);
                    w.sec.popIn[w.sec.touch(s)] -= fled;
                    w.sec.popIn[w.sec.touch(ts)] += fled;
                    w.strat.refuge[st][to.id] += fled;
                }
                w.gov.level = 3;
            }
        } else if (o.equals("edges")) {
            // hordes marching in from every side
            float m = r.px();
            float[][] pts = {{0.08f, 0.5f}, {0.92f, 0.5f}, {0.5f, 0.08f}, {0.5f, 0.92f}, {0.1f, 0.1f}, {0.9f, 0.9f}};
            int wave = 0, placed = 0;
            // (a national map: not from the far borders, but from a ring this far round the capital, so the first is
            // still about a day away)
            float ring = w.rule("edgeHordeRing", 0);
            Region.Settlement capital = r.city();
            for (float[] pt : pts) {
                float x = pt[0] * m, y = pt[1] * m;
                if (ring > 0) {
                    float dx = pt[0] - 0.5f, dy = pt[1] - 0.5f, d = (float) Math.sqrt(dx * dx + dy * dy);
                    x = Math.max(m * 0.02f, Math.min(m * 0.98f, capital.cx * 16f + dx / d * ring));
                    y = Math.max(m * 0.02f, Math.min(m * 0.98f, capital.cy * 16f + dy / d * ring));
                }
                for (int a = 0; a < 40 && !r.walkablePx(x, y); a++) {
                    x += (m / 2 - x) * 0.05f;
                    y += (m / 2 - y) * 0.05f;
                }
                int size = wave == pts.length - 1 ? n - placed : n / pts.length;
                placed += size;
                Strategic.Horde h = w.strat.addHorde(x, y, size);
                // marching on the capital
                Region.Settlement cap = r.city();
                h.target = r.sectorOfPx(cap.cx * 16f, cap.cy * 16f);
                h.tx = w.sec.cx(h.target);
                h.ty = w.sec.cy(h.target);
                h.keepT = 1440 * 30;
                // in waves a couple of days apart, the first a day or so off (time to get ready)
                h.waitT = (1.25f + 2.2f * wave++) * 1440;
            }
            w.gov.level = 2;
            originX = r.city().cx * 16f;
            originY = r.city().cy * 16f;
        }
        // hordes marching in from the region's edges
        for (int k = 0; k < sc.hordes; k++) {
            float m = r.px();
            double ang = Rng.unit(w.seed, k, 301) * 6.283;
            float x = m / 2 + (float) Math.cos(ang) * m * 0.46f, y = m / 2 + (float) Math.sin(ang) * m * 0.46f;
            float ring = w.rule("edgeHordeRing", 0);
            if (ring > 0) {
                // (a national map: loose in the country round the capital, not at the far borders)
                x = Math.max(m * 0.02f, Math.min(m * 0.98f, r.city().cx * 16f + (float) Math.cos(ang) * ring));
                y = Math.max(m * 0.02f, Math.min(m * 0.98f, r.city().cy * 16f + (float) Math.sin(ang) * ring));
            }
            for (int a = 0; a < 60 && !r.walkablePx(x, y); a++) {
                x += (m / 2 - x) * 0.04f;
                y += (m / 2 - y) * 0.04f;
            }
            w.strat.addHorde(x, y, sc.hordeSize);
        }
    }

    /**
     * What the editor adds to a scenario (none of the built-in ones has any): more outbreak points, forces and
     * defences put down, the rules sheet, and the strain of the dead.
     */
    static void extras(World w, Content.Scenario sc) {
        Region r = w.r;
        float m = r.px();
        try {
            for (int[] kv : Script.rules(sc.rules)) w.rules.set(kv[0], kv[1]);
            java.util.List<float[]> pts = Script.points(sc.points);
            for (int k = 0; k < pts.size(); k++) {
                float[] p = pts.get(k);
                float[] q = landNear(w, p[0] * m, p[1] * m, 400, 9);
                int n = (int) p[2], ind = Math.min(n, 400);
                pack(w, q[0], q[1], ind, 8, 61 + k);
                if (n > ind) w.sec.zIn[w.sec.touch(r.sectorOfPx(q[0], q[1]))] += n - ind;
            }
            for (String[] p : Script.place(sc.place)) {
                float x = Float.parseFloat(p[2]) * m, y = Float.parseFloat(p[3]) * m;
                if (p[0].equals("squad")) {
                    Squads.Squad s = Tools.placeTemplate(w, p[1], x, y);
                    if (s != null) s.player = true;
                } else Tools.placeDefence(w, Integer.parseInt(p[1]), x, y);
            }
        } catch (java.io.IOException e) {
            // (checked when the scenario was read: nothing gets here)
        }
        if (sc.strain > 0) {
            // the dead at the start are of this strain, and it is the country's newest
            int st = Math.min(3, sc.strain);
            w.strat.strain = Math.max(w.strat.strain, st);
            for (int s = 0; s < w.sec.n; s++) if (w.sec.zIn[s] > 0) w.ecology.strain[s] = (byte) st;
            Ents e = w.e;
            for (int i = 0; i < e.top; i++) if (e.loc[i] == Ents.FREE && e.kind[i] == Ents.ZOMBIE) w.ecology.strain[r.sectorOfPx(e.x[i], e.y[i])] = (byte) st;
        }
    }

    /** n of the dead as people round (x, y), in a spiral; any that can't stand there wait in that street's buildings. */
    private static void pack(World w, float x, float y, int n, float spacing, long salt) {
        Region r = w.r;
        for (int k = 0; k < n; k++) {
            float ang = k * 2.39f, rad = 6 + (float) Math.sqrt(k) * spacing;
            float zx = x + (float) Math.cos(ang) * rad, zy = y + (float) Math.sin(ang) * rad;
            if (r.walkablePx(zx, zy) && r.tileAtPx(zx, zy) != Tile.BRIDGE) w.spawnZombie(zx, zy, w.pickZType(-1, k + salt * 1000));
            else w.sec.zIn[w.sec.touch(r.sectorOfPx(x, y))]++;
        }
    }

    /** n of the dead inside the buildings of the streets within `ring` sectors of s (where people live), as evenly as the homes. */
    private static void spreadIndoors(World w, int s0, int n, int ring) {
        if (n <= 0) return;
        List<Integer> near = new ArrayList<Integer>();
        long homes = 0;
        for (int dy = -ring; dy <= ring; dy++)
            for (int dx = -ring; dx <= ring; dx++) {
                int x = s0 % w.sec.sw + dx, y = s0 / w.sec.sw + dy;
                if (x < 0 || y < 0 || x >= w.sec.sw || y >= w.sec.sh) continue;
                int s = y * w.sec.sw + x;
                if (w.sec.home[s] > 0) {
                    near.add(s);
                    // dense where it began, thinning outwards (a quarter as many a street further on)
                    int ring2 = Math.max(Math.abs(dx), Math.abs(dy));
                    homes += weight(w, s, ring2);
                }
            }
        if (near.isEmpty()) {
            w.sec.zIn[w.sec.touch(s0)] += n;
            return;
        }
        int put = 0;
        for (int s : near) {
            int ring2 = Math.max(Math.abs(s % w.sec.sw - s0 % w.sec.sw), Math.abs(s / w.sec.sw - s0 / w.sec.sw));
            int m = (int) ((long) n * weight(w, s, ring2) / Math.max(1, homes));
            w.sec.zIn[w.sec.touch(s)] += m;
            put += m;
        }
        w.sec.zIn[w.sec.touch(near.get(0))] += n - put;
    }

    private static long weight(World w, int s, int ring) {
        return Math.max(1, w.sec.home[s] >> (2 * ring));
    }

    /**
     * People bitten before anyone knew, gone home: `homes` streets of the settlement (not the one at `not`), each
     * with lo..hi of its people infected. They turn over the next hours, indoors, where nobody sees.
     */
    private static void hiddenBitten(World w, int st, int not, int homes, int lo, int hi, long salt) {
        homes = Math.round(homes * w.rule("hiddenSpread", 1)); // (more streets on a national map)
        List<Integer> cand = new ArrayList<Integer>();
        for (int s : w.sec.idx.bySettlement[st]) if (w.sec.popIn[s] > 200 && s != not) cand.add(s);
        for (int k = 0; k < homes && !cand.isEmpty(); k++) {
            int s = cand.remove(Rng.hashInt(w.seed, k, salt, cand.size()));
            int m = Math.min(w.sec.popIn[s], lo + Rng.hashInt(w.seed, k, salt + 1, hi - lo + 1));
            w.sec.popIn[w.sec.touch(s)] -= m;
            w.sec.inf[w.sec.touch(s)] += m;
        }
    }

    /**
     * People bitten at the start who don't know it yet: `homes` streets of the settlement, lo..hi people each, who
     * fall ill at a time between `from` and `to` game seconds in (and turn a few hours after that), so the
     * outbreak shows up in new places through the first day.
     */
    private static void latent(World w, int st, int homes, int lo, int hi, float from, float to, long salt) {
        homes = Math.round(homes * w.rule("hiddenSpread", 1)); // (more streets on a national map)
        List<Integer> cand = new ArrayList<Integer>();
        for (int s : w.sec.idx.bySettlement[st]) if (w.sec.popIn[s] > 300) cand.add(s);
        for (int k = 0; k < homes && !cand.isEmpty(); k++) {
            int s = cand.remove(Rng.hashInt(w.seed, k, salt, cand.size()));
            int m = lo + Rng.hashInt(w.seed, k, salt + 1, hi - lo + 1);
            float t = (float) w.time + from + Rng.unit(w.seed, k, salt + 2) * (to - from);
            w.strat.latent.add(new float[]{s, m, t});
        }
    }

    /** The sector with the most people sheltering within two of s (to take a person out into the street from), or -1. */
    private static int fullest(World w, int s0) {
        int best = -1;
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++) {
                int x = s0 % w.sec.sw + dx, y = s0 / w.sec.sw + dy;
                if (x < 0 || y < 0 || x >= w.sec.sw || y >= w.sec.sh) continue;
                int s = y * w.sec.sw + x;
                if (w.sec.popIn[s] > 0 && (best < 0 || w.sec.popIn[s] > w.sec.popIn[best])) best = s;
            }
        return best;
    }

    /** The city or town nearest a point. */
    private static Region.Settlement nearestTo(World w, float x, float y) {
        Region.Settlement best = null;
        float bd = Float.MAX_VALUE;
        for (Region.Settlement s : w.r.settlements) {
            if (s.kind != Region.S_TOWN && s.kind != Region.S_CITY) continue;
            float dx = s.cx * 16f - x, dy = s.cy * 16f - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    private static Region.Settlement nearestTown(World w, Region.Settlement from, Region.Settlement not) {
        Region.Settlement best = null;
        float bd = Float.MAX_VALUE;
        for (Region.Settlement s : w.r.settlements) {
            if (s.kind != Region.S_TOWN && s.kind != Region.S_CITY || s == not) continue;
            float dx = s.cx - from.cx, dy = s.cy - from.cy, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }
    private static Region.Settlement nearestTown(World w, Region.Settlement from) {
        Region.Settlement best = null;
        float bd = Float.MAX_VALUE;
        for (Region.Settlement s : w.r.settlements) {
            if (s.kind != Region.S_TOWN && s.kind != Region.S_CITY) continue;
            float dx = s.cx - from.cx, dy = s.cy - from.cy, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    /** How far (px) the nearest of the dead is from (x, y): walking, in the buildings, the bitten, or a horde (worth more). */
    static float clearance(World w, float x, float y) {
        float best = Float.MAX_VALUE;
        // (only awake sectors hold any of the dead or the infected)
        for (int s = w.sec.idx.first(); s >= 0; s = w.sec.idx.next(s)) {
            if (w.sec.zIn[s] <= 0 && w.sec.inf[s] < 1) continue;
            float dx = w.sec.cx(s) - x, dy = w.sec.cy(s) - y;
            best = Math.min(best, (float) Math.sqrt(dx * dx + dy * dy) - 256);
        }
        for (int i = 0; i < w.e.top; i++) {
            if (w.e.loc[i] != Ents.FREE || w.e.kind[i] != Ents.ZOMBIE) continue;
            float dx = w.e.x[i] - x, dy = w.e.y[i] - y;
            best = Math.min(best, (float) Math.sqrt(dx * dx + dy * dy));
        }
        for (Strategic.Horde h : w.strat.hordes) {
            if (!h.alive) continue;
            float dx = h.x - x, dy = h.y - y;
            best = Math.min(best, (float) Math.sqrt(dx * dx + dy * dy) - 5000);
        }
        return best;
    }

    /**
     * A police station to start from near (x, y) but away from the front: the nearest with no dead within
     * `clear` px (a city's station a few kilometres from where it began, not the one next door), or the
     * clearest there is.
     */
    static int safeStation(World w, float x, float y, float clear) {
        return safeStation(w, x, y, clear, 0);
    }

    /** As above, and at least minDist px from (x, y). */
    static int safeStation(World w, float x, float y, float clear, float minDist) {
        int best = -1, clearest = -1;
        float bd = Float.MAX_VALUE, bc = -Float.MAX_VALUE;
        for (Region.Facility f : w.r.facilities) {
            if (f.kind != Region.F_POLICE || f.pool == null) continue;
            float c = clearance(w, f.x, f.y);
            float dx = f.x - x, dy = f.y - y, d = dx * dx + dy * dy;
            if (d < minDist * minDist) continue;
            if (c >= clear && d < bd) {
                bd = d;
                best = f.id;
            }
            if (c > bc) {
                bc = c;
                clearest = f.id;
            }
        }
        return best >= 0 ? best : clearest;
    }

    /** You, as a survivor: somewhere near the start, not in it. */
    static void survivor(World w) {
        Region r = w.r;
        {
            Content.Unit u = w.c.unit("survivor");
            // a street by a police station a few kilometres from the outbreak, with none of the dead near: a
            // person dropped next to the outbreak lives for seconds
            float[] p = null;
            int st = safeStation(w, originX, originY, 3000, 1900);
            if (st >= 0) {
                Region.Facility f = r.facilities.get(st);
                for (int a = 0; a < 200 && p == null; a++) {
                    float ang = a * 0.9f, d = 60 + a * 3;
                    float x = f.x + (float) Math.cos(ang) * d, y = f.y + (float) Math.sin(ang) * d;
                    int t = r.tileAtPx(x, y);
                    if (t == Tile.PAVEMENT || t == Tile.PLAZA || t == Tile.YARD) p = new float[]{x, y};
                }
            }
            for (int a = 0; a < 400 && p == null; a++) {
                float ang = a * 0.7f, d = 1800 + a * 12;
                float x = originX + (float) Math.cos(ang) * d, y = originY + (float) Math.sin(ang) * d;
                if (x < 64 || y < 64 || x > r.px() - 64 || y > r.px() - 64) continue;
                int t = r.tileAtPx(x, y);
                if (t != Tile.PAVEMENT && t != Tile.PLAZA && t != Tile.YARD) continue;
                if (!quiet(w, x, y)) continue;
                p = new float[]{x, y};
            }
            for (int a = 0; a < 100 && p == null; a++) {
                float ang = a * 0.7f, d = 650 + a * 8;
                float x = originX + (float) Math.cos(ang) * d, y = originY + (float) Math.sin(ang) * d;
                int t = r.tileAtPx(x, y);
                if (t == Tile.PAVEMENT || t == Tile.PLAZA || t == Tile.YARD) p = new float[]{x, y};
            }
            if (p == null) p = new float[]{originX + 200, originY};
            int i = w.spawnCiv(p[0], p[1], u);
            w.e.flags[i] |= Ents.F_PLAYER;
            w.e.state[i] = CivAI.S_PLAYER;
            w.playerEnt = i;
            originX = p[0];
            originY = p[1];
        }
    }

    /** No dead (counted or walking) in the sector at (x, y) or its neighbours, and no infected waiting to turn. */
    private static boolean quiet(World w, float x, float y) {
        Region r = w.r;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                float sx = x + dx * 512, sy = y + dy * 512;
                if (sx < 0 || sy < 0 || sx >= r.px() || sy >= r.px()) continue;
                int s = r.sectorOfPx(sx, sy);
                if (w.sec.zIn[s] > 0 || w.sec.inf[s] > 0) return false;
            }
        for (int i = 0; i < w.e.top; i++) {
            if (w.e.loc[i] != Ents.FREE || w.e.kind[i] != Ents.ZOMBIE) continue;
            float ddx = w.e.x[i] - x, ddy = w.e.y[i] - y;
            if (ddx * ddx + ddy * ddy < 1200f * 1200f) return false;
        }
        return true;
    }

    /** The middle of sector s is dry ground (a national map: its coarse cover, so no chunk is made for every sector). */
    private static boolean dryMiddle(World w, int s) {
        float x = w.sec.cx(s), y = w.sec.cy(s);
        if (w.r.nation != null) return !Nation.wet(w.r.nation.coverAt((int) (x / Tile.SIZE), (int) (y / Tile.SIZE)));
        return Tile.walkable(w.r.tileAtPx(x, y));
    }

    /** A walkable point near (x, y). */
    static float[] landNear(World w, float x, float y, float spread, long salt) {
        for (int a = 0; a < 200; a++) {
            float ang = Rng.unit(w.seed, a, salt) * 6.283f, d = Rng.unit(w.seed, a, salt + 1) * spread * (1 + a / 40f);
            float px = x + (float) Math.cos(ang) * d, py = y + (float) Math.sin(ang) * d;
            int t = w.r.tileAtPx(px, py);
            if (Tile.walkable(t) && t != Tile.BRIDGE) return new float[]{px, py};
        }
        return new float[]{x, y};
    }

    /** The edge of a settlement nearest open water. */
    static float[] shore(World w, Region.Settlement s) {
        for (int d = s.radius / 2; d < s.radius + 60; d += 4)
            for (int a = 0; a < 24; a++) {
                double ang = a * Math.PI / 12;
                int tx = s.cx + (int) (Math.cos(ang) * d), ty = s.cy + (int) (Math.sin(ang) * d);
                if (Tile.water(w.r.t(tx, ty))) return landNear(w, (s.cx + (int) (Math.cos(ang) * (d - 6))) * 16f, (s.cy + (int) (Math.sin(ang) * (d - 6))) * 16f, 60, 3);
            }
        return new float[]{s.cx * 16f, s.cy * 16f};
    }

    private static Region.Facility facility(World w, int kind) {
        for (Region.Facility f : w.r.facilities) if (f.kind == kind) return f;
        return null;
    }
}
