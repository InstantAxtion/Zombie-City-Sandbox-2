package com.instantaxtion.zcs2;

import java.util.Arrays;

/**
 * Every vehicle and aircraft. Ground vehicles drive on the road graph (routes are junction lists, followed by
 * steering at a point a little ahead in their lane), queue behind each other, run the dead down, and get mobbed
 * when they stop among them. Aircraft fly straight, orbit over a fight, and come home to rearm.
 */
public final class Vehicles {
    public static final byte V_NONE = 0, V_PARKED = 1, V_DRIVE = 2, V_ARRIVED = 3, V_WRECK = 4, V_FLY = 5, V_ORBIT = 6,
            V_RTB = 7, V_STRIKE = 8, V_PARKING = 9;
    public static final String[] STATE_NAMES = {"", "Parked", "Driving", "On scene", "Wrecked", "Flying", "Over the fight",
            "Returning", "Strike run", "Parking"};
    public static final byte R_SQUAD = 0, R_PATROL = 1, R_SUPPLY = 2, R_AIR = 3, R_STRIKE = 4, R_TRANSPORT = 5;

    private final World w;
    public int top;
    public float[] x = new float[64], y = new float[64], head = new float[64], spd = new float[64], hp = new float[64],
            destX = new float[64], destY = new float[64], homeX = new float[64], homeY = new float[64], fuel = new float[64],
            stuck = new float[64], wpnT = new float[64], wpn2T = new float[64], timer = new float[64], mobT = new float[64],
            seg = new float[64], orbitA = new float[64], ghost = new float[64], alt = new float[64];
    public short[] type = new short[64], kills = new short[64];
    public byte[] state = new byte[64], role = new byte[64];
    public int[] squad = new int[64], home = new int[64], ri = new int[64], crewN = new int[64], ammo = new int[64],
            cargo = new int[64], uid = new int[64], target = new int[64];
    public int[][] route = new int[64][], crew = new int[64][];
    public boolean[] siren = new boolean[64], returning = new boolean[64];
    private int nextUid = 1;

    // a grid for "which vehicle is near here"
    private static final int GC = 128;
    private int gw;
    private int[] gStart, gList = new int[64], gCnt;

    Vehicles(World w) {
        this.w = w;
        gw = w.r.px() / GC + 1;
        gStart = new int[gw * gw + 1];
        gCnt = new int[gw * gw];
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
        cargo[v] = 0;
        target[v] = -1;
        siren[v] = false;
        returning[v] = false;
        uid[v] = nextUid++;
        return v;
    }

    private void grow(int n) {
        x = Arrays.copyOf(x, n); y = Arrays.copyOf(y, n); head = Arrays.copyOf(head, n); spd = Arrays.copyOf(spd, n);
        hp = Arrays.copyOf(hp, n); destX = Arrays.copyOf(destX, n); destY = Arrays.copyOf(destY, n);
        homeX = Arrays.copyOf(homeX, n); homeY = Arrays.copyOf(homeY, n); fuel = Arrays.copyOf(fuel, n);
        stuck = Arrays.copyOf(stuck, n); wpnT = Arrays.copyOf(wpnT, n); wpn2T = Arrays.copyOf(wpn2T, n);
        timer = Arrays.copyOf(timer, n); mobT = Arrays.copyOf(mobT, n); seg = Arrays.copyOf(seg, n);
        orbitA = Arrays.copyOf(orbitA, n); ghost = Arrays.copyOf(ghost, n); alt = Arrays.copyOf(alt, n);
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
        int cx = (int) (px / GC), cy = (int) (py / GC);
        int best = -1;
        float bd = rad * rad;
        for (int yy = cy - 1; yy <= cy + 1; yy++)
            for (int xx = cx - 1; xx <= cx + 1; xx++) {
                if (xx < 0 || yy < 0 || xx >= gw || yy >= gw) continue;
                int c = yy * gw + xx;
                for (int j = gStart[c]; j < gStart[c + 1]; j++) {
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

    /** The squad is gone: drive home empty. */
    public void release(int v) {
        squad[v] = -1;
        if (!usable(v)) return;
        if (crewN[v] > 0) alightAll(v);
        goHome(v);
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
        for (int a = 0; a < 12; a++) {
            int n = Rng.hashInt(w.seed, uid[v] * 977L + w.tick, a, g.nodeCount);
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
                case V_DRIVE: drive(v, dt); break;
                case V_PARKING: park(v, dt); break;
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
            }
            if (state[v] != V_NONE && state[v] != V_WRECK) weapons(v, dt);
        }
        // the grid reflects where vehicles are now (a save restores it exactly by rebuilding it)
        rebuildGrid();
    }

    void rebuildGrid() {
        Arrays.fill(gCnt, 0);
        int n = 0;
        for (int v = 0; v < top; v++) {
            if (state[v] == V_NONE || flying(v)) continue;
            gCnt[cell(v)]++;
            n++;
        }
        if (gList.length < n) gList = new int[n * 2];
        gStart[0] = 0;
        for (int c = 0; c < gw * gw; c++) {
            gStart[c + 1] = gStart[c] + gCnt[c];
            gCnt[c] = gStart[c];
        }
        for (int v = 0; v < top; v++) {
            if (state[v] == V_NONE || flying(v)) continue;
            gList[gCnt[cell(v)]++] = v;
        }
    }

    private int cell(int v) {
        int cx = Math.max(0, Math.min(gw - 1, (int) (x[v] / GC))), cy = Math.max(0, Math.min(gw - 1, (int) (y[v] / GC)));
        return cy * gw + cx;
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
        float rx = -uy * lane, ry = ux * lane;
        // progress along this road
        float px = x[v] - ax, py = y[v] - ay;
        float along = px * ux + py * uy;
        if (along >= len - 6) {
            ri[v]++;
            if (ri[v] >= rt.length - 1) {
                arrive(v);
                return;
            }
            return;
        }
        // steer at a point ahead in my lane
        float look = 22 + spd[v] * 0.15f;
        float tx, ty;
        float off = Math.abs(px * uy - py * ux - lane) ;
        if (along + look < len || ri[v] + 2 >= rt.length) {
            float s = Math.min(len, Math.max(0, along) + look);
            tx = ax + ux * s + rx;
            ty = ay + uy * s + ry;
        } else {
            int c2 = rt[ri[v] + 2];
            float cx2 = g.nx[c2] - bx, cy2 = g.ny[c2] - by, l2 = (float) Math.sqrt(cx2 * cx2 + cy2 * cy2) + 0.001f;
            float over = along + look - len;
            int e2 = g.edgeBetween(b, c2);
            float lane2 = e2 >= 0 ? Math.min(g.ehalf[e2] * 0.45f, 14) : 8;
            tx = bx + cx2 / l2 * over - cy2 / l2 * lane2;
            ty = by + cy2 / l2 * over + cx2 / l2 * lane2;
        }
        if (off > 60) {
            tx = ax + rx;
            ty = ay + ry;
        }
        float hx = tx - x[v], hy = ty - y[v];
        float want = (float) Math.atan2(hy, hx);
        float diff = angle(want - head[v]);
        float turnRate = 2.6f + 20f / (spd[v] + 10);
        head[v] += Math.max(-turnRate * dt, Math.min(turnRate * dt, diff));
        // speed: road kind, corners, traffic ahead, the dead in the way
        float limit = t.speed * (g.ekind[e >= 0 ? e : 0] == RoadGraph.HIGHWAY ? 1f : g.ekind[e >= 0 ? e : 0] == RoadGraph.AVENUE ? 0.72f : 0.58f);
        if (siren[v]) limit *= 1.15f;
        if (Math.abs(diff) > 0.5f) limit *= 0.45f;
        if (ri[v] + 2 < rt.length && len - along < 50) {
            int c2 = rt[ri[v] + 2];
            float cx2 = g.nx[c2] - bx, cy2 = g.ny[c2] - by, l2 = (float) Math.sqrt(cx2 * cx2 + cy2 * cy2) + 0.001f;
            float dot = (cx2 * ux + cy2 * uy) / l2;
            if (dot < 0.7f) limit *= 0.5f;
        }
        if (ri[v] + 2 >= rt.length && len - along < 60) limit *= 0.5f;
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
        x[v] = nx;
        y[v] = ny;
        fuel[v] -= spd[v] * dt / 400000f;
        if (fuel[v] < 0) fuel[v] = 0;
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
                if (xx < 0 || yy < 0 || xx >= gw || yy >= gw) continue;
                int c = yy * gw + xx;
                for (int j = gStart[c]; j < gStart[c + 1]; j++) {
                    int o = gList[j];
                    if (o == v) continue;
                    float dx = x[o] - x[v], dy = y[o] - y[v];
                    float along = dx * fx + dy * fy, lat = Math.abs(dx * fy - dy * fx);
                    float gap = (t.length + type(o).length) * 0.5f + 6;
                    if (along > 0 && along < gap + 30 && lat < 11) {
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
        int c0 = sp.cellOf(frontX, frontY);
        int ccx = c0 % sp.cw, ccy = c0 / sp.cw;
        int around = 0;
        for (int yy = ccy - 1; yy <= ccy + 1; yy++)
            for (int xx = ccx - 1; xx <= ccx + 1; xx++) {
                if (xx < 0 || yy < 0 || xx >= sp.cw || yy >= sp.ch) continue;
                int c = yy * sp.cw + xx;
                for (int j = sp.zStart[c]; j < sp.zStart[c + 1]; j++) {
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
    }

    /** The last few metres from the road into a parking place. */
    private void park(int v, float dt) {
        float dx = homeX[v] - x[v], dy = homeY[v] - y[v], d = (float) Math.sqrt(dx * dx + dy * dy);
        returning[v] = false;
        if (d < 3) {
            state[v] = V_PARKED;
            spd[v] = 0;
            if (role[v] == R_SUPPLY) state[v] = V_NONE; // back in the depot
            return;
        }
        float s = Math.min(40, d * 2);
        x[v] += dx / d * s * dt;
        y[v] += dy / d * s * dt;
        head[v] = (float) Math.atan2(dy, dx);
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
        // come in from the nearest edge
        float sx = tx < m / 2 ? -300 : m + 300, sy = ty + (w.rng.nextFloat() - 0.5f) * 800;
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

    public int countActive(String faction) {
        int n = 0;
        for (int v = 0; v < top; v++) if (state[v] != V_NONE && state[v] != V_WRECK && type(v).faction.equals(faction)) n++;
        return n;
    }
}
