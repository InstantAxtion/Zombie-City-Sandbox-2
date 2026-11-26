package com.instantaxtion.zcs2;

import java.util.ArrayList;
import java.util.List;

/** Builds a new game: the region, its police and army in their stations and bases, and the scenario's start. */
public final class Setup {
    /** Where the outbreak starts (for the camera), set by scenario(). */
    public static float originX, originY;

    public static World newGame(Content c, int size, int preset, long seed, Content.Scenario sc, int threads) {
        Region r = RegionGen.generate(size, preset, seed);
        World w = new World(c, r, seed, threads);
        w.scenario = sc;
        w.mode = sc == null || sc.mode.equals("sandbox") ? World.MODE_SANDBOX : sc.mode.equals("commander") ? World.MODE_COMMANDER : World.MODE_SURVIVOR;
        forces(w);
        Region.Settlement city = r.city();
        originX = city.cx * 16f;
        originY = city.cy * 16f;
        if (sc != null) scenario(w, sc);
        if (w.mode == World.MODE_COMMANDER) {
            w.gov.playerDecides = true;
            w.mil.strikesLeft = 6;
        }
        w.stats.startPolice = w.sq.count(Ents.COP);
        w.stats.startSoldiers = w.sq.count(Ents.SOLDIER);
        w.stats.startCivilians = w.sec.totalPop();
        w.stats.event("The day begins in " + r.name, -1, -1);
        return w;
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
                fo.addSquadToPool(f, w.c.squadT("patrol"), Math.max(2, officers / 4));
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
        int n = sc.zombies;
        Region.Facility at = null;
        if (o.equals("lab")) at = facility(w, Region.F_LAB);
        else if (o.equals("prison")) at = facility(w, Region.F_PRISON);
        else if (o.equals("festival")) at = facility(w, Region.F_PARK);
        if (at != null && o.equals("prison")) {
            originX = at.x;
            originY = at.y;
            // it started inside: the cell blocks, among the inmates
            int s = r.sectorOfPx(at.x, at.y);
            w.sec.zIn[s] += n;
            w.sec.hot[s] = 30;
            at = null;
            o = "done";
        }
        if (at != null) {
            originX = at.x;
            originY = at.y;
            if (o.equals("festival")) {
                // the crowd
                Content.Unit civ = w.c.unit("civilian");
                int s = r.sectorOfPx(at.x, at.y);
                for (int k = 0; k < 700; k++) {
                    float x = at.x + (Rng.unit(w.seed, k, 1) - 0.5f) * (at.bx1 - at.bx0) * 14;
                    float y = at.y + (Rng.unit(w.seed, k, 2) - 0.5f) * (at.by1 - at.by0) * 14;
                    if (!r.walkablePx(x, y)) continue;
                    int i = w.spawnCiv(x, y, civ);
                    w.e.flags[i] |= Ents.F_AMBIENT;
                    w.e.timer[i] = 400 + Rng.unit(w.seed, k, 3) * 400;
                    w.e.home[i] = s;
                    w.e.gx[i] = x;
                    w.e.gy[i] = y;
                }
            }
            if (o.equals("lab")) {
                // the staff who were bitten went home before anyone knew: hidden outbreaks round the city
                int c = r.city().id;
                java.util.List<Integer> homes = new java.util.ArrayList<Integer>();
                for (int s = 0; s < w.sec.n; s++) if (w.sec.settlement[s] == c && w.sec.popIn[s] > 200) homes.add(s);
                for (int k = 0; k < 6 && !homes.isEmpty(); k++) {
                    int s = homes.get(Rng.hashInt(w.seed, k, 91, homes.size()));
                    w.sec.inf[s] += 10 + Rng.hashInt(w.seed, k, 92, 14);
                }
            }
            for (int k = 0; k < n; k++) {
                float ang = k * 2.39f, rad = 6 + (float) Math.sqrt(k) * 7;
                float x = at.x + (float) Math.cos(ang) * rad, y = at.y + (float) Math.sin(ang) * rad;
                if (!r.walkablePx(x, y)) {
                    x = at.x;
                    y = at.y;
                }
                w.spawnZombie(x, y, w.c.ztype("walker"));
            }
        } else if (o.equals("crash") || o.equals("countryside")) {
            Region.Settlement c = r.city();
            float[] p = null;
            for (int a = 0; a < 200 && p == null; a++) {
                float ang = Rng.unit(w.seed, a, 5) * 6.283f, d = (c.radius + 40 + Rng.unit(w.seed, a, 6) * 80) * 16;
                float x = c.cx * 16 + (float) Math.cos(ang) * d, y = c.cy * 16 + (float) Math.sin(ang) * d;
                int t = r.tileAtPx(x, y);
                if ((t == Tile.GRASS || t == Tile.FARM) && r.settlementAt(x, y) == null) p = new float[]{x, y};
            }
            if (p == null) p = new float[]{c.cx * 16f, c.cy * 16f};
            originX = p[0];
            originY = p[1];
            if (o.equals("crash")) {
                w.strat.crashX = p[0];
                w.strat.crashY = p[1];
                int s = r.sectorOfPx(p[0], p[1]);
                int ind = Math.min(n, 150);
                for (int k = 0; k < ind; k++) {
                    float ang = k * 2.39f, rad = 10 + (float) Math.sqrt(k) * 9;
                    float x = p[0] + (float) Math.cos(ang) * rad, y = p[1] + (float) Math.sin(ang) * rad;
                    if (r.walkablePx(x, y)) w.spawnZombie(x, y, w.c.ztype("walker"));
                }
                w.sec.zIn[s] += n - ind;
                w.fx.add(Effects.SMOKE, p[0], p[1], 80, 0, 600);
            } else {
                // the countryside has fallen: the dead everywhere outside the towns, and hordes
                List<Integer> open = new ArrayList<Integer>();
                for (int s = 0; s < w.sec.n; s++)
                    if (w.sec.settlement[s] < 0 && w.sec.connect[s] != 0 && Tile.walkable(r.tileAtPx(w.sec.cx(s), w.sec.cy(s)))) open.add(s);
                int hordes = 4;
                int left = n;
                for (int k = 0; k < hordes && !open.isEmpty(); k++) {
                    int s = open.get(Rng.hashInt(w.seed, k, 77, open.size()));
                    w.strat.addHorde(w.sec.cx(s), w.sec.cy(s), n / 8);
                    left -= n / 8;
                }
                for (int k = 0; k < left && !open.isEmpty(); k++) w.sec.zIn[open.get(Rng.hashInt(w.seed, k, 78, open.size()))]++;
                // the towns' people have mostly left or fallen
                for (int s = 0; s < w.sec.n; s++) {
                    int st = w.sec.settlement[s];
                    if (st >= 0 && r.settlements.get(st).kind == Region.S_VILLAGE) {
                        w.sec.zIn[s] += w.sec.popIn[s] / 2;
                        w.sec.popIn[s] /= 3;
                    }
                }
                w.gov.level = 3;
            }
        } else if (o.equals("edges")) {
            // hordes marching in from every side
            float m = r.px();
            float[][] pts = {{0.08f, 0.5f}, {0.92f, 0.5f}, {0.5f, 0.08f}, {0.5f, 0.92f}, {0.1f, 0.1f}, {0.9f, 0.9f}};
            for (float[] pt : pts) {
                float x = pt[0] * m, y = pt[1] * m;
                for (int a = 0; a < 40 && !r.walkablePx(x, y); a++) {
                    x += (m / 2 - x) * 0.05f;
                    y += (m / 2 - y) * 0.05f;
                }
                w.strat.addHorde(x, y, n / pts.length);
            }
            w.gov.level = 3;
            originX = r.city().cx * 16f;
            originY = r.city().cy * 16f;
        }
        // hordes marching in from the region's edges
        for (int k = 0; k < sc.hordes; k++) {
            float m = r.px();
            double ang = Rng.unit(w.seed, k, 301) * 6.283;
            float x = m / 2 + (float) Math.cos(ang) * m * 0.46f, y = m / 2 + (float) Math.sin(ang) * m * 0.46f;
            for (int a = 0; a < 60 && !r.walkablePx(x, y); a++) {
                x += (m / 2 - x) * 0.04f;
                y += (m / 2 - y) * 0.04f;
            }
            w.strat.addHorde(x, y, sc.hordeSize);
        }
        if (sc.mode.equals("survivor")) {
            // you: somewhere near the start, not in it
            Content.Unit u = w.c.unit("survivor");
            float[] p = null;
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

    private static Region.Facility facility(World w, int kind) {
        for (Region.Facility f : w.r.facilities) if (f.kind == kind) return f;
        return null;
    }
}
