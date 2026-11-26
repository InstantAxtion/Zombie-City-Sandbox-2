package com.instantaxtion.zcs2;

import java.util.ArrayList;
import java.util.List;

/**
 * One running outbreak: the region, everyone in it, and the order things happen in each step.
 *
 * A step is 1/20 s of game time:
 * 1. once a second: the sectors (the people and the dead not simulated one by one), command, logistics,
 *    government, news, statistics;
 * 2. the spatial grid, and twice a second the scent field;
 * 3. squads decide (one decision per squad, in order);
 * 4. everyone thinks, in parallel (each writes only its own intent);
 * 5. intents are carried out in entity order (bites, shots, boarding, going indoors);
 * 6. everyone moves, in parallel; then vehicles, bodies and effects.
 * Nothing reads the clock on the wall, and every random number comes from the seed, so a game replays exactly.
 */
public final class World {
    public static final float DT = 0.05f;
    public static final int MODE_SANDBOX = 0, MODE_COMMANDER = 1, MODE_SURVIVOR = 2;
    public static final int SAVE_RULES = 1;

    public final Content c;
    public final Region r;
    public final long seed;
    public final Ents e;
    public final Spatial sp;
    public final Flow flow;
    public final NavPath nav;
    public final Sectors sec;
    public final Vehicles veh;
    public final Squads sq;
    public final Dispatch disp;
    public final Military mil;
    public final Strategic strat;
    public final Gov gov;
    public final News news;
    public final Effects fx;
    public final Stats stats;
    public final Forces forces;
    public final Weather weather;
    public final Infrastructure infra;
    public final Logistics logistics;
    public final Command command;
    public final Media media;
    public final Society society;
    public final Ecology ecology;
    public final Fires fires;
    public final Navy navy;
    public final Engineers engineers;
    public final Roles roles;
    public final Director director;
    public final Recovery recovery;
    /** Systems plugged in (updated, saved and drawn in this order). */
    public final List<Module> modules = new ArrayList<Module>();
    /** Strategic fast-forward: everything as numbers, ten game seconds a step. */
    public boolean strategic;
    public static final float STRATEGIC_DT = 10f;
    public final Rng rng;
    public Parallel par;

    public long tick;
    /** Game seconds since the start (one game hour is 60 s, a day 24 minutes at 1x). */
    public double time;
    public static final float START_HOUR = 8f;
    public int mode;
    public Content.Scenario scenario;
    public int playerEnt = -1;
    public boolean ended;
    public String endTitle, endText;
    public boolean won;
    /** The player chose to keep watching after the game ended: it runs on as a sandbox, with no goal. */
    public boolean watching;

    // the camera, as an input: sectors in view are simulated in detail (recorded in replays)
    public float camX0, camY0, camX1, camY1;
    public boolean camDetail;

    /** The most individuals the phone should simulate (aggregates take the rest). */
    public int maxIndividuals = 60000;

    // door positions of every building (a walkable tile next to it)
    public float[] doorX, doorY;
    /** Buildings by sector (indices into r's buildings). */
    public int[][] secBuildings;

    private final ZombieAI zai;
    private final CivAI cai;
    private final ArmedAI aai;
    final Combat combat;

    public World(Content c, Region r, long seed, int threads) {
        this.c = c;
        this.r = r;
        this.seed = seed;
        rng = new Rng(seed ^ 0x5EEDL);
        e = new Ents(8192);
        sp = new Spatial(r);
        flow = new Flow(r);
        nav = new NavPath(r);
        fx = new Effects();
        doors();
        sec = new Sectors(this);
        veh = new Vehicles(this);
        sq = new Squads(this);
        disp = new Dispatch(this);
        mil = new Military(this);
        strat = new Strategic(this);
        gov = new Gov(this);
        news = new News(this);
        stats = new Stats(this);
        forces = new Forces(this);
        weather = new Weather(this);
        ecology = new Ecology(this);
        fires = new Fires(this);
        navy = new Navy(this);
        engineers = new Engineers(this);
        modules.add(weather);
        modules.add(forces);
        infra = new Infrastructure(this);
        logistics = new Logistics(this);
        modules.add(infra);
        modules.add(logistics);
        command = new Command(this);
        media = new Media(this);
        society = new Society(this);
        modules.add(command);
        modules.add(media);
        modules.add(society);
        modules.add(ecology);
        modules.add(fires);
        modules.add(navy);
        modules.add(engineers);
        roles = new Roles(this);
        modules.add(roles);
        recovery = new Recovery(this);
        modules.add(recovery);
        director = new Director(this);
        modules.add(director);
        zai = new ZombieAI(this);
        cai = new CivAI(this);
        aai = new ArmedAI(this);
        combat = new Combat(this);
        par = new Parallel(threads);
    }

    private void doors() {
        doorX = new float[r.bCount];
        doorY = new float[r.bCount];
        int ns = r.secW * r.secH;
        List<List<Integer>> per = new ArrayList<List<Integer>>(ns);
        for (int s = 0; s < ns; s++) per.add(new ArrayList<Integer>());
        for (int b = 0; b < r.bCount; b++) {
            int x0 = r.bx[b], y0 = r.by[b], x1 = x0 + r.bw[b] - 1, y1 = y0 + r.bh[b] - 1;
            float best = -1;
            int mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
            // try the middle of each side, nearest to a road first
            int[][] cand = {{mx, y1 + 1}, {mx, y0 - 1}, {x0 - 1, my}, {x1 + 1, my}};
            doorX[b] = -1;
            for (int[] p : cand) {
                int t = r.t(p[0], p[1]);
                if (!Tile.walkable(t)) continue;
                float score = (t == Tile.PAVEMENT || t == Tile.LOT || t == Tile.PLAZA) ? 2 : 1;
                if (score > best) {
                    best = score;
                    doorX[b] = p[0] * Tile.SIZE + 8;
                    doorY[b] = p[1] * Tile.SIZE + 8;
                }
            }
            if (doorX[b] < 0) {
                // fully enclosed (rare): any walkable tile round it
                for (int y = y0 - 1; y <= y1 + 1 && doorX[b] < 0; y++)
                    for (int x = x0 - 1; x <= x1 + 1; x++)
                        if (Tile.walkable(r.t(x, y))) {
                            doorX[b] = x * Tile.SIZE + 8;
                            doorY[b] = y * Tile.SIZE + 8;
                            break;
                        }
            }
            if (doorX[b] >= 0) per.get(r.sectorOfPx(doorX[b], doorY[b])).add(b);
        }
        secBuildings = new int[ns][];
        for (int s = 0; s < ns; s++) {
            List<Integer> l = per.get(s);
            secBuildings[s] = new int[l.size()];
            for (int i = 0; i < l.size(); i++) secBuildings[s][i] = l.get(i);
        }
    }

    // ------------------------------------------------------------------ time

    public float hour() {
        return (float) ((START_HOUR + time / 60.0) % 24.0);
    }

    public int day() {
        return 1 + (int) ((START_HOUR + time / 60.0) / 24.0);
    }

    /** 0 at midnight dark, 1 in full day. */
    public float daylight() {
        float h = hour();
        if (h >= 7 && h <= 18) return 1;
        if (h <= 5 || h >= 20.5f) return 0;
        if (h < 7) return (h - 5) / 2;
        return (20.5f - h) / 2.5f;
    }

    public boolean night() {
        return daylight() < 0.35f;
    }

    public String clock() {
        float h = hour();
        int hh = (int) h, mm = (int) ((h - hh) * 60);
        return "Day " + day() + "  " + (hh < 10 ? "0" : "") + hh + ":" + (mm < 10 ? "0" : "") + mm;
    }

    // ------------------------------------------------------------------ the step

    public void step() {
        // an ended game waits on its report (God's campaigns too), unless the player chose to keep watching
        if (ended && !watching && (mode != MODE_SANDBOX || scenario != null && scenario.goal != null && scenario.goal.length() > 0)) return;
        if (strategic) {
            strategicStep();
            return;
        }
        tick++;
        time += DT;
        if (tick % 20 == 0) secondly(1f);
        sp.build(e);
        if (tick % 10 == 1) flow.build(this);
        sq.update();
        par.run(thinkJob, e.top);
        applyIntents();
        par.run(moveJob, e.top);
        veh.update();
        combat.bodies();
        for (int i = 0; i < modules.size(); i++) modules.get(i).step();
        fx.update(DT);
    }

    /** Ten game seconds at once, all as numbers (only in strategic fast-forward). */
    private void strategicStep() {
        float dt = STRATEGIC_DT;
        tick += (long) (dt * 20);
        time += dt;
        secondly(dt);
        fx.update(dt);
    }

    private void secondly(float dt) {
        sec.update(dt);
        if (!strategic) disp.update();
        mil.update(dt);
        strat.update(dt);
        gov.update(dt);
        for (int i = 0; i < modules.size(); i++) modules.get(i).update(dt);
        news.update();
        stats.update();
        if (!strategic) sq.secondly();
    }

    /** Into (or out of) strategic fast-forward. Going in, every individual folds into the counts and pools. */
    public void setStrategic(boolean on) {
        if (on == strategic) return;
        if (on) {
            if (playerEnt >= 0) return; // someone you play as can't be fast-forwarded past
            forces.enterStrategic();
            sec.demoteAll();
            sp.build(e);
            // round the clock to a whole number of strategic steps
        }
        strategic = on;
    }

    private final Parallel.Job thinkJob = new Parallel.Job() {
        @Override
        public void run(int from, int to, Parallel.Scratch s) {
            for (int i = from; i < to; i++) {
                e.intent[i] = 0;
                if (e.loc[i] != Ents.FREE) continue;
                switch (e.kind[i]) {
                    case Ents.ZOMBIE: zai.think(i, s); break;
                    case Ents.CIV: cai.think(i, s); break;
                    default: aai.think(i, s);
                }
            }
        }
    };

    private final Parallel.Job moveJob = new Parallel.Job() {
        @Override
        public void run(int from, int to, Parallel.Scratch s) {
            for (int i = from; i < to; i++) if (e.loc[i] == Ents.FREE) move(i);
        }
    };

    /** Moves one person by their chosen velocity, sliding along walls. Writes only their own slot. */
    private void move(int i) {
        float k = Math.min(1f, DT * 8f);
        float vx = e.vx[i] + (e.dvx[i] - e.vx[i]) * k, vy = e.vy[i] + (e.dvy[i] - e.vy[i]) * k;
        float x = e.x[i], y = e.y[i];
        float sl = Tile.slow(r.tileAtPx(x, y));
        float nx = x + vx * DT * sl, ny = y + vy * DT * sl;
        if (!r.walkablePx(nx, ny)) {
            if (r.walkablePx(nx, y)) {
                ny = y;
                vy = 0;
            } else if (r.walkablePx(x, ny)) {
                nx = x;
                vx = 0;
            } else {
                nx = x;
                ny = y;
                vx = vy = 0;
                // stuck inside something (a building fell on them): step to any open neighbour
                if (!r.walkablePx(x, y)) {
                    for (int d = 0; d < 8; d++) {
                        float ax = x + (float) Math.cos(d * 0.785) * 16, ay = y + (float) Math.sin(d * 0.785) * 16;
                        if (r.walkablePx(ax, ay)) {
                            nx = ax;
                            ny = ay;
                            break;
                        }
                    }
                }
            }
        }
        float max = r.px() - 1;
        if (nx < 1) nx = 1;
        if (ny < 1) ny = 1;
        if (nx > max) nx = max;
        if (ny > max) ny = max;
        e.x[i] = nx;
        e.y[i] = ny;
        e.vx[i] = vx;
        e.vy[i] = vy;
        if (vx * vx + vy * vy > 4) e.face[i] = fastAtan2(vy, vx);
    }

    /** atan2 to within about 0.005 radians, much cheaper than Math.atan2 (faces are only drawn, never decided on). */
    static float fastAtan2(float y, float x) {
        float ax = Math.abs(x), ay = Math.abs(y);
        if (ax < 1e-9f && ay < 1e-9f) return 0;
        float a = Math.min(ax, ay) / Math.max(ax, ay);
        float s = a * a;
        float r = ((-0.0464964749f * s + 0.15931422f) * s - 0.327622764f) * s * a + a;
        if (ay > ax) r = 1.57079637f - r;
        if (x < 0) r = 3.14159274f - r;
        if (y < 0) r = -r;
        return r;
    }

    private void applyIntents() {
        for (int i = 0; i < e.top; i++) {
            int in = e.intent[i];
            if (in == 0 || e.loc[i] != Ents.FREE) continue;
            combat.apply(i, in, e.intentArg[i]);
        }
    }

    // ------------------------------------------------------------------ spawning and removing

    public Content.ZType pickZType(int ztypeHint, long salt) {
        return pickZType(ztypeHint, salt, -1);
    }

    /** A type for one of the dead coming out in sector s: the strain of that place (Ecology), or the worst so far. */
    public Content.ZType pickZType(int ztypeHint, long salt, int sector) {
        if (ztypeHint >= 0) return c.ztypes.get(ztypeHint);
        float total = 0;
        for (Content.ZType z : c.ztypes) total += ecology.weight(z, sector);
        float pick = Rng.unit(seed, tick, salt) * total;
        for (Content.ZType z : c.ztypes) {
            pick -= ecology.weight(z, sector);
            if (pick <= 0) return z;
        }
        return c.ztypes.get(0);
    }

    public int spawnZombie(float x, float y, Content.ZType t) {
        int i = e.spawn(Ents.ZOMBIE, t.index, x, y, t.hp * (0.9f + Rng.unit(seed, e.nextUid(), 3) * 0.2f));
        if (t.helmet) e.flags[i] |= Ents.F_HELMET;
        e.face[i] = Rng.unit(seed, e.uid[i], 4) * 6.283f;
        stats.zombiesMade++;
        return i;
    }

    public int spawnCiv(float x, float y, Content.Unit u) {
        int i = e.spawn(Ents.CIV, u.index, x, y, u.hp);
        if (u.weapon != null) e.ammo[i] = (short) u.weapon.ammo;
        e.face[i] = Rng.unit(seed, e.uid[i], 4) * 6.283f;
        return i;
    }

    public int spawnUnit(Content.Unit u, float x, float y) {
        byte k = u.kind == Content.COP ? Ents.COP : u.kind == Content.SOLDIER ? Ents.SOLDIER : Ents.CIV;
        int i = e.spawn(k, u.index, x, y, u.hp);
        if (u.weapon != null) e.ammo[i] = (short) Math.min(Short.MAX_VALUE, u.weapon.ammo);
        e.face[i] = Rng.unit(seed, e.uid[i], 4) * 6.283f;
        return i;
    }

    public Content.Unit unitOf(int i) {
        return c.units.get(e.type[i]);
    }

    public Content.ZType ztypeOf(int i) {
        return c.ztypes.get(e.type[i]);
    }

    /** Someone leaves the individual simulation alive (indoors, out of the region). */
    public void retire(int i) {
        if (e.squad[i] >= 0) sq.removeMember(e.squad[i], i);
        if (i == playerEnt) return;
        e.remove(i);
    }

    public String nameOf(int i) {
        if (e.kind[i] == Ents.ZOMBIE) return ztypeOf(i).name;
        String n = Names.person(seed, e.uid[i]);
        if (e.kind[i] == Ents.CIV) return n;
        return Ranks.title(e.kind[i], e.rank[i]) + " " + Names.surname(seed, e.uid[i]);
    }

    public int totalZombies() {
        return sp.zCount + sec.totalZIn() + strat.hordeTotal();
    }

    public int livingCivilians() {
        return sp.secCivTotal() + sec.totalPop() + strat.refugeeTotal();
    }

    void civStep(int i, float dx, float dy, float speed, Parallel.Scratch s) {
        cai.steer(i, dx, dy, speed, s);
    }

    public void shutdown() {
        par.shutdown();
    }
}
