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
    /** Heat of each building's fire, 0 (none) to 1. */
    public final float[] heat;
    private final float[] burnT, eta;
    private final int[] crews, crewFrom, engine;
    private final int[] secFires;
    public float wind; // radians: where the wind blows to
    private final List<Integer> burning = new ArrayList<Integer>();
    private int unitFF = -1, vEngine = -1;
    public int started, collapsed, putOut;
    private float news;

    public Fires(World w) {
        super(w);
        int n = w.r.bCount;
        heat = new float[n];
        burnT = new float[n];
        eta = new float[n];
        crews = new int[n];
        crewFrom = new int[n];
        engine = new int[n];
        java.util.Arrays.fill(crewFrom, -1);
        java.util.Arrays.fill(engine, -1);
        secFires = new int[w.sec.n];
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

    /** Sets a building alight (looting, the society systems...). */
    public void ignite(int b, float h) {
        if (b < 0 || b >= heat.length || w.r.bfloors[b] <= 0) return;
        if (heat[b] <= 0) {
            burning.add(b);
            started++;
            if (w.r.bres[b] > 0 || w.r.bkind[b] >= Region.B_POLICE) {
                if (w.time - news > 120) {
                    news = (float) w.time;
                    w.news.radio("Fire reported: " + (vowel(building(w.r.bkind[b])) ? "an " : "a ") + building(w.r.bkind[b]) + " burning" + placeOf(b), w.doorX[b], w.doorY[b], News.CALL);
                }
            }
        }
        heat[b] = Math.max(heat[b], Math.min(1, h));
    }

    private String placeOf(int b) {
        int st = w.r.bset[b];
        return st >= 0 ? " in " + w.r.settlements.get(st).name : " in the countryside";
    }

    /** A building near (x, y) may catch (crashes, wrecks). */
    public void igniteNear(float x, float y, float chance) {
        int tx = (int) (x / Tile.SIZE), ty = (int) (y / Tile.SIZE);
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++) {
                if (!w.r.in(tx + dx, ty + dy)) continue;
                int b = w.r.building((ty + dy) * w.r.W + tx + dx);
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
                int b = w.r.building((ty + dy) * w.r.W + tx + dx);
                if (b < 0 || b == last || heat[b] > 0) continue;
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
        java.util.Arrays.fill(secFires, 0);
        float grow = 1f / w.c.rule("fireGrow", 600), burnout = w.c.rule("fireBurnout", 900), spread = w.c.rule("fireSpread", 0.004f);
        float crewRate = w.c.rule("fireCrew", 0.0012f), rain = w.c.rule("fireRain", 0.004f);
        float wx = (float) Math.cos(wind), wy = (float) Math.sin(wind);
        int n = burning.size();
        for (int k = 0; k < n; k++) {
            int b = burning.get(k);
            if (heat[b] <= 0) continue;
            float dx0 = w.doorX[b] >= 0 ? w.doorX[b] : (r.bx[b] + r.bw[b] / 2f) * 16, dy0 = w.doorY[b] >= 0 ? w.doorY[b] : (r.by[b] + r.bh[b] / 2f) * 16;
            int s = r.sectorOfPx(dx0, dy0);
            // the crew on its way, or at work
            if (crews[b] == 0) dispatch(b, dx0, dy0);
            float h = heat[b] + grow * dt;
            if (crews[b] > 0) {
                eta[b] -= dt;
                if (eta[b] <= 0) {
                    // the dead about: the crew can't work, and some don't come back
                    if (sec.zIn[s] + w.sp.secZ[s] > 25) {
                        int lost = Math.min(crews[b], 1 + crews[b] / 4);
                        crews[b] -= lost;
                        w.stats.crewDeaths += lost;
                        sec.zIn[s] += lost / 2;
                        recall(b);
                    } else {
                        // no water in the mains: the crews have only what the engines carry
                        int bs = r.bset[b];
                        float water = bs >= 0 && w.infra.waterOut(bs) ? 0.3f : 1f;
                        h -= crews[b] / 4f * crewRate * water * dt + grow * dt;
                    }
                }
            }
            if (w.weather.wet()) h -= rain * dt * (w.weather.state == Weather.STORM ? 2 : 1);
            heat[b] = Math.max(0, Math.min(1, h));
            if (heat[b] <= 0) {
                putOut++;
                recall(b);
                burnT[b] = 0;
                continue;
            }
            secFires[s]++;
            burnT[b] += dt * heat[b];
            // into the neighbours, with the wind
            if (heat[b] > 0.5f && Rng.unit(w.seed, w.tick, b * 13L) < spread * heat[b] * dt) spreadFrom(b, wx, wy);
            // at night a fire is a light; near people it's noise as well
            if (!w.strategic && w.night() && (sec.hot[s] > 0 || sec.cam[s]) && Rng.unit(w.seed, w.tick, b * 5L) < 0.1f * dt)
                w.fx.noise(dx0, dy0, 16, 4);
            if (burnT[b] > burnout) collapse(b, s);
        }
        for (int k = burning.size() - 1; k >= 0; k--) if (heat[burning.get(k)] <= 0) burning.remove(k);
        // engines home again go back in the station
        for (int v = 0; v < w.veh.top; v++) {
            if (w.veh.state[v] == Vehicles.V_PARKED && w.veh.type[v] == vEngine && w.veh.role[v] == Vehicles.R_SQUAD && w.veh.squad[v] < 0) {
                int h = w.veh.home[v];
                if (h >= 0) w.r.facilities.get(h).vpool[vEngine]++;
                w.veh.state[v] = Vehicles.V_NONE;
            }
        }
    }

    /** Fires nobody is minding: where the power and water are gone and people are frightened. */
    private void untended(float dt) {
        float rate = w.c.rule("fireUntended", 0.5f) / 100000f / 60f;
        Sectors sec = w.sec;
        for (int s = 0; s < sec.n; s++) {
            int p = sec.popIn[s];
            if (p < 200 || sec.grid[s] < 0) continue;
            boolean dark = !w.strat.lit(s) || (sec.settlement[s] >= 0 && w.infra.waterOut(sec.settlement[s]));
            if (!dark && sec.panic[s] < 0.5f) continue;
            float chance = p * rate * dt * (dark ? 1 : 0.3f) * (w.weather.wet() ? 0.3f : 1);
            if (Rng.unit(w.seed, w.tick, s * 41L + 9) >= chance) continue;
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
            float cx = r.bx[b] + r.bw[b] / 2f + (float) Math.cos(ang) * (r.bw[b] / 2f + d);
            float cy = r.by[b] + r.bh[b] / 2f + (float) Math.sin(ang) * (r.bh[b] / 2f + d);
            int tx = (int) cx, ty = (int) cy;
            if (!r.in(tx, ty)) continue;
            int o = r.building(ty * r.W + tx);
            if (o >= 0 && o != b && heat[o] <= 0) {
                ignite(o, 0.15f);
                return;
            }
        }
    }

    /** One building of a kind, as a sentence says it: "apartment block", "office block", "house". */
    static String building(int kind) {
        String n = Region.BNAMES[kind].toLowerCase();
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
        int lost = Math.min(w.sec.popIn[s], Math.max(0, r.bres[b] / 20));
        w.sec.popIn[s] -= lost;
        w.stats.civDeaths += lost;
        w.stats.lossBy[4] += lost;
        heat[b] = 0;
        burnT[b] = 0;
        recall(b);
        collapsed++;
        w.stats.buildingsLost++;
        r.destroyBuilding(b);
        if (lost > 50) w.news.radio("A burning " + building(r.bkind[b]) + " has collapsed" + placeOf(b) + " (" + News.num(lost) + " dead)", w.doorX[b], w.doorY[b], News.LOSS);
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
        int n = heat[b] > 0.7f && best.pool[unitFF] >= 8 ? 8 : 4;
        best.pool[unitFF] -= n;
        crews[b] = n;
        crewFrom[b] = best.id;
        eta[b] = (float) Math.sqrt(bd) / 150f + 30;
        int s = w.r.sectorOfPx(x, y);
        if (!w.strategic && vEngine >= 0 && best.vpool[vEngine] > 0 && (w.sec.cam[s] || w.sec.hot[s] > 0)) {
            best.vpool[vEngine]--;
            int v = w.veh.add(w.c.vtypes.get(vEngine), best.x, best.y, 0, best.id);
            w.veh.driveTo(v, x, y, 0);
            w.veh.siren[v] = true;
            engine[b] = v;
        }
    }

    /** The crew goes home (back in the station's pool); its engine drives back. */
    private void recall(int b) {
        if (crewFrom[b] >= 0 && crews[b] > 0) {
            Region.Facility f = w.r.facilities.get(crewFrom[b]);
            if (f.pool != null) f.pool[unitFF] += crews[b];
        }
        crews[b] = 0;
        crewFrom[b] = -1;
        int v = engine[b];
        engine[b] = -1;
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
            o.writeFloat(heat[b]);
            o.writeFloat(burnT[b]);
            o.writeFloat(eta[b]);
            o.writeInt(crews[b]);
            o.writeInt(crewFrom[b]);
            o.writeInt(engine[b]);
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
            heat[b] = in.readFloat();
            burnT[b] = in.readFloat();
            eta[b] = in.readFloat();
            crews[b] = in.readInt();
            crewFrom[b] = in.readInt();
            engine[b] = in.readInt();
        }
    }

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        Region r = w.r;
        float z = cam.zoom, t = view.time, dp = cam.dp;
        float x0 = cam.x0() - 400, y0 = cam.y0() - 400, x1 = cam.x1() + 400, y1 = cam.y1() + 400;
        boolean dark = w.daylight() < 0.5f;
        float wxv = (float) Math.cos(wind), wyv = (float) Math.sin(wind);
        for (int k0 = 0; k0 < burning.size(); k0++) {
            int b = burning.get(k0);
            float h = heat[b];
            if (h <= 0) continue;
            float wx = (r.bx[b] + r.bw[b] / 2f) * 16, wy = (r.by[b] + r.bh[b] / 2f) * 16;
            if (wx < x0 || wx > x1 || wy < y0 || wy > y1) continue;
            float sx = cam.toScreenX(wx), sy = cam.toScreenY(wy);
            float size = Math.max((float) Math.sqrt(r.bw[b] * r.bh[b]) * 16 * z, 6 * dp);
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
