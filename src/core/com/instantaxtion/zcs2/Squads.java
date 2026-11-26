package com.instantaxtion.zcs2;

import java.util.ArrayList;
import java.util.List;

/**
 * Squads: a team of police or soldiers with one order, one place to be, and (usually) one vehicle.
 * The squad decides where its members go and when to mount up, fall back or come home to restock;
 * each member then decides for itself how to get there and what to shoot (ArmedAI).
 */
public final class Squads {
    public static final int O_IDLE = 0, O_MOVE = 1, O_HOLD = 2, O_RESPOND = 3, O_CORDON = 4, O_RETURN = 5, O_PATROL = 6,
            O_GUARD = 7, O_DEMOLISH = 8;
    public static final String[] ORDER_NAMES = {"Standing by", "Moving", "Holding", "Responding", "On the cordon",
            "Returning to restock", "On patrol", "Guarding", "Demolition"};
    public static final int M_FOOT = 0, M_BOARD = 1, M_RIDE = 2;

    public static final class Squad {
        public int id, faction, n, vehicle = -1, home = -1, order, mode, incident = -1, task = -1;
        public int[] mem = new int[12];
        public float gx, gy, faceX, faceY, timer, boardT, fallT, replanT;
        public float[] path;
        public int pathI;
        public String name, label;
        public int kills, losses;
        public boolean alive = true, outside, player;
        public float hold = 40; // spread when holding

        public int leader(Ents e) {
            for (int k = 0; k < n; k++) if (e.loc[mem[k]] == Ents.FREE) return mem[k];
            return n > 0 ? mem[0] : -1;
        }
    }

    private final World w;
    public final List<Squad> list = new ArrayList<Squad>();
    private final float[] tmp = new float[4];

    Squads(World w) {
        this.w = w;
    }

    public Squad get(int id) {
        return list.get(id);
    }

    public Squad create(int faction, int home, String label) {
        Squad s = new Squad();
        s.id = list.size();
        s.faction = faction;
        s.home = home;
        s.label = label;
        s.name = Names.callsign(faction, s.id);
        if (home >= 0) {
            Region.Facility f = w.r.facilities.get(home);
            s.gx = f.x;
            s.gy = f.y;
        }
        list.add(s);
        return s;
    }

    public void add(Squad s, int ent) {
        if (s.n == s.mem.length) s.mem = java.util.Arrays.copyOf(s.mem, s.n * 2);
        s.mem[s.n++] = ent;
        w.e.squad[ent] = s.id;
    }

    void removeMember(int sid, int ent) {
        Squad s = list.get(sid);
        for (int k = 0; k < s.n; k++)
            if (s.mem[k] == ent) {
                s.mem[k] = s.mem[--s.n];
                break;
            }
        w.e.squad[ent] = -1;
        if (s.n == 0) disband(s);
    }

    private void disband(Squad s) {
        s.alive = false;
        if (s.vehicle >= 0) {
            w.veh.release(s.vehicle);
            s.vehicle = -1;
        }
        w.disp.squadGone(s);
    }

    // ------------------------------------------------------------------ orders

    /** Gives a squad an order to be somewhere. */
    public void order(Squad s, int order, float x, float y) {
        if (!s.alive) return;
        s.order = order;
        s.gx = x;
        s.gy = y;
        s.path = null;
        s.pathI = 0;
        s.timer = 0;
        s.fallT = 0;
        Ents e = w.e;
        int lead = s.leader(e);
        if (lead < 0) return;
        float lx = e.loc[lead] == Ents.ABOARD && s.vehicle >= 0 ? w.veh.x[s.vehicle] : e.x[lead];
        float ly = e.loc[lead] == Ents.ABOARD && s.vehicle >= 0 ? w.veh.y[s.vehicle] : e.y[lead];
        float d = dist(lx, ly, x, y);
        boolean canRide = s.vehicle >= 0 && w.veh.usable(s.vehicle);
        if (canRide && (d > 450 || aboardCount(s) > 0)) {
            if (aboardCount(s) == s.n) startRide(s);
            else {
                s.mode = M_BOARD;
                s.boardT = 0;
            }
        } else {
            if (aboardCount(s) > 0) dismount(s);
            s.mode = M_FOOT;
        }
    }

    private int aboardCount(Squad s) {
        int a = 0;
        for (int k = 0; k < s.n; k++) if (w.e.loc[s.mem[k]] == Ents.ABOARD) a++;
        return a;
    }

    private void startRide(Squad s) {
        // stop short of the dead: dismount where it's still quiet
        float tx = s.gx, ty = s.gy;
        if (w.veh.driveTo(s.vehicle, tx, ty, s.order == O_RETURN || s.order == O_IDLE ? 0 : 140)) s.mode = M_RIDE;
        else {
            dismount(s);
            s.mode = M_FOOT;
        }
    }

    private void dismount(Squad s) {
        if (s.vehicle >= 0) w.veh.alightAll(s.vehicle);
    }

    private static float dist(float ax, float ay, float bx, float by) {
        float dx = ax - bx, dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // ------------------------------------------------------------------ every step

    void update() {
        Ents e = w.e;
        for (int q = 0; q < list.size(); q++) {
            Squad s = list.get(q);
            if (!s.alive) continue;
            // members who are gone: bodies, or turned
            for (int k = 0; k < s.n; ) {
                int m = s.mem[k];
                if ((e.loc[m] != Ents.FREE && e.loc[m] != Ents.ABOARD) || e.squad[m] != s.id || !e.armed(m)) {
                    s.mem[k] = s.mem[--s.n];
                    if (e.squad[m] == s.id) e.squad[m] = -1;
                } else k++;
            }
            if (s.n == 0) {
                disband(s);
                continue;
            }
            switch (s.mode) {
                case M_BOARD: boarding(s); break;
                case M_RIDE: riding(s); break;
                default: onFoot(s);
            }
        }
    }

    private void boarding(Squad s) {
        Ents e = w.e;
        int v = s.vehicle;
        if (v < 0 || !w.veh.usable(v)) {
            s.mode = M_FOOT;
            return;
        }
        s.boardT += World.DT;
        float vx = w.veh.x[v], vy = w.veh.y[v];
        boolean all = true;
        for (int k = 0; k < s.n; k++) {
            int m = s.mem[k];
            if (e.loc[m] != Ents.FREE) continue;
            all = false;
            e.gx[m] = vx;
            e.gy[m] = vy;
            e.state[m] = 1;
            if (dist(e.x[m], e.y[m], vx, vy) < 22) w.veh.board(v, m);
        }
        if (all || s.boardT > 20) {
            // anyone who didn't make it walks
            startRide(s);
        }
    }

    private void riding(Squad s) {
        Ents e = w.e;
        int v = s.vehicle;
        if (v < 0) {
            s.mode = M_FOOT;
            return;
        }
        // walkers left behind head for the goal on foot
        for (int k = 0; k < s.n; k++) {
            int m = s.mem[k];
            if (e.loc[m] == Ents.FREE) {
                e.gx[m] = s.gx;
                e.gy[m] = s.gy;
                e.state[m] = 1;
            }
        }
        if (!w.veh.usable(v) || w.veh.mobbed(v)) {
            // stopped by the dead or wrecked: everyone out and fight
            dismount(s);
            s.mode = M_FOOT;
            s.path = null;
            if (s.order == O_RETURN || s.order == O_IDLE) s.order = O_HOLD;
            s.gx = w.veh.x[v];
            s.gy = w.veh.y[v];
            return;
        }
        if (w.veh.arrived(v)) {
            if (s.order == O_PATROL) {
                w.veh.patrolOn(v);
                return;
            }
            if (s.order == O_RETURN || s.order == O_IDLE) {
                // home: park, get out, restock
                dismount(s);
                s.mode = M_FOOT;
                s.path = null;
                return;
            }
            dismount(s);
            s.mode = M_FOOT;
            s.path = null;
        }
    }

    private void onFoot(Squad s) {
        Ents e = w.e;
        int lead = s.leader(e);
        if (lead < 0 || e.loc[lead] != Ents.FREE) return;
        float lx = e.x[lead], ly = e.y[lead];
        float d = dist(lx, ly, s.gx, s.gy);
        boolean there = d < 50;
        if (!there) {
            // a path for the leader; everyone follows its waypoints
            s.replanT -= World.DT;
            if (s.path == null || s.replanT <= 0) {
                s.path = w.nav.find(lx, ly, s.gx, s.gy, 6000);
                s.pathI = 0;
                s.replanT = 6;
            }
            float wx = s.gx, wy = s.gy;
            if (s.path != null && s.pathI * 2 < s.path.length) {
                wx = s.path[s.pathI * 2];
                wy = s.path[s.pathI * 2 + 1];
                if (dist(lx, ly, wx, wy) < 24 && s.pathI * 2 + 2 < s.path.length) s.pathI++;
            }
            for (int k = 0; k < s.n; k++) {
                int m = s.mem[k];
                if (e.loc[m] != Ents.FREE) continue;
                if (m == lead) {
                    e.gx[m] = wx;
                    e.gy[m] = wy;
                } else {
                    // follow in a loose file
                    float ox = (k % 3 - 1) * 10, oy = (k / 3) * 9;
                    float fx = wx + ox, fy = wy + oy;
                    if (dist(e.x[m], e.y[m], lx, ly) > 90) {
                        fx = lx;
                        fy = ly;
                    }
                    if (!w.r.walkablePx(fx, fy)) {
                        fx = wx;
                        fy = wy;
                    }
                    e.gx[m] = fx;
                    e.gy[m] = fy;
                }
                e.state[m] = (byte) (s.order == O_RETURN || s.order == O_IDLE ? 0 : 1);
            }
            return;
        }
        // there: spread out to hold the place, facing the trouble
        if (s.order == O_MOVE) s.order = O_HOLD;
        if (s.order == O_RETURN || (s.order == O_IDLE && s.timer == 0)) arrivedHome(s);
        float fx = s.faceX, fy = s.faceY;
        if (fx == 0 && fy == 0) {
            fx = 0;
            fy = -1;
        }
        float px = -fy, py = fx;
        int slots = s.n;
        for (int k = 0; k < s.n; k++) {
            int m = s.mem[k];
            if (e.loc[m] != Ents.FREE) continue;
            float off = (k - (slots - 1) / 2f) * Math.max(8f, s.hold * 2 / Math.max(1, slots));
            float sx = s.gx + px * off - fx * ((k & 1) * 8), sy = s.gy + py * off - fy * ((k & 1) * 8);
            if (s.order == O_IDLE) {
                float ang = k * 2.4f;
                sx = s.gx + (float) Math.cos(ang) * (14 + k * 2);
                sy = s.gy + (float) Math.sin(ang) * (14 + k * 2);
            }
            if (!w.r.walkablePx(sx, sy)) {
                sx = s.gx;
                sy = s.gy;
            }
            e.gx[m] = sx;
            e.gy[m] = sy;
            e.state[m] = 0;
        }
    }

    private void arrivedHome(Squad s) {
        Ents e = w.e;
        s.order = O_IDLE;
        s.timer = 1;
        if (s.home < 0) return;
        // restock and patch up at the station / base
        for (int k = 0; k < s.n; k++) {
            int m = s.mem[k];
            Content.Unit u = w.unitOf(m);
            if (u.weapon == null) continue;
            int need = u.weapon.ammo - e.ammo[m];
            int got = w.strat.drawAmmo(s.home, need);
            e.ammo[m] = (short) (e.ammo[m] + got);
            e.hp[m] = Math.max(e.hp[m], e.maxHp[m] * 0.8f);
        }
        if (s.vehicle >= 0) w.veh.refuel(s.vehicle, s.home);
    }

    // ------------------------------------------------------------------ once a second

    void secondly() {
        Ents e = w.e;
        for (int q = 0; q < list.size(); q++) {
            Squad s = list.get(q);
            if (!s.alive || s.mode != M_FOOT || s.player) continue;
            int lead = s.leader(e);
            if (lead < 0 || e.loc[lead] != Ents.FREE) continue;
            // low on ammunition: go and get more (if a home has any)
            int ammo = 0, max = 0;
            for (int k = 0; k < s.n; k++) {
                int m = s.mem[k];
                Content.Unit u = w.unitOf(m);
                if (u.weapon == null) continue;
                ammo += e.ammo[m];
                max += u.weapon.ammo;
            }
            if (max > 0 && ammo < max * 0.18f && s.order != O_RETURN && s.order != O_IDLE && s.home >= 0) {
                w.news.radio(s.name + ": low on ammo, returning to restock", e.x[lead], e.y[lead], News.UNIT);
                goHome(s);
                continue;
            }
            // under too much pressure: fall back together, away from the dead
            if (s.order == O_HOLD || s.order == O_RESPOND || s.order == O_CORDON || s.order == O_GUARD) {
                s.fallT -= 1;
                int press = 0;
                float cx = 0, cy = 0;
                int cn = 0;
                for (int k = 0; k < s.n; k++) {
                    int m = s.mem[k];
                    if (e.loc[m] != Ents.FREE) continue;
                    int near = w.sp.zombiesNear(e, e.x[m], e.y[m], 70, 20);
                    press += near;
                    if (near > 0 && w.sp.zombieCentroid(e, e.x[m], e.y[m], 120, tmp)) {
                        cx += tmp[0];
                        cy += tmp[1];
                        cn++;
                    }
                }
                Content.Unit lu = w.unitOf(lead);
                float limit = s.n * (2.5f + lu.nerve * 3);
                if (cn > 0) {
                    cx /= cn;
                    cy /= cn;
                    float dx = s.gx - cx, dy = s.gy - cy, dl = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
                    s.faceX = -dx / dl;
                    s.faceY = -dy / dl;
                    if (press > limit && s.fallT <= 0) {
                        float nx = s.gx + dx / dl * 110, ny = s.gy + dy / dl * 110;
                        if (w.r.walkablePx(nx, ny)) {
                            s.gx = nx;
                            s.gy = ny;
                            s.path = null;
                            s.fallT = 5;
                            for (int k = 0; k < s.n; k++) e.state[s.mem[k]] = 1;
                        }
                    }
                }
            }
        }
    }

    public void goHome(Squad s) {
        if (s.home < 0) {
            order(s, O_HOLD, s.gx, s.gy);
            return;
        }
        Region.Facility f = w.r.facilities.get(s.home);
        s.incident = -1;
        s.task = -1;
        order(s, O_RETURN, f.x, f.y);
    }

    /** Free = idle at home, ready to send. */
    public boolean free(Squad s) {
        return s.alive && !s.player && (s.order == O_IDLE || s.order == O_PATROL) && s.n > 0;
    }

    /** Where the squad is now (its vehicle while riding). */
    public float[] where(Squad s) {
        Ents e = w.e;
        int lead = s.leader(e);
        if (lead < 0) return new float[]{s.gx, s.gy};
        if (e.loc[lead] == Ents.ABOARD && s.vehicle >= 0) return new float[]{w.veh.x[s.vehicle], w.veh.y[s.vehicle]};
        return new float[]{e.x[lead], e.y[lead]};
    }

    public int count(int faction) {
        int n = 0;
        for (Squad s : list) if (s.alive && s.faction == faction) n += s.n;
        return n;
    }
}
