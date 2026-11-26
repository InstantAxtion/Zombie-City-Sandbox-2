package com.instantaxtion.zcs2;

/** Ranks earned by kills; a higher rank aims better and holds longer. */
public final class Ranks {
    private static final String[] COP = {"Officer", "Senior Officer", "Corporal", "Sergeant", "Lieutenant"};
    private static final String[] SOL = {"Private", "Specialist", "Corporal", "Sergeant", "Staff Sergeant"};
    public static final int[] KILLS = {0, 10, 25, 50, 100};

    public static String title(int kind, int rank) {
        String[] t = kind == Ents.COP ? COP : SOL;
        return t[Math.max(0, Math.min(t.length - 1, rank))];
    }

    public static int rankFor(int kills) {
        int r = 0;
        for (int i = 0; i < KILLS.length; i++) if (kills >= KILLS[i]) r = i;
        return r;
    }

    /** Aim bonus for a rank. */
    public static float aim(int rank) {
        return 1f + rank * 0.06f;
    }
}
