package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Police officers and soldiers on foot. The squad decides where (Squads); this decides how.
 *
 * Every half second or so each one scores what it could do and picks the best: engage, move to cover or a better
 * line of fire, fall back when the odds are bad, pull back when wounded (while the others cover), reload in cover,
 * search where the dead were last seen, rejoin the squad, hold as the rear guard while the squad falls back, or its
 * role's job (a medic treating the wounded, a marksman on a rooftop, a traffic officer at a junction). Between
 * decisions it carries the choice out, shooting whatever it can see and reach.
 *
 * Each one has nerve: their training (doctrine), rank and role, a commander or command post near, losses seen,
 * the odds and their ammunition. When it runs out they break and run to the squad's fallback point, and rally
 * once they're clear and steady again.
 *
 * Runs in parallel: reads anything, writes only its own slot (its entity and its own entries here). What touches
 * others (healing, the dog finding the bitten, the drone's picture, SWAT clearing buildings) is done once a second
 * on one thread, in the module.
 */
final class ArmedAI {
    static final int I_GRENADE = 11;

    // what each one is doing
    static final byte A_DUTY = 0, A_ENGAGE = 1, A_REPOSITION = 2, A_FALLBACK = 3, A_WOUNDED = 4, A_RELOAD = 5, A_SEARCH = 6,
            A_REJOIN = 7, A_REARGUARD = 8, A_COVER = 9, A_HEAL = 10, A_OVERWATCH = 11, A_JUNCTION = 12, A_FLEE = 13;
    static final String[] ACT_NAMES = {"On duty", "Engaging", "Moving to cover", "Falling back", "Wounded, pulling back",
            "Reloading", "Searching", "Rejoining the squad", "Rear guard", "Covering the wounded", "Treating the wounded",
            "Overwatch", "Holding the junction", "Broken, running"};
    // roles and doctrines (per unit type)
    static final byte R_NONE = 0, R_MEDIC = 1, R_COMMANDER = 2, R_MARKSMAN = 3, R_K9 = 4, R_TRAFFIC = 5, R_DRONE = 6;
    static final byte D_NONE = 0, D_POLICE = 1, D_ARMY = 2, D_SWAT = 3;
    // per-person bits
    static final int B_PERCHED = 1, B_COVER = 2, B_BROKEN = 4, B_AURA = 8, B_TOLD = 16;
    /** Marks on people (Ents.flags; high bits, clear of the rest): treated by a medic; found by a dog. */
    static final int F_STABILISED = 1 << 24, F_SPOTTED = 1 << 25;

    private final World w;
    final byte[] roleOf, docOf;

    // per entity (indexed by slot; own[] holds the uid the state belongs to, so a reused slot starts fresh)
    int[] own = new int[0], aux = new int[0], seenLoss = new int[0];
    float[] nerve = new float[0], shock = new float[0], actT = new float[0], reloadT = new float[0], gcool = new float[0],
            px = new float[0], py = new float[0], lastX = new float[0], lastY = new float[0], lastT = new float[0],
            threat = new float[0];
    byte[] act = new byte[0], bits = new byte[0];
    short[] mag = new short[0];

    /** Per settlement: seconds the authorities still see it from a drone. */
    float[] droneT;
    /** Drones up (x, y pairs), refreshed every second. */
    float[] drones = new float[16];
    int droneN;
    /** What the special roles did (for the report and the tests). */
    int healed, stabilised, spotted, isolated, broke;
    private int[] swatIn;
    private int[] swatTouched;

    final Module module;

    ArmedAI(World w) {
        this.w = w;
        int n = w.c.units.size();
        roleOf = new byte[n];
        docOf = new byte[n];
        for (Content.Unit u : w.c.units) {
            String r = u.role == null ? "" : u.role;
            roleOf[u.index] = r.equals("medic") ? R_MEDIC : r.equals("commander") ? R_COMMANDER : r.equals("marksman") ? R_MARKSMAN
                    : r.equals("k9") ? R_K9 : r.equals("traffic") ? R_TRAFFIC : r.equals("drone") ? R_DRONE : R_NONE;
            String d = u.doctrine == null ? "" : u.doctrine;
            docOf[u.index] = d.equals("police") ? D_POLICE : d.equals("army") ? D_ARMY : d.equals("swat") ? D_SWAT : D_NONE;
        }
        droneT = new float[Math.max(1, w.r.settlements.size())];
        module = new State();
    }

    // ------------------------------------------------------------------ state

    /** Room for every slot (called on the simulation thread before the parallel think). */
    void prepare() {
        int n = w.e.cap;
        if (own.length >= n) return;
        own = grow(own, n); aux = grow(aux, n); seenLoss = grow(seenLoss, n);
        nerve = grow(nerve, n); shock = grow(shock, n); actT = grow(actT, n); reloadT = grow(reloadT, n); gcool = grow(gcool, n);
        px = grow(px, n); py = grow(py, n); lastX = grow(lastX, n); lastY = grow(lastY, n); lastT = grow(lastT, n); threat = grow(threat, n);
        act = grow(act, n); bits = grow(bits, n); mag = grow(mag, n);
    }

    private static int[] grow(int[] a, int n) { return java.util.Arrays.copyOf(a, n); }
    private static float[] grow(float[] a, int n) { return java.util.Arrays.copyOf(a, n); }
    private static byte[] grow(byte[] a, int n) { return java.util.Arrays.copyOf(a, n); }
    private static short[] grow(short[] a, int n) { return java.util.Arrays.copyOf(a, n); }

    /** Is the state in slot i this person's (and not left over from someone who had the slot before)? */
    boolean has(int i) {
        return i < own.length && own[i] == w.e.uid[i];
    }

    private void fresh(int i) {
        Ents e = w.e;
        Content.Unit u = w.unitOf(i);
        own[i] = e.uid[i];
        nerve[i] = Math.min(1, u.nerve + Ranks.nerve(e.rank[i]));
        shock[i] = 0;
        act[i] = A_DUTY;
        actT[i] = Rng.unit(w.seed, e.uid[i], 77) * 0.5f;
        reloadT[i] = 0;
        gcool[i] = 0;
        px[i] = e.x[i];
        py[i] = e.y[i];
        lastT[i] = 999;
        threat[i] = 999;
        aux[i] = -1;
        bits[i] = 0;
        mag[i] = (short) (u.weapon != null && u.weapon.mag > 0 ? Math.min(u.weapon.mag, e.ammo[i]) : -1);
        Squads.Squad s = e.squad[i] >= 0 ? w.sq.get(e.squad[i]) : null;
        seenLoss[i] = s == null ? 0 : s.losses;
    }

    byte role(int i) {
        return roleOf[w.e.type[i]];
    }

    byte doctrine(int i) {
        return docOf[w.e.type[i]];
    }

    /** What person i is doing, for the inspector. */
    String doing(int i) {
        if (!has(i)) return "";
        return ACT_NAMES[act[i]];
    }

    /** The mean nerve of the squad's people on foot (the squad holds longer when its people are steady). */
    float squadNerve(Squads.Squad s, float fallback) {
        float sum = 0;
        int n = 0;
        for (int k = 0; k < s.n; k++) {
            int m = s.mem[k];
            if (w.e.loc[m] != Ents.FREE || !has(m)) continue;
            sum += nerve[m];
            n++;
        }
        return n == 0 ? fallback : sum / n;
    }

    /** Has a drone seen this settlement lately? (The authorities' picture of it is sharper.) */
    boolean seen(int settlement) {
        return settlement >= 0 && settlement < droneT.length && droneT[settlement] > 0;
    }

    // ------------------------------------------------------------------ the armed side of a shot (Combat, one thread)

    /** How much better (or worse) person i aims right now. */
    float aimMul(int i) {
        if (!has(i)) return 1;
        float m = 1;
        int b = bits[i];
        if ((b & B_AURA) != 0) m *= 1.08f;
        if ((b & B_PERCHED) != 0) m *= 1.15f;
        if ((b & B_COVER) != 0 && docOf[w.e.type[i]] != D_ARMY) m *= 1.08f;
        if (w.defs.count[Defences.SANDBAGS] > 0 && w.defs.sandbagged(w.e.x[i], w.e.y[i])) m *= 1.12f;
        // shaky hands
        if (nerve[i] < 0.35f) m *= 0.85f + 0.4f * nerve[i];
        return m;
    }

    float rangeMul(int i) {
        return has(i) && (bits[i] & B_PERCHED) != 0 ? 1.2f : 1;
    }

    /** A round fired: one fewer in the magazine. */
    void fired(int i) {
        if (has(i) && mag[i] > 0) mag[i]--;
    }

    void threw(int i) {
        if (has(i)) gcool[i] = 9;
    }

    // ------------------------------------------------------------------ thinking (parallel)

    void think(int i, Parallel.Scratch s) {
        Ents e = w.e;
        if (e.aimT[i] > 0) e.aimT[i] -= World.DT;
        if (i == w.playerEnt) {
            // a police officer the player moves (the stick), shooting by themself
            w.combat.playerThink(i, s);
            return;
        }
        if (own[i] != e.uid[i]) fresh(i);
        if (((w.tick + i) & 1) != 0) return;
        final float dt = World.DT * 2;
        if (gcool[i] > 0) gcool[i] -= dt;
        Content.Unit u = w.unitOf(i);
        Content.Weapon wp = u.weapon;
        float x = e.x[i], y = e.y[i];
        float range = wp == null ? 0 : wp.range * rangeMul(i);
        int ammo = e.ammo[i];
        // magazines: a reload under way, or the magazine refilled after a restock
        if (reloadT[i] > 0) {
            reloadT[i] -= dt;
            if (reloadT[i] <= 0) mag[i] = (short) Math.min(wp != null ? wp.mag : 0, ammo);
        }
        if (mag[i] > ammo) mag[i] = (short) ammo;
        if (wp != null && wp.mag > 0 && mag[i] < 0) mag[i] = (short) Math.min(wp.mag, ammo);
        boolean loaded = wp != null && ammo > 0 && (wp.mag <= 0 || (mag[i] > 0 && reloadT[i] <= 0));
        // the nearest of the dead (looked for less often when nothing is close)
        int z = e.target[i];
        if (z >= 0 && (e.loc[z] != Ents.FREE || e.kind[z] != Ents.ZOMBIE)) z = -1;
        if (z < 0 || ((w.tick / 2 + i) % 3) == 0) z = w.sp.nearestZombie(e, x, y, Math.max(range, 120));
        e.target[i] = z;
        float zd = Float.MAX_VALUE, zx = 0, zy = 0;
        if (z >= 0) {
            zx = e.x[z] - x;
            zy = e.y[z] - y;
            zd = (float) Math.sqrt(zx * zx + zy * zy) + 0.01f;
            lastX[i] = e.x[z];
            lastY[i] = e.y[z];
            lastT[i] = 0;
        } else if (lastT[i] < 999) lastT[i] += dt;
        threat[i] = zd;
        Squads.Squad sq = e.squad[i] >= 0 ? w.sq.get(e.squad[i]) : null;
        // ---- decide, every half second or so
        actT[i] -= dt;
        if (actT[i] <= 0) {
            decide(i, u, sq, z, zd, loaded, s);
            actT[i] = 0.4f + Rng.unit(w.seed, e.uid[i], w.tick) * 0.3f;
        }
        // ---- and carry it out
        float gx = e.gx[i] - x, gy = e.gy[i] - y;
        float gd = (float) Math.sqrt(gx * gx + gy * gy);
        boolean urgent = e.state[i] != 0;
        float dirX = 0, dirY = 0, speed = 0;
        boolean holdFire = false;
        int a = act[i];
        boolean perched = false;
        switch (a) {
            case A_FLEE:
            case A_FALLBACK:
            case A_WOUNDED:
            case A_REPOSITION:
            case A_SEARCH:
            case A_HEAL:
            case A_OVERWATCH:
            case A_JUNCTION:
            case A_REJOIN: {
                float tx = px[i], ty = py[i];
                if (a == A_REJOIN && near(px[i], py[i], x, y, 12)) {
                    tx = e.gx[i];
                    ty = e.gy[i];
                }
                if (a == A_HEAL && aux[i] >= 0 && e.loc[aux[i]] == Ents.FREE) {
                    tx = e.x[aux[i]];
                    ty = e.y[aux[i]];
                }
                float dx = tx - x, dy = ty - y, d = (float) Math.sqrt(dx * dx + dy * dy);
                float stop = a == A_HEAL ? 10 : a == A_OVERWATCH ? 6 : 8;
                if (d > stop) {
                    dirX = dx / d;
                    dirY = dy / d;
                    speed = a == A_SEARCH || (a == A_WOUNDED && zd > 120) || (d < 30 && a != A_FLEE) ? u.walk : u.run;
                } else if (a == A_SEARCH) {
                    // nothing here any more
                    lastT[i] = 999;
                    actT[i] = 0;
                }
                if (a == A_OVERWATCH && d <= 14) perched = true;
                if (a == A_SEARCH) holdFire = false;
                break;
            }
            case A_REARGUARD:
            case A_COVER:
            case A_ENGAGE:
            case A_RELOAD: {
                // stand (or hold the spot chosen) and fight
                float dx = px[i] - x, dy = py[i] - y, d = (float) Math.sqrt(dx * dx + dy * dy);
                if (a == A_RELOAD && d > 6) {
                    dirX = dx / d;
                    dirY = dy / d;
                    speed = u.run;
                } else if (a == A_ENGAGE && gd > 50 && sq != null && docOf[e.type[i]] == D_ARMY && sq.mode == Squads.M_FOOT
                        && (sq.order == Squads.O_MOVE || sq.order == Squads.O_RESPOND)) {
                    // bounding: half the squad moves up while the other half covers, turn about every 3 s
                    int slot = 0;
                    for (int k = 0; k < sq.n; k++) if (sq.mem[k] == i) slot = k;
                    if (((slot + (int) (w.time / 3)) & 1) == 0 && w.sp.zombiesNear(e, x, y, 90, 4) < 4) {
                        dirX = gx / gd;
                        dirY = gy / gd;
                        speed = u.run * 0.85f;
                    }
                } else if (a == A_ENGAGE && gd > 50 && z >= 0 && (gx * zx + gy * zy <= 0 || w.sp.zombiesNear(e, x, y, 100, 6) < 6)) {
                    // edge towards my place if I'm far off it, but not into a pack
                    dirX = gx / gd;
                    dirY = gy / gd;
                    speed = u.walk * 0.6f;
                }
                break;
            }
            default: {
                // duty, rejoining: where my squad wants me
                if (gd > 6) {
                    dirX = gx / gd;
                    dirY = gy / gd;
                    speed = gd > 70 || urgent || a == A_REJOIN ? u.run : u.walk;
                    if (gd < 20) speed *= gd / 20;
                    if (z >= 0 && zd < 130 && (gx * zx + gy * zy) > 0.2f * gd * zd) {
                        if (!loaded) {
                            // nothing to shoot with and the dead ahead: never walk into them; go round, or wait
                            float sx = -zy / zd, sy = zx / zd;
                            if (sx * gx + sy * gy < 0) {
                                sx = -sx;
                                sy = -sy;
                            }
                            dirX = sx * 0.8f - zx / zd * 0.6f;
                            dirY = sy * 0.8f - zy / zd * 0.6f;
                            speed = u.run;
                        } else if (zd < 110 && w.sp.zombiesNear(e, x, y, 110, 6) >= 6) {
                            // the dead close ahead that I can't shoot yet: don't walk into them, wait for them to show
                            speed = 0;
                            e.face[i] = (float) Math.atan2(zy, zx);
                        }
                    }
                }
            }
        }
        bits[i] = (byte) (perched ? bits[i] | B_PERCHED : bits[i] & ~B_PERCHED);
        // too close: get out of reach first (faster than the dead, so backing off works)
        float keep = wp != null && wp.range < 100 ? 18 : 30;
        if (!loaded) keep = reloadT[i] > 0 ? 55 : 110; // nothing to shoot with: stay well clear
        if (a == A_FLEE) keep = 40;
        if (z >= 0 && zd < keep) {
            // away from all the dead close round me, not just the nearest (backing off one into another kills)
            float ax = -zx / zd, ay = -zy / zd;
            if (escape(i, s.f)) {
                ax = s.f[0];
                ay = s.f[1];
            }
            float tx = (a == A_FALLBACK || a == A_WOUNDED || a == A_RELOAD || a == A_FLEE ? px[i] : e.gx[i]) - x;
            float ty = (a == A_FALLBACK || a == A_WOUNDED || a == A_RELOAD || a == A_FLEE ? py[i] : e.gy[i]) - y;
            float td = (float) Math.sqrt(tx * tx + ty * ty);
            if (td > 1 && tx * ax + ty * ay > -0.3f * td) {
                ax = ax * 0.8f + tx / td * 0.2f;
                ay = ay * 0.8f + ty / td * 0.2f;
            }
            dirX = ax;
            dirY = ay;
            speed = u.run;
        }
        // shoot whatever I can see and reach
        // the player's "hold fire": only when the dead are on them
        if (sq != null && sq.stance == Squads.STANCE_HOLD && zd > 22) holdFire = true;
        if (z >= 0 && loaded && !holdFire && e.aimT[i] <= 0 && zd <= range && (perched || seen(i, z, zd))) {
            if (u.grenade != null && gcool[i] <= 0 && zd > 60 && w.sp.zombiesNear(e, e.x[z], e.y[z], 40, 6) >= 6
                    && w.sp.livingNear(e, e.x[z], e.y[z], u.grenade.blast + 10, 1) == 0) {
                e.intent[i] = I_GRENADE;
            } else e.intent[i] = Combat.I_SHOOT;
            e.intentArg[i] = z;
            if (speed == 0) e.face[i] = (float) Math.atan2(zy, zx);
        }
        w.civStep(i, dirX, dirY, speed, s);
    }

    /** Scores what person i could do now and picks the best. Writes only i's own state. */
    private void decide(int i, Content.Unit u, Squads.Squad sq, int z, float zd, boolean loaded, Parallel.Scratch scr) {
        Ents e = w.e;
        float x = e.x[i], y = e.y[i];
        Content.Weapon wp = u.weapon;
        byte role = roleOf[e.type[i]], doc = docOf[e.type[i]];
        float range = wp == null ? 0 : wp.range * rangeMul(i);
        int press = w.sp.zombiesNear(e, x, y, 110, 24);
        int close = press == 0 ? 0 : w.sp.zombiesNear(e, x, y, 50, 8);
        float hpShare = e.hp[i] / Math.max(1, e.maxHp[i]);
        int ammo = e.ammo[i];
        float ammoShare = wp == null ? 0 : ammo / (float) Math.max(1, wp.ammo);
        // ---- who's near: friends, a commander, a medic, a wounded buddy
        int allies = 0, buddyHurt = -1, medic = -1;
        boolean commander = false;
        float medicD = 260 * 260, hurtD = 70 * 70;
        {
            float rad = 170;
            int cx0 = Math.max(0, (int) ((x - rad) / Spatial.CELL)), cx1 = Math.min(w.sp.cw - 1, (int) ((x + rad) / Spatial.CELL));
            int cy0 = Math.max(0, (int) ((y - rad) / Spatial.CELL)), cy1 = Math.min(w.sp.ch - 1, (int) ((y + rad) / Spatial.CELL));
            for (int cy = cy0; cy <= cy1; cy++)
                for (int cx = cx0; cx <= cx1; cx++) {
                    int c = w.sp.cell(cx, cy);
                    for (int j = w.sp.lStart[c]; j < w.sp.lEnd[c]; j++) {
                        int o = w.sp.lList[j];
                        if (o == i || !e.armed(o)) continue;
                        float dx = e.x[o] - x, dy = e.y[o] - y, d2 = dx * dx + dy * dy;
                        if (d2 < 100 * 100) allies++;
                        byte ro = roleOf[e.type[o]];
                        if (ro == R_COMMANDER && d2 < 160 * 160) commander = true;
                        if (ro == R_MEDIC && d2 < medicD) {
                            medicD = d2;
                            medic = o;
                        }
                        if (sq != null && e.squad[o] == sq.id && d2 < hurtD && e.hp[o] < e.maxHp[o] * 0.45f) {
                            hurtD = d2;
                            buddyHurt = o;
                        }
                    }
                }
        }
        // ---- nerve
        if (sq != null && sq.losses > seenLoss[i]) {
            shock[i] = Math.min(0.6f, shock[i] + 0.15f * (sq.losses - seenLoss[i]));
            seenLoss[i] = sq.losses;
        }
        shock[i] = Math.max(0, shock[i] - 0.02f * 0.5f);
        float target = u.nerve + Ranks.nerve(e.rank[i]);
        if (doc == D_SWAT) target += 0.08f;
        else if (doc == D_ARMY) target += 0.04f;
        if (role == R_COMMANDER) target += 0.1f;
        if (commander) target += 0.15f;
        if (sq != null && sq.leader(e) == i) target += 0.05f;
        if (allies >= 3) target += 0.05f;
        if (sq != null && sq.home >= 0) {
            // the command post (station, base or formation HQ) close by
            Region.Facility f = w.r.facilities.get(sq.home);
            float dx = f.x - x, dy = f.y - y;
            if (dx * dx + dy * dy < 400 * 400) target += 0.08f;
            else if (w.defs.postNear(x, y)) target += 0.08f;
        } else if (w.defs.postNear(x, y)) target += 0.08f; // a command post the player set up (Defences)
        if (sq != null && sq.stance == Squads.STANCE_AGGRESSIVE) target += 0.05f;
        target -= shock[i];
        if (wp != null && ammoShare < 0.15f) target -= 0.12f;
        if (wp != null && ammo == 0) target -= 0.2f;
        if (hpShare < 0.5f) target -= 0.1f;
        target -= Math.min(0.4f, Math.max(0, press - (allies + 1) * 3) * 0.025f);
        if (w.night()) target -= 0.04f;
        target = Math.max(0, Math.min(1, target));
        float n = nerve[i];
        n += (target - n) * (target < n ? 0.45f : 0.2f);
        nerve[i] = n;
        bits[i] = (byte) (commander ? bits[i] | B_AURA : bits[i] & ~B_AURA);
        boolean broken = (bits[i] & B_BROKEN) != 0;
        // panic takes them before the dead are on them (in contact, they fight on)
        if (!broken && n < 0.22f && press >= 3 && close == 0) broken = true;
        else if (broken && n >= 0.4f && close == 0) broken = false;
        bits[i] = (byte) (broken ? bits[i] | B_BROKEN : bits[i] & ~(B_BROKEN | B_TOLD));
        // ---- the fallback point: the squad's vehicle, or behind its line, or just away from the dead
        float rx, ry;
        {
            float[] rp = rally(i, sq, z, scr.f);
            rx = rp[0];
            ry = rp[1];
        }
        int was = act[i];
        if (broken) {
            choose(i, A_FLEE, rx, ry);
            return;
        }
        // ---- the scores
        float best = 0.3f; // duty
        byte pick = A_DUTY;
        float bx = e.gx[i], by = e.gy[i];
        boolean inRange = z >= 0 && zd <= range;
        float danger = Math.min(0.3f, close * 0.06f + (press > 6 ? 0.1f : 0));
        float capacity = (allies + 1) * (2.5f + 4 * n);
        // engage
        if (inRange && loaded) {
            float sc = 1.0f - danger + (was == A_ENGAGE ? 0.1f : 0);
            if (sc > best) {
                best = sc;
                pick = A_ENGAGE;
                bx = x;
                by = y;
            }
        }
        // fall back: the odds are bad, or nothing to fight with and the dead about
        if (z >= 0 && zd < 160) {
            float sc = (press - capacity) / Math.max(1, capacity);
            if (!loaded && reloadT[i] <= 0 && (wp == null || ammo == 0)) sc = Math.max(sc, 1.2f);
            if (was == A_FALLBACK) sc += 0.3f;
            // not through the ones already on me (then I fight), unless I have nothing to fight with
            if (close >= 1 && loaded) sc = 0;
            // nor past the dead on the way
            if (sc > 0.2f && w.sp.zombiesNear(e, (x + rx) / 2, (y + ry) / 2, 45, 2) >= 2) sc = 0;
            if (sc > 0.2f && sc + 0.5f > best) {
                best = sc + 0.5f;
                pick = A_FALLBACK;
                bx = rx;
                by = ry;
            }
        }
        // wounded: pull back to a medic, or to the fallback point
        if (hpShare < 0.45f && (z >= 0 && zd < 200 || medic >= 0)) {
            float sc = 0.6f + (0.45f - hpShare) * 3 + (was == A_WOUNDED ? 0.2f : 0);
            if (sc > best) {
                best = sc;
                pick = A_WOUNDED;
                boolean toMedic = medic >= 0 && clearLine(x, y, e.x[medic], e.y[medic]);
                bx = toMedic ? e.x[medic] : rx;
                by = toMedic ? e.y[medic] : ry;
            }
        }
        // reload: empty magazine, or topping up in a lull
        if (reloadT[i] > 0 && was == A_RELOAD && 1.25f > best) {
            // still changing magazines: stay put (in the cover chosen)
            best = 1.25f;
            pick = A_RELOAD;
            bx = px[i];
            by = py[i];
        } else if (wp != null && wp.mag > 0 && ammo > 0 && reloadT[i] <= 0) {
            float magShare = mag[i] / (float) wp.mag;
            float sc = mag[i] <= 0 ? 1.3f : magShare < 0.5f && zd > 110 ? 0.75f : 0;
            if (sc > best) {
                best = sc;
                pick = A_RELOAD;
                bx = x;
                by = y;
                if (z >= 0 && zd < 90) {
                    // into cover (or back) first
                    float[] c = spot(i, z, zd, true, rx, ry, scr.f);
                    if (c != null) {
                        bx = c[0];
                        by = c[1];
                    } else {
                        bx = rx;
                        by = ry;
                    }
                }
                reloadT[i] = wp.reload * Ranks.reload(e.rank[i]);
            }
        }
        // a wounded buddy close by and the dead coming: stand over them
        if (buddyHurt >= 0 && loaded && z >= 0 && zd < range) {
            float sc = 1.05f - danger;
            if (sc > best) {
                best = sc;
                pick = A_COVER;
                bx = x;
                by = y;
            }
        }
        // the rear guard: the first of a squad falling back hold a moment longer and cover the rest
        if (sq != null && sq.fallT > 3 && loaded && hpShare > 0.5f && z >= 0) {
            int guards = Math.max(1, sq.n / 4), slot = -1;
            for (int k = 0; k < sq.n; k++) if (sq.mem[k] == i) slot = k;
            if (slot >= 0 && slot < guards && 1.5f > best) {
                best = 1.5f;
                pick = A_REARGUARD;
                bx = was == A_REARGUARD ? px[i] : x;
                by = was == A_REARGUARD ? py[i] : y;
            }
        }
        // a better line, or cover: when I can't see the one in reach, or (police, SWAT) when out in the open
        if (loaded && z >= 0 && zd < range * 1.2f && close == 0 && (pick == A_ENGAGE || pick == A_DUTY)) {
            boolean los = zd < 48 || w.r.lineOfSight(x, y, e.x[z], e.y[z]);
            boolean wantCover = (doc == D_POLICE || doc == D_SWAT) && (bits[i] & B_COVER) == 0 && zd > 90;
            if (!los || wantCover) {
                float[] c = spot(i, z, zd, wantCover, e.gx[i], e.gy[i], scr.f);
                if (c != null) {
                    float sc = los ? 0.95f : 1.1f;
                    if (sc > best) {
                        best = sc;
                        pick = A_REPOSITION;
                        bx = c[0];
                        by = c[1];
                    }
                }
            }
        }
        // role jobs, when the fighting allows
        if (role == R_MEDIC && press < 3) {
            int p = patient(i, sq);
            // the worse they are, the more it matters (more than shooting, unless the dead are on us)
            float sc = p < 0 ? 0 : 0.85f + 0.5f * Math.min(1, need(p)) - close * 0.15f;
            if (p >= 0 && sc > best && clearLine(x, y, e.x[p], e.y[p])) {
                best = sc;
                pick = A_HEAL;
                aux[i] = p;
                bx = e.x[p];
                by = e.y[p];
            }
        }
        if (role == R_MARKSMAN && press == 0 && sq != null && sq.mode == Squads.M_FOOT && sq.order != Squads.O_RETURN
                && sq.order != Squads.O_IDLE && near(sq.gx, sq.gy, e.gx[i], e.gy[i], 120)) {
            float[] p = perch(i, sq, scr.f);
            if (p != null && 0.85f > best && clearLine(x, y, p[0], p[1])) {
                best = 0.85f;
                pick = A_OVERWATCH;
                bx = p[0];
                by = p[1];
            }
        }
        if (role == R_TRAFFIC && close == 0 && sq != null && sq.mode == Squads.M_FOOT
                && (sq.order == Squads.O_HOLD || sq.order == Squads.O_CORDON || sq.order == Squads.O_GUARD) && near(sq.gx, sq.gy, x, y, 260)) {
            int node = aux[i] >= 0 && was == A_JUNCTION ? aux[i] : w.r.roads.nearestNode(sq.gx, sq.gy, 220);
            if (node >= 0 && 0.8f > best && clearLine(sq.gx, sq.gy, w.r.roads.nx[node], w.r.roads.ny[node])
                    && clearLine(x, y, w.r.roads.nx[node], w.r.roads.ny[node])) {
                best = 0.8f;
                pick = A_JUNCTION;
                aux[i] = node;
                bx = w.r.roads.nx[node];
                by = w.r.roads.ny[node];
            }
        }
        // search where the dead were last seen (or where a drone sees them), not far from my place
        if (z < 0 && loaded && sq != null && (sq.order == Squads.O_HOLD || sq.order == Squads.O_RESPOND || sq.order == Squads.O_PATROL)) {
            if (lastT[i] > 20 && droneOver(x, y)) {
                int far = w.sp.nearestZombie(e, x, y, 420);
                if (far >= 0) {
                    lastX[i] = e.x[far];
                    lastY[i] = e.y[far];
                    lastT[i] = 5;
                }
            }
            if (lastT[i] < 20 && near(lastX[i], lastY[i], sq.gx, sq.gy, 220) && 0.45f > best && clearLine(x, y, lastX[i], lastY[i])) {
                best = 0.45f;
                pick = A_SEARCH;
                bx = lastX[i];
                by = lastY[i];
            }
        }
        // rejoin: well away from my place and nothing pressing; if I can't walk straight there, by way of
        // whichever of the squad I can reach that's nearer it
        if (pick == A_DUTY && press == 0) {
            float dx = e.gx[i] - x, dy = e.gy[i] - y;
            if (dx * dx + dy * dy > 140 * 140) {
                pick = A_REJOIN;
                if (sq != null && !clearLine(x, y, e.gx[i], e.gy[i])) {
                    float bd = dx * dx + dy * dy;
                    for (int k = 0; k < sq.n; k++) {
                        int m = sq.mem[k];
                        if (m == i || e.loc[m] != Ents.FREE) continue;
                        float mx = e.gx[i] - e.x[m], my = e.gy[i] - e.y[m], md = mx * mx + my * my;
                        if (md < bd && clearLine(x, y, e.x[m], e.y[m])) {
                            bd = md;
                            bx = e.x[m];
                            by = e.y[m];
                        }
                    }
                }
            }
        }
        choose(i, pick, bx, by);
    }

    private void choose(int i, byte a, float x, float y) {
        act[i] = a;
        px[i] = x;
        py[i] = y;
        if (a == A_REPOSITION || a == A_ENGAGE || a == A_REARGUARD || a == A_COVER || a == A_OVERWATCH || a == A_JUNCTION) {
            // in cover where I'll stand?
            bits[i] = (byte) (cover(x, y) ? bits[i] | B_COVER : bits[i] & ~B_COVER);
        } else bits[i] = (byte) (bits[i] & ~B_COVER);
    }

    private static boolean near(float ax, float ay, float bx, float by, float r) {
        float dx = ax - bx, dy = ay - by;
        return dx * dx + dy * dy < r * r;
    }

    /** Somewhere to fall back to: the squad's vehicle, behind its line, or just away from the dead. */
    private float[] rally(int i, Squads.Squad sq, int z, float[] out) {
        Ents e = w.e;
        float x = e.x[i], y = e.y[i];
        // wherever it is, I must be able to get there in a straight line, and back to my place from it
        float hx = sq != null ? sq.gx : e.gx[i], hy = sq != null ? sq.gy : e.gy[i];
        if (sq != null) {
            int v = sq.vehicle;
            if (v >= 0 && w.veh.usable(v) && !w.veh.mobbed(v) && !w.veh.flying(v) && near(w.veh.x[v], w.veh.y[v], x, y, 300)
                    && w.sp.zombiesNear(e, w.veh.x[v], w.veh.y[v], 60, 2) < 2 && clearLine(x, y, w.veh.x[v], w.veh.y[v])) {
                out[0] = w.veh.x[v];
                out[1] = w.veh.y[v];
                return out;
            }
            float fx = sq.faceX, fy = sq.faceY;
            if (fx != 0 || fy != 0) {
                float bx = sq.gx - fx * 150, by = sq.gy - fy * 150;
                if (w.sp.zombiesNear(e, bx, by, 70, 2) < 2 && clearLine(x, y, bx, by) && clearLine(bx, by, hx, hy)) {
                    out[0] = bx;
                    out[1] = by;
                    return out;
                }
            }
        }
        float ax = 0, ay = 1;
        if (z >= 0) {
            float dx = x - e.x[z], dy = y - e.y[z], d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
            ax = dx / d;
            ay = dy / d;
        } else if (sq != null) {
            float dx = sq.gx - x, dy = sq.gy - y, d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
            ax = dx / d;
            ay = dy / d;
        }
        // straight away from the dead, or a little to either side, as far as the street allows
        for (int r = 140; r >= 70; r -= 70)
            for (int k = 0; k < 5; k++) {
                float t = (k == 0 ? 0 : (k + 1) / 2 * 0.6f) * (k % 2 == 0 ? 1 : -1);
                float c = (float) Math.cos(t), sn = (float) Math.sin(t);
                float dx = ax * c - ay * sn, dy = ax * sn + ay * c;
                float bx = x + dx * r, by = y + dy * r;
                if (clearLine(x, y, bx, by) && clearLine(bx, by, hx, hy)) {
                    out[0] = bx;
                    out[1] = by;
                    return out;
                }
            }
        // nowhere better: stand and let the backing-off do the rest
        out[0] = x;
        out[1] = y;
        return out;
    }

    /** Can someone walk straight from a to b (sampled every 10 px)? */
    boolean clearLine(float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        int n = (int) (Math.sqrt(dx * dx + dy * dy) / 10) + 1;
        if (n > 60) return false;
        for (int k = 1; k <= n; k++) if (!w.r.walkablePx(ax + dx * k / n, ay + dy * k / n)) return false;
        return true;
    }

    /** Next to something that blocks sight (a wall, a building, trees): cover. */
    private boolean cover(float x, float y) {
        int tx = (int) (x / Tile.SIZE), ty = (int) (y / Tile.SIZE);
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++)
                if ((dx != 0 || dy != 0) && Tile.blocksSight(w.r.t(tx + dx, ty + dy))) return true;
        return w.defs.sandbagged(x, y);
    }

    /**
     * A better place to fight from near (x, y): out of the pack's reach, with a line on target z, in cover when
     * wanted, and not far from where the squad wants me (ax, ay). Null when nowhere is better than here.
     */
    private float[] spot(int i, int z, float zd, boolean wantCover, float ax, float ay, float[] out) {
        Ents e = w.e;
        float x = e.x[i], y = e.y[i], zx = e.x[z], zy = e.y[z];
        float here = spotScore(x, y, zx, zy, wantCover, ax, ay, true);
        float best = here + 0.6f;
        boolean found = false;
        float a0 = Rng.unit(w.seed, e.uid[i], w.tick / 10) * 0.78f;
        for (int k = 0; k < 8; k++) {
            float ang = a0 + k * 0.785f;
            float c = (float) Math.cos(ang), sn = (float) Math.sin(ang);
            for (int r = 1; r <= 2; r++) {
                float cx = x + c * 30 * r, cy = y + sn * 30 * r;
                if (!clearLine(x, y, cx, cy)) break;
                float sc = spotScore(cx, cy, zx, zy, wantCover, ax, ay, false);
                if (sc > best) {
                    best = sc;
                    out[0] = cx;
                    out[1] = cy;
                    found = true;
                }
            }
        }
        return found ? out : null;
    }

    private float spotScore(float x, float y, float zx, float zy, boolean wantCover, float ax, float ay, boolean here) {
        float dx = x - zx, dy = y - zy, d = (float) Math.sqrt(dx * dx + dy * dy);
        float sc = Math.min(d, 120) / 60f;
        if (d < 48 || w.r.lineOfSight(x, y, zx, zy)) sc += 1.5f;
        if (wantCover && cover(x, y)) sc += 1f;
        float gx = x - ax, gy = y - ay;
        sc -= (float) Math.sqrt(gx * gx + gy * gy) / 150f;
        if (!here) sc -= w.sp.zombiesNear(w.e, x, y, 45, 4) * 0.6f;
        return sc;
    }

    /** The squad's most hurt (or bitten, untreated) person in reach, or one found by a dog. -1 if none. */
    private int patient(int i, Squads.Squad sq) {
        Ents e = w.e;
        float x = e.x[i], y = e.y[i];
        int best = -1;
        float bs = 0;
        if (sq != null)
            for (int k = 0; k < sq.n; k++) {
                int m = sq.mem[k];
                if (m == i || e.loc[m] != Ents.FREE || !near(e.x[m], e.y[m], x, y, 260)) continue;
                float need = need(m);
                if (need > 0.3f && need > bs) {
                    bs = need;
                    best = m;
                }
            }
        if (best < 0) {
            int c = w.sp.nearestLiving(e, x, y, 140, i, (1 << Ents.COP) | (1 << Ents.SOLDIER) | (1 << Ents.ZOMBIE));
            if (c >= 0 && (e.flags[c] & (F_SPOTTED | Ents.F_INFECTED | F_STABILISED)) == (F_SPOTTED | Ents.F_INFECTED)) best = c;
        }
        return best;
    }

    /** How badly person m needs a medic: hurt, or bitten and not yet treated. */
    private float need(int m) {
        Ents e = w.e;
        float n = e.armed(m) ? 1 - e.hp[m] / Math.max(1, e.maxHp[m]) : 0;
        if ((e.flags[m] & Ents.F_INFECTED) != 0 && (e.flags[m] & F_STABILISED) == 0) n += 0.6f;
        return n;
    }

    /** A rooftop or high place for a marksman near the squad's place: beside the tallest building, behind the line. */
    private float[] perch(int i, Squads.Squad sq, float[] out) {
        Ents e = w.e;
        Region r = w.r;
        if (act[i] == A_OVERWATCH && near(px[i], py[i], sq.gx, sq.gy, 200)) {
            out[0] = px[i];
            out[1] = py[i];
            return out;
        }
        int cx = (int) (sq.gx / Tile.SIZE), cy = (int) (sq.gy / Tile.SIZE);
        float best = -1e9f;
        boolean found = false;
        for (int ty = cy - 9; ty <= cy + 9; ty += 1)
            for (int tx = cx - 9; tx <= cx + 9; tx += 1) {
                if (!r.in(tx, ty) || !Tile.walkable(r.t(tx, ty))) continue;
                int floors = 0;
                for (int k = 0; k < 4; k++) {
                    int nx = tx + (k == 0 ? 1 : k == 1 ? -1 : 0), ny = ty + (k == 2 ? 1 : k == 3 ? -1 : 0);
                    if (!r.in(nx, ny)) continue;
                    int b = r.building(nx, ny);
                    if (b >= 0) floors = Math.max(floors, r.bfloors(b));
                }
                if (floors < 2) continue;
                float wx = tx * Tile.SIZE + 8, wy = ty * Tile.SIZE + 8;
                float dx = wx - sq.gx, dy = wy - sq.gy;
                if (!clearLine(sq.gx, sq.gy, wx, wy)) continue;
                float sc = floors * 1.5f - (float) Math.sqrt(dx * dx + dy * dy) / 40f;
                // behind the line, not in front of it
                sc -= (dx * sq.faceX + dy * sq.faceY) / 30f;
                if (sc > best) {
                    best = sc;
                    out[0] = wx;
                    out[1] = wy;
                    found = true;
                }
            }
        return found ? out : null;
    }

    /** The way out from the dead within 80 px (each pushes harder the closer it is), into out[0..1]. */
    private boolean escape(int i, float[] out) {
        Ents e = w.e;
        float x = e.x[i], y = e.y[i], rad = 80, sx = 0, sy = 0;
        int cx0 = Math.max(0, (int) ((x - rad) / Spatial.CELL)), cx1 = Math.min(w.sp.cw - 1, (int) ((x + rad) / Spatial.CELL));
        int cy0 = Math.max(0, (int) ((y - rad) / Spatial.CELL)), cy1 = Math.min(w.sp.ch - 1, (int) ((y + rad) / Spatial.CELL));
        int n = 0;
        for (int cy = cy0; cy <= cy1; cy++)
            for (int cx = cx0; cx <= cx1; cx++) {
                int c = w.sp.cell(cx, cy);
                for (int j = w.sp.zStart[c]; j < w.sp.zEnd[c] && n < 40; j++) {
                    int o = w.sp.zList[j];
                    float dx = x - e.x[o], dy = y - e.y[o], d2 = dx * dx + dy * dy;
                    if (d2 > rad * rad || d2 < 0.01f) continue;
                    sx += dx / d2;
                    sy += dy / d2;
                    n++;
                }
            }
        float l = (float) Math.sqrt(sx * sx + sy * sy);
        if (n == 0 || l < 1e-6f) return false;
        sx /= l;
        sy /= l;
        // a wall that way: slide along it, whichever side is open
        if (!w.r.walkablePx(x + sx * 14, y + sy * 14)) {
            float ax = -sy, ay = sx;
            if (!w.r.walkablePx(x + ax * 14, y + ay * 14)) {
                ax = sy;
                ay = -sx;
            }
            sx = ax;
            sy = ay;
        }
        out[0] = sx;
        out[1] = sy;
        return true;
    }

    private boolean droneOver(float x, float y) {
        for (int k = 0; k < droneN; k++) if (near(drones[k * 2], drones[k * 2 + 1], x, y, 450)) return true;
        return false;
    }

    private boolean seen(int i, int z, float d) {
        if (d < 48) return true;
        return w.r.lineOfSight(w.e.x[i], w.e.y[i], w.e.x[z], w.e.y[z]);
    }

    // ------------------------------------------------------------------ once a second, on one thread

    private final class State extends Module {
        State() {
            super(ArmedAI.this.w);
        }

        @Override
        public String id() {
            return "armed";
        }

        @Override
        public void update(float dt) {
            for (int s = 0; s < droneT.length; s++) if (droneT[s] > 0) droneT[s] = Math.max(0, droneT[s] - dt);
            if (w.strategic) {
                droneN = 0;
                return;
            }
            Ents e = w.e;
            droneN = 0;
            if (swatIn == null || swatIn.length != w.sec.n) {
                swatIn = new int[w.sec.n];
                swatTouched = new int[64];
            }
            int touched = 0;
            for (int i = 0; i < e.top; i++) {
                if (e.loc[i] != Ents.FREE || !e.armed(i) || !has(i)) continue;
                // getting their breath back out of the fight
                if (threat[i] > 200 && e.hp[i] < e.maxHp[i]) e.hp[i] = Math.min(e.maxHp[i], e.hp[i] + 0.6f * dt);
                if ((bits[i] & (B_BROKEN | B_TOLD)) == B_BROKEN) {
                    bits[i] |= B_TOLD;
                    broke++;
                    if (broke <= 3 || Rng.unit(w.seed, w.tick, i) < 0.1f)
                        w.news.radio(w.nameOf(i) + " breaks and runs", e.x[i], e.y[i], News.UNIT);
                }
                if ((bits[i] & B_BROKEN) != 0) continue;
                switch (roleOf[e.type[i]]) {
                    case R_MEDIC: medic(i, dt); break;
                    case R_K9: dog(i); break;
                    case R_DRONE: {
                        if (droneN * 2 + 2 > drones.length) drones = java.util.Arrays.copyOf(drones, drones.length * 2);
                        drones[droneN * 2] = e.x[i];
                        drones[droneN * 2 + 1] = e.y[i];
                        droneN++;
                        int st = w.sec.settlement[w.r.sectorOfPx(e.x[i], e.y[i])];
                        if (st >= 0 && st < droneT.length) droneT[st] = 20;
                        break;
                    }
                    default:
                }
                if (docOf[e.type[i]] == D_SWAT && threat[i] > 60) {
                    Squads.Squad sq = e.squad[i] >= 0 ? w.sq.get(e.squad[i]) : null;
                    if (sq != null && sq.mode == Squads.M_FOOT && (sq.order == Squads.O_HOLD || sq.order == Squads.O_RESPOND)) {
                        int s = w.r.sectorOfPx(e.x[i], e.y[i]);
                        if (swatIn[s] == 0) {
                            if (touched == swatTouched.length) swatTouched = java.util.Arrays.copyOf(swatTouched, touched * 2);
                            swatTouched[touched++] = s;
                        }
                        swatIn[s]++;
                    }
                }
            }
            // SWAT breach the buildings where the dead are inside, faster than others clear them
            float clear = w.c.rule("clearRate", 0.015f);
            for (int k = 0; k < touched; k++) {
                int s = swatTouched[k];
                int n = swatIn[s];
                swatIn[s] = 0;
                if (n < 2 || w.sec.zIn[s] <= 0) continue;
                float v = n * clear * 2 * dt;
                int kill = Math.min(w.sec.zIn[s], (int) v + (Rng.unit(w.seed, w.tick, s * 31L + 9) < v % 1f ? 1 : 0));
                if (kill <= 0) continue;
                w.sec.zIn[s] -= kill;
                w.sec.bodies[s] += kill;
                w.stats.killsBySide[1] += kill;
                w.stats.zombieKills += kill;
            }
        }

        private void medic(int i, float dt) {
            Ents e = w.e;
            int p = act[i] == A_HEAL ? aux[i] : -1;
            // the one being treated
            if (p >= 0 && e.loc[p] == Ents.FREE && e.kind[p] != Ents.ZOMBIE && near(e.x[p], e.y[p], e.x[i], e.y[i], 20)) {
                if (e.armed(p) && e.hp[p] < e.maxHp[p]) {
                    e.hp[p] = Math.min(e.maxHp[p], e.hp[p] + 14 * dt);
                    healed++;
                }
                if ((e.flags[p] & Ents.F_INFECTED) != 0 && (e.flags[p] & F_STABILISED) == 0) {
                    // holds the turning off for a while
                    e.flags[p] |= F_STABILISED;
                    e.infT[p] += 60;
                    stabilised++;
                }
            }
            // and everyone of the squad standing by gets patched up a little
            int sqd = e.squad[i];
            if (sqd < 0) return;
            Squads.Squad sq = w.sq.get(sqd);
            for (int k = 0; k < sq.n; k++) {
                int m = sq.mem[k];
                if (m != i && e.loc[m] == Ents.FREE && e.hp[m] < e.maxHp[m] && near(e.x[m], e.y[m], e.x[i], e.y[i], 40))
                    e.hp[m] = Math.min(e.maxHp[m], e.hp[m] + 2 * dt);
            }
        }

        /** The dog finds the bitten hiding among people near: reported, and taken off the street. */
        private void dog(int i) {
            Ents e = w.e;
            float x = e.x[i], y = e.y[i], rad = 90;
            boolean quiet = w.sp.nearestZombie(e, x, y, 120) < 0;
            int cx0 = Math.max(0, (int) ((x - rad) / Spatial.CELL)), cx1 = Math.min(w.sp.cw - 1, (int) ((x + rad) / Spatial.CELL));
            int cy0 = Math.max(0, (int) ((y - rad) / Spatial.CELL)), cy1 = Math.min(w.sp.ch - 1, (int) ((y + rad) / Spatial.CELL));
            for (int cy = cy0; cy <= cy1; cy++)
                for (int cx = cx0; cx <= cx1; cx++) {
                    int c = w.sp.cell(cx, cy);
                    for (int j = w.sp.lStart[c]; j < w.sp.lEnd[c]; j++) {
                        int o = w.sp.lList[j];
                        if (e.loc[o] != Ents.FREE || e.kind[o] != Ents.CIV || o == w.playerEnt) continue;
                        if ((e.flags[o] & Ents.F_INFECTED) == 0 || !near(e.x[o], e.y[o], x, y, rad)) continue;
                        if ((e.flags[o] & F_SPOTTED) == 0) {
                            e.flags[o] |= F_SPOTTED;
                            spotted++;
                            w.news.radio(w.nameOf(i) + ": the dog has found someone bitten", x, y, News.UNIT);
                            w.sec.hot[w.sec.touch(w.r.sectorOfPx(x, y))] = Math.max(w.sec.hot[w.r.sectorOfPx(x, y)], 10);
                        }
                        // isolated: escorted indoors at the hospital (or the nearest door), away from the crowd
                        if (quiet && e.state[o] != CivAI.S_SHELTER && e.state[o] != CivAI.S_MILITIA) {
                            float[] q = quarantine(e.x[o], e.y[o]);
                            e.state[o] = CivAI.S_SHELTER;
                            e.gx[o] = q[0];
                            e.gy[o] = q[1];
                            isolated++;
                        }
                    }
                }
        }

        private float[] quarantine(float x, float y) {
            Region.Settlement st = w.r.settlementAt(x, y);
            float bd = 1500 * 1500;
            float[] out = null;
            for (Region.Facility f : w.r.facilities) {
                if (f.kind != Region.F_HOSPITAL || (st != null && f.settlement != st.id) || !w.strat.online(f.id)) continue;
                float dx = f.x - x, dy = f.y - y, d = dx * dx + dy * dy;
                if (d < bd) {
                    float[] g = w.r.openGroundNear(f.x, f.y, false);
                    bd = d;
                    out = g;
                }
            }
            if (out != null) return out;
            // the nearest door
            int s = w.r.sectorOfPx(x, y);
            float bdd = Float.MAX_VALUE;
            out = new float[]{x, y};
            for (int b : w.secBuildings[s]) {
                float ddx = w.r.doorX(b), ddy = w.r.doorY(b), dx = ddx - x, dy = ddy - y, d = dx * dx + dy * dy;
                if (d < bdd) {
                    bdd = d;
                    out[0] = ddx;
                    out[1] = ddy;
                }
            }
            return out;
        }

        @Override
        public void save(DataOutputStream o) throws IOException {
            int n = Math.min(w.e.top, own.length);
            o.writeInt(n);
            for (int i = 0; i < n; i++) {
                o.writeInt(own[i]);
                if (own[i] == 0) continue;
                o.writeInt(aux[i]);
                o.writeInt(seenLoss[i]);
                o.writeFloat(nerve[i]);
                o.writeFloat(shock[i]);
                o.writeFloat(actT[i]);
                o.writeFloat(reloadT[i]);
                o.writeFloat(gcool[i]);
                o.writeFloat(px[i]);
                o.writeFloat(py[i]);
                o.writeFloat(lastX[i]);
                o.writeFloat(lastY[i]);
                o.writeFloat(lastT[i]);
                o.writeFloat(threat[i]);
                o.writeByte(act[i]);
                o.writeByte(bits[i]);
                o.writeShort(mag[i]);
            }
            o.writeInt(droneT.length);
            for (float f : droneT) o.writeFloat(f);
            o.writeInt(droneN);
            for (int k = 0; k < droneN * 2; k++) o.writeFloat(drones[k]);
            o.writeInt(healed);
            o.writeInt(stabilised);
            o.writeInt(spotted);
            o.writeInt(isolated);
            o.writeInt(broke);
        }

        @Override
        public void load(DataInputStream in) throws IOException {
            prepare();
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int u = in.readInt();
                if (i < own.length) own[i] = u;
                if (u == 0) continue;
                aux[i] = in.readInt();
                seenLoss[i] = in.readInt();
                nerve[i] = in.readFloat();
                shock[i] = in.readFloat();
                actT[i] = in.readFloat();
                reloadT[i] = in.readFloat();
                gcool[i] = in.readFloat();
                px[i] = in.readFloat();
                py[i] = in.readFloat();
                lastX[i] = in.readFloat();
                lastY[i] = in.readFloat();
                lastT[i] = in.readFloat();
                threat[i] = in.readFloat();
                act[i] = in.readByte();
                bits[i] = in.readByte();
                mag[i] = in.readShort();
            }
            int ns = in.readInt();
            for (int s = 0; s < ns; s++) {
                float f = in.readFloat();
                if (s < droneT.length) droneT[s] = f;
            }
            droneN = in.readInt();
            if (drones.length < droneN * 2) drones = new float[droneN * 2 + 2];
            for (int k = 0; k < droneN * 2; k++) drones[k] = in.readFloat();
            healed = in.readInt();
            stabilised = in.readInt();
            spotted = in.readInt();
            isolated = in.readInt();
            broke = in.readInt();
        }
    }
}
