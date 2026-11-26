package com.instantaxtion.zcs2;

/**
 * The region in 512 px sectors, each holding counts: people sheltering indoors, the infected who haven't turned,
 * and the dead not walking the streets as individuals. Every sector is simulated every second, seen or not, so
 * a town nobody is watching can still fall.
 *
 * Where something is happening (the dead near the living, armed units, the camera), the counts come out as
 * individuals: the dead break out of buildings, people flee into the streets. When a sector has been quiet a
 * while, its individuals fold back into counts. Nobody is lost either way: tests count everyone before and after.
 */
public final class Sectors {
    private final World w;
    public final int n, sw, sh;
    public final int[] popIn, zIn, home, bodies, ambient;
    public final float[] inf, hot, panic, driftX, driftY, firstZ;
    public final short[] settlement;
    public final byte[] grid, connect;
    public final boolean[] cam, alerted;
    final float cs;
    /** Per-settlement lists, districts and which of them are awake (docs/SCALE_PLAN.md, P3). */
    public final SectorIndex idx;

    public Sectors(World w) {
        this.w = w;
        Region r = w.r;
        sw = r.secW;
        sh = r.secH;
        cs = Tile.SIZE * r.sect;
        n = sw * sh;
        popIn = new int[n];
        zIn = new int[n];
        home = new int[n];
        bodies = new int[n];
        ambient = new int[n];
        inf = new float[n];
        hot = new float[n];
        panic = new float[n];
        driftX = new float[n];
        driftY = new float[n];
        firstZ = new float[n];
        settlement = new short[n];
        grid = new byte[n];
        connect = new byte[n];
        cam = new boolean[n];
        alerted = new boolean[n];
        java.util.Arrays.fill(firstZ, -1);
        // a sector belongs to the settlement whose residents live in it (most of them), not the one whose
        // circle happens to cover its middle: the towns' numbers then add up to their own people
        int ns = r.settlements.size();
        if (r.nation != null) {
            // a national map: the nation's residents per sector are the count (chunks only share them among their
            // buildings), and its sector raster says whose each sector is
            Nation na = r.nation;
            if (na.sw != sw || sw != sh) throw new IllegalStateException("sectors must be the nation's");
            System.arraycopy(na.residents, 0, popIn, 0, n);
            System.arraycopy(na.residents, 0, home, 0, n);
            System.arraycopy(na.secSettlement, 0, settlement, 0, n);
            for (int s = 0; s < n; s++) grid[s] = (byte) (settlement[s] < 0 ? -1 : r.settlements.get(settlement[s]).grid);
            connectivity();
            idx = new SectorIndex(w, this);
            return;
        }
        java.util.Map<Long, Integer> share = new java.util.HashMap<Long, Integer>();
        for (int b = 0; b < r.bCount; b++) {
            if (w.doorX[b] < 0) continue;
            int s = r.sectorOfPx(w.doorX[b], w.doorY[b]);
            popIn[s] += r.bres[b];
            home[s] += r.bres[b];
            if (r.bset[b] >= 0 && r.bres[b] > 0) {
                long key = (long) s * ns + r.bset[b];
                Integer v = share.get(key);
                share.put(key, (v == null ? 0 : v) + r.bres[b]);
            }
        }
        int[] bestN = new int[n];
        java.util.Arrays.fill(settlement, (short) -1);
        for (java.util.Map.Entry<Long, Integer> en : share.entrySet()) {
            int s = (int) (en.getKey() / ns), st = (int) (en.getKey() % ns);
            if (en.getValue() > bestN[s] || (en.getValue() == bestN[s] && st < settlement[s])) {
                bestN[s] = en.getValue();
                settlement[s] = (short) st;
            }
        }
        for (int s = 0; s < n; s++) {
            if (settlement[s] < 0) {
                // nobody lives here: the settlement whose ground it is (bases, parks), if any
                Region.Settlement st = r.settlementAt((s % sw + 0.5f) * cs, (s / sw + 0.5f) * cs);
                if (st != null && home[s] == 0) settlement[s] = (short) st.id;
            }
            grid[s] = (byte) (settlement[s] < 0 ? -1 : r.settlements.get(settlement[s]).grid);
        }
        connectivity();
        idx = new SectorIndex(w, this);
    }

    /** Something in sector s rose (counts, hot, panic): see SectorIndex.poke. */
    public void wake(int s) {
        idx.poke(s);
    }

    /** Wakes sector s (as wake) and returns it: for writes that raise a count, as in zIn[sec.touch(s)] += n. */
    public int touch(int s) {
        idx.poke(s);
        return s;
    }

    /** Which neighbouring sectors can be walked to (bit d for direction d of the eight). Water and walls cut it. */
    public void connectivity() {
        connectSectors(0, 0, sw - 1, sh - 1);
    }

    /**
     * The same after tiles changed only within tiles (tx0, ty0)-(tx1, ty1) (a wall, a blown bridge): the sectors
     * round them only, with the same result as the whole map's.
     */
    public void connectivity(int tx0, int ty0, int tx1, int ty1) {
        int st = w.r.sect;
        connectSectors(Math.max(0, tx0 / st - 1), Math.max(0, ty0 / st - 1), Math.min(sw - 1, tx1 / st + 1), Math.min(sh - 1, ty1 / st + 1));
    }

    /**
     * National maps: a border is open where dry coarse cells meet across it, or a live road crosses it (bridges).
     * Neither the tiles nor what is resident is read, so construction, loading and play all agree.
     */
    private void connectNational(int sx0, int sy0, int sx1, int sy1) {
        Region r = w.r;
        Nation na = r.nation;
        int per = Nation.SECT / Nation.CELL, cw = na.cw;
        int[] dx = {1, -1, 0, 0}, dy = {0, 0, 1, -1};
        for (int sy = sy0; sy <= sy1; sy++)
            for (int sx = sx0; sx <= sx1; sx++) {
                int s = sy * sw + sx;
                connect[s] &= (byte) 0xF0;
                for (int d = 0; d < 4; d++) {
                    int tx = sx + dx[d], ty = sy + dy[d];
                    if (tx < 0 || ty < 0 || tx >= sw || ty >= sh) continue;
                    boolean open = false;
                    for (int k = 0; k < per && !open; k++) {
                        int ax, ay, bx, by;
                        if (dx[d] != 0) {
                            ax = dx[d] > 0 ? (sx + 1) * per - 1 : sx * per;
                            bx = ax + dx[d];
                            ay = by = sy * per + k;
                        } else {
                            ay = dy[d] > 0 ? (sy + 1) * per - 1 : sy * per;
                            by = ay + dy[d];
                            ax = bx = sx * per + k;
                        }
                        open = !Nation.wet(na.cover[ay * cw + ax]) && !Nation.wet(na.cover[by * cw + bx]);
                    }
                    if (open) connect[s] |= (byte) (1 << d);
                }
            }
        // roads crossing a border (over water, bridges): every live road bucketed in the box's chunks
        RoadGraph g = r.roads;
        if (g.chunkEdgeStart == null) return;
        float cs2 = cs;
        for (int sy = sy0; sy <= sy1; sy++)
            for (int sx = sx0; sx <= sx1; sx++) {
                int c = sy * g.chunkW + sx;
                for (int k = g.chunkEdgeStart[c]; k < g.chunkEdgeStart[c + 1]; k++) {
                    int e = g.chunkEdge[k];
                    if (!g.ealive[e]) continue;
                    float ax = g.nx[g.ea[e]], ay = g.ny[g.ea[e]], bx = g.nx[g.eb[e]], by = g.ny[g.eb[e]];
                    float len = (float) Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay));
                    int steps = (int) (len / 256) + 1;
                    int last = -1;
                    for (int i = 0; i <= steps; i++) {
                        float x = ax + (bx - ax) * i / steps, y = ay + (by - ay) * i / steps;
                        int qx = Math.min(sw - 1, Math.max(0, (int) (x / cs2))), qy = Math.min(sh - 1, Math.max(0, (int) (y / cs2)));
                        int q = qy * sw + qx;
                        if (last >= 0 && q != last) {
                            int lx = last % sw, ly = last / sw;
                            // only the sector in the box records its side (its neighbour is in the box too, or keeps its own)
                            if (lx == qx + 1 && ly == qy) roadOpen(last, 1, q, 0, sx0, sy0, sx1, sy1);
                            else if (lx == qx - 1 && ly == qy) roadOpen(last, 0, q, 1, sx0, sy0, sx1, sy1);
                            else if (ly == qy + 1 && lx == qx) roadOpen(last, 3, q, 2, sx0, sy0, sx1, sy1);
                            else if (ly == qy - 1 && lx == qx) roadOpen(last, 2, q, 3, sx0, sy0, sx1, sy1);
                        }
                        last = q;
                    }
                }
            }
    }

    private void roadOpen(int a, int da, int b, int db, int sx0, int sy0, int sx1, int sy1) {
        if (inBox(a, sx0, sy0, sx1, sy1)) connect[a] |= (byte) (1 << da);
        if (inBox(b, sx0, sy0, sx1, sy1)) connect[b] |= (byte) (1 << db);
    }

    private boolean inBox(int s, int sx0, int sy0, int sx1, int sy1) {
        int x = s % sw, y = s / sw;
        return x >= sx0 && x <= sx1 && y >= sy0 && y <= sy1;
    }

    private void connectSectors(int sx0, int sy0, int sx1, int sy1) {
        int[] dx = {1, -1, 0, 0, 1, 1, -1, -1}, dy = {0, 0, 1, -1, 1, -1, 1, -1};
        Region r = w.r;
        if (r.nation != null) {
            connectNational(sx0, sy0, sx1, sy1);
            diagonals(sx0, sy0, sx1, sy1);
            return;
        }
        int per = r.sect / Region.NAV;
        for (int sy = sy0; sy <= sy1; sy++)
            for (int sx = sx0; sx <= sx1; sx++) {
                int s = sy * sw + sx;
                connect[s] &= (byte) 0xF0;
                for (int d = 0; d < 4; d++) {
                    int tx = sx + dx[d], ty = sy + dy[d];
                    if (tx < 0 || ty < 0 || tx >= sw || ty >= sh) continue;
                    // look along the shared border for a pair of open cells
                    boolean open = false;
                    for (int k = 0; k < per && !open; k++) {
                        int ax, ay, bx, by;
                        if (dx[d] != 0) {
                            ax = dx[d] > 0 ? (sx + 1) * per - 1 : sx * per;
                            bx = ax + dx[d];
                            ay = by = sy * per + k;
                        } else {
                            ay = dy[d] > 0 ? (sy + 1) * per - 1 : sy * per;
                            by = ay + dy[d];
                            ax = bx = sx * per + k;
                        }
                        if (r.navOpen(ax, ay) && r.navOpen(bx, by)) open = true;
                    }
                    if (open) connect[s] |= (byte) (1 << d);
                }
            }
        diagonals(sx0, sy0, sx1, sy1);
    }

    private void diagonals(int sx0, int sy0, int sx1, int sy1) {
        int[] dx = {1, -1, 0, 0, 1, 1, -1, -1}, dy = {0, 0, 1, -1, 1, -1, 1, -1};
        // diagonals read the orthogonal bits of the sector and its neighbours: one ring further
        for (int sy = Math.max(0, sy0 - 1); sy <= Math.min(sh - 1, sy1 + 1); sy++)
            for (int sx = Math.max(0, sx0 - 1); sx <= Math.min(sw - 1, sx1 + 1); sx++) {
                int s = sy * sw + sx;
                connect[s] &= (byte) 0x0F;
                for (int d = 4; d < 8; d++) {
                    int tx = sx + dx[d], ty = sy + dy[d];
                    if (tx < 0 || ty < 0 || tx >= sw || ty >= sh) continue;
                    int hx = dx[d] > 0 ? 0 : 1, vy = dy[d] > 0 ? 2 : 3;
                    // diagonal: through either orthogonal neighbour
                    int viaH = sy * sw + tx, viaV = ty * sw + sx;
                    boolean a = (connect[s] & (1 << hx)) != 0 && (connect[viaH] & (1 << vy)) != 0;
                    boolean b = (connect[s] & (1 << vy)) != 0 && (connect[viaV] & (1 << hx)) != 0;
                    if (a || b) connect[s] |= (byte) (1 << d);
                }
            }
    }

    public int neighbour(int s, int d) {
        int[] dx = {1, -1, 0, 0, 1, 1, -1, -1}, dy = {0, 0, 1, -1, 1, -1, 1, -1};
        int tx = s % sw + dx[d], ty = s / sw + dy[d];
        if (tx < 0 || ty < 0 || tx >= sw || ty >= sh) return -1;
        return ty * sw + tx;
    }

    public float cx(int s) {
        return (s % sw + 0.5f) * cs;
    }

    public float cy(int s) {
        return (s / sw + 0.5f) * cs;
    }

    public int totalZIn() {
        return idx.totalZIn();
    }

    public int totalPop() {
        return idx.totalPop();
    }

    /** Attraction of a sector to the wandering dead: people, light at night, noise. */
    public float attraction(int s) {
        float a = popIn[s] + w.sp.secCiv[s] * 4 + (w.sp.secCop[s] + w.sp.secSol[s]) * 3;
        if (w.night() && w.strat.lit(s) && home[s] > 0) a += 60;
        if (w.night() && w.fires.burning(s)) a += 80;
        return a;
    }

    private int stoch(float v, long salt) {
        int f = (int) v;
        return f + (Rng.unit(w.seed, w.tick, salt) < v - f ? 1 : 0);
    }

    public void update(float dt) {
        float bite = w.c.rule("indoorBite", 0.003f), dens = w.rule("indoorDensity", 10000), turnTime = w.c.rule("turnTime", 180), clear = w.c.rule("clearRate", 0.015f);
        maxBite = w.c.rule("maxBitePerHour", 0.05f);
        float spillAt = w.rule("sectorSpill", 3000);
        Ents e = w.e;
        Spatial sp = w.sp;
        int individuals = e.live;
        boolean roomForMore = individuals < w.maxIndividuals;
        // (only the awake sectors: every other one is quiet, and this loop would leave it as it is)
        idx.begin(dt);
        float dt0 = dt;
        for (int s = idx.firstDue(); s >= 0; s = idx.nextDue(s)) {
            dt = dt0 * idx.scale(s);
            int zO = sp.secZ[s], cO = sp.secCiv[s], arm = sp.secCop[s] + sp.secSol[s];
            int zHere = zO + zIn[s];
            if (zHere > 0 && firstZ[s] < 0) firstZ[s] = (float) w.time;
            if (zHere == 0 && inf[s] < 0.5f) firstZ[s] = -1;
            // what makes a sector worth simulating person by person
            if (cam[s]) hot[s] = Math.max(hot[s], 4);
            if (zO > 0 && (cO + arm > 0 || popIn[s] > 0)) hot[s] = 25;
            if (arm > 0 && zHere > 0) hot[s] = 25;
            if (zO > 0 && hot[s] <= 0) {
                // the dead next to living neighbours stay individual too
                for (int d = 0; d < 8; d++) {
                    int t = neighbour(s, d);
                    if (t >= 0 && (sp.secCiv[t] + sp.secCop[t] + sp.secSol[t] > 0 || cam[t])) {
                        hot[s] = 10;
                        break;
                    }
                }
            }
            // indoors: the dead get at the people sheltering. Only so many can reach anyone at once (doors,
            // stairs, barricades), and a street can only fall so fast however many are outside it.
            if (zIn[s] > 0 && popIn[s] > 0) {
                float attackers = Math.min(zIn[s], popIn[s] * 0.5f + 20);
                float k = (w.strat.lit(s) ? 1f : 1.35f) * (holdout(s) ? 0.3f : 1f) * w.gov.compliance(settlement[s]);
                float rate = attackers * bite * dt * k * popIn[s] / (popIn[s] + dens);
                float cap = popIn[s] * maxBite * dt / 60f + w.rule("biteFloor", 0.5f) * dt / 10f;
                float bites = Math.min(popIn[s], Math.min(rate, cap));
                int b = stoch(bites, s * 7L + 1);
                popIn[s] -= b;
                w.ecology.fed(s, b);
                int killed = stoch(b * 0.2f, s * 7L + 2);
                bodies[s] += killed;
                inf[s] += b - killed;
                w.stats.civDeaths += killed;
                w.stats.infectedTotal += b - killed;
                if (settlement[s] >= 0) w.stats.lostBySettlement[settlement[s]] += b;
                w.roles.lostAt(s, b);
                w.stats.lossBy[0] += b;
                panic[s] = Math.min(1, panic[s] + 0.1f * Math.min(1, dt));
            }
            if (inf[s] > 0) {
                // turning takes hours: a share of the bitten turn each second
                float turn = Math.max(Math.min(inf[s], 0.05f * dt), inf[s] / (turnTime * w.infra.turnSlow(settlement[s])) * dt);
                int t = Math.min((int) Math.ceil(inf[s]), stoch(turn, s * 7L + 3));
                inf[s] -= t;
                if (inf[s] < 0) inf[s] = 0;
                zIn[s] += t;
                w.stats.zombiesMade += t;
                w.stats.civTurned += t;
            }
            // the infected go looking for help: the settlement's hospital fills with them
            if (inf[s] >= 4 && settlement[s] >= 0) {
                int hs = w.strat.hospitalSector(settlement[s]);
                if (hs >= 0 && hs != s) {
                    int m = Math.min((int) inf[s], stoch(inf[s] * 0.02f * dt * (1 - w.media.hidden()), s * 7L + 4));
                    inf[s] -= m;
                    inf[hs] += m;
                    idx.poke(hs);
                }
            }
            // people fight back: a few of the dead where there are many of the living are put down by residents
            // (farmers, hunters, neighbours with a gun); a holdout street is ready for them
            if (zIn[s] > 0 && popIn[s] > 0) {
                float k = 0.0002f * dt * Math.min(zIn[s], popIn[s] / 100f) * (holdout(s) ? 2 : 1);
                int kill = Math.min(zIn[s], stoch(k, s * 7L + 6));
                if (kill > 0) {
                    zIn[s] -= kill;
                    bodies[s] += kill;
                    w.stats.killsBySide[0] += kill;
                    w.stats.zombieKills += kill;
                }
            }
            // armed units clearing buildings, one room at a time
            if (arm >= 2 && zIn[s] > 0) {
                int k = Math.min(zIn[s], stoch(arm * clear * dt, s * 7L + 5));
                zIn[s] -= k;
                bodies[s] += k;
                w.stats.killsBySide[sp.secSol[s] > sp.secCop[s] ? 2 : 1] += k;
            }
            if (!w.strategic && (hot[s] > 0 || cam[s])) emerge(s, zO, cO, arm, roomForMore);
            if (panic[s] > 0) panic[s] = Math.max(0, panic[s] - 0.01f * dt);
            // the unwatched dead wander towards people and lights
            if (zIn[s] > 0 && popIn[s] == 0 && hot[s] <= 0) migrate(s, dt);
            else if (hot[s] <= 0 && overrun(s) && Rng.unit(w.seed, w.tick, s * 37L + 9) < dt / 240f)
                w.strat.formHorde(s, (zIn[s] - (int) (popIn[s] * w.c.rule("overrunRatio", 20))) / 2);
            else if (zIn[s] > spillAt && hot[s] <= 0) spill(s, dt, spillAt);
            if (hot[s] > 0) hot[s] -= dt;
        }
        // (only the dead out on the streets read the drift, and there are none in fast-forward)
        if (!w.strategic) drift();
        if (!w.strategic) demote();
    }

    /**
     * Streets the dead have overrun and gone hungry in (a few holdouts left among thousands): the starving mass
     * sets off as a horde for somewhere with more people, before it settles into a nest.
     */
    private boolean overrun(int s) {
        return zIn[s] >= w.ecology.hordeThreshold(s) && w.ecology.hunger[s] > 0.5f && !w.ecology.nest[s]
                && zIn[s] > popIn[s] * w.c.rule("overrunRatio", 20) * 2;
    }

    /** A holdout: people who've held their streets for days barricade themselves in (set by the society systems). */
    public boolean holdout(int s) {
        return holdout != null && holdout[s];
    }

    public boolean[] holdout;

    /** Every individual (but the armed and the player) folds back into the counts: for strategic fast-forward. */
    public void demoteAll() {
        Ents e = w.e;
        for (int i = 0; i < e.top; i++) {
            if (e.loc[i] == Ents.BODY) {
                if ((e.flags[i] & Ents.F_RISES) != 0) {
                    int s = w.r.sectorOfPx(e.x[i], e.y[i]);
                    zIn[s]++;
                    idx.poke(s);
                }
                e.remove(i);
                continue;
            }
            if (e.loc[i] != Ents.FREE || e.armed(i) || i == w.playerEnt) continue;
            int s = w.r.sectorOfPx(e.x[i], e.y[i]);
            idx.poke(s);
            if (e.kind[i] == Ents.ZOMBIE) zIn[s]++;
            else if ((e.flags[i] & Ents.F_INFECTED) != 0) inf[s] += 1;
            else popIn[s]++;
            w.retire(i);
        }
        java.util.Arrays.fill(ambient, 0);
        java.util.Arrays.fill(hot, 0);
    }

    private void emerge(int s, int zO, int cO, int arm, boolean room) {
        Ents e = w.e;
        int livingOut = cO + arm;
        // the dead break out to the living (or come out anyway, slowly, when watched)
        if (zIn[s] > 0 && room) {
            float rate = livingOut > 0 || cam[s] ? 0.12f : 0.03f;
            int m = Math.min(Math.min(zIn[s], (int) w.rule("emergeDead", 40)), stoch(zIn[s] * rate + 0.3f, s * 11L + 1));
            for (int k = 0; k < m; k++) {
                float[] p = spot(s, k);
                if (p == null) break;
                Content.ZType zt = w.pickZType(-1, s * 131L + k, s);
                w.spawnZombie(p[0], p[1], zt);
                zIn[s]--;
            }
        }
        // people stay barricaded in while the dead are only outside; once the dead get in, they run
        int zHere = zO + zIn[s];
        if (popIn[s] > 0 && zIn[s] > 0 && room) {
            int m = Math.min(popIn[s], Math.min((int) w.rule("emergeFleeing", 12), stoch(0.5f + zIn[s] * 0.3f + panic[s] * 2, s * 11L + 2)));
            for (int k = 0; k < m; k++) {
                float[] p = spot(s, 100 + k);
                if (p == null) break;
                int i = w.spawnCiv(p[0], p[1], w.c.unit("civilian"));
                e.flags[i] |= Ents.F_FLEEING;
                e.state[i] = CivAI.S_FLEE;
                e.timer[i] = 8;
                popIn[s]--;
            }
        }
        // ordinary life on the streets, where the camera is
        if (cam[s] && zHere == 0 && popIn[s] > 0 && room && !w.gov.streetsClosed() && !w.command.sheltering(settlement[s]) && w.daylight() > 0.3f) {
            int want = Math.min((int) w.rule("emergeStreet", 28), popIn[s] / 25 + 2);
            if (ambient[s] < want) {
                int m = Math.min(want - ambient[s], 3);
                for (int k = 0; k < m && popIn[s] > 0; k++) {
                    float[] p = spot(s, 200 + k);
                    if (p == null) break;
                    int i = w.spawnCiv(p[0], p[1], w.c.unit("civilian"));
                    e.flags[i] |= Ents.F_AMBIENT;
                    e.timer[i] = 40 + Rng.unit(w.seed, e.uid[i], 9) * 80;
                    e.home[i] = s;
                    popIn[s]--;
                    ambient[s]++;
                }
            }
        }
    }

    /** A place to come out into the street: a building's door, or open ground in the countryside. */
    public float[] spot(int s, int k) {
        int[] bl = w.secBuildings[s];
        if (bl.length > 0) {
            for (int a = 0; a < 6; a++) {
                int b = bl[Rng.hashInt(w.seed, w.tick * 31 + s, k * 7 + a, bl.length)];
                if (w.r.doorX(b) >= 0) {
                    float jx = (Rng.unit(w.seed, w.tick + k, s * 3L + a) - 0.5f) * 10;
                    float jy = (Rng.unit(w.seed, w.tick + k, s * 3L + a + 1) - 0.5f) * 10;
                    float ddx = w.r.doorX(b), ddy = w.r.doorY(b);
                    if (w.r.walkablePx(ddx + jx, ddy + jy)) return new float[]{ddx + jx, ddy + jy};
                    return new float[]{ddx, ddy};
                }
            }
        }
        float sx0 = (s % sw) * cs, sy0 = (s / sw) * cs;
        for (int a = 0; a < 12; a++) {
            float x = sx0 + Rng.unit(w.seed, w.tick * 7 + s, k * 13 + a) * cs;
            float y = sy0 + Rng.unit(w.seed, w.tick * 7 + s, k * 13 + a + 500) * cs;
            if (w.r.walkablePx(x, y)) return new float[]{x, y};
        }
        return null;
    }

    private void migrate(int s, float dt) {
        float best = attraction(s);
        int bd = -1;
        for (int d = 0; d < 8; d++) {
            if ((connect[s] & (1 << d)) == 0) continue;
            int t = neighbour(s, d);
            // (something has to be there to draw them: the random part only breaks ties)
            float a = attraction(t) / w.ecology.cost[t];
            if (a <= 0) continue;
            a += Rng.unit(w.seed, w.tick, s * 8L + d) * 2;
            if (a > best + 1) {
                best = a;
                bd = d;
            }
        }
        float rate = w.rule("migrateRate", 0.02f);
        if (bd < 0) {
            // nothing to go to: shamble about a little (more when hungry)
            if (Rng.unit(w.seed, w.tick, s * 5L) < 0.08f * Math.min(1, dt)) bd = Rng.hashInt(w.seed, w.tick, s, 8);
            if (bd < 0 || (connect[s] & (1 << bd)) == 0) return;
            rate = w.rule("shambleRate", 0.003f);
        }
        int t = neighbour(s, bd);
        int m = Math.min(zIn[s], stoch((zIn[s] * rate + 0.2f) * dt * w.weather.zombieSpeed() * w.ecology.migrateFactor(s), s * 13L));
        if (m <= 0) return;
        // a big mass on the move becomes a horde you can see on the map
        if (zIn[s] >= w.ecology.hordeThreshold(s) && popIn[t] == 0 && settlement[s] < 0) {
            w.strat.formHorde(s, zIn[s] / 2);
            return;
        }
        // a precinct's cordon on its district's edge turns most of them back
        if (w.roles.blocks(s, t)) m /= 5;
        if (m <= 0) return;
        w.ecology.carry(s, t, m, zIn[t]);
        zIn[s] -= m;
        zIn[t] += m;
    }

    private float maxBite = 0.05f;

    /** Too many of the dead for one sector's streets: the crush spreads into the neighbours with people. */
    private void spill(int s, float dt, float at) {
        int bd = -1;
        float best = -1;
        for (int d = 0; d < 8; d++) {
            if ((connect[s] & (1 << d)) == 0) continue;
            int t = neighbour(s, d);
            if (zIn[t] >= zIn[s]) continue;
            float a = attraction(t) / (1 + zIn[t]);
            if (a > best) {
                best = a;
                bd = t;
            }
        }
        if (bd < 0) return;
        int m = Math.min(zIn[s] - (int) at, stoch((zIn[s] - at) * 0.02f * dt, s * 29L));
        if (w.roles.blocks(s, bd)) m /= 5;
        if (m <= 0) return;
        zIn[s] -= m;
        zIn[bd] += m;
    }

    private void drift() {
        for (int s = idx.first(); s >= 0; s = idx.next(s)) {
            float bx = 0, by = 0, best = attraction(s);
            for (int d = 0; d < 8; d++) {
                if ((connect[s] & (1 << d)) == 0) continue;
                int t = neighbour(s, d);
                float a = attraction(t);
                if (a > best) {
                    best = a;
                    bx = cx(t) - cx(s);
                    by = cy(t) - cy(s);
                }
            }
            float len = (float) Math.sqrt(bx * bx + by * by);
            if (len == 0 && settlement[s] < 0) {
                // out in the fields with nothing near: towards the nearest town's streets and lights
                float[] d = townDir(s);
                bx = d[s * 2];
                by = d[s * 2 + 1];
                len = (float) Math.sqrt(bx * bx + by * by) * 1.6f;
            }
            driftX[s] = len > 0 ? bx / len : 0;
            driftY[s] = len > 0 ? by / len : 0;
        }
    }

    private float[] townDir;
    private boolean[] townDone;

    /**
     * For a sector outside a town: the direction to the nearest street where people live (within 12 sectors), or 0;
     * the nearest by straight-line distance, the lowest-numbered of equals. Worked out the first time a sector asks
     * (only awake sectors do), searching outwards ring by ring until no nearer one can be found.
     */
    private float[] townDir(int s) {
        if (townDir == null) {
            townDir = new float[n * 2];
            townDone = new boolean[n];
        }
        if (townDone[s]) return townDir;
        townDone[s] = true;
        int R = 12;
        int sx = s % sw, sy = s / sw, best = -1, bd = Integer.MAX_VALUE;
        for (int r = 0; r <= R && r * r <= bd; r++)
            for (int dy = -r; dy <= r; dy++) {
                int y = sy + dy;
                if (y < 0 || y >= sh) continue;
                int step = (dy == -r || dy == r) ? 1 : 2 * r;
                for (int dx = -r; dx <= r; dx += Math.max(1, step)) {
                    int x = sx + dx;
                    if (x < 0 || x >= sw) continue;
                    int t = y * sw + x, d = dx * dx + dy * dy;
                    if (home[t] > 0 && settlement[t] >= 0 && (d < bd || d == bd && t < best)) {
                        bd = d;
                        best = t;
                    }
                }
            }
        if (best < 0) return townDir;
        float dx = (best % sw) - sx, dy = (best / sw) - sy, l = (float) Math.sqrt(dx * dx + dy * dy);
        townDir[s * 2] = dx / l;
        townDir[s * 2 + 1] = dy / l;
        return townDir;
    }

    /** Quiet sectors fold their individuals back into counts. Police and soldiers always stay individual. */
    private void demote() {
        Ents e = w.e;
        for (int i = 0; i < e.top; i++) {
            if (e.loc[i] != Ents.FREE || e.armed(i) || i == w.playerEnt) continue;
            int s = w.r.sectorOfPx(e.x[i], e.y[i]);
            boolean ambientDone = (e.flags[i] & Ents.F_AMBIENT) != 0 && e.state[i] == CivAI.S_HOME;
            if (!ambientDone && (hot[s] > 0 || cam[s])) continue;
            if ((e.flags[i] & Ents.F_PLACED) != 0 && cam[s]) continue;
            if (e.kind[i] == Ents.ZOMBIE) {
                if (w.ztypeOf(i).placedOnly) continue;
                idx.poke(s);
                zIn[s]++;
            } else {
                idx.poke(s);
                if ((e.flags[i] & Ents.F_INFECTED) != 0) inf[s] += 1;
                else popIn[s]++;
                if ((e.flags[i] & Ents.F_AMBIENT) != 0 && e.home[i] >= 0 && ambient[e.home[i]] > 0) ambient[e.home[i]]--;
            }
            w.retire(i);
        }
    }

    /** People who reached shelter go indoors (counted in their sector again). */
    public void shelter(int i) {
        int s = w.r.sectorOfPx(w.e.x[i], w.e.y[i]);
        idx.poke(s);
        if ((w.e.flags[i] & Ents.F_INFECTED) != 0) inf[s] += 1;
        else popIn[s]++;
        if ((w.e.flags[i] & Ents.F_AMBIENT) != 0 && w.e.home[i] >= 0 && ambient[w.e.home[i]] > 0) ambient[w.e.home[i]]--;
        w.retire(i);
    }

    /** A zombie gets inside a building. */
    public void breakIn(int i) {
        int s = w.r.sectorOfPx(w.e.x[i], w.e.y[i]);
        idx.poke(s);
        zIn[s]++;
        w.retire(i);
    }
}
