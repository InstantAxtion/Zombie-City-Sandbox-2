package com.instantaxtion.zcs2;

/**
 * Carrying out what everyone decided: bites, shots, grenades, boarding, going indoors. Runs on one thread, in
 * entity order, so the same game always plays out the same way. Also deaths, bodies rising, and the infected turning.
 */
final class Combat {
    static final int I_SHOOT = 10, I_BOARD = 30;
    static final int CAUSE_BITE = 0, CAUSE_GUN = 1, CAUSE_BLAST = 2, CAUSE_VEHICLE = 3, CAUSE_TURNED = 4, CAUSE_PLAYER = 5;
    private final World w;
    private final float[] tmp = new float[4];

    Combat(World w) {
        this.w = w;
    }

    void apply(int i, int intent, int arg) {
        Ents e = w.e;
        switch (intent) {
            case ZombieAI.I_BITE: bite(i, arg, 1f); break;
            case ZombieAI.I_SLAM: slam(i, arg); break;
            case ZombieAI.I_SMASH: w.undead.smash(i, arg); break;
            case ZombieAI.I_SHOVE: shove(i, arg); break;
            case ZombieAI.I_ROAR: w.undead.roar(i); break;
            case I_SHOOT: shoot(i, arg, false); break;
            case ArmedAI.I_GRENADE: shoot(i, arg, true); break;
            case ZombieAI.I_CLAW: w.veh.claw(arg, i); e.aimT[i] = 1.2f; break;
            case ZombieAI.I_BREAKIN: w.sec.breakIn(i); break;
            case CivAI.I_ENTER: w.sec.shelter(i); break;
            case CivAI.I_EVAC: w.strat.evacuee(i); break;
            case I_BOARD: w.veh.board(arg, i); break;
            default:
        }
    }

    private float massFactor() {
        // the mass-outbreak balance: when the dead number in thousands, clean headshots get rarer
        int z = w.sp.zCount;
        float t = Math.max(0, Math.min(1, (z - 300) / 1500f));
        return 1f - 0.42f * t;
    }

    /** z bites v (mult: a lunge or a charge lands harder); true if it reached them. */
    private boolean bite(int z, int v, float mult) {
        Ents e = w.e;
        if (v < 0 || e.loc[v] != Ents.FREE || e.kind[v] == Ents.ZOMBIE) return false;
        float dx = e.x[v] - e.x[z], dy = e.y[v] - e.y[z];
        Content.ZType zt = w.ztypeOf(z);
        if (dx * dx + dy * dy > (zt.radius + 12) * (zt.radius + 12)) return false;
        Content.Unit u = w.unitOf(v);
        // behind sandbags the dead can't get at the armed so easily (Defences)
        if (w.defs.count[Defences.SANDBAGS] > 0 && e.armed(v) && w.defs.sandbagged(e.x[v], e.y[v])) mult *= 0.6f;
        float dmg = zt.bite * mult * (1 - u.armor) * (0.8f + w.rng.nextFloat() * 0.4f);
        if (u.armor > 0 && w.rng.chance(0.35f)) dmg *= 0.5f;
        e.hp[v] -= dmg;
        e.aimT[z] = 1.0f + w.rng.nextFloat() * 0.4f;
        // in a frenzy they bite again sooner
        Undead ud = w.undead;
        if (ud.has(z) && w.time < ud.frenzyUntil[z]) e.aimT[z] *= 0.7f;
        if (dmg > 3 && (e.flags[v] & Ents.F_INFECTED) == 0 && w.rng.chance(zt.infect * u.infect * w.rules.infection())) {
            e.flags[v] |= Ents.F_INFECTED;
            if (e.kind[v] == Ents.CIV && w.stats.resident(v)) {
                int st = w.sec.settlement[w.r.sectorOfPx(e.x[v], e.y[v])];
                if (st >= 0) w.stats.lostBySettlement[st]++;
                w.stats.rec.streetLost++;
                w.roles.lostAt(w.r.sectorOfPx(e.x[v], e.y[v]), 1);
            }
            e.infT[v] = w.rng.range(25f, 70f);
            w.stats.infectedTotal++;
        }
        w.fx.blood(e.x[v], e.y[v], 2);
        if (w.rng.chance(0.3f)) {
            // a scream carries: the dead further off hear it and come
            boolean civ = e.kind[v] == Ents.CIV;
            w.fx.sound(civ ? "scream" : "hurt", e.x[v], e.y[v]);
            w.fx.noise(e.x[v], e.y[v], civ ? 26 : 20, 4);
        } else w.fx.noise(e.x[v], e.y[v], 14, 3);
        w.sec.hot[w.sec.touch(w.r.sectorOfPx(e.x[v], e.y[v]))] = 25;
        if (e.hp[v] <= 0) kill(v, CAUSE_BITE, z);
        return true;
    }

    /** A lunge or a charge lands: a harder bite, and the living are knocked back (far, by a charging brute). */
    private void slam(int z, int v) {
        Ents e = w.e;
        Content.ZType zt = w.ztypeOf(z);
        if (!bite(z, v, zt.charge ? 1.3f : 1.5f)) return;
        if (e.loc[v] != Ents.FREE) return;
        float dx = e.x[v] - e.x[z], dy = e.y[v] - e.y[z], d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
        float push = zt.charge ? 22 + zt.radius : 6;
        for (float k = push; k >= 4; k -= 6) {
            float nx = e.x[v] + dx / d * k, ny = e.y[v] + dy / d * k;
            if (nx > 1 && ny > 1 && nx < w.r.px() - 1 && ny < w.r.px() - 1 && w.r.walkablePx(nx, ny)) {
                e.x[v] = nx;
                e.y[v] = ny;
                break;
            }
        }
        // knocked off their feet for a moment
        e.vx[v] = e.vy[v] = 0;
        if (e.aimT[v] < 0.8f) e.aimT[v] = 0.8f;
        if (zt.charge) w.fx.sound("hurt", e.x[v], e.y[v]);
    }

    /** One of the big dead shoves a vehicle aside, denting it (enough of it wrecks it, and drags people out). */
    private void shove(int z, int v) {
        Ents e = w.e;
        Vehicles vh = w.veh;
        if (v < 0 || v >= vh.top || vh.state[v] == Vehicles.V_NONE || vh.flying(v)) return;
        Content.ZType zt = w.ztypeOf(z);
        float dx = vh.x[v] - e.x[z], dy = vh.y[v] - e.y[z], d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
        if (d > zt.radius + 30) return;
        e.aimT[z] = 1.4f;
        Content.VType t = vh.type(v);
        // the heavier the vehicle, the less it moves
        float move = Math.min(14, zt.smashPower * 0.5f) * (1 - t.armor * 0.8f);
        // up against another vehicle it goes no further, and a wreck doesn't move at all
        if (move > 0.5f) vh.shove(v, dx / d, dy / d, move);
        vh.spd[v] *= 0.3f;
        w.fx.add(Effects.SPARK, vh.x[v], vh.y[v], 0, 0, 0.15f);
        w.fx.noise(vh.x[v], vh.y[v], 18, 2);
        if (!vh.usable(v)) return;
        // a dent for each blow, then the usual clawing (which wrecks it once it's gone)
        float dent = zt.smashPower * 1.2f * (1 - t.armor);
        vh.hp[v] = Math.max(0.01f, vh.hp[v] - dent);
        vh.claw(v, z);
    }

    /** A shot (or grenade) from i at zombie t. */
    void shoot(int i, int t, boolean grenade) {
        Ents e = w.e;
        if (t < 0 || e.loc[t] != Ents.FREE || e.kind[t] != Ents.ZOMBIE) return;
        Content.Unit u = w.unitOf(i);
        Content.Weapon wp = grenade ? u.grenade : u.weapon;
        if (wp == null) return;
        float x = e.x[i], y = e.y[i], tx = e.x[t], ty = e.y[t];
        float dx = tx - x, dy = ty - y, d = (float) Math.sqrt(dx * dx + dy * dy);
        boolean armed = e.armed(i);
        ArmedAI ai = w.aai;
        if (d > wp.range * 1.05f * (armed ? ai.rangeMul(i) : 1)) return;
        float rof = wp.rof;
        // rof is the sustained rate; between reloads a magazine fires faster, so the average comes out the same
        if (armed && !grenade && wp.mag > 0 && ai.has(i)) rof *= 1 + wp.reload * wp.rof / wp.mag;
        e.aimT[i] = (1f / rof) * (0.85f + w.rng.nextFloat() * 0.3f);
        e.face[i] = (float) Math.atan2(dy, dx);
        if (!grenade) {
            e.ammo[i]--;
            if (e.ammo[i] < 0) e.ammo[i] = 0;
            if (armed) ai.fired(i);
        } else if (armed) ai.threw(i);
        else e.infT[i] = 9;
        w.fx.add(Effects.FLASH, x, y, (float) Math.atan2(dy, dx), 0, 0.06f);
        w.fx.sound(wp.sound, x, y);
        w.fx.noise(x, y, wp.blast > 0 ? 40 : 30, 6);
        w.stats.shots++;
        int faction = e.kind[i];
        if (wp.blast > 0) {
            // aim at the middle of the pack
            if (w.sp.zombieCentroid(e, tx, ty, 50, tmp)) {
                tx = tmp[0];
                ty = tmp[1];
            }
            w.fx.add(Effects.TRACER, x, y, tx, ty, 0.25f);
            blast(tx, ty, wp.blast, wp.blastDmg, i, faction);
            return;
        }
        float aim = u.aim * Ranks.aim(e.rank[i]) * (armed ? ai.aimMul(i) : 1);
        float near = Math.max(0, 1 - d / wp.range);
        float crowd = w.sp.zombiesNear(e, x, y, 50, 4) >= 3 ? 0.7f : 1f;
        byte doc = armed ? ai.doctrine(i) : ArmedAI.D_NONE;
        float hit = Math.min(0.97f, (0.45f + 0.55f * aim) * (0.55f + 0.45f * near) * crowd * (armed ? w.rules.force() : 1));
        boolean hits = w.rng.chance(hit);
        float ex = tx, ey = ty;
        if (!hits) {
            ex += (w.rng.nextFloat() - 0.5f) * 24;
            ey += (w.rng.nextFloat() - 0.5f) * 24;
        }
        w.fx.add(Effects.TRACER, x, y, ex, ey, 0.08f);
        // army doctrine: sustained fire suppresses a pack (they stumble and bunch where the rounds land)
        if (doc == ArmedAI.D_ARMY && wp.rof >= 2.5f) suppress(ex, ey, t);
        if (!hits) return;
        float head = wp.head * aim * (0.5f + 0.5f * near) * massFactor() * crowd;
        // police and SWAT training: aimed shots to the head up close
        if ((doc == ArmedAI.D_POLICE || doc == ArmedAI.D_SWAT) && d < 60) head *= 1 + (doc == ArmedAI.D_SWAT ? 0.5f : 0.35f) * (1 - d / 60);
        if (w.rng.chance(head)) {
            if ((e.flags[t] & Ents.F_HELMET) != 0) {
                e.flags[t] &= ~Ents.F_HELMET;
                w.fx.add(Effects.SPARK, tx, ty, 0, 0, 0.15f);
                return;
            }
            killZombie(t, i, faction, CAUSE_GUN);
        } else {
            e.hp[t] -= wp.dmg * 0.2f * (0.8f + w.rng.nextFloat() * 0.4f);
            // a hit staggers (police rounds and training: stopping power, it can't bite for a moment)
            boolean stop = doc == ArmedAI.D_POLICE || doc == ArmedAI.D_SWAT;
            e.vx[t] *= stop ? 0.1f : 0.3f;
            e.vy[t] *= stop ? 0.1f : 0.3f;
            if (stop) e.aimT[t] = Math.max(e.aimT[t], 0.3f);
            if (e.hp[t] <= 0) killZombie(t, i, faction, CAUSE_GUN);
            else w.fx.blood(tx, ty, 1);
        }
    }

    /** Rounds landing in a pack slow the dead round where they land (a few at most). */
    private void suppress(float x, float y, int t) {
        Ents e = w.e;
        int c = w.sp.cellOf(x, y), n = 0;
        for (int j = w.sp.zStart[c]; j < w.sp.zEnd[c] && n < 6; j++) {
            int o = w.sp.zList[j];
            if (o == t || e.loc[o] != Ents.FREE) continue;
            float dx = e.x[o] - x, dy = e.y[o] - y;
            if (dx * dx + dy * dy > 18 * 18) continue;
            e.vx[o] *= 0.5f;
            e.vy[o] *= 0.5f;
            n++;
        }
    }

    /** An explosion: hurts everything in reach, the dead and the living alike. killer may be -1. */
    void blast(float x, float y, float rad, float dmg, int killer, int faction) {
        Ents e = w.e;
        w.fx.add(rad > 90 ? Effects.BIGBLAST : Effects.BLAST, x, y, rad, 0, rad > 90 ? 1.6f : 0.7f);
        w.fx.add(Effects.SMOKE, x, y, rad, 0, 6f);
        w.fx.sound(rad > 90 ? "bigboom" : "boom", x, y);
        w.fx.noise(x, y, rad > 90 ? 56 : 44, 10);
        int cx0 = Math.max(0, (int) ((x - rad) / Spatial.CELL)), cx1 = Math.min(w.sp.cw - 1, (int) ((x + rad) / Spatial.CELL));
        int cy0 = Math.max(0, (int) ((y - rad) / Spatial.CELL)), cy1 = Math.min(w.sp.ch - 1, (int) ((y + rad) / Spatial.CELL));
        float massCut = 0.45f + 0.55f * massFactor();
        for (int cy = cy0; cy <= cy1; cy++)
            for (int cx = cx0; cx <= cx1; cx++) {
                int c = w.sp.cell(cx, cy);
                for (int j = w.sp.zStart[c]; j < w.sp.zEnd[c]; j++) {
                    int o = w.sp.zList[j];
                    if (e.loc[o] != Ents.FREE) continue;
                    float dx = e.x[o] - x, dy = e.y[o] - y, d = (float) Math.sqrt(dx * dx + dy * dy);
                    if (d > rad) continue;
                    float h = dmg * (1 - d / rad) * massCut;
                    e.hp[o] -= h;
                    if (e.hp[o] <= 0) killZombie(o, killer, faction, CAUSE_BLAST);
                }
                for (int j = w.sp.lStart[c]; j < w.sp.lEnd[c]; j++) {
                    int o = w.sp.lList[j];
                    if (e.loc[o] != Ents.FREE) continue;
                    float dx = e.x[o] - x, dy = e.y[o] - y, d = (float) Math.sqrt(dx * dx + dy * dy);
                    if (d > rad) continue;
                    e.hp[o] -= dmg * (1 - d / rad) * 0.5f;
                    if (e.hp[o] <= 0) kill(o, CAUSE_BLAST, -1);
                }
            }
        w.veh.blast(x, y, rad, dmg);
        w.strat.hordeBlast(x, y, rad);
        w.fires.blast(x, y, rad);
        // big bombs bring buildings down, and the people sheltering in them
        if (rad > 90) {
            // (a national map: the chunks it falls on are taken in now, before anything on them changes)
            if (w.r.nation != null)
                for (int k = 0; k < 4; k++) w.act.touchPx(x + (k % 2 == 0 ? -rad : rad) * 0.45f, y + (k < 2 ? -rad : rad) * 0.45f);
            int tx0 = (int) ((x - rad * 0.45f) / Tile.SIZE), tx1 = (int) ((x + rad * 0.45f) / Tile.SIZE);
            int ty0 = (int) ((y - rad * 0.45f) / Tile.SIZE), ty1 = (int) ((y + rad * 0.45f) / Tile.SIZE);
            for (int ty = ty0; ty <= ty1; ty++)
                for (int tx = tx0; tx <= tx1; tx++) {
                    if (!w.r.in(tx, ty)) continue;
                    int b = w.r.building(tx, ty);
                    if (b >= 0) {
                        float ddx = w.r.doorX(b), ddy = w.r.doorY(b);
                        int s = w.r.sectorOfPx(ddx >= 0 ? ddx : x, ddy >= 0 ? ddy : y);
                        int lost = Math.min(w.sec.popIn[s], w.r.bres(b));
                        w.sec.popIn[w.sec.touch(s)] -= lost;
                        w.stats.civDeaths += lost;
                        int zl = Math.min(w.sec.zIn[s], Math.max(1, w.sec.zIn[s] / 8));
                        w.sec.zIn[s] -= zl;
                        w.stats.killsBySide[2] += zl;
                        w.r.destroyBuilding(b);
                        w.stats.buildingsLost++;
                    }
                }
            int s = w.r.sectorOfPx(x, y);
            int zl = Math.min(w.sec.zIn[s], (int) (w.sec.zIn[s] * 0.3f));
            w.sec.zIn[s] -= zl;
            w.stats.killsBySide[2] += zl;
        }
    }

    void killZombie(int t, int killer, int faction, int cause) {
        Ents e = w.e;
        if (e.loc[t] != Ents.FREE) return;
        e.die(t, e.x[t], e.y[t], false);
        w.director.died(e.x[t], e.y[t]);
        e.timer[t] = 40;
        w.fx.blood(e.x[t], e.y[t], 3);
        int side = faction == Ents.SOLDIER ? 2 : faction == Ents.COP ? 1 : 0;
        w.stats.killsBySide[side]++;
        w.stats.zombieKills++;
        if (killer >= 0 && e.loc[killer] != Ents.NONE && e.kind[killer] != Ents.ZOMBIE) {
            e.kills[killer]++;
            int nr = Ranks.rankFor(e.kills[killer]);
            if (nr > e.rank[killer] && e.armed(killer)) {
                e.rank[killer] = (short) nr;
                if (nr >= 3) w.news.radio(w.nameOf(killer) + " promoted (" + e.kills[killer] + " kills)", e.x[killer], e.y[killer], News.UNIT);
            }
            if (e.squad[killer] >= 0) w.sq.get(e.squad[killer]).kills++;
            w.stats.noteKiller(killer);
        }
    }

    /** Someone living dies. Bitten or infected: they'll get up again. */
    void kill(int v, int cause, int killer) {
        Ents e = w.e;
        if (e.loc[v] != Ents.FREE && e.loc[v] != Ents.ABOARD) return;
        boolean rises = cause == CAUSE_BITE || cause == CAUSE_TURNED || (e.flags[v] & Ents.F_INFECTED) != 0;
        if (e.loc[v] == Ents.ABOARD) w.veh.dropCrew(e.vehicle[v], v);
        int sqd = e.squad[v];
        if (sqd >= 0) {
            w.sq.removeMember(sqd, v);
            w.sq.get(sqd).losses++;
        }
        e.die(v, e.x[v], e.y[v], rises);
        w.director.died(e.x[v], e.y[v]);
        e.timer[v] = rises ? (cause == CAUSE_TURNED ? 1.5f : w.rng.range(6f, 16f)) : 90;
        w.fx.blood(e.x[v], e.y[v], 4);
        if (e.kind[v] == Ents.CIV) {
            if (w.stats.resident(v)) w.stats.civDeaths++;
            else w.stats.crewDeaths++;
        }
        else {
            w.stats.armedDeaths[e.kind[v]]++;
            w.stats.noteFallen(v, cause);
            if (w.rng.chance(0.25f) || e.rank[v] >= 2)
                w.news.radio((e.kind[v] == Ents.COP ? "Officer down: " : "Man down: ") + w.nameOf(v), e.x[v], e.y[v], News.LOSS);
            w.disp.unitDown(e.x[v], e.y[v]);
        }
        if (v == w.playerEnt) w.strat.playerDied(cause);
        // the dead stop to feed on the bitten (slower to rise for it, or eaten past rising)
        if (rises && cause == CAUSE_BITE && killer >= 0) w.undead.feast(v, killer);
    }

    /** Bodies rise or are cleared; the infected turn. Every step, one pass. */
    void bodies() {
        Ents e = w.e;
        float dt = World.DT;
        int bodyCount = 0;
        Undead ud = w.undead;
        for (int i = 0; i < e.top; i++) {
            byte l = e.loc[i];
            if (l == Ents.FREE && e.kind[i] == Ents.ZOMBIE) {
                ud.eat(i, dt);
                continue;
            }
            if (l == Ents.BODY) {
                bodyCount++;
                e.timer[i] -= dt;
                if (e.timer[i] > 0) continue;
                if ((e.flags[i] & Ents.F_RISES) != 0 && w.r.walkablePx(e.x[i], e.y[i])) {
                    boolean wasArmed = e.armed(i);
                    Content.ZType zt = w.pickZType(wasArmed && w.c.ztype("armoured") != null && w.rng.chance(0.5f)
                            ? w.c.ztype("armoured").index : -1, i, w.r.sectorOfPx(e.x[i], e.y[i]));
                    e.rise(i, zt.index, zt.hp);
                    if (zt.helmet || wasArmed) e.flags[i] |= Ents.F_HELMET;
                    w.stats.zombiesMade++;
                    w.stats.risen++;
                    if (i == w.playerEnt) w.playerEnt = -1;
                } else {
                    e.remove(i);
                }
            } else if (l == Ents.FREE && (e.flags[i] & Ents.F_INFECTED) != 0 && e.kind[i] != Ents.ZOMBIE) {
                e.infT[i] -= dt;
                if (e.infT[i] <= 0) kill(i, CAUSE_TURNED, -1);
            }
        }
        // too many bodies about: clear the oldest kind (the dead's own) sooner
        if (bodyCount > 4000) {
            for (int i = 0; i < e.top; i++)
                if (e.loc[i] == Ents.BODY && (e.flags[i] & Ents.F_RISES) == 0 && e.timer[i] > 3) e.timer[i] = 3;
        }
    }

    /** The survivor the player controls. */
    void playerThink(int i, Parallel.Scratch s) {
        Ents e = w.e;
        Content.Unit u = w.unitOf(i);
        if (e.aimT[i] > 0) e.aimT[i] -= World.DT;
        float jx = w.strat.playerJoyX, jy = w.strat.playerJoyY;
        float len = (float) Math.sqrt(jx * jx + jy * jy);
        float sp = len > 0.6f ? u.run : u.walk * 1.2f;
        if (len > 1) {
            jx /= len;
            jy /= len;
        }
        e.dvx[i] = jx * sp;
        e.dvy[i] = jy * sp;
        int z = w.sp.nearestZombie(e, e.x[i], e.y[i], u.weapon != null ? u.weapon.range : 0);
        if (z >= 0 && u.weapon != null && e.ammo[i] > 0 && e.aimT[i] <= 0 && w.strat.playerFire
                && w.r.lineOfSight(e.x[i], e.y[i], e.x[z], e.y[z])) {
            e.intent[i] = I_SHOOT;
            e.intentArg[i] = z;
        }
    }
}
