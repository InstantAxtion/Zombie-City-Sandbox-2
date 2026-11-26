package com.instantaxtion.zcs2;

import java.util.ArrayList;
import java.util.List;

/**
 * One running outbreak: the region, everyone in it, and the order things happen in each step.
 *
 * A step is 1/20 s of game time:
 * 1. once a second: the active chunks (ActiveSet), the sectors (the people and the dead not simulated one by one), command, logistics,
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
    /**
     * A national (real-scale) map: its sectors are chunk-sized, not Region.SECT. Rules read through rule() then take
     * their key@national values from content.ini (km/h speeds, km distances, per-sector numbers x4).
     */
    public final boolean national;
    public final long seed;
    public final Ents e;
    /** The chunks the street-level simulation reads (all of them on today's maps). */
    public final ActiveSet act;
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
    /** The player's rules sheet, defences and lasting orders (see Rules, Defences, Orders). */
    public final Rules rules;
    public final Defences defs;
    public final Orders orders;
    public final Roles roles;
    public final Director director;
    /** The scenario's triggers and goals, or null when it has none (Script.attach). */
    public Script script;
    public final Recovery recovery;
    /** What the dead do beyond chasing and biting (smashing, feeding, alphas, lunges), and its state. */
    public final Undead undead;
    /** A normal day round the camera: traffic, parked cars, buses, trains, patrols, people with somewhere to be (Life). */
    public final Life life;
    /** Systems plugged in (updated, saved and drawn in this order). */
    public final List<Module> modules = new ArrayList<Module>();
    /** Strategic fast-forward: everything as numbers, ten game seconds a step. */
    public boolean strategic;
    public static final float STRATEGIC_DT = 10f;
    public final Rng rng;
    public Parallel par;
    /** While a save loads its modules: the save's content indices mapped to this content's (null otherwise). */
    SaveIO.Remap loadRemap;

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
    final ArmedAI aai;
    final Combat combat;

    public World(Content c, Region r, long seed, int threads) {
        this(c, r, seed, threads, ActiveSet.sparseDefault);
    }

    /** sparse: only the chunks the simulation needs are active (on today's maps every chunk is, unless a test asks). */
    World(Content c, Region r, long seed, int threads, boolean sparse) {
        this.c = c;
        this.r = r;
        r.recordEdits(); // from here on, changes to the map go into its overlay (saves hold that, not the tiles)
        national = r.sect != Region.SECT;
        // a map made from a nation is sparse by construction: its chunks come from the cache as they become active
        if (r.nation != null) sparse = true;
        this.seed = seed;
        rng = new Rng(seed ^ 0x5EEDL);
        e = new Ents(8192);
        act = new ActiveSet(this, sparse);
        if (sparse) e.act = act;
        if (r.nation != null) {
            if (r.cache == null) r.cache = new ChunkCache(r.nation, cacheFor(Runtime.getRuntime().maxMemory()));
            r.cache.setOverlay(r.overlay);
            Pages pg = new Pages();
            act.source = pg;
            r.pager = pg;
        }
        sp = new Spatial(r, act);
        flow = new Flow(r, act);
        nav = new NavPath(r, act);
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
        rules = new Rules(this);
        defs = new Defences(this);
        orders = new Orders(this);
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
        undead = new Undead(this);
        modules.add(undead);
        director = new Director(this);
        modules.add(director);
        modules.add(rules);
        modules.add(defs);
        modules.add(orders);
        life = new Life(this);
        // (saved and stepped only when its rules are on: a game without them is exactly as before)
        if (life.on || life.flowOn) modules.add(life);
        zai = new ZombieAI(this);
        cai = new CivAI(this);
        aai = new ArmedAI(this);
        modules.add(aai.module);
        modules.add(stats.rec); // reads the world, changes nothing: last
        combat = new Combat(this);
        par = new Parallel(threads);
    }

    private void doors() {
        doorX = new float[r.bCount];
        doorY = new float[r.bCount];
        r.setDoors(doorX, doorY);
        int ns = r.secW * r.secH;
        int[] cnt = new int[ns];
        for (int b = 0; b < r.bCount; b++) {
            int x0 = r.bx(b), y0 = r.by(b), x1 = x0 + r.bw(b) - 1, y1 = y0 + r.bh(b) - 1;
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
            if (doorX[b] >= 0) cnt[r.sectorOfPx(doorX[b], doorY[b])]++;
        }
        // (by counting: a list per sector would be 200,000 objects on a national map)
        secBuildings = new int[ns][];
        int[] none = new int[0];
        for (int s = 0; s < ns; s++) secBuildings[s] = cnt[s] == 0 ? none : new int[cnt[s]];
        java.util.Arrays.fill(cnt, 0);
        for (int b = 0; b < r.bCount; b++)
            if (doorX[b] >= 0) {
                int s = r.sectorOfPx(doorX[b], doorY[b]);
                secBuildings[s][cnt[s]++] = b;
            }
        if (r.nation != null) natBuildings = secBuildings.clone();
    }

    /** National maps: each sector's national buildings (secBuildings while its chunk isn't attached). */
    private int[][] natBuildings;

    /** The chunk cache's size for a heap: three chunks a MB, at most 1,024 (about 20 MB of chunks). */
    static int cacheFor(long maxMemory) {
        return Math.min(1024, ChunkCache.capacityFor(maxMemory));
    }

    /**
     * A national map's pages: the active set takes chunks from the cache (pinned while active, the very chunk the
     * cache holds, never a copy), and reads of pages that aren't resident go to the cache without attaching.
     */
    private final class Pages implements ActiveSet.Source, Region.Pager {
        @Override
        public void fetch(int p) {
            int pw = r.store.pw, cx = p % pw, cy = p / pw;
            Chunk c = r.cache.get(cx, cy);
            r.cache.pin(cx, cy);
            r.store.attach(c);
            attached(p, c);
        }

        @Override
        public void release(int p) {
            int pw = r.store.pw;
            r.store.detach(p);
            r.cache.unpin(p % pw, p / pw);
            secBuildings[p] = natBuildings[p];
        }

        @Override
        public Chunk read(int p) {
            int pw = r.store.pw;
            return r.cache.get(p % pw, p / pw);
        }

        @Override
        public void need(int p) {
            if (inParallel) throw new IllegalStateException("page " + p + " taken in during a parallel phase");
            act.touchPage(p);
        }
    }

    /** A chunk now stands as page p (a sector): its buildings join the sector's list (doors are inside the chunk). */
    private void attached(int p, Chunk c) {
        int[] nat = natBuildings[p];
        int k = 0;
        for (int i = 0; i < c.bCount; i++) if (c.doorX[i] >= 0) k++;
        int[] l = new int[nat.length + k];
        System.arraycopy(nat, 0, l, 0, nat.length);
        k = nat.length;
        for (int i = 0; i < c.bCount; i++) if (c.doorX[i] >= 0) l[k++] = c.id(i);
        secBuildings[p] = l;
    }

    /**
     * Saves loaded on a national map: the pages taken in before the overlay was read are swapped for the cache's
     * chunks with the edits on (the cache makes them again from scratch when their version changed).
     */
    void refreshPages() {
        r.cache.setOverlay(r.overlay);
        int pw = r.store.pw;
        for (int p = 0; p < r.store.pages(); p++) {
            if (!r.store.resident(p)) continue;
            Chunk c = r.cache.get(p % pw, p / pw);
            if (c != r.store.chunk(p)) {
                r.store.attach(c);
                attached(p, c);
            }
        }
    }

    /** True while the parallel phases run (nothing may take a page in then). */
    volatile boolean inParallel;

    // ------------------------------------------------------------------ time

    public float hour() {
        // the rules sheet can hold the time of day
        if (rules != null && rules.hour() >= 0) return rules.hour();
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

    /** A rule from content.ini for this map: its key@national value on a national map, else the plain one, else def. */
    public float rule(String key, float def) {
        return c.rule(key, def, national);
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
        else if (act.fresh) act.update(); // a new sparse game's first step
        sp.build(e);
        if (tick % 10 == 1) flow.build(this);
        sq.update();
        aai.prepare();
        undead.prepare();
        parallel(thinkJob);
        applyIntents();
        parallel(moveJob);
        veh.update();
        combat.bodies();
        for (int i = 0; i < modules.size(); i++) modules.get(i).step();
        fx.update(DT);
    }

    /** Tests: the parallel phases of national maps read only resident pages, or Region throws (Region.strictPages). */
    public static boolean strictParallel;

    private void parallel(Parallel.Job job) {
        if (r.pager == null) {
            par.run(job, e.top);
            return;
        }
        inParallel = true;
        boolean strict = Region.strictPages;
        if (strictParallel) Region.strictPages = true;
        try {
            par.run(job, e.top);
        } finally {
            inParallel = false;
            Region.strictPages = strict;
        }
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
        if (!strategic) act.update();
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
            if (life.on) life.foldAll(); // (the city's traffic and the people in it, first)
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
                // stuck inside something (a building fell on them, or put down inside one): out to the nearest
                // open ground, looking further each ring
                if (!r.walkablePx(x, y)) {
                    boolean out = false;
                    for (int ring = 1; ring <= 12 && !out; ring++) {
                        int dirs = 8 + ring * 4;
                        for (int d = 0; d < dirs; d++) {
                            double a = d * 6.2831853 / dirs;
                            float ax = x + (float) Math.cos(a) * ring * 12, ay = y + (float) Math.sin(a) * ring * 12;
                            if (r.walkablePx(ax, ay)) {
                                nx = ax;
                                ny = ay;
                                out = true;
                                break;
                            }
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
