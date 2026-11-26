package com.instantaxtion.zcs2;

/**
 * Civilians on the streets: out on errands, fleeing the dead, heading for shelter or an evacuation point.
 * Runs in parallel: reads anything, writes only its own entity's slot.
 */
final class CivAI {
    static final byte S_WANDER = 0, S_FLEE = 1, S_SHELTER = 2, S_EVAC = 3, S_HOME = 4, S_PLAYER = 5, S_MILITIA = 6;
    static final int I_ENTER = 20, I_EVAC = 21;
    private final World w;

    CivAI(World w) {
        this.w = w;
    }

    void think(int i, Parallel.Scratch s) {
        Ents e = w.e;
        if (i == w.playerEnt) {
            w.combat.playerThink(i, s);
            return;
        }
        if (e.aimT[i] > 0) e.aimT[i] -= World.DT;
        // think on alternate steps; between thinks keep going the same way
        if (((w.tick + i) & 1) != 0) return;
        if (e.state[i] == S_MILITIA) {
            militia(i, s);
            return;
        }
        float dt = World.DT * 2;
        Content.Unit u = w.unitOf(i);
        float x = e.x[i], y = e.y[i];
        int z = w.sp.nearestZombie(e, x, y, 150);
        float speed = u.walk;
        float dirX = 0, dirY = 0;
        if (z >= 0) {
            float dx = x - e.x[z], dy = y - e.y[z];
            float d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
            e.state[i] = S_FLEE;
            e.flags[i] |= Ents.F_FLEEING;
            e.timer[i] = 7;
            dirX = dx / d;
            dirY = dy / d;
            speed = u.run;
            // armed survivors make a stand when they can
            if (u.weapon != null && e.ammo[i] > 0 && d < u.weapon.range * 0.8f && e.aimT[i] <= 0) {
                e.intent[i] = Combat.I_SHOOT;
                e.intentArg[i] = z;
            }
            // lean towards somewhere safe
            float sx = e.gx[i] - x, sy = e.gy[i] - y, sl = (float) Math.sqrt(sx * sx + sy * sy);
            if (sl > 20 && d > 50) {
                dirX = dirX * 0.75f + sx / sl * 0.25f;
                dirY = dirY * 0.75f + sy / sl * 0.25f;
            }
        } else {
            switch (e.state[i]) {
                case S_FLEE: {
                    e.timer[i] -= dt;
                    float sx = e.gx[i] - x, sy = e.gy[i] - y;
                    if (e.timer[i] <= 0) {
                        int settle = w.sec.settlement[w.r.sectorOfPx(x, y)];
                        if (settle >= 0 && w.gov.evacuating(settle)) {
                            e.state[i] = S_EVAC;
                            float[] p = w.strat.evacPoint(settle);
                            e.gx[i] = p[0];
                            e.gy[i] = p[1];
                        } else {
                            e.state[i] = S_SHELTER;
                            pickShelter(i, s);
                        }
                    }
                    float sl = (float) Math.sqrt(sx * sx + sy * sy);
                    if (sl > 1) {
                        dirX = sx / sl;
                        dirY = sy / sl;
                    }
                    speed = u.run * 0.8f;
                    break;
                }
                case S_WANDER: {
                    e.timer[i] -= dt;
                    float sx = e.gx[i] - x, sy = e.gy[i] - y, sl = (float) Math.sqrt(sx * sx + sy * sy);
                    if (sl < 12 || e.timer[i] % 15 < dt) {
                        // somewhere else nearby, preferring pavements
                        for (int a = 0; a < 6; a++) {
                            float ang = Rng.unit(w.seed, e.uid[i], w.tick + a) * 6.283f;
                            float rad = 60 + Rng.unit(w.seed, e.uid[i], w.tick + a + 99) * 140;
                            float tx = x + (float) Math.cos(ang) * rad, ty = y + (float) Math.sin(ang) * rad;
                            int t = w.r.tileAtPx(tx, ty);
                            if (t == Tile.PAVEMENT || t == Tile.PLAZA || t == Tile.PARK || t == Tile.YARD || (a > 3 && Tile.walkable(t))) {
                                e.gx[i] = tx;
                                e.gy[i] = ty;
                                break;
                            }
                        }
                    }
                    if (e.timer[i] <= 0) {
                        e.state[i] = S_HOME;
                        pickShelter(i, s);
                    }
                    if (sl > 1) {
                        dirX = sx / sl;
                        dirY = sy / sl;
                    }
                    speed = u.walk * 0.8f;
                    break;
                }
                case S_SHELTER:
                case S_HOME: {
                    float sx = e.gx[i] - x, sy = e.gy[i] - y, sl = (float) Math.sqrt(sx * sx + sy * sy);
                    if (sl < 10) {
                        e.intent[i] = I_ENTER;
                        break;
                    }
                    dirX = sx / sl;
                    dirY = sy / sl;
                    speed = e.state[i] == S_SHELTER ? u.run * 0.7f : u.walk;
                    // the shelter filled with the dead meanwhile: find another
                    if (w.sec.zIn[w.r.sectorOfPx(e.gx[i], e.gy[i])] > 0 && Rng.unit(w.seed, w.tick, i) < 0.1f) pickShelter(i, s);
                    break;
                }
                case S_EVAC: {
                    float sx = e.gx[i] - x, sy = e.gy[i] - y, sl = (float) Math.sqrt(sx * sx + sy * sy);
                    if (sl < 40) {
                        e.intent[i] = I_EVAC;
                        break;
                    }
                    dirX = sx / sl;
                    dirY = sy / sl;
                    speed = u.walk * 1.2f;
                    break;
                }
                default:
            }
        }
        steer(i, dirX, dirY, speed, s);
    }

    /** An armed resident holding their street: shoot what comes, step back from what gets close. */
    private void militia(int i, Parallel.Scratch s) {
        Ents e = w.e;
        Content.Unit u = w.unitOf(i);
        float x = e.x[i], y = e.y[i];
        float range = u.weapon != null ? u.weapon.range : 120;
        int z = w.sp.nearestZombie(e, x, y, range);
        float dirX = 0, dirY = 0, speed = 0;
        if (z >= 0) {
            float dx = e.x[z] - x, dy = e.y[z] - y, d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
            e.face[i] = (float) Math.atan2(dy, dx);
            if (d < 24 || e.ammo[i] <= 0) {
                dirX = -dx / d;
                dirY = -dy / d;
                speed = u.run;
                if (e.ammo[i] <= 0) e.state[i] = S_FLEE;
            } else if (e.aimT[i] <= 0 && (d < 48 || w.r.lineOfSight(x, y, e.x[z], e.y[z]))) {
                e.intent[i] = Combat.I_SHOOT;
                e.intentArg[i] = z;
            }
        } else {
            float gx = e.gx[i] - x, gy = e.gy[i] - y, gd = (float) Math.sqrt(gx * gx + gy * gy);
            if (gd > 20) {
                dirX = gx / gd;
                dirY = gy / gd;
                speed = u.walk;
            }
        }
        steer(i, dirX, dirY, speed, s);
    }

    /** The nearest door in a sector free of the dead. */
    private void pickShelter(int i, Parallel.Scratch scr) {
        Ents e = w.e;
        int s0 = w.r.sectorOfPx(e.x[i], e.y[i]);
        float bd = Float.MAX_VALUE;
        float bx = e.x[i], by = e.y[i];
        for (int d = -1; d < 8; d++) {
            int s = d < 0 ? s0 : w.sec.neighbour(s0, d);
            if (s < 0 || w.sec.zIn[s] > 0 || w.sp.secZ[s] > 2) continue;
            int[] bl = w.secBuildings[s];
            int step = Math.max(1, bl.length / 24);
            for (int k = 0; k < bl.length; k += step) {
                int b = bl[k];
                float dx = w.doorX[b] - e.x[i], dy = w.doorY[b] - e.y[i], dd = dx * dx + dy * dy;
                if (d >= 0) dd += 200 * 200;
                if (dd < bd) {
                    bd = dd;
                    bx = w.doorX[b];
                    by = w.doorY[b];
                }
            }
        }
        e.gx[i] = bx;
        e.gy[i] = by;
    }

    /** Heads along (dx, dy), feeling round obstacles, keeping a little space from others. */
    void steer(int i, float dx, float dy, float speed, Parallel.Scratch s) {
        Ents e = w.e;
        float x = e.x[i], y = e.y[i];
        if (dx == 0 && dy == 0) {
            e.dvx[i] = 0;
            e.dvy[i] = 0;
            return;
        }
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        dx /= len;
        dy /= len;
        float[] turns = {0, 0.6f, -0.6f, 1.2f, -1.2f, 1.9f, -1.9f, 2.6f, -2.6f};
        float ox = dx, oy = dy;
        for (float t : turns) {
            float c = (float) Math.cos(t), sn = (float) Math.sin(t);
            float rx = dx * c - dy * sn, ry = dx * sn + dy * c;
            if (w.r.walkablePx(x + rx * 12, y + ry * 12)) {
                ox = rx;
                oy = ry;
                break;
            }
        }
        // keep a little apart from the people next to you
        float px = 0, py = 0;
        int cell = w.sp.cellOf(x, y);
        int[] L = w.sp.lList;
        int n = 0;
        for (int j = w.sp.lStart[cell]; j < w.sp.lEnd[cell] && n < 6; j++) {
            int o = L[j];
            if (o == i) continue;
            float ddx = x - e.x[o], ddy = y - e.y[o], d2 = ddx * ddx + ddy * ddy;
            if (d2 < 64 && d2 > 0.01f) {
                float d = (float) Math.sqrt(d2);
                px += ddx / d * (8 - d);
                py += ddy / d * (8 - d);
                n++;
            }
        }
        e.dvx[i] = ox * speed + px * 3;
        e.dvy[i] = oy * speed + py * 3;
    }
}
