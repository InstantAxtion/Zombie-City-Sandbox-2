package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The sea and the air: destroyers off the coast shelling the dead in range of the shore, the coast guard taking
 * people off by boat when a coastal town is evacuated, strike jets launched from the air base, and formations flown
 * between airfields when they're sent far.
 */
public final class Navy extends Module {
    public static final class Ship {
        public float x, y, head, gunT, tx = -1, ty = -1;
        public int shells, kills;
        public boolean alive = true;
        public String name;
    }

    public static final class Flight {
        public int formation;
        public float fx, fy, tx, ty, x, y;
    }

    public final List<Ship> ships = new ArrayList<Ship>();
    public final List<Flight> flights = new ArrayList<Flight>();
    private final boolean[] coastal;
    private final boolean[] coastNews;
    public int rescued, airlifts;
    private float targetT;

    /** Sectors at or by the water (rivers and lakes too): a coastal town's waterfront. */
    private boolean[] wet;

    public Navy(World w) {
        super(w);
        coastal = new boolean[w.sec.n];
        boolean[] deepHere = new boolean[w.sec.n];
        coastNews = new boolean[w.r.settlements.size()];
        // which sectors touch open water (boats can come in)
        Region r = w.r;
        for (int s = 0; s < w.sec.n; s++) {
            int x0 = (s % w.sec.sw) * w.r.sect, y0 = (s / w.sec.sw) * w.r.sect;
            int water = 0, deep = 0, all = 0;
            for (int y = y0; y < y0 + w.r.sect; y += 4)
                for (int x = x0; x < x0 + w.r.sect; x += 4) {
                    int t = r.t(x, y);
                    if (Tile.water(t)) water++;
                    if (t == Tile.DEEP) deep++;
                    all++;
                }
            coastal[s] = water > all / 25 && water < all * 9 / 10;
            if (deep == 0) coastal[s] = false;
            deepHere[s] = deep > 0;
        }
        wet = new boolean[w.sec.n];
        for (int s = 0; s < w.sec.n; s++) {
            int x0 = (s % w.sec.sw) * w.r.sect, y0 = (s / w.sec.sw) * w.r.sect;
            for (int y = y0 - 8; y < y0 + w.r.sect + 8 && !wet[s]; y += 4)
                for (int x = x0 - 8; x < x0 + w.r.sect + 8; x += 4)
                    if (Tile.water(r.t(x, y))) {
                        wet[s] = true;
                        break;
                    }
        }
        // a shore with deep water off it (in the next sector out) is reachable by boat too
        for (int s = 0; s < w.sec.n; s++) {
            if (coastal[s]) continue;
            int x0 = (s % w.sec.sw) * w.r.sect, y0 = (s / w.sec.sw) * w.r.sect, water = 0, all = 0;
            for (int y = y0; y < y0 + w.r.sect; y += 8)
                for (int x = x0; x < x0 + w.r.sect; x += 8) {
                    if (Tile.water(r.t(x, y))) water++;
                    all++;
                }
            if (water == 0 || water > all * 9 / 10) continue;
            for (int d = 0; d < 8; d++) {
                int t = w.sec.neighbour(s, d);
                if (t >= 0 && deepHere[t]) coastal[s] = true;
            }
        }
        // destroyers by the naval station
        Content.VType dt = w.c.vtype("destroyer");
        if (dt == null) return;
        int k = 0;
        for (Region.Facility f : r.facilities) {
            if (f.kind != Region.F_PORT) continue;
            float[] p = sea(f.x, f.y, 4000);
            if (p == null) continue;
            for (int i = 0; i < 2; i++) {
                Ship sh = new Ship();
                sh.x = p[0] + i * 120;
                sh.y = p[1] + i * 60;
                if (!Tile.water(r.tileAtPx(sh.x, sh.y))) {
                    sh.x = p[0];
                    sh.y = p[1];
                }
                sh.shells = dt.weapon != null ? dt.weapon.ammo : 300;
                sh.name = "USS " + Names.place(new Rng(w.seed + 77 + k), Region.S_TOWN);
                ships.add(sh);
                k++;
            }
        }
    }

    @Override
    public String id() {
        return "navy";
    }

    /** The nearest deep water to (x, y) within reach, or null. */
    private float[] sea(float x, float y, float reach) {
        Region r = w.r;
        for (float rad = 0; rad <= reach; rad += 48)
            for (int a = 0; a < 24; a++) {
                double ang = a * Math.PI / 12;
                float px = x + (float) Math.cos(ang) * rad, py = y + (float) Math.sin(ang) * rad;
                if (r.tileAtPx(px, py) == Tile.DEEP) return new float[]{px, py};
            }
        return null;
    }

    /** Where strike jets take off: the air base, if it's still working. */
    public float[] airBase() {
        for (Region.Facility f : w.r.facilities)
            if (f.kind == Region.F_AIRBASE && w.strat.online(f.id)) return new float[]{f.x, f.y};
        return null;
    }

    public boolean coastal(int s) {
        return coastal[s];
    }

    @Override
    public void update(float dt) {
        ships(dt);
        coastGuard(dt);
        airlift(dt);
    }

    private void ships(float dt) {
        Content.VType vt = w.c.vtype("destroyer");
        if (vt == null || ships.isEmpty()) return;
        targetT -= dt;
        boolean retarget = targetT <= 0;
        if (retarget) targetT = 30;
        Sectors sec = w.sec;
        float range = vt.weapon != null ? vt.weapon.range : 6000;
        for (Ship sh : ships) {
            if (!sh.alive) continue;
            if (retarget) {
                // the biggest mass of the dead near the shore, away from the living
                int best = -1, bz = 0;
                // (only awake sectors hold any of the dead)
                for (int s = sec.idx.first(); s >= 0; s = sec.idx.next(s)) {
                    int z = sec.zIn[s] + w.sp.secZ[s];
                    if (z < 60 || sec.popIn[s] > 300 || w.sp.secCiv[s] + w.sp.secCop[s] + w.sp.secSol[s] > 0) continue;
                    float dx = sec.cx(s) - sh.x, dy = sec.cy(s) - sh.y;
                    if (dx * dx + dy * dy > (range * 2.5f) * (range * 2.5f)) continue;
                    if (z > bz) {
                        bz = z;
                        best = s;
                    }
                }
                sh.tx = best >= 0 ? sec.cx(best) : -1;
                sh.ty = best >= 0 ? sec.cy(best) : -1;
            }
            if (sh.tx < 0) continue;
            float dx = sh.tx - sh.x, dy = sh.ty - sh.y, d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
            // steam closer, staying on deep water
            if (d > range * 0.8f) {
                float step = vt.speed * dt;
                float a = (float) Math.atan2(dy, dx);
                for (int k = 0; k < 5; k++) {
                    float ta = a + (k == 0 ? 0 : (k % 2 == 1 ? 1 : -1) * ((k + 1) / 2) * 0.6f);
                    float nx = sh.x + (float) Math.cos(ta) * step, ny = sh.y + (float) Math.sin(ta) * step;
                    if (w.r.tileAtPx(nx, ny) == Tile.DEEP) {
                        sh.x = nx;
                        sh.y = ny;
                        sh.head = ta;
                        break;
                    }
                }
            }
            sh.gunT -= dt;
            if (d <= range && sh.gunT <= 0 && sh.shells > 0) {
                sh.gunT = 15;
                fire(sh, vt);
            }
        }
    }

    private void fire(Ship sh, Content.VType vt) {
        Sectors sec = w.sec;
        int s = w.r.sectorOfPx(sh.tx, sh.ty);
        Content.Weapon gun = vt.weapon;
        int salvo = 3;
        sh.shells -= salvo;
        if (!w.strategic && (sec.hot[s] > 0 || sec.cam[s])) {
            float[] c = new float[4];
            for (int k = 0; k < salvo; k++) {
                float ox = (Rng.unit(w.seed, w.tick, k * 2L + 51) - 0.5f) * 200, oy = (Rng.unit(w.seed, w.tick, k * 2L + 52) - 0.5f) * 200;
                float x = sh.tx + ox, y = sh.ty + oy;
                if (w.sp.zombieCentroid(w.e, sh.tx, sh.ty, 300, c)) {
                    x = c[0] + ox;
                    y = c[1] + oy;
                }
                if (w.sp.livingNear(w.e, x, y, 100, 1) > 0) continue;
                int before = w.stats.zombieKills;
                w.combat.blast(x, y, gun != null ? gun.blast : 70, gun != null ? gun.blastDmg : 500, -1, Ents.SOLDIER);
                sh.kills += w.stats.zombieKills - before;
            }
        } else {
            int kill = Math.min(sec.zIn[s], salvo * 6);
            sec.zIn[s] -= kill;
            w.stats.killsBySide[2] += kill;
            w.stats.zombieKills += kill;
            sh.kills += kill;
            if (kill > 0 && Rng.unit(w.seed, w.tick, s) < 0.2f) w.fires.blast(sh.tx, sh.ty, 70);
        }
        w.fx.sound("cannon", sh.x, sh.y);
        if (sh.kills > 0 && sh.kills < 30) w.news.radio(sh.name + ": naval gunfire on the dead ashore", sh.tx, sh.ty, News.COMMAND);
    }

    /** Is this a sector where boats take people off (a waterfront of a town being evacuated)? */
    public boolean pickup(int s) {
        if (s < 0 || s >= w.sec.n) return false;
        int st = w.sec.settlement[s];
        if (st < 0 || !w.gov.evacuating(st) || w.sec.zIn[s] > 0) return false;
        return coastal[s] || wet != null && wet[s] && w.roles.coastal(w.r.settlements.get(st));
    }

    /** Boats take people off the coast of a town being evacuated. */
    private void coastGuard(float dt) {
        float rate = w.c.rule("coastGuard", 0.0015f);
        Sectors sec = w.sec;
        // (the sectors of the towns being evacuated, in map order)
        boolean[] ev = new boolean[w.r.settlements.size()];
        boolean any = false;
        for (int st = 0; st < ev.length; st++) any |= ev[st] = w.gov.evacuating(st);
        if (!any) return;
        for (int s : sec.idx.sectorsOf(ev)) {
            if (sec.popIn[s] <= 0 || sec.zIn[s] > 0) continue;
            int st = sec.settlement[s];
            // boats reach a town on the coast from its waterfront (the sectors at or next to the water)
            if (!coastal[s] && !(wet[s] && w.roles.coastal(w.r.settlements.get(st)))) continue;
            float m = sec.popIn[s] * rate * dt;
            int k = Math.min(sec.popIn[s], (int) m + (Rng.unit(w.seed, w.tick, s * 3L + 99) < m % 1f ? 1 : 0));
            if (k <= 0) continue;
            sec.popIn[s] -= k;
            w.strat.leftCountry(st, k);
            rescued += k;
            if (!coastNews[st]) {
                coastNews[st] = true;
                w.news.radio("Coast guard and navy: boats are taking people off the shore at " + w.r.settlements.get(st).name, sec.cx(s), sec.cy(s), News.COMMAND);
            }
        }
    }

    // ------------------------------------------------------------------ airlift

    private Region.Facility airfieldNear(float x, float y, float reach, Region.Facility not) {
        Region.Facility best = null;
        float bd = reach * reach;
        for (Region.Facility f : w.r.facilities) {
            if ((f.kind != Region.F_AIRBASE && f.kind != Region.F_AIRPORT) || f == not || !w.strat.online(f.id)) continue;
            float dx = f.x - x, dy = f.y - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = f;
            }
        }
        return best;
    }

    /** Would a formation going to (x, y) be flown (far, with working airfields at both ends)? */
    public boolean airliftable(Forces.Formation f, float x, float y) {
        float far = w.c.rule("airliftDistance", 9000);
        float dx = x - f.hq.x, dy = y - f.hq.y;
        if (dx * dx + dy * dy < far * far) return false;
        Region.Facility a = airfieldNear(f.hq.x, f.hq.y, 5000, null);
        return a != null && airfieldNear(x, y, 4000, a) != null;
    }

    /** Formations sent far go by air when there are working airfields at both ends. */
    private void airlift(float dt) {
        float far = w.c.rule("airliftDistance", 9000);
        for (Forces.Formation f : w.forces.formations) {
            if (!f.alive || f.state != Forces.MOVING || f.route == null || f.ri > 0) continue;
            float dx = f.gx - f.hq.x, dy = f.gy - f.hq.y;
            if (dx * dx + dy * dy < far * far) continue;
            Region.Facility a = airfieldNear(f.hq.x, f.hq.y, 5000, null);
            if (a == null) continue;
            Region.Facility b = airfieldNear(f.gx, f.gy, 4000, a);
            if (b == null) continue;
            Flight fl = new Flight();
            fl.formation = f.id;
            fl.fx = fl.x = f.hq.x;
            fl.fy = fl.y = f.hq.y;
            fl.tx = b.x;
            fl.ty = b.y;
            flights.add(fl);
            f.state = Forces.AIRLIFT;
            airlifts++;
            w.news.count("airlift", "Airlift: the " + f.name + " is being flown to " + b.name + "|Airlift: %d more formation{s} flown across the country", 1, b.x, b.y);
        }
        Content.VType pl = w.c.vtype("transport_plane");
        float speed = pl != null ? pl.speed : 420;
        for (int k = flights.size() - 1; k >= 0; k--) {
            Flight fl = flights.get(k);
            Forces.Formation f = w.forces.formations.get(fl.formation);
            if (!f.alive || f.state != Forces.AIRLIFT) {
                flights.remove(k);
                continue;
            }
            float dx = fl.tx - fl.x, dy = fl.ty - fl.y, d = (float) Math.sqrt(dx * dx + dy * dy);
            float step = speed * dt;
            if (d <= step) {
                f.hq.x = fl.tx;
                f.hq.y = fl.ty;
                flights.remove(k);
                f.state = Forces.HOLDING;
                float gx = f.gx, gy = f.gy;
                if (!w.forces.order(f, gx, gy)) f.state = Forces.HOLDING;
                continue;
            }
            fl.x += dx / d * step;
            fl.y += dy / d * step;
            f.hq.x = fl.x;
            f.hq.y = fl.y;
        }
    }

    // ------------------------------------------------------------------ saves and drawing

    @Override
    public void save(DataOutputStream o) throws IOException {
        o.writeInt(ships.size());
        for (Ship s : ships) {
            o.writeFloat(s.x); o.writeFloat(s.y); o.writeFloat(s.head); o.writeFloat(s.gunT); o.writeFloat(s.tx); o.writeFloat(s.ty);
            o.writeInt(s.shells); o.writeInt(s.kills); o.writeBoolean(s.alive); o.writeUTF(s.name);
        }
        o.writeInt(flights.size());
        for (Flight f : flights) {
            o.writeInt(f.formation);
            o.writeFloat(f.fx); o.writeFloat(f.fy); o.writeFloat(f.tx); o.writeFloat(f.ty); o.writeFloat(f.x); o.writeFloat(f.y);
        }
        o.writeInt(coastNews.length);
        for (boolean b : coastNews) o.writeBoolean(b);
        o.writeInt(rescued);
        o.writeInt(airlifts);
        o.writeFloat(targetT);
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        ships.clear();
        int n = in.readInt();
        for (int k = 0; k < n; k++) {
            Ship s = new Ship();
            s.x = in.readFloat(); s.y = in.readFloat(); s.head = in.readFloat(); s.gunT = in.readFloat(); s.tx = in.readFloat(); s.ty = in.readFloat();
            s.shells = in.readInt(); s.kills = in.readInt(); s.alive = in.readBoolean(); s.name = in.readUTF();
            ships.add(s);
        }
        flights.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) {
            Flight f = new Flight();
            f.formation = in.readInt();
            f.fx = in.readFloat(); f.fy = in.readFloat(); f.tx = in.readFloat(); f.ty = in.readFloat(); f.x = in.readFloat(); f.y = in.readFloat();
            flights.add(f);
        }
        n = in.readInt();
        for (int k = 0; k < n; k++) {
            boolean b = in.readBoolean();
            if (k < coastNews.length) coastNews[k] = b;
        }
        rescued = in.readInt();
        airlifts = in.readInt();
        targetT = in.readFloat();
    }

    private final float[] hull = new float[10];

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        Content.VType vt = w.c.vtype("destroyer");
        float z = cam.zoom, dp = cam.dp;
        for (Ship s : ships) {
            if (!s.alive) continue;
            float sx = cam.toScreenX(s.x), sy = cam.toScreenY(s.y);
            if (sx < -100 * dp || sy < -100 * dp || sx > cam.sw + 100 * dp || sy > cam.sh + 100 * dp) continue;
            float L = Math.max(22 * dp, (vt != null ? vt.length : 110) * z), W = Math.max(5 * dp, (vt != null ? vt.width : 22) * z);
            // from far out a ship is a mark like any other: left out where it would cover a name
            if (z < 0.3f && !view.place(sx - L * 0.55f, sy - L * 0.3f, sx + L * 0.55f, sy + L * 0.3f)) continue;
            g.save();
            g.translate(sx, sy);
            g.rotate(s.head);
            g.color(0x50FFFFFF);
            g.fillRect(-L * 1.1f, -W * 0.3f, L * 0.6f, W * 0.6f); // wake
            hull[0] = L / 2; hull[1] = 0; hull[2] = L / 4; hull[3] = -W / 2; hull[4] = -L / 2; hull[5] = -W / 2; hull[6] = -L / 2; hull[7] = W / 2; hull[8] = L / 4; hull[9] = W / 2;
            g.color(0xFF0C0C0C);
            g.save();
            g.scale(1.08f);
            g.fillPoly(hull, 5);
            g.restore();
            g.color(vt != null ? vt.color : 0xFF6C747C);
            g.fillPoly(hull, 5);
            g.color(0xFF4C545C);
            g.fillRect(-L * 0.1f, -W * 0.25f, L * 0.25f, W * 0.5f);
            g.restore();
            if (z < 0.3f) view.label(g, s.name, sx, sy - W - 5 * dp, 10, 0xFFE8F0FF, Gfx.ALIGN_CENTER, false);
        }
        for (Flight f : flights) {
            float sx = cam.toScreenX(f.x), sy = cam.toScreenY(f.y);
            float a = (float) Math.atan2(f.ty - f.fy, f.tx - f.fx);
            float L = Math.max(18 * dp, 60 * z);
            g.save();
            g.translate(sx, sy);
            g.rotate(a);
            g.color(0x60000000);
            g.fillRect(-L / 2 + 3 * dp, -L / 12 + 4 * dp, L, L / 6);
            g.fillRect(-L / 10 + 3 * dp, -L / 2 + 4 * dp, L / 5, L);
            g.color(0xFFA8AE98);
            g.fillRect(-L / 2, -L / 12, L, L / 6);
            g.fillRect(-L / 10, -L / 2, L / 5, L);
            g.restore();
        }
    }
}
