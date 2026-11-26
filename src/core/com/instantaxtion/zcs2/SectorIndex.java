package com.instantaxtion.zcs2;

/**
 * What makes the once-a-second work scale with what is happening rather than with the size of the map
 * (docs/SCALE_PLAN.md, 2.5):
 * - each settlement's sectors as a sorted list, so "the sectors of town st" costs that town's size, not the map's;
 *   and running totals of the people in sleeping districts, so the totals need not scan the map;
 * - districts of 8x8 sectors with sums of the dead, the living and the infected;
 * - which districts are awake this second. A district is awake if any of its sectors (or of the eight districts
 *   round it) has anything the once-a-second work would change: the dead or the infected indoors, a hot timer,
 *   panic, hunger, a nest, a strain, a fire, anyone out on its streets, a vehicle, the camera. Every
 *   other sector is left exactly as it is by that work, so skipping it changes no outcome.
 *
 * The set for a second is worked out at the start of Sectors.update. Anything that raises a sector's counts later
 * in the second (a horde arriving, refugees settling, someone breaking in) calls poke(s), which adds its district
 * at once: loops later in the same second see it. A district goes back to sleep at the next start if all its
 * sectors are quiet. Loops that walk the awake sectors use first()/next(s), which give them in ascending order,
 * exactly the order of a plain loop over every sector.
 *
 * Staggering (national maps only, in fast-forward): districts awake but quiet (only wandering dead, decaying hunger
 * or panic, or the ring round busier ones) are due every 4th step, in a phase fixed by their id, with 4x dt.
 */
public final class SectorIndex {
    public static final int D = 8, SHIFT = 3;
    /** Tests: check at every start that each sleeping sector really is quiet (costs a scan of the map). */
    public static boolean paranoid;
    /** Stagger quiet districts in fast-forward: on by default on national maps only. */
    public boolean stagger;
    public static final int STAGGER = 4;

    private final World w;
    private final Sectors sec;
    public final int n, sw, sh, dw, dh, dn;
    /** Each settlement's sectors, ascending. */
    public int[][] bySettlement;
    /** Residents' homes in each settlement (fixed at the start). */
    public long[] homeBySettlement;
    /** Sectors outside every settlement that may hold people (homes there, or people put there since). */
    /**
     * People indoors (popIn) in sleeping districts, by settlement and in all. Every write to popIn wakes the
     * sector's district first (decrements too), so a sleeping district's people stay exactly as counted here.
     */
    private long[] frozenPop = new long[0];
    private long frozenTotal;
    /** Sums by district, as of the start of the second (or the last time the district was awake). */
    public final int[] dead, living, infected;
    /** Awake this second, by district; and how many are awake in each row of districts. */
    private final boolean[] awake;
    private final int[] rowAwake;
    private int awakeN;
    /** Due this step (stagger), and with what multiple of dt. */
    private final boolean[] due;
    private final byte[] scale;
    private final boolean[] act, ring;
    private boolean[] sick = new boolean[0];
    private int[] hospitals;
    private int[] camList = new int[64];
    private int camN;
    private boolean all = true;
    private long steps;
    /** Sectors visited by the last start's scan, and awake after it (for probes). */
    public int lastScanned, lastAwakeSectors;

    SectorIndex(World w, Sectors sec) {
        this.w = w;
        this.sec = sec;
        n = sec.n;
        sw = sec.sw;
        sh = sec.sh;
        dw = (sw + D - 1) >> SHIFT;
        dh = (sh + D - 1) >> SHIFT;
        dn = dw * dh;
        dead = new int[dn];
        living = new int[dn];
        infected = new int[dn];
        awake = new boolean[dn];
        rowAwake = new int[dh];
        due = new boolean[dn];
        scale = new byte[dn];
        act = new boolean[dn];
        ring = new boolean[dn];
        stagger = w.national;
        reindex();
    }

    /**
     * Works out the settlements' lists (and the hospitals') from Sectors.settlement and home, then wakes everything.
     * Call again if those are filled in afterwards (a national map's sectors start from the nation's residents).
     */
    public void reindex() {
        int ns = w.r.settlements.size();
        int[] count = new int[ns];
        homeBySettlement = new long[ns];
        for (int s = 0; s < n; s++) {
            int st = sec.settlement[s];
            if (st >= 0) {
                count[st]++;
                homeBySettlement[st] += sec.home[s];
            }
        }
        bySettlement = new int[ns][];
        for (int st = 0; st < ns; st++) bySettlement[st] = new int[count[st]];
        java.util.Arrays.fill(count, 0);
        for (int s = 0; s < n; s++) {
            int st = sec.settlement[s];
            if (st >= 0) bySettlement[st][count[st]++] = s;
        }
        // the hospitals' sectors, by settlement (the infected go to theirs)
        java.util.List<Integer> hl = new java.util.ArrayList<Integer>();
        for (Region.Facility f : w.r.facilities)
            if (f.kind == Region.F_HOSPITAL && f.settlement >= 0) {
                hl.add(f.settlement);
                hl.add(w.r.sectorOfPx(f.x, f.y));
            }
        hospitals = new int[hl.size()];
        for (int k = 0; k < hospitals.length; k++) hospitals[k] = hl.get(k);
        noSettlement = null;
        reset();
    }

    public int district(int s) {
        int y = s / sw;
        return (y >> SHIFT) * dw + ((s - y * sw) >> SHIFT);
    }

    /** Everything may have changed (a new game being set up, a save loaded): every district wakes. */
    public void reset() {
        all = true;
        java.util.Arrays.fill(awake, true);
        for (int dy = 0; dy < dh; dy++) rowAwake[dy] = dw;
        awakeN = dn;
        java.util.Arrays.fill(due, true);
        java.util.Arrays.fill(scale, (byte) 1);
        frozenPop = new long[bySettlement.length];
        frozenTotal = 0;
        camN = 0;
        for (int s = 0; s < n; s++) if (sec.cam[s]) addCam(s);
    }

    private void addCam(int s) {
        if (camN == camList.length) camList = java.util.Arrays.copyOf(camList, camN * 2);
        camList[camN++] = s;
    }

    private void setAwake(int d, boolean on) {
        if (awake[d] == on) return;
        // its people leave the frozen totals as it wakes, and join them as it sleeps
        int x0 = (d % dw) << SHIFT, y0 = (d / dw) << SHIFT, x1 = Math.min(sw, x0 + D), y1 = Math.min(sh, y0 + D);
        int sign = on ? -1 : 1;
        for (int y = y0; y < y1; y++)
            for (int s = y * sw + x0, e = y * sw + x1; s < e; s++) {
                int p = sec.popIn[s];
                if (p == 0) continue;
                frozenTotal += sign * p;
                int st = sec.settlement[s];
                if (st >= 0) frozenPop[st] += sign * p;
            }
        awake[d] = on;
        rowAwake[d / dw] += on ? 1 : -1;
        awakeN += on ? 1 : -1;
        if (on) {
            due[d] = true;
            scale[d] = 1;
        }
    }

    /**
     * Something in sector s rose (the dead, the infected, people, a hot timer, panic): its district is awake from
     * now on, in this second's loops too.
     */
    public void poke(int s) {
        if (s < 0) return;
        int d = district(s);
        if (!awake[d]) setAwake(d, true);
        else if (!due[d]) {
            due[d] = true;
            scale[d] = 1;
        }
    }

    /**
     * People indoors in sector s change by delta without waking its district (Commute: the day's comings and goings
     * leave a quiet sector quiet): a sleeping district's frozen totals and its sum of the living follow.
     */
    public void addPop(int s, int delta) {
        if (delta == 0) return;
        sec.popIn[s] += delta;
        int d = district(s);
        if (awake[d]) return;
        frozenTotal += delta;
        int st = sec.settlement[s];
        if (st >= 0 && st < frozenPop.length) frozenPop[st] += delta;
        living[d] += delta;
    }

    public boolean awake(int s) {
        return awake[district(s)];
    }

    public boolean districtAwake(int d) {
        return awake[d];
    }

    /**
     * False only if sector s certainly has nobody in it (popIn and secCiv both 0): its district is asleep and held
     * nobody when it was last awake. For searches round a point that skip whole districts.
     */
    public boolean mayLive(int s) {
        int d = district(s);
        return awake[d] || living[d] > 0;
    }

    public int awakeDistricts() {
        return awakeN;
    }

    // ------------------------------------------------------------------ the awake sectors, in ascending order

    /** The first awake sector, or -1. With due, only districts due this step (stagger). */
    public int first() {
        return next(-1, false);
    }

    public int next(int s) {
        return next(s, false);
    }

    public int firstDue() {
        return next(-1, true);
    }

    public int nextDue(int s) {
        return next(s, true);
    }

    /** The multiple of dt sector s is due with this step (1, or STAGGER for a staggered district). */
    public int scale(int s) {
        return scale[district(s)];
    }

    private int next(int s, boolean dueOnly) {
        int y, x;
        if (s < 0) {
            y = 0;
            x = 0;
        } else {
            y = s / sw;
            x = s - y * sw + 1;
            if (x < sw && (x & (D - 1)) != 0) return s + 1;
        }
        int dy = y >> SHIFT;
        while (true) {
            if (x >= sw) {
                y++;
                x = 0;
                if (y >= sh) return -1;
                dy = y >> SHIFT;
            }
            if (rowAwake[dy] == 0) {
                // a whole row of sleeping districts: on to the next
                y = (dy + 1) << SHIFT;
                x = 0;
                if (y >= sh) return -1;
                dy++;
                continue;
            }
            int dx = x >> SHIFT, d = dy * dw + dx;
            if (awake[d] && (!dueOnly || due[d])) return y * sw + x;
            x = (dx + 1) << SHIFT;
        }
    }

    private int[] noSettlement;

    /** The sectors of settlement st, ascending; for st < 0, the sectors outside every settlement. */
    public int[] settlementSectors(int st) {
        if (st >= 0) return bySettlement[st];
        if (noSettlement == null) {
            int m = 0;
            for (int s = 0; s < n; s++) if (sec.settlement[s] < 0) m++;
            noSettlement = new int[m];
            m = 0;
            for (int s = 0; s < n; s++) if (sec.settlement[s] < 0) noSettlement[m++] = s;
        }
        return noSettlement;
    }

    /** The sectors of every settlement st with pick[st], ascending (the order of a scan of the map). */
    public int[] sectorsOf(boolean[] pick) {
        int m = 0;
        for (int st = 0; st < bySettlement.length; st++) if (pick[st]) m += bySettlement[st].length;
        int[] out = new int[m];
        int k = 0, lists = 0;
        for (int st = 0; st < bySettlement.length; st++)
            if (pick[st]) {
                System.arraycopy(bySettlement[st], 0, out, k, bySettlement[st].length);
                k += bySettlement[st].length;
                lists++;
            }
        if (lists > 1) java.util.Arrays.sort(out);
        return out;
    }

    // ------------------------------------------------------------------ the people, wherever they may be

    /** Sum of popIn and (int) inf over the map: the frozen total, and the awake sectors (only they hold the infected). */
    public int totalPop() {
        long t = frozenTotal;
        int[] p = sec.popIn;
        float[] inf = sec.inf;
        for (int s = first(); s >= 0; s = next(s)) t += p[s] + (int) inf[s];
        return (int) t;
    }

    /** popIn summed by settlement into out (cleared first): frozen totals and the awake sectors. */
    public void popBySettlement(int[] out) {
        int m = Math.min(out.length, frozenPop.length);
        java.util.Arrays.fill(out, 0);
        for (int st = 0; st < m; st++) out[st] = (int) frozenPop[st];
        for (int s = first(); s >= 0; s = next(s)) {
            int st = sec.settlement[s];
            if (st >= 0 && st < out.length) out[st] += sec.popIn[s];
        }
    }

    /** Sum of zIn over the map (only awake sectors can hold any). */
    public int totalZIn() {
        int t = 0;
        for (int s = first(); s >= 0; s = next(s)) t += sec.zIn[s];
        return t;
    }

    // ------------------------------------------------------------------ the start of a second

    /** A sector the once-a-second work would leave exactly as it is (the modules add to this as they need). */
    boolean quiet(int s) {
        if (sec.zIn[s] != 0 || sec.inf[s] > 0 || sec.hot[s] > 0 || sec.panic[s] > 0 || sec.firstZ[s] >= 0) return false;
        Ecology e = w.ecology;
        if (e != null && (e.hunger[s] > 0 || e.nestT[s] > 0 || e.nest[s] || e.strain[s] != 0)) return false;
        if (w.fires != null && w.fires.burning(s)) return false;
        return true;
    }

    /**
     * Works out this second's awake districts and the cam flags (cam is exact everywhere: other systems read it
     * for any sector). Call once, at the start of Sectors.update.
     */
    void begin(float dt) {
        steps++;
        // the camera's sectors
        for (int k = 0; k < camN; k++) sec.cam[camList[k]] = false;
        camN = 0;
        if (w.camDetail) {
            float cs = sec.cs;
            float x0 = w.camX0 - cs * 0.5f, y0 = w.camY0 - cs * 0.5f, x1 = w.camX1 + cs * 0.5f, y1 = w.camY1 + cs * 0.5f;
            // (the same test as before, sector by sector, over the sectors the rectangle can touch)
            int ax = Math.max(0, (int) Math.floor(x0 / cs) - 1), bx = Math.min(sw - 1, (int) Math.floor(x1 / cs) + 1);
            int ay = Math.max(0, (int) Math.floor(y0 / cs) - 1), by = Math.min(sh - 1, (int) Math.floor(y1 / cs) + 1);
            for (int sy = ay; sy <= by; sy++)
                for (int sx = ax; sx <= bx; sx++) {
                    float sx0 = sx * cs, sy0 = sy * cs;
                    if (sx0 + cs > x0 && sx0 < x1 && sy0 + cs > y0 && sy0 < y1) {
                        int s = sy * sw + sx;
                        sec.cam[s] = true;
                        addCam(s);
                    }
                }
        }
        java.util.Arrays.fill(act, false);
        if (sick.length != bySettlement.length) sick = new boolean[bySettlement.length];
        else java.util.Arrays.fill(sick, false);
        int scanned = 0;
        // the districts awake last second (and those poked since): still anything in them?
        for (int d = 0; d < dn; d++) {
            if (!awake[d]) continue;
            int x0 = (d % dw) << SHIFT, y0 = (d / dw) << SHIFT, x1 = Math.min(sw, x0 + D), y1 = Math.min(sh, y0 + D);
            int z = 0, p = 0, f = 0;
            boolean any = false;
            for (int y = y0; y < y1; y++)
                for (int s = y * sw + x0, e = y * sw + x1; s < e; s++) {
                    z += sec.zIn[s] + w.sp.secZ[s];
                    p += sec.popIn[s] + w.sp.secCiv[s];
                    f += (int) sec.inf[s];
                    if (!any && !quiet(s)) any = true;
                    // (the infected or the dead indoors in a town: its hospital's sector joins the set below)
                    if ((sec.inf[s] > 0 || sec.zIn[s] > 0) && sec.settlement[s] >= 0) sick[sec.settlement[s]] = true;
                    scanned++;
                }
            dead[d] = z;
            living[d] = p;
            infected[d] = f;
            act[d] = any;
        }
        if (paranoid) check();
        // anyone out on the streets or on the roads, and the camera
        Ents e = w.e;
        for (int i = 0; i < e.top; i++)
            if (e.loc[i] == Ents.FREE || e.loc[i] == Ents.BODY) act[district(w.r.sectorOfPx(e.x[i], e.y[i]))] = true;
        Vehicles v = w.veh;
        // (a vehicle with people aboard: they get out where it is; an empty one changes nothing)
        for (int i = 0; i < v.top; i++) if (v.state[i] != Vehicles.V_NONE && v.crewN[i] > 0) act[district(w.r.sectorOfPx(v.x[i], v.y[i]))] = true;
        for (int k = 0; k < camN; k++) act[district(camList[k])] = true;
        boolean st = stagger && w.strategic;
        // the new set: the active districts and the ring round them
        java.util.Arrays.fill(ring, false);
        for (int d = 0; d < dn; d++) {
            if (!act[d]) continue;
            int dx = d % dw, dy = d / dw;
            for (int yy = Math.max(0, dy - 1); yy <= Math.min(dh - 1, dy + 1); yy++)
                for (int xx = Math.max(0, dx - 1); xx <= Math.min(dw - 1, dx + 1); xx++) ring[yy * dw + xx] = true;
        }
        for (int d = 0; d < dn; d++) {
            setAwake(d, ring[d]);
            due[d] = ring[d];
            scale[d] = 1;
        }
        // the infected walk to their town's hospital, wherever it is: its sector must be in the set (the infected
        // and the dead indoors are only ever in awake districts, all scanned above)
        for (int k = 0; k < hospitals.length; k += 2)
            if (sick[hospitals[k]]) setAwake(district(hospitals[k + 1]), true);
        if (st) {
            // quiet but awake: due every STAGGER steps, with STAGGER x dt
            for (int d = 0; d < dn; d++) {
                if (!awake[d] || busy(d)) continue;
                due[d] = (steps + d) % STAGGER == 0;
                scale[d] = (byte) STAGGER;
            }
        }
        all = false;
        lastScanned = scanned;
        lastAwakeSectors = 0;
        for (int d = 0; d < dn; d++)
            if (awake[d]) lastAwakeSectors += (Math.min(sw, ((d % dw) << SHIFT) + D) - ((d % dw) << SHIFT)) * (Math.min(sh, ((d / dw) << SHIFT) + D) - ((d / dw) << SHIFT));
    }

    /** A district with fighting, the infected, hot sectors or people out in it: never staggered. */
    private boolean busy(int d) {
        int x0 = (d % dw) << SHIFT, y0 = (d / dw) << SHIFT, x1 = Math.min(sw, x0 + D), y1 = Math.min(sh, y0 + D);
        for (int y = y0; y < y1; y++)
            for (int s = y * sw + x0, e = y * sw + x1; s < e; s++) {
                if (sec.zIn[s] > 0 && sec.popIn[s] > 0) return true;
                if (sec.inf[s] > 0 || sec.hot[s] > 0 || sec.cam[s]) return true;
                if (w.sp.secCiv[s] + w.sp.secCop[s] + w.sp.secSol[s] > 0) return true;
            }
        return false;
    }

    /** Tests: every sleeping sector is quiet (else a write somewhere is missing its poke), and the frozen totals hold. */
    private void check() {
        long total = 0;
        long[] by = new long[frozenPop.length];
        for (int s = 0; s < n; s++) {
            if (awake[district(s)]) continue;
            if (!quiet(s))
                throw new IllegalStateException("sector " + s + " asleep but not quiet: zIn " + sec.zIn[s] + " inf " + sec.inf[s] + " hot " + sec.hot[s] + " panic "
                        + sec.panic[s] + " firstZ " + sec.firstZ[s] + " at t=" + w.time);
            total += sec.popIn[s];
            if (sec.settlement[s] >= 0) by[sec.settlement[s]] += sec.popIn[s];
        }
        if (total != frozenTotal || !java.util.Arrays.equals(by, frozenPop))
            throw new IllegalStateException("people in sleeping districts " + total + " but counted " + frozenTotal + " at t=" + w.time);
    }
}
