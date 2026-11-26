package com.instantaxtion.zcs2;

/** Map tile kinds (one byte each, 16 px square) and what they allow. */
public final class Tile {
    public static final int SIZE = 16;

    public static final byte GRASS = 0, FARM = 1, FOREST = 2, WATER = 3, SAND = 4, ROAD = 5, HIGHWAY = 6, PAVEMENT = 7,
            BUILDING = 8, PARK = 9, BRIDGE = 10, BASE = 11, FENCE = 12, RUBBLE = 13, ROCK = 14, LOT = 15, YARD = 16,
            TREE = 17, DEEP = 18, PLAZA = 19, WALL = 20, RUNWAY = 21, RAIL = 22, BARRICADE = 23, CAMP = 24;
    public static final int KINDS = 25;

    public static final String[] NAMES = {"Grass", "Farmland", "Forest", "Water", "Sand", "Street", "Highway", "Pavement",
            "Building", "Park", "Bridge", "Base", "Fence", "Rubble", "Rock", "Car park", "Yard", "Trees", "Sea", "Plaza",
            "Wall", "Runway", "Railway", "Barricade", "Camp"};

    private static final boolean[] WALK = new boolean[KINDS];
    private static final boolean[] SIGHT_BLOCK = new boolean[KINDS];
    private static final boolean[] ROADISH = new boolean[KINDS];
    private static final float[] SLOW = new float[KINDS];
    /** What the dead can wear down by pressing on it (barricades, fences, walls): its default hit points, 0 = never. */
    private static final float[] SMASH_HP = new float[KINDS];
    /** The colour of each kind on the region map (one pixel per tile). */
    public static final int[] MAP_COLOR = new int[KINDS];

    static {
        for (int i = 0; i < KINDS; i++) {
            WALK[i] = true;
            SLOW[i] = 1f;
        }
        WALK[WATER] = WALK[BUILDING] = WALK[FENCE] = WALK[ROCK] = WALK[DEEP] = WALK[WALL] = WALK[BARRICADE] = false;
        SIGHT_BLOCK[BUILDING] = SIGHT_BLOCK[ROCK] = SIGHT_BLOCK[WALL] = true;
        ROADISH[ROAD] = ROADISH[HIGHWAY] = ROADISH[BRIDGE] = ROADISH[RUNWAY] = true;
        SLOW[FOREST] = 0.6f;
        SLOW[FARM] = 0.85f;
        SLOW[SAND] = 0.8f;
        SLOW[RUBBLE] = 0.7f;
        SLOW[TREE] = 0.8f;
        SMASH_HP[BARRICADE] = 120;
        SMASH_HP[FENCE] = 60;
        SMASH_HP[WALL] = 400;
        int[] c = MAP_COLOR;
        c[GRASS] = 0xFF6E8F4E;
        c[FARM] = 0xFF9C9A55;
        c[FOREST] = 0xFF3E6337;
        c[WATER] = 0xFF3C6E9A;
        c[SAND] = 0xFFD8C890;
        c[ROAD] = 0xFF4A4A4E;
        c[HIGHWAY] = 0xFF3A3A3E;
        c[PAVEMENT] = 0xFF9A9A96;
        c[BUILDING] = 0xFF8C7F72;
        c[PARK] = 0xFF5F9A4A;
        c[BRIDGE] = 0xFF6A6460;
        c[BASE] = 0xFF8E8A6A;
        c[FENCE] = 0xFF505050;
        c[RUBBLE] = 0xFF7A7068;
        c[ROCK] = 0xFF8A857C;
        c[LOT] = 0xFF6A6A6A;
        c[YARD] = 0xFF78984F;
        c[TREE] = 0xFF41703A;
        c[DEEP] = 0xFF2C5880;
        c[PLAZA] = 0xFFB0A898;
        c[WALL] = 0xFF6A6058;
        c[RUNWAY] = 0xFF505052;
        c[RAIL] = 0xFF6E5E52;
        c[BARRICADE] = 0xFFB09040;
        c[CAMP] = 0xFFA89870;
    }

    public static boolean walkable(int t) {
        return WALK[t];
    }

    public static boolean blocksSight(int t) {
        return SIGHT_BLOCK[t];
    }

    public static boolean roadish(int t) {
        return ROADISH[t];
    }

    public static float slow(int t) {
        return SLOW[t];
    }

    /** True if the dead can break this down (it turns to rubble when its hit points are gone). */
    public static boolean smashable(int t) {
        return SMASH_HP[t] > 0;
    }

    /** The hit points of a smashable tile when no rule overrides them (0 if it can't be smashed). */
    public static float smashHp(int t) {
        return SMASH_HP[t];
    }

    public static boolean water(int t) {
        return t == WATER || t == DEEP;
    }
}
