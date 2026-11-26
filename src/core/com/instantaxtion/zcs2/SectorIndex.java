package com.instantaxtion.zcs2;

/**
 * What makes the once-a-second work scale with what is happening rather than with the size of the map
 * (docs/SCALE_PLAN.md, 2.5):
 * - each settlement's sectors as a sorted list, so "the sectors of town st" costs that town's size, not the map's;
 *   and the sectors outside any settlement where people may be ("rural"), so the totals need not scan the map;
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
    /** Above this many sectors a map is national: staggering, and the Director's district-sized heatmaps. */
    public static final int NATIONAL = 50000;

    private final World w;
    private final Sectors sec;
    public final int n, sw, sh, dw, dh, dn;
    /** Each settlement's sectors, ascending. */
    public final int[][] bySettlement;
    /** Residents' homes in each settlement (fixed at the start). */
    public final long[] homeBySettlement;
    /** Sectors outside every settlement that may hold people (homes there, or people put there since). */
    private int[] rural = new int[16];
    private int ruralN;
    private final boolean[] inRural;
    /** Sums by district, as of the start of the second (or the last time the district was awake). */
    public final int[] dead, living, infected;
    /** Awake this second, by district; and how many are awake in each row of districts. */
    private final boolean[] awake;
    private final int[] rowAwake;
    private int awakeN;
    /** Due this step (stagger), and with what multiple of dt. */
    private final boolean[] due;
    private final byte[] scale;
    private final boolean[] act;
    private final int[] hospitals;
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
        inRural = new boolean[n];
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
        int[] hs = new int[ns];
        java.util.Arrays.fill(hs, -1);
        java.util.List<Integer> hl = new java.util.ArrayList<Integer>();
        for (Region.Facility f : w.r.facilities)
            if (f.kind == Region.F_HOSPITAL && f.settlement >= 0) {
                hl.add(f.settlement);
                hl.add(w.r.sectorOfPx(f.x, f.y));
            }
        hospitals = new int[hl.size()];
        for (int k = 0; k < hospitals.length; k++) hospitals[k] = hl.get(k);
        stagger = n > NATIONAL;
        reset();
    }

    public int district(int s) {
        int y = s / sw;
        return (y >> SHIFT) * dw + ((s - y * sw) >> SHIFT);
    }

    /** Everything may have changed (a new game being set up, a save loaded): every district wakes. */
    public void reset() {
        all = true;
        for (int d = 0; d < dn; d++) setAwake(d, true);
        for (int k = 0; k < ruralN; k++) inRural[rural[k]] = false;
        ruralN = 0;
        for (int s = 0; s < n; s++)
            if (sec.settlement[s] < 0 && (sec.home[s] > 0 || sec.popIn[s] > 0 || sec.inf[s] > 0)) addRural(s);
        camN = 0;
        for (int s = 0; s < n; s++) if (sec.cam[s]) addCam(s);
    }

    private void addRural(int s) {
        if (inRural[s]) return;
        inRural[s] = true;
        if (ruralN == rural.length) rural = java.util.Arrays.copyOf(rural, ruralN * 2);
        rural[ruralN++] = s;
    }

    private void addCam(int s) {
        if (camN == camList.length) camList = java.util.Arrays.copyOf(camList, camN * 2);
        camList[camN++] = s;
    }

    private void setAwake(int d, boolean on) {
        if (awake[d] == on) return;
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
        if (!inRural[s] && sec.settlement[s] < 0) addRural(s);
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

    /** Sum of popIn and (int) inf over every sector that may hold anyone (the same as over the whole map). */
    public int totalPop() {
        int t = 0;
        int[] p = sec.popIn;
        float[] inf = sec.inf;
        for (int[] l : bySettlement) for (int s : l) t += p[s] + (int) inf[s];
        for (int k = 0; k < ruralN; k++) t += p[rural[k]] + (int) inf[rural[k]];
        return t;
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
        for (int d = 0; d < dn; d++) {
            boolean on = false;
            int dx = d % dw, dy = d / dw;
            for (int oy = -1; oy <= 1 && !on; oy++) {
                int yy = dy + oy;
                if (yy < 0 || yy >= dh) continue;
                for (int ox = -1; ox <= 1; ox++) {
                    int xx = dx + ox;
                    if (xx >= 0 && xx < dw && act[yy * dw + xx]) {
                        on = true;
                        break;
                    }
                }
            }
            setAwake(d, on);
            due[d] = on;
            scale[d] = 1;
        }
        // the infected walk to their town's hospital, wherever it is: its sector must be in the set
        for (int k = 0; k < hospitals.length; k += 2) {
            int[] l = bySettlement[hospitals[k]];
            boolean any = false;
            for (int j = 0; j < l.length && !any; j++) any = sec.inf[l[j]] > 0 || sec.zIn[l[j]] > 0;
            if (any) setAwake(district(hospitals[k + 1]), true);
        }
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

    /** Tests: every sleeping sector is quiet (else a write somewhere is missing its poke). */
    private void check() {
        for (int s = 0; s < n; s++) {
            if (awake[district(s)]) continue;
            if (!quiet(s))
                throw new IllegalStateException("sector " + s + " asleep but not quiet: zIn " + sec.zIn[s] + " inf " + sec.inf[s] + " hot " + sec.hot[s] + " panic "
                        + sec.panic[s] + " firstZ " + sec.firstZ[s] + " at t=" + w.time);
            if (sec.settlement[s] < 0 && !inRural[s] && (sec.popIn[s] > 0 || sec.inf[s] > 0))
                throw new IllegalStateException("sector " + s + " holds people but isn't listed");
        }
        for (int s = 0; s < n; s++)
            if (sec.settlement[s] < 0 && !inRural[s] && (sec.popIn[s] > 0 || sec.inf[s] > 0))
                throw new IllegalStateException("sector " + s + " holds people but isn't listed (popIn " + sec.popIn[s] + ") at t=" + w.time);
    }
}
