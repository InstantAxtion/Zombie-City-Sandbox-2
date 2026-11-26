package com.instantaxtion.zcs2;

/**
 * The dead: chase what they see, follow the scent field to what they can't, drift with their sector towards
 * people and lights when there's nothing to follow, claw at slow vehicles, and break into buildings with people
 * inside. Crowds press on barricades, fences and walls until they give; the big ones smash them, shove cars aside,
 * and charge or lunge at the living. They stop to feed on the bitten, speed up in a frenzy round one victim, and keep
 * loosely with an alpha that roars. Runs in parallel: reads anything that the think phase doesn't write, and writes
 * only its own slot (here and in {@link Undead}).
 *
 * (Split into small methods so the JIT compiles each well: one big method was markedly slower.)
 */
final class ZombieAI {
    static final int I_BITE = 1, I_CLAW = 2, I_BREAKIN = 3, I_SMASH = 4, I_SLAM = 5, I_SHOVE = 6, I_ROAR = 7;
    // where the parts of a think leave their answer in the thread's scratch (f[0] and f[1] are the scent's)
    private static final int DX = 2, DY = 3, SPEED = 4, PRESS = 5, CLOSE = 6;
    private final World w;

    ZombieAI(World w) {
        this.w = w;
    }

    void think(int i, Parallel.Scratch s) {
        Ents e = w.e;
        Undead u = w.undead;
        int st = e.state[i];
        if ((st & Undead.S_INIT) == 0) u.init(i);
        if (e.aimT[i] > 0) e.aimT[i] -= World.DT;
        int t = e.target[i];
        // far from anything: think less often (the dead are patient)
        if (t < 0 && u.phase(i) == Undead.P_NONE) {
            int every = e.timer[i] > 2.5f ? 2 : 4;
            if ((((int) w.tick + i) & (every - 1)) != 0) return; // (every is 2 or 4: a mask, not a long division)
        }
        Content.ZType zt = w.ztypeOf(i);
        float x = e.x[i], y = e.y[i];
        float now = (float) w.time;
        // keep or drop the target
        if (t >= 0) {
            if (e.loc[t] != Ents.FREE || e.kind[t] == Ents.ZOMBIE) t = -1;
            else {
                float dx = e.x[t] - x, dy = e.y[t] - y;
                if (dx * dx + dy * dy > 260 * 260) t = -1;
            }
        }
        int heard = -1; // someone close by but out of sight (round a corner, behind a car)
        if (t < 0 || ((w.tick + i) % 10) == 0) {
            float sight = u.night && u.nightHunter(i, zt) ? 170 : 125;
            int n = w.sp.nearestLiving(e, x, y, sight, -1, 0);
            if (n >= 0) {
                float dx = e.x[n] - x, dy = e.y[n] - y;
                if (dx * dx + dy * dy < 40 * 40 || w.r.lineOfSight(x, y, e.x[n], e.y[n])) t = n;
                else if (dx * dx + dy * dy < 100 * 100) heard = n;
            }
        }
        if ((st & Undead.S_FEED) != 0 && feed(i, t, zt, now)) return;
        if (t < 0 && u.phase(i) != Undead.P_NONE) {
            // lost them mid-lunge: rest a moment
            u.setPhase(i, Undead.P_NONE);
            u.coolUntil[i] = now + 2;
        }
        e.target[i] = t;
        float[] f = s.f;
        f[CLOSE] = 1e9f;
        if (t >= 0) hunt(i, t, zt, now, s);
        else roam(i, heard, zt, now, s);
        // the big ones shove cars out of their way (and wreck them)
        if (zt.smash && f[PRESS] != 0 && e.intent[i] == 0 && e.aimT[i] <= 0) {
            int v = w.veh.near(x, y, zt.radius + 16);
            if (v >= 0 && !w.veh.flying(v) && w.veh.state[v] != Vehicles.V_NONE) {
                e.intent[i] = I_SHOVE;
                e.intentArg[i] = v;
            }
        }
        steer(i, zt, now, s);
    }

    /** At a meal (only someone alive close by takes them away from it); true if that's all this think does. */
    private boolean feed(int i, int t, Content.ZType zt, float now) {
        Ents e = w.e;
        Undead u = w.undead;
        int body = u.feeding(i, now);
        if (body < 0) return false;
        float x = e.x[i], y = e.y[i];
        if (t >= 0) {
            float ldx = e.x[t] - x, ldy = e.y[t] - y;
            if (ldx * ldx + ldy * ldy < 60 * 60) {
                u.stopFeeding(i);
                return false;
            }
        }
        e.target[i] = -1;
        float bx = e.x[body] - x, by = e.y[body] - y, bd = (float) Math.sqrt(bx * bx + by * by) + 0.01f;
        float sp = bd > 8 ? zt.walk : 0;
        e.dvx[i] = bx / bd * sp;
        e.dvy[i] = by / bd * sp;
        e.timer[i] = 5;
        return true;
    }

    /** After someone it can see: bite, lunge, charge, or close in. */
    private void hunt(int i, int t, Content.ZType zt, float now, Parallel.Scratch s) {
        Ents e = w.e;
        Undead u = w.undead;
        float[] f = s.f;
        float x = e.x[i], y = e.y[i];
        float dx = e.x[t] - x, dy = e.y[t] - y;
        float d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
        float reach = zt.radius + 4 + 4;
        float mul = u.speedMul(i);
        // a frenzy (several pressed round one victim: see steer) makes them all faster
        if (now < u.frenzyUntil[i]) mul *= u.frenzySpeed;
        float speed;
        int ph = u.phase(i);
        boolean straight = false;
        if (ph == Undead.P_WINDUP) {
            // gathering itself for the lunge
            speed = 0;
            straight = true;
            if (now >= u.phaseUntil[i]) {
                u.setPhase(i, Undead.P_LUNGE);
                u.phaseUntil[i] = now + 0.4f;
                u.dirX[i] = dx / d;
                u.dirY[i] = dy / d;
            }
        } else if (ph == Undead.P_LUNGE || ph == Undead.P_CHARGE) {
            boolean lunge = ph == Undead.P_LUNGE;
            speed = zt.run * mul * (lunge ? 3.2f : 2.1f);
            straight = true;
            if (d < reach + 2) {
                e.intent[i] = I_SLAM;
                e.intentArg[i] = t;
                u.setPhase(i, Undead.P_NONE);
                u.coolUntil[i] = now + (lunge ? 3.5f : 6f);
            } else if (now >= u.phaseUntil[i]) {
                u.setPhase(i, Undead.P_NONE);
                u.coolUntil[i] = now + (lunge ? 2.5f : 4f);
            }
        } else if (zt.lunge && now >= u.coolUntil[i] && d > reach && d < 70) {
            u.setPhase(i, Undead.P_WINDUP);
            u.phaseUntil[i] = now + 0.55f;
            speed = 0;
            straight = true;
        } else if (zt.charge && now >= u.coolUntil[i] && d > 40 && d < 120 && w.r.lineOfSight(x, y, e.x[t], e.y[t])) {
            // long enough to cover the ground at a charge
            u.setPhase(i, Undead.P_CHARGE);
            u.phaseUntil[i] = now + 0.4f + d / (zt.run * mul * 2.1f);
            speed = zt.run * mul * 2.1f;
            straight = true;
        } else if (d < reach) {
            if (e.aimT[i] <= 0) {
                e.intent[i] = I_BITE;
                e.intentArg[i] = t;
            }
            speed = 4;
        } else speed = zt.run * surge(i) * mul;
        float dirX, dirY;
        if (u.phase(i) == Undead.P_LUNGE) {
            // a lunge goes where it was aimed
            dirX = u.dirX[i];
            dirY = u.dirY[i];
        } else if (!straight && d > 40 && w.flow.downhill(x, y, f) != Flow.INF && (f[0] != 0 || f[1] != 0) && !w.nav.straightWalkable(x, y, e.x[t], e.y[t])) {
            // round corners with the scent field, straight when in the open
            dirX = f[0];
            dirY = f[1];
        } else {
            dirX = dx / d;
            dirY = dy / d;
        }
        if (speed == 0) e.face[i] = World.fastAtan2(dy, dx);
        e.timer[i] = 5;
        // the alpha roars when it has someone, and the dead round about come
        if (e.intent[i] == 0 && u.isLeader(i) && now >= u.roarAt[i]) {
            e.intent[i] = I_ROAR;
            u.roarAt[i] = now + u.roarEvery * (0.8f + 0.4f * Rng.unit(w.seed, e.uid[i], w.tick));
        }
        f[DX] = dirX;
        f[DY] = dirY;
        f[SPEED] = speed;
        f[PRESS] = 1;
        f[CLOSE] = d;
    }

    /** Nobody in sight: the scent, a horde's way, an alpha, someone heard, or the sector's drift. */
    private void roam(int i, int heard, Content.ZType zt, float now, Parallel.Scratch s) {
        Ents e = w.e;
        float[] f = s.f;
        int dist = w.flow.downhill(e.x[i], e.y[i], f);
        boolean scent = dist != Flow.INF && (f[0] != 0 || f[1] != 0);
        if (scent && dist <= 10) {
            // the living are close: everyone for themselves
            way(f, f[0], f[1], zt.run * surge(i) * w.undead.speedMul(i), true);
            e.timer[i] = 5;
        } else if (!together(i, scent, zt, now, f)) {
            if (scent) {
                way(f, f[0], f[1], zt.walk * 1.15f * w.undead.speedMul(i), true);
                e.timer[i] = 5;
            } else if (heard >= 0) {
                // nothing on the scent field here, but someone is just round the corner: go for them
                float dx = e.x[heard] - e.x[i], dy = e.y[heard] - e.y[i], d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
                way(f, dx / d, dy / d, zt.walk * 1.15f * w.undead.speedMul(i), true);
                e.timer[i] = 5;
            } else drift(i, zt, f);
        }
        // far down the scent, a new direction five times a second is plenty (half the thinking for the many)
        if (scent && dist > 16 && e.timer[i] > 2) e.timer[i] = 2;
        // an alpha on the scent of the living roars them on
        if (scent && dist <= 14 && e.intent[i] == 0) roar(i, now);
        // a slow vehicle close by is something to claw at
        if (zt.breakIn) claw(i, zt, f);
    }

    private static void way(float[] f, float dx, float dy, float speed, boolean pressing) {
        f[DX] = dx;
        f[DY] = dy;
        f[SPEED] = speed;
        f[PRESS] = pressing ? 1 : 0;
    }

    /**
     * With others: walking on with a horde's front, or keeping loosely with an alpha (a pack with its leader). True
     * if that decided the way.
     */
    private boolean together(int i, boolean scent, Content.ZType zt, float now, float[] f) {
        Ents e = w.e;
        Undead u = w.undead;
        int st = e.state[i];
        int phase = ((int) w.tick + i) & 0x7FFFFFFF;
        if ((st & (Undead.S_MARCH | Undead.S_LEAD)) == 0 && phase % 120 >= 4) return false;
        if (scent && (st & Undead.S_LEAD) != 0 && phase % 20 != 0) {
            // on the scent with its leader, at the pace worked out when it last looked at it
            way(f, f[0], f[1], zt.walk * 1.15f * u.speedMul(i) * u.pace[i], true);
            e.timer[i] = 5;
            return true;
        }
        if (!scent && u.marching(i, now)) {
            // the front of a horde, walking on the way it was going
            way(f, u.dirX[i], u.dirY[i], zt.walk * 1.15f * u.speedMul(i), true);
            e.timer[i] = 5;
            return true;
        }
        int lead = u.leaderOf(i);
        if (lead < 0 && phase % 120 < 4 && !u.isLeader(i)) {
            u.recruit(i);
            lead = u.leaderOf(i);
        }
        if (lead < 0) return false;
        float x = e.x[i], y = e.y[i], mul = u.speedMul(i);
        if (scent) {
            // on the scent with the others: its way round buildings, but held back when ahead of the leader and
            // hurried when behind, so they arrive together
            // (worked out once a second, when it looks at the leader)
            float lx = u.leadX[i] - x, ly = u.leadY[i] - y, ld = lx * lx + ly * ly;
            float behind = lx * f[0] + ly * f[1];
            u.pace[i] = ld < 60 * 60 ? 1 : behind > 0 ? 1.3f : 0.6f;
            way(f, f[0], f[1], zt.walk * 1.15f * mul * u.pace[i], true);
            e.timer[i] = 5;
            return true;
        }
        // the leader's way, and keep up (looking at it now: this is rare)
        float lx = e.x[lead] - x, ly = e.y[lead] - y, ld = (float) Math.sqrt(lx * lx + ly * ly) + 0.01f;
        float vx = e.vx[lead], vy = e.vy[lead], vl = (float) Math.sqrt(vx * vx + vy * vy);
        float pull = ld > 90 ? 0.8f : ld > 40 ? 0.25f : 0;
        way(f, (vl > 1 ? vx / vl * 0.6f : 0) + lx / ld * pull, (vl > 1 ? vy / vl * 0.6f : 0) + ly / ld * pull,
                ld > 90 ? zt.walk * 1.3f * mul : Math.max(zt.walk * 0.5f, Math.min(zt.walk * 1.2f, vl * 1.05f)) * mul, false);
        if (e.timer[i] > 0) e.timer[i] -= World.DT * 4;
        return true;
    }

    /** Nothing to follow: shuffle along with the sector's drift (and get into a building with people in it). */
    private void drift(int i, Content.ZType zt, float[] f) {
        Ents e = w.e;
        float x = e.x[i], y = e.y[i];
        int sec = w.r.sectorOfPx(x, y);
        long slot = (w.tick / 80 + i);
        float wa = Rng.unit(w.seed, e.uid[i], slot) * 6.283f;
        way(f, w.sec.driftX[sec] * 0.7f + (float) Math.cos(wa) * 0.5f, w.sec.driftY[sec] * 0.7f + (float) Math.sin(wa) * 0.5f,
                zt.walk * 0.55f * w.undead.speedMul(i), false);
        if (e.timer[i] > 0) e.timer[i] -= World.DT * 4;
        // people inside a building next to me: get in
        if (zt.breakIn && w.sec.popIn[sec] > 0 && Rng.unit(w.seed, w.tick, e.uid[i]) < 0.004f && nextToBuilding(x, y)) {
            e.intent[i] = I_BREAKIN;
        }
    }

    /** An alpha roars now and then (the intent). */
    private void roar(int i, float now) {
        Undead u = w.undead;
        if (!u.isLeader(i) || now < u.roarAt[i]) return;
        w.e.intent[i] = I_ROAR;
        u.roarAt[i] = now + u.roarEvery * (0.8f + 0.4f * Rng.unit(w.seed, w.e.uid[i], w.tick));
    }

    /** A slow vehicle close by: claw at it (the big ones shove it). */
    private void claw(int i, Content.ZType zt, float[] f) {
        Ents e = w.e;
        float x = e.x[i], y = e.y[i];
        int v = w.veh.near(x, y, 22);
        if (v < 0 || w.veh.spd[v] >= 25 || w.veh.type(v).flies) return;
        float dx = w.veh.x[v] - x, dy = w.veh.y[v] - y, d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
        f[DX] = dx / d;
        f[DY] = dy / d;
        f[SPEED] = zt.run * 0.6f;
        if (d < 20 && e.aimT[i] <= 0 && e.intent[i] == 0) {
            e.intent[i] = zt.smash ? I_SHOVE : I_CLAW;
            e.intentArg[i] = v;
        }
    }

    /** The way chosen, made good: spread out a little, beat on a barricade in the way or feel round a wall. */
    private void steer(int i, Content.ZType zt, float now, Parallel.Scratch s) {
        Ents e = w.e;
        float[] f = s.f;
        float x = e.x[i], y = e.y[i];
        float dirX = f[DX], dirY = f[DY], speed = f[SPEED];
        // spread out a little: the dead push against each other
        float px = 0, py = 0;
        int cell = w.sp.cellOf(x, y);
        int[] Z = w.sp.zList;
        int n = 0, crowd = 1, start = w.sp.zStart[cell], end = w.sp.zEnd[cell];
        int stride = Math.max(1, (end - start) / 8);
        float minD = zt.radius * 2 + 1;
        for (int j = start; j < end && n < 8; j += stride) {
            int o = Z[j];
            if (o == i) continue;
            float ddx = x - e.x[o], ddy = y - e.y[o], d2 = ddx * ddx + ddy * ddy;
            if (d2 < 28 * 28) crowd++;
            if (d2 < minD * minD && d2 > 0.0001f) {
                float d = (float) Math.sqrt(d2);
                px += ddx / d * (minD - d);
                py += ddy / d * (minD - d);
            }
            n++;
        }
        // a frenzy: close on a victim with the others pressed round
        Undead u = w.undead;
        if (f[CLOSE] < 50 && crowd >= u.frenzyCrowd) u.frenzyUntil[i] = now + 0.6f;
        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY);
        if (len > 0) {
            dirX /= len;
            dirY /= len;
            if (!Tile.walkable(tileAt(x + dirX * 10, y + dirY * 10))) {
                if (f[PRESS] != 0 && zt.smashPower > 0 && e.intent[i] == 0 && e.aimT[i] <= 0 && smash(i, zt, dirX, dirY)) {
                    // pressing on a barricade, fence or wall: beat on it (a crowd wears it down)
                    speed *= 0.3f;
                } else {
                    // feel round walls in the way
                    float c = 0.8f, cs = (float) Math.cos(c), sn = (float) Math.sin(c);
                    float rx = dirX * cs - dirY * sn, ry = dirX * sn + dirY * cs;
                    float lx = dirX * cs + dirY * sn, ly = -dirX * sn + dirY * cs;
                    if (Tile.walkable(tileAt(x + rx * 10, y + ry * 10))) {
                        dirX = rx;
                        dirY = ry;
                    } else if (Tile.walkable(tileAt(x + lx * 10, y + ly * 10))) {
                        dirX = lx;
                        dirY = ly;
                    }
                }
            }
        }
        // the rules sheet's pace for the dead, and razor wire holding them back (Defences)
        speed *= w.rules.zombieSpeed() * w.defs.wireSlow(x, y);
        e.dvx[i] = dirX * speed + px * 4;
        e.dvy[i] = dirY * speed + py * 4;
    }

    /** A barricade, fence or wall just ahead: beat on it (the intent); true if there is one. */
    private boolean smash(int i, Content.ZType zt, float dirX, float dirY) {
        Ents e = w.e;
        float ax = e.x[i] + dirX * (zt.radius + 6), ay = e.y[i] + dirY * (zt.radius + 6);
        if (ax < 0 || ay < 0) return false;
        int tx = (int) (ax / Tile.SIZE), ty = (int) (ay / Tile.SIZE);
        if (!w.r.in(tx, ty) || !Tile.smashable(w.r.t(tx, ty))) return false;
        e.intent[i] = I_SMASH;
        e.intentArg[i] = ty * w.r.W + tx;
        return true;
    }

    /**
     * The tile at a point: the same as Region.tileAtPx, without Math.floor (which isn't an intrinsic everywhere and
     * showed in the profile): below zero is off the map either way.
     */
    private int tileAt(float px, float py) {
        if (px < 0 || py < 0) return Tile.DEEP;
        return w.r.t((int) (px / Tile.SIZE), (int) (py / Tile.SIZE));
    }

    /** Each of the dead has its own rhythm: bursts of speed and slack moments. */
    private float surge(int i) {
        double v = w.time * 0.7 + (w.e.uid[i] % 97) * 0.13;
        float ph = (float) (v - (long) v); // the same as v % 1.0 (v >= 0), without the slow remainder
        return ph < 0.25f ? 1.15f : 0.95f;
    }

    private boolean nextToBuilding(float x, float y) {
        for (int d = 0; d < 4; d++) {
            float ax = x + (d == 0 ? 12 : d == 1 ? -12 : 0), ay = y + (d == 2 ? 12 : d == 3 ? -12 : 0);
            if (tileAt(ax, ay) == Tile.BUILDING) return true;
        }
        return false;
    }
}
