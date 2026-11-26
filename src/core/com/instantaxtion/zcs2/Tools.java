package com.instantaxtion.zcs2;

/**
 * What the player can do to the world: paint the dead, place forces, call strikes, blow bridges, order squads,
 * evacuate towns. The interface calls these; tests and probes call them the same way.
 */
public final class Tools {
    /** Paints count of the dead round (x, y) within radius. ztype -1 = the normal mix. Returns how many went down. */
    public static int paintZombies(World w, float x, float y, int count, float radius, int ztype) {
        int made = 0;
        int room = Math.max(0, w.maxIndividuals + 20000 - w.e.live);
        int individual = Math.min(count, room);
        for (int k = 0; k < individual * 3 && made < individual; k++) {
            float ang = Rng.unit(w.seed, w.tick * 7 + k, 11) * 6.283f;
            float rad = (float) Math.sqrt(Rng.unit(w.seed, w.tick * 7 + k, 12)) * radius;
            float px = x + (float) Math.cos(ang) * rad, py = y + (float) Math.sin(ang) * rad;
            if (px < 2 || py < 2 || px > w.r.px() - 2 || py > w.r.px() - 2 || !w.r.walkablePx(px, py)) continue;
            Content.ZType t = ztype >= 0 ? w.c.ztypes.get(ztype) : w.pickZType(-1, k);
            int i = w.spawnZombie(px, py, t);
            w.e.flags[i] |= Ents.F_PLACED;
            made++;
        }
        // whatever doesn't fit as individuals goes into the sectors underneath
        int rest = count - made;
        if (rest > 0) {
            int s = w.r.sectorOfPx(x, y);
            w.sec.zIn[w.sec.touch(s)] += rest;
            made += rest;
        }
        w.sec.hot[w.sec.touch(w.r.sectorOfPx(x, y))] = 25;
        return made;
    }

    /** A horde on the strategic map (for painting at region scale). */
    public static void paintHorde(World w, float x, float y, int count) {
        w.strat.addHorde(x, y, count);
    }

    /** Places a squad of the given unit at (x, y), holding there. */
    public static Squads.Squad placeSquad(World w, String unitId, int size, float x, float y) {
        Content.Unit u = w.c.unit(unitId);
        if (u == null || !w.r.walkablePx(x, y)) return null;
        int faction = u.kind == Content.COP ? Ents.COP : Ents.SOLDIER;
        int home = nearestFacility(w, x, y, faction);
        Squads.Squad s = w.sq.create(faction, home, u.name + "s");
        s.player = false;
        for (int k = 0; k < size; k++) {
            float px = x + (k % 4) * 8 - 12, py = y + (k / 4) * 8;
            if (!w.r.walkablePx(px, py)) {
                px = x;
                py = y;
            }
            int i = w.spawnUnit(u, px, py);
            w.sq.add(s, i);
        }
        w.sq.order(s, Squads.O_HOLD, x, y);
        if (faction == Ents.COP) w.stats.outsidePolice += size;
        else w.stats.outsideSoldiers += size;
        return s;
    }

    /** Places a vehicle on the nearest road. Tanks and aircraft act on their own; others get a squad. */
    public static int placeVehicle(World w, String vehicleId, float x, float y) {
        Content.VType t = w.c.vtype(vehicleId);
        if (t == null) return -1;
        float px = x, py = y;
        if (!t.flies) {
            int n = w.r.roads.nearestNode(x, y, 600);
            if (n < 0) return -1;
            px = w.r.roads.nx[n];
            py = w.r.roads.ny[n];
        }
        int faction = t.faction.equals("police") ? Ents.COP : Ents.SOLDIER;
        int v = w.veh.add(t, px, py, 0, nearestFacility(w, px, py, faction));
        w.veh.timer[v] = 99;
        if (t.flies) {
            w.veh.driveTo(v, x, y, 0);
            return v;
        }
        if (t.seats > 0 && !t.supply && !t.faction.equals("civil")) {
            String unit = faction == Ents.COP ? (vehicleId.equals("swat_van") ? "swat" : "patrol") : "rifleman";
            Squads.Squad s = placeSquad(w, unit, Math.min(t.seats, faction == Ents.COP ? 4 : 6), px, py);
            if (s != null) {
                s.vehicle = v;
                w.veh.squad[v] = s.id;
            }
        } else w.veh.state[v] = Vehicles.V_ARRIVED;
        return v;
    }

    static int nearestFacility(World w, float x, float y, int faction) {
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (Region.Facility f : w.r.facilities) {
            boolean ok = faction == Ents.COP ? f.kind == Region.F_POLICE : (f.kind == Region.F_BASE || f.kind == Region.F_ARMORY);
            if (!ok) continue;
            float dx = f.x - x, dy = f.y - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = f.id;
            }
        }
        return best;
    }

    /** Removes the dead (and bodies) round (x, y). */
    public static int erase(World w, float x, float y, float radius) {
        Ents e = w.e;
        int n = 0;
        for (int i = 0; i < e.top; i++) {
            if (e.loc[i] == Ents.NONE || i == w.playerEnt) continue;
            if (e.loc[i] == Ents.ABOARD) continue;
            float dx = e.x[i] - x, dy = e.y[i] - y;
            if (dx * dx + dy * dy > radius * radius) continue;
            if (e.kind[i] == Ents.ZOMBIE || e.loc[i] == Ents.BODY) {
                if (e.loc[i] == Ents.FREE) w.retire(i);
                else e.remove(i);
                n++;
            }
        }
        int s = w.r.sectorOfPx(x, y);
        if (radius >= 300) {
            n += w.sec.zIn[s];
            w.sec.zIn[s] = 0;
        }
        for (Strategic.Horde h : w.strat.hordes) {
            float dx = h.x - x, dy = h.y - y;
            if (dx * dx + dy * dy < (radius + 100) * (radius + 100)) {
                n += h.count;
                h.count = 0;
            }
        }
        return n;
    }

    /** The bridge road nearest (x, y) within 200 px, or -1. */
    public static int bridgeAt(World w, float x, float y) {
        RoadGraph g = w.r.roads;
        int best = -1;
        float bd = 200 * 200;
        for (int e = 0; e < g.edgeCount; e++) {
            if (!g.ebridge[e] || !g.ealive[e]) continue;
            float ax = g.nx[g.ea[e]], ay = g.ny[g.ea[e]], bx = g.nx[g.eb[e]], by = g.ny[g.eb[e]];
            float dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy + 0.01f;
            float t = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / l2));
            float px = ax + dx * t - x, py = ay + dy * t - y, d = px * px + py * py;
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    /** Orders a squad to a place: hold it. */
    public static void orderSquad(World w, Squads.Squad s, float x, float y) {
        if (s == null || !s.alive) return;
        float[] p = w.sq.where(s);
        float dx = x - p[0], dy = y - p[1], d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
        s.faceX = dx / d;
        s.faceY = dy / d;
        s.incident = -1;
        s.task = -1;
        s.player = true;
        w.sq.order(s, Squads.O_MOVE, x, y);
        w.news.radio(s.name + ": moving as ordered", x, y, News.UNIT);
    }

    /** Lets a squad go back to taking orders from dispatch. */
    public static void releaseSquad(World w, Squads.Squad s) {
        if (s == null || !s.alive) return;
        s.player = false;
        w.sq.goHome(s);
    }

    /** The squad nearest (x, y) within radius, or null. */
    public static Squads.Squad squadAt(World w, float x, float y, float radius) {
        Squads.Squad best = null;
        float bd = radius * radius;
        for (Squads.Squad s : w.sq.list) {
            if (!s.alive) continue;
            float[] p = w.sq.where(s);
            float dx = p[0] - x, dy = p[1] - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    /** The settlement at (x, y), or -1. */
    public static int settlementAt(World w, float x, float y) {
        Region.Settlement s = w.r.settlementAt(x, y);
        return s == null ? -1 : s.id;
    }
}
