package com.instantaxtion.zcs2;

/**
 * Names for places and people, from content.ini's [names] lists (Data). People's names come from their id and the
 * seed, so they never need saving.
 */
public final class Names {
    private static String[] pre() {
        return Data.names("placePrefixes");
    }

    public static String place(Rng rng, int kind) {
        String[] PRE = pre(), SUF = Data.names("placeSuffixes");
        String base = PRE[rng.nextInt(PRE.length)] + SUF[rng.nextInt(SUF.length)];
        switch (kind) {
            case Region.S_CITY: return (rng.chance(0.4f) ? "New " : rng.chance(0.3f) ? "Port " : "") + base;
            case Region.S_BASE: return "Fort " + PRE[rng.nextInt(PRE.length)] + (rng.chance(0.5f) ? "e" : "");
            case Region.S_PRISON: return PRE[rng.nextInt(PRE.length)] + " County Prison";
            case Region.S_AIRBASE: return PRE[rng.nextInt(PRE.length)] + " Air Force Base";
            case Region.S_NAVAL: return "Naval Station " + PRE[rng.nextInt(PRE.length)] + SUF[rng.nextInt(SUF.length)];
            case Region.S_AIRPORT: return base + " International Airport";
            case Region.S_CAMP: return base + " Camp";
            case Region.S_HOLDOUT: return base + " Holdout";
            default: return base;
        }
    }

    public static String state(Rng rng) {
        String[] PRE = pre(), STATE_SUF = Data.names("stateSuffixes");
        String p = PRE[rng.nextInt(PRE.length)];
        return p + STATE_SUF[rng.nextInt(STATE_SUF.length)];
    }

    public static String country(Rng rng) {
        String[] f = Data.names("countryForms");
        return f[rng.nextInt(f.length)] + " " + state(rng);
    }

    public static String person(long seed, int id) {
        String[] first = Data.names("first"), last = Data.names("last");
        return first[Rng.hashInt(seed, id, 1, first.length)] + " " + last[Rng.hashInt(seed, id, 2, last.length)];
    }

    public static String surname(long seed, int id) {
        String[] last = Data.names("last");
        return last[Rng.hashInt(seed, id, 2, last.length)];
    }

    /** A radio callsign for a squad or vehicle. */
    public static String callsign(int faction, int id) {
        if (faction == Content.COP) return "Unit " + (10 + id % 90) + "-" + (char) ('A' + (id / 90) % 26);
        String[] alpha = Data.names("callsigns");
        return alpha[id % alpha.length] + " " + (1 + id / alpha.length);
    }
}
