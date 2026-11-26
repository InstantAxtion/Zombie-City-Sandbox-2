package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * What the player builds that isn't a tile: sandbags (cover for the armed behind them), razor wire (slows the dead
 * and cuts them), machine-gun nests (a gunner who stays put and fires along an arc), ammunition crates and medical
 * kits (restock and patch up the armed near them) and command posts (steadier nerves round them, as a station or a
 * formation's HQ). Placed through Actions (recorded), saved with the game. Barricades and walls are tiles (Tools).
 */
public final class Defences extends Module {
    public static final int SANDBAGS = 0, WIRE = 1, MG = 2, AMMO = 3, MEDKIT = 4, POST = 5, KINDS = 6;
    public static final String[] NAMES = {"Sandbags", "Razor wire", "MG nest", "Ammo crate", "Medkit", "Command post"};
    public static final String[] IDS = {"sandbags", "wire", "mg", "ammo", "medkit", "post"};
    /** How far each reaches: cover, the wire's coil, the gun's range, the crate's and kit's reach, the post's aura. */
    static final float[] REACH = {22, 30, 300, 70, 70, 420};
    /** What each starts with: sandbags' hits, wire's cuts, the gun's rounds, the crate's rounds, the kit's health. */
    static final float[] STOCK = {1, 400, 3000, 4000, 600, 1};
    /** The machine gun's arc either side of where it faces (radians). */
    static final float ARC = 1.05f;

    public static final class Def {
        public int kind;
        public float x, y, fx, fy, stock;
        public int gunner = -1, gunnerUid, kills;
        public float gunT;
        public boolean alive = true;
    }

    public final List<Def> list = new ArrayList<Def>();
    /** How many of each are standing (the queries return at once when there are none). */
    public final int[] count = new int[KINDS];

    public Defences(World w) {
        super(w);
    }

    @Override
    public String id() {
        return "defences";
    }

    /** Places one; facing (fx, fy) for the machine gun's arc. Returns it, or null where it can't stand. */
    public Def place(int kind, float x, float y, float fx, float fy) {
        if (kind < 0 || kind >= KINDS || !w.r.walkablePx(x, y)) return null;
        Def d = new Def();
        d.kind = kind;
        d.x = x;
        d.y = y;
        float l = (float) Math.sqrt(fx * fx + fy * fy);
        d.fx = l > 0 ? fx / l : 0;
        d.fy = l > 0 ? fy / l : -1;
        d.stock = STOCK[kind];
        if (kind == MG) {
            // a gunner from the army's list, in a squad of one that holds the nest
            Squads.Squad s = Tools.placeSquad(w, w.c.unit("gunner") != null ? "gunner" : "rifleman", 1, x, y);
            if (s != null && s.n > 0) {
                s.player = true;
                s.label = "MG nest";
                s.hold = 2;
                s.faceX = d.fx;
                s.faceY = d.fy;
                d.gunner = s.mem[0];
                d.gunnerUid = w.e.uid[d.gunner];
            }
        }
        list.add(d);
        count[kind]++;
        return d;
    }

    /** Takes down everything within r of (x, y); returns how many. */
    public int clear(float x, float y, float r) {
        int n = 0;
        for (Def d : list) {
            if (!d.alive) continue;
            float dx = d.x - x, dy = d.y - y;
            if (dx * dx + dy * dy <= r * r) {
                remove(d);
                n++;
            }
        }
        compact();
        return n;
    }

    /** Takes everything down. */
    public int clearAll() {
        int n = 0;
        for (Def d : list) if (d.alive) {
            remove(d);
            n++;
        }
        compact();
        return n;
    }

    private void remove(Def d) {
        d.alive = false;
        count[d.kind]--;
        if (d.gunner >= 0 && gunnerHere(d)) {
            // the gunner stands down to the nearest squad's orders: free to move again
            int sq = w.e.squad[d.gunner];
            if (sq >= 0) w.sq.get(sq).player = false;
        }
    }

    private void compact() {
        for (int k = list.size() - 1; k >= 0; k--) if (!list.get(k).alive) list.remove(k);
    }

    private boolean gunnerHere(Def d) {
        int g = d.gunner;
        return g >= 0 && g < w.e.top && w.e.loc[g] == Ents.FREE && w.e.uid[g] == d.gunnerUid;
    }

    // ------------------------------------------------------------------ queries (safe from the parallel think: read only)

    /** Behind sandbags: cover for whoever stands here. */
    public boolean sandbagged(float x, float y) {
        if (count[SANDBAGS] == 0) return false;
        for (int k = 0; k < list.size(); k++) {
            Def d = list.get(k);
            if (d.kind != SANDBAGS) continue;
            float dx = d.x - x, dy = d.y - y;
            if (dx * dx + dy * dy < REACH[SANDBAGS] * REACH[SANDBAGS]) return true;
        }
        return false;
    }

    /** How fast the dead can move here (razor wire holds them back): 1 = freely. */
    public float wireSlow(float x, float y) {
        if (count[WIRE] == 0) return 1;
        for (int k = 0; k < list.size(); k++) {
            Def d = list.get(k);
            if (d.kind != WIRE) continue;
            float dx = d.x - x, dy = d.y - y;
            if (dx * dx + dy * dy < REACH[WIRE] * REACH[WIRE]) return 0.3f;
        }
        return 1;
    }

    /** A command post close enough to steady the nerves. */
    public boolean postNear(float x, float y) {
        if (count[POST] == 0) return false;
        for (int k = 0; k < list.size(); k++) {
            Def d = list.get(k);
            if (d.kind != POST) continue;
            float dx = d.x - x, dy = d.y - y;
            if (dx * dx + dy * dy < REACH[POST] * REACH[POST]) return true;
        }
        return false;
    }

    /** The one nearest (x, y) within r, or null. */
    public Def at(float x, float y, float r) {
        Def best = null;
        float bd = r * r;
        for (Def d : list) {
            float dx = d.x - x, dy = d.y - y, dd = dx * dx + dy * dy;
            if (d.alive && dd < bd) {
                bd = dd;
                best = d;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ every step: the machine guns

    @Override
    public void step() {
        if (count[MG] == 0) return;
        Ents e = w.e;
        for (int k = 0; k < list.size(); k++) {
            Def d = list.get(k);
            if (d.kind != MG || !d.alive) continue;
            if (!gunnerHere(d)) continue;
            // the gunner stays in the nest
            int g = d.gunner;
            e.x[g] = d.x;
            e.y[g] = d.y;
            e.vx[g] = 0;
            e.vy[g] = 0;
            d.gunT -= World.DT;
            if (d.gunT > 0 || d.stock <= 0) continue;
            int z = target(d);
            if (z < 0) {
                d.gunT = 0.3f;
                continue;
            }
            fire(d, z);
        }
    }

    /** The nearest of the dead in the gun's arc and range that it can see. */
    private int target(Def d) {
        Ents e = w.e;
        Spatial sp = w.sp;
        float range = REACH[MG], r2 = range * range, cosArc = (float) Math.cos(ARC);
        int cx0 = Math.max(0, (int) ((d.x - range) / Spatial.CELL)), cx1 = Math.min(sp.cw - 1, (int) ((d.x + range) / Spatial.CELL));
        int cy0 = Math.max(0, (int) ((d.y - range) / Spatial.CELL)), cy1 = Math.min(sp.ch - 1, (int) ((d.y + range) / Spatial.CELL));
        int best = -1;
        float bd = r2;
        for (int cy = cy0; cy <= cy1; cy++)
            for (int cx = cx0; cx <= cx1; cx++) {
                int c = sp.cell(cx, cy);
                for (int j = sp.zStart[c]; j < sp.zEnd[c]; j++) {
                    int o = sp.zList[j];
                    if (e.loc[o] != Ents.FREE || e.kind[o] != Ents.ZOMBIE) continue;
                    float dx = e.x[o] - d.x, dy = e.y[o] - d.y, dd = dx * dx + dy * dy;
                    if (dd >= bd || dd < 1) continue;
                    float l = (float) Math.sqrt(dd);
                    if ((dx * d.fx + dy * d.fy) / l < cosArc) continue;
                    if (!w.r.lineOfSight(d.x, d.y, e.x[o], e.y[o])) continue;
                    bd = dd;
                    best = o;
                }
            }
        return best;
    }

    private void fire(Def d, int z) {
        Ents e = w.e;
        Content.Weapon wp = w.c.weapon("mg");
        float rof = wp != null ? wp.rof : 8, range = REACH[MG];
        d.gunT = 1f / rof * (0.85f + w.rng.nextFloat() * 0.3f);
        d.stock--;
        float tx = e.x[z], ty = e.y[z];
        float dist = (float) Math.sqrt((tx - d.x) * (tx - d.x) + (ty - d.y) * (ty - d.y));
        float near = 1 - dist / range;
        w.fx.add(Effects.FLASH, d.x, d.y, (float) Math.atan2(ty - d.y, tx - d.x), 0, 0.06f);
        w.fx.sound(wp != null ? wp.sound : "mg", d.x, d.y);
        w.fx.noise(d.x, d.y, 34, 6);
        w.stats.shots++;
        boolean hit = w.rng.chance(Math.min(0.95f, 0.7f * (0.6f + 0.4f * near) * w.rules.force()));
        w.fx.add(Effects.TRACER, d.x, d.y, tx + (hit ? 0 : (w.rng.nextFloat() - 0.5f) * 20), ty + (hit ? 0 : (w.rng.nextFloat() - 0.5f) * 20), 0.08f);
        if (!hit) return;
        int before = w.stats.zombieKills;
        if (w.rng.chance(0.3f * (0.5f + 0.5f * near)) && (e.flags[z] & Ents.F_HELMET) == 0) w.combat.killZombie(z, d.gunner, Ents.SOLDIER, Combat.CAUSE_GUN);
        else {
            e.hp[z] -= (wp != null ? wp.dmg : 32) * 0.6f;
            e.flags[z] &= ~Ents.F_HELMET;
            e.vx[z] *= 0.3f;
            e.vy[z] *= 0.3f;
            if (e.hp[z] <= 0) w.combat.killZombie(z, d.gunner, Ents.SOLDIER, Combat.CAUSE_GUN);
        }
        d.kills += w.stats.zombieKills - before;
    }

    // ------------------------------------------------------------------ once a second: wire, crates, kits

    @Override
    public void update(float dt) {
        if (w.strategic || list.isEmpty()) return;
        Ents e = w.e;
        Spatial sp = w.sp;
        boolean gone = false;
        for (int k = 0; k < list.size(); k++) {
            Def d = list.get(k);
            if (!d.alive) continue;
            float r = REACH[d.kind];
            int cx0 = Math.max(0, (int) ((d.x - r) / Spatial.CELL)), cx1 = Math.min(sp.cw - 1, (int) ((d.x + r) / Spatial.CELL));
            int cy0 = Math.max(0, (int) ((d.y - r) / Spatial.CELL)), cy1 = Math.min(sp.ch - 1, (int) ((d.y + r) / Spatial.CELL));
            if (d.kind == WIRE) {
                // the wire cuts whoever is caught in it, and wears out doing it
                for (int cy = cy0; cy <= cy1; cy++)
                    for (int cx = cx0; cx <= cx1; cx++) {
                        int c = sp.cell(cx, cy);
                        for (int j = sp.zStart[c]; j < sp.zEnd[c]; j++) {
                            int o = sp.zList[j];
                            if (e.loc[o] != Ents.FREE || e.kind[o] != Ents.ZOMBIE) continue;
                            float dx = e.x[o] - d.x, dy = e.y[o] - d.y;
                            if (dx * dx + dy * dy >= r * r) continue;
                            e.hp[o] -= 4 * dt;
                            d.stock -= dt;
                            if (e.hp[o] <= 0) {
                                w.combat.killZombie(o, -1, Ents.SOLDIER, Combat.CAUSE_BLAST);
                                d.kills++;
                            }
                        }
                    }
                if (d.stock <= 0) {
                    remove(d);
                    gone = true;
                    w.news.radio("Razor wire torn down by the dead", d.x, d.y, News.UNIT);
                }
            } else if (d.kind == AMMO || d.kind == MEDKIT) {
                for (int cy = cy0; cy <= cy1 && d.stock > 0; cy++)
                    for (int cx = cx0; cx <= cx1 && d.stock > 0; cx++) {
                        int c = sp.cell(cx, cy);
                        for (int j = sp.lStart[c]; j < sp.lEnd[c] && d.stock > 0; j++) {
                            int o = sp.lList[j];
                            if (e.loc[o] != Ents.FREE || !e.armed(o)) continue;
                            float dx = e.x[o] - d.x, dy = e.y[o] - d.y;
                            if (dx * dx + dy * dy >= r * r) continue;
                            if (d.kind == AMMO) {
                                Content.Unit u = w.unitOf(o);
                                if (u.weapon == null) continue;
                                int need = Math.min((int) d.stock, u.weapon.ammo - e.ammo[o]);
                                if (need <= 0) continue;
                                e.ammo[o] = (short) (e.ammo[o] + need);
                                d.stock -= need;
                            } else {
                                float need = Math.min(d.stock, Math.min(e.maxHp[o] - e.hp[o], 12 * dt));
                                if (need <= 0) continue;
                                e.hp[o] += need;
                                d.stock -= need;
                            }
                        }
                    }
                if (d.stock <= 0) {
                    remove(d);
                    gone = true;
                    w.news.radio((d.kind == AMMO ? "An ammunition crate" : "A medical kit") + " is empty", d.x, d.y, News.UNIT);
                }
            } else if (d.kind == MG && d.gunner >= 0 && !gunnerHere(d)) {
                d.gunner = -1;
                w.news.radio("The machine-gun nest's gunner is down", d.x, d.y, News.LOSS);
            }
        }
        if (gone) compact();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        if (list.isEmpty() || cam.zoom < 0.12f) return;
        float z = cam.zoom, dp = cam.dp;
        float x0 = cam.x0() - 200, y0 = cam.y0() - 200, x1 = cam.x1() + 200, y1 = cam.y1() + 200;
        for (int k = 0; k < list.size(); k++) {
            Def d = list.get(k);
            if (!d.alive || d.x < x0 || d.x > x1 || d.y < y0 || d.y > y1) continue;
            float sx = cam.toScreenX(d.x), sy = cam.toScreenY(d.y);
            float s = Math.max(5 * dp, 10 * z);
            switch (d.kind) {
                case SANDBAGS:
                    g.color(0xFF2A2418);
                    g.fillRoundRect(sx - s * 1.3f - dp, sy - s * 0.45f - dp, s * 2.6f + 2 * dp, s * 0.9f + 2 * dp, s * 0.4f);
                    g.color(0xFFB89A62);
                    g.fillRoundRect(sx - s * 1.3f, sy - s * 0.45f, s * 2.6f, s * 0.9f, s * 0.4f);
                    g.color(0xFF8A7048);
                    g.line(sx - s * 0.45f, sy - s * 0.45f, sx - s * 0.45f, sy + s * 0.45f, dp);
                    g.line(sx + s * 0.45f, sy - s * 0.45f, sx + s * 0.45f, sy + s * 0.45f, dp);
                    break;
                case WIRE: {
                    float r = Math.max(8 * dp, REACH[WIRE] * z);
                    // a coil of concertina wire: loops round the ring
                    for (int pass = 0; pass < 2; pass++) {
                        g.color(pass == 0 ? 0x70000000 : 0xFFD0D4D8);
                        for (int q = 0; q < 10; q++) {
                            double t = q * Math.PI / 5;
                            g.strokeCircle(sx + (float) Math.cos(t) * r * 0.72f, sy + (float) Math.sin(t) * r * 0.72f, r * 0.3f, (pass == 0 ? 2.2f : 1f) * dp);
                        }
                    }
                    break;
                }
                case MG: {
                    // the arc it covers, faint, then the nest
                    float r = REACH[MG] * z;
                    if (r > 30 * dp) {
                        g.color(d.gunner >= 0 ? 0x1CFFD040 : 0x10FFFFFF);
                        float a0 = (float) Math.atan2(d.fy, d.fx);
                        float[] p = new float[2 * 10];
                        p[0] = sx;
                        p[1] = sy;
                        for (int q = 0; q < 9; q++) {
                            float a = a0 - ARC + q * ARC * 2 / 8;
                            p[2 + q * 2] = sx + (float) Math.cos(a) * r;
                            p[3 + q * 2] = sy + (float) Math.sin(a) * r;
                        }
                        g.fillPoly(p, 10);
                    }
                    g.color(0xFF2A2418);
                    g.fillCircle(sx, sy, s * 1.2f + dp);
                    g.color(0xFFB89A62);
                    g.fillCircle(sx, sy, s * 1.2f);
                    g.color(0xFF303030);
                    g.line(sx, sy, sx + d.fx * s * 1.8f, sy + d.fy * s * 1.8f, 2.2f * dp);
                    break;
                }
                case AMMO:
                case MEDKIT: {
                    g.color(0xFF101010);
                    g.fillRect(sx - s * 0.7f - dp, sy - s * 0.5f - dp, s * 1.4f + 2 * dp, s + 2 * dp);
                    g.color(d.kind == AMMO ? 0xFF5E6E3A : 0xFFF0F0F0);
                    g.fillRect(sx - s * 0.7f, sy - s * 0.5f, s * 1.4f, s);
                    g.color(d.kind == AMMO ? 0xFFE0C040 : 0xFFD83030);
                    if (d.kind == MEDKIT) {
                        g.fillRect(sx - s * 0.12f, sy - s * 0.38f, s * 0.24f, s * 0.76f);
                        g.fillRect(sx - s * 0.38f, sy - s * 0.12f, s * 0.76f, s * 0.24f);
                    } else g.fillRect(sx - s * 0.5f, sy - s * 0.1f, s, s * 0.2f);
                    break;
                }
                case POST: {
                    float r = REACH[POST] * z;
                    if (r > 40 * dp) {
                        g.color(0x2860A0FF);
                        g.strokeCircle(sx, sy, r, 1.5f * dp);
                    }
                    view.flag(g, sx, sy, 0xFF60A0FF);
                    break;
                }
                default:
            }
        }
    }

    // ------------------------------------------------------------------ saves

    @Override
    public void save(DataOutputStream o) throws IOException {
        int n = 0;
        for (Def d : list) if (d.alive) n++;
        o.writeInt(n);
        for (Def d : list) {
            if (!d.alive) continue;
            o.writeInt(d.kind);
            o.writeFloat(d.x);
            o.writeFloat(d.y);
            o.writeFloat(d.fx);
            o.writeFloat(d.fy);
            o.writeFloat(d.stock);
            o.writeInt(d.gunner);
            o.writeInt(d.gunnerUid);
            o.writeInt(d.kills);
            o.writeFloat(d.gunT);
        }
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        list.clear();
        java.util.Arrays.fill(count, 0);
        int n = in.readInt();
        for (int k = 0; k < n; k++) {
            Def d = new Def();
            d.kind = in.readInt();
            d.x = in.readFloat();
            d.y = in.readFloat();
            d.fx = in.readFloat();
            d.fy = in.readFloat();
            d.stock = in.readFloat();
            d.gunner = in.readInt();
            d.gunnerUid = in.readInt();
            d.kills = in.readInt();
            d.gunT = in.readFloat();
            if (d.kind < 0 || d.kind >= KINDS) continue;
            list.add(d);
            count[d.kind]++;
        }
    }
}
