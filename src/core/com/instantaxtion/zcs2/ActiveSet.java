package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The sim-active chunks (64x64-tile pages, ChunkStore's): the only part of the map the street-level simulation reads,
 * and the only part that has a scent field, foot paths, a spatial grid and a vehicle grid (Flow, NavPath, Spatial and
 * Vehicles keep their cells per active page, in slots handed out here).
 * <ul>
 * <li>Dense (today's maps): every page is active, always, and page p is slot p. Nothing is ever recomputed, so the
 * simulation is exactly what it was before pages.</li>
 * <li>Sparse (national maps, P7; forced on today's maps by tests): a deterministic function of the simulation's
 * state, recomputed once a second at the start of World.secondly on one thread. Wanted, in this order of priority
 * until {@link #limit} pages are wanted: the chunk of every free individual, then a ring of one chunk round each;
 * chunks with a building fire; hot sectors' chunks plus a ring; the camera's chunks plus a ring; and a ring round
 * every ground vehicle that is moving or crewed and stands in a chunk wanted so far (a vehicle that has driven out
 * of the wanted area doesn't pull the map along: once past the chunks still lingering it is in transit, see
 * Vehicles). A chunk leaves after {@link #QUIET}
 * seconds without being wanted. A chunk something is put down in (a spawn, someone getting out, a body rising, a
 * vehicle in transit arriving) becomes active at once ({@link #touchPx}).</li>
 * <li>A chunk that enters is fetched or generated at that moment ({@link Source}), never in a parallel phase. That
 * only costs time: the result depends on the set, and the set only on the simulation.</li>
 * </ul>
 * A sparse set is saved (SaveIO), so a loaded game goes on with exactly the same set.
 */
public final class ActiveSet {
    /** Tests: worlds made from now on start sparse (a save says for itself which it is). */
    public static boolean sparseDefault;
    /** Seconds a chunk stays active after it was last wanted. */
    public static final int QUIET = 10;
    /** Tiles per page side, and the side in px. */
    public static final int SIZE = ChunkStore.SIZE, PX = SIZE * Tile.SIZE;

    /** Something whose cells are kept per active page: told when the slots grow and when one is handed to a page. */
    interface Paged {
        /** There are now cap slots (arrays grow to cap pages; what's in the old slots stays). */
        void slotsGrown(int cap);

        /** Slot s now holds page p: whatever was in the slot before is of no use. */
        void slotAssigned(int s, int p);
    }

    /** Makes a page resident before it becomes active, and lets it go when it leaves (P7: the ChunkCache, pinning). */
    interface Source {
        void fetch(int page);

        void release(int page);
    }

    private final World w;
    public final boolean sparse;
    /** The page grid (the ChunkStore's). */
    public final int pw, ph, pages;
    /** Each page's slot, or -1 if it isn't active. */
    final int[] slotOf;
    /** Each slot's page, or -1 if it's free. */
    int[] pageOf;
    /** Slots allocated, slots ever handed out (the rest of the free ones are on the free stack), pages active. */
    int cap, used, count;
    private int[] free = new int[16];
    private int freeN;
    /** Sparse: the game second each page was last wanted. */
    private final int[] lastWant;
    /** The most pages wanted at once (in priority order); sparse only. */
    public int limit = 512;
    /** Sparse, not computed yet: the first step works it out (a new game). */
    boolean fresh;
    /** Pages that entered and left over the game (for probes). */
    public long entered, left;
    Source source;
    private final List<Paged> users = new ArrayList<Paged>();
    // the wanted list of an update
    private final int[] mark;
    private int gen;
    private int[] want = new int[64];
    private int wn;

    ActiveSet(World w, boolean sparse) {
        this.w = w;
        this.sparse = sparse;
        ChunkStore st = w.r.store;
        pw = st.pw;
        ph = st.ph;
        pages = pw * ph;
        slotOf = new int[pages];
        if (sparse) {
            Arrays.fill(slotOf, -1);
            cap = 16;
            pageOf = new int[cap];
            Arrays.fill(pageOf, -1);
            lastWant = new int[pages];
            Arrays.fill(lastWant, Integer.MIN_VALUE / 2);
            mark = new int[pages];
            fresh = true;
        } else {
            cap = used = count = pages;
            pageOf = new int[pages];
            for (int p = 0; p < pages; p++) slotOf[p] = pageOf[p] = p;
            lastWant = null;
            mark = null;
        }
    }

    /** Adds a user of the slots (it is told about every slot held now). */
    void register(Paged u) {
        users.add(u);
        u.slotsGrown(cap);
        for (int s = 0; s < used; s++) if (pageOf[s] >= 0) u.slotAssigned(s, pageOf[s]);
    }

    public boolean active(int p) {
        return slotOf[p] >= 0;
    }

    public int activeCount() {
        return count;
    }

    /** The page holding the point (x, y) in px, clamped onto the map. */
    public int pageOfPx(float x, float y) {
        int cx = (int) (x / PX), cy = (int) (y / PX);
        if (cx < 0) cx = 0;
        if (cy < 0) cy = 0;
        if (cx >= pw) cx = pw - 1;
        if (cy >= ph) cy = ph - 1;
        return cy * pw + cx;
    }

    /** Is the point (x, y) in px (clamped onto the map) in an active chunk? */
    public boolean activePx(float x, float y) {
        return !sparse || slotOf[pageOfPx(x, y)] >= 0;
    }

    /** Something is put down at (x, y): its chunk is active from now on (and wanted this second). Serial code only. */
    public void touchPx(float x, float y) {
        if (!sparse) return;
        int p = pageOfPx(x, y);
        lastWant[p] = Math.max(lastWant[p], now());
        if (slotOf[p] < 0) activate(p);
    }

    /** Page p is needed now (an edit to it): active from now on, as touchPx. Serial code only. */
    void touchPage(int p) {
        if (!sparse) return;
        lastWant[p] = Math.max(lastWant[p], now());
        if (slotOf[p] < 0) activate(p);
    }

    private int now() {
        return (int) Math.floor(w.time);
    }

    private void activate(int p) {
        if (!w.r.store.resident(p)) {
            if (source == null) throw new IllegalStateException("page " + p + " isn't resident and nothing can fetch it");
            source.fetch(p);
        }
        int s;
        if (freeN > 0) s = free[--freeN];
        else {
            if (used == cap) {
                int n = cap * 2;
                pageOf = Arrays.copyOf(pageOf, n);
                Arrays.fill(pageOf, cap, n, -1);
                cap = n;
                for (int k = 0; k < users.size(); k++) users.get(k).slotsGrown(cap);
            }
            s = used++;
        }
        slotOf[p] = s;
        pageOf[s] = p;
        count++;
        entered++;
        for (int k = 0; k < users.size(); k++) users.get(k).slotAssigned(s, p);
    }

    private void deactivate(int p) {
        int s = slotOf[p];
        slotOf[p] = -1;
        pageOf[s] = -1;
        if (freeN == free.length) free = Arrays.copyOf(free, freeN * 2);
        free[freeN++] = s;
        count--;
        left++;
        if (source != null) source.release(p);
    }

    private void want(int p) {
        if (mark[p] == gen || wn >= limit) return;
        mark[p] = gen;
        if (wn == want.length) want = Arrays.copyOf(want, wn * 2);
        want[wn++] = p;
    }

    /** The page (cx, cy) and the eight round it (those on the map). */
    private void wantRing(int cx, int cy) {
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && y >= 0 && x < pw && y < ph) want(y * pw + x);
            }
    }

    private void wantRingPx(float x, float y) {
        int p = pageOfPx(x, y);
        wantRing(p % pw, p / pw);
    }

    /** Works the set out again (the start of World.secondly; sparse only). */
    void update() {
        if (!sparse) return;
        fresh = false;
        int now = now();
        gen++;
        wn = 0;
        Ents e = w.e;
        // the individuals' own chunks, then a ring round each
        for (int i = 0; i < e.top; i++) if (e.loc[i] == Ents.FREE || e.loc[i] == Ents.INSIDE) want(pageOfPx(e.x[i], e.y[i]));
        for (int i = 0; i < e.top; i++) if (e.loc[i] == Ents.FREE || e.loc[i] == Ents.INSIDE) wantRingPx(e.x[i], e.y[i]);
        // building fires
        Fires f = w.fires;
        for (int k = 0; k < f.count(); k++) {
            int b = f.burningAt(k);
            want(pageOfPx(w.r.bx(b) * Tile.SIZE, w.r.by(b) * Tile.SIZE));
        }
        // hot sectors, then the camera's
        Region r = w.r;
        float ss = r.sect * Tile.SIZE;
        Sectors sec = w.sec;
        for (int s = 0; s < sec.n; s++)
            if (sec.hot[s] > 0) wantRingPx((s % r.secW + 0.5f) * ss, (s / r.secW + 0.5f) * ss);
        if (w.camDetail) {
            int x0 = Math.max(0, (int) (w.camX0 / PX) - 1), x1 = Math.min(pw - 1, (int) (w.camX1 / PX) + 1);
            int y0 = Math.max(0, (int) (w.camY0 / PX) - 1), y1 = Math.min(ph - 1, (int) (w.camY1 / PX) + 1);
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++) want(y * pw + x);
        }
        // ground vehicles about their business in that area (out of it a vehicle doesn't pull the map along: past
        // the chunks still lingering it goes into transit)
        Vehicles v = w.veh;
        for (int k = 0; k < v.top; k++) {
            byte st = v.state[k];
            if (st == Vehicles.V_NONE || st == Vehicles.V_WRECK || v.flying(k)) continue;
            // (the city's own traffic lives inside the camera's area and never pulls the map along)
            if (v.role[k] == Vehicles.R_LIFE) continue;
            boolean busy = st == Vehicles.V_DRIVE || st == Vehicles.V_PARKING || st == Vehicles.V_PULLIN || v.crewN[k] > 0;
            if (busy && mark[pageOfPx(v.x[k], v.y[k])] == gen) wantRingPx(v.x[k], v.y[k]);
        }
        for (int k = 0; k < wn; k++) lastWant[want[k]] = now;
        // out go the chunks quiet for long enough (in page order), in come the wanted ones (in priority order)
        for (int p = 0; p < pages; p++) if (slotOf[p] >= 0 && now - lastWant[p] >= QUIET) deactivate(p);
        for (int k = 0; k < wn; k++) if (slotOf[want[k]] < 0) activate(want[k]);
    }

    // ------------------------------------------------------------------ saves (sparse only)

    void write(DataOutputStream o) throws IOException {
        o.writeInt(limit);
        o.writeBoolean(fresh);
        o.writeInt(count);
        for (int p = 0; p < pages; p++)
            if (slotOf[p] >= 0) {
                o.writeInt(p);
                o.writeInt(lastWant[p]);
            }
    }

    void read(DataInputStream in) throws IOException {
        limit = in.readInt();
        fresh = in.readBoolean();
        int n = in.readInt();
        int[] pg = new int[n];
        boolean[] keep = new boolean[pages];
        for (int k = 0; k < n; k++) {
            pg[k] = in.readInt();
            lastWant[pg[k]] = in.readInt();
            keep[pg[k]] = true;
        }
        // exactly the saved set: anything made active while the world was being put together goes
        for (int p = 0; p < pages; p++) if (slotOf[p] >= 0 && !keep[p]) deactivate(p);
        for (int k = 0; k < n; k++) if (slotOf[pg[k]] < 0) activate(pg[k]);
    }
}
