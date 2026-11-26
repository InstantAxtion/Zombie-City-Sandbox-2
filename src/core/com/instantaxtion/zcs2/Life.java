package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * A normal day in the city, as a level of detail round the camera (rule {@code life}): civilian cars driving between
 * buildings and through, parked cars along the kerbs, city buses on their lines, passenger trains on the railways,
 * police patrols round each station's district, the army about its base, and people on the pavements with
 * somewhere to be. It exists only where the camera is at street zoom (and so only in active chunks); everyone in it
 * is drawn from the counts (a driver or a passenger is a sector's resident until they get in) and folded back when
 * they arrive or the camera leaves, so the counts and the population's ledger always hold.
 *
 * Everything here runs on the game's thread (once a second, and a little each step), with random numbers that are
 * hashes of the seed, the clock and the thing concerned, so it is the same on any number of threads; its state is
 * saved. Day and night move the counts themselves too (rule {@code lifeFlow}, see Commute).
 */
public final class Life extends Module {
    /** What one of the city's vehicles is to Life. */
    public static final byte K_NONE = 0, K_CAR = 1, K_PARKED = 2, K_BUS = 3, K_ABANDONED = 4;

    /** The street-level layer is on (rule life); the counts move with the day (rule lifeFlow). */
    public final boolean on, flowOn;
    final Commute commute;

    // ---- per vehicle slot (Vehicles' indices): what it is to Life, checked against the vehicle's uid
    byte[] kind = new byte[64];
    int[] vuid = new int[64];
    /** Where its driver is going (the sector they fold into when it arrives or leaves the camera's area). */
    int[] dest = new int[64];
    /** A parked car standing in one of the kerb's places: its key (-1 for any other). */
    long[] spot = new long[64];
    /** Seconds its alarm still sounds; seconds it has been stuck or in the dead's way; fleeing the dead. */
    float[] alarm = new float[64], stuckT = new float[64];
    boolean[] flee = new boolean[64];
    /** Buses: the line and the stop it is driving to, and how long it has waited there. */
    int[] line = new int[64], stop = new int[64];
    float[] dwell = new float[64];

    // ---- the area round the camera this second (worked out from the camera, which the world saves)
    boolean has;
    float cx0, cy0, cx1, cy1; // the camera's own rectangle
    float ax0, ay0, ax1, ay1; // and the margin round it where moving things live
    /** The kerb's places filled in so far, by page (pages leave the list when they leave the area). */
    private int[] filled = new int[16];
    private int filledN;
    /** Places whose car has driven off or been wrecked, while their page stays filled (sorted when saved). */
    private final java.util.HashSet<Long> gone = new java.util.HashSet<Long>();

    // ---- counters for the HUD, probes and tests
    public int cars, parked, buses, abandoned;
    public long spawned, folded;

    Life(World w) {
        super(w);
        on = w.rule("life", 0) > 0;
        flowOn = w.rule("lifeFlow", 0) > 0;
        commute = new Commute(w);
    }

    @Override
    public String id() {
        return "life";
    }

    // ------------------------------------------------------------------ the slots

    private void ensure(int n) {
        if (n <= kind.length) return;
        int m = Math.max(n, kind.length * 2);
        kind = Arrays.copyOf(kind, m);
        vuid = Arrays.copyOf(vuid, m);
        dest = Arrays.copyOf(dest, m);
        spot = Arrays.copyOf(spot, m);
        alarm = Arrays.copyOf(alarm, m);
        stuckT = Arrays.copyOf(stuckT, m);
        flee = Arrays.copyOf(flee, m);
        line = Arrays.copyOf(line, m);
        stop = Arrays.copyOf(stop, m);
        dwell = Arrays.copyOf(dwell, m);
    }

    /** Is vehicle v one of Life's (and still the same vehicle)? */
    public boolean mine(int v) {
        Vehicles vh = w.veh;
        return v >= 0 && v < vh.top && v < kind.length && kind[v] != K_NONE && vh.role[v] == Vehicles.R_LIFE
                && vh.state[v] != Vehicles.V_NONE && vh.uid[v] == vuid[v];
    }

    private int take(Content.VType t, float x, float y, float h, byte k) {
        Vehicles vh = w.veh;
        int v = vh.addLife(t, x, y, h);
        ensure(vh.top);
        kind[v] = k;
        vuid[v] = vh.uid[v];
        dest[v] = -1;
        spot[v] = -1;
        alarm[v] = stuckT[v] = dwell[v] = 0;
        flee[v] = false;
        line[v] = stop[v] = -1;
        return v;
    }

    /** Vehicle v leaves the map; its people (if any) are already folded. */
    private void drop(int v) {
        Vehicles vh = w.veh;
        vh.crewN[v] = 0;
        vh.route[v] = null;
        vh.state[v] = Vehicles.V_NONE;
        kind[v] = K_NONE;
        if (v < vh.lifeFree) vh.lifeFree = v;
    }

    // ------------------------------------------------------------------ once a second

    @Override
    public void update(float dt) {
        if (flowOn) commute.update(dt);
        if (!on) return;
        Vehicles vh = w.veh;
        ensure(vh.top);
        while (vh.lifeFree < vh.top && vh.state[vh.lifeFree] != Vehicles.V_NONE) vh.lifeFree++;
        boolean was = has;
        has = !w.strategic && w.camDetail;
        if (!has) {
            if (was || anyLeft()) foldAll();
            return;
        }
        area();
        tally();
        vehicles(dt);
        kerbs();
        traffic(dt);
        busLines();
        trainsIn();
        patrols();
        trample();
    }

    private boolean anyLeft() {
        for (int v = 0; v < w.veh.top; v++) if (mine(v)) return true;
        return filledN > 0 || trainN > 0 || pN > 0;
    }

    /** The camera's rectangle and the margin round it (inside the chunks the camera keeps active). */
    private void area() {
        float m = w.rule("lifeMargin", 600);
        float px = w.r.px();
        cx0 = Math.max(0, w.camX0);
        cy0 = Math.max(0, w.camY0);
        cx1 = Math.min(px, w.camX1);
        cy1 = Math.min(px, w.camY1);
        ax0 = Math.max(0, cx0 - m);
        ay0 = Math.max(0, cy0 - m);
        ax1 = Math.min(px - 1, cx1 + m);
        ay1 = Math.min(px - 1, cy1 + m);
    }

    boolean inArea(float x, float y) {
        return x >= ax0 && x <= ax1 && y >= ay0 && y <= ay1;
    }

    private boolean onScreen(float x, float y, float pad) {
        return x >= cx0 - pad && x <= cx1 + pad && y >= cy0 - pad && y <= cy1 + pad;
    }

    private void tally() {
        cars = parked = buses = abandoned = 0;
        for (int v = 0; v < w.veh.top; v++) {
            if (!mine(v)) continue;
            switch (kind[v]) {
                case K_CAR: cars++; break;
                case K_PARKED: parked++; break;
                case K_BUS: buses++; break;
                case K_ABANDONED: abandoned++; break;
                default:
            }
        }
    }

    /** The camera left street level (or fast-forward began): everything folds back. */
    void foldAll() {
        for (int v = 0; v < w.veh.top; v++) if (mine(v)) fold(v);
        foldTrains();
        foldPatrols();
        filledN = 0;
        gone.clear();
        cars = parked = buses = abandoned = 0;
    }

    /**
     * Vehicle v leaves: whoever is aboard reaches where they were going (out of sight, they get there) and is counted
     * there again; the vehicle goes.
     */
    private void fold(int v) {
        Vehicles vh = w.veh;
        Ents e = w.e;
        int to = dest[v] >= 0 ? dest[v] : w.r.sectorOfPx(vh.x[v], vh.y[v]);
        for (int k = vh.crewN[v] - 1; k >= 0; k--) {
            int m = vh.crew[v][k];
            if (e.loc[m] != Ents.ABOARD) continue;
            indoors(m, to);
        }
        vh.crewN[v] = 0;
        folded++;
        drop(v);
    }

    /** Someone aboard (or on foot) goes indoors in sector s: counted there, and off the map. */
    private void indoors(int m, int s) {
        Ents e = w.e;
        Sectors sec = w.sec;
        if ((e.flags[m] & Ents.F_INFECTED) != 0) sec.inf[sec.touch(s)] += 1;
        else sec.popIn[sec.touch(s)]++;
        if (e.squad[m] >= 0) w.sq.removeMember(e.squad[m], m);
        e.remove(m);
    }

    /** A resident of sector s gets into vehicle v (from the count). False if nobody is there. */
    private boolean board(int v, int s, long salt) {
        Sectors sec = w.sec;
        Vehicles vh = w.veh;
        if (vh.crewN[v] >= vh.type(v).seats) return false;
        boolean sick = sec.inf[s] >= 1 && Rng.unit(w.seed, w.tick + salt, s * 3L + 1) < sec.inf[s] / (sec.inf[s] + sec.popIn[s] + 1);
        if (!sick && sec.popIn[s] <= 0) return false;
        int i = w.spawnCiv(vh.x[v], vh.y[v], w.c.unit("civilian"));
        if (sick) {
            sec.inf[sec.touch(s)] -= 1;
            w.e.flags[i] |= Ents.F_INFECTED;
            w.e.infT[i] = 60 + Rng.unit(w.seed, w.e.uid[i], 21) * 120;
        } else sec.popIn[sec.touch(s)]--;
        vh.board(v, i);
        return true;
    }

    // ------------------------------------------------------------------ the vehicles Life has out

    private void vehicles(float dt) {
        Vehicles vh = w.veh;
        Sectors sec = w.sec;
        for (int v = 0; v < vh.top; v++) {
            if (v >= kind.length || kind[v] == K_NONE) continue;
            if (!mine(v)) {
                // gone by other means (towed away as a wreck, or taken over): forget it
                kind[v] = K_NONE;
                continue;
            }
            float x = vh.x[v], y = vh.y[v];
            byte st = vh.state[v];
            boolean moving = kind[v] == K_CAR || kind[v] == K_BUS;
            // out of the camera's area: folded back (parked ones a little sooner: they only need to be seen)
            if (moving ? !inArea(x, y) : !onScreen(x, y, w.rule("lifeKerbMargin", 300) + 200)) {
                fold(v);
                continue;
            }
            if (st == Vehicles.V_WRECK) {
                alarm[v] = 0;
                continue;
            }
            int s = w.r.sectorOfPx(x, y);
            if (kind[v] == K_CAR) car(v, s, dt);
            else if (kind[v] == K_BUS) bus(v, s, dt);
            // a car alarm: the dead brushing past a parked car, or a blast close by
            if (kind[v] == K_PARKED || kind[v] == K_ABANDONED) {
                if (alarm[v] <= 0 && w.sp.secZ[s] > 0 && w.sp.zombiesNear(w.e, x, y, 24, 1) > 0
                        && Rng.unit(w.seed, w.tick, vuid[v]) < w.rule("lifeAlarm", 0.35f)) {
                    alarm[v] = 20 + Rng.unit(w.seed, vuid[v], 7) * 25;
                    w.fx.sound("caralarm", x, y);
                }
                if (alarm[v] > 0) {
                    alarm[v] -= dt;
                    // it carries: the dead come to it from round about
                    w.fx.noise(x, y, w.rule("lifeAlarmLoud", 30), 2);
                    if (((int) alarm[v]) % 6 == 0) w.fx.sound("caralarm", x, y);
                }
            }
        }
    }

    /** One of the city's cars, once a second: arrived, fleeing, stuck among the dead. */
    private void car(int v, int s, float dt) {
        Vehicles vh = w.veh;
        Sectors sec = w.sec;
        float x = vh.x[v], y = vh.y[v];
        byte st = vh.state[v];
        boolean danger = w.sp.secZ[s] > 0 && w.sp.zombiesNear(w.e, x, y, 220, 1) > 0 || sec.panic[s] > 0.15f;
        if (vh.crewN[v] == 0) {
            // the driver was dragged out: an empty car in the road
            kind[v] = K_ABANDONED;
            vh.route[v] = null;
            vh.state[v] = Vehicles.V_ARRIVED;
            return;
        }
        if (danger && !flee[v]) {
            flee[v] = true;
            fleeFrom(v, x, y);
        }
        // stuck: in a jam with the dead coming, or mobbed
        if (vh.spd[v] < 3 && (st == Vehicles.V_DRIVE || st == Vehicles.V_ARRIVED)) stuckT[v] += dt;
        else stuckT[v] = Math.max(0, stuckT[v] - dt);
        boolean close = w.sp.secZ[s] > 0 && w.sp.zombiesNear(w.e, x, y, 70, 1) > 0;
        if (flee[v] && (vh.mobbed(v) || close && stuckT[v] > 1 || stuckT[v] > w.rule("lifeAbandonAfter", 12))) {
            abandon(v);
            return;
        }
        if (st == Vehicles.V_ARRIVED || st == Vehicles.V_PARKED) {
            if (flee[v]) {
                // nowhere left to drive to: out and on foot
                abandon(v);
                return;
            }
            // there: the driver goes in, the car stays at the kerb (or into a garage, if the street is full)
            int to = w.r.sectorOfPx(x, y);
            for (int k = vh.crewN[v] - 1; k >= 0; k--) indoors(vh.crew[v][k], to);
            vh.crewN[v] = 0;
            folded++;
            if (parked < w.rule("lifeParkedMax", 260)) {
                kind[v] = K_PARKED;
                parked++;
                cars--;
            } else drop(v);
        }
    }

    /** Everyone out and running; the car stays where it stopped, in everyone's way. */
    private void abandon(int v) {
        Vehicles vh = w.veh;
        Ents e = w.e;
        int n = vh.crewN[v];
        if (n > 0) {
            vh.alightAll(v);
            for (int k = 0; k < n; k++) {
                // (alightAll keeps the crew array: the people it held are now on foot)
                int m = vh.crew[v][k];
                if (e.loc[m] != Ents.FREE) continue;
                e.flags[m] |= Ents.F_FLEEING;
                e.state[m] = CivAI.S_FLEE;
                e.timer[m] = 8;
            }
        }
        vh.route[v] = null;
        vh.spd[v] = 0;
        vh.state[v] = Vehicles.V_ARRIVED;
        kind[v] = K_ABANDONED;
        alarm[v] = Rng.unit(w.seed, vuid[v], 3) < 0.3f ? 15 : 0;
        abandoned++;
    }

    /** A wreck of Life's: it burns a while (drawn), and its place at the kerb is gone. */
    void wrecked(int v) {
        if (v < kind.length && spot[v] >= 0) gone.add(spot[v]);
        if (v < kind.length && kind[v] != K_NONE) alarm[v] = 0;
    }

    // ------------------------------------------------------------------ driving off, and where to

    /** Away from the dead: a junction a few km off, away from the nearest of them (or from the town's worst). */
    private void fleeFrom(int v, float x, float y) {
        Vehicles vh = w.veh;
        Ents e = w.e;
        int z = w.sp.nearestZombie(e, x, y, 400);
        float ux, uy;
        if (z >= 0) {
            ux = x - e.x[z];
            uy = y - e.y[z];
        } else {
            ux = (float) Math.cos(vuid[v] * 2.4f);
            uy = (float) Math.sin(vuid[v] * 2.4f);
        }
        float l = (float) Math.sqrt(ux * ux + uy * uy) + 0.01f;
        ux /= l;
        uy /= l;
        float d = 3000 + Rng.unit(w.seed, vuid[v], 11) * 3000;
        float px = w.r.px() - 1;
        float tx = Math.max(1, Math.min(px, x + ux * d)), ty = Math.max(1, Math.min(px, y + uy * d));
        int saved = dest[v];
        dest[v] = w.r.sectorOfPx(tx, ty);
        if (!vh.driveTo(v, tx, ty, 0)) dest[v] = saved;
        vh.siren[v] = false;
    }

    // ------------------------------------------------------------------ traffic

    /** How busy the roads are at this hour, 0..1 (rush hours at 1). */
    static float rush(float h) {
        if (h < 5) return 0.06f;
        if (h < 6.5f) return 0.06f + (h - 5) / 1.5f * 0.3f;
        if (h < 7.5f) return 0.36f + (h - 6.5f) * 0.64f;
        if (h < 9) return 1f;
        if (h < 10) return 1f - (h - 9) * 0.45f;
        if (h < 12) return 0.55f;
        if (h < 13.5f) return 0.65f;
        if (h < 16) return 0.55f;
        if (h < 16.5f) return 0.55f + (h - 16) * 0.9f;
        if (h < 18.5f) return 1f;
        if (h < 20) return 1f - (h - 18.5f) / 1.5f * 0.55f;
        if (h < 23) return 0.45f - (h - 20) / 3f * 0.3f;
        return 0.15f - (h - 23) * 0.09f;
    }

    /** The junctions in the area (and the pages they were gathered from), worked out once a second. */
    private int[] nodes = new int[256];
    private int nodeN;

    private void gatherNodes() {
        RoadGraph g = w.r.roads;
        int P = ActiveSet.PX;
        g.nodeChunks(P);
        int[] nc = g.nodeChunks(P);
        nodeN = 0;
        int px0 = (int) (ax0 / P), py0 = (int) (ay0 / P), px1 = (int) (ax1 / P), py1 = (int) (ay1 / P);
        for (int py = py0; py <= py1; py++)
            for (int px = px0; px <= px1; px++)
                for (int k = g.nodeChunkFrom(px, py), e = g.nodeChunkTo(px, py); k < e; k++) {
                    int n = nc[k];
                    if (!inArea(g.nx[n], g.ny[n]) || !g.nodeLive(n)) continue;
                    if (nodeN == nodes.length) nodes = Arrays.copyOf(nodes, nodeN * 2);
                    nodes[nodeN++] = n;
                }
    }

    /** Cars in the area for the hour and the streets: spawn some where too few (the rest arrive or drive off). */
    private void traffic(float dt) {
        gatherNodes();
        if (nodeN < 2) return;
        Sectors sec = w.sec;
        Commute cm = commute;
        float h = w.hour();
        float rush = rush(h);
        // the cars the area should hold: busy streets by day, the centre always, the suburbs at the rush hours
        float want = 0;
        float cs = sec.cs;
        int sx0 = (int) (ax0 / cs), sy0 = (int) (ay0 / cs), sx1 = Math.min(sec.sw - 1, (int) (ax1 / cs)), sy1 = Math.min(sec.sh - 1, (int) (ay1 / cs));
        for (int sy = sy0; sy <= sy1; sy++)
            for (int sx = sx0; sx <= sx1; sx++) {
                int s = sy * sec.sw + sx;
                float ox = Math.min(ax1, (sx + 1) * cs) - Math.max(ax0, sx * cs), oy = Math.min(ay1, (sy + 1) * cs) - Math.max(ay0, sy * cs);
                if (ox <= 0 || oy <= 0) continue;
                float busy = cm.busy(s);
                // the centre stays busy all day; homes empty into the roads at the rush hours
                float k = busy * (0.35f + 0.65f * rush) * Math.max(0.15f, rush);
                want += k * ox * oy / 1e6f;
            }
        want *= w.rule("lifeCars", 7);
        boolean closed = w.gov.streetsClosed();
        if (closed) want *= 0.1f;
        if (w.daylight() < 0.3f) want *= 0.7f;
        want = Math.min(want, w.rule("lifeCarsMax", 110));
        int room = (int) want - cars;
        // people fleeing by car where the dead have come (and there's panic): extra, away from them
        int panicky = 0;
        for (int sy = sy0; sy <= sy1; sy++)
            for (int sx = sx0; sx <= sx1; sx++) {
                int s = sy * sec.sw + sx;
                if (sec.panic[s] > 0.2f && sec.popIn[s] > 50) panicky++;
            }
        if (panicky > 0 && cars < w.rule("lifeCarsMax", 110) * 1.3f) room = Math.max(room, Math.min(3, panicky));
        int burst = cars < want * 0.5f ? 6 : 2;
        for (int k = 0; k < Math.min(room, burst); k++) spawnCar(k, cars < want * 0.5f, panicky > 0);
    }

    /** One car sets off: from a parked place or a junction in the area, to a building elsewhere or out through it. */
    private void spawnCar(int k, boolean anywhere, boolean panic) {
        RoadGraph g = w.r.roads;
        Sectors sec = w.sec;
        Commute cm = commute;
        Vehicles vh = w.veh;
        Content.VType ct = w.c.vtype("car");
        if (ct == null) return;
        long salt = w.tick * 13 + k * 101;
        // a junction to start from: off the screen unless the area is still filling, in a street where people live
        int a = -1;
        for (int t = 0; t < 8 && a < 0; t++) {
            int n = nodes[Rng.hashInt(w.seed, salt, t, nodeN)];
            float x = g.nx[n], y = g.ny[n];
            if (!anywhere && onScreen(x, y, -40)) continue;
            int s = w.r.sectorOfPx(x, y);
            if (sec.popIn[s] <= 0) continue;
            if (Rng.unit(w.seed, salt, t + 50) > 0.15f + cm.busy(s)) continue;
            if (panic && sec.panic[s] < 0.2f && t < 6) continue;
            a = n;
        }
        if (a < 0) return;
        float x = g.nx[a], y = g.ny[a];
        int s = w.r.sectorOfPx(x, y);
        // somewhere to go: across the area to another street, or out of it, in towards the centre in the morning and
        // out of it in the evening
        float h = w.hour();
        float tx, ty;
        boolean through = Rng.unit(w.seed, salt, 7) < 0.55f || nodeN < 6;
        Region.Settlement home = sec.settlement[s] >= 0 ? w.r.settlements.get(sec.settlement[s]) : null;
        if (through) {
            float ang = Rng.unit(w.seed, salt, 8) * 6.2831853f;
            float ux = (float) Math.cos(ang), uy = (float) Math.sin(ang);
            if (home != null) {
                float hx = home.cx * Tile.SIZE - x, hy = home.cy * Tile.SIZE - y, hl = (float) Math.sqrt(hx * hx + hy * hy);
                if (hl > 600) {
                    float in = h < 11 ? 0.75f : h > 15 && h < 21 ? 0.25f : 0.5f;
                    float sgn = Rng.unit(w.seed, salt, 9) < in ? 1 : -1;
                    ux = ux * 0.5f + sgn * hx / hl;
                    uy = uy * 0.5f + sgn * hy / hl;
                    float ul = (float) Math.sqrt(ux * ux + uy * uy) + 0.001f;
                    ux /= ul;
                    uy /= ul;
                }
            }
            float d = 2500 + Rng.unit(w.seed, salt, 10) * 3500;
            float px = w.r.px() - 1;
            tx = Math.max(1, Math.min(px, x + ux * d));
            ty = Math.max(1, Math.min(px, y + uy * d));
        } else {
            int b = nodes[Rng.hashInt(w.seed, salt, 11, nodeN)];
            tx = g.nx[b];
            ty = g.ny[b];
            if (Math.abs(tx - x) + Math.abs(ty - y) < 500) return;
        }
        if (panic) {
            // away from the dead instead
            int z = w.sp.nearestZombie(w.e, x, y, 900);
            if (z >= 0) {
                float ux = x - w.e.x[z], uy = y - w.e.y[z], l = (float) Math.sqrt(ux * ux + uy * uy) + 0.01f;
                float px = w.r.px() - 1;
                tx = Math.max(1, Math.min(px, x + ux / l * 4000));
                ty = Math.max(1, Math.min(px, y + uy / l * 4000));
            }
        }
        // room for it at the junction?
        float h0 = 0;
        if (g.adjStart[a + 1] > g.adjStart[a]) {
            int e0 = g.adjEdge[g.adjStart[a]];
            int o = g.other(e0, a);
            h0 = (float) Math.atan2(g.ny[o] - y, g.nx[o] - x);
        }
        if (!vh.clearAt(x, y, h0, ct.length * 0.5f + 6, ct.width * 0.5f + 6)) return;
        int v = take(ct, x, y, h0, K_CAR);
        if (!board(v, s, k)) {
            drop(v);
            return;
        }
        if (Rng.unit(w.seed, salt, 12) < 0.25f) board(v, s, k + 7);
        dest[v] = w.r.sectorOfPx(tx, ty);
        if (!vh.driveTo(v, tx, ty, 0)) {
            fold(v);
            return;
        }
        flee[v] = panic;
        cars++;
        spawned++;
    }

    // ------------------------------------------------------------------ parked cars along the kerbs

    /**
     * The kerb's places in each page in view: a car in each that the hash keeps for its street, filled in when the
     * page comes into view and cleared when it leaves (a car driven off or wrecked leaves its place empty meanwhile).
     */
    private void kerbs() {
        float m = w.rule("lifeKerbMargin", 300);
        int P = ActiveSet.PX;
        float x0 = Math.max(0, cx0 - m), y0 = Math.max(0, cy0 - m), x1 = Math.min(w.r.px() - 1, cx1 + m), y1 = Math.min(w.r.px() - 1, cy1 + m);
        int px0 = (int) (x0 / P), py0 = (int) (y0 / P), px1 = (int) (x1 / P), py1 = (int) (y1 / P);
        int pw = w.act.pw;
        // pages that left the view: their places are free again
        for (int k = 0; k < filledN; ) {
            int p = filled[k], px = p % pw, py = p / pw;
            if (px < px0 || px > px1 || py < py0 || py > py1) {
                filled[k] = filled[--filledN];
                purge(p);
            } else k++;
        }
        for (int py = py0; py <= py1; py++)
            for (int px = px0; px <= px1; px++) {
                int p = py * pw + px;
                boolean done = false;
                for (int k = 0; k < filledN && !done; k++) done = filled[k] == p;
                if (done) continue;
                if (filledN == filled.length) filled = Arrays.copyOf(filled, filledN * 2);
                filled[filledN++] = p;
                fillPage(p);
            }
        // (kept in page order: the same list whatever order the camera came in)
        Arrays.sort(filled, 0, filledN);
    }

    private void purge(int p) {
        if (gone.isEmpty()) return;
        java.util.Iterator<Long> it = gone.iterator();
        while (it.hasNext()) if (pageOfSpot(it.next()) == p) it.remove();
    }

    /** A place's key: the edge, the side and the place along it; and the page whose list it is in. */
    private static long spotKey(int edge, int side, int k) {
        return ((long) edge << 8) | (side > 0 ? 128 : 0) | k;
    }

    private int pageOfSpot(long key) {
        int e = (int) (key >>> 8);
        RoadGraph g = w.r.roads;
        float mx = (g.nx[g.ea[e]] + g.nx[g.eb[e]]) * 0.5f, my = (g.ny[g.ea[e]] + g.ny[g.eb[e]]) * 0.5f;
        return w.act.pageOfPx(mx, my);
    }

    /** Every place along the streets whose middle is in page p, and a car in those the hash keeps. */
    private void fillPage(int p) {
        RoadGraph g = w.r.roads;
        int P = ActiveSet.PX, pw = w.act.pw;
        int pcx = p % pw, pcy = p / pw;
        Content.VType ct = w.c.vtype("car");
        if (ct == null) return;
        int[] nc = g.nodeChunks(P);
        float hl = ct.length * 0.5f, hw = ct.width * 0.5f;
        float density = w.rule("lifeParked", 0.22f);
        // edges are found from their junctions in this page and the eight round it, each kept once (by its middle)
        for (int qy = pcy - 1; qy <= pcy + 1; qy++)
            for (int qx = pcx - 1; qx <= pcx + 1; qx++)
                for (int kk = g.nodeChunkFrom(qx, qy), ke = g.nodeChunkTo(qx, qy); kk < ke; kk++) {
                    int n = nc[kk];
                    for (int j = g.adjStart[n]; j < g.adjStart[n + 1]; j++) {
                        int e = g.adjEdge[j];
                        int o = g.other(e, n);
                        // each edge once: from its first end, or from the second if the first is beyond the 3x3
                        if (n != g.ea[e] && inChunks(g.nx[o], g.ny[o], pcx, pcy)) continue;
                        if (!g.ealive[e] || g.ekind[e] == RoadGraph.HIGHWAY || g.ebridge[e]) continue;
                        float mx = (g.nx[n] + g.nx[o]) * 0.5f, my = (g.ny[n] + g.ny[o]) * 0.5f;
                        if (w.act.pageOfPx(mx, my) != p) continue;
                        placeAlong(e, Math.min(n, o), Math.max(n, o), ct, hl, hw, density);
                    }
                }
    }

    private boolean inChunks(float x, float y, int pcx, int pcy) {
        int P = ActiveSet.PX;
        int qx = (int) (x / P), qy = (int) (y / P);
        return Math.abs(qx - pcx) <= 1 && Math.abs(qy - pcy) <= 1;
    }

    private void placeAlong(int e, int a, int b, Content.VType ct, float hl, float hw, float density) {
        RoadGraph g = w.r.roads;
        Sectors sec = w.sec;
        float ax = g.nx[a], ay = g.ny[a], bx = g.nx[b], by = g.ny[b];
        float ux = bx - ax, uy = by - ay, len = (float) Math.sqrt(ux * ux + uy * uy);
        if (len < 120) return;
        ux /= len;
        uy /= len;
        float half = g.ehalf[e];
        float lane = Math.min(half * 0.45f, 14);
        // at the kerb, half on the pavement; never where it would stand in the lane
        float kerb = Math.max(half + hw - 4, lane + hw * 2 + 4);
        float clear = Math.max(maxHalf(a), maxHalf(b)) + hl + 10;
        float step = ct.length + 7;
        int places = (int) ((len - 2 * clear) / step);
        int cap = (int) w.rule("lifeParkedMax", 320);
        for (int k = 0; k < places && k < 127 && parked < cap; k++) {
            float d = clear + step * (k + 0.5f) + ((len - 2 * clear) - places * step) * 0.5f;
            for (int side = -1; side <= 1; side += 2) {
                float sx = ax + ux * d - uy * kerb * side, sy = ay + uy * d + ux * kerb * side;
                int s = w.r.sectorOfPx(sx, sy);
                float dens = density * (0.25f + commute.parkWeight(s));
                long key = spotKey(e, side, k);
                if (Rng.unit(w.seed ^ 0x9A4C3L, key, 17) >= dens) continue;
                if (gone.contains(key)) continue;
                float hh = side > 0 ? (float) Math.atan2(uy, ux) : (float) Math.atan2(-uy, -ux);
                if (!standable(sx, sy, hh, hl, hw)) continue;
                if (!w.veh.clearAt(sx, sy, hh, hl + 1, hw + 1)) continue;
                if (sec.zIn[s] > 0 && w.sp.secZ[s] > 20) continue;
                int v = take(ct, sx, sy, hh, K_PARKED);
                spot[v] = key;
                parked++;
            }
        }
    }

    private float maxHalf(int n) {
        RoadGraph g = w.r.roads;
        float m = 0;
        for (int k = g.adjStart[n]; k < g.adjStart[n + 1]; k++) m = Math.max(m, g.ehalf[g.adjEdge[k]]);
        return m;
    }

    /** Road, pavement or open ground under the whole footprint, and no water or building. */
    private boolean standable(float px, float py, float h, float hl, float hw) {
        float c = (float) Math.cos(h), sn = (float) Math.sin(h);
        for (int k = 0; k < 5; k++) {
            float a = k == 0 ? 0 : (k & 1) == 0 ? hl : -hl, b = k == 0 ? 0 : k < 3 ? hw : -hw;
            float qx = px + c * a - sn * b, qy = py + sn * a + c * b;
            int tt = w.r.tileAtPx(qx, qy);
            if (!Tile.walkable(tt) && !Tile.roadish(tt) || tt == Tile.RAIL || tt == Tile.BARRICADE) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ people on foot

    /** What a building is good for on the way: 0 a home, 1 work (offices, industry), 2 shops, 3 school, -1 none. */
    private int use(int b) {
        switch (w.r.bkind(b)) {
            case Region.B_HOUSE: case Region.B_APARTMENT: case Region.B_FARMHOUSE: return 0;
            case Region.B_OFFICE: case Region.B_TOWER: case Region.B_GOV: case Region.B_HALL: case Region.B_WAREHOUSE:
            case Region.B_HOSPITAL: case Region.B_LAB: case Region.B_DEPOT: case Region.B_STATION: case Region.B_TERMINAL: return 1;
            case Region.B_SHOP: case Region.B_MALL: return 2;
            case Region.B_SCHOOL: case Region.B_CHURCH: return 3;
            default: return -1;
        }
    }

    /**
     * Someone just come out into the street in sector s (Sectors' ordinary life): a door to come out of and one to
     * go to for the hour (from home to work or school in the morning, to the shops by day, home in the evening), and
     * sometimes others with them (a family, friends) who follow their lead.
     */
    void pedestrian(int i, int s, int k) {
        Ents e = w.e;
        Sectors sec = w.sec;
        float h = w.hour();
        long salt = (long) e.uid[i] * 7919L;
        // from: homes in the morning, work in the evening; to: work, school or shops; or home
        int from, to;
        if (h < 10) {
            from = 0;
            to = Rng.unit(w.seed, salt, 1) < 0.2f ? 3 : Rng.unit(w.seed, salt, 2) < 0.2f ? 2 : 1;
        } else if (h < 16) {
            from = Rng.unit(w.seed, salt, 1) < 0.5f ? 1 : 0;
            to = Rng.unit(w.seed, salt, 2) < 0.6f ? 2 : 1;
        } else {
            from = Rng.unit(w.seed, salt, 1) < 0.7f ? 1 : 2;
            to = Rng.unit(w.seed, salt, 2) < 0.8f ? 0 : 2;
        }
        int b0 = doorOf(s, from, salt + 3);
        if (b0 >= 0) {
            float dx = w.r.doorX(b0), dy = w.r.doorY(b0);
            if (w.r.walkablePx(dx, dy)) {
                e.x[i] = dx;
                e.y[i] = dy;
            }
        }
        float x = e.x[i], y = e.y[i];
        int b1 = -1;
        float bd = 0;
        // a door of the kind wanted 120-600 px off, in this sector or the next ones
        for (int t = 0; t < 10; t++) {
            int q = t < 4 ? s : sec.neighbour(s, Rng.hashInt(w.seed, salt, t + 40, 8));
            if (q < 0) continue;
            int b = doorOf(q, to, salt + 50 + t);
            if (b < 0) continue;
            float ddx = w.r.doorX(b) - x, ddy = w.r.doorY(b) - y, d = (float) Math.sqrt(ddx * ddx + ddy * ddy);
            if (d < 120 || d > 600) continue;
            if (b1 < 0 || Math.abs(d - 320) < Math.abs(bd - 320)) {
                b1 = b;
                bd = d;
            }
        }
        if (b1 < 0) return; // nowhere for the hour: a wander as before
        e.state[i] = CivAI.S_ERRAND;
        e.gx[i] = w.r.doorX(b1);
        e.gy[i] = w.r.doorY(b1);
        e.timer[i] = 60 + bd / 12;
        // company: a family or friends from the same door, following this one
        float r = Rng.unit(w.seed, salt, 60);
        int more = r < 0.12f ? 2 + Rng.hashInt(w.seed, salt, 61, 2) : r < 0.3f ? 1 : 0;
        if (more == 0) return;
        e.flags[i] |= Ents.F_LEADER;
        for (int m = 0; m < more && sec.popIn[s] > 0; m++) {
            float ox = x + (m + 1) * 5 - 6, oy = y + ((m & 1) == 0 ? 5 : -5);
            if (!w.r.walkablePx(ox, oy)) {
                ox = x;
                oy = y;
            }
            int j = w.spawnCiv(ox, oy, w.c.unit("civilian"));
            e.flags[j] |= Ents.F_AMBIENT;
            e.home[j] = s;
            e.state[j] = CivAI.S_ERRAND;
            e.gx[j] = e.gx[i];
            e.gy[j] = e.gy[i];
            e.timer[j] = e.timer[i] + 20;
            e.target[j] = i;
            sec.popIn[sec.touch(s)]--;
            sec.ambient[s]++;
        }
    }

    /** A building of sector s with a door, of the use wanted if there is one (k tries), else any; -1 if none. */
    private int doorOf(int s, int want, long salt) {
        int[] bl = w.secBuildings[s];
        if (bl.length == 0) return -1;
        int any = -1;
        for (int a = 0; a < 8; a++) {
            int b = bl[Rng.hashInt(w.seed, salt, a, bl.length)];
            if (w.r.doorX(b) < 0) continue;
            if (any < 0) any = b;
            if (use(b) == want) return b;
        }
        return any;
    }

    /** Crowds running from the dead: packed tight enough, some go down under the others' feet. */
    private void trample() {
        Ents e = w.e;
        Spatial sp = w.sp;
        for (int i = 0; i < e.top; i++) {
            if (e.loc[i] != Ents.FREE || e.kind[i] != Ents.CIV || (e.flags[i] & Ents.F_FLEEING) == 0 || i == w.playerEnt) continue;
            int c = sp.cellOf(e.x[i], e.y[i]);
            int n = 0;
            for (int j = sp.lStart[c]; j < sp.lEnd[c]; j++) {
                int o = sp.lList[j];
                if (o == i || e.loc[o] != Ents.FREE || (e.flags[o] & Ents.F_FLEEING) == 0) continue;
                float dx = e.x[o] - e.x[i], dy = e.y[o] - e.y[i];
                if (dx * dx + dy * dy < 20 * 20) n++;
            }
            if (n < 6) continue;
            if (Rng.unit(w.seed, w.tick, e.uid[i] * 5L + 1) >= 0.012f * (n - 5)) continue;
            e.hp[i] -= 20 + Rng.unit(w.seed, w.tick, e.uid[i] * 5L + 2) * 30;
            w.fx.blood(e.x[i], e.y[i], 1);
            w.fx.sound("hurt", e.x[i], e.y[i]);
            trampled++;
            if (e.hp[i] <= 0) w.combat.kill(i, Combat.CAUSE_VEHICLE, -1);
        }
    }

    public int trampled;

    // ------------------------------------------------------------------ buses

    /** Bus lines: each town's loops of stops (junctions) round its centre, worked out once from the map. */
    private int[][] lineStops;
    private int[] lineSet;
    private float[] lineBox; // x0, y0, x1, y1 per line
    /** Game time the next bus on each line may set off into the area (saved). */
    private float[] lineNext = new float[0];

    private void lines() {
        if (lineStops != null) return;
        RoadGraph g = w.r.roads;
        java.util.List<int[]> ls = new java.util.ArrayList<int[]>();
        java.util.List<Integer> set = new java.util.ArrayList<Integer>();
        float per = w.rule("lifeBusPer", 60000);
        for (Region.Settlement st : w.r.settlements) {
            if (st.kind != Region.S_CITY && st.kind != Region.S_TOWN) continue;
            if (st.residents < per * 0.4f) continue;
            int n = Math.max(1, Math.min(10, Math.round(st.residents / per)));
            float cx = st.cx * Tile.SIZE, cy = st.cy * Tile.SIZE, rad = st.radius * Tile.SIZE;
            for (int l = 0; l < n; l++) {
                float r = rad * (0.2f + 0.65f * Rng.unit(w.seed ^ 0xB05L, st.id, l * 3 + 1));
                float ph = Rng.unit(w.seed ^ 0xB05L, st.id, l * 3 + 2) * 6.2831853f;
                // a loop of stops round the centre, a little squashed so the lines differ
                float sq = 0.6f + 0.4f * Rng.unit(w.seed ^ 0xB05L, st.id, l * 3 + 3);
                int k = Math.max(6, Math.min(14, (int) (r * 6.28f / 900)));
                int[] stops = new int[k];
                int m = 0;
                for (int i = 0; i < k; i++) {
                    float a = ph + i * 6.2831853f / k;
                    float px = cx + (float) Math.cos(a) * r, py = cy + (float) Math.sin(a) * r * sq;
                    int nd = g.nearestNode(px, py, 700);
                    if (nd < 0 || m > 0 && stops[m - 1] == nd || highwayOnly(nd)) continue;
                    stops[m++] = nd;
                }
                if (m >= 4 && stops[0] == stops[m - 1]) m--;
                if (m < 4) continue;
                ls.add(Arrays.copyOf(stops, m));
                set.add(st.id);
            }
        }
        lineStops = ls.toArray(new int[0][]);
        lineSet = new int[set.size()];
        lineBox = new float[set.size() * 4];
        for (int l = 0; l < lineSet.length; l++) {
            lineSet[l] = set.get(l);
            float x0 = 1e9f, y0 = 1e9f, x1 = -1e9f, y1 = -1e9f;
            for (int nd : lineStops[l]) {
                x0 = Math.min(x0, g.nx[nd]);
                y0 = Math.min(y0, g.ny[nd]);
                x1 = Math.max(x1, g.nx[nd]);
                y1 = Math.max(y1, g.ny[nd]);
            }
            lineBox[l * 4] = x0;
            lineBox[l * 4 + 1] = y0;
            lineBox[l * 4 + 2] = x1;
            lineBox[l * 4 + 3] = y1;
        }
        if (lineNext.length != lineSet.length) lineNext = Arrays.copyOf(lineNext, lineSet.length);
    }

    private boolean highwayOnly(int n) {
        RoadGraph g = w.r.roads;
        for (int k = g.adjStart[n]; k < g.adjStart[n + 1]; k++) if (g.ekind[g.adjEdge[k]] != RoadGraph.HIGHWAY) return false;
        return true;
    }

    /** Buses set off into the area on the lines that pass through it, one every so often on each. */
    private void busLines() {
        Content.VType bt = w.c.vtype("city_bus");
        if (bt == null) return;
        lines();
        RoadGraph g = w.r.roads;
        Vehicles vh = w.veh;
        float every = w.rule("lifeBusEvery", 40);
        boolean night = w.hour() < 5.5f || w.hour() > 23.5f;
        for (int l = 0; l < lineSet.length; l++) {
            if (lineBox[l * 4 + 2] < ax0 || lineBox[l * 4] > ax1 || lineBox[l * 4 + 3] < ay0 || lineBox[l * 4 + 1] > ay1) continue;
            if (w.time < lineNext[l] || night || w.gov.streetsClosed()) continue;
            int live = 0;
            for (int v = 0; v < vh.top; v++) if (v < kind.length && kind[v] == K_BUS && line[v] == l && mine(v)) live++;
            if (live >= 2) continue;
            // the first stop in the area that is off the screen (else any in the area), and on from there
            int[] st = lineStops[l];
            int at = -1;
            for (int pass = 0; pass < 2 && at < 0; pass++)
                for (int k = 0; k < st.length; k++) {
                    float x = g.nx[st[k]], y = g.ny[st[k]];
                    if (inArea(x, y) && (pass == 1 || !onScreen(x, y, 60))) {
                        at = k;
                        break;
                    }
                }
            if (at < 0) continue;
            lineNext[l] = (float) w.time + every * (0.7f + 0.6f * Rng.unit(w.seed, w.tick, l));
            int nd = st[at];
            float x = g.nx[nd], y = g.ny[nd];
            int nx = st[(at + 1) % st.length];
            float h0 = (float) Math.atan2(g.ny[nx] - y, g.nx[nx] - x);
            if (!vh.clearAt(x, y, h0, bt.length * 0.5f + 6, bt.width * 0.5f + 6)) continue;
            int v = take(bt, x, y, h0, K_BUS);
            line[v] = l;
            int s = w.r.sectorOfPx(x, y);
            int on = (int) (3 + rush(w.hour()) * 14);
            for (int k = 0; k < on; k++) if (!board(v, s, 300 + k)) break;
            nextStop(v, (at + 1) % st.length);
            buses++;
            spawned++;
        }
    }

    private void nextStop(int v, int k) {
        RoadGraph g = w.r.roads;
        int[] st = lineStops[line[v]];
        stop[v] = k;
        int nd = st[k];
        dest[v] = w.r.sectorOfPx(g.nx[nd], g.ny[nd]);
        dwell[v] = 0;
        // (no way there, a road gone: on to the next one next second)
        if (!w.veh.driveTo(v, g.nx[nd], g.ny[nd], 0)) stop[v] = (k + 1) % st.length;
    }

    /** A bus once a second: at its stop, people get off (and go in) and on; then on to the next stop. */
    private void bus(int v, int s, float dt) {
        Vehicles vh = w.veh;
        Ents e = w.e;
        lines();
        if (line[v] < 0 || line[v] >= lineStops.length) {
            fold(v);
            return;
        }
        float x = vh.x[v], y = vh.y[v];
        boolean danger = w.sp.secZ[s] > 0 && w.sp.zombiesNear(e, x, y, 160, 1) > 0;
        if (vh.spd[v] < 3) stuckT[v] += dt;
        else stuckT[v] = 0;
        if (vh.mobbed(v) || danger && stuckT[v] > 3) {
            abandon(v);
            return;
        }
        byte st = vh.state[v];
        if (st != Vehicles.V_ARRIVED && st != Vehicles.V_PARKED) return;
        if (dwell[v] == 0 && !danger) {
            // off: a share of those aboard, into the buildings round the stop; on: some of the street's people
            int to = w.r.sectorOfPx(x, y);
            for (int k = vh.crewN[v] - 1; k >= 0; k--) {
                int m = vh.crew[v][k];
                if (Rng.unit(w.seed, w.tick, e.uid[m]) < 0.3f) {
                    vh.crew[v][k] = vh.crew[v][--vh.crewN[v]];
                    indoors(m, to);
                }
            }
            int on = (int) (rush(w.hour()) * 8 * Rng.unit(w.seed, w.tick, vuid[v]));
            for (int k = 0; k < on; k++) if (!board(v, to, 500 + k)) break;
        }
        dwell[v] += dt;
        if (dwell[v] >= 5 || danger) nextStop(v, (stop[v] + 1) % lineStops[line[v]].length);
    }

    // ------------------------------------------------------------------ trains

    /** Passenger trains: where each is on its route (rail junctions), its speed, and who is aboard (counted). */
    private int trainN;
    private int[][] tRoute = new int[4][];
    private int[] tRi = new int[4], tPass = new int[4], tInf = new int[4], tDest = new int[4];
    private float[] tX = new float[4], tY = new float[4], tHead = new float[4], tSpd = new float[4], tDwell = new float[4];
    private float trainNext;

    public int trains() {
        return trainN;
    }

    /** People aboard Life's trains (counted nowhere else). */
    public long carried() {
        long n = 0;
        for (int k = 0; k < trainN; k++) n += tPass[k] + tInf[k];
        return n;
    }

    private int[] railNodes = new int[64];
    private int railN;
    /** Each rail junction's station (facility id), or -1; worked out once. */
    private int[] stationOf;

    private int stationAt(int n) {
        if (stationOf == null) {
            stationOf = new int[w.r.rails.nodeCount];
            Arrays.fill(stationOf, -1);
            for (Region.Facility f : w.r.facilities)
                if (f.kind == Region.F_STATION && f.railNode >= 0 && f.railNode < stationOf.length && stationOf[f.railNode] < 0) stationOf[f.railNode] = f.id;
        }
        return n >= 0 && n < stationOf.length ? stationOf[n] : -1;
    }

    /** A passenger train into the area now and then: from a rail junction there to a station far off. */
    private void trainsIn() {
        RoadGraph g = w.r.rails;
        if (g == null || g.nodeCount < 2 || g.adjStart == null) return;
        if (w.time < trainNext || trainN >= 2 || w.gov.streetsClosed()) return;
        int P = ActiveSet.PX;
        int[] nc = g.nodeChunks(P);
        railN = 0;
        for (int py = (int) (ay0 / P); py <= (int) (ay1 / P); py++)
            for (int px = (int) (ax0 / P); px <= (int) (ax1 / P); px++)
                for (int k = g.nodeChunkFrom(px, py), e = g.nodeChunkTo(px, py); k < e; k++) {
                    int n = nc[k];
                    if (!inArea(g.nx[n], g.ny[n]) || onScreen(g.nx[n], g.ny[n], 80) || !g.nodeLive(n)) continue;
                    if (railN == railNodes.length) railNodes = Arrays.copyOf(railNodes, railN * 2);
                    railNodes[railN++] = n;
                }
        trainNext = (float) w.time + w.rule("lifeTrainEvery", 90) * (0.6f + 0.8f * Rng.unit(w.seed, w.tick, 77));
        if (railN == 0) return;
        int a = railNodes[Rng.hashInt(w.seed, w.tick, 78, railN)];
        // to a station at least 8 km off (else the farthest one)
        Region.Facility to = null;
        float best = -1;
        for (Region.Facility f : w.r.facilities) {
            if (f.kind != Region.F_STATION || f.railNode < 0 || f.railNode >= g.nodeCount) continue;
            float d = (float) Math.hypot(g.nx[f.railNode] - g.nx[a], g.ny[f.railNode] - g.ny[a]);
            float score = d >= 8000 ? 1e6f - Rng.unit(w.seed, f.id, w.tick) * 1e5f : d;
            if (score > best) {
                best = score;
                to = f;
            }
        }
        if (to == null) return;
        int[] rt = g.route(a, to.railNode);
        if (rt == null || rt.length < 2) return;
        if (trainN == tRoute.length) growTrains();
        int k = trainN++;
        tRoute[k] = rt;
        tRi[k] = 0;
        tX[k] = g.nx[a];
        tY[k] = g.ny[a];
        tHead[k] = (float) Math.atan2(g.ny[rt[1]] - tY[k], g.nx[rt[1]] - tX[k]);
        tSpd[k] = 220;
        tDest[k] = w.r.sectorOfPx(to.x, to.y);
        tDwell[k] = 0;
        // already carrying people from where it came from (they boarded in the streets round where it comes in)
        int s = w.r.sectorOfPx(tX[k], tY[k]);
        int n = (int) (40 + rush(w.hour()) * 120);
        int took = Math.max(0, Math.min(n, w.sec.popIn[s] / 4));
        if (took > 0) w.sec.popIn[w.sec.touch(s)] -= took;
        tPass[k] = took;
        tInf[k] = 0;
        spawned++;
    }

    private void growTrains() {
        int n = tRoute.length * 2;
        tRoute = Arrays.copyOf(tRoute, n);
        tRi = Arrays.copyOf(tRi, n); tPass = Arrays.copyOf(tPass, n); tInf = Arrays.copyOf(tInf, n);
        tDest = Arrays.copyOf(tDest, n);
        tX = Arrays.copyOf(tX, n); tY = Arrays.copyOf(tY, n); tHead = Arrays.copyOf(tHead, n);
        tSpd = Arrays.copyOf(tSpd, n); tDwell = Arrays.copyOf(tDwell, n);
    }

    private void removeTrain(int k) {
        // its people get where it was going
        int s = tDest[k];
        if (tPass[k] > 0) w.sec.popIn[w.sec.touch(s)] += tPass[k];
        if (tInf[k] > 0) w.sec.inf[w.sec.touch(s)] += tInf[k];
        folded++;
        trainN--;
        tRoute[k] = tRoute[trainN];
        tRi[k] = tRi[trainN]; tPass[k] = tPass[trainN]; tInf[k] = tInf[trainN]; tDest[k] = tDest[trainN];
        tX[k] = tX[trainN]; tY[k] = tY[trainN]; tHead[k] = tHead[trainN];
        tSpd[k] = tSpd[trainN]; tDwell[k] = tDwell[trainN];
        tRoute[trainN] = null;
    }

    private void foldTrains() {
        while (trainN > 0) removeTrain(trainN - 1);
    }

    /** Trains along their routes each step; they stop at the stations in the area, and run down the dead on the line. */
    private void trainSteps(float dt) {
        RoadGraph g = w.r.rails;
        for (int k = 0; k < trainN; k++) {
            if (!inArea(tX[k], tY[k]) || tRi[k] >= tRoute[k].length - 1) {
                removeTrain(k--);
                continue;
            }
            if (tDwell[k] > 0) {
                tDwell[k] = Math.max(0, tDwell[k] - dt);
                continue;
            }
            int[] rt = tRoute[k];
            int b = rt[tRi[k] + 1];
            // slowing for a station ahead
            int sta = stationAt(b);
            float dx = g.nx[b] - tX[k], dy = g.ny[b] - tY[k], d = (float) Math.sqrt(dx * dx + dy * dy);
            float want = 260;
            if (sta >= 0) want = Math.max(25, Math.min(260, d * 0.6f));
            tSpd[k] += Math.max(-80 * dt, Math.min(40 * dt, want - tSpd[k]));
            float left = tSpd[k] * dt;
            while (left > 0 && tRi[k] < rt.length - 1) {
                b = rt[tRi[k] + 1];
                dx = g.nx[b] - tX[k];
                dy = g.ny[b] - tY[k];
                d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d > 0.01f) tHead[k] = (float) Math.atan2(dy, dx);
                if (d <= left) {
                    tX[k] = g.nx[b];
                    tY[k] = g.ny[b];
                    tRi[k]++;
                    left -= d;
                    int f = stationAt(b);
                    if (f >= 0) {
                        stationStop(k, f);
                        break;
                    }
                } else {
                    tX[k] += dx / d * left;
                    tY[k] += dy / d * left;
                    left = 0;
                }
            }
            // anything dead on the line in front goes under it
            if (tSpd[k] > 30 && w.sp.secZ[w.r.sectorOfPx(tX[k], tY[k])] > 0) {
                float fx = tX[k] + (float) Math.cos(tHead[k]) * 20, fy = tY[k] + (float) Math.sin(tHead[k]) * 20;
                int z = w.sp.nearestZombie(w.e, fx, fy, 16);
                if (z >= 0) w.combat.killZombie(z, -1, Ents.CIV, Combat.CAUSE_VEHICLE);
            }
        }
    }

    /** At a station: some get off (into the station's streets), some get on; the infected among them too. */
    private void stationStop(int k, int fid) {
        Region.Facility f = w.r.facilities.get(fid);
        Sectors sec = w.sec;
        int s = w.r.sectorOfPx(f.x, f.y);
        tSpd[k] = 0;
        tDwell[k] = 8;
        int off = tPass[k] / 4;
        if (off > 0) {
            tPass[k] -= off;
            sec.popIn[sec.touch(s)] += off;
        }
        if (tInf[k] > 0) {
            // the bitten aboard get off with the rest
            int io = Math.max(1, tInf[k] / 3);
            tInf[k] -= io;
            sec.inf[sec.touch(s)] += io;
        }
        if (w.sp.secZ[s] > 0 || w.gov.streetsClosed()) return;
        int on = Math.min(sec.popIn[s] / 10, (int) (w.rule("lifeTrainBoard", 30) * (0.3f + rush(w.hour()))));
        if (on > 0) {
            // those boarding where people are bitten may carry it with them
            int sick = sec.inf[s] >= 1 ? Math.min((int) sec.inf[s], Math.round(on * sec.inf[s] / (sec.popIn[s] + sec.inf[s]))) : 0;
            sec.popIn[sec.touch(s)] -= on - sick;
            if (sick > 0) sec.inf[sec.touch(s)] -= sick;
            tPass[k] += on - sick;
            tInf[k] += sick;
        }
    }

    // ------------------------------------------------------------------ patrols

    /** The squads Life sent out to patrol (a police station's district, a base's perimeter) and where from. */
    private int[] pSquad = new int[8], pFac = new int[8];
    private int pN;
    private float[] facNext = new float[0];

    private void dropPatrol(int k) {
        pSquad[k] = pSquad[--pN];
        pFac[k] = pFac[pN];
    }

    private void patrols() {
        java.util.List<Region.Facility> fs = w.r.facilities;
        if (facNext.length < fs.size()) facNext = Arrays.copyOf(facNext, fs.size());
        float reach = 1500;
        // ours sent elsewhere (a call) are no longer ours; ours out of sight go back into the pools
        for (int k = 0; k < pN; ) {
            Squads.Squad s = pSquad[k] < w.sq.list.size() ? w.sq.get(pSquad[k]) : null;
            if (s == null || !s.alive || s.order != Squads.O_PATROL) {
                dropPatrol(k);
                continue;
            }
            float[] at = w.sq.where(s);
            boolean seen = at[0] > ax0 - reach && at[0] < ax1 + reach && at[1] > ay0 - reach && at[1] < ay1 + reach;
            if (!seen && w.sp.zombiesNear(w.e, at[0], at[1], 300, 1) == 0) {
                w.forces.standDown(s);
                dropPatrol(k);
                continue;
            }
            k++;
        }
        if (w.gov.level >= 3) return; // (the alert is up: the stations' people are the dispatchers' now)
        boolean night = w.daylight() < 0.3f;
        for (int fi = 0; fi < fs.size(); fi++) {
            Region.Facility f = fs.get(fi);
            boolean police = f.kind == Region.F_POLICE, base = f.kind == Region.F_BASE;
            if (!police && !base) continue;
            if (!inArea(f.x, f.y) || w.time < facNext[fi] || !w.strat.online(f.id)) continue;
            int want = (int) w.rule(police ? "lifePatrols" : "lifeBasePatrols", police ? 2 : 1);
            if (night && police) want = Math.max(1, want - 1);
            int out = 0;
            for (int k = 0; k < pN; k++) if (pFac[k] == fi) out++;
            if (out >= want) continue;
            Content.SquadT t = w.c.squadT(police ? "patrol" : "fireteam");
            if (t == null || t.vehicle == null || f.pool == null || f.vpool == null || f.vpool[t.vehicle.index] <= 0) continue;
            boolean enough = true;
            for (Content.Unit u : t.units) if (f.pool[u.index] <= 0) enough = false;
            if (!enough) continue;
            facNext[fi] = (float) w.time + 25;
            Squads.Squad s = w.forces.deploy(f, t);
            if (s == null) continue;
            if (pN == pSquad.length) {
                pSquad = Arrays.copyOf(pSquad, pN * 2);
                pFac = Arrays.copyOf(pFac, pN * 2);
            }
            pSquad[pN] = s.id;
            pFac[pN++] = fi;
            float[] p = patrolPoint(fi, s.id * 31L + (long) w.time);
            w.sq.order(s, Squads.O_PATROL, p[0], p[1]);
        }
    }

    /** Every patrol back into its pool (the camera left street level). */
    private void foldPatrols() {
        while (pN > 0) {
            Squads.Squad s = pSquad[pN - 1] < w.sq.list.size() ? w.sq.get(pSquad[pN - 1]) : null;
            if (s != null && s.alive && s.order == Squads.O_PATROL) w.forces.standDown(s);
            pN--;
        }
    }

    /** The next spot on a patrol from facility fi: a junction round the station's district, or round the base. */
    private float[] patrolPoint(int fi, long salt) {
        Region.Facility f = w.r.facilities.get(fi);
        RoadGraph g = w.r.roads;
        boolean base = f.kind == Region.F_BASE;
        float r = base ? Math.max((f.bx1 - f.bx0), (f.by1 - f.by0)) * Tile.SIZE * 0.7f + 300 : 2200;
        for (int a = 0; a < 16; a++) {
            float ang = Rng.unit(w.seed, salt, a) * 6.2831853f, d = r * (base ? 1f : 0.35f + 0.65f * Rng.unit(w.seed, salt, a + 20));
            int n = g.nearestNode(f.x + (float) Math.cos(ang) * d, f.y + (float) Math.sin(ang) * d, 500);
            if (n >= 0 && !highwayOnly(n)) return new float[]{g.nx[n], g.ny[n]};
        }
        return new float[]{f.x, f.y};
    }

    /** Vehicles.patrolOn asks: is this one of the patrols Life sent out? Then it picks the next spot round its home. */
    boolean patrolOn(int v) {
        if (!on) return false;
        for (int k = 0; k < pN; k++) {
            if (pSquad[k] >= w.sq.list.size()) continue;
            Squads.Squad s = w.sq.get(pSquad[k]);
            if (s.vehicle != v) continue;
            float[] p = patrolPoint(pFac[k], (long) w.veh.uid[v] * 977L + w.tick);
            if (w.veh.driveTo(v, p[0], p[1], 0)) w.veh.siren[v] = false;
            else w.veh.state[v] = Vehicles.V_ARRIVED;
            return true;
        }
        return false;
    }

    @Override
    public void step() {
        if (on && trainN > 0) trainSteps(World.DT);
    }

    // ------------------------------------------------------------------ saves

    @Override
    public void save(DataOutputStream o) throws IOException {
        int n = Math.min(w.veh.top, kind.length);
        int m = 0;
        for (int v = 0; v < n; v++) if (mine(v)) m++;
        o.writeInt(m);
        for (int v = 0; v < n; v++) {
            if (!mine(v)) continue;
            o.writeInt(v);
            o.writeByte(kind[v]);
            o.writeInt(dest[v]);
            o.writeLong(spot[v]);
            o.writeFloat(alarm[v]);
            o.writeFloat(stuckT[v]);
            o.writeBoolean(flee[v]);
            o.writeInt(line[v]);
            o.writeInt(stop[v]);
            o.writeFloat(dwell[v]);
        }
        o.writeBoolean(has);
        o.writeInt(filledN);
        for (int k = 0; k < filledN; k++) o.writeInt(filled[k]);
        Long[] g = gone.toArray(new Long[0]);
        Arrays.sort(g);
        o.writeInt(g.length);
        for (Long x : g) o.writeLong(x);
        o.writeLong(spawned);
        o.writeLong(folded);
        o.writeInt(trainN);
        for (int k = 0; k < trainN; k++) {
            SaveIO.intArr(o, tRoute[k]);
            o.writeInt(tRi[k]);
            o.writeInt(tPass[k]);
            o.writeInt(tInf[k]);
            o.writeInt(tDest[k]);
            o.writeFloat(tX[k]);
            o.writeFloat(tY[k]);
            o.writeFloat(tHead[k]);
            o.writeFloat(tSpd[k]);
            o.writeFloat(tDwell[k]);
        }
        o.writeFloat(trainNext);
        o.writeInt(pN);
        for (int k = 0; k < pN; k++) {
            o.writeInt(pSquad[k]);
            o.writeInt(pFac[k]);
        }
        SaveIO.floatArr(o, facNext);
        SaveIO.floatArr(o, lineNext);
        o.writeInt(trampled);
        commute.save(o);
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        ensure(w.veh.top + 1);
        Arrays.fill(kind, K_NONE);
        int m = in.readInt();
        for (int k = 0; k < m; k++) {
            int v = in.readInt();
            ensure(v + 1);
            kind[v] = in.readByte();
            vuid[v] = w.veh.uid[v];
            dest[v] = in.readInt();
            spot[v] = in.readLong();
            alarm[v] = in.readFloat();
            stuckT[v] = in.readFloat();
            flee[v] = in.readBoolean();
            line[v] = in.readInt();
            stop[v] = in.readInt();
            dwell[v] = in.readFloat();
        }
        has = in.readBoolean();
        filledN = in.readInt();
        if (filled.length < filledN) filled = new int[filledN];
        for (int k = 0; k < filledN; k++) filled[k] = in.readInt();
        gone.clear();
        int ng = in.readInt();
        for (int k = 0; k < ng; k++) gone.add(in.readLong());
        spawned = in.readLong();
        folded = in.readLong();
        trainN = in.readInt();
        while (tRoute.length < trainN) growTrains();
        for (int k = 0; k < trainN; k++) {
            tRoute[k] = SaveIO.readIntArr(in);
            tRi[k] = in.readInt();
            tPass[k] = in.readInt();
            tInf[k] = in.readInt();
            tDest[k] = in.readInt();
            tX[k] = in.readFloat();
            tY[k] = in.readFloat();
            tHead[k] = in.readFloat();
            tSpd[k] = in.readFloat();
            tDwell[k] = in.readFloat();
        }
        trainNext = in.readFloat();
        pN = in.readInt();
        if (pSquad.length < pN) {
            pSquad = new int[pN];
            pFac = new int[pN];
        }
        for (int k = 0; k < pN; k++) {
            pSquad[k] = in.readInt();
            pFac[k] = in.readInt();
        }
        float[] fn = SaveIO.readFloatArr(in);
        facNext = fn == null ? new float[0] : fn;
        float[] ln = SaveIO.readFloatArr(in);
        lineNext = ln == null ? new float[0] : ln;
        trampled = in.readInt();
        commute.load(in);
        if (has) {
            area();
            tally();
        }
    }

    @Override
    public String status() {
        return null;
    }

    // ------------------------------------------------------------------ drawing

    private final float[] pts = new float[256];

    /**
     * Over the map: trains on the line, the police and soldiers at home in their yards and bases (the pools, as
     * figures: officers by the station with its cars parked, sentries at the gate, a patrol along the fence, a drill
     * on the parade ground by day), wrecks burning, alarms flashing.
     */
    @Override
    public void draw(Gfx g, Camera cam, WorldView view) {
        if (!on || w.strategic) return;
        float z = cam.zoom;
        if (z < 0.22f) return;
        float dp = view.dp;
        float x0 = cam.x0() - 120, y0 = cam.y0() - 120, x1 = cam.x1() + 120, y1 = cam.y1() + 120;
        float t = (float) w.time;
        // wrecks of the city's cars burn a while; alarms flash
        Vehicles vh = w.veh;
        for (int v = 0; v < vh.top; v++) {
            if (!mine(v)) continue;
            float x = vh.x[v], y = vh.y[v];
            if (x < x0 || x > x1 || y < y0 || y > y1) continue;
            float sx = cam.toScreenX(x), sy = cam.toScreenY(y);
            if (vh.state[v] == Vehicles.V_WRECK && vh.timer[v] < 90) {
                float f = 1 - vh.timer[v] / 90, fl = 0.75f + 0.25f * (float) Math.sin(t * 23 + v);
                g.color(MapPainter.alpha(0xFFFF8A20, 0.55f * f * fl));
                g.fillCircle(sx, sy, 10 * z * (1 + f) * fl + 2 * dp);
                g.color(MapPainter.alpha(0xFFFFE070, 0.7f * f));
                g.fillCircle(sx + 2 * z, sy - 2 * z, 5 * z * (1 + f) * fl + 1 * dp);
            } else if (alarm[v] > 0 && ((int) (t * 4)) % 2 == 0) {
                float c = (float) Math.cos(vh.head[v]), s = (float) Math.sin(vh.head[v]), L = 11 * z;
                g.color(0xFFFFB020);
                g.fillCircle(sx + c * L, sy + s * L, Math.max(1.5f * dp, 2.5f * z));
                g.fillCircle(sx - c * L, sy - s * L, Math.max(1.5f * dp, 2.5f * z));
            }
        }
        trainsDraw(g, cam, z, dp, x0, y0, x1, y1);
        if (z >= 0.3f) homes(g, cam, z, dp, x0, y0, x1, y1, t);
    }

    private void trainsDraw(Gfx g, Camera cam, float z, float dp, float x0, float y0, float x1, float y1) {
        RoadGraph rg = w.r.rails;
        float carL = 46, gap = 4;
        for (int k = 0; k < trainN; k++) {
            if (tX[k] < x0 - 600 || tX[k] > x1 + 600 || tY[k] < y0 - 600 || tY[k] > y1 + 600) continue;
            // the cars back along the line from the front
            float px = tX[k], py = tY[k];
            int ri = tRi[k];
            int[] rt = tRoute[k];
            float bx = px, by = py, back = 0;
            float hx = (float) Math.cos(tHead[k]), hy = (float) Math.sin(tHead[k]);
            for (int c = 0; c < 6; c++) {
                // walk carL + gap back along the route
                float need = (c == 0 ? 0 : carL + gap);
                float fx = bx, fy = by;
                while (need > 0) {
                    float tx, ty;
                    if (ri >= 0) {
                        tx = rg.nx[rt[ri]];
                        ty = rg.ny[rt[ri]];
                    } else {
                        tx = bx - hx * need;
                        ty = by - hy * need;
                    }
                    float dx = tx - bx, dy = ty - by, d = (float) Math.sqrt(dx * dx + dy * dy);
                    if (d < 0.01f) {
                        ri--;
                        if (ri < -1) break;
                        continue;
                    }
                    if (d <= need) {
                        bx = tx;
                        by = ty;
                        need -= d;
                        hx = -dx / d;
                        hy = -dy / d;
                        ri--;
                    } else {
                        bx += dx / d * need;
                        by += dy / d * need;
                        hx = -dx / d;
                        hy = -dy / d;
                        need = 0;
                    }
                }
                // a car between (fx, fy) going back carL
                float ang = (float) Math.atan2(hy, hx);
                if (c == 0) ang = tHead[k];
                float mx = bx - (float) Math.cos(ang) * carL * 0.5f, my = by - (float) Math.sin(ang) * carL * 0.5f;
                float sx = cam.toScreenX(mx), sy = cam.toScreenY(my);
                float L = carL * z, W = 13 * z;
                g.save();
                g.translate(sx, sy);
                g.rotate(ang);
                g.color(0x50000000);
                g.fillRoundRect(-L / 2 + 2 * z, -W / 2 + 3 * z, L, W, 2 * z);
                g.color(0xFF141414);
                g.fillRoundRect(-L / 2 - 0.5f * dp, -W / 2 - 0.5f * dp, L + dp, W + dp, 3 * z);
                g.color(c == 0 ? 0xFFC8342C : 0xFFD8DCE0);
                g.fillRoundRect(-L / 2, -W / 2, L, W, 3 * z);
                g.color(c == 0 ? 0xFF8A2018 : 0xFF2F4F6F);
                if (c == 0) g.fillRect(L * 0.1f, -W / 2 + 2 * z, L * 0.25f, W - 4 * z);
                else for (int wdw = 0; wdw < 5; wdw++) g.fillRect(-L / 2 + 4 * z + wdw * L / 5.3f, -W / 2 + 2.5f * z, L / 8, W - 5 * z);
                g.restore();
                if (ri < -1) break;
                bx = mx - (float) Math.cos(ang) * carL * 0.5f;
                by = my - (float) Math.sin(ang) * carL * 0.5f;
            }
        }
    }

    /** The police and soldiers at home, as figures from their pools (nothing of this is simulated person by person). */
    private void homes(Gfx g, Camera cam, float z, float dp, float x0, float y0, float x1, float y1, float t) {
        float size = Math.max(2.4f * dp, Math.min(4.2f * dp, 8 * z)) * 1.3f;
        Content.VType pc = w.c.vtype("patrol_car");
        for (Region.Facility f : w.r.facilities) {
            boolean police = f.kind == Region.F_POLICE, base = f.kind == Region.F_BASE;
            if (!police && !base || f.pool == null) continue;
            if (f.x < x0 - 800 || f.x > x1 + 800 || f.y < y0 - 800 || f.y > y1 + 800) continue;
            int s = w.r.sectorOfPx(f.x, f.y);
            if (w.sp.secZ[s] > 0 || w.sec.hot[s] > 5) continue; // (out to the fight: nobody standing about)
            int n = 0;
            if (police) {
                int officers = Math.min(10, w.forces.armedAt(f, Ents.COP) / 6);
                // in a loose knot by the door, turning now and then
                for (int k = 0; k < officers; k++) {
                    float a = k * 2.4f + Rng.unit(w.seed, f.id, k) * 0.8f, r = 10 + (k % 4) * 6;
                    float sway = (float) Math.sin(t * 0.3f + k) * 3;
                    n = add(n, f.x + (float) Math.cos(a) * r + sway, f.y + (float) Math.sin(a) * r);
                }
                // the station's cars, parked in a row by the yard
                if (pc != null && f.vpool != null && pc.index < f.vpool.length) {
                    int cars = Math.min(5, f.vpool[pc.index]);
                    float bx = (f.bx0 + f.bx1 + 1) * 0.5f * Tile.SIZE, by = (f.by1 + 1) * Tile.SIZE + 4;
                    for (int c = 0; c < cars; c++) parkedFigure(g, cam, bx + (c - cars / 2f) * 16, by, 1.5708f, pc, z, dp);
                }
            } else {
                float bx0 = f.bx0 * Tile.SIZE + 12, by0 = f.by0 * Tile.SIZE + 12, bx1 = (f.bx1 + 1) * Tile.SIZE - 12, by1 = (f.by1 + 1) * Tile.SIZE - 12;
                if (bx1 - bx0 < 40 || by1 - by0 < 40) {
                    bx0 = f.x - 300;
                    bx1 = f.x + 300;
                    by0 = f.y - 300;
                    by1 = f.y + 300;
                }
                // sentries at the gate
                n = add(n, f.x - 9, f.y);
                n = add(n, f.x + 9, f.y);
                // a patrol walking the fence, two by two
                float per = 2 * ((bx1 - bx0) + (by1 - by0));
                for (int p = 0; p < 2; p++) {
                    float d = ((t * 14 + p * per / 2) % per + per) % per;
                    float[] q = alongRect(bx0, by0, bx1, by1, d);
                    n = add(n, q[0], q[1]);
                    q = alongRect(bx0, by0, bx1, by1, (d + per - 9) % per);
                    n = add(n, q[0], q[1]);
                }
                // drill by day: a block of soldiers marching up and down the parade ground
                if (w.daylight() > 0.5f && w.forces.armedAt(f, Ents.SOLDIER) >= 20) {
                    float px = (bx0 + bx1) * 0.5f, py = (by0 + by1) * 0.5f + (by1 - by0) * 0.18f;
                    float march = (float) Math.sin(t * 0.25f) * Math.min(120, (bx1 - bx0) * 0.3f);
                    for (int r = 0; r < 4; r++)
                        for (int c = 0; c < 6; c++) {
                            float step = ((int) (t * 3) + r) % 2 == 0 ? 0.6f : -0.6f;
                            n = add(n, px + march + c * 9 - 22, py + r * 9 + step);
                        }
                }
            }
            if (n == 0) continue;
            for (int k = 0; k < n; k++) {
                pts[k * 2] = cam.toScreenX(pts[k * 2]);
                pts[k * 2 + 1] = cam.toScreenY(pts[k * 2 + 1]);
            }
            g.color(0xFFF2F4F8);
            g.points(pts, n, size + Math.max(1.5f, 1.1f * dp));
            g.color(police ? WorldView.C_COP : WorldView.C_ARMY);
            g.points(pts, n, size);
        }
    }

    private int add(int n, float x, float y) {
        if (n * 2 + 2 > pts.length) return n;
        pts[n * 2] = x;
        pts[n * 2 + 1] = y;
        return n + 1;
    }

    private static float[] alongRect(float x0, float y0, float x1, float y1, float d) {
        float w = x1 - x0, h = y1 - y0;
        if (d < w) return new float[]{x0 + d, y0};
        d -= w;
        if (d < h) return new float[]{x1, y0 + d};
        d -= h;
        if (d < w) return new float[]{x1 - d, y1};
        d -= w;
        return new float[]{x0, y1 - d};
    }

    private void parkedFigure(Gfx g, Camera cam, float x, float y, float h, Content.VType t, float z, float dp) {
        float L = t.length * z, W = t.width * z;
        g.save();
        g.translate(cam.toScreenX(x), cam.toScreenY(y));
        g.rotate(h);
        g.color(0xFF141414);
        g.fillRoundRect(-L / 2 - 0.5f * dp, -W / 2 - 0.5f * dp, L + dp, W + dp, 3 * z);
        g.color(t.color);
        g.fillRoundRect(-L / 2, -W / 2, L, W, 3 * z);
        if (t.stripe != 0) {
            g.color(t.stripe);
            g.fillRect(-L / 2, -1.2f * z, L, 2.4f * z);
        }
        g.color(MapPainter.shade(t.color, 0.72f));
        g.fillRect(-L * 0.12f, -W / 2 + 1.5f * z, L * 0.38f, W - 3 * z);
        g.restore();
    }
}
