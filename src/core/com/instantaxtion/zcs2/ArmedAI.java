package com.instantaxtion.zcs2;

/**
 * Police officers and soldiers on foot. Each one: keeps a safe distance, shoots what it can see and reach, uses
 * grenades on packs, and otherwise goes to the place its squad gave it. The squad decides where (Squads);
 * this decides how. Runs in parallel: reads anything, writes only its own slot.
 */
final class ArmedAI {
    static final int I_GRENADE = 11;
    private final World w;

    ArmedAI(World w) {
        this.w = w;
    }

    void think(int i, Parallel.Scratch s) {
        Ents e = w.e;
        if (e.aimT[i] > 0) e.aimT[i] -= World.DT;
        if (i == w.playerEnt) {
            // a police officer the player moves (the stick), shooting by themself
            w.combat.playerThink(i, s);
            return;
        }
        if (e.infT[i] > 0 && (e.flags[i] & Ents.F_INFECTED) == 0) e.infT[i] -= World.DT; // grenade cooldown
        if (((w.tick + i) & 1) != 0) return;
        Content.Unit u = w.unitOf(i);
        float x = e.x[i], y = e.y[i];
        Content.Weapon wp = u.weapon;
        float range = wp == null ? 0 : wp.range;
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
        }
        // where my squad wants me
        float gx = e.gx[i] - x, gy = e.gy[i] - y;
        float gd = (float) Math.sqrt(gx * gx + gy * gy);
        boolean urgent = e.state[i] != 0;
        float dirX = 0, dirY = 0, speed = 0;
        // too close: get out of reach first (faster than the dead, so backing off works)
        float keep = u.weapon != null && u.weapon.range < 100 ? 18 : 30;
        if (wp != null && e.ammo[i] <= 0) keep = 110; // nothing to shoot with: stay well clear
        if (z >= 0 && zd < keep) {
            // away from the dead, but not into a wall
            dirX = -zx / zd;
            dirY = -zy / zd;
            if (gd > 1) {
                dirX = dirX * 0.8f + gx / gd * 0.2f;
                dirY = dirY * 0.8f + gy / gd * 0.2f;
            }
            speed = u.run;
            if (wp != null && e.ammo[i] > 0 && e.aimT[i] <= 0 && zd < range) {
                e.intent[i] = Combat.I_SHOOT;
                e.intentArg[i] = z;
            }
        } else if (z >= 0 && zd <= range && wp != null && e.ammo[i] > 0 && seen(i, z, zd)) {
            // in range and in sight: fire, edging towards my place if I'm far off it
            if (e.aimT[i] <= 0) {
                if (u.grenade != null && e.infT[i] <= 0 && zd > 60 && w.sp.zombiesNear(e, e.x[z], e.y[z], 40, 6) >= 6
                        && w.sp.livingNear(e, e.x[z], e.y[z], u.grenade.blast + 10, 1) == 0) {
                    e.intent[i] = I_GRENADE;
                } else e.intent[i] = Combat.I_SHOOT;
                e.intentArg[i] = z;
            }
            e.face[i] = (float) Math.atan2(zy, zx);
            // edge towards my place if I'm far off it, but not into a pack
            if (gd > 50 && (gx * zx + gy * zy <= 0 || w.sp.zombiesNear(e, x, y, 100, 6) < 6)) {
                dirX = gx / gd;
                dirY = gy / gd;
                speed = u.walk * 0.6f;
            }
        } else if (gd > 6) {
            dirX = gx / gd;
            dirY = gy / gd;
            speed = gd > 70 || urgent ? u.run : u.walk;
            if (gd < 20) speed *= gd / 20;
            // the dead close ahead that I can't shoot yet: don't walk into them, wait for them to show
            if (z >= 0 && zd < 110 && e.ammo[i] > 0 && (gx * zx + gy * zy) > 0.3f * gd * zd && w.sp.zombiesNear(e, x, y, 110, 6) >= 6) {
                speed = 0;
                e.face[i] = (float) Math.atan2(zy, zx);
            }
        }
        // recover slowly out of the fight
        if ((z < 0 || zd > 200) && e.hp[i] < e.maxHp[i]) e.hp[i] = Math.min(e.maxHp[i], e.hp[i] + 0.6f * World.DT * 2);
        w.civStep(i, dirX, dirY, speed, s);
    }

    private boolean seen(int i, int z, float d) {
        if (d < 48) return true;
        // look less often for distant targets: a cached result for a few steps is fine
        return w.r.lineOfSight(w.e.x[i], w.e.y[i], w.e.x[z], w.e.y[z]);
    }
}
