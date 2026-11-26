package com.instantaxtion.zcs2;

import java.util.Arrays;

/**
 * Every vehicle and aircraft. Ground vehicles drive on the road graph (routes are junction lists, followed by
 * steering at a point a little ahead in their lane), queue behind each other, run the dead down, and get mobbed
 * when they stop among them. Aircraft fly straight, orbit over a fight, and come home to rearm.
 */
public final class Vehicles implements ActiveSet.Paged {
    public static final byte V_NONE = 0, V_PARKED = 1, V_DRIVE = 2, V_ARRIVED = 3, V_WRECK = 4, V_FLY = 5, V_ORBIT = 6,
            V_RTB = 7, V_STRIKE = 8, V_PARKING = 9, V_PULLIN = 10;
    public static final String[] STATE_NAMES = {"", "Parked", "Driving", "On scene", "Wrecked", "Flying", "Over the fight",
            "Returning", "Strike run", "Parking", "Pulling in"};
    public static final byte R_SQUAD = 0, R_PATROL = 1, R_SUPPLY = 2, R_AIR = 3, R_STRIKE = 4, R_TRANSPORT = 5;

    private final World w;
    public int top;
    public float[] x = new float[64], y = new float[64], head = new float[64], spd = new float[64], hp = new float[64],
            destX = new float[64], destY = new float[64], homeX = new float[64], homeY = new float[64], fuel = new float[64],
            stuck = new float[64], wpnT = new float[64], wpn2T = new float[64], timer = new float[64], mobT = new float[64],
            seg = new float[64], orbitA = new float[64], ghost = new float[64], alt = new float[64];
    /** Where a vehicle is pulling in to (a place at the kerb), and how long it has waited for room to move. */
    public float[] slotX = new float[64], slotY = new float[64], slotH = new float[64], wait = new float[64];
    public short[] type = new short[64], kills = new short[64];
    public byte[] state = new byte[64], role = new byte[64];
    public int[] squad = new int[64], home = new int[64], ri = new int[64], crewN = new int[64], ammo = new int[64],
            cargo = new int[64], uid = new int[64], target = new int[64];
    public int[][] route = new int[64][], crew = new int[64][];
    public boolean[] siren = new boolean[64], returning = new boolean[64];
    private int nextUid = 1;

    // a grid for "which vehicle is near here", kept per active page (ActiveSet) as Spatial's is: 8x8 cells a page,
    // plus the row and column past the map's last page; gcell(cx, cy) finds a cell (0 is the empty one)
    private static final int GC = 128, GPS = ActiveSet.PX / GC;
    private final int gw;
    private int[] gStart, gList = new int[64], gEnd, gTouched = new int[64];
    private int gTouchedN;
    private final int[] slotOf;
    private final int gpw, gex, gey, gedgeW, gOff;
    private final boolean gdense;
    /** Ground vehicles in transit move at this speed along their routes (25 km/h, px/s; a content rule in P9). */
    static final float TRANSIT_SPEED = 1667f;
    /** Vehicle-steps spent in transit so far (for tests and probes). */
    public long transitSteps;
    /** Ground vehicles per 512 px sector: a small table that answers "no car near here" for most of the dead. */
    private final int[] secCars;

    Vehicles(World w) {
        this.w = w;
        if (w.r.W != w.r.H) throw new IllegalStateException("the vehicle grid assumes a square map");
        gw = w.r.px() / GC + 1;
        secCars = new int[w.r.secW * w.r.secH];
        ActiveSet act = w.act;
        slotOf = act.slotOf;
        gpw = act.pw;
        gdense = !act.sparse;
        gex = Math.min(gw, act.pw * GPS);
        gey = Math.min(gw, act.ph * GPS);
        gedgeW = gw - gex;
        gOff = 1 + gedgeW * gw + (gw - gey) * gex;
        act.register(this);
    }

    @Override
    public void slotsGrown(int cap) {
        // every page active, always (today's maps): the old grid, row by row (1 + cy * gw + cx), never paged
        int n = gdense ? 1 + gw * gw : gOff + cap * GPS * GPS;
        gStart = gStart == null ? new int[n] : Arrays.copyOf(gStart, n);
        gEnd = gEnd == null ? new int[n] : Arrays.copyOf(gEnd, n);
    }

    @Override
    public void slotAssigned(int s, int p) {
        if (gdense) return;
        int a = gOff + s * GPS * GPS;
        Arrays.fill(gStart, a, a + GPS * GPS, 0);
        Arrays.fill(gEnd, a, a + GPS * GPS, 0);
    }

    /** Grid cell (cx, cy): an index into gStart / gEnd (0, always empty, off the grid or on a page that isn't active). */
    private int gcell(int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= gw || cy >= gw) return 0;
        if (gdense) return 1 + cy * gw + cx;
        if (cx < gex && cy < gey) {
            int s = slotOf[(cy >> 3) * gpw + (cx >> 3)];
            return s < 0 ? 0 : gOff + (s << 6 | (cy & 7) << 3 | (cx & 7));
        }
        if (cx >= gex) return 1 + (cx - gex) * gw + cy;
        if (cy >= gey) return 1 + gedgeW * gw + (cy - gey) * gex + cx;
        int s = gdense ? (cy >> 3) * gpw + (cx >> 3) : slotOf[(cy >> 3) * gpw + (cx >> 3)];
        return s < 0 ? 0 : gOff + (s << 6 | (cy & 7) << 3 | (cx & 7));
    }

    public Content.VType type(int v) {
        return w.c.vtypes.get(type[v]);
    }

    public int add(Content.VType t, float px, float py, float heading, int homeFacility) {
        int v = -1;
        for (int i = 0; i < top; i++) if (state[i] == V_NONE) { v = i; break; }
        if (v < 0) {
            if (top == x.length) grow(top * 2);
            v = top++;
        }
        x[v] = px;
        y[v] = py;
        head[v] = heading;
        spd[v] = 0;
        hp[v] = t.hp;
        type[v] = (short) t.index;
        state[v] = t.flies ? V_PARKED : V_PARKED;
        role[v] = R_SQUAD;
        squad[v] = -1;
        home[v] = homeFacility;
        homeX[v] = px;
        homeY[v] = py;
        route[v] = null;
        ri[v] = 0;
        seg[v] = 0;
        crew[v] = new int[Math.max(1, t.seats)];
        crewN[v] = 0;
        fuel[v] = 1;
        stuck[v] = wpnT[v] = wpn2T[v] = timer[v] = mobT[v] = ghost[v] = 0;
        alt[v] = 0;
        ammo[v] = t.weapon != null ? t.weapon.ammo : 0;
        kills[v] = 0;
        // police cars and army trucks carry a couple of spare loads of ammunition for their squad
        cargo[v] = t.seats > 0 && !t.flies && ("police".equals(t.faction) || "army".equals(t.faction)) ? 2 : 0;
        target[v] = -1;
        siren[v] = false;
        returning[v] = false;
        uid[v] = nextUid++;
        slotX[v] = slotY[v] = slotH[v] = wait[v] = 0;
        // a car or truck put down where another already stands moves along the kerb to a free place
        if (onRoad(v) && !freeAt(v, px, py, heading, 2)) {
            int n = w.r.roads.nearestNode(px, py, 300);
            // the kerbs round the junction; when a crowded yard (a formation sending out a dozen squads at once) has
            // filled those, the streets further out: never on top of another vehicle
            if (n >= 0 && (findSlot(v, n, px, py, 400) || findSlot(v, n, px, py, 1500, 8))) {
                x[v] = homeX[v] = slotX[v];
                y[v] = homeY[v] = slotY[v];
                head[v] = slotH[v];
            }
        }
        return v;
    }

    private void grow(int n) {
        x = Arrays.copyOf(x, n); y = Arrays.copyOf(y, n); head = Arrays.copyOf(head, n); spd = Arrays.copyOf(spd, n);
        hp = Arrays.copyOf(hp, n); destX = Arrays.copyOf(destX, n); destY = Arrays.copyOf(destY, n);
        homeX = Arrays.copyOf(homeX, n); homeY = Arrays.copyOf(homeY, n); fuel = Arrays.copyOf(fuel, n);
        stuck = Arrays.copyOf(stuck, n); wpnT = Arrays.copyOf(wpnT, n); wpn2T = Arrays.copyOf(wpn2T, n);
        timer = Arrays.copyOf(timer, n); mobT = Arrays.copyOf(mobT, n); seg = Arrays.copyOf(seg, n);
        orbitA = Arrays.copyOf(orbitA, n); ghost = Arrays.copyOf(ghost, n); alt = Arrays.copyOf(alt, n);
        slotX = Arrays.copyOf(slotX, n); slotY = Arrays.copyOf(slotY, n); slotH = Arrays.copyOf(slotH, n); wait = Arrays.copyOf(wait, n);
        type = Arrays.copyOf(type, n); kills = Arrays.copyOf(kills, n); state = Arrays.copyOf(state, n);
        role = Arrays.copyOf(role, n); squad = Arrays.copyOf(squad, n); home = Arrays.copyOf(home, n);
        ri = Arrays.copyOf(ri, n); crewN = Arrays.copyOf(crewN, n); ammo = Arrays.copyOf(ammo, n);
        cargo = Arrays.copyOf(cargo, n); uid = Arrays.copyOf(uid, n); target = Arrays.copyOf(target, n);
        route = Arrays.copyOf(route, n); crew = Arrays.copyOf(crew, n); siren = Arrays.copyOf(siren, n);
        returning = Arrays.copyOf(returning, n);
    }

    void ensure(int n) {
        if (n > x.length) grow(n);
    }

    public int nextUid() {
        return nextUid;
    }

    public void setNextUid(int u) {
        nextUid = u;
    }

    // ------------------------------------------------------------------ queries used by squads and the AI

    public boolean usable(int v) {
        return v >= 0 && state[v] != V_NONE && state[v] != V_WRECK && hp[v] > 0;
    }

    public boolean arrived(int v) {
        return state[v] == V_ARRIVED || (state[v] == V_PARKED && route[v] == null && !type(v).flies && crewN[v] > 0 && timer[v] > 0.5f);
    }

    public boolean mobbed(int v) {
        return mobT[v] > 2.5f;
    }

    public boolean flying(int v) {
        return state[v] == V_FLY || state[v] == V_ORBIT || state[v] == V_RTB || state[v] == V_STRIKE;
    }

    /** The nearest vehicle within rad (read-only; safe in the parallel phase). */
    public int near(float px, float py, float rad) {
        if (gTouchedN == 0) return -1;
        // any car in the sectors this search could reach? (cells are 128 px, the search covers one cell round)
        float cs = Region.SECT * Tile.SIZE;
        int sw = w.r.secW, sh = w.r.secH;
        int sx0 = Math.max(0, (int) (((int) (px / GC) - 1) * GC / cs)), sx1 = Math.min(sw - 1, (int) (((int) (px / GC) + 2) * GC / cs));
        int sy0 = Math.max(0, (int) (((int) (py / GC) - 1) * GC / cs)), sy1 = Math.min(sh - 1, (int) (((int) (py / GC) + 2) * GC / cs));
        boolean any = false;
        for (int sy = sy0; sy <= sy1 && !any; sy++)
            for (int sx = sx0; sx <= sx1; sx++)
                if (secCars[sy * sw + sx] > 0) {
                    any = true;
                    break;
                }
        if (!any) return -1;
        int cx = (int) (px / GC), cy = (int) (py / GC);
        int best = -1;
        float bd = rad * rad;
        for (int yy = cy - 1; yy <= cy + 1; yy++)
            for (int xx = cx - 1; xx <= cx + 1; xx++) {
                int c = gcell(xx, yy);
                for (int j = gStart[c]; j < gEnd[c]; j++) {
                    int v = gList[j];
                    float dx = x[v] - px, dy = y[v] - py, d = dx * dx + dy * dy;
                    if (d < bd) {
                        bd = d;
                        best = v;
                    }
                }
            }
        return best;
    }

    // ------------------------------------------------------------------ crews

    public boolean board(int v, int ent) {
        Ents e = w.e;
        if (!usable(v) || e.loc[ent] != Ents.FREE) return false;
        Content.VType t = type(v);
        if (crewN[v] >= t.seats) return false;
        e.board(ent, v);
        crew[v][crewN[v]++] = ent;
        return true;
    }

    /** Everyone aboard gets out round the vehicle. */
    public void alightAll(int v) {
        Ents e = w.e;
        for (int k = 0; k < crewN[v]; k++) {
            int m = crew[v][k];
            float[] p = outSpot(v, k);
            e.alight(m, p[0], p[1]);
        }
        crewN[v] = 0;
    }

    private float[] outSpot(int v, int k) {
        for (int a = 0; a < 10; a++) {
            float ang = head[v] + 1.57f + (a + k) * 0.9f;
            float rad = 14 + (k % 4) * 4 + a * 3;
            float px = x[v] + (float) Math.cos(ang) * rad, py = y[v] + (float) Math.sin(ang) * rad;
            if (w.r.walkablePx(px, py)) return new float[]{px, py};
        }
        // nowhere open beside it: the road it's on
        float px = x[v], py = y[v];
        if (!w.r.walkablePx(px, py) && route[v] != null) {
            px = w.r.roads.nx[route[v][Math.min(ri[v], route[v].length - 1)]];
            py = w.r.roads.ny[route[v][Math.min(ri[v], route[v].length - 1)]];
        }
        return new float[]{px, py};
    }

    /** One crew member died aboard. */
    void dropCrew(int v, int ent) {
        for (int k = 0; k < crewN[v]; k++)
            if (crew[v][k] == ent) {
                crew[v][k] = crew[v][--crewN[v]];
                return;
            }
    }

    /** The squad is gone: the vehicle is left where it is (and towed back to its pool later). */
    public void release(int v) {
        squad[v] = -1;
        if (!usable(v)) return;
        if (crewN[v] > 0) alightAll(v);
        if (type(v).flies) {
            goHome(v);
            return;
        }
        state[v] = V_ARRIVED;
        route[v] = null;
        spd[v] = 0;
        siren[v] = false;
        timer[v] = 0;
    }

    /** Back into its home's pool of vehicles (off the map). */
    void toPool(int v) {
        if (home[v] >= 0 && home[v] < w.r.facilities.size() && usable(v)) {
            Region.Facility f = w.r.facilities.get(home[v]);
            if (f.vpool != null && type[v] < f.vpool.length) f.vpool[type[v]]++;
        }
        if (squad[v] >= 0 && squad[v] < w.sq.list.size()) {
            Squads.Squad s = w.sq.get(squad[v]);
            if (s.vehicle == v) s.vehicle = -1;
        }
        if (crewN[v] > 0) alightAll(v);
        squad[v] = -1;
        route[v] = null;
        state[v] = V_NONE;
    }

    /** A squad's car or truck with nobody in it and no squad that still means to use it. */
    private boolean abandoned(int v) {
        if (crewN[v] > 0 || role[v] != R_SQUAD) return false;
        Content.VType t = type(v);
        if (t.flies || t.tracked || t.seats == 0 || !"police".equals(t.faction) && !"army".equals(t.faction)) return false;
        if (squad[v] < 0 || squad[v] >= w.sq.list.size()) return true;
        Squads.Squad s = w.sq.get(squad[v]);
        return !s.alive || s.vehicle != v;
    }

    /** Stops (nobody left aboard to drive on): at the kerb close by if there is room, else where it is. */
    public void halt(int v) {
        if (state[v] == V_DRIVE || state[v] == V_PARKING) {
            state[v] = V_ARRIVED;
            route[v] = null;
            spd[v] = 0;
            siren[v] = false;
            timer[v] = 0;
            // not in the middle of the road: to the kerb if there's a place close by
            if (onRoad(v)) {
                int n = w.r.roads.nearestNode(x[v], y[v], 300);
                if (n >= 0 && findSlot(v, n, x[v], y[v], 90)) state[v] = V_PULLIN;
            }
        }
    }

    public void goHome(int v) {
        returning[v] = true;
        siren[v] = false;
        if (type(v).flies) {
            destX[v] = homeX[v];
            destY[v] = homeY[v];
            if (state[v] != V_PARKED) state[v] = V_RTB;
            return;
        }
        if (!driveTo(v, homeX[v], homeY[v], 0)) state[v] = V_PARKED;
    }

    public void refuel(int v, int facility) {
        if (w.strat.fuelOk(facility)) fuel[v] = 1;
    }

    /** Sends a vehicle by road (or air) to near (tx, ty), stopping stopShort px before it. */
    public boolean driveTo(int v, float tx, float ty, float stopShort) {
        Content.VType t = type(v);
        if (!usable(v)) return false;
        returning[v] = false;
        if (t.flies) {
            destX[v] = tx;
            destY[v] = ty;
            state[v] = V_FLY;
            timer[v] = 0;
            return true;
        }
        if (fuel[v] <= 0.02f) return false;
        RoadGraph g = w.r.roads;
        int a = g.nearestNode(x[v], y[v], 1200);
        // aim for a junction short of the target, on the side we come from
        float dx = x[v] - tx, dy = y[v] - ty, dl = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
        float ax = tx, ay = ty;
        if (stopShort > 0 && dl > stopShort) {
            ax = tx + dx / dl * stopShort;
            ay = ty + dy / dl * stopShort;
        }
        int b = g.nearestNode(ax, ay, 1600);
        if (a < 0 || b < 0) return false;
        int[] rt = g.route(a, b);
        if (rt == null) return false;
        route[v] = rt;
        ri[v] = 0;
        seg[v] = 0;
        destX[v] = tx;
        destY[v] = ty;
        state[v] = V_DRIVE;
        siren[v] = t.siren && squad[v] >= 0;
        stuck[v] = 0;
        timer[v] = 0;
        return true;
    }

    /** A patrol car reached its spot: on to another one in its town. */
    public void patrolOn(int v) {
        RoadGraph g = w.r.roads;
        int homeSet = home[v] >= 0 ? w.r.facilities.get(home[v]).settlement : -1;
        // (a national map: picked from the town's own junctions, not a whole country's)
        int own = w.r.nation != null ? g.nodesOfCount(homeSet) : 0;
        for (int a = 0; a < 12; a++) {
            int n = own > 0 ? g.nodeOf(homeSet, Rng.hashInt(w.seed, uid[v] * 977L + w.tick, a, own)) : Rng.hashInt(w.seed, uid[v] * 977L + w.tick, a, g.nodeCount);
            if (g.nset[n] == homeSet && g.nodeLive(n)) {
                if (driveTo(v, g.nx[n], g.ny[n], 0)) {
                    siren[v] = false;
                    return;
                }
            }
        }
        state[v] = V_ARRIVED;
    }

    // ------------------------------------------------------------------ harm

    void claw(int v, int zombie) {
        if (!usable(v)) return;
        Content.VType t = type(v);
        hp[v] -= w.ztypeOf(zombie).bite * 0.35f * (1 - t.armor);
        if (hp[v] <= 0) wreck(v);
        // a broken window: someone inside gets dragged out
        else if (t.armor < 0.3f && crewN[v] > 0 && hp[v] < t.hp * 0.35f && w.rng.chance(0.05f)) {
            int m = crew[v][--crewN[v]];
            w.e.alight(m, w.e.x[zombie] + 4, w.e.y[zombie] + 4);
        }
    }

    /**
     * Pushed bodily up to dist px along (ux, uy) (one of the big dead shoving it): it goes a little at a time and
     * stops at the first vehicle (wrecks included) or wall in the way, so it never ends up on top of anything. A wreck
     * doesn't move at all: it stays where it burned out, an obstacle the others park round.
     */
    void shove(int v, float ux, float uy, float dist) {
        if (state[v] == V_WRECK || state[v] == V_NONE || flying(v)) return;
        float m = w.r.px();
        for (float done = 0; done < dist - 0.01f; ) {
            float s = Math.min(2, dist - done);
            float nx = x[v] + ux * s, ny = y[v] + uy * s;
            if (nx <= 1 || ny <= 1 || nx >= m - 1 || ny >= m - 1 || !w.r.walkablePx(nx, ny)) break;
            if (onRoad(v) && !roomFor(v, nx, ny, head[v])) break;
            x[v] = nx;
            y[v] = ny;
            done += s;
        }
    }

    void blast(float bx, float by, float rad, float dmg) {
        for (int v = 0; v < top; v++) {
            if (state[v] == V_NONE || flying(v)) continue;
            float dx = x[v] - bx, dy = y[v] - by, d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > rad) continue;
            hp[v] -= dmg * (1 - d / rad) * (1 - type(v).armor) * 0.6f;
            if (hp[v] <= 0 && state[v] != V_WRECK) wreck(v);
        }
    }

    private void wreck(int v) {
        if (crewN[v] > 0) alightAll(v);
        state[v] = V_WRECK;
        spd[v] = 0;
        timer[v] = 0;
        siren[v] = false;
        w.fx.add(Effects.BLAST, x[v], y[v], 30, 0, 0.6f);
        w.fx.sound("boom", x[v], y[v]);
        w.stats.vehiclesLost++;
        w.fires.igniteNear(x[v], y[v], 0.5f);
        if (type(v).flies) {
            w.news.radio(type(v).name + " down!", x[v], y[v], News.LOSS);
            alt[v] = 0;
        }
        if (squad[v] >= 0) {
            Squads.Squad s = w.sq.get(squad[v]);
            if (s.vehicle == v) s.vehicle = -1;
            squad[v] = -1;
        }
    }

    // ------------------------------------------------------------------ every step

    void update() {
        float dt = World.DT;
        for (int v = 0; v < top; v++) {
            switch (state[v]) {
                case V_NONE: continue;
                case V_DRIVE:
                    // no active chunk under it: on along its route at road speed, with nothing to run into
                    if (w.act.sparse && !w.act.activePx(x[v], y[v])) transit(v, dt);
                    else drive(v, dt);
                    break;
                case V_PARKING: park(v, dt); break;
                case V_PULLIN: pullIn(v, dt); break;
                case V_FLY:
                case V_RTB:
                case V_ORBIT:
                case V_STRIKE: fly(v, dt); break;
                case V_WRECK:
                    timer[v] += dt;
                    if (timer[v] % 1f < dt) w.fx.add(Effects.SMOKE, x[v], y[v], 20, 0, 4);
                    if (timer[v] > 300) state[v] = V_NONE; // towed away
                    continue;
                default:
                    timer[v] += dt;
                    spd[v] = 0;
                    mobCheck(v, dt);
                    // left at a scene by a squad that's gone: towed back to its pool
                    if (state[v] == V_ARRIVED && timer[v] > 120 && abandoned(v)) {
                        toPool(v);
                        continue;
                    }
            }
            if (state[v] != V_NONE && state[v] != V_WRECK) weapons(v, dt);
        }
        // the grid reflects where vehicles are now (a save restores it exactly by rebuilding it)
        rebuildGrid();
    }

    void rebuildGrid() {
        // only the cells used last time are cleared (a country's grid is mostly empty)
        for (int k = 0; k < gTouchedN; k++) gStart[gTouched[k]] = gEnd[gTouched[k]] = 0;
        gTouchedN = 0;
        Arrays.fill(secCars, 0);
        int n = 0;
        for (int v = 0; v < top; v++) {
            if (state[v] == V_NONE || flying(v)) continue;
            int c = cell(v);
            if (c == 0) continue; // on a page that isn't active (in transit): in no cell
            if (gEnd[c] == 0) {
                if (gTouchedN == gTouched.length) gTouched = Arrays.copyOf(gTouched, gTouchedN * 2);
                gTouched[gTouchedN++] = c;
            }
            gEnd[c]++;
            secCars[w.r.sectorOfPx(Math.max(0, x[v]), Math.max(0, y[v]))]++;
            n++;
        }
        if (gList.length < n) gList = new int[n * 2];
        int p = 0;
        for (int k = 0; k < gTouchedN; k++) {
            int c = gTouched[k];
            gStart[c] = p;
            p += gEnd[c];
            gEnd[c] = gStart[c];
        }
        for (int v = 0; v < top; v++) {
            if (state[v] == V_NONE || flying(v)) continue;
            int c = cell(v);
            if (c != 0) gList[gEnd[c]++] = v;
        }
    }

    private int cell(int v) {
        return gcell(cellX(v), cellY(v));
    }

    private int cellX(int v) {
        return Math.max(0, Math.min(gw - 1, (int) (x[v] / GC)));
    }

    private int cellY(int v) {
        return Math.max(0, Math.min(gw - 1, (int) (y[v] / GC)));
    }

    private void drive(int v, float dt) {
        RoadGraph g = w.r.roads;
        int[] rt = route[v];
        Content.VType t = type(v);
        timer[v] += dt;
        if (rt == null || ri[v] >= rt.length - 1) {
            arrive(v);
            return;
        }
        int a = rt[ri[v]], b = rt[ri[v] + 1];
        int e = g.edgeBetween(a, b);
        if (e >= 0 && !g.ealive[e]) {
            // the road is gone (a blown bridge): find another way
            if (!driveTo(v, destX[v], destY[v], 0)) arrive(v);
            return;
        }
        float ax = g.nx[a], ay = g.ny[a], bx = g.nx[b], by = g.ny[b];
        float sx = bx - ax, sy = by - ay, len = (float) Math.sqrt(sx * sx + sy * sy) + 0.001f;
        float ux = sx / len, uy = sy / len;
        float lane = e >= 0 ? Math.min(g.ehalf[e] * 0.45f, 14) : 8;
        // squeezing past something stopped in the lane: out towards the middle of the road
        if (ghost[v] > 0) lane -= t.width + 4;
        float rx = -uy * lane, ry = ux * lane;
        // progress along this road
        float px = x[v] - ax, py = y[v] - ay;
        float along = px * ux + py * uy;
        // the next road, if there is one: its lane decides where the corner is
        boolean hasNext = ri[v] + 2 < rt.length;
        float u2x = 0, u2y = 0, lane2 = 8;
        if (hasNext) {
            int c2 = rt[ri[v] + 2];
            float cx2 = g.nx[c2] - bx, cy2 = g.ny[c2] - by, l2 = (float) Math.sqrt(cx2 * cx2 + cy2 * cy2) + 0.001f;
            u2x = cx2 / l2;
            u2y = cy2 / l2;
            int e2 = g.edgeBetween(b, c2);
            lane2 = e2 >= 0 ? Math.min(g.ehalf[e2] * 0.45f, 14) : 8;
        }
        // on to the next road: at the corner of the two lanes (inside a right turn that's before the junction),
        // when close to the junction, or once already heading down the next road
        float toBx = bx - x[v], toBy = by - y[v];
        float dB = (float) Math.sqrt(toBx * toBx + toBy * toBy);
        boolean next = along >= len - Math.max(6, lane2 + 4) || dB < lane + 10;
        if (!next && hasNext && along >= len - 60) {
            float along2 = -toBx * u2x - toBy * u2y;
            if (along2 > -lane2 && dB < 60) next = true;
        }
        // a watchdog: moving but getting nowhere on this road for too long
        if (!next && spd[v] > 5) {
            seg[v] += dt;
            if (seg[v] > 8 + len / Math.max(20f, t.speed * 0.35f)) next = true;
        }
        if (next) {
            ri[v]++;
            seg[v] = 0;
            if (ri[v] >= rt.length - 1) arrive(v);
            return;
        }
        // steer at a point ahead in my lane
        float look = 22 + spd[v] * 0.15f;
        float tx, ty;
        float off = Math.abs(px * uy - py * ux - lane) ;
        if (along + look < len || !hasNext) {
            float s = Math.min(len, Math.max(0, along) + look);
            tx = ax + ux * s + rx;
            ty = ay + uy * s + ry;
        } else {
            float over = along + look - len;
            tx = bx + u2x * over - u2y * lane2;
            ty = by + u2y * over + u2x * lane2;
        }
        if (off > 60) {
            tx = ax + rx;
            ty = ay + ry;
        }
        float hx = tx - x[v], hy = ty - y[v];
        float want = (float) Math.atan2(hy, hx);
        float diff = angle(want - head[v]);
        float turnRate = 2.6f + 20f / (spd[v] + 10);
        // a barricade across the road: stop at it, and find a way round (the road is closed to routing)
        float ahead = t.length * 0.5f + 10;
        float hfx = (float) Math.cos(head[v]), hfy = (float) Math.sin(head[v]);
        boolean wallOnRoad = w.r.tileAtPx(x[v] + ux * ahead, y[v] + uy * ahead) == Tile.BARRICADE
                || w.r.tileAtPx(tx, ty) == Tile.BARRICADE;
        boolean wallInFront = w.r.tileAtPx(x[v] + hfx * ahead, y[v] + hfy * ahead) == Tile.BARRICADE;
        if (wallInFront && !wallOnRoad && Math.abs(diff) > 0.5f) {
            // facing a barricade but the road goes elsewhere: turn on the spot
            spd[v] = 0;
            head[v] += Math.max(-3 * dt, Math.min(3 * dt, diff));
            return;
        }
        if (wallOnRoad || wallInFront) {
            spd[v] = 0;
            stuck[v] += dt;
            if (stuck[v] > 1.5f) {
                stuck[v] = 0;
                // a fresh route that still starts at the wall, or no way round: get out here
                if (timer[v] < 6 && ri[v] == 0) arrive(v);
                else if (!driveTo(v, destX[v], destY[v], 0) || throughBarricade(v)) arrive(v);
            }
            return;
        }
        float oldHead = head[v];
        head[v] += Math.max(-turnRate * dt, Math.min(turnRate * dt, diff));
        // speed: road kind, corners, traffic ahead, the dead in the way
        float limit = t.speed * (g.ekind[e >= 0 ? e : 0] == RoadGraph.HIGHWAY ? 1f : g.ekind[e >= 0 ? e : 0] == RoadGraph.AVENUE ? 0.72f : 0.58f);
        if (siren[v]) limit *= 1.15f;
        if (Math.abs(diff) > 0.5f) limit *= 0.45f;
        if (hasNext && len - along < 50) {
            float dot = u2x * ux + u2y * uy;
            if (dot < 0.7f) limit *= 0.5f;
        }
        if (!hasNext && len - along < 60) limit *= 0.5f;
        float fx = (float) Math.cos(head[v]), fy = (float) Math.sin(head[v]);
        if (ghost[v] > 0) ghost[v] -= dt;
        else limit = Math.min(limit, traffic(v, fx, fy, t));
        limit = Math.min(limit, runDown(v, fx, fy, t, dt));
        if (spd[v] < limit) spd[v] = Math.min(limit, spd[v] + 90 * dt);
        else spd[v] = Math.max(limit, spd[v] - 220 * dt);
        if (spd[v] < 2) {
            stuck[v] += dt;
            if (stuck[v] > 3.5f) {
                // waited long enough: squeeze past whatever is there
                ghost[v] = 2.5f;
                stuck[v] = 0;
            }
        } else stuck[v] = 0;
        float nx = x[v] + fx * spd[v] * dt, ny = y[v] + fy * spd[v] * dt;
        // never into another vehicle: wait for room (after a long wait it gives up and stops where it is)
        int blk = blocker(v, nx, ny, head[v], oldHead);
        if (blk >= 0) {
            head[v] = oldHead;
            spd[v] = 0;
            wait[v] += dt;
            stuck[v] += dt;
            // two vehicles in each other's way (at a corner, nose to nose): the newer one backs off a little
            if (wait[v] > 1 && state[blk] == V_DRIVE && wait[blk] > 0.5f && uid[v] > uid[blk]) {
                float bx2 = x[v] - fx * 12 * dt, by2 = y[v] - fy * 12 * dt;
                if (roomFor(v, bx2, by2, head[v])) {
                    x[v] = bx2;
                    y[v] = by2;
                }
            }
            // no way through for a long while: stop here (to the kerb if there's room) rather than drive over it
            if (wait[v] > 10) {
                wait[v] = 0;
                arrive(v);
            }
            return;
        }
        wait[v] = Math.max(0, wait[v] - dt * 2);
        x[v] = nx;
        y[v] = ny;
        fuel[v] -= spd[v] * dt / 400000f;
        if (fuel[v] < 0) fuel[v] = 0;
    }

    /**
     * A vehicle on a page that isn't active is in transit: it follows its route junction to junction at
     * TRANSIT_SPEED, through nothing and nobody (none of that is simulated there), and drives normally again as soon
     * as it is on an active page. Arriving, it makes the chunk it stops in active first (it is about to use it).
     */
    private void transit(int v, float dt) {
        RoadGraph g = w.r.roads;
        int[] rt = route[v];
        timer[v] += dt;
        transitSteps++;
        float left = w.rule("transitSpeed", TRANSIT_SPEED) * dt;
        while (rt != null && ri[v] < rt.length - 1 && left > 0) {
            int a = rt[ri[v]], b = rt[ri[v] + 1];
            int e = g.edgeBetween(a, b);
            if (e >= 0 && !g.ealive[e]) {
                // the road is gone: find another way, from where it is
                w.act.touchPx(x[v], y[v]);
                if (!driveTo(v, destX[v], destY[v], 0)) arrive(v);
                return;
            }
            float dx = g.nx[b] - x[v], dy = g.ny[b] - y[v], d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > 0.01f) head[v] = (float) Math.atan2(dy, dx);
            if (d <= left) {
                x[v] = g.nx[b];
                y[v] = g.ny[b];
                ri[v]++;
                seg[v] = 0;
                left -= d;
            } else {
                x[v] += dx / d * left;
                y[v] += dy / d * left;
                left = 0;
            }
        }
        spd[v] = w.rule("transitSpeed", TRANSIT_SPEED);
        stuck[v] = wait[v] = ghost[v] = 0;
        fuel[v] = Math.max(0, fuel[v] - (w.rule("transitSpeed", TRANSIT_SPEED) * dt - left) / 400000f);
        if (rt == null || ri[v] >= rt.length - 1) {
            w.act.touchPx(x[v], y[v]);
            arrive(v);
        }
    }

    private boolean throughBarricade(int v) {
        int[] rt = route[v];
        if (rt == null) return false;
        RoadGraph g = w.r.roads;
        for (int k = 0; k + 1 < rt.length; k++) {
            int e = g.edgeBetween(rt[k], rt[k + 1]);
            if (e >= 0 && g.epen[e] >= 1e6f) return true;
        }
        return false;
    }

    private static float angle(float a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    /** The speed allowed by whoever is in front. */
    private float traffic(int v, float fx, float fy, Content.VType t) {
        float limit = Float.MAX_VALUE;
        int cx = (int) (x[v] / GC), cy = (int) (y[v] / GC);
        for (int yy = cy - 1; yy <= cy + 1; yy++)
            for (int xx = cx - 1; xx <= cx + 1; xx++) {
                int c = gcell(xx, yy);
                for (int j = gStart[c]; j < gEnd[c]; j++) {
                    int o = gList[j];
                    if (o == v) continue;
                    float dx = x[o] - x[v], dy = y[o] - y[v];
                    float along = dx * fx + dy * fy, lat = Math.abs(dx * fy - dy * fx);
                    float gap = (t.length + type(o).length) * 0.5f + 6;
                    // exactly alongside (started from the same place): the later one waits
                    if (along == 0 && o < v) along = 0.01f;
                    if (along > 0 && along < gap + 30 && lat < Math.max(11, (t.width + type(o).width) * 0.5f - 1)) {
                        float l = Math.max(0, (along - gap) * 3);
                        if (state[o] == V_DRIVE) l = Math.max(l, Math.min(spd[o], l + spd[o]));
                        limit = Math.min(limit, l);
                    }
                }
            }
        return limit;
    }

    /** Running the dead down, or being stopped by them. Returns the speed they allow. */
    private float runDown(int v, float fx, float fy, Content.VType t, float dt) {
        Ents e = w.e;
        Spatial sp = w.sp;
        float frontX = x[v] + fx * t.length * 0.5f, frontY = y[v] + fy * t.length * 0.5f;
        float limit = Float.MAX_VALUE;
        int ccx = sp.cellX(frontX), ccy = sp.cellY(frontY);
        int around = 0;
        for (int yy = ccy - 1; yy <= ccy + 1; yy++)
            for (int xx = ccx - 1; xx <= ccx + 1; xx++) {
                if (xx < 0 || yy < 0 || xx >= sp.cw || yy >= sp.ch) continue;
                int c = sp.cell(xx, yy);
                for (int j = sp.zStart[c]; j < sp.zEnd[c]; j++) {
                    int z = sp.zList[j];
                    if (e.loc[z] != Ents.FREE) continue;
                    float dx = e.x[z] - frontX, dy = e.y[z] - frontY;
                    float d2 = dx * dx + dy * dy;
                    float cdx = e.x[z] - x[v], cdy = e.y[z] - y[v];
                    if (cdx * cdx + cdy * cdy < 26 * 26) around++;
                    if (d2 > t.width * t.width * 0.5f) continue;
                    if (spd[v] > 40) {
                        w.combat.killZombie(z, -1, t.faction.equals("army") ? Ents.SOLDIER : Ents.COP, Combat.CAUSE_VEHICLE);
                        kills[v]++;
                        hp[v] -= 5 * (1 - t.armor);
                        spd[v] *= 0.9f;
                    } else {
                        e.vx[z] += fy * 30;
                        e.vy[z] -= fx * 30;
                        limit = Math.min(limit, 20);
                    }
                }
            }
        int mob = t.armor >= 0.5f ? 14 : 7;
        if (around >= mob) {
            mobT[v] += dt;
            limit = Math.min(limit, t.tracked ? 25 : 6);
        } else mobT[v] = Math.max(0, mobT[v] - dt);
        if (hp[v] <= 0 && state[v] != V_WRECK) wreck(v);
        return limit;
    }

    private void mobCheck(int v, float dt) {
        if (flying(v)) return;
        int around = w.sp.zombiesNear(w.e, x[v], y[v], 26, 20);
        int mob = type(v).armor >= 0.5f ? 14 : 7;
        if (around >= mob) mobT[v] += dt;
        else mobT[v] = Math.max(0, mobT[v] - dt);
    }

    private void arrive(int v) {
        spd[v] = 0;
        route[v] = null;
        siren[v] = false;
        timer[v] = 0;
        if (returning[v]) {
            state[v] = V_PARKING;
            // home, or the nearest free place to it at the kerb when another vehicle is standing there
            slotX[v] = homeX[v];
            slotY[v] = homeY[v];
            slotH[v] = head[v];
            if (onRoad(v) && !freeAt(v, homeX[v], homeY[v], head[v], 1)) {
                int n = w.r.roads.nearestNode(homeX[v], homeY[v], 300);
                if (n < 0 || !findSlot(v, n, homeX[v], homeY[v], 400)) {
                    slotX[v] = homeX[v];
                    slotY[v] = homeY[v];
                    slotH[v] = head[v];
                }
            }
            return;
        }
        if (role[v] == R_SUPPLY) {
            if (cargo[v] > 0 && target[v] >= 0) {
                w.strat.deliverAmmo(target[v], cargo[v]);
                cargo[v] = 0;
                goHome(v);
                return;
            }
        }
        state[v] = V_ARRIVED;
        // a place of its own at the kerb near where the route ended, so cars arriving together don't pile up
        if (onRoad(v)) {
            int n = w.r.roads.nearestNode(x[v], y[v], 200);
            if (n >= 0 && findSlot(v, n, x[v], y[v], 260)) state[v] = V_PULLIN;
        }
    }

    /** The last few metres to its place at the kerb; then it's on scene. */
    private void pullIn(int v, float dt) {
        timer[v] += dt;
        if (moveToSlot(v, dt) || timer[v] > 15) {
            state[v] = V_ARRIVED;
            spd[v] = 0;
            timer[v] = 0;
        }
    }

    /** Creeps towards (slotX, slotY), turning to slotH at the end; true once there. */
    private boolean moveToSlot(int v, float dt) {
        float dx = slotX[v] - x[v], dy = slotY[v] - y[v], d = (float) Math.sqrt(dx * dx + dy * dy);
        if (d < 1.5f) {
            // there: the last of the turn into line, at the same rate as on the way in and only if there's room to
            // swing (snapping straight to slotH swept the footprint through whoever had pulled up alongside)
            float turn = angle(slotH[v] - head[v]);
            boolean last = Math.abs(turn) <= 3 * dt;
            float nh = last ? slotH[v] : head[v] + Math.signum(turn) * 3 * dt;
            spd[v] = 0;
            if (!roomFor(v, slotX[v], slotY[v], nh)) {
                wait[v] += dt;
                // no room to finish the turn: it stays as it is
                if (wait[v] > 3) {
                    wait[v] = 0;
                    return true;
                }
                return false;
            }
            wait[v] = 0;
            x[v] = slotX[v];
            y[v] = slotY[v];
            head[v] = nh;
            return last;
        }
        float s = Math.min(36, d * 2.5f + 4) * dt;
        if (s > d) s = d;
        float nx = x[v] + dx / d * s, ny = y[v] + dy / d * s;
        // facing the way it moves, and the way it will be parked over the last few metres
        float hh = d > 14 ? (float) Math.atan2(dy, dx) : slotH[v];
        float nh = head[v] + Math.max(-3 * dt, Math.min(3 * dt, angle(hh - head[v])));
        if (!roomFor(v, nx, ny, nh)) {
            spd[v] = 0;
            wait[v] += dt;
            // its way in is blocked: it stays where it is
            if (wait[v] > 3) {
                wait[v] = 0;
                return true;
            }
            return false;
        }
        wait[v] = 0;
        x[v] = nx;
        y[v] = ny;
        head[v] = nh;
        spd[v] = s / dt;
        return false;
    }

    // ------------------------------------------------------------------ room on the road

    /** Would v at (px, py) facing h stay clear of every other road vehicle (or at least not get closer to one it's touching)? */
    private boolean roomFor(int v, float px, float py, float h) {
        return blocker(v, px, py, h, head[v]) < 0;
    }

    /** The road vehicle v (now facing oldH) would run into at (px, py) facing h, or -1. */
    private int blocker(int v, float px, float py, float h, float oldH) {
        Content.VType t = type(v);
        float hl = t.length * 0.5f, hw = t.width * 0.5f;
        int cx = (int) (px / GC), cy = (int) (py / GC);
        for (int yy = cy - 1; yy <= cy + 1; yy++)
            for (int xx = cx - 1; xx <= cx + 1; xx++) {
                int c = gcell(xx, yy);
                for (int j = gStart[c]; j < gEnd[c]; j++) {
                    int o = gList[j];
                    if (o == v || !onRoad(o)) continue;
                    Content.VType to = type(o);
                    if (!boxesOverlap(px, py, h, hl, hw, x[o], y[o], head[o], to.length * 0.5f, to.width * 0.5f)) continue;
                    // already overlapping (put down on top of each other): moving apart is allowed, closer isn't
                    float d0 = (x[o] - x[v]) * (x[o] - x[v]) + (y[o] - y[v]) * (y[o] - y[v]);
                    float d1 = (x[o] - px) * (x[o] - px) + (y[o] - py) * (y[o] - py);
                    if (d1 > d0 && boxesOverlap(x[v], y[v], oldH, hl - 1, hw - 1, x[o], y[o], head[o], to.length * 0.5f - 1, to.width * 0.5f - 1)) continue;
                    return o;
                }
            }
        return -1;
    }

    /** Is there room for v at (px, py) facing h, with a margin, clear of every road vehicle and every place being pulled in to? */
    private boolean freeAt(int v, float px, float py, float h, float margin) {
        Content.VType t = type(v);
        float hl = t.length * 0.5f + margin, hw = t.width * 0.5f + margin;
        for (int o = 0; o < top; o++) {
            if (o == v || !onRoad(o)) continue;
            Content.VType to = type(o);
            float reach = hl + hw + to.length;
            float dx = x[o] - px, dy = y[o] - py;
            if (dx * dx + dy * dy < reach * reach
                    && boxesOverlap(px, py, h, hl, hw, x[o], y[o], head[o], to.length * 0.5f, to.width * 0.5f)) return false;
            if (state[o] == V_PULLIN || state[o] == V_PARKING) {
                dx = slotX[o] - px;
                dy = slotY[o] - py;
                if (dx * dx + dy * dy < reach * reach
                        && boxesOverlap(px, py, h, hl, hw, slotX[o], slotY[o], slotH[o], to.length * 0.5f, to.width * 0.5f)) return false;
            }
        }
        return true;
    }

    /** Can a vehicle stand here (road, pavement or open ground under its whole footprint)? */
    private boolean standable(float px, float py, float h, float hl, float hw) {
        float c = (float) Math.cos(h), sn = (float) Math.sin(h);
        for (int k = 0; k < 5; k++) {
            float a = k == 0 ? 0 : (k & 1) == 0 ? hl : -hl, b = k == 0 ? 0 : k < 3 ? hw : -hw;
            float qx = px + c * a - sn * b, qy = py + sn * a + c * b;
            int tt = w.r.tileAtPx(qx, qy);
            if (!Tile.walkable(tt) && !Tile.roadish(tt)) return false;
        }
        return true;
    }

    /**
     * Finds v a free place at the kerb along the roads out of junction n (or the junctions next to it), nearest to
     * (px, py) and within maxDist of it: half on the pavement, facing the way its side of the road runs, clear of the
     * junction. Sets slotX, slotY, slotH; false if there's none.
     */
    private boolean findSlot(int v, int n, float px, float py, float maxDist) {
        return findSlot(v, n, px, py, maxDist, 1);
    }

    /**
     * As above, looking along the roads out of the junctions up to `rings` junctions away from n, nearest ring first:
     * the nearest free place in the first ring that has one.
     */
    private boolean findSlot(int v, int n, float px, float py, float maxDist, int rings) {
        RoadGraph g = w.r.roads;
        Content.VType t = type(v);
        float hl = t.length * 0.5f, hw = t.width * 0.5f;
        float best = maxDist * maxDist;
        boolean found = false;
        java.util.ArrayList<Integer> ring = new java.util.ArrayList<Integer>();
        java.util.HashSet<Integer> seen = new java.util.HashSet<Integer>();
        ring.add(n);
        seen.add(n);
        for (int r = 0; r <= rings && !found && !ring.isEmpty(); r++) {
            if (r > 0) {
                // the next ring: the junctions one road further out (along live roads past the first ring)
                java.util.ArrayList<Integer> next = new java.util.ArrayList<Integer>();
                for (int m : ring)
                    for (int k = g.adjStart[m]; k < g.adjStart[m + 1]; k++) {
                        int e = g.adjEdge[k];
                        if (r > 1 && !g.ealive[e]) continue;
                        int o = g.other(e, m);
                        if (seen.add(o)) next.add(o);
                    }
                ring = next;
            }
            for (int q = 0; q < ring.size(); q++) {
                int m = ring.get(q);
                float maxHalf = 0;
                for (int k = g.adjStart[m]; k < g.adjStart[m + 1]; k++) maxHalf = Math.max(maxHalf, g.ehalf[g.adjEdge[k]]);
                for (int k = g.adjStart[m]; k < g.adjStart[m + 1]; k++) {
                    int e = g.adjEdge[k];
                    if (!g.ealive[e]) continue;
                    int o = g.other(e, m);
                    float ux = g.nx[o] - g.nx[m], uy = g.ny[o] - g.ny[m], len = (float) Math.sqrt(ux * ux + uy * uy);
                    if (len < 1) continue;
                    ux /= len;
                    uy /= len;
                    float kerb = g.ehalf[e] + hw - 4;
                    float d0 = maxHalf + hl + 4, d1 = Math.min(len * 0.5f, 220);
                    for (float d = d0; d <= d1; d += t.length + 5)
                        for (int side = -1; side <= 1; side += 2) {
                            float sx = g.nx[m] + ux * d - uy * kerb * side, sy = g.ny[m] + uy * d + ux * kerb * side;
                            float dx = sx - px, dy = sy - py, score = dx * dx + dy * dy;
                            if (score >= best) continue;
                            float h = side > 0 ? (float) Math.atan2(uy, ux) : (float) Math.atan2(-uy, -ux);
                            if (!standable(sx, sy, h, hl, hw) || !freeAt(v, sx, sy, h, 2)) continue;
                            best = score;
                            found = true;
                            slotX[v] = sx;
                            slotY[v] = sy;
                            slotH[v] = h;
                        }
                }
            }
        }
        return found;
    }

    /** The last few metres from the road into a parking place. */
    private void park(int v, float dt) {
        float dx = slotX[v] - x[v], dy = slotY[v] - y[v], d = (float) Math.sqrt(dx * dx + dy * dy);
        returning[v] = false;
        if (d < 3) {
            state[v] = V_PARKED;
            spd[v] = 0;
            if (role[v] == R_SUPPLY) state[v] = V_NONE; // back in the depot
            else if (abandoned(v) || ("fire_engine".equals(type(v).id) && squad[v] < 0)) toPool(v); // back in the garage
            return;
        }
        float s = Math.min(40, d * 2);
        float nx = x[v] + dx / d * s * dt, ny = y[v] + dy / d * s * dt, nh = (float) Math.atan2(dy, dx);
        timer[v] += dt;
        if (onRoad(v) && !roomFor(v, nx, ny, nh)) {
            wait[v] += dt;
            // no way in: parked where it is
            if (wait[v] > 4) {
                wait[v] = 0;
                slotX[v] = x[v];
                slotY[v] = y[v];
            }
            return;
        }
        wait[v] = 0;
        x[v] = nx;
        y[v] = ny;
        head[v] = nh;
    }

    private void fly(int v, float dt) {
        Content.VType t = type(v);
        timer[v] += dt;
        alt[v] = Math.min(1, alt[v] + dt * 0.5f);
        float dx = destX[v] - x[v], dy = destY[v] - y[v], d = (float) Math.sqrt(dx * dx + dy * dy) + 0.001f;
        if (state[v] == V_STRIKE) {
            // a straight bombing run: release over the target, then fly on out
            float s = t.speed * dt;
            x[v] += (float) Math.cos(head[v]) * s;
            y[v] += (float) Math.sin(head[v]) * s;
            if (cargo[v] > 0 && d < t.speed * dt * 1.5f + 10) {
                w.combat.blast(destX[v], destY[v], t.weapon != null ? t.weapon.blast : 120, t.weapon != null ? t.weapon.blastDmg : 900, -1, Ents.SOLDIER);
                cargo[v] = 0;
                w.stats.airStrikes++;
            }
            float m = w.r.px();
            if (x[v] < -400 || y[v] < -400 || x[v] > m + 400 || y[v] > m + 400) state[v] = V_NONE;
            return;
        }
        if (state[v] == V_ORBIT) {
            float orbitR = 150;
            orbitA[v] += t.speed * 0.5f / orbitR * dt;
            float ox = destX[v] + (float) Math.cos(orbitA[v]) * orbitR, oy = destY[v] + (float) Math.sin(orbitA[v]) * orbitR;
            head[v] = (float) Math.atan2(oy - y[v], ox - x[v]);
            x[v] += (ox - x[v]) * Math.min(1, dt * 2);
            y[v] += (oy - y[v]) * Math.min(1, dt * 2);
            if (timer[v] > 75 || (t.weapon != null && ammo[v] <= 0) || fuel[v] <= 0.05f) goHome(v);
            fuel[v] -= dt / 600f;
            return;
        }
        head[v] = (float) Math.atan2(dy, dx);
        float s = Math.min(t.speed, d * 1.5f + 20) * dt;
        x[v] += dx / d * s;
        y[v] += dy / d * s;
        fuel[v] -= dt / 900f;
        if (d < 30) {
            if (state[v] == V_RTB) {
                state[v] = V_PARKED;
                alt[v] = 0;
                returning[v] = false;
                timer[v] = 0;
                fuel[v] = 1;
                if (t.weapon != null) ammo[v] = t.weapon.ammo;
            } else if (role[v] == R_TRANSPORT || t.seats > 0 && t.weapon == null) {
                state[v] = V_ARRIVED;
                alt[v] = 0;
            } else {
                state[v] = V_ORBIT;
                orbitA[v] = (float) Math.atan2(y[v] - destY[v], x[v] - destX[v]);
                timer[v] = 0;
            }
        }
    }

    /** Mounted guns: humvee machine guns, APC cannons, tank guns, door gunners. */
    private void weapons(int v, float dt) {
        Content.VType t = type(v);
        if (t.weapon == null || state[v] == V_STRIKE) return;
        if (state[v] == V_PARKED && !t.flies && squad[v] < 0 && t.seats > 0) return;
        if (t.flies && state[v] != V_ORBIT && state[v] != V_FLY) return;
        if (t.seats > 0 && crewN[v] == 0 && !t.flies) return;
        wpnT[v] -= dt;
        wpn2T[v] -= dt;
        if (wpnT[v] <= 0 && ammo[v] > 0) fire(v, t.weapon, true);
        if (t.weapon2 != null && wpn2T[v] <= 0) fire(v, t.weapon2, false);
    }

    private void fire(int v, Content.Weapon wp, boolean main) {
        Ents e = w.e;
        float range = wp.range;
        int z = w.sp.nearestZombie(e, x[v], y[v], range);
        float pause = 1f / wp.rof;
        if (z < 0 || (!type(v).flies && !w.r.lineOfSight(x[v], y[v], e.x[z], e.y[z]))) {
            if (main) wpnT[v] = 0.4f;
            else wpn2T[v] = 0.4f;
            return;
        }
        // a big gun waits for a pack worth the shell
        if (wp.blast > 30 && w.sp.zombiesNear(e, e.x[z], e.y[z], wp.blast * 0.6f, 4) < 4 && w.sp.zombiesNear(e, x[v], y[v], 60, 1) == 0) {
            wpnT[v] = 0.5f;
            return;
        }
        if (wp.blast > 0 && w.sp.livingNear(e, e.x[z], e.y[z], wp.blast + 8, 1) > 0) {
            if (main) wpnT[v] = 0.5f;
            else wpn2T[v] = 0.5f;
            return;
        }
        if (main) {
            wpnT[v] = pause * (0.85f + w.rng.nextFloat() * 0.3f);
            ammo[v]--;
        } else wpn2T[v] = pause * (0.85f + w.rng.nextFloat() * 0.3f);
        int faction = type(v).faction.equals("army") ? Ents.SOLDIER : Ents.COP;
        float tx = e.x[z], ty = e.y[z];
        w.fx.add(Effects.FLASH, x[v], y[v], (float) Math.atan2(ty - y[v], tx - x[v]), 0, 0.06f);
        w.fx.sound(wp.sound, x[v], y[v]);
        w.fx.noise(x[v], y[v], wp.blast > 0 ? 44 : 34, 6);
        w.stats.shots++;
        if (wp.blast > 0) {
            w.fx.add(Effects.TRACER, x[v], y[v], tx, ty, 0.2f);
            int before = w.stats.zombieKills;
            w.combat.blast(tx, ty, wp.blast, wp.blastDmg, -1, faction);
            kills[v] += (short) (w.stats.zombieKills - before);
            return;
        }
        float dist = (float) Math.sqrt((tx - x[v]) * (tx - x[v]) + (ty - y[v]) * (ty - y[v]));
        float near = 1 - dist / range;
        boolean hit = w.rng.chance(0.75f * (0.6f + 0.4f * near));
        w.fx.add(Effects.TRACER, x[v], y[v], tx + (hit ? 0 : (w.rng.nextFloat() - 0.5f) * 20), ty + (hit ? 0 : (w.rng.nextFloat() - 0.5f) * 20), 0.08f);
        if (!hit) return;
        if (w.rng.chance(wp.head * (0.5f + 0.5f * near)) && (e.flags[z] & Ents.F_HELMET) == 0) {
            w.combat.killZombie(z, -1, faction, Combat.CAUSE_GUN);
            kills[v]++;
        } else {
            e.hp[z] -= wp.dmg * 0.25f;
            e.flags[z] &= ~Ents.F_HELMET;
            if (e.hp[z] <= 0) {
                w.combat.killZombie(z, -1, faction, Combat.CAUSE_GUN);
                kills[v]++;
            }
        }
    }

    /** Sends a strike jet in from the region's edge to bomb (tx, ty). */
    public int strike(float tx, float ty) {
        Content.VType jt = w.c.vtype("jet");
        if (jt == null) return -1;
        float m = w.r.px();
        // from the air base if it's still working, else in from the nearest edge
        float sx = tx < m / 2 ? -300 : m + 300, sy = ty + (w.rng.nextFloat() - 0.5f) * 800;
        float[] ab = w.navy.airBase();
        if (ab != null) {
            sx = ab[0];
            sy = ab[1];
        }
        int v = add(jt, sx, sy, 0, -1);
        role[v] = R_STRIKE;
        state[v] = V_STRIKE;
        destX[v] = tx;
        destY[v] = ty;
        head[v] = (float) Math.atan2(ty - sy, tx - sx);
        cargo[v] = 1;
        alt[v] = 1;
        w.fx.sound("jet", tx, ty);
        return v;
    }

    /** A road vehicle (not an aircraft, not a ship at sea, not off the map). */
    private boolean onRoad(int v) {
        if (state[v] == V_NONE || flying(v)) return false;
        Content.VType t = type(v);
        return !t.flies && t.length < 80;
    }

    /** Do the footprints of two road vehicles (rectangles length x width round their centres) overlap, with a margin? */
    boolean overlaps(int a, int b, float margin) {
        return boxesOverlap(x[a], y[a], head[a], type(a).length * 0.5f + margin, type(a).width * 0.5f + margin,
                x[b], y[b], head[b], type(b).length * 0.5f + margin, type(b).width * 0.5f + margin);
    }

    /** Separating axes for two rectangles given by centre, heading and half sizes. */
    static boolean boxesOverlap(float ax, float ay, float ah, float al, float aw, float bx, float by, float bh, float bl, float bw) {
        float dx = bx - ax, dy = by - ay;
        float r = al + aw + bl + bw;
        if (dx * dx + dy * dy > r * r) return false;
        float ca = (float) Math.cos(ah), sa = (float) Math.sin(ah), cb = (float) Math.cos(bh), sb = (float) Math.sin(bh);
        float[][] axes = {{ca, sa}, {-sa, ca}, {cb, sb}, {-sb, cb}};
        for (float[] u : axes) {
            float d = Math.abs(dx * u[0] + dy * u[1]);
            float ra = al * Math.abs(ca * u[0] + sa * u[1]) + aw * Math.abs(-sa * u[0] + ca * u[1]);
            float rb = bl * Math.abs(cb * u[0] + sb * u[1]) + bw * Math.abs(-sb * u[0] + cb * u[1]);
            if (d >= ra + rb) return false;
        }
        return true;
    }

    /** How many pairs of road vehicles overlap now (the footprints shrunk by a pixel, so touching doesn't count). */
    public int overlapPairs() {
        int n = 0;
        for (int k = 0; k < gTouchedN; k++) {
            int c = gTouched[k];
            for (int j = gStart[c]; j < gEnd[c]; j++) {
                int a = gList[j];
                if (!onRoad(a)) continue;
                int cx = cellX(a), cy = cellY(a);
                for (int yy = cy - 1; yy <= cy + 1; yy++)
                    for (int xx = cx - 1; xx <= cx + 1; xx++) {
                        int c2 = gcell(xx, yy);
                        for (int i = gStart[c2]; i < gEnd[c2]; i++) {
                            int b = gList[i];
                            if (b <= a || !onRoad(b)) continue;
                            if (overlaps(a, b, -1)) n++;
                        }
                    }
            }
        }
        return n;
    }

    public int countActive(String faction) {
        int n = 0;
        for (int v = 0; v < top; v++) if (state[v] != V_NONE && state[v] != V_WRECK && type(v).faction.equals(faction)) n++;
        return n;
    }
}
