package com.instantaxtion.zcs2;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Living through it as one person, at street level (the survivor; the officer's own play takes the weapons, the
 * melee, the aim and the cars). Hiding in buildings (off the street and counted in the building's sector like
 * everyone indoors, the door barricaded so the dead must break it), scavenging by building kind, hunger, thirst,
 * fatigue and stamina, weapons carried and swapped with their own ammunition, a shove and a bat or crowbar, a manual
 * aim beside the automatic one, cars found parked or abandoned and driven with the stick on limited fuel, up to
 * three companions with simple orders, and the nearest safe place.
 *
 * Every change the player makes comes through Actions (kind SURV, recorded); the rest runs on the game's thread with
 * random numbers that are hashes of the seed, the clock and the thing concerned, and it is all saved. A module only
 * in games with someone to play (World.enableSurvival), so other games are exactly as before.
 */
public final class Survival extends Module {
    // ---- what the player can do (Actions.SURV, i[0])
    public static final int ENTER = 1, LEAVE = 2, BARRICADE = 3, SEARCH = 4, USE = 5, EQUIP = 6, MELEE = 7, AIM = 8, AIM_AT = 9,
            SHOOT = 10, CAR = 11, RECRUIT = 12, ORDER = 13, REST = 14, PICKUP = 15;

    // ---- things carried
    public static final int I_FOOD = 0, I_WATER = 1, I_MEDS = 2, I_TOOLS = 3, I_FUEL = 4, NITEMS = 5;
    public static final String[] ITEM_NAMES = {"Food", "Water", "Medkit", "Tools", "Fuel can"};

    // ---- weapons: melee first, then guns (the guns are content.ini's weapons)
    public static final int W_HANDS = 0, W_BAT = 1, W_CROWBAR = 2, W_BATON = 3, W_PISTOL = 4, W_SHOTGUN = 5, W_RIFLE = 6, NW = 7;
    public static final String[] W_NAMES = {"Bare hands", "Bat", "Crowbar", "Baton", "Pistol", "Shotgun", "Rifle"};
    static final String[] W_CONTENT = {null, null, null, null, "pistol", "shotgun", "carbine"};
    /** Melee: reach (px), damage on a body blow (the dead's hp), a blow to the head's chance, seconds between, stamina a blow. */
    static final float[] W_REACH = {20, 24, 22, 22}, W_DMG = {0, 45, 60, 38}, W_HEAD = {0, 0.32f, 0.45f, 0.25f},
            W_COOL = {0.6f, 0.8f, 0.95f, 0.7f}, W_STAMINA = {0.12f, 0.08f, 0.1f, 0.07f};

    /** Companions' orders. */
    public static final int C_FOLLOW = 0, C_STAY = 1, C_CARRY = 2;
    public static final String[] C_NAMES = {"Following", "Staying", "Carrying"};
    public static final int MAX_COMPANIONS = 3;

    /** On: a game with someone to play (set with the module, and by a save that has its part). */
    public boolean enabled;
    /** Set up for the person played (their weapons, what they carry): once, on the first second. */
    public boolean ready;
    /** Hunger, thirst and fatigue count (the survivor; the officer is fed by the service). */
    public boolean needsOn;

    // ---- the person: what they carry
    /** Weapons carried (bit W_*), the gun in hand (-1 none) and the melee weapon (W_HANDS for a shove). */
    public int owned, gun = -1, melee = W_HANDS;
    /** Rounds for each gun not in hand (the gun in hand's are its Ents.ammo). */
    public final int[] ammo = new int[NW];
    public final int[] items = new int[NITEMS];

    // ---- needs, 0 (empty) to 1 (full)
    public float stamina = 1, food = 0.8f, water = 0.8f, rest = 0.85f;
    /** Resting (inside a building only): fatigue goes, stamina comes back fast. */
    public boolean resting;
    /** Sprinting this step (the stick pushed all the way, with stamina left): for the HUD and the needs. */
    public boolean sprinting;

    // ---- hiding in a building
    /** The building the player is hiding in (-1: on the street), the door's point and tile, the tile before a barricade. */
    public int inside = -1;
    public float doorPX, doorPY;
    public int doorTile = -1;
    private byte doorWas = -1;
    /** The door is barricaded by us (its tile a barricade we put there), and the dead that got in (they find you). */
    public int deadInside;
    /** Seconds the player's noise inside still carries (searching, shooting): the dead outside notice more. */
    public float noiseT;
    /** Buildings searched (each can be searched once), and searches by sector (each one leaves the street poorer). */
    final TreeSet<Integer> searched = new TreeSet<Integer>();
    final TreeMap<Integer, Integer> searchedIn = new TreeMap<Integer, Integer>();
    /** Bodies whose gun was taken (by uid). */
    final TreeSet<Integer> looted = new TreeSet<Integer>();

    // ---- fighting
    /** Manual aim (tap a target, then Fire) instead of shooting by yourself; the target (slot and uid). */
    public boolean manual;
    public int aimEnt = -1, aimUid;
    /** A manual shot asked for: seconds it stays asked (it goes as soon as the gun is ready). */
    public float shootReq;
    /** Seconds to the next blow. */
    public float meleeT;

    // ---- a car
    /** The car the player drives (-1 none) and its uid. */
    public int car = -1, carUid;

    // ---- companions
    public final int[] comp = new int[MAX_COMPANIONS], compUid = new int[MAX_COMPANIONS], order = new int[MAX_COMPANIONS];
    public final float[] stayX = new float[MAX_COMPANIONS], stayY = new float[MAX_COMPANIONS];
    public int compN;
    /** Where the player has been, once a second (companions keep to the trail round corners). */
    final float[] trailX = new float[16], trailY = new float[16];
    int trailN;

    // ---- the way out
    /** The nearest safe place (Strategic.safePoints), its kind, and how far (-1 none), worked out each second. */
    public float safeX, safeY, safeD = -1;
    public int safeKind = -1;
    /** The settlement the player joined at the end (its name), if any. */
    public String joined;
    private boolean endSeen;

    // ---- for the HUD and the tests
    /** The last thing that happened to tell the player, and its game time. */
    public String note = "";
    public double noteAt = -1;
    public int searches, finds, meleeKills, shoves, eaten, recruited, foundInside;

    public Survival(World w) {
        super(w);
    }

    @Override
    public String id() {
        return "survival";
    }

    private void say(String s) {
        note = s;
        noteAt = w.time;
    }

    private float rnd(long a, long b) {
        return Rng.unit(w.seed ^ 0x5A17L, a, b);
    }

    // ------------------------------------------------------------------ the person and what they carry

    /** The player can play a person who uses all this (the survivor, or the officer for the weapons, aim and cars). */
    boolean person() {
        return enabled && w.playerAlive();
    }

    public boolean survivor() {
        return w.roles.role == Roles.SURVIVOR;
    }

    /** Sets the person up the first time: what their unit carries, and a little more. */
    private void setUp() {
        ready = true;
        if (w.playerEnt < 0) return;
        Ents e = w.e;
        int p = w.playerEnt;
        Content.Unit u = w.unitOf(p);
        needsOn = e.kind[p] == Ents.CIV;
        int g = u.weapon != null ? gunOf(u.weapon.id) : -1;
        if (g >= 0) {
            owned |= 1 << g;
            gun = g;
        }
        if (e.kind[p] == Ents.COP) {
            owned |= 1 << W_BATON;
            melee = W_BATON;
        } else {
            items[I_FOOD] = 1;
            items[I_WATER] = 1;
        }
        owned |= 1 << W_HANDS;
    }

    /** The gun (W_*) a content weapon is, or -1. */
    static int gunOf(String id) {
        if (id == null) return -1;
        if (id.equals("pistol")) return W_PISTOL;
        if (id.equals("shotgun")) return W_SHOTGUN;
        if (id.equals("carbine") || id.equals("rifle") || id.equals("marksman") || id.equals("mg")) return W_RIFLE;
        return -1;
    }

    public boolean owns(int wp) {
        return (owned & (1 << wp)) != 0;
    }

    /** The weapon the player's shots use (content.ini's), or null with no gun in hand. Safe in the think phase. */
    Content.Weapon gunWeapon(Content.Unit u) {
        if (!ready) return u.weapon;
        if (gun < 0) return null;
        Content.Weapon wp = w.c.weapon(W_CONTENT[gun]);
        return wp != null ? wp : u.weapon;
    }

    /** Rounds for the gun in hand (or for another, carried). */
    public int rounds(int g) {
        if (g < 0) return 0;
        if (g == gun && w.playerEnt >= 0) return w.e.ammo[w.playerEnt];
        return ammo[g];
    }

    public int carried() {
        int n = 0;
        for (int k = 0; k < NITEMS; k++) n += items[k];
        return n;
    }

    /** How much can be carried: a bag, and more for each companion carrying. */
    public int capacity() {
        int c = (int) w.rule("survCarry", 12);
        for (int k = 0; k < compN; k++) if (order[k] == C_CARRY) c += (int) w.rule("survCarryEach", 8);
        return c;
    }

    /** Adds n of an item, as far as there is room; returns how many fitted. */
    private int take(int item, int n) {
        int room = Math.max(0, capacity() - carried());
        int k = Math.min(room, n);
        items[item] += k;
        return k;
    }

    // ------------------------------------------------------------------ the actions

    /** Carries out one of the player's survival verbs (from Actions: recorded, replayed). */
    Object act(int op, float[] f, int[] i) {
        if (!enabled || w.playerEnt < 0 || !w.playerAlive() || w.ended && !w.watching) return Boolean.FALSE;
        if (!ready) setUp();
        int a = i.length > 1 ? i[1] : 0;
        switch (op) {
            case ENTER: return enter();
            case LEAVE: return leave(false);
            case BARRICADE: return barricade();
            case SEARCH: return search();
            case USE: return use(a);
            case EQUIP: return equip(a);
            case MELEE: return melee(a != 0);
            case AIM:
                manual = a != 0;
                if (!manual) aimEnt = -1;
                return Boolean.TRUE;
            case AIM_AT: return aimAt(a, i.length > 2 ? i[2] : 0);
            case SHOOT:
                if (gun < 0 || rounds(gun) <= 0) return Boolean.FALSE;
                shootReq = 1f;
                return Boolean.TRUE;
            case CAR: return car();
            case RECRUIT: return recruit();
            case ORDER: return orderCompanions(a, i.length > 2 ? i[2] : -1);
            case REST: return rest(a != 0);
            case PICKUP: return pickup();
            default: return Boolean.FALSE;
        }
    }

    /** Can the player do this now (for the HUD's buttons)? */
    public boolean can(int op) {
        if (!enabled || w.playerEnt < 0 || !w.playerAlive() || w.ended) return false;
        Ents e = w.e;
        int p = w.playerEnt;
        byte l = e.loc[p];
        boolean surv = e.kind[p] == Ents.CIV;
        switch (op) {
            case ENTER: return surv && l == Ents.FREE && buildingAt(e.x[p], e.y[p]) >= 0;
            case LEAVE: return l == Ents.INSIDE;
            case BARRICADE: return l == Ents.INSIDE && (!barricaded() || items[I_TOOLS] > 0 && reinforced() < 3);
            case SEARCH: return l == Ents.INSIDE && !searched.contains(inside);
            case MELEE: return l == Ents.FREE;
            case CAR: return l == Ents.ABOARD && car >= 0 || l == Ents.FREE && carNear(e.x[p], e.y[p]) >= 0;
            case RECRUIT: return surv && l == Ents.FREE && compN < MAX_COMPANIONS && recruitable(e.x[p], e.y[p]) >= 0;
            case PICKUP: return l == Ents.FREE && bodyNear(e.x[p], e.y[p]) >= 0;
            case REST: return surv && l == Ents.INSIDE;
            default: return true;
        }
    }

    // ------------------------------------------------------------------ buildings

    /** The building whose door is within reach of (x, y), standing, or -1. */
    public int buildingAt(float x, float y) {
        Region r = w.r;
        int best = -1;
        float bd = w.rule("survDoorReach", 30);
        bd *= bd;
        int s0 = r.sectorOfPx(x, y), sw = r.secW, sh = r.secH;
        int sx = s0 % sw, sy = s0 / sw;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                int qx = sx + dx, qy = sy + dy;
                if (qx < 0 || qy < 0 || qx >= sw || qy >= sh) continue;
                int[] list = w.secBuildings[qy * sw + qx];
                for (int b : list) {
                    float ddx = r.doorX(b) - x, ddy = r.doorY(b) - y, d = ddx * ddx + ddy * ddy;
                    if (d < bd && standing(b)) {
                        bd = d;
                        best = b;
                    }
                }
            }
        return best;
    }

    private boolean standing(int b) {
        Region r = w.r;
        int cx = r.bx(b) + r.bw(b) / 2, cy = r.by(b) + r.bh(b) / 2;
        return r.in(cx, cy) && r.building(cx, cy) == b;
    }

    public boolean barricaded() {
        if (doorTile < 0) return false;
        Region r = w.r;
        return r.t(doorTile % r.W, doorTile / r.W) == Tile.BARRICADE;
    }

    /** The door barricade's strength left, 0..1+ (1 = a plain barricade). */
    public float doorStrength() {
        if (!barricaded()) return 0;
        float hp = w.undead.tileHp(Tile.BARRICADE);
        return Math.max(0, (hp - w.undead.damageAt(doorTile)) / hp);
    }

    private int reinforced() {
        return (int) (doorStrength() - 1 + 0.01f);
    }

    private Boolean enter() {
        Ents e = w.e;
        int p = w.playerEnt;
        if (!can(ENTER)) return Boolean.FALSE;
        int b = buildingAt(e.x[p], e.y[p]);
        Region r = w.r;
        inside = b;
        doorPX = r.doorX(b);
        doorPY = r.doorY(b);
        int tx = (int) (doorPX / Tile.SIZE), ty = (int) (doorPY / Tile.SIZE);
        doorTile = ty * r.W + tx;
        doorWas = -1;
        deadInside = 0;
        resting = false;
        int s = r.sectorOfPx(doorPX, doorPY);
        hide(p, s);
        // those with you come in too (the ones told to stay keep their place)
        for (int k = 0; k < compN; k++) {
            int c = comp[k];
            if (order[k] == C_STAY || e.loc[c] != Ents.FREE) continue;
            float dx = e.x[c] - e.x[p], dy = e.y[c] - e.y[p];
            if (dx * dx + dy * dy < 90 * 90) hide(c, s);
        }
        // the dead may be in there already (as many as the sector's share of the dead indoors)
        Sectors sec = w.sec;
        float share = sec.zIn[s] / (float) (sec.popIn[s] + sec.zIn[s] + 1);
        if (rnd(w.tick, b * 7L + 1) < Math.min(0.6f, share * 1.5f)) {
            deadInside = 1;
            say("Something moves in the dark in here.");
        } else say("Inside. " + kindName(r.bkind(b)) + (searched.contains(b) ? ", searched already." : ": search it, barricade the door."));
        return Boolean.TRUE;
    }

    /** Someone goes inside: off the street and counted in the sector as everyone indoors is. */
    private void hide(int i, int s) {
        Ents e = w.e;
        Sectors sec = w.sec;
        if ((e.flags[i] & Ents.F_INFECTED) != 0) sec.inf[sec.touch(s)] += 1;
        else sec.popIn[sec.touch(s)]++;
        e.goInside(i);
    }

    /** Someone comes out of the count again at the door. */
    private void unhide(int i, int s, float x, float y) {
        Ents e = w.e;
        Sectors sec = w.sec;
        if ((e.flags[i] & Ents.F_INFECTED) != 0 && sec.inf[s] >= 1) sec.inf[sec.touch(s)] -= 1;
        else if (sec.popIn[s] > 0) sec.popIn[sec.touch(s)]--;
        else if (sec.inf[s] >= 1) {
            // the count they were part of was bitten while they hid: they come out bitten
            sec.inf[sec.touch(s)] -= 1;
            if ((e.flags[i] & Ents.F_INFECTED) == 0) infect(i);
        }
        e.comeOut(i, x, y);
    }

    private void infect(int i) {
        Ents e = w.e;
        e.flags[i] |= Ents.F_INFECTED;
        e.infT[i] = 25 + rnd(e.uid[i], 77) * 45;
        w.stats.infectedTotal++;
    }

    /** Out of the building (forced: the dead found you, you're thrown out into the street). */
    private Boolean leave(boolean forced) {
        Ents e = w.e;
        int p = w.playerEnt;
        if (e.loc[p] != Ents.INSIDE) return Boolean.FALSE;
        Region r = w.r;
        // our barricade comes down to get out (a broken one is rubble already)
        if (barricaded() && doorWas >= 0) {
            int tx = doorTile % r.W, ty = doorTile / r.W;
            r.set(tx, ty, doorWas);
            w.undead.damage.remove(doorTile);
            pathsChanged(tx, ty);
        }
        int s = r.sectorOfPx(doorPX, doorPY);
        float ox = doorPX, oy = doorPY;
        if (!r.walkablePx(ox, oy)) {
            float[] q = r.openGroundNear(ox, oy, false);
            ox = q[0];
            oy = q[1];
        }
        unhide(p, s, ox, oy);
        for (int k = 0; k < compN; k++) {
            int c = comp[k];
            if (e.loc[c] != Ents.INSIDE) continue;
            unhide(c, s, ox + (k + 1) * 5, oy + (k % 2 == 0 ? 5 : -5));
            if (!r.walkablePx(e.x[c], e.y[c])) {
                e.x[c] = ox;
                e.y[c] = oy;
            }
        }
        inside = -1;
        doorTile = -1;
        doorWas = -1;
        deadInside = 0;
        resting = false;
        if (!forced) say("Back out on the street.");
        return Boolean.TRUE;
    }

    private void pathsChanged(int tx, int ty) {
        Region r = w.r;
        r.rebuildNav(tx - 1, ty - 1, tx + 1, ty + 1);
        if (r.nation != null) w.sec.connectivity(tx - 1, ty - 1, tx + 1, ty + 1);
        else w.sec.connectivity();
        r.closeBarricadedRoads(tx, ty, tx, ty);
    }

    /** Barricades the door (furniture; tools make it stronger, and reinforce it again). */
    private Boolean barricade() {
        if (!can(BARRICADE)) return Boolean.FALSE;
        Region r = w.r;
        int tx = doorTile % r.W, ty = doorTile / r.W;
        float extra = w.rule("survBarricadeTools", 150);
        if (!barricaded()) {
            int t = r.t(tx, ty);
            if (!Tile.walkable(t) || Tile.water(t) || t == Tile.BRIDGE) return Boolean.FALSE;
            w.act.touchPx(doorPX, doorPY);
            Tools.stepOff(w, tx, ty);
            doorWas = (byte) t;
            r.set(tx, ty, Tile.BARRICADE);
            w.undead.damage.remove(doorTile);
            pathsChanged(tx, ty);
            if (items[I_TOOLS] > 0) {
                items[I_TOOLS]--;
                w.undead.damage.put(doorTile, -extra);
                say("The door is barricaded, nailed shut with your tools.");
            } else say("The door is barricaded with what's in here.");
        } else {
            items[I_TOOLS]--;
            w.undead.damage.put(doorTile, w.undead.damageAt(doorTile) - extra);
            say("The barricade is stronger.");
        }
        noiseT = Math.max(noiseT, 10);
        return Boolean.TRUE;
    }

    public static String kindName(int k) {
        switch (k) {
            case Region.B_HOUSE: case Region.B_FARMHOUSE: return "A house";
            case Region.B_APARTMENT: case Region.B_TOWER: return "Flats";
            case Region.B_SHOP: return "A shop";
            case Region.B_MALL: return "A shopping centre";
            case Region.B_FOODW: return "A food warehouse";
            case Region.B_POLICE: return "A police station";
            case Region.B_HOSPITAL: return "A hospital";
            case Region.B_FUEL: return "A filling station";
            case Region.B_WAREHOUSE: case Region.B_DEPOT: return "A warehouse";
            case Region.B_BARN: return "A barn";
            case Region.B_ARMORY: case Region.B_BARRACKS: return "Army barracks";
            case Region.B_SCHOOL: return "A school";
            case Region.B_CHURCH: return "A church";
            case Region.B_OFFICE: return "Offices";
            default: return "A building";
        }
    }

    /**
     * What a building kind holds on average: food, water, medkits, tools, fuel cans, then pistol rounds, shotgun
     * shells and rifle rounds, then the chance of a bat, a crowbar, a shotgun and a rifle.
     */
    static float[] loot(int kind) {
        switch (kind) {
            case Region.B_HOUSE: case Region.B_FARMHOUSE: case Region.B_APARTMENT: case Region.B_TOWER:
                return new float[]{1.3f, 1.1f, 0.35f, 0.45f, 0.1f, 4, 1, 0, 0.14f, 0.06f, 0.03f, 0};
            case Region.B_SHOP: case Region.B_MALL: case Region.B_FOODW:
                return new float[]{2.8f, 2.6f, 0.35f, 0.7f, 0.15f, 0, 0, 0, 0.06f, 0.12f, 0, 0};
            case Region.B_POLICE:
                return new float[]{0.4f, 0.6f, 0.7f, 0.4f, 0, 34, 10, 14, 0, 0.05f, 0.4f, 0.18f};
            case Region.B_HOSPITAL:
                return new float[]{0.6f, 1.4f, 3.2f, 0.2f, 0, 0, 0, 0, 0, 0, 0, 0};
            case Region.B_FUEL:
                return new float[]{1.2f, 1.4f, 0.2f, 0.6f, 1.6f, 0, 0, 0, 0.04f, 0.15f, 0, 0};
            case Region.B_WAREHOUSE: case Region.B_DEPOT: case Region.B_BARN:
                return new float[]{0.6f, 0.4f, 0.1f, 1.6f, 0.5f, 0, 0, 0, 0.1f, 0.3f, 0, 0};
            case Region.B_ARMORY: case Region.B_BARRACKS:
                return new float[]{0.8f, 0.8f, 0.6f, 0.5f, 0.2f, 12, 8, 40, 0, 0.05f, 0.25f, 0.45f};
            default:
                return new float[]{0.6f, 0.8f, 0.2f, 0.4f, 0.05f, 2, 0, 0, 0.04f, 0.05f, 0, 0};
        }
    }

    /** How much is left for a newcomer in a sector already searched n times (people before you took the rest). */
    public float yieldIn(int sector) {
        Integer n = searchedIn.get(sector);
        return 1f / (1 + (n == null ? 0 : n) * w.rule("survDeplete", 0.25f));
    }

    private Boolean search() {
        if (!can(SEARCH)) return Boolean.FALSE;
        Region r = w.r;
        int b = inside, s = r.sectorOfPx(doorPX, doorPY);
        float[] t = loot(r.bkind(b));
        // bigger buildings hold more; somebody else has been through a looted street
        float size = Math.min(2.5f, 0.7f + r.bw(b) * r.bh(b) / 60f);
        float mul = size * yieldIn(s) * (w.stats.lootings > 0 ? 0.85f : 1);
        StringBuilder sb = new StringBuilder();
        int got = 0, lost = 0;
        for (int k = 0; k < NITEMS; k++) {
            int n = (int) (t[k] * mul + rnd(b, k + 11L * searches));
            if (n <= 0) continue;
            int in = take(k, n);
            lost += n - in;
            got += in;
            if (in > 0) sb.append(sb.length() > 0 ? ", " : "").append(in).append(' ').append(ITEM_NAMES[k].toLowerCase()).append(in > 1 && k != I_FOOD && k != I_WATER && k != I_TOOLS ? "s" : "");
        }
        int[] guns = {W_PISTOL, W_SHOTGUN, W_RIFLE};
        for (int k = 0; k < 3; k++) {
            int n = (int) (t[5 + k] * mul * (0.5f + rnd(b, 40 + k)));
            if (n <= 0) continue;
            ammo[guns[k]] += n;
            if (guns[k] == gun && w.playerEnt >= 0) {
                w.e.ammo[w.playerEnt] += ammo[gun];
                ammo[gun] = 0;
            }
            got++;
            sb.append(sb.length() > 0 ? ", " : "").append(n).append(guns[k] == W_SHOTGUN ? " shells" : guns[k] == W_PISTOL ? " pistol rounds" : " rifle rounds");
        }
        int[] wps = {W_BAT, W_CROWBAR, W_SHOTGUN, W_RIFLE};
        for (int k = 0; k < 4; k++) {
            if (owns(wps[k]) || rnd(b, 60 + k) >= t[8 + k] * Math.min(1.5f, mul)) continue;
            owned |= 1 << wps[k];
            got++;
            sb.append(sb.length() > 0 ? ", " : "").append("a ").append(W_NAMES[wps[k]].toLowerCase());
            if (wps[k] < W_PISTOL && melee == W_HANDS) melee = wps[k];
            if (wps[k] >= W_PISTOL && gun < 0) equip(wps[k]);
        }
        searched.add(b);
        Integer n = searchedIn.get(s);
        searchedIn.put(s, n == null ? 1 : n + 1);
        searches++;
        finds += got;
        noiseT = Math.max(noiseT, 20);
        say(got == 0 ? "Nothing here: someone got here first." : "Found " + sb + (lost > 0 ? " (no room for " + lost + " more)" : "") + ".");
        return got > 0;
    }

    /** One second hidden: the dead at the door break in or beat on the barricade, and the dead inside find you. */
    private void hidden(float dt) {
        Ents e = w.e;
        int p = w.playerEnt;
        Region r = w.r;
        int s = r.sectorOfPx(doorPX, doorPY);
        if (noiseT > 0) noiseT -= dt;
        // the dead close to the door: some notice someone inside (more when you've made a noise)
        int n = nearDead(doorPX, doorPY, w.rule("survDoorDead", 30), near);
        float notice = w.rule("survNotice", 0.25f) * (noiseT > 0 ? 2.5f : 1) * (resting ? 0.6f : 1);
        boolean bar = barricaded();
        for (int k = 0; k < n; k++) {
            int z = near[k];
            if (rnd(w.tick, e.uid[z]) >= notice) continue;
            if (bar) {
                if (w.ztypeOf(z).smashPower > 0) w.undead.smash(z, doorTile);
                bar = barricaded();
                if (!bar) say("They've broken the barricade!");
            } else if (w.ztypeOf(z).breakIn) {
                w.sec.breakIn(z);
                deadInside++;
            }
        }
        // the dead counted indoors here, and those that got into this building, come across you
        Sectors sec = w.sec;
        float pz = Math.min(0.05f, 0.004f * sec.zIn[s] / (float) (sec.popIn[s] + 1)) * (bar ? 0.3f : 1);
        float pin = 1 - (float) Math.pow(0.72, deadInside);
        float risk = 1 - (1 - pz) * (1 - pin);
        if (risk > 0 && rnd(w.tick, 991) < risk * dt) {
            foundInside++;
            Content.ZType zt = w.c.ztype("walker");
            float bite = zt != null ? zt.bite : 25;
            float dmg = bite * (0.8f + 0.4f * rnd(w.tick, 992));
            e.hp[p] -= dmg;
            Content.Unit u = w.unitOf(p);
            if ((e.flags[p] & Ents.F_INFECTED) == 0 && rnd(w.tick, 993) < (zt != null ? zt.infect : 0.5f) * u.infect * w.rules.infection()) infect(p);
            w.fx.sound("hurt", doorPX, doorPY);
            leave(true);
            say(deadInside > 0 ? "The dead found you inside! You fought your way out." : "One of the dead was in there with you! You got out.");
            if (e.hp[p] <= 0) w.combat.kill(p, Combat.CAUSE_BITE, -1);
        }
    }

    private final int[] near = new int[24];

    /** The dead on the street within rad of (x, y), into out (at most its length); returns how many. */
    private int nearDead(float x, float y, float rad, int[] out) {
        Ents e = w.e;
        Spatial sp = w.sp;
        int n = 0;
        int cx0 = Math.max(0, (int) ((x - rad) / Spatial.CELL)), cx1 = Math.min(sp.cw - 1, (int) ((x + rad) / Spatial.CELL));
        int cy0 = Math.max(0, (int) ((y - rad) / Spatial.CELL)), cy1 = Math.min(sp.ch - 1, (int) ((y + rad) / Spatial.CELL));
        for (int cy = cy0; cy <= cy1; cy++)
            for (int cx = cx0; cx <= cx1; cx++) {
                int c = sp.cell(cx, cy);
                for (int j = sp.zStart[c]; j < sp.zEnd[c] && n < out.length; j++) {
                    int o = sp.zList[j];
                    if (e.loc[o] != Ents.FREE || e.kind[o] != Ents.ZOMBIE) continue;
                    float dx = e.x[o] - x, dy = e.y[o] - y;
                    if (dx * dx + dy * dy <= rad * rad) out[n++] = o;
                }
            }
        return n;
    }

    // ------------------------------------------------------------------ needs

    private Boolean use(int item) {
        Ents e = w.e;
        int p = w.playerEnt;
        if (item < 0 || item >= NITEMS || items[item] <= 0) return Boolean.FALSE;
        switch (item) {
            case I_FOOD:
                if (food > 0.95f) return Boolean.FALSE;
                food = Math.min(1, food + w.rule("survFood", 0.45f));
                eaten++;
                say("You eat.");
                break;
            case I_WATER:
                if (water > 0.95f) return Boolean.FALSE;
                water = Math.min(1, water + w.rule("survWater", 0.5f));
                say("You drink.");
                break;
            case I_MEDS:
                if (e.hp[p] >= e.maxHp[p] - 0.5f) return Boolean.FALSE;
                e.hp[p] = Math.min(e.maxHp[p], e.hp[p] + w.rule("survMedkit", 35));
                e.flags[p] &= ~Ents.F_WOUNDED;
                say("You patch yourself up." + ((e.flags[p] & Ents.F_INFECTED) != 0 ? " The bite won't heal." : ""));
                break;
            case I_FUEL: {
                int v = e.loc[p] == Ents.ABOARD ? e.vehicle[p] : carNear(e.x[p], e.y[p]);
                if (v < 0) return Boolean.FALSE;
                if (w.veh.fuel[v] > 0.95f) return Boolean.FALSE;
                w.veh.fuel[v] = Math.min(1, w.veh.fuel[v] + w.rule("survFuelCan", 0.5f));
                say("You pour in the fuel.");
                break;
            }
            default: return Boolean.FALSE;
        }
        items[item]--;
        return Boolean.TRUE;
    }

    private Boolean rest(boolean on) {
        if (on && !can(REST)) return Boolean.FALSE;
        resting = on;
        say(on ? "You rest. Skip ahead while it's quiet." : "You get up.");
        return Boolean.TRUE;
    }

    /** The needs, once a second. */
    private void needs(float dt) {
        Ents e = w.e;
        int p = w.playerEnt;
        if (!needsOn) return;
        float hungerDays = w.rule("survHungerDays", 2f), thirstDays = w.rule("survThirstDays", 1f), tiredDays = w.rule("survTiredDays", 1.5f);
        float exert = sprinting ? 1.6f : 1;
        food = Math.max(0, food - dt * exert / (hungerDays * 1440f));
        water = Math.max(0, water - dt * exert * (w.weather.temperature() > 25 ? 1.3f : 1) / (thirstDays * 1440f));
        boolean inside = e.loc[p] == Ents.INSIDE;
        if (resting && inside) rest = Math.min(1, rest + dt / (w.rule("survRestHours", 5f) * 60f));
        else rest = Math.max(0, rest - dt * exert / (tiredDays * 1440f));
        if (rest >= 1 && resting) {
            resting = false;
            say("You're rested.");
        }
        // starving, parched: it wears you down; fed, watered and rested, small hurts heal
        if (food <= 0 || water <= 0) e.hp[p] -= dt * w.rule("survStarve", 0.03f) * ((food <= 0 ? 1 : 0) + (water <= 0 ? 1 : 0));
        else if (food > 0.5f && water > 0.5f && rest > 0.3f && (e.flags[p] & Ents.F_INFECTED) == 0) e.hp[p] = Math.min(e.maxHp[p], e.hp[p] + dt * 0.01f);
        if (e.hp[p] <= 0 && e.loc[p] == Ents.FREE) w.combat.kill(p, Combat.CAUSE_PLAYER, -1);
        else if (e.hp[p] <= 0) e.hp[p] = 0.5f;
    }

    /** How the needs tell on a fight (aim and blows): tired and hungry, less sure. */
    public float fitness() {
        if (!needsOn) return 1;
        float f = 1;
        if (rest < 0.2f) f *= 0.8f;
        if (food < 0.1f || water < 0.1f) f *= 0.85f;
        return f;
    }

    // ------------------------------------------------------------------ weapons and fighting

    private Boolean equip(int wp) {
        if (wp < 0 || wp >= NW || !owns(wp)) return Boolean.FALSE;
        if (wp < W_PISTOL) {
            melee = wp;
            say(W_NAMES[wp] + " in hand.");
            return Boolean.TRUE;
        }
        Ents e = w.e;
        int p = w.playerEnt;
        if (gun >= 0) ammo[gun] = e.ammo[p];
        gun = wp;
        e.ammo[p] = (short) Math.min(Short.MAX_VALUE, ammo[wp]);
        ammo[wp] = 0;
        e.aimT[p] = Math.max(e.aimT[p], 0.8f);
        say(W_NAMES[wp] + " ready, " + e.ammo[p] + " rounds.");
        return Boolean.TRUE;
    }

    /** Takes the gun and rounds off a fallen officer or soldier close by. */
    private Boolean pickup() {
        Ents e = w.e;
        int p = w.playerEnt;
        int b = bodyNear(e.x[p], e.y[p]);
        if (b < 0) return Boolean.FALSE;
        Content.Unit u = w.unitOf(b);
        int g = gunOf(u.weapon != null ? u.weapon.id : null);
        if (g < 0) g = W_PISTOL;
        int rounds = Math.max(e.ammo[b], (int) (u.weapon != null ? u.weapon.ammo * 0.3f : 15));
        looted.add(e.uid[b]);
        boolean had = owns(g);
        owned |= 1 << g;
        if (g == gun) e.ammo[p] = (short) Math.min(Short.MAX_VALUE, e.ammo[p] + rounds);
        else ammo[g] += rounds;
        if (e.kind[b] == Ents.COP && !owns(W_BATON)) owned |= 1 << W_BATON;
        if (gun < 0) equip(g);
        say((had ? "Took " : "Took a " + W_NAMES[g].toLowerCase() + " and ") + rounds + " rounds from " + w.nameOf(b) + ".");
        return Boolean.TRUE;
    }

    /** A fallen officer's or soldier's body within reach, its gun still on it, or -1. */
    public int bodyNear(float x, float y) {
        Ents e = w.e;
        int best = -1;
        float bd = 26 * 26;
        for (int i = 0; i < e.top; i++) {
            if (e.loc[i] != Ents.BODY || !e.armed(i) || looted.contains(e.uid[i])) continue;
            float dx = e.x[i] - x, dy = e.y[i] - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    /** A shove (everything close pushed back and off balance) or a blow with the melee weapon. */
    private Boolean melee(boolean swing) {
        Ents e = w.e;
        int p = w.playerEnt;
        if (e.loc[p] != Ents.FREE || meleeT > 0) return Boolean.FALSE;
        int wp = swing ? melee : W_HANDS;
        if (stamina < W_STAMINA[wp] * 0.5f) {
            say("Too out of breath.");
            return Boolean.FALSE;
        }
        stamina = Math.max(0, stamina - W_STAMINA[wp]);
        meleeT = W_COOL[wp];
        float x = e.x[p], y = e.y[p];
        int n = nearDead(x, y, W_REACH[wp], near);
        if (wp == W_HANDS) {
            for (int k = 0; k < n; k++) {
                int z = near[k];
                if (w.ztypeOf(z).radius > 7) continue; // the big ones don't budge
                knock(z, x, y, 16);
                e.aimT[z] = Math.max(e.aimT[z], 1.1f);
                if (w.undead.has(z)) w.undead.setPhase(z, Undead.P_NONE);
            }
            shoves++;
            return n > 0;
        }
        // the closest one takes the blow
        int t = -1;
        float bd = Float.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            float dx = e.x[near[k]] - x, dy = e.y[near[k]] - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                t = near[k];
            }
        }
        w.fx.noise(x, y, 10, 2);
        if (t < 0) return Boolean.FALSE;
        e.face[p] = (float) Math.atan2(e.y[t] - y, e.x[t] - x);
        float head = W_HEAD[wp] * (0.6f + 0.4f * stamina) * fitness();
        if (rnd(w.tick, e.uid[t] * 3L) < head) {
            if ((e.flags[t] & Ents.F_HELMET) != 0) {
                e.flags[t] &= ~Ents.F_HELMET;
                w.fx.add(Effects.SPARK, e.x[t], e.y[t], 0, 0, 0.15f);
            } else {
                w.combat.killZombie(t, p, e.kind[p], Combat.CAUSE_PLAYER);
                meleeKills++;
                return Boolean.TRUE;
            }
        }
        e.hp[t] -= W_DMG[wp] * (0.8f + 0.4f * rnd(w.tick, e.uid[t] * 3L + 1));
        knock(t, x, y, 7);
        e.aimT[t] = Math.max(e.aimT[t], 0.6f);
        w.fx.blood(e.x[t], e.y[t], 1);
        if (e.hp[t] <= 0) {
            w.combat.killZombie(t, p, e.kind[p], Combat.CAUSE_PLAYER);
            meleeKills++;
        }
        return Boolean.TRUE;
    }

    /** Pushes z away from (x, y) by up to d px, onto open ground. */
    private void knock(int z, float x, float y, float d) {
        Ents e = w.e;
        float dx = e.x[z] - x, dy = e.y[z] - y, l = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
        for (float k = d; k >= 3; k -= 4) {
            float nx = e.x[z] + dx / l * k, ny = e.y[z] + dy / l * k;
            if (nx > 1 && ny > 1 && nx < w.r.px() - 1 && ny < w.r.px() - 1 && w.r.walkablePx(nx, ny)) {
                e.x[z] = nx;
                e.y[z] = ny;
                break;
            }
        }
        e.vx[z] = e.vy[z] = 0;
    }

    private Boolean aimAt(int ent, int uid) {
        Ents e = w.e;
        if (ent < 0 || ent >= e.top || e.uid[ent] != uid || e.loc[ent] != Ents.FREE || e.kind[ent] != Ents.ZOMBIE) {
            aimEnt = -1;
            return Boolean.FALSE;
        }
        aimEnt = ent;
        aimUid = uid;
        manual = true;
        return Boolean.TRUE;
    }

    /** The manual target, if it is still one of the dead on the street, or -1. */
    public int target() {
        Ents e = w.e;
        int t = aimEnt;
        if (t < 0 || t >= e.top || e.uid[t] != aimUid || e.loc[t] != Ents.FREE || e.kind[t] != Ents.ZOMBIE) return -1;
        return t;
    }

    /**
     * The player's person this step (the think phase: writes only their own slot): the stick moves them, sprinting
     * while there's stamina, and they shoot, by themselves at the nearest of the dead or at the target asked for.
     */
    void think(int i, Parallel.Scratch s) {
        Ents e = w.e;
        Content.Unit u = w.unitOf(i);
        if (e.aimT[i] > 0) e.aimT[i] -= World.DT;
        float jx = w.strat.playerJoyX, jy = w.strat.playerJoyY;
        float len = (float) Math.sqrt(jx * jx + jy * jy);
        boolean sprint = len > 0.6f && (!ready || stamina > 0.02f);
        float sp = sprint ? u.run : u.walk * 1.2f;
        if (ready && needsOn && rest < 0.15f) sp *= 0.85f;
        if (len > 1) {
            jx /= len;
            jy /= len;
        }
        e.dvx[i] = jx * sp;
        e.dvy[i] = jy * sp;
        Content.Weapon wp = gunWeapon(u);
        if (wp == null || e.ammo[i] <= 0 || e.aimT[i] > 0) return;
        if (manual) {
            int t = target();
            if (t < 0 || shootReq <= 0) return;
            float dx = e.x[t] - e.x[i], dy = e.y[t] - e.y[i];
            if (dx * dx + dy * dy > wp.range * wp.range || !w.r.lineOfSight(e.x[i], e.y[i], e.x[t], e.y[t])) return;
            e.intent[i] = Combat.I_SHOOT;
            e.intentArg[i] = t;
            return;
        }
        int z = w.sp.nearestZombie(e, e.x[i], e.y[i], wp.range);
        if (z >= 0 && w.strat.playerFire && w.r.lineOfSight(e.x[i], e.y[i], e.x[z], e.y[z])) {
            e.intent[i] = Combat.I_SHOOT;
            e.intentArg[i] = z;
        }
    }

    /** A shot went off (Combat): heard inside, and a manual shot is spent. */
    void fired() {
        shootReq = 0;
        if (inside >= 0) noiseT = Math.max(noiseT, 15);
    }

    // ------------------------------------------------------------------ cars

    /** An empty car within reach to get into (parked, abandoned or left), or -1. */
    public int carNear(float x, float y) {
        Vehicles vh = w.veh;
        int best = -1;
        float bd = w.rule("survCarReach", 36);
        bd *= bd;
        for (int v = 0; v < vh.top; v++) {
            if (!vh.usable(v) || vh.flying(v) || vh.crewN[v] > 0) continue;
            Content.VType t = vh.type(v);
            if (t.flies || t.seats < 1 || vh.state[v] == Vehicles.V_DRIVE) continue;
            // a squad's vehicle is theirs (the officer may take their own patrol's)
            if (vh.role[v] == Vehicles.R_SQUAD && vh.squad[v] >= 0 && !(w.playerEnt >= 0 && w.e.squad[w.playerEnt] == vh.squad[v])) continue;
            if (vh.role[v] != Vehicles.R_SQUAD && vh.role[v] != Vehicles.R_LIFE && vh.role[v] != Vehicles.R_TRANSPORT) continue;
            float dx = vh.x[v] - x, dy = vh.y[v] - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = v;
            }
        }
        return best;
    }

    private Boolean car() {
        Ents e = w.e;
        int p = w.playerEnt;
        Vehicles vh = w.veh;
        if (e.loc[p] == Ents.ABOARD) {
            int v = e.vehicle[p];
            w.orders.drive(-1);
            if (vh.usable(v)) {
                vh.state[v] = Vehicles.V_PARKED;
                vh.spd[v] = 0;
                vh.alightAll(v);
            }
            car = -1;
            say("Out of the car.");
            return Boolean.TRUE;
        }
        if (e.loc[p] != Ents.FREE) return Boolean.FALSE;
        int v = carNear(e.x[p], e.y[p]);
        if (v < 0) return Boolean.FALSE;
        if (vh.role[v] == Vehicles.R_LIFE) {
            // one of the city's own: whoever left it, it's yours now, with what's in the tank
            vh.role[v] = Vehicles.R_TRANSPORT;
            vh.home[v] = -1;
            vh.fuel[v] = 0.12f + 0.6f * rnd(vh.uid[v], 5);
        }
        if (!vh.board(v, p)) return Boolean.FALSE;
        for (int k = 0; k < compN; k++) {
            int c = comp[k];
            if (order[k] == C_STAY || e.loc[c] != Ents.FREE) continue;
            float dx = e.x[c] - e.x[p], dy = e.y[c] - e.y[p];
            if (dx * dx + dy * dy < 70 * 70) vh.board(v, c);
        }
        w.orders.drive(v);
        car = v;
        carUid = vh.uid[v];
        say(vh.type(v).name + ": " + Math.round(vh.fuel[v] * 100) + "% fuel. Drive with the stick.");
        return Boolean.TRUE;
    }

    /** Each step at the wheel: fuel burns with the distance. */
    private void driving() {
        Ents e = w.e;
        int p = w.playerEnt;
        Vehicles vh = w.veh;
        if (car < 0) return;
        if (car >= vh.top || vh.uid[car] != carUid || !vh.usable(car) || e.loc[p] != Ents.ABOARD || e.vehicle[p] != car) {
            // wrecked, or dragged out of it by the dead
            if (w.orders.driven == car) w.orders.drive(-1);
            if (e.loc[p] == Ents.FREE) say("You're out of the car!");
            car = -1;
            return;
        }
        float km = w.rule("survFuelKm", 40);
        vh.fuel[car] = Math.max(0, vh.fuel[car] - Math.abs(vh.spd[car]) * World.DT / (km * 4000f));
    }

    // ------------------------------------------------------------------ companions

    /** Someone close by who would come with you, or -1. */
    public int recruitable(float x, float y) {
        Ents e = w.e;
        int best = -1;
        float bd = 50 * 50;
        for (int i = 0; i < e.top; i++) {
            if (e.loc[i] != Ents.FREE || e.kind[i] != Ents.CIV || i == w.playerEnt || e.squad[i] >= 0 || (e.flags[i] & Ents.F_COMPANION) != 0) continue;
            if (!w.stats.resident(i)) continue;
            float dx = e.x[i] - x, dy = e.y[i] - y, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private Boolean recruit() {
        Ents e = w.e;
        int p = w.playerEnt;
        if (!can(RECRUIT)) return Boolean.FALSE;
        int i = recruitable(e.x[p], e.y[p]);
        comp[compN] = i;
        compUid[compN] = e.uid[i];
        order[compN] = C_FOLLOW;
        compN++;
        e.flags[i] |= Ents.F_COMPANION;
        e.flags[i] &= ~(Ents.F_FLEEING | Ents.F_EVAC);
        recruited++;
        say(w.nameOf(i) + " comes with you.");
        return Boolean.TRUE;
    }

    /** Orders one companion (k) or all (k = -1). */
    private Boolean orderCompanions(int o, int k) {
        if (o < 0 || o > C_CARRY || compN == 0) return Boolean.FALSE;
        Ents e = w.e;
        for (int j = 0; j < compN; j++) {
            if (k >= 0 && j != k) continue;
            order[j] = o;
            stayX[j] = e.x[comp[j]];
            stayY[j] = e.y[comp[j]];
        }
        say(k >= 0 ? w.nameOf(comp[k]) + ": " + C_NAMES[o].toLowerCase() : (compN == 1 ? w.nameOf(comp[0]) + ": " : "Everyone: ") + C_NAMES[o].toLowerCase());
        return Boolean.TRUE;
    }

    /** The companions still with you (the bitten turn, the dead fall; they go from the list). */
    private void companions() {
        Ents e = w.e;
        for (int k = compN - 1; k >= 0; k--) {
            int c = comp[k];
            boolean ok = c < e.top && e.uid[c] == compUid[k] && e.kind[c] == Ents.CIV
                    && (e.loc[c] == Ents.FREE || e.loc[c] == Ents.ABOARD || e.loc[c] == Ents.INSIDE);
            if (ok) continue;
            if (c < e.top && e.uid[c] == compUid[k]) {
                e.flags[c] &= ~Ents.F_COMPANION;
                say(e.kind[c] == Ents.ZOMBIE ? w.nameOf(c) + " has turned." : Names.person(w.seed, compUid[k]) + " didn't make it.");
            }
            drop(k);
        }
    }

    private void drop(int k) {
        for (int j = k; j < compN - 1; j++) {
            comp[j] = comp[j + 1];
            compUid[j] = compUid[j + 1];
            order[j] = order[j + 1];
            stayX[j] = stayX[j + 1];
            stayY[j] = stayY[j + 1];
        }
        compN--;
    }

    public int companionSlot(int i) {
        for (int k = 0; k < compN; k++) if (comp[k] == i) return k;
        return -1;
    }

    /** A companion this step (the think phase: their own slot only): keep with you, hold their place, keep off the dead. */
    void companionThink(int i, Parallel.Scratch s) {
        Ents e = w.e;
        if (e.aimT[i] > 0) e.aimT[i] -= World.DT;
        int k = companionSlot(i);
        Content.Unit u = w.unitOf(i);
        float x = e.x[i], y = e.y[i];
        float tx, ty;
        int p = w.playerEnt;
        if (k < 0 || p < 0 || !w.playerAlive()) {
            e.dvx[i] = e.dvy[i] = 0;
            return;
        }
        if (order[k] == C_STAY) {
            tx = stayX[k];
            ty = stayY[k];
        } else {
            tx = w.playerX();
            ty = w.playerY();
            // round a corner: the latest point of your trail they can walk straight to
            if (!w.nav.straightWalkable(x, y, tx, ty))
                for (int j = trailN - 1; j >= 0; j--)
                    if (w.nav.straightWalkable(x, y, trailX[j], trailY[j])) {
                        tx = trailX[j];
                        ty = trailY[j];
                        break;
                    }
        }
        float dx = tx - x, dy = ty - y, d = (float) Math.sqrt(dx * dx + dy * dy) + 0.01f;
        float keep = order[k] == C_STAY ? 6 : 22 + k * 6;
        float sp = d > 90 ? u.run : d > keep ? u.walk * 1.2f : 0;
        float vx = d > keep ? dx / d * sp : 0, vy = d > keep ? dy / d * sp : 0;
        // one of the dead too close: away from it
        int z = w.sp.nearestZombie(e, x, y, 26);
        if (z >= 0) {
            float zx = x - e.x[z], zy = y - e.y[z], zd = (float) Math.sqrt(zx * zx + zy * zy) + 0.01f;
            vx = vx * 0.4f + zx / zd * u.run * 0.8f;
            vy = vy * 0.4f + zy / zd * u.run * 0.8f;
        }
        e.dvx[i] = vx;
        e.dvy[i] = vy;
    }

    // ------------------------------------------------------------------ the way out

    private void nearestSafe() {
        float px = w.playerX(), py = w.playerY();
        safeD = -1;
        safeKind = -1;
        for (float[] q : w.strat.safePoints()) {
            float dx = q[0] - px, dy = q[1] - py, d = (float) Math.sqrt(dx * dx + dy * dy);
            if (safeD < 0 || d < safeD) {
                safeD = d;
                safeX = q[0];
                safeY = q[1];
                safeKind = (int) q[2];
            }
        }
    }

    /** The game was won by reaching a safe place: at a survivor settlement you join it, with all you bring. */
    private void reached() {
        endSeen = true;
        if (!w.won || w.playerEnt < 0) return;
        int with = 0;
        Ents e = w.e;
        for (int k = 0; k < compN; k++) if (e.loc[comp[k]] == Ents.FREE || e.loc[comp[k]] == Ents.ABOARD) with++;
        float px = w.playerX(), py = w.playerY();
        Society.Colony best = null;
        float bd = 400 * 400;
        for (Society.Colony c : w.society.colonies) {
            if (c.fallen) continue;
            float dx = c.x - px, dy = c.y - py, d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        String extra = "";
        if (best != null) {
            joined = best.name;
            best.food += items[I_FOOD] + items[I_WATER];
            best.ammo += rounds(gun) + ammo[W_PISTOL] + ammo[W_SHOTGUN] + ammo[W_RIFLE];
            if (gun >= 0) best.arms++;
            extra = " You join " + best.name + (with > 0 ? " with " + with + (with == 1 ? " other" : " others") : "") + ", and bring what you carry.";
        } else if (with > 0) extra = " " + with + (with == 1 ? " other came" : " others came") + " with you.";
        if (extra.length() > 0 && w.endText != null) w.endText = w.endText + extra;
    }

    // ------------------------------------------------------------------ the clock

    @Override
    public void update(float dt) {
        if (!enabled || w.strategic) return;
        if (!ready && w.playerEnt >= 0) setUp();
        if (w.ended) {
            if (!endSeen) reached();
            return;
        }
        if (!w.playerAlive()) return;
        Ents e = w.e;
        int p = w.playerEnt;
        companions();
        if (e.loc[p] == Ents.INSIDE) hidden(dt);
        if (!w.playerAlive()) return;
        needs(dt);
        if (!w.playerAlive()) return;
        if (e.loc[p] == Ents.FREE || e.loc[p] == Ents.ABOARD) {
            float px = w.playerX(), py = w.playerY();
            if (trailN == 0 || Math.abs(trailX[trailN - 1] - px) + Math.abs(trailY[trailN - 1] - py) > 12) {
                if (trailN == trailX.length) {
                    System.arraycopy(trailX, 1, trailX, 0, trailN - 1);
                    System.arraycopy(trailY, 1, trailY, 0, trailN - 1);
                    trailN--;
                }
                trailX[trailN] = px;
                trailY[trailN] = py;
                trailN++;
            }
        }
        nearestSafe();
    }

    @Override
    public void step() {
        if (!enabled || w.playerEnt < 0 || !ready) return;
        float dt = World.DT;
        if (meleeT > 0) meleeT -= dt;
        if (shootReq > 0) shootReq -= dt;
        if (!w.playerAlive()) return;
        Ents e = w.e;
        int p = w.playerEnt;
        float jx = w.strat.playerJoyX, jy = w.strat.playerJoyY;
        sprinting = e.loc[p] == Ents.FREE && jx * jx + jy * jy > 0.36f && stamina > 0.02f;
        if (sprinting) stamina = Math.max(0, stamina - dt / w.rule("survSprintSecs", 12));
        else {
            float regen = dt / w.rule("survRecoverSecs", 25) * (resting ? 3 : 1) * (needsOn && rest < 0.2f ? 0.5f : 1);
            stamina = Math.min(1, stamina + regen);
        }
        if (e.loc[p] == Ents.ABOARD) driving();
        else if (car >= 0) driving();
    }

    // ------------------------------------------------------------------ saves

    @Override
    public void save(DataOutputStream o) throws IOException {
        o.writeInt(1);
        o.writeBoolean(ready);
        o.writeBoolean(needsOn);
        o.writeInt(owned);
        o.writeInt(gun);
        o.writeInt(melee);
        for (int k = 0; k < NW; k++) o.writeInt(ammo[k]);
        for (int k = 0; k < NITEMS; k++) o.writeInt(items[k]);
        o.writeFloat(stamina);
        o.writeFloat(food);
        o.writeFloat(water);
        o.writeFloat(rest);
        o.writeBoolean(resting);
        o.writeBoolean(sprinting);
        o.writeInt(inside);
        o.writeFloat(doorPX);
        o.writeFloat(doorPY);
        o.writeInt(doorTile);
        o.writeByte(doorWas);
        o.writeInt(deadInside);
        o.writeFloat(noiseT);
        o.writeInt(searched.size());
        for (int b : searched) o.writeInt(b);
        o.writeInt(searchedIn.size());
        for (Map.Entry<Integer, Integer> en : searchedIn.entrySet()) {
            o.writeInt(en.getKey());
            o.writeInt(en.getValue());
        }
        o.writeInt(looted.size());
        for (int b : looted) o.writeInt(b);
        o.writeBoolean(manual);
        o.writeInt(aimEnt);
        o.writeInt(aimUid);
        o.writeFloat(shootReq);
        o.writeFloat(meleeT);
        o.writeInt(car);
        o.writeInt(carUid);
        o.writeInt(compN);
        for (int k = 0; k < compN; k++) {
            o.writeInt(comp[k]);
            o.writeInt(compUid[k]);
            o.writeInt(order[k]);
            o.writeFloat(stayX[k]);
            o.writeFloat(stayY[k]);
        }
        o.writeInt(trailN);
        for (int k = 0; k < trailN; k++) {
            o.writeFloat(trailX[k]);
            o.writeFloat(trailY[k]);
        }
        o.writeBoolean(endSeen);
        o.writeUTF(joined == null ? "" : joined);
        int[] counts = {searches, finds, meleeKills, shoves, eaten, recruited, foundInside};
        for (int c : counts) o.writeInt(c);
        o.writeUTF(note);
        o.writeDouble(noteAt);
    }

    @Override
    public void load(DataInputStream in) throws IOException {
        in.readInt();
        enabled = true;
        ready = in.readBoolean();
        needsOn = in.readBoolean();
        owned = in.readInt();
        gun = in.readInt();
        melee = in.readInt();
        for (int k = 0; k < NW; k++) ammo[k] = in.readInt();
        for (int k = 0; k < NITEMS; k++) items[k] = in.readInt();
        stamina = in.readFloat();
        food = in.readFloat();
        water = in.readFloat();
        rest = in.readFloat();
        resting = in.readBoolean();
        sprinting = in.readBoolean();
        inside = in.readInt();
        doorPX = in.readFloat();
        doorPY = in.readFloat();
        doorTile = in.readInt();
        doorWas = in.readByte();
        deadInside = in.readInt();
        noiseT = in.readFloat();
        searched.clear();
        for (int k = in.readInt(); k > 0; k--) searched.add(in.readInt());
        searchedIn.clear();
        for (int k = in.readInt(); k > 0; k--) searchedIn.put(in.readInt(), in.readInt());
        looted.clear();
        for (int k = in.readInt(); k > 0; k--) looted.add(in.readInt());
        manual = in.readBoolean();
        aimEnt = in.readInt();
        aimUid = in.readInt();
        shootReq = in.readFloat();
        meleeT = in.readFloat();
        car = in.readInt();
        carUid = in.readInt();
        compN = in.readInt();
        for (int k = 0; k < compN; k++) {
            comp[k] = in.readInt();
            compUid[k] = in.readInt();
            order[k] = in.readInt();
            stayX[k] = in.readFloat();
            stayY[k] = in.readFloat();
        }
        trailN = in.readInt();
        for (int k = 0; k < trailN; k++) {
            trailX[k] = in.readFloat();
            trailY[k] = in.readFloat();
        }
        endSeen = in.readBoolean();
        joined = in.readUTF();
        if (joined.length() == 0) joined = null;
        searches = in.readInt();
        finds = in.readInt();
        meleeKills = in.readInt();
        shoves = in.readInt();
        eaten = in.readInt();
        recruited = in.readInt();
        foundInside = in.readInt();
        note = in.readUTF();
        noteAt = in.readDouble();
    }

    /** The HUD's line: what you carry. */
    @Override
    public String status() {
        return null;
    }
}
