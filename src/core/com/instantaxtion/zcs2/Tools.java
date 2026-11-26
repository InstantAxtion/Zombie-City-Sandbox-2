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

    // ------------------------------------------------------------------ the dead by the ten thousand

    /** The most of a big brush placed as individuals; the rest go into the streets' counts. */
    public static final int MASS_INDIVIDUALS = 1500;

    /**
     * A mass of the dead (10,000 to 250,000) round (x, y): up to MASS_INDIVIDUALS as individuals (fewer when the
     * phone's individuals are full), the rest spread over the sectors round about. Returns how many went down as
     * individuals.
     */
    public static int paintMass(World w, float x, float y, int count, int ztype) {
        int room = Math.max(0, w.maxIndividuals + 20000 - w.e.live);
        int ind = w.strategic ? 0 : Math.min(Math.min(count, MASS_INDIVIDUALS), room);
        int before = w.e.live;
        if (ind > 0) paintZombies(w, x, y, ind, 14 + (float) Math.sqrt(ind) * 12, ztype);
        int made = w.e.live - before;
        // (paintZombies put whatever found no place into the sector underneath: placed too, in the count)
        int rest = count - ind;
        Sectors sec = w.sec;
        int s0 = w.r.sectorOfPx(x, y), sx = s0 % sec.sw, sy = s0 / sec.sw;
        int rad = (int) Math.ceil(Math.sqrt(rest / 15000.0));
        int n = 0;
        for (int pass = 0; pass < 2 && rest > 0; pass++) {
            int k = 0;
            for (int dy = -rad; dy <= rad; dy++)
                for (int dx = -rad; dx <= rad; dx++) {
                    int ax = sx + dx, ay = sy + dy;
                    if (ax < 0 || ay < 0 || ax >= sec.sw || ay >= sec.sh || dx * dx + dy * dy > rad * rad + 1) continue;
                    int s = ay * sec.sw + ax;
                    if (s != s0 && !w.r.walkablePx(sec.cx(s), sec.cy(s))) continue;
                    if (pass == 0) n++;
                    else {
                        sec.zIn[s] += rest / n + (k < rest % n ? 1 : 0);
                        k++;
                    }
                }
        }
        sec.hot[s0] = 25;
        return made;
    }

    /** Ordinary people (civilians, survivors, militia, firefighters) put on the street round (x, y). */
    public static int placePeople(World w, Content.Unit u, int size, float x, float y) {
        int n = 0;
        for (int k = 0; k < size * 3 && n < size; k++) {
            float ang = k * 2.4f, rad = 6 + k * 3;
            float px = x + (float) Math.cos(ang) * rad, py = y + (float) Math.sin(ang) * rad;
            if (!w.r.walkablePx(px, py)) continue;
            int i = w.spawnCiv(px, py, u);
            w.e.flags[i] |= Ents.F_PLACED;
            // the people placed join the country's people (the ledger counts them from here)
            if (w.stats.resident(i)) w.stats.startPeople++;
            n++;
        }
        return n;
    }

    /** A squad from content.ini's [squad] list, with its vehicle, placed at (x, y) and holding there. */
    public static Squads.Squad placeTemplate(World w, String id, float x, float y) {
        Content.SquadT t = w.c.squadT(id);
        if (t == null || !w.r.walkablePx(x, y)) return null;
        int faction = t.faction.equals("police") ? Ents.COP : Ents.SOLDIER;
        Squads.Squad s = w.sq.create(faction, nearestFacility(w, x, y, faction), t.name);
        s.template = t.index;
        for (int k = 0; k < t.units.length; k++) {
            float ang = k * 2.4f, rad = 8 + k * 3;
            float px = x + (float) Math.cos(ang) * rad, py = y + (float) Math.sin(ang) * rad;
            if (!w.r.walkablePx(px, py)) {
                px = x;
                py = y;
            }
            w.sq.add(s, w.spawnUnit(t.units[k], px, py));
        }
        if (t.vehicle != null && !t.vehicle.flies) {
            int n = w.r.roads.nearestNode(x, y, 600);
            if (n >= 0) {
                int v = w.veh.add(t.vehicle, w.r.roads.nx[n], w.r.roads.ny[n], 0, s.home);
                w.veh.timer[v] = 99;
                w.veh.state[v] = Vehicles.V_ARRIVED;
                s.vehicle = v;
                w.veh.squad[v] = s.id;
            }
        }
        w.sq.order(s, Squads.O_HOLD, x, y);
        if (faction == Ents.COP) w.stats.outsidePolice += t.units.length;
        else w.stats.outsideSoldiers += t.units.length;
        return s;
    }

    // ------------------------------------------------------------------ defences and building

    /** A defence (Defences.*) at (x, y). A machine gun faces the nearest of the dead, else away from the nearest base. */
    public static Defences.Def placeDefence(World w, int kind, float x, float y) {
        float fx = 0, fy = -1;
        int z = w.sp.nearestZombie(w.e, x, y, 1500);
        if (z >= 0) {
            fx = w.e.x[z] - x;
            fy = w.e.y[z] - y;
        } else {
            int f = nearestFacility(w, x, y, Ents.SOLDIER);
            if (f < 0) f = nearestFacility(w, x, y, Ents.COP);
            if (f >= 0) {
                fx = x - w.r.facilities.get(f).x;
                fy = y - w.r.facilities.get(f).y;
            }
        }
        if (fx * fx + fy * fy < 1) {
            fx = 0;
            fy = -1;
        }
        return w.defs.place(kind, x, y, fx, fy);
    }

    public static final int B_BARRICADE = 0, B_WALL = 1, B_ROAD = 2, B_HOUSE = 3, B_CLEAR = 4;
    public static final String[] BUILD_NAMES = {"Barricade", "Wall", "Road", "House", "Clear ground"};

    /**
     * Builds at (x, y): a barricade or wall (a plus of five tiles the dead must smash through), a road, a house, or
     * clears rubble, trees, barricades and walls back to open ground. The paths, the sectors' connectivity and the
     * roads closed by barricades catch up at once. Returns how many tiles changed.
     */
    public static int build(World w, int what, float x, float y) {
        Region r = w.r;
        int cx = (int) Math.floor(x / Tile.SIZE), cy = (int) Math.floor(y / Tile.SIZE);
        boolean line = what == B_BARRICADE || what == B_WALL;
        int rad = line ? 1 : what == B_CLEAR ? 2 : 1;
        int changed = 0, x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        if (what == B_HOUSE) {
            // all nine tiles must be open land (not a road, water or another building)
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    int t = r.t(cx + dx, cy + dy);
                    if (!Tile.walkable(t) || Tile.roadish(t) || Tile.water(t) || t == Tile.RAIL) return 0;
                }
        }
        for (int dy = -rad; dy <= rad; dy++)
            for (int dx = -rad; dx <= rad; dx++) {
                if (line && dx != 0 && dy != 0) continue;
                if (what == B_CLEAR && dx * dx + dy * dy > rad * rad + 1) continue;
                int tx = cx + dx, ty = cy + dy;
                if (!r.in(tx, ty)) continue;
                int t = r.t(tx, ty);
                byte to;
                switch (what) {
                    case B_BARRICADE:
                    case B_WALL:
                        if (!Tile.walkable(t) || Tile.water(t) || t == Tile.BRIDGE) continue;
                        to = what == B_WALL ? Tile.WALL : Tile.BARRICADE;
                        break;
                    case B_ROAD:
                        if (Tile.water(t) || t == Tile.BUILDING || t == Tile.ROCK || t == Tile.BRIDGE || Tile.roadish(t)) continue;
                        to = Tile.ROAD;
                        break;
                    case B_HOUSE:
                        to = Tile.BUILDING;
                        break;
                    default:
                        if (t != Tile.RUBBLE && t != Tile.TREE && t != Tile.FOREST && t != Tile.BARRICADE && t != Tile.WALL) continue;
                        if (r.building(tx, ty) >= 0) continue;
                        to = Tile.GRASS;
                }
                if (t == to) continue;
                if (!Tile.walkable(to)) stepOff(w, tx, ty);
                r.set(tx, ty, to);
                changed++;
                x0 = Math.min(x0, tx);
                y0 = Math.min(y0, ty);
                x1 = Math.max(x1, tx);
                y1 = Math.max(y1, ty);
            }
        if (changed == 0) return 0;
        r.rebuildNav(x0 - 1, y0 - 1, x1 + 1, y1 + 1);
        w.sec.connectivity();
        r.closeBarricadedRoads(x0, y0, x1, y1);
        return changed;
    }

    /** Everyone standing on a tile about to be built on steps off it to the nearest open ground. */
    static void stepOff(World w, int tx, int ty) {
        Ents e = w.e;
        float cx = tx * Tile.SIZE + Tile.SIZE / 2f, cy = ty * Tile.SIZE + Tile.SIZE / 2f;
        Spatial sp = w.sp;
        int c = sp.cellOf(cx, cy);
        for (int pass = 0; pass < 2; pass++) {
            int a = pass == 0 ? sp.lStart[c] : sp.zStart[c], b = pass == 0 ? sp.lEnd[c] : sp.zEnd[c];
            int[] L = pass == 0 ? sp.lList : sp.zList;
            for (int j = a; j < b; j++) {
                int i = L[j];
                if (e.loc[i] != Ents.FREE || (int) Math.floor(e.x[i] / Tile.SIZE) != tx || (int) Math.floor(e.y[i] / Tile.SIZE) != ty) continue;
                for (int k = 0; k < 16; k++) {
                    float an = k * 0.785f, d = 18 + (k / 8) * 16;
                    float nx = cx + (float) Math.cos(an) * d, ny = cy + (float) Math.sin(an) * d;
                    if (w.r.walkablePx(nx, ny) && ((int) Math.floor(nx / Tile.SIZE) != tx || (int) Math.floor(ny / Tile.SIZE) != ty)) {
                        e.x[i] = nx;
                        e.y[i] = ny;
                        break;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ clearing

    public static final int CLEAR_WRECKS = 0, CLEAR_BLOOD = 1, CLEAR_BARRICADES = 2, CLEAR_EVERYONE = 3, CLEAR_TOWN = 4;

    /** Clears wrecks, blood and bodies, barricades and defences, everyone on the streets, or a town's counted dead. */
    public static int clear(World w, int what, int settlement) {
        Ents e = w.e;
        int n = 0;
        switch (what) {
            case CLEAR_WRECKS:
                for (int v = 0; v < w.veh.top; v++)
                    if (w.veh.state[v] == Vehicles.V_WRECK) {
                        w.veh.state[v] = Vehicles.V_NONE;
                        n++;
                    }
                return n;
            case CLEAR_BLOOD:
                n = w.fx.dCount;
                w.fx.dCount = 0;
                w.fx.dHead = 0;
                for (int i = 0; i < e.top; i++)
                    if (e.loc[i] == Ents.BODY && (e.flags[i] & Ents.F_RISES) == 0) {
                        e.remove(i);
                        n++;
                    }
                return n;
            case CLEAR_BARRICADES: {
                Region r = w.r;
                int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
                for (int kind = 0; kind < 2; kind++) {
                    int[] ed = r.editedTiles(kind == 0 ? Tile.BARRICADE : Tile.WALL);
                    for (int k = 0; k < ed.length; k += 3) {
                        int tx = ed[k], ty = ed[k + 1], was = ed[k + 2];
                        byte to = Tile.walkable(was) ? (byte) was : Tile.PAVEMENT;
                        r.set(tx, ty, to);
                        n++;
                        x0 = Math.min(x0, tx);
                        y0 = Math.min(y0, ty);
                        x1 = Math.max(x1, tx);
                        y1 = Math.max(y1, ty);
                    }
                }
                if (n > 0) {
                    r.rebuildNav(x0 - 1, y0 - 1, x1 + 1, y1 + 1);
                    w.sec.connectivity();
                    r.closeBarricadedRoads();
                }
                return n + w.defs.clearAll();
            }
            case CLEAR_EVERYONE: {
                // the dead are gone; the living go indoors (counted again); every squad goes home
                Sectors sec = w.sec;
                for (int i = 0; i < e.top; i++) {
                    if (i == w.playerEnt) continue;
                    if (e.loc[i] == Ents.BODY) {
                        e.remove(i);
                        continue;
                    }
                    if (e.loc[i] != Ents.FREE || e.armed(i)) continue;
                    int s = w.r.sectorOfPx(e.x[i], e.y[i]);
                    if (e.kind[i] == Ents.CIV && w.stats.resident(i)) {
                        if ((e.flags[i] & Ents.F_INFECTED) != 0) sec.inf[s] += 1;
                        else sec.popIn[s]++;
                    }
                    w.retire(i);
                    n++;
                }
                for (Squads.Squad s : w.sq.list) {
                    if (!s.alive) continue;
                    w.orders.cancel(s);
                    s.player = false;
                    s.stance = Squads.STANCE_NORMAL;
                    w.sq.goHome(s);
                }
                return n;
            }
            case CLEAR_TOWN: {
                if (settlement < 0 || settlement >= w.r.settlements.size()) return 0;
                Sectors sec = w.sec;
                for (int s = 0; s < sec.n; s++)
                    if (sec.settlement[s] == settlement) {
                        n += sec.zIn[s];
                        sec.zIn[s] = 0;
                    }
                Region.Settlement st = w.r.settlements.get(settlement);
                for (Strategic.Horde h : w.strat.hordes) {
                    if (!h.alive) continue;
                    float dx = h.x - st.cx * 16f, dy = h.y - st.cy * 16f;
                    if (dx * dx + dy * dy < 2000f * 2000f) {
                        n += h.count;
                        h.count = 0;
                    }
                }
                return n;
            }
            default:
                return 0;
        }
    }
}
