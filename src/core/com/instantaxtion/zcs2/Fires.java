package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Fires, and the fire brigades. Buildings catch fire from blasts, strikes, shells and crashes, and on their own where
 * the power and water are gone and nobody is minding the stoves. Fire grows, spreads to neighbours with the wind, and
 * brings a building down in the end, with anyone still sheltering in it. Rain damps it. At night a fire is a light the
 * dead walk towards. Fire stations keep crews and engines (as numbers); a crew is sent to each fire it can reach, and
 * near the camera you see the engine drive there.
 */
public final class Fires extends Module {
    /** A building's fire: heat 0 (none) to 1, how long it has burned, and the crew sent to it. */
    private static final class Fire {
        float heat, burnT, eta;
        int crews, crewFrom = -1, engine = -1;
        /** Fire crews on foot (fireCrewsOnFoot): the firefighters down from the engine, by entity and uid. */
        int[] foot = new int[0], footUid = new int[0];
        boolean unloaded, retreat;
        float retreatT;
    }

    /** Fire crews get down from the engine near the camera and fight the fire as people (rule fireCrewsOnFoot). */
    private final boolean footOn;

    /**
     * The fires by building id (Region's or a chunk's encoded ids). Only looked up by key: everything that walks
     * the fires walks the burning list, in the order they caught, so a map's iteration order never matters.
     */
    private final java.util.HashMap<Integer, Fire> fires = new java.util.HashMap<Integer, Fire>();

    /** Heat of a building's fire, 0 (none) to 1. */
    public float heat(int b) {
        Fire f = fires.get(b);
        return f == null ? 0 : f.heat;
    }

    private Fire fire(int b) {
        Fire f = fires.get(b);
        if (f == null) {
            f = new Fire();
            fires.put(b, f);
        }
        return f;
    }

    /**
     * National maps, the second tier: fires counted per sector where the chunk isn't active (nobody sees which
     * building; when the chunk becomes active they catch real buildings). In the order the sectors first caught.
     */
    private int[] cSec = new int[8], cN = new int[8];
    private float[] cBurn = new float[8];
    private int cCount;
    private final int[] secFires;
    /** The sectors with a count in secFires. */
    private int[] fireSecs = new int[16];
    private int fireSecN;
    public float wind; // radians: where the wind blows to
    private final List<Integer> burning = new ArrayList<Integer>();
    private int unitFF = -1, vEngine = -1;
    public int started, collapsed, putOut;
    private float news;

    public Fires(World w) {
        super(w);
        secFires = new int[w.sec.n];
        footOn = w.rule("fireCrewsOnFoot", 0) > 0;
        Content.Unit ff = w.c.unit("firefighter");
        Content.VType fe = w.c.vtype("fire_engine");
        if (ff != null) unitFF = ff.index;
        if (fe != null) vEngine = fe.index;
        wind = Rng.unit(w.seed, 5, 5) * 6.283f;
        // crews at every fire station
        if (unitFF >= 0) {
            float per = w.c.rule("firePerResident", 0.0001f);
            for (Region.Facility f : w.r.facilities) {
                if (f.kind != Region.F_FIRE) continue;
                w.forces.ensurePools(f);
                int res = f.settlement >= 0 ? w.r.settlements.get(f.settlement).residents : 0;
                int stations = 0;
                for (Region.Facility g : w.r.facilities) if (g.kind == Region.F_FIRE && g.settlement == f.settlement) stations++;
                f.pool[unitFF] = 12 + (int) (res * per / Math.max(1, stations));
                if (vEngine >= 0) f.vpool[vEngine] = Math.max(2, f.pool[unitFF] / 4);
            }
        }
    }

    @Override
    public String id() {
        return "fires";
    }

    public boolean burning(int s) {
        return secFires[s] > 0;
    }

    public int count() {
        return burning.size();
    }

    /** The k-th building burning (0 .. count() - 1), in the order they caught. */
    public int burningAt(int k) {
        return burning.get(k);
    }

    /** Sets a building alight (looting, the society systems...). */
    public void ignite(int b, float h) {
        if (b < 0 || !Chunk.isChunkBuilding(b) && b >= w.r.bCount || w.r.bfloors(b) <= 0) return;
        if (w.r.nation != null && heat(b) <= 0) {
            // a building in a chunk that isn't active: a fire counted in its sector until the chunk comes in
            float dx = w.r.doorX(b), dy = w.r.doorY(b);
            if (dx < 0) {
                dx = (w.r.bx(b) + w.r.bw(b) / 2f) * Tile.SIZE;
                dy = (w.r.by(b) + w.r.bh(b) / 2f) * Tile.SIZE;
            }
            if (!w.act.activePx(dx, dy)) {
                countFire(w.r.sectorOfPx(dx, dy), 1);
                return;
            }
        }
        if (heat(b) <= 0) {
            burning.add(b);
            started++;
            if (w.r.bres(b) > 0 || w.r.bkind(b) >= Region.B_POLICE) {
                if (w.time - news > 120) {
                    news = (float) w.time;
                    w.news.radio("Fire reported: " + (vowel(building(w.r.bkind(b))) ? "an " : "a ") + building(w.r.bkind(b)) + " burning" + placeOf(b), w.r.doorX(b), w.r.doorY(b), News.CALL);
                }
            }
        }
        fire(b).heat = Math.max(fire(b).heat, Math.min(1, h));
    }

    private String placeOf(int b) {
        int st = w.r.bset(b);
        return st >= 0 ? " in " + w.r.settlements.get(st).name : " in the countryside";
    }

    /** A building near (x, y) may catch (crashes, wrecks). */
    public void igniteNear(float x, float y, float chance) {
        int tx = (int) (x / Tile.SIZE), ty = (int) (y / Tile.SIZE);
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++) {
                if (!w.r.in(tx + dx, ty + dy)) continue;
                int b = w.r.building(tx + dx, ty + dy);
                if (b >= 0) {
                    if (Rng.unit(w.seed, w.tick, b * 3L + 1) < chance) ignite(b, 0.25f);
                    return;
                }
            }
    }

    /** An explosion sets buildings in its reach alight. */
    public void blast(float x, float y, float rad) {
        if (rad < 40) return;
        float chance = Math.min(0.9f, rad / 160f);
        int r0 = (int) (rad * 0.6f / Tile.SIZE);
        int tx = (int) (x / Tile.SIZE), ty = (int) (y / Tile.SIZE);
        int last = -1;
        for (int dy = -r0; dy <= r0; dy += 2)
            for (int dx = -r0; dx <= r0; dx += 2) {
                if (!w.r.in(tx + dx, ty + dy)) continue;
                int b = w.r.building(tx + dx, ty + dy);
                if (b < 0 || b == last || heat(b) > 0) continue;
                last = b;
                if (Rng.unit(w.seed, w.tick, b * 7L + 2) < chance) ignite(b, 0.4f);
            }
    }

    @Override
    public void update(float dt) {
        Region r = w.r;
        Sectors sec = w.sec;
        wind += (Rng.unit(w.seed, w.tick, 911) - 0.5f) * 0.2f * dt / 60f;
        untended(dt);
        // (only the sectors that had fires last time are cleared, not the whole map)
        for (int k = 0; k < fireSecN; k++) secFires[fireSecs[k]] = 0;
        fireSecN = 0;
        float grow = 1f / w.c.rule("fireGrow", 600), burnout = w.c.rule("fireBurnout", 900), spread = w.c.rule("fireSpread", 0.004f);
        float crewRate = w.c.rule("fireCrew", 0.0012f), rain = w.c.rule("fireRain", 0.004f);
        float wx = (float) Math.cos(wind), wy = (float) Math.sin(wind);
        int n = burning.size();
        for (int k = 0; k < n; k++) {
            int b = burning.get(k);
            if (fire(b).heat <= 0) continue;
            float dx0 = w.r.doorX(b) >= 0 ? w.r.doorX(b) : (r.bx(b) + r.bw(b) / 2f) * 16, dy0 = w.r.doorY(b) >= 0 ? w.r.doorY(b) : (r.by(b) + r.bh(b) / 2f) * 16;
            int s = r.sectorOfPx(dx0, dy0);
            // the crew on its way, or at work
            if (fire(b).crews == 0 && !fire(b).unloaded) dispatch(b, dx0, dy0);
            float h = fire(b).heat + grow * dt;
            if (footOn && fire(b).engine >= 0) {
                // the crew as people: only those at the hoses put it out
                int spraying = footCrew(b, fire(b), dx0, dy0, dt);
                if (spraying > 0) {
                    int bs = r.bset(b);
                    float water = bs >= 0 && w.infra.waterOut(bs) ? 0.3f : 1f;
                    h -= spraying / 4f * crewRate * water * dt + grow * dt;
                }
            } else if (fire(b).crews > 0) {
                fire(b).eta -= dt;
                if (fire(b).eta <= 0) {
                    // the dead about: the crew can't work, and some don't come back
                    if (sec.zIn[s] + w.sp.secZ[s] > 25) {
                        int lost = Math.min(fire(b).crews, 1 + fire(b).crews / 4);
                        fire(b).crews -= lost;
                        w.stats.crewDeaths += lost;
                        sec.zIn[sec.touch(s)] += lost / 2;
                        recall(b);
                    } else {
                        // no water in the mains: the crews have only what the engines carry
                        int bs = r.bset(b);
                        float water = bs >= 0 && w.infra.waterOut(bs) ? 0.3f : 1f;
                        h -= fire(b).crews / 4f * crewRate * water * dt + grow * dt;
                    }
                }
            }
            if (w.weather.wet()) h -= rain * dt * (w.weather.state == Weather.STORM ? 2 : 1);
            fire(b).heat = Math.max(0, Math.min(1, h));
            if (fire(b).heat <= 0) {
                putOut++;
                recall(b);
                fire(b).burnT = 0;
                continue;
            }
            if (secFires[s]++ == 0) {
                if (fireSecN == fireSecs.length) fireSecs = java.util.Arrays.copyOf(fireSecs, fireSecN * 2);
                fireSecs[fireSecN++] = s;
            }
            fire(b).burnT += dt * fire(b).heat;
            // into the neighbours, with the wind
            if (fire(b).heat > 0.5f && Rng.unit(w.seed, w.tick, b * 13L) < spread * fire(b).heat * dt) spreadFrom(b, wx, wy);
            // at night a fire is a light; near people it's noise as well
            if (!w.strategic && w.night() && (sec.hot[s] > 0 || sec.cam[s]) && Rng.unit(w.seed, w.tick, b * 5L) < 0.1f * dt)
                w.fx.noise(dx0, dy0, 16, 4);
            if (fire(b).burnT > burnout) collapse(b, s);
        }
        for (int k = burning.size() - 1; k >= 0; k--)
            if (heat(burning.get(k)) <= 0) fires.remove(burning.remove(k)); // (out: its record is all defaults again)
        if (cCount > 0) counted(dt, grow, burnout, spread, rain);
        // engines home again go back in the station
        for (int v = 0; v < w.veh.top; v++) {
            if (w.veh.state[v] == Vehicles.V_PARKED && w.veh.type[v] == vEngine && w.veh.role[v] == Vehicles.R_SQUAD && w.veh.squad[v] < 0) {
                int h = w.veh.home[v];
                if (h >= 0) w.r.facilities.get(h).vpool[vEngine]++;
                w.veh.state[v] = Vehicles.V_NONE;
            }
        }
    }

    /** n more fires counted in sector s (national maps, a chunk that isn't active). */
    private void countFire(int s, int n) {
        for (int k = 0; k < cCount; k++)
            if (cSec[k] == s) {
                cN[k] = Math.min(40, cN[k] + n);
                return;
            }
        if (cCount == cSec.length) {
            cSec = java.util.Arrays.copyOf(cSec, cCount * 2);
            cN = java.util.Arrays.copyOf(cN, cCount * 2);
            cBurn = java.util.Arrays.copyOf(cBurn, cCount * 2);
        }
        cSec[cCount] = s;
        cN[cCount] = n;
        cBurn[cCount] = 0;
        cCount++;
        started += n;
    }

    /** Counted fires in sector s (national maps). */
    public int counted(int s) {
        for (int k = 0; k < cCount; k++) if (cSec[k] == s) return cN[k];
        return 0;
    }

    /**
     * The counted fires: those whose chunk has become active catch real buildings there; the rest burn as numbers
     * (spreading, put out by rain or a crew from a station in reach, or burning out and bringing a building down
     * with some of the people in it).
     */
    private void counted(float dt, float grow, float burnout, float spread, float rain) {
        Sectors sec = w.sec;
        int out = 0;
        for (int k = 0; k < cCount; k++) {
            int s = cSec[k], n = cN[k];
            if (w.act.active(s)) {
                int[] bl = w.secBuildings[s];
                for (int i = 0; i < n && bl.length > 0; i++) {
                    int b = bl[Rng.hashInt(w.seed, w.tick + i, s * 7L + 3, bl.length)];
                    if (heat(b) <= 0 && w.r.bfloors(b) > 0) {
                        burning.add(b);
                        fire(b).heat = 0.3f;
                    }
                }
                continue;
            }
            cBurn[k] += dt;
            // spread (a fire grows to a block), rain and crews
            if (Rng.unit(w.seed, w.tick, s * 43L + 1) < spread * 0.5f * n * dt) n = Math.min(40, n + 1);
            if (w.weather.wet() && Rng.unit(w.seed, w.tick, s * 43L + 2) < rain * 20 * dt) n--;
            if (n > 0 && unitFF >= 0 && Rng.unit(w.seed, w.tick, s * 43L + 3) < dt / 300f && crewNear(sec.cx(s), sec.cy(s))) n--;
            if (n > 0 && cBurn[k] > burnout * 1.5f) {
                // burned out: the buildings come down, a few of the people sheltering in them with them
                int lost = Math.min(sec.popIn[s], sec.home[s] / 400 * n);
                if (lost > 0) {
                    sec.popIn[sec.touch(s)] -= lost;
                    w.stats.civDeaths += lost;
                    w.stats.lossBy[4] += lost;
                }
                collapsed += n;
                w.stats.buildingsLost += n;
                n = 0;
            }
            cN[k] = n;
            if (n > 0) {
                if (secFires[s] == 0) {
                    if (fireSecN == fireSecs.length) fireSecs = java.util.Arrays.copyOf(fireSecs, fireSecN * 2);
                    fireSecs[fireSecN++] = s;
                }
                secFires[s] += n;
            }
        }
        // drop the ones out or taken in (keeping the order)
        for (int k = 0; k < cCount; k++) {
            if (cN[k] > 0 && !w.act.active(cSec[k])) {
                cSec[out] = cSec[k];
                cN[out] = cN[k];
                cBurn[out] = cBurn[k];
                out++;
            }
        }
        cCount = out;
    }

    /** A fire station in reach of (x, y) with a crew to spare. */
    private boolean crewNear(float x, float y) {
        for (Region.Facility f : w.r.facilities) {
            if (f.kind != Region.F_FIRE || f.pool == null || f.pool[unitFF] < 4 || !w.strat.online(f.id)) continue;
            float dx = f.x - x, dy = f.y - y;
            if (dx * dx + dy * dy < 4500f * 4500f) return true;
        }
        return false;
    }

    /** Fires nobody is minding: where the power and water are gone and people are frightened. */
    private void untended(float dt) {
        float rate = w.c.rule("fireUntended", 0.5f) / 100000f / 60f;
        Sectors sec = w.sec;
        // (only the towns in the dark, and frightened streets, which are awake: in map order, merged)
        int ns = w.r.settlements.size();
        boolean[] darkSt = new boolean[ns];
        for (int st = 0; st < ns; st++) {
            int[] l = sec.idx.bySettlement[st];
            if (l.length > 0 && sec.grid[l[0]] >= 0) darkSt[st] = !w.strat.lit(l[0]) || w.infra.waterOut(st);
        }
        int[] darkL = sec.idx.sectorsOf(darkSt);
        int di = 0;
        for (int a = sec.idx.first(); ; ) {
            // the next sector of either list
            int s;
            if (di < darkL.length && (a < 0 || darkL[di] <= a)) {
                s = darkL[di++];
                if (s == a) a = sec.idx.next(a);
            } else if (a >= 0) {
                s = a;
                a = sec.idx.next(a);
            } else break;
            int p = sec.popIn[s];
            if (p < 200 || sec.grid[s] < 0) continue;
            boolean dark = !w.strat.lit(s) || (sec.settlement[s] >= 0 && w.infra.waterOut(sec.settlement[s]));
            if (!dark && sec.panic[s] < 0.5f) continue;
            float chance = p * rate * dt * (dark ? 1 : 0.3f) * (w.weather.wet() ? 0.3f : 1);
            if (Rng.unit(w.seed, w.tick, s * 41L + 9) >= chance) continue;
            if (w.r.nation != null && !w.act.active(s)) {
                countFire(s, 1); // (a sector is its chunk on a national map)
                continue;
            }
            int[] bl = w.secBuildings[s];
            if (bl.length == 0) continue;
            ignite(bl[Rng.hashInt(w.seed, w.tick, s, bl.length)], 0.15f);
        }
    }

    private void spreadFrom(int b, float wx, float wy) {
        Region r = w.r;
        for (int a = 0; a < 6; a++) {
            // look a few tiles out, mostly downwind
            float ang = (float) Math.atan2(wy, wx) + (Rng.unit(w.seed, w.tick, b * 17L + a) - 0.5f) * 2.4f;
            float d = 2 + Rng.unit(w.seed, w.tick, b * 19L + a) * 3;
            float cx = r.bx(b) + r.bw(b) / 2f + (float) Math.cos(ang) * (r.bw(b) / 2f + d);
            float cy = r.by(b) + r.bh(b) / 2f + (float) Math.sin(ang) * (r.bh(b) / 2f + d);
            int tx = (int) cx, ty = (int) cy;
            if (!r.in(tx, ty)) continue;
            int o = r.building(tx, ty);
            if (o >= 0 && o != b && heat(o) <= 0) {
                ignite(o, 0.15f);
                return;
            }
        }
    }

    /** One building of a kind, as a sentence says it: "apartment block", "office block", "house". */
    static String building(int kind) {
        String n = Data.buildingName(kind).toLowerCase();
        if (n.equals("apartments")) return "apartment block";
        if (n.equals("offices")) return "office block";
        return n;
    }

    private static boolean vowel(String s) {
        return s.length() > 0 && "aeiou".indexOf(s.charAt(0)) >= 0;
    }

    /** It burned out: the building comes down, with the people still in it. */
    private void collapse(int b, int s) {
        Region r = w.r;
        int lost = Math.min(w.sec.popIn[s], Math.max(0, r.bres(b) / 20));
        w.sec.popIn[w.sec.touch(s)] -= lost;
        w.stats.civDeaths += lost;
        w.stats.lossBy[4] += lost;
        fire(b).heat = 0;
        fire(b).burnT = 0;
        recall(b);
        collapsed++;
        w.stats.buildingsLost++;
        r.destroyBuilding(b);
        if (lost > 50) w.news.radio("A burning " + building(r.bkind(b)) + " has collapsed" + placeOf(b) + " (" + News.num(lost) + " dead)", w.r.doorX(b), w.r.doorY(b), News.LOSS);
    }

    /** The nearest station with a crew sends one (and, near the camera, an engine you can watch). */
    private void dispatch(int b, float x, float y) {
        if (unitFF < 0) return;
        Region.Facility best = null;
        float bd = 4500f * 4500f;
        for (Region.Facility f : w.r.facilities) {
            if (f.kind != Region.F_FIRE || f.pool == null || f.pool[unitFF] < 4 || !w.strat.online(f.id)) continue;
            float dx = f.x - x, dy = f.y - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = f;
            }
        }
        if (best == null) return;
        int n = fire(b).heat > 0.7f && best.pool[unitFF] >= 8 ? 8 : 4;
        best.pool[unitFF] -= n;
        fire(b).crews = n;
        fire(b).crewFrom = best.id;
        fire(b).eta = (float) Math.sqrt(bd) / 150f + 30;
        int s = w.r.sectorOfPx(x, y);
        if (!w.strategic && vEngine >= 0 && best.vpool[vEngine] > 0 && (w.sec.cam[s] || w.sec.hot[s] > 0)) {
            best.vpool[vEngine]--;
            int v = w.veh.add(w.c.vtypes.get(vEngine), best.x, best.y, 0, best.id);
            w.veh.driveTo(v, x, y, 0);
            w.veh.siren[v] = true;
            fire(b).engine = v;
        }
    }

    /**
     * The engine's crew on foot: down from the engine when it pulls up, each to a hose round the building; when the
     * dead come near they run back to it and the engine pulls out. Returns how many are at the hoses.
     */
    private int footCrew(int b, Fire f, float dx, float dy, float dt) {
        Vehicles vh = w.veh;
        Ents e = w.e;
        int v = f.engine;
        if (v < 0 || v >= vh.top || !vh.usable(v) || vh.type[v] != vEngine) {
            // the engine is lost: those on foot make their way back to the station
            for (int k = 0; k < f.foot.length; k++) if (alive(f.foot[k], f.footUid[k])) w.services.stray(f.foot[k], f.crewFrom);
            f.foot = new int[0];
            f.footUid = new int[0];
            f.engine = -1;
            f.unloaded = false;
            f.retreat = false;
            if (f.crews <= 0) f.crewFrom = -1;
            return 0;
        }
        float cx = (w.r.bx(b) + w.r.bw(b) / 2f) * Tile.SIZE, cy = (w.r.by(b) + w.r.bh(b) / 2f) * Tile.SIZE;
        if (!f.unloaded) {
            // pulled up close by (or as close as its road gets it, after a few seconds there)
            boolean stopped = vh.state[v] != Vehicles.V_DRIVE && vh.state[v] != Vehicles.V_PARKING && vh.state[v] != Vehicles.V_PULLIN;
            boolean there = stopped && (Math.abs(vh.x[v] - dx) + Math.abs(vh.y[v] - dy) < 450 || vh.timer[v] > 8);
            if (!there || unitFF < 0 || f.crews <= 0) return 0;
            // down from the engine: up to six of the crew, the rest stay with it
            int n = Math.min(6, f.crews);
            f.foot = new int[n];
            f.footUid = new int[n];
            Content.Unit u = w.c.units.get(unitFF);
            for (int k = 0; k < n; k++) {
                float a = vh.head[v] + 1.57f + k * 0.9f, px = vh.x[v] + (float) Math.cos(a) * (14 + k * 2), py = vh.y[v] + (float) Math.sin(a) * (14 + k * 2);
                if (!w.r.walkablePx(px, py)) {
                    px = vh.x[v];
                    py = vh.y[v];
                }
                int i = w.spawnCiv(px, py, u);
                e.flags[i] |= Ents.F_CREW;
                e.state[i] = Services.CREW_GO;
                float[] spot = hose(b, k, dx, dy);
                e.gx[i] = spot[0];
                e.gy[i] = spot[1];
                f.foot[k] = i;
                f.footUid[k] = e.uid[i];
            }
            f.crews -= n;
            f.unloaded = true;
            f.eta = 0;
            return 0;
        }
        // the dead coming: back to the engine
        if (!f.retreat) {
            boolean dead = w.sp.zombiesNear(e, vh.x[v], vh.y[v], 170, 1) > 0;
            for (int k = 0; k < f.foot.length && !dead; k++)
                if (alive(f.foot[k], f.footUid[k]) && w.sp.zombiesNear(e, e.x[f.foot[k]], e.y[f.foot[k]], 110, 2) >= 2) dead = true;
            if (dead) {
                f.retreat = true;
                f.retreatT = 0;
                w.news.radio("Fire crews pull back from a burning building" + placeOf(b) + ": the dead are on them", dx, dy, News.UNIT);
            }
        }
        int spraying = 0, out = 0;
        for (int k = 0; k < f.foot.length; k++) {
            int i = f.foot[k];
            if (!alive(i, f.footUid[k])) continue;
            out++;
            if (f.retreat) {
                e.state[i] = Services.CREW_BACK;
                e.gx[i] = vh.x[v];
                e.gy[i] = vh.y[v];
                if (Math.abs(e.x[i] - vh.x[v]) + Math.abs(e.y[i] - vh.y[v]) < 30) {
                    // aboard again (the bitten among them are held apart, and lost to the station)
                    if ((e.flags[i] & Ents.F_INFECTED) != 0) w.stats.crewDeaths++;
                    else f.crews++;
                    if (e.squad[i] >= 0) w.sq.removeMember(e.squad[i], i);
                    e.remove(i);
                    out--;
                }
                continue;
            }
            float[] spot = hose(b, k, dx, dy);
            if (Math.abs(e.x[i] - spot[0]) + Math.abs(e.y[i] - spot[1]) < 16) {
                e.state[i] = Services.CREW_SPRAY;
                e.gx[i] = cx;
                e.gy[i] = cy;
                spraying++;
            } else if (e.state[i] == Services.CREW_SPRAY && Math.abs(e.x[i] - spot[0]) + Math.abs(e.y[i] - spot[1]) < 40) spraying++;
            else {
                e.state[i] = Services.CREW_GO;
                e.gx[i] = spot[0];
                e.gy[i] = spot[1];
            }
        }
        if (f.retreat) {
            f.retreatT += dt;
            if (out == 0 || f.retreatT > 25) {
                for (int k = 0; k < f.foot.length; k++) if (alive(f.foot[k], f.footUid[k])) w.services.stray(f.foot[k], f.crewFrom);
                f.foot = new int[0];
                f.footUid = new int[0];
                f.retreat = false;
                f.unloaded = false;
                recall(b);
            }
        }
        return spraying;
    }

    private boolean alive(int i, int uid) {
        Ents e = w.e;
        return i >= 0 && i < e.top && e.uid[i] == uid && e.loc[i] == Ents.FREE && e.kind[i] == Ents.CIV;
    }

    /** Where firefighter k stands with a hose: round the door side of the building, a little way off. */
    private float[] hose(int b, int k, float dx, float dy) {
        float cx = (w.r.bx(b) + w.r.bw(b) / 2f) * Tile.SIZE, cy = (w.r.by(b) + w.r.bh(b) / 2f) * Tile.SIZE;
        float ax = dx - cx, ay = dy - cy, al = (float) Math.sqrt(ax * ax + ay * ay) + 0.01f;
        float a = (float) Math.atan2(ay, ax) + (k - 2.5f) * 0.3f;
        float rad = al + 10 + (k % 2) * 10;
        for (int t = 0; t < 6; t++) {
            float px = cx + (float) Math.cos(a) * (rad + t * 8), py = cy + (float) Math.sin(a) * (rad + t * 8);
            if (w.r.walkablePx(px, py)) return new float[]{px, py};
        }
        return new float[]{dx, dy};
    }

    /** Before fast-forward: the crews on foot climb back on (the engines go home with the rest of the vehicles). */
    void enterStrategic() {
        Ents e = w.e;
        for (int b : burning) {
            Fire f = fire(b);
            for (int k = 0; k < f.foot.length; k++) {
                int i = f.foot[k];
                if (!alive(i, f.footUid[k])) continue;
                if ((e.flags[i] & Ents.F_INFECTED) != 0) w.stats.crewDeaths++;
                else f.crews++;
                if (e.squad[i] >= 0) w.sq.removeMember(e.squad[i], i);
                e.remove(i);
            }
            f.foot = new int[0];
            f.footUid = new int[0];
            f.unloaded = false;
            f.retreat = false;
            f.engine = -1;
        }
    }


    /** The crew goes home (back in the station's pool); its engine drives back. */
    private void recall(int b) {
        Fire ff = fire(b);
        if (ff.foot.length > 0) {
            // out (or given up): the crew on foot climb back on, or walk back if the engine is gone
            Ents e = w.e;
            int v = ff.engine;
            boolean eng = v >= 0 && v < w.veh.top && w.veh.usable(v) && w.veh.type[v] == vEngine;
            for (int k = 0; k < ff.foot.length; k++) {
                int i = ff.foot[k];
                if (!alive(i, ff.footUid[k])) continue;
                if (eng && Math.abs(e.x[i] - w.veh.x[v]) + Math.abs(e.y[i] - w.veh.y[v]) < 200) {
                    if ((e.flags[i] & Ents.F_INFECTED) != 0) w.stats.crewDeaths++;
                    else ff.crews++;
                    if (e.squad[i] >= 0) w.sq.removeMember(e.squad[i], i);
                    e.remove(i);
                } else w.services.stray(i, ff.crewFrom);
            }
            ff.foot = new int[0];
            ff.footUid = new int[0];
        }
        ff.unloaded = false;
        ff.retreat = false;
        if (fire(b).crewFrom >= 0 && fire(b).crews > 0) {
            Region.Facility f = w.r.facilities.get(fire(b).crewFrom);
            if (f.pool != null) f.pool[unitFF] += fire(b).crews;
        }
        fire(b).crews = 0;
        fire(b).crewFrom = -1;
        int v = fire(b).engine;
        fire(b).engine = -1;
        if (v >= 0 && v < w.veh.top && w.veh.usable(v) && w.veh.type[v] == vEngine) w.veh.goHome(v);
    }

    @Override
    public void save(DataOutputStream o) throws IOException {
        o.writeFloat(wind);
        o.writeInt(started);
        o.writeInt(collapsed);
        o.writeInt(putOut);
        o.writeFloat(news);
        o.writeInt(burning.size());
        for (int b : burning) {
            o.writeInt(b);
            o.writeFloat(fire(b).heat);
            o.writeFloat(fire(b).burnT);
            o.writeFloat(fire(b).eta);
            o.writeInt(fire(b).crews);
            o.writeInt(fire(b).crewFrom);
            o.writeInt(fire(b).engine);
        }
        if (w.r.nation != null) {
            o.writeInt(cCount);
            for (int k = 0; k < cCount; k++) {
                o.writeInt(cSec[k]);
                o.writeInt(cN[k]);
                o.writeFloat(cBurn[k]);
            }
            o.writeInt(started);
        }
        if (footOn) {
            o.writeInt(burning.size());
            for (int b : burning) {
                Fire f = fire(b);
                o.writeBoolean(f.unloaded);
                o.writeBoolean(f.retreat);
                o.writeFloat(f.retreatT);
                SaveIO.intArr(o, f.foot);
                SaveIO.intArr(o, f.footUid);
            }
        }
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        wind = in.readFloat();
        started = in.readInt();
        collapsed = in.readInt();
        putOut = in.readInt();
        news = in.readFloat();
        int n = in.readInt();
        burning.clear();
        for (int k = 0; k < n; k++) {
            int b = in.readInt();
            burning.add(b);
            fire(b).heat = in.readFloat();
            fire(b).burnT = in.readFloat();
            fire(b).eta = in.readFloat();
            fire(b).crews = in.readInt();
            fire(b).crewFrom = in.readInt();
            fire(b).engine = in.readInt();
        }
        if (w.r.nation != null) {
            cCount = 0;
            int m = in.readInt();
            for (int k = 0; k < m; k++) {
                int s = in.readInt(), c = in.readInt();
                float t = in.readFloat();
                countFire(s, c);
                cBurn[cCount - 1] = t;
            }
            started = in.readInt();
        }
        if (in.available() > 0) {
            int m = in.readInt();
            for (int k = 0; k < m && k < burning.size(); k++) {
                Fire f = fire(burning.get(k));
                f.unloaded = in.readBoolean();
                f.retreat = in.readBoolean();
                f.retreatT = in.readFloat();
                f.foot = SaveIO.readIntArr(in);
                f.footUid = SaveIO.readIntArr(in);
            }
        }
    }

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        Region r = w.r;
        float z = cam.zoom, t = view.time, dp = cam.dp;
        float x0 = cam.x0() - 400, y0 = cam.y0() - 400, x1 = cam.x1() + 400, y1 = cam.y1() + 400;
        boolean dark = w.daylight() < 0.5f;
        float wxv = (float) Math.cos(wind), wyv = (float) Math.sin(wind);
        // the water from the hoses: a jet from each firefighter at work to the burning building, spray where it lands
        if (z > 0.2f)
            for (int k0 = 0; k0 < burning.size(); k0++) {
                Fire f = fire(burning.get(k0));
                for (int k = 0; k < f.foot.length; k++) {
                    int i = f.foot[k];
                    if (!alive(i, f.footUid[k]) || w.e.state[i] != Services.CREW_SPRAY) continue;
                    float ax = cam.toScreenX(w.e.x[i]), ay = cam.toScreenY(w.e.y[i]);
                    float tx = w.e.gx[i], ty = w.e.gy[i];
                    float ddx = tx - w.e.x[i], ddy = ty - w.e.y[i], dl = (float) Math.sqrt(ddx * ddx + ddy * ddy) + 0.01f;
                    float reach = Math.min(dl, 70);
                    float bx = cam.toScreenX(w.e.x[i] + ddx / dl * reach), by = cam.toScreenY(w.e.y[i] + ddy / dl * reach);
                    g.color(0x6080C8FF);
                    g.line(ax, ay, bx, by, Math.max(1.5f * dp, 3.5f * z));
                    g.color(0xC0D8F0FF);
                    g.line(ax, ay, bx, by, Math.max(0.8f * dp, 1.4f * z));
                    for (int d = 0; d < 5; d++) {
                        float ph = (t * 1.7f + d * 0.2f + k * 0.13f) % 1f;
                        float px = ax + (bx - ax) * ph + (Rng.unit(i, d, 3) - 0.5f) * 4 * dp, py = ay + (by - ay) * ph - (float) Math.sin(ph * 3.14f) * 5 * dp;
                        g.color(0xE0E8F8FF);
                        g.fillCircle(px, py, Math.max(0.8f * dp, 1.6f * z));
                    }
                    view.glow(g, view.smoke, bx, by, Math.max(5 * dp, 18 * z), 0.7f);
                }
            }
        for (int k0 = 0; k0 < burning.size(); k0++) {
            int b = burning.get(k0);
            float h = heat(b);
            if (h <= 0) continue;
            float wx = (r.bx(b) + r.bw(b) / 2f) * 16, wy = (r.by(b) + r.bh(b) / 2f) * 16;
            if (wx < x0 || wx > x1 || wy < y0 || wy > y1) continue;
            float sx = cam.toScreenX(wx), sy = cam.toScreenY(wy);
            float size = Math.max((float) Math.sqrt(r.bw(b) * r.bh(b)) * 16 * z, 6 * dp);
            float flick = 0.85f + 0.15f * (float) Math.sin(t * 11 + b * 1.7f) * (float) Math.sin(t * 7.3f + b);
            if (z < 0.2f) {
                view.glow(g, view.glowFire, sx, sy, size * 2.6f * flick, 0.9f);
                g.color(0xFFFFD060);
                g.fillCircle(sx, sy, Math.max(1.5f * dp, size * 0.12f));
                continue;
            }
            // the glow on everything round it, stronger by night
            view.glow(g, view.glowFire, sx, sy, size * (dark ? 3.4f : 2.2f) * flick, (dark ? 0.85f : 0.5f) * (0.4f + h * 0.6f));
            // smoke rising and drifting downwind
            for (int k = 0; k < 6; k++) {
                float ph = ((t * 0.22f + k / 6f + b * 0.17f) % 1f);
                float px = sx + wxv * ph * size * 3 + (float) Math.sin(t + k) * size * 0.1f, py = sy + wyv * ph * size * 3 - ph * size * 1.2f;
                view.glow(g, view.smoke, px, py, size * (0.7f + ph * 1.8f), Math.min(1, (1 - ph) * (dark ? 0.75f : 1.1f) * (0.5f + h * 0.5f)));
            }
            // flames over the roof
            int nf = 3 + (int) (h * 6);
            for (int k = 0; k < nf; k++) {
                float fx = sx + (Rng.unit(b, k, 1) - 0.5f) * size * 0.8f, fy = sy + (Rng.unit(b, k, 2) - 0.5f) * size * 0.8f;
                float fl = 0.6f + 0.4f * (float) Math.sin(t * 9 + k * 1.7f + b);
                float fr = size * 0.15f * fl * (0.5f + h);
                g.color(0xD8E04010);
                g.fillCircle(fx, fy, fr);
                g.color(0xE8FF9020);
                g.fillCircle(fx - wxv * fr * 0.2f, fy - fr * 0.3f, fr * 0.65f);
                g.color(0xF0FFE070);
                g.fillCircle(fx - wxv * fr * 0.3f, fy - fr * 0.45f, fr * 0.3f);
            }
            // embers
            g.color(0xE0FFB040);
            for (int k = 0; k < 4; k++) {
                float ph = ((t * 0.6f + k * 0.25f + b * 0.31f) % 1f);
                g.fillCircle(sx + wxv * ph * size * 1.6f + (Rng.unit(b, k, 5) - 0.5f) * size * 0.6f, sy + wyv * ph * size * 1.6f - ph * size * 0.8f, Math.max(1, 0.9f * dp) * (1 - ph));
            }
        }
    }

    @Override
    public String status() {
        return burning.isEmpty() ? null : burning.size() + " fires";
    }
}
