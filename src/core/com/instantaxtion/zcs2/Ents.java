package com.instantaxtion.zcs2;

import java.util.Arrays;

/**
 * Every individual person and zombie, stored as parallel arrays (one slot per entity).
 *
 * Each slot has exactly one location, and only the methods here change it:
 * NONE (free slot) -> FREE (on the map) <-> ABOARD (in a vehicle); FREE -> BODY (dead) -> FREE (risen) or NONE.
 * Nothing else writes {@link #loc}, so a person can never be on the map and in a car at once.
 */
public final class Ents {
    public static final byte NONE = 0, FREE = 1, ABOARD = 2, BODY = 3;
    /** Hiding inside a building (the survivor and their companions only, see Survival): off the street, still themselves. */
    public static final byte INSIDE = 4;
    public static final byte CIV = Content.CIV, COP = Content.COP, SOLDIER = Content.SOLDIER, ZOMBIE = Content.ZOMBIE;

    // flags
    public static final int F_INFECTED = 1, F_HELMET = 2, F_PLAYER = 4, F_RISES = 8, F_FLEEING = 16, F_EVAC = 32,
            F_AMBIENT = 64, F_PLACED = 128, F_WOUNDED = 256, F_LEADER = 512;
    /** One of the survivor's companions (Survival): never folded into the counts, thinks by the survivor's orders. */
    public static final int F_COMPANION = 1 << 20;
    /** One of a service's crew on foot (a firefighter, a paramedic): not one of the people, never folded into the counts. */
    public static final int F_CREW = 1 << 21;
    /** Waiting for the ambulance that is coming for them (they stay where they are unless the dead come). */
    public static final int F_PATIENT = 1 << 22;

    public int cap, top, live;
    public float[] x, y, vx, vy, hp, maxHp, face, timer, aimT, infT, gx, gy, dvx, dvy;
    public byte[] kind, loc, state;
    public short[] type, ammo, kills, rank;
    public int[] flags, target, squad, vehicle, uid, home, intent, intentArg;
    private int[] freeList;
    private int freeN;
    private int nextUid = 1;
    /** Sparse active set (null when every chunk is active): told wherever someone is put down on the map. */
    ActiveSet act;

    public Ents(int capacity) {
        alloc(capacity);
    }

    private void alloc(int n) {
        cap = n;
        x = grow(x, n); y = grow(y, n); vx = grow(vx, n); vy = grow(vy, n); hp = grow(hp, n); maxHp = grow(maxHp, n);
        face = grow(face, n); timer = grow(timer, n); aimT = grow(aimT, n); infT = grow(infT, n); gx = grow(gx, n);
        gy = grow(gy, n); dvx = grow(dvx, n); dvy = grow(dvy, n);
        kind = grow(kind, n); loc = grow(loc, n); state = grow(state, n);
        type = grow(type, n); ammo = grow(ammo, n); kills = grow(kills, n); rank = grow(rank, n);
        flags = grow(flags, n); target = grow(target, n); squad = grow(squad, n); vehicle = grow(vehicle, n);
        uid = grow(uid, n); home = grow(home, n); intent = grow(intent, n); intentArg = grow(intentArg, n);
        freeList = grow(freeList, n);
    }

    private static float[] grow(float[] a, int n) { return a == null ? new float[n] : Arrays.copyOf(a, n); }
    private static byte[] grow(byte[] a, int n) { return a == null ? new byte[n] : Arrays.copyOf(a, n); }
    private static short[] grow(short[] a, int n) { return a == null ? new short[n] : Arrays.copyOf(a, n); }
    private static int[] grow(int[] a, int n) { return a == null ? new int[n] : Arrays.copyOf(a, n); }

    /** A new entity on the map. */
    public int spawn(byte k, int t, float px, float py, float health) {
        int i;
        if (freeN > 0) i = freeList[--freeN];
        else {
            if (top == cap) alloc(cap * 2);
            i = top++;
        }
        kind[i] = k;
        type[i] = (short) t;
        loc[i] = FREE;
        x[i] = px;
        y[i] = py;
        vx[i] = vy[i] = dvx[i] = dvy[i] = 0;
        hp[i] = maxHp[i] = health;
        face[i] = 0;
        timer[i] = aimT[i] = infT[i] = 0;
        gx[i] = px;
        gy[i] = py;
        state[i] = 0;
        ammo[i] = kills[i] = rank[i] = 0;
        flags[i] = 0;
        target[i] = -1;
        squad[i] = -1;
        vehicle[i] = -1;
        home[i] = -1;
        intent[i] = 0;
        intentArg[i] = -1;
        uid[i] = nextUid++;
        live++;
        if (act != null) act.touchPx(px, py);
        return i;
    }

    /** Puts someone into a vehicle (the vehicle's crew list is the caller's to update, in the same call). */
    void board(int i, int v) {
        check(i, FREE);
        loc[i] = ABOARD;
        vehicle[i] = v;
        vx[i] = vy[i] = 0;
    }

    /** Takes someone out of a vehicle at a place on the map. */
    void alight(int i, float px, float py) {
        check(i, ABOARD);
        loc[i] = FREE;
        vehicle[i] = -1;
        x[i] = px;
        y[i] = py;
        if (act != null) act.touchPx(px, py);
    }

    /** Someone on the street goes into a building to hide (Survival): off the map, keeping their slot and everything else. */
    void goInside(int i) {
        check(i, FREE);
        loc[i] = INSIDE;
        vx[i] = vy[i] = dvx[i] = dvy[i] = 0;
    }

    /** Someone hiding inside comes out at a place on the map (the building's door). */
    void comeOut(int i, float px, float py) {
        check(i, INSIDE);
        loc[i] = FREE;
        x[i] = px;
        y[i] = py;
        if (act != null) act.touchPx(px, py);
    }

    /** Someone dies where they stand (or in a vehicle, which leaves them at the vehicle). */
    void die(int i, float px, float py, boolean rises) {
        if (loc[i] != FREE && loc[i] != ABOARD) throw new IllegalStateException("die from " + loc[i]);
        loc[i] = BODY;
        x[i] = px;
        y[i] = py;
        vehicle[i] = -1;
        vx[i] = vy[i] = 0;
        hp[i] = 0;
        if (rises) flags[i] |= F_RISES;
        else flags[i] &= ~F_RISES;
    }

    /** A body gets up as one of the dead. */
    void rise(int i, int ztype, float health) {
        check(i, BODY);
        loc[i] = FREE;
        kind[i] = ZOMBIE;
        type[i] = (short) ztype;
        hp[i] = maxHp[i] = health;
        flags[i] &= F_HELMET;
        squad[i] = -1;
        target[i] = -1;
        state[i] = 0;
        timer[i] = 0;
        if (act != null) act.touchPx(x[i], y[i]);
    }

    /** Frees the slot (an entity leaving the individual simulation: gone indoors, out of the region, or a body cleared). */
    void remove(int i) {
        if (loc[i] == NONE) throw new IllegalStateException("remove twice " + i);
        loc[i] = NONE;
        vehicle[i] = -1;
        squad[i] = -1;
        freeList[freeN++] = i;
        live--;
    }

    private void check(int i, byte want) {
        if (loc[i] != want) throw new IllegalStateException("entity " + i + " is " + loc[i] + ", expected " + want);
    }

    public boolean armed(int i) {
        return kind[i] == COP || kind[i] == SOLDIER;
    }

    public boolean living(int i) {
        return kind[i] != ZOMBIE;
    }

    public int nextUid() {
        return nextUid;
    }

    public void setNextUid(int v) {
        nextUid = v;
    }

    /** Rebuilds the free list from the location array (after loading a save). */
    void rebuildFree() {
        freeN = 0;
        live = 0;
        for (int i = top - 1; i >= 0; i--) {
            if (loc[i] == NONE) freeList[freeN++] = i;
            else live++;
        }
    }

    /** The free slots in the order they'll be reused (saved, so a loaded game spawns into the same slots). */
    int[] freeOrder() {
        return java.util.Arrays.copyOf(freeList, freeN);
    }

    void setFreeOrder(int[] order) {
        freeN = order.length;
        System.arraycopy(order, 0, freeList, 0, freeN);
    }

    public void ensure(int n) {
        if (n > cap) alloc(Math.max(n, cap * 2));
    }
}
