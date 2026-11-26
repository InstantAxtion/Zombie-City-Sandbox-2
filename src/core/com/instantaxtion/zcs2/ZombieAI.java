package com.instantaxtion.zcs2;

/**
 * The dead: chase what they see, follow the scent field to what they can't, drift with their sector towards
 * people and lights when there's nothing to follow, claw at slow vehicles, and break into buildings with people
 * inside. Runs in parallel: reads anything, writes only its own slot.
 */
final class ZombieAI {
    static final int I_BITE = 1, I_CLAW = 2, I_BREAKIN = 3;
    private final World w;

    ZombieAI(World w) {
        this.w = w;
    }

    void think(int i, Parallel.Scratch s) {
        Ents e = w.e;
        Content.ZType zt = w.ztypeOf(i);
        float x = e.x[i], y = e.y[i];
        if (e.aimT[i] > 0) e.aimT[i] -= World.DT;
        int t = e.target[i];
        boolean hunting = t >= 0;
        // far from anything: think less often (the dead are patient)
        if (!hunting) {
            int every = e.timer[i] > 0 ? 2 : 4;
            if (((w.tick + i) % every) != 0) return;
        }
        float dt = World.DT;
        // keep or drop the target
        if (t >= 0) {
            if (e.loc[t] != Ents.FREE || e.kind[t] == Ents.ZOMBIE) t = -1;
            else {
                float dx = e.x[t] - x, dy = e.y[t] - y;
                if (dx * dx + dy * dy > 260 * 260) t = -1;
            }
        }
        if (t < 0 || ((w.tick + i) % 10) == 0) {
            float sight = zt.night && w.night() ? 170 : 125;
            int n = w.sp.nearestLiving(e, x, y, sight, -1, 0);
            if (n >= 0) {
                float dx = e.x[n] - x, dy = e.y[n] - y;
                if (dx * dx + dy * dy < 40 * 40 || w.r.lineOfSight(x, y, e.x[n], e.y[n])) t = n;
            }
        }
        e.target[i] = t;
        float dirX = 0, dirY = 0, speed;
        if (t >= 0) {
            float dx = e.x[t] - x, dy = e.y[t] - y;
            float d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
            float reach = zt.radius + 4 + 4;
            if (d < reach) {
                if (e.aimT[i] <= 0) {
                    e.intent[i] = I_BITE;
                    e.intentArg[i] = t;
                }
                speed = 4;
            } else speed = zt.run * surge(i);
            // round corners with the scent field, straight when in the open
            if (d > 40 && w.flow.downhill(x, y, s.f) != Flow.INF && (s.f[0] != 0 || s.f[1] != 0) && !w.nav.straightWalkable(x, y, e.x[t], e.y[t])) {
                dirX = s.f[0];
                dirY = s.f[1];
            } else {
                dirX = dx / d;
                dirY = dy / d;
            }
            e.timer[i] = 5;
        } else {
            int dist = w.flow.downhill(x, y, s.f);
            if (dist != Flow.INF && (s.f[0] != 0 || s.f[1] != 0)) {
                dirX = s.f[0];
                dirY = s.f[1];
                speed = dist <= 10 ? zt.run * surge(i) : zt.walk * 1.15f;
                e.timer[i] = 5;
            } else {
                // nothing to follow: shuffle along with the sector's drift
                int sec = w.r.sectorOfPx(x, y);
                long slot = (w.tick / 80 + i) ;
                float wa = Rng.unit(w.seed, e.uid[i], slot) * 6.283f;
                dirX = w.sec.driftX[sec] * 0.7f + (float) Math.cos(wa) * 0.5f;
                dirY = w.sec.driftY[sec] * 0.7f + (float) Math.sin(wa) * 0.5f;
                speed = zt.walk * 0.55f;
                if (e.timer[i] > 0) e.timer[i] -= dt * 4;
                // people inside a building next to me: get in
                if (w.sec.popIn[sec] > 0 && Rng.unit(w.seed, w.tick, e.uid[i]) < 0.004f && nextToBuilding(x, y)) {
                    e.intent[i] = I_BREAKIN;
                }
            }
            // a slow vehicle close by is something to claw at
            int v = w.veh.near(x, y, 22);
            if (v >= 0 && w.veh.spd[v] < 25 && !w.veh.type(v).flies) {
                float dx = w.veh.x[v] - x, dy = w.veh.y[v] - y, d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
                dirX = dx / d;
                dirY = dy / d;
                speed = zt.run * 0.6f;
                if (d < 20 && e.aimT[i] <= 0) {
                    e.intent[i] = I_CLAW;
                    e.intentArg[i] = v;
                }
            }
        }
        // spread out a little: the dead push against each other
        float px = 0, py = 0;
        int cell = w.sp.cellOf(x, y);
        int[] Z = w.sp.zList;
        int n = 0, start = w.sp.zStart[cell], end = w.sp.zEnd[cell];
        int stride = Math.max(1, (end - start) / 8);
        for (int j = start; j < end && n < 8; j += stride) {
            int o = Z[j];
            if (o == i) continue;
            float ddx = x - e.x[o], ddy = y - e.y[o], d2 = ddx * ddx + ddy * ddy;
            float minD = zt.radius * 2 + 1;
            if (d2 < minD * minD && d2 > 0.0001f) {
                float d = (float) Math.sqrt(d2);
                px += ddx / d * (minD - d);
                py += ddy / d * (minD - d);
            }
            n++;
        }
        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY);
        if (len > 0) {
            dirX /= len;
            dirY /= len;
        }
        // feel round walls in the way
        if (len > 0 && !w.r.walkablePx(x + dirX * 10, y + dirY * 10)) {
            float c = 0.8f;
            float rx = dirX * (float) Math.cos(c) - dirY * (float) Math.sin(c), ry = dirX * (float) Math.sin(c) + dirY * (float) Math.cos(c);
            float lx = dirX * (float) Math.cos(-c) - dirY * (float) Math.sin(-c), ly = dirX * (float) Math.sin(-c) + dirY * (float) Math.cos(-c);
            if (w.r.walkablePx(x + rx * 10, y + ry * 10)) {
                dirX = rx;
                dirY = ry;
            } else if (w.r.walkablePx(x + lx * 10, y + ly * 10)) {
                dirX = lx;
                dirY = ly;
            }
        }
        e.dvx[i] = dirX * speed + px * 4;
        e.dvy[i] = dirY * speed + py * 4;
    }

    /** Each of the dead has its own rhythm: bursts of speed and slack moments. */
    private float surge(int i) {
        float ph = (float) ((w.time * 0.7 + (w.e.uid[i] % 97) * 0.13) % 1.0);
        return ph < 0.25f ? 1.15f : 0.95f;
    }

    private boolean nextToBuilding(float x, float y) {
        for (int d = 0; d < 4; d++) {
            float ax = x + (d == 0 ? 12 : d == 1 ? -12 : 0), ay = y + (d == 2 ? 12 : d == 3 ? -12 : 0);
            if (w.r.tileAtPx(ax, ay) == Tile.BUILDING) return true;
        }
        return false;
    }
}
