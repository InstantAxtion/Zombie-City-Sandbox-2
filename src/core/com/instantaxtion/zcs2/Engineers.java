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
        onSite = w.rule("engineersOnSite", 0) > 0;
    }

    // ------------------------------------------------------------------ work on site (rule engineersOnSite)

    public static final int J_BARRICADE = 0, J_WALL = 1, J_FOB = 2, J_BRIDGE = 3;
    public static final String[] J_NAMES = {"a barricade", "a wall", "a forward base", "blowing a bridge"};

    /** A job: what, where (and which way the street runs, the dead's side), the work done, the squad doing it. */
    public static final class Job {
        public int kind, squad = -1, formation = -1, edge = -1, tries;
        public float x, y, ax, ay, fx, fy, done, need, idle;
        public long key;
        public boolean player, stopped;
    }

    /** Engineers drive out and build over time, and the dead can stop them (else everything is built at once, as before). */
    final boolean onSite;
    public final List<Job> jobs = new ArrayList<Job>();
    public int jobsDone, jobsStopped;

    private float need(int kind) {
        switch (kind) {
            case J_WALL: return w.rule("buildWall", 90);
            case J_FOB: return w.rule("buildFob", 240);
            case J_BRIDGE: return w.rule("buildBridge", 60);
            default: return w.rule("buildBarricade", 40);
        }
    }

    /** The player's order to the engineers: a wall across the street here, a forward base here, or blow this bridge. */
    public boolean order(int kind, float x, float y) {
        Job j = new Job();
        j.kind = kind;
        j.player = true;
        j.x = x;
        j.y = y;
        if (kind == J_BRIDGE) {
            j.edge = Tools.bridgeAt(w, x, y);
            if (j.edge < 0) return false;
            RoadGraph g = w.r.roads;
            // the near end of the bridge, where it meets the road
            int a = g.ea[j.edge], b = g.eb[j.edge];
            j.x = g.nx[a];
            j.y = g.ny[a];
            if (Math.abs(g.nx[b] - x) + Math.abs(g.ny[b] - y) < Math.abs(g.nx[a] - x) + Math.abs(g.ny[a] - y)) {
                j.x = g.nx[b];
                j.y = g.ny[b];
            }
        } else if (kind == J_WALL || kind == J_BARRICADE) {
            if (!Tile.walkable(w.r.tileAtPx(x, y))) return false;
            // across the way the street runs here
            int tx = (int) (x / Tile.SIZE), ty = (int) (y / Tile.SIZE), h = 0, v = 0;
            for (int k = -4; k <= 4; k++) {
                if (Tile.roadish(w.r.t(tx + k, ty))) h++;
                if (Tile.roadish(w.r.t(tx, ty + k))) v++;
            }
            if (h >= v) {
                j.ax = 0;
                j.ay = 1;
                j.fx = 1;
                j.fy = 0;
            } else {
                j.ax = 1;
                j.ay = 0;
                j.fx = 0;
                j.fy = 1;
            }
        } else if (!Tile.walkable(w.r.tileAtPx(x, y))) return false;
        j.need = need(kind);
        if (!onSite) {
            // (without work on site the order is carried out at once)
            finish(j);
            return true;
        }
        jobs.add(j);
        w.news.radio("Engineers: on our way to build " + J_NAMES[kind].replace("blowing", "blow"), j.x, j.y, News.UNIT);
        return true;
    }

    /** Somewhere engineers can come from for a job at (x, y): the nearest formation with engineers, else a base. */
    private Region.Facility engineerHome(float x, float y, int[] formation) {
        Region.Facility best = null;
        float bd = Float.MAX_VALUE;
        Content.Unit u = w.c.unit("engineer");
        if (u == null) return null;
        List<Forces.Formation> fs = w.forces.formations;
        for (int i = 0; i < fs.size(); i++) {
            Forces.Formation f = fs.get(i);
            if (!f.alive || Forces.travelling(f) || engineers(f) <= 0) continue;
            float dx = f.hq.x - x, dy = f.hq.y - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = f.hq;
                formation[0] = i;
            }
        }
        for (Region.Facility f : w.r.facilities) {
            if (f.kind != Region.F_BASE || f.pool == null || u.index >= f.pool.length || f.pool[u.index] <= 0 || !w.strat.online(f.id)) continue;
            float dx = f.x - x, dy = f.y - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = f;
                formation[0] = -1;
            }
        }
        return best;
    }

    /** The work goes on: a squad sent for each job, working while it's there and the dead keep off. */
    private void work(float dt) {
        Ents e = w.e;
        for (int k = jobs.size() - 1; k >= 0; k--) {
            Job j = jobs.get(k);
            if (w.strategic) {
                // as numbers: the work is done in the time it takes
                j.done += dt;
                if (j.done >= j.need) {
                    jobs.remove(k);
                    finish(j);
                }
                continue;
            }
            Squads.Squad s = j.squad >= 0 && j.squad < w.sq.list.size() ? w.sq.get(j.squad) : null;
            if (s != null && (!s.alive || s.task != TASK_BUILD)) {
                s = null;
                j.squad = -1;
                if (j.done > 0) {
                    jobsStopped++;
                    w.news.radio("Engineers: the work on " + J_NAMES[j.kind] + " has stopped: the squad is lost", j.x, j.y, News.LOSS);
                }
            }
            if (s == null) {
                j.idle += dt;
                if (j.tries >= 4 || j.idle > 1800) {
                    jobs.remove(k);
                    continue;
                }
                if (j.idle < 20 && j.tries > 0) continue;
                int[] fm = {-1};
                Region.Facility home = engineerHome(j.x, j.y, fm);
                Content.SquadT t = w.c.squadT("engineers");
                if (home == null || t == null) continue;
                Squads.Squad ns = w.forces.deploy(home, t);
                j.idle = 0;
                j.tries++;
                if (ns == null) continue;
                ns.task = TASK_BUILD;
                w.sq.order(ns, Squads.O_HOLD, j.x, j.y);
                j.squad = ns.id;
                j.formation = fm[0];
                continue;
            }
            int lead = s.leader(e);
            if (lead < 0 || e.loc[lead] != Ents.FREE) continue;
            float d = Math.abs(e.x[lead] - j.x) + Math.abs(e.y[lead] - j.y);
            if (d > 120) continue;
            boolean dead = w.sp.zombiesNear(e, j.x, j.y, 140, 1) > 0;
            if (dead) {
                if (!j.stopped) {
                    j.stopped = true;
                    jobsStopped++;
                    w.news.radio("Engineers: work on " + J_NAMES[j.kind] + " stopped, the dead are on us", j.x, j.y, News.UNIT);
                }
                continue;
            }
            j.stopped = false;
            j.done += dt * Math.min(1.5f, s.n / 4f);
            if (j.done >= j.need) {
                jobs.remove(k);
                finish(j);
                s.task = -1;
                w.sq.goHome(s);
            }
        }
    }

    static final int TASK_BUILD = -5;

    /** The job is done: built (or blown). */
    private void finish(Job j) {
        jobsDone++;
        switch (j.kind) {
            case J_BRIDGE:
                w.strat.blowBridge(j.edge, true);
                w.news.radio("Engineers: the bridge is down", j.x, j.y, News.COMMAND);
                break;
            case J_FOB: {
                Forces.Formation f = j.formation >= 0 && j.formation < w.forces.formations.size() ? w.forces.formations.get(j.formation) : null;
                makeFob(f, j.x, j.y);
                break;
            }
            default: {
                boolean ok = wall(j.x, j.y, j.ax, j.ay, j.fx, j.fy, j.kind == J_WALL ? Tile.WALL : Tile.BARRICADE);
                if (ok && !j.player) {
                    built.add(new long[]{j.key});
                    barricades++;
                    if (barricades <= 3) w.news.radio("Engineers have barricaded a street on the cordon", j.x, j.y, News.COMMAND);
                } else if (ok) w.news.radio("Engineers: " + (j.kind == J_WALL ? "the wall is up" : "the barricade is up"), j.x, j.y, News.COMMAND);
            }
        }
    }

    /** A forward base at (x, y), set up by formation f's engineers (or the player's). */
    private void makeFob(Forces.Formation f, float x, float y) {
        int n = w.r.roads.nearestNode(x, y, 600);
        Region.Facility fob = w.r.addFacility(Region.F_FOB, -1, x, y, "FOB " + (f != null ? Forces.shortName(f) : (fobs + 1)));
        fob.node = n;
        fob.ammo = 15000;
        fob.fuel = 100;
        fob.med = 500;
        w.strat.extendFacilities();
        fobs++;
        w.news.count("fob", (f != null ? f.name : "Engineers") + ": engineers have set up a forward base (ammunition, fuel, a field hospital)|Engineers set up %d more forward base{s}", 1, x, y);
    }

    /** Is a job already planned for this key (a cordon post) or place? */
    private boolean planned(long key, int kind, float x, float y) {
        for (Job j : jobs) if (key != 0 && j.key == key || j.kind == kind && Math.abs(j.x - x) + Math.abs(j.y - y) < 300) return true;
        return false;
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
            if (p != null && onSite) {
                // the engineers drive out and dig in over time
                int n = w.r.roads.nearestNode(p[0], p[1], 600);
                float x = n >= 0 ? w.r.roads.nx[n] : p[0], y = n >= 0 ? w.r.roads.ny[n] : p[1];
                fobCool[i] = w.c.rule("fobCooldown", 1440);
                if (!planned(0, J_FOB, x, y)) {
                    Job j = new Job();
                    j.kind = J_FOB;
                    j.x = x;
                    j.y = y;
                    j.formation = i;
                    j.need = need(J_FOB);
                    jobs.add(j);
                }
                p = null;
            }
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
        if (onSite) work(dt);
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
            if (onSite) {
                // a job for the engineers: they come and build it
                if (planned(key, J_BARRICADE, -1e9f, -1e9f)) continue;
                Job j = new Job();
                j.kind = J_BARRICADE;
                j.key = key;
                j.x = p.x + p.fx * 28;
                j.y = p.y + p.fy * 28;
                j.ax = -p.fy;
                j.ay = p.fx;
                j.fx = p.fx;
                j.fy = p.fy;
                j.need = need(J_BARRICADE);
                jobs.add(j);
                walled++;
                continue;
            }
            if (wall(p.x + p.fx * 28, p.y + p.fy * 28, -p.fy, p.fx, p.fx, p.fy, Tile.BARRICADE)) {
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
    private boolean wall(float x, float y, float ax, float ay, float fx, float fy, byte tile) {
        Region r = w.r;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int side = -1; side <= 1; side += 2)
            for (int k = side < 0 ? 1 : 0; k <= 9; k++) {
                float px = x + ax * side * k * Tile.SIZE, py = y + ay * side * k * Tile.SIZE;
                int tx = (int) (px / Tile.SIZE), ty = (int) (py / Tile.SIZE);
                int t = r.t(tx, ty);
                if (t != Tile.ROAD && t != Tile.PAVEMENT && t != Tile.BARRICADE && !(tile == Tile.WALL && t == Tile.WALL)) break;
                stepAside(tx, ty, fx, fy);
                r.set(tx, ty, tile);
                x0 = Math.min(x0, tx);
                y0 = Math.min(y0, ty);
                x1 = Math.max(x1, tx);
                y1 = Math.max(y1, ty);
            }
        if (x1 < 0) return false;
        r.rebuildNav(x0 - 1, y0 - 1, x1 + 1, y1 + 1);
        w.sec.connectivity(x0 - 1, y0 - 1, x1 + 1, y1 + 1);
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
        if (onSite) {
            o.writeInt(jobsDone);
            o.writeInt(jobsStopped);
            o.writeInt(jobs.size());
            for (Job j : jobs) {
                o.writeInt(j.kind); o.writeInt(j.squad); o.writeInt(j.formation); o.writeInt(j.edge); o.writeInt(j.tries);
                o.writeFloat(j.x); o.writeFloat(j.y); o.writeFloat(j.ax); o.writeFloat(j.ay); o.writeFloat(j.fx); o.writeFloat(j.fy);
                o.writeFloat(j.done); o.writeFloat(j.need); o.writeFloat(j.idle); o.writeLong(j.key); o.writeBoolean(j.player); o.writeBoolean(j.stopped);
            }
        }
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
        jobs.clear();
        if (in.available() > 0) {
            jobsDone = in.readInt();
            jobsStopped = in.readInt();
            int m = in.readInt();
            for (int k = 0; k < m; k++) {
                Job j = new Job();
                j.kind = in.readInt(); j.squad = in.readInt(); j.formation = in.readInt(); j.edge = in.readInt(); j.tries = in.readInt();
                j.x = in.readFloat(); j.y = in.readFloat(); j.ax = in.readFloat(); j.ay = in.readFloat(); j.fx = in.readFloat(); j.fy = in.readFloat();
                j.done = in.readFloat(); j.need = in.readFloat(); j.idle = in.readFloat(); j.key = in.readLong(); j.player = in.readBoolean(); j.stopped = in.readBoolean();
                jobs.add(j);
            }
        }
    }

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        float dp = cam.dp, h = 8 * dp;
        // work going on: a site marker and how far along it is
        for (Job j : jobs) {
            float x = cam.toScreenX(j.x), y = cam.toScreenY(j.y);
            if (x < -40 * dp || y < -40 * dp || x > cam.sw + 40 * dp || y > cam.sh + 40 * dp) continue;
            float s = Math.max(7 * dp, 20 * cam.zoom);
            g.color(0xE0101010);
            g.fillRect(x - s - dp, y - s - dp, 2 * s + 2 * dp, 2 * s + 2 * dp);
            for (int k = 0; k < 4; k++) {
                g.color((k & 1) == 0 ? 0xFFF0C020 : 0xFF202020);
                g.fillRect(x - s + k * s / 2, y - s, s / 2, s * 2);
            }
            float u = Math.min(1, j.done / Math.max(1, j.need));
            g.color(0xFF101010);
            g.fillRect(x - s, y + s + 3 * dp, 2 * s, 4 * dp);
            g.color(j.stopped ? 0xFFE04030 : 0xFF60E060);
            g.fillRect(x - s, y + s + 3 * dp, 2 * s * u, 4 * dp);
            if (cam.zoom > 0.12f) view.label(g, "Engineers: " + J_NAMES[j.kind] + (j.stopped ? " (stopped)" : j.squad < 0 ? " (on the way)" : ""), x, y - s - 8 * dp, 9.5f, 0xFFFFE8A0, Gfx.ALIGN_CENTER, false);
        }
        if (cam.zoom > 1.2f) return;
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
