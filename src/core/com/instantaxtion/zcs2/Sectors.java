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
    private final float cs = Tile.SIZE * Region.SECT;

    public Sectors(World w) {
        this.w = w;
        Region r = w.r;
        sw = r.secW;
        sh = r.secH;
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
        for (int s = 0; s < n; s++) {
            float cx = (s % sw + 0.5f) * cs, cy = (s / sw + 0.5f) * cs;
            Region.Settlement st = r.settlementAt(cx, cy);
            // a sector near a settlement's edge still belongs to it if it holds its buildings
            settlement[s] = (short) (st == null ? -1 : st.id);
            grid[s] = (byte) (st == null ? -1 : st.grid);
        }
        for (int b = 0; b < r.bCount; b++) {
            if (w.doorX[b] < 0) continue;
            int s = r.sectorOfPx(w.doorX[b], w.doorY[b]);
            popIn[s] += r.bres[b];
            home[s] += r.bres[b];
            if (settlement[s] < 0 && r.bset[b] >= 0) {
                settlement[s] = r.bset[b];
                grid[s] = (byte) r.settlements.get(r.bset[b]).grid;
            }
        }
        connectivity();
    }

    /** Which neighbouring sectors can be walked to (bit d for direction d of the eight). Water and walls cut it. */
    public void connectivity() {
        for (int s = 0; s < n; s++) connect[s] = 0;
        int[] dx = {1, -1, 0, 0, 1, 1, -1, -1}, dy = {0, 0, 1, -1, 1, -1, 1, -1};
        Region r = w.r;
        int per = Region.SECT / Region.NAV;
        for (int s = 0; s < n; s++) {
            int sx = s % sw, sy = s / sw;
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
        for (int s = 0; s < n; s++) {
            int sx = s % sw, sy = s / sw;
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
        int t = 0;
        for (int s = 0; s < n; s++) t += zIn[s];
        return t;
    }

    public int totalPop() {
        int t = 0;
        for (int s = 0; s < n; s++) t += popIn[s] + (int) inf[s];
        return t;
    }

    /** Attraction of a sector to the wandering dead: people, light at night, noise. */
    public float attraction(int s) {
        float a = popIn[s] + w.sp.secCiv[s] * 4 + (w.sp.secCop[s] + w.sp.secSol[s]) * 3;
        if (w.night() && w.strat.lit(s) && home[s] > 0) a += 60;
        return a;
    }

    private int stoch(float v, long salt) {
        int f = (int) v;
        return f + (Rng.unit(w.seed, w.tick, salt) < v - f ? 1 : 0);
    }

    public void update(float dt) {
        float bite = w.c.rule("indoorBite", 0.006f), dens = w.c.rule("indoorDensity", 40), turnTime = w.c.rule("turnTime", 40), clear = w.c.rule("clearRate", 0.015f);
        Ents e = w.e;
        Spatial sp = w.sp;
        int individuals = e.live;
        boolean roomForMore = individuals < w.maxIndividuals;
        float x0 = w.camX0 - cs * 0.5f, y0 = w.camY0 - cs * 0.5f, x1 = w.camX1 + cs * 0.5f, y1 = w.camY1 + cs * 0.5f;
        for (int s = 0; s < n; s++) {
            float sx0 = (s % sw) * cs, sy0 = (s / sw) * cs;
            cam[s] = w.camDetail && sx0 + cs > x0 && sx0 < x1 && sy0 + cs > y0 && sy0 < y1;
        }
        for (int s = 0; s < n; s++) {
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
            // indoors: the dead get at the people sheltering
            if (zIn[s] > 0 && popIn[s] > 0) {
                float rate = zIn[s] * bite * dt * (w.strat.lit(s) ? 1f : 1.35f) * (holdout(s) ? 0.3f : 1f);
                float bites = Math.min(popIn[s], rate * popIn[s] / (popIn[s] + dens));
                int b = stoch(bites, s * 7L + 1);
                popIn[s] -= b;
                int killed = stoch(b * 0.2f, s * 7L + 2);
                bodies[s] += killed;
                inf[s] += b - killed;
                w.stats.civDeaths += killed;
                w.stats.infectedTotal += b - killed;
                panic[s] = Math.min(1, panic[s] + 0.1f * Math.min(1, dt));
            }
            if (inf[s] > 0) {
                float turn = Math.max(0.2f * dt, inf[s] / (turnTime * w.infra.turnSlow(settlement[s])) * dt);
                int t = Math.min((int) Math.ceil(inf[s]), stoch(turn, s * 7L + 3));
                inf[s] -= t;
                if (inf[s] < 0) inf[s] = 0;
                zIn[s] += t;
                w.stats.zombiesMade += t;
            }
            // the infected go looking for help: the settlement's hospital fills with them
            if (inf[s] >= 4 && settlement[s] >= 0) {
                int hs = w.strat.hospitalSector(settlement[s]);
                if (hs >= 0 && hs != s) {
                    int m = Math.min((int) inf[s], stoch(inf[s] * 0.02f * dt, s * 7L + 4));
                    inf[s] -= m;
                    inf[hs] += m;
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
            if (hot[s] > 0) hot[s] -= dt;
        }
        drift();
        if (!w.strategic) demote();
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
                if ((e.flags[i] & Ents.F_RISES) != 0) zIn[w.r.sectorOfPx(e.x[i], e.y[i])]++;
                e.remove(i);
                continue;
            }
            if (e.loc[i] != Ents.FREE || e.armed(i) || i == w.playerEnt) continue;
            int s = w.r.sectorOfPx(e.x[i], e.y[i]);
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
            int m = Math.min(Math.min(zIn[s], 40), stoch(zIn[s] * rate + 0.3f, s * 11L + 1));
            for (int k = 0; k < m; k++) {
                float[] p = spot(s, k);
                if (p == null) break;
                Content.ZType zt = w.pickZType(-1, s * 131L + k);
                w.spawnZombie(p[0], p[1], zt);
                zIn[s]--;
            }
        }
        // people stay barricaded in while the dead are only outside; once the dead get in, they run
        int zHere = zO + zIn[s];
        if (popIn[s] > 0 && zIn[s] > 0 && room) {
            int m = Math.min(popIn[s], Math.min(12, stoch(0.5f + zIn[s] * 0.3f + panic[s] * 2, s * 11L + 2)));
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
        if (cam[s] && zHere == 0 && popIn[s] > 0 && room && !w.gov.curfew && w.daylight() > 0.3f) {
            int want = Math.min(28, popIn[s] / 25 + 2);
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
                if (w.doorX[b] >= 0) {
                    float jx = (Rng.unit(w.seed, w.tick + k, s * 3L + a) - 0.5f) * 10;
                    float jy = (Rng.unit(w.seed, w.tick + k, s * 3L + a + 1) - 0.5f) * 10;
                    if (w.r.walkablePx(w.doorX[b] + jx, w.doorY[b] + jy)) return new float[]{w.doorX[b] + jx, w.doorY[b] + jy};
                    return new float[]{w.doorX[b], w.doorY[b]};
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
            float a = attraction(t) + Rng.unit(w.seed, w.tick, s * 8L + d) * 2;
            if (a > best) {
                best = a;
                bd = d;
            }
        }
        if (bd < 0) {
            // nothing to go to: shamble about a little
            if (Rng.unit(w.seed, w.tick, s * 5L) < 0.08f) bd = Rng.hashInt(w.seed, w.tick, s, 8);
            if (bd < 0 || (connect[s] & (1 << bd)) == 0) return;
        }
        int t = neighbour(s, bd);
        int m = Math.min(zIn[s], stoch((zIn[s] * 0.02f + 0.2f) * dt * w.weather.zombieSpeed(), s * 13L));
        if (m <= 0) return;
        // a big mass on the move becomes a horde you can see on the map
        if (zIn[s] >= 300 && popIn[t] == 0 && settlement[s] < 0) {
            w.strat.formHorde(s, zIn[s] / 2);
            return;
        }
        zIn[s] -= m;
        zIn[t] += m;
    }

    private void drift() {
        for (int s = 0; s < n; s++) {
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
            driftX[s] = len > 0 ? bx / len : 0;
            driftY[s] = len > 0 ? by / len : 0;
        }
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
                zIn[s]++;
            } else {
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
        if ((w.e.flags[i] & Ents.F_INFECTED) != 0) inf[s] += 1;
        else popIn[s]++;
        if ((w.e.flags[i] & Ents.F_AMBIENT) != 0 && w.e.home[i] >= 0 && ambient[w.e.home[i]] > 0) ambient[w.e.home[i]]--;
        w.retire(i);
    }

    /** A zombie gets inside a building. */
    public void breakIn(int i) {
        int s = w.r.sectorOfPx(w.e.x[i], w.e.y[i]);
        zIn[s]++;
        w.retire(i);
    }
}
