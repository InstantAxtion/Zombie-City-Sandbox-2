package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Army engineers and the services behind the front: a formation with engineers that has held its ground a while sets
 * up a forward operating base (an ammunition and fuel point, and a field hospital that sends wounded back to the
 * line), and engineers wall off cordon streets with barricades the dead can't cross.
 */
public final class Engineers extends Module {
    private float[] holdT = new float[0], wounded = new float[0];
    private int[] lossesSeen = new int[0];
    private final List<long[]> built = new ArrayList<long[]>();
    private float wallT;
    public int fobs, barricades, recovered;

    public Engineers(World w) {
        super(w);
    }

    @Override
    public String id() {
        return "engineers";
    }

    private void grow() {
        int n = w.forces.formations.size();
        if (holdT.length >= n) return;
        int old = holdT.length;
        holdT = java.util.Arrays.copyOf(holdT, n);
        wounded = java.util.Arrays.copyOf(wounded, n);
        lossesSeen = java.util.Arrays.copyOf(lossesSeen, n);
        for (int i = old; i < n; i++) lossesSeen[i] = w.forces.formations.get(i).losses;
    }

    private int engineers(Forces.Formation f) {
        Content.Unit u = w.c.unit("engineer");
        return u == null || f.hq.pool == null ? 0 : f.hq.pool[u.index];
    }

    private Region.Facility fobNear(float x, float y, float reach) {
        Region.Facility best = null;
        float bd = reach * reach;
        for (Region.Facility f : w.r.facilities) {
            if (f.kind != Region.F_FOB || !w.strat.online(f.id)) continue;
            float dx = f.x - x, dy = f.y - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = f;
            }
        }
        return best;
    }

    @Override
    public void update(float dt) {
        grow();
        float fobAfter = w.c.rule("fobAfter", 600);
        float woundShare = w.c.rule("wounded", 0.3f), recover = w.c.rule("recoverHours", 6) * 60f;
        List<Forces.Formation> fs = w.forces.formations;
        for (int i = 0; i < fs.size(); i++) {
            Forces.Formation f = fs.get(i);
            int d = f.losses - lossesSeen[i];
            lossesSeen[i] = f.losses;
            if (!f.alive) continue;
            if (d > 0) wounded[i] += d * woundShare;
            holdT[i] = f.state == Forces.HOLDING ? holdT[i] + dt : 0;
            Region.Facility fob = fobNear(f.hq.x, f.hq.y, 2500);
            // a forward base: the engineers dig in once the line has held a while
            if (fobCool.length < fs.size()) fobCool = java.util.Arrays.copyOf(fobCool, fs.size());
            if (fobCool[i] > 0) fobCool[i] -= dt;
            // behind the line, not in the middle of the dead: back from the front towards home, on a road
            float[] p = fob == null && holdT[i] >= fobAfter && engineers(f) > 0 && fobCool[i] <= 0 ? safeSpot(f) : null;
            if (p != null) {
                int n = w.r.roads.nearestNode(p[0], p[1], 600);
                float x = n >= 0 ? w.r.roads.nx[n] : p[0], y = n >= 0 ? w.r.roads.ny[n] : p[1];
                fobCool[i] = w.c.rule("fobCooldown", 1440);
                fob = w.r.addFacility(Region.F_FOB, -1, x, y, "FOB " + Forces.shortName(f));
                fob.node = n;
                fob.ammo = 15000;
                fob.fuel = 100;
                fob.med = 500;
                w.strat.extendFacilities();
                fobs++;
                w.news.count("fob", f.name + ": engineers have set up a forward base (ammunition, fuel, a field hospital)|Engineers set up %d more forward base{s}", 1, x, y);
            }
            boolean care = fob != null || (f.home >= 0 && near(f, w.r.facilities.get(f.home), 2500));
            if (fob != null && f.hq.ammo < 20000 && fob.ammo > 0) {
                float give = Math.min(fob.ammo, 200 * dt);
                fob.ammo -= give;
                f.hq.ammo += give;
            }
            // the field hospital sends the wounded back to the line
            if (care && wounded[i] >= 1 && f.t.squads.length > 0) {
                float k = wounded[i] * dt / recover;
                int back = (int) k + (Rng.unit(w.seed, w.tick, i * 7L + 3) < k % 1f ? 1 : 0);
                back = Math.min(back, (int) wounded[i]);
                if (back > 0) {
                    Content.Unit u = f.t.squads[0].units[0];
                    f.hq.pool[u.index] += back;
                    wounded[i] -= back;
                    recovered += back;
                }
            }
        }
        wallT -= dt;
        if (wallT <= 0 && !w.strategic) {
            wallT = 120;
            barricades();
        }
    }

    private float[] fobCool = new float[0];

    /** A quiet place behind a formation: no dead in that sector or next to it. Null if there's none close. */
    private float[] safeSpot(Forces.Formation f) {
        Sectors sec = w.sec;
        float hx = f.home >= 0 ? w.r.facilities.get(f.home).x : sec.cx(sec.n / 2), hy = f.home >= 0 ? w.r.facilities.get(f.home).y : sec.cy(sec.n / 2);
        float dx = hx - f.hq.x, dy = hy - f.hq.y, d = (float) Math.sqrt(dx * dx + dy * dy) + 1;
        for (int k = 1; k <= 4; k++) {
            float x = f.hq.x + dx / d * 600 * k, y = f.hq.y + dy / d * 600 * k;
            if (x < 0 || y < 0 || x >= w.r.px() || y >= w.r.px()) break;
            int s = w.r.sectorOfPx(x, y);
            boolean quiet = sec.zIn[s] + w.sp.secZ[s] == 0;
            for (int q = 0; q < 8 && quiet; q++) {
                int t = sec.neighbour(s, q);
                if (t >= 0 && sec.zIn[t] + w.sp.secZ[t] > 20) quiet = false;
            }
            if (quiet && w.r.walkablePx(x, y)) return new float[]{x, y};
        }
        return null;
    }

    private boolean near(Forces.Formation f, Region.Facility g, float reach) {
        float dx = f.hq.x - g.x, dy = f.hq.y - g.y;
        return dx * dx + dy * dy < reach * reach;
    }

    /** Engineers wall off the streets at manned cordon posts, on the side facing the dead. */
    private void barricades() {
        int max = (int) w.c.rule("maxBarricades", 40);
        if (barricades >= max) return;
        int walled = 0;
        for (Dispatch.Post p : w.disp.posts) {
            if (p.squad < 0 || barricades >= max || walled >= 3) continue;
            long key = ((long) (int) p.x << 32) ^ (int) p.y;
            boolean done = false;
            for (long[] b : built) if (b[0] == key) done = true;
            if (done) continue;
            boolean have = false;
            for (Forces.Formation f : w.forces.formations) {
                if (!f.alive || Forces.travelling(f) || engineers(f) <= 0) continue;
                float dx = f.hq.x - p.x, dy = f.hq.y - p.y;
                if (dx * dx + dy * dy < 6000f * 6000f) have = true;
            }
            if (!have) continue;
            if (wall(p.x + p.fx * 28, p.y + p.fy * 28, -p.fy, p.fx, p.fx, p.fy)) {
                built.add(new long[]{key});
                barricades++;
                walled++;
                if (barricades <= 3) w.news.radio("Engineers have barricaded a street on the cordon", p.x, p.y, News.COMMAND);
            }
        }
    }

    /**
     * A wall across the street at (x, y) along (ax, ay), the dead being towards (fx, fy); true if anything was
     * built. Only across the street and its pavements; anyone standing there steps to their own side first.
     */
    private boolean wall(float x, float y, float ax, float ay, float fx, float fy) {
        Region r = w.r;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int side = -1; side <= 1; side += 2)
            for (int k = side < 0 ? 1 : 0; k <= 9; k++) {
                float px = x + ax * side * k * Tile.SIZE, py = y + ay * side * k * Tile.SIZE;
                int tx = (int) (px / Tile.SIZE), ty = (int) (py / Tile.SIZE);
                int t = r.t(tx, ty);
                if (t != Tile.ROAD && t != Tile.PAVEMENT && t != Tile.BARRICADE) break;
                stepAside(tx, ty, fx, fy);
                r.set(tx, ty, Tile.BARRICADE);
                x0 = Math.min(x0, tx);
                y0 = Math.min(y0, ty);
                x1 = Math.max(x1, tx);
                y1 = Math.max(y1, ty);
            }
        if (x1 < 0) return false;
        r.rebuildNav(x0 - 1, y0 - 1, x1 + 1, y1 + 1);
        w.sec.connectivity();
        w.r.closeBarricadedRoads(x0, y0, x1, y1);
        return true;
    }

    /** Everyone on a tile about to be walled moves off it: the living to the near side, the dead to theirs. */
    private void stepAside(int tx, int ty, float fx, float fy) {
        Ents e = w.e;
        Spatial sp = w.sp;
        float cx = tx * Tile.SIZE + Tile.SIZE / 2f, cy = ty * Tile.SIZE + Tile.SIZE / 2f;
        int c = sp.cellOf(cx, cy);
        for (int pass = 0; pass < 2; pass++) {
            int a = pass == 0 ? sp.lStart[c] : sp.zStart[c], b = pass == 0 ? sp.lEnd[c] : sp.zEnd[c];
            int[] L = pass == 0 ? sp.lList : sp.zList;
            for (int j = a; j < b; j++) {
                int i = L[j];
                if (e.loc[i] != Ents.FREE || (int) Math.floor(e.x[i] / Tile.SIZE) != tx || (int) Math.floor(e.y[i] / Tile.SIZE) != ty) continue;
                float dir = e.kind[i] == Ents.ZOMBIE ? 1 : -1;
                for (float d = 20; d <= 52; d += 16) {
                    float nx = cx + fx * dir * d, ny = cy + fy * dir * d;
                    if (w.r.walkablePx(nx, ny)) {
                        e.x[i] = nx;
                        e.y[i] = ny;
                        break;
                    }
                }
            }
        }
    }

    @Override
    public void save(DataOutputStream o) throws IOException {
        o.writeInt(holdT.length);
        for (int i = 0; i < holdT.length; i++) {
            o.writeFloat(holdT[i]);
            o.writeFloat(wounded[i]);
            o.writeInt(lossesSeen[i]);
        }
        o.writeInt(built.size());
        for (long[] b : built) o.writeLong(b[0]);
        o.writeFloat(wallT);
        o.writeInt(fobs);
        o.writeInt(fobCool.length);
        for (float c : fobCool) o.writeFloat(c);
        o.writeInt(barricades);
        o.writeInt(recovered);
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        int n = in.readInt();
        holdT = new float[n];
        wounded = new float[n];
        lossesSeen = new int[n];
        for (int i = 0; i < n; i++) {
            holdT[i] = in.readFloat();
            wounded[i] = in.readFloat();
            lossesSeen[i] = in.readInt();
        }
        built.clear();
        n = in.readInt();
        for (int k = 0; k < n; k++) built.add(new long[]{in.readLong()});
        wallT = in.readFloat();
        fobs = in.readInt();
        fobCool = new float[in.readInt()];
        for (int i = 0; i < fobCool.length; i++) fobCool[i] = in.readFloat();
        barricades = in.readInt();
        recovered = in.readInt();
    }

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        if (cam.zoom > 1.2f) return;
        float dp = cam.dp, h = 8 * dp;
        for (Region.Facility f : w.r.facilities) {
            if (f.kind != Region.F_FOB) continue;
            float x = cam.toScreenX(f.x), y = cam.toScreenY(f.y);
            if (x < -30 * dp || y < -30 * dp || x > cam.sw + 30 * dp || y > cam.sh + 30 * dp) continue;
            boolean on = w.strat.online(f.id);
            g.color(0xE0000000);
            g.fillRect(x - h - 1.5f * dp, y - h - 1.5f * dp, h * 2 + 3 * dp, h * 2 + 3 * dp);
            g.color(on ? 0xFF8A8250 : 0xFF5A3030);
            g.fillRect(x - h, y - h, h * 2, h * 2);
            g.color(0xFFE04040);
            g.fillRect(x - h * 0.65f, y - h * 0.2f, h * 1.3f, h * 0.4f);
            g.fillRect(x - h * 0.2f, y - h * 0.65f, h * 0.4f, h * 1.3f);
            if (cam.zoom > 0.3f) view.label(g, f.name, x, y + h + 13 * dp, 10, 0xFFF0F0F0, Gfx.ALIGN_CENTER, false);
        }
    }
}
