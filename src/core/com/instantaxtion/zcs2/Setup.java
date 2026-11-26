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
        boolean hq = false;
        for (Region.Facility f : r.facilities) {
            Region.Settlement st = f.settlement >= 0 ? r.settlements.get(f.settlement) : null;
            if (f.kind == Region.F_POLICE && st != null) {
                int stations = 0;
                for (Region.Facility g : r.facilities) if (g.kind == Region.F_POLICE && g.settlement == f.settlement) stations++;
                int officers = st.residents / 55 / Math.max(1, stations) + (st.kind == Region.S_CITY ? 12 : 8);
                int cars = officers / 4;
                List<float[]> park = spots(w, f, cars + 4);
                for (int k = 0; k < cars; k++) {
                    Squads.Squad s = squad(w, f, Ents.COP, "Patrol", repeat("patrol", 4), "patrol_car", park.get(k));
                    // some cars are out on patrol when the day starts
                    if (k % 3 == 0) {
                        RoadGraph g = r.roads;
                        int n = Rng.hashInt(w.seed, f.id, k, g.nodeCount);
                        for (int a = 0; a < 20 && g.nset[n] != f.settlement; a++) n = Rng.hashInt(w.seed, f.id * 31 + a, k, g.nodeCount);
                        w.sq.order(s, Squads.O_PATROL, g.nx[n], g.ny[n]);
                    }
                }
                if (st.kind == Region.S_CITY && !hq) {
                    hq = true;
                    f.name = st.name + " Police HQ";
                    squad(w, f, Ents.COP, "SWAT", repeat("swat", 8), "swat_van", park.get(cars));
                    squad(w, f, Ents.COP, "Riot squad", repeat("riot", 8), "riot_van", park.get(cars + 1));
                    squad(w, f, Ents.COP, "Marksmen", repeat("marksman", 3), "patrol_car", park.get(cars + 2));
                    Content.VType heli = w.c.vtype("police_heli");
                    if (heli != null) w.veh.add(heli, park.get(cars + 3)[0], park.get(cars + 3)[1], 0, f.id);
                }
            } else if (f.kind == Region.F_BASE) {
                base(w, f);
            } else if (f.kind == Region.F_ARMORY) {
                List<float[]> park = spots(w, f, 2);
                squad(w, f, Ents.SOLDIER, "Guard", repeat("guard", 8), "army_truck", park.get(0));
                squad(w, f, Ents.SOLDIER, "Guard", repeat("guard", 4), "humvee", park.get(1));
            } else if (f.kind == Region.F_PRISON) {
                Squads.Squad s = squad(w, f, Ents.COP, "Prison guards", repeat("patrol", 4), null, null);
                s.order = Squads.O_GUARD;
                Squads.Squad s2 = squad(w, f, Ents.COP, "Prison guards", repeat("patrol", 4), null, null);
                s2.order = Squads.O_GUARD;
            }
        }
    }

    private static void base(World w, Region.Facility f) {
        int scale = w.r.W >= 1536 ? 3 : w.r.W >= 1024 ? 2 : 1;
        List<float[]> park = spots(w, f, 60);
        int p = 0;
        String[] rifle = {"rifleman", "rifleman", "rifleman", "rifleman", "gunner", "grenadier", "rifleman", "sniper"};
        for (int k = 0; k < 4 * scale + 2; k++) squad(w, f, Ents.SOLDIER, "Rifle squad", rifle, "army_truck", park.get(p++));
        String[] team = {"rifleman", "rifleman", "gunner", "grenadier"};
        for (int k = 0; k < 3 * scale; k++) squad(w, f, Ents.SOLDIER, "Fire team", team, "humvee", park.get(p++));
        String[] mech = {"rifleman", "rifleman", "rifleman", "gunner", "grenadier", "rifleman"};
        for (int k = 0; k < scale + 1; k++) squad(w, f, Ents.SOLDIER, "Mechanised", mech, "apc", park.get(p++));
        squad(w, f, Ents.SOLDIER, "Engineers", repeat("engineer", 4), "humvee", park.get(p++));
        // tanks: their own crews, no squad
        Content.VType tank = w.c.vtype("tank");
        for (int k = 0; k < scale + 1 && tank != null; k++) w.veh.add(tank, park.get(p)[0], park.get(p++)[1], -1.57f, f.id);
        // aircraft on the pads
        int gun = 0;
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
        if (sc.mode.equals("survivor")) {
            // you: somewhere near the start, not in it
            Content.Unit u = w.c.unit("survivor");
            float[] p = null;
            for (int a = 0; a < 100 && p == null; a++) {
                float ang = a * 0.7f, d = 260 + a * 6;
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
