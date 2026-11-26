package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The player's richer orders, carried on over time: a squad's stance, a route of waypoints, falling back to a point,
 * a cordon line, clearing an area, escorting someone; a vehicle sent somewhere, to orbit or attack an area, or home;
 * a vehicle driven with the stick ("take the wheel") and a person possessed ("control this person"); a formation's
 * posture (attack, defend, withdraw, screen, dig in). Every one is given through Actions (recorded); the state is
 * saved here.
 */
public final class Orders extends Module {
    public static final int SO_NONE = 0, SO_ROUTE = 1, SO_FALLBACK = 2, SO_CORDON = 3, SO_CLEAR = 4, SO_ESCORT = 5;
    public static final String[] SO_NAMES = {"", "Following a route", "Falling back", "On a cordon line", "Clearing an area", "Escorting"};
    public static final int VO_GO = 1, VO_ORBIT = 2, VO_ATTACK = 3, VO_RETURN = 4;
    public static final String[] VO_NAMES = {"", "Going", "Orbiting", "Attacking", "Returning"};
    public static final int FP_NONE = 0, FP_ATTACK = 1, FP_DEFEND = 2, FP_WITHDRAW = 3, FP_SCREEN = 4, FP_DIGIN = 5;
    public static final String[] FP_NAMES = {"", "Attack", "Defend", "Withdraw", "Screen", "Dig in"};
    private static final float[] FP_KILL = {1, 1.5f, 1f, 0.3f, 0.6f, 1.15f};
    private static final float[] FP_LOSS = {1, 1.6f, 0.7f, 0.3f, 0.5f, 0.45f};

    /** A squad's order that lasts: its kind, the points (x, y pairs), the one it's on, and who it escorts. */
    public static final class SqOrder {
        public int kind, idx, target = -1, targetUid, prevStance;
        public boolean vehicleTarget;
        public float[] pts = new float[0];
        public float r;
        public float t;
    }

    public static final class VOrder {
        public int v, uid, kind;
        public float x, y;
    }

    /** By squad id (null = nothing lasting). */
    public final List<SqOrder> squad = new ArrayList<SqOrder>();
    public final List<VOrder> vehicle = new ArrayList<VOrder>();
    /** Postures by formation id. */
    public int[] posture = new int[0];
    /** The vehicle driven with the stick, or -1 (and its uid, so a reused slot isn't driven). */
    public int driven = -1, drivenUid;
    /** The player took over someone in God mode (w.playerEnt is theirs). */
    public boolean possessing;

    public Orders(World w) {
        super(w);
    }

    @Override
    public String id() {
        return "orders";
    }

    // ------------------------------------------------------------------ squads

    public SqOrder of(Squads.Squad s) {
        return s == null || s.id >= squad.size() ? null : squad.get(s.id);
    }

    void set(Squads.Squad s, SqOrder o) {
        while (squad.size() <= s.id) squad.add(null);
        squad.set(s.id, o);
    }

    /** A squad's own order replaces whatever lasting order it had. */
    public void cancel(Squads.Squad s) {
        SqOrder o = of(s);
        if (o != null && o.kind == SO_FALLBACK) s.stance = o.prevStance;
        if (s != null && s.id < squad.size()) squad.set(s.id, null);
    }

    /** A route through the points (x, y pairs), one after the other. */
    public void route(Squads.Squad s, float[] pts) {
        if (pts.length < 2) return;
        cancel(s);
        SqOrder o = new SqOrder();
        o.kind = SO_ROUTE;
        o.pts = pts.clone();
        set(s, o);
        Tools.orderSquad(w, s, pts[0], pts[1]);
    }

    /** Back to a point, not stopping to fight on the way (they shoot again once there). */
    public void fallBack(Squads.Squad s, float x, float y) {
        cancel(s);
        SqOrder o = new SqOrder();
        o.kind = SO_FALLBACK;
        o.pts = new float[]{x, y};
        o.prevStance = s.stance;
        set(s, o);
        Tools.orderSquad(w, s, x, y);
        s.stance = Squads.STANCE_HOLD;
        w.news.radio(s.name + ": falling back", x, y, News.UNIT);
    }

    /** A line between two points: the squad spreads along it, facing away from where it came from. */
    public void cordon(Squads.Squad s, float ax, float ay, float bx, float by) {
        cancel(s);
        SqOrder o = new SqOrder();
        o.kind = SO_CORDON;
        o.pts = new float[]{ax, ay, bx, by};
        set(s, o);
        float mx = (ax + bx) / 2, my = (ay + by) / 2;
        float dx = bx - ax, dy = by - ay, l = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
        float[] p = w.sq.where(s);
        // face whichever side of the line the squad isn't on (it holds the line against what's beyond)
        float nx = -dy / l, ny = dx / l;
        if ((p[0] - mx) * nx + (p[1] - my) * ny > 0) {
            nx = -nx;
            ny = -ny;
        }
        Tools.orderSquad(w, s, mx, my);
        s.order = Squads.O_CORDON;
        s.faceX = nx;
        s.faceY = ny;
        s.hold = Math.max(20, l / 2);
        w.news.radio(s.name + ": holding a line", mx, my, News.UNIT);
    }

    /** Clear the dead out of the area round (x, y), then hold it. */
    public void clearArea(Squads.Squad s, float x, float y, float r) {
        cancel(s);
        SqOrder o = new SqOrder();
        o.kind = SO_CLEAR;
        o.pts = new float[]{x, y};
        o.r = r;
        set(s, o);
        Tools.orderSquad(w, s, x, y);
        w.news.radio(s.name + ": clearing the area", x, y, News.UNIT);
    }

    /** Keep with a person (ent) or a vehicle. */
    public void escort(Squads.Squad s, int target, boolean vehicleTarget) {
        cancel(s);
        SqOrder o = new SqOrder();
        o.kind = SO_ESCORT;
        o.target = target;
        o.vehicleTarget = vehicleTarget;
        o.targetUid = vehicleTarget ? w.veh.uid[target] : w.e.uid[target];
        set(s, o);
        float[] p = targetAt(o);
        if (p != null) Tools.orderSquad(w, s, p[0], p[1]);
        w.news.radio(s.name + ": escorting", p != null ? p[0] : s.gx, p != null ? p[1] : s.gy, News.UNIT);
    }

    private float[] targetAt(SqOrder o) {
        if (o.vehicleTarget) {
            int v = o.target;
            if (v < 0 || v >= w.veh.top || !w.veh.usable(v) || w.veh.uid[v] != o.targetUid) return null;
            return new float[]{w.veh.x[v], w.veh.y[v]};
        }
        int i = o.target;
        Ents e = w.e;
        if (i < 0 || i >= e.top || e.uid[i] != o.targetUid) return null;
        if (e.loc[i] == Ents.FREE) return new float[]{e.x[i], e.y[i]};
        if (e.loc[i] == Ents.ABOARD && e.vehicle[i] >= 0) return new float[]{w.veh.x[e.vehicle[i]], w.veh.y[e.vehicle[i]]};
        return null;
    }

    private boolean arrived(Squads.Squad s, float x, float y) {
        if (s.mode != Squads.M_FOOT) return false;
        float[] p = w.sq.where(s);
        float dx = p[0] - x, dy = p[1] - y;
        return dx * dx + dy * dy < 60 * 60;
    }

    // ------------------------------------------------------------------ vehicles

    public VOrder vorder(int v) {
        for (VOrder o : vehicle) if (o.v == v && o.uid == w.veh.uid[v]) return o;
        return null;
    }

    /** Sends a vehicle: go, orbit, attack an area, or come home. False if it can't. */
    public boolean orderVehicle(int v, int kind, float x, float y) {
        Vehicles vh = w.veh;
        if (v < 0 || v >= vh.top || !vh.usable(v)) return false;
        if (v == driven) driven = -1;
        for (int k = vehicle.size() - 1; k >= 0; k--) if (vehicle.get(k).v == v) vehicle.remove(k);
        Content.VType t = vh.type(v);
        boolean ok;
        switch (kind) {
            case VO_RETURN:
                vh.goHome(v);
                return true;
            case VO_ORBIT:
            case VO_ATTACK:
                if (t.flies) {
                    ok = vh.driveTo(v, x, y, 0);
                    break;
                }
                if (kind == VO_ORBIT) return false;
                ok = vh.driveTo(v, x, y, 0);
                break;
            default:
                ok = vh.driveTo(v, x, y, 0);
        }
        if (!ok) return false;
        VOrder o = new VOrder();
        o.v = v;
        o.uid = vh.uid[v];
        o.kind = kind;
        o.x = x;
        o.y = y;
        vehicle.add(o);
        return true;
    }

    /** Takes the wheel of a vehicle (or lets go of it, v = -1). */
    public void drive(int v) {
        Vehicles vh = w.veh;
        if (driven >= 0 && driven < vh.top && vh.usable(driven) && vh.uid[driven] == drivenUid) {
            // let go: an aircraft circles where it is, a car stops
            int d = driven;
            if (vh.type(d).flies) {
                vh.destX[d] = vh.x[d];
                vh.destY[d] = vh.y[d];
                vh.state[d] = Vehicles.V_FLY;
            } else {
                vh.state[d] = Vehicles.V_ARRIVED;
                vh.spd[d] = 0;
            }
        }
        driven = -1;
        if (v < 0 || v >= vh.top || !vh.usable(v)) return;
        for (int k = vehicle.size() - 1; k >= 0; k--) if (vehicle.get(k).v == v) vehicle.remove(k);
        driven = v;
        drivenUid = vh.uid[v];
        vh.route[v] = null;
        vh.returning[v] = false;
        vh.state[v] = vh.type(v).flies ? Vehicles.V_FLY : Vehicles.V_ARRIVED;
    }

    /** Is this the vehicle the stick drives (Vehicles asks every step)? */
    boolean drives(int v) {
        return v == driven && w.veh.uid[v] == drivenUid && w.veh.usable(v);
    }

    /** One step of the vehicle driven with the stick. */
    void wheel(int v, float dt) {
        Vehicles vh = w.veh;
        Content.VType t = vh.type(v);
        float jx = w.strat.playerJoyX, jy = w.strat.playerJoyY;
        float len = Math.min(1, (float) Math.sqrt(jx * jx + jy * jy));
        if (t.flies) {
            vh.alt[v] = Math.min(1, vh.alt[v] + dt * 0.5f);
            vh.state[v] = Vehicles.V_FLY;
            if (len > 0.1f) {
                float sp = t.speed * len * dt;
                vh.head[v] = (float) Math.atan2(jy, jx);
                float nx = vh.x[v] + jx / Math.max(len, 0.01f) * sp, ny = vh.y[v] + jy / Math.max(len, 0.01f) * sp;
                float m = w.r.px();
                vh.x[v] = Math.max(0, Math.min(m, nx));
                vh.y[v] = Math.max(0, Math.min(m, ny));
            }
            vh.destX[v] = vh.x[v];
            vh.destY[v] = vh.y[v];
            vh.spd[v] = t.speed * len;
            return;
        }
        vh.state[v] = Vehicles.V_ARRIVED;
        vh.timer[v] = 0;
        // turn towards the stick, then drive on (a car can't turn on the spot)
        float want = t.speed * len;
        if (len > 0.15f) {
            float target = (float) Math.atan2(jy, jx), h = vh.head[v];
            float d = target - h;
            while (d > Math.PI) d -= 2 * Math.PI;
            while (d < -Math.PI) d += 2 * Math.PI;
            float turn = (t.tracked ? 2.2f : 1.8f) * dt * Math.min(1, 0.3f + vh.spd[v] / Math.max(1, t.speed) * 2);
            vh.head[v] = h + Math.max(-turn, Math.min(turn, d));
            // backing up: the stick behind the vehicle
            if (Math.abs(d) > 2.3f) want = -t.speed * 0.3f * len;
        }
        vh.spd[v] += (want - vh.spd[v]) * Math.min(1, dt * 1.5f);
        float nx = vh.x[v] + (float) Math.cos(vh.head[v]) * vh.spd[v] * dt, ny = vh.y[v] + (float) Math.sin(vh.head[v]) * vh.spd[v] * dt;
        if (w.r.walkablePx(nx, ny)) {
            vh.x[v] = nx;
            vh.y[v] = ny;
        } else vh.spd[v] = 0;
        // on a national map the chunk under the wheels stays active (driven out of the active area, it takes it in)
        w.act.touchPx(vh.x[v], vh.y[v]);
        // the dead in the way go under it
        if (Math.abs(vh.spd[v]) > 40) {
            Ents e = w.e;
            int z = w.sp.nearestZombie(e, vh.x[v], vh.y[v], t.length * 0.5f + 4);
            if (z >= 0 && e.loc[z] == Ents.FREE) {
                e.hp[z] -= Math.abs(vh.spd[v]) * (t.tracked ? 3 : 1.2f);
                if (e.hp[z] <= 0) {
                    w.combat.killZombie(z, -1, "police".equals(t.faction) ? Ents.COP : Ents.SOLDIER, Combat.CAUSE_VEHICLE);
                    vh.kills[v]++;
                }
                if (!t.tracked) vh.spd[v] *= 0.85f;
            }
        }
    }

    // ------------------------------------------------------------------ people

    /** God takes over someone (or lets go, ent = -1). */
    public boolean possess(int ent, int uid) {
        Ents e = w.e;
        if (possessing) {
            w.playerEnt = -1;
            possessing = false;
        }
        if (ent < 0) return true;
        if (w.playerEnt >= 0 || ent >= e.top || e.loc[ent] != Ents.FREE || e.kind[ent] == Ents.ZOMBIE || e.uid[ent] != uid) return false;
        w.playerEnt = ent;
        possessing = true;
        return true;
    }

    // ------------------------------------------------------------------ formations

    public int posture(Forces.Formation f) {
        return f == null || f.id >= posture.length ? FP_NONE : posture[f.id];
    }

    public boolean setPosture(Forces.Formation f, int p, float x, float y) {
        if (f == null || !f.alive) return false;
        if (posture.length <= f.id) posture = java.util.Arrays.copyOf(posture, f.id + 8);
        f.player = true;
        f.released = false;
        boolean ok = true;
        switch (p) {
            case FP_WITHDRAW: {
                // back to where it came from (its base), or away from the dead to the point given
                float tx = x, ty = y;
                if (f.home >= 0 && f.home < w.r.facilities.size()) {
                    Region.Facility h = w.r.facilities.get(f.home);
                    tx = h.x;
                    ty = h.y;
                }
                ok = w.forces.order(f, tx, ty);
                break;
            }
            case FP_DIGIN:
                // where it stands: no further
                if (f.state == Forces.MOVING) ok = w.forces.order(f, f.hq.x, f.hq.y);
                break;
            default:
                ok = w.forces.order(f, x, y);
        }
        if (!ok) return false;
        posture[f.id] = p;
        w.news.radio(f.name + ": " + FP_NAMES[p].toLowerCase() + (p == FP_DIGIN ? "" : ", as ordered"), f.hq.x, f.hq.y, News.COMMAND);
        return true;
    }

    /** How hard a formation (or station, null) hits the dead it is in contact with, by posture and the rules. */
    public float killMul(Forces.Formation f) {
        return FP_KILL[posture(f)] * w.rules.force();
    }

    /** How much it loses in contact, by posture. */
    public float lossMul(Forces.Formation f) {
        return FP_LOSS[posture(f)];
    }

    // ------------------------------------------------------------------ once a second

    @Override
    public void update(float dt) {
        if (possessing && (w.playerEnt < 0 || w.e.loc[w.playerEnt] != Ents.FREE)) {
            possessing = false;
            w.playerEnt = -1;
        }
        if (driven >= 0 && !drives(driven)) driven = -1;
        if (w.strategic) return;
        for (int id = 0; id < squad.size(); id++) {
            SqOrder o = squad.get(id);
            if (o == null) continue;
            Squads.Squad s = id < w.sq.list.size() ? w.sq.get(id) : null;
            if (s == null || !s.alive) {
                squad.set(id, null);
                continue;
            }
            o.t += dt;
            switch (o.kind) {
                case SO_ROUTE: {
                    float x = o.pts[o.idx * 2], y = o.pts[o.idx * 2 + 1];
                    if (!arrived(s, x, y)) break;
                    if ((o.idx + 1) * 2 >= o.pts.length) {
                        squad.set(id, null);
                        w.news.radio(s.name + ": at the end of the route", x, y, News.UNIT);
                        break;
                    }
                    o.idx++;
                    Tools.orderSquad(w, s, o.pts[o.idx * 2], o.pts[o.idx * 2 + 1]);
                    break;
                }
                case SO_FALLBACK:
                    if (arrived(s, o.pts[0], o.pts[1])) {
                        s.stance = o.prevStance;
                        squad.set(id, null);
                    }
                    break;
                case SO_CLEAR: clearing(s, o); break;
                case SO_ESCORT: {
                    float[] p = targetAt(o);
                    if (p == null) {
                        squad.set(id, null);
                        w.news.radio(s.name + ": the one we escorted is gone", s.gx, s.gy, News.UNIT);
                        break;
                    }
                    float dx = p[0] - s.gx, dy = p[1] - s.gy;
                    if (dx * dx + dy * dy > 70 * 70 && w.r.walkablePx(p[0], p[1])) {
                        s.order = Squads.O_MOVE;
                        w.sq.order(s, Squads.O_MOVE, p[0], p[1]);
                    }
                    break;
                }
                default:
            }
        }
        // the aggressive close in on the dead near them
        for (Squads.Squad s : w.sq.list) {
            if (!s.alive || !s.player || s.stance != Squads.STANCE_AGGRESSIVE || s.order != Squads.O_HOLD || s.mode != Squads.M_FOOT) continue;
            if (of(s) != null) continue;
            closeIn(s, 380);
        }
        // vehicles' orders
        for (int k = vehicle.size() - 1; k >= 0; k--) {
            VOrder o = vehicle.get(k);
            int v = o.v;
            if (v >= w.veh.top || !w.veh.usable(v) || w.veh.uid[v] != o.uid || w.veh.returning[v]) {
                vehicle.remove(k);
                continue;
            }
            if ((o.kind == VO_ORBIT || o.kind == VO_ATTACK) && w.veh.type(v).flies) {
                // keep circling the place, as long as fuel and rounds last
                if (w.veh.state[v] == Vehicles.V_ORBIT) {
                    w.veh.destX[v] = o.x;
                    w.veh.destY[v] = o.y;
                    if (w.veh.fuel[v] > 0.1f && (w.veh.type(v).weapon == null || w.veh.ammo[v] > 0)) w.veh.timer[v] = 0;
                } else if (w.veh.state[v] != Vehicles.V_FLY) vehicle.remove(k);
            } else if (w.veh.state[v] != Vehicles.V_DRIVE) vehicle.remove(k);
        }
    }

    /** Clearing an area: go after the nearest of the dead in it; when none are left, say so and hold. */
    private void clearing(Squads.Squad s, SqOrder o) {
        float cx = o.pts[0], cy = o.pts[1];
        if (w.sp.nearestZombie(w.e, cx, cy, o.r) < 0 && w.sec.zIn[w.r.sectorOfPx(cx, cy)] == 0) {
            if (o.t > 5) {
                w.news.radio(s.name + ": area clear", cx, cy, News.UNIT);
                squad.set(s.id, null);
                w.sq.order(s, Squads.O_HOLD, cx, cy);
            }
            return;
        }
        if (s.mode != Squads.M_FOOT || ((int) o.t) % 3 != 0) return;
        float[] p = w.sq.where(s);
        int z = w.sp.nearestZombie(w.e, p[0], p[1], o.r * 2);
        if (z < 0) z = w.sp.nearestZombie(w.e, cx, cy, o.r);
        if (z < 0) return;
        float zx = w.e.x[z], zy = w.e.y[z];
        float dx = zx - cx, dy = zy - cy;
        if (dx * dx + dy * dy > o.r * o.r * 1.6f) return;
        standOff(s, z, 70);
    }

    private void closeIn(Squads.Squad s, float reach) {
        float[] p = w.sq.where(s);
        Ents e = w.e;
        int z = w.sp.nearestZombie(e, p[0], p[1], reach);
        if (z < 0) return;
        float dx = p[0] - e.x[z], dy = p[1] - e.y[z];
        if (dx * dx + dy * dy < 120 * 120) return;
        standOff(s, z, 90);
    }

    /** Moves the squad to stand d px short of zombie z, facing it. */
    private void standOff(Squads.Squad s, int z, float d) {
        float[] p = w.sq.where(s);
        Ents e = w.e;
        float dx = p[0] - e.x[z], dy = p[1] - e.y[z], l = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
        if (l < d + 25) return;
        float nx = e.x[z] + dx / l * d, ny = e.y[z] + dy / l * d;
        if (!w.r.walkablePx(nx, ny)) return;
        w.sq.order(s, Squads.O_HOLD, nx, ny);
        s.faceX = -dx / l;
        s.faceY = -dy / l;
    }

    // ------------------------------------------------------------------ drawing: each order as a line and a flag

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        float dp = cam.dp;
        for (int id = 0; id < squad.size(); id++) {
            SqOrder o = squad.get(id);
            if (o == null || id >= w.sq.list.size() || !w.sq.get(id).alive) continue;
            Squads.Squad s = w.sq.get(id);
            float[] p = w.sq.where(s);
            float lx = cam.toScreenX(p[0]), ly = cam.toScreenY(p[1]);
            switch (o.kind) {
                case SO_ROUTE:
                    for (int k = o.idx; k * 2 < o.pts.length; k++) {
                        float x = cam.toScreenX(o.pts[k * 2]), y = cam.toScreenY(o.pts[k * 2 + 1]);
                        dashed(g, lx, ly, x, y, 0xC0FFE040, dp);
                        view.flag(g, x, y, k == o.pts.length / 2 - 1 ? 0xFFFFE040 : 0xFFFFF0A0);
                        lx = x;
                        ly = y;
                    }
                    break;
                case SO_FALLBACK: {
                    float x = cam.toScreenX(o.pts[0]), y = cam.toScreenY(o.pts[1]);
                    dashed(g, lx, ly, x, y, 0xC0FF9040, dp);
                    view.flag(g, x, y, 0xFFFF9040);
                    break;
                }
                case SO_CORDON: {
                    float ax = cam.toScreenX(o.pts[0]), ay = cam.toScreenY(o.pts[1]), bx = cam.toScreenX(o.pts[2]), by = cam.toScreenY(o.pts[3]);
                    g.color(0xA0000000);
                    g.line(ax, ay, bx, by, 5 * dp);
                    g.color(0xFF60A0FF);
                    g.line(ax, ay, bx, by, 2.5f * dp);
                    view.flag(g, ax, ay, 0xFF60A0FF);
                    view.flag(g, bx, by, 0xFF60A0FF);
                    break;
                }
                case SO_CLEAR: {
                    float x = cam.toScreenX(o.pts[0]), y = cam.toScreenY(o.pts[1]);
                    g.color(0x90FF6040);
                    g.strokeCircle(x, y, Math.max(12 * dp, o.r * cam.zoom), 2 * dp);
                    view.flag(g, x, y, 0xFFFF6040);
                    break;
                }
                case SO_ESCORT: {
                    float[] t = targetAt(o);
                    if (t == null) break;
                    float x = cam.toScreenX(t[0]), y = cam.toScreenY(t[1]);
                    dashed(g, lx, ly, x, y, 0xC060E0A0, dp);
                    g.color(0xFF60E0A0);
                    g.strokeCircle(x, y, 9 * dp, 2 * dp);
                    break;
                }
                default:
            }
        }
        for (VOrder o : vehicle) {
            if (o.v >= w.veh.top || !w.veh.usable(o.v)) continue;
            float vx = cam.toScreenX(w.veh.x[o.v]), vy = cam.toScreenY(w.veh.y[o.v]);
            float x = cam.toScreenX(o.x), y = cam.toScreenY(o.y);
            int col = o.kind == VO_ATTACK ? 0xFFFF6040 : o.kind == VO_ORBIT ? 0xFFFFC040 : 0xFFFFE040;
            dashed(g, vx, vy, x, y, (col & 0x00FFFFFF) | 0xC0000000, dp);
            if (o.kind == VO_ORBIT || o.kind == VO_ATTACK) {
                g.color(col);
                g.strokeCircle(x, y, Math.max(14 * dp, 150 * cam.zoom), 1.5f * dp);
            }
            view.flag(g, x, y, col);
        }
        if (driven >= 0 && drives(driven)) {
            g.color(0xFFFFE040);
            g.strokeCircle(cam.toScreenX(w.veh.x[driven]), cam.toScreenY(w.veh.y[driven]), 16 * dp, 2.5f * dp);
        }
        // formations' postures: a word under the symbol
        if (posture.length > 0) for (Forces.Formation f : w.forces.formations) {
            int p = posture(f);
            if (!f.alive || p == FP_NONE) continue;
            view.label(g, FP_NAMES[p], cam.toScreenX(f.hq.x), cam.toScreenY(f.hq.y) + 24 * dp, 11, 0xFFFFE8A0, Gfx.ALIGN_CENTER, true);
        }
    }

    private static void dashed(Gfx g, float ax, float ay, float bx, float by, int col, float dp) {
        float dx = bx - ax, dy = by - ay, l = (float) Math.sqrt(dx * dx + dy * dy);
        if (l < 1) return;
        int n = (int) Math.min(200, l / (8 * dp));
        g.color(col);
        for (int k = 0; k < n; k += 2) {
            float t0 = k / (float) n, t1 = Math.min(1, (k + 1) / (float) n);
            g.line(ax + dx * t0, ay + dy * t0, ax + dx * t1, ay + dy * t1, 2 * dp);
        }
    }

    // ------------------------------------------------------------------ saves

    @Override
    public void save(DataOutputStream o) throws IOException {
        o.writeInt(w.sq.list.size());
        for (Squads.Squad s : w.sq.list) {
            o.writeByte(s.stance);
            o.writeBoolean(s.stayMounted);
        }
        o.writeInt(squad.size());
        for (SqOrder q : squad) {
            o.writeBoolean(q != null);
            if (q == null) continue;
            o.writeInt(q.kind);
            o.writeInt(q.idx);
            o.writeInt(q.target);
            o.writeInt(q.targetUid);
            o.writeInt(q.prevStance);
            o.writeBoolean(q.vehicleTarget);
            o.writeFloat(q.r);
            o.writeFloat(q.t);
            o.writeInt(q.pts.length);
            for (float f : q.pts) o.writeFloat(f);
        }
        o.writeInt(vehicle.size());
        for (VOrder q : vehicle) {
            o.writeInt(q.v);
            o.writeInt(q.uid);
            o.writeInt(q.kind);
            o.writeFloat(q.x);
            o.writeFloat(q.y);
        }
        o.writeInt(posture.length);
        for (int p : posture) o.writeByte(p);
        o.writeInt(driven);
        o.writeInt(drivenUid);
        o.writeBoolean(possessing);
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        int n = in.readInt();
        for (int k = 0; k < n; k++) {
            int st = in.readByte();
            boolean m = in.readBoolean();
            if (k < w.sq.list.size()) {
                w.sq.get(k).stance = st;
                w.sq.get(k).stayMounted = m;
            }
        }
        squad.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) {
            if (!in.readBoolean()) {
                squad.add(null);
                continue;
            }
            SqOrder q = new SqOrder();
            q.kind = in.readInt();
            q.idx = in.readInt();
            q.target = in.readInt();
            q.targetUid = in.readInt();
            q.prevStance = in.readInt();
            q.vehicleTarget = in.readBoolean();
            q.r = in.readFloat();
            q.t = in.readFloat();
            q.pts = new float[in.readInt()];
            for (int j = 0; j < q.pts.length; j++) q.pts[j] = in.readFloat();
            squad.add(q);
        }
        vehicle.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) {
            VOrder q = new VOrder();
            q.v = in.readInt();
            q.uid = in.readInt();
            q.kind = in.readInt();
            q.x = in.readFloat();
            q.y = in.readFloat();
            vehicle.add(q);
        }
        posture = new int[in.readInt()];
        for (int k = 0; k < posture.length; k++) posture[k] = in.readByte();
        driven = in.readInt();
        drivenUid = in.readInt();
        possessing = in.readBoolean();
    }
}
