package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * What the dead do beyond chasing and biting, and the state it needs: barricades, fences and walls worn down by
 * crowds pressing on them (a sparse damage map; a broken one turns to rubble and the paths and sectors open up),
 * feeding at bodies, alphas that roar and the dead that keep with them, the lunges and charges of the big ones, the
 * front of a watched horde coming out as individuals, and each one's strain.
 *
 * Each of the dead keeps its small state in its own {@link Ents#state} byte (the living use it for other things;
 * spawning and rising clear it): whether it's set up, its strain, its attack phase, and whether it is feeding, has a
 * leader or is marching with a horde. The rest lives here by slot, read only when the byte says it means something,
 * so most of the dead touch nothing more than they did. In the parallel think phase each of the dead writes only its
 * own slot, and reads others' only through state the think phase never writes (position, velocity, uid, type).
 */
public final class Undead extends Module {
    public static final byte P_NONE = 0, P_WINDUP = 1, P_LUNGE = 2, P_CHARGE = 3;
    // the state byte of one of the dead
    static final int S_INIT = 1, S_FEED = 0x20, S_LEAD = 0x40, S_MARCH = 0x80;
    private static final int STRAIN_SHIFT = 1, PHASE_SHIFT = 3;

    // per slot, meaningful only as the state byte says
    int[] leader = new int[0], leaderUid = new int[0], feed = new int[0], feedUid = new int[0];
    float[] feedUntil = new float[0], phaseUntil = new float[0], coolUntil = new float[0], roarAt = new float[0],
            marchUntil = new float[0], dirX = new float[0], dirY = new float[0], frenzyUntil = new float[0];
    /** Where its leader was when last looked at (once a second: close enough to keep loosely with, and cheap). */
    float[] leadX = new float[0], leadY = new float[0];
    /** How fast it goes on the scent with its leader (held back ahead of it, hurried behind), worked out then too. */
    float[] pace = new float[0];

    /** Night, as of the start of this step (read in the think phase). */
    boolean night;

    /** Damage done to smashable tiles, by tile index (only those hit and still standing). */
    final Map<Integer, Float> damage = new HashMap<Integer, Float>();
    // tiles broken this step, for one rebuild of the paths at its end
    private int bx0 = Integer.MAX_VALUE, by0 = Integer.MAX_VALUE, bx1 = -1, by1 = -1;
    /** Totals: tiles broken through, bodies devoured, roars, watched hordes' fronts let out as individuals. */
    public int broken, devoured, roars, frontOut;

    // the rules, read once
    final float leaderShare, roarLoud, roarEvery, feedTime, feedDevour, frenzySpeed, smashEvery, strainFast, strainTough, strainNight;
    final int frenzyCrowd, hordeFront;
    private final float barricadeHp, fenceHp, wallHp;
    /** Each zombie type's share of leaders, and the seed folded for the leader hash. */
    private final float[] leadShare;
    private final int seedMix;

    public Undead(World w) {
        super(w);
        Content c = w.c;
        leaderShare = c.rule("leaderShare", 0.03f);
        roarLoud = c.rule("roarLoud", 32);
        roarEvery = c.rule("roarEvery", 25);
        feedTime = c.rule("feedTime", 9);
        feedDevour = c.rule("feedDevour", 24);
        frenzyCrowd = (int) c.rule("frenzyCrowd", 4);
        frenzySpeed = c.rule("frenzySpeed", 1.35f);
        smashEvery = c.rule("smashEvery", 1.1f);
        hordeFront = (int) c.rule("hordeFront", 160);
        strainFast = c.rule("strainFast", 1.2f);
        strainTough = c.rule("strainTough", 1.35f);
        strainNight = c.rule("strainNight", 1.25f);
        barricadeHp = c.rule("barricadeHp", Tile.smashHp(Tile.BARRICADE));
        fenceHp = c.rule("fenceHp", Tile.smashHp(Tile.FENCE));
        wallHp = c.rule("wallHp", Tile.smashHp(Tile.WALL));
        leadShare = new float[c.ztypes.size()];
        for (int k = 0; k < leadShare.length; k++) {
            Content.ZType z = c.ztypes.get(k);
            leadShare[k] = z.leads >= 0 ? z.leads : leaderShare;
        }
        seedMix = (int) (w.seed ^ (w.seed >>> 32)) * 0x27D4EB2F;
    }

    @Override
    public String id() {
        return "undead";
    }

    /** Room for every slot (called on one thread before the think phase, and before any write from outside it). */
    void prepare() {
        night = w.night();
        int n = w.e.cap;
        if (feed.length >= n) return;
        leader = Arrays.copyOf(leader, n);
        leaderUid = Arrays.copyOf(leaderUid, n);
        feed = Arrays.copyOf(feed, n);
        feedUid = Arrays.copyOf(feedUid, n);
        feedUntil = Arrays.copyOf(feedUntil, n);
        phaseUntil = Arrays.copyOf(phaseUntil, n);
        coolUntil = Arrays.copyOf(coolUntil, n);
        roarAt = Arrays.copyOf(roarAt, n);
        marchUntil = Arrays.copyOf(marchUntil, n);
        dirX = Arrays.copyOf(dirX, n);
        dirY = Arrays.copyOf(dirY, n);
        frenzyUntil = Arrays.copyOf(frenzyUntil, n);
        leadX = Arrays.copyOf(leadX, n);
        leadY = Arrays.copyOf(leadY, n);
        pace = Arrays.copyOf(pace, n);
    }

    /** True if slot i is one of the dead whose state is set up. */
    boolean has(int i) {
        Ents e = w.e;
        return e.kind[i] == Ents.ZOMBIE && (e.state[i] & S_INIT) != 0 && i < feed.length;
    }

    /** Sets up the state of one of the dead (its own slot only), with the strain of where it is. */
    void init(int i) {
        Ents e = w.e;
        int s = w.r.sectorOfPx(e.x[i], e.y[i]);
        init(i, w.ecology.strain[s]);
    }

    void init(int i, int st) {
        Ents e = w.e;
        e.state[i] = (byte) (S_INIT | (st & 3) << STRAIN_SHIFT);
        phaseUntil[i] = coolUntil[i] = frenzyUntil[i] = 0;
        roarAt[i] = (float) w.time + roarEvery * Rng.unit(w.seed, e.uid[i], 0x52);
        // the armoured strain: tougher in every body
        if (st == 2) {
            e.hp[i] *= strainTough;
            e.maxHp[i] *= strainTough;
        }
    }

    private void setBit(int i, int bit, boolean on) {
        byte[] s = w.e.state;
        s[i] = (byte) (on ? s[i] | bit : s[i] & ~bit);
    }

    /** Its strain (0 = the original). */
    public int strain(int i) {
        return (w.e.state[i] >> STRAIN_SHIFT) & 3;
    }

    /** Where it is in a lunge or a charge (P_...). */
    public int phase(int i) {
        return (w.e.state[i] >> PHASE_SHIFT) & 3;
    }

    void setPhase(int i, int p) {
        byte[] s = w.e.state;
        s[i] = (byte) ((s[i] & ~(3 << PHASE_SHIFT)) | p << PHASE_SHIFT);
    }

    /** Walking on with a horde (the way it was going): until when, and which way (dirX, dirY). */
    boolean marching(int i, float now) {
        if ((w.e.state[i] & S_MARCH) == 0) return false;
        if (now < marchUntil[i]) return true;
        setBit(i, S_MARCH, false);
        return false;
    }

    // ------------------------------------------------------------------ traits (pure: safe to read for anyone)

    /** Leaders are chosen by a hash of the uid alone, so anyone can ask about anyone at any time (and cheaply). */
    boolean isLeader(int i) {
        float share = leadShare[w.e.type[i]];
        if (share <= 0) return false;
        int h = (w.e.uid[i] ^ seedMix) * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return (h >>> 8) < share * (1 << 24);
    }

    /** How much faster than its type this one is now (its strain, the night). */
    float speedMul(int i) {
        int st = strain(i);
        if (st == 1) return strainFast;
        if (st == 3) return night ? strainNight : 0.92f;
        return 1f;
    }

    /** Hunts by night (the stalkers' strain, or a type that does). */
    boolean nightHunter(int i, Content.ZType zt) {
        return zt.night || strain(i) == 3;
    }

    /**
     * Slot i's leader if it still has one close enough to keep with, else -1. Looked at once a second (when it's
     * checked and where it is noted in leadX/leadY); between times it's taken on trust. Reads only stable state.
     */
    int leaderOf(int i) {
        Ents e = w.e;
        if ((e.state[i] & S_LEAD) == 0) return -1;
        int l = leader[i];
        if ((((int) w.tick + i) & 0x7FFFFFFF) % 20 != 0) return l;
        if (l >= e.top || e.loc[l] != Ents.FREE || e.kind[l] != Ents.ZOMBIE || e.uid[l] != leaderUid[i]) {
            setBit(i, S_LEAD, false);
            return -1;
        }
        float dx = e.x[l] - e.x[i], dy = e.y[l] - e.y[i];
        if (dx * dx + dy * dy > 420 * 420) {
            setBit(i, S_LEAD, false);
            return -1;
        }
        leadX[i] = e.x[l];
        leadY[i] = e.y[l];
        return l;
    }

    /** Looks among the dead round it for a leader of its kind to follow (writes only slot i). */
    void recruit(int i) {
        Ents e = w.e;
        Spatial sp = w.sp;
        boolean pack = w.ztypeOf(i).pack;
        float x = e.x[i], y = e.y[i];
        int best = -1;
        float bd = 160 * 160;
        int cx0 = Math.max(0, (int) (x / Spatial.CELL) - 1), cx1 = Math.min(sp.cw - 1, (int) (x / Spatial.CELL) + 1);
        int cy0 = Math.max(0, (int) (y / Spatial.CELL) - 1), cy1 = Math.min(sp.ch - 1, (int) (y / Spatial.CELL) + 1);
        for (int cy = cy0; cy <= cy1; cy++)
            for (int cx = cx0; cx <= cx1; cx++) {
                int c = sp.cell(cx, cy);
                int start = sp.zStart[c], end = sp.zEnd[c];
                int stride = Math.max(1, (end - start) / 8);
                for (int j = start; j < end; j += stride) {
                    int o = sp.zList[j];
                    if (o == i || !isLeader(o) || w.ztypeOf(o).pack != pack) continue;
                    float dx = e.x[o] - x, dy = e.y[o] - y, d = dx * dx + dy * dy;
                    if (d < bd) {
                        bd = d;
                        best = o;
                    }
                }
            }
        if (best >= 0) {
            leader[i] = best;
            leaderUid[i] = e.uid[best];
            leadX[i] = e.x[best];
            leadY[i] = e.y[best];
            pace[i] = 1;
            setBit(i, S_LEAD, true);
        }
    }

    // ------------------------------------------------------------------ feeding

    /** The body slot i is feeding on, or -1 (and forgets a body that's gone or a meal that's over). */
    int feeding(int i, float now) {
        Ents e = w.e;
        if ((e.state[i] & S_FEED) == 0) return -1;
        int b = feed[i];
        if (now >= feedUntil[i] || b >= e.top || e.loc[b] != Ents.BODY || e.uid[b] != feedUid[i]) {
            setBit(i, S_FEED, false);
            return -1;
        }
        return b;
    }

    /** Stops feeding (something alive came close). */
    void stopFeeding(int i) {
        setBit(i, S_FEED, false);
    }

    /** Someone was bitten to death by z: z and a few of the idle dead round it stop to feed (one thread). */
    void feast(int body, int z) {
        prepare();
        Ents e = w.e;
        float now = (float) w.time;
        if (z >= 0 && e.loc[z] == Ents.FREE && e.kind[z] == Ents.ZOMBIE && w.ztypeOf(z).breakIn) startFeed(z, body, now);
        Spatial sp = w.sp;
        int c = sp.cellOf(e.x[body], e.y[body]);
        int joined = 0;
        for (int j = sp.zStart[c]; j < sp.zEnd[c] && joined < 3; j++) {
            int o = sp.zList[j];
            if (o == z || e.loc[o] != Ents.FREE || e.kind[o] != Ents.ZOMBIE || e.target[o] >= 0) continue;
            float dx = e.x[o] - e.x[body], dy = e.y[o] - e.y[body];
            if (dx * dx + dy * dy > 48 * 48 || !w.ztypeOf(o).breakIn) continue;
            startFeed(o, body, now);
            joined++;
        }
    }

    private void startFeed(int z, int body, float now) {
        if (!has(z)) init(z);
        if (feeding(z, now) >= 0) return;
        feed[z] = body;
        feedUid[z] = w.e.uid[body];
        feedUntil[z] = now + feedTime * (0.75f + 0.5f * Rng.unit(w.seed, w.e.uid[z], w.tick));
        setBit(z, S_FEED, true);
    }

    /**
     * One of the dead at its meal, once a step (one thread, from the bodies pass): the body is slower to rise for
     * every one feeding on it, and one eaten enough never rises.
     */
    void eat(int i, float dt) {
        if ((w.e.state[i] & S_FEED) == 0 || i >= feed.length) return;
        int b = feeding(i, (float) w.time);
        if (b < 0) return;
        Ents e = w.e;
        float dx = e.x[b] - e.x[i], dy = e.y[b] - e.y[i];
        if (dx * dx + dy * dy > 20 * 20) return;
        e.timer[b] += dt * 0.9f;
        e.hp[b] -= dt;
        if (-e.hp[b] >= feedDevour && (e.flags[b] & Ents.F_RISES) != 0) {
            e.flags[b] &= ~Ents.F_RISES;
            e.timer[b] = Math.min(e.timer[b], 30);
            devoured++;
        }
    }

    // ------------------------------------------------------------------ barricades, fences and walls

    float tileHp(int t) {
        return t == Tile.BARRICADE ? barricadeHp : t == Tile.FENCE ? fenceHp : t == Tile.WALL ? wallHp : Tile.smashHp(t);
    }

    /** The damage done so far to tile index ti (0 if none). */
    public float damageAt(int ti) {
        Float d = damage.get(ti);
        return d == null ? 0 : d;
    }

    /** A blow from z on the tile at index ti (one thread, in entity order). */
    void smash(int z, int ti) {
        Region r = w.r;
        Ents e = w.e;
        int t = r.t(ti % r.W, ti / r.W);
        if (!Tile.smashable(t)) {
            damage.remove(ti);
            return;
        }
        Content.ZType zt = w.ztypeOf(z);
        e.aimT[z] = smashEvery * (0.85f + 0.3f * Rng.unit(w.seed, w.tick, e.uid[z]));
        float d = damageAt(ti) + zt.smashPower;
        int tx = ti % r.W, ty = ti / r.W;
        float cx = tx * Tile.SIZE + Tile.SIZE / 2f, cy = ty * Tile.SIZE + Tile.SIZE / 2f;
        // the banging carries a little: the dead nearby come to help
        w.fx.noise(cx, cy, 16, 2);
        if (d < tileHp(t)) {
            damage.put(ti, d);
            if (zt.smash) w.fx.add(Effects.SPARK, cx, cy, 0, 0, 0.15f);
            return;
        }
        damage.remove(ti);
        r.set(tx, ty, Tile.RUBBLE);
        broken++;
        bx0 = Math.min(bx0, tx);
        by0 = Math.min(by0, ty);
        bx1 = Math.max(bx1, tx);
        by1 = Math.max(by1, ty);
        w.fx.add(Effects.SMOKE, cx, cy, 14, 0, 1.5f);
        w.fx.sound("cannon", cx, cy);
        w.fx.noise(cx, cy, 24, 3);
        String what = t == Tile.BARRICADE ? "a barricade" : t == Tile.WALL ? "a wall" : "a fence";
        w.stats.rec.callout(t == Tile.BARRICADE ? "Barricade broken" : t == Tile.WALL ? "Wall broken" : "Fence broken", cx, cy, Records.C_BREACH);
        w.news.count("smash", "The dead have broken through " + what + "|The dead broke through %d more barricade{s}, fence{s} and wall{s}", 1, cx, cy);
    }

    /** At the end of a step: paths, sectors and roads catch up with whatever was broken during it. */
    @Override
    public void step() {
        if (bx1 < 0) return;
        Region r = w.r;
        r.rebuildNav(bx0 - 1, by0 - 1, bx1 + 1, by1 + 1);
        if (r.nation != null) w.sec.connectivity(bx0 - 1, by0 - 1, bx1 + 1, by1 + 1); // (only the sectors round it)
        else w.sec.connectivity();
        r.closeBarricadedRoads(bx0, by0, bx1, by1);
        bx0 = by0 = Integer.MAX_VALUE;
        bx1 = by1 = -1;
    }

    /** An alpha roars (one thread): a noise the dead far round come to, and its followers with it. */
    void roar(int i) {
        Ents e = w.e;
        Content.ZType zt = w.ztypeOf(i);
        w.fx.noise(e.x[i], e.y[i], zt.pack ? roarLoud * 0.8f : roarLoud, 5);
        w.fx.sound(zt.pack ? "scream" : "groan", e.x[i], e.y[i]);
        roars++;
    }

    // ------------------------------------------------------------------ hordes in view

    /**
     * A horde reaching a watched place: its front comes out as individuals walking on along its heading, behind a
     * band across it; the rest stays as the mass behind them (the sector's count). Returns how many came out.
     * A hook for Strategic (which owns hordes).
     */
    public int watchedHorde(Strategic.Horde h, int s) {
        if (w.strategic || !w.sec.cam[s] || h.count <= 0) return 0;
        Ents e = w.e;
        int room = w.maxIndividuals - e.live;
        int front = Math.min(h.count, Math.min(hordeFront, room));
        if (front <= 0) return 0;
        float hx = h.tx - h.x, hy = h.ty - h.y, hl = (float) Math.sqrt(hx * hx + hy * hy);
        if (hl < 1) {
            // no heading: towards the middle of the view
            hx = (w.camX0 + w.camX1) / 2 - h.x;
            hy = (w.camY0 + w.camY1) / 2 - h.y;
            hl = (float) Math.sqrt(hx * hx + hy * hy);
            if (hl < 1) {
                hx = 1;
                hy = 0;
                hl = 1;
            }
        }
        hx /= hl;
        hy /= hl;
        int st = w.ecology.strain[s];
        float now = (float) w.time;
        int made = 0, perRow = 14;
        for (int k = 0; k < front * 4 && made < front; k++) {
            int row = made / perRow, col = made % perRow;
            float jx = (Rng.unit(w.seed, h.id * 7919L + k, 21) - 0.5f) * 10, jy = (Rng.unit(w.seed, h.id * 7919L + k, 22) - 0.5f) * 10;
            float along = -row * 13 + jx, across = (col - perRow / 2f) * 12 + jy + (k - made) * 3;
            float px = h.x + hx * along - hy * across, py = h.y + hy * along + hx * across;
            if (px < 2 || py < 2 || px > w.r.px() - 2 || py > w.r.px() - 2 || !w.r.walkablePx(px, py)) continue;
            Content.ZType zt = w.pickZType(-1, h.id * 1000L + k, s);
            int i = w.spawnZombie(px, py, zt);
            w.stats.zombiesMade--; // counted when they were made, not again now
            prepare();
            init(i, st);
            marchUntil[i] = now + 45;
            dirX[i] = hx;
            dirY[i] = hy;
            setBit(i, S_MARCH, true);
            e.face[i] = (float) Math.atan2(hy, hx);
            made++;
        }
        frontOut += made;
        return made;
    }

    // ------------------------------------------------------------------ saving

    @Override
    public void save(DataOutputStream o) throws IOException {
        Ents e = w.e;
        int n = 0, top = Math.min(e.top, feed.length);
        for (int i = 0; i < top; i++) if (keep(i)) n++;
        o.writeInt(n);
        for (int i = 0; i < top; i++) {
            if (!keep(i)) continue;
            o.writeInt(i);
            o.writeInt(leader[i]);
            o.writeInt(leaderUid[i]);
            o.writeInt(feed[i]);
            o.writeInt(feedUid[i]);
            o.writeFloat(feedUntil[i]);
            o.writeFloat(phaseUntil[i]);
            o.writeFloat(coolUntil[i]);
            o.writeFloat(roarAt[i]);
            o.writeFloat(marchUntil[i]);
            o.writeFloat(dirX[i]);
            o.writeFloat(dirY[i]);
            o.writeFloat(frenzyUntil[i]);
            o.writeFloat(leadX[i]);
            o.writeFloat(leadY[i]);
            o.writeFloat(pace[i]);
        }
        Integer[] keys = damage.keySet().toArray(new Integer[0]);
        Arrays.sort(keys);
        o.writeInt(keys.length);
        for (Integer k : keys) {
            o.writeInt(k);
            o.writeFloat(damage.get(k));
        }
        o.writeInt(broken);
        o.writeInt(devoured);
        o.writeInt(roars);
        o.writeInt(frontOut);
    }

    /** The dead whose state is set up (the state byte itself is saved with everyone). */
    private boolean keep(int i) {
        Ents e = w.e;
        return e.loc[i] == Ents.FREE && e.kind[i] == Ents.ZOMBIE && (e.state[i] & S_INIT) != 0;
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        prepare();
        int n = in.readInt();
        for (int k = 0; k < n; k++) {
            int i = in.readInt();
            if (i >= feed.length) {
                w.e.ensure(i + 1);
                prepare();
            }
            leader[i] = in.readInt();
            leaderUid[i] = in.readInt();
            feed[i] = in.readInt();
            feedUid[i] = in.readInt();
            feedUntil[i] = in.readFloat();
            phaseUntil[i] = in.readFloat();
            coolUntil[i] = in.readFloat();
            roarAt[i] = in.readFloat();
            marchUntil[i] = in.readFloat();
            dirX[i] = in.readFloat();
            dirY[i] = in.readFloat();
            frenzyUntil[i] = in.readFloat();
            leadX[i] = in.readFloat();
            leadY[i] = in.readFloat();
            pace[i] = in.readFloat();
        }
        damage.clear();
        int m = in.readInt();
        for (int k = 0; k < m; k++) damage.put(in.readInt(), in.readFloat());
        broken = in.readInt();
        devoured = in.readInt();
        roars = in.readInt();
        frontOut = in.readInt();
    }

    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        // cracks on damaged barricades and walls, close in
        if (damage.isEmpty() || cam.zoom < 1.2f) return;
        Region r = w.r;
        float ts = Tile.SIZE * cam.zoom;
        for (Map.Entry<Integer, Float> en : damage.entrySet()) {
            int ti = en.getKey(), t = r.t(ti % r.W, ti / r.W);
            float f = Math.min(1, en.getValue() / Math.max(1, tileHp(t)));
            float x = cam.toScreenX((ti % r.W) * Tile.SIZE), y = cam.toScreenY((ti / r.W) * Tile.SIZE);
            if (x < -ts || y < -ts || x > cam.sw || y > cam.sh) continue;
            g.color(((int) (40 + 150 * f) << 24) | 0x201810);
            g.fillRect(x + ts * 0.15f, y + ts * 0.45f, ts * 0.7f * f + ts * 0.1f, Math.max(1, ts * 0.1f));
            if (f > 0.5f) g.fillRect(x + ts * 0.45f, y + ts * 0.2f, Math.max(1, ts * 0.1f), ts * 0.6f * f);
        }
    }
}
